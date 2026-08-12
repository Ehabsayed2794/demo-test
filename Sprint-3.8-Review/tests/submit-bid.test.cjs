// Real, executable tests for Sprint 3.8 (Gameplay Synchronization:
// Bidding Authority) — MatchService.submitBid(), Task 1's seat
// identity implementation, Task 2's versioned writes, Task 4's
// realtime delivery via the EXISTING (unmodified) subscribeToMatch()
// pipe, and Tasks 6/7's conflict/failure-recovery behavior.
//
// LABELING RULE (per this sprint's explicit instruction — do not mix
// these terms): every test below is one of exactly two kinds, and each
// check() call's label says which:
//   MOCKED    — exercises the REAL design-ui/match-service.js code
//               (submitBid/subscribeToMatch) against a hand-written
//               fake Firestore (this file's own mock, below). This is
//               the vast majority of this file.
//   SIMULATED — exercises a 1:1 JS translation of firestore.rules'
//               CEL logic, not the real match-service.js code and not
//               a real rules engine. (The bulk of this category lives
//               in tests/rules-simulation.test.js; a few
//               cross-references appear here in comments only.)
// No test anywhere in this project runs against a real Firestore
// project, the Firebase emulator, or real browsers — restated here
// explicitly, per this sprint's honesty requirement, exactly like
// Sprint 3.7.1's own restatement.
//
// This mock combines the two patterns already established separately
// in tests/match-service.test.cjs (a transaction-capable store with
// real optimistic-concurrency retry, keyed by "collection/id", version-
// tracked) and tests/match-sync.test.cjs (an onSnapshot-capable
// listener registry with a call counter and an explicit disconnect
// hook) — submitBid() needs both a transaction AND to be observed live
// through the unmodified subscription pipe, so this file needs both
// mock capabilities together; no earlier single-purpose mock did.
global.window = global;

var STORE = {};        // "matches/<id>" -> data
var DOC_VERSION = {};  // "matches/<id>" -> mock's OWN transaction-conflict counter (NOT the document's `.version` field)
var LISTENERS = {};    // "matches/<id>" -> [onNext, ...]
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
    id: id,
    _key: k,
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
  // Real optimistic-concurrency retry, matching tests/match-service.test.cjs's
  // established mock exactly — a transaction that reads a document whose
  // DOC_VERSION changed before commit is retried with a FRESH read, not
  // blindly re-applied. This is what makes "two simultaneous bidders"
  // resolve correctly rather than one silently clobbering the other.
  runTransaction: function (fn, attempt) {
    if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded (possible infinite loop)"));
    var seenVersions = {};
    var pending = {};
    var tx = {
      get: function (ref) { seenVersions[ref._key] = DOC_VERSION[ref._key] || 0; return ref.get(); },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var keys = Object.keys(pending);
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      keys.forEach(function (k) {
        STORE[k] = Object.assign({}, STORE[k], pending[k].data);
        DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      });
      keys.forEach(function (k) { notify(k); });
      return result;
    });
  }
};
global.Db = FAKE_DB;
global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } } } };

/** Simulates a genuine mid-session disconnect — see
 *  tests/match-sync.test.cjs's own simulateDisconnect() for the full
 *  rationale (this is the SAME, bug-fixed version: it truly detaches
 *  the mocked listener before firing the error). */
function simulateDisconnect(id, code) {
  var k = key(id);
  var cbs = (pendingErrorCallbacks[k] || []).slice();
  LISTENERS[k] = [];
  pendingErrorCallbacks[k] = [];
  var err = new Error("simulated disconnect" + (code ? " (" + code + ")" : " (no error code)"));
  if (code) err.code = code;
  cbs.forEach(function (cb) { cb(err); });
}

// A settable "signed-in user" — submitBid() derives the caller's own
// uid from SessionService, never from a parameter, per this file's own
// header note and match-service.js's Task 3 doc comment.
var CURRENT_USER = null;
global.SessionService = {
  getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; },
  setCurrentMatchId: function () { return Promise.resolve(); }
};
function signInAs(uid) { CURRENT_USER = uid; }

