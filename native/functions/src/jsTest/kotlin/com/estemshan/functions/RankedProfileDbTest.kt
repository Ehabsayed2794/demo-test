package com.estemshan.functions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * S44/S51 — the Ranked profile read/normalisation matrix.
 *
 * The fake port records transaction runs, reads and merges, so each test can
 * assert the two properties the contract turns on: a valid profile is never
 * rewritten, and a migration writes exactly the missing (or highest-repair)
 * fields. Error cases assert both the HttpsError code and that no write ran.
 */

/** Marks a Tx whose `seasons/current` does not exist: the port's default answers. */
private val NO_SEASON_DOC = Any()

class RankedProfileDbTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  private fun validProfile(): MutableMap<String, Any?> = mutableMapOf(
    "displayName" to "Nour",
    "avatarInitial" to "N",
    "currentMatchId" to "m-1",
    "tier" to "Gold",
    "division" to "II",
    "rp" to 732,
    "seasonId" to "s1",
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

  private suspend fun assertPreconditionNoWrite(
    db: FakeDb,
    block: suspend () -> Any?,
  ) {
    assertEquals("failed-precondition", thrownCode(block))
    assertEquals(0, db.writes, "a failed normalization must never write")
  }

  // ── the fully valid profile ──────────────────────────────────────────

  @Test
  fun aFullyValidProfileReturnsEveryContractValueAndWritesNothing() = runTest {
    val db = FakeDb(validProfile())

    val response = loadOnce(db, fakeRequest("u1"))

    assertEquals(setOf("ok", "migrated", "profile", "stats"), response.keys)
    assertEquals(true, response["ok"])
    assertEquals(false, response["migrated"])
    assertEquals(0, db.writes)

    val profile = section(response, "profile")
    assertEquals(
      setOf(
        "tier", "division", "rp", "seasonId",
        "highestRankTier", "highestRankDivision",
        "placementState", "demotionShield",
      ),
      profile.keys,
    )
    assertEquals("Gold", profile["tier"])
    assertEquals("II", profile["division"])
    assertEquals(732, profile["rp"])
    assertEquals("s1", profile["seasonId"])
    assertEquals("Gold", profile["highestRankTier"])
    assertEquals("II", profile["highestRankDivision"])
    assertEquals("SKIPPED", profile["placementState"])
    assertEquals(false, profile["demotionShield"])

    val stats = section(response, "stats")
    assertEquals(
      setOf(
        "gamesPlayed",
        "kingCount", "kingPct",
        "secondCount", "secondPct",
        "thirdCount", "thirdPct",
        "kozCount", "kozPct",
      ),
      stats.keys,
    )
    assertEquals(12, stats["gamesPlayed"])
    assertEquals(4, stats["kingCount"])
    assertEquals(33, stats["kingPct"])
    assertEquals(3, stats["secondCount"])
    assertEquals(25, stats["secondPct"])
    assertEquals(3, stats["thirdCount"])
    assertEquals(25, stats["thirdPct"])
    assertEquals(2, stats["kozCount"])
    assertEquals(16, stats["kozPct"])
  }

  // ── legacy initialization ────────────────────────────────────────────

  @Test
  fun aLegacyUnrankedProfileInitializesOnlyRankedFieldsAndLeavesIdentityAlone() = runTest {
    val db = FakeDb(
      mutableMapOf(
        "displayName" to "Nour",
        "avatarInitial" to "N",
        "rank" to "Unranked",
        "rp" to 0,
      ),
    )

    val response = loadOnce(db, fakeRequest("u1"))

    assertEquals(true, response["migrated"])
    val profile = section(response, "profile")
    assertEquals("Bronze", profile["tier"])
    assertEquals("III", profile["division"])
    assertEquals(0, profile["rp"])
    assertEquals("s1", profile["seasonId"])
    assertEquals("Bronze", profile["highestRankTier"])
    assertEquals("III", profile["highestRankDivision"])
    assertEquals("NOT_STARTED", profile["placementState"])
    assertEquals(false, profile["demotionShield"])

    val stats = section(response, "stats")
    assertEquals(0, stats["gamesPlayed"])
    assertEquals(0, stats["kingPct"])
    assertEquals(0, stats["secondPct"])
    assertEquals(0, stats["thirdPct"])
    assertEquals(0, stats["kozPct"])

    assertEquals(1, db.writes)
    val written = db.merges.single()
    assertEquals(
      setOf(
        "tier", "division", "seasonId",
        "highestRankTier", "highestRankDivision",
        "placementState", "demotionShield",
        "rankedGamesPlayed",
        "rankedKingCount", "rankedSecondCount", "rankedThirdCount", "rankedKozCount",
      ),
      written.keys,
    )
    assertFalse("rp" in written, "the stored rp = 0 is present and must not be rewritten")
    assertFalse("displayName" in written)
    assertFalse("avatarInitial" in written)
    assertEquals("Nour", db.doc?.get("displayName"))
    assertEquals("N", db.doc?.get("avatarInitial"))
  }

  @Test
  fun aLegacyProfileWithRpDerivesGoldIiiAndSkippedPlacement() = runTest {
    val db = FakeDb(
      mutableMapOf(
        "displayName" to "Nour",
        "rank" to "Unranked",
        "rp" to 690,
      ),
    )

    val response = loadOnce(db, fakeRequest("u1"))

    assertEquals(true, response["migrated"])
    val profile = section(response, "profile")
    assertEquals("Gold", profile["tier"])
    assertEquals("III", profile["division"])
    assertEquals(690, profile["rp"])
    assertEquals("SKIPPED", profile["placementState"])
    assertEquals("Gold", profile["highestRankTier"])
    assertEquals("III", profile["highestRankDivision"])
    assertEquals("s1", profile["seasonId"])
    assertEquals(690, profile["rp"])
    assertEquals(1, db.writes)
    assertFalse("rp" in db.merges.single(), "the present rp is not a missing field")
  }

  // ── S64: the profile's season against the current one ───────────────

  @Test
  fun aLegacyProfileWithoutASeasonAdoptsTheCurrentSeasonUnderALazyMerge() = runTest {
    val db = FakeDb(
      validProfile().apply { remove("seasonId") },
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "ACTIVE"),
    )

    val response = loadOnce(db, fakeRequest("u1"))

    // A profile written before seasons existed becomes the CURRENT season —
    // merged, reported, migrated=true, and nothing else touched.
    assertEquals(true, response["migrated"])
    val profile = section(response, "profile")
    assertEquals("s2", profile["seasonId"])
    assertEquals(1, db.writes)
    assertEquals(mapOf("seasonId" to "s2"), db.merges.single())
    assertEquals("Nour", db.doc?.get("displayName"), "identity fields are nobody's business but the owner's")
    assertEquals(732, db.doc?.get("rp"))
    // The read order is the contract: profile → season → merge.
    assertEquals(listOf("read", "season", "merge"), db.events)
  }

  @Test
  fun aProfileOnTheCurrentSeasonIsServedWithoutAnySeasonWrite() = runTest {
    val db = FakeDb(
      validProfile().apply { put("seasonId", "s2") },
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "ACTIVE"),
    )

    val response = loadOnce(db, fakeRequest("u1"))

    // Already stamped with the current season: served as-is, no merge at all.
    assertEquals(false, response["migrated"])
    assertEquals("s2", section(response, "profile")["seasonId"])
    assertEquals(0, db.writes)
    assertEquals(listOf("read", "season"), db.events)
  }

  @Test
  fun aStaleProfileSeasonIsLazilyResetOnceIntoTheCurrentSeason() = runTest {
    val db = FakeDb(
      validProfile(), // carries seasonId = "s1"
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "ACTIVE"),
    )

    val response = loadOnce(db, fakeRequest("u1"))

    // Q22 (V1-C10): a missed bulk sweep is repaired on first access — ONE
    // reset inside the same transaction, never a silent re-stamp and never a
    // second failure. Gold II takes the two-step demotion to Silver I at its
    // floor; the season advances; Highest Rank never lowers.
    assertEquals(true, response["migrated"])
    val profile = section(response, "profile")
    assertEquals("Silver", profile["tier"])
    assertEquals("I", profile["division"])
    assertEquals(450, profile["rp"])
    assertEquals("s2", profile["seasonId"])
    assertEquals("Gold", profile["highestRankTier"])
    assertEquals("II", profile["highestRankDivision"])
    assertEquals("SKIPPED", profile["placementState"])
    assertEquals(false, profile["demotionShield"])

    // The migration is ONE merge of exactly the six progression fields — the
    // bulk sweep's own list — and nothing else: career statistics, placement,
    // the shield and the identity fields are carried over by not being named.
    assertEquals(1, db.writes)
    assertEquals(
      mapOf(
        "tier" to "Silver",
        "division" to "I",
        "rp" to 450,
        "seasonId" to "s2",
        "highestRankTier" to "Gold",
        "highestRankDivision" to "II",
      ),
      db.merges.single(),
    )
    // The read order is the contract: profile → season → merge.
    assertEquals(listOf("read", "season", "merge"), db.events)
    assertEquals(12, db.doc?.get("rankedGamesPlayed"))
    assertEquals("Nour", db.doc?.get("displayName"))
  }

  @Test
  fun aBlankProfileSeasonFailsClosedAndMergesNothing() = runTest {
    val db = FakeDb(
      validProfile().apply { put("seasonId", "   ") },
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "ACTIVE"),
    )

    assertEquals("failed-precondition", thrownCode { loadOnce(db, fakeRequest("u1")) })
    assertEquals(0, db.writes)
    assertEquals(0, db.merges.size)
    assertFalse("merge" in db.events)

    // A season field that carries the WRONG KIND of value must fail the same
    // way as a blank one: treating a non-string season as absent would run the
    // lazy re-stamp on it and silently rewrite a corrupt field.
    val nonString = FakeDb(
      validProfile().apply { put("seasonId", 7) },
      currentSeasonDoc = linkedMapOf<String, Any?>("seasonId" to "s2", "status" to "ACTIVE"),
    )
    assertEquals("failed-precondition", thrownCode { loadOnce(nonString, fakeRequest("u1")) })
    assertEquals(0, nonString.writes)
    assertEquals(0, nonString.merges.size)
    assertFalse("merge" in nonString.events)
  }

  // ── failing closed ───────────────────────────────────────────────────

  @Test
  fun aMissingProfileFailsPreconditionAndCreatesNothing() = runTest {
    val db = FakeDb(null)

    assertPreconditionNoWrite(db) { loadOnce(db, fakeRequest("u1")) }
    assertEquals(1, db.txRuns)
    assertEquals(1, db.reads)
    assertEquals(listOf("read"), db.events, "a missing profile is reported without opening the season singleton")
  }

  @Test
  fun nonNumericAndNegativeRpFailPreconditionWithoutWrite() = runTest {
    val nonNumeric = FakeDb(validProfile().apply { put("rp", "690") })
    assertPreconditionNoWrite(nonNumeric) { loadOnce(nonNumeric, fakeRequest("u1")) }

    val negative = FakeDb(validProfile().apply { put("rp", -1) })
    assertPreconditionNoWrite(negative) { loadOnce(negative, fakeRequest("u1")) }

    val fractional = FakeDb(validProfile().apply { put("rp", 690.5) })
    assertPreconditionNoWrite(fractional) { loadOnce(fractional, fakeRequest("u1")) }
  }

  @Test
  fun invalidTierDivisionPairsFailPreconditionWithoutWrite() = runTest {
    val foreignRung = FakeDb(validProfile().apply {
      put("tier", "Gold")
      put("division", "IV")
    })
    assertPreconditionNoWrite(foreignRung) { loadOnce(foreignRung, fakeRequest("u1")) }

    val onlyTier = FakeDb(validProfile().apply { remove("division") })
    assertPreconditionNoWrite(onlyTier) { loadOnce(onlyTier, fakeRequest("u1")) }

    val onlyDivision = FakeDb(validProfile().apply { remove("tier") })
    assertPreconditionNoWrite(onlyDivision) { loadOnce(onlyDivision, fakeRequest("u1")) }
  }

  @Test
  fun missingRpWithAHigherRungFailsInsteadOfAwardingZero() = runTest {
    val db = FakeDb(validProfile().apply { remove("rp") })

    assertPreconditionNoWrite(db) { loadOnce(db, fakeRequest("u1")) }
  }

  @Test
  fun anInvalidPlacementStateFailsPreconditionWithoutWrite() = runTest {
    val db = FakeDb(validProfile().apply { put("placementState", "DONE") })

    assertPreconditionNoWrite(db) { loadOnce(db, fakeRequest("u1")) }
  }

  @Test
  fun anUnauthenticatedRequestFailsBeforeTheTransaction() = runTest {
    val db = FakeDb(validProfile())

    assertEquals("unauthenticated", thrownCode { loadOnce(db, fakeRequest(null)) })
    assertEquals(0, db.txRuns)
    assertEquals(0, db.writes)
  }

  // ── RD3 progression invariant ────────────────────────────────────────

  @Test
  fun aStoredPairThatDisagreesWithRpFailsPreconditionWithoutWrite() = runTest {
    // rp 150 resolves to Bronze I on the ladder, not the stored Gold II.
    val rpBelowPair = FakeDb(validProfile().apply { put("rp", 150) })
    assertPreconditionNoWrite(rpBelowPair) { loadOnce(rpBelowPair, fakeRequest("u1")) }

    // rp 732 resolves to Gold II on the ladder, not the stored Bronze III.
    val rpAbovePair = FakeDb(validProfile().apply {
      put("tier", "Bronze")
      put("division", "III")
    })
    assertPreconditionNoWrite(rpAbovePair) { loadOnce(rpAbovePair, fakeRequest("u1")) }
  }

  @Test
  fun aConsistentKingProfileIsServedAndWritesNothing() = runTest {
    val db = FakeDb(
      mutableMapOf(
        "displayName" to "Nour",
        "tier" to "King",
        "division" to "",
        "rp" to 3000,
        "seasonId" to "s1",
        "highestRankTier" to "King",
        "highestRankDivision" to "",
        "placementState" to "SKIPPED",
        "demotionShield" to false,
        "rankedGamesPlayed" to 5,
        "rankedKingCount" to 2,
        "rankedSecondCount" to 2,
        "rankedThirdCount" to 1,
        "rankedKozCount" to 0,
      ),
    )

    val response = loadOnce(db, fakeRequest("u1"))

    // Every field present and the King/"":3000 invariant holds: served as-is,
    // nothing migrated — the top rung must not be refused as a mismatch.
    assertEquals(false, response["migrated"])
    assertEquals(true, response["ok"])
    val profile = section(response, "profile")
    assertEquals("King", profile["tier"])
    assertEquals("", profile["division"])
    assertEquals(3000, profile["rp"])
    assertEquals("King", profile["highestRankTier"])
    assertEquals("", profile["highestRankDivision"])
    assertEquals("SKIPPED", profile["placementState"])
    assertEquals(false, profile["demotionShield"])
    assertEquals(0, db.writes)
  }

  @Test
  fun aKingPairWithRpBelowItsLowerBoundFailsPreconditionWithoutWrite() = runTest {
    // rp 2999 resolves to Royal I, one rung below the stored King.
    val db = FakeDb(validProfile().apply {
      put("tier", "King")
      put("division", "")
      put("rp", 2999)
      put("highestRankTier", "King")
      put("highestRankDivision", "")
    })

    assertPreconditionNoWrite(db) { loadOnce(db, fakeRequest("u1")) }
  }

  // ── highest-ever repair ──────────────────────────────────────────────

  @Test
  fun aStoredHighestBelowTheCurrentRungIsRaisedAndWritten() = runTest {
    val db = FakeDb(validProfile().apply {
      put("highestRankTier", "Silver")
      put("highestRankDivision", "I")
    })

    val response = loadOnce(db, fakeRequest("u1"))

    assertEquals(true, response["migrated"])
    val profile = section(response, "profile")
    assertEquals("Gold", profile["highestRankTier"])
    assertEquals("II", profile["highestRankDivision"])
    assertEquals(1, db.writes)
    assertEquals(
      mapOf("highestRankTier" to "Gold", "highestRankDivision" to "II"),
      db.merges.single(),
    )
  }

  @Test
  fun aHigherStoredHighestIsNeverLoweredOrRewritten() = runTest {
    val db = FakeDb(validProfile().apply {
      put("highestRankTier", "Platinum")
      put("highestRankDivision", "I")
    })

    val response = loadOnce(db, fakeRequest("u1"))

    assertEquals(false, response["migrated"])
    assertEquals("Platinum", section(response, "profile")["highestRankTier"])
    assertEquals("I", section(response, "profile")["highestRankDivision"])
    assertEquals(0, db.writes)
  }

  // ── percentage math ──────────────────────────────────────────────────

  @Test
  fun zeroGamesDeriveZeroPercentagesRatherThanDividingByZero() = runTest {
    val db = FakeDb(validProfile().apply {
      put("rankedGamesPlayed", 0)
      put("rankedKingCount", 0)
      put("rankedSecondCount", 0)
      put("rankedThirdCount", 0)
      put("rankedKozCount", 0)
    })

    val response = loadOnce(db, fakeRequest("u1"))

    val stats = section(response, "stats")
    assertEquals(0, stats["gamesPlayed"])
    assertEquals(0, stats["kingPct"])
    assertEquals(0, stats["secondPct"])
    assertEquals(0, stats["thirdPct"])
    assertEquals(0, stats["kozPct"])
    assertEquals(0, db.writes)
  }

  @Test
  fun aMigrationWithOnlyStatisticsMissingWritesOnlyThoseFields() = runTest {
    val db = FakeDb(validProfile().apply {
      remove("rankedGamesPlayed")
      remove("rankedKingCount")
      remove("rankedSecondCount")
      remove("rankedThirdCount")
      remove("rankedKozCount")
    })

    val response = loadOnce(db, fakeRequest("u1"))

    assertEquals(true, response["migrated"])
    assertEquals(
      setOf(
        "rankedGamesPlayed",
        "rankedKingCount", "rankedSecondCount", "rankedThirdCount", "rankedKozCount",
      ),
      db.merges.single().keys,
    )
    // Absent counters default to 0; the write initialises exactly those zeros.
    assertEquals(0, section(response, "stats")["gamesPlayed"])
    assertFalse("displayName" in db.merges.single())
  }

  // ── the fake port ────────────────────────────────────────────────────

  /**
   * One document behind one transaction. The doc is updated by [merge] so a
   * second transaction in the same test would see the committed result; each
   * test uses one call, and the write log is what assertions inspect.
   *
   * [currentSeasonDoc] models S59's `seasons/current` the way the Admin Tx
   * would hand it over — the [NO_SEASON_DOC] marker means no document, which
   * is Tx's own defaulted (bootstrap) answer and every pre-S64 fake's path.
   * The [events] log records each Tx touch in the order loadOnce performed
   * it, which is the mechanical pin for the profile → season → merge order.
   */
  private class FakeDb(
    var doc: Map<String, Any?>?,
    private val currentSeasonDoc: Any? = NO_SEASON_DOC,
  ) : RankedProfileDb {
    var txRuns = 0
      private set
    var reads = 0
      private set
    var writes = 0
      private set
    val merges = mutableListOf<Map<String, Any?>>()
    val events = mutableListOf<String>()

    override suspend fun <T> transact(block: suspend (RankedProfileDb.Tx) -> T): T {
      txRuns++
      return block(object : RankedProfileDb.Tx {
        override suspend fun read(uid: String): Map<String, Any?>? {
          events += "read"
          reads++
          return doc
        }

        override suspend fun currentLazyResetSeason(): String? {
          events += "season"
          if (currentSeasonDoc == NO_SEASON_DOC) return super<RankedProfileDb.Tx>.currentLazyResetSeason()
          return lazyResetSeasonOf(exists = true, data = dynamicToKotlin(currentSeasonDoc))
        }

        override fun merge(uid: String, fields: Map<String, Any?>) {
          events += "merge"
          writes++
          merges += fields
          doc = (doc ?: emptyMap()) + fields
        }
      })
    }
  }
}
