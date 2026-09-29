package com.estemshan.services

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
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

  /**
   * S19's activity heartbeat: stamp `lastSeenAt` on the player's OWN profile.
   * Never throws, and best-effort by contract — it is the one activity signal
   * the frozen rules let a player write, and under those same rules no other
   * client may read players/{uid} (owner-read-only, `list: if false`), so it
   * carries no gameplay consequence today. It keeps the profile's own
   * activity field truthful while a player is in a match; it can never be
   * turned into a presence read, and nothing here promises one.
   */
  suspend fun markActive(uid: String)
}

/**
 * The authoritative implementation over Firestore. Writes only the fields the
 * match lifecycle and the heartbeat own (currentMatchId + lastSeenAt); a read
 * returns null for anything other than a real, round-tripped id.
 */
class PlayerService(
  /**
   * players/{uid} — the one document the rules let a player partially write.
   * A function of uid rather than a bare
   * [com.google.firebase.firestore.FirebaseFirestore] because a JVM test
   * cannot build the SDK object without an initialized Firebase and this repo
   * ships no SDK fake by design; the write below is the hermetic boundary.
   */
  private val profileRef: (uid: String) -> DocumentReference,
  /**
   * The write itself: a MERGE over the profile doc, never a bare `set()`.
   * Overridable for the same reason [profileRef] is injectable, and because
   * "the write cannot have clobbered anything" is exactly the heartbeat's
   * contract — a test can only assert that against the payload it receives
   * here.
   */
  private val mergeOwnProfile: suspend (uid: String, fields: Map<String, Any?>) -> Unit = { uid, fields ->
    profileRef(uid).set(fields, SetOptions.merge()).await()
  },
) : PlayerPort {

  override suspend fun currentMatchId(uid: String): String? {
    return try {
      val snap = profileRef(uid).getBlocking()
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
    runCatching {
      mergeOwnProfile(
        uid,
        mapOf(
          FIELD_CURRENT_MATCH_ID to matchId,
          FIELD_LAST_SEEN_AT to FieldValue.serverTimestamp(),
        ),
      )
    }
    // Best-effort by contract: the match write already succeeded.
  }

  override suspend fun markActive(uid: String) {
    if (uid.isEmpty()) return
    runCatching {
      // lastSeenAt and NOTHING ELSE — never displayName, avatarInitial,
      // currentRoomId or currentMatchId, so a stale local value can never
      // overwrite the profile's real fields. The merge (on top of the single
      // field) is what keeps the write from erasing the doc.
      mergeOwnProfile(uid, mapOf(FIELD_LAST_SEEN_AT to FieldValue.serverTimestamp()))
    }
    // Best-effort by contract: a dropped tick never fails the match.
  }

  private companion object {
    const val FIELD_CURRENT_MATCH_ID = "currentMatchId"
    const val FIELD_LAST_SEEN_AT = "lastSeenAt"
  }
}
