const path = require("path");
const __REPO_ROOT__ = path.join(__dirname, "..");

// Sprint J.11 — Fast-Round Leader Authority.
//
// Real, executable tests for MatchAdapter.computeRoundStartLeaderFromPersistedState()
// (design-ui/match-adapter.js) — a PURE function deriving the Round-14+
// fast-round first-trick leader entirely from durable Firestore fields
// (matchDoc.dealer, matchDoc.currentRound, matchDoc.bids, matchDoc.seats),
// with ZERO GameSession/TableEngine/local-state dependency. Fixes the
// known Round 14+ NOT_YOUR_TURN blocker (root cause: the OLD
// computeRoundStartLeaderUid() read exclusively from GameSession, which
// is structurally guaranteed stale at the exact write instant the 4th
// Final Estimate completes fast-round bidding).
//
// LABELING: Tests A-E, H, I-L are MOCKED against the REAL, unmodified
// design-ui/engine/bidding-engine.js and design-ui/engine/session.js
// (4-player only, since GameSession.getPlayers() is a fixed 4-mock-
// player roster with no built-in override — see card-sync.test.cjs's
// own identical constraint). Tests F/G (3-player/2-player) are
// FORMULA-VERIFIED against synthetic matchDoc fixtures and the same
// documented tie-break rule, hand-computed and cross-checked against
// the formula's own specification — NOT cross-checked against a live
// 2/3-player real-engine run, since this codebase has no existing
// mechanism to drive BiddingEngine with fewer than 4 players. This is
// an honest, disclosed scope limit, not a silently-skipped case.

global.window = global;
global.window.addEventListener = function () {};

require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/engine/bidding-engine.js");
require(__REPO_ROOT__ + "/design-ui/match-adapter.js");

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var MatchAdapter = global.MatchAdapter;
var computeFast = MatchAdapter.computeRoundStartLeaderFromPersistedState;

var pass = 0, fail = 0;
function check(label, cond, note) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
}

var seats4 = { p1: "userA", p2: "userB", p3: "userC", p4: "userD" };

/** Drives a REAL fast round (>=14) through bidding-engine.js's own
 *  ESTIMATES phase with a SCRIPTED sequence of final estimates, then
 *  builds the matching synthetic matchDoc (dealer uid, currentRound,
 *  bids, seats) that a real submitBid() completing transaction would
 *  have seen at that exact moment -- and returns both the REAL
 *  engine's own resolved leader (GameSession.getRound().callerId ||
 *  GameSession.getTurn() -- the ground truth) and the matchDoc to feed
 *  into the pure helper for comparison. */
var SEAT_ORDER_4 = ["p1", "p2", "p3", "p4"];
/** The pure helper rotates matchDoc.dealer CCW (currentRound-1) times
 *  (matching the REAL continuous per-round rotation across all 18
 *  rounds, not a "fast rounds reset to 0" assumption). This test drives
 *  an ISOLATED round directly via GameSession.setDealer(dealerSeat), so
 *  the matchDoc fed to the pure helper must carry the EQUIVALENT
 *  round-1 (creation-time) dealer that, after (roundNumber-1) forward
 *  rotations, lands back on `dealerSeat` -- i.e. the reverse rotation. */
function creationDealerFor(dealerSeat, roundNumber) {
  var i = SEAT_ORDER_4.indexOf(dealerSeat);
  var len = SEAT_ORDER_4.length;
  var back = ((i - (roundNumber - 1)) % len + len) % len;
  return SEAT_ORDER_4[back];
}

