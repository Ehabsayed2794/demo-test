package com.estemshan.functions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S64 — the Admin-SDK half of the leaderboard read, executed against a real
 * Firestore emulator.
 *
 * This class exists because of what [SeasonLeaderboardTest] structurally cannot
 * check. Every test there runs on a fake [LeaderboardDb], so the fake proves the
 * logic AROUND the query while the query itself —
 * `db.collection("players").where("seasonId", "==", season).get()` — is executed
 * by nothing at all. An `external` declaration is never checked against the real
 * object: a `whereEqualTo(fieldPath, value)` member that the pinned
 * firebase-admin 13.10.0 does not have compiled cleanly, passed every fake-port
 * test, and would have thrown `db.whereEqualTo is not a function` on the FIRST
 * live request. Only calling the Admin port for real can see that, so this class
 * does, and it fails at the SDK boundary rather than inside a stub.
 *
 * What the executed query pins, each in a different way:
 * - the method exists, and the three-argument `(fieldPath, opStr, value)` shape
 *   is the SDK's;
 * - the operator string is one the SDK accepts ("Unknown comparison operator"
 *   otherwise);
 * - the filter runs SERVER-side — a nonce-unique season read returns exactly
 *   this run's documents, so a Kotlin-side loop over a full collection scan
 *   cannot masquerade as a filter;
 * - a real Firestore document's stored types survive [classify] (an `rp` that
 *   came back anything but a JS number would land in `skipped`, not `total`);
 * - the display-safe projection holds against documents that really carry
 *   email, coins and a room id.
 *
 * SKIPS ITSELF (loudly) unless FIRESTORE_EMULATOR_HOST is set, so the ordinary
 * CI run of :functions:jsNodeTest neither fails nor quietly depends on an
 * emulator. To run it, the already-cached jar and no installation:
 *   java -jar ~/.cache/firebase/emulators/cloud-firestore-emulator-*.jar \
 *     --host 127.0.0.1 --port 8991 --project_id s64-verify
 *   FIRESTORE_EMULATOR_HOST=127.0.0.1:8991 GCLOUD_PROJECT=s64-verify \
 *     ./native/gradlew.bat -p native :functions:jsNodeTest
 * GCLOUD_PROJECT is required because [AdminLeaderboardDb] initializes the bare
 * default app through [ensureDefaultAdminApp], exactly as the runtime does.
 * `--rules` is deliberately absent: the Admin SDK bypasses firestore.rules, and
 * leaving the default rules off keeps this class about the query, not the rules.
 *
 * Season and doc ids both carry an "s64e-<nonce>" tag. Emulator suites share ONE
 * database with no clearing between runs, so a fixed season would accumulate a
 * document from every past run and turn an exact read into a growing one — and a
 * repeated doc id silently turns a create into an update.
 */
class SeasonLeaderboardEmulatorTest {

  // Epoch millis as a String: a Double's toString would put a "." in the doc id.
  private val nonce: String = kotlin.js.Date.now().toLong().toString()

  private fun playerId(tag: String): String = "s64e-$tag-$nonce"

  private fun season(tag: String): String = "s64e-$nonce-$tag"

  private fun emulatorReady(): Boolean {
    val host = (js("process.env.FIRESTORE_EMULATOR_HOST") as? String)?.takeIf { it.isNotEmpty() }
    if (host == null) console.log("S64_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")
    return host != null
  }

  /** A `players/{uid}` document as S51's write path stores it — private fields included. */
  private fun profileFields(
    seasonId: String,
    tier: String,
    division: String,
    rp: Any?,
    displayName: String,
  ): Map<String, Any?> = linkedMapOf(
    "seasonId" to seasonId,
    "tier" to tier,
    "division" to division,
    "rp" to rp,
    "displayName" to displayName,
    "avatarInitial" to displayName.take(1),
    // Present on purpose: the projection has to survive a document that really
    // holds these, not a fixture that simply leaves them out.
    "email" to "$displayName@estemshan.test",
    "coins" to 5_000,
    "gems" to 12,
    "currentRoomId" to "s64e-room",
  )

