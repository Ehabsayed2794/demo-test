package com.estemshan.functions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Q22 lazy season reset (E5.2/E5.3, V1-C10) — a profile whose recorded season
 * is behind `seasons/current` is reset exactly ONCE on first access, however
 * many seasons it slept through, on BOTH paths that can touch a profile:
 *
 * - the read callable ([loadOnce]) resets inside its own transaction instead
 *   of failing closed;
 * - the settlement ([settleOnce]) reads the season once inside its
 *   transaction, resets every stale seat BEFORE folding RP, and writes
 *   `seasonId = current` — so no settled profile is ever left behind the
 *   current season (V1-C10).
 *
 * Every number below is hand-derived from the closed rule table against
 * [SettlementFixtures.GOLDEN_SCORES] and the ladder (Gold II = ordinal 7,
 * lowerBound 700; two reset steps land on Silver I, 450), exactly the way
 * `RpApplicationTest` derives its goldens: KING 30 + 2/strength ordinal,
 * SECOND 15 + 2/ordinal, THIRD −8 + 2/ordinal sign-clamped to 0, KOZ
 * −20 + 2/ordinal, tier difference 0 on a same-rung table, FULL game.
 *
 * Fake ports only — no emulator, no FIRESTORE_EMULATOR_HOST.
 */
class LazySeasonResetTest {

  private val uids = listOf("u1", "u2", "u3", "u4")
  private val archives = mapOf("1" to SettlementFixtures.round1ArchiveFields())

  /** The current season's singleton document, as the Admin Tx would parse it. */
  private fun seasonDoc(id: String): Map<String, Any?> = linkedMapOf("seasonId" to id)

  /**
   * A complete, fully-migrated profile parked in [season] at Gold II / 732 RP
   * (a rung above its floor, so nothing here leans on a bottom-rung default),
   * peak Gold II, 12 games of career statistics already earned.
   */
  private fun staleableProfile(season: String): MutableMap<String, Any?> = mutableMapOf(
    "displayName" to "Nour",
    "avatarInitial" to "N",
    "tier" to "Gold",
    "division" to "II",
    "rp" to 732,
    "seasonId" to season,
    "highestRankTier" to "Gold",
    "highestRankDivision" to "II",
    "placementState" to "SKIPPED",
    "demotionShield" to false,
    "rankedGamesPlayed" to 12,
    "rankedKingCount" to 4,
    "rankedSecondCount" to 3,
    "rankedThirdCount" to 3,
    "rankedKozCount" to 2,
  )

  private fun fakeRequest(uid: String?): CallableRequest = object : CallableRequest {
    override val data: Any? = null
    override val auth: AuthData? = uid?.let {
      object : AuthData {
        override val uid: String = it
        override val token: AuthToken? = null
      }
    }
    override val instanceIdToken: String? = null
    override val rawRequest: Any? = null
    override val app: Any? = null
  }

  @Suppress("UNCHECKED_CAST")
  private fun section(response: Map<String, Any?>, key: String): Map<String, Any?> =
    response.getValue(key) as Map<String, Any?>

  private suspend fun thrownCode(block: suspend () -> Any?): String {
    try {
      block()
    } catch (e: Throwable) {
      return e.asDynamic().code as String
    }
    throw AssertionError("expected HttpsError")
  }

  private suspend fun thrownMessage(block: suspend () -> Any?): String {
    try {
      block()
    } catch (e: Throwable) {
      return e.asDynamic().message as String
    }
    throw AssertionError("expected HttpsError")
  }

  // ── 1. one reset on first access, read path ──────────────────────────

