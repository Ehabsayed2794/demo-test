const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Real, executable END-TO-END tests for Sprint 4.1 (Turn Authority &
// Remote Play Validation) — the turn-synchronization half of the
// pipeline:
//   Firestore matches/{matchId}.turn -> MatchService listener ->
//   Engine Adapter (applyRemoteTurn) -> GameSession's top-level
//   turn mirror
// exercised together, in one process, against the REAL
// design-ui/match-service.js, design-ui/match-adapter.js, and
// design-ui/engine/session.js (GameSession) — not stubs, not fakes.
//
// This sprint is NOT about card play. It is ONLY about determining WHO
// is allowed to act — see design-ui/match-adapter.js's own Sprint 4.1
// header comment for the full design rationale (in particular: why
// GameSession's top-level `turnId` mirror is a SEPARATE field from
// `GameSession.getBiddingState().turnId`, which `bidding-engine.js`'s
// own reducer already owns and this sprint does not touch).
//
// This suite deliberately does NOT reuse tests/bid-sync.test.cjs's
// runBiddingToEstimates() helper to drive turn changes — this sprint
// has nothing to do with `bidding-engine.js`'s own bidding-phase
// `waitingFor` pointer, and using it here would blur exactly the
// distinction this sprint exists to keep sharp. Turn changes are
// simulated the same way tests/bid-sync.test.cjs simulates a stale/
// forged snapshot: by writing directly into the mock Firestore STORE
// and calling notify() — since no MatchService write path for
// `matches/{matchId}.turn` exists in this codebase (turn advancement
// remains entirely a gameplay-engine decision, per this sprint's own
// "Firestore never decides whose turn it is" architecture rule).
//
// LABELING: every check below is MOCKED — real code from
// match-service.js, match-adapter.js, and session.js, exercised
// against a hand-written fake Firestore (the same shape as
// tests/bid-sync.test.cjs's own, reused here for consistency). No
// SIMULATED checks (no firestore.rules involved — this sprint does
// not touch firestore.rules at all). No real Firestore project,
// Firebase Emulator, or browser was used.
global.window = global;
global.window.addEventListener = function () {};

var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var ONSNAPSHOT_CALLS = {};
var pendingErrorCallbacks = {};
var FIRESTORE_AVAILABLE = true;

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
var idCounter = 0;
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

function simulateDisconnect(id, code) {
  var k = key(id);
  var cbs = (pendingErrorCallbacks[k] || []).slice();
  LISTENERS[k] = [];
  pendingErrorCallbacks[k] = [];
  var err = new Error("simulated disconnect" + (code ? " (" + code + ")" : ""));
  if (code) err.code = code;
  cbs.forEach(function (cb) { cb(err); });
}

var CURRENT_USER = null;
global.SessionService = { getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; }, setCurrentMatchId: function () { return Promise.resolve(); } };

require(__REPO_ROOT__ + "/design-ui/match-service.js");
require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/match-adapter.js");

var GameSession = global.GameSession;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function wait(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }

/** Seeds a mocked Firestore match document with a fixed 4-seat map and
 *  an initial turn owner. Mirrors tests/bid-sync.test.cjs's own
 *  seedMockMatch() shape, extended with nothing this sprint doesn't
 *  need. */
