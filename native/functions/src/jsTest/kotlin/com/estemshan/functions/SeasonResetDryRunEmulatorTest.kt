package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.rankLadder
import com.estemshan.engine.seasonResetRank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S59 — the season reset against a REAL Firestore emulator (R27).
 *
 * [SeasonResetRunTest] proves the orchestrator's decisions through a fake
 * [SeasonResetDb]. It cannot prove the parts that only exist at the SDK
 * boundary, and those are exactly the parts an irreversible migration must not
 * get wrong:
 *
 * - `seasons/current` read and written through the Admin SDK, with
 *   `FieldValue.serverTimestamp()` and `Timestamp.fromMillis` surviving a real
 *   commit;
 * - the claim → `RUNNING` → finalise transition, re-read in transactions;
 * - `players` read as ONE unfiltered, capped query and written as field-level
 *   batch updates that leave career statistics untouched;
 * - a resume that skips the profiles already at the target instead of demoting
 *   them a second time.
 *
 * The R27 rehearsal is test 1: a full dry run over a 250-profile,
 * production-shaped population must plan every profile and write NOTHING —
 * neither the season document nor a single player. Tests 2 and 3 then execute
 * the real migration and its crash recovery.
 *
 * SKIPS ITSELF (loudly) unless FIRESTORE_EMULATOR_HOST is set, and the marker
 * ends in `_EMULATOR_SKIPPED` so CI's report scan fails closed rather than
 * reading a skipped tier as a pass. To run it, with the already-cached jar:
 *   java -jar ~/.cache/firebase/emulators/cloud-firestore-emulator-*.jar \
 *     --host 127.0.0.1 --port 8991 --project_id s59-verify
 *   FIRESTORE_EMULATOR_HOST=127.0.0.1:8991 GCLOUD_PROJECT=s59-verify \
 *     ./native/gradlew.bat -p native :functions:jsNodeTest
 *
 * Every season and player id carries an "s59e-<nonce>" tag, so a failed cleanup
 * can never silently turn a create into an update. Emulator suites share ONE
 * database, and the reset reads the WHOLE `players` collection and fails closed
 * on any profile whose season is not the one being reset. This suite therefore
 * OWNS that collection: [clearPlayers] deletes every player before each test,
 * and [cleanSharedPlayers] deletes them again after each test — including a
 * failed one — so neither a previous test nor a later suite can contaminate a
 * reset.
 */
class SeasonResetDryRunEmulatorTest {

  private companion object {
    const val DAY: Long = 24L * 60 * 60 * 1000
    const val NINETY_DAYS: Long = 90L * DAY
    val PLACEMENTS = listOf("SKIPPED", "COMPLETE", "IN_PROGRESS")
  }

  // Epoch millis as a String: a Double's toString would put a "." in the doc id.
  private val nonce: String = kotlin.js.Date.now().toLong().toString()

  private fun season(tag: String): String = "s59e-$nonce-$tag"

  private fun player(tag: String): String = "s59e-$nonce-$tag"

  /** The emulator host, without logging; [emulatorReady] owns the skip marker. */
  private fun emulatorHost(): String? =
    (js("process.env.FIRESTORE_EMULATOR_HOST") as? String)?.takeIf { it.isNotEmpty() }

  private fun emulatorReady(): Boolean {
    val host = emulatorHost()
    if (host == null) console.log("S59_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")
    return host != null
  }

  private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * Seeded through the same guard every Admin DB uses: firebase-admin never
   * creates the default app itself, so a bare `getFirestore()` would reach the
   * emulator never and throw before opening a socket.
   */
  private val db: FirestoreDb by lazy {
    ensureDefaultAdminApp()
    getFirestore()
  }

  private fun playerRef(id: String): DocRef = db.collection("players").doc(id)

  private fun seasonRef(): DocRef = db.collection("seasons").doc("current")

  /** Written inside a transaction: this interop declares `set` on Transaction only. */
  private suspend fun seedDoc(ref: DocRef, fields: Map<String, Any?>) {
    db.runTransaction { tx -> seedScope.promise { tx.set(ref, toPlain(fields)) } }.await()
  }

  private suspend fun seedSeason(fields: Map<String, Any?>) = seedDoc(seasonRef(), fields)

  /** One transaction per up-to-400 documents: transaction writes cap at 500. */
  private suspend fun seedProfiles(profiles: List<Pair<String, Map<String, Any?>>>) {
    for (chunk in profiles.chunked(400)) {
      db.runTransaction { tx ->
        seedScope.promise {
          for ((id, fields) in chunk) tx.set(playerRef(id), toPlain(fields))
        }
      }.await()
    }
  }

