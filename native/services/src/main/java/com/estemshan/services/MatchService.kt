package com.estemshan.services

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.Card
import com.estemshan.engine.Deck
import com.estemshan.engine.Play
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.canSubmit
import com.estemshan.engine.compareForSort
import com.estemshan.engine.previewPlay
import com.estemshan.engine.trickWinner
import com.estemshan.services.model.BiddingActionInput
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.CardLogEntry
import com.estemshan.services.model.DealResult
import com.estemshan.services.model.EndMatchResult
import com.estemshan.services.model.AdvanceResult
import com.estemshan.services.model.ExtendResult
import com.estemshan.services.model.GameState
import com.estemshan.services.model.HandDoc
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.MAX_BID_VALUE
import com.estemshan.services.model.MAX_RANK_VALUE
import com.estemshan.services.model.MIN_RANK_VALUE
import com.estemshan.services.model.RAPID_ROUND_MAX
import com.estemshan.services.model.RAPID_ROUND_MIN
import com.estemshan.services.model.REMATCH_VOTE_DURATION_SECONDS
import com.estemshan.services.model.RematchCreateResult
import com.estemshan.services.model.RoundArchiveDoc
import com.estemshan.services.model.RoomDoc
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.StartMatchResult
import com.estemshan.services.model.StoredCard
import com.estemshan.services.model.SubmitBidResult
import com.estemshan.services.model.SubmitBiddingActionResult
import com.estemshan.services.model.SubmitCardResult
import com.estemshan.services.model.VoteCreateResult
import com.estemshan.services.model.VoteDoc
import com.estemshan.services.model.VoteSubmitResult
import com.estemshan.services.model.VoteTimeoutResult
import com.estemshan.services.model.Reasons.ALREADY_ADVANCED
import com.estemshan.services.model.Reasons.ALREADY_BID
import com.estemshan.services.model.Reasons.ALREADY_COMPLETE
import com.estemshan.services.model.Reasons.ALREADY_DEALT
import com.estemshan.services.model.Reasons.ALREADY_EXTENDED
import com.estemshan.services.model.Reasons.ALREADY_RESOLVED
import com.estemshan.services.model.Reasons.ALREADY_VOTED
import com.estemshan.services.model.Reasons.BIDDING_CLOSED
import com.estemshan.services.model.Reasons.DEADLINE_UNKNOWN
import com.estemshan.services.model.Reasons.ENGINE_UNAVAILABLE
import com.estemshan.services.model.Reasons.ILLEGAL_BIDDING_ACTION
import com.estemshan.services.model.Reasons.ILLEGAL_CARD
import com.estemshan.services.model.Reasons.INVALID_ARGUMENT
import com.estemshan.services.model.Reasons.INVALID_BID_VALUE
import com.estemshan.services.model.Reasons.INVALID_CARD_VALUE
import com.estemshan.services.model.Reasons.INVALID_RESULT
import com.estemshan.services.model.Reasons.MATCH_NOT_COMPLETE
import com.estemshan.services.model.Reasons.MATCH_NOT_FOUND
import com.estemshan.services.model.Reasons.NOT_ALL_YES
import com.estemshan.services.model.Reasons.NOT_YET_EXPIRED
import com.estemshan.services.model.Reasons.NOT_YOUR_TURN
import com.estemshan.services.model.Reasons.PERMISSION_DENIED
import com.estemshan.services.model.Reasons.ROUND_NOT_COMPLETE
import com.estemshan.services.model.Reasons.STALE_GAME_STATE
import com.estemshan.services.model.Reasons.TRICK_WINNER_UNAVAILABLE
import com.estemshan.services.model.Reasons.UNAUTHENTICATED
import com.estemshan.services.model.Reasons.UNKNOWN_NEXT_SEAT
import com.estemshan.services.model.Reasons.UNKNOWN_OPENING_SEAT
import com.estemshan.services.model.Reasons.UNKNOWN_SEAT
import com.estemshan.services.model.Reasons.VOTE_CLOSED
import com.estemshan.services.model.Reasons.VOTE_EXPIRED
import com.estemshan.services.model.Reasons.VOTE_LOCKED
import com.estemshan.services.model.Reasons.RECORDED
import com.estemshan.services.model.Reasons.VOTE_NOT_FOUND
import com.estemshan.services.session.AuthPort
import com.estemshan.services.session.GameSessionPort
import com.estemshan.services.session.MatchAdapterPort
import com.estemshan.services.session.NotLocalTurn
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction

/**
 * MatchService — port of design-ui/match-service.js: the ONE authoritative
 * writer for matches/{matchId} and its subcollections. Every transaction's
 * field set is pinned by docs/specs/03-transactions.md and enforced again
 * by the UNCHANGED firestore.rules (each validator whitelists its own
 * affectedKeys), so a shape deviation is a rules denial, not a style issue.
 *
 * Conventions ported verbatim (match-service.js header):
 *  - the acting uid comes from [auth], NEVER from a parameter;
 *  - generic SHAPE validation runs before any Firestore access;
 *  - the REAL engine (via [session] + the pure :engine functions) is asked
 *    BEFORE the write — no fallback, no silent skip;
 *  - answers computed in local engine state outside the transaction are
 *    guarded by a version re-check inside it (STALE_GAME_STATE);
 *  - idempotent no-ops are RETURNED with a reason, never thrown — a lost
 *    race must converge, not error (the emulator has zero retry).
 *
 * No Android imports; the only SDK surface used is Firestore.
 */
