package com.estemshan.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S47 — the RP engine's contract. The rule layers are tested separately so
 * a failure points at the rule, not the arithmetic:
 *
 * - [computeBaseDelta] — RD4's five inputs, sign discipline.
 * - [finalRpDelta] — GM6's ×0.5 and the OPEN-3 rounding (the mandatory
 *   `Math.round` asymmetry test lives in [miniRoundingIsSymmetricAwayFromZero]).
 */
class RpEngineTest {

  private val config = RpEngineConfig(
    baseKingGain = 30,
    baseSecondGain = 15,
    baseThirdLoss = -8,
    baseKozLoss = -20,
    perOpponentStrengthPoint = 2,
    perTierDifferencePoint = 3,
  )

  private fun input(outcome: MatchOutcome, player: Int, opponents: Int, type: GameType = GameType.FULL) =
    RpMatchInput(outcome, player, opponents, type)

  // ── RD4: sign discipline — the outcome sets the sign, nothing else ────

  @Test
  fun aMatchKingNeverLosesRp() {
    // A King sitting 15 ordinals above a weak table: the tier scaling
    // overshoots (the raw sum is negative), but the outcome owns the sign.
    assertEquals(0, computeBaseDelta(input(MatchOutcome.KING, 18, 3), config),
      "the worst case for a win scales to zero, never into a loss")
    // And away from the overshoot the win is still a real gain.
    assertTrue(computeBaseDelta(input(MatchOutcome.KING, 6, 9), config) > 0)
  }

  @Test
  fun kozNeverGainsRp() {
    // Koz at a strong table, 18 ordinals below it — the strength bonus
    // overshoots positively, but last place never awards RP.
    assertEquals(0, computeBaseDelta(input(MatchOutcome.KOZ, 0, 18), config),
      "the best case for last place scales to zero, never into a gain")
    assertTrue(computeBaseDelta(input(MatchOutcome.KOZ, 6, 7), config) < 0)
  }

  @Test
  fun secondGainsAndThirdLoses() {
    assertTrue(
      computeBaseDelta(input(MatchOutcome.SECOND, 3, 3), config) > 0,
      "2nd is a positive award",
    )
    assertTrue(
      computeBaseDelta(input(MatchOutcome.THIRD, 3, 3), config) < 0,
      "3rd is a loss",
    )
  }

  // ── RD4: the five inputs actually move the number ─────────────────────

  @Test
  fun strongerOpponentsPayMoreForTheSameOutcome() {
    val weak = computeBaseDelta(input(MatchOutcome.KING, 5, 3), config)
    val strong = computeBaseDelta(input(MatchOutcome.KING, 5, 12), config)
    assertTrue(strong > weak, "beating a stronger table must pay more: $strong vs $weak")
  }

  @Test
  fun aHigherTierWinIsWorthLessBase() {
    // RD4's symmetric tier scaling (the RD5 asymmetry is separate and not
    // yet built): same outcome, same table, but the player outranks it.
    val lowerTier = computeBaseDelta(input(MatchOutcome.KING, 3, 10), config)
    val higherTier = computeBaseDelta(input(MatchOutcome.KING, 14, 10), config)
    assertTrue(
      higherTier < lowerTier,
      "a higher-tier player's base win must be worth less: $higherTier vs $lowerTier",
    )
  }

  @Test
  fun tierDifferenceIsTheSignedDifferenceOfOrdinals() {
    val i = input(MatchOutcome.KING, 10, 7)
    assertEquals(3, i.tierDifference(), "positive = the player sits above the mean")
    assertEquals(-3, input(MatchOutcome.KING, 4, 7).tierDifference())
  }

  @Test
  fun baseIsExactlyTheConfiguredArithmetic() {
    // No hidden terms: base + strength(opponents) + tier(player − opponents),
    // with the sign from outcome. This case stays positive so it also proves
    // the clamp is not silently re-shaping ordinary awards.
    assertEquals(
      30 + (4 - 0) * 2 - (10 - 4) * 3,
      computeBaseDelta(input(MatchOutcome.KING, 10, 4), config),
    )
  }

  // ── GM6: FULL is a bit-identical no-op ────────────────────────────────

  @Test
  fun fullMultiplierIsExactlyOneAndARoundNoOp() {
    // Same-tier seat, so RD5's asymmetry is a no-op too and the ONLY thing
    // under test is GM6: for FULL the gameType multiplier is exactly 1.0 and
    // the round is a no-op, so Full is bit-identical to no multiplier.
    // (A mixed-tier input would now move under S48's RD5 layer, which is a
    // different rule — see RpEngineMixedTierTest.)
    val base = computeBaseDelta(input(MatchOutcome.KING, 6, 6), config)
    assertEquals(base, finalRpDelta(input(MatchOutcome.KING, 6, 6, GameType.FULL), config))
  }

  @Test
  fun miniIsExactlyHalfOfFullForEvenDeltas() {
    // Same-tier seats throughout, isolating GM6 from RD5. Every even full
    // delta must halve exactly — no rounding artefact.
    for (ordinal in 0..18) {
      for (outcome in MatchOutcome.entries) {
        val full = finalRpDelta(input(outcome, ordinal, ordinal, GameType.FULL), config)
        val mini = finalRpDelta(input(outcome, ordinal, ordinal, GameType.MINI), config)
        if (full % 2 == 0) {
          assertEquals(full / 2, mini, "MINI must be exactly half of FULL for $outcome at ordinal $ordinal")
        }
      }
    }
  }

