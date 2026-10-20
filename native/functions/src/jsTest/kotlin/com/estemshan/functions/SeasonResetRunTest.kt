package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.PlacementState
import com.estemshan.engine.rankLadder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S59 — the season-reset orchestrator (RD24, R27).
 *
 * [SeasonResetJobTest] pins the frozen pure core (the claim matrix and the
 * engine reset math). This class pins everything AROUND it, through the
 * [SeasonResetDb] port: the configuration and due gates, the second read that
 * makes the claim safe, the DRY_RUN that must not write, the fail-closed
 * planning rules (including the local profile parser), the six-field merge,
 * the 400-write chunking, and the two irreversible outcomes R27 distinguishes —
 * a fresh claim that cannot plan is reverted, while a resume that cannot plan
 * is left claimed for an operator.
 *
 * The fake records every transaction, season write and profile batch, so the
 * assertions are about what the orchestrator did, never about what Firestore
 * would have done. The real Admin port is pinned separately by
 * [SeasonResetDryRunEmulatorTest].
 */
class SeasonResetRunTest {

  private companion object {
    /** A fixed clock: the job's deadline and its 90-day successor are exact. */
    const val NOW: Long = 1_800_000_000_000
    const val DAY: Long = 24L * 60 * 60 * 1000
    const val NINETY_DAYS: Long = 90L * DAY
    const val FROM: String = "s1"
    const val TO: String = "s2"

    /** "The field is not in the document at all", distinct from an explicit null. */
    val ABSENT: Any = Any()
  }

  // ── fixtures ─────────────────────────────────────────────────────────

  private fun rung(tier: String, division: String): LadderRank =
    rankLadder.first { it.tier == tier && it.division == division }

  /**
   * A production-shaped `players/{uid}` progression block. Every parameter can
   * be [ABSENT] to model a field the legacy writer never created; an explicit
   * null stays an explicit null.
   */
  private fun validFields(
    tier: Any? = "Gold",
    division: Any? = "I",
    rp: Any? = 825,
    seasonId: Any? = FROM,
    highestTier: Any? = "Gold",
    highestDivision: Any? = "I",
    placementState: Any? = "SKIPPED",
    shield: Any? = false,
  ): MutableMap<String, Any?> = linkedMapOf<String, Any?>().apply {
    if (tier !== ABSENT) put("tier", tier)
    if (division !== ABSENT) put("division", division)
    if (rp !== ABSENT) put("rp", rp)
    if (seasonId !== ABSENT) put("seasonId", seasonId)
    if (highestTier !== ABSENT) put("highestRankTier", highestTier)
    if (highestDivision !== ABSENT) put("highestRankDivision", highestDivision)
    if (placementState !== ABSENT) put("placementState", placementState)
    if (shield !== ABSENT) put("demotionShield", shield)
  }

  private fun seasonFields(
    seasonId: Any? = FROM,
    status: Any? = "ACTIVE",
    target: Any? = ABSENT,
    next: Any? = TO,
    endsAt: Any? = NOW - 1_000,
    startsAt: Any? = NOW - NINETY_DAYS,
  ): MutableMap<String, Any?> = linkedMapOf<String, Any?>().apply {
    if (seasonId !== ABSENT) put("seasonId", seasonId)
    if (status !== ABSENT) put("status", status)
    if (target !== ABSENT) put("targetSeasonId", target)
    if (next !== ABSENT) put("nextSeasonId", next)
    if (endsAt !== ABSENT) put("endsAt", endsAt)
    if (startsAt !== ABSENT) put("startsAt", startsAt)
  }

  private class FakeDb(
    var season: MutableMap<String, Any?>?,
    var profiles: List<SeasonProfile> = emptyList(),
  ) : SeasonResetDb {
    var transactions = 0
      private set
    var readAllProfilesCalls = 0
      private set
    var beforeTransaction: ((Int) -> Unit)? = null
    var failProfileWriteNumber: Int = -1
    var failProfileRead: Boolean = false

    val seasonWrites = mutableListOf<Map<String, Any?>>()
    val profileWrites = mutableListOf<List<Pair<String, Map<String, Any?>>>>()

    override suspend fun <T> transact(block: suspend (SeasonResetDb.Tx) -> T): T {
      transactions++
      beforeTransaction?.invoke(transactions)
      val tx = object : SeasonResetDb.Tx {
        override suspend fun readSeason(): Map<String, Any?>? =
          season?.let { LinkedHashMap(it) }

        override fun writeSeason(fields: Map<String, Any?>) {
          seasonWrites += LinkedHashMap(fields)
          val current = season ?: return
          current.putAll(fields)
        }
      }
      return block(tx)
    }

    override suspend fun readAllProfiles(): List<SeasonProfile> {
      readAllProfilesCalls++
      if (failProfileRead) throw IllegalStateException("simulated profile read that never resolved")
      return profiles
    }

    override suspend fun writeProfiles(updates: List<Pair<String, Map<String, Any?>>>) {
      if (profileWrites.size + 1 == failProfileWriteNumber) {
        throw IllegalStateException("simulated transport failure on batch ${profileWrites.size + 1}")
      }
      profileWrites += updates.map { (playerId, fields) -> playerId to LinkedHashMap(fields) }
    }
  }

