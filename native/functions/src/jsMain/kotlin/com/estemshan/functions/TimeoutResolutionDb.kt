package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.DisconnectVoteState
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
 *
 * Pointer note (S73 Stage 2, C4): [reclaimOnce] joins [resolveTimeoutOnce] here
 * because it needs the same seam and the same clock — the `TimeoutTx` interface
 * itself did not change for it, so the Stage-1/2 suites' fakes are untouched.
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

    /**
     * S7 (A8.2 / Q36): removes `matches/{id}/disconnectVotes/{uid}` — the
     * 7th takeover's ballot cancellation, in the SAME transaction as the
     * removal it belongs to, so no stale ballot can outlive the seat it was
     * about. The same path `DisconnectVoteDb.Tx.deleteBallot` proved; the
     * default no-op keeps every existing fake (which models no ballots)
     * compiling and pinning exactly what it pins today.
     */
    suspend fun deleteBallot(matchId: String, uid: String) {
      // Default: the fake's transaction does not model ballots.
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
    ?: run {
      requireReadableRoundMultiplier(fields, matchId)
      return noWriteResponse(
        Reason.NOT_ACTIVE,
        "resolveDecisionTimeout: match '$matchId' is not waiting on a decision.",
      )
    }
  // S74 (DEC-DISC-01): a granted disconnect-vote WAIT pauses the table — no
  // decision resolves while the pause is live, and the caller re-arms on the
  // pause's own remaining millis. The gate sits BEFORE the expiry check on
  // purpose: an already-expired decision must not resolve either. The
  // replacement write that ends the pause re-stamps `updatedAt`, which is the
  // decision machine's only anchor, so the pending decision gets a complete
  // window when play resumes — the exact logic the S73 return documents.
  //
  // S7 / A7.3: while the pause is live the call now enters the TRANSACTION
  // instead of answering here, because the 7th-takeover terminal condition
  // must be evaluated BEFORE the pause gate — read-only, from the committed
  // document plus the private inactivity document read under the same lock —
  // and only then does the pause answer exactly as it always has. The cheap
  // pre-check's answer is unchanged for every document where the condition is
  // false, which is every document the runtime can produce today (see
  // [TimeoutResolution.seventhTakeoverOverdue]).
  if (TimeoutResolution.disconnectPauseRemaining(fields, clock.nowMillis()) != null) {
    val paused = db.transact { tx ->
      evaluatePausedTerminal(tx, matchId, clock)
    }
    // Null = the pause ended between the pre-check and the transaction: fall
    // through to the ordinary flow, which re-derives everything from its own
    // committed read.
    if (paused != null) return paused
  }
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
      ?: run {
        requireReadableRoundMultiplier(fresh, matchId)
        return@transact noWriteResponse(
          Reason.NOT_ACTIVE,
          "resolveDecisionTimeout: match '$matchId' is not waiting on a decision.",
        )
      }
    // S7/A7.3: the same terminal evaluation before the IN-TRANSACTION pause
    // gate — a pause that went live between the pre-check and this
    // transaction gets the same read-only look at the WAIT seat's 7th-takeover
    // condition first; only an undue condition falls through to the pause
    // answer below.
    if (TimeoutResolution.disconnectPauseRemaining(fresh, clock.nowMillis()) != null) {
      evaluatePausedTerminal(tx, matchId, clock, committed = fresh)?.let { return@transact it }
      TimeoutResolution.disconnectPauseRemaining(fresh, clock.nowMillis())?.let { remaining ->
        return@transact noWriteResponse(
          Reason.NOT_YET_EXPIRED,
          "resolveDecisionTimeout: the match is paused for a disconnect vote.",
          retryAfterMillis = remaining,
        )
      }
    }
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
        // S74 (DEC-DISC-01): a disconnect-held seat is bot-played from the
        // instant of detection, so its expiries are the mechanism's own doing
        // too — the counter counts HUMAN timeouts and suppresses for this
        // borrowing exactly as it does for RD19's. The detection that opened
        // the flow is not written into `fresh` yet when the seat keeps
        // resolving before its hold lands, so the window that expires just
        // before the hold still counts: it was a genuine human timeout.
        val alreadyDisconnectHeld =
          freshPending.seatId in TimeoutResolution.disconnectHeldSeats(fresh)
        // The COUNTER's human gate keeps every suppression exactly as S74 drew
        // it: a disconnect-held seat's expiries are the mechanism's own doing
        // and must never march RD20's removal.
        val humanHeldSeat =
          seatUid != null && !alreadyBotControlled && !alreadyBorrowed && !alreadyDisconnectHeld

        // S7 / A7.3: the CLOCK's gate is deliberately wider — a disconnect-held
        // seat keeps ACCRUING inactivity while the ballot about it is open,
        // because the hold is a borrowing and RD19's clock measures the human's
        // absence. This is the one semantic change of the story; the inactivity
        // advance re-checks the permanent union, the borrowed set and the
        // REPLACED holds itself, so a seat another mechanism drives never
        // accrues here.
        val clockHeldSeat =
          seatUid != null && !alreadyBotControlled && !alreadyBorrowed

        // S73 (RD19): the clock is read under the same lock as the decision that
        // advances it, and only for a seat a human still holds. Every read in
        // this transaction happens before every write, as Firestore requires.
        val storedInactivity =
          if (clockHeldSeat) tx.readInactivity(matchId, seatUid!!) else null

        val counter = if (humanHeldSeat) {
          TimeoutCounter.fromStored(
            tx.readTimeoutCount(matchId, seatUid!!),
            docExists = tx.timeoutCountExists(matchId, seatUid),
          )
        } else {
          null
        }

        val inactivity = if (clockHeldSeat) {
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

        val takeover = inactivity?.takeover
        // S7/A7.3: the takeover this window earned is the TERMINAL one when
        // the persisted count reaches the threshold. A takeover implies a
        // stored document (the clock cannot reach 50 s in a single window),
        // so the count field is present; the advance coerces it at 7, and
        // after the removal the seat is bot-held so the branch never re-runs.
        val terminalTakeover = takeover != null && seatUid != null &&
          ((inactivity.docFields[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD] as? Int) ?: 0) >=
          TimeoutResolution.TAKEOVER_REMOVAL_THRESHOLD
        // S7/A7.3 precedence (owner ruling 2026-10-27, Q-S7-4): when ONE
        // expiry is BOTH the 15th timeout and the 7th takeover, the takeover
        // owns the removal. Exactly one reason and exactly one charge is
        // recorded — the two charge maps can never name the same uid, so the
        // owner's "no 4 Koz" invariant holds by construction, not by a
        // settlement-side dedup. The counter still advances (the 15th timeout
        // DID happen); only its removal and charge are suppressed, because
        // the terminal takeover already cancels the ballot and clears the
        // marker the timeout path never touched.
        val removal = counter != null && counter.shouldRemove && !terminalTakeover
        var ballotDeletes: List<String> = emptyList()
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
            // S5 (A8.3 / Q37 + RD31 / Q14d(1), closing IB-22): the removal's
            // own Koz charge — ROOM 1, RANKED/UNRANKED 2, decided by the
            // engine table — rides the SAME transaction as the removal, so it
            // can never land without the removal, and never twice: once this
            // commits, the uid is in `timeoutRemovedUids`, so botControlledSeats
            // makes every later resolution of the seat skip the counter and
            // this branch entirely (the removal's own idempotency proof).
            // Recorded here, applied at settlement by the settlement lane —
            // nothing downstream is consumed in this lane.
            TimeoutResolution.removalChargeKoz(TimeoutResolution.storedMatchMode(fresh))?.let { koz ->
              merged[TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD] =
                TimeoutResolution.removalChargeWrite(fresh, seatUid, koz)
            }
          }
          if (terminalTakeover && seatUid != null) {
            // S7 / A7.3 + A8.2 (Q36): the 7th takeover removes the seat in the
            // SAME write as the decision that earned it — no client can
            // observe the seat borrowed-then-removed — and EXACTLY ONCE: once
            // this commits, the uid is in `takeoverRemovedUids`, so
            // botControlledSeats makes every later resolution of the seat skip
            // the clock branch entirely (the removal's own idempotency proof,
            // the same shape S5's charge rode). The ballot it leaves behind is
            // cancelled outright — its marker entry deleted, its ballot
            // documents removed after this update — never completed by an
            // expiry verdict and never re-opened.
            ballotDeletes = mergeSeventhTakeoverRemoval(
              merged, fresh, freshPending.seatId, seatUid,
            )
          } else {
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
          // so leaving it behind would kill the seat's clock permanently. On
          // the terminal branch the same write carries count = 7, the value
          // the seat keeps forever: it is bot-held from this commit on.
          tx.writeInactivity(matchId, seatUid, inactivity.docFields)
        }
        for (voter in ballotDeletes) {
          // A8.2: no stale ballot can complete later — the seat's own ballot
          // document and every electorate ballot for it leave with the
          // removal. Write-side only; other seats' ballots are untouched.
          tx.deleteBallot(matchId, voter)
        }
        appliedResponse(freshPending, outcome.write, matchId)
      }
    }
  }
}

