package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S59 — the season-reset math (RD24): two division steps down the single
 * ladder, floored at Bronze III, King → Royal I by owner decision, RP
 * restarted at the new rung's floor, Highest/placement carried, shield
 * cleared, stats structurally untouched (never taken).
 */
class SeasonResetTest {

  private fun profile(
    tier: String,
    division: String,
    rp: Int,
    seasonId: String = "S1",
    highestRank: LadderRank = rankLadder.first(),
    placementState: PlacementState = PlacementState.NOT_STARTED,
    shield: Boolean = false,
  ): RankedProfile = RankedProfile(
    tier = tier,
    division = division,
    rp = rp,
    seasonId = seasonId,
    highestRank = highestRank,
    placementState = placementState,
    demotionShield = shield,
  )

  @Test
  fun theResetStepsAreTheClosedTwo() {
    assertEquals("RD24: exactly two division steps", 2, SEASON_RESET_STEPS)
  }

  @Test
  fun goldIDemotesTwoStepsToGoldIIIAtItsFloor() {
    // RD24's own example, asserted literally: Gold I → Gold III.
    val out = applySeasonReset(profile("Gold", "I", 830), "S2")
    assertEquals("Gold", out.tier)
    assertEquals("III", out.division)
    assertEquals("RP restarts at the new rung's floor", 575, out.rp)
    assertEquals("S2", out.seasonId)
  }

  @Test
  fun stepsCrossTierBoundariesAndFloors() {
    // 750 is Gold II; two rungs down the single ladder is Silver I (450).
    val crossTier = applySeasonReset(profile("Gold", "II", 750), "S2")
    assertEquals("Silver", crossTier.tier)
    assertEquals("I", crossTier.division)
    assertEquals(450, crossTier.rp)

    // Royal II (2500) steps to Diamond I (2000).
    val royal = applySeasonReset(profile("Royal", "II", 2600), "S2")
    assertEquals("Diamond", royal.tier)
    assertEquals("I", royal.division)
    assertEquals(2000, royal.rp)

    // Bronze II floors at Bronze III (0): one step would leave the ladder.
    val floored = applySeasonReset(profile("Bronze", "II", 100), "S2")
    assertEquals("Bronze", floored.tier)
    assertEquals("III", floored.division)
    assertEquals(0, floored.rp)

    // Bronze III stays Bronze III.
    val bottom = applySeasonReset(profile("Bronze", "III", 0), "S2")
    assertEquals("Bronze", bottom.tier)
    assertEquals("III", bottom.division)
    assertEquals(0, bottom.rp)
  }

  @Test
  fun kingLandsOnRoyalIByDecisionNotByStepping() {
    // Index − 2 from King would be Royal III — RD24 overrides it:
    // King has no divisions to step through, so the destination is Royal I.
    val platinumI = rankLadder.first { it.tier == "Platinum" && it.division == "I" }
    assertEquals(1400, platinumI.lowerBound)
    val out = applySeasonReset(profile("King", "", 3100), "S2")
    assertEquals("Royal", out.tier)
    assertEquals("I", out.division)
    assertEquals(2750, out.rp)
  }

  @Test
  fun highestRankIsNeverLoweredByTheReset() {
    val peak = rankLadder.first { it.tier == "Platinum" && it.division == "II" }
    val out = applySeasonReset(
      profile("Gold", "I", 830, highestRank = peak),
      "S2",
    )
    assertEquals("Highest Rank survives the seasonal demotion", peak, out.highestRank)
  }

  @Test
  fun placementStateCarriesOverUntouched() {
    // RD10: placement never repeats per season — S59 must not reopen it.
    val done = applySeasonReset(
      profile("Silver", "I", 460, placementState = PlacementState.COMPLETE),
      "S2",
    )
    assertEquals(PlacementState.COMPLETE, done.placementState)

    val skipped = applySeasonReset(
      profile("Bronze", "III", 0, placementState = PlacementState.SKIPPED),
      "S2",
    )
    assertEquals("skipping stays irreversible across the reset", PlacementState.SKIPPED, skipped.placementState)
  }

  @Test
  fun theShieldDoesNotCrossTheSeasonBoundary() {
    val out = applySeasonReset(profile("Gold", "I", 830, shield = true), "S2")
    assertFalse("a one-match shield cannot survive a 3-month reset", hasDemotionShield(out))
    assertEquals(0, shieldMatchesRemaining(out))
  }

  @Test
  fun aResetAlwaysOpensANamedSeason() {
    assertThrows(IllegalArgumentException::class.java) {
      applySeasonReset(profile("Gold", "I", 830), "")
    }
    assertThrows(IllegalArgumentException::class.java) {
      applySeasonReset(profile("Gold", "I", 830), "   ")
    }
  }

  @Test
  fun everyRungLandsAtOrBelowItselfAndNeverAbovePlatinum() {
    // Sweep the whole ladder: no reset climbs, nothing lands above the
    // start rung, King is the only special case.
    rankLadder.forEach { rung ->
      val landed = seasonResetRank(rung)
      assertTrue(
        "${rung.tier} ${rung.division} must not climb on reset",
        rankLadder.indexOf(landed) <= rankLadder.indexOf(rung),
      )
      if (!rung.isKing) {
        val expected = rankLadder[(rankLadder.indexOf(rung) - SEASON_RESET_STEPS).coerceAtLeast(0)]
        assertEquals("two steps down, floored", expected, landed)
      }
    }
    assertEquals(
      "Royal",
      seasonResetRank(rankLadder.first { it.isKing }).tier,
    )
  }
}
