const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Real, executable tests for Sprint 4.2 (Online Card Synchronization:
// Engine Authority), Task 1 — MatchService.submitCard(matchId, card) —
// HARDENED in Sprint 4.2.1 (turn authority + pre-write engine
// legality) and again in Sprint 4.2.2 (Atomic Card Turn Progression &
// Card-Log Desync Hardening): submitCard() now ATOMICALLY writes the
// next turn (translated seat -> uid) and a new `cardPhase` field
// alongside every accepted card, using `TableEngine.previewPlay()` — a
// new, pure, non-mutating export — computed BEFORE the Firestore
// transaction even opens, and revalidated against a fresh read inside
// the transaction (STALE_GAME_STATE if the document changed
// underneath since the preview was computed).
//
// LABELING: every check below is MOCKED — real design-ui/match-service.js
// and real design-ui/match-adapter.js code, exercised against a
// hand-written fake Firestore AND a hand-written fake TableEngine
// (controllable per test, mirroring match-adapter.test.cjs's own
// fakeTableEngine() pattern; the REAL table-engine.js is exercised
// end-to-end in tests/card-sync.test.cjs instead). No SIMULATED checks
// in this file (those live in tests/rules-simulation.test.js). No real
// Firestore project, Firebase Emulator, or browser was used.
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

require(__REPO_ROOT__ + "/design-ui/match-service.js");
require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/match-adapter.js"); // needed: submitCard() calls MatchAdapter.uidToSeat()/seatToUid()/assertLocalTurn()
var MatchService = global.MatchService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

/** A controllable fake TableEngine implementing `previewPlay()` (the
 *  function submitCard() now actually calls — Sprint 4.2.2's Task 1/2)
 *  — default: every card is legal, and the fake advances a simple
 *  internal seat-rotation model so MULTIPLE sequential calls in one
 *  test correctly mirror a real engine's own turn progression, without
 *  needing the full real table-engine.js (that end-to-end coverage
 *  lives in tests/card-sync.test.cjs). */
function installFakeTableEngine(opts) {
  opts = opts || {};
  var calls = [];
  var playCount = 0;
  var seatOrder = opts.seatOrder || ["p1", "p2", "p3", "p4"];
  var engine = {
    _calls: calls,
    _legal: true,
    _reason: "ILLEGAL_CARD",
    previewPlay: function (playerId, card) {
      calls.push({ playerId: playerId, card: card });
      if (!engine._legal) return { legal: false, reason: engine._reason };
      var nextCount = playCount + 1;
      if (nextCount < 4) {
        var idx = seatOrder.indexOf(playerId);
        var nextSeat = seatOrder[(idx + 1) % seatOrder.length];
        return { legal: true, nextTurnSeat: nextSeat, nextPhase: "PLAY" };
      }
      return { legal: true, nextTurnSeat: null, nextPhase: "RESOLVING" };
    },
    /** Test-only: call after a successful submitCard() to advance the
     *  fake's own internal trick-position counter — mirrors what a
     *  real engine's `emit()` would do via the client's own
     *  applyRemoteCard() echo. Not called by submitCard() itself. */
    _advance: function () { playCount += 1; }
  };
  global.TableEngine = engine;
  return engine;
}

function seedMatch(id, overrides) {
  var base = {
    roomId: "room-x", players: ["userA", "userB", "userC", "userD"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "userA", turn: "userA", gameState: { initialized: false },
    seats: { p1: "userA", p2: "userB", p3: "userC", p4: "userD" }, version: 1, biddingOpen: false,
    bids: { p1: 4, p2: 2, p3: 2, p4: 2 }, lastBidSeat: "p4",
    cardLog: [], lastCardSeat: null, cardPhase: null
  };
  STORE[key(id)] = Object.assign(base, overrides || {});
  DOC_VERSION[key(id)] = (DOC_VERSION[key(id)] || 0) + 1;
  return STORE[key(id)];
}

var QUEEN_SPADES = { suit: "SPADES", rank: { v: 12, s: "Q" } };

(async function () {
  var fakeEngine;

  // ============================================================
  // MOCKED — Task 7, req #1-#4: the REAL production path — four
  // sequential seats each submit ONE card, with Firestore's own
  // `turn` field advancing AUTOMATICALLY between submissions. NO
  // manual/test-only turn mutation anywhere in this block — this is
  // the exact scenario Sprint 4.2.2 exists to make real.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-sequence");
  signInAs("userA");
  var r1 = await MatchService.submitCard("m-sequence", QUEEN_SPADES);
  fakeEngine._advance();
  check("MOCKED — Task 7 req #1: p1 submits a valid card and Firestore's own turn field becomes p2's uid — no test helper involved",
    r1.seatId === "p1" && r1.nextTurnSeat === "p2" && r1.cardPhase === "PLAY" && STORE[key("m-sequence")].turn === "userB" && STORE[key("m-sequence")].cardPhase === "PLAY");

  signInAs("userB");
  var r2 = await MatchService.submitCard("m-sequence", { suit: "SPADES", rank: { v: 5, s: "5" } });
  fakeEngine._advance();
  check("MOCKED — Task 7 req #2: p2 can submit IMMEDIATELY, with zero test-only setTurn() call, because submitCard() itself already advanced the turn",
    r2.seatId === "p2" && STORE[key("m-sequence")].turn === "userC");

  signInAs("userC");
  var r3 = await MatchService.submitCard("m-sequence", { suit: "SPADES", rank: { v: 8, s: "8" } });
  fakeEngine._advance();
  check("MOCKED — Task 7 req #3: p3 can submit immediately afterward, same automatic mechanism",
    r3.seatId === "p3" && STORE[key("m-sequence")].turn === "userD");

  signInAs("userD");
  var r4 = await MatchService.submitCard("m-sequence", { suit: "SPADES", rank: { v: 2, s: "2" } });
  check("MOCKED — Task 7 req #4: p4 submits the FOURTH card and Firestore moves to the resolving/null-turn boundary — turn becomes null, cardPhase becomes RESOLVING",
    r4.seatId === "p4" && r4.nextTurnSeat === null && r4.cardPhase === "RESOLVING" &&
    STORE[key("m-sequence")].turn === null && STORE[key("m-sequence")].cardPhase === "RESOLVING");

  check("MOCKED — Task 7 req #13: the full four-card sequence produced exactly 4 cardLog entries, one per real seat, with ZERO manual Firestore turn edits anywhere in this test",
    STORE[key("m-sequence")].cardLog.length === 4 &&
    STORE[key("m-sequence")].cardLog.map(function (e) { return e.seatId; }).join(",") === "p1,p2,p3,p4");

  // ============================================================
  // MOCKED — Task 7 req #5: wrong-turn submission performs zero writes.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-wrong-turn", { turn: "userB" }); // it's p2's turn
  signInAs("userA"); // owns p1 — NOT the current turn
  var wrongTurnErr = null;
  try { await MatchService.submitCard("m-wrong-turn", QUEEN_SPADES); } catch (e) { wrongTurnErr = e; }
  check("MOCKED — Task 7 req #5: wrong-turn submission rejects NOT_YOUR_TURN with zero writes",
    wrongTurnErr && wrongTurnErr.reason === "NOT_YOUR_TURN" &&
    STORE[key("m-wrong-turn")].version === 1 && STORE[key("m-wrong-turn")].cardLog.length === 0 && STORE[key("m-wrong-turn")].turn === "userB");
  // Sprint J.10.9 (Bounded Server-Sourced Reconciliation) intentionally
  // changes this: a local NOT_YOUR_TURN rejection is no longer
  // immediately terminal — one bounded, single-flighted server-sourced
  // reconciliation attempt now runs first (see match-service.js's
  // refreshFromServerAndReconcile()), which calls previewPlay() again
  // to re-check card legality against the reconciled engine before
  // deciding whether to defer to the transaction. previewPlay() being
  // called is therefore now EXPECTED, not a regression — what matters
  // (asserted above) is that this still produces zero writes and the
  // same NOT_YOUR_TURN outcome for a genuinely, non-stale wrong-turn
  // attempt, via the transaction's own unchanged internal defense-in-
  // depth check.
  check("MOCKED — Task 7 req #5 (superseded by Sprint J.10.9): previewPlay() IS now called as part of the bounded reconciliation attempt, but still produces zero writes",
    fakeEngine._calls.length > 0);

  // ============================================================
  // MOCKED — Task 7 req #6/#7: a STALE preview (the document changed
  // between the pre-check read and the transaction opening) produces
  // STALE_GAME_STATE and zero writes — including when the change
  // happens via what would be Firestore's own automatic transaction
  // retry re-invoking this callback against a DIFFERENT document state.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-stale-preview");
  signInAs("userA");
  var realRunTransaction = FAKE_DB.runTransaction;
  var interceptedOnce = false;
  FAKE_DB.runTransaction = function (fn, attempt) {
    if (!interceptedOnce) {
      interceptedOnce = true;
      // Simulate a concurrent write landing between the pre-check read
      // (which computed the preview) and the transaction opening —
      // exactly the race Task 3 exists to close.
      STORE[key("m-stale-preview")].version = 999;
      DOC_VERSION[key("m-stale-preview")] = (DOC_VERSION[key("m-stale-preview")] || 0) + 5;
    }
    return realRunTransaction(fn, attempt);
  };
  var staleErr = null;
  try { await MatchService.submitCard("m-stale-preview", QUEEN_SPADES); } catch (e) { staleErr = e; }
  FAKE_DB.runTransaction = realRunTransaction;
  check("MOCKED — Task 7 req #6: a stale preview (document changed underneath) rejects STALE_GAME_STATE, not a silently-recomputed answer",
    staleErr && staleErr.reason === "STALE_GAME_STATE");
  check("MOCKED — Task 7 req #6: zero writes occurred for the stale attempt — cardLog completely untouched",
    STORE[key("m-stale-preview")].cardLog.length === 0);
  check("MOCKED — Task 7 req #7: this function never automatically recomputed a new preview against the changed state and proceeded — it stopped, full stop, on the FIRST detection of drift",
    fakeEngine._calls.length === 1 /* the ORIGINAL pre-check preview call — no second, "let me try again" call exists in this function at all */);

  // ============================================================
  // MOCKED — Task 2: the atomic write's own shape — result includes
  // nextTurnSeat/cardPhase; the Firestore document's `turn`/`cardPhase`
  // fields are set in the SAME write as cardLog/lastCardSeat/version
  // (never a separate, second "move the turn" request).
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-atomic-shape");
  signInAs("userA");
  var atomicResult = await MatchService.submitCard("m-atomic-shape", QUEEN_SPADES);
  check("MOCKED — Task 2: the result object reports both the new turn seat and the new phase", atomicResult.nextTurnSeat === "p2" && atomicResult.cardPhase === "PLAY");
  check("MOCKED — Task 2: cardLog, lastCardSeat, turn, cardPhase, and version ALL changed in the SAME write — no separate turn-write request exists anywhere in this codebase",
    STORE[key("m-atomic-shape")].cardLog.length === 1 && STORE[key("m-atomic-shape")].lastCardSeat === "p1" &&
    STORE[key("m-atomic-shape")].turn === "userB" && STORE[key("m-atomic-shape")].cardPhase === "PLAY" && STORE[key("m-atomic-shape")].version === 2);

  // ============================================================
  // MOCKED — an unknown next-seat name from the engine is rejected
  // defensively (should never happen against a real, correctly-seated
  // match, but this function never trusts an engine answer blindly).
  // ============================================================
  var fakeUnknownSeat = {
    previewPlay: function () { return { legal: true, nextTurnSeat: "p9", nextPhase: "PLAY" }; }
  };
  global.TableEngine = fakeUnknownSeat;
  seedMatch("m-unknown-seat");
  signInAs("userA");
  var unknownSeatErr = null;
  try { await MatchService.submitCard("m-unknown-seat", QUEEN_SPADES); } catch (e) { unknownSeatErr = e; }
  check("MOCKED — defensive: an engine-named next seat this match's own seats map doesn't recognize is rejected UNKNOWN_NEXT_SEAT, never written",
    unknownSeatErr && unknownSeatErr.reason === "UNKNOWN_NEXT_SEAT" && STORE[key("m-unknown-seat")].cardLog.length === 0);

  // ============================================================
  // MOCKED — illegal card (Sprint 4.2.1's own gate, now via
  // previewPlay() instead of canPlayCard() directly) still rejects
  // before persistence.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  fakeEngine._legal = false;
  fakeEngine._reason = "ILLEGAL_CARD";
  seedMatch("m-illegal");
  signInAs("userA");
  var illegalErr = null;
  try { await MatchService.submitCard("m-illegal", { suit: "HEARTS", rank: { v: 2, s: "2" } }); } catch (e) { illegalErr = e; }
  check("MOCKED — illegal card still rejected before persistence via previewPlay()'s own legal:false answer",
    illegalErr && illegalErr.reason === "ILLEGAL_CARD" && STORE[key("m-illegal")].cardLog.length === 0);

  // ============================================================
  // MOCKED — SECURITY: a uid that owns NO seat in this match.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-no-seat");
  signInAs("userZ");
  var noSeatErr = null;
  try { await MatchService.submitCard("m-no-seat", QUEEN_SPADES); } catch (e) { noSeatErr = e; }
  check("MOCKED — SECURITY: a uid owning no seat in this match is rejected PERMISSION_DENIED",
    noSeatErr && noSeatErr.reason === "PERMISSION_DENIED" && STORE[key("m-no-seat")].cardLog.length === 0);

  // ============================================================
  // MOCKED — Generic card-shape validation (unchanged from Sprint 4.2).
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
  await expectRejected("SPADES-Q", "a string is rejected, never coerced");
  await expectRejected({ suit: "STARS", rank: { v: 10, s: "10" } }, "an unknown suit key is rejected");
  check("MOCKED — generic card validation: every rejected attempt left cardLog completely untouched", STORE[key("m-shape")].cardLog.length === 0);
  var validAfterRejections = await MatchService.submitCard("m-shape", QUEEN_SPADES);
  check("MOCKED — generic card validation: a genuinely valid card AFTER several rejections still succeeds normally", validAfterRejections.cardCount === 1);

  // ============================================================
  // MOCKED — Failure paths: missing args, no signed-in user, match not
  // found, Firestore unavailable, MatchAdapter unavailable, TableEngine
  // unavailable.
  // ============================================================
  var noMatchIdErr = null;
  try { await MatchService.submitCard(null, QUEEN_SPADES); } catch (e) { noMatchIdErr = e; }
  check("MOCKED — failure: missing matchId rejects INVALID_ARGUMENT", noMatchIdErr && noMatchIdErr.reason === "INVALID_ARGUMENT");

  CURRENT_USER = null;
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
  check("MOCKED — failure: a write attempted while Firestore is unavailable rejects cleanly, never crashes", !!unavailableErr);
  check("MOCKED — failure recovery: the stored document is completely untouched by the failed attempt", STORE[key("m-shape")].cardLog.length === cardCountBeforeOffline);
  FIRESTORE_AVAILABLE = true;

  var savedAdapter = global.MatchAdapter;
  global.MatchAdapter = undefined;
  var noAdapterErr = null;
  try { await MatchService.submitCard("m-shape", QUEEN_SPADES); } catch (e) { noAdapterErr = e; }
  check("MOCKED — failure: MatchAdapter unavailable rejects MATCH_ADAPTER_UNAVAILABLE",
    noAdapterErr && noAdapterErr.reason === "MATCH_ADAPTER_UNAVAILABLE");
  global.MatchAdapter = savedAdapter;

  var savedEngine = global.TableEngine;
  global.TableEngine = undefined;
  var noEngineErr = null;
  try { await MatchService.submitCard("m-shape", QUEEN_SPADES); } catch (e) { noEngineErr = e; }
  check("MOCKED — failure: TableEngine unavailable (no previewPlay()) rejects ENGINE_UNAVAILABLE — submitCard() refuses to write blind",
    noEngineErr && noEngineErr.reason === "ENGINE_UNAVAILABLE");
  global.TableEngine = savedEngine;

  // Old-shaped fake (canPlayCard only, no previewPlay) must ALSO be
  // treated as engine-unavailable — Sprint 4.2.2 requires previewPlay()
  // specifically, since canPlayCard() alone cannot answer "what's next."
  global.TableEngine = { canPlayCard: function () { return { legal: true }; } };
  var oldShapeErr = null;
  try { await MatchService.submitCard("m-shape", QUEEN_SPADES); } catch (e) { oldShapeErr = e; }
  check("MOCKED — an engine exposing only the OLD canPlayCard() (no previewPlay()) is treated as unavailable, not silently degraded",
    oldShapeErr && oldShapeErr.reason === "ENGINE_UNAVAILABLE");
  global.TableEngine = savedEngine;

  // ============================================================
  // MOCKED — Realtime synchronization: unchanged guarantee from every
  // prior sprint — the write is delivered through the EXISTING,
  // unmodified subscribeToMatch() pipe.
  // ============================================================
  fakeEngine = installFakeTableEngine();
  seedMatch("m-realtime");
  var deliveries = [];
  var unsub = MatchService.subscribeToMatch("m-realtime", function (data, err) { if (!err) deliveries.push(data); });
  var onSnapshotCallsBefore = ONSNAPSHOT_CALLS[key("m-realtime")];
  signInAs("userA");
  await MatchService.submitCard("m-realtime", QUEEN_SPADES);
  check("MOCKED — realtime sync: the existing subscription observed the card write (including the new turn/cardPhase fields) live",
    deliveries.length === 2 && deliveries[1].cardLog.length === 1 && deliveries[1].turn === "userB" && deliveries[1].cardPhase === "PLAY");
  check("MOCKED — realtime sync: no second Firestore listener was created", ONSNAPSHOT_CALLS[key("m-realtime")] === onSnapshotCallsBefore);
  unsub();

  // ============================================================
  // MOCKED — Task 7 req #10 (no gameplay rules duplicated outside
  // TableEngine): structural check — unchanged from Sprint 4.2.1,
  // re-verified against THIS sprint's own additions.
  // ============================================================
  var fs = require("fs");
  var serviceSource = fs.readFileSync(__REPO_ROOT__ + "/design-ui/match-service.js", "utf8");
  // Checks actual CODE (a function definition/call), not a bare-word
  // match — this file's own comments correctly NAME `nextCCW()` in
  // prose while explaining it is NOT reimplemented here; a bare regex
  // would false-positive on that documentation sentence itself (the
  // same class of test fragility already fixed once in
  // turn-sync.test.cjs's own "adapter isolation" check).
  check("MOCKED — Task 7 req #10: match-service.js contains no follow-suit/turn-order logic of its own — no `ledSuit` field access, no `nextCCW()` function definition/call",
    !/ledSuit/.test(serviceSource) && !/function nextCCW/.test(serviceSource) && !/[^`]nextCCW\(/.test(serviceSource));
  check("MOCKED — Task 7 req #10: match-service.js never calls a GameSession/TableEngine setter directly — its only TableEngine reference is the read-only previewPlay() query",
    !/TableEngine\.emit/.test(serviceSource) && !/GameSession\.setTurn/.test(serviceSource));

  // ============================================================
  // MOCKED — Regression: MatchService's other stubs and existing API
  // are untouched.
  // ============================================================
  check("MOCKED — regression: MatchService.submitBid (Sprint 3.8) is still present and unchanged in shape", typeof MatchService.submitBid === "function");
  check("MOCKED — regression: MatchService.playCard remains the OLD, never-adopted, still-unimplemented stub", (function () {
    try { MatchService.playCard("x", "y", "z"); return false; } catch (e) { return /not implemented/i.test(e.message); }
  })());

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
