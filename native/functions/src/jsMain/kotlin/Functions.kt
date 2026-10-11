// NOTE: this file deliberately has NO package declaration.
//
// @JsExport builds the export name from the package chain, so a packaged
// declaration would export as `com.estemshan.functions.engineSmoke` — nested
// one level per package segment. The Firebase CLI discovers callables by
// walking the module's exports, and the conventional flat shape
// (`exports.engineSmoke`) is what every discovery path handles unambiguously.
// Declaring the entry points here keeps them top-level; the logic they call
// stays packaged and importable like everything else.

import com.estemshan.engine.Dealer
import com.estemshan.functions.AdminSettlementDb
import com.estemshan.functions.AdminTimeoutDb
import com.estemshan.functions.CallableAuth
import com.estemshan.functions.DecisionClock
import com.estemshan.functions.onCall
import com.estemshan.functions.settleOnce
import com.estemshan.functions.resolveTimeoutOnce
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlin.js.json

/**
 * Proves the three things S42's scaffold exists to prove, in one round trip:
 *  1. the module loads and exports a callable the Firebase CLI can see;
 *  2. callable-auth denies an unauthenticated request before any work happens;
 *  3. the real :engine runs under Node — a seeded deal yields the same
 *     4×13 unique-card hands the JVM golden tests assert.
 *
 * A fixed seed keeps the deal reproducible, so this is also a live regression
 * guard on the JS target: if the multiplatform port ever drifts from the JVM
 * engine, the counts stop being 52.
 */
@JsExport
@JsName("engineSmoke")
val engineSmoke = onCall { request ->
  val uid = CallableAuth.requireUid(request)

  val hands = Dealer.dealHands(seed = SMOKE_SEED)
  json(
    "ok" to true,
    "uid" to uid,
    "seatCount" to hands.size,
    "totalCards" to hands.values.sumOf { hand -> hand.size },
    "uniqueCards" to hands.values.flatten().distinct().size,
  )
}

private const val SMOKE_SEED: Long = 42L

/** Module scope for callable Promise bridging (handlers return, never await). */
private val SettleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * S50 settlement (RD12/RD13): re-reads the converged match document +
 * roundArchive history, re-runs the shared :engine over it, and settles
 * the recomputed result — correcting a disagreeing client claim, never
 * trusting it. RANKED only (ROOM/UNRANKED no-op); authenticated (the
 * settler must be a player); exactly-once per match (a replayed call
 * returns the recorded outcome).
 */
@JsExport
@JsName("settleMatch")
val settleMatch = onCall { request ->
  // toPlain at the serialization boundary: settleOnce stays in Kotlin
  // maps (unit-testable reads), the runtime JSON-serializes plain data.
  SettleScope.promise { toPlain(settleOnce(AdminSettlementDb(), request)) }
}

/**
 * S61 Stage 1 — the decision timer's authority. Any seated client triggers
 * this when its own server-derived countdown (S37) expires; the callable
 * re-derives the deadline from the match document's own server timestamp, so
 * expiry is one fact all four clients agree on, and then asks the MEDIUM bot
 * for that ONE decision.
 *
 * Resolution is server-side by necessity: the decision needs the acting seat's
 * hand, which firestore.rules keeps owner-read-only, so the acting player's
 * own app is the only client that could ever compute it — and a backgrounded,
 * disconnected, or terminated app would leave the match stalled forever. The
 * admin SDK reads the hand here instead, and only the DECISION is written
 * back; no hand ever reaches a client.
 *
 * No-op by design when the deadline has not passed (a late trigger, a racer,
 * or a player who decided a moment ago — the fresh in-transaction document
 * re-anchors the deadline past the decision).
 */
@JsExport
@JsName("resolveDecisionTimeout")
val resolveDecisionTimeout = onCall { request ->
  SettleScope.promise {
    toPlain(resolveTimeoutOnce(AdminTimeoutDb(), request, ServerClock))
  }
}

/**
 * The Functions runtime's own clock. firebase-admin reads committed documents,
 * so `Date.now()` here is the server's view of the present — the same clock
 * that stamped `updatedAt`.
 */
private val ServerClock = DecisionClock { (js("Date.now()") as Double).toLong() }
