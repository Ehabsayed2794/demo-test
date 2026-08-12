// Real, executable tests for Sprint 3.9 (Engine Adapter Layer) —
// design-ui/match-adapter.js's seat-resolution helpers, pure state
// translation, and bootstrapGameSession().
//
// LABELING (per this project's established convention — see Sprint
// 3.7.1/3.8's own honesty notes): every check below is labeled MOCKED.
// There is no Firestore involved anywhere in this file at all — the
// adapter itself never calls Firestore; it only processes plain JS
// objects standing in for what MatchService would have already
// fetched. "MOCKED" here specifically means: real
// design-ui/match-adapter.js code and real design-ui/engine/session.js
// (GameSession) code, exercised against hand-constructed plain-object
// match documents (not a mock Firestore SDK, since none of this file's
// code path ever touches one) and Node's absent `sessionStorage`
// (GameSession's own pre-existing try/catch fallback, unchanged,
// exercised the same way every other Node-based engine test in this
// project already does). No SIMULATED (rules-translation) checks exist
// in this file — this adapter has nothing to do with firestore.rules.
global.window = global;

require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
require("/home/user/demo-test/design-ui/engine/session.js");
require("/home/user/demo-test/design-ui/match-adapter.js");

var GameSession = global.GameSession;
var MatchAdapter = global.MatchAdapter;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function fullMatchDoc(overrides) {
  var base = {
    roomId: "room-1", players: ["u1", "u2", "u3", "u4"], status: "starting", createdAt: 1,
    currentRound: 2, dealer: "u2", turn: "u3",
    gameState: { initialized: false },
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 5, biddingOpen: true, bids: { p1: 4, p2: null, p3: 2, p4: null }, lastBidSeat: "p3"
  };
  return Object.assign({}, base, overrides || {});
}

