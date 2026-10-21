package com.estemshan.functions

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ranked progression executed against a real Firestore emulator — the half of
 * [settleOnce] that a fake port structurally cannot show:
 * - [AdminSettlementDb.writeProfile]'s `tx.update()` merges `rp`/`tier`/
 *   `division` into a live profile and leaves every field it does not own
 *   (displayName, the room/match pointers) exactly as it was;
 * - the four progression writes and the claim commit as ONE transaction, and
 *   the reads-all-before-writes ordering the SDK enforces is the ordering
 *   [settleOnce] actually issues — a fake only believes it;
 * - a fail-closed seat aborts the whole settlement: no claim document, and no
 *   other seat's progression written either — for a profile that is missing as
 *   well as for one whose `rp` is not a number;
 * - a redelivered call re-awards nothing, and each of the four callers reads
 *   back its own `you` from the one shared claim (RD13);
 * - `movements` survive the Firestore round trip as numbers, so a replay's
 *   `you` is the recorded award and not a shape mismatch.
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
 * Both ids AND uids carry an "s50rp-<nonce>" prefix: emulator suites share one
 * database with no clearing between runs, so a repeated uid would hand the
 * second run a profile that the first run already settled — and a settlement
 * that reads rp 732 proves nothing about applying +42.
 */
class SettlementRpEmulatorTest {

  // Epoch millis as a String: a Double's toString would put a "." in the doc id.
  private val nonce: String = kotlin.js.Date.now().toLong().toString()

  private fun emulatorReady(): Boolean {
    val host = (js("process.env.FIRESTORE_EMULATOR_HOST") as? String)?.takeIf { it.isNotEmpty() }
    if (host == null) {
      console.log("S51_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")
      return false
    }
    ensureDefaultAdminApp()
    return true
  }

  private fun id(tag: String): String = "s50rp-$tag-$nonce"

  /** A uid no other run of this class can collide with. */
  private fun uid(seat: String): String = id(seat)

  private val seats = listOf("u1", "u2", "u3", "u4")

  private fun matchRef(matchId: String): DocRef =
    getFirestore().collection("matches").doc(matchId)

  private fun playerRef(uid: String): DocRef =
    getFirestore().collection("players").doc(uid)

  private fun claimRef(matchId: String): DocRef =
    matchRef(matchId).collection("settlement").doc("settle")

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
   * A live account: the fields progression owns are ABSENT, and the two it does
   * not own are present so their survival is observable.
   */
  private suspend fun seedPlayer(nonceUid: String, rp: Int?) {
    val profile = LinkedHashMap<String, Any?>()
    profile["uid"] = nonceUid
    profile["displayName"] = "Same Name"
    profile["currentRoomId"] = "r-keep"
    if (rp != null) profile["rp"] = rp
    writeDoc(playerRef(nonceUid), profile)
  }

  private suspend fun seedTable(
    matchId: String,
    uids: List<String>,
    rps: Map<String, Int?>,
    gameType: String = "FULL",
  ) {
    for (uid in uids) seedPlayer(uid, rps[uid])
    writeDoc(matchRef(matchId), SettlementFixtures.matchFields(uids, gameType = gameType))
    writeDoc(
      matchRef(matchId).collection("roundArchive").doc("1"),
      SettlementFixtures.round1ArchiveFields(),
    )
  }

  /** The golden table's four landing rungs, hand-derived as in `RpApplicationTest`. */
  private fun expectedProgression(uids: List<String>): Map<String, Map<String, Any?>> = mapOf(
    uids[0] to mapOf("rp" to 732, "tier" to "Gold", "division" to "II"),
    uids[1] to mapOf("rp" to 682, "tier" to "Gold", "division" to "III"),
    uids[2] to mapOf("rp" to 690, "tier" to "Gold", "division" to "III"),
    uids[3] to mapOf("rp" to 717, "tier" to "Gold", "division" to "II"),
  )

  /** The three fields progression owns, projected out of a live profile. */
  private suspend fun progressionOf(uid: String): Map<String, Any?>? =
    fieldsOf(playerRef(uid))?.let { profile ->
      linkedMapOf(
        "rp" to (profile["rp"] as? Number)?.toInt(),
        "tier" to profile["tier"],
        "division" to profile["division"],
      )
    }

