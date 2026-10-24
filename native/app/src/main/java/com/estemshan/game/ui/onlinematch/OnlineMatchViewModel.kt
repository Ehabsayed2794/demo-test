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
import com.estemshan.engine.MatchMode
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundResult
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.RoundScoreResult
import com.estemshan.engine.RoundState
import com.estemshan.engine.ScoringMode
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
import com.estemshan.game.ui.standings.RankedResultUiState
import com.estemshan.game.ui.standings.RankedSeasonUiState
import com.estemshan.game.ui.standings.buildRankedResult
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.services.MatchAdapter
import com.estemshan.services.MatchSnapshot
import com.estemshan.services.MatchSnapshotListener
import com.estemshan.services.MatchSubscriptionHandle
import com.estemshan.services.Heartbeat
import com.estemshan.services.DecisionTimeoutPort
import com.estemshan.services.DecisionWindow
import com.estemshan.services.PlayerPort
import com.estemshan.services.SeatReclaimPort
import com.estemshan.services.SettlementPort
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
  private val timeouts: DecisionTimeoutPort = OnlineServices.timeouts,
  private val reclaims: SeatReclaimPort = OnlineServices.reclaims,
  private val settlements: SettlementPort = OnlineServices.settlements,
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
   * S73 (RD19): the seats the configured bot is currently BORROWING because
   * their owner ignored decision points — the S39 chip's source, for every
   * seat at the table. Published straight from
   * [MatchDoc.inactiveTakeoverSeats], so it is a document read and nothing
   * else: no local guess about who is away, and never merged with
   * [MatchDoc.botControlledSeats] (a borrowed seat is still its owner's —
   * seated, kickable, still holding its vote). Empty when no seat is borrowed
   * or no match is live.
   */
  private val _borrowedSeats = MutableStateFlow<Set<String>>(emptySet())
  val borrowedSeats: StateFlow<Set<String>> = _borrowedSeats.asStateFlow()

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

  /**
   * S61 — the ONE pending timeout-resolution alarm. Distinct from
   * [awayAlarm]: that one flips a passive hint and only ever waits on an
   * OPPONENT; this one resolves a stalled table and arms for the seat the
   * table is waiting on EVEN WHEN THAT SEAT IS OURS, because the reliability
   * constraint is that a resolution must not depend on the acting player's
   * foreground process. Every delivery cancel-then-re-arms, so a decision
   * that landed, a phase that changed, or a round that advanced all void the
   * stale wait before a fresh one starts — there is at most one armed alarm
   * for the whole match.
   */
  private var timeoutAlarm: Job? = null

  /**
   * S73 (RD19) — the ONE pending reclaim poll, and only ever armed for OUR
   * own borrowed seat. Distinct from [timeoutAlarm]: that one resolves a
   * stalled table for whoever holds the turn, this one asks the server whether
   * the trick the bot is mid-way through has finished yet, so our seat can
   * come back. Same cancel-then-re-arm rule, so at most one poll is live for
   * the whole match.
   */
  private var reclaimPoll: Job? = null

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

  /**
   * The ranked settlement's once-per-session trigger: set the moment the
   * call is armed, so however many terminal re-deliveries stack up (the
   * snapshot fan-out, a version bump, a corrected claim), this client asks
   * the settler EXACTLY ONCE per binding. The once-only authority across
   * clients is the server's claim record, not this flag.
   */
  private var settlementAttempted: Boolean = false

  /** The pending settlement's retry loop, cancelled with the binding. */
  private var settlementJob: Job? = null

  /**
   * The reveal the settler has already delivered for THIS match, kept
   * beside the state so a re-delivery of the terminal document republishes
   * it instead of clobbering it — the standings are a pure function of the
   * document, the movement is not, and the two arrive on different paths.
   */
  private var rankedReveal: RankedResultUiState? = null

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
    settlementAttempted = false
    settlementJob = null
    rankedReveal = null
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
    clearTimeoutAlarm()
    clearReclaimSignal()
    // A settlement mid-retry belongs to the binding that armed it: a rebind
    // (even to the same match) starts a fresh session whose own first
    // terminal delivery re-triggers — the server replays the recorded
    // outcome, so nothing is lost and no reveal leaks across bindings.
    settlementJob?.cancel()
    settlementJob = null
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
      clearTimeoutAlarm()
      clearReclaimSignal()
      return
    }

    // A terminal match is published straight off the document, which is the
    // authority for the final totals — checked before the seat gate so a
    // player who backed out still sees how the table finished.
    if (doc.isComplete) {
      _reconnecting.value = false
      clearOpponentAway()
      clearTimeoutAlarm()
      clearReclaimSignal()
      publishComplete(doc)
      return
    }

    val myUid = uid()
    if (myUid == null) {
      _state.value = MatchUiState.Failed("Not signed in.")
      clearOpponentAway()
      clearTimeoutAlarm()
      clearReclaimSignal()
      return
    }
    val seat = adapter.uidToSeat(doc, myUid)
    if (seat == null) {
      _state.value = MatchUiState.NotInMatch
      _reconnecting.value = false
      clearOpponentAway()
      clearTimeoutAlarm()
      clearReclaimSignal()
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
    refreshTimeoutAlarm(doc)
    refreshReclaimPoll(doc, seat)
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
   * a Rapid/Fast round (FULL 14+, MINI 6+), the full Dash→Auction→Confirm→
   * Estimates flow otherwise. The current multiplier is carried in —
   * escalation survives.
   */
  private fun initAuction(doc: MatchDoc, dealerSeat: String, seats: List<String>) {
    // S69 (E7): the document is the authority — seed the session's type +
    // mode from it before the auction starts, and resolve the fast path
    // from the same type. Read the doc, never invent config.
    // S43 (E6b): the authority mode seeds the same way.
    session.setGameType(doc.gameType)
    session.setScoringMode(doc.scoringMode)
    session.setMatchMode(doc.mode)
    val round = doc.currentRound
    val multiplier = session.getRound().multiplier
    session.clearBiddingState()
    session.clearPlayState()
    session.initializeBiddingState(
      if (isFastRound(round, doc.gameType)) initFastRound(round, dealerSeat, seats, multiplier)
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
        // S69 (E7): the mode comes from the session, which initAuction seeded
        // from the match document. escalationCap was already correct.
        classic = session.getScoringMode() == ScoringMode.CLASSIC,
        escalationCap = session.escalationCap,
      ),
    )

    val callerSucceeded = cfg.callerId?.let { result.breakdown[it]?.succeeded } ?: false
    // S69 (E7): the extension window is the match's own type (MINI 6–10,
    // FULL 14–18) — without this a MINI match could never extend online.
    val extension = computeRoundExtension(
      cfg.round, doc.maxRounds, cfg.callerId, cfg.trump, callerSucceeded, result.isSaayda,
      gameType = doc.gameType,
    )

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
   *
   * The publish happens BEFORE any network word is spoken: the settlement
   * (RD13's server-side RP movement) runs in its own coroutine off this
   * method, so the standings and the currentMatchId clear never wait on it.
   * A re-delivery of the SAME terminal document skips the write — the
   * standings are a pure function of the document, so republishing them
   * could only clobber a ranked reveal that landed in between.
   */
  private suspend fun publishComplete(doc: MatchDoc) {
    val scores = doc.finalScores
    if (scores.isNullOrEmpty()) return
    val standings = buildStandings(scores)
    val current = _state.value
    if (!(current is MatchUiState.MatchComplete && current.standings == standings)) {
      _state.value = MatchUiState.MatchComplete(standings, rankedReveal)
    }
    if (!clearedMatchId) {
      clearedMatchId = true
      val myUid = uid()
      if (myUid != null) runCatching { players.setCurrentMatchId(myUid, null) }
    }
    maybeSettle(doc)
  }

  /**
   * The ranked settlement trigger — exactly once per match per session, and
   * RANKED only (ROOM/UNRANKED never settle, RD28). The call is armed here
   * and its outcome handled off the snapshot path, so the terminal publish
   * above is never delayed by the network. A redelivery that arrives while
   * the call is in flight finds [settlementAttempted] already set; a second
   * CLIENT's call is the server's claim record to absorb, not ours.
   */
  private fun maybeSettle(doc: MatchDoc) {
    if (settlementAttempted) return
    if (doc.mode != MatchMode.RANKED) return
    val id = matchId ?: return
    settlementAttempted = true
    settlementJob = viewModelScope.launch(worker) { runSettlement(id, doc) }
  }

  /**
   * The settlement body: ask the settler once, and act on what it says.
   * [SettlementPort.SettlementOutcome.NotRanked] and
   * [SettlementPort.SettlementOutcome.Denied] are terminal — the plain
   * standings already published are the whole story, and there is no new
   * user-facing word for a settlement that never came. Only a Retryable
   * outcome loops, with the alarm's own exponential backoff, so the bound
   * below is the loop's whole lifetime.
   */
  private suspend fun runSettlement(matchId: String, doc: MatchDoc) {
    var consecutiveFailures = 0
    while (true) {
      when (val outcome = settlements.settle(matchId)) {
        is SettlementPort.SettlementOutcome.Settled -> {
          publishRanked(doc, outcome)
          return
        }
        is SettlementPort.SettlementOutcome.NotRanked -> return
        SettlementPort.SettlementOutcome.Denied -> return
        SettlementPort.SettlementOutcome.Retryable -> {
          if (++consecutiveFailures > MAX_TRANSIENT_FAILURES) return
          delay(transientBackoffMillis(consecutiveFailures))
        }
      }
    }
  }

  /**
   * Attach the reveal. The movement is the server's own (`you`) fed into
   * the standings' existing [buildRankedResult]: the client's whole
   * contribution is the two server numbers — the previous RP and the signed
   * delta — and nothing else; what the helper derives from them (the display
   * RP against its floor, the tier labels) is its own long-standing
   * derivation, not part of this move, and the reveal trusts the pair as
   * the contract's restatement of the server's `rp` (RD13). An outcome
   * without `you` — forward-looking wire, no shipped build carries it yet;
   * the field is the server half's to add — reveals nothing: the plain
   * standings stand, silently, the same way a denied settlement does.
   *
   * The reveal is also parked in [rankedReveal] before it reaches the state,
   * so the next terminal re-delivery republishes rather than clobbers it. A
   * replay of the recorded movement arrives here too — `replayed` changes
   * nothing about what the reveal shows.
   */
  private fun publishRanked(doc: MatchDoc, outcome: SettlementPort.SettlementOutcome.Settled) {
    val movement = outcome.you ?: return
    val reveal = buildRankedResult(
      previousRP = movement.previousRp,
      delta = movement.delta,
      season = unlistedSeason,
      userSeat = uid()?.let(doc::uidToSeat),
    )
    rankedReveal = reveal
    val current = _state.value
    if (current is MatchUiState.MatchComplete && current.ranked == null) {
      _state.value = current.copy(ranked = reveal)
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
  private fun refreshOpponentAway(doc: MatchDoc, ourSeat: String) {
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
    _opponentAway.value = presence == OpponentAwayTracker.Presence.AppearsAway
    awayAlarm?.cancel()
    awayAlarm = null
    if (presence != OpponentAwayTracker.Presence.OpponentActive) return
    val remaining = awayTracker.millisUntilAway()
    if (remaining <= 0L) return
    awayAlarm = viewModelScope.launch(worker) {
      delay(remaining)
      _opponentAway.value =
        awayTracker.reevaluate() == OpponentAwayTracker.Presence.AppearsAway
    }
  }

  /** The hint is meaningless without a live match: drop the baseline, the
   *  pending alarm, and the published flag. */
  private fun clearOpponentAway() {
    awayTracker.reset()
    awayAlarm?.cancel()
    awayAlarm = null
    _opponentAway.value = false
  }

  /**
   * S61 — re-arm the timeout alarm after a snapshot. The OLD alarm is always
   * cancelled first, so at most one is ever armed: a decision that landed, a
   * phase that changed, or a round that advanced all arrive as a new snapshot
   * that voids the wait armed against the stale baseline.
   *
   * The first arm is an ESTIMATE ([DecisionWindow] — the snapshot arrived
   * after the write that anchored the deadline, so it is an upper bound and
   * fires late rather than early). Every later arm is the server's
   * [DecisionTimeoutPort.Resolution.NotYetExpired.retryAfterMillis], because
   * `MatchDoc` carries no `updatedAt` and the client cannot derive the anchor.
   */
  private fun refreshTimeoutAlarm(doc: MatchDoc) {
    timeoutAlarm?.cancel()
    timeoutAlarm = null
    val id = matchId ?: return
    if (doc.isComplete) return
    val decision = timedDecision() ?: return
    val windowMillis = DecisionWindow.durationSeconds(
      timerSeconds = doc.decisionTimerSeconds,
      bidding = decision.bidding,
      // Mirrors the resolver's shortWindowSeats (C3): a borrowed seat is on the
      // bot's fixed 2 s pace, not the human window. botControlledSeats()
      // deliberately excludes borrowed seats (DEC-RD19-04 provenance), so the
      // marker set must be read separately here.
      botControlled = decision.seat in doc.botControlledSeats() ||
        decision.seat in doc.inactiveTakeoverSeats(),
    ) * MILLIS_PER_SECOND
    timeoutAlarm = viewModelScope.launch(worker) { runTimeoutAlarm(id, windowMillis.toLong()) }
  }

  /**
   * The seat the table is waiting on RIGHT NOW, with its phase — or null when
   * nobody is: a scored round, a resolving trick, a complete match, or no live
   * engine yet. Deliberately NOT [opponentActingSeat]: that one filters our
   * own seat out because the staleness hint is only meaningful for an
   * opponent, but a timeout resolution must fire for OUR move too — the
   * constraint is that the table never stalls because the acting player's app
   * is backgrounded or gone.
   */
  private data class TimedDecision(val seat: String, val bidding: Boolean)

  private fun timedDecision(): TimedDecision? {
    val table = session.getPlayState()
    if (table != null && session.isPlayStateValidForCurrentRound()) {
      if (table.phase != TablePhase.PLAY) return null
      return table.turn?.let { TimedDecision(it, bidding = false) }
    }
    val bidding = session.getBiddingState()
    if (bidding != null && session.isBiddingStateValidForCurrentRound() &&
      bidding.subPhase != BiddingPhase.DONE
    ) {
      return bidding.waitingFor?.let { TimedDecision(it, bidding = true) }
    }
    return null
  }

  /**
   * The alarm body: wait, ask the resolver once, and act on what it says. The
   * loop is bounded in two independent ways so it can never spin:
   *  - [MAX_TIMEOUT_ATTEMPTS] caps TOTAL iterations, even if something
   *    unexpected repeats;
   *  - [MAX_TRANSIENT_FAILURES] caps consecutive network/callable failures,
   *    and each one backs off exponentially — never a tight retry loop.
   *
   * [DecisionTimeoutPort.Resolution.NotYetExpired] is NOT a failure and does
   * not consume the transient budget: it is the server's authoritative
   * remaining time, and the deadline it points at is fixed for this alarm's
   * lifetime (only a document change moves it, and a document change cancels
   * and re-arms). So the wait converges instead of looping.
   */
  private suspend fun runTimeoutAlarm(matchId: String, firstWaitMillis: Long) {
    var waitMillis = firstWaitMillis
    var attempt = 0
    var consecutiveFailures = 0
    while (true) {
      delay(waitMillis)
      if (++attempt > MAX_TIMEOUT_ATTEMPTS) return
      when (val outcome = timeouts.resolve(matchId)) {
        // The document update is the whole signal: the snapshot listener
        // delivers it and refreshTimeoutAlarm re-arms for the next decision.
        DecisionTimeoutPort.Resolution.Applied -> return
        DecisionTimeoutPort.Resolution.NotActive -> return
        DecisionTimeoutPort.Resolution.Denied -> return
        is DecisionTimeoutPort.Resolution.NotYetExpired -> {
          consecutiveFailures = 0
          waitMillis = outcome.retryAfterMillis
        }
        DecisionTimeoutPort.Resolution.Retryable -> {
          if (++consecutiveFailures > MAX_TRANSIENT_FAILURES) return
          waitMillis = transientBackoffMillis(consecutiveFailures)
        }
      }
    }
  }

  private fun clearTimeoutAlarm() {
    timeoutAlarm?.cancel()
    timeoutAlarm = null
  }

  /**
   * S73 (RD19) — publish the borrowed-seat set for the chip, and arm the
   * reclaim poll when the seat the bot is borrowing is OURS. Cancel-then-re-arm
   * on every delivery, exactly like [refreshTimeoutAlarm]: at most one poll is
   * live, and any document change voids the wait armed against the older one.
   *
   * Only our own seat is polled. The callable derives the seat from the
   * verified uid — there is no "reclaim p2" — so asking for anyone else could
   * only ever be a NOT_OWNER.
   *
   * The arm is bounded rather than persistent, and that is safe because it is
   * self-healing: every bot play for a borrowed seat is a document write, and
   * every write comes back through here. A poll that exits on its attempt cap,
   * or on a [SeatReclaimPort.Reclaim.Denied], is recreated by the next trick
   * instead of stranding the player out of their seat.
   *
   * The first arm waits one bot window. The client cannot know when the bot's
   * trick will finish, and the server floors its own answer at the same
   * window, so the estimate is late-rather-than-early; every later arm is the
   * server's authoritative `retryAfterMillis`.
   */
  private fun refreshReclaimPoll(doc: MatchDoc, ourSeat: String) {
    reclaimPoll?.cancel()
    reclaimPoll = null
    val borrowed = doc.inactiveTakeoverSeats()
    _borrowedSeats.value = borrowed
    val id = matchId ?: return
    if (ourSeat !in borrowed) return
    val firstWaitMillis = DecisionWindow.BOT_DECISION_SECONDS * MILLIS_PER_SECOND.toLong()
    reclaimPoll = viewModelScope.launch(worker) { runReclaimPoll(id, firstWaitMillis) }
  }

  /**
   * The poll body: wait, ask once, act on what the server said. Bounded the
   * same two independent ways as [runTimeoutAlarm] — [MAX_RECLAIM_ATTEMPTS] on
   * total iterations, [MAX_TRANSIENT_FAILURES] on consecutive failures with
   * [transientBackoffMillis] between them — so a flaky network can never turn
   * it into a tight loop.
   *
   * [SeatReclaimPort.Reclaim.WaitingForTrick] is NOT a failure and costs
   * nothing from the transient budget: it is the server's authoritative "the
   * trick is not down yet, ask again in N", so the wait converges on it the
   * way the timeout alarm converges on `NotYetExpired`.
   *
   * Every terminal outcome exits and lets the next snapshot decide — including
   * [SeatReclaimPort.Reclaim.Returned], whose document write IS the signal that
   * clears the chip, and [SeatReclaimPort.Reclaim.HumanHoldsSeat], which means
   * there is nothing to reclaim. No "I'm back" screen anywhere: the player's
   * return is the signal, and the seat comes back by itself.
   */
  private suspend fun runReclaimPoll(matchId: String, firstWaitMillis: Long) {
    var waitMillis = firstWaitMillis
    var attempt = 0
    var consecutiveFailures = 0
    while (true) {
      delay(waitMillis)
      if (++attempt > MAX_RECLAIM_ATTEMPTS) return
      when (val outcome = reclaims.reclaim(matchId)) {
        SeatReclaimPort.Reclaim.Returned -> return
        SeatReclaimPort.Reclaim.HumanHoldsSeat -> return
        SeatReclaimPort.Reclaim.Denied -> return
        is SeatReclaimPort.Reclaim.WaitingForTrick -> {
          consecutiveFailures = 0
          waitMillis = outcome.retryAfterMillis
        }
        SeatReclaimPort.Reclaim.Retryable -> {
          if (++consecutiveFailures > MAX_TRANSIENT_FAILURES) return
          waitMillis = transientBackoffMillis(consecutiveFailures)
        }
      }
    }
  }

  /** The chip and the poll mean nothing without a live match: drop the pending
   *  poll and the published set together. */
  private fun clearReclaimSignal() {
    reclaimPoll?.cancel()
    reclaimPoll = null
    _borrowedSeats.value = emptySet()
  }

  private fun setRejection(message: String?, version: Int?) {
    rejection = message
    rejectionVersion = if (message == null) null else version
  }

  private companion object {
    /** A full Estemshan hand; anything else is not a deal we can seed. */
    const val HAND_SIZE = 13

    const val MILLIS_PER_SECOND = 1_000

    /** S61 — the hard ceiling on one alarm's iterations, however it got there.
     *  Generous against the realistic path (an estimate correction or two plus
     *  a network blip) while guaranteeing the loop cannot spin. */
    const val MAX_TIMEOUT_ATTEMPTS = 8

    /** S73 (RD19) — the same ceiling for one reclaim poll. A trick is at most
     *  four plays and each play re-arms this poll from a fresh snapshot, so a
     *  single lifetime rarely needs more than a couple of asks; the cap exists
     *  to guarantee the loop cannot spin, not to bound the reclaim. */
    const val MAX_RECLAIM_ATTEMPTS = 8

    /** How many consecutive transient failures one caller tolerates — the S61
     *  alarm's, the S73 reclaim poll's, and the ranked settlement's, shared
     *  budget. The backoff below makes those ~31 s of patient retrying, not a
     *  burst. */
    const val MAX_TRANSIENT_FAILURES = 6

    /** S61 — exponential backoff for a transient callable/network failure:
     *  0.5 s, 1 s, 2 s, 4 s, 8 s, 16 s. */
    const val TRANSIENT_BACKOFF_BASE_MILLIS = 500L
    const val TRANSIENT_BACKOFF_CAP_MILLIS = 16_000L
  }

  private fun transientBackoffMillis(failure: Int): Long =
    (TRANSIENT_BACKOFF_BASE_MILLIS shl (failure - 1))
      .coerceAtMost(TRANSIENT_BACKOFF_CAP_MILLIS)
}

/**
 * The season window the reveal's SEASON chip would show. The settlement
 * response carries the caller's movement and nothing about a season, and
 * there is no client-side source for one that is not invented — so the chip
 * renders empty until the server half grows the field, rather than showing
 * fabricated dates. The RP movement itself, the reveal's point, is complete.
 */
private val unlistedSeason = RankedSeasonUiState(id = "", start = "", end = "")
