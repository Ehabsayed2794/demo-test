package com.estemshan.functions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S59 — the season-reset job's Firestore port (RD24, R27).
 *
 * Two distinct surfaces, because the job does two distinct things:
 *
 * - [transact] guards the ONE season document (`seasons/current`). Every claim,
 *   resume decision, revert and final transition happens inside it, so the
 *   season cannot be claimed twice by two racing schedulers. The Admin SDK's
 *   `runTransaction` may RE-RUN its callback (a contested read retries), so an
 *   implementation must read before it writes and hold no state between runs.
 * - [readAllProfiles] / [writeProfiles] are the bulk migration: one bounded
 *   `players` read, then field-level writes in caller-sized batches. Both use
 *   `update`, never `set` — the reset MERGES six progression fields and a full
 *   document replace would erase career statistics, which R27's irreversible
 *   migration could never restore.
 *
 * Reads and writes for the season document are separated on [Tx] on purpose:
 * the document is read, decided on, and written in that order inside one
 * callback, and there is no second write to the same transaction.
 */
interface SeasonResetDb {
  suspend fun <T> transact(block: suspend (Tx) -> T): T

  interface Tx {
    /** `seasons/current`, or null when the document does not exist. */
    suspend fun readSeason(): Map<String, Any?>?

    /**
     * Field-level merge of [fields] onto `seasons/current`. The job never
     * creates the document (`set`) and never writes a field it did not read.
     */
    fun writeSeason(fields: Map<String, Any?>)
  }

  /**
   * Every `players` profile, unfiltered by season: the reset must see a profile
   * whose season is foreign (a failure) and a legacy profile with no season at
   * all (s1 only), not just the ones it can move.
   */
  suspend fun readAllProfiles(): List<SeasonProfile>

  /** One field-level batch write, one `update` per entry, one commit. */
  suspend fun writeProfiles(updates: List<Pair<String, Map<String, Any?>>>)

  /**
   * `config/seasonReset` — the deploy-time kill switch. Null when the document
   * does not exist; the scheduler treats that as DRY_RUN.
   */
  suspend fun readGateConfig(): SeasonResetGateConfig?
}

/** One persisted profile: the document id (the player's uid) and its raw fields. */
data class SeasonProfile(val playerId: String, val fields: Map<String, Any?>)

/**
 * How many profiles one reset may read.
 *
 * The reset is all-or-nothing on an irreversible migration (R27), so it cannot
 * be paginated: the plan needs the whole population before the first write.
 * 10,000 is a ceiling on what one run may cost, not a limit on the game's
 * population; a season over it is REFUSED, never truncated, because a partial
 * reset is the one outcome R27 cannot roll back.
 */
internal const val MAX_RESET_PROFILES = 10_000

private const val PLAYERS_COLLECTION = "players"

private const val CONFIG_COLLECTION = "config"
private const val SEASON_RESET_GATE_DOC = "seasonReset"

/**
 * Admin-SDK implementation. Bypasses firestore.rules like every other Admin
 * path; the rules file stays byte-identical.
 *
 * `getFirestore()` resolves the DEFAULT app, which firebase-admin never creates
 * on its own — see [ensureDefaultAdminApp], which must run before it.
 */
class AdminSeasonResetDb : SeasonResetDb {
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()
  private val seasonRef: DocRef =
    db.collection(SEASONS_COLLECTION).doc(CURRENT_SEASON_DOC)
  private val gateRef: DocRef =
    db.collection(CONFIG_COLLECTION).doc(SEASON_RESET_GATE_DOC)

  override suspend fun <T> transact(block: suspend (SeasonResetDb.Tx) -> T): T {
    // The callback may be re-invoked after a contested read. Each invocation
    // gets a fresh AdminTx and [block] re-reads before it decides, so a retry
    // re-plans against the winning state instead of replaying a stale write.
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : SeasonResetDb.Tx {
    override suspend fun readSeason(): Map<String, Any?>? {
      val snapshot = tx.get(seasonRef).await()
      if (!snapshot.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snapshot.data()) as? Map<String, Any?>
    }

    override fun writeSeason(fields: Map<String, Any?>) {
      // update() is a field-level merge: status/target change without touching
      // seasonId, endsAt or any field written by a later step.
      tx.update(seasonRef, toPlain(fields))
    }
  }

  override suspend fun readAllProfiles(): List<SeasonProfile> {
    val snapshot = db.collection(PLAYERS_COLLECTION)
      // One past the cap, so "at the cap" and "over it" are distinguishable
      // without a second read. With no limit the SDK pulls the whole
      // collection into one run.
      .limit(MAX_RESET_PROFILES + 1)
      .get().await()
    val docs = snapshot.docs
    if (docs.size > MAX_RESET_PROFILES) {
      // Refused before a single profile is classified and long before the
      // first write: a truncated plan would reset an arbitrary subset of an
      // irreversible migration.
      throw HttpsError(
        "resource-exhausted",
        "season reset: more than $MAX_RESET_PROFILES player profiles; " +
          "the migration is refused rather than run on a partial population (R27).",
      )
    }
    return docs.map { doc ->
      @Suppress("UNCHECKED_CAST")
      SeasonProfile(
        playerId = doc.id,
        fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: emptyMap(),
      )
    }
  }

  override suspend fun readGateConfig(): SeasonResetGateConfig? {
    val snapshot = gateRef.get().await()
    if (!snapshot.exists) return null
    @Suppress("UNCHECKED_CAST")
    val data = dynamicToKotlin(snapshot.data()) as? Map<String, Any?> ?: return null
    // Every field is read fail-closed: a wrong type is treated as absent, and
    // an absent enabled flag is false, never an implicit "yes".
    return SeasonResetGateConfig(
      enabled = data["enabled"] as? Boolean ?: false,
      targetSeasonId = (data["targetSeasonId"] as? String)?.takeIf { it.isNotBlank() },
      environment = (data["environment"] as? String)?.takeIf { it.isNotBlank() },
    )
  }

  override suspend fun writeProfiles(updates: List<Pair<String, Map<String, Any?>>>) {
    if (updates.isEmpty()) return
    // ONE batch per call, and the caller keeps calls <= 400 writes: chunking is
    // the caller's rule because only it knows how much progress a failed chunk
    // should cost on a resume.
    val batch = db.batch()
    for ((playerId, fields) in updates) {
      batch.update(db.collection(PLAYERS_COLLECTION).doc(playerId), toPlain(fields))
    }
    batch.commit().await()
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
