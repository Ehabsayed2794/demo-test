package com.estemshan.engine

import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import kotlin.math.sqrt

/**
 * S53 — the scripted-bot director for the RD10 placement series: three
 * system-controlled matches whose recorded engine signals are the input S54's
 * 10/20/70 scoring consumes. Specified in docs/UI_UX_ROADMAP.md §4b.2 and
 * listed in docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S53).
 *
 * What lives here and what does not:
 *
 * - The **table** for each match — which tiers sit across from the player and
 *   in what format. RD10 fixes the tier pairs (M1 Easy+Medium, M2
 *   Medium+Hard, M3 Hard+Expert); the player never picks difficulty or
 *   personality, so the personality spread is a system choice, fixed here.
 * - The **progress** of the series (match 1/3, 2/3, 3/3) as an index, never a
 *   rank — RD10 forbids showing a provisional rank at any point, so this file
 *   produces no rank until S54 assigns one.
 * - The **six RD10 signals** for a finished match, derived from real engine
 *   outputs: final totals (placement, result, score) and the per-round
 *   estimate-vs-actual record (estimate accuracy, bidding performance,
 *   consistency). S54 weights them; this file only measures.
 *
 * What stays out: running a match (the `:app` coordinator drives the existing
 * quick-match machinery), scoring the series (S54), and persisting anything
 * (the profile write path is a separate story). Pure data + pure functions,
 * no I/O — the same boundary RankedProfile.kt keeps.
 */

/**
 * One seat in a scripted placement table. The system picks both fields; the
 * player picks neither (RD10).
 */
data class PlacementOpponent(
  val tier: BotTier,
  val personality: BotPersonality,
)

/**
 * One of the three scripted matches. Exactly three [opponents] — the player
 * occupies the fourth seat — seated in order against the player.
 */
data class PlacementMatchConfig(
  /** 0-based: match 1/3, 2/3, or 3/3. */
  val index: Int,
  val gameType: GameType,
  val scoringMode: ScoringMode,
  val opponents: List<PlacementOpponent>,
)

/**
 * The RD10 series. Tiers escalate match over match; each table mixes the
 * pair's lower tier into two seats and its upper tier into one, so a match is
 * always a mixed table and never four of a kind. Personalities are a fixed
 * system spread across the four profiles (all four appear somewhere in the
 * series) rather than uniform — the player cannot choose them, but the
 * matches should not feel identical.
 *
 * Format (owner decision): matches 1 and 2 are MINI, so the first two are a
 * light commitment; match 3 — the one S54 weights at 70% — is FULL, the
 * canonical Ranked format, and the long decisive read. Placement is
 * format-agnostic (a rank is neither Mini nor Full; GM6's ×0.5 only applies
 * to RP deltas, and placement awards none), so a mixed series costs nothing
 * in integrity. Scoring is NORMAL throughout (GM7's default).
 */
val placementMatches: List<PlacementMatchConfig> = listOf(
  PlacementMatchConfig(
    index = 0,
    gameType = GameType.MINI,
    scoringMode = ScoringMode.NORMAL,
    opponents = listOf(
      PlacementOpponent(BotTier.EASY, BotPersonality.BALANCED),
      PlacementOpponent(BotTier.EASY, BotPersonality.CONSERVATIVE),
      PlacementOpponent(BotTier.MEDIUM, BotPersonality.BALANCED),
    ),
  ),
  PlacementMatchConfig(
    index = 1,
    gameType = GameType.MINI,
    scoringMode = ScoringMode.NORMAL,
    opponents = listOf(
      PlacementOpponent(BotTier.MEDIUM, BotPersonality.AGGRESSIVE),
      PlacementOpponent(BotTier.MEDIUM, BotPersonality.BALANCED),
      PlacementOpponent(BotTier.HARD, BotPersonality.CONSERVATIVE),
    ),
  ),
  PlacementMatchConfig(
    index = 2,
    gameType = GameType.FULL,
    scoringMode = ScoringMode.NORMAL,
    opponents = listOf(
      PlacementOpponent(BotTier.HARD, BotPersonality.AGGRESSIVE),
      PlacementOpponent(BotTier.HARD, BotPersonality.TRICKSTER),
      PlacementOpponent(BotTier.EXPERT, BotPersonality.AGGRESSIVE),
    ),
  ),
)

/** How many scripted matches the series is. RD10: exactly three. */
const val PLACEMENT_MATCH_COUNT: Int = 3

/**
 * The [index]-th scripted match (0-based). Fails loudly past the series — a
 * caller asking for a fourth placement match has a real bug, since RD10 caps
 * the series at three and never repeats it.
 */
fun placementMatch(index: Int): PlacementMatchConfig {
  require(index in placementMatches.indices) {
    "Placement match index $index is outside the ${placementMatches.size}-match series (RD10)"
  }
  return placementMatches[index]
}

/** True when [index] is the series' last match — the S54 handoff point. */
fun isLastPlacementMatch(index: Int): Boolean = index == placementMatches.lastIndex

