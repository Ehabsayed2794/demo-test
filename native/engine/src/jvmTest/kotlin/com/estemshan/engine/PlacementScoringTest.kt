package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * S54 — placement scoring (RD10, FINAL): the 10/20/70 series composite,
 * the Platinum ceiling, the division lower-bound start, and the invalid /
 * incomplete input the scorer must refuse.
 *
 * Where the spec closes a number (weights, ceiling, floor, start rule),
 * the test pins it exactly. Where S54 owns engineering judgment, the
 * tests pin it literally too: the match composite against a hand-derived
 * exact value covering all six signals, the series→RP curve at five
 * literal points plus its OPEN-3 tie behavior, and every signal's
 * contribution via isolated differs-tests — so a dropped signal or a
 * reshaped curve fails loudly instead of passing silently.
 */
class PlacementScoringTest {

  private fun record(
    matchIndex: Int,
    placement: Int = 1,
    outcome: MatchOutcome = MatchOutcome.KING,
    score: Int = 300,
    roundMisses: List<Int> = List(10) { 0 },
  ): PlacementMatchRecord = PlacementMatchRecord(
    matchIndex = matchIndex,
    placement = placement,
    outcome = outcome,
    score = score,
    rounds = roundMisses.mapIndexed { i, m ->
      PlacementRoundSignal(round = i + 1, estimate = 5 + m, actual = 5)
    },
  )

  private fun perfect(index: Int): PlacementMatchRecord = record(index, score = 400)

  private fun terrible(index: Int): PlacementMatchRecord = record(
    matchIndex = index,
    placement = 4,
    outcome = MatchOutcome.KOZ,
    score = -300,
    // Sloppy AND unsteady: alternating over/under bids, so neither the
    // accuracy nor the consistency signal rescues the composite.
    roundMisses = List(10) { if (it % 2 == 0) 4 else -4 },
  )

  private fun catastrophic(index: Int): PlacementMatchRecord = record(
    matchIndex = index,
    placement = 4,
    outcome = MatchOutcome.KOZ,
    score = -2000,
    roundMisses = List(10) { if (it % 2 == 0) 13 else -13 },
  )

  // ==========================================================================
  //  The closed 10/20/70 weights
  // ==========================================================================

  @Test
  fun theMatchWeightsAreTheClosedRD10Split() {
    assertEquals("RD10 FINAL: M1 10%", 0.10, PLACEMENT_M1_WEIGHT, 1e-9)
    assertEquals("RD10 FINAL: M2 20%", 0.20, PLACEMENT_M2_WEIGHT, 1e-9)
    assertEquals("RD10 FINAL: M3 70%", 0.70, PLACEMENT_M3_WEIGHT, 1e-9)
    assertEquals(
      "the weights cover the whole series",
      1.0,
      placementMatchWeights.sum(),
      1e-9,
    )
  }

  // ==========================================================================
  //  Signal components
  // ==========================================================================

  @Test
  fun outcomeComponentSpansZeroToOneEvenly() {
    assertEquals(1.0, placementOutcomeComponent(MatchOutcome.KING), 1e-9)
    assertEquals(2.0 / 3.0, placementOutcomeComponent(MatchOutcome.SECOND), 1e-9)
    assertEquals(1.0 / 3.0, placementOutcomeComponent(MatchOutcome.THIRD), 1e-9)
    assertEquals(0.0, placementOutcomeComponent(MatchOutcome.KOZ), 1e-9)
  }

  @Test
  fun rankComponentMapsFirstToOneAndFourthToZero() {
    assertEquals(1.0, placementRankComponent(1), 1e-9)
    assertEquals(2.0 / 3.0, placementRankComponent(2), 1e-9)
    assertEquals(1.0 / 3.0, placementRankComponent(3), 1e-9)
    assertEquals(0.0, placementRankComponent(4), 1e-9)
  }

  @Test
  fun rankComponentRefusesARankOutsideTheFourSeatTable() {
    assertThrows(IllegalArgumentException::class.java) { placementRankComponent(0) }
    assertThrows(IllegalArgumentException::class.java) { placementRankComponent(5) }
  }

