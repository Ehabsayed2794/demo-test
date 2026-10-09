/* ════════════════════════════════════════════════════════════════════
   Estimation — Centralized Game Session
   Represents ONE playable match: players, room, dealer, round, bid,
   turn, scores, winner. Persists across page navigation (sessionStorage)
   so every screen reads the same data instead of declaring its own
   PLAYERS/state constants. Mock data only — no networking. Field names
   and shapes are written so a future networking layer can replace the
   mock generators without changing the public API (see GameSession.md).
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  var STORAGE_KEY = "estimation_game_session_v1";
  var CANONICAL_ORDER = ["p1", "p2", "p3", "p4"];

  function nextCCW(id) {
    var i = CANONICAL_ORDER.indexOf(id);
    return CANONICAL_ORDER[(i + 1) % CANONICAL_ORDER.length];
  }

  function freshPlayState() {
    return {
      roundNumber: null,
      initialized: false,
      phase: "PLAY",
      trickNumber: 1,
      leaderId: null,
      turnId: null,
      ledSuit: null,
      currentPlays: [],
      tricksWon: { p1: 0, p2: 0, p3: 0, p4: 0 },
      voids: { p1: [], p2: [], p3: [], p4: [] },
      lastTrick: null,
      completed: false
    };
  }

  function freshBiddingState() {
    return {
      roundNumber: null,
      initialized: false,
      phase: "DASH",              // DASH | AUCTION | CONFIRM | ESTIMATES | DONE (Estimation's actual sub-phases)
      turnId: null,
      openingPlayerId: null,
      bids: { p1: null, p2: null, p3: null, p4: null },   // per-seat raw bid record {type, amount} or null
      passedPlayers: [],           // informational mirror of eliminated seats — see BiddingState.md
      activeBidders: ["p1", "p2", "p3", "p4"],
      auctionTop: 0,
      auctionSuit: null,
      auctionBidderId: null,
      callerId: null,
      withPlayers: [],
      declaredTrump: null,
      estimates: { p1: null, p2: null, p3: null, p4: null },
      dashCallers: [],        // ids who made a pre-bidding Dash Call this round — extractEstimates() drops them (TRICKS-only), so this is the only place their type survives to Game Table/scoring
      riskPlayerId: null,
      lastBidderId: null,     // whoever actually submitted the round's final estimate — see BiddingState.md (fast rounds have no callerId to derive Risk from)
      actionHistory: [],
      completed: false
    };
  }

  // ── mock player roster ──────────────────────────────────────────
  // isAI is true once a match mode is known and the seat isn't the
  // human player — future multiplayer would instead mark seats
  // isAI:false / isRemote:true and fill them from the network roster,
  // without changing any field consumers already read.
  function mockPlayers(mode) {
    var ai = mode !== "friends" && mode !== "room"; // ranked/ai/solo practice → bots fill empty seats
    return [
      { id: "p1", name: "You",    initial: "Y", isUser: true,  isAI: false, isRemote: false, rank: "Gold III",   rp: 1240, wins: 142, streak: 2, level: 18, coins: 2400, gems: 120 },
      { id: "p2", name: "Layla",  initial: "L", isUser: false, isAI: ai,    isRemote: false, rank: "Gold I",      rp: 980,  wins: 318, streak: 4, level: 22, coins: 0, gems: 0 },
      { id: "p3", name: "Fatima", initial: "F", isUser: false, isAI: ai,    isRemote: false, rank: "Platinum IV", rp: 1510, wins: 201, streak: 0, level: 27, coins: 0, gems: 0 },
      { id: "p4", name: "Omar",   initial: "O", isUser: false, isAI: ai,    isRemote: false, rank: "Diamond II",  rp: 2210, wins: 540, streak: 1, level: 34, coins: 0, gems: 0 }
    ];
  }

  function freshSession(mode) {
    return {
      matchId: "m-" + Date.now().toString(36),
      mode: mode || null,
      // Scoring ruleset — orthogonal to `mode` (ranked/ai/friends), which is
      // about WHO you're playing, not how Sa'ayda escalation caps. Normal
      // is the default: ×2→×4→×6→×8 ladder. Classic caps at ×2. See
      // ScoringEngine.md.
      scoringMode: "normal",
      players: mockPlayers(mode),
      room: { code: null, host: true, seats: ["p1", "p2", "p3", "p4"] },
      dealerId: "p1",
      round: {
        number: 1, maxRounds: 18, multiplier: 1,
        trump: null, callerId: null, withPlayers: [], estimates: {}, dashCallers: []
      },
      turnId: null,
      hands: {},              // per-seat dealt cards — owned by the Card Engine (dealer.js), stored here
      dealState: { roundNumber: null, completed: false, dealtAt: null }, // deal metadata for the round `hands` belongs to
      playState: freshPlayState(),  // persisted trick-taking progress for the current round — see GameSession.md
      biddingState: freshBiddingState(), // persisted auction progress for the current round — see BiddingState.md
      teamScores: {},          // reserved: partnership variants, unused in solo-vs-3 mode
      matchScores: { p1: 0, p2: 0, p3: 0, p4: 0 },
      roundHistory: [],        // [{round, trump, callerId, tricksWon, estimates}]
      winnerId: null,
      startedAt: Date.now()
    };
  }

  function load() {
    try {
      var raw = sessionStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }
  function persist() {
    try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session)); } catch (e) {}
  }

  var session = load() || freshSession(null);
  // Backward compatibility: sessions saved before playState/biddingState
  // existed, or that still carry the old, never-consumed `bid` object.
  // Apply safe defaults without touching valid hands/dealState/round data.
  // The old `session.bid` field is RETIRED (not migrated) — nothing ever
  // read it (see BiddingState.md § Backward Compatibility), so it is
  // simply dropped in favor of the new `biddingState`.
  (function migrate() {
    var dirty = false;
    if (!session.playState) { session.playState = freshPlayState(); dirty = true; }
    if (!session.biddingState) { session.biddingState = freshBiddingState(); dirty = true; }
    if (session.bid) { delete session.bid; dirty = true; }
    if (!session.hands) { session.hands = {}; dirty = true; }
    if (!session.dealState) { session.dealState = { roundNumber: null, completed: false, dealtAt: null }; dirty = true; }
    if (!session.scoringMode) { session.scoringMode = "normal"; dirty = true; }
    if (session.round && !session.round.dashCallers) { session.round.dashCallers = []; dirty = true; }
    if (session.biddingState && !session.biddingState.dashCallers) { session.biddingState.dashCallers = []; dirty = true; }
    if (dirty) persist();
  })();
  if (!load()) persist();

  /** Start a brand-new match session (called when entering a game-mode
   *  flow). By default keeps an existing in-progress session so a page
   *  refresh mid-match doesn't lose it — pass {force:true} to start over. */
  function init(mode, opts) {
    opts = opts || {};
    if (!load() || opts.force) {
      session = freshSession(mode);
      persist();
    } else if (mode && session.mode !== mode) {
      session.mode = mode;
      persist();
    }
    return session;
  }

  function get() { return session; }
  // Scoring ruleset ("normal" | "classic") — governs the Sa'ayda escalation
  // cap (×8 vs ×2). No settings UI selects this yet; it's exposed here so
  // the escalation formula is mode-aware from day one instead of needing a
  // retrofit later. Defaults to "normal".
  function getScoringMode() { return session.scoringMode || "normal"; }
  function setScoringMode(mode) {
    session.scoringMode = (mode === "classic") ? "classic" : "normal";
    persist();
  }
  function getPlayers() { return session.players.slice(); }
  function getAIPlayers() { return session.players.filter(function (p) { return p.isAI; }); }
  function getPlayer(id) { return session.players.find(function (p) { return p.id === id; }) || null; }

  function getRoom() { return session.room; }
  function setRoom(patch) { session.room = Object.assign({}, session.room, patch); persist(); }

  function getDealer() { return session.dealerId; }
  function setDealer(id) { session.dealerId = id; persist(); }
  /** Dealer rotates one seat counter-clockwise each round (mirrors the
   *  house rules doc). */
  function rotateDealer() { session.dealerId = nextCCW(session.dealerId); persist(); return session.dealerId; }

  function getRound() { return session.round; }
  function setRound(patch) { session.round = Object.assign({}, session.round, patch); persist(); }
  function nextRound() {
    session.round = Object.assign({}, session.round, {
      number: session.round.number + 1, trump: null, callerId: null, withPlayers: [], estimates: {}, dashCallers: []
    });
    rotateDealer();
    // invalidate the previous round's deal — the next Bidding Phase must deal exactly once, fresh
    session.hands = {};
    session.dealState = { roundNumber: null, completed: false, dealtAt: null };
    session.playState = freshPlayState();
    session.biddingState = freshBiddingState();
    persist();
  }

  function getTurn() { return session.turnId; }
  function setTurn(id) { session.turnId = id; persist(); }

  // ── cards (Card Engine integration) ────────────────────────────
  /** Shuffle + deal a brand-new set of hands via Dealer, store them as
   *  the session's source of truth, stamp deal metadata for the CURRENT
   *  round, and return them. No screen should call Deck/Dealer directly
   *  and keep its own copy — always go through here (or, better,
   *  ensureHandsDealt()) so every screen renders the same hands. */
  function dealNewHands() {
    session.hands = Dealer.dealHands();
    session.dealState = { roundNumber: session.round.number, completed: true, dealtAt: Date.now() };
    persist();
    return session.hands;
  }
  function getHands() { return session.hands; }
  function getHand(id) { return session.hands[id] || []; }
  function setHand(id, cards) { session.hands = Object.assign({}, session.hands, { [id]: cards }); persist(); }

  /** Whether a valid deal already exists for the CURRENT round. Deal
   *  validity is tracked by explicit metadata (dealState), never by
   *  hand size — a player can legitimately reach zero cards by playing
   *  them all, and that must still count as "dealt" for this round. */
  function hasDealtHands() {
    return !!(session.dealState && session.dealState.completed && session.dealState.roundNumber === session.round.number);
  }

  // ── round play state (trick-taking progress) ───────────────────
  // GameSession is the source of truth for persistent round progress.
  // table-engine.js keeps a working `state` object for its own reducer
  // logic, but every accepted game action must go through these APIs
  // to persist — it must never touch sessionStorage directly.
  function getPlayState() { return session.playState; }

  /** Whether the stored playState is a valid, in-progress record for
   *  the CURRENT round (not stale from a previous round). */
  function isPlayStateValidForCurrentRound() {
    return !!(session.playState && session.playState.initialized && session.playState.roundNumber === session.round.number);
  }

  /** Start fresh trick-taking progress for the current round (caller
   *  leads first trick, tricksWon/voids at zero unless overridden). */
  function initializePlayState(config) {
    config = config || {};
    var base = freshPlayState();
    session.playState = Object.assign(base, {
      roundNumber: session.round.number,
      initialized: true,
      leaderId: config.leaderId != null ? config.leaderId : base.leaderId,
      turnId: config.turnId != null ? config.turnId : config.leaderId,
      tricksWon: config.tricksWon || base.tricksWon,
      voids: config.voids || base.voids
    });
    persist();
    return session.playState;
  }

  function updatePlayState(patch) {
    session.playState = Object.assign({}, session.playState, patch);
    persist();
    return session.playState;
  }

  /** Persist the result of one accepted card play: the player's updated
   *  hand, the current trick-in-progress, led suit, whose turn is next,
   *  updated voids, and phase. */
  function recordCardPlay(result) {
    updatePlayState({
      currentPlays: result.currentPlays,
      ledSuit: result.ledSuit,
      turnId: result.nextTurnId,
      phase: result.phase,
      voids: result.voids || session.playState.voids
    });
    if (result.playerId && result.hand) setHand(result.playerId, result.hand);
  }

  /** Persist the result of a resolved trick: winner's tally, last-trick
   *  record, next trick number/leader/turn, and clears the in-progress
   *  trick. */
  function recordResolvedTrick(result) {
    updatePlayState({
      tricksWon: result.tricksWon,
      lastTrick: result.lastTrick,
      trickNumber: result.nextTrickNumber,
      leaderId: result.nextLeaderId,
      turnId: result.nextTurnId,
      currentPlays: [],
      ledSuit: null,
      phase: result.phase || "PLAY"
    });
  }

  /** Persist round completion (13th trick resolved): final tricksWon,
   *  DONE phase, and (optionally) the next Sa'ayda multiplier — routed
   *  through setRound() so there is one source of truth for it, never a
   *  raw sessionStorage write from table-engine.js. */
  function completeRound(result) {
    updatePlayState({
      completed: true,
      phase: "DONE",
      tricksWon: result.tricksWon,
      currentPlays: [],
      turnId: null
    });
    if (result.multiplier != null) setRound({ multiplier: result.multiplier });
  }

  function clearPlayState() { session.playState = freshPlayState(); persist(); }

  // ── bidding state (auction progress) ──────────────────
  // GameSession is the single source of truth for persistent bidding
  // progress. bidding-engine.js keeps a working `state` object for its
  // own reducer logic, but every accepted bidding action must persist
  // through these APIs — it must never touch sessionStorage directly.
  function getBiddingState() { return session.biddingState; }

  /** Whether the stored biddingState is a valid, in-progress record for
   *  the CURRENT round (not stale from a previous round). */
  function isBiddingStateValidForCurrentRound() {
    return !!(session.biddingState && session.biddingState.initialized && session.biddingState.roundNumber === session.round.number);
  }

  /** Start a fresh auction for the current round — opening player bids
   *  first (the dealer, per house rules). DASH is the first sub-phase for
   *  a normal round (1-13); fast rounds (14-18) have no Dash Call/Auction
   *  at all, so bidding-engine.js passes phase:"ESTIMATES" and the round's
   *  forced trump directly for those. */
  function initializeBiddingState(config) {
    config = config || {};
    session.biddingState = Object.assign(freshBiddingState(), {
      roundNumber: session.round.number,
      initialized: true,
      turnId: config.openingPlayerId,
      openingPlayerId: config.openingPlayerId,
      phase: config.phase || "DASH",
      declaredTrump: config.declaredTrump != null ? config.declaredTrump : null,
      auctionSuit: config.auctionSuit != null ? config.auctionSuit : null,
      auctionTop: config.auctionTop || 0
    });
    persist();
    return session.biddingState;
  }

  function updateBiddingState(patch) {
    session.biddingState = Object.assign({}, session.biddingState, patch);
    persist();
    return session.biddingState;
  }

  function pushBiddingAction(entry) {
    var history = session.biddingState.actionHistory.slice();
    history.push(Object.assign({ seq: history.length + 1, ts: Date.now() }, entry));
    session.biddingState = Object.assign({}, session.biddingState, { actionHistory: history });
  }

  /** Persist one accepted bid/dash-call/raise/with action that does NOT
   *  eliminate the player (see recordPassAction for eliminations). */
  function recordBidAction(result) {
    pushBiddingAction({ playerId: result.playerId, actionType: result.actionType, value: result.value, suit: result.suit });
    updateBiddingState({
      bids: result.bids, activeBidders: result.activeBidders,
      auctionTop: result.auctionTop, auctionSuit: result.auctionSuit, auctionBidderId: result.auctionBidderId,
      withPlayers: result.withPlayers, phase: result.phase, turnId: result.turnId
    });
  }

  /** Persist a pass/elimination (Dash-decline is NOT a pass — it's
   *  recorded via recordBidAction; this is for auction eliminations and
   *  the terminal general-pass case). */
  function recordPassAction(result) {
    pushBiddingAction({ playerId: result.playerId, actionType: "PASS" });
    updateBiddingState({
      passedPlayers: result.passedPlayers, activeBidders: result.activeBidders,
      phase: result.phase, turnId: result.turnId
    });
  }

  /** Persist the auction's winning bidder — the Confirmation phase
   *  (trump selection) begins from here. */
  function setAuctionWinner(result) {
    pushBiddingAction({ playerId: result.callerId, actionType: "AUCTION_WON", value: result.auctionTop, suit: result.auctionSuit });
    updateBiddingState({
      callerId: result.callerId, auctionTop: result.auctionTop, auctionSuit: result.auctionSuit,
      phase: result.phase, turnId: result.turnId
    });
  }

  /** Persist the With (Wazz) player list as it's updated during the
   *  auction. */
  function updateWithSelection(withPlayers) {
    updateBiddingState({ withPlayers: withPlayers.slice() });
  }

  /** Persist one accepted final estimate. */
  function recordEstimate(result) {
    pushBiddingAction({ playerId: result.playerId, actionType: "ESTIMATE", value: result.tricks });
    var patch = { bids: result.bids, estimates: result.estimates, phase: result.phase, turnId: result.turnId };
    if (result.lastBidderId !== undefined) patch.lastBidderId = result.lastBidderId;
    updateBiddingState(patch);
  }

  /** THE single centralized bidding-completion method. Called exactly
   *  once, by bidding-engine.js's reducer, the moment the auction result
   *  is final — never reconstructed a second time by a screen/button
   *  handler. Commits the result into `round` (what Game Table reads)
   *  and stamps the first turn for Gameplay. */
  function completeBidding(result) {
    updateBiddingState({
      completed: true, phase: "DONE",
      callerId: result.callerId, withPlayers: result.withPlayers || [],
      estimates: result.estimates || {}, dashCallers: result.dashCallers || [],
      declaredTrump: result.trump,
      riskPlayerId: result.riskPlayerId != null ? result.riskPlayerId : null
    });
    setRound({
      trump: result.trump, callerId: result.callerId,
      withPlayers: result.withPlayers || [], estimates: result.estimates || {},
      dashCallers: result.dashCallers || []
    });
    setTurn(result.leaderId != null ? result.leaderId : result.callerId);
  }

  function clearBiddingState() { session.biddingState = freshBiddingState(); persist(); }

  /** The single funnel every screen should call instead of deciding for
   *  itself whether to reshuffle: deals fresh only if this round has no
   *  valid deal yet, otherwise reuses the existing session hands.
   *  Pass {force:true} for an explicit restart/reset action that must
   *  redeal regardless. */
  function ensureHandsDealt(opts) {
    opts = opts || {};
    if (opts.force || !hasDealtHands()) return dealNewHands();
    return session.hands;
  }

  function getTeamScores() { return session.teamScores; }  function getMatchScores() { return session.matchScores; }
  function setMatchScores(scores) { session.matchScores = Object.assign({}, session.matchScores, scores); persist(); }

  /** Called when a round of trick-play finishes, so Final Standings can
   *  seed its outcome from what actually happened instead of being
   *  fully random. */
  function recordRoundResult(result) {
    session.roundHistory.push(Object.assign({ round: session.round.number }, result));
    persist();
  }
  function getLastRoundResult() {
    return session.roundHistory.length ? session.roundHistory[session.roundHistory.length - 1] : null;
  }

  function getWinner() { return session.winnerId; }
  function setWinner(id) { session.winnerId = id; persist(); }

  function reset(mode) {
    session = freshSession(mode);   // freshSession() already clears hands + dealState + playState + biddingState
    persist();
    return session;
  }

  global.GameSession = {
    init: init, reset: reset, get: get,
    getScoringMode: getScoringMode, setScoringMode: setScoringMode,
    getPlayers: getPlayers, getAIPlayers: getAIPlayers, getPlayer: getPlayer,
    getRoom: getRoom, setRoom: setRoom,
    getDealer: getDealer, setDealer: setDealer, rotateDealer: rotateDealer,
    getRound: getRound, setRound: setRound, nextRound: nextRound,
    getTurn: getTurn, setTurn: setTurn,
    dealNewHands: dealNewHands, ensureHandsDealt: ensureHandsDealt, getHands: getHands, getHand: getHand, setHand: setHand, hasDealtHands: hasDealtHands,
    getPlayState: getPlayState, isPlayStateValidForCurrentRound: isPlayStateValidForCurrentRound,
    initializePlayState: initializePlayState, updatePlayState: updatePlayState,
    recordCardPlay: recordCardPlay, recordResolvedTrick: recordResolvedTrick,
    completeRound: completeRound, clearPlayState: clearPlayState,
    getBiddingState: getBiddingState, isBiddingStateValidForCurrentRound: isBiddingStateValidForCurrentRound,
    initializeBiddingState: initializeBiddingState, updateBiddingState: updateBiddingState,
    recordBidAction: recordBidAction, recordPassAction: recordPassAction,
    setAuctionWinner: setAuctionWinner, updateWithSelection: updateWithSelection,
    recordEstimate: recordEstimate, completeBidding: completeBidding, clearBiddingState: clearBiddingState,
    getTeamScores: getTeamScores, getMatchScores: getMatchScores, setMatchScores: setMatchScores,
    recordRoundResult: recordRoundResult, getLastRoundResult: getLastRoundResult,
    getWinner: getWinner, setWinner: setWinner
  };
})(window);
