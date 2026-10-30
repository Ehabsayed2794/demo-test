package com.estemshan.stage0

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM half of Stage 0's E7.
 *
 *  1. proves the settlement stream is a legal sequence of dependent writes —
 *     every row's RP continues the previous row for that uid, every key
 *     (matchId, seat) is unique, and the ladder index matches the RP;
 *  2. proves it is deterministic, so the workerd leg must carry the same
 *     fingerprint bytewise;
 *  3. writes the JVM document to `build/stage0-e7/`, which the E7 driver
 *     loads and feeds to the RP store.
 */
class SettlementStreamTest {

  private val outDir = File("build/stage0-e7").apply { mkdirs() }

  @Test
  fun theStreamIsDeterministic() {
    assertEquals(
      runSettlementStreamJson(1, 10),
      runSettlementStreamJson(1, 10),
      "the stream (and its fingerprint) must render identically twice",
    )
  }

  @Test
  fun everyRowContinuesItsSeatAndIsKeyedOnce() {
    val stream = buildSettlementStream(1, 60)
    assertEquals(240, stream.rows.size, "60 matches × 4 seats")

    val keys = stream.rows.map { it.matchId to it.seat }
    assertEquals(keys.size, keys.toSet().size, "duplicate (matchId, seat) — the idempotency key is not unique")

    for (row in stream.rows) {
      assertEquals(row.previousRp + row.delta, row.rp, "${row.matchId}/${row.seat}: delta does not apply cleanly")
      assertEquals(
        rankLadder.indexOf(rankAt(row.rp)),
        row.ladderIndex,
        "${row.matchId}/${row.seat}: ladder index disagrees with RP",
      )
      assertTrue(row.placement in 1..4, "${row.matchId}/${row.seat}: placement ${row.placement}")
      assertTrue(row.rp >= 0, "${row.matchId}/${row.seat}: RP went negative")
    }

    val lastRp = HashMap<String, Int>()
    for (match in stream.matches) {
      for (row in match.rows) {
        assertEquals(
          lastRp[row.uid] ?: E7_STARTING_RP.getValue(row.uid),
          row.previousRp,
          "${row.matchId}/${row.seat}: previousRp does not continue ${row.uid}'s chain",
        )
        lastRp[row.uid] = row.rp
      }
    }
  }

  @Test
  fun everyMatchHasAPlacementAndAZero() {
    val stream = buildSettlementStream(1, 60)
    for (match in stream.matches) {
      assertEquals(4, match.rows.size)
      val outcomes = match.rows.map { it.outcome }
      assertTrue(outcomes.contains("KING"), "${match.matchId}: no King")
      assertTrue(
        outcomes.contains("KOZ") || outcomes.all { it == "KING" },
        "${match.matchId}: no Koz and not an all-King tie — Q2d/Q2f violated",
      )
    }
  }

  @Test
  fun theBatteryIsNotDominatedByNoAwardRows() {
    // RD29 re-checks the band per match. The starting table is Gold↔Platinum,
    // so a two-tier drift inside one 60-match battery would be a finding about
    // the fixture, not about the rules — keep it visible instead of silent.
    val stream = buildSettlementStream(1, 60)
    assertTrue(
      stream.noAwardRows < stream.rows.size / 10,
      "${stream.noAwardRows}/${stream.rows.size} rows awarded nothing — the starting band is too wide",
    )
  }

  @Test
  fun theDocumentsAreWrittenForTheDriver() {
    val primary = runSettlementStreamJson(1, 60, GameType.FULL, ScoringMode.NORMAL)
    outDir.resolve("settlement-jvm-FULL-NORMAL.json").writeText(primary)

    val mini = runSettlementStreamJson(1, 20, GameType.MINI, ScoringMode.NORMAL)
    outDir.resolve("settlement-jvm-MINI-NORMAL.json").writeText(mini)

    // The fingerprint is the comparison the workerd leg must match; printing it
    // makes the expected value visible in the test log as well as the file.
    println("E7 settlement FULL/NORMAL fingerprint: ${extractFingerprint(primary)}")
    println("E7 settlement MINI/NORMAL fingerprint: ${extractFingerprint(mini)}")
  }

