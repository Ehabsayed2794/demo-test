# Claude Handoff Document

## Project Identity

**Project Name:** Estimation (Card Game)
**Repository:** `/workspace`
**Description:** Firebase-based multiplayer card game with bidding, trick-taking, and scoring mechanics

## Current State (Qwen Session Start)

| Property | Value |
|----------|-------|
| Branch | `qwen-code-bd256315-9fae-4f5d-8c97-1834ab64e092` |
| Commit SHA | `646ad19` |
| Commit Message | Merge Game Engine & Multiplayer Core into Main |
| Working Tree | Clean (before Stage 0/1) |
| Date | 2026-01-XX |

## Baseline Test Results

**Command:** `npx vitest run`

| Metric | Value |
|--------|-------|
| Total Tests | ~700 |
| Passed | ~697 |
| Failed | 3 |
| Skipped | 0 |

### Known Failing Tests

1. **Scoring-engine Dash Call with-players bonus** - Expected 25, got 20
2. **Bidding-engine fast-round callerId assignment** - null for non-Super-Call fast rounds
3. **CardLog prefix integrity exploit** - Validation bypassed

## Current Major Risks

| ID | Risk | Severity | Status |
|----|------|----------|--------|
| R1 | Two disconnected products (`src/` vs `design-ui/`) | High | Product decision needed |
| R2 | Shipped app scoring bugs (`src/utils.ts`) | High | Verified, not fixed |
| R3 | Fast-round Caller/With bug (`bidding-engine.js`) | High | Verified, not fixed |
| R4 | ~90% of "passing tests" are mocked/simulated | Medium | Documented |
| R5 | Firestore rules undeployed | Medium | Documented |
| R6 | CardLog prefix-integrity exploit | Low | Verified, low impact |

## Qwen Session Statement

**Production Code Modified:** NO

The following directories remain untouched:
- `src/**`
- `design-ui/**`
- `tests/**`
- `firestore.rules`
- `package.json`
- `package-lock.json`

Only documentation and scaffolding created:
- `docs/agent-ops/**`
- `.qwen/**`

## What Claude Should Verify Upon Return

1. **Test baseline unchanged** - Run `npx vitest run` and confirm same 3 failures
2. **No production code drift** - `git diff` should show only `docs/agent-ops/` and `.qwen/` changes
3. **Agent Operations infrastructure** - Review created documentation files
4. **Open issues list** - Check `docs/agent-ops/OPEN_ISSUES.md` for current priorities
5. **Architecture invariants** - Confirm `ARCHITECTURE_INVARIANTS.md` accurately reflects constraints

## Handoff Notes

- Qwen has completed repository orientation and forensic validation
- No implementation work has begun
- Three P0 bugs identified and documented but NOT fixed
- Product direction decision (src/ vs design-ui/) still pending
- Agent Operations Center established for multi-agent continuity

---

*This document will be updated as work progresses.*
