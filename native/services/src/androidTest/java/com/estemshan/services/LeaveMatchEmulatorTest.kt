package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.engine.GameSession
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.services.model.LeaveResult
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.Reasons
import com.estemshan.services.session.AuthPort
import com.estemshan.services.session.GameSessionBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S72 — Explicit Leave Match end to end against the host emulators:
 * real MatchService, real firestore.rules, two real signed-in users.
 *
 * Covers:
 *  1. First leave completes; stored leftUids contains the leaver's uid;
 *     the leaver's seat is in botSeats (configured bot takeover).
 *  2. Second seat's leave completes; stored leftUids contains BOTH uids
 *     (no overwrite of the first departure — guards the S72-01 class of bug).
 *  3. Replay of the first leave: completes as an idempotent no-op
 *     (reason == ALREADY_LEFT); version and leftUids unchanged.
 *
 * Isolation: [EmulatorSuite.reset] wipes between tests, and
 * [releaseOurFirebaseClients] gives this class's two clients back after
 * every test — the same S74 hygiene, because the instrumented process sits
 * one client away from ConnectivityManager's per-UID callback quota and an
 * unreleased pair here is enough to kill a LATER class's test.
 * Execution is CI-only (no emulator in this sandbox); this file compiles
 * locally via :services:assembleDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class LeaveMatchEmulatorTest {

  @Before
  fun wipeEmulatorsBetweenTests() {
    EmulatorSuite.reset()
  }

  /**
   * S72 — release THIS class's two Firebase clients after every test.
   *
   * Every [EmulatorSuite.client] call mints a named `FirebaseApp` whose
   * Firestore gRPC channel registers a ConnectivityManager default-network
   * callback, under a hard per-process quota. [DisconnectVoteEmulatorTest]
   * documents the same crash for the s74 clients: crossing the quota kills
   * the instrumentation process with `Internal error in Cloud Firestore` /
   * `ConnectivityManager$TooManyRequestsException`, and the victim is
   * whichever test happens to run last, not the one that leaked. CI run
   * 39046825374 died that way in `VoteKickEmulatorTest.gates_denyWithoutPlayingARound`
   * with this class's two clients left unreleased. Deleting our own named
   * apps (`emu-<n>-s72_*`) keeps the suite at its pre-S72 footprint.
   *
   * [EmulatorSuite.releaseClients] terminates Firestore before deleting;
   * this class never invokes a callable, so it has no FirebaseInstallations
   * work to drain.
   */
  @After
  fun releaseOurFirebaseClients() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    EmulatorSuite.releaseClients(context, "-s72_", awaitInstallations = false)
  }

  private suspend fun twoClients(): Map<String, EmulatorSuite.ClientFirebase> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return mapOf(
      "p1" to EmulatorSuite.client(context, "s72_p1"),
      "p2" to EmulatorSuite.client(context, "s72_p2"),
    )
  }

  private fun leaveServiceFor(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    seat: String,
  ): com.estemshan.services.MatchService {
    val client = seating.getValue(seat)
    val engine = GameSession()
    val port = GameSessionBridge(engine)
    return com.estemshan.services.MatchService(
      client.db,
      object : AuthPort { override fun currentUid() = client.uid },
      port,
      com.estemshan.services.MatchAdapter(port),
    )
  }

  private suspend fun startTwoSeatMatch(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
  ): String {
    val p1 = seating.getValue("p1")
    val p2 = seating.getValue("p2")

    val roomService1 = com.estemshan.services.RoomService(
      p1.db,
      object : AuthPort { override fun currentUid() = p1.uid },
      { roomId ->
        leaveServiceFor(seating, "p1").startMatch(roomId)
      },
      NoRankDownJoin,
    )
    val roomCode = roomService1.createRoom(
      p1.uid,
      "s72-room",
      com.estemshan.engine.GameType.FULL,
      ScoringMode.NORMAL,
      MatchMode.RANKED,
      false,
      BotTier.MEDIUM,
      BotPersonality.BALANCED,
      15,
    )

    // p2 joins
    val roomService2 = com.estemshan.services.RoomService(
      p2.db,
      object : AuthPort { override fun currentUid() = p2.uid },
      { _ -> "" },
      NoRankDownJoin,
    )
    roomService2.joinRoom(roomCode, p2.uid)

    // Both ready, p1 (creator) starts last — only the creator may start.
    roomService2.setReady(roomCode, p2.uid, true)
    val started = roomService1.setReady(roomCode, p1.uid, true)
    return started.matchStart!!.matchId
      ?: error("match did not start: ${started.matchStart}")
  }

  private suspend fun loadMatch(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    matchId: String,
  ): MatchDoc {
    val snap = seating.getValue("p1").db
      .collection("matches").document(matchId).get().await()
    if (!snap.exists()) error("match $matchId disappeared mid-test")
    return MatchDoc.fromFields(snap.data ?: emptyMap())
      ?: error("match $matchId could not be parsed")
  }

  @Test
  fun leaveMatch_recordsExactlyOnceAndBotTakesSeat() = runBlocking {
    val seating = twoClients()
    val matchId = startTwoSeatMatch(seating)

    // ── 1. uid1 (p1) leaves ──
    val svc1 = leaveServiceFor(seating, "p1")
    val result1 = svc1.leaveMatch(matchId)
    assertEquals("first leave completes", result1.uid, seating.getValue("p1").uid)
    assertNull("first leave has no ALREADY_LEFT reason", result1.reason)

    val after1 = loadMatch(seating, matchId)
    assertTrue("leftUids contains uid1",
      after1.leftUids.contains(seating.getValue("p1").uid))
    assertTrue("uid1's seat is bot-held",
      after1.botSeats.contains("p1"))

    // ── 2. uid2 (p2) leaves ──
    val svc2 = leaveServiceFor(seating, "p2")
    val result2 = svc2.leaveMatch(matchId)
    assertEquals("second leave completes", result2.uid, seating.getValue("p2").uid)
    assertNull("second leave has no ALREADY_LEFT reason", result2.reason)

    val after2 = loadMatch(seating, matchId)
    assertTrue("leftUids contains uid1",
      after2.leftUids.contains(seating.getValue("p1").uid))
    assertTrue("leftUids contains uid2",
      after2.leftUids.contains(seating.getValue("p2").uid))
    assertEquals(2, after2.leftUids.size)

    // ── 3. uid1 replays leave: idempotent no-op ──
    val result1b = svc1.leaveMatch(matchId)
    assertEquals("replay is ALREADY_LEFT",
      Reasons.ALREADY_LEFT, result1b.reason)
    assertEquals("replay is no-op: version unchanged",
      after2.version, result1b.version)

    val after3 = loadMatch(seating, matchId)
    assertEquals("leftUids unchanged after replay",
      after2.leftUids, after3.leftUids)
    assertEquals("version unchanged after replay",
      after2.version, after3.version)
  }
}

/**
 * S70 — the Rank-down join seam this suite never reaches.
 *
 * The room below is created with `rankDown = false`, so [RoomService.joinRoom]
 * keeps its direct transaction and this is never asked. It exists because the
 * seam is a required constructor argument, and it answers with a DENIAL rather
 * than throwing on purpose: if a future edit did route a join here, the result
 * should be the product's own refusal — which these tests already know how to
 * handle — not a crash in a suite that was never written to exercise it.
 */
private object NoRankDownJoin : RankDownJoinPort {
  override suspend fun join(roomId: String): RankDownJoin = RankDownJoin.Denied(
    Reasons.RANK_DOWN_UNAVAILABLE,
    "Rank-down joins are unavailable in this test.",
  )
}
