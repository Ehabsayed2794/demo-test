package com.estemshan.functions

import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier

/**
 * S71 — the bot-filled match the server writes when an Unranked table's fill
 * window closes.
 *
 * ------------------------------------------------------------------------
 * WHY THE SERVER WRITES THE MATCH, AND WHY IT IS THE ONLY WRITER
 *
 * A bot seat is a seat no client may write. `firestore.rules`' own
 * `isValidSeatMap()` demands `seats.keys().size() == players.size()` and
 * every seat's uid to be one of the `players` — at CREATION only, but
 * creation is exactly where a bot seat has to appear. The configured bots
 * can therefore only ever take a seat through a writer the rules do not
 * bind: the Admin SDK. The owner's ruling says the same thing in one line:
 * "Server creates the match."
 *
 * This is the ONLY place the server writes a match. The Ranked queue's
 * tables are still started by the client path (`MatchService.startMatch`),
 * because a pair of humans needs no seat a client may not write.
 *
 * ------------------------------------------------------------------------
 * THE SHAPE: FOUR SEATS, THE HUMANS AS THE ONLY PLAYERS
 *
 * The match carries FOUR seats — `p1`..`p4` — and the humans occupy the
 * first ones, in the order they arrived; the configured bots take the seats
 * after them. `players` carries the HUMANS ONLY, and that is load-bearing
 * rather than cosmetic: the settlement's own rule ("a seat whose occupant
 * is not in `players` is a bot seat") then skips the bot seats by itself,
 * before it ever reads a profile for one.
 *
 * `botSeats` carries the SEAT KEYS (`p4`), never uids — the same shape S72's
 * leave write uses and the same shape `MatchDoc.botSeats` parses.
 *
 * ------------------------------------------------------------------------
 * THE MATCH IS THE CLIENT'S MATCH, FIELD FOR FIELD
 *
 * The field set is `buildInitialMatchFields`'s own — the same fields, with
 * the same values, in the same order — so the frozen match schema, the
 * client's own parser and `MatchService`'s round advance all accept it
 * unchanged. Two fields are not this map's to carry:
 *
 *  - `createdAt` is the Admin writer's own server stamp. Every match the
 *    server writes is stamped by the writer that writes it (exactly as
 *    `MatchmakingSearchDb.createMatch` does for the Ranked queue's tables),
 *    so the DOCUMENT carries the canonical field set whole, and this map
 *    carries the canonical set minus that one stamp;
 *  - `botSeats` names the seats the bots took, so the server's own
 *    decision-timeout authority plays them. It is the ONE field a client
 *    could never write, which is why this file exists.
 *
 * The hands are NOT dealt here. `MatchService.dealRound` deals every
 * occupied seat — the rules let any seated member write any occupied seat's
 * hand — so the bot's thirteen cards are dealt by the ordinary client path,
 * and only the server (the Admin SDK) can read them back.
 */

/**
 * The uid a bot seat carries.
 *
 * A synthetic uid, never a player's: the seat belongs to the CONFIGURED bot
 * and no account owns it. The prefix keeps it apart from any real uid (a
 * Firebase uid is a 28-character opaque string, and this is neither) and
 * names the seat it sits in, so the seat can be read back from the uid.
 */
internal const val UNRANKED_BOT_UID_PREFIX = "bot:unranked:"

/** The match document's bot-seat list — SEAT KEYS (`p4`), never uids. */
internal const val MATCH_FIELD_BOT_SEATS = "botSeats"

/** The match document's status at creation — `MatchDoc.STATUS_STARTING`. */
internal const val MATCH_STATUS_STARTING = "starting"

private const val ROOM_FIELD_SCORING_MODE = "scoringMode"
private const val ROOM_FIELD_BOT_TIER = "botTier"
private const val ROOM_FIELD_BOT_PERSONALITY = "botPersonality"
private const val ROOM_FIELD_DECISION_TIMER = "decisionTimerSeconds"

/**
 * RD21's decision-timer default and bounds, mirrored from
 * `com.estemshan.services.model.MatchModels` — `:functions` depends on
 * `:engine` only, so the values are carried here the way
 * [TimeoutResolution] carries them.
 */
internal const val DEFAULT_DECISION_TIMER_SECONDS = 15
private const val MIN_DECISION_TIMER_SECONDS = 5
private const val MAX_DECISION_TIMER_SECONDS = 20

/** The bot seat's uid for [seatId]. */
internal fun unrankedBotUid(seatId: String): String = "$UNRANKED_BOT_UID_PREFIX$seatId"

/**
 * The seats a bot-filled table occupies: the humans first, in the order they
 * arrived, then the configured bots in the seats after them.
 *
 * The humans keep the FIRST seats, so a table's own arrival order is its
 * seat order — the same positional rule `buildSeatMap` applies to a
 * client-created match. The bots take the LAST seats, so a table's humans
 * never have their seat moved by the fill.
 */
internal data class UnrankedBotFill(
  /** The match's whole seat map — four entries, humans and bots. */
  val seats: Map<String, String>,
  /** The seats the bots took — the seat KEYS, never uids. */
  val botSeatIds: List<String>,
)

/**
 * The seat map of a bot-filled table, and the seats the bots took.
 *
 * The humans fill the first seats in the order they arrived and the bots
 * take the rest; the count of bot seats is therefore `4 - humans`, which is
 * the engine's own count (`UnrankedTable.botSeats`).
 */
