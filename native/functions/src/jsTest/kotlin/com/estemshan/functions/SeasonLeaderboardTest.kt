package com.estemshan.functions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S64 — the seasonal leaderboard as the callable runs it (RD27, roadmap §4b.8).
 *
 * [LeaderboardTest] pins the ordering math on the JVM; this pins the server's
 * READ around it: one season's `players` profiles in, already-positioned
 * display-safe rows out, the caller's own row whether or not the top limit
 * reaches it, and a malformed profile skipped rather than ranked.
 *
 * The seam is a fake [LeaderboardDb], so everything asserted here is what
 * `getRankedLeaderboard` decides for itself — never what Firestore would have
 * filtered. That distinction matters for season isolation in particular: the
 * real query filters server-side, and the port is what makes [leaderboardOnce]
 * prove it still refuses to blend a stale document into the board.
 *
 * The seam cuts both ways, and [SeasonLeaderboardEmulatorTest] exists because
 * of it: a fake port proves the logic around the query but can never prove the
 * query itself, so the Admin-SDK call shape is pinned there, against a real
 * emulator.
 *
 * `HttpsError` throw codes are sampled exactly as in [SettlementTest] and
 * [DisconnectVoteFlowTest] — the code rides on the thrown object.
 */
class SeasonLeaderboardTest {

  private companion object {
    const val SEASON = "s1"

    /** The whole public contract of a row: nothing else may appear. */
    val ROW_KEYS = setOf(
      "position", "displayName", "avatarInitial", "tier", "division", "rp", "seasonId", "isCurrentPlayer",
    )
    val RESPONSE_KEYS = setOf("ok", "seasonId", "rows", "you", "total", "skipped")

    /** Profile fields that are nobody's business but the owner's. */
    val PRIVATE_KEYS = listOf(
      "playerId", "uid", "email", "coins", "gems", "rank", "highestRank", "wins", "streak", "level",
      "placementState", "demotionShield", "lastSeenAt", "currentRoomId", "currentMatchId", "accountType",
      "createdAt", "token",
    )

    /** A request argument the caller did not send at all — distinct from sending `null`. */
    val ABSENT = Any()
  }

  /**
   * A profile carrying the real `players/{uid}` shape: the four Ranked fields
   * this read consumes, the two display fields, and every private field that
   * must NOT reach a row — present on purpose, so a projection that leaks one
   * fails a test instead of passing by accident.
   */
  private fun profile(
    playerId: String,
    tier: Any? = "Gold",
    division: Any? = "II",
    rp: Any? = 700,
    seasonId: Any? = SEASON,
    displayName: Any? = "Name-$playerId",
    avatarInitial: Any? = "I-$playerId",
  ): StoredProfile = StoredProfile(
    playerId = playerId,
    fields = linkedMapOf<String, Any?>(
      "tier" to tier,
      "division" to division,
      "rp" to rp,
      "seasonId" to seasonId,
      "displayName" to displayName,
      "avatarInitial" to avatarInitial,
      "email" to "$playerId@estemshan.test",
      "coins" to 5_000,
      "gems" to 12,
      "rank" to "King",
      "highestRank" to linkedMapOf<String, Any?>("tier" to "King", "division" to ""),
      "wins" to 40,
      "streak" to 3,
      "level" to 9,
      "placementState" to "COMPLETE",
      "demotionShield" to true,
      "lastSeenAt" to 1_700_000_000_000L,
      "currentRoomId" to "room-7",
      "currentMatchId" to "match-7",
      "accountType" to "HUMAN",
      "createdAt" to 1_600_000_000_000L,
    ),
  )

  /** Five players, each on a distinct rung: no ties, so positions are 1 to 5 in this order. */
  private fun ladder(): List<StoredProfile> = listOf(
    profile("p1", tier = "King", division = "", rp = 3_200),
    profile("p2", tier = "Royal", division = "I", rp = 2_800),
    profile("p3", tier = "Gold", division = "I", rp = 900),
    profile("p4", tier = "Silver", division = "I", rp = 460),
    profile("p5", tier = "Bronze", division = "III", rp = 10),
  )

