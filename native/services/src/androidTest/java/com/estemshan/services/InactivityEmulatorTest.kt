package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.SEAT_IDS
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S73 Stage 2, C8 — RD19's inactivity takeover on a real device, against the
 * real Firestore + Auth + Functions emulators, with the real compiled Kotlin/JS
 * resolver behind the callable.
 *
 * WHY THIS SUITE EXISTS AT ALL. S73's whole design rests on one mechanism that
 * no unit test can demonstrate: the Admin SDK bypasses `firestore.rules`, so a
 * field the SDK writes that no client-facing validator names is READABLE by
 * every seat (the match document's whole-document `allow get`) and UNWRITABLE
 * by every seat (the update dispatch falls through to a literal `false`). That
 * asymmetry is why `firestore.rules` stays byte-identical for this story — and
 * a JVM test proves only what the rules FILE says, never what the emulator
 * ENFORCES. Only here does a real signed-in client actually try the write and
 * get refused, and only here does a real client actually read what the resolver
 * wrote.
 *
 * WHAT IS PROVEN, AND WHERE:
 *  - [noSeatCanWriteTheMarkerOrReadThePrivateClock] — the unwritable half, for
 *    both documents: the marker via update, via a mixed write, and via delete;
 *    `matches/{id}/inactivity/{uid}` via create, update and even READ. The
 *    control is the SAME token reading `matches/{id}` successfully, so a denial
 *    cannot be a dead harness; and by the time the assertions run the harness
 *    has already made a dozen rules-gated client writes to that same document
 *    through [ScriptedMatch] (room, ready, start, deal), so writability itself
 *    is not in doubt — only these fields' exclusion from every validator is.
 *  - [theResolverBorrowsAnAwaySeatAndEverySeatCanSeeIt] — the readable half,
 *    end to end: four genuinely absent players, the resolver's own callable
 *    making every decision, until one seat's accumulation crosses RD19's
 *    threshold and the marker appears. Then all four seats' parses agree, the
 *    borrowed seat is NOT in the permanent bot union and its owner is still
 *    seated (the state that keeps it kickable and keeps its vote), the entry
 *    survives the JS-write/JVM-read numeric transport as a real
 *    [InactiveTakeover], and its owner's `reclaimInactiveSeat` is answered with
 *    the server's own "the borrowed trick is not down yet" — twice, because a
 *    reclaim that is not due writes nothing. It is also the drive that caught
 *    the auction stall: a round with a Dash Caller never closes `biddingOpen`,
 *    because the estimate write derives the flag from "every seat has a bid"
 *    and a Dash Caller has no bid to submit, and detection used to read that
 *    flag as the phase — so it found nothing to time and the resolver stopped
 *    owning decisions at the auction. Detection now reads its own engine replay,
 *    which is what lets this drive past the auction; the same chain, played out
 *    in milliseconds and with no hand-clearing, is in
 *    `TimeoutResolutionTest.theResolverOwnsEveryDecisionOfANormalRoundToTheLastCard`
 *    and `theStaleOpenFlagChangesNothingAfterTheAuctionCloses`.
 *
 * WHAT THIS SUITE DELIBERATELY DOES NOT CLAIM. The reclaim's RETURNED write —
 * the marker removal plus the zeroed clock — is not driven here. DEC-RD19-04
 * bounds the return by "the trick in progress at takeover finishes", and this
 * suite stops driving the moment the marker appears, so no further card is ever
 * played and the bound can never be met within the test. Reaching it means
 * playing out the rest of the auction and a whole trick through the callable at
 * RD21's shortest legal window: roughly three more minutes of wall clock on top
 * of the three this drive already costs, inside a 35-minute instrumented job
 * that also plays entire matches. The halves it would prove are covered
 * elsewhere without that cost: the DECISION and both write shapes by
 * :functions' `SeatReclaimOnceTest`, the response mapping by :services'
 * `SeatReclaimResponseTest`, and the ADMIN WRITE MECHANISM ITSELF by the marker
 * appearing below — a reclaim removal is the same `tx.updateMatch` on the same
 * field by the same SDK, so the unproven sliver is the field name in a
 * particular payload, not a path through Firestore.
 *
 * NO COUNTDOWN, NO PRESENCE SIGNAL FROM A CLIENT. Nothing in this suite writes
 * anything toward RD19. The accumulation, its window anchor and the streak
 * cursor live in the subcollection no client can read, and the only
 * client-visible field is the marker the resolver owns. That is the design's
 * central privacy claim, and the denial assertions are its proof.
 *
 * TIMING. Every wait is the SERVER's own `retryAfterMillis` plus a small
 * margin, never a guessed constant — the same convention [TimeoutEmulatorTest]
 * uses — and the drive is bounded twice over: a call ceiling, and a stall
 * ceiling that fails as soon as the resolver stops being given decisions to
 * expire. Either way the test fails loudly with the state it saw rather than
 * hanging or silently passing.
 *
 * ISOLATION. [EmulatorSuite.reset] between tests; the seat ids are per-app
 * instances, and the takeover key is the PLAYER uid (`inactivity/{uid}`), never
 * a seat id, so nothing here can collide with a rematch's re-used seats. The
 * seed and the client tags are this suite's own.
 */
@RunWith(AndroidJUnit4::class)
class InactivityEmulatorTest {

  private companion object {
    const val RECLAIM_NAME = "reclaimInactiveSeat"
    const val RESOLVER_NAME = "resolveDecisionTimeout"

    /** RD21's shortest legal timer — the shortest window the resolver can be
     *  made to expire, so the drive to the 50 s threshold costs the least. */
    const val SHORTEST_LEGAL_TIMER = 5

    /** Past the deadline the server reported, short of anything else timing
     *  out. */
    const val EXPIRY_MARGIN_MS = 1_000L

    /** Never wait longer than a window the rules can actually produce. */
    const val MAX_WAIT_MS = 30_000L

    /**
     * The drive's call ceiling. A seat crosses the 50 s threshold after roughly
     * ten of its OWN expired windows at this timer, and a seat owns one
     * decision in four, so the first borrow lands after forty-ish resolutions;
     * sixty as a ceiling, and a failure that names what it saw rather than a
     * spin.
     */
    const val MAX_DRIVE_CALLS = 60

    /** How many consecutive looks the resolver may answer "there is no timed
     *  decision" before the drive gives up. One is a document mid-transition,
     *  two is a listener race; a third means the table ran out of decisions to
     *  charge the seats for, and no amount of waiting will borrow anything. */
    const val MAX_STALLED_WAYS = 2

    const val TRIGGER_TIMEOUT_MS = 90_000L
    const val TRIGGER_POLL_MS = 500L
  }

  @Before
  fun wipeEmulatorsBetweenTests() {
    EmulatorSuite.reset()
  }

  private suspend fun seating(): Map<String, EmulatorSuite.ClientFirebase> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return SEAT_IDS.associateWith { EmulatorSuite.client(context, "s73_$it") }
  }

  private fun scripted(seating: Map<String, EmulatorSuite.ClientFirebase>) =
    ScriptedMatch(seating, seed = 7301L, decisionTimerSeconds = SHORTEST_LEGAL_TIMER)

  /**
   * The committed document, read through [seat]'s OWN token and forced to the
   * SERVER. Both halves matter. `Source.SERVER` is what makes "every seat can
   * read the admin-written field" a claim about the rules rather than about a
   * local cache that [noSeatCanWriteTheMarkerOrReadThePrivateClock] has just
   * optimistically written a forged marker into; and each seat's own token is
   * the only identity the rules could route on, so a shared instance would let
   * one token's grants stand in for four.
   */
  private suspend fun docThrough(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    seat: String,
    matchId: String,
  ): MatchDoc {
    val snap = seating.getValue(seat).db
      .collection("matches")
      .document(matchId)
      .get(Source.SERVER)
      .await()
    if (!snap.exists()) error("match $matchId disappeared mid-test")
    return MatchDoc.fromFields(snap.data ?: emptyMap())
      ?: error("match $matchId could not be parsed")
  }

  private fun matchRef(db: FirebaseFirestore, matchId: String) =
    db.collection("matches").document(matchId)

  private fun inactivityRef(db: FirebaseFirestore, matchId: String, uid: String) =
    matchRef(db, matchId).collection(InactiveTakeover.INACTIVITY_COLLECTION).document(uid)

  /**
   * A marker entry in exactly the shape the resolver writes — all three fields,
   * with real names — so a denial here is about WHO is writing and can never be
   * explained away as a payload the rules reject for a different reason.
   */
  private fun forgedMarker(round: Int): Map<String, Any> = mapOf(
    "p1" to mapOf(
      InactiveTakeover.TAKEN_OVER_AT_FIELD to System.currentTimeMillis(),
      InactiveTakeover.ROUND_AT_TAKEOVER_FIELD to round,
      InactiveTakeover.TRICK_AT_TAKEOVER_FIELD to 0,
    ),
  )

  private suspend fun expectPermissionDenied(what: String, block: suspend () -> Unit) {
    try {
      block()
      fail("$what was allowed — S73's zero-rules design depends on it being denied")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(
        "$what denied for the wrong reason ($e)",
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        e.code,
      )
    }
  }

  /**
   * Poll until the emulator has registered [name]. The empty payload reaches the
   * callable and is refused for a missing `matchId` — `invalid-argument`, never
   * NOT_FOUND — which is exactly the "this trigger is live" signal. For
   * [RECLAIM_NAME] it is also the first half of the [SeatReclaimResponse]
   * contract: an un-enumerable Kotlin/JS export that `native/functions/index.js`
   * failed to re-publish would 404 here forever.
   */
  private suspend fun awaitTrigger(functions: FirebaseFunctions, name: String) {
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
        "the manifest line in native/functions/index.js or the bundle itself is " +
        "the suspect — read the emulator log.",
    )
  }

  /**
   * The resolver plays one expired decision per call and answers the time left
   * on the one after it, so each iteration consumes exactly one window:
   * `Applied` moves the table, `NotYetExpired` says when the next one is due and
   * the wait is that number — never a sleep. Returns the first document whose
   * marker is non-empty, with the seat id the marker borrowed.
   */
  private suspend fun driveUntilBorrowed(
    functions: FirebaseFunctions,
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    matchId: String,
  ): Pair<String, MatchDoc> {
    val resolver = CallableDecisionTimeout(functions)
    var stalled = 0
    repeat(MAX_DRIVE_CALLS) { call ->
      val doc = docThrough(seating, "p1", matchId)
      val borrowed = doc.inactiveTakeoverSeats()
      if (borrowed.isNotEmpty()) {
        return borrowed.first() to doc
      }
      when (val outcome = resolver.resolve(matchId)) {
        // A live decision with time left is the table working as designed, so
        // the wait counts as progress and the stall ceiling is cleared.
        is DecisionTimeoutPort.Resolution.NotYetExpired -> {
          stalled = 0
          delay(outcome.retryAfterMillis.coerceAtMost(MAX_WAIT_MS) + EXPIRY_MARGIN_MS)
        }
        is DecisionTimeoutPort.Resolution.Applied -> stalled = 0
        DecisionTimeoutPort.Resolution.NotActive,
        DecisionTimeoutPort.Resolution.Retryable,
        -> {
          stalled++
          if (stalled > MAX_STALLED_WAYS) {
            error(driveFailure("the resolver answered $outcome for $stalled looks in a row", doc))
          }
          delay(EXPIRY_MARGIN_MS)
        }
        DecisionTimeoutPort.Resolution.Denied -> error(
          driveFailure(
            "the resolver denied a decision on a live, dealt match at drive call $call — " +
              "nothing can be borrowed while no decision resolves",
            doc,
          ),
        )
      }
    }
    val exhausted = docThrough(seating, "p1", matchId)
    error(driveFailure("no seat was borrowed in $MAX_DRIVE_CALLS resolver calls", exhausted))
  }

  /** Why a drive failed, in the only numbers a client is allowed to have. The
   *  accumulation itself is unobservable by design — it lives in the document
   *  this suite proves nobody can read. */
  private fun driveFailure(why: String, doc: MatchDoc): String =
    "$why. currentRound=${doc.currentRound}, status=${doc.status}, " +
      "biddingLog=${doc.biddingLog.size} entries, cardLog=${doc.cardLog.size} cards, " +
      "botSeats=${doc.botSeats}, timeoutRemoved=${doc.timeoutRemovedUids}, " +
      "takeovers=${doc.inactiveTakeovers}. The clock lives in a doc no client can " +
      "read, so the emulator's firestore-debug.log is where an accumulation that " +
      "never advanced would show."

  // ════════════════════════════════════════════════════════════════════
  // The unwritable half: no client can forge, ride on, or clear a borrow.
  // ════════════════════════════════════════════════════════════════════

  @Test
  fun noSeatCanWriteTheMarkerOrReadThePrivateClock() = runBlocking {
    val seating = seating()
    val match = scripted(seating)
    val matchId = match.start()
    val dealt = match.dealAndOpenAuction()

    // The control, from every seat's own token: the match document IS readable.
    // This is the half of the mechanism that lets S39 draw the chip at all, and
    // it also proves each client sign-in is live, so a denial below is the rules
    // and not an inert harness.
    SEAT_IDS.forEach { seat ->
      val doc = docThrough(seating, seat, matchId)
      assertEquals(
        "seat $seat reads the match the deal committed",
        dealt.currentRound,
        doc.currentRound,
      )
      assertTrue("seat $seat is seated in what it read", doc.isSeated(seating.getValue(seat).uid))
    }
    // And nobody is borrowed yet — the state the denials below are measured
    // against, and the proof that a pass cannot be produced by an old marker.
    assertEquals(emptySet<String>(), dealt.inactiveTakeoverSeats())

    val round = dealt.currentRound
    SEAT_IDS.forEach { seat ->
      val db = seating.getValue(seat).db
      val uid = seating.getValue(seat).uid

      expectPermissionDenied("a seated client forging $seat's marker write") {
        matchRef(db, matchId)
          .update(InactiveTakeover.TAKEOVER_FIELD, forgedMarker(round)).await()
      }
      // A marker cannot ride along with legitimate-looking keys either: the
      // update dispatch routes validators on the WHOLE affected-key set, so
      // mixing one unnamed field into a validator's own shape denies the
      // entire write, not just the field.
      expectPermissionDenied("a seated client mixing a marker into $seat's version bump") {
        matchRef(db, matchId).update(
          mapOf(
            InactiveTakeover.TAKEOVER_FIELD to forgedMarker(round),
            "version" to dealt.version + 1,
          ),
        ).await()
      }
      // Nor cleared: taking a still-absent player's marker down is exactly the
      // forgery whose only door is supposed to be the reclaim callable.
      expectPermissionDenied("a seated client deleting $seat's marker field") {
        matchRef(db, matchId)
          .update(InactiveTakeover.TAKEOVER_FIELD, FieldValue.delete()).await()
      }
      // The private document — the accumulation, its window anchor, the streak
      // cursor — has no rules block at all, so all three of its write paths and
      // even its READ path are refused for the very player it belongs to.
      expectPermissionDenied("$seat creating its own inactivity doc") {
        inactivityRef(db, matchId, uid)
          .set(
            mapOf(
              InactivityClock.ACCUMULATED_FIELD to 0L,
              InactivityClock.OPEN_WINDOW_FIELD to 0L,
              InactiveTakeover.CURSOR_FIELD to 0L,
            ),
          ).await()
      }
      expectPermissionDenied("$seat updating its own inactivity doc") {
        inactivityRef(db, matchId, uid)
          .update(InactivityClock.ACCUMULATED_FIELD, 49_000L).await()
      }
      expectPermissionDenied("$seat reading its own inactivity doc") {
        inactivityRef(db, matchId, uid).get(Source.SERVER).await()
      }
    }

    // Nothing half-landed: three denied write shapes times four seats later, the
    // document is exactly what the deal left it — including the version, which
    // the mixed write would have bumped had any part of it been accepted.
    val after = docThrough(seating, "p1", matchId)
    assertEquals(emptySet<String>(), after.inactiveTakeoverSeats())
    assertEquals(
      "a denied write never advanced the version either",
      dealt.version,
      after.version,
    )
  }

  // ════════════════════════════════════════════════════════════════════
  // The readable half, end to end: the resolver borrows, every seat sees it,
  // and the owner's reclaim is answered by the real callable.
  // ════════════════════════════════════════════════════════════════════

  @Test
  fun theResolverBorrowsAnAwaySeatAndEverySeatCanSeeIt() = runBlocking {
    val seating = seating()
    val match = scripted(seating)
    val matchId = match.start()
    awaitTrigger(seating.getValue("p1").functions, RESOLVER_NAME)
    match.dealAndOpenAuction()

    // Nobody plays. The resolver makes every decision for every seat, and each
    // seat's own private clock charges for every window it ignores, until one
    // crosses RD19's threshold.
    val (borrowedSeat, markerDoc) = driveUntilBorrowed(
      seating.getValue("p1").functions,
      seating,
      matchId,
    )
    val borrowed = markerDoc.inactiveTakeoverSeats()
    assertTrue("expected at least one borrowed seat, got $borrowed", borrowed.isNotEmpty())

    // (1) THE READABLE HALF OF THE MECHANISM. The field is admin-written and no
    // validator names it, and the match document's whole-document `allow get`
    // still hands it to every seat — which is precisely how the S39 chip and the
    // return-to-seat banner work without a rules revision. All four parses agree.
    SEAT_IDS.forEach { seat ->
      val seen = docThrough(seating, seat, matchId).inactiveTakeoverSeats()
      assertEquals("seat $seat must see the same borrow as seat p1", borrowed, seen)
    }

    // (2) THE ENTRY SURVIVES THE TRANSPORT. The marker's numbers are written by
    // Kotlin/JS as JS `number`s and read back on Android as Integer/Long;
    // [InactiveTakeover.parse] accepts any whole Number and rejects anything it
    // cannot, and a rejected entry parses as "nobody is borrowed". So a present,
    // fully-parsed entry IS the round-trip proof — and its bounds are the shape
    // the resolver promised: a round that was live when the seat was taken and a
    // trick index inside that round's 13.
    val entry = markerDoc.inactiveTakeovers[borrowedSeat]
      ?: error("seat $borrowedSeat is in the marker set but has no parseable entry: $markerDoc")
    assertTrue(
      "the takeover stamps the round in progress (roundAtTakeover=${entry.roundAtTakeover} at " +
        "currentRound=${markerDoc.currentRound})",
      entry.roundAtTakeover in 1..markerDoc.currentRound,
    )
    assertTrue(
      "and a trick index inside the round's ${InactiveTakeover.TRICKS_PER_ROUND} " +
        "(trickAtTakeover=${entry.trickAtTakeover})",
      entry.trickAtTakeover in 0 until InactiveTakeover.TRICKS_PER_ROUND,
    )
    assertTrue("and a real takeover instant (takenOverAtMillis=${entry.takenOverAtMillis})", entry.takenOverAtMillis > 0L)

    // (3) BORROWED IS NOT HELD. The permanent union drives Vote Kick's gates and
    // the resolver's short-window pacing, and folding a borrowed seat into it
    // would make a still-present player unkickable and unvotable — the exact
    // opposite of DEC-RD19-04. The MatchModels KDoc says the union is not
    // extended; this is the assertion that holds it.
    val ownerUid = seating.getValue(borrowedSeat).uid
    assertTrue(
      "the borrowed seat must stay OUT of the permanent bot union " +
        "(union=${markerDoc.botControlledSeats()}, borrowed=$borrowed)",
      borrowedSeat !in markerDoc.botControlledSeats(),
    )
    assertTrue("the owner is still seated", markerDoc.isSeated(ownerUid))
    assertTrue("and still a player", markerDoc.players.contains(ownerUid))
    assertTrue(
      "and nobody was removed to make room for the borrow (removed=${markerDoc.removedUids}, " +
        "timeoutRemoved=${markerDoc.timeoutRemovedUids})",
      markerDoc.removedUids.isEmpty() && markerDoc.timeoutRemovedUids.isEmpty(),
    )

    // (4) THE REACHABLE RECLAIM. The callable is registered through
    // native/functions/index.js's re-publish (an un-enumerable Kotlin/JS export
    // would 404 forever), the payload round-trips through the real serializer,
    // and the trick bound holds. DEC-RD19-04 says control comes back once the
    // trick in progress at takeover is down, and this suite stopped driving the
    // instant the marker appeared — so no further card exists to move
    // [InactiveTakeover.seatTrickKey] past the stamped trick, and the honest
    // server answer is TRICK_NOT_FINISHED with a wait, not a write.
    val reclaim = CallableSeatReclaim(seating.getValue(borrowedSeat).functions)
    val first = reclaim.reclaim(matchId)
    assertTrue(
      "a reclaim while the borrowed trick is still owed must wait, got $first",
      first is SeatReclaimPort.Reclaim.WaitingForTrick,
    )
    val retry = (first as SeatReclaimPort.Reclaim.WaitingForTrick).retryAfterMillis
    assertTrue(
      "the server must bound the next look (was $retry ms) — the poll re-arms on " +
        "this number and MatchDoc carries no deadline anchor of its own",
      retry > 0L,
    )

    // (5) AND ASKING TWICE CHANGES NOTHING. The reclaim's only possible writes
    // are the marker removal and the clock zeroing, so an answer that keeps the
    // seat borrowed writes nothing at all. The retry value itself is NOT
    // asserted equal: it is the remaining time on a fixed deadline measured
    // against the server's clock on two different instants, so it can only
    // shrink — the invariant is the shape and the untouched document, which is
    // what makes the client's bounded poll safe to fire twice, out of order, or
    // after a reconnect.
    val second = reclaim.reclaim(matchId)
    assertTrue(
      "a repeat of a not-yet-due reclaim is answered the same way, got $second",
      second is SeatReclaimPort.Reclaim.WaitingForTrick,
    )
    val afterReclaims = docThrough(seating, borrowedSeat, matchId)
    assertEquals(
      "and still holds the marker it started with",
      borrowed,
      afterReclaims.inactiveTakeoverSeats(),
    )
    assertEquals(
      "with the same entry, including the stamped trick",
      entry.trickAtTakeover,
      afterReclaims.inactiveTakeovers.getValue(borrowedSeat).trickAtTakeover,
    )
    assertEquals(
      "no version churn from a reclaim that did not reclaim",
      markerDoc.version,
      afterReclaims.version,
    )
  }
}
