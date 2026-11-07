package com.estemshan.stage0

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The JVM half of Stage 0's E1/E2. It does two jobs:
 *
 *  1. proves the harness itself is sane — a full match per game type, a rotating
 *     dealer, every round accounting for 13 tricks, and a bit-for-bit
 *     reproducible document for a fixed seed;
 *  2. writes the JVM's canonical JSON to `build/stage0-parity/`, which is what
 *     the workerd run is compared against. The comparison is deliberately NOT
 *     made from inside this test: workerd is a separate process and its output
 *     must come from a real Worker invocation, not from a mock.
 *
 * These tests are not wired into CI (no workflow runs `:stage0:*`) — the corpus
 * is an experiment's evidence, reproducible on demand, not a gate on every push.
 */
class MatchHarnessTest {

  private val seats = listOf("p1", "p2", "p3", "p4")

  /** The E1 seeds, kept as full single-match fixtures. 42 is the engine suite's
   *  own smoke seed; the rest are unrelated deals so the claim is not a
   *  one-deal coincidence. */
  private val fixtureSeeds = listOf(42, 7, 1, 12345, 999)

  /** E2's corpus: the plan asks for ≥200 seeds across FULL/MINI × Normal/Classic. */
  private val corpusSeedCount = 200

  private val combinations = listOf(
    GameType.FULL to ScoringMode.NORMAL,
    GameType.FULL to ScoringMode.CLASSIC,
    GameType.MINI to ScoringMode.NORMAL,
    GameType.MINI to ScoringMode.CLASSIC,
  )

  @Test
  fun theHarnessPlaysAFullEighteenRoundMatch() {
    val run = playMatch(seed = 42)

    assertEquals(18, run.rounds.size, "a FULL match is 18 rounds")
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
  fun aMiniMatchIsTenRoundsAndRotatesTheDealerTheSameWay() {
    val run = playMatch(seed = 42, gameType = GameType.MINI)

    assertEquals(10, run.rounds.size, "a MINI match is 10 rounds")
    assertEquals(
      (1..10).map { seats[(it - 1) % seats.size] },
      run.dealers,
      "the dealer rotation is game-type independent",
    )
    run.rounds.forEach { round ->
      assertEquals(13, round.tricksWon.values.sum(), "round ${round.round} lost a trick")
    }
    // MINI's Quick rounds start at 6, not 14 — the boundary the fixed-trump
    // ladder's floorMod was fixed for. Rounds 6..10 are five Quick rounds.
    assertEquals(
      GameType.MINI.baseRounds - GameType.MINI.firstFastRound + 1,
      run.rounds.count { it.round >= GameType.MINI.firstFastRound },
    )
  }

  @Test
  fun everyCombinationIsReproducibleAndTheSeedStillMatters() {
    for ((gameType, scoringMode) in combinations) {
      assertEquals(
        runMatchJson(42, gameType, scoringMode),
        runMatchJson(42, gameType, scoringMode),
        "$gameType/$scoringMode must be reproducible for one seed",
      )
      assertNotEquals(
        runMatchJson(42, gameType, scoringMode),
        runMatchJson(7, gameType, scoringMode),
        "$gameType/$scoringMode must not play the same match for two seeds",
      )
    }
  }

  @Test
  fun theJvmFixturesAreWrittenForTheWorkerdComparison() {
    val outDir = parityDir()

    for (seed in fixtureSeeds) {
      val json = runMatchJson(seed)
      assertTrue(json.length > 1_000, "seed $seed produced a suspiciously short document")
      File(outDir, "jvm-seed-$seed.json").writeText(json)
    }
    for ((gameType, scoringMode) in combinations) {
      File(outDir, "jvm-match-42-$gameType-$scoringMode.json")
        .writeText(runMatchJson(42, gameType, scoringMode))
    }
  }

  @Test
  fun theCorpusIsWrittenForBothRuntimes() {
    val outDir = parityDir()

    for ((gameType, scoringMode) in combinations) {
      val corpus = playCorpus(1, corpusSeedCount, gameType, scoringMode)
      // Every seed is accounted for exactly once, whether it played or not.
      assertEquals(
        corpusSeedCount,
        corpus.matches.size + corpus.failures.size,
        "$gameType/$scoringMode corpus must cover all $corpusSeedCount seeds",
      )
      assertEquals(
        corpus.matches.size * gameType.baseRounds,
        corpus.matches.sumOf { it.rounds.size },
        "$gameType/$scoringMode corpus must total ${gameType.baseRounds} rounds per played match",
      )
      // A seed the bots cannot legally finish is a finding about the BOTS, not
      // about the engine or the runtime — it is written into the manifest as
      // `failures` and both runtimes must produce the identical list.
      if (corpus.failures.isNotEmpty()) {
        corpus.failures.forEach { println("$gameType/$scoringMode seed ${it.seed}: ${it.message}") }
      }
      val json = runCorpusJson(1, corpusSeedCount, gameType, scoringMode)
      assertTrue(json.length > 10_000, "$gameType/$scoringMode corpus looks empty")
      assertTrue(
        json.contains("\"count\":$corpusSeedCount"),
        "$gameType/$scoringMode corpus must declare its $corpusSeedCount seeds",
      )
      File(outDir, "jvm-corpus-$gameType-$scoringMode.json").writeText(json)
    }
  }

  private fun parityDir(): File = File("build/stage0-parity").apply { mkdirs() }
}
