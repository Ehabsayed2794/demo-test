package com.estemshan.functions

import com.estemshan.engine.Dealer
import com.estemshan.engine.Suit
import com.estemshan.functions.Fixtures.archiveFields
import com.estemshan.functions.Fixtures.matchFields
import com.estemshan.functions.Fixtures.rawRound
import com.estemshan.functions.Fixtures.round1Golden
import com.estemshan.functions.Fixtures.round1Parts
import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Durable Object storage adapter, exercised against REAL SQL.
 *
 * The tests run inside the jsTest runner on Node and open an in-memory
 * `node:sqlite` database (experimental but present in Node ≥ 22.5; warning
 * only, no flag). The `sqlHandle` handed to the adapter is a ~15-line JS
 * shim exposing the same surface as the DO's `ctx.storage.sql` (`exec` →
 * cursor with `toArray()`), so every INSERT / UPDATE / SELECT the adapter
 * issues runs against actual SQLite semantics — not a mock of them.
 *
 * Cross-runtime equivalence for the *logic* is carried by the shared
 * orchestration; what is proven HERE is the adapter itself: JSON fidelity,
 * sentinel resolution at the write boundary, claim handling, and the
 * round/archive read shapes.
 */
class DoStorageTest {

  // ── harness ─────────────────────────────────────────────────────────

  /** Fresh in-memory database with the DO schema applied. */
  private fun freshDb(): dynamic {
    val db: dynamic = js("new (require('node:sqlite').DatabaseSync)(':memory:')")
    db.exec(DO_SCHEMA)
    return db
  }

  /** The DO's `ctx.storage.sql` surface, backed by node:sqlite. */
  private fun sqlHandle(db: dynamic): dynamic {
    // The IR intrinsic does not reliably resolve Kotlin locals inside a
    // multi-statement js() IIFE, so the handle reaches the database through
    // a per-test global instead. Test process is single-threaded; every
    // test re-seeds the global before creating its adapter.
    val globals: dynamic = js("globalThis")
    globals.__SQLITE_TEST_DB = db
    return js("({ exec: function (query) { var b = Array.prototype.slice.call(arguments, 1); var s = globalThis.__SQLITE_TEST_DB.prepare(query); var rows = s.all.apply(s, b); return { toArray: function () { return rows; } }; } })")
  }

  private fun seedMatch(db: dynamic, matchId: String, fields: Map<String, Any?>) {
    val v = (fields["version"] as? Number)?.toLong() ?: 0
    val stmt: dynamic = js("db.prepare('INSERT INTO match_state(match_id, version, data, created_at, updated_at) VALUES (?,?,?,?,?)')")
    stmt.run(matchId, v.toDouble(), toJson(fields), 1_000.0, 2_000.0)
  }

  private fun seedArchive(db: dynamic, matchId: String, round: Int, data: Map<String, Any?>) {
    val stmt: dynamic = js("db.prepare('INSERT INTO round_archive(match_id, round, data) VALUES (?,?,?)')")
    stmt.run(matchId, round.toDouble(), toJson(data))
  }

  private fun seedHands(db: dynamic, matchId: String, round: Int, seatId: String, cards: List<Map<String, Any?>>) {
    val stmt: dynamic = js("db.prepare('INSERT INTO hands(match_id, round, seat_id, data) VALUES (?,?,?,?)')")
    stmt.run(matchId, round.toDouble(), seatId, toJson(mapOf("round" to round, "seatId" to seatId, "cards" to cards)))
  }

  private fun rawMatch(db: dynamic, matchId: String = "m1"): Map<String, Any?> {
    val stmt: dynamic = js("db.prepare('SELECT data FROM match_state WHERE match_id = ?')")
    @Suppress("UNCHECKED_CAST")
    val rows = stmt.all(matchId) as Array<dynamic>
    return parseJson(rows[0].data as String) as Map<String, Any?>
  }

  private fun versionColumn(db: dynamic, matchId: String = "m1"): Int {
    val stmt: dynamic = js("db.prepare('SELECT version FROM match_state WHERE match_id = ?')")
    @Suppress("UNCHECKED_CAST")
    val rows = stmt.all(matchId) as Array<dynamic>
    return (rows[0].version as Number).toInt()
  }

