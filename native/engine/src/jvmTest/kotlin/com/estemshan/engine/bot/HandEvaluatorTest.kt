package com.estemshan.engine.bot

import com.estemshan.engine.Card
import com.estemshan.engine.RANKS
import com.estemshan.engine.Suit
import com.estemshan.engine.Suit.CLUBS
import com.estemshan.engine.Suit.DIAMONDS
import com.estemshan.engine.Suit.HEARTS
import com.estemshan.engine.Suit.SANS
import com.estemshan.engine.Suit.SPADES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the S5 port. Every expectation below is hand-computed from
 * `AI Bots/botEngine.ts` §1 — not from running the TypeScript, which has no
 * toolchain here — so the arithmetic is spelled out in comments for review.
 *
 * Doubles are asserted at 1e-9 rather than exactly: the port keeps the source's
 * `round2()` two-decimal rounding, and 0.6/0.4 have no exact binary form. The
 * reasoning string is deliberately not asserted — it is diagnostic only, and
 * Kotlin renders a whole Double as "6.0" where the source renders "6".
 */
class HandEvaluatorTest {

  private val eval = HandEvaluator

  // ==========================================================================
  //  Hands. Ranks are integers (ACE 14 … 2) to match Card.value.
  // ==========================================================================

  /** 8-card spade suit, two side ace-kings, two short side suits. */
  private fun handA(): List<Card> = hand(
    c(SPADES, 14, 13, 12, 11, 10, 9, 8, 7),
    c(HEARTS, 14, 13),
    c(DIAMONDS, 5, 3),
    c(CLUBS, 2),
  )

  private fun c(suit: Suit, vararg ranks: Int): List<Card> =
    ranks.map { Card(suit, RANKS.first { r -> r.v == it }) }

  private fun hand(vararg parts: List<Card>): List<Card> = parts.flatMap { it }

  private fun List<SuitBreakdown>.bySuit(): Map<Suit, SuitBreakdown> = associateBy { it.suit }

  // ==========================================================================
  //  Golden evaluation: handA under spades, full confidence
  // ==========================================================================

  @Test
  fun handAUnderSpadesScoresEightAndAHalf() {
    // Spades (trump, 8): A +K +Q +J-with-Q-and-length = 3.25 high card;
    // length beyond the 4th = 0.4 + 3*0.6 = 2.2; no ruff (it is the trump).
    // Hearts (2): A+K = 2.0; ruff 0.15.
    // Diamonds (2): no honors; ruff 0.15.
    // Clubs (1): ruff 0.75 (trumpCount 8, so the spare-trump cap never bites).
    // -> highCard 5.25, length 2.2, ruff 1.05 => 8.5
    val e = eval.evaluateHand(handA(), SPADES, 1.0)

    assertEquals(SPADES, e.trump)
    assertEquals(8.5, e.rawExpected, 1e-9)
    assertEquals(8.5, e.expectedTricks, 1e-9)
    assertEquals(4, e.breakdown.size)
  }

  @Test
  fun handABreakdownIsComputedPerSuit() {
    val b = eval.evaluateHand(handA(), SPADES, 1.0).breakdown.bySuit()

    assertEquals(8, b[SPADES]!!.count)
    assertEquals(3.25, b[SPADES]!!.highCardTricks, 1e-9)
    assertEquals(2.2, b[SPADES]!!.lengthTricks, 1e-9)
    assertEquals(0.0, b[SPADES]!!.ruffTricks, 1e-9)
    assertTrue(listOf(b[SPADES]!!.hasAce, b[SPADES]!!.hasKing, b[SPADES]!!.hasQueen).all { it })

    assertEquals(2, b[HEARTS]!!.count)
    assertEquals(2.0, b[HEARTS]!!.highCardTricks, 1e-9)
    assertEquals(0.15, b[HEARTS]!!.ruffTricks, 1e-9)
    assertTrue(b[HEARTS]!!.hasAce && b[HEARTS]!!.hasKing && !b[HEARTS]!!.hasQueen)

    // An honorless doubleton contributes nothing but a small ruff.
    assertEquals(2, b[DIAMONDS]!!.count)
    assertEquals(0.0, b[DIAMONDS]!!.highCardTricks, 1e-9)
    assertEquals(0.15, b[DIAMONDS]!!.ruffTricks, 1e-9)
    assertTrue(listOf(b[DIAMONDS]!!.hasAce, b[DIAMONDS]!!.hasKing, b[DIAMONDS]!!.hasQueen).none { it })

    assertEquals(1, b[CLUBS]!!.count)
    assertEquals(0.75, b[CLUBS]!!.ruffTricks, 1e-9)
  }

