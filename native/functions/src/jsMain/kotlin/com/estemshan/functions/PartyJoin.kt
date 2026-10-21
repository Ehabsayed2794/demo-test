package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.MatchMode
import com.estemshan.engine.PlacementState
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.progressionMismatch
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder

/**
 * S70 (RD29/RD30) — `joinRankDownRoom`: the authoritative Rank-down party gate.
 *
 * The gate is a SERVER decision because the two facts it compares are not the
 * caller's to report: the host's rank belongs to another player's document, and
 * RD29 forbids a client from reading it at all. So this callable reads both
 * profiles with the Admin SDK, hands them to the frozen pure core in
 * [decidePartyJoin], and returns only allow/deny plus the stable reason and a
 * rank-free sentence. Nothing that leaves here names a tier, a division, an RP
 * value, or a player other than the caller.
 *
 * ------------------------------------------------------------------------
 * RESIDUAL RISK — THIS IS NOT A RULES-LEVEL ENFORCEMENT BOUNDARY.
 *
 * The frozen `firestore.rules` room-update whitelist allows ANY authenticated
 * client to write `rooms/{id}.players` directly, and `isSelfOnlyChange` lets a
 * caller add only itself — so a modified client can append itself to a Rank-down
 * room without ever calling this callable. S70 delivers the authoritative server
 * decision and the honest client path, NOT a rules-level enforcement boundary.
 * The bypass is real, is not closed here, and closing it needs a rules change
 * that this story is explicitly forbidden from making.
 * ------------------------------------------------------------------------
 *
 * The order inside the transaction is the contract, not a style choice:
 * Firestore forbids a read after a write in one transaction, so both profiles
 * must be read before the append; and the mode gate must run before the
 * idempotent membership return, or a party that squeezed in before the flag was
 * cleared would be answered `joined: true` and the gate would be a no-op for
 * exactly the rooms it exists to police.
 */

/** The room's capacity — the same 4 the client's RoomDoc enforces. */
private const val MAX_ROOM_PLAYERS = 4

/** `RoomDoc.ROOM_CODE_LENGTH`, duplicated: see [roomIdOf] for why. */
private const val ROOM_CODE_LENGTH = 6

private const val KEY_ROOM_ID = "roomId"
private const val KEY_PLAYERS = "players"
private const val KEY_STATUS = "status"
private const val KEY_CREATOR = "creator"
private const val KEY_MODE = "mode"
private const val KEY_RANK_DOWN = "rankDown"

private const val ROOM_STATUS_CLOSED = "closed"
private const val ROOM_STATUS_IN_GAME = "in_game"

/** The room's public shape. Every other field on the document stays server-side. */
private val ROOM_PROJECTION = listOf(
  KEY_ROOM_ID, "name", KEY_STATUS, KEY_CREATOR, KEY_PLAYERS, "readyPlayers",
  KEY_MODE, KEY_RANK_DOWN, "gameType", "scoringMode", "matchId",
)

/**
 * Keys a client is never allowed to send. Each one is either an identity the
 * server derives (uid, playerId) or a fact the gate reads from the other
 * player's document (tier, division, rank, rp, seasonId) or room configuration
 * this callable must not accept (mode, rankDown) — so a value here is not
 * ignored, it is refused: silently dropping `tier: "King"` would make a spoof
 * look like a supported feature.
 */
private val FORBIDDEN_KEYS = listOf(
  "uid", "playerId", "tier", "division", "rank", "rp", "seasonId", "mode", "rankDown",
)

/** The stable `details.reason` tokens the client switches on. */
private object Reason {
  const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
  const val FORBIDDEN_KEY = "FORBIDDEN_KEY"
  const val ROOM_NOT_FOUND = "ROOM_NOT_FOUND"
  const val ROOM_CLOSED = "ROOM_CLOSED"
  const val ROOM_FULL = "ROOM_FULL"
  const val NOT_RANK_DOWN = "NOT_RANK_DOWN"
}

