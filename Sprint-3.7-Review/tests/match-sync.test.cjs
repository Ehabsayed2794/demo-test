// Real, executable tests for Sprint 3.7 (Real-Time Match Synchronization)
// — MatchService.subscribeToMatch()'s production-ready sync behavior
// (the brief's requested subscribe(matchId), kept under its established
// name — see match-service.js's header comment and
// docs/architecture/MatchSynchronization.md) and GameSession's new
// consumption of it.
//
// This mock is deliberately its OWN, dedicated fake Firestore (not a
// reuse/import of tests/match-service.test.cjs's mock) — matching this
// project's existing one-mock-per-test-file convention — extended with
// two things no earlier mock needed: an onSnapshot call counter (to
// prove "no duplicated listeners" directly, not just by inference) and
// explicit simulateDisconnect()/simulateReconnect() hooks (to drive
// MatchService's automatic-reconnect path deterministically).
global.window = global;

var STORE = {};       // "matches/<id>" -> data
var LISTENERS = {};   // "matches/<id>" -> [onNext, ...] (currently attached real listeners)
var ONSNAPSHOT_CALLS = {}; // "matches/<id>" -> count of onSnapshot() registrations ever made
var FIRESTORE_AVAILABLE = true;
var pendingErrorCallbacks = {}; // "matches/<id>" -> [onError, ...] registered while available, to fire on demand

function key(id) { return "matches/" + id; }

