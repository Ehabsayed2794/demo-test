package com.estemshan.services

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.EmitResult
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.canSubmit
import com.estemshan.engine.emit
import com.estemshan.engine.emitPlay
import com.estemshan.engine.resolveTrick
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.session.GameSessionPort
import com.estemshan.services.session.MatchAdapterPort
import com.estemshan.services.session.NotLocalTurn
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * MatchAdapter — port of design-ui/match-adapter.js's read-side
 * interpreter. ONE direction only: a Firestore match document becomes
 * local engine state, always THROUGH the pure :engine reducers. This
 * class never touches Firestore, never calls MatchService, and never
 * mutates the input document.
 *
 * Three independent gates per log, mirroring the JS registries exactly:
 *  1. a per-log VERSION registry — strict greater-than, so a duplicate or
 *     rolled-back delivery is ignored, never re-applied;
 *  2. a per-log COUNT registry — how many entries have been replayed,
 *     rebased to 0 when a delivery's window is SHORTER than the count
 *     (a new round's window has begun: advanceToNextRound() reset the
 *     parent log to [], never deleted history);
 *  3. a per-entry ROUND-TAG guard — an entry for a round AHEAD of the
 *     local engine stops the loop WITHOUT touching either registry
 *     (AWAITING_ROUND_TRANSITION), so a client that hasn't yet
 *     re-initialized its engines for Round N+1 re-attempts that exact
 *     index on a later delivery instead of permanently losing it.
 *
 * A desync (MALFORMED_ENTRY / ENGINE_REJECTED / LOCAL_ECHO_MISMATCH /
 * ENGINE_THREW) stops the loop, advances the count only UP TO the failing
 * index, and leaves the version registry untouched — that is what lets a
 * future delivery re-attempt the same stuck index rather than silently
 * declaring the version handled while the log and the engine disagree.
 *
 * Pure bookkeeping + engine calls; no Android imports.
 */
/**
 * The subscription half — ONE real listener per matchId no matter how many
 * local callers subscribe, reconnect-with-backoff on retryable errors, and
 * an immediate delivery of the current known state (or the terminal error)
 * to every late joiner. Every delivered document is pushed through the
 * applyRemote*() interpreters above BEFORE the listeners are told, so a
 * listener always observes engine state consistent with the document it was
 * handed.
 *
 * The infrastructural pieces are injected seams, which is what keeps this
 * whole class JVM-testable without a device: [listen] produces a parsed
 * [MatchDoc] (or a [FirestoreError]) and [scheduler] delays a reconnect.
 * Neither touches the ordering decisions, which are the actual logic.
 */