  private class FakeLeaderboardDb(private val profiles: List<StoredProfile>) : LeaderboardDb {
    var reads = 0
    var readSeasonId: String? = null

    override suspend fun readSeasonProfiles(seasonId: String): List<StoredProfile> {
      reads++
      readSeasonId = seasonId
      return profiles
    }
  }

  /**
   * The payload as the runtime really delivers it: a plain JS object in which a
   * key the caller did not send is genuinely ABSENT, not present-and-null. The
   * default-season rule turns on exactly that difference, so [kotlin.js.json] —
   * which always writes the key — cannot express the request.
   */
  private fun plainPayload(seasonId: Any?, limit: Any?): Any? {
    val data: dynamic = js("({})")
    if (seasonId !== ABSENT) data.seasonId = seasonId
    if (limit !== ABSENT) data.limit = limit
    return data
  }

  private fun fakeRequest(
    uid: String?,
    seasonId: Any? = SEASON,
    limit: Any? = null,
  ): CallableRequest = object : CallableRequest {
    // The runtime delivers data as a PLAIN JS OBJECT, never a Kotlin map.
    override val data: Any? = plainPayload(seasonId, limit)
    override val auth: AuthData? = uid?.let {
      object : AuthData {
        override val uid: String = it
        override val token: AuthToken? = null
      }
    }
    override val instanceIdToken: String? = null
    override val rawRequest: Any? = null
    override val app: Any? = null
  }

  private suspend fun called(
    profiles: List<StoredProfile>,
    uid: String? = "watcher",
    seasonId: Any? = SEASON,
    limit: Any? = null,
  ): Pair<FakeLeaderboardDb, Map<String, Any?>> {
    val db = FakeLeaderboardDb(profiles)
    return db to leaderboardOnce(db, fakeRequest(uid, seasonId, limit))
  }

  @Suppress("UNCHECKED_CAST")
  private fun rows(response: Map<String, Any?>): List<Map<String, Any?>> =
    response["rows"] as List<Map<String, Any?>>

  @Suppress("UNCHECKED_CAST")
  private fun you(response: Map<String, Any?>): Map<String, Any?>? = response["you"] as Map<String, Any?>?

  private fun names(response: Map<String, Any?>): List<Any?> = rows(response).map { it["displayName"] }

  private fun positions(response: Map<String, Any?>): List<Any?> = rows(response).map { it["position"] }

  /** The HttpsError code a call throws, sampled exactly like [SettlementTest]. */
  private suspend fun thrownCode(block: suspend () -> Any?): String {
    try {
      block()
    } catch (e: Throwable) {
      return e.asDynamic().code as String
    }
    throw AssertionError("expected HttpsError")
  }

  // ── ordering is the engine's, and the rows arrive already positioned ──

  @Test
  fun kingsOrderAmongThemselvesByRpAloneAndEveryRungBoardsAboveTheNext() = runTest {
    val (_, response) = called(
      listOf(
        profile("king-low", tier = "King", division = "", rp = 3_050),
        profile("king-high", tier = "King", division = "", rp = 3_200),
        profile("royal-one", tier = "Royal", division = "I", rp = 2_800),
        profile("gold-one", tier = "Gold", division = "I", rp = 900),
      ),
    )

    // King has no divisions, so RP is the only key there (RD3/RD27) — and both
    // Kings still sit above Royal I.
    assertEquals(listOf("Name-king-high", "Name-king-low", "Name-royal-one", "Name-gold-one"), names(response))
    assertEquals(listOf(1, 2, 3, 4), positions(response))
    assertEquals(listOf(3_200, 3_050, 2_800, 900), rows(response).map { it["rp"] })
    // The board renders King as one ordered group, never "King I": the rung's
    // own empty division is what carries that to the client.
    assertEquals(listOf("", "", "I", "I"), rows(response).map { it["division"] })
    assertEquals(listOf("King", "King", "Royal", "Gold"), rows(response).map { it["tier"] })
    assertEquals(4, response["total"])
    assertEquals(0, response["skipped"])
  }

