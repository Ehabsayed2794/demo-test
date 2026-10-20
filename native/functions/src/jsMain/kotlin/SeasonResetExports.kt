// NOTE: this file deliberately has NO package declaration.
//
// Same reason as Functions.kt: @JsExport builds the export name from the
// package chain, so a packaged declaration would export as
// `com.estemshan.functions.scheduledSeasonReset` — nested one level per package
// segment — and the Firebase CLI discovers triggers by walking the module's
// FLAT exports. The entry point stays top-level here; its logic
// ([runSeasonReset]) remains packaged and unit-testable.

import com.estemshan.functions.AdminSeasonResetDb
import com.estemshan.functions.SeasonResetMode
import com.estemshan.functions.SeasonResetReport
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
 * The handler runs the APPLY reading; the R27 dry run is the same function with
 * [SeasonResetMode.DRY_RUN], which is what makes it a rehearsal of the real
 * path rather than a second implementation.
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
    val report = runSeasonReset(
      AdminSeasonResetDb(),
      (js("Date.now()") as Double).toLong(),
      SeasonResetMode.APPLY,
    )
    console.log(summaryLine(report))
    // toPlain at the serialization boundary: runSeasonReset stays in Kotlin
    // types, the scheduled result is plain data.
    toPlain(reportForJs(report))
  }
}

/**
 * One grep-able line per run, reporting counts only. Player IDs stay out of the
 * log: a reset failure's player is a uid, and the report (not the console) is
 * where an operator gets the list.
 */
private fun summaryLine(report: SeasonResetReport): String =
  "S59_SEASON_RESET decision=${report.decision} " +
    "from=${report.fromSeasonId ?: "-"} to=${report.toSeasonId ?: "-"} " +
    "planned=${report.planned} applied=${report.applied} " +
    "skipped=${report.skippedAlreadyTarget} failures=${report.failures.size}"

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
