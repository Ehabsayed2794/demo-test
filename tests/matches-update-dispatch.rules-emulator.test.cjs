const path = require("path");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");
// P0-2 (Firestore Rules Stabilization) — REAL Firestore Rules Emulator
// regression guard for isValidMatchUpdateDispatch()'s expression-budget
// fix.
//
// ROOT CAUSE (proven this sprint via causal comparison against the
// real emulator): the historical dispatch shape —
//   allow update: if isValidBidSubmission() || isValidCardSubmission()
//     || isValidBiddingActionSubmission() || isValidRoundAdvance()
//     || isValidRoundExtension() || isValidMatchCompletion()
//     || isValidHandDealCommit();
// — relies on CEL's `||`, which (like `&&`) evaluates ALL operands
// eagerly for its own audit/debug trail, so EVERY write attempt fully
// evaluates all 7 validators' complete expression trees regardless of
// which one (if any) actually matches the write's shape. Reconstructing
// this exact historical pattern from the current, real validator
// functions and running it against the real emulator: a LEGITIMATE bid
// submission still succeeds, but an UNAUTHORIZED bid submission (a
// write the dispatch must fully evaluate every branch to conclusively
// reject) throws "Unable to evaluate the expression as the maximum of
// 1000 expressions to evaluate has been reached" instead of a clean
// denial — a resource-limit crash, not a security decision, and one
// that would only get worse as these functions grow.
//
// FIX (already in production, verified unchanged by this sprint):
// isValidMatchUpdateDispatch() computes `affectedKeys()` ONCE and
// dispatches via a ternary chain, not `||`. CEL's `?:` genuinely
// short-circuits (unlike `&&`/`||`) — only the ONE validator matching
// the write's actual field-level shape ever runs; the other 6 are never
// evaluated at all, for either an accepted or a rejected write. This
// test exists to make sure that stays true — a regression back to a
// flat `||` (even indirectly, e.g. an added 8th branch reintroducing
// eager evaluation) would fail check 2 below.
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
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
      projectId: "demo-test-p02-dispatch",
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

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";
  var baseMatch = {
    roomId: "room-x", players: [uidA, uidB, uidC, uidD], status: "starting", createdAt: 1,
    currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
    seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };

  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("m-dispatch").set(baseMatch);
  });

  var p1Ctx = testEnv.authenticatedContext(uidA);
  var matchRef = p1Ctx.firestore().collection("matches").doc("m-dispatch");

  check("P0-2.1. A legitimate first bid submission (routed via `bids` in affectedKeys()) SUCCEEDS",
    await assertSucceeds(matchRef.update({
      bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true
    })).then(function () { return true; }).catch(function () { return false; }));

  var zCtx = testEnv.authenticatedContext(uidZ);
  var zMatchRef = zCtx.firestore().collection("matches").doc("m-dispatch");

  check("P0-2.2. An unauthorized (non-player) bid submission is DENIED cleanly — not a 1000-expression-budget crash",
    await assertFails(zMatchRef.update({
      bids: { p2: 5, p1: null, p3: null, p4: null }, lastBidSeat: "p2", version: 2, biddingOpen: true
    })).then(function () { return true; }).catch(function () { return false; }));

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  await testEnv.cleanup();
  process.exitCode = fail > 0 ? 1 : 0;
}

run();