  @Test
  fun aStaleProfileIsResetExactlyOnceWithASixFieldMerge() = runTest {
    val db = FakeProfileDb(staleableProfile("s1"), seasonDoc("s3"))

    val response = loadOnce(db, fakeRequest("u1"))

    assertEquals(true, response["migrated"])
    val profile = section(response, "profile")
    // Gold II takes the two-step demotion to Silver I at its floor; the
    // season advances by the FULL gap s1 → s3 in one move; the Gold II peak
    // survives (Highest Rank never lowers).
    assertEquals("Silver", profile["tier"])
    assertEquals("I", profile["division"])
    assertEquals(450, profile["rp"])
    assertEquals("s3", profile["seasonId"])
    assertEquals("Gold", profile["highestRankTier"])
    assertEquals("II", profile["highestRankDivision"])
    // Placement and the shield carry over; the career statistics are served
    // untouched — the reset touches neither, by engine contract.
    assertEquals("SKIPPED", profile["placementState"])
    assertEquals(false, profile["demotionShield"])
    assertEquals(12, section(response, "stats")["gamesPlayed"])
    assertEquals(4, section(response, "stats")["kingCount"])

    // ONE write: exactly the bulk sweep's six progression fields, nothing
    // else — placement, shield, statistics and identity are preserved by not
    // being named.
    assertEquals(1, db.writes)
    assertEquals(
      linkedMapOf(
        "tier" to "Silver",
        "division" to "I",
        "rp" to 450,
        "seasonId" to "s3",
        "highestRankTier" to "Gold",
        "highestRankDivision" to "II",
      ),
      db.merges.single(),
    )
    assertEquals("Nour", db.doc?.get("displayName"))
    assertEquals(12, db.doc?.get("rankedGamesPlayed"))
    assertEquals(false, db.doc?.get("demotionShield"))
    assertEquals(listOf("read", "season", "merge"), db.events)
  }

  // ── 2. two missed seasons are still ONE reset ────────────────────────

  @Test
  fun twoMissedSeasonsProduceOneResetNotTwo() = runTest {
    val oneMissed = FakeProfileDb(staleableProfile("s2"), seasonDoc("s3"))
    val twoMissed = FakeProfileDb(staleableProfile("s1"), seasonDoc("s3"))

    val afterOne = section(loadOnce(oneMissed, fakeRequest("u1")), "profile")
    val afterTwo = section(loadOnce(twoMissed, fakeRequest("u1")), "profile")

    // The reset is a rung mapping, not a per-season loop: sleeping through
    // s2 AND s1 lands on the same Silver I / 450 as sleeping through s2
    // alone — NOT two two-step demotions (Silver III / 250) and not a single
    // step (Gold III / 575).
    assertEquals(afterOne["tier"], afterTwo["tier"])
    assertEquals(afterOne["division"], afterTwo["division"])
    assertEquals(afterOne["rp"], afterTwo["rp"])
    assertEquals("s3", afterTwo["seasonId"])
    assertEquals("Silver", afterTwo["tier"])
    assertEquals("I", afterTwo["division"])
    assertEquals(450, afterTwo["rp"])
    assertEquals(1, oneMissed.writes)
    assertEquals(1, twoMissed.writes)
  }

  // ── 3. a current-season profile is never season-written ──────────────

  @Test
  fun aCurrentSeasonProfileMergesOnlyLazyInitFields() = runTest {
    val db = FakeProfileDb(
      staleableProfile("s3").apply {
        remove("placementState")
        remove("demotionShield")
        remove("rankedGamesPlayed")
        remove("rankedKingCount")
        remove("rankedSecondCount")
        remove("rankedThirdCount")
        remove("rankedKozCount")
      },
      seasonDoc("s3"),
    )

    val response = loadOnce(db, fakeRequest("u1"))

    // The lazy-init writes are exactly what they always were — the missing
    // defaults — and the season is NOT among them: an already-current
    // profile is served, never re-stamped.
    assertEquals(true, response["migrated"])
    assertEquals(1, db.writes)
    assertEquals(
      setOf(
        "placementState", "demotionShield",
        "rankedGamesPlayed",
        "rankedKingCount", "rankedSecondCount", "rankedThirdCount", "rankedKozCount",
      ),
      db.merges.single().keys,
    )
    assertEquals("Gold", db.doc?.get("tier"))
    assertEquals(732, db.doc?.get("rp"))

    // And a fully-present current profile performs no write at all.
    val complete = FakeProfileDb(staleableProfile("s3"), seasonDoc("s3"))
    val served = loadOnce(complete, fakeRequest("u1"))
    assertEquals(false, served["migrated"])
    assertEquals(0, complete.writes)
  }

