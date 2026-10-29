package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2.2 — the With floor is suit-aware (T-12) and Estimation Jump-In carries
 * the suit half of rules §2.2.1a.
 *
 * The floor is the number a seat bid IN THE CALLER'S WINNING SUIT. A later,
 * higher bid in another suit does not raise it, and a seat that never bid
 * that suit has no floor at all.
 */
class WithFloorSuitAwareTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun normal(dealer: String): BiddingState = initNormalRound(1, dealer, seats)

  private fun applied(result: EmitResult): BiddingState = when (result) {
    is EmitResult.Applied -> result.state
    is EmitResult.Rejected -> throw AssertionError("expected Applied, got Rejected(${result.reason})")
    is EmitResult.Completed -> throw AssertionError("expected Applied, got Completed early")
    is EmitResult.GeneralPass -> throw AssertionError("expected Applied, got GeneralPass")
  }

  private fun declineAllDash(s: BiddingState): BiddingState {
    var cur = s
    repeat(4) {
      val seat = cur.waitingFor ?: throw AssertionError("waitingFor null mid-DASH")
      val intent = BiddingIntent.DashCallDecision(seat, false)
      assertTrue(canSubmit(cur, intent).legal)
      cur = applied(emit(cur, intent))
    }
    return cur
  }

  private fun auction(
    s: BiddingState,
    seat: String,
    tricks: Int? = null,
    suit: Suit? = null,
    isPass: Boolean = false,
  ): BiddingState {
    val intent = BiddingIntent.AuctionBid(seat, isPass, tricks, suit)
    assertTrue("auction $seat $tricks $suit legal: ${canSubmit(s, intent).reason}", canSubmit(s, intent).legal)
    return applied(emit(s, intent))
  }

  private fun confirm(s: BiddingState, seat: String, tricks: Int, suit: Suit): BiddingState {
    val intent = BiddingIntent.ConfirmCall(seat, tricks, suit)
    assertTrue("confirm $seat legal: ${canSubmit(s, intent).reason}", canSubmit(s, intent).legal)
    return applied(emit(s, intent))
  }

  private fun estimate(s: BiddingState, seat: String, tricks: Int): EmitResult {
    val intent = BiddingIntent.FinalEstimate(seat, tricks)
    assertTrue("estimate $seat=$tricks legal: ${canSubmit(s, intent).reason}", canSubmit(s, intent).legal)
    return emit(s, intent)
  }

  /**
   * T-12 "Suit at any time" + "Superseded call": p1 bids 5♥ and later 6♠.
   * With a ♥ winner the floor is p1's last ♥ bid (5); with a ♠ winner it is 6.
   */
  private val mixedHistory = listOf(
    BidAction("p1", "BID", 5, Suit.HEARTS),
    BidAction("p1", "BID", 6, Suit.SPADES),
    BidAction("p2", "BID", 4, Suit.HEARTS),
    BidAction("p1", "PASS"),
  )

  @Test
  fun suitAwareFloorReadsTheLastBidInTheWinningSuit() {
    assertEquals(5, withFloorFor(mixedHistory, "p1", Suit.HEARTS))
    assertEquals(6, withFloorFor(mixedHistory, "p1", Suit.SPADES))
    assertEquals(4, withFloorFor(mixedHistory, "p2", Suit.HEARTS))
  }

  @Test
  fun suitBlindFloorIsUnchanged() {
    // The two-arg form still reads the last BID in ANY suit — the three app
    // callers and BidBrain depend on it.
    assertEquals(6, withFloorFor(mixedHistory, "p1"))
  }

  @Test
  fun seatThatNeverBidTheWinningSuitHasNoFloor() {
    assertNull(withFloorFor(mixedHistory, "p1", Suit.CLUBS))
    assertNull(withFloorFor(mixedHistory, "p2", Suit.SPADES))
    assertNull(withFloorFor(mixedHistory, "p3", Suit.HEARTS))
  }

  @Test
  fun floorLegalityFollowsTheWinningSuitNotTheLastBid() {
    // Winning suit ♥ → the floor is 5, so 5 is legal and 4 is not.
    assertTrue(estimateBelowWithFloor(mixedHistory, "p1", 4, listOf("p1"), Suit.HEARTS))
    assertTrue(!estimateBelowWithFloor(mixedHistory, "p1", 5, listOf("p1"), Suit.HEARTS))
    // Winning suit ♠ → the floor is 6, so 5 is now below it.
    assertTrue(estimateBelowWithFloor(mixedHistory, "p1", 5, listOf("p1"), Suit.SPADES))
    assertTrue(!estimateBelowWithFloor(mixedHistory, "p1", 6, listOf("p1"), Suit.SPADES))
  }

  @Test
  fun fourArgFloorLegalityStillDelegatesToTheSuitBlindFloor() {
    // The legacy four-arg form keeps the suit-blind floor: 5 is below p1's
    // last bid of 6.
    assertTrue(estimateBelowWithFloor(mixedHistory, "p1", 5, listOf("p1")))
    assertTrue(!estimateBelowWithFloor(mixedHistory, "p1", 6, listOf("p1")))
  }

  @Test
  fun aSeatThatIsNotWithHasNoFloor() {
    assertTrue(!estimateBelowWithFloor(mixedHistory, "p2", 1, listOf("p1"), Suit.HEARTS))
  }

  /**
   * A normal auction in which p1 bids 5♥ and later 6♠, and p2 wins with 7♥.
   * p1 is With through Auction Alignment (it bid ♥), and its floor in the
   * winning suit ♥ is 5 — not the suit-blind 6.
   */
  private fun auctionWithMixedSuitBids(): BiddingState {
    var s = normal("p1")
    s = declineAllDash(s)
    s = auction(s, "p1", 5, Suit.HEARTS)
    s = auction(s, "p2", 5, Suit.HEARTS)
    s = auction(s, "p3", isPass = true)
    s = auction(s, "p4", isPass = true)
    // p1 raises in another suit: its last bid overall becomes 6♠, but its last
    // bid in the winning suit ♥ stays 5.
    s = auction(s, "p1", 6, Suit.SPADES)
    // p2 takes the call with 7♥; p1 concedes, so p2 is the Caller.
    s = auction(s, "p2", 7, Suit.HEARTS)
    s = auction(s, "p1", isPass = true)
    return s
  }

  @Test
  fun aRealAuctionGivesTheWithSeatItsSuitAwareFloor() {
    val s = auctionWithMixedSuitBids()
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p2", s.callerId)
    assertEquals(7, s.auctionTop)
    assertEquals(Suit.HEARTS, s.auctionSuit)
    // Auction Alignment: p1 bid the winning suit at some point → With.
    assertEquals(listOf("p1"), s.withPlayers)

    // T-12: p1's floor in the winning suit ♥ is its last ♥ bid (5). Its last
    // bid overall is the 6♠, which the suit-blind floor would use instead.
    assertEquals(5, withFloorFor(s.actionHistory, "p1", Suit.HEARTS))
    assertEquals(6, withFloorFor(s.actionHistory, "p1"))
    assertEquals(6, withFloorFor(s.actionHistory, "p1", Suit.SPADES))
  }

  @Test
  fun aWithEstimateBelowTheWinningSuitBidIsRejected() {
    val s = auctionWithMixedSuitBids()
    val confirmed = confirm(s, "p2", 7, Suit.HEARTS)
    assertEquals(BiddingPhase.ESTIMATES, confirmed.subPhase)

    // The With seat is asked FIRST (owner ruling 2026-10-29 — the Caller's
    // number is locked by the confirmation); p3 and p4 follow with no floor.
    assertEquals("p1", confirmed.waitingFor)

    // p1's last bid in the winning suit ♥ is 5; its last bid overall is the
    // 6♠. The suit-blind floor (6) would have rejected 5 as well.
    assertEquals(5, withFloorFor(confirmed.actionHistory, "p1", Suit.HEARTS))
    assertEquals(6, withFloorFor(confirmed.actionHistory, "p1"))

    val below = BiddingIntent.FinalEstimate("p1", 4)
    val verdict = canSubmit(confirmed, below)
    assertTrue(!verdict.legal)
    assertEquals("Min is 5 (your own With bid)", verdict.reason)
    assertTrue(emit(confirmed, below) is EmitResult.Rejected)

    // Exactly at the floor is accepted, and emit agrees with canSubmit.
    assertTrue(canSubmit(confirmed, BiddingIntent.FinalEstimate("p1", 5)).legal)
    val afterP1 = applied(estimate(confirmed, "p1", 5))
    assertEquals("p3", afterP1.waitingFor)
    val afterP3 = applied(estimate(afterP1, "p3", 0))
    assertEquals("p4", afterP3.waitingFor)

    val completed = emit(afterP3, BiddingIntent.FinalEstimate("p4", 0))
    assertTrue("the last estimate completes the round", completed is EmitResult.Completed)
    assertEquals(listOf("p1"), (completed as EmitResult.Completed).outcome.withPlayers)
  }

  @Test
  fun aLaterHigherBidInAnotherSuitDoesNotRaiseTheFloor() {
    val s = auctionWithMixedSuitBids()
    val confirmed = confirm(s, "p2", 7, Suit.HEARTS)
    assertEquals("p1", confirmed.waitingFor)

    // The floor is p1's last ♥ bid (5) even though its last bid is the 6♠, so
    // the suit-blind floor would reject 5 — the suit-aware floor does not.
    assertEquals(5, withFloorFor(confirmed.actionHistory, "p1", Suit.HEARTS))
    assertEquals(6, withFloorFor(confirmed.actionHistory, "p1"))
    assertTrue(canSubmit(confirmed, BiddingIntent.FinalEstimate("p1", 5)).legal)
    assertTrue(!canSubmit(confirmed, BiddingIntent.FinalEstimate("p1", 4)).legal)
  }

  /**
   * A hand-built ESTIMATES state: p1 is With but never bid the winning suit,
   * so it has no floor and the floor rule must not reject it.
   */
  @Test
  fun aSeatThatNeverBidTheWinningSuitIsNotRejectedByTheFloorRule() {
    val s = initNormalRound(1, "p1", seats).copy(
      subPhase = BiddingPhase.ESTIMATES,
      auctionTop = 7,
      auctionSuit = Suit.HEARTS,
      declaredTrump = Suit.HEARTS,
      callerId = "p2",
      withPlayers = listOf("p3"),
      bids = emptyMap(),
      actionHistory = listOf(BidAction("p2", "BID", 7, Suit.HEARTS)),
      waitingFor = "p3",
    )
    assertNull(withFloorFor(s.actionHistory, "p3", Suit.HEARTS))
    assertTrue(canSubmit(s, BiddingIntent.FinalEstimate("p3", 0)).legal)
    val applied0 = applied(emit(s, BiddingIntent.FinalEstimate("p3", 0)))
    assertEquals(BiddingPhase.ESTIMATES, applied0.subPhase)
  }

  /**
   * p1 bids 5♥, p2 takes the call with 6♠, p3 and p4 pass the auction and p1
   * concedes. p3 never bid, so it is asked for an estimate.
   */
  private fun auctionWithAPasser(): BiddingState {
    var s = normal("p1")
    s = declineAllDash(s)
    s = auction(s, "p1", 5, Suit.HEARTS)
    s = auction(s, "p2", 6, Suit.SPADES)
    s = auction(s, "p3", isPass = true)
    s = auction(s, "p4", isPass = true)
    s = auction(s, "p1", isPass = true)
    return s
  }

  @Test
  fun aPasserWhoMatchesTheNumberAndTheSuitJoinsWith() {
    var s = auctionWithAPasser()
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p2", s.callerId)
    assertEquals(Suit.SPADES, s.auctionSuit)
    // Nobody bid ♠ except the Caller.
    assertTrue(s.withPlayers.isEmpty())

    s = confirm(s, "p2", 6, Suit.SPADES)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p3", s.waitingFor)
    assertEquals(Suit.SPADES, s.declaredTrump)

    // p3 passed the auction and matches the Caller's number AND suit → With.
    s = applied(estimate(s, "p3", 6))
    assertTrue("a passer matching both is With", s.withPlayers.contains("p3"))

    // p4's number does not match the Caller's, so it is not With.
    s = applied(estimate(s, "p4", 2))
    val completed = estimate(s, "p1", 1) as EmitResult.Completed
    assertEquals(listOf("p3"), completed.outcome.withPlayers)
  }

  @Test
  fun aMatchingNumberInAnotherSuitIsNotWith() {
    // The engine records every estimate under `declaredTrump`, and the
    // confirmation sets `declaredTrump == auctionSuit`, so a reachable state
    // always carries the two equal. The mismatched shape is hand-built here:
    // the estimate lands under ♥ while the Caller won with ♠.
    val s = initNormalRound(1, "p1", seats).copy(
      subPhase = BiddingPhase.ESTIMATES,
      auctionTop = 6,
      auctionSuit = Suit.SPADES,
      declaredTrump = Suit.HEARTS,
      callerId = "p2",
      withPlayers = emptyList(),
      bids = emptyMap(),
      actionHistory = listOf(BidAction("p2", "BID", 6, Suit.SPADES)),
      waitingFor = "p3",
    )
    val after = applied(estimate(s, "p3", 6))
    assertTrue("a matching number in another suit is not With", after.withPlayers.isEmpty())
  }

  @Test
  fun jumpInKeepsTheNumberOnlyRuleWhenTheStateCarriesNoWinningSuit() {
    // A hand-built / legacy state with no auctionSuit keeps the number-only
    // jump-in: the engine cannot produce such a state at the estimation
    // stage, and failing closed there would reject a legal jump-in.
    val legacy = initNormalRound(1, "p1", seats).copy(
      subPhase = BiddingPhase.ESTIMATES,
      auctionTop = 6,
      auctionSuit = null,
      declaredTrump = Suit.SPADES,
      callerId = "p2",
      waitingFor = "p3",
    )
    val after = applied(estimate(legacy, "p3", 6))
    assertTrue("number-only jump-in still applies", after.withPlayers.contains("p3"))
  }
}
