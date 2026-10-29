// No package declaration, deliberately — the same reason :functions' Functions.kt
// has none. @JsExport builds the export name from the package chain, so a
// packaged declaration would surface as `com.estemshan.stage0.runMatch` on the
// compiled module. The Worker entry consumes a flat `runMatch`.

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
fun runMatch(seed: Int): String = runMatchJson(seed)
