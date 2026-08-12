// Real, executable tests for design-ui/match-service.js — startMatch /
// loadMatch / subscribeToMatch (Sprint 3.4, hardened in Sprint 3.4.1) —
// plus a cross-service integration section proving RoomService.setReady
// really does trigger MatchService.startMatch end-to-end through the
// SAME fake Firestore instance, not just MatchService in isolation.
//
// Sprint 3.4.1 (Match Start Consistency & Security Hotfix):
//   - createMatch() was REMOVED from the public API — see this file's
//     "createMatch is no longer public" test and match-service.js's
//     header comment.
//   - MatchService no longer writes currentMatchId onto every room
//     player's own profile (that write could only ever succeed for the
//     INITIATING player, since players/{uid} is owner-only — every
//     other player's write silently failed). It now self-syncs ONLY the
//     calling client's own profile via SessionService.setCurrentMatchId()
//     — a method with no uid parameter at all. global.PlayerService
//     below records every call it receives; every test asserts none of
//     them ever touch currentMatchId (the Sprint 3.4 bug's exact write
//     path) — a real, checked regression guard, not an assumption.
//
// This mock is a generalization of tests/room-service.test.cjs's single-
// collection fake: it's a single shared STORE keyed by "collection/id"
// strings, with a VERSION counter per key, and transactions that can
// tx.get/tx.set/tx.update MULTIPLE docs across MULTIPLE collections in
// one call — required because MatchService.startMatch() spans both
// rooms/{roomId} and matches/{matchId} in a single transaction. Retries
// are driven by real optimistic-concurrency version comparison (not a
// naive read-then-write mock), so concurrency tests here actually
// exercise retry-on-conflict, matching the existing project methodology.
global.window = global;

var STORE = {};    // "collection/id" -> data
var VERSION = {};  // "collection/id" -> version counter
var LISTENERS = {}; // "collection/id" -> [callback, ...] (onSnapshot)
var FAKE_TS = { __sentinel: "serverTimestamp" };
var idCounter = 0;
var FIRESTORE_AVAILABLE = true;

global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return FAKE_TS; } } } };

function key(collection, id) { return collection + "/" + id; }

