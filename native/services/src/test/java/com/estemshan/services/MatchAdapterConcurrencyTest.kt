package com.estemshan.services

import com.estemshan.engine.BiddingState
import com.estemshan.engine.TableState
import com.estemshan.services.model.GameState
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.RoundResultEntry
import com.estemshan.services.session.GameSessionPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The deferred-MatchScheduler race, pinned.
 *
 * The production scheduler (CoroutineMatchScheduler) launches the reconnect
 * action on a SHARED DISPATCHER POOL; a Firestore listener delivers on its own
 * thread; subscribe/unsubscribe run on the caller's. MatchAdapter's
 * Subscription holds only plain fields, so before the fix those threads raced
 * them directly. This suite pins the failure modes that produced:
 *
 *  1. a reconnect re-attaching a registration AFTER — or into the middle of —
 *     a teardown, leaving a live listener on a matchId nobody listens to
 *     anymore; one that can never be torn down, since close() already removed
 *     the entry;
 *  2. an INLINE scheduler (the test default, and the seam's documented
 *     contract) running the action DURING schedule(), which left the single
 *     pendingReconnect slot holding the completed task and stranded every
 *     reconnect after the first;
 *  3. two callers racing the subscription map into two real listeners for one
 *     matchId.
 *
 * Every test is deterministic: latches and barriers, never sleeps. The racy
 * ones assert invariants that hold under ALL interleavings once the monitor is
 * in place — they fail on the pre-fix code whenever the scheduler lands a bad
 * interleaving, and cannot fail on the fixed code no matter how the threads
 * are scheduled, which is what makes them safe to run in CI.
 */
class MatchAdapterConcurrencyTest {

  // ── hermetic harness ───────────────────────────────────────────────

  /** Inert: the subscription half this suite exercises touches no engine
   *  state, so a delivery replays into absent engines and no-ops. */
  private class FakeGameSession : GameSessionPort {
    override fun getPlayers(): List<String> = emptyList()
    override fun getRoundNumber(): Int = 1
    override fun getMaxRounds(): Int = 18
    override fun getDealer(): String? = null
    override fun setDealer(seatId: String?) {}
    override fun getTurn(): String? = null
    override fun setTurn(seatId: String?) {}
    override fun nextRound(): Int = 2
    override fun getPlayState(): TableState? = null
    override fun updatePlayState(state: TableState) {}
    override fun getBiddingState(): BiddingState? = null
    override fun updateBiddingState(state: BiddingState) {}
    override fun getMatchScores(): Map<String, Int> = emptyMap()
    override fun setMatchScores(scores: Map<String, Int>) {}
    override fun recordRoundResult(entry: RoundResultEntry) {}
    override fun getLastRoundResult(): RoundResultEntry? = null
    override fun setWinnerIds(ids: List<String>) {}
    override fun getWinnerIds(): List<String> = emptyList()
    override fun isMatchComplete(): Boolean = false
  }

  /** Counts every registration's lifecycle and hands a test the delivery
   *  sinks so it can drive a snapshot or an error from ANY thread — exactly
   *  what a real snapshot listener does, which is the whole point. */
  private class RecordingFactory : MatchListenerFactory {
    val listenCalls = AtomicInteger(0)
    val created = AtomicInteger(0)
    val cancelled = AtomicInteger(0)

    @Volatile private var snapshotSink: ((MatchDoc?) -> Unit)? = null
    @Volatile private var errorSink: ((FirestoreError) -> Unit)? = null

    override fun listen(
      matchId: String,
      onSnapshot: (MatchDoc?) -> Unit,
      onError: (FirestoreError) -> Unit,
    ): MatchListenerRegistration {
      listenCalls.incrementAndGet()
      snapshotSink = onSnapshot
      errorSink = onError
      created.incrementAndGet()
      return RecordingRegistration { cancelled.incrementAndGet() }
    }

