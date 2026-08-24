# PROJECT_STATUS_AND_MASTER_PLAN.md
### Estimation Card Game — Forensic Audit & Master Plan
Audit date: 2026-08-12 · Method: direct repository inspection (file reads, test execution, git history), no reliance on prior sprint reports' self-assessment. Five independent research passes (game engine, multiplayer/backend, UI/UX, roadmap reconciliation, test/QA rigor) were cross-referenced to produce this document. No code was modified during this audit.

---

## 1. Executive Summary

> **Authoritative product-direction decision PD-001 (24 August 2026):** `design-ui/` is the primary production product and target production codebase for the Estimation multiplayer game. `src/` is legacy/transitional score-tracker code. P0-01 is resolved; future multiplayer, gameplay, UI, lifecycle, persistence, and deployment work targets `design-ui/`. The selected product is not yet the shipped artifact, so the remaining blocker is build/deployment integration, not product direction.


The repository contains one selected production direction and one transitional legacy surface:

1. **Primary production product — `design-ui/`:** a real-time multiplayer Firestore-backed Estimation game with a genuine rules engine, Firebase Auth, room/match services, synchronization/security layers, and extensive focused tests. It is not yet the root-built/deployed artifact, and its user-facing gameplay screens remain incomplete.
2. **Legacy/transitional score tracker — `src/`:** a narrow React/Vite manual score-tracking tool. It remains in the repository for explicitly scoped maintenance, compatibility, or deprecation work, but it is not the target architecture for multiplayer gameplay and must not receive new multiplayer functionality.

The product-direction decision is resolved. The remaining engineering problem is to make `design-ui/` buildable, deployable, user-reachable, and production-validated without creating a second multiplayer implementation in `src/`. The existing `design-ui/` investment is substantial, but it still has missing render/navigation layers, known fast-round and trust-boundary work, no confirmed live Rules deployment, and no complete release-grade multi-client evidence.

The honest bottom line is therefore: the project has a selected multiplayer product with meaningful engine/backend foundations, but the selected product is not yet the shipped artifact and is not yet production-ready. `src/` scoring divergence is retained as legacy maintenance risk, not as a reason to redirect the multiplayer architecture.

---

## 2. Current Project Phase

**There is no single "current sprint."** The dotted-version sprint discipline (2.6→4.3) that governed `design-ui/`'s early build-out broke down after Sprint 4.3: all later work (Bidding Controls, Table Controls, Trick Resolution/Round Completion, Round Lifecycle, Match Completion, Rematch Vote, Player Hand Synchronization, and the recent Firestore rules hardening sprints "D"/"E") was done as named, un-numbered sprints, several of which were **never individually committed to git** and were reconstructed after a container crash wiped the uncommitted working tree (`docs/reviews/Working_Tree_Recovery_Report.md`).

Based on the current repository evidence, the project is at the end of a **Multiplayer Core Loop Hardening** phase for the selected `design-ui/` product (hand-dealing security is present in the recent history). The product-direction question is resolved. The urgent next phase is **Primary-Product Integration and Stabilization**: connect `design-ui/` to the build/deployment pipeline, close the known fast-round and lifecycle gaps, establish reproducible validation, and complete the user-facing core loop. `src/` remains transitional and is not a destination for multiplayer feature work.

---

## 3. What We Have Completed (evidence-backed)

- **Legacy Estemshan score tracker** (`src/`): setup, round entry, scoreboard, game-over, and a separate Classic-mode scoring utility. Retained for transition/maintenance; not the multiplayer production target.
- **Firebase Authentication** (`design-ui/login/`): real `createUserWithEmailAndPassword`, `signInWithEmailAndPassword`, `signInWithPopup` (Google), `signInAnonymously`, forgot-password flow, friendly error mapping. Genuinely wired, not mock.
- **Room lifecycle** (`design-ui/room-service.js`): transactional create/join/leave/setReady, capped at 4 players, idempotent.
- **Match start / lifecycle transitions** (`design-ui/match-service.js`): atomic room↔match binding, idempotent round advance, match completion, rematch vote + rematch match creation — all transactional with server-side cross-checks in `firestore.rules`.
- **Real-time match sync**: single ref-counted Firestore listener per match, classified retry/backoff, version-gated ordering guard — assessed as production-quality by the multiplayer audit.
- **Bidding & card-play sync**: transactional writes, seat-ownership + version-increment enforced server-side, pre-write local legality checks.
- **Trick resolution sync**: correctly designed as deterministic replay (no new write path needed) rather than a trusted broadcast.
- **Hand dealing (Sprint E, just committed)**: atomic 4-hand deal transaction now succeeds against the real Firestore Rules Emulator; write authority correctly separated from read privacy; verified with 14/14 new emulator checks plus full regression (1,513/1,513 Node tests).
- **Core rules engine** (`design-ui/engine/*.js`): deck/dealer/bidding/table/scoring engines implement the large majority of the updated authoritative rules document correctly — auction rules, Confirmation Phase, Call Cap, 13-Rule/Risk, Sa'ayda ladder, Dash Call flat scoring, Normal Dash Caller/With bonus, and the clarified live With behavior — confirmed by direct code reading and focused regression tests.
- **Extensive engine-level automated tests**: ~700+ tests across deck/bidding/table/scoring genuinely exercise real, unmodified engine source files (not mocks) and independently re-derive expected results rather than trusting engine output.

