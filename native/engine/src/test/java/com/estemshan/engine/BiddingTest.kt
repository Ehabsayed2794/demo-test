package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit mirror of the owner-pinned JS bidding cases:
 * tests/with-grant-paths.test.cjs (C1–C3 With paths, A1–A3 fast Caller/With
 * locks) and tests/fast-super-reset.test.cjs (S1–S3 Super-Call reset).
 * Same numbers, same turn orders, Kotlin implementation.
 */
class BiddingTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun normal(dealer: String, round: Int = 1): BiddingState =
    initNormalRound(round, dealer, seats)

  private fun fast(round: Int, dealer: String): BiddingState =
    initFastRound(round, dealer, seats)

  private fun applied(result: EmitResult): BiddingState = when (result) {
    is EmitResult.Applied -> result.state
    is EmitResult.Rejected -> throw AssertionError("expected Applied, got Rejected(${result.reason})")
    is EmitResult.Completed -> throw AssertionError("expected Applied, got Completed early")
    is EmitResult.GeneralPass -> throw AssertionError("expected Applied, got GeneralPass")
  }

  private fun dash(s: BiddingState, seat: String, declared: Boolean): BiddingState {
    val intent = BiddingIntent.DashCallDecision(seat, declared)
    val verdict = canSubmit(s, intent)
    assertTrue("dash $seat legal: ${verdict.reason}", verdict.legal)
    return applied(emit(s, intent))
  }

  private fun declineAllDash(s: BiddingState): BiddingState {
    var cur = s
    repeat(4) {
      val seat = cur.waitingFor ?: throw AssertionError("waitingFor null mid-DASH")
      cur = dash(cur, seat, false)
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
    val verdict = canSubmit(s, intent)
    assertTrue("auction $seat $tricks $suit pass=$isPass legal: ${verdict.reason}", verdict.legal)
    return applied(emit(s, intent))
  }

  private fun confirm(s: BiddingState, seat: String, tricks: Int, suit: Suit): BiddingState {
    val intent = BiddingIntent.ConfirmCall(seat, tricks, suit)
    val verdict = canSubmit(s, intent)
    assertTrue("confirm $seat $tricks $suit legal: ${verdict.reason}", verdict.legal)
    return applied(emit(s, intent))
  }

  private fun estimate(s: BiddingState, seat: String, tricks: Int): EmitResult {
    val intent = BiddingIntent.FinalEstimate(seat, tricks)
    val verdict = canSubmit(s, intent)
    assertTrue("estimate $seat=$tricks legal: ${verdict.reason}", verdict.legal)
    return emit(s, intent)
  }

  // ── The auction opens on the dealer, even when the dealer isn't seats[0] ──
  @Test
  fun auctionOpensOnTheDealerNotOnSeatsZero() {
    // Session.rotateDealer advances the dealer every round, and the dash phase
    // starts at the dealer and rotates from firstBidder. The auction handoff
    // must follow that same order: before the fix it rebuilt the active list
    // in state.seats order, so a round dealt by p3 opened the bidding on p1.
    var s = normal("p3")
    s = declineAllDash(s)

    assertEquals(BiddingPhase.AUCTION, s.subPhase)
    assertEquals("auction must open on the dealer", "p3", s.waitingFor)
    assertEquals("active order follows the dealer", listOf("p3", "p4", "p1", "p2"), s.activeBidders)
  }

  @Test
  fun auctionOpensOnTheFirstActiveSeatAfterADealingDash() {
    // The dealer declared a Dash Call, so the auction opens on the next
    // still-active seat in dealer rotation — p4 — not on seats[0] (p1).
    var s = normal("p3")
    s = dash(s, "p3", true)
    s = dash(s, "p4", false)
    s = dash(s, "p1", false)
    s = dash(s, "p2", false)

    assertEquals(BiddingPhase.AUCTION, s.subPhase)
    assertEquals("auction must skip the dashed dealer", "p4", s.waitingFor)
    assertEquals(listOf("p4", "p1", "p2"), s.activeBidders)
  }

  // ── C1: live exact match grants With immediately; a pass never strips it ──
  @Test
  fun withLiveExactMatchAndPassKeepsIt() {
    var s = normal("p1")
    assertEquals(BiddingPhase.DASH, s.subPhase)
    assertEquals("p1", s.waitingFor)
    s = declineAllDash(s)
    assertEquals(BiddingPhase.AUCTION, s.subPhase)

    assertEquals("p1", s.waitingFor)
    s = auction(s, "p1", 4, Suit.SPADES)
    assertEquals("p2", s.waitingFor)
    s = auction(s, "p2", 4, Suit.SPADES)
    assertEquals(listOf("p2"), s.withPlayers)

    assertEquals("p3", s.waitingFor)
    s = auction(s, "p3", 5, Suit.SPADES)
    // Raising in the same suit keeps the earlier With.
    assertEquals(listOf("p2"), s.withPlayers)
    assertEquals("p4", s.waitingFor)
    s = auction(s, "p4", isPass = true)
    assertEquals("p1", s.waitingFor)
    s = auction(s, "p1", 6, Suit.SPADES)
    assertEquals("p2", s.waitingFor)
    s = auction(s, "p2", isPass = true)
    assertEquals(listOf("p2"), s.withPlayers)
    assertEquals("p3", s.waitingFor)
    s = auction(s, "p3", isPass = true)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
  }

  // ── C2: Alignment at conclusion grants same-suit bidders; C3: Jump-In ──
  @Test
  fun withAlignmentAndEstimationJumpIn() {
    var s = normal("p1")
    s = declineAllDash(s)
    s = auction(s, "p1", 4, Suit.SPADES)
    s = auction(s, "p2", 4, Suit.SPADES)
    s = auction(s, "p3", 5, Suit.SPADES)
    s = auction(s, "p4", isPass = true)
    s = auction(s, "p1", 6, Suit.SPADES)
    s = auction(s, "p2", isPass = true)
    s = auction(s, "p3", isPass = true)

    assertEquals("p1", s.callerId)
    assertEquals(6, s.auctionTop)
    assertEquals(Suit.SPADES, s.auctionSuit)
    assertEquals(listOf("p2", "p3"), s.withPlayers)
    assertTrue(!s.withPlayers.contains("p4"))
    assertTrue(!s.withPlayers.contains("p1"))

    s = confirm(s, "p1", 6, Suit.SPADES)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)

    // p2 (non-caller) jumps on the Caller's exact 6.
    var jumped: String? = null
    var guard = 0
    while (s.subPhase == BiddingPhase.ESTIMATES && guard < 6) {
      guard++
      val seat = s.waitingFor ?: break
      // With floors: p2 ≥ 4, p3 ≥ 5; cap is 6 everywhere.
      val pick = when (seat) {
        "p2" -> 6
        "p3" -> 5
        else -> 0
      }
      when (val r = estimate(s, seat, pick)) {
        is EmitResult.Applied -> {
          if (pick == 6 && seat != "p1") jumped = seat
          s = r.state
        }
        is EmitResult.Completed -> {
          if (pick == 6 && seat != "p1") jumped = seat
          s = r.state
          assertEquals("p1", r.outcome.callerId)
          assertTrue(r.outcome.withPlayers.contains(jumped))
          assertEquals(mapOf("p1" to 6, "p2" to 6, "p3" to 5, "p4" to 0), r.outcome.estimates)
          assertEquals("p4", r.outcome.riskPlayerId)
          assertEquals("p1", r.outcome.leaderId)
        }
        else -> throw AssertionError("unexpected result")
      }
    }
    assertEquals(BiddingPhase.DONE, s.subPhase)
    assertEquals("p2", jumped)
    assertTrue(s.withPlayers.contains("p2"))
  }

  // ── A1: fast round, unique highest estimate is Caller ──
  @Test
  fun fastUniqueHighestIsCaller() {
    var s = fast(15, "p1")
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals(Suit.SPADES, s.declaredTrump)
    val vals = mapOf("p1" to 5, "p2" to 6, "p3" to 4, "p4" to 5)
    val order = listOf("p1", "p2", "p3", "p4")
    var done: EmitResult.Completed? = null
    for (seat in order) {
      assertEquals(seat, s.waitingFor)
      when (val r = estimate(s, seat, vals.getValue(seat))) {
        is EmitResult.Applied -> s = r.state
        is EmitResult.Completed -> {
          s = r.state
          done = r
        }
        else -> throw AssertionError("unexpected result")
      }
    }
    assertEquals(BiddingPhase.DONE, s.subPhase)
    assertEquals("p2", s.callerId)
    assertTrue(s.withPlayers.isEmpty())
    val outcome = done?.outcome ?: throw AssertionError("never completed")
    assertEquals("p2", outcome.callerId)
    assertEquals(Suit.SPADES, outcome.trump)
  }

  // ── A2: fast-round tie breaks to the earliest bidder in dealer order ──
  @Test
  fun fastTieBreaksToEarliestBidder() {
    var s = fast(16, "p3")
    val vals = mapOf("p3" to 7, "p4" to 5, "p1" to 7, "p2" to 7)
    for (seat in listOf("p3", "p4", "p1", "p2")) {
      assertEquals(seat, s.waitingFor)
      when (val r = estimate(s, seat, vals.getValue(seat))) {
        is EmitResult.Applied -> s = r.state
        is EmitResult.Completed -> s = r.state
        else -> throw AssertionError("unexpected result")
      }
    }
    assertEquals(BiddingPhase.DONE, s.subPhase)
    assertEquals("p3", s.callerId)
    assertEquals(listOf("p1", "p2"), s.withPlayers)
  }

  // ── A3: all-zero fast round — no Caller, no With ──
  @Test
  fun fastAllZeroHasNoCaller() {
    var s = fast(17, "p1")
    repeat(4) {
      val seat = s.waitingFor ?: throw AssertionError("waitingFor null")
      when (val r = estimate(s, seat, 0)) {
        is EmitResult.Applied -> s = r.state
        is EmitResult.Completed -> s = r.state
        else -> throw AssertionError("unexpected result")
      }
    }
    assertEquals(BiddingPhase.DONE, s.subPhase)
    assertNull(s.callerId)
    assertTrue(s.withPlayers.isEmpty())
  }

  // ── S1–S3: fast Super-Call reset (round 15, dealer p1, p3 supers at 8) ──
  @Test
  fun fastSuperCallReset() {
    var s = fast(15, "p1")
    assertTrue(s.fastRound)
    assertEquals(Suit.SPADES, s.declaredTrump)

    for ((seat, v) in listOf("p1" to 4, "p2" to 5, "p3" to 8, "p4" to 3)) {
      assertEquals(seat, s.waitingFor)
      when (val r = estimate(s, seat, v)) {
        is EmitResult.Applied -> s = r.state
        is EmitResult.Completed -> throw AssertionError("should route to CONFIRM, not DONE")
        else -> throw AssertionError("unexpected result")
      }
    }

    // S1: 8+ routes to CONFIRM with the Super Caller waiting.
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p3", s.waitingFor)
    assertEquals("p3", s.callerId)
    assertEquals(8, s.auctionTop)

    // S2: replacement trump wipes only pre-super seats.
    s = confirm(s, "p3", 8, Suit.HEARTS)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    assertEquals("p1", s.waitingFor)
    assertNull(s.bids["p1"])
    assertNull(s.bids["p2"])
    assertEquals(Bid(BidType.TRICKS, 8), s.bids["p3"])
    assertEquals(Bid(BidType.TRICKS, 3), s.bids["p4"])
    assertEquals(Suit.HEARTS, s.declaredTrump)

    // S3: re-estimation completes with the Super Caller locked.
    when (val r = estimate(s, "p1", 4)) {
      is EmitResult.Applied -> s = r.state
      else -> throw AssertionError("p1 re-estimate should apply")
    }
    assertEquals("p2", s.waitingFor)
    var outcome: BiddingOutcome? = null
    when (val r = estimate(s, "p2", 2)) {
      is EmitResult.Completed -> {
        s = r.state
        outcome = r.outcome
      }
      else -> throw AssertionError("p2 re-estimate should complete")
    }
    assertEquals(BiddingPhase.DONE, s.subPhase)
    assertEquals("p3", s.callerId)
    assertTrue(s.withPlayers.isEmpty())
    assertEquals(Suit.HEARTS, s.declaredTrump)
    val o = outcome ?: throw AssertionError("never completed")
    assertEquals("p3", o.callerId)
    assertEquals(Suit.HEARTS, o.trump)
    assertEquals(mapOf("p1" to 4, "p2" to 2, "p3" to 8, "p4" to 3), o.estimates)
  }

  // ── Dash-Call seat skips estimation; outcome carries dashCallers ──
  @Test
  fun dashCallerSkipsEstimation() {
    var s = normal("p1")
    assertEquals("p1", s.waitingFor)
    s = dash(s, "p1", true)
    s = dash(s, "p2", false)
    s = dash(s, "p3", false)
    s = dash(s, "p4", false)
    assertEquals(BiddingPhase.AUCTION, s.subPhase)
    assertEquals(listOf("p2", "p3", "p4"), s.activeBidders)
    assertEquals("p2", s.waitingFor)

    s = auction(s, "p2", 4, Suit.CLUBS)
    s = auction(s, "p3", isPass = true)
    s = auction(s, "p4", isPass = true)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("p2", s.callerId)

    s = confirm(s, "p2", 4, Suit.CLUBS)
    assertEquals(BiddingPhase.ESTIMATES, s.subPhase)
    // p1 already holds a DASHCALL bid — estimation starts at p3.
    assertEquals("p3", s.waitingFor)

    var outcome: BiddingOutcome? = null
    when (val r = estimate(s, "p3", 3)) {
      is EmitResult.Applied -> s = r.state
      else -> throw AssertionError("p3 estimate should apply")
    }
    assertEquals("p4", s.waitingFor)
    when (val r = estimate(s, "p4", 0)) {
      is EmitResult.Completed -> {
        s = r.state
        outcome = r.outcome
      }
      else -> throw AssertionError("p4 estimate should complete")
    }
    assertEquals(BiddingPhase.DONE, s.subPhase)
    val o = outcome ?: throw AssertionError("never completed")
    assertEquals("p2", o.callerId)
    assertEquals(listOf("p1"), o.dashCallers)
    assertEquals(mapOf("p2" to 4, "p3" to 3, "p4" to 0), o.estimates)
    assertEquals("p4", o.riskPlayerId)
    assertEquals("p2", o.leaderId)
  }

  // ── General pass: all four pass → redeal at doubled (×8-capped) multiplier ──
  @Test
  fun generalPassDoublesMultiplier() {
    var s = normal("p1")
    s = declineAllDash(s)
    s = auction(s, "p1", isPass = true)
    s = auction(s, "p2", isPass = true)
    s = auction(s, "p3", isPass = true)
    val last = BiddingIntent.AuctionBid("p4", true)
    assertTrue(canSubmit(s, last).legal)
    when (val r = emit(s, last)) {
      is EmitResult.GeneralPass -> assertEquals(2, r.doubledMultiplier)
      else -> throw AssertionError("expected GeneralPass, got $r")
    }

    var capped = initNormalRound(1, "p1", seats, 5)
    capped = declineAllDash(capped)
    capped = auction(capped, "p1", isPass = true)
    capped = auction(capped, "p2", isPass = true)
    capped = auction(capped, "p3", isPass = true)
    when (val r = emit(capped, BiddingIntent.AuctionBid("p4", true))) {
      is EmitResult.GeneralPass -> assertEquals(8, r.doubledMultiplier)
      else -> throw AssertionError("expected capped GeneralPass, got $r")
    }
  }

  // ── Rejections: cap, With floor, forbidden 13, confirm guards ──
  @Test
  fun estimateRejections() {
    // Cap: caller locked 6, estimator tries 7.
    var s = normal("p1")
    s = declineAllDash(s)
    s = auction(s, "p1", 4, Suit.SPADES)
    s = auction(s, "p2", isPass = true)
    s = auction(s, "p3", isPass = true)
    s = auction(s, "p4", isPass = true)
    s = confirm(s, "p1", 4, Suit.SPADES)
    val over = BiddingIntent.FinalEstimate("p2", 5)
    val overVerdict = canSubmit(s, over)
    assertTrue(!overVerdict.legal)
    assertEquals("Max is 4 (Caller's cap)", overVerdict.reason)
    assertTrue(emit(s, over) is EmitResult.Rejected)

    // Forbidden 13: p2=4, p3=4, p4=4 on the table → p1's 1 totals 13.
    var f = fast(15, "p1")
    for ((seat, v) in listOf("p1" to 4, "p2" to 4, "p3" to 4)) {
      when (val r = estimate(f, seat, v)) {
        is EmitResult.Applied -> f = r.state
        else -> throw AssertionError("setup estimate should apply")
      }
    }
    val forbidden = BiddingIntent.FinalEstimate("p4", 1)
    val fVerdict = canSubmit(f, forbidden)
    assertTrue(!fVerdict.legal)
    assertEquals("Can't pick 1 — totals 13", fVerdict.reason)
    assertTrue(emit(f, forbidden) is EmitResult.Rejected)
    // ...but 2 is fine.
    assertTrue(canSubmit(f, BiddingIntent.FinalEstimate("p4", 2)).legal)
  }

  @Test
  fun confirmRejections() {
    var s = normal("p1")
    s = declineAllDash(s)
    s = auction(s, "p1", 5, Suit.HEARTS)
    s = auction(s, "p2", isPass = true)
    s = auction(s, "p3", isPass = true)
    s = auction(s, "p4", isPass = true)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    // Below the winning call.
    val low = BiddingIntent.ConfirmCall("p1", 4, Suit.HEARTS)
    assertEquals("Can't lower your winning call", canSubmit(s, low).reason)
    assertTrue(emit(s, low) is EmitResult.Rejected)
    // Same number, weaker suit.
    val weak = BiddingIntent.ConfirmCall("p1", 5, Suit.DIAMONDS)
    assertEquals("Same number needs an equal or stronger suit", canSubmit(s, weak).reason)
    assertTrue(emit(s, weak) is EmitResult.Rejected)
    // Same number, stronger suit is fine.
    assertTrue(canSubmit(s, BiddingIntent.ConfirmCall("p1", 5, Suit.SPADES)).legal)
  }

  // ── Predicates + fixed-trump cycle + risk fallback ──
  @Test
  fun predicatesAndHelpers() {
    assertTrue(auctionBidBeatsTop(5, Suit.SPADES, 5, Suit.HEARTS))
    assertTrue(!auctionBidBeatsTop(5, Suit.CLUBS, 5, Suit.HEARTS))
    assertTrue(auctionBidBeatsTop(6, Suit.CLUBS, 5, Suit.SPADES))
    assertTrue(!auctionBidBeatsTop(4, Suit.SANS, 5, Suit.CLUBS))

    assertTrue(auctionBidIsWith("p2", 4, Suit.SPADES, 4, Suit.SPADES, "p1"))
    assertTrue(!auctionBidIsWith("p1", 4, Suit.SPADES, 4, Suit.SPADES, "p1"))
    assertTrue(!auctionBidIsWith("p2", 4, Suit.SPADES, 4, Suit.SPADES, null))
    assertTrue(!auctionBidIsWith("p2", 4, Suit.HEARTS, 4, Suit.SPADES, "p1"))

    assertTrue(isFastRound(14))
    assertTrue(!isFastRound(13))
    assertEquals(Suit.SANS, fixedTrumpFor(14))
    assertEquals(Suit.SPADES, fixedTrumpFor(15))
    assertEquals(Suit.HEARTS, fixedTrumpFor(16))
    assertEquals(Suit.DIAMONDS, fixedTrumpFor(17))
    assertEquals(Suit.CLUBS, fixedTrumpFor(18))
    assertEquals(Suit.SANS, fixedTrumpFor(19))

    assertEquals("p4", computeRiskId(seats, "p1"))
    assertEquals("p2", computeRiskId(seats, "p3"))

    assertEquals(0, riskValue(12))
    assertEquals(10, riskValue(15))
    assertEquals(20, riskValue(9))
    assertEquals(30, riskValue(7))
  }

  // ── Wrong phase / turn / DONE are typed rejections, never silent ──
  @Test
  fun guardsAreTyped() {
    val s = normal("p1")
    val wrongPhase = canSubmit(s, BiddingIntent.AuctionBid("p1", true))
    assertTrue(!wrongPhase.legal)
    assertEquals("Not the Auction phase", wrongPhase.reason)

    val wrongTurn = canSubmit(s, BiddingIntent.DashCallDecision("p2", false))
    assertTrue(!wrongTurn.legal)
    assertEquals("Not this seat's turn", wrongTurn.reason)

    val done = s.copy(subPhase = BiddingPhase.DONE, waitingFor = null)
    assertEquals("Bidding is already complete", canSubmit(done, BiddingIntent.DashCallDecision("p1", false)).reason)
    assertTrue(emit(done, BiddingIntent.DashCallDecision("p1", false)) is EmitResult.Rejected)
  }
}
