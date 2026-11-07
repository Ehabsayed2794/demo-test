package com.estemshan.functions

import com.estemshan.engine.rankLadder
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
 * The read hardening lives here too, in the same seam: the season read is
 * BOUNDED ([requireBoardWithinCap] refuses an oversized season before it is
 * worked through), the skip log names the season and the reason but NEVER a
 * player ([skipLine] is the only place a skip is reported), and every row
 * carries its rung's Arabic title as the ladder table holds it — not as the
 * profile's own private `rank` field claims it. Those three are `internal` to
 * the module, and this class calling them is what makes that visibility
 * sufficient rather than a claim in a comment.
 *
 * `HttpsError` throw codes are sampled exactly as in [SettlementTest] and
 * [DisconnectVoteFlowTest] — the code rides on the thrown object.
 */
class SeasonLeaderboardTest {

  private companion object {
    const val SEASON = "s1"

    /** The whole public contract of a row: nothing else may appear. */
    val ROW_KEYS = setOf(
      "position", "displayName", "avatarInitial", "tier", "tierArabic", "division", "rp", "seasonId",
      "isCurrentPlayer",
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

    /**
     * The port's `seasons/current` is absent: no document exists, so
     * [LeaderboardDb.currentSeasonId]'s own default — the bootstrap season —
     * is what the fake serves, exactly as the Admin port would.
     */
    val NO_SEASON_DOC = Any()
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

  private class FakeLeaderboardDb(
    private val profiles: List<StoredProfile>,
    private val currentSeasonDoc: Any? = NO_SEASON_DOC,
  ) : LeaderboardDb {
    var reads = 0
    var readSeasonId: String? = null
    var seasonReads = 0

    /**
     * The current-season read, as this scenario's S59 singleton answers:
     * no document at all → the interface's own default (the bootstrap), a
     * handed-over document → parsed by the SAME production helper the Admin
     * port calls — so a malformed singleton fails exactly as it does live,
     * and the count of this read is testable on every path.
     */
    override suspend fun currentSeasonId(): String {
      seasonReads++
      if (currentSeasonDoc == NO_SEASON_DOC) return super<LeaderboardDb>.currentSeasonId()
      return currentSeasonIdOf(exists = true, data = dynamicToKotlin(currentSeasonDoc))
    }

    override suspend fun readSeasonProfiles(seasonId: String): List<StoredProfile> {
      reads++
      readSeasonId = seasonId
      return profiles
    }
  }

  /**
   * A profile's stored fields that count every read of a value out of them.
   *
   * [classify] asks the map for the season, the rung and the RP, so a counter
   * that stays at zero after a refusal is the mechanical proof that the cap
   * check ran on the list's `size` alone and never opened a document. Move the
   * cap below the classification loop and this counter moves with it.
   */
  private class ProbeFields(private val source: Map<String, Any?>) : Map<String, Any?> by source {
    var reads = 0
      private set

    override fun get(key: String): Any? {
      reads++
      return source[key]
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
    currentSeasonDoc: Any? = NO_SEASON_DOC,
  ): Pair<FakeLeaderboardDb, Map<String, Any?>> {
    val db = FakeLeaderboardDb(profiles, currentSeasonDoc)
    return db to leaderboardOnce(db, fakeRequest(uid, seasonId, limit))
  }

  @Suppress("UNCHECKED_CAST")
  private fun rows(response: Map<String, Any?>): List<Map<String, Any?>> =
    response["rows"] as List<Map<String, Any?>>

  @Suppress("UNCHECKED_CAST")
  private fun you(response: Map<String, Any?>): Map<String, Any?>? = response["you"] as Map<String, Any?>?

  /**
   * Everything `console.warn` receives while [block] runs, restored afterwards.
   *
   * The shim is self-contained JS on purpose: production calls `console.warn`
   * directly, so this must replace the same global it reaches, not some Kotlin
   * wrapper around it. The restore lives in a `finally` so a failing assertion
   * cannot leave the rest of the suite capturing warnings.
   */
  private suspend fun warningsWhile(block: suspend () -> Unit): List<String> {
    js("globalThis.__s64Warnings = []; globalThis.__s64OriginalWarn = console.warn; console.warn = function (m) { globalThis.__s64Warnings.push(String(m)); }")
    try {
      block()
    } finally {
      js("console.warn = globalThis.__s64OriginalWarn")
    }
    return js("globalThis.__s64Warnings").unsafeCast<Array<String>>().toList()
  }

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

  // ── which season is live: the server's current-season read ─────────

  @Test
  fun anAbsentOrExplicitlyNullSeasonReadsTheCurrentSeasonOnceAndServesIt() = runTest {
    // The server's own document says s7 — an unnamed request must mean s7's
    // board, decided by exactly ONE current-season read, and the response
    // echoes what was served so a caller is never shown one board while
    // believing another.
    val (absentDb, absentResponse) = called(
      listOf(profile("p1", seasonId = "s7")),
      seasonId = ABSENT,
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s7"),
    )
    assertEquals(1, absentDb.seasonReads)
    assertEquals(1, absentDb.reads, "the season read happened once, then the named season's profiles were read")
    assertEquals("s7", absentDb.readSeasonId)
    assertEquals("s7", absentResponse["seasonId"])
    assertEquals(listOf("Name-p1"), names(absentResponse))

    // Explicit null arrives as the same request shape over JSON (undefined is
    // dropped on the way in), so it takes the same path — one season read, and
    // no board outside the resolved season.
    val (nullDb, nullResponse) = called(
      listOf(profile("p1", seasonId = "s7"), profile("stale", seasonId = "s1")),
      seasonId = null,
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s7"),
    )
    assertEquals(1, nullDb.seasonReads)
    assertEquals(1, nullDb.reads)
    assertEquals("s7", nullDb.readSeasonId)
    assertEquals("s7", nullResponse["seasonId"])
    assertEquals(listOf("Name-p1"), names(nullResponse))
    assertEquals(1, nullResponse["skipped"], "the s1 document is not s7's board, even as a leftover")
  }

  @Test
  fun anExplicitSeasonServesTheNamedBoardAndNeverOpensTheCurrentSeasonDocument() = runTest {
    // The server's current season is s7, and the caller asked for s2 BY NAME.
    // A named season is a request for that historical board, not a question
    // to re-decide: zero current-season reads, and the s2 board itself.
    val (db, response) = called(
      listOf(
        profile("s2-king", tier = "King", division = "", rp = 3_200, seasonId = "s2"),
        profile("s2-gold", tier = "Gold", division = "I", rp = 900, seasonId = "s2"),
      ),
      uid = "s2-king",
      seasonId = "s2",
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s7"),
    )

    assertEquals(0, db.seasonReads, "an explicit season is a local validation, never a singleton read")
    assertEquals(1, db.reads)
    assertEquals("s2", db.readSeasonId)
    assertEquals("s2", response["seasonId"])
    assertEquals(listOf("Name-s2-king", "Name-s2-gold"), names(response))
    assertEquals(listOf(1, 2), positions(response))
    // The highlight rides the served board's own rows, not the current season.
    assertEquals(true, rows(response).first()["isCurrentPlayer"])

    // Defense-in-depth pin: the same guarantee holds for a CORRUPT singleton —
    // an eager current-season read would throw failed-precondition here instead
    // of serving the named board, on top of the zero counter above.
    val (brokenDb, brokenResponse) = called(
      listOf(profile("s2-gold", seasonId = "s2", tier = "Gold", division = "I", rp = 900)),
      uid = "s2-gold",
      seasonId = "s2",
      currentSeasonDoc = linkedMapOf<String, Any?>(),
    )
    assertEquals("s2", brokenResponse["seasonId"])
    assertEquals(0, brokenDb.seasonReads)
  }

  @Test
  fun aPresentButUnusableSeasonCostsZeroCurrentSeasonReadsAndZeroProfileReads() = runTest {
    // A document exists and COULD have been opened; proving it was not is the
    // point: a named-but-unusable season is an argument denial, decided
    // locally before either read runs.
    val db = FakeLeaderboardDb(ladder(), currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s7"))
    for (season in listOf<Any?>("", "   ", 5, true)) {
      assertEquals(
        "invalid-argument",
        thrownCode { leaderboardOnce(db, fakeRequest("caller", seasonId = season)) },
      )
    }
    assertEquals(0, db.seasonReads)
    assertEquals(0, db.reads)
  }

  @Test
  fun aRejectedLimitCostsZeroCurrentSeasonReadsAndZeroProfileReads() = runTest {
    // Absent season: the limit is refused BEFORE the current-season document
    // would be opened to resolve it.
    val absentSeason = FakeLeaderboardDb(ladder(), currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s7"))
    assertEquals(
      "invalid-argument",
      thrownCode { leaderboardOnce(absentSeason, fakeRequest("caller", seasonId = ABSENT, limit = 0)) },
    )
    assertEquals(0, absentSeason.seasonReads)
    assertEquals(0, absentSeason.reads)

    // Explicit valid season: no singleton read is owed anyway, and the board
    // must not be opened for an argument that never reaches it.
    val namedSeason = FakeLeaderboardDb(ladder())
    assertEquals(
      "invalid-argument",
      thrownCode { leaderboardOnce(namedSeason, fakeRequest("caller", limit = 101)) },
    )
    assertEquals(0, namedSeason.seasonReads)
    assertEquals(0, namedSeason.reads)
  }

  @Test
  fun aCurrentSeasonDocumentWithoutAUsableSeasonIdFailsBeforeAnyProfileRead() = runTest {
    // An existing document is authoritative — including authoritatively
    // unreadable. A blank, non-string or missing `seasonId` fails closed, and
    // no profile of any season is read past it.
    for (brokenDoc in listOf(
      linkedMapOf<String, Any?>(),
      linkedMapOf<String, Any?>("seasonId" to ""),
      linkedMapOf<String, Any?>("seasonId" to "   "),
      linkedMapOf<String, Any?>("seasonId" to 7),
    )) {
      val db = FakeLeaderboardDb(ladder(), currentSeasonDoc = brokenDoc)
      assertEquals(
        "failed-precondition",
        thrownCode { leaderboardOnce(db, fakeRequest("watcher", seasonId = ABSENT)) },
      )
      assertEquals(1, db.seasonReads, "the singleton WAS opened; failing closed is its read's job")
      assertEquals(0, db.reads, "no profile is read for a season the server cannot identify")
    }
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

  @Test
  fun aSkipLineCarriesTheSeasonAndTheReasonAndNeverAPlayer() {
    val line = skipLine("s9", "no-ladder-rung")

    assertEquals("S64_LEADERBOARD_SKIP seasonId=s9 reason=no-ladder-rung", line)
    // The count above proves the board still reports its skips; this proves the
    // line reports nothing else. Every id this class puts in front of the board
    // is swept, because [skipLine] is the only place a skip is written — and
    // a skipped profile's document id IS its uid, so a line carrying it would
    // be a record of who was refused, built from the one read that is supposed
    // to expose nothing.
    for (uid in listOf(
      "p1", "p2", "p3", "p4", "p5", "good", "stale", "no-rung", "negative-rp", "missing-tier",
      "uid-alpha", "uid-beta", "uid-gamma", "watcher",
    )) {
      assertTrue(uid !in line, "the skip log leaked '$uid'")
    }
  }

  @Test
  fun aMalformedProfileEmitsExactlyTheSkipLineForTheServedSeason() = runTest {
    val season = "season-emission-7"
    val leakedUid = "uid-hidden-42"
    val lines = warningsWhile {
      val (_, response) = called(
        listOf(
          profile("uid-boarded", tier = "Gold", division = "II", rp = 700, seasonId = season),
          profile(leakedUid, tier = "Mithril", division = "I", rp = 9_999, seasonId = season),
        ),
        uid = "watcher",
        seasonId = season,
      )
      assertEquals(1, response["skipped"])
      assertEquals(1, response["total"])
    }

    // The line the CALL actually emits, season and all: this is the output of
    // `console.warn` while [leaderboardOnce] ran, not a call to [skipLine] in
    // isolation. `lines.single()` is also the count proof — one skipped profile,
    // one line, no per-row chatter.
    val line = lines.single()
    assertEquals("S64_LEADERBOARD_SKIP seasonId=season-emission-7 reason=no-ladder-rung", line)
    for (uid in listOf(leakedUid, "uid-boarded", "watcher")) {
      assertTrue(uid !in line, "the emitted skip log leaked '$uid'")
    }
  }

  @Test
  fun aCleanBoardEmitsNoSkipWarningsAtAll() = runTest {
    val lines = warningsWhile {
      val (_, response) = called(ladder(), uid = "p2", limit = 100)
      assertEquals(0, response["skipped"])
      assertEquals(5, response["total"])
    }

    // Zero skips must mean zero log lines: a warn on a healthy board would be
    // its own false alarm and would drown the one signal the line exists for.
    assertEquals(emptyList(), lines)
  }

  // ── denials ─────────────────────────────────────────────────────────

  @Test
  fun anUnauthenticatedRequestIsDeniedBeforeTheRead() = runTest {
    val db = FakeLeaderboardDb(ladder())

    // The season is OMITTED, not named: a named season costs no season read
    // under any implementation, so only the absent shape can witness
    // auth-before-resolve. With auth checked first, no singleton read happens;
    // with resolve-before-auth, exactly one does.
    assertEquals(
      "unauthenticated",
      thrownCode { leaderboardOnce(db, fakeRequest(uid = null, seasonId = ABSENT)) },
    )
    assertEquals(0, db.reads)
    assertEquals(0, db.seasonReads, "auth is checked before the season is resolved")
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

  // ── the read is bounded ─────────────────────────────────────────────

  @Test
  fun theCapHoldsAtItsCeilingAndRefusesTheFirstProfilePastIt() = runTest {
    requireBoardWithinCap(0, SEASON)
    requireBoardWithinCap(MAX_SEASON_PROFILES, SEASON)

    assertEquals("resource-exhausted", thrownCode { requireBoardWithinCap(MAX_SEASON_PROFILES + 1, SEASON) })
  }

  @Test
  fun anOversizedSeasonIsRefusedOnTheCountBeforeAnyProfileIsProcessed() = runTest {
    val fields = ProbeFields(
      linkedMapOf<String, Any?>(
        "seasonId" to SEASON, "tier" to "King", "division" to "", "rp" to 3_200,
        "displayName" to "Over Cap", "avatarInitial" to "O",
      ),
    )
    val db = FakeLeaderboardDb(List(MAX_SEASON_PROFILES + 1) { StoredProfile("uid-over-cap", fields) })

    // No board, no rows, no partial answer: the call throws instead.
    assertEquals("resource-exhausted", thrownCode { leaderboardOnce(db, fakeRequest("watcher")) })
    assertEquals(1, db.reads, "the bound costs one read, never a probe and then a second query")
    assertEquals(0, fields.reads, "not one profile was opened before the refusal")

    // Positive control on the same fields: let the board classify one document
    // and the counter has to move. Without this, the zero above would read the
    // same whether the cap refused early or the probe simply never counted.
    leaderboardOnce(FakeLeaderboardDb(listOf(StoredProfile("uid-over-cap", fields))), fakeRequest("watcher"))
    assertTrue(fields.reads > 0, "the probe counted nothing even when classification ran")
  }

  @Test
  fun aSeasonExactlyAtTheCapBoardsWholeRatherThanBeingCut() = runTest {
    // One distinct document per slot: a single shared profile repeated 10,000
    // times would let a board that collapsed duplicate ids still report 10,000.
    val (db, response) = called(List(MAX_SEASON_PROFILES) { profile("uid-at-cap-$it") })

    // The cap refuses over its ceiling and never truncates at it: at exactly
    // MAX the answer is a whole board, still paged by the caller's limit.
    assertEquals(MAX_SEASON_PROFILES, response["total"])
    assertEquals(0, response["skipped"])
    assertEquals(100, rows(response).size)
    assertEquals(100, rows(response).map { it["displayName"] }.distinct().size, "the at-cap fixture repeats ids")
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

  @Test
  fun everyRowCarriesItsRungArabicTitleAndItIsTheLadderTablesOwn() = runTest {
    val (_, response) = called(ladder(), limit = 100)

    // Five rungs, five titles, in board order: King, Royal I, Gold I, Silver I,
    // Bronze III. The title is a display value of the public ladder, so it
    // travels with the tier it names and never replaces it.
    assertEquals(listOf("ملك", "سلطان", "معلم", "لاعب", "مبتدئ"), rows(response).map { it["tierArabic"] })
    for (row in rows(response)) {
      assertEquals(ROW_KEYS, row.keys)
      assertTrue((row["tierArabic"] as? String)?.isNotBlank() == true, "a row reached the board with no title")
      // Cross-checked against the single rankLadder table rather than a literal
      // repeated here: a second copy of the names would agree with this test and
      // still drift from the ladder.
      assertEquals(
        rankLadder.first { it.tier == row["tier"] && it.division == row["division"] }.arabic,
        row["tierArabic"],
        "the title must be the resolved rung's, read from the one ladder table",
      )
    }
  }

  @Test
  fun allSevenTierTitlesArePinnedAndCrossCheckedAgainstTheOneLadderTable() = runTest {
    // One distinct rung per tier, taken from the table itself: the fixture
    // cannot claim a (tier, division) pair the ladder does not hold. The list is
    // in board order (highest rung first), and all seven titles are stated as
    // literals, so the two the five-rung fixture above never reaches —
    // Platinum's بلاتين and Diamond's أمير — are pinned here.
    val expected = listOf(
      "King" to "ملك",
      "Royal" to "سلطان",
      "Diamond" to "أمير",
      "Platinum" to "بلاتين",
      "Gold" to "معلم",
      "Silver" to "لاعب",
      "Bronze" to "مبتدئ",
    )
    val rungs = expected.map { (tier, _) -> rankLadder.first { it.tier == tier } }
    val (_, response) = called(
      rungs.mapIndexed { index, rung ->
        profile("uid-tier-$index", tier = rung.tier, division = rung.division, rp = rung.lowerBound)
      },
      limit = 100,
    )

    // Seven tiers, seven rows: the board's descending rung order and each row's
    // own public title.
    assertEquals(expected.map { it.first }, rows(response).map { it["tier"] })
    assertEquals(expected.map { it.second }, rows(response).map { it["tierArabic"] })
    for (row in rows(response)) {
      // Cross-checked against the single rankLadder table rather than a second
      // copy of the names: a literal above that drifts from the ladder turns
      // this red even if the literal assertion itself were edited to match.
      assertEquals(
        rankLadder.first { it.tier == row["tier"] && it.division == row["division"] }.arabic,
        row["tierArabic"],
        "the title must be the resolved rung's, read from the one ladder table",
      )
    }
  }

  @Test
  fun goldBoardsMulemAndKingMalakAndNeitherComesFromTheStoredRank() = runTest {
    val (_, response) = called(ladder(), uid = "p3", limit = 1)

    // Every fixture profile stores a private `rank` of King. A title read from
    // that field would say ملك on the Gold row, so these two literals are also
    // the proof the row was built from the resolved rung and not the profile.
    assertEquals("King", rows(response).first()["tier"])
    assertEquals("ملك", rows(response).first()["tierArabic"])
    assertEquals("Gold", you(response)?.get("tier"))
    assertEquals("معلم", you(response)?.get("tierArabic"))
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
