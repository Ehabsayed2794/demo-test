// RD21 — the bot trio persistence, rules layer: botTier (EASY/MEDIUM/HARD/
// EXPERT), botPersonality (BALANCED/AGGRESSIVE/CONSERVATIVE/TRICKSTER), and
// decisionTimerSeconds (5..20). The S36 Create Game screen captures these
// and they now ride rooms and matches, so this file is that coverage,
// mirroring the S43 mode suite's shape:
//
//   * T1: room creates accept the full trio, accept absent-trio (back-compat
//     for rooms predating the trio's persistence), deny a bogus tier, deny a
//     bogus personality, and deny a timer outside the 5..20 window (and a
//     non-integer one);
//   * T2: match creates accept the trio and absent-trio, deny bogus;
//   * T3: a rematch INHERITS the OLD match's trio — a HARD match cannot
//     rematch as EASY, and a pre-RD21 old match (no fields) rematches as
//     MEDIUM/BALANCED/15s on both sides.
//
// Same harness as every other *.rules-emulator.test.cjs in this suite:
// @firebase/rules-unit-testing against the real Firestore Rules Emulator
// at 127.0.0.1:8080, rules loaded verbatim from firestore.rules.
//
// Doc ids are RD21-prefixed and unique across tests/ — scripts/run-tests.mjs
// runs every suite against ONE shared emulator with no clearing between
// files, so a reused id would let an earlier suite's write turn a later
// suite's create into an update (the S43/mini collision).
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

// A room create, parameterised on the trio. `undefined` means "the key is
// ABSENT" — never written — so a negative models a genuine pre-RD21
// document (a present-but-null key would itself be denied, masking the
// clause under test).
function baseRoom(trio, over) {
  var doc = {
    creator: uidA, players: [uidA], readyPlayers: [], status: "waiting",
    name: "trio-room", createdAt: 1, updatedAt: 1
  };
  if (trio) {
    if (trio.tier !== undefined) doc.botTier = trio.tier;
    if (trio.personality !== undefined) doc.botPersonality = trio.personality;
    if (trio.timer !== undefined) doc.decisionTimerSeconds = trio.timer;
  }
  if (over) Object.keys(over).forEach(function (k) { doc[k] = over[k]; });
  return doc;
}

