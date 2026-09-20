package com.estemshan.engine

/**
 * Centralized Game Session — the in-memory home of ONE playable match,
 * ported from design-ui/engine/session.js (662 lines, the complete
 * contract). It holds the cross-round state the pure engines cannot:
 * identity, players, room, dealer, round, hands, the round-scoped engine
 * states, match scores, winners, and a live remote-match mirror. Engines
 * are pure functions over state held HERE; the sync adapter replays into
 * HERE; the UI observes HERE.
 *
 * Layering (docs/specs/02-engine-api.md §6): Engine → GameSession →
 * MatchAdapter → MatchService → UI. This store owns NO Firestore access
 * — the remote mirror reaches live data only through the injected
 * [RemoteMatchSync] port, implemented above this module at wiring time.
 * It also owns NO mocks: session.js's mockPlayers (fake names/ranks/
 * coins), teamScores, and getAIPlayers are deliberately NOT ported; a
 * real roster populates [setPlayers] instead.
 *
 * Persistence is out of scope here. session.js survived page reloads via
 * sessionStorage; native has no page, so the caller above this class
 * (ViewModel / services) owns survival. What this class DOES keep from
 * the JS contract is the round-fresh discipline that made reloads safe —
 * see [restartPlayState] and [clearPlayState].
 */
enum class ScoringMode { NORMAL, CLASSIC }

enum class HandAuthority { LOCAL, FIRESTORE }

/**
 * A real seat occupant. Name/uid come from the actual roster at wiring
 * time — never invented here. isAI/isRemote are flags only: this store
 * runs no bot logic (spec §6: "AI is flag-only, no bot logic").
 */
data class SessionPlayer(
  val seatId: String,
  val uid: String?,
  val displayName: String,
  val isUser: Boolean = false,
  val isAI: Boolean = false,
  val isRemote: Boolean = false,
)

data class SessionRoom(
  val code: String? = null,
  val host: Boolean = true,
  val seats: List<String> = DEFAULT_SEATS,
)

/**
 * Cross-round round record. trump/callerId/withPlayers/estimates/
 * dashCallers are committed once, by [completeBidding]; [nextRound]
 * clears exactly those and nothing else — the Sa'ayda [multiplier]
 * carries forward across rounds by design.
 */
data class RoundState(
  val number: Int = 1,
  val maxRounds: Int = 18,
  val multiplier: Int = 1,
  val trump: Suit? = null,
  val callerId: String? = null,
  val withPlayers: List<String> = emptyList(),
  val estimates: Map<String, Int> = emptyMap(),
  val dashCallers: List<String> = emptyList(),
)

/**
 * Deal metadata — the SOLE authority for "are hands dealt for this
 * round" (see [hasDealtHands]). Never infer from hand size: a seat that
 * has played every card legitimately reaches zero and is still dealt.
 */
data class DealState(
  val roundNumber: Int? = null,
  val completed: Boolean = false,
  val dealtAt: Long? = null,
)

/**
 * One recorded round outcome — what the scoring wrapper's result becomes
 * once the round is scored. Mirrors the services layer's RoundResultEntry
 * in engine-owned types (this module must not import from above it in the
 * layering); the bridge converts at the seam.
 */
data class RoundResult(
  val round: Int,
  val trump: Suit?,
  val callerId: String?,
  val tricksWon: Map<String, Int>,
  val estimates: Map<String, Int>,
  val scoreDeltas: Map<String, Int>,
  val riskPlayerId: String?,
  val totalBids: Int,
  val isOver: Boolean,
  val isSaayda: Boolean,
  val appliedMultiplier: Int,
  val nextMultiplier: Int,
  val extensionReason: ExtensionReason? = null,
)

/** What [onRemoteMatchUpdate] delivers. data is the raw published match
 *  document, carried opaquely and never interpreted here. */
data class RemoteMatchUpdate(
  val matchId: String?,
  val data: Any?,
  val error: Throwable?,
)

/**
 * The ONLY route this store ever takes to live match data (session.js's
 * single MatchService.subscribeToMatch() call, inverted into a port so
 * the engine never sees Firestore). Implement at wiring time; pass null
 * for an offline-only page and the mirror fails open (no subscription,
 * no throw). An error delivery must never clear the last known good
 * data — the implementation is expected to keep publishing it alongside
 * the error.
 */
fun interface RemoteMatchSync {
  fun subscribeToMatch(
    matchId: String,
    onUpdate: (data: Any?, error: Throwable?) -> Unit,
  ): () -> Unit
}

