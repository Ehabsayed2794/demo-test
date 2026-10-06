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
3. **Monetization has nothing to sell yet.** Shop/missions are deferred, so "monetization in v1" can only honestly mean **ads + one "Remove Ads" IAP** — not an economy. (**Seasons are NO LONGER deferred — RD24 makes them MVP; see the 2026-10-05 amendment below.**)
4. **The AI bots the owner just dropped in (`AI Bots/`) have a hidden coupling.** `botPersonality.ts` imports from `botSimulation.ts` (the "deferred" Monte-Carlo file) and uses it for **HARD, not just EXPERT**. It can't be cleanly deferred without a documented regression.

**Intended outcome:** management can see the true remaining scope, the critical path, and the one decision that actually controls the calendar (the AdMob 14-day closed-testing clock).

---

## Owner decisions of record (answered 2026-09-17)

Preserved verbatim from `ANDROID_MIGRATION_PLAN.md` §7, which this document supersedes as the project's sole plan. The code implementing all four is already merged to `main`.

- **D1 — Native scoring: as-is.** Carry bid² + dash tables + flat sole ±10 (Classic unchanged) into the native game. Same numbers the owner confirmed for legacy.
- **D2 — Risk table: graduated (native only).** Native Risk uses the canonical ladder — diff-from-13 of 1→0, 2–3→10, 4–5→20, 6+→30 — applied ONLY to the Risk player, stacking with Caller/With/sole exactly as §4 of `docs/specs/04-scoring.md`. `src/` keeps its flat ±10 (no change requested).
- **D3 — Billing verification: free tier.** RevenueCat (free tier, no server of our own). No paid plan required. Revisit only if volume outgrows the free tier. **Amended 2026-10-05 (RD11):** "no server of our own" still means **no dedicated always-on game server** — but Ranked progression now requires a **lightweight Cloud Functions + Firestore authority layer**. That is a per-match-invoked function, not a server; see §F and the appendix essay.
- **D4 — Web fate: small page.** After Play launch, hosting shrinks to a minimal site (store link + privacy policy). Full decommission explicitly rejected.

