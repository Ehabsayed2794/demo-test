const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Real, executable tests for the Match Completion sprint:
//   Round N (52 cards resolved) -> ScoringEngine.computeRoundExtension()
//   -> [MatchService.extendMatchRounds()] -> GameSession.isMatchComplete()
//   -> ScoringEngine.computeWinner() -> MatchService.endMatch() OR
//   MatchService.advanceToNextRound() -> every subscribed client's
//   MatchAdapter.applyRemoteMatchCompletion()/applyRemoteRoundTransition()
// exercised against the REAL design-ui/match-service.js,
// design-ui/match-adapter.js, design-ui/engine/bidding-engine.js,
// design-ui/engine/scoring-engine.js, and design-ui/engine/session.js
// (GameSession) — not stubs, not fakes, the actual shipped code.
//
// LABELING: every check below is MOCKED (real code, hand-written fake
// Firestore — the SAME harness shape as tests/round-lifecycle.test.cjs)
// unless marked PURE (a direct call into a pure calculation function,
// no Firestore/engine wiring involved at all). No SIMULATED checks
// (firestore.rules' own isValidRoundExtension()/isValidMatchCompletion()
// are covered separately, in tests/rules-simulation.test.js). No real
// Firestore project, Firebase Emulator, or browser was used here.
global.window = global;
global.window.addEventListener = function () {};

var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var ONSNAPSHOT_CALLS = {};

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
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    update: function (patch) {
      STORE[k] = Object.assign({}, STORE[k], patch);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      notify(k);
      return Promise.resolve();
    },
    onSnapshot: function (onNext) {
      ONSNAPSHOT_CALLS[k] = (ONSNAPSHOT_CALLS[k] || 0) + 1;
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
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { return makeMatchRef(id); } };
  },
  runTransaction: function (fn, attempt) {
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
    var seenVersions = {}, pending = {};
    var tx = {
      get: function (ref) { seenVersions[ref._key] = DOC_VERSION[ref._key] || 0; return ref.get(); },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      Object.keys(pending).forEach(function (k) { STORE[k] = Object.assign({}, STORE[k], pending[k].data); DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1; });
      Object.keys(pending).forEach(function (k) { notify(k); });
      return result;
    });
  }
};
global.Db = FAKE_DB;
global.firebase = { firestore: { FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } } } };

var CURRENT_USER = null;
global.SessionService = { getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; }, setCurrentMatchId: function () { return Promise.resolve(); } };
function signInAs(uid) { CURRENT_USER = uid; }

require(__REPO_ROOT__ + "/design-ui/match-service.js");
require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/engine/bidding-engine.js");
require(__REPO_ROOT__ + "/design-ui/engine/scoring-engine.js");
require(__REPO_ROOT__ + "/design-ui/match-adapter.js");

var MatchService = global.MatchService;
var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var ScoringEngine = global.ScoringEngine;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function seedMockMatch(matchId, opts) {
  opts = opts || {};
  STORE[key(matchId)] = Object.assign({
    roomId: "room-x", players: ["u1", "u2", "u3", "u4"], status: "starting", createdAt: 1, currentRound: 1,
    maxRounds: 18, extendedRounds: [],
    dealer: "u1", turn: null, gameState: { initialized: false },
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: []
  }, opts);
  DOC_VERSION[key(matchId)] = (DOC_VERSION[key(matchId)] || 0) + 1;
}

function fiftyTwoRoundTaggedCardEntries(round) {
  var log = [];
  for (var t = 0; t < 13; t++) {
    ["p1", "p2", "p3", "p4"].forEach(function (seatId) {
      log.push({ seatId: seatId, card: { suit: "SPADES", rank: { v: 2, s: "2" } }, round: round });
    });
  }
  return log;
}

