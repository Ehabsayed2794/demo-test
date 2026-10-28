package com.estemshan.functions

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S71 — the "Play Unranked" conversion executed against a real Firestore emulator.
 *
 * The half a fake [PlayUnrankedDb] structurally cannot show:
 * - [AdminPlayUnrankedDb]'s write really lands on the stored room, and lands
 *   the WHOLE conversion — `mode`, the cleared credential, `updatedAt` **and**
 *   the pool anchor — in the one update the Admin writer makes;
 * - the pool anchor is a **resolved server timestamp**, and it is a FRESH one:
 *   it is not the room's `createdAt`, which is what a conversion that forgot
 *   the anchor would silently inherit and hand the table to the bots on the
 *   next resolver pass;
 * - a re-conversion (an Unranked room that still carried the credential)
 *   really **re-anchors** the window, and an idempotent second press really
 *   writes NOTHING — so a double tap cannot restart the four-minute clock;
 * - every field the conversion does not own survives the write byte for byte.
 *
 * The unit suite ([PlayUnrankedTest]) pins the same write through a fake that
 * models it; this suite pins it on storage, where the anchor's identity is a
 * Firestore server timestamp and not a number the fake chose.
 *
 * SKIPS ITSELF (loudly) unless FIRESTORE_EMULATOR_HOST is set, so the ordinary
 * CI run of :functions:jsNodeTest neither fails nor quietly depends on an
 * emulator. To run it, the already-cached jar and no installation:
 *   java -jar ~/.cache/firebase/emulators/cloud-firestore-emulator-*.jar \
 *     --host 127.0.0.1 --port 8992 --rules firestore.rules --project_id s71-verify
 *   FIRESTORE_EMULATOR_HOST=127.0.0.1:8992 GCLOUD_PROJECT=s71-verify \
 *     ./native/gradlew.bat -p native :functions:jsNodeTest
 * GCLOUD_PROJECT is required because [ensureDefaultAdminApp] initializes the
 * bare default app, exactly as the Functions runtime does in production.
 *
 * Ids AND uids carry an "s71-<nonce>" prefix: emulator suites share one
 * database with no clearing between runs, and a room that a previous run
 * already converted would make this run's assertions prove nothing.
 */
class PlayUnrankedEmulatorTest {

  // Epoch millis as a String: a Double's toString would put a "." in the doc id.
  private val nonce: String = kotlin.js.Date.now().toLong().toString()

  /**
   * The skip gate AND the default-app init, in that order.
   *
   * Seeding calls [getFirestore] directly, before any [AdminPlayUnrankedDb]
   * exists to run its own `ensureDefaultAdminApp()`, and firebase-admin refuses
   * with "The default Firebase app does not exist". Init therefore belongs
   * behind the guard, not in a field initializer.
   */
  private fun emulatorReady(): Boolean {
    val host = (js("process.env.FIRESTORE_EMULATOR_HOST") as? String)?.takeIf { it.isNotEmpty() }
    if (host == null) {
      console.log("S71_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")
      return false
    }
    ensureDefaultAdminApp()
    return true
  }

  private fun id(tag: String): String = "s71-$tag-$nonce"

  private val hostUid = id("host")
  private val guestUid = id("guest")

  private fun roomRef(roomId: String): DocRef = getFirestore().collection("rooms").doc(roomId)

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

