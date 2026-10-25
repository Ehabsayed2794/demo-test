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
| Estimate range | **1,626–2,812 engineering hours** (§5.5); roughly 203–352 person-days |
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
(§5.2.3), not silently followed. One case matters immediately: Current Rules item 7 and A6.7
record a passed Vote Kick as **2 Koz**, matching the binding owner rulings Q14b and Q14d(1)
(2026-10-21). A5.1's “1 Koz” and `OPEN_QUESTIONS.md` §B are stale (D-01, D-12), and the engine
returns 1 Koz (`LeaveMatch.kt:53`) — an implementation gap, not the rule. This plan uses **2 Koz**.
**A6.12 (owner, 2026-10-22) changes no number:** it narrows *what counts as a departure* to a
**voluntary** leave, and the one consequence it left unsettled (O-03c) was closed the same day.
**Amendment A7 (owner, 2026-10-22) is the decision wave this plan was waiting on:** O-01 Classic
Dash+Risk arithmetic, O-02 the fast equal-highest Super Call tie-break, O-03 the `ROOM` 7th-takeover
charge and its timing, O-03c the permanent disconnect-replacement charge, and O-04 `ROOM` bot fill
with result attribution — all decided, recorded as A7.1–A7.5 and Q30–Q34, and consumed throughout §5.
**Amendments A8.1–A8.8 (owner, 2026-10-22/23) close the four consequences A7 did not reach** —
O-02b exact duplicate (A8.1/Q35), O-03b ballot cancellation (A8.2/Q36), O-03d ROOM 15-timeout
(A8.3/Q37), O-04b Games Played (A8.4/Q38) — plus terminology (A8.5/Q39/Q50/Q52), the matchmaking band
(A8.6/Q41), General Pass (A8.7/Q51) and Sa'ayda (A8.8/Q48).

### Scope of this document

In scope: the complete path from `7a7e65c` to a V1 that can be submitted to Google Play and
operated at $0 marginal infrastructure cost, including rules completion, Ranked runtime,
settlement integrity, matchmaking, bots, disconnect/reconnect behaviour, statistics, seasons,
authentication, **voice in Rooms and the Waiting Room (Q42)**, **ads and IAP through Play Billing
(Q43)**, Android UI design and conversion, store policy work, testing, release gates and estimates.

Out of scope for this document and for V1: writing the code itself (this is the plan the
implementation will follow), iOS, a web client, leaderboards, tournaments, friends/social graph,
spectating, and any architecture approval. **Voice and monetization are *in* V1 scope** — what is
unsettled is their providers and their verification runtime (O-12, O-13, O-06), not whether they
ship. §5.7 and §5.9 state where each sits instead of pretending it is settled.

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

V1 ships **Android only**, in **Arabic and English**, with **no leaderboards, no social graph
and no web client**. **Voice is in V1 scope for Rooms and the Waiting Room only** — never Ranked
— with provider, protocol and cost still open (**O-12**). **Monetization is in V1 scope** as ads
plus in-app purchases through Play Billing; the ad/consent provider (**O-13**) and the server-side
purchase verifier are unresolved, and verification is **blocked on O-06**. The runtime cost target
is **$0 marginal for infrastructure** on free tiers, with **no Firebase Blaze plan** and **no
Firebase/Google Cloud billing account** attached; Play's store/service fees are not infrastructure
costs.

### 5.1.2 In scope and out of scope

| Area | In V1 | Explicitly out of V1 (see §5.7) |
|---|---|---|
| Game modes | ROOM (private), UNRANKED, RANKED | Tournaments, spectating, party play beyond the existing rank-down join gate, decay beyond the reset model |
| Rules | FULL 18 rounds / fast 14–18; MINI 10 / fast 6–10; Normal and Classic calculation; Dash; Risk; With/Wazz; Sa'ayda; Super Call; General Pass; 13 Rule | Any new rule variant invented during implementation |
| Ranked | 19 ranks, RP engine, placement, Auto-Match with bot fill, seasons, rank-down party gate | Public leaderboard (deferred by owner), cross-season ladders, decay beyond the documented reset |
| Statistics | Nine career statistics for all finished matches, including UNRANKED and ROOM | Per-mode stat filters beyond what the existing profile model stores |
| Identity | Anonymous guest, email sign-up/sign-in, Google sign-in, anonymous→registered linking | Apple, phone auth, Google Play Games, other social login; account deletion (Q6 — deferred, store-policy risk accepted) |
| Android | All 27 surfaces designed, implemented and wired on the fixed 932×430 landscape frame (D10); EN/AR with RTL | Tablet-specific layouts, Wear/TV |
| Operations | Backup policy, recovery, rollback, incident runbook, $0 free-tier verification | 24/7 on-call, SLA, multi-region failover |
| Monetization | Ads + in-app purchases (Play Billing) | Subscriptions, cosmetics, loot boxes; provider and verification design (§5.9, O-13) |
| Voice | Push-to-talk in Rooms and the Waiting Room only — never Ranked | Voice in Ranked; recording; provider/transport unresolved (O-12) |
| Platform | Google Play production release | iOS, web client, desktop |

Voice and monetization are **owner-decided V1 scope** (O-08/O-09, 2026-10-22): voice for Rooms
and the Waiting Room, never Ranked; ads and IAP through Play Billing, with no Firebase/Google
Cloud billing link. Neither has a supplier yet (**O-12**, **O-13**), neither has implementation
code today, and neither is estimated — §5.5.5 and Appendix B carry them as an explicit
excluded-scope block. §5.9 records the purchase-verification design; its runtime dependency means
IAP **cannot ship** until an approved free-tier backend exists (O-06).

### 5.1.3 Mode authority matrix

The three modes do not have the same trust model, and V1 must not pretend they do.

| Property | ROOM | UNRANKED | RANKED |
|---|---|---|---|
| Gameplay state owner | Client (app-managed) | Client (app-managed) | **Server-authoritative target** (proposal; §5.7 O-06) |
| What the backend stores | Final result only | Final result only | Authoritative match state and result |
| Who reports the result | All four clients; backend requires matching reports | All four clients; backend requires matching reports | Server computes; client report is advisory |
| RP | None | None (frozen) | Yes, one source of truth |
| Career statistics | Yes (all nine) | Yes (all nine) | Yes (all nine) |
| Vote Kick | Not held in ROOM | 2 Koz (Q14d(1)) | 2 Koz (Q14d(1)) |
| Departure charge (voluntary leave only — A6.12) | 1 Koz (§5.2.3 D-02) | 2 Koz | 2 Koz |
| RD31 2-Koz doubling | Excluded | Applies | Applies |
| Bot fill | Allowed (A7.5) | Allowed per existing rules | 2-minute fill + 30-second majority vote |
| Season reset | No | No | Yes |

Two consequences follow. First, “server-authoritative” is a **RANKED-only** goal; applying it to
ROOM would contradict the owner's explicit app-managed decision and add cost for no integrity
gain. Second, statistics are **not** a Ranked feature: UNRANKED and ROOM matches must update
them, which is the opposite of the baseline's `accumulateRankedStat` gate.

Vote Kick is **2 Koz in RANKED and UNRANKED** and is not held in ROOM (Q14b `:193`, Q14d(1)
`:203`; Current Rules item 7 and A6.7 agree). A5.1's 1 Koz is superseded; the engine still
returns `Koz1` (`LeaveMatch.kt:53`), which is a code gap, not the rule.

**A6.12 (owner, 2026-10-22) narrows what counts as a departure and changes no number.** A
departure is a **voluntary** leave: a disconnect or app close that is not voluntary charges
nothing, a reconnect inside the takeover window is never a departure, and the 7th takeover and the
15-timeout removal keep their own charges rather than being re-charged as a leave. Consequence for
`§4`: `LeaveReason` (`LeaveMatch.kt:33-42`) has **no** voluntary/involuntary discriminator and has
no disconnect case at all, so the E3.5 work is not just a constant correction — it must first split
`FIFTEEN_TIMEOUT` out of the leave paths and add a reason the caller can actually populate.

**Amendment A7 (owner, the same day) closed the five decision questions this section carried as
blockers** — recorded in `CANONICAL_RULES.md` A7.1–A7.5 and `OPEN_QUESTIONS.md` Q30–Q34:

| Closed | The rule as decided | Effect on this plan |
|---|---|---|
| **O-01** (A7.1) | Classic Dash + Risk adds the **components**, the miss counted once: −2 + (−10) = **−12**, Over-round success +13 + 10 = **+23** | E2.8 unblocked; T-07's blocked rows now have exact values (§5.3) |
| **O-02** (A7.2) | Fast equal-highest: higher **number** first, then the **canonical suit hierarchy** (Sans > ♠ > ♥ > ♦ > ♣, `CANONICAL_RULES.md:27-31`, `:59`, `:68`); **never seat order**; fast With needs the exact number **and** the suit | E2.3 unblocked; T-11 becomes testable; the engine's `Bidding.kt:710` seat-order sort is now a named defect |
| **O-03** (A7.3) | The 7th takeover is **1 Koz in `ROOM`** (2 Koz otherwise) and fires **immediately, even with a 30 s ballot open or a 90 s WAIT running** | E4.3/E3.5 unblocked on the amount; the current code cannot fire it that way at all — the missing counter and the ballot-time suppression are **A8.2 / Q36**, implemented in S7 |
| **O-03c** (A7.4) | A permanent disconnect replacement **keeps** its ratified charge: `UNRANKED` 2 Koz, `ROOM` no Koz | A6.12's wording means "no *leave* penalty on top", not "nothing recorded"; `DisconnectVote.permanentCharge` is **right as written** and needs a caller, not a value change |
| **O-04** (A7.5) | A bot **may** fill a vacated `ROOM` seat; a departed human is **scored Koz even if the bot wins as King**; completing humans are unaffected; a bot's result is **never** attributed to a human | E4.4/E5.1 gain an explicit attribution requirement; today the rule exists only as a comment (`RankedStats.kt:144-147`) |

**Update, 2026-10-23.** The four A7 residuals are now closed as well — **O-02b** by **A8.1 / Q35**,
**O-03b** by **A8.2 / Q36**, **O-03d** by **A8.3 / Q37** and **O-04b** by **A8.4 / Q38** — and
O-05…O-11 were answered on 2026-10-22 (**Q39–Q45**; see §5.7.1). No rule question remains open. Where
the table above says ROOM is "excluded" from RD31, note the two positive ROOM charges decided later:
the **7th takeover = 1 Koz** (A7.3) and the **15-timeout removal = 1 Koz** (A8.3); a permanent ROOM
disconnect replacement still records nothing (A7.4).

### 5.1.4 Hard constraints

| Constraint | Consequence for the plan |
|---|---|
| No Firebase/Google Cloud billing account, no Blaze plan, $0 marginal infrastructure runtime | Firebase Functions cannot be the V1 Ranked runtime as deployed today; any replacement must fit free tiers and be approved by ADR after Stage 0. Play Billing is permitted; store/service fees are not infrastructure costs and do not count against $0 (§5.9) |
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
| V1-C9 | Every finished match in every mode updates the nine career statistics; UNRANKED and ROOM are included, not Ranked-only; only RANKED placement matches are excluded, and being mid-placement never suppresses UNRANKED or ROOM statistics | Stats integration tests |
| V1-C10 | 90-day season, lazy reset and two-step reset pass their vectors; no profile remains stale after settlement | Season reset suite |
| V1-C11 | All 27 surfaces are implemented and wired or explicitly deferred with owner sign-off; 11 routes reachable; no placeholder strings; EN/AR + RTL render | UI surface checklist + screenshots |
| V1-C12 | Guests can play ROOM but not RANKED; email sign-up and Google sign-in work; anonymous→registered linking preserves the uid | Auth tests |
| V1-C13 | Closed test with ≥12 testers opted in continuously for 14 days completed; production access granted; reviewer credentials work; Data safety declarations live | Play Console records |
| V1-C14 | $0/free-tier feasibility measured and approved; backup ≥90%; rollback rehearsed; incident runbook exists; owner signs acceptance | Stage 0 E5/E6/E10/E12 + release checklist |

### 5.1.6 Top risks

| ID | Risk | Likelihood | Impact | Mitigation | Owner |
|---|---|---|---|---|---|
| R-01 | Unresolved rules block tests | Closed | — | **CLOSED 2026-10-22/23** by A7.1–A7.5 (Q30–Q34) and A8.1–A8.8 (Q35–Q38, Q48): the last four residuals O-02b/O-03b/O-03d/O-04b are decided. What remains is implementation (S1–S7, §5.10), not an owner question | Owner |
| R-02 | Stage 0 shows the engine cannot run under the proposed worker runtime | Medium | High | Early gate; fallback architecture decision, not a silent rewrite | Owner + eng |
| R-03 | Server-authoritative Ranked is much larger than the current client-owned model suggests | High | High | Treat as a proposal; estimate range wide; Stage 0 first | Eng |
| R-04 | Free-tier request/storage limits are exceeded by real usage | Medium | High | E5/E6/E10 measurements; capacity model; no leaderboard | Eng |
| R-05 | Fewer than 12 testers available, resetting the 14-day clock | High | High | Recruit before RC; treat as external wait, not engineering time | Owner |
| R-06 | Account deletion is treated as compliant when it is only deferred | High | High | Owner-accepted deferral risk (Q6): Play may reject the release; deletion stays post-V1 (§5.7.4, O-10). Never described as compliant. | Owner |
| R-07 | Arabic quality: colloquial draft, 6 dropped placeholders, engine text not localized | High | Medium | Localization epic + native-speaker review gate | Owner |
| R-08 | Auth wiring incomplete: no google-services plugin, anonymous/email only, no Google sign-in or sign-up | High | High | E6 tasks; real project wiring verified on device | Eng |
| R-09 | Client-triggered settlement with in-memory retry loses results on process death | High | High | Durable outbox + idempotent settlement (§5.6) | Eng |
| R-10 | Four matching ROOM/UNRANKED reports can be fabricated or collusive | Medium | Medium | Documented limitation; server records final result only; no RP | Owner |
| R-11 | Version skew between app, rules and functions | Medium | Medium | Version gate and minimum-client field (§5.6 F-16) | Eng |
| R-12 | Design coverage: core screens have no artboards; only 12 screens exist, all on the fixed 932×430 landscape frame | High | Medium | E7 works on the D10 932×430 frame: reuse the compliant artboards (S45 included) as-is, fill the missing surfaces | Owner + design |
| R-13 | Single-engineer capacity: 1,626–2,812 h is 41–70 weeks at 40 productive h/week | High | High | Scope decisions in §5.7; phased release; no hidden parallel work | Owner |
| R-14 | ADR 0002 and the no-Blaze decision conflict | High | Medium | New ADR after Stage 0; ADR 0002 marked superseded in part | Eng |

### 5.1.7 Decisions required before implementation starts

**Status after the 2026-10-22 and 2026-10-23 answers: only the runtime decision still blocks a
workstream.** O-01…O-04, O-03c, O-02b, O-03b, O-03d and O-04b are all decided (`CANONICAL_RULES.md`
**A7.1–A7.5**, **A8.1–A8.4**; `OPEN_QUESTIONS.md` **Q30–Q38**), and O-05…O-11 are answered
(**Q39–Q45**, **Q51**, **Q52**). E2.3, E2.8, E3.5, E4.3, E4.4 and E5.1 no longer wait on the owner.

One decision still blocks a workstream rather than merely shaping it:

1. **O-06 runtime architecture approval** — cannot be decided before Stage 0; it blocks Ranked
   runtime implementation (E3) and the purchase verifier (§5.9, S8). It does not block Stage 0, the
   engine wave, or the planned sprints S1–S7.

The open items now shape scope rather than the rule set: **O-12** voice provider/protocol/cost,
**O-13** ads/consent provider, **O-15** matchmaking direction, **O-16** IAP/Stage 0 sequencing,
**O-17** sprint priority and **O-18** Arabic rule prose. Each is listed in §5.7.2 with its
recommendation explicitly marked unapproved; none has a placeholder value in a rule or a test.

The other decisions shape or defer specific work; they do not stop the baseline or Stage 0.

## 5.2 Repository baseline and implementation-gap audit

### 5.2.1 Baseline facts

| Fact | Value at `7a7e65c` | Evidence |
|---|---|---|
| HEAD | Merge of PR #151 (`docs/native-v1-plan-amendments`), 2026-10-22 | `git log -1` |
| PR #151 shape | Docs only: 6 files, +448 / −9 | `git show --stat 7a7e65c` |
| Working branch | `docs/native-v1-implementation-plan-v2` | branch created from `7a7e65c` |
| Kotlin modules | `native/engine`, `native/app`, `native/services`, `native/functions` | `native/settings.gradle.kts` |
| Source scale | 252 Kotlin files: 144 production, 108 test | module counts: engine 68 (36/32), app 72 (47/25), services 53 (30/23), functions 59 (31/28) |
| Engine tests | 473 tests across 32 test classes | `native/engine/src/*Test` |
| App tests | 183 tests across 24 test classes plus one test fake; no `androidTest` source set in `native/app` | `native/app/src/test` |
| Services tests | 189 tests | `native/services/src/test` |
| Functions tests | 516 tests | `native/functions/src/*Test` |
| Emulator suites | 56 `tests/*.cjs` suites run against Firestore/Auth emulators | root `package.json:13`, `tests/`, `.github/workflows/test.yml` |
| Rules file | 2,410 lines, deny-by-default, structural validation only | `firestore.rules:1470-1480`, catch-all `:2406` |
| Android toolchain | Kotlin 1.9.25, Gradle 8.14.3, Java 17, compileSdk/targetSdk 36, minSdk 26 | `native/build.gradle.kts:9-14`, `native/gradle/wrapper/gradle-wrapper.properties:3`, `native/app/build.gradle.kts:58-67`, Java 17 at `native/app/build.gradle.kts:184-188` |
| App identity | `versionCode 1`, `versionName "0.1.0-phase1"`; **no google-services plugin** | `native/app/build.gradle.kts` |
| CI | Strong build/unit-test job; instrumented job; hosting-only production deploy | `.github/workflows/android.yml:79`, `.github/workflows/deploy-production.yml:61` |
| Functions deployability | 11 exported callables; no billing account, so Cloud Functions cannot be deployed under the no-Blaze constraint | `native/functions/index.js:19-33` |
| Design artefacts | 17 HTML files / 12 distinct screens on the fixed **932×430 landscape** frame, S45 included; the checked-in artboards are compliant and are reused, with no re-cut budgeted | `docs/design/`, `docs/design/Estimation Ranked Home S45.html:8`, `docs/UI_UX_ROADMAP.md:2611-2615` |
| Android surfaces | 27 identified: 4 implemented and wired, 7 partial, 7 design-only, 9 missing | `docs/UI_UX_ROADMAP.md`, `native/app` routes |
| Navigation | 11 routes; `Routes.STANDINGS` is a dead preview | `native/app/src/main/java/com/estemshan/game/ui/Routes.kt` |

### 5.2.2 Implementation-gap audit

Status vocabulary: **Implemented** (behaviour exists and is broadly correct), **Partial**
(some path exists, material parts missing), **Incorrect** (path exists and contradicts the
binding rule), **Missing** (no implementation), **Not deployable** (code exists but cannot run
in production under the V1 constraints).

Every table uses the brief §5.2 columns: requirement/subsystem, current implementation and code
evidence, existing tests and their evidence, approved target behaviour, gap or contradiction,
proposed work, and dependencies/blockers. The status is folded into the gap column as its first
word (“IMPLEMENTED — …”, “PARTIAL — …”, “INCORRECT — …”, “MISSING — …”, “NOT DEPLOYABLE — …”), so
the seven mandated columns stay intact without losing the status distinction. Proposed work names
the closing epic and the evidence that closes the gap.

#### A. Gameplay, rules and scoring

