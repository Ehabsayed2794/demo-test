// Sprint 4.1 — Scoring Logic Correction.
//
// IMPORTANT DEVIATION FROM THE LITERAL TASK BRIEF, DISCLOSED UP FRONT:
// this file's expected values implement the CANONICAL rules formula
// (matching design-ui/engine/scoring-engine.js and
// uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx §4), NOT the
// literal worked examples given in the Sprint 4.1 task brief (e.g.
// "Bid 5, Made 5 -> Score +70"). Those literal examples use a
// different formula ((bid*10)+20 win / -10*miss loss / flat +20/-10
// Dash) that matches neither scoring-engine.js nor the rules doc —
// implementing them would have created a THIRD, newly-wrong scoring
// system, directly contradicting this same sprint's own stated Goal
// ("Align the production scoring logic with the authoritative
// scoring-engine.js and the official rules"). See the chat response
// alongside this commit for the full reasoning; docs/bugs/
// Scoring-Divergence-Analysis.md for the original divergence mapping.
//
// Tests through calculateRoundScores() — the ONLY exported function in
// src/utils.ts — exercising the REAL, fixed calcNormalScore()/
// calcClassicScore() internally, not a reimplementation.
//
// src/utils.ts is TypeScript with no build artifact checked in; this
// file compiles it to plain CommonJS JS in an isolated temp directory
// (avoiding this repo's own tsconfig.json, which is a project-mode
// config incompatible with single-file compilation) and requires the
// result — the same real source, just transpiled, not reimplemented.
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");
// Portability fix (found via a real GitHub Actions run -- this file used
// to hardcode this sandbox's own absolute path, so it failed with
// MODULE_NOT_FOUND on any other machine, including CI):
const __REPO_ROOT__ = path.join(__dirname, "..");

var pass = 0, fail = 0;
function check(label, cond, note) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
}

function buildUtils() {
  var srcDir = __REPO_ROOT__ + "/src";
  var tmpSrc = fs.mkdtempSync(path.join(os.tmpdir(), "scoring-correction-src-"));
  var tmpOut = fs.mkdtempSync(path.join(os.tmpdir(), "scoring-correction-out-"));
  fs.copyFileSync(path.join(srcDir, "utils.ts"), path.join(tmpSrc, "utils.ts"));
  fs.copyFileSync(path.join(srcDir, "types.ts"), path.join(tmpSrc, "types.ts"));
  // Portability fix (found via the same real-CI run as __REPO_ROOT__
  // above): `npx tsc` run with cwd:tmpSrc (a bare /tmp dir with no
  // node_modules ancestor) can't find this repo's own typescript
  // devDependency, so npx falls through to the public registry and
  // installs/runs a COMPLETELY UNRELATED package also named "tsc"
  // (tsc@2.0.4, a long-deprecated stub, not the TypeScript compiler) --
  // it happened to already be cached in this sandbox from earlier
  // session use, silently masking the bug here. Invoking this repo's
  // own installed typescript binary by absolute path removes the
  // ambiguity entirely.
  var tscBin = path.join(__REPO_ROOT__, "node_modules", ".bin", "tsc");
  execFileSync(tscBin, ["utils.ts", "types.ts", "--module", "commonjs", "--target", "es2019", "--outDir", tmpOut, "--skipLibCheck"], { cwd: tmpSrc, stdio: "pipe" });
  return require(path.join(tmpOut, "utils.js"));
}

var Utils = buildUtils();
var calculateRoundScores = Utils.calculateRoundScores;

console.log("=== Sprint 4.1: Scoring Correction — Test Log ===\n");

// Helper: score a SINGLE player's role/bid/won in isolation (no
// sole-winner/loser interaction, no other players affecting the
// result) by padding with 3 filler players who also succeed, so
// successCount>1 and failCount is whatever it is — used only for
// tests that want to isolate ONE formula component at a time.
function scoreOne(role, bid, won, totalBids, others) {
  var players = [{ playerId: 1, role: role, bid: bid, won: won }].concat(others || []);
  return calculateRoundScores(players, totalBids, "NORMAL")[1];
}

// ---------- 1. Standard Win (Caller) ----------
// Canonical: 10 + T + Caller(+10). bid=5, won=5, isCaller -> 10+5+10=25.
// (NOT the task brief's literal "+70" — see header disclosure.)
(function () {
  var others = [
    { playerId: 2, role: "NORMAL", bid: 3, won: 3 },
    { playerId: 3, role: "NORMAL", bid: 2, won: 2 },
    { playerId: 4, role: "NORMAL", bid: 1, won: 1 },
  ];
  var got = scoreOne("CALLER", 5, 5, 11, others);
  check("1. Standard Win (Caller, bid 5 = made 5): Expected 25, Got " + got, got === 25);
})();

