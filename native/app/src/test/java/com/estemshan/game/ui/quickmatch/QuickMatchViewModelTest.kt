package com.estemshan.game.ui.quickmatch

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
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
  fun generalPassStartsTheNextRoundWithFreshHands() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch()
    val before = seats.associateWith { qvm.handFor(it)!! }

    // S1: a general pass starts the NEXT round — a new round number, a
    // rotated dealer, and a fresh deal. A pass-happy table must never replay
    // one hand forever (which soft-locks once Choose Level can seat four
    // bots), and the restarted auction must open the new round, not the old.
    qvm.applyGeneralPass(2)

    val after = seats.associateWith { qvm.handFor(it)!! }
    assertEquals("every seat still has a full hand after the pass", 52, after.values.sumOf { it.size })
    assertNotEquals("the general pass did not deal the next round's fresh hands", before, after)
    assertEquals("the pass did not move to the next round", 2, qvm.round.value)
    assertEquals("the dealer did not rotate on the pass", "p2", qvm.dealer.value)
    assertEquals("the pass did not arm the next round's multiplier", 2, qvm.biddingMultiplier.value)
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

  // ==========================================================================
  //  S13 — deals are seeded, so a whole match reproduces from its match seed.
  // ==========================================================================

  private fun dealSnapshot(qvm: QuickMatchViewModel) = seats.associateWith { qvm.handFor(it)!! }

  @Test
  fun theSameSeedDealsTheSameMatchAcrossRoundsAndPasses() {
    val a = QuickMatchViewModel()
    val b = QuickMatchViewModel()
    a.startMatch(matchSeed = 111L)
    b.startMatch(matchSeed = 111L)
    assertEquals("round 1 deals match", dealSnapshot(a), dealSnapshot(b))

    // A pass at the same position must reproduce too — the deal seed derives
    // from the NEW round number, it neither resets nor re-deals the old one.
    a.applyGeneralPass(2)
    b.applyGeneralPass(2)
    assertEquals("the pass round reproduces at both tables", dealSnapshot(a), dealSnapshot(b))
    assertEquals("the pass advanced the two tables identically",
      a.round.value to a.dealer.value, b.round.value to b.dealer.value)

    a.nextRound()
    b.nextRound()
    assertEquals("round 3 deals match", dealSnapshot(a), dealSnapshot(b))
  }

  @Test
  fun differentSeedsDealDifferentHands() {
    val a = QuickMatchViewModel().apply { startMatch(matchSeed = 111L) }
    val b = QuickMatchViewModel().apply { startMatch(matchSeed = 222L) }
    assertNotEquals("two seeds must not deal the same table", dealSnapshot(a), dealSnapshot(b))
  }

  @Test
  fun aFreshMatchDealsUnpredictably() {
    // No seed passed → a fresh random one, so no two quick matches share a deal.
    val a = QuickMatchViewModel().apply { startMatch() }
    val b = QuickMatchViewModel().apply { startMatch() }
    assertNotEquals("two unsown matches dealt the same hands", dealSnapshot(a), dealSnapshot(b))
  }

  @Test
  fun theMatchSeedIsObservableAndStableForTheMatch() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch(matchSeed = 4242L)
    val seed = qvm.matchSeed.value
    assertEquals(4242L, seed)

    // Rounds (and a general pass consuming one) advance the *deal* seed, not
    // the match seed — a replay is one number for the whole match.
    qvm.nextRound()
    qvm.applyGeneralPass(2)
    assertEquals("the match seed moved mid-match", seed, qvm.matchSeed.value)
  }

  // ==========================================================================
  //  S69 (E7) — game type + calculation mode reach the quick match.
  // ==========================================================================

  /**
   * Pins the S69 cap fix: two stacked sa'aydas under CLASSIC arm ×2, not
   * ×4 — then an exact round scores the Classic formula at that cap
   * (caller 25, others 15/14/25, all ×2). Under the old bug (classic
   * hard-coded false, no cap) the same script banks 88/48/… at ×4 and
   * the Normal deltas, so this fails without the fix.
   */
  @Test
  fun classicCapsEscalationAt2() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch(scoringMode = ScoringMode.CLASSIC)
    assertEquals(ScoringMode.CLASSIC, qvm.scoringMode.value)

    val saayda = BiddingOutcome(
      trump = Suit.SPADES,
      callerId = "p1",
      withPlayers = emptyList(),
      estimates = mapOf("p1" to 4, "p2" to 3, "p3" to 3, "p4" to 3),
      dashCallers = emptyList(),
      riskPlayerId = "p4",
      leaderId = "p1",
    )
    val saaydaTricks = mapOf("p1" to 2, "p2" to 1, "p3" to 5, "p4" to 0)
    repeat(2) {
      qvm.onBiddingComplete(saayda)
      qvm.onTableDone(qvm.onBiddingComplete(saayda).copy(), saaydaTricks)
      qvm.nextRound()
    }
    assertEquals("sa'aydas score zero under both modes",
      mapOf("p1" to 0, "p2" to 0, "p3" to 0, "p4" to 0), qvm.totals.value)

    val exact = BiddingOutcome(
      trump = Suit.SPADES,
      callerId = "p1",
      withPlayers = emptyList(),
      estimates = mapOf("p1" to 2, "p2" to 2, "p3" to 1, "p4" to 2),
      dashCallers = emptyList(),
      riskPlayerId = "p4",
      leaderId = "p1",
    )
    qvm.onBiddingComplete(exact)
    qvm.onTableDone(
      qvm.onBiddingComplete(exact).copy(),
      mapOf("p1" to 2, "p2" to 2, "p3" to 1, "p4" to 2),
    )
    assertEquals("Classic deltas at the ×2 cap",
      mapOf("p1" to 50, "p2" to 30, "p3" to 28, "p4" to 50), qvm.totals.value)
  }

  @Test
  fun miniQuickMatchPlaysExactly10Rounds() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch(matchSeed = 7L, gameType = GameType.MINI)
    assertEquals(GameType.MINI, qvm.gameType.value)
    assertEquals(10, qvm.maxRounds)
    repeat(30) { qvm.nextRound() }
    assertEquals("a MINI match ends after round 10", 10, qvm.round.value)
  }

  @Test
  fun fullQuickMatchStillCapsAt18() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch(matchSeed = 7L)
    assertEquals(GameType.FULL, qvm.gameType.value)
    assertEquals(18, qvm.maxRounds)
    repeat(30) { qvm.nextRound() }
    assertEquals("a FULL match ends after round 18", 18, qvm.round.value)
  }

  @Test
  fun aPassDealsTheNextRoundsSharedDealNotADuplicate() {
    // S1: the deal seed is the match seed offset by the round number, and a
    // pass consumes the round — so round 1's pass deals exactly round 2's
    // opening deal. (The old constraint — redeal ≠ next round's deal — is
    // gone because the same-round redeal no longer exists.)
    val passed = QuickMatchViewModel().apply {
      startMatch(matchSeed = 111L)
      applyGeneralPass(2) // round 1 consumed → round 2, first deal
    }
    val nextRound = QuickMatchViewModel().apply {
      startMatch(matchSeed = 111L)
      nextRound() // round 2, first deal
    }
    assertEquals(
      "a passed round's deal is not the next round's opening deal",
      dealSnapshot(passed),
      dealSnapshot(nextRound),
    )
  }

  // ==========================================================================
  //  S1 (A8.7 / Q51) — the engine's own pass ladder, driven end to end
  // ==========================================================================

  /**
   * Decline every decision in a real auction: all four seats decline the
   * Dash Call, then all four pass — the general pass script. The turn walk
   * reads the engine's own `waitingFor` (never a fixed seat order — the
   * dealer-first order matters) and the general-pass emit is expected to be
   * the fourth pass's result.
   */
  private fun BiddingViewModel.allPass() {
    repeat(4) { declineDash() }
    repeat(4) {
      val seat = state.value?.waitingFor ?: error("the auction is idle before all seats passed")
      submit(BiddingIntent.AuctionBid(seat, isPass = true))
    }
  }

  /**
   * Five passes driven through a REAL BiddingViewModel — not a hand-typed
   * doubled value — walking the Normal ladder ×2 → ×4 → ×6 → ×8 → cap ×8.
   * Each pass starts the next round number with a rotated dealer, arms that
   * round's multiplier for scoring, and deals a fresh hand.
   */
  @Test
  fun normalPassStartsTheNextNumberedRoundAtTheEngineDoubledMultiplier() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch(matchSeed = 9L)
    repeat(5) { qvm.nextRound() }
    assertEquals(6, qvm.round.value)
    assertEquals("p2", qvm.dealer.value)
    assertEquals("round 6 opens at the plain multiplier", 1, qvm.biddingMultiplier.value)

    val bvm = BiddingViewModel()
    val ladder = listOf(2, 4, 6, 8, 8) // NORMAL 1→2→4→6→8, capped at 8
    val dealers = listOf("p3", "p4", "p1", "p2", "p3")
    for ((step, doubled) in ladder.withIndex()) {
      val beforeHands = dealSnapshot(qvm)
      bvm.startNormalRound(
        qvm.round.value,
        qvm.dealer.value,
        seats,
        qvm.biddingMultiplier.value,
        qvm.scoringMode.value,
      )
      bvm.allPass()
      assertEquals(
        "the engine derived the wrong next multiplier on pass ${step + 1}",
        doubled,
        bvm.generalPass.value,
      )
      qvm.applyGeneralPass(doubled)

      assertEquals("the pass did not start the next numbered round", 7 + step, qvm.round.value)
      assertEquals("the dealer did not rotate on the pass", dealers[step], qvm.dealer.value)
      assertEquals(
        "the pass did not arm the next round's multiplier",
        doubled,
        qvm.biddingMultiplier.value,
      )
      assertNotEquals(
        "pass ${step + 1} replayed the round's own deal",
        beforeHands,
        dealSnapshot(qvm),
      )
    }
    assertEquals("the ladder walked 11 rounds worth of passes", 11, qvm.round.value)
    assertEquals("the ceiling round still scores ×8", 8, qvm.biddingMultiplier.value)
  }

  /**
   * Under CLASSIC the engine's pass leaves the multiplier at 1 — but the pass
   * itself still does everything S1 requires: next round number, rotated
   * dealer, and a fresh deal.
   */
  @Test
  fun classicPassNeverCarriesAMultiplier() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch(matchSeed = 9L, scoringMode = ScoringMode.CLASSIC)

    val bvm = BiddingViewModel()
    repeat(2) { nth ->
      val beforeHands = dealSnapshot(qvm)
      val round = qvm.round.value
      bvm.startNormalRound(
        round,
        qvm.dealer.value,
        seats,
        qvm.biddingMultiplier.value,
        qvm.scoringMode.value,
      )
      bvm.allPass()
      assertEquals("a Classic pass never escalates on pass ${nth + 1}", 1, bvm.generalPass.value)
      qvm.applyGeneralPass(1)

      assertEquals("the pass did not consume the round", round + 1, qvm.round.value)
      assertEquals("the multiplier must stay 1 under CLASSIC", 1, qvm.biddingMultiplier.value)
      assertNotEquals("the pass replayed the round's own deal", beforeHands, dealSnapshot(qvm))
    }
    assertEquals("two passes advanced two rounds", 3, qvm.round.value)
    assertEquals("the dealer rotated twice", "p3", qvm.dealer.value)
  }

  /**
   * A pass that consumes the PLANNED final round completes the match instead
   * of re-dealing into a phantom round: standings bank the running totals (a
   * cancelled round scores nothing for anyone), the round number stays at its
   * ceiling so the standings screen's end-of-match state holds, further rounds
   * are refused, and no new deal happens.
   */
  @Test
  fun aPassAtThePlannedFinalRoundCompletesTheMatch() {
    val qvm = QuickMatchViewModel()
    qvm.startMatch(matchSeed = 9L, gameType = GameType.MINI) // baseRounds 10
    repeat(9) { qvm.nextRound() }
    assertEquals(10, qvm.round.value)

    val beforeHands = dealSnapshot(qvm)
    qvm.applyGeneralPass(2)

    val standings = qvm.standings.value
      ?: error("the final-round pass did not complete the match")
    assertEquals("the cancelled final round left the match's own totals banked",
      mapOf("p1" to 0, "p2" to 0, "p3" to 0, "p4" to 0), qvm.totals.value)
    assertEquals("every seat's last delta is a cancelled round's zero",
      listOf(0, 0, 0, 0), standings.rows.map { it.lastDelta })
    assertTrue("a cancelled round is not a sa'ayda", standings.rows.none { it.saaydaBadge })

    assertEquals("the round number must stay at its ceiling", 10, qvm.round.value)
    assertEquals("the completed match must not deal a fresh hand", beforeHands, dealSnapshot(qvm))
    qvm.nextRound()
    assertEquals("rounds are refused after the pass completes the match", 10, qvm.round.value)
    assertEquals("the dealer must not rotate after the match is complete", "p2", qvm.dealer.value)
  }
}