class MatchAdapter(
  private val session: GameSessionPort,
  private val listen: MatchListenerFactory = MatchListenerFactory.Unavailable,
  private val scheduler: MatchScheduler = MatchScheduler.Immediate,
) : MatchAdapterPort {

  private val bidVersion = mutableMapOf<String, Int>()
  private val biddingActionVersion = mutableMapOf<String, Int>()
  private val biddingActionCount = mutableMapOf<String, Int>()
  private val cardVersion = mutableMapOf<String, Int>()
  private val cardCount = mutableMapOf<String, Int>()
  private val resolvedTrickNo = mutableMapOf<String, Int>()

  // ── MatchAdapterPort: identity + authority ──────────────────────────

  override fun uidToSeat(match: MatchDoc, uid: String): String? = match.uidToSeat(uid)

  override fun seatToUid(match: MatchDoc, seatId: String): String? = match.seatToUid(seatId)

  /**
   * The client-side half of "neither layer trusts the other alone": the
   * doc's own `turn` (a uid) is resolved to a seat and compared, with a
   * fallback to the session's own turn id (a SEAT id, per session.js's
   * setTurn(leaderId/callerId)) when the doc's turn is still null.
   */
  override fun assertLocalTurn(match: MatchDoc, seatId: String) {
    if (!isLocalSeatsTurn(match, seatId)) {
      throw NotLocalTurn("assertLocalTurn: it is not seat '$seatId's turn right now.")
    }
  }

  private fun isLocalSeatsTurn(match: MatchDoc, seatId: String): Boolean {
    val turnSeat = match.uidToSeat(match.turn) ?: session.getTurn()
    return turnSeat != null && turnSeat == seatId
  }

  // ── Remote bid application (Final Estimate only) ───────────────────

  /**
   * applyRemoteBid — the newest accepted bid on the document becomes
   * exactly one BiddingIntent.FinalEstimate into the local engine, and
   * nothing else. bids/ holds exactly one opaque integer per seat — the
   * final-estimate shape and ONLY that shape; translating a bare integer
   * into a Dash/Auction/Confirm action would mean guessing what it means,
   * which is a gameplay rule this layer must never encode. The Dash/
   * Auction/Confirm sync path is [applyRemoteBiddingAction].
   */
  fun applyRemoteBid(matchId: String, matchDoc: MatchDoc?): ApplyOutcome {
    if (matchId.isEmpty() || matchDoc == null) return malformed()
    val version = matchDoc.version

    bidVersion[matchId]?.let { last ->
      if (version <= last) {
        return ApplyOutcome.notApplied(if (version == last) DUPLICATE_VERSION else STALE_VERSION)
      }
    }

    val seatId = matchDoc.lastBidSeat
    if (seatId.isEmpty()) {
      // A valid-but-empty snapshot (fresh doc, no bid yet): record the
      // version so an identical future delivery is a duplicate, not
      // re-evaluated forever.
      bidVersion[matchId] = version
      return ApplyOutcome.notApplied(NO_BID_TO_APPLY)
    }
    val bidValue = matchDoc.bids[seatId]
    if (bidValue == null) return malformed()

    val state = session.getBiddingState() ?: return ApplyOutcome.notApplied(ENGINE_UNAVAILABLE)
    if (state.subPhase != com.estemshan.engine.BiddingPhase.ESTIMATES) {
      bidVersion[matchId] = version
      return ApplyOutcome.notApplied(PHASE_MISMATCH)
    }
    if (state.waitingFor != seatId) {
      bidVersion[matchId] = version
      return ApplyOutcome.notApplied(NOT_THIS_SEATS_TURN)
    }
    // The local-engine echo case: this client's own bid already landed via
    // its own emit() and is now echoing back through Firestore — never
    // re-emit, even though this version is genuinely newer.
    if (state.bids[seatId] != null) {
      bidVersion[matchId] = version
      return ApplyOutcome.notApplied(ALREADY_APPLIED_LOCALLY)
    }

    val result = emit(state, BiddingIntent.FinalEstimate(seatId, bidValue))
    bidVersion[matchId] = version
    return when (result) {
      is EmitResult.Rejected ->
        ApplyOutcome(applied = false, reason = ENGINE_REJECTED, desync = true, version = version)
      is EmitResult.Applied, is EmitResult.Completed, is EmitResult.GeneralPass -> {
        session.updateBiddingState(result.state())
        ApplyOutcome(applied = true, reason = REASON_APPLIED, seatId = seatId, version = version)
      }
    }
  }

  // ── Remote bidding-action application (Dash / Auction / Confirm) ────

  /**
   * applyRemoteBiddingAction — replays the document's biddingLog tail into
   * the local engine, entry by entry. Legality is asked BEFORE every emit
   * via canSubmit(): a rejection that is a phase/turn guard means the
   * engine has already moved past this entry (this client's own echo, or
   * a late delivery) and is skipped as benign; any other rejection is a
   * real desync and stops the loop.
   */
  fun applyRemoteBiddingAction(matchId: String, matchDoc: MatchDoc?): ApplyOutcome {
    if (matchId.isEmpty() || matchDoc == null) return malformed()
    val version = matchDoc.version

    biddingActionVersion[matchId]?.let { last ->
      if (version <= last) {
        return ApplyOutcome.notApplied(if (version == last) DUPLICATE_VERSION else STALE_VERSION)
      }
    }

    var lastCount = biddingActionCount[matchId] ?: 0
    if (matchDoc.biddingLog.size < lastCount) {
      lastCount = 0
      biddingActionCount[matchId] = 0
    }
    if (matchDoc.biddingLog.size <= lastCount) {
      biddingActionVersion[matchId] = version
      return ApplyOutcome.notApplied(NO_NEW_BIDDING_ACTIONS)
    }

    val results = ArrayList<EntryOutcome>()
    var i = lastCount
    while (i < matchDoc.biddingLog.size) {
      val entry = matchDoc.biddingLog[i]
      val intent = entryToIntent(entry)
      if (intent == null) {
        results.add(EntryOutcome(i, false, MALFORMED_ENTRY, entry.seatId))
        biddingActionCount[matchId] = i
        return desync(MALFORMED_ENTRY, matchId, i, version, results)
      }

      // Round-tag guard — see the class header for why an entry whose
      // round is AHEAD of the local engine must stop WITHOUT advancing
      // either registry.
      val localRound = session.getBiddingState()?.round
      if (localRound != null && entry.round > localRound) {
        results.add(EntryOutcome(i, false, AWAITING_ROUND_TRANSITION, entry.seatId))
        return ApplyOutcome(
          applied = false, reason = AWAITING_ROUND_TRANSITION, index = i,
          version = version, results = results,
        )
      }
      if (localRound != null && entry.round < localRound) {
        results.add(EntryOutcome(i, false, STALE_ROUND, entry.seatId))
        i++
        continue
      }

      val state = session.getBiddingState()
      if (state == null) {
        results.add(EntryOutcome(i, false, ENGINE_UNAVAILABLE, entry.seatId))
        return desync(ENGINE_UNAVAILABLE, matchId, i, version, results)
      }
      val verdict = runCatching { canSubmit(state, intent) }
        .getOrElse { err ->
          results.add(EntryOutcome(i, false, ENGINE_THREW, entry.seatId))
          biddingActionCount[matchId] = i
          return desync(ENGINE_THREW, matchId, i, version, results)
        }
      if (!verdict.legal) {
        if (isPhaseOrTurnMismatchReason(verdict.reason)) {
          results.add(EntryOutcome(i, false, ALREADY_APPLIED_LOCALLY, entry.seatId))
          i++
          continue
        }
        results.add(EntryOutcome(i, false, ENGINE_REJECTED, entry.seatId))
        biddingActionCount[matchId] = i
        return desync(ENGINE_REJECTED, matchId, i, version, results)
      }

      val result = runCatching { emit(state, intent) }
        .getOrElse {
          results.add(EntryOutcome(i, false, ENGINE_THREW, entry.seatId))
          biddingActionCount[matchId] = i
          return desync(ENGINE_THREW, matchId, i, version, results)
        }
      if (result is EmitResult.Rejected) {
        results.add(EntryOutcome(i, false, ENGINE_REJECTED, entry.seatId))
        biddingActionCount[matchId] = i
        return desync(ENGINE_REJECTED, matchId, i, version, results)
      }
      session.updateBiddingState(result.state())
      results.add(EntryOutcome(i, true, REASON_APPLIED, entry.seatId))
      i++
    }

    biddingActionCount[matchId] = matchDoc.biddingLog.size
    biddingActionVersion[matchId] = version
    return ApplyOutcome(
      applied = results.any { it.applied },
      reason = if (results.any { it.applied }) REASON_APPLIED else NO_NEW_BIDDING_ACTIONS,
      appliedCount = results.count { it.applied },
      version = version,
      results = results,
    )
  }

  // ── Remote card application ────────────────────────────────────────

  /**
   * applyRemoteCard — replays the document's cardLog tail into the local
   * table engine. An echo is only an echo if the local engine's recorded
   * play for this seat matches BOTH suit and rank; a same-seat different-
   * card is a LOCAL_ECHO_MISMATCH desync, never a silent skip. An
   * opponent's private hand is unknowable, so that one observed card is
   * seeded into a throwaway state copy for the reducer and restored on
   * the committed result — it never becomes a persisted hand.
   */
  fun applyRemoteCard(matchId: String, matchDoc: MatchDoc?, localSeatId: String?): ApplyOutcome {
    if (matchId.isEmpty() || matchDoc == null) return malformed()
    val version = matchDoc.version

    cardVersion[matchId]?.let { last ->
      if (version <= last) {
        return ApplyOutcome.notApplied(if (version == last) DUPLICATE_VERSION else STALE_VERSION)
      }
    }

    var lastCount = cardCount[matchId] ?: 0
    if (matchDoc.cardLog.size < lastCount) {
      lastCount = 0
      cardCount[matchId] = 0
    }
    if (matchDoc.cardLog.size <= lastCount) {
      cardVersion[matchId] = version
      return ApplyOutcome.notApplied(NO_NEW_CARDS)
    }

    val results = ArrayList<EntryOutcome>()
    var i = lastCount
    while (i < matchDoc.cardLog.size) {
      val entry = matchDoc.cardLog[i]
      val card = entry.card.toEngineCard()
      if (card == null) {
        results.add(EntryOutcome(i, false, MALFORMED_ENTRY, entry.seatId))
        cardCount[matchId] = i
        return desync(MALFORMED_ENTRY, matchId, i, version, results)
      }

      val localRound = session.getPlayState()?.cfg?.round
      if (localRound != null && entry.round > localRound) {
        results.add(EntryOutcome(i, false, AWAITING_ROUND_TRANSITION, entry.seatId))
        return ApplyOutcome(
          applied = false, reason = AWAITING_ROUND_TRANSITION, index = i,
          version = version, results = results,
        )
      }
      if (localRound != null && entry.round < localRound) {
        results.add(EntryOutcome(i, false, STALE_ROUND, entry.seatId))
        i++
        continue
      }

      val state = session.getPlayState()
      if (state == null) {
        results.add(EntryOutcome(i, false, ENGINE_UNAVAILABLE, entry.seatId))
        return desync(ENGINE_UNAVAILABLE, matchId, i, version, results)
      }

      // Echo check: the seat's already-recorded play in this trick must
      // match the remote card exactly, or this is a divergence.
      val localPlay = state.plays.firstOrNull { it.playerId == entry.seatId }
      if (localPlay != null) {
        if (localPlay.card.suit == card.suit && localPlay.card.rank.v == card.rank.v) {
          results.add(EntryOutcome(i, false, ALREADY_APPLIED_LOCALLY, entry.seatId))
          i++
          continue
        }
        results.add(EntryOutcome(i, false, LOCAL_ECHO_MISMATCH, entry.seatId))
        cardCount[matchId] = i
        return desync(LOCAL_ECHO_MISMATCH, matchId, i, version, results)
      }

      val isObservedOpponentPlay = localSeatId != null && entry.seatId != localSeatId
      val priorHand = state.cfg.hands[entry.seatId]
      val working = if (isObservedOpponentPlay) {
        state.copy(cfg = state.cfg.copy(hands = state.cfg.hands + (entry.seatId to listOf(card))))
      } else state

      val result = emitPlay(working, PlayCard(entry.seatId, card))
      val applied = when (result) {
        is PlayEmit.Rejected -> {
          results.add(EntryOutcome(i, false, ENGINE_REJECTED, entry.seatId))
          cardCount[matchId] = i
          return desync(ENGINE_REJECTED, matchId, i, version, results)
        }
        is PlayEmit.Applied -> result.state
      }
      // Restore the opponent's unknowable hand: the observed card is
      // committed as a played card, never as a held one. A seat with no
      // prior hand must end up with no hand again, not with a null one.
      session.updatePlayState(
        if (!isObservedOpponentPlay) {
          applied
        } else if (priorHand != null) {
          applied.copy(cfg = applied.cfg.copy(hands = applied.cfg.hands + (entry.seatId to priorHand)))
        } else {
          applied.copy(cfg = applied.cfg.copy(hands = applied.cfg.hands - entry.seatId))
        },
      )
      results.add(EntryOutcome(i, true, REASON_APPLIED, entry.seatId))
      i++
    }

    cardCount[matchId] = matchDoc.cardLog.size
    cardVersion[matchId] = version
    return ApplyOutcome(
      applied = results.any { it.applied },
      reason = if (results.any { it.applied }) REASON_APPLIED else NO_NEW_CARDS,
      appliedCount = results.count { it.applied },
      version = version,
      results = results,
    )
  }

  /** Clears this adapter's bookkeeping for one match, or all of them. */
  fun resetSyncState(matchId: String? = null) {
    if (matchId == null) {
      bidVersion.clear()
      biddingActionVersion.clear()
      biddingActionCount.clear()
      cardVersion.clear()
      cardCount.clear()
      resolvedTrickNo.clear()
      return
    }
    bidVersion.remove(matchId)
    biddingActionVersion.remove(matchId)
    biddingActionCount.remove(matchId)
    cardVersion.remove(matchId)
    cardCount.remove(matchId)
    resolvedTrickNo.remove(matchId)
  }

  // Test/diagnostic-only observability of the internal gates.
  fun lastAppliedBidVersion(matchId: String): Int? = bidVersion[matchId]
  fun lastAppliedBiddingActionVersion(matchId: String): Int? = biddingActionVersion[matchId]
  fun lastAppliedBiddingActionCount(matchId: String): Int = biddingActionCount[matchId] ?: 0
  fun lastAppliedCardVersion(matchId: String): Int? = cardVersion[matchId]
  fun lastAppliedCardCount(matchId: String): Int = cardCount[matchId] ?: 0
  fun lastResolvedTrickNo(matchId: String): Int? = resolvedTrickNo[matchId]

  // ── ONE ref-counted listener per matchId ───────────────────────────

  private val subscriptions = mutableMapOf<String, Subscription>()

  /** The last exception a snapshot listener threw, surfaced instead of
   *  swallowed — one bad callback must never kill the listener loop. */
  @Volatile var lastListenerError: Throwable? = null
    private set

  private inner class Subscription(val matchId: String) {
    val listeners = mutableListOf<MatchSnapshotListener>()
    var registration: MatchListenerRegistration? = null
    var hasPublished = false
    var lastPublished: MatchDoc? = null
    var lastVersion: Int? = null
    var reconnectAttempt = 0
    var pendingReconnect: MatchSchedulerTask? = null
    var terminalError: FirestoreError? = null
    /** The local seat for this device — needed only by applyRemoteCard's
     *  opponent-hand seeding; null while unknown. */
    var localSeatId: String? = null
  }

  /**
   * subscribeToMatch — registers [listener] against the ONE real listener
   * for [matchId], creating it only if it does not already exist. A second
   * caller for the same matchId never opens a second Firestore listener;
   * it gets the current known state delivered immediately instead. The
   * returned handle removes exactly this caller; the LAST one out tears the
   * real listener down.
   */
  fun subscribeToMatch(
    matchId: String,
    listener: MatchSnapshotListener,
    localSeatId: String? = null,
  ): MatchSubscriptionHandle {
    if (matchId.isEmpty()) {
      safeInvoke(listener) { it.onSnapshot(MatchSnapshot(match = null, error = EMPTY_MATCH_ID)) }
      return MatchSubscriptionHandle.Noop
    }
    val entry = subscriptions.getOrPut(matchId) { Subscription(matchId).also { attach(it) } }
    if (localSeatId != null) entry.localSeatId = localSeatId
    entry.listeners.add(listener)

    // A late joiner (a second local caller, or the SAME caller subscribing
    // again) gets the current known state immediately instead of waiting on
    // a Firestore round trip it doesn't need — and if this subscription
    // already hit a non-retryable error, it learns that immediately too,
    // instead of silently waiting on a reconnect that will never run.
    val terminal = entry.terminalError
    when {
      terminal != null -> safeInvoke(listener) {
        it.onSnapshot(MatchSnapshot(if (entry.hasPublished) entry.lastPublished else null, terminal))
      }
      entry.hasPublished -> safeInvoke(listener) { it.onSnapshot(MatchSnapshot(entry.lastPublished, null)) }
    }
    return MatchSubscriptionHandle {
      val idx = entry.listeners.indexOf(listener)
      if (idx != -1) entry.listeners.removeAt(idx)
      if (entry.listeners.isEmpty()) close(entry)
    }
  }

  /** (Re)attaches the one real listener, reusing the SAME entry and the
   *  SAME listener list — never a second registration — on every backoff
   *  tick after a disconnect. */
  private fun attach(entry: Subscription) {
    entry.registration = listen.listen(
      matchId = entry.matchId,
      onSnapshot = { doc -> onSnapshot(entry, doc) },
      onError = { err -> onError(entry, err) },
    )
  }

  private fun onSnapshot(entry: Subscription, doc: MatchDoc?) {
    entry.reconnectAttempt = 0 // a successful snapshot means we're connected again
    // Ordering guard: a stale or duplicate version is ignored, never
    // re-published (a rolled-back delivery must never go backwards).
    val version = doc?.version
    if (version != null) {
      val last = entry.lastVersion
      if (last != null && version <= last) return
      entry.lastVersion = version
    }
    // Duplicate-content guard: an identical re-delivery (a benign
    // metadata-only refresh) is never re-published — this is what stops a
    // publish-react loop from becoming an infinite one.
    if (entry.hasPublished && doc == entry.lastPublished) return

    entry.hasPublished = true
    entry.lastPublished = doc

    // The card pipeline alternates: emitPlay() refuses a new card while
    // the engine is RESOLVING, and cardLog is append-only within a round,
    // so ONE delivery (a late subscriber, or a reconnect that missed
    // several) can legitimately carry MULTIPLE completed-but-not-yet-
    // locally-resolved tricks. Catching up on N of them takes N alternating
    // replay/resolve steps, each individually idempotent and registry-
    // gated, stopping the instant a full pass advances nothing. 13 is the
    // most tricks one round can hold; +2 bounds a final no-op pass.
    val cards = doc?.cardLog?.size ?: 0
    val bound = if (cards in 1..80) cards + 2 else 14
    var card: ApplyOutcome = ApplyOutcome.notApplied(NO_NEW_CARDS)
    for (pass in 0 until bound) {
      val countBefore = lastAppliedCardCount(entry.matchId)
      val trickBefore = lastResolvedTrickNo(entry.matchId)
      card = applyRemoteCard(entry.matchId, doc, entry.localSeatId)
      val trick = applyRemoteTrick(entry.matchId, doc)
      // The ONLY stop condition: a full pass advanced neither the replayed
      // count nor a resolved trick. A card ENGINE_REJECTED at a trick
      // boundary is the ordinary mid-catch-up state (the engine is
      // RESOLVING and refuses a new card until the trick is collected) —
      // the resolve in THIS same pass is what unblocks the next one, so a
      // desync here must NOT break the loop. A real desync stalls both
      // counters and this same check catches it on the very next pass.
      if (
        lastAppliedCardCount(entry.matchId) == countBefore &&
        lastResolvedTrickNo(entry.matchId) == trickBefore &&
        !trick.applied
      ) break
    }

    val bid = applyRemoteBid(entry.matchId, doc)
    val action = applyRemoteBiddingAction(entry.matchId, doc)

    // The document became engine state BEFORE any listener saw it, so a
    // listener always observes engines consistent with the snapshot it was
    // handed. Each interpreter is independently version-gated; a phase the
    // local engines haven't reached yet is left untouched by that gate.
    val snapshot = MatchSnapshot(doc, null, bid, action, card)
    entry.listeners.toList().forEach { safeInvoke(it) { l -> l.onSnapshot(snapshot) } }
  }

  private fun onError(entry: Subscription, err: FirestoreError) {
    // Fail-open: the last known good data (if any) is delivered ALONGSIDE
    // the error, never replaced by it — the local game keeps what it has.
    val delivered = if (entry.hasPublished) entry.lastPublished else null
    entry.listeners.toList().forEach {
      safeInvoke(it) { l -> l.onSnapshot(MatchSnapshot(delivered, err)) }
    }
    when (classify(err)) {
      ErrorClass.RETRYABLE -> scheduleReconnect(entry)
      else -> entry.terminalError = err
    }
  }

  /** Exactly one pending resubscribe attempt (never stacks a second), with
   *  exponential backoff that resets to the base delay the moment a
   *  snapshot succeeds again. Never resubscribes a matchId nobody is
   *  listening to anymore. */
  private fun scheduleReconnect(entry: Subscription) {
    if (entry.pendingReconnect != null) return
    if (entry.listeners.isEmpty()) return
    val delay = minOf(RECONNECT_BASE_MS shl entry.reconnectAttempt, RECONNECT_MAX_MS)
    entry.reconnectAttempt += 1
    entry.pendingReconnect = scheduler.schedule(delay) {
      entry.pendingReconnect = null
      if (entry.listeners.isNotEmpty()) {
        entry.registration?.cancel()
        attach(entry)
      }
    }
  }

  private fun close(entry: Subscription) {
    entry.pendingReconnect?.cancel()
    entry.pendingReconnect = null
    entry.registration?.cancel()
    entry.registration = null
    subscriptions.remove(entry.matchId)
    // DELIBERATELY does NOT call resetSyncState(matchId). The per-log count
    // registries stay put so a same-session resubscribe continues from the
    // last applied index, instead of re-replaying a log the engines already
    // absorbed (which the echo guards would correctly flag as a desync). A
    // real reload constructs a fresh MatchAdapter alongside fresh engines —
    // "a real page load simply starts with a fresh, empty registry already"
    // (design-ui/match/index.html:922). resetSyncState() stays exported for
    // that boot path and for diagnostics.
  }

  private inline fun <T> safeInvoke(target: T, block: (T) -> Unit) {
    try {
      block(target)
    } catch (t: Throwable) {
      lastListenerError = t
    }
  }

  private enum class ErrorClass { RETRYABLE, NON_RETRYABLE, UNRECOGNIZED }

  /**
   * Per docs/architecture/MatchSynchronization.md's Task 1: a code in
   * NEITHER list — including a missing one, which is what every non-
   * Firestore failure looks like — is NON-retryable. Retrying something we
   * cannot positively confirm transient is exactly the "retry forever" this
   * classification exists to remove.
   */
  private fun classify(err: FirestoreError): ErrorClass = when (err.code) {
    in RETRYABLE_CODES -> ErrorClass.RETRYABLE
    in NON_RETRYABLE_CODES -> ErrorClass.NON_RETRYABLE
    else -> ErrorClass.UNRECOGNIZED
  }

  // ── Remote trick resolution ────────────────────────────────────────

  /**
   * applyRemoteTrick — collects the trick the local engine has just
   * completed emitting (its own phase is RESOLVING) via the pure
   * resolveTrick(), and records which trickNo was resolved so a repeat
   * pass is an idempotent no-op rather than a double resolution. This is
   * the second half of the card pipeline: emitPlay() refuses a new card
   * while the engine is RESOLVING, so catching up on N backlogged tricks
   * needs N alternating applyRemoteCard()/applyRemoteTrick() steps.
   *
   * A call when the engine is not at the boundary is an ordinary no-op
   * (NOT_RESOLVING), exactly like every other applyRemote*()'s "no new X"
   * case — it never second-guesses an ENGINE_REJECTED that an earlier
   * applyRemoteCard() in the same pass already reported as a real desync.
   */
  fun applyRemoteTrick(matchId: String, matchDoc: MatchDoc?): TrickOutcome {
    if (matchId.isEmpty() || matchDoc == null) return TrickOutcome.notApplied(MALFORMED_SNAPSHOT)
    val state = session.getPlayState() ?: return TrickOutcome.notApplied(ENGINE_UNAVAILABLE)
    if (state.phase != com.estemshan.engine.TablePhase.RESOLVING) {
      return TrickOutcome.notApplied(NOT_RESOLVING)
    }

    val trickNo = state.trickNo
    if (resolvedTrickNo[matchId] == trickNo) {
      return TrickOutcome.notApplied(ALREADY_RESOLVED, trickNo)
    }

    val after = resolveTrick(state)
    session.updatePlayState(after)
    resolvedTrickNo[matchId] = trickNo

    // NECESSARY COMPLETION: the match document's `turn` is null at the
    // resolving boundary and nothing writes the real next leader back into
    // it (no Firestore field carries it). Without mirroring the engine's
    // own decision into the session's turn, the stale value would keep
    // reporting the seat that played the 3rd card as the turn holder,
    // blocking the next trick's first submission. Only when there genuinely
    // IS a next trick to lead — at trick 13 resolveTrick() itself leaves
    // the round DONE with no turn to mirror.
    if (after.phase == com.estemshan.engine.TablePhase.PLAY && after.turn != null) {
      session.setTurn(after.turn)
    }

    return TrickOutcome(
      applied = true,
      trickNo = trickNo,
      winnerId = after.lastTrick?.winnerId,
      nextLeaderId = after.leaderId,
      nextTurnSeat = after.turn,
      nextPhase = after.phase.name,
      tricksWon = after.tricksWon,
    )
  }

  // ── helpers ────────────────────────────────────────────────────────

  private fun malformed() = ApplyOutcome.notApplied(MALFORMED_SNAPSHOT)

  private fun desync(
    reason: String,
    matchId: String,
    index: Int,
    version: Int,
    results: List<EntryOutcome>,
  ) = ApplyOutcome(
    applied = false, reason = reason, desync = true, index = index,
    appliedCount = results.count { it.applied }, version = version, results = results,
  )

  /**
   * The SAME translation MatchService performs in the opposite direction
   * (its own biddingActionToIntent()) — actionType IS the engine's own
   * intent type string, so this is a field passthrough, never a
   * re-derivation. null when the entry cannot yield a well-formed intent.
   */
  private fun entryToIntent(entry: BiddingLogEntry): BiddingIntent? = when (entry.actionType) {
    BiddingLogEntry.ACTION_DASH_CALL -> {
      val declared = entry.declaredDashCall ?: return null
      BiddingIntent.DashCallDecision(entry.seatId, declared)
    }
    BiddingLogEntry.ACTION_AUCTION_BID -> {
      if (entry.isPass == true) {
        BiddingIntent.AuctionBid(entry.seatId, isPass = true)
      } else {
        BiddingIntent.AuctionBid(
          entry.seatId, isPass = false,
          tricks = entry.tricks ?: return null,
          suit = parseSuit(entry.suit) ?: return null,
        )
      }
    }
    BiddingLogEntry.ACTION_CONFIRM_CALL -> BiddingIntent.ConfirmCall(
      entry.seatId,
      entry.tricks ?: return null,
      parseSuit(entry.suit) ?: return null,
    )
    else -> null
  }

  private fun parseSuit(name: String?) =
    if (name == null) null else runCatching { com.estemshan.engine.Suit.valueOf(name) }.getOrNull()

  /**
   * The 5 phase/turn-guard reasons canSubmit() returns for every intent's
   * first two checks — matched against the literal strings the Kotlin
   * engine actually emits, never a substring guess. A rejection for any
   * OTHER reason is a content-rule disagreement, i.e. a real desync.
   */
  private fun isPhaseOrTurnMismatchReason(reason: String?): Boolean = when (reason) {
    "Not this seat's turn",
    "Not the Dash-Call phase",
    "Not the Auction phase",
    "Not the Confirmation phase",
    "Not the Final Estimates phase",
    "Bidding is already complete" -> true
    else -> false
  }

  private fun EmitResult.state() = when (this) {
    is EmitResult.Applied -> state
    is EmitResult.Completed -> state
    is EmitResult.GeneralPass -> state
    is EmitResult.Rejected -> error("unreachable: Rejected handled by the caller")
  }
}

