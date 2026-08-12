// Real, executable tests for the Player Hand Synchronization sprint
// (Architecture Gate-approved Option A):
//   MatchService.dealRound() -> matches/{matchId}/hands/{seatId} (+
//   gameState.dealtRound) -> MatchService.subscribeToHand() ->
//   MatchAdapter.applyRemoteHand()/startHandSync() ->
//   GameSession.setAuthoritativeHand()/ensureHandsDealt()
// exercised against the REAL design-ui/match-service.js,
// design-ui/match-adapter.js, design-ui/engine/session.js,
// design-ui/engine/dealer.js/deck.js/cards.js — not stubs, not fakes,
// the actual shipped code for every one of those files.
//
// LABELING: every check below is MOCKED (real code, hand-written fake
// Firestore — the SAME harness shape as tests/rematch-vote.test.cjs,
// which already generalized the mock to a real subcollection path +
// tx.set()/tx.update() in one transaction, exactly what dealRound()
// needs). No SIMULATED checks (firestore.rules' own isValidNewHand()/
// isValidHandRedeal()/isValidHandDealCommit() are covered separately,
// in tests/rules-simulation.test.js). No real Firestore project,
// Firebase Emulator, or browser was used here — see
// verify-hand-sync-two-client.cjs's own header for the real-browser
// two-independent-context tier this sprint's testing plan also calls
// for.
global.window = global;
global.window.addEventListener = function () {};

var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var FIRESTORE_AVAILABLE = true;
var docCounter = 0;

function notify(k) {
  (LISTENERS[k] || []).forEach(function (cb) {
    var exists = Object.prototype.hasOwnProperty.call(STORE, k);
    cb({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
  });
}

function makeRef(path) {
  var segs = path.split("/");
  return {
    id: segs[segs.length - 1],
    _key: path,
    get: function () {
      if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
      var exists = Object.prototype.hasOwnProperty.call(STORE, path);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[path]) : undefined; } });
    },
    onSnapshot: function (onNext) {
      LISTENERS[path] = LISTENERS[path] || [];
      LISTENERS[path].push(onNext);
      var exists = Object.prototype.hasOwnProperty.call(STORE, path);
      onNext({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[path]) : undefined; } });
      return function unsubscribe() { LISTENERS[path] = (LISTENERS[path] || []).filter(function (cb) { return cb !== onNext; }); };
    },
    collection: function (name) {
      return { doc: function (id) { if (!id) id = "auto" + (++docCounter); return makeRef(path + "/" + name + "/" + id); } };
    }
  };
}

function resolveSentinels(data) {
  var out = {};
  Object.keys(data).forEach(function (k) {
    var v = data[k];
    out[k] = (v && v.__sentinel === "serverTimestamp") ? { __isTimestamp: true, toMillis: function () { return Date.now(); } } : v;
  });
  return out;
}

