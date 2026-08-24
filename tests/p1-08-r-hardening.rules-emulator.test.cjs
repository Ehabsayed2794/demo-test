const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const { initializeTestEnvironment, assertSucceeds, assertFails } = require("@firebase/rules-unit-testing");

const REPO_ROOT = path.join(__dirname, "..");
const RULES_PATH = path.join(REPO_ROOT, "firestore.rules");
const RULES = fs.readFileSync(RULES_PATH, "utf8");
const RULES_HASH = crypto.createHash("sha256").update(RULES).digest("hex");

let passed = 0;
let failed = 0;
const failures = [];
function check(label, ok, note) {
  if (ok) {
    console.log("PASS  " + label);
    passed += 1;
  } else {
    console.log("FAIL  " + label + (note ? " -- " + note : ""));
    failed += 1;
    failures.push(label);
  }
}

const players = ["uidA", "uidB", "uidC", "uidD"];
const seats = { p1: "uidA", p2: "uidB", p3: "uidC", p4: "uidD" };
const nullBids = { p1: null, p2: null, p3: null, p4: null };
const card = { suit: "SPADES", rank: { v: 5, s: "5" } };
function handCards() {
  return Array.from({ length: 13 }, (_, i) => ({ suit: "SPADES", rank: { v: (i % 13) + 2, s: String((i % 13) + 2) } }));
}
function baseMatch(overrides) {
  return Object.assign({
    roomId: "room-p108",
    players: players.slice(),
    status: "starting",
    createdAt: 1,
    currentRound: 18,
    maxRounds: 18,
    extendedRounds: [],
    dealer: "uidA",
    turn: "uidA",
    seats: Object.assign({}, seats),
    version: 1,
    biddingOpen: true,
    bids: Object.assign({}, nullBids),
    lastBidSeat: null,
    cardLog: [],
    lastCardSeat: null,
    cardPhase: "PLAY",
    biddingLog: [],
    gameState: { initialized: true, dealtRound: 18 }
  }, overrides || {});
}
function completionPatch(winnerIds, finalScores, version) {
  return {
    status: "complete",
    winnerIds,
    finalScores,
    completedRound: 18,
    version: version || 2,
    updatedAt: 1
  };
}
function extensionPatch(rounds, maxRounds, version) {
  return { extendedRounds: rounds, maxRounds, version: version || 2, updatedAt: 1 };
}
function cardPatch(seatId, nextTurn, version) {
  return {
    cardLog: [{ seatId, card, round: 18 }],
    lastCardSeat: seatId,
    turn: nextTurn,
    cardPhase: "PLAY",
    version: version || 2,
    updatedAt: 1
  };
}
function actionEntry(seatId) {
  return { seatId, actionType: "SubmitDashCallDecision", round: 18, declaredDashCall: true };
}