---

## 4. What Is Partially Complete

- **Bidding UI**: engine and sync are solid; the actual on-screen controls are minimal/placeholder by the screen's own code comment.
- **Game Table screen**: sync engine underneath is heavily engineered; visual rendering is explicitly "a placeholder... no attempt at a real game table" per its own CSS comment.
- **Shared component adoption**: a real Toast/Modal/Input/Skeleton kit exists (`shared-ui.js`), but only 1 of 4 screens (Login) actually uses it; Lobby uses native `alert()`/`prompt()`; Match loads the CSS but never triggers it.
- **Reconnect handling**: the Firestore-listener layer (data catches up correctly) is mature; the presence/abandonment layer (does the game know an opponent left?) is 100% stub.
- **Match completion trust**: `endMatch()` checks internal self-consistency of `finalScores`/`winnerIds` but cannot independently verify they're the TRUE correct score — accepted, documented limitation.
- **Sa'ayda round-19-force rule**: implemented via the same code path as Super Call extension without being distinguished as its own rule — functionally correct for the one case the doc calls out, but conflates two distinct rule clauses in one implementation.

---

## 5. What Is Broken

- **Legacy `src/utils.ts` scoring formula diverges from the authoritative rules**: its Normal-mode Super Call and Dash Call/Normal Dash paths do not fully match the canonical formulas. This is a legacy maintenance defect and must not be allowed to become a second multiplayer scoring implementation. If `src/` is retained for users during transition, it needs an explicitly scoped compatibility/deprecation decision and independent tests.
- **Fast-round Caller/With assignment**: for any fast round (14-18) where no bid reaches 8 (i.e., no Super Call), the engine sets `callerId: null, withPlayers: []` unconditionally — meaning the Caller/With ±10 scoring bonus can never fire for the majority of fast rounds, contradicting the rules doc's explicit "first to bid the highest number is Caller" rule, which applies to every fast round, not just Super Calls.
- **Incomplete primary-product user flow**: the selected `design-ui/` product has dead navigation links and missing gameplay screens, including ranked/AI/settings/room/table/standings surfaces.
- **`cardLog` prefix-integrity gap**: a seated client can rewrite/reorder earlier card-log entries in a way that, as of the trick-resolution sprint, can actually change a computed trick winner — a demonstrated, not merely theoretical, exploit, documented as "not suitable for ranked/competitive play."
- **`firestore.rules` is explicitly marked "NOT YET DEPLOYED"** in its own file header — everything the multiplayer audit describes is the *reviewable* ruleset, with no evidence it has ever been published to the live Firebase project.

---

## 6. What Is Missing

- **Shop, Characters, Customization, Card Collection, Bundles, Battle Pass**: no files, no services, anywhere.
- **Missions and Season systems**: only static, hardcoded HTML mockups in the Lobby sidebar (`2/3`, `66%` baked directly into markup) — no backing service exists.
- **AI/bot opponents**: a boolean flag and an unused accessor exist; there is no bid-decision or card-play-decision logic anywhere for an AI-controlled seat, despite "Play vs AI" being advertised in the Lobby UI copy.
- **Ranked Match, Room (join-by-code), Settings, Final Standings screens**: referenced by the app's own routing table (`game-state.js`) and documentation (`SHARED_COMPONENTS.md`), but the files do not exist.
- **CI/CD**: no `.github/workflows` or any other CI config; no `test` script in `package.json`.
- **Presence/heartbeat/abandonment detection**: `presence-service.js` exists as a file of 100% stub functions.
- **A dedicated standings/winner engine**: winner logic lives thinly inside `ScoringEngine.computeWinner()`; no standalone module exists.
- **Any monetization, IAP, ads, or analytics implementation.**
- **Cloud Functions / trusted backend**: by design (Spark-plan constraint), not started.

