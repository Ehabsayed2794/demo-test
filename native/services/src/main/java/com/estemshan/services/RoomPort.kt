package com.estemshan.services

import com.estemshan.services.model.RoomDoc
import com.estemshan.services.model.ServiceException

/**
 * The lobby's seam over room state. Phase 5's Real Lobby (S15) consumes
 * exactly this surface; the authoritative implementation is [RoomService],
 * reached in production through
 * [com.estemshan.game.data.OnlineServices.rooms].
 *
 * Deliberately NARROWER than [RoomService]: the lobby creates, joins, and
 * leaves rooms. Ready-up, participant display, and host-start belong to the
 * Room screen (S16), so those join the seam when that screen arrives —
 * [RoomService.setReady] is here now because the Room screen is the one
 * surface that calls it. An interface with an unreachable member is dead
 * surface.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → RoomPort → RoomService → Firestore
 * The lobby never touches Firestore directly, and a test stands in for this
 * interface rather than faking the SDK — [FirebaseFirestore] has no fake in
 * this repo by design, and the surrounding seam is the cheaper thing to
 * substitute.
 */
interface RoomPort {

  /**
   * Creates a private room under a fresh shareable code and seats [playerId]
   * in it. Returns the code to hand to the other players. Requires a
   * non-empty [playerId] (throws [IllegalArgumentException] otherwise).
   */
  suspend fun createRoom(playerId: String, roomName: String?): String

  /**
   * Joins the room at [roomId]. A missing, closed, or full room is rejected
   * by throwing [ServiceException] — branch on its [ServiceException.reason]
   * (never the message) to tell the player what happened. Idempotent: joining
   * a room you are already in is a no-op, not an error.
   */
  suspend fun joinRoom(roomId: String, playerId: String): RoomDoc

  /**
   * Leaves the room at [roomId]; idempotent (leaving a room you are not in,
   * or that no longer exists, is a no-op returning null). Returns the room's
   * remaining state, or null when your departure closed it.
   */
  suspend fun leaveRoom(roomId: String, playerId: String): RoomDoc?

  /**
   * Sets (or clears) your own ready state in [roomId]. Requires membership;
   * idempotent — the same value twice performs no second write. The returned
   * room carries [RoomDoc.matchStart]: when this write made every seat ready,
   * [RoomService]'s own maybeStartMatch() attempted the match start, and its
   * outcome is attached there. Branch on [ServiceException.reason], never the
   * message. setReady itself never rejects because a match-start failed —
   * the ready toggle already succeeded independently of it.
   */
  suspend fun setReady(roomId: String, playerId: String, ready: Boolean): RoomDoc

  /** Read-only fetch of the room at [roomId]; null (not an error) when it is
   *  gone. The Room screen polls this to see seats fill and the match land. */
  suspend fun loadRoom(roomId: String): RoomDoc?
}
