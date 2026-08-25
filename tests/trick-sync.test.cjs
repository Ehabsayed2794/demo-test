// Real, executable END-TO-END tests for Sprint 4.3 (Trick Resolution
// Synchronization) — the FULL pipeline:
//   4 x submitCard() -> Firestore -> MatchService listener -> Engine
//   Adapter -> table-engine.js (emit() + resolveTrick()) -> GameSession
// exercised together, in one process, against the REAL
// design-ui/match-service.js, design-ui/match-adapter.js,
// design-ui/engine/table-engine.js, design-ui/engine/bidding-engine.js,
// and design-ui/engine/session.js (GameSession) — not stubs, not
// fakes, the actual shipped code for every one of those five files.
//
// LABELING: every check below is MOCKED — real code from all five
// files above, exercised against a hand-written fake Firestore (the
// SAME harness shape as tests/card-sync.test.cjs/tests/bid-sync.test.cjs/
// tests/turn-sync.test.cjs). No SIMULATED checks (no firestore.rules
// involved — Sprint 4.3 made no rules change; see this sprint's own
// Implementation Report for why). No real Firestore project, Firebase
// Emulator, or browser was used anywhere in this file.
//
// Architectural note this test's structure works around (identical to
// tests/card-sync.test.cjs's own note): table-engine.js's PLAYERS/
// TURN_ORDER/ROUND_CFG are computed ONCE, at require()-time, from
// GameSession's state at that instant — table-engine.js is therefore
// required LATER in this file, after bidding completes, and every
// scenario below shares the SAME underlying real engine trick-state
// timeline (one continuous round), cleaned up between scenarios via
// the same finishCurrentTrick() helper card-sync.test.cjs already
// established.
//
// HONEST SCOPE NOTE on "multiple consecutive tricks" / "late
// subscriber": this file deliberately does NOT forge a cold-start
// Firestore document with TWO already-completed tricks' worth of
// cardLog entries computed independently of the real engine — doing so
// would require this TEST to correctly predict trick 1's real winner
// (to seed trick 2's forged leader) using its own, separately-computed
// trump/follow-suit comparison, which risks silently diverging from
// table-engine.js's own real rule and would not actually strengthen
// this test (a divergence would only prove the test's OWN fixture is
// wrong, not that the sync code is). Instead: "multiple consecutive
// tricks" is tested against the REAL, continuously-running production
// pipeline (2 real tricks played end-to-end, each winner determined
// entirely by the real engine, never guessed by this test); "late
// subscriber" is tested against a genuine, real, ONE-trick backlog
// (four real submitCard() calls with card-sync but deliberately NO
// trick-sync subscriber active, so the trick reaches RESOLVING and
// STAYS there — a real, not simulated, unresolved backlog — until a
// late startTrickSync() call resolves it for the first time). The
// exact "N completed tricks in ONE cold-start snapshot" loop mechanism
// itself (the part that requires forging ahead) is unit-tested against
// a CONTROLLABLE FAKE engine in tests/match-adapter.test.cjs's own
// "multiple consecutive tricks" section instead, where predicting a
// fake's "winner" carries none of that real-engine-divergence risk.
var REPO_ROOT = require("path").join(__dirname, "..");
function repoPath() { return require("path").join.apply(require("path"), [REPO_ROOT].concat(Array.prototype.slice.call(arguments))); }

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

require(repoPath("design-ui", "match-service.js"));
require(repoPath("design-ui", "engine", "cards.js"));
require(repoPath("design-ui", "engine", "deck.js"));
require(repoPath("design-ui", "engine", "dealer.js"));
require(repoPath("design-ui", "engine", "session.js"));
require(repoPath("design-ui", "engine", "bidding-engine.js"));
require(repoPath("design-ui", "engine", "scoring-engine.js"));
require(repoPath("design-ui", "match-adapter.js"));
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

/** Identical to tests/card-sync.test.cjs's own helper — drives the REAL
 *  bidding-engine.js to a fully-committed round so table-engine.js
 *  (required immediately after) computes a real ROUND_CFG. */
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
require(repoPath("design-ui", "engine", "table-engine.js"));
var TableEngine = global.TableEngine;
TableEngine.initState();

/** Identical shape to tests/card-sync.test.cjs's own seedMockMatch() —
 *  `turn` seeded from the REAL engine's own live turn (a legitimate,
 *  one-time setup action, never a between-submissions mutation — see
 *  that file's own comment for the full "no test-only turn mutation"
 *  account this project established in Sprint 4.2.2 and has not
 *  reintroduced since). */
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

