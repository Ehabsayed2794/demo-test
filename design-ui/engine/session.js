/* ════════════════════════════════════════════════════════════════════
   Estimation — Centralized Game Session
   Represents ONE playable match: players, room, dealer, round, bid,
   turn, scores, winner. Persists across page navigation (sessionStorage)
   so every screen reads the same data instead of declaring its own
   PLAYERS/state constants. Mock data only — no networking. Field names
   and shapes are written so a future networking layer can replace the
   mock generators without changing the public API (see GameSession.md).

   Sprint 3.7 (Real-Time Match Synchronization): GameSession now also
   holds a live MIRROR of whatever MatchService publishes for one
   matchId — see subscribeToRemoteMatch()/onRemoteMatchUpdate() below
   and docs/architecture/MatchSynchronization.md. This is deliberately
   a thin, separate mirror (remoteMatch), not a merge into the seat-id
   (p1..p4) fields above: matches/{matchId} identifies players/dealer/
   turn by real Firebase Auth uid, and reconciling that with this
   engine's seat-id space is out of this sprint's scope (see
   MatchSynchronization.md's "Known Limitation" note). GameSession
   still never talks to Firestore directly — every remote update
   arrives already-decoded through MatchService.subscribeToMatch(), the
   sole Firestore-facing entry point for this.
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
      // deal metadata for the round `hands` belongs to. `source` (added
      // Sprint H) records WHERE these hands came from -- "local"
      // (Dealer.dealHands(), an in-browser random deal) or "firestore"
      // (setAuthoritativeHand(), a real server-committed hand) -- and,
      // unlike handAuthorityMode below, IS persisted, because it must
      // survive a reload: see setHandAuthorityMode()'s own comment for
      // why the transient runtime flag alone isn't enough to tell real
      // hand data from fabricated leftovers across a page reload.
      dealState: { roundNumber: null, completed: false, dealtAt: null, source: null },
      playState: freshPlayState(),  // persisted trick-taking progress for the current round — see GameSession.md
      biddingState: freshBiddingState(), // persisted auction progress for the current round — see BiddingState.md
      teamScores: {},          // reserved: partnership variants, unused in solo-vs-3 mode
      matchScores: { p1: 0, p2: 0, p3: 0, p4: 0 },
      roundHistory: [],        // [{round, trump, callerId, tricksWon, estimates}]
      // Match Completion sprint: MULTIPLE winners are a real house rule
      // (all seats tied at the highest final score are Kings — no
      // numeric/suit tie-breaker) — always an array, never a singular
      // scalar. See ScoringEngine.computeWinner() and getWinnerIds()/
      // setWinnerIds() below.
      winnerIds: [],
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

  // Player Hand Synchronization sprint: which authority `ensureHandsDealt()`
  // trusts for THIS page. "local" (default, unchanged behavior) means
  // dealing continues exactly as before — Dealer.dealHands() runs
  // in-browser via Math.random(), same as every prior sprint. "firestore"
  // means this page is running against a real MatchService/MatchAdapter
  // sync layer (see match-adapter.js's startHandSync()) — in that mode
  // ensureHandsDealt() must NEVER fall back to a local deal when a hand
  // isn't cached yet; it waits for setAuthoritativeHand() to be called
  // once the server-committed hands/{seatId} document arrives. This is
  // a page-session runtime flag, deliberately NOT persisted to
  // sessionStorage (same reasoning as remoteMatchSubscription below — a
  // fresh load must always redeclare its own context, never "resume" a
  // stale mode from storage).
  var handAuthorityMode = "local";

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
    if (!session.dealState) { session.dealState = { roundNumber: null, completed: false, dealtAt: null, source: null }; dirty = true; }
    // Sprint H.1 (post-ship review fix): a session persisted BEFORE
    // `dealState.source` existed (i.e. any real, in-progress "firestore"
    // match already live at the moment this field was introduced) has
    // `dealState.completed === true` and a real, already-synced hand in
    // `session.hands`, but no `source` key at all. Aliasing a genuinely
    // MISSING key to the same `null` value used for "known fabricated"
    // would make setHandAuthorityMode("firestore")'s wipe-guard treat
    // that real hand as fabricated on this session's very next reload —
    // reproducing the exact bug this whole mechanism exists to prevent,
    // just at the deploy boundary instead of an ordinary reload.
    // Whether a MISSING source key represents real or fabricated data is
    // genuinely ambiguous (both a legacy local deal and a legacy real
    // deal look identical here — completed:true, no source) — but
    // wiping real user data is the worse failure mode of the two, so an
    // already-completed deal with real cards on the books is trusted as
    // "firestore" rather than nulled out. An incomplete/empty deal has
    // nothing to lose either way and is left `null` (matches a fresh
    // session -- no different behavior than before this migration
    // existed).
    if (session.dealState && session.dealState.source === undefined) {
      session.dealState.source = (session.dealState.completed && session.hands && Object.keys(session.hands).length > 0)
        ? "firestore" : null;
      dirty = true;
    }
    if (!session.scoringMode) { session.scoringMode = "normal"; dirty = true; }
    if (session.round && !session.round.dashCallers) { session.round.dashCallers = []; dirty = true; }
    if (session.biddingState && !session.biddingState.dashCallers) { session.biddingState.dashCallers = []; dirty = true; }
    // Match Completion sprint: retire the old singular `winnerId` field
    // (never read by anything — see this sprint's discovery) in favor
    // of `winnerIds` (array, supports the real multi-King house rule).
    // A session persisted before this sprint gets a fresh empty array,
    // never a [oldWinnerId] guess — nothing ever set the old field
    // for a genuinely completed match, so there is nothing meaningful
    // to migrate.
    if (session.winnerId !== undefined) { delete session.winnerId; dirty = true; }
    if (!session.winnerIds) { session.winnerIds = []; dirty = true; }
    if (session.round && session.round.maxRounds == null) { session.round.maxRounds = 18; dirty = true; }
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
    session.dealState = { roundNumber: null, completed: false, dealtAt: null, source: null };
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
    session.dealState = { roundNumber: session.round.number, completed: true, dealtAt: Date.now(), source: "local" };
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

  /** Player Hand Synchronization sprint: "firestore" pages call this
   *  once, up front, before anything reads hands. Never persisted (see
   *  the flag's own declaration above) — a fresh load must re-declare
   *  its context every time, exactly like remoteMatchSubscription. */
  function setHandAuthorityMode(mode) {
    var next = (mode === "firestore") ? "firestore" : "local";
    // Sprint H (Remote Hand State / Table Engine Initialization Fix) —
    // ROOT CAUSE FIX. table-engine.js's own DOMContentLoaded auto-init
    // calls initState() -> ensureHandsDealt() unconditionally, on every
    // page load, before this function has any chance to run — so
    // whenever a real multiplayer page's own matchId/hand-authority
    // context isn't resolvable yet at that exact moment (a cold
    // reconnect, a direct/bookmarked URL open, or simply this client's
    // own earlier moment on the very same page before matchId became
    // known), ensureHandsDealt() may already have dealt and PERSISTED
    // (via this module's own sessionStorage-backed persist(), which
    // survives a later reload) a fully-fabricated, independently-random
    // 13-card hand for ALL FOUR seats, believing itself to be in
    // ordinary offline/local mode. If left in place, that fabricated
    // data would otherwise linger for every non-local seat for the rest
    // of the page's life (setAuthoritativeHand() below only ever
    // overwrites the ONE seat it's given), which is the confirmed root
    // cause of TableEngine incorrectly rejecting (and, before Phase 3's
    // separate defensive fix, crashing on) real remote card plays.
    //
    // FIXED (post-ship code review): the first version of this fix
    // gated the wipe on `handAuthorityMode !== "firestore"` — the
    // in-memory runtime flag. That is WRONG across a reload: this flag
    // is deliberately never persisted (see its own declaration above —
    // "a fresh load must always redeclare its own context"), so it
    // resets to "local" on every page load, while `session.hands`/
    // `dealState` DO persist via sessionStorage. A player reloading
    // mid-match with an already-synced, real authoritative hand would
    // hit `next === "firestore" && handAuthorityMode !== "firestore"`
    // (true on every reload, since the flag just reset) and this
    // function would wipe that real hand — reproduced directly with a
    // persistent-sessionStorage harness during review. The correct
    // signal for "is what's already in session.hands real or
    // fabricated" is `dealState.source`, which IS persisted and is
    // set by whichever function actually put the current hands there
    // (dealNewHands() stamps "local"; setAuthoritativeHand() stamps
    // "firestore" — see both below) — so it survives a reload exactly
    // as long as the hands themselves do, and correctly tells this
    // function whether the pre-existing data was ever confirmed
    // authoritative, regardless of what this transient flag says.
    if (next === "firestore" && !(session.dealState && session.dealState.source === "firestore")) {
      session.hands = {};
      session.dealState = { roundNumber: null, completed: false, dealtAt: null, source: null };
      persist();
    }
    handAuthorityMode = next;
  }
  function getHandAuthorityMode() { return handAuthorityMode; }

  /** Player Hand Synchronization sprint: populate the CURRENT round's
   *  hand from the server-committed matches/{matchId}/hands/{seatId}
   *  document (via MatchAdapter's hand-sync bridge) — never from
   *  Dealer.dealHands()/Math.random(). Marks dealState exactly like
   *  dealNewHands() does, so hasDealtHands()/ensureHandsDealt() treat an
   *  authoritative hand identically to a local deal; the only
   *  difference is where the cards came from. Only ever writes the
   *  ONE seat passed in — this client never learns (and this function
   *  never accepts) any other seat's cards. */
  function setAuthoritativeHand(seatId, cards, round) {
    session.hands = Object.assign({}, session.hands, { [seatId]: cards });
    session.dealState = { roundNumber: round, completed: true, dealtAt: Date.now(), source: "firestore" };
    persist();
    return session.hands;
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
    // Player Hand Synchronization sprint: in "firestore" mode this
    // function must NEVER fall back to a local Math.random() deal when
    // the authoritative hand hasn't arrived yet — it waits (returns
    // whatever is already cached, which may be `{}` before the first
    // setAuthoritativeHand() call lands). `opts.force` has no meaning
    // here either: forcing a REAL redeal is a server-transaction
    // decision (MatchService.dealRound()), never something a single
    // client can do unilaterally once Firestore is the authority.
    if (handAuthorityMode === "firestore") {
      return session.hands;
    }
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

  /** Match Completion sprint: derived query to determine if the match has
   *  ended. Match is complete when currentRound >= maxRounds (after
   *  accounting for Super Call extensions, which increment maxRounds
   *  dynamically). This does NOT compute a winner — that is
   *  ScoringEngine's responsibility. See ScoringEngine.computeWinner()
   *  and Match Completion docs. */
  function isMatchComplete() {
    return session.round.number >= session.round.maxRounds;
  }

  /** Match Completion sprint: the authoritative multi-winner result.
   *  Always an array — empty before/during the match, 1+ seat ids once
   *  `endMatch()` (local caller) has determined the match is over. Per
   *  house rules, ALL seats tied at the highest final score are Kings —
   *  there is no tie-breaker, so this can legitimately hold 2, 3, or 4
   *  ids. See ScoringEngine.computeWinner(). */
  function getWinnerIds() { return session.winnerIds.slice(); }
  function setWinnerIds(ids) { session.winnerIds = Array.isArray(ids) ? ids.slice() : []; persist(); }

  function reset(mode) {
    session = freshSession(mode);   // freshSession() already clears hands + dealState + playState + biddingState
    persist();
    return session;
  }

  // ── remote match synchronization (Sprint 3.7) ──────────────────
  // Deliberately NOT persisted to sessionStorage: a live subscription
  // handle can't be serialized, and a fresh page load must always
  // register a fresh onSnapshot listener anyway (never "resumed" from
  // storage) — matching how Firestore listeners actually work. Also
  // deliberately independent of reset()/init() above: switching or
  // resetting the LOCAL mock session should not silently kill an
  // unrelated, still-active remote subscription.
  var remoteMatchSubscription = null; // { matchId, unsubscribe } | null
  var remoteMatch = null;             // last data MatchService published, or null
  var remoteMatchError = null;        // last error MatchService reported, or null — fail-open, never clears remoteMatch
  var remoteMatchListeners = [];      // GameSession's OWN local pub/sub over the two fields above

  function remoteMatchPayload() {
    return { matchId: remoteMatchSubscription ? remoteMatchSubscription.matchId : null, data: remoteMatch, error: remoteMatchError };
  }
  // Sprint 3.7.1, Task 5 (cleanup): one shared safe-invoke helper for
  // both the "notify everyone" path and onRemoteMatchUpdate()'s own
  // "notify the new subscriber immediately" call below — Sprint 3.7
  // had the same try/catch duplicated in both places.
  function safeInvokeRemoteMatchListener(cb, payload) {
    try { cb(payload); } catch (e) { console.error("[GameSession] an onRemoteMatchUpdate callback threw:", e); }
  }
  function notifyRemoteMatchListeners() {
    var payload = remoteMatchPayload();
    remoteMatchListeners.forEach(function (cb) { safeInvokeRemoteMatchListener(cb, payload); });
  }

  /** Begin consuming MatchService's live sync for one matchId. Idempotent
   *  for the SAME matchId — a repeat call is a no-op, which is what
   *  keeps "no duplicated listeners" true at this layer too (GameSession
   *  only ever holds ONE subscription handle, so it can only ever call
   *  one real unsubscribe; without this guard, a second call for the
   *  same matchId would leak the first handle forever). Switching to a
   *  DIFFERENT matchId cleanly tears down the old one first. Fail-open
   *  if MatchService isn't loaded on this page (e.g. an offline-only
   *  screen) — warns, never throws. */
  function subscribeToRemoteMatch(matchId) {
    if (!matchId) return;
    if (remoteMatchSubscription && remoteMatchSubscription.matchId === matchId) return;
    unsubscribeFromRemoteMatch();
    if (!global.MatchService || typeof global.MatchService.subscribeToMatch !== "function") {
      console.warn("[GameSession] subscribeToRemoteMatch: MatchService is not available — remote sync will not start.");
      return;
    }
    var unsubscribe = global.MatchService.subscribeToMatch(matchId, function (data, err) {
      remoteMatchError = err || null;
      if (!err) remoteMatch = data; // fail-open: an error never clears the last known good state
      notifyRemoteMatchListeners();
    });
    remoteMatchSubscription = { matchId: matchId, unsubscribe: unsubscribe };
  }

  /** Clean, explicit teardown — the only path that ever calls the
   *  stored unsubscribe function, and it is only ever stored once at a
   *  time, so there is nothing left to leak. Safe to call when nothing
   *  is subscribed (no-op). */
  function unsubscribeFromRemoteMatch() {
    if (remoteMatchSubscription && typeof remoteMatchSubscription.unsubscribe === "function") {
      remoteMatchSubscription.unsubscribe();
    }
    remoteMatchSubscription = null;
  }

  function getRemoteMatch() { return remoteMatch; }
  function getRemoteMatchError() { return remoteMatchError; }
  function isSubscribedToRemoteMatch() { return !!remoteMatchSubscription; }

  /** Subscribe to GameSession's OWN local remote-match updates — mirrors
   *  SessionService.subscribe()'s exact shape (fires immediately with
   *  the current value, then again on every change; returns an
   *  unsubscribe). A screen/engine file should use this instead of ever
   *  reaching into MatchService itself — this is the concrete form of
   *  "GameSession must consume MatchService updates." */
  function onRemoteMatchUpdate(callback) {
    if (typeof callback !== "function") return function unsubscribe() {};
    remoteMatchListeners.push(callback);
    safeInvokeRemoteMatchListener(callback, remoteMatchPayload());
    return function unsubscribe() {
      var idx = remoteMatchListeners.indexOf(callback);
      if (idx !== -1) remoteMatchListeners.splice(idx, 1);
    };
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
    // Player Hand Synchronization sprint.
    setHandAuthorityMode: setHandAuthorityMode, getHandAuthorityMode: getHandAuthorityMode, setAuthoritativeHand: setAuthoritativeHand,
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
    isMatchComplete: isMatchComplete,
    getWinnerIds: getWinnerIds, setWinnerIds: setWinnerIds,
    subscribeToRemoteMatch: subscribeToRemoteMatch, unsubscribeFromRemoteMatch: unsubscribeFromRemoteMatch,
    getRemoteMatch: getRemoteMatch, getRemoteMatchError: getRemoteMatchError,
    isSubscribedToRemoteMatch: isSubscribedToRemoteMatch, onRemoteMatchUpdate: onRemoteMatchUpdate
  };
})(window);
