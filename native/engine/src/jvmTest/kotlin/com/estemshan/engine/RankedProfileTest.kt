package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
  fun placementCanBeStartedOnlyFromAnAccountThatNeverDecided() {
    // RD10 + RD30: only NOT_STARTED may enter placement. Once skipped it can
    // never start later (RD30); once complete or underway it never repeats
    // (RD10 — once per account, never per season).
    for (state in PlacementState.entries) {
      val profile = RankedProfile(seasonId = season, placementState = state)
      assertEquals(state == PlacementState.NOT_STARTED, canStartPlacement(profile))
    }
    // The RD30 path itself closes the door behind it.
    assertFalse(canStartPlacement(skipPlacement(season)))
    assertFalse(canStartPlacement(beginPlacement(season)))
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

  @Test
  fun progressionIsConsistentAtEveryRungsLowerBound() {
    for (rung in rankLadder) {
      val profile = RankedProfile(
        tier = rung.tier,
        division = rung.division,
        rp = rung.lowerBound,
        seasonId = season,
      )
      assertNull(
        "progressionMismatch(${rung.label()} at ${rung.lowerBound})",
        progressionMismatch(profile),
      )
    }
  }

  @Test
  fun progressionStaysConsistentThroughTheLastRpBelowTheNextRung() {
    for (index in 0 until rankLadder.lastIndex) {
      val rung = rankLadder[index]
      val next = rankLadder[index + 1]
      val rp = next.lowerBound - 1
      val profile = RankedProfile(
        tier = rung.tier,
        division = rung.division,
        rp = rp,
        seasonId = season,
      )
      assertEquals(rung, rankAt(rp))
      assertNull(
        "progressionMismatch(${rung.label()} at $rp)",
        progressionMismatch(profile),
      )
    }

    val king = rankLadder.last()
    val kingProfile = RankedProfile(
      tier = king.tier,
      division = king.division,
      rp = Int.MAX_VALUE,
      seasonId = season,
    )
    assertEquals(king, rankAt(Int.MAX_VALUE))
    assertNull(progressionMismatch(kingProfile))
  }

  @Test
  fun progressionMismatchNamesTheRpAndBothRungs() {
    val cases = listOf(
      RankedProfile(tier = "Gold", division = "I", rp = 0, seasonId = season),
      RankedProfile(tier = "Bronze", division = "III", rp = 150, seasonId = season),
      RankedProfile(tier = "King", division = "", rp = 2999, seasonId = season),
      RankedProfile(tier = "Silver", division = "I", rp = 449, seasonId = season),
    )
    for (profile in cases) {
      val message = progressionMismatch(profile)
      assertTrue(
        "expected a mismatch for ${profile.tier} ${profile.division} at rp=${profile.rp}",
        message != null,
      )
      val text = requireNotNull(message)
      val resolved = rankAt(profile.rp)
      val stored = ladderRankOf(profile)
      assertTrue("message must be non-blank: '$text'", text.isNotBlank())
      assertTrue("message must name rp=${profile.rp}: '$text'", text.contains("rp=${profile.rp}"))
      assertTrue(
        "message must name the rung resolved from rp (${resolved.label()}): '$text'",
        text.contains(resolved.label()),
      )
      assertTrue(
        "message must name the stored rung (${stored.label()}): '$text'",
        text.contains(stored.label()),
      )
    }
  }

  @Test
  fun progressionMismatchRejectsNegativeRp() {
    val profile = RankedProfile(
      tier = "Bronze",
      division = "III",
      rp = -1,
      seasonId = season,
    )
    val message = progressionMismatch(profile)
    assertTrue("expected a negative-rp mismatch", message != null)
    val text = requireNotNull(message)
    assertTrue("message must be non-blank: '$text'", text.isNotBlank())
    assertTrue("message must name the negative rp: '$text'", text.contains("rp=-1"))
  }

  @Test
  fun progressionMismatchLetsAnUnknownTierDivisionPairThrow() {
    val bogus = RankedProfile(tier = "Platinum", division = "IV", seasonId = season)
    assertThrows(IllegalArgumentException::class.java) { progressionMismatch(bogus) }
  }

  @Test
  fun requireProgressionConsistentThrowsTheMismatchMessage() {
    val mismatch = RankedProfile(
      tier = "Gold",
      division = "I",
      rp = 0,
      seasonId = season,
    )
    val expected = progressionMismatch(mismatch)
    assertTrue("expected a mismatch", expected != null)
    val thrown = assertThrows(IllegalArgumentException::class.java) {
      requireProgressionConsistent(mismatch)
    }
    assertEquals(expected, thrown.message)
    assertFalse(thrown.message.isNullOrBlank())
  }

  @Test
  fun requireProgressionConsistentIsANoOpForAValidProfile() {
    val valid = RankedProfile(
      tier = "Silver",
      division = "II",
      rp = 400,
      seasonId = season,
    )
    assertNull(progressionMismatch(valid))
    requireProgressionConsistent(valid)
  }

  @Test
  fun placementProfilesRemainConsistentAtBronzeIIIAndZero() {
    val profiles = listOf(
      RankedProfile(seasonId = season),
      beginPlacement(season),
      skipPlacement(season),
    )
    for (profile in profiles) {
      assertEquals("Bronze", profile.tier)
      assertEquals("III", profile.division)
      assertEquals(0, profile.rp)
      assertNull(
        "progressionMismatch(${profile.placementState})",
        progressionMismatch(profile),
      )
    }
  }
}