/**
 * Gate one join attempt to a Rank-down room.
 *
 * Returns the `{ joined, room }` / `{ joined, reason, message }` map the client
 * parses, and throws [HttpsError] for everything that is not an answer to the
 * question — each with `details.reason`, so the client maps a stable token
 * instead of re-deriving one from a message string.
 */
suspend fun joinOnce(db: PartyJoinDb, request: CallableRequest): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)
  val roomId = roomIdOf(requestFields(request.data))

  return db.transact { tx ->
    val room = tx.readRoom(roomId)
      ?: throw refusal(
        "not-found",
        Reason.ROOM_NOT_FOUND,
        "joinRankDownRoom: no room with this code exists.",
      )

    val status = room[KEY_STATUS] as? String
    if (status == ROOM_STATUS_CLOSED || status == ROOM_STATUS_IN_GAME) {
      throw refusal(
        "failed-precondition",
        Reason.ROOM_CLOSED,
        "This room is no longer accepting players.",
      )
    }

    // Rank-down = mode RANKED + the room's private-access flag (RD28), read off
    // the committed document. An absent mode or flag means ROOM/false — the
    // same defaults the client's own room parser applies — so a legacy room is
    // simply not a Rank-down room rather than an error.
    val mode = Settlement.parseMatchMode(room[KEY_MODE])
    val rankDown = room[KEY_RANK_DOWN] == true
    if (mode != MatchMode.RANKED || !rankDown) {
      throw refusal(
        "failed-precondition",
        Reason.NOT_RANK_DOWN,
        "This room is not a Rank-down game.",
      )
    }

    val players = stringList(room[KEY_PLAYERS])
    // Already seated: the join already happened, so say so and write nothing.
    // Deliberately reached only AFTER the mode gate above.
    if (uid in players) return@transact joined(roomId, room, players)

    if (players.size >= MAX_ROOM_PLAYERS) {
      throw refusal("resource-exhausted", Reason.ROOM_FULL, "This room is full.")
    }

    // Both profiles, read here and never handed to the caller: RD29's "checked
    // server-side only" is exactly this pair of reads.
    val creatorUid = room[KEY_CREATOR] as? String
    val creatorProfile = if (creatorUid == null) null else parseProfile(tx.readProfile(creatorUid))
    val joinerProfile = parseProfile(tx.readProfile(uid))

    return@transact when (val decision = decidePartyJoin(mode, rankDown, creatorProfile, joinerProfile)) {
      PartyGateDecision.Allowed -> {
        // Guarded against the same committed read the decision used: a caller
        // that arrived twice must never be appended twice, and a rank-incompatible
        // pair can only have got in through the rules bypass, not through here.
        val updated = if (uid in players) players else players + uid
        tx.writePlayers(roomId, updated)
        joined(roomId, room, updated)
      }

      is PartyGateDecision.Denied -> denied(decision.reason, decision.message)

      // Unreachable: the mode gate above is the same predicate the core checks.
      // Kept as a refusal rather than a crash, because "not a Rank-down room" is
      // the honest thing to say if that ever stops being true.
      PartyGateDecision.NotApplicable -> throw refusal(
        "failed-precondition",
        Reason.NOT_RANK_DOWN,
        "This room is not a Rank-down game.",
      )
    }
  }
}

// ── the request ────────────────────────────────────────────────────────

/**
 * `request.data` as a Kotlin map.
 *
 * The runtime delivers a PLAIN JS OBJECT, never a Kotlin map, so a raw cast
 * fails on every real call and the callable rejects calls that were perfectly
 * well formed — the S61 defect class, pinned in [CallablePayloadBridgingTest].
 * [dynamicToKotlin] is this module's one boundary converter and every callable
 * here parses its request through it.
 *
 * An absent payload is an empty field set, not an error: the missing `roomId`
 * below is what reports it, so the client gets one stable reason, not two
 * competing ones.
 */
