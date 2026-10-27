var REPO_ROOT = require("path").join(__dirname, "..");
// T-09 (Phase C) — rules-layer coverage for the fast Super Call CONFIRM.
//
// MatchService.submitBiddingAction() commits the engine's own wipe in the
// SAME Firestore write as the confirmation (bids/, biddingOpen, lastBidSeat,
// biddingCycle alongside the appended biddingLog entry), and the native
// client executes that transaction under these rules. This file proves
// against the REAL firestore.rules that the rules accept exactly that one
// extra shape and nothing wider:
//
//   P1. A FULL fast-round (14) confirm carrying the atomic reset -> ALLOWED.
//   P2. A LEGACY document with no `biddingCycle` (pre-T-09) -> the reset is
//       allowed and the cycle is read as 0 -> 1 (no rewrite required).
//   P3. A MINI fast-round (6) confirm -> ALLOWED (firstFastRoundOf()).
//   P4. An ordinary NORMAL-round Dash action (log-only append) -> ALLOWED,
//       proving the fast branch did not disturb the append-only shapes.
//   N1. A fast confirm WITHOUT the reset (log-only) -> DENIED: the round
//       would persist pre-confirm declarations the replay refuses.
//   N2. A reset that keeps another seat's declaration in bids/ -> DENIED.
//   N3. `biddingCycle` bumped by 2 instead of 1 -> DENIED.
//   N4. `bids[caller]` not equal to the confirmed number -> DENIED.
//   N5. A NORMAL-round confirm attempting the reset shape -> DENIED (the
//       append-only whitelist rejects the extra fields).
//   N6. A fast confirm below the 8-trick Super Call minimum -> DENIED.
//   N7. A fast reset that re-opens bidding with biddingOpen false -> DENIED.
//
// The rules remain a SHAPE gate: MatchService re-proves the gameplay
// (confirms.size == biddingCycle, the caller's own declaration, 8..13,
// strictly increasing cycles) inside its own transaction, and the engine
// re-validates every replay. This file pins only what rules can express.
//
// Methodology: tests/s61-timeout-counts.rules-emulator.test.cjs (same
// initializeTestEnvironment + assertSucceeds/assertFails pattern, seeded
// rules-disabled because that is the Admin SDK's own view of a write).
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

var pass = 0, fail = 0;
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
}

