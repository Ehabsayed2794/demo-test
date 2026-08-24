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

function baseMatch(players, seats, overrides) {
  const bids = {};
  Object.keys(seats).forEach((seat) => { bids[seat] = null; });
  return Object.assign({
    roomId: "room-f6",
    players: players.slice(),
    status: "starting",
    createdAt: 1,
    currentRound: 1,
    maxRounds: 18,
    extendedRounds: [],
    dealer: players[0],
    turn: players[0],
    seats: Object.assign({}, seats),
    version: 1,
    biddingOpen: true,
    bids,
    lastBidSeat: null,
    cardLog: [],
    lastCardSeat: null,
    cardPhase: null,
    biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  }, overrides || {});
}

function advancePatch(newDealer, version) {
  return {
    currentRound: 2,
    dealer: newDealer,
    version: version || 2,
    biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null },
    lastBidSeat: null,
    cardPhase: null,
    turn: null,
    updatedAt: 1
  };
}

async function main() {
  console.log("RULES_PATH " + RULES_PATH);
  console.log("RULES_SHA256 " + RULES_HASH);
  let env;
  try {
    env = await initializeTestEnvironment({
      projectId: "p1-08-r-dealer",
      firestore: { rules: RULES, host: "127.0.0.1", port: 8080 }
    });
  } catch (error) {
    console.log("STATUS BLOCKED — REAL EMULATOR UNAVAILABLE: " + error.message);
    process.exitCode = 2;
    return;
  }

  const A = "uidA", B = "uidB", C = "uidC", D = "uidD", Z = "uidZ";
  const players4 = [A, B, C, D];
  const seats4 = { p1: A, p2: B, p3: C, p4: D };
  const ctx = (uid) => env.authenticatedContext(uid).firestore();
  const ref = (uid, id) => ctx(uid).collection("matches").doc(id);
  async function seed(id, players, seats, overrides) {
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc(id).set(baseMatch(players, seats, overrides));
    });
  }
  async function seedReadyRoom(id, roomPlayers) {
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("rooms").doc(id).set({
        creator: roomPlayers[0], players: roomPlayers.slice(), readyPlayers: roomPlayers.slice(),
        status: "waiting", name: "f6-test-room", createdAt: 1
      });
    });
  }
  async function createMatchFromRoom(roomId, matchId, matchPlayers, matchSeats) {
    const db = ctx(matchPlayers[0]);
    const matchRef = db.collection("matches").doc(matchId);
    const roomRef = db.collection("rooms").doc(roomId);
    const doc = baseMatch(matchPlayers, matchSeats, { roomId });
    return db.runTransaction(async (tx) => {
      tx.set(matchRef, doc);
      tx.update(roomRef, { status: "in_game", matchId });
    });
  }

  try {
    // Positive canonical transitions for every supported four-seat position,
    // including the p4 -> p1 wraparound.
    const transitions = [[A, B], [B, C], [C, D], [D, A]];
    for (let i = 0; i < transitions.length; i += 1) {
      const [oldDealer, newDealer] = transitions[i];
      const id = "f6-positive-" + i;
      await seed(id, players4, seats4, { dealer: oldDealer });
      check("F6 positive canonical transition " + oldDealer + " -> " + newDealer,
        await assertSucceeds(ref(oldDealer, id).update(advancePatch(newDealer)))
          .then(() => true).catch(() => false));
    }

    // The repository also supports a real under-four-player seat map. Its
    // active-seat order is p1..pN and wraps among only present seats.
    const players2 = [A, B];
    const seats2 = { p1: A, p2: B };
    await seed("f6-two-p1-p2", players2, seats2, { dealer: A });
    check("F6 positive two-player transition p1 -> p2",
      await assertSucceeds(ref(A, "f6-two-p1-p2").update(advancePatch(B)))
        .then(() => true).catch(() => false));
    await seed("f6-two-p2-p1", players2, seats2, { dealer: B });
    check("F6 positive two-player wraparound p2 -> p1",
      await assertSucceeds(ref(B, "f6-two-p2-p1").update(advancePatch(A)))
        .then(() => true).catch(() => false));

    const players3 = [A, B, C];
    const seats3 = { p1: A, p2: B, p3: C };
    await seed("f6-three-p3-p1", players3, seats3, { dealer: C });
    check("F6 positive three-player wraparound p3 -> p1",
      await assertSucceeds(ref(C, "f6-three-p3-p1").update(advancePatch(A)))
        .then(() => true).catch(() => false));
    const players1 = [A];
    const seats1 = { p1: A };
    await seed("f6-one-p1-p1", players1, seats1, { dealer: A });
    check("F6 positive one-player self-successor p1 -> p1",
      await assertSucceeds(ref(A, "f6-one-p1-p1").update(advancePatch(A)))
        .then(() => true).catch(() => false));

    // Each invalid choice is a real roster UID, so membership-only rules are
    // insufficient and these are the core pre-fix failure cases.
    const invalid = [
      ["skip", A, C],
      ["repeat", B, B],
      ["backward", C, B],
      ["non-roster", D, Z]
    ];
    for (let i = 0; i < invalid.length; i += 1) {
      const [name, oldDealer, newDealer] = invalid[i];
      const id = "f6-negative-" + name;
      await seed(id, players4, seats4, { dealer: oldDealer });
      check("F6 negative " + name + " dealer choice is denied",
        await assertFails(ref(oldDealer, id).update(advancePatch(newDealer)))
          .then(() => true).catch(() => false));
    }

    // The invalid choice must remain denied even when all other transition
    // fields are otherwise valid.
    await seed("f6-negative-only-dealer", players4, seats4, { dealer: A });
    check("F6 negative wrong dealer with otherwise-valid round advance is denied",
      await assertFails(ref(A, "f6-negative-only-dealer").update(advancePatch(D)))
        .then(() => true).catch(() => false));

    // Creation-time seat validation must accept exactly the contiguous shapes
    // emitted by MatchService.buildSeatMap(), including legitimate under-four
    // player matches, and must reject sparse shapes before gameplay starts.
    const creationShapes = [
      ["one", [A], { p1: A }],
      ["two", [A, B], { p1: A, p2: B }],
      ["three", [A, B, C], { p1: A, p2: B, p3: C }],
      ["four", players4, seats4]
    ];
    for (let i = 0; i < creationShapes.length; i += 1) {
      const [name, shapePlayers, shapeSeats] = creationShapes[i];
      const roomId = "f6-create-room-" + name;
      await seedReadyRoom(roomId, shapePlayers);
      check("F6 creation positive contiguous " + name + "-seat shape is allowed",
        await assertSucceeds(createMatchFromRoom(roomId, "f6-create-match-" + name, shapePlayers, shapeSeats))
          .then(() => true).catch(() => false));
    }

    const sparsePlayers = [A, B];
    const sparseSeats = { p2: A, p4: B };
    await seedReadyRoom("f6-create-room-sparse", sparsePlayers);
    check("F6 creation negative sparse p2/p4 seat shape is denied",
      await assertFails(createMatchFromRoom("f6-create-room-sparse", "f6-create-match-sparse", sparsePlayers, sparseSeats))
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
