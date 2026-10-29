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
  fun t22saaydaZeroesAndEscalates() {
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
  fun t22nonSaaydaResetsMultiplier() {
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
  fun t24ladderAppliedEndToEnd() {
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
  fun t05superBidSquaredThroughWrapper() {
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
  fun t01multiplierDoublesDeltas() {
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
  fun t02classicMode() {
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

  // ── A7.1 / A6.3 / Current Rules item 4: a Normal Dash seat that is ALSO the
  // Risk player adds the Risk ladder to its own Dash value, exactly once. ──
  // Total 5+5+5+0 = 15 → Risk 10, Over. p4 dashes and takes 2 against its
  // fixed 0 estimate; p1/p2 succeed, so p4 is neither sole winner nor sole
  // loser and no bonus or doubling masks the component.
  private fun dashRiskRound(classic: Boolean, riskId: String = "p4") = calculateRoundScore(
    RoundScoreInput(
      round = 7,
      order = seats,
      bids = bids("p1" to t(5), "p2" to t(5), "p3" to t(5), "p4" to Bid(BidType.DASH, 0)),
      tricksWon = tricks("p1" to 5, "p2" to 5, "p3" to 1, "p4" to 2),
      callerId = "p1",
      riskPlayerId = riskId,
      classic = classic,
    ),
  )

  @Test
  fun t06normalDashWhoIsAlsoRiskAddsTheLadderOnce() {
    val r = dashRiskRound(classic = false)
    assertEquals(15, r.totalBids)
    assertEquals(10, r.riskValue)
    assertEquals("p4", r.riskPlayerId)
    // A Dash seat stays REG_DASH — its Risk is a component, never a new role.
    assertEquals(Role.REG_DASH, r.breakdown.getValue("p4").role)
    assertTrue(r.breakdown.getValue("p4").isRisk)
    assertTrue(!r.breakdown.getValue("p4").isSoleWinner)
    assertTrue(!r.breakdown.getValue("p4").isSoleLoser)
    // A6.3 owner case: −(10 + 2) − 10 = −22.
    assertEquals(-22, r.deltas.getValue("p4"))
    // The non-Risk seats are untouched: CALLER success = 10 + 5 + 10.
    assertEquals(25, r.deltas.getValue("p1"))
  }

  @Test
  fun t07classicDashWhoIsAlsoRiskAddsTheLadderOnce() {
    val r = dashRiskRound(classic = true)
    assertEquals(15, r.totalBids)
    assertEquals(Role.REG_DASH, r.breakdown.getValue("p4").role)
    assertTrue(r.breakdown.getValue("p4").isRisk)
    assertTrue(!r.breakdown.getValue("p4").isSoleLoser)
    // A7.1 owner case: Over failure −T = −2, plus −10 = −12 — never the
    // Normal-mode −22, and never the Classic WIZZ_RISK formula.
    assertEquals(-12, r.deltas.getValue("p4"))
    // Classic CALLER success = bid + 13 + 10.
    assertEquals(28, r.deltas.getValue("p1"))
  }

  @Test
  fun t06t07dashSeatThatIsNotRiskKeepsTheOldValues() {
    // Same table with the Risk moved to p3: p4's Dash rows fall back to
    // −(10+T) and the Classic −T, while p3 scores through its own RISK role.
    val normal = dashRiskRound(classic = false, riskId = "p3")
    assertEquals(Role.REG_DASH, normal.breakdown.getValue("p4").role)
    assertTrue(!normal.breakdown.getValue("p4").isRisk)
    assertEquals(-12, normal.deltas.getValue("p4")) // −(10 + 2), no ladder
    assertEquals(Role.RISK, normal.breakdown.getValue("p3").role)
    assertEquals(-14, normal.deltas.getValue("p3")) // −(miss 4 + ladder 10)

    val classic = dashRiskRound(classic = true, riskId = "p3")
    assertEquals(-2, classic.deltas.getValue("p4")) // Over failure −T
    assertEquals(Role.RISK, classic.breakdown.getValue("p3").role)
    assertEquals(-14, classic.deltas.getValue("p3")) // −(miss 4 + 10)
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
  fun t04roundExtensionWindow() {
    // Successful Super Call with an overriding trump extends (maxRounds = base 18).
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    // Same facts outside the window never extend.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(13, 18, "p1", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(19, 18, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    // Sa'ayda inside the window extends (reason SAAYDA).
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(16, 18, null, Suit.HEARTS, callerSucceeded = false, isSaayda = true),
    )
    // Failed caller extends nothing, even on an overriding trump.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = false, isSaayda = false),
    )
    // Forced trump standing means no Super override happened.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, "p3", Suit.SPADES, callerSucceeded = true, isSaayda = false),
    )
  }

  // ── A8.8 / Q48 — Sa'ayda count, generalised to 18+N ──
  @Test
  fun t03saaydaCountFull14To19() {
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(14, 18, null, Suit.SANS, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun t03saaydaCountFull14Plus15To20() {
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(15, 19, null, Suit.SPADES, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun t03saaydaCountFull14Plus15Plus16To21() {
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(16, 20, null, Suit.HEARTS, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun t03saaydaCountRound13BeforeWindowNoExtend() {
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(13, 18, null, Suit.CLUBS, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun t03saaydaCountFullEighteenN() {
    // Round 18 is in-window → extends to 19.
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(18, 18, null, Suit.CLUBS, callerSucceeded = false, isSaayda = true),
    )
    // Round 19 is post-window but IS the final round → repeats to 20.
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(19, 19, null, Suit.SANS, callerSucceeded = false, isSaayda = true),
    )
    // Round 20 is post-window and IS the final round → repeats to 21.
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(20, 20, null, Suit.SPADES, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun t03saaydaCountNonFinalPostWindowAddsNothing() {
    // Round 19 past the window, but maxRounds is 21 so round 19 is NOT final.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(19, 21, null, Suit.SANS, callerSucceeded = false, isSaayda = true),
    )
    // Round 20 past the window, maxRounds 21 → not final.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(20, 21, null, Suit.SPADES, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun t03saaydaCountMini6To11() {
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(6, 10, null, Suit.SANS, callerSucceeded = false, isSaayda = true, gameType = GameType.MINI),
    )
  }

  @Test
  fun t03saaydaCountMini6Plus7To12() {
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(7, 11, null, Suit.SPADES, callerSucceeded = false, isSaayda = true, gameType = GameType.MINI),
    )
  }

  @Test
  fun t03saaydaCountMiniRound5BeforeWindowNoExtend() {
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(5, 10, null, Suit.CLUBS, callerSucceeded = false, isSaayda = true, gameType = GameType.MINI),
    )
  }

  @Test
  fun t03saaydaCountMiniTenToEleven() {
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(10, 10, null, Suit.CLUBS, callerSucceeded = false, isSaayda = true, gameType = GameType.MINI),
    )
  }

  @Test
  fun t03saaydaCountFiveSaaydas14To18To23NoRejection() {
    // FULL: five Sa'aydas in 14, 15, 16, 17, 18 → 18 + 5 = 23.
    for (round in 14..18) {
      val currentMax = 18 + (round - 14)
      assertEquals(
        "round $round extends to maxRounds $currentMax",
        RoundExtension(true, ExtensionReason.SAAYDA),
        computeRoundExtension(round, currentMax, null, GameType.FULL.fixedTrumpFor(round),
          callerSucceeded = false, isSaayda = true),
      )
    }
  }

  @Test
  fun t04t05superCallOnlyIfCallerSucceeded() {
    // Overriding trump, caller succeeded → extends.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    // Overriding trump, caller failed → no extend.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = false, isSaayda = false),
    )
    // No callerId (not a Super Call) → no extend even with overriding trump.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, null, Suit.HEARTS, callerSucceeded = false, isSaayda = false),
    )
  }

  @Test
  fun t03t04superCallCombinesWithSaaydaCount() {
    // Round 15 Super Call extends (in window). Round 16 Sa'ayda also extends.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
    assertEquals(
      RoundExtension(true, ExtensionReason.SAAYDA),
      computeRoundExtension(16, 19, null, Suit.HEARTS, callerSucceeded = false, isSaayda = true),
    )
  }

  @Test
  fun t01passIsNotASaaydaNeverExtends() {
    // A pass never reaches this helper as isSaayda=true, but defensively:
    // isSaayda=false means no extension regardless of other facts.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, null, Suit.HEARTS, callerSucceeded = false, isSaayda = false),
    )
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(14, 18, null, Suit.SANS, callerSucceeded = false, isSaayda = false),
    )
  }

  @Test
  fun t04superCallStaysInWindowNoPostWindowExtend() {
    // Round 19 post-window Super Call never extends, even if caller succeeded.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(19, 19, "p3", Suit.SANS, callerSucceeded = true, isSaayda = false),
    )
  }

  // ── D1: the Super Call extension is decided by the LOCKED NUMBER, not the round number ──

  @Test
  fun t05normalInWindowCallerBelowSuperBidNeverExtends() {
    // THE D1 pin: an ordinary auction winner — locked 7, under a trump they
    // chose themselves, succeeding — inside the window. Keying on the round
    // number alone scored exactly this as a Super Call and extended.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false, callerBidAmount = 7),
    )
    // Both window edges behave the same (14 fixed SANS, 18 fixed CLUBS).
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(14, 18, "p1", Suit.SPADES, callerSucceeded = true, isSaayda = false, callerBidAmount = 4),
    )
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(18, 18, "p1", Suit.HEARTS, callerSucceeded = true, isSaayda = false, callerBidAmount = 7),
    )
    // MINI's window too (round 8 fixed HEARTS, override SPADES).
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(8, 10, "p3", Suit.SPADES, callerSucceeded = true, isSaayda = false, gameType = GameType.MINI, callerBidAmount = 5),
    )
  }

  @Test
  fun t05genuineSuperCallByBidAmountExtendsOnlyOnSuccess() {
    // Locked 8 under a replacement trump, caller made it → extends.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false, callerBidAmount = 8),
    )
    // The same call with the Caller failing extends nothing (Q3).
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = false, isSaayda = false, callerBidAmount = 8),
    )
    // A locked 8 at the round's FIXED trump is no trump override → no extend.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(15, 18, "p3", Suit.SPADES, callerSucceeded = true, isSaayda = false, callerBidAmount = 8),
    )
    // MINI: a locked 9 in round 8 (fixed HEARTS) under SPADES → extends.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(8, 10, "p3", Suit.SPADES, callerSucceeded = true, isSaayda = false, gameType = GameType.MINI, callerBidAmount = 9),
    )
  }

  @Test
  fun t05unknownBidAmountKeepsTheLegacyAssumptionForTheOnlineCaller() {
    // The online call site passes no amount yet (a known gap, later lane):
    // a non-null caller at an overridden trump keeps the legacy reading.
    assertEquals(
      RoundExtension(true, ExtensionReason.SUPER_CALL),
      computeRoundExtension(15, 18, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false),
    )
  }

  @Test
  fun t04superBidOutsideTheWindowNeverExtends() {
    // Before MINI's window (round 5) and past FULL's (round 19, fixed SANS,
    // override HEARTS isolates the window guard) — even a genuine 8+ Caller
    // success at an overridden trump adds nothing.
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(5, 10, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false, gameType = GameType.MINI, callerBidAmount = 8),
    )
    assertEquals(
      RoundExtension(false),
      computeRoundExtension(19, 19, "p3", Suit.HEARTS, callerSucceeded = true, isSaayda = false, callerBidAmount = 8),
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