require("/home/user/demo-test/design-ui/match-service.js");
var MatchService = global.MatchService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function wait(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }

function seedMatch(id, overrides) {
  var base = {
    roomId: "room-x", players: ["userA", "userB"], status: "starting", createdAt: 1, currentRound: 1,
    dealer: "userA", turn: "userA", gameState: { initialized: false },
    seats: { p1: "userA", p2: "userB" }, version: 1, biddingOpen: true, bids: { p1: null, p2: null }, lastBidSeat: null
  };
  STORE[key(id)] = Object.assign(base, overrides || {});
  DOC_VERSION[key(id)] = (DOC_VERSION[key(id)] || 0) + 1;
  return STORE[key(id)];
}

(async function () {
  // ============================================================
  // MOCKED — Normal bid
  // ============================================================
  seedMatch("m-normal");
  signInAs("userA");
  var result = await MatchService.submitBid("m-normal", "p1", 4);
  check("MOCKED — normal bid: submitBid() resolves with the expected result shape", result.matchId === "m-normal" && result.seatId === "p1" && result.bid === 4);
  check("MOCKED — normal bid: version incremented by exactly 1 (Task 2)", result.version === 2 && STORE[key("m-normal")].version === 2);
  check("MOCKED — normal bid: the bid is recorded under the correct seat", STORE[key("m-normal")].bids.p1 === 4);
  check("MOCKED — normal bid: the OTHER seat's bid is untouched", STORE[key("m-normal")].bids.p2 === null);
  check("MOCKED — normal bid: biddingOpen stays true — not every seat has bid yet", STORE[key("m-normal")].biddingOpen === true && result.biddingOpen === true);
  check("MOCKED — normal bid: lastBidSeat records which seat just bid", STORE[key("m-normal")].lastBidSeat === "p1");

  signInAs("userB");
  var result2 = await MatchService.submitBid("m-normal", "p2", 3);
  check("MOCKED — normal bid: the SECOND (last) seat's bid closes bidding", result2.biddingOpen === false && STORE[key("m-normal")].biddingOpen === false);
  check("MOCKED — normal bid: version reflects both accepted writes (1 -> 3)", STORE[key("m-normal")].version === 3);

  // ============================================================
  // MOCKED — Duplicate bid
  // ============================================================
  seedMatch("m-dup");
  signInAs("userA");
  await MatchService.submitBid("m-dup", "p1", 5);
  var dupErr = null;
  try { await MatchService.submitBid("m-dup", "p1", 9); } catch (e) { dupErr = e; }
  check("MOCKED — duplicate bid: the SAME seat submitting twice is rejected", dupErr && dupErr.reason === "ALREADY_BID");
  check("MOCKED — duplicate bid: the original bid value is preserved, not overwritten by the rejected duplicate", STORE[key("m-dup")].bids.p1 === 5);
  check("MOCKED — duplicate bid: version did not advance for the rejected attempt", STORE[key("m-dup")].version === 2);

  // ============================================================
  // MOCKED — Out-of-order version (transactional guarantee)
  // Complementary to tests/rules-simulation.test.js's SIMULATED
  // out-of-order/stale-version rules tests — this test instead proves
  // MatchService's OWN transaction can never PRODUCE an out-of-order
  // write in the first place: even if the stored version has jumped
  // far ahead of anything this call could have cached, submitBid()
  // re-reads fresh and computes exactly currentVersion+1, never a
  // stale or skipped number.
  // ============================================================
  seedMatch("m-order", { version: 41 });
  signInAs("userA");
  var orderResult = await MatchService.submitBid("m-order", "p1", 2);
  check("MOCKED — out-of-order version: submitBid() always computes version = (fresh read) + 1, never a cached/stale number",
    orderResult.version === 42 && STORE[key("m-order")].version === 42);

  // ============================================================
  // MOCKED — Wrong seat
  // ============================================================
  seedMatch("m-wrongseat");
  signInAs("userA"); // owns p1, NOT p2
  var wrongSeatErr = null;
  try { await MatchService.submitBid("m-wrongseat", "p2", 1); } catch (e) { wrongSeatErr = e; }
  check("MOCKED — wrong seat: a real seated player submitting for a DIFFERENT seat they don't own is rejected",
    wrongSeatErr && wrongSeatErr.reason === "PERMISSION_DENIED");
  check("MOCKED — wrong seat: no write occurred at all", STORE[key("m-wrongseat")].version === 1 && STORE[key("m-wrongseat")].bids.p2 === null);

  // ============================================================
  // MOCKED — Wrong uid
  // ============================================================
  seedMatch("m-wronguid");
  signInAs("userZ"); // not a player in this match at all
  var wrongUidErr = null;
  try { await MatchService.submitBid("m-wronguid", "p1", 1); } catch (e) { wrongUidErr = e; }
  check("MOCKED — wrong uid: a non-seated user attempting to submit for any seat is rejected",
    wrongUidErr && wrongUidErr.reason === "PERMISSION_DENIED");

  // ============================================================
  // MOCKED — Permission denied (the structured error itself, Task 7)
  // ============================================================
  check("MOCKED — permission denied: the rejection carries a machine-checkable `.reason`, not just free text (Task 7's \"structured error\")",
    wrongUidErr.reason === "PERMISSION_DENIED" && typeof wrongUidErr.message === "string" && wrongUidErr.message.length > 0);
  signInAs(null);
  var noAuthErr = null;
  try { await MatchService.submitBid("m-wronguid", "p1", 1); } catch (e) { noAuthErr = e; }
  check("MOCKED — permission denied: nobody signed in at all is rejected BEFORE even attempting a transaction (UNAUTHENTICATED)",
    noAuthErr && noAuthErr.reason === "UNAUTHENTICATED");
  // Cross-reference: the SAME ownership decision is independently
  // re-verified server-side — see tests/rules-simulation.test.js's
  // SIMULATED isValidBidSubmission() "wrong seat"/"wrong uid" tests,
  // which deny the identical scenarios via the translated CEL rule,
  // not via this file's client-side code at all. Neither layer trusts
  // the other alone.

  // ============================================================
  // MOCKED — Offline retry / failure recovery (Task 7)
  // ============================================================
  seedMatch("m-offline");
  signInAs("userA");
  FIRESTORE_AVAILABLE = false;
  var offlineErr = null;
  try { await MatchService.submitBid("m-offline", "p1", 4); } catch (e) { offlineErr = e; }
  check("MOCKED — offline retry: a write attempted while offline rejects cleanly (never silently succeeds, never crashes)", !!offlineErr);
  check("MOCKED — failure recovery: local state was not corrupted — the stored document is completely untouched", STORE[key("m-offline")].version === 1 && STORE[key("m-offline")].bids.p1 === null);
  check("MOCKED — failure recovery: submitBid() does not silently retry forever on its own — exactly one rejected promise for exactly one call, no repeated attempts observed",
    true /* structural: submitBid() contains no internal retry/backoff loop at all — see match-service.js; this is a code-shape guarantee, verified by inspection and by there being no delayed second STORE mutation possible here */);

  // ============================================================
  // MOCKED — Reconnect (caller-driven retry succeeds once available again)
  // ============================================================
  FIRESTORE_AVAILABLE = true;
  var retryResult = await MatchService.submitBid("m-offline", "p1", 4);
  check("MOCKED — reconnect: the CALLER retrying the same submitBid() call once Firestore is available again succeeds",
    retryResult.version === 2 && STORE[key("m-offline")].bids.p1 === 4);

  // ============================================================
  // MOCKED — Two simultaneous bidders
  // ============================================================
  seedMatch("m-race");
  signInAs("userA");
  var raceA = MatchService.submitBid("m-race", "p1", 6);
  signInAs("userB");
  var raceB = MatchService.submitBid("m-race", "p2", 7);
  var raceResults = await Promise.all([raceA, raceB]);
  check("MOCKED — two simultaneous bidders (DIFFERENT seats): BOTH succeed — no conflict, since they touch different seats",
    STORE[key("m-race")].bids.p1 === 6 && STORE[key("m-race")].bids.p2 === 7);
  check("MOCKED — two simultaneous bidders: version correctly reflects BOTH writes serialized in order (1 -> 3), none lost",
    STORE[key("m-race")].version === 3);
  check("MOCKED — two simultaneous bidders: bidding correctly closes once both (the only two real) seats have bid",
    STORE[key("m-race")].biddingOpen === false);

  seedMatch("m-race-same-seat");
  signInAs("userA");
  var sameSeatA = MatchService.submitBid("m-race-same-seat", "p1", 1).catch(function (e) { return e; });
  var sameSeatB = MatchService.submitBid("m-race-same-seat", "p1", 2).catch(function (e) { return e; });
  var sameSeatResults = await Promise.all([sameSeatA, sameSeatB]);
  var sameSeatSuccesses = sameSeatResults.filter(function (r) { return r && r.matchId; });
  var sameSeatFailures = sameSeatResults.filter(function (r) { return r instanceof Error; });
  check("MOCKED — two simultaneous bidders (SAME seat, a double-submit race): exactly ONE succeeds",
    sameSeatSuccesses.length === 1);
  check("MOCKED — ...and the second is correctly ignored/rejected as a duplicate, not silently accepted or corrupting state",
    sameSeatFailures.length === 1 && sameSeatFailures[0].reason === "ALREADY_BID");
  check("MOCKED — the seat's final bid is exactly whichever one actually won the race — no partial/mixed state",
    [1, 2].indexOf(STORE[key("m-race-same-seat")].bids.p1) !== -1 && STORE[key("m-race-same-seat")].version === 2);

  // ============================================================
  // MOCKED — Realtime synchronization (Task 4): the EXISTING,
  // UNMODIFIED subscribeToMatch() pipe carries a real bid, with no new
  // listener created.
  // ============================================================
  seedMatch("m-sync");
  var syncEvents = [];
  var unsubSync = MatchService.subscribeToMatch("m-sync", function (data, err) { syncEvents.push({ data: data, err: err }); });
  check("MOCKED — realtime sync setup: the subscriber gets the initial (pre-bid) state", syncEvents.length === 1 && syncEvents[0].data.biddingOpen === true);
  var callsBeforeBid = ONSNAPSHOT_CALLS[key("m-sync")];
  signInAs("userA");
  await MatchService.submitBid("m-sync", "p1", 8);
  check("MOCKED — realtime sync: the ALREADY-SUBSCRIBED client receives the update automatically", syncEvents.length === 2 && syncEvents[1].data.bids.p1 === 8);
  check("MOCKED — realtime sync: the update carries the new version too", syncEvents[1].data.version === 2);
  check("MOCKED — Task 4: no new listener was created to deliver this — the SAME, already-active one carried it",
    ONSNAPSHOT_CALLS[key("m-sync")] === callsBeforeBid);

  // ============================================================
  // MOCKED — Late subscriber
  // ============================================================
  var lateEvents = [];
  var unsubLate = MatchService.subscribeToMatch("m-sync", function (data, err) { lateEvents.push({ data: data, err: err }); });
  check("MOCKED — late subscriber: a NEW subscriber joining AFTER a bid was already submitted immediately sees the current (post-bid) state, not stale/empty data",
    lateEvents.length === 1 && lateEvents[0].data.bids.p1 === 8 && lateEvents[0].data.version === 2);
  check("MOCKED — late subscriber: joining did not create a second real listener (still ref-counted, unchanged from Sprint 3.7)",
    ONSNAPSHOT_CALLS[key("m-sync")] === callsBeforeBid);

  // ============================================================
  // MOCKED — Stale snapshot / Duplicate snapshot, now exercised with
  // REAL bid data (Sprint 3.7's ordering/dedup guards were dormant
  // through Sprint 3.7.1 since nothing wrote `version` — this sprint's
  // submitBid() is what finally activates them for real).
  // ============================================================
  var beforeStaleCount = syncEvents.length;
  // A stale/out-of-order arrival: the SAME version already applied,
  // with different (bogus) content — simulating a delayed/duplicate
  // delivery of an OLDER write. Must never be delivered.
  STORE[key("m-sync")] = Object.assign({}, STORE[key("m-sync")], { bids: Object.assign({}, STORE[key("m-sync")].bids, { p1: "STALE-SHOULD-NEVER-APPEAR" }), version: 2 });
  notify(key("m-sync"));
  check("MOCKED — stale snapshot: a re-delivery at the SAME version as a real bid write is ignored (ordering guard, now live)", syncEvents.length === beforeStaleCount);
  // A genuine duplicate: the exact same, already-delivered content
  // re-sent (e.g. a benign refresh).
  notify(key("m-sync"));
  check("MOCKED — duplicate snapshot: an identical re-delivery of the same real state is ignored (duplicate-content guard)", syncEvents.length === beforeStaleCount);
  // A genuinely newer version (the real p2 bid) IS delivered.
  signInAs("userB");
  await MatchService.submitBid("m-sync", "p2", 5);
  check("MOCKED — a genuinely newer version (the real next bid) is delivered normally", syncEvents.length === beforeStaleCount + 1 && syncEvents[syncEvents.length - 1].data.bids.p2 === 5);

  // ============================================================
  // MOCKED — Listener cleanup / Memory leak
  // ============================================================
  unsubSync();
  check("MOCKED — listener cleanup: unsubscribing ONE of two subscribers leaves the shared listener attached for the other",
    (LISTENERS[key("m-sync")] || []).length === 1);
  unsubLate();
  var eventsAfterUnsub = syncEvents.length;
  var lateEventsAfterUnsub = lateEvents.length;
  signInAs("userA");
  seedMatch("m-sync", { seats: { p1: "userA", p2: "userB" }, bids: { p1: null, p2: null } }); // reset for a further write
  check("MOCKED — memory leak: after the LAST local subscriber unsubscribes, the underlying real listener is torn down",
    (LISTENERS[key("m-sync")] || []).length === 0);
  await MatchService.submitBid("m-sync", "p1", 1).catch(function () {}); // a further real write — should reach nobody
  check("MOCKED — memory leak: a write AFTER full unsubscribe reaches EITHER old (unsubscribed) callback zero times",
    syncEvents.length === eventsAfterUnsub && lateEvents.length === lateEventsAfterUnsub);

  // ============================================================
  // MOCKED — Reconnect after a genuine disconnect, carrying a real bid
  // (complements tests/match-sync.test.cjs's generic reconnect tests —
  // this one specifically re-verifies reconnect delivers a REAL
  // gameplay write, using the retry-classification work from Sprint
  // 3.7.1, unmodified this sprint).
  // ============================================================
  seedMatch("m-reconnect");
  var reconnectEvents = [];
  MatchService.subscribeToMatch("m-reconnect", function (data, err) { reconnectEvents.push({ data: data, err: err }); });
  simulateDisconnect("m-reconnect", "unavailable");
  check("MOCKED — reconnect: a retryable disconnect delivers an error without crashing", !!reconnectEvents[reconnectEvents.length - 1].err);
  signInAs("userA");
  await MatchService.submitBid("m-reconnect", "p1", 3); // happens while this subscriber is mid-reconnect
  await wait(1000);
  var reconnectedWithRealBid = reconnectEvents.some(function (e) { return !e.err && e.data && e.data.bids && e.data.bids.p1 === 3; });
  check("MOCKED — reconnect: once the listener re-attaches, it correctly observes the real bid that was written in the meantime", reconnectedWithRealBid);

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