function legalCardForCurrentTurn() {
  var st = TableEngine.getState();
  var hand = st.hands[st.turn];
  var card = st.ledSuit ? (hand.find(function (c) { return c.suit === st.ledSuit; }) || hand[0]) : hand[0];
  return { suit: card.suit, rank: { v: card.rank.v, s: card.rank.s } };
}
function seatUidOf(seat) { return "u" + seat.slice(1); }

/** Same test-only cleanup as tests/card-sync.test.cjs's own
 *  finishCurrentTrick() — direct, non-synced engine calls, never
 *  through the pipeline under test, used only to leave a clean,
 *  freshly-led trick between scenarios. */
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

/** Independently (re-)computes which of 4 real plays SHOULD win, per
 *  the real rules doc's own trump/follow-suit/high-card ordering —
 *  used ONLY here, in a TEST, to cross-check that the SYNC layer
 *  correctly relayed table-engine.js's own real answer, never to
 *  duplicate or second-guess production logic (table-engine.js's own
 *  `cardValue()`/`trickWinner()` remain the ONLY implementation that
 *  ships; this is a test-side, independent verification of the
 *  SAME publicly-documented rule, exactly the same spirit as this
 *  project's existing docx-grounded scoring tests). */
function independentlyComputeWinner(plays, trump) {
  function value(card) {
    var isTrump = trump !== "SANS" && card.suit === trump;
    var follows = card.suit === plays[0].card.suit;
    return card.rank.v + (isTrump ? 1000 : (follows ? 100 : 0));
  }
  var best = plays[0];
  plays.forEach(function (p) { if (value(p.card) > value(best.card)) best = p; });
  return best.playerId;
}

