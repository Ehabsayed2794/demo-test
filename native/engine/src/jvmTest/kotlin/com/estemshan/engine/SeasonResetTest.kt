package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S59 — the season-reset math (RD24): two division steps down the single
 * ladder, floored at Bronze III, King → Royal I by owner decision, RP
 * restarted at the new rung's floor, Highest raised-only through the
 * existing policy, placement and shield carried over, stats structurally
 * untouched (never taken).
 */
class SeasonResetTest {

  private fun profile(
    tier: String,
    division: String,
    rp: Int,
    seasonId: String = "S1",
    highestRank: LadderRank = rankLadder.first(),
    placementState: PlacementState = PlacementState.NOT_STARTED,
    shield: Boolean = false,
  ): RankedProfile = RankedProfile(
    tier = tier,
    division = division,
    rp = rp,
    seasonId = seasonId,
    highestRank = highestRank,
    placementState = placementState,
    demotionShield = shield,
  )

  @Test
  fun theResetStepsAreTheClosedTwo() {
    assertEquals("RD24: exactly two division steps", 2, SEASON_RESET_STEPS)
  }

  @Test
  fun goldIDemotesTwoStepsToGoldIIIAtItsFloor() {
    // RD24's own example, asserted literally: Gold I → Gold III.
    val out = applySeasonReset(profile("Gold", "I", 830), "S2")
    assertEquals("Gold", out.tier)
    assertEquals("III", out.division)
    assertEquals("RP restarts at the new rung's floor", 575, out.rp)
    assertEquals("S2", out.seasonId)
  }

  @Test
  fun stepsCrossTierBoundariesAndFloors() {
    // 750 is Gold II; two rungs down the single ladder is Silver I (450).
    val crossTier = applySeasonReset(profile("Gold", "II", 750), "S2")
    assertEquals("Silver", crossTier.tier)
    assertEquals("I", crossTier.division)
    assertEquals(450, crossTier.rp)

    // Royal II (2500) steps to Diamond I (2000).
    val royal = applySeasonReset(profile("Royal", "II", 2600), "S2")
    assertEquals("Diamond", royal.tier)
    assertEquals("I", royal.division)
    assertEquals(2000, royal.rp)

    // Bronze II floors at Bronze III (0): two steps would fall off the ladder.
    val floored = applySeasonReset(profile("Bronze", "II", 100), "S2")
    assertEquals("Bronze", floored.tier)
    assertEquals("III", floored.division)
    assertEquals(0, floored.rp)

    // Bronze III stays Bronze III.
    val bottom = applySeasonReset(profile("Bronze", "III", 0), "S2")
    assertEquals("Bronze", bottom.tier)
    assertEquals("III", bottom.division)
    assertEquals(0, bottom.rp)
  }

  @Test
  fun kingLandsOnRoyalIByDecisionNotByStepping() {
    // Index − 2 from King would be Royal III — RD24 overrides it:
    // King has no divisions to step through, so the destination is Royal I.
    val out = applySeasonReset(profile("King", "", 3100), "S2")
    assertEquals("Royal", out.tier)
    assertEquals("I", out.division)
    assertEquals(2750, out.rp)
  }

  @Test
  fun highestRankIsNeverLoweredByTheReset() {
    val peak = rankLadder.first { it.tier == "Platinum" && it.division == "II" }
    val out = applySeasonReset(
      profile("Gold", "I", 830, highestRank = peak),
      "S2",
    )
    assertEquals("Highest Rank survives the seasonal demotion", peak, out.highestRank)
  }

  @Test
  fun aStalePeakIsRaisedToThePreResetRungNeverLowered() {
    // No engine path auto-records promotions, so a recorded peak can lag
    // the current rung (e.g. an S54 starting assignment, which does not
    // touch highestRank). The reset applies the existing raise-only
    // policy — it never lowers, and it closes the lag.
    val stale = rankLadder.first()
    val out = applySeasonReset(
      profile("Gold", "I", 830, highestRank = stale),
      "S2",
    )
    assertEquals(
      "the reset preserves the highest rank ATTAINED, not the stale record",
      rankLadder.first { it.tier == "Gold" && it.division == "I" },
      out.highestRank,
    )
  }

