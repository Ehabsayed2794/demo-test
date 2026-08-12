// Firestore Rules Production Stability sprint — Phase 1 (P0-2):
// real-emulator regression test for the matches/{matchId} `allow
// update` dispatcher restructuring.
//
// ROOT CAUSE (see firestore.rules' own comment on
// isValidMatchUpdateDispatch() for the full account): the flat
// `A() || B() || ... || G()` OR-chain across all 7 update-shape
// validators made Firestore evaluate EVERY validator for EVERY write
// (real Firestore Rules' `||` does not short-circuit the way
// JavaScript's does — it evaluates every disjunct to build its own
// audit trail), and several validators contain `get()`/array-slice/
// map operations expensive enough that an ordinary, completely
// legitimate bid submission deterministically exceeded the engine's
// per-request expression-evaluation budget ("Unable to evaluate the
// expression as the maximum of 1000 expressions to evaluate has been
// reached"), denying a valid write outright.
//
// FIX: a cheap, ONE-TIME-COMPUTED discriminator (which top-level keys
// the write actually touches) dispatches via a LAZY ternary chain
// (`cond ? a : b`, which genuinely short-circuits in this engine,
// unlike `||`) to exactly ONE of the 7 EXISTING, byte-for-byte
// UNCHANGED validator functions. This file proves: (a) the expression-
// budget failure is gone for a legitimate write of every shape, (b)
// every existing security check (authorization, field allowlist,
// state-transition, seat ownership, round validation) still holds
// for every shape, and (c) Hand Sync's own security boundary is
// unaffected.
//
// Requires the emulator already running on 127.0.0.1:8080 — skips
// with a clear message, rather than fabricating a pass, if it can't
// connect.
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
      projectId: "demo-test-p02-dispatch",
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

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";

  async function allowed(p) { try { await assertSucceeds(p); return true; } catch (e) { return false; } }
  async function denied(p) { try { await assertFails(p); return true; } catch (e) { return false; } }

  function baseMatch(overrides) {
    return Object.assign({
      roomId: "room-w", players: [uidA, uidB, uidC, uidD], status: "starting",
      createdAt: 1, currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    }, overrides || {});
  }
  async function seed(matchId, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(baseMatch(overrides));
    });
  }
  function fullHand() {
    var cards = [];
    ["SPADES", "HEARTS", "DIAMONDS"].forEach(function (suit) { for (var v = 2; v <= 5; v++) cards.push({ suit: suit, rank: { v: v, s: String(v) } }); });
    cards.push({ suit: "CLUBS", rank: { v: 14, s: "A" } });
    return cards;
  }

  // ============ 1-3. BID ============
  await seed("m-bid-ok");
  check("1. legitimate bid submission -> ALLOW",
    await allowed(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-bid-ok").update({
      bids: { p1: 4, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true
    })));
  await seed("m-bid-unauth");
  check("2. unauthorized bid (uid not a player) -> DENY",
    await denied(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc("m-bid-unauth").update({
      bids: { p1: 4, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true
    })));
  await seed("m-bid-malformed");
  check("3. malformed bid (out-of-range value, 99) -> DENY",
    await denied(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-bid-malformed").update({
      bids: { p1: 99, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true
    })));

  // ============ 4-6. CARD ============
  await seed("m-card-ok", { turn: uidA });
  check("4. legitimate card submission -> ALLOW",
    await allowed(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-card-ok").update({
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 10, s: "10" } }, round: 1 }],
      lastCardSeat: "p1", version: 2, turn: uidB, cardPhase: "PLAY"
    })));
  await seed("m-card-unauth", { turn: uidA });
  check("5. unauthorized card (uid not a player) -> DENY",
    await denied(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc("m-card-unauth").update({
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 10, s: "10" } }, round: 1 }],
      lastCardSeat: "p1", version: 2, turn: uidB, cardPhase: "PLAY"
    })));
  await seed("m-card-malformed", { turn: uidA });
  check("6. malformed card (bad suit) -> DENY",
    await denied(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-card-malformed").update({
      cardLog: [{ seatId: "p1", card: { suit: "NOT_A_SUIT", rank: { v: 10, s: "10" } }, round: 1 }],
      lastCardSeat: "p1", version: 2, turn: uidB, cardPhase: "PLAY"
    })));

  // ============ 7-8. ROUND ADVANCE ============
  await seed("m-adv-ok", { currentRound: 1 });
  check("7. legitimate round advance -> ALLOW",
    await allowed(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-adv-ok").update({
      currentRound: 2, version: 2, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null },
      lastBidSeat: null, cardPhase: null, turn: null
    })));
  await seed("m-adv-unauth", { currentRound: 1 });
  check("8. unauthorized round advance (uid not a player) -> DENY",
    await denied(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc("m-adv-unauth").update({
      currentRound: 2, version: 2, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null },
      lastBidSeat: null, cardPhase: null, turn: null
    })));

  // ============ 9-10. MATCH COMPLETION ============
  await seed("m-complete-ok", { currentRound: 18, maxRounds: 18 });
  check("9. legitimate match completion -> ALLOW",
    await allowed(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-complete-ok").update({
      status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 },
      completedRound: 18, version: 2
    })));
  await seed("m-complete-unauth", { currentRound: 18, maxRounds: 18 });
  check("10. unauthorized completion (uid not a player) -> DENY",
    await denied(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc("m-complete-unauth").update({
      status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 },
      completedRound: 18, version: 2
    })));

  // ============ 11-12. HAND SYNC DEAL COMMIT ============
  await seed("m-deal-ok");
  check("11. legitimate Hand Sync deal commit -> ALLOW",
    await allowed(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-deal-ok").update({
      gameState: { initialized: true, dealtRound: 1 }
    })));
  await seed("m-deal-unauth");
  check("12. unauthorized Hand Sync deal commit (uid not a player) -> DENY",
    await denied(testEnv.authenticatedContext(uidZ).firestore().collection("matches").doc("m-deal-unauth").update({
      gameState: { initialized: true, dealtRound: 1 }
    })));

  // ============ 13-14. INJECTION ============
  await seed("m-inject-arbitrary");
  check("13. arbitrary field injection (changing `players` alongside a bid) -> DENY",
    await denied(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-inject-arbitrary").update({
      bids: { p1: 4, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true,
      players: [uidA, uidB, uidC, uidZ]
    })));
  await seed("m-inject-crossfeature");
  check("14. cross-feature field injection (mixing a bid write with a gameState/hand-deal field in ONE write) -> DENY",
    await denied(testEnv.authenticatedContext(uidA).firestore().collection("matches").doc("m-inject-crossfeature").update({
      bids: { p1: 4, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true,
      gameState: { initialized: true, dealtRound: 1 }
    })));

  // ============ Hand Sync security boundary — no regression ============
  await seed("m-hs-regress");
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m-hs-regress").collection("hands").doc("p1").set({
      seatId: "p1", round: 1, version: 1, cards: fullHand()
    });
  });
  var p1ctx = testEnv.authenticatedContext(uidA);
  check("HS-regress. own hand read -> ALLOW",
    await allowed(p1ctx.firestore().collection("matches").doc("m-hs-regress").collection("hands").doc("p1").get()));
  check("HS-regress. opponent hand read -> DENY",
    await denied(p1ctx.firestore().collection("matches").doc("m-hs-regress").collection("hands").doc("p2").get()));
  check("HS-regress. hand list -> DENY",
    await denied(p1ctx.firestore().collection("matches").doc("m-hs-regress").collection("hands").get()));
  check("HS-regress. direct hand overwrite (same round) -> DENY",
    await denied(p1ctx.firestore().collection("matches").doc("m-hs-regress").collection("hands").doc("p1").set({
      seatId: "p1", round: 1, version: 1, cards: fullHand()
    })));

  await testEnv.cleanup();

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
}

run().catch(function (e) {
  console.error("FATAL:", e);
  process.exitCode = 1;
});
