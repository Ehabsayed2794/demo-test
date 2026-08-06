/* ════════════════════════════════════════════════════════════════════
   Estimation — Game Table (Trick-Taking) Engine
   Browser-side mirror of GameReducer.kt's TrickTaking phase.
   Pattern: UI emits INTENTS → reduce(state, intent) → new STATE → render().
   Scope: a single round of trick play (13 tricks). Bidding outcome
   (caller / with / risk / estimates / trump) is seeded as the entry state.
   ════════════════════════════════════════════════════════════════════ */

// ── Suit model (strength SANS5 > ♠4 > ♥3 > ♦2 > ♣1) ──
const SUITS = {
  SANS:     { id: "SANS",     sym: "SN", strength: 5, red: false, sans: true,  name: "Sans" },
  SPADES:   { id: "SPADES",   sym: "♠",  strength: 4, red: false, sans: false, name: "Spades" },
  HEARTS:   { id: "HEARTS",   sym: "♥",  strength: 3, red: true,  sans: false, name: "Hearts" },
  DIAMONDS: { id: "DIAMONDS", sym: "♦",  strength: 2, red: true,  sans: false, name: "Diamonds" },
  CLUBS:    { id: "CLUBS",    sym: "♣",  strength: 1, red: false, sans: false, name: "Clubs" },
};
const RANKS = [
  { v: 14, s: "A" }, { v: 13, s: "K" }, { v: 12, s: "Q" }, { v: 11, s: "J" },
  { v: 10, s: "10" }, { v: 9, s: "9" }, { v: 8, s: "8" }, { v: 7, s: "7" },
  { v: 6, s: "6" }, { v: 5, s: "5" }, { v: 4, s: "4" }, { v: 3, s: "3" }, { v: 2, s: "2" },
];

// p1 = You (bottom). Counter-clockwise: bottom → right → top → left.
// (This was previously mislabeled backward — bottom→left→top→right is
// actually clockwise; verified against SEAT_POS below and independently
// against the reference implementation's mirrored left/right convention.
// The turn-order code itself was always correct — only this comment was wrong.)
// Player roster now owned by GameSession — no local game variables.
const SEAT_POS = { p1: "bottom", p2: "right", p3: "top", p4: "left" };
const PLAYERS = GameSession.getPlayers().map(p => ({ ...p, letter: p.initial, pos: SEAT_POS[p.id] }));
const TURN_ORDER = PLAYERS.map(p => p.id);
function nextCCW(id) { const i = TURN_ORDER.indexOf(id); return TURN_ORDER[(i + 1) % 4]; }
function nameOf(id) { return PLAYERS.find(p => p.id === id).name; }
function posOf(id)  { return PLAYERS.find(p => p.id === id).pos; }

// round configuration — seeded from GameSession's bidding outcome when
// available (set by Bidding Phase's "Begin Trick-Taking" button); falls
// back to a plausible mock round if Game Table is opened directly.
function buildRoundCfg() {
  const r = GameSession.getRound();
  // A committed round is signaled by biddingState.completed, not by a
  // truthy callerId — an all-Dash-Call outcome is a legitimate completed
  // round with callerId === null (SANS trump, no caller), and checking
  // callerId here used to make Game Table discard that real result and
  // substitute a fabricated mock round instead.
  const bs = GameSession.getBiddingState();
  const hasBidResult = !!(bs && bs.completed);
  // No caller to lead (all-Dash) — fall back to whoever completeBidding
  // stamped as the opening leader (the dealer), so trick 1 has a turn.
  const leaderId = hasBidResult ? (r.callerId || GameSession.getTurn() || GameSession.getDealer()) : "p4";
  return {
    round: r.number, totalRounds: r.maxRounds,
    trump: hasBidResult ? r.trump : "SPADES",
    multiplier: r.multiplier || 1,
    callerId: hasBidResult ? r.callerId : "p4",
    withPlayers: hasBidResult ? r.withPlayers : ["p2"],
    estimates: hasBidResult ? r.estimates : { p1: 3, p2: 4, p3: 4, p4: 4 },
    dashCallers: hasBidResult ? (r.dashCallers || []) : [],
    leaderId,
  };
}
const ROUND_CFG = buildRoundCfg();

