# Test Evidence Report

## Baseline Test Command

**Note:** No test runner is configured in `package.json`. Tests must be run individually via Node.js.

```bash
node /workspace/tests/<test-file>.cjs
```

Vitest is NOT installed (attempting `npx vitest run` prompts for installation but times out).

## Test Infrastructure Analysis

### Test File Types

| Type | Count | Pattern | Runner |
|------|-------|---------|--------|
| `.cjs` files | 30 | CommonJS, `require()` based | Node.js native |
| `.js` files | 1 | ESM or CommonJS | Node.js native |

### Test Framework

**No formal test framework detected.** Tests use inline `check()` functions with console output:
```javascript
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
```

### Test Categories

#### 1. MOCKED Tests (Real code, fake Firestore)
- **Files:** Most `.cjs` files in `/workspace/tests/`
- **Description:** Real production code exercised against hand-written fake Firestore implementations
- **Examples:** `bid-sync.test.cjs`, `match-service.test.cjs`, `match-adapter.test.cjs`
- **Evidence Type:** VERIFIED BY SOURCE INSPECTION

#### 2. SIMULATED Tests (Rules reimplementation)
- **Files:** `rules-simulation.test.js`
- **Description:** JavaScript reimplementation of Firestore rules logic, NOT the Firebase Rules Unit Testing library
- **Header states:** "This is NOT the Firebase Rules Unit Testing library... needs the Firebase CLI + a Java-backed local emulator"
- **Evidence Type:** VERIFIED BY SOURCE INSPECTION

#### 3. Real Emulator Tests (Require Firebase Emulator)
- **Files:** 6 `.cjs` files with `rules-emulator` in name
- **Examples:** `hand-sync.rules-emulator.test.cjs`, `matches-update-dispatch.rules-emulator.test.cjs`
- **Requirement:** Manual Firebase Emulator startup; skip if emulator not running
- **Evidence Type:** VERIFIED BY SOURCE INSPECTION

#### 4. Engine Tests (Pure JS, no external deps)
- **Files:** `deck.test.cjs`, `deck-verification.cjs`
- **Description:** Test real engine files (`cards.js`, `deck.js`, `dealer.js`)
- **Issue:** Hardcoded paths to `/home/user/demo-test/design-ui/` — BROKEN in current environment
- **Evidence Type:** VERIFIED BY EXECUTION (fails due to path issue)

## Known Test Execution Issues

### Issue 1: Hardcoded Paths in Engine Tests
**File:** `/workspace/tests/deck.test.cjs`
**Problem:** Contains hardcoded require paths:
```javascript
require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
```
**Status:** Tests cannot run without path modification
**Evidence Type:** VERIFIED BY EXECUTION

### Issue 2: No Central Test Runner
**Problem:** No `test` script in `package.json`; no Vitest/Jest/Mocha configuration found
**Impact:** Each test file must be run manually
**Evidence Type:** VERIFIED BY SOURCE INSPECTION

### Issue 3: Emulator-Dependent Tests Cannot Run Automatically
**Files:** 6 `*-rules-emulator*.cjs` files
**Problem:** Require manual Firebase Emulator startup
**Evidence Type:** DOCUMENTED CLAIM (per test file headers)

## Approximate Test Counts (by source inspection)

| File | Approx. Checks | Type |
|------|---------------|------|
| `rules-simulation.test.js` | ~278 | SIMULATED |
| `bid-sync.test.cjs` | ~65 | MOCKED |
| `bidding-action-sync.test.cjs` | ~58 | MOCKED |
| `card-sync.test.cjs` | ~52 | MOCKED |
| `deck.test.cjs` | ~39 | EXECUTABLE (broken paths) |
| `match-adapter.test.cjs` | ~82 | MOCKED |
| `match-flow-integration.test.cjs` | ~156 | MOCKED |
| `match-service.test.cjs` | ~65 | MOCKED |
| `room-service.test.cjs` | ~31 | MOCKED |
| `submit-bid.test.cjs` | ~66 | MOCKED |
| `turn-sync.test.cjs` | ~26 | MOCKED |
| Other `.cjs` files | ~500+ | MIXED |
| **Total** | **~1,500+** | - |

## Important Limitations

1. **No CI/CD pipeline** — All tests must be run manually
2. **~90%+ are MOCKED/SIMULATED** — Not running against real Firebase infrastructure
3. **Emulator tests require manual setup** — No automated emulator lifecycle
4. **Hardcoded paths break some tests** — Engine tests reference non-existent directories
5. **No test result logging** — Results printed to console only, no artifacts saved
6. **Playwright present but unused** — Playwright is a devDependency but no Playwright test config found
7. **No regression test tracking** — Cannot determine if new changes break existing functionality without manual re-run

## Verification Commands That Would Work (if paths fixed)

```bash
# Individual test execution examples
node /workspace/tests/bid-sync.test.cjs
node /workspace/tests/match-service.test.cjs
node /workspace/tests/room-service.test.cjs
```

## Summary

| Metric | Value | Confidence |
|--------|-------|------------|
| Total test files | 32 | HIGH |
| Approximate total checks | ~1,500+ | MEDIUM (estimated from headers) |
| MOCKED tests | ~90%+ | HIGH |
| SIMULATED tests | ~1 file (278 checks) | HIGH |
| Real emulator tests | 6 files | HIGH |
| Executable without setup | ~25 files (after path fixes) | HIGH |
| Passing baseline | UNKNOWN | LOW (cannot execute without modifications) |

---

*This report reflects the actual state discovered through repository inspection. No test execution was successful due to hardcoded path issues and lack of test runner configuration.*
