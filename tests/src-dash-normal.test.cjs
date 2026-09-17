// Legacy tracker: Normal-mode DASH_CALL flat table + REG_DASH 10+T
// (canonical §4, owner-confirmed 2026-09-17 — src/ ONLY, Classic and
// design-ui/engine explicitly out of scope and pinned unchanged in
// tests/src-super-call-normal.test.cjs).
//
// DASH_CALL (pre-bid): win Under (round total ≤13) +33 / Over (>13)
// +25; loss Under −33 / Over −25 — flat, independent of tricks taken.
// Sole winner +10 (shared rule); sole loser 10 extra (no doubling).
// REG_DASH (Normal Dash, 0 estimated mid-auction): ordinary 10+T —
// win +10, loss −(10 + tricks taken); sole winner +10, sole loser
// 10 extra.
//
// Method: transpiles the REAL src/utils.ts with the repo's own
// typescript devDependency and exercises its REAL calculateRoundScores.
var path = require("path");
var fs = require("fs");

var REPO_ROOT = path.join(__dirname, "..");
var ts = null;
try { ts = require("typescript"); }
catch (e) { ts = null; }

var pass = 0, fail = 0;
function check(label, ok, detail) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (detail ? " -- " + detail : "")); fail++; }
}

check("typescript devDependency is available for transpiling", ts !== null);

var scores = null;
if (ts) {
  try {
    var src = fs.readFileSync(path.join(REPO_ROOT, "src", "utils.ts"), "utf8");
    var js = ts.transpileModule(src, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
    }).outputText;
    var moduleObj = { exports: {} };
    new Function("module", "exports", "require", js)(moduleObj, moduleObj.exports, require);
    scores = moduleObj.exports.calculateRoundScores;
  } catch (e) { scores = null; }
}
check("REAL src/utils.ts transpiles and exports calculateRoundScores",
  typeof scores === "function");

// role: "DASH_CALL" | "REG_DASH" for seat 1; others: [bid, won] NORMAL.
// totalBids is passed explicitly (Under ≤13 vs Over >13).
function dashRound(role, dashBid, dashWon, totalBids, others) {
  var players = [{ playerId: 1, role: role, bid: dashBid, won: dashWon }];
  others.forEach(function (o, i) {
    players.push({ playerId: i + 2, role: "NORMAL", bid: o[0], won: o[1] });
  });
  return scores(players, totalBids, "NORMAL")[1];
}

if (scores) {
  // ── DASH_CALL wins (all succeed → no sole) ──
  check("dash win Under (total 12) scores +33",
    dashRound("DASH_CALL", 0, 0, 12, [[4, 4], [4, 4], [4, 4]]) === 33);
  check("dash win Over (total 15) scores +25",
    dashRound("DASH_CALL", 0, 0, 15, [[5, 5], [5, 5], [5, 5]]) === 25);
  check("dash win sole Under scores 43 (33 + 10)",
    dashRound("DASH_CALL", 0, 0, 12, [[4, 5], [4, 5], [4, 5]]) === 43);
  check("dash win sole Over scores 35 (25 + 10)",
    dashRound("DASH_CALL", 0, 0, 15, [[5, 6], [5, 6], [5, 6]]) === 35);

  // ── DASH_CALL losses (flat, tricks taken don't matter) ──
  check("dash loss Under (total 12) scores -33",
    dashRound("DASH_CALL", 0, 2, 12, [[4, 4], [4, 4], [4, 5]]) === -33);
  check("dash loss Over (total 15) scores -25",
    dashRound("DASH_CALL", 0, 1, 15, [[5, 5], [5, 5], [5, 6]]) === -25);
  check("dash loss sole Under scores -43 (-33 - 10, no doubling)",
    dashRound("DASH_CALL", 0, 3, 12, [[4, 4], [4, 4], [4, 4]]) === -43);
  check("dash loss sole Over scores -35 (-25 - 10, no doubling)",
    dashRound("DASH_CALL", 0, 2, 15, [[5, 5], [5, 5], [5, 5]]) === -35);
  var l1 = dashRound("DASH_CALL", 0, 1, 12, [[4, 4], [4, 4], [4, 5]]);
  var l5 = dashRound("DASH_CALL", 0, 5, 12, [[4, 4], [4, 4], [4, 5]]);
  check("dash loss ignores tricks taken (won 1 == won 5 == -33)",
    l1 === -33 && l5 === -33, "got " + l1 + " / " + l5);

  // ── Defensive boundary: total exactly 13 counts as Under ──
  check("total exactly 13 counts as Under (+33)",
    dashRound("DASH_CALL", 0, 0, 13, [[4, 4], [4, 4], [5, 5]]) === 33);

  // ── REG_DASH: ordinary 10+T ──
  check("normal-dash win scores +10",
    dashRound("REG_DASH", 0, 0, 12, [[4, 4], [4, 4], [4, 4]]) === 10);
  check("normal-dash win sole scores +20",
    dashRound("REG_DASH", 0, 0, 12, [[4, 5], [4, 5], [4, 5]]) === 20);
  check("normal-dash loss of 3 scores -(10+3) = -13",
    dashRound("REG_DASH", 0, 3, 12, [[4, 4], [4, 4], [4, 5]]) === -13);
  check("normal-dash loss sole scores -23 (-13 - 10, no doubling)",
    dashRound("REG_DASH", 0, 3, 12, [[4, 4], [4, 4], [4, 4]]) === -23);
}

console.log("\n=== RESULTS ===");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
