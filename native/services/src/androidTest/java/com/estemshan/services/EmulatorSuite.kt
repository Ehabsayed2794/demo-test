package com.estemshan.services

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Emulator plumbing for the :services instrumented suite (plan §2D, S20).
 *
 * `firestore.rules` requires `request.auth != null` on every match write
 * (matches/create, card/bid submissions, advance, extend, endMatch, the
 * rematch vote) — that much is common. But the `hands/{seatId}` READ rule
 * is stricter still: `parentMatch().seats.get(seatId) == request.auth.uid`.
 * One signed-in user therefore cannot read more than their own seat's
 * hand, so four clients in one process need FOUR separate auth contexts —
 * not one shared one.
 *
 * Hence [client]: each seat gets its own named `FirebaseApp` (the SDK's
 * documented secondary-app pattern), each with its own `FirebaseAuth`
 * signed in anonymously against the Auth emulator, and its own
 * `FirebaseFirestore` carrying that token. That is exactly the trust
 * boundary the rules were written for: four real uids, four real tokens.
 *
 * 10.0.2.2 is the host loopback as seen from inside the Android emulator
 * (same constant as :app's FirebaseModule); both ports match firebase.json.
 */
object EmulatorSuite {

  const val HOST = "10.0.2.2"
  const val FIRESTORE_PORT = 8080
  const val AUTH_PORT = 9099

  /**
   * The emulator ignores the project id for routing, but the REST clear
   * endpoint is keyed on it, so it has to be stable across the run.
   * Matches the project the existing rules-emulator CI job uses.
   */
  const val PROJECT_ID = "demo-test-ci"

  /** No google-services.json in :services (and no plugin): options are
   *  built in code, exactly like :app's FirebaseModule emulator branch. */
  private fun options() = FirebaseOptions.Builder()
    .setApplicationId("1:000000000000:android:0000000000000000000000")
    .setApiKey("emulator-dummy-key-not-a-secret")
    .setProjectId(PROJECT_ID)
    .build()

  /** Distinct app names per client per test — initializeApp throws on a
   *  duplicate name, and each test mints its own four. */
  private val counter = AtomicInteger(0)

  /** One signed-in Firestore instance for one seat. */
  data class ClientFirebase(
    val db: FirebaseFirestore,
    val uid: String,
  )

  suspend fun client(context: Context, tag: String): ClientFirebase {
    val name = "emu-${counter.incrementAndGet()}-$tag"
    val app = FirebaseApp.initializeApp(context.applicationContext, options(), name)
    // useEmulator() must precede any document access on each instance.
    val auth = FirebaseAuth.getInstance(app).apply { useEmulator(HOST, AUTH_PORT) }
    val db = FirebaseFirestore.getInstance(app).apply { useEmulator(HOST, FIRESTORE_PORT) }
    val uid = auth.signInAnonymously().await().user!!.uid
    return ClientFirebase(db, uid)
  }

  /**
   * Wipe both emulators between tests. Synchronous HTTP on a background
   * thread (the emulator REST API is not a Firestore Task, so calling it
   * on the instrumentation thread would trip NetworkOnMainThread). This
   * is a structured reset, not a timed wait: it returns only once the
   * emulator has acknowledged the delete.
   */
  fun reset() = runBlocking(Dispatchers.IO) {
    // Firestore: the documented clear-database endpoint.
    runCatching {
      clear("/emulator/v1/projects/$PROJECT_ID/databases/(default)/documents")
    }
    // Auth: stale users would not actually collide (every client mints a
    // fresh anonymous uid), but a clean slate keeps the suite
    // order-independent.
    runCatching { clear("/emulator/v1/projects/$PROJECT_ID/accounts") }
  }

  private fun clear(path: String) {
    val conn = (URL("http://$HOST/$path").openConnection() as HttpURLConnection).apply {
      requestMethod = "DELETE"
      connectTimeout = 15_000
      readTimeout = 15_000
      doInput = true
    }
    try {
      conn.responseCode // forces the request to complete before returning
    } finally {
      conn.disconnect()
    }
  }
}
