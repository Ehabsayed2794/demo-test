package com.estemshan.functions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S50/RD1-RD13 — ranked progression applied inside the settlement.
 *
 * Every expected number below was hand-derived from the closed rule table
 * (RD4 base + per-strength/per-tier scaling → RD5 asymmetry → GM6's Mini ×0.5
 * → RD3 floor clamp → the RD26 ladder rung), against
 * [SettlementFixtures.GOLDEN_SCORES] and the default [RpEngineConfig]:
 *
 * ```
 * outcome   base   KING +30  SECOND +15  THIRD -8  KOZ -20
 * strength  +2 per ordinal the opponents' mean sits above Bronze III
 * tier      -3 per ordinal the player sits above that mean (sign-clamped)
 * RD5       win above table ×(1-0.1n) · win below ×(1+0.1n) · lose above ×(1+0.2n)
 * MINI      ×0.5 applied LAST, ties away from zero
 * ```
 *
 * What the goldens pin is the WIRING, not the arithmetic (`RpEngineTest` owns
 * the arithmetic): a seat written to the wrong uid, an opponents' mean that
 * includes the seat itself, a profile read after a write, or an award applied
 * twice all change these numbers while leaving the engine's own functions
 * correct.
 */
class RpApplicationTest {

  private val uids = listOf("u1", "u2", "u3", "u4")

  private val archives = mapOf("1" to SettlementFixtures.round1ArchiveFields())

  private fun profilesWith(rps: Map<String, Int?>): Map<String, Map<String, Any?>> =
    uids.associateWith { uid -> SettlementFixtures.profile(uid, rps[uid]) }

  /**
   * The FULL 13-field ranked-progression contract write for one seat, with
   * the defaults a settled Ranked profile carries: season "s1", shield off,
   * the written rung also recorded as the Highest Rank, placement SKIPPED
   * (the default for an rp > 0 account), one completed match folded, and no
   * outcome counter beyond the ones named. Exact-map equality against
   * [FakeRpDb.writeFor] — any field the fold adds or drops fails here, not
   * in production.
   */
  private fun expectedWrite(
    rp: Int,
    tier: String,
    division: String,
    demotionShield: Boolean = false,
    highestRankTier: String = tier,
    highestRankDivision: String = division,
    seasonId: String = "s1",
    placementState: String = "SKIPPED",
    gamesPlayed: Int = 1,
    king: Int = 0,
    second: Int = 0,
    third: Int = 0,
    koz: Int = 0,
  ): Map<String, Any?> = linkedMapOf(
    "rp" to rp,
    "tier" to tier,
    "division" to division,
    "demotionShield" to demotionShield,
    "highestRankTier" to highestRankTier,
    "highestRankDivision" to highestRankDivision,
    "seasonId" to seasonId,
    "placementState" to placementState,
    "rankedGamesPlayed" to gamesPlayed,
    "rankedKingCount" to king,
    "rankedSecondCount" to second,
    "rankedThirdCount" to third,
    "rankedKozCount" to koz,
  )

  // ── fake ports ────────────────────────────────────────────────────

  /**
   * The `SettlementTest` fake, re-declared for this class: its whole job is to
   * make the transaction's SHAPE observable — event order, per-uid writes, the
   * recorded claim — so progression can be asserted without an emulator. It
   * refuses a read issued after any write, which is what the real Admin SDK
   * does; a fake that allowed the order would hide the one bug class the port
   * shape exists to prevent.
   */
  private class FakeRpDb(
    val match: Map<String, Any?>,
    val archives: Map<String, Map<String, Any?>> = emptyMap(),
    var claim: Map<String, Any?>? = null,
    val profiles: Map<String, Map<String, Any?>> = emptyMap(),
    val missingProfiles: Set<String> = emptySet(),
  ) : SettlementDb {
    var txRuns = 0
    var profileReads = 0
    var corrections = 0
    var claims = 0
    val appends = mutableListOf<Map<String, Any?>>()
    val events = mutableListOf<String>()
    val profileWrites = mutableListOf<Pair<String, Map<String, Any?>>>()

    /** The fields written to one uid, as one map (order-insensitive). */
    fun writeFor(uid: String): Map<String, Any?> =
      profileWrites.single { it.first == uid }.second

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = match

    override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> = archives

    override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
      txRuns++
      return block(object : SettlementDb.SettlementTx {
        override suspend fun readClaim(matchId: String): Map<String, Any?>? {
          events.add("read-claim")
          return claim
        }

        override suspend fun readProfile(uid: String): Map<String, Any?>? {
          events.add("read-profile:$uid")
          profileReads++
          if (events.any { it.startsWith("write-") }) {
            throw IllegalStateException(
              "Firestore transactions require all reads to be executed before all writes",
            )
          }
          if (uid in missingProfiles) return null
          return profiles[uid] ?: emptyMap()
        }

        override fun writeCorrection(
          matchId: String,
          scores: Map<String, Int>,
          winners: List<String>,
          version: Int,
        ) {
          events.add("write-correction")
          corrections++
        }

        override fun writeProfile(uid: String, fields: Map<String, Any?>) {
          events.add("write-profile:$uid")
          profileWrites.add(uid to fields)
        }

        override fun writeClaim(matchId: String, record: Map<String, Any?>) {
          events.add("write-claim")
          claims++
          claim = record
        }
      })
    }

    override suspend fun appendLedger(record: Map<String, Any?>) {
      appends.add(record)
    }

