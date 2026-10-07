package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit mirror of the pinned JS scoring-engine cases:
 * tests/match-flow-scoring-scenarios.test.cjs (Sa'ayda zeroing + ×2,
 * With/Caller/Risk breakdown notes) against design-ui/engine/scoring-
 * engine.js. Same numbers; native deltas come from 04-scoring.md via
 * Scoring.kt (bid² Super, graduated Risk ladder).
 */
class RoundScoreTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  private fun tricks(vararg pairs: Pair<String, Int>): Map<String, Int> = mapOf(*pairs)

  private fun bids(vararg pairs: Pair<String, Bid>): Map<String, Bid> = mapOf(*pairs)

  private fun t(amount: Int): Bid = Bid(BidType.TRICKS, amount)

  // ── Sa'ayda: all fail → zeros, multiplier 1 applied, ladder escalates ──
  @Test
  fun saaydaZeroesAndEscalates() {
    val input = RoundScoreInput(
      round = 5,
      order = seats,
      bids = bids("p1" to t(4), "p2" to t(3), "p3" to t(3), "p4" to t(3)),
      tricksWon = tricks("p1" to 2, "p2" to 1, "p3" to 5, "p4" to 0),
      callerId = "p1",
      withPlayers = emptyList(),
      multiplier = 1,
      riskPlayerId = "p4",
    )
    val r = calculateRoundScore(input)
    assertTrue(r.isSaayda)
    assertEquals(0, r.successCount)
    assertTrue(r.deltas.values.all { it == 0 })
    assertEquals(1, r.appliedMultiplier)
    assertEquals(2, r.nextMultiplier)
    assertTrue(r.breakdown.values.all { it.notes.any { n -> n.contains("Sa'ayda") } })

    // Prior ×6 escalates to the ×8 cap, never beyond.
    val capped = calculateRoundScore(input.copy(multiplier = 6))
    assertEquals(8, capped.nextMultiplier)
    assertTrue(capped.deltas.values.all { it == 0 })

    // Classic mode caps the same ladder at ×2.
    val classic = calculateRoundScore(input.copy(classic = true, escalationCap = 2))
    assertEquals(2, classic.nextMultiplier)
  }

  @Test
  fun nonSaaydaResetsMultiplier() {
    val input = RoundScoreInput(
      round = 6,
      order = seats,
      bids = bids("p1" to t(4), "p2" to t(3), "p3" to t(3), "p4" to t(3)),
      tricksWon = tricks("p1" to 4, "p2" to 1, "p3" to 5, "p4" to 0),
      callerId = "p1",
      multiplier = 4,
      riskPlayerId = "p4",
    )
    val r = calculateRoundScore(input)
    assertTrue(!r.isSaayda)
    assertEquals(4, r.appliedMultiplier)
    assertEquals(1, r.nextMultiplier)
  }

  // ── Normal round: Caller/With/Risk/sole flow through the wrapper ──
  @Test
  fun normalCallerWithRiskAndSole() {
    // Total 13 → ladder 0. p2 is the lone failure → sole loser −10.
    val r = calculateRoundScore(
      RoundScoreInput(
        round = 1,
        order = seats,
        bids = bids("p1" to t(4), "p2" to t(4), "p3" to t(2), "p4" to t(3)),
        tricksWon = tricks("p1" to 4, "p2" to 2, "p3" to 2, "p4" to 3),
        callerId = "p1",
        withPlayers = listOf("p2"),
        riskPlayerId = "p4",
      ),
    )
    assertEquals(13, r.totalBids)
    assertEquals(0, r.riskValue)
    assertEquals(3, r.successCount)
    assertEquals(mapOf("p1" to 24, "p2" to -22, "p3" to 12, "p4" to 13), r.deltas)
    assertTrue(r.breakdown.getValue("p1").isCaller)
    assertTrue(r.breakdown.getValue("p2").isWith)
    assertTrue(r.breakdown.getValue("p4").isRisk)
    assertTrue(r.breakdown.getValue("p2").isSoleLoser)
    assertEquals(Role.CALLER, r.breakdown.getValue("p1").role)
    assertEquals(Role.WIZZ, r.breakdown.getValue("p2").role)
  }

  // ── Graduated ladder reaches seats end-to-end (total 7 → diff 6 → 30) ──
  @Test
  fun ladderAppliedEndToEnd() {
    val r = calculateRoundScore(
      RoundScoreInput(
        round = 2,
        order = seats,
        bids = bids("p1" to t(2), "p2" to t(2), "p3" to t(1), "p4" to t(2)),
        tricksWon = tricks("p1" to 2, "p2" to 2, "p3" to 1, "p4" to 2),
        callerId = null,
        riskPlayerId = "p4",
      ),
    )
    assertEquals(7, r.totalBids)
    assertEquals(30, r.riskValue)
    assertEquals("p4", r.riskPlayerId)
    assertEquals(12, r.deltas.getValue("p1"))
    assertEquals(42, r.deltas.getValue("p4")) // 10 + 2 + 30
  }

  // ── Super Call bid² flows through the wrapper (caller 8/8 → 64) ──
  @Test
  fun superBidSquaredThroughWrapper() {
    val r = calculateRoundScore(
      RoundScoreInput(
        round = 3,
        order = seats,
        bids = bids("p1" to t(8), "p2" to t(1), "p3" to t(2), "p4" to t(3)),
        tricksWon = tricks("p1" to 8, "p2" to 1, "p3" to 2, "p4" to 3),
        callerId = "p1",
        riskPlayerId = "p4",
      ),
    )
    assertEquals(14, r.totalBids)
    assertEquals(4, r.successCount)
    assertEquals(Role.SUPER_CALL, r.breakdown.getValue("p1").role)
    assertEquals(64, r.deltas.getValue("p1"))
  }

  // ── Prior multiplier doubles real deltas ──
  @Test
  fun multiplierDoublesDeltas() {
    val r = calculateRoundScore(
      RoundScoreInput(
        round = 4,
        order = seats,
        bids = bids("p1" to t(4), "p2" to t(4), "p3" to t(2), "p4" to t(3)),
        tricksWon = tricks("p1" to 4, "p2" to 2, "p3" to 2, "p4" to 3),
        callerId = "p1",
        withPlayers = listOf("p2"),
        multiplier = 2,
        riskPlayerId = "p4",
      ),
    )
    assertEquals(2, r.appliedMultiplier)
    assertEquals(mapOf("p1" to 48, "p2" to -44, "p3" to 24, "p4" to 26), r.deltas)
    assertEquals(1, r.nextMultiplier)
  }

  // ── Classic mode: genuinely different formula + doubling capped at −22 ──
  @Test
  fun classicMode() {
    val win = calculateRoundScore(
      RoundScoreInput(
        round = 1,
        order = seats,
        bids = bids("p1" to t(4), "p2" to t(1), "p3" to t(2), "p4" to t(3)),
        tricksWon = tricks("p1" to 4, "p2" to 1, "p3" to 2, "p4" to 3),
        callerId = "p1",
        classic = true,
        riskPlayerId = "p4",
      ),
    )
    assertEquals(27, win.deltas.getValue("p1")) // 4 + 13 + 10

    val loss = calculateRoundScore(
      RoundScoreInput(
        round = 2,
        order = seats,
        bids = bids("p1" to t(2), "p2" to t(2), "p3" to t(2), "p4" to t(5)),
        tricksWon = tricks("p1" to 2, "p2" to 2, "p3" to 2, "p4" to 3),
        callerId = "p1",
        classic = true,
        riskPlayerId = "p4",
      ),
    )
    // RISK 5/3 alone fails: −(2 + 10) doubled as sole loser, capped at −22.
    assertEquals(-22, loss.deltas.getValue("p4"))
  }

  // ── Risk fallback walks past Dash-Call seats to the last estimator ──
  @Test
  fun riskFallbackSkipsDashCall() {
    val orderBids = bids(
      "p1" to Bid(BidType.DASHCALL, 0),
      "p2" to t(4),
      "p3" to t(3),
      "p4" to Bid(BidType.DASH, 0),
    )
    assertEquals("p4", computeRiskPlayerId("p2", orderBids, seats))
    assertNull(computeRiskPlayerId(null, orderBids, seats))

    // An explicitly known last bidder always wins over the formula.
    val r = calculateRoundScore(
      RoundScoreInput(
        round = 1,
        order = seats,
        bids = orderBids,
        tricksWon = tricks("p1" to 0, "p2" to 4, "p3" to 3, "p4" to 0),
        callerId = "p2",
        riskPlayerId = "p3",
      ),
    )
    assertEquals("p3", r.riskPlayerId)
  }

  // ── Round extension: Rapid Rounds 14-18 only ──
  @Test
  fun roundExtensionWindow() {
    // Successful Super Call with an overriding trump extends.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(15, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    // Same facts outside the window never extend.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(13, "p1", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(19, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    // Sa'ayda inside the window extends (reason SAAYDA).
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(16, null, Suit.HEARTS, callerSucceeded = false, isSaayda = true),
    )
    // Failed caller extends nothing, even on an overriding trump.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, "p3", Suit.HEARTS, callerSucceeded = false, isSaayda = false),
    )
    // Forced trump standing means no Super override happened.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, "p3", Suit.SPADES, callerSucceeded = true, isSaayda = false),
    )
  }

  // ── Role mapping pins ──
  @Test
  fun roleMapping() {
    assertEquals(Role.SUPER_CALL, normalRoleFor(BidType.TRICKS, 8, true, false, false))
    assertEquals(Role.CALLER, normalRoleFor(BidType.TRICKS, 7, true, false, false))
    assertEquals(Role.WIZZ_RISK, normalRoleFor(BidType.TRICKS, 5, false, true, true))
    assertEquals(Role.WIZZ, normalRoleFor(BidType.TRICKS, 5, false, true, false))
    assertEquals(Role.RISK, normalRoleFor(BidType.TRICKS, 5, false, false, true))
    assertEquals(Role.NORMAL, normalRoleFor(BidType.TRICKS, 5, false, false, false))
    assertEquals(Role.DASH_CALL, normalRoleFor(BidType.DASHCALL, 0, false, false, false))
    assertEquals(Role.REG_DASH, normalRoleFor(BidType.DASH, 0, false, false, false))
    // Only the Caller themselves can be Super — a matching With stays WIZZ.
    assertEquals(Role.WIZZ, normalRoleFor(BidType.TRICKS, 8, false, true, false))
  }

  // ── Missing bid entry scores 0 with a note, never NaN ──
  @Test
  fun missingBidScoresZero() {
    val r = calculateRoundScore(
      RoundScoreInput(
        round = 1,
        order = seats,
        bids = bids("p1" to t(4), "p2" to t(3), "p3" to t(3)),
        tricksWon = tricks("p1" to 4, "p2" to 3, "p3" to 3, "p4" to 0),
        callerId = "p1",
        riskPlayerId = "p3",
      ),
    )
    assertEquals(0, r.deltas.getValue("p4"))
    assertTrue(r.breakdown.getValue("p4").notes.any { it.contains("No bid") })
  }

  // ── Match totals accumulate; teams don't exist ──
  @Test
  fun matchTotalsAndNoTeams() {
    val current = mapOf("p1" to 10, "p2" to -5, "p3" to 0, "p4" to 7)
    val deltas = mapOf("p1" to 24, "p2" to -22, "p3" to 12)
    assertEquals(
      mapOf("p1" to 34, "p2" to -27, "p3" to 12, "p4" to 7),
      accumulateMatchScores(current, deltas),
    )
    assertNull(calculateTeamScore())
  }
}