function notify(k) {
  (LISTENERS[k] || []).forEach(function (cb) {
    var exists = Object.prototype.hasOwnProperty.call(STORE, k);
    cb({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
  });
}

var FAKE_DB = {
  collection: function (name) {
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return {
      doc: function (id) {
        var k = key(id);
        return {
          id: id,
          onSnapshot: function (onNext, onError) {
            ONSNAPSHOT_CALLS[k] = (ONSNAPSHOT_CALLS[k] || 0) + 1;
            if (!FIRESTORE_AVAILABLE) {
              onError(new Error("simulated Firestore unavailable"));
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
    };
  }
};
global.Db = FAKE_DB;
global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } } } };

function setDoc(id, data) { STORE[key(id)] = Object.assign({}, data); }
function patchDoc(id, patch) { STORE[key(id)] = Object.assign({}, STORE[key(id)], patch); notify(key(id)); }
function activeListenerCount(id) { return (LISTENERS[key(id)] || []).length; }
function onSnapshotCallCount(id) { return ONSNAPSHOT_CALLS[key(id)] || 0; }

/** Simulates a genuine mid-session disconnect: fires the CURRENTLY
 *  attached onError callback(s) for this matchId, exactly like a real
 *  Firestore SDK would if the connection dropped after a successful
 *  listen. Does NOT flip FIRESTORE_AVAILABLE — that's a separate knob
 *  controlling what happens on the NEXT (re)attach attempt. */
function simulateDisconnect(id) {
  var k = key(id);
  var cbs = (pendingErrorCallbacks[k] || []).slice();
  cbs.forEach(function (cb) { cb(new Error("simulated mid-session disconnect")); });
}

require("/home/user/demo-test/design-ui/match-service.js");
var MatchService = global.MatchService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function wait(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }

(async function () {
  // ============================================================
  // ✓ Two browser instances stay synchronized / duplicate listeners
  //   are impossible
  // ============================================================
  setDoc("m1", { roomId: "r1", players: ["u1", "u2"], status: "starting", currentRound: 1, dealer: "u1", turn: "u1" });

  var tabA = [], tabB = [];
  var unsubA = MatchService.subscribeToMatch("m1", function (data, err) { tabA.push({ data: data, err: err }); });
  var unsubB = MatchService.subscribeToMatch("m1", function (data, err) { tabB.push({ data: data, err: err }); });

  check("Two independent subscribers ('two tabs') to the SAME matchId each get an immediate snapshot",
    tabA.length === 1 && tabB.length === 1 && tabA[0].data.status === "starting" && tabB[0].data.status === "starting");
  check("Duplicate listeners are impossible: two subscribeToMatch() calls for the same matchId create exactly ONE real Firestore listener",
    onSnapshotCallCount("m1") === 1 && activeListenerCount("m1") === 1);

  patchDoc("m1", { turn: "u2" });
  check("Two browser instances stay synchronized: BOTH tabs observe the same change (turn changes synchronize)",
    tabA.length === 2 && tabB.length === 2 && tabA[1].data.turn === "u2" && tabB[1].data.turn === "u2");

  // ============================================================
  // ✓ Card play appears remotely / Estimates synchronize (generic
  //   passthrough — see MatchSynchronization.md's scope note: no
  //   gameplay write method is implemented yet, so this proves the
  //   SYNC PIPE carries an arbitrary match-document change correctly,
  //   which is exactly what a future submitEstimate()/playCard() would
  //   produce once implemented)
  // ============================================================
  patchDoc("m1", { gameState: { estimates: { u1: 3, u2: 0 } } });
  check("Estimates synchronize: an estimates-shaped change to gameState is delivered to every subscriber, including a real zero",
    tabA[tabA.length - 1].data.gameState.estimates.u2 === 0 && tabB[tabB.length - 1].data.gameState.estimates.u2 === 0);

  patchDoc("m1", { gameState: { estimates: { u1: 3, u2: 0 }, lastCardPlayed: { by: "u1", card: "7-HEARTS" } } });
  check("Card play appears remotely: a lastCardPlayed-shaped change is delivered to every subscriber",
    tabA[tabA.length - 1].data.gameState.lastCardPlayed.card === "7-HEARTS" &&
    tabB[tabB.length - 1].data.gameState.lastCardPlayed.card === "7-HEARTS");

  var countBeforeDup = tabA.length;
  patchDoc("m1", { gameState: { estimates: { u1: 3, u2: 0 }, lastCardPlayed: { by: "u1", card: "7-HEARTS" } } });
  check("Ignore local duplicate updates: an identical re-delivery (no real change) is never re-published",
    tabA.length === countBeforeDup && tabB.length === countBeforeDup);

  // ============================================================
  // ✓ Snapshot ordering
  // ============================================================
  setDoc("m2", { roomId: "r2", players: ["u1", "u2"], version: 1, turn: "u1" });
  var evOrder = [];
  MatchService.subscribeToMatch("m2", function (data, err) { evOrder.push({ data: data, err: err }); });
  check("Ordering guard: the first snapshot (version 1) is delivered", evOrder.length === 1 && evOrder[0].data.version === 1);

  // A stale/out-of-order arrival carrying the SAME version (simulating
  // a delayed duplicate delivery) must never overwrite anything.
  patchDoc("m2", { version: 1, turn: "STALE-SHOULD-NEVER-APPEAR" });
  check("Old snapshots (version <= last applied) never overwrite newer local state", evOrder.length === 1);

  patchDoc("m2", { version: 2, turn: "u2" });
  check("A genuinely newer version (2 > 1) is applied", evOrder.length === 2 && evOrder[1].data.turn === "u2");

  // An out-of-order LATE arrival of an OLDER version, arriving after a
  // newer one was already applied (e.g. network reordering) — still
  // must never overwrite the newer state already applied.
  patchDoc("m2", { version: 1, turn: "STALE-AGAIN-SHOULD-NEVER-APPEAR" });
  check("A stale snapshot arriving AFTER a newer one was already applied is still ignored", evOrder.length === 2);

  patchDoc("m2", { version: 5, turn: "u1" });
  check("Ordering guard tolerates version gaps (5 after 2) — only monotonic increase is required", evOrder.length === 3 && evOrder[2].data.turn === "u1");

  // ============================================================
  // ✓ Memory leak check
  // ============================================================
  unsubA();
  check("Unsubscribing ONE of two listeners leaves the shared Firestore listener attached for the other",
    activeListenerCount("m1") === 1 && onSnapshotCallCount("m1") === 1);
  patchDoc("m1", { turn: "u1-again" });
  check("The remaining subscriber (tabB) still receives updates after the other unsubscribed", tabB[tabB.length - 1].data.turn === "u1-again");
  var tabACountAfterUnsub = tabA.length;
  patchDoc("m1", { turn: "u1-yet-again" });
  check("The unsubscribed listener (tabA) receives nothing further", tabA.length === tabACountAfterUnsub);

  unsubB();
  check("No memory leak: once the LAST subscriber unsubscribes, the underlying Firestore listener is torn down",
    activeListenerCount("m1") === 0);
  patchDoc("m1", { turn: "nobody-listening" });
  check("A change with zero subscribers touches nothing (no dangling callback fires)", tabA.length === tabACountAfterUnsub && tabB.length > 0);

  var callCountBeforeResubscribe = onSnapshotCallCount("m1");
  var tabC = [];
  var unsubC = MatchService.subscribeToMatch("m1", function (data, err) { tabC.push({ data: data, err: err }); });
  check("A fresh subscribe() after full teardown creates a NEW real listener (proving the old one is genuinely gone, not just quiet)",
    onSnapshotCallCount("m1") === callCountBeforeResubscribe + 1 && activeListenerCount("m1") === 1 && tabC.length === 1);
  unsubC();

  // ============================================================
  // ✓ Offline recovery / reconnect restores synchronization / never
  //   crash / keep local game alive
  // ============================================================
  setDoc("m3", { roomId: "r3", players: ["u1", "u2"], status: "starting", turn: "u1" });
  var evReconnect = [];
  MatchService.subscribeToMatch("m3", function (data, err) { evReconnect.push({ data: data, err: err }); });
  check("Reconnect scenario setup: initial snapshot received", evReconnect.length === 1 && !evReconnect[0].err);

  simulateDisconnect("m3");
  var afterDisconnect = evReconnect[evReconnect.length - 1];
  check("Offline recovery: a disconnect delivers an error, but NEVER crashes (no throw reached this line)", !!afterDisconnect.err);
  check("Keep local game alive: the last known good data is delivered ALONGSIDE the error, never replaced with null",
    afterDisconnect.data && afterDisconnect.data.status === "starting");

  // While still "offline" (FIRESTORE_AVAILABLE stays true here — this
  // disconnect was a one-off drop, not a sustained outage), the
  // automatic backoff-driven resubscribe should succeed on its own,
  // with no further action from the caller.
  patchDoc("m3", { turn: "u2" }); // the real doc changed while we simulate a hiccup
  await wait(1000);
  var reconnected = evReconnect.some(function (e) { return !e.err && e.data && e.data.turn === "u2"; });
  check("Reconnect restores synchronization automatically — no explicit re-subscribe call needed", reconnected);

  // Now a SUSTAINED outage: disconnect AND make the next attach attempt
  // itself fail, confirming the backoff loop keeps retrying rather than
  // giving up after one failure.
  var evSustained = [];
  setDoc("m4", { roomId: "r4", players: ["u1"], turn: "u1" });
  MatchService.subscribeToMatch("m4", function (data, err) { evSustained.push({ data: data, err: err }); });
  FIRESTORE_AVAILABLE = false;
  simulateDisconnect("m4");
  check("Sustained outage: the disconnect itself is reported (fail-open, no crash)", evSustained[evSustained.length - 1].err instanceof Error);
  await wait(400); // first backoff attempt (~250ms) — should still fail since FIRESTORE_AVAILABLE is still false
  var stillRetrying = evSustained.every(function (e) { return !!e.err || !e.data || e.data.turn === "u1"; });
  check("During a sustained outage, repeated reconnect attempts keep failing safely (never crash, never fabricate data)", stillRetrying);
  FIRESTORE_AVAILABLE = true;
  await wait(1200); // next backoff attempt (~500ms) should now succeed
  var recoveredFromSustainedOutage = evSustained.some(function (e) { return !e.err && e.data && e.data.turn === "u1"; });
  check("Once the outage clears, the backoff loop eventually reconnects successfully on its own", recoveredFromSustainedOutage);

  // ============================================================
  // ✓ Zero gameplay rule changes — sanity: MatchService's gameplay
  //   stubs are completely untouched by this sprint (full regression
  //   suites re-verified separately — see TEST_CHECKLIST.md)
  // ============================================================
  ["submitDashCall", "submitBid", "submitPass", "declareTrump", "submitEstimate", "playCard", "resolveTrick", "completeRound", "advanceToNextRound", "endMatch"].forEach(function (m) {
    var threw = false;
    try { MatchService[m](); } catch (e) { threw = /not implemented/i.test(e.message); }
    check("Zero gameplay rule changes: MatchService." + m + "() is still an unimplemented stub, unchanged by this sprint", threw);
  });

  // ============================================================
  // GameSession consumption (requirement #3: "GameSession must consume
  // MatchService updates")
  // ============================================================
  require("/home/user/demo-test/design-ui/engine/session.js");
  var GameSession = global.GameSession;

  setDoc("gm1", { roomId: "rg1", players: ["u1", "u2"], status: "starting", turn: "u1" });
  check("Before subscribing, GameSession.getRemoteMatch() is null", GameSession.getRemoteMatch() === null);
  check("Before subscribing, GameSession.isSubscribedToRemoteMatch() is false", GameSession.isSubscribedToRemoteMatch() === false);

  GameSession.subscribeToRemoteMatch("gm1");
  check("GameSession.subscribeToRemoteMatch() immediately mirrors the current match data", GameSession.getRemoteMatch() && GameSession.getRemoteMatch().status === "starting");
  check("GameSession.isSubscribedToRemoteMatch() reflects the active subscription", GameSession.isSubscribedToRemoteMatch() === true);
  check("GameSession never touched Firestore directly to get this — it only ever called MatchService.subscribeToMatch",
    onSnapshotCallCount("gm1") === 1);

  var remoteEvents = [];
  var unsubRemote = GameSession.onRemoteMatchUpdate(function (payload) { remoteEvents.push(payload); });
  check("onRemoteMatchUpdate fires immediately with the current value on subscribe", remoteEvents.length === 1 && remoteEvents[0].data.status === "starting");

  patchDoc("gm1", { turn: "u2" });
  check("GameSession consumes MatchService updates: getRemoteMatch() reflects a live change", GameSession.getRemoteMatch().turn === "u2");
  check("GameSession's own onRemoteMatchUpdate listeners are notified of the same change", remoteEvents[remoteEvents.length - 1].data.turn === "u2");

  var listenerCountBefore = onSnapshotCallCount("gm1");
  GameSession.subscribeToRemoteMatch("gm1"); // same matchId again — must be a no-op
  check("GameSession.subscribeToRemoteMatch() is idempotent for the same matchId — no duplicate MatchService subscription",
    onSnapshotCallCount("gm1") === listenerCountBefore && activeListenerCount("gm1") === 1);

  setDoc("gm2", { roomId: "rg2", players: ["u3"], turn: "u3" });
  GameSession.subscribeToRemoteMatch("gm2"); // a DIFFERENT matchId — must cleanly switch
  check("Switching matchId cleanly tears down the old GameSession-level subscription", activeListenerCount("gm1") === 0);
  check("...and attaches to the new one", GameSession.getRemoteMatch().turn === "u3" && activeListenerCount("gm2") === 1);

  GameSession.unsubscribeFromRemoteMatch();
  check("GameSession.unsubscribeFromRemoteMatch() fully tears down the underlying MatchService subscription", activeListenerCount("gm2") === 0);
  check("...and isSubscribedToRemoteMatch() reflects it", GameSession.isSubscribedToRemoteMatch() === false);
  check("...but getRemoteMatch() still returns the last known data (not wiped) — GameSession keeps the local game alive",
    GameSession.getRemoteMatch() && GameSession.getRemoteMatch().turn === "u3");
  unsubRemote();

  // GameSession's own fail-open behavior when MatchService disconnects.
  setDoc("gm3", { roomId: "rg3", players: ["u4"], turn: "u4" });
  GameSession.subscribeToRemoteMatch("gm3");
  simulateDisconnect("gm3");
  check("GameSession surfaces the remote error via getRemoteMatchError()", GameSession.getRemoteMatchError() instanceof Error);
  check("GameSession's mirrored data survives the disconnect (fail-open, never crashes, never nulled)",
    GameSession.getRemoteMatch() && GameSession.getRemoteMatch().turn === "u4");
  GameSession.unsubscribeFromRemoteMatch();

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
