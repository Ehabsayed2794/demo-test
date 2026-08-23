# Architecture Review — P0-A: Normal Mode Scoring Fix

**Task ID:** P0-A  
**Date:** 2026-08-12  
**Reviewer Role:** Architecture Reviewer  
**Scope:** `src/utils.ts` — Normal mode scoring formula corrections  

---

## Inputs Examined

- `src/utils.ts` — Modified `calcNormalScore()` function and `calculateRoundScores()` caller
- `src/types.ts` — PlayerRole type definitions (unchanged)
- Git diff showing changes to scoring logic

---

## Claims Being Evaluated

1. Changes are limited to Normal mode scoring formulas only
2. Classic mode scoring remains completely unchanged
3. Function signature change is minimal and safe
4. No architecture boundaries violated
5. No unintended coupling introduced

---

## Evidence

| Claim | Evidence Type | Result |
|-------|---------------|--------|
| Normal mode formulas corrected | VERIFIED BY SOURCE INSPECTION | ✅ DASH_CALL, REG_DASH, SUPER_CALL branches updated per canonical rules |
| Classic mode unchanged | VERIFIED BY SOURCE INSPECTION | ✅ `calcClassicScore()` function untouched |
| Signature change minimal | VERIFIED BY SOURCE INSPECTION | ✅ Only added `totalBids: number` parameter to `calcNormalScore()` |
| Caller updated correctly | VERIFIED BY SOURCE INSPECTION | ✅ `calculateRoundScores()` passes `totalBids` to `calcNormalScore()` |
| No cross-coupling | VERIFIED BY SOURCE INSPECTION | ✅ No imports from `design-ui/`, no new dependencies |

---

## Findings

| ID | Severity | Category | Evidence | Impact | Blocking? |
|----|----------|----------|----------|--------|-----------|
| A01 | INFO | Function Signature | Added `totalBids` parameter required for DASH_CALL scoring | None — internal function | NO |
| A02 | INFO | Code Comments | Updated comments reflect Official Scoring System | Improves maintainability | NO |
| A03 | INFO | Branch Separation | DASH_CALL and REG_DASH now separate branches | Clearer logic flow | NO |

---

## Verdict

**PASS**

### Rationale

1. **Minimal change scope:** Only `calcNormalScore()` modified; `calcClassicScore()` untouched
2. **Clean separation:** Normal and Classic modes remain independent
3. **No boundary violations:** No imports from `design-ui/`, no new dependencies introduced
4. **Signature change justified:** `totalBids` parameter necessary for Pre-Bidding Dash Call scoring
5. **Architecture preserved:** Layering discipline maintained (utils.ts remains pure utility module)

---

## Evidence Limitations

- Did not execute runtime tests (TypeScript compilation passed)
- Did not verify UI integration with App.tsx
- Relied on source inspection rather than dynamic analysis

---

## Recommendations

1. Consider extracting scoring constants to separate configuration object for easier rule updates
2. Add TypeScript interface for scoring result to improve type safety
3. Document why `totalBids === 13` is invalid rules state in code comment