/** One applyRemote*() call's outcome — every path returns one, none throws. */
data class ApplyOutcome(
  val applied: Boolean,
  val reason: String,
  val desync: Boolean = false,
  val appliedCount: Int = 0,
  val index: Int? = null,
  val seatId: String? = null,
  val version: Int? = null,
  val results: List<EntryOutcome> = emptyList(),
) {
  companion object {
    fun notApplied(reason: String) = ApplyOutcome(applied = false, reason = reason)
  }
}

data class EntryOutcome(
  val index: Int,
  val applied: Boolean,
  val reason: String,
  val seatId: String? = null,
)

/** One applyRemoteTrick() call's outcome — every path returns one. */
data class TrickOutcome(
  val applied: Boolean,
  val reason: String,
  val trickNo: Int? = null,
  val winnerId: String? = null,
  val nextLeaderId: String? = null,
  val nextTurnSeat: String? = null,
  val nextPhase: String? = null,
  val tricksWon: Map<String, Int> = emptyMap(),
) {
  companion object {
    fun notApplied(reason: String, trickNo: Int? = null) = TrickOutcome(applied = false, reason = reason, trickNo = trickNo)
  }
}

private const val REASON_APPLIED = "APPLIED"
private const val MALFORMED_SNAPSHOT = "MALFORMED_SNAPSHOT"
private const val DUPLICATE_VERSION = "DUPLICATE_VERSION"
private const val STALE_VERSION = "STALE_VERSION"
private const val NO_BID_TO_APPLY = "NO_BID_TO_APPLY"
private const val PHASE_MISMATCH = "PHASE_MISMATCH"
private const val NOT_THIS_SEATS_TURN = "NOT_THIS_SEATS_TURN"
private const val ALREADY_APPLIED_LOCALLY = "ALREADY_APPLIED_LOCALLY"
private const val ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE"
private const val ENGINE_REJECTED = "ENGINE_REJECTED"
private const val ENGINE_THREW = "ENGINE_THREW"
private const val NO_NEW_BIDDING_ACTIONS = "NO_NEW_BIDDING_ACTIONS"
private const val NO_NEW_CARDS = "NO_NEW_CARDS"
private const val AWAITING_ROUND_TRANSITION = "AWAITING_ROUND_TRANSITION"
private const val STALE_ROUND = "STALE_ROUND"
private const val MALFORMED_ENTRY = "MALFORMED_ENTRY"
private const val LOCAL_ECHO_MISMATCH = "LOCAL_ECHO_MISMATCH"
private const val NOT_RESOLVING = "NOT_RESOLVING"
private const val ALREADY_RESOLVED = "ALREADY_RESOLVED"