  private fun claimCount(db: dynamic, matchId: String = "m1"): Int {
    val stmt: dynamic = js("db.prepare('SELECT COUNT(*) AS n FROM settlement_claim WHERE match_id = ?')")
    @Suppress("UNCHECKED_CAST")
    val rows = stmt.all(matchId) as Array<dynamic>
    return (rows[0].n as Number).toInt()
  }

  private fun insertClaim(db: dynamic, matchId: String, data: String) {
    val stmt: dynamic = js("db.prepare('INSERT INTO settlement_claim(match_id, data, created_at) VALUES (?,?,?)')")
    stmt.run(matchId, data, 1_000.0)
  }

  private fun seedConvergedRound1(db: dynamic, matchId: String = "m1") {
    seedMatch(db, matchId, matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)))
    val golden = round1Golden()
    val parts = round1Parts()
    seedArchive(db, matchId, 1, rawRound(golden, parts.plays, parts.actions))
  }

  private fun settleData(matchId: String = "m1"): Any? = json("matchId" to matchId)

  // ── full timeout resolution through REAL SQLite ────────────────────────

  private val timeoutAnchorMillis = 1_000_000L
  private val timeoutSeats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")

  private fun fastRoundDoc(): Map<String, Any?> = mutableMapOf(
    "roomId" to "room-1",
    "players" to listOf("u1", "u2", "u3", "u4"),
    "status" to "starting",
    "currentRound" to 14,
    "maxRounds" to 18,
    "completedRound" to 0,
    "extendedRounds" to emptyList<Int>(),
    "dealer" to timeoutSeats.getValue("p1"),
    "turn" to null,
    "seats" to timeoutSeats,
    "version" to 1,
    "biddingOpen" to true,
    "bids" to linkedMapOf<String, Int?>(*listOf("p1", "p2", "p3", "p4").map { it to null }.toTypedArray()),
    "lastBidSeat" to null,
    "cardLog" to emptyList<Map<String, Any?>>(),
    "lastCardSeat" to null,
    "cardPhase" to null,
    "biddingLog" to emptyList<Map<String, Any?>>(),
    "gameState" to mapOf("initialized" to true, "dealtRound" to 14),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to "RANKED",
    "botTier" to "MEDIUM",
    "botPersonality" to "BALANCED",
    "decisionTimerSeconds" to 15,
    "updatedAt" to timeoutAnchorMillis,
  )

  private fun seedDealtHands(db: dynamic, matchId: String = "m1", round: Int = 14) {
    val hands = Dealer.dealHands(11L)
    for ((seat, cards) in hands) {
      val stored = cards.map { card ->
        mapOf("suit" to card.suit.name, "rank" to mapOf("v" to card.rank.v, "s" to card.rank.s))
      }
      seedHands(db, matchId, round, seat, stored)
    }
  }

  // ── tests ───────────────────────────────────────────────────────────

  @Test
  fun aSeededMatchRoundTripsWithUnicodeAndNesting() = runTest {
    val db = freshDb()
    val doc = LinkedHashMap(matchFields())
    doc["title"] = "صيادة الصغيرة — \"لا تُجهل\""
    doc["meta"] = mapOf("nested" to listOf(1, "اثنان", true))
    seedMatch(db, "m1", doc)

    val adapter = DoSettlementDb(sqlHandle(db))
    val read = adapter.readMatch("m1")

    assertNotNull(read)
    assertEquals("صيادة الصغيرة — \"لا تُجهل\"", read["title"])
    @Suppress("UNCHECKED_CAST")
    val meta = read["meta"] as Map<String, Any?>
    assertEquals(listOf(1.0, "اثنان", true), meta["nested"])
    assertEquals(7.0, (read["version"] as Number).toDouble())
  }

  @Test
  fun aMissingMatchReadsNull() = runTest {
    val db = freshDb()
    val adapter = DoSettlementDb(sqlHandle(db))
    assertNull(adapter.readMatch("nope"))
  }

  @Test
  fun corruptMatchJsonFailsClosedAsFailedPrecondition() = runTest {
    val db = freshDb()
    val stmt: dynamic = js("db.prepare('INSERT INTO match_state(match_id, version, data, created_at, updated_at) VALUES (?,?,?,?,?)')")
    stmt.run("m1", 1.0, "{corrupt", 1_000.0, 2_000.0)

    val adapter = DoSettlementDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> { adapter.readMatch("m1") }

    assertEquals("failed-precondition", error.code)
  }

  @Test
  fun theArchiveReadComesBackOrderedByRound() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields())
    for (round in listOf(2, 1, 3)) {
      seedArchive(db, "m1", round, archiveFields(round, emptyList(), emptyList()))
    }

    val adapter = DoSettlementDb(sqlHandle(db))
    val archives = adapter.readArchives("m1")

    assertEquals(listOf("1", "2", "3"), archives.keys.toList())
  }

  @Test
  fun handsFilterByRoundAndSeatsAndParseAsEngineCards() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields())
    val spades = mapOf("suit" to "SPADES", "rank" to mapOf("v" to 14, "s" to "A"))
    val hearts = mapOf("suit" to "HEARTS", "rank" to mapOf("v" to 11, "s" to "J"))
    seedHands(db, "m1", 1, "p1", listOf(spades, hearts))
    seedHands(db, "m1", 1, "p2", listOf(mapOf("suit" to "CLUBS", "rank" to mapOf("v" to 5, "s" to "5"))))
    // Stale round-2 row for p1 — must not leak into round-1 reads.
    seedHands(db, "m1", 2, "p1", listOf(mapOf("suit" to "DIAMONDS", "rank" to mapOf("v" to 2, "s" to "2"))))

    val adapter = DoTimeoutDb(sqlHandle(db))
    val hands = adapter.readHands("m1", 1, listOf("p1", "p2"))

    assertEquals(setOf("p1", "p2"), hands.keys)
    assertEquals(2, hands.getValue("p1").size)
    assertEquals(Suit.SPADES, hands.getValue("p1")[0].suit)
    assertEquals(14, hands.getValue("p1")[0].rank.v)
    assertEquals(1, hands.getValue("p2").size)
  }

  @Test
  fun aCorruptArchiveFailsClosedAsFailedPrecondition() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields())
    val stmt: dynamic = js("db.prepare('INSERT INTO round_archive(match_id, round, data) VALUES (?,?,?)')")
    stmt.run("m1", 1.0, "{corrupt")

    val adapter = DoSettlementDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> { adapter.readArchives("m1") }

    assertEquals("failed-precondition", error.code)
  }

  @Test
  fun aCorruptHandFailsClosedAsFailedPrecondition() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields())
    val stmt: dynamic = js("db.prepare('INSERT INTO hands(match_id, round, seat_id, data) VALUES (?,?,?,?)')")
    stmt.run("m1", 1.0, "p1", "{corrupt")

    val adapter = DoTimeoutDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> { adapter.readHands("m1", 1, listOf("p1")) }

    assertEquals("failed-precondition", error.code)
  }

  @Test
  fun anUnreadableCardFailsClosedAsFailedPrecondition() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields())
    seedHands(db, "m1", 1, "p1", listOf(mapOf("suit" to "NOT_A_SUIT", "rank" to mapOf("v" to 14))))

    val adapter = DoTimeoutDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> { adapter.readHands("m1", 1, listOf("p1")) }

    assertEquals("failed-precondition", error.code)
  }

  @Test
  fun updateMatchExtendsTheLogAndResolvesSentinels() = runTest {
    val db = freshDb()
    val seeded = LinkedHashMap(matchFields())
    seeded["biddingLog"] = emptyList<Map<String, Any?>>()
    seeded["bids"] = mapOf("p1" to 3)
    seedMatch(db, "m1", seeded)

    val adapter = DoTimeoutDb(sqlHandle(db))
    adapter.transact { tx ->
      tx.updateMatch(
        "m1",
        mapOf(
          "biddingLog" to AuthoritySentinels.arrayUnion(
            toPlain(mapOf("seatId" to "p1", "actionType" to "SubmitDashCallDecision", "round" to 1)),
          ),
          "updatedAt" to AuthoritySentinels.serverTimestamp(),
          "version" to 8,
        ),
      )
    }

    val raw = rawMatch(db)
    @Suppress("UNCHECKED_CAST")
    val log = raw["biddingLog"] as List<Map<String, Any?>>
    assertEquals(1, log.size)
    assertEquals("p1", log[0]["seatId"])
    assertTrue((raw["updatedAt"] as Number).toLong() > 0, "the sentinel must resolve to epoch millis")
    assertEquals(8, (raw["version"] as Number).toInt())
    assertEquals(8, versionColumn(db))
  }

  @Test
  fun updateMatchReplacesTheBidsMapWholesale() = runTest {
    val db = freshDb()
    val seeded = LinkedHashMap(matchFields())
    seeded["bids"] = mapOf("p1" to 3)
    seedMatch(db, "m1", seeded)

    val adapter = DoTimeoutDb(sqlHandle(db))
    adapter.transact { tx ->
      tx.updateMatch("m1", mapOf("bids" to mapOf("p1" to 4, "p2" to 1), "version" to 8))
    }

    @Suppress("UNCHECKED_CAST")
    val bids = rawMatch(db)["bids"] as Map<String, Any?>
    assertEquals(mapOf("p1" to 4.0, "p2" to 1.0), bids)
  }

  /**
   * The full RP golden through the DO adapter (agree/corrected, claim once,
   * replay exactly-once, version 7→8) — blocked by design until Step 2
   * (O-06 D6): the DO store does not own `players/{uid}` yet, and a Ranked
   * settlement must not fold RP against a profile it cannot read (that
   * would be a fabricated pre-image), so the transaction fails closed
   * before any write. Pinned as the interim contract: refusal with
   * `unimplemented`, nothing written. The goldens through the fakes live in
   * SettlementTest/RpApplicationTest. When Step 2 lands, restore the full
   * assertions and keep this fail-closed case as the missing-profile pin.
   */
  @Test
  fun aConvergedMatchFailsClosedUntilProfilesLandInStep2() = runTest {
    val db = freshDb()
    seedConvergedRound1(db)
    val before = rawMatch(db)

    val adapter = DoSettlementDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> { settleOnce(adapter, "u1", settleData()) }

    assertEquals("unimplemented", error.code)
    assertEquals(toJson(before), toJson(rawMatch(db)), "the document must be untouched")
    assertEquals(0, claimCount(db), "no claim may be written when profiles are missing")
    assertEquals(7, versionColumn(db), "no write lands before the fail-closed gate")
  }

  /**
   * ROOM matches settle statistics-only on main (S6/A8.4 — the "NOT_RANKED"
   * no-op response is gone), and that path also reads the attributed human
   * seats' profiles, so on the DO adapter it fails closed exactly like the
   * Ranked path until Step 2 lands. Pins: refusal with `unimplemented`,
   * document untouched, zero claims. When Step 2 lands, restore the
   * stats-only assertions (counters +1 per attributed human seat, one claim,
   * replay with empty movements).
   */
  @Test
  fun aRoomMatchFailsClosedUntilProfilesLandInStep2() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields(mode = "ROOM"))
    val before = rawMatch(db)

    val adapter = DoSettlementDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> { settleOnce(adapter, "u1", settleData()) }

    assertEquals("unimplemented", error.code)
    assertEquals(toJson(before), toJson(rawMatch(db)), "the document must be untouched")
    assertEquals(0, claimCount(db))
  }

  @Test
  fun aNonPlayerIsDeniedBeforeAnyWrite() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields())

    val adapter = DoSettlementDb(sqlHandle(db))
    assertFailsWith<AuthorityError> { settleOnce(adapter, "not-a-player", settleData()) }
    assertEquals(0, claimCount(db))
  }

  @Test
  fun aCorruptClaimFailsClosedAndWritesNothing() = runTest {
    val db = freshDb()
    seedConvergedRound1(db)
    insertClaim(db, "m1", "{corrupt")

    val adapter = DoSettlementDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> { settleOnce(adapter, "u1", settleData()) }

    assertEquals("failed-precondition", error.code)
    assertEquals("the stored claim is corrupt.", error.message)
    assertEquals(1, claimCount(db), "the corrupt row is left in place for inspection")
  }

  @Test
  fun timeoutCountsUpsertPerPlayerAndDistinguishAbsentFromZero() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", matchFields())
    val adapter = DoTimeoutDb(sqlHandle(db))

    assertNull(adapter.transact { tx -> tx.readTimeoutCount("m1", "u1") })
    assertEquals(false, adapter.transact { tx -> tx.timeoutCountExists("m1", "u1") })

    adapter.transact { tx -> tx.writeTimeoutCount("m1", "u1", 0) }
    assertEquals(0.0, (adapter.transact { tx -> tx.readTimeoutCount("m1", "u1") } as Number).toDouble())
    assertEquals(true, adapter.transact { tx -> tx.timeoutCountExists("m1", "u1") })

    adapter.transact { tx -> tx.writeTimeoutCount("m1", "u1", 1) }
    adapter.transact { tx -> tx.writeTimeoutCount("m1", "u1", 7) }
    assertEquals(7.0, (adapter.transact { tx -> tx.readTimeoutCount("m1", "u1") } as Number).toDouble())
  }

  @Test
  fun anExpiredBiddingDecisionResolvesThroughRealSqlite() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", fastRoundDoc())
    seedDealtHands(db)

    val adapter = DoTimeoutDb(sqlHandle(db))
    // u3 triggers it (host-less); the acting seat is p1 (dealer, u1).
    val response = resolveTimeoutOnce(
      adapter,
      "u3",
      json("matchId" to "m1"),
      DecisionClock { timeoutAnchorMillis + 20_000 },
    )

    assertEquals(true, response["resolved"])
    assertEquals("p1", response["seatId"])
    assertEquals("BIDDING", response["deadlinePhase"])
    assertEquals(20, response["deadlineSeconds"])

    val raw = rawMatch(db)
    // Round-14 fast round waits on the dealer's first ESTIMATE: the bot's
    // write shape is bids/ + biddingOpen + lastBidSeat, not biddingLog.
    @Suppress("UNCHECKED_CAST")
    val bids = raw["bids"] as Map<String, Any?>
    assertTrue(bids["p1"] != null, "the MEDIUM estimate must land in bids")
    assertEquals("p1", raw["lastBidSeat"])
    assertTrue(versionColumn(db) > 1, "the decision write must bump version")
    assertEquals(
      1.0,
      (adapter.transact { tx -> tx.readTimeoutCount("m1", "u1") } as Number).toDouble(),
      "the first human timeout must create the counter at 1",
    )
  }

  @Test
  fun anUnexpiredDeadlineWritesNothingThroughRealSqlite() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", fastRoundDoc())
    seedDealtHands(db)
    val before = toJson(rawMatch(db))

    val adapter = DoTimeoutDb(sqlHandle(db))
    val response = resolveTimeoutOnce(
      adapter,
      "u3",
      json("matchId" to "m1"),
      DecisionClock { timeoutAnchorMillis + 19_999 },
    )

    assertEquals(false, response["resolved"])
    assertEquals("NOT_YET_EXPIRED", response["reason"])
    assertEquals(before, toJson(rawMatch(db)), "an unexpired trigger must write nothing")
    assertNull(adapter.transact { tx -> tx.readTimeoutCount("m1", "u1") })
  }

  @Test
  fun aCorruptHandFailsTimeoutResolutionClosedThroughRealSqlite() = runTest {
    val db = freshDb()
    seedMatch(db, "m1", fastRoundDoc())
    seedDealtHands(db)
    // Corrupt the acting seat's hand AFTER dealing — the resolver must fail
    // closed, never decide a trick on partial data, and never write.
    val stmt: dynamic = js("db.prepare('UPDATE hands SET data = ? WHERE match_id = ? AND round = ? AND seat_id = ?')")
    stmt.run("{corrupt", "m1", 14.0, "p1")
    val before = toJson(rawMatch(db))

    val adapter = DoTimeoutDb(sqlHandle(db))
    val error = assertFailsWith<AuthorityError> {
      resolveTimeoutOnce(
        adapter,
        "u3",
        json("matchId" to "m1"),
        DecisionClock { timeoutAnchorMillis + 20_000 },
      )
    }

    assertEquals("failed-precondition", error.code)
    assertEquals(before, toJson(rawMatch(db)), "a corrupt hand must leave the document untouched")
    assertNull(adapter.transact { tx -> tx.readTimeoutCount("m1", "u1") })
  }
}