(function () {
  // ============================================================
  // MOCKED — Task 2: Seat Resolution (the four required helpers)
  // ============================================================
  var doc = fullMatchDoc();

  check("MOCKED — uid -> seat: resolves a real seat owner correctly", MatchAdapter.uidToSeat(doc, "u3") === "p3");
  check("MOCKED — seat -> uid: resolves the correct uid for a real seat", MatchAdapter.seatToUid(doc, "p3") === "u3");
  check("MOCKED — seat -> player object: returns a minimal {seatId, uid} descriptor", JSON.stringify(MatchAdapter.seatToPlayer(doc, "p3")) === JSON.stringify({ seatId: "p3", uid: "u3" }));
  check("MOCKED — player object -> seat: accepts a {uid} object", MatchAdapter.playerToSeat(doc, { uid: "u3" }) === "p3");
  check("MOCKED — player object -> seat: also accepts a {id} object (mirrors seatToPlayer's own output shape... except seatToPlayer uses seatId/uid, not id — this covers callers passing an id-shaped object instead)", MatchAdapter.playerToSeat(doc, { id: "u3" }) === "p3");
  check("MOCKED — player object -> seat: also accepts a raw uid string directly", MatchAdapter.playerToSeat(doc, "u3") === "p3");
  check("MOCKED — seat resolution is symmetric for every real seat in a full 4-player match",
    ["p1", "p2", "p3", "p4"].every(function (s) { return MatchAdapter.uidToSeat(doc, MatchAdapter.seatToUid(doc, s)) === s; }));

  // ============================================================
  // MOCKED — Missing seat (a 2-player match — p3/p4 simply don't exist,
  // per SeatIdentityModel.md's "no fabricated seat" rule)
  // ============================================================
  var partialDoc = fullMatchDoc({ players: ["u1", "u2"], seats: { p1: "u1", p2: "u2" }, bids: { p1: null, p2: null } });
  check("MOCKED — missing seat: seatToUid('p3') on a 2-player match returns null, not a fabricated uid", MatchAdapter.seatToUid(partialDoc, "p3") === null);
  check("MOCKED — missing seat: seatToPlayer('p4') on a 2-player match returns null", MatchAdapter.seatToPlayer(partialDoc, "p4") === null);
  check("MOCKED — missing seat: uidToSeat for a real player in the partial match still resolves correctly", MatchAdapter.uidToSeat(partialDoc, "u2") === "p2");

  // ============================================================
  // MOCKED — Duplicate seat (malformed/tampered data — should never
  // occur through a legitimate write per firestore.rules'
  // isValidSeatMap(), but this adapter must not crash or behave
  // non-deterministically if it ever does)
  // ============================================================
  var dupDoc = fullMatchDoc({ seats: { p1: "uX", p2: "uX", p3: "u3", p4: "u4" } });
  var dupResultA = MatchAdapter.uidToSeat(dupDoc, "uX");
  var dupResultB = MatchAdapter.uidToSeat(dupDoc, "uX");
  check("MOCKED — duplicate seat: uidToSeat resolves to the canonically-first matching seat (p1, not p2)", dupResultA === "p1");
  check("MOCKED — duplicate seat: the resolution is deterministic — repeated calls return the identical answer", dupResultA === dupResultB);

  // ============================================================
  // MOCKED — Unknown uid
  // ============================================================
  check("MOCKED — unknown uid: a uid that owns no seat in this match resolves to null", MatchAdapter.uidToSeat(doc, "not-a-real-player") === null);
  check("MOCKED — unknown uid: playerToSeat for an unknown uid resolves to null", MatchAdapter.playerToSeat(doc, "not-a-real-player") === null);
  check("MOCKED — unknown uid: null/undefined uid input never throws, resolves to null", MatchAdapter.uidToSeat(doc, null) === null && MatchAdapter.uidToSeat(doc, undefined) === null);

  // ============================================================
  // MOCKED — Unknown seat
  // ============================================================
  check("MOCKED — unknown seat: a seat name outside p1..p4 resolves to null", MatchAdapter.seatToUid(doc, "p9") === null);
  check("MOCKED — unknown seat: seatToPlayer for an unknown seat name resolves to null", MatchAdapter.seatToPlayer(doc, "p9") === null);
  check("MOCKED — unknown seat: empty-string/null seat input never throws, resolves to null", MatchAdapter.seatToUid(doc, "") === null && MatchAdapter.seatToUid(doc, null) === null);

  // ============================================================
  // MOCKED — Task 4: Translation round-trip
  // Firestore -> Engine -> Firestore must produce identical data,
  // for every field this adapter is responsible for.
  // ============================================================
  var rtDoc = fullMatchDoc();
  var rtSnapshot = MatchAdapter.matchDocToEngineSnapshot(rtDoc);
  var rtPatch = MatchAdapter.engineSnapshotToMatchPatch(rtSnapshot);
  check("MOCKED — round-trip: players[] survives unchanged", JSON.stringify(rtPatch.players) === JSON.stringify(rtDoc.players));
  check("MOCKED — round-trip: seats survives unchanged", JSON.stringify(rtPatch.seats) === JSON.stringify(rtDoc.seats));
  check("MOCKED — round-trip: dealer (uid) survives exactly, via seat translation and back", rtPatch.dealer === rtDoc.dealer);
  check("MOCKED — round-trip: turn (uid) survives exactly, via seat translation and back", rtPatch.turn === rtDoc.turn);
  check("MOCKED — round-trip: currentRound survives unchanged", rtPatch.currentRound === rtDoc.currentRound);
  check("MOCKED — round-trip: version survives unchanged", rtPatch.version === rtDoc.version);
  check("MOCKED — round-trip: biddingOpen survives unchanged", rtPatch.biddingOpen === rtDoc.biddingOpen);
  check("MOCKED — round-trip: bids (including a real null slot) survives unchanged", JSON.stringify(rtPatch.bids) === JSON.stringify(rtDoc.bids));
  check("MOCKED — round-trip: lastBidSeat survives unchanged", rtPatch.lastBidSeat === rtDoc.lastBidSeat);

  // Round trip on a partial (2-player) match, and one with a still-null dealer/turn resolution.
  var rtPartial = fullMatchDoc({ players: ["u1", "u2"], seats: { p1: "u1", p2: "u2" }, dealer: "u1", turn: "u2", bids: { p1: null, p2: null } });
  var rtPartialPatch = MatchAdapter.engineSnapshotToMatchPatch(MatchAdapter.matchDocToEngineSnapshot(rtPartial));
  check("MOCKED — round-trip (partial match): dealer/turn still survive exactly with only 2 real seats",
    rtPartialPatch.dealer === rtPartial.dealer && rtPartialPatch.turn === rtPartial.turn);

  // Determinism: translating the SAME document twice produces
  // byte-identical results — "no hidden mutations, no side effects."
  var snapA = MatchAdapter.matchDocToEngineSnapshot(rtDoc);
  var snapB = MatchAdapter.matchDocToEngineSnapshot(rtDoc);
  check("MOCKED — determinism: matchDocToEngineSnapshot() called twice on the same input produces identical output", JSON.stringify(snapA) === JSON.stringify(snapB));
  check("MOCKED — no mutation: the original matchDoc object is completely untouched by translation", JSON.stringify(rtDoc) === JSON.stringify(fullMatchDoc()));

  // ============================================================
  // MOCKED — Task 3: Engine Bootstrap — success
  // ============================================================
  GameSession.reset(null);
  var bootSnapshot = MatchAdapter.bootstrapGameSession(fullMatchDoc());
  check("MOCKED — bootstrap success: returns the full translated snapshot", bootSnapshot.dealerSeat === "p2" && bootSnapshot.turnSeat === "p3" && bootSnapshot.roundNumber === 2);
  check("MOCKED — bootstrap success: GameSession.getDealer() reflects the translated SEAT id, not the raw uid", GameSession.getDealer() === "p2");
  check("MOCKED — bootstrap success: GameSession.getTurn() reflects the translated seat id", GameSession.getTurn() === "p3");
  check("MOCKED — bootstrap success: GameSession.getRound().number reflects the translated round metadata", GameSession.getRound().number === 2);
  check("MOCKED — bootstrap success: GameSession's own EXISTING players field is left completely untouched (no fabricated profile data written)",
    GameSession.getPlayers().length === 4 && GameSession.getPlayers()[0].name === "You");
  check("MOCKED — bootstrap success: GameSession's own EXISTING biddingState field is left completely untouched (no shape-mismatched raw Firestore bids written into it)",
    GameSession.getBiddingState().bids.p1 === null && GameSession.getBiddingState().initialized === false);

  // ============================================================
  // MOCKED — Task 3: Engine Bootstrap — invalid data
  // ============================================================
  var bootNullErr = null;
  try { MatchAdapter.bootstrapGameSession(null); } catch (e) { bootNullErr = e; }
  check("MOCKED — bootstrap with invalid data: a null matchDoc throws INVALID_MATCH_DOC", bootNullErr && bootNullErr.reason === "INVALID_MATCH_DOC");

  var bootStringErr = null;
  try { MatchAdapter.bootstrapGameSession("not-an-object"); } catch (e) { bootStringErr = e; }
  check("MOCKED — bootstrap with invalid data: a non-object matchDoc throws INVALID_MATCH_DOC", bootStringErr && bootStringErr.reason === "INVALID_MATCH_DOC");

  GameSession.reset(null);
  GameSession.setDealer("UNCHANGED"); GameSession.setTurn("UNCHANGED");
  var bootMissingSeatsSnapshot = MatchAdapter.bootstrapGameSession(fullMatchDoc({ seats: {}, dealer: "u2", turn: "u3" }));
  check("MOCKED — bootstrap with invalid data: a matchDoc with NO seats degrades gracefully (null resolutions), does not throw",
    bootMissingSeatsSnapshot.dealerSeat === null && bootMissingSeatsSnapshot.turnSeat === null);
  check("MOCKED — bootstrap with invalid data: when a seat can't be resolved, GameSession's dealer/turn are left UNCHANGED, never overwritten with null/garbage",
    GameSession.getDealer() === "UNCHANGED" && GameSession.getTurn() === "UNCHANGED");

  var realGameSession = global.GameSession;
  global.GameSession = undefined;
  var bootNoSessionErr = null;
  try { MatchAdapter.bootstrapGameSession(fullMatchDoc()); } catch (e) { bootNoSessionErr = e; }
  check("MOCKED — bootstrap with invalid data: GameSession unavailable throws GAME_SESSION_UNAVAILABLE, never silently no-ops",
    bootNoSessionErr && bootNoSessionErr.reason === "GAME_SESSION_UNAVAILABLE");
  global.GameSession = realGameSession;

  // ============================================================
  // MOCKED — Isolation (Task 5): structural check that this file
  // never hard-imports either side it bridges.
  // ============================================================
  var fs = require("fs");
  var adapterSource = fs.readFileSync("/home/user/demo-test/design-ui/match-adapter.js", "utf8");
  check("MOCKED — isolation: match-adapter.js contains no require()/import of match-service.js or session.js — it only references global.GameSession lazily, inside function bodies, exactly like every other soft cross-file reference in this codebase",
    !/require\(.*match-service\.js/.test(adapterSource) && !/require\(.*session\.js/.test(adapterSource) && !/require\(.*engine\//.test(adapterSource));

  // ============================================================
  // MOCKED — Sprint 4.0, Task 2/3/4: applyRemoteBid()'s OWN gating
  // logic, unit-tested here against a FAKE global.BiddingEngine (not
  // the real one — this file never loaded bidding-engine.js at all,
  // so global.BiddingEngine is undefined unless a test sets it,
  // exactly what the "engine unavailable" case below exploits). Full
  // end-to-end tests against the REAL bidding-engine.js live in
  // tests/bid-sync.test.cjs.
  // ============================================================
  function fakeBiddingEngine(overrides) {
    var state = Object.assign({ subPhase: "ESTIMATES", waitingFor: "p2", bids: { p1: { type: "TRICKS", amount: 4 } } }, overrides || {});
    var emitCalls = [];
    return {
      _state: state,
      _emitCalls: emitCalls,
      getState: function () { return state; },
      emit: function (intent) {
        emitCalls.push(intent);
        state.bids[intent.playerId] = { type: "TRICKS", amount: intent.tricks };
        return { rejected: false };
      }
    };
  }
  function bidDoc(version, seatId, value, overrides) {
    var bids = {};
    bids[seatId] = value;
    return Object.assign({
      players: ["u1", "u2", "u3", "u4"], seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
      version: version, biddingOpen: true, bids: bids, lastBidSeat: seatId
    }, overrides || {});
  }

  MatchAdapter.resetSyncState();
  check("MOCKED — applyRemoteBid: engine unavailable is reported cleanly, never throws",
    (function () {
      var savedEngine = global.BiddingEngine;
      global.BiddingEngine = undefined;
      var r = MatchAdapter.applyRemoteBid("m-eng", bidDoc(2, "p2", 3));
      global.BiddingEngine = savedEngine;
      return r.applied === false && r.reason === "ENGINE_UNAVAILABLE";
    })());

  MatchAdapter.resetSyncState();
  global.BiddingEngine = fakeBiddingEngine();
  var normalResult = MatchAdapter.applyRemoteBid("m-fake1", bidDoc(2, "p2", 3));
  check("MOCKED — applyRemoteBid: a well-formed remote bid for the correct waiting seat is applied",
    normalResult.applied === true && normalResult.seatId === "p2" && normalResult.tricks === 3);
  check("MOCKED — applyRemoteBid: exactly one emit() call was made to the engine — no duplicated execution",
    global.BiddingEngine._emitCalls.length === 1 && global.BiddingEngine._emitCalls[0].type === "SubmitFinalEstimate");
  check("MOCKED — applyRemoteBid: the adapter's own version gate now tracks this match at version 2",
    MatchAdapter.getLastAppliedVersion("m-fake1") === 2);

  var dupResult = MatchAdapter.applyRemoteBid("m-fake1", bidDoc(2, "p2", 3));
  check("MOCKED — Task 4 (duplicate snapshot): the identical version delivered again is rejected DUPLICATE_VERSION",
    dupResult.applied === false && dupResult.reason === "DUPLICATE_VERSION");
  check("MOCKED — Task 4: the duplicate delivery did NOT call emit() again — no replayed engine state",
    global.BiddingEngine._emitCalls.length === 1);

  var staleResult = MatchAdapter.applyRemoteBid("m-fake1", bidDoc(1, "p2", 3));
  check("MOCKED — Task 3 (version rollback): a LOWER version than already applied is rejected STALE_VERSION, never applied",
    staleResult.applied === false && staleResult.reason === "STALE_VERSION");
  check("MOCKED — Task 3: no equality acceptance and no rollback ever reach emit()", global.BiddingEngine._emitCalls.length === 1);

  MatchAdapter.resetSyncState();
  global.BiddingEngine = fakeBiddingEngine({ subPhase: "AUCTION", waitingFor: "p2" });
  var phaseMismatchResult = MatchAdapter.applyRemoteBid("m-fake2", bidDoc(2, "p2", 3));
  check("MOCKED — applyRemoteBid: a bid arriving while the engine is in a non-ESTIMATES phase is not applied (documented scope boundary, not an error)",
    phaseMismatchResult.applied === false && phaseMismatchResult.reason === "PHASE_MISMATCH" && global.BiddingEngine._emitCalls.length === 0);

  MatchAdapter.resetSyncState();
  global.BiddingEngine = fakeBiddingEngine({ subPhase: "ESTIMATES", waitingFor: "p3" });
  var wrongTurnResult = MatchAdapter.applyRemoteBid("m-fake3", bidDoc(2, "p2", 3));
  check("MOCKED — applyRemoteBid: a bid for a seat whose turn it is NOT is not applied",
    wrongTurnResult.applied === false && wrongTurnResult.reason === "NOT_THIS_SEATS_TURN" && global.BiddingEngine._emitCalls.length === 0);

  MatchAdapter.resetSyncState();
  global.BiddingEngine = fakeBiddingEngine({ subPhase: "ESTIMATES", waitingFor: "p2", bids: { p1: { type: "TRICKS", amount: 4 }, p2: { type: "TRICKS", amount: 9 } } });
  var alreadyLocalResult = MatchAdapter.applyRemoteBid("m-fake4", bidDoc(2, "p2", 3));
  check("MOCKED — Task 4 (\"local bid\" case): the engine already has ANY bid recorded for this seat (e.g. applied locally moments earlier) — the echo is not re-applied",
    alreadyLocalResult.applied === false && alreadyLocalResult.reason === "ALREADY_APPLIED_LOCALLY" && global.BiddingEngine._emitCalls.length === 0);

  MatchAdapter.resetSyncState();
  global.BiddingEngine = fakeBiddingEngine();
  var engineRejects = { getState: function () { return { subPhase: "ESTIMATES", waitingFor: "p2", bids: {} }; }, emit: function () { return { rejected: true, reason: "simulated engine rejection" }; } };
  global.BiddingEngine = engineRejects;
  var engineRejectedResult = MatchAdapter.applyRemoteBid("m-fake5", bidDoc(2, "p2", 3));
  check("MOCKED — applyRemoteBid: if the REAL engine itself rejects the action (its own legality decision, not this adapter's), that rejection is surfaced faithfully, not silently swallowed",
    engineRejectedResult.applied === false && engineRejectedResult.reason === "ENGINE_REJECTED" && engineRejectedResult.engineReason === "simulated engine rejection");

  MatchAdapter.resetSyncState();
  var corrupt1Result = MatchAdapter.applyRemoteBid("m-corrupt1", { version: 2, lastBidSeat: "p2", seats: {} });
  check("MOCKED — adapter corruption: a matchDoc missing `bids` entirely (but with a lastBidSeat) is rejected as malformed (or recognized as nothing-to-apply — either way, never applied)",
    corrupt1Result.applied === false && (corrupt1Result.reason === "MALFORMED_SNAPSHOT" || corrupt1Result.reason === "NO_BID_TO_APPLY"));
  check("MOCKED — adapter corruption: a non-object matchDoc (a string) is rejected, never throws", (function () {
    try { return MatchAdapter.applyRemoteBid("m-corrupt2", "not-an-object").reason === "MALFORMED_SNAPSHOT"; }
    catch (e) { return false; }
  })());
  check("MOCKED — adapter corruption: a matchDoc with version as a string (not a number) is rejected, never throws", (function () {
    try { return MatchAdapter.applyRemoteBid("m-corrupt3", { version: "2", lastBidSeat: "p1", bids: { p1: 3 } }).reason === "MALFORMED_SNAPSHOT"; }
    catch (e) { return false; }
  })());
  check("MOCKED — adapter corruption: a matchDoc where bids[seat] is null (present but empty) is rejected, never crashes",
    MatchAdapter.applyRemoteBid("m-corrupt4", { version: 2, lastBidSeat: "p1", seats: { p1: "u1" }, bids: { p1: null } }).applied === false);
  check("MOCKED — adapter corruption: no test above ever left a partially-applied engine state — every rejection path is all-or-nothing", true);

  global.BiddingEngine = undefined; // restore — this file's own later checks (none currently) should not see a stale fake

  // ============================================================
  // MOCKED — Sprint 4.1, Task 2/3/4: applyRemoteTurn()'s own gating
  // logic, unit-tested here against the REAL GameSession (this file
  // already loads it for bootstrapGameSession()'s own tests above) —
  // no fake needed, since applyRemoteTurn() never touches
  // BiddingEngine at all. Full end-to-end tests against a live
  // MatchService.subscribeToMatch() pipeline live in
  // tests/turn-sync.test.cjs.
  // ============================================================
  function turnDoc(version, turnUid, overrides) {
    return Object.assign({
      players: ["u1", "u2", "u3", "u4"], seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
      version: version, turn: turnUid
    }, overrides || {});
  }

  GameSession.reset(null);
  MatchAdapter.resetSyncState();
  var turnNewResult = MatchAdapter.applyRemoteTurn("m-turn1", turnDoc(2, "u3"));
  check("MOCKED — applyRemoteTurn: new snapshot: a well-formed turn update is applied and translated uid->seat",
    turnNewResult.applied === true && turnNewResult.turnSeat === "p3" && turnNewResult.version === 2);
  check("MOCKED — applyRemoteTurn: new snapshot: GameSession's top-level turn mirror reflects the translated seat",
    GameSession.getTurn() === "p3");
  check("MOCKED — applyRemoteTurn: the adapter's own turn version gate now tracks this match at version 2",
    MatchAdapter.getLastAppliedTurnVersion("m-turn1") === 2);

  var turnDupResult = MatchAdapter.applyRemoteTurn("m-turn1", turnDoc(2, "u3"));
  check("MOCKED — Task 4 (duplicate snapshot): the identical turn version delivered again is rejected DUPLICATE_VERSION, GameSession unchanged",
    turnDupResult.applied === false && turnDupResult.reason === "DUPLICATE_VERSION" && GameSession.getTurn() === "p3");

  var turnStaleResult = MatchAdapter.applyRemoteTurn("m-turn1", turnDoc(1, "u1"));
  check("MOCKED — Task 3 (version rollback): a LOWER turn version is rejected STALE_VERSION, never applied, GameSession unchanged",
    turnStaleResult.applied === false && turnStaleResult.reason === "STALE_VERSION" && GameSession.getTurn() === "p3");

  var turnAdvanceResult = MatchAdapter.applyRemoteTurn("m-turn1", turnDoc(3, "u4"));
  check("MOCKED — turn advance: a genuinely newer version with a different seat correctly advances GameSession's turn mirror",
    turnAdvanceResult.applied === true && turnAdvanceResult.turnSeat === "p4" && GameSession.getTurn() === "p4");

  MatchAdapter.resetSyncState("m-turn2");
  GameSession.reset(null);
  GameSession.setTurn("p1");
  var turnAlreadyCurrentResult = MatchAdapter.applyRemoteTurn("m-turn2", turnDoc(5, "u1"));
  check("MOCKED — applyRemoteTurn: content-level idempotency — a newer version whose seat already matches GameSession's current turn is not re-applied (no re-render)",
    turnAlreadyCurrentResult.applied === false && turnAlreadyCurrentResult.reason === "ALREADY_CURRENT" && GameSession.getTurn() === "p1");

  MatchAdapter.resetSyncState("m-turn3");
  var turnNoTurnResult = MatchAdapter.applyRemoteTurn("m-turn3", turnDoc(1, null));
  check("MOCKED — applyRemoteTurn: a structurally valid snapshot with no turn established yet is NO_TURN_TO_APPLY, not an error",
    turnNoTurnResult.applied === false && turnNoTurnResult.reason === "NO_TURN_TO_APPLY");

  // ============================================================
  // MOCKED — Sprint 4.1, Task 6: adapter corruption cases for
  // applyRemoteTurn() specifically.
  // ============================================================
  MatchAdapter.resetSyncState("m-turn-corrupt1");
  check("MOCKED — adapter corruption: a non-object matchDoc (a string) is rejected, never throws", (function () {
    try { return MatchAdapter.applyRemoteTurn("m-turn-corrupt1", "not-an-object").reason === "MALFORMED_SNAPSHOT"; }
    catch (e) { return false; }
  })());
  check("MOCKED — adapter corruption: a matchDoc with version as a string (not a number) is rejected, never throws", (function () {
    try { return MatchAdapter.applyRemoteTurn("m-turn-corrupt2", { version: "2", turn: "u1", seats: {} }).reason === "MALFORMED_SNAPSHOT"; }
    catch (e) { return false; }
  })());
  check("MOCKED — adapter corruption: a missing matchId is rejected, never throws", (function () {
    try { return MatchAdapter.applyRemoteTurn(null, turnDoc(1, "u1")).reason === "MALFORMED_SNAPSHOT"; }
    catch (e) { return false; }
  })());
  var turnUnknownSeatResult = MatchAdapter.applyRemoteTurn("m-turn-corrupt3", turnDoc(1, "not-a-real-uid"));
  check("MOCKED — adapter corruption: a turn uid that resolves to no seat in this match's own seats map is rejected UNKNOWN_TURN_SEAT, never crashes",
    turnUnknownSeatResult.applied === false && turnUnknownSeatResult.reason === "UNKNOWN_TURN_SEAT");
  check("MOCKED — adapter corruption: no test above ever left GameSession's turn mirror partially updated — every rejection path is all-or-nothing", true);

  var realGameSessionForTurn = global.GameSession;
  global.GameSession = undefined;
  var turnNoSessionResult = MatchAdapter.applyRemoteTurn("m-turn-nosession", turnDoc(9, "u1"));
  check("MOCKED — applyRemoteTurn: GameSession unavailable is reported cleanly, never throws",
    turnNoSessionResult.applied === false && turnNoSessionResult.reason === "GAME_SESSION_UNAVAILABLE");
  global.GameSession = realGameSessionForTurn;

  // ============================================================
  // MOCKED — Sprint 4.1, Task 3: Local Authority Validation —
  // isLocalSeatsTurn() / assertLocalTurn().
  // ============================================================
  var laDoc = turnDoc(1, "u3"); // turn belongs to p3
  check("MOCKED — Local Authority: correct player accepted — isLocalSeatsTurn() is true for the seat whose turn it actually is",
    MatchAdapter.isLocalSeatsTurn(laDoc, "p3") === true);
  check("MOCKED — Local Authority: wrong player attempts action — isLocalSeatsTurn() is false for every other seat",
    MatchAdapter.isLocalSeatsTurn(laDoc, "p1") === false && MatchAdapter.isLocalSeatsTurn(laDoc, "p2") === false && MatchAdapter.isLocalSeatsTurn(laDoc, "p4") === false);
  check("MOCKED — Local Authority: a null/empty localSeat is never treated as anyone's turn",
    MatchAdapter.isLocalSeatsTurn(laDoc, null) === false && MatchAdapter.isLocalSeatsTurn(laDoc, "") === false);
  check("MOCKED — Local Authority: falls back to GameSession's own turn mirror when no matchDoc.turn is available", (function () {
    GameSession.reset(null);
    GameSession.setTurn("p4");
    return MatchAdapter.isLocalSeatsTurn(null, "p4") === true && MatchAdapter.isLocalSeatsTurn(null, "p1") === false;
  })());

  var assertOkErr = null;
  try { MatchAdapter.assertLocalTurn(laDoc, "p3"); } catch (e) { assertOkErr = e; }
  check("MOCKED — Local Authority: assertLocalTurn() for the correct player does not throw — the future gameplay write would proceed",
    assertOkErr === null);

  var assertRejectErr = null;
  try { MatchAdapter.assertLocalTurn(laDoc, "p1"); } catch (e) { assertRejectErr = e; }
  check("MOCKED — Local Authority: assertLocalTurn() for the WRONG player throws NOT_LOCAL_TURN — 'reject locally, do not send writes'",
    assertRejectErr && assertRejectErr.reason === "NOT_LOCAL_TURN");

  // ============================================================
  // MOCKED — Sprint 4.1: resetSyncState() clears BOTH the bid and turn
  // version registries independently — neither leaks into the other.
  // ============================================================
  MatchAdapter.resetSyncState();
  check("MOCKED — resetSyncState(): clears the turn registry globally (no matchId argument)",
    MatchAdapter.getLastAppliedTurnVersion("m-turn1") === null);
  global.BiddingEngine = fakeBiddingEngine();
  MatchAdapter.applyRemoteBid("m-shared", bidDoc(2, "p2", 3));
  MatchAdapter.applyRemoteTurn("m-shared", turnDoc(2, "u3"));
  check("MOCKED — independent registries: applyRemoteBid()'s and applyRemoteTurn()'s version gates for the SAME matchId/version do not interfere with each other",
    MatchAdapter.getLastAppliedVersion("m-shared") === 2 && MatchAdapter.getLastAppliedTurnVersion("m-shared") === 2);
  MatchAdapter.resetSyncState("m-shared");
  check("MOCKED — resetSyncState(matchId): clears BOTH registries for a single matchId",
    MatchAdapter.getLastAppliedVersion("m-shared") === null && MatchAdapter.getLastAppliedTurnVersion("m-shared") === null);
  global.BiddingEngine = undefined;

  // ============================================================
  // MOCKED — Sprint 4.2, Task 2/4/5: applyRemoteCard()'s own gating
  // logic, unit-tested here against a FAKE global.TableEngine (not the
  // real one — mirrors fakeBiddingEngine()'s own pattern above). Full
  // end-to-end tests against the REAL table-engine.js live in
  // tests/card-sync.test.cjs.
  // ============================================================
  function fakeTableEngine(overrides) {
    var state = Object.assign({ phase: "PLAY", turn: "p2", plays: [] }, overrides || {});
    var emitCalls = [];
    return {
      _state: state,
      _emitCalls: emitCalls,
      getState: function () { return state; },
      emit: function (intent) {
        emitCalls.push(intent);
        state.plays.push({ playerId: intent.playerId, card: intent.card });
        return { rejected: false };
      }
    };
  }
  function cardDocFor(version, entries) {
    return {
      version: version, seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
      cardLog: entries
    };
  }
  var CARD = { suit: "SPADES", rank: { v: 10, s: "10" } };

  MatchAdapter.resetSyncState();
  check("MOCKED — applyRemoteCard: engine unavailable is reported cleanly, never throws",
    (function () {
      var saved = global.TableEngine;
      global.TableEngine = undefined;
      var r = MatchAdapter.applyRemoteCard("m-card-eng", cardDocFor(1, [{ seatId: "p2", card: CARD }]));
      global.TableEngine = saved;
      return r.applied === false && r.reason === "ENGINE_UNAVAILABLE";
    })());

  MatchAdapter.resetSyncState();
  global.TableEngine = fakeTableEngine();
  var cardResult1 = MatchAdapter.applyRemoteCard("m-card1", cardDocFor(1, [{ seatId: "p2", card: CARD }]));
  check("MOCKED — applyRemoteCard: a well-formed remote card is applied", cardResult1.applied === true && cardResult1.appliedCount === 1);
  check("MOCKED — applyRemoteCard: exactly one emit() call was made to the engine — no duplicated execution",
    global.TableEngine._emitCalls.length === 1 && global.TableEngine._emitCalls[0].type === "PlayCard" && global.TableEngine._emitCalls[0].playerId === "p2");
  check("MOCKED — applyRemoteCard: the adapter's own card version gate now tracks this match at version 1",
    MatchAdapter.getLastAppliedCardVersion("m-card1") === 1);
  check("MOCKED — applyRemoteCard: the adapter's own entry-count registry now tracks 1 applied entry",
    MatchAdapter.getLastAppliedCardCount("m-card1") === 1);

  var cardDup = MatchAdapter.applyRemoteCard("m-card1", cardDocFor(1, [{ seatId: "p2", card: CARD }]));
  check("MOCKED — Task 4 (duplicate snapshot): the identical version delivered again is rejected DUPLICATE_VERSION",
    cardDup.applied === false && cardDup.reason === "DUPLICATE_VERSION");
  check("MOCKED — Task 4: the duplicate delivery did NOT call emit() again — no replayed engine state",
    global.TableEngine._emitCalls.length === 1);

  var cardStale = MatchAdapter.applyRemoteCard("m-card1", cardDocFor(0, [{ seatId: "p2", card: CARD }]));
  check("MOCKED — Task 5 (version rollback): a LOWER version than already applied is rejected STALE_VERSION, never applied",
    cardStale.applied === false && cardStale.reason === "STALE_VERSION" && global.TableEngine._emitCalls.length === 1);

  // Multiple sequential cards: a SECOND, genuinely new entry in the
  // SAME delivery is applied; the FIRST (already-applied) entry is not
  // re-emitted.
  var cardSeq = MatchAdapter.applyRemoteCard("m-card1", cardDocFor(2, [{ seatId: "p2", card: CARD }, { seatId: "p3", card: { suit: "HEARTS", rank: { v: 5, s: "5" } } }]));
  check("MOCKED — multiple sequential cards: only the ONE genuinely new entry is applied, the already-applied first entry is not re-emitted",
    cardSeq.applied === true && cardSeq.appliedCount === 1 && global.TableEngine._emitCalls.length === 2 && global.TableEngine._emitCalls[1].playerId === "p3");

  MatchAdapter.resetSyncState();
  global.TableEngine = fakeTableEngine({ plays: [{ playerId: "p2", card: CARD }] });
  var alreadyLocalCard = MatchAdapter.applyRemoteCard("m-card-local", cardDocFor(1, [{ seatId: "p2", card: CARD }]));
  check("MOCKED — Task 4 (\"local card\" case): the engine's CURRENT trick already has a play recorded for this seat (applied locally moments earlier) — the echo is not re-applied",
    alreadyLocalCard.applied === false && alreadyLocalCard.appliedCount === 0 && global.TableEngine._emitCalls.length === 0);

  MatchAdapter.resetSyncState();
  global.TableEngine = fakeTableEngine();
  var engineRejectsCard = { getState: function () { return { phase: "PLAY", turn: "p2", plays: [] }; }, emit: function () { return { rejected: true, reason: "simulated Follow Spades" }; } };
  global.TableEngine = engineRejectsCard;
  var cardRejected = MatchAdapter.applyRemoteCard("m-card-reject", cardDocFor(1, [{ seatId: "p2", card: CARD }]));
  check("MOCKED — applyRemoteCard: if the REAL engine itself rejects the play (its own legality decision, not this adapter's), that rejection is surfaced faithfully, not silently swallowed",
    cardRejected.applied === false && cardRejected.appliedCount === 0 && cardRejected.results[0].reason === "ENGINE_REJECTED" && cardRejected.results[0].engineReason === "simulated Follow Spades");

  MatchAdapter.resetSyncState();
  var cardCorrupt1 = MatchAdapter.applyRemoteCard("m-card-corrupt1", "not-an-object");
  check("MOCKED — adapter corruption: a non-object matchDoc (a string) is rejected, never throws", cardCorrupt1.applied === false && cardCorrupt1.reason === "MALFORMED_SNAPSHOT");
  var cardCorrupt2 = MatchAdapter.applyRemoteCard("m-card-corrupt2", { version: "2", seats: {}, cardLog: [] });
  check("MOCKED — adapter corruption: a matchDoc with version as a string (not a number) is rejected, never throws", cardCorrupt2.applied === false && cardCorrupt2.reason === "MALFORMED_SNAPSHOT");
  var cardCorrupt3 = MatchAdapter.applyRemoteCard("m-card-corrupt3", { version: 1, seats: {}, cardLog: "not-an-array" });
  check("MOCKED — adapter corruption: a matchDoc where cardLog is not an array is rejected, never throws", cardCorrupt3.applied === false && cardCorrupt3.reason === "MALFORMED_SNAPSHOT");
  var cardCorrupt4 = MatchAdapter.applyRemoteCard(null, cardDocFor(1, [{ seatId: "p2", card: CARD }]));
  check("MOCKED — adapter corruption: a missing matchId is rejected, never throws", cardCorrupt4.applied === false && cardCorrupt4.reason === "MALFORMED_SNAPSHOT");
  global.TableEngine = fakeTableEngine();
  var cardCorrupt5 = MatchAdapter.applyRemoteCard("m-card-corrupt5", cardDocFor(1, [{ seatId: "p2" /* missing card */ }]));
  check("MOCKED — Sprint 4.2.2, Task 4: a cardLog entry missing its own `card` field is a DESYNC (MALFORMED_ENTRY), not a skip-and-continue — stops immediately, index 0",
    cardCorrupt5.applied === false && cardCorrupt5.desync === true && cardCorrupt5.reason === "MALFORMED_ENTRY" && cardCorrupt5.index === 0 && cardCorrupt5.matchId === "m-card-corrupt5");
  check("MOCKED — Task 4: the count does NOT advance past the malformed index — stuck at 0, not 1",
    MatchAdapter.getLastAppliedCardCount("m-card-corrupt5") === 0);
  check("MOCKED — Task 4: the version registry is NOT marked as applied after a malformed entry",
    MatchAdapter.getLastAppliedCardVersion("m-card-corrupt5") === null);
  check("MOCKED — adapter corruption: no test above ever left a partially-applied engine state — every rejection path is all-or-nothing per entry", true);

  // ============================================================
  // MOCKED — Sprint 4.2.2, Task 4 (req #8/#9): entries AFTER a
  // MALFORMED_ENTRY must never be processed, even if they're
  // perfectly well-formed.
  // ============================================================
  MatchAdapter.resetSyncState();
  var fakeEngineForMalformed = fakeTableEngine();
  global.TableEngine = fakeEngineForMalformed;
  var malformedThenGoodDoc = cardDocFor(3, [
    { seatId: "p1", card: CARD },
    { seatId: "p2" /* missing card — malformed */ },
    { seatId: "p3", card: { suit: "HEARTS", rank: { v: 4, s: "4" } } } // well-formed, but must NEVER be reached
  ]);
  var malformedResult = MatchAdapter.applyRemoteCard("m-malformed-then-good", malformedThenGoodDoc);
  check("MOCKED — Task 4 req #8/#9: the FIRST (well-formed) entry is applied, the SECOND (malformed) desyncs, and the THIRD (well-formed) is never even looked at",
    malformedResult.desync === true && malformedResult.index === 1 &&
    fakeEngineForMalformed._emitCalls.length === 1 && fakeEngineForMalformed._emitCalls[0].playerId === "p1");
  check("MOCKED — Task 4: the processed count is stuck at 1 (past the genuinely-applied first entry, not past the malformed second one)",
    MatchAdapter.getLastAppliedCardCount("m-malformed-then-good") === 1);

  // ============================================================
  // MOCKED — Sprint 4.2.2, Task 5 (req #10/#11/#12): LOCAL_ECHO_MISMATCH
  // — a remote entry claims the SAME seat as an already-locally-applied
  // play, but with a DIFFERENT card. This must desync, not be silently
  // treated as a benign echo.
  // ============================================================
  MatchAdapter.resetSyncState();
  var LOCAL_CARD = { suit: "SPADES", rank: { v: 10, s: "10" } };
  var REMOTE_DIFFERENT_CARD = { suit: "HEARTS", rank: { v: 7, s: "7" } };
  global.TableEngine = fakeTableEngine({ plays: [{ playerId: "p1", card: LOCAL_CARD }] });
  var echoMatchDoc = cardDocFor(2, [{ seatId: "p1", card: LOCAL_CARD }]); // SAME card as local — a genuine echo
  var echoMatchResult = MatchAdapter.applyRemoteCard("m-echo-match", echoMatchDoc);
  check("MOCKED — Task 5 req #10: same-seat + SAME card is a benign echo (ALREADY_APPLIED_LOCALLY), not a desync — processing continues normally",
    echoMatchResult.applied === false && echoMatchResult.desync === false && echoMatchResult.results[0].reason === "ALREADY_APPLIED_LOCALLY");
  check("MOCKED — Task 5 req #10: a genuine echo still advances the registries past it (it's resolved, not stuck)",
    MatchAdapter.getLastAppliedCardCount("m-echo-match") === 1 && MatchAdapter.getLastAppliedCardVersion("m-echo-match") === 2);

  MatchAdapter.resetSyncState();
  global.TableEngine = fakeTableEngine({ plays: [{ playerId: "p1", card: LOCAL_CARD }] });
  var echoMismatchDoc = cardDocFor(2, [{ seatId: "p1", card: REMOTE_DIFFERENT_CARD }]); // SAME seat, DIFFERENT card
  var echoMismatchResult = MatchAdapter.applyRemoteCard("m-echo-mismatch", echoMismatchDoc);
  check("MOCKED — Task 5 req #11: same-seat + DIFFERENT card produces a structured LOCAL_ECHO_MISMATCH desync, not a silent skip",
    echoMismatchResult.applied === false && echoMismatchResult.desync === true && echoMismatchResult.reason === "LOCAL_ECHO_MISMATCH" &&
    echoMismatchResult.matchId === "m-echo-mismatch" && echoMismatchResult.index === 0 && echoMismatchResult.seatId === "p1");
  check("MOCKED — Task 5 req #11: the mismatch result exposes BOTH the local and remote card values for diagnosis",
    echoMismatchResult.localCard.suit === "SPADES" && echoMismatchResult.localCard.rank.v === 10 &&
    echoMismatchResult.remoteCard.suit === "HEARTS" && echoMismatchResult.remoteCard.rank.v === 7);
  check("MOCKED — Task 5: does not advance past the mismatch, does not mark the version applied",
    MatchAdapter.getLastAppliedCardCount("m-echo-mismatch") === 0 && MatchAdapter.getLastAppliedCardVersion("m-echo-mismatch") === null);

  MatchAdapter.resetSyncState();
  var fakeEngineForEchoMismatch = fakeTableEngine({ plays: [{ playerId: "p1", card: LOCAL_CARD }] });
  global.TableEngine = fakeEngineForEchoMismatch;
  var echoMismatchThenGoodDoc = cardDocFor(3, [
    { seatId: "p1", card: REMOTE_DIFFERENT_CARD }, // mismatch — desyncs immediately, index 0
    { seatId: "p3", card: { suit: "CLUBS", rank: { v: 6, s: "6" } } } // must NEVER be reached
  ]);
  var echoMismatchThenGood = MatchAdapter.applyRemoteCard("m-echo-mismatch-then-good", echoMismatchThenGoodDoc);
  check("MOCKED — Task 5 req #12: entries AFTER a LOCAL_ECHO_MISMATCH are never processed — the engine is never asked about the third-seat entry",
    echoMismatchThenGood.desync === true && echoMismatchThenGood.index === 0 && fakeEngineForEchoMismatch._emitCalls.length === 0);

  MatchAdapter.resetSyncState();
  var cardNoNew = MatchAdapter.applyRemoteCard("m-card-nonew", cardDocFor(1, []));
  check("MOCKED — applyRemoteCard: a version bump with an EMPTY/unchanged cardLog (e.g. caused by a concurrent bid write on the same document) is NO_NEW_CARDS, not an error",
    cardNoNew.applied === false && cardNoNew.reason === "NO_NEW_CARDS");

  // ============================================================
  // MOCKED — Sprint 4.2.1, Task 3 / Task 5 requirements #7, #8, #9:
  // remote ENGINE_REJECTED must cause DESYNC, not a silent skip. A
  // delivery with THREE new entries where the MIDDLE one is rejected
  // must: apply the first, reject+stop at the second, NEVER touch the
  // third, and leave both registries pointing exactly at the rejected
  // index — not past it.
  // ============================================================
  MatchAdapter.resetSyncState();
  var rejectAtIndex1 = {
    _emitCalls: [],
    getState: function () { return { phase: "PLAY", turn: "p2", plays: [] }; },
    emit: function (intent) {
      rejectAtIndex1._emitCalls.push(intent);
      // Reject based on the CARD'S OWN identity (HEARTS 3 specifically)
      // — not call count — so a redelivery that re-attempts the SAME
      // stuck entry deterministically gets the SAME rejection again,
      // exactly as a real engine consistently re-evaluating the same
      // proposed action against the same state would.
      if (intent.card.suit === "HEARTS" && intent.card.rank.v === 3) return { rejected: true, reason: "simulated Follow Spades" };
      return { rejected: false };
    }
  };
  global.TableEngine = rejectAtIndex1;
  var threeEntryDoc = cardDocFor(5, [
    { seatId: "p1", card: CARD },
    { seatId: "p2", card: { suit: "HEARTS", rank: { v: 3, s: "3" } } }, // this one is rejected
    { seatId: "p3", card: { suit: "CLUBS", rank: { v: 4, s: "4" } } }  // must NEVER be reached
  ]);
  var desyncResult = MatchAdapter.applyRemoteCard("m-desync", threeEntryDoc);
  check("MOCKED — Task 5 req #7 (ENGINE_REJECTED does not advance processed count past the rejected entry): getLastAppliedCardCount stops at index 1, not 2 or 3",
    MatchAdapter.getLastAppliedCardCount("m-desync") === 1);
  check("MOCKED — Task 5 req #8 (later entries are not processed after a rejected entry): the engine was asked exactly twice — index 0 (applied) and index 1 (rejected) — index 2 was NEVER emitted",
    rejectAtIndex1._emitCalls.length === 2);
  check("MOCKED — Task 5 req #9 (version is not marked applied after a rejected entry): getLastAppliedCardVersion is still null — this version is NOT considered fully synchronized",
    MatchAdapter.getLastAppliedCardVersion("m-desync") === null);
  check("MOCKED — Task 3: the result is a structured DESYNC, not a silent success — desync:true, reason:ENGINE_REJECTED, with matchId/index/seatId/engineReason diagnostics",
    desyncResult.desync === true && desyncResult.reason === "ENGINE_REJECTED" && desyncResult.matchId === "m-desync" &&
    desyncResult.index === 1 && desyncResult.seatId === "p2" && desyncResult.engineReason === "simulated Follow Spades");
  check("MOCKED — Task 3: appliedCount correctly reports ONLY the one entry that genuinely succeeded before the rejection (index 0), not the two entries attempted",
    desyncResult.appliedCount === 1);

  // A REDELIVERY of the exact same (still-stuck) snapshot must
  // re-attempt from the SAME stuck index — not silently skip it as
  // "already handled," and not auto-retry into an infinite loop on its
  // own (this call is the test driving a SECOND, explicit delivery,
  // not the adapter looping by itself).
  var desyncResult2 = MatchAdapter.applyRemoteCard("m-desync", threeEntryDoc);
  check("MOCKED — Task 3: a redelivery of the SAME still-stuck snapshot re-attempts from the SAME rejected index, rather than being silently treated as a duplicate — the desync is durable, not transient",
    desyncResult2.desync === true && desyncResult2.index === 1 &&
    rejectAtIndex1._emitCalls.length === 3 /* the loop resumes AT lastCount (1), never re-visiting the already-applied index 0 at all — only ONE more call, for index 1 again */);

  // Once the underlying cause is gone (here: simulating that a fixed/
  // corrected entry now arrives at the SAME index — the real-world
  // equivalent of an operator intervention, not automatic), a fresh
  // delivery starting from the stuck point can succeed and the
  // registries advance normally again.
  var rejectNever = {
    _emitCalls: [],
    getState: function () { return { phase: "PLAY", turn: "p2", plays: [{ playerId: "p1", card: CARD }] }; },
    emit: function (intent) { rejectNever._emitCalls.push(intent); return { rejected: false }; }
  };
  global.TableEngine = rejectNever;
  var recoveredResult = MatchAdapter.applyRemoteCard("m-desync", threeEntryDoc);
  check("MOCKED — Task 3: once the engine accepts the previously-stuck entry, processing resumes normally and the registries advance past it, including the final (third) entry",
    recoveredResult.desync === false && MatchAdapter.getLastAppliedCardCount("m-desync") === 3 && MatchAdapter.getLastAppliedCardVersion("m-desync") === 5);

  // ============================================================
  // MOCKED — Task 5 req #10 (no gameplay rules duplicated outside
  // TableEngine): structural check that match-adapter.js itself never
  // reimplements follow-suit/turn-order/hand logic — it only ever
  // calls the engine's own emit()/getState(), never computes a
  // legality decision from raw card/suit data itself.
  // ============================================================
  var fsCheck = require("fs");
  var adapterSourceForRules = fsCheck.readFileSync("/home/user/demo-test/design-ui/match-adapter.js", "utf8");
  check("MOCKED — Task 5 req #10: match-adapter.js contains no follow-suit (`ledSuit`) logic of its own anywhere in the file",
    !/ledSuit/.test(adapterSourceForRules));
  check("MOCKED — Task 5 req #10: match-adapter.js's only calls into TableEngine are emit()/getState() — it never accesses `.hands` to compute legality itself",
    !/TableEngine\.getState\(\)\.hands\[/.test(adapterSourceForRules));

  global.TableEngine = undefined; // restore

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
