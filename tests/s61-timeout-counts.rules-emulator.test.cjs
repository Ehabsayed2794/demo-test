var REPO_ROOT = require("path").join(__dirname, "..");
// S61 Stage 2 — rules-layer coverage for `matches/{matchId}/timeoutCounts/{uid}`.
//
// Proves four claims against the REAL compiled firestore.rules (not a JS
// mirror, not the resolver's own server-side guards):
//
//   R1. The counter is owner-read-only in the strict sense the design
//       requires: BOTH gates conjoined — the doc's own uid (ownership) AND
//       the reader still being listed in the parent match's `players`
//       (membership). Neither alone suffices.
//   R2. A different seated player may NOT read another seat's count — the
//       whole reason the count lives in a per-uid subcollection instead of
//       on the match document (which every seated player can read).
//   R3. An unauthenticated read is denied.
//   W1-W4. No client may create, update, delete, or list these docs. The
//       resolver writes them through the Admin SDK, which bypasses rules;
//       every client mutation path is closed here rather than trusted to
//       the client never trying — a client deleting its own count would
//       reset its own 15-timeout removal threshold.
//
// Non-goals (covered elsewhere, deliberately not duplicated here):
//   - the count's type/range validation and the 15-cap are enforced
//     fail-closed by the resolver itself (functions/TimeoutResolution.kt,
//     TimeoutCounter.fromStored / nextCount), which is the only place that
//     can enforce them since no client write path exists at all;
//   - the removal write's atomicity is a transactional property of the
//     callable, exercised by the Functions emulator suite, not by rules.
//
// Methodology: exact shape of tests/p2-negative-trust-boundary.rules-emulator
// .test.cjs (initializeTestEnvironment + assertSucceeds/assertFails, seeded
// counter docs via withSecurityRulesDisabled, which is how the Admin-SDK
// resolver's own bypass is modelled here). Seed shapes mirror
// baseMatch() in that file. This file never prints the word the runner
// hard-fails on; an unreachable emulator is a hard failure with a
// different message.
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

var pass = 0, fail = 0;
var findings = [];
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; findings.push({ label: label, note: note }); }
}

