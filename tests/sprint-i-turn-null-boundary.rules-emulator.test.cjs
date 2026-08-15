const path = require("path");
// Portability fix (established convention this session — see every
// other tests/*.test.cjs file): never hardcode this sandbox's own
// absolute path.
const __REPO_ROOT__ = path.join(__dirname, "..");

// SPRINT I — Firestore Card Submission Permission-Denied Forensic
// Investigation. Minimal, focused, real-Firestore-Rules-Emulator
// reproduction of the exact blocker discovered by the Sprint G/H real
// 4-client E2E harness: a card submission repeatedly denied with
// `permission-denied` mid-match, always on the SAME seat, never
// resolving no matter how many times the harness retried
// ("STALLED_ON_SAME_SEAT").
//
// ROOT CAUSE (identified by static trace of firestore.rules'
// isValidCardSubmission(), design-ui/match-service.js's submitCard(),
// and design-ui/match-adapter.js's isLocalSeatsTurn(), confirmed here
// against the REAL compiled rules): `isValidCardSubmission()` requires
//   oldData.turn == request.auth.uid
// unconditionally, for EVERY card submission -- including the first
// card of a NEW trick. But `submitCard()` legitimately writes
// `turn: null` at the "resolving boundary" (the 4th card of a trick --
// see match-service.js's own `nextTurnUid = null` when
// `preview.nextTurnSeat` is null), and NOTHING in this codebase's
// write path ever transitions Firestore's own `turn` field FROM null
// back to the real next-trick leader's uid -- `resolveTrick()` in
// match-service.js is an unimplemented stub (trick resolution is
// purely client-side/local, via TableEngine.resolveTrick(), which
// never writes to Firestore). The ONLY write that could ever set
// `turn` away from `null` is the very next submitCard() call (the next
// trick's first card) -- but THAT call's own oldData.turn is still
// `null` at the moment it's evaluated, so `oldData.turn ==
// request.auth.uid` can NEVER be true for ANY real uid once `turn`
// becomes null. This is a deterministic, structural deadlock -- not a
// race, not a timing issue -- which is exactly why the real harness saw
// the SAME seat's SAME submission denied identically on every retry.
//
// This gap was never previously tested: tests/rules-simulation.test.js's
// own "Sprint 4.2.3, Task 3" cases (search that file for
// fourSeatCardSubmissionWithTurn) exhaustively prove writing turn TO
// null is allowed, and writing a fresh non-null turn FROM a non-null
// oldData.turn is allowed -- but EVERY one of those fixtures uses
// `matchAfterCreate423_fourSeats`, whose own `turn` field is always the
// concrete uid "userB". None of them -- and no test in
// tests/sprint-a-write-paths.rules-emulator.test.cjs either -- ever
// construct an `oldData.turn === null` fixture and attempt the NEXT
// card submission against it. That exact case is what this file adds.
//
// This is an INVESTIGATION-ONLY file per Sprint I's own explicit
// instruction: it proves the failing predicate and its exact trigger
// condition. It does NOT implement, propose in code, or apply any fix
// to firestore.rules, match-service.js, or match-adapter.js.
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
      projectId: "demo-test-sprint-i",
      firestore: {
        rules: fs.readFileSync(__REPO_ROOT__ + "/firestore.rules", "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    console.error("EMULATOR NOT REACHABLE — " + e.message);
    console.error(
      "\nFATAL: the Firestore Rules Emulator must be running on " +
      "127.0.0.1:8080 for this test to run. This is a HARD FAILURE, " +
      "not a skip."
    );
    console.error("\n=== RESULTS ===\n");
    console.error("0 passed, 0 failed (FAILED — emulator unreachable)");
    process.exit(1);
  }

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";

  // Same base-match shape as sprint-a-write-paths.rules-emulator.test.cjs
  // (this project's own established fixture convention) -- every field
  // isValidMatchUpdateDispatch()/isValidCardSubmission() reads is
  // present, so the ONLY intentionally-anomalous value in each seeded
  // fixture below is `turn`.
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
  function matchRef(uid, matchId) { return testEnv.authenticatedContext(uid).firestore().collection("matches").doc(matchId); }

  // A completed first trick (4 real cards, p1 through p4) -- exactly
  // what a real match's cardLog looks like the instant the 4th card of
  // trick 1 is submitted: the SAME submitCard() call that completes the
  // trick also sets turn:null, cardPhase:"RESOLVING" (per
  // match-service.js's own patch, mirrored here).
  var completedTrickLog = [
    { seatId: "p1", card: { suit: "SPADES", rank: { v: 12, s: "Q" } }, round: 1 },
    { seatId: "p2", card: { suit: "SPADES", rank: { v: 5, s: "5" } }, round: 1 },
    { seatId: "p3", card: { suit: "SPADES", rank: { v: 8, s: "8" } }, round: 1 },
    { seatId: "p4", card: { suit: "SPADES", rank: { v: 2, s: "2" } }, round: 1 }
  ];

  // ══════════════════════════════════════════════════════════════
  // CONTROL — the exact write shape below, with a CONCRETE (non-null)
  // oldData.turn matching the caller, already ALLOWED (this mirrors
  // sprint-a-write-paths.rules-emulator.test.cjs's own CARD.1 and
  // proves nothing else in this fixture is accidentally malformed).
  // ══════════════════════════════════════════════════════════════
  {
    var mControl = "turn-null-control";
    await seed(mControl, { turn: uidC, cardLog: completedTrickLog, version: 5, lastCardSeat: "p4", cardPhase: "RESOLVING" });
    check("CONTROL: with a CONCRETE oldData.turn (uidC) matching the caller, the next trick's first card -> ALLOWED",
      await assertSucceeds(matchRef(uidC, mControl).update({
        cardLog: completedTrickLog.concat([{ seatId: "p3", card: { suit: "HEARTS", rank: { v: 9, s: "9" } }, round: 1 }]),
        lastCardSeat: "p3", turn: uidD, cardPhase: "PLAY", version: 6, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // THE REPRODUCTION — identical write shape, identical caller (the
  // structurally-legitimate next-trick leader, per the SAME seats map),
  // identical cardLog/version/round/card-shape correctness -- the ONLY
  // difference from the CONTROL above is oldData.turn is null (the
  // real, legitimate "resolving boundary" state match-service.js's own
  // submitCard() writes at the 4th card of every trick).
  // ══════════════════════════════════════════════════════════════
  {
    var mRepro = "turn-null-repro";
    await seed(mRepro, { turn: null, cardLog: completedTrickLog, version: 5, lastCardSeat: "p4", cardPhase: "RESOLVING" });
    check("REPRODUCTION: with oldData.turn === null (the real post-4th-card 'resolving boundary' state), the structurally-legitimate next-trick leader's card submission -> DENIED",
      await assertFails(matchRef(uidC, mRepro).update({
        cardLog: completedTrickLog.concat([{ seatId: "p3", card: { suit: "HEARTS", rank: { v: 9, s: "9" } }, round: 1 }]),
        lastCardSeat: "p3", turn: uidD, cardPhase: "PLAY", version: 6, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));

    // Prove this isn't specific to uidC/p3 -- literally NO seat, not
    // even a fresh seed per attempt, can ever cross this boundary once
    // turn is null. This is the exact "STALLED_ON_SAME_SEAT" signature
    // the real harness observed: every seat's attempt is denied
    // identically, forever, because the underlying state never changes.
    await seed(mRepro + "-p1", { turn: null, cardLog: completedTrickLog, version: 5, lastCardSeat: "p4", cardPhase: "RESOLVING" });
    check("REPRODUCTION (any seat, not just p3): p1 attempting to lead the next trick against the SAME null-turn state -> ALSO DENIED",
      await assertFails(matchRef(uidA, mRepro + "-p1").update({
        cardLog: completedTrickLog.concat([{ seatId: "p1", card: { suit: "HEARTS", rank: { v: 9, s: "9" } }, round: 1 }]),
        lastCardSeat: "p1", turn: uidB, cardPhase: "PLAY", version: 6, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));

    // Prove the SAME seed, retried with the IDENTICAL write again,
    // fails IDENTICALLY every time (deterministic, not a one-shot
    // fluke or a race that would eventually succeed on retry) -- this
    // is what the real harness's "3 stall retries, identical error"
    // observation directly corresponds to.
    check("REPRODUCTION (deterministic, not a race): retrying the IDENTICAL write against the IDENTICAL unchanged document fails IDENTICALLY every time",
      await assertFails(matchRef(uidC, mRepro).update({
        cardLog: completedTrickLog.concat([{ seatId: "p3", card: { suit: "HEARTS", rank: { v: 9, s: "9" } }, round: 1 }]),
        lastCardSeat: "p3", turn: uidD, cardPhase: "PLAY", version: 6, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  console.log("\n=== Sprint I: Firestore turn-null trick-boundary reproduction ===\n");
  console.log(pass + " passed, " + fail + " failed");
  if (fail > 0) process.exit(1);
  await testEnv.cleanup();
}

run().catch(function (e) {
  console.error("FATAL:", e);
  process.exit(1);
});
