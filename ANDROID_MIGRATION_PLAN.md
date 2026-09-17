# Android (Kotlin) Migration Plan

**Decision:** the product ships as a native Android game (Kotlin), not a
web/WebView app. Owner decision 2026-09-17 — driven by Google Play
publishing and monetization (ads + in-app purchases).
**Status:** planning — no Kotlin code exists yet; all paths below are
relative to repo root. Web code is frozen as reference except critical fixes.

---

## 1. Current problems (verified on main @ `758ecb5`)

| # | Problem | State | Migrates? |
|---|---|---|---|
| P1 | Legacy `src/` Normal scoring diverged from canonical | FIXED per owner (bid² Super win/loss — `src/utils.ts:35-45`; flat Dash table — `:53-71`; flat sole ±10 — `:82-89`; single Caller/With bonus — `:56-61`). Pinned by `tests/src-super-call-normal.test.cjs`, `tests/src-dash-normal.test.cjs` | Spec only — re-implement, do not copy |
| P2 | `design-ui/` multiplayer never ships (root build serves `src/`) | MOOT — replaced by native build; do not fix on web | No (problem disappears) |
| P3 | Client-trust gaps: `cardLog` prefix rewrite, wrong-but-consistent scores, riggable hands (accepted MVP limits) | OPEN — rules are client-agnostic, same gaps face Kotlin | Yes — same limits, re-accept explicitly |
| P4 | Presence/abandonment is 100% stub (`presence-service.js`) | OPEN | Yes — build native from day one (opportunity) |
| P5 | Game Table / Bidding UI are placeholders | MOOT — replaced by native UI | No |
| P6 | CI is JS-only (`test.yml`, `r1-golden-verification.yml`) | Must rebuild for Gradle | No — new pipeline |
| P7 | Play may reject a bare website-wrapper; billing needs a native bridge | SOLVED by going native (keep as a listed win) | — |
| P8 | Risk bonus in `src/` Normal is flat ±10; canonical risk table is 0/10/20/30 | OPEN QUESTION — never owner-confirmed | Decide (D2), then implement |
| P9 | Capacitor shell + live-URL loading (`capacitor.config.ts:8-14`) | DROPPED with the migration | No |

## 2. What transfers as-is (no rewrite)

- **Firebase project**: Auth, Firestore, (later) FCM. Android SDKs talk to the same project.
- **`firestore.rules` (1953 lines)**: enforced server-side for ANY client. The Kotlin client must replicate the EXACT transaction shapes in `design-ui/match-service.js` (archive+advance atomic write `:1672-1673`, dealer rotation `:1648`, window resets `:1668-1669`, version+1) or the rules deny — this is the #1 port risk.
- **Data model**: rooms/matches/hands/archives/rematch-vote shapes.
- **`docs/rules/CANONICAL_RULES.md` + Amendment A1**: rules + legacy scoring spec. D1/D2 below may amend it again — by dated amendment only, never silent rewrite.
- **Scoring spec**: `src/utils.ts` + its two test files are the executable spec for Normal/Classic math (including bid²). Port the numbers, not the TypeScript.
- **Monetization accounts**: Play Console, AdMob (native SDKs are easier than any bridge).

## 3. What gets rewritten in Kotlin

- **Engine** (pure Kotlin module, zero Android deps): cards/deck/dealer (`design-ui/engine/cards.js`, `deck.js`, `dealer.js`), bidding (`bidding-engine.js`, 997 lines — incl. fast-round Caller/With `:637-658` and Super-Call reset `:468-505`), table (`table-engine.js`), scoring (`scoring-engine.js` + bid²/dash/sole rules from `src/`), session (`session.js`).
- **Services**: room/match lifecycle, sync listeners, transactions, extensions, rematch — same shapes, Firebase Android SDK.
- **UI**: login, lobby, room, bidding, table, standings — native (Compose or Views, decided in Phase 0).
- **Tests**: the 49-file JS suite does NOT transfer. Every pinned behavior (A1/A2 with-grant, S1-S3 reset, dash tables, bid² tables, emulator matrices) is re-expressed as JUnit + Firebase Emulator Suite integration tests.
- **CI**: Gradle workflow (unit + emulator tiers); JS workflows stay for the frozen web reference.