  @Test
  fun tiedRungAndRpShareAPositionAndTheNextOneSkips() = runTest {
    val (_, response) = called(
      listOf(
        profile("a", tier = "Gold", division = "II", rp = 700),
        profile("b", tier = "Gold", division = "II", rp = 700),
        profile("c", tier = "Gold", division = "II", rp = 750),
        profile("d", tier = "Silver", division = "I", rp = 460),
      ),
    )

    // Competition ranking, RD17's rule on a board: 1, 2, 2, 4.
    assertEquals(listOf("Name-c", "Name-a", "Name-b", "Name-d"), names(response))
    assertEquals(listOf(1, 2, 2, 4), positions(response))
  }

  // ── season isolation ────────────────────────────────────────────────

  @Test
  fun aStaleProfileFromAnotherSeasonIsSkippedAndCannotEvenHighlight() = runTest {
    val (_, response) = called(
      listOf(
        profile("current", tier = "Gold", division = "I", rp = 900, seasonId = SEASON),
        profile("stale", tier = "King", division = "", rp = 3_200, seasonId = "s2"),
      ),
      uid = "stale",
    )

    // RD24: positions are recomputed per season, never blended. The stale
    // document would have topped this board, and the caller owns it — so the
    // caller's highlight has to go with it.
    assertEquals(listOf("Name-current"), names(response))
    assertEquals(listOf(1), positions(response))
    assertEquals(1, response["total"])
    assertEquals(1, response["skipped"])
    assertNull(you(response))
  }

  // ── the top limit, and the caller's row ─────────────────────────────

  @Test
  fun theLimitCutsTheRowsButTotalCountsTheWholeBoard() = runTest {
    val (db, response) = called(ladder(), limit = 3)

    assertEquals(listOf("Name-p1", "Name-p2", "Name-p3"), names(response))
    assertEquals(listOf(1, 2, 3), positions(response))
    assertEquals(5, response["total"], "total is the whole valid board, not the delivered slice")
    assertEquals(0, response["skipped"])
    assertNull(you(response))
    // One read, for the requested season only — the board is one query, not one
    // per row, and it never touches another collection.
    assertEquals(1, db.reads)
    assertEquals(SEASON, db.readSeasonId)
  }

  @Test
  fun theCallerInsideTheLimitIsHighlightedAndRepeatedAsYou() = runTest {
    val (_, response) = called(ladder(), uid = "p2", limit = 3)

    assertEquals(1, rows(response).count { it["isCurrentPlayer"] == true })
    assertEquals(true, rows(response)[1]["isCurrentPlayer"])
    // Same board row, delivered twice on purpose: once in the list everyone
    // scrolls, once as the caller's own anchor.
    assertEquals(rows(response)[1], you(response))
    assertEquals(2, you(response)?.get("position"))
  }

  @Test
  fun theCallerOutsideTheLimitGetsTheirFullBoardPosition() = runTest {
    val (_, response) = called(ladder(), uid = "p5", limit = 3)

    // The reason a player scrolls, made unnecessary: the row is the SERVER's
    // full-board position, so a truncated payload cannot flatten the board.
    assertEquals(3, rows(response).size)
    assertTrue(rows(response).none { it["isCurrentPlayer"] == true })
    val youRow = you(response) ?: throw AssertionError("the caller is on the board")
    assertEquals(5, youRow["position"])
    assertEquals("Name-p5", youRow["displayName"])
    assertEquals("Bronze", youRow["tier"])
    assertEquals(true, youRow["isCurrentPlayer"])
    assertEquals(5, response["total"])
  }

