// Reusable P0-1 bisection reproduction harness.
// Usage: node scratchpad_p01_repro.cjs <rulesFilePath> <uniqueTag>
const { initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const fs = require("fs");
const { Timestamp } = require("firebase/firestore");

const rulesPath = process.argv[2];
const tag = process.argv[3] || "default";

(async () => {
  const testEnv = await initializeTestEnvironment({
    projectId: "demo-test-p01-" + tag,
    firestore: {
      rules: fs.readFileSync(rulesPath, "utf8"),
      host: "127.0.0.1",
      port: 8080
    }
  });

  const uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";
  const roomId = "room-" + tag;

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("rooms").doc(roomId).set({
      creator: uidA,
      players: [uidA, uidB, uidC, uidD],
      readyPlayers: [uidA, uidB, uidC, uidD],
      status: "waiting",
      name: "Test Room",
      createdAt: Timestamp.now()
    });
  });

  const creatorCtx = testEnv.authenticatedContext(uidA);

  function buildInitialMatchDoc(rid, players) {
    var seats = { p1: players[0], p2: players[1], p3: players[2], p4: players[3] };
    return {
      roomId: rid, players: players, status: "starting", createdAt: Timestamp.now(),
      currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: players[0], turn: players[0],
      seats: seats, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    };
  }

  let caughtError = null;
  try {
    await creatorCtx.firestore().runTransaction(async (tx) => {
      const roomRef = creatorCtx.firestore().collection("rooms").doc(roomId);
      const roomSnap = await tx.get(roomRef);
      const room = roomSnap.data();
      const players = room.players;
      const newMatchRef = creatorCtx.firestore().collection("matches").doc();
      const doc = buildInitialMatchDoc(roomId, players);
      tx.set(newMatchRef, doc);
      tx.update(roomRef, { status: "in_game", matchId: newMatchRef.id, updatedAt: Timestamp.now() });
    });
    console.log("[" + tag + "] RESULT: SUCCEEDED");
  } catch (e) {
    caughtError = e;
    console.log("[" + tag + "] RESULT: THREW -> " + e.message.replace(/\n/g, " "));
  }

  await testEnv.cleanup();
  process.exitCode = caughtError ? 1 : 0;
})();
