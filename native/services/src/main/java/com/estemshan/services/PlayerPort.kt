package com.estemshan.services

import com.estemshan.services.session.AuthPort
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * The player-profile seam — players/{uid}, the one document the rules let a
 * player both read and (partially) write. Its whitelisted fields are exactly
 * displayName, avatarInitial, lastSeenAt, currentRoomId and currentMatchId;
 * true presence is impossible under the frozen rules (players/{uid} is
 * owner-read-only and `list: if false`) so this surface carries only what a
 * client may legitimately touch.
 *
 * S17 consumes one field: [currentMatchId], the reconnect entry point. A
 * cold start reads it to drop the player straight back into the match they
 * were in; [PlayerService.setCurrentMatchId] is the mirror MatchService's
 * startMatch holds (its own profileMatchSync hook) so the two can never
 * disagree about which match a player is in.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → PlayerPort → PlayerService → Firestore
 */
interface PlayerPort {

  /**
   * The match this user is currently playing, or null when they are in none.
   * Never throws: a missing or unreadable profile is "no current match",
   * not an error — the caller falls back to the lobby.
   */
  suspend fun currentMatchId(uid: String): String?

  /**
   * Best-effort mirror of the player's current match onto their own profile.
   * Never throws: [com.estemshan.services.MatchService] has already committed
   * the match write, and a failed mirror must not fail the match. A null
   * [matchId] clears it (the match is over and the player is out).
   */
  suspend fun setCurrentMatchId(uid: String, matchId: String?)
}

/**
 * The authoritative implementation over Firestore. Writes only the two
 * fields the match lifecycle owns (currentMatchId + lastSeenAt); a read
 * returns null for anything other than a real, round-tripped id.
 */
class PlayerService(
  private val db: FirebaseFirestore,
  private val auth: AuthPort,
) : PlayerPort {

  private val players get() = db.collection("players")

  override suspend fun currentMatchId(uid: String): String? {
    if (uid.isEmpty()) return null
    return try {
      val snap = players.document(uid).getBlocking()
      if (!snap.exists()) return null
      // Firestore stores ids as Strings, but a stray type is "no match",
      // never a crash — the caller's fallback is correct for both.
      snap.data?.get(FIELD_CURRENT_MATCH_ID) as? String
    } catch (e: Throwable) {
      null
    }
  }

  override suspend fun setCurrentMatchId(uid: String, matchId: String?) {
    if (uid.isEmpty()) return
    try {
      val fields = mapOf(
        FIELD_CURRENT_MATCH_ID to matchId,
        FIELD_LAST_SEEN_AT to FieldValue.serverTimestamp(),
      )
      // merge() so the write never clobbers displayName/avatarInitial —
      // the profile doc already exists from sign-up, and a `set()` without
      // merge would erase it.
      players.document(uid).set(fields, SetOptions.merge()).await()
    } catch (e: Throwable) {
      // Best-effort by contract: the match write already succeeded.
    }
  }

  private companion object {
    const val FIELD_CURRENT_MATCH_ID = "currentMatchId"
    const val FIELD_LAST_SEEN_AT = "lastSeenAt"
  }
}