  // ==========================================================================
  //  Confidence scales only distribution — never honors
  // ==========================================================================

  @Test
  fun confidenceLowersTheBidButNeverTheRawEstimate() {
    val expert = eval.evaluateHand(handA(), SPADES, 1.0)
    val easy = eval.evaluateHand(handA(), SPADES, 0.30)

    // rawExpected is confidence-independent by construction: 5.25 + 2.2 + 1.05
    assertEquals(expert.rawExpected, easy.rawExpected, 1e-9)

    // Only the 3.25 distributional tricks shrink: 5.25 + 3.25*0.30 = 6.225
    assertEquals(6.225, easy.expectedTricks, 1e-9)
    assertTrue("weak tier must value the hand less", easy.expectedTricks < expert.expectedTricks)
  }

  // ==========================================================================
  //  evaluateAllTrumps: ordering, size, and the ruff cap
  // ==========================================================================

  @Test
  fun allTrumpsEvaluatesFiveDeclarationsStrongestFirst() {
    // handA, full confidence:
    //   SPADES   5.25 + (2.20 + 1.05) = 8.50
    //   SANS     5.25 + (2.20 + 0.00) = 7.45   <- length counts in every suit
    //   HEARTS   5.25 + (0.00 + 0.65) = 5.90   <- ruff capped by 2 trumps
    //   DIAMONDS 5.25 + (0.00 + 0.65) = 5.90   <- ties keep suit order
    //   CLUBS    5.25 + (0.00 + 0.30) = 5.55
    val all = eval.evaluateAllTrumps(handA(), 1.0)

    assertEquals(5, all.size)
    assertEquals(listOf(SPADES, SANS, HEARTS, DIAMONDS, CLUBS), all.map { it.trump })
    val scores = all.map { it.expectedTricks }
    repeat(scores.size - 1) { i -> assertTrue("must be sorted descending", scores[i] >= scores[i + 1]) }
    assertEquals(8.5, scores[0], 1e-9)
    assertEquals(5.55, scores.last(), 1e-9)
  }

  @Test
  fun aWeakTierRanksTheDeclarationsIdentically() {
    // Confidence is a positive scale on a trump-independent constant, so the
    // argmax — and the whole ordering — cannot move with the tier. See
    // chooseBestTrump's kdoc for why this is a property, not a coincidence.
    val expert = eval.evaluateAllTrumps(handA(), 1.0).map { it.trump }
    val easy = eval.evaluateAllTrumps(handA(), 0.30).map { it.trump }

    assertEquals(expert, easy)
  }

  @Test
  fun chooseBestTrumpPicksTheBestSuitAtEveryTier() {
    assertEquals(SPADES, eval.chooseBestTrump(handA(), BotTier.EXPERT))
    assertEquals(SPADES, eval.chooseBestTrump(handA(), BotTier.EASY))
  }

  @Test
  fun ruffValueIsCappedBySpareTrumps() {
    // Declaring hearts leaves only 2 trumps, so spareTrumps = 0 and every ruff
    // is capped at 0.5: the singleton club's raw 0.75 is held to 0.5, while the
    // doubletons' 0.15 passes under the cap untouched.
    val b = eval.evaluateHand(handA(), HEARTS, 1.0).breakdown.bySuit()

    assertEquals(0.5, b[CLUBS]!!.ruffTricks, 1e-9)
    assertEquals(0.15, b[DIAMONDS]!!.ruffTricks, 1e-9)
    assertEquals(0.0, b[HEARTS]!!.ruffTricks, 1e-9)  // trump suit itself: no ruff
    assertEquals(0.0, b[SPADES]!!.ruffTricks, 1e-9)  // 8 cards: too long to ruff
  }

  // ==========================================================================
  //  Sans: length counts everywhere, ruffing is impossible
  // ==========================================================================

  @Test
  fun sansCountsLengthInEverySuitAndNeverRuffs() {
    // 5.25 high card + 2.2 length (the 8-card spade suit) = 7.45; every ruff is
    // zero because there is no trump to ruff with.
    val e = eval.evaluateHand(handA(), SANS, 1.0)

    assertEquals(7.45, e.expectedTricks, 1e-9)
    assertTrue("Sans can never produce ruffing value", e.breakdown.all { it.ruffTricks == 0.0 })
    // The 8-card suit is now length-scored where it was trump-only before.
    assertEquals(2.2, e.breakdown.bySuit()[SPADES]!!.lengthTricks, 1e-9)
  }

