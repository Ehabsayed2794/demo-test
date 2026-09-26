package com.estemshan.game.ui.quickmatch

import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.bot.BidBrain
import com.estemshan.game.ui.bot.BotRoster
import com.estemshan.game.ui.bot.BotSeat
import com.estemshan.game.ui.bidding.BiddingViewModel
import com.estemshan.game.ui.table.TableViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Step 3 of S11: the driver end to end through the *real* quick-match view
 * models — no fakes at all.
 *
 * Where [com.estemshan.game.ui.bot.BotDriverTest] pinned the driver's clauses
 * against a stand-in provider, this proves the plumbing that ships: the
 * [QuickMatchRoundProvider] adapter really does route a bot's intent through
 * `BiddingViewModel.submit` / `TableViewModel.play` (so it clears the same
 * legality a tap clears), and [QuickMatchViewModel.attachBots] arms a driver
 * that closes an auction and plays a round with zero human input.
 *
 * What the tests do NOT do is script a hand. Quick-match deals unseeded (the
 * S13 fix), so every assertion is an invariant over whatever the deal happened
 * to be — an auction closes, 13 tricks are accounted for, totals equal the
 * deltas — which is the stronger claim anyway: it must hold for every deal,
 * not just a cherry-picked one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickMatchBotIntegrationTest {

  private val seats = DEFAULT_SEATS

  private lateinit var qvm: QuickMatchViewModel
  private lateinit var bvm: BiddingViewModel
  private lateinit var tvm: TableViewModel

  /** Four bots, one per tier and personality — the full skill spread. */
  private val allBots = BotRoster.of(
    *seats.mapIndexed { i, seat ->
      seat to BotSeat(
        BotTier.entries[i % BotTier.entries.size],
        BotPersonality.entries[i % BotPersonality.entries.size],
      )
    }.toTypedArray(),
  )

  @Before
  fun setUp() {
    qvm = QuickMatchViewModel()
    bvm = BiddingViewModel()
    tvm = TableViewModel()
  }

  @After
  fun tearDown() {
    qvm.detachBots()
    Dispatchers.resetMain()
  }

  /**
   * Start a match with [roster] and arm the driver on the real view models.
   * [Dispatchers.setMain] is given the test scheduler so `advanceUntilIdle`
   * advances the driver's `viewModelScope` coroutines along with the test's
   * own — the driver runs on Main in production, so the test must not move it
   * elsewhere.
   */
  private fun TestScope.setUpMatch(roster: BotRoster) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    qvm.startMatch(roster)
    bvm.startNormalRound(qvm.round.value, qvm.dealer.value, qvm.seats, qvm.biddingMultiplier.value)
    qvm.attachBots(bvm, tvm)
  }

  // ==========================================================================
  //  The whole round, with no human input
  // ==========================================================================

  @Test
  fun fourBotsCloseTheAuctionAndPlayAFullRound() = runTest {
    setUpMatch(allBots)

    val outcome = closeAuction()
    val cfg = qvm.onBiddingComplete(outcome)
    tvm.startRound(cfg, qvm.seats)

    val table = playOutRound()

    // `cfg.round` is the round NUMBER (1–18); trickNo is the count, and it
    // holds at 13 when the last resolve flips the table to DONE.
    assertEquals("a round is 13 tricks", 13, table.trickNo)
    assertEquals(
      "every one of the 13 tricks is accounted for",
      13,
      table.tricksWon.values.sum(),
    )
    assertEquals("the table reaches DONE", TablePhase.DONE, table.phase)
    // No bot tripped the rejection surface — a double-dispatch or an illegal
    // intent would show up here first.
    assertNull("bidding rejected an intent", bvm.rejection.value)
    assertNull("the table rejected a card", tvm.rejection.value)
  }

  @Test
  fun theStandingsReflectTheRoundThatWasActuallyPlayed() = runTest {
    setUpMatch(allBots)

    val outcome = closeAuction()
    val cfg = qvm.onBiddingComplete(outcome)
    tvm.startRound(cfg, qvm.seats)
    val table = playOutRound()

    qvm.onTableDone(cfg, table.tricksWon)

    assertNotNull("scoring produced standings", qvm.standings.value)
    val totals = qvm.totals.value
    assertEquals("every seat has a total", seats.toSet(), totals.keys)
    // The engine's forbidden-13 invariant, at match scale through the real VMs.
    assertFalse("the round totalled exactly 13 bids", cfg.estimates.values.sum() == 13)
    // A real round of Estemshan is not all-zeros: somebody scored.
    assertTrue("nobody scored — the round was scored as if nothing happened",
      totals.values.sum() != 0 || cfg.estimates.values.all { it == 0 })
  }

  // ==========================================================================
  //  A mixed table waits for its human
  // ==========================================================================

  @Test
  fun aMixedTableStallsOnTheHumanSeatAndResumesWhenTheyAct() = runTest {
    setUpMatch(BotRoster.versusBots(human = "p1", seats = seats))

    // p1 is human. The driver arms, the auction opens on the dealer, and
    // nothing happens until a human intent arrives — the bots never move for
    // a seat that is not theirs.
    advanceUntilIdle()
    assertEquals("the auction waits on the human, not a bot", "p1", bvm.state.value?.waitingFor)
    assertNull("no outcome without the human", bvm.outcome.value)

    // A human move, submitted exactly as the Bidding screen would: an EXPERT
    // brain is just a legal-intent generator standing in for a tap.
    val state = bvm.state.value!!
    bvm.submit(BidBrain.decide(state, qvm.handFor("p1")!!, "p1", BotTier.EXPERT))

    assertNotNull("once the human acts, the bots close the auction", closeAuction())
  }

  // ==========================================================================
  //  Arming semantics
  // ==========================================================================

  @Test
  fun aHumansOnlyMatchNeverMovesOnItsOwn() = runTest {
    setUpMatch(BotRoster.HUMANS_ONLY)

    // The driver is armed but inert: it collects and never acts, so the
    // auction sits exactly where the lobby left it, waiting for four humans.
    advanceUntilIdle()
    assertEquals("the auction is still in its opening phase", BiddingPhase.DASH, bvm.state.value?.subPhase)
    assertEquals("the dealer is still waiting", "p1", bvm.state.value?.waitingFor)
    assertNull("no outcome appeared", bvm.outcome.value)
  }

  // ==========================================================================
  //  Harness: the composable glue, re-implemented for a headless test
  // ==========================================================================

  /**
   * Advance until the auction closes, applying the quick-match flow's general
   * pass handling (the Bidding screen does this in a `LaunchedEffect`): the
   * round restarts at the doubled multiplier with a fresh deal. When the turn
   * rests on a human seat the harness plays it with an EXPERT stand-in, as the
   * Bidding screen would surface a tap — a bot seat holding the turn is the
   * one stall this treats as a failure. Bounded, so a pathological deal fails
   * the test instead of hanging it.
   */
  private suspend fun TestScope.closeAuction(): BiddingOutcome {
    var redeals = 0
    while (bvm.outcome.value == null) {
      advanceUntilIdle()
      val doubled = bvm.generalPass.value
      if (doubled != null) {
        check(++redeals < 40) { "auction general-passed 40 times without closing" }
        qvm.applyGeneralPass(doubled)
        bvm.startNormalRound(qvm.round.value, qvm.dealer.value, qvm.seats, qvm.biddingMultiplier.value)
        println(
          "closeAuction: REDEAL #$redeals applied, waitingFor=${bvm.state.value?.waitingFor} " +
            "subPhase=${bvm.state.value?.subPhase} mult=${bvm.biddingMultiplier.value}",
        )
      } else {
        // TEMPORARY diagnostic: dumps the loop trajectory to the test report's
        // stdout so the post-redeal stall can be located without a local JDK.
        println(
          "closeAuction: stateNull=${bvm.state.value == null} waitingFor=${bvm.state.value?.waitingFor} " +
            "subPhase=${bvm.state.value?.subPhase} round=${bvm.state.value?.round} " +
            "outcome=${bvm.outcome.value != null} generalPass=${bvm.generalPass.value} " +
            "rejection=${bvm.rejection.value} bids=${bvm.state.value?.bids?.size} " +
            "history=${bvm.state.value?.actionHistory?.size} redeals=$redeals",
        )
        val seat = bvm.state.value?.waitingFor
          ?: error("auction is idle but no seat is waiting on it (see stdout above)")
        if (qvm.roster.value.isBot(seat)) {
          error("auction stalled waiting on $seat (a bot seat should not stall)")
        }
        val held = bvm.state.value!!
        bvm.submit(BidBrain.decide(held, qvm.handFor(seat)!!, seat, BotTier.EXPERT))
        check(bvm.state.value !== held) { "the human stand-in's intent was rejected: ${bvm.rejection.value}" }
      }
    }
    return bvm.outcome.value!!
  }

  /**
   * Advance until the round ends, resolving each completed trick the way the
   * Table screen does after its highlight (the driver only acts in PLAY, so
   * RESOLVING must be collected for the round to progress).
   */
  private suspend fun TestScope.playOutRound(): TableState {
    var tricks = 0
    while (tvm.state.value?.phase != TablePhase.DONE) {
      advanceUntilIdle()
      val s = tvm.state.value ?: error("the table never started")
      when (s.phase) {
        TablePhase.RESOLVING -> {
          check(++tricks <= 13) { "resolved more than 13 tricks" }
          tvm.resolve()
        }
        TablePhase.PLAY ->
          // PLAY but idle: a human seat is holding the turn.
          if (!qvm.roster.value.isBot(s.turn ?: "")) {
            return s
          } else error("the round stalled in PLAY on bot seat ${s.turn}")
        else -> error("unexpected phase ${s.phase}")
      }
    }
    return tvm.state.value!!
  }
}
