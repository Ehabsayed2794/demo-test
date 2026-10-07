package com.estemshan.game.ui.lobby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.game.data.OnlineServices
import com.estemshan.services.PlayerPort
import com.estemshan.services.RoomPort
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.normalizeRoomCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * S15 — the state behind the Real Lobby. Wiring only: every room rule lives
 * in [RoomService] behind [rooms], and this class owns exactly the lobby's
 * own concerns — the code of the room you are seated at, one busy flag, and
 * the reason the last join failed.
 *
 * No room list. Native v1 is invite-by-code only (spec 01-screens.md §1),
 * so the lobby's whole online surface is three actions; the waiting room,
 * participant list, ready-up, and host-start are the Room screen (S16,
 * [com.estemshan.game.ui.Routes.ROOM]) and arrive with it. [RoomPort] is
 * correspondingly narrower than RoomService — nothing here calls a method
 * the lobby has no use for.
 *
 * The room code this holds comes from create/join and is normalized the way
 * RoomService normalizes a typed code, so "x7k2pq" and "X7K2PQ" are the same
 * seat; on a failed join it stays null and the lobby stays open.
 */
class LobbyViewModel(
  private val rooms: RoomPort = OnlineServices.rooms,
  private val players: PlayerPort = OnlineServices.players,
) : ViewModel() {

  private val _state = MutableStateFlow(LobbyUiState())
  val state: StateFlow<LobbyUiState> = _state.asStateFlow()

  /**
   * Read players/{uid}.currentMatchId — the reconnect pointer the services
   * layer writes when a match starts and S17's OnlineMatchViewModel clears
   * when one completes. A non-null value means a match is still live for this
   * player, so the lobby offers the one-tap way back into it. Null is also
   * the honest result of any read failure, so a transport hiccup costs the
   * entry rather than the lobby.
   */
  fun loadResume(playerId: String) {
    if (playerId.isEmpty()) return
    launch {
      val matchId = players.currentMatchId(playerId)
      _state.value = _state.value.copy(resumableMatchId = matchId)
    }
  }

  /** Forget the resume entry — the player took it, or backed out of it. */
  fun clearResume() {
    if (_state.value.resumableMatchId == null) return
    _state.value = _state.value.copy(resumableMatchId = null)
  }

  /**
   * Create a private room and show its code to share. A double-tap while one
   * is in flight would open a second room, so a busy lobby ignores the tap.
   */
  fun createRoom(playerId: String) {
    if (_state.value.busy) return
    launch {
      _state.value = _state.value.copy(busy = true, joinError = null)
      // S66: explicit FULL/NORMAL — the configured values come from S69's
      // config state, which does not exist yet.
      val code = rooms.createRoom(playerId, null, GameType.FULL, ScoringMode.NORMAL)
      _state.value = _state.value.copy(busy = false, roomCode = code, createdCode = code)
    }
  }

  /**
   * Join the room at [rawCode]. An empty code is rejected without a service
   * call; anything else is normalized and handed to [rooms], and a rejection
   * surfaces as the reason it was refused.
   */
  fun joinRoom(playerId: String, rawCode: String) {
    if (_state.value.busy) return
    val code = rawCode.trim()
    if (code.isEmpty()) {
      _state.value = _state.value.copy(joinError = LobbyJoinError.EMPTY)
      return
    }
    launch {
      _state.value = _state.value.copy(busy = true, joinError = null)
      try {
        rooms.joinRoom(code, playerId)
        _state.value = _state.value.copy(
          busy = false,
          roomCode = normalizeRoomCode(code),
          joinError = null,
        )
      } catch (e: ServiceException) {
        _state.value = _state.value.copy(busy = false, joinError = e.reason.toLobbyError())
      } catch (e: IllegalArgumentException) {
        // RoomService requires non-empty ids; the empty case is caught above,
        // so this is a caller bug — surface it as the empty-code error rather
        // than crashing the lobby over a room action.
        _state.value = _state.value.copy(busy = false, joinError = LobbyJoinError.EMPTY)
      }
    }
  }

  /**
   * Leave the room you are seated at, if any. [RoomService.leaveRoom] is
   * idempotent, so the only failure left is a transport one — and stranding
   * the player in a room they asked to leave is worse than letting them out,
   * so the local seat clears either way.
   */
  fun leaveRoom(playerId: String) {
    val code = _state.value.roomCode ?: return
    if (_state.value.busy) return
    launch {
      _state.value = _state.value.copy(busy = true)
      try {
        rooms.leaveRoom(code, playerId)
      } catch (e: ServiceException) {
        // Best-effort teardown; see the KDoc.
      }
      _state.value = LobbyUiState()
    }
  }

  /** The player has seen the code from [createRoom]; stop showing it. */
  fun dismissCreatedCode() {
    _state.value = _state.value.copy(createdCode = null)
  }

  /** Clear the last join's failure so reopening the dialog starts clean. */
  fun clearJoinError() {
    _state.value = _state.value.copy(joinError = null)
  }

  private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

  /** Map a [Reasons] code onto the failure the lobby can show. */
  private fun String.toLobbyError(): LobbyJoinError = when (this) {
    Reasons.ROOM_NOT_FOUND -> LobbyJoinError.NOT_FOUND
    Reasons.ROOM_FULL -> LobbyJoinError.FULL
    Reasons.ROOM_CLOSED -> LobbyJoinError.CLOSED
    else -> LobbyJoinError.GENERIC
  }
}

/**
 * The lobby's view of itself: which room it is in (null = the open lobby),
 * the code it just created and should show once, a busy flag, and the reason
 * the last join was refused.
 */
data class LobbyUiState(
  val busy: Boolean = false,
  /** The room the player is seated at, or null in the open lobby. */
  val roomCode: String? = null,
  /** A room [LobbyViewModel.createRoom] just made — shown to share, once. */
  val createdCode: String? = null,
  /** Why the last join failed, or null. */
  val joinError: LobbyJoinError? = null,
  /**
   * A live match this player is part of (players/{uid}.currentMatchId), or
   * null — the lobby's reconnect entry. Cleared once taken.
   */
  val resumableMatchId: String? = null,
)

/**
 * A join failure the lobby can render, mapped off [ServiceException.reason]
 * (never the message — that's prose, this is checkable). GENERIC absorbs
 * permission and availability failures, which the lobby can only retry.
 */
enum class LobbyJoinError { EMPTY, NOT_FOUND, FULL, CLOSED, GENERIC }
