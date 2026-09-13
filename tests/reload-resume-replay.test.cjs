var REPO_ROOT = require("path").join(__dirname, "..");
// P1-3 reconnect regression (issue #16) — deterministic, no browser, no
// emulator, no timing involved.
//
// What this proves, through the REAL GameSession/TableEngine/BiddingEngine/
// MatchAdapter (same fake-Firestore harness shape as
// tests/round-lifecycle.test.cjs — the adapter replay path under test
// takes a plain matchDoc object, so no Firestore is needed at all):
//
//   CASE A pins the hazard, unchanged by the page fix: replaying the full
//   authoritative cardLog from wiped registries into a restored mid-round
//   engine double-resolves history — trickNo 13, tricksWon summing to 13
//   on a 28-card log, engine DONE. That is byte-for-byte the production
//   signature from issue #16 (adapter `resolved:13` vs 7, sums to 13 on
//   28 cards, R5 stall). If this case ever stops diverging, the adapter
//   gained cross-reload epoch protection — update this file then; do not
//   "fix" it by weakening the assertions.
//
//   CASE B pins the safe path match/index.html's maybeEnterPlayPhase()
//   now takes on every page load (clearPlayState() before initState()):
//   the same full-log replay onto a round-fresh engine converges EXACTLY
//   (trick 7, 6 counted, count 24) and genuine play continues cleanly
//   (trick 8, count 28).
//
// Determinism, not luck: hands are rigged so the auction caller holds all
// 13 trumps and wins every trick. The reloaded trick's leader is therefore
// ALWAYS the caller — the exact agreeing case (restored turn == the log's
// first entry) that diverged in production ~1/4 of deals. Both cases align
// on every run by construction.
//
// sessionStorage does not exist in this runner: session.js's own
// load()/persist() degrade to fresh-memory (their try/catch), so a
// "reload" is simulated structurally — and that IS the production reload
// outcome: adapter registries wiped (resetSyncState), the engine object
// untouched (identical to a restore — resolveTrick() persists every
// resolution synchronously in production), hands wiped then reseeded.
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
      STORE[k] = __resolveWritePatch(STORE[k], patch);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      notify(k);
      return Promise.resolve();
    },
    collection: function (sub) {
      return { doc: function (subId) { return makeSubRef(k, sub, subId); } };
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
function makeSubRef(parentKey, sub, subId) {
  var k = parentKey + "/" + sub + "/" + subId;
  return {
    id: subId, _key: k,
    get: function () {
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    set: function (data) {
      STORE[k] = Object.assign({}, data);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      notify(k);
      return Promise.resolve();
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
      set: function (ref, data) { pending[ref._key] = { ref: ref, mode: "set", data: data }; },
      update: function (ref, patch) { pending[ref._key] = { ref: ref, mode: "update", data: patch }; }
    };
    return Promise.resolve(fn(tx)).then(function (result) {
      var conflict = Object.keys(seenVersions).some(function (k) { return (DOC_VERSION[k] || 0) !== seenVersions[k]; });
      if (conflict) return FAKE_DB.runTransaction(fn, attempt + 1);
      Object.keys(pending).forEach(function (k) {
        STORE[k] = pending[k].mode === "set" ? Object.assign({}, pending[k].data) : __resolveWritePatch(STORE[k], pending[k].data);
        DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      });
      Object.keys(pending).forEach(function (k) { notify(k); });
      return result;
    });
  }
};
global.Db = FAKE_DB;
global.firebase = { firestore: { FieldValue: {
  serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; },
  arrayUnion: function () { return { __sentinel: "arrayUnion", values: Array.prototype.slice.call(arguments) }; }
} } };
function __resolveWritePatch(existing, patch) {
  var resolved = {};
  Object.keys(patch).forEach(function (k) {
    var v = patch[k];
    if (v && v.__sentinel === "arrayUnion") {
      var arr = (existing && Array.isArray(existing[k])) ? existing[k].slice() : [];
      v.values.forEach(function (item) {
        var already = arr.some(function (e) { return JSON.stringify(e) === JSON.stringify(item); });
        if (!already) arr.push(item);
      });
      resolved[k] = arr;
    } else {
      resolved[k] = v;
    }
  });
  return Object.assign({}, existing, resolved);
}

var CURRENT_USER = null;
global.SessionService = { getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; }, setCurrentMatchId: function () { return Promise.resolve(); } };

