package com.estemshan.engine

import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S53 — the scripted placement series: RD10's tier pairs and the three-match
 * shape, the 1/3–2/3–3/3 progress with no provisional rank anywhere, and the
 * six engine signals a finished match records for S54.
 */
class PlacementTest {

  // ==========================================================================
  //  The series RD10 closes
  // ==========================================================================

  @Test
  fun theSeriesIsExactlyThreeMatches() {
    assertEquals("RD10: three scripted matches", 3, placementMatches.size)
    assertEquals(PLACEMENT_MATCH_COUNT, placementMatches.size)
    placementMatches.forEachIndexed { i, match ->
      assertEquals("match $i knows its own index", i, match.index)
      assertEquals("a placement match seats three opponents against the player", 3, match.opponents.size)
    }
  }

  @Test
  fun theSeriesEscalatesThroughRD10sTierPairs() {
    // RD10: M1 Easy+Medium, M2 Medium+Hard, M3 Hard+Expert. Each table mixes
    // the pair — two of the lower tier, one of the upper — so a match is
    // always a mixed table and the difficulty climbs match over match.
    val m1 = placementMatch(0)
    assertEquals("M1's tiers are Easy + Medium", setOf(BotTier.EASY, BotTier.MEDIUM), m1.opponents.map { it.tier }.toSet())
    assertEquals("M1 leans Easy", 2, m1.opponents.count { it.tier == BotTier.EASY })

    val m2 = placementMatch(1)
    assertEquals("M2's tiers are Medium + Hard", setOf(BotTier.MEDIUM, BotTier.HARD), m2.opponents.map { it.tier }.toSet())
    assertEquals("M2 leans Medium", 2, m2.opponents.count { it.tier == BotTier.MEDIUM })

    val m3 = placementMatch(2)
    assertEquals("M3's tiers are Hard + Expert", setOf(BotTier.HARD, BotTier.EXPERT), m3.opponents.map { it.tier }.toSet())
    assertEquals("M3 leans Hard", 2, m3.opponents.count { it.tier == BotTier.HARD })
  }

  @Test
  fun matchesOneAndTwoAreMiniAndMatchThreeIsFull() {
    // Owner decision: the first two matches are a light MINI commitment and
    // the decisive 70%-weighted third is the canonical FULL format.
    assertEquals(GameType.MINI, placementMatch(0).gameType)
    assertEquals(GameType.MINI, placementMatch(1).gameType)
    assertEquals(GameType.FULL, placementMatch(2).gameType)
    placementMatches.forEach { match ->
      assertEquals("GM7: placement scores normally", ScoringMode.NORMAL, match.scoringMode)
    }
  }

  @Test
  fun thePersonalitiesAreAFixedSystemSpreadAcrossAllFour() {
    // RD10: the player picks neither difficulty nor personality. The system
    // spread is fixed, and all four profiles appear across the series so the
    // three matches do not feel identical.
    val personalities = placementMatches.flatMap { it.opponents }.map { it.personality }.toSet()
    assertEquals("all four personalities appear somewhere in the series",
      BotPersonality.entries.toSet(), personalities)
    // Every opponent is a real shipped profile — the director invents none.
    placementMatches.flatMap { it.opponents }.forEach {
      assertTrue("${it.tier} is a shipped tier", it.tier in BotTier.entries)
      assertTrue("${it.personality} is a shipped personality", it.personality in BotPersonality.entries)
    }
  }

  @Test
  fun placementMatchRefusesAnIndexOutsideTheSeries() {
    assertEquals(placementMatches.first(), placementMatch(0))
    assertEquals(placementMatches.last(), placementMatch(2))
    // RD10: once per account, never per season — there is no fourth match.
    assertThrows(IllegalArgumentException::class.java) { placementMatch(3) }
    assertThrows(IllegalArgumentException::class.java) { placementMatch(-1) }
  }

  @Test
  fun isLastPlacementMatchMarksOnlyTheThird() {
    assertFalse(isLastPlacementMatch(0))
    assertFalse(isLastPlacementMatch(1))
    assertTrue("the third match is the S54 handoff point", isLastPlacementMatch(2))
  }

  @Test
  fun placementProgressCountsOneTwoThreeAndNeverARank() {
    assertEquals(1, placementProgress(0))
    assertEquals(2, placementProgress(1))
    assertEquals(3, placementProgress(2))
    assertThrows(IllegalArgumentException::class.java) { placementProgress(3) }
  }

  // ==========================================================================
  //  Placement and outcome — RD17's tie rules
  // ==========================================================================

  private val decisive = mapOf("p1" to 300, "p2" to 200, "p3" to 100, "p4" to 0)

  @Test
  fun aDecisiveMatchRanksOneThroughFour() {
    assertEquals(1, placementRank(decisive, "p1"))
    assertEquals(2, placementRank(decisive, "p2"))
    assertEquals(3, placementRank(decisive, "p3"))
    assertEquals(4, placementRank(decisive, "p4"))
    assertEquals(MatchOutcome.KING, placementOutcome(decisive, "p1"))
    assertEquals(MatchOutcome.SECOND, placementOutcome(decisive, "p2"))
    assertEquals(MatchOutcome.THIRD, placementOutcome(decisive, "p3"))
    assertEquals(MatchOutcome.KOZ, placementOutcome(decisive, "p4"))
  }

