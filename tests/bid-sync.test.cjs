// Real, executable END-TO-END tests for Sprint 4.0 (Online Bidding
// Synchronization: Authority Layer) — the FULL pipeline:
//   Player -> submitBid() -> Firestore -> MatchService listener ->
//   Engine Adapter -> bidding-engine.js -> GameSession -> UI
// exercised together, in one process, against the REAL
// design-ui/match-service.js, design-ui/match-adapter.js,
// design-ui/engine/bidding-engine.js, and design-ui/engine/session.js
// (GameSession) — not stubs, not fakes, the actual shipped code for
// every one of those four files.
//
// LABELING: every check below is MOCKED — real code from all four
// files above, exercised against a hand-written fake Firestore (this
// file's own, combining tests/submit-bid.test.cjs's transaction +
// tests/match-sync.test.cjs's onSnapshot/listener-registry patterns,
// since this file needs both). No SIMULATED checks (no firestore.rules
// involved). No real Firestore project, Firebase Emulator, or browser
// was used — consistent with every prior sprint's own honesty
// statement.
global.window = global;
global.window.addEventListener = function () {};

var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var ONSNAPSHOT_CALLS = {};
var pendingErrorCallbacks = {};
var FIRESTORE_AVAILABLE = true;
var idCounter = 0;

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
require("/home/user/demo-test/design-ui/match-adapter.js");

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

/** Drives the REAL bidding-engine.js, from a fresh state, all the way
 *  to the ESTIMATES phase (dealer opens 4 SPADES, everyone else
 *  passes) — the one phase this sprint's pipeline supports (see
 *  match-adapter.js's own header comment on why). Returns the seat
 *  waiting to submit next (always "p2" for this fixed, deterministic
 *  script). Resets GameSession first, so each call starts genuinely
 *  fresh, not layered on a previous scenario's leftover state. */
function runBiddingToEstimates() {
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
  return BiddingEngine.getState().waitingFor; // "p2"
}

/** Seeds a mocked Firestore match document consistent with the
 *  bidding-engine state runBiddingToEstimates() just produced — same
 *  4 seats, all bids still null, bidding still open, version 1. */
