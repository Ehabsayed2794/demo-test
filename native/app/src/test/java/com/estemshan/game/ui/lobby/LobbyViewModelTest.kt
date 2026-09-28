package com.estemshan.game.ui.lobby

import com.estemshan.services.RoomPort
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.RoomDoc
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.normalizeRoomCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * S15 — the Real Lobby's wiring over [RoomPort]. These tests exercise the
 * view model's own concerns: that create yields a code another player can
 * actually join, that a refused join surfaces the reason it was refused
 * without seating the player, and that leave tears the seat down without
 * stranding anyone else.
 *
 * They run against [FakeRooms], an in-memory stand-in that implements
 * RoomService's room CONTRACT — the same reason codes, the same
 * idempotency, the same normalization — rather than faking FirebaseFirestore.
 * There is no SDK fake anywhere in this repo by design (see
 * MatchAdapterConcurrencyTest, which fakes the seams and never the
 * database), and driving a real RoomService needs one; the honest thing to
 * substitute is this interface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbyViewModelTest {

  private val me = "uid-me"
  private val friend = "uid-friend"

  private lateinit var rooms: FakeRooms
  private lateinit var vm: LobbyViewModel

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** A fresh lobby on the test's own dispatcher, so each test starts clean. */
  private fun TestScope.openLobby() {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    rooms = FakeRooms()
    vm = LobbyViewModel(rooms)
  }

  // ==========================================================================
  //  Create: a code another player can join
  // ==========================================================================

  @Test
  fun createReturnsACodeAnotherPlayerCanJoin() = runTest {
    openLobby()

    vm.createRoom(me)
    advanceUntilIdle()

    val code = vm.state.value.roomCode
    assertNotNull("creating returned a room code", code)
    assertEquals("the code is shown to share, exactly once", code, vm.state.value.createdCode)
    assertFalse("creating finished", vm.state.value.busy)

    // The point of a code: somebody else can use it. Joining through the same
    // seam the view model used proves the returned id is a real room, not a
    // label.
    rooms.joinRoom(code!!, friend)
    advanceUntilIdle()
    assertTrue("the created room admits a join", rooms.room(code).isMember(friend))
    assertTrue("the creator is still seated", rooms.room(code).isMember(me))
  }

  @Test
  fun aBusyLobbyIgnoresADoubleCreate() = runTest {
    openLobby()
    rooms.holdCreate()

    // Two taps while the first create is still in flight would open a second
    // room, so the busy guard has to drop the second one. The fake is
    // otherwise synchronous — without holding the first call mid-flight there
    // is no busy window for a second tap to land in.
    vm.createRoom(me)
    advanceUntilIdle()
    assertTrue("the first create is in flight", vm.state.value.busy)

    // The second tap arrives while the first is still in flight.
    vm.createRoom(me)
    advanceUntilIdle()

    rooms.releaseCreate()
    advanceUntilIdle()

    assertEquals("exactly one room was created — the second tap was dropped",
      1, rooms.createCalls.get())
    assertEquals("one room exists", 1, rooms.codes.size)
  }

  // ==========================================================================
  //  Join: refused cleanly, seated on success
  // ==========================================================================

  @Test
  fun anUnknownCodeIsRejectedAndKeepsTheLobbyOpen() = runTest {
    openLobby()

    vm.joinRoom(me, "NOPE00")
    advanceUntilIdle()

    assertEquals("a missing room maps to NOT_FOUND", LobbyJoinError.NOT_FOUND, vm.state.value.joinError)
    assertNull("the player was not seated", vm.state.value.roomCode)
    assertFalse("the failed join is not still busy", vm.state.value.busy)
  }

  @Test
  fun aFullRoomIsRejectedAsFull() = runTest {
    openLobby()
    rooms.preseat("FULL01", listOf("a", "b", "c", "d"))

    vm.joinRoom(me, "FULL01")
    advanceUntilIdle()

    assertEquals(LobbyJoinError.FULL, vm.state.value.joinError)
    assertNull(vm.state.value.roomCode)
    assertFalse("the seat is still mine to take elsewhere", vm.state.value.busy)
  }

  @Test
  fun aClosedRoomIsRejectedAsClosed() = runTest {
    openLobby()
    rooms.preseat("CLOSED", listOf(friend), status = RoomDoc.STATUS_CLOSED)

    vm.joinRoom(me, "CLOSED")
    advanceUntilIdle()

    assertEquals(LobbyJoinError.CLOSED, vm.state.value.joinError)
  }

  @Test
  fun aLowercaseCodeJoinsTheRoomItNormalizesTo() = runTest {
    openLobby()
    rooms.preseat("X7K2PQ", listOf(friend))

    // The player types lowercase; RoomService normalizes short codes up, so
    // the seat must land on the room the host actually made.
    vm.joinRoom(me, "x7k2pq")
    advanceUntilIdle()

    assertNull("lowercase code joined", vm.state.value.joinError)
    assertEquals("the seat is stored normalized", "X7K2PQ", vm.state.value.roomCode)
    assertTrue("the player actually seated", rooms.room("X7K2PQ").isMember(me))
  }

  @Test
  fun anEmptyJoinCodeFailsWithoutCallingTheService() = runTest {
    openLobby()

    vm.joinRoom(me, "   ")
    advanceUntilIdle()

    assertEquals(LobbyJoinError.EMPTY, vm.state.value.joinError)
    assertEquals("no join was attempted", 0, rooms.joinCalls.get())
    assertNull(vm.state.value.roomCode)
  }

  // ==========================================================================
  //  Leave: tears down the seat, keeps the room
  // ==========================================================================

  @Test
  fun leaveClearsTheSeatAndKeepsTheRoomForEveryoneElse() = runTest {
    openLobby()
    rooms.preseat("ROOM00", listOf(me, friend))

    vm.joinRoom(me, "ROOM00")
    advanceUntilIdle()
    assertEquals("ROOM00", vm.state.value.roomCode)

    vm.leaveRoom(me)
    advanceUntilIdle()

    assertNull("leaving cleared the seat", vm.state.value.roomCode)
    assertNull("leaving cleared the created-code dialog", vm.state.value.createdCode)
    assertNull("leaving cleared the stale join error", vm.state.value.joinError)
    assertTrue("the room survives for the other player", rooms.room("ROOM00").isMember(friend))
  }

  @Test
  fun leavingWhenNotInARoomIsANoOp() = runTest {
    openLobby()

    vm.leaveRoom(me)
    advanceUntilIdle()

    assertNull("nothing to leave", vm.state.value.roomCode)
    assertEquals("no leave was attempted", 0, rooms.leaveCalls.get())
  }

  // ==========================================================================
  //  The stand-in for RoomService
  // ==========================================================================

  /**
   * An in-memory [RoomPort] implementing the room rules RoomService enforces
   * — NOT_FOUND / FULL / CLOSED rejections, idempotent join and leave, and
   * [normalizeRoomCode]. It exists so these tests exercise the view model's
   * wiring instead of a hand-rolled FirebaseFirestore, and it stays honest by
   * mirroring the service's documented behavior: if these two ever drift, a
   * test below fails for the right reason.
   */
  private class FakeRooms : RoomPort {
    val createCalls = AtomicInteger(0)
    val joinCalls = AtomicInteger(0)
    val leaveCalls = AtomicInteger(0)

    private val rooms = LinkedHashMap<String, RoomDoc>()
    private var nextCode = 0

    /** Parks createRoom until released. Complete by default, so only a test
     *  that wants a create in flight has to touch it. */
    private var createGate: CompletableDeferred<Unit> =
      CompletableDeferred<Unit>().apply { complete(Unit) }

    val codes: Set<String> get() = rooms.keys

    /** Hold the next createRoom mid-flight until [releaseCreate] — the window
     *  a double-tap lands in, which a synchronous fake cannot otherwise show. */
    fun holdCreate() { createGate = CompletableDeferred() }

    fun releaseCreate() { createGate.complete(Unit) }

    /** The room at [code], as RoomService would have stored it. */
    fun room(code: String): RoomDoc = rooms.getValue(normalizeRoomCode(code))

    /** Seat a room that already exists — the state when a code is shared with
     *  you, which createRoom is not the way to reach. */
    fun preseat(code: String, players: List<String>, status: String = RoomDoc.STATUS_WAITING) {
      rooms[normalizeRoomCode(code)] = RoomDoc(
        name = null,
        status = status,
        creator = players.firstOrNull() ?: "host",
        players = players.toList(),
        readyPlayers = emptyList(),
        matchId = null,
      )
    }

    override suspend fun createRoom(playerId: String, roomName: String?): String {
      createGate.await()
      createCalls.incrementAndGet()
      // Deterministic 6-char uppercase codes: unique, so the collision retry
      // the real generator needs never applies here.
      val code = "ROOM%02d".format(nextCode++)
      rooms[code] = RoomDoc(
        name = roomName,
        status = RoomDoc.STATUS_WAITING,
        creator = playerId,
        players = listOf(playerId),
        readyPlayers = emptyList(),
        matchId = null,
      )
      return code
    }

    override suspend fun joinRoom(roomId: String, playerId: String): RoomDoc {
      joinCalls.incrementAndGet()
      val key = normalizeRoomCode(roomId)
      val room = rooms[key]
        ?: throw ServiceException(Reasons.ROOM_NOT_FOUND, "Room not found.")
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

    // The Room screen's two — unused by these lobby tests, but the seam is
    // shared, so they stand here mirroring RoomService rather than throwing.

    override suspend fun setReady(roomId: String, playerId: String, ready: Boolean): RoomDoc {
      val key = normalizeRoomCode(roomId)
      val room = rooms[key] ?: throw ServiceException(Reasons.ROOM_NOT_FOUND, "Room not found.")
      if (room.isReady(playerId) == ready) return room // idempotent no-op
      val next = if (ready) room.copy(readyPlayers = room.readyPlayers + playerId)
      else room.copy(readyPlayers = room.readyPlayers - playerId)
      rooms[key] = next
      return next
    }

    override suspend fun loadRoom(roomId: String): RoomDoc? = rooms[normalizeRoomCode(roomId)]
  }
}
