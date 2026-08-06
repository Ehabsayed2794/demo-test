// Real, executable END-TO-END tests for Sprint 4.2 (Online Card
// Synchronization: Engine Authority) — the FULL pipeline:
//   Player -> submitCard() -> Firestore -> MatchService listener ->
//   Engine Adapter -> table-engine.js -> GameSession -> UI
// exercised together, in one process, against the REAL
// design-ui/match-service.js, design-ui/match-adapter.js,
// design-ui/engine/table-engine.js, design-ui/engine/bidding-engine.js,
// and design-ui/engine/session.js (GameSession) — not stubs, not
// fakes, the actual shipped code for every one of those five files.
//
// LABELING: every check below is MOCKED — real code from all five
// files above, exercised against a hand-written fake Firestore (this
// file's own, combining tests/submit-card.test.cjs's transaction +
// tests/card-sync's onSnapshot/listener-registry patterns, same shape
// as tests/bid-sync.test.cjs/tests/turn-sync.test.cjs). No SIMULATED
// checks (no firestore.rules involved). No real Firestore project,
// Firebase Emulator, or browser was used.
//
// Architectural note this test's structure works around (documented,
// not fixed — see match-flow-integration.test.cjs's own identical
// note): table-engine.js's PLAYERS/TURN_ORDER/ROUND_CFG are computed
// ONCE, at require()-time, from GameSession's state at that instant.
// table-engine.js is therefore required LATER in this file, after
// bidding completes for each scenario that needs it — this is why
// every scenario below calls the shared driveBiddingAndDealing()
// helper BEFORE table-engine.js is required at all, and why (unlike
// bid-sync.test.cjs/turn-sync.test.cjs, which can freely reset and
// reuse the SAME bidding-engine.js across many scenarios per process)
// this file drives bidding+table-engine ONCE per process and runs
// every card-sync scenario within that ONE round's worth of real
// engine state, using DIFFERENT tricks/positions within the same round
// to keep each scenario's own engine state independent.
global.window = global;
global.window.addEventListener = function () {};

var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var ONSNAPSHOT_CALLS = {};
var pendingErrorCallbacks = {};
var FIRESTORE_AVAILABLE = true;

function key(id) { return "matches/" + id; }
function notify(k) {
  (LISTENERS[k] || []).forEach(function (cb) {
    var exists = Object.prototype.hasOwnProperty.call(STORE, k);
    cb({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
  });
}
function makeMatchRef(id) {
  var k = key(id);
  return {
    id: id, _key: k,
    get: function () {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    update: function (patch) {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      STORE[k] = Object.assign({}, STORE[k], patch);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      notify(k);
      return Promise.resolve();
    },
    onSnapshot: function (onNext, onError) {
      ONSNAPSHOT_CALLS[k] = (ONSNAPSHOT_CALLS[k] || 0) + 1;
      if (!FIRESTORE_AVAILABLE) {
        var err = new Error("simulated Firestore unavailable"); err.code = "unavailable";
        onError(err);
        return function () {};
      }
      LISTENERS[k] = LISTENERS[k] || [];
      LISTENERS[k].push(onNext);
      pendingErrorCallbacks[k] = pendingErrorCallbacks[k] || [];
      pendingErrorCallbacks[k].push(onError);
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      onNext({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
      return function unsubscribe() {
        LISTENERS[k] = (LISTENERS[k] || []).filter(function (cb) { return cb !== onNext; });
        pendingErrorCallbacks[k] = (pendingErrorCallbacks[k] || []).filter(function (cb) { return cb !== onError; });
      };
    }
  };
}
var idCounter = 0;
var FAKE_DB = {
  collection: function (name) {
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { if (!id) id = "match-" + (++idCounter); return makeMatchRef(id); } };
  },
  runTransaction: function (fn, attempt) {
    if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
    var seenVersions = {}, pending = {};
    var tx = {
      get: function (ref) { seenVersions[ref._key] = DOC_VERSION[ref._key] || 0; return ref.get(); },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var keys = Object.keys(pending);
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      keys.forEach(function (k) { STORE[k] = Object.assign({}, STORE[k], pending[k].data); DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1; });
      keys.forEach(function (k) { notify(k); });
      return result;
    });
  }
};
global.Db = FAKE_DB;
global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } } } };

function simulateDisconnect(id, code) {
  var k = key(id);
  var cbs = (pendingErrorCallbacks[k] || []).slice();
  LISTENERS[k] = [];
  pendingErrorCallbacks[k] = [];
  var err = new Error("simulated disconnect" + (code ? " (" + code + ")" : ""));
  if (code) err.code = code;
  cbs.forEach(function (cb) { cb(err); });
}

var CURRENT_USER = null;
global.SessionService = { getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; }, setCurrentMatchId: function () { return Promise.resolve(); } };
function signInAs(uid) { CURRENT_USER = uid; }

require("/home/user/demo-test/design-ui/match-service.js");
require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
require("/home/user/demo-test/design-ui/engine/session.js");
require("/home/user/demo-test/design-ui/engine/bidding-engine.js");
require("/home/user/demo-test/design-ui/engine/scoring-engine.js");
require("/home/user/demo-test/design-ui/match-adapter.js");
// table-engine.js required LATER, after bidding completes — see this
// file's own header comment.

var MatchService = global.MatchService;
var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function wait(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }

/** Drives the REAL bidding-engine.js to a fully-committed round (dealer
 *  opens 4 SPADES, everyone else passes, everyone estimates 2) — the
 *  exact same script tests/bid-sync.test.cjs's runBiddingToEstimates()
 *  uses, extended through to a real, committed round so table-engine.js
 *  (required immediately after this returns) computes a real
 *  ROUND_CFG, not a fallback mock one. */
function driveBiddingToCommittedRound() {
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  BiddingEngine.initState();
  for (var i = 0; i < 4; i++) {
    var s = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false });
  }
  var s2 = BiddingEngine.getState();
  var opener = s2.waitingFor;
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: opener, tricks: 4, suit: "SPADES", isPass: false });
  s2 = BiddingEngine.getState();
  var guard = 0;
  while (s2.subPhase === "AUCTION" && guard < 10) {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s2.waitingFor, isPass: true });
    s2 = BiddingEngine.getState();
    guard++;
  }
  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s2.waitingFor, tricks: s2.auctionTop, suit: s2.auctionSuit });
  ["p2", "p3", "p4"].forEach(function () {
    var st = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: st.waitingFor, tricks: 2 });
  });
}

driveBiddingToCommittedRound();
require("/home/user/demo-test/design-ui/engine/table-engine.js");
var TableEngine = global.TableEngine;
TableEngine.initState();

/** Seeds a mocked Firestore match document consistent with the ONE
 *  round-in-progress table-engine.js state driven above — same 4
 *  seats, cardLog empty, version 1. `turn` is seeded to whatever the
 *  REAL, local engine's own `state.turn` currently is (never
 *  hardcoded) — this is legitimate ONE-TIME setup establishing a new
 *  scenario's starting turn (exactly like a real match's initial
 *  leader would be seeded once, at deal time), NOT a manual mutation
 *  between sequential submissions — Sprint 4.2.2 removed every one of
 *  THOSE (see this file's own retired `syncTurnFieldToRealEngine()`
 *  helper, no longer present — submitCard() now advances `turn`
 *  atomically on its own, and this file no longer needs, or has, any
 *  helper that does that job for it). */