/** Read-only cross-round view of the match (round-scoped engine states
 *  have their own typed getters). */
data class SessionSnapshot(
  val matchId: String,
  val mode: String?,
  val scoringMode: ScoringMode,
  val room: SessionRoom,
  val dealerId: String?,
  val round: RoundState,
  val turnId: String?,
  val dealState: DealState,
  val matchScores: Map<String, Int>,
  val winnerIds: List<String>,
  val startedAt: Long,
)

class GameSession(
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val remoteSync: RemoteMatchSync? = null,
) {

  private var matchId: String = "m-" + clock()
  private var mode: String? = null
  private var scoringMode: ScoringMode = ScoringMode.NORMAL
  private var players: List<SessionPlayer> = emptyList()
  private var room: SessionRoom = SessionRoom()
  private var dealerId: String? = null
  private var round: RoundState = RoundState()
  private var turnId: String? = null
  private var hands: Map<String, List<Card>> = emptyMap()
  private var dealState: DealState = DealState()
  private var handAuthority: HandAuthority = HandAuthority.LOCAL
  private var table: TableState? = null
  private var bidding: BiddingState? = null
  private var matchScores: Map<String, Int> = emptyMap()
  private val roundHistory: MutableList<RoundResult> = mutableListOf()
  private var winnerIds: List<String> = emptyList()
  private var startedAt: Long = clock()
  private var started: Boolean = false

  // ── remote mirror ────────────────────────────────────────────────
  // Deliberately NOT part of reset()/init(): switching or resetting the
  // LOCAL session must not silently kill an unrelated, still-active
  // remote subscription, and a live handle cannot be serialized anyway.
  private data class Subscription(val matchId: String, val unsubscribe: () -> Unit)
  private var remoteSubscription: Subscription? = null
  private var remoteMatch: Any? = null
  private var remoteMatchError: Throwable? = null
  private val remoteListeners: MutableList<(RemoteMatchUpdate) -> Unit> = mutableListOf()

  // ════════════════════════════════════════════════════════════════
  // Lifecycle + identity
  // ════════════════════════════════════════════════════════════════

  private fun freshSession(matchMode: String?) {
    matchId = "m-" + clock()
    mode = matchMode
    scoringMode = ScoringMode.NORMAL
    players = emptyList()
    room = SessionRoom()
    dealerId = room.seats.firstOrNull()
    round = RoundState()
    turnId = null
    hands = emptyMap()
    dealState = DealState()
    handAuthority = HandAuthority.LOCAL
    table = null
    bidding = null
    matchScores = room.seats.associateWith { 0 }
    roundHistory.clear()
    winnerIds = emptyList()
    startedAt = clock()
    started = true
  }

  /**
   * Start a match. Keeps an already-started match unless [force] — the
   * same contract as session.js's init() (a reload mid-match must not
   * discard it); only the mode label updates when it differs.
   */
  fun init(matchMode: String? = null, force: Boolean = false): SessionSnapshot {
    if (!started || force) {
      freshSession(matchMode)
    } else if (matchMode != null && mode != matchMode) {
      mode = matchMode
    }
    return snapshot()
  }

  /** Explicit restart — always fresh, regardless of in-progress state. */
  fun reset(matchMode: String? = null): SessionSnapshot {
    freshSession(matchMode)
    return snapshot()
  }

  fun snapshot(): SessionSnapshot = SessionSnapshot(
    matchId = matchId,
    mode = mode,
    scoringMode = scoringMode,
    room = room,
    dealerId = dealerId,
    round = round,
    turnId = turnId,
    dealState = dealState,
    matchScores = matchScores,
    winnerIds = winnerIds.toList(),
    startedAt = startedAt,
  )

  fun getMatchId(): String = matchId
  fun getMode(): String? = mode

  // ── scoring ruleset ──────────────────────────────────────────────
  // Orthogonal to `mode` (ranked/ai/friends is WHO you play, not how
  // Sa'ayda escalation caps). Normal: ×2→×4→×6→×8. Classic caps at ×2.
  fun getScoringMode(): ScoringMode = scoringMode
  fun setScoringMode(mode: ScoringMode) { scoringMode = mode }
  val escalationCap: Int
    get() = if (scoringMode == ScoringMode.CLASSIC) 2 else 8

  // ── players (real only) ───────────────────────────────────────────

  fun getPlayers(): List<SessionPlayer> = players.toList()
  fun getPlayer(seatId: String): SessionPlayer? =
    players.firstOrNull { it.seatId == seatId }

  /** Populate seats from the real roster — the honest replacement for
   *  session.js's baked-in mockPlayers(). */
  fun setPlayers(roster: List<SessionPlayer>) { players = roster.toList() }

  // ── room / dealer / turn ──────────────────────────────────────────

  fun getRoom(): SessionRoom = room
  fun setRoom(room: SessionRoom) { this.room = room }

  fun getDealer(): String? = dealerId
  fun setDealer(seatId: String?) { dealerId = seatId }

  /** Dealer rotates one seat counter-clockwise each round (house rules).
   *  Returns the new dealer, or null if no dealer was ever set. */
  fun rotateDealer(): String? {
    val current = dealerId ?: return null
    require(room.seats.contains(current)) {
      "rotateDealer: dealer '$current' is not a seat of this room: $room"
    }
    dealerId = nextSeat(room.seats, current)
    return dealerId
  }

  fun getTurn(): String? = turnId
  fun setTurn(seatId: String?) { turnId = seatId }

  // ── round ────────────────────────────────────────────────────────

  fun getRound(): RoundState = round

  /** Whole-value update (session.js's patch-merge, Kotlin-idiom shape:
   *  callers pass getRound().copy(...), so a stale snapshot cannot leak
   *  into the round by construction). */
  fun setRound(round: RoundState) { this.round = round }

  /**
   * Advance to the next round: bumps the number, clears exactly the
   * auction-committed fields, rotates the dealer, and invalidates the
   * previous round's deal + both round-scoped engine states so the next
   * Bidding Phase deals exactly once, fresh. The Sa'ayda [RoundState]
   * multiplier survives — escalation is a cross-round property. Returns
   * the new round number.
   */
  fun nextRound(): Int {
    round = round.copy(
      number = round.number + 1,
      trump = null,
      callerId = null,
      withPlayers = emptyList(),
      estimates = emptyMap(),
      dashCallers = emptyList(),
    )
    rotateDealer()
    hands = emptyMap()
    dealState = DealState()
    table = null
    bidding = null
    return round.number
  }

  // ════════════════════════════════════════════════════════════════
  // Hands + deal authority
  // ════════════════════════════════════════════════════════════════

  /** Shuffle + deal a fresh set of hands via Dealer, store them as the
   *  source of truth, and stamp deal metadata for the CURRENT round. */
  fun dealNewHands(): Map<String, List<Card>> {
    hands = Dealer.dealHands()
    dealState = DealState(round.number, completed = true, clock())
    return hands
  }

  fun getHands(): Map<String, List<Card>> = hands
  fun getHand(seatId: String): List<Card> = hands[seatId].orEmpty()
  fun setHand(seatId: String, cards: List<Card>) {
    hands = hands + (seatId to cards.toList())
  }

  /** Whether a valid deal exists for the CURRENT round — decided by
   *  explicit [dealState] metadata, NEVER by hand size. */
  fun hasDealtHands(): Boolean =
    dealState.completed && dealState.roundNumber == round.number

  /**
   * The single funnel every caller should use instead of deciding for
   * itself whether to reshuffle: deals fresh only when this round has no
   * valid deal yet (or [force]); otherwise reuses the cached hands.
   * Under [HandAuthority.FIRESTORE] this NEVER falls back to a local
   * Math.random() deal — forcing a real redeal is a server-transaction
   * decision, never something one client does unilaterally once
   * Firestore is the authority. It returns whatever is cached (possibly
   * empty) and waits for [setAuthoritativeHand].
   */
  fun ensureHandsDealt(force: Boolean = false): Map<String, List<Card>> {
    return when (handAuthority) {
      HandAuthority.FIRESTORE -> hands
      HandAuthority.LOCAL -> if (force || !hasDealtHands()) dealNewHands() else hands
    }
  }

  /**
   * Declare which authority this session trusts for hands. Entering
   * FIRESTORE clears any locally-dealt hands first: a stale local deal
   * must never be mistaken for an authoritative one. A page-session
   * runtime flag, never persisted — a fresh load always redeclares its
   * own context.
   */
  fun setHandAuthorityMode(authority: HandAuthority) {
    if (authority == HandAuthority.FIRESTORE && handAuthority != HandAuthority.FIRESTORE) {
      hands = emptyMap()
      dealState = DealState()
    }
    handAuthority = authority
  }

  fun getHandAuthorityMode(): HandAuthority = handAuthority

  /**
   * Populate the CURRENT round's hand from the server-committed
   * matches/{matchId}/hands/{seatId} document — never from a local
   * shuffle. Marks [dealState] exactly like [dealNewHands], so
   * [hasDealtHands] treats an authoritative hand identically; the only
   * difference is where the cards came from. Writes ONLY the ONE seat
   * passed in — this client never learns another seat's cards.
   */
  fun setAuthoritativeHand(
    seatId: String,
    cards: List<Card>,
    roundNumber: Int,
  ): Map<String, List<Card>> {
    hands = hands + (seatId to cards.toList())
    dealState = DealState(roundNumber, completed = true, clock())
    return hands
  }

  // ════════════════════════════════════════════════════════════════
  // Round-scoped trick state (TableEngine's state, held here)
  // ════════════════════════════════════════════════════════════════

  fun getPlayState(): TableState? = table

  /** Raw write — the path the sync adapter's replay uses. Callers that
   *  START a round should use [initializePlayState] or
   *  [restartPlayState] instead. */
  fun updatePlayState(state: TableState) { table = state }

  /** The stored state is a valid, in-progress record for the CURRENT
   *  round — not a stale survivor of a previous one. */
  fun isPlayStateValidForCurrentRound(): Boolean = table?.cfg?.round == round.number

  /**
   * Start fresh trick-taking progress for the current round from [cfg]
   * (caller leads trick 1). Rejects a config built for any other round.
   */
  fun initializePlayState(cfg: RoundCfg): TableState {
    require(cfg.round == round.number) {
      "initializePlayState: config is for round ${cfg.round}, current round is ${round.number}"
    }
    val state = initTable(cfg, room.seats)
    table = state
    return state
  }

  /** Drop the persisted trick state for this round. */
  fun clearPlayState() { table = null }

  /**
   * THE reload-safe entry point, and the reason P1-3 stays fixed: the
   * persisted state is discarded BEFORE the engine is (re)initialized,
   * so a subsequent authoritative replay from log index 0 converges
   * exactly. Restoring the engine FIRST and replaying afterwards
   * double-resolves history — the phantom-trick class from issue #16.
   * [clearPlayState] + [initializePlayState] in the wrong order is
   * exactly that bug; this method exists so the safe order is the only
   * obvious one.
   */
  fun restartPlayState(cfg: RoundCfg): TableState {
    clearPlayState()
    return initializePlayState(cfg)
  }

  /**
   * Round 13 resolved: mark play DONE and roll the Sa'ayda multiplier
   * forward through the round (one source of truth for it — callers
   * never write the multiplier raw).
   */
  fun completeRound(finalTricksWon: Map<String, Int>, nextMultiplier: Int) {
    val current = table
    if (current != null) {
      table = current.copy(
        phase = TablePhase.DONE,
        tricksWon = finalTricksWon,
        plays = emptyList(),
        turn = null,
      )
    }
    round = round.copy(multiplier = nextMultiplier)
  }

  // ════════════════════════════════════════════════════════════════
  // Round-scoped bidding state (BiddingEngine's state, held here)
  // ════════════════════════════════════════════════════════════════

  fun getBiddingState(): BiddingState? = bidding
  fun updateBiddingState(state: BiddingState) { bidding = state }
  fun isBiddingStateValidForCurrentRound(): Boolean = bidding?.round == round.number

  /**
   * Start a fresh auction for the current round from an initialized
   * [state] (initNormalRound / initFastRound — the dealer opens).
   * Rejects a state built for any other round.
   */
  fun initializeBiddingState(state: BiddingState): BiddingState {
    require(state.round == round.number) {
      "initializeBiddingState: state is for round ${state.round}, current round is ${round.number}"
    }
    bidding = state
    return state
  }

  fun clearBiddingState() { bidding = null }

  /**
   * THE single bidding-completion funnel, called exactly once when the
   * auction result is final: commits the outcome into the round (what
   * Game Table reads), closes the bidding state, and stamps the first
   * turn for trick play. Never reconstructed by a second caller.
   */
  fun completeBidding(result: BiddingOutcome) {
    val current = bidding
    if (current != null) {
      bidding = current.copy(
        subPhase = BiddingPhase.DONE,
        callerId = result.callerId,
        withPlayers = result.withPlayers,
        declaredTrump = result.trump,
        waitingFor = null,
      )
    }
    round = round.copy(
      trump = result.trump,
      callerId = result.callerId,
      withPlayers = result.withPlayers,
      estimates = result.estimates,
      dashCallers = result.dashCallers,
    )
    // The outcome's leader is always resolved by the bidding engine —
    // the caller when there is one, the dealer when all seats dashed.
    turnId = result.leaderId
  }

  // ════════════════════════════════════════════════════════════════
  // Scores + completion
  // ════════════════════════════════════════════════════════════════

  fun getMatchScores(): Map<String, Int> = matchScores

  /** Whole-value update (scoring's accumulateMatchScores builds the
   *  next map; this store never mutates scores incrementally). */
  fun setMatchScores(scores: Map<String, Int>) { matchScores = scores }

  /** Record one scored round so Final Standings seeds from what
   *  actually happened. */
  fun recordRoundResult(result: RoundResult) { roundHistory += result }

  fun getLastRoundResult(): RoundResult? = roundHistory.lastOrNull()

  fun getRoundHistory(): List<RoundResult> = roundHistory.toList()

  /**
   * Match is complete when the current round reaches maxRounds (after
   * Super Call / Sa'ayda extensions raise it). This does NOT compute a
   * winner — that is ScoringEngine.computeWinner() + [setWinnerIds].
   */
  fun isMatchComplete(): Boolean = round.number >= round.maxRounds

  /**
   * The authoritative multi-winner result. Always a list — empty
   * before/during the match; per house rules ALL seats tied at the
   * highest final score are Kings (2, 3, or 4 ids is legitimate), with
   * no numeric or suit tie-breaker.
   */
  fun getWinnerIds(): List<String> = winnerIds.toList()
  fun setWinnerIds(ids: List<String>) { winnerIds = ids.toList() }

  // ════════════════════════════════════════════════════════════════
  // Remote match mirror (delegates to RemoteMatchSync ONLY)
  // ════════════════════════════════════════════════════════════════

  private fun remotePayload(): RemoteMatchUpdate = RemoteMatchUpdate(
    matchId = remoteSubscription?.matchId,
    data = remoteMatch,
    error = remoteMatchError,
  )

  private fun safeInvoke(
    callback: (RemoteMatchUpdate) -> Unit,
    payload: RemoteMatchUpdate,
  ) {
    try {
      callback(payload)
    } catch (e: Throwable) {
      // One listener's bug must not starve the others — mirrors
      // session.js's safeInvokeRemoteMatchListener. Nowhere to log in a
      // pure JVM module; the error surfaces in the listener's own crash.
    }
  }

  private fun notifyRemoteMatchListeners() {
    val payload = remotePayload()
    remoteListeners.forEach { safeInvoke(it, payload) }
  }

  /**
   * Begin consuming live sync for one matchId. Idempotent for the SAME
   * matchId (a repeat call is a no-op — without that guard the first
   * handle would leak forever, since this store holds exactly one).
   * Switching to a DIFFERENT matchId tears the old one down first.
   * Fails open with no sync port configured (an offline-only page):
   * subscribes to nothing, never throws.
   */
  fun subscribeToRemoteMatch(matchId: String?) {
    if (matchId.isNullOrEmpty()) return
    val current = remoteSubscription
    if (current != null && current.matchId == matchId) return
    unsubscribeFromRemoteMatch()
    val sync = remoteSync ?: return
    val unsubscribe = sync.subscribeToMatch(matchId) { data, error ->
      remoteMatchError = error
      if (error == null) remoteMatch = data // fail-open: an error never clears good state
      notifyRemoteMatchListeners()
    }
    remoteSubscription = Subscription(matchId, unsubscribe)
  }

  /** Clean, explicit teardown — the only path that ever calls the stored
   *  unsubscribe, and it is stored at most once, so nothing leaks. A
   *  no-op when nothing is subscribed. */
  fun unsubscribeFromRemoteMatch() {
    remoteSubscription?.unsubscribe?.invoke()
    remoteSubscription = null
  }

  fun getRemoteMatch(): Any? = remoteMatch
  fun getRemoteMatchError(): Throwable? = remoteMatchError
  fun isSubscribedToRemoteMatch(): Boolean = remoteSubscription != null

  /**
   * Subscribe to this store's OWN remote-match updates — fires
   * immediately with the current value, then on every change; returns
   * the unsubscribe. Screens/engines use this instead of ever reaching
   * into the sync layer themselves.
   */
  fun onRemoteMatchUpdate(callback: (RemoteMatchUpdate) -> Unit): () -> Unit {
    remoteListeners.add(callback)
    safeInvoke(callback, remotePayload())
    val unsubscribe: () -> Unit = { remoteListeners.remove(callback) }
    return unsubscribe
  }
}