let state = null;

// Risk player = whoever actually submitted the round's final estimate —
// read the value bidding-engine.js already computed and persisted (it
// tracks the real last bidder, not just a caller-relative guess, which
// matters for fast rounds that may have no caller at all). Falls back to
// the old "3 seats CCW from caller" formula only if that's unavailable
// (e.g. Game Table opened directly with no real bidding result).
function computeRiskId() {
  const stored = GameSession.getBiddingState().riskPlayerId;
  if (stored) return stored;
  if (!ROUND_CFG.callerId) return null;
  let id = ROUND_CFG.callerId, prev = id;
  for (let k = 0; k < 3; k++) prev = nextCCW(prev);   // 3 steps CCW lands on the seat before caller
  return prev;
}

function initState() {
  // Card Engine: cards were already dealt once for this round when
  // Bidding Phase started (stored in GameSession). Reuse the same
  // hands here so the trick-play round matches what was actually bid
  // on; ensureHandsDealt() only deals fresh if this round has no valid
  // deal yet (e.g. Game Table opened directly).
  const hands = GameSession.ensureHandsDealt();
  const base = { ...structuredClone(ROUND_CFG), riskId: computeRiskId(), hands, busy: false, logs: [] };

  if (GameSession.isPlayStateValidForCurrentRound()) {
    // Round Play State Persistence: resume exactly where the player left
    // off instead of restarting trick progress on refresh/re-entry.
    const ps = GameSession.getPlayState();
    state = {
      ...base,
      voids: ps.voids,
      tricksWon: ps.tricksWon,
      trickNo: ps.trickNumber,
      leaderId: ps.leaderId,
      turn: ps.turnId,
      plays: ps.currentPlays,
      ledSuit: ps.ledSuit,
      lastTrick: ps.lastTrick,
      phase: ps.phase,
    };
    pushLog("phase", `ROUND ${state.round} · RESUMED · TRICK ${state.trickNo}/13`);
  } else {
    state = {
      ...base,
      voids: { p1: [], p2: [], p3: [], p4: [] },   // suits a player is publicly known to lack
      tricksWon: { p1: 0, p2: 0, p3: 0, p4: 0 },
      trickNo: 1,
      leaderId: ROUND_CFG.leaderId,                // Caller leads the first trick (dealer, if no caller)
      turn: ROUND_CFG.leaderId,
      plays: [],                                   // [{playerId, card}] for the current trick
      ledSuit: null,
      lastTrick: null,                             // {plays, winnerId}
      phase: "PLAY",                               // PLAY | RESOLVING | DONE
    };
    GameSession.initializePlayState({
      leaderId: state.leaderId, turnId: state.turn,
      tricksWon: state.tricksWon, voids: state.voids
    });
    pushLog("phase", `ROUND ${state.round} · TRICK-TAKING · TRUMP ${SUITS[state.trump].name.toUpperCase()}`);
    pushLog("", state.callerId
      ? `${nameOf(state.callerId)} called ${state.estimates[state.callerId]} ${SUITS[state.trump].name} and leads the first trick.`
      : `All four players Dash-Called — ${nameOf(state.leaderId)} (dealer) leads the first trick, SANS trump.`);
  }
}

function pushLog(kind, text) { state.logs.push({ kind, text }); }

// ── card legality (follow suit) ──
function legalCards(id) {
  const hand = state.hands[id];
  if (!state.ledSuit) return hand.slice();             // leader may play anything
  const inSuit = hand.filter(c => c.suit === state.ledSuit);
  return inSuit.length ? inSuit : hand.slice();        // must follow if able, else anything
}
function isLegal(id, card) {
  return legalCards(id).some(c => c.suit === card.suit && c.rank.v === card.rank.v);
}