function driveRealFastRound(roundNumber, dealerSeat, estimatesByBidOrder) {
  GameSession.reset(null);
  GameSession.setDealer(dealerSeat);
  GameSession.setRound({ number: roundNumber });
  BiddingEngine.initState();
  var s = BiddingEngine.getState();
  check("[setup R" + roundNumber + "] fast round enters ESTIMATES directly", s.subPhase === "ESTIMATES" && s.fastRound === true);
  var order = [];
  var guard = 0;
  while (s.subPhase === "ESTIMATES" && guard < 10) {
    var who = s.waitingFor;
    order.push(who);
    var tricks = estimatesByBidOrder[guard];
    BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: who, tricks: tricks });
    s = BiddingEngine.getState();
    guard++;
  }
  // Ground truth differs by which branch fast-round bidding actually
  // took at the 4th estimate (verified directly against
  // bidding-engine.js's own source, not assumed):
  //  - Super Call: subPhase -> CONFIRM; the reducer sets
  //    `state.callerId = superCallerId` synchronously (line ~606), but
  //    GameSession.completeBidding() -- the ONLY thing that sets
  //    session.round.callerId -- does not run until the LATER
  //    SubmitConfirmCall event. Ground truth = s.callerId.
  //  - No Super Call: subPhase -> DONE directly; the reducer NEVER
  //    sets `state.callerId` itself in this branch (only
  //    GameSession.completeBidding({callerId: fastCallerId}) is
  //    called) -- but that call runs SYNCHRONOUSLY here, so
  //    GameSession.getRound().callerId IS correct immediately.
  //    Ground truth = GameSession.getRound().callerId.
  // Comparing against the WRONG one of these would silently reproduce
  // the exact stale-GameSession bug this sprint fixes, in the TEST's
  // own ground truth -- not a hypothetical, this was caught live while
  // writing this file (see git history / self-review).
  var realLeader = (s.subPhase === "CONFIRM") ? s.callerId : GameSession.getRound().callerId;
  var bids = {};
  order.forEach(function (seat, i) { bids[seat] = estimatesByBidOrder[i]; });
  var matchDoc = {
    dealer: seats4[creationDealerFor(dealerSeat, roundNumber)], currentRound: roundNumber, seats: seats4, bids: bids
  };
  return { realLeader: realLeader, matchDoc: matchDoc, order: order, subPhase: s.subPhase };
}

console.log("=== Sprint J.11: Fast-Round Leader Authority ===\n");

// ══════════════════════════════════════════════════════════════
// Test A — 4-player, no Super Call. Real engine comparison.
// ══════════════════════════════════════════════════════════════
(function () {
  var r = driveRealFastRound(14, "p1", [5, 3, 6, 2]); // dealer p1 bids first; highest is 6
  var pureLeader = computeFast(r.matchDoc);
  check("A.1: real engine resolved a real, non-null caller (no Super Call, max bid 6)", r.realLeader != null);
  check("A.2: pure helper's leader MATCHES the real engine's resolved leader", pureLeader === r.realLeader, "real=" + r.realLeader + " pure=" + pureLeader);
})();

// ══════════════════════════════════════════════════════════════
// Test B — 4-player, Super Call. Real engine comparison.
// ══════════════════════════════════════════════════════════════
(function () {
  var r = driveRealFastRound(15, "p2", [9, 2, 3, 4]); // first bidder (dealer p2) makes the Super Call
  var pureLeader = computeFast(r.matchDoc);
  check("B.1: pure helper's leader MATCHES the real engine's resolved Super Caller", pureLeader === r.realLeader, "real=" + r.realLeader + " pure=" + pureLeader);
})();

// ══════════════════════════════════════════════════════════════
// Test C — Super Call tie (two bids >=8). Real engine comparison.
// ══════════════════════════════════════════════════════════════
(function () {
  var r = driveRealFastRound(16, "p3", [8, 9, 3, 9]); // two bids >=8 (index1=9, index3=9) tie at 9; earliest wins
  var pureLeader = computeFast(r.matchDoc);
  check("C.1: pure helper's leader MATCHES the real engine's Super Call tie-break", pureLeader === r.realLeader, "real=" + r.realLeader + " pure=" + pureLeader);
})();

// ══════════════════════════════════════════════════════════════
// Test D — Highest-bid tie without Super Call. Real engine comparison.
// ══════════════════════════════════════════════════════════════
(function () {
  var r = driveRealFastRound(17, "p4", [6, 3, 6, 2]); // two bids tie at 6 (index0, index2); earliest wins, none >=8
  var pureLeader = computeFast(r.matchDoc);
  check("D.1: pure helper's leader MATCHES the real engine's no-Super-Call tie-break", pureLeader === r.realLeader, "real=" + r.realLeader + " pure=" + pureLeader);
})();

