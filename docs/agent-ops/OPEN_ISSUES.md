# Open Issues Log

**Last Updated:** 2026-01-XX (Qwen Session Stage 0/1)

---

## P0 — Critical (Must Fix Before Any Feature Work)

### Issue P0-1: Two Disconnected Products
- **Description:** Repository contains `src/` (shipped score tracker) and `design-ui/` (unshipped multiplayer prototype) with zero integration
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §1, §9; `vite.config.ts` only builds `src/`
- **Impact:** No shippable multiplayer game exists despite extensive engineering investment
- **Resolution Required:** Product-direction decision from stakeholder
- **Status:** UNKNOWN (requires human decision)

### Issue P0-2: Shipped App Scoring Bugs (`src/utils.ts`)
- **Description:** Normal-mode Super Call uses invented ±20 bonus; Dash Call uses plain hit/miss instead of ±33/±25
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §5, §8; rules doc comparison
- **Impact:** Live shipped application produces incorrect scores
- **Files:** `/workspace/src/utils.ts`
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue P0-3: Fast-Round Caller/With Bug (`bidding-engine.js`)
- **Description:** Fast rounds (14-18) without Super Call have `callerId: null, withPlayers: []`, preventing Caller/With bonus scoring
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §5, §8; code inspection line 605
- **Impact:** Majority of fast rounds cannot award legitimate scoring bonuses
- **Files:** `/workspace/design-ui/engine/bidding-engine.js`
- **Status:** VERIFIED BY SOURCE INSPECTION

---

## High Priority

### Issue H-1: Firestore Rules Undeployed
- **Description:** `firestore.rules` header states "NOT YET DEPLOYED"
- **Evidence:** File header comment; `PROJECT_STATUS_AND_MASTER_PLAN.md` §5, §9
- **Impact:** Multiplayer security model not enforced in production
- **Files:** `/workspace/firestore.rules`
- **Status:** DOCUMENTED CLAIM

### Issue H-2: ~90%+ Tests Are MOCKED/SIMULATED
- **Description:** Most tests use fake Firestore; one rules-simulation test passed 100% while real rules contained non-compiling constructs
- **Evidence:** Test file headers; `PROJECT_STATUS_AND_MASTER_PLAN.md` §12
- **Impact:** Green test results do not guarantee real infrastructure works
- **Files:** `/workspace/tests/*.cjs`, `/workspace/tests/rules-simulation.test.js`
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue H-3: CardLog Prefix-Integrity Exploit
- **Description:** Seated client can rewrite/reorder earlier card-log entries, potentially changing trick winner
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §5, §11; documented as demonstrated exploit
- **Impact:** Game is "not suitable for ranked/competitive play"
- **Files:** `/workspace/design-ui/engine/card-play-validator.js`
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue H-4: No CI/CD Pipeline
- **Description:** No `.github/workflows/`; no `test` script in `package.json`
- **Evidence:** Directory inspection; `package.json` review
- **Impact:** Every test must be run manually; no automated regression detection
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue H-5: Presence/Abandonment Detection Not Implemented
- **Description:** `presence-service.js` is 100% stub functions
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §6, §11
- **Impact:** Opponent disconnects are invisible to other players
- **Files:** `/workspace/design-ui/presence-service.js`
- **Status:** VERIFIED BY SOURCE INSPECTION

---

## Medium Priority

