package com.estemshan.functions

import com.estemshan.engine.LadderRank
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.applySeasonReset
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S59 / E5.3 — the reset/settlement race, on ONE shared, versioned store.
 *
 * [SeasonResetRunTest] pins the orchestrator against a fake that RECORDS writes.
 * Recording cannot prove a lost-update property: a fake that ignores
 * `expectedUpdateTime` accepts a stale plan and reports success. This harness
 * therefore ENFORCES the version contract both actors rely on:
 *
 * - the reset's profile batch is all-or-nothing and every write carries the
 *   exact version [SeasonResetDb.readAllProfiles] returned;
 * - a settlement ([ProgressionWriter]) reads a profile and writes it back at
 *   that version, exactly like the settlement transaction's read-then-write;
 * - a stale write from either actor throws and changes nothing, which is what
 *   makes the retry path meaningful.
 *
 * The interleaving is exact and deterministic: no `Thread.sleep`, no `delay`,
 * no real threads. The only scheduling points are [CompletableDeferred] gates
 * inside the port, so the same test always produces the same order.
 *
 * Modelled, not executed: the real Firestore transaction retry and the Admin
 * SDK's own `lastUpdateTimePrecondition` behaviour. The port enforces the same
 * CONTRACT (validate every version, then apply atomically); the wire-level
 * behaviour is covered by [SeasonResetDryRunEmulatorTest], which self-skips
 * without an emulator. The reset's own arithmetic is never reimplemented here:
 * assertions call [applySeasonReset] and [parseStoredProfile] directly.
 */
class SeasonResetRaceTest {

  private companion object {
    const val NOW: Long = 1_800_000_000_000
    const val DAY: Long = 24L * 60 * 60 * 1000
    const val FROM: String = "s1"
    const val TO: String = "s2"

    /** Gold I's lower bound; +30 crosses into Platinum III (1000). */
    const val GOLD_I_RP: Int = 985

    /** A rung two steps below Gold I, where a plain reset lands. */
    const val GOLD_III_RP: Int = 575
  }

  // ── the shared, versioned store ───────────────────────────────────────

  private class StoredProfile(
    val fields: MutableMap<String, Any?>,
    var version: Long,
  )

  /**
   * The one store both actors see. [seasonVersion] is bumped on every season
   * write; profile versions are bumped on every committed profile write.
   */
  private class RaceStore {
    var season: MutableMap<String, Any?>? = null
    var seasonVersion: Long = 0
    val profiles: MutableMap<String, StoredProfile> = linkedMapOf()

    fun profile(uid: String): StoredProfile = profiles.getValue(uid)

    fun seedSeason(fields: MutableMap<String, Any?>) {
      season = fields
      seasonVersion = 0
    }

    fun seedProfile(uid: String, fields: MutableMap<String, Any?>, version: Long = 1L) {
      profiles[uid] = StoredProfile(fields, version)
    }
  }

  /**
   * [SeasonResetDb] over [store]. Transactions are serialized by the test's
   * single coroutine — every suspension point is an explicit gate — so no lock
   * is needed and none is simulated.
   */
  private class ResetPort(private val store: RaceStore) : SeasonResetDb {
    /** Called before transaction number [n] (1-based) runs. */
    var beforeTransaction: (suspend (Int) -> Unit)? = null

    /** Called at the top of every profile batch, BEFORE any version check. */
    var beforeWriteProfiles: (suspend (List<ProfileWrite>) -> Unit)? = null

    /** Called after a profile batch commits, with its 1-based number. */
    var afterWriteProfiles: (suspend (Int) -> Unit)? = null

    var transactions: Int = 0
      private set
    var profileReads: Int = 0
      private set
    var profileBatches: Int = 0
      private set
    var profilesWritten: Int = 0
      private set

    val seasonWrites: MutableList<Map<String, Any?>> = mutableListOf()
    val writeVersions: MutableList<Pair<String, Any?>> = mutableListOf()
    var lastReadVersions: Map<String, Any?> = emptyMap()
      private set

    override suspend fun <T> transact(block: suspend (SeasonResetDb.Tx) -> T): T {
      transactions++
      beforeTransaction?.invoke(transactions)
      val tx = object : SeasonResetDb.Tx {
        override suspend fun readSeason(): Map<String, Any?>? =
          store.season?.let { LinkedHashMap(it) }

        override fun writeSeason(fields: Map<String, Any?>) {
          // update(): a field-level merge, never a document replace. The job
          // never creates the document, so an absent season stays absent.
          val season = store.season ?: return
          season.putAll(fields)
          store.seasonVersion++
          seasonWrites += LinkedHashMap(fields)
        }
      }
      return block(tx)
    }

