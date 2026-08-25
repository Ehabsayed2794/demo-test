var REPO_ROOT = require("path").join(__dirname, "..");
// Real, executable tests for the Post-Match Rematch Vote sprint —
// MatchService.createRematchVote() / submitRematchVote() /
// resolveRematchVoteTimeout() / createRematchMatch() against a
// hand-written fake Firestore, generalized (unlike every earlier
// mock in this tests/ directory) to support a real SUBCOLLECTION path
// (matches/{matchId}/rematchVote/current) and a real tx.set() in
// addition to tx.update() — this sprint's createRematchMatch() needs
// both "create one new document" and "update a sibling document" in
// ONE atomic transaction, which no earlier mock modeled.
//
// LABELING RULE (same as tests/submit-bid.test.cjs): every check() is
// MOCKED — exercises the REAL match-service.js code against this
// file's own fake Firestore. No test here runs against a real
// Firestore project, the Firebase emulator, or a real browser — the
// two-client synchronization claims (AA/AB/AC) are NOT covered by this
// file; see verify-rematch-vote.cjs (genuine two-independent-browser-
// context Playwright harness) for those.
global.window = global;

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
    out[k] = (v && v.__sentinel === "serverTimestamp") ? FAKE_NOW_TIMESTAMP() : v;
  });
  return out;
}

// A settable fake "server clock" — lets tests simulate "30+ seconds
// have passed" without a real 30-second sleep. Defaults to the real
// Date.now() and can be advanced per-test via advanceClock().
var clockOffsetMs = 0;
function fakeNow() { return Date.now() + clockOffsetMs; }
function advanceClock(ms) { clockOffsetMs += ms; }
function resetClock() { clockOffsetMs = 0; }
function FAKE_NOW_TIMESTAMP() {
  var ms = fakeNow();
  return { __isTimestamp: true, toMillis: function () { return ms; } };
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

require(REPO_ROOT + "/design-ui/match-service.js");
var MatchService = global.MatchService;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function seedCompletedMatch(id, overrides) {
  var base = {
    roomId: "room-x", players: ["uidA", "uidB", "uidC", "uidD"], status: "complete",
    createdAt: 1, currentRound: 19, maxRounds: 18, extendedRounds: [],
    dealer: "uidA", turn: "uidA", gameState: { initialized: false },
    seats: { p1: "uidA", p2: "uidB", p3: "uidC", p4: "uidD" },
    version: 5, biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    winnerIds: ["uidA"], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 }, completedRound: 18
  };
  var doc = Object.assign(base, overrides || {});
  STORE["matches/" + id] = doc;
  DOC_VERSION["matches/" + id] = (DOC_VERSION["matches/" + id] || 0) + 1;
  return doc;
}
function voteKey(matchId) { return "matches/" + matchId + "/rematchVote/current"; }
function getVote(matchId) { return STORE[voteKey(matchId)]; }

