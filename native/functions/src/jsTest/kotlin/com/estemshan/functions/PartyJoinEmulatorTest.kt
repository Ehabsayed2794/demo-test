package com.estemshan.functions

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S70 — the Rank-down join gate executed against a real Firestore emulator.
 *
 * The half a fake [PartyJoinDb] structurally cannot show:
 * - [AdminPartyJoinDb]'s reads and its one write actually run inside one
 *   transaction, in the order Firestore demands, against live documents;
 * - `writePlayers` really replaces `players` and really stamps `updatedAt` with
 *   a server timestamp that READS BACK as a resolved timestamp, which is what
 *   the client's own room listener then sees;
 * - the projection is asserted over a document that carries a private half
 *   (`botTier`, a password), so "nothing rank-bearing left" is proven against
 *   the shapes that could actually leak rather than against an empty document;
 * - a denial leaves the room byte-for-byte as it was — the fail-closed promise
 *   [joinOnce] makes is checked on storage, not on a fake's event log.
 *
 * SKIPS ITSELF (loudly) unless FIRESTORE_EMULATOR_HOST is set, so the ordinary
 * CI run of :functions:jsNodeTest neither fails nor quietly depends on an
 * emulator. To run it, the already-cached jar and no installation:
 *   java -jar ~/.cache/firebase/emulators/cloud-firestore-emulator-*.jar \
 *     --host 127.0.0.1 --port 8992 --rules firestore.rules --project_id s51-verify
 *   FIRESTORE_EMULATOR_HOST=127.0.0.1:8992 GCLOUD_PROJECT=s51-verify \
 *     ./native/gradlew.bat -p native :functions:jsNodeTest
 * GCLOUD_PROJECT is required because [ensureDefaultAdminApp] initializes the
 * bare default app, exactly as the Functions runtime does in production.
 *
 * Ids AND uids carry an "s70-<nonce>" prefix: emulator suites share one database
 * with no clearing between runs, and a room that a previous run already joined
 * would make this run's append assertion prove nothing.
 */
class PartyJoinEmulatorTest {

  // Epoch millis as a String: a Double's toString would put a "." in the doc id.
  private val nonce: String = kotlin.js.Date.now().toLong().toString()

  /**
   * The skip gate AND the default-app init, in that order.
   *
   * Seeding calls [getFirestore] directly, before any [AdminPartyJoinDb] exists
   * to run its own `ensureDefaultAdminApp()`, and firebase-admin refuses with
   * "The default Firebase app does not exist". Init therefore belongs behind the
   * guard, not in a field initializer: a field would initialize the app on every
   * CI run — emulator or not — and an app init that cannot find a project would
   * fail these tests for the wrong reason.
   */
  private fun emulatorReady(): Boolean {
    val host = (js("process.env.FIRESTORE_EMULATOR_HOST") as? String)?.takeIf { it.isNotEmpty() }
    if (host == null) {
      console.log("S70_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")
      return false
    }
    ensureDefaultAdminApp()
    return true
  }

  private fun id(tag: String): String = "s70-$tag-$nonce"

  private val hostUid = id("host")
  private val joinerUid = id("joiner")

  private fun roomRef(roomId: String): DocRef = getFirestore().collection("rooms").doc(roomId)

  private fun playerRef(uid: String): DocRef = getFirestore().collection("players").doc(uid)

  private suspend fun writeDoc(ref: DocRef, data: Map<String, Any?>) {
    // set() exists only on Transaction in this module's interop surface, and a
    // one-shot commit is the honest way to seed a document from a test.
    getFirestore()
      .runTransaction { tx ->
        tx.set(ref, toPlain(data))
        Promise.resolve<Any?>(null)
      }
      .await()
  }

