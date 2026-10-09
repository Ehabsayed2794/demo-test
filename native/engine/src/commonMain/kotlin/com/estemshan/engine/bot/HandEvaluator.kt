package com.estemshan.engine.bot

import com.estemshan.engine.Card
import com.estemshan.engine.DECK_SUITS
import com.estemshan.engine.Suit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Per-suit contribution to a hand evaluation. Ported from
 * `AI Bots/botEngine.ts` `SuitBreakdown`; every fractional field is rounded to
 * two decimals exactly like the source's `round2()`.
 */
data class SuitBreakdown(
  val suit: Suit,
  val count: Int,
  val highCardTricks: Double,
  val lengthTricks: Double,
  val ruffTricks: Double,
  val hasAce: Boolean,
  val hasKing: Boolean,
  val hasQueen: Boolean,
)

/**
 * A hand's estimated trick count under one trump declaration. Ported from
 * `AI Bots/botEngine.ts` `HandEvaluation`.
 *
 * [expectedTricks] applies [confidence] to the distributional part only;
 * [rawExpected] is the same estimate at full confidence, so the difference
 * between the two is precisely what a weak tier under-counts.
 */
data class HandEvaluation(
  /** Declared trump. `Suit.SANS` is the source's `trump === 'NONE'` case. */
  val trump: Suit,
  val expectedTricks: Double,
  val rawExpected: Double,
  val breakdown: List<SuitBreakdown>,
  /** Human-readable derivation for debug panels; not part of any contract. */
  val reasoning: String,
)

/**
 * Module A — the probabilistic trick estimator. Ported from
 * `AI Bots/botEngine.ts` §1 (`evaluateHand` / `evaluateAllTrumps` /
 * `chooseBestTrump` and their private helpers), which the source calls
 * "PHASE 1 / FOUNDATION".
 *
 * Expected tricks for a hand come from three independent sources:
 *  * **high-card tricks** — honors that win by rank (an Ace, a guarded King,
 *    A-K-Q, …)
 *  * **length tricks** — in a long suit, low cards become winners once the
 *    high cards everyone else holds are exhausted
 *  * **ruffing tricks** — short *side* suits let the bot trump in, which is
 *    worthless in Sans because there is no trump to ruff with
 *
 * Skill enters only through `confidence`. High-card tricks are real no matter
 * how weak the player is; distribution is exactly what a beginner under-counts.
 * So confidence scales the length+ruff term and leaves the honors alone — that
 * single decision is what makes EASY beatable without making it stupid.
 *
 * Pure, deterministic and dependency-free: no randomness, no I/O, nothing from
 * Android. The whole object is covered by the `:engine:test` JVM suite, which
 * is why the bot brains live in this module rather than in `:app` (approach A
 * in the plan).
 */
object HandEvaluator {

  /**
   * Estimate expected tricks for [hand] under [trump], where `Suit.SANS` means
   * no trump at all. [confidence] is the tier's `distributionConfidence`.
   *
   * A 13-card hand is assumed but not enforced; the arithmetic is valid for any
   * length, and the bidding brain (S6) evaluates a full hand anyway.
   */
  fun evaluateHand(
    hand: List<Card>,
    trump: Suit,
    confidence: Double = 1.0,
  ): HandEvaluation {
    val isSans = trump == Suit.SANS
    val trumpCount = if (isSans) 0 else hand.count { it.suit == trump }

    val breakdown = ArrayList<SuitBreakdown>(DECK_SUITS.size)
    var highCard = 0.0
    var length = 0.0
    var ruff = 0.0
    for (suit in DECK_SUITS) {
      val cards = hand.filter { it.suit == suit }
      val count = cards.size
      val isTrumpSuit = !isSans && suit == trump

      val hc = highCardTricksForSuit(cards)
      // Trump length is reliable; side-suit length only earns tricks in Sans,
      // where there is no trump to cut it with.
      val len = when {
        isTrumpSuit -> lengthTricksForSuit(count)
        isSans -> lengthTricksForSuit(count)
        else -> 0.0
      }
      // Ruffing needs a real trump and a *side* suit; the trump suit itself
      // can't ruff itself.
      val rf = if (!isSans && !isTrumpSuit) ruffTricksForSideSuit(count, trumpCount) else 0.0

      highCard += hc
      length += len
      ruff += rf
      breakdown += SuitBreakdown(
        suit = suit,
        count = count,
        highCardTricks = round2(hc),
        lengthTricks = round2(len),
        ruffTricks = round2(rf),
        hasAce = cards.any { it.value == ACE },
        hasKing = cards.any { it.value == KING },
        hasQueen = cards.any { it.value == QUEEN },
      )
    }

    val distributional = (length + ruff) * confidence
    return HandEvaluation(
      trump = trump,
      expectedTricks = highCard + distributional,
      rawExpected = highCard + length + ruff,
      breakdown = breakdown,
      reasoning = "Trump=$trump | HighCard=${round2(highCard)} " +
        "Length=${round2(length)} Ruff=${round2(ruff)} " +
        "(conf $confidence) => exp ${round2(highCard + distributional)}",
    )
  }

