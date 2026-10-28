// Firebase deploy-manifest parity test (sprint A5).
//
// Verifies native/functions/index.js (the CLI-facing re-publish of Kotlin
// @JsExport callables) stays in sync with every @JsName("...") export found
// under native/functions/src/jsMain/kotlin/**/*.kt.
//
// Runs with plain Node only — no built Kotlin bundle, no Firebase emulator,
// no network. It PARSES index.js source rather than requiring it, so a
// missing/unbuilt bundle cannot silently skip this check.

"use strict";

const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const KOTLIN_DIR = path.join(
  ROOT,
  "native",
  "functions",
  "src",
  "jsMain",
  "kotlin",
);
const INDEX_JS = path.join(ROOT, "native", "functions", "index.js");

// Canonical set this manifest must track (every @JsName export in the tree).
const EXPECTED_NAMES = [
  "engineSmoke",
  "settleMatch",
  "resolveDecisionTimeout",
  "reclaimInactiveSeat",
  "resolveDisconnectVote",
  "castDisconnectVote",
  "restoreDisconnectedSeat",
  "getMyRankedProfile",
  "getRankedLeaderboard",
  "scheduledSeasonReset",
  "joinRankDownRoom",
  "operatorSeasonResetDryRun",
  // S56 - the Ranked matchmaking callables.
  "enqueueSearch",
  "pollSearch",
  "cancelSearch",
  // S71 - the host-only "Play Unranked" conversion.
  "playUnranked",
].sort();

let failures = 0;
function check(label, ok, detail) {
  if (ok) {
    console.log("PASS  " + label + (detail ? " — " + detail : ""));
  } else {
    console.log("FAIL  " + label + (detail ? " — " + detail : ""));
    failures++;
  }
}

// --- 1. Collect every @JsName("...") from the Kotlin sources ---------------
const jsNameRe = /@JsName\("([^"]+)"\)/g;
const kotlinNames = new Set();
let ktFiles = 0;

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full);
    } else if (entry.name.endsWith(".kt")) {
      ktFiles++;
      const text = fs.readFileSync(full, "utf8");
      let m;
      while ((m = jsNameRe.exec(text)) !== null) {
        kotlinNames.add(m[1]);
      }
    }
  }
}

walk(KOTLIN_DIR);
check(
  "Kotlin sources scanned",
  ktFiles > 0,
  ktFiles + " .kt files under " + KOTLIN_DIR,
);

const kotlinArray = [...kotlinNames].sort();

// --- 2. Parse the exported object's keys/values from index.js source -------
const indexSource = fs.readFileSync(INDEX_JS, "utf8");

const startMarker = "const exported = {";
const startIdx = indexSource.indexOf(startMarker);
check(
  "index.js declares const exported = {",
  startIdx !== -1,
  startIdx !== -1 ? "found" : "missing",
);

let manifest = {}; // key -> value name after "kotlin."
if (startIdx !== -1) {
  const after = indexSource.slice(startIdx + startMarker.length);
  const bodyLines = [];
  for (const line of after.split("\n")) {
    const trimmed = line.trim();
    if (trimmed === "};" || trimmed === "}") {
      break;
    }
    bodyLines.push(line);
  }
  const body = bodyLines.join("\n");
  const entryRe =
    /^\s*([A-Za-z_$][\w$]*)\s*:\s*kotlin\.([A-Za-z_$][\w$]*)\s*,?\s*$/gm;
  const seenKeys = new Set();
  let totalEntries = 0; // every regex match, duplicates included
  let entry;
  while ((entry = entryRe.exec(body)) !== null) {
    totalEntries++;
    const key = entry[1];
    const valueName = entry[2];
    if (!seenKeys.has(key)) {
      seenKeys.add(key);
      manifest[key] = valueName; // keep first occurrence
    }
  }
  check(
    "no duplicate manifest key",
    totalEntries === seenKeys.size,
    totalEntries === seenKeys.size
      ? seenKeys.size + " unique keys"
      : totalEntries + " entries but only " + seenKeys.size + " unique keys",
  );
  check(
    "manifest parses N entries",
    Object.keys(manifest).length > 0,
    Object.keys(manifest).length + " entries",
  );
}

const manifestKeys = new Set(Object.keys(manifest));

// --- 3. Value-shape check: every value must be kotlin.<sameName> -----------
let shapeOk = true;
for (const [key, valueName] of Object.entries(manifest)) {
  if (valueName !== key) {
    check(
      `manifest value shape for "${key}"`,
      false,
      `value is kotlin.${valueName}, expected kotlin.${key}`,
    );
    shapeOk = false;
  }
}
check("every manifest value is kotlin.<sameName>", shapeOk);

// --- 4. Set equality: manifest keys vs Kotlin @JsName set ------------------
const missingFromManifest = kotlinArray.filter((n) => !manifestKeys.has(n));
const extraInManifest = [...manifestKeys].filter((n) => !kotlinNames.has(n));

check(
  "manifest contains every Kotlin @JsName export",
  missingFromManifest.length === 0,
  missingFromManifest.length === 0
    ? "OK"
    : "missing from manifest: " + missingFromManifest.join(", "),
);
check(
  "manifest has no extra keys beyond Kotlin @JsName exports",
  extraInManifest.length === 0,
  extraInManifest.length === 0
    ? "OK"
    : "extra in manifest: " + extraInManifest.join(", "),
);

// --- 5. Exact match against the canonical 16 -------------------------------
const manifestArray = [...manifestKeys].sort();
check(
  "Kotlin @JsName set equals canonical 16",
  kotlinArray.join(",") === EXPECTED_NAMES.join(",") &&
    kotlinArray.length === EXPECTED_NAMES.length,
  kotlinArray.join(", "),
);
check(
  "manifest key set equals canonical 16",
  manifestArray.join(",") === EXPECTED_NAMES.join(",") &&
    manifestArray.length === EXPECTED_NAMES.length,
  manifestArray.join(", "),
);

console.log("\n=== RESULTS ===");
console.log(failures + " failure(s)");
process.exitCode = failures > 0 ? 1 : 0;
