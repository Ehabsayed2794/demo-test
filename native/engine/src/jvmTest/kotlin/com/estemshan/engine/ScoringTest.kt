package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JUnit mirror of the owner-confirmed JS tables:
 * tests/src-super-call-normal.test.cjs, tests/src-dash-normal.test.cjs
 * (plus Classic spot checks). Same numbers, Kotlin implementation.
 */
class ScoringTest {

  private fun normal(
    role: Role,
    bid: Int,
    won: Int,
    totalBids: Int = bid,
    soleWinner: Boolean = false,
    soleLoser: Boolean = false,
  ): Int = calculateNormalScore(role, bid, won, soleWinner, soleLoser, totalBids)

  private fun round(
    superBid: Int,
    superWon: Int,
    others: List<Pair<Int, Int>>,
    classic: Boolean = false,
  ): Int {
    val players = mutableListOf(ScoredPlayer("p1", Role.SUPER_CALL, superBid, superWon))
    others.forEachIndexed { i, o ->
      players.add(ScoredPlayer("p${i + 2}", Role.NORMAL, o.first, o.second))
    }
    val total = superBid + others.sumOf { it.first }
    return scoreRound(players, total, classic).getValue("p1")
  }

  // ── Super Call: win = bid² ──
  @Test
  fun superWinTable() {
    val table = mapOf(8 to 64, 9 to 81, 10 to 100, 11 to 121, 12 to 144, 13 to 169)
    for ((bid, expected) in table) {
      assertEquals(expected, round(bid, bid, listOf(1 to 1, 2 to 2, 3 to 3)))
    }
  }

  @Test
  fun superSoleWinAdds10() {
    assertEquals(74, round(8, 8, listOf(1 to 2, 2 to 3, 3 to 4)))
  }

  // ── Super Call: loss = half rounded half-up, tricks ignored ──
  @Test
  fun superLossTable() {
    val table = mapOf(8 to -32, 9 to -41, 10 to -50, 11 to -61, 12 to -72, 13 to -85)
    for ((bid, expected) in table) {
      assertEquals(expected, round(bid, 0, listOf(1 to 1, 2 to 2, 5 to 6)))
    }
  }

  @Test
  fun superLossIgnoresTricksTaken() {
    assertEquals(-32, round(8, 1, listOf(1 to 1, 2 to 2, 5 to 6)))
    assertEquals(-32, round(8, 7, listOf(1 to 1, 2 to 2, 5 to 6)))
  }

  @Test
  fun superSoleLossTable() {
    val table = mapOf(8 to -42, 9 to -51, 10 to -60, 11 to -71, 12 to -82, 13 to -95)
    for ((bid, expected) in table) {
      assertEquals(expected, round(bid, 0, listOf(1 to 1, 2 to 2, 3 to 3)))
    }
  }

  // ── Standard roles: single Caller/With bonus, flat sole ──
  // Round total 15 (diff 2 → ladder 10), so the owner pins hold: WIZZ 25,
  // WIZZ_RISK 35, CALLER 25, RISK 25.
  @Test
  fun wizzWinTakesSingleBonus() {
    assertEquals(25, normal(Role.WIZZ, 5, 5, totalBids = 15))
    assertEquals(35, normal(Role.WIZZ_RISK, 5, 5, totalBids = 15))
    assertEquals(25, normal(Role.CALLER, 5, 5, totalBids = 15))
    assertEquals(25, normal(Role.RISK, 5, 5, totalBids = 15))
  }

  // ── Owner D2: the Risk component is the graduated ladder, not flat 10 ──
  @Test
  fun riskBonusIsGraduatedLadder() {
    // RISK win 5: 10 + 5 + ladder(total).
    assertEquals(15, normal(Role.RISK, 5, 5, totalBids = 12)) // diff 1 → 0
    assertEquals(25, normal(Role.RISK, 5, 5, totalBids = 15)) // diff 2 → 10
    assertEquals(35, normal(Role.RISK, 5, 5, totalBids = 9)) // diff 4 → 20
    assertEquals(45, normal(Role.RISK, 5, 5, totalBids = 7)) // diff 6 → 30
    // RISK loss: -(miss + ladder).
    assertEquals(-11, normal(Role.RISK, 5, 4, totalBids = 15))
    assertEquals(-31, normal(Role.RISK, 5, 4, totalBids = 7))
    // Non-risk seats never see the ladder, however far the total drifts.
    assertEquals(15, normal(Role.NORMAL, 5, 5, totalBids = 7))
  }

  @Test
  fun ownerSoleLossExamples() {
    // Bid 5 won 4 alone → -11; bid 3 won 1 alone → -12.
    assertEquals(
      -11,
      scoreRound(
        listOf(
          ScoredPlayer("p1", Role.NORMAL, 5, 4),
          ScoredPlayer("p2", Role.NORMAL, 1, 1),
          ScoredPlayer("p3", Role.NORMAL, 2, 2),
          ScoredPlayer("p4", Role.NORMAL, 3, 3),
        ),
        11,
      ).getValue("p1"),
    )
    assertEquals(
      -12,
      scoreRound(
        listOf(
          ScoredPlayer("p1", Role.NORMAL, 3, 1),
          ScoredPlayer("p2", Role.NORMAL, 1, 1),
          ScoredPlayer("p3", Role.NORMAL, 2, 2),
          ScoredPlayer("p4", Role.NORMAL, 3, 3),
        ),
        9,
      ).getValue("p1"),
    )
  }