  // ==========================================================================
  //  Edge hands
  // ==========================================================================

  @Test
  fun aThirteenCardSuitEstimatesNearGrandSlam() {
    // A-K-Q-J + nine more: 3.25 high card, 0.4 + 8*0.6 = 5.2 length, three void
    // side suits ruffing at 1.5 each = 4.5 => 12.95.
    val onlySpades = c(SPADES, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2)
    val e = eval.evaluateHand(onlySpades, SPADES, 1.0)

    assertEquals(13, e.breakdown.sumOf { it.count })
    assertEquals(12.95, e.expectedTricks, 1e-9)
  }

  @Test
  fun anEmptyHandEstimatesNothing() {
    val e = eval.evaluateHand(emptyList(), SPADES, 1.0)

    assertEquals(0.0, e.expectedTricks, 1e-9)
    assertEquals(0.0, e.rawExpected, 1e-9)
    assertEquals(0, e.breakdown[0].count)
    // Every declaration scores zero, so the stable order puts spades first.
    assertEquals(SPADES, eval.chooseBestTrump(emptyList()))
  }

  // ==========================================================================
  //  Honor-combination table, one rule at a time
  // ==========================================================================

  @Test
  fun highCardTableValuesEveryHonorCombination() {
    // ace alone
    assertEquals(1.0, eval.highCardTricksForSuit(c(SPADES, 14)), 1e-9)
    // bare king — unguarded, it will be captured
    assertEquals(0.0, eval.highCardTricksForSuit(c(SPADES, 13)), 1e-9)
    // guarded king
    assertEquals(0.5, eval.highCardTricksForSuit(c(SPADES, 13, 2)), 1e-9)
    // A-K is solid: the ace makes the king good
    assertEquals(2.0, eval.highCardTricksForSuit(c(SPADES, 14, 13)), 1e-9)
    // A-K-Q
    assertEquals(3.0, eval.highCardTricksForSuit(c(SPADES, 14, 13, 12)), 1e-9)
    // unsupported queen is nearly worthless
    assertEquals(0.0, eval.highCardTricksForSuit(c(SPADES, 12)), 1e-9)
    // queen with length but no higher honor
    assertEquals(0.25, eval.highCardTricksForSuit(c(SPADES, 12, 5, 4, 3)), 1e-9)
    // queen supported by one higher honor and guarded: the ace's 1.0 + 0.5
    assertEquals(1.5, eval.highCardTricksForSuit(c(SPADES, 14, 12, 5, 4)), 1e-9)
    // jack only earns anything beside the queen and real length
    assertEquals(0.5, eval.highCardTricksForSuit(c(SPADES, 12, 11, 5, 4, 3)), 1e-9)
    // a suit with no honors earns nothing
    assertEquals(0.0, eval.highCardTricksForSuit(c(SPADES, 10, 9, 8, 7)), 1e-9)
  }

  @Test
  fun anAceQueenDoubletonValuesTheQueenAtNothing() {
    // A+Q with nothing else: the guard rule needs 3 cards, and A-K-Q needs the
    // king. The queen is not yet a trick — worth pinning because it is the
    // least intuitive row of the table.
    assertEquals(1.0, eval.highCardTricksForSuit(c(SPADES, 14, 12)), 1e-9)
  }

  @Test
  fun lengthTricksStartBeyondTheFourthCard() {
    for (count in 0..4) {
      assertEquals("$count cards should earn no length tricks", 0.0, eval.lengthTricksForSuit(count), 1e-9)
    }
    // 0.4 for the fifth card, then 0.6 each
    assertEquals(0.4, eval.lengthTricksForSuit(5), 1e-9)
    assertEquals(1.0, eval.lengthTricksForSuit(6), 1e-9)
    assertEquals(1.6, eval.lengthTricksForSuit(7), 1e-9)
    assertEquals(5.2, eval.lengthTricksForSuit(13), 1e-9)
  }

  @Test
  fun ruffingRequiresTrumpsAndAShortSideSuit() {
    // No trumps held: nothing can ruff, whatever the shape.
    assertEquals(0.0, eval.ruffTricksForSideSuit(0, 0), 1e-9)
    // Five trumps (three spare): raw values survive uncapped.
    assertEquals(1.5, eval.ruffTricksForSideSuit(0, 5), 1e-9)
    assertEquals(0.75, eval.ruffTricksForSideSuit(1, 5), 1e-9)
    assertEquals(0.15, eval.ruffTricksForSideSuit(2, 5), 1e-9)
    // Three-card side suits and longer are not ruffing values.
    assertEquals(0.0, eval.ruffTricksForSideSuit(3, 5), 1e-9)
    assertEquals(0.0, eval.ruffTricksForSideSuit(5, 5), 1e-9)
  }

