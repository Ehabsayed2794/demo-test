package com.estemshan.engine.bot

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.EmitResult
import com.estemshan.engine.RANKS
import com.estemshan.engine.Suit
import com.estemshan.engine.Suit.CLUBS
import com.estemshan.engine.Suit.DIAMONDS
import com.estemshan.engine.Suit.HEARTS
import com.estemshan.engine.Suit.SANS
import com.estemshan.engine.Suit.SPADES
import com.estemshan.engine.emit
import com.estemshan.engine.initNormalRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for S9 — the personality style layer and the `SimPort` bid-time
 * seam (plan §2A).
 *
 * What these pin is deliberately *not* "the bot bids well". Skill is S6's
 * business and has its own suite; S9 adds two injectable layers over that
 * estimate and each has one property worth a regression:
 *
 *  * **Personality is a direction, not a target.** The source's `bidBias` is a
 *    nudge applied before a single rounding, so the golden asserts the
 *    documented ordering on the same hand — aggressive above balanced above
 *    conservative — and pins the one strict step the tuned values actually
 *    produce, rather than pretending every hand shifts by exactly the bias.
 *  * **`dashEagerness` is not inert.** In the source the field reads dead: its
 *    only branch is unreachable behind the dash gate, so all four profiles dash
 *    identically. The port scales the gate instead, and a hand sitting between
 *    the BALANCED and TRICKSTER ceilings separates all four verdicts.
 *  * **The seam is load-bearing for HARD, and swappable.** Without it, HARD
 *    trusts the evaluator's optimistic ceiling and over-bids — so the default
 *    must demonstrably cut an entry-starved hand, and an injected simulation
 *    must demonstrably override that default, in both directions (bid and dash).
 *
 * Every test holds the tier, the hand and the seat fixed and varies only the
 * injected layer, which is what makes the assertion about that layer.
 */
class BotPersonalityTest {

  private val brain = BidBrain

  // ==========================================================================
  //  Hands — integer ranks, same construction as BidBrainTest
  // ==========================================================================

  /** Eight spades headed A-K-Q-J plus two side ace-kings: worth exactly 8.5
   *  tricks under its own best trump — a rounding boundary, which is what makes
   *  a +/- 0.4 bias observable in the *rounded* call. */
  private fun strongHand(): List<Card> = hand(
    c(SPADES, 14, 13, 12, 11, 10, 9, 8, 7),
    c(HEARTS, 14, 13),
    c(DIAMONDS, 5, 3),
    c(CLUBS, 2),
  )

  /** Eight trumps but only *one* entry (the ace), doubletons in two side suits
   *  and a singleton club. The evaluator books every length and ruffing trick
   *  this could win — 4.25, enough to open the auction at 4 — but cashing them
   *  needs the lead, and this hand cannot regain it. That is the class of hand
   *  HARD over-bids when the seam is absent. */
  private fun entryStarvedHand(): List<Card> = hand(
    c(SPADES, 14, 2, 3, 4, 5, 6, 7, 8),
    c(HEARTS, 2, 3),
    c(DIAMONDS, 4, 5),
    c(CLUBS, 6),
  )

  /** No honors anywhere, six small spades, a singleton heart and two triples.
   *  The best-trump ceiling is 1.75: above BALANCED's historical 1.5 gate but
   *  below TRICKSTER's widened 2.1, so the hand separates the dash-eager from
   *  the dash-shy. */
  private fun marginalDashHand(): List<Card> = hand(
    c(SPADES, 2, 3, 4, 5, 6, 7),
    c(HEARTS, 8),
    c(DIAMONDS, 9, 10, 11),
    c(CLUBS, 4, 5, 6),
  )

  /** The bankrupt hand from BidBrainTest: no ace, no king, nothing but small
   *  cards. A dash the default seam accepts (P(zero) ~ 0.94) but an injected
   *  skeptic can veto. */
  private fun bankruptHand(): List<Card> = hand(
    c(SPADES, 2, 3),
    c(HEARTS, 4, 5),
    c(DIAMONDS, 6, 7),
    c(CLUBS, 8, 9, 10),
  )

  private fun c(suit: Suit, vararg ranks: Int): List<Card> =
    ranks.map { Card(suit, RANKS.first { r -> r.v == it }) }

  private fun hand(vararg parts: List<Card>): List<Card> = parts.flatMap { it }

  // ==========================================================================
  //  1. Personality direction — aggressive >= balanced >= conservative
  // ==========================================================================

