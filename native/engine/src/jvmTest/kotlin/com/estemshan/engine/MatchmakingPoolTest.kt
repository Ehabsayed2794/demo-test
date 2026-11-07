package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S56 — the RD9 eligible-pool policy (engine slice): own tier or exactly
 * one tier below, never opt-up, never two or more below; divisions never
 * enter; GameType pairs only on exact equality (GM5); a foreign tier
 * fails loudly; the pool is deterministic, duplicate-free, own tier
 * first.
 */
class MatchmakingPoolTest {

  private fun rung(tier: String, division: String): LadderRank =
    rankLadder.first { it.tier == tier && it.division == division }

  /** One representative rung per tier (its lowest division; King's single rung). */
  private fun tierRung(tier: String): LadderRank = rankLadder.first { it.tier == tier }

  private fun king(): LadderRank = rankLadder.first { it.isKing }

  /** RD9's pool per tier, hand-written: own tier first, then one below. */
  private val expectedPoolByTier = listOf(
    "Bronze" to listOf("Bronze"),
    "Silver" to listOf("Silver", "Bronze"),
    "Gold" to listOf("Gold", "Silver"),
    "Platinum" to listOf("Platinum", "Gold"),
    "Diamond" to listOf("Diamond", "Platinum"),
    "Royal" to listOf("Royal", "Diamond"),
    "King" to listOf("King", "Royal"),
  )

  @Test
  fun thePoolOrderIsTheLaddersSevenDistinctTiers() {
    assertEquals(
      "derived from the single rankLadder table, never a second tier table (RD9)",
      listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King"),
      poolTierOrder,
    )
  }

  @Test
  fun theFull19RungTableCarriesTheHandWrittenPoolLists() {
    // Every one of the 19 rungs against its tier's hand-written literal
    // pool — the literal 7-tier pools applied across all 19 rungs.
    var covered = 0
    for ((tier, expectedPool) in expectedPoolByTier) {
      for (searcher in rankLadder.filter { it.tier == tier }) {
        assertEquals(
          "searcher ${searcher.label()}",
          expectedPool,
          eligiblePoolTiers(searcher),
        )
        covered++
      }
    }
    assertEquals("the table must cover all 19 rungs", 19, covered)
  }

  @Test
  fun divisionsNeverMatter() {
    // Gold I + Gold III — the same tier, so always eligible, either way.
    assertTrue(isPoolEligible(rung("Gold", "I"), rung("Gold", "III")))
    assertTrue(isPoolEligible(rung("Gold", "III"), rung("Gold", "I")))
    // Silver I + Gold III: Gold's lowest division still serves Silver's
    // highest; the reverse is an opt-up, unaffected by divisions.
    assertTrue(isPoolEligible(rung("Gold", "III"), rung("Silver", "I")))
    assertFalse(isPoolEligible(rung("Silver", "I"), rung("Gold", "III")))
  }

  @Test
  fun noOptUp() {
    // RD9: a lower-tier player cannot opt up — Silver cannot request
    // Gold, Bronze cannot request Silver...
    assertFalse(isPoolEligible(rung("Silver", "II"), rung("Gold", "I")))
    assertFalse(isPoolEligible(rung("Bronze", "III"), rung("Silver", "I")))
    // ...and the same pairs flipped are the intended one-below pools.
    assertTrue(isPoolEligible(rung("Gold", "I"), rung("Silver", "II")))
    assertTrue(isPoolEligible(rung("Silver", "I"), rung("Bronze", "III")))
  }

  @Test
  fun twoOrMoreBelowIsNeverEligible() {
    // RD9: "never two+" — Gold + Bronze.
    assertFalse(isPoolEligible(rung("Gold", "I"), rung("Bronze", "III")))
    assertFalse(isPoolEligible(rung("Platinum", "II"), rung("Silver", "I")))
    assertFalse(isPoolEligible(rung("Diamond", "I"), rung("Bronze", "II")))
    assertFalse(isPoolEligible(king(), rung("Gold", "I")))
  }

