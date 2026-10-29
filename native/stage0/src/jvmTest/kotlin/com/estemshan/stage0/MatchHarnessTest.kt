package com.estemshan.stage0

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The JVM half of E1. It does two jobs:
 *
 *  1. proves the harness itself is sane — a full 18-round match, a rotating
 *     dealer, and a bit-for-bit reproducible document for a fixed seed;
 *  2. writes the JVM's canonical JSON to `build/stage0-parity/`, which is the
 *     file the workerd run is compared against. The comparison is deliberately
 *     NOT made from inside this test: workerd is a separate process and its
 *     output must come from a real Worker invocation, not from a mock.
 */
class MatchHarnessTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  /** Seeds the workerd run must match. 42 is the engine suite's own smoke seed;
   *  the rest are unrelated deals so the parity claim is not a one-deal
   *  coincidence — one of them is large enough to exercise the seeded shuffle
   *  well past its first few draws. */
  private val paritySeeds = listOf(42, 7, 1, 12345, 999)

  @Test
  fun theHarnessPlaysAFullEighteenRoundMatch() {
    val run = playMatch(seed = 42)

    assertEquals(18, run.rounds.size, "a match is 18 rounds")
    assertEquals(
      (1..18).toList(),
      run.rounds.map { it.round },
      "round numbers run 1..18 with no gaps",
    )
    assertEquals(
      (1..18).map { seats[(it - 1) % seats.size] },
      run.dealers,
      "the dealer rotates one seat per round",
    )
    assertEquals(seats.toSet(), run.matchScores.keys, "every seat has a final score")
    assertTrue(run.winners.isNotEmpty(), "a finished match has at least one winner")
    run.rounds.forEach { round ->
      assertEquals(
        13,
        round.tricksWon.values.sum(),
        "round ${round.round} must account for all 13 tricks",
      )
      assertTrue(round.totalBids != 13, "round ${round.round} totalled exactly 13 bids")
    }
  }

  @Test
  fun theSameSeedProducesTheSameDocumentAndDifferentSeedsDiverge() {
    assertEquals(
      runMatchJson(42),
      runMatchJson(42),
      "the same seed must produce a byte-identical document",
    )
    assertNotEquals(
      runMatchJson(42),
      runMatchJson(7),
      "two seeds must not play the same match",
    )
  }

  @Test
  fun theJvmFixturesAreWrittenForTheWorkerdComparison() {
    val outDir = File("build/stage0-parity")
    outDir.mkdirs()

    for (seed in paritySeeds) {
      val json = runMatchJson(seed)
      assertTrue(json.length > 1_000, "seed $seed produced a suspiciously short document")
      val file = File(outDir, "jvm-seed-$seed.json")
      file.writeText(json)
      assertTrue(file.isFile && file.length() > 0, "seed $seed fixture was not written")
    }
  }
}
