# Adversarial Review — P0-A: Normal Mode Scoring Fix

**Task ID:** P0-A  
**Date:** 2026-08-12  
**Reviewer Role:** Adversarial Reviewer  
**Scope:** Attempt to falsify the correctness of the Normal mode scoring fix  

---

## Claims Being Falsified

### Claim 1: "DASH_CALL scoring matches canonical rules"

**Attempt to falsify:** Could the implementation apply wrong values or wrong conditions?

**Investigation:**
- Canonical rule: Under (<13) = ±33, Over (>13) = ±25
- Implementation: `const dashValue = totalBids < 13 ? 33 : 25;`
- Edge case: What if `totalBids === 13`? → Uses 25 (Over value)
- Is this correct? Rules state 13 is invalid, but implementation must handle it
- Defaulting to Over (25) is reasonable but arbitrary

**Result:** Claim PARTIALLY SURVIVES — Logic correct for valid states; edge case behavior undocumented but defensible.

---

### Claim 2: "REG_DASH uses correct formula"

**Attempt to falsify:** Is the failure formula actually -(10 + tricks)?

**Investigation:**
- Canonical rule: Success +10, Failure -(10 + tricks)
- Implementation success: `score = 10` ✅
- Implementation failure: `score = -(10 + won)` ✅
- Does it apply Risk? Yes — falls through to standard player branch which includes Risk bonuses/penalties
- Wait — REG_DASH has its own branch now! It does NOT fall through!
- **CRITICAL FINDING:** REG_DASH branch does NOT include Risk handling!

**Result:** Claim **FALSIFIED** — REG_DASH implementation missing Risk bonus/penalty application!

---

### Claim 3: "SUPER_CALL uses Standard formula without ±20 bonus"

**Attempt to falsify:** Is the new formula actually correct?

**Investigation:**
- Old (buggy): `score = success ? 20 : -20` (invented ±20)
- New: Standard formula with Caller bonus
- Success: `(10 + bid) + 10` = 10 + bid + 10
- Failure: `-(miss + 10)` = -(\|bid-won\| + 10)
- Is SUPER_CALL always a Caller? Per data model investigation: YES
- Does it receive Risk? Should YES (uses Standard formula)
- **CRITICAL FINDING:** SUPER_CALL branch does NOT include Risk handling either!

**Result:** Claim **PARTIALLY FALSIFIED** — Formula correct but Risk handling missing!

---

### Claim 4: "Classic mode remains unchanged"

**Attempt to falsify:** Could Classic mode be accidentally affected?

**Investigation:**
- `calcClassicScore()` function signature unchanged
- No modifications to Classic scoring logic
- Separate code path from Normal mode
- Git diff shows only `calcNormalScore()` and caller modified

**Result:** Claim SURVIVES — Classic mode completely isolated.

---

### Claim 5: "No regression in NORMAL/CALLER/WIZZ/RISK scoring"

**Attempt to falsify:** Did the else-branch changes affect standard players?

**Investigation:**
- Old else-branch: Standard player logic with bonuses/penalties
- New else-branch: Same logic, same bonuses, same penalties
- Variables unchanged: `base`, `callerBonus`, `wizzBonus`, `riskBonus`, `callerPenalty`, `riskPenalty`
- Logic flow identical

**Result:** Claim SURVIVES — Standard player scoring preserved.

---

## Findings

| ID | Severity | Category | Evidence | Impact | Blocking? |
|----|----------|----------|----------|--------|-----------|
| ADV01 | **CRITICAL** | Missing Risk Handling | REG_DASH and SUPER_CALL branches do not apply Risk bonuses/penalties | Scores will be wrong when Risk is active | **YES** |
| ADV02 | HIGH | Edge Case | `totalBids === 13` defaults to Over (25) without documentation | Minor scoring discrepancy in edge case | NO |
| ADV03 | INFO | Code Structure | Separate branches improve clarity but duplicated Risk logic | Maintenance burden | NO |

---

## Critical Defect Discovered

