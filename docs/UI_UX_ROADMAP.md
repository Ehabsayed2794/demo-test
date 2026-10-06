# Estemshan — UI/UX Roadmap (Master Planning Package)

**Owner decision of record (2026-10-03):** The 4-player multiplayer playtest is
CLOSED. The UI is not ready for gameplay validation — in the last test players
reached Round 1 → Bidding with **no playing cards visible**. No UI coding, no
patching of the current UI to continue testing, and no microphone work happens
until this roadmap is complete, the Figma designs are finished and approved, and
the native implementation is rebuilt against them.

**Owner amendment of record (2026-10-03) — "New game features and rules":** the
owner added a substantial new feature set (Create Game screen, decision timers,
Medium-Bot timeout decisions, the 15-timeout automatic removal *(terminology
corrected 2026-10-05 by RD20: this is a separate mechanism from Vote Kick — no
vote, no Round-7 gate, no Koz target, no cooldown; the two are never merged)*, the
50-second inactivity system, manual Vote Kick, dynamic King/Koz badges, the
Rooms-only Disconnect Vote, Rooms-only Push-to-Talk, Ranked profile statistics,
and the in-match Quick Stats popover). **The amendment's rules are authoritative.** Where
earlier Batch 1 text contradicted them, the older line has been rewritten in
place, not preserved — the two most visible reversals: S09/S10 are no longer
deferred (both Create flows now run through one shared Create Game screen), and
open questions 3 and 4 are answered by the amendment (presence policy and the
turn timer are now specified). Full feature set: **§0.5**. Repository evidence
behind every classification: **§0.6**.

**Owner amendment of record (FINAL, 2026-10-05) — "Ranked progression system":**
the owner closed the complete Ranked design: the **19-rank ladder** (6 tiers × 3
divisions + King), **dynamic RP**, mixed-tier asymmetric rewards/penalties, the
Private Ranked ×2 loss cap, **placement (10/20/70, Platinum ceiling, once per
account)**, **Vote Kick moved to Ranked-only**, the 50 s inactivity / timeout /
15-timeout-automatic-removal separation, **exactly 9 career statistics**,
**3-month seasons** with a two-division-step reset, the **lightweight Cloud
Functions authority model** (correct-and-settle, the MVP security boundary), the
cost principle, **the seasonal Ranked leaderboard**, and — critically — **Ranked
is now MVP scope and launch-blocking (RD25)**. Decisions are **RD1–RD28**; **RD26
(the 19 numeric RP thresholds) was CLOSED on 2026-10-05 with the final
lower-bound values**, so only **OPEN-1/OPEN-2** (reconnect-after-15-timeout-removal; whether a
Vote Kick pauses the match) stay open, both non-blocking. **The 2026-10-06 GM
amendment adds OPEN-3** — the RP rounding rule for an odd Full delta under
Mini's ×0.5 — **which was CLOSED 2026-10-06, before any settlement code was
written: nearest integer, ties away from zero (+15 → +8, −15 → −8). No open item
gates a Ranked story.** **This amendment is in the same standing as the
2026-10-03 block above.** Where frozen Batch 1 text contradicted RD1–RD28, the
contradiction register below lists the correction and the document's own
"real contradiction" escape hatch (the Batch 1 freeze line) is invoked — nothing
is silently rewritten. The new screens are **S45–S58** (no renumbering of
S01–S44); the new Batch 2 design plan is **§4b**; the code/architecture
counterpart is `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (epic **E6b, stories
S42–S64, +388 h**). **Still no code, no Figma, no Firestore rules, no Cloud
Functions — planning only.**

**A second owner amendment landed 2026-10-06: Game Type & Calculation Mode
(GM1–GM8).** Two selectable match options — **Game Type** (Full / Mini) and
**Calculation** (Normal / Classic) — that are persisted on the room and match
documents and change the round count, the Quick Round boundary, the escalation
cap, and (in Ranked) the RP delta. **Ranked supports both types (GM5) and Mini's
RP delta is 50% of Full's (GM6).** The design impact is spread through this
document (S36, §2.4, S14/S16, §4b.4, §4b.6, the design gates, and the
consistency audit); the code/architecture counterpart is epic **E7, stories
S65–S69, +48 h**, which **precedes E6b** because S43/S47/S56 consume `gameType`.
**OPEN-3 — the RP rounding rule for odd Full deltas under GM6's ×0.5 — is CLOSED
(2026-10-06): nearest integer, ties away from zero (+15 → +8, −15 → −8). It was
closed before settlement was written, as planned, and now gates nothing.** **Still no code, no Figma, no Firestore rules —
planning only.**

**Delivery contract for this document (owner amendment):**

- The entire planning package lives in this one file. Nothing exists only in chat.
- Delivery is in **two batches**. **Batch 1 = Phase 1 + Phase 2 only.** After
  Batch 1 the agent STOPS and waits for owner approval. Batch 2 (Phases 3–9)
  is written only after approval. **The 2026-10-03 amendment keeps this contract
  intact** — it re-specifies Batch 1's content and pushes its own downstream
  consequences into the Batch 2 phase outlines, but it does not start them.
- Visual consistency with the two finished Figma screens (Lobby, Waiting Room)
  is **NOT claimed anywhere in this document.** No Figma link or exported PNG
  has been shared yet. *(Corrected 2026-10-05: **`docs/design/` now EXISTS in the
  repository** — `Estimation Design System.pdf` and `Mobile app design.zip`, both
  added 2026-10-04. The assets are present but have still not been reviewed into
  this document, so the claim about visual consistency is unchanged; only the
  "does not exist" half of the sentence was false.)* **Phase 5 remains BLOCKED ON
  OWNER** for that review; **Phase 4 does not** — it is written token-driven from
  the shipped code that already mirrors them (§4.1), so it can be executed now.
  *(This line was corrected on 2026-10-04, when the owner approved Batch 1 and
  directed Phase 4 to proceed; the original text blocked both phases.)*

**Status: BATCH 1 — APPROVED AND CLOSED (owner, 2026-10-04).** All owner decisions
D1–D10 and the Unique-Koz requirement are final. **The Batch 1 spec (§0–§2.8) is
frozen** — it is not modified again unless a real contradiction is discovered.
The open questions list at the end of this document is logistics only and does
not reopen any decision.

**Batch 2 opens here — Phase 4 first.** Per the owner's direction (2026-10-04):
the priority is to **complete all game screens and flows in Figma before any UI
code is written**. Phase 4 is the Figma design plan; Phase 5 is the visual
consistency pass. **No UI code, no Code Plan, no implementation work is started
by this batch.**

---

## 0. Repository findings that reframe the whole roadmap

These four facts were established by reading the repository and must be
understood before any screen is designed, because they determine what is
greenfield, what is a redesign, and what only needs wiring.

### 0.1 There are two codebases, and `android/` is not one of them

| Path | What it actually is | Relevance |
|---|---|---|
| `android/` | Legacy **Capacitor** shell — `MainActivity.java`, zero Kotlin, no game UI | **Not the app. Ignore it for UI work.** |
| `native/` | The real app: `native/engine` (pure rules), `native/services` (Firebase), `native/app` (Compose UI) | **This is the product.** |
| `design-ui/` | JavaScript **web reference implementation** — the state machine, the engines, and a web render layer | The proven flow + visual reference. The engines were ported from here. |
| `src/` | Legacy manual score tracker (`setup` → `game` → `over`) | Reference only, not ported. |

### 0.2 `main` is far behind the playtest branches

On `main` the native app is at placeholder level: `LobbyPlaceholder` (its own
text says *"room list lands in Phase 4"*), **no `RoomScreen` exists at all**,
`Routes.ROOM` is an unregistered dead constant, `BIDDING`/`TABLE` are reachable
only through the **offline** `quickMatchGraph`, and `Routes.STANDINGS` is
hard-wired to `buildStandings(emptyMap())` — always empty.

The real multiplayer UI lives on the `android/phase5-*` branches, which contain
`ui/lobby/LobbyScreen.kt`, `ui/room/RoomScreen.kt`,
`ui/onlinematch/{OnlineMatchFlow,OnlineMatchViewModel,MatchUiState}.kt`, and
`Routes.CHOOSE_LEVEL` (Play vs AI). **The playtest ran on one of these
branches.** Any implementation roadmap must decide whether to continue from
those branches or rebuild — that is an owner decision, but the *design* does not
change either way.

### 0.3 Root cause of "no cards visible during Bidding" — a design gap, not a bug

This is the single most important finding in the document. Tracing the hand data
path end to end:

1. The hands are real and correct — `Dealer.dealHands()` returns 4 × 13 sorted
   cards (`native/engine/.../Deck.kt`).
2. In `QuickMatchViewModel` they are **`private`** with **no public accessor**.
3. The engine's `BiddingState` deliberately has **no `hands` field** — the engine
   is pure functions by design, so hands are never in bidding state.
4. `BiddingScreen` has **no hand parameter and no hand composable** — there is no
   `LazyRow`, no card component, nothing in the whole `ui/bidding` package.
5. Hands leave the view model in exactly **one** place: `onBiddingComplete()`,
   which packs them into `RoundCfg.hands`. That reaches `TableState`, and only
   then does `TableScreen.HandRow` render them.
6. The online path is identical: `MatchUiState.Bidding` carries no hands either.

**So a player legally cannot see their cards until the *entire* auction
(Dash → Auction → Confirm → Estimates) is over and the Table screen opens.**
Nothing is broken to fix — the capability was never built. This is decisive for
the design: **bidding is a decision made *about your hand*, so a blind auction
is rules-nonsensical and unplayable. "Player hand visibility during bidding" is
the #1 gameplay-critical UI requirement of the entire program** (carried into
Phase 3 and the testing gate in Batch 2).

Two secondary hand-visibility defects were found on the Table screen and must
also be designed for: the hand **disappears entirely** during `RESOLVING` and
`DONE` (it renders only when `phase == PLAY`), and an empty hand renders as
blank space — **there is no empty-state fallback at all**.

### 0.4 The online multiplayer surface is where the design gaps concentrate

The engine (rules) and services (Firebase writes) are substantially built and
tested. The **UI** is the missing layer. Backend states that exist today with
**no UI representation whatsoever**: room `waiting`/`in_game`/`closed`, match
`starting`/`complete`, the seat map, the entire **rematch vote machine**
(`OPEN`/`ALL_YES`/`FAILED_NO`/`FAILED_TIMEOUT`/`NEW_MATCH_CREATED` — fully
implemented in `MatchService`, with a complete web UX in `design-ui/match`, and
zero native UI), plus every error/reason code. And critically: **there is no
presence, no reconnect-to-seat flow, no leave-match, and no abandon/forfeit
path** — a mid-round departure stalls the match silently with no notification to
anyone. These are design problems, not engineering ones, and the Figma designer
must cover them.

### 0.5 The owner amendment (2026-10-03) — the authoritative new feature set

Everything in this subsection is a product requirement stated by the owner. It is
recorded here verbatim-in-substance because Phases 1 and 2 must be re-evaluated
against it, and Phases 3–9 must inherit it. **Nothing here is implemented unless
§0.6 says so.**

> **Final owner decisions (2026-10-04).** The owner closed the six open questions
> this amendment had left (D5–D10) **plus** the unique-Koz requirement. Those
> decisions are folded into the rules below and recorded as **CLOSED** in §2.8.
> Wherever a rule below carries a *(D# — CLOSED)* tag, that value is final and no
> longer a design variable.

**One shared mode model.** Rooms and Ranked Matches are **one game**, not two.
The rules, flow, timers, bot behaviour, timeout behaviour, inactivity behaviour,
automatic 15-timeout removal, ranking badges, and statistics are all shared.
**The intentional product differences are: voice chat and the Disconnect/Pause
Vote — both Rooms-only — plus Manual Vote Kick, which is Ranked-only (RD18).**
*(Owner correction of record: any earlier statement that voice is the sole
difference is superseded; the 2026-10-05 RD18 amendment additionally moves Manual
Vote Kick out of the shared column into Ranked-only.)*

| Capability | Room | Ranked Match |
|---|---|---|
| Game rules, rounds, scoring | identical | identical |
| Create Game configuration screen | same screen (S36) | same screen (S36) |
| Bot difficulty / personality / timers | yes | yes |
| Medium-Bot decision on timeout | yes | yes |
| Inactivity bot takeover + return | yes | yes |
| **Manual Vote Kick** | **no — RD18, Ranked ONLY** | **yes** |
| **Automatic 15-timeout removal** | yes | yes |
| Dynamic King/Koz badges | yes | yes |
| Ranked progression / RP / seasons | no | yes |
| **Ranked statistics (the 9 career values)** | no | yes |
| **Push-to-Talk voice (Waiting Room + in-match)** | **yes** | **no** |
| **Disconnect / pause vote** | **yes** | **no** |

**A. Create Game screen (S36).** "Create Room" and "Create Ranked Match" open
the **same** screen — there are not two configuration surfaces. It configures,
before creation: **Bot Difficulty** (Easy / Medium / Hard / Expert); **Bot
Personality** (the four existing personalities — the owner confirms the gameplay
code exists and must be treated as reusable infrastructure, not redesigned); and
**Decision Timer** with the four selectable values **5 / 10 / 15 / 20 seconds**.

> **The timer rule (cross-cutting).** The selected value is the **Card Play**
> timer. **All other player-decision phases receive the selected value + 5
> seconds.** So 15 s → Dash 20, Bidding 20, Estimates 20, Card Play 15. 10 s →
> Dash 15, Bidding 15, Estimates 15, Card Play 10. This applies identically to
> Rooms and Ranked.
>
> **Defaults (D5 — FINAL OWNER DECISION):** Bot Difficulty **MEDIUM**, Bot
> Personality **BALANCED** ("Steady"), Decision Timer **15 seconds**. The player
> may change any of the three before creating the game, and the live per-phase
> summary must reflect the selection — under the defaults it reads Dash 20 s,
> Bidding 20 s, Estimates 20 s, Card Play 15 s.
>
> **BALANCED is the official personality fallback (D5 — FINAL).** Wherever a bot
> is created without an explicitly selected personality — any skill tier — it is
> **BALANCED / Steady**. Skill and personality stay orthogonal: **MEDIUM** with
> no personality selected is **MEDIUM + BALANCED**; **EASY** with no personality
> selected is **EASY + BALANCED**; and so on for HARD and EXPERT. This is the
> fallback in *every* place that needs a personality default, not only S36.
>
> *Repository note:* the owner's decision names `BotPersonality.DEFAULT = BALANCED`.
> No such constant exists yet — the repo's de facto default is the inline literal
> `bot.personality ?? 'BALANCED'` in `botPersonality.ts`'s wiring comment, and
> `BALANCED` is the first member of the `Personality` type. **The named constant
> is a one-line addition at port time; the value it would hold is already the
> repo's default.** No new personality is created.

**B. Timer expiration is a Medium-Bot decision, not a "default move".** When the
human's timer expires, the game decides **on that player's behalf using the
existing MEDIUM bot decision engine**. No separate fallback system, no
hard-coded defaults. It applies to **Dash, Bidding, Estimates, and Card Play**.

**C. 15 timeouts across the whole match → automatic removal (NOT a Vote Kick).**
The timeout counter is per-player, accumulates across the **entire match**
(Dash, Bidding, Estimates, Card Play — all decision phases), and never resets
between rounds. On the **15th** total timeout the game automatically **removes**
that player. **This is a separate mechanism from Manual Vote Kick (RD20): it
requires no Round-7 completion, no Koz target, no vote, no 2-of-3 threshold, no
cooldown, and does not consume or reset the Vote Kick cooldown. The two systems
share no state — do not merge them in any UI, state model, or test.**
**Visibility (D6 — CLOSED): the count is internal and authoritative — it is
never shown to opponents.** Opponents cannot see a player's timeout count nor how
close they are to 15. A player may see **their own** count when the UI needs it
(S38). The three layers must stay distinct in the design: internal tracking
(always on), own-visibility (permitted), opponent-visibility (forbidden).
Reconnect-after-automatic-removal is **not finalized** (OPEN-1) — no rejoin rule
is assumed by any screen.

**D. Inactive player (50 s) — a different system from timeout.** "Inactive" means
still connected but not using the game. After **50 seconds** of genuine
inactivity the player is marked INACTIVE, a bot **immediately takes the seat**
(using that game's configured difficulty/personality), and the match continues.
**The player may return** and regain the seat; the bot stands down. Detection
requires a real presence/activity mechanism, not a UI countdown.

**E. Manual Vote Kick (Ranked ONLY — RD18).** **Not available in Rooms** (this
corrects the frozen text above, which read "Rooms + Ranked"; the escape hatch is
the document's own "real contradiction" rule at the Batch 1 freeze line).
Eligibility: **at least 7 complete rounds** (so the first possible vote is
**Round 8 onward**); the target must be the **current UNIQUE Koz**; any eligible
**human** player other than the target may initiate; the **target does not vote**;
a **majority of eligible human voters** is required (4 players − 1 target = 3
eligible, **2 YES is enough**). Purpose: a player intentionally harming or
disrupting the game — griefing or abuse, **not** normal gameplay mistakes, and
**not auto-detected**. Success = **permanent removal**, bot immediately takes the
seat, the removed player **cannot rejoin**, and the removal is recorded as a
**Koz outcome** for that player in the match record / Ranked history. **A failed
vote puts a 5-round cooldown on that same target only.**

> **Bots never vote (D7 — CLOSED).** A bot-controlled seat does **not** receive a
> vote in any vote system, and it does **not** count toward the number of
> eligible voters. Eligibility is computed over the actual **human** players who
> qualify. So if a seat has gone bot and the target is excluded, a 4-player table
> can drop to as few as 2 eligible human voters — and the majority is recalculated
> over that smaller set, never padded by bots.

> **Failed-vote cooldown (D8 — CLOSED).** If a Vote Kick fails, the **same
> target** cannot be targeted again for **5 rounds**. The cooldown is measured in
> **game rounds, not seconds** — a failure in Round 8 blocks that player until
> Round 13. It lives in authoritative match state, and it is **not** reset by the
> target reconnecting or becoming active again.

**F. Dynamic King/Koz badges.** **KING** = currently 1st place; **KOZ** =
currently last. Shown **continuously during gameplay** next to the avatar, not
only on the Round Result screen. **Multiple Kings are allowed** (tied 1st);
ties at other positions are allowed too. At match end: 1st = King, 2nd = Second,
3rd = Third, last = Koz; tied first-place players are all Kings. **No tie-breakers
are invented anywhere.**

> **Unique-Koz requirement (CLOSED).** Manual Vote Kick is available **only when
> there is exactly ONE current Koz**. If last place is tied between two or more
> players, Vote Kick is **not available** — the table never picks one of the tied
> players arbitrarily. This rule is stated in five places, per the owner's
> instruction: the ranking rules (here), the Game Table UI (S43), Vote Kick
> eligibility (S40), the state model (`kozSeatId: String?`, null on a tie — §1.3),
> and the testing gate (Phase 9).

**G. Disconnect Vote — Rooms only.** When a player actually disconnects: the bot
immediately takes the seat, and **in Rooms only** the remaining eligible **human**
players get exactly one vote, one question — **"Pause game until the player
connect"**, YES/NO. The disconnected player does not vote; bots do not vote; 3
remaining eligible humans, **2 YES needed**. The vote is shown **once per
disconnect event**. **Expiry with no decision = NO.** NO majority or expiry →
continue with the bot.

> **The pause is bounded (D9 — FINAL OWNER DECISION).** A YES majority pauses the
> match — but only for a **maximum of 2 minutes**. At the end of that window the
> game automatically starts a **second, required vote**: **"Continue playing with
> the bot, or wait longer for the player?"** (CONTINUE / WAIT). Majority chooses
> CONTINUE → the match resumes with the bot. Majority chooses WAIT → the pause
> extends for another **2-minute** window. **No decision before that vote expires
> → treat as CONTINUE.**
>
> **Maximum WAIT cycles = 2.** The WAIT branch may be taken **at most twice**:
> first WAIT → 2 minutes → vote → second WAIT → 2 minutes → vote. **After the
> second WAIT has been consumed, a third WAIT is not offered** — the next vote is
> CONTINUE-only, and the match resumes with the bot on its expiry. There is **no
> `2 min → WAIT → 2 min → WAIT` loop** anywhere in the design: every pause is 2
> minutes, the table gets at most two of them per disconnect, and the game always
> moves on to the next state in the flow.

**H. Profile Ranked statistics — Ranked only.** **Exactly NINE career statistics**
(RD23 — never described as "10 values"): **Games Played; King count + King %;
2nd count + 2nd %; 3rd count + 3rd %; Koz count + Koz %.** (The nine are the six
count/percent centred on King/2nd/3rd/Koz plus Games Played.) Percentages are
computed from the player's **completed** Ranked matches. **Inclusion rules
(2026-10-05): the nine statistics include both Public Ranked and Private Ranked,
and they EXCLUDE Placement matches** (RD10). **No additional statistics in v1** —
no match-history feature, no seasonal trophy system. **Highest Rank ever reached
is recorded on the Profile and is never erased by a seasonal demotion (RD24).**
The `design-ui` rank mocks are **non-canonical legacy data** — do not derive any
threshold, ordering, or rank name from them.

> **Removed / abandoned matches are not erased (D10 — CLOSED).** A Ranked match
> affected by removal or abandonment is included in the player's Ranked
> statistics **according to the game's recorded final outcome**. Specifically: a
> successfully Vote-Kicked player **counts as KOZ for that match**, and the
> Profile statistics must reflect it — the kick is never treated as an unrecorded
> non-match. For genuinely incomplete scenarios the design must define an
> **authoritative recorded outcome first** and compute statistics from it; a
> normal final placement is never silently fabricated where no outcome exists.

**I. In-match Quick Stats.** **Long-press** another player's avatar/player area
opens a compact popover / bottom sheet with the **short** version of that
player's Ranked statistics — not the full Profile. It must not interfere with
card play. Same underlying data source as the Profile.

**J. Push-to-Talk — Rooms only, still deferred.** Press-and-hold → mic active;
release → inactive. Locations: **Waiting Room** and **during the match**. Never
Ranked. Implementation (including microphone permission work) stays in its later
phase; this roadmap only records the scope.

**The four ways a seat can leave its human — do not merge these.** The owner's
final instruction is that these remain conceptually distinct everywhere they
appear (state model, UI, flow, copy):

| | Trigger | Who decides | Duration | May the human return? | Counter? |
|---|---|---|---|---|---|
| **A. TIMEOUT** | A decision timer expired | The **MEDIUM** bot, for that one decision | One decision | **Yes — the player never leaves the seat** | **Yes — +1, match-wide** |
| **B. INACTIVE** | 50 s genuinely inactive while online | The configured bot takes the seat | Until the player returns | **Yes — the human regains the seat on return** | No |
| **C. DISCONNECT** | Connection lost | The configured bot takes the seat; **Rooms** may then vote to pause | Until reconnect, or until the table votes CONTINUE | **Yes** — and in Rooms the table may wait up to 2 min, then decide again | No |
| **D. VOTE KICK** | A vote succeeded (manual or the 15th timeout) | The table | **Permanent** | **No — never** | No |

Only A increments the timeout counter. Only D is permanent. B and C both allow
return and are distinguished by *why* the seat emptied (idle vs. unreachable) —
which is also why C carries the Rooms-only vote and B does not.

### 0.6 Repository verification for the amendment — what actually exists

The amendment explicitly forbids assuming a feature exists because it was
requested. Every new requirement was checked against the repository. **These
findings govern every classification in §2.6.**

**V1 — The bot engine exists, but only as untracked TypeScript at the repo root.**
`AI Bots/` holds `App.tsx`, `botEngine.ts`, `botPersonality.ts`, `botPlay.ts`,
`botSimulation.ts`, `botStrategy.ts`, `types.ts`. `git status` reports it as
`?? "AI Bots/"` — **it is not committed**. Confirmed real:

- `BotTier = 'EASY' | 'MEDIUM' | 'HARD' | 'EXPERT'` (`botEngine.ts:28`) — matches
  the owner's four difficulties exactly.
- `Personality = 'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE' | 'TRICKSTER'`
  (`botPersonality.ts`) — the four personalities, as "Steady" / "The Shark" /
  "The Fox" / "The Joker".
- **Personalities genuinely affect decisions**, not just labels: each carries
  `bidBias`, `dashEagerness`, `superCallAppetite`, applied by
  `applyPersonalityToBid()`; tiers carry `mistakeRate`, `distributionConfidence`,
  `bidNoise`, `canDash`, `countsCards`, `modelsOpponents`, `usesSimulation`
  (`TIER_CONFIG`, `botEngine.ts:52`). MEDIUM = mistakeRate 0.10, canDash true,
  no simulation.

But: a repository-wide search for `personalit|difficulty` returns **only**
`AI Bots/*.ts` and documentation — **nothing in `native/` or `design-ui/`
references the bot system at all.** The files are self-contained by design
("imports only ./types and ./botEngine"; "Additive — nothing breaks if you don't
wire it"). So the owner's premise is **true as reference logic, false as product
infrastructure**: the AI is a prototype that must be **ported to Kotlin against
the engine's `Card`/`BiddingState` types** before any screen can connect to it.

The **Medium-Bot decision entry points** the timer system must invoke:

| Decision | Entry point | Notes |
|---|---|---|
| Dash / Bidding / Estimates | `evaluateBotBid(hand, tier = 'MEDIUM', options)` → `{ bid, isDashCall, rawFloat, … }` | `botEngine.ts:309`; personality wrapper `evaluateBotBidWithPersonality()` exists but timeout takeover uses plain **MEDIUM** |
| Card Play | `selectBotCard(bot, allPlayers, currentTrick, trumpSuit, tier = 'MEDIUM', opts)` | `botPlay.ts:85`; throws on empty hand / unset bid — the caller must supply real state |

Both default to `MEDIUM`, which is exactly what amendment item B requires.

**V2 — No decision timer exists anywhere in the product.** `native/` contains no
`timer` / `timeLeft` / `timeRemaining` symbol — the only hit is a retry-backoff
seam in `MatchAdapter.kt:864`. `design-ui/match-service.js` does contain a
countdown, but it is **exclusively the 30-second rematch-vote deadline**, derived
from a server timestamp and re-derived independently in `firestore.rules`. There
is no per-turn, per-bid, or per-Dash timer, client- or server-side. **The entire
timer system is greenfield**, and the rematch deadline is the only precedent for
making a countdown authoritative rather than cosmetic.

**V3 — No presence or activity detection exists.** `design-ui/presence-service.js`
is a **31-line API-only stub**: `updateHeartbeat()` and `isOnline()` both throw
`notImplemented`; `subscribeToPresence()` logs a warning and returns a no-op
unsubscribe. The `lastSeenAt` field exists on the player document but is never
refreshed by any code. So inactivity detection, bot takeover of a seat, and the
return-to-own-seat handback are **all greenfield** — including the seat-ownership
switch the engine would need (today `restoreHand()` re-seeds a reconnecting seat,
but there is no concept of a seat being *temporarily* bot-controlled).

**V4 — The player-document rules block Quick Stats as written.** `firestore.rules`
`players/{uid}`: `allow get: if isOwner(uid); allow list: if false`. **A player
cannot read another player's document**, so a long-press Quick Stats panel cannot
fetch another player's statistics under the current rules — a rules change (or a
separate public-stats document) is a hard prerequisite. Additionally
`onlyAllowedFieldsChanged()` permits changing only `['displayName',
'avatarInitial', 'lastSeenAt', 'currentRoomId', 'currentMatchId']`, so the
`wins` / `rank` / `rp` / `streak` / `level` / `coins` / `gems` fields are
**write-once at creation** (`isValidNewProfile()` forces `rank == 'Unranked'`,
`rp == 0`, `wins == 0`, …) and **no code anywhere increments them**. There is no
`stats`, `history`, or `season` collection — `firestore.rules` governs exactly
`players`, `rooms`, `matches`, `roundArchive`, `rematchVote`, `hands`.

**V5 — The match document does not record its mode.** `MatchDoc`
(`MatchModels.kt:198`) has `roomId`, `players`, `status`, `currentRound`,
`maxRounds`, `seats`, `winnerIds`, `finalScores`, … — **no `mode` field**. `mode`
lives only on the in-memory `Session` (`Session.kt:128`, `:237` — *"ranked / ai /
friends is WHO you play"*). So **nothing in the persisted data distinguishes a
Ranked match from a Room match**, and Ranked statistics cannot be derived from
existing documents. The native `ProfileScreen.kt` is a 76-line identity card
(uid, avatar initials, "Signed in") whose own comment says *"Match stats arrive
with the season service — no invented numbers here."* The web `design-ui/profile`
shows `displayName`, `rank`, `coins`, `gems` — all fields that are never updated.

**V6 — Placements are only half-persisted, but multiple Kings already work.**
`MatchDoc` carries `winnerIds: List<String>?` and `finalScores: Map<String, Int>?`,
and `winnerIdsMatchFinalScores()` (`MatchShapes.kt:142`) enforces that the winners
are **exactly the seats tied at the maximum score**. Tied first place is
therefore already a supported, rules-validated state — amendment F's "multiple
Kings" is consistent with the backend as built. **2nd / 3rd / Koz are not stored**;
they are derivable from `finalScores` at completion but are not currently written
anywhere.

**V7 — The rematch vote machine is the only reusable vote infrastructure, and it
needs real changes.** It already provides the pattern both new votes need: a
`votes` map keyed by seat and copied verbatim from the parent's immutable `seats`
(never client-supplied); vote immutability (a cast seat can never flip); a
deadline re-derived from a server timestamp inside the rules
(`request.time <= createdAt + 30s`); and statuses `OPEN` / `ALL_YES` /
`FAILED_NO` / `FAILED_TIMEOUT` / `NEW_MATCH_CREATED`. But it differs from the new
votes in three decisive ways: it requires **unanimity** (every real seat YES),
not a majority; it exists only **after** `parent.status == 'complete'`, not
mid-match; and it has no concept of an **excluded** voter. Vote Kick (majority of
eligible, target excluded, mid-match) and the Disconnect Vote (majority, one
question, once per event) can reuse the *shape* but not the *rules*.

---

# PHASE 1 — FULL SCREEN INVENTORY

## 1.1 How the inventory was derived

Three sources were cross-checked so nothing is invented and nothing is missed:

1. **The authoritative state machine** — `design-ui/*/game-state.js` (4
   byte-identical copies, SHA-256 `5152F7C8…7069`; verified identical). Its
   `STATES` list is the game's own declaration of every state a player can be in.
2. **The engines** — `BiddingPhase` (`DASH`/`AUCTION`/`CONFIRM`/`ESTIMATES`/`DONE`),
   `TablePhase` (`PLAY`/`RESOLVING`/`DONE`), `Role` (8 roles), `EmitResult`, plus
   the services-layer statuses. Every enum value below is verbatim from source.
3. **The web render layer** — `design-ui/match/index.html` is the only
   fully-implemented game flow (bidding sub-phase panels, table, trick, hand,
   round-complete, match-complete modal, rematch vote panel). It proves which
   states are actually renderable and in what order.

Screen IDs (`S01`…`S35`) are stable and will be referenced by Phases 2–9.
**The 2026-10-03 amendment adds `S36`…`S44`** (§1.2 group H); it does not renumber
any existing screen. Where the amendment contradicted an earlier Batch 1 row, the
row was rewritten — the reversal is called out inline, not left in a footnote.

## 1.2 Master screen inventory

Grouped by player journey stage. **"Figma" column** is the design status for the
Phase 4 plan: `DONE` = the owner has marked it designed (Lobby, Waiting Room);
`NEW` = needs a new Figma design; `VARIANT` = the screen exists but a
game-state variant needs designing; `N/A` = no visual design needed.

### A. Entry & identity

| ID | Screen / surface | Figma | Exists in code? | Notes |
|---|---|---|---|---|
| S01 | **Splash** (launch + Firebase init) | NEW | `SplashScreen.kt` — real but bare: title text + spinner, no logo asset | Routes SignedIn → Lobby, else Login |
| S02 | **Login** (email/password + anonymous) | NEW | `LoginScreen` in `AppNav.kt` — functional, unstyled | States: SignedOut / SigningIn / SignedIn / Error |
| S03 | **Profile** (identity card + **Ranked statistics**) | NEW | `ProfileScreen.kt` — 76 lines, identity only, "no invented numbers" | **Amendment: now carries the 10 Ranked stat values** (§0.5-H). No stats backend exists (§0.6-V4/V5) — the screen is a design surface over data that must be built |
| S04 | **Settings** (account, sound, logout, version) | NEW | `SettingsScreen.kt` — real, every row live | Language selection explicitly out of v1 |

### B. Lobby & room setup

| ID | Screen / surface | Figma | Exists in code? | Notes |
|---|---|---|---|---|
| S05 | **Lobby** (main hub) | **DONE (owner)** | `LobbyScreen.kt` on phase5 branches; `LobbyPlaceholder` on main | **Do not redesign.** Owner has approved the design |
| S06 | **Create Room** confirmation (room code share sheet) | NEW | Yes — `lobby_created_*` strings exist | **Amendment:** "Create Room" first opens **S36 Create Game** to configure, *then* this modal: "Share this code with your friends" |
| S07 | **Join Room** dialog (enter code) | NEW | Yes — `lobby_join_dialog_*` strings + error set | Errors: empty / not-found / full / closed / generic |
| S08 | **Waiting Room** (roster + ready) | **DONE (owner)** | `RoomScreen.kt` on phase5 branches | **Do not redesign.** Code card, player rows, ready toggle, leave |
| S09 | *Game Mode Selection* | N/A | — | **Superseded by the amendment** — the mode choice is now expressed inside S36 (bot difficulty / personality / timer), not a separate screen |
| S10 | **Create Ranked Match** → S36 | NEW | No | **REVERSED — no longer deferred.** Per the amendment, "Create Ranked Match" opens the **same** S36 Create Game screen as Create Room. The differences are what S36 does on Confirm (matchmaking vs. room creation), the Rooms-only capabilities in §0.5, and that **S36 in Ranked mode shows the player's current Rank chip + RP and the Ranked-specific warnings** (§4b). **RD28: Private/Password Ranked is not a third mode — it is RANKED + a private access flag on the same screen** |
| S11 | *Shop / Missions* | N/A | — | **DEFERRED** — post-core program. Unaffected by the amendment. **Season is NO LONGER deferred — RD24 makes it MVP, see S54 in §4b. Only Shop/Missions stay here** |

### C. The match — bidding (per round)

The web reference renders **five** distinct bidding sub-phases. Each is a
separate design variant of one bidding surface.

| ID | Screen / surface | Figma | Exists in code? | Notes |
|---|---|---|---|---|
| S12 | **Bidding — DASH** (Dash Call decide) | NEW | `DashControls`: "Dash Call" / "Decline" | Pre-bid declaration of 0 tricks; max 2 players/round. **Amendment: a timed decision — the S37 ring counts down at selected+5 s; expiry hands the Dash decision to the MEDIUM bot** |
| S13 | **Bidding — AUCTION** (raise or pass) | NEW | `AuctionControls`: tricks slider 4–13, suit picker, Pass | Min bid 4. "With" alignment hint. Top-bid display. **Amendment: timed at selected+5 s; expiry = MEDIUM-bot bid** |
| S14 | **Bidding — CONFIRM** (caller locks the call) | NEW | `ConfirmControls`: slider `floor..13`, suit picker | **Normal rounds 1–13 only.** Raising the number frees the suit. **Amendment: timed at selected+5 s; expiry = MEDIUM-bot confirm** |
| S15 | **Bidding — ESTIMATES** (other players estimate) | NEW | `EstimatesControls`: slider `0..cap`, forbidden-13 + With-floor hints | Call Cap, the 13 Rule, Risk Player, Normal Dash (0). **Amendment: timed at selected+5 s; expiry = MEDIUM-bot estimate** |
| S16 | **Bidding — fast round** (the Quick Round variant) | VARIANT | Same composables, `initFastRound` starts at ESTIMATES | Forced trump ladder; no auction, no confirm; **Full rounds 14–18, Mini rounds 6–10 (GM1)** |
| S17 | **Bidding — Super Call** (8+ override variant) | VARIANT | Handled in engine; **no dedicated UI** | Fast round: cancels forced trump, extends the match +1 round |
| S18 | **Bidding — General Pass / redeal** | NEW | `EmitResult.GeneralPass` handled in VM; **no UI** | All 4 pass → redeal at ×2 multiplier (capped ×8) |

### D. The match — card play

| ID | Screen / surface | Figma | Exists in code? | Notes |
|---|---|---|---|---|
| S19 | **Game Table — PLAY** (your turn) | NEW | `TableScreen.kt` — the ONLY screen that renders a hand | Hand visible here today; this is the model to extend. **Amendment: the S37 ring counts down at the selected value (no +5 s here — Card Play is the base timer); expiry = a MEDIUM-bot legal card. S43 King/Koz badges render on every seat** |
| S20 | **Game Table — PLAY** (opponent's turn) | VARIANT | Same screen, hand dimmed/disabled | Turn banner "Waiting for X". **Amendment: shows the opponent's own S37 ring; S43 badges live here too** |
| S21 | **Game Table — RESOLVING** (trick complete) | VARIANT | Auto-advances after 900 ms highlight | **Hand currently vanishes here — design must keep it visible.** Badges persist |
| S22 | **Game Table — trick 13 / round DONE** | VARIANT | `phase == DONE` → round standings | Transition surface before round result |
| S23 | **Round Result / Round Standings** | NEW | `OnlineRoundStandings`, web `roundCompletePanel` | Per-round scores + deltas; "Waiting for the next round…". **Amendment: no longer the only place ranking appears — S43 badges are the live source of truth; this screen becomes the confirm-and-explain view** |

### E. Post-match

| ID | Screen / surface | Figma | Exists in code? | Notes |
|---|---|---|---|---|
| S24 | **Final Standings** (match result) | NEW | `FinalStandingsScreen.kt` — real but fed empty on main | Kings (ties co-win), `Sa'ayda` badge, extension note. **Amendment: adds 2nd / 3rd / Koz rows and the Vote-Kick-Koz marker. `winnerIds`/`finalScores` exist; 2nd/3rd/Koz are derived, not stored (§0.6-V6)** |
| S25 | **Match Complete modal** | NEW | Web only (`matchCompleteModal`); **no native UI** | Crown, King label, scoreboard, summary, extension note |
| S26 | **Rematch Vote panel** | NEW | Backend complete (`MatchService`), web complete, **no native UI** | 30 s countdown, YES/NO, "0/4" tally, waiting list, outcomes. **The pattern S40/S41 are built on (§0.6-V7)** |
| S27 | **Rematch — outcome states** | NEW | Backend only | ALL_YES / FAILED_NO ("Rematch Declined") / FAILED_TIMEOUT |

