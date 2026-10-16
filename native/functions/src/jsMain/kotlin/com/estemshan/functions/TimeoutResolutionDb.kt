package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.RANKS
import com.estemshan.engine.Suit
import com.estemshan.functions.TimeoutResolution.DecisionWrite
import com.estemshan.functions.TimeoutResolution.Pending
import com.estemshan.functions.TimeoutResolution.Reason
import com.estemshan.functions.TimeoutResolution.Resolution
import com.estemshan.functions.TimeoutResolution.TimeoutCounter
import com.estemshan.functions.TimeoutResolution.COUNT_FIELD
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

    /**
     * S61 Stage 2: the acting seat's stored timeout count. Read INSIDE the
     * transaction so the increment and the decision write commit atomically
     * — the count the removal decision is made against is the count under
     * the same lock as the match write.
     *
     * Returns null when the `timeoutCounts/{uid}` doc does not exist — a
     * seat's first timeout — which the caller must distinguish from a
     * present-but-invalid `count` field (the former is trusted as 0, the
     * latter fails closed and writes nothing).
     */
    suspend fun readTimeoutCount(matchId: String, uid: String): Any?

    /** S61 Stage 2: whether the `timeoutCounts/{uid}` doc exists. */
    suspend fun timeoutCountExists(matchId: String, uid: String): Boolean

    /** S61 Stage 2: writes `timeoutCounts/{uid}`'s single `count` field. */
    fun writeTimeoutCount(matchId: String, uid: String, count: Int)

    /**
     * S73 (RD19): the seat's private `matches/{id}/inactivity/{uid}` document —
     * the accumulation, its window anchor and the seat-action cursor. Read
     * INSIDE the transaction, for the same reason the count is: the clock the
     * takeover decision is made against must be under the same lock as the
     * write it authorises.
     *
     * null means the document does not exist: "this seat's inactivity has never
     * been recorded", which [InactivityClock.fromStored] trusts as a fresh
     * clock, as distinct from a present-but-invalid field, which fails closed.
     *
     * Defaulted to "no document" so the Stage-1/Stage-2 suites' fakes — which
     * model only the counter — keep pinning exactly the behaviour they pin
     * today: a fresh clock that never reaches the threshold, and a discarded
     * write. [AdminTimeoutDb] replaces both with the real thing.
     */
    suspend fun readInactivity(matchId: String, uid: String): Map<String, Any?>? = null

    /**
     * S73 (RD19): replaces `inactivity/{uid}` with [fields] — all three of its
     * fields at once, never a merge, because those three are the complete state
     * this mechanism owns and a merge could leave a stale cursor behind.
     *
     * A separate document from `timeoutCounts/{uid}` on purpose: RD20's
     * separation means the two mechanisms share no state, no doc and no field,
     * so neither one's replacement write can ever clobber the other's.
     */
    fun writeInactivity(matchId: String, uid: String, fields: Map<String, Any?>) {
      // Default: the fake's transaction does not model inactivity.
    }
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

/**
 * A no-write outcome. [retryAfterMillis] is present ONLY on NOT_YET_EXPIRED —
 * the server-authoritative time until the deadline, which the client re-arms
 * its alarm on instead of its own estimate (`updatedAt` is not on MatchDoc,
 * so the client cannot derive the anchor). Never carries the timeout count.
 */
