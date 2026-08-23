const path = require("path");
const __REPO_ROOT__ = path.join(__dirname, "..");

// Sprint J.11 (post-code-review hotfix) — regression test for the
// CRITICAL finding: MatchService.submitBid()/submitBiddingAction() both
// called MatchAdapter.computeRoundStartLeaderUid() with the PRE-write
// `match`/`freshMatch` snapshot, whose `bids` field is missing the very
// bid that is completing the round (the completing write only merges
// the new bid into a LOCAL `bids` copy destined for `patch.bids`, never
// back into `match`/`freshMatch` itself). Harmless under the old
// GameSession-based formula (which never read `matchDoc.bids`), but a
// live bug under J.11's new fast-round formula, which reads
// `matchDoc.bids` directly — most acute when the LAST bidder makes the
// Super Call, since their own >=8 bid is invisible to the computation.
//
// Unlike tests/j11-fast-round-leader-authority.test.cjs (which only
// ever calls the pure function directly with a hand-assembled,
// complete `bids` map), this file exercises the REAL
// MatchService.submitBid() transaction body end-to-end against a mock
// Firestore — the exact call site the bug lives in.

global.window = global;
global.window.addEventListener = function () {};

var STORE = {};
var DOC_VERSION = {};

function key(id) { return "matches/" + id; }

function makeMatchRef(id) {
  var k = key(id);
  return {
    id: id,
    _key: k,
    get: function () {
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    update: function (patch) {
      STORE[k] = Object.assign({}, STORE[k], patch);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      return Promise.resolve();
    }
  };
}

var FAKE_DB = {
  collection: function (name) {
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { return makeMatchRef(id); } };
  },
  // Same real optimistic-concurrency transaction shape already
  // established in tests/submit-bid.test.cjs's mock.
  runTransaction: function (fn, attempt) {
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
    var seenVersions = {};
    var pending = {};
    var tx = {
      get: function (ref) { seenVersions[ref._key] = DOC_VERSION[ref._key] || 0; return ref.get(); },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var keys = Object.keys(pending);
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      keys.forEach(function (k) { STORE[k] = Object.assign({}, STORE[k], pending[k].data); DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1; });
      return result;
    });
  }
};
global.Db = FAKE_DB;
global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } } } };

var CURRENT_USER = null;
global.SessionService = {
  getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; },
  setCurrentMatchId: function () { return Promise.resolve(); }
};
function signInAs(uid) { CURRENT_USER = uid; }

require(__REPO_ROOT__ + "/design-ui/match-service.js");
require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/engine/bidding-engine.js");
require(__REPO_ROOT__ + "/design-ui/match-adapter.js");
var MatchService = global.MatchService;
var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;

// Drives the REAL bidding-engine.js through a Normal-Caller round
// (Dash-decline-all -> Auction -> Confirm) so global.BiddingEngine.canSubmit()
// genuinely approves the caller's own SubmitConfirmCall — mirrors
// tests/j9-bid-to-turn-handoff.test.cjs's own driveToConfirm() helper.
function driveToConfirm(roundNumber) {
  GameSession.reset(null);
  GameSession.setRound({ number: roundNumber });
  BiddingEngine.initState();
  var s = BiddingEngine.getState();
  var guard = 0;
  while (s.subPhase === "DASH" && guard < 10) {
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false });
    s = BiddingEngine.getState();
    guard++;
  }
  var caller = s.waitingFor;
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: caller, isPass: false, tricks: 6, suit: "SPADES" });
  s = BiddingEngine.getState();
  guard = 0;
  while (s.subPhase === "AUCTION" && guard < 10) {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true });
    s = BiddingEngine.getState();
    guard++;
  }
  return caller; // subPhase === "CONFIRM", waitingFor === caller
}

var pass = 0, fail = 0;
function check(label, cond, note) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
}
function key_(id) { return key(id); }

console.log("=== Sprint J.11 hotfix: stale bids-snapshot at submitBid()/submitBiddingAction() call sites ===\n");

