const path = require("path");
const __REPO_ROOT__ = path.join(__dirname, "..");

// Sprint J.10.9 — Bounded Server-Sourced Reconciliation.
//
// Real, executable tests for the new recovery path in
// design-ui/match-service.js's submitCard(): a local NOT_YOUR_TURN
// pre-check failure is no longer unconditionally terminal — it now
// triggers exactly ONE bounded, single-flighted, server-sourced
// (get({source:"server"})) refresh + reconciliation pass through the
// EXISTING applyRemoteCard()/applyRemoteTrick() functions (never a
// listener re-registration, never reconciliation inside a Firestore
// transaction), gated by a mandatory minimum delay
// (SERVER_REFRESH_MIN_DELAY_MS) both before the forced read and
// before the retry — the required mitigation for the near-zero-delay
// stale-read race J.10.8 empirically proved.
//
// LABELING: every check is MOCKED — real design-ui/match-service.js
// and real design-ui/match-adapter.js code, against a hand-written
// fake Firestore that DOES distinguish get() vs get({source:"server"})
// (unlike tests/submit-card.test.cjs's simpler mock) so this file can
// actually prove the forced-refresh path is exercised. Tests A/H/I use
// the REAL design-ui/engine/table-engine.js (not a fake), per J.10.9's
// own "at least one focused test must exercise the real engine"
// requirement — mirroring tests/card-sync.test.cjs's own established
// "require table-engine.js AFTER bidding completes" pattern.

global.window = global;
global.window.addEventListener = function () {};

// ── Fake Firestore, source-aware ────────────────────────────────────
var STORE = {};
var DOC_VERSION = {};
var LISTENERS = {};
var ONSNAPSHOT_CALLS = {};
var GET_CALLS = []; // { key, options }
var idCounter = 0;
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
    get: function (options) {
      GET_CALLS.push({ key: k, options: options || null });
      if (!FIRESTORE_AVAILABLE) { var e = new Error("simulated unavailable"); e.code = "unavailable"; return Promise.reject(e); }
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
    },
    update: function (patch) {
      STORE[k] = Object.assign({}, STORE[k], patch);
      DOC_VERSION[k] = (DOC_VERSION[k] || 0) + 1;
      notify(k);
      return Promise.resolve();
    },
    onSnapshot: function (onNext, onError) {
      ONSNAPSHOT_CALLS[k] = (ONSNAPSHOT_CALLS[k] || 0) + 1;
      LISTENERS[k] = LISTENERS[k] || [];
      LISTENERS[k].push(onNext);
      var exists = Object.prototype.hasOwnProperty.call(STORE, k);
      onNext({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[k]) : undefined; } });
      return function unsubscribe() { LISTENERS[k] = (LISTENERS[k] || []).filter(function (cb) { return cb !== onNext; }); };
    }
  };
}
var FAKE_DB = {
  collection: function (name) {
    if (name !== "matches") throw new Error("unexpected collection " + name);
    return { doc: function (id) { if (!id) id = "match-" + (++idCounter); return makeMatchRef(id); } };
  },
  runTransaction: function (fn, attempt) {
    attempt = attempt || 1;
    if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
    var seenVersions = {}, pending = {};
    var tx = {
      // Real Firestore transactions ALWAYS read genuinely fresh,
      // server-committed state (a hard consistency guarantee, distinct
      // from a plain .get()'s possible cache staleness) -- this mock
      // reflects that by reading STORE directly, bypassing whatever a
      // given ref's own (possibly test-overridden) .get() simulates.
      get: function (ref) {
        seenVersions[ref._key] = DOC_VERSION[ref._key] || 0;
        var exists = Object.prototype.hasOwnProperty.call(STORE, ref._key);
        return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, STORE[ref._key]) : undefined; } });
      },
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

var CURRENT_USER = null;
global.SessionService = {
  getCurrentUser: function () { return CURRENT_USER ? { uid: CURRENT_USER } : null; },
  setCurrentMatchId: function () { return Promise.resolve(); }
};
function signInAs(uid) { CURRENT_USER = uid; }

require(__REPO_ROOT__ + "/design-ui/match-service.js");
require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/engine/bidding-engine.js");
require(__REPO_ROOT__ + "/design-ui/match-adapter.js");
var MatchService = global.MatchService;
var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond, note) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
}