// ── S7 (A7.3 / A8.2): the 7th takeover's terminal write and its helpers ───

/**
 * Merges the 7th takeover's terminal removal into [updates], in place, and
 * returns the voter uids (the removed seat first, then its electorate) whose
 * ballot documents must be deleted in the SAME transaction.
 *
 * Everything here derives from the committed [fields] the caller read under
 * the same lock, so a replay re-derives idempotently: the list is
 * `(stored + uid).distinct()` (append-only, deduplicated), the charge map is
 * SET by uid (the amount is a pure function of the create-only mode, so a
 * same-uid rewrite is value-identical, never a second charge), and the marker
 * entries are removed with the surgical map builders that carry every OTHER
 * seat's state verbatim — a 7th takeover of seat A cancels A's ballot and A's
 * markers and nobody else's (the S7 row of T-16).
 *
 * `version`/`updatedAt`: the caller's write carries them on the expiry path
 * (the decision write's own fields); the pause path adds them itself.
 */
private fun mergeSeventhTakeoverRemoval(
  updates: LinkedHashMap<String, Any?>,
  fields: Map<String, Any?>,
  seatId: String,
  seatUid: String,
): List<String> {
  updates[TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD] =
    (TimeoutResolution.takeoverRemovedUids(fields) + seatUid).toList()
  TimeoutResolution.takeoverRemovalChargeKoz(TimeoutResolution.storedMatchMode(fields))?.let { koz ->
    updates[TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD] =
      TimeoutResolution.takeoverRemovalChargeWrite(fields, seatUid, koz)
  }
  // A8.2: the ballot ends CANCELLED — its marker entry is deleted outright
  // (every phase, not only VOTE), so no expiry verdict can complete it and no
  // restore can re-open it. withoutSeat carries the other seats' entries
  // verbatim, so seat B's live ballot or WAIT is untouched.
  updates[DisconnectVoteState.VOTE_FIELD] =
    DisconnectVoteState.withoutSeat(fields[DisconnectVoteState.VOTE_FIELD], seatId)
  // The seat is REMOVED, not borrowed: its inactiveTakeovers entry leaves with
  // the same write, or a later reclaim would see a borrowed seat that no
  // longer exists. Other seats' takeover entries are carried verbatim.
  updates[InactiveTakeover.TAKEOVER_FIELD] =
    InactiveTakeover.withoutTakeover(fields[InactiveTakeover.TAKEOVER_FIELD], seatId)
  return listOf(seatUid) + DisconnectVoteFlow.electorateUids(fields, seatId)
}

