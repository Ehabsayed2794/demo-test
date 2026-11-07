// E6b / S71 — Vote Kick's mode gate, rules layer.
//
// RD28 (amended 2026-10-17): Vote Kick is PRESENT in RANKED and UNRANKED;
// ROOM is the only mode without it. The engine already said so
// (engine/VoteKick.kt — only MatchMode.ROOM is excluded), so the rules were
// the last thing denying an Unranked table its vote. This file is the
// coverage for that unfreeze: the three RANKED-only gates (the cooldown
// write, the removal write, and the open shape) now read the mode through
// one shared helper, `isVoteKickMode`.
//
//   * V1: a vote OPENS on an UNRANKED match, still opens on a RANKED one,
//     is refused on a ROOM one, is refused on a pre-S43 match (no mode
//     field ⇒ ROOM), and the two gates the unfreeze did NOT touch (the
//     round gate, the target-slot shape) still refuse on UNRANKED;
//   * V2: the cooldown write lands on an UNRANKED match and is refused on
//     a ROOM one;
//   * V3: the removal write lands on an UNRANKED match and is refused on
//     a ROOM one;
//   * V4: the rules file's hash equals the recorded pin — the rules and
//     the pin move together, never one without the other.
//
// The three modes are probed with the IDENTICAL payload, so the only thing
// that separates them is the mode: a shape defect cannot masquerade as a
// mode verdict.
//
// Same harness as every other *.rules-emulator.test.cjs in this suite:
// @firebase/rules-unit-testing against the real Firestore Rules Emulator
// at 127.0.0.1:8080, rules loaded verbatim from firestore.rules. Auto-
// discovered by scripts/run-tests.mjs.
//
// Doc ids carry a per-run suffix: the suites share ONE emulator with no
// clearing, so a fixed id would turn this suite's own second run into an
// update of the first run's vote doc (a create becomes an update, and the
// update path does not accept a fresh open).
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails
} = require("@firebase/rules-unit-testing");
var crypto = require("crypto");
var fs = require("fs");

var REPO_ROOT = require("path").join(__dirname, "..");

var pass = 0, fail = 0;
function check(label, ok) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
async function okSucceeds(label, op) {
  try { await assertSucceeds(op); check(label, true); }
  catch (e) { check(label + " — unexpected denial: " + e.message, false); }
}
async function okFails(label, op) {
  try { await assertFails(op); check(label, true); }
  catch (e) { check(label + " — write was ACCEPTED, must be denied", false); }
}

var uidA = "uidA", uidB = "uidB", uidC = "uidC", uidD = "uidD";
var PLAYERS = [uidA, uidB, uidC, uidD];
var SEATS = { p1: uidA, p2: uidB, p3: uidC, p4: uidD };
var RUN = Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36);

// A seated four-human match. `mode` absent models a genuine pre-S43
// document; `undefined` means the key is ABSENT, never null.
function baseMatch(mode, currentRound) {
  var doc = {
    roomId: "room-s71", players: PLAYERS.slice(), status: "in_game",
    createdAt: 1, currentRound: currentRound, maxRounds: 18, extendedRounds: [],
    dealer: uidA, turn: uidA, seats: Object.assign({}, SEATS), version: 1,
    biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: { initialized: true, dealtRound: currentRound },
    gameType: "FULL", scoringMode: "NORMAL", updatedAt: 1
  };
  if (mode !== undefined) doc.mode = mode;
  return doc;
}

// The vote the client opens: the target's slot is STRUCTURALLY ABSENT from
// `votes` (never null-valued) and every other human seat carries a null
// slot, exactly as engine openVoteKick() builds it
// (votes = eligible.associateWith { null }).
function openVote(matchId, roundOpened, votes) {
  return {
    matchId: matchId, targetSeat: "p3", initiatorSeat: "p1",
    reason: "idle", votes: votes, status: "OPEN",
    roundOpened: roundOpened, version: 1
  };
}

