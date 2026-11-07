package com.estemshan.stage0

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM half of Stage 0's E5.
 *
 *  1. proves the capacity probe is sane — every measured match costs requests,
 *     plays rounds within the type's ceiling, and produces winners;
 *  2. proves the counts half of the battery document is deterministic, so the
 *     fingerprint the workerd leg carries must equal this one bytewise;
 *  3. writes the JVM battery (and the state-size bound) to `build/stage0-e5/`,
 *     which is what the workerd run is compared against.
 *
 * The comparison itself is NOT made from inside this test: workerd is a
 * separate process, and its answer has to come from a real Worker invocation.
 * Timings are never compared across runtimes — only against the plan's 10 ms
 * CPU/request budget — because wall-clock over synchronous compute is exactly
 * the quantity that legitimately differs between a laptop JVM and an isolate.
 */
class CapacityProbeTest {

  private val outDir = File("build/stage0-e5").apply { mkdirs() }

  @Test
  fun everyMatchCostsRequestsAndProducesWinners() {
    val battery = playCapacityBattery(1, 50)
    assertEquals(50, battery.matches.size, "every seed 1..50 must complete under FULL/NORMAL")
    assertTrue(battery.failures.isEmpty(), "failures: ${battery.failures}")

    for (m in battery.matches) {
      assertTrue(m.rounds in 1..18, "seed ${m.seed}: ${m.rounds} rounds outside FULL's ceiling")
      assertTrue(m.bidActions > 0, "seed ${m.seed}: no bidding actions — nothing measured")
      // Exactly 13 tricks × 4 seats of cards per played round — the one action
      // count the rules fix outright.
      assertEquals(m.rounds * 52, m.cardActions,
        "seed ${m.seed}: card actions must be 52 per round")
      // The bidding loop's own pathology guard is 400 decisions per round.
      assertTrue(m.bidActions <= m.rounds * 400,
        "seed ${m.seed}: ${m.bidActions} bidding actions exceeds the harness guard")
      assertTrue(m.applyMaxNs > 0, "seed ${m.seed}: apply clock never fired")
      assertTrue(m.winners.isNotEmpty(), "seed ${m.seed}: no winners")
    }
  }

  @Test
  fun countsAreDeterministicAcrossRuns() {
    val a = runCapacityJson(1, 10).substringBefore(",\"counts\"")
    val b = runCapacityJson(1, 10).substringBefore(",\"counts\"")
    assertEquals(a, b, "the counts half (with its fingerprint) must render identically twice")
  }

  @Test
  fun theBatteriesAreWrittenForTheWorkerdComparison() {
    // Warm-up matches (not recorded): the first measured run must not pay the
    // JVM's class-loading/JIT costs, the same courtesy E4's cold/warm split
    // records explicitly instead of hiding.
    repeat(3) { playCapacityMatch(1) }

    val primary = runCapacityJson(1, 50, GameType.FULL, ScoringMode.NORMAL)
    File(outDir, "capacity-jvm-FULL-NORMAL.json").writeText(primary)

    val secondary = listOf(
      "FULL-CLASSIC" to (GameType.FULL to ScoringMode.CLASSIC),
      "MINI-NORMAL" to (GameType.MINI to ScoringMode.NORMAL),
      "MINI-CLASSIC" to (GameType.MINI to ScoringMode.CLASSIC),
    )
    for ((label, combo) in secondary) {
      val doc = runCapacityJson(1, 20, combo.first, combo.second)
      File(outDir, "capacity-jvm-$label.json").writeText(doc)
    }

    // The state-size bound: the E2 match document for the same 50 seeds. This
    // is measured here, on the JVM leg, and modelled in the report — the probe
    // itself never renders documents inside its timed sections.
    val sizes = StringBuilder("""{"note":"runMatchJson document length per seed; upper bound on the persisted state JSON","seeds":[""")
    for (seed in 1..50) {
      if (seed > 1) sizes.append(',')
      sizes.append("""{"seed":$seed,"docChars":${runMatchJson(seed).length}}""")
    }
    sizes.append("]}")
    File(outDir, "state-size-jvm.json").writeText(sizes.toString())
  }
}
