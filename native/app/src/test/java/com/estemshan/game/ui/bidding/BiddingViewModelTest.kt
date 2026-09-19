package com.estemshan.game.ui.bidding

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiddingViewModelTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun BiddingViewModel.declineDash() {
    val seat = state.value?.waitingFor ?: error("no turn in DASH")
    submit(BiddingIntent.DashCallDecision(seat, false))
  }

  private fun fullAuction(vm: BiddingViewModel) {
    vm.submit(BiddingIntent.AuctionBid("p1", false, 4, Suit.SPADES))
    vm.submit(BiddingIntent.AuctionBid("p2", false, 4, Suit.SPADES))
    vm.submit(BiddingIntent.AuctionBid("p3", false, 5, Suit.SPADES))
    vm.submit(BiddingIntent.AuctionBid("p4", true))
    vm.submit(BiddingIntent.AuctionBid("p1", false, 6, Suit.SPADES))
    vm.submit(BiddingIntent.AuctionBid("p2", true))
    vm.submit(BiddingIntent.AuctionBid("p3", true))
  }

  @Test
  fun fullNormalRoundCompletesWithOutcome() {
    val vm = BiddingViewModel()
    vm.startNormalRound(1, "p1", seats)
    repeat(4) { vm.declineDash() }
    assertEquals(BiddingPhase.AUCTION, vm.state.value?.subPhase)
    fullAuction(vm)
    assertEquals(BiddingPhase.CONFIRM, vm.state.value?.subPhase)
    assertEquals("p1", vm.state.value?.callerId)

    vm.submit(BiddingIntent.ConfirmCall("p1", 6, Suit.SPADES))
    assertEquals(BiddingPhase.ESTIMATES, vm.state.value?.subPhase)

    vm.submit(BiddingIntent.FinalEstimate("p2", 6))
    assertEquals(4, vm.withFloorOf("p2"))
    vm.submit(BiddingIntent.FinalEstimate("p3", 5))
    vm.submit(BiddingIntent.FinalEstimate("p4", 0))

    assertEquals(BiddingPhase.DONE, vm.state.value?.subPhase)
    val outcome = vm.outcome.value ?: error("no outcome")
    assertEquals("p1", outcome.callerId)
    assertTrue(outcome.withPlayers.contains("p2"))
    assertEquals(mapOf("p1" to 6, "p2" to 6, "p3" to 5, "p4" to 0), outcome.estimates)
    assertEquals("p4", outcome.riskPlayerId)
    assertNull(vm.rejection.value)
  }

  @Test
  fun rejectionSurfacedThenCleared() {
    val vm = BiddingViewModel()
    vm.startNormalRound(1, "p1", seats)
    repeat(4) { vm.declineDash() }
    vm.submit(BiddingIntent.AuctionBid("p1", false, 4, Suit.SPADES))
    vm.submit(BiddingIntent.AuctionBid("p2", true))
    vm.submit(BiddingIntent.AuctionBid("p3", true))
    vm.submit(BiddingIntent.AuctionBid("p4", true))
    vm.submit(BiddingIntent.ConfirmCall("p1", 4, Suit.SPADES))

    vm.submit(BiddingIntent.FinalEstimate("p2", 5))
    assertEquals("Max is 4 (Caller's cap)", vm.rejection.value)
    assertEquals(BiddingPhase.ESTIMATES, vm.state.value?.subPhase)

    vm.submit(BiddingIntent.FinalEstimate("p2", 3))
    assertNull(vm.rejection.value)
  }

  @Test
  fun generalPassExposesDoubledMultiplier() {
    val vm = BiddingViewModel()
    vm.startNormalRound(1, "p1", seats)
    repeat(4) { vm.declineDash() }
    vm.submit(BiddingIntent.AuctionBid("p1", true))
    vm.submit(BiddingIntent.AuctionBid("p2", true))
    vm.submit(BiddingIntent.AuctionBid("p3", true))
    vm.submit(BiddingIntent.AuctionBid("p4", true))
    assertEquals(2, vm.generalPass.value)
  }

  @Test
  fun preStartGuards() {
    val vm = BiddingViewModel()
    assertTrue(!vm.legality(BiddingIntent.DashCallDecision("p1", false)).legal)
    vm.submit(BiddingIntent.DashCallDecision("p1", false))
    assertEquals("Bidding has not started", vm.rejection.value)
    assertNull(vm.forbiddenFor("p1"))
    assertNull(vm.withFloorOf("p1"))
    assertNull(vm.outcome.value)
    assertNull(vm.generalPass.value)
  }

  @Test
  fun forbiddenHintForLastEstimator() {
    val vm = BiddingViewModel()
    vm.startFastRound(15, "p1", seats)
    vm.submit(BiddingIntent.FinalEstimate("p1", 4))
    vm.submit(BiddingIntent.FinalEstimate("p2", 4))
    vm.submit(BiddingIntent.FinalEstimate("p3", 4))
    // p4 sees 12 on the table → 1 totals 13.
    assertEquals(1, vm.forbiddenFor("p4"))
  }
}
