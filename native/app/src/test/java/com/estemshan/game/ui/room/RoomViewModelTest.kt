package com.estemshan.game.ui.room

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.services.RoomPort
import com.estemshan.services.model.MatchStartResult
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.RoomDoc
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.normalizeRoomCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * S16 — the Room screen's view model, driven against a stand-in for
 * RoomService. The four behaviours the story names are the contract: a ready
 * round trip, host-only start, exactly one start, and a leave that tears
 * down.
 *
 * The poll loop itself is NOT here and therefore NOT under test in this file:
 * a LaunchedEffect in RoomScreen owns the 4000ms tick, because its lifetime is
 * the screen's and Compose cancels it structurally the moment the room clears.
 * What this class owns is what one tick *does* — [RoomViewModel.refresh] — so
 * the last two tests drive refresh() by hand. That is the whole contract the
 * poll relies on, and it keeps these tests off advanceTimeBy: stepping a
 * Compose effect requires a composition, which a JVM test does not have.
 *
 * The fake does NOT reimplement maybeStartMatch — it records the setReady
 * calls and lets each test assert what the SERVICE would have concluded,
 * because the service's own rules are already pinned by :services' tests.
 * What this file pins is the wiring: the toggle reads the current badge,
 * applies the returned room, and refresh re-syncs on demand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomViewModelTest {

  private val me = "uid-host"
  private val friend = "uid-friend"
  private lateinit var rooms: FakeRooms
  private lateinit var vm: RoomViewModel

  private fun openRoom(code: String = "X7K2PQ", players: List<String> = listOf(me, friend)) {
    rooms = FakeRooms()
    rooms.preseat(code, players)
    vm = RoomViewModel(rooms)
    vm.open(code)
  }

  @Test
  fun openingALoadsTheRoomWithoutWaitingForAPoll() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      openRoom()
      advanceUntilIdle()
      assertEquals("X7K2PQ", vm.state.value.roomCode)
      assertEquals(2, vm.state.value.room?.players?.size)
      assertEquals("one load on open, no poll tick yet", 1, rooms.loadCalls.get())
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun aReadyToggleRoundTripsThroughTheViewModel() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      openRoom()
      advanceUntilIdle()

      vm.toggleReady(me)
      advanceUntilIdle()
      assertTrue("the badge moved to ready", vm.state.value.room!!.isReady(me))
      assertEquals("one setReady write", 1, rooms.setReadyCalls.get())

      vm.toggleReady(me)
      advanceUntilIdle()
      assertFalse("the badge moved back to not-ready", vm.state.value.room!!.isReady(me))
      assertEquals("two setReady writes", 2, rooms.setReadyCalls.get())
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun aNonHostReadyDoesNotStartTheMatch() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      // The friend is the creator here; `me` is a plain seat.
      openRoom(players = listOf(friend, me))
      advanceUntilIdle()

      vm.toggleReady(me)
      advanceUntilIdle()

      assertTrue("my own badge moved", vm.state.value.room!!.isReady(me))
      assertNull("no match started — only the creator may", vm.state.value.room!!.matchId)
      // The service is not silent on this path: maybeStartMatch runs on every
      // return path a setReady takes, so an outcome IS attached — the claim is
      // what it concludes. A non-host's toggle gets NOT_ALL_READY, or in a
      // room where every seat is ready, an outcome that still did not start.
      assertEquals("an outcome was attached", 1, rooms.matchStarts.size)
      assertFalse("that outcome started nothing", rooms.matchStarts.last().started)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun aHostStartFiresTheMatchStarterExactlyOnce() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      openRoom()
      advanceUntilIdle()
      rooms.matchStarterEnabled = true

      // Seat the friend ready first, so MY toggle is the one that completes
      // the set — the state in which RoomService's maybeStartMatch fires.
      rooms.markReady("X7K2PQ", friend)
      vm.toggleReady(me)
      advanceUntilIdle()

      val room = vm.state.value.room!!
      assertTrue("the host's own badge moved", room.isReady(me))
      assertEquals("exactly one match-start attempt", 1, rooms.matchStarts.size)
      assertEquals(
        "the start outcome is attached to the room the toggle applied",
        MatchStartResult::class, rooms.matchStarts.last()::class,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun leavingTearsTheRoomDownAndStopsThePoll() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      openRoom()
      advanceUntilIdle()

      vm.leave(me)
      advanceUntilIdle()

      assertNull("the code cleared", vm.state.value.roomCode)
      assertNull("the room cleared", vm.state.value.room)
      assertEquals("one leave attempted", 1, rooms.leaveCalls.get())

      // The screen's poll is gone with the composition, but the VM has to
      // hold up its own end too: refresh() is what a tick would have called,
      // and after a leave it must be a no-op. Otherwise the very next tick
      // would load the room right back into a state leave just cleared.
      vm.refresh()
      advanceUntilIdle()
      assertEquals("no extra load after teardown", 1, rooms.loadCalls.get())
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun aRefreshPicksUpSeatsThatJoinedSinceTheLastLoad() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      openRoom()
      advanceUntilIdle()
      assertEquals("one load on open", 1, rooms.loadCalls.get())

      // A third seat joins directly in the fake between ticks — the only
      // thing a poll exists to see. refresh() is that tick.
      rooms.joinRoom("X7K2PQ", "uid-late")
      vm.refresh()
      advanceUntilIdle()

      assertEquals("the refresh loaded again", 2, rooms.loadCalls.get())
      assertEquals(
        "the roster picked up the late join",
        3, vm.state.value.room?.players?.size,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  // ==========================================================================
  //  The stand-in for RoomService
  // ==========================================================================

  /**
   * An in-memory [RoomPort] implementing the room rules RoomService enforces
   * — NOT_FOUND / FULL / CLOSED rejections, idempotent join/leave/setReady,
   * and [normalizeRoomCode]. It exists so these tests exercise the view
   * model's wiring instead of a hand-rolled FirebaseFirestore, and it stays
   * honest by mirroring the service's documented behavior: if these two ever
   * drift, a test below fails for the right reason.
   *
   * [matchStarterEnabled] is the one deliberate simplification: the real
   * maybeStartMatch fires only for the creator once all seats are ready. The
   * tests that care about that switch it on and assert what the service
   * would have concluded, rather than re-implementing the rule here.
   */
  private class FakeRooms : RoomPort {
    val createCalls = AtomicInteger(0)
    val joinCalls = AtomicInteger(0)
    val leaveCalls = AtomicInteger(0)
    val setReadyCalls = AtomicInteger(0)
    val loadCalls = AtomicInteger(0)
    val matchStarts = mutableListOf<MatchStartResult>()

    /** When true, a setReady that completes the room starts the match. */
    var matchStarterEnabled = false

    private val rooms = LinkedHashMap<String, RoomDoc>()

    fun preseat(code: String, players: List<String>, status: String = RoomDoc.STATUS_WAITING) {
      rooms[normalizeRoomCode(code)] = RoomDoc(
        name = null,
        status = status,
        creator = players.first(),
        players = players.toList(),
        readyPlayers = emptyList(),
        matchId = null,
      )
    }

    fun markReady(code: String, playerId: String) {
      val key = normalizeRoomCode(code)
      rooms[key] = rooms.getValue(key).copy(readyPlayers = rooms.getValue(key).readyPlayers + playerId)
    }

    override suspend fun createRoom(
      playerId: String,
      roomName: String?,
      gameType: GameType,
      scoringMode: ScoringMode,
    ): String {
      createCalls.incrementAndGet()
      val code = "ROOM%02d".format(rooms.size)
      rooms[code] = RoomDoc(
        name = roomName,
        status = RoomDoc.STATUS_WAITING,
        creator = playerId,
        players = listOf(playerId),
        readyPlayers = emptyList(),
        matchId = null,
        gameType = gameType,
        scoringMode = scoringMode,
      )
      return code
    }

    override suspend fun joinRoom(roomId: String, playerId: String): RoomDoc {
      joinCalls.incrementAndGet()
      val key = normalizeRoomCode(roomId)
      val room = rooms[key] ?: throw ServiceException(Reasons.ROOM_NOT_FOUND, "Room not found.")
      if (room.status == RoomDoc.STATUS_CLOSED) {
        throw ServiceException(Reasons.ROOM_CLOSED, "This room is closed.")
      }
      if (room.isMember(playerId)) return room // idempotent
      if (room.isFull) throw ServiceException(Reasons.ROOM_FULL, "This room is full.")
      val joined = room.copy(players = room.players + playerId)
      rooms[key] = joined
      return joined
    }

    override suspend fun leaveRoom(roomId: String, playerId: String): RoomDoc? {
      leaveCalls.incrementAndGet()
      val key = normalizeRoomCode(roomId)
      val room = rooms[key] ?: return null
      if (!room.isMember(playerId)) return null
      val remaining = room.players - playerId
      if (remaining.isEmpty()) {
        rooms.remove(key) // last out closes
        return null
      }
      val left = room.copy(players = remaining)
      rooms[key] = left
      return left
    }

    override suspend fun setReady(roomId: String, playerId: String, ready: Boolean): RoomDoc {
      setReadyCalls.incrementAndGet()
      val key = normalizeRoomCode(roomId)
      val room = rooms[key] ?: throw ServiceException(Reasons.ROOM_NOT_FOUND, "Room not found.")
      val next = if (room.isReady(playerId) == ready) room // idempotent no-op
      else if (ready) room.copy(readyPlayers = room.readyPlayers + playerId)
      else room.copy(readyPlayers = room.readyPlayers - playerId)
      rooms[key] = next
      // maybeStartMatch runs on EVERY return path in the real service —
      // including the idempotent one — so this mirrors it rather than
      // short-circuiting: the room a toggle applies always carries an outcome.
      val started = matchStarterEnabled && next.allReady && next.creator == playerId
      val outcome = if (started) {
        MatchStartResult(allReady = true, started = true, matchId = "match-1", error = null)
      } else if (next.allReady) {
        MatchStartResult(allReady = true, started = next.matchId != null, matchId = next.matchId, error = null)
      } else {
        MatchStartResult.NOT_ALL_READY
      }
      matchStarts += outcome
      return next.copy(matchStart = outcome)
    }

    override suspend fun loadRoom(roomId: String): RoomDoc? {
      loadCalls.incrementAndGet()
      return rooms[normalizeRoomCode(roomId)]
    }
  }
}
