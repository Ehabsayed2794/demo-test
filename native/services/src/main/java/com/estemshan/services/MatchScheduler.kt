package com.estemshan.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The production [MatchScheduler]: a delayed one-shot run as a coroutine on
 * [scope], honoring the delay and running the action on [dispatcher].
 *
 * [MatchScheduler.Immediate] stays the test default — deterministic by
 * construction — and its own doc points here: wire this scheduler in
 * production to get the real exponential backoff instead of an instant
 * reconnect. The backoff *math* is not here: [MatchAdapter.scheduleReconnect]
 * computes the delay (`RECONNECT_BASE_MS shl attempt`, capped) and this type
 * merely honors it, so the reconnect policy stays in exactly one place.
 *
 * The returned [MatchSchedulerTask] cancels the launched job, so a
 * subscription that goes away mid-backoff stops the pending resubscribe
 * instead of firing it into a match nobody listens to anymore. Cancelling an
 * already-fired task is a no-op, which makes the returned task idempotent.
 *
 * [dispatcher] is overridable so a test can pin the scheduler to virtual time
 * rather than wall-clock — the same injectable-clock idiom the app's bot
 * driver uses for its cadence.
 */
class CoroutineMatchScheduler(
  private val scope: CoroutineScope,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MatchScheduler {

  override fun schedule(delayMillis: Long, action: () -> Unit): MatchSchedulerTask {
    val job: Job = scope.launch(dispatcher) {
      delay(delayMillis)
      action()
    }
    return MatchSchedulerTask { job.cancel() }
  }
}
