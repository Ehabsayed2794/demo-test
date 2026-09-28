package com.estemshan.game.ui.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.game.data.OnlineServices
import com.estemshan.services.RoomPort
import com.estemshan.services.model.RoomDoc
import com.estemshan.services.model.ServiceException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * S16 — the state behind the Room/waiting screen. Wiring only: every room
 * rule lives in [RoomService] behind [rooms]; this class owns the room
 * screen's own concerns — the roster it is showing and one busy flag.
 *
 * The roster is POLLed, not listened to. The web lobby made the same call
 * (design-ui/lobby/index.html's startMatchPolling): a live room listener
 * means an open subscription for every player who opens a room, and that is
 * more than a waiting screen justifies. The poll itself lives in the screen's
 * own [androidx.compose.runtime.LaunchedEffect] — a "keep this screen fresh
 * while it is shown" concern, so it dies with composition and needs no
 * lifetime bookkeeping here. This class just offers the tick: [refresh] is
 * the one thing that reads the room, and it never writes. The one thing that
 * writes is [toggleReady], whose returned room is applied directly, so the
 * local player's own ready badge never waits for a tick.
 *
 * Host-start is NOT a button here. [RoomService]'s maybeStartMatch fires the
 * match the moment every seat is ready AND the acting uid is the room's
 * creator, so [toggleReady] IS the start for a host; a non-host's identical
 * toggle sets their own ready bit and waits for the room's matchId to land
 * on a poll tick. That is the service's documented permission model
 * (firestore.rules allows only the creator to create the match), surfaced
 * rather than re-implemented.
 */
class RoomViewModel(
  private val rooms: RoomPort = OnlineServices.rooms,
) : ViewModel() {

  private val _state = MutableStateFlow(RoomUiState())
  val state: StateFlow<RoomUiState> = _state.asStateFlow()

  /**
   * Take the screen for [code]: load the room once. Re-entrant by design —
   * the screen calls this on entering composition, and a recomposition must
   * not reload. The poll then calls [refresh] on its own cadence.
   */
  fun open(code: String) {
    if (_state.value.roomCode == code) return
    _state.value = RoomUiState(roomCode = code)
    refresh()
  }

  /** One poll tick: re-read the room. Stops itself if the room is gone. */
  fun refresh() {
    val code = _state.value.roomCode ?: return
    launch {
      val room = rooms.loadRoom(code)
      _state.value = _state.value.copy(room = room)
    }
  }

  /**
   * Toggle your own ready state. The returned room is applied immediately
   * (the service hands back the post-write state), and when this toggle made
   * every seat ready the same result carries [MatchStartResult] — for a host
   * that is the match actually starting.
   */
  fun toggleReady(playerId: String) {
    val code = _state.value.roomCode ?: return
    val room = _state.value.room ?: return
    if (_state.value.busy) return
    launch {
      _state.value = _state.value.copy(busy = true)
      try {
        val next = rooms.setReady(code, playerId, !room.isReady(playerId))
        _state.value = _state.value.copy(busy = false, room = next)
      } catch (e: ServiceException) {
        // setReady rejects on NOT_FOUND / CLOSED / NOT_A_MEMBER. The room is
        // stale either way, so the poll's next tick re-syncs; the badge just
        // doesn't move on this tap.
        _state.value = _state.value.copy(busy = false)
      }
    }
  }

  /**
   * Leave the room and return to the lobby. [RoomService.leaveRoom] is
   * idempotent, so the only failure left is a transport one — stranding the
   * player in a room they asked to leave is worse than letting them out.
   * Clearing the whole state is what makes the teardown observable: [open]
   * guards on the code, so a later re-entry is a fresh open, and the screen's
   * poll effect keys on it and dies.
   */
  fun leave(playerId: String) {
    val code = _state.value.roomCode ?: return
    if (_state.value.busy) return
    launch {
      _state.value = _state.value.copy(busy = true)
      try {
        rooms.leaveRoom(code, playerId)
      } catch (e: ServiceException) {
        // Best-effort teardown; see the KDoc.
      }
      _state.value = RoomUiState()
    }
  }

  private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

  companion object {
    /** The web lobby polls at 4000ms; matched so the two stay one design. */
    const val POLL_INTERVAL_MS = 4000L
  }
}

/**
 * The room screen's view of itself: the code it is sitting at, the room's
 * current state (null while the first load is in flight or once the room is
 * gone), and one busy flag.
 */
data class RoomUiState(
  /** The code the screen was opened with; null once [RoomViewModel.leave] cleared it. */
  val roomCode: String? = null,
  /** The room, or null before the first load lands / after the room is gone. */
  val room: RoomDoc? = null,
  val busy: Boolean = false,
)