## 4. What gets dropped / frozen

- `android/` Capacitor shell, `capacitor.config.ts`, `apk-debug.yml` (replaced by Gradle signed builds + Play tracks).
- Web feature work stops at migration start. Web hosting stays up only as long as the Play listing / privacy-policy page needs a URL — decided in Phase 5.
- `src/` localStorage scores are device-local; a native fresh install starts at zero. No data migration (confirm in Phase 0 if any cohort needs export).

## 5. Phases (one engineer; S <2wk, M 2-4wk, L 1-2mo)

**Phase 0 — Spec freeze & extraction (S).**
Inventory every screen, engine API, and transaction shape; resolve D1–D4.
DoD: a Kotlin dev builds Phases 1+ without reading JS.
Owner homework in parallel: Play Console account ($25), AdMob account
(activation + 14-day closed-testing clock start on Google's side).

**Phase 1 — Android skeleton + Firebase (M).**
Project, Auth login, Firestore wiring incl. Emulator Suite flavor,
navigation shell, DI. DoD: login + read/write vs emulator, green CI.

**Phase 2 — Pure engine port (L).**
Cards/deck/dealer/bidding/table/scoring/session in Kotlin with a JUnit
suite mirroring every JS-pinned case (fast-round Caller/With + reset,
dash tables, bid² win/loss/sole, Classic untouched).
DoD: engine module has zero Android imports; all ported cases green.

**Phase 3 — Services vs emulator (L).**
Room/match/transactions/archive/extension/rematch; scripted 4-client
deal→rematch run on the emulator (the golden-path equivalent).
DoD: scripted full match green; a wrong transaction shape is denied
by the UNCHANGED `firestore.rules` (proves fidelity).

**Phase 4 — Native UI + presence (L).**
Lobby/room/bidding/table/standings + heartbeat/timeout abandonment.
DoD: 4-player playable match on emulator builds.

**Phase 5 — Play readiness + monetization (M).**
Signing, listing, privacy policy, content rating, data-safety form,
closed track; AdMob (banner + rewarded) with EU consent; Play Billing
(RevenueCat recommended — no server of our own on the free plan — or
direct Billing Library, D3). DoD: closed-track release with working
ads + test purchases.

**Phase 6 — Rollout & web decommission.**
Staged rollout, crash/ANR watch, then decide web hosting's fate
(policy page minimum). DoD: Play production + written web-EOL note.

**Rough total: ~5–8 months, one engineer.** AI opponents, shop,
missions, seasons stay deferred until a native core release exists —
same gate as before, new platform.

## 6. Risks

- **R1 — Transaction-shape fidelity.** Any field deviation → rules deny.
  Mitigation: Phase 3 emulator matrix is a release gate, not advisory.
- **R2 — Two codebases during transition.** Web is reference-only;
  critical web fixes need explicit approval or they fork the spec.
- **R3 — Spec gaps.** Anything the JS suite never pinned (e.g. P8
  risk table, Sa'ayda ladder in `src/`) must be owner-decided (D2),
  never guessed by the porter.
- **R4 — Scope creep.** Economy/AI/social stay behind the native core
  release gate.

## 7. Owner decisions needed (D1–D4, before Phase 2 code)

- **D1 — Native scoring:** carry bid² + dash tables + flat sole ±10 (recommended: yes, already decided for Normal; Classic unchanged)?
- **D2 — Risk table:** flat ±10 (current `src/`) or canonical 0/10/20/30 ladder?
- **D3 — Billing verification:** RevenueCard-style service (recommended, no server) vs direct Billing Library + own verification (needs paid plan)?
- **D4 — Web fate:** kill hosting after Play launch, or keep a minimal site (listing + privacy)?

## 8. Immediate next action

Answer D1–D4 (D1+D2 unblock engine work; D3/D4 can wait until Phase 5),
then kick off Phase 0. Nothing above starts without the four answers.