/**
 * The private inactivity document as the terminal takeover leaves it: the
 * clock zeroed (there is no seat left to accrue for), the stored cursor
 * carried verbatim (no decision was projected on the pause path; the value is
 * the mechanism's own baseline and erasing it would corrupt the next
 * comparison were the seat ever re-seated), and the count at
 * [TimeoutResolution.TAKEOVER_REMOVAL_THRESHOLD] — the value the seat keeps
 * forever, because the seat is bot-held from the removal's commit on.
 */
private fun terminalInactivityDoc(storedDoc: Map<String, Any?>?): Map<String, Any?> {
  val out = LinkedHashMap<String, Any?>(InactivityClock().storedFields())
  storedDoc?.get(InactiveTakeover.CURSOR_FIELD)?.let { cursor ->
    out[InactiveTakeover.CURSOR_FIELD] = cursor
  }
  out[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD] = TimeoutResolution.TAKEOVER_REMOVAL_THRESHOLD
  return out
}

/** The pause-path removal's answer. No decision was resolved — the pause held
 *  for every other purpose — so `resolved` is false and the removal is named
 *  by its own vocabulary. */
private fun seventhTakeoverRemovalResponse(matchId: String, seatId: String, seatUid: String): Map<String, Any?> =
  mapOf(
    "ok" to true,
    "resolved" to false,
    "removed" to true,
    "reason" to "SEVENTH_TAKEOVER",
    "matchId" to matchId,
    "seatId" to seatId,
    "uid" to seatUid,
  )

