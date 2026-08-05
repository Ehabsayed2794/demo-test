// Sprint 3.6 (Match Flow Integration) — dedicated, documented regression
// test for a REAL bug discovered while writing the primary integration
// test (tests/match-flow-integration.test.cjs). Per the brief's explicit
// instruction ("Stop immediately if a rule conflict is discovered and
// document it instead of inventing behavior"), this bug was NOT fixed —
// this test exists to confirm it is real, reproducible, and precisely
// scoped, so a future sprint can decide how to fix it with full
// information. See docs/reviews/MatchFlowIntegration_3.6.md for the
// full writeup. This is a SEPARATE process/file (not folded into the
// main integration test) because bidding-engine.js/table-engine.js cache
// PLAYERS/TURN_ORDER/ROUND_CFG at require()-time from a single
// GameSession snapshot — a second, differently-scripted round cannot be
// exercised in the same process without a fresh require() cache, i.e. a
// fresh `node` process.
//
// ROOT CAUSE, precisely: a player whose FINAL ESTIMATE during bidding is
// exactly 0 tricks is recorded by bidding-engine.js's SubmitFinalEstimate
// handler as { type: "DASH", amount: 0 } (a "Normal Dash" — a real,
// ordinary, legal outcome, not a pre-bidding Dash Call). But
// extractEstimates() — the function that builds the `estimates` map
// GameSession.completeBidding() persists into GameSession.round.estimates
// — only carries TRICKS-type bids:
//   function extractEstimates(sparseBids) {
//     const out = {};
//     Object.keys(sparseBids).forEach(id => { if (sparseBids[id].type === "TRICKS") out[id] = sparseBids[id].amount; });
//     return out;
//   }
// A Normal Dash (type "DASH") is silently DROPPED from `estimates` —
// there is no way to distinguish "estimated 0 tricks" from "never
// estimated at all" once it reaches GameSession.round.estimates; both
// are simply absent from the map.
//
// table-engine.js's resolveTrick() then reconstructs `bids` for scoring
// FROM that same lossy `estimates` map:
//   bids[id] = dashCallers.includes(id)
//     ? { type: "DASHCALL", amount: 0 }
//     : { type: state.estimates[id] === 0 ? "DASH" : "TRICKS", amount: state.estimates[id] };
// For the affected player, `state.estimates[id]` is `undefined` (not
// present at all) — `undefined === 0` is `false`, so this line WRONGLY
// reconstructs `{ type: "TRICKS", amount: undefined }` instead of the
// real `{ type: "DASH", amount: 0 }`.
//
// ScoringEngine.calculateRoundScore() then receives that corrupted bid.
// In the TRICKS branch, a failed bid computes
// `delta = -Math.abs(T - bid.amount)` = `-Math.abs(0 - undefined)` =
// `-Math.abs(NaN)` = `NaN`. That NaN then multiplies by the round
// multiplier (still NaN) and is stored as this player's score delta —
// silently corrupting GameSession.getMatchScores() for that player from
// that round onward.
//
// This bug lives in the INTERACTION between bidding-engine.js's
// extractEstimates() and table-engine.js's resolveTrick() bids
// reconstruction — NOT in ScoringEngine, which computes exactly what a
// correct implementation would for the (corrupted) input it's given.
// Per the brief ("Do not rewrite ScoringEngine unless a documented bug
// blocks integration" and the Engine Boundaries section's "if anything
// else blocks [integration], stop and document it instead of
// redesigning"), no source file was changed to fix this — it is
// reported here for a future sprint's explicit decision.
//
// A second, distinct masking effect (same root cause, observed at a
// different layer) was also discovered: ScoringEngine.applyRoundResult()
// accumulates match totals via `(current[id]||0) + (result.deltas[id]||0)`.
// Since NaN is falsy in JavaScript, that `|| 0` silently turns the
// visible NaN delta back into a plain 0 the moment it reaches
// GameSession.getMatchScores() — the corruption is visible in the
// round's own _scoreResult.deltas, but INVISIBLE in the running match
// total, which looks like a perfectly ordinary "scored 0" instead of a
// broken calculation. Also not fixed — reported alongside the primary
// bug for the same future-sprint decision.
//
// A note on why this bug is intermittent by nature, not something a
// fixed test could dodge by luck: ScoringEngine.calculateRoundScore()
// short-circuits to a flat 0-for-everyone Sa'ayda result whenever
// EVERY player's bid fails (successCount === 0) — a Sa'ayda round never
// reaches the TRICKS-branch arithmetic that this bug depends on, so it
// would be entirely MASKED by a Sa'ayda outcome. This test uses a fixed
// deterministic PRNG (seeded, substituted for Math.random only for the
// duration of the deal — see below) specifically so the reproduction
// doesn't depend on which way an unseeded random deal happens to fall.
global.window = global;
global.window.addEventListener = function () {};

require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
require("/home/user/demo-test/design-ui/engine/session.js");
require("/home/user/demo-test/design-ui/engine/bidding-engine.js");
require("/home/user/demo-test/design-ui/engine/scoring-engine.js");

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;

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

// This bug only surfaces when at least one player's REAL bid succeeds —
// ScoringEngine short-circuits to a flat 0-for-everyone Sa'ayda result
// when successCount === 0, which would mask the corrupted bid's NaN
// arithmetic entirely (a Sa'ayda round never reaches the TRICKS branch
// at all). Deck.shuffle() defaults to Math.random with no way to inject
// a seed through Dealer.dealHands() (by design — see deck.js's own
// header comment on why that stayed minimal), so this test temporarily
// substitutes a fixed, deterministic PRNG for Math.random for the
// duration of the deal ONLY, then restores the real Math.random
// immediately after — a standard, source-file-free testing technique
// (no engine file is touched by this), needed here specifically to make
// bug reproduction 100% deterministic rather than dependent on which
// way a real random deal happens to fall.
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    var t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

