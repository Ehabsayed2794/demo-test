package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.functions.Settlement.ConvergedMatch
import com.estemshan.functions.Settlement.RecomputedMatch
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * S62: the Admin SDK half of the ledger, executed against a real Firestore
 * emulator. Every other jsTest class runs on fake ports, so these are the only
 * checks that EXECUTE what review cannot:
 * - [AdminSettlementDb.appendLedger]'s create-if-absent dual write commits.
 *   Calling it directly (the port does not swallow) also pins the ordering a
 *   Firestore transaction demands: the SDK rejects a read issued after a write
 *   with "Firestore transactions require all reads to be executed before all
 *   writes", and a fake can never see that;
 * - [SettlementLedger.appendTargets] really lands a CORRECTED row in BOTH
 *   collections and an APPLIED row in the ledger alone;
 * - a redelivered append adds no document and does not restamp the row;
 * - [AdminSettlementDb.readCorrectionWindow] is recency-ordered, needs no
 *   composite index, and keeps both corrections of a repeat while a same-shaped
 *   read over the whole ledger loses one to the agreed settles around it.
 *
 * SKIPS ITSELF (loudly) unless FIRESTORE_EMULATOR_HOST is set, so the ordinary
 * CI run of :functions:jsNodeTest neither fails nor quietly depends on an
 * emulator. To run it, the already-cached jar and no installation:
 *   java -jar ~/.cache/firebase/emulators/cloud-firestore-emulator-*.jar \
 *     --host 127.0.0.1 --port 8991 --rules firestore.rules --project_id s62-verify
 *   FIRESTORE_EMULATOR_HOST=127.0.0.1:8991 GCLOUD_PROJECT=s62-verify \
 *     ./native/gradlew.bat -p native :functions:jsNodeTest
 * GCLOUD_PROJECT is required because [ensureDefaultAdminApp] initializes the
 * bare default app, exactly as the Functions runtime does in production.
 *
 * Ids carry an "s62e-<nonce>" prefix: emulator suites share one database with
 * no clearing between runs, and a repeated id silently turns a create into an
 * update.
 */
class SettlementLedgerEmulatorTest {

  // Epoch millis as a String: a Double's toString would put a "." in the doc id.
  private val nonce: String = kotlin.js.Date.now().toLong().toString()

  private fun emulatorReady(): Boolean {
    val host = (js("process.env.FIRESTORE_EMULATOR_HOST") as? String)?.takeIf { it.isNotEmpty() }
    if (host == null) console.log("S62_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")
    return host != null
  }

