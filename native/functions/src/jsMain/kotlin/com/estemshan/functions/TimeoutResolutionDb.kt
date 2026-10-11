package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.RANKS
import com.estemshan.engine.Suit
import com.estemshan.functions.TimeoutResolution.DecisionWrite
import com.estemshan.functions.TimeoutResolution.Pending
import com.estemshan.functions.TimeoutResolution.Reason
import com.estemshan.functions.TimeoutResolution.Resolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

/**
 * S61 Stage 1 — the decision-timer I/O + orchestration.
 *
 * The contract is deliberately simpler than settlement's: there is no
 * claim-check and no once-only record, because the decision itself is its own
 * idempotency token. Every write advances the document — the log gains an
 * entry, `bids/` gains a value, `version` ticks, and `updatedAt` moves forward
 * — so the deadline the NEXT caller derives is anchored past the decision this
 * caller made. A racer, a retry, or a trigger that fires a moment late re-reads
 * the fresh document and finds NOT_YET_EXPIRED: the transaction re-derives
 * everything from the committed doc, so no double decision is possible and no
 * separate guard document is needed.
 *
 * The one thing the transaction must NOT do is trust the caller's snapshot:
 * the whole decision is recomputed from the in-transaction read, the same way
 * submitBiddingAction re-validates inside its own transaction after a version
 * guard.
 *
 * Ports keep every Firestore touch behind interfaces so the orchestration
 * (detect → deadline → reconstruct → decide → write) is unit-testable with
 * fakes; only AdminTimeoutDb loads the SDK.
 */
interface TimeoutDb {
  suspend fun readMatch(matchId: String): Map<String, Any?>?

  /**
   * The round's dealt hands. A hand doc is written once at dealRound and
   * overwritten only by the next round's deal, so it is immutable within a
   * round — which is why this read is safe outside the transaction below.
   */
  suspend fun readHands(matchId: String, round: Int, seats: List<String>): Map<String, List<Card>>

  suspend fun <T> transact(block: suspend (TimeoutTx) -> T): T

  interface TimeoutTx {
    suspend fun readMatch(matchId: String): Map<String, Any?>?
    fun updateMatch(matchId: String, updates: Map<String, Any?>)
  }
}

/**
 * The wall clock the deadline is compared against. Admin reads give a
 * committed server timestamp, so the decision uses the server's own clock —
 * never a client's. Injectable so tests are deterministic.
 */
fun interface DecisionClock {
  fun nowMillis(): Long
}

private fun noWriteResponse(reason: String, message: String): Map<String, Any?> = mapOf(
  "ok" to true,
  "resolved" to false,
  "reason" to reason,
  "message" to message,
)

/**
 * The decided move is public information — it is the move itself, which the
 * document records for every seat. A hand is never included: not here, not in
 * the update, not anywhere a client can read it.
 */
private fun appliedResponse(pending: Pending, write: DecisionWrite, matchId: String): Map<String, Any?> =
  mapOf(
    "ok" to true,
    "resolved" to true,
    "matchId" to matchId,
    "seatId" to pending.seatId,
    "decided" to write.decided,
    "deadlinePhase" to pending.phase.name,
    "deadlineSeconds" to pending.durationSeconds,
  )

/**
 * Runs one timeout resolution: auth → read → membership → detect → deadline →
 * reconstruct → decide → transacted write. Throws HttpsError on a denial the
 * client should switch on; returns a plain map otherwise. NOT_YET_EXPIRED is
 * NOT an error — a late trigger is the normal outcome of the player deciding
 * just before expiry.
 */
