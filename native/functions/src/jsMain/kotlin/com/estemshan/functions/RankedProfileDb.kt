package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.PlacementState
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.RankedStats
import com.estemshan.engine.applySeasonReset
import com.estemshan.engine.progressionMismatch
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S44/S51 — the Ranked profile read/normalisation layer.
 *
 * `players/{uid}` predates the Ranked model: a legacy profile carries only the
 * write-once `rank`/`rp` pair (rules force `rank == 'Unranked'`, `rp == 0` at
 * creation), while the model's identity block, placement choice, shield and
 * career counters arrived later. [loadOnce] reads the caller's own document in
 * one Admin transaction, normalises it into the engine's [RankedProfile] and
 * [RankedStats], and initialises ONLY the missing Ranked fields. It never
 * creates a profile, never writes an unrelated field, and never rewrites a
 * value that is already present and valid.
 *
 * The port keeps every Firestore touch behind an interface so the whole
 * normalization matrix is unit-testable with a fake; only [AdminRankedProfileDb]
 * loads the Admin SDK. `players/{uid}` is read by the verified caller's uid —
 * there is no list query and no other-player access anywhere in this file.
 */
interface RankedProfileDb {
  suspend fun <T> transact(block: suspend (Tx) -> T): T

  interface Tx {
    suspend fun read(uid: String): Map<String, Any?>?

    /**
     * The current season, as S44/S51 normalizes the profile against it — read
     * from the S59 singleton (`seasons/current`) INSIDE the same transaction
     * as [read] and [merge], so the profile is judged against exactly the
     * season a merge would commit into. [loadOnce] performs this read second,
     * after the profile: every read precedes every write, as Firestore's
     * transaction rule requires.
     *
     * NULL means the singleton does not exist — the fresh-deployment
     * bootstrap: the Admin implementation answers null and the normalization
     * serves the bootstrap season to a profile that carries no season beyond
     * it, while a profile already in a NON-default season fails closed rather
     * than being silently reset (nobody declared that season dead). The
     * DEFAULT is the bootstrap literal itself — which keeps every existing
     * fake compiling and reproduces the pre-Q22 read — and an EXISTING
     * document is authoritative, parsed by [currentSeasonIdOf] and failing
     * closed on a malformed one.
     */
    suspend fun currentSeasonId(): String? = DEFAULT_SEASON_ID

    /**
     * Field-level merge of [fields] onto `players/{uid}`. The caller passes
     * only missing/repair Ranked fields; the implementation must not replace
     * the document or touch any other key.
     */
    fun merge(uid: String, fields: Map<String, Any?>)
  }
}

private const val COLLECTION_PLAYERS = "players"

private const val FIELD_RANK = "rank"
private const val FIELD_RP = "rp"
private const val FIELD_TIER = "tier"
private const val FIELD_DIVISION = "division"
private const val FIELD_SEASON_ID = "seasonId"
private const val FIELD_HIGHEST_RANK_TIER = "highestRankTier"
private const val FIELD_HIGHEST_RANK_DIVISION = "highestRankDivision"
private const val FIELD_PLACEMENT_STATE = "placementState"
private const val FIELD_DEMOTION_SHIELD = "demotionShield"

private const val FIELD_RANKED_GAMES_PLAYED = "rankedGamesPlayed"
private const val FIELD_RANKED_KING_COUNT = "rankedKingCount"
private const val FIELD_RANKED_SECOND_COUNT = "rankedSecondCount"
private const val FIELD_RANKED_THIRD_COUNT = "rankedThirdCount"
private const val FIELD_RANKED_KOZ_COUNT = "rankedKozCount"

/**
 * The bootstrap season (`s1`): the season an absent `seasonId` adopts, and the
 * EFFECTIVE current season when the `seasons/current` singleton does not exist
 * — for a profile that carries no season beyond it. A profile already in a
 * non-default season with no singleton to judge it by fails closed instead of
 * being reset to this literal. This is also the default [RankedProfileDb.Tx]
 * answer, what a current-season read resolves to under a fake or a fresh
 * deployment; an existing document is authoritative and its parsing lives in
 * [currentSeasonIdOf] — no normalization here falls back to this literal on
 * its own.
 */
private const val DEFAULT_SEASON_ID = "s1"

