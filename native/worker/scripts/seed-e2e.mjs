// E2E seeder for the seeded Durable Object verification (O-06).
//
// Seeds the fixtures below into the local DO SQLite database(s) that
// `wrangler dev --local --persist-to <dir>` produces, so the full round
// trip — Worker → DO → Kotlin core → SQL → response — can be checked with
// real data on the real runtime.
//
// Usage:
//   node scripts/seed-e2e.mjs [stateDir]   (default D:/cache/stage0/e33-state)
//   node scripts/seed-e2e.mjs --verify [stateDir]
//   node scripts/seed-e2e.mjs --verify --expect-settled [stateDir]
//
// The script maps each DO SQLite file back to its match via miniflare's own
// `__miniflare_do_name` table, resets the five authority tables, and seeds
// exactly ONE fixture per DO — so the E2E proves one-DO-per-match isolation
// instead of duplicating every fixture into every object. Verify mode exits
// non-zero on any foreign row, missing DO, or (with --expect-settled) an
// absent/incorrect golden claim.
//
// Procedure (documented in docs/implementation/Stage0_O06_DO_D1.md):
//   1. boot once   → the DO is constructed, its schema (DO_SCHEMA) is created
//   2. stop wrangler, run this script to seed
//   3. boot again  → drive the E2E assertions through the HTTP facade
//   4. stop, run --verify to inspect the persisted outcome
//
// No test-only routes exist inside the DO; the seed talks to the database
// file directly. Fixtures are plain committed data — the golden round-1
// documents are generated here by the same algorithm the Kotlin suites use,
// and the E2E's corrected-settle assertion is what pins their correctness.
//
// Requires Node ≥ 22 (uses the built-in experimental node:sqlite module;
// prints an ExperimentalWarning — harmless).

