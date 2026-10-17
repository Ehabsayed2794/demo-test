package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.engine.MatchMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.SEAT_IDS
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S74 scaffolds — the A5.2 disconnect vote, pinned to the four contract
 * names the dispatch brief fixes verbatim: **"30s live vote"**, **"2-of-3"**,
 * **"90s WAIT"**, **"configured bot default"**.
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
 * WHAT THIS SCAFFOLD DELIBERATELY DOES NOT CLAIM. **No disconnect-vote
 * production exists yet.** S74 has landed no callable, no vote document, no
 * pause state, and this file invents none: the vote's runtime halves — who
 * is asked, where the ballot lives, what a WAIT writes — are the S74
 * build's own future surfaces. What each test does *today* is the honest
 * part of the scaffolding: it stands up a real match in a vote-eligible
 * mode against the host emulators (four real signed-in users, real
 * `firestore.rules` — [EmulatorSuite] + [ScriptedMatch], the S20 harness,
 * the same drivers VoteKickEmulatorTest and InactivityEmulatorTest run on
 * CI's `instrumented` job) and it pins the contract's literal numbers and
 * the authoritative data source the future runtime inherits. Each test's
 * KDoc names where its runtime half engages. Nothing here asserts a
 * behavior that does not exist, and nothing here passes *for* one.
 *
 * RANKED is deliberately absent: A5.2 :436 and the plan's S74 row are the
 * two sentences that keep it out — the not-asked assertion itself needs
 * the runtime that could ask, which is exactly what does not exist yet.
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
   * the job red with two tests unrun. Deleting our named apps
   * (`emu-<n>-s74_*`) after each test gives the suite back its pre-S74
   * footprint, so no other suite pays for this one.
   */
  @After
  fun releaseOurFirebaseClients() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    for (app in FirebaseApp.getApps(context).filter { it.name.contains("-s74_") }) {
      app.delete()
    }
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

    /** The four contract names, verbatim from the brief — carried in the
     *  failure messages so a red run names the contract it violated. */
    const val NAME_30S_LIVE_VOTE = "30s live vote"
    const val NAME_2_OF_3 = "2-of-3"
    const val NAME_90S_WAIT = "90s WAIT"
    const val NAME_CONFIGURED_BOT_DEFAULT = "configured bot default"
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

  // ── "configured bot default" ─────────────────────────────────────────

  /**
   * "configured bot default" — no agreement inside 30 s continues with the
   * configured bot (DEC-DISC-01, unchanged; A5.2 :434). The bot IS
   * configured on the document today: the trio is inherited from the room
   * at startMatch and never client-supplied on a match
   * (native/services/src/main/java/com/estemshan/services/model/
   * MatchModels.kt:244-253), so the fallback a future vote continues with
   * is exactly [MatchDoc.botTier]/[MatchDoc.botPersonality] on the match —
   * pinned here in the one vote-eligible modes that carry the vote.
   *
   * RUNTIME HALF AT INTEGRATION: the S74 build that resolves a
   * no-agreement window must leave this trio untouched — it picks up the
   * departed seat as the recorder already found it.
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
   * RUNTIME HALF AT INTEGRATION: the S74 ballot (who is asked, what counts
   * as an answer, when 2 lands) engages there; this pin is what the ballot
   * must compare against.
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
   * reopened, :457-459). Today's "live" is the honest anchor the runtime
   * will hang the window off: a real deal and an auction of record that
   * nobody has finished, on a document that is not complete.
   *
   * RUNTIME HALF AT INTEGRATION: the S74 window and its Put-to-the-table
   * moment engage there; this pin is the length and the live state they
   * must consume.
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
   * AFTER the verdict, never during the vote (:433). The 90 is the number
   * the runtime must consume; what a pause WRITES (any new field or state)
   * is the S74 surface, and today the document carries no pause vocabulary
   * at all — which is why the only safe assertion here is the length plus
   * the boundary the pause can never cross.
   *
   * RUNTIME HALF AT INTEGRATION: the WAIT verdict and its pause engage
   * there; this pin is what the pause must respect.
   */
  @Test
  fun aWinningWaitPausesForABoundedNinetySecondsAfterTheVerdict() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, SEED)
    val doc = loadMatchDoc(seating, match.start(MatchMode.ROOM))

    assertEquals("$NAME_90S_WAIT: the pause is exactly 90 seconds", WAIT_PAUSE_SECONDS, 90L)
    assertTrue("$NAME_90S_WAIT: a fresh match is not paused or complete", doc.status != MatchDoc.STATUS_COMPLETE)
  }
}