require(REPO_ROOT + "/design-ui/match-service.js");
require(REPO_ROOT + "/design-ui/engine/cards.js");
require(REPO_ROOT + "/design-ui/engine/deck.js");
require(REPO_ROOT + "/design-ui/engine/dealer.js");
require(REPO_ROOT + "/design-ui/engine/session.js");
require(REPO_ROOT + "/design-ui/engine/bidding-engine.js");
require(REPO_ROOT + "/design-ui/engine/scoring-engine.js");
require(REPO_ROOT + "/design-ui/match-adapter.js");

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function sumWon(tricksWon) {
  return Object.keys(tricksWon).reduce(function (acc, s) { return acc + tricksWon[s]; }, 0);
}
function clone(obj) { return JSON.parse(JSON.stringify(obj)); }

/** Real bidding through bidding-engine.js with exactly one bidder, so the
 *  caller is deterministic AND known: whoever bids first wins. Mirrors
 *  tests/round-lifecycle.test.cjs's own helper, minus its fixed-seat
 *  assumption. */
function driveBiddingSingleBidder() {
  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  BiddingEngine.initState();
  var bidDone = false;
  var guard = 0;
  while (guard < 30) {
    guard++;
    var s = BiddingEngine.getState();
    if (!s || s.subPhase === "DONE") break;
    if (s.subPhase === "DASH") {
      BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false });
    } else if (s.subPhase === "AUCTION") {
      if (!bidDone) {
        BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, tricks: 4, suit: "SPADES", isPass: false });
        bidDone = true;
      } else {
        BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true });
      }
    } else if (s.subPhase === "CONFIRM") {
      BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s.waitingFor, tricks: s.auctionTop, suit: s.auctionSuit });
    } else if (s.subPhase === "ESTIMATES") {
      BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: s.waitingFor, tricks: 2 });
    } else {
      break;
    }
  }
  return BiddingEngine.getState();
}

var RANKS = [[14, "A"], [13, "K"], [12, "Q"], [11, "J"], [10, "10"], [9, "9"], [8, "8"], [7, "7"], [6, "6"], [5, "5"], [4, "4"], [3, "3"], [2, "2"]];

/** Rigged full deal: the caller holds ALL trumps (wins every trick it
 *  leads — and it leads every trick, since it never loses one), everyone
 *  else holds only non-trumps. Card shape matches Dealer.dealHands(). */
function riggedHands(trumpSuit, callerSeat) {
  var callerCards = RANKS.map(function (r) { return { suit: trumpSuit, rank: { v: r[0], s: r[1] } }; });
  var rest = [];
  ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"].forEach(function (suit) {
    if (suit === trumpSuit) return;
    RANKS.forEach(function (r) { rest.push({ suit: suit, rank: { v: r[0], s: r[1] } }); });
  });
  var hands = {};
  hands[callerSeat] = callerCards;
  ["p1", "p2", "p3", "p4"].filter(function (s) { return s !== callerSeat; }).forEach(function (seat, si) {
    hands[seat] = rest.slice(si * 13, si * 13 + 13);
  });
  return hands;
}

var tableEngineLoaded = false;

/** Fresh round setup through the real path: bidding -> authoritative hands
 *  (the production startHandSync seam) -> TableEngine.initState().
 *  tag prefixes this setup's own check labels (setups run twice). */
function freshTableSetup(tag) {
  var bFinal = driveBiddingSingleBidder();
  check(tag + " bidding reaches DONE", !!bFinal && bFinal.subPhase === "DONE");
  var round = GameSession.getRound();
  var callerSeat = round && round.callerId;
  var trump = round && round.trump;
  check(tag + " auction produced a caller with a real trump suit", !!callerSeat && !!trump && trump !== "SANS");
  // Production seam: startHandSync() puts the session in firestore hand
  // authority (synchronous wipe) before initState() runs, then the hands
  // documents reseed via setAuthoritativeHand().
  GameSession.setHandAuthorityMode("firestore");
  var fullHands = riggedHands(trump, callerSeat);
  ["p1", "p2", "p3", "p4"].forEach(function (seat) {
    GameSession.setAuthoritativeHand(seat, fullHands[seat], 1);
  });
  if (!tableEngineLoaded) {
    require(REPO_ROOT + "/design-ui/engine/table-engine.js");
    tableEngineLoaded = true;
  }
  var TableEngine = global.TableEngine;
  TableEngine.initState();
  var st0 = TableEngine.getState();
  check(tag + " engine opens at trick 1 led by the caller", !!st0 && st0.trickNo === 1 && st0.turn === callerSeat && st0.leaderId === callerSeat);
  return { callerSeat: callerSeat, trump: trump, fullHands: fullHands, doc: { version: 1, cardLog: [] } };
}

