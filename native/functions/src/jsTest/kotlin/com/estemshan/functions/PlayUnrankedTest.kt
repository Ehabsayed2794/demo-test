package com.estemshan.functions

import com.estemshan.engine.MatchMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * S71 — the host-only "Play Unranked" conversion, on a fake [PlayUnrankedDb].
 *
 * The story's assertions, in the order the transaction runs them:
 *
 * - **Only the host may convert.** A refused friend is *informed*, never the
 *   actor (RD21/S36), so the host is derived from the verified uid and a
 *   non-host is refused before anything else is learned about the room.
 * - **Pre-match only.** A room that is closed, started, or in a status this
 *   callable does not recognise is refused, and nothing is written — a mode
 *   transition mid-match would change the authority the roster, the voice
 *   gate and the Vote Kick gate are reading while they run.
 * - **The credential is cleared.** `rankDown` → false, always, in the same
 *   write as the mode. An Unranked room that kept the flag would be `ROOM` +
 *   a marker, which is the collapse S43 and R32 forbid.
 * - **The pool anchor is stamped.** The write carries the room's entry into
 *   the Unranked pool, which is what the four-minute bot-fill window runs
 *   from — so it is the conversion's own stamp, never the room's older
 *   `createdAt`.
 * - **Idempotent.** The target state is a fixed point, so the second call
 *   writes nothing at all and says so.
 * - **Nothing rank-bearing leaves.** The projection is asserted as a
 *   flattened string of every key and value the response actually carries,
 *   because a leak that rides inside a nested room map is invisible to a
 *   top-level key check.
 */
class PlayUnrankedTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  private val hostUid = "uid-host"
  private val friendUid = "uid-friend"

  private class FakePlayUnrankedDb(
    var room: Map<String, Any?>?,
    /** The fake's own clock: the stamp the conversion writes. */
    var now: Long = CONVERSION_NOW,
  ) : PlayUnrankedDb {

    /** Every touch in the order it happened, so read-before-write is assertable. */
    val events = mutableListOf<String>()
    val writes: Int get() = events.count { it.startsWith("write") }

    override suspend fun <T> transact(block: suspend (PlayUnrankedDb.Tx) -> T): T {
      events += "tx"
      return block(object : PlayUnrankedDb.Tx {
        override suspend fun readRoom(roomId: String): Map<String, Any?>? {
          events += "room:$roomId"
          return room
        }

        override fun writeUnranked(roomId: String) {
          events += "write:$roomId"
          // The fake writes the WHOLE effect, exactly as the Admin seam does:
          // the mode transition, the re-stamped `updatedAt` and the pool
          // anchor. A fake that wrote only the mode transition could not
          // represent the anchor at all, which is how the anchor's write
          // stayed invisible to every test.
          room = room?.let { it + unrankedConversionWrite(updatedAt = now, poolOpenedAt = now) }
        }
      })
    }
  }

  private fun room(
    status: String? = "waiting",
    creator: String? = hostUid,
    mode: Any? = MatchMode.RANKED.name,
    rankDown: Any? = true,
    players: List<String> = listOf(hostUid, friendUid),
    matchId: Any? = null,
    roomId: String = ROOM_ID,
  ): Map<String, Any?> = linkedMapOf(
    "roomId" to roomId,
    "name" to "Thursday table",
    "status" to status,
    "creator" to creator,
    "players" to players,
    "readyPlayers" to emptyList<String>(),
    "mode" to mode,
    "rankDown" to rankDown,
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "matchId" to matchId,
    // Present so a leak of the private half of a room document is provable:
    // the projection must not carry these, and a document that holds them is
    // the only honest test of that.
    "botTier" to "MEDIUM",
    "password" to "hunter2",
    "createdAt" to ROOM_CREATED_AT,
  )

  private fun db(room: Map<String, Any?>? = room()): FakePlayUnrankedDb =
    FakePlayUnrankedDb(room)

  private fun request(
    roomId: Any?,
    extra: Pair<String, Any?>? = null,
    uid: String? = hostUid,
  ): CallableRequest {
    val data: dynamic = js("({})")
    if (roomId != null) data.roomId = roomId
    if (extra != null) data[extra.first] = extra.second
    return object : CallableRequest {
      override val data: Any? = data
      override val auth: AuthData? = if (uid == null) null else object : AuthData {
        override val uid: String = uid
        override val token: AuthToken? = null
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }
  }

  private suspend fun play(
    db: PlayUnrankedDb,
    roomId: Any? = ROOM_ID,
    uid: String? = hostUid,
  ): Map<String, Any?> = playUnrankedOnce(db, request(roomId, uid = uid))

  /**
   * A refusal, sampled from ONE call.
   *
   * Code, stable reason and sentence are read off the same thrown object
   * because a test that asked twice would double-count the fake's event log.
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

    val refusal = refused { play(db, uid = null) }

    assertEquals("unauthenticated", refusal.code)
    assertEquals(emptyList(), db.events)
  }

  @Test
  fun aMissingBlankOrNonStringRoomIdIsRejectedWithoutTouchingFirestore() = runTest {
    for (roomId in listOf(null, "", "   ", 7, true, emptyList<String>())) {
      val db = db()
      val refusal = refused { play(db, roomId) }
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

    val refusal = refused { playUnrankedOnce(db, request(ROOM_ID, "nickname" to sentinel)) }

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
  fun aClientSuppliedModeCredentialOrIdentityIsPermissionDeniedAndNeverEchoed() = runTest {
    val forbidden = listOf(
      "mode" to "ROOM",
      "rankDown" to false,
      "creator" to friendUid,
      "players" to listOf(friendUid),
      "status" to "in_game",
      "uid" to "somebody-else",
      "playerId" to "somebody-else",
      "tier" to "King",
      "division" to "I",
      "rank" to "King",
      "rp" to 3_000,
      "seasonId" to "s7",
    )
    for ((key, value) in forbidden) {
      val db = db()
      val refusal = refused { playUnrankedOnce(db, request(ROOM_ID, key to value)) }
      assertEquals("permission-denied", refusal.code, "'$key' is server-derived")
      assertEquals(
        "FORBIDDEN_KEY",
        refusal.reason,
        "'$key' must name its stable reason",
      )
      assertFalse(
        refusal.message.contains("$value"),
        "'$key' must not echo its value: ${refusal.message}",
      )
      assertEquals(emptyList(), db.events, "'$key' must be refused before any read")
    }
  }

  @Test
  fun theRoomCodeNormalisationTheClientAppliesIsAppliedHereToo() = runTest {
    // A short code is uppercased so "x7k2pq" reaches room "X7K2PQ".
    val short = db()
    play(short, "  x7k2pq  ")
    assertEquals(listOf("room:X7K2PQ"), short.events.filter { it.startsWith("room") })

    // A legacy auto-ID is case-sensitive: trimmed only, never uppercased.
    val long = db()
    play(long, "  AbCdEfGhIjKl  ")
    assertEquals(listOf("room:AbCdEfGhIjKl"), long.events.filter { it.startsWith("room") })
  }

  // ── the gates ────────────────────────────────────────────────────────

  @Test
  fun aRoomThatDoesNotExistIsNotFoundAndWritesNothing() = runTest {
    val db = db(room = null)

    val refusal = refused { play(db) }

    assertEquals("not-found", refusal.code)
    assertEquals("ROOM_NOT_FOUND", refusal.reason)
    assertEquals(0, db.writes)
    assertEquals(listOf("tx", "room:$ROOM_ID"), db.events)
  }

  @Test
  fun aRoomWithNoCreatorReadsAsNoRoomAtAll() = runTest {
    // The client's own parser returns null for a room with no creator, so it
    // has no room to convert and no host to press the button.
    val db = db(room = room(creator = null))

    val refusal = refused { play(db) }

    assertEquals("not-found", refusal.code)
    assertEquals("ROOM_NOT_FOUND", refusal.reason)
    assertEquals(0, db.writes)
  }

  @Test
  fun onlyTheHostMayConvert() = runTest {
    val db = db()

    val refusal = refused { play(db, uid = friendUid) }

    assertEquals("permission-denied", refusal.code)
    assertEquals("NOT_HOST", refusal.reason)
    assertEquals(0, db.writes)
    assertEquals(listOf("tx", "room:$ROOM_ID"), db.events, "a non-host reads no more than the room")
  }

  @Test
  fun aClosedRoomIsRefusedAsRoomClosed() = runTest {
    val db = db(room = room(status = "closed"))

    val refusal = refused { play(db) }

    assertEquals("failed-precondition", refusal.code)
    assertEquals("ROOM_CLOSED", refusal.reason)
    assertEquals(0, db.writes)
  }

  @Test
  fun aStartedRoomIsRefusedAsAlreadyStarted() = runTest {
    val inGame = db(room = room(status = "in_game"))
    val refusal = refused { play(inGame) }
    assertEquals("failed-precondition", refusal.code)
    assertEquals("ALREADY_STARTED", refusal.reason)
    assertEquals(0, inGame.writes)

    // A room that still says `waiting` but already carries a match is started
    // too — the match id is the harder fact.
    val withMatch = db(room = room(status = "waiting", matchId = "match-42"))
    val second = refused { play(withMatch) }
    assertEquals("failed-precondition", second.code)
    assertEquals("ALREADY_STARTED", second.reason)
    assertEquals(0, withMatch.writes)
  }

  @Test
  fun aStatusThisCallableDoesNotRecogniseIsRefused() = runTest {
    val db = db(room = room(status = "paused"))

    val refusal = refused { play(db) }

    assertEquals("failed-precondition", refusal.code)
    assertEquals("NOT_PRE_MATCH", refusal.reason)
    assertEquals(0, db.writes)
  }

  // ── the conversion ───────────────────────────────────────────────────

  @Test
  fun aRankedRoomBecomesUnrankedWithTheCredentialCleared() = runTest {
    val db = db()

    val response = play(db)

    assertEquals(true, response["changed"])
    assertEquals(false, response["alreadyUnranked"])
    assertEquals(1, db.writes)
    val room = roomOf(response)
    assertEquals(MatchMode.UNRANKED.name, room["mode"])
    assertEquals(false, room["rankDown"])
  }

  @Test
  fun theConversionStampsAFreshPoolAnchorAndNotTheRoomsCreationStamp() = runTest {
    // The room was created an hour ago and the host converts it NOW. The
    // fill window's anchor is when the room entered the POOL, so it must be
    // this conversion's stamp: a room that inherited its creation time would
    // arrive with an already-closed window and be handed to the bots before
    // the host ever waited.
    val db = db()

    play(db)

    assertEquals(
      CONVERSION_NOW,
      db.room?.get("poolOpenedAt"),
      "the anchor is the conversion, not the room's creation",
    )
    assertNotEquals(
      ROOM_CREATED_AT,
      db.room?.get("poolOpenedAt"),
      "the anchor must never be the room's creation stamp",
    )
  }

  @Test
  fun aReConversionReAnchorsTheWindow() = runTest {
    // The only case where the conversion writes on an already-Unranked room
    // is the credential still being present — and it must refresh the anchor
    // all the same, or the room would keep the window it had.
    val db = db(room = room(mode = MatchMode.UNRANKED.name, rankDown = true))

    play(db)

    assertEquals(CONVERSION_NOW, db.room?.get("poolOpenedAt"))
  }

  @Test
  fun aLegacyRoomWithNoModeFieldIsConvertedToo() = runTest {
    // Absent mode reads as ROOM, exactly as the client's parser reads it.
    val db = db(room = room(mode = null, rankDown = null))

    val response = play(db)

    assertEquals(true, response["changed"])
    assertEquals(MatchMode.UNRANKED.name, roomOf(response)["mode"])
    assertEquals(false, roomOf(response)["rankDown"])
    assertEquals(1, db.writes)
  }

  @Test
  fun theSeatedPlayersKeepTheirSeats() = runTest {
    val players = listOf(hostUid, friendUid, "uid-third", "uid-fourth")
    val db = db(room = room(players = players))

    val response = play(db)

    assertEquals(players, roomOf(response)["players"])
    assertEquals(players, db.room?.get("players"))
  }

  @Test
  fun theConversionIsIdempotentAndASecondCallWritesNothing() = runTest {
    val db = db()

    val first = play(db)
    assertEquals(true, first["changed"])
    assertEquals(false, first["alreadyUnranked"])
    assertEquals(1, db.writes)

    val second = play(db)
    assertEquals(false, second["changed"], "a second call must be a no-op")
    assertEquals(true, second["alreadyUnranked"])
    assertEquals(1, db.writes, "a second call must not write again")
    assertEquals(MatchMode.UNRANKED.name, roomOf(second)["mode"])
    assertEquals(false, roomOf(second)["rankDown"])
  }

  @Test
  fun theCredentialIsClearedEvenWhenTheModeIsAlreadyUnranked() = runTest {
    // The only case where a write happens without a mode transition: a room
    // that is already Unranked but still carries the Rank-down flag.
    val db = db(room = room(mode = MatchMode.UNRANKED.name, rankDown = true))

    val response = play(db)

    assertEquals(true, response["changed"])
    assertEquals(true, response["alreadyUnranked"])
    assertEquals(1, db.writes)
    assertEquals(false, roomOf(response)["rankDown"])
  }

  @Test
  fun theReadAndTheWriteShareOneTransaction() = runTest {
    val db = db()

    play(db)

    // Firestore forbids a read after a write in one transaction, and the
    // decision must be made against the committed room, not a pre-image.
    assertEquals(listOf("tx", "room:$ROOM_ID", "write:$ROOM_ID"), db.events)
  }

  // ── what may leave the function ──────────────────────────────────────

  @Test
  fun thePermittedProjectionIsTheRoomShapeAndNothingRankBearing() = runTest {
    val db = db()

    val response = play(db)
    val room = roomOf(response)

    assertEquals(setOf("changed", "alreadyUnranked", "room"), response.keys)
    assertEquals(
      setOf(
        "roomId", "name", "status", "creator", "players", "readyPlayers",
        "mode", "rankDown", "gameType", "scoringMode", "matchId",
      ),
      room.keys,
    )
    assertEquals(ROOM_ID, room["roomId"])
    assertEquals(MatchMode.UNRANKED.name, room["mode"])

    // Every private word, key and value, searched for in the whole payload.
    val payload = rendered(response).lowercase()
    for (leak in listOf(
      "tier", "division", "seasonid", "highestrank", "placementstate", "demotionshield",
      "rp", "email", "coins", "hunter2", "bottier",
      "bronze", "silver", "gold", "platinum", "diamond", "royal", "king",
    )) {
      assertFalse(payload.contains(leak), "the response leaks '$leak'")
    }
  }

  // ── the pure decision ────────────────────────────────────────────────

  @Test
  fun theWriteCarriesExactlyTheModeAndTheClearedCredential() = runTest {
    // The seam that performs the transition can perform no other one: the
    // payload is a constant, so this is the whole write.
    assertEquals(
      linkedMapOf<String, Any?>(
        "mode" to MatchMode.UNRANKED.name,
        "rankDown" to false,
      ),
      unrankedWriteFields(),
    )
  }

  @Test
  fun theWholeWriteCarriesThePoolAnchorThatStartsTheFillWindow() = runTest {
    // The transition's own constant, plus the two server-owned stamps. The
    // anchor is an ARGUMENT, so it is this write's stamp and never the
    // room's `createdAt` — the property the four-minute window depends on.
    val updatedAt = "updated-stamp"
    val anchor = "pool-anchor"

    val write = unrankedConversionWrite(updatedAt = updatedAt, poolOpenedAt = anchor)

    assertEquals(
      setOf("mode", "rankDown", "updatedAt", "poolOpenedAt"),
      write.keys,
      "the conversion writes the transition and both server stamps",
    )
    assertEquals(MatchMode.UNRANKED.name, write["mode"])
    assertEquals(false, write["rankDown"])
    assertEquals(updatedAt, write["updatedAt"])
    assertEquals(anchor, write["poolOpenedAt"], "the anchor is the stamp it was given")
  }

  @Test
  fun theDecisionIsPureAndNamesTheSameOutcomes() = runTest {
    val allowed = decideUnrankedConversion(room(), hostUid)
    assertTrue(allowed is UnrankedConversion.Convert, "a host's Ranked room converts")

    val already = decideUnrankedConversion(
      room(mode = MatchMode.UNRANKED.name, rankDown = false),
      hostUid,
    )
    assertTrue(already is UnrankedConversion.AlreadyUnranked, "an Unranked room is a no-op")

    val refused = decideUnrankedConversion(room(), friendUid)
    assertTrue(refused is UnrankedConversion.Refused, "a non-host is refused")
    assertEquals(PlayUnranked.Reason.NOT_HOST, refused.reason)
  }

  private companion object {
    /**
     * Six characters — exactly the room code length, so the normalizer
     * uppercases it. Already uppercase, which is why the room-event id is this
     * literal in every assertion.
     */
    const val ROOM_ID = "AB12CD"

    /** The room's own creation stamp — an hour before the conversion. */
    const val ROOM_CREATED_AT = 1_700_000_000_000L

    /** The conversion's own clock: the pool anchor's value. */
    const val CONVERSION_NOW = ROOM_CREATED_AT + 3_600_000L
  }
}