/**
 * S7 / A7.3 — the read-only-before-it-decides evaluation that runs BEFORE the
 * S74 pause gate. Asked about the pause's OWN seat (the WAIT entry's), from
 * the committed document plus the private inactivity document read in the
 * same transaction.
 *
 * Returns the response map when the terminal condition holds (the removal
 * write and the ballot deletes commit in the caller's transaction); the
 * pause's ordinary NOT_YET_EXPIRED answer when it does not and the pause is
 * still live; null when the pause ended between the caller's pre-check and
 * this read, so the ordinary flow can re-derive everything from a fresh
 * committed document.
 *
 * Reachability honesty (see [TimeoutResolution.seventhTakeoverOverdue]): on a
 * document the runtime can produce, the stored accumulation is always BELOW
 * the 50 s threshold — the expiry that crosses it fires the takeover in the
 * same write — and no persisted document keeps an open window. The predicate
 * therefore holds only for a stored count of 6 against a clock that a runtime
 * write never leaves behind; implementing it is A7.3's demand, and making the
 * condition live during a pause is E4.3's decision-timer re-architecture,
 * explicitly out of scope for S7.
 */
private suspend fun evaluatePausedTerminal(
  tx: TimeoutDb.TimeoutTx,
  matchId: String,
  clock: DecisionClock,
  committed: Map<String, Any?>? = null,
): Map<String, Any?>? {
  val fresh = committed ?: tx.readMatch(matchId)
    ?: throw HttpsError("not-found", "resolveDecisionTimeout: match '$matchId' was not found.")
  val waitSeat = TimeoutResolution.disconnectWaitSeat(fresh)
  if (waitSeat != null) {
    val waitUid = TimeoutResolution.seatUid(fresh, waitSeat)
    val stored = waitUid?.let { tx.readInactivity(matchId, it) }
    if (waitUid != null &&
      TimeoutResolution.seventhTakeoverOverdue(fresh, waitSeat, stored, clock.nowMillis())
    ) {
      val updates = LinkedHashMap<String, Any?>()
      val deletes = mergeSeventhTakeoverRemoval(updates, fresh, waitSeat, waitUid)
      updates["version"] = ((fresh["version"] as? Number)?.toInt() ?: 0) + 1
      updates["updatedAt"] = FieldValue.serverTimestamp()
      tx.updateMatch(matchId, updates)
      tx.writeInactivity(matchId, waitUid, terminalInactivityDoc(stored))
      for (voter in deletes) {
        tx.deleteBallot(matchId, voter)
      }
      return seventhTakeoverRemovalResponse(matchId, waitSeat, waitUid)
    }
  }
  TimeoutResolution.disconnectPauseRemaining(fresh, clock.nowMillis())?.let { remaining ->
    return noWriteResponse(
      Reason.NOT_YET_EXPIRED,
      "resolveDecisionTimeout: the match is paused for a disconnect vote.",
      retryAfterMillis = remaining,
    )
  }
  return null
}

