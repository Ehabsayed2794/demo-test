// Real, executable tests for Sprint 4.2 (Online Card Synchronization:
// Engine Authority), Task 1 — MatchService.submitCard(matchId, card) —
// HARDENED in Sprint 4.2.1 (Pre-Write Card Authority & Desync Safety)
// with a turn-authority gate (Task 1) and a pre-write engine-legality
// gate (Task 2). Mirrors tests/submit-bid.test.cjs's exact mock/
// structure (same combined transaction + onSnapshot fake Firestore),
// adapted for submitCard()'s one deliberate signature difference: no
// `seatId` parameter — the acting seat is resolved internally via
// MatchAdapter.uidToSeat(), never trusted as a client-supplied claim.
//
// LABELING: every check below is MOCKED — real design-ui/match-service.js
// and real design-ui/match-adapter.js code, exercised against a
// hand-written fake Firestore AND a hand-written fake TableEngine (this
// file's own — controllable per test, mirroring match-adapter.test.cjs's
// own fakeTableEngine() pattern; the REAL table-engine.js is exercised
// end-to-end in tests/card-sync.test.cjs instead, since it needs a real,
// committed bidding round to initialize meaningfully). No SIMULATED
// checks in this file (those live in tests/rules-simulation.test.js).
// No real Firestore project, Firebase Emulator, or browser was used.
global.window = global;

var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var ONSNAPSHOT_CALLS = {};
var FIRESTORE_AVAILABLE = true;
var pendingErrorCallbacks = {};
var idCounter = 0;

