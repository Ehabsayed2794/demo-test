package com.estemshan.game.data

import com.estemshan.engine.GameSession
import com.estemshan.services.CoroutineMatchScheduler
import com.estemshan.services.Heartbeat
import com.estemshan.services.MatchAdapter
import com.estemshan.services.MatchListenerFactory
import com.estemshan.services.MatchScheduler
import com.estemshan.services.MatchService
import com.estemshan.services.PlayerHeartbeat
import com.estemshan.services.PlayerPort
import com.estemshan.services.PlayerService
import com.estemshan.services.RoomPort
import com.estemshan.services.RoomService
import com.estemshan.services.session.AuthPort
import com.estemshan.services.session.GameSessionBridge
import com.estemshan.services.session.GameSessionPort
import com.estemshan.services.session.MatchStore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await

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

  /**
   * The player-profile seam — players/{uid}.currentMatchId, the reconnect
   * entry point S17's OnlineMatchViewModel reads on a cold start. Lazy for
   * the same reason as [auth]: it reads [FirebaseModule.db].
   */
  /**
   * The write reaches Firestore through an injected function rather than a
   * bare FirebaseFirestore because that function IS the hermetic boundary —
   * "the profile write cannot clobber anything" is the heartbeat's contract,
   * and a JVM test asserts it against the payload it receives.
   */
  val players: PlayerPort by lazy {
    val db = FirebaseModule.db
    PlayerService(
      profileRef = { db.collection("players").document(it) },
      mergeOwnProfile = { uid, fields ->
        db.collection("players").document(uid).set(fields, SetOptions.merge()).await()
      },
    )
  }

  /**
   * S19's lastSeenAt cadence — the one activity signal the frozen rules let a
   * player emit, on their OWN doc and nowhere else. Lazy for the same reason
   * as [auth]: [PlayerHeartbeat] closes over [players], which reads
   * [FirebaseModule.db].
   */
  val heartbeat: Heartbeat by lazy { PlayerHeartbeat(players) }

  /**
   * The Room screen's match writer. Lazy for the same reason as [auth]:
   * [MatchService] reads [FirebaseModule.db], and [MatchAdapter]'s listener
   * factory closes over it too.
   *
   * [session] is the single [GameSession] this process mirrors an online
   * match into, reached through the [GameSessionBridge] held in
   * [sessionPort]. It is exposed (not private) so S17's
   * OnlineMatchViewModel drives the ONE instance the adapter replays into —
   * a second graph would hold a second store and the two would never agree.
   * [matches] stays a [MatchStore] rather than the concrete [MatchService]
   * for the same reason the adapter's seams are interfaces: it is the
   * boundary a hermetic JVM test can stand in for, since this repo ships no
   * [com.google.firebase.firestore.FirebaseFirestore] fake by design.
   *
   * [adapter] gets the REAL [MatchListenerFactory.firestore], not
   * [MatchListenerFactory.Unavailable]: Unavailable is the test default, and
   * shipping it would make every room-started match a listener that never
   * listens. [scheduler] is the deferred one, for the same reason as above.
   */
  val session: GameSession by lazy { GameSession() }

  private val sessionPort: GameSessionPort by lazy { GameSessionBridge(session) }

  val adapter: MatchAdapter by lazy {
    MatchAdapter(
      session = sessionPort,
      listen = MatchListenerFactory.firestore(FirebaseModule.db),
      scheduler = scheduler,
    )
  }

  val matches: MatchStore by lazy {
    MatchService(
      db = FirebaseModule.db,
      auth = auth,
      session = sessionPort,
      adapter = adapter,
      profileMatchSync = players::setCurrentMatchId,
    )
  }

  /**
   * The Real Lobby's room seam. Lazy for the same reason as [auth]: building
   * a [RoomService] reads [FirebaseModule.db], a computed getter over the
   * Firebase singleton that only [com.estemshan.game.MainActivity]
   * initializes from an Android Context.
   *
   * [matchStarter] is [MatchService.startMatch] — the atomic room↔match
   * write [RoomService]'s own maybeStartMatch() reaches for once every seat
   * is ready and the acting uid is the room's creator. S15 shipped a
   * NotImplementedError here because the lobby only calls create/join/leave
   * and could never reach it; the Room screen (S16) can, so this is now the
   * real thing.
   */
  val rooms: RoomPort by lazy {
    RoomService(
      db = FirebaseModule.db,
      auth = auth,
      matchStarter = matches::startMatch,
    )
  }
}