/**
 * Reads the caller's Ranked profile and lazily initialises missing fields.
 *
 * Auth is verified before the transaction, so an unauthenticated request reads
 * nothing. A missing `players/{uid}` fails with `failed-precondition` — a read
 * callable must not create a profile as a side effect. Every corrupt value also
 * fails closed before the merge, so a failed call can never leave a partial
 * migration behind. A profile whose recorded season is behind the current one
 * is the ONE value this path repairs instead: Q22's lazy reset (S64, V1-C10)
 * resets it exactly once on this first access, in the same transaction, because
 * the bulk sweep is a bounded backfill and must not be the only mechanism. The
 * current season is read inside the transaction, after the profile and before
 * any write, because all reads must precede all writes.
 */
suspend fun loadOnce(db: RankedProfileDb, request: CallableRequest): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)

  return db.transact { tx ->
    val stored = tx.read(uid)
      ?: throw HttpsError(
        "failed-precondition",
        "getMyRankedProfile: no profile document exists for this account.",
      )

    // S64: profile → season → merge. Both reads happen before the merge, as
    // the transaction requires; the season read is second so a profile that
    // does not exist is still reported without opening the season singleton.
    // Null means the singleton itself is absent (the bootstrap): the
    // normalization serves the bootstrap season only to a profile that
    // carries no season beyond it.
    val currentSeasonId = tx.currentSeasonId()

    val normalized = normalizeProfile(stored, currentSeasonId)
    if (normalized.mergeFields.isNotEmpty()) {
      tx.merge(uid, normalized.mergeFields)
    }
    responseOf(normalized, migrated = normalized.mergeFields.isNotEmpty())
  }
}

private data class NormalizedProfile(
  val profile: RankedProfile,
  val stats: RankedStats,
  val mergeFields: Map<String, Any?>,
)

/**
 * Turns the stored document into the model, deriving defaults only where the
 * field is absent. Present-but-invalid data throws instead of being repaired:
 * a corrupt RP or a foreign rung is not something a read callable may silently
 * reinterpret.
 *
 * The season is judged against the current one (S64): an absent `seasonId`
 * adopts the current season (or the bootstrap literal when the singleton
 * itself is absent) under the lazy merge; a present one equal to the current
 * season is served untouched; a present-but-blank one is corrupt; a present
 * one naming a DIFFERENT season missed the bulk sweep — Q22's lazy reset
 * (V1-C10) moves it into the current season exactly ONCE, here in the same
 * transaction, however many seasons it slept through. One bootstrap guard
 * rides on top: with NO singleton at all, only the bootstrap season may be
 * served or adopted — a profile already in a non-default season fails closed,
 * because a lazy reset to the bootstrap season would be a reset no season
 * declared.
 */