function key(id) { return "matches/" + id; }
function notify(k) {
  (LISTENERS[k] || []).forEach(function (cb) {
    var exists = Object.prototype.hasOwnProperty.call(STORE, k);
    cb({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
  });
}
function makeMatchRef(id) {
  var k = key(id);
  return {
    id: id, _key: k,
    get: function () {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    update: function (patch) {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      STORE[k] = Object.assign({}, STORE[k], patch);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      notify(k);
      return Promise.resolve();
    },
    onSnapshot: function (onNext, onError) {
      ONSNAPSHOT_CALLS[k] = (ONSNAPSHOT_CALLS[k] || 0) + 1;
      if (!FIRESTORE_AVAILABLE) {
        var err = new Error("simulated Firestore unavailable"); err.code = "unavailable";
        onError(err);
        return function () {};
      }
      LISTENERS[k] = LISTENERS[k] || [];
      LISTENERS[k].push(onNext);
      pendingErrorCallbacks[k] = pendingErrorCallbacks[k] || [];
      pendingErrorCallbacks[k].push(onError);
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      onNext({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
      return function unsubscribe() {
        LISTENERS[k] = (LISTENERS[k] || []).filter(function (cb) { return cb !== onNext; });
        pendingErrorCallbacks[k] = (pendingErrorCallbacks[k] || []).filter(function (cb) { return cb !== onError; });
      };
    }
  };
}
var FAKE_DB = {
  collection: function (name) {
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { if (!id) id = "match-" + (++idCounter); return makeMatchRef(id); } };
  },
  runTransaction: function (fn, attempt) {
    if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
    var seenVersions = {}, pending = {};
    var tx = {
      get: function (ref) { seenVersions[ref._key] = DOC_VERSION[ref._key] || 0; return ref.get(); },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var keys = Object.keys(pending);
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      keys.forEach(function (k) { STORE[k] = Object.assign({}, STORE[k], pending[k].data); DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1; });
      keys.forEach(function (k) { notify(k); });
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

require("/home/user/demo-test/design-ui/match-service.js");
require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
require("/home/user/demo-test/design-ui/engine/session.js");
require("/home/user/demo-test/design-ui/match-adapter.js"); // needed: submitCard() calls MatchAdapter.uidToSeat()/assertLocalTurn()
var MatchService = global.MatchService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

/** A controllable fake TableEngine — Task 2's pre-write engine gate.
 *  Default: every card is legal. Tests that need an ILLEGAL_CARD
 *  rejection override `_legal` (or pass a card matching `_illegalCard`)
 *  before calling submitCard(). Records every call for "was the
 *  engine asked exactly once, never twice" assertions. */
function installFakeTableEngine() {
  var calls = [];
  var engine = {
    _calls: calls,
    _legal: true,
    _reason: "ILLEGAL_CARD",
    canPlayCard: function (playerId, card) {
      calls.push({ playerId: playerId, card: card });
      return engine._legal ? { legal: true } : { legal: false, reason: engine._reason };
    }
  };
  global.TableEngine = engine;
  return engine;
}

function seedMatch(id, overrides) {
  var base = {
    roomId: "room-x", players: ["userA", "userB"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "userA", turn: "userA", gameState: { initialized: false },
    seats: { p1: "userA", p2: "userB" }, version: 1, biddingOpen: false,
    bids: { p1: 4, p2: 3 }, lastBidSeat: "p2",
    cardLog: [], lastCardSeat: null
  };
  STORE[key(id)] = Object.assign(base, overrides || {});
  DOC_VERSION[key(id)] = (DOC_VERSION[key(id)] || 0) + 1;
  return STORE[key(id)];
}
/** Directly sets which uid's turn it is on an already-seeded match —
 *  test-only convenience, mirrors how a real turn-sync delivery would
 *  update this same field (Sprint 4.1), without needing the full sync
 *  pipeline for these unit-level submitCard() tests. */
function setTurn(id, uid) { STORE[key(id)] = Object.assign({}, STORE[key(id)], { turn: uid }); }

var QUEEN_SPADES = { suit: "SPADES", rank: { v: 12, s: "Q" } };

(async function () {
  var fakeEngine;

  // ============================================================
  // MOCKED — Normal card submission (correct turn, engine accepts)
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-normal"); // turn: userA, userA owns p1
  signInAs("userA");
  var result = await MatchService.submitCard("m-normal", QUEEN_SPADES);
  check("MOCKED — normal card: submitCard() resolves with the expected result shape", result.matchId === "m-normal" && result.seatId === "p1" && result.cardCount === 1);
  check("MOCKED — normal card: version incremented by exactly 1", result.version === 2 && STORE[key("m-normal")].version === 2);
  check("MOCKED — normal card: the card is appended to cardLog under the correct (RESOLVED, not client-claimed) seat",
    STORE[key("m-normal")].cardLog.length === 1 && STORE[key("m-normal")].cardLog[0].seatId === "p1");
  check("MOCKED — normal card: the card is stored with the exact suit/rank submitted",
    STORE[key("m-normal")].cardLog[0].card.suit === "SPADES" && STORE[key("m-normal")].cardLog[0].card.rank.v === 12);
  check("MOCKED — normal card: lastCardSeat reflects the resolved seat", STORE[key("m-normal")].lastCardSeat === "p1");
  check("MOCKED — Task 2: the real (fake, here) engine was asked exactly once — never twice, never emitted twice",
    fakeEngine._calls.length === 1 && fakeEngine._calls[0].playerId === "p1");

  // ============================================================
  // MOCKED — Sprint 4.2.1, Task 1 / Test #1+#2: wrong-turn rejection,
  // correct-turn success — the CENTRAL fix this hotfix delivers.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-turn", { turn: "userB" }); // it's userB's (p2's) turn
  signInAs("userA"); // userA owns p1 — NOT the current turn
  var wrongTurnErr = null;
  try { await MatchService.submitCard("m-turn", QUEEN_SPADES); } catch (e) { wrongTurnErr = e; }
  check("MOCKED — Test #1 (wrong-turn rejection): a wrong-turn player is rejected NOT_YOUR_TURN",
    wrongTurnErr && wrongTurnErr.reason === "NOT_YOUR_TURN");
  check("MOCKED — Test #1: zero Firestore writes occurred for the wrong-turn attempt — version/cardLog completely untouched",
    STORE[key("m-turn")].version === 1 && STORE[key("m-turn")].cardLog.length === 0);
  check("MOCKED — Test #1: the engine was never even asked to validate a card that never should have reached that gate",
    fakeEngine._calls.length === 0);

  signInAs("userB"); // userB owns p2 — DOES own the current turn
  var correctTurnResult = await MatchService.submitCard("m-turn", QUEEN_SPADES);
  check("MOCKED — Test #2 (correct-turn success): the correct-turn player's submission succeeds normally",
    correctTurnResult.seatId === "p2" && correctTurnResult.version === 2);
  check("MOCKED — Test #2: the card is durably recorded for the correct seat", STORE[key("m-turn")].cardLog[0].seatId === "p2");

  // ============================================================
  // MOCKED — Test #3: an illegal (engine-rejected) card is rejected
  // BEFORE persistence — the SECOND central fix this hotfix delivers.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  fakeEngine._legal = false;
  fakeEngine._reason = "ILLEGAL_CARD";
  seedMatch("m-illegal"); // turn: userA, userA owns p1 — turn gate passes
  signInAs("userA");
  var illegalErr = null;
  try { await MatchService.submitCard("m-illegal", { suit: "HEARTS", rank: { v: 2, s: "2" } }); } catch (e) { illegalErr = e; }
  check("MOCKED — Test #3 (illegal follow-suit rejected before persistence): submitCard() rejects ILLEGAL_CARD when the real engine says the play is illegal",
    illegalErr && illegalErr.reason === "ILLEGAL_CARD");
  check("MOCKED — Test #3: zero Firestore writes occurred — cardLog was NEVER appended to, unlike Sprint 4.2's original defect where the invalid entry stayed in cardLog permanently",
    STORE[key("m-illegal")].version === 1 && STORE[key("m-illegal")].cardLog.length === 0);
  check("MOCKED — Test #3: the engine was asked exactly once (queried for validation, never actually emitted/mutated)",
    fakeEngine._calls.length === 1);

  // ============================================================
  // MOCKED — Test #4: a card not owned by the player (not in hand) is
  // rejected before persistence — the SAME canPlayCard() gate covers
  // ownership as a byproduct of table-engine.js's own isLegal(), which
  // only ever matches against the claimed seat's OWN hand.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  fakeEngine._legal = false;
  fakeEngine._reason = "ILLEGAL_CARD"; // table-engine.js's real isLegal() returns this same generic reason whether the card is off-suit OR simply not in hand — see this function's own comment
  seedMatch("m-notinhand");
  signInAs("userA");
  var notInHandErr = null;
  try { await MatchService.submitCard("m-notinhand", { suit: "CLUBS", rank: { v: 9, s: "9" } }); } catch (e) { notInHandErr = e; }
  check("MOCKED — Test #4 (card not owned/not in hand rejected before persistence): submitCard() rejects ILLEGAL_CARD via the SAME engine gate that also catches off-suit plays",
    notInHandErr && notInHandErr.reason === "ILLEGAL_CARD" && STORE[key("m-notinhand")].cardLog.length === 0);

  // ============================================================
  // MOCKED — Test #5+#6: a legal card is written EXACTLY once; the
  // local echo (this SAME client's own submission, observed again via
  // its own subscription) does not execute a second write on its own
  // account — this is MatchAdapter.applyRemoteCard()'s own, unchanged
  // content-level idempotency, verified here at the submitCard() call
  // boundary: calling submitCard() once produces exactly one cardLog
  // entry and exactly one engine validation call.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-exactly-once");
  signInAs("userA");
  await MatchService.submitCard("m-exactly-once", QUEEN_SPADES);
  check("MOCKED — Test #5 (written exactly once): a single submitCard() call produces exactly one cardLog entry",
    STORE[key("m-exactly-once")].cardLog.length === 1);
  check("MOCKED — Test #6 (no double execution): the engine validation gate was consulted exactly once for this one submission — submitCard() never calls canPlayCard() twice for one card",
    fakeEngine._calls.length === 1);

  // ============================================================
  // MOCKED — Sequential cards (different seats, append-only) — same
  // coverage as Sprint 4.2's original test, now correctly threading
  // the turn field between submissions (the hotfix's own gate would
  // otherwise reject these).
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-seq");
  signInAs("userA");
  await MatchService.submitCard("m-seq", QUEEN_SPADES);
  setTurn("m-seq", "userB");
  signInAs("userB");
  var resultSeq2 = await MatchService.submitCard("m-seq", { suit: "SPADES", rank: { v: 5, s: "5" } });
  check("MOCKED — sequential cards: a second submission from a different seat, once it's genuinely their turn, appends a SECOND entry, does not overwrite the first",
    STORE[key("m-seq")].cardLog.length === 2 && STORE[key("m-seq")].cardLog[0].seatId === "p1" && STORE[key("m-seq")].cardLog[1].seatId === "p2");
  check("MOCKED — sequential cards: version incremented again by exactly 1", resultSeq2.version === 3);

  // ============================================================
  // MOCKED — Seat resolution: uid -> seat is derived internally, NEVER
  // trusted from a client-supplied parameter (there is none).
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-seat-resolve", { turn: "userB" });
  signInAs("userB");
  var resultSeat = await MatchService.submitCard("m-seat-resolve", QUEEN_SPADES);
  check("MOCKED — seat resolution: userB correctly resolves to p2 (this match's OWN seat map), not p1",
    resultSeat.seatId === "p2" && STORE[key("m-seat-resolve")].lastCardSeat === "p2");

  // ============================================================
  // MOCKED — SECURITY: a uid that owns NO seat in this match
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-no-seat");
  signInAs("userZ"); // not a player in this match at all
  var noSeatErr = null;
  try { await MatchService.submitCard("m-no-seat", QUEEN_SPADES); } catch (e) { noSeatErr = e; }
  check("MOCKED — SECURITY: a uid owning no seat in this match is rejected PERMISSION_DENIED, never allowed to guess/claim a seat",
    noSeatErr && noSeatErr.reason === "PERMISSION_DENIED");
  check("MOCKED — SECURITY: the rejected attempt left cardLog completely untouched", STORE[key("m-no-seat")].cardLog.length === 0);
  check("MOCKED — SECURITY: the engine was never even asked — the authority gate rejects before Task 2's gate runs at all", fakeEngine._calls.length === 0);

  // ============================================================
  // MOCKED — Generic card-shape validation (NOT legality — see
  // isValidGenericCardValue()'s own comment in match-service.js)
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-shape");
  signInAs("userA");
  function expectRejected(card, label) {
    return (async function () {
      var err = null;
      try { await MatchService.submitCard("m-shape", card); } catch (e) { err = e; }
      check("MOCKED — generic card validation: " + label, err && err.reason === "INVALID_CARD_VALUE");
    })();
  }
  await expectRejected(null, "null is rejected");
  await expectRejected(undefined, "undefined is rejected");
  await expectRejected("SPADES-Q", "a string is rejected, never coerced");
  await expectRejected({ suit: "STARS", rank: { v: 10, s: "10" } }, "an unknown suit key is rejected");
  await expectRejected({ suit: "SPADES", rank: { v: 1, s: "1" } }, "rank.v below the minimum (1) is rejected");
  await expectRejected({ suit: "SPADES", rank: { v: 15, s: "15" } }, "rank.v above the maximum (15) is rejected");
  await expectRejected({ suit: "SPADES", rank: { v: NaN, s: "?" } }, "a NaN rank.v is rejected");
  await expectRejected({ suit: "SPADES" }, "a missing rank field entirely is rejected");
  await expectRejected({ rank: { v: 10, s: "10" } }, "a missing suit field entirely is rejected");
  check("MOCKED — generic card validation: every rejected attempt above left cardLog completely untouched",
    STORE[key("m-shape")].cardLog.length === 0);
  check("MOCKED — generic card validation: the engine was never asked for any of these — shape rejection happens before the authority/engine gates even run",
    fakeEngine._calls.length === 0);
  var validAfterRejections = await MatchService.submitCard("m-shape", QUEEN_SPADES);
  check("MOCKED — generic card validation: a genuinely valid card AFTER several rejections still succeeds normally",
    validAfterRejections.cardCount === 1);

  // ============================================================
  // MOCKED — Failure paths: missing args, no signed-in user, match not
  // found, Firestore unavailable, MatchAdapter unavailable, TableEngine
  // unavailable (Sprint 4.2.1's new "cannot write blind" gate).
  // ============================================================
  var noMatchIdErr = null;
  try { await MatchService.submitCard(null, QUEEN_SPADES); } catch (e) { noMatchIdErr = e; }
  check("MOCKED — failure: missing matchId rejects INVALID_ARGUMENT", noMatchIdErr && noMatchIdErr.reason === "INVALID_ARGUMENT");

  CURRENT_USER = null; // signed out
  var noUserErr = null;
  try { await MatchService.submitCard("m-shape", QUEEN_SPADES); } catch (e) { noUserErr = e; }
  check("MOCKED — failure: no signed-in user rejects UNAUTHENTICATED", noUserErr && noUserErr.reason === "UNAUTHENTICATED");
  signInAs("userA");

  var notFoundErr = null;
  try { await MatchService.submitCard("m-does-not-exist", QUEEN_SPADES); } catch (e) { notFoundErr = e; }
  check("MOCKED — failure: a non-existent match rejects MATCH_NOT_FOUND", notFoundErr && notFoundErr.reason === "MATCH_NOT_FOUND");

  var cardCountBeforeOffline = STORE[key("m-shape")].cardLog.length;
  FIRESTORE_AVAILABLE = false;
  var unavailableErr = null;
  try { await MatchService.submitCard("m-shape", QUEEN_SPADES); } catch (e) { unavailableErr = e; }
  check("MOCKED — failure: a write attempted while Firestore is unavailable rejects cleanly, never silently succeeds, never crashes", !!unavailableErr);
  check("MOCKED — failure recovery: the stored document is completely untouched by the failed attempt", STORE[key("m-shape")].cardLog.length === cardCountBeforeOffline);
  FIRESTORE_AVAILABLE = true;

  var savedAdapter = global.MatchAdapter;
  global.MatchAdapter = undefined;
  var noAdapterErr = null;
  try { await MatchService.submitCard("m-shape", QUEEN_SPADES); } catch (e) { noAdapterErr = e; }
  check("MOCKED — failure: MatchAdapter unavailable (Task 1's own 'Calls MatchAdapter only' dependency) rejects MATCH_ADAPTER_UNAVAILABLE, never throws unhandled",
    noAdapterErr && noAdapterErr.reason === "MATCH_ADAPTER_UNAVAILABLE");
  global.MatchAdapter = savedAdapter;

  var savedEngine = global.TableEngine;
  global.TableEngine = undefined;
  var noEngineErr = null;
  try { await MatchService.submitCard("m-shape", QUEEN_SPADES); } catch (e) { noEngineErr = e; }
  check("MOCKED — failure (Sprint 4.2.1, Task 2): TableEngine unavailable rejects ENGINE_UNAVAILABLE — submitCard() refuses to write blind rather than skipping validation silently",
    noEngineErr && noEngineErr.reason === "ENGINE_UNAVAILABLE");
  check("MOCKED — Task 2: no write occurred when the engine couldn't be asked at all", STORE[key("m-shape")].cardLog.length === cardCountBeforeOffline);
  global.TableEngine = savedEngine;

  // ============================================================
  // MOCKED — Realtime synchronization: submitCard()'s write is
  // delivered through the EXISTING, unmodified subscribeToMatch() pipe
  // — zero changes to that function, same guarantee submitBid() proved
  // in Sprint 3.8.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-realtime");
  var deliveries = [];
  var unsub = MatchService.subscribeToMatch("m-realtime", function (data, err) { if (!err) deliveries.push(data); });
  var onSnapshotCallsBefore = ONSNAPSHOT_CALLS[key("m-realtime")];
  signInAs("userA");
  await MatchService.submitCard("m-realtime", QUEEN_SPADES);
  check("MOCKED — realtime sync: the existing subscription observed the card write live", deliveries.length === 2 && deliveries[1].cardLog.length === 1);
  check("MOCKED — realtime sync: no second Firestore listener was created by submitCard() itself", ONSNAPSHOT_CALLS[key("m-realtime")] === onSnapshotCallsBefore);
  unsub();

  // ============================================================
  // MOCKED — Test #10 (no gameplay rules duplicated outside
  // TableEngine): structural check that match-service.js's own source
  // contains no reimplementation of follow-suit/turn-order logic — it
  // only ever CALLS into MatchAdapter/TableEngine, never recomputes
  // either decision itself.
  // ============================================================
  var fs = require("fs");
  var serviceSource = fs.readFileSync("/home/user/demo-test/design-ui/match-service.js", "utf8");
  check("MOCKED — Test #10: match-service.js contains no follow-suit/ledSuit logic of its own — the only suit-related code is the GENERIC shape check's suit-name whitelist",
    !/ledSuit/.test(serviceSource) && !/legalCards/.test(serviceSource));
  check("MOCKED — Test #10: match-service.js never calls a GameSession/TableEngine setter directly to mutate gameplay state — its only TableEngine reference is the read-only canPlayCard() query",
    !/TableEngine\.emit/.test(serviceSource) && !/GameSession\.setTurn/.test(serviceSource));

  // ============================================================
  // MOCKED — Regression: MatchService's other stubs and existing API
  // are untouched by this sprint's addition.
  // ============================================================
  check("MOCKED — regression: MatchService.submitBid (Sprint 3.8) is still present and unchanged in shape", typeof MatchService.submitBid === "function");
  check("MOCKED — regression: MatchService.playCard remains the OLD, never-adopted, still-unimplemented stub (a DIFFERENT method from submitCard)", (function () {
    try { MatchService.playCard("x", "y", "z"); return false; } catch (e) { return /not implemented/i.test(e.message); }
  })());

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