@Suppress("UNCHECKED_CAST")
private fun requestFields(data: Any?): Map<String, Any?> = when {
  data == null -> emptyMap()
  else -> dynamicToKotlin(data) as? Map<String, Any?> ?: throw refusal(
    "invalid-argument",
    Reason.INVALID_ARGUMENT,
    "joinRankDownRoom: the request must be an object carrying a 'roomId'.",
  )
}

/**
 * The room code, validated and normalized.
 *
 * Normalization is the client's `normalizeRoomCode` policy, duplicated here on
 * purpose: `:functions` cannot depend on `:services`, so there is no shared
 * symbol to call. A 6-character invite code is case-insensitive, while a longer
 * id is a Firestore auto-ID and is case-SENSITIVE — uppercasing one of those
 * would read a document that does not exist. [PartyJoinTest] pins both branches
 * and the emulator suite proves the path against a real room, so drift between
 * the two copies is caught rather than trusted.
 *
 * Refusals happen before any read: a malformed code, a forbidden key and an
 * unknown key must not cost a Firestore transaction.
 */
private fun roomIdOf(fields: Map<String, Any?>): String {
  for (key in FORBIDDEN_KEYS) {
    if (fields.containsKey(key)) throw forbiddenKey(key)
  }
  for (key in fields.keys) {
    if (key != KEY_ROOM_ID) throw unknownKey(key)
  }
  val trimmed = (fields[KEY_ROOM_ID] as? String)?.trim()
  if (trimmed.isNullOrEmpty()) {
    throw refusal(
      "invalid-argument",
      Reason.INVALID_ARGUMENT,
      "joinRankDownRoom: 'roomId' must be the room's code.",
    )
  }
  return if (trimmed.length <= ROOM_CODE_LENGTH) trimmed.uppercase() else trimmed
}

private fun forbiddenKey(key: String): HttpsError = refusal(
  "permission-denied",
  Reason.FORBIDDEN_KEY,
  "identity, tier and room configuration are server-derived; '$key' is not accepted.",
)

/** Names the key, never its value — an echo turns a denial into an oracle. */
private fun unknownKey(key: String): HttpsError = refusal(
  "invalid-argument",
  Reason.INVALID_ARGUMENT,
  "joinRankDownRoom: unknown field '$key'.",
)

private fun refusal(code: String, reason: String, message: String): HttpsError = HttpsError(
  code,
  message,
  // toPlain, not the Kotlin map: JSON.stringify of a Kotlin map yields `{}`, so
  // an unconverted `details` would reach the client as an empty object and the
  // stable reason would be lost precisely where the client needs it.
  toPlain(linkedMapOf<String, Any?>("reason" to reason)),
)

// ── the response ───────────────────────────────────────────────────────

/**
 * The permitted projection: the room's public shape and nothing else.
 *
 * Built key by key rather than by removing fields, because the room document
 * carries a private half (botTier, botPersonality, decisionTimerSeconds, a
 * password) and a projection that survives a new field must name what may
 * leave, not what may not. No rank, RP, season, placement, shield, email, coin
 * or token is reachable from this list, and [PartyJoinTest] searches the whole
 * flattened payload for every one of those words.
 */
private fun joined(
  roomId: String,
  room: Map<String, Any?>,
  players: List<String>,
): Map<String, Any?> {
  val projected = LinkedHashMap<String, Any?>()
  for (key in ROOM_PROJECTION) projected[key] = room[key]
  // The id the read used, always: an auto-ID room carries its id nowhere in its
  // own fields, and the client keys its room state on it.
  projected[KEY_ROOM_ID] = roomId
  projected[KEY_PLAYERS] = players
  return linkedMapOf("joined" to true, "room" to projected)
}