  @Test
  fun aLimitOfOneAndOfOneHundredAreBothAccepted() = runTest {
    val (_, one) = called(ladder(), limit = 1)
    val (_, hundred) = called(ladder(), limit = 100)

    assertEquals(listOf("Name-p1"), names(one))
    assertEquals(listOf(1), positions(one))
    assertEquals(5, one["total"], "a limit of 1 still reports the whole board")
    assertEquals(5, rows(hundred).size)
    assertEquals(5, hundred["total"])
  }

  @Test
  fun anEmptySeasonAnswersWithNothingRatherThanAnError() = runTest {
    val (_, response) = called(emptyList())

    // A fresh season after a reset has no board yet (RD24) — that is an empty
    // answer, not a failure, and never an invented row.
    assertEquals(emptyList<Map<String, Any?>>(), rows(response))
    assertNull(you(response))
    assertEquals(0, response["total"])
    assertEquals(0, response["skipped"])
    assertEquals(true, response["ok"])
    assertEquals(SEASON, response["seasonId"])
  }

  // ── malformed profiles are skipped, never ranked ────────────────────

  @Test
  fun everyMalformedProfileIsSkippedAndTheRestOfTheBoardStillRanks() = runTest {
    val (_, response) = called(
      listOf(
        profile("good", tier = "Gold", division = "II", rp = 700),
        profile("no-rung", tier = "Mithril", division = "I", rp = 9_999),
        profile("king-with-division", tier = "King", division = "I", rp = 3_200),
        profile("negative-rp", tier = "Gold", division = "II", rp = -5),
        profile("string-rp", tier = "Gold", division = "II", rp = "700"),
        profile("missing-rp", tier = "Gold", division = "II", rp = null),
        profile("fraction-rp", tier = "Gold", division = "II", rp = 380.5),
        profile("missing-tier", tier = null, division = "II", rp = 700),
      ),
    )

    // Seven unusable rows, one board. A rank is never invented for a profile
    // that cannot resolve a rung or carry a RP.
    assertEquals(listOf("Name-good"), names(response))
    assertEquals(listOf(1), positions(response))
    assertEquals(1, response["total"])
    assertEquals(7, response["skipped"])
  }

  // ── denials ─────────────────────────────────────────────────────────

  @Test
  fun anUnauthenticatedRequestIsDeniedBeforeTheRead() = runTest {
    val db = FakeLeaderboardDb(ladder())

    assertEquals("unauthenticated", thrownCode { leaderboardOnce(db, fakeRequest(uid = null)) })
    assertEquals(0, db.reads)
  }

  @Test
  fun aSeasonThatIsPresentButUnusableIsRejectedBeforeTheRead() = runTest {
    val db = FakeLeaderboardDb(ladder())
    // Blank, wrong type, non-string scalar: all of them say "a specific season",
    // and none of them can be honoured — so they are refused, not defaulted.
    for (season in listOf("", "   ", 5, true, emptyList<Any>())) {
      assertEquals("invalid-argument", thrownCode { leaderboardOnce(db, fakeRequest("caller", seasonId = season)) })
    }
    assertEquals(0, db.reads)
  }

  @Test
  fun anOmittedSeasonResolvesToTheServersDefaultAndThatIsWhatIsRead() = runTest {
    val (db, response) = called(ladder(), seasonId = ABSENT)

    // Absent is a normal request shape, not a mistake — and which board that
    // means is decided here, not by the client. The response echoes the season
    // actually served, so a caller can never be shown s1 while believing s2.
    assertEquals(SEASON, db.readSeasonId)
    assertEquals(SEASON, response["seasonId"])
    assertEquals(1, db.reads)
    assertEquals(5, response["total"])

    // An explicit null arrives as the same request shape over JSON (undefined
    // is dropped on the way in), so it takes the same answer — and the server-
    // side season recheck still holds the board to it.
    val (nullDb, nullResponse) = called(
      listOf(profile("here", seasonId = SEASON), profile("elsewhere", tier = "King", division = "", seasonId = "s2")),
      seasonId = null,
    )
    assertEquals(SEASON, nullDb.readSeasonId)
    assertEquals(listOf("Name-here"), names(nullResponse))
    assertEquals(1, nullResponse["skipped"])
  }