| Requirement / subsystem | Current implementation + code evidence | Existing tests + evidence | Approved target behaviour | Gap or contradiction | Proposed work | Dependencies / blockers |
|---|---|---|---|---|---|---|
| Match and round structure | `GameType.FULL(18,14,5)` / `MINI(10,6,1)` in `Bidding.kt:111-113`; 4 seats, 13 tricks, counter-clockwise | `GameTypeTest`, `GameSessionTest`; 473 engine tests green at `7a7e65c` | FULL 18 rounds (fast 14–18), MINI 10 (fast 6–10), 4 players, 13 tricks, counter-clockwise | IMPLEMENTED — no structural gap known; extension and pass rules are separate rows | E2.1 regression lock; exit evidence: engine test report | None |
| Auction and bid validation | `withFloorFor` reads the last `BID` in **any** suit (`Bidding.kt:273-274`); `forbiddenEstimateFor` (`Bidding.kt`) is number-only | `BiddingTest`, `BidBrainTest` encode current auction behaviour | Minimum bid 4; Caller’s winning suit binds With; Estimation Jump-In respects the rule | PARTIAL — With floor is suit-blind; jump-in does not carry suit semantics | E2.2; exit evidence: new auction matrix tests (§5.3 T-08/T-12) | None. **O-02 is decided (A7.2 / Q31):** higher number first, then the canonical suit ladder (`CANONICAL_RULES.md:27-31`, `:54`, `:63`; `Cards.kt:10-16`), and seat order may never break a tie. In the **Normal** auction the code already matches: equal number + equal suit does not beat the top (`auctionBidBeatsTop`, `Bidding.kt:245-247`) and makes the other seat With (`:249-257`) — the owner's T-08 case 1. The fast-round residue is now decided too — **A8.1 / Q35**: an exact number-and-suit duplicate is legal, the first declarer is Caller and each later exact duplicate is With. Engine gap: a fast declaration still stores no suit (`Bidding.kt:25`, `:692`, `:702`) → S4 |
| Fast Super Call | `fastSuperResolved` set once (`Bidding.kt:594`, `:609`); replacement trump wipes **only seats before the Caller** (`Bidding.kt:575-585`); every 8+ becomes With (`superCandidates`, `:707-722`) with a seat-order tie-break (`:710` `.thenBy { order.indexOf(it) }`, comment `:740-741`) | `BiddingTest` fast-super cases and root `tests/fast-super-reset.test.cjs` encode one-shot resolution | Every higher 8+ Super Call re-opens confirmation; every player except the Super Caller re-declares; trump may change. **Equal-highest (A7.2):** higher **number** wins whatever the suit, then the **canonical suit hierarchy** Sans > ♠ > ♥ > ♦ > ♣ — never seat order | INCORRECT — one-shot resolution; later estimates stay committed; With set invented; **and the seat-order tie-break is now a ruled violation, not an open question** | E2.3; exit evidence: T-09/T-11 vectors | **O-02 decided (A7.2 / Q31); O-02b decided (A8.1 / Q35).** Equal numbers in different suits use the suit ladder, never seat order; an exact duplicate is legal with the first declarer as Caller. `suit = null` fast estimates and the `biddingOrder` tie-break remain the gap → S4 |
| With / Wazz | Same `superCandidates.drop(1)` path (`Bidding.kt:716`); floor suit-blind (`Bidding.kt:273-274`); **a fast declaration stores no per-seat suit at all** — `Bid(val type, val amount)` (`Bidding.kt:25`), only the running top keeps one (`auctionSuit`, `:80`) | `BiddingTest`, `ScoringTest` cover number-only With, not suit semantics | Normal: With = anyone who bid the Caller’s winning suit, number = their last bid in that suit. Fast: highest winning number **exactly** **and** the matching suit (A7.2), plus the final-trump estimate if the trump changed; not every 8+ | INCORRECT — over-inclusive With set; no final-trump re-estimate rule; **and the number-AND-suit test is unevaluable until E2.3 puts a suit on a fast declaration** | E2.3/E2.4; exit evidence: T-08/T-10/T-11/T-12 | None; Q28/Q29/Q31 answers recorded (§5.7.1) |
| General Pass | `doubled = minOf(state.roundMultiplier * 2, 8)` (`Bidding.kt:496`); client re-deals the same round (`QuickMatchViewModel.kt:154-166`; `OnlineMatchViewModel.kt:724` returns on `GeneralPass`); Classic doubles | `BiddingTest`, `QuickMatchViewModelTest`, `OnlineMatchViewModelTest` encode the redeal | **A8.7 / Q51:** the pass cancels the round and deals fresh into a **new round number**; Normal ×2 (ladder ×2→×4→×6→×8, capped); Classic **no multiplier**. The pass counts toward completion | INCORRECT — the engine redeals the **same** round number (`QuickMatchViewModel.kt:154-167`), always doubles with no game-type input (`Bidding.kt:493-508`), re-inits the same round in settlement and timeout resolution (`Settlement.kt:517-524`, `TimeoutResolution.kt:979-984`), treats the pass as an ordinary applied bid in services (`MatchAdapter.kt:168-171`, `:835-840`) and ignores it online (`OnlineMatchViewModel.kt:724`) | E2.5; exit evidence: T-01/T-02/T-22; sprint **S1** | None |
| Sa'ayda extension | Window-only extension; caps FULL 5 / MINI 1 enforced and user-facing (`A2.1`); no final-round repeat | `RoundScoreTest` covers the capped window | **A8.8 / Q48:** each Sa'ayda in 14–18 (MINI 6–10) adds one round — 14→19, 14+15→20, 14+15+16→21, 13→18, generalized to 18+N; final round repeats until it ends with a winner; no caps | PARTIAL — no repeat-until-winner; caps contradict Q2c; MINI final repeat absent; `computeRoundExtension` **is wired** at `OnlineMatchViewModel.kt:851`, raises `maxRounds`, and `isMatchComplete` reads it (`Session.kt:568`) | E2.6; exit evidence: T-03/T-04; sprint **S2** | None |
| Round extension | `computeRoundExtension` returns false outside `firstFastRound..baseRounds` (`RoundScore.kt:222-238`) | `RoundScoreTest` covers fast-window success only | Extension only when the Super Caller succeeds; combines with Sa'ayda count | PARTIAL — no extension from the final round; combination with count untested | E2.6; exit evidence: T-04/T-05 | None |
| Normal scoring | Super Call and Dash rows correct (`Scoring.kt:63`, `:75`); `normalRoleFor` returns `REG_DASH` before checking `isRisk` (`RoundScore.kt:79`) | `ScoringTest`, `RoundScoreTest` green; a test encodes the dropped Risk | 10+T; Caller/With ±10; sole ±10; Risk; Super Call bid² win / half-up loss; Normal Dash + Risk **adds** both | PARTIAL — Dash+Risk silently drops the Risk component (engine +10 instead of +20 at the owner example) | E2.7; exit evidence: T-06 | None (O-01 covers Classic only) |
| Classic scoring | Base tables present (`Scoring.kt:112` for flat Super Call); Dash+Risk absent | `ScoringTest` Classic rows; no combined-row test | Separate tables: E+13, Caller penalty 10/20, Risk ±10, Wizz-Risk E+33, Super Call +42/−20, Dash tables, sole cap −22; **Classic Dash+Risk = the Dash table value plus the flat Risk value, miss counted once (A7.1): −12 / +23 on the owner's case** | PARTIAL — missing Classic Dash+Risk; **the arithmetic is no longer open** | E2.8; exit evidence: T-07 | **None — O-01 decided 2026-10-22 (A7.1 / Q30)** |
| Placement and Koz | `placementRank` = 1 + seats strictly ahead (`Placement.kt:212`); `placementOutcome` maps rank 3 → THIRD (`:218-222`); comment defends “a Koz is never fabricated” | `PlacementTest`, `PlacementScoringTest` encode tied-lowest → Third | Tied lowest are all Koz unless all four tie (then all King); shared Koz is not Second/Third | INCORRECT — tied-lowest players become THIRD; shared Koz unsupported | E2.9; exit evidence: T-13 | None |
| RP engine | `baseKozLoss = -20`, `baseThirdLoss = -8` (`RpEngine.kt:76-78`); `computeBaseDelta` (`:200`); `roundDelta` (`:327`); `finalRpDelta` (`:347`) | `RpEngineTest`, `RpEngineMixedTierTest` pass on current arithmetic | 19 ranks; base Koz −20 / Third −8; MINI ×0.5 ties away from zero; RD29 band; one source of truth | PARTIAL — correct arithmetic, but inherits the placement bug, so tied-lowest players receive Third loss | E2.9/E3.2; exit evidence: T-13/T-20 | O-11 for the balance constants |
| Bots | `botProfileFor` (`TimeoutResolution.kt:417`); bot takeover path exists; seat → uid is retained (`MatchModels.kt:357` `seatToUid`) and `botControlledSeats` already includes seats of players who **left** (`:377-392`) | `BotPersonalityTest`, `BotTierTest`, `BotVsBotSmokeTest`, `BotTrioTest` | Seat takeover, bot profile from room config, deterministic play; **a bot may fill a vacated `ROOM` seat, and its result must never be attributed to the departed human, who is scored Koz even if the bot finishes as King (A7.5)** | PARTIAL — bot play exists, but there is **no per-seat result override and no attribution guard**: the rule exists only as a comment (`RankedStats.kt:144-147`) and `ROOM` never settles (`Settlement.kt:340-350`) | E2.10/E4.3/E4.4; exit evidence: bot match suite | **None — O-03/O-04 decided 2026-10-22 (A7.3, A7.5); the ballot fate is A8.2 / Q36 and Games Played +1 is A8.4 / Q38.** Attribution work → **S6**, ballot work → **S7** |
| Inactivity and takeover | `TAKEOVER_THRESHOLD_MILLIS = 50_000L` (`InactivityClock.kt:143`); comment “There is no timeout count here” (`:147`); 15-timeout removal threshold 15 (`TimeoutResolution.kt:245`); **a vote-held seat may not increment the timeout counter at all** (`TimeoutResolution.kt:376-380`, `disconnectHeldSeats` counted as bot-held) **and a WAIT freezes every deadline** (`disconnectPauseRemaining` `TimeoutResolution.kt:358`, gated at `TimeoutResolutionDb.kt:212-218` and `:244-250`) | `InactivityClockTest`, `InactiveTakeoverTest`, `DisconnectVoteTest` cover the 50 s clock | When the decision timer expires the MEDIUM bot plays that decision and the 15-timeout counter increments; the creator's bot takes the seat **10 s after that timer**; no 50 s engine clock; the 7th takeover removes the seat and records 2 Koz in RANKED/UNRANKED and **1 Koz in ROOM**, fired **immediately — even with a 30 s ballot open or a 90 s WAIT running** (A7.3); 60 s all-gone cancellation | INCORRECT — uses the 50 s S73 clock as the takeover trigger; no decision-timer + 10 s takeover stage, **no takeover counter anywhere**, no 7th removal, no 60 s cancel; and A7.3's immediate firing is a **structural** change because the current design deliberately suppresses clock progress during a ballot | E2.10/E4.3; exit evidence: T-16 | **None — O-03 (A7.3), O-03b (A8.2 / Q36: an open ballot is cancelled immediately) and O-03d (A8.3 / Q37: ROOM 15-timeout = 1 Koz) are decided.** The structural work remains → **S7** |

#### B. Ranked runtime, settlement and progression data

| Requirement / subsystem | Current implementation + code evidence | Existing tests + evidence | Approved target behaviour | Gap or contradiction | Proposed work | Dependencies / blockers |
|---|---|---|---|---|---|---|
| Ranked runtime | Gameplay is client-owned; the client writes match state under `firestore.rules`; rules cannot verify a shuffle (`firestore.rules:1470-1480`) | 473 engine tests prove client-side rules only; no server-authority test exists | Server-authoritative match state for RANKED only; client cannot forge the result | MISSING — no authority layer for gameplay, no move validation, no server-side deal | E3.1 (after Stage 0/O-06); exit evidence: Stage 0 E1/E2 + authority tests | Stage 0 E1–E10; ADR approval (O-06) |
| Result settlement | `settleOnce` (`SettlementDb.kt:691`), transaction (`:757`), `foldRankedProgression` (`:608`); client `maybeSettle` (`OnlineMatchViewModel.kt:944`) with `MAX_TRANSIENT_FAILURES = 6` (`:1312`); `Settlement.decide` returns `NoOp` for ROOM/UNRANKED (`Settlement.kt:340-348`) | `SettlementTest`, `SettlementLedgerTest`, `SettlementLedgerEmulatorTest`, `SettlementRpEmulatorTest`, `SettlementResponseTest`; no durable-outbox test | Exactly-once settlement; durable outbox; idempotent claim; no in-memory-only retry | PARTIAL — settlement is client-triggered and retries live only in memory; no durable outbox; departure charges and all-left abandonment not applied; **`ROOM`/`UNRANKED` return `NoOp` (`Settlement.kt:340-348`), so there is nowhere to record the per-seat "departed → Koz" override that A7.5 requires** | E3.4/E3.5; exit evidence: T-18 fault injection | Runtime (E3.1/E3.3); backup policy; **A7.5 needs a non-Ranked result path (E5.1)** |
| Departure charges | `decideLeave`: Vote Kick → `Koz1`; RANKED/UNRANKED → `Koz2`; ROOM → `None` (`LeaveMatch.kt:50-56`) | `LeaveMatchTest` asserts Vote Kick = 1 Koz, encoding the stale A5.1 rule; `DepartureContractTest`, `LeaveMatchEmulatorTest` cover the other paths | ROOM 1 Koz; UNRANKED/RANKED 2 Koz; a passed Vote Kick 2 Koz in RANKED/UNRANKED (Q14b `:193`, Q14d(1) `:203`); **only a voluntary leave is a departure** — a non-voluntary disconnect or app close charges nothing, a reconnect inside the takeover window is never a departure, and the 7th takeover / 15-timeout removal keep their own 2-Koz charges without an added leave penalty (A6.12) — **and that "charges nothing" covers only the departure charge: permanent replacement still applies to a seat lost to a non-voluntary disconnect, at its ratified value — `UNRANKED` 2 Koz, `ROOM` none (A7.4 / Q33)**; only at finish if not returned; no charge for server/quota failure; **the 7th takeover is charged 2 Koz in RANKED/UNRANKED and 1 Koz in ROOM, fired immediately even with a 30 s ballot open or a 90 s WAIT running (A7.3 / Q32)** | INCORRECT — Vote Kick returns 1 Koz instead of 2 (engine gap; A5.1 stale); ROOM charge missing; no finish-time application; no server-failure exemption; **`LeaveReason` has no voluntary/involuntary discriminator (`LeaveMatch.kt:33-42`) and treats `FIFTEEN_TIMEOUT` as a leave, so A6.12 needs a reason-model change before the constants can be right**; **no takeover counter of any kind**; and A7.3's immediate firing contradicts the ballot-time suppression (`TimeoutResolution.kt:376-380`, `:358`; gated at `TimeoutResolutionDb.kt:212-218`, `:244-250`) | E3.5; exit evidence: T-14/T-15/T-16 | **None — O-03 (A7.3), O-03c (A7.4), O-03b (A8.2 / Q36) and O-03d (A8.3 / Q37: ROOM 15-timeout = 1 Koz) are decided.** Remaining is implementation → **S5/S7** |
| Matchmaking | No production implementation (`MatchmakingPort.kt:29-33`); navigation hardcodes ROOM (`AppNav.kt:291`); no queue or matchmaking screen | `MatchmakingPoolTest`, `MatchmakingViewModelTest`, `FakeMatchmaking` test an unconnected pool and UI stub | Auto-Match joins a player-created waiting table; 2-minute bot fill; 30 s majority vote; humans-only strength | MISSING — entire matchmaking flow, including bot-fill vote and strength source | E4.1/E4.2; exit evidence: T-20 | Runtime, auth (E3.1/E6); O-07 |
| ROOM lifecycle | `RoomService.createRoom` (`:55`), `setReady` (`:234`), `maybeStartMatch` (`:273`); `CreateGameScreen` is room-only (`:48-50`); no room list (`LobbyScreen.kt:43-45`) | `RoomViewModelTest`, `TableViewModelTest`, `PlayerServiceTest` | Full table of four is a **start** condition; create/join by code; ready/start; leave; a seat vacated mid-match **may be bot-filled**, the departed human scored Koz regardless of how the bot finishes (A7.5); no Ranked rules | PARTIAL — no discovery/list, no four-player enforcement surface, navigation only opens ROOM; **and no bot-fill of a vacated seat, which is now a ruled requirement rather than an open question** | E4.4; exit evidence: ROOM E2E test | **None — O-04 decided 2026-10-22 (A7.5 / Q34):** a bot may fill a vacated `ROOM` seat, the departed human is scored Koz even if that bot finishes as King, the match adds **+1 Games Played** for that human, and the bot's result is never attributed. Implementation → **S6** |
| Statistics | `accumulateRankedStat` returns unchanged unless `mode == RANKED` and not placement (`RankedStats.kt:103`); `accumulateDepartureCharge` (`:151`) | `RankedStatsTest`, `RankedProfileTest` encode the Ranked-only gate | Nine career statistics for **all** finished matches, including ROOM/UNRANKED; counts and percentages over games played; only RANKED placement matches are excluded; **a human who left and did not return is counted in that player's own result column as Koz, not with the replacement bot's placement, and that match adds one to their Games Played (A7.5, A8.4 / Q38)** | INCORRECT — statistics are Ranked-only and placement-gated; ROOM/UNRANKED contribute nothing; **and there is no per-seat result override, so a bot-filled seat could hand the departed human the bot's placement or King (A7.5 forbids this)**; A8.4 settles the Games Played half | E5.1; exit evidence: T-19; sprint **S3/S6** | Settlement records (E3.3). **O-04 (A7.5 / Q34) and O-04b (A8.4 / Q38) are decided** — the match counts in the departed human's Games Played; remaining is the ROOM/UNRANKED writer → **S3/S6** |
| Seasons | `NINETY_DAYS_MILLIS` (`SeasonResetRun.kt:80`); `MAX_RESET_PROFILES = 10_000` (`SeasonResetDb.kt:103`); scheduled sweep | `SeasonResetTest`, `SeasonResetRunTest`, `SeasonResetJobTest`, `SeasonResetDryRunEmulatorTest` | 90-day seasons, lazy reset, two-step reset, no bulk 10,000-profile sweep as the only mechanism | PARTIAL — bulk sweep caps at 10,000 and is not lazy per profile; reset races settlement | E5.2; exit evidence: T-19 + reset race tests | Statistics/profile store |

#### C. Android product, security, delivery and operations

| Requirement / subsystem | Current implementation + code evidence | Existing tests + evidence | Approved target behaviour | Gap or contradiction | Proposed work | Dependencies / blockers |
|---|---|---|---|---|---|---|
| Core Android surfaces | 4 wired, 7 partial, 7 design-only, 9 missing; `Routes.STANDINGS` dead; `MatchMode.ROOM` hardcoded (`AppNav.kt:291`) | 183 app JVM tests across 24 test classes plus one test fake, including `StandingsUiTest`; no `androidTest` source set in `native/app` | All 27 surfaces implemented and wired on the 932×430 landscape frame; 11 routes reachable; no dead previews | PARTIAL — 23 surfaces need work (7 partial, 7 design-only, 9 missing); navigation does not expose Ranked/UNRANKED | E7/E8; exit evidence: surface checklist + screenshots | Design sign-off (M7); auth (M6) |
| Ranked UI | No rank chip; S54 season blank; S45–S56 design-only | `StandingsRankedTest`, `PlacementViewModelTest` cover JVM state only | Rank chip, tier/division, RP progress, season label, placement intro, matchmaking | MISSING — all Ranked-facing UI and state wiring | E7/E8; exit evidence: Ranked UI test + screenshots | M3 API shape; M7 design |
| Match-time UI | No vote-kick, disconnect-pause, leave or timer UI | `OnlineMatchViewModelTest`, `OpponentAwayTrackerTest` cover view-model state only | Vote Kick, disconnect vote, WAIT countdown, leave confirmation, takeover indicator, 7th removal | MISSING — all in-match decision UI | E7/E8; exit evidence: T-14/T-16 UI tests | T-16 engine rows; M7 |
| Authentication | `signInAnonymously` / `signInWithEmail` only (`AuthRepository.kt:19-20`); no google-services plugin; no Google, sign-up or deletion screen | `AuthRepositoryTest`, `AuthPortAdapterTest` cover the guest/email adapters | Anonymous guest for ROOM/UNRANKED; email sign-up and Google sign-in for Ranked; linking preserves uid; deletion deferred (Q6) | PARTIAL — no Google, no registration, no linking; Firebase project wiring unproven on device | E6; exit evidence: T-21 + device walkthrough | Firebase project access; OAuth consent/fingerprints |
| Rules and security | 2,410-line rules, deny-by-default, catch-all (`:2406`); comment states structural validation only (`:1470-1480`) | 56 root `tests/*.cjs` suites include rules-emulator and real-backend matrices | Deny-by-default, but also enforce what the client must not forge; version gate | PARTIAL — rules validate shape, not fairness; no minimum-client version field | E3.6; exit evidence: rules test suite + adversarial review | Runtime; engine freeze |
| Functions deployment | 11 exports (`native/functions/index.js:19-33`); `operatorSeasonResetDryRun` absent from the export map; Cloud Functions require Blaze | `CallableAuthTest`, `CallablePayloadBridgingTest` and the `jsNodeTest` suite; deployment itself untested | Deployable runtime under $0/no Blaze; manifest matches exports | NOT DEPLOYABLE — runtime cannot be deployed as-is; manifest drift | E1/E3.1; exit evidence: Stage 0 E1/E9 + deploy dry run | Stage 0; O-06; no-Blaze constraint |
| CI and release engineering | `android.yml:79` covers app/engine/services unit tests; `.github/workflows/test.yml` runs 56 cjs suites; deploy is hosting-only (`deploy-production.yml:61`); version `0.1.0-phase1` | CI runs green on the recorded suites; no release job test | Build, unit, instrumented, emulator suites, release signing, versioning, store upload | PARTIAL — no release build, signing, bundle upload or version-skew gate | E10/E11; exit evidence: release pipeline run | Signing key (not committed); Play access |
| Backup, observability and recovery | No backup, alerting or runbook artefacts found | No tests | ≥90% backup coverage for the primary RP store; outbox; recovery runbook; alerts; rollback | MISSING — operational readiness entirely absent | E3.4/E9; exit evidence: Stage 0 E7 + gate G4 | Runtime; O-06 |

### 5.2.3 Documentation defects found

These are defects in the **sources**, not in the code. They are recorded so implementation and
review do not re-litigate them. Fixing the rule documents is a separate documentation task and
is **not** part of this plan’s allowed changes.

| ID | Defect | Where | Binding resolution | Action |
|---|---|---|---|---|
| D-01 | Vote Kick recorded as **1 Koz** in the A5.1 charge table and its “Vote Kick records 1 Koz, final” note | A5.1 (`CANONICAL_RULES.md:484-489`, `:492`); the A4 amendment (`:393-412`) is only a supersession pointer | **2 Koz** in RANKED and UNRANKED (Q14b `:98`, Q14d(1) `:32`); Current Rules item 7 (`:229`) and A6.7 (`:598`) are correct | **CANONICAL_RULES pointer DONE (2026-10-22)** — a pointer note now sits under A5.1’s “1 Koz, final” bullet pointing at Q14d(1)/A6.12, body left verbatim. Remaining: `OPEN_QUESTIONS.md` §B (D-12); tests use 2 Koz; the engine still returns `Koz1` (`LeaveMatch.kt:53`) — implementation gap for E3.5 |
| D-02 | ROOM departure described as “no 2-Koz rule” without stating the 1-Koz charge | A5.1 charge table (`:484-489`) vs A6.6 (`:597`) | **ROOM 1 Koz**, excluded from RD31’s 2-Koz charge | **DONE (2026-10-22)** — the A5.1 pointer now states that a voluntary Room leave is charged 1 Koz under Q14b/A6.6 and that only the **disconnect** row keeps “no Koz”. |
| D-03 | A6.1 says a General Pass is a “redeal”; A6.4 says it **consumes the round** | A6.1 (`:575`) vs A6.4 (`:591`) | **Consumes the round**; A6.1 wording superseded in part | **RESOLVED 2026-10-23 by A8.7 / Q51** — one behaviour: a fresh deal into the **next round number**, Normal ×2, Classic none. The engine's same-round model is the defect (S1) |
| D-04 | A6.9 fast-round With is superseded by A6.10 but both remain normative-looking | A6.9 (`:548`), A6.10 (`:549`) | A6.10 controls fast rounds; A6.9 controls Normal | Consolidate into one paragraph |
| D-05 | A3.5 statistics table marks ROOM and UNRANKED “no” | A3.5 (`:355`), prose “no progression of any kind” (`:368`) | **UNRANKED and ROOM enter all nine statistics** (A6.7 `:598`, Q14c) | Correct A3.5 and its prose |
| D-06 | Extension caps “up to 5” / “exactly 1” | A2.1 (`:241`), A2.2 (`:249`) | **Caps removed** (Q2c; A6.2 follow-up) | Remove caps from A2.1/A2.2 |
| D-07 | A1 body scopes bid² to the legacy tracker | A1 (`:216-224`) | Native Normal mode applies bid² (Q19c pointer at `:224`) | Body text correction; numbers are correct |
| D-08 | ADR 0002 defines a Firebase Functions runtime | `docs/adr/0002-functions-runtime.md` | Conflicts with no-Blaze and with the Cloudflare **proposal** | New ADR after Stage 0; mark 0002 superseded in part |
| D-09 | RD25 makes Ranked launch-blocking, but no-Blaze prevents deploying its functions | Old plan `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:62` | Unresolved until the runtime decision; Stage 0 exists to resolve it | §5.7 O-06 |
| D-10 | Story numbers S36–S44 (design) collide with S36–S74 (plan) and E7 uses S65–S69 | `docs/UI_UX_ROADMAP.md:812-819` | **Map by name, never by number** | Keep the warning; cite names in this plan |
| D-11 | `AGENTS.md` calls the 2026-09-21 file the “only plan document” | `AGENTS.md:3-8` | This V2 plan is current; old file is historical | Pointer edit (part of this task) |
| D-12 | `OPEN_QUESTIONS.md` §B still records “vote kick 1 Koz” as resolved by A5.1 | `docs/decisions/OPEN_QUESTIONS.md:21` | **2 Koz** (Q14d(1) `:203`); Q14d itself is no longer a defect | Update §B to point at Q14d(1); do not mark Q14d superseded |
| D-13 | “All players are Koz” abandonment wording does not distinguish humans from bots or specify ROOM | Current Rules item 7 (`:229`), A6.7 (`:598`) | **RESOLVED by record.** Q27 fixes the ROOM case (“ROOM/UNRANKED record Koz without RP”) and A7.5 makes a bot’s result unattributable to any human profile (`:703-714`), so the charge falls on humans only. The bot half of the reading is **derived** from A7.5’s attribution guard, not an owner sentence about bots | Implement as humans-only; no owner question needed |
| D-14 | Fast takeover timings (10 s/7th) sit beside disconnect vote (30 s/90 s WAIT) without an interaction rule | `docs/decisions/2026-10-21...md` §G vs A5.2 (`:514-522`) | **RESOLVED 2026-10-22/23:** the 7th takeover fires immediately during an open ballot or a WAIT (A7.3) and **cancels** the open ballot with no expiry completion and no double charge (**A8.2 / Q36**) | **O-03b closed** — implementation is S7 |
| D-15 | Current Rules is a 12-item summary, not the full rule set; coverage is uneven across areas | Current Rules (`:199-214`) | Use it as summary only; amendments and decisions govern detail | Note in rules index |
| D-16 | A6.6/A6.7 “leave for **any reason**” reads as including a plain disconnect, and item 7 carried that wording | A6.6 (`:597`), A6.7 (`:598`), Current Rules item 7 (`:229`) | **A6.12 (owner, 2026-10-22):** a departure is a **voluntary** leave only; a non-voluntary disconnect or app close charges nothing; a reconnect inside the takeover window is never a departure; the 7th takeover and 15-timeout removal are not departures. **No charge value changes.** | **DONE in CANONICAL_RULES (2026-10-22)** — item 7 rewritten, A6.12 added, A5.1 pointer added. **O-03c, the residual it flagged, was closed the same day by A7.4 / Q33** (permanent replacement keeps its ratified charge: `UNRANKED` 2 Koz, `ROOM` none) |
| D-17 | A5.1’s “`ROOM` is out of RD31 entirely” note says **neither** an explicit leave **nor** a 15-timeout removal in a room charges anything | A5.1 (`:496-498`) | **RESOLVED 2026-10-23 by A8.3 / Q37** — the 15-timeout ROOM cell is **1 Koz** (RANKED/UNRANKED keep 2). Three later rulings each repeal part of that sentence: a voluntary Room leave is **1 Koz** (Q14b/A6.6), the 7th takeover is **1 Koz** (A7.3), a voted permanent replacement is **no Koz** (A7.4). The A5.1 pointer carries all mode-scoped values verbatim and the superseded sentence stays on the page as history | **O-03d closed** — implementation is S5 |