var GOOD_VOTES = { p1: null, p2: null, p4: null };

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

  // ── the seeded matches ──────────────────────────────────────────
  var ID = {
    unranked: "m-s71-" + RUN + "-unranked",
    unrankedEarly: "m-s71-" + RUN + "-unranked-early",
    unrankedBadShape: "m-s71-" + RUN + "-unranked-badshape",
    ranked: "m-s71-" + RUN + "-ranked",
    room: "m-s71-" + RUN + "-room",
    legacy: "m-s71-" + RUN + "-legacy",
    unrankedCooldown: "m-s71-" + RUN + "-unranked-cooldown",
    roomCooldown: "m-s71-" + RUN + "-room-cooldown",
    unrankedRemoval: "m-s71-" + RUN + "-unranked-removal",
    roomRemoval: "m-s71-" + RUN + "-room-removal"
  };

  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    var db = ctx.firestore();
    await seed(db, "matches/" + ID.unranked, baseMatch("UNRANKED", 8));
    await seed(db, "matches/" + ID.unrankedEarly, baseMatch("UNRANKED", 7));
    await seed(db, "matches/" + ID.unrankedBadShape, baseMatch("UNRANKED", 8));
    await seed(db, "matches/" + ID.ranked, baseMatch("RANKED", 8));
    await seed(db, "matches/" + ID.room, baseMatch("ROOM", 8));
    await seed(db, "matches/" + ID.legacy, baseMatch(undefined, 8));
    await seed(db, "matches/" + ID.unrankedCooldown, baseMatch("UNRANKED", 8));
    await seed(db, "matches/" + ID.roomCooldown, baseMatch("ROOM", 8));
    await seed(db, "matches/" + ID.unrankedRemoval, baseMatch("UNRANKED", 8));
    await seed(db, "matches/" + ID.roomRemoval, baseMatch("ROOM", 8));
  });

  function voteRef(matchId) {
    return A.firestore().collection("matches").doc(matchId)
      .collection("voteKick").doc("current");
  }

  // ════════════════════════════════════════════════════════════════
  // V1. the open write — the gate the unfreeze moved
  // ════════════════════════════════════════════════════════════════
  await okSucceeds("V1.1 POSITIVE: a vote OPENS on an UNRANKED match (RD28)",
    voteRef(ID.unranked).set(openVote(ID.unranked, 8, Object.assign({}, GOOD_VOTES))));

  await okSucceeds("V1.2 POSITIVE: a vote still opens on a RANKED match (no regression)",
    voteRef(ID.ranked).set(openVote(ID.ranked, 8, Object.assign({}, GOOD_VOTES))));

  await okFails("V1.3 NEGATIVE: a vote is refused on a ROOM match",
    voteRef(ID.room).set(openVote(ID.room, 8, Object.assign({}, GOOD_VOTES))));

  await okFails("V1.4 NEGATIVE: a vote is refused on a pre-S43 match (no mode ⇒ ROOM)",
    voteRef(ID.legacy).set(openVote(ID.legacy, 8, Object.assign({}, GOOD_VOTES))));

  // The gates the unfreeze did NOT touch still bite on UNRANKED.
  await okFails("V1.5 NEGATIVE: on UNRANKED the round gate still refuses (round 7 < 8)",
    voteRef(ID.unrankedEarly).set(openVote(ID.unrankedEarly, 7, Object.assign({}, GOOD_VOTES))));

  await okFails("V1.6 NEGATIVE: on UNRANKED the target slot is still structurally absent",
    voteRef(ID.unrankedBadShape).set(
      openVote(ID.unrankedBadShape, 8, { p1: null, p2: null, p3: null, p4: null })));

  // ════════════════════════════════════════════════════════════════
  // V2. the cooldown write — the second gate
  // ════════════════════════════════════════════════════════════════
  await okSucceeds("V2.1 POSITIVE: the cooldown write lands on an UNRANKED match",
    A.firestore().collection("matches").doc(ID.unrankedCooldown).update({
      failedVoteKickCooldown: { targetSeat: "p3", blockedUntilRound: 13 },
      version: 2, updatedAt: 2
    }));

  await okFails("V2.2 NEGATIVE: the cooldown write is refused on a ROOM match",
    A.firestore().collection("matches").doc(ID.roomCooldown).update({
      failedVoteKickCooldown: { targetSeat: "p3", blockedUntilRound: 13 },
      version: 2, updatedAt: 2
    }));

  // ════════════════════════════════════════════════════════════════
  // V3. the removal write — the third gate
  // ════════════════════════════════════════════════════════════════
  await okSucceeds("V3.1 POSITIVE: the removal write lands on an UNRANKED match",
    A.firestore().collection("matches").doc(ID.unrankedRemoval).update({
      botSeats: ["p3"], removedUids: [uidC], version: 2, updatedAt: 2
    }));

  await okFails("V3.2 NEGATIVE: the removal write is refused on a ROOM match",
    A.firestore().collection("matches").doc(ID.roomRemoval).update({
      botSeats: ["p3"], removedUids: [uidC], version: 2, updatedAt: 2
    }));

  // ════════════════════════════════════════════════════════════════
  // V4. the pin — the rules and the recorded hash move together
  // ════════════════════════════════════════════════════════════════
  var rulesBytes = fs.readFileSync(REPO_ROOT + "/firestore.rules");
  var actualSha = crypto.createHash("sha256").update(rulesBytes).digest("hex");
  var pinnedSha = fs.readFileSync(REPO_ROOT + "/firestore.rules.sha256", "utf8")
    .match(/^[0-9a-f]{64}/);
  check("V4.1 sha256(firestore.rules) equals the recorded pin",
    pinnedSha !== null && actualSha === pinnedSha[0],
    pinnedSha === null ? "pin unreadable" : "actual " + actualSha + " != pinned " + pinnedSha[0]);

  console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
  if (fail > 0) process.exitCode = 1;
}

main().catch(function (e) {
  console.error("HARNESS CRASHED: " + ((e && e.stack) || e));
  process.exitCode = 1;
});
