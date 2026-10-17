package com.estemshan.game.ui.onlinematch

import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.GameSession
import com.estemshan.engine.InactiveTakeover
import com.estemshan.services.FirestoreError
import com.estemshan.services.Heartbeat
import com.estemshan.services.DecisionTimeoutPort
import com.estemshan.services.MatchAdapter
import com.estemshan.services.MatchListenerFactory
import com.estemshan.services.MatchListenerRegistration
import com.estemshan.services.PlayerPort
import com.estemshan.services.SeatReclaimPort
import com.estemshan.services.model.AdvanceResult
import com.estemshan.services.model.BiddingActionInput
import com.estemshan.services.model.DealResult
import com.estemshan.services.model.EndMatchResult
import com.estemshan.services.model.ExtendResult
import com.estemshan.services.model.GameState
import com.estemshan.services.model.HandDoc
import com.estemshan.services.model.LeaveResult
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.StoredCard
import com.estemshan.services.model.SubmitBidResult
import com.estemshan.services.model.SubmitBiddingActionResult
import com.estemshan.services.model.SubmitCardResult
import com.estemshan.services.session.GameSessionBridge
import com.estemshan.services.session.MatchStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * S73 (RD19) — the reclaim poll, the borrowed-seat signal, and nothing else.
 *
 * WHAT THIS PINS, and why it is a client property: the takeover marker lives in
 * a field no client validator names and its private document lives in a
 * subcollection the frozen rules deny outright, so a client can only ASK the
 * server. [SeatReclaimPort] is that ask, and the :services suite
 * SeatReclaimResponseTest pins how a response becomes a `Reclaim`. What is
 * left — and all of it is client logic — is what the view model DOES with each
 * answer: how long it waits, whether it re-arms on the SERVER's number, when it
 * gives up, and when a document change voids the wait. Those are pinned here the same way S61
 * pinned the timeout alarm: a scripted port, the real [MatchAdapter], the real
 * engines, virtual time, no emulator and no sleeps.
 *
 * THE THREE PROPERTIES:
 *
 *  1. The poll arms for OUR borrowed seat only. The callable derives the seat
 *     from the verified uid, so asking for anyone else can only ever be a
 *     NOT_OWNER — and the chip still has to show every borrowed seat, so the
 *     published set and the armed poll are deliberately separate things.
 *
 *  2. The wait converges on the server's `retryAfterMillis`, never on a client
 *     guess. Only the FIRST arm is estimated (one bot window — the server
 *     floors its own answer at the same window, so an estimate is late rather
 *     than early), and every later arm is the number the server handed back.
 *
 *  3. Every terminal outcome exits, and the next document decides. A returned
 *     seat, a seat nobody borrowed, a denial and the attempt/transient ceilings
 *     all end the loop; because every bot play for a borrowed seat is a
 *     document write, a new snapshot re-arms a fresh poll. Bounded, yet it
 *     cannot strand a player out of their own seat.
 *
 * NOT ASSERTED HERE, deliberately: no reclaim ever produces a departure record
 * (RD31). That is absence-of-difference, and it has exactly one pinned home —
 * the `// RD31 revisit` marker in the functions suite — so none is repeated in
 * this file. There is also no "I'm back" screen anywhere in this story: the
 * player's return is the signal, and the tests below watch the seat come back
 * by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeatReclaimPollTest {

  private val seatUids: Map<String, String> =
    linkedMapOf("p1" to "uid-a", "p2" to "uid-b", "p3" to "uid-c", "p4" to "uid-d")

  private lateinit var factory: BroadcastFactory
  private lateinit var store: PollStore

  /** One device's whole online stack: its OWN [GameSession] and OWN
   *  [MatchAdapter], sharing the ONE [factory] and ONE [store] that stand in
   *  for the single Firestore document every seat reads. */
  private fun makeVm(
    uid: String,
    worker: CoroutineDispatcher,
    reclaims: SeatReclaimPort,
    clock: () -> Long,
  ): OnlineMatchViewModel {
    val session = GameSession()
    val adapter = MatchAdapter(GameSessionBridge(session), factory)
    return OnlineMatchViewModel(
      session = session,
      adapter = adapter,
      store = store,
      players = FakePlayers(),
      uid = { uid },
      worker = worker,
      clock = clock,
      heartbeat = Heartbeat.None,
      timeouts = NotActiveTimeouts,
      reclaims = reclaims,
    )
  }

  /** A dealt-fast-round document with the bot holding [seats]. Round 14 so
   *  the auction is open and the table is waiting on p1's bid. */
  private fun borrowedDoc(vararg seats: String, version: Int = 1): MatchDoc {
    val takeover = InactiveTakeover(takenOverAtMillis = 0L, roundAtTakeover = 14, trickAtTakeover = 0)
    return MatchDoc(
      roomId = "room-1",
      players = seatUids.values.toList(),
      status = MatchDoc.STATUS_STARTING,
      currentRound = 14,
      maxRounds = 18,
      extendedRounds = emptyList(),
      dealer = seatUids.getValue("p1"),
      turn = null,
      seats = seatUids,
      version = version,
      biddingOpen = true,
      bids = emptyMap(),
      lastBidSeat = null,
      cardLog = emptyList(),
      lastCardSeat = null,
      cardPhase = null,
      biddingLog = emptyList(),
      gameState = GameState.NOT_DEALT,
      inactiveTakeovers = seats.associateWith { takeover },
    )
  }

  /** Deliver [store]'s document WITHOUT advancing the scheduler's clock, then
   * run what fell due. [advanceUntilIdle] would carry every pending alarm
   * forward the instant a document lands, and the whole point of these tests
   * is which alarm fires at which virtual millisecond.
   */
  private fun TestScope.deliverNow(matchId: String) {
    factory.deliver(matchId, store.snapshot())
    runCurrent()
  }

  /** Stop the VM and drain the scheduler, so [runTest]'s epilogue cannot
   *  resume a poll into a Main dispatcher that has been reset. Every
   *  assertion precedes it, so the extra virtual time is unobservable. */
  private fun TestScope.stopAndDrain(vm: OnlineMatchViewModel) {
    advanceUntilIdle()
    vm.stop()
    advanceUntilIdle()
  }

  /** The poll's own first wait: one bot window, the same floor the server uses
   *  for every `retryAfterMillis` it hands back. */
  private val firstArmMillis = 2_000L

  // ═══════════════════════════════════════════════════════════════════
  // Property 1 — armed for our own borrowed seat, and only that
  // ═══════════════════════════════════════════════════════════════════

  /** With no marker on the document, there is nothing to reclaim: the poll
   *  never calls the callable and the chip set is empty. This is the
   *  marker-absent path — a borrowed seat must not change how a normal table
   *  behaves, and an idle poll is already a behavior change. */
  @Test
  fun nothingIsPolledWhileNoSeatIsBorrowed() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc()
      val reclaims = scripted(SeatReclaimPort.Reclaim.Returned)
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      assertEquals("no borrowed seat is published", emptySet<String>(), vm.borrowedSeats.value)
      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("nothing is asked of the server", 0, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** An opponent's borrowed seat is visible and never polled: the set the chip
   *  reads covers every seat, while the poll stays owner-scoped. */
  @Test
  fun anOpponentsBorrowedSeatIsShownButNeverPolled() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p2")
      val reclaims = scripted(SeatReclaimPort.Reclaim.Returned)
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      assertEquals(setOf("p2"), vm.borrowedSeats.value)
      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("a seat we do not own is not ours to reclaim", 0, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** Two seats borrowed at once: the chip shows both, the poll asks about ours
   *  and names our match — never another seat, never another document. */
  @Test
  fun thePollAsksOnlyAboutOurOwnBorrowedSeat() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1", "p3")
      val reclaims = scripted(SeatReclaimPort.Reclaim.HumanHoldsSeat)
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      assertEquals(setOf("p1", "p3"), vm.borrowedSeats.value)
      advanceTimeBy(2_100)
      runCurrent()
      assertEquals(listOf("m-rc"), reclaims.matchIds)
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // Property 2 — the first arm is estimated, every later arm is the server's
  // ═══════════════════════════════════════════════════════════════════

  /** Nothing reaches the callable before one bot window elapses, and the first
   *  ask lands on it — late rather than early, since a reclaim is only due once
   *  the bot's trick is down. */
  @Test
  fun theFirstAskWaitsOneBotWindow() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(SeatReclaimPort.Reclaim.Returned)
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(1_950)
      runCurrent()
      assertEquals("no ask before the window elapses", 0, reclaims.calls.get())

      advanceTimeBy(100)
      runCurrent()
      assertEquals(1, reclaims.calls.get())
      assertEquals(listOf(firstArmMillis), reclaims.callTimes)
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** Acceptance: the poll re-arms on the SERVER's `retryAfterMillis`, not on
   *  the bot window and not on a new snapshot. Seven seconds is seven seconds
   *  here — the client neither shortens it (a busy server would then be polled
   *  every 2 s) nor lengthens it (a seat would come back later than the
   *  document allowed). */
  @Test
  fun aTrickStillRunningRearmsOnTheServersWindow() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(
        SeatReclaimPort.Reclaim.WaitingForTrick(7_000),
        SeatReclaimPort.Reclaim.Returned,
      )
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(2_000)
      runCurrent()
      assertEquals("the first ask landed on the estimate", 1, reclaims.calls.get())

      // 6.9 s of the server's 7 s: the re-armed ask has not fallen due.
      advanceTimeBy(6_900)
      runCurrent()
      assertEquals("the server's window is honored, not shortened", 1, reclaims.calls.get())

      advanceTimeBy(200)
      runCurrent()
      assertEquals("re-armed on the server's number", 2, reclaims.calls.get())
      assertEquals(listOf(2_000L, 9_000L), reclaims.callTimes)

      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("a returned seat does not keep asking", 2, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** A `WaitingForTrick` is not a failure: it does not consume the transient
   *  budget and its own window is honored verbatim, however unusual — 500 ms
   *  here, one second of it earlier, and the poll neither collapses it to a
   *  bot window nor pads it into a backoff step. */
  @Test
  fun aWindowThatIsNotAFailureCostsNothingFromTheRetryBudget() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(
        SeatReclaimPort.Reclaim.Retryable,
        SeatReclaimPort.Reclaim.Retryable,
        SeatReclaimPort.Reclaim.WaitingForTrick(500),
        SeatReclaimPort.Reclaim.Returned,
      )
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      // 2 s estimate, then the 500 ms / 1 s backoff ladder for the two blips.
      advanceTimeBy(3_500)
      runCurrent()
      assertEquals("two blips, each backed off, then the real ask", 3, reclaims.calls.get())

      // The server's own 500 ms — not another backoff step, not a bot window.
      advanceTimeBy(500)
      runCurrent()
      assertEquals(listOf(2_000L, 2_500L, 3_500L, 4_000L), reclaims.callTimes)

      advanceTimeBy(60_000)
      runCurrent()
      assertEquals(4, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** A blip never disables the poll: each failure backs off and the next ask
   *  still lands, so a flaky network costs patience rather than the seat. */
  @Test
  fun aTransientFailureBacksOffWithoutDisablingThePoll() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(
        SeatReclaimPort.Reclaim.Retryable,
        SeatReclaimPort.Reclaim.Retryable,
        SeatReclaimPort.Reclaim.Returned,
      )
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(2_025)
      runCurrent()
      assertEquals(1, reclaims.calls.get())

      advanceTimeBy(500)
      runCurrent()
      assertEquals("the first backoff step", 2, reclaims.calls.get())

      advanceTimeBy(1_000)
      runCurrent()
      assertEquals("the second, and the third ask succeeded", 3, reclaims.calls.get())

      advanceTimeBy(60_000)
      runCurrent()
      assertEquals(3, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ═══════════════════════════════════════════════════════════════════
  // Property 3 — every terminal outcome exits, and the next document decides
  // ═══════════════════════════════════════════════════════════════════

  /** A seat nobody borrowed is settled: the poll stops rather than guessing
   *  that the marker on the document is stale. The document is the authority,
   *  and it will arrive without the marker. */
  @Test
  fun aSeatNobodyBorrowedEndsThePoll() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(SeatReclaimPort.Reclaim.HumanHoldsSeat)
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(2_100)
      runCurrent()
      assertEquals(1, reclaims.calls.get())

      advanceTimeBy(120_000)
      runCurrent()
      assertEquals("a terminal answer is not retried", 1, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** A denial is permanent for this poll's lifetime — never retried into a
   *  loop — yet it strands nobody: the next document write re-arms a fresh
   *  one. That is the whole bounded-yet-self-healing argument, in one test. */
  @Test
  fun aDenialEndsThePollAndTheNextDocumentRearsIt() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(
        SeatReclaimPort.Reclaim.Denied,
        SeatReclaimPort.Reclaim.Denied,
      )
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(20_000)
      runCurrent()
      assertEquals("denied once, not retried", 1, reclaims.calls.get())

      // A bot play for the borrowed seat is a document write, and this is the
      // snapshot that write produces.
      store.doc = borrowedDoc("p1", version = 2)
      deliverNow("m-rc")
      advanceTimeBy(2_100)
      runCurrent()
      assertEquals("the newer document re-armed the ask", 2, reclaims.calls.get())

      advanceTimeBy(120_000)
      runCurrent()
      assertEquals(2, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** The cap is a ceiling on one poll's lifetime, not a limit on the reclaim:
   *  twelve owed tricks in the script still yield exactly eight asks, and the
   *  ninth never happens. */
  @Test
  fun thePollStopsAtItsAttemptCeiling() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(
        *List(12) { SeatReclaimPort.Reclaim.WaitingForTrick(100) }.toTypedArray(),
      )
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("bounded at MAX_RECLAIM_ATTEMPTS", 8, reclaims.calls.get())

      advanceTimeBy(120_000)
      runCurrent()
      assertEquals("and it stays bounded", 8, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** Consecutive failures give up too, at their own ceiling: seven asks (the
   *  sixth blip plus the seventh that trips the cap), then silence — while the
   *  document keeps moving and keeps re-arming this poll outside this loop. */
  @Test
  fun aPersistentFailureGivesUpAtTheTransientCeiling() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(*List(20) { SeatReclaimPort.Reclaim.Retryable }.toTypedArray())
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(120_000)
      runCurrent()
      assertEquals("six tolerated blips, then the seventh closes the poll", 7, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** At most one poll is live for the whole match, and a newer delivery cancels
   *  the wait armed against the older one. Both deliveries land at the same
   *  virtual instant and each arms a 2 s wait, so a stale poll that survived
   *  would fire here as well — exactly one ask proves it was cancelled, not
   *  merely superseded. */
  @Test
  fun aNewerDeliveryCancelsTheStalePoll() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(SeatReclaimPort.Reclaim.HumanHoldsSeat)
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()

      deliverNow("m-rc")
      store.doc = borrowedDoc("p1", version = 2)
      deliverNow("m-rc")

      advanceTimeBy(2_100)
      runCurrent()
      assertEquals("only the re-armed poll fired", 1, reclaims.calls.get())
      stopAndDrain(vm)
    } finally {
      Dispatchers.resetMain()
    }
  }

  /** The seat coming back is a document write, and that write alone clears the
   *  chip and tears the poll down — no screen, no button, no local guess. */
  @Test
  fun theMarkerLeavingTheDocumentClearsTheChipAndStopsThePoll() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      factory = BroadcastFactory()
      store = PollStore(Dealer.dealHands(14))
      store.doc = borrowedDoc("p1")
      val reclaims = scripted(SeatReclaimPort.Reclaim.WaitingForTrick(2_000))
      val vm = makeVm("uid-a", Dispatchers.Main, reclaims) { currentTime }
      vm.start("m-rc")
      advanceUntilIdle()
      deliverNow("m-rc")

      advanceTimeBy(2_025)
      runCurrent()
      assertEquals(1, reclaims.calls.get())
      assertEquals(setOf("p1"), vm.borrowedSeats.value)

      // The reclaim the server committed for the trick that finished.
      store.doc = borrowedDoc(version = 2)
      deliverNow("m-rc")
      assertEquals("the chip is gone with the marker", emptySet<String>(), vm.borrowedSeats.value)

      advanceTimeBy(60_000)
      runCurrent()
      assertEquals("and nothing is asked once the seat is ours again", 1, reclaims.calls.get())

      stopAndDrain(vm)
      assertEquals("a stopped match publishes no borrowed seat", emptySet<String>(), vm.borrowedSeats.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ==========================================================================
  //  The hermetic stand-ins
  // ==========================================================================

  /**
   * One Firestore document, broadcast to every subscriber of its matchId — the
   * contract a real `addSnapshotListener` satisfies and the one the adapter's
   * ref-counting assumes. Delivery is synchronous and single-threaded, which
   * is strictly stronger than the real thing and keeps these deterministic.
   */
  private class BroadcastFactory : MatchListenerFactory {
    private val sinks = mutableMapOf<String, MutableList<(MatchDoc?) -> Unit>>()

    override fun listen(
      matchId: String,
      onSnapshot: (MatchDoc?) -> Unit,
      onError: (FirestoreError) -> Unit,
    ): MatchListenerRegistration {
      val list = sinks.getOrPut(matchId) { mutableListOf() }
      list.add(onSnapshot)
      return MatchListenerRegistration { list.remove(onSnapshot) }
    }

    fun deliver(matchId: String, doc: MatchDoc?) {
      sinks[matchId]?.toList()?.forEach { it(doc) }
    }
  }

  /**
   * A [SeatReclaimPort] that answers from a script and records every call, with
   * the scheduler's virtual time at each one. The virtual time is what lets
   * [aTrickStillRunningRearmsOnTheServersWindow] pin the window the poll
   * actually armed at rather than merely that it fired again. Like
   * [FakeMatchStore] standing in for Firestore, this stands in for the callable
   * exactly as its contract reads; the instrumented suite covers the real one.
   */
  private class ScriptedReclaims(
    vararg scripted: SeatReclaimPort.Reclaim,
  ) : SeatReclaimPort {
    private val script = scripted.toList()
    val calls = AtomicInteger(0)
    val matchIds = mutableListOf<String>()
    val callTimes = mutableListOf<Long>()
    var clock: () -> Long = { 0L }

    override suspend fun reclaim(matchId: String): SeatReclaimPort.Reclaim {
      matchIds.add(matchId)
      callTimes.add(clock())
      val index = calls.getAndIncrement()
      return script.getOrElse(index) { script.last() }
    }
  }

  /**
   * A [ScriptedReclaims] whose recorded call times read the scheduler's virtual
   * clock — the SAME clock [makeVm] is fed, so an armed wait and an observed
   * fire time cannot disagree. Two clocks (one injected, one the scheduler's)
   * is how a timing test passes for the wrong reason.
   */
  private fun TestScope.scripted(vararg scripted: SeatReclaimPort.Reclaim): ScriptedReclaims {
    val now = { currentTime }
    return ScriptedReclaims(*scripted).apply { clock = now }
  }

  /** [makeVm]'s default resolver port: these tests never arm a timeout ask
   *  they assert on, and NOT_ACTIVE ends one that does. */
  private object NotActiveTimeouts : DecisionTimeoutPort {
    override suspend fun resolve(matchId: String) = DecisionTimeoutPort.Resolution.NotActive
  }

  /**
   * In-memory [MatchStore]: one mutable document and one deal. Only the reads
   * and the deal this file's pipeline reaches are real — no local action is
   * submitted here, so a write path throwing on contact is the honest
   * answer: this suite would then be pinning something it has not proven.
   */
  private class PollStore(private val hands: Map<String, List<Card>>) : MatchStore {

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

    override suspend fun startMatch(roomId: String): String = "match-from-$roomId"

    override suspend fun loadMatch(matchId: String): MatchDoc? = doc

    override suspend fun loadHand(matchId: String, seatId: String, round: Int): HandDoc? {
      if (doc.gameState.dealtRound < round) return null
      val hand = hands[seatId] ?: return null
      return HandDoc(seatId, round, hand.map { StoredCard.fromEngine(it) })
    }

    override suspend fun dealRound(matchId: String, roundNumber: Int): DealResult {
      // Like the real transaction: a redundant attempt reads the committed
      // dealtRound and no-ops, so the re-delivery bindSeat performs does not
      // look like a second deal.
      if (doc.gameState.dealtRound >= roundNumber) {
        return DealResult.alreadyDealt(matchId, roundNumber)
      }
      doc = doc.copy(gameState = GameState(initialized = true, dealtRound = roundNumber))
      return DealResult(true, null, matchId, roundNumber, doc.seats.keys.toList())
    }

    private fun notReached(name: String): Nothing =
      error("$name is not part of the reclaim poll's path — this suite submits nothing")

    override suspend fun submitBid(matchId: String, seatId: String, bid: Int): SubmitBidResult =
      notReached("submitBid")

    override suspend fun submitBiddingAction(
      matchId: String,
      action: BiddingActionInput,
    ): SubmitBiddingActionResult = notReached("submitBiddingAction")

    override suspend fun submitCard(matchId: String, card: Card): SubmitCardResult =
      notReached("submitCard")

    override suspend fun advanceToNextRound(matchId: String, completedRound: Int): AdvanceResult =
      notReached("advanceToNextRound")

    override suspend fun extendMatchRounds(
      matchId: String,
      completedRound: Int,
      reason: String,
    ): ExtendResult = notReached("extendMatchRounds")

    override suspend fun endMatch(
      matchId: String,
      completedRound: Int,
      finalScores: Map<String, Int>,
      winnerIds: List<String>,
    ): EndMatchResult = notReached("endMatch")

    override suspend fun leaveMatch(matchId: String): LeaveResult = notReached("leaveMatch")
  }

  /** players/{uid}.currentMatchId, in memory. */
  private class FakePlayers : PlayerPort {
    private val current = mutableMapOf<String, String?>()

    override suspend fun currentMatchId(uid: String): String? = current[uid]

    override suspend fun setCurrentMatchId(uid: String, matchId: String?) {
      current[uid] = matchId
    }

    override suspend fun markActive(uid: String) = Unit
  }
}
