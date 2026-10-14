package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S70 — party eligibility math (RD29/RD30): at most one tier apart on the
 * 7-tier order, symmetric, King↔Royal adjacent, divisions never matter,
 * denial token pinned, profiles resolving through the real-tier invariant.
 */
class PartyEligibilityTest {

  private fun rung(tier: String, division: String): LadderRank =
    rankLadder.first { it.tier == tier && it.division == division }

  private fun king(): LadderRank = rankLadder.first { it.isKing }

  @Test
  fun theTierOrderIsTheLaddersSevenDistinctTiers() {
    assertEquals(
      "compared by tier, never by 19-rank index (RD29)",
      listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King"),
      partyTierOrder,
    )
    assertEquals("the denial token the join gate must carry", "RANK_INCOMPATIBLE", PARTY_DENIAL_REASON)
  }

  @Test
  fun divisionGapsWithinATierAreAlwaysEligible() {
    // RD29: Gold I + Gold III — divisions never matter.
    assertTrue(arePartyTiersEligible(rung("Gold", "I"), rung("Gold", "III")))
    assertTrue(arePartyTiersEligible(rung("Bronze", "III"), rung("Bronze", "I")))
  }

  @Test
  fun oneTierApartIsEligibleInBothDirections() {
    // RD29: symmetric — Silver host + Gold friend and vice versa.
    assertTrue(arePartyTiersEligible(rung("Gold", "II"), rung("Silver", "I")))
    assertTrue(arePartyTiersEligible(rung("Silver", "I"), rung("Gold", "II")))
    assertTrue(arePartyTiersEligible(rung("Diamond", "III"), rung("Platinum", "I")))
    assertTrue(arePartyTiersEligible(rung("Platinum", "I"), rung("Diamond", "III")))
  }

  @Test
  fun twoOrMoreTiersApartIsIneligible() {
    // RD29: Gold + Bronze.
    assertFalse(arePartyTiersEligible(rung("Gold", "II"), rung("Bronze", "I")))
    assertFalse(arePartyTiersEligible(rung("Bronze", "I"), rung("Gold", "II")))
    assertFalse(arePartyTiersEligible(rung("Diamond", "I"), rung("Silver", "I")))
    assertFalse(arePartyTiersEligible(rung("Bronze", "III"), rung("King", "")))
  }

  @Test
  fun kingBordersRoyalOnly() {
    // RD29: one tier below King is Royal — and only Royal.
    assertTrue(arePartyTiersEligible(king(), rung("Royal", "I")))
    assertTrue(arePartyTiersEligible(rung("Royal", "III"), king()))
    assertFalse(arePartyTiersEligible(king(), rung("Diamond", "I")))
    assertFalse(arePartyTiersEligible(rung("Diamond", "I"), king()))
  }

  @Test
  fun profilesResolveThroughRealTiersIncludingUnplacedBronze() {
    // RD30: every account always resolves to a real tier — a fresh
    // unplaced profile IS Bronze III, so two fresh accounts may party.
    val freshA = RankedProfile(seasonId = "S1")
    val freshB = RankedProfile(seasonId = "S1")
    assertTrue(arePartyProfilesEligible(freshA, freshB))

    val gold = RankedProfile(tier = "Gold", division = "II", rp = 700, seasonId = "S1")
    val silver = RankedProfile(tier = "Silver", division = "I", rp = 450, seasonId = "S1")
    assertTrue("Gold host admits Silver friend", arePartyProfilesEligible(gold, silver))
    assertTrue("symmetric: Silver host admits Gold friend", arePartyProfilesEligible(silver, gold))

    val bronze = RankedProfile(tier = "Bronze", division = "III", rp = 0, seasonId = "S1")
    assertFalse("Gold host cannot take a Bronze friend", arePartyProfilesEligible(gold, bronze))
  }

  @Test
  fun foreignTiersFailInsteadOfComparing() {
    val foreign = LadderRank(tier = "Mithril", arabic = "x", lowerBound = 9999, division = "I")
    assertThrows(IllegalArgumentException::class.java) {
      arePartyTiersEligible(foreign, rung("Gold", "I"))
    }
    assertThrows(IllegalArgumentException::class.java) {
      arePartyTiersEligible(rung("Gold", "I"), foreign)
    }
  }
}
