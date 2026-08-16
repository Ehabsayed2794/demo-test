const path = require("path");
const __REPO_ROOT__ = path.join(__dirname, "..");

// SPRINT J.7 — Unified Bidding Completion + Seat Membership Security Fix.
// Real Firestore Rules Emulator proof of two changes:
//   1. isValidBiddingActionSubmission() may now ALSO accept a
//      SubmitConfirmCall write that mirrors the Caller's own confirmed
//      trick count into their OWN `bids` slot (closing the Sprint J.4/
//      J.5.2-confirmed gap that made `allSeatsNowHaveBids` unreachable
//      for the dominant real-caller path) — and, on the rare/adversarial
//      completion edge, the SAME turn/cardPhase establishment
//      isValidBidSubmission() already has.
//   2. Both isValidBidSubmission() and isValidBiddingActionSubmission()'s
//      round-start seat-membership check now requires a seat to
//      actually be a key of `oldData.seats` before comparing it against
//      `newData.turn` — closing the Sprint J.5.1-confirmed 2/3-player
//      absent-seat-null-fallback vulnerability.
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
function ok(p) { return p.then(function () { return true; }).catch(function () { return false; }); }

async function run() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "demo-test-sprint-j7",
      firestore: { rules: fs.readFileSync(__REPO_ROOT__ + "/firestore.rules", "utf8"), host: "127.0.0.1", port: 8080 }
    });
  } catch (e) {
    console.error("EMULATOR NOT REACHABLE — " + e.message);
    console.error("\n=== RESULTS ===\n0 passed, 0 failed (FAILED — emulator unreachable)");
    process.exit(1);
  }

  var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";

  function baseMatch(seats, overrides) {
    var players = Object.keys(seats).map(function (s) { return seats[s]; });
    var m = {
      roomId: "room-x", players: players, status: "starting", createdAt: 1,
      currentRound: 2, maxRounds: 18, extendedRounds: [], dealer: players[0], turn: null,
      seats: seats, version: 10, biddingOpen: true,
      bids: (function () { var b = {}; Object.keys(seats).forEach(function (s) { b[s] = null; }); return b; })(),
      lastBidSeat: null, cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      gameState: { initialized: true, dealtRound: 2 }
    };
    Object.assign(m, overrides || {});
    return m;
  }
  async function seed(matchId, doc) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(doc);
    });
  }
  function matchRef(uid, matchId) { return testEnv.authenticatedContext(uid).firestore().collection("matches").doc(matchId); }

  var FOUR = { p1: uidA, p2: uidB, p3: uidC, p4: uidD };
  var THREE = { p1: uidA, p2: uidB, p3: uidC };
  var TWO = { p1: uidA, p2: uidB };

  function confirmCallEntry(seatId, tricks) {
    return { seatId: seatId, actionType: "SubmitConfirmCall", tricks: tricks, suit: "SPADES", round: 2 };
  }

  // ══════════════════════════════════════════════════════════════
  // A. Normal Caller path: Confirm mirrors bids[caller], does NOT
  //    complete bidding by itself (3 seats still missing) -> ALLOWED,
  //    biddingOpen stays true.
  // ══════════════════════════════════════════════════════════════
  {
    var mA = "j7-A-confirm-mirrors-bid";
    await seed(mA, baseMatch(FOUR, { biddingLog: [] }));
    check("J.7 A: SubmitConfirmCall mirrors caller's own bid into bids[p1], biddingOpen stays true (3 seats still missing) -> ALLOWED",
      await ok(matchRef(uidA, mA).update({
        biddingLog: [confirmCallEntry("p1", 4)], version: 11,
        bids: { p1: 4, p2: null, p3: null, p4: null }, biddingOpen: true, updatedAt: 1
      })));
  }

  // ══════════════════════════════════════════════════════════════
  // A2. Full normal-caller completion: caller's bid already mirrored,
  //     then the 3 non-caller Estimates arrive via ordinary submitBid()
  //     shape (isValidBidSubmission()), the LAST one completing the
  //     round -> ALLOWED, turn/cardPhase established.
  // ══════════════════════════════════════════════════════════════
  {
    var mA2 = "j7-A2-full-normal-completion";
    await seed(mA2, baseMatch(FOUR, {
      biddingLog: [confirmCallEntry("p1", 4)],
      bids: { p1: 4, p2: 3, p3: 2, p4: null }, lastBidSeat: "p3", version: 11
    }));
    check("J.7 A2: last non-caller estimate (p4) completes bidding -> ALLOWED, turn/cardPhase established",
      await ok(matchRef(uidD, mA2).update({
        bids: { p1: 4, p2: 3, p3: 2, p4: 5 }, biddingOpen: false, version: 12,
        lastBidSeat: "p4", turn: uidA, cardPhase: "PLAY", updatedAt: 1
      })));
  }

  // ══════════════════════════════════════════════════════════════
  // B. Fast No-Caller path: all 4 seats submit via ordinary submitBid()
  //    shape, unaffected by this sprint -> ALLOWED (regression check).
  // ══════════════════════════════════════════════════════════════
  {
    var mB = "j7-B-fast-no-caller";
    await seed(mB, baseMatch(FOUR, { bids: { p1: 6, p2: 3, p3: 2, p4: null }, lastBidSeat: "p3", version: 11 }));
    check("J.7 B: fast round, 4th (no-caller) estimate completes bidding -> ALLOWED (unaffected by this sprint)",
      await ok(matchRef(uidD, mB).update({
        bids: { p1: 6, p2: 3, p3: 2, p4: 1 }, biddingOpen: false, version: 12,
        lastBidSeat: "p4", turn: uidA, cardPhase: "PLAY", updatedAt: 1
      })));
  }

  // ══════════════════════════════════════════════════════════════
  // C. Super Call: verified reality (traced during implementation) —
  //    by the time a fast round's 4th real Estimate lands via ordinary
  //    submitBid(), `bids` already reaches 4/4 and `biddingOpen` already
  //    flips false THERE (via isValidBidSubmission()'s existing,
  //    unchanged completion branch) — the SUBSEQUENT SubmitConfirmCall
  //    (the Super Call itself) therefore arrives AFTER completion
  //    already happened, with `bids[superCaller]` already non-null (from
  //    their own earlier Estimate). MatchService.submitBiddingAction()'s
  //    new guard (`bids[seatId] == null` required) correctly detects
  //    this and skips the bids/turn/cardPhase patch entirely — so this
  //    write must remain an ORDINARY biddingLog-only append (no
  //    regression, no double-write attempt) -> ALLOWED. (The fast-round
  //    leader-timing gap this reveals — the EARLIER submitBid() write
  //    may not resolve the true Super Caller as leader — is a SEPARATE,
  //    still-open, explicitly documented issue, not fixed by this test.)
  // ══════════════════════════════════════════════════════════════
  {
    var mC = "j7-C-super-call-ordinary-append";
    await seed(mC, baseMatch(FOUR, {
      bids: { p1: 9, p2: 3, p3: 2, p4: 1 }, lastBidSeat: "p4", biddingOpen: false, version: 11,
      turn: uidD, cardPhase: "PLAY", biddingLog: []
    }));
    check("J.7 C: Super Call ConfirmCall arriving after bidding already completed -> ALLOWED as ordinary biddingLog-only append",
      await ok(matchRef(uidA, mC).update({
        biddingLog: [confirmCallEntry("p1", 9)], version: 12, updatedAt: 1
      })));
  }

  // ══════════════════════════════════════════════════════════════
  // D. Caller Seat Ownership: p1 (uidA) tries to write p2's bid slot
  //    via a forged ConfirmCall claiming seatId p2 -> DENIED (seat
  //    ownership check: appended.seatId must belong to request.auth.uid).
  // ══════════════════════════════════════════════════════════════
  {
    var mD = "j7-D-wrong-seat-ownership";
    await seed(mD, baseMatch(FOUR, {}));
    check("J.7 D: p1 (uidA) forging a ConfirmCall claiming seatId p2 -> DENIED (seat ownership)",
      await assertFails(matchRef(uidA, mD).update({
        biddingLog: [confirmCallEntry("p2", 4)], version: 11,
        bids: { p1: null, p2: 4, p3: null, p4: null }, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // E. Early Completion: a ConfirmCall claims turn/cardPhase while
  //    non-caller seats' bids are still missing -> DENIED
  //    (allSeatsNowHaveBids false).
  // ══════════════════════════════════════════════════════════════
  {
    var mE = "j7-E-early-completion";
    await seed(mE, baseMatch(FOUR, {}));
    check("J.7 E: ConfirmCall smuggling turn/cardPhase while 3 seats still missing bids -> DENIED",
      await assertFails(matchRef(uidA, mE).update({
        biddingLog: [confirmCallEntry("p1", 4)], version: 11,
        bids: { p1: 4, p2: null, p3: null, p4: null }, biddingOpen: false,
        turn: uidA, cardPhase: "PLAY", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // F/G/H. Seat-membership matrix: turn:null DENIED, legitimate
  //    occupied leader ALLOWED, for 2/3/4-player matches.
  // ══════════════════════════════════════════════════════════════
  // The genuine, real submitBid()-shaped completing write: the LAST
  // seat's bid arrives, biddingOpen flips false, turn/cardPhase
  // established in the SAME write — 'bids' must genuinely be in the
  // diff for the dispatcher to route to isValidBidSubmission() at all.
  function completionSetup(seats) {
    var seatIds = Object.keys(seats);
    var bids = {}; seatIds.forEach(function (s, idx) { bids[s] = (idx === seatIds.length - 1) ? null : 5; });
    return baseMatch(seats, { bids: bids, lastBidSeat: null, version: 11 });
  }
  function completingBids(seats) {
    var seatIds = Object.keys(seats);
    var bids = {}; seatIds.forEach(function (s, idx) { bids[s] = (idx === seatIds.length - 1) ? 5 : 5; });
    return bids;
  }
  var sizes = [
    { name: "2-player", seats: TWO, actingSeat: "p2", actingUid: uidB, leaderSeat: "p1", leaderUid: uidA, absentSeat: "p3" },
    { name: "3-player", seats: THREE, actingSeat: "p3", actingUid: uidC, leaderSeat: "p2", leaderUid: uidB, absentSeat: "p4" },
    { name: "4-player", seats: FOUR, actingSeat: "p4", actingUid: uidD, leaderSeat: "p3", leaderUid: uidC, absentSeat: null }
  ];
  for (var i = 0; i < sizes.length; i++) {
    var sz = sizes[i];
    var mNull = "j7-F-" + sz.name + "-turn-null";
    await seed(mNull, completionSetup(sz.seats));
    check("J.7 F: turn:null on genuine completion (" + sz.name + ") -> DENIED",
      await assertFails(matchRef(sz.actingUid, mNull).update({
        bids: completingBids(sz.seats), lastBidSeat: sz.actingSeat,
        version: 12, biddingOpen: false, turn: null, cardPhase: "PLAY", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));

    var mLeader = "j7-G-" + sz.name + "-legit-leader";
    await seed(mLeader, completionSetup(sz.seats));
    check("J.7 G: legitimate occupied-seat leader on genuine completion (" + sz.name + ") -> ALLOWED",
      await ok(matchRef(sz.actingUid, mLeader).update({
        bids: completingBids(sz.seats), lastBidSeat: sz.actingSeat,
        version: 12, biddingOpen: false, turn: sz.leaderUid, cardPhase: "PLAY", updatedAt: 1
      })));

    if (sz.absentSeat) {
      var mAbsent = "j7-absent-" + sz.name;
      await seed(mAbsent, completionSetup(sz.seats));
      check("J.7: absent seat's fabricated presence (" + sz.absentSeat + " in " + sz.name + ") cannot satisfy membership -> DENIED",
        await assertFails(matchRef(sz.actingUid, mAbsent).update({
          bids: completingBids(sz.seats), lastBidSeat: sz.actingSeat,
          version: 12, biddingOpen: false, turn: "fabricated-" + sz.absentSeat + "-uid", cardPhase: "PLAY", updatedAt: 1
        })).then(function () { return true; }).catch(function () { return false; }));
    }
  }

  // ══════════════════════════════════════════════════════════════
  // H. Duplicate completion (replay after success) -> DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var mH = "j7-H-duplicate-completion";
    await seed(mH, completionSetup(FOUR));
    check("J.7 H setup: first genuine completion succeeds",
      await ok(matchRef(uidD, mH).update({
        bids: completingBids(FOUR), lastBidSeat: "p4", version: 12, biddingOpen: false, turn: uidC, cardPhase: "PLAY", updatedAt: 1
      })));
    check("J.7 H: replaying the identical completing write again -> DENIED",
      await assertFails(matchRef(uidD, mH).update({
        bids: completingBids(FOUR), lastBidSeat: "p4", version: 12, biddingOpen: false, turn: uidA, cardPhase: "PLAY", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // I. Stale completion (version already moved) -> DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var mI = "j7-I-stale-completion";
    await seed(mI, Object.assign(completionSetup(FOUR), { version: 12 }));
    check("J.7 I: completion write computed against a stale version -> DENIED",
      await assertFails(matchRef(uidD, mI).update({
        bids: completingBids(FOUR), lastBidSeat: "p4", version: 12, biddingOpen: false, turn: uidC, cardPhase: "PLAY", updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // J. Wrong seat writing another seat's bids via the Confirm path
  //    (bids diff touches more than the claimed seat) -> DENIED
  // ══════════════════════════════════════════════════════════════
  {
    var mJ = "j7-J-wrong-seat-bids-diff";
    await seed(mJ, baseMatch(FOUR, {}));
    check("J.7 J: ConfirmCall write also touching a DIFFERENT seat's bids entry -> DENIED",
      await assertFails(matchRef(uidA, mJ).update({
        biddingLog: [confirmCallEntry("p1", 4)], version: 11,
        bids: { p1: 4, p2: 9, p3: null, p4: null }, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  // ══════════════════════════════════════════════════════════════
  // K. Ordinary Dash/Auction actions still cannot touch bids/turn/cardPhase.
  // ══════════════════════════════════════════════════════════════
  {
    var mK = "j7-K-ordinary-action-cannot-touch-bids";
    await seed(mK, baseMatch(FOUR, {}));
    check("J.7 K: an ordinary SubmitDashCallDecision cannot smuggle a bids/turn write -> DENIED",
      await assertFails(matchRef(uidA, mK).update({
        biddingLog: [{ seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: false, round: 2 }],
        version: 11, bids: { p1: 4, p2: null, p3: null, p4: null }, updatedAt: 1
      })).then(function () { return true; }).catch(function () { return false; }));
  }

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed" + (fail ? " (FAILED)" : ""));
  process.exit(fail ? 1 : 0);
}

run().catch(function (e) { console.error(e); process.exit(1); });
