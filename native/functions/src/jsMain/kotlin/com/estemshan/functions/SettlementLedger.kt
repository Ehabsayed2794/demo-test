package com.estemshan.functions

import com.estemshan.functions.Settlement.ConvergedMatch
import com.estemshan.functions.Settlement.RecomputedMatch

/**
 * S62 RP audit ledger + observability hooks (plan S62; risks R25/R29/R30).
 *
 * The ledger is the internal settlement watchtower - nothing user-facing:
 * - one append-only document per settlement event in the admin-write-only
 *   [COLLECTION]. It has no firestore.rules entry, so clients are denied by
 *   default and the rules file stays byte-identical;
 * - a settlement that APPLIES or CORRECTS is recorded with the claimed vs
 *   final diff (the R25 signature), the settling uid, a server timestamp
 *   and a schemaVersion;
 * - a recompute failure and a malformed roundArchive are recorded as
 *   FAILED: a settlement blocked by corrupt history is exactly the anomaly
 *   the R25 field watch must see.
 *
 * Exactly-once: the document id is event-scoped and deterministic
 * ([ledgerDocId]) - a settlement is keyed by its matchId (a match settles
 * at most once, S50 claim), a failure by matchId + reason. The Admin
 * implementation appends inside a transaction that reads the id first, so
 * a delivery retried into it can never produce a second document.
 * settleOnce never appends from the replay path at all, so the ordinary
 * client retry is a read-only no-op for the ledger.
 *
 * The ledger never changes settlement behavior: the settlement transaction
 * has already committed before any of this runs, and every append and
 * detector read here is caught and logged - best-effort by contract.
 *
 * Detector wiring (where it runs, and why there): [recordSettlement] calls
 * [detectRepeats] inline, once, only on a CORRECTED settlement, against the
 * bounded corrections window just written. A correction is already the rare
 * path (RD12 fires only when the claim contradicts the recomputed history),
 * so one awaited read per correction is the cheap place; there is no
 * scheduled or on-demand scan. Its output is an S62_REPEAT console line, not
 * a rule outcome: per A4.3 nothing in placement, RP or statistics may depend
 * on it, which is also why a failed detector read is a log line and not an
 * error.
 *
 * Decisions recorded here:
 * - NoOp (ROOM/UNRANKED) and gate-denied (NOT_MEMBER) attempts do not
 *   write ledger documents: they are not settlement outcomes, and writes
 *   must not be amplifiable by an authenticated prober. Each gets one
 *   cheap ASCII console line instead (retry forensics, zero Firestore
 *   cost). Consequence for R30, stated plainly: the ledger's rows are all
 *   RANKED, so it answers R29 (settlement volume) and R25 (corrections)
 *   directly; the Ranked-vs-Unranked split is NOT read from here. R30 as
 *   amended (2026-10-18) counts that split at the M11 gate from the
 *   matches documents' own mode field.
 * - A replayed settlement never appends. The tiny window between the
 *   settlement commit and the append is accepted as lost on a process
 *   crash: making the append atomic with settlement is forbidden by the
 *   never-fail-settlement contract.
 * - Console lines are emitted for CORRECTED and FAILED only; APPLIED is
 *   silent (R29's usage signal is the ledger count, not log volume).
 * - The detector reads [COLLECTION_CORRECTIONS], a second admin-write-only
 *   collection that CORRECTED rows alone are also appended to. It is a
 *   materialized window of the ledger, not a second source of truth: its row
 *   commits in the SAME transaction as the ledger row, so a correction can
 *   never appear in one and not the other. Why it exists - narrowing the
 *   ledger query itself to corrections means an equality filter plus an
 *   orderBy on a different field, which Firestore serves only from a
 *   composite index (a new indexes artifact plus a deployment). Reading the
 *   newest 50 ledger documents instead would make R25 volume-bound: two
 *   corrections would have to fall inside the same 50-document span, so the
 *   detector would go blindest exactly when corrections get rare - the good
 *   case, and the one a fraud watch has to see.
 */
object SettlementLedger {

  /** Admin-write-only collection; no rules entry means clients are denied. */
  const val COLLECTION = "settlementLedger"

  /**
   * The repeat detector's bounded read window: admin-write-only like
   * [COLLECTION], and holds a copy of CORRECTED ledger rows alone, so a
   * recency-ordered read of it samples corrections rather than settlement
   * traffic. Written atomically with its ledger row - see [appendTargets].
   */
  const val COLLECTION_CORRECTIONS = "settlementCorrections"

