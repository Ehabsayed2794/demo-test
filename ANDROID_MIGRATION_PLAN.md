# Android (Kotlin) Migration Plan

**Decision:** the product ships as a native Android game (Kotlin), not a
web/WebView app. Owner decision 2026-09-17 — driven by Google Play
publishing and monetization (ads + in-app purchases).
**Status:** in progress — Phase 0 specs, Phase 1 skeleton, and the pure
engines + first UI screens below all merged to `main` (see §9, updated
2026-09-19); all paths below are relative to repo root. Web code is
frozen as reference except critical fixes.

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
| P8 | Risk bonus in `src/` Normal is flat ±10; canonical risk table is 0/10/20/30 | DECIDED 2026-09-17 (D2): native uses the graduated ladder; `src/` keeps flat ±10 until the owner says otherwise | Native: ladder; `src/`: unchanged |
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
via RevenueCat free tier (D3 — no server, no paid plan).
DoD: closed-track release with working ads + test purchases.

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

## 7. Owner decisions (answered 2026-09-17 — Phase 0 unblocked)

- **D1 — Native scoring: DECIDED as-is.** Carry bid² + dash tables + flat sole ±10 (Classic unchanged) into the native game. Same numbers the owner confirmed for legacy.
- **D2 — Risk table: DECIDED graduated (native only).** Native Risk uses the canonical ladder — diff-from-13 of 1→0, 2–3→10, 4–5→20, 6+→30 — applied ONLY to the Risk player, stacking with Caller/With/sole exactly like §4. `src/` keeps its flat ±10 (no change requested).
- **D3 — Billing verification: DECIDED free tier.** RevenueCat (free tier, no server of our own). No paid plan required. Revisit only if volume outgrows the free tier.
- **D4 — Web fate: DECIDED small page.** After Play launch, hosting shrinks to a minimal site (store link + privacy policy). Full decommission explicitly rejected.

## 8. Immediate next action

D1–D4 answered 2026-09-17 — Phase 0 is unblocked. Next: kick off
Phase 0 (spec freeze & extraction) + owner starts Play Console ($25)
and AdMob signup in parallel (activation and Google's 14-day
closed-testing clock run on their side).

## 9. Status update — 2026-09-19 (main @ `87e5d17`)

Delta since §8, same convention as the status doc's §23: what shipped,
with PR evidence. Two agents work in parallel (native + web
verification); branches below are all merged unless marked OPEN.

- **Phase 0 DONE.** Spec catalog `docs/specs/01-screens.md`,
  `02-engine-api.md`, `03-transactions.md`, `04-scoring.md` (PRs #32, #33).
- **Phase 1 DONE.** Skeleton + login + CI (PR #34; AGP 8.7.3 + Kotlin
  1.9.25 + Compose 1.5.15 + compile/target 34, proven matrix in §9 of
  the handoff notes).
- **Phase 2 PARTIAL — pure engines DONE, session OPEN.** Cards/Deck/
  Scoring + JUnit (PR #35, incl. half-up fix `9a6a6e8`), BiddingEngine
  (PR #36), TableEngine (PR #37), RoundScore wrapper with Sa'ayda
  ladder, multiplier arming, Classic mode, and the graduated Risk
  ladder per D2 (PR #38). Zero Android imports; every JS-pinned case
  re-expressed as JUnit. GameSession/RemoteStore NOT started —
  delegated to the second agent (`android/phase6-session`, unpushed).
- **Phase 4 UI PARTIAL (ahead of order).** Shell + routes + gold-dark
  theme + splash + standings (PR #40), bidding screen (PR #41), table
  screen (PR #42), profile + persisted settings (PR #43). Real Lobby
  rooms + Room screen wait for the session/backend. Offline
  quick-match flow (Bidding→Table→Standings, hot-seat) OPEN as PR #44
  (CI red at last check — fixes pushed, re-run pending).
- **Web verification (second agent).** T-001 DONE (PR #39 rename, green
  on emulator, no logic bug). T-002 VERIFIED already-satisfied: the
  `hosting-dist/` pipeline was correct, smoke 21/21 + 51/51 regression
  with zero code changes (harness incident hit production mid-task;
  owner cleaned it 2026-09-19). T-003 OPEN (signup 409
  double-bootstrap race, repro-first, with second agent).
- **Still open, unchanged:** Phase 3 services (room/match/transactions
  vs emulator), presence (P4), billing (D3), Play tracks (Phase 5),
  web shrink (D4/Phase 6).
- **Snapshot estimate: ~45% of native v1** (engine 100%, screens ~65%,
  session ~5%, integration/billing/launch 0).

## 10. Status update — 2026-09-21 (main @ `a8fc086`)

§9 above is superseded for planning purposes. Two merges landed after it:

- **Phase 3 services DONE.** The `:services` module (RoomService,
  MatchService, MatchAdapter, TxSupport, models, session seam) merged as
  PR #47, with the `rules-emulator` CI job green and the reload/resume
  replay tests passing.
- **Phase 2 session DONE.** The GameSession state store in `:engine` plus
  the `GameSessionBridge` in `:services` merged as PR #48; CI now runs
  `:services:test`. The "session ~5%" line in §9 is obsolete.
- The offline quick-match flow referenced as CI-red in §9 is merged and
  green (PR #44, fixed in `8dd75e1`).

Remaining: AI opponents, the `:app`→`:services` wiring (the online UI
critical path), instrumented testing, monetization, and store readiness.

For hour-level estimates, phases, risks, and the toolchain/API-36
blocker, see **`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`** — it is the
current planning document; §5's T-shirt sizes and the "~5–8 months"
figure are its scope, not its schedule.