### F. System, connection & error states

These are states, not destinations. **This group is the single biggest design
gap** — almost none have a UI.

| ID | Screen / surface | Figma | Exists in code? | Notes |
|---|---|---|---|---|
| S28 | **Loading** (generic / match starting) | NEW | `MatchUiState.Connecting` overlay | Match `status: "starting"` has no distinct UI |
| S29 | **Reconnecting** (transport retry) | NEW | `reconnecting` StateFlow + overlay; fail-open | Keeps last good doc; backoff 250 ms→4 s |
| S30 | **Disconnected** (player presence lost) | NEW | **NO PRESENCE EXISTS.** `lastSeenAt` never refreshed; `presence-service.js` is a 31-line stub | State machine has 6 inbound edges, zero UI. **Amendment now sets the policy: bot takes the seat immediately; in Rooms the S41 Disconnect Vote follows once** |
| S31 | **Waiting for disconnected player** (pause state) | NEW | **Not implemented at all** | Today a missing seat silently stalls the match. **Amendment: becomes the S42 paused state, reached only on a YES majority in the Rooms-only Disconnect Vote — never automatic** |
| S32 | **Match Failed** (terminal sync failure) | NEW | `MatchUiState.Failed` + `OnlineMatchFailed` | Shows reason; "Back to lobby" |
| S33 | **Not In Match** (no seat / match gone) | NEW | `MatchUiState.NotInMatch` → auto-leave | Ordinary path, not an error |
| S34 | **Invalid action / rejection feedback** | VARIANT | `rejection` StateFlow on Bidding + Table | Engine reasons shown inline; needs design system |

### G. Future — voice (documented only, per owner decision)

| ID | Surface | Figma | Notes |
|---|---|---|---|
| S35 | **Push-to-Talk** (press-and-hold mic button) | NEW, LATER | Press-and-hold → active; release → inactive. **Phase 7 documents placement and states only — no implementation, no design priority.** **Amendment tightens the scope: Rooms ONLY — never Ranked — in exactly two places, the Waiting Room and during the match. Not the Lobby, not menus.** In Ranked the control is not hidden-with-a-tooltip; it is absent |

### H. Amendment surfaces — configuration, timers, votes, inactivity, ranking, stats

Added by the 2026-10-03 owner amendment. None of these exist in code (evidence in
§0.6); they are grouped here so the designer sees them as one coherent system
rather than nine unrelated dialogs. **Screen / overlay / variant** is called out
per row — several of these are overlays on existing screens, not destinations.

| ID | Surface | Type | Figma | Exists in code? | Notes |
|---|---|---|---|---|---|
| S36 | **Create Game** (shared configuration) | Screen | NEW | No | **The amendment's keystone screen.** One screen for Create Room AND Create Ranked Match (§0.5-A). Bot Difficulty (Easy/Medium/Hard/Expert), Bot Personality (the four existing), Decision Timer (5/10/15/20 s). Room vs Ranked differ only in the Confirm action and the Rooms-only capabilities |
| S37 | **Decision Timer ring** | Overlay (on S12–S15, S19/S20) | NEW | No | Per-decision countdown. Card Play = selected; Dash/Bidding/Estimates = selected + 5 s. No timer exists today (§0.6-V2); must be synchronized, not client-cosmetic — the rematch deadline is the only precedent |
| S38 | **Timeout — bot decided** | Feedback state | NEW | No | Shown when a human's timer expires and the MEDIUM bot acts for them: what was decided, by whom, and that it counts toward the 15-timeout total. **The 15-timeout total is its own automatic-removal mechanism (RD20) — separate from Vote Kick (RD18).** The count is private: never shown to opponents, only to the player themselves |
| S39 | **Inactive — bot takeover** | Persistent banner + seat chip | NEW | No | 50 s of genuine inactivity → seat is bot-controlled. Player may return and reclaim the SAME seat; the bot stands down. **Distinct from S30/S31 (disconnect), from Vote Kick (permanent, no rejoin), and from the 15-timeout automatic removal (rejoin status OPEN-1)** |
| S40 | **Vote Kick panel** (manual) | Modal — **Ranked ONLY (RD18)** | NEW | No | **NOT available in Rooms.** Manual only — the automatic 15-timeout removal is a separate mechanism and gets its own feedback state, not this panel. Round 8+ (Round 7 must have FULLY completed), target = the current UNIQUE Koz, target excluded, 2 YES of 3 eligible. Purpose = griefing/abuse, not normal mistakes. Shows reason/purpose, live tally, eligible-voter count, success/failure. **The target is frozen for the vote's duration — the unique-Koz target cannot change mid-vote (OPEN-2).** Failure = 5-round cooldown on that same target only. Success = permanent removal, bot takes the seat, no rejoin, Koz recorded |
| S41 | **Disconnect Vote panel** | Modal — **Rooms only** | NEW | No | One question: "Pause game until the player connect". Disconnected player excluded; 2 YES of 3; **expiry = NO**; shown **once per disconnect event**. **Impossible in Ranked — `mode` gates it (RD28)** |
| S42 | **Paused — waiting for reconnect** | Full-table state — **Rooms only** | NEW | No | Reached only on a YES majority in S41. Match resumes when the disconnected player reconnects; bot stands down. A NO majority or expiry continues with the bot instead |
| S43 | **King / Koz badges** | Persistent overlay (on S19–S23) | NEW | No | Crown for current 1st place (one per tied player — multiple Kings allowed), Koz badge for current last. Update live as scores change. Tied last place = no unique Koz = manual Vote Kick unavailable, and the UI must say so. **These are MATCH titles, not Ranked tiers — Match King ≠ Rank King (RD8), and Koz is not a rank at all. The crown must stay visually distinct from the Gold-tier rank chip (§4b token work)** |
| S44 | **Quick Stats popover** | Bottom sheet / popover (on S19–S23) | NEW | No | **Long-press** a player's avatar → the **SHORT** version of their Ranked statistics — exactly six values (RD23): **Rank, Games, King %, 2nd %, 3rd %, Koz %** — never the full Profile. Same data source as S03. Must not block card play or swallow the play gesture. **Unblocked by RD11: the server read path supplies it** (the old §0.6-V4 blocker — `players/{uid}` owner-read-only — is resolved by the Ranked profile's own server-side read, not by loosening the frozen rules) |

## 1.3 Full state / variant inventory

Every state value that a screen must be able to render, verbatim from source.
This is the checklist the Figma designer designs against and the Android agent
implements against.

**Navigation states** (`game-state.js` `STATES`, 17): `Splash`, `Login`,
`Lobby`, `GameModeSelection`, `CreateRoom`, `JoinRoom`, `Matchmaking`,
`WaitingRoom`, `Bidding`, `Gameplay`, `RoundFinished`, `FinalStandings`, `Shop`,
`Profile`, `Settings`, `Disconnected`, `Loading`.
*(Five have no screen file today: `GameModeSelection`, `JoinRoom`, `Profile`,
`Disconnected`, `Loading`.)*

**Bidding sub-phases** (`BiddingPhase`): `DASH`, `AUCTION`, `CONFIRM`,
`ESTIMATES`, `DONE`.

**Table phases** (`TablePhase`): `PLAY`, `RESOLVING`, `DONE`.

**Bid types** (`BidType`): `DASHCALL`, `PASS`, `TRICKS`, `DASH`.

**Emit outcomes** (`EmitResult`): `Applied`, `Rejected`, `Completed`,
`GeneralPass`.

**Player roles** (`Role`, 8 — each needs a visual treatment): `NORMAL`,
`CALLER`, `WIZZ`, `RISK`, `WIZZ_RISK`, `SUPER_CALL`, `DASH_CALL`, `REG_DASH`.

**Room statuses** (`RoomDoc`): `waiting`, `closed`, `in_game`.
**Match statuses** (`MatchDoc`): `starting`, `complete`.
**Card phase** (`MatchDoc`): `PLAY`, `RESOLVING`, `null`.
**Rematch vote statuses** (`VoteDoc`): `OPEN`, `ALL_YES`, `FAILED_NO`,
`FAILED_TIMEOUT`, `NEW_MATCH_CREATED`; vote values `YES`/`NO`; 30 s deadline.

**Online match UI states** (`MatchUiState`): `Connecting`, `Bidding`, `Table`,
`RoundStandings`, `MatchComplete`, `NotInMatch`, `Failed`.

**Auth states** (`AuthUiState`): `SignedOut`, `SigningIn`, `SignedIn`, `Error`.

**Per-round variants that change the screen** (from `CANONICAL_RULES.md`):
normal round vs fast round — **the boundary is Game Type-dependent: Full 1–13
normal / 14–18 fast, Mini 1–5 normal / 6–10 fast (GM1)**; forced-trump ladder
starting Sans on the first fast round (Full 14=Sans, 15=Spades, 16=Hearts,
17=Diamonds, 18=Clubs; **Mini 6=Sans, 7=Spades, 8=Hearts, 9=Diamonds,
10=Clubs**); Super Call (bid ≥ 8);
Dash Call (max 2); Normal Dash (estimate 0); Risk Player (last estimator);
With/Wazz; the 13 Rule; Call Cap; Sa'ayda escalation (×2 → ×4 → ×6 → ×8,
**capped ×2 under Classic**); round extension (repeating the ladder from its own
start: Full 19=Sans, 20=Spades, …; **Mini 11=Sans, and Mini stops at one**);
AVOID/void tag (suit hidden); scoring mode Normal vs Classic.

**Error/reason codes to surface** (services `Reasons`, verbatim — each needs a
human-readable design treatment): room-side `ROOM_NOT_FOUND`, `ROOM_CLOSED`,
`ROOM_FULL`, `NOT_A_MEMBER`; match-side `MATCH_NOT_FOUND`, `MATCH_NOT_COMPLETE`,
`UNKNOWN_SEAT`, `NOT_YOUR_TURN`, `BIDDING_CLOSED`, `ALREADY_BID`,
`ILLEGAL_BIDDING_ACTION`, `ILLEGAL_CARD`, `INVALID_BID_VALUE`, `STALE_GAME_STATE`,
`ROUND_NOT_COMPLETE`, `ALREADY_ADVANCED`, `ALREADY_COMPLETE`, `ALREADY_DEALT`,
`ALREADY_EXTENDED`; vote-side `VOTE_NOT_FOUND`, `VOTE_CLOSED`, `VOTE_EXPIRED`,
`VOTE_LOCKED`, `ALREADY_VOTED`, `NOT_ALL_YES`, `NOT_YET_EXPIRED`,
`DEADLINE_UNKNOWN`.

**Amendment state inventory (2026-10-03).** Every value below is **PROPOSED** —
none exists in the codebase today; this is the requirement list the backend must
grow, cross-checked against source per the amendment's instruction:

- **Seat control** (new): `HUMAN` → `BOT_TIMEOUT` (timer expired, MEDIUM bot
  acting for one decision) → back to `HUMAN`; `HUMAN` → `BOT_INACTIVE` (50 s
  inactivity, bot owns the seat) → `HUMAN` on the player's return;
  `HUMAN`/`BOT_INACTIVE` → `BOT_REMOVED` (Vote Kick success — **terminal**, the
  seat never returns to a human). `BOT_DISCONNECTED` covers an actual
  disconnect, with Rooms able to flip back to `HUMAN` on reconnect.
- **Decision timer** (new): `IDLE` → `RUNNING` (duration per phase: Card Play =
  selected, Dash/Bidding/Estimates = selected + 5 s) → `EXPIRED` → `BOT_DECIDED`.
  A timer is authoritative, not a local animation — like the rematch deadline it
  must be derived from a server timestamp so all four clients agree.
- **Timeout counter** (new): per-player integer, **0…15, never reset between
  rounds**, increments on every `EXPIRED`. At **15** the game raises an automatic
  Vote Kick. Must be visible to the player themselves (S38) and survive
  reconnect.
- **Vote Kick** (new): statuses `OPEN` → `PASSED` / `FAILED_NO` / `FAILED_TIMEOUT`;
  vote values `YES` / `NO`; keyed by seat with the **target's slot structurally
  absent**, not merely null. Initiation gates: `completedRounds >= 7` **and** a
  single unique Koz. Two flavours: `MANUAL` and `AUTO_TIMEOUT_15`. **Bots hold no
  vote slot and are excluded from the eligible-voter denominator (D7).** On
  `FAILED_NO` / `FAILED_TIMEOUT` a per-target cooldown is written:
  `failedVoteKickCooldown: { targetSeat, blockedUntilRound }` — **5 rounds**,
  measured in rounds, in authoritative match state, **not reset by the target
  reconnecting or becoming active** (D8).
