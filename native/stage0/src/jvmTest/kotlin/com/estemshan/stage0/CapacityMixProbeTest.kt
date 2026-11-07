package com.estemshan.stage0

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM half of Stage 0's E6.
 *
 *  1. proves the per-seat attribution is sound — every seat plays exactly 13
 *     cards per round, every seat acts at least once per auction, and the seat
 *     loads add up to the match totals;
 *  2. proves the counts half of the battery document is deterministic;
 *  3. writes the JVM batteries to `build/stage0-e6/`, which is what the
 *     workerd run is compared against.
 *
 * The E5 probe's own document shape and fingerprint are untouched: this is a
 * separate file with a separate hash, so both experiments stay reproducible
 * at the branch head.
 */
class CapacityMixProbeTest {

  private val outDir = File("build/stage0-e6").apply { mkdirs() }

  @Test
  fun perSeatLoadsAreSound() {
    val battery = playCapacityMixBattery(1, 50)
    assertEquals(50, battery.matches.size, "every seed 1..50 must complete under FULL/NORMAL")
    assertTrue(battery.failures.isEmpty(), "failures: ${battery.failures}")

    for (m in battery.matches) {
      // Each seat plays exactly 13 cards per round — per-seat, by the rules.
      for ((seat, load) in m.seats) {
        assertEquals(m.rounds * 13, load.cardActions,
          "seed ${m.seed} $seat: card actions must be 13 per round")
        assertTrue(load.bidActions > 0, "seed ${m.seed} $seat: no auction actions")
        assertTrue(load.applyNs > 0, "seed ${m.seed} $seat: apply clock never fired")
      }
      val seatBid = m.seats.values.sumOf { it.bidActions }
      val seatCard = m.seats.values.sumOf { it.cardActions }
      assertEquals(m.bidActions, seatBid, "seed ${m.seed}: seat bid loads must add up")
      assertEquals(m.cardActions, seatCard, "seed ${m.seed}: seat card loads must add up")
      assertEquals(m.rounds * 13, m.trickCheckpoints,
        "seed ${m.seed}: one checkpoint per completed trick")
      assertTrue(m.winners.isNotEmpty(), "seed ${m.seed}: no winners")
    }
  }

  @Test
  fun countsAreDeterministicAcrossRuns() {
    val a = runCapacityMixJson(1, 10).substringBefore(",\"counts\"")
    val b = runCapacityMixJson(1, 10).substringBefore(",\"counts\"")
    assertEquals(a, b, "the counts half (with its fingerprint) must render identically twice")
  }

  @Test
  fun theBatteriesAreWrittenForTheWorkerdComparison() {
    repeat(3) { playCapacityMixMatch(1) }

    val primary = runCapacityMixJson(1, 50, GameType.FULL, ScoringMode.NORMAL)
    File(outDir, "capacity-mix-jvm-FULL-NORMAL.json").writeText(primary)

    val secondary = listOf(
      "FULL-CLASSIC" to (GameType.FULL to ScoringMode.CLASSIC),
      "MINI-NORMAL" to (GameType.MINI to ScoringMode.NORMAL),
      "MINI-CLASSIC" to (GameType.MINI to ScoringMode.CLASSIC),
    )
    for ((label, combo) in secondary) {
      val doc = runCapacityMixJson(1, 20, combo.first, combo.second)
      File(outDir, "capacity-mix-jvm-$label.json").writeText(doc)
    }
  }
}
