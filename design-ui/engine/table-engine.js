/* ════════════════════════════════════════════════════════════════════
   Estimation — Game Table (Trick-Taking) Engine
   Browser-side mirror of GameReducer.kt's TrickTaking phase.
   Pattern: UI emits INTENTS → reduce(state, intent) → new STATE → render().
   Scope: a single round of trick play (13 tricks). Bidding outcome
   (caller / with / risk / estimates / trump) is seeded as the entry state.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {

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
// Foundation Fix (Table Controls sprint, authorized): was `const
// ROUND_CFG = buildRoundCfg();` — computed exactly ONCE, at module
// load time (i.e. when this <script> tag executes, which happens at
// page load, BEFORE any real bidding interaction has occurred on a
// page that loads bidding-engine.js and table-engine.js together).
// initState() reused this same frozen snapshot forever, so it could
// never reflect the REAL bidding outcome (trump/callerId/withPlayers/
// estimates/leaderId/round) once bidding genuinely completed later in
// the same page session — confirmed by direct reproduction (see
// docs/reviews/TableEngine_Foundation_Fix_Report.md). Changed to `let`
// and reassigned at the top of initState() (below) so every call
// re-derives it fresh from GameSession's CURRENT state — buildRoundCfg()
// itself is completely unchanged, not one formula/rule touched; this
// is purely a "read the existing data at the correct lifecycle moment"
// fix, exactly like the existing `hands = GameSession.ensureHandsDealt()`
// line a few lines below already does correctly on every initState() call.
let ROUND_CFG = buildRoundCfg();

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
  // Foundation Fix: re-derive ROUND_CFG fresh from GameSession's
  // CURRENT round/bidding state on every call — never reuse a snapshot
  // from a prior call or from module load. This is the one line that
  // actually fixes the staleness bug; buildRoundCfg()'s own logic is
  // completely unchanged.
  ROUND_CFG = buildRoundCfg();

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
// Sprint H (Remote Hand State fix): `state.hands[id]` is no longer
// guaranteed to be populated for every seat under real multiplayer
// hand-authority — a real multiplayer client only ever holds ITS OWN
// authoritative hand (session.js's setHandAuthorityMode()/
// setAuthoritativeHand()); it never fabricates or receives another
// seat's private cards. `|| []` here is a defensive fallback, not a
// behavior change for the local seat (whose hand is always populated
// by the time this is consulted for a real play attempt) — it just
// stops an absent/not-yet-known remote hand from throwing here.
function legalCards(id) {
  const hand = state.hands[id] || [];
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
// Sprint H (Remote Hand State / Table Engine Initialization Fix):
// `intent.trusted` — set ONLY by match-adapter.js's applyRemoteCard()
// when replaying an entry from Firestore's authoritative `cardLog`.
// Rationale this closes: `firestore.rules` never validates follow-suit
// legality for `cardLog` (only structural shape + turn order — see
// isValidCardSubmission()); the ONLY place that ever validates
// follow-suit legality for a real play is the ACTING seat's own client,
// against ITS OWN real hand, before the write even happens
// (match-service.js's submitCard() -> TableEngine.canPlayCard()). Once
// an entry is accepted into `cardLog`, every OTHER client applying it
// has no legitimate way to re-derive that same legality check locally —
// doing so would require that seat's private hand, which this client
// correctly never has (Firestore hand-privacy rules, unchanged by this
// fix). Re-deriving it anyway against `state.hands[playerId]` is not
// just redundant, it's actively wrong whenever that array isn't the
// real hand — which, prior to this sprint's root-cause fix in
// session.js's setHandAuthorityMode(), could be a stale, fabricated
// local deal. `trusted` skips ONLY the isLegal() gate; every other
// check (phase, whose turn, and the exact same void-tracking/hand-
// mutation/turn-advance/logging the LOCAL path already ran through) is
// untouched — a trusted apply is exactly as strict about phase/turn
// as an ordinary one, just not about re-deriving a legality decision
// that was already correctly made once, by the only client able to
// make it.
function emit(intent) {
  if (state.phase !== "PLAY") return { rejected: true };
  if (intent.type !== "PlayCard") return { rejected: true };
  const { playerId, card, trusted } = intent;
  if (state.turn !== playerId) return { rejected: true };
  if (!trusted && !isLegal(playerId, card)) {
    // Sprint H, Phase 3 (Defensive Emit Fix — independent of the
    // trusted-apply change above): `state.ledSuit` is legitimately
    // `null` for a trick's first card, so `SUITS[state.ledSuit]` must
    // never be dereferenced unguarded here. This is a defensive fix
    // only — it does not, by itself, change WHETHER a play is
    // considered legal, only how a rejection is reported once one
    // occurs.
    const ledSuitName = state.ledSuit ? SUITS[state.ledSuit].name : null;
    pushLog("reject", ledSuitName
      ? `${nameOf(playerId)} can't play ${card.rank.s}${SUITS[card.suit].sym} — must follow ${ledSuitName}.`
      : `${nameOf(playerId)} can't play ${card.rank.s}${SUITS[card.suit].sym}.`);
    return { rejected: true, reason: ledSuitName ? `Follow ${ledSuitName}` : "Illegal play" };
  }

  // reveal a void: player had the chance to follow but couldn't (played off-suit)
  if (state.ledSuit && card.suit !== state.ledSuit && !state.voids[playerId].includes(state.ledSuit)) {
    state.voids[playerId].push(state.ledSuit);
    pushLog("", `${nameOf(playerId)} shows void in ${SUITS[state.ledSuit].name}.`);
  }

  // remove from hand, record play. `|| []` + a match-or-fallback removal:
  // a real multiplayer client's own hand always contains the exact card
  // (ordinary path); a REMOTE seat's hand may legitimately be empty/
  // unknown here (this client never holds another seat's private
  // cards) — in that case there is nothing meaningful to "remove by
  // identity," so this is a no-op on an already-empty array rather than
  // a crash, and per-seat hand size for remote seats is simply not
  // tracked client-side (nothing in this codebase renders it — see
  // Sprint H's own investigation).
  const priorHand = state.hands[playerId] || [];
  state.hands[playerId] = priorHand.filter(c => !(c.suit === card.suit && c.rank.v === card.rank.v));
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
// duplicated or reimplemented independently.
//
// Sprint I.2 (Turn Authority / Trick-Boundary Fix): when this play
// WOULD complete the trick (the 4th play), `nextTurnSeat` is now the
// REAL trick winner — computed by reusing `trickWinner()`/`cardValue()`
// verbatim against the hypothetical post-play `plays` array
// (`state.plays` concatenated with this pending, not-yet-applied play)
// — never a second, independent winner algorithm. This is PURE: it
// builds a local array and calls the exact same comparison function
// `resolveTrick()` itself calls, without ever mutating `state.plays`,
// `state.turn`, `state.phase`, or removing anything from a hand, and
// without advancing the trick. Root cause this closes (see Sprint I's
// own forensic report): the OLD `nextTurnSeat: null` answer here is
// exactly what `MatchService.submitCard()` wrote into
// `matches/{matchId}.turn`, and nothing in this codebase's write path
// ever wrote a real uid back into `turn` afterward — a permanent
// dead end against `firestore.rules`' `oldData.turn ==
// request.auth.uid` check, since `null` can never equal any real uid.
// Returning the real winner here instead means `submitCard()` now
// writes the ACTUAL next leader, and the EXISTING, UNMODIFIED
// Firestore rule allows that leader's own next submission naturally —
// no rules change, no new persisted field, no new trust boundary: this
// value is exactly as client-computed/client-trusted as the ordinary
// `nextCCW(playerId)` answer already was for every non-trick-completing
// play, per this project's own long-documented "gameplay turn order
// remains client-authoritative in this Spark MVP" limitation.
function previewPlay(playerId, card) {
  var validation = canPlayCard(playerId, card);
  if (!validation.legal) return { legal: false, reason: validation.reason };
  var nextPlayCount = state.plays.length + 1; // the exact count AFTER this (not-yet-applied) play, mirroring emit()'s own check
  if (nextPlayCount < 4) {
    return { legal: true, nextTurnSeat: nextCCW(playerId), nextPhase: "PLAY" };
  }
  // Trick-completing play: compute the real winner from a HYPOTHETICAL
  // plays array (state.plays is never mutated here) — the identical
  // computation resolveTrick() performs once this play has actually
  // been applied via emit().
  var hypotheticalPlays = state.plays.concat([{ playerId: playerId, card: card }]);
  var winnerId = trickWinner(hypotheticalPlays);
  return { legal: true, nextTurnSeat: winnerId, nextPhase: "RESOLVING" };
}

window.TableEngine = {
  initState: initState,
  emit: emit,
  resolveTrick: resolveTrick,
  getState: function () { return state; },
  canPlayCard: canPlayCard,
  previewPlay: previewPlay
};

})(window);