  @Test
  fun scoreComponentIsNeutralAtZeroSymmetricAndSaturating() {
    assertEquals(
      "a scoreless match reads neutral",
      0.5,
      placementScoreComponent(0, PLACEMENT_FULL_SCORE_PAR),
      1e-9,
    )
    assertEquals(
      "par reads fully dominant",
      1.0,
      placementScoreComponent(200, PLACEMENT_MINI_SCORE_PAR),
      1e-9,
    )
    assertEquals(
      "minus par reads the symmetric floor",
      0.0,
      placementScoreComponent(-200, PLACEMENT_MINI_SCORE_PAR),
      1e-9,
    )
    val halfUp = placementScoreComponent(175, PLACEMENT_FULL_SCORE_PAR)
    val halfDown = placementScoreComponent(-175, PLACEMENT_FULL_SCORE_PAR)
    assertEquals(
      "the linear region is symmetric around neutral",
      0.5 - halfDown,
      halfUp - 0.5,
      1e-9,
    )
    assertTrue(
      "a bigger total always reads higher inside the bounds",
      placementScoreComponent(300, PLACEMENT_FULL_SCORE_PAR) >
        placementScoreComponent(100, PLACEMENT_FULL_SCORE_PAR),
    )
    assertEquals(
      "a blowout saturates at dominant, never above",
      1.0,
      placementScoreComponent(5000, PLACEMENT_FULL_SCORE_PAR),
      1e-9,
    )
    assertEquals(
      "a collapse saturates at the floor, never below",
      0.0,
      placementScoreComponent(-5000, PLACEMENT_FULL_SCORE_PAR),
      1e-9,
    )
  }

  @Test
  fun scoreComponentRefusesANonPositivePar() {
    assertThrows(IllegalArgumentException::class.java) { placementScoreComponent(10, 0.0) }
    assertThrows(IllegalArgumentException::class.java) { placementScoreComponent(10, -5.0) }
  }

  @Test
  fun accuracyAndConsistencyComponentsPeakAtZeroAndDecay() {
    assertEquals(1.0, placementAccuracyComponent(0.0), 1e-9)
    assertEquals(1.0, placementConsistencyComponent(0.0), 1e-9)
    assertTrue(
      "a 2-miss average reads below a 1-miss average",
      placementAccuracyComponent(2.0) < placementAccuracyComponent(1.0),
    )
    assertTrue(
      "a wider spread reads below a tighter one",
      placementConsistencyComponent(3.0) < placementConsistencyComponent(1.0),
    )
    assertThrows(IllegalArgumentException::class.java) { placementAccuracyComponent(-1.0) }
    assertThrows(IllegalArgumentException::class.java) { placementConsistencyComponent(-1.0) }
  }

  // ==========================================================================
  //  The ceiling derives from the ladder, not a literal
  // ==========================================================================

  @Test
  fun theCeilingIsPlatinumIFloorFromTheSingleLadderTable() {
    val platinumI = rankLadder.first { it.tier == "Platinum" && it.division == "I" }
    assertEquals(
      "S54 caps at the ladder's own Platinum I rung",
      platinumI.lowerBound,
      placementRpCeiling,
    )
    assertEquals("RD26 closes Platinum I at 1400", 1400, placementRpCeiling)
    assertTrue(
      "Diamond III sits above the ceiling, so placement can never reach it",
      rankLadder.first { it.tier == "Diamond" }.lowerBound > placementRpCeiling,
    )
  }

  // ==========================================================================
  //  The composite→RP mapping, pinned literally (F1)
  // ==========================================================================

  @Test
  fun theSeriesToRpMappingIsLinearOntoTheCeiling() {
    // S54 judgment, not RD10 text: the 0..1 composite maps linearly onto
    // RP 0..Platinum-I-floor. Asserted literally at five points, so any
    // reshaping (e.g. quadratic, which would read 350 at 0.5 instead of
    // 700) fails loudly instead of silently moving every player 1–2 tiers.
    assertEquals(0, placementRpForSeriesScore(0.0))
    assertEquals(350, placementRpForSeriesScore(0.25))
    assertEquals(700, placementRpForSeriesScore(0.5))
    assertEquals(1050, placementRpForSeriesScore(0.75))
    assertEquals(1400, placementRpForSeriesScore(1.0))
  }

  @Test
  fun theMappingClampsOutsideZeroToOne() {
    assertEquals(0, placementRpForSeriesScore(-0.2))
    assertEquals(1400, placementRpForSeriesScore(1.5))
  }