  private suspend fun dryRun(fields: Map<String, Any?>, from: String = FROM, to: String = TO): SeasonResetReport {
    val db = FakeDb(
      seasonFields(seasonId = from, status = "ACTIVE", next = to),
      listOf(SeasonProfile("p1", fields.toMutableMap())),
    )
    return runSeasonReset(db, NOW, SeasonResetMode.DRY_RUN)
  }

  private fun Map<String, Any?>.without(vararg keys: String): MutableMap<String, Any?> =
    LinkedHashMap(this).apply { keys.forEach { remove(it) } }

  // ── configuration and the due clock ─────────────────────────────────

  @Test
  fun anAbsentSeasonDocumentIsNotConfiguredAndWritesNothing() = runTest {
    val db = FakeDb(
      season = null,
      profiles = listOf(SeasonProfile("p1", validFields())),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("NOT_CONFIGURED", report.decision)
    assertNull(report.fromSeasonId)
    assertNull(report.toSeasonId)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.profileWrites.size)
    assertEquals(0, db.readAllProfilesCalls)
  }

  @Test
  fun aSeasonDocumentWithoutAUsableIdentityOrStatusIsRefused() = runTest {
    val noSeasonId = FakeDb(seasonFields(seasonId = ABSENT))
    assertEquals("NOT_CONFIGURED", runSeasonReset(noSeasonId, NOW, SeasonResetMode.APPLY).decision)
    assertEquals(0, noSeasonId.seasonWrites.size)

    val blankSeasonId = FakeDb(seasonFields(seasonId = "   "))
    assertEquals("NOT_CONFIGURED", runSeasonReset(blankSeasonId, NOW, SeasonResetMode.APPLY).decision)

    val numericSeasonId = FakeDb(seasonFields(seasonId = 7))
    assertEquals("NOT_CONFIGURED", runSeasonReset(numericSeasonId, NOW, SeasonResetMode.APPLY).decision)

    for (status in listOf(ABSENT, "PAUSED", 3)) {
      val db = FakeDb(seasonFields(status = status))
      assertEquals("NOT_CONFIGURED", runSeasonReset(db, NOW, SeasonResetMode.APPLY).decision)
      assertEquals(0, db.seasonWrites.size)
      assertEquals(0, db.profileWrites.size)
    }
  }

  @Test
  fun aNonCompleteSeasonNeedsATargetButACompleteSeasonDoesNot() = runTest {
    val activeNoNext = FakeDb(seasonFields(next = ABSENT))
    val active = runSeasonReset(activeNoNext, NOW, SeasonResetMode.APPLY)
    assertEquals("NOT_CONFIGURED", active.decision)
    assertEquals(0, activeNoNext.seasonWrites.size)

    val completeNoNext = FakeDb(seasonFields(status = "COMPLETE", next = ABSENT))
    val complete = runSeasonReset(completeNoNext, NOW, SeasonResetMode.APPLY)
    assertEquals("ALREADY_COMPLETE", complete.decision)
    assertEquals(0, completeNoNext.seasonWrites.size)
    assertEquals(0, completeNoNext.readAllProfilesCalls)

    val completeWithNext = FakeDb(seasonFields(status = "COMPLETE", next = TO))
    assertEquals(
      "ALREADY_COMPLETE",
      runSeasonReset(completeWithNext, NOW, SeasonResetMode.APPLY).decision,
    )
    assertEquals(0, completeWithNext.seasonWrites.size)
  }

  @Test
  fun aSeasonWhoseDeadlineHasNotPassedIsNotDue() = runTest {
    val future = FakeDb(seasonFields(endsAt = NOW + 1))
    assertEquals("NOT_DUE", runSeasonReset(future, NOW, SeasonResetMode.APPLY).decision)
    assertEquals(0, future.seasonWrites.size)
    assertEquals(0, future.readAllProfilesCalls)

    val missing = FakeDb(seasonFields(endsAt = ABSENT))
    assertEquals("NOT_DUE", runSeasonReset(missing, NOW, SeasonResetMode.APPLY).decision)

    val malformed = FakeDb(seasonFields(endsAt = "2026-01-01"))
    assertEquals("NOT_DUE", runSeasonReset(malformed, NOW, SeasonResetMode.APPLY).decision)
  }

