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
 * S73 (C8) adds two things that cost this suite nothing extra: one assertion
 * inside (f) that a timeout expiry writes NO takeover marker (RD19 and RD20 keep
 * their own fields), and [theReclaimCallableIsRegisteredAndRefusesEveryoneWithNothingToReclaim],
 * which is the only device-side coverage of `reclaimInactiveSeat`'s refusal
 * branches. The borrow itself — the drive to a marker — is
 * [InactivityEmulatorTest]'s, because that one needs minutes of real expiries.
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

    /** S73 (C8): the second callable this suite reaches for, and the only one
     *  whose CLIENT mapping has no instrumented coverage anywhere else. */
    const val RECLAIM_NAME = "reclaimInactiveSeat"

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
   * payload reaches it and is rejected for a missing matchId, which is exactly
   * the "the trigger is live" signal; only NOT_FOUND means discovery has not
   * finished. Bounded, and never a silent pass.
   *
   * S73 (C8) defaulted [name] so the same readiness contract covers
   * [RECLAIM_NAME]: both are declared in `native/functions/index.js`, and the
   * reason a probe is needed at all — the emulator's HTTP port answers before
   * firebase-tools has discovered the bundle's triggers — is a property of the
   * emulator, not of one callable.
   */
  private suspend fun awaitTrigger(
    functions: FirebaseFunctions,
    name: String = CALLABLE_NAME,
  ) {
    val callable = functions.getHttpsCallable(name)
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
      "$name was never registered on the Functions emulator within " +
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
    // Round 1 is a NORMAL round, so the auction opens on the Dash-Call
    // decision, which submitBiddingAction appends to biddingLog — bids/ stays
    // empty until the Fast-Round estimates. The log is the move channel here.
    assertEquals("no decision landed on an unexpired decision", 0, doc.biddingLog.size)
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
    // Round 1 is a NORMAL round: the auction opens on the Dash-Call decision,
    // which lands in biddingLog (submitBiddingAction's channel), never in
    // bids/ — that is the Fast-Round estimates' field. One entry for one
    // decided seat is the move.
    val decidedSeats = decided.biddingLog.map { it.seatId }
    assertEquals(
      "exactly one seat's dash-call decision was decided",
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

    // S73 (RD19/RD20 separation): a human's expired decision is a TIMEOUT, not
    // a borrow. One expiry is nowhere near the 50 s of ignored windows a takeover
    // needs, and even past it the two mechanisms keep their own fields — the
    // counter this call just wrote, and the marker the S39 chip reads. If the
    // resolver ever stamped a marker on an expiry, a seat that merely bid late
    // would light up as INACTIVE and start a reclaim the player never asked for.
    assertTrue(
      "a timeout expiry writes no takeover marker (takeovers=${decided.inactiveTakeovers})",
      decided.inactiveTakeoverSeats().isEmpty(),
    )
  }

  // ════════════════════════════════════════════════════════════════════
  // S73 (C8) — the reclaim callable, reached from a device and mapped by the
  // production port.
  // ════════════════════════════════════════════════════════════════════

  /**
   * The four answers a player with nothing to reclaim gets, from the real
   * callable through the real [CallableSeatReclaim] — the one path in S73 that
   * has no device coverage anywhere else. [com.estemshan.services.InactivityEmulatorTest]
   * proves the borrowed-seat branch, but only after a minutes-long drive that
   * makes a seat borrowable; nothing here needs one, and each of these branches
   * is the expensive kind to get wrong in production: [SeatReclaimPort.Reclaim.Retryable]
   * on a denial makes a screen poll a callable that will never say anything else,
   * and [SeatReclaimPort.Reclaim.Denied] on a real wait stops a player from ever
   * asking for their own seat back.
   *
   * The readiness probe is part of the assertion, not a formality: a Kotlin/JS
   * export that `native/functions/index.js` failed to re-publish is invisible to
   * every JVM suite and would 404 here forever.
   */
  @Test
  fun theReclaimCallableIsRegisteredAndRefusesEveryoneWithNothingToReclaim() = runBlocking {
    val seating = seating()
    val match = scripted(seating)
    val matchId = match.start()
    awaitTrigger(seating.getValue("p1").functions, RECLAIM_NAME)
    val before = match.standings()
    assertTrue("nobody is borrowed at the start", before.inactiveTakeoverSeats().isEmpty())

    // (1) A seated player whose seat the table never took. This is the answer a
    // poll that arrived after another device's reclaim gets, and it comes from
    // the response MAP (reason NOT_BORROWED), not a code — so it pins the whole
    // `reclaimed:false` + reason shape across the serializer.
    val own = CallableSeatReclaim(seating.getValue("p1").functions).reclaim(matchId)
    assertTrue(
      "a player who holds their own seat is told exactly that, got $own",
      own is SeatReclaimPort.Reclaim.HumanHoldsSeat,
    )

    // (2) A caller who occupies no seat in this match. The callable throws
    // `permission-denied`, and the transport-code half of the mapping — which
    // keys on the code's NAME because a JVM test cannot initialize
    // FirebaseFunctionsException.Code — has never run on a device until now.
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val outsider = EmulatorSuite.client(context, "s61_outsider")
    val notMine = CallableSeatReclaim(outsider.functions).reclaim(matchId)
    assertTrue(
      "a non-player is refused terminally, not polled, got $notMine",
      notMine is SeatReclaimPort.Reclaim.Denied,
    )

    // (3)+(4) The two ways the request itself is bad: an empty matchId
    // (`invalid-argument`) and a match that is not there (`not-found`). Both are
    // denials — a retry cannot make a malformed payload or a vanished match into
    // a seat.
    val malformed = CallableSeatReclaim(seating.getValue("p1").functions).reclaim("")
    assertTrue("an empty matchId is a denial, got $malformed", malformed is SeatReclaimPort.Reclaim.Denied)
    val missing = CallableSeatReclaim(seating.getValue("p1").functions)
      .reclaim("s61-no-such-match")
    assertTrue("a missing match is a denial, got $missing", missing is SeatReclaimPort.Reclaim.Denied)

    // And four reclaim calls that decided nothing wrote nothing: the marker stays
    // absent and the document is the one the start committed. A NoWrite reclaim
    // that bumped `version` would re-arm every client's deadline anchor for free.
    val after = match.standings()
    assertTrue("no reclaim wrote a marker", after.inactiveTakeoverSeats().isEmpty())
    assertEquals("no reclaim wrote anything at all", before.version, after.version)
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
    // The same DASH channel as (f): biddingLog is where the round-1 decision
    // lands, so exactly one entry is exactly one move — a non-idempotent
    // resolver would leave two.
    val decidedSeats = decided.biddingLog.map { it.seatId }
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
