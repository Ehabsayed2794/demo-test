var REPO_ROOT = require("path").join(__dirname, "..");
// Native Phase 3 (Kotlin MatchService) — REAL Firestore Rules Emulator
// negative-shape guard for every transaction shape the Kotlin services
// module writes.
//
// What this proves: each of the 8 transaction shapes in
// docs/specs/03-transactions.md is enforced INDEPENDENTLY by the
// UNCHANGED firestore.rules — a Kotlin client (or any client) that
// emits a shape the spec does not describe is DENIED, not written.
// Per the Phase 3 definition of done: "a wrong transaction shape is
// denied by the unchanged firestore.rules (negative test per shape)."
//
// Every shape gets a POSITIVE first — the exact field set
// MatchService.kt emits — so each negative is meaningful: a denial on a
// shape the legitimate path also uses would mean the rules broke, not
// that the test passed. The negative then mutates ONE thing that crosses
// a rule (a foreign key, a skipped version, an unemptied window, a wrong
// round tag, an unpaired atomic write) and asserts PERMISSION_DENIED.
//
// This file runs the same harness every other *.rules-emulator.test.cjs
// in this suite uses (@firebase/rules-unit-testing against the emulator
// at 127.0.0.1:8080, rules loaded verbatim from firestore.rules). It is
// auto-discovered by scripts/run-tests.mjs, so the JS CI job already
// covers it; android.yml adds a dedicated emulator job so the native PR
// proves it on its own.
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
async function okSucceeds(label, op) {
  try { await assertSucceeds(op); check(label, true); return true; }
  catch (e) { check(label + " — unexpected denial: " + e.message, false); return false; }
}
async function okFails(label, op) {
  try { await assertFails(op); check(label, true); return true; }
  catch (e) { check(label + " — write was ACCEPTED, must be denied", false); return false; }
}

// The exact base shape MatchService.buildInitialMatchDoc() / the Kotlin
// MatchDoc.toFields() emit — any deviation here is itself a bug.
var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD", uidZ = "uidZ";
var PLAYERS = [uidA, uidB, uidC, uidD];
var SEATS = { p1: uidA, p2: uidB, p3: uidC, p4: uidD };
var NULL_BIDS = { p1: null, p2: null, p3: null, p4: null };
var NOT_DEALT = { initialized: false, dealtRound: 0 };

function baseMatch(over) {
  var doc = {
    roomId: "room-x", players: PLAYERS.slice(), status: "starting",
    currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA,
    turn: uidA, seats: Object.assign({}, SEATS), version: 1,
    biddingOpen: true, bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: Object.assign({}, NOT_DEALT)
  };
  if (over) Object.keys(over).forEach(function (k) { doc[k] = over[k]; });
  return doc;
}

function readyRoom() {
  return {
    creator: uidA, players: PLAYERS.slice(), readyPlayers: PLAYERS.slice(),
    status: "waiting", name: "r", createdAt: 1, updatedAt: 1, matchId: null
  };
}

// 52 round-tagged cards — the archive's own size gate.
function fiftyTwoCards(round) {
  var out = [];
  var suits = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
  for (var s = 0; s < suits.length; s++) {
    for (var v = 2; v <= 14; v++) {
      out.push({ seatId: "p1", card: { suit: suits[s], rank: { v: v, s: String(v) } }, round: round });
    }
  }
  return out;
}

// 13 well-formed cards for one seat's hand document.
function thirteenCards() {
  var out = [];
  for (var v = 2; v <= 14; v++) {
    out.push({ suit: "SPADES", rank: { v: v, s: String(v) } });
  }
  return out;
}

async function seed(db, path, data) {
  var parts = path.split("/");
  var ref = db;
  for (var i = 0; i < parts.length; i += 2) ref = ref.collection(parts[i]).doc(parts[i + 1]);
  await ref.set(data);
}