  @Test
  fun aLimitOutsideOneToOneHundredIsRejected() = runTest {
    val db = FakeLeaderboardDb(ladder())
    for (limit in listOf(0, -1, 101, 1_000, "5", 2.5)) {
      assertEquals("invalid-argument", thrownCode { leaderboardOnce(db, fakeRequest("caller", limit = limit)) })
    }
    assertEquals(0, db.reads, "a rejected argument must cost no read")

    // Absent is not invalid: the board defaults to the full first page.
    val response = leaderboardOnce(db, fakeRequest("caller", limit = null))
    assertEquals(5, rows(response).size)
    assertEquals(1, db.reads)
  }

  // ── what a row is allowed to say ────────────────────────────────────

  /**
   * Profiles whose stored ids differ from every display value, so a sweep of
   * the serialized payload proves an id did not travel under some other key.
   */
  private fun privateShapes(): List<StoredProfile> = listOf(
    profile("uid-alpha", tier = "King", division = "", rp = 3_200, displayName = "Player One", avatarInitial = "O"),
    profile("uid-beta", tier = "Royal", division = "I", rp = 2_800, displayName = "Player Two", avatarInitial = "T"),
    profile("uid-gamma", tier = "Gold", division = "I", rp = 900, displayName = "Player Three", avatarInitial = "R"),
  )

  @Test
  fun aRowCarriesExactlyTheDisplaySafeFieldsAndNoIdentityOrCurrency() = runTest {
    val (_, response) = called(privateShapes(), uid = "uid-gamma", limit = 2)
    val youRow = you(response) ?: throw AssertionError("the caller is third on the board")

    assertEquals(RESPONSE_KEYS, response.keys)
    for (row in rows(response) + listOf(youRow)) {
      assertEquals(ROW_KEYS, row.keys)
      for (secret in PRIVATE_KEYS) {
        assertTrue(secret !in row, "a leaderboard row must not carry '$secret'")
      }
    }

    // Not just the key names: nothing private survives into the payload the
    // client actually receives — not a uid, not a balance, not an email, not
    // the room or match the caller is sitting in.
    val payload = rendered(response)
    for (leak in listOf(
      "uid-alpha", "uid-beta", "uid-gamma", "5000", "@estemshan.test", "room-7", "match-7", "COMPLETE",
    )) {
      assertTrue(leak !in payload, "the response leaks '$leak'")
    }
    // 2 delivered rows out of 3, and the caller's row below the cut.
    assertEquals(2, rows(response).size)
    assertEquals(3, youRow["position"])
  }

  /** Every key and value the response actually carries, flattened to one string. */
  private fun rendered(value: Any?): String = when (value) {
    is Map<*, *> -> value.entries.joinToString("|") { "${it.key}=${rendered(it.value)}" }
    is List<*> -> value.joinToString("|") { rendered(it) }
    null -> ""
    else -> value.toString()
  }

  @Test
  fun aMissingOrBlankDisplayNameFallsBackWithoutInventingOne() = runTest {
    val (_, response) = called(
      listOf(
        profile("p1", rp = 740, displayName = null, avatarInitial = "A"),
        profile("p2", rp = 720, displayName = "   ", avatarInitial = ""),
        profile("p3", rp = 700, displayName = "Ahmed", avatarInitial = 42),
      ),
    )

    // "Player" and "?" are the fallbacks, in both directions: a stored value is
    // passed through verbatim (no trimming, no capitalising, no first-letter
    // derivation from a name the server did not get).
    assertEquals(listOf("Player", "Player", "Ahmed"), rows(response).map { it["displayName"] })
    assertEquals(listOf("A", "?", "?"), rows(response).map { it["avatarInitial"] })
    assertEquals(3, response["total"])
    assertEquals(0, response["skipped"])
  }
}