  @Test
  fun personalitiesShiftTheSameHandInTheDocumentedOrder() {
    // strongHand is worth exactly 8.5 and EXPERT is noiseless (bidNoise 0.0),
    // so the estimate reaching the style layer is identical for every profile;
    // only the bias moves the rounded call. 8.5 is a banker's-rounding boundary
    // (round to even -> 8), so this hand is where a positive bias is visible:
    // AGGRESSIVE's +0.4 reaches 8.9 and calls 9, BALANCED stays at 8.
    val aggressive = auctionCall(BotPersonality.AGGRESSIVE)
    val trickster = auctionCall(BotPersonality.TRICKSTER)
    val balanced = auctionCall(BotPersonality.BALANCED)
    val conservative = auctionCall(BotPersonality.CONSERVATIVE)

    // The exact calls the tuned values produce on this boundary hand.
    assertEquals(9, aggressive)
    assertEquals(9, trickster)
    assertEquals(8, balanced)
    assertEquals(8, conservative)

    // The documented direction, as an inequality — the form that survives a
    // hand where the bias does not happen to cross a rounding boundary.
    assertTrue("aggressive must call at least as high as balanced", aggressive >= balanced)
    assertTrue("balanced must call at least as high as conservative", balanced >= conservative)
  }

  @Test
  fun theOrderingHoldsAcrossADealtBoard() {
    // The ordering is a consequence of the bias values plus a monotone round,
    // so it holds on every hand the dealer can produce. Asserted rather than
    // trusted: reordering the enum or flipping a bias sign is a failing test,
    // and a hand that broke it here would mean the style layer is not the pure
    // shift its contract claims.
    repeat(40) { seed ->
      val dealt = deal(seed)
      val trump = HandEvaluator.chooseBestTrump(dealt)
      val estimate = HandEvaluator.evaluateHand(dealt, trump, confidence = 1.0).expectedTricks

      val aggressive = styled(noisy = estimate, rawFloat = estimate, BotPersonality.AGGRESSIVE)
      val trickster = styled(noisy = estimate, rawFloat = estimate, BotPersonality.TRICKSTER)
      val balanced = styled(noisy = estimate, rawFloat = estimate, BotPersonality.BALANCED)
      val conservative = styled(noisy = estimate, rawFloat = estimate, BotPersonality.CONSERVATIVE)

      assertTrue(
        "seed $seed broke the ordering: $aggressive/$trickster/$balanced/$conservative",
        aggressive >= trickster && trickster >= balanced && balanced >= conservative,
      )
    }
  }

  @Test
  fun theStyleLayerShiftsAnEstimateInEachDocumentedDirection() {
    // The pure style function on estimates chosen to sit on the rounding
    // boundaries the biases actually cross. The raw estimate is held fixed;
    // only the personality changes — so this isolates the shift from the
    // evaluator and the seam that produce the number.
    assertEquals(7, styled(noisy = 7.30, rawFloat = 7.30, BotPersonality.BALANCED))
    assertEquals(8, styled(noisy = 7.30, rawFloat = 7.30, BotPersonality.AGGRESSIVE)) // +0.4 -> 7.7

    assertEquals(8, styled(noisy = 7.90, rawFloat = 7.90, BotPersonality.BALANCED))
    assertEquals(7, styled(noisy = 7.90, rawFloat = 7.90, BotPersonality.CONSERVATIVE)) // -0.5 -> 7.4
  }

  @Test
  fun aSevenOnlyBecomesASuperCallWhenTheRawEstimateBacksIt() {
    // The source's super-call gate has two independent conditions: the
    // *rounded* bid sits at 7 AND the *raw* estimate already reached 7. The
    // second is the guard — a 7 manufactured by the bias off a weaker hand
    // must not be pushed to 8.
    assertEquals(8, styled(noisy = 7.00, rawFloat = 7.00, BotPersonality.AGGRESSIVE)) // 0.7 appetite
    assertEquals(8, styled(noisy = 7.00, rawFloat = 7.00, BotPersonality.TRICKSTER)) // 0.6 appetite
    assertEquals(7, styled(noisy = 7.00, rawFloat = 7.00, BotPersonality.BALANCED)) // 0.3, below the gate
    assertEquals(6, styled(noisy = 7.00, rawFloat = 7.00, BotPersonality.CONSERVATIVE)) // -0.5 -> 6.5 -> 6

    // The guard firing: AGGRESSIVE rounds 6.6 + 0.4 up to 7, but the raw hand
    // was never worth 7, so the push must not happen.
    assertEquals(7, styled(noisy = 6.60, rawFloat = 6.60, BotPersonality.AGGRESSIVE))
  }