  @Test
  fun recomputingTheTailFromTheRestoredStateReproducesTheOriginalRows() {
    // The recompute path E7's stale-backup scenario depends on: after a
    // restore, the missing settlements are recomputed from the state the
    // restored ledger actually holds. Recomputed from the right base, the rows
    // must be identical to the original — otherwise a restore would silently
    // change what a player earned.
    val full = buildSettlementStream(1, 60)
    val restoredBase = full.matches[54].rows.associate { it.uid to it.rp } // state after match 55
    val originalTail = full.matches.drop(55).flatMap { it.rows }

    val recomputed = buildSettlementStream(56, 5, GameType.FULL, ScoringMode.NORMAL, restoredBase)
    val recomputedRows = recomputed.matches.flatMap { it.rows }

    assertEquals(originalTail.size, recomputedRows.size)
    for ((expected, actual) in originalTail.zip(recomputedRows)) {
      assertEquals(expected.matchId, actual.matchId)
      assertEquals(expected.seat, actual.seat)
      assertEquals(expected.uid, actual.uid)
      assertEquals(expected.placement, actual.placement, "${expected.matchId}/${expected.seat}: placement")
      assertEquals(expected.outcome, actual.outcome, "${expected.matchId}/${expected.seat}: outcome")
      assertEquals(expected.delta, actual.delta, "${expected.matchId}/${expected.seat}: delta")
      assertEquals(expected.previousRp, actual.previousRp, "${expected.matchId}/${expected.seat}: previousRp")
      assertEquals(expected.rp, actual.rp, "${expected.matchId}/${expected.seat}: rp")
      assertEquals(expected.ladderIndex, actual.ladderIndex, "${expected.matchId}/${expected.seat}: ladderIndex")
      assertEquals(expected.movement, actual.movement, "${expected.matchId}/${expected.seat}: movement")
    }
  }

  @Test
  fun recomputingFromTheWrongBaseDiffers() {
    // The control for the test above: recomputing the same tail from the
    // batch's ORIGINAL base (instead of the restored state) must NOT reproduce
    // the rows — otherwise the equality above would prove nothing.
    val full = buildSettlementStream(1, 60)
    val restoredBase = full.matches[54].rows.associate { it.uid to it.rp }
    val originalTail = full.matches.drop(55).flatMap { it.rows }

    val wrongBase = buildSettlementStream(56, 5, GameType.FULL, ScoringMode.NORMAL, E7_STARTING_RP)
    val wrongRows = wrongBase.matches.flatMap { it.rows }

    assertEquals(restoredBase.keys, E7_STARTING_RP.keys)
    val differences = originalTail.zip(wrongRows).count { (a, b) -> a.rp != b.rp || a.delta != b.delta }
    assertTrue(
      differences > 0,
      "recomputing from the wrong base produced identical rows — the comparison cannot detect a wrong base",
    )
  }

  @Test
  fun theStartingRpMapIsParsedStrictly() {
    assertEquals(mapOf("u1" to 900, "u2" to 1020), parseRpMap("""{"u1":900,"u2":1020}"""))
    assertEquals(mapOf("u1" to 900), parseRpMap("""  { "u1" : 900 }  """))
    assertEquals(emptyMap(), parseRpMap("{}"))
    // A malformed map is a caller bug, not something to coerce.
    assertTrue(runCatching { parseRpMap("""["u1",900]""") }.isFailure)
    assertTrue(runCatching { parseRpMap("""{"u1":}""") }.isFailure)
  }

  private fun extractFingerprint(json: String): String =
    json.substringAfter("\"fingerprint\":\"").substringBefore('"')
}
