const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Focused, real, executable tests for the Table Controls sprint's
// authorized Foundation Fix: table-engine.js's ROUND_CFG must be
// re-derived from GameSession's CURRENT round/bidding state on every
// initState() call, never frozen from a stale snapshot (module-load
// time, or an earlier round). Exercises the REAL, mostly-unmodified
// design-ui/engine/cards.js, deck.js, dealer.js, session.js,
// bidding-engine.js, table-engine.js — not stubs, not mocks.
//
// Scenario 1 (per the Foundation Fix authorization): PAGE LOAD ->
// TableEngine module loads (BEFORE bidding) -> REAL bidding completes
// (trump/caller/estimates change) -> TableEngine.initState() called
// (for the first time) -> state must reflect the CURRENT round
// configuration, not the page-load defaults.
//
// Scenario 2: complete round 1, start round 2, initState() again ->
// round-2 configuration must be used; round-1 trump/caller/estimates/
// round-number must NOT bleed into round 2.
global.window = global;
global.window.addEventListener = function () {};

require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/engine/bidding-engine.js");
require(__REPO_ROOT__ + "/design-ui/engine/scoring-engine.js");
// Foundation Fix's whole point: table-engine.js is required IMMEDIATELY
// here — BEFORE any bidding has happened — deliberately mirroring the
// real browser page's own <script> tag order (match/index.html loads
// every engine file once, synchronously, at page load, before any user
// interaction). Requiring the module this early is exactly what exposed
// the bug (the OLD code computed ROUND_CFG right here, at require()
// time); the fix is what makes it safe. Deliberately NOT calling
// TableEngine.initState() yet — the fix does not require ever calling
// it "early" at all; the correct, intended usage (and what this
// sprint's own UI implementation uses) is to call it exactly ONCE,
// after bidding genuinely completes.
require(__REPO_ROOT__ + "/design-ui/engine/table-engine.js");

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var TableEngine = global.TableEngine;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

/** Drives one full, deterministic bidding round through the REAL
 *  bidding-engine.js: everyone declines Dash, the first bidder bids
 *  `tricks`/`suit`, everyone else passes, the bidder confirms, and
 *  every non-caller estimates 0 (always legal — the caller's own
 *  estimate is fixed automatically at Confirm time, per the real rule
 *  already exercised elsewhere this session). Returns the real,
 *  final BiddingEngine state and the auction opener's seat. */
function driveBiddingRound(tricks, suit) {
  for (var i = 0; i < 4; i++) {
    var s = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false });
  }
  var s2 = BiddingEngine.getState();
  var opener = s2.waitingFor;
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: opener, tricks: tricks, suit: suit, isPass: false });
  var s3 = BiddingEngine.getState();
  while (s3.subPhase === "AUCTION") {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s3.waitingFor, isPass: true });
    s3 = BiddingEngine.getState();
  }
  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s3.waitingFor, tricks: s3.auctionTop, suit: s3.auctionSuit });
  var s4 = BiddingEngine.getState();
  while (s4.subPhase === "ESTIMATES") {
    BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: s4.waitingFor, tricks: 0 });
    s4 = BiddingEngine.getState();
  }
  return { finalState: s4, opener: opener };
}

/** Drives the REAL TableEngine through a full round of trick play
 *  (13 tricks) via the real engine's own emit()/resolveTrick() — the
 *  same "leader's own hand, first card, follow-suit-if-possible"
 *  drive pattern tests/match-flow-integration.test.cjs already
 *  establishes. Never decides who WINS a trick — only what gets
 *  played, deterministically, so the round reaches DONE. */
function driveFullRound() {
  function legalCardFor(seatId) {
    var st = TableEngine.getState();
    var hand = st.hands[seatId];
    if (!st.ledSuit) return hand[0];
    var inSuit = hand.filter(function (c) { return c.suit === st.ledSuit; });
    return (inSuit.length ? inSuit : hand)[0];
  }
  var guard = 0;
  while (TableEngine.getState().phase !== "DONE" && guard < 4000) {
    var st = TableEngine.getState();
    if (st.phase === "PLAY") {
      TableEngine.emit({ type: "PlayCard", playerId: st.turn, card: legalCardFor(st.turn) });
    } else if (st.phase === "RESOLVING") {
      TableEngine.resolveTrick();
    }
    guard++;
  }
}