  @Test
  fun aKingKeepsKingAsTheHistoricalPeakThroughRoyalI() {
    val king = rankLadder.first { it.isKing }
    val out = applySeasonReset(
      profile("King", "", 3100, highestRank = king),
      "S2",
    )
    assertEquals("Royal", out.tier)
    assertEquals("the King peak is never erased by the reset", king, out.highestRank)
  }

  @Test
  fun placementStateCarriesOverUntouched() {
    // RD10: placement never repeats per season — S59 must not reopen it.
    val done = applySeasonReset(
      profile("Silver", "I", 460, placementState = PlacementState.COMPLETE),
      "S2",
    )
    assertEquals(PlacementState.COMPLETE, done.placementState)

    val skipped = applySeasonReset(
      profile("Bronze", "III", 0, placementState = PlacementState.SKIPPED),
      "S2",
    )
    assertEquals("skipping stays irreversible across the reset", PlacementState.SKIPPED, skipped.placementState)
  }

  @Test
  fun theShieldCarriesOverUnchanged() {
    // No contract ends the shield at the season boundary: it guards the
    // next MATCH played (RD7), and a reset plays no match. Clearing it
    // would invent a rule; carrying a Boolean cannot break the
    // rankAt/RP invariant.
    val out = applySeasonReset(profile("Gold", "I", 830, shield = true), "S2")
    assertTrue("the earned shield survives into the new season", hasDemotionShield(out))
    assertEquals(1, shieldMatchesRemaining(out))
  }

  @Test
  fun careerStatsSurviveTheResetUntouched() {
    // RD24: "career stats never reset". A ranked Gold account is past
    // placement (COMPLETE — NOT_STARTED at Gold I is unreachable), so the
    // realistic fixture accumulates through it: 2 pre-reset matches, the
    // reset, then 1 post-reset match on the carried totals.
    val before = profile(
      "Gold", "I", 830, seasonId = "S1",
      placementState = PlacementState.COMPLETE,
    )
    var stats = RankedStats()
    stats = accumulateRankedStat(stats, MatchOutcome.KING, before, MatchMode.RANKED)
    stats = accumulateRankedStat(stats, MatchOutcome.SECOND, before, MatchMode.RANKED)
    assertEquals(2, stats.gamesPlayed)

    val reset = applySeasonReset(before, "S2")
    assertEquals("placement completion survives the reset", PlacementState.COMPLETE, reset.placementState)

    val after = accumulateRankedStat(stats, MatchOutcome.KOZ, reset, MatchMode.RANKED)
    assertEquals("Games Played never resets across seasons", 3, after.gamesPlayed)
    assertEquals(1, after.kingCount)
    assertEquals(1, after.secondCount)
    assertEquals(1, after.kozCount)
    assertEquals(0, after.thirdCount)
    // The full RD23 nine, percentages over the CONTINUOUS total — a wipe
    // anywhere in the chain would zero these, not just the counts.
    assertEquals(
      listOf(
        RankedStat(RankedStatKey.GAMES_PLAYED, 3),
        RankedStat(RankedStatKey.KING_COUNT, 1),
        RankedStat(RankedStatKey.KING_PCT, 33),
        RankedStat(RankedStatKey.SECOND_COUNT, 1),
        RankedStat(RankedStatKey.SECOND_PCT, 33),
        RankedStat(RankedStatKey.THIRD_COUNT, 0),
        RankedStat(RankedStatKey.THIRD_PCT, 0),
        RankedStat(RankedStatKey.KOZ_COUNT, 1),
        RankedStat(RankedStatKey.KOZ_PCT, 33),
      ),
      after.grid,
    )
  }

