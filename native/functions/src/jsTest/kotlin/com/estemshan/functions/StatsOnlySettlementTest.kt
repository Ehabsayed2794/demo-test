package com.estemshan.functions

import com.estemshan.engine.MatchMode
import com.estemshan.engine.MatchOutcome
import com.estemshan.functions.Settlement.ConvergedMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * S6 (A7.5 / A8.4, Q34 / Q38, Q46) — the ROOM/UNRANKED stats-only
 * settlement, on a STATEFUL fake: `writeProfile` merges into the stored
 * profile, so a second fold would compound — which is exactly the
 * observable the idempotence test needs. The fake also instruments the two
 * surfaces the hard prohibitions name (`seasons/current` through
 * [SettlementDb.SettlementTx.currentSeasonId], and the RP ledger through
 * `appendLedger`), plus the archive read the RANKED path owns.
 *
 * The golden table throughout: p1 25 (KING), p4 14 (SECOND), p3 12 (THIRD),
 * p2 −11 (KOZ) — [SettlementFixtures.GOLDEN_SCORES] numbers, so placements
 * are derived by the engine's own [com.estemshan.engine.placementOutcome].
 */
class StatsOnlySettlementTest {

  private val uids = listOf("u1", "u2", "u3", "u4")

  private companion object {
    /** Sentinel for "key absent" (distinct from an explicit null). */
    val ABSENT = object {}

    /** p1 top, p2 bottom: the departure override's whole point is p1. */
    val GOLDEN: Map<String, Int> = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14)

    val FIVE_COUNTER_FIELDS = setOf(
      "rankedGamesPlayed", "rankedKingCount", "rankedSecondCount",
      "rankedThirdCount", "rankedKozCount",
    )