### 5.2.4 Reusable assets the plan keeps

| Asset | Why it matters | Reuse |
|---|---|---|
| Engine rules module with 473 tests | The scoring core is the most expensive thing to rebuild | Extend, do not fork; add vectors |
| FULL/MINI `GameType` and round plan | Already models both types and fast windows | Reuse in engine and UI |
| 19-rank RP model and arithmetic | `RpEngine` has base, round and final delta logic | Fix placement input, keep arithmetic |
| 2,410-line deny-by-default rules | Strong structural baseline | Add version gate and adversarial review |
| 11 callable functions and 56 emulator suites | Settlement, season, party and timeout logic already tested | Port behind the approved runtime |
| 12 design screens (932×430) | Visual language and copy exist | Reuse the compliant artboards, keep tokens |
| CI build/test pipeline | Catches regressions in four modules | Extend with release and parity jobs |

### 5.2.5 What the baseline does not prove

- **Passing tests are not rule correctness.** Several tests encode the current incorrect
  behaviour (tied-lowest → Third; one-shot fast Super Call; same-round General Pass). A green
  suite at `7a7e65c` proves consistency with today’s code, not with the owner’s rules.
- **The rules file is not an authority layer.** It validates document shape; it cannot verify a
  shuffle, a legal play or a score (`firestore.rules:1470-1480`).
- **Nothing runs on a device.** There is no `androidTest` suite in `native/app`, so UI wiring,
  auth and navigation are unproven outside JVM tests. (`native/services/src/androidTest` exists,
  but it exercises emulator-backed services, not the UI.)
- **Nothing is measured.** Free-tier limits, backup lag, startup and bundle size have not been
  measured; Stage 0 exists because of this.
- **Nothing is releasable.** There is no signed release build, no store listing pipeline, no
  version-skew gate and no operational runbook.

## 5.3 Requirement-to-test traceability

### 5.3.1 Method and legend

Every V1 rule is traced from its **binding source** to **current implementation evidence**, a
**planned implementation task**, **positive tests**, **negative and edge-case tests**, a
**per-rule acceptance criterion** and a **release evidence** item. Acceptance is per rule: a rule
is not releasable because a sibling rule in the same area passes. Positive and negative tests are
separate columns so a happy path cannot stand in for the rejected or edge cases. Test IDs are
stable strings used in test names and in the release checklist. A rule with no test ID is not
releasable; a test with no rule is either a regression guard or should be deleted.

Legend for expected values:

- **Exact** — arithmetic taken directly from a binding rule or an owner example.
- **Derived** — arithmetic produced by stacking two binding rules; the derivation is shown.
- **Blocked** — the rule is unresolved; the test exists as a skipped placeholder tied to a §5.7
  decision. A blocked test may not be “made to pass” by choosing an answer in code. **No rule row in
  this plan is Blocked as of 2026-10-23** — the only remaining blocked item is the S8 purchase
  verifier, which waits on the O-06 runtime, not on a rule.
- **Fixture** — the value depends on a configuration constant (RP balance) and is asserted
  against the constant, not hard-coded as a rule.

### 5.3.2 Master traceability table

| Requirement | Binding source | Current implementation evidence | Planned task | Positive tests | Negative / edge-case tests | Acceptance criterion | Release evidence |
|---|---|---|---|---|---|---|---|
| General Pass cancels the round and starts a **new round number**; Normal ×2; Classic no multiplier; ladder ×2→×4→×6→×8 | A6.4 (`CANONICAL_RULES.md:591`), **A8.7 (`:793-805`)**, Current Rules item 1 (`:208`), Q1b/Q51 | Same-round replay; `Bidding.kt:493-508`; `QuickMatchViewModel.kt:154-167`; `Settlement.kt:517-524`; `TimeoutResolution.kt:979-984`; `MatchAdapter.kt:168-171`; `OnlineMatchViewModel.kt:724` | E2.5/S1 | T-01, T-02 | T-22 | Round 6 all-pass cancels round 6, deals fresh, and round 7 is played at ×2; successive passes give ×4, ×6, ×8 (not ×8 at the third pass); Classic consumes with **no** multiplier; the multiplier never exceeds ×8 | Engine report + §5.8 G2 |
| Sa'ayda per-round extension, final-round repeat, no caps | A6.2 (`:583-589`), **A8.8 (`:806-814`)**, Q2c/Q48 | Window-only; caps 5/1; no final repeat; helper **is wired** at `OnlineMatchViewModel.kt:851` (`RoundScore.kt:222-238`) | E2.6/S2 | T-03 | T-04 | Each counted Sa'ayda in 14–18 adds one round: 14→19, 14+15→20, 14+15+16→21, 13→18, generalized to 18+N with no rejection; a final-round Sa'ayda adds and repeats until a winner; a non-final post-window Sa'ayda adds nothing | Engine report |
| Super Call extension only when Caller succeeds | A6.5 (`:544`) | Extension independent of success | E2.6 | T-05 (success) | T-05 (failure) | A successful Caller Super Call adds one round; a failed one adds none; the Sa'ayda count still adds independently | Engine report |
| Normal Super Call bid² / half-up loss | A1 (`:220-221`), Q19c | `Scoring.kt:63` | E2.7 (verify only) | T-05 (win rows) | T-05 (loss rows) | Bid 8 win not sole = 64; win sole = 74; loss not sole = −32; loss sole = −42; no separate Caller +10 on the Super Call row | Engine report |
| Normal Dash + Risk adds both components | A6.3 (`:538-542`) | Risk dropped (`RoundScore.kt:79`) | E2.7 | T-06 (success, sole winner) | T-06 (failure, sole loser, Risk ladder) | At the owner example (total bids 15, Risk 10, takes 0) the score is +20; taking 2 gives −22; neither component is dropped or counted twice | Engine report |
| Classic scoring tables incl. Super Call +42/−20 | Classic Calculations Mode (`:145-178`) | `Scoring.kt:112` and table rows | E2.8 (verify) | T-07 (known rows) | T-05 (Classic row) | Bid 5 Classic Caller/With gives 5+13+10 = 28; Super Call gives +42/−20; sole cap is −22; no Normal formula leaks into Classic | Engine report |
| Classic Dash + Risk adds both components | A6.8 (`:547`), **A7.1 (`:569-590`)**, Q30 | Missing; numbers now settled | E2.8 (**unblocked 2026-10-22**) | T-07 (combined failure and success) | T-07 (sole winner, sole loser, Under/Over pairs) | On the owner's Classic case (total bids 15, Risk 10, a Normal Dash seat taking 2 against its 0 estimate) the score is **−12** = −2 + (−10); a success on that Over round is **+23** = +13 + 10; an Under-round success pair is **+33**; the sole-loser case is capped at **−22**; the miss is counted **once**, never the full Classic Risk formula | Engine report |
| Normal With = Caller’s winning suit, last bid in that suit | A6.9 (`:548`), Q19 | Suit-blind floor (`Bidding.kt:273-274`) | E2.4 | T-08 | T-12 | A player who bid the winning suit at any time is With at their last bid **in that suit**; a matching number in another suit is not enough; With at 5 scoring 25 / −12 | Engine report |
| Fast With = exact highest number, plus final-trump condition | A6.10 (`:606`), Q28, **A7.2 (`:648-669`)**, **A8.1 (`:752-758`)** | Everyone ≥8 With (`Bidding.kt:716`); no per-seat suit on a fast declaration (`:25`) | E2.3/E2.4/S4 | T-10 | T-10 (trump changed, earlier estimate) + T-11 (equal-highest tie) | Only a player whose final number equals the highest exactly **and** whose suit matches — and whose estimate was made under the final trump when it changed — is With; an 8 is not With when the highest is 10; the loser of an equal-number suit tie is **not** With; an exact number-and-suit duplicate is legal, the first declarer is Caller and later duplicates are With (A8.1) | Engine report |
| Fast Super Call re-declaration by every non-Caller | A6.11 (`:607`), Q29, **A7.2 (`:648-669`)**, **A8.1 (`:752-758`)** | One-shot; keeps later estimates (`Bidding.kt:577-609`) | E2.3/S4 | T-09 | T-09 (repeat/order) + T-11 (tie) | Each higher 8+ call wipes prior declarations and every non-Caller re-declares counter-clockwise, including a player who already re-declared; equal-highest is decided (A7.2) — 8♠ beats 8♥, 9♥ beats 8♠, never seat order; an exact number-and-suit duplicate is legal with the first declarer as Caller (A8.1) | Engine report |
| Shared Koz and shared King | Q2d, A6.2 (`:537`) | Tied lowest → Third (`Placement.kt:212-222`) | E2.9 | T-13 (cases 1, 4, 6) | T-13 (cases 2, 3, 5) | 100/99/99/99 → King + three Koz; 100/99/10/10 → King, Second, two Koz; all four equal → all King; a tied-lowest seat is never Third | Engine report |
| Vote Kick 2 Koz, unique Koz only, no ROOM vote | Q14d(1) (`decisions/2026-10-21…md:203`), Q14b (`:193`), Q2e; A5.1 `:488`/`:492` superseded | `LeaveMatch.kt:53` returns Koz1 — code gap; no unique-Koz guard | E3.5 | T-14 (charge) | T-14 (shared Koz, ROOM, return) | A passed Vote Kick against a unique Koz records 2 Koz at match finish in RANKED and UNRANKED; no vote when the Koz is shared; no vote in ROOM; a target who returns is not charged | Settlement tests |
| ROOM 1 Koz; RANKED/UNRANKED 2 Koz; charge at finish only; ROOM 15-timeout 1 Koz | A6.6 (`:597`), Q14b (`:193`), **A7.4 (`:694-702`)**, **A8.3 (`:765-770`)** | ROOM returns None (`LeaveMatch.kt:56`) | E3.5/S5 | T-15 (explicit leave, 15-timeout) | T-15 (return, server/quota failure, ROOM replacement) | ROOM departure records **1 Koz**, including the **15-timeout removal (A8.3)**; RANKED/UNRANKED record 2 Koz; the charge applies only at finish and only if the player did not return; a **voted permanent replacement keeps its own charge (A7.4: UNRANKED 2 Koz, ROOM none)**; infrastructure failure never charges | Settlement tests |
| 7th takeover removes and records 2 Koz in RANKED/UNRANKED and **1 Koz in ROOM, immediately**; an open ballot is cancelled | Current Rules item 8 (`:234`), Q10c, Q11, **A7.3 (`:671-693`)**, **A8.2 (`:759-764`)** | No takeover counter (`InactivityClock.kt:147`); a ballot-held seat cannot increment it (`TimeoutResolution.kt:376-380`) | E4.3/S7 | T-16 (7th row, ROOM 1 Koz) | T-16 (open ballot cancelled, WAIT, no stale completion) | The 7th qualifying takeover removes the seat and records 2 Koz in RANKED/UNRANKED and **1 Koz in ROOM**, fired **immediately even with a ballot open or a WAIT running**; the open ballot is **cancelled** and never completes; the event applies exactly once with no duplicate charge | Engine + settlement tests |
| Disconnect 10 s → takeover; 30 s vote; 90 s WAIT; 60 s all-gone cancel | A5.2 (`:514-522`), Q27c | 50 s clock only | E4.3 | T-16 (takeover, vote) | T-16 (no pause, reclaim, all-gone) | The bot takes over 10 s after the decision timer; the ROOM/UNRANKED vote runs 30 s; a winning WAIT pauses 90 s after the verdict; all humans gone for 60 s cancels; gameplay continues during the ballot | Engine + UI tests |
| Nine statistics for every mode; percentages over games played | Q14c, A6.7 (`:598`), A6.8 (`:604`), **A7.5 (`:703-727`)**, **A8.4 (`:771-777`)** | RANKED-only, placement-gated (`RankedStats.kt:103`, gate `:109`); ROOM also skipped by the charge accumulator (`:157`) | E5.1/S3/S6 | T-19 (all-mode, percent, bot-fill attribution) | T-19 (zero games, placement gate, UNRANKED mid-placement) | 10 ROOM + 10 UNRANKED + 10 RANKED with 3 Kings each → Games Played 30, King count 9, King % 30; a RANKED placement match is excluded; an UNRANKED match during placement counts; **a bot that finishes as King in a vacated seat never puts a King in the departed human's profile — that seat's human row is Koz and the match adds +1 Games Played (A7.5, A8.4)** | Stats tests |
| 90-day seasons, lazy reset, two-step reset | Q22, `SeasonResetRun.kt:80` | Bulk sweep, 10,000 cap | E5.2 | T-19 (season length, lazy reset) | T-19 (idempotence, reset race) | `endsAt = start + 90 days`; one reset on first access after two missed seasons; two reset runs produce one migration; settlement and reset on one profile keep one total order | Season tests |
| Auto-Match with 2-min bot fill and 30 s vote; humans-only strength; shared pool with a ≤1-tier band | Q7c/Q26, **A8.6 (`:787-792`), Q41**, decisions §F | No implementation (`MatchmakingPort.kt:29-33`) | E4.1/E4.2 | T-20 (join, vote passes, RP with bots) | T-20 (no table, vote fails, tier band) | Auto-Match joins a waiting table within **one tier** after showing settings; no table → message + Create Table; 2 minutes from first player then a 30 s vote requiring more than half of waiting humans (non-votes reject); strength from humans only; the band is **absolute** and never widens after 180 s (A8.6). Directionality (whether one tier **up** pairs) remains **O-15** | Matchmaking tests |
| Guests cannot enter Ranked; email sign-up and Google sign-in for Ranked; linking preserves uid | Decisions §D, Q5, Q21 | Anonymous/email only; no Google, sign-up or gate (`AuthRepository.kt:19-20`) | E6 | T-21 (guest ROOM, email, Google) | T-21 (guest Ranked, linking; App Check not required) | A guest plays ROOM/UNRANKED and is blocked from Ranked; email sign-up and Google sign-in work; linking preserves the uid | Device walkthrough |
| ROOM/UNRANKED four matching reports; recorded limitation | Decisions §J, Q15c | No convergence enforcement for ROOM/UNRANKED (`Settlement.kt:340-348`) | E3.6 | T-17 (unanimous, duplicate, replay) | T-17 (split, missing, collusion) | A ROOM/UNRANKED result is recorded once on the fourth matching report; splits and missing reports stay pending; duplicates are deduped; collusion is accepted and documented; **a departed seat's recorded standing is overridden to Koz before aggregation, so the bot's own result stays the bot's (`Settlement.kt:340-348`, `RankedStats.kt:144-147`; A7.5)** | Settlement tests |
| Exactly-once settlement with durable outbox | Decisions §I | In-memory 6 retries (`OnlineMatchViewModel.kt:1312`) | E3.4 | T-18 (happy, duplicate) | T-18 (lost response, process death, quota, season race) | Duplicate calls, lost responses, process death and outbox replay produce one stored result and one RP delta; a quota failure writes nothing | Fault injection |
| ≥200 seeded engine/server parity | Brief Stage 0 E2 | Not run | E1.2 | T-23 (corpus, evidence) | T-23 (diff definition, known gaps) | ≥200 seeds × FULL/MINI × Normal/Classic produce zero unexplained JVM/server diffs; known gaps are listed by defect ID; the post-fix run has zero diffs | Stage 0 report |
| Auction floor, Call Cap, 13 Rule, Dash max 2, Risk values | Current Rules §2/§5 (`:89-103`, `:195-203`) | Implemented; floor defects noted | E2.2 | T-24 (minimum bid, Super threshold, Risk values) | T-24 (third Dash, 13 Rule scope, fast Call Cap, With floor) | Bid 3 is rejected and 4 accepted; a third pre-bid Dash is rejected; the 13 Rule forces 1–13 only when the first three sum to 13; Call Cap applies in normal rounds only; a With bid below the last winning-suit bid is rejected | Engine report |

### 5.3.3 Rule and scoring vectors

The vectors below are the acceptance language for the engine and settlement work. They are
written as round-level or seat-level cases so they can be implemented directly as table-driven
tests.

#### T-01 — General Pass, Normal Calculation

| Step | State | Expected |
|---|---|---|
| 1 | Round 6 is a normal round; all four pass the auction | Round 6 is **cancelled**; no scores are written for it; the pass consumes the round toward completion |
| 2 | Round 7 begins | Cards are dealt **fresh** into a **new round number** (not a replay of round 6); the dealer rotates; round 7 is played at **×2** (A8.7 / Q51) |
| 3 | Caller bids 5, takes 5, not sole | `(10 + 5 + 10) × 2 = 50` **Exact** |
| 4 | Round 7 is also all-pass | Round 7 cancelled; fresh deal into round 8 at **×4** |
| 5 | Round 8 is also all-pass | Round 8 cancelled; fresh deal into round 9 at **×6**, not ×8 |
| 6 | Round 9 is also all-pass | Round 9 cancelled; fresh deal into round 10 at **×8** |
| 7 | Round 10 is also all-pass | Round 10 cancelled; fresh deal into round 11, multiplier stays **×8** (cap) |

Note: step 5 is the engine’s current defect (`minOf(prev × 2, 8)` yields ×8 at this point).
“Fresh deal into the next round number” is the single behaviour behind both the words *redeal*
(A6.1) and *consumes the round number* (A6.4); A8.7 defines the term and the engine’s same-round
replay is the defect → S1.

#### T-02 — General Pass, Classic Calculation

| Step | State | Expected |
|---|---|---|
| 1 | Classic match, round 6 all pass | Round 6 cancelled and consumed; no scores |
| 2 | Round 7: Caller bids 5, takes 5, not sole | Cards dealt fresh into the new round number; `5 + 13 + 10 = 28`, **no multiplier** **Exact** |
| 3 | The same round under the current engine | `56` (doubles) — this is the defect A6.1 names |
| 4 | Rounds 7 and 8 also all-pass | Each cancelled and consumed; the multiplier stays **1** in every later round — Classic never accumulates a double (A8.7) |

#### T-03 — Sa'ayda count and Game Type

| Case | Setup | Expected |
|---|---|---|
| FULL, one counted Sa'ayda | Sa'ayda in round 14 only | Planned last round = **19** **Exact** (Q48) |
| FULL, two counted Sa'aydas | Sa'aydas in rounds 14 and 15 | Planned last round = **20** **Exact** (Q48) |
| FULL, two counted, non-adjacent | Sa'aydas in rounds 14 and 16 | Planned last round = 18 + 2 = **20** **Exact** |
| FULL, three counted | Sa'aydas in 14, 15, 16 | Planned last round = **21** **Exact** |
| FULL, one before the window | Sa'ayda in round 13 only | Planned last round = **18** — a pre-14 Sa'ayda never extends **Exact** (Q48) |
| MINI, one counted | Sa'ayda in round 6 only | Planned last round = **11** **Exact** |
| MINI, two counted | Sa'aydas in rounds 6 and 8 | Planned last round = 10 + 2 = **12** **Exact** |
| Post-18 non-final | Sa'ayda in round 20 after an extension | Adds **nothing** (A6.2) |
| Cap removal | Five Sa'aydas in 14–18 | Planned last round = **23**; no `ALREADY_EXTENDED` rejection **Exact** (Q2c) |

`computeRoundExtension` (`RoundScore.kt:222-238`) computes this today but has **no production
caller**, so nothing raises `maxRounds` and `isMatchComplete` reads the base value
(`Session.kt:568`). These rows are T-03's acceptance language for **S2**, not a passing suite.

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

#### T-07 — Classic Dash + Risk (unblocked 2026-10-22 by A7.1 / Q30)

| Case | Setup | Expected |
|---|---|---|
| Normal Dash Classic, success under | Total bids ≤ 13, takes 0 | **+23** **Exact** |
| Normal Dash Classic, success over | Total bids > 13, takes 0 | **+13** **Exact** |
| Normal Dash Classic, failure over | Total bids > 13, takes 2 | **−2** (failure over = −T) **Exact** |
| Normal Dash Classic, failure under | Total bids ≤ 13, takes 2 | **−10** **Exact** |
| Risk component, success/failure | Risk player, success/failure | **+10 / −10** **Exact** |
| Combined, failure over | Total bids 15 (Over), Risk value 10, a Normal Dash seat whose estimate is 0 and takes 2 | `−2 (Dash −T) + (−10) = **−12**` **Exact** (A7.1) — **not −14**, which would count the same miss twice |
| Combined, success over | Same table, takes 0 | `+13 + 10 = **+23**` **Exact** (A7.1) |
| Combined, success under | Total bids ≤ 13, takes 0 | `+23 + 10 = **+33**` **Exact** (A7.1) |
| Combined, sole winner, over | Combined role, takes 0, everyone else fails | `+13 + 10 + 10 (sole) = **+33**` **Derived** |
| Combined, sole winner, under | Combined role, takes 0, everyone else fails, total bids ≤ 13 | `+23 + 10 + 10 = **+43**` **Derived** |
| Combined, sole loser | Combined role fails, everyone else succeeds | `2 × (−12) = −24`, floored at **−22** **Derived** (`CANONICAL_RULES.md:178`) |
| Risk ladder on the combined seat | Risk value 20 / 30 instead of 10, same Over round | failure takes 2 → `−2 + (−20) = −22` / `−2 + (−30) = −32`; success takes 0 → `+13 + 20 = +33` / `+13 + 30 = +43` **Derived** (flat Risk value, A7.1) |

