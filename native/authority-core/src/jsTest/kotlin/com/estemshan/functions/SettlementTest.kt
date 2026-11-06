package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.functions.Fixtures.ABSENT
import com.estemshan.functions.Fixtures.archiveFields
import com.estemshan.functions.Fixtures.archived
import com.estemshan.functions.Fixtures.card
import com.estemshan.functions.Fixtures.confirm
import com.estemshan.functions.Fixtures.converged
import com.estemshan.functions.Fixtures.dash
import com.estemshan.functions.Fixtures.auctionBid
import com.estemshan.functions.Fixtures.auctionPass
import com.estemshan.functions.Fixtures.matchFields
import com.estemshan.functions.Fixtures.playsFor
import com.estemshan.functions.Fixtures.round1Golden
import com.estemshan.functions.Fixtures.round1Parts
import com.estemshan.functions.Fixtures.rawRound
import com.estemshan.functions.Fixtures.shellMatch
import com.estemshan.functions.Settlement.SettleDecision
import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S50 settlement goldens. Every expected total below was hand-derived
 * from docs/specs/04-scoring.md through the engine (caller/with/sole/
 * risk-ladder/dash-table applied by hand, tricksWon always summing to
 * 13) — the tests assert THOSE numbers against the shared engine's
 * recompute, so an engine drift fails loudly here instead of settling
 * wrong numbers.
 *
 * Fixture cardLogs are built, not recorded: winners take the highest
 * remaining card of the winning suit, other slots take cards that
 * cannot beat it, so trickWinner re-derives exactly the intended
 * tricksWon. A misbuilt fixture fails its own test (recompute
 * disagrees), never silently.
 */
class SettlementTest {

  // ── gate ──────────────────────────────────────────────────────────

  @Test
  fun roomAndUnrankedAreExplicitNoOps() {
    val base = converged(emptyList())
    val room = Settlement.decide("u1", base.copy(mode = MatchMode.ROOM))
    assertIs<SettleDecision.NoOp>(room)
    assertEquals(MatchMode.ROOM, room.mode)
    val unranked = Settlement.decide("u1", base.copy(mode = MatchMode.UNRANKED))
    assertIs<SettleDecision.NoOp>(unranked)
  }

  @Test
  fun absentModeParsesAsRoomAndNoOps() {
    val match = Settlement.parseMatch("m1", matchFields(mode = ABSENT))
      ?: throw AssertionError("parse failed")
    assertEquals(MatchMode.ROOM, match.mode)
    assertIs<SettleDecision.NoOp>(Settlement.decide("u1", match))
  }

  @Test
  fun garbageEnumsFallBackNeverThrow() {
    assertEquals(MatchMode.ROOM, Settlement.parseMatchMode("GOLD"))
    assertEquals(GameType.FULL, Settlement.parseGameType("MINI2"))
    assertEquals(ScoringMode.NORMAL, Settlement.parseScoringMode("CLASSIC_"))
  }

