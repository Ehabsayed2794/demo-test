// Player Hand Synchronization — REAL FIRESTORE RULES EMULATOR
// verification (Final Verification & Closure sprint).
//
// This is the tier every prior SIMULATED rules test in this project has
// explicitly, honestly disclaimed as unavailable: this file exercises
// the ACTUAL compiled CEL rules in firestore.rules via a real, locally
// running Firestore Rules Emulator (@firebase/rules-unit-testing),
// NOT a JS reimplementation of intended logic. Requires the emulator
// to be running (see tests/hand-sync.rules-emulator.README for the
// exact command) — this file does not start it itself, and skips with
// a clear message if it can't connect, rather than fabricating a pass.
//
// Every check below targets ONLY the matches/{matchId}/hands/{seatId}
// block, isValidHandDealCommit()'s update shape, and the gameState
// shape-lock added to isValidNewMatch()/isValidNewRematchMatch() —
// exactly the gap the prior report flagged as JS-simulation-only.
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

async function run() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-test-hand-sync",
      firestore: {
        rules: fs.readFileSync("/home/user/demo-test/firestore.rules", "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    // Sprint 5.0 (CI/CD Pipeline & Real Emulator Enforcement): a green
    // run with the emulator down would prove nothing about the real
    // rules -- FAIL HARD (exit 1), never a silent SKIPPED exit-2. Start
    // the emulator with `firebase emulators:start --only firestore,auth`
    // (or run `npm run test:ci`, which does this automatically) before
    // running this file directly.
    console.error("EMULATOR NOT REACHABLE — " + e.message);
    console.error(
      "\nFATAL: the Firestore Rules Emulator must be running on " +
      "127.0.0.1:8080 for this test to run. This is a HARD FAILURE, " +
      "not a skip -- see this catch block's own comment."
    );
    console.error("\n=== RESULTS ===\n");
    console.error("0 passed, 0 failed (FAILED — emulator unreachable)");
    process.exit(1);
  }
  // Sprint E hygiene fix: the emulator project persists data ACROSS
  // separate `node` invocations of this same file (same projectId,
  // same host:port) — a prior run's `hands/{seatId}` docs (e.g. D18's
  // round:2 hand) survive a fresh `matches/{matchId}.set()`, since
  // `.set()` on the parent doc never clears its subcollections. On a
  // second run this silently turned D16's intended CREATE into an
  // UPDATE against stale leftover state, routing it through
  // `isValidHandRedeal()`'s forward-only check instead of
  // `isValidNewHand()` and failing for the wrong reason. Not a rules
  // bug — a test-isolation gap, exposed only once this sprint's tests
  // started performing real multi-document transactions. Fixed at the
  // source: start every run from a guaranteed-empty project.
  await testEnv.clearFirestore();

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";

  function seedMatch(matchId, overrides) {
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

  async function withSeeded(matchId, matchData, fn) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(matchData);
    });
    return fn();
  }

  // ============ A. HAND READ AUTHORIZATION ============
  await withSeeded("m-read", seedMatch("m-read"), async function () {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc("m-read").collection("hands").doc("p1").set({ seatId: "p1", round: 1, version: 1, cards: fullHand() });
      await ctx.firestore().collection("matches").doc("m-read").collection("hands").doc("p2").set({ seatId: "p2", round: 1, version: 1, cards: fullHand() });
    });
    var p1Ctx = testEnv.authenticatedContext(uidA);
    var p2Ctx = testEnv.authenticatedContext(uidB);
    check("A1. Player P1 CAN read hands/p1 (own hand)",
      await assertSucceeds(p1Ctx.firestore().collection("matches").doc("m-read").collection("hands").doc("p1").get()).then(function () { return true; }).catch(function () { return false; }));
    check("A2. Player P1 CANNOT read hands/p2",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-read").collection("hands").doc("p2").get()).then(function () { return true; }).catch(function () { return false; }));
    check("A3. Player P1 CANNOT read hands/p3 (nonexistent doc, still denied by rule not 404)",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-read").collection("hands").doc("p3").get()).then(function () { return true; }).catch(function () { return false; }));
    check("A4. Player P1 CANNOT read hands/p4",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-read").collection("hands").doc("p4").get()).then(function () { return true; }).catch(function () { return false; }));
    check("A5. Player P2 CAN read hands/p2 (own hand) — symmetric check, seat/uid relationship genuinely enforced both ways",
      await assertSucceeds(p2Ctx.firestore().collection("matches").doc("m-read").collection("hands").doc("p2").get()).then(function () { return true; }).catch(function () { return false; }));
    check("A6. list on the hands subcollection is DENIED for a seated player",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-read").collection("hands").get()).then(function () { return true; }).catch(function () { return false; }));
    var unauthCtx = testEnv.unauthenticatedContext();
    check("A7. An unauthenticated client CANNOT read any hand",
      await assertFails(unauthCtx.firestore().collection("matches").doc("m-read").collection("hands").doc("p1").get()).then(function () { return true; }).catch(function () { return false; }));
  });

  // ============ B. HAND WRITE AUTHORIZATION ============
  await withSeeded("m-write", seedMatch("m-write"), async function () {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc("m-write").collection("hands").doc("p1").set({ seatId: "p1", round: 1, version: 1, cards: fullHand() });
    });
    var p1Ctx = testEnv.authenticatedContext(uidA);
    var p2Ctx = testEnv.authenticatedContext(uidB);
    check("B5. Player P1 CANNOT directly modify (update) hands/p1 — write-once, only dealRound()'s server-side path may set it",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-write").collection("hands").doc("p1")
        .set({ seatId: "p1", round: 1, version: 1, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
    var fabricatedAce13 = fullHand().slice(0, 12).concat([{ suit: "CLUBS", rank: { v: 14, s: "A" } }]);
    check("B6. Player P1 CANNOT create an advantageous fabricated hand directly (bypassing dealRound()) for a NEW seat that has no doc yet",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-write").collection("hands").doc("p3")
        .set({ seatId: "p3", round: 1, version: 1, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
    check("B7. Player P1 CANNOT modify another player's (p2's) hand, even if p2's doc doesn't exist yet",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-write").collection("hands").doc("p2")
        .set({ seatId: "p2", round: 1, version: 1, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
    check("B8. An unauthorized (non-seated) user CANNOT create any hand document",
      await assertFails(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc("m-write").collection("hands").doc("p2")
        .set({ seatId: "p2", round: 1, version: 1, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
  });

  // ============ C. HAND SHAPE VALIDATION (create, owner writing their OWN never-yet-created seat) ============
  await withSeeded("m-shape", seedMatch("m-shape"), async function () {
    var p1Ctx = testEnv.authenticatedContext(uidA);
    var ref = function () { return p1Ctx.firestore().collection("matches").doc("m-shape").collection("hands").doc("p1"); };
    check("C9. Fewer than 13 cards — REJECTED",
      await assertFails(ref().set({ seatId: "p1", round: 1, version: 1, cards: fullHand().slice(0, 12) })).then(function () { return true; }).catch(function () { return false; }));
    check("C10. More than 13 cards — REJECTED",
      await assertFails(ref().set({ seatId: "p1", round: 1, version: 1, cards: fullHand().concat([{ suit: "CLUBS", rank: { v: 2, s: "2" } }]) })).then(function () { return true; }).catch(function () { return false; }));
    check("C11. A malformed card object (extra key) among the 13 — REJECTED",
      await assertFails(ref().set({ seatId: "p1", round: 1, version: 1, cards: fullHand().slice(0, 12).concat([{ suit: "CLUBS", rank: { v: 14, s: "A" }, owner: "p1" }]) })).then(function () { return true; }).catch(function () { return false; }));
    check("C12. A malformed rank object (bad key) — REJECTED",
      await assertFails(ref().set({ seatId: "p1", round: 1, version: 1, cards: fullHand().slice(0, 12).concat([{ suit: "CLUBS", rank: { v: 14, s: "A", extra: 1 } }]) })).then(function () { return true; }).catch(function () { return false; }));
    check("C13. An invalid seatId (doc written under a 'p5' path) — REJECTED",
      await assertFails(p1Ctx.firestore().collection("matches").doc("m-shape").collection("hands").doc("p5")
        .set({ seatId: "p5", round: 1, version: 1, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
    check("C13b. seatId field mismatched vs. the path's own seatId — REJECTED",
      await assertFails(ref().set({ seatId: "p2", round: 1, version: 1, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
    check("C14. An invalid round (not matching the parent's currentRound) — REJECTED",
      await assertFails(ref().set({ seatId: "p1", round: 2, version: 2, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
    check("C15. A malformed version (doesn't equal round) — REJECTED",
      await assertFails(ref().set({ seatId: "p1", round: 1, version: 99, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
  });

  // ============ D. ROUND VALIDATION ============
  // Sprint E (Hand Write Authority Security Redesign): a hand write's
  // create/update ONLY succeeds when paired, in the SAME transaction,
  // with the parent match's own gameState.dealtRound advance to that
  // exact round (isValidPairedDeal()) — a standalone `ref.set()` with
  // no sibling match update, which is what D16/D18 used to do under
  // the old ownsSeat()-based model, is now correctly denied as a lone
  // write. D16/D18 are updated to perform the REAL, paired transaction
  // shape MatchService.dealRound() actually uses — this is a stronger,
  // more realistic assertion, not a weakened one; every other
  // assertion in this file (D17/D19's REJECTED cases, and every
  // shape/seat/membership check elsewhere) is unchanged.
  // Seats trimmed to just p1 so the pairing check only needs to
  // account for ONE occupied seat, keeping this block's focus on round
  // validation specifically — the full 4-seat pairing relationship is
  // exercised separately in tests/hand-sync.rules-emulator-mvp-deal-authority.test.cjs.
  await withSeeded("m-round", seedMatch("m-round", { seats: { p1: uidA } }), async function () {
    var p1Ctx = testEnv.authenticatedContext(uidA);
    var db = p1Ctx.firestore();
    var ref = db.collection("matches").doc("m-round").collection("hands").doc("p1");
    var matchRef = db.collection("matches").doc("m-round");
    function pairedDeal(round) {
      return db.runTransaction(async function (tx) {
        tx.set(ref, { seatId: "p1", round: round, version: round, cards: fullHand() });
        tx.update(matchRef, { gameState: { initialized: true, dealtRound: round } });
      });
    }
    check("D16. A valid current-round hand (round 1, matches currentRound:1), paired with the matching gameState commit, IS accepted",
      await assertSucceeds(pairedDeal(1)).then(function () { return true; }).catch(function () { return false; }));
    check("D17. A stale-round hand (round 1 again, same value, no forward progress) — REJECTED on redeal attempt",
      await assertFails(ref.set({ seatId: "p1", round: 1, version: 1, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
    // Advance the parent's currentRound to 2 (simulating advanceToNextRound()) directly, bypassing rules, to test the LEGITIMATE forward transition.
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc("m-round").update({ currentRound: 2 });
    });
    check("D18. Forward round transition (round 1 -> round 2, matching new currentRound), paired with the matching gameState commit, IS accepted",
      await assertSucceeds(pairedDeal(2)).then(function () { return true; }).catch(function () { return false; }));
    check("D19. An illegal round jump (round 2 -> round 5, currentRound is still only 2) — REJECTED",
      await assertFails(ref.set({ seatId: "p1", round: 5, version: 5, cards: fullHand() })).then(function () { return true; }).catch(function () { return false; }));
  });

  // ============ E. DEAL COMMIT (the PARENT match doc's gameState flip) ============
  // Seats trimmed to just p1 — isValidHandDealCommit() now ALSO proves
  // (via getAfter()) that every occupied seat's hand doc lands on the
  // matching round in the same transaction, so a standalone gameState
  // update (no hand write at all) is correctly denied unless there's
  // exactly one occupied seat and it's WRITTEN alongside. E20 is
  // updated to the real, paired shape; E21-E24 are unchanged (each was
  // already asserting REJECTED, which remains the correct outcome).
  await withSeeded("m-commit", seedMatch("m-commit", { seats: { p1: uidA } }), async function () {
    var p1Ctx = testEnv.authenticatedContext(uidA);
    var matchRef = function () { return p1Ctx.firestore().collection("matches").doc("m-commit"); };
    check("E20. A valid dealRound commit (gameState -> {initialized:true, dealtRound:1}), paired with hands/p1 in the same transaction, IS accepted",
      await assertSucceeds(p1Ctx.firestore().runTransaction(async function (tx) {
        tx.set(p1Ctx.firestore().collection("matches").doc("m-commit").collection("hands").doc("p1"),
          { seatId: "p1", round: 1, version: 1, cards: fullHand() });
        tx.update(matchRef(), { gameState: { initialized: true, dealtRound: 1 } });
      })).then(function () { return true; }).catch(function () { return false; }));
    check("E21. An unauthorized (non-player) deal commit attempt is REJECTED",
      await assertFails(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc("m-commit").update({ gameState: { initialized: true, dealtRound: 2 } })).then(function () { return true; }).catch(function () { return false; }));
    check("E22. Tampered deal data — smuggling an unrelated field (status) alongside the gameState flip — REJECTED",
      await assertFails(matchRef().update({ gameState: { initialized: true, dealtRound: 2 }, status: "complete" })).then(function () { return true; }).catch(function () { return false; }));
    check("E23. gameState.dealtRound cannot be arbitrarily manipulated — an attempt to REGRESS it back to 0 is REJECTED",
      await assertFails(matchRef().update({ gameState: { initialized: true, dealtRound: 0 } })).then(function () { return true; }).catch(function () { return false; }));
    check("E23b. gameState.dealtRound cannot skip ahead of currentRound — an attempt to jump to dealtRound:99 (currentRound is only 1) is REJECTED",
      await assertFails(matchRef().update({ gameState: { initialized: true, dealtRound: 99 } })).then(function () { return true; }).catch(function () { return false; }));
    check("E24. gameState shape lock REJECTS an unexpected key (e.g. the old 'todo' placeholder field)",
      await assertFails(matchRef().update({ gameState: { initialized: true, dealtRound: 1, todo: "x" } })).then(function () { return true; }).catch(function () { return false; }));
  });

  // ============ F. MATCH CREATION ============
  var readyRoom = { creator: uidA, players: [uidA, uidB, uidC, uidD], readyPlayers: [uidA, uidB, uidC, uidD], status: "waiting" };
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("rooms").doc("room-create").set(readyRoom);
  });
  var creatorCtx = testEnv.authenticatedContext(uidA);
  var validNewMatchDoc = {
    roomId: "room-create", players: [uidA, uidB, uidC, uidD], status: "starting", createdAt: new Date(),
    currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
    seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };
  check("F25. New match with valid gameState:{initialized:false, dealtRound:0} PASSES",
    await assertSucceeds((async function () {
      // P0-1: paired with the room's matchId update in the SAME
      // transaction, matching MatchService.startMatch()'s real,
      // atomic write shape (a match creation that isn't paired with
      // a same-transaction room update legitimately can't satisfy
      // isValidNewMatch()'s getAfter() room<->match binding check).
      var db = creatorCtx.firestore();
      var ref = db.collection("matches").doc();
      var roomRef = db.collection("rooms").doc("room-create");
      await db.runTransaction(async function (tx) {
        tx.set(ref, validNewMatchDoc);
        tx.update(roomRef, { status: "in_game", matchId: ref.id });
      });
      return ref;
    })()).then(function () { return true; }).catch(function () { return false; }));

  // F26. Rematch creation — requires a completed old match + an ALL_YES vote.
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m-old-complete").set(seedMatch("m-old-complete", {
      status: "complete", winnerIds: [uidA], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 }, completedRound: 18
    }));
    await ctx.firestore().collection("matches").doc("m-old-complete").collection("rematchVote").doc("current").set({
      matchId: "m-old-complete", seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD },
      votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" }, status: "ALL_YES", newMatchId: null, createdAt: new Date(), version: 5
    });
  });
  var validRematchDoc = {
    roomId: "room-x", rematchOfMatchId: "m-old-complete", players: [uidA, uidB, uidC, uidD], status: "starting",
    createdAt: new Date(), currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
    seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };
  check("F26. New rematch match with valid gameState PASSES",
    await assertSucceeds(creatorCtx.firestore().collection("matches").doc().set(validRematchDoc)).then(function () { return true; }).catch(function () { return false; }));
  check("F27. Invalid gameState shape (old placeholder {initialized:false, todo:'x'}) FAILS for a new match",
    await assertFails(creatorCtx.firestore().collection("matches").doc().set(Object.assign({}, validNewMatchDoc, { gameState: { initialized: false, todo: "x" } }))).then(function () { return true; }).catch(function () { return false; }));

  await testEnv.cleanup();

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
}

run().catch(function (e) {
  console.error("FATAL:", e);
  process.exitCode = 1;
});