private const val EMPTY_MATCH_ID = "EMPTY_MATCH_ID"
private const val RECONNECT_BASE_MS = 250
private const val RECONNECT_MAX_MS = 4000

/** gRPC codes Firestore treats as transient — retried with backoff. */
private val RETRYABLE_CODES = setOf(
  "unavailable", "deadline-exceeded", "internal", "unknown", "resource-exhausted",
)

/** Codes that will never succeed by retrying — recorded as terminal. */
private val NON_RETRYABLE_CODES = setOf(
  "permission-denied", "unauthenticated", "invalid-argument", "failed-precondition", "not-found",
)

/**
 * A Firestore snapshot error, carrying only the gRPC-style code string and
 * message. Existing as its own (SDK-free) type is what lets [classify] and
 * the whole reconnect policy be unit-tested without a device or a network.
 */
data class FirestoreError(val code: String?, val message: String?)

/** One real addSnapshotListener for matches/{matchId}, behind a seam. */
fun interface MatchListenerFactory {

  fun listen(
    matchId: String,
    onSnapshot: (MatchDoc?) -> Unit,
    onError: (FirestoreError) -> Unit,
  ): MatchListenerRegistration

  companion object {
    /** match-service.js's `if (!db())` — no Firestore means an immediate,
     *  non-retryable error and a no-op registration, never a silent no-op. */
    val Unavailable: MatchListenerFactory = MatchListenerFactory { _, _, onError ->
      onError(FirestoreError(null, "MatchService: Firestore is not initialized."))
      MatchListenerRegistration.Noop
    }

    /** The production registration against a real [FirebaseFirestore]. */
    fun firestore(db: FirebaseFirestore): MatchListenerFactory =
      MatchListenerFactory { matchId, onSnapshot, onError ->
        val reg = db.collection("matches").document(matchId).addSnapshotListener { snap, err ->
          if (err != null) {
            onError(err.toFirestoreError())
            return@addSnapshotListener
          }
          onSnapshot(snap?.takeIf { it.exists() }?.data?.let { MatchDoc.fromFields(it) })
        }
        MatchListenerRegistration { reg.remove() }
      }
  }
}

