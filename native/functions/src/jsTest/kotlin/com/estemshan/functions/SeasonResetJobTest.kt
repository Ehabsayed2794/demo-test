package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.PlacementState
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * S59 — the season-reset planner and one-way claim core (RD24, R27).
 *
 * Pins: the full claim decision matrix (fresh / resume / double-run /
 * other-target / stale-from / same-id / blank), the RD24 reset math as
 * planned through the job (all 19 rung destinations from a hand-written
 * table, King → Royal I, Bronze floor, RP restarts at the landed floor),
 * raise-only Highest Rank, placement + shield carry, progression-only
 * updates, and the fail-closed batch rules (foreign/corrupt profile,
 * wrong-season profile, duplicates, one bad among good ones).
 */
class SeasonResetJobTest {

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

  private fun rung(tier: String, division: String): LadderRank =
    rankLadder.first { it.tier == tier && it.division == division }

  private fun labelOf(tier: String, division: String): String =
    if (division.isEmpty()) tier else "$tier $division"

  /** Plan exactly one profile and assert it planned cleanly. */
  private fun resetOnce(before: RankedProfile): RankedProfile {
    val plan = planSeasonReset("S1", "S2", listOf("p1" to before))
    assertTrue(plan.failures.isEmpty(), "unexpected failures: ${plan.failures}")
    return plan.resets.single().profile
  }

  // ── the one-way claim matrix ──────────────────────────────────────

  @Test
  fun activeSeasonAtTheRequestedFromIsClaimed() {
    assertEquals(
      SeasonResetClaim.CLAIMED,
      decideSeasonResetClaim(
        currentSeasonId = "S1",
        currentStatus = SeasonStatus.ACTIVE,
        runningTargetSeasonId = null,
        requestedFrom = "S1",
        requestedTo = "S2",
      ),
    )
  }

  @Test
  fun aRunningResetTowardTheSameTargetResumes() {
    assertEquals(
      SeasonResetClaim.RESUME,
      decideSeasonResetClaim("S1", SeasonStatus.RUNNING, "S2", "S1", "S2"),
    )
  }

  @Test
  fun aRunningResetAlreadyOnItsTargetIsAlreadyComplete() {
    assertEquals(
      SeasonResetClaim.ALREADY_COMPLETE,
      decideSeasonResetClaim("S2", SeasonStatus.RUNNING, "S2", "S1", "S2"),
    )
    assertEquals(
      SeasonResetClaim.ALREADY_COMPLETE,
      decideSeasonResetClaim("S2", SeasonStatus.RUNNING, null, "S1", "S2"),
      "landing on the target is already complete even with no recorded target",
    )
  }

  @Test
  fun aCompletedResetReplaysAsAlreadyComplete() {
    assertEquals(
      SeasonResetClaim.ALREADY_COMPLETE,
      decideSeasonResetClaim("S2", SeasonStatus.COMPLETE, null, "S1", "S2"),
    )
    assertEquals(
      SeasonResetClaim.ALREADY_COMPLETE,
      decideSeasonResetClaim("S2", SeasonStatus.COMPLETE, "S2", "S1", "S2"),
      "runningTargetSeasonId is ignored once COMPLETE",
    )
  }

  @Test
  fun aRunningResetTowardAnotherTargetIsInProgressOtherSeason() {
    assertEquals(
      SeasonResetClaim.IN_PROGRESS_OTHER_SEASON,
      decideSeasonResetClaim("S1", SeasonStatus.RUNNING, "S3", "S1", "S2"),
    )
    assertEquals(
      SeasonResetClaim.IN_PROGRESS_OTHER_SEASON,
      decideSeasonResetClaim("S1", SeasonStatus.RUNNING, null, "S1", "S2"),
      "a RUNNING season with no recorded target cannot be resumed",
    )
    assertEquals(
      SeasonResetClaim.IN_PROGRESS_OTHER_SEASON,
      decideSeasonResetClaim("S0", SeasonStatus.RUNNING, "S2", "S1", "S2"),
      "a RUNNING season that is not the requested one must wait, never be claimed",
    )
  }

