package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.engine.MatchMode
import com.estemshan.engine.uniqueKozSeat
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.VoteKickDoc
import com.estemshan.services.session.AuthPort
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S60 — manual Vote Kick end to end against the host emulators: real
 * services, real `firestore.rules`, four real signed-in users. Covers
 * the wiring the JVM suites cannot reach (transactions, rules, doc
 * shapes, removal writes); the full gate/tally/cooldown matrix lives in
 * :engine VoteKickTest, never duplicated here.
 *
 * Isolation: [EmulatorSuite.reset] wipes between tests; rooms take
 * generated codes and matches take auto ids, so no s60_-prefixed ids
 * are needed beyond readability — there is nothing stable to collide.
 * Cost note: the fail/pass flows each play 7 scripted rounds to reach
 * the Round-8 window — about one full scripted match between them,
 * which this suite already pays for elsewhere.
 */
@RunWith(AndroidJUnit4::class)
class VoteKickEmulatorTest {

  @Before
  fun wipeEmulatorsBetweenTests() {
    EmulatorSuite.reset()
  }

  private suspend fun seating(): Map<String, EmulatorSuite.ClientFirebase> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return SEAT_IDS.associateWith { EmulatorSuite.client(context, "s60_$it") }
  }

  private fun kickServiceFor(seating: Map<String, EmulatorSuite.ClientFirebase>, seat: String) =
    VoteKickService(
      seating.getValue(seat).db,
      object : AuthPort {
        override fun currentUid() = seating.getValue(seat).uid
      },
    )

  private suspend fun loadMatch(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    matchId: String,
  ): MatchDoc {
    val snap = seating.getValue("p1").db.collection("matches").document(matchId).get().await()
    if (!snap.exists()) error("match $matchId disappeared mid-test")
    return MatchDoc.fromFields(snap.data ?: emptyMap())
      ?: error("match $matchId could not be parsed")
  }

  private suspend fun loadVote(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    matchId: String,
  ): VoteKickDoc? {
    val snap = seating.getValue("p1").db.collection("matches").document(matchId)
      .collection("voteKick").document(VoteKickDoc.CURRENT_DOC_ID).get().await()
    if (!snap.exists()) return null
    return VoteKickDoc.fromFields(snap.data ?: emptyMap())
  }

  private suspend fun expectReason(reason: String, block: suspend () -> Unit) {
    try {
      block()
      fail("expected ServiceException($reason)")
    } catch (e: ServiceException) {
      assertEquals(reason, e.reason)
    }
  }

  /**
   * Plays to the Round-8 window and returns committed totals with a
   * unique Koz. Plays on while last place is tied (bounded) — with a
   * fixed seed the path is deterministic; the bound fails loudly with
   * a reseed hint instead of hanging.
   */
  private suspend fun playToUniqueKoz(match: ScriptedMatch): Triple<Map<String, Int>, String, Int> {
    var round = 1
    while (true) {
      val report = match.playRound(round)
      if (report.ended) error("match ended before any vote window")
      if (round >= 7) {
        val koz = uniqueKozSeat(report.totals)
        if (koz != null) return Triple(report.totals, koz, report.currentRound)
        if (round >= 10) error("no unique Koz by round 10 — pick another seed")
      }
      round = report.currentRound
    }
  }

  @Test
  fun failFlow_cooldownBlocksSameTarget() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, 6001L)
    val matchId = match.start(MatchMode.RANKED)
    // Triple(totals, koz, currentRound): skip the Koz seat, bind the round.
    val (totals, _, currentRound) = playToUniqueKoz(match)
    val koz = uniqueKozSeat(totals)!!
    val initiator = (SEAT_IDS - koz).first()
    val others = (SEAT_IDS - koz - initiator).toList()
    assertEquals(2, others.size)

    val open = kickServiceFor(seating, initiator).openVoteKick(matchId, koz, "griefing", totals)
    assertTrue(open.created)
    assertEquals(VoteKickDoc.STATUS_OPEN, open.vote?.status)
    assertEquals(
      "no auto-vote: every slot starts null",
      true,
      open.vote?.votes?.values?.all { it == null },
    )

    for (seat in others) {
      kickServiceFor(seating, seat).castVoteKick(matchId, VoteKickDoc.VOTE_NO)
    }
    val failed = loadVote(seating, matchId)!!
    assertEquals(VoteKickDoc.STATUS_FAILED_NO, failed.status)

    val committed = loadMatch(seating, matchId)
    assertEquals(koz, committed.failedVoteKickCooldown?.targetSeat)
    assertEquals(currentRound + 5, committed.failedVoteKickCooldown?.blockedUntilRound)

    expectReason(Reasons.VOTE_COOLDOWN_LIVE) {
      kickServiceFor(seating, initiator).openVoteKick(matchId, koz, "griefing", totals)
    }
  }

  @Test
  fun passFlow_removesPermanentlyAndBarsRejoin() = runBlocking {
    val seating = seating()
    val match = ScriptedMatch(seating, 6002L)
    val matchId = match.start(MatchMode.RANKED)
    val (totals, _) = playToUniqueKoz(match)
    val koz = uniqueKozSeat(totals)!!
    val initiator = (SEAT_IDS - koz).first()
    val seconder = (SEAT_IDS - koz - initiator).first()

    kickServiceFor(seating, initiator).openVoteKick(matchId, koz, "griefing", totals)
    kickServiceFor(seating, initiator).castVoteKick(matchId, VoteKickDoc.VOTE_YES)
    val decided = kickServiceFor(seating, seconder).castVoteKick(matchId, VoteKickDoc.VOTE_YES)
    assertEquals(VoteKickDoc.STATUS_PASSED, decided.resolution)
    assertTrue(decided.removed)

    val committed = loadMatch(seating, matchId)
    assertTrue("target seat is bot-held", committed.botSeats.contains(koz))
    val targetUid = committed.seats.getValue(koz)
    assertTrue("target uid is banned", committed.removedUids.contains(targetUid))
    assertEquals(VoteKickDoc.STATUS_PASSED, loadVote(seating, matchId)?.status)

    // The removed seat cannot act again through any write path.
    expectReason(Reasons.REMOVED_FROM_MATCH) {
      kickServiceFor(seating, koz).openVoteKick(matchId, initiator, "revenge", totals)
    }
    expectReason(Reasons.REMOVED_FROM_MATCH) {
      kickServiceFor(seating, koz).castVoteKick(matchId, VoteKickDoc.VOTE_YES)
    }
  }

  @Test
  fun gates_denyWithoutPlayingARound() = runBlocking {
    // ROOM matches never vote — denied before any gate needs real scores.
    val roomSeating = seating()
    val roomMatch = ScriptedMatch(roomSeating, 6003L)
    val roomMatchId = roomMatch.start()
    expectReason(Reasons.VOTE_NOT_RANKED) {
      kickServiceFor(roomSeating, "p1").openVoteKick(
        roomMatchId, "p4", "griefing",
        mapOf("p1" to 10, "p2" to 9, "p3" to 8, "p4" to 1),
      )
    }

    // RANKED at round 1: Round 7 has not completed — refused outright.
    val earlySeating = seating()
    val earlyMatch = ScriptedMatch(earlySeating, 6004L)
    val earlyMatchId = earlyMatch.start(MatchMode.RANKED)
    expectReason(Reasons.VOTE_ROUNDS_SHORT) {
      kickServiceFor(earlySeating, "p1").openVoteKick(
        earlyMatchId, "p4", "griefing",
        mapOf("p1" to 10, "p2" to 9, "p3" to 8, "p4" to 1),
      )
    }
  }
}
