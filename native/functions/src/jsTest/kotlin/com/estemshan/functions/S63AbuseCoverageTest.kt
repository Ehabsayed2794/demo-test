package com.estemshan.functions

import kotlinx.coroutines.test.runTest
import kotlin.js.json
import com.estemshan.engine.rankAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S63 — abuse and edge-case coverage for the Ranked authority layer
 * (docs/NATIVE_V1_PLAN_AND_ESTIMATE.md, story S63, 12h).
 *
 * The story names four surfaces:
 *   A. Forged results (settlement) — the client's claim never supplies the
 *      result, the seat set, or the settler's identity. settleMatch derives
 *      everything from the match document and the archived rounds.
 *   B. Replayed settlements — a match settles once, replays return the first
 *      outcome, and no second correction/claim/profile write occurs.
 *   C. Cross-tier injection (read-side identity) — the read side never
 *      honours a client-supplied uid/playerId/tier/division/rp/position.
 *   D. Tie edge cases — the 4-player 1,2,2,4 tie stays in
 *      SeasonLeaderboardTest and the tied-top settlement stays in
 *      SettlementTest; a tie split by the page limit lives in Section D below.
 *   E. Numeric/argument abuse on the leaderboard — NaN, infinity, out-of-range
 *      and wrong-typed limit/seasonId are invalid-argument and cost zero reads.
 *
 * Every test is hermetic: plain JS-object payloads, in-memory fake ports,
 * no emulator, no network, no sleeps.
 *
 * Existing classes that already own adjacent angles (not duplicated here):
 *   - SettlementTest — golden recompute, forged-claim correction,
 *     tiedTopSettlesBothKings, missing/wrong seat-set rejection
 *   - IdempotencyGuardTest — the once-only guard's own semantics
 *   - SeasonLeaderboardTest — the 1,2,2,4 tie, season isolation, row shape,
 *     leaderboard limit and season validation
 *   - MatchmakingRequestTest — the forbidden tier/division/rank/rp/pool/optUp
 *     identity/tier keys on the search request
 *   - RankedProfileDbTest — the profile read/normalisation matrix
 *
 * The fakes below are local to this file and deliberately named S63* so they
 * cannot be confused with the fakes in SettlementTest or SeasonLeaderboardTest
 * when someone runs the whole suite.
 */

class S63AbuseCoverageTest {

  // ═══════════════════════════════════════════════════════════════════════════
  // Section A — Forged results (settlement)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * A fake [SettlementDb] that records every read and write so a test can
   * prove the payload never steers the settlement.
   */
  private class S63SettlementDb(
    private val match: Map<String, Any?>?,
    private val archives: Map<String, Map<String, Any?>> = emptyMap(),
    private val profiles: Map<String, Map<String, Any?>> = emptyMap(),
    var claim: Map<String, Any?>? = null,
  ) : SettlementDb {
    var archiveReads = 0
    var profileReads = 0
    var txRuns = 0
    var corrections = 0
    var claims = 0
    var profileWrites = 0
    var ledgerAppends = 0
    var lastClaimRecord: Map<String, Any?>? = null
    var lastCorrectionScores: Map<String, Int>? = null
    /** Every profile write, keyed by uid, so forged and constant values can be ruled out. */
    val profileWriteFields: MutableList<Pair<String, Map<String, Any?>>> = mutableListOf()

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = match

    override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
      archiveReads++
      return archives
    }

    override suspend fun appendLedger(record: Map<String, Any?>) {
      ledgerAppends++
    }

    override suspend fun readCorrectionWindow(): List<Map<String, Any?>> = emptyList()