// ── trick evaluation ──
function cardValue(card) {
  const isTrump = !SUITS[state.trump].sans && card.suit === state.trump;
  const follows = card.suit === state.ledSuit;
  return card.rank.v + (isTrump ? 1000 : (follows ? 100 : 0));
}
function trickWinner(plays) {
  let best = plays[0];
  for (const p of plays) if (cardValue(p.card) > cardValue(best.card)) best = p;
  return best.playerId;
}
function currentWinnerId() {
  return state.plays.length ? trickWinner(state.plays) : null;
}

// ════════════════════════════════════════════════════════════════════
//  REDUCER
// ════════════════════════════════════════════════════════════════════
function emit(intent) {
  if (state.phase !== "PLAY") return { rejected: true };
  if (intent.type !== "PlayCard") return { rejected: true };
  const { playerId, card } = intent;
  if (state.turn !== playerId) return { rejected: true };
  if (!isLegal(playerId, card)) {
    pushLog("reject", `${nameOf(playerId)} can't play ${card.rank.s}${SUITS[card.suit].sym} — must follow ${SUITS[state.ledSuit].name}.`);
    return { rejected: true, reason: `Follow ${SUITS[state.ledSuit].name}` };
  }

  // reveal a void: player had the chance to follow but couldn't (played off-suit)
  if (state.ledSuit && card.suit !== state.ledSuit && !state.voids[playerId].includes(state.ledSuit)) {
    state.voids[playerId].push(state.ledSuit);
    pushLog("", `${nameOf(playerId)} shows void in ${SUITS[state.ledSuit].name}.`);
  }

  // remove from hand, record play
  state.hands[playerId] = state.hands[playerId].filter(c => !(c.suit === card.suit && c.rank.v === card.rank.v));
  card.played = true;
  if (state.plays.length === 0) state.ledSuit = card.suit;
  state.plays.push({ playerId, card });
  pushLog("intent", `${nameOf(playerId)} plays ${card.rank.s} ${SUITS[card.suit].sym}.`);

  if (state.plays.length < 4) {
    state.turn = nextCCW(playerId);
  } else {
    // trick complete → resolve
    state.phase = "RESOLVING";
    state.turn = null;
  }

  GameSession.recordCardPlay({
    playerId, hand: state.hands[playerId], currentPlays: state.plays,
    ledSuit: state.ledSuit, nextTurnId: state.turn, phase: state.phase, voids: state.voids
  });
  return { rejected: false };
}

// called by the turn loop after the 4th card, once the winning highlight has shown
function resolveTrick() {
  const winner = trickWinner(state.plays);
  state.tricksWon[winner] += 1;
  state.lastTrick = { plays: state.plays.slice(), winnerId: winner, ledSuit: state.ledSuit };
  pushLog("intent", `${nameOf(winner)} takes trick ${state.trickNo}.`);

  if (state.trickNo >= 13) {
    state.phase = "DONE";
    state.plays = [];
    pushLog("phase", "ROUND COMPLETE");

    // Real Scoring Engine: bids reconstructed from the committed estimates
    // map. bidding-engine.js's `round.dashCallers` carries the one bit of
    // type info `estimates` alone can't (a pre-bidding Dash Call never
    // re-enters estimation, so extractEstimates() — TRICKS-only — would
    // otherwise drop it entirely). Anyone not in that list is classified
    // the pre-existing way: a 0 estimate is a Normal Dash (DASH), anything
    // else is TRICKS.
    const dashCallers = state.dashCallers || [];
    const bids = {};
    TURN_ORDER.forEach(id => {
      bids[id] = dashCallers.includes(id)
        ? { type: "DASHCALL", amount: 0 }
        : { type: state.estimates[id] === 0 ? "DASH" : "TRICKS", amount: state.estimates[id] };
    });
    const result = ScoringEngine.calculateRoundScore({
      round: state.round, turnOrder: TURN_ORDER, bids, tricksWon: state.tricksWon,
      callerId: state.callerId, withPlayers: state.withPlayers, multiplier: state.multiplier,
      riskPlayerId: state.riskId,
      scoringMode: GameSession.getScoringMode(),
      escalationCap: GameSession.getScoringMode() === "classic" ? 2 : 8
    });
    state._saaydaNext = result.isSaayda ? result.nextMultiplier : null;
    if (result.isSaayda) pushLog("phase", `SA'AYDA — ALL FOUR FAILED · ROUND ZEROED · NEXT ROUND ×${result.nextMultiplier}`);
    state._scoreResult = result;
    ScoringEngine.applyRoundResult(result, {
      trump: state.trump, callerId: state.callerId, tricksWon: state.tricksWon, estimates: state.estimates
    });
    GameSession.completeRound({ tricksWon: state.tricksWon, multiplier: result.nextMultiplier });
    return;
  }
  state.trickNo += 1;
  state.leaderId = winner;
  state.turn = winner;
  state.plays = [];
  state.ledSuit = null;
  state.phase = "PLAY";
  GameSession.recordResolvedTrick({
    tricksWon: state.tricksWon, lastTrick: state.lastTrick,
    nextTrickNumber: state.trickNo, nextLeaderId: state.leaderId, nextTurnId: state.turn, phase: state.phase
  });
}

