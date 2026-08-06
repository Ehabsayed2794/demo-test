// Real, executable tests for Sprint 4.2 (Online Card Synchronization:
// Engine Authority), Task 1 — MatchService.submitCard(matchId, card).
// Mirrors tests/submit-bid.test.cjs's exact mock/structure (same
// combined transaction + onSnapshot fake Firestore), adapted for
// submitCard()'s one deliberate signature difference: no `seatId`
// parameter — the acting seat is resolved internally via
// MatchAdapter.uidToSeat(), never trusted as a client-supplied claim.
//
// LABELING: every check below is MOCKED — real design-ui/match-service.js
// and real design-ui/match-adapter.js code (its uidToSeat() is pure and
// needs no GameSession at all), exercised against a hand-written fake
// Firestore. No SIMULATED checks in this file (those live in
// tests/rules-simulation.test.js). No real Firestore project, Firebase
// Emulator, or browser was used — consistent with every prior sprint.
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
require("/home/user/demo-test/design-ui/match-adapter.js"); // needed: submitCard() calls MatchAdapter.uidToSeat()
var MatchService = global.MatchService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
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

var QUEEN_SPADES = { suit: "SPADES", rank: { v: 12, s: "Q" } };

(async function () {
  // ============================================================
  // MOCKED — Normal card submission
  // ============================================================
  seedMatch("m-normal");
  signInAs("userA"); // owns p1
  var result = await MatchService.submitCard("m-normal", QUEEN_SPADES);
  check("MOCKED — normal card: submitCard() resolves with the expected result shape", result.matchId === "m-normal" && result.seatId === "p1" && result.cardCount === 1);
  check("MOCKED — normal card: version incremented by exactly 1", result.version === 2 && STORE[key("m-normal")].version === 2);
  check("MOCKED — normal card: the card is appended to cardLog under the correct (RESOLVED, not client-claimed) seat",
    STORE[key("m-normal")].cardLog.length === 1 && STORE[key("m-normal")].cardLog[0].seatId === "p1");
  check("MOCKED — normal card: the card is stored with the exact suit/rank submitted",
    STORE[key("m-normal")].cardLog[0].card.suit === "SPADES" && STORE[key("m-normal")].cardLog[0].card.rank.v === 12);
  check("MOCKED — normal card: lastCardSeat reflects the resolved seat", STORE[key("m-normal")].lastCardSeat === "p1");

  // ============================================================
  // MOCKED — Multiple sequential cards (different seats, append-only)
  // ============================================================
  signInAs("userB"); // owns p2
  var result2 = await MatchService.submitCard("m-normal", { suit: "SPADES", rank: { v: 5, s: "5" } });
  check("MOCKED — sequential cards: a second submission from a different seat appends a SECOND entry, does not overwrite the first",
    STORE[key("m-normal")].cardLog.length === 2 && STORE[key("m-normal")].cardLog[0].seatId === "p1" && STORE[key("m-normal")].cardLog[1].seatId === "p2");
  check("MOCKED — sequential cards: version incremented again by exactly 1", result2.version === 3);

  // Same seat can submit again too (submitCard() has no "already
  // played this trick" check — that's the ENGINE's job, per Task 1's
  // own "must only persist synchronized state").
  signInAs("userA");
  var result3 = await MatchService.submitCard("m-normal", { suit: "HEARTS", rank: { v: 8, s: "8" } });
  check("MOCKED — sequential cards: the SAME seat submitting a THIRD, later card in the match is also just appended (no anti-double-submit check here — that's table-engine.js's job, not this layer's)",
    STORE[key("m-normal")].cardLog.length === 3 && result3.cardCount === 3);

  // ============================================================
  // MOCKED — Seat resolution: uid -> seat is derived internally, NEVER
  // trusted from a client-supplied parameter (there is none).
  // ============================================================
  seedMatch("m-seat-resolve");
  signInAs("userB");
  var resultSeat = await MatchService.submitCard("m-seat-resolve", QUEEN_SPADES);
  check("MOCKED — seat resolution: userB correctly resolves to p2 (this match's OWN seat map), not p1",
    resultSeat.seatId === "p2" && STORE[key("m-seat-resolve")].lastCardSeat === "p2");

  // ============================================================
  // MOCKED — SECURITY: a uid that owns NO seat in this match
  // ============================================================
  seedMatch("m-no-seat");
  signInAs("userZ"); // not a player in this match at all
  var noSeatErr = null;
  try { await MatchService.submitCard("m-no-seat", QUEEN_SPADES); } catch (e) { noSeatErr = e; }
  check("MOCKED — SECURITY: a uid owning no seat in this match is rejected PERMISSION_DENIED, never allowed to guess/claim a seat",
    noSeatErr && noSeatErr.reason === "PERMISSION_DENIED");
  check("MOCKED — SECURITY: the rejected attempt left cardLog completely untouched", STORE[key("m-no-seat")].cardLog.length === 0);

  // ============================================================
  // MOCKED — Generic card-shape validation (NOT legality — see
  // isValidGenericCardValue()'s own comment in match-service.js)
  // ============================================================
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
  var validAfterRejections = await MatchService.submitCard("m-shape", QUEEN_SPADES);
  check("MOCKED — generic card validation: a genuinely valid card AFTER several rejections still succeeds normally",
    validAfterRejections.cardCount === 1);

  // ============================================================
  // MOCKED — Failure paths: missing args, no signed-in user, match not
  // found, Firestore unavailable, MatchAdapter unavailable.
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

  // ============================================================
  // MOCKED — Realtime synchronization: submitCard()'s write is
  // delivered through the EXISTING, unmodified subscribeToMatch() pipe
  // — zero changes to that function, same guarantee submitBid() proved
  // in Sprint 3.8.
  // ============================================================
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
