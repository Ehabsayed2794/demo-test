package com.estemshan.game.ui.matchmaking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S55 — the RD9 pool derivation, as a pure function of the tier the player is
 * seated at. These tests pin the rule the screen states in plain language:
 * your tier or the one below, never above, never wider — and the two shapes
 * the ladder's own ends force (Bronze has nothing beneath it, so its pool is
 * itself alone).
 *
 * They run over [RankTiers.all], the same seven tiers the screen draws, so a
 * drift between the ladder and its highlighted bracket would fail here
 * rather than render.
 */
class RankPoolTest {

  private val ladder = RankTiers.all

  // ==========================================================================
  //  The rule: your tier, and exactly one below
  // ==========================================================================

  @Test
  fun thePoolIsYourTierAndTheOneBelow() {
    val pool = RankPool(ladder, currentIndex = 2) // Gold

    assertEquals(setOf(1, 2), pool.poolIndices) // Silver and Gold
    assertEquals("Gold", pool.current.name)
    assertEquals("معلم", pool.current.arabicTitle)
  }

  @Test
  fun thePoolNeverReachesAboveYourTier() {
    // Every rung of the ladder, not just the sample one in the design.
    ladder.indices.forEach { index ->
      val pool = RankPool(ladder, index)

      assertEquals(
        "the pool must be $index and the rung beneath it",
        setOf((index - 1).coerceAtLeast(0), index),
        pool.poolIndices,
      )
      assertEquals(index, pool.poolIndices.max())
      assertTrue("the pool never widens past two rungs", pool.poolIndices.size <= 2)
    }
  }

  // ==========================================================================
  //  The ladder's two ends
  // ==========================================================================

  @Test
  fun theBottomTierIsItsOwnPool() {
    val pool = RankPool(ladder, currentIndex = 0) // Bronze — nothing below

    assertEquals(setOf(0), pool.poolIndices)
    assertEquals(1, pool.poolIndices.size)
    assertTrue(pool.isInPool(0))
  }

  @Test
  fun theTopTierKeepsTheOneBelow() {
    val pool = RankPool(ladder, currentIndex = ladder.lastIndex) // King

    assertEquals(setOf(ladder.lastIndex - 1, ladder.lastIndex), pool.poolIndices)
    assertEquals("King", pool.current.name)
  }

  // ==========================================================================
  //  A rank that has not resolved yet
  // ==========================================================================

  @Test
  fun anUnresolvedRankClampsOntoTheNearestEnd() {
    assertEquals(0, RankPool(ladder, currentIndex = -1).currentIndex)
    assertEquals(0, RankPool(ladder, currentIndex = Int.MIN_VALUE).currentIndex)
    assertEquals(ladder.lastIndex, RankPool(ladder, currentIndex = 99).currentIndex)
    assertEquals(ladder.lastIndex, RankPool(ladder, currentIndex = Int.MAX_VALUE).currentIndex)
  }

  @Test
  fun anEmptyLadderIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      RankPool(emptyList(), currentIndex = 0)
    }
  }

  // ==========================================================================
  //  The bracket
  // ==========================================================================

  @Test
  fun isInPoolAnswersForTheBracketAndNothingElse() {
    val pool = RankPool(ladder, currentIndex = 2) // Gold

    assertTrue(pool.isInPool(1)) // Silver
    assertTrue(pool.isInPool(2)) // Gold
    assertFalse(pool.isInPool(0)) // Bronze — below the pool
    assertFalse(pool.isInPool(3)) // Platinum — above the pool
    assertFalse(pool.isInPool(ladder.lastIndex)) // King
    assertFalse(pool.isInPool(-1))
  }
}
