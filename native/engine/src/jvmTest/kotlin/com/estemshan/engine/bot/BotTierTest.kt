package com.estemshan.engine.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12's tests for the tier's player-facing chrome — [BotTier.displayName] and
 * [BotTier.blurb], added so the Choose Level lobby reads its labels from the
 * enum instead of a parallel table that can drift out of sync.
 *
 * What these pin:
 *
 *  * **The chrome is complete and unambiguous.** Every tier ships a label and
 *    a blurb, and the labels are unique — the lobby keys seat rows off them,
 *    so a collision or a blank would surface as a mislabeled seat rather than
 *    a crash.
 *  * **The config survived gaining two constructor parameters.** The four
 *    behaviour numbers are pinned verbatim because S12 appended `displayName`
 *    and `blurb` to the constructor; a future reordering of that parameter
 *    list would otherwise shift a tier's skill silently and only show up as
 *    subtly different bidding.
 *  * **No tier sells the deferred Monte-Carlo path.** `usesSimulation` is
 *    post-launch, and until it lands even EXPERT plays the heuristic path, so
 *    a blurb advertising it would be a promise the build doesn't keep.
 */
class BotTierTest {

  @Test
  fun everyTierCarriesALabelAndABlurb() {
    for (tier in BotTier.entries) {
      assertTrue("tier ${tier.name} has no displayName", tier.displayName.isNotBlank())
      assertTrue("tier ${tier.name} has no blurb", tier.blurb.isNotBlank())
    }
  }

  @Test
  fun tierLabelsAreUniqueSoTheLobbyCanKeyOffThem() {
    val labels = BotTier.entries.map { it.displayName }
    assertEquals("two tiers share a displayName", labels.size, labels.toSet().size)
  }

  @Test
  fun theFourConfigNumbersSurvivedTheMetadataAppend() {
    // Pinned verbatim: see the constructor in BotTier.kt. A reordering of the
    // parameter list is the failure mode this catches.
    assertEquals(0.25, BotTier.EASY.mistakeRate, 1e-9)
    assertEquals(0.30, BotTier.EASY.distributionConfidence, 1e-9)
    assertEquals(1.20, BotTier.EASY.bidNoise, 1e-9)
    assertFalse(BotTier.EASY.canDash)
    assertFalse(BotTier.EASY.countsCards)
    assertFalse(BotTier.EASY.modelsOpponents)
    assertFalse(BotTier.EASY.usesSimulation)

    assertEquals(0.10, BotTier.MEDIUM.mistakeRate, 1e-9)
    assertTrue(BotTier.MEDIUM.canDash)

    assertEquals(0.03, BotTier.HARD.mistakeRate, 1e-9)
    assertTrue(BotTier.HARD.countsCards)
    assertTrue(BotTier.HARD.modelsOpponents)

    assertEquals(0.00, BotTier.EXPERT.mistakeRate, 1e-9)
    assertTrue(BotTier.EXPERT.usesSimulation)
  }

  @Test
  fun noBlurbAdvertisesTheDeferredSimulation() {
    // EXPERT is `usesSimulation = true`, but that path is post-launch: until
    // it lands every tier plays the heuristic path, so no blurb may claim it.
    for (tier in BotTier.entries) {
      val text = (tier.displayName + " " + tier.blurb).lowercase()
      assertFalse(
        "tier ${tier.name} advertises Monte-Carlo, which is deferred: '$text'",
        text.contains("monte") || text.contains("simulation"),
      )
    }
  }
}
