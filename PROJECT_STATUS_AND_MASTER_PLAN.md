# PROJECT_STATUS_AND_MASTER_PLAN.md
### Estimation Card Game — Forensic Audit & Master Plan
Audit date: 2026-08-12 · Method: direct repository inspection (file reads, test execution, git history), no reliance on prior sprint reports' self-assessment. Five independent research passes (game engine, multiplayer/backend, UI/UX, roadmap reconciliation, test/QA rigor) were cross-referenced to produce this document. No code was modified during this audit.

---

## 1. Executive Summary

This repository contains **two disconnected products**, not one game:

1. **The shipped app** (`src/`, built by `npm run build`, the only thing `index.html`/`vite.config.ts` actually wires up): **Estemshan**, a manual pen-and-paper score-tracking tool. Setup screen → round-entry form → running scoreboard → game-over screen. No backend, no auth, no multiplayer, no lobby. This is what a user gets today if they build and open this project.
2. **A much larger, unshipped prototype** (`design-ui/`): a real-time multiplayer Firestore-backed card game with a genuine rules engine, real Firebase Auth, a heavily-engineered synchronization/security layer, and four static HTML screens (Login, Lobby, Match, Profile). It is **not referenced by the build**, has **no npm dependency on Firebase**, and can only be opened by loading its HTML files directly in a browser.

Nearly all of the ~29 sprint reports, ~23 architecture/implementation docs, and ~1,500 passing tests in this repo describe work on product #2 — the one that isn't shipped. Product #1 — the one that is shipped — has almost no documentation, no automated tests, and (per the engine audit) contains its own separately-maintained scoring formula that **diverges from the project's own rules document** in ways that produce wrong scores today, for real users of the tool that actually ships.

Within product #2, the engineering on synchronization, security rules, and round-lifecycle correctness is genuinely disciplined and mostly rules-compliant. But roughly half of the screens a player would need to reach (Shop, Settings, Ranked Match, Play vs AI, Room, Final Standings) **do not exist** — the one working screen (Lobby) contains dead links to files that were never built. AI/bot opponents are advertised in the UI copy but have zero implementation anywhere. ~90%+ of "passing tests" are self-labeled MOCKED/SIMULATED (verified directly, not assumed) rather than real-infrastructure verification, and there is no CI/CD at all — every test must be run manually.

**The honest bottom line:** this project has done a large amount of careful, well-documented backend engineering on a game that isn't wired to anything a user can play end-to-end, while the tool that IS shipped has an unverified, incorrect scoring bug and no test coverage. Both halves need real work before either constitutes a releasable product.

---

## 2. Current Project Phase

**There is no single "current sprint."** The dotted-version sprint discipline (2.6→4.3) that governed `design-ui/`'s early build-out broke down after Sprint 4.3: all later work (Bidding Controls, Table Controls, Trick Resolution/Round Completion, Round Lifecycle, Match Completion, Rematch Vote, Player Hand Synchronization, and the recent Firestore rules hardening sprints "D"/"E") was done as named, un-numbered sprints, several of which were **never individually committed to git** and were reconstructed after a container crash wiped the uncommitted working tree (`docs/reviews/Working_Tree_Recovery_Report.md`).

Based on evidence, not on what any prior report claims: **the project is currently at the end of a "Multiplayer Core Loop Hardening" phase for the `design-ui/` prototype** (hand-dealing security, just committed as `77369bf`), with the two most urgent open items being (a) the newly-discovered fast-round scoring bug and (b) the fact that this entire prototype is not connected to the app that actually ships. Any claim that the project is "ready for Sprint N+1 of feature work" is not supported by the evidence — the correct next phase is **Stabilization**, not new features.

---

## 3. What We Have Completed (evidence-backed)