// A match create in startMatch's atomic shape (match set + room flip tested
// together in one batch). The trio rides every create.
function baseMatch(trio, over) {
  var doc = {
    roomId: "room-x", players: PLAYERS.slice(), status: "starting",
    currentRound: 1, maxRounds: 18, extendedRounds: [],
    dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 1,
    biddingOpen: true, bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: Object.assign({}, NOT_DEALT),
    gameType: "FULL", scoringMode: "NORMAL"
  };
  if (trio) {
    if (trio.tier !== undefined) doc.botTier = trio.tier;
    if (trio.personality !== undefined) doc.botPersonality = trio.personality;
    if (trio.timer !== undefined) doc.decisionTimerSeconds = trio.timer;
  }
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
  // T1. createRoom — the trio enters the room allowlist
  // ════════════════════════════════════════════════════════════════
  await okSucceeds("T1.1 POSITIVE: a room carrying the full trio (EXPERT/TRICKSTER/20s) is accepted",
    A.firestore().collection("rooms").doc("r-rd211")
      .set(baseRoom({ tier: "EXPERT", personality: "TRICKSTER", timer: 20 })));

  await okSucceeds("T1.2 POSITIVE: a room with NO trio fields is accepted (back-compat)",
    A.firestore().collection("rooms").doc("r-rd212").set(baseRoom(null)));

  await okSucceeds("T1.3 POSITIVE: each tier value is accepted",
    A.firestore().collection("rooms").doc("r-rd213")
      .set(baseRoom({ tier: "EASY", personality: "BALANCED", timer: 5 })));

  await okFails("T1.4 NEGATIVE: a room carrying a bogus fifth tier is denied",
    A.firestore().collection("rooms").doc("r-rd214")
      .set(baseRoom({ tier: "NIGHTMARE", personality: "BALANCED", timer: 15 })));

  await okFails("T1.5 NEGATIVE: a room carrying a bogus fifth personality is denied",
    A.firestore().collection("rooms").doc("r-rd215")
      .set(baseRoom({ tier: "HARD", personality: "CHAOTIC", timer: 15 })));

  await okFails("T1.6 NEGATIVE: a room carrying a timer below the window (4s) is denied",
    A.firestore().collection("rooms").doc("r-rd216")
      .set(baseRoom({ tier: "HARD", personality: "BALANCED", timer: 4 })));

  await okFails("T1.7 NEGATIVE: a room carrying a timer above the window (21s) is denied",
    A.firestore().collection("rooms").doc("r-rd217")
      .set(baseRoom({ tier: "HARD", personality: "BALANCED", timer: 21 })));

  await okFails("T1.8 NEGATIVE: a room carrying a non-integer timer is denied",
    A.firestore().collection("rooms").doc("r-rd218")
      .set(baseRoom({ tier: "HARD", personality: "BALANCED", timer: "15" })));

  // ════════════════════════════════════════════════════════════════
  // T2. startMatch — the trio rides the match create
  // Each match is paired with its own ready room in one atomic batch,
  // exactly the shape MatchService.startMatch() writes.
  // ════════════════════════════════════════════════════════════════
  async function seedRoom(id) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await seed(ctx.firestore(), "rooms/" + id, readyRoom());
    });
  }

  var ROOMS = ["r-rd221", "r-rd222", "r-rd223", "r-rd224"];
  for (var i = 0; i < ROOMS.length; i++) await seedRoom(ROOMS[i]);

  await okSucceeds("T2.1 POSITIVE: a match carrying the full trio (EXPERT/TRICKSTER/20s) is accepted",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-rd221"),
        baseMatch({ tier: "EXPERT", personality: "TRICKSTER", timer: 20 }, { roomId: ROOMS[0] }))
      .update(A.firestore().collection("rooms").doc(ROOMS[0]),
        { status: "in_game", matchId: "m-rd221", updatedAt: 2 })
      .commit());

  await okSucceeds("T2.2 POSITIVE: a match with NO trio fields is accepted (back-compat)",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-rd222"),
        baseMatch(null, { roomId: ROOMS[1] }))
      .update(A.firestore().collection("rooms").doc(ROOMS[1]),
        { status: "in_game", matchId: "m-rd222", updatedAt: 2 })
      .commit());

  await okFails("T2.3 NEGATIVE: a match carrying a bogus tier is denied",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-rd223"),
        baseMatch({ tier: "NIGHTMARE", personality: "BALANCED", timer: 15 }, { roomId: ROOMS[2] }))
      .update(A.firestore().collection("rooms").doc(ROOMS[2]),
        { status: "in_game", matchId: "m-rd223", updatedAt: 2 })
      .commit());

  await okFails("T2.4 NEGATIVE: a match carrying an out-of-range timer is denied",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-rd224"),
        baseMatch({ tier: "HARD", personality: "BALANCED", timer: 99 }, { roomId: ROOMS[3] }))
      .update(A.firestore().collection("rooms").doc(ROOMS[3]),
        { status: "in_game", matchId: "m-rd224", updatedAt: 2 })
      .commit());

  // ════════════════════════════════════════════════════════════════
  // T3. createRematchMatch — the trio is INHERITED, never chosen
  // ════════════════════════════════════════════════════════════════
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    // An RD21 old match: the trio is present and non-default.
    await seed(ctx.firestore(), "matches/m-old-rd21trio", {
      roomId: "room-rem", players: PLAYERS.slice(), status: "complete",
      createdAt: 1, currentRound: 18, maxRounds: 18, extendedRounds: [],
      dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 5,
      biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 18 },
      winnerIds: [uidA], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 },
      completedRound: 18, gameType: "FULL", scoringMode: "NORMAL",
      botTier: "EXPERT", botPersonality: "AGGRESSIVE", decisionTimerSeconds: 10
    });
    await seed(ctx.firestore(), "matches/m-old-rd21trio/rematchVote/current", {
      matchId: "m-old-rd21trio", seats: Object.assign({}, SEATS),
      votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" },
      status: "ALL_YES", newMatchId: null, createdAt: new Date(), version: 5
    });
    // A pre-RD21 old match: NO trio fields at all.
    await seed(ctx.firestore(), "matches/m-old-rd21plain", {
      roomId: "room-rem", players: PLAYERS.slice(), status: "complete",
      createdAt: 1, currentRound: 18, maxRounds: 18, extendedRounds: [],
      dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 5,
      biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 18 },
      winnerIds: [uidA], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 },
      completedRound: 18, gameType: "FULL", scoringMode: "NORMAL"
    });
    await seed(ctx.firestore(), "matches/m-old-rd21plain/rematchVote/current", {
      matchId: "m-old-rd21plain", seats: Object.assign({}, SEATS),
      votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" },
      status: "ALL_YES", newMatchId: null, createdAt: new Date(), version: 5
    });
  });

  function validRematch(oldId, trio) {
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
    if (trio) {
      if (trio.tier !== undefined) doc.botTier = trio.tier;
      if (trio.personality !== undefined) doc.botPersonality = trio.personality;
      if (trio.timer !== undefined) doc.decisionTimerSeconds = trio.timer;
    }
    return doc;
  }

  await okSucceeds("T3.1 POSITIVE: an EXPERT/AGGRESSIVE/10s match rematches with the same trio (inherited)",
    A.firestore().collection("matches").doc("m-rd21rem-ok")
      .set(validRematch("m-old-rd21trio", { tier: "EXPERT", personality: "AGGRESSIVE", timer: 10 })));

  await okFails("T3.2 NEGATIVE: an EXPERT match cannot rematch as EASY (tier is inherited, not chosen)",
    A.firestore().collection("matches").doc("m-rd21rem-bad")
      .set(validRematch("m-old-rd21trio", { tier: "EASY", personality: "AGGRESSIVE", timer: 10 })));

  await okFails("T3.3 NEGATIVE: an AGGRESSIVE match cannot rematch as CONSERVATIVE (personality is inherited)",
    A.firestore().collection("matches").doc("m-rd21rem-bad2")
      .set(validRematch("m-old-rd21trio", { tier: "EXPERT", personality: "CONSERVATIVE", timer: 10 })));

  await okFails("T3.4 NEGATIVE: a 10s match cannot rematch at 20s (timer is inherited)",
    A.firestore().collection("matches").doc("m-rd21rem-bad3")
      .set(validRematch("m-old-rd21trio", { tier: "EXPERT", personality: "AGGRESSIVE", timer: 20 })));

  await okFails("T3.5 NEGATIVE: a trio-carrying match cannot rematch with NO trio (absent ⇒ MEDIUM ≠ EXPERT)",
    A.firestore().collection("matches").doc("m-rd21rem-bad4")
      .set(validRematch("m-old-rd21trio", null)));

  await okSucceeds("T3.6 POSITIVE: a pre-RD21 old match (no trio) rematches with NO trio (both default)",
    A.firestore().collection("matches").doc("m-rd21rem-plain")
      .set(validRematch("m-old-rd21plain", null)));

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
  if (fail > 0) process.exitCode = 1;
}

main().catch(function (e) {
  console.error("HARNESS CRASHED: " + ((e && e.stack) || e));
  process.exitCode = 1;
});
