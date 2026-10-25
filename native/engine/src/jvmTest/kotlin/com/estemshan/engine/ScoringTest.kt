package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

  // A7.1 helpers. `totalBids` comes FIRST and is never defaulted: a Classic
  // Dash alone succeeding Under is also +23, so a row that hid its total would
  // let a wrong implementation pass (the hazard the rules text calls out).
  // A Dash seat's bid is its fixed 0 estimate, so `won == 0` is success.
  private fun normalDash(
    totalBids: Int,
    won: Int,
    isRisk: Boolean,
    soleWinner: Boolean = false,
    soleLoser: Boolean = false,
  ): Int = calculateNormalScore(Role.REG_DASH, 0, won, soleWinner, soleLoser, totalBids, isRisk)

  private fun classicDash(
    totalBids: Int,
    won: Int,
    isRisk: Boolean,
    soleWinner: Boolean = false,
    soleLoser: Boolean = false,
  ): Int = calculateClassicScore(Role.REG_DASH, 0, won, totalBids, soleWinner, soleLoser, isRisk)

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

  // ── A7.1 / A6.3 / Current Rules item 4: a Normal Dash seat that is ALSO the
  // Risk player takes the Risk ladder PLUS its own Dash table value, with the
  // miss charged once. The seat stays REG_DASH — Risk here is a component, not
  // a role, so the RISK/WIZZ_RISK formulas must never be substituted. ──
  @Test
  fun dashPlusRiskOwnerCases() {
    // Normal (A6.3), total 15 → Risk 10: 0 tricks → +20, 2 tricks → −22.
    assertEquals(20, normalDash(totalBids = 15, won = 0, isRisk = true))
    assertEquals(-22, normalDash(totalBids = 15, won = 2, isRisk = true))
    // Classic (A7.1), total 15 → Risk 10, Over, 2 tricks: −2 + (−10) = −12.
    assertEquals(-12, classicDash(totalBids = 15, won = 2, isRisk = true))
    // The Normal −22 is NOT a Classic value on identical facts.
    assertNotEquals(-22, classicDash(totalBids = 15, won = 2, isRisk = true))
  }

  @Test
  fun classicDashPlusRiskSuccessBothSides() {
    // Over (total 15 → Risk 10): +13 + 10 = +23.
    assertEquals(23, classicDash(totalBids = 15, won = 0, isRisk = true))
    // Under (total 11 → Risk 10): +23 + 10 = +33.
    assertEquals(33, classicDash(totalBids = 11, won = 0, isRisk = true))
  }

  @Test
  fun dashPlusRiskSoleBonusesAreUnchanged() {
    // Normal sole winner +10 → +30; sole loser −10 (flat, never the Classic
    // doubling) → −32.
    assertEquals(30, normalDash(totalBids = 15, won = 0, isRisk = true, soleWinner = true))
    assertEquals(-32, normalDash(totalBids = 15, won = 2, isRisk = true, soleLoser = true))
    // Classic sole winner +10 on top of the summed success: Over +33, Under +43.
    assertEquals(33, classicDash(totalBids = 15, won = 0, isRisk = true, soleWinner = true))
    assertEquals(43, classicDash(totalBids = 11, won = 0, isRisk = true, soleWinner = true))
    // Classic sole loser doubles the SUMMED base failure and floors at −22:
    // 2 × (−12) = −24 → −22, the cap binding.
    assertEquals(-22, classicDash(totalBids = 15, won = 2, isRisk = true, soleLoser = true))
  }

  @Test
  fun dashPlusRiskLadderBoundaries() {
    // Risk 0 — total 13 (diff 0): the flag adds nothing in either mode.
    assertEquals(10, normalDash(totalBids = 13, won = 0, isRisk = true))
    assertEquals(-12, normalDash(totalBids = 13, won = 2, isRisk = true))
    assertEquals(23, classicDash(totalBids = 13, won = 0, isRisk = true)) // Under
    assertEquals(-10, classicDash(totalBids = 13, won = 2, isRisk = true)) // Under

    // Risk 10 — total 15 (diff 2, Over) and total 11 (diff 2, Under).
    assertEquals(20, normalDash(totalBids = 15, won = 0, isRisk = true))
    assertEquals(-22, normalDash(totalBids = 15, won = 2, isRisk = true))
    assertEquals(23, classicDash(totalBids = 15, won = 0, isRisk = true)) // Over +13+10
    assertEquals(-12, classicDash(totalBids = 15, won = 2, isRisk = true)) // Over −2−10
    assertEquals(33, classicDash(totalBids = 11, won = 0, isRisk = true)) // Under +23+10
    assertEquals(-20, classicDash(totalBids = 11, won = 2, isRisk = true)) // Under −10−10

    // Risk 20 — total 17 (diff 4, Over) and total 9 (diff 4, Under).
    assertEquals(30, normalDash(totalBids = 17, won = 0, isRisk = true))
    assertEquals(-32, normalDash(totalBids = 17, won = 2, isRisk = true))
    assertEquals(33, classicDash(totalBids = 17, won = 0, isRisk = true)) // Over +13+20
    assertEquals(-22, classicDash(totalBids = 17, won = 2, isRisk = true)) // Over −2−20
    assertEquals(43, classicDash(totalBids = 9, won = 0, isRisk = true)) // Under +23+20
    assertEquals(-30, classicDash(totalBids = 9, won = 2, isRisk = true)) // Under −10−20

    // Risk 30 — total 19 (diff 6, Over) and total 7 (diff 6, Under).
    assertEquals(40, normalDash(totalBids = 19, won = 0, isRisk = true))
    assertEquals(-42, normalDash(totalBids = 19, won = 2, isRisk = true))
    assertEquals(43, classicDash(totalBids = 19, won = 0, isRisk = true)) // Over +13+30
    assertEquals(-32, classicDash(totalBids = 19, won = 2, isRisk = true)) // Over −2−30
    assertEquals(53, classicDash(totalBids = 7, won = 0, isRisk = true)) // Under +23+30
    assertEquals(-40, classicDash(totalBids = 7, won = 2, isRisk = true)) // Under −10−30
  }

  @Test
  fun dashWithoutRiskIsUnchanged() {
    // The ladder belongs to the Risk seat alone: however far the total drifts,
    // a non-Risk Dash keeps 10 / −(10+T) and the plain Classic table.
    assertEquals(10, normalDash(totalBids = 19, won = 0, isRisk = false))
    assertEquals(-12, normalDash(totalBids = 19, won = 2, isRisk = false))
    assertEquals(13, classicDash(totalBids = 15, won = 0, isRisk = false)) // Over
    assertEquals(-2, classicDash(totalBids = 15, won = 2, isRisk = false)) // Over −T
    assertEquals(23, classicDash(totalBids = 11, won = 0, isRisk = false)) // Under
    assertEquals(-10, classicDash(totalBids = 11, won = 2, isRisk = false)) // Under
    // The default is false, so every pre-existing 4-arg ScoredPlayer is inert.
    assertEquals(false, ScoredPlayer("p1", Role.REG_DASH, 0, 2).isRisk)
  }

  @Test
  fun nonDashRolesIgnoreTheFlag() {
    // Only the REG_DASH branch reads isRisk. RISK/WIZZ_RISK already carry the
    // ladder in their own role formula, so the flag must be inert for them —
    // and for NORMAL/CALLER/DASH_CALL/SUPER_CALL, which never see Risk here.
    assertEquals(25, calculateNormalScore(Role.RISK, 5, 5, false, false, 15, isRisk = true))
    assertEquals(25, calculateNormalScore(Role.RISK, 5, 5, false, false, 15, isRisk = false))
    assertEquals(-11, calculateNormalScore(Role.RISK, 5, 4, false, false, 15, isRisk = true))
    assertEquals(35, calculateNormalScore(Role.WIZZ_RISK, 5, 5, false, false, 15, isRisk = true))
    assertEquals(15, calculateNormalScore(Role.NORMAL, 5, 5, false, false, 7, isRisk = true))
    assertEquals(25, calculateNormalScore(Role.DASH_CALL, 0, 0, false, false, 15, isRisk = true))
    assertEquals(64, calculateNormalScore(Role.SUPER_CALL, 8, 8, false, false, 15, isRisk = true))
    // Classic: RISK success bid+13+10; WIZZ_RISK success bid+13+20 and failure
    // −(miss+20). None moves, and the Classic WIZZ_RISK formula is NOT reused
    // for the Dash+Risk seat (that would give +36/−14 on the owner's case).
    assertEquals(28, calculateClassicScore(Role.RISK, 5, 5, 15, false, false, isRisk = true))
    assertEquals(28, calculateClassicScore(Role.RISK, 5, 5, 15, false, false, isRisk = false))
    assertEquals(38, calculateClassicScore(Role.WIZZ_RISK, 5, 5, 15, false, false, isRisk = true))
    assertEquals(-24, calculateClassicScore(Role.WIZZ_RISK, 5, 1, 15, false, false, isRisk = true))
  }

  @Test
  fun riskComponentIsAddedExactlyOnce() {
    // total 19 → Risk 30. These rows are chosen so single- and double-counting
    // cannot collide with any pinned constant (the −22 floor or the owner's
    // +23/−12 pair), which makes the arithmetic itself the proof.
    val risk = riskValue(19)
    assertEquals(30, risk)

    // Normal: the move from the non-Risk baseline is exactly ONE ladder step.
    assertEquals(normalDash(19, 0, false) + risk, normalDash(19, 0, true))
    assertEquals(normalDash(19, 2, false) - risk, normalDash(19, 2, true))
    assertEquals(40, normalDash(19, 0, true))
    assertEquals(-42, normalDash(19, 2, true))

    // Classic: same, on both the success and the failure side.
    assertEquals(classicDash(19, 0, false) + risk, classicDash(19, 0, true))
    assertEquals(classicDash(19, 2, false) - risk, classicDash(19, 2, true))
    assertEquals(43, classicDash(19, 0, true))
    assertEquals(-32, classicDash(19, 2, true))

    // Explicitly not the doubled ladder (10+60 / −12−60 and 13+60 / −2−60).
    assertNotEquals(70, normalDash(19, 0, true))
    assertNotEquals(-72, normalDash(19, 2, true))
    assertNotEquals(73, classicDash(19, 0, true))
    assertNotEquals(-62, classicDash(19, 2, true))
  }

  @Test
  fun scoreRoundCarriesIsRiskInBothModes() {
    // p1 is the Dash seat AND the Risk player; p2/p3 succeed so p1 is neither
    // sole winner nor sole loser. Total 0+5+5+5 = 15 → Risk 10.
    val players = listOf(
      ScoredPlayer("p1", Role.REG_DASH, 0, 2, isRisk = true),
      ScoredPlayer("p2", Role.NORMAL, 5, 5),
      ScoredPlayer("p3", Role.NORMAL, 5, 5),
      ScoredPlayer("p4", Role.NORMAL, 5, 3),
    )
    assertEquals(-22, scoreRound(players, 15).getValue("p1")) // Normal (A6.3)
    assertEquals(-12, scoreRound(players, 15, classic = true).getValue("p1")) // Classic (A7.1)
    // Same table, flag cleared: back to the plain Dash values in both modes.
    val plain = players.map { it.copy(isRisk = false) }
    assertEquals(-12, scoreRound(plain, 15).getValue("p1"))
    assertEquals(-2, scoreRound(plain, 15, classic = true).getValue("p1"))
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