private fun denied(reason: String, message: String): Map<String, Any?> = linkedMapOf(
  "joined" to false,
  "reason" to reason,
  "message" to message,
)

// ── the RD30 profile read ──────────────────────────────────────────────

/**
 * `players/{uid}` as the engine's [RankedProfile], or null when it cannot be one.
 *
 * Deliberately not [normalizeProfile] in RankedProfileDb.kt: that one throws a
 * `failed-precondition` naming the broken field, because a read callable owes
 * its own caller a diagnosis. This one owes the room a decision, and the honest
 * answer to a corrupt profile is the same generic `PROFILE_UNAVAILABLE` denial
 * as the answer to a missing one — neither a list of field names nor which of
 * the two players failed may leave the gate. It is a local copy because
 * RankedProfileDb.kt is outside this story's edit scope; the two parsers are
 * held in step by [PartyJoinTest]'s mutation matrix and S50's own suite.
 *
 * Every absent field takes the RD30 default; every present field must be valid.
 * [runCatching] is the fail-closed envelope: a value that surprises the engine
 * (a pair naming no rung, a disagreement the writer never checked) denies the
 * join rather than admitting a player on an unproven identity.
 */
private fun parseProfile(stored: Map<String, Any?>?): RankedProfile? {
  if (stored == null) return null
  return runCatching { profileOf(stored) }.getOrNull()
}

private fun profileOf(stored: Map<String, Any?>): RankedProfile {
  val hasRp = stored.containsKey(FIELD_RP)
  val rp = if (hasRp) wholeNonNegative(stored.getValue(FIELD_RP), FIELD_RP) else 0

  val hasTier = stored.containsKey(FIELD_TIER)
  val hasDivision = stored.containsKey(FIELD_DIVISION)
  val current = when {
    hasTier && hasDivision -> rung(
      stored.getValue(FIELD_TIER) as? String ?: corrupt("$FIELD_TIER must be a string."),
      stored.getValue(FIELD_DIVISION) as? String ?: corrupt("$FIELD_DIVISION must be a string."),
    )
    !hasTier && !hasDivision -> rankAt(rp)
    else -> corrupt("$FIELD_TIER and $FIELD_DIVISION must be present together.")
  }

  // RD30 leaves an unplaced account with no rp AND no tier/division — the pair
  // below is then the bottom rung, reached by rankAt(0). An absent rp beside any
  // higher rung is a document this writer never produced.
  //
  // [progressionMismatch] below rejects the same document, so this guard is the
  // stated RD30 rule kept as written and the second of two checks — deleting it
  // changes no observable outcome, which is why no test here can prove it.
  if (!hasRp && rankLadder.indexOf(current) > 0) {
    corrupt("$FIELD_RP is missing while tier/division name a rung above Bronze III.")
  }

  val seasonId = if (stored.containsKey(FIELD_SEASON_ID)) {
    (stored.getValue(FIELD_SEASON_ID) as? String)?.takeIf { it.isNotBlank() }
      ?: corrupt("$FIELD_SEASON_ID must be a non-blank string.")
  } else {
    DEFAULT_SEASON_ID
  }

  val hasHighestTier = stored.containsKey(FIELD_HIGHEST_RANK_TIER)
  val hasHighestDivision = stored.containsKey(FIELD_HIGHEST_RANK_DIVISION)
  val storedHighest = when {
    hasHighestTier && hasHighestDivision -> rung(
      stored.getValue(FIELD_HIGHEST_RANK_TIER) as? String
        ?: corrupt("$FIELD_HIGHEST_RANK_TIER must be a string."),
      stored.getValue(FIELD_HIGHEST_RANK_DIVISION) as? String
        ?: corrupt("$FIELD_HIGHEST_RANK_DIVISION must be a string."),
    )
    !hasHighestTier && !hasHighestDivision -> null
    else -> corrupt("$FIELD_HIGHEST_RANK_TIER and $FIELD_HIGHEST_RANK_DIVISION must be present together.")
  }
  // A seasonal demotion lowers `tier` but never erases the highest rung ever
  // held (RD24), so a stored highest below the current rung is the model's one
  // permitted repair — and it only ever raises.
  val highest = when {
    storedHighest == null -> current
    rankLadder.indexOf(storedHighest) < rankLadder.indexOf(current) -> current
    else -> storedHighest
  }

  val placementState = if (stored.containsKey(FIELD_PLACEMENT_STATE)) {
    PlacementState.values().firstOrNull {
      it.name == (stored.getValue(FIELD_PLACEMENT_STATE) as? String)
    } ?: corrupt("$FIELD_PLACEMENT_STATE is not a PlacementState name.")
  } else if (rp > 0) {
    PlacementState.SKIPPED
  } else {
    PlacementState.NOT_STARTED
  }

  val demotionShield = if (stored.containsKey(FIELD_DEMOTION_SHIELD)) {
    stored.getValue(FIELD_DEMOTION_SHIELD) as? Boolean
      ?: corrupt("$FIELD_DEMOTION_SHIELD must be Boolean.")
  } else {
    false
  }

  val profile = RankedProfile(
    tier = current.tier,
    division = current.division,
    rp = rp,
    seasonId = seasonId,
    highestRank = highest,
    placementState = placementState,
    demotionShield = demotionShield,
  )
  // RD3: a stored pair and a stored rp must name the SAME rung, and the engine
  // owns that rule. Checked here rather than left to
  // [arePartyProfilesEligible] so a document that disagrees with itself is not
  // compared at all.
  progressionMismatch(profile)?.let { corrupt(it) }
  return profile
}