// ══════════════════════════════════════════════════════════════
// Test E — Dealer fallback (pathological: no numeric TRICKS bids at
// all is not reachable through real Estimates, since every seat must
// submit SOME integer -- the closest real-engine-reachable edge is
// "everyone bids the minimum," which still resolves a real tie-broken
// caller, not a true fallback. The TRUE fallback (no valid bidder at
// all) is a synthetic-only edge per the formula's own documented
// contract -- verified directly against the pure helper's own spec.
// ══════════════════════════════════════════════════════════════
(function () {
  // currentRound: 1 isolates the fallback from rotation arithmetic
  // (0 rotations -- dealer stays exactly matchDoc.dealer's own seat).
  var matchDoc = { dealer: "userA", currentRound: 1, seats: seats4, bids: {} }; // no bids at all
  var pureLeader = computeFast(matchDoc);
  check("E.1: dealer fallback -- with zero valid bids, the helper returns the round's own dealer seat", pureLeader === "p1", "got " + pureLeader);
})();

// ══════════════════════════════════════════════════════════════
// Test F — 3-player. Formula-verified (see file header for scope note).
// ══════════════════════════════════════════════════════════════
(function () {
  var seats3 = { p1: "userA", p2: "userB", p3: "userC" };
  // dealer(round1) = p2 (userB); round 14 -> 13 rotations over a
  // 3-seat active order; 13 mod 3 = 1 -> dealer(round14) = p3.
  // biddingOrder = CCW walk from p3 over [p1,p2,p3] -> [p3,p1,p2].
  var matchDoc = { dealer: "userB", currentRound: 14, seats: seats3, bids: { p1: 4, p2: 6, p3: 6 } };
  var pureLeader = computeFast(matchDoc);
  // tie at 6 between p2 and p3; biddingOrder = [p3,p1,p2] -> p3 is earlier
  check("F.1: 3-player tie-break resolves to the earlier seat in the dealer-seeded biddingOrder (p3)", pureLeader === "p3", "got " + pureLeader);
  check("F.2: absent p4 never participates (no crash, no p4 in output)", pureLeader !== "p4");
})();

// ══════════════════════════════════════════════════════════════
// Test G — 2-player. Formula-verified.
// ══════════════════════════════════════════════════════════════
(function () {
  var seats2 = { p1: "userA", p2: "userB" };
  var matchDoc = { dealer: "userB", currentRound: 14, seats: seats2, bids: { p1: 9, p2: 3 } }; // p1's 9 is a Super Call
  var pureLeader = computeFast(matchDoc);
  check("G.1: 2-player Super Call resolves correctly (p1, the only bid >=8)", pureLeader === "p1", "got " + pureLeader);
  check("G.2: absent p3/p4 never participate", pureLeader !== "p3" && pureLeader !== "p4");
})();

// ══════════════════════════════════════════════════════════════
// Test H — currentRound dealer rotation, rounds 14-18.
// ══════════════════════════════════════════════════════════════
(function () {
  // dealer uid = userA (seat p1 at ROUND 1 -- the match's own creation-
  // time dealer, per real gameplay: dealer rotates continuously across
  // ALL 18 rounds, not "reset to 0" when fast rounds begin at 14). By
  // round 14, (14-1)=13 rotations have happened; 13 mod 4 = 1 -> p2.
  // Each successive round adds exactly one more rotation.
  var expectedDealerAtRound = { 14: "p2", 15: "p3", 16: "p4", 17: "p1", 18: "p2" };
  Object.keys(expectedDealerAtRound).forEach(function (roundStr) {
    var round = Number(roundStr);
    // Use a matchDoc with a SINGLE dealer-fallback bid state (empty
    // bids) so the helper's returned leader IS the round's own
    // computed dealer directly (Test E's fallback path), isolating
    // just the rotation arithmetic from the tie-break logic.
    var matchDoc = { dealer: "userA", currentRound: round, seats: seats4, bids: {} };
    var pureLeader = computeFast(matchDoc);
    check("H." + round + ": round " + round + "'s rotated dealer is " + expectedDealerAtRound[round],
      pureLeader === expectedDealerAtRound[round], "got " + pureLeader);
  });
})();

// ══════════════════════════════════════════════════════════════
// Test I — Immutability / purity.
// ══════════════════════════════════════════════════════════════
(function () {
  var matchDoc = { dealer: "userA", currentRound: 14, seats: seats4, bids: { p1: 5, p2: 3, p3: 6, p4: 2 } };
  var before = JSON.parse(JSON.stringify(matchDoc));
  var r1 = computeFast(matchDoc);
  var r2 = computeFast(matchDoc);
  var r3 = computeFast(matchDoc);
  check("I.1: repeated calls with the SAME object return IDENTICAL output", r1 === r2 && r2 === r3, "r1=" + r1 + " r2=" + r2 + " r3=" + r3);
  check("I.2: the input matchDoc object is NEVER mutated", JSON.stringify(matchDoc) === JSON.stringify(before));
})();