  @Test
  fun theMappingRoundsTiesAwayFromZeroPerOPEN3() {
    // 0.5/1400 of the ceiling is exactly 0.5 RP: away-from-zero → 1.
    // kotlin.math.round returns 0 here (ties-to-even) — this test fails
    // against it and passes against the shared roundDelta (F3).
    assertEquals(1, placementRpForSeriesScore(0.5 / 1400.0))
    assertEquals(3, placementRpForSeriesScore(2.5 / 1400.0))
  }

  // ==========================================================================
  //  Every RD10 signal contributes (F2)
  // ==========================================================================

  @Test
  fun everySignalMovesTheMatchComposite() {
    val base = record(
      1,
      placement = 2,
      outcome = MatchOutcome.SECOND,
      score = 100,
      roundMisses = listOf(1, -1, 1, -1),
    )
    val baseScore = scorePlacementMatch(base)
    assertTrue(
      "dropping the placement signal must change the composite",
      baseScore != scorePlacementMatch(base.copy(placement = 1)),
    )
    assertTrue(
      "dropping the result signal must change the composite",
      baseScore != scorePlacementMatch(base.copy(outcome = MatchOutcome.KING)),
    )
    assertTrue(
      "dropping the score signal must change the composite",
      baseScore != scorePlacementMatch(base.copy(score = 150)),
    )
    // Accuracy varies while performance (no exact hits either side) and
    // consistency (constant miss either side) stay fixed.
    val steady1 = record(1, roundMisses = listOf(1, 1, 1, 1))
    val steady3 = record(1, roundMisses = listOf(3, 3, 3, 3))
    assertTrue(
      "dropping the accuracy signal must change the composite",
      scorePlacementMatch(steady1) != scorePlacementMatch(steady3),
    )
    // Consistency varies while accuracy (same mean abs miss) and
    // performance (no exact hits either side) stay fixed.
    val tight = record(1, roundMisses = listOf(2, -2, 2, -2))
    val loose = record(1, roundMisses = listOf(3, -1, -3, 1))
    assertTrue(
      "dropping the consistency signal must change the composite",
      scorePlacementMatch(tight) != scorePlacementMatch(loose),
    )
  }

  @Test
  fun theMatchCompositeIsTheMeanOfAllSixSignals() {
    // Hand-derived independently of the implementation: rank 2/3, result
    // 2/3, MINI score 0.75, accuracy 0.5, performance 0.5, consistency
    // 1/(1+√2) = √2−1. Dropping or reweighting any signal — performance
    // included — moves this value by more than 0.05.
    val r = record(
      1,
      placement = 2,
      outcome = MatchOutcome.SECOND,
      score = 100,
      roundMisses = listOf(0, 0, 2, -2),
    )
    val expected = (2.0 / 3.0 + 2.0 / 3.0 + 0.75 + 0.5 + 0.5 + (sqrt(2.0) - 1.0)) / 6.0
    assertEquals(expected, scorePlacementMatch(r), 1e-9)
  }

  // ==========================================================================
  //  Full series: extremes
  // ==========================================================================

  @Test
  fun aPerfectSeriesPlacesPlatinumIAtItsLowerBound() {
    val result = scorePlacementSeries(listOf(perfect(0), perfect(1), perfect(2)))
    assertEquals("three perfect matches composite to 1.0", 1.0, result.seriesScore, 1e-9)
    assertEquals("Platinum", result.rank.tier)
    assertEquals("the top allowed division", "I", result.rank.division)
    assertEquals("RD10: start at the division's lower bound", 1400, result.startRp)
    assertEquals(result.rank.lowerBound, result.startRp)
  }

  @Test
  fun aCatastrophicSeriesStartsAtBronzeIIILowerBound() {
    val result = scorePlacementSeries(listOf(catastrophic(0), catastrophic(1), catastrophic(2)))
    assertEquals("Bronze", result.rank.tier)
    assertEquals("III", result.rank.division)
    assertEquals("RD10: the floor is Bronze III at 0", 0, result.startRp)
  }