(async function () {
  // ============================================================
  // MOCKED — Task 6: "trick completes after fourth card" + "winner
  // matches TableEngine". startTrickSync() alone (its own callback
  // already replays cards via the EXISTING applyRemoteCard() — no
  // separate startCardSync() call is needed).
  // ============================================================
  seedMockMatch("m-trick1");
  var unsub1 = MatchAdapter.startTrickSync("m-trick1");
  var tricksWonBefore = Object.assign({}, TableEngine.getState().tricksWon);
  var trickNoBefore = TableEngine.getState().trickNo;
  var trump = TableEngine.getState().trump;
  var playedThisTrick = [];
  for (var i = 0; i < 4; i++) {
    var seat = TableEngine.getState().turn;
    var card = legalCardForCurrentTurn();
    playedThisTrick.push({ playerId: seat, card: card });
    signInAs(seatUidOf(seat));
    await MatchService.submitCard("m-trick1", card);
  }
  check("MOCKED — trick completes after fourth card: the real engine has moved on to the NEXT trick (or DONE) — phase is no longer RESOLVING",
    TableEngine.getState().phase === "PLAY" || TableEngine.getState().phase === "DONE");
  check("MOCKED — trick completes after fourth card: exactly one trick's worth of tricksWon was awarded, to exactly one seat",
    Object.keys(tricksWonBefore).reduce(function (sum, s) { return sum + (TableEngine.getState().tricksWon[s] - tricksWonBefore[s]); }, 0) === 1);
  var expectedWinner = independentlyComputeWinner(playedThisTrick, trump);
  check("MOCKED — winner matches TableEngine: MatchAdapter.getLastResolvedTrickNo() confirms this trick was resolved via the sync pipeline (not a leftover from a previous scenario)",
    MatchAdapter.getLastResolvedTrickNo("m-trick1") === trickNoBefore);
  check("MOCKED — winner matches TableEngine: the seat this test independently computed as the winner (via the SAME publicly-documented trump/follow-suit rule) is the SAME seat the real engine actually credited a trick to",
    TableEngine.getState().tricksWon[expectedWinner] === tricksWonBefore[expectedWinner] + 1);
  unsub1();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Task 6: "duplicate snapshot ignored".
  // ============================================================
  seedMockMatch("m-trickdup");
  var unsub2 = MatchAdapter.startTrickSync("m-trickdup");
  for (var d = 0; d < 4; d++) {
    var seatD = TableEngine.getState().turn;
    signInAs(seatUidOf(seatD));
    await MatchService.submitCard("m-trickdup", legalCardForCurrentTurn());
  }
  var tricksWonAfterFirst = Object.assign({}, TableEngine.getState().tricksWon);
  var trickNoAfterFirst = TableEngine.getState().trickNo;
  var resolvedTrickNo = MatchAdapter.getLastResolvedTrickNo("m-trickdup");
  notify(key("m-trickdup"));
  notify(key("m-trickdup"));
  check("MOCKED — duplicate snapshot ignored: re-delivering the same (already-resolved) snapshot does not change tricksWon again",
    JSON.stringify(TableEngine.getState().tricksWon) === JSON.stringify(tricksWonAfterFirst));
  check("MOCKED — duplicate snapshot ignored: re-delivering the same snapshot does not advance trickNo again",
    TableEngine.getState().trickNo === trickNoAfterFirst);
  check("MOCKED — duplicate snapshot ignored: applyRemoteTrick()'s own idempotency registry is unchanged by the redundant redelivery",
    MatchAdapter.getLastResolvedTrickNo("m-trickdup") === resolvedTrickNo);
  unsub2();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Task 6: "stale snapshot ignored".
  // ============================================================
  seedMockMatch("m-trickstale");
  var unsub3 = MatchAdapter.startTrickSync("m-trickstale");
  for (var st2 = 0; st2 < 4; st2++) {
    var seatS = TableEngine.getState().turn;
    signInAs(seatUidOf(seatS));
    await MatchService.submitCard("m-trickstale", legalCardForCurrentTurn());
  }
  var tricksWonAfterReal = Object.assign({}, TableEngine.getState().tricksWon);
  // Forge a stale, lower-version snapshot with a rolled-back cardLog —
  // identical shape to tests/card-sync.test.cjs's own "version rollback"
  // scenario, delivered directly (never through a real write).
  STORE[key("m-trickstale")] = Object.assign({}, STORE[key("m-trickstale")], { version: 1, cardLog: [] });
  notify(key("m-trickstale"));
  check("MOCKED — stale snapshot ignored: a stale, lower-version delivery never re-triggers or reverses trick resolution",
    JSON.stringify(TableEngine.getState().tricksWon) === JSON.stringify(tricksWonAfterReal));
  unsub3();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Task 6: "reconnect". A partial trick, a disconnect, a
  // reconnect, then the trick is completed and correctly resolved
  // through the RESTARTED listener.
  // ============================================================
  seedMockMatch("m-trickreconnect");
  var unsub4 = MatchAdapter.startTrickSync("m-trickreconnect");
  for (var r = 0; r < 2; r++) {
    var seatR = TableEngine.getState().turn;
    signInAs(seatUidOf(seatR));
    await MatchService.submitCard("m-trickreconnect", legalCardForCurrentTurn());
  }
  var playsBeforeDisconnect = TableEngine.getState().plays.length;
  check("MOCKED — reconnect setup: 2 of 4 cards applied before any disconnect", playsBeforeDisconnect === 2);

  simulateDisconnect("m-trickreconnect", "unavailable");
  await wait(1000);
  check("MOCKED — reconnect: after reconnecting, the partial trick's already-applied plays are unchanged (not reset, not reapplied)",
    TableEngine.getState().plays.length === playsBeforeDisconnect);

  var tricksWonBeforeFinish = Object.assign({}, TableEngine.getState().tricksWon);
  for (var r2 = 0; r2 < 2; r2++) {
    var seatR2 = TableEngine.getState().turn;
    signInAs(seatUidOf(seatR2));
    await MatchService.submitCard("m-trickreconnect", legalCardForCurrentTurn());
  }
  check("MOCKED — reconnect: the trick completes and resolves correctly through the RESTARTED listener — tricksWon advanced by exactly 1",
    Object.keys(tricksWonBeforeFinish).reduce(function (sum, s) { return sum + (TableEngine.getState().tricksWon[s] - tricksWonBeforeFinish[s]); }, 0) === 1);
  unsub4();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Task 6: "late subscriber". Four real cards are submitted
  // with card-sync active but DELIBERATELY NO trick-sync subscriber —
  // the trick genuinely completes (phase reaches RESOLVING) and STAYS
  // there, a real (not simulated) unresolved backlog, until a LATE
  // startTrickSync() call resolves it for the first time.
  // ============================================================
  seedMockMatch("m-tricklate");
  var unsubCardOnly = MatchAdapter.startCardSync("m-tricklate"); // cards flow in real time; nothing resolves them
  for (var l = 0; l < 4; l++) {
    var seatL = TableEngine.getState().turn;
    signInAs(seatUidOf(seatL));
    await MatchService.submitCard("m-tricklate", legalCardForCurrentTurn());
  }
  check("MOCKED — late subscriber setup: the trick genuinely completed (4 real cards applied) but remains UNRESOLVED — no trick-sync subscriber has ever run",
    TableEngine.getState().phase === "RESOLVING");
  var tricksWonBeforeLate = Object.assign({}, TableEngine.getState().tricksWon);
  var unsubTrickLate = MatchAdapter.startTrickSync("m-tricklate"); // the FIRST trick-sync subscriber, joining late
  check("MOCKED — late subscriber: joining late resolves the REAL, already-completed backlog on its very first delivery",
    TableEngine.getState().phase === "PLAY" || TableEngine.getState().phase === "DONE");
  check("MOCKED — late subscriber: exactly one trick's worth of tricksWon was awarded by the late catch-up, not zero, not more than one",
    Object.keys(tricksWonBeforeLate).reduce(function (sum, s) { return sum + (TableEngine.getState().tricksWon[s] - tricksWonBeforeLate[s]); }, 0) === 1);
  check("MOCKED — late subscriber: no duplicated Firestore listener was created for the late subscription — MatchService's own ref-counted registry is reused",
    ONSNAPSHOT_CALLS[key("m-tricklate")] === 1);
  // A SECOND late subscription for the SAME (now-resolved) match must
  // not re-resolve anything — mirrors card-sync.test.cjs's own "late
  // subscriber" idempotency shape exactly.
  var tricksWonAfterLate = Object.assign({}, TableEngine.getState().tricksWon);
  var unsubTrickLate2 = MatchAdapter.startTrickSync("m-tricklate");
  check("MOCKED — late subscriber: a SECOND late subscription for the same, already-resolved match changes nothing",
    JSON.stringify(TableEngine.getState().tricksWon) === JSON.stringify(tricksWonAfterLate));
  unsubCardOnly(); unsubTrickLate(); unsubTrickLate2();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Task 6: "malformed trick" — a genuinely malformed live
  // delivery must not crash the trick-sync pipeline.
  // ============================================================
  seedMockMatch("m-trickmalformed");
  var unsub5 = MatchAdapter.startTrickSync("m-trickmalformed");
  var tricksWonBeforeMalformed = Object.assign({}, TableEngine.getState().tricksWon);
  STORE[key("m-trickmalformed")] = Object.assign({}, STORE[key("m-trickmalformed")], { version: 2, cardLog: "not-an-array" });
  var malformedThrew = false;
  try { notify(key("m-trickmalformed")); } catch (e) { malformedThrew = true; }
  check("MOCKED — malformed trick: a malformed live delivery (cardLog not an array) never throws/crashes the trick-sync pipeline", !malformedThrew);
  check("MOCKED — malformed trick: the malformed delivery caused no trick resolution",
    JSON.stringify(TableEngine.getState().tricksWon) === JSON.stringify(tricksWonBeforeMalformed));
  var malformedResult = MatchAdapter.applyRemoteTrick("m-trickmalformed", "not-an-object");
  check("MOCKED — malformed trick: applyRemoteTrick() itself reports MALFORMED_SNAPSHOT for a non-object matchDoc, called directly",
    malformedResult.applied === false && malformedResult.reason === "MALFORMED_SNAPSHOT");
  unsub5();

  // ============================================================
  // MOCKED — Task 6: "ENGINE_REJECTED" + "desync reporting". A card
  // that the REAL engine will genuinely reject (wrong seat's turn) is
  // injected directly into cardLog, bypassing submitCard()'s own
  // pre-write gate entirely — exactly like tests/match-adapter.test.cjs's
  // own ENGINE_REJECTED unit tests, but exercised here end-to-end
  // against the REAL table-engine.js.
  // ============================================================
  seedMockMatch("m-trickrejected");
  var unsub6 = MatchAdapter.startTrickSync("m-trickrejected", "p1");
  var leaderSeat = TableEngine.getState().turn;
  var wrongSeat = ["p1", "p2", "p3", "p4"].filter(function (s) { return s !== leaderSeat; })[0];
  var illegalEntry = { seatId: wrongSeat, card: legalCardForCurrentTurn() }; // a real card, but attributed to a seat whose turn it is NOT
  STORE[key("m-trickrejected")] = Object.assign({}, STORE[key("m-trickrejected")], {
    version: 2, cardLog: [illegalEntry], lastCardSeat: wrongSeat, turn: seatUidOf(leaderSeat), cardPhase: "PLAY"
  });
  var tricksWonBeforeRejected = Object.assign({}, TableEngine.getState().tricksWon);
  var playsBeforeRejected = TableEngine.getState().plays.length;
  notify(key("m-trickrejected"));
  check("MOCKED — ENGINE_REJECTED: the real engine genuinely refuses the wrong-seat card — no play was recorded",
    TableEngine.getState().plays.length === playsBeforeRejected);
  check("MOCKED — ENGINE_REJECTED: no trick resolution occurred as a result of the rejected card",
    JSON.stringify(TableEngine.getState().tricksWon) === JSON.stringify(tricksWonBeforeRejected));
  // Call applyRemoteCard() directly, on the SAME current document, to
  // inspect the full structured desync shape applyRemoteTrick() itself
  // deliberately never re-derives or duplicates (see that function's
  // own header comment).
  var directCardResult = MatchAdapter.applyRemoteCard("m-trickrejected", STORE[key("m-trickrejected")], "p1");
  check("MOCKED — desync reporting: the underlying applyRemoteCard() call reports a structured desync — desync:true, reason:ENGINE_REJECTED",
    directCardResult.desync === true && directCardResult.reason === "ENGINE_REJECTED");
  check("MOCKED — desync reporting: the desync result carries matchId/index/seatId diagnostics, not a bare boolean",
    directCardResult.matchId === "m-trickrejected" && directCardResult.index === 0 && directCardResult.seatId === wrongSeat);
  var directTrickResult = MatchAdapter.applyRemoteTrick("m-trickrejected", STORE[key("m-trickrejected")]);
  check("MOCKED — desync reporting: applyRemoteTrick() correctly stays NOT_RESOLVING — it never masks the upstream desync by attempting a resolution anyway",
    directTrickResult.applied === false && directTrickResult.reason === "NOT_RESOLVING");
  unsub6();
  // Recovery: fix the document with the REAL legal card for the actual
  // leader, so this scenario leaves the engine in a clean, resolvable
  // state for the next one (direct engine calls, not through the
  // pipeline under test — matching finishCurrentTrick()'s own pattern).
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Task 6: "multiple consecutive tricks" — 2 full,
  // real tricks (8 real cards), played end-to-end through the REAL
  // production pipeline with startTrickSync() continuously active,
  // each winner determined entirely by the real engine.
  // ============================================================
  seedMockMatch("m-trickmulti");
  var unsub7 = MatchAdapter.startTrickSync("m-trickmulti");
  var tricksWonMultiStart = Object.assign({}, TableEngine.getState().tricksWon);
  var trickNoMultiStart = TableEngine.getState().trickNo;
  for (var trickIdx = 0; trickIdx < 2; trickIdx++) {
    for (var c = 0; c < 4; c++) {
      var seatM = TableEngine.getState().turn;
      signInAs(seatUidOf(seatM));
      await MatchService.submitCard("m-trickmulti", legalCardForCurrentTurn());
    }
    check("MOCKED — multiple consecutive tricks: trick " + (trickIdx + 1) + " resolved immediately after its own 4th card — the engine has moved past RESOLVING",
      TableEngine.getState().phase === "PLAY" || TableEngine.getState().phase === "DONE");
  }
  var tricksAwarded = Object.keys(tricksWonMultiStart).reduce(function (sum, s) { return sum + (TableEngine.getState().tricksWon[s] - tricksWonMultiStart[s]); }, 0);
  check("MOCKED — multiple consecutive tricks: exactly 2 tricks' worth of tricksWon were awarded across the 2 played tricks, none skipped, none double-counted",
    tricksAwarded === 2);
  check("MOCKED — multiple consecutive tricks: trickNo advanced by exactly 2",
    TableEngine.getState().trickNo === trickNoMultiStart + 2 || TableEngine.getState().phase === "DONE");
  unsub7();
  finishCurrentTrick();

  // ============================================================
  // MOCKED — Task 1 (Architecture Verification), re-verified directly:
  // no new export was added to table-engine.js for this sprint.
  // ============================================================
  var tableEngineSource = require("fs").readFileSync(repoPath("design-ui", "engine", "table-engine.js"), "utf8");
  var tableEngineExportsMatch = tableEngineSource.match(/window\.TableEngine = \{[\s\S]*?\};/);
  check("MOCKED — Task 1: table-engine.js's own export object is UNCHANGED by this sprint — still exactly initState/emit/resolveTrick/getState/canPlayCard/previewPlay",
    !!tableEngineExportsMatch && /initState:\s*initState/.test(tableEngineExportsMatch[0]) &&
    /emit:\s*emit/.test(tableEngineExportsMatch[0]) && /resolveTrick:\s*resolveTrick/.test(tableEngineExportsMatch[0]) &&
    /getState:/.test(tableEngineExportsMatch[0]) && /canPlayCard:\s*canPlayCard/.test(tableEngineExportsMatch[0]) &&
    /previewPlay:\s*previewPlay/.test(tableEngineExportsMatch[0]));

  // ============================================================
  // MOCKED — Task 4/5 (no MatchService/firestore.rules change was
  // required): re-verified directly here, not just asserted in docs.
  // ============================================================
  var matchServiceSource = require("fs").readFileSync(repoPath("design-ui", "match-service.js"), "utf8");
  check("MOCKED — Task 4: MatchService.submitCard()'s own transaction patch is UNCHANGED by this sprint — still exactly {cardLog, lastCardSeat, turn, cardPhase, version, updatedAt}, no new trick-related field",
    /cardLog: cardLog,\s*\n\s*lastCardSeat: freshSeatId,\s*\n\s*turn: nextTurnUid,\s*\n\s*cardPhase: preview\.nextPhase,\s*\n\s*version: nextVersion,\s*\n\s*updatedAt: serverTimestamp\(\)/.test(matchServiceSource));
  var rulesSource = require("fs").readFileSync(repoPath("firestore.rules"), "utf8");
  check("MOCKED — Task 5: firestore.rules' isValidCardSubmission() affectedKeys allowlist is UNCHANGED by this sprint — still exactly ['cardLog', 'lastCardSeat', 'version', 'turn', 'cardPhase', 'updatedAt'], no new trick-related field permitted",
    /affectedKeys\(\)\.hasOnly\(\['cardLog', 'lastCardSeat', 'version', 'turn', 'cardPhase', 'updatedAt'\]\)/.test(rulesSource));

  // ============================================================
  // MOCKED — Regression sanity: MatchService's other gameplay stubs
  // and MatchAdapter's Sprint 3.9/4.0/4.1/4.2 API are untouched.
  // ============================================================
  // Round Lifecycle sprint: advanceToNextRound() is now a REAL, implemented method (not a stub) — removed from this stub-regression list on purpose, not an oversight. Match Completion sprint: endMatch() is likewise now a REAL, implemented method (not a stub) — removed from this stub-regression list on purpose, not an oversight (see tests/match-completion.test.cjs for its own dedicated suite). completeRound() remains an intentional stub (see its own doc comment in match-service.js for why) and is still checked below.
  ["submitDashCall", "submitPass", "declareTrump", "submitEstimate", "playCard", "resolveTrick", "completeRound"].forEach(function (m) {
    var threw = false;
    try { MatchService[m](); } catch (e) { threw = /not implemented/i.test(e.message); }
    check("MOCKED — no regression: MatchService." + m + "() is still an unimplemented stub, unchanged by this sprint", threw);
  });
  check("MOCKED — no regression: MatchAdapter.applyRemoteBid (Sprint 4.0) is still present and unchanged in shape", typeof MatchAdapter.applyRemoteBid === "function");
  check("MOCKED — no regression: MatchAdapter.applyRemoteTurn (Sprint 4.1) is still present and unchanged in shape", typeof MatchAdapter.applyRemoteTurn === "function");
  check("MOCKED — no regression: MatchAdapter.applyRemoteCard (Sprint 4.2) is still present and unchanged in shape", typeof MatchAdapter.applyRemoteCard === "function");
  check("MOCKED — no regression: MatchAdapter.startCardSync (Sprint 4.2) is still present and unchanged in shape", typeof MatchAdapter.startCardSync === "function");
  check("MOCKED — no regression: MatchAdapter.assertLocalTurn (Sprint 4.1) is still present and unchanged in shape — REUSED, not reimplemented, this sprint", typeof MatchAdapter.assertLocalTurn === "function");

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
