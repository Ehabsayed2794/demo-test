// Named regressions for the three With-grant paths plus the fast-round
// Caller/With locks. Engine-track item (C) + (A)-as-locks.
//
// What this pins, against the REAL, unmodified bidding-engine.js:
//   C1. Live exact match: bidding the current top number+suit makes that
//       seat With immediately, and a later pass never strips it.
//   C2. Auction Alignment at conclusion: any seat that bid the winning
//       SUIT (even a different number, even before passing) is With;
//       a seat that only ever passed, and the Caller, are excluded.
//   C3. Estimation Jump-In (normal rounds): a non-caller whose final
//       estimate exactly matches the Caller's locked number becomes With.
//   A1. Fast round, unique highest estimate -> that seat is Caller.
//   A2. Fast-round tie -> earliest bidder in the round's ACTUAL
//       dealer-first order is Caller; every other seat at the top
//       number is With.
//   A3. All-zero fast round -> no Caller, no With (locks current edge
//       behavior: zero estimates are DASH type, outside the TRICKS-only
//       highest rule).
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

function declineDash() {
  var s = BiddingEngine.getState();
  var verdict = BiddingEngine.canSubmit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false });
  if (!verdict.legal) return false;
  var res = BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false });
  return !res || res.rejected !== true;
}

function auctionBid(seat, tricks, suit, isPass) {
  var action = { type: "SubmitAuctionBid", playerId: seat };
  if (isPass) { action.isPass = true; }
  else { action.isPass = false; action.tricks = tricks; action.suit = suit; }
  var verdict = BiddingEngine.canSubmit(action);
  if (!verdict.legal) return { ok: false, reason: verdict.reason };
  var res = BiddingEngine.emit(action);
  if (res && res.rejected === true) return { ok: false, reason: res.reason || "emit rejected" };
  return { ok: true };
}

// ── Normal round, dealer p1: scripted auction ──────────────────────
// p1 opens 4 Spades; p2 matches 4 Spades (With on the spot); p3 raises
// 5 Spades; p4 passes; p1 raises 6 Spades and the table passes out.
(function () {
  var s = beginRound(1, "p1");
  check("C setup: normal round starts in DASH with dealer p1 waiting", s.subPhase === "DASH" && s.waitingFor === "p1");
  var i, ok = true;
  for (i = 0; i < 4; i++) ok = ok && declineDash();
  s = BiddingEngine.getState();
  check("C setup: all decline reaches AUCTION", ok && s.subPhase === "AUCTION");

  check("C1 setup: opener p1 is waiting", BiddingEngine.getState().waitingFor === "p1");
  var r = auctionBid("p1", 4, "SPADES", false);
  check("C1 setup: p1 opens 4 Spades (legal, accepted)", r.ok);
  check("C1 setup: p2 is waiting next", BiddingEngine.getState().waitingFor === "p2");
  r = auctionBid("p2", 4, "SPADES", false);
  check("C1 setup: p2 matches 4 Spades (legal, accepted)", r.ok);
  check("C1: live exact match grants With immediately — withPlayers is [p2]",
    JSON.stringify(BiddingEngine.getState().withPlayers) === JSON.stringify(["p2"]));

  check("C1 setup: p3 is waiting next", BiddingEngine.getState().waitingFor === "p3");
  r = auctionBid("p3", 5, "SPADES", false);
  check("C1 setup: p3 raises 5 Spades (legal, accepted)", r.ok);
  check("C1: raising in the same suit keeps the earlier With — withPlayers still [p2]",
    JSON.stringify(BiddingEngine.getState().withPlayers) === JSON.stringify(["p2"]));
  check("C1 setup: p4 is waiting next", BiddingEngine.getState().waitingFor === "p4");
  r = auctionBid("p4", 0, null, true);
  check("C1 setup: p4 passes (legal, accepted)", r.ok);
  check("C1 setup: p1 is waiting next", BiddingEngine.getState().waitingFor === "p1");
  r = auctionBid("p1", 6, "SPADES", false);
  check("C1 setup: p1 raises 6 Spades (legal, accepted)", r.ok);
  check("C1 setup: p2 is waiting next", BiddingEngine.getState().waitingFor === "p2");
  r = auctionBid("p2", 0, null, true);
  check("C1: a pass never strips an earlier With — withPlayers still [p2] after p2 passes",
    r.ok && JSON.stringify(BiddingEngine.getState().withPlayers) === JSON.stringify(["p2"]));
  check("C1 setup: p3 is waiting next", BiddingEngine.getState().waitingFor === "p3");
  r = auctionBid("p3", 0, null, true);
  check("C1 setup: p3 passes, auction concludes to CONFIRM", r.ok && BiddingEngine.getState().subPhase === "CONFIRM");

  // ── C2: Alignment evaluated at conclusion ──
  s = BiddingEngine.getState();
  check("C2: Caller is p1 with 6 Spades on top",
    s.callerId === "p1" && s.auctionTop === 6 && s.auctionSuit === "SPADES");
  check("C2: Alignment grants With to the same-suit earlier bidder — withPlayers is [p2,p3]",
    JSON.stringify(s.withPlayers) === JSON.stringify(["p2", "p3"]));
  check("C2: pass-only p4 is excluded from With", s.withPlayers.indexOf("p4") === -1);
  check("C2: the Caller is never its own With", s.withPlayers.indexOf("p1") === -1);

  // ── C3: Jump-In during estimates ──
  var confirm = BiddingEngine.canSubmit({ type: "SubmitConfirmCall", playerId: "p1", tricks: 6, suit: "SPADES" });
  check("C3 setup: p1 confirms 6 Spades (legal)", confirm.legal === true);
  var cres = BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: "p1", tricks: 6, suit: "SPADES" });
  check("C3 setup: confirm accepted, bidding reaches ESTIMATES",
    (!cres || cres.rejected !== true) && BiddingEngine.getState().subPhase === "ESTIMATES");

  var jumped = false, guard = 0;
  while (BiddingEngine.getState().subPhase === "ESTIMATES" && guard < 6) {
    guard++;
    var cur = BiddingEngine.getState();
    var seat = cur.waitingFor;
    var want = (!jumped && seat !== "p1") ? 6 : -1;
    var pick = -1, g;
    if (want === 6 && BiddingEngine.canSubmit({ type: "SubmitFinalEstimate", playerId: seat, tricks: 6 }).legal) {
      pick = 6;
    } else {
      for (g = 0; g <= 13; g++) {
        if (g === 6 && !jumped && seat !== "p1") continue;
        if (BiddingEngine.canSubmit({ type: "SubmitFinalEstimate", playerId: seat, tricks: g }).legal) { pick = g; break; }
      }
    }
    if (pick === -1) break;
    var eres = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: seat, tricks: pick });
    if (eres && eres.rejected === true) break;
    if (pick === 6 && seat !== "p1") jumped = seat;
  }
  s = BiddingEngine.getState();
  check("C3: bidding completes (DONE)", s.subPhase === "DONE");
  check("C3: a non-caller estimated exactly the Caller's 6 (Jump-In happened)", jumped !== false);
  check("C3: Jump-In grants With — the jumper is in withPlayers",
    jumped !== false && s.withPlayers.indexOf(jumped) !== -1);
})();