- **Disconnect Vote** (new, **Rooms only**): `OPEN` → `PASSED` (pause) /
  `FAILED_NO` / `FAILED_TIMEOUT` (**both failure states mean "continue with the
  bot"**). One question, one vote, **once per disconnect event** — a flag must
  suppress a repeat while the same disconnect persists.
- **Match pause** (new, **Rooms only**): `RUNNING` → `PAUSED_AWAITING_RECONNECT`
  → `RUNNING` (player reconnects, bot stands down). Never reached automatically.
  **The pause is bounded (D9 — FINAL): a 2-minute `pauseDeadline` starts when the
  pause begins; at expiry a second vote opens** — `CONTINUE_OR_WAIT` with values
  `CONTINUE` / `WAIT`. `CONTINUE` (majority, or **no decision at expiry**) →
  resume with the bot; `WAIT` → a fresh 2-minute window. **`waitCycles` is capped
  at 2** — once two WAITs have been consumed the vote becomes CONTINUE-only, so
  the state cannot loop indefinitely. **No `PAUSED` state exists without a
  deadline, and no unbounded WAIT chain exists at all.**
- **Ranking** (new): derived, not stored — `kingSeatIds: Set` (one entry per
  tied 1st-place player) and `kozSeatId: String?` (**null when last place is
  tied** — and that null is exactly the condition that disables manual Vote
  Kick). Recomputed on every score change; rendered by S43.
- **In-match statistics view** (new): `CLOSED` → `OPEN` (long-press) → `CLOSED`;
  carries the target seat and whose stats are being shown.
- **Create Game configuration** (new): `botDifficulty` (Easy/Medium/Hard/Expert),
  `botPersonality` (the four), `decisionTimerSeconds` (5/10/15/20), `mode`
  (`ROOM` | `RANKED` — **exactly two values, RD28**), and a separate
  `isPrivate`/password flag for Private Ranked (**not a third mode**).
  **Defaults are now CLOSED (RD21): MEDIUM, BALANCED, 15 s.** RD21 also fixes
  the per-phase derivation: Dash/Bidding/Estimates = base + 5, Card Play = base.
  **`mode` is the authority key** — it gates Vote Kick (Ranked-only, RD18),
  voice (Rooms-only, RD22), settlement, and Ranked statistics, so the UI must
  treat it as load-bearing, not cosmetic.
- **Matchmaking search** (new, §4b S55/S56): `IDLE` → `SEARCHING` →
  `MATCH_FOUND` → (transition into match) or `FAILED`/`TIMED_OUT` → `IDLE`.
  The client may only *request* a search; the server derives the eligible pool
  (RD9) — own tier or exactly one below, never two+, and the player can never
  opt up.

---

# PHASE 2 — COMPLETE END-TO-END GAME FLOW

## 2.1 The golden path (happy flow)

```
App launch ──► S01 Splash ──► S02 Login (or straight to Lobby if signed in)
   ──► S05 Lobby
        ├──► Create Room ──► S36 Create Game ──► S06 Code Share ──► S08 Waiting Room
        └──► Join Room ──► S07 Join Dialog ──► S08 Waiting Room
        └──► Create Ranked ──► S36 Create Game (SAME screen, Ranked state)
                                     │  shows current Rank chip + RP (§4b)
                                     ▼
                          S55 Matchmaking Search (server-controlled, RD9)
                                     │  own tier or exactly one below
                                     ▼
                          S56 Failure / Timeout ──► retry or cancel
                                     │
                                     ▼
                                     │
                                     │  (all ready + actor is creator)
                                     ▼
                          [match starts automatically — no Start button]
                                     │
   ┌─────────────────────────────────┴─────────────────────────────────┐
   │  ROUND LOOP (rounds 1..18, +extension rounds)                     │
   │  Every decision below is timed (S37); expiry = a MEDIUM-bot move   │
   │                                                                    │
   │  S12 Dash ──► S13 Auction ──► S14 Confirm* ──► S15 Estimates ──► │
   │  [DONE] ──► S19/S20 Table PLAY (13 tricks) ──► S21 RESOLVING ────►│
   │  S22 round DONE ──► S23 Round Result                              │
   │      │   [S43 King/Koz badges are live throughout, on every seat]  │
   │      └── round < max ──► back to S12 (dealer rotates CCW) ───────►│
   │      └── round = max ──► S24 Final Standings ──► S25 Match Complete│
   └────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
                          S26 Rematch Vote (30 s)
                ├── ALL_YES ──► new match, SAME seats ──► S12
                ├── any NO   ──► S27 "Rematch Declined" ──► Lobby
                ├── timeout  ──► S27 "Not all players accepted" ──► Lobby
                └── leave    ──► Lobby
```
\* `S14 Confirm` runs in **normal rounds only**; fast rounds skip straight from
Dash to Estimates.

**The four amendment exception paths** (detailed in §2.2 and audited for
non-conflict in §2.7):

```
TIMER EXPIRES (any timed decision)          INACTIVE (50 s, still connected)
  └─► S38 Medium bot decides                   └─► S39 bot takes the seat
      └─► timeout counter +1 (match-wide,         └─► match continues
          never resets, PRIVATE to the player)  └─► player returns ──► human
      └─► #15? ──► AUTOMATIC REMOVAL               regains seat, bot stands down
          (NOT a Vote Kick — RD20: no vote,
           no Round-7 gate, no Koz target,
           no cooldown; rejoin = OPEN-1)

DISCONNECT (Rooms)                            MANUAL VOTE KICK (Ranked ONLY — RD18)
  └─► bot takes the seat immediately            └─► only if round ≥ 8
  └─► S41 Disconnect Vote (ONCE)                    AND a single unique Koz
      ├── YES majority ──► S42 pause                  AND not the initiator
      │    (max 2 minutes)                       └─► target excluded; bots excluded
      │    └─► reconnect ──► resume               └─► 2 YES of 3 eligible humans
      │    └─► 2 min expired ──► second vote:    └─► S40 PASSED ──► permanent
      │        CONTINUE / WAIT                       removal, bot keeps the
      │        ├── CONTINUE / no decision            seat, NO rejoin, recorded
      │        │   ──► resume w/ bot                 as KOZ in the 9 statistics
      │        └── WAIT (max 2) ──► another 2 min  └─► S40 FAILED ──► same target
      │            └─► 2 WAIT used ──► CONTINUE only     blocked for 5 rounds
      └── NO / expiry  ──► continue w/ bot
```

**Loop counts the UI must communicate:** the match's own `maxRounds` — **18 for
Full, 10 for Mini (GM1)** — never a hard-coded 18; a fast-round Super
Call or a final-round Sa'ayda adds extension rounds, whose count is capped by
Game Type (**up to 5 for Full, exactly 1 for Mini — GM2/GM3**), each repeating
the trump ladder from its type's own start. A Sa'ayda round still counts toward
the ceiling but scores zero and escalates the next round's multiplier.

## 2.2 Per-screen flow specification

For each screen the owner's nine questions are answered: what the player sees,
why it exists, what they can do, what must be visible, what game state controls
it, what moves forward, what can interrupt, what happens on network loss, and
what happens if another player leaves.

### S01 Splash

- **Sees:** Brand moment (title, tagline, loader). Today: text + spinner, no logo.
- **Why:** Firebase init + session restore; the gate that routes to Login or Lobby.
- **Can do:** Nothing. Wait.
- **Must be visible:** Progress indication that the app is working, not frozen.
- **Controlled by:** `SplashViewModel.ready: AuthUiState?` (null until checked).
- **Forward:** `SignedIn` → Lobby; else → Login. Pops Splash inclusive.
- **Interrupts:** None expected. A long init needs a failure path (see S32).
- **Network loss:** Silent — offline is normal here; restore is what it tests.
- **Player leaves:** N/A.

### S02 Login

- **Sees:** Email + password fields, "Sign in", "Play anonymously".
- **Why:** Player identity. Everything is keyed to the Firebase Auth `uid`.
- **Can do:** Sign in with email, sign in anonymously, proceed once signed in.
- **Must be visible:** Current auth state; the error message on failure.
- **Controlled by:** `AuthUiState` (SignedOut / SigningIn / SignedIn / Error).
- **Forward:** "Enter" → Lobby.
- **Interrupts:** None.
- **Network loss:** Auth fails → `Error: <message>`; retry is manual.
- **Player leaves:** N/A.

### S05 Lobby — *DESIGN COMPLETE, do not redesign*

- **Sees:** The approved Lobby design. Functions: Create Room, Join Room, Quick
  Match (offline), Play vs AI, Profile, Settings, Sign out; on the phase5 branch
  also "Resume match" (a match in progress) and "In room X / Leave room".
- **Why:** The hub; every flow starts and ends here.
- **Can do:** Create a room, join by code, enter an offline match, open Profile
  / Settings, resume or leave a match in progress.
- **Must be visible:** Who is signed in; whether the player is already in a room
  or match (rejoin pointers exist on the profile document).
- **Controlled by:** Auth state; `players/{uid}.currentRoomId` /
  `currentMatchId`.
- **Forward:** Create → S06; Join → S07; Resume → the online match graph.
- **Interrupts:** A match starting in a joined room pulls the player out.
- **Network loss:** Lobby still renders (mostly local); room actions fail with
  reason codes.
- **Player leaves:** N/A.

### S06 Create Room (code share sheet)

- **Sees:** A modal with the generated 6-char room code and a share affordance.
- **Why:** Rooms are invite-only; the code is the only way in.
- **Can do:** Copy/share the code; "Done" → Waiting Room.
- **Must be visible:** The code, large enough to read across a table.
- **Controlled by:** `RoomService.createRoom` → `status: "waiting"`.
- **Forward:** Done → S08.
- **Interrupts:** None.
- **Network loss:** Creation fails with a generic error.
- **Player leaves:** Creator leaving → room auto-closes (last one out).
- **Amendment:** "Create Room" no longer comes straight here. It opens **S36
  Create Game** first; only after configuration is this sheet reached. S36 is
  the new front door for both Create Room and Create Ranked Match.

### S07 Join Room (dialog)

- **Sees:** Code entry field, Join, Cancel.
- **Why:** Entry to a friend's room.
- **Can do:** Type a code, join, cancel back to Lobby.
- **Must be visible:** Per-code failure reasons — `ROOM_NOT_FOUND`,
  `ROOM_FULL`, `ROOM_CLOSED`, empty, generic.
- **Controlled by:** `RoomService.joinRoom` (transaction-guarded, idempotent).
- **Forward:** Success → S08.
- **Interrupts:** None.
- **Network loss:** Join fails with a reason code; the dialog stays usable.
- **Player leaves:** Joining a full room is refused, no state change.

### S08 Waiting Room — *DESIGN COMPLETE, do not redesign*

- **Sees:** The approved design. Functions per the branch code: the room code
  card, the player roster in join order (Host badge, Ready state, "You"), a
  ready toggle, a leave button, and "Waiting for everyone to be ready…" /
  "Starting the match…".
- **Why:** Gather up to 4 players and reach a synchronized start.
- **Can do:** Toggle own ready, copy the code, leave the room.
- **Must be visible:** The code, every member, each member's ready state, who is
  host, and how many seats are still open. **There is no Start button — the
  match fires the instant all seats are ready and the actor is the creator**, so
  the "everyone ready" state must read unmistakably as *starting*.
- **Controlled by:** `rooms/{id}` — `status`, `players[]`, `readyPlayers[]`,
  `creator`. `allReady` = non-empty and every member ready.
- **Forward:** All-ready + creator toggles → match `status: "starting"` → the
  online match graph. Today the room is **polled** (`POLL_INTERVAL_MS`, matching
  the web's 4 000 ms) because `subscribeToRoom` is an unimplemented stub —
  **a real-time listener is a design dependency, not a nice-to-have.**
- **Interrupts:** Another member joining/leaving updates the roster live (once a
  listener exists).
- **Network loss:** Polling stalls; the roster goes stale silently. **Needs an
  explicit stale/error treatment.**
- **Player leaves:** Member leaves → removed from `players` and `readyPlayers`;
  roster updates. Host leaves → creator passes to `players.first()`. Last out →
  room `closed`. **A 2- or 3-player start is valid** — the UI must not imply 4
  are required, and no AI seat is fabricated to fill gaps.

### S12 Bidding — DASH

- **Sees:** A Dash Call decision: "Dash Call" vs "Decline", per player in turn.
- **Why:** A pre-bid declaration of 0 tricks without yet knowing the trump.
- **Can do:** Declare a Dash Call or decline. **Max 2 Dash Callers per round** —
  a third declaration auto-converts to a pass (never rejected).
- **Must be visible:** **The player's own 13-card hand — this is the root-cause
  fix.** Also: how many Dash Call slots remain (0/2, 1/2), whose turn it is, the
  round number, and the current multiplier.
- **Controlled by:** `BiddingPhase.DASH`; `waitingFor` = dealer;
  `MAX_DASH_CALLS = 2`.
- **Forward:** All four decided → `AUCTION` (or `DONE` if all four dashed:
  trump = Sans, everyone scores an explicit 0).
- **Interrupts:** Disconnection (see S30) — bidding stalls on the missing seat.
- **Network loss:** Online bidding is a Firestore write; a lost write surfaces as
  a rejection. The fail-open listener keeps the last good state visible.
- **Player leaves:** **The match stalls — there is no leave-match path.** Other
  players see nothing. This must be designed (S31).

### S13 Bidding — AUCTION

- **Sees:** A tricks slider (4–13), a five-suit picker (Sans, ♠, ♥, ♦, ♣),
  "Bid", "Pass", the current top bid, and live hints ("Matches the top — goes
  With", "Opens the auction", "New top bid").
- **Why:** The dynamic auction that establishes the Caller, the trump, and the
  bid; the heart of the game.
- **Can do:** Raise (more tricks, or same tricks in a stronger suit), or Pass
  (permanent elimination from this round's auction). **Minimum bid is 4.**
- **Must be visible:** **Own hand (root-cause fix).** Current top bid
  (number + suit), who holds it, who is still active, who has passed, whose turn
  it is, the "With" alignment state, round number, multiplier.
- **Controlled by:** `BiddingPhase.AUCTION`; `auctionTop`, `auctionSuit`,
  `auctionBidder`, `activeBidders`, `withPlayers`; `actionHistory`.
- **Forward:** One active bidder left with a top bid → `CONFIRM` for the Caller.
  All four pass → `GeneralPass` → **redeal at ×2 multiplier (capped ×8)**.
- **Invalid actions:** Bids that don't beat the top are disabled with the engine
  reason ("Bid does not beat the current top bid of N <suit>"); below-4 and
  above-13 are out of range.
- **Interrupts:** Disconnection stalls the auction on the missing seat.
- **Network loss:** Fail-open keeps the last good state; writes surface as
  rejections.
- **Player leaves:** Match stalls silently. Design must show "waiting for X" and
  eventually an abandon resolution (S30/S31 — **backend does not exist yet**).

### S14 Bidding — CONFIRM (normal rounds 1–13 only)

- **Sees:** The Caller's lock screen: tricks slider (`floor..13`), suit picker,
  "Lock N <suit>", header "Lock the call (min N)".
- **Why:** Lets the winning Caller finalize or improve the call — keep the bid,
  raise the number, or switch suit.
- **Can do:** Lock, raise the number (**which frees the suit choice — any suit,
  even weaker**), or keep the number (**requires an equal-or-stronger suit; a
  weaker suit at the same number is illegal**).
- **Must be visible:** **Own hand.** The winning bid, the minimum (floor), the
  legal-suit constraint, and that this is the Caller's final decision.
- **Controlled by:** `BiddingPhase.CONFIRM`; `noSuitConstraint`;
  `confirmSuitTooWeak`.
- **Forward:** Locked → `ESTIMATES` (estimators start CCW after the Caller;
  Dash Callers never re-estimate).
- **Interrupts / network / leaves:** as S13.
- **Fast rounds:** this screen **does not exist** — no Confirmation for
  anyone. **The boundary is Game Type-dependent — Full 14–18, Mini 6–10 (GM1) —
  so "fast" is read off the match, never off a literal round number.**

### S15 Bidding — ESTIMATES

- **Sees:** A tricks slider (`0..cap`), "Estimate N" (0 reads "Estimate 0
  (Normal Dash)"), plus constraint hints: "Can't pick N — bids would total 13"
  and "With floor: N (your own auction bid)".
- **Why:** The remaining players commit their trick estimates; the input to
  scoring.
- **Can do:** Estimate 0–cap. Unlimited players may estimate 0 (Normal Dash).
- **Must be visible:** **Own hand.** The Caller's locked bid (the cap), the
  running total vs the 13 Rule, who has estimated, who the **Risk Player** is
  (the last estimator — needs a distinct ⚡ treatment), the trump, and the Call
  Cap. A pre-bid Dash Caller does **not** estimate here and is shown as locked.
- **Controlled by:** `BiddingPhase.ESTIMATES`; `auctionTop` (the cap);
  `forbiddenEstimateFor` (the 13 Rule); `withFloorFor`; `riskPlayerId`.
- **Forward:** All four in → `DONE` → `BiddingOutcome` (trump, caller, With
  players, estimates, dash callers, risk player, leader) → the Table.
- **Invalid actions:** over cap, below a With player's own floor, or the
  forbidden 13-total value — each disabled with the engine reason.
- **Interrupts / network / leaves:** as S13.

### S16 Bidding — fast round (the Quick Round variant)

- **Sees:** A single-pass estimate per player with the **forced trump shown and
  locked**, on the ladder Sans → Spades → Hearts → Diamonds → Clubs starting at
  the match's first Quick Round. **Full (rounds 14–18): 14 = Sans, 15 = Spades,
  16 = Hearts, 17 = Diamonds, 18 = Clubs. Mini (rounds 6–10): 6 = Sans, 7 =
  Spades, 8 = Hearts, 9 = Diamonds, 10 = Clubs** (GM1). The screen is identical
  in both types — only the round numbers on the ladder move, and the composable
  must read them off the match, never off a literal 14.
- **Why:** Closes the game's second half; no auction, no Confirmation. **In Mini
  the second half is rounds 6–10, so the same closure arrives sooner** — the
  screen's job is unchanged.
- **Can do:** State one final number. No raising, no passing back and forth.
- **Must be visible:** **Own hand.** The forced trump (prominent and
  non-editable), the round number and that it is a fast round, who has bid, and
  that the **first** bidder of the highest number becomes Caller with every
  other player on that number becoming With.
- **Controlled by:** `initFastRound` (starts at `ESTIMATES`, `auctionTop = 13`
  sentinel = no cap, `declaredTrump = gameType.fixedTrumpFor(round)` — the trump
  ladder is type-derived, and the round-6 case that used to crash on a negative
  modulus is fixed by the `Math.floorMod` parameterization).
- **Forward:** All four in → DONE. A bid ≥ 8 is a **Golden Super Call** (S17).
- **Interrupts / network / leaves:** as S13.

### S17 Bidding — Super Call (variant, bid ≥ 8)

- **Sees:** A distinct treatment for an 8+ bid — it is a dramatic, match-shaping
  event and deserves its own visual weight.
- **Why:** In normal rounds it forces the Confirmation Phase; in fast rounds it
  **cancels the forced trump, lets the bidder choose it, and extends the match
  by one round.**
- **Can do:** In a fast round the Super Caller picks any suit; only players who
  bid **before** the Super Caller must re-estimate (later bids stay).
- **Must be visible:** **Own hand.** That the forced trump was overridden, the
  new trump, and that the match is now longer (18 → 19 → …).
- **Controlled by:** `Role.SUPER_CALL`; `noSuitConstraint`;
  `fastSuperResolved`; `extendMatchRounds` (`SUPER_CALL` / `SAAYDA` reasons).
- **Forward:** Trump chosen → estimates resume or complete.
- **Interrupts / network / leaves:** as S13.

### S18 Bidding — General Pass / redeal

- **Sees:** A transition surface: the auction collapsed (all four passed) and the
  round is being redealt.
- **Why:** An all-pass auction has no Caller; the rules resolve it by redealing
  at a doubled multiplier rather than forcing a call.
- **Can do:** Nothing but wait (and watch the multiplier climb).
- **Must be visible:** That nobody called, that a redeal is happening, and the
  new multiplier (×2, ×4, ×6, ×8 cap — the same ladder as Sa'ayda).
- **Controlled by:** `EmitResult.GeneralPass(state, doubledMultiplier)`; the
  multiplier is `min(roundMultiplier * 2, 8)`.
- **Forward:** Redealt hand → auction restarts.
- **Interrupts / network / leaves:** as S13.

### S19 Game Table — PLAY (your turn)

- **Sees:** The trick area (played cards), the four players, the tally, and your
  hand — the only screen today that renders one.
- **Why:** Trick-taking; the actual play of the game.
- **Can do:** Tap a legal card to play it. **Illegal cards are disabled and
  dimmed (45% alpha) rather than hidden** — follow-suit is taught visually.
- **Must be visible:** **Your hand, always, for the whole round** (13 cards
  shrinking to 0). Whose turn it is, the trump, the round number, the trick
  number (`Trick N/13`), tricks won per player, cards remaining per player, the
  led suit, the last trick, and each player's public voids (AVOID tags — the
  suit itself is never revealed). Round totals and estimates should be
  reviewable.
- **Controlled by:** `TablePhase.PLAY`; `turn`; `legalCards` (must follow if
  able; the leader may play anything); `voids`.
- **Forward:** Each play advances the turn CCW. Fourth card → `RESOLVING`.
- **Invalid actions:** `NOT_PLAY_PHASE`, `NOT_THIS_SEATS_TURN`,
  `ILLEGAL_CARD` ("Follow <suit>").
- **Interrupts:** Trick resolution (900 ms highlight) and round DONE.
- **Network loss:** Fail-open — the last good document stays visible and the
  local game remains playable; the reconnect overlay marks the screen.
- **Player leaves:** Match stalls. **Design must show "waiting for X"** — and per
  the amendment the stall is now bounded: an absent seat becomes **S39
  bot-controlled** (50 s inactivity) or **BOT_DISCONNECTED** (real disconnect),
  so the table never waits forever.
- **Amendment additions:** (a) **S37 timer ring** on your own turn — Card Play
  uses the **selected** value (no +5 s here); expiry plays a MEDIUM-bot legal
  card and shows S38. (b) **S43 King/Koz badges** on all four seats, recomputed
  on every score change. (c) **S44 Quick Stats** is reachable by long-pressing
  any opponent's avatar; it must not compete with the card-tap gesture. (d) If
  this seat is currently bot-controlled (S39), the ring is the bot's, not yours —
  the UI must not invite you to play.

### S20 Game Table — PLAY (opponent's turn)

- **Sees:** The same table with the hand dimmed/disabled and a waiting banner.
- **Why:** Keeps the player oriented and informed between their turns.
- **Can do:** Observe. Possibly review scores/last trick. **Amendment: long-press
  an opponent's avatar for S44 Quick Stats** — this is the natural moment for it.
- **Must be visible:** **Whose turn it is** (the single most-asked question),
  the cards already played this trick, and **the S37 countdown ring on the
  acting opponent** — the amendment resolves the earlier open question: yes, the
  timer is shown, for every player, on every timed decision.
- **Controlled by:** `turn != userSeat`; in online play `Table.userSeat` is
  always your own seat, so opponents never play from your device.
- **Forward:** Turn returns to you → S19.
- **Interrupts / network / leaves:** as S19.

### S21 Game Table — RESOLVING (trick complete)

- **Sees:** All four cards on the table, the winner highlighted, then it clears.
- **Why:** Confirms who took the trick before the next begins.
- **Can do:** Watch. **The design must not require a tap** — resolution is
  automatic after ~900 ms today, and on the online path the document drives it.
- **Must be visible:** The winner, and **the hand must stay visible** (today it
  vanishes — a defect to design out).
- **Controlled by:** `TablePhase.RESOLVING`; `currentWinnerId`; `resolveTrick`.
- **Forward:** Trick < 13 → next trick, winner leads. Trick 13 → `DONE`.
- **Interrupts / network / leaves:** as S19.

### S22 Game Table — round DONE (trick 13)

- **Sees:** The final trick resolved; the round's tricks-won are final.
- **Why:** The hand-off between play and scoring.
- **Can do:** Wait for the round result.
- **Must be visible:** Final tricks per player this round; the hand count at 0.
- **Controlled by:** `TablePhase.DONE` after `trickNo >= 13`.
- **Forward:** Scoring runs → round result (S23).
- **Interrupts / network / leaves:** as S19.

### S23 Round Result / Round Standings

- **Sees:** Per-round scoreboard — each player's estimate vs tricks won, the
  delta, the running total, role outcomes (Caller/With/Risk/Dash), and any
  Sa'ayda or extension notice.
- **Why:** Makes the scoring legible; the feedback loop that teaches the game.
- **Can do:** Review, then continue. On the online path today it is read-only
  with a "Waiting for the next round…" footer.
- **Must be visible:** Each player's bid and actual, the round score with the
  delta, the running total, who won/lost, the multiplier if escalated, and the
  round number out of max (which may now exceed 18 after an extension).
- **Controlled by:** `MatchUiState.RoundStandings(standings, round,
  isLastRound)`; the match document advancing to the next round.
- **Forward:** Not last round → next auction (dealer rotates CCW).
  Last round → Final Standings (S24).
- **Interrupts:** A slow document advance — the waiting state must be explicit.
- **Network loss:** Last good standings stay visible; the reconnect overlay marks.
- **Player leaves:** Stalls the advance; needs the S39/S42 treatment.
- **Amendment:** this screen is no longer the only place ranking appears — the
  **S43 badges are live during play**, so by the time the player arrives here the
  King/Koz identities are already known. Its job becomes **confirm and explain**:
  show the 1st/2nd/3rd/Koz rows (which the match document does **not** persist
  today — §0.6-V6 — they must be derived from `finalScores`) and, where
  applicable, the Vote-Kick-Koz marker.

### S24 Final Standings

- **Sees:** The match result — ranked players, the King(s), final scores.
- **Why:** The match's conclusion and the record of the session.
- **Can do:** Review; then rematch or leave.
- **Must be visible:** The winner(s) — **ties are co-Kings, not a tiebreak**;
  every player's final total; the last-round deltas; a `Sa'ayda` badge where it
  applies; and any round-extension note.
- **Controlled by:** `MatchUiState.MatchComplete`; `matches/{id}.status ==
  "complete"`; `winnerIds`; `finalScores` (keyed by seat).
- **Forward:** Rematch vote (S26) or leave → Lobby.
- **Interrupts:** The rematch panel opens automatically on completion (per the
  web UX contract).
- **Network loss:** Standings are derived from the synced document; fail-open.
- **Player leaves:** Others continue to the vote without the leaver.

### S25 Match Complete modal

- **Sees:** A celebration/presentation layer over the standings — crown, King
  label, scoreboard, summary, extension note, and the rematch panel.
- **Why:** Gives the match a clear ending beat before the vote.
- **Can do:** Dismiss to the standings, vote, or return to Lobby.
- **Must be visible:** Same as S24, plus a clear "the match is over" signal.
- **Controlled by:** `matchDoc.status === "complete"` — **a renderer only; no
  winner/score recalculation happens in the UI.**
- **Forward:** Rematch vote panel (S26); "Return to Lobby".
- **Interrupts / network / leaves:** as S24.

### S26 Rematch Vote panel

- **Sees:** "Play Again?", a 30-second countdown, YES/NO buttons, the tally
  ("0/4"), your own locked vote, and the list of players still to vote.
- **Why:** Reuses the exact same seats for a new match without re-lobbying.
- **Can do:** Vote YES or NO **once — a cast vote is locked; a conflicting tap
  is refused (`VOTE_LOCKED`).**
- **Must be visible:** The authoritative countdown (derived from the document's
  `createdAt`, never a client clock), the vote tally, who has not voted yet, and
  your own vote state.
- **Controlled by:** `matches/{id}/rematchVote/current` — statuses `OPEN`,
  `ALL_YES`, `FAILED_NO`, `FAILED_TIMEOUT`, `NEW_MATCH_CREATED`. **Any NO ends
  it immediately; any seated client may resolve a timeout (host-less).**
- **Forward:** `ALL_YES` → a new match with **identical seat assignments** →
  S12. `FAILED_NO` → "Rematch Declined" → Lobby. `FAILED_TIMEOUT` → "Not all
  players accepted" → Lobby.
- **Interrupts:** A reload mid-vote must show the already-resolved state, not a
  stale default.
- **Network loss:** The vote document is authoritative; reconnection resyncs.
- **Player leaves:** The vote continues; a missing seat's vote never arrives,
  which is what timeout resolves.

### S27 Rematch — outcome states

- **Sees:** One of three terminal readouts: "Rematch Accepted / Starting New
  Match", "Rematch Declined / Returning to Lobby", or "Not all players accepted".
- **Why:** Unambiguously closes the vote before navigating.
- **Can do:** Wait for the transition (auto-navigation, per the web contract).
- **Must be visible:** The outcome and where the player is going next.
- **Controlled by:** the vote's terminal statuses; `newMatchId` on
  `NEW_MATCH_CREATED`.
- **Forward:** New match or Lobby.
- **Interrupts / network / leaves:** the outcome is persisted, so it survives.

### S28 Loading / match starting

- **Sees:** An overlay or full-screen state — today just "Connecting…".
- **Why:** Covers subscription latency and the match's `starting` status.
- **Can do:** Nothing (or cancel, if the design offers it).
- **Must be visible:** That something is happening, and how to abandon it.
- **Controlled by:** `MatchUiState.Connecting`; match `status: "starting"`.
- **Forward:** First snapshot → Bidding (or Table, if rejoining mid-round).
- **Interrupts:** Must not hide the screen underneath permanently.
- **Network loss:** Hands off to S29/S32.

### S29 Reconnecting (transport retry)

- **Sees:** A **non-blocking overlay** — "Reconnecting… / You're still in the
  match" — over the last good screen.
- **Why:** Firestore drops are common on mobile; the player should keep their
  place, not stare at an error.
- **Can do:** Keep playing from the last known state (fail-open by design).
- **Must be visible:** That connectivity is degraded but the match is alive.
- **Controlled by:** `OnlineMatchViewModel.reconnecting`; exponential backoff
  250 ms → 4 s; `UNRECOGNIZED` errors are treated as non-retryable.
- **Forward:** Snapshot succeeds → overlay clears.
- **Interrupts:** Nothing — this is the interruption.
- **Network loss:** This IS the network-loss state. A non-retryable error → S32.
- **Player leaves:** Others see the seat go quiet (no presence — S30).

### S30 Disconnected (player presence lost)

- **Sees:** **Does not exist today.** This is the single largest design + backend
  gap in the program.
- **Why:** The state machine declares `Disconnected` with six inbound transitions
  and the architecture docs specify a heartbeat/staleness model, but **no
  presence, no staleness computation, and no rejoin flow were ever built.**
- **Can do:** *(to be designed)* Return to the app → rejoin the same seat.
- **Must be visible:** *(to be designed)* That you have been dropped, that your
  seat is being held, and how to get back.
- **Controlled by:** `players/{uid}.lastSeenAt` exists but is **never refreshed
  on an interval and nothing reads it.** Design must specify the heartbeat and
  the staleness threshold.
- **Forward:** Rejoin → the same seat (seats are immutable for a match's life).
- **Interrupts:** Everything.
- **Network loss:** This state IS network loss.
- **Player leaves:** **Nothing detects it.** The match simply stalls.
- **Amendment — the policy is now decided:** a disconnected seat is
  **immediately bot-controlled** (never stalled), and **in Rooms** the S41
  Disconnect Vote decides between pausing (S42) and continuing with the bot. So
  this screen's "to be designed" is narrower than it was: the open question is no
  longer *whether* the seat is held — it is — but the heartbeat mechanism and the
  rejoin presentation.

### S31 Waiting for a disconnected player

- **Sees:** **Does not exist today.** No pause, no forfeit, no notification —
  the match hangs on the missing seat's turn and the other three learn nothing.
- **Why:** Required for any playable 4-human match; the current playtest could
  not have recovered from a single drop.
- **Can do:** *(to be designed)* Wait, possibly vote to continue/forfeit,
  possibly substitute a bot.
- **Must be visible:** Who is missing, how long they have been gone, a grace
  countdown, and what happens when it expires.
- **Controlled by:** **Does not exist in the backend.** The architecture's
  `ABANDONED` state ("pause and wait" for short absence vs "end match with
  partial results" for long absence) is design-only. **This is the roadmap's
  biggest open build.**
- **Forward:** Player returns → resume. Timeout → forfeit/abandon resolution.
- **Interrupts / network / leaves:** this state is defined by them.
- **Amendment — this is now S42, and it is no longer automatic:** a match pauses
  for a disconnected player **only** when a Rooms-only Disconnect Vote returns a
  YES majority (S41). Otherwise the bot simply continues and this state is never
  entered. The grace countdown this screen originally needed is therefore the
  **S41 vote countdown**, not a stall timer.

### S32 Match Failed (terminal sync failure)

- **Sees:** "Couldn't keep this match in sync." + the reason + "Back to lobby".
- **Why:** A non-retryable listener failure must have an exit (permission
  denied, unauthenticated, not-found, failed-precondition).
- **Can do:** Read the reason; leave to Lobby.
- **Must be visible:** A human-readable reason, not a raw code.
- **Controlled by:** `MatchUiState.Failed(message)`; `terminalError`.
- **Forward:** Lobby only.
- **Interrupts:** Terminal.
- **Network loss:** This IS a network outcome.
- **Player leaves:** Others are unaffected; the leaver's seat goes quiet.

### S33 Not In Match

- **Sees:** "You're no longer in this match."
- **Why:** Backing out of a room whose match already ended is an ordinary path,
  not an error — the UI must not scold the player.
- **Can do:** Acknowledge; auto-return to Lobby.
- **Controlled by:** `MatchUiState.NotInMatch` → `onLeft()` fires as an effect.
- **Forward:** Lobby.
- **Interrupts / network / leaves:** by definition.

### S34 Invalid action / rejection feedback

- **Sees:** Inline, non-blocking feedback: disabled/dimmed controls plus a reason
  line in error colour. Today the engine's own reasons are shown verbatim.
- **Why:** Teaches the rules without a modal interrupting every mistake.
- **Can do:** Read the reason; adjust the input.
- **Must be visible:** Which control is illegal and **why** — every reason must
  be human-readable and on-brand, not a raw enum like `STALE_GAME_STATE`.
- **Controlled by:** `rejection` StateFlow on Bidding + Table; `canSubmit` /
  `canPlayCard` legality projections.
- **Forward:** Corrected input.
- **Interrupts:** Must never block; it guides.
- **Network loss / leaves:** a stale write surfaces here as a rejection.

### S35 Push-to-Talk — *DOCUMENTED ONLY, NOT FOR THIS PHASE*

- **Sees:** *(to be designed later)* A press-and-hold microphone control.
- **Why:** Casual-room voice chat, post-UI phase.
- **Can do:** **Press and hold → microphone active; release → microphone
  inactive.** That is the entire interaction contract to remember.
- **States to design eventually:** idle, pressed/active, speaking indicator,
  muted/denied (permission not granted), unavailable (not a casual room),
  network-degraded.
- **Placement candidates (decision deferred):** the Game Table (per-round talk)
  and the Waiting Room (pre-game). **Not the Lobby, not menus.**
- **Controlled by:** nothing yet — no implementation exists and none starts
  until the UI/UX plan and Figma design are complete, per the owner decision.
- **Amendment — scope now fixed:** **Rooms only, never Ranked**, in exactly the
  two places above. So the state list gains a hard `mode == ROOM` precondition:
  in a Ranked match the control is not present at all, and "unavailable" is a
  Room-only state (mic permission denied), never a Ranked one.

---

## 2.2b Amendment surfaces — per-screen flow specification (S36–S44)

The same nine questions, answered for the nine new surfaces. These are the
specification the Figma designer draws against and the Android agent implements
against.

### S36 Create Game — *the shared configuration screen*

- **Sees:** One configuration screen reached from **both** "Create Room" and
  "Create Ranked Match" — there is exactly one configuration surface, not two.
  **Five** setting groups. **Game Type** (Full / Mini) and **Calculation**
  (Normal / Classic) sit **above** the three original groups — **Bot
  Difficulty** (Easy / Medium / Hard / Expert), **Bot Personality** (the four
  existing personalities, presented with their names and one-line flavour),
  **Decision Timer** (5 / 10 / 15 / 20 seconds) — plus a live summary of what
  the timer means per phase.
- **Why:** Configures the game before creation. The amendment makes this the
  front door for both modes so configuration is never duplicated. The 2026-10-06
  GM amendment adds the two top groups because **Game Type and Calculation are
  match-scoped, not player-scoped** — they change the round count, the Quick
  Round boundary, the escalation cap, and (in Ranked) the RP delta, so they
  belong on the one screen that creates the match.
- **Can do:** Pick one value in each of the five groups; change any of them
  freely before confirming; back out.
- **Must be visible:** The five groups and the **derived per-phase timers**:
  "Card Play Ns · Dash / Bidding / Estimates N+5 s" — the +5 rule must be
  visible here, not discovered mid-match. Which mode the player is configuring
  for (Room or Ranked), and — for Ranked — that voice and the pause vote are not
  part of this match. **In the Ranked state: the player's current Rank chip
  (Tier + Division + Arabic title + RP), progress to the next rank, and any
  Ranked-specific restriction (RD9's pool rule, stated in plain language the
  player can act on — "you will be matched in your tier or the one below").**
  **Defaults are fixed and pre-filled (RD21 + GM7): FULL / NORMAL / MEDIUM /
  BALANCED / 15 s.** **Private/Password Ranked is a toggle on this same screen
  — never a third mode (RD28).**
  **The Game Type choice must state its consequences in one line each, not as
  raw numbers** — Full: "18 rounds · Quick Rounds from 14 · up to 5 extensions";
  Mini: "10 rounds · Quick Rounds from 6 · **one extension only**" (GM1–GM3).
  **Mini is available in Ranked too** (GM5) — do not disable or hide it in the
  Ranked state, and do not imply it is a casual-only format; the only Ranked
  difference is the RP reward, shown at match end, not here (GM6).
- **Controlled by:** new configuration state (§1.3): `gameType`,
  `scoringMode`, `botDifficulty`, `botPersonality`, `decisionTimerSeconds`,
  `mode`, `isPrivate`/password.
- **Forward:** Confirm → Room: creates the room and goes to S06 (code share) →
  S08. Ranked: goes to **S55 Matchmaking Search** (server-controlled pool, RD9)
  → match. Private Ranked: skips the search and goes straight to a shareable
  code/invite, cross-tier allowed (RD6).
- **Interrupts:** None — this is pre-match.
- **Network loss:** Create fails with a reason code; the selections must persist
  so the player does not re-pick them.
- **Player leaves:** No game exists yet; backing out is harmless.
- **Designer note:** the four personalities and four tiers are **existing,
  reusable AI infrastructure** (§0.6-V1) — present the existing names, do not
  invent new AI behaviour, and do not imply the bots are configurable beyond
  these five knobs. **The same applies to the two new groups: do not invent a
  third Game Type or a third Calculation mode, and do not invent Mini-specific
  rules** — Mini is Full with a shorter round count and an earlier Quick Round
  boundary, nothing else (GM1). Present "Normal / Classic" as the two
  calculation modes they are; **Classic's escalation cap is ×2 vs Normal's ×8**,
  which is the only player-visible consequence and may be stated as one line.
- **Defaults (D5 + GM7 — FINAL OWNER DECISION):** **Game Type = FULL**,
  **Calculation = NORMAL**, **Bot Difficulty = MEDIUM**, **Bot
  Personality = BALANCED** ("Steady"), **Decision Timer = 15 seconds**. These are
  the pre-selected values every time the screen opens; the player may change any
  of them before creating the game, and the live per-phase summary must reflect
  the selection — under the defaults it reads Dash 20 s · Bidding 20 s · Estimates
  20 s · Card Play 15 s. BALANCED is also the **fallback personality for every
  bot created without an explicit one**, at any tier (§0.5-A).

### S37 Decision Timer ring — *overlay on S12–S15 and S19/S20*

- **Sees:** A countdown attached to the acting decision — a ring around the
  active player, or a bar in the turn banner. Visible for **every** player's
  every timed decision, including your own.
- **Why:** Bounded decisions keep a 4-human match moving; the amendment makes
  the timer a real rule, not a convenience.
- **Can do:** Watch it. Nothing the player does dismisses it.
- **Must be visible:** Time remaining, and the moment it expires. The phase the
  timer belongs to (so the +5 rule is legible in-play: a 20 s ring in Bidding
  and a 15 s ring in Card Play under a 15 s configuration).
- **Controlled by:** new timer state: `IDLE` → `RUNNING` → `EXPIRED` →
  `BOT_DECIDED`. Durations: Card Play = `decisionTimerSeconds`; Dash / Bidding /
  Estimates = `decisionTimerSeconds + 5`.
- **Forward:** A decision before expiry clears it. Expiry → the MEDIUM bot
  decides → S38.
- **Interrupts:** This *is* the interrupt mechanism.
- **Network loss:** **The countdown must not become a local animation.** Like
  the rematch-vote deadline it is derived from a server timestamp so all four
  clients agree on expiry; a client clock skew must not hand one table a
  different bot decision than another. This is the hardest single requirement
  in the amendment (see §2.6).
- **Player leaves:** The timer belongs to the acting seat; if that seat goes
  bot-controlled the timer continues governing the bot's pace.

### S38 Timeout — bot made the decision

- **Sees:** A clear, non-blocking acknowledgement that **the game decided for
  the player**: what was decided (the bid, the estimate, or the card played),
  that the MEDIUM bot decided it, and — for the player's own timeouts — their
  running timeout count toward 15.
- **Why:** A bot-made move on a human's behalf must never be silent; the player
  must be able to tell their own decisions from the bot's.
- **Can do:** Acknowledge and continue. **It is not undoable** — the decision is
  final, as if the player had made it.
- **Must be visible:** What was decided, by whom, and the consequence (the
  count). **Visibility rule (D6 — CLOSED): only the player's OWN count is ever
  shown — to that player alone.** Opponents never see a count, never see a
  progress-toward-15 indicator, and never learn from the UI that a bot decided
  for someone else beyond the move itself. A player's own count becomes more
  prominent as it approaches 15.
- **Controlled by:** timer `EXPIRED` → `BOT_DECIDED`; the per-player
  match-wide timeout counter increments.
- **Forward:** The decided action is applied exactly as a human action would
  be; the game proceeds normally.
- **Interrupts / network / leaves:** the decision is persisted like any other,
  so it survives.
- **Designer note:** distinguish this from **S39** — a timeout is a *single
  missed decision*; inactivity is a *state of being away*. They share nothing
  but the bot.

### S39 Inactive — bot takeover (50 s)

- **Sees:** A persistent, unobtrusive indicator on the affected seat — the
  avatar becomes bot-marked and a banner explains the seat is bot-controlled.
  The other three players see it immediately.
- **Why:** A connected-but-away player should not stall the match, yet must not
  be punished for stepping away.
- **Can do:** **The inactive player can return at any time and reclaim the
  seat** — the bot stands down and the human resumes. This is the defining
  difference from Vote Kick.
- **Must be visible:** Which seat is bot-controlled, and that **return is
  possible**. The inactive player needs no "you are inactive" screen — their
  return is the signal.
- **Controlled by:** seat control `HUMAN` → `BOT_INACTIVE` after **50 seconds**
  of genuine inactivity, using the **game's configured bot difficulty and
  personality** (not MEDIUM — MEDIUM is only for timeout decisions).
- **Forward:** Bot plays the seat until the human returns → `HUMAN`.
- **Interrupts:** The bot's decisions are real moves; they interleave normally.
- **Network loss:** **Inactivity is not network loss.** A reconnecting player
  (S29/S30) is inactive-but-returning too — the design must not double-punish a
  dropped player as "away". Detection requires a genuine presence/activity
  mechanism (heartbeat + input events), not a UI timer.
- **Player leaves:** Leaving the app entirely is the disconnect path (S30), not
  this one.

### S40 Vote Kick panel — *Ranked ONLY, manual only*

- **Sees:** A modal vote: the target is named, the **reason/purpose is
  presented** (removing a player intentionally harming or disrupting the game),
  YES / NO buttons, the live tally, and the eligible-voter count.
- **Why:** A last-resort protection **for a Ranked match** — griefing and abuse,
  **not** normal gameplay mistakes and **not** auto-detected.
- **Ranked ONLY, manual ONLY (RD18 — this corrects the frozen Batch 1 heading,
  which read "manual + automatic … in both Rooms and Ranked").** The panel
  **never appears in a Room match**; the entry point is absent, not disabled, and
  the `mode` field is the gate (RD28). **The frozen text's "automatic" flavour —
  raised by the game on the 15th timeout — is a different mechanism entirely
  (RD20): it has no panel, no vote, no Round-7 gate, no Koz target, and no
  cooldown.** It is specified at §0.5-C and S38, never here. The two systems
  share no state, no UI, and no test.
- **Can do:** An eligible player votes YES or NO, **once, and it cannot be
  changed** (the rematch vote's immutability rule). The **target does not vote
  and sees the panel from the receiving side**.
- **Must be visible:** The target, the reason, the tally (e.g. "2 / 3"), who is
  eligible, who has voted, and **why the vote may be unavailable**: fewer than 7
  complete rounds, or **no unique Koz** (last place is tied), or the 5-round
  cooldown on that target still live. The unavailable state must be explainable
  in-place, not just absent.
- **Controlled by:** gates: **`mode` = RANKED** **and** `completedRounds >= 7`
  **and** a single unique Koz **and** initiator ≠ target **and** no live cooldown
  on that target. Majority of **eligible human** voters (4 players − 1 target = 3
  eligible; **2 YES passes**). **The unique-Koz target is frozen for the vote's
  duration — a vote cannot change its target mid-vote (OPEN-2).**
- **Forward:** PASSED → the target is **permanently removed**: the bot takes
  the seat, the removed player **cannot rejoin**, and the removal is recorded as
  a **Koz outcome** in the match record and in that player's 9 career statistics
  (D10, RD23). FAILED_NO / FAILED_TIMEOUT → the match continues with the human
  still seated **and a 5-round cooldown is written against that same target only
  (D8)** — e.g. a failure in Round 8 blocks that target until Round 13.
- **Bots never vote (D7 — CLOSED):** a bot-controlled seat gets **no vote slot**
  and counts toward **no** denominator. Eligibility is recomputed over the human
  players only — if a seat has gone bot, a 4-player table may have as few as 2
  eligible human voters, and the majority threshold is taken over that set. The
  UI must show the *human* eligible count, never a bot-padded one.
- **Interrupts:** The vote is **mid-match** — this is the decisive difference
  from the rematch vote, which only exists on a completed match. The table
  underneath must remain visible. **Whether the match state advances while the
  vote is open is OPEN-2 (owner decision, §2.8)** — the frozen Batch 1 text
  asserted it must not; RD18 does not establish it, so it is recorded rather
  than assumed.
- **Network loss:** The vote document is the source of truth; reconnection
  re-syncs the tally.
- **Player leaves:** A voter leaving becomes bot-controlled; a **bot never votes
  and never inherits a direction (D7)** — the seat simply leaves the eligible
  set, and the majority is recomputed over the remaining human voters.

### S41 Disconnect Vote — *Rooms only*

- **Sees:** A modal with **exactly one question: "Pause game until the player
  connects"**, YES / NO, the live tally, and a countdown. The disconnected
  player does not see it (and would not vote).
- **Why:** In a Room, friends can decide to wait for the fourth; in Ranked the
  question does not exist and the bot simply continues.
- **Can do:** Vote YES or NO, once — **human voters only (D7)**. **The vote
  appears exactly once per disconnect event** — the panel must not re-ask while
  the same disconnect persists.
- **Must be visible:** The question verbatim, the tally (2 of 3 needed), the
  time remaining, and that **no decision means NO**.
- **Controlled by:** seat control `BOT_DISCONNECTED`; vote statuses `OPEN` →
  `PASSED` / `FAILED_NO` / `FAILED_TIMEOUT`, where **both failure states mean
  "continue with the bot"**. A per-event flag suppresses a repeat.
- **Forward:** PASSED → S42 (a **2-minute bounded** pause). FAILED_NO or
  FAILED_TIMEOUT → continue with the bot; the disconnect vote flag stays set so
  a re-ask cannot happen.
- **Interrupts:** The bot has **already** taken the seat when the vote appears —
  the match does not stall to ask.
- **Network loss:** As S40.
- **Player leaves:** The disconnected player is the subject, not a voter.

### S42 Paused — waiting for reconnect — *Rooms only*

- **Sees:** A full-table paused state: the match is held, the match-clock and
  all timers are stopped, and the table shows **who is being waited for** and
  that the bot is not playing. A **2-minute countdown** is visible, because the
  pause is bounded.
- **Why:** The YES-majority outcome of S41 — the table chose to wait.
- **Can do:** Wait. The remaining players may still review the table and open
  S44 Quick Stats, but no game action advances.
- **Must be visible:** That the match is **paused, not over**; who is missing;
  that it resumes automatically on their reconnect; and **the time left before
  the next decision vote**.
- **Controlled by:** match state `RUNNING` → `PAUSED_AWAITING_RECONNECT` with a
  `pauseDeadline` 2 minutes from entry.
- **Forward:** The disconnected player reconnects → the bot stands down → the
  human regains the seat → the match resumes from the exact same position.
  **Otherwise the `pauseDeadline` expires → the second vote opens
  automatically.**
- **Second vote (D9 — FINAL OWNER DECISION):** a **required** `CONTINUE_OR_WAIT`
  vote — "Continue playing with the bot, or wait longer for the player?" Majority
  CONTINUE → resume with the bot. **No decision before expiry → CONTINUE.**
  Majority WAIT → a fresh 2-minute window opens and the vote repeats at its end —
  **but only up to twice.** **Maximum WAIT cycles = 2**: after the second WAIT has
  been consumed the vote becomes **CONTINUE-only**, so the match always resumes
  with the bot. **There is no path in the design that leaves the match paused
  indefinitely, and no unbounded `2 min → WAIT → 2 min → WAIT` chain.**
- **Interrupts:** This is the interrupt.
- **Network loss:** If the remaining players also drop, the pause and its
  deadline persist on the document.
- **Player leaves:** If the disconnected player returns *after* the table has
  voted CONTINUE, the bot keeps the seat — the human's rejoin is handled as a
  reconnect-to-bot-held-seat, and the hand-back is a design detail to resolve in
  Phase 3 (the reconnect path already exists via `restoreHand`).

### S43 King / Koz badges — *persistent overlay on S19–S23*

- **Sees:** A crown on **every** player currently in 1st place, and a Koz badge
  on the player currently in last — **on the Game Table, during play, on every
  seat, updated live as scores change.**
- **Why:** Live ranking makes the match's stakes visible while it is being
  played, and it is the input to Vote Kick eligibility.
- **Can do:** Nothing — they are informational. They must not be tappable in a
  way that competes with card play (tapping an avatar could open S44; long-press
  definitely does).
- **Must be visible:** **Multiple Kings when 1st place is tied** — one crown per
  tied player, never a tiebreak crown. Ties at other positions render as ties.
  When last place is tied, **there is no Koz badge and manual Vote Kick is
  unavailable** — and the Vote Kick entry point must say why ("no unique last
  place"), rather than silently disappearing. Per the owner's instruction this
  rule appears here (Game Table UI), in the ranking rules (§0.5-F), in Vote Kick
  eligibility (S40), in the state model (§1.3), and in the testing gate (Phase 9).
- **Controlled by:** derived ranking state: `kingSeatIds: Set` and
  `kozSeatId: String?` (null when last place is tied), recomputed on every score
  change from the same scores the backend already holds.
- **Forward:** At match end these resolve into the S24 1st/2nd/3rd/Koz rows;
  tied first-place players are all Kings.
- **Interrupts / network / leaves:** Derived from the synced document, so they
  converge on all clients.
- **Designer note:** the backend **already** supports multiple winners
  (`winnerIdsMatchFinalScores()` treats all max-score seats as winners —
  §0.6-V6), so multiple Kings are consistent with the rules as built.

### S44 Quick Stats popover — *overlay on S19–S23*

- **Sees:** A compact popover / bottom sheet showing the **short** version of
  the long-pressed player's Ranked statistics — not the full Profile.
- **Why:** Lets a player size up an opponent without leaving the match.
- **Can do:** **Long-press** a player's avatar or player area to open it; tap
  outside or swipe down to dismiss.
- **Must be visible:** The selected player's name and **exactly the six short
  values (RD23): Rank, Games Played, King %, 2nd %, 3rd %, Koz %** — never the
  full nine, never a match history (there is no player-facing match history in
  MVP), read from the same server-side source as S03.
- **Controlled by:** overlay state `CLOSED` → `OPEN(targetSeat)` → `CLOSED`.
- **Forward:** Dismiss returns to the table exactly as it was.
- **Interrupts:** **It must never block a legal card play or swallow the play
  gesture.** If it is open on the player's own turn, the hand stays playable;
  if the long-press lands on a card, the card wins the gesture. It must be
  openable during every gameplay phase, including bidding — but it is an
  overlay, never a destination.
- **Network loss:** Stats are read from the player-statistics source; if it is
  unreachable the panel shows an explicit "unavailable" state, never zeros.
- **Player leaves:** Showing stats for a player who has left the match is
  harmless.
- **Hard prerequisite — UNBLOCKED by RD11 (2026-10-05):** `players/{uid}` was
  **owner-read-only** under the frozen rules (§0.6-V4), which would have made
  this screen unreadable for another player. **RD11's lightweight Cloud
  Functions + Firestore authority layer supplies the server-side read path**
  (the client reads a settled, server-owned statistics surface — it never writes
  one), so the screen is unblocked **without touching `firestore.rules`**. The
  prerequisite is now the statistics accumulator itself (code roadmap S55), not a
  rules decision.

## 2.3 Exceptional-state summary matrix

The flows above converge on this matrix — the columns the design cannot skip:

| Exception | Where it bites | Today's behavior | Required design |
|---|---|---|---|
| Network drops mid-round | S19–S23 | Fail-open overlay; last good doc kept | Confirm overlay + auto-clear |
| Non-retryable listener error | any match screen | `Failed` → Lobby | Human-readable reasons |
| Player leaves mid-match | S12–S23 | **Silent stall; no exit for the leaver, no signal for the others** | Leave-match affordance + "waiting for X"; the amendment bounds the stall via S39/S30 |
| Player's app killed / dropped | S12–S23 | **Undetected — no presence** | Heartbeat, held seat, rejoin-to-same-seat; bot holds the seat in the meantime |
| **Decision timer expires** | S12–S15, S19 | **No timer exists at all** | S37 ring → **MEDIUM bot decides** (S38); counter +1; **no separate fallback system** |
| **15th timeout in the match** | any | **No counter exists** | **Automatic removal — NOT a Vote Kick (RD20): no panel, no vote, no Round-7 gate, no Koz target, no cooldown.** The counter is match-wide, cumulative across all phases, never resets, and is **private to that player**. Rejoin afterwards = OPEN-1 |
| **Player inactive 50 s (still connected)** | S19–S23 | **Undetected** | S39: bot takes the seat **at the configured difficulty**, player may return and reclaim |
| **Player actually disconnects — Room** | S19–S23 | **Undetected; silent stall** | Bot takes the seat, then **S41 Disconnect Vote once**: YES → S42 pause **bounded at 2 minutes**, then a required CONTINUE/WAIT vote; NO/expiry → continue with the bot |
| **Player actually disconnects — Ranked** | S19–S23 | **Undetected; silent stall** | Bot takes the seat. **No vote, no pause** — the match continues |
| **Manual Vote Kick raised (Ranked ONLY)** | S19–S23, Ranked | **No vote infrastructure mid-match** | S40: **`mode` = RANKED**, only Round 8+, only against the **unique** Koz (**frozen for the vote's duration** — OPEN-2), target excluded, bots excluded, 2/3 human YES. Success = permanent removal + recorded as Koz; **failure = 5-round cooldown on that same target only** |
| **Last place tied when Vote Kick attempted** | S19–S23 | — | S43/S40: **no unique Koz → vote unavailable**, explained in-place. Never an arbitrary pick |
| **Vote Kick fails** | S19–S23 | — | 5-round cooldown in authoritative state, **not reset by reconnect or resumed activity** |
| All four pass the auction | S13 | `GeneralPass` redeal | Redealt-hand transition + multiplier |
| All four fail the round | S23 | Sa'ayda: round scores 0, multiplier escalates | Explicit escalation treatment |
| Round-18 Sa'ayda | S23 | Forces round 19 | Extension notice |
| Fast-round Super Call | S16/S17 | Overrides forced trump, +1 round | Dramatic override treatment |
| Vote times out | S26 | Any client may resolve | Countdown + terminal readout |
| **Disconnect vote times out** | S41 | — | **Treat as NO** — continue with the bot. Shown once per event, never re-asked |
| Back navigation from a match | S19–S23 | Backs out; state drops with the graph | Confirm-before-leave dialog |
| Room closed while joining | S07 | `ROOM_CLOSED` reason | Inline error |
| 2- or 3-player match | S08 | Valid — no AI filler | Roster must not imply 4 required |

## 2.4 Round-loop counts (reference card for the designer)

- 4 players, individual play, no partnerships. Counter-clockwise.
- 52-card deck, 13 cards each, 13 tricks per round.
- **These counts are Game Type-dependent (GM1–GM3, 2026-10-06). The structure is
  identical for both types — only three numbers move.**

| | **FULL** (the default, GM7) | **MINI** |
|---|---|---|
| Base rounds | **18** | **10** |
| Normal rounds (auction + confirmation) | 1–13 | 1–5 |
| Quick Rounds (forced trump, no auction, no confirm) | **14–18** | **6–10** |
| Extensions | **up to 5** (18 → 23 max) | **exactly 1** (10 → 11 max) |
| Trump ladder within the Quick window | 14 Sans, 15 Spades, 16 Hearts, 17 Diamonds, 18 Clubs | 6 Sans, 7 Spades, 8 Hearts, 9 Diamonds, 10 Clubs |

- In **both** types the trump ladder restarts at Sans on the first Quick Round,
  and **extension rounds repeat the ladder from its start**: Full 19 = Sans,
  20 = Spades, 21 = Hearts, 22 = Diamonds, 23 = Clubs; **Mini 11 = Sans**.
- **Mini's one-extension cap is a hard rule, not a tuning knob** (GM3): a second
  extension is rejected. Design never offers or dangles a second Mini extension.
- Rounds past the ceiling cannot trigger an extension in either type (Full 19+,
  Mini 11+), though they are still dealt and played if an extension occurred.
- Dealer rotates one seat CCW each round, in both types.
- Multiplier ladder ×2 → ×4 → ×6 → ×8 (cap under **Normal** calculation; ×2 cap
  under **Classic** — GM4), reset to ×1 on any successful round.
- Minimum auction bid 4; Super Call ≥ 8; Dash Call max 2 players. **Unchanged by
  Game Type** — Mini inherits every bid and estimate rule verbatim.
- **Design implication:** any round-count UI — the round indicator, the standings
  progress bar, the "round N of M" readout — must read `maxRounds` off the match,
  never a literal 18 or 10, and any Quick-Round visual (S16's forced-trump
  treatment) must key off the match's own boundary, never `>= 14`.

## 2.5 Dependency map (amendment additions)

The amendment's systems are new, and their dependencies must be visible before
anyone schedules them. Each row names what the feature needs **before it can be
designed or built**. (The full Phase 8 dependency map comes in Batch 2; this is
the amendment-relevant slice.)

| Feature | Depends on | Why it blocks |
|---|---|---|
| **S36 Create Game** | the match/room configuration write path; the bot tier + personality values being **portable to the backend**; **`gameType` + `scoringMode` being first-class fields on the room and match documents (2026-10-06 GM amendment — code E7/S66, S67)** | The **five** selections must reach the match document and drive both round structure and scoring. The AI is currently uncommitted TypeScript (§0.6-V1) — nothing can persist a "personality" the backend cannot read — and **the two new selections change the round count, the Quick Round boundary, the escalation cap, and (in Ranked) the RP delta**, so they cannot stay client-local or the four clients diverge |
| **S37 Decision Timer** | a **server-authoritative** time source; the MEDIUM bot decision engine; synchronized match state | A client-local countdown would let four clients disagree on expiry and on whose move was made. The rematch deadline is the only existing precedent for a rules-enforced timestamp |
| **Medium-Bot timeout decisions** | a **Kotlin port** of `evaluateBotBid` + `selectBotCard` against the engine's own types | The TS entry points (§0.6-V1) cannot be invoked from the native app; the port is the prerequisite, not an option |
| **15-timeout automatic removal** | the timer; a per-player **match-scoped** counter that survives reconnect; **its own removal state, separate from S40 (RD20)** | The counter must outlive a process restart, so it is a document field, not memory. **It shares no state with Vote Kick** — no vote, no Round-7 gate, no cooldown. Rejoin afterwards is OPEN-1 |
| **S39 Inactivity** | a real **presence/activity mechanism** (heartbeat + input events); seat-ownership switching to a bot and **back**; the configured bot difficulty | `presence-service.js` is a stub and `lastSeenAt` is never written (§0.6-V3). The "and back" handoff does not exist in the engine today |
| **S40 Vote Kick** | live ranking; **unique-Koz detection**; the round number; eligible-voter enumeration; bot takeover; **permanent** removal + no-rejoin | The rematch vote supplies the *shape* but not majority-with-excluded-voter, and it is post-match only (§0.6-V7) |
| **S41/S42 Disconnect Vote + pause** | presence detection; **room mode**; bot takeover; vote state; pause/resume; a **2-minute deadline + a second CONTINUE/WAIT vote** | Rooms-only gating must be enforceable in data; a Ranked match must never be pausable. `MatchDoc` has **no mode field today** (§0.6-V5) — that is the first thing to add. The pause deadline needs the same server-timestamp authority as the rematch vote (§2.8 risks) |
| **S43 King/Koz badges** | the live scores (already synced) + a derived ranking computation | Cheapest amendment feature: the scores exist; only the derivation and the badge components are new. Multiple winners are already rules-supported |
| **S03/S44 Statistics + Quick Stats** | a **mode flag on the match document**; a place to **persist placements per player**; a **readable** stats source for other players | None of these exist (§0.6-V4/V5/V6). `players/{uid}` is owner-read-only, so S44 is blocked until a readable stats surface is decided |
| **S35 Push-to-Talk** | room mode; microphone permission; push-to-talk state | Deferred by owner decision — no implementation work, and **never** in Ranked |
| **S45–S53 Ranked identity + progression UI** | the **RankedProfile model** (tier, division, RP, seasonId, highestRank, placementState); the 19-rank ladder definitions; the RP threshold table | Render is read-only, but it cannot ship before the profile model exists (code S44–S46). **RD26's thresholds are CLOSED (2026-10-05), so the ladder renders real numbers, not placeholders** |
| **S47–S50 Placement** | the once-per-account placement flag; the scripted-bot director (reuses the existing `BotTier`/`BotPersonality` — invent none); placement scoring (10/20/70) | The "Start from Bronze" branch needs only the profile model. The three matches are system-controlled: the player never picks difficulty or personality, and **never sees a provisional rank** — only 1/3, 2/3, 3/3 |
| **S55/S56 Matchmaking** | the **server-side** eligible-pool resolver (own tier or exactly one below — RD9); the search lifecycle | Pool derivation must read **server-side** rank, never a client claim. A lower-tier player cannot opt up; the server may still form a mixed-tier match |
| **S57 Ranked Match Result** | the settlement function (correct-and-settle, RD12); the RP engine; the mode field | Both results land in one surface: the match outcome (King/2nd/3rd/Koz) **and** the ranked delta (RP change, rank transition). Mixed-tier matches must be able to show the RD5 asymmetry in the delta |
| **S54/S58 Seasons + leaderboard** | the season doc (id, start, end); the two-step reset (King → Royal I); server-authoritative ordering | One-way data migration at reset — the reset direction and the ladder floor must be right the first time. **King has no divisions, so RP is the only ordering key above the King lower bound (RD3/RD27)** |
| **S58/S03 Statistics surfaces** | the 9-value accumulator (Public + Private Ranked, **Placement excluded**); the RD11 server read path | S44's long-press reads six short values from the same server-owned surface — unblocked by RD11, blocked on the accumulator (code S55) |

## 2.6 Repository verification — classification of every amendment requirement

The amendment forbids assuming a requested feature exists. Each requirement was
checked against the repository (evidence in §0.6) and classified on the seven
distinctions the owner asked for. **Legend:** ✅ implemented · ◐ partial · 🎨 UI
missing (backend exists) · ⚙️ backend missing · 🧠 game-engine missing · 🖌
design dependency · 👤 owner decision needed.

| Requirement | ✅/◐/🎨/⚙️/🧠/🖌/👤 | Evidence |
|---|---|---|
| Four bot difficulties | ◐ logic ✅, ⚙️ integration | `BotTier` exists in untracked `AI Bots/botEngine.ts:28`; **nothing in `native/` or `design-ui/` references it** |
| Four bot personalities that affect gameplay | ◐ logic ✅, ⚙️ integration | `PERSONALITIES` with real `bidBias`/`dashEagerness`/`superCallAppetite` in `botPersonality.ts`; unconnected to the product |
| MEDIUM engine as timeout decision-maker | 🧠 + ⚙️ | `evaluateBotBid(hand, tier='MEDIUM')` and `selectBotCard(..., tier='MEDIUM')` exist in TS; must be ported to Kotlin and wired to a timer that does not exist |
| Decision timers (5/10/15/20 s, +5 s rule) | ⚙️ + 🎨 | **No timer anywhere** in `native/`; `match-service.js`'s countdown is the rematch vote only (§0.6-V2) |
| Timer countdown UI | 🎨 + 🖌 | S37 — no component exists; the ring must be designed |
| "Decision made by bot" feedback | 🎨 + ⚙️ | S38 — nothing exists; needs the timer + bot port first |
| Match-wide 15-timeout counter | ⚙️ | No counter exists; must be a persisted document field |
| Automatic removal at 15 timeouts (RD20) | ⚙️ + 🎨 | **Not a Vote Kick and not S40** — its own removal state: no vote, no Round-7 gate, no Koz target, no cooldown. No mid-match vote exists, and this mechanism needs none either |
| 50-second inactivity detection | ⚙️ | `presence-service.js` is a 31-line stub; `lastSeenAt` never written (§0.6-V3) |
| Bot takeover of an inactive seat **and return** | 🧠 + ⚙️ | The engine has no temporary-bot-seat concept; `restoreHand()` handles a reconnecting human, not a bot handoff |
| Manual Vote Kick (Round 8+, unique Koz, 2/3) | ⚙️ + 🎨 | Only the **post-match rematch vote** exists, and it needs unanimity with no excluded voter (§0.6-V7) |
| Permanent removal + no rejoin + Koz recorded | ⚙️ | No removal or no-rejoin mechanism; placements beyond `winnerIds` are not persisted (§0.6-V6) |
| Dynamic King badges (multiple allowed) | 🎨 + ◐ backend | `winnerIdsMatchFinalScores()` already treats all tied-max seats as winners (§0.6-V6); the live in-play badge does not exist |
| Dynamic Koz badge + **unique**-Koz gating | 🎨 + ⚙️ | Koz is not stored or derived anywhere; the uniqueness check must be built |
| Disconnect Vote (Rooms only) | ⚙️ + 🎨 + 👤 | No mode field on `MatchDoc` (§0.6-V5) — "Rooms only" is not currently expressible in data |
| Pause waiting for reconnect / resume | ⚙️ | No paused state exists; the owner has now bounded it — **2-minute deadline, then a required CONTINUE/WAIT vote** (D9), so the design target is a deadline-driven loop, not an open wait |
| Ranked Games Played + King/2nd/3rd/Koz counts | ⚙️ | No stats collection; `wins`/`rank`/`rp` are write-once and never incremented (§0.6-V4); `mode` not persisted (§0.6-V5) |
| Percentages from completed Ranked matches | ⚙️ | Needs a completion rule — **now defined (D10): removal/abandonment is recorded per the game's final outcome, and a Vote-Kicked player is KOZ; no fabricated placements** |
| Profile statistics screen | 🎨 + ⚙️ | `ProfileScreen.kt` is a 76-line identity card by its own admission ("no invented numbers") |
| Quick Stats long-press popover | 🎨 + ⚙️ | S44 — **blocked**: `players/{uid}` is `get: if isOwner(uid)` (§0.6-V4); another player's stats are unreadable today |
| Push-to-Talk (Rooms only) | 🖌 + 👤 | Documented only, by owner decision. No implementation; no microphone work in this phase |
| Create Room and Ranked sharing one Create Game screen | 🎨 + ⚙️ | S36 does not exist; no configuration is captured before match creation today |
| **`mode` field on the match document (RD28)** | ⚙️ | **`MatchDoc` has no `mode` field today** (§0.6-V5); `mode` is in-memory only. It is the authority key that gates Vote Kick, settlement, and Ranked statistics — exactly `ROOM` / `RANKED`; private Ranked is `RANKED` + an access flag, **never a third mode**. On the critical path for nearly every Ranked surface (code S43) |
| **19-rank ladder + Arabic titles (RD1/RD2)** | ⚙️ + 🖌 | No tier/division/RP anywhere in `native/`. 6 tiers × 3 divisions + King; **I > II > III**; EN + Arabic titles are product identity, 1:1 — مبتدئ / لاعب / معلم / وزير / أمير / سلطان / ملك |
| **RP engine + thresholds (RD3/RD4/RD26)** | ⚙️ | Dynamic RP (opponent strength, tier difference, outcome, placement, mixed-tier conditions); RP ≥ 0; engine independent of the threshold constants. **The 19 thresholds are CLOSED (RD26, 2026-10-05) — the UI binds the real numbers, which stay tunable behind the engine seam** |
| **Mixed-tier asymmetry + private cap (RD5/RD6)** | ⚙️ | Higher-tier win → reduced reward; higher-tier loss → loss multiplied; lower-tier beats higher tier → increased reward. Private Ranked: same rules, **hard ×2 cap on the loss multiplier**, any tier mix |
| **Placement (RD10)** | ⚙️ + 🎨 | Once per account, never per season; 3 scripted matches (Easy+Medium / Medium+Hard / Hard+Expert); 10/20/70 weights; **Platinum ceiling**; counts toward **no** statistic |
| **Settlement / correct-and-settle (RD12/RD13)** | ⚙️ | No `functions/` module exists at all. The client's `finalScores` is recomputed and **corrected**, not trusted and not merely rejected; idempotent, authenticated, retry-resistant. `firestore.rules` already denies progression writes, so the Functions are the only legitimate write path — **the rules stay untouched** |
| **Seasons + two-step reset (RD24)** | ⚙️ + 🎨 | No season code; 3-month cycle; end-of-season demotes **two division steps** (Gold I → Gold III), floored at the ladder bottom, **King → Royal I**; **career stats never reset**; Highest Rank ever is preserved |
| **Seasonal Ranked Leaderboard (RD27)** | ⚙️ + 🎨 | `leaderboard-service.js` is a `notImplemented` stub. Server-authoritative ordering: position, player, Tier/Rank, RP, season; current player highlighted; **season-isolated — no carryover positions** |
| **Vote Kick Ranked-only enforcement (RD18)** | ⚙️ + 🎨 | The frozen Batch 1 text said Rooms + Ranked; **RD18 makes it Ranked only**, gated on `mode`. Round-7-complete, unique Koz, 2-of-3 humans, 5-round same-target cooldown, permanent + no rejoin |
| **Inactivity / timeout / 15-removal separation (RD19/RD20)** | ⚙️ + 🎨 | Three distinct mechanisms, never merged: 50 s inactivity (rejoin allowed, same seat); timeout → **MEDIUM** engine regardless of configured difficulty; 15 cumulative timeouts → **automatic removal, separate from Vote Kick**. Counter is **private to the player** |

**The one-line summary for the owner:** the amendment's gameplay *logic* largely
exists as a **disconnected, uncommitted TypeScript prototype**; its
**backend, engine integration, and UI are almost entirely greenfield**, with the
single exception of the ranking/multiple-Kings support, which the rules layer
already handles.

## 2.7 Consistency audit

Performed against the rules as written in §0.5 and the evidence in §0.6, before
this document was finalized. **Contradictions found in the old Batch 1 text are
named here as FIXED** rather than silently repaired. The final owner decisions
(2026-10-04, D5–D10 + unique-Koz) are audited as checks 31–40.

| # | Check | Result |
|---|---|---|
| 1 | Timeout ≠ inactivity | ✅ Distinct systems: timeout = one missed decision (S38, MEDIUM bot); inactivity = 50 s away (S39, configured bot) |
| 2 | Inactivity allows return | ✅ `BOT_INACTIVE` → `HUMAN` on the player's return; the bot stands down |
| 3 | Vote Kick does NOT allow return | ✅ `BOT_REMOVED` is terminal; no rejoin |
| 4 | Disconnect Vote is Rooms-only | ✅ Gated on room mode; Ranked has no vote and no pause. ⚠️ Requires a `mode` field that `MatchDoc` lacks today (§0.6-V5) |
| 5 | Voice is Rooms-only | ✅ S35 re-scoped in §1.2-G and its flow spec |
| 6 | Ranked has no voice | ✅ Same row — the control is absent, not disabled |
| 7 | Vote Kick starts only after 7 complete rounds | ✅ `completedRounds >= 7`; first possible vote is Round 8 |
| 8 | Manual Vote Kick only against ONE UNIQUE Koz | ✅ `kozSeatId` must be non-null and unique; tied last place disables the vote (S40) |
| 9 | Multiple Kings allowed | ✅ `kingSeatIds: Set`; matches the existing rules layer (§0.6-V6) |
| 10 | Multiple tied positions allowed generally | ✅ S43 renders ties as ties |
| 11 | Multiple Koz targets NOT allowed for Vote Kick | ✅ Exactly the condition that disables the vote |
| 12 | Vote Kick target does not vote | ✅ Target's slot is structurally absent from the vote map |
| 13 | Disconnecting player does not vote | ✅ Excluded; the question is about them |
| 14 | Majority is based on eligible voters | ✅ Not unanimity — the rule that most differs from the existing rematch vote. **Refined by D7: the denominator is eligible HUMAN voters; bots neither vote nor pad it** |
| 15 | 2 YES enough when 3 eligible | ✅ 4 players − 1 target = 3 eligible humans; 2 passes. Recalculated over the human set when seats have gone bot |
| 16 | Vote timeout = NO for Disconnect Vote | ✅ `FAILED_TIMEOUT` means continue with the bot |
| 17 | Disconnect Vote once per disconnect event | ✅ Per-event suppression flag; no re-asking |
| 18 | 15 timeouts counted across the entire match | ✅ Match-scoped, never reset per round |
| 19 | Timer selections are 5 / 10 / 15 / 20 s | ✅ The four values, in S36 |
| 20 | Card Play uses the selected timer | ✅ Base duration |
| 21 | Dash/Bidding/Estimates use selected + 5 s | ✅ Stated in §0.5-A, shown live in S36, legible in-play via S37 |
| 22 | Medium Bot makes timeout decisions | ✅ `tier = 'MEDIUM'` on both entry points; no separate fallback system exists or is planned |
| 23 | Configured difficulty/personality used for absent/inactive players | ✅ S39 uses the game's configuration; MEDIUM is reserved for timeouts only. Keeps rules 1 and 23 from collapsing into each other |
| 24 | Create Room and Create Ranked share Create Game | ✅ One S36, two entry points |
| 25 | Profile statistics are Ranked-only | ✅ §0.5-H; ⚠️ requires the persisted `mode` flag |
| 26 | Quick Stats uses the same Ranked source | ✅ S44 reads the same data as S03 |
| 27 | King/Koz indicators dynamic during gameplay | ✅ S43 is a live table overlay, not a round-result artefact |
| 28 | Old-roadmap contradictions fixed, not preserved | ✅ **FIXED:** S09/S10 "DEFERRED" → S10 reversed, S09 superseded by S36; open questions 3 (presence policy) and 4 (turn timer) are now answered by the amendment and removed from §2.8 |
| 29 | No feature silently moved into implementation | ✅ This is still a planning document: no code, no Figma, no microphone work, no gameplay-code changes |
| 30 | Batch model intact | ✅ Batch 1 remains Phase 1 + Phase 2; the amendment's downstream consequences are recorded as Batch 2 outline changes below, not started |
| 31 | Create Room and Create Ranked use the same Create Game screen | ✅ S36 is the single configuration surface for both (§1.2-B, S36 spec) |
| 32 | Default bot = Medium | ✅ **D5 CLOSED** — MEDIUM is the pre-selection |
| 33 | Default personality = the designated default | ✅ **D5 FINAL** — **BALANCED / Steady**, confirmed by the owner as the official fallback at every tier; skill and personality stay orthogonal (MEDIUM + none = MEDIUM + BALANCED) |
| 34 | Default timer = 15 s | ✅ **D5 FINAL** — and the derived defaults read Dash/Bidding/Estimates 20 s, Card Play 15 s |
| 35 | Timeout count hidden from opponents | ✅ **D6 CLOSED** — internal tracking always on; own-count permitted; opponent visibility forbidden (S38) |
| 36 | Inactive player may return; takeover is temporary | ✅ `BOT_INACTIVE` → `HUMAN`; the 50 s threshold is unchanged |
| 37 | Vote Kick is permanent; unavailable before Round 8 | ✅ `BOT_REMOVED` is terminal; `completedRounds >= 7` gate |
| 38 | Tied last place = no Vote Kick; multiple Kings allowed | ✅ **Unique-Koz CLOSED** — `kozSeatId` null on a tie disables the vote; no arbitrary pick; stated in all five required places |
| 39 | Bots do not vote, in every vote system | ✅ **D7 CLOSED** — no vote slot and no denominator contribution, in S40, S41, and the rematch vote alike |
| 40 | Failed Vote Kick cooldown = 5 rounds | ✅ **D8 CLOSED** — rounds not seconds, authoritative, survives reconnect/inactivity (S40, §1.3) |
| 41 | Maximum pause = 2 minutes, then a new Continue/Wait vote; no unlimited pause | ✅ **D9 FINAL** — `pauseDeadline` + `CONTINUE_OR_WAIT`; no-decision = CONTINUE; **WAIT is capped at 2 cycles, after which the vote is CONTINUE-only** — no `2 min → WAIT` loop can run without end |
| 42 | Ranked statistics include Vote Kick outcomes; Vote-Kicked = Koz | ✅ **D10 CLOSED** — removal is recorded per the game's final outcome, never an unrecorded non-match; no fabricated placements (§0.5-H) |
| 43 | No Figma / Android / microphone work begins from this amendment | ✅ Document update only; Batch 2 remains unstarted |
| 44 | Lobby and Waiting Room remain DESIGN COMPLETE | ✅ Untouched by both amendments; still marked do-not-redesign |
| 45 | **Vote Kick is Ranked ONLY (RD18)** — contradicts frozen Batch 1 text at five sites | ✅ **FIXED in place**, listed not hidden: the §0.5 capability table, §0.5-E, the §2.1 exception-path diagram, the S40 spec, and the S40 artboard row. The `mode` field is the gate (RD28); the entry point is **absent** in a Room, not disabled |
| 46 | **15-timeout automatic removal is NOT Vote Kick (RD20)** | ✅ **FIXED:** the frozen text's "S40 automatic flavour" is removed from S40 and specified as its own mechanism at §0.5-C, §2.1, §2.3, §2.5, §2.6, and S38 — no vote, no Round-7 gate, no Koz target, no cooldown, and the two share no state, UI, or test |
| 47 | **The timeout counter is private (RD20)** | ✅ Opponents never see a count or a progress-toward-15 indicator; only the player's own count is shown, and only to them (S38, §1.3) |
| 48 | **Exactly 9 career statistics (RD23)** — never "10 values" | ✅ §0.5-H names all nine; Public + Private Ranked included, **Placement excluded**; long-press shows the six short values only (S44) |
| 49 | **`mode` has exactly two values (RD28)** | ✅ `ROOM` / `RANKED`; private Ranked is `RANKED` + an access flag, never a third mode (§1.3, S36, S40) |
| 50 | **Ladder is 6 tiers × 3 divisions + King = 19 ranks (RD1)** | ✅ Never "7 tiers × 3 divisions"; King has no divisions; I > II > III (RD2); Arabic titles are product identity, 1:1 (S46, §4b) |
| 51 | **Seasons are MVP (RD24)** — S11's "DEFERRED" applied to Season | ✅ **FIXED:** Season moved out of DEFERRED (S11 row); only Shop/Missions remain deferred. Two-step reset, King → Royal I, career stats and Highest Rank untouched |
| 52 | **Ranked is launch-blocking MVP scope (RD25)** | ✅ Every post-v1 statement about Ranked/matchmaking/RP/seasons in this document is corrected; the code roadmap carries epic E6b (S42–S64) |
| 53 | **Voice never in Ranked (RD22)** | ✅ S35 and the Disconnect/pause vote stay Rooms-only; the ranked-absence variants are required artboards (§4.3.9) |
| 54 | **Gold ≠ Match-King (token risk)** | ✅ Called out as a token gap (§4.1.4 #5) and in §4b: `#E8A33D` is the **Match-King badge** colour; the **Gold tier** must not reuse the crown or the gold-for-King semantic |
| 55 | **No threshold number is invented (RD26)** | ✅ **CLOSED 2026-10-05:** the ladder carries the owner's 19 final lower-bound values (§4b.1); the non-canonical `design-ui` mocks (`Gold III` 1240 > `Gold I` 980, nonexistent `Platinum IV`) are flagged as legacy data and derived from nowhere |
| 56 | **A Vote Kick vote cannot change target mid-vote (OPEN-2)** | ✅ Recorded as an open owner decision, not silently assumed; the frozen text's "match state must not advance" assertion is downgraded to "not established" (S40) |

**One residual tension the owner should know about (not a contradiction):** rule
23 says an inactive seat uses the **configured** difficulty, while rule 22 says
timeouts use **MEDIUM**. In a match configured at EXPERT, an away player's seat
plays EXPERT but their individual missed decisions play MEDIUM. That is the
amendment's own intent (a seat's character vs. a single merciful move) and it is
recorded in S38/S39 so the designer does not fuse the two.

## 2.8 Risks, blockers, unknowns, and owner decisions

**Blockers (design cannot proceed past these):**

1. **No Figma assets.** **Phase 5 remains BLOCKED ON OWNER** — Lobby and
   Waiting Room designs have not been shared, and no consistency claim is made
   against them anywhere in this document. **Phase 4 is not blocked by this**:
   §4 derives its tokens from the shipped code, so the artboards can be drawn
   now and reconciled against the two designs in Phase 5. *(Corrected 2026-10-04
   alongside the Batch 1 approval; the original text blocked both phases.)*
2. **S44 Quick Stats is blocked by the data model.** `players/{uid}` is
   `get: if isOwner(uid)` — another player's statistics are unreadable. The owner
   must choose: open a minimal public-stats read in `firestore.rules`, or add a
   separate readable stats document.
3. **"Rooms only" is not currently expressible in data.** `MatchDoc` has no
   `mode` field (§0.6-V5). Until one exists, the Disconnect Vote and the pause
   cannot be reliably gated, and Ranked statistics cannot be separated from Room
   statistics. **This is the single highest-leverage backend change the amendment
   requires.**
4. **The AI is uncommitted.** `AI Bots/` is untracked and disconnected. It must
   be committed (or moved) and ported to Kotlin before S36's selections can mean
   anything at runtime.

**Owner decisions — CLOSED (final, 2026-10-04):**

| ID | Decision | Where it is enforced |
|---|---|---|
| **D5 — FINAL** | Create Game defaults: **MEDIUM** difficulty, **BALANCED** personality ("Steady"), **15 s** timer. Player may change all three before creating. BALANCED is the **fallback personality for any bot without an explicit one, at any tier** — skill and personality are orthogonal | §0.5-A defaults block; S36 spec |
| **D6 — CLOSED** | Timeout count is **internal and hidden from opponents**. A player may see their own count; opponents never see a count nor proximity to 15 | §0.5-C; S38 |
| **D7 — CLOSED** | **Bots never vote** and never count toward the eligible-voter denominator, in every vote system | §0.5-E; S40, S41; §1.3 |
| **D8 — CLOSED** | Failed Vote Kick → the **same target** is blocked for **5 rounds**. Measured in rounds, in authoritative match state, not reset by reconnect or resumed activity | §0.5-E; S40; §1.3 |
| **D9 — FINAL** | Pause is bounded at **2 minutes**; at expiry a **required CONTINUE/WAIT vote** — majority CONTINUE (or no decision) resumes with the bot, majority WAIT starts a fresh 2-minute window. **Maximum WAIT cycles = 2**; after the second WAIT the vote is CONTINUE-only. **No unlimited pause, no unbounded WAIT loop** | §0.5-G; S42; §1.3 |
| **D10 — CLOSED** | Removal/abandonment is included in Ranked statistics **per the game's recorded final outcome**; a Vote-Kicked player is recorded as **KOZ**. No unrecorded non-matches, no fabricated placements | §0.5-H; S40 |
| **Unique-Koz — CLOSED** | Manual Vote Kick requires **exactly one** current Koz; a tied last place disables it, never an arbitrary pick | §0.5-F; S40, S43; §1.3; Phase 9 |

**No open owner decisions remain in Batch 1.** D1–D4 were resolved by the
2026-10-03 amendment and the original roadmap; D5–D10 and the unique-Koz
requirement are now closed above. Anything still listed as a question elsewhere
in this document is a *design* question for Batch 2, not an owner decision.

**Risks:**

- **Timer authority is the hardest build in the amendment.** Four clients must
  agree on expiry. Any client-clock-based countdown will produce divergent bot
  decisions; the design must assume a server timestamp, as the rematch vote
  already does. **D9 adds a second deadline to this problem** — the 2-minute
  pause window must be as authoritative as the votes that bracket it, or a slow
  client can miss the CONTINUE/WAIT vote entirely.
- **Three "bot takes the seat" mechanisms share one seat.** Timeout (one
  decision), inactivity (a stretch), disconnect (until reconnect or vote), and
  removal (permanent) all write to the same seat-ownership field. The state
  machine in §1.3 is written to keep them disjoint, but this is the most likely
  place for a real bug, and it needs the most thorough testing (Phase 9).
- **The D9 WAIT loop is now bounded by the owner.** The final decision caps WAIT
  at **2 cycles**, after which the vote becomes CONTINUE-only — so a table can
  extend a match by at most **4 minutes** per disconnect before the bot resumes.
  The residual risk is only that a *stale* `waitCycles` could survive a reconnect
  and over-block a later pause; the counter must be scoped to the disconnect
  event that opened it, not to the match.
- **S44 gesture conflict.** Long-press on an avatar must not swallow a card-tap.
  This is a design problem with a concrete constraint: the popover must never
  intercept the hand area.
- **Vote Kick as a griefing vector.** A majority of 2 can remove a player
  permanently. The Round-8 gate, the unique-Koz restriction, and the 5-round
  cooldown (D8) are the safeguards.

---

## BATCH 1 ENDS HERE  —  APPROVED AND CLOSED 2026-10-04

**Batch 1 (§0–§2.8) is frozen.** It is not modified again unless a real
contradiction is discovered. What follows is Batch 2.

**Batch 2 execution order (owner direction, 2026-10-04): Figma first.** The
priority is to **complete all game screens and flows in Figma before any UI code
is written.** No UI code, no Code Plan, no implementation work is started by this
batch — only the design plan below.

The phase outlines recorded at the end of Batch 1 described what Phases 3–9 must
eventually contain. Phase 3's content was delivered inside Batch 1 itself
(§2.2 and §2.2b answer the owner's nine questions for all of S01–S44), so
Batch 2 opens directly at Phase 4:

- **Phase 3** — Screen-by-screen UI requirements. **DELIVERED inside Batch 1**
  (§2.2 and §2.2b answer the owner's nine questions for all of S01–S44), including
  the explicit hand-visibility spec for Bidding and the Table (the #1 requirement,
  unchanged). Not repeated here. **The 2026-10-05 Ranked amendment adds S45–S58
  (§4b); those screens get the same nine-question treatment in Phase 4b, not here.**
- **Phase 4** — Figma design planning (per-screen status, states/variants,
  components, interactions, dependencies). **STARTED — see §4 below.** The Lobby
  and Waiting Room Figma assets have still not been shared, so this phase is
  written to be **token-driven from the shipped code**, not from the unseen Figma
  files: the Lobby and Waiting Room designs are treated as **reference-only
  constraints** (`DONE (owner)` in §1.2 — do not redesign), and every other screen
  is specified against the verified token set in §4.1. **Nothing here claims
  visual consistency with the two finished designs until they have been seen.**
  The amendment adds nine new surfaces to the design queue, of which **S36, S40,
  and S43 are the highest-traffic**. **The 2026-10-05 amendment adds 14 more
  (S45–S58, §4b) plus the tier-token problem in §4.1 — see Phase 4b.**
- **Phase 5** — Visual consistency spec. **BLOCKED ON OWNER** for the same
  reason. The owner's stated direction (Royal Egyptian Card Club / 1930s Cairo
  coffeehouse; charcoal / deep green / brass / warm light; blue-gold card backs;
  no generic green poker-table look) is recorded and will govern, but the
  authoritative tokens must come from the two finished designs. The amendment
  adds: the **King/Koz badge system needs its own token set** (crown vs. Koz
  must read instantly at table scale), and the three vote modals (S40, S41, S26)
  should share one modal language. **The 2026-05 amendment adds the tier-token
  problem: six tiers + King need a colour/glyph system distinct from the existing
  palette, because `gold #E8A33D` already belongs to the Match-King badge and
  `Royal` is already the art direction (§4b token work).**
- **Phase 6** — Protecting existing game logic. The amendment changes this phase
  substantially: the AI prototype must be **committed and ported** (§0.6-V1), a
  `mode` field must land on `MatchDoc` (§0.6-V5), and placements beyond
  `winnerIds` must be persisted (§0.6-V6) — all **without altering the rules
  engine's behaviour**, which is tested and must not regress. **The 2026-10-05
  amendment adds the lightweight Ranked authority layer alongside the protected
  engine (RD11/12): Cloud Functions settlement, the RP engine, placement, and
  matchmaking — still additive only, still no rules-engine behaviour change. The
  code-roadmap companion is `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` §F / E6b
  (S42–S64).**
- **Phase 7** — Push-to-Talk documentation only (no implementation). Scope now
  fixed by the amendment: **Rooms only**, Waiting Room + in-match, never Ranked.
  **RD22 leaves this unchanged — voice never enters Ranked.**
- **Phase 8** — Ordered implementation roadmap and dependency map. Now seeded by
  §2.5; the ordering consequence is real: **the `mode` field, the timer
  authority, and the AI port are on the critical path for most amendment
  features**, while S43 (badges) needs only the existing scores. **The 2026-10-05
  amendment extends the critical path: `mode` → settlement → RP engine →
  statistics → matchmaking → seasons → leaderboard (§4b dependency map). S43
  (badges) still needs only the existing scores; S46/S48–S53 need the Ranked
  profile model; S55/S56 need the matchmaking pool resolver.**
- **Phase 9** — Testing gate: the conditions that must hold before a new 4-human
  playtest may be scheduled. The amendment adds tests that must be designed for
  now and run then: **timer expiry produces the same MEDIUM decision on all four
  clients**; **the 15-timeout counter survives a reconnect**; **an inactive
  player's return restores their seat mid-round**; **a Vote Kick cannot be raised
  before Round 8 or against a tied last place**; **2 of 3 YES removes the target
  permanently and records the Koz**; **a failed Vote Kick blocks the same target
  for 5 rounds even across a reconnect**; **a bot-controlled seat never votes and
  never pads the eligible-voter count**; **opponents can never read another
  player's timeout count**; **a Disconnect Vote appears exactly once and its
  expiry means NO**; **a pause cannot exceed 2 minutes and always ends in a
  CONTINUE/WAIT vote**; **a Disconnect Vote and a pause are impossible in
  Ranked**; **multiple tied 1st-place players all render as King**; and **a
  long-press never blocks a legal card play**. **The 2026-10-05 amendment adds
  the full Ranked tier (see §4b testing plan): forged `finalScores` corrected not
  accepted; settlement idempotent; RP never negative; mixed-tier asymmetry in all
  three directions with the private ×2 cap; placement 10/20/70 with a Platinum
  ceiling and no provisional rank shown; matchmaking own-tier-or-one-below with
  no opt-up; season reset direction Gold I → Gold III with King → Royal I and
  Highest Rank intact; leaderboard server-ordered with King ranked by RP; Vote
  Kick Ranked-only and frozen-target; the 15-timeout automatic removal firing
  with no vote and no Round-7 gate; and the timeout count never visible to
  opponents.**

Also deferred to Batch 2: the full risks/blockers/unknowns register (seeded at
§2.8) and the Definition of Done for the UI phase.

### Questions still open for the owner

**All owner decisions are FINAL and CLOSED** — D1–D4 by the original roadmap and
the 2026-10-03 amendment, D5–D10 and the unique-Koz requirement by the final
owner decisions (§2.8), and **RD1–RD28 by the 2026-10-05 Ranked amendment (§4b)**.
What remains is:

1. **Which branch is the implementation base** — continue on
   `android/phase5-online-match`, or rebuild from `main` against the new designs?
2. **The two finished Figma screens** — view-only links, or PNGs under
   `docs/design/`. **Phase 4 is no longer blocked on these** — it is written
   token-driven from the shipped code that already mirrors them (§4.1), with the
   two designs treated as reference-only constraints. **Phase 5 (visual
   consistency reconciliation) is the step that still needs them.**
3. **The named `BotPersonality.DEFAULT` constant does not exist yet.** The owner's
   D5 decision references it; the repo currently expresses the same default only
   as the inline literal `bot.personality ?? 'BALANCED'` (`botPersonality.ts`).
   The value is correct and unchanged — **the constant is a one-line addition at
   port time**, not an open decision. Flagged so the implementing agent does not
   grep for a constant that isn't there.
4. **RD26 — the 19 numeric RP thresholds are CLOSED (2026-10-05).** The ladder
   *structure* is closed (RD1–RD3): 6 tiers × 3 divisions + King, I > II > III,
   every rank has a lower bound, RP ≥ 0, King has no upper bound. **The numbers
   are now the owner's final lower-bound values** (Bronze III 0 → King 3,000, in
   §4b.1). Nothing is derived from the non-canonical `design-ui` mocks. The
   thresholds stay tunable constants independent of the engine, so a playtest
   balance pass can still move them — but as a tuning decision, not an open one.
5. **OPEN-1 — reconnect after the 15-timeout automatic removal.** The removal
   itself is closed (RD20); whether/how that player may rejoin that match is not.
   No screen assumes an answer either way.
6. **OPEN-2 — is gameplay paused while a Vote Kick is active?** The target is the
   unique current Koz, so it must not be able to change mid-vote; whether the
   match pauses to guarantee that is an owner decision, not an assumption.

---
---

# BATCH 2 — PHASE 4: THE FIGMA DESIGN PLAN

**Started 2026-10-04, after the owner approved and closed Batch 1.**

**What this phase is.** A complete, build-ready specification of every Figma
artboard the designer must produce: the token foundation, the shared component
library, the per-screen artboard list with every state/variant that must be
drawn, and the interactions that must be wired. It is written so the Figma work
can proceed **without rediscovering any requirement** — and so that when the
designs come back, the Android implementation is a trace against a fixed
specification rather than an interpretation.

**What this phase is not.** It contains **no UI code, no Compose, no layout XML,
no Code Plan**. It does not modify game logic. It does not start Phases 6–9.
Per the owner's direction: **all game screens and flows are completed in Figma
first.**

**A note on the two finished designs.** The Lobby (S05) and Waiting Room (S08)
are `DONE (owner)` — **do not redesign them.** Their Figma sources have not been
shared with this agent, so this plan does **not** claim visual consistency with
them. Instead it derives the token foundation from the **shipped code that
already mirrors them** (§4.1), which is verifiable, and it flags the one moment
where the unseen designs remain authoritative (§4.8). When the owner supplies
the two Figma files, Phase 5 reconciles this plan's tokens against them — that
is a reconciliation pass, not a redesign.

---

## 4.1 Token foundation — verified against the shipped code

The token set below is **not invented**. Every value is taken from the Lobby web
design (`design-ui/lobby/index.html`, which the native theme explicitly mirrors)
and the native theme (`EstemshanTheme.kt`). The two are already consistent — the
native file says so by design: *"Tokens mirror the web client exactly so both
platforms read as one product."* This is the foundation the Figma file opens with.

### 4.1.1 Colour

| Token | Hex | Source | Role |
|---|---|---|---|
| `bg` | `#0D0A07` | lobby `--bg`; `EstemshanColors.Background` | App/table background — near-black warm |
| `surface` | `#17130E` | lobby `--panel`; `EstemshanColors.Surface` | Panels, sheets, modal bodies |
| `surfaceHigh` | `#1D1912` | match `--panel-hi`; `EstemshanColors.SurfaceHigh` | Inset fields, raised tiles, seat wells |
| `pill` | `#1D1A16` | lobby `--pill` | Chips, inactive buttons, roster rows |
| `gold` | `#E8A33D` | lobby `--accent`; `EstemshanColors.Gold` | Primary accent — CTAs, active states, SANS suit, King badge |
| `goldDim` | `#A8742A` | `EstemshanColors.GoldDim` | Secondary accent, dividers, Koz badge |
| `ink` | `#F0EADA` | lobby `--ink`; `EstemshanColors.Ink` | Primary text — warm off-white |
| `inkDim` | `#A89F8E` | lobby `--ink-dim`; `EstemshanColors.InkDim` | Secondary text |
| `inkFaint` | `#8A8272` | lobby `--ink-faint` | Tertiary text, hints, placeholders |
| `error` | `#F25555` | `EstemshanColors.Error` | Destructive actions, rejection feedback, kick states |
| `panelLine` | `rgba(255,240,210,.07)` | lobby `--panel-line` | Hairline borders on every panel/chip |
| `redSuit` | `#E57373` | BiddingControls/TableScreen | Hearts + Diamonds glyph colour |
| `goldHi` / `goldLow` | gradient `#FFF3DA→#E8A33D→#1A1208` | lobby `--accent-hi`/`--accent-dim` | The primary-button gradient (160°) |

**Design rule (carried from the shipped audit):** `inkFaint` was already raised
from `#6F685B` to `#8A8272` by a production WCAG audit — the old value failed
4.5:1 against every real background. **Do not darken text tokens in Figma.** Any
new text colour must be checked at ≥ 4.5:1 against `bg`, `surface`, and
`surfaceHigh` before it enters the file.

### 4.1.2 Typography

Three families, already licensed and loaded — **no new fonts**:

| Role | Family | Native mapping | Usage |
|---|---|---|---|
| **Display** | Marcellus (serif) | `FontFamily.Serif` | Titles, mastheads, Final Standings, King/Koz labels |
| **Body** | Saira (sans, 400/500/600) | `FontFamily.SansSerif` | Buttons, body copy, controls |
| **Numeric** | Spline Sans Mono (500/600/700) | `FontFamily.Monospace` | **All numbers** — scores, bids, timers, codes, tallies |

**The mono-for-numerals rule is a design law, not a preference.** Scores, the
tricks slider value, the countdown ring digits, the room code, vote tallies
("2/3"), timeout counts, and stat values are **always** Spline Sans Mono. It is
what makes a score read as a score at table scale, and it keeps tabular figures
from jittering as they change.

Type scale (from `EstemshanTypography`, to be set as Figma text styles):

| Style | Family / size / leading | Colour |
|---|---|---|
| `displayLarge` | Marcellus 40 / 44 | `ink` |
| `headlineMedium` | Marcellus 26 / 30 | `ink` |
| `titleMedium` | Saira 17 / 22 | `ink` |
| `bodyLarge` | Saira 16 / 22 | `ink` |
| `bodyMedium` | Saira 14 / 19 | `inkDim` |
| `labelLarge` | Spline Sans Mono 13 / 17 | `inkDim` |

**Mobile scaling note for the designer:** the web reference sets very small mono
sizes (8.5–13 px) because it renders inside a fixed desktop stage. **The Figma
file targets a phone, so these must be re-scaled for a ~390–430 dp canvas and
re-audited for contrast and legibility** — the values above are the *systematic*
scale; the web pixels are the *reference proportions*, not the mobile sizes. The
minimum body size on mobile should be ≥ 14 dp, and the minimum for any text the
player must read to play (bid values, timer digits, tally counts) is ≥ 16 dp.

### 4.1.3 Shape, elevation, motion

- **Radii:** chips/pills `12–14`, buttons `9`, panels/fields `8`, cards `8–10`,
  modals/sheets `16–20` (top corners only for bottom sheets).
- **Borders:** 1 px `panelLine` on every panel, chip, and roster row. This
  hairline is the entire elevation system — the palette is too dark for shadows
  alone to read.
- **Glow:** the Lobby's `--glow: 0.6` drives a warm radial gradient behind the
  masthead (`rgba(141,84,32, glow*0.55)`). Reuse it for title areas, not body.
- **Motion:** the web reference uses `0.12s ease` for control state changes.
  Keep interactions ≤ 200 ms; nothing in a timed game may animate slowly. The
  one exception is the trick-resolution highlight (900 ms in code), which the
  design should treat as a beat, not a transition.

### 4.1.4 What the tokens do **not** cover yet

These four token gaps are real and must be closed **during** the Figma work, not
after — each is a design decision the file has to make explicitly:

1. **King / Koz badge tokens.** Crown-gold (`gold`) vs. Koz (`goldDim` or a new
   muted tone) must be **instantly distinguishable at table scale and in
   peripheral vision** — the whole point of S43. If `gold` vs. `goldDim` does
   not separate clearly, introduce a distinct Koz token rather than risking it.
2. **Timer-ring states.** S37 needs at least three visual states — running,
   low-time (warning), and expired — that read without reading the digits.
3. **Vote-language tokens.** S26, S40, and S41 share one modal language: a
   single accent for the affirmative, `error` for the negative, and a neutral
   disabled state for a vote already cast.
4. **Card-back design.** The owner's direction is **blue-gold card backs**, no
   generic green poker-table felt. This is a bespoke asset, not a token — it
   must be drawn.
5. **The tier-token system — 6 tiers + King, distinct from the palette (added
   2026-10-05 by the Ranked amendment).** This is the one that can silently
   collapse the product if it is not decided explicitly:
   - `gold #E8A33D` is the **Match-King badge** colour and `goldDim` the **Koz**
     badge. The **Gold *tier*** must **not** reuse the crown glyph or the
     gold-for-King semantic, or "Gold" reads as "Match King" on the same avatar
     that already carries a crown (S43 vs. the Rank Chip sit on the same seat).
     The Gold tier needs its own treatment.
   - **Royal** is already the art direction of the whole app. The Royal *tier*
     (the second-highest rung) must stay distinct from the room/table aesthetic,
     or the top of the ladder looks like the app's default skin.
   - **The Rank Chip vs. the Match-King crown must be instantly separable at
     table scale** — different shape, different weight, different placement.
   - **Arabic titles are product identity (RD1)**, not localizable generics —
     مبتدئ / لاعب / معلم / وزير / أمير / سلطان / ملك sit alongside the EN tier
     names in both `values` and `values-ar` (the S41 Arabic-retrofit window).
   - King is the 19th rung and has **no divisions** — the ladder component must
     render it as a single destination above the 18 divisional rungs, never as
     "King III/II/I".

---

## 4.2 The shared component library (Figma components)

Building these **once**, as variants of a small number of components, is what
keeps 44 screens consistent. Every artboard in §4.3–§4.5 is assembled from this
list. Component names below are the names the Figma file should use.

### 4.2.1 Core controls

| Component | Required variants | Used by |
|---|---|---|
| `Button / Primary` | default, pressed, disabled, loading | S02, S04, S36, S06, all confirm actions |
| `Button / Secondary` | default, pressed, disabled | S12 Decline, S13 Pass, modal dismiss |
| `Button / Destructive` | default, pressed | S40 initiate kick, leave-match |
| `Chip / Suit` | 5 — ♣♦♥♠ + SANS (gold) × selected/unselected | S13, S14, S17 |
| `Chip / Option` | selected / unselected / disabled | S36 difficulty, personality, timer pickers; S41 CONTINUE/WAIT |
| `Stepper` | − value +, with min/max clamp visuals | S13 (4–13), S14, S15 (0..cap) |
| `Field / Panel` | empty, filled, error | S02, S07, S36 |

### 4.2.2 Player & table primitives

| Component | Required variants | Used by |
|---|---|---|
| `Avatar` | 4 seats; states: idle, **active-turn**, bot-controlled (S39), inactive, disconnected, kicked, is-you | every seat, S19–S23, S31, S40 target |
| `Seat` | avatar + name + score + optional badge slot + optional timer slot | S19–S23, S08 roster rows are a sibling |
| `Badge / King` | crown glyph, gold — **must support multiple simultaneous instances** | S43 |
| `Badge / Koz` | muted/earned-distinct glyph — must read as *not*-King | S43 |
| `Card` | face (13 ranks × 4 suits + SANS), **back (blue-gold)**, dimmed, disabled, played/winner-highlight | S19–S22, and the #1 requirement below |
| `Timer Ring` | running, low-time, expired; sized to wrap an avatar **and** to sit inline | S37 on S12–S15, S19/S20 |

**`Card` is the single most important component in the file.** It is the root of
the playtest-blocking gap (§0.3): cards must render in **Bidding** (S12–S15) and
on the **Table** (S19–S22), including the RESOLVING and round-DONE states where
the hand currently vanishes. Design the hand composable once and reuse it; do not
draw four different hand treatments.

### 4.2.3 Feedback & overlay surfaces

| Component | Required variants | Used by |
|---|---|---|
| `Modal / Sheet` | bottom-sheet (mobile default) and centered dialog; with scrim | S06, S25, S26, S40, S41, S44 |
| `Vote Panel` | question, YES/NO or CONTINUE/WAIT, live tally `n/eligible`, waiting-on list, result state | S26, S40, S41 (shared language) |
| `Banner / Inline` | info, warning, error; dismissible and persistent | S29, S30, S32, S34, S38, S39 |
| `Toast / transient` | success, error (auto-dismiss) | S34 rejections, S38 timeout notice |
| `Stat Tile` | label (mono caps) + value (mono) | S03, S44, S23 deltas |
| `Empty / Loading` | skeleton, empty-state, error-state | S28, S33, S03 stats-missing |
| `FAB / Push-to-Talk` | idle, active (speaking), denied — **documented only, do not place in Ranked** | S35 |

---

## 4.3 Screen artboard list — every frame the Figma file must contain

**Status legend:** `DONE` = owner-approved, reference only. `NEW` = new
artboard. `VARIANT` = new variant of an existing artboard. `N/A` = no design.

### 4.3.1 A. Entry & identity — 3 artboards + S03's stat grid

| ID | Artboards required | States / variants |
|---|---|---|
| S01 Splash | 1 `NEW` | loading; the failed-init path → S32 treatment |
| S02 Login | 1 `NEW` | SignedOut / SigningIn (spinner state) / Error (inline field error) |
| S03 Profile | 1 `NEW` + **stat grid** | identity card; the **10 Ranked stat values** (§0.5-H); **empty state — stats not built yet**; loading skeleton |
| S04 Settings | 1 `NEW` | every row live: account, sound toggle, logout, version |

**S03 design constraint (hard):** the stats backend does not exist (§0.6-V4/V5).
Design the grid as a real surface with a genuine **empty/loading state** — never
a fabricated number. The `Stat Tile` component is defined for this.

### 4.3.2 B. Lobby & room setup — S36 first, then the two sheets

| ID | Artboards required | States / variants |
|---|---|---|
| S05 Lobby | `DONE (owner)` | **Reference only — do not redesign.** Only the entry points to S06/S07/S10 must be present |
| S06 Create Room sheet | 1 `NEW` | success (code + share rows); failure |
| S07 Join Room dialog | 1 `NEW` | empty / not-found / full / closed / generic error |
| S08 Waiting Room | `DONE (owner)` | **Reference only.** The S35 Push-to-Talk control lives here in Rooms (documented) |
| S10 Create Ranked | → **S36** | No separate artboard — the Lobby's Ranked entry opens the same S36 with a Ranked header variant |
| S36 **Create Game** | 1 `NEW` + header variant | **defaults visible per D5 FINAL** (MEDIUM / BALANCED "Steady" / 15 s); live per-phase summary (Dash/Bid/Estimates = +5 s); Room vs Ranked header; each picker's selected/unselected; disabled confirm until valid |

**S36 is the keystone artboard of this phase.** It is the first new screen a
player touches, and it is shared by both modes. Its default state **must** read
MEDIUM + BALANCED + 15 s with the derived Dash 20 / Bid 20 / Estimates 20 /
Play 15 summary — that summary is the D5 rule made visible, and it is the thing
the owner will check first.

### 4.3.3 C. The match — bidding (S12–S18)

One bidding surface, five sub-phase variants, **plus the hand**. This group
carries the #1 requirement.

| ID | Artboards | States / variants |
|---|---|---|
| S12 DASH | 1 `NEW` | your turn (Dash Call / Decline) / opponent's turn; **hand visible**; timer ring at +5 s |
| S13 AUCTION | 1 `NEW` | your turn (slider 4–13, suit chips, Pass) / opponent's turn; top-bid display; "With" alignment hint; hand visible |
| S14 CONFIRM | 1 `NEW` | rounds 1–13 only; slider `floor..13`; raising frees the suit; hand visible |
| S15 ESTIMATES | 1 `NEW` | slider `0..cap`; forbidden-13 + With-floor hints; Risk Player / Normal Dash markers; hand visible |
| S16 fast round | 1 `VARIANT` | the Quick Round variant: forced-trump ladder shown, **no auction, no confirm** — starts at ESTIMATES. **Full rounds 14–18, Mini rounds 6–10 — same artboard, the ladder numbers read off the match (GM1)** |
| S17 Super Call | 1 `VARIANT` | bid ≥ 8 state: cancels forced trump, extends the match +1 round — the extension must be visible |
| S18 General Pass | 1 `NEW` | all-4-pass → redeal at ×2 (ladder ×2→×8 cap) |

**The #1 gameplay-critical requirement (unchanged from §0.3):** the player's
**13-card hand must be visible and legible in every bidding variant** — S12
through S18. The web reference hides it (that is the bug that closed the
playtest), and the native `BiddingScreen` has no hand composable at all. The
design must show where the hand sits, how it is scaled for mobile, and how it
coexists with the controls without forcing the controls off-screen. **If the
Figma file shows a blind auction anywhere, it is wrong.**

**Timer ring placement (S37) must be drawn into each of S12–S15** — around the
active avatar, and the ring's duration differs by phase (+5 s here vs. the base
timer on Card Play). This is D5 made visible a second time.

### 4.3.4 D. The match — card play (S19–S23)

| ID | Artboards | States / variants |
|---|---|---|
| S19 Table — your turn | 1 `NEW` | full table: 4 seats, trick area, **your hand playable**, legal vs. illegal card states, timer ring at **base** timer, King/Koz badges |
| S20 Table — opponent's turn | 1 `VARIANT` | hand dimmed/disabled, "Waiting for X", **opponent's ring visible**, badges |
| S21 Table — RESOLVING | 1 `VARIANT` | trick complete, winner highlight (900 ms beat), **hand must stay visible** |
| S22 Table — round DONE | 1 `VARIANT` | trick 13 → transition surface into S23 |
| S23 Round Result | 1 `NEW` | per-round scores + deltas; "Waiting for the next round…"; **ranking now also lives in S43 — this screen confirms and explains, it is no longer the only ranking surface** |

**Hand visibility on S21 is the second half of the #1 requirement.** The code
currently drops the hand when the trick resolves. The design must keep it on
screen through RESOLVING and round DONE.

### 4.3.5 E. Post-match (S24–S27)

| ID | Artboards | States |
|---|---|---|
| S24 Final Standings | 1 `NEW` | **4 rows: King / 2nd / 3rd / Koz**; ties → multiple Kings; Sa'ayda badge; extension note; **Vote-Kicked-Koz marker (D10)**; 2nd/3rd/Koz are derived, not stored (§0.6-V6) |
| S25 Match Complete modal | 1 `NEW` | crown, King label, scoreboard, summary, extension note |
| S26 Rematch Vote panel | 1 `NEW` | 30 s countdown, YES/NO, `0/4` tally, waiting list; outcomes ALL_YES / FAILED_NO / FAILED_TIMEOUT |
| S27 Rematch outcomes | 3 `VARIANT` | ALL_YES / "Rematch Declined" / FAILED_TIMEOUT |

**S26 is the pattern S40 and S41 are built on** (§0.6-V7) — design the `Vote
Panel` component here first, then reuse it. Note the difference S40/S41 add: an
excluded target and a majority that is *not* unanimity.

### 4.3.6 F. System, connection & error (S28–S34)

This group is **the biggest design gap in the product** — almost none of it has
a UI. It is also where a player's trust is won or lost.

| ID | Artboards | States |
|---|---|---|
| S28 Loading | 1 `NEW` | generic; match `status: "starting"` variant |
| S29 Reconnecting | 1 `NEW` | persistent banner, last-good-frame kept visible; backoff is invisible to the player — show "reconnecting", not a countdown |
| S30 Disconnected | 1 `NEW` | **6 inbound edges, zero UI today.** Bot takes the seat immediately; in Rooms the S41 vote follows once |
| S31 Waiting for disconnected | → **S42** | Superseded: the pause state *is* S42 now |
| S32 Match Failed | 1 `NEW` | reason + "Back to lobby"; terminal |
| S33 Not In Match | 1 `NEW` | ordinary path, **styled as neutral, not an error** |
| S34 Invalid action | 1 `VARIANT` | inline engine-reason feedback + toast variant; needs the design system to exist first |

**S30/S31 must not be drawn as a generic spinner.** They now carry policy: the
seat is bot-controlled *right now*, and (Rooms only) a vote is coming. The
artboard has to say that.

### 4.3.7 G. Voice — documented only (S35)

| ID | Artboards | Notes |
|---|---|---|
| S35 Push-to-Talk | 1 `NEW, LATER` | Press-and-hold FAB: idle / active / permission-denied. **Rooms only — never Ranked, never the Lobby.** Drawn for completeness so the table layout reserves its space; **not for implementation this phase** |

### 4.3.8 H. Amendment surfaces (S36–S44) — the new system

S36 is specified in §4.3.2. The remaining eight:

| ID | Artboards | States / variants |
|---|---|---|
| S37 Timer Ring | component (§4.2.2) | running / low-time / expired; shown on S12–S15 at +5 s and S19/S20 at base |
| S38 Timeout — bot decided | 1 `NEW` toast | what was decided, by whom ("Medium bot"), and **that it counts toward 15** — without revealing *any other* player's count (D6) |
| S39 Inactivity banner | 1 `NEW` | 50 s inactive → **persistent** banner + seat chip showing bot control; **return-and-reclaim state** (bot stands down, same seat); **distinct from disconnect, from the 15-timeout automatic removal (rejoin = OPEN-1), and from Vote Kick (permanent, no rejoin)** |
| S40 Vote Kick panel | 1 `NEW` modal — **Ranked ONLY (RD18)** | **manual only** — Round 8+ (Round 7 fully complete), unique-Koz target, target excluded, 2-of-3; **purpose = griefing/abuse, not normal mistakes**; live tally over eligible humans only — **bots excluded from the denominator (D7)**; **the target is frozen for the vote's duration (OPEN-2)**; result states: success (permanent, no rejoin, Koz recorded) / failed-no / failed-timeout → **5-round cooldown on the same target only (D8)**. **The automatic 15-timeout removal is NOT drawn here — it is a separate mechanism (RD20) and gets its own feedback state, not a vote panel** |
| S41 Disconnect Vote | 1 `NEW` modal | **Rooms only — impossible in Ranked (RD28 `mode` gate).** One question — "Pause game until the player connect"; disconnected player excluded; **expiry = NO**; **once per disconnect event** |
| S42 Paused | 1 `NEW` full-table state | **Rooms only.** `PAUSED_AWAITING_RECONNECT` with a visible **2-minute** deadline; reconnect → resume, bot stands down; at expiry → the **CONTINUE_OR_WAIT** vote; **WAIT capped at 2 cycles, then CONTINUE-only (D9 FINAL)** — the artboard must show the "second WAIT" state and the CONTINUE-only state, not just the first vote |
| S43 King / Koz badges | component + overlay | crown per tied 1st-place player (**multiple Kings**), Koz badge for current last; **tied last place = no unique Koz = Vote Kick unavailable, and the UI must say so**; recomputed on every score change |
| S44 Quick Stats popover | 1 `NEW` sheet | **long-press** an avatar → the **six short Ranked stats only** (RD23: Rank, Games, King %, 2nd %, 3rd %, Koz %); **must not block or swallow a card-play gesture**; **unblocked by RD11 — the Ranked profile's server read path supplies the data** (the old §0.6-V4 blocker is resolved without touching the frozen rules) |

**S42 is the artboard most likely to be drawn wrong.** The D9 rule is a bounded
loop, not an open wait: initial vote → optional pause (2 min) → CONTINUE_OR_WAIT
vote → optional second WAIT (2 min) → **CONTINUE-only**. Three vote frames are
needed, plus the reconnect-resume frame. If the file shows an indefinite "waiting
for player" spinner, it contradicts D9.

**S40's eligibility arithmetic must be drawn, not implied.** With one human
already removed and a bot in that seat, the eligible-voter count is the
*remaining humans* — the panel shows "2 of 3", never "2 of 4", because bots hold
no vote slot (D7).

**S40 is Ranked-only now (RD18), and that must read in the artboard.** The panel
never appears in a Room match. The 2026-10-05 amendment split what used to be one
surface: manual Vote Kick (Ranked-only, vote, cooldown, permanent) and the
15-timeout automatic removal (both modes, no vote, no cooldown, separate state).
If the file shows an "automatic Vote Kick" flavour, it contradicts RD20.

### 4.3.9 I. Ranked progression surfaces (S45–S58) — the 2026-10-05 amendment

Added by the RD1–RD28 amendment. **None of these exist in code** — the repo has
no rank, RP, tier, division, placement, season, leaderboard, or matchmaking
implementation, and `MatchDoc` carries no `mode` field. They are specified in
full in **§4b** below; the artboard counts here keep the §4.5 summary honest.

| ID | Artboards | States / variants |
|---|---|---|
| S45 Ranked Home / Overview | 1 `NEW` | entry point; current Rank chip + RP + progress; **two paths: "Test my level" (placement) or "Start from Bronze" (permanent, irreversible — RD10)**; season indicator |
| S46 Tier & Division presentation | 1 `NEW` component | **6 tiers × 3 divisions + King = 19 rungs** (never "7 tiers × 3"); **King has NO divisions**; EN + Arabic titles (مبتدئ / لاعب / معلم / وزير / أمير / سلطان / ملك); current rung highlighted; **RD26's 19 lower-bound RP values rendered (Bronze III 0 → King 3,000, §4b.1), never invented numbers** |
| S47 Placement Introduction | 1 `NEW` | the two-path choice; **"Start from Bronze" must state it is permanent — no placement later (RD10)** |
| S48 Placement Match Progress | 1 `NEW` | **only "Placement 1/3 → 2/3 → 3/3" — a provisional rank is NEVER shown** (RD10); bot difficulty/personality are not selectable and not shown |
| S49 Placement Result / "Calculating Rank" | 1 `NEW` | calculating state → reveal |
| S50 Rank Reveal | 1 `NEW` | Tier + Division + Arabic title + RP + progress indicator |
| S51 Rank Progress | 1 `NEW` | progress to next rank; **demotion state; the one-match demotion-protection state (RD7)** |
| S52 Rank Up | 1 `NEW` | three variants: within-division / to a higher Tier / **reaching King** |
| S53 Rank Down | 1 `NEW` | within-division / to a lower Tier |
| S54 Season Overview | 1 `NEW` | season ID + remaining time; **end-of-season demotes two division steps (Gold I → Gold III), King → Royal I (RD24)**; **Highest Rank ever is shown and is NOT erased by the reset** |
| S55 Matchmaking Search | 1 `NEW` | searching → **Match Found** → transition into match; cancel; **the pool rule stated in plain language (own tier or the one below — RD9)** |
| S56 Matchmaking Failure / Timeout | 1 `NEW` | failed / timed out → retry or cancel |
| S57 Ranked Match Result | 1 `NEW` | **both results in one surface:** match result (King / 2nd / 3rd / Koz) AND ranked result (**RP delta, previous rank → new rank, promotion/demotion, season info**); **mixed-tier matches show the RD5 asymmetry in the delta**; **a Mini match shows the same surface with a delta at 50% of its Full equivalent (GM6) — no badge, no disclaimer, no separate layout** |
| S58 Seasonal Leaderboard | 1 `NEW` | position / player / Tier-Rank / RP / season; **current player's row highlighted**; **King players ranked among themselves by RP above the King lower bound**; **season-isolated — no carryover positions between seasons (RD27)** |

**Variants on existing screens (no new IDs):** S03 Profile + rank + the 9 career
stats + Highest Rank; S05 Lobby + rank chip; S08 Waiting Room + rank on roster
rows; the `Seat` identity card + a rank slot; S19/S20 + mixed-tier indicator
(public and private); S44 + short stats (above); S36 Ranked mode state + S36
private-ranked variant (password + cross-tier roster); and **ranked-absence
variants** — S35 voice and S41 Disconnect Vote are enforced *absent* by `mode`,
so their "not available" state never needs drawing, only the gate.

**≈22 new frames + 2 new components (Rank Chip, Tier Ladder).** S58's row states
(current-player highlight, King-ordered, season-scoped) extend S58, not a new
screen.

---

## 4.4 Interaction map — what must be wired in the Figma prototype

The Figma file should wire the flows so the owner can *play* the design. Three
layers:

### 4.4.1 The golden path (§2.1)

```
S01 → S02 → S05 → [S36 → S06 share sheet] → S08 → S28 →
  S12 → S13 → S14 → S15 → S19 → S21 → S22 → S23 →
  (rounds loop back to S12/S16) → S24 → S25 → S26 → S27 → S05
```

Every arrow is a prototype connection. The round loop (S23 → S12 for normal
rounds, S23 → S16 for fast rounds — **the boundary is Game Type-dependent:
Full 2–13 normal / 14–18 fast, Mini 2–5 normal / 6–10 fast, GM1**) must be wired
so the round structure is
walkable end to end — this is what makes the loop counts in §2.4 reviewable in
Figma instead of in the imagination. **Wire the Full loop as the default and add
the Mini loop as the same connections with different round numbers**, so both
are walkable and the shared S12/S16 artboards are proven to not hard-code 18.

### 4.4.2 The four exception paths (§2.1, kept conceptually distinct)

These are **four different stories** and must not collapse into one "player gone"
screen:

- **A. Timeout** → S37 ring expires → S38 toast ("Medium bot decided") → play
  continues, **player stays seated**.
- **B. Inactive (50 s)** → S39 persistent banner + bot seat chip → player
  returns → banner clears, **human reclaims the seat**.
- **C. Disconnect** → S30 → (Rooms only) S41 vote once → S42 pause (2 min) →
  CONTINUE_OR_WAIT → possibly a second WAIT → CONTINUE-only → play resumes with
  the bot.
- **D. Vote Kick** → S40 → success = **permanent** removal, no rejoin, Koz
  recorded (D10); failure = **5-round cooldown** on the same target (D8).

### 4.4.3 The overlay layer

S37 (timer ring), S43 (badges), S44 (quick stats), S35 (push-to-talk) are
**overlays on S19–S23**, not destinations. Wire them as components placed on the
table artboards so the designer can verify they coexist: a player must be able to
see their hand, the timer ring, both badges, and still play a card — with the
long-press popover never swallowing a tap intended as a play.

---

## 4.5 Artboard inventory summary — the designer's checklist

| Group | Artboards | Notes |
|---|---|---|
| A. Entry & identity | 4 | S01, S02, S03 (+stat grid), S04 |
| B. Lobby & setup | 3 new + S36 | S06, S07, S36; S05/S08 are `DONE`, reference only |
| C. Bidding | 7 | S12–S18, **all with the hand visible** |
| D. Card play | 5 | S19–S23 |
| E. Post-match | 6 | S24, S25, S26, S27 ×3 |
| F. System & error | 6 | S28, S29, S30, S32, S33, S34; S31 is S42 |
| G. Voice | 1 | S35 — documented only |
| H. Amendment | 8 | S37 (component), S38, S39, S40, S41, S42, S43 (component), S44 |
| **I. Ranked progression (2026-10-05, RD1–RD28)** | **14 new (S45–S58) + ≈22 variant frames** | §4.3.9 above and §4b below; **+2 components: Rank Chip, Tier Ladder** |
| **Total new artboards** | **≈ 63** (was ≈ 40) | plus the §4.2 component library and the two new Ranked components |

---

## 4.6 Designing for the four-way distinction (the owner's standing rule)

The owner has required four times that these concepts never merge. The Figma file
must make them visually distinct, not just textually:

| Concept | Visual signature | Returns? |
|---|---|---|
| **Timeout** | transient toast (S38); seat unchanged | Player never left |
| **Inactive** | persistent warm banner + bot seat chip (S39) | **Yes — same seat reclaimed** |
| **Disconnect** | full-table state + Rooms-only vote + bounded pause (S30/S41/S42) | **Yes, if the table waits** |
| **Vote Kick (manual)** | destructive-red modal (S40), **Ranked ONLY (RD18)**; permanent note in Final Standings (S24) | **No** |
| **15-timeout automatic removal (RD20)** | **separate from Vote Kick — no modal, no vote, no cooldown; its own removal state** | **OPEN-1 — not finalized** |

The rule for the designer: **if two of these look alike, the design is wrong.**
Colour, duration, and reversibility are the differentiators — toast vs. banner
vs. full-table state vs. destructive modal.

---

## 4.7 Design gates — what must be true before the Figma file is called complete

These are the acceptance checks for Phase 4. Each is answerable by looking at the
file:

1. **The hand is visible in every bidding artboard (S12–S18) and stays visible
   through S21 RESOLVING and S22 round DONE.** (The #1 requirement.)
2. **S36's default state reads MEDIUM + BALANCED "Steady" + 15 s, with the
   derived Dash/Bid/Estimates 20 s and Card Play 15 s summary.** (D5 FINAL.)
3. **S42 shows a bounded pause: three vote frames including the CONTINUE-only
   state, and no indefinite spinner anywhere.** (D9 FINAL.)
4. **S40's tally is over eligible humans only — bots never appear as voters or in
   the denominator.** (D7.)
5. **S40 shows the 5-round cooldown consequence on failure, and S24 shows the
   permanent Koz recording on success.** (D8, D10.)
6. **S43 renders multiple Kings for a tied first place, and shows the
   "Vote Kick unavailable — last place is tied" state.** (Unique-Koz.)
7. **S38/S39 never reveal another player's timeout count.** (D6.)
8. **S41/S42/S35 are absent in Ranked variants of the table and lobby flows.**
   (Rooms-only.)
9. **Every text colour passes 4.5:1 against `bg`, `surface`, and `surfaceHigh`.**
10. **All numerals are Spline Sans Mono** — scores, bids, timers, codes, tallies.
11. **S44's long-press popover does not cover the hand or swallow a play tap.**
12. **The golden path is wired end to end, including the round loop and the
    fast-round ladder — Full (18 rounds, ladder from 14) by default, and Mini
    (10 rounds, ladder from 6, exactly one extension) as the same connections
    with different numbers, proving the shared screens do not hard-code 18.**

---

## 4.8 What is still blocked, and what this phase deliberately does not do

**Still blocked on the owner (logistics only — no decision is reopened):**

1. **The Lobby and Waiting Room Figma sources.** Until they are shared, this plan
   derives tokens from the shipped code that mirrors them (§4.1) and does **not**
   claim visual consistency with the designs themselves. Phase 5 is the
   reconciliation pass once they arrive.
2. **The implementation branch base** — `android/phase5-online-match` vs `main`.

**Deliberately out of scope for Phase 4:**

- **No UI code, no Compose, no layout XML, no Code Plan** — per the owner's
  direction, Figma comes first.
- **No game-logic changes.** S36/S37/S39/S40/S41/S42/S43/S44 are designed as
  surfaces; their backends (§0.6 V1–V7) are Phase 6.
- **No Push-to-Talk implementation or microphone work.** S35 is drawn for
   completeness only.
- **No changes to Batch 1.** §0–§2.8 are frozen.

**The one thing this phase needs from the owner to finish:** nothing. The token
foundation is verifiable from shipped code, and the two finished screens are
reference-only constraints, not inputs. Phase 4 can be executed now; Phase 5
(visual consistency) is the step that needs the two Figma files.

---

**Phase 4 status: SPECIFICATION COMPLETE — READY FOR FIGMA EXECUTION.**
No code has been written. Batch 1 remains frozen. Phases 5–9 remain unstarted.

---

# BATCH 2 — PHASE 4b: THE RANKED PROGRESSION SYSTEM

Added by the **2026-10-05 owner amendment (RD1–RD28)**. This section mirrors
§4's structure and is the design plan for the **S45–S58** artboards in §4.3.9.
Everything here is a design decision the Figma file must make explicitly, or a
constraint it must respect. **Nothing here is code, Firestore rules, Cloud
Functions, or matchmaking implementation — planning only.** The code/architecture
counterpart is epic **E6b (S42–S64, +388 h)** in
`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`.

**The one-line framing:** Phase 4 designed the *table*. Phase 4b designs what the
table is *for* — the reason a player comes back after the 18th round. Ranked is
launch-blocking MVP scope (**RD25**), so this is not a post-v1 attachment to the
file; it is part of the same delivery.

## 4b.1 The ladder — 19 ranks and their identity

**Structure (RD1):** **6 tiers × 3 divisions + King = 19 ranks.** Never write
"7 tiers × 3 divisions" — King has **no divisions**. Tiers, lowest to highest:
**Bronze, Silver, Gold, Platinum, Diamond, Royal**, each **III → II → I**
(**RD2: I > II > III** — Gold I → Gold III is a *two-step demotion*, never the
reverse). King sits above Royal I as the single 19th rung.

**Arabic titles are product identity (RD1), one-to-one, never genericized:**

| Tier | EN | AR |
|---|---|---|
| 1 | Bronze | مبتدئ |
| 2 | Silver | لاعب |
| 3 | Gold | معلم |
| 4 | Platinum | وزير |
| 5 | Diamond | أمير |
| 6 | Royal | سلطان |
| King | King | ملك |

**Rank-King vs. Match-King vs. Koz — the three-way distinction the designer must
not fuse (RD8):**

| Concept | What it is | Where it lives |
|---|---|---|
| **Rank King** | the account's competitive ceiling; the 19th rung; no divisions | the profile, S46/S51/S52 |
| **Match King** | first place in **one** match; ties give multiple Kings | the table, S43; the result, S24/S57 |
| **Koz** | the **current unique last place** in one match — not a rank at all | the table, S43; Vote Kick eligibility, S40 |

The `#E8A33D` gold in the tokens belongs to the **Match-King badge**. The **Gold
tier** must not reuse it (§4.1.4 #5).

**Thresholds (RD3/RD26 — CLOSED 2026-10-05):** every rank has a **lower-bound
RP**; promote at the next rank's lower bound, demote below the current floor.
**RP ≥ 0 always.** **RP is dynamic (RD4), never a fixed ±X per match** — these
are only the rank boundaries the dynamic engine measures against. King has a
lower bound and **no upper bound** — RP above it is **leaderboard-only** (RD27).
**The 19 thresholds are FINAL owner-approved values** (set 2026-10-05); the
engine stays independent of the constants so the numbers remain tunable without
redesign, and a v1.1 balance pass from playtest data is expected rather than a
sign the decision was wrong. Nothing is derived from the `design-ui` mocks —
their values are non-canonical legacy data (§2.7 check 55).

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

## 4b.2 Placement — the one-time entry

**RD10.** Placement happens **once per account, never per season.** The player
arrives at S45 Ranked Home and chooses one of two paths:

```
S45 Ranked Home
   │
   ├── A: "Test my level" ──► S47 Placement Introduction
   │        (states the 3-match shape; difficulty/personality NOT selectable)
   │            └─► S48 Placement Match Progress  ×3
   │                    (shows ONLY "Placement 1/3 → 2/3 → 3/3"
   │                     — a provisional rank is NEVER shown)
   │                        └─► each match: system-controlled bots
   │                            M1 Easy+Medium · M2 Medium+Hard · M3 Hard+Expert
   │            └─► S49 "Calculating Rank"
   │                    └─► S50 Rank Reveal
   │                            (Tier + Division + Arabic title + RP
   │                             + progress indicator)
   │                                └─► S45 Ranked Home
   │
   └── B: "Start from Bronze" ──► permanent Bronze III (مبتدئ III)
            — stated as IRREVERSIBLE: no placement later, no way back
```

**Design rules the file must obey:**

- **Score is derived from real engine signals** — placement, result, score,
  estimate accuracy, bidding performance, consistency — **weighted 10 / 20 / 70**
  across M1 / M2 / M3 (RD10, FINAL).
- **Maximum placement = Platinum.** Never Diamond, Royal, or King by placement.
- The player starts at **that division's first / lower-bound RP**.
- **Placement matches count toward NO statistic** (RD23) — not Games Played, not
  any percentage. The 9-value accumulator skips them.
- The provisional rank is **never visible at any point** — not between matches,
  not in S48, not as a teaser. Only 1/3, 2/3, 3/3.
- The two paths must be presented as **genuinely equal in dignity** — "Start from
  Bronze" is a legitimate choice, not a punished one; the irreversibility is
  stated plainly, not as a warning that pushes the player toward placement.

## 4b.3 Rank progression — the match-end loop

Ranked results are computed **once, at match end, by the backend** — never live,
never client-side (RD12/RD13). The loop the UI must express:

```
match ends (all 18+ rounds, or early termination by removal)
   │
   └─► settlement (Cloud Function, once per match, idempotent)
          │  re-reads the CONVERGED action document,
          │  re-runs the pure rules engine over it,
          │  recomputes the authoritative result,
          │  and CORRECTS a client finalScores that disagrees (RD12)
          │
          └─► five possible outcomes
                 ├── 1. RP gain, no rank change ──► S51 progress bar advances
                 ├── 2. promotion, same tier (division step) ──► S52
                 ├── 3. promotion to a higher TIER ──► S52
                 ├── 4. demotion (division or tier) ──► S53
                 │      └─► one-match demotion protection (RD7) shown in S51
                 └── 5. at the King ceiling: RP accrues, rank cannot rise
                        └─► leaderboard-only overflow (RD3) — S51 must say so
   │
   └─► S57 Ranked Match Result — BOTH results on one surface:
          match result (King / 2nd / 3rd / Koz, ties honoured per RD17)
          AND ranked result (RP delta, previous rank → new rank,
                             promotion / demotion, season info)
```

**Design rules:**

- **One surface, two results (S57).** The match outcome and the ranked outcome
  are never split into two screens — the player sees "you came 2nd" and "+24 RP,
  Silver II → Silver I" together.
- **Mixed-tier matches must be able to show the RD5 asymmetry in the delta**
  (§4b.4), and S57 is where it is explained, not hidden — a higher-tier loser
  needs to see *why* the loss was multiplied, or it reads as a bug (R28).
- **Demotion protection is a visible, limited state (RD7): exactly one match.**
  S51 shows it as a labelled shield state with a countdown of one, never as a
  permanent buff.
- **RP never goes negative (RD3)** — the UI never renders a negative RP value;
  a loss at the ladder floor shows 0 and no demotion below the bottom.

## 4b.4 Matchmaking — the public Ranked pool (RD9)

**Server-controlled. The player never picks a tier pool; the server derives it.**

- **Own tier, or exactly one tier below — never two or more.**
- A **lower-tier player cannot opt up.** Silver cannot request a Gold pool.
- The server **may form a mixed Gold/Silver match.** Do not write or design
  "Gold players can only be matched in Gold" — mixed pools are permitted and
  expected.
- The pool rule is **stated in plain language on S55** ("you will be matched in
  your tier or the one below"), because an invisible rule reads as a hang.
- **Search lifecycle:** `IDLE` → `SEARCHING` → **`MATCH_FOUND`** → transition into
  match; `SEARCHING` → `FAILED` / timed out → **S56** (retry or cancel). Cancel
  is always available; there is no uncancelable spinner.
- The client sends a **search request only** — never a tier claim. Pool authority
  is server-side (RD9; code S57 is the anti-abuse story).
- **Game Type rides along, and is orthogonal to the pool (GM5, 2026-10-06).**
  The search carries the player's **Full / Mini** choice, and the server forms
  matches for it — but the tier-pool derivation above **never widens, narrows, or
  branches on Game Type**. Mini is a Ranked format in its own right: **do not
  disable it, hide it, segregate it into a casual queue, or imply it is
  unranked.** A Mini search uses the same pool rule, the same mixed-tier
  permissions, and the same S55 plain-language statement. **The only Ranked
  difference is the RP reward magnitude, shown at match end (§4b.6), never
  here** — the search surface must not advertise a "smaller reward" as a
  warning, a paywall, or a downgrade.

**Mixed-tier is the normal case, so the table must say so:** the S19/S20
mixed-tier indicator (public and private variants, §4.3.9) is a first-class
artboard, not an edge case. When tiers are mixed, **RD5 applies and the result
surface explains it:**

| Direction | RP effect (RD5) |
|---|---|
| Higher-tier player **wins** | reward **reduced** |
| Higher-tier player **loses** | loss **multiplied** (×2 hard cap in Private — RD6) |
| Lower-tier player **beats** a higher-tier opponent | reward **increased** |

All three directions are design inputs — the deltas are asymmetric on purpose,
and S57 must render the direction, not just the number.

## 4b.5 Private / Password Ranked (RD6)

**Not a third mode (RD28).** Private Ranked is **`RANKED` + a private/password
access flag** — the mode stays `RANKED`, RP is awarded, statistics accumulate,
Vote Kick is available, and settlement is identical.

- **Any tiers together — no restriction at all.** A Bronze and a King may share a
  private table.
- It is a **true Ranked match**, not casual and not unranked.
- **The same mixed-tier rules as public (RD5) apply, with a hard ×2 cap on the
  loss multiplier — never more than ×2.**
- **Access only.** The password gates entry; it gates nothing about the outcome.
- **Design consequence:** the S36 private-ranked variant (password field +
  cross-tier roster) is one artboard variant, not a new screen; the roster must be
  able to show visibly different tiers without implying they are mismatched.

## 4b.6 RP architecture

**Dynamic, not fixed ±X (RD4).** Inputs the engine consumes — and the UI must
never lie about:

1. **Opponent strength**
2. **Tier difference between the players**
3. **Match outcome**
4. **Final placement / performance**
5. **Mixed-tier conditions**

**Design constraints that fall out of the architecture:**

- **RP ≥ 0, always (RD3).**
- **The engine is independent of the threshold constants**, so the 19 closed
  values (RD26, set 2026-10-05) remain tunable without touching the engine or the
  UI — the ladder renders structure, and the numbers land behind it.
- **The client never computes RP.** It displays a settled, server-owned value.
  The UI's job is to render the delta and the direction honestly — including
  "settling…" when the function is cold-starting (R24), **never a blank and never
  a fabricated number.**
- **No player-facing match history in MVP (RD23)** — the profile carries the 9
  career statistics instead. An **internal RP audit ledger IS required** (code
  S62), but it is not a player surface and must not be designed as one.

**Mini's RP is half of Full's, and nothing else moves (GM6, 2026-10-06).** This
is a single multiplier on the **final** delta, applied once at settlement:

- **The full Ranked result is computed exactly as today first** — every RD4
  input, every RD5 asymmetry, every modifier — and **only then** is the final
  delta multiplied by **0.5**: `miniDelta = fullEquivalentDelta * 0.5`. Gains and
  losses alike (+20 → +10, −14 → −7).
- **Everything else is identical between Full and Mini and must be designed as
  identical:** the 19-rank ladder, the 19 RD26 thresholds, divisions, gates and
  eligibility, progression, promotion and demotion, the one-match demotion
  protection (RD7), the mixed-tier asymmetry (RD5), the Private ×2 cap (RD6),
  placement, and seasons (RD24). **No threshold is lowered, no gate is relaxed,
  and nothing is compensated for the shorter match.** There is no separate Mini
  RP formula.
- **Design consequence — one number changes, no new surface:** S57 Ranked Match
  Result renders the delta and its direction exactly as it does for Full. Mini
  does not get a smaller podium, a different colour, a "reduced reward" badge, or
  an explanatory disclaimer — the delta is simply smaller. The match-length
  context the player already has (the round indicator) is enough.
- **Rounding is CLOSED, and it is not a design choice (OPEN-3, closed
  2026-10-06):** nearest integer, **ties rounded away from zero**, symmetrically
  for gains and losses — +15 → **+8**, −15 → **−8**, +9 → +5, −9 → −5, while
  even deltas stay exact (+20 → +10, −14 → −7). Under the `Int` convention every
  scoring type in `:engine` uses, that is the whole rule. **Design consequence:
  an odd-delta Mini gain or loss can show one RP more than a naive half (+15
  shows +8, not +7), and a Mini loss is symmetric with the equivalent gain.
  S57 renders the resulting integer exactly as it renders any other delta — no
  asterisk, no "rounded" note, no client-side rounding of any kind.**

## 4b.7 Seasons (RD24)

- **Duration: 3 months.** A season has an ID, a start, and an end.
- **End-of-season demotes Tier/Division two division steps** — **Gold I → Gold
  III** — floored at the ladder bottom. Never one step, never three.
- **King → Royal I** at season reset (owner-closed). King has no divisions, so
  this is a *destination*, not a division change (RD1 preserved).
- **Career statistics NEVER reset.** The 9 values are lifetime.
- **Highest Rank ever is never erased** by seasonal demotion — the profile keeps
  it as a permanent record (RD23).
- **Placement never repeats per season** (RD10 — once per account).
- **No separate trophy system (RD24)** — the season itself is the reward
  structure; do not design a trophy room.
- **S54 Season Overview** is where this is visible: season ID + remaining time +
  the reset rule stated plainly + Highest Rank shown as untouchable. The reset
  must not read as a punishment; it reads as a new cycle.

## 4b.8 Seasonal Ranked Leaderboard (RD27)

**Server-authoritative ordering — the client may read, never order.**

| Column | Content |
|---|---|
| Position | rank position in the season, server-computed |
| Player | display identity |
| Tier / Rank | with the tier token and Arabic title |
| RP | the ordering key |
| Season | the season this board belongs to |

**Design rules:**

- **The current player's row is highlighted** — the reason a player scrolls.
- **King players are ranked among themselves by RP above the King lower bound**
  (RD3) — King has no divisions, so **RP is the only ordering key there**. The
  board must render the King band as a single ordered group, never as "King I /
  King II".
- **Season isolation:** the board shows **one season only** and resets with the
  season. **No carryover positions between seasons** — a new season produces a
  fresh board. Never blend.
- The row states (current-player highlight, King-ordered, season-scoped) **extend
  S58**, they are not new screens.

## 4b.9 Profile and statistics impact

**Exactly 9 career statistics (RD23) — never "10 values":**

1. Games Played
2. King count
3. King %
4. 2nd count
5. 2nd %
6. 3rd count
7. 3rd %
8. Koz count
9. Koz %

**Scope:** include **Public Ranked and Private Ranked**; **exclude Placement**
(RD10). A Vote-Kicked removal is recorded as a **Koz** outcome (D10).

**Two display surfaces, deliberately different:**

| Surface | Shows |
|---|---|
| **S03 profile stat grid** (full) | all 9, plus **Highest Rank ever** (never erased — RD23) |
| **S44 long-press popover** (short) | **Rank, Games, King %, 2nd %, 3rd %, Koz %** — six values only |

**Design rule:** the short set is not a truncated long set — it is the six values
that matter *during a match*. Never show match history in either (none exists in
MVP).

## 4b.10 In-match identity impact

Ranked changes what the table itself carries, without changing the table's
layout:

- **S03 + rank chip + 9 stats + Highest Rank** — the profile gains a ranked
  identity block.
- **S05 + rank chip** — the Lobby shows it; rank is identity, not just a number.
- **S08 + rank roster rows** — the Waiting Room shows it per seat.
- **`Seat` identity card + rank slot** — the table seat gains a rank slot beside
  the existing badge slot, and the **Rank Chip must not collide with the
  Match-King crown** (§4b.1, §4.1.4 #5).
- **S19/S20 + mixed-tier indicator** — public and private variants.
- **S44 + short stats** — the popover reads the six short values.
- **S36 Ranked mode state + private-ranked variant** — one screen, two modes, and
  the private variant adds the password field and the cross-tier roster.
- **Ranked-absence variants (enforced by `mode`):** **S35 voice and S41 Disconnect
  Vote must be visibly absent in the Ranked flows** — not disabled, not greyed,
  **absent** (RD22/RD28). These are required artboards in §4.3.9, and they are
  the easiest thing to forget.

## 4b.11 The authority model — where Ranked trust lives

The owner required this to be concrete, not a restatement of "the server
validates things." **Four tiers, each with a named mechanism:**

| Tier | What lives here | Named mechanism |
|---|---|---|
| **Client — read-only after settlement** | match UI, rank display, statistics read, matchmaking search *request* | Never writes RP/rank/result. **Enforced by the existing `firestore.rules` deny-list on progression fields — the rules stay byte-identical.** |
| **Client — converged, transient** | in-match gameplay state: bids, estimates, card plays | Written to the shared match document by all four clients and already convergent — this is what the existing reload-safe replay depends on. **The RD14 accepted residual: the server does not adjudicate these in realtime.** It is not untrusted — it is multi-client-converged, which makes single-client forgery visible. |
| **Cloud Functions — authority, invoked once per match, not always-on** | settlement, RP, placement scoring, matchmaking pool resolution, season reset, Vote Kick eligibility by `mode` | The settlement function **re-reads the converged match document, re-runs the pure rules engine over the converged action sequence, recomputes the authoritative result, and CORRECTS a wrong client `finalScores` (RD12)** rather than trusting or rejecting it outright. |
| **Firestore — source of truth** | RankedProfile, Season, RP audit ledger, match `mode` | Post-settlement the client's read of its own rank/RP/stats is the only path — there is no client write path to close. |

**Why this is enough for launch, stated plainly:** forging Ranked progression
under this design requires forging the converged multi-client action history that
all four players' devices agree on — materially harder than editing a score field,
and detectable at settlement. It does **not** stop a determined cheater who
controls all four clients; **that is the known residual, accepted at MVP under
RD16** and revisited when cheating pressure, scale, revenue, or competitive
pressure justifies realtime authority.

**Residual risks, named:** (a) a fully colluding table can still produce a
plausible-looking converged history; (b) in-play state is not server-adjudicated,
so subtle in-play exploits are caught only if they break convergence or engine
invariants; (c) settlement is retrospective, so a detected fraud can be corrected
but not prevented mid-match. Each is logged in the RP audit ledger (code S62).

**The split in one line (RD15):** **Rooms** are lower-security and
cost-sensitive — no RP progression, no Ranked authority needed. **Ranked**
protects its critical outcomes with the backend.

## 4b.12 The concrete validation mechanism

**The mechanism, not the phrase "server-side validation":**

1. **Ranked-critical actions are checked where they land.** Bidding, estimates,
   and card plays already converge to a shared Firestore document (that is what
   makes the existing reload-safe replay work). Settlement re-reads **that
   converged document** — not the client's claim — and treats the converged
   action history as the input of record.
2. **Deterministic re-derivation.** The rules engine is pure and already
   test-covered. Settlement re-runs scoring over the converged action sequence to
   recompute the authoritative final placement/scores. A client `finalScores` that
   disagrees is **corrected (RD12)** — the recomputed value is what settles.
3. **Server-owned invariants, checked at settlement.** `mode` authority (Ranked
   vs Room), unique-Koz eligibility for Vote Kick, Round-7 completion, tier
   eligibility per RD9, placement weights, the 9-stat accounting, and the
   idempotency key. These are properties the server derives from documents the
   client cannot forge, because `firestore.rules` already denies progression
   writes.
4. **What the server does NOT do.** It does not adjudicate each card in realtime;
   it does not hold authoritative game state during play. In-play state stays
   client-converged. **This is the RD14 residual, stated openly.**

**Why this design:** it is cheap (one function invocation per match, no always-on
process), it reuses the engine's existing purity and the rules' existing
deny-list, and it makes forged Ranked progression require forging a converged
multi-client action history.

## 4b.13 Component inventory for the Ranked surfaces

**Data (1–7):** Rank/Tier/Division enums · Arabic title resources · RP threshold
table (tunable, RD26) · RankedProfile doc (tier, division, RP, seasonId,
highestRank, placementState, 9 stats) · Season doc (id, start, end) · RP audit
ledger (internal) · Leaderboard doc (per-season ordering, King ranked by RP above
the King lower bound).

**Backend (8–14):** `functions/` module · settlement callable (correct-and-settle,
RD12) · idempotency + auth guard (RD13) · RP engine (RD4/5/6) · matchmaking pool
resolver (RD9) · season reset job (RD24) · leaderboard ordering builder
(server-authoritative, season-isolated).

**Engine/native (15–21):** `mode` on MatchDoc (code S43) · placement director
(scripted matches, RD10) · placement scorer (10/20/70) · statistics accumulator
(RD23) · Vote Kick controller (RD18, Ranked-only) · **15-timeout
automatic-removal controller (RD20 — a separate mechanism from Vote Kick, sharing
no vote state)** · timeout/inactivity takeover controller (RD19: MEDIUM engine on
timeout, configured bot on leave) · season reset (RD24, King → Royal I).

**UI (22–27):** Rank Chip · Tier Ladder · Placement flow (S47–S50) · Matchmaking
states (S55/S56) · Ranked result (S57) · Season Overview (S54) · Leaderboard
(S58).

**The two Figma components this adds to §4.2's library:** **`Rank Chip`** (tier
token + EN/AR title + RP, sized for a seat slot and a roster row) and **`Tier
Ladder`** (18 divisional rungs + the division-less King destination, current rung
highlighted, RD26's closed lower-bound values).

## 4b.14 Dependency map — the critical path

```
mode field (S43) ──► settlement (S50/S51) ──► RP engine (S47–S49)
        │                                          │
        │                                          ├──► statistics (S55)
        │                                          │
        │                                          └──► seasons (S59) ──► leaderboard (S64)
        │
        ├──► Vote Kick Ranked-only gate (S60)      placement (S53/S54) branches
        │                                          off S44 and runs parallel to S50
        └──► timeout tracking (S61)
```

**S43 (`mode`) and S42 (`functions/`) are on the critical path for nearly every
Ranked feature.** The UI roadmap says the same about `mode` as the code roadmap;
**after this amendment the two documents agree.**

| Feature | Depends on | Note |
|---|---|---|
| Ranked UI (S45–S53, S58) | RankedProfile model, ladder defs, threshold table | RD26 thresholds CLOSED (2026-10-05) — UI binds the real numbers, still tunable behind the engine seam |
| Placement (S53/S54) | profile model, RP engine, the scripted-bot director | Parallel to settlement; "Start from Bronze" needs only the profile model |
| RP / settlement (S47/S50/S51/S52) | functions module, `mode`, the pure rules engine (exists, test-covered) | **Critical path.** Validation consumes the same converged action history reload-safe replay already depends on — no new in-play authority is invented |
| Matchmaking (S56/S57) | authoritative rank (server-side), `mode` = RANKED | Pool derivation reads server-side rank, never a client claim |
| Leaderboard (S64) | seasons, RP engine | Ordering is server-computed; King's ordering key is RP |
| Season reset (S59) | profile model, RP engine | **One-way data migration — emulator dry-run required (R27)** |
| Vote Kick (S60) | `mode` = RANKED, Koz tracking | Target frozen for the vote's duration (OPEN-2). Never in Rooms |
| Timeout tracking (S61) | `mode` | Counter cumulative and **private**; 15-timeout removal shares no state with Vote Kick |
| Quick Stats (S44 UI) | statistics accumulator, profile model | Unblocked by RD11's server read path; shows the 6 short values |
| Private Ranked (S58 story) | settlement, mixed-tier asymmetry, `mode` + private flag | `mode` stays RANKED (RD28); the flag gates access only |
| **Game Type / Calculation (E7, S65–S69)** | **precedes every Ranked story that consumes `gameType`** — S43, S47, S56 | **The 2026-10-06 GM amendment is on the Ranked critical path.** S47 cannot apply GM6's ×0.5 and S56 cannot carry a Game Type on a match document that has no field. **E7 lands first**, and its `firestore.rules` change (the only rules write outside the Ranked epic) is re-pinned before E6b starts |

## 4b.15 Testing plan (designed now, run at the Phase 9 gate)

- Forged `finalScores` is **corrected** and the corrected result settles (RD12).
- Settlement is **idempotent** across replayed calls; duplicates/retries settle
  once.
- **RP never negative.**
- Mixed-tier asymmetry in **all three directions**; private ×2 cap enforced.
- Demotion + **one-match** protection; King ceiling: RP accrues, leaderboard-only.
- Placement: 10/20/70 weighting, Platinum ceiling, **no provisional rank shown**,
  counts toward **no** statistic.
- Matchmaking: own tier or one below, **no opt-up**, mixed pool permitted.
- **Game Type (GM1–GM3, added 2026-10-06):** a Full match still plays 18 rounds
  with Quick from 14 and still extends up to 5 times — **the existing golden
  tests are the regression gate**; a Mini match plays 10 with Quick from 6 and
  extends **exactly once**, the second attempt returning `ALREADY_EXTENDED**
  with `extended = false`; Mini round 6 returns Sans instead of throwing
  `IndexOutOfBoundsException` (the crash this amendment fixes); a Mini room
  created with `maxRounds == 18` is **denied**; a Mini extension on a doc that
  already has one entry is **denied at the rules**, proving the cap holds when
  the client lies.
- **Calculation (GM4):** both formulas byte-unchanged; a Classic match's
  escalation cap is ×2 and a Normal match's is ×8; **the pre-existing gap where
  the quick-match path omitted `escalationCap` entirely is closed** — Classic
  no longer silently inherits the Normal cap.
- **Mini Ranked RP (GM6):** the same Full-equivalent result yields **half** the
  delta (+20 → +10, −14 → −7), the multiplier is applied **once at settlement**,
  and **all four tie cases are asserted separately (+15 → +8, −15 → −8, +9 →
  +5, −9 → −5)** against the closed OPEN-3 rule, including the `Math.round`
  asymmetry test (−7.5 must round to −8, not −7). RP never goes negative as a result of the
  multiplier.
- Season reset: two-step demotion direction (Gold I → Gold III), ladder floor,
  **King → Royal I**, career stats intact, Highest Rank intact.
- Leaderboard: ordering is server-computed, never client-ordered; **King players
  sort by RP above the King lower bound**; the current player's row is
  highlighted; a new season produces a fresh board with **no carryover
  positions**.
- Vote Kick: Ranked-only enforced by `mode`; Round-7-complete gate; tied-last
  blocks it; 2-of-3 passes; 5-round same-target cooldown; success = permanent +
  no rejoin + recorded Koz.
- **15-timeout automatic removal fires with NO vote, NO Round-7 gate, NO Koz
  requirement, and does not consume or reset the Vote Kick cooldown — the two
  mechanisms never interfere.**
- Timeout → **MEDIUM** engine on all four clients; leave → configured bot.
- **A Vote Kick vote cannot change its target mid-vote** (OPEN-2).
- Tie for 1st → all Match Kings; shared placements counted (RD17).
- Long-press short stats never blocks a legal play.
- A bot-controlled seat never votes.

## 4b.16 Design gates for the Ranked artboards

Additional acceptance checks, answerable by looking at the file:

1. **The Tier Ladder renders 18 divisional rungs + King as a single
   division-less destination** — never "King III/II/I", never "7 tiers".
2. **The Gold tier does not reuse the crown glyph or `#E8A33D`**, and the Royal
   tier is visually distinct from the app's default aesthetic.
3. **The Rank Chip and the Match-King crown are instantly separable at table
   scale** though both sit on the same avatar.
4. **Arabic titles appear alongside EN tier names** in the ladder and the chips.
5. **S48 shows only 1/3, 2/3, 3/3** — no provisional rank anywhere in the flow.
6. **S47's "Start from Bronze" states its irreversibility without framing it as a
   punishment.**
7. **S57 shows match result and ranked result on one surface, and can explain a
   mixed-tier delta.**
8. **S51's demotion protection reads as exactly one match**, never permanent.
9. **S55 states the pool rule in plain language and is always cancellable; S56 is
   never a dead end.**
10. **S58's King band is a single RP-ordered group; the current player's row is
    highlighted; nothing carries over between seasons.**
11. **S35 and S41 are visibly ABSENT in the Ranked flows** — not disabled.
12. **Private Ranked adds a password field and a cross-tier roster without
    implying a third mode.**
13. **Every Ranked threshold number in the file is the owner's closed value**
    (§4b.1) — no invented value, and nothing derived from the `design-ui` mocks.
14. **S36 offers exactly five groups — Game Type, Calculation, Bot Difficulty,
    Bot Personality, Decision Timer — and no third Game Type or third
    Calculation mode exists anywhere in the file** (GM1/GM4). Mini is presented
    as Full with a shorter count, never as a different game with its own rules.
15. **No surface disables, hides, segregates, or disclaims Mini in the Ranked
    state** (GM5) — and no surface advertises Mini's smaller RP reward as a
    warning, paywall, or downgrade (GM6).
16. **No round-count or Quick-Round visual is drawn from a literal 18 or 14** —
    every "round N of M", progress bar, and forced-trump ladder reads the
    match's own `maxRounds` and boundary (§2.4).
17. **No artboard dangles a second Mini extension** — the cap is one, and it is
    never offered (GM3).

## 4b.17 Consistency-audit additions

Banned phrasing anywhere in the two roadmap documents, each expected to return
zero hits after this amendment: "Ranked is post-v1" (RD25) · "matchmaking only
later" (RD25) · "King has three divisions" / "7 tiers × 3 divisions" (RD1) ·
"Gold III → Gold I" as a demotion (RD2) · client-authoritative Ranked scoring /
RP (RD13) · `finalScores` blindly trusted (RD12) · Vote Kick in Rooms / before
Round 7 completes / with tied last place (RD18/17) · inactive players cannot
reconnect (RD19) · timeout applies only to card play (RD20) · wrong bot
difficulty on timeout (RD20) · "10 Ranked statistics" (RD23) · Placement counted
as Games Played (RD10) · Placement repeated every season (RD24) · Private Ranked
treated as unranked (RD6) · Ranked voice enabled (RD22) · Silver manually
selecting a Gold pool (RD9).

**Banned phrasing added by the 2026-10-06 GM amendment:** "Ranked is Full-only"
/ "Mini is casual-only" (GM5) · a Mini-specific bid, estimate, Super Call,
Dash, or scoring rule of any kind (GM1 — Mini inherits Full verbatim) · a
separate Mini RP formula or any Mini compensation in thresholds, gates, or
progression (GM6) · "up to N Mini extensions" where N > 1, or any UI that offers
a second Mini extension (GM3) · "Classic is a house rule" / "Classic is legacy"
(GM4 — both modes are first-class and selectable on every match) · a
round-count or Quick-Round boundary expressed as a bare literal `18` / `14`
rather than derived from the match's `gameType` (§2.4).

**Not corrected, reported as housekeeping:** old MigrationPlan / master-plan
references and the superseded architecture history in `docs/architecture/*` are
**left as-is** — their historical intent is preserved unless the current roadmap
explicitly requires correction, per owner instruction. **`firestore.rules` is
untouched.** The non-canonical `design-ui` mocks are flagged, not rewritten.

**2026-10-06 GM amendment — one narrow exception to the above:** the three
documents whose bodies carry live, currently-misleading round boundaries
(`docs/architecture/MatchLifecycle.md`, `docs/specs/03-transactions.md`,
`docs/architecture/FirestoreSchema.md`) each received a **pointer note, not a
rewrite** — the body stays Full-only (it documents the JS reference
implementation, which is Full-only) and the note routes the reader to
`CANONICAL_RULES.md` Amendment A2 for the type-derived boundary. This is the
minimum that keeps a future implementer from reading a literal `14`/`18` as a
constraint on Mini, without erasing the history those documents carry.

---

## 4b.18 Final status block

```
RANKED SYSTEM PLANNING      — UPDATED 2026-10-05 (RD1–RD28; RD26 CLOSED, OPEN-1/OPEN-2 remain)
GAME TYPE / CALCULATION     — ADDED 2026-10-06 (GM1–GM8, E7 S65–S69; OPEN-3 CLOSED)
UI/UX ROADMAP               — UPDATED (§4b + contradiction register)
CODE/ARCHITECTURE ROADMAP   — UPDATED (E6b S42–S64, E7 S65–S69, R24–R29, M9–M11)
IMPLEMENTATION              — NOT STARTED
FIGMA                       — NOT STARTED
BATCH 2                     — NOT STARTED
```

**Where the two documents agree, and where they differ on purpose:** both carry
the same 19-rank ladder, the same RD1–RD28 decision set, the same `mode`-first
critical path, and the same four-tier authority model. The UI/UX roadmap owns the
*design* view (screens S45–S58, the two new components, the token gaps, the
design gates); the code roadmap owns the *execution* view (stories S42–S64,
+388 h, risks R24–R29, milestones M9–M11). **RD26's 19 thresholds are CLOSED in
both, identically (2026-10-05, Bronze III 0 → King 3,000); OPEN-1 and OPEN-2
remain open in both, identically.** Neither document reopens a closed owner
decision. **The 2026-10-06 GM amendment is carried in both identically too** —
GM1–GM8 in the code roadmap's decision block, the same eight in this document's
S36/§2.4/§4b, and the shared rule statement in `docs/rules/CANONICAL_RULES.md`
**Amendment A2**. **OPEN-3 — the Mini RP rounding rule — is CLOSED in both
identically (2026-10-06, before S47 was written): nearest integer, ties away
from zero (+15 → +8, −15 → −8), via `kotlin.math.round`. With it closed, no open
item gates any Ranked story; OPEN-1 and OPEN-2 remain open and gate nothing.**

---

**Phase 4b status: SPECIFICATION COMPLETE — READY FOR FIGMA EXECUTION.**
No code has been written. Batch 1 remains frozen. Phases 5–9 remain unstarted.