  @Test
  fun theBalancedPersonalityLeavesEveryEstimateUntouched() {
    // BALANCED is the identity — the property that keeps every pre-S9 caller
    // of BidBrain behaving exactly as before the style layer existed, and the
    // reason decide() defaults to it.
    for (estimate in listOf(0.0, 1.5, 3.7, 6.6, 7.0, 8.5, 12.9)) {
      val base = BotBid(
        rawFloat = estimate,
        noisy = estimate,
        intendedBid = kotlin.math.round(estimate).toInt(),
        potentialTrump = SPADES,
        reasoning = "unchanged",
      )
      assertEquals("BALANCED must be the identity at $estimate", base, applyPersonalityToBid(base, BotPersonality.DEFAULT))
    }
  }

  // ==========================================================================
  //  2. dashEagerness — trickster dashes more
  // ==========================================================================

  @Test
  fun tricksterAndConservativeDashWhereAggressiveAndBalancedRefuse() {
    // marginalDashHand's best-trump ceiling is 1.75. MEDIUM trusts the dash
    // gate alone (the seam's verification only runs for HARD/EXPERT), so on
    // this tier the personality's eagerness is the *only* thing separating the
    // four verdicts — which makes it the tier the divergence is provable on.
    //
    // In the source this separation does not exist: the field's only reader is
    // unreachable behind the 1.5 gate, so all four profiles dash identically.
    // The port scales the ceiling instead, which is what the field's own
    // documentation (">1 = dashes more often") and the source's blurbs already
    // promise. See BidBrain.dashSignal for the dead-branch proof.
    assertFalse("AGGRESSIVE (0.6) tightens the gate to 0.9", dashVerdict(BotPersonality.AGGRESSIVE))
    assertFalse("BALANCED (1.0) keeps the historical 1.5", dashVerdict(BotPersonality.BALANCED))
    assertTrue("TRICKSTER (1.4) widens the gate to 2.1", dashVerdict(BotPersonality.TRICKSTER))
    assertTrue("CONSERVATIVE (1.6) widens the gate to 2.4", dashVerdict(BotPersonality.CONSERVATIVE))
  }

  // ==========================================================================
  //  3. The SimPort default keeps HARD's bids sane
  // ==========================================================================

  @Test
  fun theSimPortDefaultStopsHardOvercallingAnEntryStarvedHand() {
    val hand = entryStarvedHand()
    val state = skipDash(initNormalRound(round = 1, dealer = "p1"))

    // The raw evaluator is an optimistic ceiling — it books every length and
    // ruffing trick the hand could take if everything broke right. Here that
    // is 4.25: enough to open the auction at the 4-trick floor on one entry.
    val optimistic = HandEvaluator.evaluateHand(hand, SPADES, confidence = 1.0).expectedTricks
    assertEquals(4.25, optimistic, 1e-9)

    // The ship-default seam caps the distributional part at what the hand's
    // entries can actually support: 3.0. Cashing three length/ruffing tricks
    // needs the lead three times, and this hand holds it once.
    val realistic = BotSimulation.Default.estimateBid(hand, SPADES).estimate
    assertEquals(3.0, realistic, 1e-9)

    // HARD's +/- 0.2 seat jitter cannot cross an integer from 3.0 or 4.25, so
    // the verdict is robust to which seat holds the hand: the default passes.
    val intent = brain.decide(state, hand, "p1", BotTier.HARD) as BiddingIntent.AuctionBid
    assertTrue("HARD must pass an entry-starved hand, not open at 4", intent.isPass)
  }

  // ==========================================================================
  //  4. An injected simulation overrides the default
  // ==========================================================================

  @Test
  fun anInjectedSimulationOverridesTheDefaultBid() {
    val hand = entryStarvedHand()
    val state = skipDash(initNormalRound(round = 1, dealer = "p1"))

    // Same hand, tier and personality; only the simulation changes.
    val withDefault = brain.decide(state, hand, "p1", BotTier.HARD, BotPersonality.DEFAULT, BotSimulation.Default)
    assertTrue("the ship default passes this hand", (withDefault as BiddingIntent.AuctionBid).isPass)

    // A simulation reporting the raw optimistic count — as a real Monte-Carlo
    // would if the hand's entries were actually secure — flips the call to 4.
    // The seam is the only thing between the heuristic and the bid, so a
    // future simulator drops in here without touching the brain.
    val optimistic = BotSimulation { _, _ -> SimBid(estimate = 4.25, confidence = 1.0, reasoning = "injected: optimistic") }
    val withInjected = brain.decide(state, hand, "p1", BotTier.HARD, BotPersonality.DEFAULT, optimistic) as BiddingIntent.AuctionBid
    assertFalse("an injected simulation must reach the bid", withInjected.isPass)
    assertEquals(4, withInjected.tricks)
  }

