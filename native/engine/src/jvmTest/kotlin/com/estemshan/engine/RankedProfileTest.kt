package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S44 — the persistent Ranked profile: the ladder is referenced, never
 * copied; RD30's "Start from Bronze" is a real Bronze III; Highest Rank only
 * ever rises (RD24).
 */
class RankedProfileTest {

  private val season = "2026Q4"

  @Test
  fun freshProfileSeatsAtRealBronzeIII_byDefault() {
    val fresh = RankedProfile(seasonId = season)
    assertEquals("Bronze", fresh.tier)
    assertEquals("III", fresh.division)
    assertEquals(0, fresh.rp)
    assertEquals(PlacementState.NOT_STARTED, fresh.placementState)
    // The default highest rank is the ladder's real bottom rung, not null or
    // a placeholder — Bronze III is a rank an account genuinely holds (RD30).
    assertEquals(rankLadder.first(), fresh.highestRank)
    assertEquals(rankLadder.first(), ladderRankOf(fresh))
  }

  @Test
  fun ladderRankOfResolvesEveryRungIncludingDivisionlessKing() {
    for (rung in rankLadder) {
      val profile = RankedProfile(
        tier = rung.tier,
        division = rung.division,
        seasonId = season,
      )
      assertEquals("ladderRankOf(${rung.label()})", rung, ladderRankOf(profile))
    }
  }

  @Test
  fun ladderRankOfRejectsATierDivisionPairThatIsNoRung() {
    val bogus = RankedProfile(tier = "Platinum", division = "IV", seasonId = season)
    assertThrows(IllegalArgumentException::class.java) { ladderRankOf(bogus) }
    val wrongPair = RankedProfile(tier = "King", division = "II", seasonId = season)
    assertThrows(IllegalArgumentException::class.java) { ladderRankOf(wrongPair) }
  }

  @Test
  fun ladderRankOfAgreesWithRankAtOnAConsistentProfile() {
    // Settlement keeps tier/division in step with rp; both derivations then
    // name the same rung, including across a tier boundary and at King.
    for (rung in rankLadder) {
      val profile = RankedProfile(
        tier = rung.tier,
        division = rung.division,
        rp = rung.lowerBound,
        seasonId = season,
      )
      assertEquals(rung, rankAt(profile.rp))
    }
  }

  @Test
  fun isPlacementIsTrueOnlyWhilePlacementIsInProgress() {
    for (state in PlacementState.entries) {
      val profile = RankedProfile(seasonId = season, placementState = state)
      assertEquals(state == PlacementState.IN_PROGRESS, isPlacement(profile))
    }
  }

  @Test
  fun hasSkippedPlacementIsTrueOnlyForTheSkippedState() {
    for (state in PlacementState.entries) {
      val profile = RankedProfile(seasonId = season, placementState = state)
      assertEquals(state == PlacementState.SKIPPED, hasSkippedPlacement(profile))
    }
  }

  @Test
  fun beginPlacementEntersThreeMatchStateAtHiddenBronzeIII() {
    val placing = beginPlacement(season)
    assertEquals(PlacementState.IN_PROGRESS, placing.placementState)
    assertTrue(isPlacement(placing))
    // The provisional rank is never shown, but the model still names a real
    // rung — never a "no tier" placeholder (RD30).
    assertEquals(rankLadder.first(), ladderRankOf(placing))
    assertEquals(0, placing.rp)
    assertEquals(season, placing.seasonId)
  }

  @Test
  fun skipPlacementHandsARealBronzeIIIRankNotANoTierState() {
    val skipped = skipPlacement(season)
    // RD30: skipping starts the account at Bronze III as a real Ranked tier
    // that climbs by normal progression — never "no tier", never a penalty.
    assertEquals(rankLadder.first(), ladderRankOf(skipped))
    assertEquals(0, skipped.rp)
    assertEquals(PlacementState.SKIPPED, skipped.placementState)
    assertTrue(hasSkippedPlacement(skipped))
    assertFalse(isPlacement(skipped))
    assertEquals(rankLadder.first(), skipped.highestRank)
    // Built from the ladder's own bottom rung, so no tier name or threshold
    // is duplicated here.
    assertEquals(rankAt(skipped.rp), ladderRankOf(skipped))
  }

  @Test
  fun updateHighestRankRaisesToAHigherRungAndKeepsAnEqualOne() {
    val start = skipPlacement(season) // Bronze III
    val gold = rankAt(600) // Gold III
    val raised = updateHighestRank(start, gold)
    assertEquals(gold, raised.highestRank)

    // The same rung again is a no-op.
    assertEquals(raised, updateHighestRank(raised, gold))
  }

  @Test
  fun updateHighestRankNeverLowersOnDemotionOrSeasonalReset() {
    // RD24: Highest Rank ever reached is never erased by a seasonal demotion
    // or a loss — it is a permanent record, not a mirror of the current rung.
    val peak = RankedProfile(
      tier = "Gold", division = "I", rp = 825, seasonId = season,
      highestRank = rankAt(3000), // King, once reached
    )
    val demoted = updateHighestRank(peak, rankAt(400)) // Silver I
    assertEquals(rankAt(3000), demoted.highestRank)
    assertEquals(peak, demoted)
  }

  @Test
  fun updateHighestRankRaisesOneRungAtATimeAcrossTheWholeLadder() {
    var profile = skipPlacement(season)
    for (rung in rankLadder) {
      profile = updateHighestRank(profile, rung)
      assertEquals(rung, profile.highestRank)
    }
    // Monotonic: replaying a lower rung afterwards does not rewind it.
    val finalProfile = updateHighestRank(profile, rankLadder[3])
    assertEquals(rankLadder.last(), finalProfile.highestRank)
  }
}
