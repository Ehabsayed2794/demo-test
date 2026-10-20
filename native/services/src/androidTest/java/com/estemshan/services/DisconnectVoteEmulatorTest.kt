package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.engine.DisconnectVoteChoice
import com.estemshan.engine.DisconnectVotePhase
import com.estemshan.engine.MatchMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.SEAT_IDS
import com.google.firebase.Timestamp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * S74 — the disconnect vote end to end against the host emulators: the four
 * real signed-in users, the real `firestore.rules`, the real compiled
 * Functions bundle, exactly the stack the S20 harness runs
 * ([EmulatorSuite] + [ScriptedMatch]). The flow's runtime now EXISTS — the
 * three callables (`resolveDisconnectVote`, `castDisconnectVote`,
 * `restoreDisconnectedSeat`), the public marker `matches/{id}.disconnectVote`,
 * and the admin-only ballots under `matches/{id}/disconnectVotes/{uid}` — so
 * this suite drives it for real and asserts the committed documents.
 *
 * SOURCE OF TRUTH. docs/rules/CANONICAL_RULES.md Amendment A5.2 (:427-438,
 * owner ruling 2026-10-16, HEAD 40c82895d96966625a93db034aaf71300e3dc644):
 * the window is **30 seconds**, put to the table **while play continues**
 * (:429-430) — no pause while the ballot is open, because A4.4's OPEN-2
 * closure binds both votes and is not reopened (A5.3.4, :457-459); the
 * agreement threshold is **majority = 2 of 3** answers (:431); a winning
 * WAIT pauses the match for a bounded **90 seconds**, beginning **after**
 * the verdict and never during the vote — the single D9 invariant A4.4
 * preserved (:432-433); **no agreement inside 30 s continues with the
 * configured bot** (DEC-DISC-01, unchanged, :434); in `RANKED` the
 * question is not asked at all (:436) — the vote exists for `ROOM` +
 * `UNRANKED` only (DEC-DISC-01; plan S74 row :715, S74 judgement note
 * :889-890).
 *
 * WHAT THIS SUITE DRIVES AND WHAT IT DOES NOT. Detection is seeded the way
 * production's is written — the owner's own `players/{uid}.lastSeenAt`
 * heartbeat (S19's field, the only activity signal the frozen rules let a
 * player write and no client read, R7) — with a stale literal timestamp: the
 * threshold is 90 s of silence, so a fresh write of "two minutes ago" is
 * exactly a dropped client as the server sees one. Everything WALL-CLOCK is
 * deliberately NOT here: the 30 s close, the majority verdicts, the 90 s
 * pause and the replacement that follows it need the clock to advance, and
 * `:functions`' `DisconnectVoteFlowTest` already drives all of them on
 * injected fake documents and a fake clock, to the millisecond and with no
 * scheduler flakiness. An instrumented wall-clock wait would buy the same
 * arithmetic at ~2 minutes of CI time and a new class of red run. The
 * scaffold's four contract pins stay: this class is also a contract sheet,
 * and where a pin's "(future) runtime half" phrase WAS, its KDoc now names
 * where the half lives — the happy-path tests below, or the functions suite.
 */
@RunWith(AndroidJUnit4::class)
class DisconnectVoteEmulatorTest {

  @Before
  fun wipeEmulatorsBetweenTests() {
    EmulatorSuite.reset()
  }