---

## 7. Technical Debt

- **Two unconnected codebases sharing a repo and rough problem domain**, with no shared code, no shared tests, and no doc that treats them as one system.
- **SUITS/RANKS card-value tables duplicated three times** (`cards.js`, `bidding-engine.js`, `table-engine.js`) — currently consistent, a real drift risk.
- **Risk-player fallback formula triplicated** (bidding-engine.js, table-engine.js, scoring-engine.js) — same drift risk.
- **A THIRD, independently-maintained scoring implementation** (`src/utils.ts`) with no cross-reference to the canonical engine and no test coverage — actively diverging today.
- **Aspirational/stale documentation**: `SHARED_COMPONENTS.md` and `game-state.js`'s route table describe a Shop, Room, Ranked Match, Bidding Phase, Game Table, Final Standings, and Settings screen that were never built — creating dead links, not just missing docs.
- **CSS design tokens duplicated verbatim across all four HTML screens** rather than centralized — a WCAG contrast fix already had to be hand-applied four times.
- **Sprint-numbering discipline collapsed after 4.3**: a large stretch of named, individually-documented sprints exists only in `docs/reviews/`, with no matching commits, until all of it was reconstructed from a session transcript after a data-loss incident and dumped into one oversized, unrelated-sounding commit.
- **One sprint number (3.7) was deliberately reused** for two unrelated pieces of work.

---

## 8. Rules Compliance Audit