(function () {
  // ============ Script a round where one seat's final estimate is 0 ============
  var realRandom = Math.random;
  Math.random = mulberry32(42); // fixed seed — see comment above
  BiddingEngine.initState();
  Math.random = realRandom;
  for (var i = 0; i < 4; i++) {
    var cur = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: cur.waitingFor, declaredDashCall: false });
  }
  var s = BiddingEngine.getState();
  var auctionOpener = s.waitingFor; // the dealer, "p1"
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: auctionOpener, tricks: 4, suit: "SPADES", isPass: false });
  s = BiddingEngine.getState();
  var guard1 = 0;
  while (s.subPhase === "AUCTION" && guard1 < 10) {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true });
    s = BiddingEngine.getState();
    guard1++;
  }
  check("Auction concludes uncontested with the opener as Caller", s.subPhase === "CONFIRM" && s.callerId === auctionOpener);
  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s.waitingFor, tricks: s.auctionTop, suit: s.auctionSuit });
  s = BiddingEngine.getState();
  check("Bidding reaches ESTIMATES", s.subPhase === "ESTIMATES");

  // The affected player: the FIRST seat to estimate submits a Normal
  // Dash (0 tricks) — a completely ordinary, legal choice.
  var affectedPlayerId = s.waitingFor;
  var guard2 = 0;
  var estimatesGiven = 0;
  while (s.subPhase === "ESTIMATES" && guard2 < 10) {
    var who = s.waitingFor;
    var tricks = (estimatesGiven === 0) ? 0 : 1; // first estimator dashes; the rest estimate normally
    var res = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: who, tricks: tricks });
    check("Final estimate for " + who + " (" + tricks + ") is accepted by bidding-engine.js (a Normal Dash IS a legal estimate)", !res.rejected);
    s = BiddingEngine.getState();
    estimatesGiven++;
    guard2++;
  }
  check("Bidding completes", s.subPhase === "DONE");
  check("The affected player's bid is recorded as a real Normal Dash inside bidding-engine.js's own state", JSON.stringify(s.bids[affectedPlayerId]) === JSON.stringify({ type: "DASH", amount: 0 }));

  var committedEstimates = GameSession.getRound().estimates;
  check("CONFIRMED ROOT CAUSE: the affected player's Normal Dash (0 tricks) is ABSENT from GameSession.round.estimates " +
    "(extractEstimates() only carries TRICKS-type bids, silently dropping this legal DASH-type estimate)",
    !Object.prototype.hasOwnProperty.call(committedEstimates, affectedPlayerId));

  // ============ Play the round out and confirm the corruption reaches scoring ============
  require("/home/user/demo-test/design-ui/engine/table-engine.js");
  var TableEngine = global.TableEngine;
  TableEngine.initState();

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
  check("The round still completes structurally despite the corrupted bid — 13 tricks resolve, hands empty",
    Object.keys(finalState.hands).every(function (id) { return finalState.hands[id].length === 0; }));

  var deltas = finalState._scoreResult.deltas;
  check("CONFIRMED BUG: the affected player's score delta is NaN (not a real number) — this is the reproduced corruption",
    typeof deltas[affectedPlayerId] === "number" && Number.isNaN(deltas[affectedPlayerId]));
  check("The corruption is ISOLATED to the affected player — every OTHER seat still receives a finite delta",
    Object.keys(deltas).filter(function (id) { return id !== affectedPlayerId; })
      .every(function (id) { return typeof deltas[id] === "number" && Number.isFinite(deltas[id]); }));

  // A second, distinct masking effect discovered while writing this
  // test — NOT a separate bug, the same corruption observed at a
  // different layer: ScoringEngine.applyRoundResult() accumulates
  // match totals via `(current[id] || 0) + (result.deltas[id] || 0)`.
  // Since `NaN` is falsy in JavaScript, `result.deltas[id] || 0`
  // silently turns the visible NaN back into a plain 0 the moment it's
  // added to GameSession.getMatchScores() — arguably WORSE than an
  // overt NaN, since the running match total looks perfectly ordinary
  // (a plausible "scored 0 this round") instead of visibly broken; the
  // affected player's total simply fails to update for this round with
  // no error and no visible signal anything went wrong.
  var matchScores = GameSession.getMatchScores();
  check("CONFIRMED (second masking effect, same root cause): applyRoundResult()'s `|| 0` fallback silently converts the " +
    "visible NaN into a plausible-looking 0 in GameSession.getMatchScores() — the corruption becomes INVISIBLE at the " +
    "match-totals level, which is arguably worse than an overt NaN",
    matchScores[affectedPlayerId] === 0);

  console.log("\n" + pass + " passed, " + fail + " failed");
  console.log("\nNOTE: every check above is expected to PASS — this test's purpose is to CONFIRM the bug exists and is precisely");
  console.log("scoped, exactly like this project's established 'confirms the documented limitation, not a failure' precedent");
  console.log("(see e.g. Sprint 3.3's creator self-promotion gap). A PASS here means the bug was successfully reproduced and");
  console.log("isolated, not that anything is fixed. See docs/reviews/MatchFlowIntegration_3.6.md for the recommended fix.");
  process.exitCode = fail ? 1 : 0;
})();
