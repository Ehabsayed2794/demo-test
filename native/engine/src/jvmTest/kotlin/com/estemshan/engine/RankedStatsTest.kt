package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S44 + S3 — the exactly-nine career statistics: the grid is nine and no
 * tenth, every mode's completion reaches the counters (Q46/Q14c/Q17 — the
 * old RANKED-only gate was the defect) while placement (RD10) is the one
 * exclusion left standing in every mode, and percentages derive from the
 * all-mode Games Played (RD23 / RD10). Tie conventions arrive via the merged
 * [placementOutcome] (Q2d/Q2f, PR #161): a shared bottom takes the LAST
 * placement — Koz for stats — and the all-tie case is all-King. Below that:
 * the A5.1 departure-charge fold — unchanged, still the one ROOM-closed
 * path (the Koz counter as a plain departure counter, 2026-10-17).
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
  fun everyModeCompletionRecordsTheSameCounters() {
    // Q46 (N-1, 2026-10-22): the RANKED-only gate was the defect. Q14c/Q17:
    // a completion in ROOM, RANKED or UNRANKED increments Games Played and
    // the seat's outcome counter alike. Only RP stays Ranked-only, and this
    // fold is RP-blind by construction — it takes a profile to READ the
    // placement gate and returns counters only, so a ROOM or UNRANKED
    // completion can never move tier/division/RP through it.
    for (mode in MatchMode.entries) {
      val stats = accumulateRankedStat(RankedStats(), MatchOutcome.KING, settled, mode)
      assertEquals("$mode: Games Played +1", 1, stats.gamesPlayed)
      assertEquals("$mode: King +1", 1, stats.kingCount)
      assertEquals("$mode: no second", 0, stats.secondCount)
      assertEquals("$mode: no third", 0, stats.thirdCount)
      assertEquals("$mode: no koz", 0, stats.kozCount)
    }
  }

  @Test
  fun tenMatchesPerModeAggregateIntoOneDenominator() {
    // T-19's worked example (plan §5.8 row "Nine statistics for every
    // mode"): 10 ROOM + 10 UNRANKED + 10 RANKED with 3 Kings each → Games
    // Played 30, King count 9, King % 30. Q17: counts and percentages
    // aggregate across all three modes — one career, one denominator.
    var stats = RankedStats()
    for (mode in listOf(MatchMode.ROOM, MatchMode.UNRANKED, MatchMode.RANKED)) {
      repeat(3) { stats = accumulateRankedStat(stats, MatchOutcome.KING, settled, mode) }
      repeat(7) { stats = accumulateRankedStat(stats, MatchOutcome.SECOND, settled, mode) }
    }
    assertEquals(30, stats.gamesPlayed)
    assertEquals(9, stats.kingCount)
    assertEquals(21, stats.secondCount)
    assertEquals(30, stats.kingPct)
    assertEquals(70, stats.secondPct)
  }

  @Test
  fun percentagesDenominateOverAllModesEvenWithNoRankedMatch() {
    // The denominator is the all-mode Games Played (Q17), not a Ranked-only
    // one in disguise: a career with no RANKED match at all still computes
    // whole percentages — and the zero-game divide-by-zero safety is
    // unchanged (pinned above in anEmptyStatBlockReadsAllZeros).
    var stats = accumulateRankedStat(RankedStats(), MatchOutcome.KING, settled, MatchMode.ROOM)
    stats = accumulateRankedStat(stats, MatchOutcome.SECOND, settled, MatchMode.UNRANKED)
    assertEquals(2, stats.gamesPlayed)
    assertEquals(50, stats.kingPct)
    assertEquals(50, stats.secondPct)
  }

  @Test
  fun placementMatchesCountTowardNoStatisticInEveryMode() {
    // RD10 through the widened gate (S3 acceptance 4): placement counts
    // toward nothing — not Games Played, not any percentage — in EVERY
    // mode. Placement runs in Ranked play, but the gate is mode-blind: it
    // reads the profile, so it cannot disagree with what the placement
    // service sees.
    val placing = beginPlacement(season)
    assertTrue(isPlacement(placing))
    for (mode in MatchMode.entries) {
      val before = RankedStats(gamesPlayed = 2, kozCount = 1)
      val after = accumulateRankedStat(before, MatchOutcome.KING, placing, mode)
      assertEquals("$mode: mid-placement records nothing", before, after)
    }
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

  // ── Tie placement through the merged conventions (Q2d/Q2f, PR #161) ────

  @Test
  fun t13sharedBottomSeatsTakeTheLastPlacementAsKozForStats() {
    // Q2d: every seat tied at the lowest total shares the LAST placement —
    // rank 4, KOZ in this vocabulary — and Q2f gives them all the same last
    // placement, so no bottom-tied seat ever reads Third. The convention is
    // decided upstream by the merged placementRank/placementOutcome; what
    // this pins is that the accumulator folds whatever they produce, per
    // player, in any mode. 100/99/10/10 (T-13 case 2's shape): King,
    // Second, two Koz.
    val totals = mapOf("A" to 100, "B" to 99, "C" to 10, "D" to 10)
    assertEquals(4, placementRank(totals, "C"))
    assertEquals(4, placementRank(totals, "D"))
    for (seat in listOf("C", "D")) {
      assertEquals("bottom-tied seat $seat is Koz", MatchOutcome.KOZ, placementOutcome(totals, seat))
      val stats = accumulateRankedStat(RankedStats(), placementOutcome(totals, seat), settled, ranked)
      assertEquals(1, stats.gamesPlayed)
      assertEquals(1, stats.kozCount)
      assertEquals("a tied-lowest seat is never Third", 0, stats.thirdCount)
    }
    // The same shared-bottom Koz rides the widened ROOM path — the mode the
    // old gate closed (Q46).
    val roomSharedKoz = accumulateRankedStat(
      RankedStats(), placementOutcome(totals, "C"), settled, MatchMode.ROOM,
    )
    assertEquals(1, roomSharedKoz.kozCount)
    assertEquals(1, roomSharedKoz.gamesPlayed)

    // T-13 case 1: 100/99/99/99 → one King + a three-way shared bottom, all
    // Koz — shared Koz is never Second/Third.
    val threeWayBottom = mapOf("A" to 100, "B" to 99, "C" to 99, "D" to 99)
    assertEquals(MatchOutcome.KING, placementOutcome(threeWayBottom, "A"))
    for (seat in listOf("B", "C", "D")) {
      assertEquals(MatchOutcome.KOZ, placementOutcome(threeWayBottom, seat))
      assertEquals(4, placementRank(threeWayBottom, seat))
    }
  }

  @Test
  fun t13theAllTieCaseIsAllKingInEveryMode() {
    // Q2d's sole exception: all four seats tied stays rank 1 — every seat
    // is King, and each player's own fold records a King, in every mode.
    val totals = mapOf("A" to 77, "B" to 77, "C" to 77, "D" to 77)
    for (mode in MatchMode.entries) {
      for (seat in totals.keys) {
        assertEquals(1, placementRank(totals, seat))
        assertEquals(MatchOutcome.KING, placementOutcome(totals, seat))
        val stats = accumulateRankedStat(RankedStats(), placementOutcome(totals, seat), settled, mode)
        assertEquals("$mode seat $seat: all-tie is a King", 1, stats.kingCount)
        assertEquals("$mode seat $seat: Games Played +1", 1, stats.gamesPlayed)
      }
    }
  }

  // ── A5.1 departure charges (2026-10-16 closure option (a), with the
  //    2026-10-17 owner clarification on Unranked) ───────────────────────

  @Test
  fun t15theTwoKozDeparturesChargeTwoKozAndOneGamesPlayedInRankedAndUnranked() {
    // A5.1's first two rows (explicit leave + the 15-timeout removal), the
    // RANKED/UNRANKED cells: "2 Koz" exactly — re-ratified by Q14b /
    // Q14b(1), 2026-10-22. The recorded form is option
    // (a) — +2 to the career Koz count with the Koz % denominator +1,
    // Games Played +1 (`NATIVE_V1_PLAN_AND_ESTIMATE.md:883-884`) — and
    // never a placement: no outcome row moves.
    for (mode in listOf(MatchMode.RANKED, MatchMode.UNRANKED)) {
      for (reason in listOf(LeaveReason.EXPLICIT_LEAVE, LeaveReason.FIFTEEN_TIMEOUT)) {
        val after = accumulateDepartureCharge(
          RankedStats(), settled, mode, LeaveMatch.decideLeave(mode, reason),
        )
        assertEquals("$reason in $mode: Games Played +1", 1, after.gamesPlayed)
        assertEquals("$reason in $mode: Koz +2", 2, after.kozCount)
        assertEquals("$reason in $mode: no outcome row is modelled",
          0, after.kingCount + after.secondCount + after.thirdCount)
        assertEquals("$reason in $mode: the charge is a counter increment, not a placement",
          200, after.kozPct)
      }
    }
  }

  @Test
  fun t15roomDeparturesRecordNothing() {
    // A5.1's scope correction as it lands at THIS surface: the fold's ROOM
    // gate records nothing. (The domain charges for ROOM departures are now
    // Koz1 — Q14b for the explicit leave, Q37 for the 15-timeout removal,
    // whose O-03d amount CANONICAL_RULES.md's "Pointer, 2026-10-23" closes
    // at 1 Koz.) The removal still fires in a Room; the record does not.
    for (reason in listOf(LeaveReason.EXPLICIT_LEAVE, LeaveReason.FIFTEEN_TIMEOUT)) {
      val before = RankedStats(gamesPlayed = 5, kozCount = 2)
      val after = accumulateDepartureCharge(
        before, settled, MatchMode.ROOM, LeaveMatch.decideLeave(MatchMode.ROOM, reason),
      )
      assertEquals("ROOM records nothing for a $reason departure", before, after)
    }
  }

  @Test
  fun t15aChargedDepartureForRoomRecordsNothingEvenWhenFedDirectly() {
    // A second line of defense at the stats surface: since Q14b / Q37
    // (2026-10-22) decideLeave produces a Koz1 for a ROOM explicit leave and
    // for the ROOM 15-timeout removal, while permanentCharge still reads
    // NONE for ROOM — so the fold's ROOM gate is what keeps every ROOM
    // departure at zero here, including a caller that bypasses the cores.
    // Feed it the exact state such a caller would — a 2-Koz departure
    // charged against a Room — and the fold must refuse.
    val before = RankedStats(gamesPlayed = 6, kingCount = 3, kozCount = 2)
    val after = accumulateDepartureCharge(before, settled, MatchMode.ROOM, KozCharge.Koz2)
    assertEquals("a caller-fed charge for a ROOM match records nothing", before, after)
  }

  @Test
  fun t15aVoteKickDepartureChargesTwoKozInBothVotableModes() {
    // Q14d(1) (2026-10-22; register of 2026-10-23): a passed Vote Kick
    // records 2 Koz in RANKED and UNRANKED — superseding A5.1's "1 Koz,
    // final" and the repealed 2026-10-16 C1 closure. Seed one natural Koz so
    // the assertion is unambiguous: the repealed 1-Koz reading would read 2.
    for (mode in listOf(MatchMode.RANKED, MatchMode.UNRANKED)) {
      val after = accumulateDepartureCharge(
        RankedStats(gamesPlayed = 1, kozCount = 1), settled, mode,
        LeaveMatch.decideLeave(mode, LeaveReason.VOTE_KICK),
      )
      assertEquals("vote kick in $mode: Games Played +1", 2, after.gamesPlayed)
      assertEquals("vote kick in $mode: Koz +2 over the seeded one (Q14d(1))", 3, after.kozCount)
    }
  }

  @Test
  fun t15anUnrankedDisconnectReplacementChargesTwoKozAndRoomRecordsNothing() {
    // A5.1's fourth row: a permanently replaced disconnect records 2 Koz in
    // UNRANKED; ROOM's verdict is "no Koz"; RANKED is "n/a — the vote is
    // not held in Ranked" (no such path ever calls the fold there). The
    // natural outcome rows stay untouched in every case.
    val unranked = accumulateDepartureCharge(
      RankedStats(gamesPlayed = 2, kingCount = 2), settled, MatchMode.UNRANKED,
      DisconnectVote.permanentCharge(MatchMode.UNRANKED)!!.toKozCharge(),
    )
    assertEquals(3, unranked.gamesPlayed)
    assertEquals(2, unranked.kozCount)
    assertEquals("the bot's play is never attributed to the departed player",
      2, unranked.kingCount)

    val room = accumulateDepartureCharge(
      RankedStats(gamesPlayed = 4, kozCount = 2), settled, MatchMode.ROOM,
      DisconnectVote.permanentCharge(MatchMode.ROOM)!!.toKozCharge(),
    )
    assertEquals(4, room.gamesPlayed)
    assertEquals(2, room.kozCount)
  }

  @Test
  fun t15aReclaimableReturnRecordsNothingInAnyMode() {
    // A5.1's fifth row + the borrowed-seat discriminator: a seat still
    // reclaimable whose human returns is no departure — "no penalty" in
    // all three modes, and not one of the nine moves.
    for (mode in MatchMode.entries) {
      val before = RankedStats(
        gamesPlayed = 7, kingCount = 2, secondCount = 1, thirdCount = 1, kozCount = 3,
      )
      val after = accumulateDepartureCharge(
        before, settled, mode, LeaveMatch.decideLeave(mode, LeaveReason.RETURNED_ALLOWED),
      )
      assertEquals("no row of the nine moves on a return ($mode)", before, after)
    }
  }

  @Test
  fun aDepartureChargeDuringPlacementRecordsNothing() {
    // RD10: placement matches count toward NOTHING — not Games Played, not
    // any percentage. A departure charge feeds exactly those counters, so
    // it records nothing mid-placement (implementation reading; flagged in
    // the S63 report).
    val placing = beginPlacement("2026Q4")
    val before = RankedStats(gamesPlayed = 1, kingCount = 1)
    val after = accumulateDepartureCharge(
      before, placing, MatchMode.RANKED,
      LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.EXPLICIT_LEAVE),
    )
    assertEquals(before, after)
  }

  @Test
  fun theGridCarriesTheChargedKozWeight() {
    // The nine are still exactly nine (RD23): a charge never adds a row —
    // it rides the existing Koz counters. One natural Koz plus one 2-Koz
    // departure reads 3 Koz over 2 Games Played — 150%, the counter
    // increment form's own arithmetic, not a placement.
    var stats = accumulateRankedStat(RankedStats(), MatchOutcome.KOZ, settled, MatchMode.RANKED)
    stats = accumulateDepartureCharge(
      stats, settled, MatchMode.RANKED,
      LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.EXPLICIT_LEAVE),
    )
    assertEquals(2, stats.gamesPlayed)
    assertEquals(3, stats.kozCount)
    assertEquals(150, stats.kozPct)
    assertEquals("the grid is still the nine", 9, stats.grid.size)
    assertEquals(3, stats.grid[7].value) // KOZ_COUNT
    assertEquals(150, stats.grid[8].value) // KOZ_PCT
  }
}
