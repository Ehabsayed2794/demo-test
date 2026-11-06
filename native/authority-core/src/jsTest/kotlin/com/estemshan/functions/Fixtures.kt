package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.functions.Settlement.ArchivedRound
import com.estemshan.functions.Settlement.ConvergedMatch
import kotlin.test.assertEquals

/**
 * Shared converged-match fixtures for the JS test suites (S50 goldens).
 *
 * Extracted verbatim from SettlementTest so the Durable Object adapter suite
 * (DoStorageTest) exercises the SAME documents — one source of truth for
 * "the corrected-settle round 1" that both the portable and the DO round-trip
 * tests assert on. Every fixture stays self-validating: a misbuilt fixture
 * fails its own test, never silently.
 *
 * All helpers are public for the suites; this file is fixture-only and must
 * never reach production code.
 */
object Fixtures {

  /** Sentinel for "key absent" (distinct from an explicit null). */
  val ABSENT = object {}

  fun rankS(v: Int): String = when (v) {
    11 -> "J"
    12 -> "Q"
    13 -> "K"
    14 -> "A"
    else -> "$v"
  }

  fun card(seat: String, suit: String, v: Int, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "card" to mapOf("suit" to suit, "rank" to mapOf("v" to v, "s" to rankS(v))),
      "round" to round,
    )

  fun dash(seat: String, declared: Boolean, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitDashCallDecision",
      "declaredDashCall" to declared,
      "round" to round,
    )

  fun auctionBid(seat: String, tricks: Int, suit: String, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitAuctionBid",
      "isPass" to false,
      "tricks" to tricks,
      "suit" to suit,
      "round" to round,
    )

  fun auctionPass(seat: String, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitAuctionBid",
      "isPass" to true,
      "round" to round,
    )

  fun confirm(seat: String, tricks: Int, suit: String, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitConfirmCall",
      "tricks" to tricks,
      "suit" to suit,
      "round" to round,
    )

  /**
   * Builds 52 plays for 13 tricks: [winners] is the winner seat per trick
   * in play order (must total the intended tricksWon). Two phases, so filler
   * assignment can never starve a future winner: first every winner takes
   * the highest remaining card of the winning suit (trump, or led when
   * trump is SANS), then every other slot takes the lowest remaining card
   * that cannot beat its trick's winner. Led suit cycles S/H/D/C; play
   * order is p1..p4.
   */
  fun playsFor(
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

  private fun nextSeat(seat: String): String {
    val order = listOf("p1", "p2", "p3", "p4")
    return order[(order.indexOf(seat) + 1) % order.size]
  }

  fun matchFields(
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

  fun archiveFields(
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

  fun shellMatch(order: List<String> = listOf("p1", "p2", "p3", "p4")) = ConvergedMatch(
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

  fun converged(
    archives: List<ArchivedRound>,
    fields: Map<String, Any?> = matchFields(),
  ): ConvergedMatch {
    val parsed = Settlement.parseMatch("m1", fields)
      ?: throw AssertionError("fixture match did not parse")
    return parsed.copy(archives = archives)
  }

  fun archived(
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
  data class RoundFixture(
    val winners: List<String>,
    val plays: List<Map<String, Any?>>,
    val actions: List<Map<String, Any?>>,
    val estimates: Map<String, Int>,
  )

  fun round1Parts(): RoundFixture {
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

  fun round1Golden(): ArchivedRound {
    val parts = round1Parts()
    return archived(1, parts.winners, "SPADES", parts.actions, parts.estimates)
  }

  fun rawRound(round: ArchivedRound, plays: List<Map<String, Any?>>, actions: List<Map<String, Any?>>): Map<String, Any?> {
    val estimates: Any? = round.estimates ?: ABSENT
    return archiveFields(round.round, plays, actions, estimates)
  }
}
