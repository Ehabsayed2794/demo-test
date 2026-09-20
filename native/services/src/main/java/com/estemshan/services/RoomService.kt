package com.estemshan.services

import com.estemshan.services.model.MatchStartResult
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.ROOM_CODE_ALPHABET
import com.estemshan.services.model.ROOM_CODE_LENGTH
import com.estemshan.services.model.ROOM_CODE_MAX_ATTEMPTS
import com.estemshan.services.model.RoomDoc
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.normalizeRoomCode
import com.estemshan.services.session.AuthPort
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * RoomService — port of design-ui/room-service.js. Owns EVERY room-state
 * mutation except startMatch()'s single room↔match transaction (that
 * atomicity belongs to MatchService; see match-service.js's boundary
 * note). One-directional dependency only: RoomService → MatchService via
 * [matchStarter], never back.
 */
class RoomService(
  private val db: FirebaseFirestore,
  private val auth: AuthPort,
  /** startMatch(roomId) — MatchService's atomic room↔match write. */
  private val matchStarter: suspend (roomId: String) -> String,
  /** Best-effort currentRoomId mirror onto the player's own profile;
   *  never throws (the room action already succeeded). */
  private val profileRoomSync: suspend (playerId: String, roomId: String?) -> Unit =
    { _, _ -> },
) {

  private val rooms get() = db.collection("rooms")

  /**
   * Creates a room under a fresh 6-char unambiguous code. Collision-safe:
   * regenerated up to [ROOM_CODE_MAX_ATTEMPTS] times, then falls back to a
   * Firestore auto-ID so creation never fails. readyPlayers starts empty
   * — creating a room does not imply being ready.
   */
  suspend fun createRoom(playerId: String, roomName: String?): String {
    require(playerId.isNotEmpty()) { "createRoom: playerId is required." }
    return tryCreateRoomWithCode(playerId, roomName, ROOM_CODE_MAX_ATTEMPTS)
  }

  private suspend fun tryCreateRoomWithCode(
    playerId: String,
    roomName: String?,
    attemptsLeft: Int,
  ): String {
    val room = hashMapOf(
      "name" to roomName,
      "status" to RoomDoc.STATUS_WAITING,
      "creator" to playerId,
      "players" to listOf(playerId),
      "readyPlayers" to emptyList<String>(),
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp(),
    )
    if (attemptsLeft <= 0) {
      // Last-resort fallback: plain auto-ID (the original behaviour).
      val autoRef = rooms.document()
      autoRef.set(room).await()
      syncProfile(playerId, autoRef.id)
      return autoRef.id
    }
    val code = generateRoomCode()
    val ref = rooms.document(code)
    if (ref.getBlocking().exists()) {
      return tryCreateRoomWithCode(playerId, roomName, attemptsLeft - 1)
    }
    ref.set(room).await()
    syncProfile(playerId, code)
    return code
  }

  /**
   * Joins an existing room. Existence/open/not-full validated INSIDE a
   * transaction — the guard against two joins racing for the last slot.
   * Idempotent: joining a room you are already in is a no-op, not an error.
   */
  suspend fun joinRoom(roomId: String, playerId: String): RoomDoc {
    require(roomId.isNotEmpty() && playerId.isNotEmpty()) {
      "joinRoom: roomId and playerId are both required."
    }
    val ref = rooms.document(normalizeRoomCode(roomId))
    val joined = runTx(db) { tx ->
      val snap = tx.getBlocking(ref)
      val room = parseRoom(snap) ?: return@runTx TxOutcome.Err(
        ServiceException(Reasons.ROOM_NOT_FOUND, "Room not found."),
      )
      if (room.status == RoomDoc.STATUS_CLOSED) {
        return@runTx TxOutcome.Err(
          ServiceException(Reasons.ROOM_CLOSED, "This room is closed."),
        )
      }
      if (room.isMember(playerId)) {
        return@runTx TxOutcome.Ok(room) // already a member — no-op
      }
      if (room.isFull) {
        return@runTx TxOutcome.Err(
          ServiceException(Reasons.ROOM_FULL, "This room is full."),
        )
      }
      val updated = room.players + playerId
      tx.update(ref, mapOf(
        "players" to updated,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(room.copy(players = updated))
    }
    syncProfile(playerId, roomId)
    return joined
  }

  /**
   * Leaves a room. Last player out closes the room; a departing creator
   * transfers ownership to the next remaining player in array order
   * (inline here — not via the unimplemented transferHost()). Idempotent:
   * leaving a room you are not in, or that no longer exists, is a no-op.
   */
  suspend fun leaveRoom(roomId: String, playerId: String): RoomDoc? {
    require(roomId.isNotEmpty() && playerId.isNotEmpty()) {
      "leaveRoom: roomId and playerId are both required."
    }
    val ref = rooms.document(normalizeRoomCode(roomId))
    val left = runTx(db) { tx ->
      val snap = tx.getBlocking(ref)
      if (!snap.exists()) return@runTx TxOutcome.Ok<RoomDoc?>(null) // gone — no-op
      val room = parseRoom(snap) ?: return@runTx TxOutcome.Ok<RoomDoc?>(null)
      val players = room.players - playerId
      // A departing player must not remain marked ready.
      val readyPlayers = room.readyPlayers - playerId
      val patch = hashMapOf(
        "players" to players,
        "readyPlayers" to readyPlayers,
        "updatedAt" to FieldValue.serverTimestamp(),
      )
      when {
        players.isEmpty() -> patch["status"] = RoomDoc.STATUS_CLOSED
        room.creator == playerId -> patch["creator"] = players.first()
      }
      tx.update(ref, patch)
      TxOutcome.Ok<RoomDoc?>(room.copy(players = players, readyPlayers = readyPlayers))
    }
    syncProfile(playerId, null)
    return left
  }

  /**
   * Sets (or clears) exactly one player's own ready state. Requires room
   * membership. Idempotent: the same value twice performs no second write.
   * Never touches players/creator/status/name. When this write makes
   * everyone ready, [matchStarter] is invoked and its outcome is attached
   * to the returned room as matchStart — setReady itself never rejects
   * because a match-start failed (the ready toggle already succeeded).
   */
  suspend fun setReady(roomId: String, playerId: String, ready: Boolean): RoomDoc {
    require(roomId.isNotEmpty() && playerId.isNotEmpty()) {
      "setReady: roomId and playerId are both required."
    }
    val ref = rooms.document(normalizeRoomCode(roomId))
    val room = runTx(db) { tx ->
      val snap = tx.getBlocking(ref)
      val room = parseRoom(snap) ?: return@runTx TxOutcome.Err(
        ServiceException(Reasons.ROOM_NOT_FOUND, "Room not found."),
      )
      if (room.status == RoomDoc.STATUS_CLOSED) {
        return@runTx TxOutcome.Err(
          ServiceException(Reasons.ROOM_CLOSED, "This room is closed."),
        )
      }
      if (!room.isMember(playerId)) {
        return@runTx TxOutcome.Err(
          ServiceException(Reasons.NOT_A_MEMBER, "You are not a member of this room."),
        )
      }
      if (room.isReady(playerId) == ready) {
        return@runTx TxOutcome.Ok(room) // already in the desired state
      }
      val nextReady = if (ready) room.readyPlayers + playerId else room.readyPlayers - playerId
      tx.update(ref, mapOf(
        "readyPlayers" to nextReady,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(room.copy(readyPlayers = nextReady))
    }
    return room.copy(matchStart = maybeStartMatch(roomId, room))
  }

  /**
   * Resolves a structured result describing whether a match-start was
   * attempted and what happened — never rejects. Only the room creator
   * attempts the start: rules allow only that, and other ready clients
   * must not generate a predictable permission-denied retry loop.
   */
  private suspend fun maybeStartMatch(roomId: String, room: RoomDoc): MatchStartResult {
    if (room.status != RoomDoc.STATUS_WAITING || !room.allReady) {
      return MatchStartResult.NOT_ALL_READY
    }
    val currentUid = auth.currentUid()
    if (currentUid != null && currentUid != room.creator) {
      return MatchStartResult(
        allReady = true, started = room.matchId != null, matchId = room.matchId, error = null,
      )
    }
    return try {
      val matchId = matchStarter(roomId)
      MatchStartResult(allReady = true, started = true, matchId = matchId, error = null)
    } catch (err: ServiceException) {
      MatchStartResult(allReady = true, started = false, matchId = null, error = err)
    }
  }

  /** Read-only fetch; null (not an error) when the room is gone. */
  suspend fun loadRoom(roomId: String): RoomDoc? {
    require(roomId.isNotEmpty()) { "loadRoom: roomId is required." }
    val snap = rooms.document(normalizeRoomCode(roomId)).getBlocking()
    return if (snap.exists()) parseRoom(snap) else null
  }

  /**
   * NOT IMPLEMENTED — explicit stubs, ported as such per
   * docs/specs/03-transactions.md §8. leaveRoom handles transfer/close
   * inline; these exist so the API surface matches and no caller can
   * mistake silence for work done.
   */
  fun transferHost(roomId: String, newHostUid: String): Nothing =
    throw NotImplementedError("RoomService.transferHost() is not implemented yet.")

  fun closeRoom(roomId: String): Nothing =
    throw NotImplementedError("RoomService.closeRoom() is not implemented yet.")

  private suspend fun syncProfile(playerId: String, roomId: String?) {
    try {
      profileRoomSync(playerId, roomId)
    } catch (err: Throwable) {
      // Non-fatal: the room action itself already succeeded.
    }
  }

  private fun parseRoom(snap: com.google.firebase.firestore.DocumentSnapshot): RoomDoc? =
    if (!snap.exists()) null else RoomDoc.fromFields(snap.data ?: emptyMap())

  private fun generateRoomCode(): String =
    (1..ROOM_CODE_LENGTH).map { ROOM_CODE_ALPHABET.random() }.joinToString("")
}
