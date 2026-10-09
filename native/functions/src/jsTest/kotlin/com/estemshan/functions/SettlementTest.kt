package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.functions.Settlement.ArchivedRound
import com.estemshan.functions.Settlement.ConvergedMatch
import com.estemshan.functions.Settlement.SettleDecision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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

  // ── raw Firestore-shaped builders ─────────────────────────────────

  private fun rankS(v: Int): String = when (v) {
    11 -> "J"
    12 -> "Q"
    13 -> "K"
    14 -> "A"
    else -> "$v"
  }

  private fun card(seat: String, suit: String, v: Int, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "card" to mapOf("suit" to suit, "rank" to mapOf("v" to v, "s" to rankS(v))),
      "round" to round,
    )

  private fun dash(seat: String, declared: Boolean, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitDashCallDecision",
      "declaredDashCall" to declared,
      "round" to round,
    )

  private fun auctionBid(seat: String, tricks: Int, suit: String, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitAuctionBid",
      "isPass" to false,
      "tricks" to tricks,
      "suit" to suit,
      "round" to round,
    )

  private fun auctionPass(seat: String, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitAuctionBid",
      "isPass" to true,
      "round" to round,
    )

  private fun confirm(seat: String, tricks: Int, suit: String, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitConfirmCall",
      "tricks" to tricks,
      "suit" to suit,
      "round" to round,
    )

  /**
   * Builds 52 plays for 13 tricks: [winners] is the winner seat per
   * trick in play order (must total the intended tricksWon). Two
   * phases, so filler assignment can never starve a future winner:
   * first every winner takes the highest remaining card of the
   * winning suit (trump, or led when trump is SANS), then every other
   * slot takes the lowest remaining card that cannot beat its trick's
   * winner. Led suit cycles S/H/D/C; play order is p1..p4.
   */
  private fun playsFor(winners: List<String>, trump: String, round: Int): List<Map<String, Any?>> {
    require(winners.size == 13)
    val order = listOf("p1", "p2", "p3", "p4")
    val pools = mutableMapOf<String, ArrayDeque<Int>>()
    for (suit in listOf("SPADES", "HEARTS", "DIAMONDS", "CLUBS")) {
      pools[suit] = ArrayDeque((14 downTo 2).toList())
    }
    val ledCycle = listOf("SPADES", "HEARTS", "DIAMONDS", "CLUBS")
    val winSuitOf = winners.indices.map { if (trump == "SANS") ledCycle[it % 4] else trump }
    val winValue = HashMap<Int, Int>()
    for (trick in winners.indices) {
      winValue[trick] = pools.getValue(winSuitOf[trick]).removeFirst()
    }
    val plays = ArrayList<Map<String, Any?>>()
    winners.forEachIndexed { trick, winner ->
      val winSuit = winSuitOf[trick]
      val winV = winValue.getValue(trick)
      for (seat in order) {
        if (seat == winner) {
          plays.add(card(seat, winSuit, winV, round))
        } else {
          val fallback = pools.entries
            .filter { it.key != winSuit && it.value.isNotEmpty() }
            .minByOrNull { it.value.last() }
            ?: pools.entries.first { it.value.isNotEmpty() }
          val suit = fallback.key
          plays.add(card(seat, suit, fallback.value.removeLast(), round))
        }
      }
    }
    assertEquals(52, plays.size)
    return plays
  }

  private fun matchFields(
    status: String = "complete",
    mode: Any? = "RANKED",
    gameType: Any? = "FULL",
    scoringMode: Any? = "NORMAL",
    seats: Map<String, String> = mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4"),
    players: List<String> = listOf("u1", "u2", "u3", "u4"),
    dealer: Any? = "u1",
    completedRound: Any? = 1,
    // Synthetic shortened matches: completedRound ends the match, so the
    // default pairs 1/1 (mirrors endMatch's completedRound+1 > maxRounds).
    maxRounds: Any? = 1,
    finalScores: Any? = mapOf("p1" to 25, "p2" to 13, "p3" to 12, "p4" to 14),
    winnerIds: Any? = listOf("p1"),
    version: Any? = 7,
  ): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["roomId"] = "r1"
    out["players"] = players
    out["status"] = status
    out["currentRound"] = completedRound
    out["maxRounds"] = maxRounds
    out["dealer"] = dealer
    out["seats"] = seats
    out["version"] = version
    if (mode !== ABSENT) out["mode"] = mode
    if (gameType !== ABSENT) out["gameType"] = gameType
    if (scoringMode !== ABSENT) out["scoringMode"] = scoringMode
    if (completedRound !== ABSENT) out["completedRound"] = completedRound
    if (maxRounds !== ABSENT) out["maxRounds"] = maxRounds
    if (finalScores !== ABSENT) out["finalScores"] = finalScores
    if (winnerIds !== ABSENT) out["winnerIds"] = winnerIds
    return out
  }

  private companion object {
    /** Sentinel for "key absent" (distinct from an explicit null). */
    val ABSENT = object {}
  }

  private fun archiveFields(
    round: Int,
    plays: List<Map<String, Any?>>,
    actions: List<Map<String, Any?>>,
    estimates: Any? = ABSENT,
  ): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["round"] = round
    out["matchId"] = "m1"
    out["cardLog"] = plays
    out["biddingLog"] = actions
    if (estimates !== ABSENT) out["estimates"] = estimates
    return out
  }

  private fun shellMatch(order: List<String> = listOf("p1", "p2", "p3", "p4")) = ConvergedMatch(
    matchId = "m1",
    mode = MatchMode.RANKED,
    status = "complete",
    order = order,
    players = listOf("u1", "u2", "u3", "u4"),
    dealerSeatFinal = "p1",
    gameType = GameType.FULL,
    classic = false,
    completedRound = 1,
    maxRounds = 18,
    claimedScores = null,
    claimedWinners = null,
    archives = emptyList(),
  )

  private fun converged(
    archives: List<ArchivedRound>,
    fields: Map<String, Any?> = matchFields(),
  ): ConvergedMatch {
    val parsed = Settlement.parseMatch("m1", fields)
      ?: throw AssertionError("fixture match did not parse")
    return parsed.copy(archives = archives)
  }

  private fun archived(
    round: Int,
    winners: List<String>,
    trump: String,
    actions: List<Map<String, Any?>>,
    estimates: Map<String, Int>?,
  ): ArchivedRound {
    val parsed = Settlement.parseArchive(
      "$round",
      archiveFields(round, playsFor(winners, trump, round), actions, estimates ?: ABSENT),
    ) ?: throw AssertionError("fixture archive $round did not parse")
    // Self-validating fixture: the replay must re-derive the intended wins.
    val tricks = Settlement.replayTricks(shellMatch(), parsed, Suit.valueOf(trump))
      ?: throw AssertionError("fixture tricks did not replay")
    assertEquals(winners.groupingBy { it }.eachCount(), tricks.filterValues { it > 0 })
    return parsed
  }

  /** Round-1 golden parts: caller p1 5♠; est p2:3, p3:2, p4:3 (total 14);
   *  all succeed, no sole → p1:25, p2:13, p3:12, p4:14. */
  private data class RoundFixture(
    val winners: List<String>,
    val plays: List<Map<String, Any?>>,
    val actions: List<Map<String, Any?>>,
    val estimates: Map<String, Int>,
  )

  private fun round1Parts(): RoundFixture {
    val winners = listOf("p1", "p1", "p1", "p1", "p1", "p2", "p2", "p2", "p3", "p3", "p4", "p4", "p4")
    val plays = playsFor(winners, "SPADES", 1)
    val actions = listOf(
      dash("p1", false, 1), dash("p2", false, 1), dash("p3", false, 1), dash("p4", false, 1),
      auctionBid("p1", 5, "SPADES", 1),
      auctionPass("p2", 1), auctionPass("p3", 1), auctionPass("p4", 1),
      confirm("p1", 5, "SPADES", 1),
    )
    return RoundFixture(winners, plays, actions, mapOf("p2" to 3, "p3" to 2, "p4" to 4))
  }

  private fun round1Golden(): ArchivedRound {
    val parts = round1Parts()
    return archived(1, parts.winners, "SPADES", parts.actions, parts.estimates)
  }

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
    assertEquals(mapOf("p1" to 25, "p2" to 13, "p3" to 12, "p4" to 14), decision.recomputed.finalScores)
    assertEquals(listOf("p1"), decision.recomputed.winnerIds)
  }

  @Test
  fun correctedWhenClaimDisagrees() {
    val forged = matchFields(finalScores = mapOf("p1" to 25, "p2" to 23, "p3" to 12, "p4" to 14))
    val decision = Settlement.recompute(converged(listOf(round1Golden()), forged))
    assertTrue(decision is SettleDecision.Corrected, "expected Corrected but was $decision")
    // The recomputed value is what settles — p2 drops back to 13.
    assertEquals(mapOf("p1" to 25, "p2" to 13, "p3" to 12, "p4" to 14), decision.recomputed.finalScores)
    assertEquals(listOf("p1"), decision.recomputed.winnerIds)
    assertEquals(mapOf("p1" to 25, "p2" to 23, "p3" to 12, "p4" to 14), decision.recomputed.claimedScores)
  }

  @Test
  fun withAndDashCallDeriveFromReplay() {
    // Caller p1 5♠; p3 matched the suit in-auction (With); p2 dash-called;
    // total 12 (under): p1 25, p2 DASHCALL +33, p3 WIZZ 25, p4 12.
    // Match King is p2 — the dash caller — not the match caller.
    val winners = listOf("p1", "p1", "p1", "p1", "p1", "p3", "p3", "p3", "p3", "p3", "p4", "p4", "p4")
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
    assertEquals(mapOf("p1" to 25, "p2" to 33, "p3" to 25, "p4" to 12), decision.recomputed.finalScores)
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
    // Round 2 (×2, total 12): p2 CALLER 23×2=46, p3 13×2=26,
    // p4 13×2=26, p1 RISK-miss −1×2=−2.
    val r1Winners = listOf("p1", "p1", "p2", "p3", "p3", "p3", "p3", "p4", "p4", "p4", "p4", "p4", "p4")
    val r1Actions = listOf(
      dash("p1", false, 1), dash("p2", false, 1), dash("p3", false, 1), dash("p4", false, 1),
      auctionBid("p1", 4, "SPADES", 1),
      auctionPass("p2", 1), auctionPass("p3", 1), auctionPass("p4", 1),
      confirm("p1", 4, "SPADES", 1),
    )
    val r1 = archived(1, r1Winners, "SPADES", r1Actions, mapOf("p2" to 2, "p3" to 2, "p4" to 3))
    val r2Winners = listOf("p2", "p2", "p2", "p3", "p3", "p3", "p4", "p4", "p4", "p1", "p1", "p1", "p1")
    val r2Actions = listOf(
      dash("p2", false, 2), dash("p3", false, 2), dash("p4", false, 2), dash("p1", false, 2),
      auctionBid("p2", 3, "HEARTS", 2),
      auctionPass("p3", 2), auctionPass("p4", 2), auctionPass("p1", 2),
      confirm("p2", 3, "HEARTS", 2),
    )
    val r2 = archived(2, r2Winners, "HEARTS", r2Actions, mapOf("p3" to 3, "p4" to 3, "p1" to 3))
    val fields = matchFields(
      dealer = "u2",
      completedRound = 2,
      maxRounds = 2,
      finalScores = mapOf("p1" to -2, "p2" to 46, "p3" to 26, "p4" to 26),
      winnerIds = listOf("p2"),
    )
    val decision = Settlement.recompute(converged(listOf(r1, r2), fields))
    assertTrue(decision is SettleDecision.Agreed, "expected Agreed but was $decision")
    assertEquals(mapOf("p1" to -2, "p2" to 46, "p3" to 26, "p4" to 26), decision.recomputed.finalScores)
    assertEquals(listOf("p2"), decision.recomputed.winnerIds)
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
      archiveFields(1, playsFor(List(13) { "p1" }, "SPADES", 1), emptyList()),
    ) ?: throw AssertionError("parse failed")
    assertNull(parsed.estimates)
    val decision = Settlement.recompute(converged(listOf(parsed)))
    assertIs<SettleDecision.Failed>(decision)
    assertEquals(Settlement.Reason.HISTORY_INCOMPLETE, decision.reason)
  }

  @Test
  fun shortCardLogIsCorrupt() {
    val full = playsFor(List(13) { "p1" }, "SPADES", 1)
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
        playsFor(List(13) { "p2" }, "SPADES", 1),
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

  private fun settleRequest(uid: String?, matchId: Any?): CallableRequest =
    object : CallableRequest {
      override val data: Any? = mapOf("matchId" to matchId)
      override val auth: AuthData? = uid?.let { object : AuthData {
        override val uid: String = it
        override val token: AuthToken? = null
      } }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }

  private fun rawRound(round: ArchivedRound, plays: List<Map<String, Any?>>, actions: List<Map<String, Any?>>): Map<String, Any?> {
    val estimates: Any? = round.estimates ?: ABSENT
    return archiveFields(round.round, plays, actions, estimates)
  }

  @Test
  fun firstSettleCorrectsAndRecords() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(finalScores = mapOf("p1" to 25, "p2" to 23, "p3" to 12, "p4" to 14)),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["ok"] == true, "expected ok response but was $response")
    assertTrue(response["corrected"] == true, "expected corrected response but was $response")
    assertEquals(mapOf("p1" to 25, "p2" to 13, "p3" to 12, "p4" to 14), response["finalScores"])
    assertEquals(1, db.corrections)
    assertEquals(1, db.claims)

    // Replay: recorded outcome returns, nothing rewrites.
    val replay = settleOnce(db, settleRequest("u2", "m1"))
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
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["corrected"] == false, "expected agreed response but was $response")
    assertEquals(0, db.corrections)
    assertEquals(1, db.claims)
  }

  @Test
  fun unauthenticatedSettleThrows() = runTest {
    val db = FakeDb(match = matchFields())
    try {
      settleOnce(db, settleRequest(null, "m1"))
      throw AssertionError("expected HttpsError")
    } catch (e: Throwable) {
      assertEquals("unauthenticated", e.asDynamic().code as String)
    }
  }

  @Test
  fun nonRankedSkipsReadsAndWrites() = runTest {
    val db = FakeDb(match = matchFields(mode = "ROOM"))
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertEquals(false, response["settled"])
    assertEquals(0, db.archiveReads)
    assertEquals(0, db.txRuns)
  }
}

