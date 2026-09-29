package com.estemshan.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * S19's lastSeenAt cadence: while a player sits in a match, their own profile
 * is re-stamped on an interval. It is the honest, rules-permitted half of
 * "opponent appears away" — the frozen rules let a player WRITE lastSeenAt on
 * players/{uid} and let nobody else read it, so this is the one activity
 * signal a client may emit, and it stays strictly on the OWN doc.
 *
 * [None] is the test default, exactly as [MatchScheduler.Immediate] is for the
 * reconnect scheduler: a periodic loop has no place in a hermetic JVM test,
 * and wiring one would put a self-perpetuating `delay` on the test clock that
 * [kotlinx.coroutines.test.advanceUntilIdle] would never let finish.
 */
interface Heartbeat {

  /**
   * Begin beating for [uid] in [scope]. Best-effort by construction: a failed
   * or cancelled tick never propagates into the scope. The returned [Job] IS
   * the cadence — cancelling it stops it, and cancelling an already-stopped
   * one is a no-op.
   */
  fun start(scope: CoroutineScope, uid: String): Job

  object None : Heartbeat {
    override fun start(scope: CoroutineScope, uid: String): Job = Job()
  }
}

/**
 * The production cadence over [PlayerPort.markActive]. The interval is the
 * freshness window the profile's lastSeenAt ends up meaning; every tick is
 * fire-and-forget so a failed write never disturbs the match.
 */
class PlayerHeartbeat(
  private val players: PlayerPort,
  private val intervalMillis: Long = INTERVAL_MILLIS,
  private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Heartbeat {

  override fun start(scope: CoroutineScope, uid: String): Job = scope.launch(dispatcher) {
    while (isActive) {
      delay(intervalMillis)
      runCatching { players.markActive(uid) }
    }
  }

  private companion object {
    /** Well inside any plausible "is this player still here" window, and far
     *  enough apart to be a negligible write load on the profile doc. */
    const val INTERVAL_MILLIS = 30_000L
  }
}
