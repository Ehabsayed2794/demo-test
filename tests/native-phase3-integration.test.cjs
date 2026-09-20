var REPO_ROOT = require("path").join(__dirname, "..");
// Native Phase 3 — the integration proof the Kotlin services/sync layer
// depends on: a scripted FULL MATCH across four independent clients
// against the REAL, UNCHANGED firestore.rules, plus the same-tick races
// (round advance + match completion) proving EXACTLY ONE winner via
// CONVERGENCE.
//
// Why convergence, not single-winner determinism: the Firestore Emulator
// has ZERO transaction retry — a lost race surfaces as a rules denial.
// So the claim under test is not "client N wins" but "exactly one write
// commits, every loser is denied, and the committed state advanced by
// exactly one step, no phantoms" (docs/specs/03-transactions.md's own
// any-client model — whichever of the four fires first wins; the other
// three re-read committed state and take ALREADY_*).
//
// What is and is not under test:
//   UNDER TEST — the RULES layer: every shape the Kotlin MatchService
//   emits is accepted end to end (startMatch batch, four bids, 52 card
//   appends, the archive+advance pair, the terminal completion), and
//   every racing duplicate is denied by the compiled rules.
//   NOT UNDER TEST — card/bidding LEGALITY. By this project's own
//   established trust boundary (see firestore.rules' own validators),
//   the ruleset verifies SHAPE, not gameplay; the plays below are
//   scripted shape-valid entries, exactly like
//   tests/round-lifecycle.test.cjs's own "cheap fixture, not a duplicate
//   rules engine" choice. The real engine is exercised by the :engine
//   and :services JUnit suites.
//
// Same harness as every other *.rules-emulator.test.cjs here
// (@firebase/rules-unit-testing, rules loaded verbatim from
// firestore.rules, emulator at 127.0.0.1:8080). An unreachable emulator
// is a hard failure that never prints the word the runner hard-fails on.
const {
  initializeTestEnvironment,
  assertSucceeds
} = require("@firebase/rules-unit-testing");
const fs = require("fs");

var pass = 0, fail = 0;
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
}
async function okSucceeds(label, op) {
  try { await assertSucceeds(op); check(label, true); return true; }
  catch (e) { check(label + " — unexpected denial: " + e.message, false); return false; }
}

var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";
var PLAYERS = [uidA, uidB, uidC, uidD];
var SEATS = { p1: uidA, p2: uidB, p3: uidC, p4: uidD };
var SEAT_ORDER = ["p1", "p2", "p3", "p4"];
var SUITS = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"];

function baseMatch(over) {
  var doc = {
    roomId: "room-x", players: PLAYERS.slice(), status: "starting",
    currentRound: 1, maxRounds: 18, extendedRounds: [], dealer: uidA,
    turn: uidA, seats: Object.assign({}, SEATS), version: 1,
    biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null },
    lastBidSeat: null, cardLog: [], lastCardSeat: null, cardPhase: null,
    biddingLog: [], gameState: { initialized: false, dealtRound: 0 }
  };
  if (over) Object.keys(over).forEach(function (k) { doc[k] = over[k]; });
  return doc;
}

