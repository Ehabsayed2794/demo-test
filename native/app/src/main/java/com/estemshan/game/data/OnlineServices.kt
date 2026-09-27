package com.estemshan.game.data

import com.estemshan.services.CoroutineMatchScheduler
import com.estemshan.services.MatchScheduler
import com.estemshan.services.session.AuthPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The online stack's wiring point: the process-scoped pieces a screen builds
 * its MatchService/RoomService/MatchAdapter from. Only the two that have a
 * consumer today are here — the service factories arrive with the screens
 * that use them, so nothing here is dead code in the meantime.
 *
 * [scheduler] is [CoroutineMatchScheduler], not [MatchScheduler.Immediate]:
 * Immediate is the *test* default by design, and shipping it would make every
 * reconnect fire instantly instead of backing off. CoroutineMatchScheduler
 * only honors the delay MatchAdapter computes, so the backoff policy stays in
 * exactly one place.
 *
 * CONSTRAINT — [auth] must stay `by lazy`. It builds an [AuthRepository] over
 * a FirebaseAuthBackend, whose constructor default reads [FirebaseModule.auth]
 * — a computed getter over the Firebase singleton that MainActivity
 * initializes from an Android Context. If [auth] were eager, merely
 * referencing [OnlineServices] (say, `OnlineServices.scheduler`) in a JVM unit
 * test would touch Firebase before any Context exists and throw. The
 * scheduler carries no such hazard, so it initializes eagerly: it only builds
 * a coroutine scope.
 */
object OnlineServices {

  /** Outlives any one screen, so a pending reconnect survives navigation. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** The deferred scheduler — [MatchScheduler.Immediate] is the *test* default
   *  by design; production wires this one to get the real exponential backoff
   *  rather than an instant reconnect. */
  val scheduler: MatchScheduler = CoroutineMatchScheduler(scope)

  /** The services layer's auth seam over the app's repository. Lazy: reading
   *  it constructs a FirebaseAuthBackend, which must not happen until the app
   *  has initialized Firebase. */
  val auth: AuthPort by lazy { AuthPortAdapter(AuthRepository(FirebaseAuthBackend())) }
}