    fun deliver(doc: MatchDoc?) = snapshotSink?.invoke(doc)
    fun fail(err: FirestoreError) = errorSink?.invoke(err)

    /** Every registration was cancelled exactly once: nothing outlived its
     *  subscription. */
    fun assertNoLeaks(label: String) {
      assertEquals("$label: every registration was torn down", created.get(), cancelled.get())
    }
  }

  private class RecordingRegistration(val onCancel: () -> Unit) : MatchListenerRegistration {
    private val cancelled = AtomicBoolean(false)
    override fun cancel() {
      if (cancelled.compareAndSet(false, true)) onCancel()
    }
  }

  /** CoroutineMatchScheduler as MatchAdapter sees it: the action is NOT run
   *  inline — it is handed back so a test can fire it on the thread of its
   *  choosing, the way a dispatcher pool would. */
  private class DeferredScheduler : MatchScheduler {
    val scheduled = AtomicInteger(0)
    @Volatile private var pendingAction: (() -> Unit)? = null

    /** The queued action, captured BEFORE any race so firing it does not
     *  depend on this fake's own mutable state. */
    fun captured(): () -> Unit = pendingAction ?: error("no reconnect was queued")

    override fun schedule(delayMillis: Long, action: () -> Unit): MatchSchedulerTask {
      scheduled.incrementAndGet()
      pendingAction = action
      return MatchSchedulerTask { pendingAction = null }
    }
  }

  private class CollectingListener : MatchSnapshotListener {
    val snapshots = CopyOnWriteArrayList<MatchSnapshot>()
    override fun onSnapshot(snapshot: MatchSnapshot) {
      snapshots.add(snapshot)
    }
  }

  private fun emptyDoc(version: Int): MatchDoc = MatchDoc(
    roomId = "room",
    players = emptyList(),
    status = MatchDoc.STATUS_STARTING,
    currentRound = 1,
    maxRounds = 18,
    extendedRounds = emptyList(),
    dealer = "",
    turn = null,
    seats = emptyMap(),
    version = version,
    biddingOpen = true,
    bids = emptyMap(),
    lastBidSeat = null,
    cardLog = emptyList(),
    lastCardSeat = null,
    cardPhase = null,
    biddingLog = emptyList(),
    gameState = GameState.NOT_DEALT,
  )

  private val retryable = FirestoreError("unavailable", "transient")

  // ── the race: reconnect on another thread vs teardown on this one ──

  /**
   * The sequenced half: the backoff fires on a dispatcher thread and runs to
   * completion, THEN the last subscriber tears the match down. The
   * reconnect's registration must be cancelled by that teardown, not orphaned
   * — a plain (non-volatile) registration field can leave the caller reading a
   * stale slot and skipping the cancel.
   */
  @Test
  fun reconnectCompletesBeforeTeardown_registrationIsCancelledNotOrphaned() {
    val session = FakeGameSession()
    val factory = RecordingFactory()
    val scheduler = DeferredScheduler()
    val adapter = MatchAdapter(session, factory, scheduler)

    val handle = adapter.subscribeToMatch("race-sequenced", CollectingListener())
    assertEquals("subscribe attaches exactly one listener", 1, factory.listenCalls.get())

    factory.fail(retryable)
    assertEquals("exactly one reconnect is queued", 1, scheduler.scheduled.get())

    // The backoff tick lands on a pool thread and re-attaches.
    val pool = Executors.newSingleThreadExecutor()
    val fired = CountDownLatch(1)
    try {
      pool.execute {
        try { scheduler.captured()() } finally { fired.countDown() }
      }
      assertTrue("the reconnect ran on the pool thread", fired.await(10, TimeUnit.SECONDS))
    } finally {
      pool.shutdown()
    }
    assertEquals("the reconnect re-attached the one real listener", 2, factory.listenCalls.get())

    handle.unsubscribe()
    factory.assertNoLeaks("sequenced")
    assertNull("no listener callback threw", adapter.lastListenerError)
  }

