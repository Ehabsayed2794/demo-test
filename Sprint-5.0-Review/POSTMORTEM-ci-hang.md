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

## Why this is a legitimate finding, not an excuse

The whole point of this sprint was "stop trusting untested claims about
CI." The correct response to my own CI hanging on its first real run is
not to quietly patch it and claim success — it's to diagnose it from the
actual job log, fix the real root cause (not just silence the symptom),
and verify the fix the same way: for real, against the actual failure
mode, not by assertion.