/** One genuine play through the REAL adapter replay path (append to the
 *  authoritative log, bump version, applyRemoteCard + applyRemoteTrick —
 *  the startTrickSync shape): the engine-turn seat plays its first hand
 *  card. With rigged hands the caller leads and wins every trick, so the
 *  turn chain is deterministic. */
function replayOneGenuinePlay(TableEngine, setup, matchId) {
  var st = TableEngine.getState();
  var seat = st.turn;
  var card = st.hands[seat][0];
  setup.doc.cardLog.push({ seatId: seat, card: { suit: card.suit, rank: { v: card.rank.v, s: card.rank.s } }, round: 1 });
  setup.doc.version += 1;
  var rc = MatchAdapter.applyRemoteCard(matchId, setup.doc, setup.callerSeat);
  if (rc.desync) return { desync: true };
  for (var k = 0; k < 13; k++) {
    var rt = MatchAdapter.applyRemoteTrick(matchId, setup.doc);
    if (!rt.applied) break;
  }
  return { desync: false };
}

function replayGenuinePlays(TableEngine, setup, matchId, n) {
  for (var i = 0; i < n; i++) {
    var r = replayOneGenuinePlay(TableEngine, setup, matchId);
    if (r.desync) return { ok: false, at: i };
  }
  return { ok: true };
}

function nullSafeCount(v) { return v == null ? -1 : v; }

/** Full-log redelivery after a registry wipe (what every page load's first
 *  deliveries do): mirrors startTrickSync()/refreshEngineFromDoc() exactly
 *  — alternate applyRemoteCard + applyRemoteTrick until an iteration makes
 *  no progress. (One applyRemoteCard call CANNOT replay a whole log: the
 *  4th emit of each trick flips the engine to RESOLVING, which rejects
 *  further emits until applyRemoteTrick resolves — the routine
 *  ENGINE_REJECTED-at-the-trick-boundary the loop is built to drive
 *  through.) Returns the converged count/resolved registries. */
function redeliverFullLog(TableEngine, setup, matchId) {
  for (var k = 0; k < 40; k++) {
    var countBefore = nullSafeCount(MatchAdapter.getLastAppliedCardCount(matchId));
    var trickBefore = nullSafeCount(MatchAdapter.getLastResolvedTrickNo(matchId));
    MatchAdapter.applyRemoteCard(matchId, setup.doc, setup.callerSeat);
    var rt = MatchAdapter.applyRemoteTrick(matchId, setup.doc);
    var countAfter = nullSafeCount(MatchAdapter.getLastAppliedCardCount(matchId));
    var trickAfter = nullSafeCount(MatchAdapter.getLastResolvedTrickNo(matchId));
    if (countAfter === countBefore && trickAfter === trickBefore && !rt.applied) break;
  }
  return {
    count: MatchAdapter.getLastAppliedCardCount(matchId),
    resolved: MatchAdapter.getLastResolvedTrickNo(matchId)
  };
}

