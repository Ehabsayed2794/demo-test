# Estemshan Native v1 — Project Plan & Estimation (Remaining Work to Release)

**Date:** 2026-09-21 · **Prepared by:** Senior PM / Tech Lead / Estimation role
**Baseline:** git `origin/main` @ `a8fc086` (verified, not the stale checked-out branch)
**Operating model:** 1 engineer + AI coding agents (matches actual cadence — Phases 0–10 landed at ~1 phase/day, Sep 17–19)
**Scope:** Remaining work to a Play Store–ready v1. Already-built work is listed as sunk, not re-estimated.

---

## Context — why this plan exists

The project is a 4-player trick-taking card game ("Estemshan"/Estimation) being migrated from a working JavaScript web implementation (`design-ui/`) to native Android Kotlin/Compose (`native/`). The migration plan (`ANDROID_MIGRATION_PLAN.md`) says "~5–8 months, one engineer" but gives **only T-shirt sizes** — no hour breakdown exists anywhere in the repo. The only numeric estimate (`PROJECT_STATUS_AND_MASTER_PLAN.md` §19) is web-era and marked **[STALE — DO NOT QUOTE]** by the owner.

This plan replaces vibes with hours, and corrects four things the existing plan doesn't reflect:

1. **The status snapshot is stale.** The plan says "session ~5%, Phase 3 untouched." As of today, PRs #47 and #48 merged the entire `:services` module (MatchService, MatchAdapter, RoomService, TxSupport, models, session bridge) **and** the `GameSession` store in `:engine` — CI now runs `:services:test`. The honest remaining figure is smaller than the plan implies.
2. **`targetSdk 34` is a hard Play rejection right now.** Google requires new apps to target Android 16 (API 36) as of **August 31, 2026** — that deadline has passed. This forces an AGP/Gradle/Kotlin toolchain cascade that the current plan treats as an afterthought in "Phase 5."
3. **Monetization has nothing to sell yet.** Shop/missions/seasons are deferred, so "monetization in v1" can only honestly mean **ads + one "Remove Ads" IAP** — not an economy.
4. **The AI bots the owner just dropped in (`AI Bots/`) have a hidden coupling.** `botPersonality.ts` imports from `botSimulation.ts` (the "deferred" Monte-Carlo file) and uses it for **HARD, not just EXPERT**. It can't be cleanly deferred without a documented regression.

**Intended outcome:** management can see the true remaining scope, the critical path, and the one decision that actually controls the calendar (the AdMob 14-day closed-testing clock).

---

## Owner decisions of record (answered 2026-09-17)

Preserved verbatim from `ANDROID_MIGRATION_PLAN.md` §7, which this document supersedes as the project's sole plan. The code implementing all four is already merged to `main`.

- **D1 — Native scoring: as-is.** Carry bid² + dash tables + flat sole ±10 (Classic unchanged) into the native game. Same numbers the owner confirmed for legacy.
- **D2 — Risk table: graduated (native only).** Native Risk uses the canonical ladder — diff-from-13 of 1→0, 2–3→10, 4–5→20, 6+→30 — applied ONLY to the Risk player, stacking with Caller/With/sole exactly as §4 of `docs/specs/04-scoring.md`. `src/` keeps its flat ±10 (no change requested).
- **D3 — Billing verification: free tier.** RevenueCat (free tier, no server of our own). No paid plan required. Revisit only if volume outgrows the free tier.
- **D4 — Web fate: small page.** After Play launch, hosting shrinks to a minimal site (store link + privacy policy). Full decommission explicitly rejected.