  // ── 4. corrupt stays fail-closed, zero writes ────────────────────────

  @Test
  fun aStaleProfileThatCannotBeParsedFailsClosedWithoutWriting() = runTest {
    val corruptPlacement = FakeProfileDb(
      staleableProfile("s1").apply { put("placementState", "DONE") },
      seasonDoc("s3"),
    )
    assertEquals(
      "failed-precondition",
      thrownCode { loadOnce(corruptPlacement, fakeRequest("u1")) },
    )
    assertTrue(
      "placementState 'DONE' is not a PlacementState name" in
        thrownMessage { loadOnce(corruptPlacement, fakeRequest("u1")) },
    )
    assertEquals(0, corruptPlacement.writes)
    assertEquals(0, corruptPlacement.merges.size)

    // A blank recorded season can be neither adopted nor reset: the same
    // fail-closed branch the read path always had.
    val blankSeason = FakeProfileDb(staleableProfile("  "), seasonDoc("s3"))
    assertEquals(
      "failed-precondition",
      thrownCode { loadOnce(blankSeason, fakeRequest("u1")) },
    )
    assertEquals(0, blankSeason.writes)
    assertEquals(0, blankSeason.merges.size)
  }

  // ── 5. idempotence: the second read writes nothing ───────────────────

  @Test
  fun aSecondReadAfterTheResetWritesNothingAndServesIdenticalValues() = runTest {
    val db = FakeProfileDb(staleableProfile("s1"), seasonDoc("s3"))

    val first = loadOnce(db, fakeRequest("u1"))
    val second = loadOnce(db, fakeRequest("u1"))

    assertEquals(true, first["migrated"])
    assertEquals(false, second["migrated"])
    assertEquals(1, db.writes, "the reset landed once; the second read wrote nothing")
    assertEquals(section(first, "profile"), section(second, "profile"))
    assertEquals(section(first, "stats"), section(second, "stats"))
    assertEquals("s3", section(second, "profile")["seasonId"])
    assertEquals(450, section(second, "profile")["rp"])
  }

  // ── 6. settlement resets a stale seat before folding RP ──────────────

  /**
   * The full 13-field contract write for one seat folded from Silver I /
   * 450, on top of the seeded career statistics (12 games, 4/3/3/2): the
   * counters carried over + this match's one outcome.
   */
  private fun foldedWrite(
    rp: Int,
    tier: String,
    division: String,
    king: Int = 4,
    second: Int = 3,
    third: Int = 3,
    koz: Int = 2,
  ): Map<String, Any?> = linkedMapOf(
    "rp" to rp,
    "tier" to tier,
    "division" to division,
    "demotionShield" to false,
    "highestRankTier" to "Gold",
    "highestRankDivision" to "II",
    "seasonId" to "s3",
    "placementState" to "SKIPPED",
    "rankedGamesPlayed" to 13,
    "rankedKingCount" to king,
    "rankedSecondCount" to second,
    "rankedThirdCount" to third,
    "rankedKozCount" to koz,
  )

  @Test
  fun settlementResetsStaleSeatsBeforeFoldingRpIntoTheCurrentSeason() = runTest {
    val db = FakeSettleDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = uids.associateWith { staleableProfile("s1") },
      currentSeason = seasonDoc("s3"),
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, response["settled"])
    assertEquals(4, db.profileWrites.size)
    assertTrue(
      db.profileWrites.all { (_, fields) -> fields["seasonId"] == "s3" },
      "no settled seat may be left behind the current season (V1-C10)",
    )

    // The fold starts from the RESET pre-image (Silver I / 450), never from
    // the stale Gold II / 732: KING 450+40=490, SECOND 450+25=475, THIRD
    // −8+10 sign-clamped to 0 stays 450, KOZ −10 crosses the division floor
    // down to Silver II / 440. Each award applied exactly once.
    assertEquals(foldedWrite(490, "Silver", "I", king = 5), db.writeFor("u1"))
    assertEquals(foldedWrite(475, "Silver", "I", second = 4), db.writeFor("u4"))
    assertEquals(foldedWrite(450, "Silver", "I", third = 4), db.writeFor("u3"))
    assertEquals(foldedWrite(440, "Silver", "II", koz = 3), db.writeFor("u2"))