function main() {
  var TableEngine = null;

  // ════════════════════════════════════════════════════════════════
  // CASE A — pin the hazard (adapter behavior, NOT changed by the page
  // fix): replaying the full log from wiped registries into a RESTORED
  // mid-round engine double-resolves history. Production signature from
  // issue #16, reproduced exactly and deterministically.
  // ════════════════════════════════════════════════════════════════
  var A = freshTableSetup("A setup:");
  TableEngine = global.TableEngine;
  var matchA = "reload-case-a";
  var histA = replayGenuinePlays(TableEngine, A, matchA, 24);
  check("A history builds cleanly (24 genuine plays, no desync)", histA.ok === true);
  var preA = TableEngine.getState();
  check("A pre-reload engine is mid-round exact (trick 7, 6 counted, caller to lead)",
    preA.trickNo === 7 && sumWon(preA.tricksWon) === 6 && preA.turn === A.callerSeat && preA.phase === "PLAY");
  check("A adapter applied the full history (count 24, resolved 6)",
    MatchAdapter.getLastAppliedCardCount(matchA) === 24 && MatchAdapter.getLastResolvedTrickNo(matchA) === 6);

  // Structural reload WITHOUT the page fix: registries wiped, engine
  // object untouched (== the restore resolveTrick() persistence makes
  // exact in production), hands wiped then the own seat reseeded full
  // (the production startHandSync wipe + applyRemoteHand outcome —
  // other seats stay absent, exactly like production).
  MatchAdapter.resetSyncState(matchA);
  var liveA = TableEngine.getState();
  liveA.hands = {};
  liveA.hands[A.callerSeat] = clone(A.fullHands[A.callerSeat]);
  A.doc.version += 50;
  var redA = redeliverFullLog(TableEngine, A, matchA);
  var postA = TableEngine.getState();
  check("A HAZARD: history double-resolved (trickNo 13, 12 counted, count 24, resolved 12)",
    postA.trickNo === 13 && sumWon(postA.tricksWon) === 12 &&
    redA.count === 24 && redA.resolved === 12);

  // The 4 genuine next plays (trick 7, caller leads — the true game)
  // land on the diverged engine and race it to DONE, exactly like R5.
  var ccw = ["p1", "p2", "p3", "p4"];
  var startIdx = ccw.indexOf(A.callerSeat);
  for (var g = 0; g < 4; g++) {
    var seatG = ccw[(startIdx + g) % 4];
    var cardG = TableEngine.getState().hands[seatG][0];
    A.doc.cardLog.push({ seatId: seatG, card: { suit: cardG.suit, rank: { v: cardG.rank.v, s: cardG.rank.s } }, round: 1 });
    A.doc.version += 1;
  }
  redeliverFullLog(TableEngine, A, matchA);
  var finA = TableEngine.getState();
  check("A PRODUCTION SIGNATURE: DONE at trick 13, 13 counted on a 28-card log (count 28, resolved 13)",
    finA.phase === "DONE" && finA.trickNo === 13 && sumWon(finA.tricksWon) === 13 &&
    MatchAdapter.getLastAppliedCardCount(matchA) === 28 && MatchAdapter.getLastResolvedTrickNo(matchA) === 13);

  // ════════════════════════════════════════════════════════════════
  // CASE B — pin the safe path the page now takes (clearPlayState()
  // before initState() on every page load): the same full-log replay
  // onto a round-fresh engine converges EXACTLY, and genuine play
  // continues cleanly.
  // ════════════════════════════════════════════════════════════════
  var B = freshTableSetup("B setup:");
  var matchB = "reload-case-b";
  var histB = replayGenuinePlays(TableEngine, B, matchB, 24);
  check("B history builds cleanly (24 genuine plays, no desync)", histB.ok === true);

  // Reload WITH the page fix (match/index.html maybeEnterPlayPhase):
  // registries wiped, persisted playState discarded, hands reseeded
  // from the authoritative source, engine (re)started.
  MatchAdapter.resetSyncState(matchB);
  GameSession.clearPlayState();
  ["p1", "p2", "p3", "p4"].forEach(function (seat) {
    GameSession.setAuthoritativeHand(seat, clone(B.fullHands[seat]), 1);
  });
  TableEngine.initState();
  var freshB = TableEngine.getState();
  check("B post-clear engine restarts round-fresh (trick 1, zero counted)",
    freshB.trickNo === 1 && sumWon(freshB.tricksWon) === 0 && freshB.phase === "PLAY");

  B.doc.version += 50;
  var redB = redeliverFullLog(TableEngine, B, matchB);
  var postB = TableEngine.getState();
  check("B replay converges EXACTLY (trick 7, 6 counted, count 24, resolved 6, caller to lead)",
    postB.trickNo === 7 && sumWon(postB.tricksWon) === 6 && postB.phase === "PLAY" && postB.turn === B.callerSeat &&
    redB.count === 24 && redB.resolved === 6);

  var contB = replayGenuinePlays(TableEngine, B, matchB, 4);
  var finB = TableEngine.getState();
  check("B genuine play continues cleanly (trick 8, 7 counted, count 28)",
    contB.ok === true && finB.trickNo === 8 && sumWon(finB.tricksWon) === 7 &&
    MatchAdapter.getLastAppliedCardCount(matchB) === 28);
}

try {
  main();
} catch (e) {
  console.error("HARNESS CRASHED: " + ((e && e.stack) || e));
  process.exitCode = 1;
}
if (process.exitCode == null) {
  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
}
