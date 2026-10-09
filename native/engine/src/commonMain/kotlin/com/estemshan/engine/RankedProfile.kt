package com.estemshan.engine

/**
 * S44 — the player's persistent Ranked state: the identity block S45 Ranked
 * Home renders, the S53 placement flow writes, and the S59 season reset
 * demotes. Specified in docs/UI_UX_ROADMAP.md §4b (RD1–RD30) and listed in
 * docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S44).
 *
 * The profile REFERENCES the ladder in RankLadder.kt; it is not a second copy
 * of it. [tier] and [division] are the identity keys of one rung of the single
 * tunable [rankLadder] table — 19 rungs, thresholds and Arabic titles owned
 * there — and [ladderRankOf] resolves them back to that rung. No threshold,
 * ordering, or title is duplicated here, so the ladder stays tunable in one
 * place (RD26).
 *
 * An unplaced account carries Bronze III as a REAL tier, never a "no tier"
 * placeholder (RD30): placement is optional, and declining it is not a
 * penalty. [rp] is the progression counter whose own rung is [rankAt]; the
 * settlement that moves one keeps both consistent.
 *
 * Pure data + pure functions, no I/O.
 */

/**
 * RD10 — placement is once per account, never per season: three scripted
 * matches (Easy+Medium / Medium+Hard / Hard+Expert) whose provisional rank is
 * never shown at any point, only 1/3 → 2/3 → 3/3. RD30 — placement is
 * optional and must be explicitly chosen.
 */
enum class PlacementState {
  /** A fresh account: the S45 two-path choice has not been made yet. */
  NOT_STARTED,

  /** The three scripted matches are underway. Counts toward NO statistic. */
  IN_PROGRESS,

  /**
   * "Start from Bronze" was chosen — irreversible (RD30: once skipped,
   * placement cannot be started later on this account). Bronze III is a real
   * Ranked tier from here, climbed by normal progression, not a "no tier"
   * state.
   */
  SKIPPED,

  /** Placement finished; the S50 Rank Reveal assigned the rank. */
  COMPLETE,
}

data class RankedProfile(
  /** Identity key of the current rung's tier (Bronze … King). */
  val tier: String = "Bronze",
  /** III / II / I, or "" for King — mirrors [LadderRank.division]. */
  val division: String = "III",
  val rp: Int = 0,
  val seasonId: String,
  /**
   * The highest rung ever held. Never erased by a seasonal demotion (RD24) —
   * [updateHighestRank] only ever raises it. Defaults to the ladder's bottom
   * rung: an account that starts at Bronze III has genuinely reached it.
   */
  val highestRank: LadderRank = rankLadder.first(),
  val placementState: PlacementState = PlacementState.NOT_STARTED,
)

/**
 * Derive the profile's current ladder rung by resolving [RankedProfile.tier]
 * + [RankedProfile.division] against the single [rankLadder] table. This is
 * the profile's rank — with its Arabic title and lower bound for S46's
 * ladder draw and S51's progress bar — without a second copy of the ladder.
 *
 * Fails loudly on a pair that names no rung: a persisted profile always
 * holds one (Bronze III by default — RD30's "never a no-tier placeholder").
 */
fun ladderRankOf(profile: RankedProfile): LadderRank {
  val rung = rankLadder.firstOrNull { it.tier == profile.tier && it.division == profile.division }
  require(rung != null) {
    "RankedProfile names no ladder rung: tier='${profile.tier}', division='${profile.division}'"
  }
  return rung
}

/** RD10: the account is mid-placement — its matches count toward nothing. */
fun isPlacement(profile: RankedProfile): Boolean =
  profile.placementState == PlacementState.IN_PROGRESS

/**
 * RD30: placement was declined — permanently. The account's Bronze III is a
 * real tier, and placement can never be started on it again.
 */
fun hasSkippedPlacement(profile: RankedProfile): Boolean =
  profile.placementState == PlacementState.SKIPPED

/**
 * RD10 + RD30: the S45 two-path choice may still offer placement. Only a
 * account that has never decided can start it — once skipped, placement
 * cannot be started later (RD30); once finished or underway, it never
 * repeats (RD10: once per account, never per season).
 */
fun canStartPlacement(profile: RankedProfile): Boolean =
  profile.placementState == PlacementState.NOT_STARTED

/**
 * RD10 — enter the three scripted placement matches from a fresh account. The
 * account sits at an unplaced Bronze III that is NEVER displayed (S48 shows
 * only 1/3 → 3/3; a provisional rank is never teased); the rank stays hidden
 * until the S50 reveal writes the final one.
 */
fun beginPlacement(seasonId: String): RankedProfile = RankedProfile(
  seasonId = seasonId,
  placementState = PlacementState.IN_PROGRESS,
)

/**
 * RD30 — "Start from Bronze": declining placement hands the account a REAL
 * Bronze III rung at its lower bound, climbed by normal Ranked progression.
 * Presented as an equal-dignity choice, never a downgrade. Irreversible —
 * once [PlacementState.SKIPPED], [beginPlacement] is closed on this account.
 */
fun skipPlacement(seasonId: String): RankedProfile {
  val bronze = rankLadder.first()
  return RankedProfile(
    tier = bronze.tier,
    division = bronze.division,
    rp = bronze.lowerBound,
    seasonId = seasonId,
    highestRank = bronze,
    placementState = PlacementState.SKIPPED,
  )
}

/**
 * Record [rank] as the new highest when it sits above the recorded one;
 * otherwise return the profile untouched. Never lowers: a seasonal demotion
 * (RD24's two-step reset) or an ordinary loss leaves Highest Rank intact —
 * it is a permanent record, not a mirror of the current rung. Both arguments
 * must be rungs of the one [rankLadder] table.
 */
fun updateHighestRank(profile: RankedProfile, rank: LadderRank): RankedProfile {
  return if (rankLadder.indexOf(rank) <= rankLadder.indexOf(profile.highestRank)) {
    profile
  } else {
    profile.copy(highestRank = rank)
  }
}