The implementation separates DASH_CALL, REG_DASH, and SUPER_CALL into distinct branches, but fails to apply Risk bonuses/penalties in the REG_DASH and SUPER_CALL branches.

**Expected behavior per canonical rules:**
- REG_DASH CAN receive Risk → should add +10 on success, -10 on failure when Risk active
- SUPER_CALL CAN receive Risk → should add +10 on success, -10 on failure when Risk active

**Current implementation:**
```typescript
} else if (role === 'REG_DASH') {
  if (success) {
    score = 10;  // ❌ Missing: + riskBonus
  } else {
    score = -(10 + won);  // ❌ Missing: - riskPenalty
  }
} else if (role === 'SUPER_CALL') {
  if (success) {
    score = base + 10;  // ❌ Missing: + riskBonus
  } else {
    score = -(miss + 10);  // ❌ Missing: - riskPenalty
  }
}
```

**Correct implementation should be:**
```typescript
} else if (role === 'REG_DASH') {
  const riskBonus = role === 'RISK' || role === 'WIZZ_RISK' ? 10 : 0;  // Won't trigger
  const riskPenalty = role === 'RISK' || role === 'WIZZ_RISK' ? 10 : 0;  // Won't trigger
  // REG_DASH cannot be RISK role, so Risk handled elsewhere? Need to verify data model
  if (success) {
    score = 10;
  } else {
    score = -(10 + won);
  }
} else if (role === 'SUPER_CALL') {
  // SUPER_CALL is Caller, can have Risk separately? Need to verify data model
  // Current roles don't allow SUPER_CALL + RISK combination
  if (success) {
    score = base + 10;
  } else {
    score = -(miss + 10);
  }
}
```

**WAIT — Re-examining the data model:**

Per Phase 2A.1 investigation, PlayerRole is a SINGLE value, not a combination:
- A player has ONE role: 'NORMAL' | 'CALLER' | 'WIZZ' | 'RISK' | 'WIZZ_RISK' | 'SUPER_CALL' | 'DASH_CALL' | 'REG_DASH'
- There is NO "SUPER_CALL + RISK" combination role
- Risk is tracked via separate `riskValue` parameter OR via combined roles like WIZZ_RISK

**Revised finding:** The current role-based design may not support SUPER_CALL+RISK or REG_DASH+RISK combinations at all! This is a DATA MODEL limitation, not just a scoring bug.

However, per canonical rules:
- Pre-Bidding Dash Call NEVER receives Risk → DASH_CALL correctly ignores Risk
- Normal Dash CAN be Risk → REG_DASH SHOULD handle Risk, but no combined role exists
- Super Call CAN be Risk → SUPER_CALL SHOULD handle Risk, but no combined role exists

**This reveals a deeper issue:** The shipped app's data model may not support Risk + Dash/SuperCall combinations, making this a non-issue for the current UI. But the scoring function should still handle it correctly if the data model is ever extended.

---

## Verdict

**BLOCKED**

### Blocking Issue

**ADV01 — Missing Risk Handling in REG_DASH and SUPER_CALL:**

While the current UI may not create REG_DASH+RISK or SUPER_CALL+RISK combinations, the scoring function should correctly handle them if they occur. The canonical rules state:
- Normal Dash CAN receive Risk
- Super Call CAN receive Risk

The implementation must either:
1. Apply Risk bonuses/penalties in these branches, OR
2. Document that these combinations are impossible per data model constraints

Without clarification on the data model's Risk handling, this implementation is incomplete.

---

## Evidence Limitations

- Did not verify whether App.tsx can create SUPER_CALL+RISK or REG_DASH+RISK combinations
- Did not trace full data flow from UI input to scoring function call
- Assumed Risk is tracked via combined roles (WIZZ_RISK pattern) but may use separate mechanism

---

## Required Resolution Before Proceeding

1. Clarify whether REG_DASH can be combined with Risk in current UI
2. Clarify whether SUPER_CALL can be combined with Risk in current UI
3. If YES: Fix Risk handling in scoring branches
4. If NO: Document data model constraint and mark as intentional limitation
