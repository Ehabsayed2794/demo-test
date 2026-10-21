// NOTE: this file deliberately has NO package declaration.
//
// Same reason as Functions.kt: @JsExport builds the export name from the
// package chain, so a packaged declaration would export as
// `com.estemshan.functions.scheduledSeasonReset` — nested one level per package
// segment — and the Firebase CLI discovers triggers by walking the module's
// FLAT exports. The entry point stays top-level here; its logic
// ([runSeasonReset]) remains packaged and unit-testable.

import com.estemshan.functions.AdminSeasonResetDb
import com.estemshan.functions.CallableAuth
import com.estemshan.functions.HttpsError
import com.estemshan.functions.SeasonResetGate
import com.estemshan.functions.SeasonResetGateDecision
import com.estemshan.functions.SeasonResetMode
import com.estemshan.functions.SeasonResetOperator
import com.estemshan.functions.SeasonResetReport
import com.estemshan.functions.onCall
import com.estemshan.functions.onSchedule
import com.estemshan.functions.runSeasonReset
import com.estemshan.functions.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise

/** Module scope for the scheduled handler's Promise bridging (the handler returns, never awaits). */
private val SeasonResetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * S59 (RD24, R27) — the end-of-season reset, on a daily 03:00 UTC schedule.
 *
 * The schedule is only a TRIGGER, never the authority: every run re-reads
 * `seasons/current`, gates on its own `endsAt`, and claims the transition in a
 * transaction, so a missed or duplicated tick cannot double-reset a season.
 * The R27 dry run is the same function with [SeasonResetMode.DRY_RUN], which is
 * what makes it a rehearsal of the real path rather than a second
 * implementation.
 *
 * The handler is DRY_RUN by default. It APPLYs only when [SeasonResetGate]
 * finds all three deploy-time conditions at once: `config/seasonReset` is
 * enabled, it names the approved target season, and its environment matches the
 * `SEASON_RESET_ENV` set on the deployed service. A normal Functions deploy
 * sets none of them, so a full deploy cannot arm the reset by itself.
 *
 * Domain outcomes resolve with a structured summary (a no-op season is a
 * legitimately quiet result and must never throw). A transport failure that
 * prevents knowing the state propagates: the scheduler records it as a failed
 * run, which is the honest signal, and the season document still says exactly
 * what happened because the claim/finalise transactions are atomic.
 */
@JsExport
@JsName("scheduledSeasonReset")
val scheduledSeasonReset = onSchedule(
  js("({ schedule: '0 3 * * *', timeZone: 'UTC' })"),
) { _ ->
  SeasonResetScope.promise {
    val db = AdminSeasonResetDb()
    val gate = try {
      SeasonResetGate.decide(
        db.readGateConfig(),
        js("process.env.SEASON_RESET_ENV") as? String,
      )
    } catch (failure: Throwable) {
      // A config read that cannot be trusted must never arm an APPLY.
      console.warn(
        "S59_SEASON_RESET gate read failed; staying in dry-run: " +
          (failure.message ?: failure.toString()),
      )
      SeasonResetGateDecision(
        SeasonResetMode.DRY_RUN,
        null,
        "config/seasonReset read failed",
      )
    }
    val report = runSeasonReset(
      db,
      (js("Date.now()") as Double).toLong(),
      gate.mode,
      gate.targetSeasonId,
    )
    console.log(summaryLine(report, gate))
    // toPlain at the serialization boundary: runSeasonReset stays in Kotlin
    // types, the scheduled result is plain data.
    toPlain(reportForJs(report))
  }
}

/**
 * The operator rehearsal: the same DRY_RUN reading as the scheduler, callable on
 * demand by an allowlisted operator. It never applies, even when the scheduler's
 * gate is enabled, and it returns the same structured report the scheduler logs.
 *
 * Authorization has two layers: [CallableAuth] requires a verified Firebase
 * identity, and [SeasonResetOperator] requires that uid to be named in the
 * deploy-time `SEASON_RESET_OPERATOR_UIDS` allowlist. A missing or empty
 * allowlist denies everyone.
 */
@JsExport
@JsName("operatorSeasonResetDryRun")
val operatorSeasonResetDryRun = onCall { request ->
  val uid = CallableAuth.requireUid(request)
  val allowlist = js("process.env.SEASON_RESET_OPERATOR_UIDS") as? String
  if (!SeasonResetOperator.isAllowed(uid, allowlist)) {
    throw HttpsError(
      "permission-denied",
      "operatorSeasonResetDryRun is restricted to the configured season-reset operator.",
    )
  }
  SeasonResetScope.promise {
    val db = AdminSeasonResetDb()
    val config = try {
      db.readGateConfig()
    } catch (failure: Throwable) {
      console.warn(
        "S59_SEASON_RESET operator dry-run config read failed; continuing without an approved target: " +
          (failure.message ?: failure.toString()),
      )
      null
    }
    val report = runSeasonReset(
      db,
      (js("Date.now()") as Double).toLong(),
      SeasonResetMode.DRY_RUN,
      config?.targetSeasonId,
    )
    console.log(
      summaryLine(
        report,
        SeasonResetGateDecision(
          SeasonResetMode.DRY_RUN,
          config?.targetSeasonId,
          "operator dry-run requested by an authorized operator",
        ),
      ),
    )
    toPlain(reportForJs(report))
  }
}

/**
 * One grep-able line per run, reporting counts only. Player IDs stay out of the
 * log: a reset failure's player is a uid, and the report (not the console) is
 * where an operator gets the list.
 */
private fun summaryLine(report: SeasonResetReport, gate: SeasonResetGateDecision): String =
  "S59_SEASON_RESET mode=${report.mode} gate=${oneLine(gate.reason)} " +
    "decision=${report.decision} " +
    "from=${report.fromSeasonId ?: "-"} to=${report.toSeasonId ?: "-"} " +
    "planned=${report.planned} applied=${report.applied} " +
    "skipped=${report.skippedAlreadyTarget} failures=${report.failures.size}"

private fun oneLine(value: String): String =
  value.replace('\n', ' ').replace('\r', ' ')

/** The report as schedulers and logs can read it — plain maps, failures included. */
private fun reportForJs(report: SeasonResetReport): Map<String, Any?> = mapOf(
  "mode" to report.mode.name,
  "decision" to report.decision,
  "fromSeasonId" to report.fromSeasonId,
  "toSeasonId" to report.toSeasonId,
  "planned" to report.planned,
  "applied" to report.applied,
  "skippedAlreadyTarget" to report.skippedAlreadyTarget,
  "failures" to report.failures.map { failure ->
    mapOf("playerId" to failure.playerId, "reason" to failure.reason)
  },
  "message" to report.message,
)