**Assertion hazard (from the derived pair above):** Dash-alone success Under is **+23** and
combined success Over is also **+23**. Every row must assert the round's **TotalBids** alongside the
score, or a mode bug can pass a score-only assertion. A7.1's rule is *component addition* — the seat
keeps its Classic **Dash table** value and adds the **flat ±Risk value**; the full Classic Risk role
formula (`E+13+10` / `−(miss+10)`) belongs to a Risk seat that is not also a Dash seat, and
Wizz-Risk (`E+33`) is a different role again.

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
| 2 | B | 9 | Spades (changed again) | C, D, A; **not B** — A re-declares despite having declared after the first Super Call |
| 3 | C | 10 | Clubs | D, A, B |
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

#### T-11 — Equal-highest fast Super Call (decided 2026-10-22 by A7.2 / Q31)

| Case | Setup | Expected |
|---|---|---|
| Two 8s, different trumps | A chooses ♥, B chooses ♠, C and D lower | **B is Caller at 8 with ♠ final; A is not With** — equal number is broken by the canonical ladder ♠ > ♥ (`CANONICAL_RULES.md:27-31`, `:59`, `:68`), and With needs the number **and** the suit. **Exact (A7.2)** |
| Suit does not outrank number | A declares 8♠, B declares 9♥ | **B is Caller at 9 with ♥** — the higher number wins whatever the suit **Exact (A7.2)** |
| Sans beats a suit on a tie | A declares 8♠, B declares 8 as Sans | **B is Caller, trump Sans** — Sans is strongest (5) **Exact (§1 `:27-31`)** |
| Loser of a suit tie is not With | A 8♠ Caller, B 8♥ | B is **not** With; an 8♥ seat scoring with the Caller is a failure of this row **Derived from A7.2** |
| Seat order is never the answer | Same table, A seats before B; re-seat the same two declarations in the opposite order | The Caller and trump are **identical** — the assertion fails against the current engine **Exact (A7.2)** |
| Two 8s then a 9 | C declares 9 | C is Caller; the A/B tie no longer decides the call, but With still depends on the final number **and** trump **Derived** |
| Two 8s, **same suit** | A and B both declare 8♥, neither outbids | **Legal, not blocked (A8.1 / Q35).** The declaration is accepted; the **first declarer is Caller** and each **later exact duplicate is With** — symmetric if the seat order is reversed. Declaration order decides **only** this exact-duplicate case; it never breaks a different-suit tie |
| Three ways on one number | A 8♠ and B 8♠ and C 8♥ declare | Higher suit first: A/B (♠) beat C (♥); between A and B the first declarer is Caller and the other is With **Exact (A7.2 + A8.1)** |
| Exact duplicate, then a raise | A 8♥ Caller, B 8♥ With, then B declares 9♥ | B is Caller at 9 with ♥; the duplicate no longer decides anything, and With is recomputed on the final number **and** suit **Derived** |

The A8.1 rows are enforceable only once a fast declaration carries its **own** suit and the
declaration order is recorded per seat: the engine stores no per-seat suit (`Bid(val type, val
amount)`, `Bidding.kt:25`) and sorts ties by `biddingOrder` (`:710`, `:746`). → **S4**.

The existing engine sorts by amount then seat order (`Bidding.kt:707-710`, `.thenBy { order.indexOf(it) }`
at `:709`) and stores no suit per fast declaration (`Bid(val type, val amount)`, `:25`). Both are now
ruled violations (IB-16, IB-17), not open questions: that sort must be deleted, not asserted.

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
four base values are **final** (O-11 → Q45, `RpEngine.kt:65-68`); only the Mini multiplier and
the tier-band scaling rates remain tunable. The vectors test the
**mapping and arithmetic**, not a balance. O-11 is closed.

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
for cases 2 and 3 (case 5, 100/90/90/80, already places the unique 80 last).

#### T-14 — Vote Kick

| Case | Setup | Expected |
|---|---|---|
| Charge | RANKED or UNRANKED, Vote Kick passes against a unique Koz | **2 Koz**, applied at match finish |
| Shared Koz | Two players tied at the bottom | Vote unavailable; no kick target |
| ROOM | Any ROOM table | Vote unavailable (ROOM is not a Vote Kick mode) |
| Return | Target reconnects and finishes the match | No charge |
| RP | Vote Kick in RANKED | Koz charge is a departure record; RP uses the match outcome, not a second last-place penalty |
| Ballot | Threshold and UI | Per S63 design; this plan tests the **charge and availability**, not a re-invented threshold |

Charge provenance: a passed Vote Kick is **2 Koz in RANKED and UNRANKED** (Q14b
`docs/decisions/2026-10-21-server-authority-and-owner-rulings.md:193`, Q14d(1) `:203`); A5.1's
1 Koz (`CANONICAL_RULES.md:488`, `:491`) is superseded, and `OPEN_QUESTIONS.md` §B is stale
(D-01, D-12). The engine returns `Koz1` (`LeaveMatch.kt:53`) and `LeaveMatchTest` asserts it —
E3.5 changes both code and test.

#### T-15 — Departure charge matrix

