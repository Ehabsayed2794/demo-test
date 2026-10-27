package com.estemshan.game.ui.onlinematch

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.DECK_SUITS
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.GameType
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.KozCharge
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RANKS
import com.estemshan.engine.MatchMode
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.Dealer
import com.estemshan.engine.emit
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.initTable
import com.estemshan.engine.nextSeat
import com.estemshan.engine.resolveTrick
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.services.MatchAdapter
import com.estemshan.services.FirestoreError
import com.estemshan.services.Heartbeat
import com.estemshan.services.effectiveDashCallCount
import com.estemshan.services.generalPassMultiplier
import com.estemshan.services.normaliseDashCallEntry
import com.estemshan.services.MatchListenerFactory
import com.estemshan.services.MatchListenerRegistration
import com.estemshan.services.PlayerPort
import com.estemshan.services.session.GameSessionBridge
import com.estemshan.services.model.AdvanceResult
import com.estemshan.services.model.BiddingActionInput
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.CardLogEntry
import com.estemshan.services.model.DealResult
import com.estemshan.services.model.EndMatchResult
import com.estemshan.services.model.ExtendResult
import com.estemshan.services.model.GameState
import com.estemshan.services.model.HandDoc
import com.estemshan.services.model.LeaveResult
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.StoredCard
import com.estemshan.services.model.SubmitBidResult
import com.estemshan.services.model.SubmitBiddingActionResult
import com.estemshan.services.model.SubmitCardResult
import com.estemshan.services.session.MatchStore
import com.estemshan.services.DecisionTimeoutPort
import com.estemshan.services.RpMovement
import com.estemshan.services.SeatReclaimPort
import com.estemshan.services.SettlementPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * S17 — OnlineMatchViewModel's three trust properties, each pinned against
 * the REAL MatchAdapter and the REAL engine functions through a hermetic
 * stand-in for Firestore. No emulator, no device, no network, no sleeps.
 *
 * The seam that makes this possible is [MatchStore]: the view model never
 * sees a FirebaseFirestore, so a document plus a deal is the whole world.
 * [BroadcastFactory] is the one listener-per-matchId contract every real
 * delivery satisfies — one document, handed to every subscriber — and the
 * view model's own pipeline (inbox → serialized handleSnapshot) is driven
 * the same way a snapshot thread drives it, through the adapter first.
 *
 * THE THREE PROPERTIES:
 *
 *  1. Idempotency — the same snapshot applied twice moves nothing. Every
 *     delivery runs the interpreters TWICE by design (the adapter's own
 *     pre-pass, then the view model's re-drive), so this is not a
 *     theoretical concern; it is the property that makes the architecture
 *     safe. Pinned at both levels: the adapter's content dedupe, and the
 *     interpreter gates that make a redundant pass a no-op.
 *
 *  2. Cold-start reconnect resumes the EXACT trick — a view model that has
 *     never seen the match lands mid-round and converges to the same
 *     trickNo, tricksWon, turn and phase a client that played the whole
 *     round holds. This is the P1-3 reload order (discard → reseed →
 *     re-init → replay from index 0), driven through the view model's own
 *     pipeline rather than asserted against it.
 *
 *  3. Two view models converge — two seats, one document, one broadcast:
 *     after every delivery both hold the same auction, the same table and
 *     the same turn, including across a card one of them played.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMatchViewModelTest {

  private val seatUids: Map<String, String> =
    linkedMapOf("p1" to "uid-a", "p2" to "uid-b", "p3" to "uid-c", "p4" to "uid-d")
  private val seats: List<String> = seatUids.keys.toList()

  private lateinit var factory: BroadcastFactory
  private lateinit var store: FakeMatchStore
  private lateinit var players: FakePlayers

  /** The adapter each view model owns, keyed by the uid it plays — the
   *  view model keeps its adapter private, but the registry gates these
   *  tests assert on are the adapter's own diagnostics. */
  private val adapters = mutableMapOf<String, MatchAdapter>()

  /** The session each view model drives, keyed by uid — S69 asserts the
   *  document-seeded type/mode/cap through these. */
  private val sessions = mutableMapOf<String, GameSession>()

  /**
   * One seat's whole online stack: its OWN [GameSession] and its OWN
   * [MatchAdapter] (the per-process engines and registries are per
   * device), sharing the ONE [factory] and ONE [store] that stand in for
   * the single Firestore document every seat reads.
   */
  private fun makeVm(
    uid: String,
    worker: CoroutineDispatcher,
    clock: () -> Long = { System.currentTimeMillis() },
    heartbeat: Heartbeat = Heartbeat.None,
    timeouts: DecisionTimeoutPort = DecisionTimeoutPortStub,
    settlements: SettlementPort = SettlementPortStub,
  ): OnlineMatchViewModel {
    val session = GameSession()
    sessions[uid] = session
    // The production wiring: the adapter replays into a bridge around the
    // SAME GameSession the view model drives — a second instance would never
    // agree with the VM's own reads (OnlineServices.session vs sessionPort).
    val adapter = MatchAdapter(GameSessionBridge(session), factory)
    adapters[uid] = adapter
    return OnlineMatchViewModel(
      session = session,
      adapter = adapter,
      store = store,
      players = players,
      uid = { uid },
      worker = worker,
      clock = clock,
      heartbeat = heartbeat,
      timeouts = timeouts,
      // S73: every seam of this constructor must be passed, not defaulted —
      // the defaults read OnlineServices, whose lazy getters touch Firebase
      // before any Context exists on a JVM test thread.
      reclaims = SeatReclaimPortStub,
      settlements = settlements,
    )
  }

  /** A fast-round match document (round 14+, auction open, not dealt). */
  private fun fastRoundDoc(): MatchDoc = MatchDoc(
    roomId = "room-1",
    players = seatUids.values.toList(),
    status = MatchDoc.STATUS_STARTING,
    currentRound = 14,
    maxRounds = 18,
    extendedRounds = emptyList(),
    dealer = seatUids.getValue("p1"),
    turn = null,
    seats = seatUids,
    version = 1,
    biddingOpen = true,
    bids = emptyMap(),
    lastBidSeat = null,
    cardLog = emptyList(),
    lastCardSeat = null,
    cardPhase = null,
    biddingLog = emptyList(),
    gameState = GameState.NOT_DEALT,
  )

  // ── T-09 documents, derived from the real engine ───────────────────

  /** One real fast-round emit chain from the dealer, asserting nothing was
   *  rejected — the fixtures below are legal engine histories, not invented
   *  documents. */
  private fun driveFast(vararg intents: BiddingIntent): BiddingState {
    var state = initFastRound(14, seats.first(), seats)
    for (intent in intents) {
      state = when (val result = emit(state, intent)) {
        is EmitResult.Applied -> result.state
        is EmitResult.Completed -> result.state
        is EmitResult.Rejected -> error("fast fixture rejected $intent: ${result.reason}")
        is EmitResult.GeneralPass -> error("unexpected general pass in a fast fixture")
      }
    }
    return state
  }

  private fun fastDocFrom(
    state: BiddingState,
    version: Int,
    log: List<BiddingLogEntry>,
    cycle: Int,
    lastBidSeat: String?,
  ): MatchDoc = fastRoundDoc().copy(
    version = version,
    bids = state.bids.mapValues { it.value.amount },
    lastBidSeat = lastBidSeat,
    biddingCycle = cycle,
    biddingLog = log,
    gameState = GameState(initialized = true, dealtRound = 14),
  )

  /** The opening cycle is complete: all four declared 8/9/10/7, so the
   *  engine's own next act is the Super Call's CONFIRM on p3 (10). */
  private fun fastOpeningCycleDoc(): MatchDoc {
    val state = driveFast(
      BiddingIntent.FinalEstimate("p1", 8),
      BiddingIntent.FinalEstimate("p2", 9),
      BiddingIntent.FinalEstimate("p3", 10),
      BiddingIntent.FinalEstimate("p4", 7),
    )
    assertEquals("the 8+ declarations opened the call", BiddingPhase.CONFIRM, state.subPhase)
    return fastDocFrom(state, version = 40, log = emptyList(), cycle = 0, lastBidSeat = "p4")
  }

  /** Live cycle 1: p3 confirmed 10 CLUBS (wiping the opening declarations);
   *  p4 and p1 re-declared, so the next seat is p2. */
  private fun fastCycleOneState(): BiddingState = driveFast(
    BiddingIntent.FinalEstimate("p1", 8),
    BiddingIntent.FinalEstimate("p2", 9),
    BiddingIntent.FinalEstimate("p3", 10),
    BiddingIntent.FinalEstimate("p4", 7),
    BiddingIntent.ConfirmCall("p3", 10, Suit.CLUBS),
    BiddingIntent.FinalEstimate("p4", 6),
    BiddingIntent.FinalEstimate("p1", 4),
  )

  private fun fastSuperCycleOneDoc(): MatchDoc {
    val state = fastCycleOneState()
    return fastDocFrom(
      state, version = 41, cycle = 1, lastBidSeat = "p1",
      log = listOf(
        BiddingLogEntry(
          seatId = "p3",
          actionType = BiddingLogEntry.ACTION_CONFIRM_CALL,
          tricks = 10,
          suit = Suit.CLUBS.name,
          round = 14,
        ),
      ),
    )
  }

  /** Live cycle 2: p2 re-declared 11 over the confirmed 10, re-opened the
   *  call on itself, confirmed a NEW trump (HEARTS); p3 re-declared. */
  private fun fastCycleTwoState(): BiddingState {
    var state = fastCycleOneState()
    state = when (val r = emit(state, BiddingIntent.FinalEstimate("p2", 11))) {
      is EmitResult.Applied -> r.state
      else -> error("p2's higher declaration must re-open the call: $r")
    }
    state = when (val r = emit(state, BiddingIntent.ConfirmCall("p2", 11, Suit.HEARTS))) {
      is EmitResult.Applied -> r.state
      else -> error("the higher Super Call must confirm: $r")
    }
    return when (val r = emit(state, BiddingIntent.FinalEstimate("p3", 5))) {
      is EmitResult.Applied -> r.state
      else -> error("p3 must re-declare under the new trump: $r")
    }
  }

  private fun fastSuperCycleTwoDoc(): MatchDoc = fastDocFrom(
    fastCycleTwoState(), version = 42, cycle = 2, lastBidSeat = "p3",
    log = listOf(
      BiddingLogEntry(
        seatId = "p3",
        actionType = BiddingLogEntry.ACTION_CONFIRM_CALL,
        tricks = 10,
        suit = Suit.CLUBS.name,
        round = 14,
      ),
      BiddingLogEntry(
        seatId = "p2",
        actionType = BiddingLogEntry.ACTION_CONFIRM_CALL,
        tricks = 11,
        suit = Suit.HEARTS.name,
        round = 14,
      ),
    ),
  )

  // ═══════════════════════════════════════════════════════════════════
  // S69 (E7) — the document seeds the session's type, mode and ceiling
  // ═══════════════════════════════════════════════════════════════════

  /**
   * A MINI document at round 6 seeds maxRounds 10 into the session and
   * resolves the auction to the FAST path — under FULL's window round 6
   * would open a normal Dash auction, so ESTIMATES proves the doc's type
   * reached the session through initAuction.
   */
  @Test
  fun miniDocSeedsMaxRounds10AndFastPathAtRound6() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc().copy(
        currentRound = 6,
        maxRounds = 10,
        gameType = GameType.MINI,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-mini")
      advanceUntilIdle()

      broadcast("m-mini")
      val session = sessions.getValue("uid-a")
      assertEquals(GameType.MINI, session.getGameType())
      assertEquals("a MINI doc seeds maxRounds 10", 10, session.getRound().maxRounds)
      assertEquals("MINI round 6 resolves to the fast path",
        BiddingPhase.ESTIMATES, biddingOf(vm.state.value).subPhase)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * A CLASSIC document seeds the session's scoring mode (cap ×2) — the two
   * facts scoreAndAdvance reads into RoundScoreInput (classic = mode is
   * CLASSIC, escalationCap straight from the session).
   */
  @Test
  fun classicDocSeedsSessionScoringModeAndCap() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc().copy(scoringMode = ScoringMode.CLASSIC)
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-classic")
      advanceUntilIdle()

      broadcast("m-classic")
      val session = sessions.getValue("uid-a")
      assertEquals(ScoringMode.CLASSIC, session.getScoringMode())
      assertEquals("CLASSIC caps escalation at 2", 2, session.escalationCap)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * S1: the document's scoring mode must reach the auction's own engine
   * state — not just the session envelope — because the engine derives a
   * general pass's next-round multiplier from the state's mode (CLASSIC
   * never escalates).
   */
  @Test
  fun theDocScoringModeSeedsTheBiddingEngineState() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc().copy(
        currentRound = 1,
        scoringMode = ScoringMode.CLASSIC,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-classic-state")
      advanceUntilIdle()

      broadcast("m-classic-state")
      assertEquals(
        "the auction state must carry the doc's scoring mode",
        ScoringMode.CLASSIC,
        biddingOf(vm.state.value).scoringMode,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * O-19 (Option B, 2026-10-24) — the online General Pass is now a persisted
   * advance. A delivery whose biddingLog is the canonical all-pass window
   * (N declining dash calls + N auction passes, no cards) must:
   *
   *  - arm the session round's multiplier for the round the document is
   *    about to open (the pass hint), and
   *  - route exactly ONE write to [MatchStore.advanceAfterGeneralPass],
   *    which owns the ladder — the client never computes the value itself.
   *
   * The local round number stays document-pinned: this delivery's document
   * still says round 1, so the session does not advance unilaterally; the
   * next document (round 2, multiplier 2) reconciles through [alignToDoc].
   */
  @Test
  fun aGeneralPassDeliveryArmsTheMultiplierAndAdvancesTheDocumentOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      val passLog = buildList {
        seats.forEach {
          add(BiddingLogEntry(it, BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false, round = 1))
        }
        seats.forEach {
          add(BiddingLogEntry(it, BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 1))
        }
      }
      store.doc = fastRoundDoc().copy(
        currentRound = 1,
        biddingLog = passLog,
        version = 3,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-pass")
      advanceUntilIdle()

      broadcast("m-pass")
      val session = sessions.getValue("uid-a")
      assertEquals(
        "the session must not advance the round ahead of the document",
        1,
        session.getRound().number,
      )
      assertEquals(
        "the pass did not arm the session round's multiplier",
        2,
        session.getRound().multiplier,
      )
      assertEquals(
        "the cancelled auction state keeps the cancelled round's number",
        1,
        biddingOf(vm.state.value).round,
      )
      assertNull(
        "the cancelled round waits on nobody",
        biddingOf(vm.state.value).waitingFor,
      )
      assertEquals(
        "the pass must be advanced exactly once despite re-deliveries",
        1,
        store.passAdvanceCalls.get(),
      )
      assertEquals(
        "the document owns the advance",
        2,
        store.doc.currentRound,
      )
      assertEquals(
        "the document carries the laddered multiplier",
        2,
        store.doc.currentMultiplier,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * O-19 gate 7 — a General Pass after a Sa'ayda continues from the value
   * the Sa'ayda armed in the document, not from ×1. The document opens the
   * round at ×2 (what a Sa'ayda at ×1 writes), so the cancelled round's
   * ladder step is ×2 → ×4: both the engine's replay and the store's own
   * transaction read the persisted 2, never a defaulted 1.
   */
  @Test
  fun aPassAfterASaaydaContinuesFromThePersistedMultiplier() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      val passLog = buildList {
        seats.forEach {
          add(BiddingLogEntry(it, BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false, round = 1))
        }
        seats.forEach {
          add(BiddingLogEntry(it, BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 1))
        }
      }
      store.doc = fastRoundDoc().copy(
        currentRound = 1,
        currentMultiplier = 2,
        biddingLog = passLog,
        version = 3,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-pass-after-saayda")
      advanceUntilIdle()

      broadcast("m-pass-after-saayda")

      assertEquals(1, store.passAdvanceCalls.get())
      assertEquals(2, store.doc.currentRound)
      assertEquals(
        "the pass must ladder from the Sa'ayda's persisted ×2, not from ×1",
        4,
        store.doc.currentMultiplier,
      )
      assertEquals(
        "the next auction the client plays is armed at the laddered value",
        4,
        sessions.getValue("uid-a").getRound().multiplier,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * O-19 / A8 F1 + the F1 owner ruling (2026-10-24): the refusal stands for
   * what the widened proof still refuses, and says why.
   *
   * A round closed with no cards is a General Pass only if its window is
   * the canonical all-pass shape — which since the ruling INCLUDES one or
   * two declared Dash Calls. What still refuses is the strict two-Dash
   * maximum: here THREE seats declared, a document the normalising write
   * boundary can never produce. The client leaves the document exactly
   * where it found it AND publishes the NAMED reason
   * (TOO_MANY_DASH_CALLS) on the state it shows — never a silent clamp.
   */
  @Test
  fun aThreeDashWindowRefusesTheRoundAndNamesTheReason() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      val threeDashWindow = buildList {
        seats.take(3).forEach {
          add(
            BiddingLogEntry(
              it, BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 1,
            ),
          )
        }
        add(
          BiddingLogEntry(
            seats[3], BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false, round = 1,
          ),
        )
        add(BiddingLogEntry(seats[3], BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 1))
      }
      assertEquals("4 dash decisions + the one remaining seat's pass", 5, threeDashWindow.size)
      // Dealt, closed, no cards: the auction is over and nothing was played.
      store.doc = fastRoundDoc().copy(
        currentRound = 1,
        currentMultiplier = 1,
        biddingOpen = false,
        biddingLog = threeDashWindow,
        gameState = GameState(initialized = true, dealtRound = 1),
        version = 3,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-three-dash-pass")
      advanceUntilIdle()

      broadcast("m-three-dash-pass")

      // Half 1 — nothing advanced: not the document, not the store, not here.
      assertEquals("the refusal must not reach the store at all", 0, store.passAdvanceCalls.get())
      assertEquals("the stalled round must stay on the document", 1, store.doc.currentRound)
      val session = sessions.getValue("uid-a")
      assertEquals("and this client's session must not advance either", 1, session.getRound().number)

      // Half 2 — the user can see why: the published state carries the
      // named reason, not a bare stall.
      val reason = rejectionOf(vm.state.value)
      assertTrue(
        "the stall must publish a reason (state was ${vm.state.value::class.simpleName})",
        reason != null,
      )
      assertTrue(
        "the reason must name TOO_MANY_DASH_CALLS, was: $reason",
        reason!!.contains(Reasons.TOO_MANY_DASH_CALLS),
      )

      // Re-delivery of the same document neither advances nor goes quiet.
      broadcast("m-three-dash-pass")
      assertEquals("a re-delivery must not clamp three dashes into a pass", 0, store.passAdvanceCalls.get())
      assertEquals(1, store.doc.currentRound)
      assertEquals(reason, rejectionOf(vm.state.value))
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * F1 (owner ruling 2026-10-24, Option A): the mixed window is a FIRST-CLASS
   * General Pass. A delivery whose round holds TWO declared Dash Calls and a
   * pass from each remaining seat (2N-k = 6 entries, no cards) arms the
   * next multiplier and routes exactly ONE advance write — the same contract
   * the all-decline window satisfies above.
   */
  @Test
  fun aMixedTwoDashGeneralPassDeliveryArmsTheMultiplierAndAdvances() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      val dashers = seats.take(2).toSet()
      val mixedLog = buildList {
        seats.forEach {
          add(
            BiddingLogEntry(
              it, BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = it in dashers, round = 1,
            ),
          )
        }
        seats.filter { it !in dashers }.forEach {
          add(BiddingLogEntry(it, BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 1))
        }
      }
      assertEquals(6, mixedLog.size)
      store.doc = fastRoundDoc().copy(
        currentRound = 1,
        biddingLog = mixedLog,
        version = 3,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-mixed-pass")
      advanceUntilIdle()

      broadcast("m-mixed-pass")
      val session = sessions.getValue("uid-a")
      assertEquals(
        "the mixed pass must arm the session round's multiplier",
        2,
        session.getRound().multiplier,
      )
      assertNull(
        "the cancelled round waits on nobody",
        biddingOf(vm.state.value).waitingFor,
      )
      assertEquals(
        "the mixed pass must be advanced exactly once despite re-deliveries",
        1,
        store.passAdvanceCalls.get(),
      )
      assertEquals(
        "the document owns the advance",
        2,
        store.doc.currentRound,
      )
      assertEquals(
        "the document carries the laddered multiplier",
        2,
        store.doc.currentMultiplier,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * F1: the test fake's own write boundary mirrors the service's — a
   * declared Dash attempt that arrives once two are effective persists as a
   * decline, so windows built through the fake are the windows Firestore
   * would really hold.
   */
  @Test
  fun theStoreFakeNormalisesAThirdDashAttemptLikeTheService() = runTest {
    store = FakeMatchStore(Dealer.dealHands(11))
    store.doc = fastRoundDoc().copy(
      // fastRoundDoc sits on round 14; the seeded effective Dashes and the
      // attempt below are all tagged with the round being played.
      currentRound = 1,
      biddingLog = listOf(
        BiddingLogEntry("p1", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 1),
        BiddingLogEntry("p2", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 1),
      ),
    )
    store.submitBiddingAction(
      "m-normalise",
      BiddingActionInput(actionType = BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true),
    )
    val persisted = store.doc.biddingLog.last()
    assertEquals(
      "the over-cap attempt must persist as a decline at the fake boundary too",
      false,
      persisted.declaredDashCall,
    )
    assertEquals(BiddingLogEntry.ACTION_DASH_CALL, persisted.actionType)
  }

  /**
   * The other half of A8 F1: the new message must not misfire. An auction
   * still in flight, and a closed auction that DOES hold a contract, are both
   * ordinary documents on their way to trick play — the round is not a pass
   * candidate at all, so reporting an unprovable pass would be a false alarm
   * that pushes the table away from a round it can still play.
   */
  @Test
  fun anOrdinaryAuctionIsNotReportedAsAnUnprovablePass() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      val dashBlock = buildList {
        add(
          BiddingLogEntry(
            seats[0], BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 1,
          ),
        )
        seats.drop(1).forEach {
          add(
            BiddingLogEntry(
              it, BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false, round = 1,
            ),
          )
        }
      }
      store.doc = fastRoundDoc().copy(
        currentRound = 1,
        biddingOpen = true,
        biddingLog = dashBlock,
        gameState = GameState(initialized = true, dealtRound = 1),
        version = 3,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-not-a-pass")
      advanceUntilIdle()

      broadcast("m-not-a-pass")
      assertEquals(0, store.passAdvanceCalls.get())
      assertNull(
        "an auction that is still open is not a closed pass round",
        rejectionOf(vm.state.value),
      )

      // Same round, bidding closed, but a seat holds a bid: the contract is on
      // record and the round will be played, not passed.
      store.doc = store.doc.copy(
        biddingOpen = false,
        biddingLog = dashBlock + listOf(
          BiddingLogEntry(
            seats[1], BiddingLogEntry.ACTION_AUCTION_BID, isPass = false,
            tricks = 13, suit = Suit.SPADES.name, round = 1,
          ),
          BiddingLogEntry(seats[2], BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 1),
          BiddingLogEntry(seats[3], BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 1),
        ),
        version = 4,
      )

      broadcast("m-not-a-pass")
      assertEquals(0, store.passAdvanceCalls.get())
      assertNull(
        "a closed auction with a contract on record is not an unprovable pass",
        rejectionOf(vm.state.value),
      )
      assertEquals("and the round stays where the document has it", 1, store.doc.currentRound)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * O-19 gate 4 (a) — a cold start arms the auction at the document's
   * persisted multiplier. Before Option B this client started at x1 and a
   * mid-escalation round scored at the wrong value.
   */
  @Test
  fun aColdStartFromADocumentAtX4StartsTheAuctionAtX4() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc().copy(currentMultiplier = 4)
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-x4")
      advanceUntilIdle()

      broadcast("m-x4")
      val session = sessions.getValue("uid-a")
      assertEquals("the doc's round must seed the session", 14, session.getRound().number)
      assertEquals("the doc's multiplier must seed the session", 4, session.getRound().multiplier)
      val auction = biddingOf(vm.state.value)
      assertEquals(14, auction.round)
      assertEquals(
        "the auction engine must be armed at the doc's multiplier",
        4,
        auction.roundMultiplier,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * O-19 gate 4 (b) — a client that missed the pass entirely reconciles the
   * round AND the multiplier from the next document, and keeps the totals it
   * already accumulated. (A NEW session cannot restore past totals — there
   * is no roundArchive read on MatchStore; this pins the reconnect case the
   * document can heal.)
   */
  @Test
  fun aMissedGeneralPassIsReconciledFromTheDocumentAndKeepsTheTotals() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc().copy(currentRound = 1, version = 1)
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-missed-pass")
      advanceUntilIdle()
      broadcast("m-missed-pass")

      val session = sessions.getValue("uid-a")
      val totals = seats.associateWith { 7 }
      session.setMatchScores(totals)

      // What the document looks like to a client that never saw the pass:
      // the next round, already opened at the laddered multiplier.
      store.doc = fastRoundDoc().copy(
        currentRound = 2,
        currentMultiplier = 2,
        version = 4,
        gameState = GameState(initialized = true, dealtRound = 2),
      )
      broadcast("m-missed-pass")

      assertEquals("the session followed the document's round", 2, session.getRound().number)
      assertEquals("the missed pass's multiplier was reconciled", 2, session.getRound().multiplier)
      assertEquals(
        "the next auction is armed at the reconciled multiplier",
        2,
        biddingOf(vm.state.value).roundMultiplier,
      )
      assertEquals(
        "the totals already banked must survive the advance",
        totals,
        session.getMatchScores(),
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * O-19 gate 4 (c) — a pass at the planned final round completes the match
   * through the pass path. It must never advance `currentRound` to
   * maxRounds + 1 with no result.
   */
  @Test
  fun aFinalRoundGeneralPassCompletesTheMatch() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      val passLog = buildList {
        seats.forEach {
          add(BiddingLogEntry(it, BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false, round = 18))
        }
        seats.forEach {
          add(BiddingLogEntry(it, BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 18))
        }
      }
      store.doc = fastRoundDoc().copy(
        currentRound = 18,
        maxRounds = 18,
        currentMultiplier = 4,
        biddingLog = passLog,
        version = 5,
      )
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-final-pass")
      advanceUntilIdle()

      broadcast("m-final-pass")
      assertEquals("the final pass must be advanced exactly once", 1, store.passAdvanceCalls.get())
      assertTrue("the final-round pass must complete the match", store.doc.isComplete)
      assertEquals("completion must stay on the final round", 18, store.doc.currentRound)
      assertEquals(18, store.doc.completedRound)

      broadcast("m-final-pass")
      assertTrue(
        "the terminal document must publish the result",
        vm.state.value is MatchUiState.MatchComplete,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** Hand the store's current document to every subscriber, then let the
   *  view models' pipelines drain — what a Firestore tick followed by the
   *  collector looks like from the outside. */
  /** Needs the [TestScope] receiver for [advanceUntilIdle], like every
   *  call site it is invoked from. */
  private suspend fun TestScope.broadcast(matchId: String) {
    factory.deliver(matchId, store.snapshot())
    advanceUntilIdle()
  }

  /**
   * Deliver a document WITHOUT advancing the scheduler's clock — the
   * S19 staleness tests need time to pass *between* deliveries, and
   * [advanceUntilIdle] would run the pending staleness alarm forward to
   * the threshold the moment a document lands.
   */
  private fun TestScope.deliverNow(matchId: String) {
    factory.deliver(matchId, store.snapshot())
    runCurrent()
  }

  private fun biddingOf(state: MatchUiState): BiddingState =
    (state as? MatchUiState.Bidding)?.state
      ?: error("expected Bidding, was ${state::class.simpleName}")

  private fun tableOf(state: MatchUiState): TableState =
    (state as? MatchUiState.Table)?.state
      ?: error("expected Table, was ${state::class.simpleName}")

  /** The inline rejection on whichever live state the VM published — the
   *  only surface a player has for "the round stalled and here is why". */
  private fun rejectionOf(state: MatchUiState): String? = when (state) {
    is MatchUiState.Bidding -> state.rejection
    is MatchUiState.Table -> state.rejection
    else -> null
  }

  // ═══════════════════════════════════════════════════════════════════
  // 1. IDEMPOTENCY — the same snapshot twice applies nothing twice
  // ═══════════════════════════════════════════════════════════════════

  /**
   * Every delivery runs each interpreter twice — once in the adapter's own
   * pre-pass, once in the view model's re-drive — so a redundant second
   * application would corrupt the auction on every single snapshot. This
   * pins the guard at both levels: the adapter's identical-content dedupe,
   * and the version/echo gates that make a re-driven duplicate a no-op.
   */
  @Test
  fun sameSnapshotTwice_appliesNothingTwice() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val vm = makeVm("uid-a", Dispatchers.Main)
      vm.start("m-idem")
      advanceUntilIdle()

      // The deal arrives with the first snapshot; the auction opens at the
      // dealer, waiting on nobody's estimate yet.
      broadcast("m-idem")
      assertEquals(
        "one real deal landed; bindSeat's redundant re-delivery no-oped it",
        1, store.dealCalls.get(),
      )
      val opening = biddingOf(vm.state.value)
      assertEquals("a fast round opens straight into estimates",
        BiddingPhase.ESTIMATES, opening.subPhase,
      )
      assertEquals("the dealer estimates first", "p1", opening.waitingFor)

      // One estimate, submitted through the view model's own channel: the
      // store moves the document, the broadcast delivers it, and the
      // interpreter pair must land exactly ONE bid.
      vm.submitBidding(BiddingIntent.FinalEstimate("p1", 2))
      advanceUntilIdle()
      broadcast("m-idem")

      val afterFirst = biddingOf(vm.state.value)
      assertEquals("exactly one estimate applied", 1, afterFirst.bids.size)
      assertEquals("p1 holds the estimate", 2, afterFirst.bids.getValue("p1").amount)
      assertEquals("the auction moved on to p2", "p2", afterFirst.waitingFor)
      assertEquals("one bid write reached the store", 1, store.bidCalls.get())

      // Level 1 — the adapter's own dedupe: an identical re-delivery is
      // never even published, so the view model's inbox stays empty.
      broadcast("m-idem")
      val afterRedelivery = biddingOf(vm.state.value)
      assertEquals("an identical re-delivery added no estimate", 1, afterRedelivery.bids.size)
      assertEquals("the waiting seat did not move", "p2", afterRedelivery.waitingFor)

      // Level 2 — the interpreter gates: a NEW version carrying the SAME
      // bid content IS published (the document differs, so the dedupe lets
      // it through), and the bid interpreter must still refuse to re-apply.
      store.bumpVersion()
      broadcast("m-idem")
      val afterBump = biddingOf(vm.state.value)
      assertEquals("a same-content new version added no estimate", 1, afterBump.bids.size)
      assertEquals("the waiting seat still did not move", "p2", afterBump.waitingFor)
      assertEquals("the bid registry advanced to the new version",
        3, adapters.getValue("uid-a").lastAppliedBidVersion("m-idem"),
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // 2. COLD START — resume the exact trick
  // ═══════════════════════════════════════════════════════════════════

  /**
   * A client that has never seen this match arrives mid-round: the document
   * carries a closed auction and a half-played round. The view model must
   * rebuild the engines from the document alone and land on the SAME trick
   * a client that played the round holds — same trick number, same counted
   * tricks, same seat to act, same phase.
   *
   * The fixture is built by driving the REAL engine forward and recording
   * what it did, so the document is a legal match rather than an invented
   * one; and hands are rigged so the caller holds every trump and therefore
   * leads and wins every trick, making the whole sequence deterministic
   * (the exact case that diverged in issue #16 on ~1/4 of deals).
   */
  @Test
  fun coldStart_resumesTheExactTrick() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      val fixture = roundOneFixture(tricks = 6)
      store = FakeMatchStore(fixture.hands)
      store.doc = fixture.doc
      // A NON-caller seat: the local table holds only this seat's hand, so
      // the caller's cards must arrive through the replay's throwaway
      // seeding — the path a real opponent's play takes.
      val vm = makeVm(seatUids.getValue("p2"), Dispatchers.Main)

      vm.start("m-cold")
      broadcast("m-cold")

      val published = vm.state.value
      assertTrue("the cold start reached trick play, not a stuck auction",
        published is MatchUiState.Table,
      )

      val resumed = tableOf(published)
      assertEquals("resumes the exact trick", fixture.table.trickNo, resumed.trickNo)
      assertEquals("counts the same tricks", fixture.table.tricksWon, resumed.tricksWon)
      assertEquals("the same seat holds the next turn", fixture.table.turn, resumed.turn)
      assertEquals("the phase agrees", fixture.table.phase, resumed.phase)
      assertEquals("the table is the replayed round",
        fixture.table.cfg.round, resumed.cfg.round,
      )
      assertEquals("the estimates the auction locked are the document's",
        fixture.table.cfg.estimates, resumed.cfg.estimates,
      )
      // The registry the view model's own resolve() re-enters through agrees
      // with the replay's progress, so a repeat resolve() is a no-op here.
      assertEquals("the resolve registry stopped at the replayed tricks",
        fixture.table.trickNo - 1,
        adapters.getValue(seatUids.getValue("p2")).lastResolvedTrickNo("m-cold"),
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ── 2b. THE COLD-START GAP — fail-open, never drop the player ────────

  /**
   * S18 — the relaunch blip. The FIRST Firestore callback a cold-started
   * client receives is a transient error (network unavailable at launch)
   * with no document. The adapter classifies it RETRYABLE and schedules a
   * reconnect with backoff, so the real document is still coming; publishing
   * Failed now would land the player on an error page before it arrives and
   * the match would be lost to a blip. The gap is covered by Connecting
   * instead, and once the reconnect delivers, the pipeline converges to the
   * exact trick a live seat holds.
   */
  @Test
  fun coldStart_transientFirstError_holdsTheGapThenConvergesTheExactTrick() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      val fixture = roundOneFixture(tricks = 6)
      store = FakeMatchStore(fixture.hands)
      store.doc = fixture.doc
      // A NON-caller seat, exactly like the plain cold start: the local table
      // holds only this seat's hand, so the caller's cards arrive through the
      // replay's throwaway seeding.
      val vm = makeVm(seatUids.getValue("p2"), Dispatchers.Main)

      vm.start("m-gap")
      // The launch blip: no document, a retryable error. The adapter queues a
      // reconnect, and the view model must cover the gap rather than fail.
      factory.fail("m-gap", FirestoreError("unavailable", "transient"))
      advanceUntilIdle()

      assertTrue("the cold-start gap is covered by Connecting, not Failed",
        vm.state.value is MatchUiState.Connecting,
      )
      // The gap is signalled by Connecting itself, NOT by the warm fail-open
      // reconnecting flag — there is no last-known-good document to play, so
      // that flag's documented meaning is left exactly as it was.
      assertFalse("the warm fail-open flag stays down with no document to play",
        vm.reconnecting.value,
      )

      // The scheduled reconnect delivers the real document and the pipeline
      // converges to the exact trick a live seat holds.
      broadcast("m-gap")

      val published = vm.state.value
      assertTrue("the reconnect reached trick play, not a stuck auction",
        published is MatchUiState.Table,
      )
      val resumed = tableOf(published)
      assertEquals("resumes the exact trick", fixture.table.trickNo, resumed.trickNo)
      assertEquals("counts the same tricks", fixture.table.tricksWon, resumed.tricksWon)
      assertEquals("the same seat holds the next turn", fixture.table.turn, resumed.turn)
      assertEquals("the phase agrees", fixture.table.phase, resumed.phase)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * S18 — the honest half of fail-open. A terminal error at cold start
   * (permission-denied, a genuinely unreadable match) has no reconnect
   * coming, so the player is told plainly instead of being parked on an
   * overlay that would never clear. Fail-open must not become fail-silent.
   */
  @Test
  fun coldStart_terminalFirstError_publishesFailed() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val vm = makeVm(seatUids.getValue("p2"), Dispatchers.Main)

      vm.start("m-terminal")
      factory.fail("m-terminal", FirestoreError("permission-denied", "not allowed"))
      advanceUntilIdle()

      val published = vm.state.value
      assertTrue("a terminal cold-start error fails honestly, not held",
        published is MatchUiState.Failed,
      )
      assertEquals("the failure carries the error's own message",
        "not allowed", (published as MatchUiState.Failed).message,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * S18 — the gap is only for RETRYABLE errors. A confirmed deletion (no
   * error, no document) is the match being gone, and stays NotInMatch:
   * unchanged behavior, pinned so the fail-open cannot swallow it.
   */
  @Test
  fun coldStart_deletedDocument_publishesNotInMatch() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val vm = makeVm(seatUids.getValue("p2"), Dispatchers.Main)

      vm.start("m-deleted")
      // A deletion, not a failure: the listener delivers null with no error.
      factory.deliver("m-deleted", null)
      advanceUntilIdle()

      assertTrue("a deleted match is NotInMatch, not a held gap",
        vm.state.value is MatchUiState.NotInMatch,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // 3. CONVERGENCE — two seats, one document, one truth
  // ═══════════════════════════════════════════════════════════════════

  /**
   * Two devices, two independent engines and adapters, one document. After
   * every delivery both must hold the same auction — and once the auction
   * closes, the same table — including across a card one of them played
   * (the submitter sees its own play echoed back; the other sees an
   * observed card seeded into a hand it does not hold).
   */
  @Test
  fun twoViewModels_convergeOnRemoteBidsAndCards() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(23))
      store.doc = fastRoundDoc()
      val vmA = makeVm(seatUids.getValue("p1"), Dispatchers.Main)
      val vmB = makeVm(seatUids.getValue("p2"), Dispatchers.Main)

      vmA.start("m-conv")
      vmB.start("m-conv")
      advanceUntilIdle()
      broadcast("m-conv")

      assertEquals("two live subscribers, one per device",
        2, factory.subscribers("m-conv"),
      )
      assertEquals("both opened the fast-round auction",
        BiddingPhase.ESTIMATES, biddingOf(vmA.state.value).subPhase,
      )
      assertEquals("both opened the fast-round auction",
        BiddingPhase.ESTIMATES, biddingOf(vmB.state.value).subPhase,
      )
      assertEquals("both wait on the dealer", "p1", biddingOf(vmA.state.value).waitingFor)
      assertEquals("both wait on the dealer", "p1", biddingOf(vmB.state.value).waitingFor)

      // p1's estimate goes through the view model's own submit channel —
      // the write, then the echo, then convergence.
      vmA.submitBidding(BiddingIntent.FinalEstimate("p1", 6))
      advanceUntilIdle()
      broadcast("m-conv")
      assertBidsAgree(vmA, vmB, "after p1's estimate", setOf("p1"))

      // The other seats' estimates land as if their own devices wrote them.
      store.submitBid("m-conv", "p2", 3)
      broadcast("m-conv")
      assertBidsAgree(vmA, vmB, "after p2's estimate", setOf("p1", "p2"))

      store.submitBid("m-conv", "p3", 1)
      broadcast("m-conv")
      assertBidsAgree(vmA, vmB, "after p3's estimate", setOf("p1", "p2", "p3"))

      // The fourth estimate closes the auction: the completing emit's
      // outcome reaches both, both build the table, both publish Table.
      store.submitBid("m-conv", "p4", 2)
      broadcast("m-conv")

      assertTrue("vmA reached the table", vmA.state.value is MatchUiState.Table)
      assertTrue("vmB reached the table", vmB.state.value is MatchUiState.Table)
      val tableA = tableOf(vmA.state.value)
      val tableB = tableOf(vmB.state.value)
      assertEquals("both tables are at trick 1", 1, tableA.trickNo)
      assertEquals("both agree on the trick number", tableA.trickNo, tableB.trickNo)
      assertEquals("both agree on the leader", tableA.leaderId, tableB.leaderId)
      assertEquals("both agree on whose turn it is", tableA.turn, tableB.turn)
      assertEquals("both hold the same estimates",
        tableA.cfg.estimates, tableB.cfg.estimates,
      )
      val leader = tableA.turn ?: error("no turn to open play")

      // The leader plays, through the view model's own channel. The
      // submitter's echo and the other device's observed card must converge
      // on the same single-card trick and the same next seat.
      val card = store.peekHand(leader).first()
      vmA.playCard(card)
      advanceUntilIdle()
      broadcast("m-conv")

      val afterA = tableOf(vmA.state.value)
      val afterB = tableOf(vmB.state.value)
      assertEquals("one card write reached the store", 1, store.cardCalls.get())
      assertEquals("one card on the table", 1, afterA.plays.size)
      assertEquals("both see the same played card",
        afterA.plays.map { it.card.suit to it.card.rank.v },
        afterB.plays.map { it.card.suit to it.card.rank.v },
      )
      assertEquals("both agree on the next seat to act", afterA.turn, afterB.turn)
      assertEquals("and it is the seat after the leader",
        nextSeat(seats, leader), afterA.turn,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  private fun assertBidsAgree(
    a: OnlineMatchViewModel,
    b: OnlineMatchViewModel,
    label: String,
    expected: Set<String>,
  ) {
    val ba = biddingOf(a.state.value)
    val bb = biddingOf(b.state.value)
    assertEquals("$label: both hold the same estimates", ba.bids.keys, bb.bids.keys)
    assertEquals("$label: the estimates are the expected set", expected, ba.bids.keys)
    assertEquals("$label: both wait on the same seat", ba.waitingFor, bb.waitingFor)
  }

  // ═══════════════════════════════════════════════════════════════════
  // 4. S19 — the passive "opponent appears away" hint + the heartbeat
  // ═══════════════════════════════════════════════════════════════════

  /**
   * The hint is this client's observation of the DOCUMENT, not a presence
   * read — the frozen rules make one unreadable — so both halves are driven
   * the way they actually arrive: the clock moves without a document (an away
   * opponent sends none), and the document moves without the clock.
   *
   * A stale turn flips the published hint; a card landing clears it and
   * restarts the clock from THAT progress, so the same age afterward does not
   * flip it back. A content-free version bump is explicitly NOT activity: it
   * is a re-delivery or a rejected write, and counting it would let noise
   * quietly clear the hint.
   */
  @Test
  fun opponentAppearsAway_flipsOnStaleness_andClearsOnProgress() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      // One completed trick: the table is live, the caller (who holds every
      // trump in the rig) holds the opening lead of trick 2.
      val fixture = roundOneFixture(tricks = 1)
      store = FakeMatchStore(fixture.hands)
      store.doc = fixture.doc
      val leader = fixture.table.turn ?: error("the fixture table has no seat to act")
      // We sit anywhere but the acting seat, so the turn is an opponent's.
      val ourSeat = seats.first { it != leader }

      // The tracker's clock IS the scheduler's: the baseline, the staleness
      // alarm's delay, and the time-passage steps below all read the one
      // clock. A separate injected `var` would desync from the alarm, which
      // fires on the scheduler's clock — the hint would then measure one
      // clock and be flipped by another.
      val vm = makeVm(seatUids.getValue(ourSeat), Dispatchers.Main, clock = { currentTime })
      vm.start("m-away")
      deliverNow("m-away")

      assertEquals("mid-table, the leader to act", leader, tableOf(vm.state.value).turn)
      assertFalse("a fresh document shows no hint", vm.opponentAway.value)

      // Only time moves, past the tracker's staleness threshold. An away
      // opponent sends no document, so the flip comes from the ONE pending
      // alarm the view model arms — not from a delivery.
      advanceUntilIdle()
      assertTrue("a turn that went stale flips the hint", vm.opponentAway.value)

      // A version bump carrying no content change is not play.
      store.bumpVersion()
      deliverNow("m-away")
      assertTrue("a content-free version bump does not clear the hint",
        vm.opponentAway.value,
      )

      // A card lands on the document: progress, and a fresh baseline taken at
      // the instant the card arrived.
      store.submitCard("m-away", store.peekHand(leader)[1])
      deliverNow("m-away")
      assertFalse("fresh activity clears the hint", vm.opponentAway.value)
      // ...and a re-delivery at that same instant still reads zero elapsed
      // against the card that just landed — the clock restarted with it.
      store.bumpVersion()
      deliverNow("m-away")
      assertFalse("the restarted clock is measured from the progress",
        vm.opponentAway.value,
      )

      // S61: the timeout alarm this VM now also arms must not outlive the
      // test — see stopAndDrain.
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The hint clears the moment nobody is being waited on — a scored round's
   * window is a wait on the document, not on a player, and a finished match
   * has no wait at all.
   */
  @Test
  fun opponentAway_clearsWhenTheMatchEnds() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      val fixture = roundOneFixture(tricks = 1)
      store = FakeMatchStore(fixture.hands)
      store.doc = fixture.doc
      val leader = fixture.table.turn ?: error("the fixture table has no seat to act")
      val ourSeat = seats.first { it != leader }

      val vm = makeVm(seatUids.getValue(ourSeat), Dispatchers.Main, clock = { currentTime })
      vm.start("m-end")
      deliverNow("m-end")
      assertFalse(vm.opponentAway.value)

      advanceUntilIdle()
      assertTrue("the turn went stale", vm.opponentAway.value)

      // The match completes: the hint has nothing left to say.
      store.doc = store.doc.copy(
        status = MatchDoc.STATUS_COMPLETE,
        finalScores = seatUids.keys.associateWith { 10 },
        winnerIds = listOf("p1"),
        completedRound = 1,
        version = store.doc.version + 1,
      )
      deliverNow("m-end")
      assertFalse("a completed match clears the hint", vm.opponentAway.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The heartbeat is the one activity signal the frozen rules let a player
   * EMIT — on their OWN doc, nowhere else. It starts with the binding for the
   * signed-in player's uid and drops with it, so a player who left stops
   * writing their profile.
   */
  @Test
  fun heartbeat_runsForTheSignedInPlayer_andStopsWithTheBinding() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val heartbeat = RecordingHeartbeat()
      val vm = makeVm("uid-a", Dispatchers.Main, heartbeat = heartbeat)

      vm.start("m-beat")
      advanceUntilIdle()
      assertEquals("the cadence starts for the signed-in player's own uid",
        listOf("uid-a"), heartbeat.started,
      )

      vm.stop()
      assertEquals("leaving the match stops the cadence", 1, heartbeat.stopped)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // T-09 — the live Fast Super Call declaration cycle
  // ═══════════════════════════════════════════════════════════════════

  /**
   * A client that reloads mid-cycle has no log to replay: fast declarations
   * are never logged, and the CONFIRM cannot be emitted into a fresh engine.
   * The document is the authority (`biddingCycle` + the last CONFIRM + the
   * active `bids`), and the view model must rebuild the engine from it BEFORE
   * any incremental interpreter touches the round. The seats that declared
   * before the call (p1) and after it (p4) are both in the active cycle; the
   * engine waits on p2.
   */
  @Test
  fun resumingAfterASuperCall_restoresTheActiveCycleBeforeTheLog() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastSuperCycleOneDoc()
      val vm = makeVm("uid-b", Dispatchers.Main)

      vm.start("m-super-restore")
      advanceUntilIdle()
      broadcast("m-super-restore")

      val bidding = biddingOf(vm.state.value)
      assertNull("a proven cycle is not a desync", rejectionOf(vm.state.value))
      assertEquals(BiddingPhase.ESTIMATES, bidding.subPhase)
      assertEquals("the Super Caller", "p3", bidding.callerId)
      assertEquals("the confirmed number", 10, bidding.auctionTop)
      assertEquals("the confirmed trump", Suit.CLUBS, bidding.declaredTrump)
      assertEquals("the next seat CCW from the caller", "p2", bidding.waitingFor)
      assertEquals(
        "only the ACTIVE cycle is in the engine",
        setOf("p3", "p4", "p1"), bidding.bids.keys,
      )
      assertEquals(10, bidding.bids.getValue("p3").amount)
      assertEquals(6, bidding.bids.getValue("p4").amount)
      assertEquals(4, bidding.bids.getValue("p1").amount)
      assertTrue("the Super Call is resolved", bidding.fastSuperResolved)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * A higher re-declaration starts ANOTHER cycle: the document's last CONFIRM
   * is the authority, and the first cycle's declarations must not survive
   * anywhere in the restored engine.
   */
  @Test
  fun resumingAfterASecondSuperCall_restoresOnlyTheFinalCycle() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastSuperCycleTwoDoc()
      val vm = makeVm("uid-a", Dispatchers.Main)

      vm.start("m-super-two")
      advanceUntilIdle()
      broadcast("m-super-two")

      val bidding = biddingOf(vm.state.value)
      assertEquals(BiddingPhase.ESTIMATES, bidding.subPhase)
      assertEquals("the second Super Caller", "p2", bidding.callerId)
      assertEquals("the final call's number", 11, bidding.auctionTop)
      assertEquals("the replacement trump", Suit.HEARTS, bidding.declaredTrump)
      assertEquals("p4 re-declares next", "p4", bidding.waitingFor)
      assertEquals(
        "the first cycle was wiped, not merged",
        setOf("p2", "p3"), bidding.bids.keys,
      )
      assertEquals(5, bidding.bids.getValue("p3").amount)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The pre-persistence shape: a CONFIRM is in the log but the document
   * claims cycle 0, so the re-declarations it needs were never persisted.
   * The client must refuse by name rather than invent a cycle — and must
   * leave the fresh opening auction untouched for the document to fix.
   */
  @Test
  fun legacySuperCallDocument_isRefusedByNameAndLeavesTheRoundUnplayed() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastSuperCycleOneDoc().copy(biddingCycle = 0, version = 43)
      val vm = makeVm("uid-b", Dispatchers.Main)

      vm.start("m-super-legacy")
      advanceUntilIdle()
      broadcast("m-super-legacy")

      val rejection = rejectionOf(vm.state.value)
      assertTrue(
        "the refusal names the shape: $rejection",
        rejection != null && rejection.startsWith("FAST_CYCLE_UNPERSISTED"),
      )
      val bidding = biddingOf(vm.state.value)
      assertEquals("the round is still at its fresh opening", "p1", bidding.waitingFor)
      assertNull("no caller was invented", bidding.callerId)
      assertEquals("the fast-round opening sentinel", 13, bidding.auctionTop)
      assertTrue("no declaration was applied", bidding.bids.isEmpty())
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** A document whose CONFIRM count and cycle disagree cannot be replayed. */
  @Test
  fun inconsistentCycleLog_isRefusedAndStaysUnplayed() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      // Two cycles claimed, one CONFIRM in the log.
      store.doc = fastSuperCycleOneDoc().copy(biddingCycle = 2, version = 44)
      val vm = makeVm("uid-a", Dispatchers.Main)

      vm.start("m-super-mismatch")
      advanceUntilIdle()
      broadcast("m-super-mismatch")

      val rejection = rejectionOf(vm.state.value)
      assertTrue(
        "the refusal names the inconsistency: $rejection",
        rejection != null && rejection.startsWith("FAST_CYCLE_LOG_MISMATCH"),
      )
      assertTrue("nothing was applied", biddingOf(vm.state.value).bids.isEmpty())
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The cycle gate on the WRITE side: every intent carries the declaration
   * cycle it was decided in. An estimate from the restored cycle 1 goes to
   * submitBid with that cycle, and the live document accepts it as an
   * ordinary one-value-per-seat re-declaration.
   */
  @Test
  fun estimateFromTheRestoredCycle_carriesTheDocumentsCycle() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastSuperCycleOneDoc()
      val vm = makeVm("uid-b", Dispatchers.Main)

      vm.start("m-super-estimate")
      advanceUntilIdle()
      broadcast("m-super-estimate")

      vm.submitBidding(BiddingIntent.FinalEstimate("p2", 3))
      advanceUntilIdle()

      assertEquals("the write carried cycle 1", listOf(1), store.bidCycles)
      assertEquals("the re-declaration landed", 3, store.doc.bids["p2"])
      assertEquals("the cycle did not move", 1, store.doc.biddingCycle)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Dash/Auction intents share the action channel and must carry the cycle
   * too. Pinned on a normal round (cycle 0) so the legacy shape stays the
   * default rather than being accidentally gated on a missing field.
   */
  @Test
  fun normalRoundDashCall_carriesCycleZero() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      val vm = makeVm("uid-a", Dispatchers.Main)

      vm.start("m-cycle-dash")
      advanceUntilIdle()
      broadcast("m-cycle-dash")
      assertEquals(BiddingPhase.DASH, biddingOf(vm.state.value).subPhase)

      vm.submitBidding(BiddingIntent.DashCallDecision("p1", declaredDashCall = false))
      advanceUntilIdle()

      assertEquals("the action carried the opening cycle", listOf(0), store.actionCycles)
      assertEquals(
        "the Dash decision is in the log",
        BiddingLogEntry.ACTION_DASH_CALL, store.doc.biddingLog.single().actionType,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The Super Call itself, end to end through the document: p3 confirms 10
   * with a new trump, the ONE transaction-applied write wipes bids/ down to
   * p3's own number and bumps the cycle, and the echo reconstructs the next
   * cycle's ESTIMATES state on the same client.
   */
  @Test
  fun confirmingTheSuperCall_roundTripsTheAtomicResetThroughTheDocument() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastOpeningCycleDoc()
      store.actorUid = "uid-c" // p3, the Super Caller
      val vm = makeVm("uid-c", Dispatchers.Main)

      vm.start("m-super-confirm")
      advanceUntilIdle()
      broadcast("m-super-confirm")
      val opening = biddingOf(vm.state.value)
      assertEquals(BiddingPhase.CONFIRM, opening.subPhase)
      assertEquals("the winning declaration's owner confirms", "p3", opening.waitingFor)

      vm.submitBidding(BiddingIntent.ConfirmCall("p3", 10, Suit.CLUBS))
      advanceUntilIdle()

      assertEquals("the action carried the opening cycle", listOf(0), store.actionCycles)
      // The document's own shape: ONE log entry, bids wiped to the caller's
      // number, the declarations reopened, and the cycle advanced.
      assertEquals(1, store.doc.biddingCycle)
      assertEquals(1, store.doc.biddingLog.size)
      assertEquals(mapOf("p3" to 10), store.doc.bids)
      assertNull("the wipe clears the last declarer", store.doc.lastBidSeat)
      assertTrue("re-declarations are open", store.doc.biddingOpen)

      broadcast("m-super-confirm")
      val next = biddingOf(vm.state.value)
      assertNull("the echo is not a desync", rejectionOf(vm.state.value))
      assertEquals(BiddingPhase.ESTIMATES, next.subPhase)
      assertEquals("p3", next.callerId)
      assertEquals(10, next.auctionTop)
      assertEquals("the replacement trump", Suit.CLUBS, next.declaredTrump)
      assertEquals("every other seat re-declares from the caller's next seat", "p4", next.waitingFor)
      assertEquals("the previous cycle's declarations are gone", setOf("p3"), next.bids.keys)
      assertEquals(1, store.doc.biddingCycle)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Stale intent, live refusal: an intent decided in cycle 1 must not land in
   * cycle 2 after another Super Call committed. The service refuses it by
   * name; the newer delivery then restores the client onto the new cycle.
   */
  @Test
  fun staleCycleIntent_isRefusedAndTheNextDeliveryRestoresTheNewCycle() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastSuperCycleOneDoc()
      val vm = makeVm("uid-a", Dispatchers.Main)

      vm.start("m-super-stale")
      advanceUntilIdle()
      broadcast("m-super-stale")
      assertEquals(BiddingPhase.ESTIMATES, biddingOf(vm.state.value).subPhase)

      // Move the document to cycle 2 behind this client's back (another
      // seat's higher call committed and the delivery has not arrived yet).
      store.doc = fastSuperCycleTwoDoc()
      vm.submitBidding(BiddingIntent.FinalEstimate("p1", 2))
      advanceUntilIdle()

      assertEquals(
        "the client sent the cycle it had actually seen",
        listOf(1), store.bidCycles,
      )
      assertEquals("the service refused the stale write", 1, store.staleCycleRefusals)
      assertNull("nothing landed in the new cycle", store.doc.bids["p1"])
      assertEquals("the document's cycle did not move", 2, store.doc.biddingCycle)

      // The delivery arrives: the client rebuilds onto the ACTIVE cycle.
      broadcast("m-super-stale")
      val restored = biddingOf(vm.state.value)
      assertNull("the newer document is not a desync", rejectionOf(vm.state.value))
      assertEquals("the new Super Caller", "p2", restored.callerId)
      assertEquals(11, restored.auctionTop)
      assertEquals(Suit.HEARTS, restored.declaredTrump)
      assertEquals("p4", restored.waitingFor)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The full live loop: a reloading client restores cycle 1, submits the
   * missing re-declaration, the document records it as an ordinary bid, and
   * the round completes — Caller, trump, With set and estimates all come from
   * the FINAL cycle, and the table opens on the caller's lead.
   */
  @Test
  fun restoredCycle_persistsTheLastReDeclarationAndCompletesTheRound() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastSuperCycleOneDoc()
      val vm = makeVm("uid-b", Dispatchers.Main)

      vm.start("m-super-complete")
      advanceUntilIdle()
      broadcast("m-super-complete")
      assertEquals("p2", biddingOf(vm.state.value).waitingFor)

      vm.submitBidding(BiddingIntent.FinalEstimate("p2", 3))
      advanceUntilIdle()
      broadcast("m-super-complete")

      assertEquals("the re-declaration is on the document", 3, store.doc.bids["p2"])
      assertEquals("still the same cycle", 1, store.doc.biddingCycle)
      val published = vm.state.value
      assertNull("no rejection on the completing delivery", rejectionOf(published))
      val table = tableOf(published)
      assertEquals("the round opens at trick 1", 1, table.trickNo)
      assertEquals("the Super Caller leads", "p3", table.cfg.callerId)
      assertEquals("the final trump", Suit.CLUBS, table.cfg.trump)
      assertEquals(
        "the estimates are the ACTIVE cycle's declarations",
        mapOf("p3" to 10, "p4" to 6, "p1" to 4, "p2" to 3),
        table.cfg.estimates,
      )
      assertEquals("nobody else matched 10 CLUBS", emptyList<String>(), table.cfg.withPlayers)
      assertEquals("the caller holds the turn", "p3", table.turn)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * And the cold reload after that completion: a brand-new view model (fresh
   * session and adapter, no client-side history) rebuilds the same table from
   * the document alone — the acceptance criterion's "persist, restore and
   * replay the complete flow", not just "the unit tests are green".
   */
  @Test
  fun coldReloadAfterTheSuperCallRound_rebuildsTheSameTable() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastSuperCycleOneDoc()
      val first = makeVm("uid-b", Dispatchers.Main)
      first.start("m-super-reload")
      advanceUntilIdle()
      broadcast("m-super-reload")
      first.submitBidding(BiddingIntent.FinalEstimate("p2", 3))
      advanceUntilIdle()
      broadcast("m-super-reload")
      val live = tableOf(first.state.value)

      // Structural reload: a NEW session+adapter (the page worktree's own
      // bootstrap), receiving the committed document for the first time.
      val reloaded = makeVm("uid-d", Dispatchers.Main)
      reloaded.start("m-super-reload")
      advanceUntilIdle()
      broadcast("m-super-reload")

      val resumed = tableOf(reloaded.state.value)
      assertNull("the reload is not a desync", rejectionOf(reloaded.state.value))
      assertEquals("the same trick", live.trickNo, resumed.trickNo)
      assertEquals("the same caller", live.cfg.callerId, resumed.cfg.callerId)
      assertEquals("the same final trump", live.cfg.trump, resumed.cfg.trump)
      assertEquals("the same estimates", live.cfg.estimates, resumed.cfg.estimates)
      assertEquals("the same With set", live.cfg.withPlayers, resumed.cfg.withPlayers)
      assertEquals("the same opening leader", live.turn, resumed.turn)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // The round-1 cold-start fixture, built by driving the real engines
  // ═══════════════════════════════════════════════════════════════════

  private data class RoundOneFixture(
    val outcome: BiddingOutcome,
    val hands: Map<String, List<Card>>,
    val table: TableState,
    val doc: MatchDoc,
  )

  /**
   * A complete, legal round-1 mid-match document: a closed auction (the
   * whole Dash→Auction→Confirm flow plus four final estimates) and [tricks]
   * completed tricks, produced by actually playing them.
   */
  private fun roundOneFixture(tricks: Int): RoundOneFixture {
    val log = mutableListOf<BiddingLogEntry>()
    val estimates = linkedMapOf<String, Int>()
    val perSeatEstimate = mapOf("p1" to 2, "p2" to 3, "p3" to 1, "p4" to 2)

    var state = initNormalRound(round = 1, dealer = seats.first(), seats = seats)
    var outcome: BiddingOutcome? = null
    var auctionBidPlaced = false
    var guard = 0
    while (state.subPhase != BiddingPhase.DONE && guard < 40) {
      guard++
      val actor = state.waitingFor ?: error("no seat waiting in ${state.subPhase}")
      val intent: BiddingIntent = when (state.subPhase) {
        BiddingPhase.DASH -> BiddingIntent.DashCallDecision(actor, declaredDashCall = false)
        BiddingPhase.AUCTION -> if (!auctionBidPlaced) {
          auctionBidPlaced = true
          BiddingIntent.AuctionBid(actor, isPass = false, tricks = 4, suit = Suit.SPADES)
        } else {
          BiddingIntent.AuctionBid(actor, isPass = true)
        }
        BiddingPhase.CONFIRM -> BiddingIntent.ConfirmCall(
          actor, state.auctionTop, state.auctionSuit ?: error("no auction suit to confirm"),
        )
        BiddingPhase.ESTIMATES -> {
          // Estimates live in the document's bids map, not its biddingLog —
          // FinalEstimate has no log entry shape.
          val value = perSeatEstimate.getValue(actor)
          estimates[actor] = value
          BiddingIntent.FinalEstimate(actor, value)
        }
        BiddingPhase.DONE -> break
      }
      if (state.subPhase != BiddingPhase.ESTIMATES) log.add(intent.toLogEntry(actor, 1))
      state = when (val result = emit(state, intent)) {
        is EmitResult.Applied -> result.state
        is EmitResult.Completed -> {
          outcome = result.outcome
          result.state
        }
        is EmitResult.GeneralPass -> error("unexpected general pass in the fixture auction")
        is EmitResult.Rejected -> error("fixture auction rejected at ${state.subPhase}: ${result.reason}")
      }
    }
    val done = outcome ?: error("the fixture auction never closed")

    // The rig: the caller holds all 13 trumps and so leads and wins every
    // trick; the other three seats hold exactly the 39 non-trumps.
    val rig = riggedHands(done.trump, done.callerId!!)

    // Play [tricks] tricks through the real table engine, recording what it
    // did as the document's cardLog.
    var table = initTable(
      RoundCfg(
        round = 1,
        trump = done.trump,
        callerId = done.callerId,
        withPlayers = done.withPlayers,
        estimates = done.estimates,
        dashCallers = done.dashCallers,
        leaderId = done.leaderId,
        riskId = done.riskPlayerId,
        hands = rig,
      ),
      seats,
    )
    val cardLog = mutableListOf<CardLogEntry>()
    repeat(tricks) {
      repeat(4) {
        val seat = table.turn ?: error("no turn to play in the fixture")
        val card = table.cfg.hands.getValue(seat).first()
        cardLog.add(CardLogEntry(seat, StoredCard.fromEngine(card), 1))
        table = when (val r = emitPlay(table, PlayCard(seat, card))) {
          is PlayEmit.Applied -> r.state
          is PlayEmit.Rejected -> error("fixture play rejected for $seat: ${r.reason}")
        }
        if (table.phase == TablePhase.RESOLVING) table = resolveTrick(table)
      }
    }

    val doc = MatchDoc(
      roomId = "room-1",
      players = seatUids.values.toList(),
      status = MatchDoc.STATUS_STARTING,
      currentRound = 1,
      maxRounds = 18,
      extendedRounds = emptyList(),
      dealer = seatUids.getValue(seats.first()),
      turn = null,
      seats = seatUids,
      // A document this client is resuming mid-match: well past the auction,
      // well into play, at a version nothing local has ever seen.
      version = 100,
      biddingOpen = false,
      bids = estimates,
      lastBidSeat = estimates.keys.last(),
      cardLog = cardLog.toList(),
      lastCardSeat = cardLog.last().seatId,
      cardPhase = MatchDoc.CARD_PHASE_PLAY,
      biddingLog = log.toList(),
      gameState = GameState(initialized = true, dealtRound = 1),
    )
    return RoundOneFixture(done, rig, table, doc)
  }

  /** The caller holds every trump; each other seat holds one non-trump
   *  suit's 13 cards. Deterministic by construction: the caller cannot lose
   *  a trick it leads, and it leads all of them. */
  private fun riggedHands(trump: Suit, callerSeat: String): Map<String, List<Card>> {
    val rest = DECK_SUITS.filter { it != trump }.flatMap { s -> RANKS.map { Card(s, it) } }
    assertEquals("the rig deals exactly 39 non-trump cards", 39, rest.size)
    val hands = linkedMapOf<String, List<Card>>()
    hands[callerSeat] = RANKS.map { Card(trump, it) }
    seats.filter { it != callerSeat }.forEachIndexed { i, seat ->
      hands[seat] = rest.subList(i * 13, i * 13 + 13)
    }
    return hands
  }

  /** The document shape for a log entry the real engine just accepted. */
  private fun BiddingIntent.toLogEntry(seat: String, round: Int): BiddingLogEntry = when (this) {
    is BiddingIntent.DashCallDecision -> BiddingLogEntry(
      seatId = seat,
      actionType = BiddingLogEntry.ACTION_DASH_CALL,
      declaredDashCall = declaredDashCall,
      round = round,
    )
    is BiddingIntent.AuctionBid -> BiddingLogEntry(
      seatId = seat,
      actionType = BiddingLogEntry.ACTION_AUCTION_BID,
      isPass = isPass,
      tricks = if (isPass) null else tricks,
      suit = if (isPass) null else suit?.name,
      round = round,
    )
    is BiddingIntent.ConfirmCall -> BiddingLogEntry(
      seatId = seat,
      actionType = BiddingLogEntry.ACTION_CONFIRM_CALL,
      tricks = tricks,
      suit = suit.name,
      round = round,
    )
    is BiddingIntent.FinalEstimate ->
      error("FinalEstimate is the bids map, not the biddingLog")
  }

  /**
   * S61 — stop the VM and drain the scheduler. Any test that leaves the
   * timeout alarm armed must do this before [Dispatchers.resetMain]:
   * [runTest]'s own epilogue resumes the pending alarm afterwards, and a Main
   * dispatcher that has been reset cannot serve it. [advanceUntilIdle] fires
   * and drains everything still scheduled — the away alarm, the timeout alarm,
   * and the cancellation runnables stop() raises — so nothing is left for the
   * epilogue to trip over. Every assertion precedes it, so the extra virtual
   * time it spends is unobservable.
   */
  private fun TestScope.stopAndDrain(vm: OnlineMatchViewModel) {
    advanceUntilIdle()
    vm.stop()
    advanceUntilIdle()
  }

  // ==========================================================================
  // S61 (E6b) — the timeout-resolution alarm
  //
  // The alarm is pure client logic over a scriptable [DecisionTimeoutPort],
  // so these pin its retry semantics hermetically — the same way
  // [FakeMatchStore] pins the write paths. The instrumented suite exercises
  // the REAL callable against the Functions emulator; these tests pin what
  // the alarm DOES with each answer it gets back.
  // ==========================================================================

  /**
   * The first arm is the derived window — 20 s here (the 15 s default timer +
   * RD21's 5 s bidding offset). Nothing reaches the callable before it, and
   * one second short of it nothing has fired.
   */
  @Test
  fun theAlarmWaitsTheDerivedWindowBeforeCalling() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Applied)
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      // 500 ms short of the window: the first fire has not happened yet
      // (advanceTimeBy also runs the tasks that fall due inside it, so the
      // boundary itself would fire).
      advanceTimeBy(19_500)
      runCurrent()
      assertEquals("no call before the window elapses", 0, timeouts.calls.get())

      advanceTimeBy(2_000)
      runCurrent()
      assertEquals("the window elapsed and the callable was invoked", 1, timeouts.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Acceptance (a): an early call is retried on the SERVER's remaining time
   * and eventually resolves. No unrelated Firestore update is needed to move
   * it along — NOT_YET_EXPIRED re-arms the alarm itself.
   */
  @Test
  fun anEarlyNotYetExpiredIsRetriedAndEventuallyResolves() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(
        DecisionTimeoutPort.Resolution.NotYetExpired(1_000),
        DecisionTimeoutPort.Resolution.Applied,
      )
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      // 250 ms past the 20 s window but short of the 1 s retry that follows,
      // so exactly the first fire lands here.
      advanceTimeBy(20_250)
      runCurrent()
      assertEquals("the first call landed early", 1, timeouts.calls.get())

      // Re-armed on the server's 1 s, not on a re-delivery.
      advanceTimeBy(1_000)
      runCurrent()
      assertEquals(2, timeouts.calls.get())

      // APPLIED terminates the loop — it does not poll for the update.
      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("no further call after an applied resolution", 2, timeouts.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Acceptance (b): a transient callable failure does not permanently disable
   * the alarm. Each failure backs off and the next attempt still lands.
   */
  @Test
  fun aTransientCallableFailureDoesNotDisableTheAlarm() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(
        DecisionTimeoutPort.Resolution.Retryable,
        DecisionTimeoutPort.Resolution.Retryable,
        DecisionTimeoutPort.Resolution.Applied,
      )
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      // 250 ms past the 20 s window but short of the 500 ms first backoff,
      // so exactly the first fire lands here.
      advanceTimeBy(20_250)
      runCurrent()
      assertEquals(1, timeouts.calls.get())

      // 500 ms backoff, then 1 s.
      advanceTimeBy(500)
      runCurrent()
      assertEquals(2, timeouts.calls.get())
      advanceTimeBy(1_000)
      runCurrent()
      assertEquals(3, timeouts.calls.get())

      // The third call applied; the loop stops.
      advanceTimeBy(60_000)
      runCurrent()
      assertEquals(3, timeouts.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Acceptance (c): a newer delivery cancels the stale scheduled attempt. Both
   * deliveries land at the same virtual instant and each arms a 20 s wait, so
   * if the first alarm had survived it would ALSO have fired here — exactly
   * one call proves it was cancelled rather than merely superseded.
   */
  @Test
  fun aNewerDeliveryCancelsTheStaleAlarm() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Applied)
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()

      deliverNow("m-to")
      // A newer decision baseline arrives before the first alarm fires.
      store.doc = fastRoundDoc().copy(version = 2)
      deliverNow("m-to")

      advanceTimeBy(21_000)
      runCurrent()
      assertEquals("only the re-armed alarm fired", 1, timeouts.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * At most one alarm is ever armed: three rapid deliveries, one call. This is
   * the client half of "at most one active alarm job".
   */
  @Test
  fun atMostOneAlarmIsEverArmedAcrossDeliveries() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Applied)
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()

      repeat(3) { i ->
        store.doc = fastRoundDoc().copy(version = i + 1)
        deliverNow("m-to")
      }

      advanceTimeBy(21_000)
      runCurrent()
      assertEquals(1, timeouts.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * A bot-controlled seat does not make the table wait the human window — the
   * S61 pacing fix is pointless if the client still arms at 15 s + 5 and stalls
   * the table first. Three seconds is past the 2 s bot window and well short
   * of the 20 s human window.
   */
  @Test
  fun aBotControlledSeatArmsAtTheShortWindow() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Applied, clock = { currentTime })
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      val waiting = biddingOf(vm.state.value).waitingFor
        ?: error("expected a live bidding decision to wait on")
      // Remove the deciding seat by the timeout mechanism's OWN provenance —
      // never Vote Kick's botSeats — and re-deliver. The version MUST tick:
      // every real write bumps it, and the adapter's ordering guard drops a
      // same-version delivery, so a flat copy would never reach the VM.
      store.doc = fastRoundDoc().copy(
        version = 2,
        timeoutRemovedUids = listOf(seatUids.getValue(waiting)),
      )
      deliverNow("m-to")
      val armedAt = currentTime

      // advanceUntilIdle fires every pending timer; the recorded call time is
      // what distinguishes the two-second bot window from the twenty-second
      // human one, which is the whole point of the pacing fix.
      advanceUntilIdle()
      assertEquals(1, timeouts.callTimes.size)
      val firedAt = timeouts.callTimes.single()
      assertTrue(
        "the bot-controlled seat fired at its short window (${firedAt - armedAt} ms), " +
          "not the 20 s human one",
        firedAt - armedAt <= 3_000,
      )
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * A borrowed seat arms at the short window too — the resolver times it at
   * the fixed 2 s bot pace (C3 shortWindowSeats), so the client must not arm
   * the 20 s human window or it stalls the table on the first decision.
   * Mirrors aBotControlledSeatArmsAtTheShortWindow, but through S73's own
   * provenance (inactiveTakeovers, never botSeats/timeoutRemovedUids).
   */
  @Test
  fun aBorrowedSeatArmsAtTheShortWindow() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Applied, clock = { currentTime })
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      val waiting = biddingOf(vm.state.value).waitingFor
        ?: error("expected a live bidding decision to wait on")
      // Mark the deciding seat borrowed through the takeover marker — the
      // owner's uid stays seated, so this is a borrow, not a removal.
      store.doc = fastRoundDoc().copy(
        version = 2,
        inactiveTakeovers = mapOf(
          waiting to InactiveTakeover(
            takenOverAtMillis = currentTime,
            roundAtTakeover = 14,
            trickAtTakeover = 0,
          ),
        ),
      )
      deliverNow("m-to")
      val armedAt = currentTime

      advanceUntilIdle()
      assertEquals(1, timeouts.callTimes.size)
      val firedAt = timeouts.callTimes.single()
      assertTrue(
        "the borrowed seat fired at its short window (${firedAt - armedAt} ms), " +
          "not the 20 s human one",
        firedAt - armedAt <= 3_000,
      )
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * A permanent denial terminates the alarm: retrying a denial the server
   * raised on purpose would only hammer it.
   */
  @Test
  fun aDeniedResolutionTerminatesTheAlarm() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Denied)
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      advanceTimeBy(21_000)
      runCurrent()
      assertEquals(1, timeouts.calls.get())

      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("a denial is terminal", 1, timeouts.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The loop is bounded: a callable that never succeeds cannot spin it. The
   * transient budget is six consecutive failures plus the attempt that spends
   * the last one — the exponential backoff makes those ~31 s of patient
   * retrying, not a burst.
   */
  @Test
  fun theAlarmStopsWhenItsTransientBudgetIsSpent() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Retryable)
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      advanceUntilIdle()
      val calls = timeouts.calls.get()
      assertEquals("exactly the transient budget, then the alarm stopped", 7, calls)

      // And it stays stopped.
      advanceTimeBy(120_000)
      runCurrent()
      assertEquals(7, timeouts.calls.get())
      // advanceUntilIdle above already drained everything, so nothing is
      // left armed — no stopAndDrain needed here.
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Leaving the match drops the armed alarm along with everything else — a
   * resolved alarm for a match nobody is watching is a leak.
   */
  @Test
  fun stopCancelsTheArmedAlarm() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = fastRoundDoc()
      val timeouts = ScriptedTimeouts(DecisionTimeoutPort.Resolution.Applied)
      val vm = makeVm("uid-a", Dispatchers.Main, timeouts = timeouts)
      vm.start("m-to")
      advanceUntilIdle()
      deliverNow("m-to")

      vm.stop()
      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("the armed alarm never fired after stop", 0, timeouts.calls.get())
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ==========================================================================
  // The ranked settlement — the once-per-session trigger and the reveal (RD13)
  // ==========================================================================

  /**
   * A terminal RANKED document, the way endMatch leaves it: four totals one
   * of which wins. The standings the screen shows are a pure function of
   * these; the RP movement is only ever the settlement callable's answer.
   */
  private fun completeRankedDoc(): MatchDoc = MatchDoc(
    roomId = "room-1",
    players = seatUids.values.toList(),
    status = MatchDoc.STATUS_COMPLETE,
    currentRound = 1,
    maxRounds = 1,
    extendedRounds = emptyList(),
    dealer = seatUids.getValue("p1"),
    turn = null,
    seats = seatUids,
    version = 1,
    biddingOpen = false,
    bids = emptyMap(),
    lastBidSeat = null,
    cardLog = emptyList(),
    lastCardSeat = null,
    cardPhase = null,
    biddingLog = emptyList(),
    gameState = GameState.NOT_DEALT,
    mode = MatchMode.RANKED,
    finalScores = mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77),
    winnerIds = listOf("p3"),
    completedRound = 1,
  )

  /**
   * The reveal the tests drive: the server pair 600 and +30; the display RP
   * of 630 is what the standings' existing helper derives from them.
   */
  private fun theMovement() = RpMovement(previousRp = 600, delta = 30)

  /**
   * A completed RANKED match settles exactly once per session (even across
   * terminal re-deliveries), publishes the plain standings BEFORE the word
   * settlement is spoken, and then — when the callable answers with the
   * caller's OWN movement — attaches the reveal through the standings'
   * existing [buildRankedResult]. The movement is never computed here: RD13
   * makes the server's `you` the only truthful source a client has.
   */
  @Test
  fun rankedComplete_settlesOnceAndRevealsTheServerMovement() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = completeRankedDoc()
      val settlements = ScriptedSettlements(
        SettlementPort.SettlementOutcome.Settled(replayed = false, you = theMovement()),
      )
      val vm = makeVm("uid-a", Dispatchers.Main, settlements = settlements)
      vm.start("m-ranked")
      advanceUntilIdle()

      // The terminal delivery publishes FIRST — deliverNow hands the document
      // over and runs exactly one dispatch, and the held answer below keeps
      // the settlement coroutine suspended inside the call it armed: the
      // standings must be on screen long before the network answers.
      settlements.holdNextCall()
      deliverNow("m-ranked")
      val beforeAnySettle = vm.state.value as MatchUiState.MatchComplete
      assertEquals(
        buildStandings(mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77)),
        beforeAnySettle.standings,
      )
      assertNull("no reveal before the server has answered", beforeAnySettle.ranked)
      assertEquals("the call is in flight, not waiting to be made", 1, settlements.calls.get())

      // The answer lands and the reveal rides the state the moment it does.
      settlements.release()
      advanceUntilIdle()
      assertEquals("exactly one settle call", 1, settlements.calls.get())
      assertEquals("the whole match id went to the callable", "m-ranked", settlements.matchIds.single())

      // The reveal rides the state the moment the outcome lands.
      val revealed = vm.state.value as MatchUiState.MatchComplete
      val ranked = revealed.ranked
      assertEquals("the server's own previous RP", 600, ranked!!.previousRP)
      assertEquals("the server's own delta", 30, ranked.delta)
      assertEquals("the current RP derived off the server's pair", 630, ranked.rp)
      assertEquals("the tier chip marks the calling seat", "p1", ranked.userSeat)
      assertEquals("the standings are untouched by the reveal", beforeAnySettle.standings, revealed.standings)
      // currentMatchId handling is unchanged: cleared once, on first sight.
      assertEquals(1, players.clears.get())
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * A redelivery of the SAME terminal document must not re-trigger — the
   * trigger is once per session, the claim record is once per match — and
   * must not clobber the reveal that landed between deliveries: the
   * standings are a pure function of the document, so republishing them
   * could only erase what the settlement gave the screen.
   */
  @Test
  fun rankedComplete_redeliveriesReTriggerNothingAndKeepTheReveal() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = completeRankedDoc()
      val settlements = ScriptedSettlements(
        SettlementPort.SettlementOutcome.Settled(replayed = false, you = theMovement()),
      )
      val vm = makeVm("uid-a", Dispatchers.Main, settlements = settlements)
      vm.start("m-ranked")
      advanceUntilIdle()
      broadcast("m-ranked")

      // A NEW version of the same terminal document — exactly what the
      // snapshot fan-out, a metadata bump, or the settlement's own
      // correction write would deliver.
      store.bumpVersion()
      broadcast("m-ranked")
      store.bumpVersion()
      broadcast("m-ranked")

      assertEquals("one settle call per session, however many deliveries", 1, settlements.calls.get())
      val revealed = vm.state.value as MatchUiState.MatchComplete
      assertEquals("the reveal survived the redeliveries", 600, revealed.ranked!!.previousRP)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * A second client's call is absorbed by the SERVER's claim record, and a
   * replay that returns the recorded movement still shows the reveal —
   * `replayed` changes nothing about what the screen is told.
   */
  @Test
  fun rankedComplete_aReplayShowsTheRecordedMovement() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = completeRankedDoc()
      val settlements = ScriptedSettlements(
        SettlementPort.SettlementOutcome.Settled(replayed = true, you = theMovement()),
      )
      val vm = makeVm("uid-a", Dispatchers.Main, settlements = settlements)
      vm.start("m-ranked")
      broadcast("m-ranked")
      advanceUntilIdle()

      assertEquals(1, settlements.calls.get())
      val ranked = (vm.state.value as MatchUiState.MatchComplete).ranked
      assertEquals("the recorded movement replays whole", 600, ranked!!.previousRP)
      assertEquals(30, ranked.delta)
      assertEquals(630, ranked.rp)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * The no-settlement paths all land on the same thing: plain standings, no
   * reveal, no crash, no new word on the screen. NOT_RANKED (the mode gate's
   * answer), a server denial, a `you` the response does not carry, and a
   * retryable transport failure exhausted to its bound.
   */
  @Test
  fun rankedComplete_notRankedDeniedAbsentYouAndExhaustedRetriesShowPlainStandings() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val outcomes = listOf(
        SettlementPort.SettlementOutcome.NotRanked("UNRANKED"),
        SettlementPort.SettlementOutcome.Denied,
        SettlementPort.SettlementOutcome.Settled(replayed = true, you = null),
        SettlementPort.SettlementOutcome.Retryable,
      )
      for (outcome in outcomes) {
        factory = BroadcastFactory()
        players = FakePlayers()
        store = FakeMatchStore(Dealer.dealHands(11))
        store.doc = completeRankedDoc()
        val settlements = ScriptedSettlements(outcome)
        val vm = makeVm("uid-a", Dispatchers.Main, settlements = settlements)
        vm.start("m-not-revealed")
        broadcast("m-not-revealed")
        advanceUntilIdle()

        val complete = vm.state.value as MatchUiState.MatchComplete
        assertNull("$outcome reveals nothing", complete.ranked)
        assertEquals(
          buildStandings(mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77)),
          complete.standings,
        )
        assertTrue("no crash, just plain standings", players.clears.get() == 1)

        // The exhausted-retry loop ran its bounded budget and stopped.
        val expectedCalls = if (outcome is SettlementPort.SettlementOutcome.Retryable) {
          // failures 1..6 each back off and re-ask; the 7th collapses the
          // budget exactly like the S61 alarm's.
          7
        } else {
          1
        }
        assertEquals(expectedCalls, settlements.calls.get())

        vm.stop()
      }
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * ROOM mode never settles: the trigger is RANKED-only (RD28), so a
   * completed ROOM match costs the settler nothing at all.
   */
  @Test
  fun roomComplete_speaksNoSettlementWord() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(11))
      store.doc = completeRankedDoc().copy(mode = MatchMode.ROOM)
      val settlements = ScriptedSettlements(
        SettlementPort.SettlementOutcome.Settled(replayed = false, you = theMovement()),
      )
      val vm = makeVm("uid-a", Dispatchers.Main, settlements = settlements)
      vm.start("m-room")
      broadcast("m-room")
      advanceUntilIdle()

      assertEquals("a ROOM match never asks the settler", 0, settlements.calls.get())
      assertTrue(vm.state.value is MatchUiState.MatchComplete)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ==========================================================================
  //  The hermetic stand-ins
  // ==========================================================================

  /**
   * One Firestore document, broadcast to every subscriber of its matchId —
   * the contract a real addSnapshotListener satisfies and the one the
   * adapter's ref-counting assumes. A delivery is synchronous and
   * single-threaded, which is strictly stronger than the real thing and
   * keeps these tests deterministic without a latch in sight.
   */
  private class BroadcastFactory : MatchListenerFactory {
    val listenCalls = AtomicInteger(0)
    private val sinks = mutableMapOf<String, MutableList<(MatchDoc?) -> Unit>>()
    private val errorSinks = mutableMapOf<String, MutableList<(FirestoreError) -> Unit>>()

    override fun listen(
      matchId: String,
      onSnapshot: (MatchDoc?) -> Unit,
      onError: (FirestoreError) -> Unit,
    ): MatchListenerRegistration {
      listenCalls.incrementAndGet()
      val list = sinks.getOrPut(matchId) { mutableListOf() }
      list.add(onSnapshot)
      val errs = errorSinks.getOrPut(matchId) { mutableListOf() }
      errs.add(onError)
      return MatchListenerRegistration {
        list.remove(onSnapshot)
        errs.remove(onError)
      }
    }

    /** Hand [doc] to every live subscriber of [matchId]. */
    fun deliver(matchId: String, doc: MatchDoc?) {
      sinks[matchId]?.toList()?.forEach { it(doc) }
    }

    /** Hand [error] to every live subscriber's error sink — a Firestore
     *  listener blip, delivered the way the adapter's onError receives it.
     *  The adapter's own backoff (Immediate in these tests) re-attaches the
     *  one real listener before this returns when the error is retryable, so
     *  the next [deliver] still lands exactly one delivery. */
    fun fail(matchId: String, error: FirestoreError) {
      errorSinks[matchId]?.toList()?.forEach { it(error) }
    }

    /** Live subscribers — the "one real listener per device" invariant. */
    fun subscribers(matchId: String): Int = sinks[matchId]?.size ?: 0
  }

  /**
   * A [DecisionTimeoutPort] that answers from a script and records every call.
   * The alarm's retry semantics are client logic, so this stands in for the
   * callable exactly the way [FakeMatchStore] stands in for Firestore: a call
   * is the observable, and the script is the answer the server would have
   * given. [DecisionTimeoutPort.Resolution.Applied] and [NotYetExpired] are
   * the two the alarm's behaviour turns on; the instrumented suite covers the
   * real callable path.
   */
  private class ScriptedTimeouts(vararg scripted: DecisionTimeoutPort.Resolution) :
    DecisionTimeoutPort {
    private val script = scripted.toList()
    val calls = AtomicInteger(0)
    val matchIds = mutableListOf<String>()
    /** The scheduler's virtual time at each call — the bot-pacing test pins the
     *  window the alarm actually armed at, not just that it fired. */
    val callTimes = mutableListOf<Long>()
    private var clock: () -> Long = { 0L }

    constructor(vararg scripted: DecisionTimeoutPort.Resolution, clock: () -> Long) : this(
      *scripted,
    ) {
      this.clock = clock
    }

    override suspend fun resolve(matchId: String): DecisionTimeoutPort.Resolution {
      matchIds.add(matchId)
      callTimes.add(clock())
      val index = calls.getAndIncrement()
      return script.getOrElse(index) { script.last() }
    }
  }

  /**
   * [makeVm]'s default port: the pre-S61 sections never arm an alarm that
   * reaches a callable, and if one does, [NotActive] ends it immediately.
   */
  private object DecisionTimeoutPortStub : DecisionTimeoutPort {
    override suspend fun resolve(matchId: String) = DecisionTimeoutPort.Resolution.NotActive
  }

  /**
   * [makeVm]'s reclaim seam: no section of this suite puts an
   * `inactiveTakeovers` marker on the document, so no poll ever arms and this
   * is never reached — and if one did, DENIED ends it without a retry. The
   * poll's own semantics are pinned in SeatReclaimPollTest with a scripted
   * port. Like every other seam here, it exists so the constructor never
   * defaults into [com.estemshan.game.data.OnlineServices] and Firebase.
   */
  private object SeatReclaimPortStub : SeatReclaimPort {
    override suspend fun reclaim(matchId: String) = SeatReclaimPort.Reclaim.Denied
  }

  /**
   * [makeVm]'s default settlement port: no section but the ranked-settlement
   * one should ever reach the callable, so an unexpected call is a loud
   * failure rather than a silently-swallowed outcome.
   */
  private object SettlementPortStub : SettlementPort {
    override suspend fun settle(matchId: String): SettlementPort.SettlementOutcome =
      error("no test outside the ranked-settlement section expects a settle call")
  }

  /**
   * A [SettlementPort] that answers from a script and records every call —
   * [ScriptedTimeouts]' idiom, for the same reason: the retry and
   * once-per-session semantics are client logic, so a call is the observable
   * and the script is the answer the server would have given. Nothing drives
   * the real callable yet, and no settlement emulator/device suite exists;
   * the mapping test pins the response shape until one does.
   *
   * [holdNextCall] makes the NEXT settle() suspend until [release] — a
   * round-trip whose answer has not come back yet — so a test can observe
   * the screen while the network is still pending.
   */
  private class ScriptedSettlements(
    vararg scripted: SettlementPort.SettlementOutcome,
  ) : SettlementPort {
    private val script = scripted.toList()
    val calls = AtomicInteger(0)
    val matchIds = mutableListOf<String>()
    private var gate: CompletableDeferred<Unit>? = null

    fun holdNextCall() {
      gate = CompletableDeferred()
    }

    fun release() {
      gate?.complete(Unit)
      gate = null
    }

    override suspend fun settle(matchId: String): SettlementPort.SettlementOutcome {
      matchIds.add(matchId)
      calls.incrementAndGet()
      gate?.let { it.await(); gate = null }
      val index = calls.get() - 1
      return script.getOrElse(index) { script.last() }
    }
  }

  /**
   * In-memory [MatchStore]: one mutable match document and one deal, plus a
   * counter for every write path. It stays honest by mirroring what the
   * real MatchService's transactions conclude — bids overwrite the seat's
   * slot and bump the version, the deal is idempotent, a card is appended
   * once and attributed to the one seat whose hand still holds it (the real
   * service resolves the actor from the signed-in user; the deal resolves
   * it here, since a card is unique in the deck).
   */
  private class FakeMatchStore(
    private val hands: Map<String, List<Card>> = Dealer.dealHands(1),
  ) : MatchStore {

    val dealCalls = AtomicInteger(0)
    val redundantDealCalls = AtomicInteger(0)
    val bidCalls = AtomicInteger(0)
    val actionCalls = AtomicInteger(0)
    val cardCalls = AtomicInteger(0)
    val advanceCalls = AtomicInteger(0)
    val passAdvanceCalls = AtomicInteger(0)
    val extendCalls = AtomicInteger(0)
    val endCalls = AtomicInteger(0)

    /** T-09: the `expectedCycle` each write carried, in call order. */
    val bidCycles = mutableListOf<Int?>()
    val actionCycles = mutableListOf<Int?>()

    /** How many writes the cycle guard refused. */
    var staleCycleRefusals: Int = 0

    /** The signed-in uid the fake's seat resolution uses (MatchService reads
     *  it from auth; one store stands in for every seat's writes here). */
    var actorUid: String = "uid-a"

    /** The deal as played: a card leaves the hand that played it, so a
     *  submitCard() can attribute it the way the signed-in user would. */
    private val remaining = hands.mapValues { it.value.toMutableList() }

    @Volatile
    var doc: MatchDoc = MatchDoc(
      roomId = "room-1",
      players = listOf("uid-a", "uid-b", "uid-c", "uid-d"),
      status = MatchDoc.STATUS_STARTING,
      currentRound = 1,
      maxRounds = 18,
      extendedRounds = emptyList(),
      dealer = "uid-a",
      turn = null,
      seats = linkedMapOf("p1" to "uid-a", "p2" to "uid-b", "p3" to "uid-c", "p4" to "uid-d"),
      version = 1,
      biddingOpen = true,
      bids = emptyMap(),
      lastBidSeat = null,
      cardLog = emptyList(),
      lastCardSeat = null,
      cardPhase = null,
      biddingLog = emptyList(),
      gameState = GameState.NOT_DEALT,
    )

    fun snapshot(): MatchDoc = doc

    /** The full deal hand for [seat], before any play shrank it. */
    fun peekHand(seat: String): List<Card> = hands[seat].orEmpty()

    /** A metadata-only version bump: a re-delivery the content dedupe
     *  forwards but the interpreters must still refuse to re-apply. */
    fun bumpVersion() {
      doc = doc.copy(version = doc.version + 1)
    }

    override suspend fun startMatch(roomId: String): String = "match-from-$roomId"

    override suspend fun loadMatch(matchId: String): MatchDoc? = doc

    override suspend fun loadHand(matchId: String, seatId: String, round: Int): HandDoc? {
      if (doc.gameState.dealtRound < round) return null
      val hand = hands[seatId] ?: return null
      return HandDoc(seatId, round, hand.map { StoredCard.fromEngine(it) })
    }

    override suspend fun submitBid(
      matchId: String,
      seatId: String,
      bid: Int,
      expectedCycle: Int?,
    ): SubmitBidResult {
      bidCalls.incrementAndGet()
      bidCycles += expectedCycle
      // The real service's gates, in its own order: cycle guard, then the
      // open-bidding gate, then one value per seat IN THE ACTIVE CYCLE
      // (MatchService.submitBid) — a faithful double must not accept a
      // re-declaration the document would refuse.
      if (expectedCycle != null && doc.biddingCycle != expectedCycle) {
        staleCycleRefusals++
        throw ServiceException(Reasons.STALE_CYCLE, "submitBid: stale cycle")
      }
      if (doc.biddingOpen != true) {
        throw ServiceException(Reasons.BIDDING_CLOSED, "submitBid: bidding is closed")
      }
      if (doc.bids[seatId] != null) {
        throw ServiceException(Reasons.ALREADY_BID, "submitBid: already submitted")
      }
      val bids = doc.bids + (seatId to bid)
      val allSubmitted = doc.seats.isNotEmpty() && doc.seats.keys.all { bids[it] != null }
      doc = doc.copy(
        bids = bids,
        lastBidSeat = seatId,
        biddingOpen = !allSubmitted,
        version = doc.version + 1,
      )
      return SubmitBidResult(
        matchId, seatId, bid, doc.version, !allSubmitted, allSubmitted,
        biddingCycle = doc.biddingCycle,
      )
    }

    override suspend fun submitBiddingAction(
      matchId: String,
      action: BiddingActionInput,
    ): SubmitBiddingActionResult {
      actionCalls.incrementAndGet()
      actionCycles += action.expectedCycle
      val seat = doc.seats.entries.firstOrNull { it.value == actorUid }?.key ?: "p1"
      // MatchService's cycle guard: an intent decided in an older declaration
      // cycle is refused instead of landing in the new one.
      if (action.expectedCycle != null && doc.biddingCycle != action.expectedCycle) {
        staleCycleRefusals++
        throw ServiceException(Reasons.STALE_CYCLE, "submitBiddingAction: stale cycle")
      }
      // The real service's F1 normalising boundary: an over-cap declared
      // Dash attempt persists as a decline (MatchService.submitBiddingAction).
      val entry = normaliseDashCallEntry(
        action.toLogEntry(seat, doc.currentRound),
        effectiveDashCallCount(doc.biddingLog, doc.currentRound),
      )
      // T-09: a fast-round CONFIRM is the Super Call's ONE atomic reset —
      // the log entry, the wipe down to the caller's own number, the reopened
      // declarations and the cycle bump (MatchService.submitBiddingAction).
      if (action.actionType == BiddingLogEntry.ACTION_CONFIRM_CALL &&
        doc.gameType.isFastRound(doc.currentRound)
      ) {
        doc = doc.copy(
          biddingLog = doc.biddingLog + entry,
          bids = mapOf(seat to (action.tricks ?: error("confirm without tricks"))),
          biddingOpen = true,
          lastBidSeat = null,
          biddingCycle = doc.biddingCycle + 1,
          version = doc.version + 1,
        )
      } else {
        doc = doc.copy(
          biddingLog = doc.biddingLog + entry,
          version = doc.version + 1,
        )
      }
      return SubmitBiddingActionResult(
        matchId, seat, action.actionType, doc.version, doc.biddingLog.size,
        biddingCycle = doc.biddingCycle,
      )
    }

    override suspend fun submitCard(matchId: String, card: Card): SubmitCardResult {
      cardCalls.incrementAndGet()
      val seat = remaining.entries.firstOrNull { it.value.contains(card) }?.key
        ?: return SubmitCardResult(matchId, "", doc.version, doc.cardLog.size, null, null)
      remaining.getValue(seat).remove(card)
      doc = doc.copy(
        cardLog = doc.cardLog + CardLogEntry(seat, StoredCard.fromEngine(card), doc.currentRound),
        lastCardSeat = seat,
        cardPhase = MatchDoc.CARD_PHASE_PLAY,
        version = doc.version + 1,
      )
      return SubmitCardResult(
        matchId, seat, doc.version, doc.cardLog.size, null, MatchDoc.CARD_PHASE_PLAY,
      )
    }

    override suspend fun dealRound(matchId: String, roundNumber: Int): DealResult {
      // Like the real transaction: a redundant attempt reads the committed
      // dealtRound and no-ops. Counting the attempt rather than the deal
      // would make the redundant re-delivery bindSeat performs on every
      // first snapshot look like a double-deal.
      if (doc.gameState.dealtRound >= roundNumber) {
        redundantDealCalls.incrementAndGet()
        return DealResult(dealt = false, reason = "ALREADY_DEALT", matchId, roundNumber)
      }
      dealCalls.incrementAndGet()
      doc = doc.copy(gameState = GameState(initialized = true, dealtRound = roundNumber))
      return DealResult(dealt = true, reason = null, matchId, roundNumber, doc.seats.keys.toList())
    }

    override suspend fun advanceToNextRound(
      matchId: String,
      completedRound: Int,
      nextMultiplier: Int,
    ): AdvanceResult {
      advanceCalls.incrementAndGet()
      doc = doc.copy(
        currentRound = doc.currentRound + 1,
        currentMultiplier = nextMultiplier,
        version = doc.version + 1,
        biddingOpen = true,
        bids = emptyMap(),
        lastBidSeat = null,
        cardLog = emptyList(),
        lastCardSeat = null,
        cardPhase = null,
        biddingLog = emptyList(),
      )
      return AdvanceResult(
        advanced = true, reason = null, matchId = matchId,
        previousRound = completedRound, currentRound = doc.currentRound,
        currentMultiplier = nextMultiplier,
      )
    }

    override suspend fun advanceAfterGeneralPass(
      matchId: String,
      closedRound: Int,
      finalScores: Map<String, Int>,
      winnerIds: List<String>,
    ): AdvanceResult {
      passAdvanceCalls.incrementAndGet()
      if (doc.isComplete) return AdvanceResult.alreadyComplete(matchId, doc.currentRound)
      if (doc.currentRound != closedRound) {
        return AdvanceResult.alreadyAdvanced(matchId, doc.currentRound)
      }
      if (closedRound + 1 > doc.maxRounds) {
        doc = doc.copy(
          status = MatchDoc.STATUS_COMPLETE,
          winnerIds = winnerIds,
          finalScores = finalScores,
          completedRound = closedRound,
          version = doc.version + 1,
          cardLog = emptyList(),
          lastCardSeat = null,
          cardPhase = null,
          biddingLog = emptyList(),
        )
        return AdvanceResult(
          advanced = true, reason = null, matchId = matchId,
          previousRound = closedRound, currentRound = closedRound,
          completed = true, currentMultiplier = doc.currentMultiplier,
        )
      }
      val nextMultiplier = generalPassMultiplier(doc.currentMultiplier, doc.scoringMode)
      doc = doc.copy(
        currentRound = doc.currentRound + 1,
        currentMultiplier = nextMultiplier,
        version = doc.version + 1,
        biddingOpen = true,
        bids = emptyMap(),
        lastBidSeat = null,
        cardLog = emptyList(),
        lastCardSeat = null,
        cardPhase = null,
        biddingLog = emptyList(),
      )
      return AdvanceResult(
        advanced = true, reason = null, matchId = matchId,
        previousRound = closedRound, currentRound = doc.currentRound,
        currentMultiplier = nextMultiplier,
      )
    }

    override suspend fun extendMatchRounds(
      matchId: String,
      completedRound: Int,
      reason: String,
    ): ExtendResult {
      extendCalls.incrementAndGet()
      doc = doc.copy(
        maxRounds = doc.maxRounds + 1,
        extendedRounds = doc.extendedRounds + completedRound,
        version = doc.version + 1,
      )
      return ExtendResult(
        extended = true, reason = reason, matchId = matchId,
        completedRound = completedRound, maxRounds = doc.maxRounds,
      )
    }

    override suspend fun endMatch(
      matchId: String,
      completedRound: Int,
      finalScores: Map<String, Int>,
      winnerIds: List<String>,
    ): EndMatchResult {
      endCalls.incrementAndGet()
      doc = doc.copy(
        status = MatchDoc.STATUS_COMPLETE,
        finalScores = finalScores,
        winnerIds = winnerIds,
        completedRound = completedRound,
        version = doc.version + 1,
      )
      return EndMatchResult(
        complete = true, reason = null, matchId = matchId,
        completedRound = completedRound, winnerIds = winnerIds, finalScores = finalScores,
      )
    }

    override suspend fun leaveMatch(matchId: String): LeaveResult {
      val uid = doc.seats.entries.firstOrNull { it.value == "uid-a" }?.value ?: ""
      val seatId = doc.uidToSeat(uid) ?: ""
      doc = doc.copy(
        leftUids = doc.leftUids + uid,
        botSeats = doc.botSeats + seatId,
        version = doc.version + 1,
      )
      return LeaveResult(matchId, seatId, uid, KozCharge.Koz2, doc.version)
    }
  }

  /** players/{uid}.currentMatchId, in memory. */
  private class FakePlayers : PlayerPort {
    val current = mutableMapOf<String, String?>()
    val clears = AtomicInteger(0)

    override suspend fun currentMatchId(uid: String): String? = current[uid]

    override suspend fun setCurrentMatchId(uid: String, matchId: String?) {
      if (matchId == null) clears.incrementAndGet()
      current[uid] = matchId
    }

    // S19's lastSeenAt: the payload is pinned in PlayerServiceTest against the
    // merge seam; a wiring test observes the cadence through the Heartbeat
    // seam instead, so this stands in without a profile doc.
    override suspend fun markActive(uid: String) = Unit
  }

  /** Records the S19 cadence's lifetime: started for a uid, then stopped. */
  private class RecordingHeartbeat : Heartbeat {
    val started = mutableListOf<String>()
    var stopped = 0

    override fun start(scope: CoroutineScope, uid: String): Job {
      started += uid
      // Cancelling the returned job is how the view model stops the cadence,
      // so a completion handler is the stop signal.
      return Job().also { it.invokeOnCompletion { stopped++ } }
    }
  }

  // ==========================================================================
  //  S2 (A8.8 / Q48) — Sa'ayda extension online
  // ==========================================================================

  /**
   * A complete fast-round fixture at [roundNumber]: all four seats estimate 4,
   * the caller holds all trumps and wins all 13 tricks (bid 4 ≠ won 13 → fail),
   * everyone else bids 4 and wins 0 (fail) → all four fail → Sa'ayda.
   * The cardLog holds all 52 plays so [maybeCompleteRound] can score and
   * advance, which triggers [store.extendMatchRounds].
   */
  private data class FastRoundCompleteFixture(
    val outcome: BiddingOutcome,
    val hands: Map<String, List<Card>>,
    val table: TableState,
    val doc: MatchDoc,
  )

  private fun fastRoundCompleteFixture(roundNumber: Int): FastRoundCompleteFixture {
    // Deal deterministically and play all 13 tricks. Every seat bids 4; we
    // need every seat to miss its bid (won != 4) → all fail → Sa'ayda.
    // Must match the FakeMatchStore's seed so the local hand lines up.
    val hands = Dealer.dealHands(1L)
    var state = initFastRound(round = roundNumber, dealer = seats.first(), seats = seats)
    var outcome: BiddingOutcome? = null
    var guard = 0
    while (state.subPhase != BiddingPhase.DONE && guard < 40) {
      guard++
      val actor = state.waitingFor ?: error("no seat waiting in ${state.subPhase}")
      val intent = BiddingIntent.FinalEstimate(actor, 4)
      state = when (val result = emit(state, intent)) {
        is EmitResult.Applied -> result.state
        is EmitResult.Completed -> {
          outcome = result.outcome
          result.state
        }
        is EmitResult.Rejected -> error("fast-round estimate rejected: ${result.reason}")
        is EmitResult.GeneralPass -> error("unexpected general pass in fast round")
      }
    }
    val done = outcome ?: error("fast-round auction never closed")

    // Play all 13 tricks through the real table engine.
    var table = initTable(
      RoundCfg(
        round = roundNumber,
        trump = done.trump,
        callerId = done.callerId,
        withPlayers = done.withPlayers,
        estimates = done.estimates,
        dashCallers = done.dashCallers,
        leaderId = done.leaderId,
        riskId = done.riskPlayerId,
        hands = hands,
      ),
      seats,
    )
    val cardLog = mutableListOf<CardLogEntry>()
    repeat(13) {
      repeat(4) {
        val seat = table.turn ?: error("no turn to play in trick")
        val hand = table.cfg.hands.getValue(seat)
        val led = table.ledSuit
        val card = if (led == null) hand.first() else hand.firstOrNull { it.suit == led } ?: hand.first()
        cardLog.add(CardLogEntry(seat, StoredCard.fromEngine(card), roundNumber))
        table = when (val r = emitPlay(table, PlayCard(seat, card))) {
          is PlayEmit.Applied -> r.state
          is PlayEmit.Rejected -> error("fixture play rejected for $seat: ${r.reason}")
        }
        if (table.phase == TablePhase.RESOLVING) table = resolveTrick(table)
      }
    }

    // All four bid 4. If anyone won exactly 4, this isn't a Sa'ayda — pick a
    // different seed. (Verified: seed 1L yields all-miss at rounds 14, 18, 19.)
    val tricksWon = table.tricksWon
    val allMiss = seats.all { tricksWon.getValue(it) != 4 }
    if (!allMiss) {
      error("seed 1L did not produce a Sa'ayda at round $roundNumber: tricksWon=$tricksWon bids=4/4/4/4")
    }

    val doc = MatchDoc(
      roomId = "room-1",
      players = seatUids.values.toList(),
      status = MatchDoc.STATUS_STARTING,
      currentRound = roundNumber,
      maxRounds = 18,
      extendedRounds = emptyList(),
      dealer = seatUids.getValue(seats.first()),
      turn = null,
      seats = seatUids,
      version = 1,
      biddingOpen = false,
      bids = done.estimates.entries.associate { it.key to (it.value as Int?) },
      lastBidSeat = seats.last(),
      cardLog = cardLog.toList(),
      lastCardSeat = cardLog.last().seatId,
      cardPhase = MatchDoc.CARD_PHASE_PLAY,
      biddingLog = emptyList(),
      gameState = GameState(initialized = true, dealtRound = roundNumber),
    )
    return FastRoundCompleteFixture(done, hands, table, doc)
  }

  /**
   * Round 14 Sa'ayda online: scoreAndAdvance sees isSaayda=true, computeRoundExtension
   * extends (in window), store.extendMatchRounds is called, maxRounds → 19.
   *
   * O-19 gate 7: the same advance must persist the Sa'ayda's armed multiplier,
   * so a client that cold-starts after the extension replays round 15 at ×2 —
   * the extension alone is not the continuity proof.
   */
  @Test
  fun onlineSaaydaRound14ExtendsAndPersists() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(1))
      store.doc = fastRoundCompleteFixture(14).doc
      val vm = makeVm(seatUids.getValue("p1"), Dispatchers.Main)
      vm.start("m-extend-14")
      broadcast("m-extend-14")

      // One extension write for the round-14 Sa'ayda, ceiling 18 → 19.
      assertEquals("round-14 Sa'ayda extends online", 1, store.extendCalls.get())
      assertEquals("maxRounds raised to 19", 19, store.doc.maxRounds)
      assertEquals("extendedRounds records round 14", listOf(14), store.doc.extendedRounds)

      // And one advance write, carrying the Sa'ayda ladder step ×1 → ×2.
      assertEquals("the Sa'ayda advanced the round", 15, store.doc.currentRound)
      assertEquals(
        "the Sa'ayda's ×2 is persisted, not only held by this client",
        2,
        store.doc.currentMultiplier,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Re-delivery of the same terminal round-14 document is idempotent: the
   * extension write fires exactly once and maxRounds stays 19.
   */
  @Test
  fun onlineSaaydaRound14RedeliveryIsIdempotent() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(1))
      store.doc = fastRoundCompleteFixture(14).doc.copy(maxRounds = 18)
      val vm = makeVm(seatUids.getValue("p1"), Dispatchers.Main)
      vm.start("m-idem-14")
      broadcast("m-idem-14")
      assertEquals(1, store.extendCalls.get())
      assertEquals(19, store.doc.maxRounds)
      assertEquals(listOf(14), store.doc.extendedRounds)

      // Same document again — no second extension write.
      broadcast("m-idem-14")
      assertEquals(1, store.extendCalls.get())
      assertEquals(19, store.doc.maxRounds)
      assertEquals(listOf(14), store.doc.extendedRounds)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /**
   * Round 13 (before the FULL window) Sa'ayda: no extension, maxRounds stays 18.
   */
  @Test
  fun onlineSaaydaRound13BeforeWindowNoExtend() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      players = FakePlayers()
      store = FakeMatchStore(Dealer.dealHands(1))
      // Round 13 is a normal round, not fast — build the doc directly.
      store.doc = MatchDoc(
        roomId = "room-1",
        players = seatUids.values.toList(),
        status = MatchDoc.STATUS_STARTING,
        currentRound = 13,
        maxRounds = 18,
        extendedRounds = emptyList(),
        dealer = seatUids.getValue("p1"),
        turn = null,
        seats = seatUids,
        version = 1,
        biddingOpen = false,
        bids = mapOf("p1" to 4, "p2" to 4, "p3" to 4, "p4" to 4),
        lastBidSeat = "p4",
        cardLog = emptyList(),
        lastCardSeat = null,
        cardPhase = null,
        biddingLog = emptyList(),
        gameState = GameState(initialized = true, dealtRound = 13),
      )
      val vm = makeVm(seatUids.getValue("p1"), Dispatchers.Main)
      vm.start("m-nowindow-13")
      broadcast("m-nowindow-13")

      // Pre-window: no extension write at all.
      assertEquals("pre-window Sa'ayda never extends", 0, store.extendCalls.get())
      assertEquals("maxRounds stays 18", 18, store.doc.maxRounds)
    } finally {
      Dispatchers.resetMain()
    }
  }
}
