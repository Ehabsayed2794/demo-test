var REPO_ROOT = require("path").join(__dirname, "..");
// P2-2 — Negative rules-level tests for the ranked-trust boundary.
//
// Roadmap: AUDIT_R1_GOLDEN_PATH_POSTCLOSE_ROADMAP.md P2-2 (M, after P1).
// Proves three claims against the REAL compiled firestore.rules (not the
// JS mirror in tests/rules-simulation.test.js, not MatchService's own
// client-side guards):
//
//   A. K.4++ — raw-REST writes against a `status:"complete"` match are
//      DENIED at the rules layer, not just refused client-side by
//      MatchService's phase checks. The Golden Path K.4 gate is a single
//      client-side submitCard attempt; these tests bypass MatchService
//      entirely (direct Db.collection().doc().update()) so a rules
//      regression would fail here even if client code stayed polite.
//   B. Score-forgery — a consistent-but-wrong finalScores (internally
//      consistent winnerIds == max-score seat, but fabricated vs any real
//      gameplay) is ACCEPTED today. Documents the ranked-blocker; do NOT
//      fix (friends-MVP trust boundary per
//      docs/architecture/SecurityArchitecture.md). The DENIED control
//      (B.3) pins exactly what the rule DOES check (winner == max).
//   C. Advance-without-archive (R-A) — a parent advance that resets the
//      per-round window but writes NO roundArchive doc is ACCEPTED today.
//      The reverse direction (every archive that DOES exist is genuine)
//      IS enforced; this file proves both directions explicitly.
//
// What this file does NOT do (explicit non-goals, still deferred):
//   - P2-1 review-rounds screen: no product request, no code here.
//   - P2-3 SHA automation: still manual guard, untouched here.
//   - P2-4 presence: PresenceService stays a stub, untouched here.
//   - No engine, UI, or firestore.rules changes: tests only.
//
// Methodology: exact shape of tests/sprint-a-write-paths.rules-emulator.test.cjs
// (initializeTestEnvironment + assertSucceeds/assertFails + seeded matches
// via withSecurityRulesDisabled). Seed shapes mirror design-ui/match-service.js
// buildInitialMatchDoc()/advanceToNextRound()/endMatch() field lists and
// firestore.rules isValid*() affectedKeys() allowlists — never invented.
// This file never prints the word that the runner hard-fails on; an
// unreachable emulator is a hard failure with a different message.
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
      projectId: "demo-test-p2-negative",
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

  // A terminal match as endMatch() leaves it: status complete, windows
  // reset to [], terminal fields set. Seeded directly (rules-disabled)
  // so every group below starts from authoritative post-match state.
  function completeMatch(overrides) {
    return baseMatch(Object.assign({
      status: "complete", currentRound: 18, maxRounds: 18, version: 2,
      winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 },
      completedRound: 18, cardLog: [], biddingLog: []
    }, overrides || {}));
  }

  async function seed(matchId, doc) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(doc);
    });
  }

  function ctxFor(uid) { return testEnv.authenticatedContext(uid); }
  function matchRef(uid, matchId) { return ctxFor(uid).firestore().collection("matches").doc(matchId); }
  async function archiveExists(matchId, round) {
    var exists = false;
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      var snap = await ctx.firestore().collection("matches").doc(matchId).collection("roundArchive").doc(String(round)).get();
      exists = snap.exists;
    });
    return exists;
  }

  // Round tag must equal the parent's currentRound (round-tagging invariant);
  // callers pass the seeded match's own round so denial isolates the guard
  // under test, never a tag mismatch.
  function cardPatch(seat, v, turnUid, round) {
    return {
      cardLog: [{ seatId: seat, card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: round }],
      lastCardSeat: seat, turn: turnUid, cardPhase: "PLAY", version: v, updatedAt: 1
    };
  }
  function actionEntry(seat, round) {
    return { seatId: seat, actionType: "SubmitDashCallDecision", round: round, declaredDashCall: true };
  }
  // Per-round-window advance shape (matches MatchService.advanceToNextRound()
  // patch + firestore.rules isValidRoundAdvance() allowlist). Dealer rotates
  // p1(uidA) -> p2(uidB) per expectedNextDealer().
  function advancePatch(v) {
    return {
      currentRound: 2, dealer: uidB, version: v, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardPhase: null, turn: null, updatedAt: 1
    };
  }
  function completePatch(v, winnerIds, finalScores, completedRound) {
    return { status: "complete", winnerIds: winnerIds, finalScores: finalScores, completedRound: completedRound, version: v, updatedAt: 1 };
  }

  // ══════════════════════════════════════════════════════════════
  // A. K.4++ — raw-REST post-complete writes bypass MatchService's own
  //    client-side phase guard entirely (direct update()) and must still
  //    be DENIED by firestore.rules' own terminal-immutability guards
  //    (firestore.rules:573 bid, :711 card, :916 biddingAction, :1010
  //    advance, :1112 extension, :1200 completion, :1259 deal).
  // ══════════════════════════════════════════════════════════════
  {
    var m = "p2-post-bid";
    await seed(m, completeMatch({}));
    check("P2-A1 raw-REST submitBid on a complete match -> DENIED at rules layer",
      await assertFails(matchRef(uidA, m).update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 3
      })).then(function () { return true; }).catch(function () { return false; }));

    // A2 isolation note: the seeded complete match sits at currentRound 18,
    // so the card entry is tagged round 18 — denial is the terminal guard,
    // never a round-tag mismatch.
    await seed(m + "-card", completeMatch({}));
    check("P2-A2 raw-REST submitCard on a complete match -> DENIED at rules layer",
      await assertFails(matchRef(uidA, m + "-card").update(cardPatch("p1", 3, uidB, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-act", completeMatch({}));
    check("P2-A3 raw-REST submitBiddingAction on a complete match -> DENIED at rules layer",
      await assertFails(matchRef(uidA, m + "-act").update({ biddingLog: [actionEntry("p1", 18)], version: 3 }))
        .then(function () { return true; }).catch(function () { return false; }));

    // A4 isolation note: the seeded complete match sits at currentRound 18
    // (dealer still p1/uidA), so the otherwise-shape-valid advance is
    // 18 -> 19 with the exact next dealer p2/uidB — the ONLY reason for
    // denial is the terminal guard, never round math.
    await seed(m + "-adv", completeMatch({}));
    check("P2-A4 raw-REST advanceToNextRound on a complete match -> DENIED at rules layer",
      await assertFails(matchRef(uidA, m + "-adv").update({
        currentRound: 19, dealer: uidB, version: 3, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
        cardPhase: null, turn: null, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-ext", completeMatch({ currentRound: 14 }));
    check("P2-A5 raw-REST extendMatchRounds on a complete match -> DENIED at rules layer",
      await assertFails(matchRef(uidA, m + "-ext").update({ maxRounds: 19, extendedRounds: [14], version: 3, updatedAt: 1 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-end", completeMatch({}));
    check("P2-A6 raw-REST second endMatch on a complete match -> DENIED at rules layer",
      await assertFails(matchRef(uidA, m + "-end").update(completePatch(3, ["p2"], { p1: 10, p2: 50, p3: 30, p4: 20 }, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    // A7 honesty note: this denial is over-determined by design — the
    // terminal guard (firestore.rules:1259) AND the atomic hands-pairing
    // getAfter() clauses both fail for a lone parent update with no
    // sibling hands writes. Either alone denies; the assertion is that a
    // post-complete deal commit is DENIED, not which conjunct fires first.
    await seed(m + "-deal", completeMatch({}));
    check("P2-A7 raw-REST deal commit (gameState flip) on a complete match -> DENIED at rules layer",
      await assertFails(matchRef(uidA, m + "-deal").update({ gameState: { initialized: true, dealtRound: 18 }, updatedAt: 1 }))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // B. Score-forgery — documents the ranked-only trust boundary.
  //    The rule checks winnerIds == max-score tie set
  //    (isExactWinnerTieSet, firestore.rules:1224), never gameplay truth.
  //    B.1/B.2 are ACCEPTED today (record, do not fix). B.3 is the DENIED
  //    control proving the consistency check itself is real.
  // ══════════════════════════════════════════════════════════════
  {
    var m = "p2-forge";
    await seed(m, baseMatch({ currentRound: 18, maxRounds: 18 }));
    check("P2-B1 DOCUMENTED ACCEPT: fabricated runaway scores (p1:9999, winner p1) complete -> ALLOWED (ranked-blocker, not a bug)",
      await assertSucceeds(matchRef(uidA, m).update(
        completePatch(2, ["p1"], { p1: 9999, p2: 0, p3: 0, p4: -999 }, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-tie", baseMatch({ currentRound: 18, maxRounds: 18 }));
    check("P2-B2 DOCUMENTED ACCEPT: fabricated tie (p1/p2 500, winners p1+p2) completes -> ALLOWED (same boundary)",
      await assertSucceeds(matchRef(uidB, m + "-tie").update(
        completePatch(2, ["p1", "p2"], { p1: 500, p2: 500, p3: 0, p4: 0 }, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-bad", baseMatch({ currentRound: 18, maxRounds: 18 }));
    check("P2-B3 control: inconsistent winnerIds (scores crown p1, winners name p2) -> DENIED (rule checks winner==max)",
      await assertFails(matchRef(uidA, m + "-bad").update(
        completePatch(2, ["p2"], { p1: 100, p2: 90, p3: 80, p4: 70 }, 18)))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // C. Advance-without-archive (R-A honest limitation, firestore.rules
  //    roundArchive block :1570-1578). The parent advance does NOT imply
  //    an archive write; C.1 is ACCEPTED today (history loss, gameplay
  //    unaffected — engines score locally). C.2/C.3 pin the direction
  //    that IS enforced: every archive that exists is genuine.
  // ══════════════════════════════════════════════════════════════
  {
    var m = "p2-noarch";
    await seed(m, baseMatch({}));
    var c1 = await assertSucceeds(matchRef(uidC, m).update(advancePatch(2)))
      .then(function () { return true; }).catch(function () { return false; });
    var c1Archived = await archiveExists(m, 1);
    check("P2-C1 DOCUMENTED ACCEPT: advance with empty windows and NO archive doc -> ALLOWED (R-A; gameplay unaffected, history lost)",
      c1 === true && c1Archived === false);

    // Archive sincerity (the enforced direction): the create rule demands
    // EXACTLY 52 cardLog plays for the round actually being closed
    // (firestore.rules:1594,1596), so a malformed archive is DENIED even
    // though its parent is live and its writer is seated.
    await seed(m + "-arch-ok", baseMatch({}));
    var archOk = await ctxFor(uidA).firestore().collection("matches").doc(m + "-arch-ok")
      .collection("roundArchive").doc("1").set({
        round: 1, matchId: m + "-arch-ok",
        cardLog: [],
        biddingLog: []
      }).then(function () { return "written"; }).catch(function (e) { return "denied:" + e.code; });
    // cardLog.size()==52 is required, so an empty cardLog MUST be denied —
    // this assertion doubles as the shape check (empty != genuine round).
    check("P2-C2 archive shape: empty cardLog archive (0 != 52 plays) -> DENIED",
      typeof archOk === "string" && archOk.indexOf("denied") === 0);

    // C3 isolation note: the archive below carries a full 52-entry cardLog
    // (the rule checks entry COUNT, never entry truth — same structural-only
    // precedent as every other rule), so the ONLY reason for denial is the
    // parent's terminal status, never log shape.
    await seed(m + "-arch-post", completeMatch({ currentRound: 1 }));
    var fullRound = [];
    for (var i = 0; i < 52; i++) {
      fullRound.push({ seatId: "p1", card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 });
    }
    check("P2-C3 archive fabrication post-match (parent already complete) -> DENIED",
      await assertFails(ctxFor(uidA).firestore().collection("matches").doc(m + "-arch-post")
        .collection("roundArchive").doc("1").set({
          round: 1, matchId: m + "-arch-post",
          cardLog: fullRound, biddingLog: []
        })).then(function () { return true; }).catch(function () { return false; }));
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