/**
 * The exact ladder rung a stored pair names, resolved locally rather than
 * through the engine's helper: `ladderRankOf` REQUIRES a matching pair and
 * throws on one that names nothing, and here a pair that names nothing is a
 * denial to be reported, not an exception to be survived.
 */
private fun rung(tier: String, division: String): LadderRank =
  rankLadder.firstOrNull { it.tier == tier && it.division == division }
    ?: corrupt("tier/division '$tier $division' name no ladder rung.")

private fun stringList(value: Any?): List<String> =
  (value as? List<*>).orEmpty().mapNotNull { it as? String }

/**
 * A whole, finite, non-negative number — the same shape RankedProfileDb demands
 * of `rp`. A fraction or a negative is a corrupt document, not a rounding
 * question.
 */
private fun wholeNonNegative(value: Any?, key: String): Int {
  val number = value as? Number ?: corrupt("$key must be a non-negative number.")
  val asDouble = number.toDouble()
  if (!asDouble.isFinite() || asDouble < 0.0 || asDouble % 1.0 != 0.0 ||
    asDouble > Int.MAX_VALUE.toDouble()
  ) {
    corrupt("$key must be a non-negative whole number.")
  }
  return asDouble.toInt()
}

/** The profile field names, spelled as they are on `players/{uid}`. */
private const val FIELD_RP = "rp"
private const val FIELD_TIER = "tier"
private const val FIELD_DIVISION = "division"
private const val FIELD_SEASON_ID = "seasonId"
private const val FIELD_HIGHEST_RANK_TIER = "highestRankTier"
private const val FIELD_HIGHEST_RANK_DIVISION = "highestRankDivision"
private const val FIELD_PLACEMENT_STATE = "placementState"
private const val FIELD_DEMOTION_SHIELD = "demotionShield"

/** The first season's id — the model's stable default until S59 creates seasons. */
private const val DEFAULT_SEASON_ID = "s1"

/** Carries a diagnosis for the logs; NEVER reaches the response. */
private class CorruptProfile(message: String) : Throwable(message)

/** Throws [CorruptProfile], which [parseProfile] turns into the generic denial. */
private fun corrupt(message: String): Nothing = throw CorruptProfile(message)