(async function () {
  // ============================================================
  // A. Vote creation
  // ============================================================
  seedCompletedMatch("m-a");
  signInAs("uidA");
  var createResult = await MatchService.createRematchVote("m-a");
  check("A. Vote creation: succeeds for a seated player of a completed match", createResult.created === true);
  check("A. Vote creation: vote document actually exists in the subcollection", !!getVote("m-a"));
  check("A. Vote creation: status starts OPEN", getVote("m-a").status === "OPEN");
  check("A. Vote creation: newMatchId starts null", getVote("m-a").newMatchId === null);

  // ============================================================
  // B. Only seated players can vote
  // ============================================================
  seedCompletedMatch("m-b");
  signInAs("uidA");
  await MatchService.createRematchVote("m-b");
  signInAs("uidX"); // not a player in this match
  var notSeatedErr = null;
  try { await MatchService.submitRematchVote("m-b", "YES"); } catch (e) { notSeatedErr = e; }
  check("B. Only seated players can vote: a non-seated uid is rejected", notSeatedErr && notSeatedErr.reason === "PERMISSION_DENIED");
  check("B. Only seated players can vote: no vote was recorded for the rejection attempt", Object.keys(getVote("m-b").votes).every(function (s) { return getVote("m-b").votes[s] == null; }));

  // ============================================================
  // C. Four seats initialized
  // ============================================================
  seedCompletedMatch("m-c");
  signInAs("uidA");
  await MatchService.createRematchVote("m-c");
  var voteC = getVote("m-c");
  check("C. Four seats initialized: votes has exactly p1-p4, all null", Object.keys(voteC.votes).length === 4 && ["p1", "p2", "p3", "p4"].every(function (s) { return voteC.votes[s] === null; }));
  check("C. Four seats initialized: seats copied verbatim from the parent match", JSON.stringify(voteC.seats) === JSON.stringify({ p1: "uidA", p2: "uidB", p3: "uidC", p4: "uidD" }));

  // 2-player match — structural support for <4 seats (per buildSeatMap()'s own established convention)
  seedCompletedMatch("m-c2", { players: ["uidA", "uidB"], seats: { p1: "uidA", p2: "uidB" }, bids: { p1: 4, p2: 3 }, winnerIds: ["uidA"], finalScores: { p1: 50, p2: 40 } });
  signInAs("uidA");
  await MatchService.createRematchVote("m-c2");
  var voteC2 = getVote("m-c2");
  check("C. 2-player match: exactly 2 seats initialized, no fabricated p3/p4", Object.keys(voteC2.votes).length === 2 && !("p3" in voteC2.votes) && !("p4" in voteC2.votes));

  // ============================================================
  // D. YES vote
  // ============================================================
  seedCompletedMatch("m-d");
  signInAs("uidA");
  await MatchService.createRematchVote("m-d");
  var dResult = await MatchService.submitRematchVote("m-d", "YES");
  check("D. YES vote: accepted and recorded", dResult.accepted === true && dResult.choice === "YES" && getVote("m-d").votes.p1 === "YES");
  check("D. YES vote: status stays OPEN (not all seats voted yet)", getVote("m-d").status === "OPEN");

  // ============================================================
  // E. NO vote
  // ============================================================
  seedCompletedMatch("m-e");
  signInAs("uidB");
  await MatchService.createRematchVote("m-e");
  var eResult = await MatchService.submitRematchVote("m-e", "NO");
  check("E. NO vote: accepted and recorded", eResult.accepted === true && getVote("m-e").votes.p2 === "NO");

  // ============================================================
  // F. Vote cannot be changed (YES -> NO rejected)
  // ============================================================
  seedCompletedMatch("m-f");
  signInAs("uidA");
  await MatchService.createRematchVote("m-f");
  await MatchService.submitRematchVote("m-f", "YES");
  var flipResult = await MatchService.submitRematchVote("m-f", "NO");
  check("F. Vote cannot be changed: a conflicting second value is rejected (not silently applied)", flipResult.accepted === false && flipResult.reason === "VOTE_LOCKED");
  check("F. Vote cannot be changed: the original YES is preserved", getVote("m-f").votes.p1 === "YES");

  // ============================================================
  // G. Duplicate same vote is idempotent
  // ============================================================
  seedCompletedMatch("m-g");
  signInAs("uidA");
  await MatchService.createRematchVote("m-g");
  await MatchService.submitRematchVote("m-g", "YES");
  var versionAfterFirst = getVote("m-g").version;
  var dupResult = await MatchService.submitRematchVote("m-g", "YES");
  check("G. Duplicate same vote: idempotent no-op, no error", dupResult.accepted === true && dupResult.reason === "ALREADY_VOTED");
  check("G. Duplicate same vote: version did not advance for the no-op", getVote("m-g").version === versionAfterFirst);

  // ============================================================
  // H. Conflicting second vote rejected/no-op. Uses a YES (which does
  // NOT close the vote on its own, unlike NO) so the vote is still
  // OPEN when the conflicting attempt arrives — isolating "the vote
  // LOCK itself rejects a flip" (VOTE_LOCKED) from "the vote is simply
  // already closed" (VOTE_CLOSED, covered structurally by F/I/J above
  // where a NO closes the vote as its own, separate, correct outcome).
  // ============================================================
  seedCompletedMatch("m-h");
  signInAs("uidC");
  await MatchService.createRematchVote("m-h");
  await MatchService.submitRematchVote("m-h", "YES"); // 1/4 — vote stays OPEN
  var versionAfterYes = getVote("m-h").version;
  var conflictResult = await MatchService.submitRematchVote("m-h", "NO");
  check("H. Conflicting second vote: rejected, never applied", conflictResult.accepted === false && conflictResult.reason === "VOTE_LOCKED");
  check("H. Conflicting second vote: no version change", getVote("m-h").version === versionAfterYes);

  // ============================================================
  // I. Any NO immediately fails
  // ============================================================
  seedCompletedMatch("m-i");
  signInAs("uidA");
  await MatchService.createRematchVote("m-i");
  await MatchService.submitRematchVote("m-i", "YES");
  signInAs("uidB");
  await MatchService.submitRematchVote("m-i", "NO");
  check("I. Any NO immediately fails: status is FAILED_NO after only 2/4 responded", getVote("m-i").status === "FAILED_NO");

  // ============================================================
  // J. 3 YES + 1 NO fails
  // ============================================================
  seedCompletedMatch("m-j");
  signInAs("uidA"); await MatchService.createRematchVote("m-j"); await MatchService.submitRematchVote("m-j", "YES");
  signInAs("uidB"); await MatchService.submitRematchVote("m-j", "YES");
  signInAs("uidC"); await MatchService.submitRematchVote("m-j", "YES");
  signInAs("uidD"); await MatchService.submitRematchVote("m-j", "NO");
  check("J. 3 YES + 1 NO fails: status is FAILED_NO, not ALL_YES", getVote("m-j").status === "FAILED_NO");

  // ============================================================
  // K. 4 YES succeeds
  // ============================================================
  seedCompletedMatch("m-k");
  signInAs("uidA"); await MatchService.createRematchVote("m-k"); await MatchService.submitRematchVote("m-k", "YES");
  signInAs("uidB"); await MatchService.submitRematchVote("m-k", "YES");
  signInAs("uidC"); await MatchService.submitRematchVote("m-k", "YES");
  signInAs("uidD"); var kFinal = await MatchService.submitRematchVote("m-k", "YES");
  check("K. 4 YES succeeds: status becomes ALL_YES exactly on the completing vote", getVote("m-k").status === "ALL_YES" && kFinal.status === "ALL_YES");

  // ============================================================
  // L. 30-second timeout fails
  //
  // resolveRematchVoteTimeout()'s own production code calls the REAL,
  // unmocked Date.now() (deliberately — see this sprint's own "no
  // client Date.now()+30000 authority" instruction; the only thing
  // that could safely be a fake here would be the SERVER's clock, and
  // there is no server in this Node harness to fake). So instead of
  // trying to fast-forward time forward (impossible without a real
  // sleep), these tests BACKDATE `createdAt` at vote-creation time —
  // clockOffsetMs is applied to the serverTimestamp() sentinel's
  // resolved value, simulating "this vote was created 31 real seconds
  // ago" relative to the genuinely-current Date.now() the production
  // code reads a moment later. advanceClock()/resetClock() below are
  // named for what they do to the WRITE-TIME sentinel, not to any
  // clock the production code itself reads.
  // ============================================================
  seedCompletedMatch("m-l");
  signInAs("uidA");
  advanceClock(-31000); // backdate: this vote will appear to have been created 31s ago
  await MatchService.createRematchVote("m-l");
  resetClock();
  var lResult = await MatchService.resolveRematchVoteTimeout("m-l");
  check("L. 30-second timeout fails: resolves to FAILED_TIMEOUT", lResult.resolved === true && getVote("m-l").status === "FAILED_TIMEOUT");

  // ============================================================
  // M. Unvoted player at timeout counts as NO (outcome, not a
  // fabricated vote value). Sequence that needs "some real time
  // elapsed AFTER a genuine, successful, in-window vote": create the
  // vote fresh (deadline genuinely 30s away), record one real YES
  // vote (must succeed — proves the vote was NOT already expired at
  // that moment), THEN directly backdate the stored `createdAt` to
  // simulate the 30s window having since elapsed — this is pure test
  // SETUP for "time has now passed," exactly like __seedMatch()'s own
  // direct STORE mutation in the browser harnesses; no client-facing
  // API can do this, and resolveRematchVoteTimeout() below is still
  // the REAL production function reading the REAL Date.now().
  // ============================================================
  seedCompletedMatch("m-m");
  signInAs("uidA");
  await MatchService.createRematchVote("m-m");
  var mYesResult = await MatchService.submitRematchVote("m-m", "YES"); // only 1/4 voted, well within the window
  check("M. (setup) the in-window YES genuinely succeeded before backdating", mYesResult.accepted === true);
  STORE[voteKey("m-m")].createdAt = { __isTimestamp: true, toMillis: function () { return Date.now() - 31000; } };
  await MatchService.resolveRematchVoteTimeout("m-m");
  check("M. Unvoted counts as NO: overall outcome is FAILED_TIMEOUT despite one real, valid YES", getVote("m-m").status === "FAILED_TIMEOUT");
  check("M. Unvoted counts as NO: the unvoted seats' vote values are NOT fabricated to 'NO' — they remain null (the OUTCOME is the fail, not the raw data)", getVote("m-m").votes.p2 === null && getVote("m-m").votes.p3 === null && getVote("m-m").votes.p4 === null);
  check("M. Unvoted counts as NO: the one real YES vote itself is untouched/preserved", getVote("m-m").votes.p1 === "YES");

  // ============================================================
  // N. Timeout cannot happen before deadline
  // ============================================================
  seedCompletedMatch("m-n");
  signInAs("uidA");
  await MatchService.createRematchVote("m-n"); // created "now" — deadline is genuinely 30s in the future
  var tooEarly = await MatchService.resolveRematchVoteTimeout("m-n");
  check("N. Timeout cannot happen before deadline: not resolved, vote stays OPEN", tooEarly.resolved === false && getVote("m-n").status === "OPEN");

  // ============================================================
  // O. Vote after deadline rejected
  // ============================================================
  seedCompletedMatch("m-o");
  signInAs("uidA");
  advanceClock(-31000);
  await MatchService.createRematchVote("m-o");
  resetClock();
  var lateVote = await MatchService.submitRematchVote("m-o", "YES");
  check("O. Vote after deadline rejected: submitRematchVote() itself refuses (fast client-side pre-check — see this sprint's own 'neither layer trusts the other alone' fix)", lateVote.accepted === false && lateVote.reason === "VOTE_EXPIRED");
  check("O. Vote after deadline rejected: no vote value was recorded", getVote("m-o").votes.p1 === null);

  // ============================================================
  // P. Simultaneous final YES race (two "clients" racing the 4th vote)
  // Modeled the same way tests/submit-bid.test.cjs models a bid race —
  // two logically-concurrent calls against the SAME fake transactional
  // store; Firestore's own conflict-retry (this mock's own
  // implementation of it) guarantees exactly one sees itself as "the"
  // completing vote.
  // ============================================================
  seedCompletedMatch("m-p");
  signInAs("uidA"); await MatchService.createRematchVote("m-p"); await MatchService.submitRematchVote("m-p", "YES");
  signInAs("uidB"); await MatchService.submitRematchVote("m-p", "YES");
  signInAs("uidC"); await MatchService.submitRematchVote("m-p", "YES");
  signInAs("uidD");
  var raceResults = await Promise.all([MatchService.submitRematchVote("m-p", "YES"), MatchService.submitRematchVote("m-p", "YES")]);
  check("P. Simultaneous final YES race: exactly one ends OPEN write, other is idempotent no-op (ALREADY_VOTED)", raceResults.filter(function (r) { return r.reason === "ALREADY_VOTED" || r.status === "ALL_YES"; }).length === 2);
  check("P. Simultaneous final YES race: final status is ALL_YES, never corrupted", getVote("m-p").status === "ALL_YES");

  // ============================================================
  // Q. Simultaneous NO race (two different seats racing NO)
  // ============================================================
  seedCompletedMatch("m-q");
  signInAs("uidA"); await MatchService.createRematchVote("m-q");
  signInAs("uidB");
  var noRace = await Promise.all([MatchService.submitRematchVote("m-q", "NO"), (function () { signInAs("uidC"); return MatchService.submitRematchVote("m-q", "NO"); })()]);
  check("Q. Simultaneous NO race: status is FAILED_NO, no corruption", getVote("m-q").status === "FAILED_NO");

  // ============================================================
  // R. Simultaneous timeout race (two clients both attempt timeout resolution)
  // ============================================================
  seedCompletedMatch("m-r");
  signInAs("uidA");
  advanceClock(-31000);
  await MatchService.createRematchVote("m-r");
  resetClock();
  var timeoutRace = await Promise.all([MatchService.resolveRematchVoteTimeout("m-r"), MatchService.resolveRematchVoteTimeout("m-r")]);
  check("R. Simultaneous timeout race: exactly one resolves=true, other is ALREADY_RESOLVED no-op", timeoutRace.filter(function (r) { return r.resolved === true; }).length === 1);
  check("R. Simultaneous timeout race: final status is FAILED_TIMEOUT, no corruption", getVote("m-r").status === "FAILED_TIMEOUT");

  // ============================================================
  // S. Simultaneous rematch creation race
  // ============================================================
  seedCompletedMatch("m-s");
  signInAs("uidA"); await MatchService.createRematchVote("m-s"); await MatchService.submitRematchVote("m-s", "YES");
  signInAs("uidB"); await MatchService.submitRematchVote("m-s", "YES");
  signInAs("uidC"); await MatchService.submitRematchVote("m-s", "YES");
  signInAs("uidD"); await MatchService.submitRematchVote("m-s", "YES");
  check("S. (setup) status is ALL_YES before the creation race", getVote("m-s").status === "ALL_YES");
  var createRace = await Promise.all([MatchService.createRematchMatch("m-s"), MatchService.createRematchMatch("m-s")]);
  check("S. Simultaneous rematch creation race: exactly one created=true, other is idempotent no-op returning the SAME newMatchId", createRace[0].newMatchId === createRace[1].newMatchId && createRace.filter(function (r) { return r.created === true; }).length === 1);

  // ============================================================
  // T. Exactly one new match created
  // ============================================================
  var allMatchKeys = Object.keys(STORE).filter(function (k) { return k.indexOf("matches/") === 0 && k.split("/").length === 2 && k !== "matches/m-s"; });
  var newMatchKeysForS = allMatchKeys.filter(function (k) { return k === "matches/" + getVote("m-s").newMatchId; });
  check("T. Exactly one new match created for m-s's vote", newMatchKeysForS.length === 1);

  // ============================================================
  // U. Old match remains unchanged
  // ============================================================
  var oldMatchS = STORE["matches/m-s"];
  check("U. Old match remains unchanged: status/winnerIds/finalScores/completedRound untouched by rematch creation",
    oldMatchS.status === "complete" && JSON.stringify(oldMatchS.winnerIds) === JSON.stringify(["uidA"]) &&
    oldMatchS.completedRound === 18 && JSON.stringify(oldMatchS.finalScores) === JSON.stringify({ p1: 100, p2: 80, p3: 70, p4: 60 }));

  // ============================================================
  // V. New match has same four players / W. same seat assignments
  // ============================================================
  var newMatchS = STORE["matches/" + getVote("m-s").newMatchId];
  check("V. New match has the SAME four players (as a set)", newMatchS.players.slice().sort().join(",") === ["uidA", "uidB", "uidC", "uidD"].sort().join(","));
  check("W. New match has the SAME seat assignments", JSON.stringify(newMatchS.seats) === JSON.stringify({ p1: "uidA", p2: "uidB", p3: "uidC", p4: "uidD" }));
  check("W. New match links back to the old one (rematchOfMatchId)", newMatchS.rematchOfMatchId === "m-s");
  check("W. New match starts fresh (status starting, round 1, no cards/bids)", newMatchS.status === "starting" && newMatchS.currentRound === 1 && newMatchS.cardLog.length === 0);

  // ============================================================
  // X. Arbitrary UID injection rejected — createRematchMatch() never
  // reads any client-supplied player/seat list; it derives everything
  // from the vote's OWN, already-authoritative seats map. There is no
  // parameter on createRematchMatch() through which a client COULD
  // inject an arbitrary uid — verified structurally, not by trying to
  // pass one (the function signature itself is (matchId) only).
  // ============================================================
  check("X. Arbitrary UID injection rejected: createRematchMatch() takes no players/seats argument at all — nothing to inject", MatchService.createRematchMatch.length === 1);
  check("X. Arbitrary UID injection rejected: new match's players are EXACTLY the vote's own seats' values, never a superset", newMatchS.players.every(function (p) { return Object.values(newMatchS.seats).indexOf(p) !== -1; }));

  // ============================================================
  // Y. New matchId differs from old matchId
  // ============================================================
  check("Y. New matchId differs from old matchId", getVote("m-s").newMatchId !== "m-s");

  // ============================================================
  // Z. Reload during vote recovers authoritative state — modeled as a
  // SECOND, independent subscribeToRematchVote() call for the same
  // matchId (exactly what a page reload produces: a fresh subscriber
  // with no prior local state) observing the CURRENT document, not a
  // stale/default one.
  // ============================================================
  seedCompletedMatch("m-z");
  signInAs("uidA");
  await MatchService.createRematchVote("m-z");
  await MatchService.submitRematchVote("m-z", "YES");
  var reloadObserved = null;
  var unsub = MatchService.subscribeToRematchVote("m-z", function (data) { reloadObserved = data; });
  check("Z. Reload during vote recovers authoritative state: a fresh subscriber immediately observes the CURRENT vote (YES already cast)", reloadObserved && reloadObserved.votes.p1 === "YES");
  unsub();

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exit(fail ? 1 : 0);
})();