  @Test
  fun kingPoolsRoyalOnlyBelowIt() {
    // King's one tier below is Royal, per the derived order.
    assertTrue("King + Royal", isPoolEligible(king(), rung("Royal", "III")))
    assertFalse("King + Diamond", isPoolEligible(king(), rung("Diamond", "I")))
    // And a Royal searcher cannot opt up into King either.
    assertFalse(isPoolEligible(rung("Royal", "III"), king()))
  }

  @Test
  fun everyTierPairIsOwnTierOrExactlyOneBelow() {
    // Exhaustive over the 7-tier order: eligible iff the candidate is the
    // searcher's own tier or the one immediately below it.
    val order = poolTierOrder
    for ((searcherIndex, searcherTier) in order.withIndex()) {
      for ((candidateIndex, candidateTier) in order.withIndex()) {
        val expected: Boolean =
          candidateIndex == searcherIndex || candidateIndex == searcherIndex - 1
        assertEquals(
          "searcher $searcherTier, candidate $candidateTier",
          expected,
          isPoolEligible(tierRung(searcherTier), tierRung(candidateTier)),
        )
      }
    }
  }

  @Test
  fun poolEligibilityIsMembershipInTheDerivedEligiblePool() {
    // Exhaustive over all 19 rungs on both sides: isPoolEligible is
    // exactly "candidate tier is in eligiblePoolTiers(searcher)".
    for (searcher in rankLadder) {
      val pool = eligiblePoolTiers(searcher)
      for (candidate in rankLadder) {
        assertEquals(
          "searcher ${searcher.label()}, candidate ${candidate.label()}",
          candidate.tier in pool,
          isPoolEligible(searcher, candidate),
        )
      }
    }
  }

  @Test
  fun gameTypeNeverWidensOrNarrowsThePool() {
    // GM5: the pool policy carries no GameType input — one pool per tier
    // for FULL and MINI searches alike; the only GameType rule in Ranked
    // search is exact-equality pairing.
    assertTrue(isSearchCompatible(GameType.FULL, GameType.FULL))
    assertTrue(isSearchCompatible(GameType.MINI, GameType.MINI))
    assertFalse(isSearchCompatible(GameType.FULL, GameType.MINI))
    assertFalse(isSearchCompatible(GameType.MINI, GameType.FULL))
    assertEquals(
      "pin the enum so a third GameType must revisit GM5 pairing",
      2,
      GameType.values().size,
    )
    // The identical per-tier pool (Gold serves [Gold, Silver]) is what a
    // Mini searcher sees — no MINI variant exists to narrow or widen it.
    assertEquals(listOf("Gold", "Silver"), eligiblePoolTiers(rung("Gold", "III")))
  }

  @Test
  fun foreignTiersFailInsteadOfDeciding() {
    val foreign = LadderRank(tier = "Mithril", arabic = "x", lowerBound = 9999, division = "I")
    assertThrows(IllegalArgumentException::class.java) { eligiblePoolTiers(foreign) }
    assertThrows(IllegalArgumentException::class.java) {
      isPoolEligible(rung("Gold", "I"), foreign)
    }
    assertThrows(IllegalArgumentException::class.java) {
      isPoolEligible(foreign, rung("Gold", "I"))
    }
  }

  @Test
  fun poolsAreDeterministicWithoutDuplicates() {
    for (searcher in rankLadder) {
      val pool = eligiblePoolTiers(searcher)
      assertEquals(
        "re-derivation of ${searcher.label()}'s pool is stable",
        pool,
        eligiblePoolTiers(searcher),
      )
      assertEquals(
        "no duplicate tiers in ${searcher.label()}'s pool",
        pool.distinct(),
        pool,
      )
      assertEquals(
        "the searcher's own tier comes first",
        searcher.tier,
        pool.first(),
      )
    }
  }

  @Test
  fun bronzePoolsForItselfOnly() {
    // The bottom tier has nothing below: the pool is exactly [Bronze],
    // and Silver is one above — never eligible as a candidate.
    assertEquals(listOf("Bronze"), eligiblePoolTiers(rung("Bronze", "II")))
    assertTrue(isPoolEligible(rung("Bronze", "I"), rung("Bronze", "III")))
    assertFalse(isPoolEligible(rung("Bronze", "I"), rung("Silver", "III")))
  }
}