Canonical source: `Estimation_Rules_v2_SingleSourceOfTruth.docx` (found only in this session's upload area, **not committed to the repo** — engine comments cite it as if it were present, which it is not, a documentation/repo-hygiene gap in its own right).

| Rule area | Engine location | Compliant | Evidence |
|---|---|---|---|
| Suit/rank hierarchy | cards.js / bidding-engine.js / table-engine.js | ✅ | Matches Sans5>♠4>♥3>♦2>♣1 in all three (duplicated) tables |
| Dash Call (pre-bid, max 2, flat ±33/±25, no Risk) | bidding-engine.js, scoring-engine.js | ✅ | Exact breakpoints, explicit "never Risk" comment |
| Auction min-bid/raise/tie-break | bidding-engine.js | ✅ | Correct number-or-stronger-suit raise logic |
| Confirmation Phase | bidding-engine.js | ✅ | Correctly scoped to normal rounds only |
| With — Auction Alignment (suit-only) | bidding-engine.js | ✅ (fixed) | Suit-only match confirmed by direct read; **zero test coverage** |
| With — Estimation Jump-In | bidding-engine.js | ✅ | |
| Call Cap / 13-Rule / Risk Value table | bidding-engine.js, scoring-engine.js | ✅ | Exact breakpoints (0/10/20/30) |
| Fast-round forced trump cycle | bidding-engine.js | ✅ | Correct modulo cycling for extension rounds |
| **Fast-round Caller/With for non-Super-Call rounds** | bidding-engine.js | ❌ **NEW BUG** | Never assigns Caller/With unless a Super Call (8+) occurs |
| Fast-round tie-break (first bidder wins) | bidding-engine.js | ✅ (as coded) | Correct logic, but **untested**, and only reachable via the Super-Call path per the bug above |
| Golden Super Call reset (pre-Super-Caller re-estimates) | bidding-engine.js | ❌ | Not implemented — no re-estimation step exists at all |
| Round extension (+1 on qualifying Super Call/Sa'ayda) | scoring-engine.js | ✅ | Correctly windowed to rounds 14-18 |
| Standard Win/Loss stacking bonuses | scoring-engine.js | ✅ | All four components stack independently |
| Normal Dash Caller/With ±10 bonus | scoring-engine.js | ✅ (previously fixed, re-verified) | Confirmed present at the exact branch; passing regression test exists |
| Sa'ayda escalation ladder | scoring-engine.js | ✅ | ×2→×4→×6→×8, resets on success |
| **`src/utils.ts` Normal-mode Super Call bonus** | src/utils.ts | ❌ **NEW BUG (live, shipped)** | Invents a ±20 bonus the doc doesn't specify for Normal mode |
| **`src/utils.ts` Dash Call formula** | src/utils.ts | ❌ **NEW BUG (live, shipped)** | Plain hit/miss instead of doc's flat ±33/±25 |

**Rules→Engine→UI→Tests discrepancy summary**: the `design-ui/engine` layer is well-aligned with the rules doc except for the two new findings above; the UI layer barely exercises the engine's more advanced mechanics (no bidding-controls test for Auction Alignment or Jump-In); the shipped `src/` app's independent scoring implementation is the least compliant and least tested of anything in the repo.

---

## 9. Architecture Assessment

- **Layering** (design-ui/ prototype): Engine (pure functions) → GameSession (state store) → MatchAdapter (seat/engine translation) → MatchService (Firestore I/O) → UI (HTML/vanilla JS). This layering is real and mostly respected — no obvious circular dependencies found.
- **Client-trusted game state**: deliberate, documented, and consistent — bid/card *value* legality, `cardLog` prefix integrity, `endMatch()` score correctness, and (now) hand-dealing content fairness are all explicitly accepted as client-authoritative under a Spark (free) Firebase plan constraint. This is coherent as a stated MVP trade-off, but it is a **security model with a genuine, demonstrated cheat surface** in at least the `cardLog` case.
- **Firestore rules deployment status is unknown/unverified** — the rules file itself says "NOT YET DEPLOYED."
- **Dead code / prototype-as-production risk**: `presence-service.js`, `shop-service.js`, `getAIPlayers()` are all stub/dead scaffolding sitting in the same directories as production-quality code, with no clear marker distinguishing "real" from "placeholder" modules at a glance.
- **The single largest architectural risk is now integration execution**: `design-ui/` is the selected product but is not yet the artifact produced by the root build/deployment pipeline. Continuing feature work without a concrete integration slice, environment packaging, and smoke test would leave the selected product unreachable to users.

---

## 10. UI/UX Assessment

| Screen | Status | Evidence |
|---|---|---|
| Estemshan Setup/Scoreboard/Game-over (shipped) | DONE (but scoring bug, §5) | `src/App.tsx` |
| Splash | MISSING | Referenced file doesn't exist |
| Login/Onboarding | DONE | Real Firebase Auth, real error handling |
| Lobby | PARTIAL | Real room/ready wiring; native alert()/prompt() UI; dead links to 3 non-existent screens |
| Ranked Match | MISSING | File doesn't exist |
| Play with Friends | PARTIAL/MOCK UI | Backend real, no dedicated screen |
| Play vs AI | MISSING (mock flag only) | No bot logic anywhere |
| Game Table | PARTIAL | Sync engine mature, visuals explicitly placeholder |
| Bidding UI | PARTIAL | Engine solid, controls minimal |
| Score/Standings (embedded) | DONE (narrow scope) | Real Firestore-sourced score rows; no dedicated screen |
| Shop | MISSING | Stub service, no screen |
| Characters/Customization/Collection/Bundles/Battle Pass | MISSING | Nothing exists |
| Currency display | DONE (display only); purchase MISSING | "+" buttons have no event listeners |
| Missions | MOCK | Hardcoded static markup |
| Season systems | MOCK | Hardcoded static markup |
| Settings | MISSING | Dead link |
| Room/join-by-code | MOCK/MISSING | Native `prompt()` only |
| Profile | DONE (narrow scope) | Real wiring; RP/Level honestly omitted rather than faked |

**Design system**: token values are consistent across screens but duplicated four times with no shared stylesheet. Shared component kit is real but adopted by only 1 of 4 screens.

---

## 11. Multiplayer Assessment

Production-quality: room lifecycle, match lifecycle transitions, real-time sync, reconnect (data layer), hand-deal write authority (post Sprint E). Documented, accepted, unresolved gaps: bid/card value legality (client-only), `cardLog` prefix integrity (demonstrated exploit), `endMatch()` score-correctness trust, hand-content-fairness (accepted MVP risk), presence/abandonment (not built), rules deployment status (unverified), and a previously-flagged, unresolved concurrent-transaction contention behavior in the emulator (separate from the Sprint E fix).

---

## 12. QA Assessment

~1,500+ Node tests pass, but **~90%+ are self-labeled MOCKED or SIMULATED** — real production code exercised against a hand-written fake Firestore, not real infrastructure. The one full JS reimplementation of the Firestore rules (`rules-simulation.test.js`, 278 checks) once passed 100% while the real rules contained a construct that doesn't compile on real Firestore — direct proof a green simulated suite doesn't guarantee the real thing works. Real-emulator tests (6 files) require manual emulator startup and report `SKIPPED` otherwise — no CI runs them ever. The one artifact that could substantiate "a full round verified across real independent browsers with real multiplayer" (`verify-sprint-b-multiclient.cjs`) is not part of the committed test suite, has no logged results, and was not re-executed in this audit. **There is no CI/CD of any kind.**

---

## 13. Feature Completion Matrix

| Feature | Status | Completion | Evidence | Blocker | Priority |
|---|---|---|---|---|---|
| Legacy score tracker | TRANSITIONAL / UNDER-TESTED | 80% | `src/App.tsx` | Legacy scoring divergence; no multiplayer scope | P2 |
| Firebase Auth | DONE | 90% | `design-ui/login/` | Not connected to shipped app | P1 |
| Room lifecycle | DONE | 85% | `room-service.js` | No live subscribe | P2 |
| Match lifecycle | DONE | 85% | `match-service.js` | Rules undeployed | P1 |
| Bidding/card/trick sync | DONE (core loop) | 80% | `match-service.js`/`match-adapter.js` | cardLog integrity gap | P1 |
| Hand dealing | DONE (MVP-secured) | 80% | Sprint E, firestore.rules | Content-rigging accepted risk | P2 |
| Core rules engine | MOSTLY DONE | 80% | engine/*.js | 2 new bugs found (§5/§8) | P0 |
| Game Table UI | PARTIAL | 30% | match/index.html | Explicitly placeholder | P1 |
| Bidding UI | PARTIAL | 40% | match/index.html | Minimal controls | P2 |
| AI opponents | MISSING | 2% | flag only | No logic exists | P2 |
| Shop/Economy | MISSING | 3% | stub service | Not started | P3 |
| Missions/Season | MOCK | 5% | static markup | Not started | P4 |
| Presence/reconnect (opponent-left) | MISSING | 0% | stub file | Not started | P1 |
| CI/CD | MISSING | 0% | none found | Not started | P1 |
| Analytics/Monetization | MISSING | 0% | none found | Not started | P4 |
| `design-ui` production integration | NOT IMPLEMENTED | 0% | `package.json`, root Vite wiring, `design-ui/` screens | Build/deployment packaging and end-to-end smoke path | **P0** |

---

## 14. Risk Register

| ID | Severity | Probability | Impact | System | Evidence | Mitigation | Priority |
|---|---|---|---|---|---|---|---|
| R1 | 🔴 Critical | Certain | Product has no shippable multiplayer game today | Architecture | Two disconnected codebases | Decide: integrate `design-ui/` into `src/`, or formally re-scope the product | P0 |
| R2 | 🔴 Critical | Certain | Live shipped app produces wrong scores | src/utils.ts | Diverges from rules doc, untested | Fix formula, add tests | P0 |
| R3 | 🟠 High | Certain | Fast-round scoring bonus never applies in most fast rounds | bidding-engine.js | Confirmed by direct code read | Fix Caller/With assignment for non-Super-Call fast rounds | P0 |
| R4 | 🟠 High | Certain | Every "tested" claim needs re-reading for MOCKED vs REAL | QA process | ~90% of tests are simulated | Stand up CI running real-emulator tier; log Playwright multiclient results | P1 |
| R5 | 🟠 High | Certain | Firestore rules may not be live | Security | File header says "NOT YET DEPLOYED" | Verify/deploy; confirm against live project | P1 |
| R6 | 🟠 High | High | Player can manipulate trick outcomes | cardLog integrity | Demonstrated exploit, documented | Restructure cardLog as append-only subcollection, or accept for MVP explicitly | P1 |
| R7 | 🟡 Medium | Certain | Opponent disconnect is invisible to other players | Presence | 100% stub | Build minimal heartbeat/timeout | P1 |
| R8 | 🟡 Medium | Certain | UI has dead links in its only working flow | UI | Lobby → 3 nonexistent files | Remove dead buttons or build the screens | P2 |
| R9 | 🟡 Medium | Certain | Hand content can be rigged by any match member | Hand deal | Explicitly accepted MVP risk | Documented; revisit if going to paid infra | P3 |
| R10 | 🟢 Low | Certain | Card-value tables triplicated | Engine | 3 copies | Consolidate into one shared module | P3 |
| R11 | 🟢 Low | Certain | No documentation reconciling shipped vs prototype | Docs | 29 sprint reports, 0 mention the split | Add an honest top-level README | P2 |

---

## 15. Dependency Map

```
P0-01 (product direction) ─────────────→ RESOLVED by PD-001; all future product work targets design-ui/
P0-02 (canonical rules artifact) ──────→ rule matrix and every game change
P0-03 (design-ui fast-round roles) ─────→ scoring, lifecycle, extension, and UI state
P1-01 (build/test/CI) ──────────────────→ every subsequent release claim
P1-02 (Rules Emulator/deployment) ─────→ real multiplayer testing and release
P1-03 (state/invariant contract) ───────→ cross-engine and reconnect work
P1-04 (trust-boundary decision) ────────→ ranked/public claims and schema hardening
P1-05 (presence/rejoin) ─────────────────→ non-deadlocking multiplayer
P1-06 (lifecycle integration) ──────────→ UI wiring and release completion
P2-01 (design-ui build packaging) ──────→ selected artifact can ship
P2-02 (design-ui gameplay UI) ──────────→ human-playable core loop
P2-03 (design-ui sync wiring) ──────────→ real multiplayer screen
P3 (AI/meta) ───────────────────────────→ only after core release gate
```

**Critical path**: P0-02 → P0-03/P0-04 → P1-01/P1-03 → P1-02/P1-04 → P1-06 → P2-01 → P2-02/P2-03 → P1-05 and trust hardening → release QA.

**Parallelizable now**: canonical rules artifact, build/test/CI bootstrap, fast-round investigation, state-contract documentation, and Rules Emulator setup can proceed in parallel. Legacy `src/` maintenance is separately scoped and must not block `design-ui/`.

**Should NOT proceed yet**: Shop, Missions, Battle Pass, monetization, or a broad AI feature. They remain gated on a playable, validated `design-ui/` core and the selected product’s release gate.

---

## 16. Critical Path

P0-02/P0-03/P0-04 → P1-01/P1-03 → P1-02/P1-04 → P1-06 → P2-01 (`design-ui` build/deployment) → P2-02/P2-03 (UI and sync) → P1-05/trust hardening → release QA. P0-01 is resolved and is no longer on the critical path.

---

## 17. New Master Roadmap

**PHASE 0 — Authority & Stabilization (P0)**
- Objective: Operationalize the authoritative rules and stabilize the selected `design-ui/` product.
- Tasks: commit the rules artifact and matrix; fix fast-round Caller/With behavior; keep legacy `src/` scoring maintenance explicitly separate; preserve the completed With, DASH, auction-invariant, and retry regressions.
- Acceptance criteria: PD-001 is recorded; all future multiplayer tasks name `design-ui/`; known P0 engine defects have failing-before-fix regressions; the canonical rule matrix is committed.
- Effort: **S–M**.
- DoD: authority and product direction are documented; `design-ui/` engine fixes are merged with tests; no new multiplayer code is added to `src/`.

**PHASE 1 — Rules/Engine Hardening**
- Objective: Close remaining engine gaps (Golden Super Call reset, SUITS/RANKS dedup, Risk-player formula dedup); commit the rules docx into the repo as the actual source of truth.
- Effort: **S–M**.

**PHASE 2 — Real Verification Infrastructure**
- Objective: Stand up CI that actually runs the real-emulator tier and (ideally) the Playwright multiclient harness on a schedule, not just on-demand.
- Tasks: `.github/workflows` (or equivalent) running `firebase emulators:exec` against the full test suite; commit results artifacts.
- Effort: **M**.
- DoD: every PR gets a real pass/fail signal, not just a MOCKED one.

**PHASE 3 — Core Gameplay UI Completion**
- Objective: Build the actual `design-ui/` Game Table visuals and Bidding controls for the selected production product.
- Effort: **L**.

**PHASE 4 — Multiplayer Trust Hardening**
- Objective: Close or explicitly re-accept `design-ui/` cardLog integrity, presence/abandonment, hand-content-fairness, turn-authority, and score-trust gaps.
- Effort: **L** (presence is straightforward; cardLog restructuring and any move toward trusted dealing are bigger).

**PHASE 5 — AI**
- Objective: Build actual `design-ui/` bot decision logic for bid/card play, or remove/gate "Play vs AI" until it exists.
- Effort: **L**.

**PHASE 6 — Meta Game (Shop/Economy/Missions/Battle Pass)**
- Objective: Only after Phases 0-4. Currently 0% built.
- Effort: **XL**.

**PHASE 7 — Social**
- Objective: Complete the `design-ui/` room/friends/join-by-code experience and remove placeholder `prompt()` flows.
- Effort: **M**.

**PHASE 8 — Analytics/Monetization**
- Effort: **L**, and gated on the Blaze migration this project has explicitly deferred.

**PHASE 9 — QA/Hardening & Release**
- Objective: Full regression, security review, real Firebase project deployment verification.
- Effort: **M**.

**PHASE 10 — Release**

---

## 18. Sprint-by-Sprint Execution Plan (near-term only — beyond this, re-plan after Phase 0's decision)

1. **Sprint N (P0)**: Fix `src/utils.ts` scoring + tests; fix fast-round Caller/With + tests; write the missing Auction Alignment/Jump-In regression tests.
2. **Sprint N+1 (P0/P1)**: Product-direction decision + honest top-level README documenting the split; verify/deploy firestore.rules to a real Firebase project.
3. **Sprint N+2 (P1)**: Stand up CI running the real-emulator tier.
4. **Sprint N+3 (P1)**: Presence/abandonment minimal implementation.
5. **Sprint N+4+**: Re-plan based on Phase 0's product decision.

---

## 19. Estimated Remaining Effort

Assumptions: one full-time-equivalent engineer, "hours" are rough order-of-magnitude, not committed estimates.

| Category | Effort size | Hours (rough) |
|---|---|---|
| Engineering — bug fixes (Phase 0/1) | S–M | 20–40 |
| Engineering — real CI (Phase 2) | M | 20–30 |
| Engineering — Core Gameplay UI (Phase 3) | L | 120–200 |
| Engineering — Trust hardening (Phase 4) | L | 80–150 |
| Engineering — AI (Phase 5) | L | 100–160 |
| Engineering — Meta game (Phase 6) | XL | 300–500+ |
| QA effort (real verification across all phases) | L | 100–160 |
| Backend effort (Blaze migration, if pursued) | L | 80–150 |
| Release effort | M | 40–60 |

**Timeline estimates** (assuming Phases 0-4 only, i.e. a genuinely playable, honestly-secured core multiplayer game — NOT the full meta-game vision):
- Optimistic: 6-8 weeks (1 engineer, no surprises)
- Realistic: 12-16 weeks
- Conservative: 20-26 weeks (accounting for the project's own demonstrated pattern of discovering new gaps mid-sprint)

Full scope (through Phase 10) remains materially larger, but it is now estimable as a `design-ui` production-integration program; the remaining uncertainty is implementation and infrastructure scope rather than product direction.

---

## 20. Estimated Production Readiness

**Implementation completeness (weighted, using the requested categories):**

| Category | Weight | Score | Weighted |
|---|---|---|---|
| Core Game Engine | 25% | 75/100 | 18.75 |
| Multiplayer/Backend | 20% | 65/100 | 13.00 |
| UI/UX | 15% | 30/100 | 4.50 |
| AI | 10% | 5/100 | 0.50 |
| Progression/Economy | 10% | 5/100 | 0.50 |
| Shop/Cosmetics | 5% | 0/100 | 0.00 |
| Authentication/Account | 5% | 85/100 | 4.25 |
| QA/Testing | 5% | 40/100 | 2.00 |
| Production/Deployment/Analytics | 5% | 5/100 | 0.25 |
| **Total** | 100% | | **≈44%** |

**This 44% describes the selected `design-ui/` product's internal completeness against its current ambitions.** It still does not mean that the selected product is shipped: the root build currently produces the transitional `src/` tracker, while `design-ui/` needs explicit build/deployment packaging and a complete user-facing core loop.

**Reconciled overall completion estimate: ~35–40%** of a released Estimation multiplayer game after resolving product direction, with the remaining gap concentrated in build integration, UI completion, real infrastructure verification, trust hardening, presence, and release QA.

**Production readiness: ~20–25%.** The score remains low because build/lint are not reproducible in the current workspace, Rules deployment is unverified, presence is incomplete, card-log/turn/hand/score trust gaps remain, the live gameplay UI is incomplete, and no complete release-grade multi-client run is logged. The score is higher than the prior assessment only because the product-direction ambiguity is now resolved; it is not a claim that the product is ready.

---

## 21. Definition of Done

A feature is **not** done because the UI exists, the function compiles, or one happy-path test passes. For this project, DONE requires:

- **Core gameplay rules**: implementation + verified against the canonical rules doc (committed into the repo) + edge cases (Sa'ayda, fast rounds, Risk, forbidden-13) + automated tests that independently re-derive expected results + integration with the real UI + regression coverage that survives future refactors.
- **Multiplayer features**: the above, plus real Firestore Rules Emulator verification (not simulated), plus a demonstrated real-browser multi-client test with logged, reproducible results, plus an explicit, written trust-boundary statement (what's server-enforced vs. client-trusted, and why that's acceptable).
- **UI screens**: real service wiring (not static/mock data), reachable via real navigation (no dead links), consistent with the shared design-token/component system, accessible (contrast, etc.).
- **Everything**: a decision about whether it belongs to the shipped app or the prototype, documented, not left ambiguous.

---

## 22. Immediate Next Actions After PD-001

### NEXT 5 ACTIONS

1. **Commit the updated authoritative rules artifact and matrix** and update all active planning references to PD-001.
2. **Fix the confirmed fast-round Caller/With defect in `design-ui/`** with failing-before-fix and cross-engine regression coverage.
3. **Make `design-ui/` the actual build/deployment target**: define entry points, asset/Firebase configuration, packaging, and a clean-checkout smoke test; leave `src/` intact as transitional code.
4. **Stand up CI and real Rules Emulator validation** so the selected product’s build, contracts, security rules, and browser smoke checks are reproducible.
5. **Complete the `design-ui/` core loop**: Bidding/Table render layer, sync registration, final-trick scoring, round transition, match completion, rematch, and recovery behavior.

---

## FINAL EXECUTIVE QUESTIONS

**WHERE ARE WE?**
Mid-build on the selected `design-ui/` multiplayer product. Product direction is resolved; the remaining gap is turning this existing engine/service investment into the artifact that the build and deployment pipeline ships, then completing the user-facing core loop and release validation.

**WHAT HAVE WE ACTUALLY BUILT?**
A transitional manual score tracker under `src/`; a mostly rules-compliant `design-ui/` card-game engine with focused regression coverage; a substantial but not fully verified Firebase multiplayer backend; incomplete/placeholder gameplay screens; zero complete AI, economy, presence/abandonment flow, or reproducible CI release pipeline.

**WHAT IS THE TRUE COMPLETION %?**
~44% against the selected `design-ui/` product's current internal scope; **~35-40%** against an actual released multiplayer game, with the remaining gap concentrated in build/deployment integration, UI completion, infrastructure verification, trust hardening, presence, and release QA.

**WHAT IS THE TRUE PRODUCTION READINESS %?**
**~20–25%.** The selected product still lacks reproducible build/lint/CI, verified Rules deployment, complete presence handling, trust hardening, a complete user-facing core loop, and release-grade multi-client evidence. The score is higher than the prior assessment only because product direction is now resolved; it is not a readiness claim.

**WHAT IS THE BIGGEST CURRENT RISK?**
That `design-ui/` is now correctly selected but remains disconnected from the build/deployment artifact. Every additional feature should therefore include an integration path, a selected-product test, and a release-surface check rather than accumulating more unreachable prototype functionality.

**WHAT IS BLOCKING US?**
The product decision is no longer blocking us. The P0 blockers are build/deployment integration for `design-ui/`, canonical rules artifact hygiene, fast-round role correctness, reproducible validation, and the remaining UI/trust/lifecycle work.

**WHAT SHOULD WE DO NEXT?**
Commit the authoritative rules and PD-001 decision, fix the fast-round role defect in `design-ui/`, make `design-ui/` buildable and deployable, and then complete the core multiplayer loop with real emulator/browser evidence.

**HOW MUCH WORK IS LEFT?**
For a genuinely playable, honestly-secured `design-ui/` core multiplayer game (through the current core phases): realistically 12–16 weeks for one engineer, subject to the build/deployment and trust-boundary decisions. The full meta-game vision (economy, AI, social, monetization) remains materially larger, but can now be estimated as a separate post-core program.

**WHAT IS THE MOST EFFICIENT PATH TO RELEASE?**
Preserve the selected `design-ui/` architecture → commit and test the authoritative rules → fix fast-round roles → make the selected artifact build/deploy reproducibly → verify Rules Emulator and multi-client behavior → complete Bidding/Table UI and lifecycle wiring → harden trust/presence/recovery → release QA. Keep `src/` limited to explicit transition/deprecation work and defer AI/economy/social until the core multiplayer product passes its release gate.