internal fun unrankedBotFill(humans: List<String>): UnrankedBotFill {
  val seats = LinkedHashMap<String, String>()
  val botSeatIds = ArrayList<String>()
  DEFAULT_SEATS.forEachIndexed { index, seatId ->
    val human = humans.getOrNull(index)
    if (human != null) {
      seats[seatId] = human
    } else {
      seats[seatId] = unrankedBotUid(seatId)
      botSeatIds += seatId
    }
  }
  return UnrankedBotFill(seats, botSeatIds)
}

/**
 * The match a bot-filled Unranked table becomes.
 *
 * Every field is `buildInitialMatchFields`' own, in the same order and with
 * the same values — so the frozen schema, the client's parser and the
 * round advance accept it unchanged — with two named exceptions:
 *
 *  - `createdAt` is NOT this map's: the Admin writer stamps every match it
 *    writes with the server's own clock, exactly as it does for the Ranked
 *    queue's tables, so the DOCUMENT carries the canonical field set whole
 *    while this map carries the canonical set minus that one stamp;
 *  - `botSeats` names the seats the bots took, so the server's own
 *    decision-timeout authority plays them.
 *
 * `players` are the HUMANS ONLY, so the settlement's bot-seat rule skips
 * the bots without ever reading a profile for one.
 *
 * `dealer` and `turn` are the first human: the table's own arrival order
 * decides who opens, exactly as `initialDealer` does for a client-created
 * match.
 */
internal fun unrankedBotFilledMatchFields(
  roomId: String,
  humans: List<String>,
  gameType: GameType,
  scoringMode: ScoringMode,
  botTier: BotTier,
  botPersonality: BotPersonality,
  decisionTimerSeconds: Int,
): Map<String, Any?> {
  val fill = unrankedBotFill(humans)
  val dealer = humans.firstOrNull()
  return linkedMapOf(
    "roomId" to roomId,
    "players" to humans,
    "status" to MATCH_STATUS_STARTING,
    "currentRound" to 1,
    "maxRounds" to gameType.baseRounds,
    "currentMultiplier" to 1,
    "gameType" to gameType.name,
    "scoringMode" to scoringMode.name,
    "mode" to MatchMode.UNRANKED.name,
    "botTier" to botTier.name,
    "botPersonality" to botPersonality.name,
    "decisionTimerSeconds" to decisionTimerSeconds,
    "extendedRounds" to emptyList<Int>(),
    "dealer" to dealer,
    "turn" to dealer,
    "seats" to fill.seats,
    "version" to 1,
    "biddingOpen" to true,
    "bids" to fill.seats.keys.associateWith { null },
    "lastBidSeat" to null,
    "cardLog" to emptyList<Any?>(),
    "lastCardSeat" to null,
    "cardPhase" to null,
    "biddingLog" to emptyList<Any?>(),
    "gameState" to mapOf("initialized" to false, "dealtRound" to 0),
    MATCH_FIELD_BOT_SEATS to fill.botSeatIds,
  )
}

/**
 * The room's GameType — absent or unrecognised reads as FULL, exactly as the
 * client's own room parser reads it (`RoomDoc.parseGameType`), so the server
 * and the client agree on what an old room is.
 */
internal fun gameTypeOf(fields: Map<String, Any?>): GameType =
  (fields[ROOM_FIELD_GAME_TYPE] as? String)
    ?.let { name -> GameType.values().firstOrNull { it.name == name } }
    ?: GameType.FULL

/**
 * The room's scoring mode — absent or unrecognised reads as NORMAL, exactly
 * as `RoomDoc.parseScoringMode` reads it.
 */
internal fun scoringModeOf(fields: Map<String, Any?>): ScoringMode =
  (fields[ROOM_FIELD_SCORING_MODE] as? String)
    ?.let { name -> ScoringMode.values().firstOrNull { it.name == name } }
    ?: ScoringMode.NORMAL

/**
 * The room's bot tier — absent or unrecognised reads as MEDIUM, exactly as
 * `RoomDoc.parseBotTier` reads it: the host's own bot configuration is what
 * the match inherits.
 */
internal fun botTierOf(fields: Map<String, Any?>): BotTier =
  (fields[ROOM_FIELD_BOT_TIER] as? String)
    ?.let { name -> BotTier.values().firstOrNull { it.name == name } }
    ?: BotTier.MEDIUM

/**
 * The room's bot personality — absent or unrecognised reads as BALANCED,
 * exactly as `RoomDoc.parseBotPersonality` reads it.
 */
internal fun botPersonalityOf(fields: Map<String, Any?>): BotPersonality =
  (fields[ROOM_FIELD_BOT_PERSONALITY] as? String)
    ?.let { name -> BotPersonality.values().firstOrNull { it.name == name } }
    ?: BotPersonality.BALANCED

/**
 * The room's decision timer — absent, non-numeric or outside RD21's 5..20 s
 * window reads as 15 s, exactly as `RoomDoc.parseDecisionTimerSeconds` reads
 * it. A present-but-out-of-range value is treated as missing rather than
 * clamped: the rules deny writing it, so a read can never surface a timer
 * the host never picked.
 */
internal fun decisionTimerOf(fields: Map<String, Any?>): Int {
  val seconds = (fields[ROOM_FIELD_DECISION_TIMER] as? Number)?.toInt()
    ?: return DEFAULT_DECISION_TIMER_SECONDS
  return if (seconds in MIN_DECISION_TIMER_SECONDS..MAX_DECISION_TIMER_SECONDS) seconds
  else DEFAULT_DECISION_TIMER_SECONDS
}
