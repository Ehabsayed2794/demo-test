// No package declaration, deliberately — the same reason :functions' Functions.kt
// has none. @JsExport builds the export name from the package chain, so a
// packaged declaration would surface as `com.estemshan.stage0.runMatch` on the
// compiled module. The Worker entry consumes flat names.

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.stage0.runCorpusJson
import com.estemshan.stage0.runMatchJson
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

private fun gameTypeOf(name: String): GameType =
  GameType.entries.firstOrNull { it.name == name.uppercase() }
    ?: error("unknown gameType: $name")

private fun scoringModeOf(name: String): ScoringMode =
  ScoringMode.entries.firstOrNull { it.name == name.uppercase() }
    ?: error("unknown scoringMode: $name")