async function run() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-test-fast-super-call",
      firestore: {
        rules: fs.readFileSync(REPO_ROOT + "/firestore.rules", "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    console.log("EMULATOR UNREACHABLE (bootstrap): " + e.message);
    console.log("\n=== RESULTS ===\n");
    console.log("0 passed, 1 failed (emulator unreachable)");
    process.exitCode = 2;
    return;
  }

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";

  function baseMatch(overrides) {
    var m = {
      roomId: "room-x", players: [uidA, uidB, uidC, uidD], status: "starting", createdAt: 1,
      currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 },
      gameType: "FULL", scoringMode: "NORMAL", mode: "RANKED", currentMultiplier: 1
    };
    Object.assign(m, overrides || {});
    return m;
  }

  // The moment before a fast Super Call confirm: every seat declared (the
  // engine opens CONFIRM only then), p2 holds the winning 9, uidD declared
  // last, and the auction window is closed.
  function preConfirm(overrides) {
    return baseMatch(Object.assign({
      currentRound: 14, maxRounds: 18, version: 5, biddingOpen: false,
      bids: { p1: 8, p2: 9, p3: 3, p4: 2 }, lastBidSeat: uidD,
      gameState: { initialized: true, dealtRound: 14 }, biddingCycle: 0
    }, overrides || {}));
  }

  var seq = 0;
  async function seed(doc) {
    seq += 1;
    var id = "fsc-" + seq;
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(id).set(doc);
    });
    return id;
  }
  // A pre-T-09 document OMITS `biddingCycle` entirely (the field did not
  // exist when it was written); the reset must still be accepted, reading
  // the absent value as cycle 0.
  function legacyPreConfirm() {
    var doc = preConfirm();
    delete doc.biddingCycle;
    return doc;
  }
  function ref(uid, id) {
    return testEnv.authenticatedContext(uid).firestore()
      .collection("matches").doc(id);
  }

  function confirmEntry(round) {
    return { seatId: "p2", actionType: "SubmitConfirmCall", tricks: 9, suit: "SPADES", round: round };
  }
  // Everything the atomic reset writes, exactly as MatchService does.
  function confirmUpdate(round, overrides) {
    return Object.assign({
      biddingLog: [confirmEntry(round)],
      bids: { p2: 9 },
      biddingOpen: true,
      lastBidSeat: null,
      biddingCycle: 1,
      version: 6,
      updatedAt: new Date()
    }, overrides || {});
  }
  async function allowed(uid, id, data) {
    return assertSucceeds(ref(uid, id).update(data)).then(function () { return true; })
      .catch(function () { return false; });
  }
  async function denied(uid, id, data) {
    return assertFails(ref(uid, id).update(data)).then(function () { return true; })
      .catch(function () { return false; });
  }

  try {
    // ══════════════════════════════════════════════════════════════
    // P. The reset shape the service actually writes.
    // ══════════════════════════════════════════════════════════════
    var id = await seed(preConfirm());
    check("T09-P1 FULL round-14 confirm with the atomic reset -> ALLOWED",
      await allowed(uidB, id, confirmUpdate(14)));

    id = await seed(legacyPreConfirm());
    check("T09-P2 legacy doc without biddingCycle reads 0 -> 1 -> ALLOWED",
      await allowed(uidB, id, confirmUpdate(14)));

    id = await seed(preConfirm({ gameType: "MINI", currentRound: 6, maxRounds: 10, dealer: uidA }));
    check("T09-P3 MINI round-6 confirm with the atomic reset -> ALLOWED",
      await allowed(uidB, id, confirmUpdate(6)));

    id = await seed(baseMatch({
      currentRound: 3, version: 5, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null },
      gameState: { initialized: true, dealtRound: 3 }
    }));
    check("T09-P4 NORMAL-round Dash append-only write -> ALLOWED",
      await allowed(uidA, id, {
        biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: true, round: 3 }],
        version: 6, updatedAt: new Date()
      }));

    // ══════════════════════════════════════════════════════════════
    // N. Shapes the rules must refuse.
    // ══════════════════════════════════════════════════════════════
    id = await seed(preConfirm());
    check("T09-N1 fast confirm without the wipe (log-only) -> DENIED",
      await denied(uidB, id, {
        biddingLog: [confirmEntry(14)], version: 6, updatedAt: new Date()
      }));

    id = await seed(preConfirm());
    check("T09-N2 reset keeps another seat's declaration in bids/ -> DENIED",
      await denied(uidB, id, confirmUpdate(14, { bids: { p2: 9, p1: 8 } })));

    id = await seed(preConfirm());
    check("T09-N3 biddingCycle bumped by two -> DENIED",
      await denied(uidB, id, confirmUpdate(14, { biddingCycle: 2 })));

    id = await seed(preConfirm());
    check("T09-N4 bids/caller not the confirmed number -> DENIED",
      await denied(uidB, id, confirmUpdate(14, { bids: { p2: 8 } })));

    id = await seed(baseMatch({
      currentRound: 3, version: 5, biddingOpen: false,
      bids: { p1: 8, p2: 5, p3: 3, p4: 2 }, lastBidSeat: uidD,
      gameState: { initialized: true, dealtRound: 3 }
    }));
    check("T09-N5 NORMAL-round confirm cannot use the reset shape -> DENIED",
      await denied(uidB, id, confirmUpdate(3, {
        biddingLog: [{ seatId: "p2", actionType: "SubmitConfirmCall", tricks: 5, suit: "SANS", round: 3 }],
        bids: { p2: 5 }
      })));

    id = await seed(preConfirm({ bids: { p1: 8, p2: 7, p3: 3, p4: 2 } }));
    check("T09-N6 fast confirm below the 8-trick minimum -> DENIED",
      await denied(uidB, id, {
        biddingLog: [{ seatId: "p2", actionType: "SubmitConfirmCall", tricks: 7, suit: "SPADES", round: 14 }],
        bids: { p2: 7 }, biddingOpen: true, lastBidSeat: null, biddingCycle: 1,
        version: 6, updatedAt: new Date()
      }));

    id = await seed(preConfirm());
    check("T09-N7 reset that does not re-open bidding -> DENIED",
      await denied(uidB, id, confirmUpdate(14, { biddingOpen: false })));
  } finally {
    await testEnv.cleanup();
  }

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
}

run().catch(function (e) {
  console.log("HARNESS ERROR: " + (e && e.stack ? e.stack : e));
  process.exitCode = 1;
});