  @Test
  fun nonMemberIsDenied() {
    val decision = Settlement.decide("stranger", converged(emptyList()))
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.NOT_MEMBER, decision.reason)
  }

  @Test
  fun incompleteMatchFailsClosed() {
    val match = converged(emptyList(), matchFields(status = "starting"))
    val decision = Settlement.recompute(match)
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.NOT_COMPLETE, decision.reason)
  }

  // ── golden recompute ──────────────────────────────────────────────

  @Test
  fun agreedWhenClaimMatchesRecompute() {
    val decision = Settlement.recompute(converged(listOf(round1Golden())))
    assertTrue(decision is SettleDecision.Agreed, "expected Agreed but was $decision")
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), decision.recomputed.finalScores)
    assertEquals(listOf("p1"), decision.recomputed.winnerIds)
  }

  @Test
  fun correctedWhenClaimDisagrees() {
    val forged = matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14))
    val decision = Settlement.recompute(converged(listOf(round1Golden()), forged))
    assertTrue(decision is SettleDecision.Corrected, "expected Corrected but was $decision")
    // The recomputed value is what settles — p2 drops back to -11.
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), decision.recomputed.finalScores)
    assertEquals(listOf("p1"), decision.recomputed.winnerIds)
    assertEquals(mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14), decision.recomputed.claimedScores)
  }

  @Test
  fun withAndDashCallDeriveFromReplay() {
    // Caller p1 5♠; p3 matched the suit in-auction (With) but misses
    // (WIZZ miss −(1+10) = −11); p2 dash-called and wins (+33, under);
    // p4 RISK-misses by two (−2). Match King is p2 — the dash caller.
    val winners = listOf("p1", "p1", "p1", "p1", "p1", "p3", "p3", "p3", "p3", "p4", "p4", "p4", "p4")
    val actions = listOf(
      dash("p1", false, 1), dash("p2", true, 1), dash("p3", false, 1), dash("p4", false, 1),
      auctionBid("p1", 5, "SPADES", 1),
      auctionBid("p3", 5, "SPADES", 1),
      auctionPass("p4", 1),
      auctionPass("p1", 1),
      confirm("p1", 5, "SPADES", 1),
    )
    val round = archived(1, winners, "SPADES", actions, mapOf("p3" to 5, "p4" to 2))
    val decision = Settlement.recompute(converged(listOf(round)))
    assertTrue(decision is SettleDecision.Corrected, "expected Corrected but was $decision")
    assertEquals(mapOf("p1" to 25, "p2" to 33, "p3" to -11, "p4" to -2), decision.recomputed.finalScores)
    assertEquals(listOf("p2"), decision.recomputed.winnerIds)
  }

  @Test
  fun fastRoundUsesForcedTrumpAndCallerFromEstimates() {
    // FULL round 14 → forced SANS, no log entries at all. Highest
    // estimate (p1:4) becomes caller; p4 misses alone (sole loser −10):
    // p1 24, p2 13, p3 12, p4 −1−10 = −11.
    val winners = listOf("p1", "p1", "p1", "p1", "p2", "p2", "p2", "p3", "p3", "p4", "p4", "p4", "p4")
    val round = archived(14, winners, "SANS", emptyList(), mapOf("p1" to 4, "p2" to 3, "p3" to 2, "p4" to 3))
    val scored = Settlement.scoreRound(shellMatch(), round, "p1", 1, 8)
    assertTrue(scored != null, "fast round did not score")
    assertEquals(mapOf("p1" to 24, "p2" to 13, "p3" to 12, "p4" to -11), scored.deltas)
  }

  @Test
  fun saaydaChainDoublesThroughNextMultiplier() {
    // Round 1: everybody misses (Saayda) → zeros, multiplier → 2.
    // Round 2 (×2, total 16): p2 CALLER 24×2=48; p3/p4 WIZZ (triple
    // jump-in on the 4-call) 24×2=48 each; p1 takes one trick off a 4
    // (WIZZ_RISK miss −(3+10+10), SOLE loser −10 → −33×2 = −66).
    val r1Winners = listOf("p1", "p1", "p2", "p3", "p3", "p3", "p3", "p4", "p4", "p4", "p4", "p4", "p4")
    val r1Actions = listOf(
      dash("p1", false, 1), dash("p2", false, 1), dash("p3", false, 1), dash("p4", false, 1),
      auctionBid("p1", 4, "SPADES", 1),
      auctionPass("p2", 1), auctionPass("p3", 1), auctionPass("p4", 1),
      confirm("p1", 4, "SPADES", 1),
    )
    val r1 = archived(1, r1Winners, "SPADES", r1Actions, mapOf("p2" to 2, "p3" to 2, "p4" to 3))
    val r2Winners = listOf("p2", "p2", "p2", "p2", "p3", "p3", "p3", "p3", "p4", "p4", "p4", "p4", "p1")
    val r2Actions = listOf(
      dash("p2", false, 2), dash("p3", false, 2), dash("p4", false, 2), dash("p1", false, 2),
      auctionBid("p2", 4, "HEARTS", 2),
      auctionPass("p3", 2), auctionPass("p4", 2), auctionPass("p1", 2),
      confirm("p2", 4, "HEARTS", 2),
    )
    val r2 = archived(2, r2Winners, "HEARTS", r2Actions, mapOf("p3" to 4, "p4" to 4, "p1" to 4), "p2")
    val fields = matchFields(
      dealer = "u2",
      completedRound = 2,
      maxRounds = 2,
      finalScores = mapOf("p1" to -66, "p2" to 48, "p3" to 48, "p4" to 48),
      winnerIds = listOf("p2", "p3", "p4"),
    )
    val decision = Settlement.recompute(converged(listOf(r1, r2), fields))
    assertTrue(decision is SettleDecision.Agreed, "expected Agreed but was $decision")
    assertEquals(mapOf("p1" to -66, "p2" to 48, "p3" to 48, "p4" to 48), decision.recomputed.finalScores)
    assertEquals(listOf("p2", "p3", "p4"), decision.recomputed.winnerIds)
  }

  @Test
  fun tiedTopSettlesBothKings() {
    // Caller p1 4♠ misses (−12); p2/p3 hit 3s (13 each); p4 hits 5
    // off a 2 (RISK miss −3). Winners p2 + p3.
    val winners = listOf("p1", "p1", "p2", "p2", "p2", "p3", "p3", "p3", "p4", "p4", "p4", "p4", "p4")
    val actions = listOf(
      dash("p1", false, 1), dash("p2", false, 1), dash("p3", false, 1), dash("p4", false, 1),
      auctionBid("p1", 4, "SPADES", 1),
      auctionPass("p2", 1), auctionPass("p3", 1), auctionPass("p4", 1),
      confirm("p1", 4, "SPADES", 1),
    )
    val round = archived(1, winners, "SPADES", actions, mapOf("p2" to 3, "p3" to 3, "p4" to 2))
    val fields = matchFields(
      finalScores = mapOf("p1" to -12, "p2" to 13, "p3" to 13, "p4" to -3),
      winnerIds = listOf("p2", "p3"),
    )
    val decision = Settlement.recompute(converged(listOf(round), fields))
    assertIs<SettleDecision.Agreed>(decision)
    assertEquals(mapOf("p1" to -12, "p2" to 13, "p3" to 13, "p4" to -3), decision.recomputed.finalScores)
    assertEquals(listOf("p2", "p3"), decision.recomputed.winnerIds)
  }

  // ── fail-closed ───────────────────────────────────────────────────

  @Test
  fun missingArchiveIsIncomplete() {
    val match = converged(
      listOf(round1Golden()),
      matchFields(completedRound = 2, finalScores = mapOf("p1" to 0, "p2" to 0, "p3" to 0, "p4" to 0)),
    )
    val decision = Settlement.recompute(match)
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.HISTORY_INCOMPLETE, decision.reason)
  }

  @Test
  fun prefixArchiveIsIncompleteNotFabricated() {
    val parsed = Settlement.parseArchive(
      "1",
      archiveFields(1, playsFor(List(13) { "p1" }, "SPADES", 1, "p1"), emptyList()),
    ) ?: throw AssertionError("parse failed")
    assertNull(parsed.estimates)
    val decision = Settlement.recompute(converged(listOf(parsed)))
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.HISTORY_INCOMPLETE, decision.reason)
  }

  @Test
  fun shortCardLogIsCorrupt() {
    val full = playsFor(List(13) { "p1" }, "SPADES", 1, "p1")
    val parsed = Settlement.parseArchive(
      "1",
      archiveFields(1, full.dropLast(1), emptyList(), emptyMap<String, Int>()),
    ) ?: throw AssertionError("parse failed")
    val decision = Settlement.recompute(converged(listOf(parsed)))
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.HISTORY_CORRUPT, decision.reason)
  }

  @Test
  fun illegalEstimateIsCorrupt() {
    // p2 estimates 6 over the caller's cap of 5 — the engine rejects it.
    val round = round1Golden().copy(
      estimates = mapOf("p2" to 6, "p3" to 2, "p4" to 4),
    )
    val decision = Settlement.recompute(converged(listOf(round)))
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.HISTORY_CORRUPT, decision.reason)
  }

  @Test
  fun outOfTurnAuctionBidIsCorrupt() {
    // p2 opens the auction while the dealer (p1) holds it.
    val rigged = listOf(
      dash("p1", false, 1), dash("p2", false, 1), dash("p3", false, 1), dash("p4", false, 1),
      auctionBid("p2", 5, "SPADES", 1),
      auctionPass("p1", 1), auctionPass("p3", 1), auctionPass("p4", 1),
      confirm("p2", 5, "SPADES", 1),
    )
    val parsed = Settlement.parseArchive(
      "1",
      archiveFields(
        1,
        playsFor(List(13) { "p2" }, "SPADES", 1, "p1"),
        rigged,
        mapOf("p1" to 3, "p3" to 2, "p4" to 4),
      ),
    ) ?: throw AssertionError("parse failed")
    val decision = Settlement.recompute(converged(listOf(parsed)))
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.HISTORY_CORRUPT, decision.reason)
  }

  @Test
  fun malformedCardFailsParse() {
    val bad = mapOf(
      "seatId" to "p1",
      "card" to mapOf("suit" to "JOKER", "rank" to mapOf("v" to 15, "s" to "?")),
      "round" to 1,
    )
    assertNull(Settlement.parseCard(bad))
  }

  @Test
  fun claimedKeysMustCoverExactlyTheSeats() {
    val match = converged(
      listOf(round1Golden()),
      matchFields(finalScores = mapOf("p1" to 25, "p2" to 13, "p3" to 12)),
    )
    val decision = Settlement.recompute(match)
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.HISTORY_CORRUPT, decision.reason)
  }

  // ── mechanics ─────────────────────────────────────────────────────

  @Test
  fun dealerWalkBackFromFinalMarker() {
    val match = converged(emptyList(), matchFields(dealer = "u3", completedRound = 5))
    // Final marker p3 at round 5 → r4 p2, r3 p1, r2 p4, r1 p3.
    assertEquals("p3", Settlement.dealerSeatForRound(match, 5))
    assertEquals("p2", Settlement.dealerSeatForRound(match, 4))
    assertEquals("p1", Settlement.dealerSeatForRound(match, 3))
    assertEquals("p4", Settlement.dealerSeatForRound(match, 2))
    assertEquals("p3", Settlement.dealerSeatForRound(match, 1))
  }

  @Test
  fun unresolvableDealerIsNull() {
    val match = converged(emptyList(), matchFields(dealer = "ghost"))
    assertNull(Settlement.dealerSeatForRound(match, 1))
  }

  @Test
  fun noOpResponseShape() {
    val response = noOpResponse("ROOM")
    assertEquals(true, response["ok"])
    assertEquals(false, response["settled"])
    assertEquals("NOT_RANKED", response["reason"])
    assertEquals("ROOM", response["mode"])
  }

  @Test
  fun plainBridgingRoundTrips() {
    val nested: Map<String, Any?> = mapOf("a" to 1, "b" to listOf(1, 2), "c" to mapOf("d" to "x"))
    val plain = toPlain(nested)
    assertEquals("{\"a\":1,\"b\":[1,2],\"c\":{\"d\":\"x\"}}", js("JSON.stringify(plain)") as String)
    assertEquals(nested, dynamicToKotlin(plain))
  }

  /**
   * `retryAfterMillis` is a Long, and on Kotlin/JS a Long is a Number subclass
   * with own enumerable low/high fields and no toJSON. Before the special case
   * it serialized as `{"low_1":..,"high_1":..}`, the client's `as? Number`
   * failed, and every NotYetExpired fell back to the constant retry delay —
   * which the instrumented suite saw as a deadline that never arrived.
   */
  @Test
  fun aLongSerializesAsANumberNotALowHighObject() {
    val plain = toPlain(mapOf("retryAfterMillis" to 1_000L))

    assertEquals("""{"retryAfterMillis":1000}""", js("JSON.stringify(plain)") as String)
    // The client reads it back as a Number, so the round trip must stay numeric.
    val roundTripped = (dynamicToKotlin(plain) as Map<*, *>)["retryAfterMillis"]
    assertEquals(1_000L, (roundTripped as Number).toLong())
  }

  // ── orchestration (fake ports) ────────────────────────────────────

  private class FakeDb(
    var match: Map<String, Any?>?,
    var archives: Map<String, Map<String, Any?>> = emptyMap(),
    var claim: Map<String, Any?>? = null,
  ) : SettlementDb {
    var archiveReads = 0
    var txRuns = 0
    var corrections = 0
    var claims = 0

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = match

    override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
      archiveReads++
      return archives
    }

    override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
      txRuns++
      return block(object : SettlementDb.SettlementTx {
        override suspend fun readClaim(matchId: String): Map<String, Any?>? = claim

        override fun writeCorrection(
          matchId: String,
          scores: Map<String, Int>,
          winners: List<String>,
          version: Int,
        ) {
          corrections++
        }

        override fun writeClaim(matchId: String, record: Map<String, Any?>) {
          claims++
          @Suppress("UNCHECKED_CAST")
          val outcome = record["outcome"] as Map<String, Any?>
          claim = mapOf("outcome" to outcome, "settledBy" to (record["settledBy"] as String))
        }
      })
    }
  }

  // The adapters deliver the payload as a PLAIN JS OBJECT, never a Kotlin
  // map — `mapOf` here would let a broken `as? Map` cast pass. json() is
  // the runtime shape.
  private fun settleData(matchId: Any?): Any? = json("matchId" to matchId)

  @Test
  fun firstSettleCorrectsAndRecords() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    val response = settleOnce(db, "u1", settleData("m1"))
    assertTrue(response["ok"] == true, "expected ok response but was $response")
    assertTrue(response["corrected"] == true, "expected corrected response but was $response")
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), response["finalScores"])
    assertEquals(1, db.corrections)
    assertEquals(1, db.claims)

    // Replay: recorded outcome returns, nothing rewrites.
    val replay = settleOnce(db, "u2", settleData("m1"))
    assertTrue(replay["replayed"] == true, "expected replay but was $replay")
    assertTrue(replay["corrected"] == true, "expected corrected replay but was $replay")
    assertEquals(1, db.corrections)
    assertEquals(1, db.claims)
    assertEquals(2, db.txRuns)
  }

  @Test
  fun agreedSettleRecordsWithoutCorrection() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    val response = settleOnce(db, "u1", settleData("m1"))
    assertTrue(response["corrected"] == false, "expected agreed response but was $response")
    assertEquals(0, db.corrections)
    assertEquals(1, db.claims)
  }

  @Test
  fun aSettlerOutsideTheMatchIsDenied() = runTest {
    // Authentication itself happens at the adapter (callable-auth / the
    // Worker's bearer gate) before this orchestration runs; what remains
    // here is authorization — the caller must be a player in the match.
    val db = FakeDb(match = matchFields())
    try {
      settleOnce(db, "not-a-player", settleData("m1"))
      throw AssertionError("expected AuthorityError")
    } catch (e: AuthorityError) {
      assertEquals("permission-denied", e.code)
    }
  }

  @Test
  fun aMissingMatchIdIsRejectedBeforeAnyRead() = runTest {
    val db = FakeDb(match = matchFields())
    try {
      settleOnce(db, "u1", json("matchId" to null))
      throw AssertionError("expected AuthorityError")
    } catch (e: AuthorityError) {
      assertEquals("invalid-argument", e.code)
    }
    // The rejection happens before the storage layer is touched.
    assertEquals(0, db.txRuns)
  }

  @Test
  fun nonRankedSkipsReadsAndWrites() = runTest {
    val db = FakeDb(match = matchFields(mode = "ROOM"))
    val response = settleOnce(db, "u1", settleData("m1"))
    assertEquals(false, response["settled"])
    assertEquals(0, db.archiveReads)
    assertEquals(0, db.txRuns)
  }
}

