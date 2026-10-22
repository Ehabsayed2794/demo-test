# Estemshan (Egyptian Estimation) — Native V1 Implementation Plan (V2)

> **Status:** current implementation plan for native V1. This document **replaces**
> `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (2026-09-21) as the practical plan. The older file is
> retained unchanged as history. This is a plan, not a report: it defines what must be built,
> tested, decided and evidenced before V1 can ship. Nothing in it has been executed.
>
> **Baseline:** commit `7a7e65c` (merge of PR #151 into `origin/main`, 2026-10-22). All code
> references below were re-checked against that commit. Branch:
> `docs/native-v1-implementation-plan-v2`.
>
> **Document date:** 2026-10-22. **Owner:** product owner (Ehab Sayed). **Author:** engineering
> planning pass. **Change class:** documentation only — no application, engine, functions,
> rules, infrastructure, build or deployment changes accompany this plan.

## Document control

| Item | Value |
|---|---|
| Plan file | `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` |
| Supersedes | `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (2026-09-21, baseline `a8fc086`) as the practical plan; that file stays as history |
| Baseline commit | `7a7e65c` — merge of PR #151 on `origin/main`, dated 2026-10-22 |
| Baseline PR | #151, docs-only: 6 files, +448 / −9 |
| Target | Native V1, Android only, 4-player Estemshan, Arabic + English |
| Planning unit | engineering hours (one experienced Android/Kotlin engineer), excluding external waiting time |
| Estimate range | **1,638–2,832 engineering hours** (§5.5); roughly 205–354 person-days |
| Stage 0 status | **NOT YET RUN.** All 13 experiments are engineering investigations, not results |
| Runtime proposal | Cloudflare Workers + SQLite Durable Objects + D1 is a **PROPOSAL**, not an approved architecture (§5.7, O-06) |
| Monetization | No billing, ads or IAP in the current baseline and no approved V1 monetization scope (O-09) |

### Source-of-truth hierarchy

When two sources disagree, the higher row wins. This hierarchy is the one the owner set for this
planning pass; it is not a claim that every lower source has been rewritten.

| Rank | Source | Role |
|---|---|---|
| 1 | Latest dated owner decision (`docs/decisions/2026-10-21-server-authority-and-owner-rulings.md`, and later dated records) | Binds the plan even where older text disagrees |
| 2 | `docs/rules/CANONICAL_RULES.md` **Current Rules** section | Governing rule summary |
| 3 | Dated decision records and `docs/decisions/OPEN_QUESTIONS.md` answers | Detail and history behind rank 1–2 |
| 4 | Earlier canonical sections and amendments in `CANONICAL_RULES.md` | Context; may be explicitly superseded |
| 5 | Plans, ADRs, design documents | Intent and architecture; not rule authority |
| 6 | Code, tests, rules files | **Evidence of what exists**, never evidence of what is correct |

A lower-ranked source that contradicts a higher one is recorded as a documentation defect
(§5.2.3), not silently followed. Two known cases matter immediately: the Current Rules summary
still says Vote Kick records 2 Koz, and A6.7 still says the same, while the binding owner ruling
(A5.1, 2026-10-17) says **1 Koz**. This plan uses 1 Koz.

### Scope of this document

In scope: the complete path from `7a7e65c` to a V1 that can be submitted to Google Play and
operated at $0 marginal infrastructure cost, including rules completion, Ranked runtime,
settlement integrity, matchmaking, bots, disconnect/reconnect behaviour, statistics, seasons,
authentication, Android UI design and conversion, store policy work, testing, release gates and
estimates.

Out of scope for this document and for V1 unless the owner rules otherwise: writing the code
itself (this is the plan the implementation will follow), iOS, a web client, leaderboards,
tournaments, friends/social graph, spectating, voice chat, monetization, and any architecture
approval. The plan states where those sit in §5.7 rather than pretending they are settled.

### How to read this plan

- §5.1 defines what “V1” means and the objective criteria for calling it released.
- §5.2 records the exact repository baseline and the gap between it and V1, including
  documentation defects found while reconciling sources.
- §5.3 maps every V1 requirement to planned tests, with concrete scoring and state vectors.
- §5.4 sequences the work into phases and identifies the critical path and the Stage 0 gate.
- §5.5 is the work breakdown and estimate, epic by epic, with files, dependencies, acceptance,
  commands, evidence and confidence.
- §5.6 enumerates failure modes and the data-integrity rules that must survive them.
- §5.7 separates owner decisions still required from engineering investigations and from work
  deferred beyond V1. It also separates spec gaps from implementation bugs.
- §5.8 defines the release gates. A gate is met only by evidence produced by the gate itself;
  a documentation change or a successful compile never satisfies a gate.

## 5.1 Executive summary and release definition

### 5.1.1 Release definition

**V1 is a production Android release of Estemshan that a stranger can install from Google Play,
sign into, play a complete four-player match in any of the three modes, and return to without
losing progress or being charged incorrectly — with Ranked results produced by an authority the
client cannot forge.**

That sentence implies four things the baseline does not yet have: complete and unambiguous
rules, a trustworthy Ranked result path, a designed and wired Android experience for all 27
identified surfaces, and the store/compliance work needed to publish. The engine is strong
(473 tests, full match scoring) but the product around it is not release-shaped.

V1 ships **Android only**, in **Arabic and English**, with **no billing, no ads, no
leaderboards, no social graph, no voice chat and no web client** unless the owner adds any of
them through the decisions in §5.7. The runtime cost target is **$0 marginal** on free tiers,
with **no Firebase Blaze plan** and no billing account attached to the project.

### 5.1.2 In scope and out of scope

| Area | In V1 | Explicitly out of V1 (see §5.7) |
|---|---|---|
| Game modes | ROOM (private), UNRANKED, RANKED | Tournaments, spectating, party play beyond the existing rank-down join gate, decay beyond the reset model |
| Rules | FULL 18 rounds / fast 14–18; MINI 10 / fast 6–10; Normal and Classic calculation; Dash; Risk; With/Wazz; Sa'ayda; Super Call; General Pass; 13 Rule | Any new rule variant invented during implementation |
| Ranked | 19 ranks, RP engine, placement, Auto-Match with bot fill, seasons, rank-down party gate | Public leaderboard (deferred by owner), cross-season ladders, decay beyond the documented reset |
| Statistics | Nine career statistics for all finished matches, including UNRANKED and ROOM | Per-mode stat filters beyond what the existing profile model stores |
| Identity | Anonymous guest, email sign-up/sign-in, Google sign-in, anonymous→registered linking, account deletion path | Apple, phone auth, Google Play Games, other social login |
| Android | All 27 surfaces designed, implemented and wired; EN/AR with RTL | Tablet-specific layouts, landscape-only redesign, Wear/TV |
| Operations | Backup policy, recovery, rollback, incident runbook, $0 free-tier verification | 24/7 on-call, SLA, multi-region failover |
| Monetization | None | Ads, IAP, subscriptions, cosmetics |
| Voice | None | Push-to-talk or any real-time voice |
| Platform | Google Play production release | iOS, web client, desktop |

The absence of monetization and voice is a **scope statement, not a judgement that they are
unimportant**. Both appeared in the 2026-09-21 plan's V1 framing and neither appears in the
current release gates or the baseline. They are owner decisions O-08 and O-09; this plan does
not estimate them and does not silently drop them.

### 5.1.3 Mode authority matrix

The three modes do not have the same trust model, and V1 must not pretend they do.

| Property | ROOM | UNRANKED | RANKED |
|---|---|---|---|
| Gameplay state owner | Client (app-managed) | Client (app-managed) | **Server-authoritative target** (proposal; §5.7 O-06) |
| What the backend stores | Final result only | Final result only | Authoritative match state and result |
| Who reports the result | All four clients; backend requires matching reports | All four clients; backend requires matching reports | Server computes; client report is advisory |
| RP | None | None (frozen) | Yes, one source of truth |
| Career statistics | Yes (all nine) | Yes (all nine) | Yes (all nine) |
| Vote Kick | Not held in ROOM | 1 Koz | 1 Koz |
| Departure charge | 1 Koz (§5.2.3 D-02) | 2 Koz | 2 Koz |
| RD31 2-Koz doubling | Excluded | Applies | Applies |
| Bot fill | Allowed (owner scope question C18) | Allowed per existing rules | 2-minute fill + 30-second majority vote |
| Season reset | No | No | Yes |

Two consequences follow. First, “server-authoritative” is a **RANKED-only** goal; applying it to
ROOM would contradict the owner's explicit app-managed decision and add cost for no integrity
gain. Second, statistics are **not** a Ranked feature: UNRANKED and ROOM matches must update
them, which is the opposite of the baseline's `accumulateRankedStat` gate.

### 5.1.4 Hard constraints

| Constraint | Consequence for the plan |
|---|---|
| No billing account, no Blaze plan, $0 marginal runtime | Firebase Functions cannot be the V1 Ranked runtime as deployed today; any replacement must fit free tiers and be approved by ADR after Stage 0 |
| Cloudflare Workers/DO/D1 is a proposal only | No implementation task may assume approval; Stage 0 is a gate, not a formality |
| Android only | No shared web UI work; Compose is the only client |
| Four players, one device each | No hot-seat or pass-and-play mode |
| Arabic + English | Every user-visible string, including engine-produced text, must be localizable |
| Owner decisions bind | Unresolved rules become §5.7 questions, never guessed implementations |
| Single primary engineer | Estimates are engineering hours, not calendar promises; external waits are separated |
| 90% backup policy, one RP source of truth | Backup design cannot create a second authoritative RP store |
| Leaderboard deferred | Do not build it, do not design around it, keep the data model able to add it later |

### 5.1.5 Measurable V1 acceptance criteria

These are the criteria §5.8 turns into gates. Each is objectively checkable.

| ID | Criterion | Evidence required |
|---|---|---|
| V1-C1 | Every P0 rule in §5.3 has at least one passing automated test at the engine or functions layer, and no known P0 rule gap remains | Test report mapped to §5.3 IDs |
| V1-C2 | ≥200 seeded matches produce zero unexplained JVM-vs-server diffs | Stage 0 E2 report with seeds and diff classification |
| V1-C3 | All pre-existing tests still pass: engine 473, app 183, services 189, functions 516, 56 emulator suites (or documented replacements with equal coverage) | CI run on the release commit |
| V1-C4 | 100% of V1 requirements in §5.3 trace to at least one test ID and one acceptance row | Traceability matrix review |
| V1-C5 | RP for every owner vector matches exactly; one RP source of truth; no double application under retry | RP vector suite + fault-injection run |
| V1-C6 | Duplicate settlement calls and replayed requests produce exactly one stored result and one RP delta | Idempotency test log |
| V1-C7 | Interruption at trick, round, result and settlement boundaries recovers to a consistent state within the documented bound | Recovery test matrix (§5.6) |
| V1-C8 | Departure charges match the owner table for all three modes, only at match finish, never for server/quota failure | Charge vector suite |
| V1-C9 | Every finished match in every mode updates the nine career statistics; UNRANKED is included; the Ranked placement gate applies only to placement matches | Stats integration tests |
| V1-C10 | 90-day season, lazy reset and two-step reset pass their vectors; no profile remains stale after settlement | Season reset suite |
| V1-C11 | All 27 surfaces are implemented and wired or explicitly deferred with owner sign-off; 11 routes reachable; no placeholder strings; EN/AR + RTL render | UI surface checklist + screenshots |
| V1-C12 | Guests can play ROOM but not RANKED; email sign-up and Google sign-in work; anonymous→registered linking preserves the uid; account deletion path exists in-app and on the web | Auth tests + deletion walkthrough |
| V1-C13 | Closed test with ≥12 testers opted in continuously for 14 days completed; production access granted; reviewer credentials work; Data safety and deletion declarations live | Play Console records |
| V1-C14 | $0/free-tier feasibility measured and approved; backup ≥90%; rollback rehearsed; incident runbook exists; owner signs acceptance | Stage 0 E5/E6/E10/E12 + release checklist |

### 5.1.6 Top risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| R-01 | Unresolved rules (Classic Dash+Risk, fast equal-highest Super Call, ROOM 7th takeover) block tests | High | High | §5.7 decisions O-01…O-03 before the affected engine tasks | Owner |
| R-02 | Stage 0 shows the engine cannot run under the proposed worker runtime | Medium | High | Early gate; fallback architecture decision, not a silent rewrite | Owner + eng |
| R-03 | Server-authoritative Ranked is much larger than the current client-owned model suggests | High | High | Treat as a proposal; estimate range wide; Stage 0 first | Eng |
| R-04 | Free-tier request/storage limits are exceeded by real usage | Medium | High | E5/E6/E10 measurements; capacity model; no leaderboard | Eng |
| R-05 | Fewer than 12 testers available, resetting the 14-day clock | High | High | Recruit before RC; treat as external wait, not engineering time | Owner |
| R-06 | Account deletion is treated as compliant when it is only deferred | High | High | In-app path + web resource + declarations are release gates (§5.8 G8) | Owner |
| R-07 | Arabic quality: colloquial draft, 6 dropped placeholders, engine text not localized | High | Medium | Localization epic + native-speaker review gate | Owner |
| R-08 | Auth wiring incomplete: no google-services plugin, anonymous/email only, no Google sign-in or sign-up | High | High | E6 tasks; real project wiring verified on device | Eng |
| R-09 | Client-triggered settlement with in-memory retry loses results on process death | High | High | Durable outbox + idempotent settlement (§5.6) | Eng |
| R-10 | Four matching ROOM/UNRANKED reports can be fabricated or collusive | Medium | Medium | Documented limitation; server records final result only; no RP | Owner |
| R-11 | Version skew between app, rules and functions | Medium | Medium | Version gate and minimum-client field (§5.6 F-16) | Eng |
| R-12 | Design coverage: core screens have no artboards; all mockups are 932×430 landscape | High | Medium | E7 designs portrait first for the 27 surfaces | Owner + design |
| R-13 | Single-engineer capacity: 1,638–2,832 h is 41–71 weeks at 40 productive h/week | High | High | Scope decisions in §5.7; phased release; no hidden parallel work | Owner |
| R-14 | ADR 0002 and the no-Blaze decision conflict | High | Medium | New ADR after Stage 0; ADR 0002 marked superseded in part | Eng |

### 5.1.7 Decisions required before implementation starts

Four decisions block work rather than merely shaping it:

1. **O-01 Classic Dash+Risk numbers** — blocks Classic scoring tests and implementation.
2. **O-02 fast-round equal-highest Super Call tie** — blocks fast auction and Wazz tests.
3. **O-03 7th bot-takeover Koz scope and its interaction with ROOM/UNRANKED vote/WAIT** —
   blocks departure-charge and takeover implementation in those modes.
4. **O-06 runtime architecture approval** — cannot be decided before Stage 0; blocks Ranked
   runtime implementation, not Stage 0 itself.

The other decisions shape or defer specific work; they do not stop the baseline or Stage 0.

## 5.2 Repository baseline and implementation-gap audit

### 5.2.1 Baseline facts

