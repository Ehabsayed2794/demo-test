package com.estemshan.services

import androidx.test.core.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.SEAT_IDS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S20 (plan §2D) — the instrumented 4-client emulator suite.
 *
 * Four real `MatchService` instances, four real signed-in users, one real
 * `firestore.rules` — the first place real Kotlin meets real rules. Every
 * shared transition is raced by all four clients at once, and every
 * assertion is on CONVERGENCE, never on which client happened to win: the
 * emulator has zero transaction retry, so the winner of a race is luck.
 * A lost race must land as an idempotent no-op (RETURNED with a reason),
 * never an error — that is the property under test.
 *
 * No sleeps: every wait in the suite is a real Firestore round trip whose
 * completion is the signal.
 */
@RunWith(AndroidJUnit4::class)
class FourClientEmulatorSuiteTest {

  @Before
  fun wipeEmulatorsBetweenTests() {
    EmulatorSuite.reset()
  }

  private suspend fun newMatch(seed: Long): ScriptedMatch {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // One signed-in Firestore instance per seat — see EmulatorSuite.client
    // for why a shared auth context cannot read four hands.
    val seating = SEAT_IDS.associateWith { EmulatorSuite.client(context, it) }
    return ScriptedMatch(seating, seed)
  }

  /**
   * The fast signal: one round, end to end, through the real rules. This
   * isolates the wiring most likely to fail first (Auth + Firestore
   * emulator addressing, the rules' auth requirement, the room→match
   * start, the opening-turn publication, the archive+advance race) without
   * paying for a full match.
   */
  @Test
  fun roundOne_convergesAcrossFourClients() = runBlocking {
    val match = newMatch(seed = 101L)
    match.start()

    val report = match.playRound(1)

    assertFalse("round 1 is not a Rapid Round — no extension", report.extended)
    assertFalse("round 1 does not end the match", report.ended)
    assertTrue("round 1 advanced", report.advanced)
    assertEquals("the race moved the match to round 2", 2, report.currentRound)

    // The auction every seat replayed converged on one outcome.
    assertTrue("the auction produced a caller", report.callerId != null)
    assertEquals("every seat has a score", 4, report.totals.size)
    assertTrue("the highest score has at least one winner", report.winners.isNotEmpty())
    // Winners are a seat set drawn from the table, never uids.
    assertTrue("winners are seat ids", report.winners.all { it in SEAT_IDS })

    val committed = match.standings()
    assertEquals("the committed document advanced to round 2", 2, committed.currentRound)
    assertTrue("the dealer rotated onto a real seat owner",
      committed.dealer in committed.seats.values)
  }

  /**
   * The headline test: a full match — startMatch → 19 rounds of bidding
   * and 52 cards each → the archive+advance race every round → a
   * maxRounds extension on the first Rapid Round → the endMatch race → a
   * unanimous rematch vote → the rematch match — with all four clients
   * agreeing at every shared boundary.
   */
  @Test
  fun fullMatch_racesExtendEndMatchAndRematchAllConverge() = runBlocking {
    val match = newMatch(seed = 202L)
    val matchId = match.start()

    val rounds = match.playToEnd()

    // ── the match ran the distance the extension set ─────────────────
    val last = rounds.last()
    assertTrue("the match ended", last.ended)
    assertEquals("19 rounds were played", 19, rounds.size)
    assertEquals("the last round is round 19", 19, last.round)
    assertEquals("the extension lifted maxRounds to 19", 19, last.maxRounds)
    assertEquals("exactly one round was extended", 1, rounds.count { it.extended })
    assertEquals("the extension landed on the first Rapid Round",
      14, rounds.single { it.extended }.round)

    // ── endMatch convergence against the committed document ──────────
    val committed = match.standings()
    assertEquals("status is complete", MatchDoc.STATUS_COMPLETE, committed.status)
    assertEquals("the committed round is the final round", last.round, committed.completedRound)
    assertEquals("the committed winners are what all four computed",
      last.winners, committed.winnerIds)
    assertEquals("the committed final scores are what all four computed",
      last.totals, committed.finalScores)
    assertTrue("the winner set is a seat set",
      committed.winnerIds!!.all { it in SEAT_IDS })

    // The convergence contract: winnerIds is EVERY seat tied at the top —
    // 2, 3 or 4 winners is a legitimate outcome, not a bug to assert away.
    val top = committed.finalScores!!.values.max()
    assertEquals("winnerIds is exactly the highest-scoring seat set",
      committed.finalScores!!.filterValues { it == top }.keys,
      committed.winnerIds!!.toSet())

    // ── the rematch machine, raced the same way ──────────────────────
    val rematch = match.rematch()

    assertNotEquals("the rematch created a NEW match", matchId, rematch.newMatchId)
    assertEquals("the new match links back to the finished one", matchId, rematch.rematchOf)
    assertEquals("the rematch keeps all four seats",
      SEAT_IDS, rematch.newMatch.seats.keys.toList())
    assertEquals("the rematch match starts on round 1", 1, rematch.newMatch.currentRound)
  }
}
