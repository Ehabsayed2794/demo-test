package com.estemshan.functions

import com.estemshan.engine.UNRANKED_FILL_MILLIS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S71 — the FILL-CLOSE: the moment an Unranked table's window shuts, the
 * server writes the match the table becomes.
 *
 * The owner ruled it in one line — "Server creates the match." — and the
 * reason is mechanical, not stylistic: `firestore.rules`' own
 * `isValidSeatMap()` demands `seats.keys().size() == players.size()` and
 * every seat's uid to be one of the `players`, so a bot seat cannot be
 * written by a client at all. The Admin SDK is the only writer the rules do
 * not bind, and the fill-close is the only moment it writes a match.
 *
 * The properties this story has to leave provable:
 *
 *  1. **The close happens.** A window that shuts with fewer than four humans
 *     produces a match — not a room that waits for ever.
 *  2. **The match is the client's match, field for field.** The field set is
 *     `buildInitialMatchFields`' own, in the canonical order, so the client's
 *     parser, the frozen schema and the round advance accept it unchanged.
 *     The two named differences are the Admin writer's own `createdAt` stamp
 *     (which lands on the document, not on the map) and `botSeats`.
 *  3. **The bot seats are seats, and the bots are not players.** `seats`
 *     carries four entries; `players` carries the humans only; `botSeats`
 *     carries the SEAT KEYS. The settlement's own rule — "a seat whose
 *     occupant is not in `players` is a bot seat" — then skips the bots
 *     without reading a profile for one.
 *  4. **A table of four humans is never bot-filled.** Those seats are the
 *     humans', and the client path starts that match.
 *  5. **The close is idempotent.** A room that has a match is never closed
 *     again, so a second pass cannot leave two matches behind.
 *  6. **The match inherits the room's own configuration** — the GameType, the
 *     scoring mode and RD21's bot trio.
 */
class UnrankedFillTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  /**
   * A WAITING Unranked queue document, as the server writes one.
   *
   * [heartbeatAt] defaults to [enqueuedAt] — a search that arrived and is
   * still polling. A search whose heartbeat has aged out is reclaimed before
   * the pool ever sees it, so a fixture that wants the pool to act must keep
   * the lease alive.
   */
  private fun waitingUnranked(
    uid: String,
    enqueuedAt: Long = 1_000L,
    heartbeatAt: Long = enqueuedAt,
  ): Map<String, Any?> = linkedMapOf(
    "uid" to uid,
    "mode" to "UNRANKED",
    "gameType" to "FULL",
    "status" to "WAITING",
    "enqueuedAt" to enqueuedAt,
    "heartbeatAt" to heartbeatAt,
  )

  /** The clock's instant at which a window anchored at 1 000 has shut. */
  private val closedWindowNow: Long = 1_000L + UNRANKED_FILL_MILLIS

  /** The seats of a match, as the test reads them back. */
  private fun seatsOf(match: Map<String, Any?>): Map<String, String> =
    @Suppress("UNCHECKED_CAST")
    (match["seats"] as Map<String, String>)

  // ── 1. the close happens ─────────────────────────────────────────────

  @Test
  fun aPoolTableWhoseWindowClosesBecomesTheMatchTheServerWrites() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.searches["uid-a"] = waitingUnranked("uid-a", heartbeatAt = closedWindowNow)

    runSearchPass(db, clock)

    assertEquals(1, db.matches.size, "the shut window's table became a match")
    val match = db.matches.values.single()
    assertEquals("room-1", match["roomId"])
    assertEquals("UNRANKED", match["mode"])
    assertEquals("starting", match["status"])
  }

  @Test
  fun aConvertedRoomsShutWindowBecomesTheMatchTheServerWrites() = runTest {
    // The host's own room — converted with "Play Unranked" — and nobody
    // searching at all. The window shuts on its own clock, with nobody
    // waiting, so the close must never be gated on the queue.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    assertEquals(1, db.matches.size, "the shut room became a match")
    assertEquals(
      listOf("uid-host"),
      db.matches.values.single()["players"],
      "the host is the only human",
    )
  }

  @Test
  fun theRoomIsBoundToTheMatchItBecame() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    val room = db.rooms.getValue("room-host")
    assertEquals("in_game", room["status"], "the room has started")
    assertEquals("match-1", room["matchId"], "the room names the match it became")
  }

  @Test
  fun theRoomAndItsMatchAreWrittenInOneTransaction() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    // The room's binding and the match itself are one write: a failure
    // between them would leave a room that waits for ever, or a match with
    // no room to belong to.
    assertEquals(
      listOf("tx", "room:room-host", "write-match:match-1", "bind:room-host"),
      db.lastTransaction,
    )
  }

  // ── 2. the match is the client's match, field for field ──────────────

  @Test
  fun theMatchCarriesTheCanonicalFieldSetInOrderAndNothingButTheBotSeatsDelta() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.searches["uid-a"] = waitingUnranked("uid-a", heartbeatAt = closedWindowNow)

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    // The canonical builder's own field set, in the canonical ORDER
    // (`MatchShapes.kt`'s `buildInitialMatchFields`) — minus `createdAt`,
    // which the Admin writer stamps on every match it writes, plus
    // `botSeats`, the one field a client could never write. The ORDER is
    // asserted, not just the set: "the same order" is part of the contract
    // the KDoc states, so a drift in it has to fail here.
    assertEquals(
      listOf(
        "roomId", "players", "status", "currentRound", "maxRounds",
        "currentMultiplier", "gameType", "scoringMode", "mode", "botTier",
        "botPersonality", "decisionTimerSeconds", "extendedRounds", "dealer",
        "turn", "seats", "version", "biddingOpen", "bids", "lastBidSeat",
        "cardLog", "lastCardSeat", "cardPhase", "biddingLog", "gameState",
        "botSeats",
      ),
      match.keys.toList(),
      "the canonical field set in the canonical order, plus botSeats",
    )
    assertFalse(
      match.containsKey("createdAt"),
      "createdAt is the Admin writer's stamp — adding it here would double-stamp the match",
    )
    assertEquals(1, match["currentRound"])
    assertEquals(18, match["maxRounds"], "FULL's ceiling")
    assertEquals(1, match["currentMultiplier"])
    assertEquals(1, match["version"])
    assertEquals(true, match["biddingOpen"])
    assertEquals(emptyList<Any?>(), match["extendedRounds"])
    assertEquals(emptyList<Any?>(), match["cardLog"])
    assertEquals(emptyList<Any?>(), match["biddingLog"])
    assertEquals(
      mapOf("initialized" to false, "dealtRound" to 0),
      match["gameState"],
    )
  }

  @Test
  fun theBidsAreOpenForEverySeatIncludingTheBots() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.searches["uid-a"] = waitingUnranked("uid-a", heartbeatAt = closedWindowNow)

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    @Suppress("UNCHECKED_CAST")
    val bids = match["bids"] as Map<String, Any?>
    assertEquals(setOf("p1", "p2", "p3", "p4"), bids.keys)
    assertTrue(bids.values.all { it == null }, "nobody has bid yet")
  }

  @Test
  fun theDealerIsTheFirstHumanAndTheTurnIsTheDealers() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    assertEquals("uid-host", match["dealer"])
    assertEquals("uid-host", match["turn"])
  }

  // ── 3. the bot seats are seats, and the bots are not players ─────────

  @Test
  fun theMatchHasFourSeatsAndTheBotsTakeTheLastOfThem() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.searches["uid-a"] = waitingUnranked("uid-a", heartbeatAt = closedWindowNow)

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    assertEquals(
      mapOf(
        "p1" to "uid-a",
        "p2" to "bot:unranked:p2",
        "p3" to "bot:unranked:p3",
        "p4" to "bot:unranked:p4",
      ),
      seatsOf(match),
      "the humans keep the first seats and the bots take the rest",
    )
  }

  @Test
  fun thePlayersAreTheHumansOnlySoTheSettlementSkipsTheBots() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    val players = (match["players"] as List<*>).map { it as String }
    assertEquals(listOf("uid-host"), players, "the bot uids are not players")
    val seatUids = seatsOf(match).values.toList()
    assertTrue(
      seatUids.any { it !in players },
      "at least one seat's occupant is not a player — that is the bot seat",
    )
  }

  @Test
  fun theBotSeatListCarriesSeatKeysAndNeverUids() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    assertEquals(
      listOf("p2", "p3", "p4"),
      match["botSeats"],
      "the seat keys, exactly as S72's leave write and MatchDoc.botSeats use them",
    )
  }

  @Test
  fun theBotUidIsSyntheticAndNamesItsSeat() = runTest {
    assertEquals("bot:unranked:p4", unrankedBotUid("p4"))
    assertTrue(
      unrankedBotUid("p4").startsWith(UNRANKED_BOT_UID_PREFIX),
      "a bot uid is never a player's uid",
    )
  }

  @Test
  fun theBotSeatCountIsTheRoomsOwnShape() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom(
      "room-host",
      listOf("uid-a", "uid-b"),
      poolOpenedAt = 1_000L,
    )

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    assertEquals(
      listOf("p3", "p4"),
      match["botSeats"],
      "two humans, two bot seats",
    )
  }

  // ── 4. a table of four humans is never bot-filled ────────────────────

  @Test
  fun aTableOfFourHumansIsNeverBotFilledAndNeverBecomesAMatch() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock()

    for (i in 1..4) {
      enqueueSearchOnce(
        db,
        fakeRequest("uid-$i", linkedMapOf("gameType" to "FULL", "mode" to "UNRANKED")),
        clock,
      )
    }

    assertTrue(db.matches.isEmpty(), "four humans start their own match")
    val room = db.rooms.getValue("room-1")
    assertEquals("waiting", room["status"])
    assertFalse(room.containsKey("matchId"), "the room names no match")
  }

  @Test
  fun aRoomOfFourHumansIsNeverClosedEvenAfterItsWindowShuts() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom(
      "room-host",
      listOf("uid-a", "uid-b", "uid-c", "uid-d"),
      poolOpenedAt = 1_000L,
    )

    runSearchPass(db, clock)

    assertTrue(db.matches.isEmpty(), "the humans' seats are the humans'")
    assertEquals("waiting", db.rooms.getValue("room-host")["status"])
    assertFalse(
      db.events.any { it.startsWith("room:") },
      "the scan settles it: no transaction is opened for a full room: ${db.events}",
    )
  }

  @Test
  fun aRoomThatFilledUpBetweenTheScanAndTheWriteIsNeverClosed() = runTest {
    // The race, one step later: the scan saw a room with a free seat, and
    // the room filled up before the transaction read it. The bots' seats are
    // the humans' seats now, so the close must refuse it.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-a"), poolOpenedAt = 1_000L)
    db.roomChangesOnRead["room-host"] = { current ->
      current + ("players" to listOf("uid-a", "uid-b", "uid-c", "uid-d"))
    }

    runSearchPass(db, clock)

    assertTrue(db.matches.isEmpty(), "a full room is never closed")
  }

  @Test
  fun aRoomWithNoHumansIsNeverClosed() = runTest {
    // The bots alone are not a table: there is nobody to play with, and a
    // match of nothing but bots is not a game.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-empty", emptyList(), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    assertTrue(db.matches.isEmpty(), "no humans, no match")
    assertEquals("waiting", db.rooms.getValue("room-empty")["status"])
    assertFalse(
      db.events.any { it.startsWith("room:") },
      "the scan settles it: no transaction is opened for an empty room: ${db.events}",
    )
  }

  @Test
  fun aRoomWhoseWindowIsStillOpenIsNeverClosed() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = 1_000L + UNRANKED_FILL_MILLIS - 1)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)

    assertTrue(db.matches.isEmpty(), "the window has not shut yet")
    assertEquals("waiting", db.rooms.getValue("room-host")["status"])
  }

  // ── 5. the close is idempotent ───────────────────────────────────────

  @Test
  fun aSecondPassOverAClosedRoomCreatesNoSecondMatch() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)

    runSearchPass(db, clock)
    runSearchPass(db, clock)

    assertEquals(1, db.matches.size, "one room, one match")
  }

  @Test
  fun aRoomThatAlreadyHasAMatchIsNeverEvenReadForAClose() = runTest {
    // A room whose matchId landed but whose status has not: the scan sees a
    // waiting room, and the close must never even open a transaction for it.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)
    db.rooms["room-host"] = db.rooms.getValue("room-host") + (ROOM_FIELD_MATCH_ID to "match-old")

    runSearchPass(db, clock)

    assertTrue(db.matches.isEmpty(), "the room already has its match")
    assertFalse(
      db.events.any { it.startsWith("room:") },
      "no transaction is opened for a room that already has its match: ${db.events}",
    )
  }

  @Test
  fun aRoomThatGainedItsMatchBetweenTheScanAndTheWriteIsNeverClosed() = runTest {
    // The same race, one step later: the scan saw a waiting room, and the
    // room gained its match before the transaction read it. Every decision is
    // re-derived inside the transaction, so the close refuses it.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)
    db.roomChangesOnRead["room-host"] = { current -> current + (ROOM_FIELD_MATCH_ID to "match-old") }

    runSearchPass(db, clock)

    assertTrue(db.matches.isEmpty(), "the room already has its match")
  }

  @Test
  fun aRoomThatStartedBetweenTheScanAndTheWriteIsNeverClosed() = runTest {
    // The race: the scan saw a waiting room, and the room started before the
    // transaction read it. Every decision is re-derived inside the
    // transaction, so the close refuses it.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 1_000L)
    db.roomChangesOnRead["room-host"] = { current -> current + ("status" to "in_game") }

    runSearchPass(db, clock)

    assertTrue(db.matches.isEmpty(), "a room that started is never closed")
  }

  // ── 6. the match inherits the room's own configuration ───────────────

  @Test
  fun theMatchInheritsTheRoomsOwnBotTrio() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom(
      "room-host",
      listOf("uid-host"),
      poolOpenedAt = 1_000L,
      botTier = "HARD",
      botPersonality = "AGGRESSIVE",
      decisionTimerSeconds = 12,
    )

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    assertEquals("HARD", match["botTier"])
    assertEquals("AGGRESSIVE", match["botPersonality"])
    assertEquals(12, match["decisionTimerSeconds"])
  }

  @Test
  fun theMatchInheritsTheRoomsGameTypeAndScoringMode() = runTest {
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom(
      "room-host",
      listOf("uid-host"),
      poolOpenedAt = 1_000L,
      gameType = "MINI",
    )

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    assertEquals("MINI", match["gameType"])
    assertEquals(10, match["maxRounds"], "MINI's ceiling")
    assertEquals("NORMAL", match["scoringMode"])
  }

  @Test
  fun aRoomWithNoBotConfigurationTakesTheDefaults() = runTest {
    // A room written before RD21's trio existed parses as MEDIUM / BALANCED /
    // 15 s — the same defaults the client's own room parser uses.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.rooms["room-old"] = linkedMapOf(
      "status" to "waiting",
      "creator" to "uid-host",
      "players" to listOf("uid-host"),
      "mode" to "UNRANKED",
      ROOM_FIELD_POOL_OPENED_AT to 1_000L,
    )

    runSearchPass(db, clock)

    val match = db.matches.values.single()
    assertEquals("MEDIUM", match["botTier"])
    assertEquals("BALANCED", match["botPersonality"])
    assertEquals(15, match["decisionTimerSeconds"])
  }

  @Test
  fun aDecisionTimerOutsideTheAllowedWindowIsTreatedAsMissing() = runTest {
    // The rules deny writing a timer outside RD21's 5..20 s window, so a read
    // can never surface one: the value is treated as missing, not clamped.
    val db = FakeSearchDb()
    val clock = FakeSearchClock(now = closedWindowNow)
    db.openUnrankedRoom(
      "room-host",
      listOf("uid-host"),
      poolOpenedAt = 1_000L,
      decisionTimerSeconds = 45,
    )

    runSearchPass(db, clock)

    assertEquals(15, db.matches.values.single()["decisionTimerSeconds"])
  }
}