  /** Pinned record schema version. Bump on any shape change. */
  const val SCHEMA_VERSION = 1

  const val OUTCOME_APPLIED = "APPLIED"
  const val OUTCOME_CORRECTED = "CORRECTED"
  const val OUTCOME_FAILED = "FAILED"

  const val KIND_PLAYER = "PLAYER"
  const val KIND_TABLE = "TABLE"

  /**
   * How many recent corrections the repeat detector reads, newest-first, in
   * one bounded read of [COLLECTION_CORRECTIONS]. 50 is a field-watch sample,
   * not a census: the detector needs two corrections, and because this
   * collection holds corrections only, the sample stays 50 CORRECTIONS however
   * high the settlement volume around them gets.
   */
  const val DETECT_WINDOW = 50

  /** "Repeated" starts at the second correction (per player / per table). */
  const val REPEAT_THRESHOLD = 2

  // ---- record builders (pure) ---------------------------------------------

  /**
   * The audit record for a settlement that APPLIED or CORRECTED. claimed*
   * and final* are the correction diff itself (the R25 signature);
   * [changedSeats] and [winnerChanged] make the diff explicit without
   * requiring a reader to re-derive it.
   */
  fun settlementRecord(
    match: ConvergedMatch,
    recomputed: RecomputedMatch,
    settledBy: String,
  ): Map<String, Any?> {
    val changedSeats = match.order.filter { seat ->
      recomputed.finalScores[seat] != recomputed.claimedScores[seat]
    }
    return linkedMapOf(
      "schemaVersion" to SCHEMA_VERSION,
      "matchId" to match.matchId,
      "mode" to match.mode.name,
      "gameType" to match.gameType.name,
      "players" to match.players,
      "outcomeKind" to if (recomputed.corrected) OUTCOME_CORRECTED else OUTCOME_APPLIED,
      "corrected" to recomputed.corrected,
      "settledBy" to settledBy,
      "rounds" to recomputed.rounds,
      "claimedScores" to recomputed.claimedScores,
      "claimedWinners" to recomputed.claimedWinners,
      "finalScores" to recomputed.finalScores,
      "winnerIds" to recomputed.winnerIds,
      "changedSeats" to changedSeats,
      "winnerChanged" to (recomputed.winnerIds.sorted() != recomputed.claimedWinners.sorted()),
      "appendedAt" to FieldValue.serverTimestamp(),
    )
  }

  /**
   * The audit record for a settlement that failed closed (recompute
   * failure or malformed archive). Deliberately lean: no correction fields
   * exist for a settlement that never happened.
   */
  fun failureRecord(
    match: ConvergedMatch,
    settledBy: String,
    reason: String,
    message: String,
  ): Map<String, Any?> = linkedMapOf(
    "schemaVersion" to SCHEMA_VERSION,
    "matchId" to match.matchId,
    "mode" to match.mode.name,
    "gameType" to match.gameType.name,
    "players" to match.players,
    "outcomeKind" to OUTCOME_FAILED,
    "settledBy" to settledBy,
    "failureReason" to reason,
    "failureMessage" to message,
    "appendedAt" to FieldValue.serverTimestamp(),
  )

  /**
   * The deterministic, event-scoped document id: a settlement is keyed by
   * its matchId (a match settles at most once), a failure by matchId +
   * reason (the first failure of a reason class per match is kept -
   * retried identical failures coalesce, so a prober cannot amplify
   * documents). Null when the record carries no matchId: an
   * unidentifiable ledger document is never appended.
   */
  fun ledgerDocId(record: Map<String, Any?>): String? {
    val matchId = record["matchId"] as? String ?: return null
    return if (record["outcomeKind"] == OUTCOME_FAILED) {
      "$matchId-fail-${record["failureReason"] as? String ?: "UNKNOWN"}"
    } else {
      matchId
    }
  }

  /**
   * Which collections one record is appended to, in write order: the ledger
   * always, plus [COLLECTION_CORRECTIONS] for a CORRECTED settlement (the
   * detector's materialized window). Same document id in both, so the two
   * rows are the same event seen twice - and the Admin implementation writes
   * them in ONE transaction, which is why pure [detectRepeats] can treat the
   * window as authoritative. Pure so the materialization rule is asserted by
   * a unit test instead of living untested inside the SDK layer.
   */
  fun appendTargets(record: Map<String, Any?>): List<String> =
    if (record["outcomeKind"] == OUTCOME_CORRECTED) {
      listOf(COLLECTION, COLLECTION_CORRECTIONS)
    } else {
      listOf(COLLECTION)
    }