  @Test
  fun nonSoleLossIsPlainMiss() {
    assertEquals(
      -1,
      scoreRound(
        listOf(
          ScoredPlayer("p1", Role.NORMAL, 5, 4),
          ScoredPlayer("p2", Role.NORMAL, 1, 1),
          ScoredPlayer("p3", Role.NORMAL, 2, 2),
          ScoredPlayer("p4", Role.NORMAL, 3, 5),
        ),
        11,
      ).getValue("p1"),
    )
  }

  // ── Dash Call flat table ──
  @Test
  fun dashTable() {
    fun dash(won: Int, total: Int, others: List<Pair<Int, Int>>): Int {
      val players = mutableListOf(ScoredPlayer("p1", Role.DASH_CALL, 0, won))
      others.forEachIndexed { i, o ->
        players.add(ScoredPlayer("p${i + 2}", Role.NORMAL, o.first, o.second))
      }
      return scoreRound(players, total).getValue("p1")
    }
    assertEquals(33, dash(0, 12, listOf(4 to 4, 4 to 4, 4 to 4)))
    assertEquals(25, dash(0, 15, listOf(5 to 5, 5 to 5, 5 to 5)))
    assertEquals(43, dash(0, 12, listOf(4 to 5, 4 to 5, 4 to 5)))
    assertEquals(35, dash(0, 15, listOf(5 to 6, 5 to 6, 5 to 6)))
    assertEquals(-33, dash(2, 12, listOf(4 to 4, 4 to 4, 4 to 5)))
    assertEquals(-25, dash(1, 15, listOf(5 to 5, 5 to 5, 5 to 6)))
    assertEquals(-43, dash(3, 12, listOf(4 to 4, 4 to 4, 4 to 4)))
    assertEquals(-35, dash(2, 15, listOf(5 to 5, 5 to 5, 5 to 5)))
  }

  // ── Normal Dash 10+T ──
  @Test
  fun normalDashTable() {
    fun reg(won: Int, total: Int, others: List<Pair<Int, Int>>): Int {
      val players = mutableListOf(ScoredPlayer("p1", Role.REG_DASH, 0, won))
      others.forEachIndexed { i, o ->
        players.add(ScoredPlayer("p${i + 2}", Role.NORMAL, o.first, o.second))
      }
      return scoreRound(players, total).getValue("p1")
    }
    assertEquals(10, reg(0, 12, listOf(4 to 4, 4 to 4, 4 to 4)))
    assertEquals(20, reg(0, 12, listOf(4 to 5, 4 to 5, 4 to 5)))
    assertEquals(-13, reg(3, 12, listOf(4 to 4, 4 to 4, 4 to 5)))
    assertEquals(-23, reg(3, 12, listOf(4 to 4, 4 to 4, 4 to 4)))
  }

  // ── Risk ladder (native D2) ──
  @Test
  fun riskLadder() {
    assertEquals(0, riskValue(12)) // diff 1
    assertEquals(0, riskValue(14)) // diff 1
    assertEquals(10, riskValue(11)) // diff 2
    assertEquals(10, riskValue(10)) // diff 3
    assertEquals(10, riskValue(15)) // diff 2
    assertEquals(10, riskValue(16)) // diff 3
    assertEquals(20, riskValue(9)) // diff 4
    assertEquals(20, riskValue(8)) // diff 5
    assertEquals(20, riskValue(17)) // diff 4
    assertEquals(20, riskValue(18)) // diff 5
    assertEquals(30, riskValue(7)) // diff 6
    assertEquals(30, riskValue(19)) // diff 6
    assertEquals(30, riskValue(0)) // diff 13
  }

  // ── Classic untouched spot checks ──
  @Test
  fun classicUntouched() {
    assertEquals(42, round(8, 8, listOf(1 to 1, 2 to 2, 3 to 3), classic = true))
    assertEquals(-20, round(8, 5, listOf(1 to 1, 2 to 2, 3 to 4), classic = true))
  }

  // ── Winners + Sa'ayda ──
  @Test
  fun winnersAndSaayda() {
    assertEquals(listOf("p1"), computeWinner(mapOf("p1" to 10, "p2" to 5)))
    assertEquals(
      setOf("p1", "p2"),
      computeWinner(mapOf("p1" to 10, "p2" to 10, "p3" to 4)).toSet(),
    )
    assertEquals(emptyList<String>(), computeWinner(emptyMap()))
    assertEquals(1, saaydaMultiplier(0))
    assertEquals(2, saaydaMultiplier(1))
    assertEquals(4, saaydaMultiplier(2))
    assertEquals(6, saaydaMultiplier(3))
    assertEquals(8, saaydaMultiplier(4))
    assertEquals(8, saaydaMultiplier(9))
  }
}