  /**
   * Evaluate [hand] under every possible declaration — the four real suits plus
   * Sans — strongest first. This is the basis for both trump choice and
   * bidding (S6).
   *
   * Ties keep the iteration order SPADES, HEARTS, DIAMONDS, CLUBS, SANS, which
   * matches the source's (stable) descending sort exactly, so a golden test can
   * pin the chosen trump without ambiguity.
   */
  fun evaluateAllTrumps(hand: List<Card>, confidence: Double = 1.0): List<HandEvaluation> =
    (DECK_SUITS + Suit.SANS)
      .map { evaluateHand(hand, it, confidence) }
      .sortedByDescending { it.expectedTricks }

  /**
   * The best *declared* trump for [hand] at the given [tier]'s confidence —
   * Sans is excluded, because this answers "which suit should I call", not
   * "should I play Sans".
   *
   * **[tier] provably cannot change the answer.** High-card tricks are summed
   * over all four suits and never depend on the declaration, so for a fixed
   * hand `expectedTricks(t) = highCard + (length(t) + ruff(t)) * confidence` —
   * a positive scaling of a trump-independent constant. Confidence therefore
   * moves the *number* a bot bids, never the suit it bids it in. That is by
   * design in the source: a beginner picks the same trump as an expert and
   * simply under-values it. `HandEvaluatorTest` pins the property so a later
   * change that makes confidence scale honors is caught as the behaviour
   * change it is.
   *
   * Always resolves: four real suits are always candidates. The source has a
   * `?? 'SPADES'` fallback that is unreachable for the same reason, and it is
   * deliberately not ported — failing loudly beats a silent default here.
   */
  fun chooseBestTrump(hand: List<Card>, tier: BotTier = BotTier.MEDIUM): Suit =
    evaluateAllTrumps(hand, tier.distributionConfidence)
      .first { it.trump != Suit.SANS }
      .trump

  // --------------------------------------------------------------------------
  //  Suit-level helpers, ported 1:1 from botEngine.ts. The constants are
  //  calibrated DOWN from naive bridge-style counting — the source's comments
  //  record the old values alongside, and the numbers are load-bearing for the
  //  golden bids in S10, so they are not up for retuning here.
  // --------------------------------------------------------------------------

  /**
   * High-card tricks for one suit given how many cards are held in it. Length
   * matters because a King is only a trick if something low sits behind it.
   */
  internal fun highCardTricksForSuit(cards: List<Card>): Double {
    val has = { rank: Int -> cards.any { it.value == rank } }
    val n = cards.size
    val ace = has(ACE)
    val king = has(KING)
    val queen = has(QUEEN)
    val jack = has(JACK)

    var tricks = 0.0
    // Ace: almost always a trick.
    if (ace) tricks += 1.0
    // King: a trick only if guarded (2+ cards) or backed by the Ace.
    if (king) {
      when {
        ace -> tricks += 1.0        // A-K solid, two tricks
        n >= 2 -> tricks += 0.5     // guarded king ~ half a trick
        // singleton bare King ~ 0 — it will be captured
      }
    }
    // Queen: a trick only if well guarded and supported by a higher honor.
    if (queen) {
      when {
        ace && king -> tricks += 1.0          // A-K-Q, three solid
        (ace || king) && n >= 3 -> tricks += 0.5
        n >= 3 -> tricks += 0.25              // long-suit chance
      }
    }
    // Jack: marginal, only with Queen support and real length.
    if (jack && queen && n >= 4) tricks += 0.25
    return tricks
  }

  /**
   * Length tricks: in a long suit, low cards become winners once the high cards
   * everyone else holds are exhausted. Cards up to the fourth earn nothing.
   */
  internal fun lengthTricksForSuit(count: Int): Double {
    if (count <= 4) return 0.0
    // Calibrated down from 0.5 + 0.75/card: opponents also hold high cards, so
    // long-suit low cards win less often than a naive count suggests.
    return 0.4 + (count - 5) * 0.6
  }

  /**
   * Ruffing value of a *side* suit holding [sideCount] cards when the hand
   * holds [trumpCount] trumps. Void is strongest, singleton good, doubleton
   * mild; three or more is nothing.
   *
   * You cannot ruff more often than you have spare trumps, so the raw value is
   * capped just above the trumps left over after keeping ~2 for drawing.
   */
  internal fun ruffTricksForSideSuit(sideCount: Int, trumpCount: Int): Double {
    if (trumpCount == 0) return 0.0
    val spareTrumps = max(0, trumpCount - 2)
    // Calibrated down from 2.0 / 1.0 / 0.25: ruffs are often pre-empted — the
    // suit is led before you're void, opponents over-ruff, or you must follow.
    val raw = when (sideCount) {
      0 -> 1.5      // void: multiple ruffs
      1 -> 0.75     // singleton: one likely ruff
      2 -> 0.15     // doubleton: occasional
      else -> 0.0
    }
    return min(raw, spareTrumps + 0.5)
  }

  /** Round to two decimals, matching the source's `round2()` exactly. */
  internal fun round2(n: Double): Double = round(n * 100.0) / 100.0

  private const val ACE = 14
  private const val KING = 13
  private const val QUEEN = 12
  private const val JACK = 11
}
