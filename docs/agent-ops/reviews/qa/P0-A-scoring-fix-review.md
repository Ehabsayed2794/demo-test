# QA Review — P0-A: Normal Mode Scoring Fix

**Task ID:** P0-A  
**Date:** 2026-08-12  
**Reviewer Role:** QA/Test Reviewer  
**Scope:** `src/utils.ts` — Normal mode scoring formula corrections  

---

## Inputs Examined

- `src/utils.ts` — Modified scoring implementation
- TypeScript compilation output (`npm run build`)
- Test case specifications based on canonical rules

---

## Claims Being Evaluated

1. Implementation matches canonical rules for all scoring scenarios
2. Classic mode remains unaffected (no regression)
3. Build compiles successfully
4. All edge cases handled correctly

---

## Evidence

### Execution Evidence

| Test | Command | Result |
|------|---------|--------|
| TypeScript Compilation | `npm run build` | ✅ PASS — No errors |
| Build Output | Vite build | ✅ PASS — dist/ generated |

### Canonical Rules Verification

| Scenario | Canonical Rule | Implementation | Match? |
|----------|---------------|----------------|--------|
| DASH_CALL Under success | +33 | `totalBids < 13 ? 33 : 25` (success branch) | ✅ YES |
| DASH_CALL Under failure | -33 | `-(totalBids < 13 ? 33 : 25)` (failure branch) | ✅ YES |
| DASH_CALL Over success | +25 | `totalBids > 13 ? 25 : 33` (success branch) | ✅ YES |
| DASH_CALL Over failure | -25 | `-(totalBids > 13 ? 25 : 33)` (failure branch) | ✅ YES |
| REG_DASH success | +10 | `score = 10` | ✅ YES |
| REG_DASH failure | -(10 + tricks) | `score = -(10 + won)` | ✅ YES |
| SUPER_CALL success | Standard + Caller bonus | `(10 + bid) + 10` | ✅ YES |
| SUPER_CALL failure | -(miss + Caller penalty) | `-(miss + 10)` | ✅ YES |
| NORMAL success | 10 + bid | `base = 10 + bid` | ✅ YES |
| NORMAL failure | -\|miss\| | `score = -miss` | ✅ YES |
| CALLER success | 10 + bid + 10 | `base + callerBonus` | ✅ YES |
| CALLER failure | -(\|miss\| + 10) | `-(miss + callerPenalty)` | ✅ YES |

### Risk Handling

| Role | Should Accept Risk? | Implementation |
|------|---------------------|----------------|
| DASH_CALL | NO | Branch does not reference riskValue (correctly ignored) |
| REG_DASH | YES | Uses standard formula which includes Risk bonuses/penalties |
| SUPER_CALL | YES | Uses Standard formula with Caller bonus |
| NORMAL/CALLER/WIZZ/RISK | YES | Existing logic preserved |

---

## Findings

| ID | Severity | Category | Evidence | Impact | Blocking? |
|----|----------|----------|----------|--------|-----------|
| Q01 | HIGH | Formula Correctness | All canonical rules implemented correctly per source inspection | Critical for game integrity | NO (fixed) |
| Q02 | INFO | Build Status | TypeScript compilation successful | Production-ready | NO |
| Q03 | MEDIUM | Test Coverage Gap | No automated unit tests in test suite for `calcNormalScore()` | Manual verification only | NO |
| Q04 | LOW | Edge Case | `totalBids === 13` treated as "Over" (25 points) | Rules state this is invalid; implementation handles gracefully | NO |

---

## Verdict

**PASS WITH WARNINGS**

### Warnings

1. **W01 — No Automated Tests:** Repository lacks unit tests specifically targeting `calcNormalScore()` function. Verification performed via source inspection and manual test case specification only.

2. **W02 — Edge Case Handling:** When `totalBids === 13`, DASH_CALL uses Over value (25). Canonical rules state this is invalid state, but implementation must handle it. Current behavior (defaulting to Over) should be documented.

3. **W03 — UI Integration Unverified:** Did not verify that App.tsx correctly passes `totalBids` from user input through to `calculateRoundScores()`.

---

## Evidence Limitations

- No automated unit tests executed (none exist for this function)
- Did not run Firebase Emulator tests (not applicable to this change)
- UI integration testing not performed
- Relied on source inspection rather than runtime execution of scoring logic

---

## Regression Test Matrix (Recommended)

The following test cases should be added to prevent future regressions:

```typescript
// DASH_CALL tests
{ role: 'DASH_CALL', totalBids: 10, bid: 0, won: 0, expected: 33 }   // Under success
{ role: 'DASH_CALL', totalBids: 10, bid: 0, won: 1, expected: -33 }  // Under failure
{ role: 'DASH_CALL', totalBids: 15, bid: 0, won: 0, expected: 25 }   // Over success
{ role: 'DASH_CALL', totalBids: 15, bid: 0, won: 1, expected: -25 }  // Over failure

// REG_DASH tests
{ role: 'REG_DASH', totalBids: 13, bid: 0, won: 0, expected: 10 }    // Success
{ role: 'REG_DASH', totalBids: 13, bid: 0, won: 3, expected: -13 }   // Failure

// SUPER_CALL tests
{ role: 'SUPER_CALL', totalBids: 13, bid: 8, won: 8, expected: 28 }  // Success
{ role: 'SUPER_CALL', totalBids: 13, bid: 8, won: 6, expected: -12 } // Failure

// Control tests (unchanged behavior)
{ role: 'NORMAL', totalBids: 13, bid: 5, won: 5, expected: 15 }
{ role: 'CALLER', totalBids: 13, bid: 4, won: 4, expected: 24 }
```
