// Real, executable tests for design-ui/match-service.js (Sprint 3.4) —
// createMatch / startMatch / loadMatch / subscribeToMatch — plus a
// cross-service integration section proving RoomService.setReady really
// does trigger MatchService.startMatch end-to-end through the SAME fake
// Firestore instance, not just MatchService in isolation.
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

var updateProfileCalls = [];
var refreshCalls = 0;
global.PlayerService = {
  updatePlayerProfile: function (uid, patch) { updateProfileCalls.push({ uid: uid, patch: patch }); return Promise.resolve(); }
};
global.SessionService = {
  refresh: function () { refreshCalls++; return Promise.resolve(); }
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
  // ============ createMatch ============
  makeRoom("room-create-1", { players: ["p1", "p2", "p3"] });
  var matchId1 = await MatchService.createMatch("room-create-1");
  check("createMatch resolves a matchId", typeof matchId1 === "string" && matchId1.length > 0);
  var doc1 = STORE[key("matches", matchId1)];
  check("createMatch: roomId set correctly", doc1.roomId === "room-create-1");
  check("createMatch: players copied from room", JSON.stringify(doc1.players) === JSON.stringify(["p1", "p2", "p3"]));
  check("createMatch: status is 'starting'", doc1.status === "starting");
  check("createMatch: currentRound is 1", doc1.currentRound === 1);
  check("createMatch: dealer defaults to room.creator", doc1.dealer === "p1");
  check("createMatch: turn defaults to dealer", doc1.turn === "p1");
  check("createMatch: gameState is the documented TODO placeholder, not fabricated dealt hands",
    doc1.gameState && doc1.gameState.initialized === false && typeof doc1.gameState.todo === "string" && /Deck/.test(doc1.gameState.todo));
  check("createMatch: does NOT write the room document (that's startMatch's job)", STORE[key("rooms", "room-create-1")].matchId === undefined);

  var createNoRoomErr = null;
  try { await MatchService.createMatch("does-not-exist"); } catch (e) { createNoRoomErr = e; }
  check("createMatch rejects for a nonexistent room", createNoRoomErr && /not found/i.test(createNoRoomErr.message));

  // ============ startMatch: happy path ============
  makeRoom("room-start-1", { players: ["p1", "p2"], readyPlayers: ["p1", "p2"] });
  updateProfileCalls.length = 0; refreshCalls = 0;
  var matchId2 = await MatchService.startMatch("room-start-1");
  check("startMatch resolves a matchId", typeof matchId2 === "string" && matchId2.length > 0);
  check("startMatch: match document created", !!STORE[key("matches", matchId2)]);
  check("startMatch: room status becomes 'in_game'", STORE[key("rooms", "room-start-1")].status === "in_game");
  check("startMatch: room.matchId set to the new matchId", STORE[key("rooms", "room-start-1")].matchId === matchId2);
  check("startMatch: currentMatchId mirrored onto every player's profile",
    updateProfileCalls.some(function (c) { return c.uid === "p1" && c.patch.currentMatchId === matchId2; }) &&
    updateProfileCalls.some(function (c) { return c.uid === "p2" && c.patch.currentMatchId === matchId2; }));
  check("startMatch: SessionService.refresh() called after syncing profiles", refreshCalls >= 1);

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
  STORE[key("matches", matchId2)].status = "in_progress";
  VERSION[key("matches", matchId2)]++;
  notifyListeners(key("matches", matchId2));
  check("subscribeToMatch delivers a second snapshot on change", subEvents.length === 2 && subEvents[1].data.status === "in_progress");
  unsub();
  STORE[key("matches", matchId2)].status = "changed-after-unsub";
  notifyListeners(key("matches", matchId2));
  check("subscribeToMatch's unsubscribe function actually stops delivery", subEvents.length === 2);

  var subMissingEvents = [];
  MatchService.subscribeToMatch("no-such-match-id", function (data, err) { subMissingEvents.push({ data: data, err: err }); });
  check("subscribeToMatch delivers (null, null-ish exists:false) for a nonexistent match, not a thrown error", subMissingEvents.length === 1 && subMissingEvents[0].data === null && subMissingEvents[0].err === null);

  // ============ Firestore unavailable ============
  FIRESTORE_AVAILABLE = false;
  var offlineCreateErr = null;
  try { await MatchService.createMatch("room-start-1"); } catch (e) { offlineCreateErr = e; }
  check("createMatch surfaces a clear rejection when Firestore is unavailable", offlineCreateErr && /unavailable/i.test(offlineCreateErr.message));
  var offlineStartErr = null;
  try { await MatchService.startMatch("room-start-1"); } catch (e) { offlineStartErr = e; }
  check("startMatch surfaces a clear rejection when Firestore is unavailable", offlineStartErr && /unavailable/i.test(offlineStartErr.message));
  var offlineSubEvents = [];
  MatchService.subscribeToMatch(matchId2, function (data, err) { offlineSubEvents.push({ data: data, err: err }); });
  check("subscribeToMatch delivers (null, err) when Firestore is unavailable, rather than throwing", offlineSubEvents.length === 1 && offlineSubEvents[0].data === null && !!offlineSubEvents[0].err);
  FIRESTORE_AVAILABLE = true;

  // ============ Not-yet-implemented gameplay methods remain stubs ============
  ["submitDashCall", "submitBid", "submitPass", "declareTrump", "submitEstimate", "playCard", "resolveTrick", "completeRound", "advanceToNextRound", "endMatch"].forEach(function (m) {
    var threw = false;
    try { MatchService[m](); } catch (e) { threw = /not implemented/i.test(e.message); }
    check("MatchService." + m + "() still throws Not implemented (bidding/estimation/card-play out of scope this sprint)", threw);
  });

  // ============================================================
  // Cross-service integration: RoomService.setReady triggers
  // MatchService.startMatch automatically, end-to-end, through the
  // SAME fake Firestore instance — not MatchService called directly.
  // ============================================================

  // Two players join a room; the SECOND ready-up (not the first) is the
  // one that should cross the "everyone is ready" threshold and trigger
  // startMatch as a fire-and-forget follow-up inside setReady itself.
  var introRoomId = await RoomService.createRoom("i1", "Integration Room");
  await RoomService.joinRoom(introRoomId, "i2");
  await RoomService.setReady(introRoomId, "i1", true);
  check("integration: room not yet all-ready after only one player readies — no match yet", !STORE[key("rooms", introRoomId)].matchId);
  await RoomService.setReady(introRoomId, "i2", true);
  // maybeStartMatch's startMatch() call is fire-and-forget (a Promise not
  // awaited by setReady) — give its microtask/transaction chain a tick to
  // actually land before asserting on it.
  await new Promise(function (resolve) { setTimeout(resolve, 0); });
  check("integration: RoomService.setReady (last player ready) triggers MatchService.startMatch automatically",
    !!STORE[key("rooms", introRoomId)].matchId);
  check("integration: room status becomes 'in_game' via the triggered startMatch", STORE[key("rooms", introRoomId)].status === "in_game");
  var triggeredMatchId = STORE[key("rooms", introRoomId)].matchId;
  check("integration: the triggered match document actually exists", !!STORE[key("matches", triggeredMatchId)]);
  check("integration: the triggered match's players match the room's players",
    JSON.stringify(STORE[key("matches", triggeredMatchId)].players) === JSON.stringify(["i1", "i2"]));

  // Two players in a DIFFERENT room both call setReady concurrently — the
  // last of the two Promise.all calls to resolve is the one whose
  // maybeStartMatch fires; both fire (both see "all ready" after their own
  // transaction), but startMatch's OWN atomicity must still collapse them
  // into exactly one match, proving the guarantee holds through the real
  // trigger path, not just when MatchService is called directly.
  var raceRoomId2 = await RoomService.createRoom("j1", "Integration Race Room");
  await RoomService.joinRoom(raceRoomId2, "j2");
  await Promise.all([
    RoomService.setReady(raceRoomId2, "j1", true),
    RoomService.setReady(raceRoomId2, "j2", true)
  ]);
  await new Promise(function (resolve) { setTimeout(resolve, 0); });
  var raceMatchDocs2 = Object.keys(STORE).filter(function (k) {
    return k.indexOf("matches/") === 0 && STORE[k].roomId === raceRoomId2;
  });
  check("integration: two players calling setReady concurrently (real trigger path) still produce exactly ONE match",
    raceMatchDocs2.length === 1);
  check("integration: the room ends up with a consistent matchId matching the one match document",
    STORE[key("rooms", raceRoomId2)].matchId === raceMatchDocs2[0].split("/")[1]);

  // A room where MatchService IS available but readyPlayers never reaches
  // "everyone" (a third, not-yet-joined seat) must NOT trigger a match.
  var partialRoomId = await RoomService.createRoom("k1", "Partial Ready Room");
  await RoomService.joinRoom(partialRoomId, "k2");
  await RoomService.joinRoom(partialRoomId, "k3");
  await RoomService.setReady(partialRoomId, "k1", true);
  await RoomService.setReady(partialRoomId, "k2", true);
  await new Promise(function (resolve) { setTimeout(resolve, 0); });
  check("integration: partial-ready room (not everyone ready) never triggers startMatch",
    !STORE[key("rooms", partialRoomId)].matchId && STORE[key("rooms", partialRoomId)].status === "waiting");

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
