var REPO_ROOT = require("path").join(__dirname, "..");
// Real, executable tests for the Round Lifecycle sprint — Round 1 ->
// Round 2 transition:
//   TableEngine reaches phase DONE -> MatchAdapter.maybeAdvanceRound()
//   -> MatchService.advanceToNextRound() -> Firestore -> every
//   subscribed client's MatchAdapter.applyRemoteRoundTransition() ->
//   GameSession.nextRound() + BiddingEngine.initState()
// exercised together, in one process, against the REAL
// design-ui/match-service.js, design-ui/match-adapter.js,
// design-ui/engine/bidding-engine.js, design-ui/engine/table-engine.js,
// and design-ui/engine/session.js (GameSession) — not stubs, not
// fakes, the actual shipped code for every one of those five files.
//
// LABELING: every check below is MOCKED — real code from all five
// files above, exercised against a hand-written fake Firestore (the
// SAME harness shape as tests/card-sync.test.cjs/tests/trick-sync.test.cjs).
// No SIMULATED checks (firestore.rules' own isValidRoundAdvance()/
// round-tagging additions are covered separately, in
// tests/rules-simulation.test.js). No real Firestore project, Firebase
// Emulator, or browser was used anywhere in this file.
//
// SCOPE NOTE: advanceToNextRound()'s own structural completion check
// (52 round-tagged cardLog entries) only counts entries — it does not
// re-run table-engine.js's own rules. This file therefore fabricates
// the 52-entry cardLog directly (tagged `round: 1`) rather than
// re-playing 13 real tricks through the engine — the exact same
// "cheap fixture, not a duplicate rules engine" choice this project's
// other sync tests already make wherever content correctness isn't
// what's under test (see e.g. tests/card-sync.test.cjs's own
// seedMockMatch()). What IS exercised against the REAL engine, live,
// is the round-transition machinery itself: GameSession.nextRound(),
// BiddingEngine.initState(), and the AWAITING_ROUND_TRANSITION
// deferral inside applyRemoteBiddingAction()/applyRemoteCard().
global.window = global;
global.window.addEventListener = function () {};

var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var ONSNAPSHOT_CALLS = {};

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
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    update: function (patch) {
      STORE[k] = Object.assign({}, STORE[k], patch);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      notify(k);
      return Promise.resolve();
    },
    onSnapshot: function (onNext) {
      ONSNAPSHOT_CALLS[k] = (ONSNAPSHOT_CALLS[k] || 0) + 1;
      LISTENERS[k] = LISTENERS[k] || [];
      LISTENERS[k].push(onNext);
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      onNext({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
      return function unsubscribe() {
        LISTENERS[k] = (LISTENERS[k] || []).filter(function (cb) { return cb !== onNext; });
      };
    }
  };
}
var FAKE_DB = {
  collection: function (name) {
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { return makeMatchRef(id); } };
  },
  runTransaction: function (fn, attempt) {
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
    var seenVersions = {}, pending = {};
    var tx = {
      get: function (ref) { seenVersions[ref._key] = DOC_VERSION[ref._key] || 0; return ref.get(); },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      Object.keys(pending).forEach(function (k) { STORE[k] = Object.assign({}, STORE[k], pending[k].data); DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1; });
      Object.keys(pending).forEach(function (k) { notify(k); });
      return result;
    });
  }
};
global.Db = FAKE_DB;
global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } } } };

var CURRENT_USER = null;
global.SessionService = { getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; }, setCurrentMatchId: function () { return Promise.resolve(); } };
function signInAs(uid) { CURRENT_USER = uid; }

require(REPO_ROOT + "/design-ui/match-service.js");
require(REPO_ROOT + "/design-ui/engine/cards.js");
require(REPO_ROOT + "/design-ui/engine/deck.js");
require(REPO_ROOT + "/design-ui/engine/dealer.js");
require(REPO_ROOT + "/design-ui/engine/session.js");
require(REPO_ROOT + "/design-ui/engine/bidding-engine.js");
require(REPO_ROOT + "/design-ui/engine/scoring-engine.js");
require(REPO_ROOT + "/design-ui/match-adapter.js");

var MatchService = global.MatchService;
var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