| Fact | Value at `7a7e65c` | Evidence |
|---|---|---|
| HEAD | Merge of PR #151 (`docs/native-v1-plan-amendments`), 2026-10-22 | `git log -1` |
| PR #151 shape | Docs only: 6 files, +448 / −9 | `git show --stat 7a7e65c` |
| Working branch | `docs/native-v1-implementation-plan-v2` | branch created from `7a7e65c` |
| Kotlin modules | `native/engine`, `native/app`, `native/services`, `native/functions` | `native/settings.gradle.kts` |
| Source scale | 252 Kotlin files: 144 production, 108 test | module counts: engine 69 (36/32), app 99 (47/25), services 56 (30/23), functions 63 (31/28) |
| Engine tests | 473 tests across 32 test classes | `native/engine/src/*Test` |
| App tests | 183 tests across 25 JVM test files; no `androidTest` directory | `native/app/src/test` |
| Services tests | 189 tests | `native/services/src/test` |
| Functions tests | 516 tests | `native/functions/src/*Test` |
| Emulator suites | 56 `tests/*.cjs` suites run against Firestore/Auth emulators | `native/functions/package.json`, `.github/workflows/test.yml` |
| Rules file | 2,410 lines, deny-by-default, structural validation only | `firestore.rules:1470-1480`, catch-all `:2406` |
| Android toolchain | Kotlin 1.9.25, Gradle 8.14.3, Java 17, compileSdk/targetSdk 36, minSdk 26 | `native/app/build.gradle.kts`, `native/gradle/libs.versions.toml` |
| App identity | `versionCode 1`, `versionName "0.1.0-phase1"`; **no google-services plugin** | `native/app/build.gradle.kts` |
| CI | Strong build/unit-test job; instrumented job; hosting-only production deploy | `.github/workflows/android.yml:79`, `.github/workflows/deploy-production.yml:61` |
| Functions deployability | 11 exported callables; no billing account, so Cloud Functions cannot be deployed under the no-Blaze constraint | `native/functions/index.js:19-33` |
| Design artefacts | 17 HTML files / 12 distinct screens, all **932×430 landscape**; no portrait artboards | `docs/design/`, `docs/UI_UX_ROADMAP.md:2612-2615` |
| Android surfaces | 27 identified: 4 implemented and wired, 7 partial, 7 design-only, 9 missing | `docs/UI_UX_ROADMAP.md`, `native/app` routes |
| Navigation | 11 routes; `Routes.STANDINGS` is a dead preview | `native/app/src/main/java/com/estemshan/game/ui/Routes.kt` |

### 5.2.2 Implementation-gap audit

Status vocabulary: **Implemented** (behaviour exists and is broadly correct), **Partial**
(some path exists, material parts missing), **Incorrect** (path exists and contradicts the
binding rule), **Missing** (no implementation), **Not deployable** (code exists but cannot run
in production under the V1 constraints).

Every row has seven columns: requirement area, required V1 behaviour, baseline evidence,
status, gap, planned work, and the evidence that closes the gap.

#### A. Gameplay, rules and scoring

| Area | Required V1 behaviour | Baseline evidence | Status | Gap | Planned work | Exit evidence |
|---|---|---|---|---|---|---|
| Match and round structure | FULL 18 rounds (fast 14–18), MINI 10 (fast 6–10), 4 players, 13 tricks, counter-clockwise | `GameType.FULL(18,14,5)` / `MINI(10,6,1)` in `Bidding.kt:111-113`; engine test suite | Implemented | None known at the structural level; extension and pass rules are separate rows | E2.1 regression lock | Engine test report |
| Auction and bid validation | Minimum bid 4; Caller’s winning suit binds With; Estimation Jump-In respects the rule | `withFloorFor` reads the last `BID` in **any** suit (`Bidding.kt:273-274`); `forbiddenEstimateFor` (`Bidding.kt`) is number-only | Partial | With floor is suit-blind; jump-in does not carry suit semantics | E2.2 | New auction matrix tests (§5.3 T-08/T-12) |
| Fast Super Call | Every higher 8+ Super Call re-opens confirmation; every player except the Super Caller re-declares; trump may change | `fastSuperResolved` set once (`Bidding.kt:594`, `:609`); replacement trump wipes **only seats before the Caller** (`Bidding.kt:575-585`); every 8+ becomes With (`superCandidates`, `:707-722`) with a seat-order tie-break | Incorrect | One-shot resolution; later estimates stay committed; With set and tie-break invented | E2.3 | T-09/T-11 vectors; owner decision O-02 for ties |
| With / Wazz | Normal: With = anyone who bid the Caller’s winning suit, number = their last bid in that suit. Fast: highest winning number **exactly**, plus final-trump estimate if trump changed; not every 8+ | Same `superCandidates.drop(1)` path (`Bidding.kt:716`); floor suit-blind | Incorrect | Over-inclusive With set; no final-trump re-estimate rule | E2.3/E2.4 | T-08/T-10/T-12 |
| General Pass | Consumes the round; Normal applies ×2; Classic consumes with no multiplier; multiplier sequence ×2→×4→×6→×8 | `doubled = minOf(state.roundMultiplier * 2, 8)` (`Bidding.kt:496`); client re-deals the same round (`QuickMatchViewModel.kt:154-166`; `OnlineMatchViewModel.kt:724` returns on `GeneralPass`); Classic doubles | Incorrect | Same-round replay; sequence skips ×6; Classic wrongly doubles; no round consumption | E2.5 | T-01/T-02/T-22 |
| Sa'ayda extension | Each 14–18 round with Sa'ayda adds a round; final round repeats until it ends with a winner; no caps; MINI 6–10 mirror | Window-only extension; caps FULL 5 / MINI 1 enforced and user-facing (`A2.1`); no final-round repeat | Partial | No repeat-until-winner; caps contradict Q2c; MINI final repeat absent | E2.6 | T-03/T-04 |
| Round extension | Extension only when the Super Caller succeeds; combines with Sa'ayda count | `computeRoundExtension` returns false outside `firstFastRound..baseRounds` (`RoundScore.kt:222-238`) | Partial | No extension from the final round; combination with count untested | E2.6 | T-04/T-05 |
| Normal scoring | 10+T; Caller/With ±10; sole ±10; Risk; Super Call bid² win / half-up loss; Normal Dash + Risk **adds** both | Super Call and Dash rows correct (`Scoring.kt:63`, `:75`); `normalRoleFor` returns `REG_DASH` before checking `isRisk` (`RoundScore.kt:79`) | Partial | Dash+Risk silently drops the Risk component (engine +10 instead of +20 at the owner example) | E2.7 | T-06 |
| Classic scoring | Separate tables: E+13, Caller penalty 10/20, Risk ±10, Wizz-Risk E+33, Super Call +42/−20, Dash tables, sole cap −22; Classic Dash+Risk adds both | Base tables present (`Scoring.kt:112` for flat Super Call); Dash+Risk absent; numbers for Classic Dash+Risk open (Q4) | Partial | Missing Classic Dash+Risk; owner numbers unresolved | E2.8 (blocked on O-01) | T-07 / O-01 |
| Placement and Koz | Tied lowest are all Koz unless all four tie (then all King); shared Koz is not Second/Third | `placementRank` = 1 + seats strictly ahead (`Placement.kt:212`); `placementOutcome` maps rank 3 → THIRD (`:218-222`); comment defends “a Koz is never fabricated” | Incorrect | Tied-lowest players become THIRD; shared Koz unsupported | E2.9 | T-13 |
| RP engine | 19 ranks; base Koz −20 / Third −8; MINI ×0.5 ties away from zero; RD29 band; one source of truth | `baseKozLoss = -20`, `baseThirdLoss = -8` (`RpEngine.kt:76-78`); `computeBaseDelta` (`:200`); `roundDelta` (`:327`); `finalRpDelta` (`:347`) | Partial | Correct arithmetic, but inherits the placement bug, so tied-lowest players receive Third loss | E2.9/E3.2 | T-13/T-20 |
| Bots | Seat takeover, bot profile from room config, deterministic play | `botProfileFor` (`TimeoutResolution.kt:417`); bot takeover path exists; engine bot tests | Partial | Bot play exists, but the 7th-takeover charge rule and ROOM scope are unresolved (O-03) | E2.10/E4.3 | Bot match suite |
| Inactivity and takeover | 10 s disconnect delay, then takeover; no 50 s engine clock; 7th takeover removes and records 2 Koz in RANKED/UNRANKED (ROOM open, O-03); 60 s all-gone cancellation | `TAKEOVER_THRESHOLD_MILLIS = 50_000L` (`InactivityClock.kt:143`); comment “There is no timeout count here” (`:147`); 15-timeout removal threshold 15 (`TimeoutResolution.kt:245`) | Incorrect | Uses the 50 s S73 clock as the takeover trigger; no 10 s delay, no takeover counter, no 7th removal, no 60 s cancel | E2.10/E4.3 | T-16 |

#### B. Ranked runtime, settlement and progression data

| Area | Required V1 behaviour | Baseline evidence | Status | Gap | Planned work | Exit evidence |
|---|---|---|---|---|---|---|
| Ranked runtime | Server-authoritative match state for RANKED only; client cannot forge the result | Gameplay is client-owned; the client writes match state under `firestore.rules`; rules cannot verify a shuffle (`firestore.rules:1470-1480`) | Missing | No authority layer for gameplay, no move validation, no server-side deal | E3.1 (after Stage 0/O-06) | Stage 0 E1/E2 + authority tests |
| Result settlement | Exactly-once settlement; durable outbox; idempotent claim; no in-memory-only retry | `settleOnce` (`SettlementDb.kt:691`), transaction (`:757`), `foldRankedProgression` (`:608`); client `maybeSettle` (`OnlineMatchViewModel.kt:944`) with `MAX_TRANSIENT_FAILURES = 6` (`:1312`); `Settlement.decide` returns `NoOp` for ROOM/UNRANKED (`Settlement.kt:340-348`) | Partial | Settlement is client-triggered and retries live only in memory; no durable outbox; departure charges and all-left abandonment not applied | E3.4/E3.5 | T-18 fault injection |
| Departure charges | ROOM 1 Koz; UNRANKED/RANKED 2 Koz; only at finish if not returned; no charge for server/quota failure; 7th takeover 2 Koz in RANKED/UNRANKED (ROOM open, O-03) | `decideLeave`: Vote Kick → `Koz1`; RANKED/UNRANKED → `Koz2`; ROOM → `None` (`LeaveMatch.kt:50-56`) | Partial | ROOM charge missing; no finish-time application; no server-failure exemption; 7th-takeover scope open | E3.5 (O-03) | T-14/T-15 |
| Matchmaking | Auto-Match joins a player-created waiting table; 2-minute bot fill; 30 s majority vote; humans-only strength | No production implementation (`MatchmakingPort.kt:29-33`); navigation hardcodes ROOM (`AppNav.kt:291`); no queue or matchmaking screen | Missing | Entire matchmaking flow, including bot-fill vote and strength source | E4.1/E4.2 | T-20 |
| ROOM lifecycle | Full table of four; create/join by code; ready/start; leave; no Ranked rules | `RoomService.createRoom` (`:55`), `setReady` (`:234`), `maybeStartMatch` (`:273`); `CreateGameScreen` is room-only (`:48-50`); no room list (`LobbyScreen.kt:43-45`) | Partial | No discovery/list, no four-player enforcement surface, navigation only opens ROOM | E4.4 | ROOM E2E test |
| Statistics | Nine career statistics for **all** finished matches, including ROOM/UNRANKED; counts and percentages over games played; the Ranked placement gate applies only to placement matches | `accumulateRankedStat` returns unchanged unless `mode == RANKED` and not placement (`RankedStats.kt:103`); `accumulateDepartureCharge` (`:151`) | Incorrect | Statistics are Ranked-only and placement-gated; ROOM/UNRANKED contribute nothing | E5.1 | T-19 |
| Seasons | 90-day seasons, lazy reset, two-step reset, no bulk 10,000-profile sweep as the only mechanism | `NINETY_DAYS_MILLIS` (`SeasonResetRun.kt:80`); `MAX_RESET_PROFILES = 10_000` (`SeasonResetDb.kt:103`); scheduled sweep | Partial | Bulk sweep caps at 10,000 and is not lazy per profile; reset races settlement | E5.2 | T-19 + reset race tests |

#### C. Android product, security, delivery and operations

| Area | Required V1 behaviour | Baseline evidence | Status | Gap | Planned work | Exit evidence |
|---|---|---|---|---|---|---|
| Core Android surfaces | All 27 surfaces implemented and wired; 11 routes reachable; no dead previews | 4 wired, 7 partial, 7 design-only, 9 missing; `Routes.STANDINGS` dead; `MatchMode.ROOM` hardcoded (`AppNav.kt:291`) | Partial | 23 surfaces need work (7 partial, 7 design-only, 9 missing); navigation does not expose Ranked/UNRANKED | E7/E8 | Surface checklist + screenshots |
| Ranked UI | Rank chip, tier/division, RP progress, season label, placement intro, matchmaking | No rank chip; S54 season blank; S45–S56 design-only | Missing | All Ranked-facing UI and state wiring | E7/E8 | Ranked UI test + screenshots |
| Match-time UI | Vote Kick, disconnect vote, WAIT countdown, leave confirmation, takeover indicator, 7th removal | No vote-kick, disconnect-pause, leave or timer UI | Missing | All in-match decision UI | E7/E8 | T-14/T-16 UI tests |
| Authentication | Anonymous guest for ROOM/UNRANKED; email sign-up and Google sign-in for Ranked; linking preserves uid; deletion path | `signInAnonymously` / `signInWithEmail` only (`AuthRepository.kt:19-20`); no google-services plugin; no Google, sign-up or deletion screen | Partial | No Google, no registration, no linking, no deletion; Firebase project wiring unproven on device | E6 | T-21 + device walkthrough |
| Rules and security | Deny-by-default, but also enforce what the client must not forge; version gate | 2,410-line rules, deny-by-default, catch-all (`:2406`); comment states structural validation only (`:1470-1480`) | Partial | Rules validate shape, not fairness; no minimum-client version field | E3.6 | Rules test suite + adversarial review |
| Functions deployment | Deployable runtime under $0/no Blaze; manifest matches exports | 11 exports (`native/functions/index.js:19-33`); `operatorSeasonResetDryRun` absent from the export map; Cloud Functions require Blaze | Not deployable | Runtime cannot be deployed as-is; manifest drift | E1/E3.1 | Stage 0 E1/E9 + deploy dry run |
| CI and release engineering | Build, unit, instrumented, emulator suites, release signing, versioning, store upload | `android.yml:79` covers app/engine/services unit tests; `.github/workflows/test.yml` runs 56 cjs suites; deploy is hosting-only (`deploy-production.yml:61`); version `0.1.0-phase1` | Partial | No release build, signing, bundle upload or version-skew gate | E10/E11 | Release pipeline run |
| Backup, observability and recovery | ≥90% backup coverage for the primary RP store; outbox; recovery runbook; alerts; rollback | No backup, alerting or runbook artefacts found | Missing | Operational readiness entirely absent | E3.4/E9 | Stage 0 E7 + gate G4 |

### 5.2.3 Documentation defects found

These are defects in the **sources**, not in the code. They are recorded so implementation and
review do not re-litigate them. Fixing the rule documents is a separate documentation task and
is **not** part of this plan’s allowed changes.

| ID | Defect | Where | Binding resolution | Action |
|---|---|---|---|---|
| D-01 | Vote Kick recorded as **2 Koz** | Current Rules item 7 (`CANONICAL_RULES.md:209`) and A6.7 (`:533`) | **1 Koz** (A5.1 `:445`, `:449`; A4 amendment `:365`; owner ruling 2026-10-17) | Rules correction ticket; tests use 1 Koz |
| D-02 | ROOM departure described as “no 2-Koz rule” without stating the 1-Koz charge | A5.1 charge table (`:441-447`) vs A6.6 (`:532`) | **ROOM 1 Koz**, excluded from RD31’s 2-Koz charge | Clarify A5.1 table footnote |
| D-03 | A6.1 says a General Pass is a “redeal”; A6.4 says it **consumes the round** | A6.1 (`:516`) vs A6.4 (`:530`) | **Consumes the round**; A6.1 wording superseded in part | Consolidate A6.1/A6.4 |
| D-04 | A6.9 fast-round With is superseded by A6.10 but both remain normative-looking | A6.9 (`:535`), A6.10 (`:536`) | A6.10 controls fast rounds; A6.9 controls Normal | Consolidate into one paragraph |
| D-05 | A3.5 statistics table marks ROOM and UNRANKED “no” | A3.5 (`:318`), prose “no progression of any kind” (`:325`) | **UNRANKED and ROOM enter all nine statistics** (A6.7 `:533`, Q14c) | Correct A3.5 and its prose |
| D-06 | Extension caps “up to 5” / “exactly 1” | A2.1 (`:241`), A2.2 (`:249`) | **Caps removed** (Q2c; A6.2 follow-up) | Remove caps from A2.1/A2.2 |
| D-07 | A1 body scopes bid² to the legacy tracker | A1 (`:216-224`) | Native Normal mode applies bid² (Q19c pointer at `:224`) | Body text correction; numbers are correct |
| D-08 | ADR 0002 defines a Firebase Functions runtime | `docs/adr/0002-functions-runtime.md` | Conflicts with no-Blaze and with the Cloudflare **proposal** | New ADR after Stage 0; mark 0002 superseded in part |
| D-09 | RD25 makes Ranked launch-blocking, but no-Blaze prevents deploying its functions | Old plan `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:58` | Unresolved until the runtime decision; Stage 0 exists to resolve it | §5.7 O-06 |
| D-10 | Story numbers S36–S44 (design) collide with S36–S74 (plan) and E7 uses S65–S69 | `docs/UI_UX_ROADMAP.md:812-819` | **Map by name, never by number** | Keep the warning; cite names in this plan |
| D-11 | `AGENTS.md` calls the 2026-09-21 file the “only plan document” | `AGENTS.md:3-8` | This V2 plan is current; old file is historical | Pointer edit (part of this task) |
| D-12 | Q14d answer still says Vote Kick 2 Koz | `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md` §N | 1 Koz per A5.1 | Mark Q14d superseded |
| D-13 | “All players are Koz” abandonment wording does not distinguish humans from bots or specify ROOM | A5.1 (`:209`), A6.7 (`:533`) | Partially unresolved; see §5.7 O-03 | Owner clarification |
| D-14 | Fast takeover timings (10 s/7th) sit beside disconnect vote (30 s/90 s WAIT) without an interaction rule | `docs/decisions/2026-10-21...md` §G vs A5.2 (`:455-466`) | **Not defined**; do not invent | §5.7 O-03 |
| D-15 | Current Rules is a 12-item summary, not the full rule set; coverage is uneven across areas | Current Rules (`:199-214`) | Use it as summary only; amendments and decisions govern detail | Note in rules index |