    override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
      txRuns++
      return block(object : SettlementDb.SettlementTx {
        override suspend fun readClaim(matchId: String): Map<String, Any?>? = claim

        override suspend fun readProfile(uid: String): Map<String, Any?>? {
          profileReads++
          return profiles[uid]
        }

        override fun writeCorrection(
          matchId: String,
          scores: Map<String, Int>,
          winners: List<String>,
          version: Int,
        ) {
          corrections++
          lastCorrectionScores = scores
        }

        override fun writeProfile(uid: String, fields: Map<String, Any?>) {
          profileWrites++
          profileWriteFields += uid to fields
        }

        override fun writeClaim(matchId: String, record: Map<String, Any?>) {
          claims++
          lastClaimRecord = record
          claim = mapOf(
            "outcome" to record["outcome"],
            "settledBy" to record["settledBy"],
            "movements" to record["movements"],
          )
        }
      })
    }
  }

  /** A callable request whose [data] is a plain JS object carrying matchId. */
  private fun settleReq(uid: String?, matchId: Any?): CallableRequest = object : CallableRequest {
    override val data: Any? = json("matchId" to matchId)
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

  /** A callable request whose data is a plain JS object carrying matchId plus forged fields. */
  private fun forgedSettleReq(uid: String?, matchId: Any? = "m1"): CallableRequest = object : CallableRequest {
    override val data: Any? = json(
      "matchId" to matchId,
      "finalScores" to mapOf("p1" to 999, "p2" to 999, "p3" to 999, "p4" to 999),
      "winnerIds" to listOf("attacker"),
      "uid" to "attacker",
      "tier" to "King",
      "rp" to 9999,
      "position" to 1,
    )
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

  /** A forged request whose finalScores key bogus seats that don't exist in the match. */
  private fun forgedSettleReqWrongSeats(uid: String?, matchId: Any? = "m1"): CallableRequest = object : CallableRequest {
    override val data: Any? = json(
      "matchId" to matchId,
      "finalScores" to mapOf("seat-x" to 999, "seat-y" to 999, "seat-z" to 999),
      "winnerIds" to listOf("seat-x"),
      "uid" to "attacker",
    )
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

  @Suppress("UNCHECKED_CAST")
  private fun s63Response(response: Map<String, Any?>): Map<String, Any?> =
    response as Map<String, Any?>

  /** The golden round-1 archive, as the existing suite builds it. */
  private fun goldenArchives(): Map<String, Map<String, Any?>> =
    mapOf("1" to SettlementFixtures.round1ArchiveFields())

  /** A complete RANKED 1-round match whose claim equals the golden recompute. */
  private fun goldenMatch(uids: List<String> = listOf("u1", "u2", "u3", "u4")): Map<String, Any?> =
    SettlementFixtures.matchFields(uids)

  /** A valid seated profile that parses through parseRankedProfile without failing closed. */
  private fun validSeatedProfile(): Map<String, Any?> = linkedMapOf(
    "tier" to "Gold",
    "division" to "II",
    "rp" to 700,
    "seasonId" to "s1",
    "placementState" to "SKIPPED",
    "demotionShield" to false,
    "rankedGamesPlayed" to 0,
    "rankedKingCount" to 0,
    "rankedSecondCount" to 0,
    "rankedThirdCount" to 0,
    "rankedKozCount" to 0,
  )

  /** Profiles for all four seats (p1..p4 -> u1..u4). */
  private val allProfiles: Map<String, Map<String, Any?>> = mapOf(
    "u1" to validSeatedProfile(),
    "u2" to validSeatedProfile(),
    "u3" to validSeatedProfile(),
    "u4" to validSeatedProfile(),
  )

  @Test
  fun aForgedPayloadSettlesOnTheRecomputedValuesAndNeverEchoesForgedFields() = runTest {
    val db = S63SettlementDb(
      match = goldenMatch(),
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    // u1 is p1 (a seated player). The payload carries forged finalScores,
    // winnerIds, uid, tier, rp and position — none of them may steer anything.
    val response = s63Response(settleOnce(db, forgedSettleReq("u1")))

    assertEquals(true, response["settled"])
    // The recomputed golden values settle — never the forged 999s.
    assertEquals(
      mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14),
      response["finalScores"],
    )
    assertEquals(listOf("p1"), response["winnerIds"])
    // No forged field rides back in the response.
    assertFalse("attacker" in (response["winnerIds"] as List<*>))
    assertFalse((response["finalScores"] as Map<*, *>).values.contains(999))
    assertFalse((response["finalScores"] as Map<*, *>).values.contains(9999))
    // The claim matches the recompute, so no correction, one claim.
    assertEquals(0, db.corrections)
    assertEquals(1, db.claims)
    assertEquals(4, db.profileReads, "all four seated profiles were read before any write")
    assertEquals(4, db.profileWrites, "all four seated profiles were written")
    assertNull(db.lastCorrectionScores)
    // No forged field lands in any profile write: the writes carry progression
    // fields (rp/tier/division/demotionShield/...), never uid or position from
    // the forged payload, and never the forged values attacker/9999/King.
    // Each write carries the recomputed progression for its own seat.
    assertEquals(4, db.profileWriteFields.size, "every seated profile was written once")
    @Suppress("UNCHECKED_CAST")
    val movements = db.lastClaimRecord!!["movements"] as Map<String, Map<String, Any?>>
    val writes = db.profileWriteFields.toMap()
    assertEquals(setOf("u1", "u2", "u3", "u4"), writes.keys, "one write per seat")
    assertNotEquals(
      movements.getValue("u1"), movements.getValue("u2"),
      "the fixture must give the winning and losing seats different movements",
    )
    for ((uid, fields) in writes) {
      // No forged field lands in any profile write.
      assertFalse(fields.containsKey("uid"), "no write carries the forged uid key")
      assertFalse(fields.containsKey("position"), "no write carries the forged position key")
      assertFalse(fields.values.contains("attacker"), "no write carries the forged uid value")
      assertFalse(fields.values.contains(999), "no write carries the forged score value")
      assertFalse(fields.values.contains(9999), "no write carries the forged rp value")
      assertFalse(fields["tier"] == "King", "no write carries the forged tier value")
      // The write carries the recomputed progression, seat by seat.
      val movement = movements.getValue(uid)
      val rp = (movement["rp"] as Number).toInt()
      assertEquals(rp, fields["rp"], "the write for $uid carries the recomputed rp")
      assertEquals(
        (movement["previousRp"] as Number).toInt() + (movement["delta"] as Number).toInt(),
        rp,
        "the movement for $uid is internally consistent",
      )
      assertEquals(rankAt(rp).tier, fields["tier"], "the write for $uid carries the recomputed tier")
      assertEquals(rankAt(rp).division, fields["division"], "the write for $uid carries the recomputed division")
    }
  }

  @Test
  fun theClaimRecordsTheAuthenticatedUidNotTheForgedPayloadUid() = runTest {
    val db = S63SettlementDb(
      match = goldenMatch(),
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    settleOnce(db, forgedSettleReq("u1"))

    // settledBy is u1 (the authenticated caller), never "attacker" from the payload.
    assertNotNull(db.lastClaimRecord)
    assertEquals("u1", db.lastClaimRecord!!["settledBy"])
    assertFalse("attacker" == db.lastClaimRecord!!["settledBy"])
    // The claim outcome carries the recomputed values, not the forged ones.
    @Suppress("UNCHECKED_CAST")
    val outcome = db.lastClaimRecord!!["outcome"] as Map<String, Any?>
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), outcome["finalScores"])
  }

  @Test
  fun aForgedFinalScoresMapThatCoversTheWrongSeatsIsIgnoredBecauseTheDocumentSuppliesTheSeatSet()
    = runTest {
    // The document's claimedScores cover exactly the four match seats.
    val match = goldenMatch().toMutableMap()
    match["finalScores"] = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14)
    val db = S63SettlementDb(
      match = match,
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    // The payload's forged finalScores cover completely different seats — they
    // must not become the seat set, because the payload never supplies it.
    val response = s63Response(settleOnce(db, forgedSettleReqWrongSeats("u1")))

    assertEquals(true, response["settled"])
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), response["finalScores"])
    assertFalse((response["finalScores"] as Map<*, *>).keys.contains("seat-x"))
    assertFalse((response["finalScores"] as Map<*, *>).keys.contains("seat-y"))
    assertFalse((response["finalScores"] as Map<*, *>).keys.contains("seat-z"))
    // No bogus seat appears in winners either.
    assertFalse((response["winnerIds"] as List<*>).contains("seat-x"))
    assertFalse((response["winnerIds"] as List<*>).contains("seat-y"))
    assertFalse((response["winnerIds"] as List<*>).contains("seat-z"))
  }

  @Test
  fun aNonStringMatchIdIsInvalidArgumentBeforeAnyRead() = runTest {
    val db = S63SettlementDb(
      match = goldenMatch(),
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    val error = assertFailsWith<Throwable> {
      settleOnce(db, settleReq("u1", 123))
    }
    assertEquals("invalid-argument", error.asDynamic().code as String)
    // No read happened — the matchId check precedes readMatch.
    assertEquals(0, db.archiveReads)
    assertEquals(0, db.profileReads)
    assertEquals(0, db.txRuns)
  }

  @Test
  fun aMissingMatchIdIsInvalidArgumentBeforeAnyRead() = runTest {
    val db = S63SettlementDb(
      match = goldenMatch(),
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    val error = assertFailsWith<Throwable> {
      settleOnce(db, settleReq("u1", null))
    }
    assertEquals("invalid-argument", error.asDynamic().code as String)
    assertEquals(0, db.archiveReads)
    assertEquals(0, db.profileReads)
    assertEquals(0, db.txRuns)
  }

  @Test
  fun anAbsentMatchIdKeyIsInvalidArgumentBeforeAnyRead() = runTest {
    val db = S63SettlementDb(
      match = goldenMatch(),
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    val request = object : CallableRequest {
      override val data: Any? = js("({})")
      override val auth: AuthData? = object : AuthData {
        override val uid: String = "u1"
        override val token: AuthToken? = null
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }
    val error = assertFailsWith<Throwable> { settleOnce(db, request) }
    assertEquals("invalid-argument", error.asDynamic().code as String)
    assertEquals(0, db.archiveReads)
    assertEquals(0, db.profileReads)
    assertEquals(0, db.txRuns)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Section B — Replayed settlements
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  fun aReplayedSettlementReturnsTheFirstOutcomeAndWritesNothing() = runTest {
    val db = S63SettlementDb(
      match = goldenMatch(),
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    // First settle by u1: one claim, one outcome, no correction (claim matches).
    val first = s63Response(settleOnce(db, settleReq("u1", "m1")))
    assertEquals(true, first["settled"])
    assertEquals(false, first["replayed"])
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), first["finalScores"])
    assertEquals(1, db.claims)
    assertEquals(0, db.corrections)
    assertEquals(4, db.profileReads, "all four profiles read on first settlement")

    // Second call: a different caller (u2) with a completely forged payload.
    // It must replay the first outcome and write nothing.
    val replay = s63Response(settleOnce(db, forgedSettleReq("u2", "m1")))
    assertEquals(true, replay["replayed"], "a redelivered call must report replayed")
    assertEquals(first["finalScores"], replay["finalScores"], "the replay returns the first outcome")
    assertEquals(first["winnerIds"], replay["winnerIds"], "the first caller's winnerIds stand")
    // The forged payload changed nothing: no second correction, no second claim,
    // no second profile write. The golden claim matches the recompute, so no
    // correction was ever written — on either call.
    assertEquals(0, db.corrections, "no correction write on replay — claim matched the recompute")
    assertEquals(1, db.claims, "no second claim write on replay")
    assertEquals(4, db.profileReads, "no profile reads on replay — the claim check returns before any profile read")
    assertEquals(4, db.profileWrites, "no second profile write on replay")
    assertEquals(2, db.txRuns, "the transaction still ran once per call")
  }

  @Test
  fun aSecondCallerSeesTheRecordedOutcomeNotTheirForgedPayload() = runTest {
    val db = S63SettlementDb(
      match = goldenMatch(),
      archives = goldenArchives(),
      profiles = allProfiles,
    )

    settleOnce(db, settleReq("u1", "m1"))

    // u2 replays with forged winnerIds, finalScores, uid — none of it lands.
    val replay = s63Response(settleOnce(db, forgedSettleReq("u2", "m1")))

    assertEquals(true, replay["replayed"])
    // The recorded outcome is u1's recomputed result, not u2's forged one.
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), replay["finalScores"])
    assertEquals(listOf("p1"), replay["winnerIds"])
    assertFalse("attacker" in (replay["winnerIds"] as List<*>))
    // The claim record still bears u1 as settledBy — u2 never overwrote it.
    assertEquals("u1", db.lastClaimRecord!!["settledBy"])
    assertFalse("u2" == db.lastClaimRecord!!["settledBy"])
    // u2 sees their OWN movement slice, not u1's, and no forged values.
    @Suppress("UNCHECKED_CAST")
    val movements = db.lastClaimRecord!!["movements"] as Map<String, Map<String, Any?>>
    assertNotEquals(
      movements.getValue("u1"), movements.getValue("u2"),
      "the fixture must give the two callers different movements",
    )
    val you = replay["you"] as? Map<String, Any?>
    assertNotNull(you, "a replay must return this caller's own movement slice")
    assertEquals(
      movements.getValue("u2"), you,
      "the replay returns u2's own recorded movement, not u1's",
    )
    assertFalse(you.values.contains("attacker"))
    assertFalse(you.values.contains(9999))
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Section C — Cross-tier injection (read-side identity)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * A fake [RankedProfileDb] that records the uid [Tx.read] was asked to read
   * and whether [Tx.merge] ran — so a test can prove a client-supplied uid is
   * never read, merged, or returned.
   */
  private class S63RankedProfileDb(var doc: Map<String, Any?>?) : RankedProfileDb {
    var txRuns = 0
      private set
    var readUid: String? = null
      private set
    var mergeUid: String? = null
    var merges = 0
      private set

    override suspend fun <T> transact(block: suspend (RankedProfileDb.Tx) -> T): T {
      txRuns++
      return block(object : RankedProfileDb.Tx {
        override suspend fun read(uid: String): Map<String, Any?>? {
          readUid = uid
          return doc
        }

        override fun merge(uid: String, fields: Map<String, Any?>) {
          merges++
          mergeUid = uid
        }
      })
    }
  }

  /** A callable request for getMyRankedProfile: auth only, data carries a forged uid. */
  private fun profileReq(authUid: String?, forgedData: Map<String, Any?>? = null): CallableRequest =
    object : CallableRequest {
      override val data: Any? = if (forgedData == null) null else json(
        *forgedData.map { (k, v) -> k to v }.toTypedArray()
      )
      override val auth: AuthData? = authUid?.let {
        object : AuthData {
          override val uid: String = it
          override val token: AuthToken? = null
        }
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }

  /** A complete profile with every Ranked field present, so loadOnce never merges. */
  private fun aliceProfile(): Map<String, Any?> = linkedMapOf(
    "uid" to "alice",
    "displayName" to "Alice",
    "avatarInitial" to "A",
    "tier" to "Gold",
    "division" to "II",
    "rp" to 700,
    "seasonId" to "s1",
    "highestRankTier" to "Gold",
    "highestRankDivision" to "II",
    "placementState" to "SKIPPED",
    "demotionShield" to false,
    "rankedGamesPlayed" to 0,
    "rankedKingCount" to 0,
    "rankedSecondCount" to 0,
    "rankedThirdCount" to 0,
    "rankedKozCount" to 0,
  )

  @Test
  fun getMyRankedProfileReadsTheAuthenticatedUidNotThePayloadUid() = runTest {
    val db = S63RankedProfileDb(aliceProfile())

    // Authenticate as alice but send bob's uid and playerId in the payload.
    val response = loadOnce(db, profileReq("alice", mapOf("uid" to "bob", "playerId" to "bob")))

    assertEquals(true, response["ok"])
    // Tx.read received alice (from auth), never bob (from the payload).
    assertEquals("alice", db.readUid)
    assertFalse("bob" == db.readUid)
    // No merge happened (the profile was already complete).
    assertEquals(0, db.merges)
    assertNull(db.mergeUid)
    // The response carries alice's profile, not bob's.
    @Suppress("UNCHECKED_CAST")
    val profile = response["profile"] as Map<String, Any?>
    assertEquals("Gold", profile["tier"])
    assertEquals("II", profile["division"])
    assertEquals(700, profile["rp"])
    // bob never appears anywhere in the response.
    assertFalse(response.toString().contains("bob"))
  }

  /**
   * A fake [LeaderboardDb] that counts profile reads and names the season it
   * was asked to read — the same pattern as the settlement fake, but for the
   * leaderboard read path.
   */
  private class S63LeaderboardDb(private val profiles: List<StoredProfile>) : LeaderboardDb {
    var reads = 0
    var readSeasonId: String? = null

    override suspend fun readSeasonProfiles(seasonId: String): List<StoredProfile> {
      reads++
      readSeasonId = seasonId
      return profiles
    }
  }

  /** A callable request for getRankedLeaderboard: plain JS object payload. */
  private fun leaderboardReq(
    authUid: String?,
    seasonId: Any?,
    limit: Any? = null,
    forgedData: Map<String, Any?>? = null,
  ): CallableRequest =
    object : CallableRequest {
      override val data: Any? = if (forgedData == null) {
        json("seasonId" to seasonId, "limit" to limit)
      } else {
        json(
          "seasonId" to seasonId,
          "limit" to limit,
          *forgedData.map { (k, v) -> k to v }.toTypedArray(),
        )
      }
      override val auth: AuthData? = authUid?.let {
        object : AuthData {
          override val uid: String = it
          override val token: AuthToken? = null
        }
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }

  @Suppress("UNCHECKED_CAST")
  private fun leaderboardRows(response: Map<String, Any?>): List<Map<String, Any?>> =
    response["rows"] as List<Map<String, Any?>>

  @Suppress("UNCHECKED_CAST")
  private fun leaderboardYou(response: Map<String, Any?>): Map<String, Any?>? =
    response["you"] as Map<String, Any?>?

  @Test
  fun getRankedLeaderboardHighlightsOnlyTheAuthenticatedPlayer() = runTest {
    val alice = StoredProfile("alice", linkedMapOf(
      "seasonId" to "s1", "tier" to "Gold", "division" to "II", "rp" to 700,
      "displayName" to "Alice", "avatarInitial" to "A",
    ))
    val charlie = StoredProfile("charlie", linkedMapOf(
      "seasonId" to "s1", "tier" to "Royal", "division" to "I", "rp" to 2800,
      "displayName" to "Charlie", "avatarInitial" to "C",
    ))
    val db = S63LeaderboardDb(listOf(alice, charlie))

    // Authenticate as alice but send bob's forged identity, tier, RP and
    // position in the payload — none of them may appear on the board.
    val response = leaderboardOnce(
      db,
      leaderboardReq(
        "alice", "s1", null,
        mapOf(
          "uid" to "bob",
          "playerId" to "bob",
          "tier" to "King",
          "division" to "",
          "rp" to 9999,
          "position" to 1,
        ),
      ),
    )

    assertEquals(true, response["ok"])
    assertEquals("s1", response["seasonId"])
    val rows = leaderboardRows(response)
    assertEquals(2, rows.size)
    // Board order is the server's: charlie (Royal I, 2800 RP) above alice (Gold II, 700 RP).
    assertEquals("Charlie", rows[0]["displayName"])
    assertEquals("Alice", rows[1]["displayName"])
    // Only alice's row is the current player — bob's forged position:1 never highlights bob.
    assertEquals(1, rows.count { it["isCurrentPlayer"] == true })
    assertEquals(true, rows[1]["isCurrentPlayer"])
    assertFalse(rows.any { it["displayName"] == "bob" })
    // No forged value appears in any row: no King tier, no 9999 RP.
    assertFalse(rows.any { it["tier"] == "King" })
    assertFalse(rows.any { it["rp"] == 9999 })
    // bob is not the current player and not highlighted.
    val you = leaderboardYou(response) ?: throw AssertionError("alice must be on the board")
    assertEquals("Alice", you["displayName"])
  }

  @Test
  fun aCallerNamingAnotherSeasonGetsOnlyThatSeasonsRows() = runTest {
    val s1Profile = StoredProfile("s1-player", linkedMapOf(
      "seasonId" to "s1", "tier" to "Gold", "division" to "II", "rp" to 700,
      "displayName" to "S1 Player", "avatarInitial" to "S",
    ))
    val s2Profile = StoredProfile("s2-player", linkedMapOf(
      "seasonId" to "s2", "tier" to "Royal", "division" to "I", "rp" to 2800,
      "displayName" to "S2 Player", "avatarInitial" to "T",
    ))
    val db = S63LeaderboardDb(listOf(s1Profile, s2Profile))

    // Naming s2 explicitly is allowed (a historical board) — but only s2's rows.
    val response = leaderboardOnce(db, leaderboardReq("alice", "s2", null))

    assertEquals("s2", response["seasonId"])
    val rows = leaderboardRows(response)
    assertEquals(1, rows.size)
    assertEquals("S2 Player", rows[0]["displayName"])
    assertFalse(rows.any { it["displayName"] == "S1 Player" })
    assertEquals(1, response["total"])
  }

  @Test
  fun aSeasonStringIsAnExactNameNeverAPattern() = runTest {
    val s1Profile = StoredProfile("s1-player", linkedMapOf(
      "seasonId" to "s1", "tier" to "Gold", "division" to "II", "rp" to 700,
      "displayName" to "S1 Player", "avatarInitial" to "S",
    ))
    val s2Profile = StoredProfile("s2-player", linkedMapOf(
      "seasonId" to "s2", "tier" to "Royal", "division" to "I", "rp" to 2800,
      "displayName" to "S2 Player", "avatarInitial" to "T",
    ))
    val starProfile = StoredProfile("star-player", linkedMapOf(
      "seasonId" to "s*", "tier" to "King", "division" to "", "rp" to 3200,
      "displayName" to "Star Player", "avatarInitial" to "X",
    ))
    val db = S63LeaderboardDb(listOf(s1Profile, s2Profile, starProfile))

    // Requesting the literal season "s*" returns only that season's profile —
    // the asterisk is an ordinary character, never a glob or regex.
    val response = leaderboardOnce(db, leaderboardReq("alice", "s*", null))

    assertEquals("s*", response["seasonId"])
    val rows = leaderboardRows(response)
    assertEquals(1, rows.size)
    assertEquals("Star Player", rows[0]["displayName"])
    assertEquals(3200, rows[0]["rp"])
    assertFalse(rows.any { it["displayName"] == "S1 Player" })
    assertFalse(rows.any { it["displayName"] == "S2 Player" })
    assertEquals(1, response["total"])
    // Exactly one read, for the exact season name.
    assertEquals(1, db.reads)
    assertEquals("s*", db.readSeasonId)
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Section D — Tie edge cases (boundary ties not already covered)
  // ═══════════════════════════════════════════════════════════════════════════
  //
  // SeasonLeaderboardTest.tiedRungAndRpShareAPositionAndTheNextOneSkips covers
  // a 4-player board with positions 1, 2, 2, 4 (a tied 2nd). SettlementTest
  // covers tiedTopSettlesBothKings (settlement, not leaderboard). What is not
  // covered there: a two-player tie at the very top (both position 1) and a
  // tie split by the page limit where positions and total must remain stable.

  @Test
  fun twoPlayerTieAtTopOfLeaderboardSharePositionOne() = runTest {
    val tiedTop1 = StoredProfile("top-a", linkedMapOf(
      "seasonId" to "s1", "tier" to "King", "division" to "", "rp" to 3_200,
      "displayName" to "Top A", "avatarInitial" to "X",
    ))
    val tiedTop2 = StoredProfile("top-b", linkedMapOf(
      "seasonId" to "s1", "tier" to "King", "division" to "", "rp" to 3_200,
      "displayName" to "Top B", "avatarInitial" to "Y",
    ))
    val third = StoredProfile("third", linkedMapOf(
      "seasonId" to "s1", "tier" to "Royal", "division" to "I", "rp" to 2_800,
      "displayName" to "Third", "avatarInitial" to "Z",
    ))
    val db = S63LeaderboardDb(listOf(tiedTop1, tiedTop2, third))

    val response = leaderboardOnce(db, leaderboardReq("alice", "s1", null))

    assertEquals(true, response["ok"])
    val rows = leaderboardRows(response)
    assertEquals(3, rows.size)
    // Competition ranking: two tied at top share position 1, next skips to 3.
    assertEquals(listOf(1, 1, 3), rows.map { it["position"] })
    assertEquals(listOf("Top A", "Top B", "Third"), rows.map { it["displayName"] })
    assertEquals(3, response["total"])
    assertEquals(0, response["skipped"])
  }

  @Test
  fun aTieSplitByThePageLimitKeepsPositionsAndTotalStable() = runTest {
    // Three players tied at position 1; limit = 2 cuts through the tie.
    // positions and total must remain stable despite the cut.
    val tied1 = StoredProfile("tied-1", linkedMapOf(
      "seasonId" to "s1", "tier" to "King", "division" to "", "rp" to 3_200,
      "displayName" to "Tied 1", "avatarInitial" to "X",
    ))
    val tied2 = StoredProfile("tied-2", linkedMapOf(
      "seasonId" to "s1", "tier" to "King", "division" to "", "rp" to 3_200,
      "displayName" to "Tied 2", "avatarInitial" to "Y",
    ))
    val tied3 = StoredProfile("tied-3", linkedMapOf(
      "seasonId" to "s1", "tier" to "King", "division" to "", "rp" to 3_200,
      "displayName" to "Tied 3", "avatarInitial" to "Z",
    ))
    val db = S63LeaderboardDb(listOf(tied1, tied2, tied3))

    // Limit of 2 splits a 3-way tie; total and positions stay stable.
    val response = leaderboardOnce(db, leaderboardReq("alice", "s1", 2))

    assertEquals(true, response["ok"])
    val rows = leaderboardRows(response)
    assertEquals(2, rows.size, "limit truncates delivered rows")
    assertEquals(listOf(1, 1), rows.map { it["position"] }, "tied positions stay 1")
    val delivered = rows.map { it["displayName"] }.toSet()
    assertEquals(2, delivered.size, "two distinct rows delivered")
    assertTrue(setOf("Tied 1", "Tied 2", "Tied 3").containsAll(delivered),
      "every delivered row is a member of the tied set")
    // Total is the whole board, not the slice; positions/total would be wrong
    // if the tie were collapsed or renumbered at the boundary.
    assertEquals(3, response["total"])
    assertEquals(0, response["skipped"])
    assertEquals("s1", response["seasonId"])
  }

  // ══════════════════════════════════════════════════════════════════════════
  // Section E — Numeric/argument abuse on the leaderboard
  // ═══════════════════════════════════════════════════════════════════════════

  private fun leaderboardWithProfiles(seasonId: String = "s1"): S63LeaderboardDb {
    val profile = StoredProfile("alice", linkedMapOf(
      "seasonId" to seasonId, "tier" to "Gold", "division" to "II", "rp" to 700,
      "displayName" to "Alice", "avatarInitial" to "A",
    ))
    return S63LeaderboardDb(listOf(profile))
  }

  /** Asserts a leaderboard call throws invalid-argument and costs zero reads. */
  private suspend fun assertInvalidArgumentZeroReads(db: S63LeaderboardDb, block: suspend () -> Any?) {
    try {
      block()
      throw AssertionError("expected invalid-argument HttpsError")
    } catch (e: Throwable) {
      assertEquals("invalid-argument", e.asDynamic().code as String)
    }
    assertEquals(0, db.reads, "an invalid argument must cost zero profile reads")
  }

  @Test
  fun aNanLimitIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", Double.NaN)) }
  }

  @Test
  fun aPositiveInfinityLimitIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", Double.POSITIVE_INFINITY)) }
  }

  @Test
  fun aNegativeInfinityLimitIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", Double.NEGATIVE_INFINITY)) }
  }

  @Test
  fun anIntMaxPlusOneLimitIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", (Int.MAX_VALUE + 1.0))) }
  }

  @Test
  fun aLongMaxValueLimitIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", Long.MAX_VALUE.toDouble())) }
  }

  @Test
  fun aNumericStringLimitIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", "5")) }
  }

  @Test
  fun aBooleanLimitIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", true)) }
  }

  @Test
  fun aRejectedSeasonIdCostsZeroReadsEvenWithAValidLimit() = runTest {
    val db = leaderboardWithProfiles()
    // A non-string seasonId is refused before the limit is ever parsed, and
    // before any profile is read.
    for (season in listOf(5, true, Double.NaN, emptyList<Any>())) {
      assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", season, 10)) }
    }
    assertEquals(0, db.reads)
  }

  @Test
  fun aRejectedLimitCostsZeroReadsEvenWithAValidSeasonId() = runTest {
    val db = leaderboardWithProfiles()
    // A valid season is resolved, but a bad limit is refused before the read.
    for (limit in listOf(0, -1, 101, 1_000, 2.5)) {
      assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "s1", limit)) }
    }
    assertEquals(0, db.reads)
  }

  @Test
  fun aBlankSeasonIdIsInvalidArgumentAndCostsZeroReads() = runTest {
    val db = leaderboardWithProfiles()
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "", 10)) }
    assertInvalidArgumentZeroReads(db) { leaderboardOnce(db, leaderboardReq("alice", "   ", 10)) }
    assertEquals(0, db.reads)
  }

  @Test
  fun aValidSeasonAndValidLimitCostsExactlyOneRead() = runTest {
    val db = leaderboardWithProfiles()
    val response = leaderboardOnce(db, leaderboardReq("alice", "s1", 10))
    assertEquals(true, response["ok"])
    assertEquals(1, db.reads)
    assertEquals("s1", db.readSeasonId)
    assertEquals(1, response["total"])
  }
}
