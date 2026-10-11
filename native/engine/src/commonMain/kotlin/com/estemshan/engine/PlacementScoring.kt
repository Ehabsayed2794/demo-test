package com.estemshan.engine

/**
 * S54 — placement scoring: the series composite that turns S53's three
 * finished-match records into a starting rank.
 *
 * Specified in docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S54; feature F3)
 * and docs/UI_UX_ROADMAP.md §4b.2 (RD10, FINAL):
 *
 * - Score is derived from real engine signals — placement, result, score,
 *   estimate accuracy, bidding performance, consistency — weighted
 *   10 / 20 / 70 across M1 / M2 / M3. The weights are CLOSED; they are
 *   constants below, pinned by test.
 * - Maximum placement = Platinum. Never Diamond, Royal, or King by
 *   placement. The series composite maps onto RP 0..Platinum-I-floor, so
 *   the ceiling holds structurally, not by a special case.
 * - The player starts at that division's first / lower-bound RP.
 * - Placement matches count toward NO statistic (RD23): this file writes
 *   no statistics object and touches no accumulator — the caller keeps it
 *   that way by never feeding these records to one.
 * - No provisional rank is ever produced: the only rank this file emits
 *   is the final one, from all three records at once.
 *
 * What is engineering judgment inside that closed envelope (documented,
 * tunable, and pinned by test rather than invented silently): each match's
 * six signals are normalized to 0..1 and averaged with equal weight, and
 * the raw-score signal is scaled against a per-format par. The match
 * weights, the ceiling, and the lower-bound start are NOT judgment calls —
 * they are the spec, asserted exactly.
 *
 * Pure data + pure functions, no I/O — the same boundary Placement.kt
 * and RankLadder.kt keep. Common-main: stdlib only.
 */

/** RD10 match weights, CLOSED: M1 10%, M2 20%, M3 70%. Not tunable. */
const val PLACEMENT_M1_WEIGHT: Double = 0.10
const val PLACEMENT_M2_WEIGHT: Double = 0.20
const val PLACEMENT_M3_WEIGHT: Double = 0.70

/**
 * Per-format score par for the raw-score signal: the total that reads as
 * fully dominant (component 1.0), with the symmetric floor at −par (0.0)
 * and a scoreless match neutral at 0.5. Beyond ±par the component
 * saturates — a blowout cannot outrank the placement it produced.
 * Tunable without redesign — it only sets where saturation starts, never
 * a rung boundary. Grounded in the per-round Normal scale (typical |round|
 * ~15–25 over 10 MINI / 18 FULL rounds).
 */
const val PLACEMENT_MINI_SCORE_PAR: Double = 200.0
const val PLACEMENT_FULL_SCORE_PAR: Double = 350.0

/** The three per-match weights in series order. Sums to exactly 1.0. */
val placementMatchWeights: List<Double> =
  listOf(PLACEMENT_M1_WEIGHT, PLACEMENT_M2_WEIGHT, PLACEMENT_M3_WEIGHT)

/**
 * The RP ceiling for placement: Platinum I's lower bound, derived from
 * the single tunable [rankLadder] table — never a scattered literal, so
 * an S46 retune moves the ceiling with the ladder. RD26 puts it at 1400;
 * the value is read, not written, here.
 */
val placementRpCeiling: Int
  get() = rankLadder.first { it.tier == "Platinum" && it.division == "I" }.lowerBound

/** The outcome signal as a 0..1 component (RD23 vocabulary, evenly spaced). */
fun placementOutcomeComponent(outcome: MatchOutcome): Double = when (outcome) {
  MatchOutcome.KING -> 1.0
  MatchOutcome.SECOND -> 2.0 / 3.0
  MatchOutcome.THIRD -> 1.0 / 3.0
  MatchOutcome.KOZ -> 0.0
}

/** The placement signal as a 0..1 component: 1st → 1.0, 4th → 0.0. */
fun placementRankComponent(placement: Int): Double {
  require(placement in 1..4) {
    "Placement rank $placement is outside the four-seat table (RD17 competition rank is 1..4)"
  }
  return (4 - placement) / 3.0
}

/**
 * The raw-score signal as a 0..1 component: linear from the symmetric
 * floor (−par → 0.0) through neutral (0 → 0.5) to dominant (par → 1.0),
 * saturating beyond ±par. [par] is the format's
 * [PLACEMENT_MINI_SCORE_PAR] / [PLACEMENT_FULL_SCORE_PAR].
 */