| Path | ROOM | UNRANKED | RANKED |
|---|---|---|---|
| Explicit Leave Match | **1 Koz** | **2 Koz** | **2 Koz** |
| Automatic 15-timeout removal | **1 Koz** — **A8.3 / Q37**, closing O-03d. A5.1's cell says only "no 2-Koz rule" (`:487`) and its note (`:496-498`) reads as no charge in ROOM; A8.3 supersedes that with a positive value rather than repealing it to zero, and keeps this path distinct from the 7th takeover and from a permanent replacement | **2 Koz** | **2 Koz** |
| Vote Kick | n/a — no vote in ROOM | **2 Koz** | **2 Koz** |
| Disconnect, permanently replaced | **0** — ratified by **A7.4 / Q33** (the A5.1 row `:489` stands; A6.12's "charges nothing" removed only the *leave* penalty) | **2 Koz** (A7.4) | No disconnect vote in RANKED; a 15-timeout removal or 7th takeover records **2 Koz** |
| Return before finish | 0 | 0 | 0 |
| 7th bot takeover | **1 Koz** — **A7.3 / Q32**, fired immediately even with a ballot open or a WAIT running | **2 Koz** | **2 Koz** |
| Server or free-quota failure | 0 | 0 | 0 |
| Charge timing | Only when the match finishes and the player did not return | Same | Same |
| RD31 2-Koz rule | **Excluded** | Applies to the 2-Koz paths | Applies to the 2-Koz paths |

The 2 Koz in Q14b/Q14d(1) is the final charge. A5.1's “1 Koz, final” was itself a one-path
amendment to RD31's 2-Koz departure result
(`docs/decisions/2026-10-16-open4-open5-owner-closure.md:57`) and is superseded by Q14d(1); a passed
Vote Kick is 2 Koz, not 1 and not 4. **Every cell in this table is now owner-settled: Q14b (leaves),
Q14d(1) (Vote Kick), A7.3 (ROOM 7th takeover = 1 Koz), A7.4 (permanent replacement), and
A8.3 / Q37 (ROOM 15-timeout = 1 Koz).** `DisconnectVote.permanentCharge` (`:69-73`) already returns exactly the
A7.4 values and needs a caller, not a change (IB-19); the ROOM 15-timeout path records no charge today
(`TimeoutResolutionDb.kt:326-343`) → S5.**

Abandonment is a separate outcome, not a departure charge:

| Case | Result |
|---|---|
| Last human explicitly leaves | Match cancelled immediately; all four seats recorded Koz; RANKED deducts the engine’s last-place RP loss; ROOM/UNRANKED record 1/2 Koz respectively with no RP (Q27(4)) |
| All humans disconnected | Wait 60 s; if nobody returns, cancel and apply the same outcome (Q27c) |
| Server/quota failure | Never charged (Q27(3)) |

#### T-16 — Disconnect, takeover, reconnect and cancellation

| Time / event | Expected |
|---|---|
| Disconnect at t=0 | Seat remains; no bot yet; the decision clock runs to its timer |
| Decision timer (5/10/15/20 s) expires, still disconnected | MEDIUM bot plays that decision; the 15-timeout counter increments; the 10 s takeover clock starts |
| Timer + 10 s, still disconnected | The creator-selected bot takes the seat; the takeover counter increments |
| Return while the bot holds the seat | Human reclaims; the bot stops |
| Reconnect cycles 1–7 | Allowed; each takeover needs the decision timer plus 10 s |
| 7th takeover | Seat removed and **1 Koz recorded in ROOM** (2 in RANKED/UNRANKED); the removal **fires immediately, even with a 30 s ballot open or a 90 s WAIT running**, and exactly once — no second charge, no counter reset (A7.3). **An open ballot is CANCELLED at that instant and never completes (A8.2 / Q36)** — no expiry verdict, no stale write, no re-open |
| 15-timeout removal | Independent counter; not the same as takeover; charge per T-15 — **1 Koz in ROOM (A8.3 / Q37)**, 2 Koz in RANKED/UNRANKED |
| ROOM/UNRANKED disconnect vote | 30 s window, play continues, majority 2 of 3; a winning WAIT pauses 90 s after the verdict; no agreement → configured bot |
| No pause during ballot | Gameplay continues until the verdict |
| Takeover during an open ballot | The removal is **not** deferred by the ballot (A7.3) and the ballot goes terminal **CANCELLED** in the same step (A8.2) — fired during **VOTE or WAIT**, deterministic, with no stale completion and no double charge. Today it cannot even happen: a ballot-held seat counts as bot-held and cannot increment the counter (`TimeoutResolution.kt:376-380`), and WAIT freezes every deadline (`:358`, `TimeoutResolutionDb.kt:212-218`, `:244-250`) — E4.3/S7 must lift that suppression on purpose |
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
| ROOM table size | 3 humans ready | Match does not start; four is a **start** condition (A6.8) |
| ROOM seat vacated mid-match | 3 humans + 1 leaves after the start | Play continues; **a bot may fill the seat (A7.5)**; the departed human's row is Koz whatever the bot finishes as |

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
| Departure charge | 2-Koz UNRANKED departure | Koz +2 is owner-required (the charge enters statistics); the current code also adds Games Played +1 (`RankedStats.kt:32`). **A8.4 / Q38 settles the ROOM case the same way: the match adds one to that human's Games Played and the percentage denominator includes it** — the row is no longer skipped, and the ROOM writer that would make it evaluable does not exist yet (→ S3) |
| Bot-filled ROOM, bot wins King | 4 humans start, one leaves, a bot takes the seat and the table finishes with the bot first | The departed human's row is **Koz, never King** (A7.5), and that match adds **+1 Games Played** (A8.4); the three completing humans keep their own placements and percentages **Exact (A7.5, A8.4)** — unevaluable until E5.1 adds a per-seat override, because ROOM accumulates nothing today (`accumulateDepartureCharge` guard `:157`) |
| Bot row attribution | Same match | No bot uid enters any human profile; the bot's tricks and score stay on the seat **Exact (A7.5)** |
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
| Tier band | Two players 2 tiers apart | Cannot join the same Auto-Match table — the band is **absolute** and never widens, including after the 180 s fill timer (A8.6 / Q41) |
| Directionality (open) | Gold and Platinum, one tier apart | **O-15.** `isPoolEligible` (`MatchmakingPool.kt:82-91`) accepts the same tier or exactly one tier **below** and never one above, so whether the Gold→Platinum direction pairs is **not settled**; no matchmaking change until the owner confirms. The row asserts only the distance rule, never a direction |

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
| Classic General Pass | Round cancelled and consumed, cards dealt fresh into the next round number, multiplier stays **×1** (A8.7) |
| Classic, two passes in a row | Still ×1 on the third round — Classic never accumulates a multiplier (A8.7) |

Every “all pass” row means **cancelled and consumed, cards dealt fresh into the next round number**
— not a replay of the same round (A8.7 / Q51; C-2).

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
| General Pass consumes a round | Round 14 is all-pass | Round 14 is cancelled and consumed; the match budget advances to 15; **no Sa'ayda extension fires**, because no round was played (A8.7, A8.8; S1/S2 acceptance) **Derived** |
| General Pass at the planned last round | The planned last round is all-pass | The pass cancels that round and it counts toward completion, so `isMatchComplete` does not add a round on its own (A8.7; S1 acceptance 3) **Derived** |

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
| P0 — Baseline and decisions | Confirm baseline, **O-01…O-11 recorded; the O-01…O-04 closure is 2026-10-22 (A7.1–A7.5 / Q30–Q34) and its residues O-02b/O-03b/O-03d/O-04b were closed 2026-10-23 (A8.1–A8.4 / Q35–Q38)**, record architecture questions | This plan approved | Decisions recorded; work queue unblocked; the six still-open owner items (O-12, O-13, O-15, O-16, O-17, O-18) registered in `OPEN_QUESTIONS.md` §A | M0 | 16–28 |
| P1 — Stage 0 feasibility | All 13 experiments; measurements; go/no-go memo | P0; access to a free-tier account | Every experiment has a result or a documented blocker; runtime recommendation | M1 | 150–260 |
| P2 — Rules and engine | Auction, fast Super Call, With/Wazz, General Pass, Sa'ayda, extension, Dash+Risk, Classic, placement, RP input, takeover clock | P0. **O-01/O-02 were the only owner items gating engine rules and both are decided**, so E2.3/E2.8 run on their numbers now; the seat-order tie-break removal (IB-16), per-seat suit (IB-17), exact-duplicate declaration (A8.1), new-round General Pass (A8.7) and Sa'ayda wiring (A8.8) are in scope | §5.3 engine vectors pass; no P0 rule gap | M2 | 248–416 |
| P3 — Ranked runtime and settlement | Authority layer, durable outbox, exactly-once settlement, departure charges, recovery, version gate | M1 go; ADR approved; M2 engine frozen | T-15/T-17/T-18 pass; fault injection clean | M3 | 280–504 |
| P4 — Matchmaking, bots, disconnect | Auto-Match, 2-minute fill, 30 s vote, takeover counter, 7th removal, 60 s cancel | M3. **O-03 is decided (A7.3), O-03b is decided (A8.2)** — the counter must be built from scratch, the ballot suppression lifted, and an open ballot made terminal **CANCELLED** on the 7th takeover | T-16/T-20 pass | M4 | 128–224 |
| P5 — Statistics and seasons | All-mode nine stats, percentages, lazy 90-day reset, race safety | M3 | T-19 passes | M5 | 96–168 |
| P6 — Authentication and account | Email sign-up/sign-in, guest gate, linking | M3 | T-21 passes on device | M6 | 44–76 |
| P7 — UI design | 27 surfaces on the fixed 932×430 landscape frame (D10), EN/AR, RTL, states, copy | M2 rules frozen; M3 API shape frozen | Design sign-off on all 27 surfaces | M7 | 168–280 |
| P8 — Native conversion | Compose implementation, navigation, state, accessibility, localization | M7 sign-off; M6 auth | Every non-deferred surface wired; UI tests pass | M8 | 288–480 |
| P9 — Hardening and operations | Localization review, observability, backup drill, rollback, performance | M8 | Runbook, backup ≥90%, rollback rehearsal | M9 | 96–168 |
| P10 — Store readiness and real-people testing | Listing, Data safety, credentials, 12 testers × 14 days | M9; build is RC | 14 continuous days complete; production access granted | M10 | 72–136 |
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
| E10 | Write/cleanup costs and retention | Do retention and cleanup fit request and storage limits? | Measure write/cleanup costs; model retention | Fits free tier; retention policy costed | P3, G5 |
| E11 | Anonymous → registered linking | Does Firebase linking preserve the uid and data? | Link a real anonymous account to email in a test project | UID stable; stats/history intact | P6 |
| E12 | Hibernation and duration cost | Does hibernation keep idle cost inside the free tier? | Idle table for a full match duration; measure | No billing trigger; duration cost recorded | P3 |
| E13 | Account deletion and store policy | What does current Play policy require for closed testing and for account deletion, and what risk does the V1 deferral carry? | Verify current Play policy pages and API behaviour | Written checklist with source URLs and access date | P10, G8 |

Stage 0 does **not** decide the architecture by itself. It produces measurements and a
recommendation; the owner approves or rejects the runtime through a new ADR (O-06).

### 5.4.4 Milestones

| Milestone | Definition of done |
|---|---|
| M0 | Baseline accepted. **DONE on the decisions half: O-01…O-04 decided 2026-10-22 (A7.1–A7.5 / Q30–Q34) and their four residues closed 2026-10-23 (A8.1–A8.4 / Q35–Q38); O-05…O-11 are recorded by the 2026-10-22/23 rulings and O-12/O-13/O-15/O-16/O-17/O-18 stay open.** M0 still needs the baseline confirmation itself |
| M1 | Stage 0 memo: every experiment has a result, a blocker, or a measured “no” |
| M2 | Engine rule freeze: §5.3 engine vectors green; rule documents unchanged except recorded defects |
| M3 | Ranked runtime and settlement accepted: exactly-once and recovery evidence |
| M4 | Matchmaking, bot fill and disconnect flows accepted |
| M5 | Stats and seasons accepted |
| M6 | Auth and account lifecycle accepted on device |
| M7 | Design sign-off: all 27 surfaces designed on the 932×430 landscape frame, EN/AR |
| M8 | Feature-complete Android build: all non-deferred surfaces wired |
| M9 | Hardening complete: localization review, backup drill, rollback rehearsal |
| M10 | Closed test complete: 12 testers × 14 continuous days; production access granted |
| M11 | Owner accepts release; signed bundle ready for staged rollout |

### 5.4.5 Dependency map

| Workstream | Depends on | Why |
|---|---|---|
| Engine rules | ~~O-01, O-02~~ **decided 2026-10-22 (A7.1, A7.2)**; ~~O-02b~~ **decided 2026-10-23 (A8.1)** | The numbers, the tie-break and the exact-duplicate rule all exist; what remains is code work — delete the seat-order sort (IB-16), put a suit and declaration order on a fast declaration (IB-17), move General Pass to a new round number (A8.7) and wire the Sa'ayda extension (A8.8) |
| Ranked runtime | Stage 0 E1–E10, ADR approval | No architecture may be assumed |
| Settlement | Runtime, engine freeze, backup policy | Settlement re-runs the engine and needs a durable store |
| Departure charges | Settlement. ~~O-03, O-03c, O-03d~~ **decided (A7.3, A7.4, A8.3)**; `permanentCharge` still has no caller (IB-19) | Charges are applied at finish by the settlement path |
| Matchmaking | Runtime, auth, O-07 (band settled by A8.6; **directionality is O-15**) | Queue and table membership need a stable identity |
| Bots/disconnect | Runtime, matchmaking. ~~O-03, O-03b, O-04~~ **decided (A7.3, A8.2, A7.5)** | Bot fill and takeover share seat state |
| Statistics | Settlement records. ~~O-04, O-04b~~ **decided (A7.5, A8.4)** | Stats fold finished outcomes |
| Seasons | Statistics/profile store | Reset operates on the same profile |
| UI design | Engine freeze, runtime API shape | Designs must not encode wrong rules or unavailable data |
| Native conversion | Design sign-off, engine/services APIs | Conversion is a rendering/wiring step, not a design step |
| Closed testing | Feature-complete build, store assets | Testers need a usable build and a compliant listing |
| Release | 14 continuous test days, production access, owner acceptance | Policy and owner gates |

### 5.4.6 Critical path

```
P0 ─┬─→ P1 Stage 0 ─┐
    └─→ P2 engine ──┴──→ P3 runtime ──→ P4/P5/P6 in parallel ──┐
                                                               ▼
                                       P7 design → P8 conversion → P9 hardening
                                                                     │
                                                                     ▼
                                                    P10 closed test (14 days) → P11 release
```

The critical path is **P0 → (P1 Stage 0 ∥ P2 engine) → P3 → P7 → P8 → P10 → P11**. P1 and P2 run
in parallel after P0 and **both** gate P3: Stage 0 supplies the go/no-go and the ADR input, and P2
supplies the frozen engine that P3's server runtime re-executes. P2 is therefore on the critical
path, not merely an input to design; “P2 must finish before P7” would understate the dependency.
P4–P6 run after P3 and before P7's sign-off where they change the UI contract.
The 14-day closed test and the review period are **elapsed time, not engineering time**; they
dominate the calendar tail and cannot be compressed by adding engineers.

### 5.4.7 Sequencing rules

1. **No rule is implemented twice.** The engine is the only rules implementation; the server
   runtime executes the same module (Stage 0 E1/E2).
2. **No design before rule freeze.** A beautiful screen for a wrong rule is rework.
3. **No conversion before design sign-off.** Compose work starts from approved **932×430
   landscape** artboards (D10), not from unapproved mockups; a portrait frame is non-compliant
   and is re-cut, never implemented.
4. **No real-people testing before feature completeness.** Closed testing is the last step
   before publishing; using it as a beta for unfinished features burns the 14-day clock.
5. **Stage 0 does not reorder delivery.** It gates the runtime; it does not move design ahead of
   code or testing ahead of conversion.
6. **A blocked rule does not block unrelated work.** O-01, O-02, O-03 and their 2026-10-23
   residues (O-03b/O-03d) are decided, so nothing in that sentence blocks anything today. What
   remains in this document as a rule-level block is **O-15** (matchmaking directionality) — a
   single test row in T-20, never a placeholder value — and **O-06** (no approved runtime), which
   blocks Play Billing verification and the whole Ranked runtime. O-12/O-13 (voice, ads providers)
   block no test: no code exists for either.

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
| Comparability | The 2026-09-21 plan’s headline was **1,188 h** pre-contingency and **~1,366 h** after 15% for an overlapping but different scope (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:1330-1331`); this plan adds Stage 0, server-authoritative Ranked, all-mode statistics, full design and store work |
| Uncertainty | Low for infrastructure-dependent work (E1, E3), medium for engine/UI, higher for store timing |

The ranges are deliberately wide where the work depends on an unapproved architecture or an
unresolved rule. A single-point estimate would hide the two largest risks in the project.

### 5.5.2 Estimate summary

| Epic | Scope | Low (h) | High (h) | Confidence | Class | External gate |
|---|---|---|---|---|---|---|
| E0 | Baseline and decisions | 16 | 28 | High | Mandatory | **Decided 2026-10-22/23** for O-01…O-04 and O-05…O-11 (A7.1–A7.5 / Q30–Q34, A8.1–A8.8 / Q35–Q52); the six remaining owner items O-12/O-13/O-15/O-16/O-17/O-18 gate nothing in E0 |
| E1 | Stage 0 feasibility (13 experiments) | 150 | 260 | Low | Mandatory | Free-tier account; owner accepts memo |
| E2 | Rules and engine | 248 | 416 | Medium | Mandatory | ~~O-01, O-02, O-02b~~ **decided (A7.1, A7.2, A8.1)**. **The range may be exceeded**: A7.2 needs a suit on every fast declaration (IB-17), which is a state-model change, not a filter, and A8.7/A8.8 change the round model |
| E3 | Ranked runtime and settlement | 280 | 504 | Low | Mandatory, conditional on ADR | Stage 0; O-06 |
| E4 | Matchmaking, bots, disconnect | 128 | 224 | Medium | Mandatory (part blocked) | ~~O-03, O-03b~~ **decided (A7.3, A7.5, A8.2)**; O-07 decided (A8.6 / Q41). Still open: **O-15** (band directionality) and the provider items O-12 (voice) / O-13 (ads). **The range may be exceeded**: there is no takeover counter at all, and A7.3's immediate firing reverses the ballot-time suppression |
| E5 | Statistics and seasons | 96 | 168 | Medium | Mandatory | None. A7.5/A8.4 add a per-seat "departed → Koz + 1 Games Played" override where ROOM accumulates nothing today (`RankedStats.kt:157`); **S3** carries the work |
| E6 | Authentication and account | 44 | 76 | Medium | Mandatory | Firebase project access |
| E7 | UI design | 168 | 280 | Medium | Mandatory | Design sign-off |
| E8 | Native conversion | 288 | 480 | Medium | Mandatory | Design sign-off |
| E9 | Hardening and operations | 96 | 168 | Medium | Mandatory | None |
| E10 | Store readiness and real-people testing | 72 | 136 | Medium | Mandatory | 12 testers; Play review |
| E11 | Release candidate and acceptance | 40 | 72 | High | Mandatory | Owner acceptance |
| **Total** | | **1,626** | **2,812** | | | |

**No hour in this table was rewritten by the 2026-10-22 or 2026-10-23 decisions.** Closing O-01…O-04
and their four residues removed risk, not scope: E2.3 gained a state-model change (IB-17), E4.3 gained
a counter that does not exist plus a reversal of the ballot-time suppression, and E5.1 gained a
per-seat attribution override. The 2026-10-23 rulings add three behaviours with **no dedicated line
item in this baseline** — General Pass round progression (S1), Sa'ayda wiring (S2) and all-mode
statistics (S3). They are not folded into the numbers below; an **indicative +85–150 h** delta is
recorded in §5.10 and **is not added to the total** until the owner approves an estimate revision.
The ranges stand, and the total is unchanged at 1,626–2,812 h.

Planning midpoint: **2,219 engineering hours** (≈277 person-days). At 40 productive hours per
week this is **41–70 weeks** of engineering before external waits; at 50 h/week, **33–56 weeks**;
at 30 h/week, **54–94 weeks**. These are capacity arithmetic, not a schedule promise.

### 5.5.3 Epic detail

#### E0 — Baseline and decisions (16–28 h)

Goal: make the baseline reproducible and remove decision blockers from the critical path.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E0.1 | Verify clean build and test baseline on a fresh clone | `native/gradlew`; CI workflows | none | All four module suites reproduce the recorded counts | 4–6 | High |
| E0.2 | Decision pack for O-01, O-02, O-03 with examples and consequences | §5.7; `CANONICAL_RULES.md` | none | **DONE 2026-10-22/23** — A7.1–A7.5 record Q30–Q34 and A8.1–A8.8 record Q35–Q52; the four residues the pack surfaced (O-02b, O-03b, O-03d, O-04b) are **closed**, and the six items left in §A (O-12, O-13, O-15, O-16, O-17, O-18) carry no defaults | 6–10 | High |
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
| E1.10 | E10 write/cleanup/retention costs | Cost and retention note | 4–8 | Medium |
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
| E2.3 | Fast Super Call re-declaration, repeated higher calls, tie rule, exact duplicate | `Bidding.kt:560-625`, `:695-722`, `:25`, `:710`, `:746` | ~~O-02, O-02b~~ **decided (A7.2, A8.1)** | T-09 and **every T-11 row green**, including the same-number-same-suit row (legal; first declarer Caller, later duplicates With). Scope includes deleting the seat-order sort (IB-16, `:709`) and storing a suit plus declaration order per fast declaration (IB-17, `:25`) | 32–52 | Medium |
| E2.4 | Normal and fast With/Wazz sets and numbers | `Bidding.kt`; scoring role assignment | E2.2 | T-08, T-10, T-12 green | 32–52 | Medium |
| E2.5 | General Pass: cancels the round, fresh deal into a **new round number**, ladder ×2/×4/×6/×8, Classic no multiplier, consumes a round toward completion | `Bidding.kt:493-508`; `Session.kt:337-352`, `:568`; client pass paths; services and settlement replay | E2.1 | T-01, T-02, T-22, T-25 green; round number, deal point and multiplier identical on client, services and functions replay (A8.7 / Q51, **S1**) | 22–36 | High |
| E2.6 | Sa'ayda count, final-round repeat, cap removal, extension on success, wired into the production match flow | `RoundScore.kt:222-238` (wired at `OnlineMatchViewModel.kt:851`); `Session.kt:67-76`, `:213`, `:480-495`, `:568` | E2.5 | T-03, T-04, T-05 green; `computeRoundExtension` has a production caller and `maxRounds` moves 14→19, 14+15→20, 14+15+16→21, 13→18 (A8.8 / Q48, **S2**) | 26–44 | Medium |
| E2.7 | Normal Dash + Risk stacking | `RoundScore.kt:71-84`; `Scoring.kt:63-75` | E2.1 | T-06 green | 16–28 | High |
| E2.8 | Classic scoring verification and Classic Dash + Risk | `Scoring.kt:100-130` | ~~O-01~~ **decided (A7.1 / Q30)** | T-07 green on every row, including combined **−12 / +23 / +33**, the sole-winner +33/+43 pair and the −22 sole-loser cap; **each row also asserts TotalBids**, because Dash-alone Under (+23) equals combined Over (+23) | 24–40 | Medium |
| E2.9 | Shared Koz/King placement and RP input | `Placement.kt:212-222`; `RpEngine.kt:200` | E2.1 | T-13 green | 18–32 | High |
| E2.10 | Takeover clock, 7th counter, no 50 s clock, disconnect timing | `InactivityClock.kt:143-147`; takeover resolver | E2.1 | T-16 engine rows green; **the ROOM 7th value is 1 Koz and it fires immediately (A7.3), an open ballot is CANCELLED (A8.2), so the counter, its exactly-once rule and the ballot-cancellation hook are all in scope here** | 22–36 | Medium |
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
| E3.5 | Departure charges, abandonment, 7th takeover | `LeaveMatch.kt:50-56`; settlement | E3.3. ~~O-03, O-03c, O-03d~~ **decided (A7.3, A7.4, A8.3)** | T-15/T-16 charge rows green, **including the ROOM 15-timeout = 1 Koz row (A8.3 / Q37, S5)**. **`DisconnectVote.permanentCharge` (`:69-73`) already returns the A7.4 values and needs a production caller (IB-19); `LeaveReason` still has no voluntary/involuntary discriminator (`LeaveMatch.kt:33-42`); the 15-timeout removal path records no charge today (`TimeoutResolutionDb.kt:326-343`)** | 28–48 | Medium |
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
| E4.1 | Auto-Match queue and table join within the **absolute** ≤1-tier band | `MatchmakingPort.kt:29-33`; `MatchmakingPool.kt:82-91`; new queue service | E3.1 | T-20 join/no-table/tier-distance rows green; **band never widens after 180 s (A8.6)**; directionality stays **O-15** and is not asserted | 32–56 | Medium |
| E4.2 | 2-minute fill timer, 30 s vote, creator bots | New matchmaking service; `botProfileFor` (`TimeoutResolution.kt:417`) | E4.1 | T-20 vote rows green | 24–40 | Medium |
| E4.3 | Takeover counter, 7th removal, 60 s all-gone cancel, ballot cancellation | `InactivityClock.kt`; `TimeoutResolution.kt:245`, `:358`, `:376-380`; `DisconnectVoteState.kt:327`; `DisconnectVoteFlow.kt:219-223`, `:288-311` | E2.10. ~~O-03, O-03b~~ **decided (A7.3, A8.2)** | T-16 green **including the ROOM 1 Koz row and the ballot-cancellation row** (A8.2 / Q36, **S7**): the open ballot goes terminal CANCELLED during VOTE or WAIT, no expiry completion, no stale write, one removal and one charge. The counter does not exist yet and the ballot-time suppression must be lifted deliberately | 28–48 | Medium |
| E4.4 | ROOM lifecycle: four-player start, join/ready/leave, bot-filled seat | `RoomService.kt:55`, `:234`, `:273`; `AppNav.kt:291` | E4.1. ~~O-04~~ **decided (A7.5)** | T-17 room-size and bot-fill rows green: four is a **start** condition and a vacated seat may be bot-filled | 20–36 | Medium |
| E4.5 | In-match disconnect/vote/leave UI flows | New Compose overlays | E7 design (UI shell) | T-16 UI script passes | 24–44 | Medium |

Commands: `native/gradlew -p native :engine:jvmTest :services:test :app:testDebugUnitTest`; emulator
suites for the services paths. Evidence: vote logs, takeover matrix, cancellation trace. Gate: M4.

#### E5 — Statistics and seasons (96–168 h)

Goal: all-mode career statistics and a lazy 90-day season reset that cannot race settlement.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E5.1 | All-mode statistics, placement gate only for Ranked placement, bot-fill attribution, nine Arabic labels | `RankedStats.kt:96-121` (gate `:109`), `:151`, `:157`; `Settlement.kt:340-351`; app ROOM/UNRANKED writer; `values-ar/strings.xml` | E3.3. ~~O-04, O-04b~~ **decided (A7.5, A8.4)** | T-19 stats rows green, including the bot-filled ROOM rows: the departed human is **Koz, never the bot's King**, and that match adds **+1 Games Played** (A8.4 / Q38); ROOM, RANKED and UNRANKED all increment Games Played and outcomes while only RANKED writes RP; the nine stat labels render (A8.5 / Q39, **S3**) | 32–56 | Medium |
| E5.2 | Lazy reset and two-step seasonal reset | `SeasonResetRun.kt:80`; `SeasonResetDb.kt:103` | E5.1 | T-19 season rows green | 28–48 | Medium |
| E5.3 | Reset/settlement race and idempotence | Season id/version; profile merge | E5.2, E3.4 | T-19 reset-race green | 20–36 | Medium |
| E5.4 | Profile data contract for the UI | Profile model; `ProfileScreen` | E5.1 | Nine values + rank available to UI | 16–28 | High |

Commands: `:functions:jsNodeTest`, emulator suites, `:engine:jvmTest`. Evidence: stats table for a
fixture account, reset run report, race test log. Gate: M5.

#### E6 — Authentication and account (44–76 h)

Goal: a guest can play ROOM; a registered user can play Ranked; linking preserves identity.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E6.1 | Firebase project wiring and google-services plugin | `native/app/build.gradle.kts`; `google-services.json` (not committed) | Project access | App builds against the real project on device | 8–14 | Medium |
| E6.2 | Email sign-up and Google sign-in UI | `AuthRepository.kt:19-20`; new screens; OAuth consent/fingerprints | E6.1 | New email account and Google account both sign in | 20–34 | Medium |
| E6.3 | Guest gate for Ranked/Auto-Match | Navigation and queue guard | E6.2 | T-21 guest rows green | 6–10 | High |
| E6.4 | Anonymous → registered linking | `linkWithCredential` path | E6.2 | T-21 linking row green; uid stable | 10–18 | Medium |

Commands: app unit tests; manual device walkthrough recorded as evidence. Gate: M6, G7.

#### E7 — UI design (168–280 h)

Goal: design all 27 surfaces on the fixed **932×430 landscape** frame (D10), against frozen rules
and the real API shape, in English and Arabic. Compliant existing artboards (all checked in at
932×430, including S45) are reused, and missing surfaces and states are designed new.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E7.1 | Landscape design system and 932×430 grid | New artboard library on the fixed frame | M2 | Tokens, type, spacing, RTL rules for 932×430 | 24–40 | Medium |
| E7.2 | Core 12 screens | `docs/design/`; S01–S12 equivalents | E7.1 | Every core screen has a compliant 932×430 artboard (reused, re-cut or new) | 48–80 | Medium |
| E7.3 | Ranked, placement, matchmaking, seasons | S45–S56 surfaces (existing 932×430 artboards reused) | E7.1 | 932×430 screens/states for rank chip, queue, placement, season label | 32–52 | Medium |
| E7.4 | In-match overlays: vote, WAIT, takeover, leave | S63/S72/S74 surfaces | E7.1 | Every overlay has default/loading/error states at 932×430 | 24–40 | Medium |
| E7.5 | Profile, nine statistics, history | S03/S44 surfaces | E7.1 | Nine stats + percentages designed at 932×430 | 20–36 | Medium |
| E7.6 | Arabic copy, RTL, terminology review | Strings and artboards | E7.2–E7.5 | Native-speaker sign-off; no dropped placeholders | 20–32 | Medium |

Files: `docs/design/*.html`, `docs/UI_UX_ROADMAP.md`, `native/app/src/main/res/values/strings.xml`,
`values-ar/strings.xml`. Evidence: signed design checklist per surface; **932×430 landscape**
screenshots. Gate: M7.

Estimate assumption: the 168–280 h range is unchanged from the first draft. It assumes the
existing artboards, S45 included, are reused as-is on the D10 932×430 frame, so no re-cut is
budgeted; the historical “390×844 / re-cut” statement in
`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md:729-733` is stale (that file is historical). The saved hours
are absorbed by filling the surfaces that have no artboard. No portrait artboard is produced.

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
| E9.6 | Security/privacy review | Data flows; rules | E3, E6 | No unapproved data collection; retention matches Q18 | 12–20 | Medium |

Commands: release build, fault drill, restore drill. Evidence: drill logs, runbook, alert
screenshot. Gate: M9, G4, G9.

#### E10 — Store readiness and real-people testing (72–136 h)

Goal: meet Play's release process for what V1 actually ships (account deletion is deferred under Q6,
its risk accepted) and run the mandatory closed test without resetting the 14-day clock.

| Task | Work | Files / symbols | Deps | Acceptance | Hours | Conf. |
|---|---|---|---|---|---|---|
| E10.1 | Listing, screenshots, feature graphic, descriptions | `docs/release/play-listing.md`; store assets | E8 | Listing complete in EN/AR | 12–20 | Medium |
| E10.2 | Data safety declarations | Play Console; privacy page | E6.2 | Declarations match actual data flows | 12–20 | High |
| E10.3 | Recruit and manage ≥12 testers | Owner + release checklist | E10.1 | 12 personal accounts opted in | 12–24 | Low |
| E10.4 | 14-day closed test support | Release candidate | E10.3 | Continuous 14 days; feedback triaged | 20–40 | Medium |
| E10.5 | Production access application | Play Console | E10.4 | Access granted | 8–16 | Low |
| E10.6 | Reviewer credentials and access notes | Console | E6.2 | Working login verified by a second person | 8–16 | High |

Policy facts used here (verified 2026-10-22): closed testing requires **12 testers opted in
continuously for 14 days** on personal accounts created after 2023-11-13, followed by a
production-access application; review usually takes **up to 7 days**; testers must be able to
sign in, so working credentials are required. Account deletion requires an **in-app path**, a
**web-accessible deletion resource** and accurate **Data safety** declarations; the owner has
**deferred it from V1 and accepted the store-policy risk (Q6)**, so no deletion work is in this
plan's scope or hours and shipping without it is a rejection risk, not compliance.

Evidence: Play Console tester list and dates, production-access approval, credential test, the Q6
risk acceptance recorded in the 2026-10-21 decision record. Gate: M10, G8.

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

| Capacity | Low (1,626 h) | High (2,812 h) |
|---|---|---|
| 30 productive h/week | 54 weeks | 94 weeks |
| 40 productive h/week | 41 weeks | 70 weeks |
| 50 productive h/week | 33 weeks | 56 weeks |

External waits are **not** in those numbers:

| Wait | Duration | Can start before RC? |
|---|---|---|
| Tester recruitment (12 personal accounts) | 2–8 weeks; unknown | Yes, start at P7 |
| Closed test | **14 continuous days** | No — starts only with the RC |
| Production access review | Usually ≤7 days; plan 7–14 | No |
| First-release store review | Not guaranteed; plan a buffer | No |
| Firebase project access and configuration | 1–3 days | Yes |

Calendar tail after feature completeness is therefore **at least 3 weeks** and realistically
**6–10 weeks** once recruitment, review and one feedback iteration are included. The 2026-09-21
plan recorded only **4 testers available**; if that is still true, recruiting 8 more is the
single longest external dependency.

### 5.5.5 What is not estimated

| Item | Why not estimated | Where it sits |
|---|---|---|
| Leaderboard | Deferred by owner | §5.7 deferred |
| Voice chat | **In V1 scope (Rooms and Waiting Room, never Ranked)** but no vendor, transport, moderation or hours exist; the provider/protocol/cost decision is **O-12** | §5.1.2, §5.7.2 |
| Monetization (ads/IAP) | **In V1 scope**, Play Billing permitted, but verification is **blocked on an approved runtime (O-06)** and the ad/consent provider is **O-13**; no hours are in this baseline and no cost is asserted zero | §5.9, §5.7.2 |
| Web client / iOS | Out of V1 | §5.1.2 |
| App Check / Play Integrity | Deferred after V1 (Q21b) | §5.7 deferred |
| Account deletion | Deferred by the owner with the store-policy risk accepted (Q6); an indicative 12–20 h is excluded from the total | §5.7.4, O-10 |
| Tournament/social features | Not in scope | §5.1.2 |
| Legal review, company/account setup | Owner/external, not engineering | §5.7 |
| Ongoing live-ops and support | Post-release | §5.8 |

### 5.5.6 Confidence and what would invalidate the estimate

- **Stage 0 failure (E1/E3).** If the engine cannot run in the proposed runtime, E3 is
  re-estimated from scratch, not patched. The 280–504 h range assumes the proposal works.
- **Rule decisions.** **O-01…O-11 were answered on 2026-10-22/23 (A7.1–A7.5, A8.1–A8.8)** —
  the Classic combined row got two numbers, not a new formula, and the last four residues closed
  inside the cases §5.3 already carried. What still invalidates these ranges: a **broader** reading
  of A8.1/A8.7/A8.8 than the tested shapes (each expands E2.3 or E2.5/E2.6 beyond the ranges above),
  the **+85–150 h** S1–S3 delta in §5.10 once the owner approves an estimate revision, and an
  O-11 balance pass that renames constants rather than only values.
- **Design direction.** The canvas is fixed by D10: every screen is 932×430 landscape and no
  portrait screen exists. If the owner changes that rule, E7/E8 rework invalidates the medium
  confidence in those epics.
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

### 5.6.5 Deferred account deletion and retention integrity

Account deletion is **deferred from V1** by owner decision (Q6), with the store-policy risk
accepted, so **no deletion path and no deletion integrity test ship in V1**. When deletion is
implemented it must remove or anonymize the profile, cancel pending outbox entries and invalidate
sessions, so that a stale client or a queued outbox entry cannot resurrect an RP-bearing profile.
Data retained in V1 is what Q18 allows: the final result, the RP ledger and idempotency keys. How
long anonymized match records are retained for integrity and dispute purposes is **not specified**
in the current decisions; it is owner decision O-10 and must be settled before the Data safety
declaration is submitted.

## 5.7 Open decisions and deferred work

### 5.7.1 Answered questions — do not ask again

These are owner-approved or otherwise settled. This plan implements them; it does not reopen
them. Every row carries exactly one of the four brief labels: **Owner-approved**,
**Engineering investigation**, **Owner decision required**, **Deferred beyond V1**. The only rows
still labelled “Owner decision required” are O-06, O-10, O-11 and O-12…O-18 in §5.7.2; **O-01…O-05,
O-07, O-08 and O-09 were all answered between 2026-10-22 and 2026-10-23** and live here. Rows
labelled “Engineering investigation” are all in §5.7.3 (T1–T9). Each question has its own row where
it has its own answer.

| Q | Topic | Decision as recorded | Label | Source |
|---|---|---|---|---|
| Q1 | General Pass | Consumes the round: round 6 all pass → round 7 played ×2. **R-1 / Q51 restates it: the new round is a new round number with a fresh deal, never a replay** | Owner-approved | record §O; A8.7 |
| Q1b | General Pass / Sa'ayda repeats | No cap on passes or Sa'aydas; only the multiplier caps at ×8; a Classic General Pass consumes the round with no multiplier, **then and in every later round (Q51)** | Owner-approved | record §P; A8.7 |
| Q2 | Sa'ayda count | Count rounds 14–18 (+1 per counted round), then check the final round; each counted Sa'ayda adds one round | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q2b | Sa'ayda MINI and interactions | MINI mirrors 6–10; Super Call extensions combine with the count; a tied final round counts as having a winner | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q2c | Extension caps | Caps removed; the final-round winner rule repeats until a winner | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q2d | Koz on ties | Every player tied at the lowest is Koz unless all four tie (then all King); shared Koz is not Second/Third; supersedes RD17's last sentence | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q2e | Vote Kick on a shared Koz | No Vote Kick when the Koz is shared; the target must be a unique Koz | Owner-approved | record §O |
| Q2f | RP for a shared Koz | Players tied at the bottom take the same last placement for RP | Owner-approved | record §P |
| Q3 | Super Call extension | Extends only when the Caller succeeds | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q4 | Classic Dash + Risk | Adds both tables; **the exact combined numbers are closed by Q30 / A7.1** — component addition, not formula addition | Owner-approved | `OPEN_QUESTIONS.md` §D; A6.8; A7.1 |
| Q5 | Sign-in methods | Google sign-in and email sign-up in Android V1 through Firebase Auth; no Play Games | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q6 | Account deletion | Deferred from V1 by owner decision; the store-policy risk is accepted and the policy is not verified for our app; **not** a compliance claim | Owner-approved | `OPEN_QUESTIONS.md` §D + record §E/Q6 |
| Q7 | Bot settings | The Ranked game creator chooses bot difficulty and personality | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q7b | Ranked entry paths | Two paths: create the table yourself, or Auto-Match | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q7c | Auto-Match model | Auto-Match joins a player-created waiting table; bots are the creator's choice | Owner-approved | record §O |
| Q8 | Bot-fill timing | Two minutes from the first player entering; ready = connected and seated | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q9 | RP with bots | Humans get normal RP; opponent strength comes from humans only | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q10 | Takeover counter | Increments only when a bot takes over after a disconnection, not for the 50 s inactivity timeout | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q10b | Disconnect detection | Server detects the disconnection; waits the decision timer, then 10 more seconds; then the bot takes over | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q10c | Takeover sequence | MEDIUM bot plays the decision and the timeout counter increments; after 10 more disconnected seconds the creator's bot takes the seat and the takeover counter increments; 10 s is fixed for all timers | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q11 | 7th takeover | Removed from the current match, 2 Koz, not an account ban; **ROOM = 1 Koz and the removal fires immediately, per Q32 / A7.3** | Owner-approved | `OPEN_QUESTIONS.md` §D; A7.3 |
| Q12 | Architecture approval | Write the ADR after Stage 0 succeeds; the ADR text itself is not yet approved (O-06) | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q13 | Backup at 90% | Backup hosts new matches; running matches stay; results wait for one central RP database | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q14b | Departure charges | ROOM leave = 1 Koz; RANKED/UNRANKED leave = 2 Koz; charged only at finish if the player did not return; supersedes A5.1 where they differ. **“Leave” is narrowed to a voluntary leave by Q14b(1)** | Owner-approved | record §O; A6.12 |
| Q14b(1) | Departure = voluntary only (2026-10-22) | Only a **voluntary** leave is a departure: a non-voluntary disconnect or app close charges nothing; a reconnect inside the takeover window is never a departure; the 7th takeover and the 15-timeout removal are not departures — each keeps its own 2-Koz charge and is not re-charged as a leave. **No charge value changes; no new value is introduced.** | Owner-approved | CANONICAL_RULES A6.12; **residual O-03c closed by Q33 / A7.4** |
| Q14c | UNRANKED statistics | UNRANKED computes no RP but enters all profile statistics; reverses A3.5 and the earlier Q14 line | Owner-approved | record §P |
| Q14d(1) | Vote Kick charge | A passed Vote Kick records **2 Koz** in RANKED and UNRANKED; supersedes A5.1's 1 Koz; ROOM has no Vote Kick | Owner-approved | record §P |
| Q14d(2) | Abandoned match | If a match never finishes because everyone left, every player is counted Koz and has RP deducted; details open (Q27) | Owner-approved | record §P |
| Q15 | ROOM authority | ROOM stays app-managed; the server records the final result only | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q15b | ROOM result recording | A ROOM result is recorded only when all four players' apps report matching results | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q15c | UNRANKED result recording | An UNRANKED result is recorded under the same four-matching-reports condition as ROOM | Owner-approved | record §Q |
| Q16 | ROOM size | No ROOM without a full table of four; supersedes A3.1's 2–3 player start | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q17 | Statistics basis | Counts and percentages over Games Played, aggregated across ROOM, RANKED and UNRANKED | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q18 | Data retention | Keep the final result, RP ledger and idempotency keys; delete action history | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q19 | Normal With | Winning-suit alignment; the With number is the player's last bid in that suit | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q19c | A1 bid² wording | The A1 “legacy tracker only” sentence is corrected in the documents only; no code or scoring change; bid² stays | Owner-approved | record §T |
| Q20 | Outage | Timers freeze and resume; no timeout is charged | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q21 | Auth gate | Ranked requires a registered account (App Check is Q21b) | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q21b | App Check / Play Integrity | Deferred past V1 | Deferred beyond V1 | `OPEN_QUESTIONS.md` §D |
| Q22 | Seasons | Lazy reset, no inactive-player records, 90-day seasons, two-step reset | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q24 | ROOM/UNRANKED authority | Stay app-managed; any server move is a later release with no committed project | Owner-approved | `OPEN_QUESTIONS.md` §D |
| Q28 | Fast-round With (superseded) | Q28 was restated by the later fast-round ruling at `record §U:242-250`, which supersedes it; the plan follows the later ruling (exact number plus the final-trump condition) | Owner-approved | record §T |
| Q29 | Re-declaration order | After each Super Call, every player except the Super Caller re-declares under the new trump, counter-clockwise; players who bid after the Super Caller no longer keep their estimate | Owner-approved | record §T |
| Q26 | Auto-Match join rules | RD29 band (≤1 tier); the joiner sees the table settings first; no table → “no tables right now” + Create Table | Owner-approved | record §O |
| Q27 | Everyone-left outcome | Cancelled as soon as the last human leaves; all players Koz; RANKED RP = the engine's last-place loss; server/quota failure exempt; ROOM/UNRANKED record Koz without RP | Owner-approved | record §Q |
| Q27b | Leave versus disconnect | An explicit leave cancels immediately only if no player remains; a disconnection follows the 10 s rule, then the bot; all humans disconnected for a fixed short period → cancel | Owner-approved | record §Q |
| Q27c | All-disconnected period | Wait one minute, then cancel even if players are still registered at the table | Owner-approved | `OPEN_QUESTIONS.md` §D |

**Q30–Q34 (owner rulings of 2026-10-22) — these close O-01, O-02, O-03, O-03c and O-04.**

| Q | Topic | Decision as recorded | Label | Source |
|---|---|---|---|---|
| Q30 | Classic Normal Dash + Risk (closes O-01) | The seat keeps its **Classic Dash table value** and adds only the **flat Risk value**: miss −2 + (−10) = **−12**; Over-round success +13 + 10 = **+23** (Under-round success +23 + 10 = +33). Adding the two complete Classic role formulas (−14 / +36) counts the same miss twice and is **not** the rule; **−22 is a Normal-mode value**; a Classic Wizz-Risk seat is a different role | Owner-approved | CANONICAL_RULES A7.1; `OPEN_QUESTIONS.md` §D |
| Q31 | Fast equal-highest Super Call (closes O-02) | A higher **number** beats a lower one whatever the suit; at an equal number the **stronger suit** wins the Call by the canonical hierarchy (Sans > ♠ > ♥ > ♦ > ♣, `CANONICAL_RULES.md:27-31`, `:59`, `:68`; `Cards.kt:10-16`): **8♠ beats 8♥**, **9♥ beats 8♠**. **Seat order is never a tie-break.** Fast With needs the exact number **and** the matching suit, so the loser of an equal-number tie is **not** With. Residue: same number **and** same suit → **O-02b, closed by A8.1 / Q35** | Owner-approved | CANONICAL_RULES A7.2, A8.1; `OPEN_QUESTIONS.md` §D |
| Q32 | 7th takeover in ROOM, and its timing (closes O-03) | In `ROOM` the 7th takeover records **1 Koz** (`RANKED`/`UNRANKED` keep 2); it fires **immediately when the 7th qualifying takeover happens, even if a 30 s disconnect vote is open or a 90 s WAIT is running**. Residues: fate of that open ballot → **O-03b, closed by A8.2 / Q36**; the `ROOM` 15-timeout amount → **O-03d, closed by A8.3 / Q37** | Owner-approved | CANONICAL_RULES A7.3, A8.2, A8.3; `OPEN_QUESTIONS.md` §D |
| Q33 | Permanent disconnect replacement (closes O-03c) | Reading **(ii)** is the rule: A6.12's "a disconnect charges nothing" removes only the *leave* penalty; a **permanent replacement keeps its ratified charge — `UNRANKED` 2 Koz, `ROOM` no Koz, and `RANKED` holds no such vote**. `DisconnectVote.permanentCharge` (`DisconnectVote.kt:69-73`) already encodes these values and **has no production caller** — wiring, not a value change | Owner-approved | CANONICAL_RULES A7.4; `OPEN_QUESTIONS.md` §D |
| Q34 | ROOM bots and result attribution (closes O-04 / C18) | A bot **may** fill a vacated `ROOM` seat. A human who leaves and does not complete the match is **counted Koz even when the replacement bot finishes as King**; the humans who complete keep their own normal results and statistics; **a bot's result is never attributed to a human profile**. Residue: whether that match enters the departed human's Games Played → **O-04b, closed by A8.4 / Q38 (it does; +1 Games Played)** | Owner-approved | CANONICAL_RULES A7.5, A8.4; `OPEN_QUESTIONS.md` §D |

**Q35–Q52 (owner rulings of 2026-10-22 and 2026-10-23) — these close O-02b, O-03b, O-03d,
O-04b, O-05, O-07, O-08 and O-09, and record the sprint directive.**

| Q | Topic | Decision as recorded | Label | Source |
|---|---|---|---|---|
| Q35 | Fast exact duplicate (closes O-02b) | The **same number in the same suit is legal**; the **first declarer is Caller** and each **later exact duplicate is With**; symmetric under re-seating. Declaration order decides **only** exact duplicates — a different-suit tie still uses the canonical suit ladder, never seat order | Owner-approved | CANONICAL_RULES A8.1; `2026-10-22-final-decision-sync.md` |
| Q36 | Ballot vs 7th takeover (closes O-03b) | The 7th takeover **cancels an open 30 s ballot immediately**, firing during **VOTE or WAIT**, deterministically, with no expiry verdict, no stale completion and no double charge | Owner-approved | CANONICAL_RULES A8.2; `2026-10-22-final-decision-sync.md` |
| Q37 | ROOM 15-timeout charge (closes O-03d) | The 15-timeout removal records **1 Koz in ROOM**; RANKED/UNRANKED keep **2 Koz**; the 7th takeover keeps its own 1 Koz and one removal is never charged twice | Owner-approved | CANONICAL_RULES A8.3; `2026-10-22-final-decision-sync.md` |
| Q38 | ROOM bot fill and result attribution (closes O-04b) | A departed non-completing ROOM human is **Koz even if the bot wins King**, **Games Played +1**, percentages use the new denominator; **no bot result is ever written to a human profile**; completing humans keep their own outcomes | Owner-approved | CANONICAL_RULES A8.4; `2026-10-22-final-decision-sync.md` |
| Q39 | Arabic rank/stat terminology (closes O-05) | Platinum is **بلاتين**; the nine career statistics get Arabic labels (Games Played عدد المباريات; King 👑; King% 👑%; Second عدد مرات وزير / وزير%; Third عدد مرات كوز صغير / كوز صغير%; Koz عدد مرات الكوز / %الكوز). **Keys and arithmetic unchanged.** `وزير` is the Second-place stat and never the rank | Owner-approved | CANONICAL_RULES A8.5; `2026-10-22-final-decision-sync.md` |
| Q40 | Runtime approval process | Stage 0 first; the runtime is chosen on measured evidence. **Cloudflare Workers + DO + D1 is not approved**, and nothing may assume it. This settles the *process*; O-06's actual approval stays open | Owner-approved (process only) | `2026-10-22-final-decision-sync.md`; ADR 0002 pointer |
| Q41 | Matchmaking pool and band (closes O-07) | **Shared pool**; more than one rank tier apart is **never paired directly**; the band is **absolute** and never widens after the 180 s fill timer. Directionality is **O-15** | Owner-approved | CANONICAL_RULES A8.6; `2026-10-22-final-decision-sync.md` |
| Q42 | Voice scope (closes O-08) | Voice is **in V1 for Rooms and the Waiting Room**, and **not in Ranked**. Provider, transport, moderation, permissions and cost are unresolved → **O-12** | Owner-approved (scope only) | `2026-10-22-final-decision-sync.md` |
| Q43 | Monetization scope and constraints (closes O-09) | **Ads and IAP are in V1**; **Play Billing is allowed**; Spark/no-Blaze and $0 marginal **infrastructure** remain; **no Firebase/Cloud billing link**; store fees are not infrastructure costs. Verification is blocked on O-06, the ad provider on O-13 | Owner-approved (scope only) | `2026-10-22-final-decision-sync.md`; §5.9 |
| Q44 | Account deletion (closes O-10's scope half) | **Full deletion** of the profile; **no anonymized-retention substitute**; still **deferred from V1** by Q6. The retention window of anonymized match rows may not be used to keep a profile alive | Owner-approved | `2026-10-22-final-decision-sync.md` |
| Q45 | RP constants (closes O-11) | Final values: King **+30**, Second **+15**, Third **−8**, Koz **−20**. The "placeholder" wording is removed; the scaling rates stay tunable until the balance pass | Owner-approved | `RpEngine.kt:65-68` KDoc; `2026-10-22-final-decision-sync.md` |
| Q46 | Statistics mode scope (N-1, closes C-1) | Career result statistics include **ROOM, RANKED and UNRANKED**; **RP stays Ranked-only**. The engine's RANKED-only gate is the defect | Owner-approved | CANONICAL_RULES pointers; `2026-10-22-final-decision-sync.md`; S3 |
| Q47 | Normal vs Classic redeal multiplier (N-2) | Normal passes deal again **with** the Double; Classic deals again **without** it. **Superseded and restated by Q51** | Owner-approved, superseded | `2026-10-22-final-decision-sync.md` |
| Q48 | Sa'ayda extension count (N-3) | Each Sa'ayda in fast rounds **14–18 inclusive** adds one round: **14→19, 14+15→20, 14+15+16→21, 13 alone→18**, generalised to **18+N**. Not limited to the examples. MINI keeps **6–10** (Q2b). No cap (Q2c) | Owner-approved | CANONICAL_RULES A8.8; `2026-10-22-final-decision-sync.md`; S2 |
| Q49 | Play Billing (N-5) | Play Billing is acceptable for digital IAP under the stated constraints; purchases require **server-side verification** with an entitlement ledger; costs must be identified and anything undeliverable flagged. **Blocked on O-06; sequencing is O-16** | Owner-approved (design) | `2026-10-22-final-decision-sync.md`; §5.9; S8 |
| Q50 | Diamond Arabic label (N-6) | ♦ Arabic display is **كاروه**; inconsistent labels are standardised. **Extended by Q52 to all five suits**; internal ids and hierarchy untouched | Owner-approved, extended | CANONICAL_RULES A8.5; `2026-10-22-final-decision-sync.md` |
| Q51 | General Pass is a NEW round (R-1) | All four pass → the round is **cancelled**, cards are **dealt fresh**, and a **new round number** starts — not the same round replayed. **Normal:** the new round is scored with the Double (ladder ×2 → ×4 → ×6 → ×8, capped ×8). **Classic:** the new round is scored with **no multiplier**. The pass counts toward match completion | Owner-approved (authoritative over Q47) | CANONICAL_RULES A8.7; `2026-10-23-final-owner-answers.md` |
| Q52 | Five Arabic suit labels (O-14) | Display labels: **SANS سانز, SPADES أسبيد, HEARTS هارت, DIAMONDS كاروه, CLUBS تريفل**. Internal ids, comparison logic and the hierarchy are untouched | Owner-approved | CANONICAL_RULES A8.5; `2026-10-23-final-owner-answers.md` |
| — | Sprint directive (2026-10-23) | Add a **sequenced implementation sprint plan** S0–S8 with dependencies, affected files, acceptance criteria and targeted tests. **Planning a sprint is not delivering it** | Owner-approved | `2026-10-23-final-owner-answers.md`; §5.10 |

**Scope of these answers.** The 2026-10-22 transmission reached O-01, O-02, O-03, O-03c, O-04 and
O-05…O-11; the 2026-10-23 transmission closed the last four residues (O-02b, O-03b, O-03d, O-04b)
and fixed the suit labels (O-14 / Q52). Nothing in O-01…O-11 is now unanswered: O-06 remains a
**pending approval** (its process is settled by Q40), O-10 is **deferred by Q44**, and O-11's values
are **final by Q45**.

Q14 is superseded by Q14b/Q14c and is not implemented. Q23 and Q25 do not exist in the register;
there is no unanswered question hiding under those numbers. §A of `OPEN_QUESTIONS.md` holds exactly
the six items this wave left open — **O-12, O-13, O-15, O-16, O-17 and O-18** — and nothing else.

### 5.7.2 Owner decisions required

Only the rows below are open. Every recommendation here is **recorded, not approved**; none is a
default value in a rule or a test.

| ID | Question | Concrete example | Why it blocks | Blocks |
|---|---|---|---|---|
| O-06 | Approve or reject the proposed runtime after Stage 0 | Cloudflare Workers + DO + D1 is a proposal, and **Q40 records that it is not approved**. If Stage 0 passes, approve a runtime by ADR; if it fails, choose a fallback that still meets $0/no-Blaze and Ranked authority | E3 cannot start implementation without an architecture, and **Play Billing verification (S8) needs a deployable backend** | E3, S8, ADR |
| O-10 | Deletion retention (scope settled by Q44) | Q44 requires **full deletion** of the profile with **no anonymized substitute**, deferred from V1 by Q6. What survives a deletion for integrity (RP ledger, idempotency keys) and for how long still has no ruling | No V1 work while deletion is deferred, but the Data safety form must not describe a deletion behaviour the owner has not chosen | Post-V1 deletion work; store form |
| O-11 | RP scaling rates | **The four base values are final (Q45): King +30, Second +15, Third −8, Koz −20.** The Mini multiplier and the tier-band scaling rates stay tunable pending a balance pass (RD4) | Ranked can ship on the base values; a later balance pass changes rates, not the four values | E2.9 follow-up, G3 |
| O-12 | Voice provider, protocol and cost | Voice is **in V1 for Rooms and the Waiting Room and never in Ranked (Q42)**. No vendor, transport, moderation model, microphone-permission policy or hourly cost exists; Spark cannot host a media relay | A V1-scope feature with no design, no estimate and no provider; it is excluded from the 1,626–2,812 h baseline | E7 design, E8, release |
| O-13 | Ads and consent provider | **Ads are in V1 (Q43)**; Play Billing covers IAP. No ad network, consent-management choice (GDPR/EEA) or placement policy exists | Ad SDK choice affects permissions, Data safety, privacy policy and the "no Firebase/Cloud billing link" constraint | E7, E10, G8 |
| O-15 | Matchmaking band directionality (C-5) | O-07/A8.6 say shared pool and *more than one tier apart is never paired*. `isPoolEligible` (`MatchmakingPool.kt:82-91`) allows the **same tier or exactly one below**, never one above | Whether Gold↔Platinum pairs both ways is **not** settled; the T-20 directionality row cannot assert either. *Recommendation (not approved): symmetric absolute band.* No matchmaking code change until confirmed | E4.1 later, T-20 |
| O-16 | IAP / Stage 0 sequencing | Play Billing verification needs a backend. Spark cannot host it and Cloudflare is not approved (Q40). Do we run Stage 0 next, or keep IAP planned-but-blocked? | Decides whether S8 stays blocked or becomes the next work item. *Recommendation (not approved): keep blocked; run Stage 0 when the owner schedules it.* | S8, E1 scheduling |
| O-17 | Sprint priority | Proposed order in §5.10: S1 → S2/S4 → S3 → S5/S6 → S7 → S8 | Re-ordering changes which behaviours land first but not the dependency edges, which hold regardless. *Recommendation (not approved): exactly this order; S1 first because S2 depends on its round progression.* | §5.10 |
| O-18 | Arabic rule prose | Rules documents stay in English. Does the owner supply Arabic wording for new rule text where the localization gate (R-07) applies? | Affects R-07 sign-off for the rules-facing copy only. *Recommendation (not approved): English rules docs, Arabic app labels only (as supplied).* | R-07, E7.6 |

**Closed 2026-10-22/23 — do not ask again; the IDs stay reserved.** O-01 (Classic Dash + Risk) →
**A7.1 / Q30**; O-02 (fast equal-highest tie) → **A7.2 / Q31**; O-03 (ROOM 7th takeover amount and
timing) → **A7.3 / Q32**; O-03b (ballot fate) → **A8.2 / Q36**; O-03c (permanent disconnect
replacement) → **A7.4 / Q33**; O-03d (ROOM 15-timeout) → **A8.3 / Q37**; O-04 (ROOM bots and
attribution) → **A7.5 / Q34**; O-04b (Games Played) → **A8.4 / Q38**; O-05 (Arabic rank/stat
terminology) → **A8.5 / Q39 + Q52**; O-07 (pool vs band) → **A8.6 / Q41**; O-08 (voice) →
**Q42**; O-09 (monetization) → **Q43**. Each is in §5.7.1 with its decision text.

None of the open rows has a default. Where the plan needed a value to keep writing, it used a
**blocked test placeholder**, not a guess.

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
| Account deletion | Deferred (owner); store-policy risk accepted, policy not verified for our app. **Q44 fixes the shape: full deletion of the profile, no anonymized-retention substitute.** A future implementation needs an in-app path, a web resource and Data safety declarations (indicative 12–20 h, **excluded** from this estimate) | Q6, Q44; brief §G |
| Web client | Out of scope | Android-only V1 |
| iOS | Out of scope | Android-only V1 |
| ROOM/UNRANKED server authority | Later release; no committed project | Q24 |
| Match history and seasonal trophies | Not in V1 | RD23; Q18 keeps only result/ledger/keys |
| Tournaments, friends, spectating | Out of scope | §5.1.2 |

Voice and monetization are **not** in this table: Q42 and Q43 put both **in V1** scope. Neither has
an estimate — voice is blocked on O-12 and purchase verification on O-06, with the ad provider under
O-13 (§5.9).

### 5.7.5 Spec gaps versus implementation bugs

A **spec gap** is a rule the sources do not settle. It cannot be fixed in code; it needs an
owner answer. An **implementation bug** is a rule the sources settle but the code violates. It
can be fixed and tested now.

| Spec gap | Where the sources are silent or contradictory | Owner question |
|---|---|---|
| SG-01 Fast equal-highest Super Call | **RESOLVED 2026-10-22** — A7.2 orders number, then the canonical suit ladder, and removes seat order as a tie-break | ~~O-02~~ closed (Q31) |
| SG-02 7th takeover in ROOM / vote interaction | **RESOLVED 2026-10-23** — A7.3 sets the ROOM amount (1 Koz) and the firing time; A8.2 settles the open ballot: it is cancelled immediately, during VOTE or WAIT | ~~O-03~~ closed (Q32); ~~O-03b~~ closed (Q36) |
| SG-03 Classic Dash + Risk numbers | **RESOLVED 2026-10-22** — A7.1: component addition, miss counted once (−12 / +23 on the owner's Over case) | ~~O-01~~ closed (Q30) |
| SG-04 ROOM disconnect replacement charge | **RESOLVED 2026-10-22** — A7.4: reading (ii); `UNRANKED` 2 Koz, `ROOM` no Koz, `RANKED` holds no vote | ~~O-03c~~ closed (Q33) |
| SG-05 ROOM bots | **RESOLVED 2026-10-22/23** — A7.5: a bot may fill a vacated seat, the departed human is scored Koz regardless, and no bot result reaches a human profile; A8.4 adds the match to the departed human's Games Played | ~~O-04~~ closed (Q34); ~~O-04b~~ closed (Q38) |
| SG-06 RD9 pool vs RD29 band | **RESOLVED 2026-10-22** — A8.6: shared pool, absolute ≤1-tier band, no widening after 180 s | ~~O-07~~ closed (Q41); directionality is **O-15** |
| SG-07 Retention after deletion | Q18 retention vs deletion requirement. **Q44 fixes the deletion shape (full deletion, no anonymized substitute) but the retention window of integrity rows is still unstated** | O-10 |
| SG-08 Voice/monetization in V1 | **RESOLVED 2026-10-22** — both in V1 (Q42, Q43); the *providers* are unresolved, which is a different question | ~~O-08/O-09~~ closed; providers → **O-12 / O-13** |
| SG-09 “Everyone left” vs network loss | Q27b tension between leave and disconnect | Implementation follows Q27c; no owner question left by A6.12/A7.4 |
| SG-10 Arabic rank/stat labels | **RESOLVED 2026-10-22/23** — A8.5: Platinum **بلاتين**, the nine stat labels, and the five suit labels (Q39, Q50, Q52) | ~~O-05~~ closed (Q39/Q52) |
| SG-11 Two fast declarations of the **same number in the same suit** | **RESOLVED 2026-10-23** — A8.1: legal; first declarer Caller, later exact duplicates With; declaration order decides only this case | ~~O-02b~~ closed (Q35) |
| SG-12 Fate of a disconnect ballot whose seat the 7th takeover just removed | **RESOLVED 2026-10-23** — A8.2: terminal CANCELLED immediately, no expiry verdict, no stale write, no double charge | ~~O-03b~~ closed (Q36) |
| SG-13 15-timeout removal charge **in ROOM** | **RESOLVED 2026-10-23** — A8.3: **1 Koz** (RANKED/UNRANKED keep 2) | ~~O-03d~~ closed (Q37) |
| SG-14 Does a bot-filled ROOM match enter the departed human’s **Games Played**? | **RESOLVED 2026-10-23** — A8.4: **yes, +1 Games Played**; the percentage denominator includes it | ~~O-04b~~ closed (Q38) |
| SG-15 Matchmaking band directionality | A8.6 says shared pool and an absolute ≤1-tier band; `isPoolEligible` only ever looks **down**, so whether Gold↔Platinum pairs both ways is unstated (C-5) | **O-15** |
| SG-16 Play Billing sequencing to a runtime | Q43/Q49 permit Play Billing with server-side verification; verification needs a backend, and no runtime is approved (Q40/O-06) | **O-16** |

| Implementation bug | Binding rule | Code evidence | Task |
|---|---|---|---|
| IB-01 Fast Super Call resolves once; later calls do not re-open | A6.10/A6.11 | `fastSuperResolved` (`Bidding.kt:594`, `:609`) | E2.3 |
| IB-02 Only pre-Caller seats are wiped | A6.11/Q29 | `preSuper` wipe (`Bidding.kt:575-585`) | E2.3 |
| IB-03 Every 8+ becomes With | A6.10/Q28 | `superCandidates.drop(1)` (`:716`) | E2.4 |
| IB-04 With floor is suit-blind | A6.9/Q19 | `withFloorFor` (`:273-274`) | E2.2/E2.4 |
| IB-05 General Pass replays the same round instead of dealing into the next round number | **A8.7/Q51** (A6.4's "consumes the round number") | client pass handlers (`QuickMatchViewModel.kt:154-167`); functions re-init the same round (`Settlement.kt:517-524`, `TimeoutResolution.kt:979-984`, whose comment `:913-928` documents the same-round model) | E2.5/S1 |
| IB-06 The multiplier ladder skips ×6 and caps at ×8 with **no game-type input at all** | Current Rules `:208` (item 1), Q1b, **A8.7** (Classic = no multiplier) | `minOf(state.roundMultiplier * 2, 8)` (`Bidding.kt:496`); `Bidding.kt:493-508` has no `GameType` parameter | E2.5/S1 |
| IB-07 Classic General Pass wrongly doubles | A6.4, **A8.7** | same unconditional double (`Bidding.kt:496`) — nothing consults the game type | E2.5/S1 |
| IB-08 Sa'ayda caps and no final-round repeat; **and the extension helper is never called in production** | A6.2/Q2c, **A8.8/Q48** | `computeRoundExtension` (`RoundScore.kt:222-238`) **is wired** at `OnlineMatchViewModel.kt:851`; `isMatchComplete` reads `maxRounds` (`Session.kt:568`) | E2.6/S2 |
| IB-09 Normal Dash + Risk drops Risk | A6.3 | `normalRoleFor` (`:79`) | E2.7 |
| IB-10 Tied lowest becomes Third | Q2d | `placementOutcome` (`Placement.kt:218-222`) | E2.9 |
| IB-11 ROOM departure records no Koz | A6.6, controller ruling, **A8.3** (15-timeout ROOM = 1 Koz) | `decideLeave` (`LeaveMatch.kt:56`); the 15-timeout removal path records no charge at all (`TimeoutResolutionDb.kt:326-343`) | E3.5/S5 |
| IB-12 Statistics are Ranked-only and placement-gated | Q14c/Q17, **A8.4/Q38** | `accumulateRankedStat` returns early unless `RANKED` (`RankedStats.kt:109`); settlement is Ranked-only (`Settlement.kt:340-351`); ROOM/UNRANKED writers do not exist | E5.1/S3 |
| IB-13 Takeover uses a 50 s clock, no counter, and a ballot-held seat cannot increment it | Q10b/Q10c, **A7.3, A8.2** | `InactivityClock.kt:143-147`; `TimeoutResolution.kt:358`, `:376-380`; `TimeoutResolutionDb.kt:212-218`, `:244-250` | E2.10/E4.3/S7 |
| IB-14 Settlement retries live only in memory | Decisions §I | `MAX_TRANSIENT_FAILURES` (`:1312`) | E3.4 |
| IB-15 Season reset is a 10,000-profile bulk sweep | Q22 lazy reset | `MAX_RESET_PROFILES` (`SeasonResetDb.kt:103`) | E5.2 |
| IB-16 Fast equal-highest is settled by **seat order** — a ruled violation as of 2026-10-22, not an open question | **A7.2**: number, then the canonical suit ladder; seat order never breaks a tie | `.thenBy { order.indexOf(it) }` (`Bidding.kt:710`, `:746`); no suit comparison at all in the fast path | E2.3, T-11, S4 |
| IB-17 A fast declaration stores **no suit per seat**, so A7.2’s number-AND-suit test and A8.1’s exact duplicate cannot be evaluated | **A7.2, A8.1** | `data class Bid(val type: BidType, val amount: Int = 0)` (`Bidding.kt:25`); only the running top keeps one (`auctionSuit`, `:80`); fast estimates store `suit = null` (`:692`, `:702`) | E2.3, E2.4, S4 |
| IB-18 No per-seat “departed → Koz” override and no attribution guard, so a bot’s King can land on a human profile | **A7.5, A8.4** | rule lives only as a comment (`RankedStats.kt:144-147`); settlement reads every seat with no override (`SettlementDb.kt:508-527`, `:608-682`); `ROOM`/`UNRANKED` return `NoOp` (`Settlement.kt:347-350`) | E4.4, E5.1, S6 |
| IB-19 The ratified replacement charge is **correct in the engine and applied nowhere** | **A7.4** | `DisconnectVote.permanentCharge` (`:69-73`) and `accumulateDepartureCharge` (`:151`) have only test callers (`DisconnectVoteTest.kt:104-115`, `RankedStatsTest.kt:188-303`) and comment references (`DisconnectVoteDb.kt:181`, `MatchModels.kt:321`) — no production path invokes either | E3.5, T-14/T-15, S5 |
| IB-20 The online match **ignores** General Pass entirely, so the round never advances | **A8.7/Q51** | `OnlineMatchViewModel.kt:724` returns on `GeneralPass` | E2.5/S1 |
| IB-21 Services treat General Pass as an ordinary applied bid and replay the same round | **A8.7/Q51** | `MatchAdapter.kt:168-171`, `:835-840` | E2.5/S1 |
| IB-22 The 15-timeout removal path **records no charge** in any mode, and existing tests pin that behaviour | **A8.3/Q37** (ROOM 1 Koz; the 2-Koz modes are unchanged by A8.3 but unapplied in this path) | `TimeoutResolutionDb.kt:326-343` | E3.5/S5 |
| IB-23 `toKozCharge` and `LeaveMatch.decideLeave` have **no settlement caller**, so no departure result reaches a profile | A6.6, A7.4, **A8.3** | `toKozCharge` — no production caller; `LeaveMatch.decideLeave` exists but no settlement/match path applies its result | E3.5/S5 |
| IB-24 The Platinum rank label is Arabic **وزير** — the same word as the Second-place statistic | Q39, Q52 | `RankLadder.kt:47`; expectation pinned at `RankedResolutionTest.kt:44` — **fixed in Sprint 0** | done (S0) |

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
| G2 — Rule coverage and regression | Are the V1 rules implemented and tested? | Every P0 rule in §5.3 has a passing test; all pre-existing suites pass or have documented equal replacements; **no skipped P0 test at all** — the 2026-10-23 rulings left no blocked rule row | Test report mapped to T-IDs | Eng | A rule “verified by reading”; skipped test counted as pass |
| G3 — RP integrity | Is RP correct and single-sourced? | T-13/T-15/T-19/T-20 vectors pass; one RP source; no double application under retry; **the four base RP values are the Q45 finals (+30/+15/−8/−20)** | Vector report + ledger audit | Eng | Two stores can award RP; client computes RP |
| G4 — Recovery and duplicate settlement | Can the system survive interruption? | T-18 and §5.6 recovery matrix pass; duplicate settlement impossible; quota failure never charges | Fault-injection log | Eng | Any confirmed double RP or lost settled match |
| G5 — No billing and free tier | Does V1 run at $0 marginal **infrastructure**? | **No Firebase/Cloud billing link and no Blaze**; measured usage at 90% of the primary scenario within free limits. **Play Billing is permitted under Q43 and is not a billing-account violation; its verification runtime must still be an approved free tier (O-06)** | Billing console + Stage 0 E5/E6/E10/E12 | Owner + eng | Any Firebase/Cloud billing link; over-limit without a documented plan |
| G6 — Architecture approval | Is the runtime approved? | ADR approved by owner after G1; supersedes ADR 0002 where needed. **No runtime is approved today: Q40 explicitly does not approve Cloudflare Workers + DO + D1** | Signed ADR | Owner | Implementation proceeding on an unapproved proposal |
| G7 — Auth and account | Can the right people get in? | Guest gate, email sign-up, Google sign-in, linking pass on device | Device matrix | Eng | Google or sign-up missing |
| G8 — Store policy | Can the app be published? | 12 testers opted in **continuously for 14 days**; production access granted; reviewer credentials verified by a second person; Data safety declarations live; listing accurate. **Voice (Q42) and ads (Q43) are in V1, so their permissions, consent flow and privacy disclosures are part of the declaration** — both remain blocked on providers (O-12, O-13) | Play Console records + credential test | Owner | Fewer than 12 testers; clock reset; no working credentials; deletion deferral not recorded as owner-accepted; an ad/voice permission shipped without a declaration |
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
| Account deletion | Play requires an in-app path + web resource; **deferred from V1 by owner decision (Q6)** | Risk acceptance recorded in the 2026-10-21 decision record (no V1 walkthrough) |
| Deletion risk | The deferral is a **recorded risk, not compliance**; the owner accepted it and will wait for the store's response (Q6) | Decision record §E/Q6 |
| Data safety | Declarations match actual collection and retention; V1 has no in-app deletion, and the form must state that | Completed console form |
| Target API level | Re-verify the current requirement at submission; the app currently targets SDK 36 | Policy page + build config |

Sources (accessed **2026-10-22**):

- “App testing requirements for new personal developer accounts” —
  <https://support.google.com/googleplay/android-developer/answer/14151465?hl=en>
- “Understanding Google Play’s app account deletion requirements” —
  <https://support.google.com/googleplay/android-developer/answer/13327111?hl=en>

The current requirement, for personal accounts created after 2023-11-13, is **12 testers opted in
continuously for 14 days** before a production-access application. The older “20 testers” figure
must not be used.

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

## 5.9 Monetization and purchase verification

### 5.9.1 Scope and invariants

The owner put **ads and IAP in V1** and allowed **Play Billing** (Q43), with purchase verification
designed server-side (Q49). Five invariants constrain every design decision here:

| Invariant | Consequence |
|---|---|
| **Play Billing is the only IAP path** | No third-party payment SDK, no web checkout, no bypass. Digital goods are sold through Play. |
| **Spark / no Blaze** | Firebase Functions cannot be deployed on the free Spark plan, so Functions cannot host verification. Enabling Blaze is not authorised. |
| **No Firebase or Cloud billing link** | A payment or billing account must never be attached to the Firebase/Cloud project. |
| **$0 marginal infrastructure** | Any runtime chosen for verification must fit a free tier; the owner approves it through O-06. |
| **Store fees are not infrastructure costs** | The Play service fee is a distribution cost. It is not counted as infrastructure and no figure here is asserted as zero. |

### 5.9.2 Verification flow (designed, not implemented)

1. **Purchase on device.** The app launches the Play Billing flow and receives a `purchaseToken`
   plus the product id and order id.
2. **Send to the server, never trust the client.** The client posts the token to the verification
   endpoint together with its Firebase ID token.
3. **Authenticate the caller.** The endpoint verifies the Firebase ID token and derives the uid from
   it — never from the request body.
4. **Server-to-server verification.** The endpoint calls the **Google Play Developer API**
   (`purchases.products.get` / `purchases.subscriptionsv2.get`) using a **least-privilege service
   account** scoped to the app. This is the only place a purchase is considered paid.
5. **Idempotent entitlement write.** The entitlement is written keyed by `purchaseToken` in one
   transaction that also records the order id and product id. A replay of the same token is a no-op.
6. **Acknowledge after commit.** The server acknowledges or consumes the purchase **only after** the
   entitlement transaction commits, so a crash leaves the purchase unacknowledged and retryable
   rather than acknowledged-and-lost.
7. **Refund reconciliation.** Refunds and chargebacks are reconciled from the API (or real-time
   developer notifications), and a refunded purchase revokes the entitlement. The ledger keeps the
   revocation, never deletes the row.

**The UI reads the server entitlement.** No screen grants a feature from a local purchase record, a
cached receipt or an optimistic client state.

### 5.9.3 Hard blocker and cost treatment

**Blocked on O-06.** Steps 4–7 need a server that can hold a service-account secret and make outbound
calls. Functions cannot deploy on Spark; Cloudflare Workers + DO + D1 is **not approved** (Q40).
**No purchase code starts until an approved free-tier runtime exists** — S8 stays BLOCKED (§5.10).

**Client-only verification is rejected.** A check performed in the app can be patched out; it does
not satisfy Q49's "secure verification" requirement and is not an acceptable fallback.

**Costs are stated, not assumed.** No purchase cost, provider fee or ad revenue is claimed zero or
estimated here. Play's service fee is a store cost. Any runtime cost is a Stage 0 measurement (E5,
E6, E10, E12), not an assumption.

**Ads sit behind O-13.** No ad network, consent-management platform or placement policy has been
chosen. The choice changes permissions, the Data safety declaration and the privacy policy, so it
cannot be made inside an implementation sprint.

### 5.9.4 Tests

Only **contract tests with a fake verifier** are in scope before O-06 resolves: token parsing,
idempotent replay, acknowledge-after-commit ordering, refund revocation, rejected-signature handling
and uid-from-token. **No end-to-end purchase test can run locally** — it needs a Play merchant
account, licence testers and a deployed backend. Those remain unavailable, and no sprint may report
a purchase flow as verified without them.

## 5.10 Implementation sprint sequence

These are **planning artefacts, not delivered work**. Sprint 0 is the documentation and localization
work of 2026-10-23; **S1–S8 are sequenced, testable work items whose code has not been written.**
Nothing in this section may be read as a claim that the behaviour exists. The order below is
**provisional pending O-17**; the dependency edges hold regardless of priority.

> **Superseded 2026-10-24 by the PR #152 merge (pointer; the text above is left unchanged).**
> S1, S2 and S4 are no longer unwritten: all three merged to `origin/main` (`88217a3`, PR #152)
> on 2026-10-24, and their Status cells below now carry the per-sprint git evidence. S3 and
> S5–S8 remain unwritten planning artefacts. Two owner-scoped open items — F1 v2 and the
> seat-count asymmetry — are recorded as their own rows at the end of the table.

> **Superseded further 2026-10-25 (pointer; the text above is left verbatim).** The
> `origin/main` remote-tracking ref is now `9fc5e7b`: ten PR merges, #153-#162, landed on top of `88217a3`, and
> `git log --first-parent --no-merges 88217a3..9fc5e7b` is empty (no direct integration commits).
> S2's two named defects are addressed on main — D1 by PR #155 and D2 by PR #159 — and F1 v2 merged
> as PR #157. S5 is only partial (the engine charge table via PR #154; the functions recording
> path is still in flight), S3 is still unmerged, and the seat-count asymmetry is still open. The
> per-sprint cells below carry dated pointers; the full merge list follows the table.

```
S0 (records + labels — done 2026-10-23)
├── S1 General Pass ──┬── S2 Sa'ayda wiring
│                      └── S4 O-02b fast-bid duplicate/tie
└── S3 statistics eligibility ──┬── S5 O-03d ROOM 15-timeout
                                └── S6 O-04b ROOM attribution
S5 ── S7 O-03b takeover + ballot cancellation
S8 Play Billing verification ── BLOCKED on O-06 (no runtime approved)
```
**Citation baseline.** All `firestore.rules` line numbers in this table are as of `88217a3` (2026-10-24); each sits beside the quoted clause and the enclosing rule function that are its durable anchors. The numbers are navigational, not evidence — they drift as soon as that file changes, so read the clause and the function name, not the number.

| Sprint | Goal | Depends on | Status |
|---|---|---|---|
| S0 | Record Q35–Q52; canonical/plan/roadmap; five suit labels; Platinum label | — | **EXECUTED** 2026-10-23 (documentation + labels only; no behavioural code) |
| S1 | General Pass: new round number, fresh deal, Normal ×2 / Classic none, replay parity | S0 | **O-19 Option B IMPLEMENTED 2026-10-24** on `agent/s1b-option-b-impl` — `currentMultiplier` persisted and read by rules, services, app and functions; engine 507 / services 213 / app 208 / functions 545 unit tests green, rules 29 of 29 on the live emulator. A round whose window is not provably all-pass (a declared Dash Call in it) is still refused and is now named to the player as `UNSUPPORTED_GENERAL_PASS_SHAPE` instead of failing silently; the window itself, the scored-archive `cardLog.size() == 52` clause in `firestore.rules` and the S2 extension caps are untouched pending owner rulings. **MERGED 2026-10-24 as PR #152** — `origin/main` (`88217a3`) is the merge of `agent/s1b-option-b-impl` (parents `7a7e65c` + `c266d08`), and `git diff --stat c266d08 origin/main` is empty, so main's tree is byte-identical to `c266d08`, the SHA whose CI was audited green (the counts above). |
| S2 | Wire Sa'ayda extensions into the production match flow, with tests | S1 | **MERGED 2026-10-24 inside PR #152** (`8881fe0`, *Integrate S2: Sa'ayda extension wiring, with the effective ceiling honored end to end*; ancestor of `origin/main` `88217a3` on the branch side, not the main-side parent `7a7e65c`). Carries **TWO KNOWN-LIVE DEFECTS**, named here rather hidden behind the green: **D1** — offline normal callers are scored as Super Calls (`computeRoundExtension` in `RoundScore.kt` gates on round number; QuickMatchFlow always calls `startNormalRound`), adding phantom extension rounds; **in flight on branch `agent/s2-d1-offline-supercall`** (unpushed WIP — not merged, not accepted, not green). **D2** — `firestore.rules` still enforces the count cap `MatchService` deliberately removed (the MatchService comment reads: "the extension COUNT cap (MINI 1, FULL 5) is REMOVED — no maxExtensions enforcement"): `:380` `maxExtensionsOf` (MINI 1 / FULL 5); in `isValidRoundExtension`, `:1441` `appendedRound <= baseRoundsOf` and `:1446` `oldRounds.size() < maxExtensionsOf`. The `:378` comment inside `maxExtensionsOf` calling the FULL cap "unreachable" is false once a post-window 18+N extension exists, so online the 2nd MINI extension and every post-window repeat get PERMISSION_DENIED, and because the extension write is not gated the client advances anyway, desyncing `currentRound` from `maxRounds`. **NOT fixed this round** — D2 must branch AFTER the F1 v2 rules work, never in parallel (both edit the same `firestore.rules` region and the same SHA pin).<br>**Pointer 2026-10-25 (supersedes both defect clauses above; the text is left verbatim):** **D1 — MERGED as PR #155** (`ca09212`, branch `agent/s2-d1-offline-supercall`; commits `6fb420d` *Engine(S2-D1)* + `65b8960` *App(S2-D1)*; 4 files, +220/-6; ancestor of `9fc5e7b`). The engine decision now takes `callerBidAmount` and treats `SUPER_CALL_MIN_BID` (8) as the Super-Call signal when the amount is supplied (null keeps the legacy fast-round reading), and `QuickMatchViewModel` passes the caller's locked estimate. **D2 — MERGED as PR #159** (`df15f0d`, branch `agent/a1-rules-extensions`, commit `a59f244`; 3 files, +106/-52; ancestor of `9fc5e7b`): `maxExtensionsOf` and `oldRounds.size() < maxExtensionsOf` are gone from the merged blob, `appendedRound` is bounded by `firstFastRoundOf` / `maxRounds` / window-or-actual-final, and the pin moved `ed12488…` → `8300267…`. Both PRs added tests; this docs pass ran none, so "merged" is the claim, not a fresh green. |
| S3 | ROOM/RANKED/UNRANKED career statistics; RP Ranked-only; nine stat labels | S0 | PLANNED<br>**Pointer 2026-10-25:** still unmerged. Lane `a7` (`D:\worktrees\a7-s3-stats-eligibility`, branch `agent/a7-s3-stats-eligibility`) is IN FLIGHT at `9fc5e7b` with an engine-only write scope (`native/engine/**`; expected focus `RankedStats.kt` + `RankedStatsTest.kt`). Its HEAD equals `9fc5e7b` with no commits ahead; at the final read it carries uncommitted edits to `RankedStats.kt` and `RankedStatsTest.kt`. No acceptance is claimed. |
| S4 | O-02b fast-bid duplicate declaration, first-declarer Caller, suit tie-break | S1 | **ON MAIN 2026-10-24 inside PR #152** (`origin/main` `88217a3`): `Bid.suit` (`Bidding.kt:32`) and `resolveFastCall` (`Bidding.kt:359`) landed via `1dd7d94` *Checkpoint(S4)*, integrated by `5edfb34` *Integrate S4: fast-bid ties, exact duplicates and fast Super Call archive replay* (both ancestors of `c266d08`, branch-side of PR #152). `MAX_DASH_CALLS = 2` (`Bidding.kt:116`) and the effective-cap conversion (`Bidding.kt:495` — a third declared Dash records PASS) are also on main but predate S4 (`8246428`, 2026-09-19 Phase 3 BiddingEngine port; moved into `commonMain` by the KMP migration `c6c7bff`). |
| S5 | O-03d ROOM 15-timeout = 1 Koz | S3 | PLANNED<br>**Pointer 2026-10-25 (partial, not complete):** the engine charge table for the 15-timeout path is on main via PR #154 (`77ec4e7`; branch commit `2d1abef`; 6 files, +331/-97; ancestor of `9fc5e7b`): `LeaveMatch.decideLeave(FIFTEEN_TIMEOUT)` returns `Koz1` for ROOM and `Koz2` for RANKED/UNRANKED. That PR did not touch `TimeoutResolution*.kt` or its tests (`git diff --name-status 77ec4e7^1 77ec4e7`), so the functions recording path and the rest of S5 stay open. Lane `a8` (`D:\worktrees\a8-s5-timeout-charge`, branch `agent/a8-s5-timeout-charge`) is IN FLIGHT at `9fc5e7b` with a functions-only scope (`native/functions/**`). |
| S6 | O-04b ROOM bot fill: departed human Koz + Games Played; no bot attribution | S3 | PLANNED |
| S7 | O-03b 7th takeover cancels an open ballot; fires during VOTE/WAIT | S5 | PLANNED |
| S8 | Play Billing verification + entitlement ledger | O-06 approval | **BLOCKED** |
| F1 v2 | Mixed declared-Dash + all-pass window is a valid General Pass — owner **RULED Option A 2026-10-24**: N dash answers of which exactly k are `true`, 0 ≤ k ≤ min(2, N−1), then N−k auction passes from distinct non-dashers (2N−k entries total, zero cards), under a STRICT two-Dash maximum (three declared → reject everywhere, never a silent clamp; a third attempt is ineffective and records `declaredDashCall = false`) | — | **IN FLIGHT on branch `agent/f1-dash-pass-general-pass`** (unpushed WIP — not merged, not accepted, not green). As of `88217a3` (2026-10-24) the ruled shape is refused or mis-recorded: `isCanonicalGeneralPassWindow` in `MatchShapes.kt` (requires every dash entry `declaredDashCall == false` and exactly 2N entries), the `isAllPassBiddingLog()` helper in `firestore.rules` (same 2N/all-declining shape) and MatchService's persisted-log write boundary, the `action.toLogEntry` call. The S2 **D2** cap fix must branch AFTER this, never in parallel (same `firestore.rules` region, same SHA pin).<br>**Pointer 2026-10-25 (supersedes the IN-FLIGHT status above; the text is left verbatim): MERGED as PR #157** (`3ee5b67`, branch `agent/f1-dash-pass-general-pass`; six branch commits `24783d7`, `a54bed8`, `dc96b9d`, `c5bb7b5`, `32b4ea5`, `2eae5df`; 13 files, +1172/-109; ancestor of `9fc5e7b`). On main: `firestore.rules`'s `isAllPassBiddingLog` dispatches `isAllDeclineWindow` (2N) or `isMixedPassWindow` (2N−k, k ≤ min(2, N−1), no declared dasher among the passers); services hold `generalPassWindowDefect` and the named `TOO_MANY_DASH_CALLS` reason; `MatchService` normalises an over-cap Dash attempt to a decline at the single persisted-log boundary; functions `TimeoutResolution` normalises the same write and refuses a >2-declared document before replay (`HISTORY_CORRUPT`); the app advances mixed windows and reports the named reason. Tests and the 2026-10-24 decision record were added; this docs pass ran none of them. |
| Seat-count asymmetry | A 2- or 3-seat match can never archive a scored round: `firestore.rules` admits matches with 1–4 players (the seat ladder inside `isValidSeatMap`: `players.size() == 1` / `players.size() == 4`, `:521-527`) and lets `players` change mid-match (`isValidRoomUpdate`'s `affectedKeys().hasOnly` allow-list admits `players`, `:304`), yet in `isValidRoundArchiveCreate` the ORDINARY scored-archive branch still hardcodes `data.cardLog.size() == 52` (`:2041`), while the pass branch (the `isPassArchive` true-branch, which calls `isAllPassBiddingLog(data.biddingLog, data.round, parent.seats)`, `:2038-2040`) proves its window against the real seat count. `Settlement.replayTricks` (`13 * match.order.size`) and `TimeoutResolution.cardTotal` (`= 13 * seats`) already use 13 × seats; only the rules' scored path is hardcoded | — | **OPEN — needs its own story (owner-scoped); not started.** "The rules allow a 3-seat match" and "a 3-seat match can complete" are different claims; the second is false as of `88217a3` (2026-10-24).<br>**Pointer 2026-10-25:** still OPEN. The 52-card scored-archive clause is unchanged in `88217a3..9fc5e7b` (`git diff 88217a3 9fc5e7b -- firestore.rules` contains no `52`/`cardLog` line); at `9fc5e7b` it sits at line 2168 rather than the `:2041` baseline above because #157/#159 changed the file above it. Lane `a9` (`D:\worktrees\a9-rules-seat-count`, branch `agent/a9-rules-seat-count`) is IN FLIGHT at `9fc5e7b` with a rules-only scope: `firestore.rules`, `firestore.rules.sha256`, and rules tests under `tests/`. At the final read its worktree carries uncommitted edits to `firestore.rules` and `firestore.rules.sha256` plus an untracked `tests/rules-archive-seat-count.test.cjs`. No fix is merged. |
| Q2d/Q2f shared-bottom placement | A bottom tie shares the last placement (and therefore Koz + last-place RP); an all-seat tie stays all-King; a shared Koz is never a Vote Kick target | — | **MERGED 2026-10-25 as PR #161** (`394e290`, branch `agent/a4-shared-koz`; commit `53918cc`; 6 files, +118/-19; ancestor of `9fc5e7b`). `placementRank` returns the numeric last rank (`totals.size`) for every tied-bottom seat unless all seats tie (then rank 1, all King); `uniqueKozSeat` still returns null for a shared bottom, so Q2e's unique-Koz Vote Kick target is unchanged. |
| A7.1 Dash + Risk | A Normal Dash seat that is also the Risk player takes the Risk ladder as a component of its own Dash value, in both Normal and Classic, with the miss charged once | — | **MERGED 2026-10-25 as PR #162** (`9fc5e7b`, branch `agent/a3-dash-risk`; commit `2b648bc`; 4 files, +266/-10; this merge is the tip). `ScoredPlayer.isRisk` feeds both score functions; a `REG_DASH` seat that is also Risk adds `riskValue(totalBids)` to its own Dash value in Normal and Classic, with the miss charged once, and never switches to the `RISK`/`WIZZ_RISK` formula. Test cases for the Normal/Classic Risk-on-Dash and non-Risk control paths were added. |

> **Merged since PR #152 (re-derived 2026-10-25; all ten are ancestors of the `origin/main` remote-tracking ref `9fc5e7b`).** `git log --first-parent --oneline 88217a3..9fc5e7b` returns exactly ten merge commits, and `git log --first-parent --no-merges 88217a3..9fc5e7b` returns none — there are **no direct integration commits** in the range. In merge order: **#157** `3ee5b67` *F1 mixed Dash+all-pass General Pass* (13 files, +1172/-109); **#154** `77ec4e7` *Koz charge conformance — ROOM 15-timeout 1 Koz* (6 files, +331/-97); **#155** `ca09212` *S2-D1 offline Super Call* (4 files, +220/-6); **#156** `95808bd` *agent-ops docs: project-memory, Node policy, AGENTS rule, 2026-10-20 prompt package* (9 files, +1814); **#158** `50af795` *2026-10-24 A8.8 placement ruling, O-19/O-20 register rows, S1/S1b split* (3 files, +138); **#153** `6bbe3f1` *§5.10 status reconcile for PR #152 and citation cleanup* (1 file, +12/-3); **#159** `df15f0d` *S2-D2 rules extension-cap removal* (3 files, +106/-52); **#160** `3ce0ffe` *E6b S54 test KDoc correction* (1 file, +8/-5); **#161** `394e290` *Q2d/Q2f shared-bottom Koz* (6 files, +118/-19); **#162** `9fc5e7b` *A7.1 Dash+Risk* (4 files, +266/-10). Each count is `git diff --shortstat <merge>^1 <merge>`. Tests were added by #154/#155/#157/#159/#161/#162, but this docs pass ran none, so no green claim is made.
>
> **Rule citations at `9fc5e7b`.** #157 and #159 changed `firestore.rules`, so the "as of `88217a3`" line numbers above are stale (2,695 lines at `9fc5e7b`, blob `2ac419423f9035f7c995f4c2dec63fe33c20e73c`; the 52-card clause moved `:2041` → line 2168, and the `isValidSeatMap` seat ladder now sits at lines 518-524 (the function starts at 505). Read the named clause and enclosing function, not the number.

**Estimate treatment.** S4–S7 re-sequence work already inside E2/E3/E4/E5 (T-11, T-15, T-16,
T-19); their hours are **not double-counted**. S1–S3 are newly ruled behaviour with no dedicated
line item, so an indicative **+85–150 h** delta is recorded here and **not added** to the
1,626–2,812 h baseline until the owner approves an estimate revision. S8 and voice/ads stay
**unestimated** until O-06/O-12/O-13 resolve.

### S0 — Records and localization (EXECUTED 2026-10-23)

**Documents:** `docs/decisions/2026-10-22-final-decision-sync.md`; `docs/decisions/2026-10-23-final-owner-answers.md`;
`docs/decisions/OPEN_QUESTIONS.md`; `docs/rules/CANONICAL_RULES.md` (Amendment A8.1–A8.8 + pointers);
this plan (§5.1–§5.10); `docs/UI_UX_ROADMAP.md`; pointers in the 2026-10-21 and 2026-10-17 records and
ADR 0002 (status untouched).

**Code:** `RankLadder.kt:47` (Platinum → بلاتين) and `RankedResolutionTest.kt:44`;
`values-ar/strings.xml:111` (اسبيد → أسبيد); `design-ui/shared-i18n.js:16-24` and
`tests/i18n-strings.test.cjs:7-15` (all five suit labels); comment-only KDoc at `RpEngine.kt:65-68`
and `DisconnectVote.kt:19-20`.

**Acceptance:** every Q35–Q52 row exists in a dated record and in the register; no answered row
remains in `OPEN_QUESTIONS.md` §A; A8 exists with pointers and superseded bodies left verbatim; the
five Arabic labels appear identically in app resources, design fixtures and fixture tests; Platinum is
`بلاتين` everywhere and `وزير` survives only as the Second-place stat; no document claims a sprint is
implemented.

### S1 — General Pass round progression (A8.7 / Q51)

**Code areas:** `Bidding.kt:493-508` (emission + game-type policy where `GameType` is known);
`Session.kt:337-352` (`nextRound()` is the production transition), `:568`; `QuickMatchViewModel.kt:154-167`;
`QuickMatchFlow.kt:75`; `BiddingViewModel.kt:73`; `OnlineMatchViewModel.kt:724` (ignored today);
`MatchAdapter.kt:168-171`, `:835-840`; `Settlement.kt:517-524`; `TimeoutResolution.kt:979-984`,
`:913-928`; the match document's `currentRound` persistence (the consumed round must be written once
so a reconnect sees 7).

**Acceptance criteria:**
1. Normal, round 6 all pass → round 7 played with fresh hands, dealer rotated, ×2; a second pass in 7
   → 8 at ×4; the ladder caps at ×8.
2. Classic, round 6 all pass → round 7 with fresh hands and multiplier **1** now and in every later
   round.
3. The pass consumes a round toward completion (`isMatchComplete`, `Session.kt:568`).
4. Client, services and functions replay reproduce the same round number, deal point and multiplier.
5. The online match advances instead of ignoring the pass.
6. No unrelated scoring, bid legality or timeout behaviour changes.

**Tests:** `:engine:jvmTest` — `BiddingTest:359-379`, `GameSessionTest`, `BotVsBotSmokeTest:128-137`;
`:app:testDebugUnitTest` — `QuickMatchViewModelTest:110/138/195-196/229/312`,
`QuickMatchBotIntegrationTest:232`, `PlacementViewModelTest:298`, `OnlineMatchViewModelTest`;
`:services:test` — `MatchAdapterReloadReplayTest`, `GameSessionBridgeTest`; `:functions:jsNodeTest` —
`SettlementTest`, `TimeoutResolutionTest` general-pass replays.

### S2 — Sa'ayda extension wiring (A8.8 / Q48)

**Code areas:** `RoundScore.kt:222-238` (`computeRoundExtension`, wired at `OnlineMatchViewModel.kt:851`);
`Session.kt:67-76`, `:213`, `:480-495`, `:568`; the app's round-completion and `nextRound()` sites;
`OnlineMatchViewModel.kt:544`; functions settlement/replay and the match document's planned-last-round
field; the `GameType` windows (FULL 14–18, MINI 6–10).

**Acceptance criteria:**
1. FULL: each Sa'ayda in rounds **14–18 inclusive** adds one round — 14→19, 14+15→20, 14+15+16→21,
   13 alone→18 — generalised to 18+N.
2. Round 13 or earlier never extends; a Sa'ayda in a non-final round after 18 adds nothing.
3. A Sa'ayda in the actual final round adds one and repeats until a winner (A6.2 rule 2; a shared King
   counts as a winner).
4. MINI uses 6–10 with the same logic.
5. No extension cap (Q2c); the ×8 scoring cap is separate and unchanged.
6. The extended planned last round is persisted and replayed identically, and every client's
   `isMatchComplete` uses it.
7. Interaction with S1: a pass at 13 moves to 14 without extension; a pass at 14 moves to 15 without
   extension — only a **played** Sa'ayda extends.

**Tests:** new `RoundScoreTest` cases 13/14/15/16/19 and multi-occurrence; new `GameSessionTest`
maxRounds/completion cases; `QuickMatchViewModelTest`, `OnlineMatchViewModelTest`;
`:functions:jsNodeTest`; `BotVsBotSmokeTest`; CI instrumented replays.

### S3 — Profile statistics eligibility (A8.4 / Q38, Q46, Q39)

**Code areas:** `RankedStats.kt:96-121` (RANKED-only gate at `:109`); the RD10 placement exclusion
stays; `Settlement.kt:340-351` (RP/stats separation); the app-side ROOM/UNRANKED writer (Q15/Q24/Q15c);
profile aggregation on S03/S44; `values-ar/strings.xml` for the nine stat labels.

**Acceptance criteria:**
1. RANKED completion increments Games Played and the outcome counter.
2. UNRANKED completion increments the same counters and never RP/tier/division.
3. ROOM completion increments the same counters and never RP.
4. Placement profiles still record nothing in all modes (RD10).
5. Percentages use the new denominator (`counter / gamesPlayed`), with no divide-by-zero display.
6. One match updates a profile exactly once across retries/replay.
7. Aggregation sums all three modes (Q17); the nine labels render on the stats surface.

**Tests:** `RankedStatsTest` for all modes + placement; `SettlementTest` RP unchanged; new app
stats-writer tests; replay/idempotency; a resource check for the nine labels.

### S4 — Fast-bid duplicate declaration and tie handling (A8.1 / Q35)

**Code areas:** `Bidding.kt:25`, `:45-50`, `:692`, `:702`, `:705-755` (fast estimates store
`suit = null`; ties by `biddingOrder` at `:710`, `:746`); settlement/timeout replay;
`BiddingTest.kt:222-235`, `:257-284`.

**Acceptance criteria:**
1. The same number **and** suit is legal.
2. The first declarer is Caller; each later exact duplicate is With; symmetric under re-seating.
3. Declaration order decides **only** exact duplicates.
4. Equal numbers in different suits → Sans > Spades > Hearts > Diamonds > Clubs; seat order never
   decides.
5. A higher number beats a lower one whatever the suit.
6. The declared suit is stored per seat (no `suit = null`), so replay is deterministic.
7. With needs the exact number and the matching final suit; the equal-number loser elsewhere is not
   With.
8. Client and server replay produce the same outcome.

**Tests:** `BiddingTest` duplicate/tie cases; `SettlementTest`/`TimeoutResolutionTest` fast replays;
With-set tests; `MatchAdapterReloadReplayTest`.

### S5 — ROOM 15-timeout removal = 1 Koz (A8.3 / Q37)

**Code areas:** `TimeoutResolution.kt:245`, `:170-207`; `TimeoutResolutionDb.kt:326-343` (records no
charge today); `LeaveMatch.decideLeave`; `RankedStats.kt:151-167` (ROOM short-circuit).

**Acceptance criteria:**
1. A 15-timeout removal in ROOM records exactly **1 Koz**.
2. RANKED/UNRANKED 15-timeout keeps **2 Koz**.
3. A 7th-takeover removal in ROOM records its own **1 Koz** (A7.3) and is never double-charged for
   one event.
4. A permanent disconnect replacement in ROOM records **no** Koz; UNRANKED keeps **2 Koz** (A7.4).
5. Games Played is asserted explicitly under S3/A8.4.
6. Replay/retry records the charge once.

**Tests:** `TimeoutResolutionTest`/DB emulator suites; `LeaveMatchTest`; `RankedStatsTest`.

### S6 — ROOM bot fill and result attribution (A8.4 / Q38)

**Code areas:** `SettlementDb.kt:508-527`, `:608-682`; `RankedStats.kt:151-167`; the ROOM
completion/all-apps path (Q15b); the app ROOM bot-takeover flow.

**Acceptance criteria:**
1. A departed, non-completing ROOM human is scored **Koz even if the bot finishes King**.
2. That human's Games Played +1; the percentage denominator includes the match.
3. No bot result is written to a human profile; bots never gain profile rows.
4. Completing humans keep their own outcomes and statistics.
5. The bot's King does not increment the departed human's King counter.
6. Exactly one Koz — the charge is the result, with no second departure charge.
7. ROOM still writes no RP.

**Tests:** Settlement DB emulator suites (bot fill); `RankedStatsTest`; app ROOM completion tests;
CI-only instrumented where an emulator is required.

### S7 — 7th takeover and ballot cancellation (A8.2 / Q36)

**Code areas:** `TimeoutResolution.kt:358`, `:376-380`; `TimeoutResolutionDb.kt:212-218`, `:244-250`,
`:326-343`; `DisconnectVoteState.kt:327` (terminal **CANCELLED**); `DisconnectVoteFlow.kt:219-223`,
`:288-311`, `:459`; `DisconnectVote.kt:19-20`, `:69-73`; `DisconnectVoteDb.kt:110-193`.

**Acceptance criteria:**
1. The 7th qualifying takeover removes the seat immediately during an open ballot and during a WAIT.
2. An open ballot goes terminal **CANCELLED**; no expiry completion, no stale write.
3. Exactly one removal and one charge; retries idempotent.
4. Deterministic; the ballot never re-opens.
5. Charges: ROOM 1 Koz; RANKED/UNRANKED 2 Koz; no second charge for the same event.
6. The vacated seat is bot-filled where the mode allows (A7.5).
7. Replay does not resurrect a cancelled ballot.

**Tests:** `TimeoutResolutionTest` VOTE+WAIT; `DisconnectVoteFlowTest`; DB emulator
concurrency/idempotency; functions `jsNodeTest`.

### S8 — Play Billing verification and entitlements (Q49) — BLOCKED

The design is §5.9: token → server verifies the Firebase ID token → Play Developer API with a
least-privilege service account → idempotent entitlement write keyed by `purchaseToken` →
acknowledge/consume after commit → refund reconciliation. **The hard blocker is O-06:** verification
needs a backend, Functions cannot deploy without Blaze, and Cloudflare Workers + DO + D1 is not
approved. No purchase code starts until an approved free-tier runtime exists. Client-only
verification is rejected. Play service fees are store costs, not infrastructure, and no purchase
cost is claimed zero.

## Appendix A — Evidence index

| File | What it establishes | Used in |
|---|---|---|
| `docs/rules/CANONICAL_RULES.md` | Current Rules summary; amendments A1–**A8**; charge table; RP definitions | §5.1, §5.2, §5.3, §5.7 |
| `docs/decisions/2026-10-22-final-decision-sync.md` | Q35–Q50: exact duplicate, ballot cancellation, ROOM 15-timeout, ROOM attribution, terminology, matchmaking band, General Pass, Sa'ayda, Play Billing | §5.1, §5.2, §5.7, §5.9, §5.10 |
| `docs/decisions/2026-10-23-final-owner-answers.md` | Q51 (General Pass is a new round), Q52 (five suit labels), the sprint directive | §5.3, §5.7, §5.10 |
| `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md` | Owner rulings A–V, Q answers, authority and backup decisions | §5.1, §5.2, §5.6, §5.7 |
| `docs/decisions/OPEN_QUESTIONS.md` | Q/T inventory; answered list **Q30–Q52**; §A holds exactly the six open items (O-12, O-13, O-15, O-16, O-17, O-18) | §5.2.3, §5.7 |
| `docs/decisions/2026-10-16-open4-open5-owner-closure.md` | A5.1 charge table; its Vote Kick 1 Koz (`:57` table row, `:164` result record) is superseded by Q14b/Q14d(1)'s 2 Koz | §5.2.3, §5.7 |
| `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` | Old plan and delivery-order amendment; historical baseline | Header, §5.7 |
| `docs/UI_UX_ROADMAP.md` | 27 surfaces, S-number collision warning, artboard sizes | §5.2, §5.4, §5.5 |
| `docs/adr/0002-functions-runtime.md` | Firebase Functions runtime assumption | §5.2.3 D-08 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/Bidding.kt` | Auction, fast Super Call, General Pass (`:493-508`, no game-type input), With selection, fast `Bid` with no per-seat suit (`:25`) | §5.2, §5.3, §5.10 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/Session.kt` | `nextRound()` (`:337-352`); `isMatchComplete` reads the base `maxRounds` (`:568`) | §5.2, §5.10 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/RoundScore.kt` | Round roles; `computeRoundExtension` (`:222-238`) wired at `OnlineMatchViewModel.kt:851` | §5.2, §5.3, §5.10 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/RankLadder.kt` | Rank names and Arabic labels (`:47` Platinum = بلاتين after S0) | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/Scoring.kt` | Normal and Classic score rows | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/Placement.kt` | Placement and Koz outcomes | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/RpEngine.kt` | RP base/round/final arithmetic and config | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/LeaveMatch.kt` | Vote Kick and departure charge decision | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/DisconnectVote.kt` | `permanentCharge` `:69-73` (A7.4 values, no production caller); "shares no state" `:19-20` | §5.2.2, §5.3.2, §5.7.5 IB-19 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/InactivityClock.kt` | 50 s takeover clock and missing counter | §5.2, §5.3 |
| `native/engine/src/commonMain/kotlin/com/estemshan/engine/RankedStats.kt` | Nine statistics and gates | §5.2, §5.3 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/SettlementDb.kt` | Settlement fold, claim, transaction; every seat read with no departure override (`:508-527`, `:608-682`) | §5.2, §5.3, §5.10 S6 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/Settlement.kt` | ROOM/UNRANKED NoOp (`:340-351`); General Pass re-inits the same round (`:517-524`) | §5.2, §5.10 S1/S3 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/TimeoutResolution.kt` | 15-timeout threshold `:245`; `disconnectPauseRemaining` `:358`; `botHeldSeat` `:376-380` (ballot-held seat cannot increment the counter); same-round re-init `:979-984` (documented `:913-928`) | §5.2.2, §5.3.2, §5.10 S1/S7 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/TimeoutResolutionDb.kt` | `NOT_YET_EXPIRED` gates `:212-218`, `:244-250`: WAIT freezes deadlines; the 15-timeout removal records no charge (`:326-343`) | §5.2.2, §5.10 S5/S7 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/Settlement.kt` | ROOM/UNRANKED NoOp (`:340-351`); General Pass re-inits the same round (`:517-524`) | §5.2, §5.10 S1/S3 |
| `native/app/src/main/java/com/estemshan/game/ui/quickmatch/QuickMatchViewModel.kt` | General Pass re-deals the same round (`:154-167`) | §5.10 S1 |
| `native/app/src/main/java/com/estemshan/game/ui/onlinematch/OnlineMatchViewModel.kt` | General Pass ignored (`:724`) | §5.10 S1 |
| `native/services/src/main/java/com/estemshan/services/MatchAdapter.kt` | General Pass applied as an ordinary bid (`:168-171`, `:835-840`) | §5.10 S1 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetRun.kt` | 90-day season and reset phases | §5.2, §5.3 |
| `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetDb.kt` | 10,000-profile sweep cap | §5.2 |
| `native/functions/index.js` | 11 callable exports and manifest drift | §5.2 |
| `firestore.rules` | Deny-by-default structural validation | §5.2 |
| `native/app/src/main/java/com/estemshan/game/ui/AppNav.kt` | ROOM hardcode at `:291` | §5.2 |
| `native/app/src/main/java/com/estemshan/game/ui/Routes.kt` | 11 routes | §5.2 |
| `native/services/src/main/java/com/estemshan/services/model/MatchModels.kt` | Seat → uid attribution: `seatToUid` `:357`; `botControlledSeats` `:391-392` (union includes `leftUids`); `:317-324` says `permanentCharge` is applied "downstream" | §5.2.2, §5.7.5 IB-18, IB-19 |
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
| E6 Authentication and account | 44 | 76 | 60 |
| E7 UI design | 168 | 280 | 224 |
| E8 Native conversion | 288 | 480 | 384 |
| E9 Hardening and operations | 96 | 168 | 132 |
| E10 Store readiness and testing | 72 | 136 | 104 |
| E11 RC and acceptance | 40 | 72 | 56 |
| **Total** | **1,626** | **2,812** | **2,219** |

Midpoints are arithmetic means, not commitments. The high end is the number to use for capacity
planning while Stage 0 and the open owner items (O-06, O-10, O-11, O-12…O-18) remain unresolved.
O-01…O-11 closed between 2026-10-22 and 2026-10-23 and the total was deliberately left unchanged;
the indicative **+85–150 h** for S1–S3 is recorded in §5.10 and **not** added here — see the note
under §5.5.2.

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
| V2.1 | 2026-10-25 | `9fc5e7b` | §5.10 status true-up for PRs #153–#162: S2 D1/D2 addressed on main, F1 v2 merged, S5 partial, Q2d/Q2f and A7.1 recorded, seat-count still open. Documentation only. |

This document must be revised when any of the following changes:

- an owner decision in §5.7 is answered;
- Stage 0 produces a result that changes the runtime choice;
- a P0 rule or scoring table changes;
- the estimate range moves by more than 15%;
- Google Play policy changes the closed-testing or deletion requirements.

The 2026-09-21 file remains in the repository as history. It is not amended by this plan and
must not be used for scope, sequencing or estimates. `AGENTS.md` points to this file as the
current plan.

