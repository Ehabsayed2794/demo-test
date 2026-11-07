package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MATCHMAKING_SEATS
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.UNRANKED_FILL_MILLIS
import com.estemshan.engine.UnrankedCandidate
import com.estemshan.engine.UnrankedTable
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.formUnrankedTables

/**
 * S71 — the UNRANKED half of the resolver: the rank-blind pool's tables, and
 * the hand-off of an open Unranked room's free seats to the pool.
 *
 * ------------------------------------------------------------------------
 * WHY A SEPARATE FILE, AND WHY IT IS STILL ONE QUEUE
 *
 * The queue is ONE collection with a mode on each document (S71's owner
 * ruling: one mode-aware queue, not two). The RANKED branch pairs two
 * searches at a time under RD9's tier band; the UNRANKED branch has no tier
 * at all and forms tables of up to four under a fill window. Two different
 * rules, one pass — and they never mix: a search is only ever seated with
 * searches of its own mode.
 *
 * ------------------------------------------------------------------------
 * THE RANK-BLINDNESS, AND WHERE IT IS ENFORCED
 *
 * The Unranked documents carry NO tier, so this branch never reads a profile
 * and never consults a rank: the engine's [formUnrankedTables] groups by
 * GameType and arrival order alone, and a King and a Bronze share a table
 * because neither candidate carries a rank to compare. A rank-blind pool that
 * READ a rank would be a bug waiting for an input.
 *
 * GM5 still rides along: a Full searcher is never seated at a Mini table.
 * The GameType is the only thing that narrows the Unranked pool, exactly as
 * it narrows the Ranked one.
 *
 * ------------------------------------------------------------------------
 * TWO KINDS OF UNRANKED TABLE
 *
 * 1. **An open Unranked room** — a table the host already has: a Rank-down
 *    room the host converted ("Play Unranked"), or a room the host created
 *    as Unranked in the first place. Its free seats are offered to the pool,
 *    which is the plan's own line: "the remaining seats are then offered to
 *    the shared Unranked pool".
 * 2. **A pool table** — a table the pool forms from its own searchers, when
 *    four humans share a GameType, or when the group's four-minute fill
 *    window closes (the engine's [formUnrankedTables]).
 *
 * The open rooms are offered FIRST: they carry a running window, while the
 * pool's own tables have no one waiting on them yet.
 *
 * ------------------------------------------------------------------------
 * THE FILL WINDOW, AND WHAT "FILLED" MEANS
 *
 * A table's window is anchored on the moment it entered the pool — the
 * conversion for a converted room, the group's first arrival for a pool
 * table — and it closes four minutes later
 * ([UNRANKED_FILL_MILLIS]). While the window is open, its free seats are
 * offered to human searchers; once it has closed, those seats are the
 * configured bots' ([com.estemshan.engine.UnrankedMatchmakingSession.botSeatsToFill]),
 * so a human arriving after the window is NOT seated there — a late human
 * cannot take a seat the timer already gave to the bot.
 *
 * ------------------------------------------------------------------------
 * THE CLOSE, AND THE MATCH THE SERVER WRITES
 *
 * A closed window is not a table that waits: it is a table that PLAYS. The
 * bots take the free seats and the server writes the match — the only writer
 * that may, because a bot seat is a seat no client may write (see
 * [UnrankedFill]).
 *
 * There are TWO closes, and they are the same rule:
 *
 *  1. **A pool table's own close.** The group was smaller than four when
 *     its window closed, so the bots take the seats after the humans — and
 *     the room and its match are written in ONE transaction.
 *  2. **A converted room's close.** The host's room was still short of
 *     four when its window closed, so the same bots take its free seats and
 *     the server writes that room's match.
 *
 * A table of four HUMANS is never bot-filled: those seats are the humans',
 * and the client path starts that match exactly as it starts a Ranked
 * table's.
 */