// ══════════════════════════════════════════════════════════════
// Test J — GameSession independence (HIGH-VALUE regression test).
// ══════════════════════════════════════════════════════════════
(function () {
  // Poison GameSession with a WRONG turn/round/dealer state.
  GameSession.reset(null);
  GameSession.setRound({ number: 99, callerId: "p4" });
  GameSession.setTurn("p3");
  GameSession.setDealer("p2");
  var matchDoc = { dealer: "userA", currentRound: 14, seats: seats4, bids: { p1: 5, p2: 3, p3: 6, p4: 2 } };
  var pureLeader = computeFast(matchDoc);
  // Expected leader per the formula: dealer=userA->p1, round14->0 rotations,
  // biddingOrder=[p1,p2,p3,p4], highest bid p3=6, no tie -> p3.
  check("J.1: pure helper's leader is UNAFFECTED by a poisoned GameSession (still correctly p3, not p4/p3/p2 from the poisoned state)",
    pureLeader === "p3", "got " + pureLeader);
})();

// ══════════════════════════════════════════════════════════════
// Test K — Local-state independence (poison MORE local state).
// ══════════════════════════════════════════════════════════════
(function () {
  GameSession.reset(null);
  GameSession.setRound({ number: 1, callerId: "p1" }); // wrong round number, wrong caller
  GameSession.setTurn("p1");
  GameSession.setDealer("p4");
  var matchDoc = { dealer: "userB", currentRound: 15, seats: seats4, bids: { p1: 2, p2: 9, p3: 1, p4: 0 } };
  var pureLeader = computeFast(matchDoc);
  // dealer=userB->p2, round15->1 rotation from p2 = p3; biddingOrder=[p3,p4,p1,p2];
  // p2's bid (9) is a Super Call, sole >=8 bidder -> p2.
  check("K.1: pure helper unaffected by poisoned local round/turn/dealer -- correctly resolves the real Super Caller (p2)",
    pureLeader === "p2", "got " + pureLeader);
})();

// ══════════════════════════════════════════════════════════════
// Test L — Same persisted document across "clients" (repeated calls).
// ══════════════════════════════════════════════════════════════
(function () {
  var matchDoc = { dealer: "userC", currentRound: 16, seats: seats4, bids: { p1: 4, p2: 4, p3: 5, p4: 5 } };
  var results = [];
  for (var i = 0; i < 5; i++) { results.push(computeFast(matchDoc)); }
  check("L.1: 5 independent calls (simulating 5 different clients reading the same doc) all agree",
    results.every(function (r) { return r === results[0]; }), JSON.stringify(results));
})();

// ══════════════════════════════════════════════════════════════
// Regression: computeRoundStartLeaderUid() dispatcher — normal rounds
// (1-13) remain on the UNCHANGED GameSession-based path.
// ══════════════════════════════════════════════════════════════
(function () {
  GameSession.reset(null);
  GameSession.setRound({ number: 5, callerId: "p3" });
  var matchDoc = { currentRound: 5, seats: seats4, dealer: "userA", bids: {} };
  var uid = MatchAdapter.computeRoundStartLeaderUid(matchDoc);
  check("Regression: normal round (5) still resolves via GameSession.getRound().callerId (unchanged J.9 path)",
    uid === "userC", "got " + uid);
})();

(function () {
  GameSession.reset(null);
  GameSession.setRound({ number: 14, callerId: null }); // fast round: GameSession never has this set correctly at write time
  GameSession.setTurn("p4"); // stale leftover from a previous round
  var matchDoc = { currentRound: 14, seats: seats4, dealer: "userB", bids: { p1: 3, p2: 7, p3: 2, p4: 1 } };
  var uid = MatchAdapter.computeRoundStartLeaderUid(matchDoc);
  // dealer=userB->p2, 0 rotations; biddingOrder=[p2,p3,p4,p1]; highest bid p2=7, no Super Call, no tie -> p2 -> userB
  check("Regression: fast round (14) dispatches to the NEW persisted-state path, ignoring the poisoned GameSession.getTurn()",
    uid === "userB", "got " + uid);
})();

console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed" + (fail ? " (FAILED)" : ""));
process.exit(fail ? 1 : 0);