  /**
   * A FIELD-LEVEL write — the rest of the document survives.
   *
   * `set` would replace the whole room and take the conversion's own fields
   * with it, so a probe written that way would be testing a different room.
   */
  private suspend fun patchDoc(ref: DocRef, data: Map<String, Any?>) {
    getFirestore()
      .runTransaction { tx ->
        tx.update(ref, toPlain(data))
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
   * A stored value as a RESOLVED server timestamp, or null when it is not one.
   *
   * A plain number is not a server timestamp: [timestampToMillis] accepts both
   * (so a fixture can supply millis), which is exactly why the two cannot be
   * told apart by that call alone. The anchor has to be a real Timestamp —
   * that is what "the server stamped it" means on storage.
   */
  private fun serverStampMillis(value: Any?): Long? =
    if (value is Number) null else timestampToMillis(value)

  /**
   * The room as a client would have left it: a Rank-down room (RD28: mode
   * RANKED + the private flag) with a creation stamp from long ago, so a
   * conversion that inherited the creation time instead of stamping its own
   * would be visible.
   */
  private fun room(
    mode: String = "RANKED",
    rankDown: Boolean = true,
    poolOpenedAt: Long? = null,
  ): Map<String, Any?> = linkedMapOf(
    "name" to "s71-room",
    "status" to "waiting",
    "creator" to hostUid,
    "players" to listOf(hostUid, guestUid),
    "readyPlayers" to emptyList<String>(),
    "mode" to mode,
    "rankDown" to rankDown,
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "matchId" to null,
    "botTier" to "MEDIUM",
    "createdAt" to ROOM_CREATED_AT,
    // A plain number the conversion must replace with a real server timestamp:
    // 1 ms after the epoch means "never touched by this run" for the assertion.
    "updatedAt" to 1L,
  ).apply {
    if (poolOpenedAt != null) put("poolOpenedAt", poolOpenedAt)
  }

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
  fun theConversionStoresUnrankedAndClearsTheCredential() = runTest {
    if (!emulatorReady()) return@runTest
    val code = id("convert")
    writeDoc(roomRef(code), room())

    val response = playUnrankedOnce(AdminPlayUnrankedDb(), request(hostUid, code))

    assertEquals(true, response["changed"], "response: $response")
    assertEquals(false, response["alreadyUnranked"])
    @Suppress("UNCHECKED_CAST")
    val projected = response["room"] as Map<String, Any?>
    assertEquals("UNRANKED", projected["mode"])
    assertEquals(false, projected["rankDown"])

    val stored = assertNotNull(fieldsOf(roomRef(code)))
    assertEquals("UNRANKED", stored["mode"], "the mode is persisted as UNRANKED")
    assertEquals(false, stored["rankDown"], "the access credential is cleared")
    assertNotNull(
      serverStampMillis(stored["updatedAt"]),
      "updatedAt is re-stamped with a server timestamp, not a number",
    )
    assertNotNull(
      serverStampMillis(stored["poolOpenedAt"]),
      "the pool anchor is a server timestamp, not a number",
    )

    // Every field the conversion does not own survives the write.
    assertEquals(hostUid, stored["creator"])
    @Suppress("UNCHECKED_CAST")
    assertEquals(listOf(hostUid, guestUid), stored["players"] as List<String>)
    assertEquals("s71-room", stored["name"])
    assertEquals("FULL", stored["gameType"])
    assertEquals("NORMAL", stored["scoringMode"])
    assertEquals("waiting", stored["status"])
    assertEquals("MEDIUM", stored["botTier"])
    @Suppress("UNCHECKED_CAST")
    assertEquals(emptyList<String>(), stored["readyPlayers"] as List<String>)
  }

  @Test
  fun thePoolAnchorIsAFreshStampAndNotTheRoomsCreationStamp() = runTest {
    if (!emulatorReady()) return@runTest
    val code = id("anchor")
    writeDoc(roomRef(code), room())

    playUnrankedOnce(AdminPlayUnrankedDb(), request(hostUid, code))

    val stored = assertNotNull(fieldsOf(roomRef(code)))
    val anchor = assertNotNull(
      serverStampMillis(stored["poolOpenedAt"]),
      "the pool anchor is a resolved server timestamp",
    )
    assertTrue(
      anchor > ROOM_CREATED_AT,
      "the anchor is the conversion's own stamp, not the room's creation stamp: " +
        "anchor=$anchor createdAt=$ROOM_CREATED_AT",
    )
    assertEquals(
      ROOM_CREATED_AT,
      timestampToMillis(stored["createdAt"]),
      "the creation stamp is untouched",
    )
  }

  @Test
  fun aReConversionReAnchorsTheWindow() = runTest {
    if (!emulatorReady()) return@runTest
    val code = id("reanchor")
    // Already Unranked, but the credential is still there: the one case where
    // the conversion writes on an Unranked room. The stale anchor below is
    // what a re-anchor has to replace.
    writeDoc(roomRef(code), room(mode = "UNRANKED", rankDown = true, poolOpenedAt = 1L))

    val response = playUnrankedOnce(AdminPlayUnrankedDb(), request(hostUid, code))

    assertEquals(true, response["changed"], "response: $response")
    assertEquals(true, response["alreadyUnranked"])
    val stored = assertNotNull(fieldsOf(roomRef(code)))
    assertEquals("UNRANKED", stored["mode"])
    assertEquals(false, stored["rankDown"], "the credential is cleared")
    val anchor = assertNotNull(
      serverStampMillis(stored["poolOpenedAt"]),
      "the stale anchor was replaced by a resolved server timestamp",
    )
    assertTrue(
      anchor > ROOM_CREATED_AT,
      "the window is re-anchored on the conversion, not on the room's creation: " +
        "anchor=$anchor createdAt=$ROOM_CREATED_AT",
    )
  }

  @Test
  fun aSecondPressWritesNothingAndDoesNotRestartTheWindow() = runTest {
    if (!emulatorReady()) return@runTest
    val code = id("twice")
    writeDoc(roomRef(code), room())

    val first = playUnrankedOnce(AdminPlayUnrankedDb(), request(hostUid, code))
    assertEquals(true, first["changed"])

    // A sentinel the conversion must NOT touch. Comparing two server
    // timestamps instead would be millisecond-sensitive — a second write
    // inside the same millisecond would read identically — while a sentinel
    // survives exactly one thing: a write that replaces it.
    patchDoc(roomRef(code), mapOf("poolOpenedAt" to ANCHOR_SENTINEL))

    val second = playUnrankedOnce(AdminPlayUnrankedDb(), request(hostUid, code))

    assertEquals(false, second["changed"], "the second press finds the room already there")
    assertEquals(true, second["alreadyUnranked"])
    val afterSecond = assertNotNull(fieldsOf(roomRef(code)))
    assertEquals("UNRANKED", afterSecond["mode"])
    assertEquals(
      ANCHOR_SENTINEL,
      timestampToMillis(afterSecond["poolOpenedAt"]),
      "an idempotent press writes nothing, so it cannot re-anchor the window",
    )
    assertEquals(
      ROOM_CREATED_AT,
      timestampToMillis(afterSecond["createdAt"]),
      "the creation stamp is untouched by an idempotent press",
    )
  }

  private companion object {
    /** A creation stamp from long ago: 2023-11-14, in epoch millis. */
    const val ROOM_CREATED_AT = 1_700_000_000_000L

    /** The probe value: a stamp only a write would replace. */
    const val ANCHOR_SENTINEL = 12_345L
  }
}
