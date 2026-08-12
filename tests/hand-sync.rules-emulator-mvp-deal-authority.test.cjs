// Sprint E — Hand Write Authority Security Redesign (MVP DECISION):
// REAL Firestore Rules Emulator verification of the zero-cost,
// rules-only deal-authority architecture.
//
// This file exercises EXACTLY the 13 scenarios the MVP decision
// requires, plus the full production-shaped 4-hand + gameState
// transaction dealRound() actually performs. It is a NEW, dedicated
// file — it does not replace or weaken any existing hand-sync
// emulator suite (hand-sync.rules-emulator.test.cjs stays at 32/32,
// unchanged in spirit even though its D16/D18/E20 checks were updated
// this sprint to reflect the new, intentionally-required transaction
// pairing).
//
// KNOWN MVP LIMITATION (accepted, not hidden — see Deal Authority
// Security Architecture Report and docs/architecture/
// SecurityArchitecture.md): these rules prove authorization, shape,
// round/state validity, and atomic pairing — they CANNOT prove that
// `cards` was genuinely produced by Dealer.dealHands() or that the
// shuffle was fair. A malicious seated match member could still
// choose shape-valid card contents for any seat, including
// opponents', while satisfying every check this file verifies. This
// is explicitly NOT cheat-proof.
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

var pass = 0, fail = 0;
function check(label, ok) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function outcome(p) {
  return p.then(function () { return true; }).catch(function () { return false; });
}