var seats = { p1: "userA", p2: "userB", p3: "userC", p4: "userD" };
/** A genuinely different, but VALID (uidToSeat()-resolvable) seat's
 *  uid, for "wrong turn" test setups -- an unresolvable garbage string
 *  would fall through isLocalSeatsTurn()'s GameSession.getTurn()
 *  fallback and never actually exercise the reconciliation path. */
function otherUid(excludeSeat) {
  return seats[Object.keys(seats).filter(function (s) { return s !== excludeSeat; })[0]];
}
function seedMatch(id, overrides) {
  var m = Object.assign({
    roomId: "r", players: Object.values(seats), status: "playing", createdAt: 1,
    currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: "userA", turn: "userA",
    seats: seats, version: 1, biddingOpen: false, bids: {}, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: "PLAY", biddingLog: [],
    gameState: { initialized: true, dealtRound: 1 }
  }, overrides || {});
  STORE[key(id)] = m;
  DOC_VERSION[key(id)] = 0;
}

/** Drives a real full round through table-engine.js (real bidding +
 *  real card play), matching the pattern j108's own harness used, so
 *  Tests A/H/I exercise the REAL engine, not a fake one. */
function driveRealRoundToPlay(roundNumber) {
  GameSession.reset(null);
  GameSession.setRound({ number: roundNumber });
  BiddingEngine.initState();
  var s = BiddingEngine.getState(); var g = 0;
  while (s.subPhase === "DASH" && g < 10) { BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false }); s = BiddingEngine.getState(); g++; }
  var caller = s.waitingFor;
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: caller, isPass: false, tricks: 6, suit: "SPADES" });
  s = BiddingEngine.getState(); g = 0;
  while (s.subPhase === "AUCTION" && g < 10) { BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true }); s = BiddingEngine.getState(); g++; }
  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: caller, tricks: 6, suit: "SPADES" });
  s = BiddingEngine.getState(); g = 0;
  while (s.subPhase === "ESTIMATES" && g < 10) { BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: s.waitingFor, tricks: 0 }); s = BiddingEngine.getState(); g++; }

  delete require.cache[require.resolve(__REPO_ROOT__ + "/design-ui/engine/table-engine.js")];
  delete require.cache[require.resolve(__REPO_ROOT__ + "/design-ui/match-adapter.js")];
  require(__REPO_ROOT__ + "/design-ui/engine/table-engine.js");
  require(__REPO_ROOT__ + "/design-ui/match-adapter.js");
  global.TableEngine.initState();
  MatchAdapter = global.MatchAdapter;
  return global.TableEngine.getState().turn; // leader seat
}

