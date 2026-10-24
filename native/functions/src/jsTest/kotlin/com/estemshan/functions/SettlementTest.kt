package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.functions.Settlement.ArchivedRound
import com.estemshan.functions.Settlement.ConvergedMatch
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
  private fun nextSeat(seat: String): String {
    val order = listOf("p1", "p2", "p3", "p4")
    return order[(order.indexOf(seat) + 1) % order.size]
  }

  private fun playsFor(
    winners: List<String>,
    trump: String,
    round: Int,
    leaderId: String,
  ): List<Map<String, Any?>> {
    require(winners.size == 13)
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
    var leader = leaderId
    winners.forEachIndexed { trick, winner ->
      val winSuit = winSuitOf[trick]
      val winV = winValue.getValue(trick)
      val led = ledCycle[trick % 4]
      var seat = leader
      repeat(4) {
        if (seat == winner) {
          plays.add(card(seat, winSuit, winV, round))
        } else if (seat == leader && trump == "SANS") {
          val pool = pools.getValue(led)
          plays.add(card(seat, led, pool.removeLast(), round))
        } else {
          val fallback = pools.entries
            .filter { it.key != winSuit && it.value.isNotEmpty() }
            .minByOrNull { it.value.last() }
            ?: pools.entries.first { it.value.isNotEmpty() }
          plays.add(card(seat, fallback.key, fallback.value.removeLast(), round))
        }
        seat = nextSeat(seat)
      }
      leader = winner
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
    finalScores: Any? = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14),
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
    leaderId: String = "p1",
  ): ArchivedRound {
    val parsed = Settlement.parseArchive(
      "$round",
      archiveFields(round, playsFor(winners, trump, round, leaderId), actions, estimates ?: ABSENT),
    ) ?: throw AssertionError("fixture archive $round did not parse")
    // Self-validating fixture: the replay must re-derive the intended wins.
    val tricks = Settlement.replayTricks(shellMatch(), parsed, Suit.valueOf(trump))
      ?: throw AssertionError("fixture tricks did not replay")
    assertEquals(winners.groupingBy { it }.eachCount(), tricks.filterValues { it > 0 })
    return parsed
  }

  /** Round-1 golden parts: caller p1 5♠; est p2:3, p3:2, p4:4 (total
   *  14, over); won 5/2/2/4 — p2 alone misses (sole loser): p1 CALLER 25,
   *  p2 −1−10 = −11, p3 12, p4 RISK win 14. (Completed totals never equal
   *  13 — the forbidden-13 rule — so all-succeed is unreachable; at least
   *  one seat always misses.) */
  private data class RoundFixture(
    val winners: List<String>,
    val plays: List<Map<String, Any?>>,
    val actions: List<Map<String, Any?>>,
    val estimates: Map<String, Int>,
  )

  private fun round1Parts(): RoundFixture {
    val winners = listOf("p1", "p1", "p1", "p1", "p1", "p2", "p2", "p3", "p3", "p4", "p4", "p4", "p4")
    val plays = playsFor(winners, "SPADES", 1, "p1")
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
  fun fastRoundExactDuplicateDeclarerIsWithOnReplay() {
    // A8.1/A7.2: FULL round 14 is fixed SANS. p1 and p3 both declare 4 under
    // that trump — an exact duplicate, which is legal. p1 declares first, so
    // p1 is Caller and p3 is With; declaration order (not seat strength)
    // decides only the exact duplicate. p2 3, p4 3. Won 4/3/2/4 (total 13):
    // caller p1 24, p2 13, With p3 misses by 2 → −(2+10) = −12, Risk p4
    // misses by 1 with a zero ladder → −1. If p3 had been treated as an
    // ordinary seat the replay would score −2, so the With set is load-
    // bearing on the settlement result.
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3",
      "p4", "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SANS", emptyList(),
      mapOf("p1" to 4, "p2" to 3, "p3" to 4, "p4" to 3),
    )
    val scored = Settlement.scoreRound(shellMatch(), round, "p1", 1, 8)
    assertTrue(scored != null, "fast duplicate round did not score")
    assertEquals(mapOf("p1" to 24, "p2" to 13, "p3" to -12, "p4" to -1), scored.deltas)
  }

  @Test
  fun fastSuperCallReplaysAfterPreSuperSeatRedeclares() {
    // p2's 8 triggers the Super Call and confirms SANS at 8. p1 (before p2)
    // is wiped and re-declares 8 under the final trump, so the authoritative
    // With set is [p1]. The archive only keeps the FINAL estimates — p1's
    // original number is gone — so this exercises the rebuild from the
    // logged CONFIRM rather than a straight estimate replay.
    // Bids 8/8/3/2 total 21 → Risk ladder 30, Risk player p1 (the last
    // re-declaration). Won 4/3/3/3: p1 WIZZ_RISK misses 4 → −44; p2
    // SUPER_CALL misses 5 → −32; p3 sole winner 13+10 = 23; p4 misses 1
    // → −1. If p1 were not With, its With bonus would vanish (−34).
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3", "p3",
      "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SANS",
      listOf(confirm("p2", 8, "SANS", 14)),
      mapOf("p1" to 8, "p2" to 8, "p3" to 3, "p4" to 2),
      leaderId = "p2",
    )
    val scored = Settlement.scoreRound(shellMatch(), round, "p1", 1, 8)
    assertTrue(scored != null, "fast Super Call replay must settle")
    assertEquals(mapOf("p1" to -44, "p2" to -32, "p3" to 23, "p4" to -1), scored.deltas)
  }

  @Test
  fun fastSuperCallWithNoPreSuperSeatsCompletesAtConfirm() {
    // The Super Caller is first in bidding order, so no seat is wiped: the
    // CONFIRM completes the auction and every archived estimate has already
    // been materialized. p2's matching 8 SANS is With (p1 Caller). Bids
    // 8/8/3/2 total 21 → Risk ladder 30, Risk player p4 (last estimate).
    // Won 4/3/3/3: p1 SUPER_CALL misses 4 → −32; p2 WIZZ misses 5 → −15;
    // p3 sole winner 23; p4 RISK misses 1 → −31.
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3", "p3",
      "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SANS",
      listOf(confirm("p1", 8, "SANS", 14)),
      mapOf("p1" to 8, "p2" to 8, "p3" to 3, "p4" to 2),
    )
    val scored = Settlement.scoreRound(shellMatch(), round, "p1", 1, 8)
    assertTrue(scored != null, "fast Super Call with no wipe must settle")
    assertEquals(mapOf("p1" to -32, "p2" to -15, "p3" to 23, "p4" to -31), scored.deltas)
  }

  @Test
  fun fastSuperCallConfirmMayRaiseAboveTheCallerEstimate() {
    // The engine allows the Super Caller to confirm ABOVE their winning
    // number (only lowering is illegal). p2 declared 8, then confirms 9 SANS;
    // p1 (before p2) is wiped and re-declares 8. Bids 8/9/3/2 total 22 →
    // Risk ladder 30, Risk player p1 (last re-declaration). Won 4/3/3/3:
    // p1 RISK misses 4 → −34; p2 SUPER_CALL 9 misses 6 → −41 (raising the
    // call from 8 would have scored −32, so the confirmed number is load-
    // bearing); p3 sole winner 23; p4 misses 1 → −1.
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3", "p3",
      "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SANS",
      listOf(confirm("p2", 9, "SANS", 14)),
      mapOf("p1" to 8, "p2" to 8, "p3" to 3, "p4" to 2),
      leaderId = "p2",
    )
    val scored = Settlement.scoreRound(shellMatch(), round, "p1", 1, 8)
    assertTrue(scored != null, "a raising fast Super Call must settle")
    assertEquals(mapOf("p1" to -34, "p2" to -41, "p3" to 23, "p4" to -1), scored.deltas)
  }

  @Test
  fun fastSuperCallConfirmBelowTheCallerEstimateFailsClosed() {
    // Impossible archive: p2's estimate is 9 (the winning declaration), but
    // the logged CONFIRM says 8. The live engine rejects "can't lower your
    // winning call", so settlement must refuse rather than replay a lower
    // number the caller never confirmed.
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3", "p3",
      "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SANS",
      listOf(confirm("p2", 8, "SANS", 14)),
      mapOf("p1" to 8, "p2" to 9, "p3" to 3, "p4" to 2),
      leaderId = "p2",
    )
    assertNull(
      Settlement.scoreRound(shellMatch(), round, "p1", 1, 8),
      "a lowering fast Super Call CONFIRM must fail closed",
    )
  }

  @Test
  fun fastSuperCallWithSubEightCallerEstimateFailsClosed() {
    // Impossible archive: p2 is named as the CONFIRM's caller but their
    // archived estimate is 7, below the >=8 Super Call trigger. A seat that
    // never declared 8+ cannot have triggered the call, so fail closed.
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3", "p3",
      "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SANS",
      listOf(confirm("p2", 8, "SANS", 14)),
      mapOf("p1" to 8, "p2" to 7, "p3" to 3, "p4" to 2),
      leaderId = "p2",
    )
    assertNull(
      Settlement.scoreRound(shellMatch(), round, "p1", 1, 8),
      "a sub-8 fast Super Call CONFIRM caller must fail closed",
    )
  }

  @Test
  fun fastSuperCallConfirmWithHigherLaterDeclarationFailsClosed() {
    // Impossible/forged archive: p3 declared 13, so p2's 8 cannot have been
    // the winning pre-confirm declaration. The caller won the auction, so no
    // seat after them can hold a higher number; accepting this would replay
    // p3's 13 as an ordinary bid under a caller who never won.
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3", "p3",
      "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SANS",
      listOf(confirm("p2", 8, "SANS", 14)),
      mapOf("p1" to 8, "p2" to 8, "p3" to 13, "p4" to 2),
      leaderId = "p2",
    )
    assertNull(
      Settlement.scoreRound(shellMatch(), round, "p1", 1, 8),
      "a CONFIRM naming a caller who did not win the auction must fail closed",
    )
  }

  @Test
  fun fastSuperCallTrumpChangeKeepsOldTrumpSeatOutOfWith() {
    // Round 14's fixed trump is SANS. p2's 8 triggers the call and confirms
    // SPADES; p1 (before p2) re-declares 8 under the new trump and is With.
    // p3's 8 was declared under SANS and is KEPT, so it is not With even at
    // the final number. Bids 8/8/8/3 total 27 → Risk ladder 30; risk is p1
    // (last re-declaration). Won 4/3/3/3: p1 WIZZ_RISK misses 4 → −44;
    // p2 SUPER_CALL misses 5 → −32; p3 misses 5 → −5; p4 sole winner 23.
    // If the trump change were ignored, p3 would be With and score −15.
    val winners = listOf(
      "p1", "p1", "p1", "p1",
      "p2", "p2", "p2",
      "p3", "p3", "p3",
      "p4", "p4", "p4",
    )
    val round = archived(
      14, winners, "SPADES",
      listOf(confirm("p2", 8, "SPADES", 14)),
      mapOf("p1" to 8, "p2" to 8, "p3" to 8, "p4" to 3),
      leaderId = "p2",
    )
    val scored = Settlement.scoreRound(shellMatch(), round, "p1", 1, 8)
    assertTrue(scored != null, "a changed-trump fast Super Call must settle")
    assertEquals(mapOf("p1" to -44, "p2" to -32, "p3" to -5, "p4" to 23), scored.deltas)
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
    /** Seated profiles by uid. A uid absent here still reads as an existing,
     *  unplaced profile — the way a real `players/{uid}` doc with no `rp`. */
    var profiles: Map<String, Map<String, Any?>> = emptyMap(),
    /** Uids whose profile DOCUMENT is absent (the fail-closed case). */
    var missingProfiles: Set<String> = emptySet(),
  ) : SettlementDb {
    var archiveReads = 0
    var txRuns = 0
    var corrections = 0
    var claims = 0
    var profileReads = 0
    var appendAttempts = 0
    var appendThrows = false
    val appends = mutableListOf<Map<String, Any?>>()
    var recentReads = 0
    var recentCorrections: List<Map<String, Any?>>? = null
    /** Every profile write, in call order: (uid, merged fields). */
    val profileWrites = mutableListOf<Pair<String, Map<String, Any?>>>()
    /** Port calls in order, so the reads-before-writes rule is observable. */
    val events = mutableListOf<String>()

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = match

    override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
      archiveReads++
      return archives
    }

    override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
      txRuns++
      return block(object : SettlementDb.SettlementTx {
        override suspend fun readClaim(matchId: String): Map<String, Any?>? {
          events.add("read-claim")
          return claim
        }

        override suspend fun readProfile(uid: String): Map<String, Any?>? {
          profileReads++
          events.add("read-profile:$uid")
          // The Admin SDK throws on a read issued after a write in one
          // transaction, so the fake refuses it too: a fake that allowed the
          // order would hide the only bug class this port shape exists to stop.
          if (events.any { it.startsWith("write-") }) {
            throw IllegalStateException(
              "Firestore transactions require all reads to be executed before all writes",
            )
          }
          if (uid in missingProfiles) return null
          return profiles[uid] ?: emptyMap()
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
        }

        override fun writeClaim(matchId: String, record: Map<String, Any?>) {
          events.add("write-claim")
          claims++
          // The whole record is kept, movements included: a replay has to be
          // able to slice the caller's own movement back out of it, exactly as
          // the claim document allows in Firestore.
          claim = record
        }
      })
    }

    override suspend fun appendLedger(record: Map<String, Any?>) {
      appendAttempts++
      if (appendThrows) throw IllegalStateException("ledger append failed (test)")
      appends.add(record)
    }

    override suspend fun readCorrectionWindow(): List<Map<String, Any?>> {
      recentReads++
      // Unset answers the way AdminSettlementDb does: newest-first over the
      // CORRECTED rows only (what appendTargets would have materialized), so
      // the window provably contains the record the correction just wrote and
      // is not diluted by the agreed settles around it.
      return recentCorrections ?: appends.filter {
        it["outcomeKind"] == SettlementLedger.OUTCOME_CORRECTED
      }.reversed()
    }
  }

  private fun settleRequest(uid: String?, matchId: Any?): CallableRequest =
    object : CallableRequest {
      // The Functions runtime delivers data as a PLAIN JS OBJECT, never a Kotlin
      // map — `mapOf` here would let a broken `as? Map` cast pass. json() is
      // the runtime shape; matchId is Any? because one caller probes a null id.
      override val data: Any? = json("matchId" to matchId)
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
      match = matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["ok"] == true, "expected ok response but was $response")
    assertTrue(response["corrected"] == true, "expected corrected response but was $response")
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), response["finalScores"])
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
  fun crossTierRankedSettlementAwardsNoRp() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
      profiles = mapOf(
        "u1" to mapOf("rp" to 30), // Bronze III
        "u2" to mapOf("rp" to 600), // Gold III
        "u3" to mapOf("rp" to 640), // Gold III
        "u4" to mapOf("rp" to 700), // Gold II
      ),
    )
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["settled"] == true, "expected a settlement but was $response")
    assertEquals(1, db.claims)

    val movements = db.claim!!["movements"] as Map<*, *>
    val deltas = movements.values.map { (it as Map<*, *>)["delta"] as Int }
    assertTrue(
      deltas.all { it == 0 },
      "a Bronze-to-Gold table is two tiers apart and must award no RP, was $movements",
    )

    val writes = db.profileWrites.toMap()
    assertEquals(30, writes.getValue("u1")["rp"], "Bronze RP must not move")
    assertEquals(600, writes.getValue("u2")["rp"], "Gold RP must not move")
    assertEquals(640, writes.getValue("u3")["rp"], "Gold RP must not move")
    assertEquals(700, writes.getValue("u4")["rp"], "Gold RP must not move")
    assertEquals("Bronze", writes.getValue("u1")["tier"])
    assertEquals("Gold", writes.getValue("u2")["tier"])
    // The match still settles and folds statistics; only progression is held.
    assertEquals(1, writes.getValue("u1")["rankedGamesPlayed"])
  }

  @Test
  fun oneTierGapStillAwardsRp() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
      profiles = mapOf(
        "u1" to mapOf("rp" to 30), // Bronze III
        "u2" to mapOf("rp" to 300), // Silver III
        "u3" to mapOf("rp" to 350), // Silver II
        "u4" to mapOf("rp" to 400), // Silver II
      ),
    )
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["settled"] == true, "expected a settlement but was $response")
    val movements = db.claim!!["movements"] as Map<*, *>
    val deltas = movements.values.map { (it as Map<*, *>)["delta"] as Int }
    assertTrue(
      deltas.any { it != 0 },
      "one tier apart is RD29-eligible and must still award RP, was $movements",
    )
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

  // -- S62 ledger (fake ports) --------------------------------------

  @Test
  fun correctedSettleAppendsLedgerOnceAndReplayAppendsNever() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["settled"] == true, "expected a settlement but was $response")
    assertEquals(1, db.appendAttempts)
    assertEquals(1, db.appends.size)
    val record = db.appends[0]
    assertEquals("CORRECTED", record["outcomeKind"])
    assertEquals("m1", record["matchId"])
    assertEquals("u1", record["settledBy"])
    assertEquals("RANKED", record["mode"])
    assertEquals("FULL", record["gameType"])
    assertEquals(listOf("u1", "u2", "u3", "u4"), record["players"])
    // The corrected hook ran the bounded detector once.
    assertEquals(1, db.recentReads)

    // Retry of the settled match: the replay path is inert for the ledger.
    val replay = settleOnce(db, settleRequest("u2", "m1"))
    assertTrue(replay["replayed"] == true, "expected a replay but was $replay")
    assertEquals(1, db.appendAttempts)
    assertEquals(1, db.appends.size)
    assertEquals(1, db.recentReads)
    assertEquals(1, db.claims)
    assertEquals(1, db.corrections)
  }

  @Test
  fun agreedSettleAppendsAppliedRecordWithoutDetector() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["corrected"] == false, "expected an agreed settle but was $response")
    assertEquals(1, db.appends.size)
    assertEquals("APPLIED", db.appends[0]["outcomeKind"])
    assertEquals(false, db.appends[0]["corrected"])
    assertEquals(0, db.recentReads)
  }

  @Test
  fun appendFailureDoesNotFailSettlement() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    db.appendThrows = true
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(
      response["ok"] == true && response["settled"] == true,
      "the settlement must survive an append failure but was $response",
    )
    assertEquals(1, db.appendAttempts)
    assertEquals(0, db.appends.size)
    assertEquals(1, db.claims)
    assertEquals(1, db.corrections)
    // The corrected hook is append-independent: the detector still ran.
    assertEquals(1, db.recentReads)
  }

  @Test
  fun recomputeFailureAppendsFailedRecordThenThrows() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(
        completedRound = 2,
        finalScores = mapOf("p1" to 0, "p2" to 0, "p3" to 0, "p4" to 0),
      ),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    try {
      settleOnce(db, settleRequest("u1", "m1"))
      throw AssertionError("expected HttpsError")
    } catch (e: Throwable) {
      assertEquals("failed-precondition", e.asDynamic().code as String)
    }
    assertEquals(1, db.appends.size)
    assertEquals("FAILED", db.appends[0]["outcomeKind"])
    assertEquals("HISTORY_INCOMPLETE", db.appends[0]["failureReason"])
    assertEquals("u1", db.appends[0]["settledBy"])
    assertEquals(0, db.claims)
    assertEquals(0, db.recentReads)
  }

  @Test
  fun malformedArchiveAppendsFailedRecord() = runTest {
    val db = FakeDb(
      match = matchFields(),
      archives = mapOf(
        "1" to mapOf(
          "round" to 1,
          "cardLog" to listOf(mapOf("seatId" to "p1", "round" to 1)),
          "biddingLog" to emptyList<String>(),
        ),
      ),
    )
    try {
      settleOnce(db, settleRequest("u1", "m1"))
      throw AssertionError("expected HttpsError")
    } catch (e: Throwable) {
      assertEquals("failed-precondition", e.asDynamic().code as String)
      assertTrue(
        (e.asDynamic().message as String).contains("roundArchive/1 is malformed"),
        "unexpected message: ${e.asDynamic().message}",
      )
    }
    assertEquals(1, db.appends.size)
    assertEquals("FAILED", db.appends[0]["outcomeKind"])
    assertEquals("HISTORY_CORRUPT", db.appends[0]["failureReason"])
  }

  @Test
  fun noOpAndDeniedAttemptsAppendNothing() = runTest {
    val roomDb = FakeDb(match = matchFields(mode = "ROOM"))
    val response = settleOnce(roomDb, settleRequest("u1", "m1"))
    assertEquals(false, response["settled"])
    assertEquals(0, roomDb.appendAttempts)

    val deniedDb = FakeDb(match = matchFields())
    try {
      settleOnce(deniedDb, settleRequest("stranger", "m1"))
      throw AssertionError("expected HttpsError")
    } catch (e: Throwable) {
      assertEquals("permission-denied", e.asDynamic().code as String)
    }
    assertEquals(0, deniedDb.appendAttempts)
  }

  @Test
  fun repeatDetectorRunsAfterACorrection() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val db = FakeDb(
      match = matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)),
      archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions)),
    )
    db.recentCorrections = listOf(
      mapOf(
        "matchId" to "m0",
        "players" to listOf("u1", "u2", "u3", "u4"),
        "settledBy" to "u1",
        "outcomeKind" to "CORRECTED",
      ),
      mapOf(
        "matchId" to "m1",
        "players" to listOf("u1", "u2", "u3", "u4"),
        "settledBy" to "u1",
        "outcomeKind" to "CORRECTED",
      ),
    )
    val response = settleOnce(db, settleRequest("u1", "m1"))
    assertTrue(response["corrected"] == true, "expected a corrected settle but was $response")
    // The bounded corrections window was read and fed to the pure detector
    // (SettlementLedgerTest pins what the detector derives from it).
    assertEquals(1, db.recentReads)
  }

  @Test
  fun aDetectedRepeatCannotChangeTheSettlement() = runTest {
    val golden = round1Golden()
    val parts = round1Parts()
    val fields = matchFields(finalScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14))
    val archives = mapOf("1" to rawRound(golden, parts.plays, parts.actions))

    // A: the table has never been corrected - the detector finds no repeat.
    val quiet = FakeDb(match = fields, archives = archives)
    val quietResponse = settleOnce(quiet, settleRequest("u1", "m1"))

    // B: identical settlement, but the window now says this same table was
    // corrected once before, so S62_REPEAT fires.
    val loud = FakeDb(match = fields, archives = archives)
    val roster = listOf("u1", "u2", "u3", "u4")
    loud.recentCorrections = listOf(
      mapOf(
        "matchId" to "m0", "players" to roster, "settledBy" to "u1",
        "outcomeKind" to "CORRECTED",
      ),
      mapOf(
        "matchId" to "m1", "players" to roster, "settledBy" to "u1",
        "outcomeKind" to "CORRECTED",
      ),
    )
    val loudResponse = settleOnce(loud, settleRequest("u1", "m1"))

    // A4.3, pinned: the diagnostic signal may not reach the client response,
    // the correction write, the claim, or even the audit row itself.
    assertEquals(quietResponse, loudResponse)
    // The audit row itself is identical too (appendedAt excluded: each call
    // mints a fresh sentinel object, so it can never compare equal).
    assertEquals(
      quiet.appends.single().filterKeys { it != "appendedAt" },
      loud.appends.single().filterKeys { it != "appendedAt" },
    )
    assertEquals(quiet.claims, loud.claims)
    assertEquals(quiet.corrections, loud.corrections)
    assertEquals(1, loud.recentReads)
    assertEquals(1, quiet.recentReads)
  }
}

