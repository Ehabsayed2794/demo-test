package com.estemshan.game.ui.onlinematch

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.DECK_SUITS
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.Dealer
import com.estemshan.engine.emit
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.initTable
import com.estemshan.engine.nextSeat
import com.estemshan.engine.resolveTrick
import com.estemshan.services.MatchAdapter
import com.estemshan.services.FirestoreError
import com.estemshan.services.Heartbeat
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
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.StoredCard
import com.estemshan.services.model.SubmitBidResult
import com.estemshan.services.model.SubmitBiddingActionResult
import com.estemshan.services.model.SubmitCardResult
import com.estemshan.services.session.MatchStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  private companion object {
    /** Comfortably past [OpponentAwayTracker.AWAY_THRESHOLD_MILLIS]. */
    const val STALE_MARGIN = 15_000L
  }

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
  ): OnlineMatchViewModel {
    val session = GameSession()
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

  /** Hand the store's current document to every subscriber, then let the
   *  view models' pipelines drain — what a Firestore tick followed by the
   *  collector looks like from the outside. */
  /** Needs the [TestScope] receiver for [advanceUntilIdle], like every
   *  call site it is invoked from. */
  private suspend fun TestScope.broadcast(matchId: String) {
    factory.deliver(matchId, store.snapshot())
    advanceUntilIdle()
  }

  private fun biddingOf(state: MatchUiState): BiddingState =
    (state as? MatchUiState.Bidding)?.state
      ?: error("expected Bidding, was ${state::class.simpleName}")

  private fun tableOf(state: MatchUiState): TableState =
    (state as? MatchUiState.Table)?.state
      ?: error("expected Table, was ${state::class.simpleName}")

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

      var now = 0L
      val vm = makeVm(seatUids.getValue(ourSeat), Dispatchers.Main, clock = { now })
      vm.start("m-away")
      broadcast("m-away")

      assertEquals("mid-table, the leader to act", leader, tableOf(vm.state.value).turn)
      assertFalse("a fresh document shows no hint", vm.opponentAway.value)
      val tableAfterFirst = (vm.state.value as? MatchUiState.Table)?.state
      val docAfterFirst = "cards=${store.doc.cardLog.size} bids=${store.doc.biddingLog.size} " +
        "round=${store.doc.currentRound} turn=${store.doc.turn} phase=${store.doc.cardPhase}"
      val phaseAfterFirst = "state=${vm.state.value::class.simpleName} " +
        "turn=${tableAfterFirst?.turn} phase=${tableAfterFirst?.phase}"

      // Only the clock moves, past the tracker's staleness threshold.
      now = OpponentAwayTracker.AWAY_THRESHOLD_MILLIS + STALE_MARGIN
      broadcast("m-away")
      val diagState = vm.state.value
      val diagTable = (diagState as? MatchUiState.Table)?.state
      val docAfterSecond = "cards=${store.doc.cardLog.size} bids=${store.doc.biddingLog.size} " +
        "round=${store.doc.currentRound} turn=${store.doc.turn} phase=${store.doc.cardPhase}"
      assertTrue(
        "DIAG away=${vm.opponentAway.value} state=${diagState::class.simpleName} " +
          "turn=${diagTable?.turn} phase=${diagTable?.phase} ourSeat=$ourSeat leader=$leader " +
          "doc1=[$docAfterFirst] doc2=[$docAfterSecond] after1=[$phaseAfterFirst] " +
          "inputs=${vm.awayInputs}",
        vm.opponentAway.value,
      )

      // A version bump carrying no content change is not play.
      store.bumpVersion()
      broadcast("m-away")
      assertTrue("a content-free version bump does not clear the hint",
        vm.opponentAway.value,
      )

      // A card lands on the document: progress, and a fresh baseline.
      store.submitCard("m-away", store.peekHand(leader)[1])
      broadcast("m-away")
      assertFalse("fresh activity clears the hint", vm.opponentAway.value)
      // ...and the SAME clock value does not flip it again, because the
      // baseline moved with the card.
      now = OpponentAwayTracker.AWAY_THRESHOLD_MILLIS + STALE_MARGIN
      broadcast("m-away")
      assertFalse("the restarted clock is measured from the progress",
        vm.opponentAway.value,
      )
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

      var now = 0L
      val vm = makeVm(seatUids.getValue(ourSeat), Dispatchers.Main, clock = { now })
      vm.start("m-end")
      broadcast("m-end")
      assertFalse(vm.opponentAway.value)

      now = OpponentAwayTracker.AWAY_THRESHOLD_MILLIS + STALE_MARGIN
      broadcast("m-end")
      assertTrue("the turn went stale", vm.opponentAway.value)

      // The match completes: the hint has nothing left to say.
      store.doc = store.doc.copy(
        status = MatchDoc.STATUS_COMPLETE,
        finalScores = seatUids.keys.associateWith { 10 },
        winnerIds = listOf("p1"),
        completedRound = 1,
        version = store.doc.version + 1,
      )
      broadcast("m-end")
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
   * In-memory [MatchStore]: one mutable match document and one deal, plus a
   * counter for every write path. It stays honest by mirroring what the
   * real MatchService's transactions conclude — bids overwrite the seat's
   * slot and bump the version, the deal is idempotent, a card is appended
   * once and attributed to the one seat whose hand still holds it (the real
   * service resolves the actor from the signed-in user; the deal resolves
   * it here, since a card is unique in the deck).
   */
  private class FakeMatchStore(
    private val hands: Map<String, List<Card>> = Dealer.dealHands(7),
  ) : MatchStore {

    val dealCalls = AtomicInteger(0)
    val redundantDealCalls = AtomicInteger(0)
    val bidCalls = AtomicInteger(0)
    val actionCalls = AtomicInteger(0)
    val cardCalls = AtomicInteger(0)
    val advanceCalls = AtomicInteger(0)
    val extendCalls = AtomicInteger(0)
    val endCalls = AtomicInteger(0)

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

    override suspend fun submitBid(matchId: String, seatId: String, bid: Int): SubmitBidResult {
      bidCalls.incrementAndGet()
      val bids = doc.bids + (seatId to bid)
      val allSubmitted = doc.seats.isNotEmpty() && doc.seats.keys.all { bids[it] != null }
      doc = doc.copy(
        bids = bids,
        lastBidSeat = seatId,
        biddingOpen = !allSubmitted,
        version = doc.version + 1,
      )
      return SubmitBidResult(matchId, seatId, bid, doc.version, !allSubmitted, allSubmitted)
    }

    override suspend fun submitBiddingAction(
      matchId: String,
      action: BiddingActionInput,
    ): SubmitBiddingActionResult {
      actionCalls.incrementAndGet()
      val seat = doc.seats.entries.firstOrNull { it.value == "uid-a" }?.key ?: "p1"
      doc = doc.copy(
        biddingLog = doc.biddingLog + action.toLogEntry(seat, doc.currentRound),
        version = doc.version + 1,
      )
      return SubmitBiddingActionResult(
        matchId, seat, action.actionType, doc.version, doc.biddingLog.size,
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

    override suspend fun advanceToNextRound(matchId: String, completedRound: Int): AdvanceResult {
      advanceCalls.incrementAndGet()
      doc = doc.copy(
        currentRound = doc.currentRound + 1,
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
}