  @Test
  fun placementAndUnrankedMatchesStayExcludedAcrossTheReset() {
    // RD23/RD28 gates hold on both sides of the boundary: mid-placement
    // and non-RANKED modes record nothing, before or after a reset.
    val ranked = profile("Gold", "I", 830, seasonId = "S1", placementState = PlacementState.COMPLETE)
    val midPlacement = profile("Bronze", "III", 0, seasonId = "S1", placementState = PlacementState.IN_PROGRESS)
    val reset = applySeasonReset(ranked, "S2")

    assertEquals("placement matches count toward nothing", RankedStats(), accumulateRankedStat(RankedStats(), MatchOutcome.KING, midPlacement, MatchMode.RANKED))
    assertEquals("rooms record no Ranked stats", RankedStats(), accumulateRankedStat(RankedStats(), MatchOutcome.KING, reset, MatchMode.ROOM))
    assertEquals("unranked records no Ranked stats", RankedStats(), accumulateRankedStat(RankedStats(), MatchOutcome.KING, reset, MatchMode.UNRANKED))
    assertEquals(
      "ranked still accumulates after the reset",
      1,
      accumulateRankedStat(RankedStats(), MatchOutcome.KING, reset, MatchMode.RANKED).gamesPlayed,
    )
  }

  @Test
  fun aResetAlwaysOpensANamedSeason() {
    assertThrows(IllegalArgumentException::class.java) {
      applySeasonReset(profile("Gold", "I", 830), "")
    }
    assertThrows(IllegalArgumentException::class.java) {
      applySeasonReset(profile("Gold", "I", 830), "   ")
    }
  }

  @Test
  fun reopeningTheCurrentSeasonIdIsADoubleRun() {
    // Pure double-run aid: a reset always opens a NEW season, so the same
    // ID is definitionally a re-run. Not an exactly-once guard — that
    // belongs to the server-side season job.
    assertThrows(IllegalArgumentException::class.java) {
      applySeasonReset(profile("Gold", "I", 830, seasonId = "S2"), "S2")
    }
    val ok = applySeasonReset(profile("Gold", "I", 830, seasonId = "S1"), "S2")
    assertEquals("S2", ok.seasonId)
  }

  @Test
  fun aForeignRungFailsInsteadOfFlooring() {
    // indexOf == -1 must never flow into the floor math: under R27's
    // irreversible migration, a silent Bronze III for an unknown rank is
    // the worst outcome. Valid bottom flooring is unaffected (see floors).
    val foreign = LadderRank(tier = "Mithril", arabic = "x", lowerBound = 9999, division = "I")
    assertThrows(IllegalArgumentException::class.java) { seasonResetRank(foreign) }
    // A foreign rung wearing the King tier must not slip through the
    // King shortcut either.
    val falseKing = LadderRank(tier = "King", arabic = "x", lowerBound = 9999, division = "I")
    assertThrows(IllegalArgumentException::class.java) { seasonResetRank(falseKing) }
  }

  @Test
  fun everyRungLandsOnItsHandDerivedDestination() {
    // Independent expected table: literal tier/division per rung, derived
    // by hand from the ladder order — the test never reimplements the
    // index arithmetic. Also pins the keeps-both-consistent invariant:
    // rankAt(reset RP) always resolves the landed rung.
    val expected = listOf(
      "Bronze III", "Bronze III", "Bronze III",
      "Bronze II", "Bronze I", "Silver III",
      "Silver II", "Silver I", "Gold III",
      "Gold II", "Gold I", "Platinum III",
      "Platinum II", "Platinum I", "Diamond III",
      "Diamond II", "Diamond I", "Royal III",
      "Royal I",
    )
    assertEquals("the table covers the whole 19-rung ladder", rankLadder.size, expected.size)
    rankLadder.forEachIndexed { index, rung ->
      val landed = seasonResetRank(rung)
      val label = if (landed.division.isEmpty()) landed.tier else "${landed.tier} ${landed.division}"
      assertEquals("rung $index (${rung.tier} ${rung.division}) lands wrong", expected[index], label)

      val reset = applySeasonReset(
        profile(rung.tier, rung.division, rung.lowerBound, seasonId = "S1"),
        "S2",
      )
      assertEquals(expected[index], "${reset.tier} ${reset.division}".trim())
      assertEquals(
        "RP restarts at the landed rung's floor",
        landed.lowerBound,
        reset.rp,
      )
      assertEquals(
        "rankAt(reset RP) resolves the landed rung — the profile stays self-consistent",
        landed,
        rankAt(reset.rp),
      )
    }
  }
}