async function run() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-test-s61-timeout-counts",
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

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";

  function baseMatch(overrides) {
    var m = {
      roomId: "room-x", players: [uidA, uidB, uidC, uidD], status: "starting", createdAt: 1,
      currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: uidA,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 1, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    };
    Object.assign(m, overrides || {});
    return m;
  }

  // The counter doc as the resolver writes it: one field, `count`. Seeded
  // rules-disabled because that is exactly the Admin SDK's own view of the
  // write (rules do not apply to admin), so a seed is the faithful model.
  async function seedCounter(matchId, uid, count) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId)
        .collection("timeoutCounts").doc(uid).set({ count: count });
    });
  }

  // The parent match a counter hangs under. isOwnerInMatch()'s membership
  // conjunct reads this document, so every positive case needs a REAL
  // parent or the read fails closed — which is R5's whole point, and the
  // reason the negative cases there seed no parent on purpose.
  async function seedMatch(matchId, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(baseMatch(overrides || {}));
    });
  }

  function ctxFor(uid) { return testEnv.authenticatedContext(uid); }
  function counterRef(uid, matchId, counterUid) {
    return ctxFor(uid).firestore().collection("matches").doc(matchId)
      .collection("timeoutCounts").doc(counterUid);
  }

  // ══════════════════════════════════════════════════════════════
  // R. Reads — owner-read-only, with BOTH gates load-bearing.
  // ══════════════════════════════════════════════════════════════
  {
    var m = "s61-r";

    // R1: the positive case. uidA reads its OWN counter under a match it is
    // listed in — the only combination the rules allow.
    await seedMatch(m);
    await seedCounter(m, uidA, 3);
    check("S61-R1 owner reads own timeout count under own match -> ALLOWED",
      await assertSucceeds(counterRef(uidA, m, uidA).get())
        .then(function () { return true; }).catch(function () { return false; }));

    // R1b: the count's VALUE is not what gates the read — a counter sitting
    // exactly at the removal threshold (15) is still owner-readable, so a
    // player about to be removed can see their own final count.
    await seedMatch(m + "-15");
    await seedCounter(m + "-15", uidA, 15);
    check("S61-R1b owner reads own count at the removal threshold -> ALLOWED",
      await assertSucceeds(counterRef(uidA, m + "-15", uidA).get())
        .then(function () { return true; }).catch(function () { return false; }));

    // R2: a co-seated player may not read another seat's count. uidB is
    // legitimately seated in this match — the denial is the OWNERSHIP gate,
    // never membership.
    await seedCounter(m + "-other", uidA, 3);
    check("S61-R2 a seated player reads ANOTHER seat's count -> DENIED",
      await assertFails(counterRef(uidB, m + "-other", uidA).get())
        .then(function () { return true; }).catch(function () { return false; }));

    // R3: no auth at all.
    await seedCounter(m + "-anon", uidA, 3);
    check("S61-R3 unauthenticated read of any timeout count -> DENIED",
      await assertFails(testEnv.unauthenticatedContext().firestore()
        .collection("matches").doc(m + "-anon")
        .collection("timeoutCounts").doc(uidA).get())
        .then(function () { return true; }).catch(function () { return false; }));

    // R4: the membership gate is load-bearing, not dead code. uidZ owns a
    // counter doc (ownership alone satisfied) under a match whose `players`
    // does not list it — denied. This is the case that would break if the
    // `in parentMatch().players` conjunct were dropped.
    await seedCounter(m + "-nonmember", uidZ, 3);
    check("S61-R4 owner reads own count under a match they are NOT in -> DENIED",
      await assertFails(counterRef(uidZ, m + "-nonmember", uidZ).get())
        .then(function () { return true; }).catch(function () { return false; }));

    // R5: a counter under a match whose PARENT document does not exist.
    // parentMatch() then reads a non-existent doc; rules treat its data as
    // null and the membership test fails closed — no counter is ever
    // readable without a real, readable parent.
    await seedCounter(m + "-orphan", uidA, 3);
    check("S61-R5 owner reads own count under a MISSING parent match -> DENIED",
      await assertFails(counterRef(uidA, "match-never-seeded", uidA).get())
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // W. Writes — every client mutation path is closed.
  // ══════════════════════════════════════════════════════════════
  {
    var m = "s61-w";
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(m).set(baseMatch({}));
    });

    // W1: create — a client forging its own first count.
    check("S61-W1 client creates its own timeout count -> DENIED",
      await assertFails(counterRef(uidA, m, uidA).set({ count: 0 }))
        .then(function () { return true; }).catch(function () { return false; }));

    // W2: update — a client editing an existing count.
    await seedCounter(m, uidA, 14);
    check("S61-W2 client updates its own timeout count -> DENIED",
      await assertFails(counterRef(uidA, m, uidA).set({ count: 14 }, { merge: true }))
        .then(function () { return true; }).catch(function () { return false; }));

    // W3: delete — the attack that matters most: a client erasing its own
    // count to reset the 15-timeout removal threshold for itself.
    await seedCounter(m + "-del", uidA, 15);
    check("S61-W3 client DELETES its own timeout count -> DENIED",
      await assertFails(counterRef(uidA, m + "-del", uidA).delete())
        .then(function () { return true; }).catch(function () { return false; }));

    // W4: list — enumerating the subcollection would return every seat's
    // count at once, defeating the per-player split.
    await seedCounter(m + "-list", uidA, 3);
    await seedCounter(m + "-list", uidB, 7);
    check("S61-W4 client LISTS the timeoutCounts subcollection -> DENIED",
      await assertFails(ctxFor(uidA).firestore().collection("matches").doc(m + "-list")
        .collection("timeoutCounts").get())
        .then(function () { return true; }).catch(function () { return false; }));

    // W5: a non-owner client is denied too — the write prohibition is not
    // an ownership question at all (no client may write, owner included).
    check("S61-W5 client writes ANOTHER seat's timeout count -> DENIED",
      await assertFails(counterRef(uidB, m, uidA).set({ count: 0 }))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  if (findings.length) {
    console.log("\n=== FAILURES (for triage) ===");
    findings.forEach(function (f) { console.log("- " + f.label); });
  }
  await testEnv.cleanup();
  process.exitCode = fail > 0 ? 1 : 0;
}

run();