// A shape-valid card for play index i (any suit/rank the rules accept —
// legality is the engine's job, not the ruleset's).
function cardFor(i) {
  return { suit: SUITS[i % 4], rank: { v: 2 + (i % 13), s: String(2 + (i % 13)) } };
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

  var A = testEnv.authenticatedContext(uidA);
  var B = testEnv.authenticatedContext(uidB);
  var C = testEnv.authenticatedContext(uidC);
  var D = testEnv.authenticatedContext(uidD);

  // Fires four operations concurrently and reports each one's real
  // outcome — a denied op is a rejection, caught per-op so Promise.all
  // never short-circuits on the losers.
  async function fourWayRace(ops) {
    return Promise.all(ops.map(function (op) {
      return op.then(function () { return { committed: true }; },
        function (e) { return { committed: false, code: (e && e.code) || "DENIED" }; });
    }));
  }
  function summarize(results) {
    var winners = results.filter(function (r) { return r.committed; });
    return winners.length + " committed, " + (4 - winners.length) + " denied";
  }

  // ════════════════════════════════════════════════════════════════
  // PART 1 — a scripted FULL MATCH across four clients: the complete
  // legitimate write sequence the Kotlin MatchService produces, from an
  // empty room to an archived round, accepted end to end by the rules.
  // ════════════════════════════════════════════════════════════════
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "rooms/r-full",
      { creator: uidA, players: PLAYERS.slice(), readyPlayers: PLAYERS.slice(),
        status: "waiting", name: "full", createdAt: 1, updatedAt: 1, matchId: null });
  });

  // 1.1 startMatch — the atomic room↔match pair, from client A.
  await okSucceeds("1.1 startMatch: room->in_game + match create commit atomically",
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-full"), baseMatch({ roomId: "r-full" }))
      .update(A.firestore().collection("rooms").doc("r-full"),
        { status: "in_game", matchId: "m-full", updatedAt: 2 })
      .commit());

  // 1.2 the four Final Estimates — each client owns exactly one seat,
  // each write version+1, and the fourth closes bidding (the rules
  // re-derive biddingOpen from the resulting bids map, never trusting
  // the client's claim).
  var version = 1;
  var bidValues = { p1: 5, p2: 6, p3: 7, p4: 4 };
  for (var s = 0; s < 4; s++) {
    var seat = SEAT_ORDER[s];
    var ctx = [A, B, C, D][s];
    var bids = {};
    for (var t = 0; t <= s; t++) bids[SEAT_ORDER[t]] = bidValues[SEAT_ORDER[t]];
    version++;
    await okSucceeds("1.2." + (s + 1) + " submitBid: seat " + seat + " estimates " + bidValues[seat] +
      (s === 3 ? " and closes bidding" : ""),
      ctx.firestore().collection("matches").doc("m-full")
        .update({ bids: bids, lastBidSeat: seat, version: version,
          biddingOpen: s < 3 }));
  }

  // 1.3 all 52 card plays of the round, rotating through the four
  // clients in seat order: each write appends exactly one entry tagged
  // with the current round, passes the turn to the next real seat owner,
  // and marks the resolving boundary every fourth card. turn starts set
  // (the match create wrote the dealer), so no opening-turn publication
  // is needed for round 1.
  var cardLog = [];
  for (var i = 0; i < 52; i++) {
    var playSeat = SEAT_ORDER[i % 4];
    var nextSeat = SEAT_ORDER[(i + 1) % 4];
    cardLog.push({ seatId: playSeat, card: cardFor(i), round: 1 });
    version++;
    var client = [A, B, C, D][i % 4];
    await okSucceeds("1.3." + (i + 1) + " submitCard: seat " + playSeat +
      " plays " + cardFor(i).suit + " " + cardFor(i).rank.v +
      " (" + (i % 4 === 3 ? "resolving the trick" : "turn -> " + nextSeat) + ")",
      client.firestore().collection("matches").doc("m-full")
        .update({
          cardLog: cardLog.slice(),
          lastCardSeat: playSeat,
          version: version,
          turn: SEATS[nextSeat],
          cardPhase: (i % 4 === 3) ? "RESOLVING" : "PLAY"
        }));
  }

  // 1.4 advanceToNextRound — the archive write and the window reset in
  // ONE transaction, from client B (any client may advance; the
  // transaction is what makes that safe).
  version++;
  await okSucceeds("1.4 advanceToNextRound: round 1 archives and the window resets",
    B.firestore().batch()
      .set(B.firestore().collection("matches").doc("m-full").collection("roundArchive").doc("1"),
        { round: 1, matchId: "m-full", cardLog: cardLog.slice(), biddingLog: [] })
      .update(B.firestore().collection("matches").doc("m-full"), {
        currentRound: 2, dealer: uidB, version: version, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: []
      })
      .commit());

  // 1.5 read back the converged state every client now observes: the
  // archived round is byte-identical to what was played, the live window
  // is empty, and the round advanced by exactly one.
  var settled = await A.firestore().collection("matches").doc("m-full").get();
  var settledMatch = settled.data();
  check("1.5a convergence: currentRound advanced exactly 1 -> 2",
    settledMatch.currentRound === 2, "got " + settledMatch.currentRound);
  check("1.5b convergence: the live cardLog window is empty after the advance",
    Array.isArray(settledMatch.cardLog) && settledMatch.cardLog.length === 0);
  check("1.5c convergence: the live biddingLog window is empty after the advance",
    Array.isArray(settledMatch.biddingLog) && settledMatch.biddingLog.length === 0);
  check("1.5d convergence: the version counter advanced once per write (56 -> " + settledMatch.version + ")",
    settledMatch.version === 1 + 4 + 52 + 1, "got " + settledMatch.version);

  var archived = await A.firestore().collection("matches").doc("m-full")
    .collection("roundArchive").doc("1").get();
  var archivedDoc = archived.data();
  check("1.5e convergence: roundArchive/1 holds all 52 plays verbatim",
    !!archivedDoc && Array.isArray(archivedDoc.cardLog) &&
    archivedDoc.cardLog.length === 52 &&
    JSON.stringify(archivedDoc.cardLog) === JSON.stringify(cardLog));
  check("1.5f convergence: roundArchive/1 is tagged for its own round",
    !!archivedDoc && archivedDoc.round === 1 && archivedDoc.matchId === "m-full");

  // ════════════════════════════════════════════════════════════════
  // PART 2 — the SAME-TICK advance race. Four clients each fire
  // advanceToNextRound for the same completed round on the same tick.
  // Exactly one commits; the rules deny the other three; the round
  // moves by exactly one and no phantom archive appears.
  // ════════════════════════════════════════════════════════════════
  var racePlayed = [];
  for (var r = 0; r < 52; r++) {
    racePlayed.push({ seatId: SEAT_ORDER[r % 4], card: cardFor(r), round: 5 });
  }
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/m-race",
      baseMatch({ currentRound: 5, cardLog: racePlayed }));
  });

  var raceResults = await fourWayRace([
    A.firestore().batch()
      .set(A.firestore().collection("matches").doc("m-race").collection("roundArchive").doc("5"),
        { round: 5, matchId: "m-race", cardLog: racePlayed, biddingLog: [] })
      .update(A.firestore().collection("matches").doc("m-race"), {
        currentRound: 6, dealer: uidB, version: 2, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: []
      }).commit(),
    B.firestore().batch()
      .set(B.firestore().collection("matches").doc("m-race").collection("roundArchive").doc("5"),
        { round: 5, matchId: "m-race", cardLog: racePlayed, biddingLog: [] })
      .update(B.firestore().collection("matches").doc("m-race"), {
        currentRound: 6, dealer: uidB, version: 2, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: []
      }).commit(),
    C.firestore().batch()
      .set(C.firestore().collection("matches").doc("m-race").collection("roundArchive").doc("5"),
        { round: 5, matchId: "m-race", cardLog: racePlayed, biddingLog: [] })
      .update(C.firestore().collection("matches").doc("m-race"), {
        currentRound: 6, dealer: uidB, version: 2, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: []
      }).commit(),
    D.firestore().batch()
      .set(D.firestore().collection("matches").doc("m-race").collection("roundArchive").doc("5"),
        { round: 5, matchId: "m-race", cardLog: racePlayed, biddingLog: [] })
      .update(D.firestore().collection("matches").doc("m-race"), {
        currentRound: 6, dealer: uidB, version: 2, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: []
      }).commit()
  ]);
  var raceWinners = raceResults.filter(function (r) { return r.committed; });

  check("2.1 the same-tick advance race has EXACTLY ONE winner (" + summarize(raceResults) + ")",
    raceWinners.length === 1);
  check("2.2 every losing client was DENIED by the rules (no silent double-advance)",
    raceResults.filter(function (r) { return !r.committed; }).length === 3);

  var raced = await A.firestore().collection("matches").doc("m-race").get();
  var racedMatch = raced.data();
  check("2.3 the race converged the round by exactly one (5 -> 6, never beyond)",
    racedMatch.currentRound === 6, "got " + racedMatch.currentRound);
  check("2.4 the race bumped the version exactly once (1 -> 2)",
    racedMatch.version === 2, "got " + racedMatch.version);
  check("2.5 the race reset the live card window to empty",
    Array.isArray(racedMatch.cardLog) && racedMatch.cardLog.length === 0);
  check("2.6 the race cleared the turn for the new bidding phase",
    racedMatch.turn === null);

  var archiveSnap = await A.firestore().collection("matches").doc("m-race")
    .collection("roundArchive").doc("5").get();
  var archiveDoc = archiveSnap.data();
  check("2.7 roundArchive/5 exists once with all 52 plays",
    !!archiveDoc && Array.isArray(archiveDoc.cardLog) && archiveDoc.cardLog.length === 52);
  var phantom = await A.firestore().collection("matches").doc("m-race")
    .collection("roundArchive").doc("6").get();
  check("2.8 no losing client fabricated a roundArchive/6",
    !phantom.exists);

  // ════════════════════════════════════════════════════════════════
  // PART 3 — the SAME-TICK completion race. Four clients each fire
  // endMatch for the final round on the same tick. Exactly one commits
  // the terminal transition; the rules deny the other three (a completed
  // match is immutable); exactly one winnerIds set lands.
  // ════════════════════════════════════════════════════════════════
  var finalPlayed = [];
  for (var f = 0; f < 52; f++) {
    finalPlayed.push({ seatId: SEAT_ORDER[f % 4], card: cardFor(f), round: 18 });
  }
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await seed(ctx.firestore(), "matches/m-final",
      baseMatch({ currentRound: 18, cardLog: finalPlayed }));
  });

  function endMatch(client) {
    return client.firestore().collection("matches").doc("m-final").update({
      status: "complete", winnerIds: ["p2"],
      finalScores: { p1: 90, p2: 120, p3: 70, p4: 80 },
      completedRound: 18, version: 2, cardLog: [], biddingLog: []
    });
  }
  var finalResults = await fourWayRace([endMatch(A), endMatch(B), endMatch(C), endMatch(D)]);
  var finalWinners = finalResults.filter(function (r) { return r.committed; });

  check("3.1 the same-tick completion race has EXACTLY ONE winner (" + summarize(finalResults) + ")",
    finalWinners.length === 1);
  check("3.2 every losing client was DENIED (a completed match is immutable)",
    finalResults.filter(function (r) { return !r.committed; }).length === 3);

  var completed = await A.firestore().collection("matches").doc("m-final").get();
  var completedMatch = completed.data();
  check("3.3 the match converged to status complete",
    completedMatch.status === "complete", "got " + completedMatch.status);
  check("3.4 exactly one winnerIds set landed (the highest scorer)",
    Array.isArray(completedMatch.winnerIds) && completedMatch.winnerIds.length === 1 &&
    completedMatch.winnerIds[0] === "p2",
    "got " + JSON.stringify(completedMatch.winnerIds));

  var finalArchiveSnap = await A.firestore().collection("matches").doc("m-final")
    .collection("roundArchive").doc("18").get();
  var finalArchive = finalArchiveSnap.data();
  check("3.5 the completed round is archived once with all 52 plays",
    finalArchiveSnap.exists && !!finalArchive && Array.isArray(finalArchive.cardLog) &&
    finalArchive.cardLog.length === 52);

  await okSucceeds("3.6 a rematch vote may now be opened on the completed match",
    A.firestore().collection("matches").doc("m-final")
      .collection("rematchVote").doc("current")
      .set({ matchId: "m-final", seats: Object.assign({}, SEATS),
        votes: { p1: null, p2: null, p3: null, p4: null },
        status: "OPEN", newMatchId: null, version: 1, createdAt: new Date() }));

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
  if (fail > 0) process.exitCode = 1;
}

main().catch(function (e) {
  console.error("HARNESS CRASHED: " + ((e && e.stack) || e));
  process.exitCode = 1;
});
