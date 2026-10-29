package com.estemshan.stage0

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM half of the E6 chain probe (review item 6).
 *
 * Chain lengths are pure functions of the seed stream and are fingerprinted;
 * chain CPU is local wall-clock and is compared only against the 10 ms budget,
 * never across runtimes. A separate file so the E5/E6 fingerprints stay
 * reproducible untouched.
 */
class CapacityChainProbeTest {

  private val outDir = File("build/stage0-e6").apply { mkdirs() }

  @Test
  fun chainsAreBoundedByTableSize() {
    val battery = playCapacityChainBattery(1, 50)
    assertEquals(50, battery.matches.size, "every seed 1..50 must complete")
    assertTrue(battery.failures.isEmpty(), "failures: ${battery.failures}")

    for (m in battery.matches) {
      // Measured (seeds 1..50): 1-human chains reach 13 actions — auction raise
      // wars and trick/round boundaries chain bot seats far beyond one trick —
      // so the bound here only catches double-counting, not the measured shape.
      assertTrue(m.longestOneHumanChainLen <= 16,
        "seed ${m.seed}: 1-human chain ${m.longestOneHumanChainLen} is above any plausible legal run")
      assertTrue(m.longestTwoHumanChainLen <= 10,
        "seed ${m.seed}: 2-human chain ${m.longestTwoHumanChainLen} is above any plausible legal run")
      assertTrue(m.longestOneHumanChainLen < m.actions,
        "seed ${m.seed}: 1-human chain cannot be the whole match")
    }
  }

  @Test
  fun countsAreDeterministicAcrossRuns() {
    val a = runCapacityChainJson(1, 10).substringBefore(",\"counts\"")
    val b = runCapacityChainJson(1, 10).substringBefore(",\"counts\"")
    assertEquals(a, b, "the counts half (with its fingerprint) must render identically twice")
  }

  @Test
  fun theChainBatteriesAreWrittenForTheWorkerdComparison() {
    repeat(2) { playCapacityChainMatch(1) }

    val primary = runCapacityChainJson(1, 50, GameType.FULL, ScoringMode.NORMAL)
    File(outDir, "capacity-chain-jvm-FULL-NORMAL.json").writeText(primary)
    val mini = runCapacityChainJson(1, 20, GameType.MINI, ScoringMode.NORMAL)
    File(outDir, "capacity-chain-jvm-MINI-NORMAL.json").writeText(mini)
  }
}