async function run() {
  console.log("=== Sprint J.10.9: Bounded Server-Sourced Reconciliation ===\n");

  // ══════════════════════════════════════════════════════════════
  // Test A — Stale local turn recovered. Real engine.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(2);
    seedMatch("m-A", { turn: seats[leaderSeat], currentRound: 2, cardLog: [] });
    // Simulate this client's own pre-check `.get()` observing a STALE
    // (previous-round) turn value, while the SERVER-SOURCED doc (what
    // {source:"server"} will return) already correctly reflects the
    // real leader. We fake this by making the FIRST (bare) get() see a
    // stale doc, and any {source:"server"} get() see the true one.
    var trueDoc = STORE[key("m-A")];
    var staleTurnUid = seats[Object.keys(seats).filter(function (s) { return s !== leaderSeat; })[0]];
    var originalGet = FAKE_DB.collection("matches").doc; // not used; patch ref directly below
    var refA = FAKE_DB.collection("matches").doc("m-A");
    var realGet = refA.get.bind(refA);
    refA.get = function (options) {
      GET_CALLS.push({ key: refA._key, options: options || null });
      if (options && options.source === "server") {
        return Promise.resolve({ exists: true, data: function () { return Object.assign({}, trueDoc); } });
      }
      return Promise.resolve({ exists: true, data: function () { return Object.assign({}, trueDoc, { turn: staleTurnUid }); } });
    };
    var origDoc = FAKE_DB.collection("matches").doc;
    FAKE_DB.collection = function (name) { return { doc: function (id) { return id === "m-A" ? refA : makeMatchRef(id); } }; };

    signInAs(seats[leaderSeat]);
    GET_CALLS.length = 0;
    var hand = global.TableEngine.getState().hands[leaderSeat];
    var legalCard = hand.filter(function (c) { return global.TableEngine.canPlayCard(leaderSeat, c).legal; })[0];
    var result = null, errA = null;
    try { result = await MatchService.submitCard("m-A", legalCard); } catch (e) { errA = e; }
    check("A.1: server refresh happened (a {source:'server'} get was issued)", GET_CALLS.some(function (c) { return c.options && c.options.source === "server"; }));
    check("A.2: local state reconciled -- submission ultimately SUCCEEDED, not rejected", errA === null && result && result.matchId === "m-A", errA && errA.message);
    check("A.3: the write actually landed (cardLog grew by 1)", STORE[key("m-A")].cardLog.length === 1);
  }

  // ══════════════════════════════════════════════════════════════
  // Test B — No refresh on healthy local state.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(3);
    FAKE_DB.collection = function (name) { return { doc: function (id) { if (!id) id = "match-" + (++idCounter); return makeMatchRef(id); } }; };
    seedMatch("m-B", { turn: seats[leaderSeat], currentRound: 3, cardLog: [] });
    signInAs(seats[leaderSeat]);
    GET_CALLS.length = 0;
    var hand = global.TableEngine.getState().hands[leaderSeat];
    var legalCard = hand.filter(function (c) { return global.TableEngine.canPlayCard(leaderSeat, c).legal; })[0];
    await MatchService.submitCard("m-B", legalCard);
    check("B.1: healthy local state -- ZERO forced {source:'server'} reads",
      GET_CALLS.filter(function (c) { return c.options && c.options.source === "server"; }).length === 0);
  }

  // ══════════════════════════════════════════════════════════════
  // Test C — Single-flight: N concurrent triggers share ONE refresh.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(4);
    seedMatch("m-C", { turn: otherUid(leaderSeat), currentRound: 4, cardLog: [] });
    signInAs(seats[leaderSeat]);
    GET_CALLS.length = 0;
    var hand = global.TableEngine.getState().hands[leaderSeat];
    var legalCard = hand.filter(function (c) { return global.TableEngine.canPlayCard(leaderSeat, c).legal; })[0];
    var attempts = [
      MatchService.submitCard("m-C", legalCard).catch(function (e) { return e; }),
      MatchService.submitCard("m-C", legalCard).catch(function (e) { return e; }),
      MatchService.submitCard("m-C", legalCard).catch(function (e) { return e; })
    ];
    await Promise.all(attempts);
    var serverReads = GET_CALLS.filter(function (c) { return c.key === "matches/m-C" && c.options && c.options.source === "server"; }).length;
    check("C.1: 3 concurrent triggers on the SAME match collapsed to exactly 1 actual server refresh", serverReads === 1, "got " + serverReads);
  }

  // ══════════════════════════════════════════════════════════════
  // Test D — Rapid tap: measure exact reads for a burst.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(5);
    seedMatch("m-D", { turn: otherUid(leaderSeat), currentRound: 5, cardLog: [] });
    signInAs(seats[leaderSeat]);
    GET_CALLS.length = 0;
    var hand = global.TableEngine.getState().hands[leaderSeat];
    var legalCard = hand.filter(function (c) { return global.TableEngine.canPlayCard(leaderSeat, c).legal; })[0];
    var burst = [];
    for (var i = 0; i < 20; i++) burst.push(MatchService.submitCard("m-D", legalCard).catch(function (e) { return e; }));
    await Promise.all(burst);
    var serverReadsD = GET_CALLS.filter(function (c) { return c.key === "matches/m-D" && c.options && c.options.source === "server"; }).length;
    check("D.1: 20 rapid, synchronous taps on the SAME match -- exactly 1 actual server refresh (single-flight collapse)", serverReadsD === 1, "got " + serverReadsD);
  }

  // ══════════════════════════════════════════════════════════════
  // Test E — Listener leak: no onSnapshot growth across triggers.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(6);
    seedMatch("m-E", { turn: otherUid(leaderSeat), currentRound: 6, cardLog: [] });
    signInAs(seats[leaderSeat]);
    var hand = global.TableEngine.getState().hands[leaderSeat];
    var legalCard = hand.filter(function (c) { return global.TableEngine.canPlayCard(leaderSeat, c).legal; })[0];
    var before = ONSNAPSHOT_CALLS[key("m-E")] || 0;
    var burstE = [];
    for (var i = 0; i < 10; i++) burstE.push(MatchService.submitCard("m-E", legalCard).catch(function (e) { return e; }));
    await Promise.all(burstE);
    var after10 = ONSNAPSHOT_CALLS[key("m-E")] || 0;
    for (var i = 0; i < 90; i++) burstE.push(MatchService.submitCard("m-E", legalCard).catch(function (e) { return e; }));
    await Promise.all(burstE);
    var after100 = ONSNAPSHOT_CALLS[key("m-E")] || 0;
    check("E.1: no onSnapshot listener registrations from the refresh mechanism, after 10 triggers", after10 === before, "before=" + before + " after10=" + after10);
    check("E.2: no onSnapshot listener registrations from the refresh mechanism, after 100 triggers", after100 === before, "before=" + before + " after100=" + after100);
  }

  // ══════════════════════════════════════════════════════════════
  // Test F — Duplicate reconciliation: call recovery twice.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(7);
    seedMatch("m-F", { turn: seats[leaderSeat], currentRound: 7, cardLog: [] });
    var freshDoc = STORE[key("m-F")];
    var r1 = MatchAdapter.applyRemoteCard("m-F", freshDoc);
    var r2 = MatchAdapter.applyRemoteCard("m-F", freshDoc);
    check("F.1: duplicate reconciliation call -- no duplicate application (second call reports NO_NEW_CARDS/DUPLICATE_VERSION, not a fresh apply)",
      r2.applied === false);
    check("F.2: no turn regression -- TableEngine.turn unchanged by the duplicate call", global.TableEngine.getState().turn === leaderSeat);
  }

  // ══════════════════════════════════════════════════════════════
  // Test G — Out-of-order: an older forced-refresh response must
  // never regress state already applied by a newer one.
  // ══════════════════════════════════════════════════════════════
  {
    var seatsArr = Object.keys(seats);
    var docV10 = { version: 10, seats: seats, cardLog: [] };
    var docV11 = { version: 11, seats: seats, cardLog: [] };
    var rNewer = MatchAdapter.applyRemoteCard("m-G", docV11);
    var rOlder = MatchAdapter.applyRemoteCard("m-G", docV10);
    check("G.1: out-of-order (v11 then v10) -- the older response is ignored (STALE_VERSION), never regresses state", rOlder.reason === "STALE_VERSION");
  }

  // ══════════════════════════════════════════════════════════════
  // Test H — RESOLVING: fresh server state lands mid-resolution.
  // Real engine, one turn transition, no duplicate trick.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(8);
    var cardLog = [];
    for (var i = 0; i < 4; i++) {
      var turnSeat = global.TableEngine.getState().turn;
      var hand = global.TableEngine.getState().hands[turnSeat];
      var legal = hand.filter(function (c) { return global.TableEngine.canPlayCard(turnSeat, c).legal; })[0];
      global.TableEngine.emit({ type: "PlayCard", playerId: turnSeat, card: legal, trusted: true });
      cardLog.push({ seatId: turnSeat, card: { suit: legal.suit, rank: { v: legal.rank.v, s: legal.rank.s } }, round: 8 });
    }
    check("H.setup: phase is RESOLVING after the 4th card", global.TableEngine.getState().phase === "RESOLVING");
    var docResolving = { version: 20, seats: seats, cardLog: cardLog };
    var tricksBefore = Object.assign({}, global.TableEngine.getState().tricksWon);
    global.MatchAdapter.applyRemoteCard("m-H", docResolving);
    var trickResult1 = global.MatchAdapter.applyRemoteTrick("m-H", docResolving);
    var trickResult2 = global.MatchAdapter.applyRemoteTrick("m-H", docResolving); // duplicate
    var tricksAfter = global.TableEngine.getState().tricksWon;
    var totalAwarded = Object.keys(tricksAfter).reduce(function (s, k) { return s + (tricksAfter[k] - tricksBefore[k]); }, 0);
    check("H.1: exactly ONE turn transition -- exactly one trick's worth of tricksWon awarded", totalAwarded === 1, "got " + totalAwarded);
    check("H.2: no duplicate trick resolution -- the second applyRemoteTrick() call was a no-op", trickResult1.applied === true && trickResult2.applied === false);
  }

  // ══════════════════════════════════════════════════════════════
  // Test I — Round transition: no old/new round mixing.
  // ══════════════════════════════════════════════════════════════
  {
    driveRealRoundToPlay(9);
    var round10Entry = { seatId: "p1", card: { suit: "HEARTS", rank: { v: 10, s: "10" } }, round: 10 };
    var staleDoc = { version: 500, seats: seats, cardLog: [round10Entry] };
    var deferResult = global.MatchAdapter.applyRemoteCard("m-I", staleDoc);
    check("I.1: a round-10 entry is DEFERRED while local is still round 9 (AWAITING_ROUND_TRANSITION)", deferResult.reason === "AWAITING_ROUND_TRANSITION" && deferResult.desync === false);
    check("I.2: no premature application -- local round unchanged", global.TableEngine.getState().round === 9);
  }

  // ══════════════════════════════════════════════════════════════
  // Test J — Offline: forced refresh fails cleanly, no loop, no leak.
  // ══════════════════════════════════════════════════════════════
  {
    var leaderSeat = driveRealRoundToPlay(10);
    seedMatch("m-J", { turn: otherUid(leaderSeat), currentRound: 10, cardLog: [] });
    var refJ = FAKE_DB.collection("matches").doc("m-J");
    refJ.get = function (options) {
      GET_CALLS.push({ key: refJ._key, options: options || null });
      if (options && options.source === "server") {
        var e = new Error("simulated offline"); e.code = "unavailable";
        return Promise.reject(e);
      }
      return Promise.resolve({ exists: true, data: function () { return Object.assign({}, STORE[key("m-J")]); } });
    };
    FAKE_DB.collection = function (name) { return { doc: function (id) { return id === "m-J" ? refJ : makeMatchRef(id); } }; };
    signInAs(seats[leaderSeat]);
    var hand = global.TableEngine.getState().hands[leaderSeat];
    var legalCard = hand.filter(function (c) { return global.TableEngine.canPlayCard(leaderSeat, c).legal; })[0];
    var errJ = null;
    var settled = await Promise.race([
      MatchService.submitCard("m-J", legalCard).then(function (r) { return { settled: "resolved", r: r }; }).catch(function (e) { return { settled: "rejected", e: e }; }),
      new Promise(function (_, rej) { setTimeout(function () { rej(new Error("TIMEOUT -- promise never settled")); }, 2000); })
    ]);
    check("J.1: offline forced refresh settles (rejects cleanly) rather than hanging forever", settled.settled === "rejected");
    check("J.2: no card written as a result of the failed refresh", STORE[key("m-J")].cardLog.length === 0);
    // A subsequent, independent attempt must not be permanently blocked
    // by a leaked in-flight state from the failed attempt. The server
    // has since genuinely committed the correct turn (STORE itself
    // updated, not just a mock's claim) -- network is back.
    STORE[key("m-J")].turn = seats[leaderSeat];
    refJ.get = function (options) {
      GET_CALLS.push({ key: refJ._key, options: options || null });
      var exists = Object.prototype.hasOwnProperty.call(STORE, key("m-J"));
      return Promise.resolve({ exists: exists, data: function () { return Object.assign({}, STORE[key("m-J")]); } });
    };
    var secondAttempt = null;
    try { secondAttempt = await MatchService.submitCard("m-J", legalCard); } catch (e) { secondAttempt = e; }
    check("J.3: a later, independent attempt is NOT permanently blocked by the earlier failed refresh's in-flight state", secondAttempt && secondAttempt.matchId === "m-J",
      secondAttempt && secondAttempt.message);
  }

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed" + (fail ? " (FAILED)" : ""));
  process.exit(fail ? 1 : 0);
}

run().catch(function (e) { console.error("HARNESS ERROR:", e); process.exit(1); });