### 5.2.4 Reusable assets the plan keeps

| Asset | Why it matters | Reuse |
|---|---|---|
| Engine rules module with 473 tests | The scoring core is the most expensive thing to rebuild | Extend, do not fork; add vectors |
| FULL/MINI `GameType` and round plan | Already models both types and fast windows | Reuse in engine and UI |
| 19-rank RP model and arithmetic | `RpEngine` has base, round and final delta logic | Fix placement input, keep arithmetic |
| 2,410-line deny-by-default rules | Strong structural baseline | Add version gate and adversarial review |
| 11 callable functions and 56 emulator suites | Settlement, season, party and timeout logic already tested | Port behind the approved runtime |
| 12 design screens (932×430) | Visual language and copy exist | Redesign portrait; keep tokens |
| CI build/test pipeline | Catches regressions in four modules | Extend with release and parity jobs |

### 5.2.5 What the baseline does not prove

- **Passing tests are not rule correctness.** Several tests encode the current incorrect
  behaviour (tied-lowest → Third; one-shot fast Super Call; same-round General Pass). A green
  suite at `7a7e65c` proves consistency with today’s code, not with the owner’s rules.
- **The rules file is not an authority layer.** It validates document shape; it cannot verify a
  shuffle, a legal play or a score (`firestore.rules:1470-1480`).
- **Nothing runs on a device.** There is no `androidTest` suite, so UI wiring, auth and
  navigation are unproven outside JVM tests.
- **Nothing is measured.** Free-tier limits, backup lag, startup and bundle size have not been
  measured; Stage 0 exists because of this.
- **Nothing is releasable.** There is no signed release build, no store listing pipeline, no
  version-skew gate and no operational runbook.

## 5.3 Requirement-to-test traceability

### 5.3.1 Method and legend

Every V1 rule is traced from its **binding source** to **planned implementation**, a **test ID**
and a **release evidence** item. Test IDs are stable strings used in test names and in the
release checklist. A rule with no test ID is not releasable; a test with no rule is either a
regression guard or should be deleted.

Legend for expected values:

- **Exact** — arithmetic taken directly from a binding rule or an owner example.
- **Derived** — arithmetic produced by stacking two binding rules; the derivation is shown.
- **Blocked** — the rule is unresolved; the test exists as a skipped placeholder tied to a §5.7
  decision. A blocked test may not be “made to pass” by choosing an answer in code.
- **Fixture** — the value depends on a configuration constant (RP balance) and is asserted
  against the constant, not hard-coded as a rule.

### 5.3.2 Master traceability table

| Requirement | Binding source | Baseline evidence | Planned task | Test IDs | Release evidence |
|---|---|---|---|---|---|
| General Pass consumes the round; Normal ×2; Classic no multiplier; ladder ×2→×4→×6→×8 | A6.4 (`CANONICAL_RULES.md:530`), Current Rules item 1 (`:203`) | Same-round replay; `Bidding.kt:496`; `QuickMatchViewModel.kt:154-166`; `OnlineMatchViewModel.kt:724` | E2.5 | T-01, T-02, T-22 | Engine report + §5.8 G2 |
| Sa'ayda per-round extension, final-round repeat, no caps | A6.2 (`:520-526`), Q2c | Window-only; caps 5/1; no final repeat (`RoundScore.kt:222-238`) | E2.6 | T-03, T-04 | Engine report |
| Super Call extension only when Caller succeeds | A6.5 (`:531`) | Extension independent of success | E2.6 | T-05 | Engine report |
| Normal Super Call bid² / half-up loss | A1 (`:220-221`), Q19c | `Scoring.kt:63` | E2.7 (verify only) | T-05 | Engine report |
| Normal Dash + Risk adds both components | A6.3 (`:527-529`) | Risk dropped (`RoundScore.kt:79`) | E2.7 | T-06 | Engine report |
| Classic scoring tables incl. Super Call +42/−20 | Current Rules §4 (`:150-180`) | `Scoring.kt:112` and table rows | E2.8 (verify) | T-07 | Engine report |
| Classic Dash + Risk adds both components | A6.8 (`:534`) | Missing; numbers open | E2.8 (blocked O-01) | T-07 | O-01 decision |
| Normal With = Caller’s winning suit, last bid in that suit | A6.9 (`:535`), Q19 | Suit-blind floor (`Bidding.kt:273-274`) | E2.4 | T-08, T-12 | Engine report |
| Fast With = exact highest number, plus final-trump condition | A6.10 (`:536`), Q28 | Everyone ≥8 With (`Bidding.kt:716`) | E2.3/E2.4 | T-10, T-11 | Engine report |
| Fast Super Call re-declaration by every non-Caller | A6.11 (`:537`), Q29 | One-shot; keeps later estimates (`Bidding.kt:577-609`) | E2.3 | T-09 | Engine report |
| Shared Koz and shared King | Q2d, A6.2 (`:526`) | Tied lowest → Third (`Placement.kt:212-222`) | E2.9 | T-13 | Engine report |
| Vote Kick 1 Koz, unique Koz only, no ROOM vote | A5.1 (`:445`, `:449`), controller ruling | `LeaveMatch.kt:53` returns Koz1; no unique-Koz guard | E3.5 | T-14 | Settlement tests |
| ROOM 1 Koz; RANKED/UNRANKED 2 Koz; charge at finish only | A6.6 (`:532`), A5.1 (`:441-447`) | ROOM returns None (`LeaveMatch.kt:56`) | E3.5 | T-15 | Settlement tests |
| 7th takeover removes and records 2 Koz in RANKED/UNRANKED; ROOM scope open | Decisions §G; controller ruling | No takeover counter (`InactivityClock.kt:147`) | E4.3 (scope O-03) | T-16 | O-03 decision |
| Disconnect 10 s → takeover; 30 s vote; 90 s WAIT; 60 s all-gone cancel | A5.2 (`:455-466`), Q27c | 50 s clock only | E4.3 | T-16 | Engine + UI tests |
| Nine statistics for every mode; percentages over games played | Q14c, A6.7 (`:533`), A6.8 (`:534`) | RANKED-only, placement-gated (`RankedStats.kt:103`) | E5.1 | T-19 | Stats tests |
| 90-day seasons, lazy reset, two-step reset | Q22, `SeasonResetRun.kt:80` | Bulk sweep, 10,000 cap | E5.2 | T-19 | Season tests |
| Auto-Match with 2-min bot fill and 30 s vote; humans-only strength | Q7c/Q26, decisions §F | No implementation (`MatchmakingPort.kt:29-33`) | E4.1/E4.2 | T-20 | Matchmaking tests |
| Guests cannot enter Ranked; email sign-up and Google sign-in for Ranked; linking preserves uid | Decisions §D, Q5, Q21 | Anonymous/email only; no Google, sign-up or gate (`AuthRepository.kt:19-20`) | E6 | T-21 | Device walkthrough |
| ROOM/UNRANKED four matching reports; recorded limitation | Decisions §J, Q15c | No convergence enforcement for ROOM/UNRANKED (`Settlement.kt:340-348`) | E3.6 | T-17 | Settlement tests |
| Exactly-once settlement with durable outbox | Decisions §I | In-memory 6 retries (`OnlineMatchViewModel.kt:1312`) | E3.4 | T-18 | Fault injection |
| ≥200 seeded engine/server parity | Brief Stage 0 E2 | Not run | E1.2 | T-23 | Stage 0 report |
| Auction floor, Call Cap, 13 Rule, Dash max 2, Risk values | Current Rules §2/§5 (`:84-98`, `:190-198`) | Implemented; floor defects noted | E2.2 | T-24 | Engine report |

### 5.3.3 Rule and scoring vectors

The vectors below are the acceptance language for the engine and settlement work. They are
written as round-level or seat-level cases so they can be implemented directly as table-driven
tests.

#### T-01 — General Pass, Normal Calculation

| Step | State | Expected |
|---|---|---|
| 1 | Round 6 is a normal round; all four pass the auction | Round 6 is consumed; no scores are written for it |
| 2 | Round 7 begins | Round 7 is played at **×2** |
| 3 | Caller bids 5, takes 5, not sole | `(10 + 5 + 10) × 2 = 50` **Exact** |
| 4 | Round 7 is also all-pass | Round 7 consumed; round 8 at **×4** |
| 5 | Round 8 is also all-pass | Round 8 consumed; round 9 at **×6**, not ×8 |
| 6 | Round 9 is also all-pass | Round 9 consumed; round 10 at **×8** |
| 7 | Round 10 is also all-pass | Round 10 consumed; round 11 stays **×8** (cap) |

Note: step 5 is the engine’s current defect (`minOf(prev × 2, 8)` yields ×8 at this point).

#### T-02 — General Pass, Classic Calculation

| Step | State | Expected |
|---|---|---|
| 1 | Classic match, round 6 all pass | Round consumed |
| 2 | Round 7: Caller bids 5, takes 5, not sole | `5 + 13 + 10 = 28`, **no multiplier** **Exact** |
| 3 | The same round under the current engine | `56` (doubles) — this is the defect A6.1 names |

#### T-03 — Sa'ayda count and Game Type

| Case | Setup | Expected |
|---|---|---|
| FULL, two counted Sa'aydas | Sa'ayda in rounds 14 and 16 | Planned last round = 18 + 2 = **20** **Exact** |
| FULL, three counted | Sa'aydas in 14, 15, 16 | Planned last round = **21** **Exact** |
| MINI, two counted | Sa'aydas in rounds 6 and 8 | Planned last round = 10 + 2 = **12** **Exact** |
| Post-18 non-final | Sa'ayda in round 20 after an extension | Adds **nothing** (A6.2) |
| Cap removal | Five Sa'aydas in 14–18 | Planned last round = **23**; no `ALREADY_EXTENDED` rejection **Exact** (Q2c) |

#### T-04 — Final-round repeat

