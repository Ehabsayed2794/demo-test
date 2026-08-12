// SPRINT A — Real Firestore Rules Emulator closure for P1-1 (Multiplayer
// Integration Audit). Proves the 6 remaining matches/{matchId} update
// write paths against the REAL compiled firestore.rules, following the
// exact methodology already established in hand-sync.rules-emulator.test.cjs,
// hand-sync.rules-emulator-rematch-fix.test.cjs, and
// matches-update-dispatch.rules-emulator.test.cjs.
//
// Scope: submitBid, submitCard, submitBiddingAction, advanceToNextRound,
// extendMatchRounds (extendRound), endMatch (match completion). Every
// write shape and field list below was read directly from
// design-ui/match-service.js and firestore.rules (isValidBidSubmission,
// isValidCardSubmission, isValidBiddingActionSubmission,
// isValidRoundAdvance, isValidRoundExtension, isValidMatchCompletion,
// isValidMatchUpdateDispatch) — not inferred from comments.
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
      projectId: "demo-test-sprint-a",
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

  async function seed(matchId, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(baseMatch(overrides));
    });
  }

  function ctxFor(uid) { return testEnv.authenticatedContext(uid); }
  function matchRef(uid, matchId) { return ctxFor(uid).firestore().collection("matches").doc(matchId); }

  // ══════════════════════════════════════════════════════════════
  // 1. submitBid — writes: bids, biddingOpen, version, lastBidSeat, updatedAt
  //    Actor: seat owner. Version: oldData.version+1.
  // ══════════════════════════════════════════════════════════════
  {
    var m = "bid-1";
    await seed(m, {});
    check("BID.1 Legitimate first bid (p1, own seat) -> ALLOWED",
      await assertSucceeds(matchRef(uidA, m).update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-2", {});
    check("BID.2 Unauthorized (non-player) bid -> DENIED",
      await assertFails(matchRef(uidZ, m + "-2").update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-3", {});
    check("BID.3 Wrong seat (uidB claiming p1's bid) -> DENIED",
      await assertFails(matchRef(uidB, m + "-3").update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-4", {});
    check("BID.4 Stale version (claiming version 2 twice / version unchanged) -> DENIED",
      await assertFails(matchRef(uidA, m + "-4").update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 1
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-5", {});
    check("BID.5 Tampered protected field (players array) alongside a valid bid -> DENIED",
      await assertFails(matchRef(uidA, m + "-5").update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2,
        players: [uidA, uidB, uidC, uidD, uidZ]
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-6", {});
    check("BID.6 Wrong write shape (touches cardLog, an unexpected field for this shape) -> DENIED",
      await assertFails(matchRef(uidA, m + "-6").update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2,
        cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 }]
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-7", { biddingOpen: false });
    check("BID.7 Invalid state transition (bidding already closed) -> DENIED",
      await assertFails(matchRef(uidA, m + "-7").update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: false, version: 2
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 2. submitCard — writes: cardLog, lastCardSeat, version, turn, cardPhase, updatedAt
  //    Actor: seat owner AND oldData.turn == auth.uid (turn authority).
  // ══════════════════════════════════════════════════════════════
  function cardPatch(seat, v) {
    return {
      cardLog: [{ seatId: seat, card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 }],
      lastCardSeat: seat, turn: uidB, cardPhase: "PLAY", version: v, updatedAt: 1
    };
  }
  {
    var m = "card-1";
    await seed(m, { turn: uidA }); // p1/uidA's turn
    check("CARD.1 Legitimate card play (p1, own seat, own turn) -> ALLOWED",
      await assertSucceeds(matchRef(uidA, m).update(cardPatch("p1", 2)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-2", { turn: uidA });
    check("CARD.2 Unauthorized (non-player) card play -> DENIED",
      await assertFails(matchRef(uidZ, m + "-2").update(cardPatch("p1", 2)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-3", { turn: uidA });
    check("CARD.3 Wrong seat (uidB claiming p1's card) -> DENIED",
      await assertFails(matchRef(uidB, m + "-3").update(cardPatch("p1", 2)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-4", { turn: uidA });
    check("CARD.4 Stale version -> DENIED",
      await assertFails(matchRef(uidA, m + "-4").update(cardPatch("p1", 1)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-5", { turn: uidA });
    check("CARD.5 Tampered protected field (seats map) alongside a valid card play -> DENIED",
      await assertFails(matchRef(uidA, m + "-5").update(Object.assign(cardPatch("p1", 2), { seats: { p1: uidZ, p2: uidB, p3: uidC, p4: uidD } })))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-6", { turn: uidA });
    check("CARD.6 Wrong write shape (touches bids, an unexpected field for this shape) -> DENIED",
      await assertFails(matchRef(uidA, m + "-6").update(Object.assign(cardPatch("p1", 2), { bids: { p1: 5, p2: null, p3: null, p4: null } })))
        .then(function () { return true; }).catch(function () { return false; }));

    // Invalid state transition case for card play: it is NOT this seat's
    // turn (turn authority is the meaningful "state transition" gate here,
    // distinct from wrong-seat above — this is the RIGHT seat owner
    // attempting to play when the OLD `turn` field does not point at them).
    await seed(m + "-7", { turn: uidB }); // turn belongs to p2/uidB, not p1/uidA
    check("CARD.7 Invalid state transition (right seat owner, but not their turn) -> DENIED",
      await assertFails(matchRef(uidA, m + "-7").update(cardPatch("p1", 2)))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 3. submitBiddingAction — writes: biddingLog, version, updatedAt
  //    Actor: seat owner (appended.seatId owned by auth.uid). No turn
  //    check in RULES (bidding-engine.js enforces turn client-side only
  //    — see Phase 6 security review for this residual note).
  // ══════════════════════════════════════════════════════════════
  function actionEntry(seat, round) {
    return { seatId: seat, actionType: "SubmitDashCallDecision", round: round, declaredDashCall: true };
  }
  {
    var m = "act-1";
    await seed(m, {});
    check("ACT.1 Legitimate bidding action (p1, own seat) -> ALLOWED",
      await assertSucceeds(matchRef(uidA, m).update({ biddingLog: [actionEntry("p1", 1)], version: 2 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-2", {});
    check("ACT.2 Unauthorized (non-player) bidding action -> DENIED",
      await assertFails(matchRef(uidZ, m + "-2").update({ biddingLog: [actionEntry("p1", 1)], version: 2 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-3", {});
    check("ACT.3 Wrong seat (uidB claiming p1's action) -> DENIED",
      await assertFails(matchRef(uidB, m + "-3").update({ biddingLog: [actionEntry("p1", 1)], version: 2 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-4", {});
    check("ACT.4 Stale version -> DENIED",
      await assertFails(matchRef(uidA, m + "-4").update({ biddingLog: [actionEntry("p1", 1)], version: 1 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-5", {});
    check("ACT.5 Tampered protected field (dealer) alongside a valid action -> DENIED",
      await assertFails(matchRef(uidA, m + "-5").update({ biddingLog: [actionEntry("p1", 1)], version: 2, dealer: uidZ }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-6", {});
    check("ACT.6 Wrong write shape (touches bids, an unexpected field for this shape) -> DENIED",
      await assertFails(matchRef(uidA, m + "-6").update({ biddingLog: [actionEntry("p1", 1)], version: 2, bids: { p1: 5, p2: null, p3: null, p4: null } }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-7", {});
    check("ACT.7 Invalid state transition (round tag mismatch — entry claims round 2, match is on round 1) -> DENIED",
      await assertFails(matchRef(uidA, m + "-7").update({ biddingLog: [actionEntry("p1", 2)], version: 2 }))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 4. advanceToNextRound — writes: currentRound, version, biddingOpen,
  //    bids, lastBidSeat, cardPhase, turn, updatedAt
  //    Actor: ANY match player (no per-seat check — by design).
  // ══════════════════════════════════════════════════════════════
  function advancePatch(v) {
    return {
      currentRound: 2, version: v, biddingOpen: true,
      bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardPhase: null, turn: null, updatedAt: 1
    };
  }
  {
    var m = "adv-1";
    await seed(m, {});
    check("ADV.1 Legitimate round advance (any player, e.g. p3/uidC) -> ALLOWED",
      await assertSucceeds(matchRef(uidC, m).update(advancePatch(2)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-2", {});
    check("ADV.2 Unauthorized (non-player) round advance -> DENIED",
      await assertFails(matchRef(uidZ, m + "-2").update(advancePatch(2)))
        .then(function () { return true; }).catch(function () { return false; }));

    // "Wrong/mismatched seat" does not apply here by design (any seated
    // player may advance) — replaced with the closest meaningful case:
    // a player who is NOT actually in this match's `players` array
    // (distinct from uidZ above only in that this checks the exact
    // membership predicate, not seat ownership, since there's no seat
    // check at all for this write shape).
    await seed(m + "-3", {});
    check("ADV.3 (replaces seat-mismatch, N/A here) Non-member attempting advance -> DENIED",
      await assertFails(matchRef(uidZ, m + "-3").update(advancePatch(2)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-4", {});
    check("ADV.4 Stale version -> DENIED",
      await assertFails(matchRef(uidA, m + "-4").update(advancePatch(1)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-5", {});
    check("ADV.5 Skip version (claiming version 3 instead of 2) -> DENIED",
      await assertFails(matchRef(uidA, m + "-5").update(advancePatch(3)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-6", {});
    check("ADV.6 Tampered protected field (maxRounds) alongside a valid advance -> DENIED",
      await assertFails(matchRef(uidA, m + "-6").update(Object.assign(advancePatch(2), { maxRounds: 99 })))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-7", {});
    check("ADV.7 Wrong write shape (touches cardLog, an unexpected field) -> DENIED",
      await assertFails(matchRef(uidA, m + "-7").update(Object.assign(advancePatch(2), {
        cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 }]
      }))).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-8", { status: "complete" });
    check("ADV.8 Invalid state transition (match already complete) -> DENIED",
      await assertFails(matchRef(uidA, m + "-8").update(advancePatch(2)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-9", {});
    check("ADV.9 Invalid state transition (currentRound jumps by 2, not 1) -> DENIED",
      await assertFails(matchRef(uidA, m + "-9").update(Object.assign({}, advancePatch(2), { currentRound: 3 })))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 5. extendMatchRounds (extendRound) — writes: maxRounds,
  //    extendedRounds, version, updatedAt. Actor: any match player.
  // ══════════════════════════════════════════════════════════════
  function extendPatch(v, rounds, maxR) {
    return { maxRounds: maxR, extendedRounds: rounds, version: v, updatedAt: 1 };
  }
  {
    var m = "ext-1";
    await seed(m, { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("EXT.1 Legitimate extension (round 14, any player) -> ALLOWED",
      await assertSucceeds(matchRef(uidB, m).update(extendPatch(2, [14], 19)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-2", { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("EXT.2 Unauthorized (non-player) extension -> DENIED",
      await assertFails(matchRef(uidZ, m + "-2").update(extendPatch(2, [14], 19)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-3", { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("EXT.3 (N/A: no seat check by design) Non-member extension attempt -> DENIED",
      await assertFails(matchRef(uidZ, m + "-3").update(extendPatch(2, [14], 19)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-4", { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("EXT.4 Stale version -> DENIED",
      await assertFails(matchRef(uidA, m + "-4").update(extendPatch(1, [14], 19)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-5", { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("EXT.5 Skip maxRounds (jumping +2 instead of +1) -> DENIED",
      await assertFails(matchRef(uidA, m + "-5").update(extendPatch(2, [14], 20)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-6", { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("EXT.6 Tampered protected field (currentRound) alongside a valid extension -> DENIED",
      await assertFails(matchRef(uidA, m + "-6").update(Object.assign(extendPatch(2, [14], 19), { currentRound: 15 })))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-7", { currentRound: 14, maxRounds: 18, extendedRounds: [] });
    check("EXT.7 Wrong write shape (touches status, an unexpected field) -> DENIED",
      await assertFails(matchRef(uidA, m + "-7").update(Object.assign(extendPatch(2, [14], 19), { status: "complete" })))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-8", { currentRound: 14, maxRounds: 18, extendedRounds: [14] });
    check("EXT.8 Invalid state transition (round 14 already used to extend once) -> DENIED",
      await assertFails(matchRef(uidA, m + "-8").update(extendPatch(2, [14, 14], 19)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-9", { currentRound: 14, maxRounds: 18, extendedRounds: [], status: "complete" });
    check("EXT.9 Invalid state transition (match already complete) -> DENIED",
      await assertFails(matchRef(uidA, m + "-9").update(extendPatch(2, [14], 19)))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // 6. endMatch (match completion) — writes: status, winnerIds,
  //    finalScores, completedRound, version, updatedAt. Actor: any
  //    match player.
  // ══════════════════════════════════════════════════════════════
  function completePatch(v, winnerIds, finalScores, completedRound) {
    return { status: "complete", winnerIds: winnerIds, finalScores: finalScores, completedRound: completedRound, version: v, updatedAt: 1 };
  }
  var fullScores = { p1: 100, p2: 90, p3: 80, p4: 70 };
  {
    var m = "end-1";
    await seed(m, { currentRound: 18, maxRounds: 18 });
    check("END.1 Legitimate completion (round 18 == maxRounds, any player) -> ALLOWED",
      await assertSucceeds(matchRef(uidD, m).update(completePatch(2, ["p1"], fullScores, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-2", { currentRound: 18, maxRounds: 18 });
    check("END.2 Unauthorized (non-player) completion -> DENIED",
      await assertFails(matchRef(uidZ, m + "-2").update(completePatch(2, ["p1"], fullScores, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-3", { currentRound: 18, maxRounds: 18 });
    check("END.3 (N/A: no seat check by design) Non-member completion attempt -> DENIED",
      await assertFails(matchRef(uidZ, m + "-3").update(completePatch(2, ["p1"], fullScores, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-4", { currentRound: 18, maxRounds: 18 });
    check("END.4 Stale version -> DENIED",
      await assertFails(matchRef(uidA, m + "-4").update(completePatch(1, ["p1"], fullScores, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-5", { currentRound: 18, maxRounds: 18 });
    check("END.5 Tampered protected field (seats) alongside a valid completion -> DENIED",
      await assertFails(matchRef(uidA, m + "-5").update(Object.assign(completePatch(2, ["p1"], fullScores, 18), { seats: { p1: uidZ, p2: uidB, p3: uidC, p4: uidD } })))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-6", { currentRound: 18, maxRounds: 18 });
    check("END.6 Wrong write shape (touches cardLog, an unexpected field) -> DENIED",
      await assertFails(matchRef(uidA, m + "-6").update(Object.assign(completePatch(2, ["p1"], fullScores, 18), {
        cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 }]
      }))).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-7", { currentRound: 10, maxRounds: 18 });
    check("END.7 Invalid state transition (round 10, not yet at maxRounds) -> DENIED",
      await assertFails(matchRef(uidA, m + "-7").update(completePatch(2, ["p1"], fullScores, 10)))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-8", { currentRound: 18, maxRounds: 18, status: "complete" });
    check("END.8 Invalid state transition (already complete, re-targeting) -> DENIED",
      await assertFails(matchRef(uidA, m + "-8").update(completePatch(2, ["p1"], fullScores, 18)))
        .then(function () { return true; }).catch(function () { return false; }));

    // winnerIds must match the actual max-score seat(s) — structural
    // self-consistency the RULE itself does NOT verify (only
    // MatchService.endMatch()'s own winnerIdsMatchFinalScores() does,
    // client-side, before this transaction opens) — see security review.
    await seed(m + "-9", { currentRound: 18, maxRounds: 18 });
    check("END.9 winnerIds naming a non-existent seat -> DENIED",
      await assertFails(matchRef(uidA, m + "-9").update(completePatch(2, ["p9"], fullScores, 18)))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // PHASE 3 — Dispatcher coverage: affectedKeys() routes correctly,
  // and mixed-field writes are never silently accepted.
  // ══════════════════════════════════════════════════════════════
  {
    // bid + unrelated field (currentRound) -> must NOT be silently
    // accepted just because `bids` is present; isValidBidSubmission()'s
    // own hasOnly() must still reject the extra field.
    var m = "disp-1";
    await seed(m, {});
    check("DISP.1 bid + unrelated field (currentRound) -> DENIED, not silently routed/accepted",
      await assertFails(matchRef(uidA, m).update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2, currentRound: 2 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-2", { turn: uidA });
    check("DISP.2 card + unrelated field (maxRounds) -> DENIED, not silently routed/accepted",
      await assertFails(matchRef(uidA, m + "-2").update(Object.assign(cardPatch("p1", 2), { maxRounds: 99 })))
        .then(function () { return true; }).catch(function () { return false; }));

    // The P2-3 concern from the audit: a write touching BOTH `bids` AND
    // `biddingLog` simultaneously. affectedKeys() checks 'gameState' ->
    // 'status' -> 'maxRounds' -> 'currentRound' -> 'cardLog' ->
    // 'biddingLog' -> 'bids' in that exact ternary order — 'biddingLog'
    // is checked BEFORE 'bids', so this should route to
    // isValidBiddingActionSubmission(), whose own hasOnly(['biddingLog',
    // 'version', 'updatedAt']) then correctly rejects the extra `bids`
    // key. Proving this is NOT ambiguously accepted.
    await seed(m + "-3", {});
    check("DISP.3 bids + biddingLog together -> DENIED (routes to biddingLog validator, which rejects the extra `bids` key)",
      await assertFails(matchRef(uidA, m + "-3").update({
        bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true,
        biddingLog: [actionEntry("p1", 1)], version: 2
      })).then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-4", {});
    check("DISP.4 round field (currentRound) + protected field (dealer) -> DENIED, not silently routed/accepted",
      await assertFails(matchRef(uidA, m + "-4").update(Object.assign(advancePatch(2), { dealer: uidZ })))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed(m + "-5", { currentRound: 18, maxRounds: 18 });
    check("DISP.5 completion field (status) + unrelated field (turn) -> DENIED, not silently routed/accepted",
      await assertFails(matchRef(uidA, m + "-5").update(Object.assign(completePatch(2, ["p1"], fullScores, 18), { turn: uidZ })))
        .then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // PHASE 4 — Concurrency / version edge cases (rules-level only —
  // NOT a claim of real multi-client concurrency proof).
  // ══════════════════════════════════════════════════════════════
  {
    await seed("ver-1", {});
    check("VER.1 old version=1, write claims new version=2 (N+1) -> ALLOWED",
      await assertSucceeds(matchRef(uidA, "ver-1").update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed("ver-2", {});
    check("VER.2 old version=1, write claims stale N=1 -> DENIED",
      await assertFails(matchRef(uidA, "ver-2").update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 1 }))
        .then(function () { return true; }).catch(function () { return false; }));

    await seed("ver-3", {});
    check("VER.3 old version=1, write claims skip N+2=3 -> DENIED",
      await assertFails(matchRef(uidA, "ver-3").update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 3 }))
        .then(function () { return true; }).catch(function () { return false; }));

    // Competing-write style: seed at version 1, have uidA win first
    // (version 1->2), then have uidB attempt to write version 1->2 AGAIN
    // against the (now stale, version-2) document — simulates the
    // loser of a real race discovering its own base version is gone.
    await seed("ver-4", {});
    await matchRef(uidA, "ver-4").update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", biddingOpen: true, version: 2 });
    check("VER.4 competing-write style: second writer's stale-base attempt (still claiming version 2) -> DENIED",
      await assertFails(matchRef(uidB, "ver-4").update({ bids: { p1: 5, p2: 7, p3: null, p4: null }, lastBidSeat: "p2", biddingOpen: true, version: 2 }))
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
