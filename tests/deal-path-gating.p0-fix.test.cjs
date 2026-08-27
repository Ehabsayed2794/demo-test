var REPO_ROOT = require("path").join(__dirname, "..");
// TASK F1 — P0 SECURITY FIX VERIFICATION (MOCKED convention: real
// design-ui/match-adapter.js + design-ui/engine/session.js + engines,
// hand-constructed plain-object docs / stubbed MatchService; no Firestore).
//
// F1-1: maybeDealRound()'s attempt path is DEALER-GATED — a non-dealer
//        seat's snapshot must NEVER reach MatchService.dealRound().
// F1-2: switching hand-authority to "firestore" wipes any cached LOCAL
//        fallback shuffle; only setAuthoritativeHand() data renders after.
// F1-3: a rejected dealRound() logs its FULL denial body (code/message)
//        instead of the previous silent empty .catch.
//
// Regression guard: startHandSync's own deal trigger must STILL work for
// the dealer (hand-sync.test.cjs test 14 semantics preserved).
global.window = global;
global.window.addEventListener = function () {};

require(REPO_ROOT + "/design-ui/engine/cards.js");
require(REPO_ROOT + "/design-ui/engine/deck.js");
require(REPO_ROOT + "/design-ui/engine/dealer.js");
require(REPO_ROOT + "/design-ui/engine/session.js");
require(REPO_ROOT + "/design-ui/match-adapter.js");

var GameSession = global.GameSession;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function tick() { return new Promise(function (r) { setTimeout(r, 0); }); }

// ── Production identity facts (golden-prod-evidence/evidence.jsonl) ──
var UID_P1 = "CBuxgr72uVUpFbUWF29zKB37Dzs1";
var UID_P2 = "84a7hj8FRsSwZpmmmmLjE1aEQGC2";
var UID_P3 = "BeD0HLm7Fvdd4ZEJR7oVvjez5es2";
var UID_P4 = "S1WrTtnnAtUjFgj65rF84SocgS93";

function undealtDoc() {
  return {
    roomId: "room-x", players: [UID_P1, UID_P2, UID_P3, UID_P4],
    status: "starting", createdAt: 1, currentRound: 1,
    dealer: UID_P1, turn: UID_P1,
    seats: { p1: UID_P1, p2: UID_P2, p3: UID_P3, p4: UID_P4 },
    version: 1, biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null },
    lastBidSeat: null, cardLog: [], lastCardSeat: null,
    cardPhase: null, biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };
}

// Stub MatchService: captures subscribe callbacks so tests deliver
// snapshots manually, and spies on dealRound().
function installMatchServiceStub() {
  var stub = {
    calls: [],
    rejectNext: false,
    _matchCb: {}, _handCb: {},
    dealRound: function (id, round) {
      stub.calls.push([id, round]);
      var err = new Error("7 PERMISSION_DENIED: simulated denial body for " + id);
      err.code = "permission-denied";
      return stub.rejectNext ? Promise.reject(err) : Promise.resolve({ dealt: true });
    },
    subscribeToMatch: function (id, cb) {
      stub._matchCb[id] = cb;
      return function () { delete stub._matchCb[id]; };
    },
    subscribeToHand: function (id, seat, cb) {
      stub._handCb[id + "/" + seat] = cb;
      return function () { delete stub._handCb[id + "/" + seat]; };
    },
    deliver: function (id, doc) {
      if (stub._matchCb[id]) stub._matchCb[id](doc, null);
    }
  };
  global.MatchService = stub;
  return stub;
}

