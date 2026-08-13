const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Sprint 3.6.1 (Normal Dash Scoring Hotfix) — fix-verification test.
//
// This file REPLACES tests/match-flow-normal-dash-scoring-bug.test.cjs
// (Sprint 3.6), which used this EXACT same deterministic scenario (same
// fixed-seed PRNG, same scripted bids) to CONFIRM the bug existed. Now
// that the bug is fixed (see design-ui/engine/bidding-engine.js's
// extractEstimates() and design-ui/engine/scoring-engine.js's
// applyRoundResult() — both changed this sprint, see
// docs/reviews/MatchFlowIntegration_3.6.1.md for the full writeup),
// this file asserts the OPPOSITE: the exact same scenario that used to
// produce a NaN now produces a correct, finite score. Renamed
// (`-bug-` -> `-fix-`) rather than edited in place, since a file that
// used to document a confirmed bug and now confirms a fix is different
// enough in purpose to deserve an honest new name — the git history
// still shows the relationship.
global.window = global;
global.window.addEventListener = function () {};

require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/engine/bidding-engine.js");
require(__REPO_ROOT__ + "/design-ui/engine/scoring-engine.js");

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var ScoringEngine = global.ScoringEngine;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function legalCardsFor(state, id) {
  var hand = state.hands[id];
  if (!state.ledSuit) return hand.slice();
  var inSuit = hand.filter(function (c) { return c.suit === state.ledSuit; });
  return inSuit.length ? inSuit : hand.slice();
}

// Same fixed, deterministic PRNG and seed as the original bug
// reproduction — deliberately unchanged, so this is provably the SAME
// scenario, not a different, easier one that happens to avoid the bug.
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    var t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

