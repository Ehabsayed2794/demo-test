// No package declaration, deliberately — the same reason :functions' Functions.kt
// has none. @JsExport builds the export name from the package chain, so a
// packaged declaration would surface as `com.estemshan.stage0.runMatch` on the
// compiled module. The Worker entry consumes flat names.

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.stage0.runCapacityChainJson
import com.estemshan.stage0.runCapacityJson
import com.estemshan.stage0.runCapacityMixJson
import com.estemshan.stage0.runCorpusJson
import com.estemshan.stage0.runMatchJson
import com.estemshan.stage0.runRuleVectorsText
import com.estemshan.stage0.runSettlementStreamJson
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * E1's entry point as seen by the Worker: play one full match and hand back the
 * canonical JSON described in [runMatchJson]. Kept to a one-liner so the exported
 * surface contains no logic of its own to diverge from the JVM side.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runMatch(seed: Int, gameType: String, scoringMode: String): String =
  runMatchJson(seed, gameTypeOf(gameType), scoringModeOf(scoringMode))

/** E2's entry point: the corpus manifest for one (gameType, scoringMode) column. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCorpus(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCorpusJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E3's entry point: the whole rule/scoring vector table, rendered as the
 * deterministic line-oriented document the JVM side writes. One line per
 * vector case, so the comparison between the two runtimes is a byte
 * comparison.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runRuleVectors(): String = runRuleVectorsText()

/**
 * E5's entry point: the capacity battery — request counts (fingerprinted, so
 * the two runtimes must agree bytewise) plus the per-match timings the
 * capacity model is built from.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCapacity(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCapacityJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E6's entry point: the per-seat capacity battery — the same match stream as
 * E5's probe with every action attributed to the seat that acted, so the
 * human/bot mix can be applied as a model on top of measured loads.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCapacityMix(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCapacityMixJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E6's chain entry point: the longest run of consecutive server-side actions
 * that executes without an intervening human message, per seat attribution —
 * the quantity the 10 ms CPU-per-request budget actually bounds.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runCapacityChain(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runCapacityChainJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

/**
 * E7's entry point: the settlement stream a durable RP store has to apply
 * exactly once — one row per seat per finished match, RP carried seat to seat.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun runSettlementStream(firstSeed: Int, count: Int, gameType: String, scoringMode: String): String =
  runSettlementStreamJson(firstSeed, count, gameTypeOf(gameType), scoringModeOf(scoringMode))

private fun gameTypeOf(name: String): GameType =
  GameType.entries.firstOrNull { it.name == name.uppercase() }
    ?: error("unknown gameType: $name")

private fun scoringModeOf(name: String): ScoringMode =
  ScoringMode.entries.firstOrNull { it.name == name.uppercase() }
    ?: error("unknown scoringMode: $name")