  /**
   * Deletes EVERY player, not just this run's own: the reset reads the whole
   * collection and fails closed on a foreign season, so a leftover profile
   * would block the plan. Runs before each test and again in
   * [cleanSharedPlayers] after it.
   */
  private suspend fun clearPlayers() {
    val docs = db.collection("players").get().await().docs.toList()
    for (chunk in docs.chunked(400)) {
      db.runTransaction { tx ->
        seedScope.promise {
          for (doc in chunk) tx.delete(playerRef(doc.id))
        }
      }.await()
    }
  }

  /**
   * Leaves the shared emulator clean even when an assertion fails, so the next
   * S59 test (or a later suite) starts from an empty players collection. The
   * quiet host check keeps a hermetic run marker-free here.
   */
  @AfterTest
  fun cleanSharedPlayers() = runTest {
    if (emulatorHost() == null) return@runTest
    clearPlayers()
  }

  private suspend fun readSeasonDoc(): Map<String, Any?>? {
    val snapshot = seasonRef().get().await()
    if (!snapshot.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snapshot.data()) as? Map<String, Any?>
  }

  private suspend fun readProfiles(): Map<String, Map<String, Any?>> =
    db.collection("players").get().await().docs.associate { doc ->
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: emptyMap()
      doc.id to fields
    }

  /**
   * The WHOLE season document, with every timestamp flattened to millis: two
   * separate reads return distinct Timestamp INSTANCES, and reference equality
   * would make an unchanged document look changed. Nothing is projected away,
   * so a stray added field fails the "wrote nothing" assertions.
   */
  private suspend fun seasonSnapshot(): Map<String, Any?>? =
    readSeasonDoc()?.mapValues { (_, value) -> canonical(value) }

  private fun canonical(value: Any?): Any? = timestampToMillis(value) ?: value

  // ── fixtures ─────────────────────────────────────────────────────────