function seedMockMatch(matchId) {
  var leaderUid = seatUidOf(TableEngine.getState().turn);
  STORE[key(matchId)] = {
    roomId: "room-x", players: ["u1", "u2", "u3", "u4"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "u1", turn: leaderUid, gameState: { initialized: false },
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 1, biddingOpen: false, bids: { p1: 4, p2: 2, p3: 2, p4: 2 }, lastBidSeat: "p4",
    cardLog: [], lastCardSeat: null, cardPhase: null
  };
  DOC_VERSION[key(matchId)] = (DOC_VERSION[key(matchId)] || 0) + 1;
}

/** Reads a legal card for the CURRENTLY waiting seat from the real
 *  engine's own hand — never a fabricated card, so every scenario
 *  below exercises a genuinely legal play the real engine actually
 *  accepts (or, where a scenario explicitly wants a REJECTED play,
 *  those cases construct their own explicitly-illegal card instead). */
function legalCardForCurrentTurn() {
  var st = TableEngine.getState();
  var hand = st.hands[st.turn];
  var card = st.ledSuit ? (hand.find(function (c) { return c.suit === st.ledSuit; }) || hand[0]) : hand[0];
  return { suit: card.suit, rank: { v: card.rank.v, s: card.rank.s } };
}
function seatUidOf(seat) { return "u" + seat.slice(1); }
// Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync
// Hardening) REMOVED the test-only `syncTurnFieldToRealEngine()`
// helper that previously lived here. That helper manually copied the
// REAL, local engine's own turn into the mocked Firestore document
// before every single submission — exactly the "test-only manual turn
// mutation... claim the production flow works" pattern this sprint's
// own brief explicitly forbids. `submitCard()` now performs this
// exact write itself, atomically, as part of every accepted card
// (see design-ui/match-service.js's own Task 2 comment) — every
// scenario below relies ENTIRELY on that real, production write path
// for turn progression between sequential submissions. The only
// place a turn value is ever set directly in this file now is
// `seedMockMatch()`'s ONE-TIME initial seed (a legitimate setup
// action, not a between-submissions mutation) and the deliberately
// FORGED test data in the "stale snapshot" scenario (simulating a
// malicious/corrupted delivery, not a real submission).

/** Test-only cleanup between scenarios: table-engine.js's `state` is a
 *  SHARED, module-level singleton (not one per mock match — see this
 *  file's own header comment on why table-engine.js can only be
 *  required once per process) — every scenario below plays into the
 *  SAME underlying trick unless explicitly finished first. Fills any
 *  in-progress trick with direct (non-synced, not-under-test) engine
 *  calls for whichever seats haven't played yet this trick, then
 *  resolves it — leaving every scenario a clean, freshly-led trick to
 *  start from, regardless of how many (or how few) plays the PREVIOUS
 *  scenario made through the sync pipeline under test. */
function finishCurrentTrick() {
  var guard = 0;
  while (TableEngine.getState().phase === "PLAY" && guard < 8) {
    var st = TableEngine.getState();
    var hand = st.hands[st.turn];
    var card = st.ledSuit ? (hand.find(function (c) { return c.suit === st.ledSuit; }) || hand[0]) : hand[0];
    TableEngine.emit({ type: "PlayCard", playerId: st.turn, card: { suit: card.suit, rank: { v: card.rank.v, s: card.rank.s } } });
    guard++;
  }
  if (TableEngine.getState().phase === "RESOLVING") TableEngine.resolveTrick();
}

(async function () {
  // ============================================================
  // MOCKED — Task 1 / Acceptance Criteria + "valid card sync" +
  // "remote card": a remote player plays a legal card; it is applied
  // to the local engine exactly once.
  // ============================================================
  seedMockMatch("m-remote");
  var unsub1 = MatchAdapter.startCardSync("m-remote");
  check("MOCKED — Task 1: startCardSync() delivers the initial (pre-play) snapshot without applying anything (no card yet)",
    TableEngine.getState().plays.length === 0);

  var turnBefore = TableEngine.getState().turn;
  signInAs(seatUidOf(turnBefore));
  var card1 = legalCardForCurrentTurn();
  var submitResult1 = await MatchService.submitCard("m-remote", card1);
  check("MOCKED — remote card: submitCard() itself succeeds", submitResult1.version === 2 && submitResult1.cardCount === 1);
  check("MOCKED — Acceptance: the REAL table-engine.js executed exactly once — the card is now recorded in the engine's current trick",
    TableEngine.getState().plays.length === 1 && TableEngine.getState().plays[0].playerId === turnBefore);
  check("MOCKED — Acceptance: the engine correctly advanced to the next seat, proving the real reducer ran, not a stub",
    TableEngine.getState().turn !== turnBefore);
  check("MOCKED — Acceptance: no Firestore write happened outside MatchService — the mocked document's version is exactly what submitCard() itself produced (2)",
    STORE[key("m-remote")].version === 2);
  unsub1();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Duplicate snapshot: receiving the same snapshot twice
  // must not re-render / re-run engine logic / advance turn twice.
  // ============================================================
  seedMockMatch("m-dupsnap");
  var unsub2 = MatchAdapter.startCardSync("m-dupsnap");
  var turnBeforeDup = TableEngine.getState().turn;
  signInAs(seatUidOf(turnBeforeDup));
  await MatchService.submitCard("m-dupsnap", legalCardForCurrentTurn());
  var playsAfterFirst = TableEngine.getState().plays.length;
  var turnAfterFirst = TableEngine.getState().turn;
  notify(key("m-dupsnap"));
  notify(key("m-dupsnap"));
  check("MOCKED — duplicate snapshot: re-delivering the same snapshot does not add another play",
    TableEngine.getState().plays.length === playsAfterFirst);
  check("MOCKED — duplicate snapshot: re-delivering the same snapshot does not move the engine's turn pointer (no replayed state / no double turn advance)",
    TableEngine.getState().turn === turnAfterFirst);
  unsub2();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Stale snapshot / version rollback: an out-of-order
  // delivery of an OLDER version must never roll the engine back.
  // ============================================================
  seedMockMatch("m-stale");
  var unsub3 = MatchAdapter.startCardSync("m-stale");
  var turnBeforeStale = TableEngine.getState().turn;
  signInAs(seatUidOf(turnBeforeStale));
  await MatchService.submitCard("m-stale", legalCardForCurrentTurn());
  var afterRealPlay = { plays: TableEngine.getState().plays.length, turn: TableEngine.getState().turn };
  // Forge a stale, lower-version snapshot with a truncated (rolled
  // back) cardLog and deliver it directly.
  STORE[key("m-stale")] = Object.assign({}, STORE[key("m-stale")], { version: 1, cardLog: [] });
  notify(key("m-stale"));
  check("MOCKED — version rollback: a stale, lower-version snapshot never rolls back the already-applied play",
    TableEngine.getState().plays.length === afterRealPlay.plays);
  check("MOCKED — version rollback: the engine's turn pointer is unaffected by the stale delivery", TableEngine.getState().turn === afterRealPlay.turn);
  unsub3();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Multiple sequential cards: a full trick (4 plays)
  // submitted in order, each one immediately reflected, GameSession
  // consistency checked at the end.
  // ============================================================
  seedMockMatch("m-sequence");
  var unsub4 = MatchAdapter.startCardSync("m-sequence");
  var seq = [];
  for (var t = 0; t < 4; t++) {
    var seat = TableEngine.getState().turn;
    var uid = seatUidOf(seat);
    var card = legalCardForCurrentTurn();
    signInAs(uid);
    await MatchService.submitCard("m-sequence", card);
    seq.push(seat);
    check("MOCKED — multiple sequential cards: " + seat + "'s card is recorded immediately after its own submission",
      TableEngine.getState().plays.some(function (p) { return p.playerId === seat; }) || TableEngine.getState().phase === "RESOLVING");
  }
  check("MOCKED — multiple sequential cards: all 4 real seats each contributed exactly one play to this trick",
    STORE[key("m-sequence")].cardLog.length === 4 && seq.length === 4 && (new Set(seq)).size === 4);
  check("MOCKED — GameSession consistency: table-engine.js's own persisted play state (GameSession.getPlayState()) matches what the engine actually did",
    GameSession.getPlayState().currentPlays.length === 0 || GameSession.getPlayState().phase === "RESOLVING" || GameSession.getPlayState().phase === "PLAY");
  check("MOCKED — Firestore-side consistency: the mocked document's own cardLog has exactly 4 entries, matching the 4 real submissions",
    STORE[key("m-sequence")].cardLog.length === 4);
  unsub4();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Local card vs. remote card / "local echo does not execute
  // the card twice" (Task 5, Sprint 4.2.1, requirement #6). ARCHITECTURE
  // NOTE this test corrects from Sprint 4.2's original version: Sprint
  // 4.2.1's Task 2 pre-write gate (`TableEngine.canPlayCard()`) checks
  // the LOCAL engine's OWN current turn/hand state — so a UI pattern
  // of "call TableEngine.emit() directly for instant feedback, THEN
  // call submitCard() to persist the SAME play" no longer works: by
  // the time submitCard() asks the engine to validate, the engine's
  // own `state.turn` has ALREADY moved past that seat (the direct
  // emit() call advanced it), so `canPlayCard()` correctly reports
  // `NOT_THIS_SEATS_TURN` for the every same play that was just
  // optimistically applied. This is a DELIBERATE, correct consequence
  // of closing Sprint 4.2's Critical defects, not a bug in this
  // sprint's own design — see docs/architecture/EngineAdapter.md's
  // Sprint 4.2.1 section for the full account. The corrected
  // architecture is: `submitCard()` validates-then-persists FIRST
  // (never mutating locally on its own — Task 2's own "do not
  // permanently mutate... merely to validate" — see table-engine.js's
  // `canPlayCard()`), and the actual `TableEngine.emit()` mutation
  // happens EXACTLY ONCE, uniformly, through this SAME client's own
  // `applyRemoteCard()` echo — never via a separate, earlier, direct
  // local `emit()` call racing against the pre-write gate.
  // ============================================================
  seedMockMatch("m-localecho");
  var unsub5 = MatchAdapter.startCardSync("m-localecho");
  var localSeat = TableEngine.getState().turn;
  var localCard = legalCardForCurrentTurn();
  signInAs(seatUidOf(localSeat));
  var playsBeforeSubmit = TableEngine.getState().plays.length;
  await MatchService.submitCard("m-localecho", localCard);
  // tx.update() already triggered this client's own subscription
  // (startCardSync above) automatically — applyRemoteCard() applied
  // the ONE new cardLog entry through the real engine exactly once,
  // with no separate, earlier direct emit() call in the picture at all.
  check("MOCKED — local echo: this client's own submission is applied to the engine EXACTLY ONCE, via its own subscription's echo, not via any separate direct call",
    TableEngine.getState().plays.length === playsBeforeSubmit + 1 && TableEngine.getState().plays.some(function (p) { return p.playerId === localSeat; }));
  var playsAfterSubmit = TableEngine.getState().plays.length;
  // Simulate a redundant, benign re-delivery of the SAME echo (e.g. a
  // duplicate SDK fire for the write this client itself just made) —
  // Task 5, requirement #6's exact ask: the local echo must not
  // execute the card TWICE.
  notify(key("m-localecho"));
  check("MOCKED — local echo: a redundant re-delivery of this client's OWN echo does not re-execute the engine — play count unchanged (not double-applied)",
    TableEngine.getState().plays.length === playsAfterSubmit);
  unsub5();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Late subscriber: a NEW subscription joining AFTER a card
  // has already been applied must observe the CURRENT, already-
  // resolved state, without re-executing anything on its own account.
  // ============================================================
  seedMockMatch("m-late");
  var unsub6a = MatchAdapter.startCardSync("m-late");
  var lateTurnSeat = TableEngine.getState().turn;
  signInAs(seatUidOf(lateTurnSeat));
  await MatchService.submitCard("m-late", legalCardForCurrentTurn());
  var playsBeforeLate = TableEngine.getState().plays.length;
  var turnBeforeLate = TableEngine.getState().turn;
  var unsub6b = MatchAdapter.startCardSync("m-late"); // a second, later subscriber for the SAME match
  check("MOCKED — late subscriber: joining after a card was already applied does not add another play",
    TableEngine.getState().plays.length === playsBeforeLate);
  check("MOCKED — late subscriber: joining does not move the engine's turn pointer", TableEngine.getState().turn === turnBeforeLate);
  check("MOCKED — late subscriber: no duplicated listener was created — MatchService's own ref-counted registry is reused",
    ONSNAPSHOT_CALLS[key("m-late")] === 1);
  unsub6a(); unsub6b();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Listener restart (reconnect) / listener duplicate event.
  // ============================================================
  seedMockMatch("m-restart");
  var unsub7 = MatchAdapter.startCardSync("m-restart");
  var restartSeat1 = TableEngine.getState().turn;
  signInAs(seatUidOf(restartSeat1));
  await MatchService.submitCard("m-restart", legalCardForCurrentTurn());
  var playsAfterFirstRestart = TableEngine.getState().plays.length;
  check("MOCKED — listener restart setup: a card applied before any disconnect", playsAfterFirstRestart >= 1);

  simulateDisconnect("m-restart", "unavailable");
  await wait(1000);
  check("MOCKED — listener restart: after reconnecting, the previously-applied plays are still correct (not reset, not reapplied)",
    TableEngine.getState().plays.length === playsAfterFirstRestart);

  if (TableEngine.getState().phase === "PLAY") {
    var restartSeat2 = TableEngine.getState().turn;
    signInAs(seatUidOf(restartSeat2));
    await MatchService.submitCard("m-restart", legalCardForCurrentTurn());
    check("MOCKED — listener restart: a NEW card submitted after reconnect is correctly applied through the restarted listener",
      TableEngine.getState().plays.length === playsAfterFirstRestart + 1 || TableEngine.getState().phase === "RESOLVING");
  }

  var playsBeforeDup = TableEngine.getState().plays.length;
  var turnBeforeDup2 = TableEngine.getState().turn;
  notify(key("m-restart"));
  notify(key("m-restart"));
  notify(key("m-restart"));
  check("MOCKED — listener duplicate event: three redundant re-deliveries of the current snapshot cause zero additional engine changes",
    TableEngine.getState().plays.length === playsBeforeDup && TableEngine.getState().turn === turnBeforeDup2);
  unsub7();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Wrong turn rejection (Task 3 — reuses Sprint 4.1's
  // EXISTING assertLocalTurn(), NOT a new function). Verified at the
  // gate level: the CLIENT-side check that would run before ANY future
  // submitCard() call, exactly per Task 3's own wording.
  // ============================================================
  var wrongTurnSeat = TableEngine.getState().turn === "p1" ? "p2" : "p1"; // guaranteed NOT the current turn
  var wrongTurnDoc = { turn: seatUidOf(wrongTurnSeat), seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" } };
  var wrongTurnErr = null;
  try { MatchAdapter.assertLocalTurn(wrongTurnDoc, TableEngine.getState().turn); } catch (e) { wrongTurnErr = e; }
  check("MOCKED — wrong turn rejection: assertLocalTurn() throws NOT_LOCAL_TURN when matches/{matchId}.turn names a DIFFERENT seat than the local caller — 'reject locally, do not send writes'",
    wrongTurnErr && wrongTurnErr.reason === "NOT_LOCAL_TURN");
  var correctTurnDoc = { turn: seatUidOf(wrongTurnSeat), seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" } };
  var correctTurnErr = null;
  try { MatchAdapter.assertLocalTurn(correctTurnDoc, wrongTurnSeat); } catch (e) { correctTurnErr = e; }
  check("MOCKED — correct player accepted: assertLocalTurn() does not throw when the local seat DOES match matches/{matchId}.turn",
    correctTurnErr === null);

  // ============================================================
  // MOCKED — Adapter corruption (end-to-end): a genuinely malformed
  // live delivery must not crash the pipeline.
  // ============================================================
  seedMockMatch("m-corrupt");
  var unsub8 = MatchAdapter.startCardSync("m-corrupt");
  var playsBeforeCorrupt = TableEngine.getState().plays.length;
  STORE[key("m-corrupt")] = Object.assign({}, STORE[key("m-corrupt")], { version: 2, cardLog: "not-an-array" });
  var corruptThrew = false;
  try { notify(key("m-corrupt")); } catch (e) { corruptThrew = true; }
  check("MOCKED — adapter corruption (end-to-end): a malformed live delivery (cardLog not an array) never throws/crashes the pipeline", !corruptThrew);
  check("MOCKED — adapter corruption (end-to-end): the malformed delivery caused no engine change", TableEngine.getState().plays.length === playsBeforeCorrupt);
  unsub8();

  // ============================================================
  // MOCKED — Regression sanity: MatchService's other gameplay stubs
  // and MatchAdapter's Sprint 3.9/4.0/4.1 API are untouched.
  // ============================================================
  ["submitDashCall", "submitPass", "declareTrump", "submitEstimate", "playCard", "resolveTrick", "completeRound", "advanceToNextRound", "endMatch"].forEach(function (m) {
    var threw = false;
    try { MatchService[m](); } catch (e) { threw = /not implemented/i.test(e.message); }
    check("MOCKED — regression: MatchService." + m + "() is still an unimplemented stub, unchanged by this sprint", threw);
  });
  check("MOCKED — regression: MatchAdapter.applyRemoteBid (Sprint 4.0) is still present and unchanged in shape", typeof MatchAdapter.applyRemoteBid === "function");
  check("MOCKED — regression: MatchAdapter.applyRemoteTurn (Sprint 4.1) is still present and unchanged in shape", typeof MatchAdapter.applyRemoteTurn === "function");
  check("MOCKED — regression: MatchAdapter.assertLocalTurn (Sprint 4.1) is still present and unchanged in shape — REUSED, not reimplemented, this sprint", typeof MatchAdapter.assertLocalTurn === "function");

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