  @Test
  fun aStaleFromNeverClaims() {
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S1", SeasonStatus.ACTIVE, null, "S0", "S2"),
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S0", SeasonStatus.ACTIVE, null, "S1", "S2"),
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S3", SeasonStatus.COMPLETE, null, "S1", "S2"),
    )
  }

  @Test
  fun sameIdResetIsRejected() {
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S1", SeasonStatus.ACTIVE, null, "S1", "S1"),
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S2", SeasonStatus.COMPLETE, null, "S2", "S2"),
      "the double-run guard precedes even a matching COMPLETE",
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S1", SeasonStatus.RUNNING, "S2", "S1", "S1"),
      "the same-id guard precedes the RUNNING branch",
    )
  }

  @Test
  fun blankIdsAreRejected() {
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("", SeasonStatus.ACTIVE, null, "S1", "S2"),
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S1", SeasonStatus.ACTIVE, null, "", "S2"),
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S1", SeasonStatus.ACTIVE, null, "S1", ""),
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("   ", SeasonStatus.RUNNING, "S2", "S1", "S2"),
      "whitespace is blank",
    )
    assertEquals(
      SeasonResetClaim.REJECTED,
      decideSeasonResetClaim("S1", SeasonStatus.ACTIVE, null, "S1", "   "),
    )
  }

  @Test
  fun theWholeClaimMatrix() {
    val cases = listOf(
      ClaimCase("fresh claim", "S1", SeasonStatus.ACTIVE, null, "S1", "S2", SeasonResetClaim.CLAIMED),
      ClaimCase("ACTIVE ignores a present running target", "S1", SeasonStatus.ACTIVE, "S3", "S1", "S2", SeasonResetClaim.CLAIMED),
      ClaimCase("stale from", "S1", SeasonStatus.ACTIVE, null, "S0", "S2", SeasonResetClaim.REJECTED),
      ClaimCase("the active season is another one", "S0", SeasonStatus.ACTIVE, null, "S1", "S2", SeasonResetClaim.REJECTED),
      ClaimCase("resume the same in-flight transition", "S1", SeasonStatus.RUNNING, "S2", "S1", "S2", SeasonResetClaim.RESUME),
      ClaimCase("running toward another target", "S1", SeasonStatus.RUNNING, "S3", "S1", "S2", SeasonResetClaim.IN_PROGRESS_OTHER_SEASON),
      ClaimCase("running with no recorded target", "S1", SeasonStatus.RUNNING, null, "S1", "S2", SeasonResetClaim.IN_PROGRESS_OTHER_SEASON),
      ClaimCase("running from another season", "S0", SeasonStatus.RUNNING, "S2", "S1", "S2", SeasonResetClaim.IN_PROGRESS_OTHER_SEASON),
      ClaimCase("running already on the target", "S2", SeasonStatus.RUNNING, "S2", "S1", "S2", SeasonResetClaim.ALREADY_COMPLETE),
      ClaimCase("running on the target with no recorded target", "S2", SeasonStatus.RUNNING, null, "S1", "S2", SeasonResetClaim.ALREADY_COMPLETE),
      ClaimCase("completed and replayed", "S2", SeasonStatus.COMPLETE, null, "S1", "S2", SeasonResetClaim.ALREADY_COMPLETE),
      ClaimCase("completed replay ignores a stale running target", "S2", SeasonStatus.COMPLETE, "S2", "S1", "S2", SeasonResetClaim.ALREADY_COMPLETE),
      ClaimCase("completed at the source, target never landed", "S1", SeasonStatus.COMPLETE, null, "S1", "S2", SeasonResetClaim.REJECTED),
      ClaimCase("completed at a third season", "S3", SeasonStatus.COMPLETE, null, "S1", "S2", SeasonResetClaim.REJECTED),
      ClaimCase("same from and to while active", "S1", SeasonStatus.ACTIVE, null, "S1", "S1", SeasonResetClaim.REJECTED),
      ClaimCase("same from and to while completed", "S2", SeasonStatus.COMPLETE, null, "S2", "S2", SeasonResetClaim.REJECTED),
      ClaimCase("same from and to while running", "S1", SeasonStatus.RUNNING, "S2", "S1", "S1", SeasonResetClaim.REJECTED),
      ClaimCase("blank current season", "", SeasonStatus.ACTIVE, null, "S1", "S2", SeasonResetClaim.REJECTED),
      ClaimCase("blank from", "S1", SeasonStatus.ACTIVE, null, "", "S2", SeasonResetClaim.REJECTED),
      ClaimCase("blank to", "S1", SeasonStatus.ACTIVE, null, "S1", "", SeasonResetClaim.REJECTED),
      ClaimCase("whitespace to", "S1", SeasonStatus.ACTIVE, null, "S1", "   ", SeasonResetClaim.REJECTED),
    )
    cases.forEach { case ->
      assertEquals(
        case.expected,
        decideSeasonResetClaim(
          case.currentSeasonId,
          case.status,
          case.runningTarget,
          case.from,
          case.to,
        ),
        case.name,
      )
    }
  }

  // ── the planner ───────────────────────────────────────────────────

  @Test
  fun freshBatchPlansInInputOrder() {
    val plan = planSeasonReset(
      "S1",
      "S2",
      listOf(
        "p1" to profile("Gold", "I", 830),
        "p2" to profile("Silver", "II", 380),
        "p3" to profile("King", "", 3100),
      ),
    )

    assertEquals("S1", plan.fromSeasonId)
    assertEquals("S2", plan.toSeasonId)
    assertTrue(plan.failures.isEmpty(), "a clean batch must not report failures")
    assertEquals(listOf("p1", "p2", "p3"), plan.resets.map { it.playerId })
    assertEquals(
      listOf("Gold III", "Bronze I", "Royal I"),
      plan.resets.map { labelOf(it.profile.tier, it.profile.division) },
    )
  }

  @Test
  fun everyRungLandsOnItsHandWrittenDestination() {
    // Independent expected table: literal tier/division per rung, derived
    // by hand from the ladder order — the test never reimplements the
    // index arithmetic.
    val expected = listOf(
      "Bronze III", "Bronze III", "Bronze III",
      "Bronze II", "Bronze I", "Silver III",
      "Silver II", "Silver I", "Gold III",
      "Gold II", "Gold I", "Platinum III",
      "Platinum II", "Platinum I", "Diamond III",
      "Diamond II", "Diamond I", "Royal III",
      "Royal I",
    )
    assertEquals(19, expected.size, "the hand table is the whole 19-rung ladder: 6 tiers × 3 divisions + King")
    assertEquals(rankLadder.size, expected.size, "the table covers the whole ladder")

    rankLadder.forEachIndexed { index, rung ->
      val plan = planSeasonReset(
        "S1",
        "S2",
        listOf("p$index" to profile(rung.tier, rung.division, rung.lowerBound)),
      )
      assertTrue(plan.failures.isEmpty(), "rung $index must plan cleanly: ${plan.failures}")
      val reset = plan.resets.single().profile
      assertEquals(
        expected[index],
        labelOf(reset.tier, reset.division),
        "rung $index (${rung.label()}) lands wrong",
      )
      val landed = rankLadder.first { labelOf(it.tier, it.division) == expected[index] }
      assertEquals(landed.lowerBound, reset.rp, "RP restarts at the landed rung's floor")
      assertEquals(landed, rankAt(reset.rp), "rankAt(reset rp) resolves the landed rung — the profile stays self-consistent")
    }
  }

  @Test
  fun goldIDemotesTwoStepsToGoldIIIAtItsFloor() {
    // RD24's own example, asserted literally through the plan.
    val reset = resetOnce(profile("Gold", "I", 830))

    assertEquals("Gold", reset.tier)
    assertEquals("III", reset.division)
    assertEquals(575, reset.rp, "RP restarts at Gold III's approved floor")
    assertEquals("S2", reset.seasonId)
  }

  @Test
  fun kingLandsOnRoyalIByDecisionNotByStepping() {
    // Index − 2 from King would land Royal II — RD24 overrides it: King has
    // no divisions to step through, so the destination is Royal I.
    val reset = resetOnce(profile("King", "", 3100))

    assertEquals("Royal", reset.tier)
    assertEquals("I", reset.division)
    assertEquals(2750, reset.rp)
  }

  @Test
  fun bronzeFloorsAtBronzeIII() {
    val fromBronzeII = resetOnce(profile("Bronze", "II", 100))
    assertEquals("Bronze", fromBronzeII.tier)
    assertEquals("III", fromBronzeII.division)
    assertEquals(0, fromBronzeII.rp, "two steps off the ladder bottom floors at Bronze III")

    val atBottom = resetOnce(profile("Bronze", "III", 0))
    assertEquals("Bronze", atBottom.tier)
    assertEquals("III", atBottom.division)
    assertEquals(0, atBottom.rp)
  }

  @Test
  fun highestRankIsPreservedAndNeverLowered() {
    val peak = rung("Platinum", "II")
    val demoted = resetOnce(profile("Gold", "I", 830, highestRank = peak))
    assertEquals(peak, demoted.highestRank, "Highest Rank survives the seasonal demotion")

    val king = rung("King", "")
    val fromKing = resetOnce(profile("King", "", 3100, highestRank = king))
    assertEquals(king, fromKing.highestRank, "the King peak is never erased by the reset")
  }

  @Test
  fun aStalePeakIsRaisedToThePreResetRungNeverLowered() {
    // The raise-only updateHighestRank policy: a recorded peak can lag the
    // current rung, and the reset closes the lag without ever lowering it.
    val reset = resetOnce(profile("Gold", "I", 830, highestRank = rankLadder.first()))

    assertEquals(rung("Gold", "I"), reset.highestRank)
  }

  @Test
  fun placementStateAndShieldCarryOverUntouched() {
    val done = resetOnce(
      profile(
        "Silver", "II", 380,
        placementState = PlacementState.COMPLETE,
        shield = true,
      ),
    )
    assertEquals(PlacementState.COMPLETE, done.placementState, "placement never repeats per season")
    assertTrue(done.demotionShield, "no contract ends the shield at the season boundary (RD7)")

    val skipped = resetOnce(profile("Bronze", "III", 0, placementState = PlacementState.SKIPPED))
    assertEquals(PlacementState.SKIPPED, skipped.placementState, "skipping stays irreversible across the reset")
  }

  @Test
  fun thePlannedProfileChangesProgressionFieldsOnly() {
    // The structural half of R27's stats rule: compared field-by-field via
    // copy-equality, the plan may change ONLY tier/division/rp/seasonId/
    // highestRank. placementState and the shield come through untouched, so
    // the caller can merge progression into the player document.
    val before = RankedProfile(
      tier = "Gold",
      division = "I",
      rp = 830,
      seasonId = "S1",
      highestRank = rung("Gold", "I"),
      placementState = PlacementState.COMPLETE,
      demotionShield = true,
    )
    assertEquals(
      before.copy(division = "III", rp = 575, seasonId = "S2"),
      resetOnce(before),
    )

    val crossTier = RankedProfile(
      tier = "Gold",
      division = "II",
      rp = 750,
      seasonId = "S1",
      highestRank = rung("Gold", "II"),
      placementState = PlacementState.SKIPPED,
    )
    assertEquals(
      crossTier.copy(tier = "Silver", division = "I", rp = 450, seasonId = "S2"),
      resetOnce(crossTier),
    )
  }

  // ── fail-closed batch rules (R27) ─────────────────────────────────

  @Test
  fun aForeignProfileFailsClosed() {
    val corruptRung = RankedProfile(tier = "Mithril", division = "I", rp = 9999, seasonId = "S1")
    val plan = planSeasonReset("S1", "S2", listOf("ghost" to corruptRung))

    assertTrue(plan.resets.isEmpty(), "a foreign rung must never plan a reset (R27)")
    assertEquals(1, plan.failures.size)
    assertEquals("ghost", plan.failures.single().playerId)
    assertTrue(plan.failures.single().reason.isNotBlank(), "a failure must carry a reason")

    val corruptDivision = profile("Gold", "IV", 830)
    val plan2 = planSeasonReset("S1", "S2", listOf("ghost" to corruptDivision))
    assertTrue(plan2.resets.isEmpty())
    assertEquals(1, plan2.failures.size)
    assertEquals("ghost", plan2.failures.single().playerId)
    assertTrue(plan2.failures.single().reason.isNotBlank(), "a failure must carry a reason")
  }

  @Test
  fun oneBadProfileEmptiesTheWholePlan() {
    val plan = planSeasonReset(
      "S1",
      "S2",
      listOf(
        "good1" to profile("Gold", "I", 830),
        "bad" to RankedProfile(tier = "Mithril", division = "I", rp = 9999, seasonId = "S1"),
        "good2" to profile("Silver", "I", 460),
      ),
    )

    assertTrue(plan.resets.isEmpty(), "fail closed: one blocker empties the whole plan (R27)")
    assertEquals(listOf("bad"), plan.failures.map { it.playerId })
  }

  @Test
  fun aProfileFromAnotherSeasonFailsClosed() {
    val plan = planSeasonReset(
      "S1",
      "S2",
      listOf(
        "stale" to profile("Gold", "I", 830, seasonId = "S0"),
        "fresh" to profile("Silver", "I", 460),
      ),
    )

    assertTrue(plan.resets.isEmpty(), "a foreign-season profile must not be reset")
    assertEquals(listOf("stale"), plan.failures.map { it.playerId })
    assertTrue(plan.failures.single().reason.isNotBlank())
  }

  @Test
  fun duplicatePlayerIdsFailClosed() {
    val plan = planSeasonReset(
      "S1",
      "S2",
      listOf(
        "p1" to profile("Gold", "I", 830),
        "p2" to profile("Silver", "I", 460),
        "p1" to profile("Bronze", "III", 0),
      ),
    )

    assertTrue(plan.resets.isEmpty(), "duplicate player IDs fail closed (R27)")
    assertEquals(listOf("p1"), plan.failures.map { it.playerId })
  }

  @Test
  fun aBlankPlayerIdFailsClosed() {
    val whitespace = planSeasonReset(
      "S1",
      "S2",
      listOf(
        "   " to profile("Gold", "I", 830),
        "good" to profile("Silver", "I", 460),
      ),
    )

    assertTrue(whitespace.resets.isEmpty(), "a blank player ID must fail closed (R27)")
    assertEquals(listOf("   "), whitespace.failures.map { it.playerId })
    assertTrue(whitespace.failures.single().reason.isNotBlank())

    val empty = planSeasonReset("S1", "S2", listOf("" to profile("Bronze", "III", 0)))
    assertTrue(empty.resets.isEmpty(), "an empty player ID must fail closed (R27)")
    assertEquals(listOf(""), empty.failures.map { it.playerId })
  }

  @Test
  fun everyBlockerIsReportedInOnePass() {
    val plan = planSeasonReset(
      "S1",
      "S2",
      listOf(
        "wrongSeason" to profile("Gold", "I", 830, seasonId = "S0"),
        "good" to profile("Silver", "I", 460),
        "corrupt" to RankedProfile(tier = "Mithril", division = "I", rp = 9999, seasonId = "S1"),
      ),
    )

    assertTrue(plan.resets.isEmpty(), "fail closed on any blocker")
    assertEquals(
      listOf("wrongSeason", "corrupt"),
      plan.failures.map { it.playerId },
      "the scan continues after a failure so the caller sees every blocker",
    )
  }

  @Test
  fun emptyInputPlansNothing() {
    val plan = planSeasonReset("S1", "S2", emptyList())

    assertEquals("S1", plan.fromSeasonId)
    assertEquals("S2", plan.toSeasonId)
    assertTrue(plan.resets.isEmpty())
    assertTrue(plan.failures.isEmpty())
  }

  @Test
  fun badSeasonIdsAreRejected() {
    assertFailsWith<IllegalArgumentException> { planSeasonReset("", "S2", emptyList()) }
    assertFailsWith<IllegalArgumentException> { planSeasonReset("   ", "S2", emptyList()) }
    assertFailsWith<IllegalArgumentException> { planSeasonReset("S1", "", emptyList()) }
    assertFailsWith<IllegalArgumentException> { planSeasonReset("S1", "S1", emptyList()) }
  }

  private data class ClaimCase(
    val name: String,
    val currentSeasonId: String,
    val status: SeasonStatus,
    val runningTarget: String?,
    val from: String,
    val to: String,
    val expected: SeasonResetClaim,
  )
}