/** What [MatchListenerFactory.listen] hands back — cancel() is idempotent. */
fun interface MatchListenerRegistration {
  fun cancel()

  companion object {
    val Noop = MatchListenerRegistration {}
  }
}

/** A delayed one-shot — the backoff timer, behind a seam. */
fun interface MatchScheduler {
  fun schedule(delayMillis: Long, action: () -> Unit): MatchSchedulerTask

  companion object {
    /** Runs the action immediately and ignores the delay. Deterministic by
     *  construction, so it is the test default — and a safe production
     *  default only because [MatchListenerFactory.Unavailable]'s errors are
     *  non-retryable; wire a deferred scheduler in production to get the
     *  real exponential backoff rather than an instant reconnect. */
    val Immediate = MatchScheduler { _, action ->
      action()
      MatchSchedulerTask.Noop
    }
  }
}

fun interface MatchSchedulerTask {
  fun cancel()

  companion object {
    val Noop = MatchSchedulerTask {}
  }
}

/**
 * One delivery to one subscriber: the parsed document (or null when the
 * match was deleted), the error when the listener failed (delivered
 * alongside the last known good document, never instead of it), and the
 * three interpreters' own outcomes — surfaced so a desync is observable by
 * the UI instead of silently halting the replay.
 */
data class MatchSnapshot(
  val match: MatchDoc?,
  val error: FirestoreError?,
  val bidSync: ApplyOutcome? = null,
  val biddingActionSync: ApplyOutcome? = null,
  val cardSync: ApplyOutcome? = null,
)

fun interface MatchSnapshotListener {
  fun onSnapshot(snapshot: MatchSnapshot)
}

/** Returned by subscribeToMatch(); unsubscribe() is idempotent. */
fun interface MatchSubscriptionHandle {
  fun unsubscribe()

  companion object {
    val Noop = MatchSubscriptionHandle {}
  }
}

private fun FirebaseFirestoreException.toFirestoreError(): FirestoreError =
  FirestoreError(code.name.lowercase().replace('_', '-'), message)