// ---------- 2. Standard Loss (Caller) ----------
// Canonical: -(|T-E| + Caller(10)). bid=5, won=3, miss=2 -> -(2+10) = -12.
(function () {
  var others = [
    { playerId: 2, role: "NORMAL", bid: 3, won: 4 },
    { playerId: 3, role: "NORMAL", bid: 2, won: 3 },
    { playerId: 4, role: "NORMAL", bid: 1, won: 3 },
  ];
  var got = scoreOne("CALLER", 5, 3, 11, others);
  check("2. Standard Loss (Caller, bid 5, made 3): Expected -12, Got " + got, got === -12);
})();

// ---------- 3. Dash Call Success (pre-bid Dash Call, round Under 13) ----------
// Canonical: flat +33 (Under, totalBids<=13), independent of `won`.
(function () {
  var others = [
    { playerId: 2, role: "CALLER", bid: 5, won: 5 },
    { playerId: 3, role: "NORMAL", bid: 3, won: 3 },
    { playerId: 4, role: "NORMAL", bid: 2, won: 2 },
  ];
  var got = scoreOne("DASH_CALL", 0, 0, 10, others); // totalBids=10 (Under)
  check("3. Dash Call Success (Under 13): Expected +33, Got " + got, got === 33);
})();

// ---------- 4. Dash Call Failure (pre-bid Dash Call, round Under 13) ----------
// Canonical: flat -33 (Under), independent of `won`.
(function () {
  var others = [
    { playerId: 2, role: "CALLER", bid: 5, won: 4 },
    { playerId: 3, role: "NORMAL", bid: 3, won: 2 },
    { playerId: 4, role: "NORMAL", bid: 2, won: 2 },
  ];
  var got = scoreOne("DASH_CALL", 0, 1, 10, others); // took 1 trick, still flat -33
  check("4. Dash Call Failure (Under 13): Expected -33, Got " + got, got === -33);
})();

// ---------- 5. Super Call: verify NO invented +/-20 bonus — scored as
// an ordinary Caller instead (per canonical rules, no Normal-mode
// Super Call special case). bid=8, won=8 -> 10+8+10(caller)=28. ----------
(function () {
  var others = [
    { playerId: 2, role: "NORMAL", bid: 2, won: 2 },
    { playerId: 3, role: "NORMAL", bid: 1, won: 1 },
    { playerId: 4, role: "NORMAL", bid: 2, won: 2 },
  ];
  var got = scoreOne("SUPER_CALL", 8, 8, 13, others);
  check("5a. Super Call success (bid 8, made 8): Expected 28 (10+8+10, NOT +20), Got " + got, got === 28);
  var othersFail = [
    { playerId: 2, role: "NORMAL", bid: 2, won: 3 },
    { playerId: 3, role: "NORMAL", bid: 1, won: 1 },
    { playerId: 4, role: "NORMAL", bid: 2, won: 1 },
  ];
  var gotFail = scoreOne("SUPER_CALL", 8, 6, 13, othersFail);
  check("5b. Super Call failure (bid 8, made 6): Expected -12 (-(2+10), NOT -20), Got " + gotFail, gotFail === -12);
})();

// ---------- 6. Edge Case: Bid 13, Made 13 (Caller) ----------
// Canonical: 10+13+10(caller) = 33.
(function () {
  var others = [
    { playerId: 2, role: "NORMAL", bid: 0, won: 0 },
    { playerId: 3, role: "NORMAL", bid: 0, won: 0 },
    { playerId: 4, role: "NORMAL", bid: 0, won: 0 },
  ];
  var got = scoreOne("CALLER", 13, 13, 13, others);
  check("6. Edge case: Bid 13, Made 13 (Caller): Expected 33, Got " + got, got === 33);
})();

// ---------- 7. Normal Dash (REG_DASH) success/failure — the OTHER
// half of the previously-conflated Dash formula, distinct from
// pre-bid Dash Call above. ----------
(function () {
  var othersSuccess = [
    { playerId: 2, role: "CALLER", bid: 5, won: 5 },
    { playerId: 3, role: "NORMAL", bid: 3, won: 3 },
    { playerId: 4, role: "NORMAL", bid: 5, won: 5 },
  ];
  var gotSuccess = scoreOne("REG_DASH", 0, 0, 13, othersSuccess);
  check("7a. Normal Dash success (0 tricks): Expected +10, Got " + gotSuccess, gotSuccess === 10);

  var othersFail = [
    { playerId: 2, role: "CALLER", bid: 5, won: 3 },
    { playerId: 3, role: "NORMAL", bid: 3, won: 4 },
    { playerId: 4, role: "NORMAL", bid: 3, won: 4 },
  ];
  var gotFail = scoreOne("REG_DASH", 0, 2, 14, othersFail); // took 2 tricks
  check("7b. Normal Dash failure (took 2 tricks): Expected -12 (-(10+2), NOT -2), Got " + gotFail, gotFail === -12);
})();

