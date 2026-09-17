// P2-3 release-hash pin: firestore.rules.sha256 is the single source of
// truth for the reviewed Firestore Rules blob. The deploy-production.yml
// SHA guard reads it, and THIS test fails fast on any PR that changes
// firestore.rules without updating the pin in the same commit —
// previously that mistake surfaced only as a broken deploy (see 817989e).
//
// Convention: raw bytes, sha256sum semantics, LF blob (enforced by
// .gitattributes: `firestore.rules text eol=lf`). No emulator needed —
// pure file hashing, runs everywhere test:ci runs.
var path = require("path");
var fs = require("fs");
var crypto = require("crypto");

var REPO_ROOT = path.join(__dirname, "..");
var RULES_PATH = path.join(REPO_ROOT, "firestore.rules");
var PIN_PATH = path.join(REPO_ROOT, "firestore.rules.sha256");
var WORKFLOW_PATH = path.join(REPO_ROOT, ".github", "workflows", "deploy-production.yml");

var pass = 0, fail = 0;
function check(label, ok, detail) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (detail ? " -- " + detail : "")); fail++; }
}

var pinRaw = null;
try { pinRaw = fs.readFileSync(PIN_PATH, "utf8"); }
catch (e) { pinRaw = null; }
check("pin file firestore.rules.sha256 exists and is readable", pinRaw !== null);

var pinned = null;
if (pinRaw !== null) {
  var m = pinRaw.match(/^[0-9a-f]{64}(?=\s|$)/);
  pinned = m ? m[0] : null;
}
check("pin file starts with a 64-char lowercase hex sha256",
  pinned !== null, pinRaw ? JSON.stringify(pinRaw.slice(0, 80)) : "pin unreadable");

if (pinRaw !== null) {
  var parts = pinRaw.trim().split(/\s+/);
  check("pin file names firestore.rules (sha256sum -c shape)",
    parts.length >= 2 && parts[1] === "firestore.rules",
    JSON.stringify(pinRaw.slice(0, 80)));
}

var rulesBytes = null;
try { rulesBytes = fs.readFileSync(RULES_PATH); }
catch (e) { rulesBytes = null; }
check("firestore.rules is readable as raw bytes", rulesBytes !== null);

if (rulesBytes) {
  var hasCR = rulesBytes.indexOf(13) !== -1;
  check("firestore.rules is LF-only (no CR bytes; .gitattributes enforces eol=lf)", !hasCR,
    hasCR ? "CRLF bytes present — check .gitattributes / checkout settings; CI checks out LF" : null);
  if (pinned) {
    var actual = crypto.createHash("sha256").update(rulesBytes).digest("hex");
    check("sha256(firestore.rules) matches the pin", actual === pinned,
      actual === pinned ? null : "actual " + actual + " != pinned " + pinned +
      " — firestore.rules changed without updating firestore.rules.sha256 in the same PR");
  }
}

var workflow = null;
try { workflow = fs.readFileSync(WORKFLOW_PATH, "utf8"); }
catch (e) { workflow = null; }
check("deploy-production.yml is readable", workflow !== null);
if (workflow) {
  check("deploy guard reads the pin file (single source of truth)",
    workflow.indexOf("firestore.rules.sha256") !== -1);
  var hardcoded = /expected='[0-9a-f]{64}'/.test(workflow);
  check("deploy guard carries no hardcoded expected hash", !hardcoded,
    hardcoded ? "stale hardcoded expected=... present — read the pin file instead" : null);
}

console.log("\n=== RESULTS ===");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
