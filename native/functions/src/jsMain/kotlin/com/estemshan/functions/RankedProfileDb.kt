package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.PlacementState
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.RankedStats
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

/** The first season's ID; the model's stable default until S59 creates seasons. */
private const val DEFAULT_SEASON_ID = "s1"

/**
 * Reads the caller's Ranked profile and lazily initialises missing fields.
 *
 * Auth is verified before the transaction, so an unauthenticated request reads
 * nothing. A missing `players/{uid}` fails with `failed-precondition` — a read
 * callable must not create a profile as a side effect. Every corrupt value also
 * fails closed before the merge, so a failed call can never leave a partial
 * migration behind.
 */
suspend fun loadOnce(db: RankedProfileDb, request: CallableRequest): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)

  return db.transact { tx ->
    val stored = tx.read(uid)
      ?: throw HttpsError(
        "failed-precondition",
        "getMyRankedProfile: no profile document exists for this account.",
      )

    val normalized = normalizeProfile(stored)
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
 */
private fun normalizeProfile(stored: Map<String, Any?>): NormalizedProfile {
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
  val seasonId = if (hasSeason) {
    presentString(stored, FIELD_SEASON_ID).also {
      if (it.isBlank()) corrupt("seasonId must be non-blank.")
    }
  } else {
    DEFAULT_SEASON_ID
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