/**
 * The one-through-three progress number RD10 lets the UI show. Deliberately
 * an Int and not a string: the label is localized in `:app` resources (S48),
 * and this file carries no UI strings. Never derive a rank from the index —
 * a provisional rank is never shown (RD10).
 */
fun placementProgress(index: Int): Int {
  require(index in placementMatches.indices) {
    "Placement match index $index is outside the ${placementMatches.size}-match series (RD10)"
  }
  return index + 1
}

/**
 * One round's estimate-vs-actual for the player's seat — the raw pair behind
 * the accuracy, performance, and consistency signals.
 */
data class PlacementRoundSignal(
  /** The round number, 1-based. */
  val round: Int,
  /** What the player estimated (bid) for the round. */
  val estimate: Int,
  /** What the player actually took. */
  val actual: Int,
) {
  /** Signed miss: negative is an under-bid, positive an over-bid. */
  val miss: Int get() = estimate - actual

  /** Absolute miss — the accuracy signal's per-round term. */
  val absMiss: Int get() = if (miss < 0) -miss else miss

  /** Nailed the estimate exactly — the performance signal's per-round term. */
  val isExact: Boolean get() = estimate == actual
}

/**
 * The six RD10 signals for one finished placement match, plus the raw
 * per-round record they derive from. Unweighted: S54 applies the 10/20/70.
 *
 * Note on degenerate rounds: a general pass (every seat estimates 0 and takes
 * 0) counts here as an exact hit. That is faithful — the player did make
 * their bid — but it flatters [biddingPerformance] on a pass-happy match, so
 * S54 is free to re-weight from [rounds] if it wants pass rounds excluded.
 */
data class PlacementMatchRecord(
  val matchIndex: Int,
  /** Competition rank among the four seats, ties sharing the better rank. */
  val placement: Int,
  /** The same result as a [MatchOutcome]: KING / 2nd / 3rd / KOZ. */
  val outcome: MatchOutcome,
  /** The player's final score for the match. */
  val score: Int,
  /** Every round's estimate-vs-actual, in played order. */
  val rounds: List<PlacementRoundSignal>,
) {
  /**
   * RD10 "estimate accuracy" — mean absolute miss across the match. Lower is
   * better. Zero before any round is recorded (a match in flight), not a
   * claim of perfection.
   */
  val estimateAccuracy: Double
    get() = if (rounds.isEmpty()) 0.0 else rounds.sumOf { it.absMiss }.toDouble() / rounds.size

  /**
   * RD10 "bidding performance" — the share of rounds the estimate was exact.
   * Higher is better.
   */
  val biddingPerformance: Double
    get() = if (rounds.isEmpty()) 0.0 else rounds.count { it.isExact }.toDouble() / rounds.size

  /**
   * RD10 "consistency" — the spread of the signed miss across rounds
   * (population standard deviation). Lower is better: a player whose misses
   * cluster tightly is more predictable than one swinging between big
   * over-bids and big under-bids at the same mean accuracy.
   */
  val consistency: Double
    get() {
      if (rounds.size < 2) return 0.0
      val mean = rounds.sumOf { it.miss }.toDouble() / rounds.size
      val variance = rounds.sumOf { (it.miss - mean) * (it.miss - mean) } / rounds.size
      return sqrt(variance)
    }
}

/**
 * RD17 — competition rank: 1 plus the number of seats strictly ahead, so
 * ties share the better rank (tie 1st → all are Match King, tie 2nd → all
 * 2nd, tie 3rd → all 3rd). Because a tie for last leaves the seat with
 * company at the bottom, [placementOutcome] then produces no KOZ for it — a
 * Koz is never fabricated (RD17's "tie for last → no unique Koz").
 */
fun placementRank(totals: Map<String, Int>, seat: String): Int {
  val total = totals.getValue(seat)
  return 1 + totals.count { it.value > total }
}

/** The [placementRank] as the RD23 outcome vocabulary. */
fun placementOutcome(totals: Map<String, Int>, seat: String): MatchOutcome = when (placementRank(totals, seat)) {
  1 -> MatchOutcome.KING
  2 -> MatchOutcome.SECOND
  3 -> MatchOutcome.THIRD
  else -> MatchOutcome.KOZ
}

/**
 * Build one finished match's record from real engine outputs: the final
 * [totals] (placement, outcome, score) and the per-round [rounds] already
 * recorded for the player's [seat]. This is the whole handoff to S54 —
 * nothing else about a finished placement match needs to escape the
 * coordinator.
 */
fun recordPlacementMatch(
  matchIndex: Int,
  totals: Map<String, Int>,
  seat: String,
  rounds: List<PlacementRoundSignal>,
): PlacementMatchRecord = PlacementMatchRecord(
  matchIndex = matchIndex,
  placement = placementRank(totals, seat),
  outcome = placementOutcome(totals, seat),
  score = totals.getValue(seat),
  rounds = rounds,
)
