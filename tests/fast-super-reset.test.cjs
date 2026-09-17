// Golden Super Call Reset regression (fast rounds 14-18).
//
// Canonical source: docs/rules/CANONICAL_RULES.md §3 — "Super Call Reset:
// only players who bid BEFORE the Super Caller must re-estimate (their
// estimates were under the old forced suit). Players who bid after it
// keep their estimates."
//
// What this pins, against the REAL, unmodified bidding-engine.js:
//   S1. A fast-round estimate of 8+ routes bidding to CONFIRM with the
//       Super Caller waiting (highest bid, earliest in dealer order).
//   S2. Confirming a replacement trump wipes ONLY the pre-super seats'
//       bids; seats that bid after the Super Caller keep theirs.
//   S3. Re-estimation reopens ESTIMATES at the first pre-super seat and
//       completes to DONE with the Super Caller locked, the replacement
//       trump in force, and later estimates untouched.
//
// Deliberately NO engine change in this file's scope: if any check below
// fails, the rule is broken and the failure must be reported, never
// "fixed" by touching design-ui/engine/*.js here. Bootstrapped via
// tests/support/harness.cjs — no new testing framework introduced.
var Harness = require("./support/harness.cjs");
Harness.makeWindow();
Harness.loadModules([
  "design-ui/engine/cards.js",
  "design-ui/engine/deck.js",
  "design-ui/engine/dealer.js",
  "design-ui/engine/session.js",
  "design-ui/engine/bidding-engine.js"
]);

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;

var counter = Harness.createCounter();
var check = counter.check;

function beginRound(roundNumber, dealerId) {
  GameSession.reset(null);
  GameSession.setDealer(dealerId);
  GameSession.setRound({ number: roundNumber, maxRounds: 18 });
  BiddingEngine.initState();
  return BiddingEngine.getState();
}

function estimate(seat, tricks) {
  var action = { type: "SubmitFinalEstimate", playerId: seat, tricks: tricks };
  var verdict = BiddingEngine.canSubmit(action);
  if (!verdict.legal) return { ok: false, reason: verdict.reason };
  var res = BiddingEngine.emit(action);
  if (res && res.rejected === true) return { ok: false, reason: res.reason || "emit rejected" };
  return { ok: true };
}

// ── Round 15 (forced Spades), dealer p1: p3 Super Calls at 8 ────────
// Order: p1=4, p2=5, p3=8 (super), p4=3. Total 20 — clear of the 13-rule
// at every seat (last estimator p4 sees 4+5+8=17, nothing forbidden).
(function () {
  var s = beginRound(15, "p1");
  check("S setup: fast round starts in ESTIMATES with dealer p1 waiting",
    s.subPhase === "ESTIMATES" && s.waitingFor === "p1" && s.fastRound === true);
  check("S setup: forced trump is Spades", s.declaredTrump === "SPADES");

  var plan = [["p1", 4], ["p2", 5], ["p3", 8], ["p4", 3]];
  var i, ok = true;
  for (i = 0; i < plan.length; i++) {
    if (BiddingEngine.getState().waitingFor !== plan[i][0]) { ok = false; break; }
    var r = estimate(plan[i][0], plan[i][1]);
    if (!r.ok) { ok = false; break; }
  }
  check("S setup: all four estimates accepted", ok);

  // ── S1: Super Call routes to CONFIRM ──
  s = BiddingEngine.getState();
  check("S1: an 8+ estimate routes a fast round to CONFIRM", s.subPhase === "CONFIRM");
  check("S1: the Super Caller waits with the call locked",
    s.waitingFor === "p3" && s.callerId === "p3" && s.auctionTop === 8);

  // ── S2: trump override wipes only pre-super bids ──
  var confirm = BiddingEngine.canSubmit({ type: "SubmitConfirmCall", playerId: "p3", tricks: 8, suit: "HEARTS" });
  check("S2 setup: p3 may confirm 8 Hearts as the replacement trump (legal)", confirm.legal === true);
  var cres = BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: "p3", tricks: 8, suit: "HEARTS" });
  check("S2 setup: confirm accepted, re-estimation opens at p1",
    (!cres || cres.rejected !== true) &&
    BiddingEngine.getState().subPhase === "ESTIMATES" &&
    BiddingEngine.getState().waitingFor === "p1");

  s = BiddingEngine.getState();
  check("S2: pre-super seats p1/p2 were wiped (must re-estimate)",
    !s.bids.p1 && !s.bids.p2);
  check("S2: the Super Caller's own 8 survives the reset",
    !!s.bids.p3 && s.bids.p3.type === "TRICKS" && s.bids.p3.amount === 8);
  check("S2: the post-super seat p4 keeps its 3 (later estimates stand)",
    !!s.bids.p4 && s.bids.p4.amount === 3);
  check("S2: replacement trump is Hearts (forced Spades overridden)",
    s.declaredTrump === "HEARTS");

  // ── S3: re-estimation completes with the Super Caller locked ──
  // p1 sees 8+3=11 on the table, so 2 is forbidden — re-bids 4.
  // p2 then sees 4+8+3=15, nothing forbidden — bids 2. Total 17.
  var r1 = estimate("p1", 4);
  check("S3 setup: p1 re-estimates 4 (legal, accepted)", r1.ok);
  check("S3 setup: turn passes to p2",
    r1.ok && BiddingEngine.getState().waitingFor === "p2");
  var r2 = estimate("p2", 2);
  check("S3 setup: p2 re-estimates 2 (legal, accepted)", r2.ok);

  s = BiddingEngine.getState();
  check("S3: re-estimation completes bidding (DONE)", s.subPhase === "DONE");
  check("S3: Super Caller p3 is the locked Caller", s.callerId === "p3");
  check("S3: no With — nobody else sits on the Super Call number",
    Array.isArray(s.withPlayers) && s.withPlayers.length === 0);
  check("S3: replacement trump survives to the committed round",
    s.declaredTrump === "HEARTS");
  check("S3: committed round carries the Super Caller",
    GameSession.getRound().callerId === "p3");
})();

counter.summary();
