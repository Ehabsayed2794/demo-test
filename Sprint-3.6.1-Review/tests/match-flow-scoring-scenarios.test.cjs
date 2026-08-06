// Sprint 3.6.1 (Normal Dash Scoring Hotfix) — Regression Safety.
// Verifies the hotfix (bidding-engine.js's extractEstimates(),
// scoring-engine.js's applyRoundResult()) did not change scoring
// behavior for any OTHER bid/role combination: Sa'ayda (all four fail)
// and With/Wazz (a matched auction bid), both exercised with a real,
// deterministic deal via a fixed-seed PRNG. "Without" (no With player
// in the round at all — an uncontested auction) and "Normal bid"
// (plain TRICKS success/failure) are already covered by
// tests/match-flow-integration.test.cjs and
// tests/match-flow-normal-dash-scoring-fix.test.cjs, both of which run
// with `withPlayers: []`; not duplicated here.
//
// Each scenario needs its OWN fresh process — bidding-engine.js's and
// table-engine.js's PLAYERS/TURN_ORDER/ROUND_CFG are computed once, at
// require()-time, from a single GameSession snapshot (documented in
// docs/reviews/MatchFlowIntegration_3.6.md §2), so a second scripted
// round cannot be exercised via a second require() in the same
// process. Rather than duplicate the ~60 lines of setup/teardown
// boilerplate across multiple small files (a "no scope creep, minimal"
// tradeoff — this project's existing test files don't share helpers
// across files either, but doing it 2+ more times here starts to add
// real duplicated surface), this single file spawns itself as a child
// process once per scenario and aggregates each child's PASS/FAIL
// output. No engine/source file is touched by this — it's a test-only
// technique.
var path = require("path");

var SCENARIOS = ["saayda", "with"];
var mode = process.argv[2];

if (!mode) {
  // ---- Parent/orchestrator mode ----
  var spawnSync = require("child_process").spawnSync;
  var totalPass = 0, totalFail = 0;
  SCENARIOS.forEach(function (scenario) {
    console.log("\n=== scenario: " + scenario + " ===");
    var result = spawnSync(process.execPath, [__filename, scenario], { encoding: "utf8" });
    process.stdout.write(result.stdout || "");
    if (result.stderr) process.stderr.write(result.stderr);
    var match = /(\d+) passed, (\d+) failed/.exec(result.stdout || "");
    if (!match) {
      console.log("FAIL  scenario '" + scenario + "' did not report a pass/fail summary (crashed?)");
      totalFail++;
      return;
    }
    totalPass += parseInt(match[1], 10);
    totalFail += parseInt(match[2], 10);
    if (result.status !== 0) totalFail = Math.max(totalFail, totalFail); // status already reflected via the summary line
  });
  console.log("\n" + totalPass + " passed, " + totalFail + " failed");
  process.exitCode = totalFail ? 1 : 0;
  return;
}

// ---- Child mode: run exactly one scenario ----
global.window = global;
global.window.addEventListener = function () {};

require(path.join(__dirname, "..", "design-ui", "engine", "cards.js"));
require(path.join(__dirname, "..", "design-ui", "engine", "deck.js"));
require(path.join(__dirname, "..", "design-ui", "engine", "dealer.js"));
require(path.join(__dirname, "..", "design-ui", "engine", "session.js"));
require(path.join(__dirname, "..", "design-ui", "engine", "bidding-engine.js"));
require(path.join(__dirname, "..", "design-ui", "engine", "scoring-engine.js"));

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  [" + mode + "] " + label); pass++; }
  else { console.log("FAIL  [" + mode + "] " + label); fail++; }
}

function legalCardsFor(state, id) {
  var hand = state.hands[id];
  if (!state.ledSuit) return hand.slice();
  var inSuit = hand.filter(function (c) { return c.suit === state.ledSuit; });
  return inSuit.length ? inSuit : hand.slice();
}
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    var t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