  // ==========================================================================
  //  Tier table — S6/S7/S8/S9 all read these numbers
  // ==========================================================================

  @Test
  fun tierTableMatchesThePortBaseline() {
    // Values transcribed from AI Bots/botEngine.ts TIER_CONFIG. If any of these
    // move, the golden bids in S10 move with them.
    assertEquals(0.25, BotTier.EASY.mistakeRate, 1e-9)
    assertEquals(0.30, BotTier.EASY.distributionConfidence, 1e-9)
    assertEquals(1.20, BotTier.EASY.bidNoise, 1e-9)
    assertAllFalse(BotTier.EASY.canDash, BotTier.EASY.countsCards, BotTier.EASY.modelsOpponents, BotTier.EASY.usesSimulation)

    assertEquals(0.10, BotTier.MEDIUM.mistakeRate, 1e-9)
    assertEquals(0.70, BotTier.MEDIUM.distributionConfidence, 1e-9)
    assertEquals(0.60, BotTier.MEDIUM.bidNoise, 1e-9)
    assertTrue(BotTier.MEDIUM.canDash)
    assertAllFalse(BotTier.MEDIUM.countsCards, BotTier.MEDIUM.modelsOpponents, BotTier.MEDIUM.usesSimulation)

    // HARD is where the bot starts counting and modelling opponents, but still
    // does not simulate — that is why the heuristic SimPort default must exist.
    assertEquals(0.03, BotTier.HARD.mistakeRate, 1e-9)
    assertEquals(1.00, BotTier.HARD.distributionConfidence, 1e-9)
    assertEquals(0.20, BotTier.HARD.bidNoise, 1e-9)
    assertAllTrue(BotTier.HARD.canDash, BotTier.HARD.countsCards, BotTier.HARD.modelsOpponents)
    assertFalse(BotTier.HARD.usesSimulation)

    assertEquals(0.00, BotTier.EXPERT.mistakeRate, 1e-9)
    assertEquals(1.00, BotTier.EXPERT.distributionConfidence, 1e-9)
    assertEquals(0.00, BotTier.EXPERT.bidNoise, 1e-9)
    assertAllTrue(
      BotTier.EXPERT.canDash, BotTier.EXPERT.countsCards,
      BotTier.EXPERT.modelsOpponents, BotTier.EXPERT.usesSimulation,
    )
  }

  @Test
  fun monotonicSkillGradientHoldsAcrossTiers() {
    // The tiers must actually get harder — the whole point of the table.
    val tiers = BotTier.values()
    for (i in 0 until tiers.size - 1) {
      val weak = tiers[i]
      val strong = tiers[i + 1]
      assertTrue("$weak must make more mistakes than $strong", weak.mistakeRate > strong.mistakeRate)
      assertTrue("$weak must trust distribution no more than $strong",
        weak.distributionConfidence <= strong.distributionConfidence)
      assertTrue("$weak must be noisier than $strong", weak.bidNoise > strong.bidNoise)
    }
    // EASY is the only tier banned from Dash, and EXPERT the only simulator.
    assertEquals(listOf(true, true, true), listOf(BotTier.MEDIUM.canDash, BotTier.HARD.canDash, BotTier.EXPERT.canDash))
    assertEquals(listOf(false, false, false, true), tiers.map { it.usesSimulation })
  }

  @Test
  fun normalizeMapsKnownLevelsAndDefaultsToMedium() {
    assertEquals(BotTier.EASY, BotTier.normalize("EASY"))
    assertEquals(BotTier.MEDIUM, BotTier.normalize("MEDIUM"))
    assertEquals(BotTier.HARD, BotTier.normalize("HARD"))
    assertEquals(BotTier.EXPERT, BotTier.normalize("EXPERT"))
    // Unrecognised input falls back to MEDIUM, matching normalizeTier(): a
    // missing or stale persisted difficulty must not crash a match.
    assertEquals(BotTier.MEDIUM, BotTier.normalize(null))
    assertEquals(BotTier.MEDIUM, BotTier.normalize(""))
    assertEquals(BotTier.MEDIUM, BotTier.normalize("easy"))
  }

  // ==========================================================================

  private fun assertAllFalse(vararg actual: Boolean) = actual.forEach { assertFalse(it) }

  private fun assertAllTrue(vararg actual: Boolean) = actual.forEach { assertTrue(it) }
}
