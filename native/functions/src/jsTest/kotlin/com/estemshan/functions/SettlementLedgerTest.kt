package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.functions.Settlement.ConvergedMatch
import com.estemshan.functions.Settlement.RecomputedMatch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S62 RP audit ledger: pure unit pinning - record shape (exact key set),
 * deterministic event ids, which collections a record materializes into, the
 * R25 repeat detector (true positive on a repeat signature, no false
 * positive on a single correction, a repeat that survives a flood of clean
 * settles between the two corrections), fail-closed parsing and the
 * ASCII/greppable console-line shapes. The hook's own wiring (append ->
 * bounded corrections window -> detector) is pinned against a fake that
 * answers the way AdminSettlementDb does. Orchestration exactly-once /
 * append-failure behavior is pinned in SettlementTest with fake ports.
 */
class SettlementLedgerTest {

  // ---- fixtures -----------------------------------------------------------

  private fun match(): ConvergedMatch = ConvergedMatch(
    matchId = "m1",
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

  private fun recomputed(
    finalScores: Map<String, Int> = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14),
    winnerIds: List<String> = listOf("p1"),
    claimedScores: Map<String, Int> = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14),
    claimedWinners: List<String> = listOf("p1"),
    corrected: Boolean = true,
  ): RecomputedMatch = RecomputedMatch(
    finalScores = finalScores,
    winnerIds = winnerIds,
    rounds = 1,
    corrected = corrected,
    claimedScores = claimedScores,
    claimedWinners = claimedWinners,
  )

  private fun entry(
    matchId: String,
    players: List<String>,
    settledBy: String,
    outcomeKind: String = SettlementLedger.OUTCOME_CORRECTED,
  ): SettlementLedger.LedgerEntry =
    SettlementLedger.LedgerEntry(matchId, players, settledBy, outcomeKind)

  // ---- record shape -------------------------------------------------------

  @Test
  fun settlementRecordPinsTheSchemaShape() {
    val record = SettlementLedger.settlementRecord(match(), recomputed(), "u1")
    assertEquals(
      setOf(
        "schemaVersion", "matchId", "mode", "gameType", "players",
        "outcomeKind", "corrected", "settledBy", "rounds",
        "claimedScores", "claimedWinners", "finalScores", "winnerIds",
        "changedSeats", "winnerChanged", "appendedAt",
      ),
      record.keys,
    )
    assertEquals(1, record["schemaVersion"])
    assertEquals("m1", record["matchId"])
    assertEquals("RANKED", record["mode"])
    assertEquals("FULL", record["gameType"])
    assertEquals(listOf("u1", "u2", "u3", "u4"), record["players"])
    assertEquals("CORRECTED", record["outcomeKind"])
    assertEquals(true, record["corrected"])
    assertEquals("u1", record["settledBy"])
    assertEquals(1, record["rounds"])
    assertEquals(mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14), record["claimedScores"])
    assertEquals(mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14), record["finalScores"])
    assertEquals(listOf("p2"), record["changedSeats"])
    assertEquals(false, record["winnerChanged"])
    assertTrue(record["appendedAt"] != null, "server timestamp sentinel must be present")
  }

  @Test
  fun appliedRecordHasAnEmptyCorrectionDelta() {
    val agreed = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)
    val record = SettlementLedger.settlementRecord(
      match(),
      recomputed(finalScores = agreed, corrected = false),
      "u1",
    )
    assertEquals("APPLIED", record["outcomeKind"])
    assertEquals(false, record["corrected"])
    assertEquals(emptyList<String>(), record["changedSeats"])
    assertEquals(false, record["winnerChanged"])
  }

  @Test
  fun correctedRecordCarriesTheClaimedVersusFinalDiff() {
    // The canonical correction - p2's forged -1 recomputed to -11.
    val record = SettlementLedger.settlementRecord(match(), recomputed(), "u1")
    assertEquals(listOf("p2"), record["changedSeats"])

    // A winner flip registers too.
    val flipped = SettlementLedger.settlementRecord(
      match(),
      recomputed(winnerIds = listOf("p2"), claimedWinners = listOf("p1")),
      "u2",
    )
    assertEquals(true, flipped["winnerChanged"])
  }

  @Test
  fun failureRecordPinsTheSchemaShape() {
    val record = SettlementLedger.failureRecord(
      match(),
      "u1",
      Settlement.Reason.HISTORY_INCOMPLETE,
      "settleMatch: roundArchive/2 is absent.",
    )
    assertEquals(
      setOf(
        "schemaVersion", "matchId", "mode", "gameType", "players",
        "outcomeKind", "settledBy", "failureReason", "failureMessage",
        "appendedAt",
      ),
      record.keys,
    )
    assertEquals("FAILED", record["outcomeKind"])
    assertEquals("HISTORY_INCOMPLETE", record["failureReason"])
    assertEquals("settleMatch: roundArchive/2 is absent.", record["failureMessage"])
    assertEquals("u1", record["settledBy"])
  }

  @Test
  fun ledgerDocIdIsEventScopedAndDeterministic() {
    val settlement = SettlementLedger.settlementRecord(match(), recomputed(), "u1")
    assertEquals("m1", SettlementLedger.ledgerDocId(settlement))

    val failure = SettlementLedger.failureRecord(
      match(),
      "u1",
      Settlement.Reason.HISTORY_INCOMPLETE,
      "x",
    )
    assertEquals("m1-fail-HISTORY_INCOMPLETE", SettlementLedger.ledgerDocId(failure))
    assertEquals(SettlementLedger.ledgerDocId(failure), SettlementLedger.ledgerDocId(failure))

    assertNull(SettlementLedger.ledgerDocId(mapOf("outcomeKind" to "FAILED")))
  }

  @Test
  fun onlyCorrectionsMaterializeIntoTheDetectorWindow() {
    val corrected = SettlementLedger.settlementRecord(match(), recomputed(), "u1")
    assertEquals(
      listOf(SettlementLedger.COLLECTION, SettlementLedger.COLLECTION_CORRECTIONS),
      SettlementLedger.appendTargets(corrected),
      "a CORRECTED row must be readable by the detector, so it goes in the window too",
    )

    val agreed = mapOf("p1" to 25, "p2" to -1, "p3" to 12, "p4" to 14)
    val applied = SettlementLedger.settlementRecord(
      match(),
      recomputed(finalScores = agreed, claimedScores = agreed, corrected = false),
      "u1",
    )
    val failure = SettlementLedger.failureRecord(
      match(),
      "u1",
      Settlement.Reason.HISTORY_INCOMPLETE,
      "x",
    )
    // Neither pollutes the corrections sample: the window stays 50 corrections
    // no matter how much settlement traffic sits around them.
    assertEquals(listOf(SettlementLedger.COLLECTION), SettlementLedger.appendTargets(applied))
    assertEquals(listOf(SettlementLedger.COLLECTION), SettlementLedger.appendTargets(failure))
  }

  // ---- the R25 repeat detector --------------------------------------------

  @Test
  fun detectRepeatsFlagsTheSamePlayerAcrossCorrections() {
    val signatures = SettlementLedger.detectRepeats(
      listOf(
        entry("m1", listOf("u1", "u2", "u3", "u4"), "u1"),
        entry("m2", listOf("u2", "u5", "u6", "u7"), "u5"),
      ),
    )
    assertEquals(
      listOf(SettlementLedger.RepeatSignature(SettlementLedger.KIND_PLAYER, listOf("u2"), 2)),
      signatures,
    )
  }

  @Test
  fun detectRepeatsFlagsTheSameTable() {
    val signatures = SettlementLedger.detectRepeats(
      listOf(
        entry("m1", listOf("u4", "u1", "u2", "u3"), "u1"),
        entry("m2", listOf("u3", "u4", "u1", "u2"), "u2"),
      ),
    )
    assertTrue(
      signatures.contains(
        SettlementLedger.RepeatSignature(
          SettlementLedger.KIND_TABLE,
          listOf("u1", "u2", "u3", "u4"),
          2,
        ),
      ),
      "expected a TABLE signature but was $signatures",
    )
    // Every recurring roster member is involved twice as well.
    assertTrue(
      signatures.contains(SettlementLedger.RepeatSignature(SettlementLedger.KIND_PLAYER, listOf("u1"), 2)),
      "expected a PLAYER signature but was $signatures",
    )
  }

  @Test
  fun singleCorrectionIsNotARepeat() {
    assertEquals(
      emptyList(),
      SettlementLedger.detectRepeats(listOf(entry("m1", listOf("u1", "u2", "u3", "u4"), "u1"))),
    )
    assertEquals(emptyList(), SettlementLedger.detectRepeats(emptyList()))
  }

  @Test
  fun appliedAndFailedEntriesDoNotCount() {
    val entries = listOf(
      entry("m1", listOf("u1", "u2", "u3", "u4"), "u1", SettlementLedger.OUTCOME_CORRECTED),
      entry("m2", listOf("u1", "u2", "u3", "u4"), "u1", SettlementLedger.OUTCOME_APPLIED),
      entry("m3", listOf("u1", "u2", "u3", "u4"), "u1", SettlementLedger.OUTCOME_FAILED),
    )
    assertEquals(emptyList(), SettlementLedger.detectRepeats(entries))
  }

  @Test
  fun theSameMatchNeverDoubleCounts() {
    val entries = listOf(
      entry("m1", listOf("u1", "u2", "u3", "u4"), "u1"),
      entry("m1", listOf("u1", "u2", "u3", "u4"), "u1"),
    )
    assertEquals(emptyList(), SettlementLedger.detectRepeats(entries))
  }

  @Test
  fun parseEntryFailsClosedAndRoundTrips() {
    assertNull(SettlementLedger.parseEntry(null))
    assertNull(SettlementLedger.parseEntry(mapOf("matchId" to "m1")))
    assertNull(
      SettlementLedger.parseEntry(
        mapOf(
          "matchId" to "m1",
          "outcomeKind" to "CORRECTED",
          "settledBy" to "u1",
          "players" to listOf("u1", 2),
        ),
      ),
    )
    val parsed = SettlementLedger.parseEntry(
      mapOf(
        "matchId" to "m1",
        "outcomeKind" to "CORRECTED",
        "settledBy" to "u1",
        "players" to listOf("u1", "u2"),
      ),
    )
    assertEquals(
      SettlementLedger.LedgerEntry("m1", listOf("u1", "u2"), "u1", "CORRECTED"),
      parsed,
    )
  }

  // ---- the wired hook (append -> window -> detector) -----------------------

  /**
   * Ledger-facing fake that answers the way AdminSettlementDb does: appends
   * are captured, and the corrections window is the newest-first list of the
   * CORRECTED rows among them - [SettlementLedger.appendTargets] applied in
   * memory. Lets the hook's wiring be asserted rather than argued.
   */
  private class FakeLedgerDb : SettlementDb {
    val appends = mutableListOf<Map<String, Any?>>()
    var windowReads = 0
    var windowThrows = false

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = null

    override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> = emptyMap()

    override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T =
      throw IllegalStateException("the ledger hook never transacts")

    override suspend fun appendLedger(record: Map<String, Any?>) {
      appends.add(record)
    }

    override suspend fun readCorrectionWindow(): List<Map<String, Any?>> {
      windowReads++
      if (windowThrows) throw IllegalStateException("ledger window failed (test)")
      return appends.filter { it["outcomeKind"] == SettlementLedger.OUTCOME_CORRECTED }.reversed()
    }
  }

  @Test
  fun oneCorrectionThroughTheHookIsNotARepeat() = runTest {
    val db = FakeLedgerDb()
    val signatures = SettlementLedger.recordSettlement(db, match(), recomputed(), "u1")
    assertEquals(1, db.appends.size)
    assertEquals("CORRECTED", db.appends[0]["outcomeKind"])
    // The window contained only this match: a single correction is not the
    // R25 signature, so nothing is reported.
    assertEquals(1, db.windowReads)
    assertEquals(emptyList(), signatures)
  }

  @Test
  fun aSecondCorrectionAtTheSameTableIsReportedByTheHook() = runTest {
    val db = FakeLedgerDb()
    SettlementLedger.recordSettlement(db, match(), recomputed(), "u1")
    val signatures = SettlementLedger.recordSettlement(
      db,
      match().copy(matchId = "m2"),
      recomputed(),
      "u1",
    )
    assertEquals(2, db.appends.size)
    assertEquals(
      listOf(
        SettlementLedger.RepeatSignature(
          SettlementLedger.KIND_TABLE,
          listOf("u1", "u2", "u3", "u4"),
          2,
        ),
      ),
      signatures.filter { it.kind == SettlementLedger.KIND_TABLE },
    )
    assertEquals(4, signatures.count { it.kind == SettlementLedger.KIND_PLAYER })
  }

  @Test
  fun aRepeatSurvivesAFloodOfCleanSettlesBetweenTheCorrections() = runTest {
    val db = FakeLedgerDb()
    val agreed = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14)
    SettlementLedger.recordSettlement(db, match(), recomputed(), "u1")

    // 60 agreed ranked settles - more than DETECT_WINDOW - at other tables.
    repeat(60) { index ->
      SettlementLedger.recordSettlement(
        db,
        match().copy(matchId = "a$index"),
        recomputed(finalScores = agreed, claimedScores = agreed, corrected = false),
        "u$index",
      )
    }

    val signatures = SettlementLedger.recordSettlement(
      db,
      match().copy(matchId = "m2"),
      recomputed(),
      "u1",
    )
    // A window over the WHOLE ledger would hold only these 50 newest rows, all
    // agreed settles: the first correction would have aged out and R25 would
    // read "no repeat". The corrections window still sees both.
    assertEquals(
      listOf(
        SettlementLedger.RepeatSignature(
          SettlementLedger.KIND_TABLE,
          listOf("u1", "u2", "u3", "u4"),
          2,
        ),
      ),
      signatures.filter { it.kind == SettlementLedger.KIND_TABLE },
    )
    assertEquals(62, db.appends.size)
    // One bounded read per correction only; the 60 agreed settles cost none.
    assertEquals(2, db.windowReads)
  }

  @Test
  fun anAgreedSettleAppendsButNeverReadsTheWindow() = runTest {
    val db = FakeLedgerDb()
    val agreed = mapOf("p1" to 25, "p2" to -11, "p3" to 12, "p4" to 14)
    val signatures = SettlementLedger.recordSettlement(
      db,
      match(),
      recomputed(finalScores = agreed, claimedScores = agreed, corrected = false),
      "u1",
    )
    assertEquals(1, db.appends.size)
    assertEquals("APPLIED", db.appends[0]["outcomeKind"])
    // The detector runs only on corrections: an agreed settle costs no read.
    assertEquals(0, db.windowReads)
    assertEquals(emptyList(), signatures)
  }

  @Test
  fun aWindowReadFailureIsLoggedAndTheSettlementStands() = runTest {
    val db = FakeLedgerDb()
    db.windowThrows = true
    assertEquals(emptyList(), SettlementLedger.recordSettlement(db, match(), recomputed(), "u1"))
    assertEquals(1, db.appends.size)
  }

  // ---- console lines ------------------------------------------------------

  @Test
  fun consoleLinesAreAsciiAndGreppable() {
    val corrected = SettlementLedger.settleLine(
      SettlementLedger.settlementRecord(match(), recomputed(), "u1"),
    )
    assertTrue(
      corrected.startsWith("S62_SETTLE outcome=CORRECTED matchId=m1 players=u1,u2,u3,u4"),
      "was $corrected",
    )

    val failed = SettlementLedger.settleLine(
      SettlementLedger.failureRecord(
        match(),
        "u1",
        Settlement.Reason.HISTORY_INCOMPLETE,
        "x",
      ),
    )
    assertTrue(
      failed.startsWith(
        "S62_SETTLE outcome=FAILED matchId=m1 players=u1,u2,u3,u4 reason=HISTORY_INCOMPLETE",
      ),
      "was $failed",
    )

    val repeat = SettlementLedger.repeatLine(
      SettlementLedger.RepeatSignature(SettlementLedger.KIND_TABLE, listOf("u1", "u2"), 2),
    )
    assertEquals("S62_REPEAT kind=TABLE key=u1+u2 occurrences=2", repeat)

    assertEquals("S62_SETTLE outcome=NOOP matchId=m1 mode=ROOM", SettlementLedger.noOpLine("m1", "ROOM"))
    assertEquals(
      "S62_SETTLE outcome=DENIED matchId=m1 uid=u9 reason=NOT_MEMBER",
      SettlementLedger.deniedLine("m1", "u9", "NOT_MEMBER"),
    )

    for (line in listOf(corrected, failed, repeat)) {
      assertTrue(line.all { it.code < 128 }, "non-ASCII in $line")
    }
  }
}
