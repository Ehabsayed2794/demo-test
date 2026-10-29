package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estimation order in a normal round (owner ruling 2026-10-29): the Caller's
 * number is locked by the confirmation, so the counter-clockwise walk BREAKS —
 * the With players are asked first, then everyone else, both legs
 * counter-clockwise from the Caller.
 *
 * The rule is not cosmetic. Under the old plain-CCW walk the seats between the
 * Caller and the With player were asked before the With, which could leave the
 * With seat as the LAST estimator — where its floor (its own With bid), the
 * Caller's cap, and the forbidden-13 rule can between them admit no legal
 * number at all. Seed 194 round 5 of the E2 corpus hit exactly that dead end.
 */
class EstimationOrderTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun applied(result: EmitResult): BiddingState = when (result) {
    is EmitResult.Applied -> result.state
    is EmitResult.Rejected -> throw AssertionError("expected Applied, got Rejected(${result.reason})")
    is EmitResult.Completed -> throw AssertionError("expected Applied, got Completed early")
    is EmitResult.GeneralPass -> throw AssertionError("expected Applied, got GeneralPass")
  }

  private fun dash(s: BiddingState, seat: String, declared: Boolean): BiddingState {
    val intent = BiddingIntent.DashCallDecision(seat, declared)
    assertTrue("dash $seat legal: ${canSubmit(s, intent).reason}", canSubmit(s, intent).legal)
    return applied(emit(s, intent))
  }

  private fun auction(
    s: BiddingState,
    seat: String,
    tricks: Int? = null,
    suit: Suit? = null,
    isPass: Boolean = false,
  ): BiddingState {
    val intent = BiddingIntent.AuctionBid(seat, isPass, tricks, suit)
    assertTrue("auction $seat legal: ${canSubmit(s, intent).reason}", canSubmit(s, intent).legal)
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

  // ── The queue itself ──

  @Test
  fun withSeatsAreAskedFirstThenTheRestBothCounterClockwiseFromTheCaller() {
    // Caller p2, so the plain CCW walk is p3, p4, p1 — p3 is NOT With and must
    // fall behind both With seats. The stored With order (p1, p4) is
    // deliberately not the CCW order, so insertion order cannot pass this.
    val state = initNormalRound(1, "p1", seats).copy(
      callerId = "p2",
      withPlayers = listOf("p1", "p4"),
      bids = mapOf("p2" to Bid(BidType.TRICKS, 5, Suit.HEARTS)),
    )
    assertEquals(listOf("p4", "p1", "p3"), estimationQueue(state))
  }

  @Test
  fun theCallerAndSeatsAlreadyHoldingABidAreNotInTheQueue() {
    // p4 is a pre-bid Dash Caller: its DASHCALL record keeps it out.
    val state = initNormalRound(1, "p1", seats).copy(
      callerId = "p1",
      withPlayers = listOf("p3"),
      bids = mapOf(
        "p1" to Bid(BidType.TRICKS, 6, Suit.SPADES),
        "p4" to Bid(BidType.DASHCALL, 0, null),
      ),
    )
    assertEquals(listOf("p3", "p2"), estimationQueue(state))
  }

  @Test
  fun aFastRoundWithNoCallerYetKeepsTheDealerRotation() {
    // Fast rounds have no With while the estimates are being taken, so the
    // queue degenerates to the legacy next-open-seat walk from the dealer.
    assertEquals(seats, estimationQueue(initFastRound(15, "p1", seats)))
  }

  // ── Seed 194, round 5: the dead end the rule removes ──

  /**
   * The corpus shape: p1 takes the call at 6♠, p3 matches it exactly and is
   * With, p4 declared a pre-bid Dash Call and never estimates. Under the old
   * walk p2 estimated first, which left p3 — the With seat — last, with cap 6,
   * floor 6 and forbidden-13 leaving no legal number at all. With is asked
   * first now, so a non-With seat closes the round.
   */
  @Test
  fun aWithSeatIsNeverTheLastEstimatorInTheSeed194Shape() {
    var s = initNormalRound(5, "p1", seats)
    s = dash(s, "p1", false)
    s = dash(s, "p2", false)
    s = dash(s, "p3", false)
    s = dash(s, "p4", true)
    s = auction(s, "p1", 6, Suit.SPADES)
    s = auction(s, "p2", isPass = true)
    s = auction(s, "p3", 6, Suit.SPADES)
    s = auction(s, "p1", isPass = true)

    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p1", s.callerId)
    assertEquals(listOf("p3"), s.withPlayers)

    val confirmed = confirm(s, "p1", 6, Suit.SPADES)
    assertEquals(BiddingPhase.ESTIMATES, confirmed.subPhase)
    assertEquals("the With seat is asked first", "p3", confirmed.waitingFor)

    // p3's floor is its own 6♠ in the winning suit; the cap is the same 6.
    assertEquals(6, withFloorFor(confirmed.actionHistory, "p3", Suit.SPADES))
    assertEquals(6, s.auctionTop)
    val afterWith = applied(estimate(confirmed, "p3", 6))
    assertEquals("p2 closes the round", "p2", afterWith.waitingFor)

    // Forbidden-13 still binds the last seat: 13 - (6 + 6 + 0) = 1.
    val forbidden = BiddingIntent.FinalEstimate("p2", 1)
    assertTrue(!canSubmit(afterWith, forbidden).legal)
    assertEquals("Can't pick 1 — totals 13", canSubmit(afterWith, forbidden).reason)
    assertTrue(canSubmit(afterWith, BiddingIntent.FinalEstimate("p2", 2)).legal)

    val completed = estimate(afterWith, "p2", 2)
    assertTrue("the round completes", completed is EmitResult.Completed)
    val outcome = (completed as EmitResult.Completed).outcome
    assertEquals(mapOf("p1" to 6, "p2" to 2, "p3" to 6), outcome.estimates)
    assertEquals(listOf("p3"), outcome.withPlayers)
    assertEquals(listOf("p4"), outcome.dashCallers)
    assertEquals("p2", outcome.riskPlayerId)
  }
}
