package com.estemshan.functions

import com.estemshan.engine.MatchMode

/**
 * The queue's Firestore seam, on a fake — shared by the two files that test
 * the queue's two branches (`MatchmakingSearchTest` for RANKED,
 * `UnrankedPoolTest` for UNRANKED).
 *
 * The writes are BUFFERED and applied only when the transaction completes, so
 * a transaction that throws leaves nothing behind — the same guarantee
 * Firestore gives, and the reason "a corrupt document writes nothing" is a
 * real assertion here and not an artefact of the fake.
 *
 * The event log records every touch in order, so read-before-write is
 * assertable rather than assumed, and [lastTransaction] isolates the
 * transaction under inspection from the ones around it.
 */
internal class FakeSearchDb(
  val searches: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
  val profiles: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
) : MatchmakingSearchDb {

  val events = mutableListOf<String>()
  val rooms = mutableMapOf<String, Map<String, Any?>>()
  val matches = mutableMapOf<String, Map<String, Any?>>()
  private var roomCounter = 0
  private var matchCounter = 0

  /**
   * Uids that VANISH the moment a transaction reads them.
   *
   * This is the race the resolver is written for: the pass saw the document
   * in its scan, and by the time the transaction read it the other player
   * had already been seated elsewhere. The read answers "no document", the
   * pass is told "no table", and it must move on rather than spin.
   */
  val vanishInsideTransaction = mutableSetOf<String>()

  /**
   * Rooms that CHANGE the moment a transaction reads them.
   *
   * The race the fill-close is written for: the scan saw a room whose window
   * had closed, and by the time the transaction read it the room had started
   * (or had taken its match). The change applies ONCE and is permanent, so
   * the room the transaction sees is the room the world now holds.
   */
  val roomChangesOnRead = mutableMapOf<String, (Map<String, Any?>) -> Map<String, Any?>>()

  /** The events of the most recent transaction, and nothing after it. */
  var lastTransaction: List<String> = emptyList()
    private set

  /** How many transactions the pass opened. */
  val transactionCount: Int get() = events.count { it == "tx" }

  val writes: Int get() = events.count {
    it.startsWith("write") || it.startsWith("resolve") ||
      it.startsWith("delete") || it.startsWith("seat") ||
      it.startsWith("bind")
  }

  /**
   * An open Unranked room, exactly as the conversion leaves one: the mode,
   * the seated humans, the host's own bot configuration, and the stamp of the
   * moment it entered the pool (its fill window's anchor).
   */
  fun openUnrankedRoom(
    roomId: String,
    players: List<String>,
    poolOpenedAt: Long,
    gameType: String = "FULL",
    status: String = "waiting",
    botTier: String = "MEDIUM",
    botPersonality: String = "BALANCED",
    decisionTimerSeconds: Int = 15,
  ) {
    rooms[roomId] = linkedMapOf(
      "status" to status,
      "creator" to players.firstOrNull(),
      "players" to players,
      "readyPlayers" to emptyList<String>(),
      "gameType" to gameType,
      "scoringMode" to "NORMAL",
      "mode" to MatchMode.UNRANKED.name,
      "rankDown" to false,
      "botTier" to botTier,
      "botPersonality" to botPersonality,
      "decisionTimerSeconds" to decisionTimerSeconds,
      ROOM_FIELD_POOL_OPENED_AT to poolOpenedAt,
    )
  }

  override suspend fun readSearch(uid: String): Map<String, Any?>? {
    events += "read:$uid"
    return searches[uid]
  }

  override suspend fun listWaitingSearches(limit: Int): List<SearchDocument> {
    events += "list"
    return searches.entries
      .filter { it.value["status"] == "WAITING" }
      .take(limit)
      .map { SearchDocument(it.key, it.value) }
  }

  override suspend fun listUnrankedRooms(limit: Int): List<RoomDocument> {
    events += "list-rooms"
    // Deliberately WIDER than the Admin query, which narrows the rooms by
    // mode in the query itself: the resolver narrows them again in memory,
    // and a fake that pre-filtered would hide that rule from the test.
    return rooms.entries
      .take(limit)
      .map { RoomDocument(it.key, it.value) }
  }

  override fun newRoomId(): String {
    roomCounter += 1
    return "room-$roomCounter"
  }

  override fun newMatchId(): String {
    matchCounter += 1
    return "match-$matchCounter"
  }

  override suspend fun <T> transact(block: suspend (MatchmakingSearchDb.Tx) -> T): T {
    val start = events.size
    events += "tx"
    val pending = mutableListOf<() -> Unit>()
    val outcome = block(object : MatchmakingSearchDb.Tx {
      override suspend fun readSearch(uid: String): Map<String, Any?>? {
        events += "read:$uid"
        if (vanishInsideTransaction.remove(uid)) {
          searches.remove(uid)
          return null
        }
        return searches[uid]
      }

      override suspend fun readProfile(uid: String): Map<String, Any?>? {
        events += "profile:$uid"
        return profiles[uid]
      }

      override suspend fun readRoom(roomId: String): Map<String, Any?>? {
        events += "room:$roomId"
        val current = rooms[roomId] ?: return null
        val change = roomChangesOnRead.remove(roomId) ?: return current
        val changed = change(current)
        rooms[roomId] = changed
        return changed
      }

      override fun putSearch(uid: String, fields: Map<String, Any?>) {
        events += "write-search:$uid"
        pending += { searches[uid] = fields }
      }

      override fun renewSearch(uid: String, heartbeatMillis: Long) {
        events += "renew:$uid"
        pending += {
          searches[uid] = (searches[uid] ?: emptyMap()) + ("heartbeatAt" to heartbeatMillis)
        }
      }

      override fun resolveSearch(uid: String, tableId: String) {
        events += "resolve:$uid"
        pending += {
          searches[uid] = (searches[uid] ?: emptyMap()) +
            ("status" to "RESOLVED") + ("tableId" to tableId)
        }
      }

      override fun deleteSearch(uid: String) {
        events += "delete:$uid"
        pending += { searches.remove(uid) }
      }

      override fun createRoom(roomId: String, fields: Map<String, Any?>) {
        events += "write-room:$roomId"
        pending += { rooms[roomId] = fields }
      }

      override fun seatPlayers(roomId: String, players: List<String>) {
        events += "seat:$roomId"
        pending += {
          val current = rooms[roomId] ?: emptyMap()
          rooms[roomId] = current + ("players" to players)
        }
      }

      override fun createMatch(matchId: String, fields: Map<String, Any?>) {
        events += "write-match:$matchId"
        pending += { matches[matchId] = fields }
      }

      override fun bindRoomToMatch(roomId: String, matchId: String) {
        events += "bind:$roomId"
        pending += {
          val current = rooms[roomId] ?: emptyMap()
          rooms[roomId] = current +
            ("status" to ROOM_STATUS_IN_GAME) +
            (ROOM_FIELD_MATCH_ID to matchId)
        }
      }
    })
    lastTransaction = events.subList(start, events.size).toList()
    for (apply in pending) apply()
    return outcome
  }
}

/** A mutable clock, so a lease can be aged without waiting for it. */
internal class FakeSearchClock(var now: Long = 1_000L) : DecisionClock {
  override fun nowMillis(): Long = now
}

/**
 * A callable request for [uid], carrying [fields] — the plain JS object the
 * Functions runtime actually delivers.
 */
internal fun fakeRequest(
  uid: String,
  fields: Map<String, Any?> = linkedMapOf("gameType" to "FULL", "mode" to "RANKED"),
): CallableRequest {
  val data: dynamic = js("({})")
  for ((key, value) in fields) data[key] = value
  return object : CallableRequest {
    override val data: Any? = data
    override val auth: AuthData? = object : AuthData {
      override val uid: String = uid
      override val token: AuthToken? = null
    }
    override val instanceIdToken: String? = null
    override val rawRequest: Any? = null
    override val app: Any? = null
  }
}
