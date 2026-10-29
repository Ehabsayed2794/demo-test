package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The absolute 13-trick maximum (MAX_TRICKS = 13) holds in EVERY round.
 *
 * The fast-round exemption removes ONLY the Caller-relative Call Cap — the
 * Caller's number is not a ceiling in a fast round, so a seat may declare
 * above it and trigger a new Super Call. It never removes the absolute
 * maximum of 13 tricks: that limit is a property of the 13-card suit, not
 * of the Call. These cases pin both halves of that split on every entry
 * path — the estimate, the Super Call declaration, the trump selection and
 * the raise of an existing Super Call.
 */
class FastSuperCallMaxTricksTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun fast(dealer: String): BiddingState = initFastRound(15, dealer, seats)

  private fun estimateState(s: BiddingState, seat: String, tricks: Int): BiddingState =
    when (val r = emit(s, BiddingIntent.FinalEstimate(seat, tricks))) {
      is EmitResult.Applied -> r.state
      is EmitResult.Completed -> r.state
      is EmitResult.Rejected -> throw AssertionError("estimate $seat=$tricks rejected: ${r.reason}")
      is EmitResult.GeneralPass -> throw AssertionError("unexpected GeneralPass")
    }

  private fun confirmState(s: BiddingState, seat: String, tricks: Int, suit: Suit): BiddingState =
    when (val r = emit(s, BiddingIntent.ConfirmCall(seat, tricks, suit))) {
      is EmitResult.Applied -> r.state
      is EmitResult.Rejected -> throw AssertionError("confirm $seat=$tricks $suit rejected: ${r.reason}")
      is EmitResult.Completed -> throw AssertionError("the confirmation must not complete the round")
      is EmitResult.GeneralPass -> throw AssertionError("unexpected GeneralPass")
    }

  /** A fast round that reached the CONFIRM phase through a first Super Call of 8. */
  private fun confirmedSuperCall(): BiddingState {
    var s = fast("p1")
    s = estimateState(s, "p1", 8)
    s = estimateState(s, "p2", 4)
    s = estimateState(s, "p3", 5)
    s = estimateState(s, "p4", 6)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p1", s.callerId)
    return s
  }

  /** The same fast round after p1 confirmed 8 — every other seat re-declares. */
  private fun confirmedSuperCallWiped(): BiddingState {
    val s = confirmState(confirmedSuperCall(), "p1", 8, Suit.HEARTS)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p2", s.waitingFor)
    assertEquals(setOf("p1"), s.bids.keys)
    return s
  }

  // ── The estimate path: 13 is legal, 14 is not ────────────────────────
  @Test
  fun t24aFastSeatMayDeclareThirteenButNotFourteen() {
    val s = confirmedSuperCallWiped()

    val ok = canSubmit(s, BiddingIntent.FinalEstimate("p2", 13))
    assertTrue("13 is the absolute maximum and stays legal: ${ok.reason}", ok.legal)

    val over = canSubmit(s, BiddingIntent.FinalEstimate("p2", 14))
    assertFalse("14 is one over the absolute maximum", over.legal)
    assertEquals("Max is 13", over.reason)

    val rejected = emit(s, BiddingIntent.FinalEstimate("p2", 14))
    assertTrue("the reducer must reject 14 too: $rejected", rejected is EmitResult.Rejected)
    assertEquals("Max is 13", (rejected as EmitResult.Rejected).reason)
  }

  // ── The fast exemption removes the CALL cap only ─────────────────────
  @Test
  fun t24aFastSeatMayDeclareAboveTheCallersNumberBecauseFastRoundsHaveNoCallCap() {
    val s = confirmedSuperCallWiped()
    // The Caller's confirmed 8 is not a ceiling in a fast round: 10 is legal
    // and opens a new Super Call.
    val ok = canSubmit(s, BiddingIntent.FinalEstimate("p2", 10))
    assertTrue("a fast round has no Caller-relative cap: ${ok.reason}", ok.legal)
  }

  // ── The trump selection path: 13 is legal, 14 is not ──────────────────
  @Test
  fun t24aSuperCallerMaySelectThirteenAsTheFinalNumber() {
    val s = confirmedSuperCall()
    val ok = canSubmit(s, BiddingIntent.ConfirmCall("p1", 13, Suit.HEARTS))
    assertTrue("13 must be legal: ${ok.reason}", ok.legal)

    val applied = confirmState(s, "p1", 13, Suit.HEARTS)
    assertEquals(13, applied.auctionTop)
    assertEquals(Suit.HEARTS, applied.declaredTrump)
    assertEquals(BiddingPhase.ESTIMATES, applied.subPhase)
  }

  @Test
  fun t24aSuperCallerMayNotRaiseTheCallToFourteen() {
    val s = confirmedSuperCall()
    val over = canSubmit(s, BiddingIntent.ConfirmCall("p1", 14, Suit.HEARTS))
    assertFalse("14 is one over the absolute maximum", over.legal)
    assertEquals("Max is 13", over.reason)

    val rejected = emit(s, BiddingIntent.ConfirmCall("p1", 14, Suit.HEARTS))
    assertTrue("the reducer must reject 14 too: $rejected", rejected is EmitResult.Rejected)
    assertEquals("Max is 13", (rejected as EmitResult.Rejected).reason)

    // The rejected confirmation changes nothing: 13 still works afterwards.
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p1", s.waitingFor)
    val applied = confirmState(s, "p1", 13, Suit.HEARTS)
    assertEquals(13, applied.auctionTop)
  }

  // ── The raise path: an existing Super Call cannot be raised past 13 ───
  @Test
  fun t24raisingAnExistingSuperCallAboveThirteenIsRejected() {
    val s = confirmedSuperCallWiped()
    val over = canSubmit(s, BiddingIntent.FinalEstimate("p2", 14))
    assertFalse("a raise may not exceed the absolute maximum", over.legal)
    assertEquals("Max is 13", over.reason)
  }

  // ── The normal round keeps its Caller-relative cap ───────────────────
  @Test
  fun t24aNormalRoundKeepsTheCallersRelativeCap() {
    var s = initNormalRound(1, "p1", seats)
    repeat(4) {
      val seat = s.waitingFor ?: throw AssertionError("waitingFor null mid-DASH")
      val dash = emit(s, BiddingIntent.DashCallDecision(seat, false))
      s = (dash as? EmitResult.Applied)?.state ?: throw AssertionError("the dash must not complete the round")
    }
    assertEquals(BiddingPhase.AUCTION, s.subPhase)

    s = (emit(s, BiddingIntent.AuctionBid("p1", false, 8, Suit.SPADES)) as EmitResult.Applied).state
    s = (emit(s, BiddingIntent.AuctionBid("p2", true, null, null)) as EmitResult.Applied).state
    s = (emit(s, BiddingIntent.AuctionBid("p3", true, null, null)) as EmitResult.Applied).state
    s = (emit(s, BiddingIntent.AuctionBid("p4", true, null, null)) as EmitResult.Applied).state
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p1", s.callerId)
    assertEquals(8, s.auctionTop)

    s = confirmState(s, "p1", 8, Suit.SPADES)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)

    // The normal round keeps the Caller-relative cap: 9 is over it.
    val over = canSubmit(s, BiddingIntent.FinalEstimate("p2", 9))
    assertFalse("the Caller's cap is the ceiling in a normal round", over.legal)
    assertEquals("Max is 8 (Caller's cap)", over.reason)

    // The cap itself is legal.
    val ok = canSubmit(s, BiddingIntent.FinalEstimate("p2", 8))
    assertTrue("the Caller's own number is legal: ${ok.reason}", ok.legal)
  }
}