    override suspend fun readAllProfiles(): List<SeasonProfile> {
      profileReads++
      lastReadVersions = store.profiles.mapValues { it.value.version }
      return store.profiles.map { (uid, stored) ->
        SeasonProfile(uid, LinkedHashMap(stored.fields), stored.version)
      }
    }

    override suspend fun writeProfiles(updates: List<ProfileWrite>) {
      beforeWriteProfiles?.invoke(updates)
      // The Admin batch builds EVERY write before it commits: one stale or
      // missing version aborts the whole batch and applies nothing. Validate
      // first, mutate second — never per-document best effort.
      val targets = updates.map { update ->
        val expected = update.expectedUpdateTime as? Long
          ?: throw IllegalStateException(
            "profile '${update.playerId}' has no read version; refusing an unguarded write",
          )
        val stored = store.profiles[update.playerId]
          ?: throw IllegalStateException("profile '${update.playerId}' disappeared")
        if (stored.version != expected) {
          throw IllegalStateException(
            "stale write for '${update.playerId}': planned at v$expected, " +
              "the store is at v${stored.version}",
          )
        }
        stored
      }
      for ((index, update) in updates.withIndex()) {
        targets[index].fields.putAll(update.fields)
        targets[index].version++
      }
      profileBatches++
      profilesWritten += updates.size
      writeVersions += updates.map { it.playerId to it.expectedUpdateTime }
      afterWriteProfiles?.invoke(profileBatches)
    }

    override suspend fun readGateConfig(): SeasonResetGateConfig? = null
  }

  /**
   * The settlement's WRITE, not its arithmetic: read the season and the
   * profile, apply the delta at the read version, and recompute the rung from
   * RP the way the settlement path does. A stale version throws — the
   * settlement transaction would retry on a fresh read.
   */
  private class ProgressionWriter(private val store: RaceStore) {
    data class Read(val uid: String, val fields: Map<String, Any?>, val version: Long)

    fun read(uid: String): Read {
      val stored = store.profile(uid)
      return Read(uid, LinkedHashMap(stored.fields), stored.version)
    }

    fun settle(read: Read, rpDelta: Int) {
      val stored = store.profile(read.uid)
      check(stored.version == read.version) {
        "stale settlement for '${read.uid}': read v${read.version}, " +
          "the store is at v${stored.version}"
      }
      val rp = (read.fields["rp"] as? Number)?.toInt() ?: 0
      val nextRp = rp + rpDelta
      val rung = rankAt(nextRp)
      val highest = higherOf(highestOf(read.fields), rung)
      stored.fields["rp"] = nextRp
      stored.fields["tier"] = rung.tier
      stored.fields["division"] = rung.division
      stored.fields["highestRankTier"] = highest.tier
      stored.fields["highestRankDivision"] = highest.division
      stored.fields["rankedGamesPlayed"] = counter(read.fields, "rankedGamesPlayed") + 1
      stored.fields["rankedKingCount"] = counter(read.fields, "rankedKingCount") + 1
      stored.fields["rankedSecondCount"] = counter(read.fields, "rankedSecondCount")
      stored.fields["rankedThirdCount"] = counter(read.fields, "rankedThirdCount")
      stored.fields["rankedKozCount"] = counter(read.fields, "rankedKozCount")
      stored.version++
    }

    fun settleNow(uid: String, rpDelta: Int) = settle(read(uid), rpDelta)
  }

  // ── fixtures ─────────────────────────────────────────────────────────

  /** A production-shaped `players/{uid}` progression block. */
  private fun profileFields(
    tier: String = "Gold",
    division: String = "I",
    rp: Int = 825,
    seasonId: String = FROM,
    highestTier: String = tier,
    highestDivision: String = division,
  ): MutableMap<String, Any?> = linkedMapOf(
    "tier" to tier,
    "division" to division,
    "rp" to rp,
    "seasonId" to seasonId,
    "highestRankTier" to highestTier,
    "highestRankDivision" to highestDivision,
    "placementState" to "SKIPPED",
    "demotionShield" to false,
    "rankedGamesPlayed" to 0,
    "rankedKingCount" to 0,
    "rankedSecondCount" to 0,
    "rankedThirdCount" to 0,
    "rankedKozCount" to 0,
  )

