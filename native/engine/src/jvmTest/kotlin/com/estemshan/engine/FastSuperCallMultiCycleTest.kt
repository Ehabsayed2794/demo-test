package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2.3 (T-09) — the REPEATED fast Super Call, end to end in the engine.
 *
 * A fast Super Call never ends the round: the confirmation replaces the
 * forced trump, wipes EVERY other seat's declaration and re-opens the
 * re-declaration counter-clockwise from the seat after the caller. A
 * declaration above the confirmed number opens the NEXT cycle, and the
 * final With set comes from the FINAL cycle's number and trump.
 */
class FastSuperCallMultiCycleTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun fast(dealer: String): BiddingState = initFastRound(15, dealer, seats)

  private fun estimateState(s: BiddingState, seat: String, tricks: Int): BiddingState {
    assertEquals("$seat must be the seat to declare", seat, s.waitingFor)
    val intent = BiddingIntent.FinalEstimate(seat, tricks)
    assertTrue(
      "declare $seat=$tricks must be legal: ${canSubmit(s, intent).reason}",
      canSubmit(s, intent).legal,
    )
    return when (val r = emit(s, intent)) {
      is EmitResult.Applied -> r.state
      is EmitResult.Completed -> r.state
      else -> throw AssertionError("unexpected result")
    }
  }

  private fun confirmState(s: BiddingState, seat: String, tricks: Int, suit: Suit): BiddingState {
    assertEquals("$seat must be the seat to confirm", seat, s.waitingFor)
    val intent = BiddingIntent.ConfirmCall(seat, tricks, suit)
    assertTrue(
      "confirm $seat=$tricks $suit must be legal: ${canSubmit(s, intent).reason}",
      canSubmit(s, intent).legal,
    )
    return when (val r = emit(s, intent)) {
      is EmitResult.Applied -> r.state
      else -> throw AssertionError("the confirmation must not complete the round, got $r")
    }
  }

  @Test
  fun t09t10aSecondSuperCallWipesTheFirstCycleAndTheWithSetComesFromTheFinalCycle() {
    var s = fast("p1")
    // Cycle 1 — p1 declares 8 and every other seat declares below it.
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p1", s.callerId)
    assertEquals(8, s.auctionTop)

    // p1 confirms 8 SANS: the round does NOT end. Every other seat is
    // wiped and declares again counter-clockwise from p2.
    s = confirmState(s, "p1", 8, Suit.SANS)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p2", s.waitingFor)
    assertEquals(setOf("p1"), s.bids.keys)
    assertEquals(Suit.SANS, s.declaredTrump)
    assertTrue("a bare 8+ is never With", s.withPlayers.isEmpty())

    // Cycle 1's re-declaration: p4 goes higher and opens the NEXT cycle.
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 9)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p4", s.callerId)
    assertEquals(9, s.auctionTop)

    // p4 confirms 9 SPADES: the FIRST cycle's declarations are wiped with
    // it — p1's 8 under the replaced SANS is gone.
    s = confirmState(s, "p4", 9, Suit.SPADES)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p1", s.waitingFor)
    assertEquals(setOf("p4"), s.bids.keys)
    assertNull("p1's 8 under the replaced SANS is wiped", s.bids["p1"])
    assertEquals(Suit.SPADES, s.declaredTrump)
    assertEquals(9, s.auctionTop)

    // The final cycle: every seat except p4 declares again, under SPADES.
    s = estimateState(s, "p1", 9)
    s = estimateState(s, "p2", 9)
    val intent = BiddingIntent.FinalEstimate("p3", 9)
    assertTrue(canSubmit(s, intent).legal)
    val completed = emit(s, intent) as? EmitResult.Completed
      ?: throw AssertionError("the auction must end with the last re-declaration")
    assertEquals(BiddingPhase.DONE, completed.state.subPhase)
    assertEquals("p4", completed.outcome.callerId)
    assertEquals(Suit.SPADES, completed.outcome.trump)
    // With comes from the FINAL number and the FINAL trump, and the final
    // Caller is never With.
    assertEquals(listOf("p1", "p2", "p3"), completed.outcome.withPlayers)
    assertEquals(mapOf("p1" to 9, "p2" to 9, "p3" to 9, "p4" to 9), completed.outcome.estimates)
  }

  @Test
  fun t10aSeatThatKeepsThePreviousCyclesNumberIsNotWith() {
    var s = fast("p1")
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    s = confirmState(s, "p1", 8, Suit.SANS)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 9)
    s = confirmState(s, "p4", 9, Suit.SPADES)

    // p3 re-declares the OLD number 8 under the new trump: the final call
    // is 9, so p3 is not With even though it re-declared under the final
    // trump.
    s = estimateState(s, "p1", 9)
    s = estimateState(s, "p2", 9)
    val intent = BiddingIntent.FinalEstimate("p3", 8)
    assertTrue(canSubmit(s, intent).legal)
    val completed = emit(s, intent) as? EmitResult.Completed
      ?: throw AssertionError("the auction must end with the last re-declaration")
    assertEquals("p4", completed.outcome.callerId)
    assertEquals(listOf("p1", "p2"), completed.outcome.withPlayers)
    assertEquals(mapOf("p1" to 9, "p2" to 9, "p3" to 8, "p4" to 9), completed.outcome.estimates)
  }

  @Test
  fun t09aSecondCycleMayNotBeLowerThanTheFirst() {
    // A cycle is opened only by a declaration ABOVE the confirmed number:
    // a re-declaration at or below it does not open a new confirmation.
    var s = fast("p1")
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    s = confirmState(s, "p1", 8, Suit.SANS)

    // p2 re-declares 8 — equal to the confirmed number, not higher.
    s = estimateState(s, "p2", 8)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p3", s.waitingFor)
    assertTrue("the caller is locked", s.fastSuperResolved)
    assertEquals(8, s.auctionTop)
  }
}