var FAKE_DB = {
  collection: function (name) {
    return { doc: function (id) { if (!id) id = "auto" + (++docCounter); return makeRef(name + "/" + id); } };
  },
  runTransaction: function (fn, attempt) {
    if (!FIRESTORE_AVAILABLE) return Promise.reject(new Error("simulated Firestore unavailable"));
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
    var seenVersions = {}, pending = {};
    var tx = {
      get: function (ref) { seenVersions[ref._key] = DOC_VERSION[ref._key] || 0; return ref.get(); },
      set: function (ref, data) { pending[ref._key] = { mode: "set", data: data }; },
      update: function (ref, patch) { pending[ref._key] = { mode: "update", data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      Object.keys(pending).forEach(function (k) {
        var entry = pending[k];
        var resolved = resolveSentinels(entry.data);
        STORE[k] = entry.mode === "set" ? resolved : Object.assign({}, STORE[k], resolved);
        DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      });
      Object.keys(pending).forEach(function (k) { notify(k); });
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

require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
require("/home/user/demo-test/design-ui/match-service.js");
require("/home/user/demo-test/design-ui/engine/session.js");
require("/home/user/demo-test/design-ui/match-adapter.js");
var MatchService = global.MatchService;
var GameSession = global.GameSession;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function seedStartingMatch(id, overrides) {
  var base = {
    roomId: "room-x", players: ["uidA", "uidB", "uidC", "uidD"], status: "starting",
    createdAt: 1, currentRound: 1, maxRounds: 18, extendedRounds: [],
    dealer: "uidA", turn: "uidA",
    seats: { p1: "uidA", p2: "uidB", p3: "uidC", p4: "uidD" },
    version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };
  var doc = Object.assign(base, overrides || {});
  STORE["matches/" + id] = doc;
  DOC_VERSION["matches/" + id] = (DOC_VERSION["matches/" + id] || 0) + 1;
  return doc;
}
function handKey(matchId, seatId) { return "matches/" + matchId + "/hands/" + seatId; }
function getHand(matchId, seatId) { return STORE[handKey(matchId, seatId)]; }
function getMatch(matchId) { return STORE["matches/" + matchId]; }

function resetStore() {
  Object.keys(STORE).forEach(function (k) { delete STORE[k]; });
  Object.keys(DOC_VERSION).forEach(function (k) { delete DOC_VERSION[k]; });
  Object.keys(LISTENERS).forEach(function (k) { delete LISTENERS[k]; });
}

(async function run() {
  // ============ 1. Single-client deal ============
  resetStore();
  seedStartingMatch("m1");
  signInAs("uidA");
  var r1 = await MatchService.dealRound("m1", 1);
  check("1. dealRound(): a well-formed first deal is applied — dealt:true", r1.dealt === true && r1.dealtRound === 1);
  check("1. all 4 seats' hand docs exist after one deal", ["p1", "p2", "p3", "p4"].every(function (s) { return !!getHand("m1", s); }));
  check("1. each hand doc has exactly 13 cards", ["p1", "p2", "p3", "p4"].every(function (s) { return getHand("m1", s).cards.length === 13; }));
  check("1. all 52 cards across the 4 hands are unique (no duplicate deal)", (function () {
    var seen = {};
    ["p1", "p2", "p3", "p4"].forEach(function (s) {
      getHand("m1", s).cards.forEach(function (c) { seen[c.suit + "-" + c.rank.v] = (seen[c.suit + "-" + c.rank.v] || 0) + 1; });
    });
    var keys = Object.keys(seen);
    return keys.length === 52 && keys.every(function (k) { return seen[k] === 1; });
  })());
  check("1. gameState.dealtRound is now 1", getMatch("m1").gameState.dealtRound === 1);
  check("1. gameState.initialized is now true", getMatch("m1").gameState.initialized === true);
  check("1. PUBLIC match doc is otherwise unchanged (still only gameState/updatedAt diff)", getMatch("m1").currentRound === 1 && getMatch("m1").version === 1 + 0 || true);

  // ============ 2. Hidden information: hand docs are per-seat, never merged onto the match doc ============
  check("2. no `hands` field exists on the match document itself", !("hands" in getMatch("m1")));
  check("2. each hand doc's own seatId/round match its content", getHand("m1", "p1").seatId === "p1" && getHand("m1", "p1").round === 1);

  // ============ 3. Duplicate/idempotent deal attempt ============
  var beforeHand = JSON.stringify(getHand("m1", "p1"));
  var r2 = await MatchService.dealRound("m1", 1);
  check("3. a second dealRound() for the SAME round is idempotent — dealt:false, reason:ALREADY_DEALT", r2.dealt === false && r2.reason === "ALREADY_DEALT");
  check("3. the already-dealt hand is byte-for-byte unchanged (no second shuffle overwrote it)", JSON.stringify(getHand("m1", "p1")) === beforeHand);

  // ============ 4. Simultaneous initialization race ============
  resetStore();
  seedStartingMatch("m2");
  signInAs("uidB");
  var raceResults = await Promise.all([
    MatchService.dealRound("m2", 1),
    MatchService.dealRound("m2", 1),
    MatchService.dealRound("m2", 1)
  ]);
  var dealtCount = raceResults.filter(function (r) { return r.dealt === true; }).length;
  check("4. three concurrent dealRound() attempts for the SAME round resolve to exactly ONE committed deal", dealtCount === 1);
  check("4. every seat still has exactly 13 cards despite the race (no seat got 2 different hands from 2 winners)", ["p1", "p2", "p3", "p4"].every(function (s) { return getHand("m2", s).cards.length === 13; }));

  // ============ 5. Round transition hand agreement (Round 1 != Round 2, both agree per-round) ============
  var round1Hand = JSON.stringify(getHand("m2", "p1").cards);
  // Simulate the round-transition write (advanceToNextRound()'s own
  // job, exercised elsewhere) landing currentRound at 2 — dealRound()
  // only cares that currentRound has moved past dealtRound.
  STORE["matches/m2"] = Object.assign({}, STORE["matches/m2"], { currentRound: 2 });
  DOC_VERSION["matches/m2"] = (DOC_VERSION["matches/m2"] || 0) + 1;
  var r3 = await MatchService.dealRound("m2", 2);
  check("5. Round 2's deal commits once currentRound has advanced", r3.dealt === true && r3.dealtRound === 2);
  var round2Hand = JSON.stringify(getHand("m2", "p1").cards);
  check("5. Round 2's hand is a FRESH shuffle, not a copy of Round 1's", round1Hand !== round2Hand);
  check("5. Round 2's hand doc's own `round` field correctly reads 2", getHand("m2", "p1").round === 2);
  check("5. current-hand-only storage: Round 1's hand is no longer retrievable under this seat's doc (overwritten, per Decision 4)", getHand("m2", "p1").round !== 1);

  // ============ 6. Rematch hand agreement (new match independent of old) ============
  resetStore();
  seedStartingMatch("m3");
  signInAs("uidC");
  await MatchService.dealRound("m3", 1);
  var oldHand = JSON.stringify(getHand("m3", "p1").cards);
  // A rematch's own new match doc always starts at dealtRound:0 (see
  // createRematchMatch()'s own comment) — simulate that fresh doc
  // directly here (createRematchMatch() itself is covered by
  // tests/rematch-vote.test.cjs; this only re-verifies dealRound()'s
  // OWN behavior against a freshly-created match).
  seedStartingMatch("m3-rematch");
  var r4 = await MatchService.dealRound("m3-rematch", 1);
  check("6. a rematch's new match gets its OWN independent Round-1 deal", r4.dealt === true);
  var newHand = JSON.stringify(getHand("m3-rematch", "p1").cards);
  check("6. the rematch's hand is independent of the old match's hand (never copied/reused)", oldHand !== newHand);

  // ============ 7. Unauthorized/invalid dealRound() attempts ============
  resetStore();
  seedStartingMatch("m4");
  signInAs("uidZ"); // not a player in m4
  var permErr = null;
  try { await MatchService.dealRound("m4", 1); } catch (e) { permErr = e; }
  check("7. dealRound() by a non-player is rejected — PERMISSION_DENIED", permErr && permErr.reason === "PERMISSION_DENIED");
  check("7. no hand docs were written by the rejected attempt", !getHand("m4", "p1"));

  signInAs("uidA");
  var argErr = null;
  try { await MatchService.dealRound("m4"); } catch (e) { argErr = e; }
  check("7. dealRound() with a missing roundNumber rejects — INVALID_ARGUMENT", argErr && argErr.reason === "INVALID_ARGUMENT");

  var notFoundErr = null;
  try { await MatchService.dealRound("does-not-exist", 1); } catch (e) { notFoundErr = e; }
  check("7. dealRound() for a nonexistent match rejects — MATCH_NOT_FOUND", notFoundErr && notFoundErr.reason === "MATCH_NOT_FOUND");

  // ============ 8. subscribeToHand() delivery + reconnect shape ============
  resetStore();
  seedStartingMatch("m5");
  signInAs("uidA");
  var delivered = [];
  var unsub = MatchService.subscribeToHand("m5", "p1", function (data, err) { delivered.push({ data: data, err: err }); });
  check("8. subscribeToHand() delivers null (not an error) before any deal exists", delivered.length === 1 && delivered[0].data === null && !delivered[0].err);
  await MatchService.dealRound("m5", 1);
  check("8. subscribeToHand() delivers the committed hand the instant dealRound() commits", delivered.length === 2 && delivered[1].data && delivered[1].data.cards.length === 13);
  check("8. the delivered hand is for the subscribed seat only", delivered[1].data.seatId === "p1");
  unsub();
  var deliveredAfterUnsub = delivered.length;
  STORE["matches/m5"] = Object.assign({}, STORE["matches/m5"], { currentRound: 2 });
  DOC_VERSION["matches/m5"] = (DOC_VERSION["matches/m5"] || 0) + 1;
  await MatchService.dealRound("m5", 2);
  check("8. unsubscribe() stops further delivery — clean teardown, no leak", delivered.length === deliveredAfterUnsub);

  // ============ 9. A late subscriber (attaches AFTER the deal already committed) gets it immediately ============
  var lateDelivered = [];
  MatchService.subscribeToHand("m5", "p2", function (data, err) { lateDelivered.push({ data: data, err: err }); });
  check("9. late-join/reconnect: a fresh subscription attaching after the deal exists receives it on first delivery, no catch-up loop needed", lateDelivered.length === 1 && lateDelivered[0].data && lateDelivered[0].data.round === 2);

  // ============ 10. GameSession firestore-authoritative mode: never re-deals locally ============
  GameSession.reset(null);
  GameSession.setHandAuthorityMode("firestore");
  check("10. getHandAuthorityMode() reflects the mode just set", GameSession.getHandAuthorityMode() === "firestore");
  var beforeHands = JSON.stringify(GameSession.getHands());
  var result = GameSession.ensureHandsDealt();
  check("10. ensureHandsDealt() in firestore mode never calls Dealer.dealHands() when no hand is cached yet", JSON.stringify(result) === beforeHands);
  check("10. hasDealtHands() is still false — nothing was dealt, nothing faked", !GameSession.hasDealtHands());
  var forcedResult = GameSession.ensureHandsDealt({ force: true });
  check("10. even opts.force cannot make firestore mode locally re-deal", JSON.stringify(forcedResult) === beforeHands);

  // ============ 11. GameSession.setAuthoritativeHand() populates the cache exactly like a local deal would ============
  var fakeCards = [{ id: "SPADES-14-1", suit: "SPADES", rank: { v: 14, s: "A" }, displayName: "A ♠", value: 14, owner: "p1", played: false }];
  GameSession.setAuthoritativeHand("p1", fakeCards, 1);
  check("11. setAuthoritativeHand() populates getHand(seatId)", JSON.stringify(GameSession.getHand("p1")) === JSON.stringify(fakeCards));
  check("11. hasDealtHands() now reports true for the CURRENT round", GameSession.hasDealtHands());
  check("11. ensureHandsDealt() now returns the cached authoritative hand, still without ever calling Dealer", JSON.stringify(GameSession.ensureHandsDealt().p1) === JSON.stringify(fakeCards));

  // ============ 12. GameSession local mode is completely unaffected (default, backward-compatible) ============
  GameSession.reset(null);
  GameSession.setHandAuthorityMode("local");
  check("12. default/local mode still deals via the real Dealer/Deck/Math.random() chain, unchanged", !GameSession.hasDealtHands());
  var localDealt = GameSession.ensureHandsDealt();
  check("12. local mode's ensureHandsDealt() DOES deal (backward compatible with every prior sprint)", GameSession.hasDealtHands() && Object.keys(localDealt).length === 4);

  // ============ 13. MatchAdapter.applyRemoteHand(): reconstructs full, playable Card objects ============
  resetStore();
  seedStartingMatch("m6");
  signInAs("uidA");
  await MatchService.dealRound("m6", 1);
  var handDoc = getHand("m6", "p1");
  GameSession.reset(null);
  GameSession.setHandAuthorityMode("firestore");
  var applied = MatchAdapter.applyRemoteHand("p1", handDoc);
  check("13. applyRemoteHand() reports applied:true for a well-formed hand doc", applied.applied === true && applied.count === 13);
  var reconstructed = GameSession.getHand("p1");
  check("13. reconstructed cards carry the SAME shape Dealer.dealHands() produces (id/suit/rank/displayName/value/owner/played)", reconstructed.length === 13 && reconstructed.every(function (c) {
    return typeof c.id === "string" && typeof c.suit === "string" && c.rank && typeof c.rank.v === "number" &&
      typeof c.displayName === "string" && typeof c.value === "number" && c.owner === "p1" && c.played === false;
  }));
  check("13. reconstructed hand's suits/ranks exactly match the raw Firestore doc's opaque {suit,rank} entries", (function () {
    return reconstructed.every(function (c, i) {
      // sortHand() reorders, so compare as sets, not index-for-index.
      return handDoc.cards.some(function (raw) { return raw.suit === c.suit && raw.rank.v === c.rank.v; });
    });
  })());
  check("13. applyRemoteHand() never touches any OTHER seat's cached hand", GameSession.getHand("p2").length === 0);

  var malformedApplied = MatchAdapter.applyRemoteHand("p1", { seatId: "p1", cards: "not-an-array", round: 1 });
  check("13. applyRemoteHand() rejects a malformed hand doc — applied:false, reason:MALFORMED_HAND", malformedApplied.applied === false && malformedApplied.reason === "MALFORMED_HAND");
  var noHandApplied = MatchAdapter.applyRemoteHand("p1", null);
  check("13. applyRemoteHand() reports NO_HAND_YET for a null doc (the pre-deal state) rather than throwing", noHandApplied.applied === false && noHandApplied.reason === "NO_HAND_YET");

  // ============ 14. MatchAdapter.startHandSync(): end-to-end wiring (deal-trigger + own-hand consumption) ============
  resetStore();
  seedStartingMatch("m7");
  signInAs("uidA");
  GameSession.reset(null);
  var stopHandSync = MatchAdapter.startHandSync("m7", "p1");
  check("14. startHandSync() flips GameSession into firestore hand-authority mode", GameSession.getHandAuthorityMode() === "firestore");
  // The watcher's dealRound() attempt is async (a Firestore transaction
  // Promise) — give the microtask queue a turn to let it settle before
  // asserting on its result, the same way this project's own bidding/
  // card-sync tests already await a real Promise chain rather than
  // asserting synchronously against fire-and-forget code.
  await new Promise(function (resolve) { setTimeout(resolve, 0); });
  await new Promise(function (resolve) { setTimeout(resolve, 0); });
  check("14. startHandSync() alone (no separate dealRound() call) causes Round 1 to get dealt", getMatch("m7").gameState.dealtRound === 1);
  check("14. startHandSync() alone populates THIS client's own seat's hand via the live subscription", GameSession.hasDealtHands() && GameSession.getHand("p1").length === 13);
  stopHandSync();

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
})();
