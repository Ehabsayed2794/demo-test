package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.services.model.SEAT_IDS
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S61 Stage 2 — the timeout resolver reached end to end from the Android
 * client, against the real Firebase Functions + Firestore + Auth emulators.
 *
 * This is the ONLY suite where the compiled Kotlin/JS callable runs under
 * the Functions emulator AND the client is real Android, so it is the only
 * place three claims can be proven together:
 *
 *   (e) the client really invokes the resolver — auth, routing, trigger
 *       discovery, and the response mapping all work on a device;
 *   (f) the decision and the timeout counter commit together — after one
 *       APPLIED call the match has moved AND the counter doc exists;
 *   (d) concurrent invocations do not double-count or double-move — two
 *       racing calls on an expired decision leave the counter at exactly 1.
 *
 * The retry semantics (a)/(b)/(c) have their own coverage in
 * :app's OnlineMatchViewModelTest, where the alarm loop is driven against
 * a scripted port; this suite's job is the path those tests stand in for.
 *
 * TIMING. The deadline the resolver expires is the document's `updatedAt`
 * plus the configured timer plus the bidding offset. The shortest legal
 * timer is 5 s (RD21's 5..20 window), so the shortest bidding deadline is
 * 10 s — the suite waits on the SERVER's own `retryAfterMillis`, never a
 * fixed guess, which is the same "bounded wait on a real condition"
 * convention the rest of the instrumented suite uses.
 *
 * READINESS. The Functions emulator's HTTP port answers before firebase-tools
 * has discovered the Kotlin/JS bundle's triggers; until then every call
 * returns `functions/not-found`. No port probe can see that, so
 * [awaitTrigger] polls the callable itself and treats NOT_FOUND as "still
 * loading" — the same contract tests/e2e/native-functions-smoke.test.cjs
 * established for S42.
 *
 * ISOLATION. [EmulatorSuite.reset] wipes Firestore and Auth between tests;
 * every match takes a fresh room code and match id, so nothing stable can
 * collide. The counter reads go through the TIMED-OUT seat's own client on
 * purpose — that is the only token firestore.rules lets read that doc, so
 * the rules are exercised, not bypassed.
 */
@RunWith(AndroidJUnit4::class)
class TimeoutEmulatorTest {

  private companion object {
    const val CALLABLE_NAME = "resolveDecisionTimeout"

    /** RD21's shortest legal timer — keeps the expiry this suite waits on
     *  as short as the rules allow. */
    const val SHORTEST_LEGAL_TIMER = 5

    /** The bidding deadline the resolver derives: timer + RD21's 5 s
     *  bidding offset. An upper bound on the retry any early call reports. */
    const val WINDOW_MS = (SHORTEST_LEGAL_TIMER + 5) * 1_000L

    /** Past the deadline, short of anything else timing out. */
    const val EXPIRY_MARGIN_MS = 1_000L

    const val TRIGGER_TIMEOUT_MS = 90_000L
    const val TRIGGER_POLL_MS = 500L
  }

  @Before
  fun wipeEmulatorsBetweenTests() {
    EmulatorSuite.reset()
  }

  private suspend fun seating(): Map<String, EmulatorSuite.ClientFirebase> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return SEAT_IDS.associateWith { EmulatorSuite.client(context, "s61_$it") }
  }

  private fun scripted(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    decisionTimerSeconds: Int = SHORTEST_LEGAL_TIMER,
  ) = ScriptedMatch(seating, seed = 11L, decisionTimerSeconds = decisionTimerSeconds)

  /**
   * Poll the callable until the emulator has discovered it. The empty
   * payload reaches the resolver and is rejected for a missing matchId,
   * which is exactly the "the trigger is live" signal; only NOT_FOUND means
   * discovery has not finished. Bounded, and never a silent pass.
   */
  private suspend fun awaitTrigger(functions: FirebaseFunctions) {
    val callable = functions.getHttpsCallable(CALLABLE_NAME)
    val deadline = System.currentTimeMillis() + TRIGGER_TIMEOUT_MS
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
      try {
        callable.call(emptyMap<String, Any>()).await()
        return
      } catch (e: FirebaseFunctionsException) {
        if (e.code != FirebaseFunctionsException.Code.NOT_FOUND) return
        last = e
      } catch (e: Throwable) {
        last = e
      }
      delay(TRIGGER_POLL_MS)
    }
    error(
      "$CALLABLE_NAME was never registered on the Functions emulator within " +
        "$TRIGGER_TIMEOUT_MS ms (last: ${last?.message}). The port was open, so " +
        "the Kotlin/JS module likely failed to load — read the emulator log.",
    )
  }

  private suspend fun resolve(
    functions: FirebaseFunctions,
    matchId: String,
  ): DecisionTimeoutPort.Resolution = CallableDecisionTimeout(functions).resolve(matchId)

  /** The count stored for [uid], or null when the counter doc does not exist. */
  private suspend fun storedCount(
    db: FirebaseFirestore,
    matchId: String,
    uid: String,
  ): Int? {
    val snap = db.collection("matches").document(matchId)
      .collection("timeoutCounts").document(uid).get().await()
    if (!snap.exists()) return null
    val value = snap.get("count")
    assertFalse(
      "the stored count must be a number, was ${value?.javaClass?.name}",
      value !is Number,
    )
    return (value as Number).toInt()
  }

  // ════════════════════════════════════════════════════════════════════
  // (e) The client reaches the real resolver.
  // ════════════════════════════════════════════════════════════════════

  /**
   * The wiring itself: a deal lands, the client calls the callable through
   * the production [CallableDecisionTimeout], and the server answers with
   * the only thing it CAN answer so soon — NOT_YET_EXPIRED, carrying a
   * positive remaining time. An unreachable emulator, a trigger that never
   * registered, or a response shape the mapping does not recognise would
   * all fail here, before any timing assertion is even attempted.
   */
  @Test
  fun theClientInvokesTheResolverOnTheFunctionsEmulator() = runBlocking {
    val seating = seating()
    val match = scripted(seating)
    val matchId = match.start()
    awaitTrigger(seating.getValue("p1").functions)
    match.dealAndOpenAuction()

    val outcome = resolve(seating.getValue("p1").functions, matchId)

    assertTrue(
      "a decision made seconds ago is not yet expired — expected " +
        "NotYetExpired with a positive remaining time, got $outcome",
      outcome is DecisionTimeoutPort.Resolution.NotYetExpired,
    )
    val remaining = (outcome as DecisionTimeoutPort.Resolution.NotYetExpired).retryAfterMillis
    assertTrue("retryAfterMillis must be positive, was $remaining", remaining > 0)
    assertTrue(
      "retryAfterMillis ($remaining ms) must stay inside the window we " +
        "configured (${WINDOW_MS} ms) — a larger value would mean the server " +
        "derived the deadline from something other than this match's timer",
      remaining <= WINDOW_MS,
    )
    // Nothing was decided: the call reported time left, it did not move the
    // table. The counter cannot exist yet either — no timeout happened.
    val doc = match.standings()
    assertEquals("no bid landed on an unexpired decision", 0, doc.bids.count { it.value != null })
    assertNull(
      "no counter before a timeout",
      storedCount(seating.getValue("p1").db, matchId, seating.getValue("p1").uid),
    )
  }

  // ════════════════════════════════════════════════════════════════════
  // (f) The decision and the counter commit together.
  // ════════════════════════════════════════════════════════════════════

  /**
   * The whole point of the alarm: an early NOT_YET_EXPIRED is retried on the
   * server's own remaining time, and once the deadline passes ONE more call
   * resolves the decision — no unrelated document update was needed to move
   * it along. Then (f): the bid and the counter are both visible afterwards,
   * so they committed together rather than the decision landing and the
   * counter being lost.
   */
  @Test
  fun anEarlyNotYetExpiredIsRetriedAndTheDecisionAndCounterCommitTogether() = runBlocking {
    val seating = seating()
    val match = scripted(seating)
    val matchId = match.start()
    awaitTrigger(seating.getValue("p1").functions)
    match.dealAndOpenAuction()
    val caller = seating.getValue("p1")

    val early = resolve(caller.functions, matchId)
    assertTrue("expected NotYetExpired first, got $early", early is DecisionTimeoutPort.Resolution.NotYetExpired)
    val waitMillis = (early as DecisionTimeoutPort.Resolution.NotYetExpired).retryAfterMillis

    // The retry that acceptance test (a) asks for: re-arm on the server's
    // own number, then wait past it. The deadline is fixed for this
    // decision, so one wait is enough — the loop in the VM does this
    // perpetually; this asserts one turn of it against the real callable.
    delay(waitMillis + EXPIRY_MARGIN_MS)

    val outcome = resolve(caller.functions, matchId)
    assertTrue("expected the retried call to resolve, got $outcome", outcome is DecisionTimeoutPort.Resolution.Applied)

    // (f): the decision landed...
    val decided = match.standings()
    val decidedSeats = decided.bids.filterValues { it != null }.keys
    assertEquals(
      "exactly one seat's bid was decided",
      1,
      decidedSeats.size,
    )
    val decidedSeat = decidedSeats.single()
    val timedOutUid = seating.getValue(decidedSeat).uid

    // ...and the counter landed WITH it, in the same commit. Reading it
    // through the timed-out seat's own client exercises the rules too.
    val count = storedCount(seating.getValue(decidedSeat).db, matchId, timedOutUid)
    assertNotNull("the counter doc must exist after a timeout", count)
    assertEquals("a first timeout records exactly one", 1, count)

    // Vote Kick's state is untouched — a timeout removal never merges into
    // it, and at count 1 nobody was removed anyway.
    assertNull("no timeout removal at count 1", decided.timeoutRemovedUids.firstOrNull())
  }

  // ════════════════════════════════════════════════════════════════════
  // (d) Concurrent invocations do not double-count or double-move.
  // ════════════════════════════════════════════════════════════════════

  /**
   * Two clients fire the resolver at the same expired decision. One wins,
   * applies the move, and re-anchors the document; the other sees the fresh
   * `updatedAt` and writes nothing. The observable result is exactly one
   * bid and exactly one on the counter — never two moves, never 2, which is
   * what a non-idempotent resolver would leave.
   */
  @Test
  fun concurrentInvocationsIncrementTheCounterExactlyOnce() = runBlocking {
    val seating = seating()
    val match = scripted(seating)
    val matchId = match.start()
    awaitTrigger(seating.getValue("p1").functions)
    match.dealAndOpenAuction()

    // Let the deadline actually pass before the race, so BOTH calls see an
    // expired decision and the idempotency is what saves the count.
    delay(WINDOW_MS + EXPIRY_MARGIN_MS)

    val outcomes = coroutineScope {
      listOf("p1", "p2").map { seat ->
        async { resolve(seating.getValue(seat).functions, matchId) }
      }.map { it.await() }
    }

    val applied = outcomes.count { it is DecisionTimeoutPort.Resolution.Applied }
    assertTrue(
      "at least one call applied the decision (got $applied of ${outcomes.size}: $outcomes)",
      applied >= 1,
    )

    val decided = match.standings()
    val decidedSeats = decided.bids.filterValues { it != null }.keys
    assertEquals(
      "exactly one seat was decided, no double-move",
      1,
      decidedSeats.size,
    )
    val decidedSeat = decidedSeats.single()
    val count = storedCount(seating.getValue(decidedSeat).db, matchId, seating.getValue(decidedSeat).uid)
    assertNotNull("the counter doc must exist after a timeout", count)
    assertEquals(
      "two racing calls recorded exactly one timeout, not ${count}",
      1,
      count,
    )
  }
}