// ---------- 8. Sole winner / sole loser modifiers still apply correctly
// after the fix (regression against the ALREADY-correct part of the
// formula, lines 34-35, deliberately left untouched). ----------
(function () {
  var others = [
    { playerId: 2, role: "NORMAL", bid: 3, won: 2 },
    { playerId: 3, role: "NORMAL", bid: 2, won: 1 },
    { playerId: 4, role: "NORMAL", bid: 1, won: 0 },
  ];
  var got = scoreOne("CALLER", 10, 10, 16, others); // sole winner: 10+10+10(caller)+10(sole)=40
  check("8. Sole winner bonus still applies: Expected 40, Got " + got, got === 40);
})();

// ---------- 8b. SECOND bug found via testing (not in the original
// divergence doc): plain WIZZ was double-counting the Caller/With
// bonus (+20 instead of +10) because the OLD code added a separate
// `callerBonus` (10, for CALLER/WIZZ/WIZZ_RISK) AND `wizzBonus` (10,
// for WIZZ/WIZZ_RISK). scoring-engine.js's `if (isCaller || isWith)
// delta += 10` — a SINGLE bonus — is the correct reference. Fixed
// alongside the originally-scoped changes since it's the same
// function and directly affects scoring correctness. ----------
(function () {
  var others = [
    { playerId: 2, role: "CALLER", bid: 4, won: 4 },
    { playerId: 3, role: "NORMAL", bid: 2, won: 2 },
    { playerId: 4, role: "NORMAL", bid: 2, won: 2 },
  ];
  var got = scoreOne("WIZZ", 4, 4, 12, others); // 10+4+10(caller-or-with)=24, NOT +20 extra=34
  check("8b. WIZZ success: Expected 24 (single +10, was 34 before fix), Got " + got, got === 24);
  var othersFail = [
    { playerId: 2, role: "CALLER", bid: 4, won: 2 },
    { playerId: 3, role: "NORMAL", bid: 2, won: 3 },
    { playerId: 4, role: "NORMAL", bid: 2, won: 3 },
  ];
  var gotFail = scoreOne("WIZZ", 4, 2, 12, othersFail); // -(2+10)=-12, NOT -22
  check("8c. WIZZ failure: Expected -12 (single -10, was -22 before fix), Got " + gotFail, gotFail === -12);
})();

// ---------- Task 4: Integration check — a full simulated 4-player
// round using the corrected calculateRoundScores(), verifying the
// complete scoreboard is internally consistent. ----------
(function () {
  console.log("\n--- Task 4: Integration check (simulated 4-player round) ---\n");
  var players = [
    { playerId: 1, role: "CALLER", bid: 6, won: 6 },   // Caller succeeds: 10+6+10=26
    { playerId: 2, role: "WIZZ", bid: 6, won: 6 },      // With succeeds: 10+6+10=26
    { playerId: 3, role: "NORMAL", bid: 1, won: 1 },    // Normal succeeds: 10+1=11
    { playerId: 4, role: "DASH_CALL", bid: 0, won: 0 }, // Pre-bid Dash Call, round Under 13 -> +33
  ];
  var totalBids = 6 + 6 + 1 + 0; // 13 -- Dash Call counts as 0 toward the 13 sum, per the rules
  var scores = calculateRoundScores(players, totalBids, "NORMAL");
  console.log("Scoreboard:", JSON.stringify(scores));
  check("9. Integration: Caller score correct", scores[1] === 26, "got " + scores[1]);
  // With gets ONE +10 (Caller-or-With), same as Caller — NOT an extra
  // +20 (see the WIZZ double-counting bug fixed alongside this test).
  check("10. Integration: With score correct (single +10, not double-counted)", scores[2] === 26, "got " + scores[2]);
  check("11. Integration: Normal score correct", scores[3] === 11, "got " + scores[3]);
  check("12. Integration: Dash Call score correct (flat +33, Under 13)", scores[4] === 33, "got " + scores[4]);
  check("13. Integration: all 4 seats produced a finite numeric score", [1, 2, 3, 4].every(function (id) { return typeof scores[id] === "number" && !isNaN(scores[id]); }));
})();

console.log("\n=== RESULTS ===\n");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
