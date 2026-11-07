package com.estemshan.functions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

private const val CLAIM_PATH = "settlement"
private const val CLAIM_DOC = "settle"

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through translate + toPlain. */
class AdminSettlementDb : SettlementDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef =
    db.collection("matches").doc(matchId)

  /** The Ranked progression document — the only one settlement writes to. */
  private fun playerRef(uid: String): DocRef = db.collection("players").doc(uid)

  override suspend fun readMatch(matchId: String): Map<String, Any?>? {
    val snap = matchRef(matchId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
    val snapshot = matchRef(matchId).collection("roundArchive").get().await()
    val out = LinkedHashMap<String, Map<String, Any?>>()
    for (doc in snapshot.docs) {
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: continue
      out[doc.id] = fields
    }
    return out
  }

  override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  override suspend fun appendLedger(record: Map<String, Any?>) {
    // S62: create-if-absent in its own Admin transaction, keyed by
    // SettlementLedger.ledgerDocId - the append is idempotent even if the
    // same event is ever delivered twice, and a failure here cannot touch
    // the settlement (that transaction has already committed).
    // appendTargets decides which collections the row lands in, and they all
    // commit together: the corrections window can never hold a row the ledger
    // lacks, or miss one it should mirror.
    val docId = SettlementLedger.ledgerDocId(record) ?: return
    val refs = SettlementLedger.appendTargets(record).map { collection ->
      db.collection(collection).doc(docId)
    }
    db.runTransaction { tx ->
      TxScope.promise {
        // Firestore requires EVERY read in a transaction to precede EVERY
        // write (the SDK throws on a get after a set), so the create-if-absent
        // test is done for all targets first and only then written.
        val existed = refs.map { tx.get(it).await().exists }
        // record["appendedAt"] is the core's ServerTimestampSentinel — resolved
        // here, at the commit boundary, like every other authority write.
        val plain = toPlain(Sentinels.translate(record))
        refs.forEachIndexed { index, ref ->
          if (!existed[index]) tx.set(ref, plain)
        }
      }
    }.await()
  }

  override suspend fun readCorrectionWindow(): List<Map<String, Any?>> {
    // S62: newest-first over ONE field of the corrections collection, so the
    // window is genuinely "the last DETECT_WINDOW corrections" and includes
    // the record the correction just wrote. An un-ordered equality query on
    // the ledger instead would return index order, not recency, and would
    // freeze on the oldest documents once the collection grows - silently
    // blinding the detector. Mirror the correction at write time and the read
    // stays on the automatic single-field index: no composite index, no
    // indexes artifact, no deployment.
    val snapshot = db.collection(SettlementLedger.COLLECTION_CORRECTIONS)
      .orderBy("appendedAt", "desc")
      .limit(SettlementLedger.DETECT_WINDOW)
      .get().await()
    return snapshot.docs.mapNotNull { doc ->
      @Suppress("UNCHECKED_CAST")
      dynamicToKotlin(doc.data()) as? Map<String, Any?>
    }
  }

  private inner class AdminTx(private val tx: Transaction) : SettlementDb.SettlementTx {
    override suspend fun readClaim(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      // A present-but-unparseable claim must fail closed — returning null
      // here would let a second settlement double-apply (same fix as the DO
      // adapter).
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
        ?: throw AuthorityError(
          "failed-precondition",
          "settleMatch: settlement record for '$matchId' is malformed.",
        )
    }

    override suspend fun readProfile(uid: String): Map<String, Any?>? {
      val snap = tx.get(playerRef(uid)).await()
      // Absent, not empty: a missing profile document is a fail-closed
      // condition for the whole settlement, while an existing document with no
      // `rp` field is a different thing (an unplaced account, RD30).
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    /**
     * S59's singleton, read INSIDE the transaction and before any write (Q22,
     * V1-C10): the season a stale seat is lazily reset into is exactly the
     * season this transaction commits into, so a season flipping mid-call
     * cannot stamp a profile with a season the write did not land in. Absent
     * answers NULL — the fresh-deployment bootstrap the alignment guards —
     * while an existing document is authoritative and parsed by
     * [lazyResetSeasonOf]: the in-flight TARGET while a reset is RUNNING (a
     * seat the sweep already moved must not be reset backwards), otherwise
     * the outgoing `seasonId` the board still serves (malformed fails closed).
     */
    override suspend fun currentLazyResetSeason(): String? {
      val snap = tx.get(db.collection(SEASONS_COLLECTION).doc(CURRENT_SEASON_DOC)).await()
      if (!snap.exists) return null
      return lazyResetSeasonOf(exists = true, data = dynamicToKotlin(snap.data()))
    }

    override fun writeCorrection(
      matchId: String,
      scores: Map<String, Int>,
      winners: List<String>,
      version: Int,
    ) {
      tx.update(
        matchRef(matchId),
        toPlain(
          Sentinels.translate(
            mapOf(
              "finalScores" to scores,
              "winnerIds" to winners,
              "version" to version,
            ),
          ),
        ),
      )
    }

    override fun writeProfile(uid: String, fields: Map<String, Any?>) {
      // update(), not set(): it merges these fields into the profile and
      // leaves everything else the account holds (name, avatar, room/match
      // pointers, the legacy `rank`, and every field this path does not own)
      // exactly as it is.
      tx.update(playerRef(uid), toPlain(fields))
    }

    override fun writeClaim(matchId: String, record: Map<String, Any?>) {
      // record["settledAt"] is the core's ServerTimestampSentinel — resolved
      // here, at the commit boundary.
      tx.set(
        matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC),
        toPlain(Sentinels.translate(record)),
      )
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
