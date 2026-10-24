// O-19 / Option B — the persisted `currentMultiplier` rules surface, against
// the REAL Firestore emulator (not the JS simulation).
//
// Covers:
//   * the ordinary scored advance still accepted, with the multiplier bounded
//     (1..8) and Classic pinned to 1;
//   * the General Pass branch: S1's actual provable window (N declining Dash
//     decisions followed by N auction passes) accepted with the exact ladder
//     value only — never a jump, never a skipped round, never a non-player;
//   * the pass archive (`passRound: true`, empty cardLog, that exact window)
//     accepted; an ordinary archive still requires 52 cards;
//   * under-four seat maps (the seat-count correction to the draft's
//     hardcoded four), and the unprovable Dash-call variant refused.
//
// Run with a live emulator on 127.0.0.1:8080. If unavailable the suite exits 2
// and prints STATUS BLOCKED — never a green result.

const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const { initializeTestEnvironment, assertSucceeds, assertFails } = require("@firebase/rules-unit-testing");

const REPO_ROOT = path.join(__dirname, "..");
const RULES_PATH = path.join(REPO_ROOT, "firestore.rules");
const RULES = fs.readFileSync(RULES_PATH, "utf8");
const RULES_HASH = crypto.createHash("sha256").update(RULES).digest("hex");
const TEST_PROJECT_ID = "o19-multiplier-" + (typeof process !== "undefined" ? process.pid : "rn") + "-" + Date.now();

let passed = 0;
let failed = 0;
const failures = [];
function check(label, ok, note) {
  if (ok) {
    console.log("PASS  " + label);
    passed += 1;
  } else {
    console.log("FAIL  " + label + (note ? " -- " + note : ""));
    failed += 1;
    failures.push(label);
  }
}

const A = "uidA", B = "uidB", C = "uidC", D = "uidD", Z = "uidZ";
const players4 = [A, B, C, D];
const seats4 = { p1: A, p2: B, p3: C, p4: D };

function bidsFor(seats) {
  const out = {};
  Object.keys(seats).forEach((seat) => { out[seat] = null; });
  return out;
}

function baseMatch(players, seats, overrides) {
  return Object.assign({
    roomId: "room-o19",
    players: players.slice(),
    status: "starting",
    createdAt: new Date(),
    currentRound: 1,
    maxRounds: 18,
    extendedRounds: [],
    dealer: players[0],
    turn: players[0],
    seats: Object.assign({}, seats),
    version: 5,
    biddingOpen: true,
    bids: bidsFor(seats),
    lastBidSeat: null,
    cardLog: [],
    lastCardSeat: null,
    cardPhase: null,
    biddingLog: [],
    gameState: { initialized: true, dealtRound: 1 },
    gameType: "FULL",
    scoringMode: "NORMAL",
    currentMultiplier: 1
  }, overrides || {});
}

// S1's canonical general-pass window: one declining Dash decision per seat,
// then one auction PASS per seat, every entry tagged with the round.
// F1 (owner ruling 2026-10-24, Option A): dashCallSeats generalises it to
// the MIXED window — of the N Dash decisions exactly k are DECLARED
// (0 <= k <= min(2, N-1)), and the auction holds exactly N-k passes from
// the remaining seats (total 2N-k entries).
function passWindow(seats, round, opts) {
  const options = opts || {};
  const dashers = options.dashCallSeats || [];
  const seatIds = Object.keys(seats);
  const log = [];
  seatIds.forEach((seat) => {
    log.push({
      seatId: seat,
      actionType: "SubmitDashCallDecision",
      declaredDashCall: dashers.includes(seat),
      round
    });
  });
  seatIds.forEach((seat) => {
    if (dashers.includes(seat)) return;
    log.push({ seatId: seat, actionType: "SubmitAuctionBid", isPass: true, round });
  });
  return log;
}

function passPatch(round, newDealer, multiplier, overrides) {
  return Object.assign({
    currentRound: round + 1,
    dealer: newDealer,
    version: 6,
    biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null },
    lastBidSeat: null,
    cardPhase: null,
    turn: null,
    cardLog: [],
    biddingLog: [],
    currentMultiplier: multiplier,
    updatedAt: new Date()
  }, overrides || {});
}

function ordinaryCardLog(round) {
  const log = [];
  const suits = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
  for (let i = 0; i < 52; i += 1) {
    log.push({
      seatId: "p" + ((i % 4) + 1),
      card: { suit: suits[i % 4], rank: { v: (i % 13) + 2, s: String((i % 13) + 2) } },
      round
    });
  }
  return log;
}