async function main() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      // Phase 3's own project id — no real Firebase, no credentials.
      projectId: "demo-test-ci",
      firestore: {
        rules: fs.readFileSync(REPO_ROOT + "/firestore.rules", "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    console.log("EMULATOR NOT REACHABLE — " + e.message);
    console.log("A real Firestore Rules Emulator must be running at 127.0.0.1:8080 for this suite.");
    console.log("\n=== RESULTS ===\n0 passed, 0 failed (emulator required; nothing was silently ignored)");
    process.exitCode = 2;
    return;
  }

  // Every precondition document is written with the rules disabled; the
  // writes UNDER TEST are the only rules-evaluated ones.
  var n = 0;
  function freshMatch(over) {
    var id = "m-" + (++n);
    return { id: id, doc: baseMatch(over) };
  }
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    var db = ctx.firestore();
    await seed(db, "rooms/r-s1", readyRoom());
    await seed(db, "rooms/r-s1c", readyRoom());
    await seed(db, "rooms/r-s1d", readyRoom());
  });

  var A = testEnv.authenticatedContext(uidA);
  var B = testEnv.authenticatedContext(uidB);
  var Z = testEnv.authenticatedContext(uidZ);

  // ════════════════════════════════════════════════════════════════
  // S1. startMatch — the atomic room+match pair
  // ════════════════════════════════════════════════════════════════
  await okSucceeds("S1.1 POSITIVE: the paired room->in_game + match create commits atomically",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-s1"), baseMatch({ roomId: "r-s1" }))
      .update(A.firestore().collection("rooms").doc("r-s1"), { status: "in_game", matchId: "m-s1", updatedAt: 2 })
      .commit());

  await okFails("S1.2 NEGATIVE: a match created WITHOUT its paired room transition is denied",
    A.firestore().collection("matches").doc("m-s1b").set(baseMatch({ roomId: "r-s1d" })));

  await okFails("S1.3 NEGATIVE: a room flipped to in_game with a matchId that does not exist is denied",
    A.firestore().collection("rooms").doc("r-s1c")
      .update({ status: "in_game", matchId: "m-s1c-missing", updatedAt: 2 }));

  await okFails("S1.4 NEGATIVE: a match create carrying a field outside the create whitelist is denied",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-s1d"),
        baseMatch({ roomId: "r-s1d", evilField: "not a real match field" }))
      .update(A.firestore().collection("rooms").doc("r-s1d"), { status: "in_game", matchId: "m-s1d", updatedAt: 2 })
      .commit());

  // ════════════════════════════════════════════════════════════════
  // S2. submitBid — the Final Estimate write
  // ════════════════════════════════════════════════════════════════
  var s2 = freshMatch();
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s2.id, s2.doc);
  });
  await okSucceeds("S2.1 POSITIVE: one owned seat's null bid becomes 5, version+1",
    A.firestore().collection("matches").doc(s2.id)
      .update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true }));

  var s2b = freshMatch();
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s2b.id, s2b.doc);
  });
  await okFails("S2.2 NEGATIVE: a bid that skips the version counter is denied",
    A.firestore().collection("matches").doc(s2b.id)
      .update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 3, biddingOpen: true }));

  await okFails("S2.3 NEGATIVE: a bid that also rewrites the immutable dealer is denied",
    A.firestore().collection("matches").doc(s2b.id)
      .update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true, dealer: uidD }));

  await okFails("S2.4 NEGATIVE: a bid from a uid who is not a player is denied",
    Z.firestore().collection("matches").doc(s2b.id)
      .update({ bids: { p1: 5, p2: null, p3: null, p4: null }, lastBidSeat: "p1", version: 2, biddingOpen: true }));

  // ════════════════════════════════════════════════════════════════
  // S3. submitBiddingAction — the Dash/Auction/Confirm log
  // ════════════════════════════════════════════════════════════════
  var s3 = freshMatch();
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s3.id, s3.doc);
  });
  await okSucceeds("S3.1 POSITIVE: one well-formed Dash-Call entry appends, version+1",
    A.firestore().collection("matches").doc(s3.id)
      .update({ biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }], version: 2 }));

  // On the log that S3.1 already grew, a two-entry write would still be
  // size-consistent (2 == 1+1), so its denial would come from the
  // appended seat's ownership, not from the one-entry rule. A fresh empty
  // log is what actually pins "exactly one entry per write".
  var s3b = freshMatch();
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s3b.id, s3b.doc);
  });
  await okFails("S3.2 NEGATIVE: batching two actions into one write is denied",
    A.firestore().collection("matches").doc(s3b.id).update({
      biddingLog: [
        { seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 },
        { seatId: "p2", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1 }
      ], version: 2
    }));

  await okFails("S3.3 NEGATIVE: an action entry carrying a foreign key is denied",
    A.firestore().collection("matches").doc(s3.id).update({
      biddingLog: [{ seatId: "p2", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 1, evilKey: 1 }],
      version: 3
    }));

  await okFails("S3.4 NEGATIVE: an action tagged for the wrong round is denied",
    A.firestore().collection("matches").doc(s3.id).update({
      biddingLog: [{ seatId: "p2", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 7 }],
      version: 3
    }));

  // ════════════════════════════════════════════════════════════════
  // S4. submitCard — the card-log append
  // ════════════════════════════════════════════════════════════════
  var s4 = freshMatch();
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s4.id, s4.doc);
  });
  await okSucceeds("S4.1 POSITIVE: one owned card appends, version+1, turn passes to a real seat owner",
    A.firestore().collection("matches").doc(s4.id).update({
      cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 14, s: "A" } }, round: 1 }],
      lastCardSeat: "p1", version: 2, turn: uidB, cardPhase: "PLAY"
    }));

  await okFails("S4.2 NEGATIVE: a card write that does not increment the version is denied",
    A.firestore().collection("matches").doc(s4.id).update({
      cardLog: [
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 14, s: "A" } }, round: 1 },
        { seatId: "p2", card: { suit: "HEARTS", rank: { v: 14, s: "A" } }, round: 1 }
      ],
      lastCardSeat: "p2", version: 2, turn: uidC, cardPhase: "PLAY"
    }));

  await okFails("S4.3 NEGATIVE: a card played by the seat that does not own the current turn is denied",
    A.firestore().collection("matches").doc(s4.id).update({
      cardLog: [
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 14, s: "A" } }, round: 1 },
        { seatId: "p1", card: { suit: "CLUBS", rank: { v: 2, s: "2" } }, round: 1 }
      ],
      lastCardSeat: "p1", version: 3, turn: uidC, cardPhase: "PLAY"
    }));

  await okFails("S4.4 NEGATIVE: a card tagged for a round the match is not on is denied",
    B.firestore().collection("matches").doc(s4.id).update({
      cardLog: [
        { seatId: "p1", card: { suit: "SPADES", rank: { v: 14, s: "A" } }, round: 1 },
        { seatId: "p2", card: { suit: "HEARTS", rank: { v: 14, s: "A" } }, round: 9 }
      ],
      lastCardSeat: "p2", version: 3, turn: uidC, cardPhase: "PLAY"
    }));

  // ════════════════════════════════════════════════════════════════
  // S5. advanceToNextRound — the archive + window reset
  // ════════════════════════════════════════════════════════════════
  var s5 = freshMatch({ currentRound: 5, dealer: uidA });
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s5.id, s5.doc);
  });
  await okSucceeds("S5.1 POSITIVE: the archive write and the round advance commit atomically",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc(s5.id).collection("roundArchive").doc("5"),
        { round: 5, matchId: s5.id, cardLog: fiftyTwoCards(5), biddingLog: [] })
      .update(A.firestore().collection("matches").doc(s5.id), {
        currentRound: 6, dealer: uidB, version: 2, biddingOpen: true,
        bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: []
      })
      .commit());

  var s5b = freshMatch({ currentRound: 5, dealer: uidA });
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s5b.id, s5b.doc);
  });
  await okFails("S5.2 NEGATIVE: an advance that leaves the cardLog non-empty is denied",
    A.firestore().collection("matches").doc(s5b.id).update({
      currentRound: 6, dealer: uidB, version: 2, biddingOpen: true,
      bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
      cardPhase: null, turn: null, cardLog: [{ seatId: "p1", card: { suit: "SPADES", rank: { v: 2, s: "2" } }, round: 5 }],
      biddingLog: []
    }));

  await okFails("S5.3 NEGATIVE: an advance that keeps a turn set instead of clearing it is denied",
    A.firestore().collection("matches").doc(s5b.id).update({
      currentRound: 6, dealer: uidB, version: 2, biddingOpen: true,
      bids: Object.assign({}, NULL_BIDS), lastBidSeat: null,
      cardPhase: null, turn: uidA, cardLog: [], biddingLog: []
    }));

  await okFails("S5.4 NEGATIVE: an archive with fewer than 52 cards is denied",
    A.firestore().collection("matches").doc(s5b.id).collection("roundArchive").doc("5")
      .set({ round: 5, matchId: s5b.id, cardLog: fiftyTwoCards(5).slice(1), biddingLog: [] }));

  await okFails("S5.5 NEGATIVE: an archive for a round the match has not reached is denied",
    A.firestore().collection("matches").doc(s5b.id).collection("roundArchive").doc("9")
      .set({ round: 9, matchId: s5b.id, cardLog: fiftyTwoCards(9), biddingLog: [] }));

  // ════════════════════════════════════════════════════════════════
  // S6. extendMatchRounds — the Rapid-Round extension
  // ════════════════════════════════════════════════════════════════
  var s6 = freshMatch({ currentRound: 14, maxRounds: 18, extendedRounds: [] });
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s6.id, s6.doc);
  });
  await okSucceeds("S6.1 POSITIVE: a qualifying round 14 extends maxRounds 18->19 once",
    A.firestore().collection("matches").doc(s6.id)
      .update({ maxRounds: 19, extendedRounds: [14], version: 2 }));

  // cardLog:[] would be a no-op here ([] -> [] is not an affected key at
  // all, so the whitelist would let it through); biddingOpen is a field
  // no extension may ever touch, so this is a real shape violation.
  await okFails("S6.2 NEGATIVE: an extension that also closes bidding is denied",
    A.firestore().collection("matches").doc(s6.id)
      .update({ maxRounds: 20, extendedRounds: [14, 15], version: 3, biddingOpen: false }));

  await okFails("S6.3 NEGATIVE: an extension recording a round outside the Rapid-Round window is denied",
    A.firestore().collection("matches").doc(s6.id)
      .update({ maxRounds: 20, extendedRounds: [14, 13], version: 3 }));

  await okFails("S6.4 NEGATIVE: re-extending on an already-recorded round is denied",
    A.firestore().collection("matches").doc(s6.id)
      .update({ maxRounds: 20, extendedRounds: [14, 14], version: 3 }));

  // ════════════════════════════════════════════════════════════════
  // S7. endMatch — the terminal transition
  // ════════════════════════════════════════════════════════════════
  var s7 = freshMatch({ currentRound: 18, maxRounds: 18 });
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s7.id, s7.doc);
  });
  await okSucceeds("S7.1 POSITIVE: the highest scorer wins and the match completes",
    A.firestore().collection("matches").doc(s7.id).update({
      status: "complete", winnerIds: ["p1"],
      finalScores: { p1: 100, p2: 50, p3: 50, p4: 50 },
      completedRound: 18, version: 2, cardLog: [], biddingLog: []
    }));

  var s7b = freshMatch({ currentRound: 18, maxRounds: 18 });
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s7b.id, s7b.doc);
  });
  await okFails("S7.2 NEGATIVE: declaring a winner who did not have the highest score is denied",
    A.firestore().collection("matches").doc(s7b.id).update({
      status: "complete", winnerIds: ["p2"],
      finalScores: { p1: 100, p2: 50, p3: 50, p4: 50 },
      completedRound: 18, version: 2, cardLog: [], biddingLog: []
    }));

  await okFails("S7.3 NEGATIVE: a completion whose completedRound is not the current round is denied",
    A.firestore().collection("matches").doc(s7b.id).update({
      status: "complete", winnerIds: ["p1"],
      finalScores: { p1: 100, p2: 50, p3: 50, p4: 50 },
      completedRound: 17, version: 2, cardLog: [], biddingLog: []
    }));

  await okFails("S7.4 NEGATIVE: a completion that leaves status non-terminal is denied",
    A.firestore().collection("matches").doc(s7b.id).update({
      status: "starting", winnerIds: ["p1"],
      finalScores: { p1: 100, p2: 50, p3: 50, p4: 50 },
      completedRound: 18, version: 2, cardLog: [], biddingLog: []
    }));

  // ════════════════════════════════════════════════════════════════
  // S8. dealRound — the paired hand + gameState commit
  // ════════════════════════════════════════════════════════════════
  var s8 = freshMatch();
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s8.id, s8.doc);
  });
  await okSucceeds("S8.1 POSITIVE: four 13-card hands plus the gameState flip commit atomically",
    (function () {
      var b = A.firestore().batch();
      ["p1", "p2", "p3", "p4"].forEach(function (seat) {
        b.set(A.firestore().collection("matches").doc(s8.id).collection("hands").doc(seat),
          { seatId: seat, round: 1, cards: thirteenCards(), version: 1 });
      });
      b.update(A.firestore().collection("matches").doc(s8.id),
        { gameState: { initialized: true, dealtRound: 1 }, updatedAt: 2 });
      return b.commit();
    })());

  var s8b = freshMatch();
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s8b.id, s8b.doc);
  });
  await okFails("S8.2 NEGATIVE: a 12-card hand is denied",
    (function () {
      var b = A.firestore().batch();
      ["p1", "p2", "p3", "p4"].forEach(function (seat) {
        b.set(A.firestore().collection("matches").doc(s8b.id).collection("hands").doc(seat),
          { seatId: seat, round: 1, cards: thirteenCards().slice(1), version: 1 });
      });
      b.update(A.firestore().collection("matches").doc(s8b.id),
        { gameState: { initialized: true, dealtRound: 1 }, updatedAt: 2 });
      return b.commit();
    })());

  await okFails("S8.3 NEGATIVE: the gameState flip without its paired hand writes is denied",
    A.firestore().collection("matches").doc(s8b.id)
      .update({ gameState: { initialized: true, dealtRound: 1 }, updatedAt: 2 }));

  await okFails("S8.4 NEGATIVE: a hand document carrying a foreign key is denied",
    (function () {
      var b = A.firestore().batch();
      ["p1", "p2", "p3", "p4"].forEach(function (seat) {
        b.set(A.firestore().collection("matches").doc(s8b.id).collection("hands").doc(seat),
          { seatId: seat, round: 1, cards: thirteenCards(), version: 1, evilKey: 1 });
      });
      b.update(A.firestore().collection("matches").doc(s8b.id),
        { gameState: { initialized: true, dealtRound: 1 }, updatedAt: 2 });
      return b.commit();
    })());

  // ════════════════════════════════════════════════════════════════
  // S9. rematch vote — create + cast
  // ════════════════════════════════════════════════════════════════
  // createdAt MUST be a real Timestamp: the cast rule derives the 30s
  // deadline as oldData.createdAt + duration.value(30,'s'), so a plain
  // number here would make every cast fail on a CEL type error instead
  // of on the shape we are actually pinning. The JS SDK converts a Date
  // to a Timestamp, exactly as hand-sync.rules-emulator-rematch-fix does.
  var s9 = freshMatch({ status: "complete" });
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s9.id, s9.doc);
  });
  await okSucceeds("S9.1 POSITIVE: a seated player opens the vote with one null slot per seat",
    A.firestore().collection("matches").doc(s9.id).collection("rematchVote").doc("current")
      .set({ matchId: s9.id, seats: Object.assign({}, SEATS), votes: Object.assign({}, NULL_BIDS),
        status: "OPEN", newMatchId: null, version: 1, createdAt: new Date() }));

  await okSucceeds("S9.2 POSITIVE: a seat casting its own still-null YES vote is accepted",
    A.firestore().collection("matches").doc(s9.id).collection("rematchVote").doc("current")
      .update({ votes: { p1: "YES", p2: null, p3: null, p4: null }, status: "OPEN", version: 2 }));

  await okFails("S9.3 NEGATIVE: flipping an already-cast vote is denied (votes are immutable)",
    A.firestore().collection("matches").doc(s9.id).collection("rematchVote").doc("current")
      .update({ votes: { p1: "NO", p2: null, p3: null, p4: null }, status: "FAILED_NO", version: 3 }));

  await okFails("S9.4 NEGATIVE: a vote cast by a uid with no seat in this match is denied",
    Z.firestore().collection("matches").doc(s9.id).collection("rematchVote").doc("current")
      .update({ votes: { p1: "YES", p2: null, p3: null, p4: null }, status: "OPEN", version: 3 }));

  var s9b = freshMatch({ status: "complete" });
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/" + s9b.id, s9b.doc);
  });
  await okFails("S9.5 NEGATIVE: a vote doc that starts already-decided is denied",
    A.firestore().collection("matches").doc(s9b.id).collection("rematchVote").doc("current")
      .set({ matchId: s9b.id, seats: Object.assign({}, SEATS), votes: Object.assign({}, NULL_BIDS),
        status: "ALL_YES", newMatchId: null, version: 1, createdAt: new Date() }));

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
  if (fail > 0) process.exitCode = 1;
}

main().catch(function (e) {
  console.error("HARNESS CRASHED: " + ((e && e.stack) || e));
  process.exitCode = 1;
});