private fun normalizeProfile(stored: Map<String, Any?>, currentSeasonId: String?): NormalizedProfile {
  val hasRp = stored.containsKey(FIELD_RP)
  val rp = if (hasRp) nonNegativeInt(stored.getValue(FIELD_RP), FIELD_RP) else 0

  val hasTier = stored.containsKey(FIELD_TIER)
  val hasDivision = stored.containsKey(FIELD_DIVISION)
  val current: LadderRank = when {
    hasTier && hasDivision -> rungOf(
      presentString(stored, FIELD_TIER),
      presentString(stored, FIELD_DIVISION),
    )
    !hasTier && !hasDivision -> rankAt(rp)
    else -> corrupt("tier and division must be present together.")
  }

  // A missing RP on a higher rung would award the player a lost counter (0 RP)
  // beside an identity the server did not compute. Only the ladder's bottom
  // rung is representable by the absent-RP default.
  if (!hasRp && rankLadder.indexOf(current) > 0) {
    corrupt("rp is missing while tier/division name a rung above Bronze III.")
  }

  val hasSeason = stored.containsKey(FIELD_SEASON_ID)
  val seasonId: String = if (hasSeason) {
    val recorded = presentString(stored, FIELD_SEASON_ID)
    when {
      recorded.isBlank() -> corrupt("seasonId must be non-blank.")
      // No singleton at all: only the bootstrap season is defensible as the
      // current one. A profile IN the bootstrap season is served as current
      // (the fresh-deployment read); any other recorded season is a season
      // state the bootstrap must not silently overwrite — resetting it to
      // the bootstrap literal would demote a player against a season nobody
      // declared, so the read fails loud before any merge can run.
      currentSeasonId == null -> when {
        recorded == DEFAULT_SEASON_ID -> recorded
        else -> corrupt(
          "profile season '$recorded' cannot be served while seasons/current does not " +
            "exist — it is not the bootstrap season '$DEFAULT_SEASON_ID', and a lazy " +
            "reset to the bootstrap season would be a reset no season declared.",
        )
      }
      // The profile's own season IS the current one: served as-is, no season
      // merge — a profile the reset has already stamped must not be rewritten.
      recorded == currentSeasonId -> recorded
      // A season the reset has moved past missed the bulk sweep: ONE lazy
      // reset on this first access (Q22, V1-C10), regardless of how many
      // seasons were missed — the engine's reset math is defined against the
      // rung, not against a season count, and no record is fabricated for a
      // season the player never played in (RD24).
      else -> return lazySeasonReset(stored, recorded, currentSeasonId)
    }
  } else {
    // Lazy initialization: a profile written before seasons existed becomes
    // the CURRENT season, merged below — whatever the singleton says, not a
    // hardcoded first season; a missing singleton adopts the bootstrap.
    currentSeasonId ?: DEFAULT_SEASON_ID
  }

  val hasHighestTier = stored.containsKey(FIELD_HIGHEST_RANK_TIER)
  val hasHighestDivision = stored.containsKey(FIELD_HIGHEST_RANK_DIVISION)
  val storedHighest: LadderRank? = when {
    hasHighestTier && hasHighestDivision -> rungOf(
      presentString(stored, FIELD_HIGHEST_RANK_TIER),
      presentString(stored, FIELD_HIGHEST_RANK_DIVISION),
    )
    !hasHighestTier && !hasHighestDivision -> null
    else -> corrupt("highestRankTier and highestRankDivision must be present together.")
  }
  val highest = when {
    storedHighest == null -> current
    rankLadder.indexOf(storedHighest) < rankLadder.indexOf(current) -> current
    else -> storedHighest
  }

  val hasPlacement = stored.containsKey(FIELD_PLACEMENT_STATE)
  val placementState = if (hasPlacement) {
    placementStateOf(presentString(stored, FIELD_PLACEMENT_STATE))
  } else if (rp > 0 || legacyRankImpliesPlacement(stored)) {
    PlacementState.SKIPPED
  } else {
    PlacementState.NOT_STARTED
  }

  val hasShield = stored.containsKey(FIELD_DEMOTION_SHIELD)
  val demotionShield = if (hasShield) {
    stored.getValue(FIELD_DEMOTION_SHIELD) as? Boolean
      ?: corrupt("demotionShield must be Boolean.")
  } else {
    false
  }

  val stats = statsOf(stored)

  val merge = linkedMapOf<String, Any?>()
  if (!hasTier && !hasDivision) {
    merge[FIELD_TIER] = current.tier
    merge[FIELD_DIVISION] = current.division
  }
  if (!hasRp) merge[FIELD_RP] = rp
  if (!hasSeason) merge[FIELD_SEASON_ID] = seasonId
  // Missing highest fields are initialised; a stale highest below the current
  // rung is the model's one repair (the highest-ever invariant only raises).
  if (storedHighest == null || highest != storedHighest) {
    merge[FIELD_HIGHEST_RANK_TIER] = highest.tier
    merge[FIELD_HIGHEST_RANK_DIVISION] = highest.division
  }
  if (!hasPlacement) merge[FIELD_PLACEMENT_STATE] = placementState.name
  if (!hasShield) merge[FIELD_DEMOTION_SHIELD] = demotionShield

  if (!stored.containsKey(FIELD_RANKED_GAMES_PLAYED)) {
    merge[FIELD_RANKED_GAMES_PLAYED] = stats.gamesPlayed
  }
  if (!stored.containsKey(FIELD_RANKED_KING_COUNT)) {
    merge[FIELD_RANKED_KING_COUNT] = stats.kingCount
  }
  if (!stored.containsKey(FIELD_RANKED_SECOND_COUNT)) {
    merge[FIELD_RANKED_SECOND_COUNT] = stats.secondCount
  }
  if (!stored.containsKey(FIELD_RANKED_THIRD_COUNT)) {
    merge[FIELD_RANKED_THIRD_COUNT] = stats.thirdCount
  }
  if (!stored.containsKey(FIELD_RANKED_KOZ_COUNT)) {
    merge[FIELD_RANKED_KOZ_COUNT] = stats.kozCount
  }

  val profile = RankedProfile(
    tier = current.tier,
    division = current.division,
    rp = rp,
    seasonId = seasonId,
    highestRank = highest,
    placementState = placementState,
    demotionShield = demotionShield,
  )
  // RD3 — a present tier/division pair and a present rp must name the same
  // rung. The pair was already resolved by rungOf above, so the helper returns
  // null for a consistent profile or the mismatch message for a disagreeing
  // one; either way it is never repaired here — the read fails closed before
  // any merge can run, exactly like every other corrupt value.
  progressionMismatch(profile)?.let { corrupt(it) }
  return NormalizedProfile(profile = profile, stats = stats, mergeFields = merge)
}