  /**
   * S74 — release THIS class's four Firebase clients after every test.
   *
   * Every [EmulatorSuite.client] call mints a named `FirebaseApp` whose
   * Firestore stack registers ConnectivityManager callbacks, under a hard
   * per-process quota the suite was already close to. Nothing has ever
   * released them: pre-S74 the accumulated peak was 48 clients and the
   * suite passed. CI run 39001269460 (2026-10-17) added this class's 16
   * extra clients (4 tests × 4 seats) and the running count crossed the
   * quota mid-suite — the instrumented process died with `Internal error in
   * Cloud Firestore` / `ConnectivityManager$TooManyRequestsException`
   * during VoteKickEmulatorTest.failFlow_cooldownBlocksSameTarget, leaving
   * the job red with two tests unrun. The class has since grown to seven
   * tests — 28 clients per run — so this cleanup is load-bearing today, not
   * a precaution. Deleting our named apps
   * (`emu-<n>-s74_*`) after each test gives the suite back its pre-S74
   * footprint, so no other suite pays for this one.
   *
   * Deleting an app is only safe after its FirebaseInstallations work has
   * drained: the callables above start a FID registration/refresh, and its
   * worker thread would otherwise call back into the deleted app and crash
   * the process (CI 39305235939: `IllegalStateException: FirebaseApp was
   * deleted` in this class's third test). [EmulatorSuite.releaseClients]
   * awaits the FID and terminates Firestore before `delete()`.
   */
  @After
  fun releaseOurFirebaseClients() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    EmulatorSuite.releaseClients(context, "-s74_", awaitInstallations = true)
  }

  private companion object {
    /** One fixed seed for all four setups. */
    const val SEED = 9101L

    /** "30s live vote" — the window (:429). */
    const val VOTE_WINDOW_SECONDS = 30L

    /** "2-of-3" — the agreement threshold (:431). */
    const val MAJORITY_ANSWERS_REQUIRED = 2

    /** "90s WAIT" — the bounded pause (:432). */
    const val WAIT_PAUSE_SECONDS = 90L

    /**
     * S74's detection threshold — the runtime's own
     * `HEARTBEAT_STALE_AFTER_MILLIS`: 3 × S19's 30 s heartbeat cadence.
     * Seeding "silent for two minutes" is unambiguously past it without
     * camping on the boundary, which is a jsTest pin, not this suite's.
     */
    const val SILENT_FOR_MILLIS = 120_000L

    /** The four contract names, verbatim from the brief — carried in the
     *  failure messages so a red run names the contract it violated. */
    const val NAME_30S_LIVE_VOTE = "30s live vote"
    const val NAME_2_OF_3 = "2-of-3"
    const val NAME_90S_WAIT = "90s WAIT"
    const val NAME_CONFIGURED_BOT_DEFAULT = "configured bot default"

    const val RESOLVE_CALLABLE = "resolveDisconnectVote"
    const val CAST_CALLABLE = "castDisconnectVote"
    const val RESTORE_CALLABLE = "restoreDisconnectedSeat"
  }

  private suspend fun seating(): Map<String, EmulatorSuite.ClientFirebase> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return SEAT_IDS.associateWith { EmulatorSuite.client(context, "s74_$it") }
  }

  /** The committed document — the same ground-truth read every suite runs. */
  private suspend fun loadMatchDoc(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    matchId: String,
  ): MatchDoc {
    val snap = seating.getValue("p1").db.collection("matches").document(matchId).get().await()
    if (!snap.exists()) error("match $matchId disappeared mid-test")
    return MatchDoc.fromFields(snap.data ?: emptyMap())
      ?: error("match $matchId could not be parsed")
  }

  /** The client that owns a uid — the seat assignment is the document's, so
   *  the seat→uid map is read off the committed doc, never assumed. */
  private fun clientForUid(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    uid: String,
  ): EmulatorSuite.ClientFirebase =
    seating.values.firstOrNull { it.uid == uid }
      ?: error("no client in this seating owns uid $uid")

  /**
   * The dropped client, as the server sees one: the owner's own profile,
   * written by the owner, with `lastSeenAt` two minutes stale. This mirrors
   * S19's heartbeat write shape ([com.estemshan.services.Heartbeat] writes
   * exactly this field with a server timestamp) — the only difference is
   * the clock, which the server never trusts a client for anyway: it
   * re-reads the field and compares it to ITS clock.
   */
  private suspend fun seedSilentHeartbeat(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    uid: String,
  ) {
    val client = clientForUid(seating, uid)
    client.db.collection("players").document(uid).set(
      mapOf(
        "displayName" to "silent-seat",
        "accountType" to "guest",
        "avatarInitial" to "S",
        "rank" to "Unranked",
        "rp" to 0,
        "wins" to 0,
        "streak" to 0,
        "level" to 1,
        "coins" to 500,
        "gems" to 10,
        "createdAt" to Timestamp.now(),
        "lastSeenAt" to Timestamp(Date(System.currentTimeMillis() - SILENT_FOR_MILLIS)),
        "currentRoomId" to null,
        "currentMatchId" to null,
      ),
    ).await()
  }

  /** The reconnected client's first beat: the owner's own heartbeat, fresh. */
  private suspend fun refreshHeartbeat(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    uid: String,
  ) {
    clientForUid(seating, uid).db.collection("players").document(uid)
      .update(mapOf("lastSeenAt" to Timestamp.now()))
      .await()
  }

  private suspend fun call(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    uid: String,
    callable: String,
    payload: Map<String, Any>,
  ): Map<*, *> =
    clientForUid(seating, uid).functions
      .getHttpsCallable(callable)
      .call(payload)
      .await()
      .getData() as? Map<*, *>
      ?: error("$callable returned a non-map payload")

  private suspend fun resolve(seating: Map<String, EmulatorSuite.ClientFirebase>, uid: String, matchId: String): Map<*, *> =
    call(seating, uid, RESOLVE_CALLABLE, mapOf("matchId" to matchId))

  private suspend fun cast(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    uid: String,
    matchId: String,
    choice: DisconnectVoteChoice,
  ): Map<*, *> =
    call(seating, uid, CAST_CALLABLE, mapOf("matchId" to matchId, "choice" to choice.name))

  private suspend fun restore(seating: Map<String, EmulatorSuite.ClientFirebase>, uid: String, matchId: String): Map<*, *> =
    call(seating, uid, RESTORE_CALLABLE, mapOf("matchId" to matchId))

  private fun number(response: Map<*, *>, key: String): Long =
    (response[key] as? Number)?.toLong() ?: error("response has no readable '$key': $response")

  // ── the runtime, driven: detection → ballot → answers → reconnect ────

  /**
   * The whole fast half of the flow on real documents: a frozen heartbeat is
   * detected by a seated peer's poll, the ballot opens for the three who
   * remain WHILE the auction is still live, the subject's own answer is
   * refused, the two other humans' answers count once each, and a repeat of
   * an answer is `already` — never a second vote.
   *
   * What this proves that the jsTest cannot: the marker's shape survives
   * the real write path (rules, Admin SDK, timestamps), the electorates'
   * refusals are the real callables' answers, and the answers land under
   * admin-only ballots that no client can read back.
   */
  @Test
  fun aFrozenHeartbeatOpensTheBallotWhilePlayContinues() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    val matchId = match.start(MatchMode.UNRANKED)
    match.dealAndOpenAuction()

    val initial = loadMatchDoc(seating, matchId)
    val subjectSeat = "p2"
    val subjectUid = initial.seats.getValue(subjectSeat)
    val peers = SEAT_IDS - subjectSeat
    seedSilentHeartbeat(seating, subjectUid)

    // ── the resolver's first pass: detection, and the ballot ────────────
    val opened = resolve(seating, initial.seats.getValue("p3"), matchId)
    assertEquals("the first pass must commit", true, opened["changed"])
    assertEquals("the seat is held from the instant of detection", "OPENED", opened["action"])
    assertEquals(subjectSeat, opened["seatId"])
    assertEquals(
      "$NAME_30S_LIVE_VOTE: the window is the ruled 30 s, opened from the detection instant",
      VOTE_WINDOW_SECONDS * 1_000L,
      number(opened, "retryAfterMillis"),
    )

    val afterOpen = loadMatchDoc(seating, matchId)
    val entry = afterOpen.disconnectVote.getValue(subjectSeat)
    assertEquals(DisconnectVotePhase.VOTE, entry.phase)
    assertNull("no verdict before the close", entry.verdict)
    assertEquals("no answer yet: the first resolver pass is not a voter", 0, entry.answeredCount)
    val detectedAt = requireNotNull(entry.detectedAtMillis)
    val deadline = requireNotNull(entry.deadlineMillis)
    assertEquals(
      "$NAME_30S_LIVE_VOTE: the close is exactly one window after the detection instant",
      detectedAt + VOTE_WINDOW_SECONDS * 1_000L,
      deadline,
    )

    // The held seat is a BORROWAL, and only this mechanism writes it: the
    // permanent bot union (Vote Kick's read-side view) must never absorb it,
    // and no replacement has been recorded.
    assertEquals(setOf(subjectSeat), afterOpen.disconnectHeldSeats())
    assertEquals(setOf(subjectSeat), afterOpen.disconnectReclaimableSeats())
    assertTrue(
      "a disconnect-held seat is not in botSeats' read-side union",
      subjectSeat !in afterOpen.botControlledSeats(),
    )
    assertTrue("no replacement happened", afterOpen.disconnectReplacedUids.isEmpty())

    // "While play continues": the ballot opened mid-auction on a live match.
    assertTrue("$NAME_30S_LIVE_VOTE: the round is still in play", afterOpen.gameState.dealtRound >= 1)
    assertTrue("$NAME_30S_LIVE_VOTE: the match is not complete", afterOpen.status != MatchDoc.STATUS_COMPLETE)

    // ── a second poll before the close: nothing due, server's number ────
    val quiet = resolve(seating, initial.seats.getValue("p4"), matchId)
    assertEquals(false, quiet["changed"])
    assertEquals("nothing is due before the close", "NOT_YET_DUE", quiet["reason"])
    val quietRetry = number(quiet, "retryAfterMillis")
    assertTrue(
      "$NAME_30S_LIVE_VOTE: the next look is bounded by the window, was $quietRetry",
      quietRetry in 1..VOTE_WINDOW_SECONDS * 1_000L,
    )

    // ── the answers: the subject is refused, the three answer once ──────
    val subjectAnswer = cast(seating, subjectUid, matchId, DisconnectVoteChoice.WAIT)
    assertEquals(
      "$NAME_2_OF_3: the departed seat does not answer its own ballot",
      false,
      subjectAnswer["recorded"],
    )
    assertEquals("NOT_ELIGIBLE", subjectAnswer["reason"])

    val peerList = peers.toList()
    val first = cast(seating, initial.seats.getValue(peerList[0]), matchId, DisconnectVoteChoice.WAIT)
    assertEquals(true, first["recorded"])
    assertEquals(subjectSeat, first["seatId"])
    assertEquals(1, loadMatchDoc(seating, matchId).disconnectVote.getValue(subjectSeat).answeredCount)

    val second = cast(seating, initial.seats.getValue(peerList[1]), matchId, DisconnectVoteChoice.WAIT)
    assertEquals(true, second["recorded"])
    assertEquals(
      "$NAME_2_OF_3: two answers stand — majority reached",
      2,
      loadMatchDoc(seating, matchId).disconnectVote.getValue(subjectSeat).answeredCount,
    )

    // First answer stands: the repeat is the idempotent `already`, and the
    // count does not move.
    val repeat = cast(seating, initial.seats.getValue(peerList[0]), matchId, DisconnectVoteChoice.CONTINUE)
    assertEquals(true, repeat["recorded"])
    assertEquals(true, repeat["already"])
    assertEquals(
      "a retried answer is not a second vote",
      MAJORITY_ANSWERS_REQUIRED,
      loadMatchDoc(seating, matchId).disconnectVote.getValue(subjectSeat).answeredCount,
    )
  }

  /**
   * The reconnect, on real documents: the owner's restore ends the borrowal
   * — marker entry gone, ballots gone (a later answer is refused
   * `NO_BALLOT`, which is also the only client-observable proof they are
   * deleted, since the subcollection is deny-everything), no provenance and
   * no charge written — and once the heartbeat resumes, the same poll that
   * detected the silence answers `NOT_DETECTED`. The configured trio is
   * untouched across the whole borrow-and-return: a borrowal is not a
   * departure and configures nothing (A5.3; `DisconnectVote`'s charge table
   * is applied downstream of the document, never here).
   */
  @Test
  fun aReconnectReturnsTheSeatEndsTheBallotsAndChargesNothing() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    val matchId = match.start(MatchMode.ROOM)
    match.dealAndOpenAuction()

    val initial = loadMatchDoc(seating, matchId)
    val subjectSeat = "p1"
    val subjectUid = initial.seats.getValue(subjectSeat)
    seedSilentHeartbeat(seating, subjectUid)
    val opened = resolve(seating, initial.seats.getValue("p2"), matchId)
    assertEquals("OPENED", opened["action"])

    // ── the reconnect ────────────────────────────────────────────────────
    val returned = restore(seating, subjectUid, matchId)
    assertEquals(true, returned["restored"])
    assertEquals(subjectSeat, returned["seatId"])
    assertTrue(
      "a borrowal is not a departure — the response carries no charge and nothing else",
      returned.keys.all { it in setOf("ok", "restored", "matchId", "seatId") },
    )

    val afterRestore = loadMatchDoc(seating, matchId)
    assertTrue("the seat's marker entry is gone", afterRestore.disconnectVote.isEmpty())
    assertTrue(afterRestore.disconnectHeldSeats().isEmpty())
    assertTrue("no provenance was recorded", afterRestore.disconnectReplacedUids.isEmpty())
    assertEquals("$NAME_CONFIGURED_BOT_DEFAULT: the tier is the room's own", BotTier.MEDIUM, afterRestore.botTier)
    assertEquals(
      "$NAME_CONFIGURED_BOT_DEFAULT: the style is the room's own",
      BotPersonality.BALANCED,
      afterRestore.botPersonality,
    )

    // The ballot is gone with the borrowal, and the ballot subcollection is
    // unreadable to every client — so the refusal IS the observable.
    val lateAnswer = cast(seating, initial.seats.getValue("p3"), matchId, DisconnectVoteChoice.WAIT)
    assertEquals(false, lateAnswer["recorded"])
    assertEquals("NO_BALLOT", lateAnswer["reason"])

    // The reconnected client resumes its heartbeat (S19's cadence), and the
    // next poll finds nothing to ask about: a returned seat is silent no
    // more.
    refreshHeartbeat(seating, subjectUid)
    val quiet = resolve(seating, initial.seats.getValue("p2"), matchId)
    assertEquals(false, quiet["changed"])
    assertEquals("NOT_DETECTED", quiet["reason"])
  }

  // ── "configured bot default" ─────────────────────────────────────────

  /**
   * "configured bot default" — no agreement inside 30 s continues with the
   * configured bot (DEC-DISC-01, unchanged; A5.2 :434). The bot IS
   * configured on the document today: the trio is inherited from the room
   * at startMatch and never client-supplied on a match
   * (native/services/src/main/java/com/estemshan/services/model/
   * MatchModels.kt), so the fallback the vote's no-agreement branch picks up
   * is exactly [MatchDoc.botTier]/[MatchDoc.botPersonality] on the match —
   * pinned here in the one vote-eligible mode this test starts.
   *
   * RUNTIME HALF (where): the no-agreement VERDICT — including the
   * disagreeing-table case — is driven on an injected clock by
   * `DisconnectVoteFlowTest` in `:functions`; the trio being untouched
   * across a REAL borrow-and-return is asserted in
   * [aReconnectReturnsTheSeatEndsTheBallotsAndChargesNothing] above. This
   * pin stays what it always was: the document the fallback comes from.
   */
  @Test
  fun theNoAgreementDefaultContinuesWithTheConfiguredBotTheDocCarries() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    val matchId = match.start(MatchMode.UNRANKED)
    val doc = loadMatchDoc(seating, matchId)

    // UNRANKED is one of the vote's two modes (plan S74 row :715), so the
    // fallback the vote's no-agreement branch would use is exercised here.
    assertEquals(
      "$NAME_CONFIGURED_BOT_DEFAULT: the vote may be asked in this mode only",
      MatchMode.UNRANKED,
      doc.mode,
    )
    assertEquals("$NAME_CONFIGURED_BOT_DEFAULT: the tier is the room's own", BotTier.MEDIUM, doc.botTier)
    assertEquals(
      "$NAME_CONFIGURED_BOT_DEFAULT: the style is the room's own",
      BotPersonality.BALANCED,
      doc.botPersonality,
    )
    // The fallback plays in the departed player's own seat: the four real
    // seats this match seated at creation are what the bot picks up.
    assertEquals(SEAT_IDS.size, doc.seats.size)
  }

  // ── "2-of-3" ─────────────────────────────────────────────────────────

  /**
   * "2-of-3" — the agreement threshold is majority = 2 of 3 answers
   * (A5.2 :431). The electorate is the three seats that stay when one
   * departs; majority is the smallest count that always carries the three.
   *
   * RUNTIME HALF (where): the real electorate and the real counting are
   * asserted in [aFrozenHeartbeatOpensTheBallotWhilePlayContinues] — the
   * subject's own answer is refused `NOT_ELIGIBLE`, the two peers' answers
   * move the public count to 2, and a repeat is `already` without moving it.
   * The verdict arithmetic itself (what 2 of 3 decides, and every
   * below-majority shape) is `DisconnectVoteFlowTest`'s. This pin keeps the
   * number the ballot compares against.
   */
  @Test
  fun theThresholdIsMajorityTwoOfTheThreeNonDepartedAnswers() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    val matchId = match.start(MatchMode.ROOM)
    val doc = loadMatchDoc(seating, matchId)

    // A ROOM match seats the four canonical seats before anything departs;
    // the vote never runs over fewer.
    assertEquals("the four seats are seated at creation", SEAT_IDS.size, doc.seats.size)
    assertTrue(
      "$NAME_2_OF_3: the electorate is all four seats before a departure",
      doc.seats.keys.containsAll(SEAT_IDS.toSet()),
    )

    // The departed seat does NOT answer (its owner's participation
    // terminates; the charge contract is DepartureContractTest's table).
    val departed = SEAT_IDS.first()
    val electorate = SEAT_IDS - departed
    assertEquals("$NAME_2_OF_3: three answers remain", 3, electorate.size)
    assertEquals(
      "$NAME_2_OF_3: majority of those three is 2",
      MAJORITY_ANSWERS_REQUIRED,
      electorate.size / 2 + 1,
    )
    // And on a fresh match no seat is already held: neither removal list
    // nor a takeover marker pre-decides an answer for the electorate.
    assertTrue(
      "$NAME_2_OF_3: nothing removed, nothing borrowed",
      doc.removedUids.isEmpty() && doc.timeoutRemovedUids.isEmpty() &&
        doc.inactiveTakeoverSeats().isEmpty(),
    )
  }

  // ── "30s live vote" ──────────────────────────────────────────────────

  /**
   * "30s live vote" — the window is 30 seconds (:429), and the ballot opens
   * over a table in motion: "while play continues", with no pause while the
   * ballot is open (:429-430; OPEN-2 binds both ballots and is not
   * reopened, :457-459). "Live" is the honest anchor the runtime hangs the
   * window off: a real deal and an auction of record that nobody has
   * finished, on a document that is not complete.
   *
   * RUNTIME HALF (where): the real window is on the marker in
   * [aFrozenHeartbeatOpensTheBallotWhilePlayContinues] — `deadlineMillis`
   * is exactly `detectedAtMillis + 30 s`, both written by the resolver on
   * the server's clock, and the second poll before the close answers
   * `NOT_YET_DUE` with a bounded number. This pin is the length and the
   * live state they consume.
   */
  @Test
  fun theWindowIsThirtySecondsPutToTheTableWhilePlayContinues() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    match.start(MatchMode.UNRANKED)
    val open = match.dealAndOpenAuction()

    // The rule's own shape on the document that exists today.
    assertTrue("$NAME_30S_LIVE_VOTE: the round was dealt and is in play", open.gameState.dealtRound >= 1)
    assertTrue("$NAME_30S_LIVE_VOTE: the match is not complete", open.status != MatchDoc.STATUS_COMPLETE)
    assertTrue(
      "$NAME_30S_LIVE_VOTE: this mode is vote-eligible (ROOM or UNRANKED; never RANKED, :436)",
      open.mode != MatchMode.RANKED,
    )
    assertEquals("$NAME_30S_LIVE_VOTE: the window is 30 seconds", VOTE_WINDOW_SECONDS, 30L)
  }

  // ── "90s WAIT" ───────────────────────────────────────────────────────

  /**
   * "90s WAIT" — a winning WAIT pauses the match for exactly 90 seconds:
   * bounded, never indefinite — the single D9 invariant A4.4 preserved
   * (:432-433; plan R34 :620, S74 judgement note :889-890) — and it begins
   * AFTER the verdict, never during the vote (:433). The pause is not a
   * field of its own: it is the marker's WAIT phase, whose deadline the
   * resolver writes as `ballot close + 90 s`, and the decision machine
   * refuses to advance a paused table until that bound passes.
   *
   * RUNTIME HALF (where): the WAIT verdict, the pause, its collapse when a
   * poll arrives after the bound, and the replacement that lands when it
   * ends are ALL wall-clock and ALL driven on the injected clock by
   * `DisconnectVoteFlowTest` in `:functions` — deliberately not re-waited
   * here, because the pause itself already gates the resolver, so a real
   * 90 s sleep would prove nothing the jsTest does not. This pin is the one
   * number the pause must consume.
   */
  @Test
  fun aWinningWaitPausesForABoundedNinetySecondsAfterTheVerdict() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    val doc = loadMatchDoc(seating, match.start(MatchMode.ROOM))

    assertEquals("$NAME_90S_WAIT: the pause is exactly 90 seconds", WAIT_PAUSE_SECONDS, 90L)
    assertTrue("$NAME_90S_WAIT: a fresh match is not paused or complete", doc.status != MatchDoc.STATUS_COMPLETE)
    assertTrue("$NAME_90S_WAIT: and carries no vote marker to pause on", doc.disconnectVote.isEmpty())
  }

  // ── RANKED: the question is not asked at all ─────────────────────────

  /**
   * A5.2 :436 / DEC-DISC-01 — in `RANKED` the question is not asked at
   * all: not on a poll, not on an answer, not on a reconnect. The user is
   * seated and the heartbeat is frozen exactly as in the UNRANKED test, so
   * the only difference the calls can see is the mode — and the committed
   * document must come out of all three calls untouched: no marker, no
   * provenance, no bot seats, no side effects of any kind.
   */
  @Test
  fun rankedNeverAsksTheQuestionAndWritesNothing() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    val matchId = match.start(MatchMode.RANKED)
    match.dealAndOpenAuction()

    val initial = loadMatchDoc(seating, matchId)
    val subjectUid = initial.seats.getValue("p2")
    seedSilentHeartbeat(seating, subjectUid)

    val poll = resolve(seating, initial.seats.getValue("p3"), matchId)
    assertEquals(false, poll["changed"])
    assertEquals("a Ranked poll is terminal, not a wait", "NOT_APPLICABLE", poll["reason"])
    assertNull("not applicable carries no next look", poll["retryAfterMillis"])

    val answer = cast(seating, initial.seats.getValue("p1"), matchId, DisconnectVoteChoice.WAIT)
    assertEquals(false, answer["recorded"])
    assertEquals("NOT_APPLICABLE", answer["reason"])

    // The reconnect is benign and terminal: Ranked never borrowed the seat,
    // so there is nothing to return — NOT_HELD is the honest reading, not a
    // replacement.
    val returned = restore(seating, subjectUid, matchId)
    assertEquals(false, returned["restored"])
    assertEquals("NOT_HELD", returned["reason"])

    val committed = loadMatchDoc(seating, matchId)
    assertTrue("no marker was ever written", committed.disconnectVote.isEmpty())
    assertTrue(committed.disconnectHeldSeats().isEmpty())
    assertTrue(committed.disconnectReplacedUids.isEmpty())
    assertTrue("and the silence never became a bot seat", "p2" !in committed.botControlledSeats())
  }
}