/** The room fields the Unranked pool reads and writes. */
internal const val ROOM_FIELD_MODE = "mode"
internal const val ROOM_FIELD_STATUS = "status"
internal const val ROOM_FIELD_PLAYERS = "players"
internal const val ROOM_FIELD_GAME_TYPE = "gameType"

/**
 * When the room entered the Unranked pool — the anchor of its fill window.
 *
 * Written by the conversion (the moment a Rank-down room became Unranked)
 * and by the resolver (the moment a pool table was formed). A room the
 * client created as Unranked has no such field and is anchored on its own
 * `createdAt` instead — the moment it was created IS the moment it entered
 * the pool.
 */
internal const val ROOM_FIELD_POOL_OPENED_AT = "poolOpenedAt"

/** The room's own creation stamp — the fallback anchor. */
private const val ROOM_FIELD_CREATED_AT = "createdAt"

/**
 * The room's own match binding — the id of the match it became.
 *
 * A room that carries one has already started: the fill-close refuses it,
 * and the client path (`MatchService.startMatch`) answers a later start
 * attempt with this same match instead of creating a second one.
 */
internal const val ROOM_FIELD_MATCH_ID = "matchId"

private const val ROOM_STATUS_WAITING = "waiting"

/**
 * The room status a room carries once it has become a match.
 *
 * Shared across the functions package: the fill-close writes it, and the
 * join and conversion paths read it to refuse a room that has already
 * started.
 */
internal const val ROOM_STATUS_IN_GAME = "in_game"

/**
 * How many open Unranked rooms one pass may offer.
 *
 * A room is offered only while it has a free seat, so this bounds the work
 * rather than the queue: a pool with thirty converted rooms and one searcher
 * reads the first twenty and offers that searcher a seat in one of them.
 */
private const val MAX_UNRANKED_ROOMS = 20

/**
 * The Unranked branch of one resolver pass.
 *
 * The open rooms are read ONCE, before the queue is considered at all: a
 * room's fill window closes on its own clock, with nobody searching, so the
 * close must never be gated on the queue.
 *
 * The order is the room's, then the queue's:
 *
 *  1. **The fill-close.** A room whose window has closed takes the configured
 *     bots and becomes a match — the server writes it, because a bot seat is
 *     a seat no client may write. It runs first: a closed room accepts no
 *     more humans (the seats are the bots'), so there is nothing to seat.
 *  2. **The open rooms' free seats**, offered to the waiting searchers.
 *  3. **The pool's own tables**, formed from the searchers nobody seated.
 *
 * A searcher is seated at most once — the [budget] records it, so a
 * searcher who took a converted room's free seat is never also counted into
 * a pool table.
 */
internal suspend fun resolveUnrankedPool(
  db: MatchmakingSearchDb,
  searches: List<QueuedSearch>,
  now: Long,
  budget: PassBudget,
) {
  val rooms = openUnrankedRooms(db)

  for (room in rooms) {
    if (!budget.allowsAnotherAttempt()) break
    // The scanned room decides whether a transaction is worth opening at
    // all: a room whose window is still open, that already has its match, or
    // that is already full costs nothing.
    if (!roomNeedsBotFill(room, now)) continue
    budget.spendAttempt()
    if (closeRoomWithBots(db, room, now) != null) budget.recordTable()
  }

  val waiting = searches.filter { it.uid !in budget.seated }
  if (waiting.isEmpty()) return

  for (room in rooms) {
    if (!budget.allowsAnotherAttempt()) break
    budget.seated += seatIntoRoom(db, room, waiting, now, budget)
  }

  val remaining = waiting.filter { it.uid !in budget.seated }
  if (remaining.isEmpty()) return

  val tables = formUnrankedTables(
    remaining.map { UnrankedCandidate(it.uid, it.gameType, it.enqueuedAtMillis) },
    now,
  )
  for (table in tables) {
    if (!budget.allowsAnotherAttempt()) break
    budget.spendAttempt()
    val tableId = createUnrankedTable(db, table)
    if (tableId == null) continue
    budget.recordTable()
    table.humans.forEach { budget.seated += it.uid }
  }
}

