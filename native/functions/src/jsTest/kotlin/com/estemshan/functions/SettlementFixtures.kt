package com.estemshan.functions

import com.estemshan.engine.Suit
import kotlin.js.json
import kotlin.test.assertEquals

/**
 * The S50-shaped settlement fixtures the ranked-progression tests drive
 * (`RpApplicationTest`, `SettlementRpEmulatorTest`).
 *
 * Copied from `SettlementTest`'s private builders instead of refactored out of
 * them: those goldens are hand-derived. Because they are, [round1Archived] is
 * the kept self-check — it re-derives the golden archive through the real
 * bidding, trick and settlement engines and asserts the result is exactly
 * [GOLDEN_SCORES], so a misbuilt `cardLog` cannot make every progression test
 * quietly wrong together.
 *
 * Uids and match ids are parameters because the emulator suite shares one
 * database with no clearing between runs — a repeated id silently turns a
 * create into an update.
 */
internal object SettlementFixtures {

  /** The round-1 golden totals: caller p1 5♠ makes 5, p2 alone misses. */
  val GOLDEN_SCORES: Map<String, Int> = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14)

  /** Round-1 winners per trick: p1 5, p2 1, p3 2, p4 5. */
  private val round1Winners: List<String> =
    listOf("p1", "p1", "p1", "p1", "p1", "p2", "p2", "p3", "p3", "p4", "p4", "p4", "p4")

  private val round1Estimates: Map<String, Int> = mapOf("p2" to 3, "p3" to 2, "p4" to 4)

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

  private fun nextSeat(seat: String): String {
    val order = listOf("p1", "p2", "p3", "p4")
    return order[(order.indexOf(seat) + 1) % order.size]
  }

  /**
   * 52 plays for 13 tricks (verbatim from `SettlementTest`): every winner takes
   * the highest remaining card of the winning suit, every other slot a card that
   * cannot beat it, so `trickWinner` re-derives exactly the intended tricksWon.
   */
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

  /** [uids] seated in canonical order: p1 = uids[0], and so on. */
  fun seatsOf(uids: List<String>): Map<String, String> =
    uids.mapIndexed { index, uid -> "p${index + 1}" to uid }.toMap()

  /**
   * A complete, converged, RANKED 1-round match whose claim equals the golden
   * recompute (so the ordinary path settles AGREED and writes no correction).
   */
  fun matchFields(
    uids: List<String>,
    mode: Any? = "RANKED",
    gameType: Any? = "FULL",
    finalScores: Any? = GOLDEN_SCORES,
    winnerIds: Any? = listOf("p1"),
  ): Map<String, Any?> = linkedMapOf(
    "roomId" to "r1",
    "players" to uids,
    "status" to "complete",
    "currentRound" to 1,
    "completedRound" to 1,
    "maxRounds" to 1,
    "dealer" to uids[0],
    "seats" to seatsOf(uids),
    "version" to 7,
    "mode" to mode,
    "gameType" to gameType,
    "scoringMode" to "NORMAL",
    "finalScores" to finalScores,
    "winnerIds" to winnerIds,
  )

  fun archiveFields(
    round: Int,
    plays: List<Map<String, Any?>>,
    actions: List<Map<String, Any?>>,
    estimates: Map<String, Int>,
  ): Map<String, Any?> = linkedMapOf(
    "round" to round,
    "cardLog" to plays,
    "biddingLog" to actions,
    "estimates" to estimates,
  )

  /** The golden round-1 archive, as the fake ports and the emulator both need it. */
  fun round1ArchiveFields(round: Int = 1): Map<String, Any?> = archiveFields(
    round,
    playsFor(round1Winners, "SPADES", round, "p1"),
    listOf(
      dash("p1", false, round), dash("p2", false, round),
      dash("p3", false, round), dash("p4", false, round),
      auctionBid("p1", 5, "SPADES", round),
      auctionPass("p2", round), auctionPass("p3", round), auctionPass("p4", round),
      confirm("p1", 5, "SPADES", round),
    ),
    round1Estimates,
  )

  /**
   * The golden round-1 archive, parsed and re-derived through the real
   * engines. Every progression test asserts numbers hand-computed from
   * [GOLDEN_SCORES], so if the archive that produces them were misbuilt, all
   * of them would be quietly wrong together. This is the check that they are
   * not: the cardLog replays to the trick counts it was written to produce,
   * and the archive recompute — the same [Settlement.recompute] the
   * production path runs — agrees with the claim and returns
   * [GOLDEN_SCORES] seat for seat.
   */
  fun round1Archived(): Settlement.ArchivedRound {
    val fields = round1ArchiveFields()
    val parsed = Settlement.parseArchive("1", fields)
      ?: throw AssertionError("golden archive did not parse")
    val match = Settlement.parseMatch("m1", matchFields(listOf("u1", "u2", "u3", "u4")))
      ?.let { it.copy(dealerSeatFinal = "p1", completedRound = 1, archives = listOf(parsed)) }
      ?: throw AssertionError("golden match did not parse")
    val tricks = Settlement.replayTricks(match, parsed, Suit.SPADES)
      ?: throw AssertionError("golden tricks did not replay")
    assertEquals(
      round1Winners.groupingBy { it }.eachCount(),
      tricks.filterValues { it > 0 },
    )
    val decision = Settlement.recompute(match)
    val agreed = decision as? Settlement.SettleDecision.Agreed
      ?: throw AssertionError("golden archive must recompute AGREED, was $decision")
    assertEquals(GOLDEN_SCORES, agreed.recomputed.finalScores)
    return parsed
  }

  /** A profile document as `players/{uid}` holds it: RP only. */
  fun profile(uid: String, rp: Int?): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["uid"] = uid
    if (rp != null) out["rp"] = rp
    return out
  }

  /**
   * A callable request for [uid]. The runtime delivers `data` as a PLAIN JS
   * OBJECT, never a Kotlin map, so `json()` is the shape under test; a null uid
   * is the unauthenticated call.
   */
  fun settleRequest(uid: String?, matchId: Any?): CallableRequest = object : CallableRequest {
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
}