  @Test
  fun aDeadlineMovedBetweenTheReadsWinsAndNothingIsClaimed() = runTest {
    val db = FakeDb(seasonFields(endsAt = NOW - 1), listOf(SeasonProfile("p1", validFields())))
    db.beforeTransaction = { number -> if (number == 2) db.season!!["endsAt"] = NOW + 1 }

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("NOT_DUE", report.decision)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.profileWrites.size)
    assertEquals(0, db.readAllProfilesCalls)
  }

  @Test
  fun aTargetChangedBetweenTheReadsIsRejectedAndNeverClaimed() = runTest {
    val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", validFields())))
    db.beforeTransaction = { number -> if (number == 2) db.season!!["nextSeasonId"] = "s3" }

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.profileWrites.size)
    assertEquals(0, db.readAllProfilesCalls)
  }

  @Test
  fun aSeasonReplacedBetweenTheReadsIsRejected() = runTest {
    val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", validFields())))
    db.beforeTransaction = { number ->
      if (number == 2) db.season!!["seasonId"] = "s2"
    }

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.profileWrites.size)
  }

  // ── the claim, the reset and the next 90-day season ─────────────────

  @Test
  fun aDueActiveSeasonIsClaimedAppliedAndFinalised() = runTest {
    val db = FakeDb(
      seasonFields(),
      listOf(
        SeasonProfile("p1", validFields("Gold", "I", 825)),
        SeasonProfile("p2", validFields("Silver", "I", 450)),
      ),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("CLAIMED", report.decision)
    assertEquals(FROM, report.fromSeasonId)
    assertEquals(TO, report.toSeasonId)
    assertEquals(2, report.planned)
    assertEquals(2, report.applied)
    assertEquals(0, report.skippedAlreadyTarget)
    assertTrue(report.failures.isEmpty(), "unexpected failures: ${report.failures}")

    assertEquals(2, db.seasonWrites.size)
    val claim = db.seasonWrites[0]
    assertEquals(setOf("status", "targetSeasonId", "updatedAt"), claim.keys)
    assertEquals("RUNNING", claim["status"])
    assertEquals(TO, claim["targetSeasonId"])
    assertNotNull(claim["updatedAt"])

    val final = db.seasonWrites[1]
    assertEquals(TO, final["seasonId"])
    assertEquals("ACTIVE", final["status"])
    assertNull(final["targetSeasonId"])
    assertNull(final["nextSeasonId"])
    assertEquals(NOW + NINETY_DAYS, timestampToMillis(final["endsAt"]), "the next season runs 90 days")
    assertNotNull(final["startsAt"])
    assertNotNull(final["updatedAt"])

    val chunks = db.profileWrites
    assertEquals(1, chunks.size)
    assertEquals(listOf("p1", "p2"), chunks.single().map { it.first })
    assertEquals(TO, db.season?.get("seasonId"))
  }

  @Test
  fun aRunningResetTowardTheSameTargetResumesWithoutRewritingTheClaim() = runTest {
    val db = FakeDb(
      seasonFields(status = "RUNNING", target = TO),
      listOf(
        SeasonProfile("moving", validFields("Gold", "I", 825)),
        SeasonProfile("done", validFields("Gold", "III", 575, seasonId = TO)),
      ),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("RESUME", report.decision)
    assertEquals(1, report.planned)
    assertEquals(1, report.applied)
    assertEquals(1, report.skippedAlreadyTarget)
    assertEquals(1, db.seasonWrites.size, "a resume rewrites nothing before the final transition")
    assertEquals(TO, db.seasonWrites.single()["seasonId"])
    assertEquals(listOf("moving"), db.profileWrites.single().map { it.first })
  }

  @Test
  fun aCompleteSeasonIsATerminalNoOp() = runTest {
    val db = FakeDb(seasonFields(status = "COMPLETE"))

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("ALREADY_COMPLETE", report.decision)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.profileWrites.size)
    assertEquals(0, db.readAllProfilesCalls)
  }

  @Test
  fun aRunningResetTowardAnotherTargetWaits() = runTest {
    val db = FakeDb(seasonFields(status = "RUNNING", target = "s3"))

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("IN_PROGRESS_OTHER_SEASON", report.decision)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.readAllProfilesCalls)
  }

  @Test
  fun aSameSeasonTargetIsRejected() = runTest {
    val db = FakeDb(seasonFields(next = FROM))

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.readAllProfilesCalls)
  }

  @Test
  fun aReplayAfterTheResetIsANoOpAndNeverDemotesTwice() = runTest {
    val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", validFields())))
    val first = runSeasonReset(db, NOW, SeasonResetMode.APPLY)
    assertEquals("CLAIMED", first.decision)
    val writesAfterFirst = db.profileWrites.size
    val readsAfterFirst = db.readAllProfilesCalls

    val replay = runSeasonReset(db, NOW + 1, SeasonResetMode.APPLY)

    assertEquals("NOT_CONFIGURED", replay.decision)
    assertEquals(0, replay.planned)
    assertEquals(writesAfterFirst, db.profileWrites.size)
    assertEquals(readsAfterFirst, db.readAllProfilesCalls)
    assertNull(db.season?.get("nextSeasonId"))
  }

  // ── the R27 dry run ──────────────────────────────────────────────────

  @Test
  fun aDryRunPlansButWritesNothing() = runTest {
    val db = FakeDb(
      seasonFields(),
      listOf(
        SeasonProfile("p1", validFields("Gold", "I", 825)),
        SeasonProfile("p2", validFields("Silver", "II", 350)),
      ),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.DRY_RUN)

    assertEquals("WOULD_CLAIM", report.decision)
    assertEquals(2, report.planned)
    assertEquals(0, report.applied)
    assertEquals(0, report.skippedAlreadyTarget)
    assertTrue(report.failures.isEmpty(), "unexpected failures: ${report.failures}")
    assertEquals(0, db.seasonWrites.size, "a dry run writes no season document, including no claim")
    assertEquals(0, db.profileWrites.size, "a dry run writes no profile")
    assertEquals(1, db.readAllProfilesCalls)
  }

  @Test
  fun aDryRunOfARunningResetWouldResumeAndSkipTheMovedProfile() = runTest {
    val db = FakeDb(
      seasonFields(status = "RUNNING", target = TO),
      listOf(
        SeasonProfile("moving", validFields("Gold", "I", 825)),
        SeasonProfile("done", validFields("Gold", "III", 575, seasonId = TO)),
      ),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.DRY_RUN)

    assertEquals("WOULD_RESUME", report.decision)
    assertEquals(1, report.planned)
    assertEquals(1, report.skippedAlreadyTarget)
    assertEquals(0, report.applied)
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.profileWrites.size)
  }

  @Test
  fun aDryRunOfATerminalDecisionReadsNoProfiles() = runTest {
    val complete = FakeDb(seasonFields(status = "COMPLETE"))
    assertEquals("ALREADY_COMPLETE", runSeasonReset(complete, NOW, SeasonResetMode.DRY_RUN).decision)
    assertEquals(0, complete.readAllProfilesCalls)

    val otherTarget = FakeDb(seasonFields(status = "RUNNING", target = "s3"))
    assertEquals("IN_PROGRESS_OTHER_SEASON", runSeasonReset(otherTarget, NOW, SeasonResetMode.DRY_RUN).decision)
    assertEquals(0, otherTarget.readAllProfilesCalls)

    val sameId = FakeDb(seasonFields(next = FROM))
    assertEquals("REJECTED", runSeasonReset(sameId, NOW, SeasonResetMode.DRY_RUN).decision)
    assertEquals(0, sameId.readAllProfilesCalls)
  }

  @Test
  fun aDryRunReportsFailuresButNeverWrites() = runTest {
    val db = FakeDb(
      seasonFields(),
      listOf(SeasonProfile("bad", validFields("Mithril", "I", 0))),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.DRY_RUN)

    assertEquals("WOULD_CLAIM", report.decision)
    assertEquals(0, report.planned)
    assertEquals(listOf("bad"), report.failures.map { it.playerId })
    assertEquals(0, db.seasonWrites.size)
    assertEquals(0, db.profileWrites.size)
  }

  // ── the two irreversible directions (R27) ───────────────────────────

  @Test
  fun aPlanFailureOnAFreshClaimRevertsTheClaim() = runTest {
    val db = FakeDb(
      seasonFields(),
      listOf(SeasonProfile("bad", validFields("Mithril", "I", 0))),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(0, report.applied)
    assertEquals(listOf("bad"), report.failures.map { it.playerId })
    assertEquals(
      "the fresh claim of 's1' → 's2' cannot plan; it was reverted and no profile was written.",
      report.message,
    )
    assertEquals(2, db.seasonWrites.size, "the claim then its revert")
    assertEquals("RUNNING", db.seasonWrites[0]["status"])
    val revert = db.seasonWrites[1]
    assertEquals(setOf("status", "targetSeasonId", "updatedAt"), revert.keys)
    assertEquals("ACTIVE", revert["status"])
    assertNull(revert["targetSeasonId"])
    assertEquals("ACTIVE", db.season?.get("status"))
    assertNull(db.season?.get("targetSeasonId"))
    assertEquals(TO, db.season?.get("nextSeasonId"), "the revert keeps the configured target for a retry")
    assertEquals(0, db.profileWrites.size)
  }

  @Test
  fun aPlanFailureOnAResumeLeavesTheClaimForAnOperator() = runTest {
    val db = FakeDb(
      seasonFields(status = "RUNNING", target = TO),
      listOf(SeasonProfile("bad", validFields("Mithril", "I", 0))),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("PARTIAL", report.decision)
    assertEquals(0, report.applied)
    assertEquals(listOf("bad"), report.failures.map { it.playerId })
    assertEquals(0, db.seasonWrites.size, "a resume never un-claims a mixed state")
    assertEquals("RUNNING", db.season?.get("status"))
    assertEquals(TO, db.season?.get("targetSeasonId"))
    assertEquals(0, db.profileWrites.size)
  }

  @Test
  fun aBatchFailureAfterACommittedChunkLeavesTheSeasonResumable() = runTest {
    val db = FakeDb(
      seasonFields(),
      (0 until 401).map { SeasonProfile("p$it", validFields()) },
    )
    db.failProfileWriteNumber = 2

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("PARTIAL", report.decision)
    assertEquals(401, report.planned)
    assertEquals(400, report.applied, "only the committed chunk counts")
    assertEquals(1, report.failures.size)
    assertEquals(1, db.profileWrites.size)
    assertEquals(400, db.profileWrites.single().size)
    assertEquals(1, db.seasonWrites.size, "the failure must not finalise the season")
    assertEquals("RUNNING", db.season?.get("status"))
  }

  // ── profile classification ──────────────────────────────────────────

  @Test
  fun aProfileWithoutASeasonIsLegacyS1Only() = runTest {
    val legacy = linkedMapOf<String, Any?>("displayName" to "Old")

    val s1 = FakeDb(seasonFields(), listOf(SeasonProfile("p1", legacy)))
    val s1Report = runSeasonReset(s1, NOW, SeasonResetMode.DRY_RUN)
    assertEquals("WOULD_CLAIM", s1Report.decision)
    assertEquals(1, s1Report.planned)
    assertTrue(s1Report.failures.isEmpty(), "a legacy profile belongs to s1: ${s1Report.failures}")

    val s2 = FakeDb(
      seasonFields(seasonId = "s2", next = "s3"),
      listOf(SeasonProfile("p1", linkedMapOf("displayName" to "Old"))),
    )
    val s2Report = runSeasonReset(s2, NOW, SeasonResetMode.DRY_RUN)
    assertEquals(0, s2Report.planned)
    assertEquals(listOf("p1"), s2Report.failures.map { it.playerId })
  }

  @Test
  fun aForeignSeasonFailsAFreshClaim() = runTest {
    val db = FakeDb(
      seasonFields(),
      listOf(SeasonProfile("alien", validFields(seasonId = "s9"))),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(listOf("alien"), report.failures.map { it.playerId })
    assertEquals(2, db.seasonWrites.size, "the claim then its revert")
    assertEquals(0, db.profileWrites.size)
  }

  @Test
  fun aResumeSkipsProfilesAlreadyAtTheTargetAndNeverDemotesThemAgain() = runTest {
    val db = FakeDb(
      seasonFields(status = "RUNNING", target = TO),
      listOf(
        SeasonProfile("moving", validFields("Gold", "I", 825)),
        SeasonProfile("done", validFields("Gold", "III", 575, seasonId = TO)),
      ),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals(1, report.applied)
    assertEquals(1, report.skippedAlreadyTarget)
    val written = db.profileWrites.single()
    assertEquals(listOf("moving"), written.map { it.first })
    assertTrue(written.none { it.first == "done" }, "a profile already at the target is never rewritten")
  }

  @Test
  fun aBlankPlayerIdFailsThePlan() = runTest {
    val db = FakeDb(seasonFields(), listOf(SeasonProfile("", validFields())))

    val report = runSeasonReset(db, NOW, SeasonResetMode.DRY_RUN)

    assertEquals(0, report.planned)
    assertEquals(listOf(""), report.failures.map { it.playerId })
  }

  @Test
  fun anAbsentRungAndRpResolveToTheBottomRung() = runTest {
    val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", linkedMapOf())))

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertTrue(report.failures.isEmpty(), "unexpected failures: ${report.failures}")
    val write = db.profileWrites.single().single().second
    assertEquals("Bronze", write["tier"])
    assertEquals("III", write["division"])
    assertEquals(0, write["rp"])
    assertEquals(TO, write["seasonId"])
    assertEquals("Bronze", write["highestRankTier"])
    assertEquals("III", write["highestRankDivision"])
  }

  @Test
  fun anAbsentRpBesideAHigherRungIsAFailure() = runTest {
    val report = dryRun(validFields("Gold", "I", ABSENT))

    assertEquals(0, report.planned)
    assertEquals(listOf("p1"), report.failures.map { it.playerId })
  }

  @Test
  fun anUnusableRpIsAFailure() = runTest {
    for (rp in listOf("825", -1, 825.5, Double.NaN, 9_999_999_999.0)) {
      val report = dryRun(validFields("Gold", "I", rp))
      assertEquals(0, report.planned, "rp=$rp must not plan")
      assertEquals(listOf("p1"), report.failures.map { it.playerId }, "rp=$rp must fail")
    }
  }

  @Test
  fun tierAndDivisionMustBePresentTogether() = runTest {
    val onlyTier = dryRun(validFields("Gold", "I", 825).without("division"))
    assertEquals(listOf("p1"), onlyTier.failures.map { it.playerId })

    val onlyDivision = dryRun(validFields("Gold", "I", 825).without("tier"))
    assertEquals(listOf("p1"), onlyDivision.failures.map { it.playerId })
  }

  @Test
  fun anUnknownRungIsAFailure() = runTest {
    val report = dryRun(validFields("Gold", "IV", 825))

    assertEquals(0, report.planned)
    assertEquals(listOf("p1"), report.failures.map { it.playerId })
  }

  @Test
  fun theStoredHighestIsRaisedToTheCurrentRungAndNeverLowered() = runTest {
    val below = validFields("Gold", "I", 825).apply {
      put("highestRankTier", "Bronze")
      put("highestRankDivision", "III")
    }
    val above = validFields("Gold", "I", 825).apply {
      put("highestRankTier", "King")
      put("highestRankDivision", "")
    }
    val absent = validFields("Gold", "I", 825).without("highestRankTier", "highestRankDivision")
    val db = FakeDb(
      seasonFields(),
      listOf(
        SeasonProfile("raised", below),
        SeasonProfile("kept", above),
        SeasonProfile("defaulted", absent),
      ),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)
    assertTrue(report.failures.isEmpty(), "unexpected failures: ${report.failures}")

    val writes = db.profileWrites.single().associate { (playerId, fields) -> playerId to fields }
    assertEquals("Gold", writes.getValue("raised")["highestRankTier"])
    assertEquals("I", writes.getValue("raised")["highestRankDivision"])
    assertEquals("King", writes.getValue("kept")["highestRankTier"])
    assertEquals("", writes.getValue("kept")["highestRankDivision"])
    assertEquals("Gold", writes.getValue("defaulted")["highestRankTier"])
    assertEquals("I", writes.getValue("defaulted")["highestRankDivision"])
  }

  @Test
  fun exactlyOneHighestFieldOrAnUnknownHighestRungIsAFailure() = runTest {
    val oneField = validFields("Gold", "I", 825).without("highestRankDivision")
    assertEquals(listOf("p1"), dryRun(oneField).failures.map { it.playerId })

    val unknown = validFields("Gold", "I", 825).apply {
      put("highestRankTier", "Mithril")
      put("highestRankDivision", "I")
    }
    assertEquals(listOf("p1"), dryRun(unknown).failures.map { it.playerId })
  }

  @Test
  fun placementDefaultsAndItsNameIsValidated() = runTest {
    val fresh = dryRun(validFields("Bronze", "III", 0, placementState = ABSENT))
    assertEquals(1, fresh.planned)
    assertTrue(fresh.failures.isEmpty(), "an absent placement defaults: ${fresh.failures}")

    val played = dryRun(validFields("Gold", "I", 825, placementState = ABSENT))
    assertEquals(1, played.planned, "an absent placement with RP defaults to SKIPPED")

    val invalid = dryRun(validFields("Gold", "I", 825, placementState = "DONE"))
    assertEquals(0, invalid.planned)
    assertEquals(listOf("p1"), invalid.failures.map { it.playerId })
  }

  /**
   * The bulk parser inherits RankedProfileDb's legacy inference: a stored
   * `rank` beside no `rp` means the profile was placed in an earlier season,
   * so the reset must read it as SKIPPED. A divergence here would silently
   * rewrite placement for every legacy profile in the R27 migration.
   */
  @Test
  fun aLegacyRankWithoutRpImpliesThePlacementWasSkipped() = runTest {
    val ranked = parseStoredProfile(linkedMapOf<String, Any?>("rank" to "Gold"), FROM)
    val rankedProfile = (ranked as? ProfileParse.Parsed)?.profile
    assertNotNull(rankedProfile, "a legacy rank must parse: $ranked")
    assertEquals(PlacementState.SKIPPED, rankedProfile.placementState)

    val unranked = parseStoredProfile(linkedMapOf<String, Any?>("rank" to "Unranked"), FROM)
    val unrankedProfile = (unranked as? ProfileParse.Parsed)?.profile
    assertNotNull(unrankedProfile, "a legacy Unranked profile must parse: $unranked")
    assertEquals(PlacementState.NOT_STARTED, unrankedProfile.placementState)

    val explicit = parseStoredProfile(
      linkedMapOf<String, Any?>("rank" to "Gold", "placementState" to "NOT_STARTED"),
      FROM,
    )
    val explicitProfile = (explicit as? ProfileParse.Parsed)?.profile
    assertNotNull(explicitProfile, "an explicit placement must parse: $explicit")
    assertEquals(PlacementState.NOT_STARTED, explicitProfile.placementState)
  }

  @Test
  fun theShieldMustBeBooleanWhenPresent() = runTest {
    val absent = dryRun(validFields(shield = ABSENT))
    assertEquals(1, absent.planned)

    val invalid = dryRun(validFields(shield = "true"))
    assertEquals(0, invalid.planned)
    assertEquals(listOf("p1"), invalid.failures.map { it.playerId })
  }

  @Test
  fun aProgressionMismatchIsAFailureAndIsNeverRepaired() = runTest {
    // Silver I's floor is 450; rp 0 names Bronze III.
    val report = dryRun(validFields("Silver", "I", 0))

    assertEquals(0, report.planned)
    assertEquals(listOf("p1"), report.failures.map { it.playerId })
    assertTrue(report.failures.single().reason.isNotBlank())
  }

  @Test
  fun anUnusableStoredSeasonIsAFailure() = runTest {
    assertEquals(0, dryRun(validFields(seasonId = 7)).planned)
    assertEquals(0, dryRun(validFields(seasonId = "   ")).planned)
  }

  // ── the merge contract ──────────────────────────────────────────────

  @Test
  fun everyProfileWriteMergesExactlyTheSixProgressionFields() = runTest {
    val fields = validFields("Gold", "I", 825).apply {
      put("placementState", "COMPLETE")
      put("demotionShield", true)
      put("displayName", "Player")
      put("avatarInitial", "P")
      put("email", "p@estemshan.test")
      put("coins", 100)
      put("rank", "Gold")
      put("rankedGamesPlayed", 12)
      put("rankedKingCount", 1)
      put("rankedSecondCount", 2)
      put("rankedThirdCount", 3)
      put("rankedKozCount", 4)
      put("currentRoomId", "r-1")
    }
    val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", fields)))

    runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    val write = db.profileWrites.single().single().second
    assertEquals(
      setOf("tier", "division", "rp", "seasonId", "highestRankTier", "highestRankDivision"),
      write.keys,
      "the reset merges progression only — stats, placement, shield and display fields must never be written",
    )
    assertEquals("Gold", write["tier"])
    assertEquals("III", write["division"])
    assertEquals(575, write["rp"])
    assertEquals(TO, write["seasonId"])
    assertEquals("Gold", write["highestRankTier"])
    assertEquals("I", write["highestRankDivision"])
  }

  @Test
  fun profilesAreWrittenInChunksOfAtMostFourHundred() = runTest {
    val db401 = FakeDb(
      seasonFields(),
      (0 until 401).map { SeasonProfile("p$it", validFields()) },
    )
    val report401 = runSeasonReset(db401, NOW, SeasonResetMode.APPLY)
    assertEquals(401, report401.applied)
    assertEquals(listOf(400, 1), db401.profileWrites.map { it.size })

    val db501 = FakeDb(
      seasonFields(),
      (0 until 501).map { SeasonProfile("q$it", validFields()) },
    )
    val report501 = runSeasonReset(db501, NOW, SeasonResetMode.APPLY)
    assertEquals(501, report501.applied)
    assertEquals(listOf(400, 101), db501.profileWrites.map { it.size })
  }

  // ── the guards an operator's season state depends on ────────────────

  /**
   * The finalise may only open the next season on the exact claim this run
   * wrote. Every mutation models a writer moving the document between the claim
   * transaction and the finalise transaction: the run reports PARTIAL and
   * leaves the moved document alone.
   */
  @Test
  fun aSeasonThatMovesBeforeTheFinaliseIsReportedWithoutOpeningTheNextSeason() = runTest {
    val moves = listOf(
      Triple(
        "deleted",
        { db: FakeDb -> db.season = null },
        { db: FakeDb -> assertNull(db.season, "the deleted season must not be recreated") },
      ),
      Triple(
        "replaced",
        { db: FakeDb -> db.season = seasonFields(seasonId = "s9", status = "RUNNING", next = "s3") },
        { db: FakeDb -> assertEquals("s9", db.season?.get("seasonId")) },
      ),
      Triple(
        "completed",
        { db: FakeDb -> db.season!!["status"] = "COMPLETE" },
        { db: FakeDb -> assertEquals("COMPLETE", db.season?.get("status")) },
      ),
      Triple(
        "retargeted",
        { db: FakeDb -> db.season!!["targetSeasonId"] = "s3" },
        { db: FakeDb -> assertEquals("s3", db.season?.get("targetSeasonId")) },
      ),
    )

    for ((name, move, unchanged) in moves) {
      val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", validFields())))
      db.beforeTransaction = { number -> if (number == 3) move(db) }

      val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

      assertEquals("PARTIAL", report.decision, name)
      assertEquals(1, report.planned, name)
      assertEquals(1, report.applied, name)
      assertEquals(
        "could not finalise 's1' → 's2'; the season document moved during the run.",
        report.message,
        name,
      )
      assertEquals(1, db.profileWrites.size, name)
      assertEquals(
        1, db.seasonWrites.size,
        "only the claim may be written once the season moved underneath the run ($name)",
      )
      assertEquals("RUNNING", db.seasonWrites.single()["status"], name)
      unchanged(db)
    }
  }

  /** The claim transaction never trusts the gating read: the document can move in between. */
  @Test
  fun aClaimThatIsNoLongerTheReadStateIsTerminalAndWritesNothing() = runTest {
    val moves = listOf(
      Triple(
        "disappeared",
        { db: FakeDb -> db.season = null },
        "NOT_CONFIGURED" to "seasons/current disappeared before the claim; nothing was written.",
      ),
      Triple(
        "completed without landing on the target",
        { db: FakeDb -> db.season!!["status"] = "COMPLETE" },
        "REJECTED" to "season 's1' completed before the claim; nothing was written.",
      ),
    )

    for ((name, move, expected) in moves) {
      val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", validFields())))
      db.beforeTransaction = { number -> if (number == 2) move(db) }

      val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

      assertEquals(expected.first, report.decision, name)
      assertEquals(expected.second, report.message, name)
      assertEquals(0, db.seasonWrites.size, name)
      assertEquals(0, db.profileWrites.size, name)
    }
  }

  @Test
  fun aFreshClaimWithAProfileAlreadyAtTheTargetFailsClosed() = runTest {
    val db = FakeDb(
      seasonFields(),
      listOf(SeasonProfile("early", validFields(seasonId = TO))),
    )

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(0, report.applied)
    assertEquals(listOf("early"), report.failures.map { it.playerId })
    assertEquals(2, db.seasonWrites.size, "the claim then its revert")
    assertEquals("ACTIVE", db.season?.get("status"))
    assertEquals(0, db.profileWrites.size)
  }

  /**
   * The gating read is not authoritative. If it saw RUNNING but the claim
   * transaction finds ACTIVE (the claim was reverted in between), this run
   * performs a FRESH claim, so a profile already at the target is a failure —
   * never a resume skip.
   */
  @Test
  fun aClaimThatFallsBackToActiveIsFreshAndRejectsAProfileAlreadyAtTheTarget() = runTest {
    val db = FakeDb(
      seasonFields(status = "RUNNING", target = TO),
      listOf(SeasonProfile("early", validFields(seasonId = TO))),
    )
    db.beforeTransaction = { number ->
      if (number == 2) {
        db.season!!["status"] = "ACTIVE"
        db.season!!["targetSeasonId"] = null
      }
    }

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(listOf("early"), report.failures.map { it.playerId })
    assertEquals(0, report.applied)
    assertEquals(2, db.seasonWrites.size, "the fresh claim then its revert")
    assertEquals(0, db.profileWrites.size)
  }

  /**
   * The revert is preconditioned on the claim it wrote. A second writer taking
   * the claim over must leave it running, and the report must not tell an
   * operator that a revert happened when the guard refused.
   */
  @Test
  fun aRevertThatCannotSeeItsOwnClaimIsLeftAloneAndIsNotReportedAsReverted() = runTest {
    val db = FakeDb(
      seasonFields(),
      listOf(SeasonProfile("bad", validFields("Mithril", "I", 0))),
    )
    db.beforeTransaction = { number -> if (number == 3) db.season!!["targetSeasonId"] = "s3" }

    val report = runSeasonReset(db, NOW, SeasonResetMode.APPLY)

    assertEquals("REJECTED", report.decision)
    assertEquals(listOf("bad"), report.failures.map { it.playerId })
    assertEquals(1, db.seasonWrites.size, "the revert must not clear a claim it does not own")
    assertEquals("RUNNING", db.season?.get("status"))
    assertEquals("s3", db.season?.get("targetSeasonId"))
    assertEquals(
      "the fresh claim of 's1' → 's2' cannot plan and the claim moved on; " +
        "it was left as found and no profile was written.",
      report.message,
    )
  }

  @Test
  fun aProfileReadThatNeverResolvesIsNeverReportedAsASuccess() = runTest {
    val db = FakeDb(seasonFields(), listOf(SeasonProfile("p1", validFields())))
    db.failProfileRead = true

    val failure = try {
      runSeasonReset(db, NOW, SeasonResetMode.APPLY)
      null
    } catch (thrown: Throwable) {
      thrown
    }

    assertNotNull(failure, "a profile read that never resolved must surface as a throw")
    assertEquals(1, db.seasonWrites.size, "the claim is already written and is not silently rolled back")
    assertEquals("RUNNING", db.season?.get("status"))
    assertEquals(TO, db.season?.get("targetSeasonId"))
  }
}
