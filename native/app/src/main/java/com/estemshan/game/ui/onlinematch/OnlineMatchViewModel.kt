package com.estemshan.game.ui.onlinematch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Card
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.HandAuthority
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundResult
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.RoundScoreResult
import com.estemshan.engine.RoundState
import com.estemshan.engine.SessionPlayer
import com.estemshan.engine.SessionRoom
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.computeRoundExtension
import com.estemshan.engine.computeWinner
import com.estemshan.engine.emit
import com.estemshan.engine.forbiddenEstimateFor
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.isFastRound
import com.estemshan.engine.restoreHand
import com.estemshan.engine.withFloorFor
import com.estemshan.game.data.OnlineServices
import com.estemshan.game.ui.quickmatch.bidsFromOutcome
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.services.MatchAdapter
import com.estemshan.services.MatchSnapshot
import com.estemshan.services.MatchSnapshotListener
import com.estemshan.services.MatchSubscriptionHandle
import com.estemshan.services.Heartbeat
import com.estemshan.services.PlayerPort
import com.estemshan.services.ROUND_CARD_TOTAL
import com.estemshan.services.model.BiddingActionInput
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.session.MatchStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OnlineMatchViewModel — the online match's single local driver. One per
 * online nav-graph entry, and the ONLY writer to [OnlineServices.session]'s
 * engines for a match this process is in: every card, bid and auction
 * transition arrives as a [MatchSnapshot] from [OnlineServices.adapter] and
 * is converged into engine state here, and every local action leaves as a
 * [MatchStore] write whose echo comes back through that same snapshot.
 *
 * THE INVARIANT — the engines have exactly one writer. [submitBidding],
 * [playCard] and [resolve] never touch an engine directly; they only write
 * to Firestore. Local engine state is produced exclusively by the adapter's
 * interpreters (the adapter's own pre-pass inside onSnapshot, plus this
 * class's idempotent re-drive in [handleSnapshot]). That is what makes "the
 * same snapshot twice must not double-apply" a structural property rather
 * than a discipline: both passes are registry-gated (version/count/round-
 * tag), so whichever one lands the application, the other is a no-op.
 *
 * WHY THE RE-DRIVE EXISTS — the adapter replays a document into the engines
 * BEFORE it invokes listeners, so the FIRST delivery of a cold-started match
 * hits engines that do not exist yet: every interpreter returns
 * ENGINE_UNAVAILABLE and leaves every registry untouched. [alignToDoc]
 * builds the engines from the document, then the re-drive applies that same
 * document through the now-live interpreters. On every delivery after the
 * first the engines already exist, so the adapter's own pass applies and the
 * re-drive is the no-op. This is the consumer's half of the reload contract
 * MatchAdapterReloadReplayTest CASE B pins (resetSyncState → clear → reseed
 * → re-init → replay from index 0).
 *
 * COLD-START LIMITATIONS, stated rather than papered over: the match
 * document carries the round, seats, dealer and maxRounds, but NOT the
 * Sa'ayda multiplier or the accumulated totals of rounds scored before this
 * client joined. A client that reconnects mid-escalation therefore resumes
 * the exact trick (the log replays fully) but scores the remaining rounds
 * from a ×1 multiplier and from the totals it witnesses from here on. The
 * authoritative totals at [MatchUiState.MatchComplete] always come from the
 * document's own finalScores, so a completed match is never mis-shown; only
 * the in-flight window can be partial. Restoring the multiplier and prior
 * totals needs a roundArchive read, which is not on [MatchStore] and so is
 * not part of this story.
 */
class OnlineMatchViewModel(
  private val session: GameSession = OnlineServices.session,
  private val adapter: MatchAdapter = OnlineServices.adapter,
  private val store: MatchStore = OnlineServices.matches,
  private val players: PlayerPort = OnlineServices.players,
  private val uid: () -> String? = { OnlineServices.auth.currentUid() },
  private val worker: CoroutineDispatcher = Dispatchers.Default,
  private val clock: () -> Long = System::currentTimeMillis,
  private val heartbeat: Heartbeat = OnlineServices.heartbeat,
) : ViewModel() {

  private val _state = MutableStateFlow<MatchUiState>(MatchUiState.Connecting)
  val state: StateFlow<MatchUiState> = _state.asStateFlow()

  /**
   * Fail-open reconnect signal: true while the listener has errored but a
   * last-known-good document is still being played. The overlay it drives
   * never blocks input — the local game keeps what it has.
   */
  private val _reconnecting = MutableStateFlow(false)
  val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

  /**
   * S19's passive staleness hint — "opponent appears away". True only while an
   * OPPONENT holds the turn and the match document has not progressed past the
   * tracker's threshold. A hint, never a game state: it blocks no input and
   * times nobody out, and it can never be more than "appears away" because the
   * frozen rules make true presence impossible (players/{uid} is
   * owner-read-only, `list: if false`). It reflects this client's observation
   * of the document — nothing more.
   */
  private val _opponentAway = MutableStateFlow(false)
  val opponentAway: StateFlow<Boolean> = _opponentAway.asStateFlow()

  /**
   * S19's staleness derivation — document progress + the local clock. Pure:
   * the only timer state lives in the alarm below.
   */
  private val awayTracker = OpponentAwayTracker(clock = clock)

  /**
   * The ONE pending staleness alarm. An away opponent sends no snapshots, so
   * the indicator would never flip without it; each delivery reschedules it,
   * and any progress cancels it.
   */
  private var awayAlarm: Job? = null

  /** The running lastSeenAt cadence, dropped with the binding. */
  private var heartbeatJob: Job? = null

  /** The match this VM is bound to; null while unbound. */
  private var matchId: String? = null
  private var handle: MatchSubscriptionHandle? = null

  /**
   * Our seat, resolved from the document. The adapter needs it to tell an
   * observed card from an echoed one — emitPlay() rejects any card whose
   * seat holds no hand, so without the hint an opponent's play during the
   * adapter's own pre-pass would be a desync instead of a seeded play.
   */
  private var boundSeat: String? = null

  /** The last snapshot handled, kept so [resolve] can re-enter the trick. */
  private var lastDoc: MatchDoc? = null

  /** The auction outcome that closed the current round's bidding. */
  private var lastOutcome: BiddingOutcome? = null

  /** The round [scoreAndAdvance] has already banked; guards a double score. */
  private var scoredRound: Int? = null

  /** currentMatchId cleared once for a completed match. */
  private var clearedMatchId: Boolean = false

  /** Inline rejection text, and the document version it was raised against. */
  private var rejection: String? = null
  private var rejectionVersion: Int? = null

  /**
   * Snapshots arrive on the listener's thread and may stack (a reconnect that
   * missed several); every one is funneled through this channel and consumed
   * by ONE collector, so the engine writes are strictly serialized and no
   * two snapshots interleave their bootstrap/replay stages.
   */
  private val inbox = Channel<MatchSnapshot>(Channel.UNLIMITED)

  /** One local submission in flight at a time: keeps double-taps ordered. */
  private val submitMutex = Mutex()

  private val listener = MatchSnapshotListener { snapshot -> inbox.trySend(snapshot) }

  // ── Lifecycle ───────────────────────────────────────────────────────

  /** The collector's lifetime, so rebinding replaces it rather than racing it. */
  private var collectorJob: Job? = null

  /**
   * Bind to [matchId] and start converging. Idempotent for the same matchId;
   * a different one tears the previous subscription down first. Our seat is
   * resolved from the first delivery and patched into the adapter by
   * [bindSeat] — it cannot be known before one arrives, and it cannot be
   * needed before one either: the adapter's own pre-pass on that first
   * delivery finds no engines to write into.
   */
  fun start(matchId: String) {
    if (matchId.isEmpty()) {
      _state.value = MatchUiState.Failed("No match to open.")
      return
    }
    if (this.matchId == matchId && handle != null) return
    stop()
    this.matchId = matchId
    _state.value = MatchUiState.Connecting
    _reconnecting.value = false
    lastDoc = null
    lastOutcome = null
    scoredRound = null
    clearedMatchId = false
    rejection = null
    rejectionVersion = null
    handle = adapter.subscribeToMatch(matchId, listener, localSeatId = null)

    // S19: the one activity signal the frozen rules let a player emit, on
    // their OWN doc. Starts with the binding and drops with it.
    heartbeatJob?.cancel()
    val myUid = uid()
    if (myUid != null) heartbeatJob = heartbeat.start(viewModelScope, myUid)

    collectorJob?.cancel()
    collectorJob = viewModelScope.launch(worker) {
      for (snapshot in inbox) {
        // A throw in one snapshot must not starve the ones behind it; the
        // states set inside are the only terminal ones.
        runCatching { handleSnapshot(snapshot) }
      }
    }
  }

  /** Unsubscribe and forget. Safe repeatedly; safe from onCleared. */
  fun stop() {
    collectorJob?.cancel()
    collectorJob = null
    handle?.unsubscribe()
    handle = null
    heartbeatJob?.cancel()
    heartbeatJob = null
    clearOpponentAway()
    matchId = null
    boundSeat = null
  }

  override fun onCleared() {
    super.onCleared()
    stop()
  }

  // ── Local actions: write to Firestore only; the echo writes the engines.

  /**
   * The Bidding screen's one intent channel. FinalEstimate goes to
   * [MatchStore.submitBid] (the one-value-per-seat field); Dash, Auction and
   * Confirm go to [MatchStore.submitBiddingAction] (the append-only log).
   * [BiddingActionInput] carries no seat — the services layer resolves it
   * from the signed-in user, so a caller cannot act for another seat.
   */
  fun submitBidding(intent: BiddingIntent) {
    val id = matchId ?: return
    val seat = boundSeat ?: return
    val version = lastDoc?.version
    viewModelScope.launch(worker) {
      submitMutex.withLock {
        val failure = runCatching {
          when (intent) {
            is BiddingIntent.FinalEstimate ->
              store.submitBid(id, seat, intent.tricks)
            is BiddingIntent.DashCallDecision ->
              store.submitBiddingAction(
                id,
                BiddingActionInput(
                  actionType = BiddingLogEntry.ACTION_DASH_CALL,
                  declaredDashCall = intent.declaredDashCall,
                ),
              )
            is BiddingIntent.AuctionBid ->
              store.submitBiddingAction(
                id,
                BiddingActionInput(
                  actionType = BiddingLogEntry.ACTION_AUCTION_BID,
                  isPass = intent.isPass,
                  tricks = intent.tricks,
                  suit = intent.suit?.name,
                ),
              )
            is BiddingIntent.ConfirmCall ->
              store.submitBiddingAction(
                id,
                BiddingActionInput(
                  actionType = BiddingLogEntry.ACTION_CONFIRM_CALL,
                  tricks = intent.tricks,
                  suit = intent.suit.name,
                ),
              )
          }
        }.exceptionOrNull()
        setRejection(failure?.message, version)
      }
    }
  }

  /**
   * The Table screen's play. Legality and turn are decided by the services
   * layer against the local engine before any write is attempted, so an
   * illegal tap is rejected without a transaction.
   */
  fun playCard(card: Card) {
    val id = matchId ?: return
    val version = lastDoc?.version
    viewModelScope.launch(worker) {
      submitMutex.withLock {
        val failure = runCatching { store.submitCard(id, card) }.exceptionOrNull()
        setRejection(failure?.message, version)
      }
    }
  }

  /**
   * The Table screen auto-calls this while the engine is RESOLVING. Online,
   * the trick is collected by the replay path itself; this is the idempotent
   * re-entry, gated by the adapter's resolvedTrickNo registry so a repeat
   * call is exactly a no-op.
   */
  fun resolve() {
    val id = matchId ?: return
    val doc = lastDoc ?: return
    adapter.applyRemoteTrick(id, doc)
  }

  // ── The snapshot pipeline: serialized, staged, idempotent ───────────

  private suspend fun handleSnapshot(snapshot: MatchSnapshot) {
    val doc = snapshot.match
    val error = snapshot.error

    // Fail-open: an error alongside a good document keeps the good document.
    _reconnecting.value = error != null && doc != null

    if (doc == null) {
      if (error != null) {
        // The cold-start gap. On a relaunch the FIRST Firestore callback can
        // be a transient error — a network blip exactly at launch — with no
        // document yet. The adapter has classified it RETRYABLE and scheduled
        // a reconnect with backoff, so the real document is still coming;
        // publishing Failed here would land the player on an error page
        // before it arrives, and the match is lost to a blip. Holding
        // Connecting covers that gap (it renders as the "Reconnecting…"
        // overlay) until the reconnect delivers. A TERMINAL error — a
        // permission denial, a genuinely unreadable match — still fails
        // honestly: fail-open must never become fail-silent.
        if (lastDoc == null && !snapshot.retryable) {
          _state.value = MatchUiState.Failed(error.message ?: "Match unavailable.")
        }
      } else {
        // Deleted, not loading — the match is gone.
        _state.value = MatchUiState.NotInMatch
        _reconnecting.value = false
      }
      clearOpponentAway()
      return
    }

    // A terminal match is published straight off the document, which is the
    // authority for the final totals — checked before the seat gate so a
    // player who backed out still sees how the table finished.
    if (doc.isComplete) {
      _reconnecting.value = false
      clearOpponentAway()
      publishComplete(doc)
      return
    }

    val myUid = uid()
    if (myUid == null) {
      _state.value = MatchUiState.Failed("Not signed in.")
      clearOpponentAway()
      return
    }
    val seat = adapter.uidToSeat(doc, myUid)
    if (seat == null) {
      _state.value = MatchUiState.NotInMatch
      _reconnecting.value = false
      clearOpponentAway()
      return
    }
    bindSeat(seat)

    // A rejection lives until the document moves past the version it was
    // raised against: our retry succeeding, or the table simply moving on.
    if (rejectionVersion != null && doc.version > (rejectionVersion ?: Int.MAX_VALUE)) {
      setRejection(null, null)
    }

    lastDoc = doc
    alignToDoc(doc, seat)
    ensureDealAndHand(doc, seat)
    replayBidding(snapshot, doc)
    ensureTable(doc, seat)
    replayCards(doc)
    maybeCompleteRound(doc)
    publish(doc, seat)
    refreshOpponentAway(doc, seat)
  }

  /**
   * Patch the adapter's local-seat hint once the document resolves it. The
   * second subscribe reuses the SAME underlying subscription (the adapter
   * ref-counts listeners per matchId) and only sets the seat; the first
   * handle is then dropped. The immediate re-delivery the late-join path
   * performs is one redundant, idempotent snapshot.
   */
  private fun bindSeat(seatId: String) {
    val id = matchId ?: return
    if (boundSeat == seatId) return
    boundSeat = seatId
    val prior = handle
    handle = adapter.subscribeToMatch(id, listener, localSeatId = seatId)
    prior?.unsubscribe()
  }

  /**
   * Bring the session's cross-round state to the document. Three cases, in
   * the order tested:
   *  1. a match this VM has never seen (or a different roster): full
   *     bootstrap — fresh engines in the P1-3 safe order, registries reset,
   *     the auction initialized for the document's own round;
   *  2. the document advanced one or more rounds without us: advance ours,
   *     keeping the totals and multiplier a fresh session would discard, and
   *     reset the registries because the new round's log window is empty;
   *  3. the same round: sync only the drift-able fields — maxRounds grows by
   *     extension, the dealer is whatever the document rotated to.
   */
  private fun alignToDoc(doc: MatchDoc, seat: String) {
    val seats = DEFAULT_SEATS.filter { doc.seats.containsKey(it) }
    if (seats.isEmpty()) return
    val dealerSeat = adapter.uidToSeat(doc, doc.dealer) ?: seat

    val known = session.getMode() == "online" &&
      session.getPlayers().mapNotNull { it.uid }.sorted() == doc.seats.values.sorted()

    if (!known) {
      bootstrap(doc, seats, dealerSeat)
      return
    }

    val current = session.getRound()
    if (current.number != doc.currentRound) {
      adapter.resetSyncState(matchId)
      while (session.getRound().number < doc.currentRound) session.nextRound()
      session.setDealer(dealerSeat)
      if (current.maxRounds != doc.maxRounds) {
        session.setRound(session.getRound().copy(maxRounds = doc.maxRounds))
      }
      lastOutcome = null
      scoredRound = null
      initAuction(doc, dealerSeat, seats)
      return
    }

    if (current.maxRounds != doc.maxRounds) session.setRound(current.copy(maxRounds = doc.maxRounds))
    if (session.getDealer() != dealerSeat) session.setDealer(dealerSeat)
  }

  /**
   * The cold-start bootstrap, in the order issue #16 proved safe: discard,
   * reseed authority, build engines, then replay (the replay is the caller's
   * next stage). [GameSession.init]'s `force` discards any stale local match;
   * [setHandAuthorityMode] wipes a locally-dealt hand so a stale one can
   * never be mistaken for an authoritative one.
   */
  private fun bootstrap(doc: MatchDoc, seats: List<String>, dealerSeat: String) {
    session.init("online", force = true)
    session.setHandAuthorityMode(HandAuthority.FIRESTORE)
    adapter.resetSyncState(matchId)
    session.setPlayers(
      doc.seats.entries.map { (seatId, playerUid) ->
        SessionPlayer(
          seatId = seatId,
          uid = playerUid,
          displayName = playerUid,
          isUser = playerUid == uid(),
          isRemote = playerUid != uid(),
        )
      },
    )
    session.setRoom(SessionRoom(code = doc.roomId, host = false, seats = seats))
    session.setRound(RoundState(number = doc.currentRound, maxRounds = doc.maxRounds))
    session.setDealer(dealerSeat)
    session.setMatchScores(seats.associateWith { 0 })
    lastOutcome = null
    scoredRound = null
    initAuction(doc, dealerSeat, seats)
  }

  /**
   * Fresh auction for the current round: the fixed-trump Estimates phase for
   * a Rapid/Fast round (14+), the full Dash→Auction→Confirm→Estimates flow
   * otherwise. The current multiplier is carried in — escalation survives.
   */
  private fun initAuction(doc: MatchDoc, dealerSeat: String, seats: List<String>) {
    val round = doc.currentRound
    val multiplier = session.getRound().multiplier
    session.clearBiddingState()
    session.clearPlayState()
    session.initializeBiddingState(
      if (isFastRound(round)) initFastRound(round, dealerSeat, seats, multiplier)
      else initNormalRound(round, dealerSeat, seats, multiplier),
    )
  }

  /**
   * The authoritative deal. [MatchStore.dealRound] is the ONE transaction
   * that writes hands, and it is idempotent — whichever seat commits first
   * wins, every other attempt reads the committed dealtRound and no-ops — so
   * this client attempting it is safe regardless of what the other three do.
   * Our own hand is then seeded from the doc only it may read.
   */
  private suspend fun ensureDealAndHand(doc: MatchDoc, seat: String) {
    val id = matchId ?: return
    val round = doc.currentRound
    if (doc.gameState.dealtRound < round) {
      runCatching { store.dealRound(id, round) }.onFailure { setRejection(it.message, doc.version) }
    }
    if (session.getHand(seat).isEmpty()) seedHand(doc, seat, round)
  }

  /** Load and seed our hand for [round]; re-seed a live table that started
   *  before the hand arrived (the JS reference's async-reseed-then-replay). */
  private suspend fun seedHand(doc: MatchDoc, seat: String, round: Int) {
    val id = matchId ?: return
    val hand = runCatching { store.loadHand(id, seat, round) }.getOrNull() ?: return
    val cards = hand.cards.mapNotNull { it.toEngineCard() }
    if (cards.size != HAND_SIZE) return
    session.setAuthoritativeHand(seat, cards, round)
    session.getPlayState()?.let { table ->
      if (table.cfg.round == round && table.cfg.hands[seat] == null) {
        val restored = restoreHand(table, seat, cards, round)
        if (restored.restored) session.updatePlayState(restored.state)
      }
    }
  }

  /**
   * Replay the auction: the two interpreters, then the cold-start seed.
   *
   * Both interpreters are re-run deliberately: the adapter's own onSnapshot
   * pass has already applied them when the engines existed, and this call
   * is the no-op then; on the cold-start delivery it is the only call that
   * applies. [GameSession.completeBidding] is the single funnel that
   * commits the outcome into the round — the adapters never call it, so
   * without this the trick-play config has no estimates, dash callers or
   * leader.
   *
   * [seedStoredEstimates] runs LAST for a reason: the engine has to reach
   * the ESTIMATES phase before any stored estimate can be re-emitted into
   * it, and only the biddingLog replay above gets it there. It closes an
   * auction this client arrived after — the one engine write beyond
   * [initAuction], and the reason a cold start resumes trick play instead
   * of stalling in an auction the document says is over.
   */
  private fun replayBidding(snapshot: MatchSnapshot, doc: MatchDoc) {
    val id = matchId ?: return
    adapter.applyRemoteBid(id, doc)
    val reDrive = adapter.applyRemoteBiddingAction(id, doc)
    seedStoredEstimates(doc)
    // Either interpreter may be the one that closed the auction; the other
    // then reports a duplicate with no outcome attached. A seed that
    // already closed it contributes no outcome here either, so the funnel
    // is still hit exactly once per round.
    val outcome = snapshot.biddingActionSync?.outcome
      ?: reDrive.outcome
      ?: snapshot.bidSync?.outcome
    if (outcome != null) {
      lastOutcome = outcome
      session.completeBidding(outcome)
    }
  }

  /**
   * The cold-start auction reconstruction, and the one place this class
   * drives [emit] itself. [MatchAdapter.applyRemoteBid] applies exactly
   * ONE bid per delivery — the document's `lastBidSeat` — and is version-
   * gated, so every estimate behind the last is behind its registry's gate
   * and unreachable by delivery. A client that arrives after the auction
   * closed would therefore sit in an ESTIMATES phase the document says is
   * done, with no outcome and so no table.
   *
   * The document's `bids` map holds one authoritative estimate per seat,
   * and the engine's estimate order is fully determined (each seat
   * estimates exactly once, walking from the phase's first seat), so the
   * seats the document has that the local engine does not are re-emitted
   * through the REAL [emit], in the engine's own turn order. This is the
   * "load bidding state" half of the JS reference's bootstrapGameSession()
   * — that function's own contract leaves `bidsBySeat` on its returned
   * snapshot for exactly this consumer — and it is a pure function of the
   * authoritative document, never a gameplay decision of ours.
   *
   * The completing emit carries the outcome itself, so the seed closes the
   * auction the same way a live delivery would. A rejection stops it cold:
   * a stored estimate that does not replay legally means the reconstructed
   * state disagrees with the document, and inventing state is worse than
   * waiting for the next delivery. A live delivery never reaches the loop
   * — the adapter's own pre-pass has already applied the one new bid, so
   * the seat the engine is waiting on has nothing stored the engine lacks.
   */
  private fun seedStoredEstimates(doc: MatchDoc) {
    var state = session.getBiddingState() ?: return
    if (state.subPhase != com.estemshan.engine.BiddingPhase.ESTIMATES) return
    val stored = doc.bids.mapNotNull { (seat, value) -> value?.let { seat to it } }.toMap()
    if (stored.isEmpty()) return

    // The engine stops needing the seed once its own bids catch up; the
    // guard bounds a walk that the completion exit otherwise ends early.
    var guard = 0
    while (guard <= state.seats.size) {
      guard++
      val seat = state.waitingFor ?: return
      val value = stored[seat] ?: return
      when (val result = emit(state, BiddingIntent.FinalEstimate(seat, value))) {
        is EmitResult.Rejected -> return
        is EmitResult.GeneralPass -> return
        is EmitResult.Applied -> {
          session.updateBiddingState(result.state)
          state = result.state
        }
        is EmitResult.Completed -> {
          session.updateBiddingState(result.state)
          lastOutcome = result.outcome
          session.completeBidding(result.outcome)
          return
        }
      }
    }
  }

  /**
   * Build the trick-play table exactly once for this round, and only after
   * both facts it needs exist: the auction closed ([lastOutcome]) and our
   * authoritative hand landed. The config holds ONLY our hand — an
   * opponent's cards enter through the replay's throwaway seeding, so no
   * seat's hand is ever peekable. [restartPlayState] (clearPlayState THEN
   * initializePlayState) is the P1-3-safe entry point by construction.
   */
  private fun ensureTable(doc: MatchDoc, seat: String) {
    if (session.isPlayStateValidForCurrentRound()) return
    val outcome = lastOutcome ?: return
    if (session.getBiddingState()?.let { it.subPhase } != com.estemshan.engine.BiddingPhase.DONE) return
    if (doc.gameState.dealtRound < doc.currentRound) return
    val myHand = session.getHand(seat)
    if (myHand.isEmpty()) return
    val round = session.getRound()
    session.restartPlayState(
      RoundCfg(
        round = round.number,
        trump = outcome.trump,
        callerId = outcome.callerId,
        withPlayers = outcome.withPlayers,
        estimates = outcome.estimates,
        dashCallers = outcome.dashCallers,
        leaderId = outcome.leaderId,
        riskId = outcome.riskPlayerId,
        multiplier = round.multiplier,
        hands = mapOf(seat to myHand),
      ),
    )
    session.setTurn(outcome.leaderId)
  }

  /**
   * Replay the cards, alternating play/resolve exactly as the adapter's own
   * catch-up loop does: one delivery can carry several backlogged completed
   * tricks, and emitPlay() refuses a new card while RESOLVING, so N tricks
   * take N alternations. The stop condition is the same one — a full pass
   * advanced neither the replayed count nor a resolved trick.
   */
  private fun replayCards(doc: MatchDoc) {
    val id = matchId ?: return
    if (!session.isPlayStateValidForCurrentRound()) return
    val seat = boundSeat
    val cards = doc.cardLog.size
    val bound = if (cards in 1..80) cards + 2 else 14
    repeat(bound) {
      val countBefore = adapter.lastAppliedCardCount(id)
      val trickBefore = adapter.lastResolvedTrickNo(id)
      adapter.applyRemoteCard(id, doc, seat)
      val trick = adapter.applyRemoteTrick(id, doc)
      if (
        adapter.lastAppliedCardCount(id) == countBefore &&
        adapter.lastResolvedTrickNo(id) == trickBefore &&
        !trick.applied
      ) return
    }
  }

  /**
   * Score a round the instant it is truly complete: the local engine is DONE
   * AND all 52 round-tagged cards are on the document. Either alone is not
   * enough — a locally-DONE engine with a short document log means this
   * client is ahead of the write, and scoring then would bank a round the
   * document has not finished. [scoredRound] makes the whole method a
   * once-per-round property.
   */
  private suspend fun maybeCompleteRound(doc: MatchDoc) {
    val round = doc.currentRound
    if (scoredRound == round) return
    val table = session.getPlayState() ?: return
    if (table.phase != TablePhase.DONE) return
    if (doc.roundCardCount(round) < ROUND_CARD_TOTAL) return
    scoredRound = round
    scoreAndAdvance(doc, table)
  }

  /**
   * Score, publish the standings locally, then publish the round to
   * Firestore. Every client runs this; the three transactions are idempotent
   * by design (ALREADY_EXTENDED / ALREADY_ADVANCED / ALREADY_COMPLETE for the
   * race losers), and scoring is a pure function of the same cardLog every
   * seat replayed, so all four compute identical totals and winners.
   *
   * The standings are published BEFORE the network writes so the player sees
   * the result immediately; if a write fails, the next snapshot self-heals —
   * another seat's successful advance or endMatch moves the document, and
   * this VM follows it.
   */
  private suspend fun scoreAndAdvance(doc: MatchDoc, table: TableState) {
    val id = matchId ?: return
    val cfg = table.cfg
    val result = calculateRoundScore(
      RoundScoreInput(
        round = cfg.round,
        order = session.getRoom().seats,
        bids = bidsFromOutcome(cfg),
        tricksWon = table.tricksWon,
        callerId = cfg.callerId,
        withPlayers = cfg.withPlayers,
        multiplier = cfg.multiplier,
        riskPlayerId = cfg.riskId,
        classic = false,
        escalationCap = session.escalationCap,
      ),
    )

    val callerSucceeded = cfg.callerId?.let { result.breakdown[it]?.succeeded } ?: false
    val extension = computeRoundExtension(cfg.round, cfg.callerId, cfg.trump, callerSucceeded, result.isSaayda)

    val totals = accumulateMatchScores(session.getMatchScores(), result.deltas)
    session.setMatchScores(totals)
    session.recordRoundResult(toRoundResultEntry(cfg, table, result, extension.reason))
    session.setWinnerIds(computeWinner(totals))
    session.completeRound(table.tricksWon, result.nextMultiplier)

    val maxRounds = if (extension.extend) doc.maxRounds + 1 else doc.maxRounds
    val isLastRound = cfg.round + 1 > maxRounds
    val saaydaSeats = if (result.isSaayda) session.getRoom().seats.toSet() else emptySet()

    _state.value = MatchUiState.RoundStandings(
      standings = buildStandings(totals, result.deltas, saaydaSeats),
      round = cfg.round,
      isLastRound = isLastRound,
    )

    if (extension.extend) {
      runCatching { store.extendMatchRounds(id, cfg.round, extension.reason!!.name) }
        .onFailure { setRejection(it.message, doc.version) }
    }
    if (isLastRound) {
      runCatching { store.endMatch(id, cfg.round, totals, computeWinner(totals)) }
        .onFailure { setRejection(it.message, doc.version) }
    } else {
      runCatching { store.advanceToNextRound(id, cfg.round) }
        .onFailure { setRejection(it.message, doc.version) }
    }
  }

  private fun toRoundResultEntry(
    cfg: RoundCfg,
    table: TableState,
    result: RoundScoreResult,
    extensionReason: com.estemshan.engine.ExtensionReason?,
  ) = RoundResult(
    round = cfg.round,
    trump = cfg.trump,
    callerId = cfg.callerId,
    tricksWon = table.tricksWon,
    estimates = cfg.estimates,
    scoreDeltas = result.deltas,
    riskPlayerId = result.riskPlayerId,
    totalBids = result.totalBids,
    isOver = result.isOver,
    isSaayda = result.isSaayda,
    appliedMultiplier = result.appliedMultiplier,
    nextMultiplier = result.nextMultiplier,
    extensionReason = extensionReason,
  )

  /**
   * Publish the terminal standings off the document, which is the only
   * authority for the full-match totals — a client that joined late sees the
   * real final scores here even though its own in-flight window was partial.
   * Clearing the profile's currentMatchId once is what keeps a relaunch from
   * rejoining a finished match.
   */
  private suspend fun publishComplete(doc: MatchDoc) {
    val scores = doc.finalScores
    if (scores.isNullOrEmpty()) return
    _state.value = MatchUiState.MatchComplete(buildStandings(scores))
    if (!clearedMatchId) {
      clearedMatchId = true
      val myUid = uid()
      if (myUid != null) runCatching { players.setCurrentMatchId(myUid, null) }
    }
  }

  /**
   * Publish the state for the current phase. A scored round owns the window
   * until the document advances — a re-delivery of the still-open document
   * must not bounce the standings back to a DONE table.
   */
  private fun publish(doc: MatchDoc, seat: String) {
    if (scoredRound == doc.currentRound) return
    val table = session.getPlayState()
    if (table != null && session.isPlayStateValidForCurrentRound()) {
      _state.value = MatchUiState.Table(table, seat, rejection)
      return
    }
    val bidding = session.getBiddingState()
    if (bidding != null && session.isBiddingStateValidForCurrentRound()) {
      // userSeat is always OUR seat: online, each device holds one chair,
      // and the screens gate their controls on turn == userSeat — passing
      // the acting seat would let this player act for an opponent.
      _state.value = MatchUiState.Bidding(
        state = bidding,
        userSeat = seat,
        rejection = rejection,
        forbidden = forbiddenEstimateFor(bidding, seat),
        floor = withFloorFor(bidding.actionHistory, seat),
      )
      return
    }
    _state.value = MatchUiState.Connecting
  }

  /**
   * S19 — re-derive the passive staleness hint after a snapshot. [ourSeat] is
   * the seat this client holds, and the acting seat comes from the engines
   * this same delivery drove, so the hint is a function of the state the
   * player already sees — never a second opinion about it.
   */
  internal val awayInputs = mutableListOf<String>()
  internal val awayWrites = mutableListOf<String>()

  internal fun awayDebug(): String = awayTracker.debugState() + " tcalls=${awayTracker.calls}"

  private fun refreshOpponentAway(doc: MatchDoc, ourSeat: String) {
    awayInputs.add(
      "seat=$ourSeat acting=${opponentActingSeat(ourSeat)} " +
        "tablePhase=${session.getPlayState()?.phase} valid=${session.isPlayStateValidForCurrentRound()} " +
        "scored=$scoredRound cards=${doc.cardLog.size} bids=${doc.biddingLog.size}",
    )
    // A scored round's window is a wait on the document, not on a player.
    if (scoredRound == doc.currentRound) {
      clearOpponentAway()
      return
    }
    publishOpponentAway(awayTracker.onDocument(doc, opponentActingSeat(ourSeat)))
  }

  /**
   * The seat the engines are waiting on, or null when nobody is: our own move,
   * a resolving trick (collected automatically), a scored round, or no live
   * engine yet. This is where "opponent" is decided — [OpponentAwayTracker]
   * stays engine-free and receives a seat that is never ours.
   */
  private fun opponentActingSeat(ourSeat: String): String? {
    val table = session.getPlayState()
    if (table != null && session.isPlayStateValidForCurrentRound()) {
      return if (table.phase == TablePhase.PLAY) table.turn?.takeIf { it != ourSeat } else null
    }
    val bidding = session.getBiddingState()
    if (bidding != null && session.isBiddingStateValidForCurrentRound() &&
      bidding.subPhase != BiddingPhase.DONE
    ) {
      return bidding.waitingFor?.takeIf { it != ourSeat }
    }
    return null
  }

  /**
   * Publish [presence] and keep exactly one alarm armed: the indicator has to
   * flip with no new snapshot, because an away opponent sends none. The alarm
   * is rescheduled on every delivery, so any progress cancels the one that
   * would have fired against the stale baseline.
   */
  private fun publishOpponentAway(presence: OpponentAwayTracker.Presence) {
    awayWrites.add("publish presence=$presence")
    _opponentAway.value = presence == OpponentAwayTracker.Presence.AppearsAway
    awayAlarm?.cancel()
    awayAlarm = null
    if (presence != OpponentAwayTracker.Presence.OpponentActive) return
    val remaining = awayTracker.millisUntilAway()
    if (remaining <= 0L) return
    awayAlarm = viewModelScope.launch(worker) {
      delay(remaining)
      val reeval = awayTracker.reevaluate()
      awayWrites.add("alarm fired reeval=$reeval")
      _opponentAway.value =
        reeval == OpponentAwayTracker.Presence.AppearsAway
    }
  }

  /** The hint is meaningless without a live match: drop the baseline, the
   *  pending alarm, and the published flag. */
  private fun clearOpponentAway() {
    awayWrites.add("clear")
    awayTracker.reset()
    awayAlarm?.cancel()
    awayAlarm = null
    _opponentAway.value = false
  }

  private fun setRejection(message: String?, version: Int?) {
    rejection = message
    rejectionVersion = if (message == null) null else version
  }

  private companion object {
    /** A full Estemshan hand; anything else is not a deal we can seed. */
    const val HAND_SIZE = 13
  }
}
