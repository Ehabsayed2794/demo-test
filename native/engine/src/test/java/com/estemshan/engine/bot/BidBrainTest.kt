package com.estemshan.engine.bot

import com.estemshan.engine.BidType
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
import com.estemshan.engine.auctionBidBeatsTop
import com.estemshan.engine.auctionBidIsWith
import com.estemshan.engine.canSubmit
import com.estemshan.engine.dashCallerIds
import com.estemshan.engine.emit
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Golden tests for the S6 bidding brain.
 *
 * The contract under test is narrower and stronger than "it bids well": every
 * intent the brain produces must clear `canSubmit`, and it must clear it
 * *immediately* — the TypeScript source compensated with a 20-attempt
 * retry loop in FOLLOWING_BIDS, and the port replaces that loop with
 * correctness, so a brain that needs a second attempt is a failing test.
 */
class BidBrainTest {

  private val brain = BidBrain

  // ==========================================================================
  //  Hands — same integer-rank construction as HandEvaluatorTest
  // ==========================================================================

  /** A genuinely strong hand: 8 spades headed A-K-Q-J, two side ace-kings. */
  private fun strongHand(): List<Card> = hand(
    c(SPADES, 14, 13, 12, 11, 10, 9, 8, 7),
    c(HEARTS, 14, 13),
    c(DIAMONDS, 5, 3),
    c(CLUBS, 2),
  )

  /** A genuinely bankrupt hand: no ace, no king, nothing but small cards. */
  private fun dashHand(): List<Card> = hand(
    c(SPADES, 2, 3),
    c(HEARTS, 4, 5),
    c(DIAMONDS, 6, 7),
    c(CLUBS, 8, 9, 10),
  )

  private fun c(suit: Suit, vararg ranks: Int): List<Card> =
    ranks.map { Card(suit, RANKS.first { r -> r.v == it }) }

  private fun hand(vararg parts: List<Card>): List<Card> = parts.flatMap { it }

  // ==========================================================================
  //  The contract: every intent clears canSubmit, first try
  // ==========================================================================

  @Test
  fun everyIntentAcrossAFullNormalRoundClearsCanSubmit() {
    // Four EXPERTs dealt known hands, bidding a complete normal round through
    // the real reducer. If the brain ever emits something canSubmit rejects,
    // decide() itself throws first — so reaching DONE is the assertion.
    val outcome = playRound(strongHand(), dashHand(), strongHand(), dashHand())

    assertTrue("a four-bot round must complete", outcome.subPhase == BiddingPhase.DONE)
    assertEquals(4, outcome.bids.size)
  }

