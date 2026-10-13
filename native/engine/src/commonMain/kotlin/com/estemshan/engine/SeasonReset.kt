package com.estemshan.engine

/**
 * S59 — the end-of-season reset math (RD24; 3-month cycle, owner-closed).
 *
 * Specified in docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S59: "3-month
 * cycle, season ID, two-step reset with ladder floor, King → Royal I,
 * Highest Rank preserved") and docs/UI_UX_ROADMAP.md §4b (seasons row +
 * S54 Season Overview): end-of-season demotes Tier/Division TWO division
 * steps (Gold I → Gold III), floored at the ladder bottom; King → Royal I;
 * career stats never reset; Highest Rank ever is shown and is NOT erased.
 *
 * Scope of THIS file (engine slice): the pure reset mapping from one
 * [RankedProfile] to the next season's. The periodic season job — the
 * 3-month clock, who advances the season ID, the one-way migration
 * dry-run (R27) — is server scope with no specified trigger and lives
 * outside this file. The season doc (id/start/end) and the S62 ledger
 * are likewise separate stories.
 *
 * What is engineering judgment inside that closed envelope (documented
 * here and pinned by SeasonResetTest, not specified by RD24):
 *
 * - "Two division steps" = two rungs down the single ascending
 *   [rankLadder] (I > II > III per RD2, so Gold I − 2 = Gold III),
 *   floored at index 0 (Bronze III). The ladder order IS the step
 *   definition — no second table.
 * - RP restarts at the new rung's LOWER BOUND. Keeping the old RP would
 *   break the ladder invariant ([rankAt] would resolve a different rung
 *   than the profile names); the floor is the only consistent value, in
 *   the same convention as S54's division start.
 * - King → Royal I is a DESTINATION, not two steps: index − 2 from King
 *   would land Royal III, which RD24 explicitly overrides (owner-closed;
 *   King has no divisions to step through).
 * - The demotion shield does not cross the season boundary: a one-match
 *   shield (RD7) cannot survive a 3-month reset, so the reset profile is
 *   disarmed. Highest Rank is carried untouched (never lowered); the
 *   placement state is carried untouched (RD10: placement never repeats
 *   per season, and S59 must not reopen it); career stats are a separate
 *   object this function never takes, so "never reset" holds structurally.
 *
 * Pure data + pure functions, no I/O. Common-main: stdlib only.
 */

/** Season-reset steps down the ladder. Closed by RD24's Gold I → Gold III. */
const val SEASON_RESET_STEPS: Int = 2

/**
 * The rung a season reset lands on: two ladder steps down, floored at the
 * ladder bottom — except King, which lands on Royal I by owner decision
 * (RD24), not by stepping.
 */
fun seasonResetRank(current: LadderRank): LadderRank {
  if (current.isKing) {
    return rankLadder.first { it.tier == "Royal" && it.division == "I" }
  }
  val stepped = rankLadder.indexOf(current) - SEASON_RESET_STEPS
  return rankLadder[stepped.coerceAtLeast(0)]
}

/**
 * Reset [profile] for [newSeasonId]: tier/division move to
 * [seasonResetRank], RP restarts at the new rung's lower bound, the
 * shield disarms, and highestRank + placementState carry over untouched.
 * Fails loudly on a blank season ID — a reset always opens a named season.
 */
fun applySeasonReset(profile: RankedProfile, newSeasonId: String): RankedProfile {
  require(newSeasonId.isNotBlank()) {
    "Season reset needs the new season's ID — a reset never opens an unnamed season (RD24)"
  }
  val current = ladderRankOf(profile)
  val landed = seasonResetRank(current)
  return profile.copy(
    tier = landed.tier,
    division = landed.division,
    rp = landed.lowerBound,
    seasonId = newSeasonId,
    demotionShield = false,
  )
}