  private suspend fun fieldsOf(ref: DocRef): Map<String, Any?>? {
    val snap = ref.get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  /**
   * A stored profile as S50's write path stores one: the rung and its `rp` agree
   * (RD3), so the gate reads a real identity instead of tripping the fail-closed
   * path by accident.
   */
  private fun profileOf(tier: String, division: String, rp: Int): Map<String, Any?> =
    linkedMapOf(
      "displayName" to "P",
      "tier" to tier,
      "division" to division,
      "rp" to rp,
      "seasonId" to "s1",
      "highestRankTier" to tier,
      "highestRankDivision" to division,
      "placementState" to "COMPLETE",
      "demotionShield" to false,
    )

  /**
   * A Rank-down room (RD28: mode RANKED + the private flag) with its private
   * half intact. No `roomId` field: the document id IS the code, exactly as
   * [RoomDoc] stores it, which is why the gate re-stamps it on the projection.
   */
  private fun rankDownRoom(creator: String, players: List<String>): Map<String, Any?> =
    linkedMapOf(
      "name" to "s70-room",
      "status" to "waiting",
      "creator" to creator,
      "players" to players,
      "readyPlayers" to emptyList<String>(),
      "mode" to "RANKED",
      "rankDown" to true,
      "gameType" to "FULL",
      "scoringMode" to "NORMAL",
      "matchId" to null,
      // The projection must not carry these, and only a document that HAS them
      // can prove it.
      "botTier" to "MEDIUM",
      "password" to "hunter2",
      // A plain number the gate must replace with a real server timestamp: 1 ms
      // after the epoch means "never touched by this run" for the assertion.
      "updatedAt" to 1L,
    )

  private fun request(callerUid: String, roomId: String): CallableRequest = object : CallableRequest {
    // A plain JS object, exactly as the Functions runtime delivers it — the
    // shape a Kotlin map fixture would not exercise.
    override val data: Any? = json("roomId" to roomId)
    override val auth: AuthData? = object : AuthData {
      override val uid: String = callerUid
      override val token: AuthToken? = null
    }
    override val instanceIdToken: String? = null
    override val rawRequest: Any? = null
    override val app: Any? = null
  }

  @Test
  fun anEligibleJoinAppendsTheCallerOnceAndCarriesNoRank() = runTest {
    if (!emulatorReady()) return@runTest
    val code = id("eligible")
    writeDoc(playerRef(hostUid), profileOf("Gold", "I", 825))
    writeDoc(playerRef(joinerUid), profileOf("Silver", "III", 250))
    writeDoc(roomRef(code), rankDownRoom(hostUid, listOf(hostUid)))

    val response = joinOnce(AdminPartyJoinDb(), request(joinerUid, code))

    assertEquals(true, response["joined"], "response: $response")
    @Suppress("UNCHECKED_CAST")
    val room = response["room"] as Map<String, Any?>
    assertEquals(
      setOf(
        "roomId", "name", "status", "creator", "players", "readyPlayers",
        "mode", "rankDown", "gameType", "scoringMode", "matchId",
      ),
      room.keys,
    )
    assertEquals(code, room["roomId"])
    @Suppress("UNCHECKED_CAST")
    assertEquals(listOf(hostUid, joinerUid), room["players"] as List<String>)

    // Nothing rank-bearing on either level of the payload.
    for (key in listOf("rp", "tier", "division", "seasonId", "highestRankTier",
      "highestRankDivision", "placementState", "demotionShield", "botTier", "password",
    )) {
      assertNull(room[key], "the projection must not carry '$key'")
    }

    val stored = assertNotNull(fieldsOf(roomRef(code)))
    @Suppress("UNCHECKED_CAST")
    val players = stored["players"] as List<String>
    assertEquals(listOf(hostUid, joinerUid), players, "appended exactly once")
    assertEquals(1, players.count { it == joinerUid })
    val updatedAt = assertNotNull(timestampToMillis(stored["updatedAt"]), "updatedAt is a server timestamp")
    assertTrue(updatedAt > 1L, "updatedAt moved: $updatedAt")
    // The gate wrote players + updatedAt and reconfigured nothing.
    assertEquals("RANKED", stored["mode"])
    assertEquals(true, stored["rankDown"])
    assertEquals("hunter2", stored["password"])
  }

  @Test
  fun anIncompatibleJoinDeniesAndLeavesTheRoomUntouched() = runTest {
    if (!emulatorReady()) return@runTest
    val code = id("incompatible")
    writeDoc(playerRef(hostUid), profileOf("Gold", "I", 825))
    writeDoc(playerRef(joinerUid), profileOf("Bronze", "II", 75))
    writeDoc(roomRef(code), rankDownRoom(hostUid, listOf(hostUid)))

    val before = assertNotNull(fieldsOf(roomRef(code)))
    val response = joinOnce(AdminPartyJoinDb(), request(joinerUid, code))

    assertEquals(false, response["joined"])
    assertEquals("RANK_INCOMPATIBLE", response["reason"])
    assertEquals(setOf("joined", "reason", "message"), response.keys)
    val message = (response["message"] as String).lowercase()
    for (tier in listOf("bronze", "silver", "gold", "platinum", "diamond", "royal", "king")) {
      assertTrue(!message.contains(tier), "the sentence names tier '$tier': $message")
    }

    val after = assertNotNull(fieldsOf(roomRef(code)))
    @Suppress("UNCHECKED_CAST")
    assertEquals(listOf(hostUid), after["players"] as List<String>, "a denial writes no player")
    assertEquals(1L, timestampToMillis(after["updatedAt"]), "a denial does not stamp the room")
    assertEquals(before, after)
  }

  @Test
  fun aSecondJoinIsIdempotentAndNeverDuplicatesTheCaller() = runTest {
    if (!emulatorReady()) return@runTest
    val code = id("twice")
    writeDoc(playerRef(hostUid), profileOf("Gold", "I", 825))
    writeDoc(playerRef(joinerUid), profileOf("Silver", "III", 250))
    writeDoc(roomRef(code), rankDownRoom(hostUid, listOf(hostUid)))

    val first = joinOnce(AdminPartyJoinDb(), request(joinerUid, code))
    val second = joinOnce(AdminPartyJoinDb(), request(joinerUid, code))

    assertEquals(true, first["joined"])
    assertEquals(true, second["joined"], "a re-join is an answer, not an error: $second")
    @Suppress("UNCHECKED_CAST")
    val stored = assertNotNull(fieldsOf(roomRef(code)))["players"] as List<String>
    assertEquals(listOf(hostUid, joinerUid), stored)
    assertEquals(1, stored.count { it == joinerUid }, "the caller appears exactly once")
  }
}