async function run() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-test-mvp-deal-authority",
      firestore: {
        rules: fs.readFileSync("/home/user/demo-test/firestore.rules", "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    console.log("EMULATOR NOT REACHABLE — " + e.message);
    console.log("\n=== RESULTS ===\n");
    console.log("0 passed, 0 failed (SKIPPED — no emulator connection)");
    process.exitCode = 2;
    return;
  }
  await testEnv.clearFirestore();

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";

  function baseMatch(overrides) {
    var base = {
      roomId: "room-x", players: [uidA, uidB, uidC, uidD], status: "starting",
      createdAt: 1, currentRound: 1, maxRounds: 18, extendedRounds: [],
      dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD },
      version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    };
    return Object.assign({}, base, overrides || {});
  }
  function fullHand() {
    var cards = [];
    ["SPADES", "HEARTS", "DIAMONDS"].forEach(function (suit) {
      for (var v = 2; v <= 5; v++) cards.push({ suit: suit, rank: { v: v, s: String(v) } });
    });
    cards.push({ suit: "CLUBS", rank: { v: 14, s: "A" } });
    return cards; // 13
  }
  async function seed(matchId, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(baseMatch(overrides));
    });
  }
  function realDealTransaction(db, matchId, round, seatIds) {
    return db.runTransaction(async function (tx) {
      var matchRef = db.collection("matches").doc(matchId);
      (seatIds || ["p1", "p2", "p3", "p4"]).forEach(function (seatId) {
        tx.set(matchRef.collection("hands").doc(seatId), { seatId: seatId, round: round, version: round, cards: fullHand() });
      });
      tx.update(matchRef, { gameState: { initialized: true, dealtRound: round } });
    });
  }

  // 1. Real 4-hand deal transaction -> ALLOW (the exact production
  //    dealRound() shape: 4 hand creates + 1 gameState update, ONE caller).
  await seed("m1-real-deal");
  check("1. Real 4-hand deal transaction (production dealRound() shape) -> ALLOW",
    await outcome(assertSucceeds(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m1-real-deal", 1))));

  // 2/3. Own hand read ALLOW, opponent hand read DENY.
  await seed("m2-read");
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m2-read").collection("hands").doc("p1").set({ seatId: "p1", round: 1, version: 1, cards: fullHand() });
    await ctx.firestore().collection("matches").doc("m2-read").collection("hands").doc("p2").set({ seatId: "p2", round: 1, version: 1, cards: fullHand() });
  });
  check("2. Own hand read -> ALLOW",
    await outcome(assertSucceeds(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m2-read").collection("hands").doc("p1").get())));
  check("3. Opponent hand read -> DENY",
    await outcome(assertFails(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m2-read").collection("hands").doc("p2").get())));

  // 4. Direct hand write (standalone, no paired deal transaction) -> DENY.
  await seed("m4-direct-write");
  check("4. Direct hand write (standalone, no gameState pairing) -> DENY",
    await outcome(assertFails(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m4-direct-write")
      .collection("hands").doc("p1").set({ seatId: "p1", round: 1, version: 1, cards: fullHand() }))));

  // 5. Lone hand write (only ONE seat written, paired with a gameState
  //    update that claims ALL FOUR occupied seats are dealt) -> DENY.
  //    isValidHandDealCommit()'s getAfter() checks on p2/p3/p4 fail since
  //    those docs never get created in this transaction.
  await seed("m5-lone-hand");
  check("5. Lone hand write (1 of 4 occupied seats, paired gameState claims full deal) -> DENY",
    await outcome(assertFails(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m5-lone-hand", 1, ["p1"]))));

  // 6. Fake seat -> DENY.
  await seed("m6-fake-seat");
  var db6 = testEnv.authenticatedContext(uidA).firestore();
  check("6. Fake seat (hands/p5) -> DENY",
    await outcome(assertFails(db6.runTransaction(async function (tx) {
      var matchRef = db6.collection("matches").doc("m6-fake-seat");
      tx.set(matchRef.collection("hands").doc("p5"), { seatId: "p5", round: 1, version: 1, cards: fullHand() });
      tx.update(matchRef, { gameState: { initialized: true, dealtRound: 1 } });
    }))));

  // 7. Wrong round (hand round doesn't match parent's currentRound) -> DENY.
  await seed("m7-wrong-round");
  var db7 = testEnv.authenticatedContext(uidA).firestore();
  check("7. Wrong round (hand round != currentRound) -> DENY",
    await outcome(assertFails(db7.runTransaction(async function (tx) {
      var matchRef = db7.collection("matches").doc("m7-wrong-round");
      tx.set(matchRef.collection("hands").doc("p1"), { seatId: "p1", round: 5, version: 5, cards: fullHand() });
      tx.update(matchRef, { gameState: { initialized: true, dealtRound: 5 } });
    }))));

  // 8. Replay/duplicate (re-dealing an already-dealt round) -> DENY.
  await seed("m8-replay");
  await assertSucceeds(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m8-replay", 1));
  check("8. Replay/duplicate (re-deal the SAME already-dealt round) -> DENY",
    await outcome(assertFails(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m8-replay", 1))));

  // 9. Direct dealtRound manipulation (no paired hand writes) -> DENY.
  await seed("m9-direct-dealtround");
  check("9. Direct dealtRound manipulation (standalone gameState update) -> DENY",
    await outcome(assertFails(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m9-direct-dealtround")
      .update({ gameState: { initialized: true, dealtRound: 1 } }))));

  // 10. Unauthorized match member (not in players) -> DENY.
  await seed("m10-unauthorized");
  check("10. Unauthorized match member (uidZ not in players) -> DENY",
    await outcome(assertFails(realDealTransaction(testEnv.authenticatedContext(uidZ).firestore(), "m10-unauthorized", 1))));

  // 11. Malformed hand (bad shape) -> DENY.
  await seed("m11-malformed");
  var db11 = testEnv.authenticatedContext(uidA).firestore();
  check("11. Malformed hand (12 cards instead of 13) -> DENY",
    await outcome(assertFails(db11.runTransaction(async function (tx) {
      var matchRef = db11.collection("matches").doc("m11-malformed");
      tx.set(matchRef.collection("hands").doc("p1"), { seatId: "p1", round: 1, version: 1, cards: fullHand().slice(0, 12) });
      tx.update(matchRef, { gameState: { initialized: true, dealtRound: 1 } });
    }))));

  // 12. Valid Round 2 redeal (forward transition, real paired transaction) -> ALLOW.
  await seed("m12-round2-redeal");
  await assertSucceeds(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m12-round2-redeal", 1));
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m12-round2-redeal").update({ currentRound: 2 });
  });
  check("12. Valid Round 2 redeal (forward transition, real 4-hand transaction) -> ALLOW",
    await outcome(assertSucceeds(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m12-round2-redeal", 2))));

  // 13. Invalid skipped round (Round 1 -> Round 3, currentRound only 2) -> DENY.
  await seed("m13-skipped-round");
  await assertSucceeds(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m13-skipped-round", 1));
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m13-skipped-round").update({ currentRound: 2 });
  });
  check("13. Invalid skipped round (Round 1 -> Round 3, currentRound is only 2) -> DENY",
    await outcome(assertFails(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m13-skipped-round", 3))));

  // Bonus: the exact production shape called out explicitly by the MVP
  // decision — hands/p1..p4 + matches/{matchId}.gameState, ONE atomic
  // transaction, non-owner-of-3-of-4-seats caller (matches
  // MatchService.dealRound()'s real write byte-for-byte).
  await seed("m14-production-shape");
  check("14. Production-shaped transaction: hands/p1..p4 + gameState.dealtRound, ONE atomic transaction -> ALLOW",
    await outcome(assertSucceeds(realDealTransaction(testEnv.authenticatedContext(uidA).firestore(), "m14-production-shape", 1))));

  await testEnv.cleanup();

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
}

run().catch(function (e) {
  console.error("FATAL:", e);
  process.exitCode = 1;
});