  // ---- structured console lines (ASCII, greppable) ------------------------

  /** One line for a recorded CORRECTED/FAILED settlement: matchId + players. */
  fun settleLine(record: Map<String, Any?>): String {
    val line = StringBuilder("S62_SETTLE outcome=").append(record["outcomeKind"])
    line.append(" matchId=").append(record["matchId"])
    line.append(" players=").append(playerKey(record["players"]))
    (record["failureReason"] as? String)?.let { line.append(" reason=").append(it) }
    return line.toString()
  }

  /** NoOp hook line (not ledger-recorded - see the file KDoc). */
  fun noOpLine(matchId: String, mode: String): String =
    "S62_SETTLE outcome=NOOP matchId=$matchId mode=$mode"

  /** Denied-attempt hook line (not ledger-recorded - see the file KDoc). */
  fun deniedLine(matchId: String, uid: String, reason: String): String =
    "S62_SETTLE outcome=DENIED matchId=$matchId uid=$uid reason=$reason"

  /** One line per repeat signature (the R25 field-watch signal). */
  fun repeatLine(signature: RepeatSignature): String =
    "S62_REPEAT kind=${signature.kind} key=${signature.key.joinToString("+")} " +
      "occurrences=${signature.occurrences}"

  private fun playerKey(value: Any?): String =
    (value as? List<*>)?.mapNotNull { it as? String }?.joinToString(",") ?: ""

  // ---- the pure repeat detector -------------------------------------------

  /** One ledger document, reduced to what the repeat detector needs. */
  data class LedgerEntry(
    val matchId: String,
    val players: List<String>,
    val settledBy: String,
    val outcomeKind: String,
  )

  /** Fail-closed parse of a ledger document read back from Firestore. */
  fun parseEntry(raw: Map<String, Any?>?): LedgerEntry? {
    if (raw == null) return null
    val matchId = raw["matchId"] as? String ?: return null
    val outcomeKind = raw["outcomeKind"] as? String ?: return null
    val settledBy = raw["settledBy"] as? String ?: return null
    val playersRaw = raw["players"] as? List<*> ?: return null
    val players = playersRaw.map { it as? String ?: return null }
    return LedgerEntry(matchId, players, settledBy, outcomeKind)
  }

  /** A repeat-correction signature (the R25 field watch). */
  data class RepeatSignature(
    val kind: String,
    /** The recurring player (size 1) or the recurring roster (sorted uids). */
    val key: List<String>,
    val occurrences: Int,
  )

  /**
   * The pure R25 detector: "repeated correct-and-settle corrections from
   * the same player or the same table". Only CORRECTED entries count, and
   * each distinct matchId counts once (defensive; a match settles once).
   * A player counts once per corrected match when the player is on the
   * roster - the settling claimant is always a roster member (membership
   * is gated before any settlement), so the settledBy union in the loop
   * is defensive only. A PLAYER signature therefore means "this uid sat
   * at two or more corrected tables", not "this uid authored the
   * corrections": no claim-authorship identity exists at MVP. A table
   * counts once per corrected match by its sorted roster ("the same
   * table"). A repeatedly-corrected table flags both its table signature
   * and each recurring player - both statements are true; the TABLE line
   * identifies the cluster. Returns signatures at or above [threshold] in
   * encounter order (deterministic for a given input).
   */
  fun detectRepeats(
    entries: List<LedgerEntry>,
    threshold: Int = REPEAT_THRESHOLD,
  ): List<RepeatSignature> {
    val correctedByMatch = LinkedHashMap<String, LedgerEntry>()
    for (entry in entries) {
      if (entry.outcomeKind != OUTCOME_CORRECTED) continue
      if (!correctedByMatch.containsKey(entry.matchId)) {
        correctedByMatch[entry.matchId] = entry
      }
    }
    if (correctedByMatch.size < threshold) return emptyList()
    val playerCounts = LinkedHashMap<String, Int>()
    val tableCounts = LinkedHashMap<String, Int>()
    for (entry in correctedByMatch.values) {
      val involved = LinkedHashSet<String>(entry.players)
      involved.add(entry.settledBy)
      for (uid in involved) playerCounts[uid] = (playerCounts[uid] ?: 0) + 1
      val tableKey = entry.players.sorted().joinToString(",")
      tableCounts[tableKey] = (tableCounts[tableKey] ?: 0) + 1
    }
    val signatures = ArrayList<RepeatSignature>()
    for ((uid, count) in playerCounts) {
      if (count >= threshold) signatures.add(RepeatSignature(KIND_PLAYER, listOf(uid), count))
    }
    for ((tableKey, count) in tableCounts) {
      if (count >= threshold) signatures.add(RepeatSignature(KIND_TABLE, tableKey.split(","), count))
    }
    return signatures
  }

