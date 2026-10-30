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

  private fun extractFingerprint(json: String): String =
    json.substringAfter("\"fingerprint\":\"").substringBefore('"')
}