**Additional decisions made 2026-09-21 for this plan:** AI opponents are in v1 scope (4 tiers + personalities + "Play vs AI" + Choose Level screen), with EXPERT Monte-Carlo deferred post-launch over mobile perf risk. Monetization is in v1. The dealer seam is seat-keyed (`p1..p4`) — a uid→seat translation layer in the session layer is explicitly forbidden (see `GameSessionBridge.kt`'s seam note).

**Voice chat decisions of record (2026-09-21):**

- **V1 — Push-to-talk ONLY.** Hold/touch-to-speak; **muted by default.** No open mic, no always-on, no audio transmitted while the user is outside a room.
- **V2 — Casual rooms only.** Room-code rooms and matches started from them. **Ranked play ships with no voice, by design** (ranked is now **in v1 scope per RD25**, and it stays voiceless — RD22).
- **V3 — Max 4 speakers per room** (one mesh, the room's own player set).
- **V4 — Hard $0 constraint.** No paid SDK, no metered minutes, no credit card on file, no usage-based billing that can surprise us. Pure WebRTC (Android) + free public STUN + signaling over existing Firebase. **Agora / Twilio / Daily / LiveKit and any vendor requiring billing activation are forbidden.**
- **V5 — No recording, no transcription, no server-side storage of audio.** Audio flows peer-to-peer; the signaling channel carries only connection metadata.

**Voice scope placement:** Full version, **not MVP** (see §4). Voice is the lowest-priority item in v1 and the first thing an aggressive scenario defers — it is a social nicety for a room of friends, not a launch gate.

**i18n decision of record (2026-09-26):** S12 ships EN `strings.xml` infrastructure only (matching the 8 existing English-only screens); full Arabic — `values-ar` for every screen + migration of the 8 hardcoded screens + native-speaker review + RTL verification — is scheduled story **S41** in the store phase (with S24), in-v1 and blocking production (not the closed track). Per-screen state is never mixed: S12 uses `stringResource` from day one; the 8 older screens migrate wholesale in S41.

---

## Owner amendment of record (FINAL — 2026-10-05): the Ranked progression system

**This amendment is documentation-only. No code, no Firestore rules, no Cloud Functions, no Figma, no screens, no matchmaking have been implemented. It changes this plan's scope, stories, hours, and timelines.**

The owner issued a FINAL decision set for Ranked / RP / Seasons / Matchmaking / Vote Kick / Timers / Statistics / Leaderboard on 2026-10-05. Ranked is **no longer post-v1 — it is MVP and launch-blocking** (RD25). The decisions are recorded below as **RD1–RD28**, and they carry the same standing as D1–D4 and V1–V5 above.

The full design narrative, the contradiction register, and the per-screen UI impact live in `docs/UI_UX_ROADMAP.md` (its new **Phase 4b**). This document carries the **code, architecture, security, and estimate** impact.

### Decisions RD1–RD28

**Rank / RP**

- **RD1** The ladder is **6 tiers × 3 divisions + King = 19 ranks**: Bronze, Silver, Gold, Platinum, Diamond, Royal — each III → II → I — plus **King, which has NO divisions. Never write "7 tiers × 3 divisions."** Arabic titles are product identity, 1:1, never genericized: مبتدئ / لاعب / معلم / وزير / أمير / سلطان / ملك.
- **RD2** Division order is **I > II > III**. Gold I → Gold III is a two-step demotion; never write Gold III → Gold I as a demotion.
- **RD3** Every rank has a lower-bound RP; promote at the next rank's lower-bound, demote below the current floor. **RP ≥ 0 always.** King has a lower bound but no upper bound; RP above King is **leaderboard-only** (ordered against other King players by RP — RD27).
- **RD4** RP is **DYNAMIC**, not a fixed ±X. Inputs: opponent strength, tier difference, match outcome, final placement/performance, mixed-tier conditions. The engine is **independent of threshold constants** so numbers are tunable without redesign. Concept + inputs only; constants not finalized.
- **RD5** Mixed-tier public Ranked is **asymmetric**: higher-tier wins → **reduced** RP reward; higher-tier loses → loss **multiplied (×2 in Private Ranked, RD6's cap)**; lower-tier beats a higher-tier opponent → **increased** RP reward. All three directions documented explicitly.
- **RD6** Private/Password Ranked: **any tiers together**, no restriction; a **true Ranked match** awarding RP; **the same mixed-tier rules as public (RD5) apply**, with a **hard ×2 cap on the loss multiplier — never more than ×2.** NOT casual/unranked.
- **RD7** Demotion is real, with **exactly ONE match of demotion protection** — a limited one-match mechanism, not permanent.
- **RD8** **Rank King ≠ Match King.** Rank King = account competitive ceiling; Match King = first place in one match; Koz = current unique last place, NOT a ranked tier.

**Matchmaking**

- **RD9** Public Ranked matchmaking is **server-controlled**. The player never picks a higher-tier pool; the server derives it. **Own tier or exactly one tier below, never two+**; a lower-tier player cannot opt up (Silver cannot request Gold). The server **may form a mixed Gold/Silver match** — do not write "Gold players can only be matched in Gold."

**Placement**

- **RD10** **Once per account, never per season.** 3 matches: M1 Easy+Medium, M2 Medium+Hard, M3 Hard+Expert. The player cannot choose difficulty or personality and **never sees a provisional rank** — only "Placement 1/3, 2/3, 3/3." Score from real engine signals (placement, result, score, estimate accuracy, bidding performance, consistency). **Weights FINAL: M1 10%, M2 20%, M3 70%.** Max placement = **Platinum** (never Diamond/Royal/King). Determines Tier + Division; the player starts at **that division's first/lower-bound RP**. **Placement matches count toward NO statistic.** "Start from Bronze" is permanent and irreversible.

**Authority / security / cost**

- **RD11** **Lightweight Firebase-backed authority: Cloud Functions + Firestore** — NOT a dedicated always-on game server, NOT enterprise-scale. Early-stage game; low infra cost; enough integrity that progression can't be trivially forged; upgradeable later.
- **RD12** **Correct-and-settle.** Client `finalScores` is not blindly trusted and not merely rejected: the backend **validates, corrects/recomputes the authoritative result, and settles the corrected result**; RP is computed from it.
- **RD13** Settlement is **idempotent** (once per match), authenticated, retry-resistant. **The client is read-only for rank/RP/history after settlement.** No fabricating wins/scores/RP, no suppressing losses, no double-settling, no client rank manipulation. **Action evidence is ownership-constrained and immutable:** one client must not be able to rewrite another player's actions or fabricate another player's result — each player's action records are written under that player's own identity, and the converged sequence the server re-derives is append-only.
- **RD14** **Settlement alone is NOT the whole boundary.** If all Ranked-critical state stays client-authoritative, settlement is insufficient. The plan includes **server-side validation of Ranked-critical state, actions, and results**. **Do not claim Cloud Functions provide full realtime authority** — the real boundary is documented below as the **MVP security boundary**.
- **RD15** Rooms = lower-security, cost-sensitive, no RP progression, no Ranked authority needed. Ranked = critical outcomes protected, authority in the backend.
- **RD16** **Cost principle:** prefer the lower-cost MVP option provided it creates no unacceptable integrity risk. No expensive infra because it is technically stronger. Strengthen later on growth / revenue / cheating pressure / competitor pressure / budget.

**Ties / King-Koz / Vote Kick / inactivity**

- **RD17** Ties: tie 1st → all are Match King; tie 2nd → all 2nd; tie 3rd → all 3rd; **stats count the placement actually achieved, even when shared**. Tie for last → **no unique Koz → no Vote Kick**; never fabricate a Koz.
- **RD18** **Vote Kick is RANKED ONLY — not in Rooms** (this corrects frozen Batch 1 text in `docs/UI_UX_ROADMAP.md`; see its contradiction register). Only **after Round 7 has FULLY completed** (first vote is Round 8). Target = the **unique** Koz; the target cannot initiate; any other player may. **2 YES of 3 non-target players** (2Y/1N = kick; 1Y/2N = no; no majority by timeout = no). Failure → **5-round cooldown on that same target only**. Success → **permanent** removal, **no rejoin**, recorded as **Koz**. Purpose: griefing/abuse, **not** normal mistakes.
- **RD19** Inactivity (**50 s**) → the configured bot takes over immediately; the player **may reconnect and reclaim the SAME seat**; the bot stands down. Vote Kick forbids rejoin. Never merge the two.
- **RD20** **Timeout → MEDIUM bot decision engine regardless of configured difficulty.** Explicit **leave → configured difficulty + configured personality immediately.** Timeouts accumulate across **all** phases (Dash, Bidding, Estimates, Card Play); **15 total decision timeouts during the same match → automatic kick/removal — a SEPARATE mechanism from Vote Kick.** It requires no Round-7 completion, no Koz target, no vote, no 2-of-3 threshold, no cooldown, and does not count as a Vote Kick. **Never merge the two systems.** Reconnect-after-15-timeout-removal behaviour is **not finalized** (OPEN-1). **The timeout counter is cumulative across the whole match and is PRIVATE — opponents must never see another player's timeout count.**

**Config / timers / voice / stats / seasons / leaderboard / mode**

- **RD21** One Create Game screen for Rooms and Ranked. Replacement-bot difficulty Easy/Medium/Hard/Expert; personality from the existing four only (reuse the Kotlin implementation, invent none); decision timer 5/10/15/20. **Dash/Bidding/Estimates = base + 5; Card Play = base** (15 → 20/20/20/15). **Defaults: Medium bot, the approved default personality, 15 s timer.** (The default-personality constant is a one-line addition at port time — the value is the existing `BALANCED` literal; `BotPersonality.DEFAULT` does not exist yet.)
- **RD28** **`mode` is exactly two values: `ROOM` or `RANKED`.** Private/Password Ranked is **NOT a third mode** — it is `RANKED` plus a private/password access flag. It still awards RP and stats and still allows any tier mix (RD6). `mode` is the authority key that gates Vote Kick, settlement, and Ranked statistics; the private flag only gates access.
- **RD22** Voice is **Rooms-only** (PTT, press-and-hold, Waiting Room + Rooms match). **Never in Ranked.**
- **RD23** **Exactly 9 career stats:** Games Played, King count, King %, 2nd count, 2nd %, 3rd count, 3rd %, Koz count, Koz %. (**Never "10 values."**) Include Public + Private Ranked; **exclude Placement**. Long-press shows **SHORT stats only**: Rank, Games, King %, 2nd %, 3rd %, Koz %. **No player-facing match history for MVP**; an internal RP audit ledger IS required. Profile records **Highest Rank ever**, never erased by seasonal demotion.
- **RD24** **Seasons are MVP scope NOW.** Duration **3 months**. End-of-season demotes Tier/Division **two division steps** (Gold I → Gold III), floored at the ladder bottom; **career stats never reset**. Placement never repeats per season. No separate trophy system. **King has no divisions and is demoted to Royal I at season reset** (owner-closed; RD1's division-less King is preserved — this is a destination, not a division).
- **RD27** **Seasonal Ranked Leaderboard is in scope.** Server-authoritative ordering: rank position, player, Tier/Rank, RP, season, **current player highlighted**. **King players are ranked among themselves by RP above the King lower bound** — King has no divisions, so RP is the only ordering key there (RD3). **Season isolation:** the board shows one season only and resets with the season (RD24); rank position is recomputed per season, never blended. The client may read, never order.

**Open (do not invent answers)**

- **RD26** **RP thresholds for all 19 ranks — CLOSED 2026-10-05.** These are the
  **lower-bound RP** values, per RD3 — promote at the next rank's lower bound,
  demote below the current floor:

  | Rank | Lower-bound RP | | Rank | Lower-bound RP |
  |---|---:|---|---|---:|
  | Bronze III | 0 | | Platinum III | 1,000 |
  | Bronze II | 75 | | Platinum II | 1,200 |
  | Bronze I | 150 | | Platinum I | 1,400 |
  | Silver III | 250 | | Diamond III | 1,600 |
  | Silver II | 350 | | Diamond II | 1,800 |
  | Silver I | 450 | | Diamond I | 2,000 |
  | Gold III | 575 | | Royal III | 2,250 |
  | Gold II | 700 | | Royal II | 2,500 |
  | Gold I | 825 | | Royal I | 2,750 |
  | | | | **King** | **3,000** |

  Structure per RD1–RD3: Bronze → Silver → Gold → Platinum → Diamond → Royal,
  each III → II → I, plus King with **no division** = 19 ranks. **RP is dynamic
  (RD4), never a fixed ±X per match** — these are only the rank boundaries the
  dynamic engine measures against. **RP ≥ 0 always**; **King has a lower bound
  and no upper bound — RP above King is leaderboard-only** (RD27). The thresholds
  are **tunable server/config constants and stay independent of the RP
  calculation engine** (S46), so these numbers can move after playtest without
  touching the engine or the UI. Nothing is derived from the `design-ui` mocks —
  their values are non-canonical legacy data.
- **OPEN-1** **Reconnect after 15-timeout automatic removal — not finalized.** The removal itself is decided (RD20); whether/how that player may rejoin that match is an open implementation detail.
- **OPEN-2** **Is gameplay paused while a Vote Kick is active?** The vote's target is the *unique current Koz*, so the target must not be able to change mid-vote. Whether the match pauses for the vote duration is **not established by the repository or the design**. Recorded as a small owner decision, not silently assumed.

---

## Owner amendment of record (2026-10-06): Game Type & Calculation Mode

**Documentation-only. No Kotlin, no Firestore rules, no UI, no Cloud Functions have been changed.** This amendment records a FINAL owner decision set, the audit that verified it against the code, and the file-by-file plan that implements it. It changes this plan's scope, stories, and hours.

The design view lives in `docs/UI_UX_ROADMAP.md` (S36, §2.4, §4b); the rules view in `docs/rules/CANONICAL_RULES.md` **Amendment A2**. This document carries the **code, architecture, security, and estimate** impact.

### Decisions GM1–GM8

These carry the same standing as D1–D4, V1–V5, and RD1–RD28.

**Game Type**

- **GM1 — Two Game Types: FULL and MINI.** FULL = 18 base rounds, rounds 14–18 Quick. MINI = 10 base rounds, rounds 1–5 normal, rounds 6–10 Quick. **Identical rules otherwise** — same 52-card deal, same auction, same estimates, same card play, same scoring, same roles, same win conditions, same flow. MINI is FULL with two numbers changed, not a second ruleset.
- **GM2 — FULL's extension behaviour is preserved byte-for-byte.** Window stays 14–18, up to 5 extensions (18 → 23 maximum), rounds 19+ repeat the trump ladder and cannot trigger. The existing golden tests (`BiddingTest.kt:451-458`) must keep passing unaltered.
- **GM3 — MINI allows exactly ONE extension.** Window is rounds 6–10, capped at **one extension total** (10 → 11 maximum). A second extension is rejected through the existing `ALREADY_EXTENDED` path, extended with a count guard — **not a new error surface**.
- **GM8 — One shared GameType model; no duplication.** The engine derives base round count, first Quick Round, the fixed-trump offset, and the extension policy from a single `GameType`. FULL → 18 / 14 / up to 5; MINI → 10 / 6 / max 1. Scoring formulas stay shared and are selected through `ScoringMode`.

**Calculation Mode**

- **GM4 — Two Calculation Modes: NORMAL and CLASSIC.** Both formulas already exist in the engine and are **preserved unchanged** (`calculateNormalScore`, `calculateClassicScore`). The selected `ScoringMode` is wired through the session, the match document, the online flow, and the local/quick-match flow; the hard-coded `classic = false` sites are replaced by the persisted mode. Classic's escalation cap ×2 (vs Normal ×8) is already correct at `Session.kt:241-242` and must simply be reached.

**Ranked interaction**

- **GM5 — Ranked supports BOTH Game Types.** Ranked is **not** Full-only. `gameType` is part of ranked match creation, the persisted match state, the matchmaking state, and the RP calculation. The RD9 tier-pool rule is **orthogonal** to GameType: the server still derives the tier pool; the player's GameType choice rides along and does not narrow or widen it.
- **GM6 — MINI Ranked RP = 50% of the FULL RP delta.** The Ranked result is computed **exactly as today first** — every modifier, every RD5 asymmetry, every threshold — and only then is the **final RP delta** multiplied by 0.5. Applies to **gains and losses** (+20 → +10, −14 → −7). **No separate Mini formula.** Thresholds, ladder, divisions, gates, progression, promotion/demotion, asymmetry, and season behaviour are **identical** between Full and Mini; nothing is compensated for the shorter match.
- **GM7 — Defaults: FULL + NORMAL.** Joined to the RD21 defaults on S36 (MEDIUM / BALANCED / 15 s). The screen opens FULL + NORMAL every time.

**Open**

- **OPEN-3 — RP rounding for MINI (from GM6). CLOSED 2026-10-06: nearest integer, ties rounded AWAY FROM ZERO (symmetric).** **There was no existing RP implementation to inspect** — verified 2026-10-06: zero hits for RP / rank-points / ELO / placement / season / leaderboard across `native/` **and** `src/`, and no `functions/` directory exists. `docs/UI_UX_ROADMAP.md:2331-2334` records the same. So GM6's ×0.5 could not "respect an existing convention"; the rule is set here instead. The only in-repo precedent is `src/utils.ts:12` ("rounded half-up"), which is a **round-score** rule (Amendment A1) and does not bind RP.

  **The closed rule:** RP stays an integer; after the ×0.5, round to the nearest integer with **exactly .5 rounded away from zero**, symmetrically for gains and losses.

  | Full delta | ×0.5 | Rounded RP |
  |---|---|---|
  | +20 | +10 | **+10** (exact) |
  | −14 | −7 | **−7** (exact) |
  | +15 | +7.5 | **+8** |
  | −15 | −7.5 | **−8** |
  | +9 | +4.5 | **+5** |
  | −9 | −4.5 | **−5** |

  **The implementation trap this decision exists to catch:** `java.lang.Math.round(Double)` rounds **half up toward +∞**, so `Math.round(-7.5)` returns **−7** — asymmetric, and wrong for this decision. The correct call is **`kotlin.math.round(Double)`**, which rounds ties **away from zero** (`round(-7.5) == -8.0`), then `.toInt()`. A test asserting `−15 → −8` fails against `Math.round` and passes against `kotlin.math.round`; that asymmetry test is therefore mandatory in S47, not optional.

  **Status:** closed before S47 is written, as planned. It no longer gates anything.

### The audit (verified against `origin/main`, 2026-10-06)

**1. Every hard-coded assumption of 18 rounds.**

| Site | Form |
|---|---|
| `native/engine/.../Session.kt:58` | `RoundState.maxRounds: Int = 18` — the round-ceiling default |
| `native/services/.../model/MatchModels.kt:318` | `const val DEFAULT_MAX_ROUNDS = 18` |
| `native/services/.../MatchShapes.kt:80` | `put("maxRounds", DEFAULT_MAX_ROUNDS)` in `buildMatchFields` — the ONE match-doc writer |
| `firestore.rules:1349` | `&& data.maxRounds == 18` in `isValidNewMatch()` |
| `firestore.rules:480` | `&& data.maxRounds == 18` in `isValidNewRematchMatch()` |

Doc prose: `UI_UX_ROADMAP.md:787, 1636`; `CANONICAL_RULES.md:21`; `01-screens.md:35`.

**2. Every hard-coded assumption that Quick Rounds are 14–18.**

| Site | Form |
|---|---|
| `native/engine/.../Bidding.kt:103` | `fun isFastRound(roundNumber: Int) = roundNumber >= 14` |
| `native/engine/.../Bidding.kt:105` | `fixedTrumpFor(roundNumber) = FIXED_SUITS[(roundNumber - 14) % FIXED_SUITS.size]` |
| `native/engine/.../RoundScore.kt:225` | `if (round < 14 || round > 18) return RoundExtension(false)` — extension eligibility |
| `native/services/.../MatchShapes.kt:201` | `isRapidRound(round) = round in RAPID_ROUND_MIN..RAPID_ROUND_MAX` (14..18, from `MatchModels.kt:321-322`) |
| `native/services/.../MatchService.kt:567` | the `isRapidRound(completedRound)` gate in `extendMatchRounds` |
| `firestore.rules:1139` | `&& appendedRound >= 14 && appendedRound <= 18` |

Consumers that inherit the boundary: `OnlineMatchViewModel.kt:512` (fast vs normal round init), `BidBrain.kt:317` (bot trump), `Bidding.kt:675` and `:705` (fast Super Call replacement trump), `RoundScore.kt:229` (the Super extension test), `ScriptedMatch.kt:225`.

**3. Every extension path, and where the MINI cap is enforced.**

- **`MatchService.extendMatchRounds` (`MatchService.kt:561-603`)** — the ONE place `maxRounds` ever increments. Existing guards: `isRapidRound` (:567), `isComplete` (:584), per-round idempotency `completedRound in match.extendedRounds` (:587). **There is no extension-COUNT cap today** — GM3's "Mini max 1" is a new guard here: `match.extendedRounds.size >= gameType.maxExtensions` → return `ALREADY_EXTENDED` before writing. FULL's allowance is 5, which the 14–18 window already implies; the count cap is *not* a behaviour change for FULL.
- **`computeRoundExtension` (`RoundScore.kt:218-233`)** — decides whether a round extends at all; its `14..18` window becomes `gameType.firstFastRound..gameType.baseRounds`.
- **`firestore.rules:1096-1141` `isValidRoundExtension()`** — the independent server-side re-check, and the enforcement that survives a malicious client. Line 1139's window becomes type-derived **and** a count cap is added: `oldData.extendedRounds.size() < (oldData.gameType == 'MINI' ? 1 : 5)`. The rules must read `gameType` off the document.
- Client: `OnlineMatchViewModel.kt:758` reads `extension.extend` and computes `doc.maxRounds + 1`; no change needed beyond carrying the type.

**4. The exact trump / fixed-suit logic that must become GameType-aware — and the crash it hides.**

`Bidding.kt:105` `fixedTrumpFor(roundNumber) = FIXED_SUITS[(roundNumber - 14) % FIXED_SUITS.size]`. For **MINI round 6** this evaluates `FIXED_SUITS[(6 - 14) % 5]` = `FIXED_SUITS[-8 % 5]` = `FIXED_SUITS[-3]`. **Kotlin's `%` keeps the dividend's sign, so this is `IndexOutOfBoundsException` — a crash, not a wrong suit.** MINI cannot deal a single Quick Round until this is parameterized. The fix is `Math.floorMod(round - gameType.firstFastRound, FIXED_SUITS.size)`, which reproduces FULL exactly: 14 → Sans, 15 → Spades, 16 → Hearts, 17 → Diamonds, 18 → Clubs, 19 → Sans (`floorMod(5, 5) == 0`), satisfying every assertion at `BiddingTest.kt:451-458` unchanged. For MINI: 6 → Sans, 7 → Spades, 8 → Hearts, 9 → Diamonds, 10 → Clubs, 11 (the one extension) → Sans. The same parameterization must reach `initFastRound` (`Bidding.kt:140`), the two Super Call fallbacks (`Bidding.kt:675, 705`), `RoundScore.kt:229`, and `BidBrain.kt:317`.

**5. Every place GameType must be persisted and propagated.**

1. `RoomDoc.kt:8-24` + the `isValidNewRoom` allowlist (`firestore.rules:103`) — the room is created from S36, so `gameType` rides the room doc to the match.
2. `MatchService.startMatch` (`MatchService.kt:128-165`) — today passes **no** config from the room; must read `gameType`/`scoringMode` and hand them down.
3. `buildInitialMatchFields` / `buildMatchFields` (`MatchShapes.kt:44, 65-90`) — write `gameType` and the **derived** `maxRounds` (18 or 10).
4. `buildRematchMatchFields` (`MatchShapes.kt:101`) — a rematch preserves the type.
5. `MatchDoc` (`MatchModels.kt:198`) + `fromFields` (`:267+`) — parse `gameType`.
6. `firestore.rules` `isValidNewMatch` (`:1336-1350`) and `isValidNewRematchMatch` (`:474-481`) — allowlists plus the `maxRounds == 18` hard-code (see audit point 9).
7. `OnlineMatchViewModel` — `session.setGameType(doc.gameType)` before `initAuction`, alongside the existing `setRound(... maxRounds = doc.maxRounds)` at `:493`.
8. `Session` — a `gameType` field feeding the `RoundState.maxRounds` default and the Quick Round boundary.
9. **Matchmaking / Ranked (not yet built)** — `gameType` in the search request, in the server-created match, and in settlement input (GM5). Specified now so **S56 (matchmaking) and S50/S51 (settlement)** never inherit a Full-only assumption.
10. `QuickMatchViewModel.startMatch` (`:107`) — today seeds round 1 with **no** ceiling at all; takes the type.

**6. Every place ScoringMode must be persisted and propagated.**

The same room → match → doc → client chain as point 5, plus:

| Site | Change |
|---|---|
| `OnlineMatchViewModel.kt:744` | `classic = false` → `classic = session.scoringMode == ScoringMode.CLASSIC`. `escalationCap` at `:745` is **already correctly wired** and stays. |
| `QuickMatchViewModel.kt:167` | `classic = false` → same. **Pre-existing gap fixed here:** this call site omits `escalationCap` entirely, so Classic silently keeps the Normal ×8 cap; it must pass `session.escalationCap`. |
| `Session.freshSession()` (`Session.kt:181`) | resets `scoringMode = NORMAL` unconditionally → resets to the match's configured mode |
| `ScriptedMatch.kt:181` | the instrumented harness already calls `setScoringMode(NORMAL)`; drives the configured mode |

Both formulas themselves (`Scoring.kt:50-98`, `:100-141`) and the branch at `RoundScore.kt:157-167` are **untouched** — GM4.

**7. Every Ranked / RP path that currently assumes Full-only.**

**None — because no Ranked or RP path exists.** Zero RP/rank/ELO/placement/season/leaderboard code in `native/` or `src/`; no `functions/` directory (see OPEN-3). The work is therefore **making the shared substrate GameType-aware *before* S45–S58 are built**, so Ranked never acquires the assumption. The only Full-only assumptions today are in the *documents*: `UI_UX_ROADMAP.md:1636-1639` (§2.4), `:787-789` (loop counts), `:979-982` (S16), `:2470`; `CANONICAL_RULES.md:21, 58, 62, 92-93, 109`; `MatchLifecycle.md:68, 100`; `03-transactions.md:64`. Each is amended by this decision set. `UI_UX_ROADMAP.md:1658` already flagged the prerequisite independently ("`MatchDoc` has no mode field today — that is the first thing to add").

**8. The exact location where the final RP delta is calculated.**

**It does not exist yet.** There is no RP function, no RP field, no settlement code (see OPEN-3). So GM6's ×0.5 is recorded as an **insertion contract** for **S47 (the RP engine) and S50/S51 (settlement)** rather than a patch: compute the full Ranked result exactly as the RD4/RD5 architecture specifies — opponent strength, tier difference, outcome, placement, mixed-tier conditions, every modifier — and apply the multiplier **once, at the single authority point, after the Full-equivalent delta is final and before RP is written to the player's rank document**:

```
finalRpDelta = kotlin.math.round(fullEquivalentFinalRpDelta * (if (gameType == GameType.MINI) 0.5 else 1.0)).toInt()
```

Applied on the **backend** (RD12 correct-and-settle, RD13 idempotent settlement), never on the client, and never inside any per-round or per-component computation. **`kotlin.math.round`, not `java.lang.Math.round`** — the latter rounds −7.5 to −7 and breaks the closed rule (see OPEN-3). When `gameType == FULL` the multiplier is exactly 1.0 and `round(…).toInt()` is a no-op, so **Full's RP is bit-identical to a system with no multiplier at all**; the rounding only ever touches Mini. **S47's** settlement tests must assert both even and odd deltas in both signs (see the test plan), including the `Math.round` asymmetry test that fails against the wrong call.

**9. Firestore rules changes required.**

`firestore.rules` is SHA-pinned (`firestore.rules.sha256`); a rules change is a **deployment**, not just a commit, and needs the emulator suite re-run.

| Function | Line | Change |
|---|---|---|
| `isValidNewRoom` | `:103` | add `gameType`, `scoringMode` to the `hasOnly` allowlist; accept `gameType in ['FULL','MINI']` (absent ⇒ `'FULL'`, for back-compat with rooms created before this amendment) and `scoringMode in ['NORMAL','CLASSIC']` (absent ⇒ `'NORMAL'`) |
| `isValidNewMatch` | `:1336-1350` | add both keys to `hasOnly`; replace `data.maxRounds == 18` with a **type-derived** check: `(data.gameType == 'FULL' && data.maxRounds == 18) \|\| (data.gameType == 'MINI' && data.maxRounds == 10)`; keep `extendedRounds == []` |
| `isValidNewRematchMatch` | `:474-481` | same allowlist + type-derived `maxRounds`; **plus** `data.gameType == oldMatch.gameType` — a rematch must inherit the type |
| `isValidRoundExtension` | `:1139` | the `>= 14 && <= 18` window becomes type-derived (`FULL` 14–18, `MINI` 6–10) **and** add the count cap `oldData.extendedRounds.size() < (oldData.gameType == 'MINI' ? 1 : 5)` |

Because absent-means-FULL back-compat is baked into the room rule, **existing rooms and matches keep working with no migration**; a Full match is byte-identical to today.

**10. All roadmap / spec documents that must be amended.**

1. `docs/rules/CANONICAL_RULES.md` — **Amendment A2** (the rules authority; §1 "Number of Rounds", §3 "Forced Fast Rounds", the extension-repeat note at `:109`).
2. `docs/UI_UX_ROADMAP.md` — S36 (two new setting groups), §2.4 round-loop counts (GameType-aware), the loop-count paragraph (`:787-789`), the S16 fast-round spec (`:979-982`), §4b (GM5 Mini-in-Ranked + GM6 RP ×0.5), and the consistency-audit list.
3. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — this amendment; the sunk-cost table; the E6b story table (**S47 acquires the ×0.5**, S43 and S56 acquire `gameType`); the estimate totals.
4. `docs/architecture/MatchLifecycle.md:68, 100` and `docs/specs/03-transactions.md:64` — the 14–18 references, amended to note the type-derived boundary.
5. `docs/architecture/FirestoreSchema.md:102` — the "up to 18 rounds" write-frequency ceiling.

### Architecture: the shared GameType model

```kotlin
enum class GameType(val baseRounds: Int, val firstFastRound: Int, val maxExtensions: Int) {
  FULL(baseRounds = 18, firstFastRound = 14, maxExtensions = 5),
  MINI(baseRounds = 10, firstFastRound =  6, maxExtensions = 1);

  fun isFastRound(round: Int): Boolean = round >= firstFastRound
  fun isExtensionRound(round: Int): Boolean = round in firstFastRound..baseRounds
  fun fixedTrumpFor(round: Int): Suit = FIXED_SUITS[Math.floorMod(round - firstFastRound, FIXED_SUITS.size)]
}
```

`ScoringMode` (`Session.kt:26`) already exists and already selects the formula (`RoundScore.kt:157-167`) and the cap (`Session.kt:241-242`) — GM4 needs no new type, only wiring. **FULL preservation proof:** with `firstFastRound = 14`, `isFastRound` reduces to `round >= 14` (Bidding.kt:103), `fixedTrumpFor` reduces to the existing ladder (14→Sans … 19→Sans, satisfying `BiddingTest.kt:451-458`), `isExtensionRound` reduces to `14..18` (RoundScore.kt:225 / MatchShapes.kt:201 / firestore.rules:1139), and `maxExtensions = 5` is exactly what the 14–18 window already yields. **No FULL behaviour changes; MINI is the same code with different constants.**

### File-by-file implementation plan

**Phase 1 — `:engine` (S65, 10h).** New `GameType` enum (in `Bidding.kt` beside `FIXED_SUITS`, or its own file). Parameterize `isFastRound`, `fixedTrumpFor`, `initFastRound`/`initNormalRound`, and `computeRoundExtension` to take a `GameType` (kept as plain functions so `RoundScore.kt` stays I/O-free). Add `Session.gameType` feeding `RoundState.maxRounds`'s default. **No call site is changed in this phase except to pass `GameType.FULL`** — FULL stays the default everywhere until the UI exists.

**Phase 2 — `:services` (S66, 12h).** `RoomDoc` + `MatchDoc` gain `gameType`/`scoringMode` (absent ⇒ FULL/NORMAL in `fromFields`, so old docs parse). `DEFAULT_MAX_ROUNDS` stays 18 as FULL's constant. `buildMatchFields` writes both fields and derives `maxRounds` from the type. `buildRematchMatchFields` inherits the old match's type. `startMatch` reads config off the room and passes it down. `extendMatchRounds` gains the `extendedRounds.size >= gameType.maxExtensions` guard, returning `ALREADY_EXTENDED`. `isRapidRound` takes the type.

**Phase 3 — `firestore.rules` (S67, 6h).** The four function changes in audit point 9, with absent-means-FULL/NORMAL back-compat. Re-run the emulator suite; re-pin `firestore.rules.sha256`.

**Phase 4 — `:app` (S68 + S69, 16h).** S36 gains its **fourth and fifth setting groups** — **Game Type** (Full / Mini) and **Calculation** (Normal / Classic) — using the existing `PickerRow` idiom (`ChooseLevelScreen.kt`), above the three RD21 groups, defaulting FULL + NORMAL (GM7). Config state gains `gameType`/`scoringMode`. `OnlineMatchViewModel` sets both from the doc before `initAuction` and sources `classic` from the session (`:744`). `QuickMatchViewModel` seeds the type at `:107` and passes both `classic` and `escalationCap` (`:167`).

**Phase 5 — documents (4h).** CANONICAL_RULES A2, UI_UX_ROADMAP amendments, this amendment, the two architecture/spec note updates.

**Sequencing consequence (the planning point):** this epic is a **prerequisite for E6b (S42–S64)** — Ranked must be GameType-aware from the first line of **S47** (the RP engine that applies GM6's ×0.5) — and for **finalizing the S36 artboard**, which now shows five groups, not three. It is scheduled **before** both.

### Test plan

**`:engine` (unit)**
- **FULL golden regression (the safety net):** `BiddingTest.kt:451-458` unaltered, plus new cases asserting `GameType.FULL.fixedTrumpFor` equals the old `fixedTrumpFor` for rounds 14–23, `FULL.isFastRound` matches `round >= 14`, and `FULL.isExtensionRound` matches `14..18`. If any of these fail, FULL changed.
- **MINI ladder:** 6 → Sans, 7 → Spades, 8 → Hearts, 9 → Diamonds, 10 → Clubs, 11 → Sans (the one extension repeating).
- **The crash, as a test:** MINI round 6 through the old code path throws `IndexOutOfBoundsException`; through `GameType.MINI.fixedTrumpFor(6)` it returns Sans. Pin the regression.
- **MINI extension cap:** `computeRoundExtension` returns `extend = true` for a qualifying round 6–10 with zero prior extensions, and `false` once `extendedRounds.size == 1`; FULL still extends up to 5 times (14, 15, 16, 17, 18) and never on 19+.
- **Scoring formulas are untouched:** `ScoringTest.kt:200-202` (Classic) and the Normal suite pass without modification. `GameSessionTest.kt:102-112` already pins the escalation-cap duality — keep it green.

**`:services` (unit + instrumented)**
- `buildMatchFields` writes `maxRounds` 18 for FULL and 10 for MINI, plus both mode fields; `fromFields` parses a doc with **no** `gameType` as FULL (back-compat) and a doc with `'MINI'` as MINI.
- `buildRematchMatchFields` copies the old match's type.
- `extendMatchRounds`: a MINI match's **first** qualifying extension succeeds (10 → 11) and the **second** returns `ALREADY_EXTENDED` with `extended = false` — asserted on a fresh doc read, not on the returned result (`instrumented-suite-assertions`). A FULL match still reaches 23.
- `ScriptedMatch` gains a MINI variant: 10 base rounds, Quick from 6, exactly one extension. This is the end-to-end Mini smoke test.

**`firestore.rules` (emulator)**
- A FULL match create is accepted with `maxRounds == 18`; a MINI create is accepted with `maxRounds == 10`; a MINI create claiming 18 is **denied**.
- An extension on a MINI doc with `extendedRounds == []` is accepted; the same doc with one entry is **denied** — proving the count cap holds when the client lies.
- A room create carrying `gameType: 'MINI'` + `scoringMode: 'CLASSIC'` is accepted; one carrying `'MEGA'` is denied.
- The FULL paths are re-run unchanged as the regression gate.

**`:app` (unit)**
- S36 state opens FULL + NORMAL (GM7) and the two new groups are independently selectable without disturbing the RD21 trio.
- `OnlineMatchViewModel`: a MINI doc seeds `maxRounds == 10` and resolves round 6 to `initFastRound` (not `initNormalRound`); a CLASSIC doc drives `classic = true` into `RoundScoreInput` with `escalationCap == 2`.
- `QuickMatchViewModel`: CLASSIC now passes `escalationCap == 2` (the pre-existing gap), and a MINI quick match plays 10 rounds.

**S47/S50/S51 settlement (written when E6b lands, specified now)**
- Same Full-equivalent result, MINI delta exactly half: +20 → +10, −14 → −7.
- **Rounding, per the closed OPEN-3 rule (nearest integer, ties away from zero):**
  +15 → +8, −15 → −8, +9 → +5, −9 → −5. **All four tie cases are separate
  assertions** — a tie is the only place a rounding rule can be wrong.
- **The `Math.round` trap, as a test:** the same inputs through
  `java.lang.Math.round` yield −7.5 → **−7** (half-up toward +∞), so a test
  asserting `−15 → −8` fails against `Math.round` and passes against
  `kotlin.math.round`. This test is **mandatory**, not optional — it is the
  guard that keeps a future refactor from silently asymmetrizing Mini's losses.
- **Full is a no-op:** with `gameType == FULL` the multiplier is exactly 1.0 and
  the round is a no-op, asserted so Full's RP is provably bit-identical to a
  system with no multiplier.
- The multiplier is applied **once**, at settlement, and never in any per-round or per-component path.

### Estimate impact

New epic **E7 — Game Type & Calculation Mode (GM)**, placed **before** E6b:

| Story | Scope | Hours |
|---|---|---|
| S65 | `:engine` `GameType` + parameterized fast-round/extension/trump logic + unit tests | 10 |
| S66 | `:services` models + match/room builders + `startMatch` propagation + the MINI extension cap + tests | 12 |
| S67 | `firestore.rules` (four functions, absent-means-FULL back-compat) + emulator re-run + re-pin | 6 |
| S68 | S36 UI: the Game Type + Calculation groups, config state, strings | 8 |
| S69 | `OnlineMatchViewModel` + `QuickMatchViewModel` wiring (incl. the escalationCap gap) + tests | 8 |
| — | Documents: CANONICAL_RULES A2, UI_UX_ROADMAP, this amendment, architecture/spec notes | 4 |
| **E7 total** | | **48** |

**Totals become 1,080 pre-contingency (was 1,032) and ~1,242 after 15% (was ~1,187).** E7 does **not** add to E6b's 388h — it precedes it, and S47 absorbs the ×0.5 contract above as acceptance criteria.

---

## Correction: what's actually already built (sunk, not re-estimated)

Verified on `origin/main`:

| Layer | State | Evidence |
|---|---|---|
| `:engine` — Cards, Deck/Dealer, Bidding, Table, Scoring, RoundScore, **GameSession** | **DONE, green** | 14 kt files; `:engine:test` green incl. P1-3 hazard/safe pins |
| `:services` — MatchService, MatchAdapter, RoomService, TxSupport, models, session/ | **DONE, green** | 15 kt files; CI runs `:services:test`; `MatchAdapterReloadReplayTest` + `GameSessionBridgeTest` |
| `:app` Compose UI — Splash, Login, Bidding, Table, Standings, Profile, Settings | **DONE** | Offline hot-seat QuickMatchFlow end-to-end |
| Online multiplayer wiring | **0%** | `:app` depends on `:engine` **only** — `:services` is not even a dependency yet |
| AI bots | **DONE, green** | the Kotlin bot port is merged to `main` — `BotTier` (EASY/MEDIUM/HARD/EXPERT) + `BotPersonality` (BALANCED/AGGRESSIVE/CONSERVATIVE/TRICKSTER) are wired and reused by Ranked (RD10/RD21). *(This row read "0%" before the port landed; corrected 2026-10-05.)* |
| Ads / IAP / analytics / audio / art / signing / store assets | **0%** | see §Dependencies & Risks |
| Voice chat / WebRTC / any audio-input path | **0%** | manifest declares **only** `INTERNET` — no `RECORD_AUDIO`, no `FOREGROUND_SERVICE`; no WebRTC dep anywhere in `native/` |

The critical path is **not** "write the services" — they exist. It's **wiring `:services` into `:app`** and everything platform-facing after.

**2026-10-06 GM amendment — one row is missing from this table on purpose:** there is no GameType / Calculation-Mode row because **none of it is built**. The engine has one fixed 18-round structure (`Session.kt:58`, `Bidding.kt:103-105`), Classic is implemented but unreachable (`Scoring.kt:100-141` with zero production callers), and `MatchDoc` carries neither `gameType` nor `scoringMode`. That gap is epic **E7 (S65–S69)** above, and it is now on the critical path **ahead of E6b**: S63's settlement cannot apply GM6's ×0.5 to a match document that has no `gameType`.

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
| 6 | Backend / API | **Amended 2026-10-05 (RD11):** the frozen `firestore.rules` + existing JS services remain the Room backend, but Ranked now adds a **lightweight Cloud Functions authority layer** — settlement, RP, placement scoring, matchmaking pool resolution, season reset, leaderboard ordering. Still **no dedicated always-on game server** (D3 amended below); the functions are invoked per-match, not always-on. `firestore.rules` itself stays untouched |
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
| Full Arabic retrofit (S41) | `values-ar` for every screen + migrate the 8 hardcoded-EN screens to `stringResource` + native-speaker review + RTL verification with S21 | S12 ships EN infra only (i18n decision 2026-09-26); S41 lands in the S24 window, in-v1, blocking production | M | 12 |
| Audio | `SoundManager` (SoundPool) honoring the existing persisted `soundEnabled` toggle; assets: card place, trick win, round score, match win, tap | Zero assets exist today; asset sourcing/licensing is the dependency | M | 14 |
| Compose animations | Card-play transition, trick collection sweep, winner highlight | Must never gate an intent dispatch | M | 12 |
| AdMob + UMP consent | Banner in **Lobby/Standings only — never in-play**; UMP request must complete before any ad load (EU/UK/CA) | Mis-configured consent is a **policy** problem, not just engineering | L | 20 |
| RevenueCat + "Remove Ads" | Free tier ($2,500 MTR free, then 1% — verified); one-time non-consumable | Fits D3 (no server) exactly; add a card before crossing the threshold | M | 14 |
| Crashlytics | Near-zero cost on existing Firebase BOM; the only way a 1-engineer launch sees field crashes | Must-have, not optional | S | 6 |
| Store listing + policy | Privacy policy URL + hosting (the D4 web shrink provides it), IARC questionnaire, data-safety form **consistent with ad declarations**, EN+AR screenshots | Draft exists at `docs/release/play-listing.md`; content-rating must avoid a simulated-gambling flag on "bid" vocabulary | L | 18 |
| **Platform subtotal** | | | | **150** |

### D. QA / verification

| Feature | Description | Technical Work | Complexity | Hours |
|---|---|---|---|---|
| Instrumented 4-client emulator suite | `androidTest` in `:services`: four real `MatchService` instances in one emulator vs the host Firestore emulator — scripted full match: startMatch → bids → 52 cards → archive+advance race → extend → endMatch → rematch | `androidTestImplementation` + `testInstrumentationRunner` are **already configured**; needs a CI emulator job + the test itself. **First time real Kotlin hits real rules.** Assert *convergence*, not single-winner determinism (emulator has zero tx retry) | L | 30 |
| Online manual QA + device matrix | minSdk 26 → modern API-36 device; RTL layout pass for Arabic | | M | 16 |
| **QA subtotal** | | | | **46** |

### F. Ranked progression system (owner amendment, 2026-10-05 — RD1–RD28)

**Status: MVP and launch-blocking (RD25).** This is the single largest scope addition since voice. It is **not** a UI-only feature: it adds a data model, a backend authority layer, a progression engine, matchmaking, seasons, and a leaderboard. Verified against `origin/main` before writing — **none of it exists in the repo today** (no rank/RP/tier/division fields, no `mode` on `MatchDoc`, no matchmaking, no seasons, no leaderboard code, no Vote Kick, no in-match Koz).

What **does** exist and is reused, not replaced: the Kotlin bot engine (`BotTier` EASY/MEDIUM/HARD/EXPERT, `BotPersonality` BALANCED/AGGRESSIVE/CONSERVATIVE/TRICKSTER), the pure rules engine (re-used by settlement), the converged multi-client action history that reload-safe replay already depends on, and the frozen `firestore.rules` deny-list on progression fields (which is *why* the client cannot self-award RP).

| Area | Scope |
|---|---|
| **F1 Rank data model** | 19-rank ladder enum (6×3 + King), Arabic title resources, RP fields, season fields, Highest Rank, placement state, leaderboard doc |
| **F2 RP engine** | Dynamic calculation from RD4 inputs; threshold table as a tunable constant source; RP ≥ 0; mixed-tier asymmetry (RD5); ×2 private cap (RD6) |
| **F3 Placement** | Once-per-account flag; 3 scripted bot matches (Easy+Med / Med+Hard / Hard+Expert); 10/20/70 weighting; Platinum ceiling; division lower-bound start |
| **F4 Settlement** | Cloud Functions: correct-and-settle, idempotency, auth, retry resistance, RP application; ownership-constrained/immutable action evidence |
| **F5 Matchmaking** | Server-side eligible-pool derivation (own tier or one below); queue/search states |
| **F6 Seasons + leaderboard** | 3-month season, two-step reset (King → Royal I), career stats untouched, Highest Rank preserved; server-authoritative season-isolated ordering |
| **F7 Statistics** | The 9 career stats; long-press short stats; Placement excluded |
| **F8 Mode model** | `mode` = ROOM \| RANKED only; private Ranked is RANKED + access flag, not a third mode |

**See the story table (§E6b, S42–S64) for the hour breakdown, and the appendix essay for the security boundary.**

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
| Push-to-talk UI | Hold-to-talk button (press = unmute+transmit, release = mute); **muted is the default and the resting state**; per-seat mic indicator showing who is speaking / who is unreachable; "unreachable" state for the STUN-only failure case | Compose. Voice follows V2/RD22 exactly: room-code rooms **and matches started from them** carry it; it must be `mode`-gated so it can never surface in a Ranked match (**Ranked exists in v1 now — RD25 — so the gate is enforced against a real `mode` field, S43, not a hypothetical**). Voice is structurally absent from Ranked; Vote Kick's Ranked-only rule (RD18) uses the same `mode` key in the opposite direction | M | 16 |
| Per-player mute + host mute-all | Any player can mute any other player locally (client-side audio-track disable — no need for a server round-trip); host gets a "mute all" if recommended | Local track `setEnabled(false)` per remote stream — cheap and idempotent | S | 8 |
| `RECORD_AUDIO` permission flow | Rationale UI (EN + **AR**), `shouldShowRequestPermissionRationale` handling, denied → graceful degradation (text/preset chat fallback, voice controls hidden, no crash), limited-access on API-31+; headset/Bluetooth routing (`AudioManager` `setMode`/`startBluetoothSco`) | minSdk 26 → targetSdk 36 span means handling both the legacy and the API-31+ one-time/limited models | M | 14 |
| Audio focus + echo handling | Request transient audio focus while PTT is active, abandon on release; duck the game SFX while someone speaks; AEC on speaker (the echo hazard is speakerphone, not headphones — see R16) | Coordinates with §C Audio (`SoundManager`) — the two must not fight over focus | M | 12 |
| Interruption handling | Incoming GSM call → auto-mute + release focus; app backgrounded → leave voice entirely (V-constraint: no background transmission); phone-state + lifecycle observers | **No foreground service** — voice lives only while the room screen is foregrounded. This is a deliberate scope cap, not an omission | M | 14 |
| Voice QA tier | Four real devices in one room over a real Wi-Fi/LTE mix incl. **one deliberate symmetric-NAT case**; permission-denied path; speaker-vs-headphone echo pass | Extends the §D device matrix; the symmetric-NAT case is the one that predicts production failure rate | M | 16 |
| **Voice subtotal** | | | | **158** |

**Voice total: 158 hours** (in the Full version only — excluded from MVP; see §4).

**Ranked total: 388 hours** (MVP and launch-blocking — RD25; see §F and the E6b story table).
**GameType & Calculation total: 48 hours** (E7, S65–S69 + docs — see the 2026-10-06 amendment above; a **prerequisite** for E6b, since S43/S47/S56 consume `gameType`).

**Feature total: 434 + 388 (Ranked) + 48 (GM) + 158 (voice) = 1,028 hours.** Adding the 12h voice store/policy deltas (§3) gives **1,040**; plus 40 PM = **1,080** — the number every downstream section uses. *(Before the 2026-10-05 Ranked amendment this was 592 / 604 / 644; before the 2026-10-06 GM amendment it was 980 / 992 / 1,032.)*

---

# 3. Development Estimation by Discipline

| Discipline | Hours | Why this amount |
|---|---|---|
| Game development (AI bot port) | 142 | ~1,600 lines of TS across 5 files, but it is **not** a line-by-line port: `cardRules` is replaced by existing `Table.kt` functions (`legalCards`/`cardValue`/`trickWinner`) — that saves time — while the `seenCards` accumulator and intent-emission layer are genuinely new. Heaviest single item is making bidding emit `BiddingIntent` per sub-phase, because the TS returns a raw number and the engine's `canSubmit` is the real legality gate. |
| Android development / integration | 96 + 158 = 254 | The services already exist; this is wiring + 2 screens + one ViewModel + reconnect. Priced as integration, not greenfield. The `OnlineMatchViewModel` is the hard part (callback → StateFlow, one GameSession, idempotent replay). The +158 is the voice line (§E): WebRTC engine, RTDB signaling, PTT lifecycle and UI, permission and audio-focus plumbing — all of it Android-side, none of it in the engine. |
| Platform / release / monetization | 150 | Front-loaded by the toolchain spike (24h) because API-36 is non-negotiable and the Kotlin-version decision is genuinely uncertain. Audio/art/res are priced minimal — this is a card game, not a 3D title. Excludes the voice store/policy deltas, which are broken out below. Includes the full Arabic retrofit S41 (+12) — EN infra lands with S12, AR values with S41. |
| **Voice — store & policy deltas** | **12** | Mic-permission justification copy, data-safety audio declaration (**must** say "no collection, no storage, transmitted peer-to-peer"), one privacy-policy sentence on voice, EN+AR strings. Cheap in hours, expensive in policy risk if skipped (R10/R18/R19). |
| UI/UX | 42 + 16 = 58 | *(inside the above)* Choose Level 14 + Lobby 16 + Room 14 — reuse the existing gold-on-dark theme; no new design system. +16 for the PTT control + per-seat mic indicators (§E). |
| Art / animation | 26 | 14 audio + 12 animation. No 2D/3D artist needed at v1 scope: cards render as glyphs today and that is acceptable for launch. |
| Sound | 14 | Included above (5 SFX + SoundManager). |
| Backend development | **388** *(Ranked only)* | **Amended 2026-10-05 (RD11):** the Ranked authority layer — `functions/` module, correct-and-settle, idempotent RP application, server-side validation, placement, matchmaking pool, seasons, leaderboard. Deliberately lightweight: Cloud Functions invoked per-match, not an always-on server. `firestore.rules` stays frozen; the functions are the only legitimate Ranked write path. See §F and the appendix essay. |
| QA / testing | 46 + 16 = 62 | JVM coverage is already excellent (12 suites, incl. the P1-3 pins). The gap is exclusively the instrumented/emulator tier. The +16 is the voice QA tier (§E), which is *counted inside the Android row above* and restated here only so QA isn't misread as shortchanged — it is not double-counted. |
| Project management | 40 | ~7% — owner/PM overhead, store-form correspondence, account activation chasing, release coordination, **voice policy correspondence** (mic-justification + data-safety answers). |
| **Total** | **434 + 388 (Ranked) + 48 (GM) + 158 (voice) + 12 (voice-policy) + 40 (PM) = 1,080** | *(pre-contingency; was 644 before the 2026-10-05 Ranked amendment and 1,032 before the 2026-10-06 GM amendment)* |

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
| **6b Ranked authority + RP + placement** ⏱ | **6.5 wk** | **5b, 4a (bot engine reused)** | `functions/` module, `mode` field, correct-and-settle, RP engine, placement (S42–S54) |
| **6c Matchmaking + private Ranked** ⏱ | **3 wk** | **6b** | Server-controlled pool, private/password cross-tier (S56–S58) |
| **6d Seasons + leaderboard + Vote Kick + timeout tracking** ⏱ | **5 wk** | **6b, 6c** | Two-step reset, King → Royal I, season-isolated board, Ranked-only Vote Kick, 15-timeout removal (S59–S61, S64) |
| 7 Monetization (AdMob + UMP + RevenueCat) | 1.5 wk | 0b, accounts active | Banner + Remove Ads, test purchases |
| 12 Store prep (res/, strings, policy, listing, Crashlytics) | 1.5 wk | 0b | Store-ready listing, signed AAB, full AR strings (S41) |
| 8b Audio + animations (parallel throughout) | 1 wk | — | SFX + polish |
| 13 Closed track → staged rollout | 1 wk | all | Production v1 |

### MVP Timeline — 20 weeks (~4.6 months) — amended 2026-10-05

**The MVP now includes Ranked (RD25).** Ranked is launch-blocking, so it cannot be deferred; voice still is.

The minimum to launch: **AI-only + online + Ranked, no voice, monetization as a rapid v1.0.1 follow-up.**
0b (1.5) + 4a (2.5) + 4b (1.5) + 5a (1.5) + 5b (2) + 6b (6.5) + 6c (3) + 6d (5) + store basics (1) + stabilization (1).
Rationale: neither AI nor online depends on ads — and neither depends on voice. **Voice is deliberately outside the MVP:** it adds 170h, it carries the only two new hardware-policy surfaces in the project (mic permission + Play audio disclosure), and its worst-case failure mode (echo, or a NAT pair that can't connect) is a *support burden*, not a blocker. If the AdMob 14-day clock or a UMP edge case slips, this is the line that holds. Voice (2.5) + voice QA (0.5) then land as v1.0.1/v1.1 alongside TURN (see §E).

*(Pre-amendment MVP was 9.5 weeks / ~340h; +388h of launch-blocking Ranked at the same cadence lands at ≈20 weeks.)*

### Full Version Timeline — 29 weeks (~6.7 months) — amended 2026-10-05

Everything above + voice (2.5) + voice device matrix (0.5) + instrumented QA (1.5) + monetization (1.5) + store prep overlap + audio/polish (1) + closed-track rollout (1). Calendar time exceeds the raw hours because of the **AdMob 14-day closed-testing clock** — an immovable external dependency. Voice is the single largest week-adder after the Ranked block, and it is sequenced *after* online works, because voice membership is derived from room membership and must never be able to drift from it. Full Arabic (S41) rides inside store prep — +12h absorbed without moving the 29-week label.

**Ranked adds ~14.5 weeks** (6b 6.5 + 6c 3 + 6d 5) and sits on the critical path: `mode` (S43) gates Vote Kick, settlement, and Ranked statistics, so it cannot start after online — it starts *with* it.

---

# 5. Team Requirements

**Operating model: 1 engineer + AI coding agents.** This is not a shortcut — it's the observed cadence (Phases 0–10 at ~1/day). No roles are added to make the project look bigger.

| Role | Required? | Involvement | Hours |
|---|---|---|---|
| **Lead engineer (owner)** | **Yes — full-time** | Architecture, all Kotlin, CI, store submission | 400 |
| AI coding agents (subagents) | Yes — already in the operating model | Parallel worktrees: bot port while the toolchain spike runs | (included, not extra) |
| QA / manual tester | **Part-time, only in the final 3 weeks** | Device matrix, RTL pass, online 4-human playthroughs, **4-device voice room incl. one symmetric-NAT case**, ad/IAP verification | 16 (folded into §604) |
| 2D/3D artist, animator | **Not required for v1** | Cards render as glyphs; launcher icon from an icon generator is acceptable at v1 | 0 |
| Sound designer | **Not required** | License 5 SFX from a royalty-free library (voice uses the WebRTC library's own AEC/NS, not custom DSP work) | 0 |
| Backend developer | **Not required — but the plan now includes backend *work*** | **Amended 2026-10-05 (RD11):** the Ranked authority layer is 388h of Cloud Functions + Firestore, but it is written by the lead engineer in the existing 1-engineer model — it is Kotlin/TS-adjacent Functions over the project's own data model, not a distributed-systems hire. D3 still means no always-on server | 0 (headcount; the hours are in §F) |
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
| **R23** | **Arabic review bottleneck.** `values-ar` needs a native-speaker review before submission, and that reviewer is an external person on no sprint clock | Store submission waits on one human's inbox | Draft `values-ar` early with AI assistance (EN infra lands with S12, so strings are reviewable long before S41 starts); book the reviewer when S24 opens, not when S41 needs sign-off. **The Ranked tier titles (مبتدئ / لاعب / معلم / وزير / أمير / سلطان / ملك) are product identity — put them in the reviewer's first batch, they are the most visible AR strings in the app** |
| **R24** | **Cloud Functions cold-start latency on settlement.** The player sits on the Ranked result screen (S57) while the settlement callable warms up | A "settling…" wait that feels broken at the most satisfying moment in the game | UI shows an explicit settling state, never a blank or a spinner that implies failure. min-instances only if cost-justified — RD16 says monitor first, do not pre-buy capacity |
| **R25** | **The MVP security boundary (RD14) is a compromise, not full authority.** In-play state stays client-converged; the server validates at settlement, not realtime | A determined cheater who controls all four clients can still produce a plausible-looking converged history | **This residual is accepted deliberately (RD16), named in the appendix essay, and logged in the RP audit ledger (S62).** Upgrade path: stronger realtime authority when cheating, scale, revenue, or competitive pressure justifies it. Do not silently oversell the boundary |
| **R26** | **RD26 thresholds are SET (2026-10-05) but unvalidated by play.** All 19 rank lower-bounds are now closed constants — but no real player has climbed the ladder yet | Progression feel may still move promotion/demotion cadence once a playtest grinds it; the numbers were set by judgement, not by data | **S46 isolates the thresholds into a tunable constant source**, so numbers change without redesign. The UI binds the ladder *structure*, not the numbers. The residual risk is now *tuning*, not *absence* — a v1.1 balance pass is expected, not a sign the decision was wrong |
| **R27** | **Season reset is a one-way data migration.** Two-step demotion direction (Gold I → Gold III, never the reverse), ladder floor, Highest Rank preservation, King → Royal I — all irreversible once run | A botched reset corrupts every player's rank at once and cannot be rolled back without a backup | S59 + a **mandatory emulator dry-run against a seeded production-shaped dataset before the first real season**. The direction is the trap: I > II > III, so "down two" from Gold I is Gold III |
| **R28** | **Mixed-tier RP feels punitive to the higher-tier loser.** RD5 multiplies their loss because they lost to a lower-tier opponent — intentional, but it *reads* as unfair if unexplained | Negative reviews from Gold players losing to Silver | The Ranked result screen (S57) shows the delta and the reason; RD5 is owner-approved, so the fix is communication, not a formula change. Tunable in S47 |
| **R29** | **Cloud Functions cost at scale.** Settlement is one invocation per match — cheap at launch, unbounded at growth | A surprise bill if the game succeeds | RD16 governs: monitor, do not pre-build. The RP audit ledger (S62) is the natural usage signal. Spark plan covers the whole v1 forecast |

---

# 7. Milestones

| Milestone | "Done" means |
|---|---|
| **M1 — Toolchain unblocked** | API-36 build compiles; gradle wrapper committed; signed release build initializes the **real** Firebase project; a clean repo clone builds locally |
| **M2 — AI playable** | A full 18-round match vs 3 bots completes; all 4 tiers observably differ; bot-vs-bot JVM smoke test green in CI; no `Math.random` in any bot path |
| **M3 — Online playable** | 4 humans create/join by code, play a full 18-round match to FinalStandings, and rematch — all through the real `MatchService` |
| **M4 — Reconnect works** | Kill the app mid-match, relaunch: returns to the exact trick via `currentMatchId` + log replay, no double-resolved history (P1-3 pin holds) |
| **M4b — Voice works in a real room** | **Four physical devices** (not emulators) in one room-code room: any player holds-to-talk and the other three hear them; **nobody is transmitting at room join** (muted-by-default verified by packet inspection, not just UI state); backgrounding the app leaves voice and nothing keeps transmitting; a player who denies the mic still plays and still sees others' indicators |
| **M4c — Voice failure modes are honest** | At least one **deliberate symmetric-NAT pairing** in the matrix: the affected seat shows "unreachable", the room continues, and the round completes. Also: voice is provably **gated to the casual-room path only** — a grep for any WebRTC init, `RECORD_AUDIO` prompt, or PTT button outside the room-code/room screen returns **zero**, and **voice is provably absent from any `mode == RANKED` match** (RD22). Ranked now exists in v1 (RD25), so this is verified against the real `mode` field (S43), not as a hypothetical. |
| **M5 — Instrumented gate green** | The 4-client emulator suite asserts convergence on the archive/advance race and endMatch in CI |
| **M6 — Monetization integrated** | UMP consent completes before any ad loads; banner shows in Lobby/Standings only; "Remove Ads" test purchase succeeds via RevenueCat |
| **M7 — Store-ready** | Privacy policy live, IARC rating set, data-safety form submitted and consistent with ad declarations, EN+AR screenshots, full AR strings live on every screen (S41), signed AAB on the **closed track** |
| **M8 — Production v1** | Staged rollout started; Crashlytics receiving; no P0 crashes for 72h at 5% |
| **M9 — Ranked settlement is authoritative** | The `functions/` module is live on the emulator; a **forged** `finalScores` is **corrected**, not accepted; settlement is idempotent across replayed calls; RP never goes negative |
| **M10 — The Ranked loop is playable** | Placement (3 matches, 10/20/70, Platinum ceiling) → matchmaking (own tier or one below, no opt-up) → full match → Ranked result with RP delta, end to end |
| **M11 — Ranked launch gate** | Seasons (two-step reset, King → Royal I, Highest Rank preserved), Vote Kick (Ranked-only, Round-7 gate, 2-of-3, 5-round cooldown, no rejoin), the 9 statistics, the 15-timeout automatic removal, and the season-isolated leaderboard all green. **Ranked is launch-blocking — v1 does not ship without this** |

---

# 8. Estimation Scenarios

| Scenario | Hours | Timeline | Conditions |
|---|---|---|---|
| **Conservative** | 1,080 + 25% = **1,350** | **~35 weeks** | Toolchain spike forces Kotlin 2.x migration; AdClock slips; bot balancing needs real playtest iteration; instrumented suite reveals a rules-fidelity bug requiring a client rework; **voice exceeds its 40h XL row** (ICE debugging on real devices is the classic 2x line); **RD26 threshold tuning takes a full playtest cycle** (R26); season-reset dry-run finds a migration defect (R27) |
| **Realistic** | 1,080 + 15% = **1,242** | **~30 weeks** | Spike stays on Kotlin 1.9.25; bots port cleanly with the SimPort seam; online wiring behaves as the JS reference did; voice mesh connects on the first 4-device attempt using a Maven-Central WebRTC republish; **the settlement layer lands on the emulator without a rules revision** (RD11's promise holds). **Recommended.** |
| **Aggressive** | 1,080 − 10% (skip animations, minimal audio, Crashlytics only, **defer voice to v1.1**) = **972** | **~27 weeks** | Requires the toolchain spike to land clean in week 1, zero rules-fidelity surprises, and the AdMob account already activated. Voice is the cleanest single thing to cut: 170h (158 voice + 12 policy) with no gameplay dependency — **but neither Ranked (RD25) nor the GameType substrate (E7, a Ranked prerequisite) can be cut, so this scenario's floor is higher than the old one's.** Cutting it maps this scenario onto the 910h no-voice baseline. Only viable if accounts are started **today** |

> **Voice is still the swing item — but it is no longer the only one.** It remains the only scope block that is simultaneously large (170h), non-blocking for launch, and carrying two policy surfaces. **Ranked (388h) is the new immovable block: RD25 makes it launch-blocking, so it moves the floor of every scenario.** The spread between Realistic and Aggressive is still mostly "voice or not"; the spread between this amendment and the previous plan is entirely "Ranked or not."

> **Arithmetic, stated rather than hidden (2026-10-05 amendment):** all figures are recomputed from the **388h Ranked subtotal** (S42–S64, §E6b). Pre-amendment this table was 805 / 741 / 427 against 644. The old "defer voice → 474h / 13 weeks" baseline becomes **862h / ~24 weeks**, because Ranked is added to it. Do not carry the old numbers forward.

> **Arithmetic, stated rather than hidden (2026-10-06 GM amendment):** all figures are recomputed again from the **48h E7 subtotal** (S65–S69 + docs, §E7 above), which lands **before** E6b because Ranked must be GameType-aware from S63's first line. The 1,032 / 1,187 / 1,290 / 862 figures above become **1,080 / 1,242 / 1,350 / 910**. Week counts scale proportionally and are rounded — the contingency percentage, not the week count, is the number with real precision. Do not carry the 2026-10-05 figures forward.

---

# 9. Contingency

| Contingency bucket | % of 1,080 | Hours | Why this rate |
|---|---|---|---|
| Unknown requirements | 4% | 43 | **Ranked is now the largest undocumented area** (its thresholds are set but unplayed — R26) — this bucket grows with it. AI-bot scope was the previous driver (R13); voice is pinned by V1–V5. **The GM amendment adds a second unplayed surface: MINI's pacing (10 rounds, Quick from 6) has never been played by anyone** |
| Bugs | 4% | 43 | Bot tier balance always needs more iterations than planned; **RP tuning will need a playtest cycle or two** (R26); **MINI's one-extension cap is a new rule boundary with a rules-enforcement counterpart** (E7/S67) |
| Integration problems | 4% | 43 | The `:app`↔`:services` wiring + instrumented tier is untested territory (R8), **plus a first WebRTC integration** (R15/R16/R21), **plus a first Cloud Functions settlement layer** (R24 cold starts, R25 boundary) |
| Rework | 2% | 22 | Toolchain decision may force a Compose-compiler migration; the WebRTC artifact choice may need swapping (R21); **a season-reset defect found in dry-run is rework by definition** (R27) |
| Testing | 1% | 11 | Device-matrix surprises, especially RTL Arabic layouts and the speakerphone echo pass |
| **Total contingency** | **15%** | **162** | **Conservative = 25% (270h) if the toolchain spike goes badly, voice ICE debugging runs long, or RD26 needs more than one tuning cycle** |

- **Before contingency:** **1,080 hours**
- **After contingency (realistic):** **1,242 hours ≈ 30 weeks**
- **After contingency (conservative):** **1,350 hours ≈ 35 weeks**
- **If voice is deferred to v1.1:** **1,080 − 170 = 910 hours ≈ 25 weeks** — the pre-voice baseline, **now with Ranked included** (RD25 makes Ranked uncuttable)
- *(Pre-amendment figures, for traceability only — do not carry forward: 644 / 741 / 805 / 474 / 1,032 / 1,187.)*

---

# 10. Final Executive Summary

**Project scope.** Ship "Estemshan," a 4-player Egyptian trick-taking card game, as a native Android (Kotlin/Compose) app with three modes: offline vs AI bots (4 difficulty tiers + personalities), online matches with friends via room code, and offline hot-seat — plus **two selectable game options on every match** (**Game Type**: Full 18 rounds or Mini 10 rounds, Quick Rounds from 14 or 6; **Calculation**: Normal or Classic — GM1/GM4), **a full Ranked progression system** (19-rank ladder, dynamic RP, placement test, server-controlled matchmaking, 3-month seasons, seasonal leaderboard — MVP and launch-blocking per RD25, **open to both Game Types with Mini's RP delta at 50% of Full's — GM5/GM6**), and **push-to-talk voice chat in casual rooms only** (muted by default, max 4 speakers, **never in Ranked** — RD22), fully localized in English and Arabic (EN `strings.xml` infra from S12, full `values-ar` retrofit in S41). Monetized with a single banner ad plus a one-time "Remove Ads" purchase.

**Where we're starting from.** The game engines, scoring, and online *service* layer are **already built and green on `main`** — including the hardest piece, reload-safe replay — and **the AI bot port is merged and wired** (`BotTier` + `BotPersonality` are reusable by Ranked placement and timeouts). Roughly **half the native v1 is done**. What's left is: wiring the online services into the app, the Ranked authority + progression layer, voice chat, and everything platform-facing.

**MVP scope.** AI play + online multiplayer + **Ranked (launch-blocking)**, with monetization following as a rapid v1.0.1. Neither gameplay mode depends on ads — and neither depends on voice. **Voice is Full-version scope, deliberately outside the MVP. Ranked is not: RD25 puts it in the MVP.**

**Total estimated hours.** **1,080** before contingency (434 base + **388 Ranked** + **48 GameType/Calculation (E7)** + 158 voice + 12 voice-policy deltas + 40 PM), **~1,242 after** (15%). *(Pre-Ranked: 644 / ~741; pre-GM: 1,032 / ~1,187.)*

**Estimated timeline.** **29 weeks** realistic (MVP in ~20). Conservative 34 weeks if the Android toolchain forces a Kotlin migration, voice ICE debugging runs long, or RD26 threshold tuning needs extra playtest cycles. **Deferring voice to v1.1 gives a 24-week / 862-hour baseline — but Ranked stays, so the floor no longer drops to 13 weeks.**

**Required team.** **One engineer (the owner) + AI coding agents**, plus part-time QA for the final 3 weeks (including a 4-physical-device voice room). No artist, animator, sound designer, or backend *hire* needed at v1 scope — **but the plan now contains 388h of backend work**, written by that one engineer as lightweight Cloud Functions (RD11), not as a server.

**Major risks.** (1) The app currently targets API 34 — Google requires API 36 as of Aug 31, 2026, so the toolchain must be upgraded before anything can ship. (2) The AdMob 14-day closed-testing clock is the one immovable external deadline. (3) The AI bot files are coupled to the "deferred" Monte-Carlo module more tightly than expected. (4) The frozen security rules make true player presence impossible — by design, not by oversight. (5) **Voice cannot reach every player STUN-only: 5–15% of room-pairs sit behind symmetric NATs and will show "unreachable" — TURN is a v1.1, $0-effort-only fix, not a launch blocker, and the UX must say so rather than spin.** (6) The official WebRTC artifact is no longer on Google's Maven (Bintray sunset) — pin a Maven Central republish deliberately. **(7) The MVP security boundary is a compromise: Cloud Functions validate and settle, but in-play state stays client-converged — a fully colluding table is the known residual (R25, RD14). (8) The 19 RP thresholds are set (RD26, closed 2026-10-05) but unplayed — progression feel is a tuning risk for the first Ranked playtest, not a missing-input risk (R26).**

**Main assumptions.** The 1-engineer + AI-agent cadence observed over Phases 0–10 continues; the AdMob/Play/RevenueCat accounts are activated immediately; bot balancing is "good enough," not tournament-grade; EN + Arabic only; voice is acceptable to players as PTT-only with no open mic; **RD26's RP thresholds are set (2026-10-05) and are treated as v1 values, to be re-tuned from playtest data rather than from judgement**.

**What is NOT included.** EXPERT Monte-Carlo bots and adaptive difficulty (post-launch); **voice in Ranked — never (RD22)**; shop and missions (no economy to sell into — **seasons ARE in scope, RD24**); rewarded video (nothing to reward yet); true presence/abandonment (blocked by frozen rules); custom analytics events beyond crash reporting; full i18n beyond EN/AR; **a dedicated always-on game server (D3 amended — lightweight Cloud Functions are in, a server is not, RD11)**; any **Firestore rules revision** — `firestore.rules` stays frozen and byte-identical, the Functions are the only legitimate Ranked write path; **no TURN server in v1** (STUN-only, symmetric-NAT limitation accepted and disclosed, coturn on a free VM reserved for v1.1); **no recording, transcription, or server-side audio storage of any kind**; no open-mic / always-on voice; no foreground-service background voice; **no player-facing Ranked match history for MVP (RD23 — the profile carries the 9 career statistics instead)**; **no separate seasonal trophy system (RD24)**. Also excluded: re-estimating the ~460 hours of work already merged to `main`.

**One decision for management.** Start the AdMob/Play Console/RevenueCat account activations **today** — they're external clocks, and they — not the code — determine the launch date. **RD26's 19 RP threshold numbers are now CLOSED (2026-10-05) — the last owner input on the Ranked critical path is in, and nothing on that path is waiting on a decision anymore.**

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
| | S41 Full Arabic retrofit: `values-ar` + migrate 8 hardcoded screens + native-speaker review + RTL verification | 12 | S12, S24 |
| | S25 Crashlytics | 6 | S3 |
| | S26 Privacy policy hosted + data-safety + IARC + listing | 18 | S25 |
| **E8b Polish** | S27 SoundManager + 5 SFX behind existing toggle | 14 | — |
| | S28 Compose animations (card play, trick sweep, winner) | 12 | — |
| **E13 Release** | S29 Closed track + staged rollout + 72h Crashlytics watch | 8 | all |
| **E6b Ranked** ⏱ | S42 `functions/` module: deploy target + emulator wiring + callable-auth + idempotency guard — lightweight (RD11/RD16) | 20 | S4 |
| | S43 `mode` field on `MatchDoc` — **exactly `ROOM` / `RANKED`** (RD28) + separate private/password access flag + migration of in-memory mode to persisted authority; **consumes `gameType`/`scoringMode` on the Ranked path (written by E7/S66 — not re-estimated here)** | 10 | S42 |
| | S44 Ranked profile model: tier, division, RP, seasonId, highestRank, placementState, 9 stats | 14 | S43 |
| | S45 Rank ladder definitions + Arabic title resources (EN `values`, AR `values-ar`) + rank formatting | 10 | S44 |
| | S46 RP threshold table as tunable constants (**RD26 values closed 2026-10-05**) + rank↔RP conversion | 10 | S44 |
| | S47 RP engine: dynamic gain/loss from RD4 inputs; tunable; unit-testable in isolation; **MINI's ×0.5 applied once to the final delta, after every RD4/RD5 input (GM6); rounding per the closed OPEN-3 — `kotlin.math.round`, ties away from zero (+15→+8, −15→−8), with the `Math.round` asymmetry test mandatory; FULL's 1.0 multiplier asserted as a no-op** | 24 | S46 |
| | S48 Mixed-tier asymmetry (RD5) + Private ×2 cap (RD6) | 14 | S47 |
| | S49 Demotion + one-match protection (RD7); King ceiling + leaderboard-only overflow (RD3) | 12 | S47 |
| | S50 Settlement function: authoritative result recompute + correct-and-settle (RD12) | 26 | S42, S43 |
| | S51 Idempotent RP application + retry/duplicate resistance + auth identity verify (RD13) | 16 | S50 |
| | S52 Server-side validation of Ranked-critical state/actions (**MVP security boundary, RD14**) | 30 | S50 |
| | S53 Placement service: 3 scripted matches (Easy+Med / Med+Hard / Hard+Expert), system-controlled bots | 16 | S44 |
| | S54 Placement scoring: engine-derived signals, 10/20/70 weights, Platinum ceiling, division lower-bound start | 20 | S53, S47 |
| | S55 Ranked statistics accumulator: the 9 values, Public+Private, Placement excluded | 14 | S51, S44 |
| | S56 Matchmaking: server-side eligible-pool derivation (own tier / one below), queue, search lifecycle; **`gameType` carried on the search, orthogonal to the tier pool (GM5 — the pool rule never widens or narrows for Mini)** | 30 | S44 |
| | S57 Matchmaking anti-abuse: no client tier selection, no opt-up, pool authority (RD9) | 12 | S56 |
| | S58 Private/Password Ranked: cross-tier allowed, password gate, Ranked integrity retained | 14 | S50, S48 |
| | S59 Seasons: 3-month cycle, season ID, two-step reset with ladder floor, **King → Royal I**, Highest Rank preserved | 20 | S44, S49 |
| | S60 Vote Kick Ranked-only enforcement by `mode` + Round-7-complete gate + unique-Koz requirement + **target frozen for the vote duration (OPEN-2)** (RD17/18) | 20 | S43 |
| | S61 Timeout/inactivity separation: MEDIUM engine on timeout, configured bot on leave, 15-timeout counter → **automatic removal (distinct from Vote Kick)** | 16 | S43 |
| | S62 RP audit ledger (internal) + observability hooks | 10 | S51 |
| | S63 Abuse/edge-case coverage: forged results, replayed settlements, cross-tier injection, tie edge cases | 12 | S61 |
| | S64 Seasonal Ranked Leaderboard: server-authoritative ordering (position, player, Tier/Rank, RP, season), King ranked by RP above the King lower bound, current player highlighted, season isolation/reset | 18 | S59 |
| | **E6b Ranked subtotal** | **388** | |
| **E7 GameType & Calculation** ⏱ | S65 `:engine` `GameType` enum (`baseRounds` / `firstFastRound` / `maxExtensions`); parameterize `isFastRound`, `fixedTrumpFor` (the `Math.floorMod` fix for the round-6 crash), `initFastRound`/`initNormalRound`, `computeRoundExtension`, `Session.gameType`; FULL golden regression + MINI ladder/cap tests | 10 | — |
| | S66 `:services` — `gameType`/`scoringMode` on `RoomDoc` + `MatchDoc` (absent ⇒ FULL/NORMAL, so old docs parse); `buildMatchFields` derives `maxRounds` from the type; `buildRematchMatchFields` inherits the type; `startMatch` propagates room config; `extendMatchRounds` gains the count cap returning `ALREADY_EXTENDED` + unit tests | 12 | S65 |
| | S67 `firestore.rules` — four function changes (room / match / rematch allowlists, type-derived `maxRounds`, Mini extension count cap), absent-means-FULL back-compat; emulator re-run + re-pin `firestore.rules.sha256` | 6 | S66 |
| | S68 S36 Create Game UI — the **Game Type** (Full / Mini) and **Calculation** (Normal / Classic) groups atop the RD21 trio, the existing `PickerRow` idiom, config state, strings; defaults FULL + NORMAL (GM7) | 8 | S66 |
| | S69 `OnlineMatchViewModel` + `QuickMatchViewModel` wiring — `classic` from the persisted mode (removing the two hard-coded `false`s), `escalationCap` on the quick-match path (pre-existing gap), MINI round seeding + tests | 8 | S65, S66 |
| | Documents — `CANONICAL_RULES.md` Amendment A2, `UI_UX_ROADMAP.md` amendments, architecture/spec notes | 4 | — |
| | **E7 GameType subtotal** | **48** | |
| | **PM overhead** | 40 | — |
| | **Contingency 15%** | 162 | — |
| | **TOTAL** | **1,252** | |

> Story-table arithmetic, stated rather than hidden. **Pre-amendment (for traceability):** the 30 non-voice stories summed to **444h** (the 29 pre-voice stories at **432h** — a +10h decomposition granularity, since S7 is sized 26 vs its feature row's 24 and S13 is split out separately — plus S41 at **12h**) against §2's 434h feature line. The 11 voice stories summed to exactly **170h**, matching §2/§3 (158 voice + 12 policy deltas). PM (40) + 15% contingency (97) landed the table at **751**, against the §9 headline of **741** — the 10h difference was precisely that story-vs-feature drift, absorbed by contingency.
>
> **After the 2026-10-05 Ranked amendment:** the 23 new **E6b** stories (S42–S64) sum to exactly **388h**, matching §2's Ranked feature line (§F) with zero drift this time — the ladder, settlement, and matchmaking stories decompose cleanly against the feature rows. The table now carries 444 + 388 = **832h** non-voice stories plus **170h** voice = **1,002h** of stories; PM (40) and 15% contingency (**155**, taken on the §3 pre-contingency total of 1,032 per this table's own convention — pre-amendment it was 97 on 644) land the table at **1,197**, against the §9 headline of **1,187**. The 10h gap is exactly the pre-amendment story-vs-feature drift, unchanged in kind: the non-voice stories sum to 832h against §2's 822h non-voice feature line (+10h of decomposition granularity), and contingency amplifies it by 1.15. Stories are the *execution* view; §9 is the *estimate* view. Nothing is rounded away silently.
>
> **After the 2026-10-06 GM amendment:** the 5 new **E7** stories (S65–S69, plus a 4h documents row) sum to exactly **48h**, matching §2's GM feature line with zero drift. The table now carries 832 + 48 = **880h** non-voice stories plus **170h** voice = **1,050h** of stories; PM (40) and 15% contingency (**162**, on the new pre-contingency total of 1,080) land the table at **1,252**, against the §9 headline of **1,242** — the same 10h story-vs-feature drift, unchanged in kind and size. E7 is placed **after** E6b in the table only because the table is ordered by story number; **the execution order is E7 → E6b**, since S43/S47/S56 all consume `gameType`.
>
> **Rank-King vs Match-King, once, because it is the single easiest thing to get wrong in this amendment:** Rank King is the account's competitive ceiling (19th rung, no divisions). Match King is whoever finishes first in one match. Koz is the unique current last place in one match — not a rank at all. The `#E8A33D` gold colour in the design tokens belongs to the **Match-King badge**; the **Gold tier** must not reuse it, or "Gold" collapses into "King" in the UI.

---

## Appendix (2026-10-05): Ranked — the lightweight authority compromise

**The question this section answers:** how does a low-cost MVP validate Ranked results without pretending Cloud Functions are a realtime game server? The owner's priorities are explicit (RD16): keep infra cost low, avoid enterprise-scale architecture, keep enough integrity that progression can't be *trivially* forged, and upgrade later when scale or cheating justifies it.

### Why not a dedicated game server

An always-on authoritative game server is the textbook answer and the wrong one *for this stage*. It is a permanent running cost, it needs capacity for peak concurrent matches, it needs a uptime/operations owner, and this is a card game with one engineer. **RD11 chooses Cloud Functions + Firestore instead**: the authority logic runs on invocation — once per match, at settlement — and costs nothing between matches. The frozen `firestore.rules` deny-list on progression fields already guarantees the client cannot write its own rank or RP; the Functions are the only legitimate write path into those fields.

### What the Functions actually buy (RD12/RD13), and what they do not (RD14)

**They buy:** correct-and-settle (the client's `finalScores` is recomputed, and a wrong claim is *corrected*, then settled — not blindly trusted, not merely rejected); idempotency (a match settles exactly once, replays and retries included); authenticated identity (the settling player is verified, and only their own action records are accepted); and server-owned invariants (`mode`, tier eligibility, placement weights, the 9-stat accounting, Vote Kick's Round-7 and unique-Koz gates).

**They do NOT buy:** realtime authority. The server does not adjudicate each bid or card as it happens. In-play state stays client-converged for the whole match and is only verified retrospectively, at settlement. **This is the MVP security boundary (RD14), and overstating it would be the main way this plan goes wrong.**

### The concrete validation mechanism

The mechanism is **server-validated Ranked actions plus deterministic verification at settlement** — reusing two things the repo already has:

1. **Ranked-critical actions converge to a shared Firestore document.** Bidding, estimates, and card plays are already written by all four clients to the same match document — that is precisely what the existing reload-safe replay depends on. The settlement function re-reads that **converged** document, not the client's claim, and treats the converged action history as the input of record.
2. **The rules engine is pure and already test-covered.** Settlement re-runs scoring over the converged action sequence to recompute the authoritative final placement and scores. A `finalScores` that disagrees is **corrected** (RD12) — the recomputed value is what settles.
3. **Server-owned invariants, checked at settlement.** `mode` authority (Ranked vs Room), unique-Koz eligibility for Vote Kick, Round-7 completion, tier eligibility per RD9, placement weights, the 9-stat accounting, and the idempotency key. These are properties the server derives from documents the client cannot forge, because `firestore.rules` already deny progression writes.
4. **Action evidence is ownership-constrained and immutable (RD13).** Each player's action records are written under that player's own identity; one client cannot rewrite another player's actions or fabricate another player's result. The converged sequence the server re-derives is append-only.

**What the server does not do:** it does not hold authoritative game state during play, and it does not intervene mid-match. This is the accepted residual, stated openly rather than buried.

### The four trust tiers, named

| Tier | What lives here | Mechanism |
|---|---|---|
| **Client — read-only post-settlement** | Match UI, rank display, statistics read, matchmaking search *request* | Never writes RP/rank/result. Enforced by the existing `firestore.rules` deny-list on progression fields |
| **Client — converged, transient** | In-match gameplay state: bidding, estimates, card plays | Written by all clients to the shared match document; convergent by design (reload-safe replay already relies on it). **The RD14 residual: not server-adjudicated in realtime.** Not untrusted — multi-client-converged, which makes single-client forgery visible |
| **Cloud Functions — authority, invoked once per match** | Settlement, RP, placement scoring, matchmaking pool resolution, season reset, leaderboard ordering, Vote Kick eligibility by `mode` | Re-reads the converged document, re-runs the pure engine, **corrects** a wrong `finalScores` (RD12), applies RP idempotently (RD13) |
| **Firestore — source of truth** | RankedProfile, Season, RP audit ledger, match `mode`, Leaderboard | Post-settlement the client's read of its own rank/RP/stats is the only path — there is no client write path to close |

### Why this is sufficient for launch, and what it costs you

**Sufficient because:** forging Ranked progression under this design requires forging the converged multi-client action history that all four players' devices agree on — materially harder than editing a score field, and detectable at settlement. Combined with the rules deny-list, the cheap attacks (self-awarding RP, suppressing a loss, double-settling, fabricating another player's result) are all closed.

**Costs you, knowingly (R25):** (a) a fully colluding table — four players who agree to fabricate a whole match — can still produce a plausible-looking converged history, because the server was not watching each trick; (b) subtle in-play exploits are caught only if they break convergence or an engine invariant, not in realtime; (c) settlement is retrospective, so a detected fraud can be *corrected* but not *prevented* mid-match. Each is accepted at MVP, logged in the RP audit ledger (S62), and revisited under the RD16 upgrade triggers.

### The upgrade path, and its triggers

Stronger **realtime** server authority — the server adjudicating each action as it happens — becomes the right answer when **any** of these fire: cheating becomes a meaningful problem in the field, player scale increases, revenue justifies higher infrastructure cost, or competitive-integrity requirements increase (esports-style play). The architecture is designed so that step is an *addition* — the settlement layer stays, and per-action validation is layered in front of it — not a rewrite. **That upgradeability is the reason "lightweight" is a deliberate choice here, not a shortcut.**

### The Rooms / Ranked trust split (RD15)

**Rooms** are the lower-security, cost-sensitive flow: no competitive RP progression, so no need for full Ranked authority. The existing frozen rules and client-converged play are sufficient — which is why Rooms ship unchanged and voice is allowed there.

**Ranked** is where the boundary applies: critical outcomes are protected, authority lives in the backend, and the four tiers above govern every write. **A Room match and a Ranked match use the same engine and the same table; they differ in authority, not in gameplay.** The `mode` field (S43) is the single key that separates them, and it gates Vote Kick (Ranked-only, RD18), settlement, and Ranked statistics.

**One consequence worth stating flatly:** this is an MVP compromise, not a claim of full server authority. If the plan is read as "Cloud Functions make cheating impossible," it has been misread — they make *trivial* forgery impossible and *hard* forgery detectable, at a cost of one function invocation per match.

---

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
6. **Field** — Crashlytics is the only way a one-engineer launch learns about field crashes; 72h at 5% rollout with no P0 before widening. For voice specifically, watch for a cluster of "unreachable" reports from one country/carrier — that's the symmetric-NAT rate exceeding the estimate and the trigger to pull coturn forward from v1.1. **For Ranked, watch the RP audit ledger (S62) for settlement anomalies — repeated correct-and-settle corrections from the same player or the same table is the signature of the R25 residual being exercised in the wild.**
7. **Ranked tier (new, M9–M11)** — designed now, run at the Phase 9 gate alongside the instrumented suite:
   - Forged `finalScores` is **corrected** and the corrected result settles (RD12) — a fabricated score never becomes a real RP gain.
   - Settlement is idempotent across replayed calls; duplicate/retry settlement settles once (RD13).
   - RP never goes negative; mixed-tier asymmetry holds in all three directions; the private ×2 cap is never exceeded (RD5/RD6).
   - Demotion + the one-match protection (RD7); King ceiling: RP accrues above King and is leaderboard-only (RD3).
   - Placement: 10/20/70 weighting, Platinum ceiling never exceeded, no provisional rank ever shown, placement matches count toward no statistic (RD10).
   - Matchmaking: own tier or exactly one below, no opt-up, mixed pool permitted when the server forms it (RD9).
   - Season reset: two-step demotion **direction** (Gold I → Gold III, never the reverse), ladder floor respected, career stats intact, Highest Rank intact, **King → Royal I** (RD24).
   - Leaderboard: ordering is server-computed, never client-ordered; King players sort by RP above the King lower bound; the current player's row is highlighted; a new season produces a fresh board with no carryover positions (RD27).
   - Vote Kick: Ranked-only enforced by `mode`; unavailable before Round 7 completes; tied last place blocks it; 2-of-3 passes; 1-of-3 or timeout fails; failure puts a 5-round cooldown on that same target only; success removes permanently, forbids rejoin, and records the target as Koz (RD17/18).
   - **15-timeout automatic removal fires with no vote, no Round-7 gate, and no Koz requirement — and it neither consumes nor resets the Vote Kick cooldown. The two mechanisms share no state.** (RD20)
   - **The timeout counter is private: an opponent can never read another player's timeout count** (RD20).
   - A Vote Kick vote cannot change its target mid-vote — the unique-Koz target is frozen for the vote's duration (OPEN-2, resolved one way or the other before this test is written).
   - Tie for 1st → all tied players render as Match King; shared placements are counted in stats exactly as achieved (RD17).
   - A bot-controlled seat never votes and never pads the eligible-voter count.
   - Long-press short stats never blocks a legal card play; the popup shows exactly the 6 short values (RD23).

---

### Honest reconciliation with the previous estimate

`ANDROID_MIGRATION_PLAN.md` says "**~5–8 months, one engineer**" for the whole migration. Before the Ranked amendment this plan said **~4.1 months for what's left** (18 weeks, voice included; 13 weeks without it) — consistent, not contradictory, with roughly 5 months of the original range already spent (Phases 0–10, spec through offline UI). **The 2026-10-05 Ranked amendment (RD25) breaks that envelope: 29 weeks realistic / ~34 conservative**, because Ranked is 388h of launch-blocking scope that the original migration plan never contained. The honest statement is now: **the original "~5–8 months" estimate covered the JS→Kotlin migration, not the competitive system.** Ranked is new product scope, and it is the reason this revision grew from 18 to 29 weeks — just as voice was the reason it previously grew from 13 to 18.

The one place this plan disagrees with the existing documentation is the **status snapshot**: the plan's "~45% — session ~5%, Phase 3 untouched" was true on 2026-09-19 but is stale as of 2026-09-21, because PRs #47 and #48 merged the services module and the GameSession store. Estimating from the stale snapshot would have roughly **doubled** the online-multiplayer line. The assumptions that would have caused that error: trusting a status section instead of reading `origin/main`, and not noticing that `:services` is now a CI-tested module.

**A second disagreement, corrected by the 2026-10-05 amendment rather than by reading code:** the plan's own scope statement excluded matchmaking/ranked and seasons, describing ranked as post-v1. That was a planning decision, not a repository fact — and the owner's RD25 inverts it. **Ranked, matchmaking, RP, and seasons are MVP and launch-blocking.** The six sites that stated otherwise (V2, the Phase 6 row, D3, the Backend-development row, M4c, and the executive summary's "what is NOT included") are all amended in place above, with the pre-amendment text preserved in parentheses where it still carries the voice-in-Ranked exclusion — which survives, because RD22 is unchanged. **The `design-ui` rank mocks are non-canonical legacy data** (`Gold III` 1240 > `Gold I` 980 reverses the I > II > III order, and `Platinum IV` does not exist in a 6×3+King ladder). RD26's closed threshold values govern (set 2026-10-05); nothing is derived from those mocks.

---

### Store & policy deltas introduced by voice (part of the 12h row)

Voice adds no new purchase, no new data collection, and no new server — but it *does* add a permission and a data-flow that Play and the privacy policy must describe accurately. These are cheap in hours and expensive in risk if skipped (R10/R18/R19).

| Item | What it must say | Where it lands |
|---|---|---|
| `RECORD_AUDIO` permission justification | "Used for push-to-talk voice chat with other players in the same room. Audio is captured only while you press and hold the talk button, is transmitted directly to those players, and is never recorded or stored." Must be **honest about PTT** — do not describe an always-on mic we don't have, and do not describe a mic we then leave open | Play Console → App content → Permissions; also the in-app rationale dialog (EN + AR) |
| Data-safety audio declaration | **Audio is not collected, not stored, not transcribed, not uploaded to any server.** It flows peer-to-peer between room members. If Play offers a "transferred" vs "collected" distinction, answer transferred-between-users, never "collected" | Play Console → Data safety. **Must agree with the permission justification and the privacy policy — reviewed as one set, not three** |
| Privacy policy sentence (EN + AR) | "Estemshan offers optional push-to-talk voice chat in casual rooms. Voice audio is transmitted directly between players in the same room while you hold the talk button. We do not record, store, transcribe, or retain voice audio." (**Ranked is now MVP scope — RD25 — and carries no voice ever, RD22. The sentence stays accurate as written; the exclusion is a permanent rule, not a hypothetical.**) | Privacy policy page (hosted via the D4 web shrink) |
| Mic-disclosure posture | No foreground service, no background capture, default muted, auto-leave on background — **this is the policy defense, and it is a code property, not a claim**. If any of those four regresses, this disclosure becomes false | Verified by the voice QA matrix items (d) and (e), not by review |

**The dependency to respect:** `AI Bots/` strings are EN-only today, and the app's RTL is already enabled (`supportsRtl=true`) with no `res/` at all. The PTT button label, the mic-rationale dialog, and the "unreachable"/"muted-by-default" states are **new user-facing strings that must ship in EN and AR** — EN lands with their own stories' `strings.xml` infra (S12 pattern), AR values land with S41 — never as hardcoded Compose text.

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

---

## Final status block (2026-10-05)

```
RANKED SYSTEM PLANNING      — UPDATED 2026-10-05 (RD1–RD28; RD26 CLOSED, OPEN-1/OPEN-2 remain)
GAME TYPE / CALCULATION     — ADDED 2026-10-06 (GM1–GM8, E7 S65–S69, +48h; OPEN-3 CLOSED)
UI/UX ROADMAP               — UPDATED (§4b + contradiction register)
CODE/ARCHITECTURE ROADMAP   — UPDATED (E6b S42–S64, E7 S65–S69, R24–R29, M9–M11)
IMPLEMENTATION              — NOT STARTED
FIGMA                       — NOT STARTED
BATCH 2                     — NOT STARTED
```

**Planning only.** No code, no Kotlin/Java/TypeScript/JavaScript, no Cloud
Functions, no Firestore rules change, no matchmaking implementation, no UI, no
screens, no Figma. The two roadmap documents — and, for the GM amendment,
`docs/rules/CANONICAL_RULES.md` Amendment A2 — are the deliverable.

**Where the two documents agree, and where they differ on purpose:** both carry
the same 19-rank ladder, the same RD1–RD28 decision set, the same `mode`-first
critical path, and the same four-tier authority model. The code roadmap owns the
*execution* view (S42–S64 + **S65–S69**, +388 h **+48 h**, R24–R29, M9–M11); the UI/UX roadmap owns the
*design* view (screens S45–S58, the Rank Chip and Tier Ladder components, the
tier-token gaps, the design gates). **RD26's 19 thresholds are CLOSED in both,
identically (2026-10-05); OPEN-1 and OPEN-2 remain open in both, identically, and
both are non-blocking.** Neither document
reopens a closed owner decision. **The GM amendment (GM1–GM8) is carried in both
identically, with A2 as the shared rule statement. **OPEN-3 — the Mini RP rounding rule — was CLOSED 2026-10-06, before S47 was written, exactly as planned: nearest integer, ties away from zero (+15 → +8, −15 → −8), implemented with `kotlin.math.round` rather than `java.lang.Math.round`.** With OPEN-3 closed, **no open item gates a Ranked story** — OPEN-1 and OPEN-2 remain open and non-blocking, and neither blocks E6b or E7.

**The two small decisions the owner still owes, both on the Ranked critical path
and both non-blocking for design work:** **OPEN-1** (reconnect after the
15-timeout automatic removal) and **OPEN-2** (whether a Vote Kick pauses the
match). Everything else is closed — **RD26's 19 thresholds are in**.

Arabic translations are provided as a working baseline; **have a native speaker review them before submission** — the same standard the rest of the AR strings should meet.