  @Test
  fun miniIsAppliedOnceToTheFinalDeltaNeverPerComponent() {
    // The multiplier applies to the whole base and the round happens once,
    // after it. An ODD full delta makes that single round observable: half
    // is a .5 tie, so the closed away-from-zero rule decides it.
    val full = computeBaseDelta(input(MatchOutcome.KOZ, 2, 5), config)
    val mini = finalRpDelta(input(MatchOutcome.KOZ, 2, 5, GameType.MINI), config)
    assertEquals(-1, full)
    assertEquals(-1, mini, "−1 × 0.5 = −0.5 rounds away from zero to −1")
  }

  // ── OPEN-3 (CLOSED): ties away from zero, symmetric ───────────────────

  @Test
  fun roundDeltaRoundsTiesAwayFromZero() {
    assertEquals(8, roundDelta(7.5), "+7.5 ties away from zero → 8")
    assertEquals(-8, roundDelta(-7.5), "−7.5 ties away from zero → −8 — NOT −7")
    assertEquals(5, roundDelta(4.5))
    assertEquals(-5, roundDelta(-4.5))
    // Even halves are exact — no rounding artefact at all.
    assertEquals(10, roundDelta(10.0))
    assertEquals(-7, roundDelta(-7.0))
  }

  @Test
  fun miniRoundingIsSymmetricForGainsAndLosses() {
    // The four closed tie cases, asserted as pairs: a gain and its equal
    // loss round to mirror images, never toward zero.
    assertEquals(8, roundDelta(15 * 0.5), "+15 → +8")
    assertEquals(-8, roundDelta(-15 * 0.5), "−15 → −8")
    assertEquals(5, roundDelta(9 * 0.5), "+9 → +5")
    assertEquals(-5, roundDelta(-9 * 0.5), "−9 → −5")
    // Even deltas stay exact.
    assertEquals(10, roundDelta(20 * 0.5), "+20 → +10")
    assertEquals(-7, roundDelta(-14 * 0.5), "−14 → −7")
  }

  /**
   * THE mandatory regression guard (OPEN-3). `java.lang.Math.round` rounds
   * half-up toward +∞, so it maps −7.5 to **−7** — the asymmetry that would
   * silently make every odd Mini loss one RP cheaper than its gain. This
   * test documents the trap by asserting the wrong library's answer is
   * wrong, so a future refactor that swaps in `Math.round` fails here
   * rather than in production.
   */
  @Test
  fun mathRoundIsAsymmetricAndIsNotOurRule() {
    assertEquals(-7, java.lang.Math.round(-7.5), "Math.round documents the trap: −7.5 → −7")
    assertEquals(-8, roundDelta(-7.5), "our rule is −7.5 → −8, away from zero")
    // The two rules agree on positive ties and disagree on negative ones —
    // which is exactly why the loss side is where the bug lands.
    assertEquals(java.lang.Math.round(7.5).toInt(), roundDelta(7.5))
    assertTrue(
      java.lang.Math.round(-7.5).toInt() != roundDelta(-7.5),
      "the two rules must differ on negative ties — if they agree, the guard is stale",
    )
  }

  // ── The engine stays independent of the ladder's threshold constants ──

  @Test
  fun theEngineProducesADeltaTheLadderConsumes() {
    // RD4's independence: the engine never reads a threshold. It hands a
    // signed Int to resolveRanked, which is the only place the 19 closed
    // RD26 values live.
    val delta = finalRpDelta(input(MatchOutcome.KING, 6, 12), config)
    val previousRP = 300 // Silver III
    val resolution = resolveRanked(previousRP, delta)
    assertTrue(resolution.rp >= 0, "RD3: RP never goes negative")
    assertEquals((previousRP + delta).coerceAtLeast(0), resolution.rp)
  }

  @Test
  fun aLossAtTheLadderFloorClampsToZeroNotNegative() {
    val delta = finalRpDelta(input(MatchOutcome.KOZ, 0, 3), config)
    assertTrue(delta < 0)
    val resolution = resolveRanked(0, delta)
    assertEquals(0, resolution.rp, "RD3: the floor clamps a loss to 0")
    assertEquals(0, resolution.delta, "the effective delta is 0 after the clamp")
  }

  @Test
  fun configChangesMoveTheAwardWithoutTouchingTheLadder() {
    // Tunability: retune the base and the ladder file is untouched.
    val tuned = config.copy(baseKingGain = 60)
    val default = finalRpDelta(input(MatchOutcome.KING, 6, 6), config)
    val retuned = finalRpDelta(input(MatchOutcome.KING, 6, 6), tuned)
    assertTrue(retuned > default, "raising baseKingGain must raise the award")
    // The ladder's thresholds are not in the config and cannot drift here.
    assertEquals(0, rankLadder.first().lowerBound, "Bronze III stays at 0")
    assertEquals(3000, rankLadder.last().lowerBound, "King stays at 3,000")
  }
}
