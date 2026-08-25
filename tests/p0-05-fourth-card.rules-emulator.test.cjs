// P0-05 — real Firestore Rules Emulator contract for the fourth card.
// This verifies the service/rules boundary after J.1: the fourth card is
// persisted with cardPhase RESOLVING and a real winner UID as turn, so the
// winner alone may open the next trick. No Rules are modified by this test.
const fs = require("fs");
const crypto = require("crypto");
const { initializeTestEnvironment, assertSucceeds, assertFails } = require("@firebase/rules-unit-testing");

const REPO_ROOT = require("path").join(__dirname, "..");
const RULES = fs.readFileSync(REPO_ROOT + "/firestore.rules", "utf8");
const RULES_SHA256 = crypto.createHash("sha256").update(RULES).digest("hex");
const PROJECT_ID = "p0-05-card-" + process.pid + "-" + Date.now();

const A = "uidA", B = "uidB", C = "uidC", D = "uidD";
const seats = { p1: A, p2: B, p3: C, p4: D };
const cards = [
  { suit: "SPADES", rank: { v: 14, s: "A" } },
  { suit: "SPADES", rank: { v: 5, s: "5" } },
  { suit: "SPADES", rank: { v: 8, s: "8" } },
  { suit: "SPADES", rank: { v: 2, s: "2" } },
  { suit: "SPADES", rank: { v: 3, s: "3" } }
];

function entry(seatId, card) {
  return { seatId, card, round: 1 };
}
function baseMatch() {
  return {
    roomId: "room-p0-05", players: [A, B, C, D], status: "starting", createdAt: new Date(),
    currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: A, turn: D,
    seats, version: 4, biddingOpen: false,
    bids: { p1: 0, p2: 0, p3: 0, p4: 0 }, lastBidSeat: "p4",
    cardLog: [entry("p1", cards[0]), entry("p2", cards[1]), entry("p3", cards[2])],
    lastCardSeat: "p3", cardPhase: "PLAY", biddingLog: [],
    gameState: { initialized: true, dealtRound: 1 }
  };
}
function ctx(env, uid) { return env.authenticatedContext(uid).firestore(); }
function ref(env, uid) { return ctx(env, uid).collection("matches").doc("m-p0-05"); }
function fourthPatch() {
  return {
    cardLog: [entry("p1", cards[0]), entry("p2", cards[1]), entry("p3", cards[2]), entry("p4", cards[3])],
    lastCardSeat: "p4", turn: A, cardPhase: "RESOLVING", version: 5, updatedAt: new Date()
  };
}

(async function () {
  console.log("RULES_SHA256 " + RULES_SHA256);
  let env;
  try {
    env = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: { rules: RULES, host: "127.0.0.1", port: 8080 }
    });
  } catch (e) {
    console.log("STATUS BLOCKED — REAL EMULATOR UNAVAILABLE: " + e.message);
    process.exitCode = 2;
    return;
  }

  try {
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc("m-p0-05").set(baseMatch());
    });

    const fourthAccepted = await assertSucceeds(ref(env, D).update(fourthPatch()))
      .then(() => true).catch(() => false);
    console.log((fourthAccepted ? "PASS" : "FAIL") + " P0-05.1 fourth card accepted at RESOLVING boundary");

    const afterFourth = await ctx(env, A).collection("matches").doc("m-p0-05").get();
    const winnerPersisted = afterFourth.exists && afterFourth.data().turn === A && afterFourth.data().cardPhase === "RESOLVING";
    console.log((winnerPersisted ? "PASS" : "FAIL") + " P0-05.2 resolved winner UID persisted as turn with cardPhase RESOLVING");

    const duplicateFourth = await assertFails(ref(env, D).update(fourthPatch()))
      .then(() => true).catch(() => false);
    console.log((duplicateFourth ? "PASS" : "FAIL") + " P0-05.3 duplicate/replay of fourth card rejected");

    const wrongNext = await assertFails(ref(env, B).update({
      cardLog: fourthPatch().cardLog.concat([entry("p2", cards[4])]),
      lastCardSeat: "p2", turn: B, cardPhase: "PLAY", version: 6, updatedAt: new Date()
    })).then(() => true).catch(() => false);
    console.log((wrongNext ? "PASS" : "FAIL") + " P0-05.4 non-winner cannot submit the next trick card");

    const winnerNext = await assertSucceeds(ref(env, A).update({
      cardLog: fourthPatch().cardLog.concat([entry("p1", cards[4])]),
      lastCardSeat: "p1", turn: B, cardPhase: "PLAY", version: 6, updatedAt: new Date()
    })).then(() => true).catch(() => false);
    console.log((winnerNext ? "PASS" : "FAIL") + " P0-05.5 persisted winner can submit the next trick's first card");

    const checks = [fourthAccepted, winnerPersisted, duplicateFourth, wrongNext, winnerNext];
    const passed = checks.filter(Boolean).length;
    console.log("=== RESULTS ===");
    console.log(passed + " passed, " + (checks.length - passed) + " failed");
    if (passed !== checks.length) process.exitCode = 1;
  } finally {
    await env.cleanup();
  }
})();