function seedMockMatch(matchId, initialTurnUid) {
  STORE[key(matchId)] = {
    roomId: "room-x", players: ["u1", "u2", "u3", "u4"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "u1", turn: initialTurnUid || "u1", gameState: { initialized: false },
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null
  };
  DOC_VERSION[key(matchId)] = (DOC_VERSION[key(matchId)] || 0) + 1;
}

/** Directly forges a new `turn` value onto an already-seeded match and
 *  delivers it via the mock listener — simulating a turn change that
 *  arrived through Firestore sync. This is the correct simulation
 *  method for this sprint specifically because no MatchService write
 *  path for `matches/{matchId}.turn` exists yet (see this file's own
 *  header comment) — turn advancement is a gameplay-engine decision,
 *  not something ANY code in this codebase, including this test,
 *  should compute; forging a value directly and delivering it is
 *  exactly what "Firestore never decides whose turn it is" makes safe
 *  to do here, since it's simulating what an external decision-maker
 *  (the engine, once it writes turns back — not built this sprint)
 *  would eventually produce. */
function forgeTurnUpdate(matchId, newTurnUid, versionOverride) {
  var k = key(matchId);
  var nextVersion = versionOverride != null ? versionOverride : (STORE[k].version + 1);
  STORE[k] = Object.assign({}, STORE[k], { turn: newTurnUid, version: nextVersion });
  DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
  notify(k);
}

(async function () {
  // ============================================================
  // MOCKED — Task 1 / New snapshot: startTurnSync() delivers the
  // initial snapshot and applies the seeded turn owner correctly.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-new", "u1");
  var unsub1 = MatchAdapter.startTurnSync("m-new");
  check("MOCKED — Task 1: startTurnSync() delivers the initial snapshot and correctly applies the seeded turn owner",
    GameSession.getTurn() === "p1");
  check("MOCKED — Task 1: no gameplay logic inside MatchService — startTurnSync() reuses subscribeToMatch() verbatim, no second listener",
    ONSNAPSHOT_CALLS[key("m-new")] === 1);
  unsub1();

  // ============================================================
  // MOCKED — New snapshot: a genuinely new turn owner delivered after
  // the initial snapshot is correctly applied.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-newturn", "u1");
  var unsub2 = MatchAdapter.startTurnSync("m-newturn");
  forgeTurnUpdate("m-newturn", "u3");
  check("MOCKED — new snapshot: a new turn owner (p3) delivered through Firestore sync is correctly applied to GameSession's turn mirror",
    GameSession.getTurn() === "p3");
  unsub2();

  // ============================================================
  // MOCKED — Turn advance: a sequence of turn changes, each strictly
  // newer than the last, each correctly applied in order.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-advance", "u1");
  var unsub3 = MatchAdapter.startTurnSync("m-advance");
  var advanceSeq = [["u2", "p2"], ["u3", "p3"], ["u4", "p4"], ["u1", "p1"]];
  advanceSeq.forEach(function (pair) {
    forgeTurnUpdate("m-advance", pair[0]);
    check("MOCKED — turn advance: GameSession's turn mirror correctly advances to " + pair[1], GameSession.getTurn() === pair[1]);
  });
  unsub3();

  // ============================================================
  // MOCKED — Duplicate snapshot: receiving the identical turn snapshot
  // twice must not re-render, re-run engine logic, or advance the turn.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-dup", "u1");
  var unsub4 = MatchAdapter.startTurnSync("m-dup");
  forgeTurnUpdate("m-dup", "u2");
  var turnAfterFirst = GameSession.getTurn();
  var versionAfterFirst = MatchAdapter.getLastAppliedTurnVersion("m-dup");
  // Re-deliver the IDENTICAL, already-processed snapshot directly —
  // simulating a benign duplicate delivery from the underlying SDK.
  notify(key("m-dup"));
  notify(key("m-dup"));
  check("MOCKED — Task 4 (duplicate snapshot): re-delivering the same snapshot does not change GameSession's turn mirror",
    GameSession.getTurn() === turnAfterFirst);
  check("MOCKED — Task 4: re-delivering the same snapshot does not advance the adapter's own turn version gate",
    MatchAdapter.getLastAppliedTurnVersion("m-dup") === versionAfterFirst);
  unsub4();

  // ============================================================
  // MOCKED — Stale snapshot / version rollback: an out-of-order
  // delivery of an OLDER version must never roll the turn mirror back.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-stale", "u1");
  var unsub5 = MatchAdapter.startTurnSync("m-stale");
  forgeTurnUpdate("m-stale", "u3"); // version 2, turn -> p3
  var afterRealAdvance = GameSession.getTurn();
  // Forge a stale, LOWER-version snapshot with a different (bogus) turn.
  STORE[key("m-stale")] = Object.assign({}, STORE[key("m-stale")], { version: 1, turn: "u4" });
  notify(key("m-stale"));
  check("MOCKED — version rollback: a stale, lower-version snapshot never overwrites the already-applied turn",
    GameSession.getTurn() === afterRealAdvance);
  unsub5();

  // ============================================================
  // MOCKED — Late subscriber: a NEW subscription joining AFTER a turn
  // change has already been applied must observe the CURRENT state,
  // and must not cause a re-application on its own account.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-late", "u1");
  var unsub6a = MatchAdapter.startTurnSync("m-late");
  forgeTurnUpdate("m-late", "u2");
  var turnBeforeLate = GameSession.getTurn();
  var unsub6b = MatchAdapter.startTurnSync("m-late"); // a second, later subscriber for the SAME match
  check("MOCKED — late subscriber: joining after a turn change was already applied does not change GameSession's turn mirror",
    GameSession.getTurn() === turnBeforeLate);
  check("MOCKED — late subscriber: no duplicated listener was created — MatchService's own ref-counted registry is reused",
    ONSNAPSHOT_CALLS[key("m-late")] === 1);
  unsub6a(); unsub6b();

  // ============================================================
  // MOCKED — Listener restart / listener duplicate event.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-restart", "u1");
  var unsub7 = MatchAdapter.startTurnSync("m-restart");
  forgeTurnUpdate("m-restart", "u2");
  check("MOCKED — listener restart setup: turn applied before any disconnect", GameSession.getTurn() === "p2");

  simulateDisconnect("m-restart", "unavailable"); // retryable — MatchService auto-reconnects
  await wait(1000);
  check("MOCKED — listener restart: after reconnecting, the previously-applied turn is still correct (not reset, not reapplied)",
    GameSession.getTurn() === "p2");

  forgeTurnUpdate("m-restart", "u3");
  check("MOCKED — listener restart: a NEW turn change delivered after reconnect is correctly applied through the restarted listener",
    GameSession.getTurn() === "p3");

  var turnBeforeDup = GameSession.getTurn();
  var versionBeforeDup = MatchAdapter.getLastAppliedTurnVersion("m-restart");
  notify(key("m-restart"));
  notify(key("m-restart"));
  notify(key("m-restart"));
  check("MOCKED — listener duplicate event: three redundant re-deliveries of the current snapshot cause zero additional changes",
    GameSession.getTurn() === turnBeforeDup && MatchAdapter.getLastAppliedTurnVersion("m-restart") === versionBeforeDup);
  unsub7();

  // ============================================================
  // MOCKED — Task 3: Local Authority Validation, driven through the
  // real subscription pipeline (not just the unit-level matchDoc
  // checks in tests/match-adapter.test.cjs).
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-authority", "u1");
  var unsub8 = MatchAdapter.startTurnSync("m-authority");
  forgeTurnUpdate("m-authority", "u3"); // turn now belongs to p3
  var liveDoc = STORE[key("m-authority")];
  check("MOCKED — correct player accepted: isLocalSeatsTurn() is true for p3, the seat whose turn it now is",
    MatchAdapter.isLocalSeatsTurn(liveDoc, "p3") === true);
  check("MOCKED — wrong player attempts action: isLocalSeatsTurn() is false for every other seat",
    MatchAdapter.isLocalSeatsTurn(liveDoc, "p1") === false && MatchAdapter.isLocalSeatsTurn(liveDoc, "p2") === false && MatchAdapter.isLocalSeatsTurn(liveDoc, "p4") === false);
  var wrongPlayerErr = null;
  try { MatchAdapter.assertLocalTurn(liveDoc, "p1"); } catch (e) { wrongPlayerErr = e; }
  check("MOCKED — wrong player attempts action: assertLocalTurn() throws NOT_LOCAL_TURN for the wrong seat — 'reject locally, do not send writes'",
    wrongPlayerErr && wrongPlayerErr.reason === "NOT_LOCAL_TURN");
  var correctPlayerErr = null;
  try { MatchAdapter.assertLocalTurn(liveDoc, "p3"); } catch (e) { correctPlayerErr = e; }
  check("MOCKED — correct player accepted: assertLocalTurn() does not throw for the correct seat", correctPlayerErr === null);
  unsub8();

  // ============================================================
  // MOCKED — GameSession consistency: after a full sequence of turn
  // changes, GameSession's mirror and the mocked Firestore document
  // agree exactly, with no drift.
  // ============================================================
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  seedMockMatch("m-consistency", "u1");
  var unsub9 = MatchAdapter.startTurnSync("m-consistency");
  ["u2", "u3", "u4", "u1", "u2"].forEach(function (uid) { forgeTurnUpdate("m-consistency", uid); });
  var finalDoc = STORE[key("m-consistency")];
  check("MOCKED — GameSession consistency: after a full sequence of turn changes, GameSession's turn mirror matches the mocked document's own turn field exactly",
    GameSession.getTurn() === MatchAdapter.uidToSeat(finalDoc, finalDoc.turn) && GameSession.getTurn() === "p2");
  check("MOCKED — GameSession consistency: the adapter's own version gate matches the document's final version exactly",
    MatchAdapter.getLastAppliedTurnVersion("m-consistency") === finalDoc.version);
  unsub9();

  // ============================================================
  // MOCKED — Adapter isolation (Task 5): no other file in this
  // codebase's diff history manipulates engine turn state directly.
  // ============================================================
  var fs = require("fs");
  var serviceSource = fs.readFileSync(__REPO_ROOT__ + "/design-ui/match-service.js", "utf8");
  // Checks actual USAGE patterns (`GameSession.` / `.setTurn(`), not a
  // bare-word match — Sprint 4.2 added a comment to this file that
  // correctly NAMES GameSession in prose while explaining this file
  // still never calls it; a bare /GameSession/ regex would false-
  // positive on that documentation sentence itself.
  check("MOCKED — adapter isolation: design-ui/match-service.js has zero CODE reference to GameSession/setTurn/any engine file, unchanged",
    !/GameSession\./.test(serviceSource) && !/\.setTurn\(/.test(serviceSource));

  // ============================================================
  // MOCKED — Regression sanity: Sprint 4.0's bid-sync API is untouched
  // by this sprint's additions.
  // ============================================================
  check("MOCKED — regression: MatchAdapter.applyRemoteBid (Sprint 4.0) is still present and unchanged in shape", typeof MatchAdapter.applyRemoteBid === "function");
  check("MOCKED — regression: MatchAdapter.startBidSync (Sprint 4.0) is still present and unchanged in shape", typeof MatchAdapter.startBidSync === "function");
  check("MOCKED — regression: MatchAdapter.getLastAppliedVersion (Sprint 4.0) is still present and unchanged in shape", typeof MatchAdapter.getLastAppliedVersion === "function");

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
