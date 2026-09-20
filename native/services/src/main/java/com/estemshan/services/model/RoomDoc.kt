package com.estemshan.services.model

/**
 * Parsed view of rooms/{roomId}. Field shape mirrors
 * design-ui/room-service.js exactly (creator / players / readyPlayers),
 * re-synced to docs/architecture/FirestoreSchema.md in Sprint 3.3.
 */
data class RoomDoc(
  val name: String?,
  val status: String,
  val creator: String,
  val players: List<String>,
  val readyPlayers: List<String>,
  val matchId: String?,
  /** Transient (never persisted): setReady() attaches the outcome of the
   *  match-start attempt it triggered, so a caller can observe success or
   *  failure without setReady itself rejecting on a start failure. */
  val matchStart: MatchStartResult? = null,
) {

  val isFull: Boolean get() = players.size >= MAX_PLAYERS

  /** Every player in the room is ready (players non-empty). */
  val allReady: Boolean
    get() = players.isNotEmpty() && players.all { it in readyPlayers }

  fun isMember(playerId: String): Boolean = playerId in players

  fun isReady(playerId: String): Boolean = playerId in readyPlayers

  companion object {
    const val STATUS_WAITING = "waiting"
    const val STATUS_CLOSED = "closed"
    const val STATUS_IN_GAME = "in_game"

    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Map<String, Any?>): RoomDoc? {
      val creator = fields["creator"] as? String ?: return null
      val players = (fields["players"] as? List<*>)?.mapNotNull { it as? String }
        ?: emptyList()
      return RoomDoc(
        name = fields["name"] as? String,
        status = fields["status"] as? String ?: STATUS_WAITING,
        creator = creator,
        players = players,
        readyPlayers = (fields["readyPlayers"] as? List<*>)?.mapNotNull { it as? String }
          ?: emptyList(),
        matchId = fields["matchId"] as? String,
      )
    }
  }
}

/** Table cap — a plain implementation constant (room-service.js:65). */
const val MAX_PLAYERS = 4

/** Short, human-shareable room codes: unambiguous characters only, so a
 *  code read aloud or copied by hand cannot be misread (room-service.js:96). */
const val ROOM_CODE_LENGTH = 6
const val ROOM_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
const val ROOM_CODE_MAX_ATTEMPTS = 5

/** Normalizes a player-typed code: trims, and uppercases SHORT codes so
 *  "x7k2pq" joins room "X7K2PQ". Long legacy auto-IDs (case-sensitive)
 *  pass through unchanged. */
fun normalizeRoomCode(roomId: String): String {
  val trimmed = roomId.trim()
  return if (trimmed.length <= ROOM_CODE_LENGTH) trimmed.uppercase() else trimmed
}
