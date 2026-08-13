#!/usr/bin/env node
/* Sprint 5.0 (CI/CD Pipeline & Real Emulator Enforcement) — the runner
 * behind `npm test`/`npm run test:ci`. Runs every test file in tests/
 * as a child process (so one file's crash can't take down the others'
 * results) and aggregates a single pass/fail exit code.
 *
 * Two independent hard-failure signals, not just one:
 *   1. Any individual test file's own non-zero exit code (each file is
 *      already responsible for its own checks -- this runner does not
 *      re-implement or second-guess them).
 *   2. A defense-in-depth scan of every file's combined stdout/stderr
 *      for the literal word "SKIPPED" -- so a future test file that
 *      reintroduces a silent-skip pattern (accidentally exiting 0
 *      anyway) still fails the build, per this sprint's explicit "Zero
 *      Tolerance for Silent Skips" requirement. This does not touch or
 *      re-derive any test's actual pass/fail logic -- it only refuses
 *      to call the run clean if that word appears anywhere in its output.
 */
import { readdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const TESTS_DIR = path.join(ROOT, "tests");

const files = readdirSync(TESTS_DIR)
  .filter((f) => f.endsWith(".cjs") || f.endsWith(".test.js"))
  .sort();

if (files.length === 0) {
  console.error("[run-tests] No test files found in " + TESTS_DIR);
  process.exit(1);
}

console.log("[run-tests] Running " + files.length + " test files from tests/\n");

const results = [];
for (const file of files) {
  const full = path.join(TESTS_DIR, file);
  console.log("──── " + file + " ────");
  const res = spawnSync(process.execPath, [full], {
    cwd: ROOT,
    encoding: "utf8",
    maxBuffer: 1024 * 1024 * 64,
  });
  const output = (res.stdout || "") + (res.stderr || "");
  process.stdout.write(output);

  const exitCode = res.status == null ? 1 : res.status;
  const hasSkipMarker = /\bSKIPPED\b/.test(output);
  const ok = exitCode === 0 && !hasSkipMarker;

  results.push({ file, exitCode, hasSkipMarker, ok });
  console.log(
    (ok ? "PASS" : "FAIL") + "  " + file +
    "  (exit " + exitCode + (hasSkipMarker ? ", contains SKIPPED marker" : "") + ")\n"
  );
}

const failed = results.filter((r) => !r.ok);

console.log("=== SUMMARY ===");
console.log(results.length - failed.length + "/" + results.length + " test files passed cleanly.");
if (failed.length > 0) {
  console.log("\nFAILED:");
  failed.forEach((r) => {
    console.log(
      "  - " + r.file + " (exit " + r.exitCode +
      (r.hasSkipMarker ? ", SKIPPED marker present -- zero tolerance for silent skips" : "") + ")"
    );
  });
}

process.exit(failed.length > 0 ? 1 : 0);