  @Test
  fun anInjectedSimulationCanVetoADashTheDefaultAccepts() {
    val hand = bankruptHand()
    val state = initNormalRound(round = 1, dealer = "p1")

    // The default rates this hand very likely to win zero (~0.94), so HARD's
    // seam verification accepts the dash the gate proposed.
    val withDefault = brain.decide(state, hand, "p1", BotTier.HARD, BotPersonality.DEFAULT, BotSimulation.Default)
    assertTrue("the ship default dashes this bankrupt hand", (withDefault as BiddingIntent.DashCallDecision).declaredDashCall)

    // A simulation that sees forced tricks the heuristic missed vetoes the
    // dash — without changing the gate, the personality, the tier or the hand.
    // This is the override direction that keeps a real Monte-Carlo honest: a
    // dash it disagrees with is a dash that does not happen.
    val skeptical = object : BotSimulation {
      override fun estimateBid(hand: List<Card>, assumedTrump: Suit) =
        SimBid(estimate = 1.0, confidence = 1.0, reasoning = "injected: skeptical")
      override fun estimateDashSuccess(hand: List<Card>): Double = 0.0
    }
    val withInjected = brain.decide(state, hand, "p1", BotTier.HARD, BotPersonality.DEFAULT, skeptical) as BiddingIntent.DashCallDecision
    assertFalse("an injected simulation must be able to veto the dash", withInjected.declaredDashCall)
  }

  // ==========================================================================
  //  Harness
  // ==========================================================================

  /** The trick count a [personality] calls on [hand] at [tier] in the auction,
   *  holding the seat fixed. Asserts the hand calls rather than passes so a
   *  caller reading `tricks` is not silently reading a pass. */
  private fun auctionCall(
    personality: BotPersonality,
    hand: List<Card> = strongHand(),
    tier: BotTier = BotTier.EXPERT,
  ): Int {
    val state = skipDash(initNormalRound(round = 1, dealer = "p1"))
    val intent = brain.decide(state, hand, "p1", tier, personality) as BiddingIntent.AuctionBid
    assertFalse("this hand must call, not pass", intent.isPass)
    return intent.tricks!!
  }

  /** Whether a [personality] declares a Dash Call on [hand] at [tier], holding
   *  the seat and the round fixed so only the personality varies. */
  private fun dashVerdict(
    personality: BotPersonality,
    tier: BotTier = BotTier.MEDIUM,
    hand: List<Card> = marginalDashHand(),
  ): Boolean {
    val state = initNormalRound(round = 1, dealer = "p1")
    val intent = brain.decide(state, hand, "p1", tier, personality) as BiddingIntent.DashCallDecision
    return intent.declaredDashCall
  }

  /** Run the pure style function on a synthetic estimate, the way the estimator
   *  does: the bias lands on [noisy] and the super-call appetite is measured
   *  against [rawFloat]. */
  private fun styled(noisy: Double, rawFloat: Double, personality: BotPersonality): Int =
    applyPersonalityToBid(
      BotBid(
        rawFloat = rawFloat,
        noisy = noisy,
        intendedBid = kotlin.math.round(noisy).toInt(),
        potentialTrump = SPADES,
        reasoning = "test",
      ),
      personality,
    ).intendedBid

  private fun skipDash(state: BiddingState): BiddingState {
    var s = state
    while (s.subPhase == BiddingPhase.DASH) {
      val seat = s.waitingFor ?: break
      s = emitAndApply(s, BiddingIntent.DashCallDecision(seat, false))
    }
    return s
  }

  /** Apply any emit result that keeps the round moving; fail on rejection. */
  private fun emitAndApply(state: BiddingState, intent: BiddingIntent): BiddingState =
    when (val result = emit(state, intent)) {
      is EmitResult.Applied -> result.state
      is EmitResult.Completed -> result.state
      is EmitResult.GeneralPass -> error("unexpected general pass in the test harness")
      is EmitResult.Rejected -> error("brain emitted an intent the reducer rejected: ${result.reason}")
    }

  /** A deterministic pseudo-hand, same generator as BidBrainTest's deal(). */
  private fun deal(seed: Int): List<Card> {
    val all = Suit.entries.filter { it != SANS }.flatMap { s ->
      RANKS.map { Card(s, it) }
    }
    return all.sortedBy { (it.suit.name.hashCode() * 31 + it.value * 7 + seed) }.take(13)
  }
}