    // The caller's own movement reports the fold from the reset value.
    assertEquals(mapOf("previousRp" to 450, "rp" to 490, "delta" to 40), response["you"])

    // Reads before writes, inside the one transaction.
    assertEquals(
      listOf("read-profile:u1", "read-profile:u2", "read-profile:u3", "read-profile:u4"),
      db.events.take(4),
    )
  }

  // ── 7. settlement/reset race: one total order at the fake-tx level ───

  @Test
  fun aReadResetAndASettlementSerializeIntoOneResetAndOneFold() = runTest {
    // Order (a): the read callable resets u1 first; the settlement then
    // folds u1 from the reset value and resets only the seats still stale.
    val readFirst = SharedStore(seasonDoc("s3"))
    val readResponse = loadOnce(readFirst.profileDb, fakeRequest("u1"))
    assertEquals(true, readResponse["migrated"])
    assertEquals(1, readFirst.merges.size)
    assertEquals(450, section(readResponse, "profile")["rp"])

    val settleAfterRead = settleOnce(
      readFirst.settlementDb,
      SettlementFixtures.settleRequest("u1", "m1"),
    )
    assertEquals(true, settleAfterRead["settled"])
    assertEquals(mapOf("previousRp" to 450, "rp" to 490, "delta" to 40), settleAfterRead["you"])
    // u1 was already current when the settlement read it: its write folds
    // from 450, and it is demoted exactly once across BOTH paths.
    assertEquals(490, readFirst.profileWrites.single { it.first == "u1" }.second["rp"])

    // Order (b): the settlement resets and folds everyone first; the read
    // then finds u1 current and writes nothing.
    val settleFirst = SharedStore(seasonDoc("s3"))
    val settled = settleOnce(settleFirst.settlementDb, SettlementFixtures.settleRequest("u1", "m1"))
    assertEquals(true, settled["settled"])
    val readAfter = loadOnce(settleFirst.profileDb, fakeRequest("u1"))
    assertEquals(false, readAfter["migrated"])
    assertEquals(0, settleFirst.merges.size)

    // One total order either way: both orders leave u1 with exactly one
    // reset and one fold — identical final documents, never a stale season.
    val expectedU1 = linkedMapOf<String, Any?>(
      "displayName" to "Nour",
      "avatarInitial" to "N",
      "tier" to "Silver",
      "division" to "I",
      "rp" to 490,
      "highestRankTier" to "Gold",
      "highestRankDivision" to "II",
      "seasonId" to "s3",
      "placementState" to "SKIPPED",
      "demotionShield" to false,
      "rankedGamesPlayed" to 1,
      "rankedKingCount" to 1,
      "rankedSecondCount" to 0,
      "rankedThirdCount" to 0,
      "rankedKozCount" to 0,
    )
    assertEquals(expectedU1, readFirst.players.getValue("u1"))
    assertEquals(expectedU1, settleFirst.players.getValue("u1"))
    assertEquals("s3", readFirst.players.getValue("u2")["seasonId"])
    assertEquals("s3", settleFirst.players.getValue("u4")["seasonId"])
  }

  // ── 8. no seasons/current: bootstrap served, season state protected ──

  @Test
  fun aMissingSingletonBootstrapsFreshAndBootstrapSeasonProfiles() = runTest {
    // A legacy profile with no seasonId adopts the bootstrap literal —
    // exactly the fresh-deployment read.
    val legacy = FakeProfileDb(
      staleableProfile("s1").apply { remove("seasonId") },
      currentSeason = null,
    )
    val legacyResponse = loadOnce(legacy, fakeRequest("u1"))
    assertEquals(true, legacyResponse["migrated"])
    assertEquals("s1", section(legacyResponse, "profile")["seasonId"])
    assertEquals(mapOf("seasonId" to "s1"), legacy.merges.single())

    // A profile already IN the bootstrap season is served as current: no
    // reset, no write.
    val bootstrap = FakeProfileDb(staleableProfile("s1"), currentSeason = null)
    val served = loadOnce(bootstrap, fakeRequest("u1"))
    assertEquals(false, served["migrated"])
    assertEquals("s1", section(served, "profile")["seasonId"])
    assertEquals(0, bootstrap.writes)
  }

  @Test
  fun aNonDefaultSeasonWithNoSingletonFailsClosedOnRead() = runTest {
    val db = FakeProfileDb(staleableProfile("s2"), currentSeason = null)

    assertEquals(
      "failed-precondition",
      thrownCode { loadOnce(db, fakeRequest("u1")) },
    )
    // The bootstrap must not silently overwrite an existing season state: a
    // lazy reset to 's1' would be a reset no season declared.
    val message = thrownMessage { loadOnce(db, fakeRequest("u1")) }
    assertTrue("'s2'" in message, "expected '$message' to name the recorded season")
    assertTrue("seasons/current does not exist" in message)
    assertEquals(0, db.writes)
    assertEquals(0, db.merges.size)
    assertFalse("merge" in db.events)
  }

  @Test
  fun aCorruptSingletonDocFailsClosedOnRead() = runTest {
    val noSeasonField = FakeProfileDb(
      staleableProfile("s1"),
      currentSeason = linkedMapOf<String, Any?>("status" to "ACTIVE"),
    )
    assertEquals(
      "failed-precondition",
      thrownCode { loadOnce(noSeasonField, fakeRequest("u1")) },
    )
    assertEquals(0, noSeasonField.writes)

    val blank = FakeProfileDb(
      staleableProfile("s1"),
      currentSeason = linkedMapOf<String, Any?>("seasonId" to "   "),
    )
    assertEquals(
      "failed-precondition",
      thrownCode { loadOnce(blank, fakeRequest("u1")) },
    )
    assertEquals(0, blank.writes)
    assertTrue(
      "seasons/current has no usable seasonId" in
        thrownMessage { loadOnce(blank, fakeRequest("u1")) },
    )
  }

  /** The 13-field fold for a fresh 690-RP Gold III table bootstrapped into "s1". */
  private fun bootstrapWrite(
    rp: Int,
    tier: String,
    division: String,
    king: Int = 0,
    second: Int = 0,
    third: Int = 0,
    koz: Int = 0,
  ): Map<String, Any?> = linkedMapOf(
    "rp" to rp,
    "tier" to tier,
    "division" to division,
    "demotionShield" to false,
    "highestRankTier" to tier,
    "highestRankDivision" to division,
    "seasonId" to "s1",
    "placementState" to "SKIPPED",
    "rankedGamesPlayed" to 1,
    "rankedKingCount" to king,
    "rankedSecondCount" to second,
    "rankedThirdCount" to third,
    "rankedKozCount" to koz,
  )

  @Test
  fun settlementBootstrapsFreshSeatsWhenNoSingletonExists() = runTest {
    // u1 carries the bootstrap season explicitly; u2-u4 carry none. With no
    // singleton, every seat bootstraps into "s1" and the table folds exactly
    // as it did before seasons existed — no reset, no demotion.
    val db = FakeSettleDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", 690, seasonId = "s1"),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to SettlementFixtures.profile("u3", 690),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
      currentSeason = null,
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, response["settled"])
    assertTrue(
      db.profileWrites.all { (_, fields) -> fields["seasonId"] == "s1" },
      "the bootstrap season is written for every seat",
    )
    assertEquals(bootstrapWrite(732, "Gold", "II", king = 1), db.writeFor("u1"))
    assertEquals(bootstrapWrite(717, "Gold", "II", second = 1), db.writeFor("u4"))
    assertEquals(bootstrapWrite(690, "Gold", "III", third = 1), db.writeFor("u3"))
    assertEquals(bootstrapWrite(682, "Gold", "III", koz = 1), db.writeFor("u2"))
  }

  @Test
  fun settlementFailsClosedWhenASeatBeyondBootstrapMeetsNoSingleton() = runTest {
    val db = FakeSettleDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", 690, seasonId = "s2"),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to SettlementFixtures.profile("u3", 690),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
      currentSeason = null,
    )

    assertEquals(
      "failed-precondition",
      thrownCode { settleOnce(db, SettlementFixtures.settleRequest("u1", "m1")) },
    )
    val message = thrownMessage { settleOnce(db, SettlementFixtures.settleRequest("u1", "m1")) }
    assertTrue("'s2'" in message, "expected '$message' to name the recorded season")
    assertTrue("seasons/current does not exist" in message)
    // The whole settlement failed closed BEFORE any write: no profile write,
    // no claim, so the match stays settleable once the season state is.
    assertEquals(0, db.profileWrites.size)
    assertEquals(null, db.claim)
    assertTrue(db.events.none { it.startsWith("write-") })
  }

  @Test
  fun settlementFailsClosedOnACorruptSingletonDoc() = runTest {
    val db = FakeSettleDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = uids.associateWith { uid -> SettlementFixtures.profile(uid, 690) },
      currentSeason = linkedMapOf<String, Any?>("status" to "ACTIVE"),
    )

    assertEquals(
      "failed-precondition",
      thrownCode { settleOnce(db, SettlementFixtures.settleRequest("u1", "m1")) },
    )
    assertTrue(
      "seasons/current has no usable seasonId" in
        thrownMessage { settleOnce(db, SettlementFixtures.settleRequest("u1", "m1")) },
    )
    assertEquals(0, db.profileWrites.size)
    assertEquals(null, db.claim)
  }

  // ── the read-path fake ───────────────────────────────────────────────

  /**
   * One profile document behind one transaction, against a `seasons/current`
   * document — NULL models the singleton not existing (the bootstrap). The
   * merge COMMITS into the document, so a second transaction in the same test
   * sees the first one's migration.
   */
  private class FakeProfileDb(
    var doc: Map<String, Any?>?,
    private val currentSeason: Map<String, Any?>?,
  ) : RankedProfileDb {
    var writes = 0
      private set
    val merges = mutableListOf<Map<String, Any?>>()
    val events = mutableListOf<String>()

    override suspend fun <T> transact(block: suspend (RankedProfileDb.Tx) -> T): T =
      block(object : RankedProfileDb.Tx {
        override suspend fun read(uid: String): Map<String, Any?>? {
          events += "read"
          return doc
        }

        override suspend fun currentSeasonId(): String? {
          events += "season"
          if (currentSeason == null) return null
          return currentSeasonIdOf(exists = true, data = currentSeason)
        }

        override fun merge(uid: String, fields: Map<String, Any?>) {
          events += "merge"
          writes++
          merges += fields
          doc = (doc ?: emptyMap()) + fields
        }
      })
  }

  // ── the settlement fake ──────────────────────────────────────────────

  /**
   * One settlement transaction over committed profile documents. NULL
   * [currentSeason] models the `seasons/current` singleton not existing (the
   * bootstrap).
   */
  private class FakeSettleDb(
    val match: Map<String, Any?>,
    val archives: Map<String, Map<String, Any?>>,
    profiles: Map<String, Map<String, Any?>>,
    val currentSeason: Map<String, Any?>?,
  ) : SettlementDb {
    val players = LinkedHashMap<String, MutableMap<String, Any?>>()

    init {
      profiles.forEach { (uid, doc) -> players[uid] = doc.toMutableMap() }
    }
    var claim: Map<String, Any?>? = null
    val profileWrites = mutableListOf<Pair<String, Map<String, Any?>>>()
    val events = mutableListOf<String>()

    fun writeFor(uid: String): Map<String, Any?> =
      profileWrites.single { it.first == uid }.second

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = match

    override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> = archives

    override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T =
      block(object : SettlementDb.SettlementTx {
        override suspend fun readClaim(matchId: String): Map<String, Any?>? = claim

        override suspend fun currentSeasonId(): String? =
          currentSeason?.let { currentSeasonIdOf(exists = true, data = it) }

        override suspend fun readProfile(uid: String): Map<String, Any?>? {
          events += "read-profile:$uid"
          if (events.any { it.startsWith("write-") }) {
            throw IllegalStateException(
              "Firestore transactions require all reads to be executed before all writes",
            )
          }
          return players[uid]
        }

        override fun writeCorrection(
          matchId: String,
          scores: Map<String, Int>,
          winners: List<String>,
          version: Int,
        ) {}

        override fun writeProfile(uid: String, fields: Map<String, Any?>) {
          events += "write-profile:$uid"
          profileWrites += uid to fields
          players.getOrPut(uid) { mutableMapOf() }.putAll(fields)
        }

        override fun writeClaim(matchId: String, record: Map<String, Any?>) {
          claim = record
        }
      })

    override suspend fun appendLedger(record: Map<String, Any?>) {}

    override suspend fun readCorrectionWindow(): List<Map<String, Any?>> = emptyList()
  }

  // ── the race fixture: one store, two ports ───────────────────────────

  /**
   * ONE committed store behind TWO ports — the read callable's and the
   * settlement's — so a test can interleave [loadOnce] and [settleOnce]
   * against the same profiles and assert the single total order their
   * transactions impose. Each transaction sees the other's committed writes;
   * neither can interleave a read between the other's writes.
   */
  private class SharedStore(val currentSeason: Map<String, Any?>) {
    val players = LinkedHashMap<String, MutableMap<String, Any?>>()
    var claim: Map<String, Any?>? = null
    val merges = mutableListOf<Map<String, Any?>>()
    val profileWrites = mutableListOf<Pair<String, Map<String, Any?>>>()

    init {
      for (uid in listOf("u1", "u2", "u3", "u4")) {
        players[uid] = mutableMapOf(
          "displayName" to "Nour",
          "avatarInitial" to "N",
          "tier" to "Gold",
          "division" to "II",
          "rp" to 732,
          "seasonId" to "s1",
          "highestRankTier" to "Gold",
          "highestRankDivision" to "II",
          "placementState" to "SKIPPED",
          "demotionShield" to false,
        )
      }
    }

    val profileDb = object : RankedProfileDb {
      override suspend fun <T> transact(block: suspend (RankedProfileDb.Tx) -> T): T =
        block(object : RankedProfileDb.Tx {
          override suspend fun read(uid: String): Map<String, Any?>? = players[uid]

          override suspend fun currentSeasonId(): String =
            currentSeasonIdOf(exists = true, data = currentSeason)

          override fun merge(uid: String, fields: Map<String, Any?>) {
            merges += fields
            players.getOrPut(uid) { mutableMapOf() }.putAll(fields)
          }
        })
    }

    val settlementDb = object : SettlementDb {
      override suspend fun readMatch(matchId: String): Map<String, Any?>? =
        SettlementFixtures.matchFields(listOf("u1", "u2", "u3", "u4"))

      override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> =
        mapOf("1" to SettlementFixtures.round1ArchiveFields())

      override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T =
        block(object : SettlementDb.SettlementTx {
          override suspend fun readClaim(matchId: String): Map<String, Any?>? = claim

          override suspend fun currentSeasonId(): String =
            currentSeasonIdOf(exists = true, data = currentSeason)

          override suspend fun readProfile(uid: String): Map<String, Any?>? = players[uid]

          override fun writeCorrection(
            matchId: String,
            scores: Map<String, Int>,
            winners: List<String>,
            version: Int,
          ) {}

          override fun writeProfile(uid: String, fields: Map<String, Any?>) {
            profileWrites += uid to fields
            players.getOrPut(uid) { mutableMapOf() }.putAll(fields)
          }

          override fun writeClaim(matchId: String, record: Map<String, Any?>) {
            claim = record
          }
        })

      override suspend fun appendLedger(record: Map<String, Any?>) {}

      override suspend fun readCorrectionWindow(): List<Map<String, Any?>> = emptyList()
    }
  }
}