  // ---- best-effort hooks (never fail the settlement) ----------------------

  /**
   * Post-commit settlement hook: builds the record, emits the CORRECTED
   * console line, appends best-effort, then runs the bounded repeat
   * detector for corrections. Called by settleOnce only for the invocation
   * that actually applied the settlement - never for a replay.
   *
   * The whole body is guarded: a throw anywhere inside it (record building,
   * logging, append, detector) is logged and swallowed, so this can never
   * fail the settlement that has already committed.
   *
   * Returns the signatures it logged (empty when the read failed or the
   * hook was swallowed). Nothing in the settlement path consumes them; they
   * exist so the wiring is assertable in a unit test instead of argued in a
   * comment.
   */
  suspend fun recordSettlement(
    db: SettlementDb,
    match: ConvergedMatch,
    recomputed: RecomputedMatch,
    settledBy: String,
  ): List<RepeatSignature> {
    return try {
      val record = settlementRecord(match, recomputed, settledBy)
      if (recomputed.corrected) console.log(settleLine(record))
      appendQuietly(db, record)
      if (recomputed.corrected) detectQuietly(db, match.matchId) else emptyList()
    } catch (t: Throwable) {
      hookFailed("recordSettlement", match.matchId, t)
      emptyList()
    }
  }

  /**
   * Failure hook (recompute failures and malformed archives): console line
   * plus a best-effort FAILED record. The caller rethrows its own
   * HttpsError afterwards - the ledger never replaces the denial, and
   * (like [recordSettlement]) this guarded body never throws.
   */
  suspend fun reportFailure(
    db: SettlementDb,
    match: ConvergedMatch,
    settledBy: String,
    reason: String,
    message: String,
  ) {
    try {
      val record = failureRecord(match, settledBy, reason, message)
      console.log(settleLine(record))
      appendQuietly(db, record)
    } catch (t: Throwable) {
      hookFailed("reportFailure", match.matchId, t)
    }
  }

  /**
   * Last resort for a hook that failed outside the own guards of
   * [appendQuietly] and [detectQuietly]: log and swallow. The settlement has
   * already committed, so the ledger must never change that - best-effort
   * by contract - and even this logging must not escape.
   */
  private fun hookFailed(hook: String, matchId: String, t: Throwable) {
    try {
      console.log("S62_LEDGER_HOOK_FAILED hook=$hook matchId=$matchId error=${t.message}")
    } catch (ignored: Throwable) {
      // console.log itself failed: nowhere left to report, and not throwing
      // is the entire point of this function.
    }
  }

  private suspend fun appendQuietly(db: SettlementDb, record: Map<String, Any?>) {
    try {
      db.appendLedger(record)
    } catch (t: Throwable) {
      console.log(
        "S62_LEDGER_APPEND_FAILED matchId=${record["matchId"]} " +
          "docId=${ledgerDocId(record)} error=${t.message}",
      )
    }
  }

  private suspend fun detectQuietly(
    db: SettlementDb,
    matchId: String,
  ): List<RepeatSignature> {
    val window = try {
      db.readCorrectionWindow()
    } catch (t: Throwable) {
      console.log("S62_LEDGER_READ_FAILED matchId=$matchId error=${t.message}")
      return emptyList()
    }
    // The window is corrections newest-first, and it already contains the row
    // this settlement just wrote (the append transaction has committed). The
    // detector re-filters the outcome kind and dedupes per matchId anyway: a
    // hand-written or future row in that collection can only widen the input,
    // never fake a signature.
    val signatures = detectRepeats(window.mapNotNull { parseEntry(it) })
    for (signature in signatures) console.log(repeatLine(signature))
    return signatures
  }
}
