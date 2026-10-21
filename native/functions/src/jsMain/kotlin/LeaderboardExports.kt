// NOTE: this file deliberately has NO package declaration.
//
// Same reason as Functions.kt: @JsExport builds the export name from the
// package chain, so a packaged declaration would export as
// `com.estemshan.functions.getRankedLeaderboard` — nested one level per package
// segment — and the Firebase CLI discovers callables by walking the module's
// FLAT exports. The entry point stays top-level here; its logic
// ([leaderboardOnce]) remains packaged and unit-testable.

import com.estemshan.functions.AdminLeaderboardDb
import com.estemshan.functions.leaderboardOnce
import com.estemshan.functions.onCall
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise

/** Module scope for this callable's Promise bridging (the handler returns, never awaits). */
private val BoardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * S64 (RD27) — the seasonal Ranked leaderboard, ordered by the server.
 *
 * Reads one season's `players` profiles through the Admin SDK, hands them to
 * the shared :engine ordering, and returns rows that already carry their
 * position plus the caller's own row. Authenticated because the highlight is
 * derived from the verified uid — never from a client claim about who is
 * looking — and because the board is the one place a player's RP is shown to
 * other people, which is a server decision, not a client read.
 */
@JsExport
@JsName("getRankedLeaderboard")
val getRankedLeaderboard = onCall { request ->
  // toPlain at the serialization boundary: leaderboardOnce stays in Kotlin
  // maps (unit-testable reads), the runtime JSON-serializes plain data.
  BoardScope.promise { toPlain(leaderboardOnce(AdminLeaderboardDb(), request)) }
}