- **Shipped Estemshan score tracker** (`src/`): setup, round entry, scoreboard, game-over, Classic-mode scoring formula. Builds and runs.
- **Firebase Authentication** (`design-ui/login/`): real `createUserWithEmailAndPassword`, `signInWithEmailAndPassword`, `signInWithPopup` (Google), `signInAnonymously`, forgot-password flow, friendly error mapping. Genuinely wired, not mock.
- **Room lifecycle** (`design-ui/room-service.js`): transactional create/join/leave/setReady, capped at 4 players, idempotent.
- **Match start / lifecycle transitions** (`design-ui/match-service.js`): atomic room↔match binding, idempotent round advance, match completion, rematch vote + rematch match creation — all transactional with server-side cross-checks in `firestore.rules`.
- **Real-time match sync**: single ref-counted Firestore listener per match, classified retry/backoff, version-gated ordering guard — assessed as production-quality by the multiplayer audit.
- **Bidding & card-play sync**: transactional writes, seat-ownership + version-increment enforced server-side, pre-write local legality checks.
- **Trick resolution sync**: correctly designed as deterministic replay (no new write path needed) rather than a trusted broadcast.
- **Hand dealing (Sprint E, just committed)**: atomic 4-hand deal transaction now succeeds against the real Firestore Rules Emulator; write authority correctly separated from read privacy; verified with 14/14 new emulator checks plus full regression (1,513/1,513 Node tests).
- **Core rules engine** (`design-ui/engine/*.js`): deck/dealer/bidding/table/scoring engines implement the large majority of `Estimation_Rules_v2_SingleSourceOfTruth.docx` correctly — auction rules, Confirmation Phase, Call Cap, 13-Rule/Risk, Sa'ayda ladder, Dash Call flat scoring, Normal Dash Caller/With bonus (previously-fixed bug, re-verified present) — confirmed by direct code reading against the doc, not by trusting prior reports.
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

- **`src/utils.ts` scoring formula (used by the SHIPPED app) diverges from the rules doc**: its Normal-mode Super Call bonus (fixed ±20, which the doc doesn't specify for Normal mode at all) and its Dash Call formula (plain hit/miss instead of the doc's flat ±33/±25) are both wrong relative to the single source of truth — and this file has **zero test coverage**. This is a live bug in the one thing that actually ships.
- **Fast-round Caller/With assignment**: for any fast round (14-18) where no bid reaches 8 (i.e., no Super Call), the engine sets `callerId: null, withPlayers: []` unconditionally — meaning the Caller/With ±10 scoring bonus can never fire for the majority of fast rounds, contradicting the rules doc's explicit "first to bid the highest number is Caller" rule, which applies to every fast round, not just Super Calls.
- **Dead navigation links in the one working prototype screen**: Lobby's "Ranked Match," "Play vs AI," and Settings (gear icon) buttons all navigate to HTML files that don't exist anywhere in the repo or git history.
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
- **The single largest architectural risk**: the entire `design-ui/` investment (services, engine, rules, ~30 docs) has zero integration path into the actually-shipped `src/` build. Continuing to invest in `design-ui/` without resolving this is building a second product, not finishing the first.

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
| Shipped score tracker | DONE w/ bug | 80% | `src/App.tsx` | Scoring bug (§5) | P0 |
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
| App↔prototype integration | MISSING | 0% | two disconnected codebases | Architectural decision needed | **P0** |

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
R1 (Architecture decision: integrate or re-scope)
 ├─→ everything about design-ui/ becoming "the product"
 └─→ if re-scoped: most design-ui/ work becomes reference material, not roadmap

R2/R3 (Engine bug fixes)  ─────────────→ independent of R1, do regardless
R5 (Rules deployment verification) ───→ blocks any real-user multiplayer testing
R4 (CI + real verification)        ───→ should happen before any further feature work,
                                        else every future sprint repeats the same
                                        MOCKED-only pattern