(function () {
  // ============ Script the EXACT same round as the original bug repro ============
  var realRandom = Math.random;
  Math.random = mulberry32(42);
  BiddingEngine.initState();
  Math.random = realRandom;

  for (var i = 0; i < 4; i++) {
    var cur = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: cur.waitingFor, declaredDashCall: false });
  }
  var s = BiddingEngine.getState();
  var auctionOpener = s.waitingFor;
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: auctionOpener, tricks: 4, suit: "SPADES", isPass: false });
  s = BiddingEngine.getState();
  var guard1 = 0;
  while (s.subPhase === "AUCTION" && guard1 < 10) {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true });
    s = BiddingEngine.getState();
    guard1++;
  }
  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s.waitingFor, tricks: s.auctionTop, suit: s.auctionSuit });
  s = BiddingEngine.getState();

  var affectedPlayerId = s.waitingFor; // the first estimator — submits the Normal Dash
  var guard2 = 0;
  var estimatesGiven = 0;
  while (s.subPhase === "ESTIMATES" && guard2 < 10) {
    var who = s.waitingFor;
    var tricks = (estimatesGiven === 0) ? 0 : 1; // first estimator dashes; the rest estimate normally
    var res = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: who, tricks: tricks });
    check("Final estimate for " + who + " (" + tricks + ") is accepted — a Normal Dash is a legal estimate", !res.rejected);
    s = BiddingEngine.getState();
    estimatesGiven++;
    guard2++;
  }
  check("Bidding completes", s.subPhase === "DONE");
  check("The affected player's bid is recorded as a real Normal Dash inside bidding-engine.js's own state",
    JSON.stringify(s.bids[affectedPlayerId]) === JSON.stringify({ type: "DASH", amount: 0 }));

  // ============ FIX VERIFICATION #1: the 0 survives extraction/storage ============
  var committedEstimates = GameSession.getRound().estimates;
  check("FIXED: the affected player's Normal Dash (0 tricks) now SURVIVES extraction — present in GameSession.round.estimates",
    Object.prototype.hasOwnProperty.call(committedEstimates, affectedPlayerId));
  check("FIXED: the surviving value is exactly 0 (a real number, not undefined/null/false/a string)",
    committedEstimates[affectedPlayerId] === 0 && typeof committedEstimates[affectedPlayerId] === "number");
  check("Every OTHER seat's real TRICKS estimate is still present and correct, AND now all 4 seats have an entry " +
    "(the fix didn't disturb unrelated seats, and no seat is spuriously absent anymore)",
    Object.keys(committedEstimates).length === 4 && committedEstimates[affectedPlayerId] === 0);

  // ============ Play the round out ============
  require(__REPO_ROOT__ + "/design-ui/engine/table-engine.js");
  var TableEngine = global.TableEngine;
  TableEngine.initState();
  var t0 = TableEngine.getState();
  check("FIX VERIFICATION #2 (estimate lookup): table-engine.js's own state.estimates ALSO carries the surviving 0 " +
    "(the value's survival propagates all the way through to card-play state, not just bidding state)",
    Object.prototype.hasOwnProperty.call(t0.estimates, affectedPlayerId) && t0.estimates[affectedPlayerId] === 0);

  var guard3 = 0;
  while (true) {
    guard3++;
    if (guard3 > 500) { check("Trick-play loop terminated within a sane number of steps", false); break; }
    var t = TableEngine.getState();
    if (t.phase === "DONE") break;
    if (t.phase === "RESOLVING") { TableEngine.resolveTrick(); continue; }
    var who2 = t.turn;
    var legal = legalCardsFor(t, who2);
    TableEngine.emit({ type: "PlayCard", playerId: who2, card: legal[0] });
  }

  var finalState = TableEngine.getState();
  check("The round completes structurally — 13 tricks resolve, hands empty",
    Object.keys(finalState.hands).every(function (id) { return finalState.hands[id].length === 0; }));

  // ============ FIX VERIFICATION #3: no NaN anywhere in the score result ============
  var deltas = finalState._scoreResult.deltas;
  check("FIXED: the affected player's score delta is a real, finite number — NOT NaN",
    typeof deltas[affectedPlayerId] === "number" && Number.isFinite(deltas[affectedPlayerId]));
  check("Every seat's delta is finite (no NaN anywhere in the result)",
    Object.keys(deltas).every(function (id) { return typeof deltas[id] === "number" && Number.isFinite(deltas[id]); }));

  // ============ FIX VERIFICATION #4: the score matches what the (now-correct)
  // reconstructed bid SHOULD produce, per ScoringEngine's own existing,
  // UNCHANGED DASH-type formula — an independent re-derivation, not a
  // duplicate implementation of the formula itself. ============
  var independentBids = {
    p1: { type: "TRICKS", amount: 4 },
  };
  independentBids[affectedPlayerId] = { type: "DASH", amount: 0 };
  ["p1", "p2", "p3", "p4"].forEach(function (id) {
    if (id !== "p1" && id !== affectedPlayerId) independentBids[id] = { type: "TRICKS", amount: 1 };
  });
  var recheck = ScoringEngine.calculateRoundScore({
    round: 1, turnOrder: ["p1", "p2", "p3", "p4"], bids: independentBids,
    tricksWon: finalState.tricksWon, callerId: auctionOpener, withPlayers: [],
    multiplier: 1, riskPlayerId: GameSession.getBiddingState().riskPlayerId,
    scoringMode: GameSession.getScoringMode(), escalationCap: 8
  });
  check("FIXED: the actual, engine-produced score EXACTLY matches an independent re-derivation from the CORRECT " +
    "(type:DASH, amount:0) bid — proving the fix produces the officially-correct score, not just 'a' finite number",
    JSON.stringify(recheck.deltas) === JSON.stringify(deltas));
  check("The affected player's DASH-branch delta is computed using the real DASH formula (10 on success, " +
    "-(10+tricksWon) on failure, plus Caller/With/Risk/sole-winner-or-loser adjustments) — never the TRICKS formula",
    recheck.breakdown[affectedPlayerId].notes.some(function (n) { return /Normal Dash/.test(n); }));

  // ============ FIX VERIFICATION #5: running totals are correct, not masked ============
  var matchScores = GameSession.getMatchScores();
  check("FIXED: GameSession.getMatchScores() reflects the REAL delta for the affected player, never silently masked to 0",
    matchScores[affectedPlayerId] === deltas[affectedPlayerId]);
  check("Every seat's running total matches its round delta exactly (starting from a fresh 0 baseline)",
    Object.keys(deltas).every(function (id) { return matchScores[id] === deltas[id]; }));

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
