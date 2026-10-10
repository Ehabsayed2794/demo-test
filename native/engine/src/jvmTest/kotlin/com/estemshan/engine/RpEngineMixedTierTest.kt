package com.estemshan.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S48 — RD5's three mixed-tier directions and RD6's Rank-down ×2 cap.
 *
 * The base delta is passed IN to [withMixedTierAdjustment] rather than
 * computed, so every test isolates the asymmetry from RD4's own symmetric
 * tier scaling — a failure here points at the asymmetry, not the base. The
 * end-to-end [finalRpDelta] tests then re-couple the layers to pin the
 * composition order (RD4 → RD5/RD6 → GM6's ×0.5 last).
 *
 * Default config rates under test: win reduction 0.10/ordinal, win increase
 * 0.10/ordinal, loss multiplier 0.20/ordinal, Rank-down cap 2.0.
 */
class RpEngineMixedTierTest {

  private val config = RpEngineConfig()

  /**
   * A seat at a given SIGNED tier difference. Opponents sit at ordinal 10;
   * the player is placed to produce [tierDifference] (positive = higher
   * tier). Ordinals are unchecked arithmetic inputs, so going past the
   * ladder's 19 rungs is fine — the engine never reads a threshold.
   */
  private fun mixed(
    outcome: MatchOutcome,
    tierDifference: Int,
    rankDown: Boolean = false,
    gameType: GameType = GameType.FULL,
    opponents: Int = 10,
  ): RpMatchInput {
    return RpMatchInput(
      outcome = outcome,
      playerOrdinal = opponents + tierDifference,
      opponentStrength = opponents,
      gameType = gameType,
      rankDown = rankDown,
    )
  }

  // ── RD5's precondition: same tier is not mixed-tier ───────────────────

  @Test
  fun aSameTierTableLeavesTheBaseUntouched() {
    for (base in listOf(30, -30, 0)) {
      assertEquals(base, withMixedTierAdjustment(base, mixed(MatchOutcome.KING, 0)))
      assertEquals(base, withMixedTierAdjustment(base, mixed(MatchOutcome.KOZ, 0)))
    }
  }

  // ── Direction 1: a higher-tier win is worth less ──────────────────────

  @Test
  fun aHigherTierWinIsReduced() {
    val sameTier = withMixedTierAdjustment(30, mixed(MatchOutcome.KING, 0))
    val oneAbove = withMixedTierAdjustment(30, mixed(MatchOutcome.KING, 1))
    val twoAbove = withMixedTierAdjustment(30, mixed(MatchOutcome.KING, 2))

    assertEquals(27, oneAbove, "1 ordinal up: 30 × 0.9 = 27")
    assertEquals(24, twoAbove, "2 ordinals up: 30 × 0.8 = 24")
    assertTrue(oneAbove < sameTier && twoAbove < oneAbove,
      "the reduction must deepen with tier distance")
  }

  // ── Direction 2: a lower-tier win is worth more ───────────────────────

  @Test
  fun aLowerTierWinIsIncreased() {
    val oneBelow = withMixedTierAdjustment(30, mixed(MatchOutcome.KING, -1))
    val twoBelow = withMixedTierAdjustment(30, mixed(MatchOutcome.KING, -2))

    assertEquals(33, oneBelow, "1 ordinal down: 30 × 1.1 = 33")
    assertEquals(36, twoBelow, "2 ordinals down: 30 × 1.2 = 36")
    assertTrue(twoBelow > oneBelow,
      "the increase must deepen with tier distance")
  }

  // ── Direction 3: a higher-tier loss costs more ────────────────────────

  @Test
  fun aHigherTierLossIsMultiplied() {
    val oneAbove = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, 1))
    val twoAbove = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, 2))

    assertEquals(-36, oneAbove, "1 ordinal up: 30 × 1.2 = 36, sign restored → −36")
    assertEquals(-42, twoAbove, "2 ordinals up: 30 × 1.4 = 42 → −42")
    assertTrue(oneAbove < -30 && twoAbove < oneAbove,
      "the loss must deepen with tier distance")
  }

  /**
   * The fourth cell RD5 never names: a lower-tier loser. Not reduced, not
   * multiplied — the symmetric base already covered it, and inventing a
   * fourth direction the closed rules don't name is how the balance becomes
   * unreviewable.
   */
  @Test
  fun aLowerTierLossIsLeftAtTheBase() {
    assertEquals(-30, withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, -1)))
    assertEquals(-30, withMixedTierAdjustment(-30, mixed(MatchOutcome.THIRD, -5)))
  }

  // ── Sign discipline: the adjustment can never invert the award ───────

  @Test
  fun anExtremeWinReductionStopsAtZeroNeverInverts() {
    // 10 ordinals up zeroes the award exactly (1 − 0.1 × 10); beyond that
    // the clamp holds it at 0 rather than letting the reduction run negative.
    assertEquals(0, withMixedTierAdjustment(30, mixed(MatchOutcome.KING, 10)),
      "10 ordinals up: exactly zeroed")
    assertEquals(0, withMixedTierAdjustment(30, mixed(MatchOutcome.KING, 15)),
      "15 ordinals up: clamped at zero, never a loss")
    assertEquals(0, withMixedTierAdjustment(30, mixed(MatchOutcome.KING, 20)))
  }

  @Test
  fun anExtremeMultipliedLossNeverBecomesAGain() {
    // 20 ordinals up, uncapped public: 30 × (1 + 0.2 × 20) = 30 × 5 = 150.
    assertEquals(-150, withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, 20)))
    assertTrue(
      withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, 20)) < 0,
      "an uncapped extreme loss stays a loss",
    )
  }

  @Test
  fun theAdjustmentNeverInvertsTheBaseSignAcrossTheWholeTierRange() {
    for (rankDown in listOf(false, true)) {
      for (tierDifference in -20..20) {
        val gain = withMixedTierAdjustment(30, mixed(MatchOutcome.KING, tierDifference, rankDown))
        val loss = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, tierDifference, rankDown))
        assertTrue(gain >= 0,
          "a gain must never invert: td=$tierDifference rankDown=$rankDown → $gain")
        assertTrue(loss <= 0,
          "a loss must never invert: td=$tierDifference rankDown=$rankDown → $loss")
      }
    }
  }

  // ── RD6: the ×2 cap is Rank-down-only ─────────────────────────────────

  @Test
  fun rankDownCapsTheLossMultiplierAtExactlyTwo() {
    // 6 ordinals up: raw 1 + 0.2 × 6 = 2.2, past the cap.
    val rankDown = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, 6, rankDown = true))
    val public = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, 6, rankDown = false))

    assertEquals(-60, rankDown, "capped at ×2: 30 × 2 = 60 → −60, never −66")
    assertEquals(-66, public, "public is uncapped: 30 × 2.2 = 66 → −66")
    assertTrue(rankDown > public, "the Rank-down cap protects the higher-tier loser")
  }

  @Test
  fun theCapIsNeverExceededAtAnyTierDistance() {
    for (tierDifference in 1..20) {
      val adjusted = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, tierDifference, rankDown = true))
      assertTrue(
        kotlin.math.abs(adjusted) <= 60,
        "RD6: the loss may never exceed ×2 (60), td=$tierDifference → $adjusted",
      )
    }
    // From 5 ordinals up the raw multiplier (1 + 0.2 × 5 = 2.0) reaches the
    // cap, so the award pins at exactly ×2 for every greater distance.
    for (tierDifference in 5..20) {
      assertEquals(
        -60,
        withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, tierDifference, rankDown = true)),
        "past the cap the loss pins at ×2: td=$tierDifference",
      )
    }
  }

  @Test
  fun theCapDoesNotBindWithinRD29sOneTierBand() {
    // Public and Rank-down agree at one tier apart (raw 1.2 < the 2.0 cap),
    // so RD29's permitted band is unaffected by the cap either way.
    for (rankDown in listOf(false, true)) {
      assertEquals(
        -36,
        withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, 1, rankDown = rankDown)),
        "one tier apart: cap inactive, rankDown=$rankDown",
      )
    }
  }

  /**
   * The interaction the owner's pre-merge question turns on. RD29 compares by
   * TIER, never by ordinal, so the widest gap the one-tier band admits is
   * division I of the upper tier against division III of the lower. Derived
   * from the real [rankLadder], not hardcoded, so a ladder change breaks this
   * and forces the cap decision to be re-taken consciously.
   *
   * At the default 0.20 rate that gap reaches the cap EXACTLY (1 + 0.2 × 5 =
   * 2.0), so the cap is a no-op across the whole eligible band in both modes.
   * This pins that contract: if a retune pushes the rate up, the cap starts
   * binding and Rank-down's award diverges from public — which is a
   * deliberate rule consequence, not a silent regression.
   */
  @Test
  fun theCapReachesTheRankDownCeilingExactlyAtRD29sWidestBand() {
    val widestEligibleGap = widestOneTierApartGap()
    assertEquals(5, widestEligibleGap, "the widest gap RD29 admits, in ladder ordinals")

    val rawAtEdge = 1.0 + config.higherTierLossMultiplierPerOrdinal * widestEligibleGap
    assertEquals(
      config.rankDownLossMultiplierCap, rawAtEdge,
      "the raw loss multiplier reaches the cap exactly at the band's edge",
    )

    // Across the entire eligible band the two modes agree — the cap never
    // alters an output for a table RD29 can actually form.
    for (gap in 0..widestEligibleGap) {
      val public = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, gap, rankDown = false))
      val rankDown = withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, gap, rankDown = true))
      assertEquals(
        public, rankDown,
        "the cap must not bind anywhere in RD29's band: gap=$gap",
      )
    }

    // One ordinal PAST the band the cap bites — this is the guardrail, and it
    // is Rank-down-only, as the rule text scopes it.
    assertNotEquals(
      withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, widestEligibleGap + 1, rankDown = false)),
      withMixedTierAdjustment(-30, mixed(MatchOutcome.KOZ, widestEligibleGap + 1, rankDown = true)),
      "past the band only Rank-down is capped",
    )
  }

  /**
   * The widest ordinal distance two ADJACENT ladder tiers can span: division I
   * of the upper tier minus division III of the lower. RD29 admits tiers
   * differing by at most one, compared by tier, so this is the band's edge.
   */
  private fun widestOneTierApartGap(): Int {
    val byTier = rankLadder.withIndex().groupBy { it.value.tier }
    val tierOrder = rankLadder.map { it.tier }.distinct()
    var widest = 0
    for (i in 0 until tierOrder.size - 1) {
      val lower = byTier[tierOrder[i]]!!
      val upper = byTier[tierOrder[i + 1]]!!
      widest = maxOf(widest, upper.last().index - lower.first().index)
    }
    return widest
  }

  // ── Composition: GM6's ×0.5 applies last, after the asymmetry ────────

  @Test
  fun miniHalvesTheRankDownLossAfterTheCap() {
    // base −18 (Koz at opponents 10, 6 ordinals up), Rank-down: |−18| × 2.0
    // = 36 — the raw 2.2 multiplier is capped.
    val full = finalRpDelta(mixed(MatchOutcome.KOZ, 6, rankDown = true, gameType = GameType.FULL), config)
    val mini = finalRpDelta(mixed(MatchOutcome.KOZ, 6, rankDown = true, gameType = GameType.MINI), config)
    assertEquals(-36, full)
    assertEquals(-18, mini, "GM6 halves the capped loss exactly (even delta)")
  }

  @Test
  fun miniRoundsTheAsymmetricLossAwayFromZeroAfterTheMultiplier() {
    // An ODD full delta is what makes the composition order observable: half
    // of it is a .5 tie, so OPEN-3 decides it. baseKozLoss −21 at opponents 0
    // gives base −24; ×1.2 = 28.8 → 29 (odd) → −29, and −29 × 0.5 = −14.5
    // rounds AWAY from zero to −15, never −14. The cap is inactive at one
    // tier apart, so public and Rank-down agree here.
    val odd = config.copy(baseKozLoss = -21)
    for (rankDown in listOf(false, true)) {
      val full = finalRpDelta(mixed(MatchOutcome.KOZ, 1, rankDown, GameType.FULL, opponents = 0), odd)
      val mini = finalRpDelta(mixed(MatchOutcome.KOZ, 1, rankDown, GameType.MINI, opponents = 0), odd)
      assertEquals(-29, full, "the asymmetric loss lands on an odd value: rankDown=$rankDown")
      assertEquals(-15, mini, "−29 × 0.5 = −14.5 → −15 away from zero: rankDown=$rankDown")
      assertEquals(roundDelta(full * 0.5), mini, "mini is half of the final full delta, rounded once")
    }
  }

  @Test
  fun theAsymmetryAppliesBeforeTheMiniMultiplierNeverAfter() {
    for (rankDown in listOf(false, true)) {
      for (tierDifference in -6..6) {
        val full = finalRpDelta(mixed(MatchOutcome.KING, tierDifference, rankDown), config)
        val mini = finalRpDelta(mixed(MatchOutcome.KING, tierDifference, rankDown, GameType.MINI), config)
        assertEquals(
          roundDelta(full * 0.5), mini,
          "GM6 applies last: td=$tierDifference rankDown=$rankDown",
        )
      }
    }
  }
}