R6 (cardLog integrity) ─────┐
R7 (Presence)          ─────┼──→ both required before "ranked"/competitive framing is honest
R9 (Hand content)       ─────┘
R8 (Dead links / missing screens) ───→ cosmetic/functional, parallelizable with anything
```

**Critical path**: R1 (decide product direction) → R2/R3 (fix known scoring bugs, cheap and urgent regardless of R1's outcome) → R5 (confirm/deploy rules) → R4 (real CI) → R6/R7/R9 (trust-boundary hardening) → UI completion → economy/AI/meta systems.

**Parallelizable now, regardless of R1's outcome**: R2, R3 (bug fixes), R10 (dedup), R11 (docs).

**Should NOT proceed yet**: any new meta-game feature (Shop, Missions, Battle Pass, AI) until R1 is resolved — building more on top of a product that may not ship is the single biggest waste-of-effort risk in this repo today.

---

## 16. Critical Path

R1 → R2/R3 → R5 → R4 → (R6, R7, R9 in parallel) → UI completion for whichever product direction R1 selects → Economy/AI/Meta (only if in scope) → Release QA.

---

## 17. New Master Roadmap

**PHASE 0 — Stabilization & Decision (this must happen before anything else)**
- Objective: Resolve the two-codebases problem; fix the two confirmed scoring bugs.
- Tasks: Product-direction decision (R1); fix `src/utils.ts` scoring formula + add tests (R2); fix fast-round Caller/With (R3) + add regression tests for both this and the recently-fixed With mechanics (Auction Alignment, Jump-In) which currently have zero coverage.
- Acceptance criteria: one clear answer to "what ships"; both engines pass rules-compliance tests against the doc.
- Effort: **M** (bug fixes are small; the product decision is a stakeholder call, not an engineering task).
- DoD: fixes merged with tests; decision documented.

**PHASE 1 — Rules/Engine Hardening**
- Objective: Close remaining engine gaps (Golden Super Call reset, SUITS/RANKS dedup, Risk-player formula dedup); commit the rules docx into the repo as the actual source of truth.
- Effort: **S–M**.

**PHASE 2 — Real Verification Infrastructure**
- Objective: Stand up CI that actually runs the real-emulator tier and (ideally) the Playwright multiclient harness on a schedule, not just on-demand.
- Tasks: `.github/workflows` (or equivalent) running `firebase emulators:exec` against the full test suite; commit results artifacts.
- Effort: **M**.
- DoD: every PR gets a real pass/fail signal, not just a MOCKED one.

**PHASE 3 — Core Gameplay UI Completion**
- Objective: Build the actual Game Table visuals and Bidding controls (currently placeholder/minimal) for whichever product Phase 0 selected.
- Effort: **L**.

**PHASE 4 — Multiplayer Trust Hardening**
- Objective: Close or explicitly re-accept `cardLog` integrity, presence/abandonment, hand-content-fairness gaps.
- Effort: **L** (presence is straightforward; cardLog restructuring and any move toward trusted dealing are bigger).

**PHASE 5 — AI**
- Objective: Build actual bot decision logic for bid/card play, or remove "Play vs AI" from UI copy until it exists.
- Effort: **L**.

**PHASE 6 — Meta Game (Shop/Economy/Missions/Battle Pass)**
- Objective: Only after Phases 0-4. Currently 0% built.
- Effort: **XL**.

**PHASE 7 — Social**
- Objective: Room UI, friends, join-by-code screen (currently a `prompt()`).
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

Full scope (through Phase 10) is materially larger and not responsibly estimable without first resolving the Phase 0 product decision.

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

**This 44% describes the `design-ui/` prototype's own internal completeness against its own ambitions.** It does NOT account for the fact that this prototype isn't connected to anything shippable. Grading the actual product-as-it-would-ship today (the Estemshan score tracker, since that's the only thing `npm run build` produces) yields a much lower number — the shipped artifact doesn't have multiplayer, engine sophistication, or most of what's counted above at all.

**Reconciled overall completion estimate: ~20-25%** of "a released Estimation multiplayer game," once the integration gap is honestly priced in.

**Production readiness: ~15%.** Why lower than completeness: no CI, unverified/possibly-undeployed security rules, no presence/abandonment handling, a demonstrated cheat exploit, zero monetization/analytics, two confirmed live scoring bugs, and the fundamental fact that the most-engineered half of the codebase has no path to a user's hands today.

---

## 21. Definition of Done

A feature is **not** done because the UI exists, the function compiles, or one happy-path test passes. For this project, DONE requires:

- **Core gameplay rules**: implementation + verified against the canonical rules doc (committed into the repo) + edge cases (Sa'ayda, fast rounds, Risk, forbidden-13) + automated tests that independently re-derive expected results + integration with the real UI + regression coverage that survives future refactors.
- **Multiplayer features**: the above, plus real Firestore Rules Emulator verification (not simulated), plus a demonstrated real-browser multi-client test with logged, reproducible results, plus an explicit, written trust-boundary statement (what's server-enforced vs. client-trusted, and why that's acceptable).
- **UI screens**: real service wiring (not static/mock data), reachable via real navigation (no dead links), consistent with the shared design-token/component system, accessible (contrast, etc.).
- **Everything**: a decision about whether it belongs to the shipped app or the prototype, documented, not left ambiguous.

---

## 22. Immediate Next Actions

### NEXT 5 ACTIONS

1. **Fix the two confirmed live scoring bugs** — `src/utils.ts`'s Normal-mode Dash/Super-Call formulas (shipped, wrong today) and `bidding-engine.js`'s fast-round Caller/With assignment (affects most fast rounds) — with regression tests for each.
2. **Make an explicit product-direction decision**: integrate `design-ui/` into the shipped app, or formally re-scope the project's ambitions to match what's actually shippable. Document it in a top-level README. Every other roadmap item depends on this.
3. **Verify (or deploy) `firestore.rules` against a real Firebase project** — it is currently marked "NOT YET DEPLOYED" and nothing in the repo confirms otherwise.
4. **Stand up CI that runs the real-emulator test tier**, not just the mocked/simulated tier — so future "all tests pass" claims mean what they say.
5. **Write regression tests for the recently-fixed With mechanics (Auction Alignment, Estimation Jump-In)** and the fast-round tie-break — all three currently have zero automated coverage despite being real, previously-buggy code paths.

---

## FINAL EXECUTIVE QUESTIONS

**WHERE ARE WE?**
Mid-build on a multiplayer card game prototype (`design-ui/`) that has real engineering quality in its sync/security layer, but is disconnected from the app that actually ships (`src/`, a simple score tracker). No canonical roadmap exists; sprint discipline broke down after Sprint 4.3 and a data-loss incident forced a messy reconstruction.

**WHAT HAVE WE ACTUALLY BUILT?**
A working score-tracking tool (with a scoring bug); a mostly rules-compliant card-game engine (with one new, real bug); a well-engineered but not-fully-verified Firebase multiplayer backend; four static prototype screens, half of which link to features that don't exist; zero AI, zero economy/shop, zero CI.

**WHAT IS THE TRUE COMPLETION %?**
~44% against the prototype's own internal scope; **~20-25%** against an actual released multiplayer game, once the disconnect between the two codebases is priced in.

**WHAT IS THE TRUE PRODUCTION READINESS %?**
**~15%.** No CI, unverified rules deployment, no presence handling, a demonstrated cheat exploit, two live scoring bugs, zero monetization/analytics.

**WHAT IS THE BIGGEST CURRENT RISK?**
That continued investment goes into `design-ui/` — a genuinely well-built prototype — without ever resolving that it has no path to the app that ships. Every sprint that adds a new `design-ui/` feature without addressing this makes the eventual reconciliation more expensive.

**WHAT IS BLOCKING US?**
No formal decision has been made about which codebase is "the product." That decision blocks meaningful roadmap prioritization for everything else.

**WHAT SHOULD WE DO NEXT?**
Fix the two confirmed bugs (cheap, urgent, uncontroversial), then force the product-direction decision before authorizing any further feature sprints.

**HOW MUCH WORK IS LEFT?**
For a genuinely playable, honestly-secured core multiplayer game (Phases 0-4 only): realistically 12-16 weeks for one engineer. For the full meta-game vision (economy, AI, social, monetization): materially larger and not responsibly estimable until the product decision is made.

**WHAT IS THE MOST EFFICIENT PATH TO RELEASE?**
Fix the bugs → decide the product → verify what's actually deployed/tested for real (not simulated) → complete the core loop UI for whichever codebase is "the product" → only then invest in AI/economy/social. Building meta-game features on top of an unresolved architecture split, as several past sprints did, is the single most avoidable source of wasted effort in this project's history.