  @Test
  fun aWinlessButOrdinarySeriesStaysInLowBronze() {
    val result = scorePlacementSeries(listOf(terrible(0), terrible(1), terrible(2)))
    assertEquals("three Koz finishes never leave Bronze", "Bronze", result.rank.tier)
    assertEquals(result.rank.lowerBound, result.startRp)
    assertTrue(
      "a winless series starts below Silver, was ${result.startRp}",
      result.startRp < rankLadder.first { it.tier == "Silver" }.lowerBound,
    )
  }

  @Test
  fun aPerfectSeriesNeverBreachesThePlatinumCeiling() {
    val result = scorePlacementSeries(listOf(perfect(0), perfect(1), perfect(2)))
    assertTrue(
      "placement never yields Diamond, Royal, or King: was ${result.rank.tier}",
      result.rank.tier in setOf("Bronze", "Silver", "Gold", "Platinum"),
    )
    assertTrue(
      "start RP never reaches Diamond III",
      result.startRp < rankLadder.first { it.tier == "Diamond" }.lowerBound,
    )
  }

  // ==========================================================================
  //  Full series: the 70% match dominates
  // ==========================================================================

  @Test
  fun theDecisiveThirdMatchCarriesSeventyPercent() {
    // A great M3 drags two terrible early matches up; a terrible M3 drags
    // two great ones down. That asymmetry IS the 10/20/70 split.
    val strongFinish = scorePlacementSeries(listOf(terrible(0), terrible(1), perfect(2)))
    val weakFinish = scorePlacementSeries(listOf(perfect(0), perfect(1), terrible(2)))
    assertTrue(
      "M3 at 70% outweighs M1+M2 at 30%: strong finish ${strongFinish.seriesScore} vs weak ${weakFinish.seriesScore}",
      strongFinish.seriesScore > weakFinish.seriesScore,
    )
    val m3 = perfect(2)
    val solo = scorePlacementMatch(m3)
    val expected = 0.10 * scorePlacementMatch(terrible(0)) +
      0.20 * scorePlacementMatch(terrible(1)) + 0.70 * solo
    assertEquals(
      "the series score is exactly the weighted mean",
      expected,
      strongFinish.seriesScore,
      1e-9,
    )
  }

  @Test
  fun identicalMatchesScoreTheSameWhateverTheWeights() {
    // A scoreless match reads 0.5 under either format par, so the same
    // record composites identically in every slot and the weights drop out.
    val one = record(0, placement = 2, outcome = MatchOutcome.SECOND, score = 0)
    val flat = scorePlacementSeries(
      listOf(one, one.copy(matchIndex = 1), one.copy(matchIndex = 2)),
    )
    assertEquals(
      "uniform input makes the weights irrelevant",
      scorePlacementMatch(one),
      flat.seriesScore,
      1e-9,
    )
    assertEquals(3, flat.matchScores.size)
  }

  @Test
  fun eachSlotAppliesItsOwnFormatPar() {
    // Same record, different slots: slots 0/1 are MINI (par 200), slot 2 is
    // FULL (par 350). A positive score reads higher under the lower par.
    val mini = scorePlacementMatch(record(0, score = 200))
    val full = scorePlacementMatch(record(2, score = 200))
    assertTrue(
      "MINI par is steeper for the same positive score: $mini vs $full",
      mini > full,
    )
  }

  // ==========================================================================
  //  Start rule: always the rung floor
  // ==========================================================================

  @Test
  fun everySeriesStartsItsRungAtTheLowerBound() {
    val cases = listOf(
      listOf(perfect(0), perfect(1), perfect(2)),
      listOf(terrible(0), terrible(1), terrible(2)),
      listOf(perfect(0), terrible(1), perfect(2)),
      listOf(
        record(0, placement = 2, outcome = MatchOutcome.SECOND, score = 120),
        record(1, placement = 3, outcome = MatchOutcome.THIRD, score = -40),
        record(2, placement = 1, outcome = MatchOutcome.KING, score = 260),
      ),
    )
    cases.forEach { series ->
      val result = scorePlacementSeries(series)
      assertEquals(
        "RD10: ${result.rank.tier} ${result.rank.division} starts at its floor",
        result.rank.lowerBound,
        result.startRp,
      )
      assertEquals(
        "the rank resolves through the single ladder",
        rankAt(result.startRp),
        result.rank,
      )
    }
  }

