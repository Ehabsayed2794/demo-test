package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S44 — the exactly-nine career statistics: the grid is nine and no tenth,
 * the two inclusion gates (mode == RANKED, not placement) are both real, and
 * percentages derive from completed Ranked matches (RD23 / RD10 / RD28).
 */
class RankedStatsTest {

  private val season = "2026Q4"
  private val settled = skipPlacement(season)
  private val ranked = MatchMode.RANKED

  @Test
  fun theGridIsExactlyNineInRD23Order() {
    assertEquals(9, RankedStatKey.entries.size)
    val grid = RankedStats().grid
    assertEquals(9, grid.size)
    // The grid's keys are the nine, in the spec's order — never a tenth.
    assertEquals(RankedStatKey.entries, grid.map { it.key })
  }

  @Test
  fun anEmptyStatBlockReadsAllZeros() {
    val empty = RankedStats()
    assertEquals(0, empty.gamesPlayed)
    assertEquals(0, empty.kingCount)
    assertEquals(0, empty.secondCount)
    assertEquals(0, empty.thirdCount)
    assertEquals(0, empty.kozCount)
    // Percentages are 0, never NaN or a divide-by-zero, before any match.
    assertEquals(0, empty.kingPct)
    assertEquals(0, empty.secondPct)
    assertEquals(0, empty.thirdPct)
    assertEquals(0, empty.kozPct)
    empty.grid.forEach { assertEquals(0, it.value) }
  }

  @Test
  fun eachOutcomeIncrementsItsOwnCounterAndGamesPlayed() {
    for (outcome in MatchOutcome.entries) {
      val stats = accumulateRankedStat(RankedStats(), outcome, settled, ranked)
      assertEquals(1, stats.gamesPlayed)
      assertEquals(if (outcome == MatchOutcome.KING) 1 else 0, stats.kingCount)
      assertEquals(if (outcome == MatchOutcome.SECOND) 1 else 0, stats.secondCount)
      assertEquals(if (outcome == MatchOutcome.THIRD) 1 else 0, stats.thirdCount)
      assertEquals(if (outcome == MatchOutcome.KOZ) 1 else 0, stats.kozCount)
    }
  }

  @Test
  fun accumulationKeepsTheFourOutcomesSummingToGamesPlayed() {
    var stats = RankedStats()
    val outcomes = listOf(
      MatchOutcome.KING, MatchOutcome.SECOND, MatchOutcome.THIRD, MatchOutcome.KOZ,
      MatchOutcome.KING, MatchOutcome.KING, MatchOutcome.SECOND,
    )
    for (outcome in outcomes) {
      stats = accumulateRankedStat(stats, outcome, settled, ranked)
      val counted = stats.kingCount + stats.secondCount + stats.thirdCount + stats.kozCount
      assertEquals(stats.gamesPlayed, counted)
    }
    assertEquals(outcomes.size, stats.gamesPlayed)
  }

  @Test
  fun tenMatchesProduceAllNineValuesAtWholePercents() {
    // 4 King / 3 2nd / 2 3rd / 1 Koz over 10 completed Ranked matches.
    var stats = RankedStats()
    val sequence = listOf(
      MatchOutcome.KING, MatchOutcome.SECOND, MatchOutcome.THIRD, MatchOutcome.KOZ,
      MatchOutcome.KING, MatchOutcome.SECOND, MatchOutcome.THIRD,
      MatchOutcome.KING, MatchOutcome.KING, MatchOutcome.SECOND,
    )
    for (outcome in sequence) {
      stats = accumulateRankedStat(stats, outcome, settled, ranked)
    }
    assertEquals(10, stats.gamesPlayed)
    assertEquals(4, stats.kingCount)
    assertEquals(3, stats.secondCount)
    assertEquals(2, stats.thirdCount)
    assertEquals(1, stats.kozCount)
    assertEquals(40, stats.kingPct)
    assertEquals(30, stats.secondPct)
    assertEquals(20, stats.thirdPct)
    assertEquals(10, stats.kozPct)

    val grid = stats.grid
    assertEquals(10, grid[0].value) // GAMES_PLAYED
    assertEquals(4, grid[1].value) // KING_COUNT
    assertEquals(40, grid[2].value) // KING_PCT
    assertEquals(3, grid[3].value) // SECOND_COUNT
    assertEquals(30, grid[4].value) // SECOND_PCT
    assertEquals(2, grid[5].value) // THIRD_COUNT
    assertEquals(20, grid[6].value) // THIRD_PCT
    assertEquals(1, grid[7].value) // KOZ_COUNT
    assertEquals(10, grid[8].value) // KOZ_PCT
  }

