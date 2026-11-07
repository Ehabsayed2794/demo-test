package com.estemshan.functions

import com.estemshan.engine.MatchMode

/**
 * S71 — the host-only "Play Unranked" conversion.
 *
 * **What it is.** One callable that turns a room the host created into an
 * Unranked room: `mode` → `UNRANKED`, the access credential cleared, the
 * seated players left exactly where they were. It is the button the host
 * presses when a friend was refused entry to their Ranked table (RD29) — the
 * plan's own flow:
 *
 * ```
 * friend refused ──► host presses [Play Unranked] ──► conversion ──► Unranked
 * ```
 *
 * **Why it is a callable and not a client write.** `firestore.rules` is
 * frozen, and `isValidRoomUpdate`'s affected-keys allowlist is
 * `['players', 'readyPlayers', 'status', 'creator', 'updatedAt', 'matchId']` —
 * `mode` is not on it, so a client cannot write a room's mode, and cannot
 * clear the credential either. The Admin SDK bypasses the rules, so this
 * callable is the only path that can perform the transition. **No rules
 * change is made and no rules SHA is re-pinned.**
 *
 * **Why the host, and why the server.** RD21/S36 make the mode the host's
 * configuration; the refused friend is *informed*, never the actor. The host
 * is derived from the VERIFIED callable identity, so a request can name no
 * host at all — the only thing the body may carry is the room's code.
 *
 * **The credential.** A Rank-down room's access credential IS its
 * private-access flag: `PartyGate`'s own wording calls `rankDown` "the
 * room's private/password access flag", and Rank-down is `RANKED` + that flag
 * (RD28) — never a fourth mode. So clearing the access credential is
 * `rankDown` → false, and it is a *required* part of the conversion rather
 * than a nicety: an Unranked room that still carried `rankDown: true` would
 * be `ROOM` + a marker, which is exactly the collapse S43 and R32 forbid.
 *
 * A separate password field cannot exist on a room today: `isValidNewRoom`'s
 * `hasOnly` allowlist has no such key, so no client can create a room that
 * carries one, and the rules are frozen. The flag is therefore the whole
 * credential today. When the S36 UI story adds a password field, this
 * conversion must clear it in the same write.
 *
 * **Pre-match only.** The room must still be `waiting` and must not carry a
 * `matchId`. A conversion mid-match would change the very authority the
 * roster, the voice gate and the Vote Kick gate are reading *while they are
 * running*.
 *
 * **Idempotent by construction.** The target state is a fixed point —
 * `mode: UNRANKED` with no credential — so a second call finds the room
 * already there and writes nothing. Idempotency is a property of the state,
 * not a special case bolted onto the code path.
 */
object PlayUnranked {

  /**
   * The stable `details.reason` tokens this callable refuses with.
   *
   * The client's two malformed-request refusals reuse [RequestReason]'s
   * vocabulary, so the whole authority layer speaks one set of tokens; the
   * four room-gate tokens are this callable's own.
   */
  object Reason {
    /** No room with that code exists — or it has no creator, which the
     *  client's own parser also reads as "no room". */
    const val ROOM_NOT_FOUND = "ROOM_NOT_FOUND"

    /** The caller is not the room's creator. */
    const val NOT_HOST = "NOT_HOST"

    /** The room is closed. */
    const val ROOM_CLOSED = "ROOM_CLOSED"

    /** The room is in a match (or already carries one). */
    const val ALREADY_STARTED = "ALREADY_STARTED"

    /** The room's status is neither `waiting`, `closed` nor `in_game`. */
    const val NOT_PRE_MATCH = "NOT_PRE_MATCH"
  }
}

/** The outcome of one conversion decision over a committed room document. */
sealed class UnrankedConversion {

  /** The room is already an Unranked room with no credential: nothing is written. */
  data object AlreadyUnranked : UnrankedConversion()

  /**
   * The room must be written: `mode` → `UNRANKED`, credential cleared.
   *
   * [alreadyUnranked] records whether the room's MODE was already `UNRANKED`
   * when the call arrived — the only case where a write happens without a
   * mode transition, because a credential was still present.
   */
  data class Convert(val alreadyUnranked: Boolean) : UnrankedConversion()

  /**
   * Refused. [code] is the HttpsError code, [reason] the stable token the
   * client switches on, [message] the plain-language sentence.
   */
  data class Refused(val code: String, val reason: String, val message: String) :
    UnrankedConversion()
}

