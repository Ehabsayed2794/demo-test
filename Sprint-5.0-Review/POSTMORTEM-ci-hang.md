# Postmortem — the CI I built hung for 58 minutes on its first real run

This is exactly the kind of thing Sprint 5.0 exists to catch, and it caught
it — on the very first live execution, not in theory.

## What happened

After pushing the Sprint 5.0 commit, the workflow (`31691313922`) ran for
real on GitHub's hosted runner:
- `Checkout`, `Set up Node.js`, `Install dependencies` all completed
  normally (seconds each).
- The test step started, and the JS-logic tests ran fine (e.g.
  `rules-simulation.test.js`: 278 passed, 0 failed, printed at
  `2026-08-13T11:07:35Z`).
- Immediately after, `score-ui-verification.test.cjs` started — and
  **nothing else was ever printed**. The job sat at "in progress" for
  **58 minutes** until GitHub's own cancellation killed it
  (`2026-08-13T12:05:11Z`, `##[error]The operation was canceled.`).

Confirmed directly from the real job log (`get_job_logs`), not inferred.

## Root cause (two compounding bugs)

1. **`tests/score-ui-verification.test.cjs` and `tests/table-play-ui.test.cjs`
   hardcoded** `chromium.launch({ executablePath:
   "/opt/pw-browsers/chromium-1194/chrome-linux/chrome" })` — a path that
   only exists in this project's own dev sandbox image. On a real GitHub
   Actions `ubuntu-latest` runner, that path doesn't exist, and (unlike a
   typical `ENOENT`, which fails fast) this Playwright launch call never
   resolved and never rejected — it hung indefinitely.
2. **`scripts/run-tests.mjs` had no timeout** on its `spawnSync` call. A
   hung child process therefore blocked the entire runner forever. There
   was no mechanism for the pipeline to notice "this file is stuck" and
   fail — the only thing that eventually stopped it was GitHub's own
   job-level cancellation, nearly an hour later.

Bug 2 is the more serious one architecturally: Sprint 5.0's entire premise
is "the build MUST fail if something's wrong, never silently pass." A
58-minute hang followed by an external cancellation is arguably *worse*
than a silent skip — it gives no diagnosis at all, just an eventual,
unexplained red X.

## Fix

- **`scripts/resolve-chromium.cjs`** (new): resolves to the sandbox path
  when it exists (so this sandbox's own behavior is unchanged — verified:
  `node -e "console.log(require('./scripts/resolve-chromium.cjs')...)"`
  still prints the sandbox path here), otherwise returns `undefined`, which
  is equivalent to omitting `executablePath` — Playwright then resolves
  its own normal managed-browser install. Both test files updated to call
  it instead of hardcoding the path.
- **`.github/workflows/test.yml`**: added `npx playwright install
  --with-deps chromium` before the test step, so a real, resolvable
  Chromium actually exists on the runner.
- **`scripts/run-tests.mjs`**: added a per-file timeout
  (`PER_FILE_TIMEOUT_MS`, default 3 minutes, overridable via
  `RUN_TESTS_TIMEOUT_MS` for testing) to the `spawnSync` call. A timed-out
  file is now killed (`SIGKILL`) and reported as a clearly-labeled hard
  failure (`TIMED OUT after Ns`) — never a silent hang, never an
  externally-imposed cancellation with no diagnosis.

## Verification

- `spawnSync`'s timeout mechanism confirmed directly (not assumed):
  spawning a deliberately-hung child process with `timeout: 3000` returns
  `status: null, signal: 'SIGKILL', error.code: 'ETIMEDOUT'` — exactly the
  shape `run-tests.mjs` now checks for.
- `scripts/run-tests.mjs` itself confirmed to report it correctly
  end-to-end: a temporary hung test file (removed after), run with
  `RUN_TESTS_TIMEOUT_MS=2000`, produced `FAIL zz-temp-hang.test.cjs (exit
  1, TIMED OUT)` in the summary — a clear, fast, diagnosable failure
  instead of a multi-minute hang.
- Full suite re-run after the fix: `npm run test:ci` → still 35/35, same
  result as before this hotfix — confirming the fix changes nothing about
  the passing path, only the failure/hang path.
- Pushed and re-triggered; see this file's own git history / the PR
  discussion for the follow-up live run's actual result once observed
  (the tool chain used to check the first run's live status returned
  stale/cached data for several minutes in a row — see the chat transcript
  around this postmortem for that separate, disclosed limitation).

## Round 2 — the timeout fix worked, and immediately exposed a much bigger finding

