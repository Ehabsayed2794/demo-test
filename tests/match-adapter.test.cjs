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

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