fun placementScoreComponent(score: Int, par: Double): Double {
  require(par > 0) { "Score par must be positive, was $par" }
  return (score.toDouble() / par / 2.0 + 0.5).coerceIn(0.0, 1.0)
}

/** Estimate accuracy (mean absolute miss) as 0..1: exact → 1.0. */
fun placementAccuracyComponent(meanAbsMiss: Double): Double {
  require(meanAbsMiss >= 0) { "Mean absolute miss cannot be negative, was $meanAbsMiss" }
  return 1.0 / (1.0 + meanAbsMiss)
}

/** Consistency (miss spread) as 0..1: perfectly steady → 1.0. */
fun placementConsistencyComponent(stdDev: Double): Double {
  require(stdDev >= 0) { "Consistency spread cannot be negative, was $stdDev" }
  return 1.0 / (1.0 + stdDev)
}

/**
 * One finished placement match as a single 0..1 composite: the mean of
 * the six RD10 signals, each normalized above. Equal within-match weight
 * is the least arbitrary choice the spec leaves open — the spec fixes
 * only the ACROSS-match weights (10/20/70), which [scorePlacementSeries]
 * applies. The format's score par is resolved from S53's
 * [placementMatches] table by [record]'s own index, so the MINI/FULL
 * assignment stays in exactly one place.
 */
fun scorePlacementMatch(record: PlacementMatchRecord): Double {
  val format = placementMatches.getOrNull(record.matchIndex)
  require(format != null) {
    "Placement record matchIndex ${record.matchIndex} names no scripted series match (RD10: 0..2)"
  }
  require(record.rounds.isNotEmpty()) {
    "Placement match ${record.matchIndex} has no recorded rounds — it is still in flight, not scoreable (S53)"
  }
  val par = when (format.gameType) {
    GameType.MINI -> PLACEMENT_MINI_SCORE_PAR
    GameType.FULL -> PLACEMENT_FULL_SCORE_PAR
  }
  val components = listOf(
    placementRankComponent(record.placement),
    placementOutcomeComponent(record.outcome),
    placementScoreComponent(record.score, par),
    placementAccuracyComponent(record.estimateAccuracy),
    record.biddingPerformance,
    placementConsistencyComponent(record.consistency),
  )
  return components.average()
}

/**
 * The scored series: per-match composites, the weighted series score,
 * and the final rank + starting RP.
 *
 * @param seriesScore the 10/20/70-weighted composite in 0..1.
 * @param rank the starting rung — always at or below Platinum I (RD10
 *   ceiling), resolved by [rankAt] over the closed RD26 thresholds.
 * @param startRp the rung's lower bound: the player starts at the
 *   division's first RP (RD10), never mid-division.
 */
data class PlacementSeriesResult(
  val matchScores: List<Double>,
  val seriesScore: Double,
  val rank: LadderRank,
  val startRp: Int,
)

/**
 * Score the finished three-match series. Fails loudly unless [records]
 * holds exactly one finished record per series slot (indices 0, 1, 2) —
 * a partial series has no provisional rank to show (RD10), so there is
 * nothing to return for it.
 */
fun scorePlacementSeries(records: List<PlacementMatchRecord>): PlacementSeriesResult {
  require(records.size == PLACEMENT_MATCH_COUNT) {
    "Placement scoring needs the full $PLACEMENT_MATCH_COUNT-match series, got ${records.size} (RD10: never score a partial series)"
  }
  val byIndex = records.sortedBy { it.matchIndex }
  byIndex.forEachIndexed { slot, record ->
    require(record.matchIndex == slot) {
      "Placement series slot $slot holds matchIndex ${record.matchIndex} — the series needs indices 0, 1, 2 exactly once (RD10)"
    }
  }
  val matchScores = byIndex.map { scorePlacementMatch(it) }
  val seriesScore = matchScores.zip(placementMatchWeights) { score, weight -> score * weight }.sum()
    .coerceIn(0.0, 1.0)
  val targetRp = kotlin.math.round(seriesScore * placementRpCeiling).toInt()
    .coerceIn(0, placementRpCeiling)
  val rank = rankAt(targetRp)
  return PlacementSeriesResult(
    matchScores = matchScores,
    seriesScore = seriesScore,
    rank = rank,
    startRp = rank.lowerBound,
  )
}