private const val KEY_ROOM_ID = "roomId"
private const val KEY_CREATOR = "creator"
private const val KEY_STATUS = "status"
private const val KEY_MODE = "mode"
private const val KEY_RANK_DOWN = "rankDown"
private const val KEY_MATCH_ID = "matchId"

private const val ROOM_STATUS_WAITING = "waiting"
private const val ROOM_STATUS_CLOSED = "closed"

/**
 * The conversion's whole write: the two fields it changes, and nothing else.
 *
 * A constant rather than a map a caller fills in, so the seam that performs
 * the transition cannot be used to perform a different one — and a pure
 * function, so the payload is provable without a Firestore fake.
 */
internal fun unrankedWriteFields(): Map<String, Any?> = linkedMapOf(
  KEY_MODE to MatchMode.UNRANKED.name,
  KEY_RANK_DOWN to false,
)

/**
 * `RoomDoc.ROOM_CODE_LENGTH`, duplicated: `:functions` cannot depend on
 * `:services`, so there is no shared symbol to call. A 6-character invite code
 * is case-insensitive, while a longer id is a Firestore auto-ID and is
 * case-SENSITIVE — uppercasing one of those would read a document that does
 * not exist.
 */
private const val ROOM_CODE_LENGTH = 6

/**
 * The server-derived facts a conversion request may never carry.
 *
 * `mode` and `rankDown` are the two fields this callable WRITES, so a client
 * that sends them is trying to set the mode itself — the one thing the whole
 * conversion exists to do on the server. `creator`, `players` and `status` are
 * the room's identity and lifecycle, and `uid`/`playerId` are identities the
 * runtime derives. The tier keys are the standing this conversion does not
 * care about.
 *
 * They are refused, not ignored: a silently-ignored key is a key a client
 * can keep sending while believing it works.
 */
private val FORBIDDEN_KEYS: Set<String> = setOf(
  "uid", "playerId", "tier", "division", "rank", "rp", "seasonId",
  "mode", "rankDown", "creator", "players", "status",
)

/**
 * Decides one conversion over the room document as it is COMMITTED.
 *
 * The order of the gates is the contract:
 *
 * 1. **The room exists.** A room with no `creator` reads as no room at all —
 *    the client's own parser returns null for it, so it has no room to
 *    convert and no host to press the button.
 * 2. **The caller is the host.** Before anything else, so a non-host learns
 *    nothing about the room beyond its existence.
 * 3. **The room is pre-match.** `closed` is a closed room; `in_game` — or a
 *    `matchId` already set — is a started match; anything else that is not
 *    `waiting` is a status this callable does not recognise and will not
 *    convert.
 * 4. **The target state.** `mode: UNRANKED` with no credential. A room
 *    already exactly there is [UnrankedConversion.AlreadyUnranked] and
 *    nothing is written.
 */
fun decideUnrankedConversion(
  room: Map<String, Any?>,
  callerUid: String,
): UnrankedConversion {
  val creator = room[KEY_CREATOR] as? String
    ?: return refuse(
      "not-found",
      PlayUnranked.Reason.ROOM_NOT_FOUND,
      "playUnranked: no room with this code exists.",
    )

  if (creator != callerUid) {
    return refuse(
      "permission-denied",
      PlayUnranked.Reason.NOT_HOST,
      "playUnranked: only the room's host can switch this game to Unranked.",
    )
  }

  // An absent status reads as `waiting`, exactly as the client's own room
  // parser reads it (RoomDoc.fromFields) — so a legacy room is convertible
  // rather than mysteriously refused.
  val status = room[KEY_STATUS] as? String ?: ROOM_STATUS_WAITING
  val matchId = room[KEY_MATCH_ID] as? String
  when {
    status == ROOM_STATUS_CLOSED -> return refuse(
      "failed-precondition",
      PlayUnranked.Reason.ROOM_CLOSED,
      "playUnranked: this room is closed.",
    )

    status == ROOM_STATUS_IN_GAME || (matchId != null && matchId.isNotBlank()) -> return refuse(
      "failed-precondition",
      PlayUnranked.Reason.ALREADY_STARTED,
      "playUnranked: this game has already started, so its mode cannot change.",
    )

    status != ROOM_STATUS_WAITING -> return refuse(
      "failed-precondition",
      PlayUnranked.Reason.NOT_PRE_MATCH,
      "playUnranked: this room is not waiting for players.",
    )
  }

  val mode = Settlement.parseMatchMode(room[KEY_MODE])
  val rankDown = room[KEY_RANK_DOWN] == true
  return if (mode == MatchMode.UNRANKED && !rankDown) {
    UnrankedConversion.AlreadyUnranked
  } else {
    UnrankedConversion.Convert(alreadyUnranked = mode == MatchMode.UNRANKED)
  }
}

