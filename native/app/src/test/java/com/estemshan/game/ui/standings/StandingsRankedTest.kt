package com.estemshan.game.ui.standings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S57 — VM/state half only. The reveal animation (Animatable /
 * LaunchedEffect in FinalStandingsScreen) stays off the unit-test clock
 * per the S19 Heartbeat seam decision; these tests drive
 * [buildRankedResult], [formatRpDelta], and the pure [RankedReveal]
 * helpers instead. No Compose, no delay, no engine timing.
 */
class StandingsRankedTest {

  private val season = RankedSeasonUiState(id = "S12", start = "2026-07-01", end = "2026-10-01")

  @Test
  fun bindingMirrorsWithEffectiveDelta() {
    val r = buildRankedResult(600, 30, season = season, userSeat = "p1")
    assertEquals(600, r.previousRP)
    assertEquals(30, r.delta)
    assertEquals(630, r.rp)
    assertEquals("Gold III · معلم", r.previousRankLabel)
    assertEquals("Gold III · معلم", r.nextRankLabel)
    assertEquals(0, r.movement)
    assertFalse(r.ceiling)
    assertNull(r.reason)
    assertEquals(season, r.season)
    assertEquals("p1", r.userSeat)
  }

  @Test
  fun promotionDemotionFloorAndCeiling() {
    val up = buildRankedResult(430, 30, season = season)
    assertEquals("Silver II · لاعب", up.previousRankLabel)
    assertEquals("Silver I · لاعب", up.nextRankLabel)
    assertEquals(1, up.movement)

    val down = buildRankedResult(710, -30, season = season)
    assertEquals("Gold II · معلم", down.previousRankLabel)
    assertEquals("Gold III · معلم", down.nextRankLabel)
    assertEquals(-1, down.movement)

    // RD3: a loss at the ladder floor shows 0 and no demotion.
    val floor = buildRankedResult(0, -30, season = season)
    assertEquals(0, floor.rp)
    assertEquals(0, floor.delta)
    assertEquals(0, floor.movement)

    // RD27: King ceiling accrues, stays King, settles.
    val king = buildRankedResult(3050, 30, season = season)
    assertEquals("King · ملك", king.nextRankLabel)
    assertEquals(0, king.movement)
    assertTrue(king.ceiling)
  }

  @Test
  fun reasonPassesThroughForMixedTier() {
    val r = buildRankedResult(
      previousRP = 740,
      delta = -45,
      reason = "Loss increased, you were the higher tier",
      season = season,
    )
    assertEquals(-45, r.delta)
    assertEquals("Loss increased, you were the higher tier", r.reason)
    assertEquals(-1, r.movement)
  }

  @Test
  fun deltaFormatsWithMinusSign() {
    assertEquals("+30", formatRpDelta(30))
    // U+2212 MINUS SIGN, exactly as standings-render.js tallies.
    assertEquals("−30", formatRpDelta(-30))
    assertEquals("0", formatRpDelta(0))
  }

  @Test
  fun revealTimingsMatchTheDesign() {
    assertEquals(90L, RankedReveal.RowStaggerMillis)
    assertEquals(950, RankedReveal.ScoreTallyMillis)
    assertEquals(350L, RankedReveal.RpStartDelayMillis)
    assertEquals(550, RankedReveal.RpTallyMillis)
    assertEquals(350, RankedReveal.RankTransitionMillis)
    // Rows stagger from the bottom: (lastIndex − i) × 90 ms.
    assertEquals(270L, RankedReveal.rowDelayMillis(0, 3))
    assertEquals(0L, RankedReveal.rowDelayMillis(3, 3))
  }

  @Test
  fun easeOutCubicAndTallyFrames() {
    assertEquals(0f, RankedReveal.easeOutCubic(0f), 1e-6f)
    assertEquals(1f, RankedReveal.easeOutCubic(1f), 1e-6f)
    assertEquals(0.875f, RankedReveal.easeOutCubic(0.5f), 1e-6f)
    // Tallies start at 0 and land exactly.
    assertEquals(0, tallyScoreFrame(186, 0f))
    assertEquals(186, tallyScoreFrame(186, 1f))
    assertEquals(0, tallyDeltaFrame(30, 0f))
    assertEquals(30, tallyDeltaFrame(30, 1f))
    assertEquals(-30, tallyDeltaFrame(-30, 1f))
    // JS Math.round parity (half up toward +∞).
    assertEquals(1, jsRoundFrame(0.5f))
    assertEquals(-7, jsRoundFrame(-7.5f))
  }
}