/**
 * Q22's lazy reset, read side (S64, V1-C10): the stored document is parsed
 * under its RECORDED season — [parseStoredProfile], the same fail-closed
 * defaults the bulk sweep plans from, so a legacy profile parses here too —
 * the engine's [applySeasonReset] moves it into the current season, and ONE
 * merge carries the six progression fields ([progressionFields] — the sweep's
 * own list, never a second copy of the reset math) plus whatever lazy-init
 * fields are still missing. Placement, the shield and the career statistics
 * are carried over untouched: the engine preserves the first two by contract
 * and the statistics are simply not in the merge.
 *
 * Exactly ONE reset lands no matter how many seasons were slept through:
 * [applySeasonReset] is a mapping from rung to rung, not a per-season loop.
 * A document that cannot be parsed under its recorded season stays
 * fail-closed — the callable never writes a partial or guessed reset, so the
 * second read of an unrepaired profile still fails rather than half-moving
 * it.
 */
private fun lazySeasonReset(
  stored: Map<String, Any?>,
  recorded: String,
  currentSeasonId: String,
): NormalizedProfile {
  val preReset = when (val parsed = parseStoredProfile(stored, recorded)) {
    is ProfileParse.Parsed -> parsed.profile
    is ProfileParse.Failed -> corrupt(
      "profile of season '$recorded' cannot be lazily reset into '$currentSeasonId': ${parsed.reason}",
    )
  }
  val reset = applySeasonReset(preReset, currentSeasonId)
  val stats = statsOf(stored)

  val merge = linkedMapOf<String, Any?>()
  merge.putAll(progressionFields(reset))
  if (!stored.containsKey(FIELD_PLACEMENT_STATE)) {
    merge[FIELD_PLACEMENT_STATE] = reset.placementState.name
  }
  if (!stored.containsKey(FIELD_DEMOTION_SHIELD)) {
    merge[FIELD_DEMOTION_SHIELD] = reset.demotionShield
  }
  if (!stored.containsKey(FIELD_RANKED_GAMES_PLAYED)) {
    merge[FIELD_RANKED_GAMES_PLAYED] = stats.gamesPlayed
  }
  if (!stored.containsKey(FIELD_RANKED_KING_COUNT)) {
    merge[FIELD_RANKED_KING_COUNT] = stats.kingCount
  }
  if (!stored.containsKey(FIELD_RANKED_SECOND_COUNT)) {
    merge[FIELD_RANKED_SECOND_COUNT] = stats.secondCount
  }
  if (!stored.containsKey(FIELD_RANKED_THIRD_COUNT)) {
    merge[FIELD_RANKED_THIRD_COUNT] = stats.thirdCount
  }
  if (!stored.containsKey(FIELD_RANKED_KOZ_COUNT)) {
    merge[FIELD_RANKED_KOZ_COUNT] = stats.kozCount
  }
  return NormalizedProfile(profile = reset, stats = stats, mergeFields = merge)
}

private fun statsOf(stored: Map<String, Any?>): RankedStats = RankedStats(
  gamesPlayed = optionalNonNegativeInt(stored, FIELD_RANKED_GAMES_PLAYED) ?: 0,
  kingCount = optionalNonNegativeInt(stored, FIELD_RANKED_KING_COUNT) ?: 0,
  secondCount = optionalNonNegativeInt(stored, FIELD_RANKED_SECOND_COUNT) ?: 0,
  thirdCount = optionalNonNegativeInt(stored, FIELD_RANKED_THIRD_COUNT) ?: 0,
  kozCount = optionalNonNegativeInt(stored, FIELD_RANKED_KOZ_COUNT) ?: 0,
)

/** The legacy profile's `rank` string, if it was set beyond the fresh-account default. */
private fun legacyRankImpliesPlacement(stored: Map<String, Any?>): Boolean {
  val legacyRank = stored[FIELD_RANK]
  return legacyRank != null && legacyRank != "Unranked"
}

