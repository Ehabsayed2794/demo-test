package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2.3 — the repeated fast Super Call (T-09).
 *
 * Every higher 8+ call wipes EVERY prior declaration except the new Super
 * Caller's own, re-opens the confirmation for that caller, lets the caller
 * take any trump, and makes every other seat declare again counter-clockwise
 * from the caller. A seat that already declared again after an earlier call
 * declares again after the next one.
 */
class FastSuperCallRedeclarationTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun fast(dealer: String): BiddingState = initFastRound(15, dealer, seats)

  private fun estimateResult(s: BiddingState, seat: String, tricks: Int): EmitResult {
    assertEquals("$seat must be the seat to declare", seat, s.waitingFor)
    val intent = BiddingIntent.FinalEstimate(seat, tricks)
    assertTrue(
      "declare $seat=$tricks must be legal: ${canSubmit(s, intent).reason}",
      canSubmit(s, intent).legal,
    )
    return emit(s, intent)
  }

  private fun estimateState(s: BiddingState, seat: String, tricks: Int): BiddingState =
    when (val r = estimateResult(s, seat, tricks)) {
      is EmitResult.Applied -> r.state
      is EmitResult.Completed -> r.state
      else -> throw AssertionError("unexpected result")
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
  fun t09SequenceLetsEveryOtherSeatRedeclareAfterEachHigherCall() {
    var s = fast("p1")
    // Step 1 — p1 declares 8: the first Super Call.
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p1", s.callerId)
    assertEquals("p1", s.waitingFor)
    assertEquals(8, s.auctionTop)
    assertTrue("a bare 8+ is never With", s.withPlayers.isEmpty())

    // p1 confirms 8 HEARTS. p2, p3, p4 declare again — p1 does not.
    s = confirmState(s, "p1", 8, Suit.HEARTS)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p2", s.waitingFor)
    assertEquals(setOf("p1"), s.bids.keys)
    assertEquals(Suit.HEARTS, s.declaredTrump)

    // Cycle 1: p2, p3, p4 declare again. p2 raises to 9.
    s = estimateState(s, "p2", 9)
    s = estimateState(s, "p3", 7)
    s = estimateState(s, "p4", 6)
    // Step 2 — the higher call re-opens the confirmation; the round is NOT
    // complete.
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p2", s.callerId)
    assertEquals("p2", s.waitingFor)
    assertEquals(9, s.auctionTop)
    assertTrue("the new call is not confirmed yet", !s.fastSuperResolved)

    // p2 confirms 9 SPADES. p3, p4, p1 declare again — p2 does not.
    s = confirmState(s, "p2", 9, Suit.SPADES)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p3", s.waitingFor)
    assertEquals(setOf("p2"), s.bids.keys)
    assertEquals(Suit.SPADES, s.declaredTrump)

    // Cycle 2: p3, p4, p1 declare again — p1 declared 8 before the first
    // call, so it has to declare again. p3 raises to 10.
    s = estimateState(s, "p3", 10)
    s = estimateState(s, "p4", 7)
    s = estimateState(s, "p1", 6)
    // Step 3 — a third call opens.
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p3", s.callerId)
    assertEquals("p3", s.waitingFor)
    assertEquals(10, s.auctionTop)

    // p3 confirms 10 CLUBS. p4, p1, p2 declare again — p3 does not.
    s = confirmState(s, "p3", 10, Suit.CLUBS)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p4", s.waitingFor)
    assertEquals(setOf("p3"), s.bids.keys)
    assertEquals(Suit.CLUBS, s.declaredTrump)

    // Cycle 3: p4, p1, p2 declare again — nobody goes higher.
    s = estimateState(s, "p4", 5)
    s = estimateState(s, "p1", 4)
    val intent = BiddingIntent.FinalEstimate("p2", 3)
    assertTrue(canSubmit(s, intent).legal)
    val completed = emit(s, intent) as? EmitResult.Completed
      ?: throw AssertionError("the auction must end with the last caller")
    s = completed.state
    assertEquals(BiddingPhase.DONE, s.subPhase)
    assertEquals("p3", s.callerId)
    assertEquals("p3", completed.outcome.callerId)
    assertEquals(Suit.CLUBS, completed.outcome.trump)
    assertTrue(s.withPlayers.isEmpty())
    assertEquals(mapOf("p1" to 4, "p2" to 3, "p3" to 10, "p4" to 5), completed.outcome.estimates)
  }

  @Test
  fun aSeatThatAlreadyRedeclaredMustDeclareAgainAfterAHigherCall() {
    var s = fast("p1")
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    s = confirmState(s, "p1", 8, Suit.HEARTS)
    assertEquals(setOf("p1"), s.bids.keys)

    // p2 declares 9 — higher than the confirmed 8.
    s = estimateState(s, "p2", 9)
    s = estimateState(s, "p3", 6)
    s = estimateState(s, "p4", 5)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p2", s.callerId)

    // p2's confirmation wipes p1's 8 as well: p1 has to declare a third time.
    s = confirmState(s, "p2", 9, Suit.SPADES)
    assertEquals(setOf("p2"), s.bids.keys)
    assertNull(s.bids["p1"])
    assertEquals("p3", s.waitingFor)

    s = estimateState(s, "p3", 6)
    s = estimateState(s, "p4", 5)
    val intent = BiddingIntent.FinalEstimate("p1", 4)
    assertTrue(canSubmit(s, intent).legal)
    val completed = emit(s, intent) as? EmitResult.Completed
      ?: throw AssertionError("the auction must end")
    assertEquals("p2", completed.outcome.callerId)
    assertEquals(Suit.SPADES, completed.outcome.trump)
    assertEquals(mapOf("p1" to 4, "p2" to 9, "p3" to 6, "p4" to 5), completed.outcome.estimates)
  }

  @Test
  fun aHigherCallOpensConfirmAndDoesNotCompleteTheRound() {
    var s = fast("p1")
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    s = confirmState(s, "p1", 8, Suit.HEARTS)

    s = estimateState(s, "p2", 9)
    s = estimateState(s, "p3", 5)
    val result = estimateResult(s, "p4", 4)
    val reopened = result as? EmitResult.Applied
      ?: throw AssertionError("a higher call must not complete the round, got $result")
    s = reopened.state
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p2", s.callerId)
    assertEquals("p2", s.waitingFor)
    assertEquals(9, s.auctionTop)
    assertTrue("the fast confirmation has no suit constraint", s.noSuitConstraint)
    assertTrue("the new call is not confirmed yet", !s.fastSuperResolved)
  }

  @Test
  fun noHigherCallEndsTheAuctionWithTheLastCaller() {
    var s = fast("p1")
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    s = confirmState(s, "p1", 8, Suit.HEARTS)

    // Nobody goes higher: the round ends with the last caller.
    s = estimateState(s, "p2", 7)
    s = estimateState(s, "p3", 6)
    val intent = BiddingIntent.FinalEstimate("p4", 5)
    assertTrue(canSubmit(s, intent).legal)
    val completed = emit(s, intent) as? EmitResult.Completed
      ?: throw AssertionError("the auction must end")
    assertEquals(BiddingPhase.DONE, completed.state.subPhase)
    assertEquals("p1", completed.outcome.callerId)
    assertEquals(Suit.HEARTS, completed.outcome.trump)
    assertTrue(completed.outcome.withPlayers.isEmpty())
    assertEquals(mapOf("p1" to 8, "p2" to 7, "p3" to 6, "p4" to 5), completed.outcome.estimates)
  }

  @Test
  fun exactNumberAndSuitDuplicateIsLegalAndLaterDuplicatesAreWith() {
    var s = fast("p1")
    // p1 and p2 both declare 8 under the round's fixed SPADES: exact
    // duplicates. The first declarer is the Caller, the later one is With.
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 8)
    s = estimateState(s, "p3", 4)
    s = estimateState(s, "p4", 5)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p1", s.callerId)
    assertEquals(listOf("p2"), s.withPlayers)

    // p1 confirms 8 HEARTS: p2's 8♠ is wiped along with every other seat.
    s = confirmState(s, "p1", 8, Suit.HEARTS)
    assertEquals(setOf("p1"), s.bids.keys)
    assertTrue("the provisional duplicate is wiped with its seat", s.withPlayers.isEmpty())

    // p2 declares 8 again under the final trump: an exact duplicate of the
    // Caller's 8♥, so it is With again.
    s = estimateState(s, "p2", 8)
    s = estimateState(s, "p3", 4)
    val intent = BiddingIntent.FinalEstimate("p4", 5)
    assertTrue(canSubmit(s, intent).legal)
    val completed = emit(s, intent) as? EmitResult.Completed
      ?: throw AssertionError("the round must complete")
    assertEquals(listOf("p2"), completed.outcome.withPlayers)
    assertEquals(listOf("p2"), completed.state.withPlayers)
    assertEquals(Suit.HEARTS, completed.outcome.trump)
  }

  @Test
  fun aHigherCallMayConfirmWithAWeakerSuitBecauseTheNumberRose() {
    var s = fast("p1")
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    // The fast confirmation carries no suit constraint (A6.10): the Super
    // Caller may take any trump, even a weaker one.
    s = confirmState(s, "p1", 8, Suit.CLUBS)
    assertEquals(Suit.CLUBS, s.declaredTrump)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)

    // p2 raises to 9 and may take an even weaker trump.
    s = estimateState(s, "p2", 9)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 4)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p2", s.callerId)
    s = confirmState(s, "p2", 9, Suit.DIAMONDS)
    assertEquals(Suit.DIAMONDS, s.declaredTrump)
    assertEquals(9, s.auctionTop)
  }

  @Test
  fun equalNumberInAWeakerSuitOpensNoCallAndGrantsNoWith() {
    // Hand-built state: the confirmed call is 8 HEARTS and p2 still holds an
    // equal 8 declared under the weaker SPADES. The engine never keeps such a
    // record on its own — every declaration is made under the current trump —
    // so the case is built by hand.
    val s = initFastRound(15, "p1", seats).copy(
      subPhase = BiddingPhase.ESTIMATES,
      auctionTop = 8,
      auctionSuit = Suit.HEARTS,
      declaredTrump = Suit.HEARTS,
      callerId = "p1",
      fastSuperResolved = true,
      noSuitConstraint = false,
      waitingFor = "p4",
      bids = mapOf(
        "p1" to Bid(BidType.TRICKS, 8, Suit.HEARTS),
        "p2" to Bid(BidType.TRICKS, 8, Suit.SPADES),
        "p3" to Bid(BidType.TRICKS, 3, Suit.SPADES),
      ),
    )
    val intent = BiddingIntent.FinalEstimate("p4", 2)
    assertTrue(canSubmit(s, intent).legal)
    val completed = emit(s, intent) as? EmitResult.Completed
      ?: throw AssertionError("the round must complete")
    assertEquals(BiddingPhase.DONE, completed.state.subPhase)
    assertEquals("p1", completed.outcome.callerId)
    assertTrue("the weaker-suit 8 is not With", completed.outcome.withPlayers.isEmpty())
    assertEquals(Suit.HEARTS, completed.outcome.trump)
  }
}
