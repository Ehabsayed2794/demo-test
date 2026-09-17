// Legacy tracker: Normal-mode SUPER_CALL square rule (owner decision).
//
// Owner-mandated house rule (2026-09-17, legacy src/ ONLY — Classic mode
// and design-ui/engine are explicitly out of scope and pinned unchanged
// below): a Normal-mode Super Call win scores bid SQUARED
// (8->64, 9->81, 10->100, 11->121, 12->144, 13->169; +10 sole winner via
// the shared rule); a loss scores half of that, rounded half-up
// (-32/-41/-50/-61/-72/-85), independent of tricks taken.
//
// Method: transpiles the REAL src/utils.ts with the repo's own
// typescript devDependency (import type is erasable — no runtime deps)
// and exercises its REAL calculateRoundScores. No mocks of the formula.
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

function round(superBid, superWon, others) {
  // others: array of [bid, won] for the other 3 seats (NORMAL role).
  var players = [{ playerId: 1, role: "SUPER_CALL", bid: superBid, won: superWon }];
  others.forEach(function (o, i) {
    players.push({ playerId: i + 2, role: "NORMAL", bid: o[0], won: o[1] });
  });
  var totalBids = superBid + others[0][0] + others[1][0] + others[2][0];
  return scores(players, totalBids, "NORMAL")[1];
}

if (scores) {
  // ── Win = bid squared (all succeed → no sole bonus) ──
  var wins = [[8, 64], [9, 81], [10, 100], [11, 121], [12, 144], [13, 169]];
  wins.forEach(function (w) {
    var got = round(w[0], w[0], [[1, 1], [2, 2], [3, 3]]);
    check("win " + w[0] + " scores " + w[1] + " (bid squared)", got === w[1], "got " + got);
  });

  // ── Sole winner still adds +10 ──
  var sole = round(8, 8, [[1, 2], [2, 3], [3, 4]]);
  check("sole win 8 scores 74 (64 + 10 sole)", sole === 74, "got " + sole);

  // ── Loss = half the square, rounded half-up ──
  var losses = [[8, -32], [9, -41], [10, -50], [11, -61], [12, -72], [13, -85]];
  losses.forEach(function (l) {
    // Two fail (super + one other) so no sole-loser doubling kicks in.
    var got = round(l[0], 0, [[1, 1], [2, 2], [5, 6]]);
    check("lose " + l[0] + " scores " + l[1], got === l[1], "got " + got);
  });

  // ── Loss is independent of tricks taken ──
  var a = round(8, 1, [[1, 1], [2, 2], [5, 6]]);
  var b = round(8, 7, [[1, 1], [2, 2], [5, 6]]);
  check("loss ignores tricks taken (won 1 == won 7 == -32)", a === -32 && b === -32,
    "got " + a + " / " + b);

  // ── Sole loser takes 10 extra (owner decision), no doubling ──
  var soleLosses = [[8, -42], [9, -51], [10, -60], [11, -71], [12, -82], [13, -95]];
  soleLosses.forEach(function (l) {
    // Super alone fails (others succeed) → sole loser, no doubling.
    var got = round(l[0], 0, [[1, 1], [2, 2], [3, 3]]);
    check("sole lose " + l[0] + " scores " + l[1] + " (flat + 10 extra)",
      got === l[1], "got " + got);
  });

  // ── Scope pins: everything else untouched ──
  var callerWin = (function () {
    var ps = [
      { playerId: 1, role: "CALLER", bid: 6, won: 6 },
      { playerId: 2, role: "NORMAL", bid: 1, won: 1 },
      { playerId: 3, role: "NORMAL", bid: 2, won: 2 },
      { playerId: 4, role: "NORMAL", bid: 3, won: 3 }
    ];
    return scores(ps, 12, "NORMAL")[1];
  })();
  check("SCOPE: Normal CALLER untouched (win 6 still 10+6+10=26)",
    callerWin === 26, "got " + callerWin);

  var callerSoleLoss = (function () {
    var ps = [
      { playerId: 1, role: "CALLER", bid: 6, won: 4 },
      { playerId: 2, role: "NORMAL", bid: 1, won: 1 },
      { playerId: 3, role: "NORMAL", bid: 2, won: 2 },
      { playerId: 4, role: "NORMAL", bid: 3, won: 3 }
    ];
    return scores(ps, 12, "NORMAL")[1];
  })();
  check("SCOPE: other roles keep sole-loser doubling (CALLER sole loss still -22)",
    callerSoleLoss === -22, "got " + callerSoleLoss);

  var dashWin = (function () {
    var ps = [
      { playerId: 1, role: "DASH_CALL", bid: 0, won: 0 },
      { playerId: 2, role: "NORMAL", bid: 4, won: 4 },
      { playerId: 3, role: "NORMAL", bid: 4, won: 4 },
      { playerId: 4, role: "NORMAL", bid: 4, won: 4 }
    ];
    return scores(ps, 12, "NORMAL")[1];
  })();
  check("SCOPE: Normal DASH_CALL untouched (win still 10)", dashWin === 10, "got " + dashWin);

  var classicSuper = (function () {
    var ps = [
      { playerId: 1, role: "SUPER_CALL", bid: 8, won: 8 },
      { playerId: 2, role: "NORMAL", bid: 1, won: 1 },
      { playerId: 3, role: "NORMAL", bid: 2, won: 2 },
      { playerId: 4, role: "NORMAL", bid: 3, won: 3 }
    ];
    return scores(ps, 14, "CLASSIC")[1];
  })();
  check("SCOPE: Classic SUPER_CALL untouched (win still fixed 42)", classicSuper === 42,
    "got " + classicSuper);

  var classicSuperLoss = (function () {
    var ps = [
      { playerId: 1, role: "SUPER_CALL", bid: 8, won: 5 },
      { playerId: 2, role: "NORMAL", bid: 1, won: 1 },
      { playerId: 3, role: "NORMAL", bid: 2, won: 2 },
      { playerId: 4, role: "NORMAL", bid: 3, won: 4 }
    ];
    return scores(ps, 14, "CLASSIC")[1];
  })();
  check("SCOPE: Classic SUPER_CALL loss untouched (still fixed -20)",
    classicSuperLoss === -20, "got " + classicSuperLoss);
}

console.log("\n=== RESULTS ===");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
