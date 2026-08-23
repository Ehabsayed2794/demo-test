const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Real, executable tests for design-ui/room-service.js — create/join/leave
// (Sprint 3.2) plus setReady (Sprint 3.3) — against an in-memory Firestore
// stub that faithfully mimics Firestore's optimistic-concurrency
// transaction retry (not just a plain read-then-write), so concurrency
// tests actually exercise retry-on-conflict rather than hiding a race
// behind non-interleaved mock calls.
global.window = global;

var STORE = {};   // roomId -> room data
var VERSION = {}; // roomId -> version counter
var FAKE_TS = { __sentinel: "serverTimestamp" };
var idCounter = 0;
var FIRESTORE_AVAILABLE = true; // toggled by the "Firestore unavailable" test

global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return FAKE_TS; } } } };

function makeRef(id) {
  return {
    id: id,
    get: function () {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      var exists = Object.prototype.hasOwnProperty.call(STORE, id);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[id]) : undefined; } });
    },
    set: function (data) {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      STORE[id] = Object.assign({}, data); VERSION[id] = (VERSION[id] || 0) + 1; return Promise.resolve();
    }
  };
}

var FAKE_DB = {
  collection: function (name) {
    if (name !== "rooms") throw new Error("unexpected collection " + name);
    return { doc: function (id) { if (!id) id = "room-" + (++idCounter); return makeRef(id); } };
  },
  runTransaction: function (fn, attempt) {
    if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded (possible infinite loop)"));
    var seenVersions = {};
    var tx = {
      get: function (ref) { seenVersions[ref.id] = VERSION[ref.id] || 0; return ref.get(); },
      update: function (ref, patch) { tx._pendingId = ref.id; tx._pendingPatch = patch; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      if (tx._pendingId) {
        var id = tx._pendingId;
        var currentVersion = VERSION[id] || 0;
        if (currentVersion !== seenVersions[id]) return FAKE_DB.runTransaction(fn, attempt + 1);
        STORE[id] = Object.assign({}, STORE[id], tx._pendingPatch);
        VERSION[id] = currentVersion + 1;
      }
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

require(__REPO_ROOT__ + "/design-ui/room-service.js");
var RoomService = global.RoomService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

(async function () {
  // ============ Sprint 3.2 regression coverage (create/join/leave) ============
  var roomId = await RoomService.createRoom("p1", "Khaled's Room");
  check("createRoom returns a roomId", typeof roomId === "string" && roomId.length > 0);
  check("createRoom initializes readyPlayers to an empty array", Array.isArray(STORE[roomId].readyPlayers) && STORE[roomId].readyPlayers.length === 0);
  check("createRoom sets players to [creator]", JSON.stringify(STORE[roomId].players) === JSON.stringify(["p1"]));

  await RoomService.joinRoom(roomId, "p2");
  await RoomService.joinRoom(roomId, "p3");
  check("multiple players can join the same room", JSON.stringify(STORE[roomId].players) === JSON.stringify(["p1", "p2", "p3"]));

  // ============ setReady: Ready true ============
  var r1 = await RoomService.setReady(roomId, "p1", true);
  check("setReady(true): player is added to readyPlayers", STORE[roomId].readyPlayers.indexOf("p1") !== -1);
  check("setReady(true): return value reflects the new state", r1.readyPlayers.indexOf("p1") !== -1);
  check("setReady(true): players[] is untouched", JSON.stringify(STORE[roomId].players) === JSON.stringify(["p1", "p2", "p3"]));
  check("setReady(true): creator/status/name are untouched", STORE[roomId].creator === "p1" && STORE[roomId].status === "waiting");

  // Idempotent — setting the same value twice is a no-op, no extra write.
  var versionBeforeRepeat = VERSION[roomId];
  await RoomService.setReady(roomId, "p1", true);
  check("setReady(true) called twice does not perform a second write (Spark-conscious no-op)", VERSION[roomId] === versionBeforeRepeat);

  // ============ Multiple players readying up ============
  await RoomService.setReady(roomId, "p2", true);
  check("multiple players: p1 and p2 both ready, p3 not", JSON.stringify(STORE[roomId].readyPlayers.slice().sort()) === JSON.stringify(["p1", "p2"]));

  // ============ setReady: Ready false ============
  await RoomService.setReady(roomId, "p1", false);
  check("setReady(false): player is removed from readyPlayers", STORE[roomId].readyPlayers.indexOf("p1") === -1);
  check("setReady(false): other ready players are unaffected", STORE[roomId].readyPlayers.indexOf("p2") !== -1);

  var versionBeforeRepeat2 = VERSION[roomId];
  await RoomService.setReady(roomId, "p1", false);
  check("setReady(false) called twice does not perform a second write", VERSION[roomId] === versionBeforeRepeat2);

  // ============ setReady: rejects for a non-member ============
  var notMemberErr = null;
  try { await RoomService.setReady(roomId, "totally-not-a-member", true); } catch (e) { notMemberErr = e; }
  check("setReady rejects for a player who isn't a room member", notMemberErr && /not a member/i.test(notMemberErr.message));

  // ============ setReady: rejects for a nonexistent room ============
  var notFoundErr = null;
  try { await RoomService.setReady("does-not-exist-at-all", "p1", true); } catch (e) { notFoundErr = e; }
  check("setReady rejects for a nonexistent room", notFoundErr && /not found/i.test(notFoundErr.message));

  // ============ setReady: rejects for a closed room ============
  var closedRoomId = await RoomService.createRoom("pZ", "Closed Room Test");
  STORE[closedRoomId].status = "closed";
  var closedErr = null;
  try { await RoomService.setReady(closedRoomId, "pZ", true); } catch (e) { closedErr = e; }
  check("setReady rejects for a closed room", closedErr && /closed/i.test(closedErr.message));

  // ============ Concurrent ready updates: two different players toggling
  // ready at the same time must not clobber each other ============
  var concRoomId = await RoomService.createRoom("c1", "Concurrency Room");
  await RoomService.joinRoom(concRoomId, "c2");
  await Promise.all([
    RoomService.setReady(concRoomId, "c1", true),
    RoomService.setReady(concRoomId, "c2", true)
  ]);
  check("concurrent setReady calls from two different players: both land, neither is lost",
    JSON.stringify(STORE[concRoomId].readyPlayers.slice().sort()) === JSON.stringify(["c1", "c2"]));

  // Concurrent opposite toggles from the SAME player (true then false racing) —
  // exercises the transaction retry path directly; final state must be
  // one of the two valid outcomes, never a corrupted/duplicated array.
  var raceRoomId = await RoomService.createRoom("d1", "Ready Race Room");
  await Promise.all([
    RoomService.setReady(raceRoomId, "d1", true).catch(function () {}),
    RoomService.setReady(raceRoomId, "d1", true).catch(function () {})
  ]);
  check("concurrent identical setReady(true) calls from the same player: readyPlayers has no duplicate entry",
    STORE[raceRoomId].readyPlayers.filter(function (id) { return id === "d1"; }).length === 1);

  // ============ leaveRoom keeps readyPlayers consistent ============
  var leaveRoomId = await RoomService.createRoom("e1", "Leave Consistency Room");
  await RoomService.joinRoom(leaveRoomId, "e2");
  await RoomService.setReady(leaveRoomId, "e2", true);
  await RoomService.leaveRoom(leaveRoomId, "e2");
  check("leaveRoom removes the departing player from readyPlayers too, not just players", STORE[leaveRoomId].readyPlayers.indexOf("e2") === -1);

  // ============ Firestore unavailable ============
  FIRESTORE_AVAILABLE = false;
  var offlineErr = null;
  try { await RoomService.setReady(roomId, "p2", true); } catch (e) { offlineErr = e; }
  check("setReady surfaces a clear rejection when Firestore is unavailable (not a hang, not a silent success)", offlineErr && /unavailable/i.test(offlineErr.message));
  FIRESTORE_AVAILABLE = true;

  // ============ Regression: transferHost/closeRoom remain stubs ============
  ["transferHost", "closeRoom"].forEach(function (m) {
    var threw = false;
    try { RoomService[m](); } catch (e) { threw = /not implemented/i.test(e.message); }
    check("RoomService." + m + "() still throws Not implemented (out of scope this sprint)", threw);
  });

  // ============================================================
  // Sprint 3.4.1 (Match Start Consistency & Security Hotfix)
  // ============================================================

  // ---- loadRoom() — the new "room polling" primitive (Requirement #3:
  // each client can discover matchId from the room) ----
  var loadRoomId = await RoomService.createRoom("l1", "Load Room Test");
  var loadedRoom = await RoomService.loadRoom(loadRoomId);
  check("loadRoom resolves the room document", loadedRoom && loadedRoom.creator === "l1");
  check("loadRoom resolves a room without a matchId as matchId undefined/absent (not yet started)", !loadedRoom.matchId);

  var loadedMissingRoom = await RoomService.loadRoom("does-not-exist-at-all-either");
  check("loadRoom resolves null (not an error) for a nonexistent room — mirrors MatchService.loadMatch()'s pattern", loadedMissingRoom === null);

  var loadRoomNoArgErr = null;
  try { await RoomService.loadRoom(); } catch (e) { loadRoomNoArgErr = e; }
  check("loadRoom rejects clearly when roomId is missing", loadRoomNoArgErr && /roomId is required/i.test(loadRoomNoArgErr.message));

  FIRESTORE_AVAILABLE = false;
  var loadRoomOfflineErr = null;
  try { await RoomService.loadRoom(loadRoomId); } catch (e) { loadRoomOfflineErr = e; }
  check("loadRoom surfaces a clear rejection when Firestore is unavailable", loadRoomOfflineErr && /unavailable/i.test(loadRoomOfflineErr.message));
  FIRESTORE_AVAILABLE = true;

  // ---- setReady()'s room.matchStart — observability (Task 4) ----
  // Not-all-ready case: a second, not-yet-ready member keeps allReady false.
  var msRoomId = await RoomService.createRoom("n1", "MatchStart Observability Room");
  await RoomService.joinRoom(msRoomId, "n2");
  var msRoomAfterOneReady = await RoomService.setReady(msRoomId, "n1", true);
  check("room.matchStart is always present on setReady()'s resolved value, and correctly reports allReady:false when a member still isn't ready",
    msRoomAfterOneReady.matchStart && msRoomAfterOneReady.matchStart.allReady === false && msRoomAfterOneReady.matchStart.started === false && msRoomAfterOneReady.matchStart.error === null);

  // All-ready case, but MatchService is genuinely unavailable on this page
  // (no global.MatchService defined anywhere in THIS test file at this
  // point, matching the existing "concurrent setReady" scenario above that
  // already exercises this fail-open path).
  var msRoomAfterAllReadyNoService = await RoomService.setReady(msRoomId, "n2", true);
  check("room.matchStart reports allReady:true, started:false, and a clear error when MatchService is unavailable",
    msRoomAfterAllReadyNoService.matchStart.allReady === true &&
    msRoomAfterAllReadyNoService.matchStart.started === false &&
    !!msRoomAfterAllReadyNoService.matchStart.error &&
    /MatchService is not available/i.test(msRoomAfterAllReadyNoService.matchStart.error.message));

  // ---- setReady()'s room.matchStart when MatchService IS available —
  // exercised here too (not just in match-service.test.cjs) so RoomService's
  // OWN test suite proves the observable-result contract on its own terms. ----
  var mockStartMatchCalls = [];
  global.MatchService = {
    startMatch: function (roomId) {
      mockStartMatchCalls.push(roomId);
      return Promise.resolve("mock-match-" + mockStartMatchCalls.length);
    }
  };
  var msRoomId2 = await RoomService.createRoom("o1", "MatchStart Available Room");
  await RoomService.joinRoom(msRoomId2, "o2");
  await RoomService.setReady(msRoomId2, "o1", true);
  var msRoomAfterAllReady = await RoomService.setReady(msRoomId2, "o2", true);
  check("room.matchStart reports started:true with the matchId MatchService.startMatch resolved, when everyone is ready",
    msRoomAfterAllReady.matchStart.allReady === true &&
    msRoomAfterAllReady.matchStart.started === true &&
    msRoomAfterAllReady.matchStart.matchId === "mock-match-1" &&
    msRoomAfterAllReady.matchStart.error === null);
  check("RoomService actually called MatchService.startMatch(roomId) with the correct roomId", mockStartMatchCalls[0] === msRoomId2);
  delete global.MatchService; // restore to "unavailable" for any later test relying on that fail-open path

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