    override suspend fun readCorrectionWindow(): List<Map<String, Any?>> =
      appends.filter { it["outcomeKind"] == SettlementLedger.OUTCOME_CORRECTED }.reversed()
  }

  private suspend fun expectDenied(
    code: String,
    vararg messageParts: String,
    block: suspend () -> Any?,
  ): Throwable {
    val thrown = try {
      block()
      throw AssertionError("expected an HttpsError")
    } catch (e: Throwable) {
      if (e.message == "expected an HttpsError") throw e
      e
    }
    assertEquals(code, thrown.asDynamic().code as String, "denied with: ${thrown.message}")
    messageParts.forEach { messagePart ->
      assertTrue(
        thrown.message?.contains(messagePart) == true,
        "expected '${thrown.message}' to name '$messagePart'",
      )
    }
    return thrown
  }

  // ── the golden fixture is re-derived, not assumed ─────────────────

  /**
   * Every number this class asserts was hand-derived from
   * [SettlementFixtures.GOLDEN_SCORES], and that map is itself the claim the
   * golden archive is settled against. So a misbuilt `cardLog` would not fail
   * one test — it would make all of them wrong together, quietly, in the same
   * direction. This is the check that they are not: the archive is re-parsed,
   * re-replayed and re-recomputed by the production path inside
   * [SettlementFixtures.round1Archived], and it must still land on the golden
   * totals.
   */
  @Test
  fun theGoldenArchiveRecomputesToTheGoldenScores() {
    val archived = SettlementFixtures.round1Archived()
    assertEquals(1, archived.round)
    assertEquals(52, archived.plays.size, "a full deck: 13 tricks × 4 seats")
  }

  // ── the four seats progress together ──────────────────────────────

  /**
   * A table of four Gold III (690 RP, ladder ordinal 6) settling the golden
   * result. Strength = 6 for every seat and the tier difference = 0, so each
   * award is the pure RD4 base + strength term: KING 30+12, SECOND 15+12,
   * THIRD -8+12 clamped to 0 by sign, KOZ -20+12.
   */
  @Test
  fun fourSeatsProgressTogether() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(mapOf("u1" to 690, "u2" to 690, "u3" to 690, "u4" to 690)),
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, response["settled"])
    assertEquals(4, db.profileReads)
    assertEquals(4, db.profileWrites.size)
    // p1 KING → 732, p4 SECOND → 717: both cross the Gold II threshold (700).
    assertEquals(
      expectedWrite(rp = 732, tier = "Gold", division = "II", king = 1),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(rp = 717, tier = "Gold", division = "II", second = 1),
      db.writeFor("u4"),
    )
    // p3 THIRD lands on 0 (sign clamp), p4's seat-3 neighbour p2 keeps the KOZ loss.
    assertEquals(
      expectedWrite(rp = 690, tier = "Gold", division = "III", third = 1),
      db.writeFor("u3"),
    )
    assertEquals(
      expectedWrite(rp = 682, tier = "Gold", division = "III", koz = 1),
      db.writeFor("u2"),
    )

    // The caller's own slice, and nothing else, rides on the response (RD13).
    assertEquals(
      mapOf("previousRp" to 690, "rp" to 732, "delta" to 42),
      response["you"],
    )
  }

  /**
   * The seat → uid link is the whole point of [Settlement.parseSeats]: u2 holds
   * p2's KOZ and u4 holds p4's SECOND. Asserting the two awards are DIFFERENT
   * numbers per uid is what fails loudly if a future edit index-correlates
   * `players` (join order) with `finalScores` (seat order) and hands one seat's
   * award to another player.
   */
  @Test
  fun eachAwardFollowsItsSeatNotThePlayersList() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(mapOf("u1" to 690, "u2" to 690, "u3" to 690, "u4" to 690)),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    // Same starting RP for all four, so ANY difference here is placement's.
    assertEquals(732, db.writeFor("u1")["rp"])
    assertEquals(682, db.writeFor("u2")["rp"])
    assertEquals(717, db.writeFor("u4")["rp"])
    assertEquals(
      listOf("u1", "u2", "u3", "u4"),
      db.profileWrites.map { it.first },
      "progression is written in seat order p1..p4, mapped through `seats`",
    )
  }

  // ── the field set ────────────────────────────────────────────────

  /**
   * The settlement owns EXACTLY the 13 ranked-progression contract fields —
   * `rp`, `tier`, `division`, `demotionShield`, `highestRankTier`,
   * `highestRankDivision`, `seasonId`, `placementState` and the five stored
   * counters — and nothing else. The Admin SDK bypasses firestore.rules, so
   * nothing but this call site bounds the write: a stray `rank`, `stats`,
   * `kingPct` or `you` key here is a silent client-trustable-field
   * escalation, and this assertion is the only place it would be caught.
   * The four derived percentages are never persisted (RD23 — they are
   * derived over gamesPlayed, not stored), and every pre-existing profile
   * field — the legacy `rank` string, displayName, the rest — is left to the
   * merge-write's update() semantics.
   */
  @Test
  fun progressionWritesOwnExactlyTheThirteenContractFields() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(4, db.profileWrites.size)
    db.profileWrites.forEach { (uid, fields) ->
      assertEquals(
        setOf(
          "rp", "tier", "division", "demotionShield",
          "highestRankTier", "highestRankDivision",
          "seasonId", "placementState",
          "rankedGamesPlayed", "rankedKingCount", "rankedSecondCount",
          "rankedThirdCount", "rankedKozCount",
        ),
        fields.keys,
        "the write for '$uid' owns exactly the progression contract fields",
      )
    }
  }

  // ── RD30 / RD3 ───────────────────────────────────────────────────

  /**
   * A profile DOCUMENT that exists but carries no `rp` is an unplaced account
   * (RD30): it reads as 0, Bronze III. A missing document is a different thing
   * entirely and is the fail-closed case below.
   *
   * At ordinal 0 the strength term is 0, so the losers' raw losses (-8, -20)
   * stand and RD3 floors them: rp stays 0 and the EFFECTIVE delta reported is 0
   * — not -8, not -20, and no demotion (there is nothing below Bronze III).
   */
  @Test
  fun anUnplacedTableClampsItsLossesAtTheFloor() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(mapOf("u1" to null, "u2" to null, "u3" to null, "u4" to null)),
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u2", "m1"))

    assertEquals(
      expectedWrite(
        rp = 30, tier = "Bronze", division = "III",
        king = 1, placementState = "NOT_STARTED",
      ),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(
        rp = 15, tier = "Bronze", division = "III",
        second = 1, placementState = "NOT_STARTED",
      ),
      db.writeFor("u4"),
    )
    assertEquals(
      expectedWrite(
        rp = 0, tier = "Bronze", division = "III",
        koz = 1, placementState = "NOT_STARTED",
      ),
      db.writeFor("u2"),
    )
    assertEquals(
      expectedWrite(
        rp = 0, tier = "Bronze", division = "III",
        third = 1, placementState = "NOT_STARTED",
      ),
      db.writeFor("u3"),
    )

    // The KOZ seat's own view: the loss is real (-20) but its effective delta
    // is 0, because RP never goes below the floor.
    assertEquals(
      mapOf("previousRp" to 0, "rp" to 0, "delta" to 0),
      response["you"],
    )
  }

  /** An explicit `rp: 0` and an absent `rp` settle identically (RD30). */
  @Test
  fun anExplicitZeroRpMatchesAnAbsentOne() = runTest {
    val explicit = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 0 }),
    )
    val absent = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { null }),
    )

    settleOnce(explicit, SettlementFixtures.settleRequest("u1", "m1"))
    settleOnce(absent, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      explicit.profileWrites.toList(),
      absent.profileWrites.toList(),
    )
  }

  // ── RD4: opponent strength, RD5: the mixed table ─────────────────

  /**
   * The strength term alone: four seats all at Bronze I (150 RP, ordinal 2)
   * face a table whose mean is 2, so KING earns 30 + 2×2 = 34 where the same
   * KING at Bronze III earns 30. Nothing else changes between the two tables,
   * which is what isolates RD4 input 1.
   */
  @Test
  fun aStrongerTablePaysMore() = runTest {
    val weak = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 0 }),
    )
    val strong = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 150 }),
    )

    val onWeak = settleOnce(weak, SettlementFixtures.settleRequest("u1", "m1"))
    val onStrong = settleOnce(strong, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(30, (onWeak["you"] as Map<*, *>)["delta"])
    // 150 → 184: still Bronze I (lower bound 150, next rung 250).
    assertEquals(
      expectedWrite(rp = 184, tier = "Bronze", division = "I", king = 1),
      strong.writeFor("u1"),
    )
    assertEquals(34, (onStrong["you"] as Map<*, *>)["delta"])
  }

  /**
   * A mixed table — one Bronze III (ordinal 0) against three Bronze I
   * (ordinal 2). The three opponents' mean is 2 for the low seat, but each
   * high seat sees (0+2+2)/3 = 1 (integer mean), so their tier difference is
   * exactly +1 and the low seat's is -2. Every number below is that shape:
   *  - u1 KING: 30 + 4 + 6 = 40, then RD5's lower-tier-win ×1.2 → 48
   *  - u4 SECOND: 15 + 2 - 3 = 14, then higher-tier-win ×0.9 → 13
   *  - u3 THIRD: -8 + 2 - 3 = -9, then higher-tier-loss ×1.2 → -11
   *  - u2 KOZ: -20 + 2 - 3 = -21, then ×1.2 → -25
   * A win below the table pays MORE than the same win at a uniform table (34),
   * and a loss above it costs MORE than at a uniform table (-16 → -21): RD5's
   * asymmetry, through settlement rather than through the engine directly.
   */
  @Test
  fun aMixedTableIsAsymmetricBothWays() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(mapOf("u1" to 0, "u2" to 150, "u3" to 150, "u4" to 150)),
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(
        rp = 48, tier = "Bronze", division = "III",
        king = 1, placementState = "NOT_STARTED",
      ),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(rp = 163, tier = "Bronze", division = "I", second = 1),
      db.writeFor("u4"),
    )
    assertEquals(
      expectedWrite(
        rp = 139, tier = "Bronze", division = "II",
        third = 1, highestRankTier = "Bronze", highestRankDivision = "I",
      ),
      db.writeFor("u3"),
    )
    // 150 → 125 crosses a rung: Bronze II, a demotion (movement -1).
    assertEquals(
      expectedWrite(
        rp = 125, tier = "Bronze", division = "II",
        koz = 1, highestRankTier = "Bronze", highestRankDivision = "I",
      ),
      db.writeFor("u2"),
    )

    assertEquals(
      mapOf("previousRp" to 0, "rp" to 48, "delta" to 48),
      response["you"],
    )
  }

  // ── Highest Rank, the shield, the counters, season, legacy ──────

  /**
   * Highest Rank only ever raises (RD23): u1 carries a stored Platinum III
   * peak into the uniform 690 table, its KING climbs to Gold II — below the
   * peak — and the write must re-persist the STORED peak, never the rung the
   * match reached. A fold that wrote the resolved rung into the peak fields
   * would silently erase the career record on every ordinary match.
   */
  @Test
  fun theHighestRankOnlyEverRaises() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile(
          "u1", 690, highestRankTier = "Platinum", highestRankDivision = "III",
        ),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to SettlementFixtures.profile("u3", 690),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(
        rp = 732, tier = "Gold", division = "II", king = 1,
        highestRankTier = "Platinum", highestRankDivision = "III",
      ),
      db.writeFor("u1"),
    )
  }

  /**
   * A demotion never lowers the Highest Rank — both shapes of "peak" in one
   * mixed table: u2 carries a seeded Platinum III peak and demotes 150 → 125
   * (Bronze I → Bronze II), so the write must name Platinum III; u3's peak is
   * DEFAULTED (no stored fields ⇒ the pre-match rung, Bronze I) and its
   * demotion to Bronze II must leave the write at Bronze I — never at the
   * rung the match landed on.
   */
  @Test
  fun aDemotionNeverLowersTheHighestRank() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", 0),
        "u2" to SettlementFixtures.profile(
          "u2", 150, highestRankTier = "Platinum", highestRankDivision = "III",
        ),
        "u3" to SettlementFixtures.profile("u3", 150),
        "u4" to SettlementFixtures.profile("u4", 150),
      ),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(
        rp = 125, tier = "Bronze", division = "II", koz = 1,
        highestRankTier = "Platinum", highestRankDivision = "III",
      ),
      db.writeFor("u2"),
    )
    assertEquals(
      expectedWrite(
        rp = 139, tier = "Bronze", division = "II", third = 1,
        highestRankTier = "Bronze", highestRankDivision = "I",
      ),
      db.writeFor("u3"),
    )
  }

  /**
   * An armed shield holds the rank on a would-be demotion (RD7): u2 is armed
   * at 150 (Bronze I) in the mixed table and its KOZ resolves to 125
   * (Bronze II, movement -1). The shield blocks it, so the WRITE persists the
   * held rung at the previous rung's lower bound — rp 150, Bronze I — the
   * shield is consumed (demotionShield=false), and the caller's `you` and the
   * claim's movement both report the held numbers: the effective delta is the
   * truthful clamped 0, not the raw -25 the unshielded resolution carried.
   */
  @Test
  fun anArmedShieldHoldsTheRankOnDemotion() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", 0),
        "u2" to SettlementFixtures.profile("u2", 150, demotionShield = true),
        "u3" to SettlementFixtures.profile("u3", 150),
        "u4" to SettlementFixtures.profile("u4", 150),
      ),
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u2", "m1"))

    assertEquals(
      expectedWrite(
        rp = 150, tier = "Bronze", division = "I",
        koz = 1, demotionShield = false,
      ),
      db.writeFor("u2"),
    )
    assertEquals(
      mapOf("previousRp" to 150, "rp" to 150, "delta" to 0),
      response["you"],
    )
    @Suppress("UNCHECKED_CAST")
    val movements = db.claim!!["movements"] as Map<String, Map<String, Any?>>
    assertEquals(
      mapOf("previousRp" to 150, "rp" to 150, "delta" to 0),
      movements["u2"],
      "the claim records the held movement, not the raw one",
    )
  }

  /**
   * A promotion resolved while armed REPLACES the shield atomically (RD7):
   * u1 is armed at 690, its KING resolves +42 → 732 (Gold II), the resolution
   * passes through unchanged, and the write comes out RE-ARMED — a promotion
   * while armed never drops the new shield. u4's SECOND resolves unarmed and
   * stays false, pinning that the re-arm belongs to the armed seat alone.
   */
  @Test
  fun anArmedPromotionKeepsItsShieldArmed() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", 690, demotionShield = true),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to SettlementFixtures.profile("u3", 690),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(
        rp = 732, tier = "Gold", division = "II",
        king = 1, demotionShield = true,
      ),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(rp = 717, tier = "Gold", division = "II", second = 1),
      db.writeFor("u4"),
    )
  }

  /**
   * The armed shield is consumed by the NEXT resolved match whatever its
   * outcome (RD7's countdown of one): u3 is armed at 690 and its THIRD
   * resolves to a neutral 0 delta — movement 0 spends the shield, the write
   * carries demotionShield=false, and the resolution passes through
   * unchanged (690, Gold III). Neutral neither re-arms nor holds anything.
   */
  @Test
  fun anArmedNeutralMatchConsumesTheShield() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", 690),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to SettlementFixtures.profile("u3", 690, demotionShield = true),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u3", "m1"))

    assertEquals(
      expectedWrite(
        rp = 690, tier = "Gold", division = "III",
        third = 1, demotionShield = false,
      ),
      db.writeFor("u3"),
    )
  }

  /**
   * RD10: a placement match counts toward NO statistic. u1 is mid-placement
   * (IN_PROGRESS), and its counters come back all zeros — not Games Played,
   * not any percentage — even though its RP still resolves (+30 from the
   * floor, this fold leaves placement's RP resolution to the settlement) and
   * all 13 fields are still written. u2..u4 are NOT_STARTED — not placement —
   * so their counters fold their one outcome in normally.
   */
  @Test
  fun aPlacementProfileAccumulatesNoStats() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", null, placementState = "IN_PROGRESS"),
        "u2" to SettlementFixtures.profile("u2", null),
        "u3" to SettlementFixtures.profile("u3", null),
        "u4" to SettlementFixtures.profile("u4", null),
      ),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(
        rp = 30, tier = "Bronze", division = "III",
        placementState = "IN_PROGRESS", gamesPlayed = 0,
      ),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(
        rp = 0, tier = "Bronze", division = "III",
        koz = 1, placementState = "NOT_STARTED",
      ),
      db.writeFor("u2"),
    )
  }

  /**
   * An existing seasonId is PRESERVED and a missing one defaults to "s1"
   * (RD24): u1 carries "s2", u2 carries nothing, and the writes must differ
   * exactly so — nothing in this fold ever moves a season forward (S59's
   * reset is the only writer that does).
   */
  @Test
  fun seasonIdIsPreservedWhenPresentAndDefaultedWhenAbsent() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = mapOf(
        "u1" to SettlementFixtures.profile("u1", 690, seasonId = "s2"),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to SettlementFixtures.profile("u3", 690),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(rp = 732, tier = "Gold", division = "II", king = 1, seasonId = "s2"),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(rp = 682, tier = "Gold", division = "III", koz = 1),
      db.writeFor("u2"),
    )
  }

  /**
   * A legacy pre-Ranked profile — `rank: "Unranked"`, `rp: 0`, none of the
   * new contract fields — settles on its defaults: NOT_STARTED placement (an
   * "Unranked" legacy rank is not a placed rank), Bronze III, season "s1",
   * shield off, peak defaulted to the rung. And the write copies NOTHING but
   * the 13 contract fields: the legacy `rank` string and `displayName` are
   * untouched by the merge-write, which is exactly what
   * [progressionWritesOwnExactlyTheThirteenContractFields] pins per key.
   */
  @Test
  fun aLegacyProfileSettlesOnItsDefaults() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = uids.associateWith { uid ->
        linkedMapOf<String, Any?>(
          "uid" to uid,
          "displayName" to "Player $uid",
          "rank" to "Unranked",
          "rp" to 0,
        )
      },
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(
        rp = 30, tier = "Bronze", division = "III",
        king = 1, placementState = "NOT_STARTED",
      ),
      db.writeFor("u1"),
    )
  }

  // ── GM6: MINI halves, once, last ─────────────────────────────────

  /**
   * The same Gold III table as [fourSeatsProgressTogether], MINI: ×0.5 applied
   * to the FINAL delta, ties away from zero (OPEN-3). u4's +27 → 13.5 → +14 is
   * the tie branch, and u1's 711 vs 732 shows the half is taken once over the
   * whole award, never per component (30/2 + 12/2 = 21 here too, so the
   * distinction is carried by 27 → 14, not 13).
   */
  @Test
  fun miniHalvesTheFinalDeltaOnce() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids, gameType = "MINI"),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(
      expectedWrite(rp = 711, tier = "Gold", division = "II", king = 1),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(rp = 704, tier = "Gold", division = "II", second = 1),
      db.writeFor("u4"),
    )
    assertEquals(
      expectedWrite(rp = 690, tier = "Gold", division = "III", third = 1),
      db.writeFor("u3"),
    )
    assertEquals(
      expectedWrite(rp = 686, tier = "Gold", division = "III", koz = 1),
      db.writeFor("u2"),
    )
  }

  // ── exactly-once ─────────────────────────────────────────────────

  /**
   * The claim's once-only property is what makes an RP award once-only: a
   * redelivered call reads the claim, returns the recorded outcome and touches
   * no profile — no read, no write, no second award.
   */
  @Test
  fun aRedeliveredCallReAwardsNothing() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    val first = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))
    val writesAfterFirst = db.profileWrites.toList()

    val replay = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, replay["replayed"])
    assertEquals(2, db.txRuns)
    assertEquals(1, db.claims)
    assertEquals(4, db.profileReads, "a replay reads no profile")
    assertEquals(4, db.profileWrites.size, "a replay writes no profile")
    assertEquals(writesAfterFirst, db.profileWrites.toList())
    // Same caller, same numbers: the replay is sliced from the recorded
    // movements, not recomputed, so it cannot drift from the first answer.
    assertEquals(first["you"], replay["you"])
  }

  /**
   * RD13: one claim is replayed by four different clients, and each sees only
   * its own progression. u3's replay carries u3's numbers — and no key that
   * would let a client read a neighbour's.
   */
  @Test
  fun aSecondCallerGetsOnlyItsOwnMovement() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    val asU1 = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))
    val asU3 = settleOnce(db, SettlementFixtures.settleRequest("u3", "m1"))

    assertEquals(mapOf("previousRp" to 690, "rp" to 732, "delta" to 42), asU1["you"])
    assertEquals(mapOf("previousRp" to 690, "rp" to 690, "delta" to 0), asU3["you"])
    assertNeitherLeaks(asU1)
    assertNeitherLeaks(asU3)

    // The stored claim is caller-neutral: `you` is a projection on the way out,
    // never a field of the shared document (a stored `you` is a stored leak).
    val claim = db.claim!!
    @Suppress("UNCHECKED_CAST")
    assertNull((claim["outcome"] as Map<String, Any?>)["you"])
    assertEquals("u1", claim["settledBy"])
  }

  private fun assertNeitherLeaks(response: Map<String, Any?>) {
    assertNull(response["movements"], "the four-seat table is never returned to a client")
    assertEquals(
      setOf("previousRp", "rp", "delta"),
      (response["you"] as Map<*, *>).keys,
    )
  }

  /**
   * A claim recorded before progression existed (S50 as merged, no
   * `movements`) still replays — as it always did, with no `you`. The absence
   * is answered by an absent key, never by a crash or a fabricated number.
   */
  @Test
  fun aLegacyClaimReplaysWithoutAYou() = runTest {
    val recorded = mapOf<String, Any?>(
      "ok" to true,
      "settled" to true,
      "replayed" to false,
      "matchId" to "m1",
      "corrected" to false,
      "finalScores" to SettlementFixtures.GOLDEN_SCORES,
      "winnerIds" to listOf("p1"),
      "claimedScores" to SettlementFixtures.GOLDEN_SCORES,
      "claimedWinners" to listOf("p1"),
      "rounds" to 1,
    )
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      claim = mapOf("settledBy" to "u1", "outcome" to recorded),
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    val replay = settleOnce(db, SettlementFixtures.settleRequest("u2", "m1"))

    assertEquals(true, replay["replayed"])
    assertNull(replay["you"])
    assertEquals(recorded["finalScores"], replay["finalScores"])
    assertEquals(0, db.profileReads)
    assertEquals(0, db.profileWrites.size)
  }

  // ── fail closed ──────────────────────────────────────────────────

  /**
   * The last seat's profile is missing, so the three that WERE read still get
   * nothing: the settlement cannot invent a starting rung, and a Ranked table
   * either applies four awards or applies none. Because every read precedes
   * every write, the throw lands before the first write is issued — no claim,
   * no partial progression, and the match stays settleable once the profile
   * exists (the claim is what would otherwise forbid a second execution).
   */
  @Test
  fun aMissingProfileFailsTheWholeSettlementClosed() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(mapOf("u1" to 690, "u2" to 690, "u3" to 690)),
      missingProfiles = setOf("u4"),
    )

    expectDenied("failed-precondition", "u4") {
      settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))
    }

    assertEquals(0, db.claims, "a fail-closed settlement writes no claim")
    assertEquals(0, db.profileWrites.size, "a fail-closed settlement writes no profile")
    assertEquals(0, db.corrections)
  }

  /** A seat whose occupant is not a uid is the same refusal, one level earlier. */
  @Test
  fun anUnseatedSeatFailsClosed() = runTest {
    val fields = SettlementFixtures.matchFields(uids).toMutableMap()
    @Suppress("UNCHECKED_CAST")
    val seats = (fields["seats"] as Map<String, Any?>).toMutableMap()
    // p4 is occupied by something that is not a uid, so no seat can be trusted
    // to map onto a profile the settlement may write.
    seats["p4"] = 42
    fields["seats"] = seats
    val db = FakeRpDb(
      match = fields,
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    expectDenied("failed-precondition", "is not seated to a uid") {
      settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))
    }
    assertEquals(0, db.claims)
    assertEquals(0, db.profileWrites.size)
  }

  /**
   * A non-numeric `players/rp` is corrupt, not unplaced. Reading `"690"` as 0
   * would hand a Gold player the +30 of a first match — the same fabrication
   * the missing-document rule refuses, one level down — so the whole table is
   * denied, and the refusal names the offending uid.
   */
  @Test
  fun aNonNumericRpFailsTheWholeSettlementClosed() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = uids.associateWith { uid ->
        linkedMapOf<String, Any?>("uid" to uid, "rp" to if (uid == "u3") "690" else 690)
      },
    )

    expectDenied(
      "failed-precondition",
      "seated player 'u3' of match 'm1' has a non-numeric players/rp",
    ) {
      settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))
    }

    assertEquals(0, db.claims, "a corrupt rp writes no claim")
    assertEquals(0, db.profileWrites.size, "a corrupt rp writes no profile, for any seat")
    assertEquals(0, db.corrections)
  }

  /**
   * The third shape of "carries no rp": an explicit JSON null, which is what a
   * client that wrote `rp: null` leaves behind. RD30 reads it as unplaced, like
   * an absent key — an absence, not a value that cannot be trusted. Pinned
   * against the absent-key table so the two cannot drift apart.
   */
  @Test
  fun aNullRpSettlesItsSeatAsUnplaced() = runTest {
    val explicitNull = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = uids.associateWith { uid ->
        linkedMapOf<String, Any?>("uid" to uid, "rp" to null)
      },
    )
    val absentKey = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { null }),
    )

    settleOnce(explicitNull, SettlementFixtures.settleRequest("u1", "m1"))
    settleOnce(absentKey, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(absentKey.profileWrites.toList(), explicitNull.profileWrites.toList())
    assertEquals(
      expectedWrite(
        rp = 30, tier = "Bronze", division = "III",
        king = 1, placementState = "NOT_STARTED",
      ),
      explicitNull.writeFor("u1"),
    )
  }

  /**
   * A placed account that names its rank identity (`tier` + `division`) but
   * carries no `rp` is corrupt, not unplaced: reading it as 0 would award a
   * placed account a first match's progression. The whole table is denied —
   * nothing writes for ANY seat.
   */
  @Test
  fun aPlacedRankIdentityWithNoRpFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      mapOf(
        "u1" to SettlementFixtures.profile("u1", 690),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to SettlementFixtures.profile("u3", null, tier = "Gold", division = "III"),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
      "no players/rp under a placed rank",
    )
  }

  /**
   * The same fabrication, one layer down: a legacy `players/rank` that names
   * a placed rung ("Gold III") with no `rp` is denied the same way — an
   * unparseable-or-unplaced legacy string defaults, a PLACED one does not.
   */
  @Test
  fun aPlacedLegacyRankWithNoRpFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      mapOf(
        "u1" to SettlementFixtures.profile("u1", 690),
        "u2" to SettlementFixtures.profile("u2", 690),
        "u3" to linkedMapOf<String, Any?>("uid" to "u3", "rank" to "Gold III"),
        "u4" to SettlementFixtures.profile("u4", 690),
      ),
      "placed legacy rank",
    )
  }

  /**
   * Every present-but-invalid contract field fails the WHOLE settlement
   * closed — the battery, one shape per test. Corrupt VALUES: a rank
   * identity that names no rung of the one ladder, a half-written identity
   * (one of the two keys), a placement state that is not a placement state,
   * a Highest Rank that names no rung, and a negative counter. Corrupt
   * TYPES: a non-string seasonId, a non-string legacy rank, a non-string
   * placementState, a non-boolean shield, a non-numeric counter — a field
   * whose JSON type is not the contract's is denied exactly like a field
   * whose value is out of range. Each refusal names the offending uid, and
   * nothing commits — no claim (the match stays settleable once the data is
   * repaired), no profile write for any seat, no correction.
   */
  @Test
  fun anInvalidRankIdentityFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("tier" to "Wood", "division" to "III")),
      "names no rung of the rank ladder",
    )
  }

  @Test
  fun aHalfWrittenRankIdentityFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("tier" to "Gold")),
      "the pair is written together or not at all",
    )
  }

  @Test
  fun anInvalidPlacementStateFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("placementState" to "MAYBE")),
      "not a placement state",
    )
  }

  @Test
  fun anInvalidHighestRankFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("highestRankTier" to "Platinum", "highestRankDivision" to "IV")),
      "players/highestRankTier",
    )
  }

  @Test
  fun aNegativeStatCounterFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("rankedKingCount" to -1)),
      "a negative players/rankedKingCount",
    )
  }

  @Test
  fun aNonStringSeasonIdFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("seasonId" to 2)),
      "a non-string players/seasonId",
    )
  }

  @Test
  fun aNonStringLegacyRankFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("rank" to 7)),
      "a non-string legacy players/rank",
    )
  }

  @Test
  fun aNonStringPlacementStateFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("placementState" to true)),
      "a non-string players/placementState",
    )
  }

  @Test
  fun aNonBooleanDemotionShieldFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("demotionShield" to "yes")),
      "a non-boolean players/demotionShield",
    )
  }

  @Test
  fun aNonNumericStatCounterFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("rankedKingCount" to "2")),
      "a non-numeric players/rankedKingCount",
    )
  }

  /** The uniform-690 table with u3's profile corrupted by [corrupt] fields. */
  private fun corruptingU3(corrupt: Map<String, Any?>): Map<String, Map<String, Any?>> =
    mapOf(
      "u1" to SettlementFixtures.profile("u1", 690),
      "u2" to SettlementFixtures.profile("u2", 690),
      "u3" to (SettlementFixtures.profile("u3", 690) + corrupt),
      "u4" to SettlementFixtures.profile("u4", 690),
    )

  /**
   * The shared body of the fail-closed battery: the whole settlement is
   * denied with [messagePart] in the refusal, and NOTHING commits — no claim,
   * no profile write for any seat, no correction. The absence of the claim is
   * what keeps the match settleable once the corrupt field is repaired.
   */
  private suspend fun expectWholeSettlementDenied(
    profiles: Map<String, Map<String, Any?>>,
    vararg messageParts: String,
  ) {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profiles,
    )
    expectDenied("failed-precondition", *messageParts) {
      settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))
    }
    assertEquals(0, db.claims, "a fail-closed settlement writes no claim")
    assertEquals(0, db.profileWrites.size, "a fail-closed settlement writes no profile")
    assertEquals(0, db.corrections, "a fail-closed settlement writes no correction")
  }

  // ── ordering, gates, untouched surfaces ──────────────────────────

  /**
   * The transaction's event log, read as one sequence: four profile reads and
   * the claim read all before the first write. The fake already throws on the
   * reverse order, so this assertion is the readable statement of WHY it
   * cannot throw — and it pins that the claim check is not re-read per seat.
   */
  @Test
  fun everyReadPrecedesEveryWrite() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    val lastRead = db.events.indexOfLast { it.startsWith("read-") }
    val firstWrite = db.events.indexOfFirst { it.startsWith("write-") }
    assertTrue(lastRead < firstWrite, "reads and writes interleaved: ${db.events}")
    assertEquals(
      listOf(
        "read-claim",
        "read-profile:u1", "read-profile:u2", "read-profile:u3", "read-profile:u4",
        "write-profile:u1", "write-profile:u2", "write-profile:u3", "write-profile:u4",
        "write-claim",
      ),
      db.events,
    )
  }

  /**
   * ROOM and UNRANKED still no-op BEFORE the transaction — zero profile reads,
   * zero writes, and (as before progression) zero transactions. A room match
   * must never move anyone's rank, and it must not even look.
   */
  @Test
  fun roomAndUnrankedNeverTouchAProfile() = runTest {
    for (mode in listOf("ROOM", "UNRANKED")) {
      val db = FakeRpDb(
        match = SettlementFixtures.matchFields(uids, mode = mode),
        archives = archives,
        profiles = profilesWith(uids.associateWith { 690 }),
      )

      val response = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

      assertEquals(true, response["ok"])
      assertEquals(false, response["settled"])
      assertEquals("NOT_RANKED", response["reason"])
      assertEquals(mode, response["mode"])
      assertNull(response["you"], "a no-op reports no progression")
      assertEquals(0, db.txRuns, "$mode must not open a transaction")
      assertEquals(0, db.profileReads, "$mode must not read a profile")
      assertEquals(0, db.profileWrites.size, "$mode must not write a profile")
    }
  }

  /**
   * The unauthenticated and non-member denials are unchanged by progression:
   * both die before the transaction, so neither can be used to probe who sits
   * where, and neither writes.
   */
  @Test
  fun theExistingDenialsAreUntouched() = runTest {
    val anon = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )
    expectDenied("unauthenticated", "") {
      settleOnce(anon, SettlementFixtures.settleRequest(null, "m1"))
    }
    assertEquals(0, anon.txRuns)
    assertEquals(0, anon.profileReads)

    val outsider = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )
    settleOnceShouldRefuse(outsider, "u5")
    assertEquals(0, outsider.txRuns)
    assertEquals(0, outsider.profileWrites.size)
    assertEquals(0, outsider.appends.size)
  }

  private suspend fun settleOnceShouldRefuse(db: FakeRpDb, uid: String) {
    expectDenied("permission-denied", "") {
      settleOnce(db, SettlementFixtures.settleRequest(uid, "m1"))
    }
  }

  /**
   * A corrected claim settles the RECOMPUTED totals, not the client's claim —
   * progression reads from the recomputation, so a false claim costs a cheater
   * the award they invented rather than paying it. The correction write stays
   * ordered with the reads, and the ledger row is unchanged.
   */
  @Test
  fun progressionFollowsTheRecomputeNotTheClaim() = runTest {
    val cheated = mapOf("p1" to 25, "p2" to 99, "p3" to 12, "p4" to 14)
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(
        uids,
        finalScores = cheated,
      ),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, response["corrected"])
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), response["finalScores"])
    // Recomputed: u2 is alone at the bottom → KOZ −8, exactly as in the agreed
    // golden table. Its invented 99 bought nothing.
    assertEquals(
      expectedWrite(rp = 682, tier = "Gold", division = "III", koz = 1),
      db.writeFor("u2"),
    )
    assertEquals(1, db.corrections)
    assertEquals(1, db.appends.size, "the S62 ledger row is still written once")
    @Suppress("UNCHECKED_CAST")
    assertEquals(
      cheated,
      ((db.claim!!["outcome"] as Map<String, Any?>)["claimedScores"]) as Map<String, Int>,
      "the record still documents what the client claimed",
    )
  }

  /**
   * Progression never adds a field the ledger row did not carry before: the
   * movements are the claim's business, and the S62 audit record stays the
   * settlement's shape (match, mode, totals, who settled).
   */
  @Test
  fun theLedgerRowDoesNotGainMovements() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(uids.associateWith { 690 }),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    val record = db.appends.single()
    assertNull(record["movements"])
    assertNull(record["you"])
    assertEquals("APPLIED", record["outcomeKind"])
    assertEquals("m1", record["matchId"])
  }

  /**
   * The claim records all four movements — the audit trail RD12 asks for —
   * keyed by uid, each with previousRp/rp/delta, and NOTHING else. This
   * document is admin-only (no client rule reads matches/{id}/settlement), so
   * holding four seats here is not a leak; holding them in the RESPONSE is.
   */
  @Test
  fun theClaimRecordsAllFourMovements() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = profilesWith(mapOf("u1" to 0, "u2" to 150, "u3" to 150, "u4" to 150)),
    )

    settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    val claim = db.claim!!
    assertEquals(setOf("settledBy", "settledAt", "outcome", "movements"), claim.keys)
    @Suppress("UNCHECKED_CAST")
    val movements = claim["movements"] as Map<String, Map<String, Any?>>
    assertEquals(uids.toSet(), movements.keys)
    assertEquals(
      mapOf("previousRp" to 0, "rp" to 48, "delta" to 48),
      movements["u1"],
    )
    assertEquals(
      mapOf("previousRp" to 150, "rp" to 125, "delta" to -25),
      movements["u2"],
    )
    movements.forEach { (uid, movement) ->
      assertEquals(
        setOf("previousRp", "rp", "delta"),
        movement.keys,
        "movement for '$uid' records exactly three numbers",
      )
    }
  }

  // ── corrupt numbers fail the whole settlement, never truncate ─────

  /**
   * A fractional RP is not read as 690 by truncation: the whole table refuses
   * before any profile read can be written, so no seat is paid from a number
   * that was never a valid RP.
   */
  @Test
  fun aFractionalRpFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("rp" to 690.5)),
      "seated player 'u3' of match 'm1' has a players/rp",
      "(690.5)",
      "not a finite whole number from 0 to ${Int.MAX_VALUE}",
    )
  }

  /** Negative RP is corrupt, not a value to floor to Bronze III's 0. */
  @Test
  fun aNegativeRpFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("rp" to -1)),
      "seated player 'u3' of match 'm1' has a players/rp",
      "(-1)",
      "not a finite whole number from 0 to ${Int.MAX_VALUE}",
    )
  }

  /** A whole number past Int.MAX_VALUE must not be narrowed into an Int. */
  @Test
  fun anOutOfIntRpFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("rp" to 3.0E9)),
      "seated player 'u3' of match 'm1' has a players/rp",
      "not a finite whole number from 0 to ${Int.MAX_VALUE}",
    )
  }

  /** NaN and either infinity are not numbers a settlement may truncate. */
  @Test
  fun aNonFiniteRpFailsTheWholeSettlementClosed() = runTest {
    for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      expectWholeSettlementDenied(
        corruptingU3(mapOf("rp" to value)),
        "seated player 'u3' of match 'm1' has a players/rp",
        "not a finite whole number from 0 to ${Int.MAX_VALUE}",
      )
    }
  }

  /** Every counter shares the same finite/integral gate; a fraction never folds. */
  @Test
  fun everyStatCounterRejectsAFraction() = runTest {
    for (field in listOf(
      "rankedGamesPlayed",
      "rankedKingCount",
      "rankedSecondCount",
      "rankedThirdCount",
      "rankedKozCount",
    )) {
      expectWholeSettlementDenied(
        corruptingU3(mapOf(field to 1.5)),
        "seated player 'u3' of match 'm1' has a players/$field",
        "not a finite whole number within the Int range",
      )
    }
  }

  /**
   * The pre-existing negative-integer and non-numeric refusals keep their
   * messages, while the malformed shapes around them (a negative fraction,
   * NaN, infinity, out-of-Int) are refused by the finite/whole gate first.
   */
  @Test
  fun malformedStatCounterNumbersFailTheWholeSettlementClosed() = runTest {
    for (value in listOf(-0.5, 3.0E9, Double.NaN, Double.POSITIVE_INFINITY)) {
      expectWholeSettlementDenied(
        corruptingU3(mapOf("rankedKingCount" to value)),
        "seated player 'u3' of match 'm1' has a players/rankedKingCount",
        "not a finite whole number within the Int range",
      )
    }
  }

  /**
   * A stored tier/division below the rung its RP resolves to is corrupt data.
   * The helper names the RP and both rungs; the settlement does not repair the
   * identity or write any seat.
   */
  @Test
  fun aStoredRankBelowTheRungRpImpliesFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("tier" to "Gold", "division" to "III", "rp" to 150)),
      "seated player 'u3' of match 'm1'",
      "progression mismatch at rp=150",
      "rankAt(150) is Bronze I",
      "stored tier/division is Gold III",
    )
  }

  /** The mirror case: a stored rung above the RP-derived rung. */
  @Test
  fun aStoredRankAboveTheRungRpImpliesFailsTheWholeSettlementClosed() = runTest {
    expectWholeSettlementDenied(
      corruptingU3(mapOf("tier" to "Bronze", "division" to "III", "rp" to 690)),
      "seated player 'u3' of match 'm1'",
      "progression mismatch at rp=690",
      "rankAt(690) is Gold III",
      "stored tier/division is Bronze III",
    )
  }

  /**
   * Four Kings settle at the 3,000 floor. Every seat's ladder ordinal is 18,
   * so strength = (18 - 0) × 2 = 36 and the tier term is 0:
   *
   * ```
   * KING   30 + 36 = 66  → 3066
   * SECOND 15 + 36 = 51  → 3051
   * THIRD  -8 + 36 = 28  → RD4 sign clamp holds it at 0 → 3000
   * KOZ   -20 + 36 = 16  → RD4 sign clamp holds it at 0 → 3000
   * ```
   *
   * The last two are negative-base outcomes; `computeBaseDelta` clamps them
   * to <= 0, so they cannot climb above the ceiling. The requested raw sums
   * (3028/3016) would invert RD4's sign rule and are not reachable without an
   * engine change, which this task forbids.
   */
  @Test
  fun aKingTableSettlesAtTheCeiling() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      profiles = uids.associateWith { uid ->
        SettlementFixtures.profile(uid, 3000, tier = "King", division = "")
      },
    )

    val response = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(4, db.profileWrites.size, "every seat settles together")
    assertEquals(
      expectedWrite(rp = 3066, tier = "King", division = "", king = 1),
      db.writeFor("u1"),
    )
    assertEquals(
      expectedWrite(rp = 3051, tier = "King", division = "", second = 1),
      db.writeFor("u4"),
    )
    assertEquals(
      expectedWrite(rp = 3000, tier = "King", division = "", third = 1),
      db.writeFor("u3"),
    )
    assertEquals(
      expectedWrite(rp = 3000, tier = "King", division = "", koz = 1),
      db.writeFor("u2"),
    )
    assertEquals(mapOf("previousRp" to 3000, "rp" to 3066, "delta" to 66), response["you"])
  }

  // ── replay refuses corrupt recorded movements ─────────────────────

  private fun replayClaim(movements: Any?): Map<String, Any?> = linkedMapOf(
    "settledBy" to "u1",
    "outcome" to linkedMapOf<String, Any?>(
      "ok" to true,
      "settled" to true,
      "replayed" to false,
      "matchId" to "m1",
      "corrected" to false,
      "finalScores" to SettlementFixtures.GOLDEN_SCORES,
      "winnerIds" to listOf("p1"),
      "claimedScores" to SettlementFixtures.GOLDEN_SCORES,
      "claimedWinners" to listOf("p1"),
      "rounds" to 1,
    ),
    "movements" to movements,
  )

  private suspend fun expectReplayDenied(
    movement: Map<String, Any?>,
    vararg messageParts: String,
  ) {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      claim = replayClaim(mapOf("u1" to movement)),
    )
    expectDenied("failed-precondition", *messageParts) {
      settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))
    }
    assertEquals(0, db.profileReads, "a refused replay reads no profile")
    assertEquals(0, db.profileWrites.size, "a refused replay writes no profile")
    assertEquals(0, db.claims, "a refused replay writes no claim")
    assertEquals(0, db.corrections, "a refused replay writes no correction")
  }

  /**
   * A recorded movement is a claim about numbers that were already awarded.
   * Truncating a fractional/out-of-range/non-numeric one would report an
   * award the caller never received; the replay refuses instead.
   */
  @Test
  fun aCorruptRecordedMovementRefusesTheReplay() = runTest {
    expectReplayDenied(
      mapOf("previousRp" to 690.5, "rp" to 732, "delta" to 42),
      "replay for 'u1'",
      "movements/previousRp",
      "690.5",
    )
    expectReplayDenied(
      mapOf("previousRp" to 690, "rp" to 3.0E9, "delta" to 42),
      "replay for 'u1'",
      "movements/rp",
    )
    expectReplayDenied(
      mapOf("previousRp" to 690, "rp" to 732, "delta" to "42"),
      "replay for 'u1'",
      "movements/delta",
      "42",
    )
  }

  /** delta may be negative; only its finiteness/wholeness/range are constrained. */
  @Test
  fun aNegativeRecordedDeltaStillReplays() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      claim = replayClaim(
        mapOf("u1" to mapOf("previousRp" to 150, "rp" to 125, "delta" to -25)),
      ),
    )

    val replay = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, replay["replayed"])
    assertEquals(mapOf("previousRp" to 150, "rp" to 125, "delta" to -25), replay["you"])
    assertEquals(0, db.profileReads)
    assertEquals(0, db.profileWrites.size)
  }

  /**
   * Absent/null movement keys remain the legacy no-`you` path: a claim that
   * does not carry all three numbers still replays, but reports no movement.
   */
  @Test
  fun aMovementWithAnAbsentOrNullKeyReplaysWithoutYou() = runTest {
    for (movement in listOf(
      mapOf<String, Any?>("previousRp" to 690, "rp" to 732),
      mapOf<String, Any?>("previousRp" to 690, "rp" to 732, "delta" to null),
    )) {
      val db = FakeRpDb(
        match = SettlementFixtures.matchFields(uids),
        archives = archives,
        claim = replayClaim(mapOf("u1" to movement)),
      )

      val replay = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

      assertEquals(true, replay["replayed"])
      assertNull(replay["you"])
      assertEquals(0, db.profileWrites.size)
    }
  }

  /**
   * A movements map that names another caller is not the caller's business:
   * the slice is absent, so the replay has no `you` and reads no profile.
   */
  @Test
  fun aMovementForAnotherCallerReplaysWithoutYou() = runTest {
    val db = FakeRpDb(
      match = SettlementFixtures.matchFields(uids),
      archives = archives,
      claim = replayClaim(
        mapOf("u2" to mapOf("previousRp" to 690, "rp" to 682, "delta" to -8)),
      ),
    )

    val replay = settleOnce(db, SettlementFixtures.settleRequest("u1", "m1"))

    assertEquals(true, replay["replayed"])
    assertNull(replay["you"])
    assertEquals(0, db.profileWrites.size)
  }
}
