package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S64 — seasonal leaderboard ordering (RD27): season isolation, rung-then-RP
 * ordering with King-by-RP, competition positions with shared ties, current
 * player marking, and fail-loud invalid rungs. Expected orders are
 * hand-derived literals, never recomputed by the suite.
 */
class LeaderboardTest {

  private fun entry(
    playerId: String,
    tier: String,
    division: String,
    rp: Int,
    seasonId: String = "S1",
  ): LeaderboardEntry = LeaderboardEntry(
    playerId = playerId,
    tier = tier,
    division = division,
    rp = rp,
    seasonId = seasonId,
  )

  @Test
  fun higherRungsBoardFirstAndKingsOrderByRpAlone() {
    // King has no divisions: RP above 3,000 is the only ordering key there
    // (RD27/RD3). A 3,200 King outranks a 3,050 King; both outrank Royal I.
    val rows = orderLeaderboard(
      listOf(
        entry("royal", "Royal", "I", 2800),
        entry("kingLow", "King", "", 3050),
        entry("kingHigh", "King", "", 3200),
        entry("gold", "Gold", "I", 900),
      ),
      seasonId = "S1",
      currentPlayerId = "nobody",
    )
    assertEquals(
      "rung desc, then RP desc within the rung",
      listOf("kingHigh", "kingLow", "royal", "gold"),
      rows.map { it.playerId },
    )
    assertEquals(listOf(1, 2, 3, 4), rows.map { it.position })
    assertTrue(rows.all { !it.isCurrentPlayer })
  }

  @Test
  fun tiesShareThePositionAndTheNextSkips() {
    // Competition ranking, RD17's rule applied to a board: 1, 2, 2, 4.
    // Same rung + same RP ties even across the boundary of "different"
    // players; a lower RP in the same rung still ranks below.
    val rows = orderLeaderboard(
      listOf(
        entry("a", "Gold", "II", 700),
        entry("b", "Gold", "II", 700),
        entry("c", "Gold", "II", 750),
        entry("d", "Silver", "I", 460),
      ),
      seasonId = "S1",
      currentPlayerId = "b",
    )
    assertEquals(listOf("c", "a", "b", "d"), rows.map { it.playerId })
    assertEquals("ties share, then skip", listOf(1, 2, 2, 4), rows.map { it.position })
    assertEquals(listOf(false, false, true, false), rows.map { it.isCurrentPlayer })
  }

  @Test
  fun theBoardShowsOneSeasonOnly() {
    val rows = orderLeaderboard(
      listOf(
        entry("old", "Gold", "I", 900, seasonId = "S1"),
        entry("new", "Silver", "I", 460, seasonId = "S2"),
      ),
      seasonId = "S2",
      currentPlayerId = "new",
    )
    assertEquals("season isolation: only S2 boards", listOf("new"), rows.map { it.playerId })
    assertEquals(1, rows.single().position)
    assertTrue(rows.single().isCurrentPlayer)
  }

  @Test
  fun aSeasonWithNoEntriesBoardsEmpty() {
    assertTrue(orderLeaderboard(emptyList(), "S9", "ghost").isEmpty())
    assertTrue(
      "a fresh season after a reset has no board yet",
      orderLeaderboard(listOf(entry("old", "Gold", "I", 900, seasonId = "S1")), "S2", "old").isEmpty(),
    )
  }

  @Test
  fun aBogusRungFailsInsteadOfSortingSomewhereVague() {
    val bad = listOf(entry("ghost", "Mithril", "I", 9999))
    assertThrows(IllegalArgumentException::class.java) {
      orderLeaderboard(bad, "S1", "ghost")
    }
  }

  @Test
  fun rowsCarryRankRpSeasonAndHighlight() {
    val rows = orderLeaderboard(
      listOf(entry("p1", "Silver", "I", 470)),
      seasonId = "S1",
      currentPlayerId = "p1",
    )
    val row = rows.single()
    assertEquals(1, row.position)
    assertEquals("Silver", row.rank.tier)
    assertEquals("I", row.rank.division)
    assertEquals(470, row.rp)
    assertEquals("S1", row.seasonId)
    assertTrue(row.isCurrentPlayer)
  }
}