// ── A1: fast round, unique highest estimate is Caller ───────────────
(function () {
  beginRound(15, "p1");
  var order = ["p1", "p2", "p3", "p4"];
  var vals = { p1: 5, p2: 6, p3: 4, p4: 5 };
  var i, ok = true;
  for (i = 0; i < 4; i++) {
    var seat = BiddingEngine.getState().waitingFor;
    if (seat !== order[i]) { ok = false; break; }
    var v = BiddingEngine.canSubmit({ type: "SubmitFinalEstimate", playerId: seat, tricks: vals[seat] });
    if (!v.legal) { ok = false; break; }
    var res = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: seat, tricks: vals[seat] });
    if (res && res.rejected === true) { ok = false; break; }
  }
  var s = BiddingEngine.getState();
  check("A1: fast-round estimates drive bidding to DONE", ok && s.subPhase === "DONE");
  check("A1: unique highest estimate wins — Caller is p2", s.callerId === "p2");
  check("A1: nobody else at 6 — withPlayers is empty", Array.isArray(s.withPlayers) && s.withPlayers.length === 0);
  check("A1: committed round carries the Caller", GameSession.getRound().callerId === "p2");
})();

// ── A2: fast-round tie breaks to the earliest bidder in dealer order ─
(function () {
  beginRound(16, "p3");
  var vals = { p3: 7, p4: 5, p1: 7, p2: 7 };
  var expectOrder = ["p3", "p4", "p1", "p2"], i, ok = true;
  for (i = 0; i < 4; i++) {
    var seat = BiddingEngine.getState().waitingFor;
    if (seat !== expectOrder[i]) { ok = false; break; }
    var v = BiddingEngine.canSubmit({ type: "SubmitFinalEstimate", playerId: seat, tricks: vals[seat] });
    if (!v.legal) { ok = false; break; }
    var res = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: seat, tricks: vals[seat] });
    if (res && res.rejected === true) { ok = false; break; }
  }
  var s = BiddingEngine.getState();
  check("A2: bidding order follows the dealer (p3 first)", ok);
  check("A2: tie at 7 breaks to the earliest bidder — Caller is p3", s.subPhase === "DONE" && s.callerId === "p3");
  check("A2: every other seat at the top number is With — withPlayers is [p1,p2]",
    JSON.stringify(s.withPlayers) === JSON.stringify(["p1", "p2"]));
})();

// ── A3: all-zero fast round locks the no-Caller edge ─────────────────
(function () {
  beginRound(17, "p1");
  var i, ok = true;
  for (i = 0; i < 4; i++) {
    var seat = BiddingEngine.getState().waitingFor;
    var v = BiddingEngine.canSubmit({ type: "SubmitFinalEstimate", playerId: seat, tricks: 0 });
    if (!v.legal) { ok = false; break; }
    var res = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: seat, tricks: 0 });
    if (res && res.rejected === true) { ok = false; break; }
  }
  var s = BiddingEngine.getState();
  check("A3: all-zero estimates complete bidding (DONE)", ok && s.subPhase === "DONE");
  check("A3: zero estimates are DASH type, outside the TRICKS-only highest rule — Caller stays null",
    s.callerId === null);
  check("A3: no With without a top number — withPlayers is empty",
    Array.isArray(s.withPlayers) && s.withPlayers.length === 0);
})();

counter.summary();
