package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.engine.GameSession
import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.RoomDoc
import com.estemshan.services.model.ServiceException
import com.estemshan.services.session.AuthPort
import com.estemshan.services.session.GameSessionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket

/**
 * E4.4 start gate — the two server call sites, end to end against the host
 * emulators: a ROOM match starts only through [RoomDoc.canStart] (FULL and
 * all-ready), never through [RoomDoc.allReady] alone.
 *
 * Covers:
 *  1. A 2-player all-ready room no longer starts: the creator's setReady
 *     attaches matchStart.started == false (NOT_ALL_READY), the room stays
 *     waiting with no matchId. This is the exact scenario that started a
 *     2-player match before the gate.
 *  2. MatchService.startMatch on the same room is refused with
 *     INVALID_ARGUMENT and the message that is true for both causes
 *     ("A table needs four ready players to start.").
 *  3. A full 4-player all-ready room still starts: started == true with a
 *     real matchId, room flipped in_game — the gate never blocks a legal
 *     table.
 *
 * SELF-SKIP: the suite needs the Firestore/Auth emulators (firebase emulators
 * running beside the host). A TCP probe against [EmulatorSuite.HOST] must
 * succeed or every test here is skipped via Assume — a skipped run is the
 * honest "no emulator" answer; it never reports a pass without a run.
 * Isolation follows the house rules: [EmulatorSuite.reset] between tests and
 * this class's clients released after each one (the ConnectivityManager
 * per-process callback quota killed a later class once; see
 * [LeaveMatchEmulatorTest]'s S72 note). Execution is CI-only in this sandbox:
 * the file compiles via :services:assembleDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class RoomStartGateEmulatorTest {

  @Before
  fun requireEmulatorsOrSkip() = runBlocking(Dispatchers.IO) {
    assumeTrue(
      "Firestore/Auth emulators not reachable at ${EmulatorSuite.HOST}:${EmulatorSuite.FIRESTORE_PORT} — skipping",
      emulatorReachable(),
    )
    EmulatorSuite.reset()
  }

  @After
  fun releaseOurFirebaseClients() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    EmulatorSuite.releaseFirestoreClients(context, "-rg_")
  }

  /** Raw-socket probe off the main thread (a socket on the instrumentation
   *  thread would trip NetworkOnMainThread, exactly like EmulatorSuite.reset). */
  private suspend fun emulatorReachable(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
      Socket(EmulatorSuite.HOST, EmulatorSuite.FIRESTORE_PORT).use { it.isConnected }
    }.getOrDefault(false)
  }

  private suspend fun clients(vararg tags: String): Map<String, EmulatorSuite.ClientFirebase> {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return tags.associateWith { EmulatorSuite.client(context, "rg_$it") }
  }

  private fun matchServiceFor(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    seat: String,
  ): MatchService {
    val client = seating.getValue(seat)
    val port = GameSessionBridge(GameSession())
    return MatchService(
      client.db,
      object : AuthPort { override fun currentUid() = client.uid },
      port,
      MatchAdapter(port),
    )
  }

  private fun roomServiceFor(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    seat: String,
  ): RoomService {
    val client = seating.getValue(seat)
    return RoomService(
      client.db,
      object : AuthPort { override fun currentUid() = client.uid },
      { roomId -> matchServiceFor(seating, seat).startMatch(roomId) },
      NoRankDownJoinForGate,
    )
  }

  /** A plain ROOM room: creator creates, every other tag joins. */
  private suspend fun seatedRoom(
    seating: Map<String, EmulatorSuite.ClientFirebase>,
    creator: String,
    guests: List<String>,
  ): String {
    val code = roomServiceFor(seating, creator).createRoom(
      seating.getValue(creator).uid,
      "rg-room",
      GameType.FULL,
      ScoringMode.NORMAL,
      MatchMode.ROOM,
      false,
      BotTier.MEDIUM,
      BotPersonality.BALANCED,
      15,
    )
    for (guest in guests) {
      roomServiceFor(seating, guest).joinRoom(code, seating.getValue(guest).uid)
    }
    return code
  }

  @Test
  fun aTwoPlayerAllReadyRoomNoLongerStarts() = runBlocking {
    val seating = clients("p1", "p2")
    val code = seatedRoom(seating, creator = "p1", guests = listOf("p2"))
    val guest = roomServiceFor(seating, "p2").setReady(code, seating.getValue("p2").uid, true)
    assertNull("a guest's setReady never attempts a start", guest.matchStart)

    // The creator readies LAST — the exact precondition that used to start
    // a 2-player match through maybeStartMatch.
    val started = roomServiceFor(seating, "p1").setReady(code, seating.getValue("p1").uid, true)
    assertFalse("the outcome is NOT_ALL_READY", started.matchStart!!.allReady)
    assertFalse("no start was attempted", started.matchStart!!.started)
    assertNull("no match was started", started.matchStart!!.matchId)

    val room = roomServiceFor(seating, "p1").loadRoom(code)!!
    assertEquals("the room is still waiting", RoomDoc.STATUS_WAITING, room.status)
    assertEquals("no match was linked", null, room.matchId)
  }

  @Test
  fun startMatchOnANonFullRoomIsRefusedWithTheFourPlayerMessage() = runBlocking {
    val seating = clients("p1", "p2")
    val code = seatedRoom(seating, creator = "p1", guests = listOf("p2"))
    roomServiceFor(seating, "p2").setReady(code, seating.getValue("p2").uid, true)
    roomServiceFor(seating, "p1").setReady(code, seating.getValue("p1").uid, true)

    // The direct entry point: MatchService.startMatch, same hole, same fix.
    val err = runCatching { matchServiceFor(seating, "p1").startMatch(code) }
      .exceptionOrNull() as? ServiceException
    assertNotNull("a non-full start is refused", err)
    assertEquals(Reasons.INVALID_ARGUMENT, err!!.reason)
    assertEquals("A table needs four ready players to start.", err.message)
  }

  @Test
  fun aFullFourPlayerAllReadyRoomStillStarts() = runBlocking {
    val seating = clients("p1", "p2", "p3", "p4")
    val code = seatedRoom(
      seating, creator = "p1", guests = listOf("p2", "p3", "p4"),
    )
    for (guest in listOf("p2", "p3", "p4")) {
      roomServiceFor(seating, guest).setReady(code, seating.getValue(guest).uid, true)
    }
    val started = roomServiceFor(seating, "p1").setReady(code, seating.getValue("p1").uid, true)
    assertTrue("the full all-ready table started", started.matchStart!!.started)
    val matchId = started.matchStart!!.matchId
    assertNotNull("a real matchId came back", matchId)

    val room = roomServiceFor(seating, "p1").loadRoom(code)!!
    assertEquals("the room is in_game", RoomDoc.STATUS_IN_GAME, room.status)
    assertEquals("the room carries the new matchId", matchId, room.matchId)
  }
}

/** S70 — the Rank-down seam this suite never reaches (same stance as the
 *  other emulator suites: refuse, never crash). */
private object NoRankDownJoinForGate : RankDownJoinPort {
  override suspend fun join(roomId: String): RankDownJoin = RankDownJoin.Denied(
    Reasons.RANK_DOWN_UNAVAILABLE,
    "Rank-down joins are unavailable in this test.",
  )
}
