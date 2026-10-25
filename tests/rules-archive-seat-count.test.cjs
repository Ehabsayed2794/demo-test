var REPO_ROOT = require("path").join(__dirname, "..");
// Plan V2 section 5.10 — rules coverage for the "Seat-count asymmetry" defect
// (owner-scoped; agent branch agent/a9-rules-seat-count).
//
// Proves the claims against the REAL compiled firestore.rules (emulator, not
// a JS mirror): isValidRoundArchiveCreate's ORDINARY scored-archive branch
// used to hardcode `data.cardLog.size() == 52`, while its sibling pass branch
// proves its window against the match's real seat count (isAllPassBiddingLog
// derives n = seats.keys().size()) and isValidSeatMap admits the 1..4 seat
// ladder (players.size() == 1..4, the contiguous p1..pN prefix). The scored
// branch now requires 13 * parent.seats.keys().size() — the same derivation —
// so a 1-, 2- or 3-seat match can legitimately archive a scored round at all
// (previously it NEVER could), and Settlement.replayTricks' /
// TimeoutResolution.cardTotal's 13 * seats factor finally has a rules-side
// counterpart.
//
//   S1. Every admitted seat count n in {1,2,3,4} archives a scored round
//       holding exactly 13 * n card plays, and ONLY that: 13 * n + 1 and
//       13 * n - 1 are both denied.
//   S2. Regression guard (the task's byte-for-byte requirement): a FOUR-seat
//       match archives exactly 52 plays and is denied at 51/53 — the
//       newly-derived 13 * 4 == 52 keeps the old hardcoded constant's
//       allow/deny surface unchanged.
//   S3. The scored path is no longer a universal 52 gate: on non-four-seat
//       matches a 52-play archive is DENIED, and a round-sized archive for a
//       DIFFERENT seat count is denied too (13 * own seats or nothing).
//   S4. Pass-branch equilibrium still rides the same derivation: passRound
//       archives keep zero card plays and an n-window bid log (the all-
//       decline shape, 2N entries) for non-four tables as well, unchanged —
//       and a wrong-size pass window is still denied.
//   S5. The rest of the create conjunction stays load-bearing beside the new
//       derivation: membership, the pre-commit round binding and the matchId
//       binding still deny.
//
// Non-goals (covered elsewhere, deliberately not duplicated here):
//   - the pass window's internals (isAllDeclineWindow / isMixedPassWindow)
//     are pinned by the hand-sync and sprint-A suites; only cross-branch
//     seat-count agreement is exercised here;
//   - Kotlin settlement parity (Settlement.replayTricks 13 * order.size /
//     TimeoutResolution.cardTotal 13 * seats) is production Kotlin outside
//     rules scope — this file pins only the rules side of 13 * seats.
//
// Methodology: exact shape of tests/s61-timeout-counts.rules-emulator
// .test.cjs (initializeTestEnvironment + assertSucceeds/assertFails, seeded
// parent matches via withSecurityRulesDisabled, which is how the production
// writer's own Admin-SDK view of the matches collection is modelled here);
// seed shapes mirror that file's baseMatch() with the seat ladder made
// explicit. CI gate: .github/workflows/test.yml runs `npm run test:ci`
// (firestore emulator under `firebase emulators:exec`); the runner
// scripts/run-tests.mjs auto-discovers every tests/*.cjs. Every case uses a
// DISTINCT archive document id — the subcollection is write-once
// (allow update: false), so a reused id would turn the second case's create
// into an always-denied update. This file never prints the word the runner
// hard-fails on; an unreachable emulator is a hard failure with a different
// message.
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
      projectId: "demo-test-a9-seat-count",
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

  var uids = ["uidA", "uidB", "uidC", "uidD", "uidZ"];
  var ROUND = 7; // parent matches seed currentRound: 7; archives bind to it

  // The archived plays of one scored round at an n-seat table: entries are
  // {seatId, card, round}, seat-rotating, exactly as the /roundArchive schema
  // comment documents. The branch under test validates only the SIZE, but
  // every seed stays production-realistic anyway.
  function plays(n, count, round) {
    var out = [];
    for (var i = 0; i < count; i++) {
      out.push({
        seatId: "p" + ((i % n) + 1),
        // 52 named real cards; a synthetic 53rd play (only ever used to
        // prove a DENY) reuses the first suit rather than reading a hole.
        card: (i < 52 ? ["h", "d", "c", "s"][Math.floor(i / 13)] : "h") + ((i % 13) + 1),
        round: round
      });
    }
    return out;
  }

  // A General Pass round's all-decline window for n seats: N declining Dash
  // decisions followed by N auction passes (2N entries), round-tagged —
  // precisely the shape isAllDeclineWindow() proves at k = 0.
  function allDeclineBiddingLog(n, round) {
    var decisions = [];
    var passes = [];
    for (var i = 0; i < n; i++) {
      decisions.push({
        seatId: "p" + (i + 1),
        actionType: "SubmitDashCallDecision",
        declaredDashCall: false,
        round: round
      });
      passes.push({
        seatId: "p" + (i + 1),
        actionType: "SubmitAuctionBid",
        isPass: true,
        round: round
      });
    }
    return decisions.concat(passes);
  }

  function baseMatch(seatCount, overrides) {
    var seats = {};
    for (var i = 0; i < seatCount; i++) {
      seats["p" + (i + 1)] = uids[i];
    }
    var bids = {};
    for (var key in seats) { bids[key] = null; }
    var m = {
      roomId: "room-x", players: uids.slice(0, seatCount), status: "starting", createdAt: 1,
      currentRound: ROUND, maxRounds: 18, extendedRounds: [], dealer: uids[0], turn: uids[0],
      seats: seats, version: 1, biddingOpen: true,
      bids: bids, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: false, dealtRound: 0 }
    };
    Object.assign(m, overrides || {});
    return m;
  }

  function bareArchiveDoc(matchId, round) {
    // `estimates` is allowed and never required; passRound omitted entirely
    // for ordinary archives (data.get('passRound', false) default).
    return { round: round, matchId: matchId, cardLog: [], biddingLog: [] };
  }

  async function seedMatch(matchId, seatCount, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId)
        .set(baseMatch(seatCount, overrides || {}));
    });
  }

  // uidA (p1) is a player of every seeded match — every positive case is
  // written by a genuinely seated participant.
  function writerCtx() { return testEnv.authenticatedContext(uids[0]); }

  function archiveRef(context, matchId, docId) {
    return context.firestore().collection("matches").doc(matchId)
      .collection("roundArchive").doc(docId);
  }

  // Compose an ordinary archive create: `docId` MUST differ per case.
  function scoredCreate(matchId, docId, n, cardCount, overrides) {
    var doc = bareArchiveDoc(matchId, ROUND);
    doc.cardLog = plays(n, cardCount, ROUND);
    if (overrides) {
      for (var k in overrides) { doc[k] = overrides[k]; }
    }
    return assertSucceeds(archiveRef(writerCtx(), matchId, docId).set(doc))
      .then(function () { return true; }).catch(function () { return false; });
  }

  function scoredDenyCreate(matchId, docId, n, cardCount, context, overrides) {
    var doc = bareArchiveDoc(matchId, ROUND);
    doc.cardLog = plays(n, cardCount, ROUND);
    if (overrides) {
      for (var k in overrides) { doc[k] = overrides[k]; }
    }
    return assertFails(archiveRef(context || writerCtx(), matchId, docId).set(doc))
      .then(function () { return true; }).catch(function () { return false; });
  }

  // ──────────────────────────────────────────────────────────────
  // S1. The admitted seat ladder — exactly 13 * n plays per scored round.
  // ──────────────────────────────────────────────────────────────
  var seatCounts = [1, 2, 3, 4];
  for (var t = 0; t < seatCounts.length; t++) {
    var n = seatCounts[t];
    var id = "a9-s1-" + n;
    await seedMatch(id, n);
    check("A9-S1-" + n + "a " + n + "-seat match archives a FULL scored round: exactly " +
      (13 * n) + " plays -> ALLOWED",
      await scoredCreate(id, "full", n, 13 * n));
    check("A9-S1-" + n + "b " + n + "-seat match, " + (13 * n + 1) + " plays -> DENIED",
      await scoredDenyCreate(id, "over", n, 13 * n + 1));
    check("A9-S1-" + n + "c " + n + "-seat match, " + (13 * n - 1) + " plays -> DENIED",
      await scoredDenyCreate(id, "short", n, 13 * n - 1));
  }

  // ──────────────────────────────────────────────────────────────
  // S2. Regression guard: the four-seat behaviour is EXACTLY today's.
  // ──────────────────────────────────────────────────────────────
  {
    var id = "a9-s2-four";
    await seedMatch(id, 4);
    // The literal 52 the old branch hardcoded: the derived 13 * 4 == 52
    // keeps this allow bit-identical.
    check("A9-S2a FOUR-seat match archives 52 plays -> ALLOWED (legacy constant preserved)",
      await scoredCreate(id, "full52", 4, 52));
    check("A9-S2b FOUR-seat match, 51 plays -> DENIED",
      await scoredDenyCreate(id, "f51", 4, 51));
    check("A9-S2c FOUR-seat match, 53 plays -> DENIED",
      await scoredDenyCreate(id, "f53", 4, 53));
  }

  // ──────────────────────────────────────────────────────────────
  // S3. Anti-asymmetry: no universal 52 — 13 * THIS match's seats or nothing.
  // ──────────────────────────────────────────────────────────────
  {
    var id2 = "a9-s3-two";
    var id1 = "a9-s3-one";
    var id3 = "a9-s3-three";
    await seedMatch(id2, 2);
    await seedMatch(id1, 1);
    await seedMatch(id3, 3);
    check("A9-S3a TWO-seat match, legacy-constant 52 plays -> DENIED",
      await scoredDenyCreate(id2, "f52", 2, 52));
    check("A9-S3b TWO-seat match, ANOTHER table's size (39 = 13 * 3) -> DENIED",
      await scoredDenyCreate(id2, "f39", 2, 39));
    check("A9-S3c ONE-seat match, legacy-constant 52 plays -> DENIED",
      await scoredDenyCreate(id1, "f52", 1, 52));
    check("A9-S3d ONE-seat match, ANOTHER table's size (26 = 13 * 2) -> DENIED",
      await scoredDenyCreate(id1, "f26", 1, 26));
    check("A9-S3e THREE-seat match, ANOTHER table's size (52 = 13 * 4) -> DENIED",
      await scoredDenyCreate(id3, "f52", 3, 52));
  }

  // ──────────────────────────────────────────────────────────────
  // S4. Pass-branch equilibrium on non-four tables (same derivation).
  // ──────────────────────────────────────────────────────────────
  {
    var id2 = "a9-s4-two";
    var id4 = "a9-s4-four";
    await seedMatch(id2, 2);
    await seedMatch(id4, 4);

    var okPass2 = bareArchiveDoc(id2, ROUND);
    okPass2.cardLog = [];
    okPass2.biddingLog = allDeclineBiddingLog(2, ROUND);
    okPass2.passRound = true;
    check("A9-S4a TWO-seat General Pass archive: 0 cards + 2N = 4-entry all-decline window -> ALLOWED",
      await assertSucceeds(archiveRef(writerCtx(), id2, "pass").set(okPass2))
        .then(function () { return true; }).catch(function () { return false; }));

    var noCards = bareArchiveDoc(id2, ROUND);
    noCards.cardLog = plays(2, 26, ROUND);
    noCards.biddingLog = allDeclineBiddingLog(2, ROUND);
    noCards.passRound = true;
    check("A9-S4b TWO-seat pass archive with 26 card plays -> DENIED (pass keeps zero plays)",
      await assertFails(archiveRef(writerCtx(), id2, "pass26").set(noCards))
        .then(function () { return true; }).catch(function () { return false; }));

    var wrongWindow = bareArchiveDoc(id2, ROUND);
    wrongWindow.cardLog = [];
    wrongWindow.biddingLog = allDeclineBiddingLog(3, ROUND); // n = 2 table, yet a 6-entry strategy
    wrongWindow.passRound = true;
    check("A9-S4c TWO-seat pass archive sized for a THREE-seat window -> DENIED (window still derives seats)",
      await assertFails(archiveRef(writerCtx(), id2, "badwin").set(wrongWindow))
        .then(function () { return true; }).catch(function () { return false; }));

    var okPass4 = bareArchiveDoc(id4, ROUND);
    okPass4.cardLog = [];
    okPass4.biddingLog = allDeclineBiddingLog(4, ROUND);
    okPass4.passRound = true;
    check("A9-S4d FOUR-seat General Pass archive: unchanged 8-entry all-decline window -> ALLOWED",
      await assertSucceeds(archiveRef(writerCtx(), id4, "pass").set(okPass4))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ──────────────────────────────────────────────────────────────
  // S5. The rest of the create conjunction stays load-bearing.
  // ──────────────────────────────────────────────────────────────
  {
    var id = "a9-s5-two";
    var id2 = "a9-s5-member";
    var id3 = "a9-s5-round";
    var id4 = "a9-s5-match";
    await seedMatch(id, 2);
    await seedMatch(id2, 2);
    await seedMatch(id3, 2);
    await seedMatch(id4, 2);
    check("A9-S5a scored 26-play archive with NO authentication -> DENIED",
      await scoredDenyCreate(id, "anon", 2, 26, testEnv.unauthenticatedContext()));
    check("A9-S5b scored 26-play archive written by a NON-seated uid -> DENIED",
      await scoredDenyCreate(id2, "f26", 2, 26, testEnv.authenticatedContext(uids[4])));
    check("A9-S5c scored 26-play archive bound to the WRONG round -> DENIED",
      await scoredDenyCreate(id3, "f26", 2, 26, null, { round: 6 }));
    check("A9-S5d scored 26-play archive carrying a WRONG matchId -> DENIED",
      await scoredDenyCreate(id4, "f26", 2, 26, null, { matchId: "somewhere-else" }));
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

run().catch(function (e) {
  console.log("UNEXPECTED ERROR: " + (e && e.stack || e));
  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = 2;
});
