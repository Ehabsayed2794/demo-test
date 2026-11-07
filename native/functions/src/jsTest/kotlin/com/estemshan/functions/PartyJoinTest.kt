package com.estemshan.functions

import com.estemshan.engine.MatchMode
import com.estemshan.engine.PARTY_DENIAL_REASON
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S70 — the Rank-down join gate's authority half, on a fake [PartyJoinDb].
 *
 * This is where RD29's "checked server-side only" gets its proof, and the
 * assertions are ordered the way the transaction is: what a caller may send,
 * what a room must be before any rank is read, which read happens in what
 * order, and what may leave the function afterwards. Two of those are the
 * load-bearing ones, and each has a way to be silently wrong that a cheaper
 * test would miss:
 *
 * - **The mode gate runs before the membership return.** A joiner already in
 *   `players` of a room whose flag has since been cleared must not be answered
 *   `joined: true` — an early idempotent return that forgets this turns the
 *   gate into a no-op for exactly the parties that already squeezed in.
 * - **Nothing rank-bearing may leave.** The projection is asserted as a
 *   flattened string of every key and value the response actually carries,
 *   because a leak that rides inside a nested room map is invisible to a
 *   top-level `keys` check — and the whole story exists to prevent that leak.
 *
 * The parser cases (a missing profile, a tier that names no rung, an absent `rp`
 * beside a placed rung) all assert the same thing twice: the denial is
 * `PROFILE_UNAVAILABLE`, and nothing was written. Fail-closed means the room is
 * left exactly as it was found.
 */
class PartyJoinTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  private val creatorUid = "uid-creator"
  private val joinerUid = "uid-joiner"

  private class FakePartyJoinDb(
    var room: Map<String, Any?>?,
    val profiles: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
  ) : PartyJoinDb {

    /** Every touch in the order it happened, so read-before-write is assertable. */
    val events = mutableListOf<String>()
    val writes: Int get() = events.count { it.startsWith("write") }
    var writtenPlayers: List<String>? = null
      private set

    override suspend fun <T> transact(block: suspend (PartyJoinDb.Tx) -> T): T {
      events += "tx"
      return block(object : PartyJoinDb.Tx {
        override suspend fun readRoom(roomId: String): Map<String, Any?>? {
          events += "room:$roomId"
          return room
        }

        override suspend fun readProfile(uid: String): Map<String, Any?>? {
          events += "profile:$uid"
          return profiles[uid]
        }

        override fun writePlayers(roomId: String, players: List<String>) {
          events += "write:$roomId"
          writtenPlayers = players
          room = room?.plus("players" to players)
        }
      })
    }
  }

  private fun room(
    status: String = "waiting",
    players: List<String> = listOf(creatorUid),
    mode: String? = MatchMode.RANKED.name,
    rankDown: Any? = true,
    roomId: String = ROOM_ID,
  ): Map<String, Any?> = linkedMapOf(
    "roomId" to roomId,
    "name" to "Thursday table",
    "status" to status,
    "creator" to creatorUid,
    "players" to players,
    "readyPlayers" to emptyList<String>(),
    "mode" to mode,
    "rankDown" to rankDown,
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "matchId" to null,
    // Present so a leak of the private half of a room document is provable:
    // the projection must not carry these, and a document that holds them is
    // the only honest test of that.
    "botTier" to "MEDIUM",
    "password" to "hunter2",
    "createdAt" to 1_700_000_000_000,
  )

  /**
   * A profile exactly as S50's write path stores one: the rung and its `rp`
   * agree (RD3), because a fixture that breaks that is indistinguishable at the
   * gate from a genuinely corrupt document — the callable would deny it for the
   * right reason and the test would fail for the wrong one.
   */
  private fun profile(tier: String, division: String, rp: Int): MutableMap<String, Any?> {
    val rung = rankLadder.first { it.tier == tier && it.division == division }
    val storedRp = rp.coerceAtLeast(rung.lowerBound)
    require(rankAt(storedRp) == rung) {
      "S70 fixture breaks RD3: rp $storedRp resolves to " +
        "'${rankAt(storedRp).tier} ${rankAt(storedRp).division}', not '$tier $division'."
    }
    return linkedMapOf(
      "displayName" to "P",
      "tier" to tier,
      "division" to division,
      "rp" to storedRp,
      "seasonId" to "s1",
      "highestRankTier" to tier,
      "highestRankDivision" to division,
      "placementState" to "COMPLETE",
      "demotionShield" to false,
    )
  }

  private fun placed(tier: String): MutableMap<String, Any?> {
    val rung = rankLadder.first { it.tier == tier }
    return profile(tier, rung.division, rung.lowerBound)
  }

  private fun db(
    room: Map<String, Any?>? = room(),
    creator: MutableMap<String, Any?>? = placed("Gold"),
    joiner: MutableMap<String, Any?>? = placed("Silver"),
  ): FakePartyJoinDb = FakePartyJoinDb(room).apply {
    if (creator != null) profiles[creatorUid] = creator
    if (joiner != null) profiles[joinerUid] = joiner
  }

  private fun request(roomId: Any?, extra: Pair<String, Any?>? = null): CallableRequest {
    val data: dynamic = js("({})")
    if (roomId != null) data.roomId = roomId
    if (extra != null) data[extra.first] = extra.second
    return object : CallableRequest {
      override val data: Any? = data
      override val auth: AuthData? = object : AuthData {
        override val uid: String = joinerUid
        override val token: AuthToken? = null
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }
  }

  private suspend fun join(db: PartyJoinDb, roomId: Any? = ROOM_ID): Map<String, Any?> =
    joinOnce(db, request(roomId))

  /**
   * A refusal, sampled from ONE call.
   *
   * Code, stable reason and sentence are read off the same thrown object
   * because a test that asked twice would double-count the fake's event log and
   * could no longer prove the read order it is asserting.
   */
  private class Refusal(val code: String, val reason: String?, val message: String)

  private suspend fun refused(block: suspend () -> Any?): Refusal {
    try {
      block()
    } catch (e: Throwable) {
      val code = e.asDynamic().code as? String
        ?: throw AssertionError("expected an HttpsError, got $e")
      return Refusal(code, e.asDynamic().details?.reason as? String, e.message ?: "")
    }
    throw AssertionError("expected HttpsError")
  }

  /** Every key and value the response actually carries, flattened to one string. */
  private fun rendered(value: Any?): String = when (value) {
    is Map<*, *> -> value.entries.joinToString("|") { "${it.key}=${rendered(it.value)}" }
    is List<*> -> value.joinToString("|") { rendered(it) }
    null -> ""
    else -> value.toString()
  }

  @Suppress("UNCHECKED_CAST")
  private fun roomOf(response: Map<String, Any?>): Map<String, Any?> =
    response.getValue("room") as Map<String, Any?>

  // ── the request ──────────────────────────────────────────────────────

  @Test
  fun anUnauthenticatedRequestIsRefusedBeforeAnyRead() = runTest {
    val db = db()
    val unauthenticated = object : CallableRequest {
      override val data: Any? = js("({})")
      override val auth: AuthData? = null
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }

    val refusal = refused { joinOnce(db, unauthenticated) }
    assertEquals("unauthenticated", refusal.code)
    assertEquals(emptyList(), db.events)
  }

  @Test
  fun aMissingBlankOrNonStringRoomIdIsRejectedWithoutTouchingFirestore() = runTest {
    for (roomId in listOf(null, "", "   ", 7, true, emptyList<String>())) {
      val db = db()
      val refusal = refused { join(db, roomId) }
      assertEquals("invalid-argument", refusal.code, "roomId '$roomId'")
      assertEquals(
        "INVALID_ARGUMENT",
        refusal.reason,
        "roomId '$roomId' must name its stable reason",
      )
      assertEquals(emptyList(), db.events, "a malformed roomId must read nothing")
    }
  }

  @Test
  fun anUnknownKeyIsRejectedByNameAndNeverByValue() = runTest {
    val db = db()
    // An arbitrary, unguessable value: the message must not echo it back.
    val sentinel = "SECRET-VALUE-42"

    val refusal = refused { joinOnce(db, request(ROOM_ID, "nickname" to sentinel)) }
    assertEquals("invalid-argument", refusal.code)
    assertEquals("INVALID_ARGUMENT", refusal.reason)
    assertTrue(refusal.message.contains("nickname"), "the key must be named: ${refusal.message}")
    assertFalse(
      refusal.message.contains(sentinel),
      "the value must never be echoed: ${refusal.message}",
    )
    assertEquals(emptyList(), db.events)
  }

  @Test
  fun aClientSuppliedIdentityTierOrRoomConfigIsPermissionDeniedAndNeverEchoed() = runTest {
    val forbidden = listOf(
      "uid" to "somebody-else",
      "playerId" to "somebody-else",
      "tier" to "King",
      "division" to "I",
      "rank" to "King",
      "rp" to 3_000,
      "seasonId" to "s7",
      "mode" to "ROOM",
      "rankDown" to false,
    )
    for ((key, value) in forbidden) {
      val db = db()
      val refusal = refused { joinOnce(db, request(ROOM_ID, key to value)) }
      assertEquals("permission-denied", refusal.code, "'$key' is server-derived")
      assertEquals(
        "FORBIDDEN_KEY",
        refusal.reason,
        "'$key' must name its stable reason",
      )
      assertEquals(
        "identity, tier and room configuration are server-derived; '$key' is not accepted.",
        refusal.message,
        "'$key' must be refused with the fixed sentence",
      )
      assertFalse(
        refusal.message.contains("$value"),
        "'$key' must not echo its value: ${refusal.message}",
      )
      assertEquals(emptyList(), db.events, "'$key' must be refused before any read")
    }
  }

  @Test
  fun theNormalisationRoomDocAppliesIsAppliedHereToo() = runTest {
    // A short code is uppercased so "x7k2pq" reaches room "X7K2PQ".
    val short = db()
    join(short, "  x7k2pq  ")
    assertEquals(listOf("room:X7K2PQ"), short.events.filter { it.startsWith("room") })

    // A legacy auto-ID is case-sensitive: trimmed only, never uppercased.
    val long = db()
    join(long, "  AbCdEfGhIjKl  ")
    assertEquals(listOf("room:AbCdEfGhIjKl"), long.events.filter { it.startsWith("room") })
  }

  // ── the room gate ────────────────────────────────────────────────────

  @Test
  fun anAbsentRoomIsNotFoundAndReadsNoProfile() = runTest {
    val db = db(room = null)

    val refusal = refused { join(db) }
    assertEquals("not-found", refusal.code)
    assertEquals("ROOM_NOT_FOUND", refusal.reason)
    assertEquals(listOf("tx", "room:$ROOM_ID"), db.events)
  }

  @Test
  fun aClosedOrStartedRoomIsRefusedAsRoomClosedBeforeAnyRankRead() = runTest {
    for (status in listOf("closed", "in_game")) {
      val db = db(room = room(status = status))

      val refusal = refused { join(db) }
      assertEquals("failed-precondition", refusal.code, "status '$status'")
      assertEquals("ROOM_CLOSED", refusal.reason, "status '$status'")
      assertEquals(0, db.writes)
      assertEquals(listOf("tx", "room:$ROOM_ID"), db.events, "status '$status' reads no profile")
    }
  }

  @Test
  fun aFullRoomIsResourceExhaustedAndWritesNothing() = runTest {
    val db = db(room = room(players = listOf(creatorUid, "p2", "p3", "p4")))

    val refusal = refused { join(db) }
    assertEquals("resource-exhausted", refusal.code)
    assertEquals("ROOM_FULL", refusal.reason)
    assertEquals(0, db.writes)
  }

  @Test
  fun aRoomThatIsNotRankDownIsRefusedWhateverItsMode() = runTest {
    val cases: List<Pair<String?, Any?>> = listOf(
      MatchMode.ROOM.name to true,
      MatchMode.UNRANKED.name to true,
      MatchMode.RANKED.name to false,
      null to true,
      MatchMode.RANKED.name to null,
      MatchMode.RANKED.name to "yes",
    )
    for ((mode, rankDown) in cases) {
      val db = db(room = room(mode = mode, rankDown = rankDown))

      val refusal = refused { join(db) }
      assertEquals("failed-precondition", refusal.code, "mode '$mode' flag '$rankDown'")
      assertEquals("NOT_RANK_DOWN", refusal.reason, "mode '$mode' flag '$rankDown'")
      assertEquals(listOf("tx", "room:$ROOM_ID"), db.events, "mode '$mode' flag '$rankDown'")
    }
  }

  @Test
  fun theModeGateRunsBeforeTheIdempotentMembershipReturn() = runTest {
    // The joiner IS already in players, and the room's flag has been cleared.
    // Answering joined:true here would make the gate a no-op for the party that
    // got in first, so the refusal must win.
    val db = db(room = room(players = listOf(creatorUid, joinerUid), rankDown = false))

    val refusal = refused { join(db) }
    assertEquals("failed-precondition", refusal.code)
    assertEquals("NOT_RANK_DOWN", refusal.reason)
    assertEquals(0, db.writes)
  }

  @Test
  fun anAlreadySeatedPlayerIsRejoinableEvenInAFullRoomAndIsNeverAppendedTwice() = runTest {
    val db = db(room = room(players = listOf(creatorUid, joinerUid, "p3", "p4")))

    val response = join(db)

    assertEquals(true, response["joined"])
    assertEquals(0, db.writes)
    assertEquals(listOf("tx", "room:$ROOM_ID"), db.events, "a re-join reads no profile")
  }

  // ── RD29 eligibility ─────────────────────────────────────────────────

  @Test
  fun aDivisionGapInsideOneTierIsEligibleAndAppendsTheJoinerOnce() = runTest {
    val db = db(creator = profile("Gold", "I", 825), joiner = profile("Gold", "III", 575))

    val response = join(db)

    assertEquals(true, response["joined"])
    assertEquals(1, db.writes)
    assertEquals(listOf(creatorUid, joinerUid), db.writtenPlayers)
  }

  @Test
  fun oneTierApartIsEligibleInBothDirections() = runTest {
    val goldHost = db(creator = profile("Gold", "I", 825), joiner = profile("Silver", "III", 250))
    assertEquals(true, join(goldHost)["joined"])
    assertEquals(listOf(creatorUid, joinerUid), goldHost.writtenPlayers)

    // RD29 is symmetric: a Silver host may take a Gold friend.
    val silverHost = db(creator = profile("Silver", "III", 250), joiner = profile("Gold", "I", 825))
    assertEquals(true, join(silverHost)["joined"])
    assertEquals(listOf(creatorUid, joinerUid), silverHost.writtenPlayers)
  }

  @Test
  fun twoTiersApartIsDeniedWithRankIncompatibleAndWritesNothing() = runTest {
    val db = db(creator = profile("Gold", "I", 825), joiner = profile("Bronze", "II", 75))

    val response = join(db)

    assertEquals(false, response["joined"])
    assertEquals(PARTY_DENIAL_REASON, response["reason"])
    assertEquals(0, db.writes)
    assertEquals(listOf("tx", "room:$ROOM_ID", "profile:$creatorUid", "profile:$joinerUid"), db.events)
    assertRankFree(response["message"] as String)
  }

  @Test
  fun kingBordersRoyalAndNothingAboveIt() = runTest {
    val kingHost = db(
      creator = profile("King", "", 3_000),
      joiner = profile("Royal", "I", 2_750),
    )
    assertEquals(true, join(kingHost)["joined"])

    val kingAgainstDiamond = db(
      creator = profile("King", "", 3_000),
      joiner = profile("Diamond", "I", 2_000),
    )
    val denied = join(kingAgainstDiamond)
    assertEquals(false, denied["joined"])
    assertEquals(PARTY_DENIAL_REASON, denied["reason"])
    assertEquals(0, kingAgainstDiamond.writes)
  }

  @Test
  fun anUnplacedAccountResolvesToBronzeThreeAndPlaysWithSilver() = runTest {
    // RD30: no rp, no tier/division at all — a real Bronze III, never a
    // "no tier" state, and eligible one tier up.
    val unplacedJoiner = db(
      creator = profile("Silver", "III", 250),
      joiner = linkedMapOf("displayName" to "Fresh"),
    )

    assertEquals(true, join(unplacedJoiner)["joined"])
    assertEquals(listOf(creatorUid, joinerUid), unplacedJoiner.writtenPlayers)

    // Two tiers up (Gold host) the same unplaced account is denied, not excused.
    val goldHost = db(
      creator = profile("Gold", "III", 575),
      joiner = linkedMapOf("displayName" to "Fresh"),
    )
    assertEquals(PARTY_DENIAL_REASON, join(goldHost)["reason"])
  }

  // ── fail-closed profile handling ─────────────────────────────────────

  @Test
  fun aMissingProfileDeniesProfileUnavailableWithoutNamingWhichSideFailed() = runTest {
    val noCreator = db(creator = null)
    val noJoiner = db(joiner = null)

    for ((label, database) in listOf("creator" to noCreator, "joiner" to noJoiner)) {
      val response = join(database)

      assertEquals(false, response["joined"], label)
      assertEquals("PROFILE_UNAVAILABLE", response["reason"], label)
      assertEquals(0, database.writes, label)
      assertRankFree(response["message"] as String)
      assertFalse(
        (response["message"] as String).lowercase().contains(label),
        "the denial must not say which $label failed",
      )
    }
  }

  @Test
  fun everyMalformedProfileFieldFailsClosedToProfileUnavailable() = runTest {
    val cases: List<Pair<String, (MutableMap<String, Any?>) -> Unit>> = listOf(
      "a non-numeric rp" to { p: MutableMap<String, Any?> -> p["rp"] = "690" },
      "a negative rp" to { p: MutableMap<String, Any?> -> p["rp"] = -1 },
      "a fractional rp" to { p: MutableMap<String, Any?> -> p["rp"] = 690.5 },
      "a rung that does not exist" to { p: MutableMap<String, Any?> -> p["division"] = "IV" },
      "half a tier identity" to { p: MutableMap<String, Any?> -> p.remove("division") },
      "half a highest identity" to { p: MutableMap<String, Any?> -> p.remove("highestRankDivision") },
      "a blank season" to { p: MutableMap<String, Any?> -> p["seasonId"] = "  " },
      "a non-string season" to { p: MutableMap<String, Any?> -> p["seasonId"] = 7 },
      "an unknown placement state" to { p: MutableMap<String, Any?> -> p["placementState"] = "DONE" },
      "a non-boolean shield" to { p: MutableMap<String, Any?> -> p["demotionShield"] = "true" },
      // RD3: rp 150 resolves to Bronze I, not the stored Gold II.
      "a tier that disagrees with rp" to { p: MutableMap<String, Any?> -> p["rp"] = 150 },
      // Only the bottom rung is representable by the absent-rp default.
      "an absent rp beside a placed rung" to { p: MutableMap<String, Any?> -> p.remove("rp") },
    )
    for ((label, mutate) in cases) {
      val corruptJoiner = db(joiner = profile("Gold", "II", 700).also(mutate))
      val deniedJoiner = join(corruptJoiner)
      assertEquals("PROFILE_UNAVAILABLE", deniedJoiner["reason"], "joiner: $label")
      assertEquals(0, corruptJoiner.writes, "joiner: $label")

      val corruptCreator = db(creator = profile("Gold", "II", 700).also(mutate))
      val deniedCreator = join(corruptCreator)
      assertEquals("PROFILE_UNAVAILABLE", deniedCreator["reason"], "creator: $label")
      assertEquals(0, corruptCreator.writes, "creator: $label")
    }
  }

  @Test
  fun aHalfTheLadderCanNameIsStillServedWithTheDefaultsRdThirtyDescribes() = runTest {
    // Everything absent except a legacy-shaped pair at the very bottom rung:
    // highest defaults to current, placement to NOT_STARTED, shield to false,
    // season to the first one — and Bronze III is eligible with Bronze I.
    val minimal = db(
      creator = linkedMapOf("tier" to "Bronze", "division" to "III", "rp" to 0),
      joiner = profile("Bronze", "I", 150),
    )

    assertEquals(true, join(minimal)["joined"])
    assertEquals(listOf(creatorUid, joinerUid), minimal.writtenPlayers)
  }

  // ── what may leave the function ──────────────────────────────────────

  @Test
  fun thePermittedProjectionIsTheRoomShapeAndNothingRankBearing() = runTest {
    val db = db(
      creator = profile("Gold", "I", 825).apply { put("email", "host@estemshan.test") },
      joiner = profile("Silver", "III", 250).apply {
        put("coins", 9_999)
        put("highestRankTier", "Silver")
        put("seasonId", "s9")
      },
    )

    val response = join(db)
    val room = roomOf(response)

    assertEquals(setOf("joined", "room"), response.keys)
    assertEquals(
      setOf(
        "roomId", "name", "status", "creator", "players", "readyPlayers",
        "mode", "rankDown", "gameType", "scoringMode", "matchId",
      ),
      room.keys,
    )
    assertEquals(ROOM_ID, room["roomId"])
    assertEquals(listOf(creatorUid, joinerUid), room["players"])
    assertEquals(MatchMode.RANKED.name, room["mode"])
    assertEquals(true, room["rankDown"])

    // Every private word, key and value, searched for in the whole payload.
    val payload = rendered(response).lowercase()
    for (leak in listOf(
      "tier", "division", "seasonid", "highestrank", "placementstate", "demotionshield",
      "rp", "email", "coins", "hunter2", "bottier",
      "bronze", "silver", "gold", "platinum", "diamond", "royal", "king",
      "s9", "825", "250", "host@estemshan.test", "9999",
    )) {
      assertFalse(payload.contains(leak), "the response leaks '$leak'")
    }
  }

  @Test
  fun aDeniedJoinReturnsTheReasonAndSentenceAndNoRoomAtAll() = runTest {
    val db = db(creator = profile("Gold", "I", 825), joiner = profile("Bronze", "II", 75))

    val response = join(db)

    assertEquals(setOf("joined", "reason", "message"), response.keys)
    assertEquals(false, response["joined"])
    assertEquals(PARTY_DENIAL_REASON, response["reason"])
  }

  @Test
  fun aProfileUnavailableDenialCarriesTheSameShapeAndNoRoom() = runTest {
    val db = db(creator = null)

    val response = join(db)

    assertEquals(setOf("joined", "reason", "message"), response.keys)
    assertEquals("PROFILE_UNAVAILABLE", response["reason"])
    assertRankFree(response["message"] as String)
  }

  // ── the read order itself ────────────────────────────────────────────

  @Test
  fun theTransactionReadsRoomThenCreatorThenJoinerBeforeAnyWrite() = runTest {
    val db = db()

    join(db)

    // Firestore forbids a read after a write in one transaction, and the two
    // profiles must be the ones committed against this room, not a pre-image.
    assertEquals(
      listOf("tx", "room:$ROOM_ID", "profile:$creatorUid", "profile:$joinerUid", "write:$ROOM_ID"),
      db.events,
    )
  }

  // ── helpers ──────────────────────────────────────────────────────────

  /** No tier word, no roman numeral, no number, no uid, no "rp". */
  private fun assertRankFree(message: String) {
    assertTrue(message.isNotBlank(), "a denial must carry a plain-language message")
    val lower = message.lowercase()
    for (tier in listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King")) {
      assertFalse(lower.contains(tier.lowercase()), "message names tier '$tier': $message")
    }
    assertFalse(message.any { it.isDigit() }, "message carries a number: $message")
    assertFalse(
      Regex("\\brp\\b", RegexOption.IGNORE_CASE).containsMatchIn(message),
      "message mentions RP: $message",
    )
    assertFalse(lower.contains("uid"), "message carries a uid: $message")
  }

  private companion object {
    /**
     * Six characters — exactly [ROOM_CODE_LENGTH], so the normalizer uppercases
     * it. Already uppercase, which is why the room-event id is this literal in
     * every assertion; the two normalization branches are pinned separately in
     * [theNormalisationRoomDocAppliesIsAppliedHereToo].
     */
    const val ROOM_ID = "AB12CD"
  }
}