class MatchService(
  private val db: FirebaseFirestore,
  private val auth: AuthPort,
  private val session: GameSessionPort,
  private val adapter: MatchAdapterPort,
  /** Deals exactly 13 cards per present seat; must be unique per call. */
  private val dealer: Deal = Deal.Random,
  /** Best-effort profile mirror; never throws (the write already succeeded). */
  private val profileMatchSync: suspend (uid: String, matchId: String?) -> Unit = { _, _ -> },
  /** Wall clock, injectable so vote-deadline tests are deterministic. */
  private val now: () -> Long = System::currentTimeMillis,
) {

  private val matches get() = db.collection("matches")

  // ── 0. startMatch: the atomic room↔match write ──────────────────────

  /**
   * startMatch(roomId) — creates matches/{id} and flips rooms/{roomId} to
   * in_game in ONE transaction. Idempotent: a room that already has a
   * matchId returns it without a second write. Requires all players ready;
   * firestore.rules' isValidMatchIdChange() re-verifies that independently.
   */
  suspend fun startMatch(roomId: String): String {
    require(roomId.isNotEmpty()) { "startMatch: roomId is required." }
    val roomRef = db.collection("rooms").document(roomId)
    val newMatchRef = matches.document()
    val result = runTx(db) { tx ->
      val roomSnap = tx.getBlocking(roomRef)
      if (!roomSnap.exists()) {
        return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_FOUND, "Room not found."))
      }
      val room = RoomDoc.fromFields(roomSnap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_FOUND, "Room not found."))
      room.matchId?.let { existing ->
        return@runTx TxOutcome.Ok(
          StartMatchResult(matchId = existing, created = false, players = room.players),
        )
      }
      if (!room.allReady) {
        return@runTx TxOutcome.Err(
          ServiceException(INVALID_ARGUMENT, "Not all players are ready."),
        )
      }
      tx.set(newMatchRef, buildInitialMatchFields(
        roomId = roomId,
        players = room.players,
        creator = room.creator,
        serverTimestamp = FieldValue.serverTimestamp(),
      ))
      tx.update(roomRef, mapOf(
        "status" to RoomDoc.STATUS_IN_GAME,
        "matchId" to newMatchRef.id,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(StartMatchResult(newMatchRef.id, true, room.players))
    }
    // Self-sync only — every other seat does this for itself via loadRoom().
    auth.currentUid()?.let { syncProfileMatch(it, result.matchId) }
    return result.matchId
  }

  /** Read-only fetch; null (never an error) when the match is gone. */
  suspend fun loadMatch(matchId: String): MatchDoc? {
    require(matchId.isNotEmpty()) { "loadMatch: matchId is required." }
    val snap = matches.document(matchId).getBlocking()
    return if (!snap.exists()) null else snap.data?.let { MatchDoc.fromFields(it) }
  }

  // ── 1. submitBid: Final Estimate — one value per seat, ever ─────────

  /**
   * submitBid(matchId, seatId, bid) — Final Estimate only. Dash/Auction/
   * Confirm go through [submitBiddingAction]; this field's "exactly one
   * value per seat" shape was always right for the final estimate and
   * stays as-is. The engine is NOT consulted here — a final estimate's
   * legality is the bidding log's business, and bids/ is the one-value
   * channel the rules validator locks to a single seat's slot.
   */
  suspend fun submitBid(matchId: String, seatId: String, bid: Int): SubmitBidResult {
    require(matchId.isNotEmpty()) { "submitBid: matchId is required." }
    require(seatId.isNotEmpty()) { "submitBid: seatId is required." }
    if (!isValidGenericBidValue(bid)) {
      throw ServiceException(INVALID_BID_VALUE,
        "submitBid: bid must be an integer between 0 and $MAX_BID_VALUE.")
    }
    auth.currentUid() ?: throw ServiceException(UNAUTHENTICATED, "submitBid: no signed-in user.")
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val match = readMatch(tx, matchRef) ?: return@runTx noMatchTx("submitBid")
      if (!match.seats.containsKey(seatId)) {
        return@runTx TxOutcome.Err(ServiceException(UNKNOWN_SEAT,
          "submitBid: seat '$seatId' does not exist in this match."))
      }
      if (match.seats.getValue(seatId) != auth.currentUid()) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "submitBid: you do not own seat '$seatId'."))
      }
      if (match.biddingOpen != true) {
        return@runTx TxOutcome.Err(ServiceException(BIDDING_CLOSED,
          "submitBid: bidding is closed for this match."))
      }
      if (match.bids[seatId] != null) {
        return@runTx TxOutcome.Err(ServiceException(ALREADY_BID,
          "submitBid: seat '$seatId' has already submitted a bid."))
      }
      val bids = match.bids + (seatId to bid)
      val allSubmitted = match.seats.isNotEmpty() &&
        match.seats.keys.all { bids[it] != null }
      val nextVersion = match.version + 1
      tx.update(matchRef, mapOf(
        "bids" to bids,
        "biddingOpen" to !allSubmitted,
        "version" to nextVersion,
        "lastBidSeat" to seatId,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(SubmitBidResult(
        matchId = matchId, seatId = seatId, bid = bid, version = nextVersion,
        biddingOpen = !allSubmitted, allSubmitted = allSubmitted,
      ))
    }
  }

  // ── 2. submitBiddingAction: Dash/Auction/Confirm append-only log ────

  /**
   * submitBiddingAction(matchId, action) — the ONE public API for a Dash
   * Call, Auction Bid or Confirm Call. The acting seat is resolved from
   * [auth]; the action's legality is decided by the REAL engine's
   * canSubmit() before any transaction opens, and the version guard inside
   * the transaction keeps that outside-the-transaction verdict honest.
   * The persisted entry is rebuilt from [BiddingActionInput] — the caller's
   * own round claim is never trusted, the fresh document's currentRound
   * stamps the entry.
   */
  suspend fun submitBiddingAction(
    matchId: String,
    action: BiddingActionInput,
  ): SubmitBiddingActionResult {
    require(matchId.isNotEmpty()) { "submitBiddingAction: matchId is required." }
    if (!isValidGenericBiddingAction(action)) {
      throw ServiceException(com.estemshan.services.model.Reasons.INVALID_BIDDING_ACTION_VALUE,
        "submitBiddingAction: action must be a well-formed {actionType,...} entry.")
    }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "submitBiddingAction: no signed-in user.")
    val matchRef = matches.document(matchId)

    /** resolveSeat() — the same call pre-transaction and in-transaction. */
    fun resolveSeat(match: MatchDoc): String = adapter.uidToSeat(match, callingUid)
      ?: throw ServiceException(PERMISSION_DENIED,
        "submitBiddingAction: you do not own a seat in this match.")

    // Pre-check read: engine verdict BEFORE runTransaction() — zero writes
    // attempted for an illegal or out-of-turn action.
    val preSnap = matchRef.getBlocking()
    if (!preSnap.exists()) {
      throw ServiceException(MATCH_NOT_FOUND, "submitBiddingAction: match '$matchId' was not found.")
    }
    val preMatch = MatchDoc.fromFields(preSnap.data ?: emptyMap())
      ?: throw ServiceException(MATCH_NOT_FOUND, "submitBiddingAction: match '$matchId' was not found.")
    val seatId = resolveSeat(preMatch)
    val intent = biddingActionToIntent(seatId, action)
      ?: throw ServiceException(com.estemshan.services.model.Reasons.INVALID_BIDDING_ACTION_VALUE,
        "submitBiddingAction: could not build an engine intent from this action.")
    val bidding = session.getBiddingState()
      ?: throw ServiceException(ENGINE_UNAVAILABLE,
        "submitBiddingAction: the local BiddingEngine has no state for this round — cannot validate this action before writing it.")
    if (!canSubmit(bidding, intent).legal) {
      throw ServiceException(ILLEGAL_BIDDING_ACTION,
        "submitBiddingAction: the bidding engine rejected this action — not written.")
    }
    val expectedVersion = preMatch.version

    return runTx(db) { tx ->
      val fresh = readMatch(tx, matchRef) ?: return@runTx noMatchTx("submitBiddingAction")
      if (fresh.version != expectedVersion) {
        return@runTx TxOutcome.Err(ServiceException(STALE_GAME_STATE,
          "submitBiddingAction: the match document changed since this action was validated (expected version $expectedVersion, found ${fresh.version}) — not written; re-fetch and retry."))
      }
      val freshSeatId = resolveSeat(fresh)
      val newEntry = action.toLogEntry(freshSeatId, fresh.currentRound)
      val nextVersion = expectedVersion + 1
      tx.update(matchRef, mapOf(
        "biddingLog" to FieldValue.arrayUnion(newEntry.toFields()),
        "version" to nextVersion,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(SubmitBiddingActionResult(
        matchId = matchId, seatId = freshSeatId, actionType = action.actionType,
        version = nextVersion, logLength = fresh.biddingLog.size + 1,
      ))
    }
  }

  // ── 3. submitCard: the trick-by-trick card log ──────────────────────

  /**
   * submitCard(matchId, card) — appends exactly one entry to the current
   * round's cardLog via arrayUnion (O(1) payload, never a full-array
   * rewrite), advances turn/cardPhase under the engine's preview, and at
   * the fourth card persists the engine-derived winner atomically with the
   * RESOLVING marker (firestore.rules requires the new turn to be a real
   * seat owner — null is not a valid card-write transition).
   *
   * Two pre-transaction gates (seat+turn authority, then engine legality)
   * run BEFORE any transaction opens; the version guard inside rejects
   * STALE_GAME_STATE rather than trusting a stale preview. A fresh round
   * whose turn is still null needs the opening-turn publication first —
   * a separate atomic write the card ruleset requires before it will
   * accept any card (oldData.turn == request.auth.uid).
   */
  suspend fun submitCard(matchId: String, card: Card): SubmitCardResult {
    require(matchId.isNotEmpty()) { "submitCard: matchId is required." }
    if (!isValidGenericCardValue(StoredCard.fromEngine(card))) {
      throw ServiceException(INVALID_CARD_VALUE,
        "submitCard: card must have a real suit and a rank.v between $MIN_RANK_VALUE and $MAX_RANK_VALUE.")
    }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "submitCard: no signed-in user.")
    val matchRef = matches.document(matchId)

    /** resolveSeatAndAuthorize() — same call pre-transaction and in-tx. */
    fun resolveSeatAndAuthorize(match: MatchDoc): String {
      val seatId = adapter.uidToSeat(match, callingUid)
        ?: throw ServiceException(PERMISSION_DENIED,
          "submitCard: you do not own a seat in this match.")
      if (match.isRoundOneOpeningWindow) return seatId
      try {
        adapter.assertLocalTurn(match, seatId)
      } catch (e: NotLocalTurn) {
        throw ServiceException(NOT_YOUR_TURN,
          "submitCard: it is not seat '$seatId's turn right now.")
      }
      return seatId
    }

    // ── pre-check read: authority + legality, zero writes attempted ───
    val preSnap = matchRef.getBlocking()
    if (!preSnap.exists()) {
      throw ServiceException(MATCH_NOT_FOUND, "submitCard: match '$matchId' was not found.")
    }
    var match = MatchDoc.fromFields(preSnap.data ?: emptyMap())
      ?: throw ServiceException(MATCH_NOT_FOUND, "submitCard: match '$matchId' was not found.")
    val seatId = resolveSeatAndAuthorize(match)
    val table = session.getPlayState()
      ?: throw ServiceException(ENGINE_UNAVAILABLE,
        "submitCard: the local TableEngine has no state for this round — cannot validate this card before writing it.")
    val preview = previewPlay(table, seatId, card)
    if (!preview.legal) {
      throw ServiceException(ILLEGAL_CARD,
        "submitCard: the table engine rejected this card (${preview.reason}) — not written.")
    }
    var nextTurnUid: String? = null
    if (preview.nextTurnSeat != null) {
      nextTurnUid = adapter.seatToUid(match, preview.nextTurnSeat)
        ?: throw ServiceException(UNKNOWN_NEXT_SEAT,
          "submitCard: the table engine's next seat ('${preview.nextTurnSeat}') is not a real seat in this match.")
    }

    // ── opening-turn publication (P1-08): a separate atomic write ─────
    match = publishOpeningTurnIfNeeded(matchId, match, seatId)
    val expectedVersion = match.version

    return runTx(db) { tx ->
      val fresh = readMatch(tx, matchRef) ?: return@runTx noMatchTx("submitCard")
      if (fresh.version != expectedVersion) {
        return@runTx TxOutcome.Err(ServiceException(STALE_GAME_STATE,
          "submitCard: the match document changed since this card was validated (expected version $expectedVersion, found ${fresh.version}) — not written; re-fetch and retry."))
      }
      val freshSeatId = resolveSeatAndAuthorize(fresh)
      val newEntry = CardLogEntry(freshSeatId, StoredCard.fromEngine(card), fresh.currentRound)
      val localCardLog = fresh.cardLog + newEntry
      if (preview.nextPhase == TablePhase.RESOLVING) {
        nextTurnUid = resolveFourthCardWinnerUid(fresh, localCardLog)
      }
      val nextVersion = expectedVersion + 1
      tx.update(matchRef, mapOf(
        "cardLog" to FieldValue.arrayUnion(newEntry.toFields()),
        "lastCardSeat" to freshSeatId,
        "turn" to nextTurnUid,
        "cardPhase" to preview.nextPhase?.name,
        "version" to nextVersion,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(SubmitCardResult(
        matchId = matchId, seatId = freshSeatId, version = nextVersion,
        cardCount = localCardLog.size,
        nextTurnSeat = preview.nextTurnSeat, cardPhase = preview.nextPhase?.name,
      ))
    }
  }

  /**
   * publishOpeningTurnIfNeeded — a fresh round's `turn`/`cardPhase` are
   * null until the engine-selected opening actor publishes itself; the
   * card ruleset rejects any write whose OLD turn is null, so this
   * separate atomic publication must precede it (isValidOpeningTurnPublication
   * accepts exactly the null->PLAY or Round-1-dealer-window transition).
   * Structural bridge only — it decides neither bidding nor card legality.
   */
  private suspend fun publishOpeningTurnIfNeeded(
    matchId: String,
    match: MatchDoc,
    seatId: String,
  ): MatchDoc {
    val needsPublish = match.turn == null || match.isRoundOneOpeningWindow
    if (!needsPublish) return match
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val fresh = readMatch(tx, matchRef) ?: return@runTx noMatchTx("submitCard")
      if (fresh.turn != null && !fresh.isRoundOneOpeningWindow) {
        return@runTx TxOutcome.Ok(fresh)
      }
      val openingUid = adapter.seatToUid(fresh, seatId)
        ?: return@runTx TxOutcome.Err(ServiceException(UNKNOWN_OPENING_SEAT,
          "submitCard: the engine opening seat ('$seatId') is not a real seat in this match."))
      tx.update(matchRef, mapOf(
        "turn" to openingUid,
        "cardPhase" to MatchDoc.CARD_PHASE_PLAY,
        "version" to fresh.version + 1,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(fresh.copy(
        turn = openingUid,
        cardPhase = MatchDoc.CARD_PHASE_PLAY,
        version = fresh.version + 1,
      ))
    }
  }

  /**
   * The fourth-card resolving boundary (Sprint J.1): the winner is derived
   * from the FRESH in-transaction cardLog and the current local engine
   * context via the pure engine's trickWinner(); no winner algorithm is
   * duplicated here, and the persisted turn stays a real seat owner as
   * the card ruleset requires. Client-side computation under the existing
   * trust boundary — the rules enforce the write shape and prior-turn
   * ownership, not the winner's correctness.
   */
  private fun resolveFourthCardWinnerUid(
    match: MatchDoc,
    localCardLog: List<CardLogEntry>,
  ): String {
    val table = session.getPlayState()
    val lastFour = localCardLog.takeLast(4)
    if (table == null || lastFour.size != 4) {
      throw ServiceException(TRICK_WINNER_UNAVAILABLE,
        "submitCard: the resolving trick has no complete four-card engine context.")
    }
    val plays = lastFour.map { entry ->
      Play(entry.seatId, entry.card.toEngineCard()
        ?: throw ServiceException(TRICK_WINNER_UNAVAILABLE,
          "submitCard: the resolving trick has no complete four-card engine context."))
    }
    val ledSuit = table.ledSuit ?: plays.first().card.suit
    val winnerSeat = trickWinner(table.cfg.trump, ledSuit, plays)
    return adapter.seatToUid(match, winnerSeat)
      ?: throw ServiceException(TRICK_WINNER_UNAVAILABLE,
        "submitCard: the table engine did not return a real winner seat.")
  }

  // ── 4. advanceToNextRound: archive + window reset + dealer rotation ─

  /**
   * advanceToNextRound(matchId, completedRound) — archives this round's
   * log window to roundArchive/{round}, then resets the parent windows to
   * [] and rotates the dealer, all atomically. Idempotent: a race loser
   * re-reads committed state and takes ALREADY_ADVANCED without writing.
   * A match already complete is a no-op, never a corruption.
   */
  suspend fun advanceToNextRound(matchId: String, completedRound: Int): AdvanceResult {
    require(matchId.isNotEmpty()) { "advanceToNextRound: matchId is required." }
    requireCompletedRound("advanceToNextRound", completedRound)
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "advanceToNextRound: no signed-in user.")
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val match = readMatch(tx, matchRef) ?: return@runTx noMatchTx("advanceToNextRound")
      if (!match.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "advanceToNextRound: you are not a player in this match."))
      }
      if (match.isComplete) {
        return@runTx TxOutcome.Ok(AdvanceResult.alreadyComplete(matchId, match.currentRound))
      }
      if (match.currentRound != completedRound) {
        return@runTx TxOutcome.Ok(AdvanceResult.alreadyAdvanced(matchId, match.currentRound))
      }
      val roundCardCount = match.roundCardCount(completedRound)
      if (roundCardCount != ROUND_CARD_TOTAL) {
        return@runTx TxOutcome.Err(ServiceException(ROUND_NOT_COMPLETE,
          "advanceToNextRound: round $completedRound has only $roundCardCount/$ROUND_CARD_TOTAL recorded card plays — not advancing."))
      }
      tx.set(
        matchRef.collection("roundArchive").document(completedRound.toString()),
        RoundArchiveDoc(
          round = completedRound,
          matchId = matchId,
          cardLog = match.roundCards(completedRound),
          biddingLog = match.roundBids(completedRound),
        ).toFields(),
      )
      val nextVersion = match.version + 1
      tx.update(matchRef, mapOf(
        "currentRound" to completedRound + 1,
        "dealer" to nextDealerUid(match.seats, match.dealer),
        "version" to nextVersion,
        "biddingOpen" to true,
        "bids" to match.seats.keys.associateWith { null },
        "lastBidSeat" to null,
        "turn" to null,
        "cardPhase" to null,
        "cardLog" to emptyList<CardLogEntry>(),
        "biddingLog" to emptyList<BiddingLogEntry>(),
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(AdvanceResult(
        advanced = true, reason = null, matchId = matchId,
        previousRound = completedRound, currentRound = completedRound + 1,
        archivedRound = completedRound, version = nextVersion,
      ))
    }
  }

  // ── 5. extendMatchRounds: the ONE place maxRounds ever increments ──

  /**
   * extendMatchRounds(matchId, completedRound, reason) — idempotent on
   * extendedRounds (NOT on currentRound: a round's extension and its
   * advancement are independent events referencing the same round, so
   * they need independent guards). Structural only: it verifies the round
   * is a real Rapid Round and the reason is defined; it CANNOT verify a
   * Super Call or Sa'ayda actually occurred (that is the caller's
   * engine-derived fact, the same trust boundary advance uses).
   */
  suspend fun extendMatchRounds(
    matchId: String,
    completedRound: Int,
    reason: String,
  ): ExtendResult {
    require(matchId.isNotEmpty()) { "extendMatchRounds: matchId is required." }
    if (!isRapidRound(completedRound)) {
      throw ServiceException(INVALID_ARGUMENT,
        "extendMatchRounds: completedRound must be an integer between $RAPID_ROUND_MIN and $RAPID_ROUND_MAX.")
    }
    if (reason !in VALID_EXTENSION_REASONS) {
      throw ServiceException(INVALID_ARGUMENT,
        "extendMatchRounds: reason must be one of ${VALID_EXTENSION_REASONS.joinToString("/")}.")
    }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "extendMatchRounds: no signed-in user.")
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val match = readMatch(tx, matchRef) ?: return@runTx noMatchTx("extendMatchRounds")
      if (!match.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "extendMatchRounds: you are not a player in this match."))
      }
      if (match.isComplete) {
        return@runTx TxOutcome.Ok(ExtendResult.alreadyComplete(matchId, match.maxRounds))
      }
      if (completedRound in match.extendedRounds) {
        return@runTx TxOutcome.Ok(ExtendResult.alreadyExtended(matchId, match.maxRounds))
      }
      val nextVersion = match.version + 1
      tx.update(matchRef, mapOf(
        "maxRounds" to match.maxRounds + 1,
        "extendedRounds" to match.extendedRounds + completedRound,
        "version" to nextVersion,
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(ExtendResult(
        extended = true, reason = reason, matchId = matchId,
        completedRound = completedRound, maxRounds = match.maxRounds + 1,
        version = nextVersion,
      ))
    }
  }

  // ── 6. endMatch: the terminal transition ───────────────────────────

  /**
   * endMatch(matchId, completedRound, finalScores, winnerIds) — archives
   * the final round, then writes status:complete with the client-computed
   * scores. [finalScores] is keyed by SEAT ID and [winnerIds] must be
   * exactly the highest-score seat(s) — the one self-consistency check
   * possible without re-deriving the score, re-verified by the rules'
   * isExactWinnerTieSet(). The match must be at or past maxRounds; a
   * pending extension for this round means MATCH_NOT_OVER and the caller
   * must extend first.
   */
  suspend fun endMatch(
    matchId: String,
    completedRound: Int,
    finalScores: Map<String, Int>,
    winnerIds: List<String>,
  ): EndMatchResult {
    require(matchId.isNotEmpty()) { "endMatch: matchId is required." }
    requireCompletedRound("endMatch", completedRound)
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "endMatch: no signed-in user.")
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val match = readMatch(tx, matchRef) ?: return@runTx noMatchTx("endMatch")
      if (!match.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "endMatch: you are not a player in this match."))
      }
      if (match.isComplete) {
        return@runTx TxOutcome.Ok(EndMatchResult.alreadyComplete(
          matchId, match.winnerIds.orEmpty(), match.finalScores.orEmpty(),
        ))
      }
      if (match.currentRound != completedRound) {
        return@runTx TxOutcome.Ok(EndMatchResult.alreadyAdvanced(matchId, match.currentRound))
      }
      val roundCardCount = match.roundCardCount(completedRound)
      if (roundCardCount != ROUND_CARD_TOTAL) {
        return@runTx TxOutcome.Err(ServiceException(ROUND_NOT_COMPLETE,
          "endMatch: round $completedRound has only $roundCardCount/$ROUND_CARD_TOTAL recorded card plays — not completing."))
      }
      if (completedRound + 1 <= match.maxRounds) {
        return@runTx TxOutcome.Ok(
          EndMatchResult.notOver(matchId, match.currentRound, match.maxRounds),
        )
      }
      if (!winnerIdsMatchFinalScores(finalScores, winnerIds, match.seats)) {
        return@runTx TxOutcome.Err(ServiceException(INVALID_RESULT,
          "endMatch: winnerIds does not match the highest score(s) in finalScores."))
      }
      tx.set(
        matchRef.collection("roundArchive").document(completedRound.toString()),
        RoundArchiveDoc(
          round = completedRound,
          matchId = matchId,
          cardLog = match.roundCards(completedRound),
          biddingLog = match.roundBids(completedRound),
        ).toFields(),
      )
      val nextVersion = match.version + 1
      tx.update(matchRef, mapOf(
        "status" to MatchDoc.STATUS_COMPLETE,
        "winnerIds" to winnerIds.toList(),
        "finalScores" to finalScores.toMap(),
        "completedRound" to completedRound,
        "version" to nextVersion,
        "cardLog" to emptyList<CardLogEntry>(),
        "biddingLog" to emptyList<BiddingLogEntry>(),
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(EndMatchResult(
        complete = true, reason = null, matchId = matchId,
        completedRound = completedRound, archivedRound = completedRound,
        winnerIds = winnerIds.toList(), finalScores = finalScores.toMap(),
        version = nextVersion,
      ))
    }
  }

  // ── 7. dealRound: the ONE authoritative dealing transaction ────────

  /**
   * dealRound(matchId, roundNumber) — deals exactly one round, exactly
   * once: whichever client commits first wins, every other attempt reads
   * the committed gameState.dealtRound and no-ops. Card CONTENT is never
   * trusted from a client — [dealer] runs inside this transaction and its
   * result is split by seat into hands/{seatId}, readable only by that
   * seat's uid. The rules require each hand to be exactly 13 cards and
   * this round to be the match's current round.
   */
  suspend fun dealRound(matchId: String, roundNumber: Int): DealResult {
    require(matchId.isNotEmpty()) { "dealRound: matchId is required." }
    requireCompletedRound("dealRound", roundNumber)
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "dealRound: no signed-in user.")
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val match = readMatch(tx, matchRef) ?: return@runTx noMatchTx("dealRound")
      if (!match.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "dealRound: you are not a player in this match."))
      }
      if (match.gameState.dealtRound >= roundNumber) {
        return@runTx TxOutcome.Ok(DealResult.alreadyDealt(matchId, match.gameState.dealtRound))
      }
      val seatIds = SEAT_IDS.filter { match.seats[it] != null }
      val hands = dealer.deal(matchId, roundNumber, seatIds)
      for (seatId in seatIds) {
        tx.set(
          matchRef.collection("hands").document(seatId),
          HandDoc(seatId, roundNumber, hands[seatId].orEmpty().map { StoredCard.fromEngine(it) })
            .toFields(),
        )
      }
      tx.update(matchRef, mapOf(
        "gameState" to GameState(initialized = true, dealtRound = roundNumber).toFields(),
        "updatedAt" to FieldValue.serverTimestamp(),
      ))
      TxOutcome.Ok(DealResult(
        dealt = true, reason = null, matchId = matchId,
        dealtRound = roundNumber, seats = seatIds,
      ))
    }
  }

  // ── 8. rematch: create vote → cast votes → create the next match ────

  /**
   * createRematchVote(matchId) — the post-match vote machine's seed: one
   * doc at rematchVote/current, seats copied verbatim from the completed
   * match, every vote null. Idempotent: an existing vote is returned,
   * never recreated. The 30s deadline is derived from createdAt's real
   * serverTimestamp(), never a client clock.
   */
  suspend fun createRematchVote(matchId: String): VoteCreateResult {
    require(matchId.isNotEmpty()) { "createRematchVote: matchId is required." }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "createRematchVote: no signed-in user.")
    val matchRef = matches.document(matchId)
    val voteRef = matchRef.collection("rematchVote").document("current")
    return runTx(db) { tx ->
      val matchSnap = tx.getBlocking(matchRef)
      if (!matchSnap.exists()) {
        return@runTx TxOutcome.Err(
          ServiceException(MATCH_NOT_FOUND, "createRematchVote: match '$matchId' was not found."),
        )
      }
      val match = MatchDoc.fromFields(matchSnap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(
          ServiceException(MATCH_NOT_FOUND, "createRematchVote: match '$matchId' was not found."),
        )
      if (!match.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "createRematchVote: you are not a player in this match."))
      }
      if (!match.isComplete) {
        return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_COMPLETE,
          "createRematchVote: match '$matchId' has not completed yet."))
      }
      val existing = tx.getBlocking(voteRef)
      if (existing.exists()) {
        val vote = VoteDoc.fromFields(existing.data ?: emptyMap())
        return@runTx TxOutcome.Ok(
          VoteCreateResult(created = false, matchId = matchId, vote = vote),
        )
      }
      val vote = VoteDoc(
        matchId = matchId,
        seats = match.seats.toMap(),
        votes = match.seats.keys.associateWith { null },
        status = VoteDoc.STATUS_OPEN,
        newMatchId = null,
        version = 1,
        createdAt = FieldValue.serverTimestamp(),
      )
      tx.set(voteRef, vote.toFields())
      TxOutcome.Ok(VoteCreateResult(created = true, matchId = matchId, vote = vote))
    }
  }

  /**
   * submitRematchVote(matchId, choice) — casts exactly one seat's vote.
   * LOCKED once cast: a duplicate of the same value is an idempotent
   * no-op (ALREADY_VOTED, no write); a conflicting second value for an
   * already-cast seat is VOTE_LOCKED, never silently applied. A "NO"
   * fails the rematch immediately in this same write; a "YES" that
   * completes every real seat moves status to ALL_YES but does NOT itself
   * create the next match (createRematchMatch does, separately). The
   * deadline pre-check here is a fast mirror of the rules' own
   * request.time check — never the authority.
   */
  suspend fun submitRematchVote(matchId: String, choice: String): VoteSubmitResult {
    require(matchId.isNotEmpty()) { "submitRematchVote: matchId is required." }
    if (choice !in VoteDoc.VOTE_VALUES) {
      throw ServiceException(INVALID_ARGUMENT,
        "submitRematchVote: choice must be one of ${VoteDoc.VOTE_VALUES.joinToString("/")}.")
    }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "submitRematchVote: no signed-in user.")
    val voteRef = matches.document(matchId).collection("rematchVote").document("current")
    return runTx(db) { tx ->
      val snap = tx.getBlocking(voteRef)
      if (!snap.exists()) {
        return@runTx TxOutcome.Err(ServiceException(VOTE_NOT_FOUND,
          "submitRematchVote: no rematch vote exists for match '$matchId' — call createRematchVote() first."))
      }
      val vote = VoteDoc.fromFields(snap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(ServiceException(VOTE_NOT_FOUND,
          "submitRematchVote: no rematch vote exists for match '$matchId'."))
      // Resolve the acting seat from the vote's OWN parent-derived seats
      // map — never a client-supplied seat id.
      val actingSeat = vote.seatForUid(callingUid)
        ?: return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "submitRematchVote: you do not own a seat in this match's rematch vote."))
      if (vote.status != VoteDoc.STATUS_OPEN) {
        return@runTx TxOutcome.Ok(VoteSubmitResult(
          accepted = false, reason = VOTE_CLOSED, matchId = matchId, seatId = actingSeat,
          status = vote.status,
        ))
      }
      val createdAt = vote.createdAtMillis
      if (createdAt != null && now() > createdAt + REMATCH_VOTE_DURATION_SECONDS * MILLIS_PER_SECOND) {
        return@runTx TxOutcome.Ok(VoteSubmitResult(
          accepted = false, reason = VOTE_EXPIRED, matchId = matchId, seatId = actingSeat,
        ))
      }
      val existingChoice = vote.votes[actingSeat]
      if (existingChoice != null) {
        return@runTx TxOutcome.Ok(
          if (existingChoice == choice) {
            VoteSubmitResult(accepted = true, reason = ALREADY_VOTED, matchId = matchId,
              seatId = actingSeat, choice = choice)
          } else {
            VoteSubmitResult(accepted = false, reason = VOTE_LOCKED, matchId = matchId,
              seatId = actingSeat, existing = existingChoice)
          },
        )
      }
      val newVotes = vote.votes + (actingSeat to choice)
      val newStatus = when {
        choice == VoteDoc.VOTE_VALUES[1] -> VoteDoc.STATUS_FAILED_NO
        vote.seats.keys.all { newVotes[it] == VoteDoc.VOTE_VALUES[0] } -> VoteDoc.STATUS_ALL_YES
        else -> vote.status
      }
      val nextVersion = vote.version + 1
      // No updatedAt: the vote rules whitelist exactly {votes, status, version}.
      tx.update(voteRef, mapOf(
        "votes" to newVotes,
        "status" to newStatus,
        "version" to nextVersion,
      ))
      TxOutcome.Ok(VoteSubmitResult(
        accepted = true, reason = RECORDED,
        matchId = matchId, seatId = actingSeat, choice = choice,
        status = newStatus, version = nextVersion,
      ))
    }
  }

  /**
   * resolveRematchVoteTimeout(matchId) — any seated client may attempt
   * this once its own clock suggests the window has passed; that local
   * judgment is only ever an optimization for WHEN to try, never the
   * authority for WHETHER. The transaction re-reads createdAt and the
   * rules independently re-derive the deadline from request.time.
   * Idempotent: an already-terminal vote is ALREADY_RESOLVED, no write.
   */
  suspend fun resolveRematchVoteTimeout(matchId: String): VoteTimeoutResult {
    require(matchId.isNotEmpty()) { "resolveRematchVoteTimeout: matchId is required." }
    auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "resolveRematchVoteTimeout: no signed-in user.")
    val voteRef = matches.document(matchId).collection("rematchVote").document("current")
    return runTx(db) { tx ->
      val snap = tx.getBlocking(voteRef)
      if (!snap.exists()) {
        return@runTx TxOutcome.Err(ServiceException(VOTE_NOT_FOUND,
          "resolveRematchVoteTimeout: no rematch vote exists for match '$matchId'."))
      }
      val vote = VoteDoc.fromFields(snap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(ServiceException(VOTE_NOT_FOUND,
          "resolveRematchVoteTimeout: no rematch vote exists for match '$matchId'."))
      if (vote.status != VoteDoc.STATUS_OPEN) {
        return@runTx TxOutcome.Ok(VoteTimeoutResult(
          resolved = false, reason = ALREADY_RESOLVED, matchId = matchId, status = vote.status,
        ))
      }
      val createdAt = vote.createdAtMillis
      if (createdAt == null) {
        // The sentinel is still pending on this client's own cache — never
        // guess a deadline; wait for a real server-round-tripped read.
        return@runTx TxOutcome.Ok(VoteTimeoutResult(
          resolved = false, reason = DEADLINE_UNKNOWN, matchId = matchId,
        ))
      }
      val deadline = createdAt + REMATCH_VOTE_DURATION_SECONDS * MILLIS_PER_SECOND
      if (now() < deadline) {
        return@runTx TxOutcome.Ok(VoteTimeoutResult(
          resolved = false, reason = NOT_YET_EXPIRED, matchId = matchId,
        ))
      }
      val nextVersion = vote.version + 1
      tx.update(voteRef, mapOf(
        "status" to VoteDoc.STATUS_FAILED_TIMEOUT,
        "version" to nextVersion,
      ))
      TxOutcome.Ok(VoteTimeoutResult(
        resolved = true, reason = null, matchId = matchId,
        status = VoteDoc.STATUS_FAILED_TIMEOUT, version = nextVersion,
      ))
    }
  }

  /**
   * createRematchMatch(matchId) — any seated client may attempt this once
   * it observes ALL_YES; race-safe by construction: whichever transaction
   * commits first creates the new match AND links it in ONE atomic
   * transaction, every simultaneous attempt re-reads newMatchId and
   * idempotently returns the EXISTING id. seats/players are copied
   * verbatim from the vote's own seats map — never from a client list —
   * so "same seats, same assignments" is structural, not a trust
   * assumption. The OLD match document is read for eligibility only.
   */
  suspend fun createRematchMatch(matchId: String): com.estemshan.services.model.RematchCreateResult {
    require(matchId.isNotEmpty()) { "createRematchMatch: matchId is required." }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "createRematchMatch: no signed-in user.")
    val oldMatchRef = matches.document(matchId)
    val voteRef = oldMatchRef.collection("rematchVote").document("current")
    return runTx(db) { tx ->
      val voteSnap = tx.getBlocking(voteRef)
      if (!voteSnap.exists()) {
        return@runTx TxOutcome.Err(ServiceException(VOTE_NOT_FOUND,
          "createRematchMatch: no rematch vote exists for match '$matchId'."))
      }
      val vote = VoteDoc.fromFields(voteSnap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(ServiceException(VOTE_NOT_FOUND,
          "createRematchMatch: no rematch vote exists for match '$matchId'."))
      vote.newMatchId?.let { existing ->
        return@runTx TxOutcome.Ok(
          RematchCreateResult(
            created = false, matchId = matchId, newMatchId = existing,
          ),
        )
      }
      if (vote.status != VoteDoc.STATUS_ALL_YES) {
        return@runTx TxOutcome.Ok(RematchCreateResult(
          created = false, matchId = matchId, reason = NOT_ALL_YES, status = vote.status,
        ))
      }
      val oldMatchSnap = tx.getBlocking(oldMatchRef)
      if (!oldMatchSnap.exists()) {
        return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_FOUND,
          "createRematchMatch: original match '$matchId' was not found."))
      }
      val oldMatch = MatchDoc.fromFields(oldMatchSnap.data ?: emptyMap())
        ?: return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_FOUND,
          "createRematchMatch: original match '$matchId' was not found."))
      if (!oldMatch.isComplete) {
        return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_COMPLETE,
          "createRematchMatch: original match '$matchId' is not complete."))
      }
      if (!oldMatch.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "createRematchMatch: you are not a player in this match."))
      }
      val newMatchRef = matches.document()
      tx.set(newMatchRef, buildRematchMatchFields(
        roomId = oldMatch.roomId,
        seats = vote.seats.toMap(),
        oldDealerFallback = oldMatch.dealer,
        serverTimestamp = FieldValue.serverTimestamp(),
        rematchOfMatchId = matchId,
      ))
      tx.update(voteRef, mapOf(
        "status" to VoteDoc.STATUS_NEW_MATCH_CREATED,
        "newMatchId" to newMatchRef.id,
        "version" to vote.version + 1,
      ))
      TxOutcome.Ok(RematchCreateResult(
        created = true, matchId = matchId, newMatchId = newMatchRef.id,
      ))
    }
  }

  // ── shared plumbing ────────────────────────────────────────────────

  /** In-transaction read; null when the match is gone. */
  private fun readMatch(tx: Transaction, ref: com.google.firebase.firestore.DocumentReference): MatchDoc? {
    val snap = tx.getBlocking(ref)
    if (!snap.exists()) return null
    return snap.data?.let { MatchDoc.fromFields(it) }
  }

  /** The one MISSING_MATCH outcome shape, for the no-op/Err boundary. */
  private fun <T> noMatchTx(method: String): TxOutcome<T> =
    TxOutcome.Err(ServiceException(MATCH_NOT_FOUND, "$method: match was not found."))

  private fun requireCompletedRound(method: String, completedRound: Int) {
    require(completedRound >= 1) { "$method: completedRound must be a positive integer." }
  }

  private suspend fun syncProfileMatch(uid: String, matchId: String?) {
    try {
      profileMatchSync(uid, matchId)
    } catch (err: Throwable) {
      // Non-fatal: the match write itself already succeeded.
    }
  }

  /**
   * Translates a stored action into the exact BiddingIntent shape the
   * engine's emit()/canSubmit() accept (match-service.js
   * biddingActionToIntent()) — actionType IS the engine's own intent
   * type string, reused verbatim, not a parallel vocabulary to keep in
   * sync. null when the generic shape check should already have caught it.
   */
  private fun biddingActionToIntent(seatId: String, action: BiddingActionInput): BiddingIntent? =
    when (action.actionType) {
      BiddingLogEntry.ACTION_DASH_CALL -> {
        val declared = action.declaredDashCall ?: return null
        BiddingIntent.DashCallDecision(seatId, declared)
      }
      BiddingLogEntry.ACTION_AUCTION_BID -> {
        val isPass = action.isPass ?: return null
        if (isPass) {
          BiddingIntent.AuctionBid(seatId, isPass = true)
        } else {
          BiddingIntent.AuctionBid(
            seatId, isPass = false,
            tricks = action.tricks ?: return null,
            suit = parseSuit(action.suit) ?: return null,
          )
        }
      }
      BiddingLogEntry.ACTION_CONFIRM_CALL -> BiddingIntent.ConfirmCall(
        seatId,
        action.tricks ?: return null,
        parseSuit(action.suit) ?: return null,
      )
      else -> null
    }

  private fun parseSuit(name: String?): Suit? =
    if (name == null) null else runCatching { Suit.valueOf(name) }.getOrNull()
}