private fun extractRound(fields: Map<String, Any?>): Int =
  (fields["currentRound"] as? Number)?.toInt() ?: 1

private fun extractSeats(fields: Map<String, Any?>): List<String> =
  TimeoutResolution.parseOrder(fields) ?: emptyList()

/**
 * O-19 (Option B) — a document whose persisted `currentMultiplier` cannot be
 * read has unreplayable history, and an unreplayable history is a CORRUPT
 * match, not an idle table. [TimeoutResolution.pendingDecision] necessarily
 * returns null for it (the replay fails closed instead of resetting the round
 * to ×1), so both detection sites name the cause here rather than answering
 * NOT_ACTIVE and leaving the decision machine silent on a broken document.
 * Unreachable for a document the rules can produce: those pin 1..8 and pin
 * Classic to 1.
 */
private fun requireReadableRoundMultiplier(fields: Map<String, Any?>, matchId: String) {
  if (TimeoutResolution.storedRoundMultiplier(fields) == null) {
    throw HttpsError(
      "failed-precondition",
      "resolveDecisionTimeout: match '$matchId' holds no readable currentMultiplier.",
    )
  }
}

/**
 * S73 Stage 2, C4 — a reclaim outcome. `reclaimed`, not `resolved`: the two
 * callables answer different questions and a client must never conflate a
 * no-write that means "the bot still owes a trick" with one that means "the
 * deadline has not passed". [retryAfterMillis] is present ONLY on
 * TRICK_NOT_FINISHED, and it is the only timing fact this response gives away —
 * never the accumulation, never the cursor, never the timeout count, which live
 * in subcollections the other three players cannot read and which no seat has
 * any business seeing.
 */
private fun reclaimNoWrite(
  reason: String,
  message: String,
  retryAfterMillis: Long?,
): Map<String, Any?> = buildMap {
  put("ok", true)
  put("reclaimed", false)
  put("reason", reason)
  put("message", message)
  if (retryAfterMillis != null) put("retryAfterMillis", retryAfterMillis)
}

/** The seat is back. It names the seat the DOCUMENT gave the caller, never an echo. */
private fun reclaimedResponse(matchId: String, seatId: String): Map<String, Any?> = mapOf(
  "ok" to true,
  "reclaimed" to true,
  "matchId" to matchId,
  "seatId" to seatId,
)

/**
 * Runs one reclaim: auth → read → membership → transacted re-derive → write.
 *
 * The boundary discipline is the resolver's, unchanged, because both are entered
 * by a client whose clock is not the server's: the payload is converted with
 * [dynamicToKotlin] (the runtime hands a PLAIN JS OBJECT, so `as? Map` on it
 * silently yields null — the trap [resolveTimeoutOnce] documents and
 * CallablePayloadBridgingTest pins), identity comes from
 * [CallableAuth.requireUid] and never from the body, membership is gated on the
 * LIVE document through [TimeoutResolution.parsePlayers] rather than
 * `Settlement.parseMatch` (which requires the `completedRound` only `endMatch`
 * ever writes), and everything is re-derived from the in-transaction read so a
 * takeover or a trick that resolved a moment ago is seen rather than assumed.
 *
 * **Both writes ride one transaction**: the marker loses this seat's entry, the
 * private document is replaced by the zeroed clock and its new baseline cursor,
 * `version` ticks and `updatedAt` is re-stamped — so no client can observe a
 * seat whose chip says INACTIVE while its clock says otherwise, and no delivery
 * can lose half of the return. A reclaim reads nothing it writes except ONE
 * document: the counter doc is not touched (RD20 — a return is not a timeout)
 * and the stored clock is not consulted for its validity (it is being
 * replaced) — but since S7 the stored inactivity document IS read under the
 * same lock, because the takeover count it carries must survive the return
 * (A7.3: the 7th fires on the count's history, never reset by a reclaim).
 *
 * **Why `version` and `updatedAt` move.** The version bump is what makes the
 * removal stick: a client holding a pre-reclaim snapshot fails its own version
 * guard instead of silently writing the marker back. Re-stamping `updatedAt`
 * gives the returning player a complete window for the decision that is pending
 * when control lands, which is what "returns at the next decision point" means in
 * a machine whose only deadline anchor is that timestamp — and it costs nothing
 * to trust: the marker is gone once it succeeds, so a player cannot farm deadline
 * extensions, and earning one costs 50 s of ignored windows.
 *
 * No `status` gate: a match that completed while a seat was borrowed has nothing
 * left to stall, and the removal on a finished document is inert. The client's
 * poll is bounded and stops with the snapshot that says `complete`.
 */