  private val SeedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /**
   * The seeding client, initialized the way every Admin DB in this module
   * initializes its own: [ensureDefaultAdminApp] before `getFirestore()`. A bare
   * `getFirestore()` here would reach the emulator never, because firebase-admin
   * never creates the default app on its own and the call throws
   * "The default Firebase app does not exist" before it opens a socket — which
   * is exactly what it did in the first run of this class, on the very line that
   * seeds, while the tests that only constructed [AdminLeaderboardDb] passed.
   */
  private val seedDb: FirestoreDb by lazy {
    ensureDefaultAdminApp()
    getFirestore()
  }

  /**
   * Written inside a transaction because this interop declares `set` only on
   * Transaction — the minimal surface settlement pinned. Seeding must not widen
   * the SDK surface the production read path is allowed to depend on.
   */
  private suspend fun seed(id: String, fields: Map<String, Any?>) {
    val ref = seedDb.collection("players").doc(id)
    seedDb.runTransaction { tx -> SeedScope.promise { tx.set(ref, toPlain(fields)) } }.await()
  }

  private fun request(uid: String?, seasonId: Any?): CallableRequest = object : CallableRequest {
    // A plain JS object, exactly as the runtime delivers it — and with the
    // seasonId key genuinely absent when the caller sent none, which is the
    // request shape the default-season rule turns on.
    override val data: Any? = plainData(seasonId)
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

  private fun plainData(seasonId: Any?): Any? {
    val data: dynamic = js("({})")
    if (seasonId != null) data.seasonId = seasonId
    return data
  }

  @Suppress("UNCHECKED_CAST")
  private fun rows(response: Map<String, Any?>): List<Map<String, Any?>> =
    response["rows"] as List<Map<String, Any?>>

  @Suppress("UNCHECKED_CAST")
  private fun you(response: Map<String, Any?>): Map<String, Any?>? = response["you"] as Map<String, Any?>?

  /** Every key and value the response actually carries, flattened to one string. */
  private fun rendered(value: Any?): String = when (value) {
    is Map<*, *> -> value.entries.joinToString("|") { "${it.key}=${rendered(it.value)}" }
    is List<*> -> value.joinToString("|") { rendered(it) }
    null -> ""
    else -> value.toString()
  }

  // ── the query itself, executed ───────────────────────────────────────

  @Test
  fun theSeasonFilterIsARealFirestoreFilterAndNotAKotlinLoop() = runTest {
    if (!emulatorReady()) return@runTest
    // Deliberately FIRST in the class: every Node process pays firebase-admin's
    // cold start (module load, app init, the first gRPC dial) once, and this is
    // the smallest test that can absorb it.
    val current = season("filter")
    val other = season("filter-other")
    val goldOne = playerId("f-gold-one")
    val goldTwo = playerId("f-gold-two")
    val staleKing = playerId("f-king-other-season")
    seed(goldOne, profileFields(current, "Gold", "I", 900, "Ahmed"))
    seed(goldTwo, profileFields(current, "Gold", "II", 700, "Sara"))
    seed(staleKing, profileFields(other, "King", "", 3_200, "Omar"))

    // THE regression pin. This one line is the only executed leaderboard query
    // in the story: a wrong method name throws TypeError, a wrong operator string
    // throws "Unknown comparison operator", a wrong field path returns nothing,
    // and a filter that was really a Kotlin loop returns the King too.
    val read = AdminLeaderboardDb().readSeasonProfiles(current)

    assertEquals(listOf(goldOne, goldTwo).sorted(), read.map { it.playerId }.sorted())
    assertTrue(staleKing !in read.map { it.playerId }, "the query leaked another season's document")
    // The port's own contract, on real documents: the id is the doc id and the
    // fields are the stored map, converted — not a JS object that fails a cast.
    assertEquals(listOf(current, current), read.map { it.fields["seasonId"] })
  }

  @Test
  fun leaderboardOnceOverTheAdminPortOrdersRealDocumentsAndRefusesTheBadOne() = runTest {
    if (!emulatorReady()) return@runTest
    val current = season("board")
    val badRp = playerId("b-bad-rp")
    seed(playerId("b-king"), profileFields(current, "King", "", 3_200, "King Player"))
    seed(playerId("b-gold"), profileFields(current, "Gold", "I", 900, "Gold Player"))
    seed(playerId("b-bronze"), profileFields(current, "Bronze", "III", 10, "Bronze Player"))
    // A real document the board must refuse to rank: rp stored as a STRING.
    seed(badRp, profileFields(current, "Silver", "I", "460", "Bad RP Player"))
    // A complete, higher row in a different season: the query must not return it,
    // so it never even reaches the classifier.
    seed(playerId("b-stale"), profileFields(season("board-other"), "Royal", "I", 2_800, "Stale Player"))

    val response = leaderboardOnce(AdminLeaderboardDb(), request(playerId("b-bronze"), current))
    val delivered = rows(response)

    // Rung first, RP within it (RD3/RD27) — computed by :engine over documents
    // that came out of a real Firestore, positions already assigned.
    assertEquals(listOf("King Player", "Gold Player", "Bronze Player"), delivered.map { it["displayName"] })
    assertEquals(listOf(1, 2, 3), delivered.map { it["position"] })
    // RP arrives as a JS number — the same `is Number` contract [boardableRp]
    // relies on, which is what makes the total below a type proof.
    assertEquals(listOf(3_200, 900, 10), delivered.map { (it["rp"] as Number).toInt() })
    // The string rp is skipped, not ranked and not guessed: 3 rows on the board,
    // 1 unusable document. And Royal I from the other season is absent entirely.
    assertEquals(3, response["total"])
    assertEquals(1, response["skipped"])
    assertEquals(current, response["seasonId"])
    val deliveredNames = delivered.map { it["displayName"] }
    assertTrue("Stale Player" !in deliveredNames)
    // A skipped profile's fields stay private, including its name.
    assertTrue("Bad RP Player" !in deliveredNames)

    // The caller is highlighted from the verified uid, at their real position,
    // with the King's empty division carried through for the renderer.
    val youRow = you(response) ?: throw AssertionError("the caller is on the board")
    assertEquals(3, youRow["position"])
    assertEquals("Bronze", youRow["tier"])
    assertEquals(true, youRow["isCurrentPlayer"])
    assertEquals("", delivered.first()["division"])

    // The projection, against documents that genuinely hold the private fields.
    // Every doc id this run wrote, plus the currency, the contact address and
    // the room — none may appear anywhere in the payload. (The run nonce is NOT
    // in this list on purpose: every row carries its `seasonId`, and the season
    // id here embeds the nonce, so it is a legitimate value, not a leak.)
    val payload = rendered(response)
    for (leak in listOf(
      playerId("b-king"), playerId("b-gold"), playerId("b-bronze"), badRp,
      "5000", "@estemshan.test", "s64e-room",
    )) {
      assertTrue(leak !in payload, "the response leaks '$leak'")
    }
    console.log("S64_EMULATOR_BOARD season=$current rows=${delivered.size} skipped=${response["skipped"]}")
  }

  @Test
  fun anOmittedSeasonReachesTheEmulatorAsTheServersDefault() = runTest {
    if (!emulatorReady()) return@runTest
    // No seasonId key at all: the default has to survive the real query, not
    // just the fake port. What s1 contains depends on nothing this test writes,
    // so the assertions are about WHICH season was read, never how many rows.
    val response = leaderboardOnce(AdminLeaderboardDb(), request(uid = "s64e-watcher-$nonce", seasonId = null))

    assertEquals("s1", response["seasonId"])
    // Safe to key on the nonce here (unlike the board test above): the served
    // season is "s1", so the only way this run's nonce can reach the payload is
    // through one of its own documents — and a default read that returned them
    // would be a broken filter rather than a thrown error.
    val payload = rendered(response)
    assertTrue(nonce !in payload, "the default-season read returned this run's documents")
  }
}