/**
 * Whether this room's free seats are the bots' — the scan-level half of the
 * fill-close.
 *
 * The window CLOSES at `anchor + UNRANKED_FILL_MILLIS` (the engine's own
 * boundary: `nowMillis < fillDeadlineMillis` is still filling, and the
 * deadline instant has closed), so the close is `now >= anchor + window`.
 *
 * A room with four humans is NOT one: those seats are the humans', and the
 * client path starts that match. A room with no humans is not one either —
 * there is nobody to play with, and the bots alone are not a table.
 */
private fun roomNeedsBotFill(room: RoomDocument, now: Long): Boolean {
  if (room.fields[ROOM_FIELD_MATCH_ID] != null) return false
  val anchor = poolAnchorOf(room.fields) ?: return false
  if (now < anchor + UNRANKED_FILL_MILLIS) return false
  val humans = playersOf(room.fields)
  return humans.isNotEmpty() && humans.size < MATCHMAKING_SEATS
}

/**
 * Closes one Unranked room: the configured bots take its free seats and the
 * server writes the match it becomes.
 *
 * Every decision is re-derived INSIDE the transaction, from the FRESH room:
 * a room that filled up (four humans), that started (it has a match), that
 * was closed, or whose window is somehow still open is not written into.
 * The roster the match is built from is the fresh one, so the bot count is
 * always `4 - the humans still seated`.
 *
 * The match id is minted BEFORE the transaction, so a retry keeps the same
 * id and can never leave two matches behind.
 */
private suspend fun closeRoomWithBots(
  db: MatchmakingSearchDb,
  room: RoomDocument,
  now: Long,
): String? {
  val matchId = db.newMatchId()
  return db.transact { tx ->
    val fresh = tx.readRoom(room.id) ?: return@transact null
    if (fresh[ROOM_FIELD_MODE] != MatchMode.UNRANKED.name) return@transact null
    if ((fresh[ROOM_FIELD_STATUS] as? String ?: ROOM_STATUS_WAITING) != ROOM_STATUS_WAITING) {
      return@transact null
    }
    if (fresh[ROOM_FIELD_MATCH_ID] != null) return@transact null
    val freshAnchor = poolAnchorOf(fresh) ?: return@transact null
    if (now < freshAnchor + UNRANKED_FILL_MILLIS) return@transact null
    val seated = playersOf(fresh)
    if (seated.isEmpty() || seated.size >= MATCHMAKING_SEATS) return@transact null

    tx.createMatch(matchId, unrankedBotFilledMatchFields(
      roomId = room.id,
      humans = seated,
      gameType = gameTypeOf(fresh),
      scoringMode = scoringModeOf(fresh),
      botTier = botTierOf(fresh),
      botPersonality = botPersonalityOf(fresh),
      decisionTimerSeconds = decisionTimerOf(fresh),
    ))
    tx.bindRoomToMatch(room.id, matchId)
    matchId
  }
}

/**
 * The open Unranked rooms — the ones whose free seats the pool may fill.
 *
 * The mode filter belongs to the QUERY (one equality filter, so Firestore's
 * automatic single-field index answers it and no composite index is needed —
 * `firebase.json` declares no indexes artifact at all). The status filter is
 * applied IN MEMORY, because a second equality filter on another field is
 * exactly what would need a composite index.
 *
 * The order is the window's: the room whose window opened first is offered
 * first, and the room id breaks a tie — so the offer is deterministic, and a
 * pass over the same state always offers the same seat to the same searcher.
 */
private suspend fun openUnrankedRooms(db: MatchmakingSearchDb): List<RoomDocument> =
  db.listUnrankedRooms(MAX_UNRANKED_ROOMS)
    .filter { room ->
      room.fields[ROOM_FIELD_MODE] == MatchMode.UNRANKED.name &&
        (room.fields[ROOM_FIELD_STATUS] as? String ?: ROOM_STATUS_WAITING) == ROOM_STATUS_WAITING
    }
    .sortedWith(compareBy<RoomDocument>({ poolAnchorOf(it.fields) ?: Long.MAX_VALUE }, { it.id }))

