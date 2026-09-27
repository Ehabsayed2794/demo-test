package com.estemshan.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CoroutineMatchScheduler] is the production counterpart of
 * [MatchScheduler.Immediate]: where Immediate runs the action instantly and
 * ignores the delay (the deterministic test default), this one must ACTUALLY
 * honor the delay — it is what turns MatchAdapter's reconnect backoff into a
 * real wait instead of an instant resubscribe. The backoff MATH is not here;
 * it lives in MatchAdapter.scheduleReconnect (base shl attempt, capped), so
 * these tests pin only the scheduler's own contract on virtual time, never
 * wall-clock: an action stays pending until its delay elapses and fires
 * exactly then, and cancelling the returned task stops a pending resubscribe
 * from ever firing into a match nobody listens to anymore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineMatchSchedulerTest {

  /**
   * The scheduler pinned to the test's virtual clock. [backgroundScope] dies
   * with the test, and [StandardTestDispatcher] built on [testScheduler] is
   * what makes the launch and its `delay` advance only when the test moves
   * them — no thread, no wall clock.
   */
  private fun TestScope.scheduler(): MatchScheduler =
    CoroutineMatchScheduler(backgroundScope, StandardTestDispatcher(testScheduler))

  @Test
  fun theActionStaysPendingUntilItsDelayElapsesExactly() = runTest {
    val scheduler = scheduler()
    var ran = false
    scheduler.schedule(1000) { ran = true }

    // The launch is queued on the test dispatcher, so nothing has run yet.
    assertFalse(ran)
    // advanceTimeBy is the only thing moving the virtual clock; 999 ms is
    // deliberately one millisecond short of the delay.
    advanceTimeBy(999)
    assertFalse(ran)
    advanceTimeBy(1)
    // Exactly at the delay the one-shot fires — never early, never late.
    assertTrue(ran)
  }

  @Test
  fun cancellingBeforeTheDelayStopsTheActionFromEverFiring() = runTest {
    val scheduler = scheduler()
    var ran = false
    val task = scheduler.schedule(1000) { ran = true }
    assertFalse(ran)

    // Cancel while the action is still pending, then run the clock well past
    // the whole delay: advanceUntilIdle would fire it if the job were live.
    task.cancel()
    advanceUntilIdle()
    assertFalse(ran)
  }

  @Test
  fun cancellingAnAlreadyFiredTaskIsAHarmlessNoOp() = runTest {
    val scheduler = scheduler()
    var ran = false
    val task = scheduler.schedule(500) { ran = true }

    advanceTimeBy(500)
    assertTrue(ran)
    // The job has already completed, so cancel() must not throw. MatchAdapter
    // holds this task in pendingReconnect and cancels it on close(), which can
    // legitimately happen after the resubscribe already ran and cleared it.
    task.cancel()
    advanceUntilIdle()
    assertTrue(ran)
  }

  @Test
  fun cancelIsIdempotent() = runTest {
    val scheduler = scheduler()
    var ran = false
    val task = scheduler.schedule(1000) { ran = true }

    // Job.cancel() is idempotent and the task's cancel() is a thin wrapper over
    // it, so a second call — close() racing an unsubscribe — must be harmless.
    task.cancel()
    task.cancel()
    advanceUntilIdle()
    assertFalse(ran)
  }

  @Test
  fun twoTasksAreIndependentCancellingOneLeavesTheOtherFiring() = runTest {
    val scheduler = scheduler()
    var firstRan = false
    var secondRan = false
    val first = scheduler.schedule(1000) { firstRan = true }
    val second = scheduler.schedule(2000) { secondRan = true }

    first.cancel()
    advanceTimeBy(1000)
    // The cancelled task stayed dead and the survivor is still pending.
    assertFalse(firstRan)
    assertFalse(secondRan)
    advanceTimeBy(1000)
    // The survivor fires at ITS OWN delay, unaffected by its neighbour.
    assertFalse(firstRan)
    assertTrue(secondRan)
  }

  @Test
  fun theActionFiresExactlyOnce() = runTest {
    val scheduler = scheduler()
    var fires = 0
    scheduler.schedule(1000) { fires += 1 }

    advanceTimeBy(1000)
    assertEquals(1, fires)
    // Pumping the clock far past the delay and to quiescence must not re-run
    // the one-shot — a resubscribe that fired twice would double-attach a
    // Firestore listener.
    advanceTimeBy(10_000)
    advanceUntilIdle()
    assertEquals(1, fires)
  }
}