async function main() {
  console.log("RULES_PATH " + RULES_PATH);
  console.log("RULES_SHA256 " + RULES_HASH);
  let env;
  try {
    env = await initializeTestEnvironment({
      projectId: TEST_PROJECT_ID,
      firestore: { rules: RULES, host: "127.0.0.1", port: 8080 }
    });
  } catch (error) {
    console.log("STATUS BLOCKED — REAL EMULATOR UNAVAILABLE: " + error.message);
    if (typeof process !== "undefined") process.exitCode = 2;
    return;
  }

  const ctx = (uid) => env.authenticatedContext(uid).firestore();
  const ref = (uid, id) => ctx(uid).collection("matches").doc(id);

  let seq = 0;
  async function seed(overrides) {
    seq += 1;
    const id = "o19-m-" + seq;
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc(id).set(baseMatch(players4, seats4, overrides));
    });
    return id;
  }

  try {
    // ── 1. ordinary scored advance: bounded value accepted ────────────
    let id = await seed({
      currentRound: 2, dealer: A, version: 5, currentMultiplier: 2,
      cardLog: ordinaryCardLog(2)
    });
    check("ordinary 52-card Normal advance carrying nextMultiplier 4 -> ALLOW",
      await assertSucceeds(ref(A, id).update({
        currentRound: 3, dealer: B, version: 6, biddingOpen: true,
        bids: bidsFor(seats4), lastBidSeat: null, cardPhase: null, turn: null,
        cardLog: [], biddingLog: [], currentMultiplier: 4, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    id = await seed({
      currentRound: 2, dealer: A, version: 5, currentMultiplier: 4,
      scoringMode: "CLASSIC", cardLog: ordinaryCardLog(2)
    });
    check("ordinary Classic advance pinned to 1 -> ALLOW",
      await assertSucceeds(ref(A, id).update({
        currentRound: 3, dealer: B, version: 6, biddingOpen: true,
        bids: bidsFor(seats4), lastBidSeat: null, cardPhase: null, turn: null,
        cardLog: [], biddingLog: [], currentMultiplier: 1, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    id = await seed({
      currentRound: 2, dealer: A, version: 5, currentMultiplier: 2,
      scoringMode: "CLASSIC", cardLog: ordinaryCardLog(2)
    });
    check("ordinary Classic advance claiming 2 -> DENY",
      await assertFails(ref(A, id).update({
        currentRound: 3, dealer: B, version: 6, biddingOpen: true,
        bids: bidsFor(seats4), lastBidSeat: null, cardPhase: null, turn: null,
        cardLog: [], biddingLog: [], currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    id = await seed({
      currentRound: 2, dealer: A, version: 5, currentMultiplier: 4,
      cardLog: ordinaryCardLog(2)
    });
    check("ordinary advance claiming 9 (outside 1..8) -> DENY",
      await assertFails(ref(A, id).update({
        currentRound: 3, dealer: B, version: 6, biddingOpen: true,
        bids: bidsFor(seats4), lastBidSeat: null, cardPhase: null, turn: null,
        cardLog: [], biddingLog: [], currentMultiplier: 9, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    id = await seed({
      currentRound: 2, dealer: A, version: 5, currentMultiplier: 2,
      cardLog: ordinaryCardLog(2)
    });
    check("ordinary advance without currentMultiplier (legacy client) -> ALLOW",
      await assertSucceeds(ref(A, id).update({
        currentRound: 3, dealer: B, version: 6, biddingOpen: true,
        bids: bidsFor(seats4), lastBidSeat: null, cardPhase: null, turn: null,
        cardLog: [], biddingLog: [], updatedAt: new Date()
      })).then(() => true).catch(() => false));

    // A legacy doc (no field) reads as 1 and an ordinary advance stays legal.
    id = await seed({ currentRound: 2, dealer: A, version: 5, cardLog: ordinaryCardLog(2) });
    await env.withSecurityRulesDisabled(async (admin) => {
      const ref2 = admin.firestore().collection("matches").doc(id);
      const snap = await ref2.get();
      const data = snap.data();
      delete data.currentMultiplier;
      await ref2.set(data);
    });
    check("legacy doc without currentMultiplier: ordinary advance -> ALLOW",
      await assertSucceeds(ref(A, id).update({
        currentRound: 3, dealer: B, version: 6, biddingOpen: true,
        bids: bidsFor(seats4), lastBidSeat: null, cardPhase: null, turn: null,
        cardLog: [], biddingLog: [], currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    // ── 2. General Pass: the provable S1 window, exact ladder ─────────
    const ladder = [
      ["Normal 1 -> 2", 1, 2],
      ["Normal 2 -> 4", 2, 4],
      ["Normal 4 -> 6", 4, 6],
      ["Normal 6 -> 8", 6, 8],
      ["Normal 8 -> 8 (cap)", 8, 8]
    ];
    for (let i = 0; i < ladder.length; i += 1) {
      const [label, oldValue, nextValue] = ladder[i];
      id = await seed({
        currentRound: 1, dealer: A, version: 5, currentMultiplier: oldValue,
        biddingLog: passWindow(seats4, 1)
      });
      check("General Pass " + label + " -> ALLOW",
        await assertSucceeds(ref(A, id).update(passPatch(1, B, nextValue)))
          .then(() => true).catch(() => false));
    }

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      scoringMode: "CLASSIC", biddingLog: passWindow(seats4, 1)
    });
    check("General Pass Classic 1 -> 1 -> ALLOW",
      await assertSucceeds(ref(A, id).update(passPatch(1, B, 1)))
        .then(() => true).catch(() => false));

    // ── 3. General Pass tampering ─────────────────────────────────────
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1)
    });
    check("General Pass raising 1 -> 8 -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 8)))
        .then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1)
    });
    check("General Pass skipping a round (1 -> 3) -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2, { currentRound: 3 })))
        .then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      scoringMode: "CLASSIC", biddingLog: passWindow(seats4, 1)
    });
    check("General Pass Classic claiming 2 -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1)
    });
    check("General Pass by a non-player -> DENY",
      await assertFails(ref(Z, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      status: "complete", biddingLog: passWindow(seats4, 1)
    });
    check("General Pass on a complete match -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    // ── 3b. F1 (owner ruling 2026-10-24, Option A): the MIXED window ───
    // Two effective Dashes + a pass from every remaining active seat IS a
    // valid General Pass: N Dash decisions of which exactly k are DECLARED
    // (k <= min(2, N-1)), then N-k auction passes. Total 2N-k entries
    // (four seats: 8/7/6 at k = 0/1/2). The ladder applies unchanged.
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1, { dashCallSeats: ["p3"] })
    });
    check("F1 k=1 window (7 entries, p3 declared) advancing 1 -> 2 -> ALLOW",
      await assertSucceeds(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 2,
      biddingLog: passWindow(seats4, 1, { dashCallSeats: ["p1", "p4"] })
    });
    check("F1 k=2 window (6 entries) advancing 2 -> 4 -> ALLOW",
      await assertSucceeds(ref(A, id).update(passPatch(1, B, 4)))
        .then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1, { dashCallSeats: ["p2", "p3"] })
    });
    check("F1 k=2 window claiming a ladder jump (1 -> 4) -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 4)))
        .then(() => true).catch(() => false));

    // The strict two-Dash maximum: a window claiming THREE declared Dashes
    // is refused by name in the service proof (TOO_MANY_DASH_CALLS) and
    // denied here — never silently clamped to two.
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1, { dashCallSeats: ["p1", "p2", "p3"] })
    });
    check("F1 k=3 window (three declared Dashes) -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    // A pass entry from a seat that already declared an effective Dash —
    // an engine-impossible history the window proof refuses.
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1, { dashCallSeats: ["p1"] })
        .map((entry, index, all) => index === all.length - 1
          ? { seatId: "p1", actionType: "SubmitAuctionBid", isPass: true, round: 1 }
          : entry)
    });
    check("F1 pass block containing a declared dasher's seat -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    // Duplicate seat inside the Dash block.
    const dupDash = passWindow(seats4, 1, { dashCallSeats: ["p1", "p2"] });
    dupDash[1] = { seatId: "p1", actionType: "SubmitDashCallDecision", declaredDashCall: true, round: 1 };
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: dupDash
    });
    check("F1 duplicate seat in the Dash block -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    // The pass archive accepts the mixed window too (same proof).
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1, { dashCallSeats: ["p2", "p4"] })
    });
    check("pass archive with an F1 k=2 window -> ALLOW",
      await assertSucceeds(ctx(A).collection("matches").doc(id)
        .collection("roundArchive").doc("1").set({
          round: 1, matchId: id, cardLog: [],
          biddingLog: passWindow(seats4, 1, { dashCallSeats: ["p2", "p4"] }),
          estimates: {}, passRound: true
        })).then(() => true).catch(() => false));

    // ── 3c. Duplicate seat in the pass block / missing pass ────────────
    const dup = passWindow(seats4, 1);
    dup[7].seatId = dup[6].seatId;
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1, biddingLog: dup
    });
    check("General Pass with a duplicate PASS seat -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1).slice(0, 7)
    });
    check("General Pass missing one auction pass -> DENY",
      await assertFails(ref(A, id).update(passPatch(1, B, 2)))
        .then(() => true).catch(() => false));

    // ── 4. under-four seat maps (the seat-count correction) ───────────
    const players2 = [A, B];
    const seats2 = { p1: A, p2: B };
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc("o19-two").set(
        baseMatch(players2, seats2, {
          currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
          biddingLog: passWindow(seats2, 1)
        })
      );
    });
    check("two-seat General Pass 1 -> 2 -> ALLOW",
      await assertSucceeds(ref(A, "o19-two").update({
        currentRound: 2, dealer: B, version: 6, biddingOpen: true,
        bids: { p1: null, p2: null }, lastBidSeat: null, cardPhase: null,
        turn: null, cardLog: [], biddingLog: [], currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    const players3 = [A, B, C];
    const seats3 = { p1: A, p2: B, p3: C };
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc("o19-three").set(
        baseMatch(players3, seats3, {
          currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
          biddingLog: passWindow(seats3, 1)
        })
      );
    });
    check("three-seat General Pass 1 -> 2 -> ALLOW",
      await assertSucceeds(ref(A, "o19-three").update({
        currentRound: 2, dealer: B, version: 6, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null }, lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: [],
        currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    // F1 at under-four tables: N=2 admits k<=1, N=3 k<=2 — and an
    // ALL-dashing table (k = N) is SANS, never a General Pass.
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc("o19-two-mixed").set(
        baseMatch(players2, seats2, {
          currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
          biddingLog: passWindow(seats2, 1, { dashCallSeats: ["p2"] })
        })
      );
      await admin.firestore().collection("matches").doc("o19-two-alldash").set(
        baseMatch(players2, seats2, {
          currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
          biddingLog: passWindow(seats2, 1, { dashCallSeats: ["p1", "p2"] })
        })
      );
      await admin.firestore().collection("matches").doc("o19-three-mixed").set(
        baseMatch(players3, seats3, {
          currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
          biddingLog: passWindow(seats3, 1, { dashCallSeats: ["p1", "p3"] })
        })
      );
    });
    check("two-seat F1 k=1 window -> ALLOW",
      await assertSucceeds(ref(A, "o19-two-mixed").update({
        currentRound: 2, dealer: B, version: 6, biddingOpen: true,
        bids: { p1: null, p2: null }, lastBidSeat: null, cardPhase: null,
        turn: null, cardLog: [], biddingLog: [], currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    check("two-seat ALL-dash window (k = N) -> DENY",
      await assertFails(ref(A, "o19-two-alldash").update({
        currentRound: 2, dealer: B, version: 6, biddingOpen: true,
        bids: { p1: null, p2: null }, lastBidSeat: null, cardPhase: null,
        turn: null, cardLog: [], biddingLog: [], currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    check("three-seat F1 k=2 window -> ALLOW",
      await assertSucceeds(ref(A, "o19-three-mixed").update({
        currentRound: 2, dealer: B, version: 6, biddingOpen: true,
        bids: { p1: null, p2: null, p3: null }, lastBidSeat: null,
        cardPhase: null, turn: null, cardLog: [], biddingLog: [],
        currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("matches").doc("o19-two-bad").set(
        baseMatch(players2, seats2, {
          currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
          biddingLog: passWindow(seats4, 1)
        })
      );
    });
    check("two-seat parent with a four-pass window (draft's hardcoded shape) -> DENY",
      await assertFails(ref(A, "o19-two-bad").update({
        currentRound: 2, dealer: B, version: 6, biddingOpen: true,
        bids: { p1: null, p2: null }, lastBidSeat: null, cardPhase: null,
        turn: null, cardLog: [], biddingLog: [], currentMultiplier: 2, updatedAt: new Date()
      })).then(() => true).catch(() => false));

    // ── 5. roundArchive: pass branch vs the ordinary 52-card gate ─────
    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1)
    });
    check("pass archive (passRound, empty cardLog, exact window) -> ALLOW",
      await assertSucceeds(ctx(A).collection("matches").doc(id)
        .collection("roundArchive").doc("1").set({
          round: 1, matchId: id, cardLog: [],
          biddingLog: passWindow(seats4, 1), estimates: {}, passRound: true
        })).then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1)
    });
    check("archive with an empty cardLog and no pass marker -> DENY (52-card gate)",
      await assertFails(ctx(A).collection("matches").doc(id)
        .collection("roundArchive").doc("1").set({
          round: 1, matchId: id, cardLog: [],
          biddingLog: passWindow(seats4, 1), estimates: {}
        })).then(() => true).catch(() => false));

    id = await seed({
      currentRound: 1, dealer: A, version: 5, currentMultiplier: 1,
      biddingLog: passWindow(seats4, 1)
    });
    check("pass archive whose window contains a real bid -> DENY",
      await assertFails(ctx(A).collection("matches").doc(id)
        .collection("roundArchive").doc("1").set({
          round: 1, matchId: id, cardLog: [],
          biddingLog: passWindow(seats4, 1).map((entry, index) => index === 7
            ? { seatId: entry.seatId, actionType: "SubmitAuctionBid", isPass: false, tricks: 4, suit: "SPADES", round: 1 }
            : entry),
          estimates: {}, passRound: true
        })).then(() => true).catch(() => false));

    id = await seed({ currentRound: 1, dealer: A, version: 5, currentMultiplier: 1 });
    check("ordinary 52-card archive still accepted",
      await assertSucceeds(ctx(A).collection("matches").doc(id)
        .collection("roundArchive").doc("1").set({
          round: 1, matchId: id, cardLog: ordinaryCardLog(1),
          biddingLog: [], estimates: {}
        })).then(() => true).catch(() => false));

    // ── 6. creation allowlists carry the field ────────────────────────
    // The first-match create path needs a real room binding; exercise the
    // simplest shape the rules accept (see dealer-rotation's own fixture).
    await env.withSecurityRulesDisabled(async (admin) => {
      await admin.firestore().collection("rooms").doc("o19-room").set({
        creator: A, players: players4.slice(), readyPlayers: players4.slice(),
        status: "waiting", name: "o19-room", createdAt: new Date(), updatedAt: new Date()
      });
    });
    const createDoc = baseMatch(players4, seats4, {
      roomId: "o19-room", currentRound: 1, version: 1,
      gameState: { initialized: false, dealtRound: 0 }
    });
    check("match creation carrying currentMultiplier = 1 -> ALLOW",
      await assertSucceeds((() => {
        const db = ctx(A);
        const roomRef = db.collection("rooms").doc("o19-room");
        return db.runTransaction(async (tx) => {
          await tx.get(roomRef);
          tx.set(db.collection("matches").doc("o19-created"), createDoc);
          tx.update(roomRef, { status: "in_game", matchId: "o19-created", updatedAt: new Date() });
        });
      })()).then(() => true).catch(() => false));

    const badCreate = baseMatch(players4, seats4, {
      roomId: "o19-room", currentRound: 1, version: 1,
      gameState: { initialized: false, dealtRound: 0 },
      scoringMode: "CLASSIC", currentMultiplier: 3
    });
    check("Classic creation with currentMultiplier = 3 -> DENY",
      await assertFails((() => {
        const db = ctx(A);
        const roomRef = db.collection("rooms").doc("o19-room");
        return db.runTransaction(async (tx) => {
          await tx.get(roomRef);
          tx.set(db.collection("matches").doc("o19-created-bad"), badCreate);
          tx.update(roomRef, { status: "in_game", matchId: "o19-created-bad", updatedAt: new Date() });
        });
      })()).then(() => true).catch(() => false));
  } finally {
    await env.cleanup();
  }

  console.log("\n=== RESULTS ===\n");
  console.log(passed + " passed, " + failed + " failed");
  if (failures.length) console.log(failures.map((x) => "- " + x).join("\n"));
  if (typeof process !== "undefined") process.exitCode = failed ? 1 : 0;
}

const done = main().catch((error) => {
  console.error(error);
  if (typeof process !== "undefined") process.exitCode = 1;
});

module.exports = { done };