/**
 * Seats waiting searchers into an open Unranked room's free seats.
 *
 * One transaction, and every decision is re-derived inside it: the room is
 * re-read, so a room that filled up (or started) between the scan and the
 * write is not written into; each candidate's search document is re-read, so
 * a search that was cancelled in the meantime is not seated.
 *
 * The window is checked twice — once from the scanned room and once from the
 * re-read room — because the seat belongs to the bot the moment the window
 * closes, and a late human must not take it.
 *
 * The uids it seated are RETURNED, so the caller marks them as seated: a
 * searcher who took a converted room's free seat must never also be counted
 * into a pool table, and must never be offered a second room's seat.
 */
private suspend fun seatIntoRoom(
  db: MatchmakingSearchDb,
  room: RoomDocument,
  waiting: List<QueuedSearch>,
  now: Long,
  budget: PassBudget,
): List<String> {
  val players = playersOf(room.fields)
  if (players.size >= MATCHMAKING_SEATS) return emptyList()
  // No anchor, no window: the room has neither a pool stamp nor a creation
  // stamp, so nothing says its seats are the bots' — they stay human seats.
  val anchor = poolAnchorOf(room.fields) ?: return emptyList()
  if (now >= anchor + UNRANKED_FILL_MILLIS) return emptyList()

  val gameType = gameTypeOf(room.fields)
  val candidates = waiting
    .filter { it.uid !in budget.seated && it.gameType == gameType }
    .take(MATCHMAKING_SEATS - players.size)
  if (candidates.isEmpty()) return emptyList()

  budget.spendAttempt()
  return db.transact { tx ->
    val fresh = tx.readRoom(room.id) ?: return@transact emptyList()
    if (fresh[ROOM_FIELD_MODE] != MatchMode.UNRANKED.name) return@transact emptyList()
    val freshStatus = fresh[ROOM_FIELD_STATUS] as? String ?: ROOM_STATUS_WAITING
    if (freshStatus != ROOM_STATUS_WAITING) return@transact emptyList()
    val freshAnchor = poolAnchorOf(fresh) ?: return@transact emptyList()
    if (now >= freshAnchor + UNRANKED_FILL_MILLIS) return@transact emptyList()
    val seated = playersOf(fresh)
    if (seated.size >= MATCHMAKING_SEATS) return@transact emptyList()
    val freshGameType = gameTypeOf(fresh)

    val joining = ArrayList<String>()
    for (candidate in candidates) {
      if (joining.size >= MATCHMAKING_SEATS - seated.size) break
      if (candidate.uid in seated || candidate.uid in joining) continue
      val document = tx.readSearch(candidate.uid) ?: continue
      val parsed = parseSearchDocument(candidate.uid, document) ?: continue
      if (parsed.mode != MatchMode.UNRANKED) continue
      if (parsed.gameType != freshGameType) continue
      joining += candidate.uid
    }
    if (joining.isEmpty()) return@transact emptyList()

    tx.seatPlayers(room.id, seated + joining)
    for (uid in joining) tx.resolveSearch(uid, room.id)
    joining
  }
}

/**
 * Forms one pool table and resolves its searchers into it.
 *
 * Every search document is re-read inside the transaction and re-judged: a
 * search that vanished between the scan and the write abandons the WHOLE
 * table, so a table is never created with a searcher who is no longer
 * waiting. The abandoned searchers stay in the queue and are re-formed by
 * the next pass — which is the same rule the Ranked branch follows for a
 * pair that raced.
 *
 * The table id is minted BEFORE the transaction, so a transaction retry keeps
 * the same id and can never leave two tables behind.
 *
 * A table the bots filled (its window closed with fewer than four humans)
 * also becomes its MATCH in the same transaction, and the room is bound to it
 * there and then. The server is the only writer that may do this: a bot seat
 * is a seat no client may write. A table of four humans is NOT bot-filled —
 * the client path starts that match, exactly as it starts a Ranked table's.
 */