suspend fun resolveTimeoutOnce(
  db: TimeoutDb,
  request: CallableRequest,
  clock: DecisionClock,
): Map<String, Any?> {
  val data = request.data as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "resolveDecisionTimeout: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  // Cheap pre-check OUTSIDE the transaction: an unexpired deadline costs one
  // read, not a transaction plus four hand reads.
  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "resolveDecisionTimeout: match '$matchId' was not found.")
  val parsed = Settlement.parseMatch(matchId, fields)
    ?: throw HttpsError(
      "failed-precondition",
      "resolveDecisionTimeout: match '$matchId' is not a match document.",
    )
  if (uid !in parsed.players) {
    throw HttpsError(
      "permission-denied",
      "resolveDecisionTimeout: you are not a player in this match.",
    )
  }

  val pending = TimeoutResolution.pendingDecision(fields)
    ?: return noWriteResponse(
      Reason.NOT_ACTIVE,
      "resolveDecisionTimeout: match '$matchId' is not waiting on a decision.",
    )
  if (!TimeoutResolution.isExpired(pending, clock.nowMillis())) {
    return noWriteResponse(
      Reason.NOT_YET_EXPIRED,
      "resolveDecisionTimeout: the decision for seat '${pending.seatId}' has not expired yet.",
    )
  }

  val hands = db.readHands(matchId, extractRound(fields), extractSeats(fields))

  return db.transact { tx ->
    // Everything is re-derived from the committed document, so a decision that
    // landed between the pre-check and this transaction re-anchors here and
    // resolves to a no-write instead of a second move.
    val fresh = tx.readMatch(matchId)
      ?: throw HttpsError("not-found", "resolveDecisionTimeout: match '$matchId' was not found.")
    val freshPending = TimeoutResolution.pendingDecision(fresh)
      ?: return@transact noWriteResponse(
        Reason.NOT_ACTIVE,
        "resolveDecisionTimeout: match '$matchId' is not waiting on a decision.",
      )
    if (!TimeoutResolution.isExpired(freshPending, clock.nowMillis())) {
      return@transact noWriteResponse(
        Reason.NOT_YET_EXPIRED,
        "resolveDecisionTimeout: the decision for seat '${freshPending.seatId}' " +
          "has not expired yet.",
      )
    }

    when (val outcome = TimeoutResolution.resolveExpired(fresh, hands, freshPending)) {
      is Resolution.NoWrite -> when (outcome.reason) {
        Reason.NOT_ACTIVE -> noWriteResponse(outcome.reason, outcome.message)
        else -> throw HttpsError("failed-precondition", outcome.message)
      }
      is Resolution.Applied -> {
        tx.updateMatch(matchId, outcome.write.updates)
        appliedResponse(freshPending, outcome.write, matchId)
      }
    }
  }
}

private fun extractRound(fields: Map<String, Any?>): Int =
  (fields["currentRound"] as? Number)?.toInt() ?: 1

private fun extractSeats(fields: Map<String, Any?>): List<String> =
  TimeoutResolution.parseOrder(fields) ?: emptyList()

/** Admin-SDK implementation. Reads normalize through dynamicToKotlin;
 *  writes normalize through toPlain. */
class AdminTimeoutDb : TimeoutDb {
  private val db = getFirestore()

  private fun matchRef(matchId: String): DocRef = db.collection("matches").doc(matchId)

  override suspend fun readMatch(matchId: String): Map<String, Any?>? {
    val snap = matchRef(matchId).get().await()
    if (!snap.exists) return null
    @Suppress("UNCHECKED_CAST")
    return dynamicToKotlin(snap.data()) as? Map<String, Any?>
  }

  /**
   * The round's dealt hands, read with the admin SDK. firestore.rules keeps
   * `hands/{seatId}` owner-read-only (`allow get: if ownsSeat()`), which is
   * exactly why the decision is computed here rather than on the acting
   * client: the admin read never exposes a hand to any player, and the card
   * this produces is the only thing written back.
   */
  override suspend fun readHands(
    matchId: String,
    round: Int,
    seats: List<String>,
  ): Map<String, List<Card>> {
    val out = LinkedHashMap<String, List<Card>>()
    for (seat in seats) {
      val snap = matchRef(matchId).collection("hands").doc(seat).get().await()
      if (!snap.exists) continue
      @Suppress("UNCHECKED_CAST")
      val fields = dynamicToKotlin(snap.data()) as? Map<String, Any?> ?: continue
      val handRound = (fields["round"] as? Number)?.toInt() ?: continue
      if (handRound != round) continue
      val cards = (fields["cards"] as? List<*>).orEmpty().mapNotNull { parseStoredCard(it) }
      out[seat] = cards
    }
    return out
  }

  private fun parseStoredCard(fields: Any?): Card? {
    val map = fields as? Map<*, *> ?: return null
    val suit = (map["suit"] as? String)?.let { runCatching { Suit.valueOf(it) }.getOrNull() }
      ?: return null
    val rankMap = map["rank"] as? Map<*, *> ?: return null
    val v = (rankMap["v"] as? Number)?.toInt() ?: return null
    val rank = RANKS.firstOrNull { it.v == v } ?: return null
    return Card(suit, rank)
  }

  override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T {
    val outcome = db.runTransaction { tx ->
      TxScope.promise { block(AdminTx(tx)) }
    }.await()
    @Suppress("UNCHECKED_CAST")
    return outcome as T
  }

  private inner class AdminTx(private val tx: Transaction) : TimeoutDb.TimeoutTx {
    override suspend fun readMatch(matchId: String): Map<String, Any?>? {
      val snap = tx.get(matchRef(matchId)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
      tx.update(matchRef(matchId), toPlain(updates))
    }
  }

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
