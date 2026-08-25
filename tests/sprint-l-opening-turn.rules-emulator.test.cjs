// SPRINT L — Real Firestore Rules Emulator regression for the owner-authorized
// Round-1 opening-turn publication window. This test loads the literal repo
// firestore.rules file and does not simulate the Rules predicate in JavaScript.
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const RULES_PATH = path.join(REPO_ROOT, "firestore.rules");
const RULES_SHA = require("crypto").createHash("sha256").update(fs.readFileSync(RULES_PATH)).digest("hex");

var pass = 0;
var fail = 0;
function check(label, ok, note) {
  if (ok) {
    console.log("PASS  " + label);
    pass++;
  } else {
    console.log("FAIL  " + label + (note ? " -- " + note : ""));
    fail++;
  }
}

async function run() {
  console.log("Rules SHA256=" + RULES_SHA);
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-test-sprint-l",
      firestore: {
        rules: fs.readFileSync(RULES_PATH, "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    console.log("EMULATOR NOT REACHABLE — " + e.message);
    console.log("0 passed, 0 failed (SKIPPED — no emulator connection)");
    process.exitCode = 2;
    return;
  }

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";
  function baseMatch(overrides) {
    var match = {
      roomId: "room-l",
      players: [uidA, uidB, uidC, uidD],
      status: "starting",
      createdAt: 1,
      currentRound: 1,
      maxRounds: 18,
      extendedRounds: [],
      dealer: uidA,
      turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD },
      version: 1,
      biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null },
      lastBidSeat: null,
      cardLog: [],
      lastCardSeat: null,
      cardPhase: null,
      biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    };
    return Object.assign(match, overrides || {});
  }
  async function seed(matchId, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(baseMatch(overrides));
    });
  }
  function matchRef(uid, matchId) {
    return testEnv.authenticatedContext(uid).firestore().collection("matches").doc(matchId);
  }
  function openingPatch(targetUid) {
    return { turn: targetUid, cardPhase: "PLAY", version: 2, updatedAt: 1 };
  }
  function cardEntry(seat) {
    return { seatId: seat, card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 };
  }

  var positive = "opening-round-positive";
  await seed(positive);
  check("L.1 Round-1 fresh match (turn=dealer, empty cardLog) allows caller opening-turn publication",
    await assertSucceeds(matchRef(uidB, positive).update(openingPatch(uidB)))
      .then(function () { return true; }).catch(function () { return false; }));

  var nonEmpty = "opening-round-cardlog-nonempty";
  await seed(nonEmpty, { cardLog: [cardEntry("p1")] });
  check("L.2 Round-1 opening publication is denied once cardLog already contains a card",
    await assertFails(matchRef(uidB, nonEmpty).update(openingPatch(uidB)))
      .then(function () { return true; }).catch(function () { return false; }));

  var nonSeatTarget = "opening-round-non-seat-target";
  await seed(nonSeatTarget);
  check("L.3 Opening publication targeting a UID absent from the seat map is denied",
    await assertFails(matchRef(uidB, nonSeatTarget).update(openingPatch(uidZ)))
      .then(function () { return true; }).catch(function () { return false; }));

  var wrongRound = "opening-round-wrong-round";
  await seed(wrongRound, { currentRound: 2 });
  check("L.4 The widened opening window remains limited to currentRound 1",
    await assertFails(matchRef(uidB, wrongRound).update(openingPatch(uidB)))
      .then(function () { return true; }).catch(function () { return false; }));

  var nonDealer = "opening-round-nondealer-turn";
  await seed(nonDealer, { turn: uidC });
  check("L.5 A non-null, non-dealer old turn cannot use the Round-1 opening window",
    await assertFails(matchRef(uidB, nonDealer).update(openingPatch(uidB)))
      .then(function () { return true; }).catch(function () { return false; }));

  await testEnv.cleanup();
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
}

run().catch(function (e) {
  console.error(e);
  process.exitCode = 1;
});
