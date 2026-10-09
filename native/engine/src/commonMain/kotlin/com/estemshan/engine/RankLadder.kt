package com.estemshan.engine

/**
 * S57 — the Ranked ladder. Ported from
 * design-ui/standings-ranked-result/ranked-result.js (canonical) and
 * docs/UI_UX_ROADMAP.md §4b.1 (RD1/RD2/RD3/RD26, CLOSED 2026-10-05).
 *
 * 19 rungs = 6 tiers × 3 divisions + King. King has NO divisions and is
 * the single 19th rung above Royal I. Divisions run III → II → I with
 * I > II > III (Gold I → Gold III is a two-step demotion).
 *
 * Thresholds are the FINAL owner-approved lower-bound RP values
 * (Bronze III 0 → King 3,000). They live in ONE tunable table below —
 * the engine stays independent of the constants so the numbers remain
 * tunable without redesign (§4b.1). Never derive a threshold, rank, or
 * RP value from game-state.js (non-canonical legacy seed: Gold III at
 * 1,240 RP = Platinum II on the approved ladder).
 *
 * Pure functions, no I/O.
 *
 * Note: card ranks already own the name `Rank` (Cards.kt), so the
 * ladder rung is [LadderRank] — same shape the story calls "Rank".
 */
data class LadderRank(
  val tier: String,
  val arabic: String,
  val lowerBound: Int,
  /** III / II / I, or "" for King (King has no division slot). */
  val division: String,
) {
  val isKing: Boolean get() = tier == "King"

  /** "Gold III · معلم", "King · ملك" (King has no division slot). */
  fun label(): String =
    if (division.isEmpty()) "$tier · $arabic" else "$tier $division · $arabic"
}

/**
 * The single tunable constant table: (tier, arabic title, lower bounds
 * ascending). Everything below derives from this — do not scatter
 * literals elsewhere.
 */
private val LADDER_TABLE: List<Triple<String, String, List<Int>>> = listOf(
  Triple("Bronze", "مبتدئ", listOf(0, 75, 150)),
  Triple("Silver", "لاعب", listOf(250, 350, 450)),
  Triple("Gold", "معلم", listOf(575, 700, 825)),
  Triple("Platinum", "وزير", listOf(1000, 1200, 1400)),
  Triple("Diamond", "أمير", listOf(1600, 1800, 2000)),
  Triple("Royal", "سلطان", listOf(2250, 2500, 2750)),
  Triple("King", "ملك", listOf(3000)),
)

private val DIVISIONS = listOf("III", "II", "I")

/** The 19 rungs in ascending order. */
val rankLadder: List<LadderRank> = LADDER_TABLE.flatMap { (tier, arabic, bounds) ->
  bounds.mapIndexed { i, lower ->
    LadderRank(
      tier = tier,
      arabic = arabic,
      lowerBound = lower,
      division = if (tier == "King") "" else DIVISIONS[i],
    )
  }
}

/**
 * Rank for [rp]: the highest rung whose lower bound is <= rp.
 * Negative RP is floored to 0 first (RP ≥ 0 always, RD3).
 */
fun rankAt(rp: Int): LadderRank {
  val floored = rp.coerceAtLeast(0)
  return rankLadder.last { it.lowerBound <= floored }
}

/** "Gold III · معلم" — mirrors RankedResult.label() in the reference. */
fun rankLabel(rank: LadderRank): String = rank.label()