  @Test
  fun noSeriesCompositionEscapesTheCeiling() {
    // Sweep placements × outcomes × scores: nothing may land above Platinum I.
    val placements = listOf(1, 2, 3, 4)
    val outcomes = MatchOutcome.entries.toList()
    val scores = listOf(-400, 0, 200, 600)
    for (p in placements) for (o in outcomes) for (s in scores) {
      val result = scorePlacementSeries(
        listOf(
          record(0, placement = p, outcome = o, score = s),
          record(1, placement = p, outcome = o, score = s),
          record(2, placement = p, outcome = o, score = s),
        ),
      )
      assertTrue(
        "p=$p o=$o s=$s placed ${result.rank.tier} ${result.rank.division} — above Platinum",
        rankLadder.indexOf(result.rank) <= rankLadder.indexOf(
          rankLadder.first { it.tier == "Platinum" && it.division == "I" },
        ),
      )
      assertTrue("series score stays in 0..1: ${result.seriesScore}", result.seriesScore in 0.0..1.0)
    }
  }

  @Test
  fun theDecisiveMatchScoresAtFullLength() {
    // M3 is FULL (18 rounds per GameType): the 70% slot must work at FULL
    // length, not just the 10-round helper shape used elsewhere here.
    val fullLength = record(
      2,
      placement = 1,
      outcome = MatchOutcome.KING,
      score = 400,
      roundMisses = List(18) { 0 },
    )
    assertEquals(18, fullLength.rounds.size)
    assertEquals(1.0, scorePlacementMatch(fullLength), 1e-9)
    val result = scorePlacementSeries(listOf(perfect(0), perfect(1), fullLength))
    assertEquals("Platinum", result.rank.tier)
    assertEquals(1400, result.startRp)
  }

  // ==========================================================================
  //  Invalid and incomplete input
  // ==========================================================================

  @Test
  fun aPartialSeriesHasNoScore() {
    assertThrows(IllegalArgumentException::class.java) {
      scorePlacementSeries(listOf(perfect(0), perfect(1)))
    }
    assertThrows(IllegalArgumentException::class.java) { scorePlacementSeries(emptyList()) }
    assertThrows(IllegalArgumentException::class.java) {
      scorePlacementSeries(listOf(perfect(0), perfect(1), perfect(2), perfect(0)))
    }
  }

  @Test
  fun theSeriesNeedsEachSlotExactlyOnce() {
    assertThrows(IllegalArgumentException::class.java) {
      scorePlacementSeries(listOf(perfect(0), perfect(0), perfect(2)))
    }
    assertThrows(IllegalArgumentException::class.java) {
      scorePlacementSeries(listOf(perfect(0), perfect(1), perfect(1)))
    }
    // Order of arrival does not matter — slot identity does.
    val shuffled = scorePlacementSeries(listOf(perfect(2), perfect(0), perfect(1)))
    assertEquals(1.0, shuffled.seriesScore, 1e-9)
  }

  @Test
  fun anInFlightMatchWithNoRoundsIsNotScoreable() {
    val inFlight = PlacementMatchRecord(
      matchIndex = 1,
      placement = 1,
      outcome = MatchOutcome.KING,
      score = 0,
      rounds = emptyList(),
    )
    assertThrows(IllegalArgumentException::class.java) { scorePlacementMatch(inFlight) }
    assertThrows(IllegalArgumentException::class.java) {
      scorePlacementSeries(listOf(perfect(0), inFlight, perfect(2)))
    }
  }

  @Test
  fun aRecordOutsideTheThreeMatchSeriesIsRejected() {
    val stray = record(0).copy(matchIndex = 3)
    assertThrows(IllegalArgumentException::class.java) { scorePlacementMatch(stray) }
  }

  @Test
  fun perMatchScoresStayInsideZeroToOne() {
    listOf(perfect(1), terrible(1), catastrophic(1)).forEach {
      val score = scorePlacementMatch(it)
      assertTrue("match score in 0..1: $score", score in 0.0..1.0)
    }
    assertEquals(
      "a dominant match composites to the ceiling",
      1.0,
      scorePlacementMatch(perfect(1)),
      1e-9,
    )
    assertTrue(
      "a winless match composites near the floor",
      scorePlacementMatch(terrible(1)) < 0.15,
    )
  }
}
