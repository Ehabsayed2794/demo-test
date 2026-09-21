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

**Voice chat decisions of record (2026-09-21):**

- **V1 — Push-to-talk ONLY.** Hold/touch-to-speak; **muted by default.** No open mic, no always-on, no audio transmitted while the user is outside a room.
- **V2 — Casual rooms only.** Room-code rooms and matches started from them. **Ranked play ships with no voice, by design** (ranked is itself post-v1; if it ever ships, it stays voiceless).
- **V3 — Max 4 speakers per room** (one mesh, the room's own player set).
- **V4 — Hard $0 constraint.** No paid SDK, no metered minutes, no credit card on file, no usage-based billing that can surprise us. Pure WebRTC (Android) + free public STUN + signaling over existing Firebase. **Agora / Twilio / Daily / LiveKit and any vendor requiring billing activation are forbidden.**
- **V5 — No recording, no transcription, no server-side storage of audio.** Audio flows peer-to-peer; the signaling channel carries only connection metadata.

**Voice scope placement:** Full version, **not MVP** (see §4). Voice is the lowest-priority item in v1 and the first thing an aggressive scenario defers — it is a social nicety for a room of friends, not a launch gate.

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
| Voice chat / WebRTC / any audio-input path | **0%** | manifest declares **only** `INTERNET` — no `RECORD_AUDIO`, no `FOREGROUND_SERVICE`; no WebRTC dep anywhere in `native/` |

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
| 5 | Android Development / Integration | **The critical path** — `:app`↔`:services` wiring, Room screen, online VM, reconnect, **push-to-talk voice** (casual rooms only) |
| 6 | Backend / API | Frozen `firestore.rules` + existing JS services = the backend. No new server (D3). Backend work = **zero new code, only rules-conformant clients**. Voice signaling rides on **Firebase RTDB** — a separate rules file, so `firestore.rules` stays untouched (see §E and R22) |
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

### E. Push-to-talk voice chat (casual rooms only — new scope from owner)

**Hard constraints (owner, 2026-09-21): $0 cost, push-to-talk only, muted by default, casual rooms only (never ranked), max 4 speakers, no recording/transcription/storage.**

Verified against `origin/main` before designing this — these facts drove every row:

- The app manifest declares **only** `INTERNET`. No `RECORD_AUDIO`, no `FOREGROUND_SERVICE`. Nothing audio-input exists.
- No WebRTC, `firebase-database`, or RTC dependency exists anywhere in `native/` (`grep` across all `*.gradle.kts`: zero hits).
- **`firestore.rules` cannot host signaling.** Every client-writable path has a **closed field whitelist**: `rooms/{roomId}` updates may touch only `['players','readyPlayers','status','creator','updatedAt','matchId']` (`isValidRoomUpdate()`), and `players/{uid}` only `['displayName','avatarInitial','lastSeenAt','currentRoomId','currentMatchId']`. A signaling field on either doc is *denied*, not merely unmodeled. A new collection hits the catch-all `allow read, write: if false;` (line 1950).
- `firebase.json` has **no RTDB configured** — but RTDB is a separate product with its **own rules file**, so adopting it touches the frozen `firestore.rules` not at all (see R22).

**Consequence:** signaling goes on **Firebase Realtime Database**, not Firestore. This was not a preference — it is the only $0 path that leaves `firestore.rules` frozen.

| Feature | Description | Technical Work | Complexity | Hours |
|---|---|---|---|---|
| WebRTC voice engine | Audio-only peer connections over the official WebRTC Android API — `AudioRecord` capture + `AudioTrack` playback, or the library's built-in audio device module; `AcousticEchoCanceler` + `NoiseSuppressor` (WebRTC's internal AEC/NS where available, platform AEC as fallback); Opus/ISAC via the library's default audio codec | **New `:webrtc` or `:services` voice package.** Note `org.webrtc:google-webrtc:1.0.32006` is **no longer resolvable from Google's Maven** (Bintray sunset — the recurring "Failed to Resolve" reports through 2026). Use a Maven Central republish such as `com.infobip:google-webrtc` (verified present, latest `1.0.48246t`) or GetStream's `webrtc-android` build — pin an exact version, audit it, and treat the artifact source as R21 | XL | 40 |
| Signaling over Firebase RTDB | Join/leave/offer/answer/ICE-candidate exchange. Paths: `rooms/{roomId}/voice/{uid}/presence` (onDisconnect-cleared), `.../offers/{fromUid}`, `.../answers/{fromUid}`, `.../ice/{fromUid}`. Clients write only their own subtree | Write a **separate `database.rules.json`** (auth-required, self-only writes, presence TTL). **Zero cost**, but it *is* a new rules artifact + an RTDB instance to enable in the console — flagged as a cost-free, time-cost item (R22) | L | 24 |
| Room voice lifecycle | Join a room → join that room's voice mesh; leave room / host-kick / host-starts-match → leave voice; **auto-mute the moment the app goes to background** | Hooks into the existing `RoomService` join/leave surface — voice membership is derived from room membership, never an independent list a player can desync | M | 14 |
| Push-to-talk UI | Hold-to-talk button (press = unmute+transmit, release = mute); **muted is the default and the resting state**; per-seat mic indicator showing who is speaking / who is unreachable; "unreachable" state for the STUN-only failure case | Compose. Voice follows V2 exactly: room-code rooms **and matches started from them** carry it; it must be mode-gated so it can never surface outside that path (ranked doesn't exist in v1 — the gate is built now so a future ranked mode inherits silence) | M | 16 |
| Per-player mute + host mute-all | Any player can mute any other player locally (client-side audio-track disable — no need for a server round-trip); host gets a "mute all" if recommended | Local track `setEnabled(false)` per remote stream — cheap and idempotent | S | 8 |
| `RECORD_AUDIO` permission flow | Rationale UI (EN + **AR**), `shouldShowRequestPermissionRationale` handling, denied → graceful degradation (text/preset chat fallback, voice controls hidden, no crash), limited-access on API-31+; headset/Bluetooth routing (`AudioManager` `setMode`/`startBluetoothSco`) | minSdk 26 → targetSdk 36 span means handling both the legacy and the API-31+ one-time/limited models | M | 14 |
| Audio focus + echo handling | Request transient audio focus while PTT is active, abandon on release; duck the game SFX while someone speaks; AEC on speaker (the echo hazard is speakerphone, not headphones — see R16) | Coordinates with §C Audio (`SoundManager`) — the two must not fight over focus | M | 12 |
| Interruption handling | Incoming GSM call → auto-mute + release focus; app backgrounded → leave voice entirely (V-constraint: no background transmission); phone-state + lifecycle observers | **No foreground service** — voice lives only while the room screen is foregrounded. This is a deliberate scope cap, not an omission | M | 14 |
| Voice QA tier | Four real devices in one room over a real Wi-Fi/LTE mix incl. **one deliberate symmetric-NAT case**; permission-denied path; speaker-vs-headphone echo pass | Extends the §D device matrix; the symmetric-NAT case is the one that predicts production failure rate | M | 16 |
| **Voice subtotal** | | | | **158** |

**Voice total: 158 hours** (in the Full version only — excluded from MVP; see §4).

**Feature total: 422 + 158 (voice) = 580 hours.** Adding the 12h voice store/policy deltas (§3) gives **592**; plus 40 PM = **632** — the number every downstream section uses.

---

# 3. Development Estimation by Discipline

| Discipline | Hours | Why this amount |
|---|---|---|
| Game development (AI bot port) | 142 | ~1,600 lines of TS across 5 files, but it is **not** a line-by-line port: `cardRules` is replaced by existing `Table.kt` functions (`legalCards`/`cardValue`/`trickWinner`) — that saves time — while the `seenCards` accumulator and intent-emission layer are genuinely new. Heaviest single item is making bidding emit `BiddingIntent` per sub-phase, because the TS returns a raw number and the engine's `canSubmit` is the real legality gate. |
| Android development / integration | 96 + 158 = 254 | The services already exist; this is wiring + 2 screens + one ViewModel + reconnect. Priced as integration, not greenfield. The `OnlineMatchViewModel` is the hard part (callback → StateFlow, one GameSession, idempotent replay). The +158 is the voice line (§E): WebRTC engine, RTDB signaling, PTT lifecycle and UI, permission and audio-focus plumbing — all of it Android-side, none of it in the engine. |
| Platform / release / monetization | 138 | Front-loaded by the toolchain spike (24h) because API-36 is non-negotiable and the Kotlin-version decision is genuinely uncertain. Audio/art/res are priced minimal — this is a card game, not a 3D title. Excludes the voice store/policy deltas, which are broken out below. |
| **Voice — store & policy deltas** | **12** | Mic-permission justification copy, data-safety audio declaration (**must** say "no collection, no storage, transmitted peer-to-peer"), one privacy-policy sentence on voice, EN+AR strings. Cheap in hours, expensive in policy risk if skipped (R10/R18/R19). |
| UI/UX | 42 + 16 = 58 | *(inside the above)* Choose Level 14 + Lobby 16 + Room 14 — reuse the existing gold-on-dark theme; no new design system. +16 for the PTT control + per-seat mic indicators (§E). |
| Art / animation | 26 | 14 audio + 12 animation. No 2D/3D artist needed at v1 scope: cards render as glyphs today and that is acceptable for launch. |
| Sound | 14 | Included above (5 SFX + SoundManager). |
| Backend development | **0** | Deliberate. `firestore.rules` is frozen and deployed; the JS services are the reference. D3 = no own server. Any backend work would be a **rules revision** with a new SHA pin — that's a separate release, not this one. |
| QA / testing | 46 + 16 = 62 | JVM coverage is already excellent (12 suites, incl. the P1-3 pins). The gap is exclusively the instrumented/emulator tier. The +16 is the voice QA tier (§E), which is *counted inside the Android row above* and restated here only so QA isn't misread as shortchanged — it is not double-counted. |
| Project management | 40 | ~7% — owner/PM overhead, store-form correspondence, account activation chasing, release coordination, **voice policy correspondence** (mic-justification + data-safety answers). |
| **Total** | **422 + 158 + 12 + 40 = 632** | |

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
| **5c Push-to-talk voice** | **2.5 wk** | **5a (room membership)** | PTT works in a real 4-device room; muted by default; voice structurally absent outside the casual-room path |
| 9 Instrumented 4-client emulator suite | 1.5 wk | 5b (design during 5b) | Release gate for online |
| 9b Voice device matrix (incl. symmetric-NAT) | 0.5 wk | 5c, 9 | Field failure-rate estimate + echo pass |
| 7 Monetization (AdMob + UMP + RevenueCat) | 1.5 wk | 0b, accounts active | Banner + Remove Ads, test purchases |
| 12 Store prep (res/, strings, policy, listing, Crashlytics) | 1.5 wk | 0b | Store-ready listing, signed AAB |
| 8b Audio + animations (parallel throughout) | 1 wk | — | SFX + polish |
| 13 Closed track → staged rollout | 1 wk | all | Production v1 |

### MVP Timeline — 9.5 weeks (~2.2 months)

The minimum to launch: **AI-only + online, no voice, monetization as a rapid v1.0.1 follow-up.**
0b (1.5) + 4a (2.5) + 4b (1.5) + 5a (1.5) + 5b (2) + store basics (1) + stabilization (0.5).
Rationale: neither AI nor online depends on ads — and neither depends on voice. **Voice is deliberately outside the MVP:** it adds 170h, it carries the only two new hardware-policy surfaces in the project (mic permission + Play audio disclosure), and its worst-case failure mode (echo, or a NAT pair that can't connect) is a *support burden*, not a blocker. If the AdMob 14-day clock or a UMP edge case slips, this is the line that holds. Voice (2.5) + voice QA (0.5) then land as v1.0.1/v1.1 alongside TURN (see §E).

### Full Version Timeline — 18 weeks (~4.1 months)

Everything above + voice (2.5) + voice device matrix (0.5) + instrumented QA (1.5) + monetization (1.5) + store prep overlap + audio/polish (1) + closed-track rollout (1). Calendar time exceeds the raw hours because of the **AdMob 14-day closed-testing clock** — an immovable external dependency. Voice is the single largest week-adder after the toolchain spike, and it is sequenced *after* online works, because voice membership is derived from room membership and must never be able to drift from it.

---

# 5. Team Requirements

**Operating model: 1 engineer + AI coding agents.** This is not a shortcut — it's the observed cadence (Phases 0–10 at ~1/day). No roles are added to make the project look bigger.

| Role | Required? | Involvement | Hours |
|---|---|---|---|
| **Lead engineer (owner)** | **Yes — full-time** | Architecture, all Kotlin, CI, store submission | 400 |
| AI coding agents (subagents) | Yes — already in the operating model | Parallel worktrees: bot port while the toolchain spike runs | (included, not extra) |
| QA / manual tester | **Part-time, only in the final 3 weeks** | Device matrix, RTL pass, online 4-human playthroughs, **4-device voice room incl. one symmetric-NAT case**, ad/IAP verification | 16 (folded into §592) |
| 2D/3D artist, animator | **Not required for v1** | Cards render as glyphs; launcher icon from an icon generator is acceptable at v1 | 0 |
| Sound designer | **Not required** | License 5 SFX from a royalty-free library (voice uses the WebRTC library's own AEC/NS, not custom DSP work) | 0 |
| Backend developer | **Not required** | Frozen `firestore.rules` untouched; RTDB signaling rules are a thin declarative artifact, not a service. D3 = no server | 0 |
| **WebRTC / real-time media specialist** | **Not required — but flag the learning curve** | The WebRTC engine row (40h, XL) assumes the engineer becomes productive with `PeerConnectionFactory`/SDP/ICE. If the first mesh refuses to connect, that row is the one most likely to blow its estimate. Mitigation: GetStream's `webrtc-android` reference + a deliberate day-1 "two devices, one audio track" spike before the full mesh | 0 (risk, not headcount) |
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
| **R15** | **Symmetric-NAT pairs cannot connect STUN-only.** Without a TURN relay, two players both behind symmetric NATs (common on carrier-grade IPv4 and some home routers) never complete ICE — the call silently fails | **Expected 5–15% of room-pairs** need a relay; the room is 4 players so a single bad pairing leaves one player unable to speak | **The UX contract, stated up front:** the affected seat's mic indicator shows "unreachable" (not a spinner that never resolves), the room keeps working silently, and preset/text chat remains the fallback. Self-hosted **coturn on a free-tier VM is the v1.1 fix — still $0, effort-only** (see §E). Do NOT promise every room voice works |
| **R16** | **Echo on speakerphone** — the classic WebRTC complaint; platform AEC quality varies wildly at minSdk 26 | Other players hear themselves; one bad device can make a whole room unbearable | WebRTC built-in AEC + platform `AcousticEchoCanceler` as fallback; **the QA matrix must include a speakerphone pass**; default to headphones-aware routing; PTT (not open mic) is itself the strongest mitigation — audio only flows while a button is held |
| **R17** | **Battery / thermal** — an active WebRTC mesh keeps the radio + audio pipeline alive for a full 18-round match | "voice killed my battery" reviews; thermal throttling slows the table UI | Mesh is audio-only and 4-peers max; PTT means the encoder is idle unless someone holds the button; no foreground service, voice dies on background. Measure in the voice QA pass, not in the field |
| **R18** | **Play mic-permission policy** — Google requires a clear justification for audio capture, and apps that *appear* to listen in the background get suspended | **Account-level risk**, not just a bug | Push-to-talk + default-muted + no foreground service + auto-leave-on-background is a *policy posture*, not just a feature set. The data-safety answer must match the code exactly |
| **R19** | **Data-safety form must declare audio** and state it is **not collected, stored, or transcribed** — it is transmitted P2P between room members | Takedown if the form and the behavior disagree | R10's policy-consistency triple now includes audio. One sentence in the privacy policy: "Voice is transmitted directly between players in the same room; it is never recorded, stored, or transcribed." EN + AR |
| **R20** | **Voice + turn timeouts collide** — a player holding-to-talk during their turn is the exact scenario where a card game's turn clock fires | Lost turns, "the game skipped me" support load | PTT must not reset or pause the turn clock; voice is orthogonal to `turn`. Verify in the 4-device matrix, and make the turn-clock resolution the *gameplay* authority regardless of voice state |
| **R21** | **WebRTC artifact supply chain** — `org.webrtc:google-webrtc:1.0.32006` is **no longer resolvable from Google's Maven** (the Bintray hosting sunset; "Failed to Resolve" reports recur through 2026) | Build breaks on a clean checkout, or a transitive consumer drags in a stale/mirrored binary | Use a Maven Central republish (`com.infobip:google-webrtc`, latest verified `1.0.48246t`) or GetStream's `webrtc-android` build; **pin an exact version**, audit the artifact once, and record the choice in an ADR. Do not leave it on a floating `+` |
| **R22** | **Voice signaling needs a Firebase RTDB instance + its own `database.rules.json`** — cost $0, but it is a *new rules artifact and a console change*, i.e. exactly the kind of item the "rules release" constraint was meant to catch | Owner asked to prefer a path that avoids a rules release; **this is why we could not** | Being explicit: the **frozen `firestore.rules` is NOT touched** — no new SHA pin, no Firestore release. But RTDB signaling requires enabling Realtime Database in the console and shipping a small `database.rules.json` (auth-required, self-only writes, presence TTL). Cost **$0** on the Spark plan; effort ~2h of the 24h signaling row. Listed here as a *cost-free-but-time-cost* item so it is never mistaken for a surprise billing event |

---

# 7. Milestones

| Milestone | "Done" means |
|---|---|
| **M1 — Toolchain unblocked** | API-36 build compiles; gradle wrapper committed; signed release build initializes the **real** Firebase project; a clean repo clone builds locally |
| **M2 — AI playable** | A full 18-round match vs 3 bots completes; all 4 tiers observably differ; bot-vs-bot JVM smoke test green in CI; no `Math.random` in any bot path |
| **M3 — Online playable** | 4 humans create/join by code, play a full 18-round match to FinalStandings, and rematch — all through the real `MatchService` |
| **M4 — Reconnect works** | Kill the app mid-match, relaunch: returns to the exact trick via `currentMatchId` + log replay, no double-resolved history (P1-3 pin holds) |
| **M4b — Voice works in a real room** | **Four physical devices** (not emulators) in one room-code room: any player holds-to-talk and the other three hear them; **nobody is transmitting at room join** (muted-by-default verified by packet inspection, not just UI state); backgrounding the app leaves voice and nothing keeps transmitting; a player who denies the mic still plays and still sees others' indicators |
| **M4c — Voice failure modes are honest** | At least one **deliberate symmetric-NAT pairing** in the matrix: the affected seat shows "unreachable", the room continues, and the round completes. Also: voice is provably **gated to the casual-room path only** — a grep for any WebRTC init, `RECORD_AUDIO` prompt, or PTT button outside the room-code/room screen returns **zero**. (Ranked doesn't exist in v1, so this is verified as an *absence*, not a toggle: the gate is structural, and it must stay that way if ranked ever ships.) |
| **M5 — Instrumented gate green** | The 4-client emulator suite asserts convergence on the archive/advance race and endMatch in CI |
| **M6 — Monetization integrated** | UMP consent completes before any ad loads; banner shows in Lobby/Standings only; "Remove Ads" test purchase succeeds via RevenueCat |
| **M7 — Store-ready** | Privacy policy live, IARC rating set, data-safety form submitted and consistent with ad declarations, EN+AR screenshots, signed AAB on the **closed track** |
| **M8 — Production v1** | Staged rollout started; Crashlytics receiving; no P0 crashes for 72h at 5% |

---

# 8. Estimation Scenarios

| Scenario | Hours | Timeline | Conditions |
|---|---|---|---|
| **Conservative** | 632 + 25% = **790** | **~21 weeks** | Toolchain spike forces Kotlin 2.x migration; AdClock slips; bot balancing needs real playtest iteration; instrumented suite reveals a rules-fidelity bug requiring a client rework; **voice exceeds its 40h XL row** (ICE debugging on real devices is the classic 2x line) |
| **Realistic** | 632 + 15% = **727** | **18 weeks** | Spike stays on Kotlin 1.9.25; bots port cleanly with the SimPort seam; online wiring behaves as the JS reference did; voice mesh connects on the first 4-device attempt using a Maven-Central WebRTC republish. **Recommended.** |
| **Aggressive** | 632 − 10% (skip animations, minimal audio, Crashlytics only, **defer voice to v1.1**) = **416** | **10 weeks** | Requires the toolchain spike to land clean in week 1, zero rules-fidelity surprises, and the AdMob account already activated. Voice is the cleanest single thing to cut: 170h (158 voice + 12 policy) with no gameplay dependency — cutting it maps this scenario onto the pre-voice 462h baseline exactly. Only viable if accounts are started **today** |

> **Voice is the swing item.** It is the only scope block in this plan that is simultaneously large (170h), non-blocking for launch, and carrying two policy surfaces. The spread between Realistic and Aggressive is almost entirely "voice or not."

---

# 9. Contingency

| Contingency bucket | % of 632 | Hours | Why this rate |
|---|---|---|---|
| Unknown requirements | 4% | 25 | AI-bot scope is the only genuinely undocumented area (R13); specs froze everything else. Voice requirements are pinned by V1–V5 above, which is why voice itself does not add to this bucket |
| Bugs | 4% | 25 | Bot tier balance always needs more iterations than planned |
| Integration problems | 4% | 25 | The `:app`↔`:services` wiring + instrumented tier is untested territory (R8), **plus a first WebRTC integration** (R15/R16/R21) |
| Rework | 2% | 13 | Toolchain decision may force a Compose-compiler migration; the WebRTC artifact choice may need swapping (R21) |
| Testing | 1% | 6 | Device-matrix surprises, especially RTL Arabic layouts and the speakerphone echo pass |
| **Total contingency** | **15%** | **95** | **Conservative = 25% (158h) if the toolchain spike goes badly or voice ICE debugging runs long** |

- **Before contingency:** **632 hours**
- **After contingency (realistic):** **727 hours ≈ 18 weeks**
- **After contingency (conservative):** **790 hours ≈ 21 weeks**
- **If voice is deferred to v1.1:** **632 − 170 = 462 hours ≈ 13 weeks** — the pre-voice baseline exactly

---

# 10. Final Executive Summary

**Project scope.** Ship "Estemshan," a 4-player Egyptian trick-taking card game, as a native Android (Kotlin/Compose) app with three modes: offline vs AI bots (4 difficulty tiers + personalities), online matches with friends via room code, and offline hot-seat — with **push-to-talk voice chat in casual rooms** (muted by default, max 4 speakers, never in ranked). Monetized with a single banner ad plus a one-time "Remove Ads" purchase.

**Where we're starting from.** The game engines, scoring, and online *service* layer are **already built and green on `main`** — including the hardest piece, reload-safe replay. Roughly **half the native v1 is done**. What's left is: the AI bots the owner just supplied, wiring the online services into the app, voice chat, and everything platform-facing.

**MVP scope.** AI play + online multiplayer, with monetization following as a rapid v1.0.1. Neither gameplay mode depends on ads — and neither depends on voice. **Voice is Full-version scope, deliberately outside the MVP.**

**Total estimated hours.** **632** before contingency (422 base + 158 voice + 12 voice-policy deltas + 40 PM), **~727 after** (15%).

**Estimated timeline.** **18 weeks** realistic (MVP in 9.5). Conservative 21 weeks if the Android toolchain forces a Kotlin migration or voice ICE debugging runs long. **Deferring voice to v1.1 restores the 13-week / 462-hour baseline exactly.**

**Required team.** **One engineer (the owner) + AI coding agents**, plus part-time QA for the final 3 weeks (including a 4-physical-device voice room). No artist, animator, sound designer, or backend developer is needed at v1 scope.

**Major risks.** (1) The app currently targets API 34 — Google requires API 36 as of Aug 31, 2026, so the toolchain must be upgraded before anything can ship. (2) The AdMob 14-day closed-testing clock is the one immovable external deadline. (3) The AI bot files are coupled to the "deferred" Monte-Carlo module more tightly than expected. (4) The frozen security rules make true player presence impossible — by design, not by oversight. (5) **Voice cannot reach every player STUN-only: 5–15% of room-pairs sit behind symmetric NATs and will show "unreachable" — TURN is a v1.1, $0-effort-only fix, not a launch blocker, and the UX must say so rather than spin.** (6) The official WebRTC artifact is no longer on Google's Maven (Bintray sunset) — pin a Maven Central republish deliberately.

**Main assumptions.** The 1-engineer + AI-agent cadence observed over Phases 0–10 continues; the AdMob/Play/RevenueCat accounts are activated immediately; bot balancing is "good enough," not tournament-grade; EN + Arabic only; voice is acceptable to players as PTT-only with no open mic.

**What is NOT included.** EXPERT Monte-Carlo bots and adaptive difficulty (post-launch); matchmaking/ranked play (**ranked will never carry voice**); shop, missions, and seasons (no economy to sell into); rewarded video (nothing to reward yet); true presence/abandonment (blocked by frozen rules); custom analytics events beyond crash reporting; full i18n beyond EN/AR; any **backend development** — `firestore.rules` stays frozen and no server is being built (D3); **no TURN server in v1** (STUN-only, symmetric-NAT limitation accepted and disclosed, coturn on a free VM reserved for v1.1); **no recording, transcription, or server-side audio storage of any kind**; no open-mic / always-on voice; no foreground-service background voice. Also excluded: re-estimating the ~460 hours of work already merged to `main`.

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
| **E5c Voice** | S30 WebRTC engine: dep pin + `PeerConnectionFactory` + AEC/NS + 2-device one-audio-track spike | 16 | S14 |
| | S31 4-peer audio mesh + PTT capture gating (mute by default) | 24 | S30 |
| | S32 RTDB signaling: `database.rules.json` + presence/offer/answer/ICE + onDisconnect cleanup | 24 | S30, S15 |
| | S33 Room voice lifecycle: join⇄voice, leave/kick/host-start auto-leave, **background auto-mute** | 14 | S32, S16 |
| | S34 PTT UI: hold-to-talk button, per-seat mic indicator, "unreachable" state, casual-path mode-gate | 16 | S31, S33 |
| | S35 Per-player local mute + host mute-all | 8 | S34 |
| | S36 `RECORD_AUDIO` flow: EN+AR rationale, denied/limited, headset/Bluetooth routing | 14 | S30 |
| | S37 Audio focus + echo handling (duck game SFX, speakerphone AEC) | 12 | S31, S27 |
| | S38 Interruptions: GSM call auto-mute, background leave, no foreground service | 14 | S33 |
| | S39 Voice store/policy: mic justification + data-safety audio (no collection/storage) + privacy sentence EN+AR | 12 | S36, S26 |
| | S40 Voice QA: 4 real devices incl. 1 symmetric-NAT + permission-denied path + echo pass | 16 | S34, S37 |
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
| | **Contingency 15%** | 95 | — |
| | **TOTAL** | **737** | |

> Story-table arithmetic, stated rather than hidden: the 29 pre-voice stories sum to **432h** against §2's 422h feature line — a +10h decomposition granularity (S7 is sized 26 vs its feature row's 24, and S13 is split out separately). The 11 voice stories sum to exactly **170h**, matching §2/§3 (158 voice + 12 policy deltas). PM (40) + 15% contingency (95) land the table at **737**, against the §9 headline of **727** — the 10h difference is precisely that story-vs-feature drift, and it is absorbed by contingency. Stories are the *execution* view; §9 is the *estimate* view. Nothing is rounded away silently.

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

### Voice: the $0 technical decision

The owner's constraint is absolute (V4): **no paid SDK, no metered minutes, no credit card on file, no surprise billing.** Two technically valid approaches exist. Only one survives it.

**Approach V-A (recommended): pure WebRTC on Android + Google's free public STUN + signaling over Firebase RTDB.**
- Media is peer-to-peer; the only infrastructure cost is signaling, which rides an existing Firebase project. STUN at `stun.l.google.com:19302` is free and unmetered.
- **The limitation, stated plainly rather than buried:** STUN only *discovers* addresses. Two players both behind **symmetric NAT** (common on carrier networks and some home routers) cannot complete ICE without a relay, because each side's mapped port differs per destination — so the address STUN returns is useless to the peer. **Expected failure rate: 5–15% of pairwise connections.** In a 4-player room this usually means one seat can't speak, not that the room dies.
- **UX fallback (this is the product promise, not a footnote):** the affected seat's mic indicator shows **"unreachable"** — a terminal, honest state, never a spinner. The room keeps playing; preset/text chat still works. Nobody opens a support ticket wondering if it's loading.
- **v1.1 fix, still $0:** self-host **coturn** on a free-tier VM (Oracle Cloud Always Free / Google e2-micro free tier) with a static IP. Cost stays zero; it costs *effort* (deployment + long-term-secret rotation), which is exactly why it is deferred, not why it's rejected. TURN would take the failure rate to ~0.

**Approach V-B: free-capped managed voice (Agora/LiveKit/Daily/Twilio "free tier").**
- Rejected on the constraint, not on quality — these are genuinely better products. Every one of them requires **billing activation** (a card on file) to reach the keys, and their free tiers are *metered*: 10k minutes/month at Agora, or a WAU cap, after which they bill at a per-minute rate. A 4-player room burns minutes whenever it's open. Under a room full of friends who leave the app running, "free tier" becomes a surprise charge — the exact outcome V4 exists to prevent.
- The managed-service carve-out the owner allowed ("free-forever within hard caps **and** an automatic hard-stop that never auto-charges") **does not apply**: none of these vendors refuse traffic when the cap is hit; they bill. The correct response is to name that and move on.

**Recommendation: V-A.** It is the only option that cannot bill us, and its single weakness (symmetric NAT) is a *known, disclosed, bounded* failure mode with a designed UX answer and a $0 fix on the roadmap. **Voice quality will be worse than Agora** — that is the honest cost of $0, and it is acceptable for a card game between four friends.

**Why signaling is on RTDB, not Firestore (this was forced, not chosen).** I verified every client-writable path in `firestore.rules` before designing: `rooms/{roomId}` updates may touch only `['players','readyPlayers','status','creator','updatedAt','matchId']`, `players/{uid}` only `['displayName','avatarInitial','lastSeenAt','currentRoomId','currentMatchId']`, and the catch-all at line 1950 denies everything else. **Signaling cannot piggyback on an existing doc, and a new collection is denied outright.** RTDB is a *separate product with its own rules file* — adopting it leaves the frozen, SHA-pinned `firestore.rules` byte-identical. The honest cost (R22): it still needs a `database.rules.json` artifact and a console enablement, so it is a **cost-free-but-time-cost rules item**, called out here so it is never mistaken for a surprise.

---

### Verification (how the plan gets tested end-to-end)

1. **JVM tier (existing CI, `:engine:test` / `:services:test`)** — every bot brain is pure, so golden bid cases, forbidden-13 avoidance, Dash gating, and spoiler gating run headless. The **bot-vs-bot full 18-round match** is the single best regression guard: it exercises bidding → table → scoring → Sa'ayda → extension end-to-end, on every commit.
2. **Instrumented tier (new, M5)** — four real `MatchService` instances in one emulator against the host Firestore emulator play a scripted full match, asserting **convergence** on the archive+advance race (asserting a single winner is wrong — the emulator has zero transaction retry). This is the first time actual Kotlin hits the actual frozen rules.
3. **JS rules suites (keep green)** — `tests/native-phase3-rules-negative.test.cjs` + `native-phase3-integration.test.cjs` remain the fidelity gate for the rules layer; they must not bit-rot. **The voice RTDB rules get their own negative test** (a player may not write another player's presence/offer/ICE subtree) — signaling is the one new rules surface in the project and it is tested at the same standard as the frozen rules, not hand-waved.
4. **Voice — 4 real devices, one room (M4b/M4c).** Not emulators: WebRTC audio path, AEC, and NAT behavior are all hardware-dependent, so an emulator pass here is a false green. The matrix must include: (a) at least one **deliberate symmetric-NAT pairing** (force it via a carrier-tethered device or a NAT-simulation router) asserting the seat shows "unreachable" and the round still completes; (b) a **speakerphone echo pass**; (c) the **permission-denied path** — revoke `RECORD_AUDIO` mid-room and confirm the room stays playable with voice controls hidden, no crash, no repeated re-prompt loop; (d) **backgrounding the app** and confirming transmission actually stops (verified by packet capture or the remote side's indicator flipping, not just the local UI); (e) **voice is gated to the casual-room path** — grep the whole codebase for any WebRTC init, `RECORD_AUDIO` prompt, or PTT button outside the room-code/room screen, and expect zero. Matches started *from* a casual room do carry voice (V2); everything else must not.
5. **Manual** — device matrix minSdk 26 → API 36, an Arabic RTL layout pass (including the PTT button and mic-justification dialog, which are new RTL surfaces), a 4-human online playthrough, and a real UMP consent flow on a EU-region device before submission.
6. **Field** — Crashlytics is the only way a one-engineer launch learns about field crashes; 72h at 5% rollout with no P0 before widening. For voice specifically, watch for a cluster of "unreachable" reports from one country/carrier — that's the symmetric-NAT rate exceeding the estimate and the trigger to pull coturn forward from v1.1.

---

### Honest reconciliation with the previous estimate

`ANDROID_MIGRATION_PLAN.md` says "**~5–8 months, one engineer**" for the whole migration. This plan says **~4.1 months for what's left** (18 weeks, voice included; 13 weeks without it). Those are consistent, not contradictory — roughly 5 months of the original range is already spent (Phases 0–10, spec through offline UI). The remaining 4 months lands inside the original envelope, and the voice addition is the reason this revision grew from 13 to 18 weeks.

The one place this plan disagrees with the existing documentation is the **status snapshot**: the plan's "~45% — session ~5%, Phase 3 untouched" was true on 2026-09-19 but is stale as of 2026-09-21, because PRs #47 and #48 merged the services module and the GameSession store. Estimating from the stale snapshot would have roughly **doubled** the online-multiplayer line. The assumptions that would have caused that error: trusting a status section instead of reading `origin/main`, and not noticing that `:services` is now a CI-tested module.

---

### Store & policy deltas introduced by voice (part of the 12h row)

Voice adds no new purchase, no new data collection, and no new server — but it *does* add a permission and a data-flow that Play and the privacy policy must describe accurately. These are cheap in hours and expensive in risk if skipped (R10/R18/R19).

| Item | What it must say | Where it lands |
|---|---|---|
| `RECORD_AUDIO` permission justification | "Used for push-to-talk voice chat with other players in the same room. Audio is captured only while you press and hold the talk button, is transmitted directly to those players, and is never recorded or stored." Must be **honest about PTT** — do not describe an always-on mic we don't have, and do not describe a mic we then leave open | Play Console → App content → Permissions; also the in-app rationale dialog (EN + AR) |
| Data-safety audio declaration | **Audio is not collected, not stored, not transcribed, not uploaded to any server.** It flows peer-to-peer between room members. If Play offers a "transferred" vs "collected" distinction, answer transferred-between-users, never "collected" | Play Console → Data safety. **Must agree with the permission justification and the privacy policy — reviewed as one set, not three** |
| Privacy policy sentence (EN + AR) | "Estemshan offers optional push-to-talk voice chat in casual rooms. Voice audio is transmitted directly between players in the same room while you hold the talk button. We do not record, store, transcribe, or retain voice audio." (Ranked/competitive modes, if ever introduced, will not include voice — a policy commitment, not a description of a shipped feature, so it is stated in the plan rather than the policy.) | Privacy policy page (hosted via the D4 web shrink) |
| Mic-disclosure posture | No foreground service, no background capture, default muted, auto-leave on background — **this is the policy defense, and it is a code property, not a claim**. If any of those four regresses, this disclosure becomes false | Verified by the voice QA matrix items (d) and (e), not by review |

**The dependency to respect:** `AI Bots/` strings are EN-only today, and the app's RTL is already enabled (`supportsRtl=true`) with no `res/` at all. The PTT button label, the mic-rationale dialog, and the "unreachable"/"muted-by-default" states are **new user-facing strings that must ship in EN and AR** — they belong in the same `strings.xml` batch as the app-name fix (S24), not as hardcoded Compose text.

---

### Voice strings (EN / AR) — the minimum translatable set

| Key | EN | AR |
|---|---|---|
| `ptt_button_hold` | Hold to talk | اضغط مع الاستمرار للتحدث |
| `ptt_state_muted_default` | Muted — hold to talk | تم كتم الصوت — اضغط مع الاستمرار للتحدث |
| `ptt_state_unreachable` | Voice unreachable — text chat works | التعذر الوصول للصوت — الدردشة النصية تعمل |
| `mic_rationale_title` | Voice chat needs your mic | يحتاج الدردشة الصوتية إلى الميكروفون |
| `mic_rationale_body` | Audio is sent only while you hold the talk button, directly to players in this room. Nothing is recorded or stored. | يُرسل الصوت فقط أثناء الضغط على زر التحدث، مباشرةً إلى اللاعبين في هذه الغرفة. لا يتم تسجيل أي شيء أو تخزينه. |
| `mic_denied_fallback` | Mic turned off — you can still play and chat | تم إيقاف الميكروفون — لا يزال بإمكانك اللعب والدردشة |
| `mute_player` | Mute this player | كتم هذا اللاعب |

Arabic translations are provided as a working baseline; **have a native speaker review them before submission** — the same standard the rest of the AR strings should meet.