  /**
   * The racy half, repeated: the backoff fires on one thread and the last
   * subscriber unsubscribes on another, released together by a barrier so
   * every interleaving is reachable. Whatever order they land in, a
   * registration is either never attached (teardown first) or attached then
   * cancelled (reconnect first) — never left live behind a removed entry.
   */
  @Test
  fun reconnectRacesTeardown_neverLeaksARegistration() {
    val iterations = 400
    val pool = Executors.newFixedThreadPool(2)
    try {
      repeat(iterations) { i ->
        val factory = RecordingFactory()
        val scheduler = DeferredScheduler()
        val adapter = MatchAdapter(FakeGameSession(), factory, scheduler)
        val handle = adapter.subscribeToMatch("race-$i", CollectingListener())
        factory.fail(retryable)
        val fireReconnect = scheduler.captured()

        val barrier = CyclicBarrier(2)
        val done = CountDownLatch(2)
        pool.execute {
          barrier.await()
          try { fireReconnect() } finally { done.countDown() }
        }
        pool.execute {
          barrier.await()
          try { handle.unsubscribe() } finally { done.countDown() }
        }
        assertTrue("both race threads finished on iteration $i", done.await(10, TimeUnit.SECONDS))

        factory.assertNoLeaks("iteration $i")
        assertNull("no listener callback threw on iteration $i", adapter.lastListenerError)
      }
    } finally {
      pool.shutdown()
    }
  }

  /**
   * Teardown wins cleanly: the pending task is cancelled, and even an action
   * that had ALREADY STARTED (a coroutine job cancel is cooperative) attaches
   * nothing, because nobody is listening anymore.
   */
  @Test
  fun teardownBeatsPendingReconnect_firedActionReattachesNothing() {
    val factory = RecordingFactory()
    val scheduler = DeferredScheduler()
    val adapter = MatchAdapter(FakeGameSession(), factory, scheduler)

    val handle = adapter.subscribeToMatch("race-teardown-first", CollectingListener())
    factory.fail(retryable)
    val fireReconnect = scheduler.captured()

    handle.unsubscribe()
    fireReconnect() // the action had already started; it must still attach nothing

    assertEquals("only the original registration was ever attached", 1, factory.listenCalls.get())
    factory.assertNoLeaks("teardown-first")
    assertNull("no listener callback threw", adapter.lastListenerError)
  }

  /**
   * Two retryable failures from two threads at once queue exactly ONE
   * resubscribe — never a second stacked behind the first.
   */
  @Test
  fun concurrentFailures_queueExactlyOneReconnect() {
    val factory = RecordingFactory()
    val scheduler = DeferredScheduler()
    val adapter = MatchAdapter(FakeGameSession(), factory, scheduler)
    adapter.subscribeToMatch("dedupe", CollectingListener())

    val pool = Executors.newFixedThreadPool(2)
    val barrier = CyclicBarrier(2)
    val done = CountDownLatch(2)
    try {
      repeat(2) {
        pool.execute {
          barrier.await()
          try { factory.fail(retryable) } finally { done.countDown() }
        }
      }
      assertTrue("both failures landed", done.await(10, TimeUnit.SECONDS))
    } finally {
      pool.shutdown()
    }

    assertEquals("exactly one reconnect was queued", 1, scheduler.scheduled.get())
    assertEquals("exactly one registration", 1, factory.listenCalls.get())
    assertNull("no listener callback threw", adapter.lastListenerError)
  }