// ════════════════════════════════════════════════════════════════════
//  AI — plays toward its own estimate (grab while short, dump when met)
// ════════════════════════════════════════════════════════════════════
function aiPlay(id) {
  const legal = legalCards(id);
  const needs = state.tricksWon[id] < state.estimates[id];
  const byLow = [...legal].sort((a, b) => cardValue(a) - cardValue(b));

  // leading
  if (state.plays.length === 0) {
    const card = needs ? byLow[byLow.length - 1] : byLow[0];   // lead high to grab, low to shed
    return emit({ type: "PlayCard", playerId: id, card });
  }
  // following
  const bestVal = cardValue(state.plays.reduce((b, p) => cardValue(p.card) > cardValue(b.card) ? p : b).card);
  const winners = byLow.filter(c => cardValue(c) > bestVal);
  let card;
  if (needs && winners.length) card = winners[0];   // win as cheaply as possible
  else card = byLow[0];                              // dump the lowest
  return emit({ type: "PlayCard", playerId: id, card });
}

// ════════════════════════════════════════════════════════════════════
//  TURN LOOP
// ════════════════════════════════════════════════════════════════════
function advance() {
  render();
  if (state.phase === "DONE") {
    if (state._saaydaNext) setTimeout(() => showEscalationBanner("⚔️", `Sa'ayda! Round Zeroed`, `All four missed \u00b7 next round \u00d7${state._saaydaNext}`), 300);
    setTimeout(showRoundDone, state._saaydaNext ? 1800 : 650);
    return;
  }

  if (state.phase === "RESOLVING") {
    // show the completed trick + winning card briefly, then collect & continue
    state.busy = true;
    setTimeout(() => {
      sweepThenResolve();
    }, 1050);
    return;
  }

  const p = PLAYERS.find(x => x.id === state.turn);
  if (p && !p.isUser) {
    state.busy = true; render();
    const delay = 650 + Math.random() * 600;
    setTimeout(() => { state.busy = false; aiPlay(state.turn); advance(); }, delay);
  } else {
    state.busy = false; render();   // wait for the user to click a card
  }
}

// user action from the hand
function playFromHand(card) {
  if (state.phase !== "PLAY") return;
  const p = PLAYERS.find(x => x.id === state.turn);
  if (!p || !p.isUser) return;
  const res = emit({ type: "PlayCard", playerId: "p1", card });
  if (res.rejected) { flashReject(res.reason); return; }
  advance();
}

function restart() {
  GameSession.clearPlayState();
  GameSession.ensureHandsDealt({ force: true });
  initState();
  document.querySelector(".round-done")?.classList.remove("show");
  advance();
}

window.addEventListener("DOMContentLoaded", () => {
  GameState.sync(GameState.STATES.GAMEPLAY);
  initState();
  bindStatic();
  advance();
});

