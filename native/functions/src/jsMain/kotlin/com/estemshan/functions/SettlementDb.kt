package com.estemshan.functions

import com.estemshan.functions.Settlement.ConvergedMatch
import com.estemshan.functions.Settlement.SettleDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S50 settlement I/O + orchestration (RD12/RD13).
 *
 * The once-only contract: claim-check, recompute, correction write and
 * settlement record commit inside ONE Admin transaction on the claim doc
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
 * Ports keep every Firestore touch behind interfaces so the whole
 * orchestration (gate → recompute → write/replay) is unit-testable with
 * fakes; only AdminSettlementDb loads the SDK.
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

private const val CLAIM_PATH = "settlement"
private const val CLAIM_DOC = "settle"

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
  "settledAt" to FieldValue.serverTimestamp(),
  "outcome" to response,
)

/**
 * Runs one settlement: auth → read → gate → recompute → transacted
 * claim/write. Throws HttpsError on every denial (the client switches on
 * code). Returns Kotlin maps throughout so the orchestration stays
 * unit-testable — the callable handler applies toPlain() once, at the
 * serialization boundary (plain JS objects fail Kotlin Map reads).
 */
suspend fun settleOnce(db: SettlementDb, request: CallableRequest): Map<String, Any?> {
  val data = request.data as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "settleMatch: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "settleMatch: match '$matchId' was not found.")
  val parsed = Settlement.parseMatch(matchId, fields)
    ?: throw HttpsError(
      "failed-precondition",
      "settleMatch: match '$matchId' is not a match document.",
    )
  when (val gate = Settlement.decide(uid, parsed)) {
    is SettleDecision.NoOp -> return noOpResponse(gate.mode.name)
    is SettleDecision.Failed -> throw HttpsError(
      if (gate.reason == Settlement.Reason.NOT_MEMBER) "permission-denied" else "failed-precondition",
      gate.message,
    )
    SettleDecision.Proceed -> Unit
    else -> throw HttpsError("internal", "settleMatch: unreachable gate.")
  }

  val archives = db.readArchives(matchId).mapNotNull { (docId, archiveFields) ->
    Settlement.parseArchive(docId, archiveFields)?.let { docId to it }
      ?: throw HttpsError(
        "failed-precondition",
        "settleMatch: roundArchive/$docId is malformed.",
      )
  }.toMap()
  val withHistory = parsed.copy(archives = archives.values.toList())

  val decision = Settlement.recompute(withHistory)
  val recomputed = when (decision) {
    is SettleDecision.Agreed -> decision.recomputed
    is SettleDecision.Corrected -> decision.recomputed
    is SettleDecision.Failed -> throw HttpsError("failed-precondition", decision.message)
    else -> throw HttpsError("internal", "settleMatch: unreachable recompute.")
  }

  return db.transact { tx ->
    val existing = tx.readClaim(matchId)
    if (existing != null) {
      @Suppress("UNCHECKED_CAST")
      val recorded = existing["outcome"] as? Map<String, Any?>
        ?: throw HttpsError(
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

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through toPlain. */
class AdminSettlementDb : SettlementDb {
  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef =
    db.collection("matches").doc(matchId)

  override suspend fun readMatch(matchId: String): Map<String, Any?>? {
    val snap = matchRef(matchId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
    val snapshot = matchRef(matchId).collection("roundArchive").get().await()
    val out = LinkedHashMap<String, Map<String, Any?>>()
    for (doc in snapshot.docs) {
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(doc.data()) as? Map<String, Any?> ?: continue
      out[doc.id] = fields
    }
    return out
  }

  override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : SettlementDb.SettlementTx {
    override suspend fun readClaim(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override fun writeCorrection(
      matchId: String,
      scores: Map<String, Int>,
      winners: List<String>,
      version: Int,
    ) {
      tx.update(
        matchRef(matchId),
        toPlain(
          mapOf(
            "finalScores" to scores,
            "winnerIds" to winners,
            "version" to version,
          ),
        ),
      )
    }

    override fun writeClaim(matchId: String, record: Map<String, Any?>) {
      tx.set(matchRef(matchId).collection(CLAIM_PATH).doc(CLAIM_DOC), toPlain(record))
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