  @Test
  fun fourProfileWritesCommitWithTheClaimAndKeepTheFieldsTheyDoNotOwn() = runTest {
    if (!emulatorReady()) return@runTest
    // Deliberately FIRST: this class pays firebase-admin's cold start (module
    // load, default-app init, the first gRPC dial) once, and mocha caps a test
    // at its configured timeout. The remaining tests run warm.
    val matchId = id("commit")
    val uids = seats.map { uid(it) }
    seedTable(matchId, uids, uids.associateWith { 690 })

    val response = settleOnce(AdminSettlementDb(), SettlementFixtures.settleRequest(uids[0], matchId))

    assertEquals(true, response["settled"])
    assertEquals(
      mapOf("previousRp" to 690, "rp" to 732, "delta" to 42),
      response["you"],
      "the caller's own award, computed by the real engine over the real document",
    )
    val expected = expectedProgression(uids)
    for (uid in uids) {
      val profile = assertNotNull(
        fieldsOf(playerRef(uid)),
        "settlement must leave a profile for '$uid'",
      )
      // update(), not set(): the award lands and the rest of the account is
      // untouched — a set() would erase the name and the room pointer.
      assertEquals(expected[uid]?.get("rp"), profile["rp"], "rp for '$uid'")
      assertEquals(expected[uid]?.get("tier"), profile["tier"], "tier for '$uid'")
      assertEquals(expected[uid]?.get("division"), profile["division"], "division for '$uid'")
      assertEquals("Same Name", profile["displayName"], "displayName for '$uid'")
      assertEquals("r-keep", profile["currentRoomId"], "currentRoomId for '$uid'")
      assertNull(profile["rank"], "progression writes tier+division, never a rank alias")
    }
    // The claim exists, so the match is settled; both facts committed together.
    assertNotNull(fieldsOf(claimRef(matchId)), "the claim must commit with the writes")
  }

  @Test
  fun aRedeliveredCallReAwardsNothingAndEachCallerReadsItsOwnMovement() = runTest {
    if (!emulatorReady()) return@runTest
    val matchId = id("replay")
    val uids = seats.map { uid(it) }
    seedTable(matchId, uids, uids.associateWith { 690 })
    val db = AdminSettlementDb()

    val first = settleOnce(db, SettlementFixtures.settleRequest(uids[0], matchId))
    assertEquals(true, first["settled"])
    val claimBefore = assertNotNull(fieldsOf(claimRef(matchId)))
    val profilesBefore = uids.associateWith { assertNotNull(fieldsOf(playerRef(it))) }

    // The same call delivered again (a client retry) and a DIFFERENT caller
    // replaying the one claim document.
    val again = settleOnce(db, SettlementFixtures.settleRequest(uids[0], matchId))
    val neighbour = settleOnce(db, SettlementFixtures.settleRequest(uids[3], matchId))
    assertEquals(true, again["replayed"])
    assertEquals(true, neighbour["replayed"])
    assertEquals(first["you"], again["you"], "a retry answers with the recorded award")
    assertEquals(
      mapOf("previousRp" to 690, "rp" to 717, "delta" to 27),
      neighbour["you"],
      "p4's seat belongs to uids[3], and to nobody else",
    )
    assertNull(again["movements"])
    assertNull(neighbour["movements"])

    for (uid in uids) {
      assertEquals(
        profilesBefore[uid],
        fieldsOf(playerRef(uid)),
        "a redelivery must not move '$uid' again",
      )
    }
    // `settledAt` is excluded from the map comparison on purpose: each read
    // hands back a NEW Timestamp instance, so whole-document equality would
    // compare object identity. Its millis are pinned separately, which is the
    // stronger statement — the replay neither rewrote the record nor restamped
    // it.
    val claimAfter = assertNotNull(fieldsOf(claimRef(matchId)))
    assertEquals(claimBefore["movements"], claimAfter["movements"], "a replay must not rewrite the movements")
    assertEquals(claimBefore["outcome"], claimAfter["outcome"], "a replay must not rewrite the recorded outcome")
    assertEquals(
      timestampToMillis(claimBefore["settledAt"]),
      timestampToMillis(claimAfter["settledAt"]),
      "a replay must not restamp the claim it found",
    )

    // The stored record is caller-neutral and complete: four movements, and no
    // `you` baked into the outcome a different client will read back.
    @Suppress("UNCHECKED_CAST")
    val movements = claimBefore["movements"] as? Map<String, Any?>
    assertNotNull(movements, "the claim records the progression it applied")
    assertEquals(uids.toSet(), movements.keys)
    @Suppress("UNCHECKED_CAST")
    val outcome = claimBefore["outcome"] as Map<String, Any?>
    assertNull(outcome["you"], "one shared claim must not store a caller's slice")
    assertEquals(uids[0], claimBefore["settledBy"])

    // Numbers read back through Firestore are still numbers, so the replay's
    // slice is an award and not a shape mismatch.
    @Suppress("UNCHECKED_CAST")
    val mine = movements[uids[0]] as Map<String, Any?>
    assertEquals(732, (mine["rp"] as? Number)?.toInt(), "movements survive the round trip")
    assertEquals(42, (mine["delta"] as? Number)?.toInt())
  }