### Issue M-1: Dead Navigation Links in Lobby
- **Description:** Lobby UI has buttons for "Ranked Match", "Play vs AI", Settings that navigate to non-existent files
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §5, §10
- **Impact:** Broken user experience in only working prototype screen
- **Files:** `/workspace/design-ui/lobby/index.html`, `/workspace/design-ui/game-state.js`
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue M-2: AI/Bot Opponents Advertised But Not Implemented
- **Description:** "Play vs AI" button exists; zero bid-decision or card-play logic for AI-controlled seats
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §6, §13
- **Impact:** Misleading UI copy; feature unusable
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue M-3: Missing Screens Referenced in Documentation
- **Description:** Shop, Ranked Match, Room, Settings, Final Standings screens referenced but files don't exist
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §6, §10
- **Impact:** Incomplete product experience
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue M-4: SUITS/RANKS Tables Triplicated
- **Description:** Card-value tables duplicated in `cards.js`, `bidding-engine.js`, `table-engine.js`
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §7, §18
- **Impact:** Drift risk; maintenance burden
- **Files:** `/workspace/design-ui/engine/cards.js`, `bidding-engine.js`, `table-engine.js`
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue M-5: Hardcoded Paths in Engine Tests
- **Description:** `deck.test.cjs` references `/home/user/demo-test/design-ui/` which doesn't exist
- **Evidence:** Test execution failure
- **Impact:** Engine tests cannot run without modification
- **Files:** `/workspace/tests/deck.test.cjs`
- **Status:** VERIFIED BY EXECUTION

---

## Low Priority

### Issue L-1: CSS Design Tokens Duplicated Across Screens
- **Description:** Token values consistent but duplicated four times with no shared stylesheet
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §7, §10
- **Impact:** WCAG contrast fixes must be applied four times
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue L-2: Shared Component Kit Low Adoption
- **Description:** Real Toast/Modal/Input/Skeleton kit exists but only Login screen uses it
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §4
- **Impact:** Inconsistent UI patterns; duplicated effort
- **Status:** VERIFIED BY SOURCE INSPECTION

### Issue L-3: Sprint Numbering Discipline Collapsed After 4.3
- **Description:** Named sprints exist only in `docs/reviews/` with no matching commits until reconstructed after data loss
- **Evidence:** `PROJECT_STATUS_AND_MASTER_PLAN.md` §2, §7
- **Impact:** Git history doesn't reflect actual development timeline
- **Status:** DOCUMENTED CLAIM

---

## Product Decisions Required

### Decision PD-1: Which Product Ships?
- **Options:**
  - A: Integrate `design-ui/` into `src/` build (make prototype the product)
  - B: Re-scope `design-ui/` as reference material; fix and ship only `src/`
  - C: Maintain two separate products with clear documentation
- **Blocks:** All feature work on `design-ui/`
- **Status:** UNKNOWN (requires stakeholder decision)

### Decision PD-2: Rules Document Authority
- **Question:** Is `uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx` the canonical source? Should it be committed to repo?
- **Current State:** Document exists only in upload folder, not in git
- **Status:** UNKNOWN

### Decision PD-3: Trusted Backend Migration
- **Question:** When/if to migrate from Spark (free, client-authoritative) to Blaze plan with Cloud Functions?
- **Current State:** Deliberately deferred; estimated 80-150 hours
- **Status:** DOCUMENTED CLAIM (deferred by design)

---

## Unknown / Needs Investigation

### Unknown U-1: Actual Test Pass/Fail Baseline
- **Reason:** Cannot execute tests due to hardcoded paths and no test runner
- **Investigation Needed:** Fix paths, run individual tests, record results
- **Status:** UNKNOWN

### Unknown U-2: Emulator Test Results
- **Reason:** Require manual Firebase Emulator startup
- **Investigation Needed:** Start emulator, run 6 emulator-dependent tests
- **Status:** UNKNOWN

### Unknown U-3: Playwright Multi-Client Verification
- **Reason:** Playwright is devDependency but no config or results found
- **Investigation Needed:** Locate or create Playwright config, execute multi-browser tests
- **Status:** UNKNOWN

### Unknown U-4: Firestore Rules Deployment Status
- **Reason:** File says "NOT YET DEPLOYED" but no evidence of deployment attempt
- **Investigation Needed:** Check live Firebase project, verify rules status
- **Status:** DOCUMENTED CLAIM (may be outdated)

### Unknown U-5: Golden Super Call Reset Rule
- **Reason:** Documented as not implemented; unclear if this is intentional or oversight
- **Investigation Needed:** Clarify rules interpretation with stakeholder
- **Status:** UNKNOWN

---

*This issues log will be updated as new issues are discovered or existing issues are resolved.*
