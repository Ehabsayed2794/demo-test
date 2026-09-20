# TASK T-001: Fix dealer-rotation-rules.test.cjs Failure

## TASK ID
T-001

## PRIORITY
P0 — Critical (blocks clean CI)

## OBJECTIVE
Fix the `dealer-rotation-rules.test.cjs` test failure so it passes when the Firebase Rules Emulator is running.

## BACKGROUND
This test validates the Firestore Rules' `expectedNextDealer()` function (in `firestore.rules:978-992`) which enforces correct dealer rotation during round advance. The test requires the real Firestore Rules Emulator (port 8080) to execute against actual compiled CEL rules.

**Current Status:** Test exits with code 2 and "REAL EMULATOR UNAVAILABLE" when run via `npm test` (no emulator). This is expected behavior per the test design — it's an emulator-dependent test, not a broken unit test.

**Root Cause:** The test is categorized as an emulator test but doesn't follow the `*rules-emulator*.test.cjs` naming convention. It should either:
1. Be renamed to `dealer-rotation-rules.rules-emulator.test.cjs` (to match the pattern), OR
2. The CI workflow should include it in the emulator test run (it already does via `npm run test:ci`)

## SCOPE
- **Investigate:** Run the test against a live emulator to determine if it actually PASSES or has genuine failures
- **Fix:** If genuine failures exist, fix either the test expectations or the rules logic
- **Categorize:** Rename file to follow `*.rules-emulator.test.cjs` convention if it truly requires emulator

## NON-GOALS
- Do not modify `firestore.rules` dealer rotation logic unless the test proves a real bug
- Do not convert this to a mocked test — it must validate real CEL compilation/execution

## RELEVANT FILES
- `tests/dealer-rotation-rules.test.cjs` — the test file
- `firestore.rules:978-992` — `expectedNextDealer()` function
- `firestore.rules:1023-1036` — `isValidRoundAdvance()` which calls `expectedNextDealer()`
- `design-ui/match-service.js:1549-1566` — `nextDealerUid()` (JS mirror)
- `.github/workflows/test.yml` — CI workflow

## ACCEPTANCE CRITERIA
1. Test runs and PASSES when Firebase Rules Emulator is available (`npm run test:ci`)
2. Test is properly categorized (renamed to `*.rules-emulator.test.cjs` if emulator-required)
3. No regression in round-advance dealer rotation logic

## TEST REQUIREMENTS
- Start Firebase Emulator: `npx firebase-tools emulators:start --only firestore,auth --project demo-test-ci`
- Run test: `node tests/dealer-rotation-rules.test.cjs`
- Verify all 13 positive transitions + 4 negative transitions + 5 creation shapes pass

## VERIFICATION COMMANDS
```bash
# Terminal 1: Start emulator
npx firebase-tools emulators:start --only firestore,auth --project demo-test-ci

# Terminal 2: Run test
node tests/dealer-rotation-rules.test.cjs

# Expected: All checks PASS, exit code 0
```

## EXPECTED EVIDENCE
- Test output showing all PASS checks
- Exit code 0
- If failures: specific rule/test mismatch identified

## RISKS
- If `expectedNextDealer()` in rules diverges from `nextDealerUid()` in match-service.js, round advance will fail in production
- This test is the ONLY validation that the CEL dealer rotation logic compiles and works correctly

## DEPENDENCIES
- Firebase Rules Emulator must be installed and working
- `firestore.rules` must be current (SHA-256: `9d8662e3da774b74e1d8570521b64a69bbde6fda4d18ad4158f81daa0cc4b516`)

## ROLLBACK / SAFETY NOTES
- This is a test-only change (or test + minimal rules fix)
- No production code modified unless rules bug confirmed
- If rules fix needed: verify `match-service.js:nextDealerUid()` matches exactly