const path = require("path");
// Portability fix (established convention this session): never
// hardcode this sandbox's own absolute path.
const __REPO_ROOT__ = path.join(__dirname, "..");

// SPRINT J.3 — Hardened Round-Start Turn Authority Implementation.
// Real Firestore Rules Emulator proof of the new `isValidBidSubmission()`
// round-start branch: an Estimates-phase completion write may ALSO
// establish `turn`/`cardPhase` (closing the `advanceToNextRound()`
// turn:null/cardPhase:null dead end Sprint J's forensic report found),
// but ONLY on the genuine `biddingOpen: true -> false` completion edge
// — never on an ordinary/intermediate bid, never twice, never stale.
//
// SCOPE (see firestore.rules' own isValidBidSubmission() comment for
// the full account): this fix covers the DOMINANT bidding-completion
// path (Estimates, via submitBid()) only. Two other theoretical paths
// were investigated and are DELIBERATELY NOT covered here:
//   - The DASH-phase's own "all four Dash-Called, straight to DONE"
//     branch in bidding-engine.js is UNREACHABLE in production
//     (MAX_DASH_CALLS = 2 caps the count of DASHCALL-typed bids at 2,
//     so the `active.length === 0` condition that branch requires can
//     never be true) — confirmed by direct code trace, not assumed.
//   - The fast-round "Super Call" case (SubmitConfirmCall transitioning
//     directly to subPhase DONE without ever entering ESTIMATES) DOES
//     complete via `biddingLog`/`isValidBiddingActionSubmission()`
//     instead of this function, and is left as an explicitly
//     documented, deferred gap — see this sprint's own Final Report.
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
      projectId: "demo-test-sprint-j3",
      firestore: { rules: fs.readFileSync(__REPO_ROOT__ + "/firestore.rules", "utf8"), host: "127.0.0.1", port: 8080 }
    });
  } catch (e) {
    console.error("EMULATOR NOT REACHABLE — " + e.message);
    console.error("\n=== RESULTS ===\n0 passed, 0 failed (FAILED — emulator unreachable)");
    process.exit(1);
  }

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";

  function baseMatch(overrides) {
    var m = {
      roomId: "room-x", players: [uidA, uidB, uidC, uidD], status: "starting", createdAt: 1,
      currentRound: 2, maxRounds: 18, extendedRounds: [], dealer: uidA, turn: null,
      seats: { p1: uidA, p2: uidB, p3: uidC, p4: uidD }, version: 10, biddingOpen: true,
      bids: { p1: 4, p2: 3, p3: 2, p4: null }, lastBidSeat: "p3",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 2 }
    };
    Object.assign(m, overrides || {});
    return m;
  }
  async function seed(matchId, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(baseMatch(overrides));
    });
  }
  function matchRef(uid, matchId) { return testEnv.authenticatedContext(uid).firestore().collection("matches").doc(matchId); }

  // The genuine final-estimate write shape: p4 (uidD) submits the last
  // bid, biddingOpen flips to false, and the write ALSO establishes the
  // real first-trick leader (here, uidA/p1 — an arbitrary but
  // structurally real choice for this test).
  function genuineCompletionPatch(turnUid) {
    return {
      bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11,
      lastBidSeat: "p4", turn: turnUid, cardPhase: "PLAY", updatedAt: 1
    };
  }

  // ══════════════════════════════════════════════════════════════
  // 1. Intermediate estimate (not the last seat) + turn claim -> DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var m1 = "j3-1-intermediate-plus-claim";
    await seed(m1, { bids: { p1: 4, p2: 3, p3: null, p4: null } });
    check("J.3 #1: intermediate estimate (p3, not final) smuggling a turn/cardPhase claim -> DENIED",
      await assertFails(matchRef(uidC, m1).update({
        bids: { p1: 4, p2: 3, p3: 2, p4: null }, version: 11, lastBidSeat: "p3", turn: uidA, cardPhase: "PLAY", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 2. Genuine final estimate completion + valid turn/cardPhase -> ALLOWED
  // ══════════════════════════════════════════════════════════════
  {
    var m2 = "j3-2-genuine-completion-allowed";
    await seed(m2, {});
    check("J.3 #2: the genuine final estimate (p4), establishing a structurally real leader -> ALLOWED",
      await assertSucceeds(matchRef(uidD, m2).update(genuineCompletionPatch(uidA))).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 3. Genuine completion + a fabricated (non-real-seat) turn uid -> DENIED
  // 3b. Genuine completion + a WRONG but structurally real seat's uid ->
  //     ALLOWED at the Rules layer (the documented, accepted
  //     client-authoritative limitation — correctness is enforced
  //     client-side, not by Rules; see Sprint J.2's own adversarial
  //     analysis for the full traced consequence of this).
  // ══════════════════════════════════════════════════════════════
  {
    var m3 = "j3-3-fabricated-turn-denied";
    await seed(m3, {});
    check("J.3 #3: genuine completion naming a fabricated (non-seat) uid as turn -> DENIED",
      await assertFails(matchRef(uidD, m3).update(genuineCompletionPatch("some-fabricated-uid"))).then(function () { return true; }).catch(function () { return false; }));

    var m3b = "j3-3b-wrong-seat-allowed-documented";
    await seed(m3b, {});
    check("J.3 #3b: genuine completion naming a WRONG but real seat's uid -> ALLOWED (documented limitation, not a new gap)",
      await assertSucceeds(matchRef(uidD, m3b).update(genuineCompletionPatch(uidC))).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 4. Duplicate completion (replaying the same completing write again
  //    after it already succeeded) -> DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var m4 = "j3-4-duplicate-completion";
    await seed(m4, {});
    check("J.3 #4 setup: the first genuine completion succeeds",
      await assertSucceeds(matchRef(uidD, m4).update(genuineCompletionPatch(uidA))).then(function () { return true; }).catch(function () { return false; }));
    check("J.3 #4: replaying the IDENTICAL completing write a second time -> DENIED (oldData.turn no longer null)",
      await assertFails(matchRef(uidD, m4).update(genuineCompletionPatch(uidB))).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 5. Stale-version completion attempt -> DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var m5 = "j3-5-stale-completion";
    await seed(m5, { version: 11 }); // simulate the doc already having moved past the version this write expects
    check("J.3 #5: a completion write computed against a stale version -> DENIED",
      await assertFails(matchRef(uidD, m5).update(genuineCompletionPatch(uidA))).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 6. Genuine completion but invalid cardPhase ("RESOLVING" instead of
  //    "PLAY") -> DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var m6 = "j3-6-invalid-cardphase";
    await seed(m6, {});
    check("J.3 #6: genuine completion with cardPhase='RESOLVING' instead of 'PLAY' -> DENIED",
      await assertFails(matchRef(uidD, m6).update(Object.assign({}, genuineCompletionPatch(uidA), { cardPhase: "RESOLVING" })))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 13. Mid-round turn mutation attempt through this same bidding path,
  //     once bidding is already closed (not a real completion edge) ->
  //     DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var m13 = "j3-13-mid-round-mutation";
    await seed(m13, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, turn: uidA, cardPhase: "PLAY" });
    check("J.3 #13: attempting to reuse the bidding-completion branch once bidding is already closed and turn already set -> DENIED",
      await assertFails(matchRef(uidD, m13).update({
        bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, lastBidSeat: "p4", turn: uidD, cardPhase: "PLAY", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 14. Turn reassignment after completion (turn != null already) via
  //     ANY subsequent bidding write -> DENIED (same mechanism as #13,
  //     reproduced against a match already fully in PLAY phase)
  // ══════════════════════════════════════════════════════════════
  {
    var m14 = "j3-14-reassignment-after-completion";
    await seed(m14, { bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, turn: uidA, cardPhase: "PLAY", currentRound: 2 });
    check("J.3 #14: turn cannot be reassigned via the bidding path once it is already non-null -> DENIED",
      await assertFails(matchRef(uidB, m14).update({
        bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 11, lastBidSeat: "p1", turn: uidB, cardPhase: "PLAY", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 15/16. Ordinary, non-round-start bids remain completely unaffected
  // (no regression on the existing, dominant write shape).
  // ══════════════════════════════════════════════════════════════
  {
    var m15 = "j3-15-ordinary-bid-unaffected";
    await seed(m15, { bids: { p1: 4, p2: 3, p3: null, p4: null } });
    check("J.3 #15 (no regression): an ordinary intermediate estimate with NO turn/cardPhase claim at all -> still ALLOWED",
      await assertSucceeds(matchRef(uidC, m15).update({
        bids: { p1: 4, p2: 3, p3: 2, p4: null }, version: 11, lastBidSeat: "p3", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // CLIENT SAFETY NET PROOF (Sprint J.2's own required test): Firestore
  // ALLOWS a structurally-valid-but-game-rule-wrong turn claim (J.3 #3b
  // above already proved this at the Rules layer) — this section proves
  // the OTHER half: that the committed document's `turn` value, once
  // wrong, is exactly what a real client would read back, closing the
  // loop on "Rules can't verify correctness, but the client-side
  // deterministic-replay check is what actually protects the game" by
  // confirming the document a dishonest write produces is observable,
  // not silently corrected or masked.
  // ══════════════════════════════════════════════════════════════
  {
    var m17 = "j3-17-client-safety-net-proof";
    await seed(m17, {});
    await matchRef(uidD, m17).update(genuineCompletionPatch(uidC)); // uidC/p3 is NOT necessarily the real leader -- this is exactly the fraudulent-but-structurally-valid case
    var committed;
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      var snap = await ctx.firestore().collection("matches").doc(m17).get();
      committed = snap.data();
    });
    check("J.3 #17: the committed document faithfully reflects whatever turn value was written (Rules do not silently correct a wrong-but-structural claim) -- this is precisely the boundary the client-side TableEngine.emit() turn check exists to cover, per Sprint J.2's own adversarial analysis",
      committed && committed.turn === uidC, committed);
  }

  console.log("\n=== Sprint J.3: Hardened Round-Start Turn Authority ===\n");
  console.log(pass + " passed, " + fail + " failed");
  if (fail > 0) process.exit(1);
  await testEnv.cleanup();
}

run().catch(function (e) {
  console.error("FATAL:", e);
  process.exit(1);
});