  @Test
  fun percentagesTruncateToAWholePercentFromGamesPlayed() {
    // 1 of 3 → 33 (33.33 truncated), 2 of 3 → 66 — whole percents, no
    // decimals, computed over completed matches only.
    var stats = RankedStats()
    stats = accumulateRankedStat(stats, MatchOutcome.KING, settled, ranked)
    stats = accumulateRankedStat(stats, MatchOutcome.SECOND, settled, ranked)
    stats = accumulateRankedStat(stats, MatchOutcome.THIRD, settled, ranked)
    assertEquals(3, stats.gamesPlayed)
    assertEquals(33, stats.kingPct)
    assertEquals(33, stats.secondPct)
    assertEquals(33, stats.thirdPct)
    stats = accumulateRankedStat(stats, MatchOutcome.KING, settled, ranked)
    // 2 of 4 = 50, so the truncation above was genuinely a truncation.
    assertEquals(50, stats.kingPct)
  }

  @Test
  fun roomAndUnrankedMatchesRecordNoRankedStatistics() {
    // RD28: only RANKED counts. Unranked is first-class rank-blind, not a
    // ROOM + a marker, so it is its own no-op branch here.
    for (mode in listOf(MatchMode.ROOM, MatchMode.UNRANKED)) {
      val before = RankedStats(gamesPlayed = 5, kingCount = 2)
      val after = accumulateRankedStat(before, MatchOutcome.KING, settled, mode)
      assertEquals("$mode must not record stats", before, after)
    }
  }

  @Test
  fun placementMatchesCountTowardNoStatisticEvenInRankedMode() {
    // RD10: placement matches count toward nothing — not Games Played, not
    // any percentage — even though the mode is RANKED.
    val placing = beginPlacement(season)
    assertTrue(isPlacement(placing))
    val before = RankedStats(gamesPlayed = 2, kozCount = 1)
    val after = accumulateRankedStat(before, MatchOutcome.KING, placing, ranked)
    assertEquals(before, after)
    assertNotEquals(settled, placing)
  }

  @Test
  fun settledProfilesRecordStatsWhetherPlacementWasSkippedOrCompleted() {
    // Public + Private Ranked both count; so does either way of becoming
    // settled — skipping (RD30) and finishing placement (RD10).
    val completed = RankedProfile(
      tier = "Gold", division = "III", rp = 600, seasonId = season,
      placementState = PlacementState.COMPLETE,
    )
    for (settledProfile in listOf(skipPlacement(season), completed)) {
      val stats = accumulateRankedStat(RankedStats(), MatchOutcome.KOZ, settledProfile, ranked)
      assertEquals(1, stats.gamesPlayed)
      assertEquals(1, stats.kozCount)
      // A Vote-Kicked removal reaches the accumulator as a Koz outcome (D10).
    }
  }

  @Test
  fun seasonsDoNotResetCareerStatistics() {
    // RD24: the 9 values are lifetime — a new seasonId changes the profile's
    // season, not the accumulator's input gate.
    val stats = accumulateRankedStat(RankedStats(), MatchOutcome.KING, settled, ranked)
    val nextSeason = settled.copy(seasonId = "2027Q1")
    val carried = accumulateRankedStat(stats, MatchOutcome.SECOND, nextSeason, ranked)
    assertEquals(2, carried.gamesPlayed)
    assertEquals(1, carried.kingCount)
    assertEquals(1, carried.secondCount)
  }
}