function notifyListeners(k) {
  (LISTENERS[k] || []).forEach(function (cb) {
    var exists = Object.prototype.hasOwnProperty.call(STORE, k);
    cb({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
  });
}

function makeRef(collection, id) {
  var k = key(collection, id);
  return {
    id: id,
    _collection: collection,
    _key: k,
    get: function () {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    set: function (data) {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      STORE[k] = Object.assign({}, data);
      VERSION[k] = (VERSION[k] || 0) + 1;
      notifyListeners(k);
      return Promise.resolve();
    },
    update: function (patch) {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      STORE[k] = Object.assign({}, STORE[k], patch);
      VERSION[k] = (VERSION[k] || 0) + 1;
      notifyListeners(k);
      return Promise.resolve();
    },
    onSnapshot: function (onNext, onError) {
      if (!FIRESTORE_AVAILABLE) { onError(new Error("simulated Firestore unavailable")); return function () {}; }
      LISTENERS[k] = LISTENERS[k] || [];
      LISTENERS[k].push(onNext);
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      onNext({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
      return function unsubscribe() {
        LISTENERS[k] = (LISTENERS[k] || []).filter(function (cb) { return cb !== onNext; });
      };
    }
  };
}

var FAKE_DB = {
  collection: function (name) {
    if (name !== "rooms" && name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { if (!id) id = name + "-" + (++idCounter); return makeRef(name, id); } };
  },
  runTransaction: function (fn, attempt) {
    if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded (possible infinite loop)"));
    var seenVersions = {};
    var pending = {}; // key -> { ref, patch, mode: "set"|"update" }
    var tx = {
      get: function (ref) { seenVersions[ref._key] = VERSION[ref._key] || 0; return ref.get(); },
      set: function (ref, data) { pending[ref._key] = { ref: ref, data: data, mode: "set" }; },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, data: patch, mode: "update" }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var keys = Object.keys(pending);
      // Conflict check across ALL docs this transaction touched (read or written).
      var conflict = Object.keys(seenVersions).some(function (k) {
        return (VERSION[k] || 0) !== seenVersions[k];
      });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      keys.forEach(function (k) {
        var entry = pending[k];
        if (entry.mode === "set") {
          STORE[k] = Object.assign({}, entry.data);
        } else {
          STORE[k] = Object.assign({}, STORE[k], entry.data);
        }
        VERSION[k] = (VERSION[k] || 0) + 1;
      });
      keys.forEach(function (k) { notifyListeners(k); });
      return result;
    });
  }
};
global.Db = FAKE_DB;

// Sprint 3.4.1: RoomService legitimately still calls
// PlayerService.updatePlayerProfile for its OWN, unrelated,
// already-self-only currentRoomId sync (createRoom/joinRoom/leaveRoom —
// unchanged, out of scope for this hotfix). What must NEVER happen
// again is MatchService writing currentMatchId through this API for
// anyone — that was exactly the Sprint 3.4 bug (players/{uid} is
// owner-only; the write could only ever succeed for the initiating
// player). So this mock records every call and every test below can
// assert none of them ever touched currentMatchId, rather than a
// blanket throw that would also (incorrectly) flag RoomService's own,
// legitimate, unrelated self-sync.
var updateProfileCalls = [];
global.PlayerService = {
  updatePlayerProfile: function (uid, patch) {
    updateProfileCalls.push({ uid: uid, patch: patch });
    return Promise.resolve();
  }
};
function noProfileCallTouchedCurrentMatchId() {
  return !updateProfileCalls.some(function (c) { return c.patch && Object.prototype.hasOwnProperty.call(c.patch, "currentMatchId"); });
}

var setCurrentMatchIdCalls = [];
var refreshCalls = 0;
global.SessionService = {
  refresh: function () { refreshCalls++; return Promise.resolve(); },
  setCurrentMatchId: function (matchId) {
    // Deliberately takes ONLY matchId — no uid parameter exists on this
    // API at all, so there is nothing for a caller to misuse to target
    // another player. See session-service.js.
    setCurrentMatchIdCalls.push(matchId);
    return Promise.resolve();
  }
};

require("/home/user/demo-test/design-ui/match-service.js");
var MatchService = global.MatchService;
global.MatchService = MatchService; // so room-service.js's maybeStartMatch can see it

require("/home/user/demo-test/design-ui/room-service.js");
var RoomService = global.RoomService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function makeRoom(id, patch) {
  var base = { name: "Room", status: "waiting", creator: "p1", players: ["p1", "p2"], readyPlayers: [], createdAt: FAKE_TS, updatedAt: FAKE_TS };
  STORE[key("rooms", id)] = Object.assign(base, patch || {});
  VERSION[key("rooms", id)] = (VERSION[key("rooms", id)] || 0) + 1;
}

(async function () {
  // ============ Public API cleanup (Sprint 3.4.1, Task 3) ============
  check("createMatch is no longer part of the public MatchService API — RoomService/UI must use startMatch() only",
    typeof MatchService.createMatch === "undefined");

  // ============ startMatch: happy path + full document shape ============
  makeRoom("room-start-1", { players: ["p1", "p2"], readyPlayers: ["p1", "p2"] });
  setCurrentMatchIdCalls.length = 0; refreshCalls = 0;
  var matchId2 = await MatchService.startMatch("room-start-1");
  check("startMatch resolves a matchId", typeof matchId2 === "string" && matchId2.length > 0);
  var doc2 = STORE[key("matches", matchId2)];
  check("startMatch: match document created", !!doc2);
  check("startMatch: roomId set correctly", doc2.roomId === "room-start-1");
  check("startMatch: players copied from room", JSON.stringify(doc2.players) === JSON.stringify(["p1", "p2"]));
  check("startMatch: status is 'starting'", doc2.status === "starting");
  check("startMatch: currentRound is 1", doc2.currentRound === 1);
  check("startMatch: dealer defaults to room.creator", doc2.dealer === "p1");
  check("startMatch: turn defaults to dealer", doc2.turn === "p1");
  check("startMatch: gameState is the documented TODO placeholder, not fabricated dealt hands",
    doc2.gameState && doc2.gameState.initialized === false && typeof doc2.gameState.todo === "string" && /Deck/.test(doc2.gameState.todo));
  check("startMatch: room status becomes 'in_game'", STORE[key("rooms", "room-start-1")].status === "in_game");
  check("startMatch: room.matchId set to the new matchId", STORE[key("rooms", "room-start-1")].matchId === matchId2);

  // ============ Sprint 3.8: seat identity + version + bidding sync fields ============
  check("startMatch (Sprint 3.8, Task 1): seats assigned positionally from room.players — p1->\"p1\", p2->\"p2\"",
    JSON.stringify(doc2.seats) === JSON.stringify({ p1: "p1", p2: "p2" }));
  check("startMatch (Sprint 3.8, Task 1): only real seats exist — no fabricated p3/p4 for a 2-player match",
    Object.keys(doc2.seats).length === 2 && !("p3" in doc2.seats) && !("p4" in doc2.seats));
  check("startMatch (Sprint 3.8, Task 2): version starts at 1", doc2.version === 1);
  check("startMatch (Sprint 3.8, Task 3): biddingOpen starts true", doc2.biddingOpen === true);
  check("startMatch (Sprint 3.8, Task 3): bids starts with one null slot per real seat, no more",
    JSON.stringify(doc2.bids) === JSON.stringify({ p1: null, p2: null }));
  check("startMatch (Sprint 3.8, Task 3): lastBidSeat starts null", doc2.lastBidSeat === null);
  check("startMatch (Sprint 4.2): cardLog starts as an empty array", Array.isArray(doc2.cardLog) && doc2.cardLog.length === 0);
  check("startMatch (Sprint 4.2): lastCardSeat starts null", doc2.lastCardSeat === null);

  // ============ Sprint 3.4.1: currentMatchId propagation fix ============
  check("startMatch: self-syncs currentMatchId via SessionService.setCurrentMatchId(matchId) — SELF ONLY",
    setCurrentMatchIdCalls.length === 1 && setCurrentMatchIdCalls[0] === matchId2);
  check("startMatch: never attempts to write currentMatchId via PlayerService.updatePlayerProfile at all " +
    "(the Sprint 3.4 bug's exact write path) — only ever via SessionService.setCurrentMatchId, checked above",
    noProfileCallTouchedCurrentMatchId());

  // ============ startMatch: rejects if not all ready ============
  makeRoom("room-notready-1", { players: ["p1", "p2"], readyPlayers: ["p1"] });
  var notReadyErr = null;
  try { await MatchService.startMatch("room-notready-1"); } catch (e) { notReadyErr = e; }
  check("startMatch rejects when not everyone is ready (defense in depth)", notReadyErr && /not all players are ready/i.test(notReadyErr.message));
  check("startMatch: rejecting for not-ready leaves room untouched", STORE[key("rooms", "room-notready-1")].status === "waiting" && !STORE[key("rooms", "room-notready-1")].matchId);

  var startNoRoomErr = null;
  try { await MatchService.startMatch("does-not-exist-either"); } catch (e) { startNoRoomErr = e; }
  check("startMatch rejects for a nonexistent room", startNoRoomErr && /not found/i.test(startNoRoomErr.message));

  // ============ startMatch: duplicate start prevented (sequential) ============
  makeRoom("room-dup-1", { players: ["p1", "p2"], readyPlayers: ["p1", "p2"] });
  var dupMatchIdA = await MatchService.startMatch("room-dup-1");
  var matchCountBefore = Object.keys(STORE).filter(function (k) { return k.indexOf("matches/") === 0; }).length;
  var dupMatchIdB = await MatchService.startMatch("room-dup-1");
  var matchCountAfter = Object.keys(STORE).filter(function (k) { return k.indexOf("matches/") === 0; }).length;
  check("startMatch called twice for the same room is idempotent: same matchId returned", dupMatchIdA === dupMatchIdB);
  check("startMatch called twice for the same room: no second match document created", matchCountBefore === matchCountAfter);
  check("startMatch: idempotent (already-existing) return still self-syncs the caller's own profile",
    setCurrentMatchIdCalls.indexOf(dupMatchIdB) !== -1);

  // ============ startMatch: two CONCURRENT calls for the same room produce
  // exactly one match — the core "two players pressing Ready simultaneously
  // cannot create two matches" guarantee ============
  makeRoom("room-race-1", { players: ["p1", "p2"], readyPlayers: ["p1", "p2"] });
  var raceResults = await Promise.all([
    MatchService.startMatch("room-race-1"),
    MatchService.startMatch("room-race-1")
  ]);
  check("two concurrent startMatch calls resolve to the SAME matchId", raceResults[0] === raceResults[1]);
  var raceMatchDocs = Object.keys(STORE).filter(function (k) {
    return k.indexOf("matches/") === 0 && STORE[k].roomId === "room-race-1";
  });
  check("two concurrent startMatch calls create exactly ONE match document for the room", raceMatchDocs.length === 1);

  // ============ loadMatch ============
  var loaded = await MatchService.loadMatch(matchId2);
  check("loadMatch resolves the match document", loaded && loaded.roomId === "room-start-1");
  var loadedMissing = await MatchService.loadMatch("no-such-match-id");
  check("loadMatch resolves null (not an error) for a nonexistent match", loadedMissing === null);

  // ============ subscribeToMatch ============
  var subEvents = [];
  var unsub = MatchService.subscribeToMatch(matchId2, function (data, err) { subEvents.push({ data: data, err: err }); });
  check("subscribeToMatch delivers an immediate snapshot with the current data", subEvents.length === 1 && subEvents[0].data && subEvents[0].data.roomId === "room-start-1");
  // Sprint 3.8: matches/{matchId} documents now carry a real `version`
  // FIELD (Task 2 — distinct from this mock's OWN, unrelated VERSION[]
  // transaction-conflict counter bumped above). subscribeToMatch()'s
  // Sprint 3.7 ordering guard (dormant until this sprint) is now LIVE
  // for every match document, so a direct test mutation that changes
  // content WITHOUT bumping `.version` is correctly treated as stale
  // and ignored — exactly like a real out-of-band write with no
  // version bump would be. Bumping `.version` here simulates what any
  // REAL write path (i.e. submitBid()) always does.
  STORE[key("matches", matchId2)].status = "in_progress";
  STORE[key("matches", matchId2)].version = (STORE[key("matches", matchId2)].version || 0) + 1;
  notifyListeners(key("matches", matchId2));
  check("subscribeToMatch delivers a second snapshot on change", subEvents.length === 2 && subEvents[1].data.status === "in_progress");
  unsub();
  STORE[key("matches", matchId2)].status = "changed-after-unsub";
  STORE[key("matches", matchId2)].version = (STORE[key("matches", matchId2)].version || 0) + 1;
  notifyListeners(key("matches", matchId2));
  check("subscribeToMatch's unsubscribe function actually stops delivery", subEvents.length === 2);

  var subMissingEvents = [];
  MatchService.subscribeToMatch("no-such-match-id", function (data, err) { subMissingEvents.push({ data: data, err: err }); });
  check("subscribeToMatch delivers (null, null-ish exists:false) for a nonexistent match, not a thrown error", subMissingEvents.length === 1 && subMissingEvents[0].data === null && subMissingEvents[0].err === null);

  // ============ Firestore unavailable ============
  FIRESTORE_AVAILABLE = false;
  var offlineStartErr = null;
  try { await MatchService.startMatch("room-start-1"); } catch (e) { offlineStartErr = e; }
  check("startMatch surfaces a clear rejection when Firestore is unavailable", offlineStartErr && /unavailable/i.test(offlineStartErr.message));
  var offlineSubEvents = [];
  MatchService.subscribeToMatch(matchId2, function (data, err) { offlineSubEvents.push({ data: data, err: err }); });
  check("subscribeToMatch delivers (null, err) when Firestore is unavailable, rather than throwing", offlineSubEvents.length === 1 && offlineSubEvents[0].data === null && !!offlineSubEvents[0].err);
  FIRESTORE_AVAILABLE = true;

  // ============ Not-yet-implemented gameplay methods remain stubs ============
  // Sprint 3.8: submitBid() is EXCLUDED from this loop — it is no
  // longer a stub (see tests/submit-bid.test.cjs for its own full,
  // dedicated test suite). Every OTHER gameplay method remains
  // unimplemented, unchanged, per this sprint's explicit "only
  // synchronize bidding" scope.
  ["submitDashCall", "submitPass", "declareTrump", "submitEstimate", "playCard", "resolveTrick", "completeRound", "advanceToNextRound", "endMatch"].forEach(function (m) {
    var threw = false;
    try { MatchService[m](); } catch (e) { threw = /not implemented/i.test(e.message); }
    check("MatchService." + m + "() still throws Not implemented (bidding/estimation/card-play out of scope this sprint)", threw);
  });

  // Sprint 3.8: submitBid() with missing arguments rejects (it's now a
  // real, Promise-based, transactional method — argument validation
  // rejects rather than throwing synchronously, matching startMatch()'s
  // own established convention).
  var submitBidNoArgsErr = null;
  try { await MatchService.submitBid(); } catch (e) { submitBidNoArgsErr = e; }
  check("MatchService.submitBid() with no arguments rejects with a structured INVALID_ARGUMENT error (not a synchronous throw, not silently resolved)",
    submitBidNoArgsErr && submitBidNoArgsErr.reason === "INVALID_ARGUMENT");

  // ============================================================
  // Cross-service integration: RoomService.setReady triggers
  // MatchService.startMatch automatically, end-to-end, through the
  // SAME fake Firestore instance — not MatchService called directly.
  // Sprint 3.4.1: setReady() now AWAITS maybeStartMatch() (was fire-
  // and-forget), so no manual setTimeout tick-wait is needed anymore to
  // observe its effects — the returned room.matchStart is available the
  // moment setReady()'s own promise resolves.
  // ============================================================

  setCurrentMatchIdCalls.length = 0;

  // Two players join a room; the SECOND ready-up (not the first) is the
  // one that should cross the "everyone is ready" threshold and trigger
  // startMatch.
  var introRoomId = await RoomService.createRoom("i1", "Integration Room");
  await RoomService.joinRoom(introRoomId, "i2");
  var roomAfterFirstReady = await RoomService.setReady(introRoomId, "i1", true);
  check("integration: room not yet all-ready after only one player readies — no match yet", !STORE[key("rooms", introRoomId)].matchId);
  check("integration: matchStart.allReady is false while not everyone is ready", roomAfterFirstReady.matchStart.allReady === false && roomAfterFirstReady.matchStart.started === false);

  var roomAfterSecondReady = await RoomService.setReady(introRoomId, "i2", true);
  check("integration: RoomService.setReady (last player ready) triggers MatchService.startMatch automatically",
    !!STORE[key("rooms", introRoomId)].matchId);
  check("integration: room status becomes 'in_game' via the triggered startMatch", STORE[key("rooms", introRoomId)].status === "in_game");
  check("integration: setReady()'s OWN resolved room.matchStart reports started:true with the real matchId",
    roomAfterSecondReady.matchStart.allReady === true &&
    roomAfterSecondReady.matchStart.started === true &&
    roomAfterSecondReady.matchStart.matchId === STORE[key("rooms", introRoomId)].matchId &&
    roomAfterSecondReady.matchStart.error === null);
  var triggeredMatchId = STORE[key("rooms", introRoomId)].matchId;
  check("integration: the triggered match document actually exists", !!STORE[key("matches", triggeredMatchId)]);
  check("integration: the triggered match's players match the room's players",
    JSON.stringify(STORE[key("matches", triggeredMatchId)].players) === JSON.stringify(["i1", "i2"]));
  check("integration: the triggering client's own profile was self-synced via SessionService.setCurrentMatchId",
    setCurrentMatchIdCalls.indexOf(triggeredMatchId) !== -1);

  // Two players in a DIFFERENT room both call setReady concurrently — the
  // last of the two Promise.all calls to resolve is the one whose
  // maybeStartMatch fires; both fire (both see "all ready" after their own
  // transaction), but startMatch's OWN atomicity must still collapse them
  // into exactly one match, proving the guarantee holds through the real
  // trigger path, not just when MatchService is called directly.
  var raceRoomId2 = await RoomService.createRoom("j1", "Integration Race Room");
  await RoomService.joinRoom(raceRoomId2, "j2");
  var raceResults2 = await Promise.all([
    RoomService.setReady(raceRoomId2, "j1", true),
    RoomService.setReady(raceRoomId2, "j2", true)
  ]);
  var raceMatchDocs2 = Object.keys(STORE).filter(function (k) {
    return k.indexOf("matches/") === 0 && STORE[k].roomId === raceRoomId2;
  });
  check("integration: two players calling setReady concurrently (real trigger path) still produce exactly ONE match",
    raceMatchDocs2.length === 1);
  check("integration: the room ends up with a consistent matchId matching the one match document",
    STORE[key("rooms", raceRoomId2)].matchId === raceMatchDocs2[0].split("/")[1]);
  // Exactly one of the two concurrent setReady calls is the one whose OWN
  // transaction observes "everyone is now ready" (transactions on the
  // same room document serialize via retry — see the mock's optimistic-
  // concurrency design); the OTHER call's own matchStart legitimately
  // reports allReady:false with no matchId, since IT never saw both
  // players ready. What must hold for EITHER result: if a matchId is
  // present at all, it's the correct, single, real one — never a
  // fabricated or mismatched id.
  check("integration: any concurrent setReady call that reports a matchId reports the correct, single one (never a mismatch)",
    [raceResults2[0], raceResults2[1]].every(function (r) {
      return !r.matchStart.matchId || r.matchStart.matchId === raceMatchDocs2[0].split("/")[1];
    }));
  check("integration: exactly one of the two concurrent setReady calls actually started the match",
    [raceResults2[0], raceResults2[1]].filter(function (r) { return r.matchStart.started; }).length === 1);

  // A room where MatchService IS available but readyPlayers never reaches
  // "everyone" (a third, not-yet-joined seat) must NOT trigger a match.
  var partialRoomId = await RoomService.createRoom("k1", "Partial Ready Room");
  await RoomService.joinRoom(partialRoomId, "k2");
  await RoomService.joinRoom(partialRoomId, "k3");
  await RoomService.setReady(partialRoomId, "k1", true);
  var roomAfterPartial = await RoomService.setReady(partialRoomId, "k2", true);
  check("integration: partial-ready room (not everyone ready) never triggers startMatch",
    !STORE[key("rooms", partialRoomId)].matchId && STORE[key("rooms", partialRoomId)].status === "waiting");
  check("integration: partial-ready room's matchStart correctly reports allReady:false", roomAfterPartial.matchStart.allReady === false);

  // ============ Task 4: match-start failure is observable, not silently
  // stuck (MatchService.startMatch itself fails, e.g. a real rejection) ============
  var failRoomId = await RoomService.createRoom("m1", "Failure Observability Room");
  await RoomService.joinRoom(failRoomId, "m2");
  await RoomService.setReady(failRoomId, "m1", true);
  // Force the underlying startMatch() call to fail on the NEXT attempt
  // only, simulating a transient error (not a permanent Firestore outage
  // — that's already covered by the unrelated "Firestore unavailable"
  // tests above).
  var realStartMatch = MatchService.startMatch;
  MatchService.startMatch = function () { return Promise.reject(new Error("simulated transient failure")); };
  var roomAfterFailedStart = await RoomService.setReady(failRoomId, "m2", true);
  MatchService.startMatch = realStartMatch;
  check("Task 4: a failed match-start attempt is OBSERVABLE via room.matchStart.error, not silently swallowed",
    roomAfterFailedStart.matchStart.allReady === true &&
    roomAfterFailedStart.matchStart.started === false &&
    !!roomAfterFailedStart.matchStart.error &&
    /simulated transient failure/.test(roomAfterFailedStart.matchStart.error.message));
  check("Task 4: setReady() itself still resolves (never rejects) even when the match-start attempt failed",
    !!roomAfterFailedStart); // already true if we reached here without a thrown/rejected error
  check("Task 4: a failed attempt leaves the room genuinely retryable — status still 'waiting', no matchId",
    STORE[key("rooms", failRoomId)].status === "waiting" && !STORE[key("rooms", failRoomId)].matchId);
  // Retrying via the same idempotent setReady(true) call (matching
  // Lobby's own documented poll/retry behavior) succeeds once the
  // transient failure is gone.
  var roomAfterRetry = await RoomService.setReady(failRoomId, "m2", true);
  check("Task 4: retrying via the same idempotent setReady(true) call succeeds once the failure clears",
    roomAfterRetry.matchStart.started === true && !!roomAfterRetry.matchStart.matchId);

  // ============ Final, whole-run regression guard ============
  check("Requirement #2 (whole run): across EVERY startMatch/setReady call in this entire test file, " +
    "PlayerService.updatePlayerProfile was never once called with currentMatchId",
    noProfileCallTouchedCurrentMatchId());

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