/**
 * The conversion, end to end.
 *
 * Everything happens inside one transaction: the host check, the pre-match
 * check and the write must see the same committed room, or a room that
 * started between the read and the write would be converted mid-match.
 */
suspend fun playUnrankedOnce(
  db: PlayUnrankedDb,
  request: CallableRequest,
): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)
  val roomId = roomIdOf(requestFields(request.data))

  return db.transact { tx ->
    val room = tx.readRoom(roomId)
      ?: throw refusal(
        "not-found",
        PlayUnranked.Reason.ROOM_NOT_FOUND,
        "playUnranked: no room with this code exists.",
      )

    when (val decision = decideUnrankedConversion(room, uid)) {
      is UnrankedConversion.Refused ->
        throw refusal(decision.code, decision.reason, decision.message)

      UnrankedConversion.AlreadyUnranked -> linkedMapOf(
        "changed" to false,
        "alreadyUnranked" to true,
        "room" to projectRoom(room, roomId),
      )

      is UnrankedConversion.Convert -> {
        tx.writeUnranked(roomId)
        linkedMapOf(
          "changed" to true,
          "alreadyUnranked" to decision.alreadyUnranked,
          "room" to projectRoom(room + unrankedWriteFields(), roomId),
        )
      }
    }
  }
}

// ── the request ────────────────────────────────────────────────────────

/**
 * `request.data` as a Kotlin map.
 *
 * The runtime delivers a PLAIN JS OBJECT, never a Kotlin map, so a raw cast
 * fails on every real call (the S61 defect class). [dynamicToKotlin] is this
 * module's one boundary converter.
 */
@Suppress("UNCHECKED_CAST")
private fun requestFields(data: Any?): Map<String, Any?> {
  val fields = dynamicToKotlin(data) as? Map<*, *>
    ?: throw refusal(
      "invalid-argument",
      RequestReason.INVALID_ARGUMENT,
      "playUnranked: the request must be an object carrying a 'roomId'.",
    )
  val out = LinkedHashMap<String, Any?>()
  for ((key, value) in fields) {
    out[key as? String ?: throw refusal(
      "invalid-argument",
      RequestReason.INVALID_ARGUMENT,
      "playUnranked: request field names must be strings.",
    )] = value
  }
  return out
}

/**
 * The room code, validated and normalized.
 *
 * The two branches are the client's `normalizeRoomCode` policy, duplicated on
 * purpose for the same reason [roomIdOf]'s length constant is: `:functions`
 * cannot depend on `:services`. Refusals happen before any read — a malformed
 * code, a forbidden key and an unknown key must not cost a Firestore
 * transaction.
 */
private fun roomIdOf(fields: Map<String, Any?>): String {
  for (key in FORBIDDEN_KEYS) {
    if (fields.containsKey(key)) {
      throw refusal(
        "permission-denied",
        RequestReason.FORBIDDEN_KEY,
        "identity, tier and room configuration are server-derived; '$key' is not accepted.",
      )
    }
  }
  for (key in fields.keys) {
    if (key != KEY_ROOM_ID) {
      throw refusal(
        "invalid-argument",
        RequestReason.INVALID_ARGUMENT,
        "playUnranked: unknown field '$key'.",
      )
    }
  }
  val trimmed = (fields[KEY_ROOM_ID] as? String)?.trim()
  if (trimmed.isNullOrEmpty()) {
    throw refusal(
      "invalid-argument",
      RequestReason.INVALID_ARGUMENT,
      "playUnranked: 'roomId' must be the room's code.",
    )
  }
  return if (trimmed.length <= ROOM_CODE_LENGTH) trimmed.uppercase() else trimmed
}

// ── the refusal ────────────────────────────────────────────────────────

private fun refuse(code: String, reason: String, message: String): UnrankedConversion.Refused =
  UnrankedConversion.Refused(code, reason, message)

/**
 * A refusal with a stable reason.
 *
 * `details` is a PLAIN object: `JSON.stringify` of a Kotlin map yields `{}`,
 * so an unconverted map would reach the client as an empty object and the
 * stable reason would be lost exactly where the client needs it.
 */
private fun refusal(code: String, reason: String, message: String): HttpsError = HttpsError(
  code,
  message,
  toPlain(linkedMapOf<String, Any?>("reason" to reason)),
)