suspend fun reclaimOnce(
  db: TimeoutDb,
  request: CallableRequest,
  clock: DecisionClock,
): Map<String, Any?> {
  val data = dynamicToKotlin(request.data) as? Map<*, *>
  val matchId = data?.get("matchId") as? String
  if (matchId.isNullOrEmpty()) {
    throw HttpsError("invalid-argument", "reclaimInactiveSeat: matchId is required.")
  }
  val uid = CallableAuth.requireUid(request)

  val fields = db.readMatch(matchId)
    ?: throw HttpsError("not-found", "reclaimInactiveSeat: match '$matchId' was not found.")
  val players = TimeoutResolution.parsePlayers(fields)
    ?: throw HttpsError(
      "failed-precondition",
      "reclaimInactiveSeat: match '$matchId' is not a match document.",
    )
  if (uid !in players) {
    throw HttpsError(
      "permission-denied",
      "reclaimInactiveSeat: you are not a player in this match.",
    )
  }

  return db.transact { tx ->
    val fresh = tx.readMatch(matchId)
      ?: throw HttpsError("not-found", "reclaimInactiveSeat: match '$matchId' was not found.")
    // S7: the seat's stored inactivity document is read under the same lock as
    // the write that replaces it, because a reclaim CARRIES the takeover count
    // (A7.3's "no counter reset") — the one read this path now performs that
    // it does not write, and the only way the carried count can be the
    // committed one.
    val storedInactivity = tx.readInactivity(matchId, uid)
    when (val decision = SeatReclaim.decide(fresh, uid, clock.nowMillis(), storedInactivity)) {
      is SeatReclaim.Decision.NoWrite -> reclaimNoWrite(
        decision.reason,
        decision.message,
        decision.retryAfterMillis,
      )
      is SeatReclaim.Decision.Reclaim -> {
        val write = decision.write
        tx.updateMatch(
          matchId,
          mapOf(
            // A replacement of the whole marker, built by
            // [InactiveTakeover.withoutTakeover] so every OTHER seat's entry is
            // carried over verbatim — one player's return can never discard
            // another's takeover.
            InactiveTakeover.TAKEOVER_FIELD to write.marker,
            "version" to ((fresh["version"] as? Number)?.toInt() ?: 0) + 1,
            "updatedAt" to FieldValue.serverTimestamp(),
          ),
        )
        // Keyed by the CALLER, which is the seat's owner by construction: the
        // seat came from the document's own seats map for this uid.
        tx.writeInactivity(matchId, uid, write.inactivityFields)
        reclaimedResponse(matchId, write.seatId)
      }
    }
  }
}

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

    /**
     * S7 (A8.2): the ballot document's tombstone, against the same
     * admin-only-by-construction path `DisconnectVoteDb` uses — the
     * subcollection has no rules block at all, so the delete is an admin
     * action by construction and `firestore.rules` stays byte-identical.
     */
    override suspend fun deleteBallot(matchId: String, uid: String) {
      tx.delete(ballotRef(matchId, uid))
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

  /** `matches/{matchId}/disconnectVotes/{uid}` — S7's ballot cancellation target. */
  private fun ballotRef(matchId: String, uid: String): DocRef =
    matchRef(matchId).collection(DisconnectVoteState.BALLOT_COLLECTION).doc(uid)

  companion object {
    private val TxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
}
