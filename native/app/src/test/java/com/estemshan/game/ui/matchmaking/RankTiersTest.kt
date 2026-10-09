package com.estemshan.game.ui.matchmaking

import com.estemshan.engine.rankLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S55 — the screen's seven-tier ladder, derived from the engine's 19-rung
 * [rankLadder]. The derivation crosses a module boundary, so this pins its
 * shape: reorder or reshape the engine's table and the screen's ladder must
 * still come out as seven ascending tiers with their RD1 Arabic titles, or
 * this fails rather than rendering a bracket over the wrong rungs.
 */
class RankTiersTest {

  @Test
  fun theEngineNineteenRungsCollapseToSevenTiers() {
    assertEquals(19, rankLadder.size)
    assertEquals(7, RankTiers.all.size)
  }

  @Test
  fun theTiersAscendBronzeToKing() {
    assertEquals(
      listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King"),
      RankTiers.all.map { it.name },
    )
  }

  @Test
  fun eachTierIsSeatedOnce() {
    val names = RankTiers.all.map { it.name }
    assertEquals(names.size, names.distinct().size)
  }

  @Test
  fun eachTierCarriesItsRd1ArabicTitle() {
    RankTiers.all.forEach { tier ->
      assertTrue("'${tier.name}' has no Arabic title", tier.arabicTitle.isNotBlank())
    }
    val byName = RankTiers.all.associateBy { it.name }
    assertEquals("مبتدئ", byName.getValue("Bronze").arabicTitle)
    assertEquals("معلم", byName.getValue("Gold").arabicTitle)
    assertEquals("ملك", byName.getValue("King").arabicTitle)
  }
}