  /**
   * The map race: many callers subscribing to the SAME matchId at once open
   * exactly ONE real listener — never two.
   */
  @Test
  fun concurrentSubscribersForOneMatch_openExactlyOneRealListener() {
    val callers = 8
    val factory = RecordingFactory()
    val adapter = MatchAdapter(FakeGameSession(), factory)
    val handles = ConcurrentLinkedQueue<MatchSubscriptionHandle>()

    val pool = Executors.newFixedThreadPool(callers)
    val barrier = CyclicBarrier(callers)
    val done = CountDownLatch(callers)
    try {
      repeat(callers) {
        pool.execute {
          barrier.await()
          try { handles.add(adapter.subscribeToMatch("shared", CollectingListener())) } finally {
            done.countDown()
          }
        }
      }
      assertTrue("every caller subscribed", done.await(10, TimeUnit.SECONDS))
    } finally {
      pool.shutdown()
    }

    assertEquals("ONE real listener per matchId no matter how many callers", 1, factory.listenCalls.get())
    assertEquals("every caller got a handle", callers, handles.size)
    handles.forEach { it.unsubscribe() }
    factory.assertNoLeaks("shared")
    assertNull("no listener callback threw", adapter.lastListenerError)
  }

  // ── the inline scheduler: the action runs DURING schedule() ────────

  /**
   * The default [MatchScheduler.Immediate] runs the reconnect action inline.
   * A single pendingReconnect field would be left holding the COMPLETED task
   * afterwards and strand every later failure behind a dead slot; the dedupe
   * must instead free itself when the action runs.
   */
  @Test
  fun inlineScheduler_eachFailureStillQueuesAReconnect() {
    val factory = RecordingFactory()
    val adapter = MatchAdapter(FakeGameSession(), factory) // Immediate scheduler
    val handle = adapter.subscribeToMatch("inline", CollectingListener())
    assertEquals("subscribe attaches exactly one listener", 1, factory.listenCalls.get())

    // First failure: Immediate runs the reconnect DURING schedule().
    factory.fail(retryable)
    assertEquals("the first reconnect re-attached", 2, factory.listenCalls.get())

    // A SECOND failure must still queue — nothing may strand the slot.
    factory.fail(retryable)
    assertEquals("the second reconnect re-attached too", 3, factory.listenCalls.get())

    handle.unsubscribe()
    factory.assertNoLeaks("inline")
    assertNull("no listener callback threw", adapter.lastListenerError)
  }

  // ── the refactor's behavior contract: late joiners ─────────────────

  /**
   * A caller arriving after a delivery gets the published document
   * immediately, without a second real listener.
   */
  @Test
  fun lateJoiner_receivesThePublishedSnapshotImmediately() {
    val factory = RecordingFactory()
    val adapter = MatchAdapter(FakeGameSession(), factory)
    adapter.subscribeToMatch("late", CollectingListener())

    val doc = emptyDoc(version = 5)
    factory.deliver(doc)

    val second = CollectingListener()
    adapter.subscribeToMatch("late", second)

    assertEquals("no second real listener for a late joiner", 1, factory.listenCalls.get())
    assertEquals("the late joiner got exactly one delivery", 1, second.snapshots.size)
    assertEquals("the late joiner sees the published document", doc, second.snapshots.single().match)
    assertNull("with no error", second.snapshots.single().error)
  }

  /**
   * A caller arriving after a non-retryable failure learns it immediately
   * instead of waiting on a reconnect that will never run.
   */
  @Test
  fun lateJoiner_afterTerminalError_receivesItImmediately() {
    val factory = RecordingFactory()
    val adapter = MatchAdapter(FakeGameSession(), factory)
    adapter.subscribeToMatch("terminal", CollectingListener())

    val fatal = FirestoreError("permission-denied", "not allowed")
    factory.fail(fatal)

    val second = CollectingListener()
    adapter.subscribeToMatch("terminal", second)

    assertEquals("the late joiner got exactly one delivery", 1, second.snapshots.size)
    assertEquals("the terminal error is delivered immediately", fatal, second.snapshots.single().error)
    assertNull("nothing was ever published", second.snapshots.single().match)
    assertNull("no listener callback threw", adapter.lastListenerError)
  }
}
