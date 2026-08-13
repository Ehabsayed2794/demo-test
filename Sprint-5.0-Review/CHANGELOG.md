# Changelog — Sprint 5.0: CI/CD Pipeline & Real Emulator Enforcement (R4)

Establishes a "Trustworthy CI" gate: if the Firestore Rules Emulator is
unreachable, or the rules themselves are wrong, the build now fails hard.
No silent skips anywhere in the suite. No game logic or security rules
changed by this sprint itself (the one rule edit made was an intentional,
disclosed, immediately-reverted sabotage used purely to prove the
pipeline detects real rule regressions — see TEST_CHECKLIST.md item 3).

## Added

- **`.github/workflows/test.yml`**: new GitHub Actions workflow. Triggers
  on push to `claude/busy-bohr-ez5rz3` and `main`. checkout →
  `actions/setup-node@v4` (Node 22) → `npm ci` → `npm run test:ci`. No
  `continue-on-error`/`if: always()` anywhere in the test step.
- **`scripts/run-tests.mjs`**: the runner behind `npm test`/`npm run
  test:ci`. Runs every file in `tests/` as its own child process,
  aggregates a single pass/fail exit code, and — independently of each
  file's own exit code — scans every file's combined output for the
  literal word `SKIPPED` as a second, defense-in-depth guard against a
  future test file silently reintroducing the old skip pattern.
- **`package.json` scripts**:
  - `"test": "node scripts/run-tests.mjs"` — runs the full suite as-is;
    the 6 emulator files will FAIL (not skip) if no emulator is running.
  - `"test:ci": "firebase emulators:exec --project demo-test-ci --only
    firestore,auth \"node scripts/run-tests.mjs\""` — starts the Firestore
    + Auth emulators, runs the suite, tears the emulators down in a
    `finally` regardless of outcome, and propagates the real exit code.
    Uses Firebase's own `emulators:exec` lifecycle management rather than
    a hand-rolled start/sleep/kill choreography, per the sprint's own
    goal of *robust* enforcement (a crashed manual choreography could
    leave an orphaned emulator process masking a real failure as a hang).
  - The `--project demo-test-ci` flag uses Firebase's documented
    `demo-`-prefixed project-id convention for emulator-only local
    testing — no real Firebase project, login, or credentials are
    involved anywhere in this sprint.

## Changed

- **`tests/hand-sync.rules-emulator-mvp-deal-authority.test.cjs`,
  `hand-sync.rules-emulator-p02-dispatch.test.cjs`,
  `hand-sync.rules-emulator-rematch-fix.test.cjs`,
  `hand-sync.rules-emulator.test.cjs`,
  `matches-update-dispatch.rules-emulator.test.cjs`,
  `sprint-a-write-paths.rules-emulator.test.cjs`**: the identical
  catch-block all 6 files shared (byte-for-byte identical, confirmed via
  md5sum before editing) now logs a `FATAL` message and calls
  `process.exit(1)` if `initializeTestEnvironment()` can't reach the
  emulator, instead of logging `SKIPPED` and exiting 2. No test
  assertion, no rules-reading logic, no scenario inside any of these
  files was touched — only this one shared catch block.
- **`README.md`**: new "Tests" section instructions for `npm test` vs.
  `npm run test:ci`, and what "mandatory, not optional" now means in
  practice.

## Fixed (found along the way, in scope for this sprint)

- **`package.json` never declared `@firebase/rules-unit-testing` as a
  dependency**, even though all 6 emulator test files `require()` it.
  It was apparently installed ad hoc in an earlier sprint without being
  added to `package.json`, so it silently worked only because
  `node_modules/` happened to still have it from that earlier install —
  a fresh `npm ci` (exactly what CI does) would have failed every one of
  these 6 files at `require()` time, before ever reaching the emulator.
  Confirmed directly: my own `npm install` (adding `firebase-tools`)
  pruned it as an undeclared package, immediately reproducing the
  failure. Added `"@firebase/rules-unit-testing": "^5.0.1"` to
  `devDependencies`. This is squarely test-infrastructure, not game
  logic, and was necessary for this sprint's own goal (a green CI run
  must mean something) to be achievable at all.
- **`devDependencies.firebase-tools`** added (`^13.35.1`) so `firebase`
  is available via `node_modules/.bin` in `npm run` scripts and in CI,
  without relying on `npx`'s network-fetch-per-invocation behavior.

## Not changed

- No file under `design-ui/engine/*.js`, `match-service.js`,
  `match-adapter.js`, any `*-service.js`, `bidding-engine.js`,
  `scoring-engine.js`, `table-engine.js`, or `dealer.js`.
- `firestore.rules`' actual rule semantics: unchanged. The one edit made
  during this sprint (`allow get: if ownsSeat();` → `if false;`) was an
  intentional, disclosed, single-line sabotage used ONLY to prove the
  pipeline catches a real regression, and was reverted before this
  sprint's commit — `git diff firestore.rules` at commit time shows no
  change beyond the already-committed Sprint-D warning header from the
  prior sprint.
- No Blaze feature anywhere: the Firestore/Auth Emulator Suite used by
  `test:ci` is part of Firebase's free tooling, runs entirely locally,
  and requires no live project, billing account, or `firebase login`.

## Testing

See `TEST_CHECKLIST.md` in this folder for the full record: the hard-fail
proof (no emulator), the green-path proof (35/35, 138 real emulator
assertions across the 6 files), and the intentional-sabotage proof (4/6
files correctly failed against a real, disclosed rule regression, then a
clean revert back to green).

**One disclosed limitation**: `.github/workflows/test.yml`'s actual live
execution on GitHub's runners has not been observed from within this
session — everything above was verified by running the exact same
`npm run test:ci` command the workflow invokes, locally, in this sandbox
(which has Java + Node 22 + the same emulator stack GitHub's
`ubuntu-latest` runner provides). The workflow file itself will run for
real on the next push to `claude/busy-bohr-ez5rz3`; recommend checking
the Actions tab after that push to confirm the hosted run matches this
sandbox's result, rather than assuming environment parity blindly.