    val UNTOUCHED_CONTRACT_FIELDS = setOf(
      "rp", "tier", "division", "demotionShield",
      "highestRankTier", "highestRankDivision", "seasonId", "placementState",
    )
  }

  // ── fixture builders ──────────────────────────────────────────────

  private fun roomMatch(
    mode: Any? = "ROOM",
    players: List<String> = listOf("u1", "u2", "u3", "u4"),
    seats: Map<String, String> = mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4"),
    status: String = "complete",
    completedRound: Any? = 1,
    maxRounds: Any? = 1,
    finalScores: Any? = GOLDEN,
    winnerIds: Any? = listOf("p1"),
    leftUids: Any? = ABSENT,
    timeoutRemovedUids: Any? = ABSENT,
    removedUids: Any? = ABSENT,
    takeoverRemovedUids: Any? = ABSENT,
    disconnectReplacedUids: Any? = ABSENT,
  ): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["roomId"] = "r1"
    out["players"] = players
    out["status"] = status
    out["currentRound"] = completedRound
    out["completedRound"] = completedRound
    out["maxRounds"] = maxRounds
    out["dealer"] = players.first()
    out["seats"] = seats
    out["version"] = 7
    out["mode"] = mode
    out["gameType"] = "FULL"
    out["scoringMode"] = "NORMAL"
    if (finalScores !== ABSENT) out["finalScores"] = finalScores
    if (winnerIds !== ABSENT) out["winnerIds"] = winnerIds
    if (leftUids !== ABSENT) out["leftUids"] = leftUids
    if (timeoutRemovedUids !== ABSENT) out["timeoutRemovedUids"] = timeoutRemovedUids
    if (removedUids !== ABSENT) out["removedUids"] = removedUids
    if (takeoverRemovedUids !== ABSENT) out["takeoverRemovedUids"] = takeoverRemovedUids
    if (disconnectReplacedUids !== ABSENT) out["disconnectReplacedUids"] = disconnectReplacedUids
    return out
  }

  /** Existing profile documents for every uid (an unplaced-but-existing doc). */
  private fun profiles(vararg placement: Pair<String, String>): MutableMap<String, MutableMap<String, Any?>> {
    val base = uids.associateWith { uid ->
      mutableMapOf<String, Any?>("uid" to uid)
    }.toMutableMap()
    for ((uid, state) in placement) base.getValue(uid)["placementState"] = state
    return base
  }

  /**
   * The stateful fake: every profile write MERGES into the stored document,
   * so calling the settlement twice compounds the counters unless the claim
   * truly makes the fold once-only. Reads after writes throw, exactly as
   * the Admin SDK does.
   */
  private class StatsDb(
    val match: Map<String, Any?>,
    val storedProfiles: MutableMap<String, MutableMap<String, Any?>>,
    var claim: Map<String, Any?>? = null,
  ) : SettlementDb {
    var txRuns = 0
    var archiveReads = 0
    var seasonReads = 0
    var corrections = 0
    var claims = 0
    val appends = mutableListOf<Map<String, Any?>>()
    val profileWrites = mutableListOf<Pair<String, Map<String, Any?>>>()
    val profileReads = mutableListOf<String>()
    val events = mutableListOf<String>()

    fun writeFor(uid: String): Map<String, Any?> = profileWrites.single { it.first == uid }.second

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = match

    override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
      archiveReads++
      return emptyMap()
    }

    override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
      txRuns++
      return block(object : SettlementDb.SettlementTx {
        override suspend fun readClaim(matchId: String): Map<String, Any?>? {
          events.add("read-claim")
          return claim
        }

        override suspend fun currentSeasonId(): String? {
          seasonReads++
          events.add("read-season")
          return "s1"
        }

        override suspend fun readProfile(uid: String): Map<String, Any?>? {
          events.add("read-profile:$uid")
          profileReads.add(uid)
          if (events.any { it.startsWith("write-") }) {
            throw IllegalStateException(
              "Firestore transactions require all reads to be executed before all writes",
            )
          }
          return storedProfiles[uid]
        }

        override fun writeCorrection(
          matchId: String,
          scores: Map<String, Int>,
          winners: List<String>,
          version: Int,
        ) {
          events.add("write-correction")
          corrections++
        }

        override fun writeProfile(uid: String, fields: Map<String, Any?>) {
          events.add("write-profile:$uid")
          profileWrites.add(uid to fields)
          val merged = storedProfiles[uid]?.toMutableMap() ?: mutableMapOf()
          merged.putAll(fields)
          storedProfiles[uid] = merged
        }

        override fun writeClaim(matchId: String, record: Map<String, Any?>) {
          events.add("write-claim")
          claims++
          claim = record
        }
      })
    }

    override suspend fun appendLedger(record: Map<String, Any?>) {
      appends.add(record)
    }

    override suspend fun readCorrectionWindow(): List<Map<String, Any?>> = emptyList()
  }

  private fun db(
    match: Map<String, Any?> = roomMatch(),
    storedProfiles: MutableMap<String, MutableMap<String, Any?>> = profiles(),
  ) = StatsDb(match, storedProfiles)

  private suspend fun expectDenied(
    vararg messageParts: String,
    block: suspend () -> Any?,
  ) {
    val thrown = try {
      block()
      throw AssertionError("expected an HttpsError")
    } catch (e: Throwable) {
      if (e.message == "expected an HttpsError") throw e
      e
    }
    assertEquals("failed-precondition", thrown.asDynamic().code as String, "denied with: ${thrown.message}")
    messageParts.forEach { part ->
      assertTrue(
        thrown.message?.contains(part) == true,
        "expected '${thrown.message}' to name '$part'",
      )
    }
  }

  // ── the fold, per mode ────────────────────────────────────────────

  /**
   * A ROOM and an UNRANKED completion each fold Games Played +1 and exactly
   * one outcome counter for every human seat, from the engine's own
   * placement of the claimed totals: p1 King, p2 Koz, p3 Third, p4 Second.
   */
  @Test
  fun everyModeCountsOneGameAndOneOutcomePerSeat() = runTest {
    for (mode in listOf("ROOM", "UNRANKED")) {
      val statsDb = db(roomMatch(mode = mode))
      val response = settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

      assertEquals(true, response["settled"], "$mode settles")
      assertEquals(false, response["corrected"], "$mode never corrects — no recompute ran")
      assertEquals(GOLDEN, response["finalScores"], "$mode settles the claimed totals as-is")
      assertNull(response["you"], "$mode reports no movement")
      for (uid in uids) {
        val write = statsDb.writeFor(uid)
        assertEquals(1, write["rankedGamesPlayed"], "$mode games played for $uid")
        val outcomes = listOf(
          "rankedKingCount", "rankedSecondCount", "rankedThirdCount", "rankedKozCount",
        ).map { write.getValue(it) as Int }
        assertEquals(1, outcomes.sum(), "exactly one outcome counter moves for $uid: $write")
        assertEquals(1, outcomes.count { it == 1 }, "one counter, not split: $write")
      }
      assertEquals(1, statsDb.writeFor("u1")["rankedKingCount"])
      assertEquals(0, statsDb.writeFor("u1")["rankedKozCount"])
      assertEquals(1, statsDb.writeFor("u2")["rankedKozCount"])
      assertEquals(1, statsDb.writeFor("u3")["rankedThirdCount"])
      assertEquals(1, statsDb.writeFor("u4")["rankedSecondCount"])
    }
  }

  /** The write owns EXACTLY the five counters; the RP contract set is absent, not merely equal. */
  @Test
  fun writesOwnExactlyTheFiveCounterFieldsAndNoRpField() = runTest {
    val statsDb = db()
    settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(4, statsDb.profileWrites.size)
    statsDb.profileWrites.forEach { (uid, fields) ->
      assertEquals(FIVE_COUNTER_FIELDS, fields.keys, "write for '$uid' owns exactly the five counters")
      UNTOUCHED_CONTRACT_FIELDS.forEach { forbidden ->
        assertTrue(forbidden !in fields, "$forbidden must not be written for '$uid'")
      }
    }
  }

  /**
   * The S6 review's substantive guard: a corrupt Ranked-progression field
   * (`rp` here) must not block a stats-only settlement, because this path
   * never reads or writes RP. A RANKED settlement would fail closed on the
   * same document.
   */
  @Test
  fun aCorruptRpFieldDoesNotBlockAStatsOnlySettlement() = runTest {
    val stored = profiles().also { it.getValue("u1")["rp"] = "not-a-number" }
    val statsDb = db(storedProfiles = stored)
    val response = settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, response["settled"])
    assertEquals(1, statsDb.writeFor("u1")["rankedGamesPlayed"])
    assertEquals(1, statsDb.writeFor("u1")["rankedKingCount"], "u1's seat is King in GOLDEN")
    assertTrue("rp" !in statsDb.writeFor("u1"))
  }

  /**
   * The three prohibited reads/writes, instrumented: no `seasons/current`
   * read inside the transaction, no RP ledger append, no roundArchive read
   * (the recompute is the RANKED authority path and never runs here).
   */
  @Test
  fun statsOnlyNeverReadsSeasonsAppendsLedgerNorReadsArchives() = runTest {
    val statsDb = db(roomMatch(mode = "UNRANKED"))
    settleOnce(statsDb, SettlementFixtures.settleRequest("u2", "m1"))

    assertEquals(0, statsDb.seasonReads, "seasons/current is never consulted")
    assertEquals(0, statsDb.archiveReads, "no archive is read — no recompute")
    assertEquals(0, statsDb.appends.size, "no RP ledger document is appended")
    assertEquals(0, statsDb.corrections, "no correction is written")
    assertTrue("read-season" !in statsDb.events, "the season read must not even be attempted")
  }

  // ── the departure override (A7.5) ─────────────────────────────────

  /**
   * The heart of A7.5: u1's seat holds the TOP total (25, King), but every
   * one of the four departure mechanisms overrides the row to KOZ — the
   * bot's King is never attributed to the departed human. Games Played
   * still grows by one (A8.4 / Q38), which is the same test's second half.
   */
  @Test
  fun departedHumanIsKozEvenWhenTheSeatFinishedKing() = runTest {
    val provenance = mapOf(
      "leftUids" to listOf("u1"),
      "timeoutRemovedUids" to listOf("u1"),
      "removedUids" to listOf("u1"),
      "takeoverRemovedUids" to listOf("u1"),
    )
    for ((field, value) in provenance) {
      val fields = roomMatch().apply { this[field] = value }
      val statsDb = db(fields)
      settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

      assertEquals(0, statsDb.writeFor("u1")["rankedKingCount"], "$field: the bot's King is not attributed")
      assertEquals(1, statsDb.writeFor("u1")["rankedKozCount"], "$field: the departed row is KOZ")
      assertEquals(1, statsDb.writeFor("u1")["rankedGamesPlayed"], "$field: Games Played still +1 (A8.4)")
    }
  }

  /** The union: one uid per mechanism still lands the override exactly once. */
  @Test
  fun allFourMechanismsUnionIntoOneOverrideEach() = runTest {
    val statsDb = db(
      roomMatch(
        leftUids = listOf("u1"),
        timeoutRemovedUids = listOf("u2"),
        removedUids = listOf("u3"),
        takeoverRemovedUids = listOf("u4"),
      ),
    )
    settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

    for (uid in uids) {
      assertEquals(1, statsDb.writeFor(uid)["rankedKozCount"], "$uid is departed → KOZ")
      assertEquals(0, statsDb.writeFor(uid)["rankedKingCount"])
      assertEquals(0, statsDb.writeFor(uid)["rankedSecondCount"])
      assertEquals(0, statsDb.writeFor(uid)["rankedThirdCount"])
      assertEquals(1, statsDb.writeFor(uid)["rankedGamesPlayed"])
    }
  }

  /**
   * The three completing humans keep their OWN placements in the very match
   * where u1 was overridden: u4's Second and u3's Third are untouched, and
   * u2 keeps the KOZ it earned at the bottom on its own.
   */
  @Test
  fun completingHumansKeepTheirOwnPlacements() = runTest {
    val statsDb = db(roomMatch(leftUids = listOf("u1")))
    settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(1, statsDb.writeFor("u4")["rankedSecondCount"], "u4 keeps Second")
    assertEquals(1, statsDb.writeFor("u3")["rankedThirdCount"], "u3 keeps Third")
    assertEquals(1, statsDb.writeFor("u2")["rankedKozCount"], "u2 keeps its own bottom KOZ")
    assertEquals(0, statsDb.writeFor("u4")["rankedKozCount"])
    assertEquals(0, statsDb.writeFor("u3")["rankedKingCount"])
  }

  /**
   * `disconnectReplacedUids` is DELIBERATELY not consumed (A7.4: a
   * permanently replaced disconnect keeps its own charge table — ROOM none,
   * UNRANKED 2 Koz — and that consumer does not exist yet). u1's row keeps
   * the seat's own placement (King), and no charge is invented: this pins
   * the gap, not a rule.
   */
  @Test
  fun disconnectReplacedUidsIsDeliberatelyNotForced() = runTest {
    val statsDb = db(roomMatch(disconnectReplacedUids = listOf("u1")))
    settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(1, statsDb.writeFor("u1")["rankedKingCount"], "the placement stands — no override")
    assertEquals(0, statsDb.writeFor("u1")["rankedKozCount"], "and no charge is invented either")
    assertEquals(1, statsDb.writeFor("u1")["rankedGamesPlayed"])
  }

  // ── attribution ───────────────────────────────────────────────────

  /**
   * A seat whose uid is not in `players` is a pure bot seat: its profile is
   * never read and nothing is written for it, while the three humans fold
   * normally. (p2 is the bot seat here; u2 is simply gone from the table.)
   */
  @Test
  fun aPureBotSeatWritesNothingToAnyProfile() = runTest {
    val statsDb = db(
      roomMatch(
        players = listOf("u1", "u3", "u4"),
        seats = mapOf("p1" to "u1", "p2" to "bot-1", "p3" to "u3", "p4" to "u4"),
      ),
    )
    settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(listOf("u1", "u3", "u4"), statsDb.profileReads, "the bot uid is never read")
    assertEquals(setOf("u1", "u3", "u4"), statsDb.profileWrites.map { it.first }.toSet())
    assertEquals(1, statsDb.writeFor("u1")["rankedKingCount"], "the bot seat's KOZ row belongs to nobody")
    assertEquals(1, statsDb.writeFor("u3")["rankedThirdCount"])
    assertEquals(1, statsDb.claims, "the table still settles and records its claim")
  }

  /**
   * RD10 through the stats-only path: a placement profile counts toward NO
   * statistic — not even a departed one. u1 is mid-placement AND departed;
   * its five counters come back exactly as stored (zeros), while the
   * completing neighbours fold.
   */
  @Test
  fun aPlacementProfileCountsTowardNothingEvenWhenDeparted() = runTest {
    val stored = profiles("u1" to "IN_PROGRESS")
    val statsDb = db(roomMatch(leftUids = listOf("u1")), stored)
    settleOnce(statsDb, SettlementFixtures.settleRequest("u2", "m1"))

    for (field in FIVE_COUNTER_FIELDS) {
      val written = statsDb.writeFor("u1")
      assertEquals(0, written[field], "placement profile: $field unchanged")
    }
    assertEquals(1, statsDb.writeFor("u2")["rankedKozCount"])
    assertEquals(1, statsDb.writeFor("u4")["rankedSecondCount"])
  }

  // ── exactly-once ──────────────────────────────────────────────────

  /**
   * The claim is the once-only record: the second call replays
   * (`replayed: true`), answers with no `you`, writes no second claim — and
   * because the fake is STATEFUL, the counters in the stored documents are
   * the proof they moved exactly once (a second fold would read 2).
   */
  @Test
  fun aSecondCallReplaysAndTheCountersMovedExactlyOnce() = runTest {
    val statsDb = db()
    val first = settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1"))
    assertEquals(true, first["settled"])
    assertNull(first["you"])

    val replay = settleOnce(statsDb, SettlementFixtures.settleRequest("u3", "m1"))

    assertEquals(true, replay["replayed"], "the second call replays")
    assertNull(replay["you"], "a stats-only replay has no you — empty movements")
    assertEquals(first["finalScores"], replay["finalScores"])
    assertEquals(2, statsDb.txRuns)
    assertEquals(1, statsDb.claims, "one claim document, not two")
    assertEquals(4, statsDb.profileWrites.size, "no profile is written a second time")
    assertEquals(0, statsDb.appends.size, "and no ledger row appears on the replay either")
    for (uid in uids) {
      val stored = statsDb.storedProfiles.getValue(uid)
      assertEquals(1, stored["rankedGamesPlayed"], "counters moved exactly once for $uid")
    }
    assertEquals(1, statsDb.storedProfiles.getValue("u1")["rankedKingCount"])
  }

  // ── gate and fail-closed validation ───────────────────────────────

  /** The membership gate is unchanged by S6: a stranger is denied on a ROOM match too. */
  @Test
  fun aNonMemberIsStillRejectedOnARoomMatch() = runTest {
    val statsDb = db()
    val thrown = try {
      settleOnce(statsDb, SettlementFixtures.settleRequest("stranger", "m1"))
      throw AssertionError("expected an HttpsError")
    } catch (e: Throwable) {
      if (e.message == "expected an HttpsError") throw e
      e
    }
    assertEquals("permission-denied", thrown.asDynamic().code as String)
    assertEquals(0, statsDb.txRuns, "the denial precedes any transaction")
    assertEquals(0, statsDb.claims)
    assertEquals(0, statsDb.profileWrites.size)
  }

  /**
   * The converged-document validation, one shape per row: not complete,
   * completedRound that does not end the match, no finalScores, no
   * winnerIds, a winner naming a seat that does not exist, and a totals map
   * that does not cover exactly the seats. Every shape fails closed with
   * failed-precondition BEFORE any write — no claim, so the match stays
   * settleable — and no score is invented.
   */
  @Test
  fun anInvalidConvergedDocumentFailsClosed() = runTest {
    val shapes = listOf(
      roomMatch(status = "starting") to "is not complete",
      roomMatch(completedRound = 1, maxRounds = 18) to "does not end",
      roomMatch(finalScores = ABSENT) to "no claimed result",
      roomMatch(winnerIds = ABSENT) to "no claimed result",
      roomMatch(winnerIds = listOf("p9")) to "not in the match",
      roomMatch(finalScores = mapOf("p1" to 25, "p2" to -11, "p3" to 12)) to
        "do not cover exactly the match seats",
    )
    for ((fields, messagePart) in shapes) {
      val statsDb = db(fields)
      expectDenied(messagePart) { settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1")) }
      assertEquals(0, statsDb.claims, "[$messagePart] no claim — the match stays settleable")
      assertEquals(0, statsDb.profileWrites.size, "[$messagePart] nothing writes")
    }
  }

  /** A seated human with no players/ document fails the whole table closed, exactly as RANKED does. */
  @Test
  fun aMissingProfileFailsTheWholeSettlementClosed() = runTest {
    val stored = profiles()
    stored.remove("u4")
    val statsDb = db(roomMatch(), stored)
    expectDenied("u4") { settleOnce(statsDb, SettlementFixtures.settleRequest("u1", "m1")) }
    assertEquals(0, statsDb.claims)
    assertEquals(0, statsDb.profileWrites.size)
  }

  // ── provenance parsing ────────────────────────────────────────────

  /**
   * The defensive parse, pinned: absent ⇒ empty, a non-list ⇒ empty, a
   * non-string element is skipped (the DisconnectVoteFlow `stringSet`
   * convention), and a well-formed list round-trips. Provenance is
   * advisory override input, so a malformed list can never make a converged
   * match unsettleable.
   */
  @Test
  fun provenanceListsParseDefensively() {
    assertEquals(emptyList(), Settlement.parseUidList(null))
    assertEquals(emptyList(), Settlement.parseUidList("u1"))
    assertEquals(emptyList(), Settlement.parseUidList(42))
    assertEquals(listOf("u1", "u2"), Settlement.parseUidList(listOf("u1", 7, "u2", null)))
    assertEquals(listOf("u1"), Settlement.parseUidList(listOf("u1")))
  }

  /** parseMatch wires the four raw fields — including S7's not-yet-existing takeover list — into the match. */
  @Test
  fun parseMatchCarriesAllFourProvenanceLists() {
    val parsed = Settlement.parseMatch(
      "m1",
      roomMatch(
        leftUids = listOf("u1"),
        timeoutRemovedUids = listOf("u2"),
        removedUids = listOf("u3"),
        takeoverRemovedUids = listOf("u4"),
        disconnectReplacedUids = listOf("u1"),
      ),
    ) ?: fail("fixture did not parse")

    assertEquals(listOf("u1"), parsed.leftUids)
    assertEquals(listOf("u2"), parsed.timeoutRemovedUids)
    assertEquals(listOf("u3"), parsed.removedUids)
    assertEquals(listOf("u4"), parsed.takeoverRemovedUids)
    assertEquals(setOf("u1", "u2", "u3", "u4"), parsed.departedUids)
  }

  /** A document with no provenance at all parses to empty lists and an empty union. */
  @Test
  fun absentProvenanceParsesEmpty() {
    val parsed = Settlement.parseMatch("m1", roomMatch()) ?: fail("fixture did not parse")
    assertEquals(emptyList(), parsed.leftUids)
    assertEquals(emptyList(), parsed.timeoutRemovedUids)
    assertEquals(emptyList(), parsed.removedUids)
    assertEquals(emptyList(), parsed.takeoverRemovedUids)
    assertEquals(emptySet(), parsed.departedUids)
  }

  /** The union the override consumes is exposed on the parsed match for every mode. */
  @Test
  fun departedUidsUnionsTheFourLists() {
    val match = ConvergedMatch(
      matchId = "m1",
      mode = MatchMode.ROOM,
      status = "complete",
      order = listOf("p1", "p2", "p3", "p4"),
      players = uids,
      dealerSeatFinal = "p1",
      gameType = com.estemshan.engine.GameType.FULL,
      classic = false,
      completedRound = 1,
      maxRounds = 1,
      claimedScores = GOLDEN,
      claimedWinners = listOf("p1"),
      archives = emptyList(),
      leftUids = listOf("u1", "u2"),
      timeoutRemovedUids = listOf("u2"),
      removedUids = listOf("u3"),
      takeoverRemovedUids = listOf("u4"),
    )
    assertEquals(setOf("u1", "u2", "u3", "u4"), match.departedUids, "deduped across mechanisms")
  }

  /**
   * The override's two inputs: the parser marks the departed uid, and the
   * seat's own placement is King. The end-to-end override is covered by
   * [departedHumanIsKozEvenWhenTheSeatFinishedKing]; this test must not
   * restate the production expression.
   */
  @Test
  fun theOverrideInputsAreTheDepartedUidAndTheKingSeat() {
    val parsed = Settlement.parseMatch("m1", roomMatch(leftUids = listOf("u1"))) ?: fail("parse failed")
    assertTrue("u1" in parsed.departedUids, "u1 is parsed as departed")
    assertEquals(
      MatchOutcome.KING,
      com.estemshan.engine.placementOutcome(GOLDEN, "p1"),
      "fixture sanity: p1 is King by total",
    )
  }
}
