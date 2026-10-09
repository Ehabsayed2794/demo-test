package com.estemshan.engine

/**
 * S44 — the exactly-NINE career statistics (RD23 — never described as ten):
 * Games Played; King count + King %; 2nd count + 2nd %; 3rd count + 3rd %;
 * Koz count + Koz %. Enumerated in this order in docs/UI_UX_ROADMAP.md §4b.9.
 * The S03 profile grid renders all nine plus Highest Rank; the S44 long-press
 * popover renders only the six values that matter mid-match.
 *
 * Scope (RD28): Public Ranked AND Private Ranked count; ROOM and UNRANKED do
 * not — Unranked is a first-class rank-blind mode, never ROOM + a marker.
 * Placement matches count toward NOTHING (RD10) — not Games Played, not any
 * percentage. A Vote-Kicked removal reaches the accumulator as a
 * [MatchOutcome.KOZ] (D10).
 *
 * Five counters are stored; the four percentages are derived over
 * [gamesPlayed] as whole percents (0 before the first completed match). The
 * [RankedStatKey] enum fixes the nine at compile time so a tenth cannot slip
 * in, and the accumulator keeps king + 2nd + 3rd + Koz equal to Games Played
 * (every completed match gives one seat exactly one outcome).
 *
 * Pure data + pure functions, no I/O.
 */

/** A completed match's outcome for one seat — the accumulator's sole input. */
enum class MatchOutcome { KING, SECOND, THIRD, KOZ }

/**
 * The nine statistics, in the spec's order. Exactly nine entries — the
 * structural guarantee behind "never a 10th" (RD23).
 */
enum class RankedStatKey {
  GAMES_PLAYED,
  KING_COUNT, KING_PCT,
  SECOND_COUNT, SECOND_PCT,
  THIRD_COUNT, THIRD_PCT,
  KOZ_COUNT, KOZ_PCT,
}

data class RankedStat(val key: RankedStatKey, val value: Int)

data class RankedStats(
  val gamesPlayed: Int = 0,
  val kingCount: Int = 0,
  val secondCount: Int = 0,
  val thirdCount: Int = 0,
  val kozCount: Int = 0,
) {
  /** Whole percent over completed Ranked matches; 0 before the first. */
  val kingPct: Int get() = percent(kingCount)
  val secondPct: Int get() = percent(secondCount)
  val thirdPct: Int get() = percent(thirdCount)
  val kozPct: Int get() = percent(kozCount)

  private fun percent(count: Int): Int =
    if (gamesPlayed == 0) 0 else count * 100 / gamesPlayed

  /** The nine in RD23 order — S03's grid. Never a tenth. */
  val grid: List<RankedStat>
    get() = listOf(
      RankedStat(RankedStatKey.GAMES_PLAYED, gamesPlayed),
      RankedStat(RankedStatKey.KING_COUNT, kingCount),
      RankedStat(RankedStatKey.KING_PCT, kingPct),
      RankedStat(RankedStatKey.SECOND_COUNT, secondCount),
      RankedStat(RankedStatKey.SECOND_PCT, secondPct),
      RankedStat(RankedStatKey.THIRD_COUNT, thirdCount),
      RankedStat(RankedStatKey.THIRD_PCT, thirdPct),
      RankedStat(RankedStatKey.KOZ_COUNT, kozCount),
      RankedStat(RankedStatKey.KOZ_PCT, kozPct),
    )
}

/**
 * Fold ONE completed match into the nine career statistics. Two gates, both
 * returning [stats] untouched:
 *
 * - [mode] must be [MatchMode.RANKED] (RD28: ROOM and UNRANKED record no
 *   Ranked statistics).
 * - the account must not be mid-placement (RD10: placement matches count
 *   toward no statistic — not Games Played, not any percentage). Derived from
 *   [profile] rather than passed as a flag so the two gates cannot disagree:
 *   placement matches happen exactly while [isPlacement] holds.
 *
 * Nothing else is recorded (RD23): no match history and no seasonal trophy in
 * v1. Callers above the engine apply the result; this function never mutates
 * the profile's placement state (that is the S53 placement service's job).
 */
fun accumulateRankedStat(
  stats: RankedStats,
  outcome: MatchOutcome,
  profile: RankedProfile,
  mode: MatchMode,
): RankedStats {
  if (mode != MatchMode.RANKED || isPlacement(profile)) return stats
  return stats.copy(
    gamesPlayed = stats.gamesPlayed + 1,
    kingCount = stats.kingCount + if (outcome == MatchOutcome.KING) 1 else 0,
    secondCount = stats.secondCount + if (outcome == MatchOutcome.SECOND) 1 else 0,
    thirdCount = stats.thirdCount + if (outcome == MatchOutcome.THIRD) 1 else 0,
    kozCount = stats.kozCount + if (outcome == MatchOutcome.KOZ) 1 else 0,
  )
}
