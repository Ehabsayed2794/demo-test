// E7 / S67 — Game Type & Calculation Mode, rules layer (MINI half).
//
// The Phase 3 negative suite (native-phase3-rules-negative.test.cjs) is
// written entirely in FULL terms — every match it creates has maxRounds 18
// and every extension sits in 14–18 — so it proves S67's absent-means-FULL
// back-compat (38/38 byte-identical to the pre-S67 rules) but never once
// exercises the new MINI constants. This file is that coverage:
//
//   * a MINI room carries its gameType/scoringMode through creation;
//   * a MINI match must start at maxRounds 10 — and 18 is now DENIED for
//     MINI, just as 10 is denied for a doc with no gameType (absent really
//     does mean FULL, not "any value");
//   * MINI's Rapid Rounds window is 6–10, not 14–18;
//   * GM3's load-bearing boundary: MINI allows exactly ONE extension
//     (10 → 11 max) — the second is denied by the count cap, and the test
//     is shaped so the denial can ONLY come from that cap (the appended
//     round 7 is inside the 6–10 window, so the window rule passes);
//   * a rematch inherits the OLD match's type — a MINI match cannot
//     rematch as FULL.
//
// Same harness as every other *.rules-emulator.test.cjs in this suite:
// @firebase/rules-unit-testing against the real Firestore Rules Emulator
// at 127.0.0.1:8080, rules loaded verbatim from firestore.rules. Auto-
// discovered by scripts/run-tests.mjs, so the JS CI job covers it;
// android.yml's emulator job covers it on the native side too.
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

// The base match shape, parameterised on the type so one helper serves
// both halves of every M2/M3 test. `over` wins over the type defaults,
// which is how the negatives below are shaped.
function baseMatch(gameType, over) {
  var mini = gameType === "MINI";
  var doc = {
    roomId: "room-x", players: PLAYERS.slice(), status: "starting",
    currentRound: 1, maxRounds: mini ? 10 : 18, extendedRounds: [],
    dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 1,
    biddingOpen: true, bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: Object.assign({}, NOT_DEALT),
    gameType: gameType, scoringMode: "NORMAL"
  };
  if (over) Object.keys(over).forEach(function (k) { doc[k] = over[k]; });
  // `gameType: null` / `scoringMode: null` in `over` means "the key is
  // ABSENT" — delete, not null — so a negative can model a genuine
  // pre-S67 document. This matters: a key present-but-null would itself
  // be denied by isValidGameType(), masking the clause under test.
  if (doc.gameType === null) delete doc.gameType;
  if (doc.scoringMode === null) delete doc.scoringMode;
  return doc;
}

// A room with everyone ready — the state startMatch's batch finds with
// get() (pre-transaction) while the same batch flips it to in_game.
function readyRoom(over) {
  var doc = {
    creator: uidA, players: PLAYERS.slice(), readyPlayers: PLAYERS.slice(),
    status: "waiting", name: "r", createdAt: 1, updatedAt: 1, matchId: null
  };
  if (over) Object.keys(over).forEach(function (k) { doc[k] = over[k]; });
  return doc;
}