  private fun seasonFields(
    seasonId: String = FROM,
    status: String = "ACTIVE",
    target: String? = null,
    next: String? = TO,
    endsAt: Any? = NOW - 1_000,
  ): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
    "seasonId" to seasonId,
    "status" to status,
    "nextSeasonId" to next,
    "endsAt" to endsAt,
    "startsAt" to NOW - 90L * DAY,
  ).apply {
    if (target != null) put("targetSeasonId", target)
  }

  private fun uid(index: Int): String = "p" + index.toString().padStart(3, '0')

  private fun profileOf(fields: Map<String, Any?>, seasonId: String = FROM): RankedProfile {
    val parsed = parseStoredProfile(fields, seasonId)
    check(parsed is ProfileParse.Parsed) { "the fixture does not parse: $parsed" }
    return parsed.profile
  }

  /** The real engine math, never a hand-computed RP. */
  private fun resetOf(fields: Map<String, Any?>): RankedProfile =
    applySeasonReset(profileOf(fields), TO)

  private fun seededStore(
    vararg profiles: Pair<String, MutableMap<String, Any?>>,
  ): RaceStore = RaceStore().apply {
    seedSeason(seasonFields())
    for ((id, fields) in profiles) seedProfile(id, fields)
  }

  // ── 1. a progression write lands between the plan read and the batch ──

  /**
   * The reset reads Gold I at 985, plans a Gold III landing (575). A
   * settlement then promotes the seat to Platinum III (1015) at v2. The reset
   * batch still carries v1, so it must fail ATOMICALLY: `PARTIAL`, `applied 0`,
   * the season left RUNNING, and not one profile moved.
   *
   * The resume re-reads, skips nothing, and lands the seat at the rung a
   * Platinum III profile resets to (Gold II, 700). The settlement's promotion
   * is therefore still visible in the final profile — it was not silently
   * overwritten by the stale plan.
   */
  @Test
  fun aProgressionWriteBetweenThePlanAndTheBatchFailsTheWholeBatchAndSurvivesTheResume() = runTest {
    val original = profileFields(rp = GOLD_I_RP)
    val store = seededStore("u1" to original)
    val port = ResetPort(store)
    val writer = ProgressionWriter(store)
    var settled = false
    port.beforeWriteProfiles = {
      if (!settled) {
        settled = true
        writer.settleNow("u1", 30)
      }
    }

    val first = runSeasonReset(port, NOW, SeasonResetMode.APPLY)

    assertEquals("PARTIAL", first.decision)
    assertEquals(1, first.planned)
    assertEquals(0, first.applied)
    assertEquals(SeasonStatus.RUNNING.name, store.season!!["status"])
    assertEquals(TO, store.season!!["targetSeasonId"])
    assertEquals(SeasonStatus.RUNNING.name, store.season!!["status"])
    assertEquals(0, port.profilesWritten)
    assertEquals(GOLD_I_RP + 30, store.profile("u1").fields["rp"])
    assertEquals("Platinum", store.profile("u1").fields["tier"])
    assertEquals(2L, store.profile("u1").version)

    val second = runSeasonReset(port, NOW, SeasonResetMode.APPLY)

    assertEquals("RESUME", second.decision)
    assertEquals(1, second.applied)
    val settledFields = profileFields(
      tier = "Platinum",
      division = "III",
      rp = GOLD_I_RP + 30,
      highestTier = "Platinum",
      highestDivision = "III",
    )
    val finalRp = store.profile("u1").fields["rp"]
    assertEquals(resetOf(settledFields).rp, finalRp)
    assertNotEquals(resetOf(original).rp, finalRp)
    assertEquals(TO, store.profile("u1").fields["seasonId"])
    assertEquals("Gold", store.profile("u1").fields["tier"])
    assertEquals("II", store.profile("u1").fields["division"])
  }

  // ── 2. a settlement that read before the reset ────────────────────────

  /**
   * The settlement reads v1 and pauses. The reset completes (batch at v2,
   * finalise to `s2`). The writer resumes holding v1: its write must throw,
   * and the retry must re-read v2 and settle ON the reset profile — so the
   * final state contains exactly one reset and one settlement, in that order,
   * with the reset's tier and season intact.
   */
  @Test
  fun aWriterThatReadBeforeTheResetIsRejectedAndRetriedOntoTheResetProfile() = runTest {
    val store = seededStore("u1" to profileFields())
    val port = ResetPort(store)
    val writer = ProgressionWriter(store)

    val stale = writer.read("u1")
    val reset = runSeasonReset(port, NOW, SeasonResetMode.APPLY)

    assertEquals("CLAIMED", reset.decision)
    assertEquals(1, reset.applied)
    assertEquals(TO, store.profile("u1").fields["seasonId"])

    assertFailsWith<IllegalStateException> { writer.settle(stale, 30) }
    assertEquals(TO, store.profile("u1").fields["seasonId"])
    assertEquals(GOLD_III_RP, store.profile("u1").fields["rp"])

    val fresh = writer.read("u1")
    writer.settle(fresh, 30)

    val final = store.profile("u1").fields
    assertEquals(TO, final["seasonId"])
    assertEquals("Gold", final["tier"])
    assertEquals("III", final["division"])
    assertEquals(GOLD_III_RP + 30, final["rp"])
    assertEquals(1, final["rankedGamesPlayed"])
    assertEquals(1, final["rankedKingCount"])
    assertEquals(3L, store.profile("u1").version)
  }

  // ── 3. two resets across a partial migration ──────────────────────────

  /**
   * 401 profiles means two chunks (400 + 1). Reset A commits its first chunk
   * and pauses. Reset B resumes the same claim, skips the 400 already at the
   * target, moves the last one and finalises. A's second chunk is now stale:
   * `PARTIAL`, `applied 400`, no finalise. A third run has nothing to do.
   *
   * The proof is the per-profile version: every one of the 401 profiles is at
   * exactly v2 — one committed write each, never two.
   */
  @Test
  fun twoResetsAcrossAPartialMigrationDemoteEachProfileExactlyOnce() = runTest {
    val store = RaceStore().apply { seedSeason(seasonFields()) }
    for (index in 1..401) store.seedProfile(uid(index), profileFields())
    val port = ResetPort(store)
    val paused = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var gated = false
    port.afterWriteProfiles = { batch ->
      if (!gated) {
        gated = true
        assertEquals(1, batch)
        paused.complete(Unit)
        release.await()
      }
    }

    val resetA = async { runSeasonReset(port, NOW, SeasonResetMode.APPLY) }
    paused.await()

    assertEquals(1, port.seasonWrites.size)
    assertEquals(SeasonStatus.RUNNING.name, port.seasonWrites.single()["status"])
    assertEquals(400, port.profilesWritten)

    val seasonWritesBeforeB = port.seasonWrites.size
    val resetB = runSeasonReset(port, NOW, SeasonResetMode.APPLY)

    assertEquals("RESUME", resetB.decision)
    assertEquals(400, resetB.skippedAlreadyTarget)
    assertEquals(1, resetB.applied)
    assertEquals(401, resetB.planned + resetB.skippedAlreadyTarget)
    val bSeasonWrites = port.seasonWrites.drop(seasonWritesBeforeB)
    assertEquals(1, bSeasonWrites.size)
    assertEquals("ACTIVE", bSeasonWrites.single()["status"])
    assertEquals(TO, bSeasonWrites.single()["seasonId"])

    val seasonWritesBeforeRelease = port.seasonWrites.size
    release.complete(Unit)
    val reportA = resetA.await()

    assertEquals("PARTIAL", reportA.decision)
    assertEquals(400, reportA.applied)
    assertEquals(seasonWritesBeforeRelease, port.seasonWrites.size)

    val readsBeforeC = port.profileReads
    val batchesBeforeC = port.profileBatches
    val seasonWritesBeforeC = port.seasonWrites.size
    val resetC = runSeasonReset(port, NOW, SeasonResetMode.APPLY)

    assertEquals("NOT_CONFIGURED", resetC.decision)
    assertEquals(readsBeforeC, port.profileReads)
    assertEquals(batchesBeforeC, port.profileBatches)
    assertEquals(seasonWritesBeforeC, port.seasonWrites.size)

    val expectedRp = resetOf(profileFields()).rp
    for (index in 1..401) {
      val stored = store.profile(uid(index))
      assertEquals(TO, stored.fields["seasonId"], "profile ${uid(index)}")
      assertEquals(expectedRp, stored.fields["rp"], "profile ${uid(index)}")
      assertEquals(2L, stored.version, "profile ${uid(index)} was written more than once")
    }
  }

  // ── 4. the finalise window is visible ─────────────────────────────────

  /**
   * CHARACTERIZATION of the current contract, not a desired end state.
   *
   * The reset writes the profile batch first and finalises the season document
   * last. Inside that window the season still reads `s1` while every migrated
   * profile already reads `s2`. A lazy reader in this window — a settlement
   * that calls `alignSeatedProfilesToSeason` — would see
   * `recordedSeason = s1`, `currentSeason = s1` and a profile at `s2`, and
   * `planSeasonReset(s1, s1, ...)` is refused by the same-ID check, but a
   * reader that derives the target from the profile would plan `s2 → s1`,
   * i.e. a BACKWARDS reset. That hazard lives in `SettlementDb.kt`
   * (`alignSeatedProfilesToSeason` / `planSeasonReset` have no forward-only
   * guard) and is reported, never fixed here.
   */
  @Test
  fun theFinaliseWindowExposesFromWhileTheProfileAlreadyReadsTo() = runTest {
    val store = seededStore("u1" to profileFields())
    val port = ResetPort(store)
    var sawWindow = false
    port.beforeTransaction = { number ->
      if (number == 3) {
        sawWindow = true
        assertEquals(FROM, store.season!!["seasonId"])
        assertEquals(SeasonStatus.RUNNING.name, store.season!!["status"])
        assertEquals(TO, store.profile("u1").fields["seasonId"])
      }
    }

    val report = runSeasonReset(port, NOW, SeasonResetMode.APPLY)

    assertEquals("CLAIMED", report.decision)
    assertTrue(sawWindow, "the finalise transaction never ran")
    assertEquals(TO, store.season!!["seasonId"])
    assertEquals(SeasonStatus.ACTIVE.name, store.season!!["status"])
    assertNull(store.season!!["nextSeasonId"])
  }

  // ── 5. a replay after the finalise is a no-op ─────────────────────────

  @Test
  fun aReplayAfterTheFinaliseIsNotConfiguredAndReadsNoProfiles() = runTest {
    val store = seededStore("u1" to profileFields())
    val port = ResetPort(store)
    assertEquals("CLAIMED", runSeasonReset(port, NOW, SeasonResetMode.APPLY).decision)

    val reads = port.profileReads
    val batches = port.profileBatches
    val seasonWrites = port.seasonWrites.size

    val replay = runSeasonReset(port, NOW, SeasonResetMode.APPLY)

    assertEquals("NOT_CONFIGURED", replay.decision)
    assertEquals(reads, port.profileReads)
    assertEquals(batches, port.profileBatches)
    assertEquals(seasonWrites, port.seasonWrites.size)
  }

  // ── 6. every write carries the exact version read ─────────────────────

  @Test
  fun everyWriteCarriesTheExactVersionReadAndAMissingVersionWritesNothing() = runTest {
    val store = seededStore(
      "u1" to profileFields(),
      "u2" to profileFields(tier = "Silver", division = "I", rp = 500),
    )
    val port = ResetPort(store)

    assertEquals("CLAIMED", runSeasonReset(port, NOW, SeasonResetMode.APPLY).decision)

    assertEquals(2, port.writeVersions.size)
    for ((uid, expected) in port.writeVersions) {
      assertEquals(port.lastReadVersions[uid], expected, "write for $uid")
      assertEquals(1L, expected, "the plan must carry the read version")
    }

    // A missing version is a programming error, never an unguarded write: the
    // whole batch is refused and the valid entry beside it is not applied.
    val second = seededStore("u1" to profileFields(), "u2" to profileFields())
    val secondPort = ResetPort(second)
    val before = LinkedHashMap(second.profile("u1").fields)
    assertFailsWith<IllegalStateException> {
      secondPort.writeProfiles(
        listOf(
          ProfileWrite("u1", mapOf("rp" to 1), null),
          ProfileWrite("u2", mapOf("rp" to 1), 1L),
        ),
      )
    }
    assertEquals(before, second.profile("u1").fields)
    assertEquals(1L, second.profile("u1").version)
    assertEquals(1L, second.profile("u2").version)
    assertEquals(0, secondPort.profileBatches)
  }
}

// File-level so the nested store, port and writer can share them.

private fun counter(fields: Map<String, Any?>, key: String): Int =
  (fields[key] as? Number)?.toInt() ?: 0

private fun highestOf(fields: Map<String, Any?>): LadderRank {
  val tier = fields["highestRankTier"] as? String
  val division = fields["highestRankDivision"] as? String
  return rankLadder.firstOrNull { it.tier == tier && it.division == division }
    ?: rankAt((fields["rp"] as? Number)?.toInt() ?: 0)
}

private fun higherOf(a: LadderRank, b: LadderRank): LadderRank =
  if (rankLadder.indexOf(a) >= rankLadder.indexOf(b)) a else b