  private fun match(matchId: String): ConvergedMatch = ConvergedMatch(
    matchId = matchId,
    mode = MatchMode.RANKED,
    status = "complete",
    order = listOf("p1", "p2", "p3", "p4"),
    players = listOf("u1", "u2", "u3", "u4"),
    dealerSeatFinal = "p1",
    gameType = GameType.FULL,
    classic = false,
    completedRound = 1,
    maxRounds = 1,
    claimedScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14),
    claimedWinners = listOf("p1"),
    archives = emptyList(),
  )

  private fun corrected(): RecomputedMatch = RecomputedMatch(
    finalScores = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14),
    winnerIds = listOf("p1"),
    rounds = 1,
    corrected = true,
    claimedScores = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14),
    claimedWinners = listOf("p1"),
  )

  private fun agreed(): RecomputedMatch {
    val both = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14)
    return RecomputedMatch(
      finalScores = both,
      winnerIds = listOf("p1"),
      rounds = 1,
      corrected = false,
      claimedScores = both,
      claimedWinners = listOf("p1"),
    )
  }

  private fun id(tag: String): String = "s62e-$tag-$nonce"

  private suspend fun append(db: AdminSettlementDb, matchId: String, recomputed: RecomputedMatch) {
    db.appendLedger(SettlementLedger.settlementRecord(match(matchId), recomputed, "u1"))
  }

  private suspend fun fieldsOf(collection: String, docId: String): Map<String, Any?>? {
    val snap = getFirestore().collection(collection).doc(docId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  private suspend fun countOf(collection: String): Int =
    getFirestore().collection(collection).get().await().docs.size

  @Test
  fun theAdminClientReachesTheEmulatorAndAnAgreedRowStaysOutOfTheWindow() = runTest {
    if (!emulatorReady()) return@runTest
    // This test is deliberately FIRST and deliberately minimal: every Node
    // process pays firebase-admin's cold start (module load, app init, the
    // first gRPC dial) once, and mocha caps a test at its 2000 ms default. The
    // rest of the class then runs warm (each later test measured ~0.5 s for a
    // dozen round trips), so the cold cost belongs to the smallest test.
    val db = AdminSettlementDb()
    val agreedId = id("one")
    append(db, agreedId, agreed())
    assertNotNull(
      fieldsOf(SettlementLedger.COLLECTION, agreedId),
      "the Admin append must reach the emulator",
    )
    assertEquals(
      null,
      fieldsOf(SettlementLedger.COLLECTION_CORRECTIONS, agreedId),
      "an APPLIED row must not pollute the corrections sample",
    )
  }

  @Test
  fun aCorrectedAppendWritesBothCollectionsAndResolvesTheServerTimestamp() = runTest {
    if (!emulatorReady()) return@runTest
    val db = AdminSettlementDb()

    val correctedId = id("both")
    // Direct call: an SDK rejection (wrong read/write order) throws here
    // instead of being logged, so this line is the ordering pin.
    append(db, correctedId, corrected())
    val ledgerRow = fieldsOf(SettlementLedger.COLLECTION, correctedId)
    val windowRow = fieldsOf(SettlementLedger.COLLECTION_CORRECTIONS, correctedId)
    assertNotNull(ledgerRow, "the ledger row must exist in the emulator")
    assertNotNull(windowRow, "a CORRECTED row must also materialize in the window")
    assertEquals("CORRECTED", ledgerRow["outcomeKind"])
    assertEquals("CORRECTED", windowRow["outcomeKind"])
    assertNotNull(
      timestampToMillis(ledgerRow["appendedAt"]),
      "the server timestamp must resolve, or orderBy('appendedAt') has nothing to order on",
    )
  }

  @Test
  fun aRedeliveredAppendAddsNoDocumentAndDoesNotRestamp() = runTest {
    if (!emulatorReady()) return@runTest
    val db = AdminSettlementDb()

    val matchId = id("replay")
    append(db, matchId, corrected())
    val appendedAt = fieldsOf(SettlementLedger.COLLECTION, matchId)?.get("appendedAt")
    val ledgerBefore = countOf(SettlementLedger.COLLECTION)
    val windowBefore = countOf(SettlementLedger.COLLECTION_CORRECTIONS)

    // The same event delivered again (a retried delivery, a re-run hook).
    append(db, matchId, corrected())
    assertEquals(ledgerBefore, countOf(SettlementLedger.COLLECTION), "a replay must create no ledger row")
    assertEquals(windowBefore, countOf(SettlementLedger.COLLECTION_CORRECTIONS), "a replay must create no window row")
    assertEquals(
      timestampToMillis(appendedAt),
      timestampToMillis(fieldsOf(SettlementLedger.COLLECTION, matchId)?.get("appendedAt")),
      "a replay must not overwrite the row it found",
    )

    // A genuinely different event still writes both collections.
    append(db, id("replay2"), corrected())
    assertEquals(ledgerBefore + 1, countOf(SettlementLedger.COLLECTION))
    assertEquals(windowBefore + 1, countOf(SettlementLedger.COLLECTION_CORRECTIONS))
  }

  @Test
  fun theCorrectionsWindowKeepsTheRepeatTheLedgerWindowLoses() = runTest {
    if (!emulatorReady()) return@runTest
    val db = AdminSettlementDb()

    val first = id("w-first")
    append(db, first, corrected())
    // Clean settles that happened AFTER the first correction. DETECT_WINDOW at
    // scale would cost 50 appends per run; four rows and a three-row probe
    // demonstrate the same crowd-out, because the mechanism is the limit, not
    // the number 50.
    val filler = (0 until 4).map { id("fill$it") }
    for (matchId in filler) append(db, matchId, agreed())
    val last = id("w-last")
    append(db, last, corrected())

    val window = db.readCorrectionWindow().mapNotNull { SettlementLedger.parseEntry(it) }
    val windowIds = window.map { it.matchId }
    assertTrue(windowIds.containsAll(listOf(first, last)), "both corrections must be in the window: $windowIds")
    assertEquals(last, windowIds.first(), "the window must be newest-first")
    assertTrue(
      windowIds.intersect(filler.toSet()).isEmpty(),
      "no agreed settle may enter the corrections window",
    )

    // The counterfactual, executed: the same bounded recency read over the
    // WHOLE ledger sees only the newest correction, so the repeat is invisible
    // - which is exactly why the window is its own collection.
    val ledgerProbe = getFirestore()
      .collection(SettlementLedger.COLLECTION)
      .orderBy("appendedAt", "desc")
      .limit(3)
      .get()
      .await()
    val correctionsSeen = ledgerProbe.docs.count { doc ->
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(doc.data()) as? Map<String, Any?>
      fields?.get("outcomeKind") == SettlementLedger.OUTCOME_CORRECTED
    }
    assertTrue(
      correctionsSeen < SettlementLedger.REPEAT_THRESHOLD,
      "a 3-row whole-ledger window saw $correctionsSeen corrections; it must not reach the threshold",
    )
  }
}