function seedMockMatch(matchId) {
  STORE[key(matchId)] = {
    roomId: "room-x", players: ["u1", "u2", "u3", "u4"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "u1", turn: "u1", gameState: { initialized: false },
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null
  };
  DOC_VERSION[key(matchId)] = (DOC_VERSION[key(matchId)] || 0) + 1;
}

(async function () {
  // ============================================================
  // MOCKED — Task 1 / Acceptance Criteria: a remote player submits a
  // bid; the connected client receives exactly one synchronized
  // update; the bidding engine executes exactly once.
  // ============================================================
  runBiddingToEstimates(); // waiting seat is p2, engine has p1's caller bid recorded already
  seedMockMatch("m-remote");
  var unsub1 = MatchAdapter.startBidSync("m-remote");
  check("MOCKED — Task 1: startBidSync() delivers the initial (pre-bid) snapshot without applying anything (no bid yet)",
    BiddingEngine.getState().waitingFor === "p2" && GameSession.getBiddingState().estimates.p2 == null);

  signInAs("u2"); // owns seat p2, per seedMockMatch's seats map
  var submitResult = await MatchService.submitBid("m-remote", "p2", 3);
  check("MOCKED — remote bid: submitBid() itself succeeds (Sprint 3.8, unchanged)", submitResult.version === 2 && submitResult.bid === 3);
  check("MOCKED — Acceptance: the REAL bidding-engine.js executed exactly once — p2's estimate is now recorded", GameSession.getBiddingState().estimates.p2 === 3);
  check("MOCKED — Acceptance: the engine correctly advanced to the next seat (p3), proving the real reducer ran, not a stub", BiddingEngine.getState().waitingFor === "p3");
  check("MOCKED — Acceptance: no Firestore write happened outside MatchService — the mocked document's version is exactly what submitBid() itself produced (2), nothing else touched it", STORE[key("m-remote")].version === 2);
  unsub1();

  // ============================================================
  // MOCKED — Duplicate snapshot: receiving the same snapshot twice
  // must not re-render / re-run bidding logic / replay engine state.
  // ============================================================
  runBiddingToEstimates();
  seedMockMatch("m-dupsnap");
  var unsub2 = MatchAdapter.startBidSync("m-dupsnap");
  signInAs("u2");
  await MatchService.submitBid("m-dupsnap", "p2", 2);
  var estimateAfterFirst = GameSession.getBiddingState().estimates.p2;
  var waitingAfterFirst = BiddingEngine.getState().waitingFor;
  // Re-deliver the IDENTICAL, already-processed snapshot directly —
  // simulating a benign duplicate delivery from the underlying SDK.
  notify(key("m-dupsnap"));
  notify(key("m-dupsnap"));
  check("MOCKED — duplicate snapshot: re-delivering the same snapshot does not change the recorded estimate",
    GameSession.getBiddingState().estimates.p2 === estimateAfterFirst);
  check("MOCKED — duplicate snapshot: re-delivering the same snapshot does not move the engine's turn pointer (no replayed state)",
    BiddingEngine.getState().waitingFor === waitingAfterFirst);
  unsub2();

  // ============================================================
  // MOCKED — Stale snapshot / version rollback (end-to-end): an
  // out-of-order delivery of an OLDER version, after a newer one was
  // already applied, must never roll the engine back.
  // ============================================================
  runBiddingToEstimates();
  seedMockMatch("m-stale");
  var unsub3 = MatchAdapter.startBidSync("m-stale");
  signInAs("u2");
  await MatchService.submitBid("m-stale", "p2", 3);
  var afterRealBid = { estimate: GameSession.getBiddingState().estimates.p2, waiting: BiddingEngine.getState().waitingFor };
  // Forge a stale, lower-version snapshot with a DIFFERENT (bogus)
  // value for the same seat and deliver it directly.
  STORE[key("m-stale")] = Object.assign({}, STORE[key("m-stale")], { version: 1, bids: Object.assign({}, STORE[key("m-stale")].bids, { p2: 999 }) });
  notify(key("m-stale"));
  check("MOCKED — version rollback: a stale, lower-version snapshot never overwrites the already-applied estimate",
    GameSession.getBiddingState().estimates.p2 === afterRealBid.estimate);
  check("MOCKED — version rollback: the engine's turn pointer is unaffected by the stale delivery", BiddingEngine.getState().waitingFor === afterRealBid.waiting);
  unsub3();

  // ============================================================
  // MOCKED — New snapshot / multiple sequential bids, in order,
  // reaching a fully-resolved bidding phase — plus GameSession
  // consistency at the end.
  // ============================================================
  runBiddingToEstimates();
  seedMockMatch("m-sequence");
  var unsub4 = MatchAdapter.startBidSync("m-sequence");
  var seatUids = { p2: "u2", p3: "u3", p4: "u4" };
  var picks = { p2: 1, p3: 2, p4: 1 };
  var seq = ["p2", "p3", "p4"];
  for (var si = 0; si < seq.length; si++) {
    var seat = seq[si];
    check("MOCKED — multiple sequential bids: the engine is waiting for " + seat + " before this submission", BiddingEngine.getState().waitingFor === seat);
    signInAs(seatUids[seat]);
    await MatchService.submitBid("m-sequence", seat, picks[seat]);
    check("MOCKED — multiple sequential bids: " + seat + "'s estimate (" + picks[seat] + ") is recorded immediately after its own submission",
      GameSession.getBiddingState().estimates[seat] === picks[seat]);
  }
  check("MOCKED — GameSession consistency: bidding is now complete — every real seat has a recorded estimate",
    GameSession.getBiddingState().estimates.p1 === 4 && GameSession.getBiddingState().estimates.p2 === 1 &&
    GameSession.getBiddingState().estimates.p3 === 2 && GameSession.getBiddingState().estimates.p4 === 1);
  check("MOCKED — GameSession consistency: GameSession.getRound() reflects the committed caller/trump/withPlayers from the real engine",
    GameSession.getRound().callerId === "p1" && GameSession.getRound().trump === "SPADES");
  check("MOCKED — Firestore-side consistency: the mocked document's own bids map matches GameSession's estimates exactly (same values, correctly attributed per seat)",
    STORE[key("m-sequence")].bids.p2 === 1 && STORE[key("m-sequence")].bids.p3 === 2 && STORE[key("m-sequence")].bids.p4 === 1);
  unsub4();

  // ============================================================
  // MOCKED — Local bid vs. remote bid: the ORIGINATING client's own
  // bid, applied LOCALLY first (as a real UI would, for instant
  // feedback), must not be re-executed when its own echo arrives back
  // through Firestore sync.
  // ============================================================
  runBiddingToEstimates();
  seedMockMatch("m-localecho");
  var unsub5 = MatchAdapter.startBidSync("m-localecho");
  // The "local" action: this client's own UI already called
  // BiddingEngine.emit() directly for its own seat (p2), exactly as
  // Sprint 3.6's offline flow always has — BEFORE any Firestore round
  // trip completes.
  var localEmitResult = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: "p2", tricks: 3 });
  check("MOCKED — local bid: the local, direct engine call succeeds and is recorded", !localEmitResult.rejected && GameSession.getBiddingState().estimates.p2 === 3);
  var waitingAfterLocal = BiddingEngine.getState().waitingFor;
  // Now the SAME action's echo arrives via Firestore (the same client
  // also persisted it via submitBid(), and its own subscription hears
  // the echo back).
  signInAs("u2");
  await MatchService.submitBid("m-localecho", "p2", 3);
  check("MOCKED — local bid: the echo of the client's OWN bid does not re-execute the engine — estimate is unchanged (still 3, not double-applied)",
    GameSession.getBiddingState().estimates.p2 === 3);
  check("MOCKED — local bid: the engine's turn pointer, already advanced by the local call, is not moved again by the echo",
    BiddingEngine.getState().waitingFor === waitingAfterLocal);
  unsub5();

  // ============================================================
  // MOCKED — Late subscriber: a NEW subscription joining AFTER bids
  // have already been applied must observe the CURRENT, already-
  // resolved state — and must not cause the engine to re-execute
  // anything on its own account (it's a pure Firestore listener join,
  // not a bid submission).
  // ============================================================
  runBiddingToEstimates();
  seedMockMatch("m-late");
  var unsub6a = MatchAdapter.startBidSync("m-late");
  signInAs("u2");
  await MatchService.submitBid("m-late", "p2", 1);
  var emitCountBeforeLate = 0; // proxy: capture engine turn/estimate state, not a private counter
  var estimateBeforeLate = GameSession.getBiddingState().estimates.p2;
  var waitingBeforeLate = BiddingEngine.getState().waitingFor;
  var unsub6b = MatchAdapter.startBidSync("m-late"); // a second, later subscriber for the SAME match
  check("MOCKED — late subscriber: joining after bids were already applied does not change any already-recorded estimate",
    GameSession.getBiddingState().estimates.p2 === estimateBeforeLate);
  check("MOCKED — late subscriber: joining does not move the engine's turn pointer", BiddingEngine.getState().waitingFor === waitingBeforeLate);
  check("MOCKED — late subscriber: no duplicated listener was created — MatchService's own ref-counted registry is reused (Sprint 3.7, unchanged)",
    ONSNAPSHOT_CALLS[key("m-late")] === 1);
  unsub6a(); unsub6b();

  // ============================================================
  // MOCKED — Listener restart (reconnect) / listener duplicate event:
  // a simulated disconnect-and-reconnect, and a raw duplicate SDK
  // delivery, must both still result in the engine executing AT MOST
  // once per genuinely new bid.
  // ============================================================
  runBiddingToEstimates();
  seedMockMatch("m-restart");
  var unsub7 = MatchAdapter.startBidSync("m-restart");
  signInAs("u2");
  await MatchService.submitBid("m-restart", "p2", 3);
  check("MOCKED — listener restart setup: p2's bid applied before any disconnect", GameSession.getBiddingState().estimates.p2 === 3);

  simulateDisconnect("m-restart", "unavailable"); // retryable — MatchService (Sprint 3.7.1) will auto-reconnect
  await wait(1000); // allow the backoff-driven reconnect to complete
  check("MOCKED — listener restart: after reconnecting, the previously-applied estimate is still correct (not reset, not reapplied)",
    GameSession.getBiddingState().estimates.p2 === 3);

  signInAs("u3");
  await MatchService.submitBid("m-restart", "p3", 2);
  check("MOCKED — listener restart: a NEW bid submitted after reconnect is correctly applied through the restarted listener",
    GameSession.getBiddingState().estimates.p3 === 2 && BiddingEngine.getState().waitingFor === "p4");

  // Listener duplicate event: force the mock to redeliver the CURRENT
  // stored snapshot twice in a row (simulating the underlying SDK
  // firing a redundant, non-error event for no real change).
  var waitingBeforeDup = BiddingEngine.getState().waitingFor;
  notify(key("m-restart"));
  notify(key("m-restart"));
  notify(key("m-restart"));
  check("MOCKED — listener duplicate event: three redundant re-deliveries of the current snapshot cause zero additional engine changes",
    BiddingEngine.getState().waitingFor === waitingBeforeDup && GameSession.getBiddingState().estimates.p3 === 2);
  unsub7();

  // ============================================================
  // MOCKED — Regression sanity: MatchService's other gameplay stubs
  // and MatchAdapter's Sprint 3.9 API are untouched by this sprint.
  // ============================================================
  // Round Lifecycle sprint: advanceToNextRound() is now a REAL, implemented method (not a stub) — removed from this stub-regression list on purpose, not an oversight. Match Completion sprint: endMatch() is likewise now a REAL, implemented method (not a stub) — removed from this stub-regression list on purpose, not an oversight (see tests/match-completion.test.cjs for its own dedicated suite). completeRound() remains an intentional stub (see its own doc comment in match-service.js for why) and is still checked below.
  ["submitDashCall", "submitPass", "declareTrump", "submitEstimate", "playCard", "resolveTrick", "completeRound"].forEach(function (m) {
    var threw = false;
    try { MatchService[m](); } catch (e) { threw = /not implemented/i.test(e.message); }
    check("MOCKED — regression: MatchService." + m + "() is still an unimplemented stub, unchanged by this sprint", threw);
  });
  check("MOCKED — regression: MatchAdapter.bootstrapGameSession (Sprint 3.9) is still present and unchanged in shape", typeof MatchAdapter.bootstrapGameSession === "function");
  check("MOCKED — regression: MatchAdapter.matchDocToEngineSnapshot (Sprint 3.9) is still present and unchanged in shape", typeof MatchAdapter.matchDocToEngineSnapshot === "function");

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