  @Test
  fun decideRejectsBeingAskedForTheWrongSeat() {
    val state = initNormalRound(round = 1, dealer = "p1")
    try {
      brain.decide(state, strongHand(), playerId = "p2", tier = BotTier.EXPERT)
      fail("decide must refuse a seat the state is not waiting on")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("waiting on p1"))
    }
  }

  @Test
  fun decideRefusesToBidAnAlreadyCompleteRound() {
    val completed = playRound(strongHand(), dashHand(), strongHand(), dashHand())
    try {
      brain.decide(completed, strongHand(), playerId = "p1", tier = BotTier.EXPERT)
      fail("decide must refuse a completed round")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("completed"))
    }
  }

  // ==========================================================================
  //  DASH phase
  // ==========================================================================

  @Test
  fun anExpertDashesABankruptHand() {
    val state = initNormalRound(round = 1, dealer = "p1")

    val intent = brain.decide(state, dashHand(), "p1", BotTier.EXPERT)

    assertTrue(intent is BiddingIntent.DashCallDecision)
    assertTrue((intent as BiddingIntent.DashCallDecision).declaredDashCall)
  }

  @Test
  fun anEasyTierIsNeverAllowedToDash() {
    val state = initNormalRound(round = 1, dealer = "p1")

    val intent = brain.decide(state, dashHand(), "p1", BotTier.EASY)

    assertTrue(intent is BiddingIntent.DashCallDecision)
    // EASY.canDash is false — the brain must not declare even on a perfect
    // dash hand, because the tier cannot evaluate the risk.
    assertFalse((intent as BiddingIntent.DashCallDecision).declaredDashCall)
  }

  @Test
  fun aStrongHandNeverDashes() {
    val state = initNormalRound(round = 1, dealer = "p1")

    val intent = brain.decide(state, strongHand(), "p1", BotTier.EXPERT)

    assertTrue(intent is BiddingIntent.DashCallDecision)
    assertFalse((intent as BiddingIntent.DashCallDecision).declaredDashCall)
  }

  @Test
  fun aThirdDashCallIsStillLegalBecauseTheEngineConvertsIt() {
    // The engine caps Dash Calls at two and auto-converts the rest to PASS
    // inside emit, so canSubmit never rejects an over-the-limit declaration.
    // The brain respects the cap for its own model, but must not break if it
    // sees a state where two seats already dashed.
    var state = initNormalRound(round = 1, dealer = "p1")
    state = emitAndApply(state, BiddingIntent.DashCallDecision("p1", true))
    state = emitAndApply(state, BiddingIntent.DashCallDecision("p2", true))
    assertEquals(2, dashCallerIds(state).size)

    val intent = brain.decide(state, dashHand(), "p3", BotTier.EXPERT)

    // canSubmit is the authority and it accepts; the brain's own cap is a
    // modelling choice, not a legality requirement.
    assertTrue(canSubmit(state, intent).legal)
  }

  // ==========================================================================
  //  AUCTION phase
  // ==========================================================================

  @Test
  fun aStrongHandCallsRatherThanPasses() {
    var state = initNormalRound(round = 1, dealer = "p1")
    // Skip past the dash decisions.
    state = emitAndApply(state, BiddingIntent.DashCallDecision("p1", false))
    state = emitAndApply(state, BiddingIntent.DashCallDecision("p2", false))
    state = emitAndApply(state, BiddingIntent.DashCallDecision("p3", false))
    state = emitAndApply(state, BiddingIntent.DashCallDecision("p4", false))
    assertEquals(BiddingPhase.AUCTION, state.subPhase)

    val intent = brain.decide(state, strongHand(), "p1", BotTier.EXPERT)

    assertTrue("a strong hand must call, not pass", intent is BiddingIntent.AuctionBid)
    val bid = intent as BiddingIntent.AuctionBid
    assertFalse(bid.isPass)
    assertEquals(SPADES, bid.suit)
    assertTrue("call must reach the 4-trick auction floor", bid.tricks!! >= 4)
  }

  @Test
  fun aBankruptHandPassesTheAuction() {
    var state = initNormalRound(round = 1, dealer = "p1")
    state = skipDash(state)

    // A hand worth ~0-1 tricks cannot legally call (floor is 4), so the brain
    // must pass rather than emit a bid canSubmit would reject.
    val intent = brain.decide(state, dashHand(), "p1", BotTier.EXPERT) as BiddingIntent.AuctionBid

    assertTrue("a bankrupt hand must pass", intent.isPass)
  }

  @Test
  fun anAuctionCallAlwaysBeatsTheTopOrMatchesItAsWith() {
    // This is the property that makes the retry loop unnecessary: whatever the
    // brain emits is either a raise, a With-match, or a pass — never a bid
    // that falls below the top on the engine's own ordering.
    var state = initNormalRound(round = 1, dealer = "p1")
    state = skipDash(state)
    state = emitAndApply(state, BiddingIntent.AuctionBid("p1", isPass = false, tricks = 7, suit = SPADES))
    state = emitAndApply(state, BiddingIntent.AuctionBid("p2", isPass = true))
    state = emitAndApply(state, BiddingIntent.AuctionBid("p3", isPass = true))

    val intent = brain.decide(state, strongHand(), "p4", BotTier.EXPERT)
    val bid = intent as BiddingIntent.AuctionBid

    if (!bid.isPass) {
      val beats = auctionBidBeatsTop(bid.tricks!!, bid.suit!!, state.auctionTop, state.auctionSuit)
      val with = auctionBidIsWith("p4", bid.tricks!!, bid.suit!!,
        state.auctionTop, state.auctionSuit, state.auctionBidder)
      assertTrue("a call must beat the top or be a With match", beats || with)
    }
  }

  // ==========================================================================
  //  CONFIRM phase
  // ==========================================================================

  @Test
  fun theCallerConfirmsOrUpgradesButNeverLowers() {
    // Drive an auction to a win, then let the winning brain confirm.
    var state = initNormalRound(round = 1, dealer = "p1")
    state = skipDash(state)
    state = emitAndApply(state, BiddingIntent.AuctionBid("p1", isPass = false, tricks = 6, suit = SPADES))
    state = emitAndApply(state, BiddingIntent.AuctionBid("p2", isPass = true))
    state = emitAndApply(state, BiddingIntent.AuctionBid("p3", isPass = true))
    state = emitAndApply(state, BiddingIntent.AuctionBid("p4", isPass = true))
    assertEquals(BiddingPhase.CONFIRM, state.subPhase)
    assertEquals("p1", state.callerId)

    val intent = brain.decide(state, strongHand(), "p1", BotTier.EXPERT)
    val confirm = intent as BiddingIntent.ConfirmCall

    // canSubmit forbids lowering; a confirm at exactly the won number is the
    // safe fallback and always legal.
    assertTrue("confirm must not lower the won contract", confirm.tricks >= state.auctionTop)
  }

  // ==========================================================================
  //  ESTIMATES phase
  // ==========================================================================

  @Test
  fun anEstimateNeverExceedsTheCallersCap() {
    val outcome = playRound(strongHand(), dashHand(), strongHand(), dashHand())
    val cap = outcome.auctionTop

    // Every committed non-dash estimate sits at or under the Caller's cap,
    // including the Caller itself.
    outcome.bids.forEach { (seat, bid) ->
      if (bid.type == BidType.TRICKS) {
        assertTrue("$seat estimated ${bid.amount} above the cap of $cap", bid.amount <= cap)
      }
    }
  }

  @Test
  fun aFullRoundNeverTotalsExactlyThirteen() {
    // The forbidden-13 rule is the one the TS source patched with its retry
    // loop. Completing a round here and checking the total exercises the
    // brain's fold-before-emit replacement for that loop.
    repeat(8) { seed ->
      val outcome = playRound(deal(seed), deal(seed + 100), deal(seed + 200), deal(seed + 300))
      val total = outcome.bids.values.sumOf { if (it.type == BidType.TRICKS) it.amount else 0 }
      assertTrue("round $seed totalled exactly 13", total != 13)
    }
  }

  @Test
  fun aFastRoundCompletesWithFourBots() {
    var state = initFastRound(round = 14, dealer = "p1")
    assertEquals(BiddingPhase.ESTIMATES, state.subPhase)
    assertEquals(SANS, state.declaredTrump)

    // Fast rounds start straight at estimates under a fixed trump; the brain
    // must handle a state with no auction at all.
    var guard = 0
    while (state.subPhase != BiddingPhase.DONE && guard < 40) {
      val seat = state.waitingFor ?: break
      val intent = brain.decide(state, deal(guard), seat, BotTier.EXPERT)
      state = emitAndApply(state, intent)
      guard++
    }

    assertEquals(BiddingPhase.DONE, state.subPhase)
    assertEquals("fast round must keep its fixed trump", SANS, state.declaredTrump)
  }

  // ==========================================================================
  //  Determinism
  // ==========================================================================

  @Test
  fun theSameHandAndSeatAlwaysBidIdentically() {
    val state = skipDash(initNormalRound(round = 1, dealer = "p1"))

    val a = brain.decide(state, strongHand(), "p1", BotTier.EXPERT)
    val b = brain.decide(state, strongHand(), "p1", BotTier.EXPERT)

    assertEquals(a, b)
  }

  @Test
  fun differentSeatsProduceDifferentBidsOnTheSameHand() {
    // seatJitter is a hash of the player id, so two seats holding identical
    // hands bid differently — the property that makes four bots feel like
    // four players rather than one decision copied four times.
    val state = skipDash(initNormalRound(round = 1, dealer = "p1"))
    val sameHand = deal(7)

    val a = brain.decide(state, sameHand, "p1", BotTier.EXPERT)
    val b = brain.decide(state, sameHand, "p2", BotTier.EXPERT)

    assertFalse("identical hands at different seats should usually differ", a == b)
  }

  // ==========================================================================
  //  Harness
  // ==========================================================================


  /**
   * Play a complete normal round with four EXPERT bots, each seeing the hand
   * supplied for their seat. Throws if the brain ever produces an illegal
   * intent, since that is the contract under test.
   */
  private fun playRound(
    p1: List<Card>,
    p2: List<Card>,
    p3: List<Card>,
    p4: List<Card>,
  ): BiddingState {
    val hands = mapOf("p1" to p1, "p2" to p2, "p3" to p3, "p4" to p4)
    var state = initNormalRound(round = 1, dealer = "p1")
    var guard = 0
    while (state.subPhase != BiddingPhase.DONE && guard < 80) {
      val seat = state.waitingFor ?: break
      val intent = brain.decide(state, hands.getValue(seat), seat, BotTier.EXPERT)
      state = emitAndApply(state, intent)
      guard++
    }
    return state
  }

  /** Apply any emit result that keeps the round moving; fail on rejection. */
  private fun emitAndApply(state: BiddingState, intent: BiddingIntent): BiddingState =
    when (val result = emit(state, intent)) {
      is EmitResult.Applied -> result.state
      is EmitResult.Completed -> result.state
      is EmitResult.GeneralPass -> error("unexpected general pass in the test harness")
      is EmitResult.Rejected -> error("brain emitted an intent the reducer rejected: ${result.reason}")
    }

  private fun skipDash(state: BiddingState): BiddingState {
    var s = state
    if (s.subPhase == BiddingPhase.DASH) {
      s = emitAndApply(s, BiddingIntent.DashCallDecision("p1", false))
      s = emitAndApply(s, BiddingIntent.DashCallDecision("p2", false))
      s = emitAndApply(s, BiddingIntent.DashCallDecision("p3", false))
      s = emitAndApply(s, BiddingIntent.DashCallDecision("p4", false))
    }
    return s
  }

  /**
   * A deterministic pseudo-hand for rounds where the exact cards matter less
   * than completing the state machine. Deliberately mediocre so the bots bid
   * mid-range numbers and exercise the cap and forbidden-13 paths.
   */
  private fun deal(seed: Int): List<Card> {
    val all = Suit.entries.filter { it != SANS }.flatMap { s ->
      RANKS.map { Card(s, it) }
    }
    return all.sortedBy { (it.suit.name.hashCode() * 31 + it.value * 7 + seed) }.take(13)
  }
}