(function () {
  // SEED 13, dealer/opener p1 bidding 4 Spades uncontested, gives a
  // known, hand-verified tricksWon distribution under this project's
  // "always play the first legal card" deterministic strategy:
  //   p1: 0, p2: 8, p3: 4, p4: 1
  // (confirmed by direct inspection before writing this test — not
  // guessed). Both scenarios below reuse this exact deal (same seed,
  // same trump/leader) and differ only in the AUCTION path (whether
  // p2 matches to become With) and in the chosen final estimates.
  var realRandom = Math.random;
  Math.random = mulberry32(13);
  BiddingEngine.initState();
  Math.random = realRandom;

  for (var i = 0; i < 4; i++) {
    var cur = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: cur.waitingFor, declaredDashCall: false });
  }
  var s = BiddingEngine.getState();
  var opener = s.waitingFor; // "p1", the dealer
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: opener, tricks: 4, suit: "SPADES", isPass: false });
  s = BiddingEngine.getState();

  if (mode === "with") {
    // p2 matches the top bid exactly (same number, same suit) — becomes
    // With (Wazz). p3 and p4 pass; p1 (already holding the top bid)
    // passes on their own next turn to conclude the auction with p2
    // still marked With but not eliminated.
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, tricks: 4, suit: "SPADES", isPass: false });
    s = BiddingEngine.getState();
    check("p2's matching bid grants With status", s.withPlayers.indexOf("p2") !== -1);
    var guardW = 0;
    while (s.subPhase === "AUCTION" && guardW < 10) {
      BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true });
      s = BiddingEngine.getState();
      guardW++;
    }
  } else {
    // Sa'ayda: everyone else simply passes — uncontested, no With.
    var guardS = 0;
    while (s.subPhase === "AUCTION" && guardS < 10) {
      BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true });
      s = BiddingEngine.getState();
      guardS++;
    }
  }
  check("Auction concludes with p1 as Caller", s.subPhase === "CONFIRM" && s.callerId === opener);

  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s.waitingFor, tricks: s.auctionTop, suit: s.auctionSuit });
  s = BiddingEngine.getState();
  check("Bidding reaches ESTIMATES", s.subPhase === "ESTIMATES");

  // Estimates deliberately chosen (per the known tricksWon distribution
  // above) so that:
  //  - "saayda": every seat's estimate MISSES its actual tricksWon (p1
  //    bid 4 vs actual 0; p2/p3/p4 all pick values that miss too) —
  //    successCount === 0 for everyone, triggering Sa'ayda.
  //  - "with": p3 estimates exactly 4 (their real actual tricksWon) to
  //    SUCCEED as a plain Normal player (sole winner), while p1
  //    (Caller) and p2 (With) and p4 (Normal) all still fail — this
  //    exercises Caller-fail, With-fail, sole-winner, and Risk
  //    adjustments together in one non-Sa'ayda round.
  // A matched auction bid ("With") IS persisted to actionHistory as a
  // real "BID" entry (the auction reducer's own "continue" branch calls
  // recordBidAction() for every non-concluding bid, including a match —
  // it doesn't special-case isWith) — so withFloorFor("p2") finds it and
  // enforces a floor of exactly 4 (their own matched auction number).
  // With cap also 4 (the Caller's locked number), p2's ONLY valid final
  // estimate is exactly 4.
  var picks = (mode === "with") ? { p2: 4, p3: 4, p4: 3 } : { p2: 1, p3: 1, p4: 2 };
  var guardE = 0;
  while (s.subPhase === "ESTIMATES" && guardE < 10) {
    var who = s.waitingFor;
    var tricks = picks[who] != null ? picks[who] : 1;
    var res = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: who, tricks: tricks });
    check("Final estimate for " + who + " (" + tricks + ") is accepted", !res.rejected);
    s = BiddingEngine.getState();
    guardE++;
  }
  check("Bidding completes", s.subPhase === "DONE");

  require(path.join(__dirname, "..", "design-ui", "engine", "table-engine.js"));
  var TableEngine = global.TableEngine;
  TableEngine.initState();

  var guardP = 0;
  while (true) {
    guardP++;
    if (guardP > 500) { check("Trick-play loop terminated within a sane number of steps", false); break; }
    var t = TableEngine.getState();
    if (t.phase === "DONE") break;
    if (t.phase === "RESOLVING") { TableEngine.resolveTrick(); continue; }
    var who2 = t.turn;
    var legal = legalCardsFor(t, who2);
    TableEngine.emit({ type: "PlayCard", playerId: who2, card: legal[0] });
  }

  var finalState = TableEngine.getState();
  check("Round completes structurally — 13 tricks resolve, hands empty",
    Object.keys(finalState.hands).every(function (id) { return finalState.hands[id].length === 0; }));
  check("Actual tricksWon matches the known, hand-verified distribution for this seed (p1:0, p2:8, p3:4, p4:1)",
    JSON.stringify(finalState.tricksWon) === JSON.stringify({ p1: 0, p2: 8, p3: 4, p4: 1 }));

  var result = finalState._scoreResult;
  var deltas = result.deltas;
  check("No NaN anywhere in the score result (the hotfix holds for this scenario too)",
    Object.keys(deltas).every(function (id) { return typeof deltas[id] === "number" && Number.isFinite(deltas[id]); }));

  if (mode === "saayda") {
    check("SA'AYDA: successCount is 0 — every seat genuinely failed", result.successCount === 0);
    check("SA'AYDA: isSaayda flag is set", result.isSaayda === true);
    check("SA'AYDA: every seat's delta is exactly 0 (round zeroed for everyone, per rules §4)",
      Object.keys(deltas).every(function (id) { return deltas[id] === 0; }));
    check("SA'AYDA: nextMultiplier escalates to x2", result.nextMultiplier === 2);
    var matchScores = GameSession.getMatchScores();
    check("SA'AYDA: GameSession.getMatchScores() is unaffected (every delta was 0) — and NOT silently masked, genuinely computed as 0",
      Object.keys(matchScores).every(function (id) { return matchScores[id] === 0; }));
  } else {
    check("WITH: p2 is recorded as With for this round", GameSession.getRound().withPlayers.indexOf("p2") !== -1);
    check("WITH: not a Sa'ayda round — p3 (Normal) genuinely succeeded", result.isSaayda === false && result.successCount === 1);
    check("WITH: p3 is the sole winner and receives the +10 sole-winner bonus",
      result.breakdown.p3.isSoleWinner === true && result.breakdown.p3.notes.some(function (n) { return /sole winner/.test(n); }));
    check("WITH: p1 (failing Caller) receives the Caller failure adjustment",
      result.breakdown.p1.isCaller === true && result.breakdown.p1.notes.some(function (n) { return /Caller -10/.test(n); }));
    check("WITH: p2 (failing With) receives the With failure adjustment, not the Caller one",
      result.breakdown.p2.isWith === true && result.breakdown.p2.isCaller === false && result.breakdown.p2.notes.some(function (n) { return /With -10/.test(n); }));
    check("WITH: p4 correctly carries the Risk role (last bidder) with a nonzero risk adjustment",
      result.breakdown.p4.isRisk === true && result.riskValue > 0);
    var matchScores2 = GameSession.getMatchScores();
    check("WITH: GameSession.getMatchScores() reflects the real, non-Sa'ayda deltas for every seat (none silently zeroed)",
      Object.keys(deltas).every(function (id) { return matchScores2[id] === deltas[id]; }));
  }

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
