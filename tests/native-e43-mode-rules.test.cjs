// E6b / S43 — MatchMode authority key (ROOM | RANKED | UNRANKED) + the
// room's private-access rankDown flag, rules layer.
//
// The writers (:services) now persist `mode` on every room and match and
// `rankDown` on rooms; firestore.rules admits the new keys and validates
// them. This file is that coverage, mirroring the S67 MINI suite's shape:
//
//   * R1: room creates accept each of the three modes, accept rankDown
//     true/false, accept absent-both (back-compat), deny a bogus fourth
//     mode, and deny a non-bool rankDown;
//   * R2: match creates accept each mode and absent-mode, deny bogus;
//   * R3: a rematch inherits the OLD match's mode — a RANKED match cannot
//     rematch as ROOM, and a pre-S43 old match (no field) rematches as
//     ROOM on both sides.
//
// Same harness as every other *.rules-emulator.test.cjs in this suite:
// @firebase/rules-unit-testing against the real Firestore Rules Emulator
// at 127.0.0.1:8080, rules loaded verbatim from firestore.rules. Auto-
// discovered by scripts/run-tests.mjs.
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

var REPO_ROOT = require("path").join(__dirname, "..");

var pass = 0, fail = 0;
function check(label, ok) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
async function okSucceeds(label, op) {
  try { await assertSucceeds(op); check(label, true); }
  catch (e) { check(label + " — unexpected denial: " + e.message, false); }
}
async function okFails(label, op) {
  try { await assertFails(op); check(label, true); }
  catch (e) { check(label + " — write was ACCEPTED, must be denied", false); }
}

var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";
var PLAYERS = [uidA, uidB, uidC, uidD];
var SEATS = { p1: uidA, p2: uidB, p3: uidC, p4: uidD };
var NULL_BIDS = { p1: null, p2: null, p3: null, p4: null };
var NOT_DEALT = { initialized: false, dealtRound: 0 };

// A room create, parameterised on mode/rankDown. `undefined` means "the
// key is ABSENT" — deleted below, never null — so a negative models a
// genuine pre-S43 document (a present-but-null key would itself be
// denied, masking the clause under test).
function baseRoom(mode, rankDown) {
  var doc = {
    creator: uidA, players: [uidA], readyPlayers: [], status: "waiting",
    name: "mode-room", createdAt: 1, updatedAt: 1
  };
  if (mode !== undefined) doc.mode = mode;
  if (rankDown !== undefined) doc.rankDown = rankDown;
  return doc;
}

// A match create in startMatch's atomic shape (match set + room flip
// tested together in R2 via batch). Mode rides every create.
function baseMatch(mode, over) {
  var doc = {
    roomId: "room-x", players: PLAYERS.slice(), status: "starting",
    currentRound: 1, maxRounds: 18, extendedRounds: [],
    dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 1,
    biddingOpen: true, bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: Object.assign({}, NOT_DEALT),
    gameType: "FULL", scoringMode: "NORMAL"
  };
  if (mode !== undefined) doc.mode = mode;
  if (over) Object.keys(over).forEach(function (k) { doc[k] = over[k]; });
  return doc;
}

async function seed(db, path, data) {
  var parts = path.split("/");
  var ref = db;
  for (var i = 0; i < parts.length; i += 2) ref = ref.collection(parts[i]).doc(parts[i + 1]);
  await ref.set(data);
}

// A room with everyone ready — the state startMatch's batch finds with
// get() (pre-transaction) while the same batch flips it to in_game.
function readyRoom() {
  return {
    creator: uidA, players: PLAYERS.slice(), readyPlayers: PLAYERS.slice(),
    status: "waiting", name: "r", createdAt: 1, updatedAt: 1, matchId: null
  };
}