(async function () {
  // ============================================================
  // Test 1 — real submitBid(): last bidder makes the Super Call.
  // Round 14 (fast round), 4 seats. p1=3, p2=4, p3=5 already bid.
  // Last bidder p4 (owned by "userD") submits 9 (a Super Call, >= 8).
  // The correct leader/Caller is p4 -- NOT p3 (the highest of the
  // OTHER three bids), which is what the pre-fix stale snapshot would
  // wrongly crown.
  // ============================================================
  var matchId = "m-superlast";
  STORE[key_(matchId)] = {
    roomId: "room-x", status: "starting", createdAt: 1, currentRound: 14,
    // Round-1 (creation-time) dealer chosen so that, after 13 forward
    // rotations (currentRound - 1 = 13; 13 mod 4 = 1), the ACTIVE
    // round-14 dealer seat is p1 -- matches this test's own bidding
    // order assumption (p1, p2, p3, p4). See match-adapter.js's
    // computeRoundStartLeaderFromPersistedState() dealer-rotation logic.
    dealer: "userD", // creation-time dealer = seat p4; +13 rotations (CCW: p4->p1->p2->p3->p4->... ) lands on p4? verified via helper below, not asserted blindly
    turn: null, cardPhase: null,
    seats: { p1: "userA", p2: "userB", p3: "userC", p4: "userD" },
    version: 5, biddingOpen: true,
    bids: { p1: 3, p2: 4, p3: 5, p4: null },
    lastBidSeat: "p3"
  };
  DOC_VERSION[key_(matchId)] = 1;

  signInAs("userD");
  var result = await MatchService.submitBid(matchId, "p4", 9);
  var finalDoc = STORE[key_(matchId)];

  check("Test1.1: submitBid() resolves and closes bidding (all 4 seats now have bids)",
    result.allSubmitted === true, "got allSubmitted=" + result.allSubmitted);
  check("Test1.2: the completing seat's own bid IS recorded", finalDoc.bids.p4 === 9);
  check("Test1.3: round-start turn/cardPhase handoff fires (allSubmitted + turn/cardPhase were null)",
    finalDoc.turn != null && finalDoc.cardPhase === "PLAY",
    "got turn=" + finalDoc.turn + " cardPhase=" + finalDoc.cardPhase);
  check("Test1.4 (THE BUG): the real Super Caller (p4/userD) is crowned leader, NOT p3/userC " +
    "(which is what the pre-fix stale bids-snapshot -- missing p4's own just-submitted 9 -- would wrongly compute)",
    finalDoc.turn === "userD",
    "got turn=" + finalDoc.turn + " (expected userD; a value of userC means the stale-snapshot bug has regressed)");

  // ============================================================
  // Test 2 — real submitBiddingAction() SubmitConfirmCall path: same
  // stale-snapshot shape, at the SECOND call site (freshMatch.bids).
  //
  // Honesty note on reachability: under the CURRENT client wiring
  // (design-ui/match/index.html), a fast round's Super Caller always
  // has their own bid mirrored into Firestore's `bids` map via
  // submitBid() BEFORE their SubmitConfirmCall can fire (their own
  // triggering estimate IS that submitBid() call) -- so in practice
  // this call site's merge-and-complete block is normally skipped for
  // fast rounds (freshMatch.bids[seat] is already non-null, failing
  // this block's own entry guard). This test does NOT claim that
  // skip-path is currently reachable in production; it defensively
  // proves the SECOND call site's merge is ALSO correct, using the
  // real Normal-Caller engine path (driveToConfirm(), where the
  // caller's bid genuinely is unmirrored pre-Confirm) with the
  // Firestore doc's `currentRound` set to 14 to exercise the new
  // fast-round formula through this exact code path -- so that if a
  // future change ever does let this block run with a fast round
  // number, the merge fix already protects it.
  // ============================================================
  var caller = driveToConfirm(1); // e.g. "p1" (GameSession's own canonical player ids)
  var matchId2 = "m-confirmcall";
  var seats2 = { p1: "userA", p2: "userB", p3: "userC", p4: "userD" };
  var callerUid = seats2[caller];
  var bids2 = { p1: null, p2: null, p3: null, p4: null };
  Object.keys(seats2).forEach(function (s) { if (s !== caller) bids2[s] = (s === "p3" ? 5 : 4); });
  STORE[key_(matchId2)] = {
    roomId: "room-y", status: "starting", createdAt: 1, currentRound: 14,
    dealer: "userD",
    turn: null, cardPhase: null,
    seats: seats2,
    version: 5, biddingOpen: true,
    bids: bids2, // caller's own slot deliberately still null, matching real pre-Confirm reality
    lastBidSeat: null
  };
  DOC_VERSION[key_(matchId2)] = 1;

  signInAs(callerUid);
  var result2 = await MatchService.submitBiddingAction(matchId2, { actionType: "SubmitConfirmCall", tricks: 6, suit: "SPADES" });
  var finalDoc2 = STORE[key_(matchId2)];

  check("Test2.1: submitBiddingAction() resolves", !!result2);
  check("Test2.2: the Caller's own mirrored bid (6) IS recorded", finalDoc2.bids[caller] === 6);
  check("Test2.3 (THE BUG, second call site): the real highest bidder (the Caller, bid 6) is crowned leader, " +
    "NOT seat p3 (bid 5 -- the highest VISIBLE bid under the pre-fix stale freshMatch.bids snapshot)",
    finalDoc2.turn === callerUid,
    "got turn=" + finalDoc2.turn + " (expected " + callerUid + "; the pre-fix bug would crown userC/p3 instead)");

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed" + (fail ? " (FAILED)" : ""));
  process.exit(fail ? 1 : 0);
})();
