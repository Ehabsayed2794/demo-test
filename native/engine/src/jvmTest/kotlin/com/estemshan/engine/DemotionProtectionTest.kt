package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S49 — the one-match demotion shield (RD7): armed by promotion, expired
 * by the next resolved match whatever its outcome, negating exactly one
 * would-be demotion at the current rung's floor. King uniform, no King
 * branch.
 *
 * The shield mechanics (arm-on-promotion, expire-after-one, floor-clamp)
 * are S49 judgment inside RD7's closed envelope, pinned here literally —
 * including the exact held RP/delta of a blocked demotion — so any
 * reshaping fails loudly.
 */
class DemotionProtectionTest {

  private fun shieldedProfile(): RankedProfile =
    RankedProfile(seasonId = "S1", demotionShield = true)

  // ==========================================================================
  //  Arming: promotion and nothing else
  // ==========================================================================

  @Test
  fun aFreshProfileCarriesNoShield() {
    assertFalse(hasDemotionShield(RankedProfile(seasonId = "S1")))
  }

  @Test
  fun promotionArmsTheShield() {
    // 140 is Bronze II (75); +15 → 155, Bronze I (150): a climb.
    val promotion = resolveRanked(previousRP = 140, delta = 15)
    assertEquals(1, promotion.movement)
    val armed = armShieldOnPromotion(RankedProfile(seasonId = "S1"), promotion)
    assertTrue(hasDemotionShield(armed))
  }

  @Test
  fun neutralAndDemotingMatchesNeverArm() {
    val neutral = resolveRanked(previousRP = 100, delta = 10)
    assertEquals(0, neutral.movement)
    assertFalse(hasDemotionShield(armShieldOnPromotion(RankedProfile(seasonId = "S1"), neutral)))

    val demotion = resolveRanked(previousRP = 80, delta = -10)
    assertEquals(-1, demotion.movement)
    assertFalse(hasDemotionShield(armShieldOnPromotion(RankedProfile(seasonId = "S1"), demotion)))
  }

  @Test
  fun theShieldNeverStacks() {
    val promotion = resolveRanked(previousRP = 140, delta = 15)
    val rearmed = armShieldOnPromotion(shieldedProfile(), promotion)
    assertTrue("arming an armed profile stays a single shield", hasDemotionShield(rearmed))
  }

  // ==========================================================================
  //  Countdown of one: the next match consumes, whatever happens
  // ==========================================================================

  @Test
  fun anUnarmedProfilePassesThroughUntouched() {
    val demotion = resolveRanked(previousRP = 80, delta = -10)
    val out = applyDemotionShield(RankedProfile(seasonId = "S1"), demotion)
    assertEquals("no shield, no change", demotion, out.resolution)
    assertFalse(out.shieldConsumed)
    assertFalse(out.demotionBlocked)
    assertFalse(hasDemotionShield(out.profile))
  }

  @Test
  fun aNeutralMatchExpiresTheShieldWithoutChangingTheResult() {
    val neutral = resolveRanked(previousRP = 100, delta = 10)
    val out = applyDemotionShield(shieldedProfile(), neutral)
    assertEquals("the countdown expires even with no demotion", neutral, out.resolution)
    assertTrue(out.shieldConsumed)
    assertFalse(out.demotionBlocked)
    assertFalse(hasDemotionShield(out.profile))
  }

  @Test
  fun aShieldedPromotionConsumesThenRearmsThroughThePipeline() {
    // The settlement pipeline composes consume-then-arm: a promotion while
    // armed spends the old shield and earns the new one — net still armed.
    val promotion = resolveRanked(previousRP = 140, delta = 15)
    val applied = applyDemotionShield(shieldedProfile(), promotion)
    assertTrue(applied.shieldConsumed)
    assertFalse(applied.demotionBlocked)
    val next = armShieldOnPromotion(applied.profile, applied.resolution)
    assertTrue("promotion re-arms after the old shield is spent", hasDemotionShield(next))
  }

  @Test
  fun aFlooredLossStillExpiresTheShield() {
    // Bronze III at 0 losing 50: RP floors at 0 with no demotion — but the
    // countdown still ticks. Protection is one match, not one demotion.
    val floored = resolveRanked(previousRP = 0, delta = -50)
    assertEquals(0, floored.movement)
    val out = applyDemotionShield(shieldedProfile(), floored)
    assertEquals(floored, out.resolution)
    assertTrue(out.shieldConsumed)
    assertFalse(out.demotionBlocked)
  }

  // ==========================================================================
  //  The block: hold the rung floor, record the loss
  // ==========================================================================

  @Test
  fun aBlockedDemotionHoldsTheRungFloorWithATruthfulDelta() {
    // Hand-derived: 750 is Gold II (floor 700); −60 → 690, Gold III —
    // a one-step demotion. Shielded: RP holds at 700, rank holds Gold II,
    // movement settles to 0, and the delta stays −50 (the loss is
    // recorded; only the rank is saved).
    val demotion = resolveRanked(previousRP = 750, delta = -60)
    assertEquals(690, demotion.rp)
    assertEquals(-1, demotion.movement)

    val out = applyDemotionShield(shieldedProfile(), demotion)
    assertTrue(out.shieldConsumed)
    assertTrue(out.demotionBlocked)
    assertEquals(700, out.resolution.rp)
    assertEquals(-50, out.resolution.delta)
    assertEquals("Gold", out.resolution.next.tier)
    assertEquals("II", out.resolution.next.division)
    assertEquals(0, out.resolution.movement)
    assertFalse(hasDemotionShield(out.profile))
  }

  @Test
  fun theSameFallWithoutAShieldDemotesNormally() {
    val demotion = resolveRanked(previousRP = 750, delta = -60)
    val out = applyDemotionShield(RankedProfile(seasonId = "S1"), demotion)
    assertEquals(demotion, out.resolution)
    assertEquals(-1, out.resolution.movement)
    assertEquals(690, out.resolution.rp)
  }

  @Test
  fun aShieldedKingHoldsThreeThousandUniformly() {
    // 3050 is King; −100 → 2950, Royal I. No King branch: the same floor
    // rule holds King at its 3,000 lower bound.
    val fall = resolveRanked(previousRP = 3050, delta = -100)
    assertEquals(-1, fall.movement)

    val out = applyDemotionShield(shieldedProfile(), fall)
    assertTrue(out.demotionBlocked)
    assertEquals(3000, out.resolution.rp)
    assertTrue(out.resolution.next.isKing)
    assertEquals(0, out.resolution.movement)
    assertFalse(hasDemotionShield(out.profile))
  }

  @Test
  fun aBlockedShieldCannotBlockTwice() {
    // After one block the profile is disarmed: feeding the same demotion
    // again passes through and demotes.
    val demotion = resolveRanked(previousRP = 750, delta = -60)
    val first = applyDemotionShield(shieldedProfile(), demotion)
    assertTrue(first.demotionBlocked)
    val second = applyDemotionShield(first.profile, demotion)
    assertFalse(second.demotionBlocked)
    assertEquals(-1, second.resolution.movement)
    assertEquals(690, second.resolution.rp)
  }
}
