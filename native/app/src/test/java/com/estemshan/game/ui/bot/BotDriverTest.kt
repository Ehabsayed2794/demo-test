package com.estemshan.game.ui.bot

import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.EmitResult
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.canSubmit
import com.estemshan.engine.emit
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.initTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 1 of S11: the [BotDriver] against a fake [RoundStateProvider].
 *
 * The fake is deliberately NOT a stub — it advances state through the real
 * `emit`/`emitPlay` reducers, exactly the pure functions the real view models
 * call, so "the driver moved the game on" is the same fact a host would
 * observe. What is faked is only the hosting: no Android lifecycle, no
 * Compose nav, no wall clock (the delay is immediate).
 *
 * Every test pins one clause of the driver's contract rather than a scripted
 * hand, because the driver is plumbing — its worth is the invariants, not the
 * particular cards it happens to play. Those come from the brains, whose
 * goldens already exist, and from the S10 match smoke test.
 */
class BotDriverTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  /**
   * The provider under test. Tests set its roster, seed its bidding/table
   * state, then [run] the driver and inspect [submitted]/[played].
   */
  private class FakeProvider(
    roster: BotRoster = BotRoster.HUMANS_ONLY,
    initialBidding: BiddingState? = null,
    initialTable: TableState? = null,
  ) : RoundStateProvider {
    private val _bidding = MutableStateFlow(initialBidding)
    override val bidding = _bidding.asStateFlow()
    private val _table = MutableStateFlow(initialTable)
    override val table = _table.asStateFlow()

    override var roster: BotRoster = roster
      private set

    /** Per-seat hands for the auction (BidBrain needs the raw cards). */
    var hands: Map<String, List<Card>> = emptyMap()

    override fun handFor(seat: String): List<Card>? = hands[seat]

    val submitted = mutableListOf<com.estemshan.engine.BiddingIntent>()
    val played = mutableListOf<PlayCard>()
    var redealCount = 0
      private set

    /** Seed for the redeal deals; bumped so a redeal never repeats a hand. */
    private var redealSeed = 11L

    /** The auction's resolved outcome, once it closes. */
    var outcome: BiddingOutcome? = null
      private set

    override fun submitBidding(intent: com.estemshan.engine.BiddingIntent) {
      submitted += intent
      val s = _bidding.value ?: return
      when (val r = emit(s, intent)) {
        is EmitResult.Applied -> _bidding.value = r.state
        is EmitResult.Completed -> {
          _bidding.value = r.state
          outcome = r.outcome
        }
        is EmitResult.GeneralPass -> {
          // The host's redeal, mirrored from QuickMatchFlow: the same round
          // restarts at the doubled multiplier with a fresh deal.
          _bidding.value = r.state
          check(++redealCount <= MAX_REDEALS) { "auction redealt $MAX_REDEALS times" }
          restart(r.doubledMultiplier)
        }
        is EmitResult.Rejected -> error("driver submitted an illegal intent: ${r.reason}")
      }
    }

    /** Restart the auction for a general pass (see [submitBidding]). */
    fun restart(multiplier: Int) {
      val previous = _bidding.value ?: return
      // A fresh deal too — the real host reshuffles, and re-bidding the
      // exact same hand could loop on the same all-pass forever.
      hands = Dealer.dealHands(++redealSeed)
      _bidding.value = initNormalRound(previous.round, previous.firstBidder, previous.seats, multiplier)
    }

    override fun playCard(seat: String, card: Card) {
      played += PlayCard(seat, card)
      val s = _table.value ?: return
      when (val r = emitPlay(s, PlayCard(seat, card))) {
        is PlayEmit.Applied -> _table.value = r.state
        is PlayEmit.Rejected -> error("driver played an illegal card: ${r.reason}")
      }
    }

    fun setBidding(state: BiddingState?) { _bidding.value = state }
    fun setTable(state: TableState?) { _table.value = state }
  }

  /**
   * Launch the driver with an immediate clock, let it settle, then stop it.
   * The collectors run for the lifetime of a match by design — a host cancels
   * the whole [Job] when its scope dies — so a test that returns with them
   * still active makes `runTest` wait out its timeout for nothing.
   */
  private fun run(provider: FakeProvider, delayMillis: (BotAction) -> Long = { 0 }) = runTest {
    val driverJob = BotDriver(provider, delayMillis).launchIn(this)
    advanceUntilIdle()
    driverJob.cancel()
    advanceUntilIdle()
  }

  /** A deal for every seat, from a seed. */
  private fun dealtHands(seed: Long = 11L): Map<String, List<Card>> = Dealer.dealHands(seed)

  // ==========================================================================
  //  Who the driver moves for
  // ==========================================================================

  @Test
  fun aBotActsWhenTheAuctionWaitsOnIt() {
    val state = initNormalRound(round = 1, dealer = "p1", seats = seats)
    val provider = FakeProvider(
      roster = BotRoster.of("p1" to BotSeat(BotTier.EXPERT)),
      initialBidding = state,
    )
    provider.hands = dealtHands()
    val seat = state.waitingFor!!

    run(provider)

    // Exactly one intent, for the waiting seat, and it was legal — the fake
    // errors on a rejected emit, so reaching here already means the brain's
    // answer cleared the real reducer. canSubmit is checked against the state
    // the intent was formed for, not the state it produced.
    assertEquals("the driver must act once for the waiting bot seat", 1, provider.submitted.size)
    assertTrue(
      "the submitted intent must be legal for $seat",
      canSubmit(state, provider.submitted.last()).legal,
    )
    // And the turn actually moved: the auction no longer waits on that seat.
    assertNotEquals(
      "the driver's intent must advance the turn off $seat",
      seat,
      provider.bidding.value?.waitingFor,
    )
  }

  @Test
  fun aHumanSeatIsNeverActedFor() {
    val state = initNormalRound(round = 1, dealer = "p1", seats = seats)
    // p1 is the only seat waiting, and the roster has no bots at all.
    val provider = FakeProvider(
      roster = BotRoster.HUMANS_ONLY,
      initialBidding = state,
    )
    provider.hands = dealtHands()

    run(provider)

    assertTrue("a humans-only roster must produce no bot actions", provider.submitted.isEmpty())
    assertEquals("the auction must sit untouched", state, provider.bidding.value)
  }

  @Test
  fun aBotNeverPlaysOutsideThePlayPhase() {
    // A completed trick waits on nobody, and RESOLVING is the UI's beat. The
    // driver must stay out of both — interjecting in RESOLVING would race the
    // screen's own auto-collect.
    val table = tableFor(phase = TablePhase.RESOLVING, turn = "p2")
    val provider = FakeProvider(
      roster = BotRoster.of("p2" to BotSeat(BotTier.EXPERT)),
      initialTable = table,
    )

    run(provider)

    assertTrue("the driver must not play while RESOLVING", provider.played.isEmpty())
  }

  @Test
  fun aBotNeverActsWhenTheAuctionIsDone() {
    // A finished auction lingers in state with no waitingFor; the driver
    // must read that as "nothing to do" rather than error.
    val done = initNormalRound(round = 1, dealer = "p1", seats = seats)
      .copy(subPhase = BiddingPhase.DONE, waitingFor = null)
    val provider = FakeProvider(
      roster = BotRoster.of(*seats.map { it to BotSeat(BotTier.EXPERT) }.toTypedArray()),
      initialBidding = done,
    )
    provider.hands = dealtHands()

    run(provider)

    assertTrue("a DONE auction must produce no actions", provider.submitted.isEmpty())
  }

  // ==========================================================================
  //  The recheck-after-delay guard
  // ==========================================================================

  @Test
  fun theDriverRechecksWhoseTurnItIsAfterTheDelay() {
    val state = initNormalRound(round = 1, dealer = "p1", seats = seats)
    val provider = FakeProvider(
      roster = BotRoster.of("p1" to BotSeat(BotTier.EXPERT)),
      initialBidding = state,
    )
    provider.hands = dealtHands()

    // The delay function stands in for elapsed time: it moves the auction on
    // to a human's turn BEFORE the driver re-reads the state, simulating the
    // round ending or a seat being taken over while the bot was thinking.
    run(provider) { _ ->
      provider.setBidding(state.copy(waitingFor = "p2")) // p2 is human
      0L
    }

    // The pre-delay snapshot said "p1, a bot, your move"; the CURRENT state
    // says otherwise. A driver without the recheck would submit for p1 anyway
    // and either be rejected or move a seat that was no longer acting.
    assertTrue("a turn that moved on during the delay must not be acted on", provider.submitted.isEmpty())
  }

  @Test
  fun theDriverRechecksThePlayTurnAfterTheDelay() {
    val table = tableFor(phase = TablePhase.PLAY, turn = "p2")
    val provider = FakeProvider(
      roster = BotRoster.of("p2" to BotSeat(BotTier.EXPERT)),
      initialTable = table,
    )

    run(provider) { _ ->
      provider.setTable(table.copy(turn = "p1")) // now a human's card
      0L
    }

    assertTrue("a play turn that moved on during the delay must not be acted on", provider.played.isEmpty())
  }

  // ==========================================================================
  //  Whole phases, driven end to end with no human input
  // ==========================================================================

  @Test
  fun fourBotsDriveAFullAuctionToCompletion() {
    // Every seat is a bot, so the auction must close with zero human input.
    // The provider redeals on a general pass, exactly as the real host does.
    val provider = FakeProvider(
      roster = BotRoster.of(
        *seats.map { it to BotSeat(BotTier.HARD, BotPersonality.BALANCED) }.toTypedArray(),
      ),
      initialBidding = initNormalRound(round = 1, dealer = "p1", seats = seats),
    )
    provider.hands = dealtHands()

    run(provider)

    val finished = provider.bidding.value
    assertEquals("the auction must reach DONE", BiddingPhase.DONE, finished?.subPhase)
    assertNull("DONE means nobody is waiting", finished?.waitingFor)
    assertNotNull("the auction must have produced an outcome", provider.outcome)
    assertTrue("general passes must be bounded", provider.redealCount < MAX_REDEALS)
    assertTrue("every bot turn took itself — no seat was skipped", provider.submitted.isNotEmpty())
  }

  @Test
  fun fourBotsPlayACompleteTrickAndStopAtResolving() {
    // Four bot plays fill a trick; the table then goes RESOLVING, which is
    // the UI's cue to collect. The driver must stop there — the fifth action
    // would land while the screen is mid-highlight.
    val provider = FakeProvider(
      roster = BotRoster.of(
        *seats.map { it to BotSeat(BotTier.EXPERT, BotPersonality.BALANCED) }.toTypedArray(),
      ),
      initialTable = tableFor(phase = TablePhase.PLAY, turn = "p1"),
    )

    run(provider)

    assertEquals("four bots must fill the trick with exactly four plays", 4, provider.played.size)
    assertEquals(
      "a full trick must leave the table RESOLVING",
      TablePhase.RESOLVING,
      provider.table.value?.phase,
    )
    assertNull("RESOLVING has no turn to act on", provider.table.value?.turn)
  }

  // ==========================================================================
  //  Cadence
  // ==========================================================================

  @Test
  fun theHumanizedDelayIsDeterministicAndWithinThePlansWindow() {
    val bid = BotAction.Bid("p3", round = 7, subPhase = BiddingPhase.ESTIMATES)
    val play = BotAction.Play("p3", round = 7, trickNo = 11)

    listOf(bid, play).forEach { action ->
      val first = BotDriver.HumanizedDelay(action)
      assertEquals("the same action must always wait the same time", first, BotDriver.HumanizedDelay(action))
      assertTrue(
        "the plan's cadence is 400..900 ms — $action waited $first ms",
        first in 400L..900L,
      )
    }

    // Distinct moments should not collapse onto one value; a table where every
    // seat answers at the same instant feels mechanical, not human.
    val waits = (1..13).flatMap { round ->
      seats.map { seat -> BotDriver.HumanizedDelay(BotAction.Play(seat, round, trickNo = 1)) }
    }.toSet()
    assertTrue("the cadence must vary across seats and rounds, got ${waits.size} distinct waits", waits.size > 1)
  }

  // ==========================================================================
  //  Helpers
  // ==========================================================================

  /** A minimal but legal table for [phase], dealing real hands. */
  private fun tableFor(phase: TablePhase, turn: String?): TableState {
    val hands = dealtHands()
    val cfg = RoundCfg(
      round = 1,
      trump = Suit.SPADES,
      callerId = "p4",
      withPlayers = emptyList(),
      estimates = mapOf("p4" to 6),
      dashCallers = emptyList(),
      leaderId = turn ?: "p1",
      riskId = null,
      multiplier = 1,
      hands = hands,
    )
    return initTable(cfg, seats).copy(phase = phase, turn = turn)
  }

  private companion object {
    const val MAX_REDEALS = 40
  }
}
