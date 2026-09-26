package com.estemshan.game.ui.quickmatch

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.Suit
import com.estemshan.game.ui.bidding.BiddingViewModel
import com.estemshan.game.ui.table.TableViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMatchViewModelTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun BiddingViewModel.declineDash() {
    val seat = state.value?.waitingFor ?: error("no turn in DASH")
    submit(BiddingIntent.DashCallDecision(seat, false))
  }

  /** Full offline round through the real VMs: bidding → table → standings. */
  @Test
  fun fullOfflineRoundScoresAndStands() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch()
    assertEquals(1, qvm.round.value)
    assertEquals("p1", qvm.dealer.value)

    val bvm = BiddingViewModel()
    bvm.startNormalRound(qvm.round.value, qvm.dealer.value, seats, qvm.biddingMultiplier.value)
    repeat(4) { bvm.declineDash() }
    bvm.submit(BiddingIntent.AuctionBid("p1", false, 4, Suit.SPADES))
    bvm.submit(BiddingIntent.AuctionBid("p2", true))
    bvm.submit(BiddingIntent.AuctionBid("p3", true))
    bvm.submit(BiddingIntent.AuctionBid("p4", true))
    bvm.submit(BiddingIntent.ConfirmCall("p1", 4, Suit.SPADES))
    bvm.submit(BiddingIntent.FinalEstimate("p2", 3))
    bvm.submit(BiddingIntent.FinalEstimate("p3", 2))
    bvm.submit(BiddingIntent.FinalEstimate("p4", 0))
    val outcome = bvm.outcome.value ?: error("no outcome")
    assertEquals("p1", outcome.callerId)

    val cfg = qvm.onBiddingComplete(outcome)
    assertEquals(52, cfg.hands.values.sumOf { it.size })
    assertEquals(Suit.SPADES, cfg.trump)
    assertEquals("p1", cfg.leaderId)

    val tvm = TableViewModel()
    tvm.startRound(cfg, seats)
    var guard = 0
    while (tvm.state.value?.phase != com.estemshan.engine.TablePhase.DONE && guard < 200) {
      guard++
      val s = tvm.state.value!!
      if (s.phase == com.estemshan.engine.TablePhase.PLAY) {
        val seat = s.turn ?: error("no turn")
        val hand = s.cfg.hands.getValue(seat)
        val led = s.ledSuit
        tvm.play(seat, if (led == null) hand[0] else hand.firstOrNull { it.suit == led } ?: hand[0])
      } else {
        tvm.resolve()
      }
    }
    val done = tvm.state.value!!
    qvm.onTableDone(done.cfg, done.tricksWon)

    val standings = qvm.standings.value ?: error("no standings")
    assertEquals(4, standings.rows.size)
    val totals = standings.rows.associate { it.seat to it.total }
    assertEquals(totals, qvm.totals.value)
    assertEquals(totals.values.sum(), standings.rows.sumOf { it.lastDelta })
    val max = totals.values.maxOrNull()!!
    assertTrue(standings.rows.filter { it.total == max }.all { it.isWinner })

    qvm.nextRound()
    assertEquals(2, qvm.round.value)
    assertEquals("p2", qvm.dealer.value)
    assertEquals(totals, qvm.totals.value)
    assertEquals(null, qvm.standings.value)
  }

  @Test
  fun bidsFromOutcomeMapping() {
    val cfg = com.estemshan.engine.RoundCfg(
      round = 1,
      trump = Suit.SPADES,
      callerId = "p2",
      withPlayers = emptyList(),
      estimates = mapOf("p2" to 4, "p3" to 3, "p4" to 0),
      dashCallers = listOf("p1"),
      leaderId = "p2",
      riskId = "p4",
      hands = emptyMap(),
    )
    val bids = bidsFromOutcome(cfg)
    assertEquals(Bid(BidType.DASHCALL, 0), bids.getValue("p1"))
    assertEquals(Bid(BidType.TRICKS, 4), bids.getValue("p2"))
    assertEquals(Bid(BidType.TRICKS, 3), bids.getValue("p3"))
    assertEquals(Bid(BidType.DASH, 0), bids.getValue("p4"))
  }

  @Test
  fun generalPassDoublesRoundScore() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch()
    qvm.applyGeneralPass(2)
    val outcome = BiddingOutcome(
      trump = Suit.SPADES,
      callerId = "p1",
      withPlayers = emptyList(),
      estimates = mapOf("p1" to 2, "p2" to 2, "p3" to 2, "p4" to 2),
      dashCallers = emptyList(),
      riskPlayerId = "p4",
      leaderId = "p1",
    )
    qvm.onBiddingComplete(outcome)
    // Everyone takes exactly what they bid: raw 12/12/12/32 → doubled.
    qvm.onTableDone(
      qvm.onBiddingComplete(outcome).copy(),
      mapOf("p1" to 2, "p2" to 2, "p3" to 2, "p4" to 2),
    )
    assertEquals(mapOf("p1" to 44, "p2" to 24, "p3" to 24, "p4" to 64), qvm.totals.value)
  }

  @Test
  fun generalPassRedealsTheHands() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch()
    val before = seats.associateWith { qvm.handFor(it)!! }

    // A general pass re-deals: the restarted auction must see fresh hands, not
    // the same deal again — a pass-happy table otherwise replays one hand
    // forever, and soft-locks once Choose Level can seat four bots.
    qvm.applyGeneralPass(2)

    val after = seats.associateWith { qvm.handFor(it)!! }
    assertEquals("every seat still has a full hand after the redeal", 52, after.values.sumOf { it.size })
    assertNotEquals("the general pass did not redeal the hands", before, after)
  }

  @Test
  fun saaydaZeroesAndArmsNext() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch()
    val outcome = BiddingOutcome(
      trump = Suit.SPADES,
      callerId = "p1",
      withPlayers = emptyList(),
      estimates = mapOf("p1" to 4, "p2" to 3, "p3" to 3, "p4" to 3),
      dashCallers = emptyList(),
      riskPlayerId = "p4",
      leaderId = "p1",
    )
    qvm.onBiddingComplete(outcome)
    qvm.onTableDone(
      qvm.onBiddingComplete(outcome).copy(),
      mapOf("p1" to 2, "p2" to 1, "p3" to 5, "p4" to 0),
    )
    assertEquals(mapOf("p1" to 0, "p2" to 0, "p3" to 0, "p4" to 0), qvm.totals.value)
    assertTrue(qvm.standings.value!!.rows.all { it.saaydaBadge })

    // Next round scores doubled (armed ×2).
    qvm.nextRound()
    val outcome2 = outcome.copy(estimates = mapOf("p1" to 2, "p2" to 2, "p3" to 1, "p4" to 2))
    qvm.onBiddingComplete(outcome2)
    qvm.onTableDone(
      qvm.onBiddingComplete(outcome2).copy(),
      mapOf("p1" to 2, "p2" to 2, "p3" to 1, "p4" to 2),
    )
    val totals = qvm.totals.value
    assertEquals(2 * (10 + 2 + 10), totals.getValue("p1")) // caller win ×2
    assertEquals(2 * (10 + 2), totals.getValue("p2"))
  }
}