async function main() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-test-ci",
      firestore: {
        rules: fs.readFileSync(REPO_ROOT + "/firestore.rules", "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    console.log("EMULATOR NOT REACHABLE — " + e.message);
    console.log("A real Firestore Rules Emulator must be running at 127.0.0.1:8080 for this suite.");
    console.log("\n=== RESULTS ===\n0 passed, 0 failed (emulator required; nothing was silently ignored)");
    process.exitCode = 2;
    return;
  }

  var A = testEnv.authenticatedContext(uidA);

  // ════════════════════════════════════════════════════════════════
  // R1. createRoom — mode + rankDown enter the allowlist
  // ════════════════════════════════════════════════════════════════
  await okSucceeds("R1.1 POSITIVE: a ROOM room is accepted",
    A.firestore().collection("rooms").doc("r-s431").set(baseRoom("ROOM", false)));

  await okSucceeds("R1.2 POSITIVE: a RANKED rank-down room (flag true) is accepted",
    A.firestore().collection("rooms").doc("r-s432").set(baseRoom("RANKED", true)));

  await okSucceeds("R1.3 POSITIVE: an UNRANKED room is accepted (first-class, not a marker)",
    A.firestore().collection("rooms").doc("r-s433").set(baseRoom("UNRANKED", false)));

  await okSucceeds("R1.4 POSITIVE: a room with NO mode/rankDown is accepted (back-compat)",
    A.firestore().collection("rooms").doc("r-s434").set(baseRoom(undefined, undefined)));

  await okFails("R1.5 NEGATIVE: a room carrying a bogus fourth mode is denied",
    A.firestore().collection("rooms").doc("r-s435").set(baseRoom("MEGA", false)));

  await okFails("R1.6 NEGATIVE: a room carrying a non-bool rankDown is denied",
    A.firestore().collection("rooms").doc("r-s436").set(baseRoom("RANKED", "yes")));

  // ════════════════════════════════════════════════════════════════
  // R2. startMatch — the authority key rides the match create
  // Each match is paired with its own ready room in one atomic batch,
  // exactly the shape MatchService.startMatch() writes.
  // ════════════════════════════════════════════════════════════════
  async function seedRoom(id) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await seed(ctx.firestore(), "rooms/" + id, readyRoom());
    });
  }
  await seedRoom("r-s443"); await seedRoom("r-s444");

  const MODES = ["ROOM", "RANKED", "UNRANKED"];
  for (var i = 0; i < MODES.length; i++) {
    var id = "r-s44" + i;
    await seedRoom(id);
    await okSucceeds("R2." + (i + 1) + " POSITIVE: a " + MODES[i] + " match create is accepted",
      A.firestore().batch()
        .set(A.firestore().collection("matches").doc("m-s43" + (i + 1)),
          baseMatch(MODES[i], { roomId: id }))
        .update(A.firestore().collection("rooms").doc(id),
          { status: "in_game", matchId: "m-s43" + (i + 1), updatedAt: 2 })
        .commit());
  }

  await okSucceeds("R2.4 POSITIVE: a match with NO mode is accepted (back-compat ⇒ ROOM)",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-s437"),
        baseMatch(undefined, { roomId: "r-s443" }))
      .update(A.firestore().collection("rooms").doc("r-s443"),
        { status: "in_game", matchId: "m-s437", updatedAt: 2 })
      .commit());

  await okFails("R2.5 NEGATIVE: a match carrying a bogus fourth mode is denied",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-s438"),
        baseMatch("MEGA", { roomId: "r-s444" }))
      .update(A.firestore().collection("rooms").doc("r-s444"),
        { status: "in_game", matchId: "m-s438", updatedAt: 2 })
      .commit());

  // ════════════════════════════════════════════════════════════════
  // R3. createRematchMatch — the mode is INHERITED, never chosen
  // ════════════════════════════════════════════════════════════════
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/m-old-ranked", {
      roomId: "room-rem", players: PLAYERS.slice(), status: "complete",
      createdAt: 1, currentRound: 18, maxRounds: 18, extendedRounds: [],
      dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 5,
      biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 18 },
      winnerIds: [uidA], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 },
      completedRound: 18, gameType: "FULL", scoringMode: "NORMAL", mode: "RANKED"
    });
    await seed(ctx.firestore(), "matches/m-old-ranked/rematchVote/current", {
      matchId: "m-old-ranked", seats: Object.assign({}, SEATS),
      votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" },
      status: "ALL_YES", newMatchId: null, createdAt: new Date(), version: 5
    });
    // A pre-S43 old match: NO mode field at all.
    await seed(ctx.firestore(), "matches/m-old-plain", {
      roomId: "room-rem", players: PLAYERS.slice(), status: "complete",
      createdAt: 1, currentRound: 18, maxRounds: 18, extendedRounds: [],
      dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 5,
      biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 18 },
      winnerIds: [uidA], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 },
      completedRound: 18, gameType: "FULL", scoringMode: "NORMAL"
    });
    await seed(ctx.firestore(), "matches/m-old-plain/rematchVote/current", {
      matchId: "m-old-plain", seats: Object.assign({}, SEATS),
      votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" },
      status: "ALL_YES", newMatchId: null, createdAt: new Date(), version: 5
    });
  });

  function validRematch(oldId, mode) {
    var doc = {
      roomId: "room-rem", rematchOfMatchId: oldId, players: PLAYERS.slice(),
      status: "starting", createdAt: new Date(), currentRound: 1,
      maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: Object.assign({}, SEATS), version: 1, biddingOpen: true,
      bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: Object.assign({}, NOT_DEALT),
      gameType: "FULL", scoringMode: "NORMAL"
    };
    if (mode !== undefined) doc.mode = mode;
    return doc;
  }

  await okSucceeds("R3.1 POSITIVE: a RANKED match rematches as RANKED (inherited)",
    A.firestore().collection("matches").doc("m-rem-ok")
      .set(validRematch("m-old-ranked", "RANKED")));

  await okFails("R3.2 NEGATIVE: a RANKED match cannot rematch as ROOM (mode is inherited, not chosen)",
    A.firestore().collection("matches").doc("m-rem-bad")
      .set(validRematch("m-old-ranked", "ROOM")));

  await okFails("R3.3 NEGATIVE: a RANKED match cannot rematch with NO mode (absent ⇒ ROOM ≠ RANKED)",
    A.firestore().collection("matches").doc("m-rem-bad2")
      .set(validRematch("m-old-ranked", undefined)));

  await okSucceeds("R3.4 POSITIVE: a pre-S43 old match (no mode) rematches as ROOM on both sides",
    A.firestore().collection("matches").doc("m-rem-plain")
      .set(validRematch("m-old-plain", undefined)));

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
  if (fail > 0) process.exitCode = 1;
}

main().catch(function (e) {
  console.error("HARNESS CRASHED: " + ((e && e.stack) || e));
  process.exitCode = 1;
});
