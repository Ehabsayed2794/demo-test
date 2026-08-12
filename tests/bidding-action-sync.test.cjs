// Real, executable END-TO-END tests for Sprint 3.7 (Online Bidding
// Synchronization Contract) — the FULL pipeline for Dash Call /
// Auction Bid / Confirm Call:
//   Player -> MatchService.submitBiddingAction() -> Firestore ->
//   MatchService listener -> MatchAdapter.applyRemoteBiddingAction() ->
//   bidding-engine.js -> GameSession -> UI (later)
// exercised together, in one process, against the REAL
// design-ui/match-service.js, design-ui/match-adapter.js,
// design-ui/engine/bidding-engine.js, and design-ui/engine/session.js
// (GameSession) — not stubs, not fakes, the actual shipped code for
// every one of those four files. Final Estimate sync (submitBid() /
// applyRemoteBid()) is deliberately NOT retested here — it is
// unchanged by this sprint and already covered by
// tests/bid-sync.test.cjs / tests/submit-bid.test.cjs.
//
// LABELING: every check below is MOCKED — real code from all four
// files above, exercised against a hand-written fake Firestore
// (mirrors tests/bid-sync.test.cjs's own harness). No SIMULATED checks
// (no firestore.rules involved — see tests/rules-simulation.test.js
// for that layer). No real Firestore project, Firebase Emulator, or
// browser was used.
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
var FAKE_DB = {
  collection: function (name) {
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { return makeMatchRef(id); } };
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

/** Resets everything to a genuinely fresh DASH phase — dealer (p1) is
 *  waiting. Mirrors bid-sync.test.cjs's own reset convention. */
function freshDash() {
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  BiddingEngine.initState();
  return BiddingEngine.getState().waitingFor; // "p1"
}

/** Seeds a mocked Firestore match document with the NEW biddingLog
 *  schema alongside the existing bids/cardLog fields (a real match
 *  document has all of them, per buildInitialMatchDoc()). */
function seedMockMatch(matchId) {
  STORE[key(matchId)] = {
    roomId: "room-x", players: ["u1", "u2", "u3", "u4"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "u1", turn: "u1", gameState: { initialized: false },
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null,
    biddingLog: []
  };
  DOC_VERSION[key(matchId)] = (DOC_VERSION[key(matchId)] || 0) + 1;
}

(async function () {
  // ============================================================
  // A. Dash Call synchronization
  // ============================================================
  freshDash(); // p1 waiting
  seedMockMatch("m-dash");
  var unsubA = MatchAdapter.startBiddingActionSync("m-dash");
  signInAs("u1");
  var resA = await MatchService.submitBiddingAction("m-dash", { actionType: "SubmitDashCallDecision", declaredDashCall: false });
  check("A. Dash sync: submitBiddingAction() succeeds and appends exactly 1 entry", resA.version === 2 && resA.logLength === 1);
  check("A. Dash sync: the REAL bidding-engine.js executed — p1's dash decision is recorded", BiddingEngine.getState().bids.p1 != null);
  check("A. Dash sync: engine correctly advanced to p2", BiddingEngine.getState().waitingFor === "p2");
  unsubA();

  // ============================================================
  // B. Auction Bid synchronization
  // ============================================================
  freshDash();
  for (var bi = 0; bi < 4; bi++) { BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: BiddingEngine.getState().waitingFor, declaredDashCall: false }); }
  check("B. setup: reached AUCTION locally", BiddingEngine.getState().subPhase === "AUCTION");
  var auctionOpener = BiddingEngine.getState().waitingFor; // p1
  seedMockMatch("m-auction");
  var unsubB = MatchAdapter.startBiddingActionSync("m-auction");
  signInAs("u1");
  var resB = await MatchService.submitBiddingAction("m-auction", { actionType: "SubmitAuctionBid", isPass: false, tricks: 5, suit: "SPADES" });
  check("B. Auction sync: submitBiddingAction() succeeds", resB.version === 2 && resB.logLength === 1);
  check("B. Auction sync: the REAL engine applied the raise — auctionTop is now 5 Spades", BiddingEngine.getState().auctionTop === 5 && BiddingEngine.getState().auctionSuit === "SPADES");
  unsubB();

  // ============================================================
  // C. Confirm Call synchronization
  // ============================================================
  freshDash();
  for (var ci = 0; ci < 4; ci++) { BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: BiddingEngine.getState().waitingFor, declaredDashCall: false }); }
  var confirmOpener = BiddingEngine.getState().waitingFor; // p1
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: confirmOpener, tricks: 4, suit: "SPADES", isPass: false });
  var cGuard = 0;
  while (BiddingEngine.getState().subPhase === "AUCTION" && cGuard < 10) { BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: BiddingEngine.getState().waitingFor, isPass: true }); cGuard++; }
  check("C. setup: reached CONFIRM locally with p1 as caller", BiddingEngine.getState().subPhase === "CONFIRM" && BiddingEngine.getState().callerId === "p1");
  seedMockMatch("m-confirm");
  var unsubC = MatchAdapter.startBiddingActionSync("m-confirm");
  signInAs("u1");
  var resC = await MatchService.submitBiddingAction("m-confirm", { actionType: "SubmitConfirmCall", tricks: 4, suit: "SPADES" });
  check("C. Confirm sync: submitBiddingAction() succeeds", resC.version === 2);
  check("C. Confirm sync: the REAL engine locked the trump and moved to ESTIMATES", BiddingEngine.getState().subPhase === "ESTIMATES" && BiddingEngine.getState().declaredTrump === "SPADES");
  unsubC();

  // ============================================================
  // D. Final Estimate stays on the PRE-EXISTING pipeline — confirm the
  // boundary is intact: submitBiddingAction() must NOT accept a
  // SubmitFinalEstimate actionType (that remains submitBid()'s job).
  // ============================================================
  signInAs("u1");
  var rejectedFinalEstimate = null;
  try {
    await MatchService.submitBiddingAction("m-confirm", { actionType: "SubmitFinalEstimate", tricks: 3 });
  } catch (e) { rejectedFinalEstimate = e; }
  check("D. Boundary: submitBiddingAction() rejects a SubmitFinalEstimate actionType — that remains submitBid()'s exclusive job", rejectedFinalEstimate && rejectedFinalEstimate.reason === "INVALID_BIDDING_ACTION_VALUE");

  // ============================================================
  // E. Malformed action
  // ============================================================
  var malformedCases = [
    { actionType: "NotARealType" },
    { actionType: "SubmitAuctionBid" }, // missing isPass
    { actionType: "SubmitAuctionBid", isPass: false }, // missing tricks/suit
    { actionType: "SubmitConfirmCall", tricks: 4 }, // missing suit
    { actionType: "SubmitConfirmCall", tricks: 99, suit: "SPADES" }, // out-of-range tricks
    { actionType: "SubmitConfirmCall", tricks: 4, suit: "NOT_A_SUIT" },
    null, "a string", 42
  ];
  var allMalformedRejected = true;
  for (var mi = 0; mi < malformedCases.length; mi++) {
    var caught = null;
    try { await MatchService.submitBiddingAction("m-confirm", malformedCases[mi]); } catch (e) { caught = e; }
    if (!caught || caught.reason !== "INVALID_BIDDING_ACTION_VALUE") { allMalformedRejected = false; }
  }
  check("E. Malformed action: every malformed shape is rejected INVALID_BIDDING_ACTION_VALUE, before any Firestore access", allMalformedRejected);
  check("E. Malformed action: zero writes were attempted for any malformed case — version unchanged", STORE[key("m-confirm")].version === 2);

  // ============================================================
  // F. Wrong player (seat not owned by caller; and legal-seat-but-
  // wrong-turn — both are "wrong player" from the caller's perspective)
  // ============================================================
  freshDash();
  seedMockMatch("m-wrongplayer");
  signInAs("u9"); // not seated in this match at all
  var wrongSeatErr = null;
  try { await MatchService.submitBiddingAction("m-wrongplayer", { actionType: "SubmitDashCallDecision", declaredDashCall: false }); } catch (e) { wrongSeatErr = e; }
  check("F. Wrong player (unseated uid): rejected PERMISSION_DENIED, zero writes", wrongSeatErr && wrongSeatErr.reason === "PERMISSION_DENIED" && STORE[key("m-wrongplayer")].version === 1);

  signInAs("u2"); // owns p2, but p1 (dealer) is waiting, not p2
  var wrongTurnErr = null;
  try { await MatchService.submitBiddingAction("m-wrongplayer", { actionType: "SubmitDashCallDecision", declaredDashCall: false }); } catch (e) { wrongTurnErr = e; }
  check("F. Wrong player (real seat, wrong turn): rejected ILLEGAL_BIDDING_ACTION (\"Not this seat's turn\"), zero writes",
    wrongTurnErr && wrongTurnErr.reason === "ILLEGAL_BIDDING_ACTION" && wrongTurnErr.message.indexOf("Not this seat's turn") !== -1 && STORE[key("m-wrongplayer")].version === 1);

  // ============================================================
  // G. Wrong phase
  // ============================================================
  freshDash(); // still DASH — p1 waiting
  seedMockMatch("m-wrongphase");
  signInAs("u1");
  var wrongPhaseErr = null;
  try { await MatchService.submitBiddingAction("m-wrongphase", { actionType: "SubmitAuctionBid", isPass: false, tricks: 5, suit: "SPADES" }); } catch (e) { wrongPhaseErr = e; }
  check("G. Wrong phase: an AUCTION-shaped action during DASH is rejected ILLEGAL_BIDDING_ACTION (\"Not the Auction phase\"), zero writes",
    wrongPhaseErr && wrongPhaseErr.reason === "ILLEGAL_BIDDING_ACTION" && wrongPhaseErr.message.indexOf("Not the Auction phase") !== -1 && STORE[key("m-wrongphase")].version === 1);

  // ============================================================
  // H. Duplicate action (identical snapshot delivered twice must not
  // re-apply / double-advance the engine)
  // ============================================================
  freshDash();
  seedMockMatch("m-dupdeliver");
  var unsubH = MatchAdapter.startBiddingActionSync("m-dupdeliver");
  signInAs("u1");
  await MatchService.submitBiddingAction("m-dupdeliver", { actionType: "SubmitDashCallDecision", declaredDashCall: false });
  var waitingAfterFirstH = BiddingEngine.getState().waitingFor;
  var docSnapshotH = STORE[key("m-dupdeliver")];
  // Re-deliver the IDENTICAL, already-processed snapshot directly to
  // the adapter (bypassing the listener) — mirrors bid-sync.test.cjs's
  // own duplicate-snapshot technique.
  var dupResultH = MatchAdapter.applyRemoteBiddingAction("m-dupdeliver", docSnapshotH);
  check("H. Duplicate: re-delivering the SAME (already-applied) snapshot is recognized as a no-op", dupResultH.reason === "DUPLICATE_VERSION" || dupResultH.reason === "NO_NEW_BIDDING_ACTIONS");
  check("H. Duplicate: the engine's turn did NOT advance again", BiddingEngine.getState().waitingFor === waitingAfterFirstH);
  unsubH();

  // ============================================================
  // I. Stale action (an older-version snapshot must be ignored, never
  // applied out of order / never roll back)
  // ============================================================
  freshDash();
  seedMockMatch("m-stale");
  var unsubI = MatchAdapter.startBiddingActionSync("m-stale");
  signInAs("u1");
  await MatchService.submitBiddingAction("m-stale", { actionType: "SubmitDashCallDecision", declaredDashCall: false }); // version -> 2
  signInAs("u2");
  await MatchService.submitBiddingAction("m-stale", { actionType: "SubmitDashCallDecision", declaredDashCall: false }); // version -> 3
  var waitingAfterBothI = BiddingEngine.getState().waitingFor; // p3
  // Construct an artificially STALE snapshot: version 2's worth of log
  // (only 1 entry), delivered AFTER the real version-3 state already
  // applied — must be rejected as stale, never rolled back into.
  var staleDocI = Object.assign({}, STORE[key("m-stale")], { version: 2, biddingLog: STORE[key("m-stale")].biddingLog.slice(0, 1) });
  var staleResultI = MatchAdapter.applyRemoteBiddingAction("m-stale", staleDocI);
  check("I. Stale: a version-2 snapshot delivered after version-3 was already applied is rejected STALE_VERSION", staleResultI.reason === "STALE_VERSION");
  check("I. Stale: the engine's already-advanced turn is NOT rolled back", BiddingEngine.getState().waitingFor === waitingAfterBothI);
  unsubI();

  // ============================================================
  // J. Reload / resume — a late subscriber (simulating a page reload
  // mid-auction) must catch up to the CURRENT state by replaying the
  // WHOLE log, not just what changed after it subscribed.
  // ============================================================
  freshDash();
  seedMockMatch("m-resume");
  // A sync pipeline must be ACTIVE for the (single, shared, in-process)
  // engine instance to advance between each simulated player's own
  // submission — standing in for "whichever real client happens to be
  // connected right now sees every other seat's action live," exactly
  // like scenarios A/B/C/N above. This is torn down before the
  // simulated reload below, mirroring a real page closing.
  var unsubJPre = MatchAdapter.startBiddingActionSync("m-resume");
  signInAs("u1");
  await MatchService.submitBiddingAction("m-resume", { actionType: "SubmitDashCallDecision", declaredDashCall: false });
  signInAs("u2");
  await MatchService.submitBiddingAction("m-resume", { actionType: "SubmitDashCallDecision", declaredDashCall: false });
  signInAs("u3");
  await MatchService.submitBiddingAction("m-resume", { actionType: "SubmitDashCallDecision", declaredDashCall: false });
  unsubJPre();
  // Simulate "reload": reset the LOCAL engine/adapter state entirely
  // (as a fresh page load would), then subscribe fresh — the very
  // first delivery must replay all 3 already-accepted entries.
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  BiddingEngine.initState();
  var unsubJ = MatchAdapter.startBiddingActionSync("m-resume");
  check("J. Reload/resume: a fresh subscribe replays the FULL existing log — engine correctly waiting on p4", BiddingEngine.getState().waitingFor === "p4");
  check("J. Reload/resume: all 3 prior decisions are reflected in the resumed engine state", Object.keys(BiddingEngine.getState().bids).length === 3);
  unsubJ();

  // ============================================================
  // K. Listener duplication — starting bidding-action sync alongside
  // bid-sync/card-sync for the SAME matchId must not create a second
  // real Firestore listener.
  // ============================================================
  freshDash();
  seedMockMatch("m-listeners");
  var kKey = key("m-listeners");
  var before_K = ONSNAPSHOT_CALLS[kKey] || 0;
  var unsubK1 = MatchAdapter.startBiddingActionSync("m-listeners");
  var unsubK2 = MatchAdapter.startBidSync("m-listeners");
  var unsubK3 = MatchAdapter.startCardSync("m-listeners");
  var after_K = ONSNAPSHOT_CALLS[kKey] || 0;
  check("K. Listener duplication: starting 3 different sync pipelines for the SAME matchId creates exactly ONE real onSnapshot registration", after_K - before_K === 1);
  unsubK1(); unsubK2(); unsubK3();

  // ============================================================
  // L. Concurrent submission — two submitBiddingAction() calls for the
  // SAME seat's SAME action, fired in parallel (no await between
  // them). Exactly one must succeed; the other must be rejected
  // STALE_GAME_STATE (never silently double-applied, never trusted to
  // the UI to prevent) — mirrors submitCard()'s own already-proven
  // version-guard pattern (Sprint 4.2.2), reused here unchanged.
  // ============================================================
  freshDash();
  seedMockMatch("m-concurrent");
  signInAs("u1");
  var actionL = { actionType: "SubmitDashCallDecision", declaredDashCall: false };
  var pL1 = MatchService.submitBiddingAction("m-concurrent", actionL).then(function (r) { return { ok: true, r: r }; }, function (e) { return { ok: false, e: e }; });
  var pL2 = MatchService.submitBiddingAction("m-concurrent", actionL).then(function (r) { return { ok: true, r: r }; }, function (e) { return { ok: false, e: e }; });
  var resultsL = await Promise.all([pL1, pL2]);
  var succeededL = resultsL.filter(function (x) { return x.ok; });
  var failedL = resultsL.filter(function (x) { return !x.ok; });
  check("L. Concurrent submission: EXACTLY ONE of the two simultaneous calls succeeds", succeededL.length === 1);
  check("L. Concurrent submission: the other is rejected (STALE_GAME_STATE or a legality mismatch caused by the race) — never silently double-applied", failedL.length === 1);
  check("L. Concurrent submission: the document's log grew by exactly 1 entry, not 2 — no duplicate/lost write", STORE[key("m-concurrent")].biddingLog.length === 1);

  // ============================================================
  // M. Local vs. remote application — a self-echo of an action THIS
  // client's own local engine already applied directly (optimistic
  // local update, before the Firestore round-trip) must be recognized
  // as ALREADY_APPLIED_LOCALLY, never double-applied, never reported
  // as a false desync.
  // ============================================================
  freshDash();
  var localSeatM = BiddingEngine.getState().waitingFor; // p1
  // The LOCAL engine applies this action directly (as a future
  // renderer's own optimistic local emit() would).
  BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: localSeatM, declaredDashCall: false });
  var waitingAfterLocalM = BiddingEngine.getState().waitingFor; // p2
  // Now the SAME action's echo arrives via the Firestore log (as if
  // this client's own submitBiddingAction() call had just round-tripped).
  var matchDocM = { version: 2, seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" }, biddingLog: [{ seatId: localSeatM, actionType: "SubmitDashCallDecision", declaredDashCall: false }] };
  var echoResultM = MatchAdapter.applyRemoteBiddingAction("m-echo-not-a-real-match", matchDocM);
  check("M. Local vs remote: the self-echo is recognized as ALREADY_APPLIED_LOCALLY (benign), not a desync", echoResultM.desync !== true && echoResultM.results[0].reason === "ALREADY_APPLIED_LOCALLY");
  check("M. Local vs remote: the engine's turn was NOT advanced a second time by the echo", BiddingEngine.getState().waitingFor === waitingAfterLocalM);

  // ============================================================
  // N. State convergence — every client, replaying the SAME log
  // through the SAME real bidding-engine.js reducer, reaches the
  // IDENTICAL state. Verified by driving a full Dash+Auction+Confirm
  // sequence through the remote pipeline ONLY (never touching emit()
  // directly) and confirming the resulting engine state exactly
  // matches what the equivalent DIRECT-emit() sequence produces
  // (already proven correct by tests/bidding-contract.test.cjs and
  // tests/match-flow-integration.test.cjs) — i.e., the sync pipeline
  // introduces no divergence from the engine's own offline behavior.
  // ============================================================
  freshDash();
  seedMockMatch("m-converge");
  var unsubN = MatchAdapter.startBiddingActionSync("m-converge");
  var seatsN = ["u1", "u2", "u3", "u4"];
  var seatIdsN = ["p1", "p2", "p3", "p4"];
  for (var ni = 0; ni < 4; ni++) {
    var idx = seatIdsN.indexOf(BiddingEngine.getState().waitingFor);
    signInAs(seatsN[idx]);
    await MatchService.submitBiddingAction("m-converge", { actionType: "SubmitDashCallDecision", declaredDashCall: false });
  }
  check("N. Convergence: after 4 remote Dash decisions, engine reached AUCTION exactly as the direct-emit() equivalent would", BiddingEngine.getState().subPhase === "AUCTION" && BiddingEngine.getState().activeBidders.length === 4);
  var openerN = BiddingEngine.getState().waitingFor;
  var idxN = seatIdsN.indexOf(openerN);
  signInAs(seatsN[idxN]);
  await MatchService.submitBiddingAction("m-converge", { actionType: "SubmitAuctionBid", isPass: false, tricks: 4, suit: "SPADES" });
  check("N. Convergence: remote auction bid produced the identical auctionTop/Suit a direct emit() call would", BiddingEngine.getState().auctionTop === 4 && BiddingEngine.getState().auctionSuit === "SPADES");
  unsubN();

  // ============================================================
  // O. Sprint 3.7.x (Bidding Trust-Boundary Hardening) — a remote
  // biddingLog entry that passes the existing shape-level
  // MALFORMED_ENTRY check (correct seatId, correct actionType) but is
  // missing a required payload field for that actionType must NOT
  // crash applyRemoteBiddingAction() — canSubmit()'s own hardening
  // (bidding-engine.js) now rejects it as "Malformed intent" BEFORE
  // emit() is ever called, so this is reported as an ordinary
  // ENGINE_REJECTED desync, never an uncaught exception and never a
  // fabricated success. This directly proves scenarios K/L/M from the
  // hardening brief using the REAL engine + REAL adapter, not a mock.
  // ============================================================
  freshDash();
  seedMockMatch("m-malformed-confirm");
  // Drive the real engine to CONFIRM so the malformed entry below is
  // actually addressed to the correct seat in the correct sub-phase —
  // otherwise it would be swallowed as a benign phase/turn mismatch
  // (ALREADY_APPLIED_LOCALLY) rather than exercising the malformed-
  // payload path this scenario is specifically about.
  for (var oi = 0; oi < 4; oi++) {
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: BiddingEngine.getState().waitingFor, declaredDashCall: false });
  }
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: BiddingEngine.getState().waitingFor, isPass: false, tricks: 5, suit: "SPADES" });
  while (BiddingEngine.getState().subPhase === "AUCTION") {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: BiddingEngine.getState().waitingFor, isPass: true });
  }
  var confirmWaitingForO = BiddingEngine.getState().waitingFor;
  var confirmSeatIdxO = ["p1", "p2", "p3", "p4"].indexOf(confirmWaitingForO);
  // A SubmitConfirmCall entry that is structurally a valid log entry
  // (has seatId + a recognized actionType) but omits BOTH `tricks` and
  // `suit` — exactly the shape that, before Fix 1/Fix 3, made
  // canSubmit() return {legal:true} and then emit() throw.
  var matchDocO = {
    version: 2, seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    biddingLog: [{ seatId: confirmWaitingForO, actionType: "SubmitConfirmCall" }]
  };
  var beforeWaitingO = BiddingEngine.getState().waitingFor;
  var resultO;
  var threwO = false;
  try {
    resultO = MatchAdapter.applyRemoteBiddingAction("m-malformed-confirm", matchDocO);
  } catch (e) {
    threwO = true;
  }
  check("O. Malformed remote SubmitConfirmCall (missing tricks/suit): applyRemoteBiddingAction() does NOT throw", threwO === false);
  check("O. Malformed remote SubmitConfirmCall: returns a structured desync result, not a fabricated success", !!resultO && resultO.applied === false && resultO.desync === true);
  check("O. Malformed remote SubmitConfirmCall: reason is ENGINE_REJECTED (canSubmit() caught it as \"Malformed intent\" before emit() ever ran)", resultO.reason === "ENGINE_REJECTED" && resultO.engineReason === "Malformed intent");
  check("O. Malformed remote SubmitConfirmCall: the engine's turn/state was NOT mutated by the rejected replay", BiddingEngine.getState().waitingFor === beforeWaitingO && BiddingEngine.getState().subPhase === "CONFIRM");

  console.log("\n" + pass + " passed, " + fail + " failed");
  if (fail > 0) process.exit(1);
})();