Pushed the hang fix, a new run (`31700108453`) executed for real (confirmed
by fresh, changing timestamps this time, not the stale/cached reads from
round 1). Result: **no hang** — `Install Playwright Chromium` succeeded in
~21s, and the test step completed (didn't hang) in ~6 minutes. But the
job's conclusion was `failure`, with a real job log to diagnose:

- **31 of 35 test files fail with `Error: Cannot find module
  '/home/user/demo-test/design-ui/...'`** — every one of them hardcoded
  this sandbox's own absolute filesystem path (`/home/user/demo-test/...`)
  in a `require()` or `fs.readFileSync()` call, instead of a path derived
  from the file's own location. On GitHub's runner the repo checks out at
  `/home/runner/work/demo-test/demo-test/`, so every one of those calls
  threw `MODULE_NOT_FOUND` immediately, before a single check ran.
- The two Playwright tests (`score-ui-verification.test.cjs`,
  `table-play-ui.test.cjs`) correctly hit the new 3-minute timeout and
  were killed and reported as `TIMED OUT` — proving the round-1 fix does
  what it's supposed to (no more silent hangs) — but they still didn't
  *pass*, because they also hardcoded `/tmp/fb-cdn-cache` (this sandbox's
  own manually-populated Firebase SDK mock cache, never committed to the
  repo) for their CDN-mock fixtures.

This is a bigger deal than the hang: it means most of this project's
"passing" test history was only ever proven inside this exact sandbox,
never on a portable checkout — precisely the class of risk R4 (this whole
sprint) exists to close.

**Fix (mechanical, applied identically everywhere):**
- Every one of the 31 files now computes
  `const __REPO_ROOT__ = path.join(__dirname, "..")` and uses it instead
  of the hardcoded literal. Confirmed the literal was ALWAYS
  double-quoted and never appeared in a template literal or single-quoted
  string before doing a scripted replacement across all 31 files (so the
  mechanical substitution couldn't silently corrupt an unrelated string).
- **Caught and fixed my own script's bug before committing it**: for the
  3 files that already had a `require("path")` line further down (not at
  the top), my first pass inserted the new `__REPO_ROOT__` constant
  *before* that line — a `const` temporal-dead-zone `ReferenceError`,
  confirmed by actually running each file, not assumed. Moved the new
  line to immediately after the existing `require("path")` in those 3
  files instead.
- The two Playwright tests' `/tmp/fb-cdn-cache` mock directory is now
  `tests/fixtures/firebase-cdn/` (the same 3 Firebase compat SDK files,
  vendored into the repo, ~512KB total) — the CDN-mocking strategy itself
  was already the right call (this sandbox's proxy to
  fonts.googleapis.com/gstatic.com is unreliable), it just needed to stop
  depending on an uncommitted, sandbox-only cache path.

**Verified**: every one of the 31 files re-run individually (grep-checked
for `ReferenceError`/`Cannot find module`/`is not defined` in their
output — none found); both Playwright tests re-run individually (still
11/11 and 22/22, unchanged); full `npm run test:ci` re-run end-to-end —
still 35/35, identical result to before any of these fixes, confirming
none of this changed any test's actual pass/fail logic, only its
portability.

## Round 3 — one file left, a THIRD distinct class of the same root cause

Pushed the path fix, a third fresh run (`31701404817`) executed for real
in ~90 seconds total (no hang) with a genuinely new result: **34 of 35
files passed, including both real-Chromium Playwright tests (22/22 and
their earlier 11/11)** — confirming rounds 1 and 2's fixes both actually
worked end-to-end on a real, fresh GitHub runner. One file left:
`scoring-correction.test.cjs`.

Its real error, from the actual job log:
```
Error: Command failed: npx tsc utils.ts types.ts ...
npm warn exec The following package was not found and will be installed: tsc@2.0.4
npm warn deprecated tsc@2.0.4: Package no longer supported.
```

A third distinct flavor of the same underlying class of bug (environment-
implicit resolution instead of an explicit, portable path): the test ran
`npx tsc ...` with `cwd` set to a bare `/tmp` scratch directory (deliberately,
to avoid this repo's own project-mode `tsconfig.json`). With no
`node_modules` anywhere in that directory's ancestry, `npx` on GitHub's
runner correctly fell through to the public npm registry — where `tsc` is
the name of a real, totally unrelated, long-deprecated package (`tsc@2.0.4`,
not the TypeScript compiler) — and ran that instead. On silently-wrong
input, `require()`ing its output threw `MODULE_NOT_FOUND` for the never-
produced `utils.js`.

This one had a uniquely deceptive failure mode: it worked flawlessly every
single time in this dev sandbox (confirmed: 17/17, repeatedly, across this
whole session), almost certainly because this sandbox already had a real
`tsc` cached/resolvable via `npx` from earlier, unrelated session activity
— an environmental accident, not a correctness property of the test
itself.

**Fix**: invoke this repo's own `typescript` devDependency directly by its
installed, absolute path (`path.join(__REPO_ROOT__, "node_modules", ".bin",
"tsc")`) instead of asking `npx` to resolve `tsc` from an unrelated
directory. Removes the ambiguity entirely — there is now exactly one `tsc`
this test could possibly run.

**Verified**: re-ran the file standalone (17/17, unchanged) and the full
`npm run test:ci` suite end-to-end — **35/35**, matching every prior run's
result exactly. Grepped the rest of the repo for the same `npx <bin>` +
unrelated-`cwd` anti-pattern — no other occurrences found.

## Why this is a legitimate finding, not an excuse

The whole point of this sprint was "stop trusting untested claims about
CI." The correct response to my own CI hanging on its first real run is
not to quietly patch it and claim success — it's to diagnose it from the
actual job log, fix the real root cause (not just silence the symptom),
and verify the fix the same way: for real, against the actual failure
mode, not by assertion.