| Round | Result | Expected |
|---|---|---|
| 18 | All four fail (Sa'ayda) | Round 19 is added; round 19 multiplier ×2 |
| 19 | All four fail | Round 20 added; multiplier ×4 |
| 20 | All four fail | Round 21 added; multiplier ×6 |
| 21 | At least one player succeeds | Game ends after 21; no further round |
| 21 | All four fail | Round 22 added; multiplier ×8 (cap) |
| Final round | Top scores tied, not all-fail | Counts as having a winner (shared King); no repeat |

Assumes no earlier counted Sa'ayda. With earlier extensions, the same rule applies from the
actual final round.

#### T-05 — Super Call scoring and extension

| Case | Setup | Expected |
|---|---|---|
| Win, not sole | Normal, bid 8, takes 8, at least one other player succeeds | **64** (bid²; no Caller +10 on the Super Call row) **Exact** (A1) |
| Win, sole | Normal, bid 8, takes 8, everyone else fails | **74** (64 + sole +10) **Exact** (A1) |
| Loss, not sole | Normal, bid 8, takes 7, at least one other player succeeds | **−32** **Exact** (A1) |
| Loss, sole | Normal, bid 8, takes 7, everyone else succeeds | **−42** **Exact** (A1) |
| Extension, success | Fast round, bid 8, takes 8 | One round added **Exact** (A6.5) |
| Extension, failure | Fast round, bid 8, takes 6 | **No** extension **Exact** (A6.5) |
| Classic | Classic, bid 8, makes or misses | **+42 / −20**, and no separate Caller +10 **Exact** |
| With on a Super Call | Another player shares the number | With keeps the ordinary Wizz / Wizz-Risk formula, not the Super Call formula **Exact** |

#### T-06 — Normal Dash + Risk

| Case | Setup | Expected |
|---|---|---|
| Success | Total bids 15, Risk value 10, seat is Normal Dash, takes 0 | `+10 (dash) + 10 (risk) = +20` **Exact** (A6.3) |
| Failure | Same seat takes 2 | `−(10 + 2) − 10 = −22` **Exact** (A6.3) |
| Sole winner | Takes 0, everyone else fails | `+10 + 10 + 10 = +30` **Derived** |
| Sole loser | Takes 2, everyone else succeeds | `−12 − 10 − 10 = −32` **Derived** |
| Risk ladder | Risk value 20 / 30 on the same seat | +30 / +40 success; −32 / −42 failure **Derived** |

#### T-07 — Classic Dash + Risk (partially blocked)

| Case | Setup | Expected |
|---|---|---|
| Normal Dash Classic, success under | Total bids ≤ 13, takes 0 | **+23** **Exact** |
| Normal Dash Classic, failure over | Total bids > 13, takes 2 | **−2** (failure over = −T) **Exact** |
| Risk component, success/failure | Risk player, success/failure | **+10 / −10** **Exact** |
| Combined Classic Dash + Risk | Both roles on one seat | Adds both components (A6.8); **exact numbers blocked by O-01** |
| Combined with sole | Both roles plus sole | **Blocked by O-01**; no implementation until decided |

#### T-08 — Normal With (owner examples, verbatim)

| Case | Auction | Expected |
|---|---|---|
| 1 | Ahmed 5♥, Osama 5♥ | Ahmed Caller, Osama **With** |
| 2 | Osama then raises to 6♥ | Osama Caller, Ahmed **With** (Ahmed’s last ♥ bid is 5) |
| 3 | Ahmed wins with 6♠; Osama never bid ♠ | Osama **not With** — a matching number is not enough |
| 4 | Osama wins with 7♥; Ahmed’s last ♥ bid was 6 | Ahmed **With at 6**, even if Ahmed later bid another suit |
| 5 | Ahmed With at 5, takes 5, not sole | `10 + 5 + 10 = 25` **Exact** |
| 6 | Ahmed With at 5, takes 7 | `−2 − 10 = −12` **Exact** |

#### T-09 — Fast Super Call and re-declaration

| Step | Declarer | Call | Trump after | Who re-declares |
|---|---|---|---|---|
| 1 | A | 8 | Hearts (changed from forced Sans) | B, C, D; **not A** |
| 2 | B | 9 | Spades (changed again) | A, C, D; **not B** — A re-declares despite having declared after the first Super Call |
| 3 | C | 10 | Clubs | A, B, D |
| 4 | No higher call | — | Clubs | Auction ends; C Caller |

Additional assertions: any 8+ declarer may choose any trump (A6.10); each higher call repeats
the wipe; the order is counter-clockwise; a player who already re-declared still re-declares
again after a higher call.

#### T-10 — Fast With / Wazz

| Case | Setup | Expected |
|---|---|---|
| No Super Call | Highest number 9, no trump change | Another player with final 9 is **With**; an 8 is not |
| Super Call, trump unchanged | Caller keeps Sans at 8, another declares 8 | Another 8 is **With** |
| Super Call, trump changed | Final trump ♥, highest 9; another declares 9 **after** the change | **With** |
| Trump changed, earlier estimate | Another declared 9 under ♠ before the change to ♥, and does not re-declare 9 | **Not With** (estimate not made under final trump) |
| Number only | Final trump ♥, another declares 9♠ equivalent under a different trump | **Not With** |
| 8 alone | Another declares 8 while highest is 10 | **Not With** |

#### T-11 — Equal-highest fast Super Call (blocked)

| Case | Setup | Expected |
|---|---|---|
| Two 8s, same trump | A and B both declare 8; neither outbids | **Blocked by O-02** — no seat-order tie-break may be invented |
| Two 8s, different trumps | A chooses ♥, B chooses ♠ | **Blocked by O-02** — Caller and final trump undefined |
| Two 8s then a 9 | C declares 9 | C is Caller; the A/B tie no longer matters for the call, but With membership still depends on the final number/trump |

The existing engine sorts by amount then seat order (`Bidding.kt:709-712`); that sort is an
invention and must not become the tested rule.

#### T-12 — With edge cases

| Case | Setup | Expected |
|---|---|---|
| Suit at any time | Ahmed bids 5♥, later 6♠; Osama wins 7♥ | Ahmed **With at 5** (last ♥ bid) |
| Suit never bid | Ahmed only bid ♠; Osama wins ♥ | **Not With** |
| Same number, different suit | Ahmed 5♠, Osama wins 5♥ | **Not With** in Normal (suit governs) |
| Superseded call | A player’s last bid is a higher number in another suit | Still With in the winning suit at their last bid **in that suit** |
| With scoring | With at 6 makes 6 / misses by 1 | `+26` / `−11` **Exact** |

#### T-13 — Shared King and shared Koz

Placement outcomes for a finished match. RP base values are **fixtures** from `RpEngineConfig`
(King +30, Second +15, Third −8, Koz −20) with strength and tier differences set to zero. These
constants are documented as placeholders pending a balance pass (RD4); the vectors test the
**mapping and arithmetic**, not a final balance. The owner approves or replaces the constants
before G3 (O-11).

| Final totals | Outcomes | RP base per seat | Rule |
|---|---|---|---|
| 100 / 100 / 100 / 100 | King ×4 | +30 each | All four tied → all King; no Koz |
| 100 / 99 / 99 / 99 | King, Koz ×3 | +30, −20, −20, −20 | Every player tied at the lowest is Koz, not Second |
| 100 / 99 / 10 / 10 | King, Second, Koz ×2 | +30, +15, −20, −20 | Shared lowest → shared Koz |
| 100 / 100 / 50 / 20 | King ×2, Third, Koz | +30, +30, −8, −20 | Unique lowest → Koz; 50 is Third |
| 100 / 90 / 90 / 80 | King, Second ×2, Koz | +30, +15, +15, −20 | Shared middle, unique lowest |
| 100 / 99 / 50 / 20 | King, Second, Third, Koz | +30, +15, −8, −20 | Ordinary ladder regression |

Derived invariant: if not all four totals are equal, **every** seat holding the minimum total is
Koz regardless of its competition rank. The current `placementRank` implementation violates this
for cases 2, 3 and 5.

#### T-14 — Vote Kick

| Case | Setup | Expected |
|---|---|---|
| Charge | RANKED or UNRANKED, Vote Kick passes against a unique Koz | **1 Koz**, applied at match finish |
| Shared Koz | Two players tied at the bottom | Vote unavailable; no kick target |
| ROOM | Any ROOM table | Vote unavailable (ROOM is not a Vote Kick mode) |
| Return | Target reconnects and finishes the match | No charge |
| RP | Vote Kick in RANKED | Koz charge is a departure record; RP uses the match outcome, not a second last-place penalty |
| Ballot | Threshold and UI | Per S63 design; this plan tests the **charge and availability**, not a re-invented threshold |

#### T-15 — Departure charge matrix

| Path | ROOM | UNRANKED | RANKED |
|---|---|---|---|
| Explicit Leave Match | **1 Koz** | **2 Koz** | **2 Koz** |
| Automatic 15-timeout removal | **1 Koz** | **2 Koz** | **2 Koz** |
| Vote Kick | n/a | **1 Koz** | **1 Koz** |
| Disconnect, permanently replaced | **0** per A5.1 row (`:446`); A6.6 “any reason” conflict → §5.7 O-03c | **2 Koz** | n/a (no vote held in Ranked) |
| Return before finish | 0 | 0 | 0 |
| 7th bot takeover | **Open** → §5.7 O-03 | **2 Koz** | **2 Koz** |
| Server or free-quota failure | 0 | 0 | 0 |
| Charge timing | Only when the match finishes and the player did not return | Same | Same |
| RD31 doubling | **Excluded** | Applies to the 2-Koz paths | Applies to the 2-Koz paths |

Abandonment is a separate outcome, not a departure charge:

| Case | Result |
|---|---|
| Last human explicitly leaves | Match cancelled immediately; all four seats recorded Koz; RANKED deducts the engine’s last-place RP loss; ROOM/UNRANKED record 1/2 Koz respectively with no RP (Q27(4)) |
| All humans disconnected | Wait 60 s; if nobody returns, cancel and apply the same outcome (Q27c) |
| Server/quota failure | Never charged (Q27(3)) |

#### T-16 — Disconnect, takeover, reconnect and cancellation

| Time / event | Expected |
|---|---|
| Disconnect at t=0 | Seat remains; no immediate bot |
| t=10 s, no return | Bot takes over the seat |
| Return while bot holds the seat | Human reclaims; bot stops |
| Reconnect cycles 1–7 | Allowed; each takeover waits 10 s |
| 7th takeover | Seat removed; 2 Koz recorded in RANKED/UNRANKED; ROOM scope open (O-03) |
| 15-timeout removal | Independent counter; not the same as takeover; charge per T-15 |
| ROOM/UNRANKED disconnect vote | 30 s window, play continues, majority 2 of 3; a winning WAIT pauses 90 s after the verdict; no agreement → configured bot |
| No pause during ballot | Gameplay continues until the verdict |
| All humans gone | 60 s, then cancel per T-15 |
| Takeover and Vote Kick together | Share no state, no counter, no countdown (A3.5/A4.4) |

#### T-17 — ROOM/UNRANKED four-report convergence

| Case | Reports | Expected |
|---|---|---|
| Unanimous | 4 identical | Result recorded once |
| Split | 3 identical, 1 different | **Pending**, never auto-resolved; operator-visible mismatch |
| Split | 2 + 2 | **Pending** |
| Missing | 3 reports only | **Pending** indefinitely; no timeout auto-resolution |
| Duplicate | Same uid reports twice | Deduplicated; not counted as a fourth report |
| Replay | Same four reports resubmitted | No second record; idempotent |
| Collusion | Four fabricated but identical reports | **Accepted by design**; documented limitation, no RP at stake |
| ROOM table size | 3 humans ready | Match does not start; ROOM requires four (A6.8) |

#### T-18 — Exactly-once settlement

| Case | Sequence | Expected |
|---|---|---|
| Happy path | One settle call | Result stored, RP applied once, ledger entry |
| Duplicate call | Same idempotency key twice | Second returns the stored result; RP unchanged |
| Retry after lost response | First transaction commits, response lost, client retries | No double application |
| Retry before commit | First attempt fails, retry succeeds | One application |
| Process death | Instance dies after commit but before reply | Recovery reads the stored result; no replay |
| Outbox | Client queues result, process dies, outbox replays on next launch | One application |
| Quota failure | Backend rejects for quota | No charge, no partial profile write, retry allowed |
| Season race | Settlement for season N arrives after N+1 starts | Applied to the season in which the match completed; never overwrites the new season |
| ROOM/UNRANKED | Result pending, then converged | Recorded once when the fourth matching report arrives |

#### T-19 — Statistics and seasons

| Case | Setup | Expected |
|---|---|---|
| Stats, all modes | 10 ROOM, 10 UNRANKED, 10 RANKED finished matches; 3 Kings in each | Games Played 30; King count 9; King % = 30 |
| Percent floor | 3 games, 1 King | King % = 33 (integer division) |
| Zero games | New profile | All percentages 0, no division error |
| Departure charge | 2-Koz UNRANKED departure | Koz +2 is owner-required (the charge enters statistics); the current code also adds Games Played +1 — confirm that increment in E5.1 before locking the test |
| Placement gate | RANKED placement match | Counts toward **no** statistic |
| UNRANKED during placement | UNRANKED match while the account is mid-placement | **Counts** — the placement gate is Ranked-only |
| Season length | Season start | `endsAt = start + 90 days` |
| Lazy reset | Inactive through seasons 1 and 2, returns in season 3 | One reset on first access; no records fabricated for missed seasons |
| Reset idempotence | Two reset runs for the same season | One migration; second reports already complete |
| Reset race | Settlement and reset touch the same profile | One total order; no RP lost or double-reset |

#### T-20 — Matchmaking, bot fill and RP strength

| Case | Setup | Expected |
|---|---|---|
| Auto-Match | Player taps Auto-Match, a waiting table exists within ≤1 tier | Joins that table after showing settings |
| No table | No waiting table | App says none waiting and offers Create Table |
| Bot fill timer | First player enters; 2 minutes elapse; humans still missing | 30 s vote starts |
| Vote passes | 3 waiting humans, 2 approve | >half approve → fill with creator’s bots |
| Vote fails | 3 waiting humans, 1 approves; 2 non-votes | Non-votes are rejection → no fill |
| Two humans | 2 waiting, 1 approves, 1 non-votes | Not more than half → no fill |
| RP with bots | 3 humans + 1 bot, human finishes King | Normal RP; opponent strength computed from the 3 humans only |
| Tier band | Two players 2 tiers apart | Cannot join the same Auto-Match table (RD29 ≤1 tier) |

#### T-21 — Authentication and guest gate

| Case | Action | Expected |
|---|---|---|
| Guest ROOM | Anonymous user creates/joins ROOM | Allowed |
| Guest Ranked | Anonymous user taps Ranked/Auto-Match | Blocked with sign-in prompt |
| Guest UNRANKED | Anonymous user | Allowed (UNRANKED is rank-blind) |
| Email sign-up | New user creates an account | Account created; Ranked allowed |
| Email sign-in | Registered user | Ranked allowed |
| Google sign-in | Registered user chooses Google | Sign-in succeeds; no Google Play Games dependency |
| Linking | Anonymous → email or Google link | UID preserved; history and stats remain attached |
| Deletion | In-app delete | Account and profile data deleted through the documented path |
| Web resource | Deletion URL | Reachable without the app and works |
| App Check | V1 | Not required (deferred Q21b); do not gate launch on it |

#### T-22 — Multiplier ladder and caps

| Sequence | Expected multiplier on the next round |
|---|---|
| Round 6 all pass | ×2 |
| Rounds 6, 7 all pass | ×4 on round 8 |
| Rounds 6, 7, 8 all pass | ×6 on round 9 |
| Rounds 6–9 all pass | ×8 on round 10 |
| Rounds 6–10 all pass | ×8 (cap) |
| Sa'ayda in 18, 19, 20 | ×2, ×4, ×6 on 19, 20, 21 |
| Four consecutive Sa'aydas | ×8 |
| Any successful round | Reset to ×1 |
| No cap on passes/Sa'aydas | Only the multiplier caps |
| Classic General Pass | Round consumed, multiplier stays ×1 |

#### T-23 — Seeded engine/server parity (Stage 0 E2)

| Case | Setup | Expected |
|---|---|---|
| Corpus | ≥200 seeds × FULL/MINI × Normal/Classic | Every seed produces a complete match |
| Diff definition | Same actions replayed on JVM engine and server runtime | Zero **unexplained** diffs |
| Known gaps | Diffs attributable to a listed implementation gap | Enumerated by defect ID, not hidden |
| Post-fix | Re-run after engine fixes | Zero diffs of any kind (release gate G1) |
| Evidence | Seed list, action logs, per-seat scores, diff classification | Published in the Stage 0 report |

#### T-24 — Auction, Call Cap, 13 Rule and Dash limits

| Case | Setup | Expected |
|---|---|---|
| Minimum bid | A player bids 3 | Rejected; 4 accepted |
| Super Call threshold | Bid 7 / bid 8 | 7 is not a Super Call; 8 is |
| Dash limit | Pre-bid Dash calls by three players | Third rejected; maximum 2 |
| 13 Rule | First three estimates sum to 13 | Last estimator forced to 1–13 |
| 13 Rule scope | First three sum to 12 or 14 | No force on the last estimator |
| Call Cap, normal | Estimate exceeds the Caller’s bid | Rejected |
| Call Cap, fast | Estimate exceeds the Caller’s bid | Allowed (no cap in fast rounds) |
| Risk values | Risk seat at 10 / 20 / 30 | Value applies exactly once |
| Confirmation | Same number, weaker suit | Rejected; equal or stronger accepted |
| With floor | With attempts below their last bid in the winning suit | Rejected |

#### T-25 — Round and match end conditions

| Case | Setup | Expected |
|---|---|---|
| FULL end | Round 18 completes with no extension | Match ends |
| MINI end | Round 10 completes with no extension | Match ends |
| Extension end | Planned last round completes without a new trigger | Match ends |
| Tied top | Two players share the highest total | Shared King; both receive King outcome |
| All four tied | 100/100/100/100 | All King; no Koz |
| Win condition | One seat reaches the match end with the highest total | Match King |

#### 5.3.4 Coverage and test-layer assignment

| Layer | Test IDs | Runs in |
|---|---|---|
| Engine unit / table tests | T-01…T-16, T-22, T-24, T-25 | `:engine:jvmTest` |
| Engine ↔ functions settlement | T-13, T-15, T-17, T-18 | `:functions:jsNodeTest` + emulator suites |
| Stats and seasons | T-19 | `:functions` tests and emulator |
| Matchmaking and auth | T-20, T-21 | Emulator + instrumented/device tests |
| Android UI | T-14/T-16 UI rows, T-21 | Compose UI tests + manual device script |
| Runtime parity | T-23 | Stage 0 harness (new, disposable) |

No V1 rule is “covered” by a documentation sentence alone. A rule without a passing test at the
layer above remains an open gap in §5.8 G2.

## 5.4 Phases, milestones, dependencies and critical path

### 5.4.1 Approved delivery sequence

The owner’s 2026-10-21 FINAL amendment fixes the order:

1. **Code** — rules, engine, backend and feature implementation.
2. **UI design** — design the 27 surfaces against the finished rules and API shape.
3. **HTML → native Android** — convert approved designs into Compose.
4. **Real-people testing** — closed testing immediately before publishing.

This plan does not reorder that sequence. Stage 0 sits **inside the code phase as an early
feasibility gate**; it blocks the Ranked runtime work, not the engine rules work. UI design
starts only after the rules and Ranked API shape are frozen, and conversion starts only after
design sign-off. Real-people testing is the last step before release, not a mid-project
activity.

### 5.4.2 Phases

| Phase | Scope | Entry criteria | Exit criteria | Milestone | Hours |
|---|---|---|---|---|---|
| P0 — Baseline and decisions | Confirm baseline, close O-01…O-03, record architecture questions | This plan approved | Decisions recorded; work queue unblocked | M0 | 16–28 |
| P1 — Stage 0 feasibility | All 13 experiments; measurements; go/no-go memo | P0; access to a free-tier account | Every experiment has a result or a documented blocker; runtime recommendation | M1 | 150–260 |
| P2 — Rules and engine | Auction, fast Super Call, With/Wazz, General Pass, Sa'ayda, extension, Dash+Risk, Classic, placement, RP input, takeover clock | P0; O-01/O-02 for affected rules | §5.3 engine vectors pass; no P0 rule gap | M2 | 248–416 |
| P3 — Ranked runtime and settlement | Authority layer, durable outbox, exactly-once settlement, departure charges, recovery, version gate | M1 go; ADR approved; M2 engine frozen | T-15/T-17/T-18 pass; fault injection clean | M3 | 280–504 |
| P4 — Matchmaking, bots, disconnect | Auto-Match, 2-minute fill, 30 s vote, takeover counter, 7th removal, 60 s cancel | M3; O-03 | T-16/T-20 pass | M4 | 128–224 |
| P5 — Statistics and seasons | All-mode nine stats, percentages, lazy 90-day reset, race safety | M3 | T-19 passes | M5 | 96–168 |
| P6 — Authentication and account | Email sign-up/sign-in, guest gate, linking, deletion path | M3 | T-21 passes on device | M6 | 56–96 |
| P7 — UI design | 27 surfaces, portrait-first, EN/AR, RTL, states, copy | M2 rules frozen; M3 API shape frozen | Design sign-off on all 27 surfaces | M7 | 168–280 |
| P8 — Native conversion | Compose implementation, navigation, state, accessibility, localization | M7 sign-off; M6 auth | Every non-deferred surface wired; UI tests pass | M8 | 288–480 |
| P9 — Hardening and operations | Localization review, observability, backup drill, rollback, performance | M8 | Runbook, backup ≥90%, rollback rehearsal | M9 | 96–168 |
| P10 — Store readiness and real-people testing | Listing, Data safety, deletion URL, credentials, 12 testers × 14 days | M9; build is RC | 14 continuous days complete; production access granted | M10 | 72–136 |
| P11 — Release candidate and acceptance | Signed bundle, staged rollout, owner acceptance | M10 | Gate G11 signed | M11 | 40–72 |

Hours are engineering hours from §5.5; external waiting time is separate.

### 5.4.3 Stage 0 — thirteen experiments (NOT YET RUN)

Every row is an engineering investigation. None has been executed for this plan. The table
defines what would count as a result; it does not report one.

| ID | Experiment | Question answered | Method | Pass / decision criterion | Blocks |
|---|---|---|---|---|---|
| E1 | Engine under `workerd` | Does the Kotlin/JS engine run in the proposed worker runtime? | Bundle the engine, load it in a local `workerd`, execute one full match | Full match completes; no unsupported API | P3 |
| E2 | Seeded parity | Do JVM and server runtime agree on ≥200 matches? | 200+ seeds × FULL/MINI × Normal/Classic; replay identical actions | Zero **unexplained** diffs; known gaps listed | P3, G1 |
| E3 | Rule/scoring parity | Do all §5.3 scoring vectors produce identical results in both runtimes? | Table-driven vectors T-01…T-16, T-22, T-24 | Every vector identical | P3 |
| E4 | Bundle and start-up limits | Does the bundle fit the platform and start within acceptable time? | Measure compressed bundle, cold start, warm start | Within platform limit; latency recorded | P3 |
| E5 | Measured resources vs capacity model | What does one match cost in requests, CPU and storage? | Instrument a full match; build a per-match cost model | Model exists; capacity at target usage computed | P3, G5 |
| E6 | Primary usage at 90% | Is the primary usage scenario measurable at 90% of the free tier? | Drive a synthetic 90%-of-limit workload | Measured headroom or a documented overrun | P3, G5 |
| E7 | Backup lag, outbox, recovery, duplicate RP | Can the backup policy meet ≥90% without a second RP truth? | Inject lag, kill the primary, replay outbox | No duplicate RP; lag measured; recovery documented | P3, G4 |
| E8 | D1 atomicity, triggers, query limits | Does D1 provide the transactional guarantees settlement needs? | Batch atomicity tests, trigger behaviour, query-limit tests | Settlement can be atomic within documented limits | P3 |
| E9 | Durable recovery after interruption | Does a match survive process/match interruption? | Kill at trick/round/result/settlement boundaries | Consistent recovery at every boundary | P3, G4 |
| E10 | Delete/write costs and retention | Do retention and deletion fit request and storage limits? | Measure delete/write costs; model retention | Fits free tier; retention policy costed | P3, G5 |
| E11 | Anonymous → registered linking | Does Firebase linking preserve the uid and data? | Link a real anonymous account to email in a test project | UID stable; stats/history intact | P6 |
| E12 | Hibernation and duration cost | Does hibernation keep idle cost inside the free tier? | Idle table for a full match duration; measure | No billing trigger; duration cost recorded | P3 |
| E13 | Account deletion and store policy | What exactly must ship for deletion and closed testing? | Verify current Play policy pages and API behaviour | Written checklist with source URLs and access date | P10, G8 |

Stage 0 does **not** decide the architecture by itself. It produces measurements and a
recommendation; the owner approves or rejects the runtime through a new ADR (O-06).

### 5.4.4 Milestones

| Milestone | Definition of done |
|---|---|
| M0 | Baseline accepted; O-01…O-03 either decided or explicitly deferred with test placeholders |
| M1 | Stage 0 memo: every experiment has a result, a blocker, or a measured “no” |
| M2 | Engine rule freeze: §5.3 engine vectors green; rule documents unchanged except recorded defects |
| M3 | Ranked runtime and settlement accepted: exactly-once and recovery evidence |
| M4 | Matchmaking, bot fill and disconnect flows accepted |
| M5 | Stats and seasons accepted |
| M6 | Auth and account lifecycle accepted on device |
| M7 | Design sign-off: all 27 surfaces designed, portrait-first, EN/AR |
| M8 | Feature-complete Android build: all non-deferred surfaces wired |
| M9 | Hardening complete: localization review, backup drill, rollback rehearsal |
| M10 | Closed test complete: 12 testers × 14 continuous days; production access granted |
| M11 | Owner accepts release; signed bundle ready for staged rollout |

### 5.4.5 Dependency map

| Workstream | Depends on | Why |
|---|---|---|
| Engine rules | O-01 (Classic Dash+Risk), O-02 (fast equal-highest Super Call) | Those rules cannot be tested without an answer |
| Ranked runtime | Stage 0 E1–E10, ADR approval | No architecture may be assumed |
| Settlement | Runtime, engine freeze, backup policy | Settlement re-runs the engine and needs a durable store |
| Departure charges | Settlement, O-03 (7th takeover/ROOM) | Charges are applied at finish by the settlement path |
| Matchmaking | Runtime, auth | Queue and table membership need a stable identity |
| Bots/disconnect | Runtime, matchmaking | Bot fill and takeover share seat state |
| Statistics | Settlement records | Stats fold finished outcomes |
| Seasons | Statistics/profile store | Reset operates on the same profile |
| UI design | Engine freeze, runtime API shape | Designs must not encode wrong rules or unavailable data |
| Native conversion | Design sign-off, engine/services APIs | Conversion is a rendering/wiring step, not a design step |
| Closed testing | Feature-complete build, store assets, deletion path | Testers need a usable build and a compliant listing |
| Release | 14 continuous test days, production access, owner acceptance | Policy and owner gates |

### 5.4.6 Critical path

```
P0 → P1 Stage 0 ─────────────┐
        │                    ▼
        └────→ P2 engine → P3 runtime → P4 matchmaking → P5 stats → P6 auth
                                                      │
                                                      ▼
                                                   P7 design → P8 conversion → P9 hardening
                                                                                   │
                                                                                   ▼
                                                                     P10 closed test (14 days)
                                                                                   │
                                                                                   ▼
                                                                              P11 release
```

The critical path is **P1 → P3 → P7 → P8 → P10 → P11**. P2 runs in parallel with P1 and must
finish before P7. P4–P6 run after P3 and before P7’s sign-off where they change the UI contract.
The 14-day closed test and the review period are **elapsed time, not engineering time**; they
dominate the calendar tail and cannot be compressed by adding engineers.

### 5.4.7 Sequencing rules

1. **No rule is implemented twice.** The engine is the only rules implementation; the server
   runtime executes the same module (Stage 0 E1/E2).
2. **No design before rule freeze.** A beautiful screen for a wrong rule is rework.
3. **No conversion before design sign-off.** Compose work starts from approved portrait
   artboards, not from the 932×430 HTML mockups.
4. **No real-people testing before feature completeness.** Closed testing is the last step
   before publishing; using it as a beta for unfinished features burns the 14-day clock.
5. **Stage 0 does not reorder delivery.** It gates the runtime; it does not move design ahead of
   code or testing ahead of conversion.
6. **A blocked rule does not block unrelated work.** O-01 blocks Classic Dash+Risk only; O-02
   blocks the fast tie case only; O-03 blocks 7th-takeover charges only.

## 5.5 Work breakdown and estimates

### 5.5.1 Estimate basis

| Factor | Assumption |
|---|---|
| Unit | Engineering hours for one experienced Kotlin/Android engineer familiar with the codebase |
| Day | 8 productive hours; no calendar commitment implied |
| Included | Design analysis, implementation, tests, review, documentation of the change, local verification |
| Excluded | External waiting (store review, tester recruitment, 14-day closed test), owner decision time, procurement, third-party outages |
| Contingency | Embedded in the high end of each range; no separate percentage is added |
| Scale evidence | 252 Kotlin files, 1,361 existing tests across four modules, 56 emulator suites, 2,410 rules lines, 27 UI surfaces, 12 existing design screens |
| Comparability | The 2026-09-21 plan’s headline was **1,188 h** pre-contingency and **~1,366 h** after 15% for an overlapping but different scope (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:1326-1327`); this plan adds Stage 0, server-authoritative Ranked, all-mode statistics, full design and store work |
| Uncertainty | Low for infrastructure-dependent work (E1, E3), medium for engine/UI, higher for store timing |

The ranges are deliberately wide where the work depends on an unapproved architecture or an
unresolved rule. A single-point estimate would hide the two largest risks in the project.

### 5.5.2 Estimate summary

| Epic | Scope | Low (h) | High (h) | Confidence | Class | External gate |
|---|---|---|---|---|---|---|
| E0 | Baseline and decisions | 16 | 28 | High | Mandatory | Owner decisions O-01…O-03 |
| E1 | Stage 0 feasibility (13 experiments) | 150 | 260 | Low | Mandatory | Free-tier account; owner accepts memo |
| E2 | Rules and engine | 248 | 416 | Medium | Mandatory (part blocked) | O-01, O-02 |
| E3 | Ranked runtime and settlement | 280 | 504 | Low | Mandatory, conditional on ADR | Stage 0; O-06 |
| E4 | Matchmaking, bots, disconnect | 128 | 224 | Medium | Mandatory (part blocked) | O-03 |
| E5 | Statistics and seasons | 96 | 168 | Medium | Mandatory | None |
| E6 | Authentication and account | 56 | 96 | Medium | Mandatory | Firebase project access |
| E7 | UI design | 168 | 280 | Medium | Mandatory | Design sign-off |
| E8 | Native conversion | 288 | 480 | Medium | Mandatory | Design sign-off |
| E9 | Hardening and operations | 96 | 168 | Medium | Mandatory | None |
| E10 | Store readiness and real-people testing | 72 | 136 | Medium | Mandatory | 12 testers; Play review |
| E11 | Release candidate and acceptance | 40 | 72 | High | Mandatory | Owner acceptance |
| **Total** | | **1,638** | **2,832** | | | |

Planning midpoint: **2,235 engineering hours** (≈279 person-days). At 40 productive hours per
week this is **41–71 weeks** of engineering before external waits; at 50 h/week, **33–57 weeks**;
at 30 h/week, **55–94 weeks**. These are capacity arithmetic, not a schedule promise.

### 5.5.3 Epic detail

#### E0 — Baseline and decisions (16–28 h)

Goal: make the baseline reproducible and remove decision blockers from the critical path.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E0.1 | Verify clean build and test baseline on a fresh clone | `native/gradlew`; CI workflows | none | All four module suites reproduce the recorded counts | 4–6 | High |
| E0.2 | Decision pack for O-01, O-02, O-03 with examples and consequences | §5.7; `CANONICAL_RULES.md` | none | Owner answers recorded in a dated decision file | 6–10 | High |
| E0.3 | Wire test IDs T-01…T-25 into the task tracker and test names | §5.3 | E0.1 | Every P0 rule has an owner task and a test placeholder | 3–6 | High |
| E0.4 | Draft the runtime ADR skeleton for post-Stage 0 | New ADR file | E0.2 | ADR skeleton lists decision, alternatives, evidence required | 3–6 | High |

Commands: `native/gradlew -p native :engine:jvmTest :services:test :app:testDebugUnitTest`
and `npm run test:ci` from the repository root.

Evidence: build log, test counts, dated decision file. Gate: M0.

#### E1 — Stage 0 feasibility (150–260 h)

Goal: produce measured answers to the 13 questions in §5.4.3. This epic is an investigation; it
does **not** ship product code.

| Task | Experiment | Deliverable | Hours | Conf. |
|---|---|---|---|---|
| E1.1 | E1 engine under `workerd` | Harness + one full match under the runtime | 32–52 | Low |
| E1.2 | E2 seeded parity | 200+ seed corpus and diff report | 20–36 | Low |
| E1.3 | E3 scoring parity | Vector harness running in both runtimes | 14–24 | Low |
| E1.4 | E4 bundle/start-up | Measurements and limits note | 8–14 | Medium |
| E1.5 | E5 capacity model | Per-match cost model and capacity table | 14–24 | Low |
| E1.6 | E6 90% usage | Synthetic workload and headroom report | 10–18 | Low |
| E1.7 | E7 backup/outbox/duplicate RP | Fault-injection results and recovery note | 18–30 | Low |
| E1.8 | E8 D1 atomicity/limits | Atomicity and limit test report | 14–24 | Low |
| E1.9 | E9 interruption recovery | Boundary kill matrix and results | 10–18 | Low |
| E1.10 | E10 delete/write/retention costs | Cost and retention note | 4–8 | Medium |
| E1.11 | E11 auth linking | Linking result and uid evidence | 2–4 | Medium |
| E1.12 | E12 hibernation/duration | Idle-cost measurement | 2–4 | Medium |
| E1.13 | E13 policy verification | Checklist with source URLs and access date | 2–4 | High |

Commands are experiment-specific; the shared build command is
`native/gradlew -p native :functions:jsNodeTest`. Evidence: `stage0-report.md` with raw
measurements, not conclusions alone. Gate: M1 and G1.

#### E2 — Rules and engine (248–416 h)

Goal: make the engine match the binding rules, with tests that fail on today’s code.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E2.1 | Auction regression lock (min 4, Super ≥8, Dash max 2, confirmation) | `Bidding.kt`; `BiddingTest` | E0.1 | T-24 green; existing 473 tests still pass | 14–24 | High |
| E2.2 | Suit-aware With floor, Estimation Jump-In, Call Cap/13 Rule | `withFloorFor` (`Bidding.kt:273-274`); `forbiddenEstimateFor` | E2.1 | T-24 floor rows green | 24–40 | Medium |
| E2.3 | Fast Super Call re-declaration, repeated higher calls, tie placeholder | `Bidding.kt:560-625`, `:695-722` | O-02 | T-09 green; T-11 skipped with decision link | 32–52 | Medium |
| E2.4 | Normal and fast With/Wazz sets and numbers | `Bidding.kt`; scoring role assignment | E2.2 | T-08, T-10, T-12 green | 32–52 | Medium |
| E2.5 | General Pass consumes round, ladder ×2/×4/×6/×8, Classic no multiplier | `Bidding.kt:494-508`; client pass paths | E2.1 | T-01, T-02, T-22 green | 22–36 | High |
| E2.6 | Sa'ayda count, final-round repeat, cap removal, extension on success | `RoundScore.kt:222-238`; `Bidding.kt` | E2.5 | T-03, T-04, T-05 green | 26–44 | Medium |
| E2.7 | Normal Dash + Risk stacking | `RoundScore.kt:71-84`; `Scoring.kt:63-75` | E2.1 | T-06 green | 16–28 | High |
| E2.8 | Classic scoring verification and Classic Dash + Risk | `Scoring.kt:100-130` | O-01 | T-07 known rows green; combined row unblocked by O-01 | 24–40 | Medium |
| E2.9 | Shared Koz/King placement and RP input | `Placement.kt:212-222`; `RpEngine.kt:200` | E2.1 | T-13 green | 18–32 | High |
| E2.10 | Takeover clock, 7th counter, no 50 s clock, disconnect timing | `InactivityClock.kt:143-147`; takeover resolver | E2.1 | T-16 engine rows green; ROOM scope per O-03 | 22–36 | Medium |
| E2.11 | Vector suite and mutation checks | `native/engine/src/jvmTest` | E2.1–E2.10 | Every T-row has a failing-before/passing-after record | 18–32 | High |

Commands: `native/gradlew -p native :engine:jvmTest`, then the full CI command. Evidence: test
report, before/after diff for each fixed rule. Gate: M2, G2.

#### E3 — Ranked runtime and settlement (280–504 h)

Goal: make Ranked results authoritative and exactly-once, **if and only if** Stage 0 supports
the proposed runtime. This epic is conditional on ADR approval (O-06).

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E3.1 | Runtime scaffold for the approved architecture | New runtime package; `index.js` export map | E1, O-06 | Callable responds in staging; manifest matches exports | 48–88 | Low |
| E3.2 | Authoritative match state and move validation | Engine session; new state store | E3.1 | Illegal move rejected; state survives reload | 56–104 | Low |
| E3.3 | Settlement re-runs the engine and folds RP | `SettlementDb.kt:608-757`; `Settlement.kt:340` | E2 freeze, E3.2 | T-18 happy path green | 36–64 | Medium |
| E3.4 | Durable outbox, idempotency keys, recovery | Client outbox; server claim | E3.3 | T-18 duplicate/lost-response/process-death green | 40–72 | Low |
| E3.5 | Departure charges, abandonment, 7th takeover | `LeaveMatch.kt:50-56`; settlement | E3.3, O-03 | T-15/T-16 charge rows green | 28–48 | Medium |
| E3.6 | Four-report convergence and version gate | ROOM/UNRANKED result path; rules | E3.3 | T-17 green; stale client rejected | 24–44 | Medium |
| E3.7 | Fault injection: quota, timeout, kill, replay | Harness | E3.4 | No duplicate RP, no partial profile | 32–56 | Low |
| E3.8 | Adversarial review of the authority boundary | Threat model | E3.2–E3.6 | No client-forgeable ranked result | 16–28 | Medium |

Commands: `native/gradlew -p native :functions:jsNodeTest`, full CI, emulator suites
`npm run test:ci`. Evidence: fault-injection log, idempotency ledger, ADR approval. Gates: M3,
G3, G4.

#### E4 — Matchmaking, bots and disconnect (128–224 h)

Goal: make table formation and seat continuity work in all three modes without inventing rules.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E4.1 | Auto-Match queue and table join within ≤1 tier | `MatchmakingPort.kt:29-33`; new queue service | E3.1 | T-20 join/no-table rows green | 32–56 | Medium |
| E4.2 | 2-minute fill timer, 30 s vote, creator bots | New matchmaking service; `botProfileFor` (`TimeoutResolution.kt:417`) | E4.1 | T-20 vote rows green | 24–40 | Medium |
| E4.3 | Takeover counter, 7th removal, 60 s all-gone cancel | `InactivityClock.kt`; `TimeoutResolution.kt:245`, `:862` | E2.10, O-03 | T-16 green except ROOM 7th (open) | 28–48 | Medium |
| E4.4 | ROOM lifecycle: four-player start, join/ready/leave | `RoomService.kt:55`, `:234`, `:273`; `AppNav.kt:291` | E4.1 | T-17 room-size row green | 20–36 | Medium |
| E4.5 | In-match disconnect/vote/leave UI flows | New Compose overlays | E7 design (UI shell) | T-16 UI script passes | 24–44 | Medium |

Commands: `native/gradlew -p native :engine:jvmTest :services:test :app:testDebugUnitTest`; emulator
suites for the services paths. Evidence: vote logs, takeover matrix, cancellation trace. Gate: M4.

#### E5 — Statistics and seasons (96–168 h)

Goal: all-mode career statistics and a lazy 90-day season reset that cannot race settlement.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E5.1 | All-mode statistics, placement gate only for Ranked placement | `RankedStats.kt:103`; outcome fold | E3.3 | T-19 stats rows green | 32–56 | Medium |
| E5.2 | Lazy reset and two-step seasonal reset | `SeasonResetRun.kt:80`; `SeasonResetDb.kt:103` | E5.1 | T-19 season rows green | 28–48 | Medium |
| E5.3 | Reset/settlement race and idempotence | Season id/version; profile merge | E5.2, E3.4 | T-19 reset-race green | 20–36 | Medium |
| E5.4 | Profile data contract for the UI | Profile model; `ProfileScreen` | E5.1 | Nine values + rank available to UI | 16–28 | High |

Commands: `:functions:jsNodeTest`, emulator suites, `:engine:jvmTest`. Evidence: stats table for a
fixture account, reset run report, race test log. Gate: M5.

#### E6 — Authentication and account (56–96 h)

Goal: a guest can play ROOM; a registered user can play Ranked; linking preserves identity; an
account can be deleted.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E6.1 | Firebase project wiring and google-services plugin | `native/app/build.gradle.kts`; `google-services.json` (not committed) | Project access | App builds against the real project on device | 8–14 | Medium |
| E6.2 | Email sign-up and Google sign-in UI | `AuthRepository.kt:19-20`; new screens; OAuth consent/fingerprints | E6.1 | New email account and Google account both sign in | 20–34 | Medium |
| E6.3 | Guest gate for Ranked/Auto-Match | Navigation and queue guard | E6.2 | T-21 guest rows green | 6–10 | High |
| E6.4 | Anonymous → registered linking | `linkWithCredential` path | E6.2 | T-21 linking row green; uid stable | 10–18 | Medium |
| E6.5 | In-app account deletion | Profile/settings; backend delete | E6.2 | T-21 deletion row green | 8–14 | Medium |
| E6.6 | Web deletion resource | Hosted page | E6.5 | URL reachable without the app | 4–6 | High |

Commands: app unit tests; manual device walkthrough recorded as evidence. Gate: M6, G7.

#### E7 — UI design (168–280 h)

Goal: design all 27 surfaces portrait-first, against frozen rules and the real API shape, in
English and Arabic.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E7.1 | Portrait design system and grid | New artboard library | M2 | Tokens, type, spacing, RTL rules | 24–40 | Medium |
| E7.2 | Core 12 screens | `docs/design/`; S01–S12 equivalents | E7.1 | Every core screen has a portrait artboard | 48–80 | Medium |
| E7.3 | Ranked, placement, matchmaking, seasons | S45–S56 surfaces | E7.1 | Screens/states for rank chip, queue, placement, season label | 32–52 | Medium |
| E7.4 | In-match overlays: vote, WAIT, takeover, leave | S63/S72/S74 surfaces | E7.1 | Every overlay has default/loading/error states | 24–40 | Medium |
| E7.5 | Profile, nine statistics, history | S03/S44 surfaces | E7.1 | Nine stats + percentages designed | 20–36 | Medium |
| E7.6 | Arabic copy, RTL, terminology review | Strings and artboards | E7.2–E7.5 | Native-speaker sign-off; no dropped placeholders | 20–32 | Medium |

Files: `docs/design/*.html`, `docs/UI_UX_ROADMAP.md`, `native/app/src/main/res/values/strings.xml`,
`values-ar/strings.xml`. Evidence: signed design checklist per surface; portrait screenshots.
Gate: M7.

#### E8 — Native conversion (288–480 h)

Goal: convert approved designs into Compose and wire every non-deferred surface to real state.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E8.1 | Navigation and state scaffold for 11 routes | `AppNav.kt`; `Routes.kt` | M7, E6 | Every route reachable; no dead preview | 32–52 | Medium |
| E8.2 | Core screens | `native/app/src/main/java/com/estemshan/game/ui` | E8.1 | Core flows usable end to end | 64–104 | Medium |
| E8.3 | Ranked, placement, matchmaking, seasons | New screens/view models | E8.1, E4, E5 | Ranked flow completes on device | 48–80 | Medium |
| E8.4 | In-match UI: auction, estimates, tricks, overlays | Match screens/view models | E8.1, E2 | Full match playable; overlays behave | 56–92 | Medium |
| E8.5 | Profile and statistics | Profile/stat composables | E8.1, E5 | Nine stats with percentages render | 32–56 | Medium |
| E8.6 | Arabic, RTL and accessibility | Resources; layout direction; semantics | E8.2–E8.5 | EN/AR screenshots; TalkBack pass | 32–56 | Medium |
| E8.7 | Compose/UI tests | `androidTest` (new) | E8.2–E8.6 | Critical flows covered; CI runs them | 24–40 | Medium |

Commands: `native/gradlew -p native :app:assembleDebug :app:testDebugUnitTest`; instrumented job
for the new `androidTest` suite. Evidence: device screenshots per surface, UI test report,
accessibility checklist. Gate: M8.

#### E9 — Hardening and operations (96–168 h)

Goal: make the release operable, observable and recoverable.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E9.1 | Localization review and placeholder cleanup | EN/AR resources | E8.6 | No placeholder strings; native-speaker sign-off | 20–36 | Medium |
| E9.2 | Observability and alerts | Runtime logs/metrics | E3 | Settlement failures and quota errors visible | 20–36 | Medium |
| E9.3 | Backup drill and restore evidence | Backup config; runbook | E3.4 | ≥90% coverage demonstrated; restore tested | 16–28 | Medium |
| E9.4 | Rollback and incident runbook | New runbook | E9.2 | Rollback rehearsed; on-call steps written | 16–28 | High |
| E9.5 | Startup/performance check | App and runtime | E8 | No regression against Stage 0 E4 measurements | 12–20 | Medium |
| E9.6 | Security/privacy review | Data flows; rules | E3, E6 | No unapproved data collection; deletion verified | 12–20 | Medium |

Commands: release build, fault drill, restore drill. Evidence: drill logs, runbook, alert
screenshot. Gate: M9, G4, G9.

#### E10 — Store readiness and real-people testing (72–136 h)

Goal: satisfy Play policy and run the mandatory closed test without resetting the 14-day clock.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E10.1 | Listing, screenshots, feature graphic, descriptions | `docs/release/play-listing.md`; store assets | E8 | Listing complete in EN/AR | 12–20 | Medium |
| E10.2 | Data safety and deletion declarations | Play Console; privacy page | E6.5/E6.6 | Declarations match actual data flows | 12–20 | High |
| E10.3 | Recruit and manage ≥12 testers | Owner + release checklist | E10.1 | 12 personal accounts opted in | 12–24 | Low |
| E10.4 | 14-day closed test support | Release candidate | E10.3 | Continuous 14 days; feedback triaged | 20–40 | Medium |
| E10.5 | Production access application | Play Console | E10.4 | Access granted | 8–16 | Low |
| E10.6 | Reviewer credentials and access notes | Console | E6.2 | Working login verified by a second person | 8–16 | High |

Policy facts used here (verified 2026-10-22): closed testing requires **12 testers opted in
continuously for 14 days** on personal accounts created after 2023-11-13, followed by a
production-access application; review usually takes **up to 7 days**; testers must be able to
sign in, so working credentials are required. Account deletion requires an **in-app path**, a
**web-accessible deletion resource**, and accurate **Data safety** declarations. The deletion
deferral is a recorded **risk, not compliance**.

Evidence: Play Console tester list and dates, production-access approval, deletion walkthrough,
credential test. Gate: M10, G8.

#### E11 — Release candidate and acceptance (40–72 h)

Goal: ship the accepted build with a rehearsed rollback.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E11.1 | Signed release build, versioning, bundle | `build.gradle.kts`; signing config (not committed) | M10 | Signed AAB installs and runs | 12–20 | High |
| E11.2 | Staged rollout plan | Play Console | E11.1 | Rollout percentages and halt criteria written | 8–16 | High |
| E11.3 | Post-release monitoring window | Dashboards | E11.2 | First 72 h monitored against criteria | 8–16 | Medium |
| E11.4 | Owner acceptance | Release checklist | E11.3 | Written sign-off | 12–20 | High |

Evidence: signed bundle hash, rollout record, acceptance sign-off. Gate: M11, G11.

### 5.5.4 Engineering time and elapsed time

| Capacity | Low (1,638 h) | High (2,832 h) |
|---|---|---|
| 30 productive h/week | 55 weeks | 94 weeks |
| 40 productive h/week | 41 weeks | 71 weeks |
| 50 productive h/week | 33 weeks | 57 weeks |

External waits are **not** in those numbers:

| Wait | Duration | Can start before RC? |
|---|---|---|
| Tester recruitment (12 personal accounts) | 2–8 weeks; unknown | Yes, start at P7 |
| Closed test | **14 continuous days** | No — starts only with the RC |
| Production access review | Usually ≤7 days; plan 7–14 | No |
| First-release store review | Not guaranteed; plan a buffer | No |
| Firebase project access and configuration | 1–3 days | Yes |
| Web deletion page hosting | 1–2 days | Yes |

Calendar tail after feature completeness is therefore **at least 3 weeks** and realistically
**6–10 weeks** once recruitment, review and one feedback iteration are included. The 2026-09-21
plan recorded only **4 testers available**; if that is still true, recruiting 8 more is the
single longest external dependency.

### 5.5.5 What is not estimated

| Item | Why not estimated | Where it sits |
|---|---|---|
| Leaderboard | Deferred by owner | §5.7 deferred |
| Voice chat | No current gate, no code; old plan assumed it | O-08 |
| Monetization (ads/IAP) | No current gate, no code | O-09 |
| Web client / iOS | Out of V1 | §5.1.2 |
| App Check / Play Integrity | Deferred after V1 (Q21b) | §5.7 deferred |
| Tournament/social features | Not in scope | §5.1.2 |
| Legal review, company/account setup | Owner/external, not engineering | §5.7 |
| Ongoing live-ops and support | Post-release | §5.8 |

### 5.5.6 Confidence and what would invalidate the estimate

- **Stage 0 failure (E1/E3).** If the engine cannot run in the proposed runtime, E3 is
  re-estimated from scratch, not patched. The 280–504 h range assumes the proposal works.
- **Rule decisions (O-01…O-03).** An answer broader than the examples in §5.3 expands E2 and
  E4; an answer that changes a scoring table expands E2.8.
- **Design direction.** If portrait-first design is rejected, E7/E8 rework invalidates the
  medium confidence in those epics.
- **Testers.** If 12 testers cannot be recruited, V1 cannot ship regardless of engineering; the
  estimate is not the constraint.
- **Architecture approval.** Any architecture other than the proposal is a new estimate, not a
  variant of this one.

## 5.6 Failure modes and data integrity

### 5.6.1 Integrity principles

1. **One RP source of truth.** A backup is a copy, never a second authority. No client and no
   cache may compute a competing RP total.
2. **A stored result is not automatically trustworthy.** Storage proves only that bytes were
   written. It does not prove that the score was computed correctly, that the reporter was
   honest, or that the match happened as reported.
3. **Four matching reports are not proof.** In ROOM and UNRANKED, four clients can fabricate the
   same result or collude. V1 accepts this because no RP is at stake and records the limitation;
   it does not accept it for RANKED, where the server computes the result.
4. **Exactly once.** Every settlement carries an idempotency key; the server stores the key and
   the result in the same transaction as the RP delta.
5. **No partial writes.** Result, placement, RP and ledger entries commit together or not at all.
6. **Durable intent.** The client persists “this match needs settling” before attempting the
   call; a process death cannot erase the intent.
7. **Fail closed, never charge for infrastructure failure.** Server or free-quota failure never
   costs a player Koz or RP.
8. **One clock.** Authoritative timers use server time; client countdowns are display only.
9. **Recovery is bounded and observable.** Every interruption has a defined resume point and an
   alert if it does not resume.

### 5.6.2 Failure-mode table

| ID | Failure | Detection | Integrity impact | Mitigation | Test | Residual risk |
|---|---|---|---|---|---|---|
| F-01 | Free-tier/quota exhaustion | Runtime error, quota metric | Settlement cannot complete | No charge; pending result; alert; retry after quota resets | T-18 quota row | Results delayed until quota resets |
| F-02 | Primary store unavailable or slow | Health check, latency alert | Writes fail or lag | Outbox; retry; backup policy ≥90%; one source of truth | E7/E9 drills | Loss window equal to backup lag |
| F-03 | Result delivered late (offline client) | Pending outbox on launch | Stale result could land after season change | Season id/version on the write; stale rejected or applied to its season | T-18 season race | Late result may be rejected and need support |
| F-04 | Result stored, RP not written | Reconciliation count mismatch | Player sees result without RP | Same transaction for result + RP; reconciliation job alerts on mismatch | T-18 partial row | None if transaction holds; else manual repair |
| F-05 | RP written, result not stored | Reconciliation mismatch | RP without a match record | Same transaction; ledger is the audit source | T-18 partial row | None if transaction holds |
| F-06 | Duplicate retries double RP | Ledger duplicate key | Inflated RP | Idempotency key + unique ledger entry | T-18 duplicate | None |
| F-07 | Replayed or fabricated Ranked request | Auth uid, signature/version | Forged result | Server computes; client reports advisory only; reject unknown match ids | T-18 replay | Compromised account can still act as itself |
| F-08 | Fabricated/collusive ROOM or UNRANKED result | Four-report comparison | False stats; no RP | Accept only four matching reports; document limitation; no RP | T-17 collusion | **Accepted**: four colluding clients can fake a result |
| F-09 | Process dies with timers active | Restart, timer state | Double timeout or lost timeout | Server-time deadlines; expiry resolved once per deadline | T-16 kill rows | Brief UX pause during recovery |
| F-10 | Reconnect races takeover | Seat claim conflict | Two owners of one seat | Atomic claim; one winner; loser gets spectator/wait state | T-16 reclaim race | Player may briefly see a bot |
| F-11 | Reconnect races cancellation | Cancel and return simultaneous | Match cancelled for a player who returned | Single transaction checks return before cancel; 60 s window | T-16 all-gone | Race window at the boundary |
| F-12 | Restart mid-trick or mid-round | Recovery on launch | Partial trick/round state | Commit at trick boundary; replay from last committed; no score until round end | T-16 kill matrix | Replayed trick animation |
| F-13 | Restart mid-result or mid-settlement | Outbox present | Lost result | Outbox replays; idempotency dedupes | T-18 process death | Delay until next launch |
| F-14 | Duplicate departure penalty | Same match+seat charge twice | Double Koz | Charge once at finish, keyed by match+seat; leave/Vote Kick mutually exclusive | T-15 | None |
| F-15 | Season reset races settlement | Season id/version mismatch | RP applied to wrong season or reset twice | Claim/resume protocol; stale writes rejected; single reset per season | T-19 reset race | Operator repair if partial |
| F-16 | Version skew (old app, new backend) | Min-client field | Old client writes invalid state | Reject below minimum; force update message; version gate | T-18 stale client | Users on old builds cannot play Ranked |
| F-17 | All humans leave or disconnect | Presence + 60 s timer | Abandoned match never resolved | Immediate cancel on last explicit leave; 60 s on all-disconnect; all Koz per T-15 | T-15/T-16 | Ambiguity between leave and network loss (Q27b) |
| F-18 | Clock skew | Server vs client timestamps | Early/late expiry | Server timestamps; client display offset | T-16 | Cosmetic countdown drift |
| F-19 | Duplicate report from one uid | uid repeated | Fake quorum | Dedupe by uid; one report per seat | T-17 duplicate | None |
| F-20 | Corrupt local state or restore from backup | Checksum/validation | Wrong match resume | Validate against server; discard local if divergent; restore drill | E9 restore drill | Lost local-only ROOM state |

### 5.6.3 Explicit trust statement

> **A stored result is not automatically trustworthy.** For RANKED, trust comes from the server
> computing the result from validated moves. For ROOM and UNRANKED, trust comes only from four
> clients agreeing; **four matching reports can be fabricated by one client controlling four
> accounts or by four colluding players**. V1 accepts that limitation because those modes carry
> no RP. It must be stated in the release notes and must never be described as “verified” in the
> UI or store listing.

### 5.6.4 Recovery matrix

| Kill point | RANKED expected state | ROOM/UNRANKED expected state |
|---|---|---|
| Mid-trick, before commit | Last committed trick; trick replays | Same |
| After trick commit, before broadcast | Trick committed; clients resync | Same |
| Mid-round, before score | Round in progress; no partial score | Same |
| After round score, before next deal | Round committed; next round deals | Same |
| After match end, before result | Result pending; settlement retries | Four-report convergence pending |
| After result, before RP | Transaction prevents this state; if it occurs, reconcile | No RP; result recorded once |
| After RP, before response | Retry returns stored result; no second delta | n/a |
| During outbox replay | Dedupe by key | Dedupe by key |
| During departure charge | Charge once at finish | Charge once at finish |
| During season reset | Claim/resume; no double migration | n/a |

### 5.6.5 Deletion and retention integrity

Deleting an account must not leave a live RP-bearing profile that can be resurrected by a stale
client or a queued outbox entry. The deletion path must remove or anonymize the profile, cancel
pending outbox entries and invalidate sessions. How long anonymized match records are retained
for integrity and dispute purposes is **not specified** in the current decisions; it is owner
decision O-10 and must be settled before the Data safety declaration is submitted.

## 5.7 Open decisions and deferred work

### 5.7.1 Answered questions — do not ask again

These are owner-approved or otherwise settled. This plan implements them; it does not reopen
them. Labels: **Owner-approved**, **Owner-approved (supersedes …)**, **Deferred beyond V1**,
**Engineering investigation**.

| Topic | Decision | Label | Source |
|---|---|---|---|
| General Pass | Consumes the round; Normal ×2; Classic no multiplier; ladder ×2→×4→×6→×8 | Owner-approved | Q1, Q1b, A6.4 |
| Sa'ayda | Per-round extension in 14–18; final-round repeat until a winner; no caps; MINI mirrors 6–10 | Owner-approved (supersedes earlier final-round-only wording) | Q2, Q2b, Q2c, A6.2 |
| Koz on ties | All tied at the lowest are Koz unless all four tie; shared Koz gets the same last-place RP | Owner-approved (supersedes RD17 last sentence) | Q2d, Q2f, A6.2 |
| Vote Kick charge | **1 Koz**, RANKED and UNRANKED; unique Koz only; no ROOM vote | Owner-approved (supersedes Current Rules :209 and A6.7) | A5.1, Q2e |
| Super Call extension | Only when the Caller succeeds | Owner-approved | Q3 |
| Classic Dash + Risk | Adds both components; exact numbers confirmed later | Owner-approved principle, **numbers open O-01** | Q4, A6.8 |
| Sign-in methods | Google sign-in and email sign-up in Android V1 through Firebase Auth; no Play Games | Owner-approved | Q5, decisions §D |
| Account deletion | Deferred in the old plan; **not compliant**; in-app path + web link + declarations required | Owner-approved risk, compliance work mandatory | Q6, decisions §E, controller ruling |
| Ranked bot settings and Auto-Match | Creator chooses bots; Auto-Match joins a player-created table; joiner sees settings | Owner-approved | Q7, Q7b, Q7c, Q26 |
| Bot fill timing | 2 minutes from first player; ready = connected and seated; 30 s vote; >half of waiting humans; non-votes reject | Owner-approved | Q8, decisions §F, Current Rules :211 |
| RP with bots | Humans get normal RP; opponent strength from humans only | Owner-approved | Q9 |
| Disconnect and takeover | Server detects; decision timer; 10 s more; bot takes over; takeover counter increments only for bot takeover | Owner-approved | Q10, Q10b, Q10c |
| 7th takeover | Removed from the match, 2 Koz, no account ban | Owner-approved (ROOM scope open O-03) | Q11 |
| Architecture approval | ADR after Stage 0 succeeds; ADR text not yet approved | Owner-approved process, **decision open O-06** | Q12 |
| Backup at 90% | Backup hosts new matches; running matches stay; results wait for one central RP database | Owner-approved | Q13, decisions §I |
| ROOM authority | App-managed; server records final result only; four matching reports | Owner-approved | Q15, Q15b, Q24 |
| UNRANKED | No RP; enters all statistics; four matching reports | Owner-approved (reverses A3.5) | Q14c, Q15c |
| Departure charges | ROOM 1 Koz; RANKED/UNRANKED 2 Koz; at finish, if not returned | Owner-approved | Q14b, A6.6 |
| ROOM size | Full table of four; no 2–3 player start | Owner-approved (supersedes A3.1) | Q16, A6.8 |
| Statistics | Counts and percentages over Games Played, aggregated across all modes | Owner-approved | Q17, A6.8 |
| Data retention | Keep final result, RP ledger and idempotency keys; delete action history | Owner-approved | Q18 |
| With rules | Normal: winning-suit alignment, floor = last number in that suit. Fast: exact highest number plus final-trump condition | Owner-approved (supersedes engine) | Q19, Q19c, Q28, Q29 |
| Outage | Timers freeze and resume; no timeout charged | Owner-approved | Q20 |
| Auth gate and App Check | Ranked requires a registered account; App Check/Play Integrity deferred past V1 | Owner-approved / Deferred | Q21, Q21b |
| Seasons | Lazy reset, no inactive-player records, 90-day seasons, two-step reset | Owner-approved | Q22 |
| Everyone-left | Last explicit leave cancels immediately; all disconnected waits 60 s; all Koz; RP = last-place loss; server/quota exempt; ROOM/UNRANKED Koz without RP | Owner-approved | Q27, Q27b, Q27c |
| Vote Kick (duplicate) | Q14d still says 2 Koz; **A5.1 controls at 1 Koz** | Stale answer, documentation defect D-12 | Q14d |

Q23 and Q25 do not exist in the register; §A of `OPEN_QUESTIONS.md` is empty. There is no
unanswered question hiding under those numbers.

### 5.7.2 Owner decisions required

| ID | Question | Concrete example | Why it blocks | Blocks |
|---|---|---|---|---|
| O-01 | Exact Classic Normal Dash + Risk numbers | Classic, total bids 15, Risk 10, seat is Normal Dash and takes 2. Normal Dash failure over = −T = −2; Risk failure = −(miss + 10) = −12. “Add both” gives **−14**. A Wizz-Risk reading gives **−22**. Success under: +23 + 10 = **+33**. The owner must confirm which arithmetic is the rule | E2.8 cannot be implemented or tested; T-07 has a blocked row | E2.8, T-07 |
| O-02 | Fast-round equal-highest Super Call tie | Fast round: A declares 8♥, B declares 8♠, C and D lower. Who is Caller? Which trump is final? Is B With? Does the extension trigger? Current code uses seat order, which is an invention | Fast auction, With/Wazz, extension and T-11 all depend on it | E2.3, T-11 |
| O-03 | 7th bot-takeover in ROOM and its interaction with the disconnect vote/WAIT | A player reconnects six times; on the 7th takeover, RANKED/UNRANKED record 2 Koz. In ROOM, is it 2 Koz, 1 Koz, or 0? If a ROOM/UNRANKED 30 s vote is open or a 90 s WAIT is running, does the 7th takeover fire, wait, or reset the cycle? | E4.3 and E3.5 cannot apply a charge or timing rule in ROOM without an answer | E4.3, E3.5, T-16 |
| O-03c | ROOM permanent disconnect replacement charge | A5.1’s table says ROOM replacement = **no Koz** (`:446`); A6.6 says ROOM leave “for any reason” = **1 Koz**. Which controls? | Settlement would charge or not charge the same event differently | E3.5 |
| O-04 | Are bots allowed in ROOM? (C18) | A ROOM has 3 humans and one leaves; may the configured bot fill the fourth seat? Do bot-filled ROOM matches count in the nine statistics? | ROOM E2E and stats aggregation | E4.4, E5.1 |
| O-05 | Arabic rank/stat terminology | S47 labels Platinum as **وزير** (vizier) and marks it approved in the annotation. Is that the intended Arabic rank word, and what are the nine statistic labels? | Localization sign-off and store listing | E7.6 |
| O-06 | Approve or reject the proposed runtime after Stage 0 | Cloudflare Workers + DO + D1 is a proposal. If Stage 0 passes, approve it by ADR; if it fails, choose a fallback that still meets $0/no-Blaze and Ranked authority | E3 cannot start implementation without an architecture | E3, ADR |
| O-07 | RD9 pool vs RD29 band for Auto-Match | RD9 says a pool; RD29 says joiner and creator are ≤1 tier apart. Two players two tiers apart: same pool or not? | Queue eligibility and T-20 | E4.1 |
| O-08 | Is voice in V1? | The 2026-09-21 plan included push-to-talk in V1; the current gates are silent; there is no voice code | Scope and estimate; not included in this plan’s hours | Scope |
| O-09 | Is monetization in V1? | No ads/IAP code; no billing account; old plan assumed AdMob | Scope, policy and $0 constraint | Scope |
| O-10 | What remains after account deletion? | Q18 keeps result, RP ledger and idempotency keys; deletion must remove the profile and sessions. Are anonymized match rows retained, and for how long? | Data safety declaration and deletion implementation | E6.5, G8 |
| O-11 | Are the RP balance constants final? | `RpEngineConfig` defaults (King +30, Second +15, Third −8, Koz −20) are marked placeholders pending a balance pass (RD4). Tests assert mapping and arithmetic against the config, not a final balance | The owner must approve the defaults or supply final numbers before Ranked can be called released | E2.9, G3 |

None of these has a default. Where the plan needed a value to keep writing, it used a **blocked
test placeholder**, not a guess.

### 5.7.3 Engineering investigations (not owner decisions)

These are the T1–T9 experiments from `OPEN_QUESTIONS.md` §C, mapped into Stage 0. They are
measured, not voted on.

| ID | Investigation | Stage 0 | Output |
|---|---|---|---|
| T1 | Engine ES module in `workerd` + ≥200-seed parity | E1, E2, E4 | Zero diffs; bundle/start-up numbers |
| T2 | Delete cost: row deletes vs `deleteAll()`/table drop | E10 | Rows written per match with/without cleanup |
| T3 | Quota accounting readable at 90% | E6 | Counter error vs dashboard |
| T4 | Backup lag/outbox cannot award RP twice | E7 | Fault-injection result |
| T5 | D1 batch atomicity, trigger abort, query cap | E8 | Atomicity/limit report |
| T6 | Durable recovery mid-trick and between result and D1 | E9 | Replay equality; one ledger row |
| T7 | Anonymous→registered link keeps uid | E11 | UID and claim evidence |
| T8 | Hibernation/duration billing for a shared host | E12 | GB-s per match |
| T9 | Current Play account-deletion policy | E13 | Citation with access date |

### 5.7.4 Deferred beyond V1

| Item | Status | Basis |
|---|---|---|
| Public leaderboard | Deferred (owner) | Decisions §K; no board in V1 |
| Inactive-player season records / season board | Not needed in V1 | No board |
| App Check / Play Integrity | Deferred past V1 | Q21b |
| Web client | Out of scope | Android-only V1 |
| iOS | Out of scope | Android-only V1 |
| ROOM/UNRANKED server authority | Later release; no committed project | Q24 |
| Match history and seasonal trophies | Not in V1 | RD23; Q18 keeps only result/ledger/keys |
| Voice chat | Conditional on O-08; not estimated | Old plan included it, current gates silent |
| Monetization | Conditional on O-09; not estimated | No code, no billing |
| Tournaments, friends, spectating | Out of scope | §5.1.2 |

### 5.7.5 Spec gaps versus implementation bugs

A **spec gap** is a rule the sources do not settle. It cannot be fixed in code; it needs an
owner answer. An **implementation bug** is a rule the sources settle but the code violates. It
can be fixed and tested now.

| Spec gap | Where the sources are silent or contradictory | Owner question |
|---|---|---|
| SG-01 Fast equal-highest Super Call | No rule for equal 8+ declarations | O-02 |
| SG-02 7th takeover in ROOM / vote interaction | Decisions §G vs A5.2 | O-03 |
| SG-03 Classic Dash + Risk numbers | A6.8 says numbers confirmed later | O-01 |
| SG-04 ROOM disconnect replacement charge | A5.1 `:446` vs A6.6 `:532` | O-03c |
| SG-05 ROOM bots | C18 open; A6.8 requires four | O-04 |
| SG-06 RD9 pool vs RD29 band | Two different matchmaking scopes | O-07 |
| SG-07 Retention after deletion | Q18 retention vs deletion requirement | O-10 |
| SG-08 Voice/monetization in V1 | Old plan includes; current gates silent | O-08/O-09 |
| SG-09 “Everyone left” vs network loss | Q27b tension between leave and disconnect | Partly O-03; implementation follows Q27c |
| SG-10 Arabic rank/stat labels | S47 annotation vs terminology review | O-05 |

| Implementation bug | Binding rule | Code evidence | Task |
|---|---|---|---|
| IB-01 Fast Super Call resolves once; later calls do not re-open | A6.10/A6.11 | `fastSuperResolved` (`Bidding.kt:594`, `:609`) | E2.3 |
| IB-02 Only pre-Caller seats are wiped | A6.11/Q29 | `preSuper` wipe (`Bidding.kt:575-585`) | E2.3 |
| IB-03 Every 8+ becomes With | A6.10/Q28 | `superCandidates.drop(1)` (`:716`) | E2.4 |
| IB-04 With floor is suit-blind | A6.9/Q19 | `withFloorFor` (`:273-274`) | E2.2/E2.4 |
| IB-05 General Pass replays the same round | A6.4 | client pass handlers | E2.5 |
| IB-06 Multiplier skips ×6 | Current Rules :203, Q1b | `minOf(prev*2, 8)` (`:496`) | E2.5 |
| IB-07 Classic General Pass wrongly doubles | A6.4 | `Bidding.kt:496` path | E2.5 |
| IB-08 Sa'ayda caps and no final-round repeat | A6.2/Q2c | `computeRoundExtension` (`RoundScore.kt:222-238`) | E2.6 |
| IB-09 Normal Dash + Risk drops Risk | A6.3 | `normalRoleFor` (`:79`) | E2.7 |
| IB-10 Tied lowest becomes Third | Q2d | `placementOutcome` (`Placement.kt:218-222`) | E2.9 |
| IB-11 ROOM departure records no Koz | A6.6, controller ruling | `decideLeave` (`LeaveMatch.kt:56`) | E3.5 |
| IB-12 Statistics are Ranked-only and placement-gated | Q14c/Q17 | `accumulateRankedStat` (`:103`) | E5.1 |
| IB-13 Takeover uses a 50 s clock, no counter | Q10b/Q10c | `InactivityClock.kt:143-147` | E2.10/E4.3 |
| IB-14 Settlement retries live only in memory | Decisions §I | `MAX_TRANSIENT_FAILURES` (`:1312`) | E3.4 |
| IB-15 Season reset is a 10,000-profile bulk sweep | Q22 lazy reset | `MAX_RESET_PROFILES` (`SeasonResetDb.kt:103`) | E5.2 |

## 5.8 Release gates and operational readiness

### 5.8.1 Gate rules

A gate is a **go/no-go decision backed by evidence produced for that gate**. The following never
satisfy a gate:

- a documentation change, including this plan;
- a successful compile or a green build with no test evidence;
- a green unit suite that encodes the old, incorrect rule;
- “it works on my device” without a recorded device/OS matrix;
- a promise that a policy requirement will be handled after launch.

Every gate has an owner, an evidence bundle and a named disqualifier. A failed gate stops the
release; it is not averaged against passing gates.

### 5.8.2 Gate table

| Gate | Question | Pass criteria | Evidence | Owner | Disqualifier |
|---|---|---|---|---|---|
| G0 — Baseline | Is the starting point reproducible? | Clean clone builds; recorded suite counts reproduce | CI log, test counts | Eng | Build depends on local state |
| G1 — Stage 0 and parity | Is the proposed runtime feasible? | All 13 experiments have results; ≥200 seeded matches with zero unexplained diffs; bundle/startup within platform limits; recommendation written | `stage0-report.md` with raw data | Eng + owner | “Mostly works”; unexplained diffs; no capacity model |
| G2 — Rule coverage and regression | Are the V1 rules implemented and tested? | Every P0 rule in §5.3 has a passing test; all pre-existing suites pass or have documented equal replacements; no skipped P0 test except O-01/O-02 with a recorded deferral | Test report mapped to T-IDs | Eng | A rule “verified by reading”; skipped test counted as pass |
| G3 — RP integrity | Is RP correct and single-sourced? | T-13/T-15/T-19/T-20 vectors pass; one RP source; no double application under retry | Vector report + ledger audit | Eng | Two stores can award RP; client computes RP |
| G4 — Recovery and duplicate settlement | Can the system survive interruption? | T-18 and §5.6 recovery matrix pass; duplicate settlement impossible; quota failure never charges | Fault-injection log | Eng | Any confirmed double RP or lost settled match |
| G5 — No billing and free tier | Does V1 run at $0? | No billing account; no Blaze; measured usage at 90% of the primary scenario within free limits | Billing console + Stage 0 E5/E6/E10/E12 | Owner + eng | Any billing link; over-limit without a documented plan |
| G6 — Architecture approval | Is the runtime approved? | ADR approved by owner after G1; supersedes ADR 0002 where needed | Signed ADR | Owner | Implementation proceeding on an unapproved proposal |
| G7 — Auth and account | Can the right people get in and out? | Guest gate, email sign-up, Google sign-in, linking, deletion path pass on device | Device matrix + deletion walkthrough | Eng | Google or sign-up missing; deletion only promised |
| G8 — Store policy | Can the app be published? | 12 testers opted in **continuously for 14 days**; production access granted; reviewer credentials verified by a second person; Data safety and deletion URL live; listing accurate | Play Console records + credential test | Owner | Fewer than 12 testers; clock reset; no working credentials; deletion risk unsigned |
| G9 — Production configuration | Is the release configured safely? | Secrets not in VCS; rules deployed; version gate on; backup ≥90%; alerts on; environment separation verified | Config review checklist | Eng | Test project in production; open rules; no alerts |
| G10 — Rollback and incident response | Can a bad release be stopped? | Rollback rehearsed; incident runbook; halt criteria; owner reachable | Drill log + runbook | Eng + owner | No rollback path; no runbook |
| G11 — Owner acceptance | Does the owner accept V1? | V1-C1…V1-C14 met; written sign-off | Acceptance record | Owner | Verbal acceptance; open P0 defect |

### 5.8.3 Policy gate detail (G8)

The policy facts below were verified on **2026-10-22** and must be re-verified before submission
because Play policy changes.

| Requirement | V1 action | Evidence |
|---|---|---|
| Closed testing | **12 testers opted in continuously for 14 days**, personal accounts created after 2023-11-13 | Console tester list with opt-in dates |
| Production access | Apply after the 14 days; review **usually ≤7 days** | Approval record |
| Low engagement / fewer than 12 | Continued testing required; the 14-day clock is not satisfied | Console status |
| Reviewer access | Working sign-in credentials and instructions | Second-person login test |
| Account deletion | In-app path + web-accessible resource + Data safety declarations | Walkthrough + URL + declaration screenshot |
| Deletion risk | The old deferral is a **recorded risk, not compliance**; owner signs that the path ships | Signed risk/acceptance note |
| Data safety | Declarations match actual collection, retention and deletion | Completed console form |
| Target API level | Re-verify the current requirement at submission; the app currently targets SDK 36 | Policy page + build config |

Sources (accessed **2026-10-22**):

- “App testing requirements for new personal developer accounts” —
  <https://support.google.com/googleplay/android-developer/answer/14151465?hl=en>
- “Understanding Google Play’s app account deletion requirements” —
  <https://support.google.com/googleplay/android-developer/answer/13327111?hl=en>

The older “20 testers” figure is **not** the current page text and must not be used. The plan
uses 12 testers / 14 continuous days.

If the 2026-09-21 note that only **4 testers** were available is still true, G8 is the project’s
hardest external gate and should start before the RC exists.

### 5.8.4 Production configuration review (G9)

| Item | Check |
|---|---|
| Secrets | No service keys, signing keys or `google-services.json` secrets in version control |
| Rules | Deployed rules match the reviewed file; emulator tests passed |
| Version gate | Minimum client version enforced; old clients blocked from Ranked |
| Backup | ≥90% coverage; restore tested; one RP source |
| Alerts | Settlement failure, quota exhaustion, auth failure, crash rate |
| Environments | Test and production projects separated; no test data in production |
| Logging | No tokens, passwords or personal data in logs |
| Retention | Matches Q18 and the Data safety declaration |

### 5.8.5 Incident response and rollback (G10)

The runbook must answer, in order:

1. **Detect** — which alert fires, and who sees it.
2. **Contain** — halt rollout, disable the affected callable, or revert the app version.
3. **Assess** — is RP affected? Are results lost? How many players?
4. **Repair** — replay outbox, reconcile ledger, restore backup, or issue a correction.
5. **Communicate** — in-app message and store note if the player impact is material.
6. **Learn** — post-incident note and a new test.

Rollback triggers: confirmed duplicate RP, a settlement path that cannot complete, a crash or
ANR pattern that blocks match completion, a quota outage that stops Ranked, or a security defect
in the authority boundary. The exact numeric thresholds are agreed at G10; the triggers are
binary where integrity is concerned (“any confirmed duplicate RP” is enough).

### 5.8.6 Go/no-go record

| Field | Value |
|---|---|
| Release commit | |
| Version code / name | |
| Gates G0–G11 | pass / fail per gate |
| Open P0/P1 defects | count and links |
| Policy evidence date | |
| Rollback plan reference | |
| Owner decision | GO / NO-GO |
| Date and signature | |

A GO with any failed gate is not a GO; it is a recorded exception that the owner must sign and
that becomes a post-release risk with a date.

### 5.8.7 Post-release watch

For the first 72 hours after rollout, monitor settlement success, duplicate-RP alerts, crash/ANR
reports, auth failures, quota usage and store feedback. Halt the staged rollout on any
integrity trigger from G10. The release is not “done” at upload; it is done when the first
production week passes without an integrity incident or an unpatched P0 defect.

## Appendix A — Evidence index

| File | What it establishes | Used in |
|---|---|---|
| `docs/rules/CANONICAL_RULES.md` | Current Rules summary; amendments A1–A6; charge table; RP definitions | §5.1, §5.2, §5.3, §5.7 |
| `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md` | Owner rulings A–V, Q answers, authority and backup decisions | §5.1, §5.2, §5.6, §5.7 |
| `docs/decisions/OPEN_QUESTIONS.md` | Q/T inventory; answered list; empty §A | §5.7 |
| `docs/decisions/2026-10-16-open4-open5-owner-closure.md` | A5.1 scope: 2 Koz RANKED/UNRANKED, ROOM excluded, Vote Kick 1 Koz | §5.2.3, §5.7 |
| `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` | Old plan and delivery-order amendment; historical baseline | Header, §5.7 |
| `docs/UI_UX_ROADMAP.md` | 27 surfaces, S-number collision warning, artboard sizes | §5.2, §5.4, §5.5 |
| `docs/adr/0002-functions-runtime.md` | Firebase Functions runtime assumption | §5.2.3 D-08 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/Bidding.kt` | Auction, fast Super Call, General Pass, With selection | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/RoundScore.kt` | Round roles, extension calculation | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/Scoring.kt` | Normal and Classic score rows | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/Placement.kt` | Placement and Koz outcomes | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/RpEngine.kt` | RP base/round/final arithmetic and config | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/LeaveMatch.kt` | Vote Kick and departure charge decision | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/InactivityClock.kt` | 50 s takeover clock and missing counter | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/RankedStats.kt` | Nine statistics and gates | §5.2, §5.3 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/SettlementDb.kt` | Settlement fold, claim, transaction | §5.2, §5.3 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/Settlement.kt` | ROOM/UNRANKED NoOp decision | §5.2 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetRun.kt` | 90-day season and reset phases | §5.2, §5.3 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetDb.kt` | 10,000-profile sweep cap | §5.2 |
| `native/functions/index.js` | 11 callable exports and manifest drift | §5.2 |
| `firestore.rules` | Deny-by-default structural validation | §5.2 |
| `native/app/src/main/java/com/estemshan/game/ui/AppNav.kt` | ROOM hardcode at `:291` | §5.2 |
| `native/app/src/main/java/com/estemshan/game/ui/Routes.kt` | 11 routes | §5.2 |
| `native/services/src/main/java/com/estemshan/services/MatchmakingPort.kt` | Missing production matchmaking | §5.2 |
| `.github/workflows/android.yml` | Test command at `:79` | §5.2 |
| `.github/workflows/deploy-production.yml` | Hosting-only deploy at `:61` | §5.2 |
| `docs/design/` | 17 HTML files / 12 screens, all 932×430 | §5.2, §5.5 |

## Appendix B — Estimate roll-up

| Epic | Low | High | Midpoint |
|---|---|---|---|
| E0 Baseline and decisions | 16 | 28 | 22 |
| E1 Stage 0 | 150 | 260 | 205 |
| E2 Rules and engine | 248 | 416 | 332 |
| E3 Ranked runtime and settlement | 280 | 504 | 392 |
| E4 Matchmaking, bots, disconnect | 128 | 224 | 176 |
| E5 Statistics and seasons | 96 | 168 | 132 |
| E6 Authentication and account | 56 | 96 | 76 |
| E7 UI design | 168 | 280 | 224 |
| E8 Native conversion | 288 | 480 | 384 |
| E9 Hardening and operations | 96 | 168 | 132 |
| E10 Store readiness and testing | 72 | 136 | 104 |
| E11 RC and acceptance | 40 | 72 | 56 |
| **Total** | **1,638** | **2,832** | **2,235** |

Midpoints are arithmetic means, not commitments. The high end is the number to use for capacity
planning while Stage 0 and O-01…O-03 remain open.

## Appendix C — Glossary

| Term | Meaning |
|---|---|
| **Koz** | The losing placement; also the departure record. Every player tied at the lowest total is Koz unless all four tie |
| **King** | First place; shared when players tie at the top |
| **With / Wazz** | A player aligned with the Caller by suit (Normal) or exact highest number (Fast); receives the Caller bonus |
| **Super Call** | A bid of 8+ tricks; in Normal scored as bid², in Classic flat +42/−20 |
| **Dash Call** | Pre-bidding declaration of 0 tricks; flat table, no Risk |
| **Normal Dash** | A 0 estimate made in the Estimation stage; scored as 10+T, can hold Risk |
| **Risk** | A 10/20/30 stake on the Risk player’s outcome |
| **Sa'ayda** | All four players fail in one round; round scores zero, next round’s multiplier escalates |
| **General Pass** | All four pass the auction; the round is consumed and the next round is multiplied (Normal only) |
| **FULL / MINI** | 18-round / 10-round game types with fast windows 14–18 / 6–10 |
| **Fast round** | No auction, single pass, forced trump, Super Call changes trump |
| **RP** | Ranked Points; Ranked-only progression |
| **Placement** | Initial Ranked matches that count toward no statistic |
| **ROOM / UNRANKED / RANKED** | Private / rank-blind / ranked modes with different authority and rewards |
| **Stage 0** | The 13 feasibility experiments that gate the runtime proposal |
| **RD31** | The 2-Koz doubling rule for departures, RANKED and UNRANKED only |
| **O-xx** | Owner decision required |
| **T-xx** | Test/vector ID |
| **IB-xx / SG-xx** | Implementation bug / spec gap |

## Appendix D — Maintenance and supersession

| Version | Date | Baseline | Change |
|---|---|---|---|
| V2 | 2026-10-22 | `7a7e65c` | Complete replacement plan; supersedes the 2026-09-21 plan as the practical plan |

This document must be revised when any of the following changes:

- an owner decision in §5.7 is answered;
- Stage 0 produces a result that changes the runtime choice;
- a P0 rule or scoring table changes;
- the estimate range moves by more than 15%;
- Google Play policy changes the closed-testing or deletion requirements.

The 2026-09-21 file remains in the repository as history. It is not amended by this plan and
must not be used for scope, sequencing or estimates. `AGENTS.md` points to this file as the
current plan.

