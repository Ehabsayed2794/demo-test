package com.estemshan.engine

/**
 * S44 — the exactly-NINE career statistics (RD23 — never described as ten):
 * Games Played; King count + King %; 2nd count + 2nd %; 3rd count + 3rd %;
 * Koz count + Koz %. Enumerated in this order in docs/UI_UX_ROADMAP.md §4b.9.
 * The S03 profile grid renders all nine plus Highest Rank; the S44 long-press
 * popover renders only the six values that matter mid-match.
 *
 * Scope (Q46 + Q14c + Q17, 2026-10-22 — superseding RD28/A3.5, which the
 * plan's D-05 dissonance row already flagged): a completed match counts
 * toward the nine in ROOM, RANKED and UNRANKED alike, and the percentages
 * denominate over that all-mode Games Played. Unranked stays a first-class
 * rank-blind mode, never ROOM + a marker — rank-blind means no RP, never no
 * statistics. RP movement stays Ranked-only (Q46). Placement matches count
 * toward NOTHING (RD10) — not Games Played, not any percentage, in every
 * mode.
 *
 * ONE movement is carved out of the no-Unranked rule: the A5.1 departure
 * charge. The 2026-10-16 owner closure (option (a)) and the 2026-10-17 owner
 * clarification make the career Koz counter a **plain departure counter,
 * not Ranked progression** — it moves no tier and no RP — so an
 * `UNRANKED` departure records its charge; A3.5's "a finished Unranked
 * match writes no Ranked outcome" still holds, because a charge is not a
 * match outcome. The charge rides [accumulateDepartureCharge], never the
 * outcome fold below. A Vote-Kicked removal records through the same
 * departure surface: [LeaveMatch.decideLeave]([MatchMode],
 * [LeaveReason.VOTE_KICK]) is 1 Koz, final (D10 as amended by A5.1 — the
 * RD31 doubling is repealed for that path).
 *
 * Five counters are stored; the four percentages are derived over
 * [gamesPlayed] as whole percents (0 before the first completed match). The
 * [RankedStatKey] enum fixes the nine at compile time so a tenth cannot slip
 * in, and the outcome fold keeps king + 2nd + 3rd + Koz equal to Games
 * Played (every completed match gives one seat exactly one outcome). An
 * A5.1 departure charge adds its amount to Koz and one to Games Played, so
 * a Koz count heavier than the outcome rows counts departed matches at
 * their charged weight — a 2-Koz departure plus one King reads, honestly,
 * as 3 Koz over 2 Games Played.
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
  /** Whole percent over completed matches in every mode (Q17); 0 before the first. */
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
 * Fold ONE completed match into the nine career statistics. ONE gate,
 * returning [stats] untouched:
 *
 * - the account must not be mid-placement (RD10: placement matches count
 *   toward no statistic — not Games Played, not any percentage — in every
 *   mode). Derived from [profile] rather than passed as a flag so the gate
 *   cannot disagree with the placement service's view: placement matches
 *   happen exactly while [isPlacement] holds.
 *
 * [mode] stays in the signature (callers — the RANKED settlement path today,
 * the ROOM/UNRANKED writers S3/S6 add — carry it) but no longer gates: Q46
 * (N-1, 2026-10-22) rules the old RANKED-only gate the defect, and Q14c +
 * Q17 widen it — a completion in ROOM, RANKED or UNRANKED increments Games
 * Played and the seat's outcome counter, and the percentages' denominator
 * aggregates all three modes. What stays Ranked-only is RP movement (Q46):
 * tier/division/RP live in [RankedProfile] and the RP engines above this
 * fold, which never touches them — a ROOM or UNRANKED completion moves
 * counters only, never RP.
 *
 * Tie conventions arrive with [outcome], decided upstream by the merged
 * [placementRank]/[placementOutcome] (Q2d/Q2f, PR #161): every bottom-tied
 * seat takes the LAST placement — KOZ in this vocabulary — unless all four
 * seats tie, which is all-King.
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
  if (isPlacement(profile)) return stats
  return stats.copy(
    gamesPlayed = stats.gamesPlayed + 1,
    kingCount = stats.kingCount + if (outcome == MatchOutcome.KING) 1 else 0,
    secondCount = stats.secondCount + if (outcome == MatchOutcome.SECOND) 1 else 0,
    thirdCount = stats.thirdCount + if (outcome == MatchOutcome.THIRD) 1 else 0,
    kozCount = stats.kozCount + if (outcome == MatchOutcome.KOZ) 1 else 0,
  )
}

/**
 * Fold ONE A5.1 departure charge into the career counters — option (a)'s
 * consumption surface ("`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:883-884`":
 * "the match contributes +2 to the career Koz count (Koz % denominator +1,
 * Games Played +1) — no new result value is modelled"). The amount comes
 * from [LeaveMatch.decideLeave] or [DisconnectVote.permanentCharge]; the
 * charge table behind them (A5.1, `docs/rules/CANONICAL_RULES.md:421-427`)
 * governs: the two Koz departures add 2, a Vote Kick adds 1 final, a
 * reclaimable return adds nothing.
 *
 * Gates:
 * - [KozCharge.None] records nothing — the "no penalty" row and the ROOM
 *   disconnect's "no Koz" verdict never reach the counters.
 * - [MatchMode.ROOM] records nothing — A5.1 excludes ROOM from RD31
 *   entirely ("no 2-Koz rule"). Since Q46, ROOM completions reach the
 *   outcome fold above, but this charge path stays ROOM-closed here; the
 *   ROOM amounts are S5's to rule in.
 * - a placement profile records nothing (RD10: placement matches count
 *   toward NO statistic — the charge feeds Games Played and Koz %, the very
 *   counters RD10 pins). Reading flagged in the S63 report.
 * - [MatchMode.RANKED] and [MatchMode.UNRANKED] both record. This is the
 *   one career counter that moves in UNRANKED: the 2026-10-17 owner
 *   clarification rules the career Koz counter a plain departure counter,
 *   not Ranked progression, and A5.3 item 3 stands RESOLVED. It still
 *   writes NO outcome — no king/2nd/3rd, no placement, no RP — so A3.5's
 *   "writes no Ranked outcome" is untouched.
 *
 * Never a double record: the charge IS the departed player's individual
 * match result, so a settlement must never ALSO feed that departed seat's
 * final standing for the departed player — the bot's play in the vacated
 * seat is the seat's own record and is never attributed to them (A5.1).
 *
 * Pure, no I/O; callers above the engine apply the result.
 */
fun accumulateDepartureCharge(
  stats: RankedStats,
  profile: RankedProfile,
  mode: MatchMode,
  charge: KozCharge,
): RankedStats {
  if (charge == KozCharge.None || mode == MatchMode.ROOM || isPlacement(profile)) return stats
  val amount = when (charge) {
    KozCharge.Koz1 -> 1
    KozCharge.Koz2 -> 2
    KozCharge.None -> 0
  }
  return stats.copy(
    gamesPlayed = stats.gamesPlayed + 1,
    kozCount = stats.kozCount + amount,
  )
}