  /**
   * The fail-closed seat is the LAST one read, so the three good profiles have
   * already been read — and still nothing writes. That is the whole
   * all-reads-before-any-write discipline, executed: a Ranked table applies
   * four awards or applies none, and the absent claim leaves the match
   * settleable once the missing profile exists.
   */
  @Test
  fun aMissingProfileAbortsEverySeat() = runTest {
    if (!emulatorReady()) return@runTest
    val matchId = id("abort")
    val uids = seats.map { uid(it) }
    // uids[3] gets a seat in the match and no players/ document.
    for (uid in uids.take(3)) seedPlayer(uid, 690)
    writeDoc(matchRef(matchId), SettlementFixtures.matchFields(uids))
    writeDoc(
      matchRef(matchId).collection("roundArchive").doc("1"),
      SettlementFixtures.round1ArchiveFields(),
    )

    val thrown = try {
      settleOnce(AdminSettlementDb(), SettlementFixtures.settleRequest(uids[0], matchId))
      throw AssertionError("expected a fail-closed denial")
    } catch (e: Throwable) {
      if (e.message == "expected a fail-closed denial") throw e
      e
    }
    assertEquals("failed-precondition", thrown.asDynamic().code as String)
    assertTrue(
      thrown.message?.contains(uids[3]) == true,
      "the denial must name the uid whose profile is missing, was: ${thrown.message}",
    )

    assertNull(fieldsOf(claimRef(matchId)), "a failed settlement writes no claim")
    for (uid in uids.take(3)) {
      val profile = assertNotNull(fieldsOf(playerRef(uid)))
      assertEquals(690, profile["rp"], "'$uid' must keep its pre-settlement rp")
      assertNull(profile["tier"], "no seat may be settled ahead of the others")
      assertNull(profile["division"])
    }
  }

  /**
   * The other fail-closed seat, executed: a profile that exists but whose `rp`
   * came back as a string. `dynamicToKotlin` hands the raw value through, so
   * this is the real shape of a corrupt field — and reading it as 0 RP would
   * award a Gold player a first match's +30 on top of a live account. Same
   * discipline as a missing document: the seat that is fine until the last one
   * still gets nothing.
   */
  @Test
  fun aNonNumericRpAbortsEverySeat() = runTest {
    if (!emulatorReady()) return@runTest
    val matchId = id("corrupt")
    val uids = seats.map { uid(it) }
    for (uid in uids.take(3)) seedPlayer(uid, 690)
    writeDoc(
      playerRef(uids[3]),
      linkedMapOf<String, Any?>(
        "uid" to uids[3],
        "displayName" to "Same Name",
        "rp" to "690",
      ),
    )
    writeDoc(matchRef(matchId), SettlementFixtures.matchFields(uids))
    writeDoc(
      matchRef(matchId).collection("roundArchive").doc("1"),
      SettlementFixtures.round1ArchiveFields(),
    )

    val thrown = try {
      settleOnce(AdminSettlementDb(), SettlementFixtures.settleRequest(uids[0], matchId))
      throw AssertionError("expected a fail-closed denial")
    } catch (e: Throwable) {
      if (e.message == "expected a fail-closed denial") throw e
      e
    }
    assertEquals("failed-precondition", thrown.asDynamic().code as String)
    assertTrue(
      thrown.message?.contains("non-numeric players/rp") == true,
      "the denial must name the corrupt field, was: ${thrown.message}",
    )
    assertTrue(
      thrown.message?.contains(uids[3]) == true,
      "the denial must name the uid whose rp is corrupt, was: ${thrown.message}",
    )

    assertNull(fieldsOf(claimRef(matchId)), "a failed settlement writes no claim")
    for (uid in uids) {
      val profile = assertNotNull(fieldsOf(playerRef(uid)))
      assertNull(profile["tier"], "no seat may be settled ahead of the others")
      assertNull(profile["division"])
    }
    assertEquals("690", fieldsOf(playerRef(uids[3]))?.get("rp"), "settlement repairs nothing")
  }

  /**
   * RD30 through the real SDK: a profile document that EXISTS but carries no
   * `rp` is an unplaced account, so it reads as 0 and the first award writes
   * 30 onto Bronze III. This is the case the fake answers with `emptyMap()` and
   * the emulator answers with a snapshot whose data() has no key — the missing
   * FIELD path, kept separate from the missing DOCUMENT path above.
   */
  @Test
  fun anUnplacedProfileIsPlacedByItsFirstSettlement() = runTest {
    if (!emulatorReady()) return@runTest
    val matchId = id("unplaced")
    val uids = seats.map { uid(it) }
    seedTable(matchId, uids, uids.associateWith { null })

    val response = settleOnce(
      AdminSettlementDb(),
      SettlementFixtures.settleRequest(uids[1], matchId),
    )

    // A table of four Bronze IIIs: KING 30, SECOND 15, and both losers floored
    // at 0 RP with an effective delta of 0 (RD3).
    assertEquals(mapOf("rp" to 30, "tier" to "Bronze", "division" to "III"), progressionOf(uids[0]))
    assertEquals(mapOf("rp" to 15, "tier" to "Bronze", "division" to "III"), progressionOf(uids[3]))
    assertEquals(
      mapOf("previousRp" to 0, "rp" to 0, "delta" to 0),
      response["you"],
      "the KOZ seat loses nothing it does not have",
    )
    assertEquals(
      mapOf("rp" to 0, "tier" to "Bronze", "division" to "III"),
      progressionOf(uids[1]),
    )
    assertEquals("Same Name", fieldsOf(playerRef(uids[1]))?.get("displayName"))
  }
}