import { DatabaseSync } from "node:sqlite";
import { readdirSync, statSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { dirname } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const VERIFY = args.includes("--verify");
const EXPECT_SETTLED = args.includes("--expect-settled");
const stateDir = args.find((a) => !a.startsWith("--")) ?? "D:/cache/stage0/e33-state";

const SEED_MATCHES = ["e2e-room", "e2e-incomplete", "e2e-golden"];

// ── fixture builders (ported from SettlementTest.kt 1:1; the E2E's own
//    assertions are the check that the port is faithful) ────────────────

const SENT = { SPADES: "S", HEARTS: "H", DIAMONDS: "D", CLUBS: "C" };
const SUITS = Object.keys(SENT);
const ORDER = ["p1", "p2", "p3", "p4"];
const nextSeat = (s) => ORDER[(ORDER.indexOf(s) + 1) % ORDER.length];
const rankS = (v) => ({ 11: "J", 12: "Q", 13: "K", 14: "A" }[v] ?? String(v));

function card(seat, suit, v, round = 1) {
  return { seatId: seat, card: { suit, rank: { v, s: rankS(v) } }, round };
}

function playsFor(winners, trump, round, leaderId) {
  const pools = Object.fromEntries(SUITS.map((s) => [s, Array.from({ length: 13 }, (_, i) => 14 - i)]));
  const ledCycle = SUITS;
  const winSuitOf = winners.map((_, t) => (trump === "SANS" ? ledCycle[t % 4] : trump));
  const winValue = {};
  winners.forEach((_, t) => {
    winValue[t] = pools[winSuitOf[t]].shift();
  });
  const plays = [];
  let leader = leaderId;
  winners.forEach((winner, trick) => {
    const winSuit = winSuitOf[trick];
    const winV = winValue[trick];
    const led = ledCycle[trick % 4];
    let seat = leader;
    for (let i = 0; i < 4; i++) {
      if (seat === winner) {
        plays.push(card(seat, winSuit, winV, round));
      } else if (seat === leader && trump === "SANS") {
        plays.push(card(seat, led, pools[led].pop(), round));
      } else {
        // Fallback: lowest remaining card from any non-winning suit.
        const candidates = Object.entries(pools).filter(([s, p]) => s !== winSuit && p.length > 0);
        const pick = candidates.sort((a, b) => a[1][a[1].length - 1] - b[1][b[1].length - 1])[0]
          ?? Object.entries(pools).filter(([, p]) => p.length > 0).sort((a, b) => a[1][a[1].length - 1] - b[1][b[1].length - 1])[0];
        plays.push(card(seat, pick[0], pick[1].pop(), round));
      }
      seat = nextSeat(seat);
    }
    leader = winner;
  });
  if (plays.length !== 52) throw new Error(`fixture broken: built ${plays.length} plays`);
  return plays;
}

const dash = (seat, d, round = 1) => ({ seatId: seat, actionType: "SubmitDashCallDecision", declaredDashCall: d, round });
const auctionBid = (seat, tricks, suit, round = 1) => ({ seatId: seat, actionType: "SubmitAuctionBid", isPass: false, tricks, suit, round });
const auctionPass = (seat, round = 1) => ({ seatId: seat, actionType: "SubmitAuctionBid", isPass: true, round });
const confirm = (seat, tricks, suit, round = 1) => ({ seatId: seat, actionType: "SubmitConfirmCall", tricks, suit, round });

function matchFields(overrides = {}) {
  return {
    roomId: "r1",
    players: ["u1", "u2", "u3", "u4"],
    status: "complete",
    currentRound: 1,
    maxRounds: 1,
    dealer: "u1",
    seats: { p1: "u1", p2: "u2", p3: "u3", p4: "u4" },
    version: 7,
    mode: "RANKED",
    gameType: "FULL",
    scoringMode: "NORMAL",
    completedRound: 1,
    finalScores: { p1: 25, p2: -1, p3: 12, p4: 14 },
    winnerIds: ["p1"],
    ...overrides,
  };
}

const GOLDEN_WINNERS = ["p1", "p1", "p1", "p1", "p1", "p2", "p2", "p3", "p3", "p4", "p4", "p4", "p4"];
const GOLDEN_PLAYS = playsFor(GOLDEN_WINNERS, "SPADES", 1, "p1");
const GOLDEN_ACTIONS = [
  dash("p1", false), dash("p2", false), dash("p3", false), dash("p4", false),
  auctionBid("p1", 5, "SPADES"),
  auctionPass("p2"), auctionPass("p3"), auctionPass("p4"),
  confirm("p1", 5, "SPADES"),
];

const FIXTURES = [
  { matchId: "e2e-room",    fields: matchFields({ mode: "ROOM" }),                  archive: null },
  { matchId: "e2e-incomplete", fields: matchFields({ status: "starting" }),        archive: null },
  { matchId: "e2e-golden",  fields: matchFields({
      finalScores: { p1: 25, p2: -1, p3: 12, p4: 14 },
    }),
    archive: { round: 1, matchId: "e2e-golden", cardLog: GOLDEN_PLAYS, biddingLog: GOLDEN_ACTIONS, estimates: { p2: 3, p3: 2, p4: 4 } },
  },
];

// ── locate the DO SQLite file(s) under the persist-to dir ────────────────

function findDatabases(dir) {
  const found = [];
  const walk = (path) => {
    for (const entry of readdirSync(path, { withFileTypes: true })) {
      const full = join(path, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.endsWith(".sqlite")) found.push(full);
    }
  };
  if (existsSync(dir)) walk(dir);
  return found;
}

/**
 * Miniflare records the idFromName() name inside each DO's own SQLite file
 * (`__miniflare_do_name`, written by its wrapper). That is the only reliable
 * way to map a hashed file name back to a match without re-implementing
 * workerd's ID derivation. The script refuses to guess: a DB with the match
 * schema but no single recorded name is an error, never a bucket for every
 * fixture.
 */
function recordedDoName(db) {
  const tables = db
    .prepare("SELECT name FROM sqlite_master WHERE type='table'")
    .all()
    .map((r) => r.name);
  if (!tables.includes("__miniflare_do_name")) return null;
  const rows = db.prepare("SELECT name FROM __miniflare_do_name").all();
  if (rows.length !== 1 || typeof rows[0].name !== "string") return null;
  return rows[0].name;
}

function matchIdOf(doName) {
  return doName.startsWith("match:") ? doName.slice("match:".length) : null;
}

function rowCounts(db) {
  const counts = {};
  for (const table of ["match_state", "round_archive", "hands", "settlement_claim", "timeout_counts"]) {
    counts[table] = db.prepare(`SELECT COUNT(*) AS n FROM ${table}`).get().n;
  }
  return counts;
}

const dbFiles = findDatabases(stateDir);
if (dbFiles.length === 0) {
  console.error(`no .sqlite files under ${stateDir} — did you boot wrangler once first (step 1)?`);
  process.exit(1);
}

const problems = [];
const seededMatches = new Set();
const seenMatches = new Set();
let emptyProbes = 0;

for (const file of dbFiles) {
  const db = new DatabaseSync(file);
  const tables = db
    .prepare("SELECT name FROM sqlite_master WHERE type='table'")
    .all()
    .map((r) => r.name);
  if (!tables.includes("match_state")) {
    db.close();
    continue; // metadata.sqlite and other non-match stores
  }

  const doName = recordedDoName(db);
  const matchId = doName === null ? null : matchIdOf(doName);
  if (matchId === null) {
    problems.push(`${file}: match schema but no single recorded match: DO name`);
    db.close();
    continue;
  }
  if (seenMatches.has(matchId)) {
    problems.push(`${file}: duplicate DO for ${matchId}`);
    db.close();
    continue;
  }
  seenMatches.add(matchId);

  const fixture = FIXTURES.find((f) => f.matchId === matchId);
  if (fixture === undefined) {
    // A probe for a missing match (the E2E's NOT_FOUND assertion) constructs
    // an empty DO. It holds no authority state, so it is harmless; only a DO
    // with rows but no fixture is a real isolation violation.
    const counts = rowCounts(db);
    const total = Object.values(counts).reduce((sum, n) => sum + n, 0);
    if (total === 0) {
      console.log(`skip (empty probe DO ${doName})`);
      emptyProbes++;
      db.close();
      continue;
    }
    problems.push(`${file}: DO ${doName} has data but no fixture (${JSON.stringify(counts)})`);
    db.close();
    continue;
  }

  if (VERIFY) {
    const state = db
      .prepare("SELECT match_id, version FROM match_state ORDER BY match_id")
      .all();
    const archives = db
      .prepare("SELECT match_id, round FROM round_archive ORDER BY match_id, round")
      .all();
    const claims = db
      .prepare("SELECT match_id FROM settlement_claim ORDER BY match_id")
      .all();
    const hands = db
      .prepare("SELECT match_id, round, seat_id FROM hands ORDER BY match_id, round, seat_id")
      .all();
    const timeouts = db
      .prepare("SELECT match_id, uid FROM timeout_counts ORDER BY match_id, uid")
      .all();

    console.log(`== ${file}`);
    console.log(`   DO name: ${doName}`);
    console.log("   match_state:", JSON.stringify(state));
    console.log("   round_archive:", JSON.stringify(archives));
    console.log("   hands:", JSON.stringify(hands));
    console.log("   settlement_claim:", JSON.stringify(claims));
    console.log("   timeout_counts:", JSON.stringify(timeouts));

    if (state.length !== 1 || state[0].match_id !== matchId) {
      problems.push(`${file}: expected exactly one match_state row for ${matchId}`);
    }
    for (const row of archives) {
      if (row.match_id !== matchId) problems.push(`${file}: archive row for foreign match ${row.match_id}`);
    }
    for (const row of hands) {
      if (row.match_id !== matchId) problems.push(`${file}: hand row for foreign match ${row.match_id}`);
    }
    for (const row of claims) {
      if (row.match_id !== matchId) problems.push(`${file}: claim row for foreign match ${row.match_id}`);
    }
    for (const row of timeouts) {
      if (row.match_id !== matchId) problems.push(`${file}: timeout row for foreign match ${row.match_id}`);
    }

    if (EXPECT_SETTLED && matchId === "e2e-golden") {
      if (claims.length !== 1) {
        problems.push(`${file}: expected exactly one settlement claim, got ${claims.length}`);
      }
      const golden = db
        .prepare(
          "SELECT json_extract(data, '$.finalScores') AS fs, json_extract(data, '$.version') AS v " +
            "FROM match_state WHERE match_id = 'e2e-golden'",
        )
        .all();
      const scores = golden.length === 1 ? JSON.parse(golden[0].fs) : null;
      const version = golden.length === 1 ? golden[0].v : null;
      const expected = { p1: 25, p2: -11, p3: 12, p4: 14 };
      if (JSON.stringify(scores) !== JSON.stringify(expected)) {
        problems.push(`${file}: golden finalScores are ${JSON.stringify(scores)}, expected ${JSON.stringify(expected)}`);
      }
      if (version !== 8) {
        problems.push(`${file}: golden version is ${version}, expected 8`);
      }
    }
    db.close();
    continue;
  }

  // Seed mode: this is a throwaway E2E state directory. Reset every table so
  // a rerun cannot inherit a claim or a foreign fixture from a previous run.
  db.exec("BEGIN");
  try {
    for (const table of ["match_state", "round_archive", "hands", "settlement_claim", "timeout_counts"]) {
      db.exec(`DELETE FROM ${table}`);
    }
    db.prepare(
      "INSERT INTO match_state(match_id, version, data, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
    ).run(
      fixture.matchId,
      fixture.fields.version,
      JSON.stringify(fixture.fields),
      1_749_000_000_000,
      1_749_000_001_000,
    );
    if (fixture.archive) {
      db.prepare(
        "INSERT INTO round_archive(match_id, round, data) VALUES (?, ?, ?)",
      ).run(fixture.matchId, fixture.archive.round, JSON.stringify(fixture.archive));
    }
    db.exec("COMMIT");
  } catch (error) {
    db.exec("ROLLBACK");
    db.close();
    throw error;
  }
  seededMatches.add(matchId);
  console.log(`seeded ${matchId} into ${file}`);
  db.close();
}

if (VERIFY) {
  if (problems.length > 0) {
    console.error("\nVERIFY FAILED:");
    for (const problem of problems) console.error(`  - ${problem}`);
    process.exit(1);
  }
  const missing = SEED_MATCHES.filter((m) => !seenMatches.has(m));
  if (missing.length > 0) {
    console.error(`\nVERIFY FAILED: no DO found for ${missing.join(", ")}`);
    process.exit(1);
  }
  console.log(`\nVERIFY OK — ${SEED_MATCHES.length} fixture DO(s), one fixture per DO, no foreign rows; ${emptyProbes} empty probe DO(s) skipped.`);
} else {
  if (problems.length > 0) {
    console.error("\nSEED FAILED:");
    for (const problem of problems) console.error(`  - ${problem}`);
    process.exit(1);
  }
  const missing = SEED_MATCHES.filter((m) => !seededMatches.has(m));
  if (missing.length > 0) {
    console.error(`\nSEED FAILED: no DO found for ${missing.join(", ")} — boot wrangler and touch every match first.`);
    process.exit(1);
  }
  console.log(`\ndone — ${seededMatches.size} match fixture(s) written, one per DO.`);
}