private suspend fun createUnrankedTable(
  db: MatchmakingSearchDb,
  table: UnrankedTable,
): String? {
  val tableId = db.newRoomId()
  val humans = table.humans.map { it.uid }
  val gameType = table.humans.first().gameType
  // The window's anchor is the GROUP's first arrival — the table was formed
  // because that window closed, so its seats are already the bots'.
  val anchor = table.humans.first().enqueuedAtMillis
  val botFilled = table.botSeats > 0
  // Minted BEFORE the transaction, like the table id: a retry keeps the same
  // match id and can never leave two matches behind.
  val matchId = if (botFilled) db.newMatchId() else null
  return db.transact { tx ->
    for (human in table.humans) {
      val document = tx.readSearch(human.uid) ?: return@transact null
      val parsed = parseSearchDocument(human.uid, document) ?: return@transact null
      if (parsed.mode != MatchMode.UNRANKED) return@transact null
      if (parsed.gameType != gameType) return@transact null
    }
    if (botFilled) {
      tx.createRoom(tableId, unrankedRoomFields(humans, gameType, anchor, matchId))
      tx.createMatch(matchId!!, unrankedBotFilledMatchFields(
        roomId = tableId,
        humans = humans,
        gameType = gameType,
        scoringMode = ScoringMode.NORMAL,
        botTier = BotTier.MEDIUM,
        botPersonality = BotPersonality.BALANCED,
        decisionTimerSeconds = DEFAULT_DECISION_TIMER_SECONDS,
      ))
    } else {
      tx.createRoom(tableId, unrankedRoomFields(humans, gameType, anchor, null))
    }
    for (uid in humans) tx.resolveSearch(uid, tableId)
    tableId
  }
}

/**
 * The table a pool forms, in `RoomService.createRoom`'s field shape.
 *
 * Same collection, same keys, same defaults — with two differences that are
 * the mode's own: `mode` is UNRANKED, and the room carries the stamp of the
 * moment it entered the pool, because that stamp is its fill window's
 * anchor.
 *
 * A [matchId] names the match the table already became: the room is then
 * `in_game` rather than `waiting`, because the bots filled it and it is
 * playing.
 */
internal fun unrankedRoomFields(
  players: List<String>,
  gameType: GameType,
  poolOpenedAtMillis: Long,
  matchId: String? = null,
): Map<String, Any?> {
  val fields = linkedMapOf<String, Any?>(
    "name" to null,
    "status" to if (matchId == null) ROOM_STATUS_WAITING else ROOM_STATUS_IN_GAME,
    "creator" to players.first(),
    "players" to players,
    "readyPlayers" to emptyList<String>(),
    "gameType" to gameType.name,
    "scoringMode" to ScoringMode.NORMAL.name,
    "mode" to MatchMode.UNRANKED.name,
    "rankDown" to false,
    "botTier" to BotTier.MEDIUM.name,
    "botPersonality" to BotPersonality.BALANCED.name,
    "decisionTimerSeconds" to DEFAULT_DECISION_TIMER_SECONDS,
    ROOM_FIELD_POOL_OPENED_AT to poolOpenedAtMillis,
  )
  if (matchId != null) fields[ROOM_FIELD_MATCH_ID] = matchId
  return fields
}

/** The room's seated players — the humans, in the room's own order. */
private fun playersOf(fields: Map<String, Any?>): List<String> =
  (fields[ROOM_FIELD_PLAYERS] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

/**
 * When the room entered the Unranked pool — its window's anchor.
 *
 * The pool stamp wins over the creation stamp: a room that was created as a
 * Rank-down room an hour ago and converted a minute ago entered the POOL a
 * minute ago, and the bot timer must run from then.
 */
internal fun poolAnchorOf(fields: Map<String, Any?>): Long? =
  timestampToMillis(fields[ROOM_FIELD_POOL_OPENED_AT])
    ?: timestampToMillis(fields[ROOM_FIELD_CREATED_AT])
