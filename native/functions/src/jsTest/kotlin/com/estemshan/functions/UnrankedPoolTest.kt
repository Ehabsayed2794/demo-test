package com.estemshan.functions

import com.estemshan.engine.UNRANKED_FILL_MILLIS
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S71 — the UNRANKED half of the queue, on the same fake seam the Ranked
 * half is tested on.
 *
 * The pure policy (the rank-blind grouping, the four-minute window, the bot
 * seat COUNT) is pinned by the engine's own `UnrankedMatchmakingTest` and is
 * NOT re-tested here. What is pinned here is the part the policy alone
 * cannot prove — that the SERVER decides who is seated with whom, that the
 * Unranked pool never consults a rank, and that an open Unranked room's free
 * seats are handed to the pool.
 *
 * The properties this story has to leave provable:
 *
 *  1. **The pool is rank-blind.** An Unranked search reads NO profile, so a
 *     player with no ranked profile may still play — and its queue document
 *     carries NO tier, so no rank can be read from it even by accident.
 *  2. **The fill window is four minutes.** A group smaller than four is not a
 *     table until its window closes; four humans of one GameType are a table
 *     at once.
 *  3. **GM5 still rides along.** A Full searcher is never seated at a Mini
 *     table.
 *  4. **The two pools are separate.** A Ranked search and an Unranked search
 *     never share a table.
 *  5. **An open Unranked room's free seats go to the pool**, and a searcher
 *     seated there is never ALSO counted into a pool table.
 *  6. **A late human never takes a seat the timer already gave to the bot.**
 *  7. **The client's answer carries the deadline** it is waiting against.
 *
 * The bots' OCCUPATION is not pinned here: the seat the timer gives to a bot
 * is the engine's count, and writing that bot into the room's roster is the
 * one boundary this story leaves open (a client-created match cannot carry a
 * bot seat under the frozen rules).
 */
class UnrankedPoolTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  /**
   * A queue with NO profiles at all — the rank-blind pool never reads one, so
   * a fixture that had profiles would hide the property.
   */
  private fun db(): FakeSearchDb = FakeSearchDb()

  private fun profile(tier: String, rp: Int): Map<String, Any?> = linkedMapOf(
    "tier" to tier,
    "division" to "",
    "rp" to rp,
    "seasonId" to "s1",
  )

  private suspend fun enqueueUnranked(
    db: FakeSearchDb,
    uid: String,
    gameType: String = "FULL",
    clock: FakeSearchClock,
  ): Map<String, Any?> = enqueueSearchOnce(
    db,
    fakeRequest(uid, linkedMapOf("gameType" to gameType, "mode" to "UNRANKED")),
    clock,
  )

  private suspend fun enqueueRanked(
    db: FakeSearchDb,
    uid: String,
    gameType: String = "FULL",
    clock: FakeSearchClock,
  ): Map<String, Any?> = enqueueSearchOnce(
    db,
    fakeRequest(uid, linkedMapOf("gameType" to gameType, "mode" to "RANKED")),
    clock,
  )

  /**
   * Polls until [until], so the searcher's lease survives the window.
   *
   * The lease is 30 s and the fill window is four minutes: a client that stops
   * polling is reclaimed long before its window closes, which is correct —
   * the client is gone. A client that KEEPS polling is the one whose seat the
   * window is really counting down to.
   */
  private suspend fun pollUntil(
    db: FakeSearchDb,
    uid: String,
    clock: FakeSearchClock,
    until: Long,
  ) {
    while (clock.now < until) {
      clock.now = minOf(clock.now + 10_000L, until)
      pollSearchOnce(db, fakeRequest(uid, emptyMap()), clock)
    }
  }

  /** The humans seated at a table, in the table's own order. */
  private fun playersOf(room: Map<String, Any?>): List<String> =
    (room["players"] as List<*>).map { it as String }

  // ── 1. the pool is rank-blind ────────────────────────────────────────

  @Test
  fun anUnrankedSearchReadsNoProfileSoAPlayerWithoutOneMayPlay() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    val answer = enqueueUnranked(db, "uid-a", clock = clock)

    assertEquals("SEARCHING", answer["status"])
    assertFalse(
      db.events.any { it.startsWith("profile:") },
      "the Unranked pool reads no profile: ${db.events}",
    )
    assertTrue(db.profiles.isEmpty(), "the fixture has no profile to read")
  }

  @Test
  fun theUnrankedSearchDocumentCarriesNoTierAtAll() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    enqueueUnranked(db, "uid-a", clock = clock)

    val document = db.searches.getValue("uid-a")
    assertEquals(
      setOf("uid", "mode", "gameType", "status", "enqueuedAt", "heartbeatAt"),
      document.keys,
    )
    assertEquals("UNRANKED", document["mode"])
    assertFalse(document.containsKey("tier"), "a rank-blind document holds no tier")
  }

  @Test
  fun aKingAndABronzeShareAnUnrankedTableBecauseNeitherCarriesARank() = runTest {
    // The two players are named for the tiers they would hold — and the
    // resolver never learns either: no profile exists, and no tier is
    // written. The table they share is the rank-blindness, expressed.
    val db = db()
    val clock = FakeSearchClock()

    for (i in 1..4) enqueueUnranked(db, "uid-$i", clock = clock)

    assertEquals(1, db.rooms.size, "four Unranked searchers are one table")
    assertEquals(
      listOf("uid-1", "uid-2", "uid-3", "uid-4"),
      playersOf(db.rooms.values.single()),
    )
  }

  // ── 2. the four-minute fill window ───────────────────────────────────

  @Test
  fun aGroupSmallerThanFourIsNotATableUntilItsWindowCloses() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    enqueueUnranked(db, "uid-a", clock = clock)

    assertTrue(db.rooms.isEmpty(), "one human is not a table yet")
    assertEquals("WAITING", db.searches.getValue("uid-a")["status"])
  }

  @Test
  fun theTableFormsWhenTheFourMinuteWindowCloses() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    enqueueUnranked(db, "uid-a", clock = clock)
    pollUntil(db, "uid-a", clock, 1_000L + UNRANKED_FILL_MILLIS)

    val table = db.rooms.values.single()
    assertEquals(listOf("uid-a"), playersOf(table))
    // The other three seats are the configured bots' — the COUNT is the room's
    // own shape (four seats minus the humans seated in them), and writing the
    // bots themselves into the roster is the boundary this story leaves open.
    assertEquals(1, playersOf(table).size)
    assertEquals("RESOLVED", db.searches.getValue("uid-a")["status"])
  }

  @Test
  fun fourHumansOfTheSameGameTypeAreATableAtOnce() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    for (i in 1..4) enqueueUnranked(db, "uid-$i", clock = clock)

    assertEquals(1, db.rooms.size, "four humans fill the table on their own")
    assertEquals(listOf("uid-1", "uid-2", "uid-3", "uid-4"), playersOf(db.rooms.values.single()))
  }

  // ── 3. GM5 still rides along ─────────────────────────────────────────

  @Test
  fun aFullSearcherIsNeverSeatedAtAMiniTable() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    for (i in 1..4) enqueueUnranked(db, "full-$i", gameType = "FULL", clock = clock)
    for (i in 1..4) enqueueUnranked(db, "mini-$i", gameType = "MINI", clock = clock)

    assertEquals(2, db.rooms.size, "the two GameTypes are two tables")
    for (room in db.rooms.values) {
      val players = playersOf(room)
      val sameType = players.all { it.startsWith("full-") } || players.all { it.startsWith("mini-") }
      assertTrue(sameType, "a table never mixes GameTypes: $players")
    }
  }

  // ── 4. the two pools are separate ────────────────────────────────────

  @Test
  fun aRankedSearchAndAnUnrankedSearchNeverShareATable() = runTest {
    val db = db()
    val clock = FakeSearchClock()
    db.profiles["uid-ranked"] = profile("Gold", 575)

    enqueueRanked(db, "uid-ranked", clock = clock)
    for (i in 1..4) enqueueUnranked(db, "u-$i", clock = clock)

    assertEquals(1, db.rooms.size, "only the Unranked searchers form a table")
    val table = db.rooms.values.single()
    assertEquals("UNRANKED", table["mode"])
    assertEquals(
      listOf("u-1", "u-2", "u-3", "u-4"),
      playersOf(table),
      "the Ranked searcher is never seated at the Unranked table",
    )
    assertEquals("WAITING", db.searches.getValue("uid-ranked")["status"])
  }

  // ── 5. an open Unranked room's free seats go to the pool ─────────────

  @Test
  fun anOpenUnrankedRoomsFreeSeatIsOfferedToThePool() = runTest {
    val db = db()
    val clock = FakeSearchClock()
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = clock.now)

    enqueueUnranked(db, "uid-a", clock = clock)

    assertEquals(1, db.rooms.size, "the pool forms no table of its own")
    assertEquals(
      listOf("uid-host", "uid-a"),
      playersOf(db.rooms.getValue("room-host")),
      "the searcher took the room's free seat",
    )
    assertEquals("RESOLVED", db.searches.getValue("uid-a")["status"])
    assertEquals(
      "room-host",
      db.searches.getValue("uid-a")["tableId"],
      "the search resolves into the room it was seated in",
    )
  }

  @Test
  fun aSearcherSeatedInAnOpenRoomIsNeverAlsoCountedIntoAPoolTable() = runTest {
    val db = db()
    val clock = FakeSearchClock()
    // Three seats taken, one free: the first searcher takes it, and the rest
    // are a table of their own.
    db.openUnrankedRoom(
      "room-host",
      listOf("uid-host", "uid-x", "uid-y"),
      poolOpenedAt = clock.now,
    )

    for (i in 1..5) enqueueUnranked(db, "u-$i", clock = clock)

    assertEquals(2, db.rooms.size, "the room's seat and the pool's table are two tables")
    assertEquals(
      listOf("uid-host", "uid-x", "uid-y", "u-1"),
      playersOf(db.rooms.getValue("room-host")),
    )
    assertEquals(
      listOf("u-2", "u-3", "u-4", "u-5"),
      playersOf(db.rooms.getValue("room-1")),
      "a searcher seated in a room is never counted into a pool table as well",
    )
  }

  @Test
  fun aRankedRoomIsNeverOfferedToTheUnrankedPool() = runTest {
    val db = db()
    val clock = FakeSearchClock()
    db.rooms["room-ranked"] = linkedMapOf(
      "status" to "waiting",
      "creator" to "uid-host",
      "players" to listOf("uid-host"),
      "mode" to "RANKED",
      // A creation stamp, so the room has an anchor and an OPEN window: it
      // is a room the pool WOULD offer if the scan's mode filter were not
      // there. A room without an anchor would be skipped by an earlier guard
      // and the filter would never be exercised at all.
      "createdAt" to clock.now,
    )

    enqueueUnranked(db, "uid-a", clock = clock)
    // The RESOLVER, not just the enqueue: the scan that offers the open
    // rooms' free seats is the resolver's, and the scan's own mode filter is
    // what keeps a Ranked room out of the pool.
    runSearchPass(db, clock)

    assertEquals(
      listOf("uid-host"),
      playersOf(db.rooms.getValue("room-ranked")),
      "a Ranked room is never offered to the Unranked pool",
    )
    assertEquals(1, db.rooms.size, "the pool forms no table of its own yet")
    // The SCAN's filter, not the transaction's re-check: a Ranked room must
    // never even be OPENED. An outcome assertion alone cannot tell the two
    // apart — the transaction re-checks the mode and would refuse the seat
    // anyway — so the guard that keeps the room out of the scan is asserted
    // here as what it is: no transaction was opened against it.
    assertFalse(
      db.events.any { it == "room:room-ranked" },
      "the resolver never opened a transaction against the Ranked room",
    )
  }

  @Test
  fun aRoomThatIsNotWaitingIsNeverOfferedToThePool() = runTest {
    val db = db()
    val clock = FakeSearchClock()
    db.openUnrankedRoom("room-live", listOf("uid-host"), poolOpenedAt = clock.now, status = "in_game")

    enqueueUnranked(db, "uid-a", clock = clock)
    runSearchPass(db, clock)

    assertEquals(
      listOf("uid-host"),
      playersOf(db.rooms.getValue("room-live")),
      "a room that has started is never offered to the pool",
    )
    assertFalse(
      db.events.any { it == "room:room-live" },
      "the resolver never opened a transaction against the started room",
    )
  }

  // ── 6. a late human never takes a bot's seat ─────────────────────────

  @Test
  fun aLateHumanIsNotSeatedInARoomWhoseWindowHasClosed() = runTest {
    val db = db()
    // The room entered the pool five minutes ago, so its free seats are the
    // bots' — the timer gave them away before this searcher arrived.
    val clock = FakeSearchClock(now = 300_000L)
    db.openUnrankedRoom("room-host", listOf("uid-host"), poolOpenedAt = 0L)

    enqueueUnranked(db, "uid-a", clock = clock)

    assertEquals(
      listOf("uid-host"),
      playersOf(db.rooms.getValue("room-host")),
      "the closed window's seat belongs to the bot",
    )
    assertEquals("WAITING", db.searches.getValue("uid-a")["status"])
  }

  // ── 7. the answer carries the deadline ───────────────────────────────

  @Test
  fun theAnswerCarriesTheFillDeadlineForAnUnrankedSearch() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    val answer = enqueueUnranked(db, "uid-a", clock = clock)

    assertEquals("SEARCHING", answer["status"])
    assertEquals(
      1_000L + UNRANKED_FILL_MILLIS,
      answer["fillDeadlineMillis"],
      "the client is told the deadline it is waiting against",
    )
  }

  @Test
  fun aRankedSearchIsToldNoDeadlineBecauseItsTableIsAPair() = runTest {
    val db = db()
    val clock = FakeSearchClock()
    db.profiles["uid-ranked"] = profile("Gold", 575)

    val answer = enqueueRanked(db, "uid-ranked", clock = clock)

    assertEquals("SEARCHING", answer["status"])
    assertFalse(
      answer.containsKey("fillDeadlineMillis"),
      "the Ranked queue has no fill window: $answer",
    )
  }

  // ── the shape of what the pool writes ────────────────────────────────

  @Test
  fun theTableIsWrittenInTheRoomsOwnShapeWithThePoolStamp() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    for (i in 1..4) enqueueUnranked(db, "uid-$i", clock = clock)

    val table = db.rooms.values.single()
    assertEquals(
      setOf(
        "name", "status", "creator", "players", "readyPlayers", "gameType",
        "scoringMode", "mode", "rankDown", "botTier", "botPersonality",
        "decisionTimerSeconds", "poolOpenedAt",
      ),
      table.keys,
    )
    assertEquals("waiting", table["status"])
    assertEquals("uid-1", table["creator"])
    assertEquals(emptyList<String>(), table["readyPlayers"])
    assertEquals("FULL", table["gameType"])
    assertEquals("NORMAL", table["scoringMode"])
    assertEquals("UNRANKED", table["mode"])
    assertEquals(false, table["rankDown"])
    assertEquals("MEDIUM", table["botTier"])
    assertEquals("BALANCED", table["botPersonality"])
    assertEquals(15, table["decisionTimerSeconds"])
    assertEquals(1_000L, table["poolOpenedAt"], "the window's anchor is the group's first arrival")
  }

  @Test
  fun theUnrankedTransactionReadsEverySearchBeforeItWrites() = runTest {
    val db = db()
    val clock = FakeSearchClock()

    for (i in 1..4) enqueueUnranked(db, "uid-$i", clock = clock)

    assertEquals(
      listOf(
        "tx",
        "read:uid-1",
        "read:uid-2",
        "read:uid-3",
        "read:uid-4",
        "write-room:room-1",
        "resolve:uid-1",
        "resolve:uid-2",
        "resolve:uid-3",
        "resolve:uid-4",
      ),
      db.lastTransaction,
    )
  }

  @Test
  fun aSearchThatVanishedMidPassAbandonsTheWholeTable() = runTest {
    // The race the resolver is written for: one of the four searchers is
    // seated elsewhere between the scan and the write. The table is abandoned
    // — never created with a searcher who is no longer waiting.
    val db = db()
    val clock = FakeSearchClock()
    for (i in 1..4) db.searches["uid-$i"] = waitingUnranked("uid-$i")
    db.vanishInsideTransaction += "uid-3"

    runSearchPass(db, clock)

    assertTrue(db.rooms.isEmpty(), "an abandoned table is never created")
    assertEquals("WAITING", db.searches.getValue("uid-4")["status"])
  }

  /** A WAITING Unranked queue document, as the server writes one. */
  private fun waitingUnranked(uid: String, enqueuedAt: Long = 1_000L): Map<String, Any?> = linkedMapOf(
    "uid" to uid,
    "mode" to "UNRANKED",
    "gameType" to "FULL",
    "status" to "WAITING",
    "enqueuedAt" to enqueuedAt,
    "heartbeatAt" to enqueuedAt,
  )
}
