package com.estemshan.functions

import com.estemshan.functions.Settlement.SettleDecision

/**
 * S50 settlement I/O + orchestration (RD12/RD13).
 *
 * The once-only contract: claim-check, recompute, correction write and
 * settlement record commit inside ONE transaction on the claim doc
 * (`matches/{matchId}/settlement/settle`). Two racers read absent, one
 * commits, the other retries into the recorded outcome — correction
 * writes and the claim can never diverge, so a crash cannot leave a
 * claimed-but-uncorrected match. This single-transaction shape is why the
 * settlement does not compose S42's two-phase IdempotencyGuard (claim
 * then commit would leave exactly that window); the guard's contract —
 * first invocation executes, replays return the recorded outcome — is
 * what this guarantees instead, and S51 composes the guard itself for RP
 * application per S42's own note.
 *
 * Ports keep every storage touch behind the [SettlementDb] interface so the
 * whole orchestration (gate → recompute → write/replay) is unit-testable
 * with fakes; only the runtime adapters touch real storage — the Firebase
 * Admin SDK in :functions, the Cloudflare Durable Object / D1 in the O-06
 * slice. The claim path itself is the adapter's business.
 */
interface SettlementDb {
  suspend fun readMatch(matchId: String): Map<String, Any?>?
  suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>>
  suspend fun <T> transact(block: suspend (SettlementTx) -> T): T

  interface SettlementTx {
    suspend fun readClaim(matchId: String): Map<String, Any?>?
    fun writeCorrection(matchId: String, scores: Map<String, Int>, winners: List<String>, version: Int)
    fun writeClaim(matchId: String, record: Map<String, Any?>)
  }
}

/** Callable response when the mode gate declines (ROOM/UNRANKED never settle). */
fun noOpResponse(mode: String): Map<String, Any?> = mapOf(
  "ok" to true,
  "settled" to false,
  "reason" to "NOT_RANKED",
  "mode" to mode,
)

private fun executedResponse(
  recomputed: Settlement.RecomputedMatch,
  matchId: String,
): Map<String, Any?> = mapOf(
  "ok" to true,
  "settled" to true,
  "replayed" to false,
  "matchId" to matchId,
  "corrected" to recomputed.corrected,
  "finalScores" to recomputed.finalScores,
  "winnerIds" to recomputed.winnerIds,
  "claimedScores" to recomputed.claimedScores,
  "claimedWinners" to recomputed.claimedWinners,
  "rounds" to recomputed.rounds,
)

private fun claimRecord(
  uid: String,
  response: Map<String, Any?>,
): Map<String, Any?> = mapOf(
  "settledBy" to uid,
  // A sentinel, not a timestamp: the storage adapter resolves it at commit
  // time (Firebase: FieldValue.serverTimestamp(); DO/D1: SQL now()).
  "settledAt" to AuthoritySentinels.serverTimestamp(),
  "outcome" to response,
)

/**
 * Runs one settlement: auth → read → gate → recompute → transacted
 * claim/write. The caller (a runtime adapter) has already verified the
 * caller's identity and passes the verified [uid]; [data] is the raw
 * callable payload and is converted here because both runtimes hand it
 * over as a plain JS object, never a Kotlin map.
 *
 * Throws [AuthorityError] on every denial (the adapter maps the code onto
 * its wire envelope — Firebase: `HttpsError`; Worker: the callable
 * envelope). Returns Kotlin maps throughout so the orchestration stays
 * unit-testable — the adapter applies toPlain() once, at the serialization
 * boundary (plain JS objects fail Kotlin Map reads).
 */
suspend fun settleOnce(db: SettlementDb, uid: String, data: Any?): Map<String, Any?> {
  // The runtime delivers the payload as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one, silently rejecting every real call with
  // "matchId is required". Convert at the boundary, as below.
  val converted = dynamicToKotlin(data) as? Map<*, *>
  val matchId = converted?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw AuthorityError("invalid-argument", "settleMatch: matchId is required.")
  }

  val fields = db.readMatch(matchId)
    ?: throw AuthorityError("not-found", "settleMatch: match '$matchId' was not found.")
  val parsed = Settlement.parseMatch(matchId, fields)
    ?: throw AuthorityError(
      "failed-precondition",
      "settleMatch: match '$matchId' is not a match document.",
    )
  when (val gate = Settlement.decide(uid, parsed)) {
    is SettleDecision.NoOp -> return noOpResponse(gate.mode.name)
    is SettleDecision.Failed -> throw AuthorityError(
      if (gate.reason == Settlement.Reason.NOT_MEMBER) "permission-denied" else "failed-precondition",
      gate.message,
    )
    SettleDecision.Proceed -> Unit
    else -> throw AuthorityError("internal", "settleMatch: unreachable gate.")
  }

  val archives = db.readArchives(matchId).mapNotNull { (docId, archiveFields) ->
    Settlement.parseArchive(docId, archiveFields)?.let { docId to it }
      ?: throw AuthorityError(
        "failed-precondition",
        "settleMatch: roundArchive/$docId is malformed.",
      )
  }.toMap()
  val withHistory = parsed.copy(archives = archives.values.toList())

  val decision = Settlement.recompute(withHistory)
  val recomputed = when (decision) {
    is SettleDecision.Agreed -> decision.recomputed
    is SettleDecision.Corrected -> decision.recomputed
    is SettleDecision.Failed -> throw AuthorityError("failed-precondition", decision.message)
    else -> throw AuthorityError("internal", "settleMatch: unreachable recompute.")
  }

  return db.transact { tx ->
    val existing = tx.readClaim(matchId)
    if (existing != null) {
      @Suppress("UNCHECKED_CAST")
      val recorded = existing["outcome"] as? Map<String, Any?>
        ?: throw AuthorityError(
          "failed-precondition",
          "settleMatch: settlement record for '$matchId' is malformed.",
        )
      val replayed = recorded.toMutableMap()
      replayed["replayed"] = true
      return@transact replayed
    }
    if (recomputed.corrected) {
      val version = (fields["version"] as? Number)?.toInt() ?: 0
      tx.writeCorrection(matchId, recomputed.finalScores, recomputed.winnerIds, version + 1)
    }
    val response = executedResponse(recomputed, matchId)
    tx.writeClaim(matchId, claimRecord(uid, response))
    response
  }
}