async function seed(db, path, data) {
  var parts = path.split("/");
  var ref = db;
  for (var i = 0; i < parts.length; i += 2) ref = ref.collection(parts[i]).doc(parts[i + 1]);
  await ref.set(data);
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
  // M1. createRoom — gameType / scoringMode enter the allowlist
  // ════════════════════════════════════════════════════════════════
  await okSucceeds("M1.1 POSITIVE: a room created as MINI + CLASSIC is accepted",
    A.firestore().collection("rooms").doc("r-m11").set({
      creator: uidA, players: [uidA], readyPlayers: [], status: "waiting",
      name: "mini-room", gameType: "MINI", scoringMode: "CLASSIC",
      createdAt: 1, updatedAt: 1
    }));

  await okSucceeds("M1.2 POSITIVE: a room created with NO gameType/scoringMode is accepted (back-compat)",
    A.firestore().collection("rooms").doc("r-m12").set({
      creator: uidA, players: [uidA], readyPlayers: [], status: "waiting",
      name: "old-room", createdAt: 1, updatedAt: 1
    }));

  await okFails("M1.3 NEGATIVE: a room carrying an unknown gameType is denied",
    A.firestore().collection("rooms").doc("r-m13").set({
      creator: uidA, players: [uidA], readyPlayers: [], status: "waiting",
      name: "bad", gameType: "QUICK", scoringMode: "NORMAL",
      createdAt: 1, updatedAt: 1
    }));

  await okFails("M1.4 NEGATIVE: a room carrying an unknown scoringMode is denied",
    A.firestore().collection("rooms").doc("r-m14").set({
      creator: uidA, players: [uidA], readyPlayers: [], status: "waiting",
      name: "bad", gameType: "MINI", scoringMode: "SUDDEN_DEATH",
      createdAt: 1, updatedAt: 1
    }));

  // ════════════════════════════════════════════════════════════════
  // M2. startMatch — the type-derived maxRounds ceiling
  // Each match is paired with its own ready room in one atomic batch,
  // exactly the shape MatchService.startMatch() writes.
  // ════════════════════════════════════════════════════════════════
  var rooms = {};
  async function seedRoom(id) {
    rooms[id] = true;
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await seed(ctx.firestore(), "rooms/" + id, readyRoom());
    });
  }
  await seedRoom("r-m21"); await seedRoom("r-m22");
  await seedRoom("r-m23"); await seedRoom("r-m24"); await seedRoom("r-m25");

  await okSucceeds("M2.1 POSITIVE: a MINI match starts at maxRounds 10, paired with its room atomically",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-m21"), baseMatch("MINI", { roomId: "r-m21" }))
      .update(A.firestore().collection("rooms").doc("r-m21"), { status: "in_game", matchId: "m-m21", updatedAt: 2 })
      .commit());

  await okSucceeds("M2.2 POSITIVE: a FULL match starts at maxRounds 18 (GM2: Full is unchanged)",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-m22"), baseMatch("FULL", { roomId: "r-m22" }))
      .update(A.firestore().collection("rooms").doc("r-m22"), { status: "in_game", matchId: "m-m22", updatedAt: 2 })
      .commit());

  await okFails("M2.3 NEGATIVE: a MINI match claiming maxRounds 18 is denied (MINI's ceiling is 10)",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-m23"), baseMatch("MINI", { roomId: "r-m23", maxRounds: 18 }))
      .update(A.firestore().collection("rooms").doc("r-m23"), { status: "in_game", matchId: "m-m23", updatedAt: 2 })
      .commit());

  await okFails("M2.4 NEGATIVE: a match with NO gameType claiming maxRounds 10 is denied (absent means FULL, so 18)",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-m24"), baseMatch("FULL", { roomId: "r-m24", gameType: null, maxRounds: 10 }))
      .update(A.firestore().collection("rooms").doc("r-m24"), { status: "in_game", matchId: "m-m24", updatedAt: 2 })
      .commit());

  await okFails("M2.5 NEGATIVE: a MINI match carrying an unknown scoringMode is denied",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-m25"), baseMatch("MINI", { roomId: "r-m25", scoringMode: "SUDDEN_DEATH" }))
      .update(A.firestore().collection("rooms").doc("r-m25"), { status: "in_game", matchId: "m-m25", updatedAt: 2 })
      .commit());

  // ════════════════════════════════════════════════════════════════
  // M3. extendMatchRounds — MINI's 6-10 window and GM3's one-extension cap
  // Seeded directly at the post-deal/pre-extension state with the rules
  // disabled (the write under test is the only rules-evaluated one), the
  // same convention the Phase 3 negative suite uses for its S6 block.
  // ════════════════════════════════════════════════════════════════
  var m = 0;
  async function miniMatch(over) {
    var id = "m-ext-" + (++m);
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await seed(ctx.firestore(), "matches/" + id, baseMatch("MINI", over));
    });
    return id;
  }

  var e1 = await miniMatch({ currentRound: 6, maxRounds: 10, extendedRounds: [] });
  await okSucceeds("M3.1 POSITIVE: a MINI round-6 extension raises maxRounds 10->11 once",
    A.firestore().collection("matches").doc(e1)
      .update({ maxRounds: 11, extendedRounds: [6], version: 2 }));

  var e2 = await miniMatch({ currentRound: 6, maxRounds: 10, extendedRounds: [] });
  await okFails("M3.2 NEGATIVE: a MINI extension recording a round below MINI's window (5) is denied",
    A.firestore().collection("matches").doc(e2)
      .update({ maxRounds: 11, extendedRounds: [5], version: 2 }));

  var e3 = await miniMatch({ currentRound: 6, maxRounds: 10, extendedRounds: [] });
  await okFails("M3.3 NEGATIVE: a MINI extension recording a round above MINI's ceiling (11) is denied",
    A.firestore().collection("matches").doc(e3)
      .update({ maxRounds: 11, extendedRounds: [11], version: 2 }));

  // Seeded AFTER one extension is already recorded. Round 7 is INSIDE
  // MINI's 6-10 window, so every other clause of isValidRoundExtension()
  // passes — the only clause left to deny this is the GM3 count cap
  // (oldRounds.size() < 1). This is the one test in the suite that pins
  // "MINI allows exactly one extension, not two."
  var e4 = await miniMatch({ currentRound: 7, maxRounds: 11, extendedRounds: [6], version: 2 });
  await okFails("M3.4 NEGATIVE (GM3): MINI's SECOND extension is denied by the one-extension cap",
    A.firestore().collection("matches").doc(e4)
      .update({ maxRounds: 12, extendedRounds: [6, 7], version: 3 }));

  // The same cap must NOT bite FULL, whose window already implies five.
  // Seeded after four recorded extensions (14-17), still inside 14-18,
  // with size 4 < 5: the fifth extension is allowed.
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/m-ext-full", baseMatch("FULL", {
      currentRound: 18, maxRounds: 18, extendedRounds: [14, 15, 16, 17], version: 5
    }));
  });
  await okSucceeds("M3.5 POSITIVE: FULL's fifth extension is still allowed (the cap never binds FULL)",
    A.firestore().collection("matches").doc("m-ext-full")
      .update({ maxRounds: 19, extendedRounds: [14, 15, 16, 17, 18], version: 6 }));

  // ════════════════════════════════════════════════════════════════
  // M4. createRematchMatch — the type is INHERITED, never chosen
  // ════════════════════════════════════════════════════════════════
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/m-old-mini", {
      roomId: "room-rem", players: PLAYERS.slice(), status: "complete",
      createdAt: 1, currentRound: 11, maxRounds: 10, extendedRounds: [6],
      dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 5,
      biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 11 },
      winnerIds: [uidA], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 },
      completedRound: 11, gameType: "MINI", scoringMode: "NORMAL"
    });
    await seed(ctx.firestore(), "matches/m-old-mini/rematchVote/current", {
      matchId: "m-old-mini", seats: Object.assign({}, SEATS),
      votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" },
      status: "ALL_YES", newMatchId: null, createdAt: new Date(), version: 5
    });
  });

  function validRematch(over) {
    var doc = {
      roomId: "room-rem", rematchOfMatchId: "m-old-mini", players: PLAYERS.slice(),
      status: "starting", createdAt: new Date(), currentRound: 1,
      maxRounds: 10, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: Object.assign({}, SEATS), version: 1, biddingOpen: true,
      bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: Object.assign({}, NOT_DEALT),
      gameType: "MINI", scoringMode: "NORMAL"
    };
    if (over) Object.keys(over).forEach(function (k) { doc[k] = over[k]; });
    return doc;
  }

  await okSucceeds("M4.1 POSITIVE: a MINI match rematches as MINI (10 rounds, inherited)",
    A.firestore().collection("matches").doc("m-rem-ok").set(validRematch()));

  await okFails("M4.2 NEGATIVE: a MINI match cannot rematch as FULL (the type is inherited, not chosen)",
    A.firestore().collection("matches").doc("m-rem-bad").set(
      validRematch({ gameType: "FULL", maxRounds: 18 })));

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
  if (fail > 0) process.exitCode = 1;
}

main().catch(function (e) {
  console.error("HARNESS CRASHED: " + ((e && e.stack) || e));
  process.exitCode = 1;
});
