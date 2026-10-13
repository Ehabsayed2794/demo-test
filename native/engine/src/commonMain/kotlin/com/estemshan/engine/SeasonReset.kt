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
 * [RankedProfile] to the next season's. The 3-month clock and trigger, the
 * periodic season job (including its concurrency-safe one-way guard), the
 * season doc (id/start/end), the S62 ledger, and the R27 mandatory
 * emulator dry-run against a seeded production-shaped dataset before the
 * first real season are server scope / release gates with no specified
 * trigger in the repo — explicitly outside this file, tracked as
 * blockers in the delivery report, never claimed satisfied by unit tests.
 *
 * What is engineering judgment inside that closed envelope (documented
 * here and pinned by SeasonResetTest, not specified by RD24):
 *
 * - "Two division steps" = two rungs down the single ascending
 *   [rankLadder] (I > II > III per RD2, so Gold I − 2 = Gold III),
 *   floored at index 0 (Bronze III). The ladder order IS the step
 *   definition — no second table.
 * - RP restarts at the new rung's LOWER BOUND. Keeping the old RP is not
 *   an option the contract leaves open: the profile model states "[rp]
 *   is the progression counter whose own rung is [rankAt]; the settlement
 *   that moves one keeps both consistent" (RankedProfile.kt) — a kept
 *   830 RP beside a Gold III identity would resolve to Gold I. Any value
 *   inside the new rung would satisfy the invariant; the floor follows the
 *   repo's rank-transition convention (S54's division start, S49's hold).
 * - King → Royal I is a DESTINATION, not two steps: index − 2 from King
 *   would land Royal III, which RD24 explicitly overrides (owner-closed;
 *   King has no divisions to step through).
 * - The demotion shield CARRIES OVER unchanged. Clearing it would invent
 *   a rule: the shield guards the next MATCH played (RD7), and a reset
 *   plays no match — no contract ends it at the season boundary, and
 *   carrying a Boolean cannot break the rankAt/RP invariant.
 * - Highest Rank goes through the EXISTING [updateHighestRank] policy,
 *   which only ever raises: an already-correct peak is untouched, a stale
 *   peak (possible — no engine path auto-records promotions; the S54
 *   starting assignment does not touch it) is raised to the pre-reset
 *   rung. No new historical-ranking policy is introduced.
 * - A foreign rung fails LOUDLY ([require] on the ladder index) instead
 *   of flooring to Bronze III: under R27's irreversible migration, a
 *   silent wrong rung is the worst outcome. (A bogus profile identity
 *   already fails inside [ladderRankOf]; this guards direct callers.)
 * - Reopening the CURRENT season ID fails: a reset always opens a NEW
 *   season, so a same-ID call is definitionally a double-run bug. This is
 *   a pure orchestration aid, NOT an exactly-once guard — concurrency-safe
 *   one-way execution belongs to the server-side season job (unbuilt, see
 *   below) and must not be claimed from an ID comparison.
 *
 * Pure data + pure functions, no I/O. Common-main: stdlib only.
 */

/** Season-reset steps down the ladder. Closed by RD24's Gold I → Gold III. */
const val SEASON_RESET_STEPS: Int = 2

/**
 * The rung a season reset lands on: two ladder steps down, floored at the
 * ladder bottom — except King, which lands on Royal I by owner decision
 * (RD24), not by stepping. A rung of no ladder fails loudly: silent
 * flooring would corrupt an irreversible migration (R27).
 */
fun seasonResetRank(current: LadderRank): LadderRank {
  val index = rankLadder.indexOf(current)
  require(index >= 0) {
    "Season reset needs a rung of the single ladder, was $current — a foreign rank must fail, never floor (R27)"
  }
  if (current.isKing) {
    return rankLadder.first { it.tier == "Royal" && it.division == "I" }
  }
  return rankLadder[(index - SEASON_RESET_STEPS).coerceAtLeast(0)]
}

/**
 * Reset [profile] for [newSeasonId]: tier/division move to
 * [seasonResetRank], RP restarts at the new rung's lower bound (the
 * keeps-both-consistent invariant), Highest Rank passes through the
 * raise-only [updateHighestRank] policy, placementState and the shield
 * carry over untouched, and the season ID advances. Fails loudly on a
 * blank OR same-as-current season ID — a reset always opens a NAMED, NEW
 * season (the same-ID refusal is a double-run aid, not an exactly-once
 * guard: that belongs to the server job).
 */
fun applySeasonReset(profile: RankedProfile, newSeasonId: String): RankedProfile {
  require(newSeasonId.isNotBlank()) {
    "Season reset needs the new season's ID — a reset never opens an unnamed season (RD24)"
  }
  require(newSeasonId != profile.seasonId) {
    "Season reset from ${profile.seasonId} to the same ID is a double-run — a reset always opens a NEW season"
  }
  val current = ladderRankOf(profile)
  val landed = seasonResetRank(current)
  return profile.copy(
    tier = landed.tier,
    division = landed.division,
    rp = landed.lowerBound,
    seasonId = newSeasonId,
    highestRank = updateHighestRank(profile, current).highestRank,
  )
}
