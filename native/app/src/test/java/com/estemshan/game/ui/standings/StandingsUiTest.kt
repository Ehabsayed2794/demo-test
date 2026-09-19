package com.estemshan.game.ui.standings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingsUiTest {

  @Test
  fun sortsDescendingAndCrownsMax() {
    val s = buildStandings(mapOf("p1" to 12, "p2" to 48, "p3" to 30, "p4" to 5))
    assertEquals(listOf("p2", "p3", "p1", "p4"), s.rows.map { it.seat })
    assertTrue(s.rows[0].isWinner)
    assertTrue(s.rows.drop(1).none { it.isWinner })
  }

  @Test
  fun tiesShareTheCrown() {
    val s = buildStandings(mapOf("p1" to 44, "p2" to 44, "p3" to 10))
    assertTrue(s.rows[0].isWinner)
    assertTrue(s.rows[1].isWinner)
    assertTrue(!s.rows[2].isWinner)
  }

  @Test
  fun deltasAndSaaydaPassThrough() {
    val s = buildStandings(
      totals = mapOf("p1" to 48, "p2" to -22),
      lastDeltas = mapOf("p1" to 24, "p2" to -22),
      saaydaSeats = setOf("p2"),
    )
    assertEquals(24, s.rows[0].lastDelta)
    assertEquals(-22, s.rows[1].lastDelta)
    assertTrue(!s.rows[0].saaydaBadge)
    assertTrue(s.rows[1].saaydaBadge)
  }

  @Test
  fun emptyTotalsStayEmpty() {
    val s = buildStandings(emptyMap())
    assertTrue(s.rows.isEmpty())
    assertEquals("Final Standings", s.title)
  }
}
