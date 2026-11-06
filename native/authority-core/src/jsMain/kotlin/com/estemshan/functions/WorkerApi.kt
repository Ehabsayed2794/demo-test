// NOTE: this file deliberately has NO package declaration — mirrors
// :functions' Functions.kt. A packaged @JsExport builds a nested export
// path (com.estemshan.functions.*); the Worker imports this bundle with
// the same flat shape as the Firebase export map, so the entry points stay
// top-level.
//
// IMPORTANT (O-06 collision fix): the Worker entry points are exported as
// members of ONE `WorkerApi` object, not as top-level names. :functions
// depends on this module and Kotlin/JS re-exports a dependency's @JsExport
// declarations into the consumer's bundle (`$jsExportAll$`). Top-level
// `settleMatch` / `resolveDecisionTimeout` / `engineSmoke` here therefore
// collided with the Firebase callables of the same name and made the
// :functions bundle throw at require() time ("Cannot set property
// engineSmoke ... which has only a getter"). A namespaced export cannot
// collide with a Firebase callable name, now or when a Worker entry point
// is added later.

import com.estemshan.engine.Dealer
import com.estemshan.functions.DO_SCHEMA
import com.estemshan.functions.AuthorityError
import com.estemshan.functions.DecisionClock
import com.estemshan.functions.DoSettlementDb
import com.estemshan.functions.DoTimeoutDb
import com.estemshan.functions.UnwiredSettlementDb
import com.estemshan.functions.UnwiredTimeoutDb
import com.estemshan.functions.resolveTimeoutOnce
import com.estemshan.functions.settleOnce
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.js.json

private val ExportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private const val SMOKE_SEED: Long = 42L

private fun errorEnvelope(code: String, message: String): dynamic =
  json("ok" to false, "error" to json("code" to code, "message" to message))

private suspend fun guard(block: suspend () -> Map<String, Any?>): dynamic = try {
  toPlain(block())
} catch (e: AuthorityError) {
  errorEnvelope(e.code, e.message)
} catch (e: Throwable) {
  errorEnvelope("internal", "The authority layer failed.")
}

private fun isUnwired(sqlHandle: dynamic): Boolean = sqlHandle == null

/**
 * The Worker's entire JS surface, under a single name.
 *
 * The DO's SQLite schema is re-exported here for `native/worker/src/match-do.js`
 * (it creates the tables on first construction) — and this is the only place
 * the export lives, so the schema string has exactly one source of truth
 * ([DO_SCHEMA]).
 *
 * No `firebase-*` import, no callable framework, no ambient auth: the caller
 * (the Worker's dispatch, or a Durable Object RPC endpoint) has already
 * verified the caller's identity and passes the verified `uid` plus the raw
 * callable payload. Every entry point returns a plain, JSON-serializable
 * object; failures the caller should switch on come back as
 * `{ ok: false, error: { code, message } }` (lowercase-dash, the Firebase
 * wire spelling) — never a Kotlin exception object, whose property
 * visibility is not guaranteed on the JS side.
 *
 * Storage injection (O-06): the two DB-backed entry points take an explicit
 * third argument, `sqlHandle` — the raw `ctx.storage.sql` object under
 * workerd, a hand-rolled fake in tests, or `null` (the HTTP edge has no
 * database; it passes `null` and keeps the fail-closed 501 behaviour).
 * `null`/`undefined` is treated as unwired for caller convenience.
 */
@JsExport
@JsName("WorkerApi")
object WorkerApi {
  /** The DO's SQLite schema (one source of truth: [DO_SCHEMA]). */
  @JsName("DO_SCHEMA")
  val schema: String = DO_SCHEMA

  /**
   * Proves the module loads on this runtime and the shared engine runs on
   * it — the same 4×13 seeded deal the JVM golden tests and the Firebase
   * `engineSmoke` callable assert. A fixed seed keeps it reproducible.
   */
  @JsName("engineSmoke")
  fun engineSmoke(uid: String): dynamic = try {
    val hands = Dealer.dealHands(seed = SMOKE_SEED)
    json(
      "ok" to true,
      "uid" to uid,
      "seatCount" to hands.size,
      "totalCards" to hands.values.sumOf { hand -> hand.size },
      "uniqueCards" to hands.values.flatten().distinct().size,
    )
  } catch (e: Throwable) {
    errorEnvelope("internal", "The authority layer failed.")
  }

  /** RD12/RD13 settlement. [sqlHandle] selects the storage; null fails closed. */
  @JsName("settleMatch")
  fun settleMatch(uid: String, data: dynamic, sqlHandle: dynamic): Promise<dynamic> =
    ExportScope.promise {
      guard {
        if (isUnwired(sqlHandle)) {
          settleOnce(UnwiredSettlementDb, uid, data)
        } else {
          settleOnce(DoSettlementDb(sqlHandle), uid, data)
        }
      }
    }

  /** S61 Stage 1 decision timer. [sqlHandle] selects the storage; null fails closed. */
  @JsName("resolveDecisionTimeout")
  fun resolveDecisionTimeout(uid: String, data: dynamic, sqlHandle: dynamic): Promise<dynamic> =
    ExportScope.promise {
      guard {
        if (isUnwired(sqlHandle)) {
          resolveTimeoutOnce(
            UnwiredTimeoutDb,
            uid,
            data,
            DecisionClock { (js("Date.now()") as Double).toLong() },
          )
        } else {
          resolveTimeoutOnce(
            DoTimeoutDb(sqlHandle),
            uid,
            data,
            DecisionClock { (js("Date.now()") as Double).toLong() },
          )
        }
      }
    }
}
