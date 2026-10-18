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

  /**
   * S62 RP audit ledger: best-effort append of one audit record. Abstract
   * so every port wires the ledger consciously; the Admin implementation
   * writes create-if-absent, once per [SettlementLedger.appendTargets] entry,
   * keyed by [SettlementLedger.ledgerDocId], and settleOnce calls this inside
   * a catch - an append failure is logged and never changes the settlement
   * outcome.
   */
  suspend fun appendLedger(record: Map<String, Any?>)

  /**
   * S62: the bounded, most-recent-first window of CORRECTIONS the repeat
   * detector reads ([SettlementLedger.DETECT_WINDOW] documents, drawn from
   * [SettlementLedger.COLLECTION_CORRECTIONS]). Called only after a correction
   * applied; the caller catches failures. Reading corrections alone, instead
   * of the whole ledger, is what stops R25 going blind as settlement volume
   * grows - and it is a write-time materialization precisely so this read
   * needs no composite index. See [AdminSettlementDb.readCorrectionWindow].
   */
  suspend fun readCorrectionWindow(): List<Map<String, Any?>>

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
  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one, silently rejecting every real call with
  // "matchId is required". Convert at the boundary, as below.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
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
    is SettleDecision.NoOp -> {
      // S62 decision: ROOM/UNRANKED no-ops are console-only - no ledger
      // document (not a settlement outcome; the ledger stays free of
      // routine traffic), cheap structured line for retry forensics.
      console.log(SettlementLedger.noOpLine(matchId, gate.mode.name))
      return noOpResponse(gate.mode.name)
    }
    is SettleDecision.Failed -> {
      // S62 decision: a denied attempt is console-only (no write - an
      // authenticated prober must not be able to amplify documents).
      console.log(SettlementLedger.deniedLine(matchId, uid, gate.reason))
      throw HttpsError(
        if (gate.reason == Settlement.Reason.NOT_MEMBER) "permission-denied" else "failed-precondition",
        gate.message,
      )
    }
    SettleDecision.Proceed -> Unit
    else -> throw HttpsError("internal", "settleMatch: unreachable gate.")
  }

  val parsedArchives = LinkedHashMap<String, Settlement.ArchivedRound>()
  for ((docId, archiveFields) in db.readArchives(matchId)) {
    val archive = Settlement.parseArchive(docId, archiveFields)
    if (archive == null) {
      val message = "settleMatch: roundArchive/$docId is malformed."
      // S62: a malformed archive is corrupt history - the same FAILED
      // ledger record and console line as a closed recompute.
      SettlementLedger.reportFailure(db, parsed, uid, Settlement.Reason.HISTORY_CORRUPT, message)
      throw HttpsError("failed-precondition", message)
    }
    parsedArchives[docId] = archive
  }
  val withHistory = parsed.copy(archives = parsedArchives.values.toList())

  val decision = Settlement.recompute(withHistory)
  val recomputed = when (decision) {
    is SettleDecision.Agreed -> decision.recomputed
    is SettleDecision.Corrected -> decision.recomputed
    is SettleDecision.Failed -> {
      // S62: recompute failures are ledger-recorded (FAILED) before the
      // denial is thrown; reporting is best-effort and never replaces it.
      SettlementLedger.reportFailure(db, withHistory, uid, decision.reason, decision.message)
      throw HttpsError("failed-precondition", decision.message)
    }
    else -> throw HttpsError("internal", "settleMatch: unreachable recompute.")
  }

  val settled = db.transact { tx ->
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

  // S62: only the invocation that applied the settlement appends to the
  // ledger - the replay path (replayed=true) is a read-only no-op for it,
  // which makes the ledger append exactly-once across retries.
  if (settled["settled"] == true && settled["replayed"] != true) {
    SettlementLedger.recordSettlement(db, parsed, recomputed, uid)
  }
  return settled
}

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through toPlain. */
class AdminSettlementDb : SettlementDb {
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

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

  override suspend fun appendLedger(record: Map<String, Any?>) {
    // S62: create-if-absent in its own Admin transaction, keyed by
    // SettlementLedger.ledgerDocId - the append is idempotent even if the
    // same event is ever delivered twice, and a failure here cannot touch
    // the settlement (that transaction has already committed).
    // appendTargets decides which collections the row lands in, and they all
    // commit together: the corrections window can never hold a row the ledger
    // lacks, or miss one it should mirror.
    val docId = SettlementLedger.ledgerDocId(record) ?: return
    val refs = SettlementLedger.appendTargets(record).map { collection ->
      db.collection(collection).doc(docId)
    }
    db.runTransaction { tx ->
      TxScope.promise {
        // Firestore requires EVERY read in a transaction to precede EVERY
        // write (the SDK throws on a get after a set), so the create-if-absent
        // test is done for all targets first and only then written.
        val existed = refs.map { tx.get(it).await().exists }
        val plain = toPlain(record)
        refs.forEachIndexed { index, ref ->
          if (!existed[index]) tx.set(ref, plain)
        }
      }
    }.await()
  }

  override suspend fun readCorrectionWindow(): List<Map<String, Any?>> {
    // S62: newest-first over ONE field of the corrections collection, so the
    // window is genuinely "the last DETECT_WINDOW corrections" and includes
    // the record the correction just wrote. An un-ordered equality query on
    // the ledger instead would return index order, not recency, and would
    // freeze on the oldest documents once the collection grows - silently
    // blinding the detector. Mirror the correction at write time and the read
    // stays on the automatic single-field index: no composite index, no
    // indexes artifact, no deployment.
    val snapshot = db.collection(SettlementLedger.COLLECTION_CORRECTIONS)
      .orderBy("appendedAt", "desc")
      .limit(SettlementLedger.DETECT_WINDOW)
      .get().await()
    return snapshot.docs.mapNotNull { doc ->
      @Suppress("UNCHECKED_CAST")
      dynamicToKotlin(doc.data()) as? Map<String, Any?>
    }
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
