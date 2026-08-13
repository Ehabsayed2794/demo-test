# Test Checklist — Sprint 5.0: CI/CD Pipeline & Real Emulator Enforcement

**Emulator-tier tests are now MANDATORY, not optional.** Prior to this
sprint, `tests/*rules-emulator*.test.cjs` silently printed `SKIPPED` and
exited 2 if no Firestore Rules Emulator was reachable — a green CI run
never actually proved anything about the real rules. That is no longer
possible: these 6 files now FAIL HARD (exit 1) with no emulator, and the
new `scripts/run-tests.mjs` runner independently scans every file's output
for the literal word `SKIPPED` as a second, defense-in-depth guard.

## 1. Hard-fail proof (no emulator running)

```
$ node tests/hand-sync.rules-emulator.test.cjs
EMULATOR NOT REACHABLE — fetch failed

FATAL: the Firestore Rules Emulator must be running on 127.0.0.1:8080 for
this test to run. This is a HARD FAILURE, not a skip.

=== RESULTS ===

0 passed, 0 failed (FAILED — emulator unreachable)
$ echo $?
1
```
Confirmed for all 6 files individually; no `SKIPPED` text anywhere,
exit code 1 in every case.

## 2. Green-path proof (real emulator, real checks)

`npm run test:ci` (starts Firestore + Auth emulators via
`firebase emulators:exec`, runs the full 35-file suite, tears the
emulators down, propagates the real exit code) — full log:
`Sprint-5.0-Review/test-ci-green-run.log`.

**Result: 35/35 test files passed cleanly. Zero `SKIPPED` markers. Exit
code 0.**

Real per-file emulator check counts (not just "exit 0" — actual assertion
counts against the live emulator):

| File | Checks |
|---|---|
| `hand-sync.rules-emulator-mvp-deal-authority.test.cjs` | 14 passed, 0 failed |
| `hand-sync.rules-emulator-p02-dispatch.test.cjs` | 18 passed, 0 failed |
| `hand-sync.rules-emulator-rematch-fix.test.cjs` | 15 passed, 0 failed |
| `hand-sync.rules-emulator.test.cjs` | 32 passed, 0 failed |
| `matches-update-dispatch.rules-emulator.test.cjs` | 2 passed, 0 failed |
| `sprint-a-write-paths.rules-emulator.test.cjs` | 57 passed, 0 failed |
| **Total real emulator assertions** | **138** |

## 3. Intentional-sabotage proof (the actual point of this sprint)

Per the request's own deliverable #4 ("or failing correctly if you break
a rule intentionally to test it") — temporarily changed
`firestore.rules`' hand-read rule from `allow get: if ownsSeat();` to
`allow get: if false;` (one line, reverted immediately after), then ran
`npm run test:ci` again against the *same* real emulator. Full log:
`Sprint-5.0-Review/test-ci-sabotage-run.log`.

**Result: 4 of 6 emulator files correctly failed**, each naming the exact
broken check:
```
FAIL  2. Own hand read -> ALLOW                                    (hand-sync.rules-emulator-mvp-deal-authority.test.cjs)
FAIL  HS-regress. own hand read -> ALLOW                            (hand-sync.rules-emulator-p02-dispatch.test.cjs)
FAIL  Hand Sync regression — own hand read (P1 -> hands/p1) — ALLOWED   (hand-sync.rules-emulator-rematch-fix.test.cjs)
FAIL  A1. Player P1 CAN read hands/p1 (own hand)                    (hand-sync.rules-emulator.test.cjs)
FAIL  A5. Player P2 CAN read hands/p2 (own hand) — symmetric check  (hand-sync.rules-emulator.test.cjs)
```
Summary line: `31/35 test files passed cleanly.` Pipeline exit code
non-zero (`Script exited unsuccessfully (code 1)` /
`Error: Script "node scripts/run-tests.mjs" exited with code 1`).

**This is the actual proof the sprint's own Goal asked for: "If the rules
are wrong, the build MUST fail."** It did — against a real bug, not a
simulated one.

The sabotage was reverted immediately after capturing this log
(`git diff firestore.rules` shows zero uncommitted changes) and a final
green re-run (`test-ci-green-run.log`) confirms the suite is back to
35/35.

## 4. GitHub Actions workflow

`.github/workflows/test.yml` — triggers on push to
`claude/busy-bohr-ez5rz3` and `main`; checkout → setup-node 22 → `npm ci`
→ `npm run test:ci`. No `continue-on-error`/`if: always()` anywhere in the
test step, so any failure (individual test failure OR the runner's own
`SKIPPED`-text scan) turns the workflow red. Live GitHub Actions execution
will be visible on the next push to this branch — see the sprint's
CHANGELOG for the disclosed limitation on verifying this from within the
sandbox itself.

## 5. Scope check

`git diff --name-only` for this sprint touches only test-infrastructure
and CI files, plus the two dependencies needed to make the existing
emulator tests actually runnable (see CHANGELOG's "found along the way"
note): no `design-ui/engine/*.js`, no `*-service.js`, no
`firestore.rules` rule semantics (only the header comment from the prior
sprint remains there — this sprint made zero rule changes of its own,
confirmed by the sabotage-then-revert leaving a clean `git diff`).