private fun placementStateOf(name: String): PlacementState =
  PlacementState.values().firstOrNull { it.name == name }
    ?: corrupt("placementState '$name' is not a PlacementState name.")

private fun rungOf(tier: String, division: String): LadderRank =
  rankLadder.firstOrNull { it.tier == tier && it.division == division }
    ?: corrupt("tier/division '$tier $division' name no ladder rung.")

private fun presentString(stored: Map<String, Any?>, key: String): String =
  stored[key] as? String ?: corrupt("$key must be a string.")

private fun optionalNonNegativeInt(stored: Map<String, Any?>, key: String): Int? =
  if (!stored.containsKey(key)) null else nonNegativeInt(stored.getValue(key), key)

/**
 * A whole, finite, non-negative number. Fractions and values outside Int fail
 * closed rather than being truncated into a plausible-looking counter.
 */
private fun nonNegativeInt(value: Any?, key: String): Int {
  val number = value as? Number ?: corrupt("$key must be a non-negative number.")
  val asDouble = number.toDouble()
  if (!asDouble.isFinite() || asDouble < 0.0 || asDouble % 1.0 != 0.0 ||
    asDouble > Int.MAX_VALUE.toDouble()
  ) {
    corrupt("$key must be a non-negative whole number.")
  }
  return asDouble.toInt()
}

private fun corrupt(message: String): Nothing =
  throw HttpsError("failed-precondition", "getMyRankedProfile: $message")

private fun responseOf(normalized: NormalizedProfile, migrated: Boolean): Map<String, Any?> {
  val profile = normalized.profile
  val stats = normalized.stats
  return linkedMapOf(
    "ok" to true,
    "migrated" to migrated,
    "profile" to linkedMapOf(
      "tier" to profile.tier,
      "division" to profile.division,
      "rp" to profile.rp,
      "seasonId" to profile.seasonId,
      "highestRankTier" to profile.highestRank.tier,
      "highestRankDivision" to profile.highestRank.division,
      "placementState" to profile.placementState.name,
      "demotionShield" to profile.demotionShield,
    ),
    "stats" to linkedMapOf(
      "gamesPlayed" to stats.gamesPlayed,
      "kingCount" to stats.kingCount,
      "kingPct" to stats.kingPct,
      "secondCount" to stats.secondCount,
      "secondPct" to stats.secondPct,
      "thirdCount" to stats.thirdCount,
      "thirdPct" to stats.thirdPct,
      "kozCount" to stats.kozCount,
      "kozPct" to stats.kozPct,
    ),
  )
}

/**
 * Admin-SDK implementation. The read and merge happen inside one transaction,
 * so the merge is a field update on the document the normalization just read —
 * never a blind overwrite and never a create.
 */
class AdminRankedProfileDb : RankedProfileDb {
  init { ensureDefaultAdminApp() }

  private val db = getFirestore()

  private fun playerRef(uid: String): DocRef =
    db.collection(COLLECTION_PLAYERS).doc(uid)

  override suspend fun <T> transact(block: suspend (RankedProfileDb.Tx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : RankedProfileDb.Tx {
    override suspend fun read(uid: String): Map<String, Any?>? {
      val snap = tx.get(playerRef(uid)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    /**
     * S59's singleton, read INSIDE the transaction: the season check and any
     * merge commit against one consistent view, so a season that flips
     * mid-call cannot stamp a profile with a season the write did not land
     * in. Absent answers NULL — the fresh-deployment bootstrap the
     * normalization guards — while an existing document is authoritative,
     * converted through [dynamicToKotlin] exactly as [read]'s is and parsed
     * by [currentSeasonIdOf]'s, shared with the leaderboard path (malformed
     * fails closed).
     */
    override suspend fun currentSeasonId(): String? {
      val snap = tx.get(db.collection(SEASONS_COLLECTION).doc(CURRENT_SEASON_DOC)).await()
      if (!snap.exists) return null
      return currentSeasonIdOf(exists = true, data = dynamicToKotlin(snap.data()))
    }

    override fun merge(uid: String, fields: Map<String, Any?>) {
      // update() is a field-level write: every unrelated key on the profile is
      // left exactly as it was. The document's existence was checked by read().
      tx.update(playerRef(uid), toPlain(fields))
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