Promise.resolve()

  // ════════════════════════════════════════════════════════════════
  // PURE — ScoringEngine.computeRoundExtension(): rules A-G.
  // ════════════════════════════════════════════════════════════════
  .then(function () {
    // A/H covered via MatchService below (maxRounds-aware completion).
    // B is implicit: computeRoundExtension() only ever returns {extend:false}
    // for round < 14, so isMatchComplete() (round < maxRounds) naturally holds.
    check("B (PURE). computeRoundExtension() never extends a round below the Rapid Rounds window (round 5)",
      ScoringEngine.computeRoundExtension({ round: 5, callerId: "p1", trump: "HEARTS", breakdown: { p1: { succeeded: true } }, isSaayda: false }).extend === false);

    // C. Successful Super Call + different mandatory trump during Rapid Round -> extend.
    var mandatoryTrump15 = BiddingEngine.fixedTrumpFor(15); // SPADES (14=SANS,15=SPADES per FIXED_SUITS)
    check("C (PURE). Successful Super Call with a DIFFERENT trump than round 15's mandatory trump -> extend:true, reason:SUPER_CALL",
      (function () {
        var r = ScoringEngine.computeRoundExtension({ round: 15, callerId: "p1", trump: "HEARTS", breakdown: { p1: { succeeded: true } }, isSaayda: false });
        return r.extend === true && r.reason === "SUPER_CALL" && mandatoryTrump15 !== "HEARTS";
      })());

    // D. Successful Super Call + SAME mandatory trump -> no extension.
    check("D (PURE). Successful Super Call with the SAME trump as round 15's mandatory trump -> extend:false",
      ScoringEngine.computeRoundExtension({ round: 15, callerId: "p1", trump: mandatoryTrump15, breakdown: { p1: { succeeded: true } }, isSaayda: false }).extend === false);

    // E. Failed Super Call -> no extension.
    check("E (PURE). Failed Super Call (breakdown.succeeded:false) -> extend:false regardless of trump mismatch",
      ScoringEngine.computeRoundExtension({ round: 15, callerId: "p1", trump: "HEARTS", breakdown: { p1: { succeeded: false } }, isSaayda: false }).extend === false);

    // F. Super Call outside Rapid Rounds 14-18 -> no extension.
    check("F (PURE). A qualifying-shaped Super Call at round 19 (already-extended territory, outside the 14-18 window) -> extend:false",
      ScoringEngine.computeRoundExtension({ round: 19, callerId: "p1", trump: "HEARTS", breakdown: { p1: { succeeded: true } }, isSaayda: false }).extend === false);

    // G. Multiple qualifying Super Calls -> each independently returns +1 (accumulation is MatchService.extendMatchRounds()'s job, tested below).
    check("G (PURE). Two independent qualifying rounds (15 and 17) EACH independently qualify for +1",
      (function () {
        var r15 = ScoringEngine.computeRoundExtension({ round: 15, callerId: "p1", trump: "HEARTS", breakdown: { p1: { succeeded: true } }, isSaayda: false });
        var r17 = ScoringEngine.computeRoundExtension({ round: 17, callerId: "p2", trump: "SANS", breakdown: { p2: { succeeded: true } }, isSaayda: false });
        return r15.extend === true && r17.extend === true;
      })());

    // "All players lose" (Sa'ayda) inside Rapid Rounds also qualifies, independent of caller/trump.
    check("PURE. Sa'ayda (all players lose) inside Rapid Rounds -> extend:true, reason:SAAYDA, regardless of callerId",
      ScoringEngine.computeRoundExtension({ round: 16, callerId: null, trump: null, breakdown: {}, isSaayda: true }).extend === true &&
      ScoringEngine.computeRoundExtension({ round: 16, callerId: null, trump: null, breakdown: {}, isSaayda: true }).reason === "SAAYDA");
    check("PURE. Sa'ayda OUTSIDE the Rapid Rounds window (round 5) does NOT extend (extension is Rapid-Rounds-only)",
      ScoringEngine.computeRoundExtension({ round: 5, callerId: null, trump: null, breakdown: {}, isSaayda: true }).extend === false);
  })

  // ════════════════════════════════════════════════════════════════
  // PURE — ScoringEngine.computeWinner(): I-L, multi-winner support.
  // ════════════════════════════════════════════════════════════════
  .then(function () {
    check("I (PURE). Single winner: computeWinner() returns exactly the one highest-score seat",
      JSON.stringify(ScoringEngine.computeWinner({ p1: 120, p2: 40, p3: 30, p4: 20 }).sort()) === JSON.stringify(["p1"]));
    check("J (PURE). Two tied Kings: computeWinner() returns BOTH tied seats, no tie-breaker applied",
      JSON.stringify(ScoringEngine.computeWinner({ p1: 100, p2: 100, p3: 40, p4: 20 }).sort()) === JSON.stringify(["p1", "p2"]));
    check("K (PURE). Three tied Kings: computeWinner() returns all three",
      JSON.stringify(ScoringEngine.computeWinner({ p1: 100, p2: 100, p3: 100, p4: 20 }).sort()) === JSON.stringify(["p1", "p2", "p3"]));
    check("L (PURE). Four tied Kings: computeWinner() returns all four seats",
      JSON.stringify(ScoringEngine.computeWinner({ p1: 100, p2: 100, p3: 100, p4: 100 }).sort()) === JSON.stringify(["p1", "p2", "p3", "p4"]));
    check("PURE. computeWinner() with negative scores still picks the (least-negative) maximum correctly",
      JSON.stringify(ScoringEngine.computeWinner({ p1: -10, p2: -5, p3: -30, p4: -5 }).sort()) === JSON.stringify(["p2", "p4"]));
  })

  // ════════════════════════════════════════════════════════════════
  // PURE — GameSession.isMatchComplete(): dynamic maxRounds, never 18.
  // ════════════════════════════════════════════════════════════════
  .then(function () {
    GameSession.reset(null);
    check("A (PURE). isMatchComplete() is false before round 18 with the default maxRounds:18",
      (GameSession.setRound({ number: 17, maxRounds: 18 }), GameSession.isMatchComplete() === false));
    check("A (PURE). isMatchComplete() is true once currentRound reaches maxRounds:18 (normal, un-extended match)",
      (GameSession.setRound({ number: 18, maxRounds: 18 }), GameSession.isMatchComplete() === true));
    check("H (PURE). An EXTENDED match (maxRounds:19) is NOT complete at round 18 — the old hardcoded ceiling never applies",
      (GameSession.setRound({ number: 18, maxRounds: 19 }), GameSession.isMatchComplete() === false));
    check("H (PURE). An EXTENDED match (maxRounds:19) IS complete once round 19 is reached",
      (GameSession.setRound({ number: 19, maxRounds: 19 }), GameSession.isMatchComplete() === true));
    GameSession.reset(null);
  })

  // ════════════════════════════════════════════════════════════════
  // C/G — MatchService.extendMatchRounds(): real Firestore-shaped
  // transaction, structural checks, idempotency, accumulation.
  // ════════════════════════════════════════════════════════════════
  .then(function () {
    seedMockMatch("match-ext-1", { currentRound: 15 });
    signInAs("u1");
    return MatchService.extendMatchRounds("match-ext-1", 15, "SUPER_CALL").then(function (result) {
      check("C. extendMatchRounds(): a well-formed extension is applied — extended:true, maxRounds 18 -> 19", result.extended === true && result.maxRounds === 19);
      check("C. extendMatchRounds(): the round is recorded in extendedRounds", STORE[key("match-ext-1")].extendedRounds.indexOf(15) !== -1);
    });
  })
  .then(function () {
    // M. Duplicate attempt for the SAME round is idempotent.
    return MatchService.extendMatchRounds("match-ext-1", 15, "SUPER_CALL").then(function (result) {
      check("M. extendMatchRounds(): a duplicate attempt for the SAME round is idempotent — extended:false, reason:ALREADY_EXTENDED, no double-increment", result.extended === false && result.reason === "ALREADY_EXTENDED" && STORE[key("match-ext-1")].maxRounds === 19);
    });
  })
  .then(function () {
    // G. A second, independent qualifying round accumulates +1 again.
    STORE[key("match-ext-1")].currentRound = 17;
    return MatchService.extendMatchRounds("match-ext-1", 17, "SAAYDA").then(function (result) {
      check("G. extendMatchRounds(): a SECOND independent qualifying round (17) accumulates another +1 — maxRounds 19 -> 20", result.extended === true && result.maxRounds === 20);
    });
  })
  .then(function () {
    // P. Invalid maxRounds inflation rejected — round outside 14-18.
    return MatchService.extendMatchRounds("match-ext-1", 10, "SUPER_CALL").catch(function (e) { return e; }).then(function (e) {
      check("P. extendMatchRounds(): a round OUTSIDE the Rapid Rounds window (10) is REJECTED — INVALID_ARGUMENT", e && e.reason === "INVALID_ARGUMENT");
    });
  })
  .then(function () {
    return MatchService.extendMatchRounds("match-ext-1", 15, "NOT_A_REAL_REASON").catch(function (e) { return e; }).then(function (e) {
      check("P. extendMatchRounds(): an invalid `reason` value is REJECTED — INVALID_ARGUMENT", e && e.reason === "INVALID_ARGUMENT");
    });
  })
  .then(function () {
    // N. Two clients attempting the SAME extension concurrently converge (Promise.all over both calls).
    seedMockMatch("match-ext-2", { currentRound: 16 });
    return Promise.all([
      MatchService.extendMatchRounds("match-ext-2", 16, "SUPER_CALL"),
      MatchService.extendMatchRounds("match-ext-2", 16, "SUPER_CALL")
    ]).then(function (results) {
      var extendedCount = results.filter(function (r) { return r.extended === true; }).length;
      check("N. extendMatchRounds(): two concurrent attempts for the SAME round converge — exactly ONE actually extends, the other is a safe no-op", extendedCount === 1);
      check("N. extendMatchRounds(): maxRounds increased by exactly 1 total, not 2, despite two concurrent callers", STORE[key("match-ext-2")].maxRounds === 19);
    });
  })

  // ════════════════════════════════════════════════════════════════
  // A/H/M/N/O — MatchService.endMatch(): completion, idempotency,
  // concurrency, invalid winnerIds/finalScores rejection.
  // ════════════════════════════════════════════════════════════════
  .then(function () {
    seedMockMatch("match-end-1", { currentRound: 18, cardLog: fiftyTwoRoundTaggedCardEntries(18) });
    signInAs("u1");
    var finalScores = { p1: 120, p2: 40, p3: 30, p4: 20 };
    return MatchService.endMatch("match-end-1", 18, finalScores, ["p1"]).then(function (result) {
      check("A. endMatch(): a well-formed completion at currentRound==maxRounds is applied — complete:true, status:'complete'", result.complete === true && STORE[key("match-end-1")].status === "complete");
      check("A. endMatch(): finalScores/winnerIds/completedRound are persisted verbatim", JSON.stringify(STORE[key("match-end-1")].finalScores) === JSON.stringify(finalScores) &&
        JSON.stringify(STORE[key("match-end-1")].winnerIds) === JSON.stringify(["p1"]) && STORE[key("match-end-1")].completedRound === 18);
    });
  })
  .then(function () {
    // M. Duplicate endMatch attempt is idempotent.
    return MatchService.endMatch("match-end-1", 18, { p1: 999 }, ["p2"]).then(function (result) {
      check("M. endMatch(): a duplicate attempt on an already-complete match is idempotent — complete:false, reason:ALREADY_COMPLETE, original result UNCHANGED",
        result.complete === false && result.reason === "ALREADY_COMPLETE" &&
        STORE[key("match-end-1")].winnerIds[0] === "p1" && STORE[key("match-end-1")].finalScores.p1 === 120);
    });
  })
  .then(function () {
    // Q. status cannot move from complete back to starting — enforced structurally: endMatch() never writes any status other than "complete", and the idempotent branch above proves a second call can't touch it again.
    check("Q. status never regresses: match-end-1's status is still 'complete' after every subsequent (idempotent) attempt", STORE[key("match-end-1")].status === "complete");
  })
  .then(function () {
    // H. Match completion uses the EXTENDED maxRounds, not a hardcoded 18.
    seedMockMatch("match-end-2", { currentRound: 18, maxRounds: 19, extendedRounds: [15], cardLog: fiftyTwoRoundTaggedCardEntries(18) });
    return MatchService.endMatch("match-end-2", 18, { p1: 10, p2: 10, p3: 10, p4: 10 }, ["p1", "p2", "p3", "p4"]).then(function (result) {
      check("H. endMatch(): round 18 does NOT complete an EXTENDED match (maxRounds:19) — complete:false, reason:MATCH_NOT_OVER", result.complete === false && result.reason === "MATCH_NOT_OVER");
    });
  })
  .then(function () {
    STORE[key("match-end-2")].currentRound = 19;
    STORE[key("match-end-2")].cardLog = fiftyTwoRoundTaggedCardEntries(19);
    return MatchService.endMatch("match-end-2", 19, { p1: 10, p2: 10, p3: 10, p4: 10 }, ["p1", "p2", "p3", "p4"]).then(function (result) {
      check("H. endMatch(): the SAME match completes correctly once round 19 (the EXTENDED ceiling) is reached — complete:true", result.complete === true);
      check("K. endMatch(): all-four-tied result correctly persists FOUR winnerIds", STORE[key("match-end-2")].winnerIds.length === 4);
    });
  })
  .then(function () {
    // O. Invalid client-supplied winnerIds rejected — winnerIds doesn't match the actual max of finalScores.
    seedMockMatch("match-end-3", { currentRound: 18, cardLog: fiftyTwoRoundTaggedCardEntries(18) });
    return MatchService.endMatch("match-end-3", 18, { p1: 120, p2: 40, p3: 30, p4: 20 }, ["p2"]).catch(function (e) { return e; }).then(function (e) {
      check("O. endMatch(): winnerIds NOT matching the actual highest score in finalScores is REJECTED — INVALID_RESULT", e && e.reason === "INVALID_RESULT" && STORE[key("match-end-3")].status !== "complete");
    });
  })
  .then(function () {
    // O. Missing a real winner from a tie is also rejected (partial winnerIds).
    return MatchService.endMatch("match-end-3", 18, { p1: 100, p2: 100, p3: 30, p4: 20 }, ["p1"]).catch(function (e) { return e; }).then(function (e) {
      check("O. endMatch(): winnerIds missing a TIED seat (only p1, not p1+p2) is REJECTED — INVALID_RESULT", e && e.reason === "INVALID_RESULT");
    });
  })
  .then(function () {
    // Structural completion check reused: round not genuinely complete (fewer than 52 card entries).
    seedMockMatch("match-end-4", { currentRound: 18, cardLog: fiftyTwoRoundTaggedCardEntries(18).slice(0, 8) });
    return MatchService.endMatch("match-end-4", 18, { p1: 10, p2: 10, p3: 10, p4: 10 }, ["p1", "p2", "p3", "p4"]).catch(function (e) { return e; }).then(function (e) {
      check("endMatch(): ROUND_NOT_COMPLETE is rejected when fewer than 52 round-tagged card entries exist", e && e.reason === "ROUND_NOT_COMPLETE");
    });
  })
  .then(function () {
    // N. Two clients attempting completion concurrently converge to the same result.
    seedMockMatch("match-end-5", { currentRound: 18, cardLog: fiftyTwoRoundTaggedCardEntries(18) });
    var scores = { p1: 55, p2: 55, p3: 10, p4: 5 };
    return Promise.all([
      MatchService.endMatch("match-end-5", 18, scores, ["p1", "p2"]),
      MatchService.endMatch("match-end-5", 18, scores, ["p1", "p2"])
    ]).then(function (results) {
      var completedCount = results.filter(function (r) { return r.complete === true; }).length;
      check("N. endMatch(): two concurrent completion attempts converge — exactly ONE actually completes, the other is a safe no-op", completedCount === 1);
      check("N. endMatch(): status is 'complete' exactly once regardless of the race", STORE[key("match-end-5")].status === "complete");
    });
  })
  .then(function () {
    // REGRESSION (found via real-browser QA, Phase 4 Scenario N):
    // advanceToNextRound() racing endMatch() for the SAME completedRound
    // must NEVER win once the match has already completed — it must
    // converge on MATCH_ALREADY_COMPLETE, never bump currentRound past
    // a document that is simultaneously claiming to be finished.
    seedMockMatch("match-end-6", { currentRound: 18, cardLog: fiftyTwoRoundTaggedCardEntries(18) });
    var scores6 = { p1: 40, p2: 30, p3: 20, p4: 10 };
    return MatchService.endMatch("match-end-6", 18, scores6, ["p1"]).then(function () {
      return MatchService.advanceToNextRound("match-end-6", 18);
    }).then(function (advanceResult) {
      check("Regression. advanceToNextRound() called AFTER endMatch() already completed the SAME round is a safe no-op — advanced:false, reason:MATCH_ALREADY_COMPLETE", advanceResult.advanced === false && advanceResult.reason === "MATCH_ALREADY_COMPLETE");
      check("Regression. currentRound was NOT bumped past the completed match — still 18, not 19", STORE[key("match-end-6")].currentRound === 18);
      check("Regression. status remains 'complete', untouched by the rejected advance attempt", STORE[key("match-end-6")].status === "complete");
    });
  })
  .then(function () {
    // Same regression, genuinely concurrent (Promise.all, not sequential).
    seedMockMatch("match-end-7", { currentRound: 18, cardLog: fiftyTwoRoundTaggedCardEntries(18) });
    var scores7 = { p1: 40, p2: 30, p3: 20, p4: 10 };
    return Promise.all([
      MatchService.endMatch("match-end-7", 18, scores7, ["p1"]),
      MatchService.advanceToNextRound("match-end-7", 18).catch(function (e) { return { advanced: false, reason: e.reason }; })
    ]).then(function (results) {
      var doc = STORE[key("match-end-7")];
      check("Regression (concurrent). endMatch() racing advanceToNextRound() for the SAME round converges on completion, never a corrupted currentRound bump", doc.status === "complete" && doc.currentRound === 18);
      check("Regression (concurrent). The racing advanceToNextRound() attempt correctly reports it did NOT advance (either ALREADY_ADVANCED or MATCH_ALREADY_COMPLETE, never advanced:true)", results[1].advanced === false);
    });
  })
  .then(function () {
    // Same regression for extendMatchRounds(): must not extend an
    // already-completed match either. Forces completion directly
    // (rather than driving endMatch() through a real round, which
    // isn't the point of this particular check) — this is a setup
    // fixture, not a claim about how completion is normally reached.
    seedMockMatch("match-end-8", {
      currentRound: 18, extendedRounds: [], status: "complete",
      winnerIds: ["p1"], finalScores: { p1: 40, p2: 30, p3: 20, p4: 10 }, completedRound: 18
    });
    signInAs("u1");
    return MatchService.extendMatchRounds("match-end-8", 15, "SUPER_CALL").then(function (result) {
      check("Regression. extendMatchRounds() called on an ALREADY-complete match is a safe no-op — extended:false, reason:MATCH_ALREADY_COMPLETE", result.extended === false && result.reason === "MATCH_ALREADY_COMPLETE");
      check("Regression. maxRounds was NOT bumped on the completed match", STORE[key("match-end-8")].maxRounds === 18);
    });
  })

  // ════════════════════════════════════════════════════════════════
  // MatchAdapter — remote sync: winnerIds/status/maxRounds propagate
  // to a client that did NOT win the endMatch()/extendMatchRounds()
  // race (or reconnects after the fact).
  // ════════════════════════════════════════════════════════════════
  .then(function () {
    MatchAdapter.resetSyncState();
    GameSession.reset(null);
    var matchId = "match-remote-complete";
    seedMockMatch(matchId, {
      currentRound: 18, status: "complete", winnerIds: ["p1", "p3"],
      finalScores: { p1: 90, p2: 40, p3: 90, p4: 20 }, completedRound: 18
    });
    var unsub = MatchAdapter.startMatchCompletionSync(matchId);
    check("MatchAdapter.applyRemoteMatchCompletion(): a client that did NOT run endMatch() itself still receives winnerIds via sync", JSON.stringify(GameSession.getWinnerIds().sort()) === JSON.stringify(["p1", "p3"]));
    var before = ONSNAPSHOT_CALLS[key(matchId)];
    var unsub2 = MatchAdapter.startRoundSync(matchId);
    check("MatchAdapter: startMatchCompletionSync() + startRoundSync() for the SAME matchId share ONE Firestore listener", ONSNAPSHOT_CALLS[key(matchId)] === before);
    unsub(); unsub2();
  })
  .then(function () {
    // applyRemoteRoundTransition() also syncs maxRounds down independently of currentRound moving.
    GameSession.reset(null);
    GameSession.setRound({ number: 5, maxRounds: 18 });
    var applied = MatchAdapter.applyRemoteRoundTransition("match-maxrounds-sync", { currentRound: 5, maxRounds: 19 });
    check("MatchAdapter.applyRemoteRoundTransition(): syncs an AHEAD maxRounds down to the local session even when currentRound itself didn't move", GameSession.getRound().maxRounds === 19);
  })

  .then(function () {
    console.log("\n" + pass + " passed, " + fail + " failed");
    process.exitCode = fail ? 1 : 0;
  })
  .catch(function (e) {
    console.error("UNCAUGHT TEST ERROR:", e);
    process.exitCode = 1;
  });