**Additional decisions made 2026-09-21 for this plan:** AI opponents are in v1 scope (4 tiers + personalities + "Play vs AI" + Choose Level screen), with EXPERT Monte-Carlo deferred post-launch over mobile perf risk. Monetization is in v1. The dealer seam is seat-keyed (`p1..p4`) — a uid→seat translation layer in the session layer is explicitly forbidden (see `GameSessionBridge.kt`'s seam note).

---

## Correction: what's actually already built (sunk, not re-estimated)

Verified on `origin/main`:

| Layer | State | Evidence |
|---|---|---|
| `:engine` — Cards, Deck/Dealer, Bidding, Table, Scoring, RoundScore, **GameSession** | **DONE, green** | 14 kt files; `:engine:test` green incl. P1-3 hazard/safe pins |
| `:services` — MatchService, MatchAdapter, RoomService, TxSupport, models, session/ | **DONE, green** | 15 kt files; CI runs `:services:test`; `MatchAdapterReloadReplayTest` + `GameSessionBridgeTest` |
| `:app` Compose UI — Splash, Login, Bidding, Table, Standings, Profile, Settings | **DONE** | Offline hot-seat QuickMatchFlow end-to-end |
| Online multiplayer wiring | **0%** | `:app` depends on `:engine` **only** — `:services` is not even a dependency yet |
| AI bots | **0%** | no Kotlin bot code |
| Ads / IAP / analytics / audio / art / signing / store assets | **0%** | see §Dependencies & Risks |

The critical path is **not** "write the services" — they exist. It's **wiring `:services` into `:app`** and everything platform-facing after.

---

# 1. Project Breakdown — Phases

The 13 canonical phases, adjusted to this project's reality:

| # | Phase | Notes |
|---|---|---|
| 1 | Requirements & Analysis | **Mostly sunk** — `docs/specs/01–04` froze this in Phase 0. Remaining: AI-bot spec gap (the `AI Bots/` files are untracked, undocumented) |
| 2 | Game Design | Sunk (canonical rules + D1/D2/D3/D4 decisions). Open: bot tier balance |
| 3 | UI/UX | Partial — 7 screens exist; Lobby/Room/Choose-Level are missing |
| 4 | Game Development | Engines sunk. **AI bot port is the big remaining game-dev item** |
| 5 | Android Development / Integration | **The critical path** — `:app`↔`:services` wiring, Room screen, online VM, reconnect |
| 6 | Backend / API | Frozen `firestore.rules` + existing JS services = the backend. No new server (D3). Backend work = **zero new code, only rules-conformant clients** |
| 7 | Ads / Monetization | AdMob banner + UMP + RevenueCat "Remove Ads" — in v1 per owner |
| 8 | Analytics | Crashlytics only in v1; custom events → v1.1 |
| 9 | Testing / QA | JVM tests strong; **instrumented/emulator tier for Kotlin does not exist** |
| 10 | Bug Fixing | Contingency-funded, not separately estimated |
| 11 | Optimization | Folded into audio/perf; MC-sim perf explicitly deferred |
| 12 | Play Store Preparation | Signing, icon, strings (EN+AR), policy, listing, IARC |
| 13 | Final Release | Closed track → staged rollout |

**Structural change vs. the template:** Phase 6 (Backend) collapses to "don't break the frozen rules," and a new **Phase 0b — Toolchain unblock** must precede everything, because `targetSdk 34` is a submission blocker today.

---

# 2. Feature Breakdown

Complexity: **S** = straightforward port/2–6h · **M** = 6–16h · **L** = 16–40h · **XL** = 40h+

### A. AI opponents (new scope from owner)

| Feature | Description | Technical Work | Complexity | Hours |
|---|---|---|---|---|
| Bot hand evaluator | Port `evaluateHand`/`evaluateAllTrumps` (high-card + trump-length + ruffing) | Pure Kotlin in `:engine/bot/`; maps onto existing `Card`/`Suit` | M | 10 |
| 4-tier difficulty | Port `TIER_CONFIG` (mistakeRate, distributionConfidence, bidNoise, canDash, countsCards, modelsOpponents, usesSimulation) | Enum + config table; used to gate every brain path | S | 4 |
| Bot bidding brain | Port `evaluateBotBid` — Dash detection (forced-trick ceiling), forbidden-13 avoidance, call-cap respect, fast-round mandatory trump | Must emit `BiddingIntent` per sub-phase (DASH/AUCTION/CONFIRM/ESTIMATES), not raw numbers; `canSubmit` is the authority | L | 22 |
| Bot card-play brain | Port `botPlay.ts` — `isBoss` card counting, ducking, finesse, tier mistakes | Needs a **`seenCards` accumulator** (`TableState` clears plays on resolve — this state exists nowhere yet); replace `Math.random()` mistake injection with a deterministic key | L | 24 |
| Spoiler strategy | Port `botStrategy.ts` — engage only when own contract busted; target by points at stake (Caller/Super/Dash), direction-aware, tier-gated | Pure; reads `TableState` + `RoundCfg.estimates` | M | 12 |
| Personalities | Port `botPersonality.ts` (BALANCED/AGGRESSIVE/CONSERVATIVE/TRICKSTER, bidBias, dashEagerness, superCallAppetite) | **Coupled to `botSimulation.ts`** — see row below; adaptive difficulty → v1.1 | M | 10 |
| **SimPort seam (HARD regression)** | `fun interface BotSimulation` with heuristic default; `botPersonality` takes it as a ctor param | Without this, HARD over-bids (documented in source: "at full confidence it trusted the optimistic heuristic and over-bid") | M | 12 |
| BotDriver + cadence | Coroutine observing round state; when an AI seat owns `waitingFor`/`turn`, dispatch intent through the **same** `emit()` path a human tap takes; 400–900 ms humanized delay | `RoundStateProvider` interface implemented first by `QuickMatchViewModel`, later by the online VM — **written once** | L | 20 |
| Choose Level screen + lobby entry | Tier/personality pickers, preset tables, "Play vs AI" button; port UI from `AI Bots/App.tsx` (~3754–3945) | Compose; MC indicator hidden until post-launch | M | 14 |
| Bot test suite | Golden bids per tier, forbidden-13, Dash gating, spoiler gating, **bot-vs-bot full 18-round smoke test** | Runs headless in existing JVM CI — the single best regression guard for engine+bots | M | 14 |
| **AI subtotal** | | | | **142** |

### B. Online multiplayer integration

| Feature | Description | Technical Work | Complexity | Hours |
|---|---|---|---|---|
| `:app` → `:services` dependency | Wire the module graph | One gradle line + the 4 ports: `AuthPort`←`AuthRepository`, `GameSessionPort`←`GameSessionBridge`, `MatchAdapterPort`, `MatchListenerFactory`/real `MatchScheduler` (coroutine backoff, not `Immediate`) | M | 12 |
| Real Lobby | Replace `LobbyPlaceholder` — create/join-by-code (native dialogs; spec forbids web's `prompt()`), no public room list | Compose; `RoomService` already implements create/join/leave/ready | M | 16 |
| Room / waiting screen | Ready toggles, host-starts semantics, seat roster | `RoomService.setReady` → `maybeStartMatch` | M | 14 |
| `OnlineMatchViewModel` | Adapt `MatchAdapter` callback → `StateFlow<MatchUiState>`; owns **one** `GameSession`; replays remote snapshots via adapter interpreters | Route existing Bidding/Table/Standings screens with this VM (screens stay stateless) | L | 28 |
| Reconnect / resume | `players/{uid}.currentMatchId` entry → straight into match; cold-start log replay from index 0; `restartPlayState` discipline; fail-open "Reconnecting…" overlay | The P1-3 hazard is already solved by the store; this is the *consumer* of it | L | 20 |
| Activity-derived "opponent away" | Turn/cardLog staleness observed through the match subscription + `lastSeenAt` heartbeat on own doc | **True presence is impossible under frozen rules** — `players/{uid}` is owner-read-only, `list: if false`. Do not promise presence UI | S | 6 |
| **Online subtotal** | | | | **96** |

### C. Platform / release / monetization

| Feature | Description | Technical Work | Complexity | Hours |
|---|---|---|---|---|
| Toolchain unblock (SPIKE) | `compileSdk`/`targetSdk` 34→**36**; AGP 8.7.3→8.11+; Gradle 8.10→8.13+; **commit the gradle wrapper** (none exists in `native/`); decide Kotlin 1.9.25+legacy Compose compiler 1.5.15 vs Kotlin 2.x + `plugin.compose` | **Unknown-compatibility spike** — must be first; 2026-era AndroidX/AdMob libs may force Kotlin 2.x. Also 16KB page-size alignment for ad/billing `.so` | L | 24 |
| Release Firebase fix | `FirebaseModule` hardcodes `projectId "demo-test-ci"` + dummy key for **all build types** — the comment claiming "release talks to the real project" is **wrong**. Inject real `FirebaseOptions` for release | Cheapest real blocker in the plan; easiest to miss | S | 6 |
| Signing config | Release keystore via CI secrets, never committed; `minifyEnabled` decision | | M | 8 |
| `res/` from scratch | Adaptive launcher icon, `strings.xml` (EN + **AR**; RTL already set), proper theme | `native/app/src/main/res/` **does not exist at all**; manifest hardcodes the app name | M | 16 |
| Audio | `SoundManager` (SoundPool) honoring the existing persisted `soundEnabled` toggle; assets: card place, trick win, round score, match win, tap | Zero assets exist today; asset sourcing/licensing is the dependency | M | 14 |
| Compose animations | Card-play transition, trick collection sweep, winner highlight | Must never gate an intent dispatch | M | 12 |
| AdMob + UMP consent | Banner in **Lobby/Standings only — never in-play**; UMP request must complete before any ad load (EU/UK/CA) | Mis-configured consent is a **policy** problem, not just engineering | L | 20 |
| RevenueCat + "Remove Ads" | Free tier ($2,500 MTR free, then 1% — verified); one-time non-consumable | Fits D3 (no server) exactly; add a card before crossing the threshold | M | 14 |
| Crashlytics | Near-zero cost on existing Firebase BOM; the only way a 1-engineer launch sees field crashes | Must-have, not optional | S | 6 |
| Store listing + policy | Privacy policy URL + hosting (the D4 web shrink provides it), IARC questionnaire, data-safety form **consistent with ad declarations**, EN+AR screenshots | Draft exists at `docs/release/play-listing.md`; content-rating must avoid a simulated-gambling flag on "bid" vocabulary | L | 18 |
| **Platform subtotal** | | | | **138** |

### D. QA / verification

| Feature | Description | Technical Work | Complexity | Hours |
|---|---|---|---|---|
| Instrumented 4-client emulator suite | `androidTest` in `:services`: four real `MatchService` instances in one emulator vs the host Firestore emulator — scripted full match: startMatch → bids → 52 cards → archive+advance race → extend → endMatch → rematch | `androidTestImplementation` + `testInstrumentationRunner` are **already configured**; needs a CI emulator job + the test itself. **First time real Kotlin hits real rules.** Assert *convergence*, not single-winner determinism (emulator has zero tx retry) | L | 30 |
| Online manual QA + device matrix | minSdk 26 → modern API-36 device; RTL layout pass for Arabic | | M | 16 |
| **QA subtotal** | | | | **46** |

**Feature total: 422 hours**

---

# 3. Development Estimation by Discipline

| Discipline | Hours | Why this amount |
|---|---|---|
| Game development (AI bot port) | 142 | ~1,600 lines of TS across 5 files, but it is **not** a line-by-line port: `cardRules` is replaced by existing `Table.kt` functions (`legalCards`/`cardValue`/`trickWinner`) — that saves time — while the `seenCards` accumulator and intent-emission layer are genuinely new. Heaviest single item is making bidding emit `BiddingIntent` per sub-phase, because the TS returns a raw number and the engine's `canSubmit` is the real legality gate. |
| Android development / integration | 96 | The services already exist; this is wiring + 2 screens + one ViewModel + reconnect. Priced as integration, not greenfield. The `OnlineMatchViewModel` is the hard part (callback → StateFlow, one GameSession, idempotent replay). |
| Platform / release / monetization | 138 | Front-loaded by the toolchain spike (24h) because API-36 is non-negotiable and the Kotlin-version decision is genuinely uncertain. Audio/art/res are priced minimal — this is a card game, not a 3D title. |
| UI/UX | 42 | *(inside the above)* Choose Level 14 + Lobby 16 + Room 14 — reuse the existing gold-on-dark theme; no new design system. |
| Art / animation | 26 | 14 audio + 12 animation. No 2D/3D artist needed at v1 scope: cards render as glyphs today and that is acceptable for launch. |
| Sound | 14 | Included above (5 SFX + SoundManager). |
| Backend development | **0** | Deliberate. `firestore.rules` is frozen and deployed; the JS services are the reference. D3 = no own server. Any backend work would be a **rules revision** with a new SHA pin — that's a separate release, not this one. |
| QA / testing | 46 | JVM coverage is already excellent (12 suites, incl. the P1-3 pins). The gap is exclusively the instrumented/emulator tier. |
| Project management | 40 | ~10% — owner/PM overhead, store-form correspondence, account activation chasing, release coordination. |
| **Total** | **422 + 40 = 462** | |

---

# 4. Timeline

One engineer + AI agents, ~22 productive engineering days/month (allowing for review, rework, non-coding days).

| Phase | Duration | Dependencies | Deliverable |
|---|---|---|---|
| 0b Toolchain unblock + release Firebase | 1.5 wk | None — **start first** | API-36 build, committed wrapper, signed release reaching the real project |
| 4a AI bot port into `:engine` | 2.5 wk | None — **runs parallel with 0b** | Pure-JVM brains + SimPort seam + bot-vs-bot JVM smoke test |
| 4b BotDriver + Choose Level + "Play vs AI" | 1.5 wk | 4a | Fully playable offline vs AI (shippable on its own) |
| 5a `:services` wiring + Lobby + Room | 1.5 wk | 0b | Real room create/join/ready/start |
| 5b `OnlineMatchViewModel` + reconnect | 2 wk | 5a, 4a (driver seam) | Full online 18-round match + resume |
| 9 Instrumented 4-client emulator suite | 1.5 wk | 5b (design during 5b) | Release gate for online |
| 7 Monetization (AdMob + UMP + RevenueCat) | 1.5 wk | 0b, accounts active | Banner + Remove Ads, test purchases |
| 12 Store prep (res/, strings, policy, listing, Crashlytics) | 1.5 wk | 0b | Store-ready listing, signed AAB |
| 8b Audio + animations (parallel throughout) | 1 wk | — | SFX + polish |
| 13 Closed track → staged rollout | 1 wk | all | Production v1 |

### MVP Timeline — 9.5 weeks (~2.2 months)

The minimum to launch: **AI-only + online, monetization as a rapid v1.0.1 follow-up.**
0b (1.5) + 4a (2.5) + 4b (1.5) + 5a (1.5) + 5b (2) + store basics (1) + stabilization (0.5).
Rationale: neither AI nor online depends on ads. If the AdMob 14-day clock or a UMP edge case slips, this is the line that holds.

### Full Version Timeline — 13 weeks (~3 months)

Everything above + instrumented QA (1.5) + monetization (1.5) + store prep overlap + audio/polish (1) + closed-track rollout (1). Calendar time exceeds the raw hours because of the **AdMob 14-day closed-testing clock** — an immovable external dependency.

---

# 5. Team Requirements

**Operating model: 1 engineer + AI coding agents.** This is not a shortcut — it's the observed cadence (Phases 0–10 at ~1/day). No roles are added to make the project look bigger.

| Role | Required? | Involvement | Hours |
|---|---|---|---|
| **Lead engineer (owner)** | **Yes — full-time** | Architecture, all Kotlin, CI, store submission | 400 |
| AI coding agents (subagents) | Yes — already in the operating model | Parallel worktrees: bot port while the toolchain spike runs | (included, not extra) |
| QA / manual tester | **Part-time, only in the final 3 weeks** | Device matrix, RTL pass, online 4-human playthroughs, ad/IAP verification | 16 (folded into §422) |
| 2D/3D artist, animator | **Not required for v1** | Cards render as glyphs; launcher icon from an icon generator is acceptable at v1 | 0 |
| Sound designer | **Not required** | License 5 SFX from a royalty-free library | 0 |
| Backend developer | **Not required** | Frozen rules + no server (D3) | 0 |
| Project manager | **Not separately** | Owner already performs this | (in the 40 PM hours) |

---

# 6. Dependencies & Risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | **`targetSdk 34` → Play submission rejected.** Deadline was 2026-08-31 (API 36). **Confirmed.** | **Blocks all release** | Toolchain spike is Phase 0b, first. Verify AGP 8.11 + Kotlin 1.9.25 + Compose compiler 1.5.15 still coexist; if any 2026-era lib forces Kotlin 2.x, decide then. Also handle API-35+ edge-to-side and 16KB page alignment. |
| R2 | **`FirebaseModule` points at a dummy project in every build type.** The code comment claiming release works is wrong. | Release builds silently hit a nonexistent Firebase project | 6-hour fix; verified by reading the actual code, not the comment |
| R3 | **No gradle wrapper committed in `native/`** | Any local build fails; CI-only patch via `setup-gradle` | Commit the wrapper in Phase 0b |
| R4 | **AdMob 14-day closed-testing clock** — immovable external dependency | **Controls the calendar**, not the code | Start account activation **today**, in parallel with everything |
| R5 | **`botPersonality` ↔ `botSimulation` coupling.** HARD (not just EXPERT) runs simulation for bid verification; the source says without it HARD "over-bid." | Known AI regression if MC is deferred | `SimPort` seam with a heuristic default; document the regression as a v1 known-issue, or ship a cheap deterministic playout instead |
| R6 | **`Math.random()` in `botPlay.ts:114-116`** breaks determinism | Golden bot tests flicker | Replace with a deterministic key derived from `(seatId, round, trickNo, decisionIndex)`; port `seatJitter` verbatim |
| R7 | **Frozen `firestore.rules`** (SHA-pinned, deployed) | True presence is **impossible** (`players/{uid}` owner-read-only, `list: false`); no friends-list query; room codes readable by any authenticated user | Deliver activity-derived "opponent appears away" instead. Record the room-code enumeration in the threat model — don't call it private. Any presence/economy feature later = a **rules release** with a new SHA pin |
| R8 | **`:services` is an Android library** — can't be consumed by `:engine`, and its Firebase deps mean real Firestore is unreachable from the JVM tier | Online correctness cannot be verified by JVM tests alone | The instrumented 4-client emulator suite (§D) is **unavoidable**, not optional |
| R9 | **Ad placement vs. multiplayer** — an interstitial while three humans wait is churn *and* a turn-timeout source | Retention + UX | Banner only, Lobby/Standings only, never in-play |
| R10 | **Policy-consistency triple** — data-safety form, privacy policy, and ad declarations must agree | Takedown risk | Treat as one deliverable, reviewed as a set before submission |
| R11 | **IARC content-rating flag on "bid" vocabulary** | Could get a simulated-gambling rating (no real money is involved) | Answer the questionnaire carefully; the listing draft already drafts the answers |
| R12 | **Compose BOM 2024.10.01 / Kotlin 1.9.25 is a library ceiling** — locked out of every post-Oct-2024 Compose API and any AndroidX lib requiring Kotlin 2.0 | May collide with current AdMob/UMP/billing SDKs | This is exactly why Phase 0b is a **spike** with a decision deliverable, not a checklist item |
| R13 | **AI Bots files are untracked and undocumented** — no spec, not referenced by any plan | Scope ambiguity | Fold them into the spec set before porting; this plan assumes the owner's v1 scope (4 tiers + personalities + Choose Level, MC deferred) |
| R14 | **Two cross-round state owners** (`QuickMatchViewModel` hand-rolls what `GameSession` now owns; also deals unseeded) | Duplicated logic, non-reproducible deals | `RoundStateProvider` seam means the bot driver is written once; consolidating quick-match onto `GameSession` is a v1.1 cleanup, acknowledged as debt |

---

# 7. Milestones

| Milestone | "Done" means |
|---|---|
| **M1 — Toolchain unblocked** | API-36 build compiles; gradle wrapper committed; signed release build initializes the **real** Firebase project; a clean repo clone builds locally |
| **M2 — AI playable** | A full 18-round match vs 3 bots completes; all 4 tiers observably differ; bot-vs-bot JVM smoke test green in CI; no `Math.random` in any bot path |
| **M3 — Online playable** | 4 humans create/join by code, play a full 18-round match to FinalStandings, and rematch — all through the real `MatchService` |
| **M4 — Reconnect works** | Kill the app mid-match, relaunch: returns to the exact trick via `currentMatchId` + log replay, no double-resolved history (P1-3 pin holds) |
| **M5 — Instrumented gate green** | The 4-client emulator suite asserts convergence on the archive/advance race and endMatch in CI |
| **M6 — Monetization integrated** | UMP consent completes before any ad loads; banner shows in Lobby/Standings only; "Remove Ads" test purchase succeeds via RevenueCat |
| **M7 — Store-ready** | Privacy policy live, IARC rating set, data-safety form submitted and consistent with ad declarations, EN+AR screenshots, signed AAB on the **closed track** |
| **M8 — Production v1** | Staged rollout started; Crashlytics receiving; no P0 crashes for 72h at 5% |

---

# 8. Estimation Scenarios

| Scenario | Hours | Timeline | Conditions |
|---|---|---|---|
| **Conservative** | 462 + 25% = **578** | **~16 weeks** | Toolchain spike forces Kotlin 2.x migration; AdClock slips; bot balancing needs real playtest iteration; instrumented suite reveals a rules-fidelity bug requiring a client rework |
| **Realistic** | 462 + 15% = **531** | **13 weeks** | Spike stays on Kotlin 1.9.25; bots port cleanly with the SimPort seam; online wiring behaves as the JS reference did. **Recommended.** |
| **Aggressive** | 462 − 10% (skip animations, minimal audio, Crashlytics only) = **416** | **10 weeks** | Requires the toolchain spike to land clean in week 1, zero rules-fidelity surprises, and the AdMob account already activated. Only viable if accounts are started **today** |

---

# 9. Contingency

| Contingency bucket | % of 462 | Hours | Why this rate |
|---|---|---|---|
| Unknown requirements | 4% | 18 | AI-bot scope is the only genuinely undocumented area (R13); specs froze everything else |
| Bugs | 4% | 18 | Bot tier balance always needs more iterations than planned |
| Integration problems | 4% | 18 | The `:app`↔`:services` wiring + instrumented tier is untested territory (R8) |
| Rework | 2% | 9 | Toolchain decision may force a Compose-compiler migration |
| Testing | 1% | 5 | Device-matrix surprises, especially RTL Arabic layouts |
| **Total contingency** | **15%** | **68** | **Conservative = 25% (115h) if the toolchain spike goes badly** |

- **Before contingency:** **462 hours**
- **After contingency (realistic):** **530 hours ≈ 13 weeks**
- **After contingency (conservative):** **577 hours ≈ 16 weeks**

---

# 10. Final Executive Summary

**Project scope.** Ship "Estemshan," a 4-player Egyptian trick-taking card game, as a native Android (Kotlin/Compose) app with three modes: offline vs AI bots (4 difficulty tiers + personalities), online matches with friends via room code, and offline hot-seat. Monetized with a single banner ad plus a one-time "Remove Ads" purchase.

**Where we're starting from.** The game engines, scoring, and online *service* layer are **already built and green on `main`** — including the hardest piece, reload-safe replay. Roughly **half the native v1 is done**. What's left is: the AI bots the owner just supplied, wiring the online services into the app, and everything platform-facing.

**MVP scope.** AI play + online multiplayer, with monetization following as a rapid v1.0.1. Neither gameplay mode depends on ads.

**Total estimated hours.** **462** before contingency, **~530 after** (15%).

**Estimated timeline.** **13 weeks** realistic (MVP in 9.5). Conservative 16 weeks if the Android toolchain forces a Kotlin migration.

**Required team.** **One engineer (the owner) + AI coding agents**, plus part-time QA for the final 3 weeks. No artist, animator, sound designer, or backend developer is needed at v1 scope.

**Major risks.** (1) The app currently targets API 34 — Google requires API 36 as of Aug 31, 2026, so the toolchain must be upgraded before anything can ship. (2) The AdMob 14-day closed-testing clock is the one immovable external deadline. (3) The AI bot files are coupled to the "deferred" Monte-Carlo module more tightly than expected. (4) The frozen security rules make true player presence impossible — by design, not by oversight.

**Main assumptions.** The 1-engineer + AI-agent cadence observed over Phases 0–10 continues; the AdMob/Play/RevenueCat accounts are activated immediately; bot balancing is "good enough," not tournament-grade; EN + Arabic only.

**What is NOT included.** EXPERT Monte-Carlo bots and adaptive difficulty (post-launch); matchmaking/ranked play; shop, missions, and seasons (no economy to sell into); rewarded video (nothing to reward yet); true presence/abandonment (blocked by frozen rules); custom analytics events beyond crash reporting; full i18n beyond EN/AR; and any **backend development** — the security rules are frozen and no server is being built (decision D3). Also excluded: re-estimating the ~460 hours of work already merged to `main`.

**One decision for management.** Start the AdMob/Play Console/RevenueCat account activations **today** — they're external clocks, and they — not the code — determine the launch date.

---

## Recommended project structure (for Jira/Trello/Excel)

One epic per phase; stories sized in hours; `S/M/L` from §2. Critical path marked ⏱.

| Epic | Story | Hrs | Deps |
|---|---|---|---|
| **E0b Toolchain** ⏱ | S1 API-36 + AGP 8.11 + wrapper commit | 14 | — |
| | S2 Kotlin-vs-Compose-compiler decision spike | 6 | S1 |
| | S3 Release FirebaseOptions injection | 6 | S1 |
| | S4 Signing config + `:app:assembleRelease` to real project | 8 | S3 |
| **E4a Bots** | S5 Port hand evaluator + TIER_CONFIG | 14 | — |
| | S6 BidBrain → emits `BiddingIntent` per sub-phase | 22 | S5 |
| | S7 seenCards accumulator + PlayBrain (deterministic mistakes) | 26 | S5 |
| | S8 Strategy (spoiler) | 12 | S7 |
| | S9 SimPort seam + Personality | 22 | S6 |
| | S10 Bot golden tests + bot-vs-bot 18-round smoke | 14 | S6,S7,S8,S9 |
| **E4b Bot UI** | S11 BotDriver + `RoundStateProvider` (host: QuickMatchVM) | 20 | S10 |
| | S12 Choose Level screen + presets + "Play vs AI" | 14 | S11 |
| | S13 Seed quick-match deals deterministically | 4 | S11 |
| **E5 Online** ⏱ | S14 `:app`→`:services` + 4 ports + real MatchScheduler | 12 | S4 |
| | S15 Real Lobby (create/join by code) | 16 | S14 |
| | S16 Room/waiting screen + ready + host-start | 14 | S15 |
| | S17 `OnlineMatchViewModel` (callback→StateFlow, one GameSession) | 28 | S16 |
| | S18 Reconnect/resume via `currentMatchId` + log replay | 20 | S17 |
| | S19 Activity-derived "opponent away" + heartbeat | 6 | S17 |
| **E9 QA** | S20 Instrumented 4-client emulator suite + CI job | 30 | S18 |
| | S21 Device matrix + RTL pass + manual 4-human playthrough | 16 | S20 |
| **E7 Monetization** | S22 AdMob banner + UMP consent-before-load | 20 | S4 |
| | S23 RevenueCat + "Remove Ads" non-consumable + test purchase | 14 | S22 |
| **E12 Store** | S24 `res/`: launcher icon, strings EN+AR, theme | 16 | S1 |
| | S25 Crashlytics | 6 | S3 |
| | S26 Privacy policy hosted + data-safety + IARC + listing | 18 | S25 |
| **E8b Polish** | S27 SoundManager + 5 SFX behind existing toggle | 14 | — |
| | S28 Compose animations (card play, trick sweep, winner) | 12 | — |
| **E13 Release** | S29 Closed track + staged rollout + 72h Crashlytics watch | 8 | all |
| | **PM overhead** | 40 | — |
| | **Contingency 15%** | 68 | — |
| | **TOTAL** | **~530** | |

---

### Two technically valid approaches — compared

**Approach A (recommended): Bot brains in `:engine` as pure functions; a `BotDriver` in `:app`.**
- Brains take engine state and return `BiddingIntent`/`PlayCard` — they're pure JVM, testable in the **existing** JVM CI tier, including the bot-vs-bot smoke test.
- The driver observes round state and dispatches through the **same** `emit()` path a human tap takes, so one driver serves both offline and online (via `RoundStateProvider`).
- *Cost:* slightly more design upfront. *Maintenance:* one driver, two consumers.

**Approach B: Bots inside the ViewModel / UI layer, calling the TS logic inline.**
- Faster to first-glance — no new module packages, bots read whatever the screen has.
- *Cost:* bots become untestable from JVM (they'd need Android instrumentation to exercise), the bot-vs-bot smoke test can't run in CI, and the logic gets written **twice** — once for `QuickMatchViewModel`, once for `OnlineMatchViewModel`.
- *Maintenance:* duplicated AI in two ViewModels that must be kept in sync by hand.
- **Verdict: rejected.** The ~1 week Approach A costs upfront is repaid by the single driver and the headless regression guard; Approach B pays that cost twice over and ships with weaker testing.

**A second, smaller choice worth flagging:** whether to ship v1 with `minSdk 26` or raise it to 29. Raising it trims a small amount of legacy-compat testing and loses under 2% of devices. **Recommendation: keep 26** — the audience is broad consumer, and there's no saving here worth the lost reach.

---

### Verification (how the plan gets tested end-to-end)

1. **JVM tier (existing CI, `:engine:test` / `:services:test`)** — every bot brain is pure, so golden bid cases, forbidden-13 avoidance, Dash gating, and spoiler gating run headless. The **bot-vs-bot full 18-round match** is the single best regression guard: it exercises bidding → table → scoring → Sa'ayda → extension end-to-end, on every commit.
2. **Instrumented tier (new, M5)** — four real `MatchService` instances in one emulator against the host Firestore emulator play a scripted full match, asserting **convergence** on the archive+advance race (asserting a single winner is wrong — the emulator has zero transaction retry). This is the first time actual Kotlin hits the actual frozen rules.
3. **JS rules suites (keep green)** — `tests/native-phase3-rules-negative.test.cjs` + `native-phase3-integration.test.cjs` remain the fidelity gate for the rules layer; they must not bit-rot.
4. **Manual** — device matrix minSdk 26 → API 36, an Arabic RTL layout pass, a 4-human online playthrough, and a real UMP consent flow on a EU-region device before submission.
5. **Field** — Crashlytics is the only way a one-engineer launch learns about field crashes; 72h at 5% rollout with no P0 before widening.

---

### Honest reconciliation with the previous estimate

`ANDROID_MIGRATION_PLAN.md` says "**~5–8 months, one engineer**" for the whole migration. This plan says **~3 months for what's left**. Those are consistent, not contradictory — roughly 5 months of the original range is already spent (Phases 0–10, spec through offline UI). The remaining 3 months lands inside the original envelope.

The one place this plan disagrees with the existing documentation is the **status snapshot**: the plan's "~45% — session ~5%, Phase 3 untouched" was true on 2026-09-19 but is stale as of 2026-09-21, because PRs #47 and #48 merged the services module and the GameSession store. Estimating from the stale snapshot would have roughly **doubled** the online-multiplayer line. The assumptions that would have caused that error: trusting a status section instead of reading `origin/main`, and not noticing that `:services` is now a CI-tested module.