async function main() {
  console.log("RULES_PATH " + RULES_PATH);
  console.log("RULES_SHA256 " + RULES_HASH);
  let env;
  try {
    env = await initializeTestEnvironment({
      projectId: "p1-08-r-hardening",
      firestore: { rules: RULES, host: "127.0.0.1", port: 8080 }
    });
  } catch (error) {
    console.log("STATUS BLOCKED — REAL EMULATOR UNAVAILABLE: " + error.message);
    process.exitCode = 2;
    return;
  }

  const ctx = (uid) => env.authenticatedContext(uid).firestore();
  const matchRef = (uid, id) => ctx(uid).collection("matches").doc(id);
  async function seed(id, overrides) {
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc(id).set(baseMatch(overrides));
    });
  }
  async function seedHand(id, seatId, round) {
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc(id).collection("hands").doc(seatId).set({
        seatId, round, cards: handCards(), version: round
      });
    });
  }

  try {
    // F7 positive liveness: unique winner, tie, negative cumulative values,
    // and a large legitimate integer score are all allowed without invented
    // numeric bounds.
    await seed("f7-unique", { currentRound: 18 });
    check("F7 positive unique winner with complete score map is allowed",
      await assertSucceeds(matchRef("uidA", "f7-unique").update(completionPatch(["p1"], { p1: 100, p2: 90, p3: 80, p4: 70 })))
        .then(() => true).catch(() => false));

    await seed("f7-tie", { currentRound: 18 });
    check("F7 positive exact two-way tie is allowed",
      await assertSucceeds(matchRef("uidB", "f7-tie").update(completionPatch(["p1", "p3"], { p1: 50, p2: -20, p3: 50, p4: -100 })))
        .then(() => true).catch(() => false));

    await seed("f7-negative-scores", { currentRound: 18 });
    check("F7 positive negative cumulative scores and large integer are allowed",
      await assertSucceeds(matchRef("uidC", "f7-negative-scores").update(completionPatch(["p4"], { p1: -1000, p2: -500, p3: -750, p4: 250000 })))
        .then(() => true).catch(() => false));

    // F7 negative consistency boundaries.
    const invalidWinnerCases = [
      ["wrong winner", ["p2"], { p1: 100, p2: 90, p3: 80, p4: 70 }],
      ["missing tied winner", ["p1"], { p1: 50, p2: 0, p3: 50, p4: -1 }],
      ["extra non-winner", ["p1", "p2"], { p1: 100, p2: 90, p3: 80, p4: 70 }],
      ["invalid winner UID", ["p9"], { p1: 100, p2: 90, p3: 80, p4: 70 }],
      ["duplicate winner entry", ["p1", "p1"], { p1: 100, p2: 90, p3: 80, p4: 70 }]
    ];
    for (let i = 0; i < invalidWinnerCases.length; i += 1) {
      const [label, winners, scores] = invalidWinnerCases[i];
      const id = "f7-invalid-winner-" + i;
      await seed(id, { currentRound: 18 });
      check("F7 negative " + label + " is denied",
        await assertFails(matchRef("uidA", id).update(completionPatch(winners, scores)))
          .then(() => true).catch(() => false));
    }

    await seed("f7-missing-score", { currentRound: 18 });
    check("F7 negative missing required roster score key is denied",
      await assertFails(matchRef("uidA", "f7-missing-score").update(completionPatch(["p1"], { p1: 100, p2: 90, p3: 80 })))
        .then(() => true).catch(() => false));

    await seed("f7-invalid-score-type", { currentRound: 18 });
    check("F7 negative non-integer score value is denied without a numeric bound",
      await assertFails(matchRef("uidA", "f7-invalid-score-type").update(completionPatch(["p1"], { p1: "100", p2: 90, p3: 80, p4: 70 })))
        .then(() => true).catch(() => false));

    await seed("f7-four-way-tie", { currentRound: 18 });
    check("F7 positive exact four-way integer tie is allowed",
      await assertSucceeds(matchRef("uidD", "f7-four-way-tie").update(completionPatch(["p1", "p2", "p3", "p4"], { p1: -7, p2: -7, p3: -7, p4: -7 })))
        .then(() => true).catch(() => false));

    const invalidNumericCases = [
      ["one fractional score", ["p1"], { p1: 100.5, p2: 90, p3: 80, p4: 70 }],
      ["all fractional scores", ["p1"], { p1: 100.5, p2: 90.25, p3: 80.75, p4: 70.125 }],
      ["one NaN score", ["p2"], { p1: NaN, p2: 90, p3: 80, p4: 70 }],
      ["all NaN scores with empty winnerIds", [], { p1: NaN, p2: NaN, p3: NaN, p4: NaN }],
      ["mixed integer and NaN scores", ["p2"], { p1: 100, p2: NaN, p3: 80, p4: 70 }],
      ["infinite score", ["p1"], { p1: Infinity, p2: 90, p3: 80, p4: 70 }]
    ];
    for (let i = 0; i < invalidNumericCases.length; i += 1) {
      const [label, winners, scores] = invalidNumericCases[i];
      const id = "f7-invalid-numeric-" + i;
      await seed(id, { currentRound: 18 });
      check("F7 negative " + label + " is denied",
        await assertFails(matchRef("uidA", id).update(completionPatch(winners, scores)))
          .then(() => true).catch(() => false));
    }

    // F4 schema-faithful extension records are the repository's flat list
    // of completed round integers; the reason is a service/engine input and
    // is not persisted in the current Firestore schema.
    await seed("f4-super-call", { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("F4 positive valid Super Call-shaped round-14 extension is allowed",
      await assertSucceeds(matchRef("uidB", "f4-super-call").update(extensionPatch([14], 19)))
        .then(() => true).catch(() => false));

    await seed("f4-saayda", { currentRound: 18, maxRounds: 18, extendedRounds: [] });
    check("F4 positive valid Sa'ayda-shaped round-18 extension is allowed",
      await assertSucceeds(matchRef("uidC", "f4-saayda").update(extensionPatch([18], 19)))
        .then(() => true).catch(() => false));

    await seed("f4-retry", { currentRound: 16, maxRounds: 19, extendedRounds: [14] });
    check("F4 positive later extension preserves prior records and increments once",
      await assertSucceeds(matchRef("uidA", "f4-retry").update(extensionPatch([14, 16], 20)))
        .then(() => true).catch(() => false));

    const invalidExtensionCases = [
      ["decrement maxRounds", [14], 17],
      ["duplicate same-round extension", [14, 14], 19],
      ["invalid string record", ["14"], 19],
      ["malformed map record", [{ round: 14, reason: "SUPER_CALL" }], 19],
      ["invalid reason field not present in repository schema", [14], 19],
      ["unrelated field mutation", [14], 19]
    ];
    for (let i = 0; i < invalidExtensionCases.length; i += 1) {
      const [label, rounds, maxRounds] = invalidExtensionCases[i];
      const id = "f4-invalid-" + i;
      await seed(id, { currentRound: 14, maxRounds: 18, extendedRounds: [] });
      const patch = extensionPatch(rounds, maxRounds);
      if (label === "invalid reason field not present in repository schema") patch.reason = "ADMIN";
      if (label === "unrelated field mutation") patch.status = "complete";
      check("F4 negative " + label + " is denied",
        await assertFails(matchRef("uidA", id).update(patch))
          .then(() => true).catch(() => false));
    }

    // Terminal immutability must be tested against every parent-match update
    // discriminator, not a separate restrictive allow that OR semantics
    // would bypass.
    await seed("terminal-bid", { status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18 });
    check("Terminal immutability: bid path is denied after completion",
      await assertFails(matchRef("uidA", "terminal-bid").update({ bids: { p1: 5, p2: null, p3: null, p4: null }, biddingOpen: true, lastBidSeat: "p1", version: 2, updatedAt: 1 }))
        .then(() => true).catch(() => false));

    await seed("terminal-card", { status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18, turn: "uidA" });
    check("Terminal immutability: card path is denied after completion",
      await assertFails(matchRef("uidA", "terminal-card").update(cardPatch("p1", "uidB")))
        .then(() => true).catch(() => false));

    await seed("terminal-action", { status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18 });
    check("Terminal immutability: bidding-action path is denied after completion",
      await assertFails(matchRef("uidA", "terminal-action").update({ biddingLog: [actionEntry("p1")], version: 2, updatedAt: 1 }))
        .then(() => true).catch(() => false));

    await seed("terminal-round", { status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18 });
    check("Terminal immutability: round-advance path remains denied after completion",
      await assertFails(matchRef("uidA", "terminal-round").update({ currentRound: 19, dealer: "uidB", biddingOpen: true, bids: Object.assign({}, nullBids), lastBidSeat: null, cardPhase: null, turn: null, version: 2, updatedAt: 1 }))
        .then(() => true).catch(() => false));

    await seed("terminal-extension", { status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18 });
    check("Terminal immutability: extension path remains denied after completion",
      await assertFails(matchRef("uidA", "terminal-extension").update(extensionPatch([14], 19)))
        .then(() => true).catch(() => false));

    await seed("terminal-opening", { status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18, turn: null, cardPhase: null });
    check("Terminal immutability: opening-turn publication path is denied after completion",
      await assertFails(matchRef("uidA", "terminal-opening").update({ turn: "uidA", cardPhase: "PLAY", version: 2, updatedAt: 1 }))
        .then(() => true).catch(() => false));

    // Hand documents are part of the match lifecycle. A terminal parent
    // must not accept a late redeal through the separate hands allow block.
    await seed("terminal-hand", { status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18 });
    await seedHand("terminal-hand", "p1", 17);
    check("Terminal immutability: hand redeal path is denied after completion",
      await assertFails(ctx("uidA").collection("matches").doc("terminal-hand").collection("hands").doc("p1").update({ seatId: "p1", round: 18, cards: handCards(), version: 18 }))
        .then(() => true).catch(() => false));
  } finally {
    await env.cleanup();
  }

  console.log("\n=== RESULTS ===\n");
  console.log(passed + " passed, " + failed + " failed");
  if (failures.length) console.log(failures.map((x) => "- " + x).join("\n"));
  process.exitCode = failed ? 1 : 0;
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});

module.exports = {};