// Sprint 3.6 (Match Flow Integration): same minimum-export treatment as
// bidding-engine.js's matching addition — see that file's comment and
// docs/reviews/MatchFlowIntegration_3.6.md for the full rationale.
// Nothing above this line was touched. `resolveTrick` is exported too:
// integration tests bypass `advance()`'s setTimeout-based auto-resolve
// (real per-turn delays would make an automated test impractically
// slow) and instead call `emit()` for all four plays of a trick, then
// call `resolveTrick()` directly once `state.phase === "RESOLVING"` —
// the exact same function the real turn loop already calls internally,
// just invoked explicitly instead of via a timer.
// Sprint 4.2.1 (Pre-Write Card Authority & Desync Safety): ONE more
// minimum-export addition, same treatment as Sprint 3.6's own
// `resolveTrick`/`getState` above — nothing above this line was
// touched, and `canPlayCard` itself introduces NO new rule: it is a
// PURE, non-mutating composition of the exact same three conditions
// `emit()` already checks before it mutates anything (`state.phase`,
// `state.turn`, and `isLegal()` — all pre-existing, unchanged). This
// is what closes Sprint 4.2.1's Task 2 gap (a caller needing to know
// "would this play be accepted" WITHOUT calling `emit()` and without
// duplicating `isLegal()`'s own follow-suit/hand-ownership logic) — see
// design-ui/match-service.js's submitCard() for the one caller that
// uses it. Never mutates `state`; safe to call any number of times
// with zero side effects; never calls `emit()` itself.
function canPlayCard(playerId, card) {
  if (!state) return { legal: false, reason: "NOT_INITIALIZED" };
  if (state.phase !== "PLAY") return { legal: false, reason: "NOT_PLAY_PHASE" };
  if (state.turn !== playerId) return { legal: false, reason: "NOT_THIS_SEATS_TURN" };
  if (!card || typeof card !== "object" || !card.suit || !card.rank || !isLegal(playerId, card)) {
    return { legal: false, reason: "ILLEGAL_CARD" };
  }
  return { legal: true };
}

// Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync
// Hardening), Task 1: ONE more minimum-export addition, same
// additive-only treatment as `canPlayCard` above and Sprint 3.6's
// original `resolveTrick`/`getState` — nothing above this line was
// touched. `previewPlay` introduces NO new rule and NO new state
// transition of its own: it reuses `canPlayCard`'s own legality
// answer verbatim, then reads the EXACT same "how many plays would
// this trick have, and who's next" arithmetic `emit()` already
// performs (`state.plays.length` vs. `4`, `nextCCW()`) — just without
// ever calling `emit()`, mutating `state`, removing a card from a
// hand, or touching `GameSession`. This is what makes it safe to call
// from `design-ui/match-service.js`'s `submitCard()` BEFORE a
// Firestore write even starts (Sprint 4.2.2's Task 2) — the answer it
// gives is exactly what `emit()` would decide, read in advance, never
// duplicated or reimplemented independently. Winner/trick-resolution
// logic (what happens once `nextPhase` is `"RESOLVING"`) is
// deliberately NOT computed here — this function answers "is this
// legal, and what turn/phase does accepting it lead to," nothing about
// who wins the completed trick, per this sprint's own stop list. */
function previewPlay(playerId, card) {
  var validation = canPlayCard(playerId, card);
  if (!validation.legal) return { legal: false, reason: validation.reason };
  var nextPlayCount = state.plays.length + 1; // the exact count AFTER this (not-yet-applied) play, mirroring emit()'s own check
  if (nextPlayCount < 4) {
    return { legal: true, nextTurnSeat: nextCCW(playerId), nextPhase: "PLAY" };
  }
  return { legal: true, nextTurnSeat: null, nextPhase: "RESOLVING" };
}

window.TableEngine = {
  initState: initState,
  emit: emit,
  resolveTrick: resolveTrick,
  getState: function () { return state; },
  canPlayCard: canPlayCard,
  previewPlay: previewPlay
};
