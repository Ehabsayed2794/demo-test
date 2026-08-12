// Sprint 4.0, Task B — targeted regression test for the "Fast-Round
// Caller" bug fix in design-ui/engine/bidding-engine.js.
//
// Bug (found during the Sprint 4.0 engine audit, PROJECT_STATUS_AND_MASTER_PLAN.md
// §5/§8): rounds 14-18 (fast rounds) with no Super Call (no bid >= 8)
// completed bidding with `callerId: null, withPlayers: []` unconditionally
// — but the rules doc (§3) says the highest bidder is ALWAYS the Caller
// in a fast round, Super Call or not. Fixed by resolving a real
// Caller/With from the highest bid whenever no Super Call occurs, using
// the same first-to-bid tie-break already established for the Super
// Call path.
//
// Uses the SAME require/harness pattern as every other test file in
// this suite (see tests/bidding-contract.test.cjs's own fast-round
// setup, lines ~356+, for the identical GameSession.setRound()/
// BiddingEngine.initState() pattern reused here).
global.window = global;
global.window.addEventListener = function () {};

require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
require("/home/user/demo-test/design-ui/engine/session.js");
require("/home/user/demo-test/design-ui/engine/bidding-engine.js");

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

console.log("=== Fast-Round Caller Bug Fix — Regression Test ===\n");

(function () {
  // Round 15 starts (a fast round, forced trump per the 14-18 cycle).
  GameSession.reset(null);
  GameSession.setRound({ number: 15 });
  BiddingEngine.initState();
  var s = BiddingEngine.getState();
  check("1. Round 15 starts. Bidding skips Dash/Auction straight to ESTIMATES (fast round)",
    s.subPhase === "ESTIMATES" && s.fastRound === true);

  // Bids occur but NO Super Call — every bid stays below 8. Distinct
  // amounts so there's an unambiguous single highest bidder to check
  // against (the "everyone bids 8" With-tie case is covered separately
  // below).
  var bidsByGuardStep = [5, 3, 6, 2]; // p-whoever's-turn-first gets 5, then 3, 6, 2 in turn order
  var guard = 0;
  var bidLog = [];
  while (s.subPhase === "ESTIMATES" && guard < 10) {
    var who = s.waitingFor;
    var tricks = bidsByGuardStep[guard];
    bidLog.push({ who: who, tricks: tricks });
    BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: who, tricks: tricks });
    s = BiddingEngine.getState();
    guard++;
  }
  check("2. Bids occur but no Super Call (highest bid, 6, stays below 8)",
    bidLog.every(function (b) { return b.tricks < 8; }));

  // The highest bidder (6 tricks) must be the Caller.
  var highestBidder = bidLog.reduce(function (best, b) { return b.tricks > best.tricks ? b : best; }, bidLog[0]);

  check("3. Bidding completed (subPhase === DONE) with no Confirmation Phase (fast round)", s.subPhase === "DONE");
  var round = GameSession.getRound();
  check("4. callerId is NOT null — the highest bidder is assigned as Caller (the bug's exact symptom)",
    round.callerId !== null && round.callerId !== undefined);
  check("5. callerId is specifically the actual highest bidder", round.callerId === highestBidder.who);
  check("6. withPlayers is empty (all 4 bids were distinct — no one else matched the highest)",
    Array.isArray(round.withPlayers) && round.withPlayers.length === 0);
  check("7. leaderId (trick 1 leader) is the newly-assigned Caller, not a fallback dealer/firstBidder value",
    GameSession.getTurn() === highestBidder.who);
})();

(function () {
  // A second scenario: a tie at the highest (non-Super-Call) bid must
  // produce a Caller (first-to-bid among the tied seats, per the
  // established tie-break) PLUS real With players — not an empty list.
  GameSession.reset(null);
  GameSession.setRound({ number: 16 });
  BiddingEngine.initState();
  var s = BiddingEngine.getState();
  var firstBidder = s.waitingFor;
  var tiedBids = [6, 6, 3, 6]; // 3 of 4 seats tie at the highest (6), none reach Super Call (8+)
  var order = [];
  var guard = 0;
  while (s.subPhase === "ESTIMATES" && guard < 10) {
    order.push(s.waitingFor);
    BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: s.waitingFor, tricks: tiedBids[guard] });
    s = BiddingEngine.getState();
    guard++;
  }
  var round = GameSession.getRound();
  check("8. Tie-at-highest (no Super Call): callerId is the FIRST of the tied seats to have bid",
    round.callerId === order[0]);
  check("9. Tie-at-highest: the other two tied seats are recorded as With",
    Array.isArray(round.withPlayers) && round.withPlayers.length === 2 &&
    round.withPlayers.indexOf(order[1]) !== -1 && round.withPlayers.indexOf(order[3]) !== -1);
})();

console.log("\n=== RESULTS ===\n");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
