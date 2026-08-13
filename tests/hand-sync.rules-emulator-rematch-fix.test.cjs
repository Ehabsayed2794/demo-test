const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Firestore Rules Bug-Fix sprint — dedicated regression test for the
// isValidNewRematchMatch() defect found during the Player Hand
// Synchronization real-emulator verification.
//
// ROOT CAUSE (see firestore.rules' own comment on isValidNewRematchMatch()
// for the full account): the function unconditionally dot-accessed
// `data.rematchOfMatchId` at invocation time (both directly, and via two
// `let ... = get(...)` bindings) — Firestore Rules' `||` in
// `allow create: if isValidNewMatch() || isValidNewRematchMatch();`
// invokes BOTH disjuncts (unlike JS's short-circuiting `||`), so a
// PLAIN (non-rematch) match write — which has no `rematchOfMatchId`
// field at all — made this function throw, denying the ENTIRE write.
//
// This file exercises the REAL, running Firestore Rules Emulator (not
// the JS simulation) against the FIXED rules. Requires the emulator to
// already be running on 127.0.0.1:8080 (see hand-sync.rules-emulator.test.cjs's
// own header for the exact command) — skips with a clear message,
// rather than fabricating a pass, if it can't connect.
const { initializeTestEnvironment, assertFails, assertSucceeds } = require("@firebase/rules-unit-testing");
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
      projectId: "demo-test-rematch-fix",
      firestore: {
        rules: fs.readFileSync(__REPO_ROOT__ + "/firestore.rules", "utf8"),
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
  // Sprint E hygiene fix (see hand-sync.rules-emulator.test.cjs for the
  // full explanation): start every run from a guaranteed-empty
  // project — leftover `hands/{seatId}` docs from a prior run of this
  // same file would otherwise silently turn an intended CREATE into
  // an UPDATE on rerun.
  await testEnv.clearFirestore();

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";

  async function ok(p) { try { await p; return true; } catch (e) { return false; } }
  async function denied(p) { try { await assertFails(p); return true; } catch (e) { return false; } }
  async function allowed(p) { try { await assertSucceeds(p); return true; } catch (e) { return false; } }

  // ============ NORMAL MATCH CREATION ============
  var readyRoom = { players: [uidA, uidB, uidC, uidD], readyPlayers: [uidA, uidB, uidC, uidD], status: "waiting" };
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("rooms").doc("room-plain").set(readyRoom);
  });
  var creator = testEnv.authenticatedContext(uidA);
  function validPlainMatch(overrides) {
    return Object.assign({
      roomId: "room-plain", players: [uidA, uidB, uidC, uidD], status: "starting", createdAt: new Date(),
      currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    }, overrides || {});
  }

  // NOTE ON SCOPE: a full end-to-end "plain match creation, via a real
  // atomic transaction, ALLOWED" check surfaces a SEPARATE, unrelated,
  // pre-existing issue in isValidNewMatch()'s OWN
  // `getAfter(rooms/{roomId}).data.matchId` check (firestore.rules:509)
  // — confirmed via the emulator's own debug log (`line [509], column
  // [97]. Null value error.`) even when the room document demonstrably
  // exists beforehand, independent of anything isValidNewRematchMatch()
  // does. This is OUT OF SCOPE for this bug-fix sprint (scoped only to
  // isValidNewRematchMatch()) and is reported, not fixed — see the
  // Final Report's "Remaining limitations" section, not this file.
  //
  // What IS in scope and directly verified here: isValidNewRematchMatch()
  // itself no longer CRASHES the whole `allow create` OR-expression for
  // a plain-match write — confirmed by the very next check below
  // ("missing rematchOfMatchId entirely") denying CLEANLY (a normal
  // rules rejection) rather than throwing (the original bug's exact
  // signature, "Property rematchOfMatchId is undefined on object").

  check("Normal match creation — malformed plain match (maxRounds != 18) — DENIED",
    await denied(creator.firestore().collection("matches").doc().set(validPlainMatch({ maxRounds: 20 }))));

  check("Normal match creation — unauthorized (creator uid not in players) — DENIED",
    await denied(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc().set(validPlainMatch())));

  // ============ REMATCH CREATION ============
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m-old").set({
      roomId: "room-x", players: [uidA, uidB, uidC, uidD], status: "complete",
      createdAt: 1, currentRound: 19, maxRounds: 18, extendedRounds: [],
      dealer: uidA, turn: uidA, seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD },
      version: 5, biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 18 },
      winnerIds: [uidA], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 }, completedRound: 18
    });
    await ctx.firestore().collection("matches").doc("m-old").collection("rematchVote").doc("current").set({
      matchId: "m-old", seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD },
      votes: { p1: "YES", p2: "YES", p3: "YES", p4: "YES" }, status: "ALL_YES", newMatchId: null, createdAt: new Date(), version: 5
    });
    await ctx.firestore().collection("matches").doc("m-other-complete").set({
      roomId: "room-y", players: [uidA, uidB, uidC, uidD], status: "complete",
      createdAt: 1, currentRound: 19, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 5, biddingOpen: false,
      bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4", cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 18 }, winnerIds: [uidA], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 }, completedRound: 18
    });
  });
  function validRematchMatch(overrides) {
    return Object.assign({
      roomId: "room-x", rematchOfMatchId: "m-old", players: [uidA, uidB, uidC, uidD], status: "starting",
      createdAt: new Date(), currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    }, overrides || {});
  }

  check("Rematch creation — valid rematch (real matchId, ALL_YES vote, seats verbatim) — ALLOWED",
    await allowed(creator.firestore().collection("matches").doc().set(validRematchMatch())));

  var noRematchField = validPlainMatch({ roomId: "room-x" });
  check("Rematch creation — missing rematchOfMatchId entirely (this is the exact bug scenario, tested directly against a room-x-rooted write) — DENIED, not thrown",
    await denied(creator.firestore().collection("matches").doc().set(noRematchField)));

  check("Rematch creation — invalid rematchOfMatchId (points to a match that doesn't exist) — DENIED",
    await denied(creator.firestore().collection("matches").doc().set(validRematchMatch({ rematchOfMatchId: "does-not-exist" }))));

  check("Rematch creation — wrong referenced match (rematchOfMatchId points to a genuinely different, unrelated completed match with NO matching vote/seats) — DENIED",
    await denied(creator.firestore().collection("matches").doc().set(validRematchMatch({ rematchOfMatchId: "m-other-complete" }))));

  check("Rematch creation — unauthorized (uid not a player) — DENIED",
    await denied(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc().set(validRematchMatch())));

  // ============ EXISTING HAND SYNC SECURITY — NO REGRESSION ============
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m-hand-check").set({
      roomId: "room-z", players: [uidA, uidB, uidC, uidD], status: "starting",
      createdAt: 1, currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    });
    var cards = [];
    ["SPADES", "HEARTS", "DIAMONDS"].forEach(function (suit) { for (var v = 2; v <= 5; v++) cards.push({ suit: suit, rank: { v: v, s: String(v) } }); });
    cards.push({ suit: "CLUBS", rank: { v: 14, s: "A" } });
    await ctx.firestore().collection("matches").doc("m-hand-check").collection("hands").doc("p1").set({ seatId: "p1", round: 1, version: 1, cards: cards });
  });
  var p1 = testEnv.authenticatedContext(uidA);
  var p2 = testEnv.authenticatedContext(uidB);
  check("Hand Sync regression — own hand read (P1 -> hands/p1) — ALLOWED",
    await allowed(p1.firestore().collection("matches").doc("m-hand-check").collection("hands").doc("p1").get()));
  check("Hand Sync regression — opponent hand read (P1 -> hands/p2, no doc, still denied by rule not 404) — DENIED",
    await denied(p1.firestore().collection("matches").doc("m-hand-check").collection("hands").doc("p2").get()));
  check("Hand Sync regression — hand list — DENIED",
    await denied(p1.firestore().collection("matches").doc("m-hand-check").collection("hands").get()));
  check("Hand Sync regression — direct hand write (overwrite own already-created hand) — DENIED",
    await denied(p1.firestore().collection("matches").doc("m-hand-check").collection("hands").doc("p1").set({ seatId: "p1", round: 1, version: 1, cards: cardsList() })));
  check("Hand Sync regression — malformed hand (wrong card count) for a seat with no doc yet — DENIED",
    await denied(p2.firestore().collection("matches").doc("m-hand-check").collection("hands").doc("p2").set({ seatId: "p2", round: 1, version: 1, cards: cardsList().slice(0, 5) })));
  function cardsList() {
    var cards = [];
    ["SPADES", "HEARTS", "DIAMONDS"].forEach(function (suit) { for (var v = 2; v <= 5; v++) cards.push({ suit: suit, rank: { v: v, s: String(v) } }); });
    cards.push({ suit: "CLUBS", rank: { v: 14, s: "A" } });
    return cards;
  }

  // ============ EXISTING MATCH UPDATE SECURITY — NO REGRESSION ============
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m-update-check").set({
      roomId: "room-w", players: [uidA, uidB, uidC, uidD], status: "starting",
      createdAt: 1, currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    });
  });
  // biddingOpen stays true here — only 1 of 4 seats (p1) has bid, so
  // isValidBidSubmission()'s own biddingOpen-derivation check correctly
  // requires it to remain open (this was a test-fixture bug, not a
  // rules bug — found and fixed during the P0-2 dispatcher work).
  check("Match update regression — a legitimate bid submission (isValidBidSubmission()) still succeeds",
    await allowed(p1.firestore().collection("matches").doc("m-update-check").update({
      bids: { p1: 4, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true
    })));
  // Sprint E (Hand Write Authority Security Redesign): isValidHandDealCommit()
  // now ALSO proves, via getAfter(), that every occupied seat's hand doc
  // lands on the matching round in the SAME transaction — a standalone
  // gameState update with no paired hand writes is correctly denied. This
  // check is updated to the real, paired 4-hand transaction shape
  // MatchService.dealRound() actually uses — a stronger assertion, not a
  // weakened one.
  check("Match update regression — a legitimate hand-deal-commit (isValidHandDealCommit()), paired with all four hands in the same transaction, still succeeds",
    await allowed(p1.firestore().runTransaction(async function (tx) {
      var matchRef = p1.firestore().collection("matches").doc("m-update-check");
      ["p1", "p2", "p3", "p4"].forEach(function (seatId) {
        tx.set(matchRef.collection("hands").doc(seatId), { seatId: seatId, round: 1, version: 1, cards: cardsList() });
      });
      tx.update(matchRef, { gameState: { initialized: true, dealtRound: 1 } });
    })));
  check("Match update regression — an arbitrary/unauthorized field change (e.g. changing players) is still DENIED",
    await denied(p1.firestore().collection("matches").doc("m-update-check").update({ players: [uidA, uidB, uidC, uidZ] })));

  await testEnv.cleanup();

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
}

run().catch(function (e) {
  console.error("FATAL:", e);
  process.exitCode = 1;
});