(async function main() {
  console.log("── F1-1 dealer-seat gate on the adapter attempt path ──");

  // A1: NON-dealer seat (p4 — the production 403 client) observing the
  // undealt match must never call dealRound().
  var svc = installMatchServiceStub();
  var stopA = MatchAdapter.startHandSync("gate-a", "p4");
  svc.deliver("gate-a", undealtDoc());
  await tick(); await tick();
  check("A1 non-dealer p4 snapshot -> dealRound NEVER called (" + svc.calls.length + " calls)",
    svc.calls.length === 0);
  stopA();

  // A2: the DEALER seat observing the same snapshot attempts exactly once.
  svc = installMatchServiceStub();
  var stopB = MatchAdapter.startHandSync("gate-b", "p1");
  svc.deliver("gate-b", undealtDoc());
  await tick(); await tick();
  check("A2 dealer p1 snapshot -> dealRound called exactly once",
    svc.calls.length === 1 && svc.calls[0][0] === "gate-b" && svc.calls[0][1] === 1);
  stopB();

  // A3: once-per-round guard intact for the dealer across repeat snapshots.
  svc.deliver("gate-b", undealtDoc());
  await tick();
  check("A3 repeat snapshot, same round -> still exactly one call",
    svc.calls.length === 1);

  // A4: unresolvable dealer (null) -> nobody attempts.
  svc = installMatchServiceStub();
  var docNoDealer = undealtDoc(); docNoDealer.dealer = null;
  var stopC = MatchAdapter.startHandSync("gate-c", "p1");
  svc.deliver("gate-c", docNoDealer);
  await tick();
  check("A4 null dealer -> no attempt", svc.calls.length === 0);
  stopC();

  // A5: dealer not seated in THIS match's map -> no attempt.
  svc = installMatchServiceStub();
  var docGhostDealer = undealtDoc(); docGhostDealer.dealer = "ghost-uid";
  var stopD = MatchAdapter.startHandSync("gate-d", "p1");
  svc.deliver("gate-d", docGhostDealer);
  await tick();
  check("A5 unseated dealer -> no attempt", svc.calls.length === 0);
  stopD();

  console.log("\n── F1-3 full denial-body logging on swallowed rejection ──");
  svc = installMatchServiceStub();
  svc.rejectNext = true;
  var captured = [];
  var origErr = console.error;
  console.error = function () { captured.push(Array.prototype.slice.call(arguments)); };
  var stopE = MatchAdapter.startHandSync("log-e", "p1");
  svc.deliver("log-e", undealtDoc());
  await tick(); await tick();
  console.error = origErr;
  var logged = JSON.stringify(captured);
  check("E1 rejection logged with code+message (not silent)",
    captured.length > 0 && logged.indexOf("permission-denied") !== -1 &&
    logged.indexOf("simulated denial body") !== -1 && logged.indexOf("stack") !== -1);
  stopE();

  console.log("\n── F1-2 authority switch clears cached local fallback hands ──");
  // Fresh session in LOCAL mode; deal locally via the engine's own path.
  global.GameSession.reset(null);
  GameSession.setHandAuthorityMode("local");
  var localHands = GameSession.ensureHandsDealt();
  var hadLocalShuffle = Object.keys(localHands).length === 4 &&
    GameSession.getHand("p1").length === 13;
  check("B1 precondition: local fallback shuffle present before switch", hadLocalShuffle);

  // The switch to firestore authority MUST wipe it.
  GameSession.setHandAuthorityMode("firestore");
  check("B2 getHands() empty after switch", Object.keys(GameSession.getHands()).length === 0);
  check("B3 getHand('p1') can never surface the private shuffle", GameSession.getHand("p1").length === 0);
  check("B4 hasDealtHands() false after switch", !GameSession.hasDealtHands());

  // Authoritative data then applies cleanly and survives re-declaration.
  var fakeCards = [{ id: "SPADES-14-1", suit: "SPADES", rank: { v: 14, s: "A" }, displayName: "A♠", value: 14, owner: "p1", played: false }];
  GameSession.setAuthoritativeHand("p1", fakeCards, 1);
  check("B5 setAuthoritativeHand() populates post-switch",
    JSON.stringify(GameSession.getHand("p1")) === JSON.stringify(fakeCards));
  GameSession.setHandAuthorityMode("firestore"); // re-declare, NOT a transition
  check("B6 re-declaring firestore does NOT wipe authoritative data",
    JSON.stringify(GameSession.getHand("p1")) === JSON.stringify(fakeCards));

  // Local mode remains untouched by the fix (backward compatibility).
  GameSession.reset(null);
  GameSession.setHandAuthorityMode("local");
  GameSession.ensureHandsDealt();
  GameSession.setHandAuthorityMode("local");
  check("B7 local->local re-declaration never wipes local hands",
    GameSession.hasDealtHands() && Object.keys(GameSession.getHands()).length === 4);

  console.log("\n=== RESULTS ===");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
})().catch(function (e) { console.error("FATAL:", e); process.exit(1); });