  private fun seasonFields(
    from: String,
    to: String,
    status: String,
    target: String?,
    endsAt: Long,
  ): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    put("seasonId", from)
    put("status", status)
    if (target != null) put("targetSeasonId", target)
    put("nextSeasonId", to)
    put("startsAt", Timestamp.fromMillis((endsAt - NINETY_DAYS).toDouble()))
    put("endsAt", Timestamp.fromMillis(endsAt.toDouble()))
    put("updatedAt", endsAt)
  }

  private fun activeSeason(from: String, to: String, endsAt: Long): Map<String, Any?> =
    seasonFields(from, to, "ACTIVE", null, endsAt)

  /**
   * A production-shaped `players/{uid}`: the model's progression block beside
   * the display fields and the career statistics the reset must never write.
   */
  private fun rungFields(
    from: String,
    rung: LadderRank,
    index: Int,
    highest: LadderRank = rung,
  ): Map<String, Any?> = linkedMapOf(
    "seasonId" to from,
    "tier" to rung.tier,
    "division" to rung.division,
    "rp" to rung.lowerBound,
    "highestRankTier" to highest.tier,
    "highestRankDivision" to highest.division,
    "placementState" to PLACEMENTS[index % PLACEMENTS.size],
    "demotionShield" to (index % 3 == 0),
    "displayName" to "Player $index",
    "avatarInitial" to "P",
    "email" to "p$index@estemshan.test",
    "coins" to 1_000 + index,
    "currentRoomId" to "room-$index",
    // The career counters (RD23): present on purpose, so a reset that replaced
    // the document instead of merging would wipe them and fail a test.
    "rankedGamesPlayed" to index,
    "rankedKingCount" to index % 5,
    "rankedSecondCount" to index % 7,
    "rankedThirdCount" to index % 3,
    "rankedKozCount" to index % 2,
  )

  /**
   * The legacy shape: a season id but no progression block at all. The reset
   * must read it as Bronze III / 0 RP, because that is how the model reads a
   * profile that predates it.
   */
  private fun legacyFields(from: String, index: Int): Map<String, Any?> = linkedMapOf(
    "seasonId" to from,
    "displayName" to "Legacy $index",
    "avatarInitial" to "L",
    "email" to "legacy$index@estemshan.test",
    "rankedGamesPlayed" to index,
  )

  private fun rungs(): List<LadderRank> = rankLadder

  private fun rung(tier: String, division: String): LadderRank =
    rankLadder.first { it.tier == tier && it.division == division }

  private data class ExpectedRung(val tier: String, val division: String, val rp: Int)

  /**
   * The reset rule written out literally — two rungs down, floored at Bronze
   * III, King → Royal I. Deliberately NOT [seasonResetRank]: a test that asks
   * the implementation for its expected value cannot catch a wrong mapping.
   */
  private val expectedAfterReset: Map<LadderRank, ExpectedRung> = mapOf(
    rung("Bronze", "III") to ExpectedRung("Bronze", "III", 0),
    rung("Bronze", "II") to ExpectedRung("Bronze", "III", 0),
    rung("Bronze", "I") to ExpectedRung("Bronze", "III", 0),
    rung("Silver", "III") to ExpectedRung("Bronze", "II", 75),
    rung("Silver", "II") to ExpectedRung("Bronze", "I", 150),
    rung("Silver", "I") to ExpectedRung("Silver", "III", 250),
    rung("Gold", "III") to ExpectedRung("Silver", "II", 350),
    rung("Gold", "II") to ExpectedRung("Silver", "I", 450),
    rung("Gold", "I") to ExpectedRung("Gold", "III", 575),
    rung("Platinum", "III") to ExpectedRung("Gold", "II", 700),
    rung("Platinum", "II") to ExpectedRung("Gold", "I", 825),
    rung("Platinum", "I") to ExpectedRung("Platinum", "III", 1000),
    rung("Diamond", "III") to ExpectedRung("Platinum", "II", 1200),
    rung("Diamond", "II") to ExpectedRung("Platinum", "I", 1400),
    rung("Diamond", "I") to ExpectedRung("Diamond", "III", 1600),
    rung("Royal", "III") to ExpectedRung("Diamond", "II", 1800),
    rung("Royal", "II") to ExpectedRung("Diamond", "I", 2000),
    rung("Royal", "I") to ExpectedRung("Royal", "III", 2250),
    rung("King", "") to ExpectedRung("Royal", "I", 2750),
  )

  // ── the R27 rehearsal ────────────────────────────────────────────────

  @Test
  fun aDryRunPlansEveryProfileAndWritesNeitherASeasonNorAProfile() = runTest {
    if (!emulatorReady()) return@runTest
    val from = season("dry-from")
    val to = season("dry-to")
    val now = kotlin.js.Date.now().toLong()

    clearPlayers()
    seedSeason(activeSeason(from, to, now - 1_000))

    // 244 rung profiles (every one of the 19 rungs, 12 times over plus change)
    // and 6 legacy profiles: exactly the 250 the R27 rehearsal reports.
    val rungProfiles = (0 until 244).map { i ->
      val rung = rungs()[i % rungs().size]
      // A mixed Highest: sometimes the current rung, sometimes above it.
      val highest = rungs()[(rungs().indexOf(rung) + if (i % 4 == 0) 3 else 0)
        .coerceAtMost(rungs().lastIndex)]
      player("dry-rung-$i") to rungFields(from, rung, i, highest)
    }
    val legacyProfiles = (0 until 6).map { i ->
      player("dry-legacy-$i") to legacyFields(from, i)
    }
    assertEquals(
      rungs().size,
      (0 until 244).map { rungs()[it % rungs().size] }.distinct().size,
      "the fixture must cover every ladder rung",
    )
    seedProfiles(rungProfiles + legacyProfiles)

    val seasonBefore = seasonSnapshot()
    val profilesBefore = readProfiles()

    val report = runSeasonReset(AdminSeasonResetDb(), now, SeasonResetMode.DRY_RUN)

    assertEquals("WOULD_CLAIM", report.decision)
    assertEquals(250, report.planned)
    assertEquals(0, report.applied)
    assertEquals(0, report.skippedAlreadyTarget)
    assertTrue(report.failures.isEmpty(), "unexpected failures: ${report.failures}")

    assertEquals(seasonBefore, seasonSnapshot(), "a dry run must not touch the season document")
    assertEquals(profilesBefore, readProfiles(), "a dry run must not touch a single profile")
    console.log("S59_EMULATOR_DRY_RUN planned=${report.planned} season=$from")
  }

  // ── the real migration ───────────────────────────────────────────────

  @Test
  fun anApplyOverFiveHundredProfilesMovesEveryRungAndKeepsCareerData() = runTest {
    if (!emulatorReady()) return@runTest
    val from = season("apply-from")
    val to = season("apply-to")
    val now = kotlin.js.Date.now().toLong()

    clearPlayers()
    seedSeason(activeSeason(from, to, now - 1_000))
    val seeded = (0 until 501).map { i -> player("apply-$i") to rungs()[i % rungs().size] }
    seedProfiles(seeded.mapIndexed { i, (id, rung) -> id to rungFields(from, rung, i) })

    val report = runSeasonReset(AdminSeasonResetDb(), now, SeasonResetMode.APPLY)

    assertEquals("CLAIMED", report.decision)
    assertEquals(501, report.planned)
    assertEquals(501, report.applied)
    assertEquals(0, report.skippedAlreadyTarget)
    assertTrue(report.failures.isEmpty(), "unexpected failures: ${report.failures}")
    assertEquals(
      rungs().toSet(),
      expectedAfterReset.keys,
      "the literal table must cover every ladder rung",
    )

    val after = readProfiles()
    seeded.forEachIndexed { i, (id, rung) ->
      val fields = after.getValue(id)
      val expected = expectedAfterReset.getValue(rung)
      assertEquals(expected.tier, fields["tier"], "$id tier")
      assertEquals(expected.division, fields["division"], "$id division")
      assertEquals(expected.rp, (fields["rp"] as Number).toInt(), "$id rp")
      assertEquals(to, fields["seasonId"], "$id season")
      // Everything the six-field merge must leave alone.
      assertEquals("Player $i", fields["displayName"], "$id display")
      assertEquals("p$i@estemshan.test", fields["email"], "$id email")
      assertEquals(1_000 + i, (fields["coins"] as Number).toInt(), "$id coins")
      assertEquals(i, (fields["rankedGamesPlayed"] as Number).toInt(), "$id ranked games")
      assertEquals(i % 5, (fields["rankedKingCount"] as Number).toInt(), "$id ranked kings")
      assertEquals(i % 7, (fields["rankedSecondCount"] as Number).toInt(), "$id ranked seconds")
      assertEquals(i % 3, (fields["rankedThirdCount"] as Number).toInt(), "$id ranked thirds")
      assertEquals(i % 2, (fields["rankedKozCount"] as Number).toInt(), "$id ranked koz")
      assertEquals(PLACEMENTS[i % PLACEMENTS.size], fields["placementState"], "$id placement")
      assertEquals(i % 3 == 0, fields["demotionShield"], "$id shield")
      assertEquals("room-$i", fields["currentRoomId"], "$id room")
      // Highest was already the pre-reset rung; a reset never lowers it.
      assertEquals(rung.tier, fields["highestRankTier"], "$id highest tier")
      assertEquals(rung.division, fields["highestRankDivision"], "$id highest division")
    }

    val doc = readSeasonDoc() ?: throw AssertionError("seasons/current must exist")
    assertEquals(to, doc["seasonId"])
    assertEquals("ACTIVE", doc["status"])
    assertNull(doc["targetSeasonId"])
    assertNull(doc["nextSeasonId"])
    assertEquals(now + NINETY_DAYS, timestampToMillis(doc["endsAt"]), "the next season runs 90 days")
    assertNotNull(timestampToMillis(doc["startsAt"]))
    console.log("S59_EMULATOR_APPLY applied=${report.applied} season=$to")
  }

  @Test
  fun aCrashedResetResumesAndOnlyMovesTheProfilesStillBehind() = runTest {
    if (!emulatorReady()) return@runTest
    val from = season("resume-from")
    val to = season("resume-to")
    val now = kotlin.js.Date.now().toLong()

    clearPlayers()
    seedSeason(activeSeason(from, to, now - 1_000))
    val seeded = (0 until 501).map { i -> player("resume-$i") to rungs()[i % rungs().size] }
    val originalFields = seeded.mapIndexed { i, (id, rung) -> id to rungFields(from, rung, i) }
    seedProfiles(originalFields)

    val first = runSeasonReset(AdminSeasonResetDb(), now, SeasonResetMode.APPLY)
    assertEquals("CLAIMED", first.decision)
    assertEquals(501, first.applied)

    // Crash simulation: three profiles were not migrated, and the season
    // document was left claimed toward the same target.
    val restored = originalFields.take(3)
    for ((id, fields) in restored) seedDoc(playerRef(id), fields)
    val restoredIds = restored.map { it.first }.toSet()
    seedSeason(seasonFields(from, to, "RUNNING", to, now - 1_000))

    val untouchedBefore = readProfiles().filterKeys { it !in restoredIds }

    val second = runSeasonReset(AdminSeasonResetDb(), now + 1, SeasonResetMode.APPLY)

    assertEquals("RESUME", second.decision)
    assertEquals(3, second.planned)
    assertEquals(3, second.applied)
    assertEquals(498, second.skippedAlreadyTarget)
    assertTrue(second.failures.isEmpty(), "unexpected failures: ${second.failures}")

    val after = readProfiles()
    assertEquals(
      untouchedBefore,
      after.filterKeys { it !in restoredIds },
      "a resume must not rewrite a profile that already reached the target",
    )
    restored.forEachIndexed { i, pair ->
      val id = pair.first
      val landed = seasonResetRank(seeded[i].second)
      val fields = after.getValue(id)
      assertEquals(landed.tier, fields["tier"], "$id tier after resume")
      assertEquals(landed.division, fields["division"], "$id division after resume")
      assertEquals(landed.lowerBound, (fields["rp"] as Number).toInt(), "$id rp after resume")
      assertEquals(to, fields["seasonId"], "$id season after resume")
    }

    val doc = readSeasonDoc() ?: throw AssertionError("seasons/current must exist")
    assertEquals(to, doc["seasonId"])
    assertEquals("ACTIVE", doc["status"])
    assertNull(doc["targetSeasonId"])
    console.log("S59_EMULATOR_RESUME applied=${second.applied} skipped=${second.skippedAlreadyTarget}")
  }

  /**
   * A wrapper that lets this test interleave one real write between the reset's
   * population read and its profile batch. It is deterministic, unlike racing
   * two schedulers, and it exercises the exact window the `lastUpdateTime`
   * precondition exists to close.
   */
  private class InterleavedSeasonResetDb(
    private val delegate: SeasonResetDb,
    private val afterRead: suspend () -> Unit,
  ) : SeasonResetDb {
    override suspend fun <T> transact(block: suspend (SeasonResetDb.Tx) -> T): T =
      delegate.transact(block)

    override suspend fun readAllProfiles(): List<SeasonProfile> {
      val profiles = delegate.readAllProfiles()
      afterRead()
      return profiles
    }

    override suspend fun writeProfiles(updates: List<ProfileWrite>) =
      delegate.writeProfiles(updates)

    override suspend fun readGateConfig(): SeasonResetGateConfig? = delegate.readGateConfig()
  }

  @Test
  fun aConcurrentProfileWriteBetweenReadAndBatchIsNeverOverwritten() = runTest {
    if (!emulatorReady()) return@runTest
    val from = season("guard-from")
    val to = season("guard-to")
    val now = kotlin.js.Date.now().toLong()
    val racer = player("guard-race")

    clearPlayers()
    seedSeason(activeSeason(from, to, now - 1_000))
    seedProfiles(listOf(racer to rungFields(from, rung("Gold", "I"), 0)))

    // A settlement commits after the reset has read the population but before
    // its batch. The reset's plan still says Gold I / 575 RP; the settlement
    // writes 999 RP. Without the precondition the batch would overwrite 999.
    var injected = false
    val racingDb = InterleavedSeasonResetDb(AdminSeasonResetDb()) {
      if (!injected) {
        injected = true
        seedDoc(
          playerRef(racer),
          LinkedHashMap(rungFields(from, rung("Gold", "I"), 0)).apply { put("rp", 999) },
        )
      }
    }

    val report = runSeasonReset(racingDb, now, SeasonResetMode.APPLY)

    assertEquals("PARTIAL", report.decision)
    assertEquals(1, report.planned)
    assertEquals(0, report.applied, "the contested batch must commit zero profiles")
    assertTrue(report.failures.isNotEmpty(), "the operator must see the failed batch")

    val after = readProfiles().getValue(racer)
    assertEquals(999, (after["rp"] as Number).toInt(), "the concurrent write must survive")
    assertEquals(from, after["seasonId"], "the reset must not half-apply the contested profile")

    val seasonDoc = readSeasonDoc() ?: throw AssertionError("seasons/current must exist")
    assertEquals(from, seasonDoc["seasonId"], "the source season must remain current")
    assertEquals("RUNNING", seasonDoc["status"], "the claim must remain for a safe resume")
    assertEquals(to, seasonDoc["targetSeasonId"])

    console.log("S59_EMULATOR_LOST_UPDATE_GUARD decision=${report.decision} applied=${report.applied}")
  }

  @Test
  fun theKillSwitchConfigReadsThroughTheAdminPort() = runTest {
    if (!emulatorReady()) return@runTest
    val gateRef = db.collection("config").doc("seasonReset")
    seedDoc(
      gateRef,
      mapOf(
        "enabled" to true,
        "targetSeasonId" to "s2",
        "environment" to "staging",
      ),
    )
    try {
      val config = assertNotNull(AdminSeasonResetDb().readGateConfig())
      assertEquals(true, config.enabled)
      assertEquals("s2", config.targetSeasonId)
      assertEquals("staging", config.environment)
      console.log("S59_EMULATOR_GATE enabled=${config.enabled} target=${config.targetSeasonId}")
    } finally {
      db.runTransaction { tx -> seedScope.promise { tx.delete(gateRef) } }.await()
    }
  }
}