/** Identical to every other sync test file's own helper — drives the
 *  REAL bidding-engine.js to a fully-committed round so table-engine.js
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
require(REPO_ROOT + "/design-ui/engine/table-engine.js");
var TableEngine = global.TableEngine;
TableEngine.initState();

function fiftyTwoRoundTaggedCardEntries(round) {
  var log = [];
  for (var t = 0; t < 13; t++) {
    ["p1", "p2", "p3", "p4"].forEach(function (seatId) {
      log.push({ seatId: seatId, card: { suit: "SPADES", rank: { v: 2, s: "2" } }, round: round });
    });
  }
  return log;
}

function seedMockMatch(matchId, opts) {
  opts = opts || {};
  STORE[key(matchId)] = Object.assign({
    roomId: "room-x", players: ["u1", "u2", "u3", "u4"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "u1", turn: null, gameState: { initialized: false },
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: []
  }, opts);
  DOC_VERSION[key(matchId)] = (DOC_VERSION[key(matchId)] || 0) + 1;
}

function uidOf(seatId) { return "u" + seatId.slice(1); }

// ════════════════════════════════════════════════════════════════
// A/B — round-tagging: submitBiddingAction()/submitCard() stamp the
// FRESH, in-transaction document's own currentRound onto every new
// entry, never a client-supplied value.
// ════════════════════════════════════════════════════════════════
GameSession.reset(null);
MatchAdapter.resetSyncState();
BiddingEngine.initState(); // fresh DASH phase, round 1 — canSubmit() needs a real, live DASH-phase engine, not the DONE one table-engine.js's own setup above left it in
var dashSeat = BiddingEngine.getState().waitingFor;
seedMockMatch("match-tag-1");
signInAs(uidOf(dashSeat));
MatchService.submitBiddingAction("match-tag-1", { actionType: "SubmitDashCallDecision", declaredDashCall: false })
  .then(function () {
    var doc = STORE[key("match-tag-1")];
    check("A. submitBiddingAction() stamps the new biddingLog entry with the document's own currentRound (1)", doc.biddingLog[0].round === 1);
  })
  .then(function () {
    // Re-commit a fresh round (round 1 again — GameSession.reset() above
    // did not advance the round number, only reset bidding/play
    // sub-state) so TableEngine has a genuine, current PLAY state to
    // preview a real card against for test B.
    driveBiddingToCommittedRound();
    TableEngine.initState();
    var playSeat = TableEngine.getState().turn;
    var hand = TableEngine.getState().hands[playSeat];
    var card = { suit: hand[0].suit, rank: { v: hand[0].rank.v, s: hand[0].rank.s } };
    seedMockMatch("match-tag-2", { turn: uidOf(playSeat), biddingOpen: false, cardPhase: "PLAY" });
    signInAs(uidOf(playSeat));
    return MatchService.submitCard("match-tag-2", card);
  })
  .then(function () {
    var doc = STORE[key("match-tag-2")];
    check("B. submitCard() stamps the new cardLog entry with the document's own currentRound (1)", doc.cardLog[0].round === 1);
  })
  .then(runRemainingChecks)
  .catch(function (e) {
    console.error("UNCAUGHT TEST ERROR (A/B):", e);
    process.exitCode = 1;
  });

function runRemainingChecks() {
  // ════════════════════════════════════════════════════════════════
  // C/D/E/F — MatchService.advanceToNextRound()
  // ════════════════════════════════════════════════════════════════
  seedMockMatch("match-adv-1", { cardLog: fiftyTwoRoundTaggedCardEntries(1).slice(0, 8) });
  signInAs("u1");
  return MatchService.advanceToNextRound("match-adv-1", 1)
    .then(function () { check("C. advanceToNextRound(): unexpectedly resolved with an incomplete round (only 8/52 cards) — should have thrown", false); })
    .catch(function (e) { check("C. advanceToNextRound(): rejects ROUND_NOT_COMPLETE when fewer than 52 round-tagged cards exist", e.reason === "ROUND_NOT_COMPLETE"); })

    .then(function () {
      seedMockMatch("match-adv-2", { cardLog: fiftyTwoRoundTaggedCardEntries(1), bids: { p1: 4, p2: 2, p3: 2, p4: 2 }, turn: "u3", cardPhase: "RESOLVING" });
      return MatchService.advanceToNextRound("match-adv-2", 1);
    })
    .then(function (result) {
      var doc = STORE[key("match-adv-2")];
      check("D. advanceToNextRound(): resolves {advanced:true} once 52 round-tagged cards exist", result.advanced === true);
      check("D. advanceToNextRound(): currentRound advances from 1 to 2", doc.currentRound === 2);
      check("D. advanceToNextRound(): dealer rotates from p1 owner u1 to p2 owner u2", doc.dealer === "u2");
      check("D. advanceToNextRound(): version increments by exactly 1", doc.version === 2);
      check("D. advanceToNextRound(): biddingOpen reset to true", doc.biddingOpen === true);
      check("D. advanceToNextRound(): bids reset to all-null", doc.bids.p1 === null && doc.bids.p2 === null && doc.bids.p3 === null && doc.bids.p4 === null);
      check("D. advanceToNextRound(): turn reset to null (no defined meaning until new bidding resumes)", doc.turn === null);
      check("D. advanceToNextRound(): cardPhase reset to null", doc.cardPhase === null);
      check("D. advanceToNextRound(): Round 1's own 52 cardLog entries are completely untouched (append-only, never cleared)", doc.cardLog.length === 52 && doc.cardLog.every(function (e) { return e.round === 1; }));

      // E — idempotent no-op on a second call for the SAME completed round
      return MatchService.advanceToNextRound("match-adv-2", 1);
    })
    .then(function (result) {
      var doc = STORE[key("match-adv-2")];
      check("E. advanceToNextRound(): a second call for the SAME already-advanced round is a harmless no-op, not an error", result.advanced === false && result.reason === "ALREADY_ADVANCED");
      check("E. advanceToNextRound(): the no-op did NOT create a Round 3 — currentRound is still 2", doc.currentRound === 2);
      check("E. advanceToNextRound(): the no-op did NOT bump version again", doc.version === 2);
    })

    .then(function () {
      seedMockMatch("match-adv-3", { cardLog: fiftyTwoRoundTaggedCardEntries(1) });
      signInAs("some-fabricated-uid");
      return MatchService.advanceToNextRound("match-adv-3", 1)
        .then(function () { check("F. advanceToNextRound(): a non-player uid unexpectedly succeeded — should have thrown PERMISSION_DENIED", false); })
        .catch(function (e) { check("F. advanceToNextRound(): a non-player uid is rejected with PERMISSION_DENIED", e.reason === "PERMISSION_DENIED"); });
    })

    // ════════════════════════════════════════════════════════════════
    // G — applyRemoteRoundTransition(): the read-side counterpart —
    // detects a Firestore currentRound bump and drives the REAL
    // GameSession.nextRound() + BiddingEngine.initState().
    // ════════════════════════════════════════════════════════════════
    .then(function () {
      driveBiddingToCommittedRound(); // real BiddingEngine, round 1, DONE
      var roundBefore = GameSession.getRound().number;
      var result = MatchAdapter.applyRemoteRoundTransition("match-g", { currentRound: roundBefore + 1 });
      check("G. applyRemoteRoundTransition(): applied:true when the document's currentRound is ahead of the local round", result.applied === true);
      check("G. applyRemoteRoundTransition(): GameSession.getRound().number advanced by exactly 1", GameSession.getRound().number === roundBefore + 1);
      check("G. applyRemoteRoundTransition(): BiddingEngine was re-initialized for the new round (subPhase back to DASH, not stuck at DONE)", BiddingEngine.getState().subPhase === "DASH");
      check("G. applyRemoteRoundTransition(): BiddingEngine.getState().round reflects the NEW round number", BiddingEngine.getState().round === roundBefore + 1);

      var again = MatchAdapter.applyRemoteRoundTransition("match-g", { currentRound: roundBefore + 1 });
      check("G. applyRemoteRoundTransition(): a repeat delivery of the SAME currentRound is a harmless no-op (NO_NEW_ROUND)", again.applied === false && again.reason === "NO_NEW_ROUND");
    })

    // ════════════════════════════════════════════════════════════════
    // H — applyRemoteBiddingAction(): a Round 2 entry arriving before
    // THIS client has locally transitioned is deferred, never
    // misapplied against a still-Round-1 BiddingEngine, and never
    // silently lost once the client does catch up (Section 1's own
    // "Client A / Client B" race — see match-adapter.js's own comment).
    // ════════════════════════════════════════════════════════════════
    .then(function () {
      driveBiddingToCommittedRound(); // real BiddingEngine, round 1, DONE — never locally transitioned
      var matchId = "match-h";
      // Now this client catches up locally FIRST (so we know which seat
      // Round 2's fresh DASH phase will actually be waiting on) — the
      // deferral check right below still exercises the real "not yet
      // caught up" path by asking the ADAPTER's round-tag filter to
      // defer, which reads TableEngine/BiddingEngine's round only (never
      // this seat identity), so computing the seat afterward doesn't
      // weaken what's under test.
      MatchAdapter.applyRemoteRoundTransition(matchId, { currentRound: 2 });
      var round2Seat = BiddingEngine.getState().waitingFor;
      check("H setup: BiddingEngine is now genuinely on Round 2", BiddingEngine.getState().round === 2);
      // Roll the local engine back to simulate NOT having caught up yet
      // (a lagging client), to exercise the deferral itself.
      driveBiddingToCommittedRound();

      var doc = { version: 1, biddingLog: [{ seatId: round2Seat, actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 2 }] };
      var deferred = MatchAdapter.applyRemoteBiddingAction(matchId, doc);
      check("H. applyRemoteBiddingAction(): a Round 2 entry is DEFERRED (not desynced, not silently consumed) while the local engine is still on Round 1", deferred.applied === false && deferred.desync === false && deferred.reason === "AWAITING_ROUND_TRANSITION");
      check("H. applyRemoteBiddingAction(): the count registry did NOT advance past the deferred entry", MatchAdapter.getLastAppliedBiddingActionCount(matchId) === 0);

      // Now this client catches up locally for real...
      MatchAdapter.applyRemoteRoundTransition(matchId, { currentRound: 2 });
      check("H. applyRemoteRoundTransition(): BiddingEngine is genuinely on Round 2 again after catch-up", BiddingEngine.getState().round === 2);

      // ...and the SAME delivery (same doc, same version) is re-attempted —
      // this time it applies for real, proving the entry was never lost.
      var applied = MatchAdapter.applyRemoteBiddingAction(matchId, doc);
      check("H. applyRemoteBiddingAction(): the SAME Round 2 entry is APPLIED once the local engine has caught up — nothing was permanently lost", applied.applied === true && applied.appliedCount === 1);
    })

    // ════════════════════════════════════════════════════════════════
    // I — the identical AWAITING_ROUND_TRANSITION deferral for
    // applyRemoteCard(), gated on TableEngine's own round instead of
    // BiddingEngine's.
    // ════════════════════════════════════════════════════════════════
    .then(function () {
      driveBiddingToCommittedRound();
      TableEngine.initState(); // real TableEngine, round 1
      var matchId = "match-i";
      var localRound = TableEngine.getState().round;
      var doc = { version: 1, cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 10, s: "10" } }, round: localRound + 1 }] };
      var deferred = MatchAdapter.applyRemoteCard(matchId, doc);
      check("I. applyRemoteCard(): an entry tagged for the NEXT round is DEFERRED while TableEngine is still on the current round", deferred.applied === false && deferred.desync === false && deferred.reason === "AWAITING_ROUND_TRANSITION");
      check("I. applyRemoteCard(): the count registry did NOT advance past the deferred entry", MatchAdapter.getLastAppliedCardCount(matchId) === 0);
    })

    // ════════════════════════════════════════════════════════════════
    // J — synchronization: startRoundSync() shares the SAME single
    // Firestore listener as every other start*Sync() function.
    // ════════════════════════════════════════════════════════════════
    .then(function () {
      seedMockMatch("match-j");
      var unsub1 = MatchAdapter.startRoundSync("match-j");
      var unsub2 = MatchAdapter.startBiddingActionSync("match-j");
      var unsub3 = MatchAdapter.startCardSync("match-j");
      check("J. Synchronization: startRoundSync() + startBiddingActionSync() + startCardSync() for the SAME matchId still register exactly ONE Firestore listener", ONSNAPSHOT_CALLS[key("match-j")] === 1);
      unsub1(); unsub2(); unsub3();
    })

    .then(function () {
      console.log("\n" + pass + " passed, " + fail + " failed");
      process.exitCode = fail ? 1 : 0;
    })
    .catch(function (e) {
      console.error("UNCAUGHT TEST ERROR:", e);
      process.exitCode = 1;
    });
}