/**
 * Dealing seam: 13 cards per present seat, one card at a time in seat
 * order (mirrors design-ui/engine/dealer.js). Every call must produce a
 * fresh, unique deal; [Random] is the production default and tests inject
 * a seeded variant for determinism.
 */
fun interface Deal {
  fun deal(matchId: String, round: Int, seatIds: List<String>): Map<String, List<Card>>

  /** Unseeded production deal — a fresh shuffle every call, like dealer.js. */
  object Random : Deal {
    override fun deal(matchId: String, round: Int, seatIds: List<String>): Map<String, List<Card>> =
      dealWithSeed(kotlin.random.Random.Default.nextLong(), seatIds)
  }

  companion object {
    /** A deterministic deal for tests; the seed is caller-chosen. */
    fun seeded(seed: Long): Deal = Deal { _, _, seatIds -> dealWithSeed(seed, seatIds) }

    private fun dealWithSeed(seed: Long, seatIds: List<String>): Map<String, List<Card>> {
      if (seatIds.isEmpty()) return emptyMap()
      val deck = Deck(seed).shuffle()
      val hands = seatIds.associateWith { mutableListOf<Card>() }
      repeat(HAND_SIZE) {
        for (seat in seatIds) {
          hands.getValue(seat).add(deck.draw().copy(owner = seat))
        }
      }
      return hands.mapValues { (_, cards) -> cards.sortedWith(::compareForSort) }
    }
  }
}

/** firestore.rules requires exactly 13 cards per hand doc. */
private const val HAND_SIZE = 13
private const val MILLIS_PER_SECOND = 1000L