private fun noWriteResponse(
  reason: String,
  message: String,
  retryAfterMillis: Long? = null,
): Map<String, Any?> = buildMap {
  put("ok", true)
  put("resolved", false)
  put("reason", reason)
  put("message", message)
  if (retryAfterMillis != null) put("retryAfterMillis", retryAfterMillis)
}

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
  // The runtime delivers request.data as a PLAIN JS OBJECT, not a Kotlin map —
  // `as? Map` returns null on one (JsBridging's own documented trap), which
  // silently turned every real call into "matchId is required". Convert at the
  // boundary, the same way every Admin read does.
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "resolveDecisionTimeout: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  // Cheap pre-check OUTSIDE the transaction: an unexpired deadline costs one
  // read, not a transaction plus four hand reads.
  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "resolveDecisionTimeout: match '$matchId' was not found.")
  // A live match gate, NOT Settlement.parseMatch: that one requires
  // `completedRound`, which only endMatch writes, so every mid-match document
  // would fail it. See TimeoutResolution.parsePlayers.
  val players = TimeoutResolution.parsePlayers(fields)
    ?: throw HttpsError(
      "failed-precondition",
      "resolveDecisionTimeout: match '$matchId' is not a match document.",
    )
  if (uid !in players) {
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
      retryAfterMillis = TimeoutResolution.millisUntilDeadline(pending, clock.nowMillis()),
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
        retryAfterMillis = TimeoutResolution.millisUntilDeadline(freshPending, clock.nowMillis()),
      )
    }

    when (val outcome = TimeoutResolution.resolveExpired(fresh, hands, freshPending)) {
      is Resolution.NoWrite -> when (outcome.reason) {
        Reason.NOT_ACTIVE -> noWriteResponse(outcome.reason, outcome.message)
        else -> throw HttpsError("failed-precondition", outcome.message)
      }
      is Resolution.Applied -> {
        // S61 Stage 2: the counter and the removal ride the SAME transaction
        // as the decision write, so all three commit atomically or none does.
        // The acting seat's uid comes from the RETAINED seats map — a seat
        // already removed by Vote Kick or by a prior timeout still resolves
        // here (it must, or the table would stall), but a seat already under
        // bot control increments nothing: the counter counts HUMAN timeouts,
        // and that seat is no longer human-driven.
        val seatUid = TimeoutResolution.seatUid(fresh, freshPending.seatId)
        val alreadyBotControlled =
          freshPending.seatId in TimeoutResolution.botControlledSeats(fresh)
        // S73 (RD19): a BORROWED seat's expiries are this mechanism's own doing,
        // not fresh human timeouts, so the counter suppresses for them too.
        // Without this, a 50 s pause would march the player through RD20's
        // removal at the fifteenth 2 s window — under thirty seconds of table
        // time — which is the opposite of "INACTIVE never increments the
        // counter" (§0.5-C). The expiry that EARNED the takeover still counted:
        // it was a genuine human timeout, and the marker written below is not
        // yet in `fresh`.
        val alreadyBorrowed =
          freshPending.seatId in TimeoutResolution.inactiveTakeoverSeats(fresh)
        val humanHeldSeat = seatUid != null && !alreadyBotControlled && !alreadyBorrowed

        // S73 (RD19): the clock is read under the same lock as the decision that
        // advances it, and only for a seat a human still holds. Every read in
        // this transaction happens before every write, as Firestore requires.
        val storedInactivity =
          if (humanHeldSeat) tx.readInactivity(matchId, seatUid!!) else null

        val counter = if (humanHeldSeat) {
          TimeoutCounter.fromStored(
            tx.readTimeoutCount(matchId, seatUid!!),
            docExists = tx.timeoutCountExists(matchId, seatUid),
          )
        } else {
          null
        }

        val inactivity = if (humanHeldSeat) {
          TimeoutResolution.inactivityAfterExpiredWindow(
            fields = fresh,
            pending = freshPending,
            seatUid = seatUid,
            storedDoc = storedInactivity,
            nowMillis = clock.nowMillis(),
            write = outcome.write,
          )
        } else {
          null
        }

        if (counter != null) {
          tx.writeTimeoutCount(matchId, seatUid!!, counter.nextCount)
        }

        val removal = counter != null && counter.shouldRemove
        val takeover = inactivity?.takeover
        val updates = if (removal || takeover != null) {
          val merged = LinkedHashMap(outcome.write.updates)
          if (removal) {
            // The 15th: the decision (still the Stage 1 MEDIUM policy) lands in
            // the SAME write that records the removal — the removal takes
            // effect WITH the deciding move, never before it. Subsequent
            // decisions for this seat are still MEDIUM: RD20 pins the timeout
            // engine to MEDIUM regardless of the match's configured difficulty.
            // The configured profile is the explicit-leave path's (RD20), which
            // this resolver never runs.
            merged["timeoutRemovedUids"] =
              TimeoutResolution.removalWrite(fresh, seatUid!!)
          }
          takeover?.let {
            // RD19: the takeover rides the SAME write as the decision that
            // earned it, so no client can ever observe the accumulation without
            // the seat being borrowed — and no separate write can be lost
            // between the two. [InactiveTakeover.withTakeover] is surgical: the
            // other seats' entries are carried verbatim, so one resolver can
            // never discard another seat's takeover.
            merged[InactiveTakeover.TAKEOVER_FIELD] = InactiveTakeover.withTakeover(
              fresh[InactiveTakeover.TAKEOVER_FIELD],
              freshPending.seatId,
              it,
            )
          }
          merged
        } else {
          outcome.write.updates
        }

        tx.updateMatch(matchId, updates)
        if (inactivity != null && seatUid != null) {
          // The clock and the marker commit together with the decision, and the
          // clock it persists is ZEROED whenever the marker is written — an
          // accumulation at or above the threshold is one `fromStored` rejects,
          // so leaving it behind would kill the seat's clock permanently.
          tx.writeInactivity(matchId, seatUid, inactivity.docFields)
        }
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
  // Before getFirestore() resolves the default app — see ensureDefaultAdminApp.
  init { ensureDefaultAdminApp() }

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

    override suspend fun readTimeoutCount(matchId: String, uid: String): Any? {
      val snap = tx.get(countRef(matchId, uid)).await()
      if (!snap.exists) return null
      return dynamicToKotlin(snap.data())?.let { it as? Map<*, *> }?.get(COUNT_FIELD)
    }

    override suspend fun timeoutCountExists(matchId: String, uid: String): Boolean =
      tx.get(countRef(matchId, uid)).await().exists

    override fun writeTimeoutCount(matchId: String, uid: String, count: Int) {
      tx.set(countRef(matchId, uid), toPlain(mapOf(COUNT_FIELD to count)))
    }

    /**
     * S73 (RD19): the whole private document, normalized exactly like the match
     * read, so a JS object handed to [InactivityClock.fromStored]'s validators
     * behaves the same as one built in a test. Missing doc ⇒ null ⇒ "never
     * recorded", which is the trusted first-window state.
     */
    override suspend fun readInactivity(matchId: String, uid: String): Map<String, Any?>? {
      val snap = tx.get(inactivityRef(matchId, uid)).await()
      if (!snap.exists) return null
      @Suppress("UNCHECKED_CAST")
      return dynamicToKotlin(snap.data()) as? Map<String, Any?>
    }

    /**
     * Replacement write, the counter's own idiom: the three fields ARE this
     * mechanism's complete state for the player, so merging could only leave a
     * stale cursor or an accumulation the takeover was supposed to zero.
     */
    override fun writeInactivity(matchId: String, uid: String, fields: Map<String, Any?>) {
      tx.set(inactivityRef(matchId, uid), toPlain(fields))
    }
  }

  /** `matches/{matchId}/timeoutCounts/{uid}` — owner-read-only by the rules. */
  private fun countRef(matchId: String, uid: String): DocRef =
    matchRef(matchId).collection("timeoutCounts").doc(uid)

  /**
   * `matches/{matchId}/inactivity/{uid}` — RD19's private accumulator, keyed by
   * PLAYER like the counter and for the same reason: how long someone has been
   * ignoring the table is not their opponents' business. It has NO rules block
   * at all, which is what makes it admin-only: an admin-written field no
   * validator names is unwritable by every client (the update dispatch falls
   * through to `false`) while the match's own whole-document `allow get` still
   * lets every seat read the marker on the match. `firestore.rules` is
   * therefore byte-identical for S73 — the same zero-rules path
   * `timeoutRemovedUids` proved.
   */
  private fun inactivityRef(matchId: String, uid: String): DocRef =
    matchRef(matchId).collection(InactiveTakeover.INACTIVITY_COLLECTION).doc(uid)

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