(function () {
  BiddingEngine.initState();

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 1 — page-load module state must not survive as the
  // ACTIVE round configuration once real bidding has completed and
  // TableEngine is initialized for the first time.
  // ════════════════════════════════════════════════════════════════
  check("Scenario 1 setup: no bidding has happened yet — GameSession's bidding state is not completed", !GameSession.getBiddingState().completed);

  driveBiddingRound(7, "HEARTS");
  var round1Round = GameSession.getRound();
  check("Scenario 1 setup: real bidding actually completed with a real outcome (trump=HEARTS, caller=p1)",
    round1Round.trump === "HEARTS" && round1Round.callerId === "p1");

  // TableEngine.initState() called for the FIRST time — AFTER bidding
  // completed, exactly as this sprint's own UI implementation calls it
  // (never before). The module itself was required back at page-load
  // time, above, before any of this bidding happened.
  TableEngine.initState();
  var afterReal = TableEngine.getState();
  check("Scenario 1 (THE FIX): TableEngine.trump reflects the CURRENT round config, not the documented page-load mock default (SPADES)", afterReal.trump === "HEARTS");
  check("Scenario 1 (THE FIX): TableEngine.callerId reflects the CURRENT round config, not the documented page-load mock default (p4)", afterReal.callerId === "p1");
  check("Scenario 1 (THE FIX): TableEngine.withPlayers reflects the CURRENT round config", JSON.stringify(afterReal.withPlayers) === JSON.stringify(round1Round.withPlayers));
  check("Scenario 1 (THE FIX): TableEngine.estimates reflects the CURRENT round config", JSON.stringify(afterReal.estimates) === JSON.stringify(round1Round.estimates));
  check("Scenario 1 (THE FIX): TableEngine.leaderId is the real caller (p1), not the page-load mock fallback (p4)", afterReal.leaderId === "p1");
  check("Scenario 1 (THE FIX): TableEngine.turn starts on the real leader (p1)", afterReal.turn === "p1");
  check("Scenario 1 (THE FIX): TableEngine.round matches the real round number", afterReal.round === round1Round.number);

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 2 — round-1 config must not bleed into round 2.
  // ════════════════════════════════════════════════════════════════
  driveFullRound();
  check("Scenario 2 setup: round 1 actually reached DONE via the real engine (13 tricks played)", TableEngine.getState().phase === "DONE");
  var round1Trump = afterReal.trump, round1Number = afterReal.round;

  // Start round 2 with a DIFFERENT, deterministic bidding outcome.
  GameSession.setRound({ number: round1Number + 1 });
  BiddingEngine.initState();
  var round2Result = driveBiddingRound(4, "CLUBS");
  var round2Round = GameSession.getRound();
  check("Scenario 2 setup: round 2's real bidding outcome genuinely differs from round 1's (trump=CLUBS vs HEARTS)",
    round2Round.trump === "CLUBS" && round2Round.trump !== round1Trump && round2Round.number === round1Number + 1);

  GameSession.clearPlayState();
  TableEngine.initState();
  var round2State = TableEngine.getState();
  check("Scenario 2 (THE FIX): round 2's TableEngine.trump is CLUBS, not round 1's stale HEARTS", round2State.trump === "CLUBS");
  check("Scenario 2 (THE FIX): round 2's TableEngine.callerId matches round 2's real caller, not round 1's", round2State.callerId === round2Round.callerId);
  check("Scenario 2 (THE FIX): round 2's TableEngine.estimates matches round 2's real estimates, not round 1's", JSON.stringify(round2State.estimates) === JSON.stringify(round2Round.estimates));
  check("Scenario 2 (THE FIX): round 2's TableEngine.round is the NEW round number, not round 1's", round2State.round === round1Number + 1 && round2State.round !== round1Number);

  // ════════════════════════════════════════════════════════════════
  // Structural guard: buildRoundCfg()'s own logic (the mock-fallback
  // formula, the leaderId derivation, the hasBidResult branching) was
  // NOT rewritten by this fix — only WHEN it is invoked changed. Read
  // directly from source, the same "prove the fix didn't touch the
  // formula" convention this session's own Sprint 3.7.x report used.
  // ════════════════════════════════════════════════════════════════
  var fs = require("fs");
  var src = fs.readFileSync(__REPO_ROOT__ + "/design-ui/engine/table-engine.js", "utf8");
  var fnStart = src.indexOf("function buildRoundCfg() {");
  var fnEnd = src.indexOf("\n}\n", fnStart);
  var fnBody = src.slice(fnStart, fnEnd);
  check("Structural: buildRoundCfg()'s own hasBidResult/mock-fallback formula is unchanged (still present verbatim)",
    fnBody.indexOf('hasBidResult ? (r.callerId || GameSession.getTurn() || GameSession.getDealer()) : "p4"') !== -1 &&
    fnBody.indexOf('hasBidResult ? r.trump : "SPADES"') !== -1);
  check("Structural: ROUND_CFG is now reassigned (not merely read) inside initState()", src.indexOf("ROUND_CFG = buildRoundCfg();\n\n  // Card Engine") !== -1 || (function () {
    var initIdx = src.indexOf("function initState() {");
    var nextFnIdx = src.indexOf("function pushLog(", initIdx);
    var body = src.slice(initIdx, nextFnIdx);
    return body.indexOf("ROUND_CFG = buildRoundCfg();") !== -1;
  })());

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