  @Test
  fun aTieForFirstMakesEveryTiedSeatMatchKing() {
    val tieFirst = mapOf("p1" to 300, "p2" to 300, "p3" to 100, "p4" to 0)
    assertEquals("ties share the better rank", 1, placementRank(tieFirst, "p2"))
    assertEquals(MatchOutcome.KING, placementOutcome(tieFirst, "p2"))
    assertEquals(3, placementRank(tieFirst, "p3"))
    assertEquals(MatchOutcome.THIRD, placementOutcome(tieFirst, "p3"))
    assertEquals(MatchOutcome.KOZ, placementOutcome(tieFirst, "p4"))
  }

  @Test
  fun aTieForSecondMakesEveryTiedSeatSecond() {
    val tieSecond = mapOf("p1" to 300, "p2" to 200, "p3" to 200, "p4" to 0)
    assertEquals(2, placementRank(tieSecond, "p2"))
    assertEquals(MatchOutcome.SECOND, placementOutcome(tieSecond, "p3"))
    assertEquals(MatchOutcome.KING, placementOutcome(tieSecond, "p1"))
    assertEquals(MatchOutcome.KOZ, placementOutcome(tieSecond, "p4"))
  }

  @Test
  fun aTieForLastFabricatesNoKoz() {
    // RD17: a tie for last means there is no *unique* last place, so nobody
    // is KOZ — the tied seats take the placement they actually achieved.
    val tieLast = mapOf("p1" to 300, "p2" to 200, "p3" to 100, "p4" to 100)
    assertEquals(3, placementRank(tieLast, "p3"))
    assertEquals(MatchOutcome.THIRD, placementOutcome(tieLast, "p4"))
    assertFalse("no seat is KOZ on a tied last", tieLast.any { placementOutcome(tieLast, it.key) == MatchOutcome.KOZ })
  }

  @Test
  fun anAllTieMatchMakesEverySeatMatchKing() {
    val allTie = mapOf("p1" to 150, "p2" to 150, "p3" to 150, "p4" to 150)
    allTie.keys.forEach { seat ->
      assertEquals(1, placementRank(allTie, seat))
      assertEquals(MatchOutcome.KING, placementOutcome(allTie, seat))
    }
  }

  // ==========================================================================
  //  The six signals
  // ==========================================================================

  private val mixedRounds = listOf(
    PlacementRoundSignal(round = 1, estimate = 5, actual = 7),
    PlacementRoundSignal(round = 2, estimate = 4, actual = 4),
    PlacementRoundSignal(round = 3, estimate = 6, actual = 4),
  )

  @Test
  fun aRoundSignalReportsItsSignedAndAbsoluteMiss() {
    val under = mixedRounds[0]
    assertEquals("under-bid by two", -2, under.miss)
    assertEquals(2, under.absMiss)
    assertFalse("an under-bid is not exact", under.isExact)

    val exact = mixedRounds[1]
    assertEquals(0, exact.miss)
    assertEquals(0, exact.absMiss)
    assertTrue("estimate == actual is exact", exact.isExact)

    val over = mixedRounds[2]
    assertEquals("over-bid by two", 2, over.miss)
    assertEquals(2, over.absMiss)
    assertFalse(over.isExact)
  }

  @Test
  fun accuracyIsTheMeanAbsoluteMiss() {
    // |−2| + |0| + |+2| = 4, over three rounds.
    assertEquals(4.0 / 3.0, mixedRounds.toRecord().estimateAccuracy, 1e-9)
  }

  @Test
  fun biddingPerformanceIsTheExactHitRate() {
    // Only round 2 was exact: one of three.
    assertEquals(1.0 / 3.0, mixedRounds.toRecord().biddingPerformance, 1e-9)
  }

  @Test
  fun consistencyIsTheSpreadOfTheSignedMiss() {
    // Misses −2, 0, +2: mean 0, population variance 8/3.
    assertEquals(kotlin.math.sqrt(8.0 / 3.0), mixedRounds.toRecord().consistency, 1e-9)
  }

  @Test
  fun aPerfectRecordIsAccurateConsistentAndPerformant() {
    val perfect = listOf(
      PlacementRoundSignal(round = 1, estimate = 3, actual = 3),
      PlacementRoundSignal(round = 2, estimate = 5, actual = 5),
    ).toRecord()
    assertEquals(0.0, perfect.estimateAccuracy, 1e-9)
    assertEquals(1.0, perfect.biddingPerformance, 1e-9)
    assertEquals(0.0, perfect.consistency, 1e-9)
  }

  @Test
  fun anUnstartedMatchReportsNeutralSignalsNotFakePerfection() {
    // A match mid-flight has no rounds yet; the aggregates read neutral rather
    // than claiming a 1.0 hit rate the player has not earned.
    val empty = PlacementMatchRecord(matchIndex = 0, placement = 0, outcome = MatchOutcome.KING, score = 0, rounds = emptyList())
    assertEquals(0.0, empty.estimateAccuracy, 1e-9)
    assertEquals(0.0, empty.biddingPerformance, 1e-9)
    assertEquals("fewer than two rounds has no spread", 0.0, empty.consistency, 1e-9)
  }

  @Test
  fun recordPlacementMatchDerivesPlacementOutcomeAndScoreFromTotals() {
    val record = recordPlacementMatch(
      matchIndex = 1,
      totals = decisive,
      seat = "p2",
      rounds = mixedRounds,
    )
    assertEquals(1, record.matchIndex)
    assertEquals(2, record.placement)
    assertEquals(MatchOutcome.SECOND, record.outcome)
    assertEquals("the score is the seat's total", 200, record.score)
    assertEquals(mixedRounds, record.rounds)
  }

  private fun List<PlacementRoundSignal>.toRecord(): PlacementMatchRecord =
    PlacementMatchRecord(matchIndex = 0, placement = 1, outcome = MatchOutcome.KING, score = 0, rounds = this)
}
