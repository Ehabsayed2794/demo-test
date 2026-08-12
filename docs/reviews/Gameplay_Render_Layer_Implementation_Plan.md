# Gameplay Render Layer — Architecture & Implementation Plan

**Type:** Planning/architecture document only. No source file was modified. This is the only file created by this task. Every claim below was re-verified directly against the CURRENT repository source during this task (not carried over from prior reports) — file line numbers and exact code are cited so another engineer can jump straight to the evidence.

**Labeling:** FACT (read directly in source, this task), DOCUMENTED (asserted in a `docs/` file), INFERENCE (reasoned conclusion, not directly stated), OPEN DECISION (repo evidence cannot resolve it alone).

---

## 1. Executive Decision

The engine layer (`Cards → Deck → Dealer → GameSession → BiddingEngine/TableEngine → ScoringEngine`) is real, fully tested (1042/1042 Node tests), browser-compatible (IIFE fix verified in real Chromium), and exposes a legality contract a renderer can consume without duplicating rules: `BiddingEngine.canSubmit()` (Sprint 3.6.1, verified equivalent to `emit()` in Sprint 3.6.2) and `TableEngine.canPlayCard()`/`previewPlay()` (Sprints 4.2.1/4.2.2). **Zero render/UI code exists for gameplay anywhere in this repository** — no card assets, no bidding controls, no table layout, confirmed by a fresh exhaustive asset search this task (§8).

**One new, load-bearing finding this task surfaced that prior audits did not call out explicitly enough to act on:** `MatchService.submitBid(matchId, seatId, bid)` (match-service.js:663) writes a **bare integer per seat** — the same shape as a final trick estimate, and ONLY that shape (confirmed by reading the function body directly). `MatchAdapter.applyRemoteBid()` (match-adapter.js:568) translates this into exactly one `BiddingEngine` action: `SubmitFinalEstimate`. **There is currently no Firestore schema, and no `MatchAdapter` translation, for the Dash phase, the Auction phase, or the Confirm-Call phase.** Online multiplayer bidding synchronization today only covers the ESTIMATES sub-phase. This is a hard architectural boundary the render layer's plan must design around, not paper over — see §4 and §6.

**Recommendation:** Build the render layer as a **Hybrid Match Shell** (re-validated in §6, not merely re-asserted), starting with an **offline-only, single-device, all-four-seats-visible** Bidding + Table renderer (no Firestore sync activated yet) as the first implementation sprint, deferring online sync activation to a clearly separated follow-on sprint once the Dash/Auction/Confirm schema gap above is either accepted as a scoped limitation or separately resolved. This sequencing lets the highest-risk, highest-value work (proving the render layer talks to the real engine correctly, with zero rule duplication) ship first, without being blocked on a schema decision that is genuinely a product/architecture call, not a rendering one.

---

## 2. Current Architecture

**FACT**, directly read this task:

```
Lobby (design-ui/lobby/) → GameState.goTo(GAMEPLAY) → design-ui/match/index.html
                                                              │
                                                    loads (in this exact order,
                                                    match/index.html:116-159):
                                                    game-state.js, Firebase compat×3,
                                                    firebase-init.js, player-service.js,
                                                    session-service.js, room-service.js,
                                                    match-service.js,
                                                    engine/cards.js, engine/deck.js,
                                                    engine/dealer.js, engine/session.js,
                                                    engine/bidding-engine.js,
                                                    engine/table-engine.js,
                                                    engine/scoring-engine.js,
                                                    match-adapter.js
                                                              │
                                        MatchService.subscribeToMatch(matchId, cb)
                                        (match/index.html:297) — the ONE live listener
                                                              │
                                        cb calls renderMatch(data) [existing, Firestore-
                                        status-only fields] THEN bootstrapEngineOnce(data)
                                        (match/index.html:307-308)
                                                              │
                                        bootstrapEngineOnce() calls, once:
                                        MatchAdapter.bootstrapGameSession(data)  → GameSession.setRound/setDealer/setTurn
                                        GameSession.ensureHandsDealt()          → diagnostic only
                                        TableEngine.initState()                 → diagnostic only
                                        BiddingEngine.initState()               → diagnostic only
```

**Nothing past this point renders anything gameplay-related.** `match/index.html`'s own diagnostic div (`#matchEngineDiag`) is the only observable output of the engine having loaded (match/index.html:108, 248-252) — confirmed unchanged from the prior sprint's own report.

**FACT: none of the four `MatchAdapter.start*Sync()` pipelines (`startBidSync`, `startTurnSync`, `startCardSync`, `startTrickSync`) is called anywhere in `match/index.html`.** Grepped and re-confirmed this task. This means: today, even if a render layer existed, it would show only the ONE-TIME bootstrap snapshot — no live bid/turn/card/trick updates from other seats would ever reach the engine. Activating these is REQUIRED for any real multiplayer render layer, and is explicitly scoped as a separate concern in §16.

---

## 3. Engine Contract Map

### A. BiddingEngine public API (`design-ui/engine/bidding-engine.js:876-882`, IIFE-wrapped, `window.BiddingEngine`)

| Member | Signature | Mutates state? | Sync/Async | Exists? |
|---|---|---|---|---|
| `initState()` | `()` | Yes (creates/resumes `state`) | Sync | ✅ |
| `emit(intent)` | `({type, playerId, ...})` | Yes | Sync | ✅ |
| `getState()` | `()` → full working `state` object (by reference) | No | Sync | ✅ |
| `canSubmit(intent)` | `({type, playerId, ...})` → `{legal, reason?}` | **No** (verified Sprint 3.6.2, 88 assertions) | Sync | ✅ |

`emit()` accepts exactly 4 intent `type`s: `SubmitDashCallDecision`, `SubmitAuctionBid`, `SubmitConfirmCall`, `SubmitFinalEstimate` (bidding-engine.js:210-586 switch cases). `canSubmit()` mirrors all 4 (bidding-engine.js:665-734). Neither call touches the DOM or Firestore.

`state` fields the renderer can read via `getState()` (bidding-engine.js:114-188, confirmed field-by-field): `round`, `subPhase` (`DASH|AUCTION|CONFIRM|ESTIMATES|DONE`), `hands`, `waitingFor`, `firstBidder`, `bids` (sparse, `{id:{type,amount}}`), `auctionTop`, `auctionSuit`, `auctionBidder`, `activeBidders[]`, `withPlayers[]`, `callerId`, `declaredTrump`, `lastBidderId`, `fastRound`, `noSuitConstraint`, `logs[]` (`{kind,text,intentTag}` — a ready-made activity feed, zero translation needed), `busy` (AI-thinking flag).

### B. TableEngine public API (`design-ui/engine/table-engine.js:393-400`, `window.TableEngine`)

| Member | Signature | Mutates state? | Sync/Async | Exists? |
|---|---|---|---|---|
| `initState()` | `()` | Yes | Sync | ✅ |
| `emit(intent)` | `({type:"PlayCard", playerId, card})` | Yes | Sync | ✅ |
| `resolveTrick()` | `()` | Yes | Sync | ✅ |
| `getState()` | `()` → full working `state` | No | Sync | ✅ |
| `canPlayCard(playerId, card)` | → `{legal, reason?}` | **No** (Sprint 4.2.1) | Sync | ✅ |
| `previewPlay(playerId, card)` | → `{legal, reason?, nextTurnSeat?, nextPhase?, ...}` (Sprint 4.2.2; exact shape used live by `match-service.js:911`) | **No** | Sync | ✅ |

`state` fields (table-engine.js:81-129, 145-198, 201-252): `phase` (`PLAY|RESOLVING|DONE`), `round`, `trickNumber`/`trickNo`, `leaderId`, `turn`, `ledSuit`, `currentPlays`/`plays` (in-progress trick, `{playerId,card}[]`), `hands` (mutates as cards are played), `tricksWon` (`{p1..p4:n}`), `voids` (`{p1..p4:[suit]}`), `lastTrick` (`{winnerId,...}` after `resolveTrick()`). **No score field lives here** — scoring is `ScoringEngine`'s job, invoked from inside `resolveTrick()` only at trick 13.

### C. GameSession public API relevant to UI (`design-ui/engine/session.js:545-569`)

Renderer-relevant getters, all synchronous, none requiring translation: `getPlayers()` (rich mock roster: `id,name,initial,isUser,isAI,rank,rp,wins,streak,level,coins,gems`), `getPlayer(id)`, `getDealer()`, `getRound()` (`number,maxRounds,multiplier,trump,callerId,withPlayers,estimates,dashCallers`), `getHand(id)`/`getHands()`, `getBiddingState()`, `getPlayState()`, `getMatchScores()`, `getTeamScores()` (always `null`-shaped — no partnerships), `getWinner()`, `getLastRoundResult()`. Pub/sub hook: `onRemoteMatchUpdate(callback)` (session.js:535-543) — fires immediately with current value then on every change; **this is the one existing hook a renderer could subscribe to today with zero new engine code**, and nothing currently calls it from `match/index.html`.

**Confirmed FACT (re-verified this task, unchanged since the prior audit): three independent turn fields exist** — `GameSession.getTurn()` (Firestore-mirror, set by `MatchAdapter.applyRemoteTurn()`/`applyRemoteTrick()`), `getBiddingState().turnId` (owned by `BiddingEngine`'s own reducer), `getPlayState().turnId` (owned by `TableEngine`'s own reducer). A renderer must read the RIGHT one per phase — `BiddingEngine.getState().waitingFor` during bidding, `TableEngine.getState().turn` during play — never `GameSession.getTurn()` for local single-device rendering (that field is Firestore-facing only).

### D. Cards public API relevant to rendering (`design-ui/engine/cards.js:47-53`)

`Cards.SUITS` (`{SANS,SPADES,HEARTS,DIAMONDS,CLUBS}`, each `{id,sym,strength,red,sans,name}` — `sym` is a literal unicode glyph, e.g. `"♠"`, `"SN"` for Sans; `red` is a boolean the renderer can use directly for card-color CSS), `Cards.DECK_SUITS` (the 4 playable suits, excluding SANS), `Cards.RANKS` (`{v,s}[]`, `s` is display text: `"A","K","Q","J","10".."2"`), `Cards.compareForSort` (display sort comparator, already used by `Dealer.sortHand()`). A card object (from `Cards.createCard()`, `cards.js:28-40`) has the exact shape: `{id, suit, rank:{v,s}, displayName, value, owner, played}` — `displayName` is already a ready-to-render string (e.g. `"K ♠"`).

### E. MatchAdapter public API (`design-ui/match-adapter.js:1374-1398`)

| Member | Purpose | Side effects |
|---|---|---|
| `bootstrapGameSession(matchDoc)` | One-shot Firestore→GameSession seed | `GameSession.setRound/setDealer/setTurn` |
| `applyRemoteBid`/`startBidSync` | ESTIMATES-only bid sync | `BiddingEngine.emit()` |
| `applyRemoteTurn`/`startTurnSync` | Mirrors `matches/{id}.turn` → `GameSession.setTurn()` | `GameSession.setTurn()` only |
| `isLocalSeatsTurn`/`assertLocalTurn` | Turn-authority gate for any future write | none (pure) |
| `applyRemoteCard`/`startCardSync` | Card-play sync | `TableEngine.emit()` |
| `applyRemoteTrick`/`startTrickSync` | Trick-resolution sync | `TableEngine.resolveTrick()`, `GameSession.setTurn()` |
| `uidToSeat`/`seatToUid`/`seatToPlayer`/`playerToSeat` | Identity translation | none (pure) |
| `matchDocToEngineSnapshot`/`engineSnapshotToMatchPatch` | Firestore↔engine shape translation | none (pure) |

**This is the ONLY file permitted to know both worlds (match-adapter.js:1-16, its own header, re-read this task).** A renderer must never call `db()`/Firestore directly and must never re-derive a `matchDoc`↔engine-seat translation itself — it goes through `MatchAdapter` or through `GameSession`'s already-translated getters.

### F. MatchService subscription/update flow (`design-ui/match-service.js`)

- `subscribeToMatch(matchId, callback)` (line 998) — the sole live listener, ref-counted per matchId (confirmed by its own doc comment, unchanged).
- `submitBid(matchId, seatId, bid)` (line 663) — **bare integer only**, transactional, seat-ownership-checked, rejects if `biddingOpen !== true` or already bid. This is the schema gap noted in §1.
- `submitCard(matchId, card)` (line 852) — the reference pattern for "pre-write engine validation": resolves the seat via `MatchAdapter.uidToSeat()`, asserts turn via `MatchAdapter.assertLocalTurn()`, then calls `TableEngine.previewPlay(seatId, card)` and **rejects before any Firestore write** if `!preview.legal` (match-service.js:911-914). **This exact pattern is what the render layer's own local interaction flow should mirror** (§4/§5).
- `submitDashCall`/`submitPass`/`declareTrump`/`submitEstimate`/`playCard`/`resolveTrick`/`completeRound`/`advanceToNextRound`/`endMatch` — **all still `notImplemented()` stubs** (confirmed, lines 567, 713-715, 980-984). No Firestore write path exists for Dash, Auction-pass, trump declaration as a distinct action, round completion, or match end.

### G. Current Match page lifecycle

Documented in full in §2. One page, one shell, one subscription, one bootstrap — no phase transitions, no gameplay rendering.

### H. All engine-to-renderer hooks currently referenced (i.e., undefined functions the engines call)

Unchanged from the prior audit, re-confirmed this task by direct grep against the current file state:

| Hook | File | Called from | When |
|---|---|---|---|
| `render()` | bidding-engine.js:822 (`advance()`) | Every state change | After every accepted intent |
| `buildHand()` | bidding-engine.js (`DOMContentLoaded`) | Once | Page load |
| `bindStatic()` | bidding-engine.js + table-engine.js (`DOMContentLoaded`, each) | Once each | Page load |
| `showDone()` | bidding-engine.js:822 (`advance()`) | Once | `subPhase==="DONE"` |
| `render()` | table-engine.js (`advance()`) | Every state change | — |
| `showEscalationBanner()` | table-engine.js (`advance()`) | Conditionally | Escalation event |
| `showRoundDone()` | table-engine.js (`advance()`) | Once | `phase==="DONE"` |
| `sweepThenResolve()` | table-engine.js (`advance()`) | Once per trick | 4th card played |
| `flashReject()` | table-engine.js (`playFromHand()`) | On illegal play | — |
| `document.getElementById("doneOverlay")` | bidding-engine.js `restart()` | On restart | — |
| `document.querySelector(".round-done")` | table-engine.js `restart()` | On restart | — |

None of these exist anywhere in the repository (re-confirmed this task). **The render layer's job is NOT to define these specific function names** — it is free to build its own render/bind architecture (§9) — but it MUST either (a) never trigger the `DOMContentLoaded`→`advance()` path at all (as `match/index.html` currently does, calling `initState()`/`getState()` directly instead), or (b) define these exact names before that listener fires. Recommendation: **(a)**, consistent with the existing, working `match/index.html` pattern — see §9.

### I. All data the renderer needs to display the game

Fully enumerated in §4 (Bidding) and §5 (Table) below, cross-referenced against §3's exact API map — no invented fields.

---

## 4. Bidding Render Contract

### Required display data (every item traced to an exact existing getter — none invented)

| Displayed thing | Source |
|---|---|
| Players/seats/dealer | `GameSession.getPlayers()`, `GameSession.getDealer()` |
| Current player (whose turn) | `BiddingEngine.getState().waitingFor` |
| Phase/subPhase | `BiddingEngine.getState().subPhase` |
| Dash state | `state.bids[id].type === "DASHCALL"`, count via `Object.values(state.bids).filter(...)` (renderer may COUNT existing data, this is not a rule — see the "no duplication" boundary below) |
| Auction state | `state.auctionTop`, `state.auctionSuit`, `state.auctionBidder`, `state.activeBidders` |
| Winning bid/bidder/suit | same fields, post-auction: `state.callerId`, `state.declaredTrump` |
| Confirm-call state | `state.subPhase==="CONFIRM"`, `state.auctionTop`/`auctionSuit` as the pre-filled default |
| Final estimate state | `state.bids` (per-seat), `state.auctionTop` (cap) |
| Logs/status | `state.logs[]` — direct render, no reformatting needed |
| Legal actions / disabled actions | **`BiddingEngine.canSubmit(intent)`** — see below, never re-derived |
| Rejection feedback | The `{rejected, reason}` returned by `emit()`, OR the `{legal:false, reason}` from `canSubmit()` pre-check — same reason strings either way (Sprint 3.6.1 guarantee) |
| Waiting state (AI thinking) | `state.busy` |

### Verified/corrected interaction flow

The brief's proposed flow is **almost right** but omits one required step this repository's OWN existing pattern (`match-service.js:911-914`, `submitCard()`) already establishes as mandatory for any local write: **the renderer never calls `emit()` for a REMOTE seat's action** — only for the LOCAL human seat's own action. AI/other-seat actions are already driven by the engine's own `aiAct()` (offline) or by `MatchAdapter.applyRemoteBid()` (online, ESTIMATES-only). Corrected flow, for the LOCAL player's own action:

```
User clicks a bid control
    ↓
Renderer builds the intent object (type, playerId=localSeat, ...)
    ↓
BiddingEngine.canSubmit(intent)          ← READ-ONLY pre-check, never mutates
    ↓
if legal.false → show rejection reason inline, STOP (no emit(), no write)
    ↓
if legal.true  → BiddingEngine.emit(intent)   ← the ONLY place gameplay state changes
    ↓
re-render from BiddingEngine.getState()       ← immediate, LOCAL, synchronous
    ↓
[ONLINE ONLY, separate concern — see §6/§16]
MatchService.submitBid(matchId, localSeat, bid)   ← ONLY valid during ESTIMATES
    (Dash/Auction/Confirm have NO Firestore write path yet — §1/§3F)
    ↓
Firestore round-trip → MatchAdapter.applyRemoteBid() on EVERY client (including this one)
    ↓
re-render again from the post-sync BiddingEngine.getState()
```

**Why `canSubmit()` before `emit()`, not `emit()` alone:** `emit()` already returns `{rejected,reason}` for 3 of its 4 intents, but pre-checking with `canSubmit()` lets the renderer disable/grey out an illegal control BEFORE the user even attempts it (e.g., grey out "Confirm 3 Clubs" because it's a lower call) — `emit()` alone can only react after a click, `canSubmit()` lets the UI prevent the click from being possible in the first place. This is the exact reason `canSubmit()` was built (Sprint 3.6.1's own stated purpose).

### Explicit rules the renderer must NEVER reproduce (confirmed exact source location for each, so a future code reviewer can grep for a violation)

| Rule | Lives in | Renderer must call |
|---|---|---|
| With-floor | `withFloorFor()`, bidding-engine.js:747 | `canSubmit({type:"SubmitFinalEstimate",...})` |
| Forbidden-13 | `forbiddenEstimateFor()`/`estimateIsForbidden13()`, bidding-engine.js:94/100 | same |
| Dash limits | `MAX_DASH_CALLS`, bidding-engine.js:26 | `canSubmit({type:"SubmitDashCallDecision",...})` |
| Suit strength | `SUITS[x].strength`, bidding-engine.js:11-17 | `canSubmit()` (any auction/confirm intent) |
| Auction comparison (beats-top/With) | `auctionBidBeatsTop()`/`auctionBidIsWith()`, bidding-engine.js:69/73 | `canSubmit({type:"SubmitAuctionBid",...})` |
| Caller cap | `estimateExceedsCap()`, bidding-engine.js:82 | `canSubmit({type:"SubmitFinalEstimate",...})` |

A renderer MAY do pure display-only counting/filtering of ALREADY-PUBLIC `state` fields (e.g., "how many seats have bid so far" = `Object.keys(state.bids).length`) — this is not a game rule, it's a display aggregation of data the engine already computed and exposed. The dividing line: if the computation could ever produce a DIFFERENT accept/reject decision than the engine's own, it belongs in the engine; if it only summarizes already-decided facts for display, it's fine in the renderer.

---

## 5. Table Render Contract

### Required display data

| Displayed thing | Source |
|---|---|
| 4 players, positions | `GameSession.getPlayers()` (each has `.initial` for a minimal avatar; no `.pos`/seat-layout field exists on `GameSession`'s player objects — `table-engine.js`'s OWN internal `SEAT_POS` (table-engine.js:29) is a private constant, not exported; the renderer needs its OWN seat-layout mapping — see §9's `PlayerRenderer` responsibility) |
| Hands | `GameSession.getHand(id)` / `TableEngine.getState().hands[id]` (mutates as cards are played — prefer reading from `TableEngine.getState()` during PLAY phase, since that's the actively-mutating copy) |
| Card backs | Renderer-only concept — the engine has no "hidden card" representation; the renderer decides whose hand to show face-down (every non-local seat) — this is UI policy, not a rule |
| Played cards / current trick | `TableEngine.getState().currentPlays` |
| Trump | `GameSession.getRound().trump` |
| Current turn | `TableEngine.getState().turn` |
| Trick winner | `TableEngine.getState().lastTrick.winnerId` (after `resolveTrick()`) |
| Score | `GameSession.getMatchScores()` (running total); `GameSession.getLastRoundResult()` for the per-round breakdown |
| Round/phase | `TableEngine.getState().round`, `.phase` |
| Rejected card | The `{legal:false,reason}` from `canPlayCard()`/`previewPlay()` |
| Waiting state | Absence of a `busy`-equivalent field in `TableEngine`'s state (**confirmed FACT: `table-engine.js` has no `busy` flag, unlike `bidding-engine.js`** — this is a real, minor asymmetry the render layer must handle itself, e.g. a fixed short delay before an AI's card appears, since the engine gives no "AI is thinking" signal for table play) |
| Completed trick | `TableEngine.getState().lastTrick` |
| Match completion | `GameSession.getWinner()` (setter exists, `GameSession.setWinner()`, but **nothing in the engine currently calls it** — confirmed by grep: `setWinner` has zero call sites outside its own definition and `tests/`. Match-completion detection/declaration is NOT implemented anywhere yet — flagged as a real gap, not invented) |

### Verified/corrected interaction flow

The brief's proposed flow is directionally correct; corrected to match the exact existing `submitCard()` pattern (match-service.js:898-914) as the reference implementation:

```
Card click (LOCAL seat only)
    ↓
TableEngine.previewPlay(localSeat, card)      ← READ-ONLY, never mutates
    ↓
if !legal → flashReject-equivalent inline, STOP (mirrors match-service.js's own
             "reject before transaction" pattern — never write blind)
    ↓
if legal  → TableEngine.emit({type:"PlayCard", playerId:localSeat, card})
    ↓
re-render from TableEngine.getState()
    ↓
if state.phase === "RESOLVING" → TableEngine.resolveTrick()   ← existing, unmodified
    ↓
re-render again (trick winner, tricksWon, next leader)
    ↓
[ONLINE ONLY — separate concern, §16]
MatchService.submitCard(matchId, card) → Firestore → MatchAdapter.applyRemoteCard()/
applyRemoteTrick() on every client → re-render
```

**One important correction to the brief's own flow diagram:** `resolveTrick()` is a SEPARATE call from `emit()` — `emit({type:"PlayCard",...})` only plays the card and (per table-engine.js:162-198) sets `phase:"RESOLVING"` once the 4th card lands; it does NOT itself resolve the trick. The renderer (offline) must call `resolveTrick()` itself after detecting `phase==="RESOLVING"` — exactly what the existing (currently-broken) `advance()`→`sweepThenResolve()`→`resolveTrick()` chain was always meant to do (table-engine.js:279-304). Online, `MatchAdapter.applyRemoteTrick()` already does this — a renderer must NOT call `resolveTrick()` itself in the online path (it would race/duplicate the adapter's own call); it should only call it in a genuinely OFFLINE, single-device render mode.

### Explicit rules the renderer must NEVER reproduce

| Rule | Lives in | Renderer must call |
|---|---|---|
| Follow-suit / card legality | `legalCards()`/`isLegal()`, table-engine.js:134-142 | `canPlayCard(playerId, card)` |
| Trick winner computation | `trickWinner()`/`cardValue()`, table-engine.js:145-157 | read `getState().lastTrick.winnerId` AFTER `resolveTrick()` |
| Turn order | `nextCCW()`, table-engine.js:32 | read `getState().turn` |
| Scoring | `ScoringEngine.calculateRoundScore()` | never call from the renderer directly for a LIVE round — only `TableEngine`'s own internal call (inside `resolveTrick()` at trick 13) should trigger it; the renderer only ever READS `GameSession.getMatchScores()`/`getLastRoundResult()` afterward |
| Trump logic | Established once, at bidding completion (`state.declaredTrump`) | read `GameSession.getRound().trump` |

---

## 6. Match Shell Architecture

### Re-validating the Hybrid Match Shell recommendation against current source (not re-asserting it)

**FACT, re-confirmed this task:** `match/index.html`'s own inline script (lines 160-316) ALREADY implements exactly this shape today — one `<script>` shell, one `MatchService.subscribeToMatch()` call, one `bootstrapEngineOnce()` bootstrap, redraw-in-place via `renderMatch(data)`. Extending it with phase-aware internal views (Bidding view / Table view / Results view swapped in place, same page, same subscription) is additive to a pattern already proven to work, not a new one.

**Cross-checked against every constraint the brief requires be preserved:**

| Constraint | How the Hybrid Shell satisfies it |
|---|---|
| MatchService stays Firestore-only | Unaffected — shell calls `MatchService.subscribeToMatch()` exactly as today; no engine reference added to `match-service.js` |
| MatchAdapter remains the seam | Unaffected — shell calls `MatchAdapter.bootstrapGameSession()` once (as today) and, when online sync is activated (§16), the four `start*Sync()` functions — never a new adapter path |
| Engine rules stay authoritative | Enforced structurally by §4/§5's "must call canSubmit/canPlayCard/previewPlay" contract — independent of screen architecture |
| GameSession stays authoritative for local state | Unaffected — a single shell means `GameSession`'s one `sessionStorage`-backed session object is read/written exactly once per page lifetime, matching its own design (session.js's `load()`/`persist()` pattern assumes one continuous page session, not per-screen reloads) |
| No duplicated rules in UI | Enforced by §4/§5 |
| No new parallel engine | Enforced by "renderer never reimplements a formula" rule (§4/§5) |
| No hidden state machine in the renderer | Enforced by §7 — the renderer's ONLY state machine is a thin VIEW-SELECTION derivation (`subPhase`/`phase` → which view to show), never a duplicate of `BiddingEngine`/`TableEngine`'s own phase transitions |

**Why NOT separate Bidding/Table pages (Option A from the prior audit), re-verified against a NEW piece of evidence this task found:** both `BiddingEngine` and `TableEngine` register a `DOMContentLoaded` listener that calls `initState()` (bidding-engine.js:938-944 / table-engine.js equivalent) — a full page navigation between a Bidding screen and a Table screen would mean EACH engine script reloads fresh on its own page, re-triggering `initState()`'s own resume-vs-fresh logic on every navigation. `initState()` IS resume-safe (checks `GameSession.isBiddingStateValidForCurrentRound()`/`isPlayStateValidForCurrentRound()` first), so this would not corrupt data, but it adds an unnecessary full-page-reload's worth of latency and a visible flash between every phase transition, purely to satisfy a screen-count decision the engine's own architecture doesn't require. The Hybrid Shell avoids this entirely: `initState()` for each engine is called exactly once per shell load, matching how `match/index.html` already calls both today (lines 228-246).

**RECOMMENDED MATCH ARCHITECTURE: Hybrid Match Shell — CONFIRMED, not merely carried over.**

---

## 7. UI State Ownership

| Category | Property | Owner | Notes |
|---|---|---|---|
| **REMOTE** | Match status, `players[]` (uid list), `dealer`/`turn` (uid) | `GameSession.getRemoteMatch()` (raw Firestore mirror) | Never rendered directly for gameplay — only used to detect connection/existence; gameplay data comes from the ENGINE state below, seeded/synced via `MatchAdapter` |
| **REMOTE** | Remote seat's bid/card actions (other players, online mode) | `MatchAdapter.applyRemote*()` → engine `emit()` | Renderer never touches this path directly — it only re-renders AFTER an adapter call updates engine state |
| **ENGINE** | `subPhase`/`phase`, `waitingFor`/`turn`, `hands`, `bids`, `auctionTop/Suit/Bidder`, `withPlayers`, `callerId`, `declaredTrump`, `currentPlays`, `tricksWon`, `lastTrick`, `matchScores`, `round` | `BiddingEngine.getState()` / `TableEngine.getState()` / `GameSession.get*()` | THE single source of truth for all gameplay facts. Renderer reads, never independently derives or caches a second copy that could drift |
| **DERIVED** | Which bid/card controls are enabled | `canSubmit()`/`canPlayCard()` result, computed fresh on every render pass | Never cached across a state change — cheap, pure functions, safe to call every render |
| **DERIVED** | Highlighted/active player | `state.waitingFor === player.id` (a pure display comparison of two already-public facts) | Not a new fact — a view computed from two existing facts |
| **DERIVED** | Playable/disabled cards in the local hand | `canPlayCard(localSeat, card)` per card in `getHand(localSeat)` | Recomputed every render |
| **DERIVED** | Which top-level view to show (Bidding/Table/Results) | `subPhase`/`phase` → view mapping (a lookup table, not a state machine — see §6) | The ONLY renderer-owned "logic," and it is a pure function of engine state, not an independent state machine |
| **LOCAL UI** | Modal open/closed, toast visibility/text, selected-but-not-yet-submitted bid value, card hover/drag animation state, screen-fit scale | Renderer's own local variables (mirrors `match/index.html`'s existing `ui = {bidValue,...}` pattern already declared, unused, in `bidding-engine.js:895` — `let ui = {...}` exists in the engine file today but is NEVER read by anything; the render layer should own its OWN local UI state, not resurrect that specific unused variable, since it lives on the wrong side of the engine/render boundary) | Never mixed with engine state — a toast closing must never cause an engine re-init |

**Explicit non-negotiable:** the renderer's view-selection lookup (`subPhase`→view) is a MAPPING, not a state machine — it has no memory, no transitions of its own, and no ability to reach a state the engine itself didn't reach first. This is what "the renderer must not create a second gameplay state machine" means concretely.

---

## 8. Asset Reality Check

Exhaustive filesystem search performed THIS task (not reused from a prior report):

```
find design-ui -iname "*.png" -o -iname "*.jpg" -o -iname "*.svg" -o -iname "*.gif" \
  -o -iname "*.webp" -o -iname "*.mp3" -o -iname "*.wav" -o -iname "*.woff*" -o -iname "*.ttf"
→ design-ui/lobby/uploads/Ranked Match.png
→ design-ui/lobby/uploads/AI.jpg
→ design-ui/lobby/uploads/Play with Friend.png
(3 results total, repo-wide)

find design-ui -iname "*.css"
→ design-ui/login/shared-ui.css
(1 result total, repo-wide)
```

| Asset | Classification |
|---|---|
| Card face images (any suit/rank) | **MISSING** |
| Card back image | **MISSING** |
| Player avatars (photo/illustration) | **MISSING** — only a single-letter `.initial` field exists (`GameSession.getPlayers()`), already used elsewhere in the project as a text-only avatar |
| Table background | **MISSING** |
| Chips/tokens | **MISSING** |
| Bidding controls (any) | **MISSING** — zero markup exists anywhere |
| Icons | **EXISTS BUT DISCONNECTED** — 3 lobby mode-selector images exist (`AI.jpg`, `Play with Friend.png`, `Ranked Match.png`) but are Lobby-specific game-MODE icons, not gameplay icons; not reusable for cards/suits/actions |
| Sound assets | **MISSING** |
| Fonts | **EXISTS AND USABLE** — Marcellus/Saira/Spline Sans Mono, loaded via Google Fonts CDN `<link>` in every screen's `<head>` (confirmed in `match/index.html:7-9`) — no local font files, but the CDN pattern is consistent and already proven across 4 screens |
| Existing CSS design system | **EXISTS AND USABLE (partially)** — the `:root` custom-property token set (`--accent`, `--panel`, `--panel-hi`, `--panel-line`, `--ink`, `--ink-dim`, `--ink-faint`, `--bg`, `--pill`, `--mono`) is defined inline in EVERY screen's own `<style>` block (confirmed identical in `match/index.html:17-23`) — no shared `.css` file exports it; it is currently copy-pasted per screen, not `@import`ed |
| Shared UI components (Toast/Modal/Input/Skeleton) | **EXISTS AND USABLE, DISCONNECTED FROM MATCH** — `design-ui/login/shared-ui.css` + `shared-ui.js` (`window.UI.toast/openModal/closeModal/bindModalDismiss/setFieldError`) is real, generic, and matches the same token system, but is NOT `<script>`/`<link>`-loaded by `match/index.html` today |
| `design-ui/SHARED_COMPONENTS.md`'s references to `room.js`/`shop.js`/`bidding-render.js`/`table-system.css`/"Game Table"/"Final Standings" | **DOCUMENTATION-ONLY** — none of these files exist anywhere in this repository (re-confirmed this task); this doc describes a different, larger planning-universe project state that was never imported here. Do not treat it as evidence of anything existing in THIS repo. |

### Minimum viable representation for the first render-layer implementation

Given zero card art exists, the recommended MVP representation (a rendering/implementation decision, not a rules decision, and therefore safely decidable here rather than deferred as an open product question):

- **Cards:** CSS-only rectangles using the existing token system — suit `sym` (already a literal glyph string on `Cards.SUITS[x].sym`) + rank `s` (already a literal string on `Cards.RANKS[i].s`) rendered as text inside a bordered `div`, colored via the existing `red` boolean (`Cards.SUITS[x].red`) mapped to two CSS custom properties (a "red suit" and "black suit" ink color) — zero new image assets needed for a functional first pass.
- **Card backs (non-local hands):** a solid/patterned `div` using `--panel`/`--accent` tokens, no image.
- **Avatars:** the existing `.initial` single-letter convention (already used elsewhere in the project), styled as a circular badge — consistent with the project's existing minimal-asset visual language, not a regression.
- **Table layout:** CSS `position:absolute` seat slots against the existing fixed `932×430` `#screen` frame (the exact frame every current screen, including `match/index.html`, already uses) — no background image required for function; a gradient/token-based background (matching `match/index.html`'s existing `radial-gradient`/`linear-gradient` `#screen` background) is sufficient.

This is explicitly a FUNCTIONAL MVP recommendation, not a visual-design decision — a later, separate design pass (potentially the "Claude Design" track referenced in this project's own planning history) can replace these with real art without touching any engine-call wiring, since the render layer's DOM structure and the engine calls it makes are independent of what the cards look like.

---

## 9. Navigation Decision

**A vs. B vs. C, evaluated against the 9 named criteria:**

| Criterion | A: Bidding page + Table page | B: One Match page, internal phases | 
|---|---|---|
| GameSession lifecycle | Fine either way (resume-safe `initState()`) | Fine — one continuous session, matching `session.js`'s own single-page-session design intent |
| MatchAdapter lifecycle | `bootstrapGameSession()` would need to re-run or be skipped on the 2nd page — extra logic to avoid double-dealing | One bootstrap, once, ever, per match |
| MatchService subscription lifecycle | A 2nd `subscribeToMatch()` call on the Table page is safe (ref-counted) but means writing subscribe/unsubscribe logic TWICE | One subscription for the whole match |
| Firebase listener duplication risk | Low but non-zero extra surface (must remember to `unsubscribe()` on the Bidding→Table navigation, or rely on ref-counting correctly, per-page) | None — a single unsubscribe path, matching the existing single-page pattern already in use |
| Resume/reload behavior | A raw browser reload on the Table page must re-derive "we're mid-match, not at Bidding" from `GameSession`/Firestore state before even loading the right script set — non-trivial | A single `initState()` call per engine, at shell load, already handles resume via `isBiddingStateValidForCurrentRound()`/`isPlayStateValidForCurrentRound()` — proven pattern, zero new logic needed |
| Browser navigation (back button) | Real risk: browser back from Table to Bidding mid-play is meaningless and must be actively suppressed | Not applicable — no page boundary to navigate across |
| State persistence | Requires passing state via `GameState`'s existing data-handoff mechanism between the two pages (adds a dependency on that mechanism carrying MORE than it does today — currently only `{match:{id}}`, per `match/index.html:285`) | Nothing new — state already lives in `GameSession`'s `sessionStorage`, page-independent |
| Future mobile landscape UI | Extra page-load flicker at a fixed small frame size (932×430) reads worse on mobile than an in-place view swap | Smooth in-place transitions, better for the existing fixed-frame mobile-landscape design language every screen already uses |
| Future multiplayer | No architectural benefit — sync pipelines are per-matchId, not per-page, so splitting pages buys nothing for multiplayer correctness while adding real listener-lifecycle risk | No downside — sync activates once, shell-wide |

**FINAL RECOMMENDATION: B — one Match page (shell) with internal phases**, consistent with and reinforcing §6's Hybrid Match Shell architecture (they are the same decision, viewed from two angles: §6 is the data/responsibility architecture, this section is the literal page-count/navigation decision). Not "it depends" — B wins on every criterion except a hypothetical "smaller individual file size" argument that this codebase's own existing screens (e.g. `bidding-engine.js` at 900+ lines, `table-engine.js` at 400+) already show is not a real constraint acted on elsewhere in this project.

---

## 10. Implementation Breakdown

Every task specifies files to create/modify, explicit forbidden files (the full engine/adapter/service list is forbidden for EVERY task below unless explicitly noted otherwise — restated per-task for clarity, not because it varies), dependencies, acceptance criteria, tests, effort, and risk.

**Global forbidden list, every task (per the brief's own Phase 11, restated here as a standing rule):** `bidding-engine.js`, `table-engine.js`, `session.js`, `cards.js`, `deck.js`, `dealer.js`, `scoring-engine.js`, `match-adapter.js`, `match-service.js`. Any task below needing a change to one of these must STOP and report, not silently expand scope — exactly the same rule enforced in Sprints 3.6.1/3.6.2.

### Task 1 — Match Shell Foundation
- **Create:** none (extends existing `design-ui/match/index.html`)
- **Modify:** `design-ui/match/index.html` — add a phase-derivation function (`subPhase`/`phase` → view name), a view-container swap mechanism (show/hide `<div>`s, or innerHTML swap — implementation detail, not an architecture decision), remove/replace the current diagnostic-only `bootstrapEngineOnce()` reporting with real view initialization once a view exists to show.
- **Forbidden:** the global list above.
- **Dependencies:** none (first task).
- **Acceptance criteria:** page loads, engine bootstraps exactly as today (no regression to existing diagnostic behavior until it's replaced), a view container exists and is empty/placeholder.
- **Tests:** Playwright — confirm no new console errors beyond the already-known `buildHand`/`bindStatic` ones (still present until Task 4/6 supply an alternative, non-`DOMContentLoaded` init path — see §3H's recommendation (a)).
- **Effort:** LOW.
- **Risk:** LOW — additive to a working pattern.

### Task 2 — Shared Match Components
- **Create:** none, OR a new `design-ui/match/match-ui.css`/`.js` if project convention prefers a dedicated file (decide at implementation time; `shared-ui.css`/`.js` is the existing precedent for this exact choice).
- **Modify:** `design-ui/match/index.html` — load `../login/shared-ui.css`/`shared-ui.js` (currently unloaded, per §8), extend the `:root` token block if any new token is genuinely needed (e.g. `--red-suit`/`--black-suit` per §8's card-color recommendation).
- **Forbidden:** global list.
- **Dependencies:** Task 1.
- **Acceptance criteria:** `window.UI.toast()` callable from `match/index.html`; no visual regression to the existing status card.
- **Tests:** Playwright — confirm `window.UI` is defined.
- **Effort:** LOW.
- **Risk:** LOW.

### Task 3 — Player/Seat Renderer
- **Create:** none (inline within `match/index.html`'s script, or a new file per Task 2's file-split decision).
- **Modify:** `design-ui/match/index.html`.
- **Forbidden:** global list.
- **Dependencies:** Task 1.
- **Acceptance criteria:** all 4 seats render with name/initial/dealer indicator, reading ONLY `GameSession.getPlayers()`/`getDealer()` — no invented seat-position data (per §5's finding that no `.pos` field exists; the renderer must define its OWN seat-layout constant, analogous to `table-engine.js`'s private `SEAT_POS`, since that one isn't exported).
- **Tests:** Node — none needed (pure DOM rendering of already-tested data); Playwright — 4 seats visible.
- **Effort:** LOW.
- **Risk:** LOW.

### Task 4 — Bidding Renderer
- **Create:** none, OR `design-ui/match/bidding-renderer.js` (file-split decision, per Task 2).
- **Modify:** `design-ui/match/index.html`.
- **Forbidden:** global list.
- **Dependencies:** Tasks 1-3.
- **Acceptance criteria:** every §4 "required display data" item renders correctly from `BiddingEngine.getState()`; phase transitions (DASH→AUCTION→CONFIRM→ESTIMATES→DONE) reflect live.
- **Tests:** Playwright, driving `BiddingEngine.emit()` directly (bypassing controls) and confirming the DOM updates to match — proves the render function is a correct, pure projection of `getState()`.
- **Effort:** MEDIUM.
- **Risk:** MEDIUM — this is the first task touching real gameplay-state rendering; a mistake here (e.g. reading a stale `getState()` reference) is the most likely source of the "renderer shows wrong data" class of bug.

### Task 5 — Bidding Controls
- **Create:** none, OR extends Task 4's file.
- **Modify:** `design-ui/match/index.html` (or the Task 4 file).
- **Forbidden:** global list — **especially critical here**: this task is the highest-risk point for someone to "just add a quick check" that duplicates a rule (§4's table). Code review for this task specifically should grep the diff for any inline `if` comparing `tricks`/`suit`/`auctionTop`/etc. that ISN'T immediately followed by/replaced with a `canSubmit()` call.
- **Dependencies:** Task 4.
- **Acceptance criteria:** every control's enabled/disabled state comes from `canSubmit()`; every submit action calls `canSubmit()` then `emit()`, in that order, never `emit()` alone; rejection reasons display verbatim from the engine's own reason strings (no re-wording that could drift from the engine's actual behavior).
- **Tests:** the exact flow tests/bidding-contract.test.cjs already established as the equivalence baseline (Sprint 3.6.2) — a Playwright test clicking a control that SHOULD be disabled and confirming no `emit()` side effect occurred (state unchanged), plus one clicking a legal control and confirming state DID change.
- **Effort:** MEDIUM.
- **Risk:** MEDIUM — same class of risk as Task 4, concentrated in the click-handler wiring specifically.

### Task 6 — Table Renderer
- **Create:** none, OR `design-ui/match/table-renderer.js`.
- **Modify:** `design-ui/match/index.html`.
- **Forbidden:** global list.
- **Dependencies:** Tasks 1-3 (does NOT depend on 4/5 — Bidding and Table renderers are independent consumers of different engine files and can be built in either order or in parallel by different engineers).
- **Acceptance criteria:** every §5 "required display data" item renders correctly from `TableEngine.getState()`.
- **Tests:** Playwright, driving `TableEngine.emit()` directly and confirming DOM updates.
- **Effort:** MEDIUM.
- **Risk:** MEDIUM.

### Task 7 — Card Renderer
- **Create:** none, OR extends Task 6's file.
- **Modify:** `design-ui/match/index.html` (or Task 6 file), plus the CSS token additions from Task 2 (red/black suit colors).
- **Forbidden:** global list.
- **Dependencies:** Task 6, §8's MVP asset recommendation.
- **Acceptance criteria:** local player's hand renders face-up with real suit/rank; other 3 seats render face-down (card-back placeholder) with correct COUNT (`hand.length`) but no revealed content — this is a genuine security-relevant detail even for a local single-device build (establishes the pattern correctly ahead of any future "don't leak other players' hands over Firestore" concern, which is out of THIS plan's scope but the render convention should not need rework later).
- **Tests:** Playwright — local hand shows N real cards; other 3 seats show `hand.length` backs each, zero real card data present in their DOM nodes (a real assertion, not just a screenshot).
- **Effort:** MEDIUM.
- **Risk:** LOW (isolated, well-scoped visual task) — but see the face-down/face-up distinction above as a correctness requirement, not a style choice.

### Task 8 — Trick Renderer
- **Create:** none, OR extends Task 6/7's file.
- **Modify:** `design-ui/match/index.html`.
- **Forbidden:** global list.
- **Dependencies:** Tasks 6-7.
- **Acceptance criteria:** `currentPlays` renders positioned per-seat in the center of the table; clears after `resolveTrick()`; winner is briefly highlighted using `lastTrick.winnerId`.
- **Tests:** Playwright — play 4 cards via `emit()`, confirm all 4 render, confirm `resolveTrick()` clears them.
- **Effort:** LOW.
- **Risk:** LOW.

### Task 9 — Score Renderer
- **Create:** none, OR a small dedicated view.
- **Modify:** `design-ui/match/index.html`.
- **Forbidden:** global list.
- **Dependencies:** Task 1.
- **Acceptance criteria:** `GameSession.getMatchScores()` renders per-seat running totals; `getLastRoundResult()`'s breakdown renders after a round completes (reusing the engine's own already-computed `breakdown[id].notes` strings — human-readable, zero re-derivation needed, per `scoring-engine.js:161-205`).
- **Tests:** Playwright — after a full round via direct `emit()` calls, confirm displayed totals match `getMatchScores()` exactly.
- **Effort:** LOW.
- **Risk:** LOW.

### Task 10 — Interaction Wiring
- **Create:** none.
- **Modify:** `design-ui/match/index.html` — ties Tasks 3-9 together into one coherent phase-driven view (the "shell" logic itself: which view is visible when).
- **Forbidden:** global list.
- **Dependencies:** Tasks 1-9.
- **Acceptance criteria:** a full offline round (Dash→Auction→Confirm→Estimates→13 tricks→score→next round OR match end) is playable end-to-end through the UI alone, with zero direct console/`emit()` calls needed.
- **Tests:** Playwright — a full scripted click-through of one entire round.
- **Effort:** MEDIUM.
- **Risk:** MEDIUM — integration risk (each piece works alone; this task proves they compose).

### Task 11 — Match Completion
- **Create:** none.
- **Modify:** `design-ui/match/index.html`.
- **Forbidden:** global list.
- **Dependencies:** Task 10.
- **Acceptance criteria:** **honestly scoped to what the engine currently supports** — since `GameSession.setWinner()` has zero callers anywhere (confirmed §5), this task can render "final standings after N rounds of local play" but CANNOT yet render a real "match complete, winner declared" state without either (a) the renderer itself deciding when the match ends and calling `setWinner()` — a genuine game-rule decision ("what round count or score threshold ends a match?") this plan explicitly does NOT authorize inventing, or (b) treating match-completion detection as its own small, separately-scoped follow-on decision. **Recommendation: scope Task 11 to "round-complete → next-round transition" only for this plan's implementation sequence, and flag full match completion as an explicit OPEN DECISION for a future sprint** (see §15).
- **Tests:** Playwright — after round 1 completes, `GameSession.nextRound()` correctly resets and the UI reflects round 2.
- **Effort:** LOW (with the above scope correction) — would be MEDIUM-HIGH if match-end rules were included, which this plan defers.
- **Risk:** LOW (scoped), otherwise MEDIUM (if scope creeps into inventing end-of-match rules).

### Task 12 — Browser Integration
- **Create:** a persisted Playwright verification script (following this project's own established pattern — local static server, stubbed Firebase compat scripts, seeded `sessionStorage`, explicit Chromium `executablePath`) — recommend formalizing this as a real, checked-in script rather than the scratchpad-only throwaway scripts used in prior sprints, given how many times this exact harness has now been rebuilt.
- **Modify:** none beyond what Tasks 1-11 already changed.
- **Forbidden:** global list.
- **Dependencies:** Tasks 1-11.
- **Acceptance criteria:** all of §14's Definition of Done browser-side items pass in real Chromium.
- **Tests:** itself.
- **Effort:** MEDIUM.
- **Risk:** LOW — verification only, no new logic.

### Task 13 — Playwright Regression
- **Create:** none (extends Task 12's script/suite).
- **Modify:** none.
- **Forbidden:** global list.
- **Dependencies:** Task 12.
- **Acceptance criteria:** re-run after EVERY subsequent change to `match/index.html` for the remainder of this feature's development, confirming no regression to the already-proven engine-bootstrap diagnostic behavior or any earlier task's acceptance criteria.
- **Tests:** itself, repeatedly.
- **Effort:** LOW (per run) — this is a recurring verification gate, not a one-time task.
- **Risk:** LOW.

---

## 11. Effort Estimation

Using exactly the 5 specified levels:

1. **This architecture/planning sprint:** **MAX** (as instructed — matches the actual depth required: 14 files re-read in full or targeted-verified, exact line-numbered evidence for every claim, 13 atomic tasks each independently scoped).
2. **Bidding Render (Tasks 3-5):** **HIGH** — three MEDIUM-effort tasks with real gameplay-correctness risk (Task 5 especially, per its own risk note), not a simple form UI; the Dash/Auction/Confirm/Estimates 4-phase state machine has meaningfully more surface area than the Table side.
3. **Table Render (Tasks 6-8):** **HIGH** — three tasks, card rendering + trick animation + turn/legality wiring is comparable total surface area to Bidding, even though individual tasks are MEDIUM/LOW.
4. **Full Match integration (Tasks 1-2, 9-11):** **MEDIUM** — mostly LOW/MEDIUM individual tasks, but real integration risk in Task 10 specifically.
5. **Browser QA (Tasks 12-13):** **MEDIUM** — real Playwright work across every phase, not a rubber-stamp; this project's own history (this session alone) shows real, non-trivial bugs are consistently found only at this stage (the `SUITS` redeclaration collision, the missing render-layer hooks), so under-resourcing this stage has a proven cost.
6. **Total recommended effort for the full render layer (Tasks 1-13, this plan's entire scope, excluding online-sync activation which is explicitly deferred — §16):** **MAX.** This is a genuine multi-file, multi-session gameplay UI build with real correctness stakes (money-equivalent game scoring, real rule enforcement) — calling it anything less than MAX would misrepresent the actual scope to whoever authorizes the next sprint. It is NOT "Ultra Code" scale (no evidence this needs multi-agent orchestration/fan-out — it's sequential, dependency-chained work best done by one engineer maintaining full context across the 13 tasks, per §16's sequencing).

---

## 12. Risks

**CRITICAL**
- None identified that block starting implementation — the engine layer is proven sound (1042/1042 tests, real-browser-verified).

**HIGH**
- **Rule-duplication drift risk, concentrated in Task 5 (Bidding Controls).** The single highest-probability way this plan fails is a future engineer adding "just one more check" inline in a click handler instead of routing through `canSubmit()`. Mitigation: the §4 table (exact rule → exact source location → exact required call) should be pasted into that task's own PR/review checklist verbatim.
- **The Dash/Auction/Confirm Firestore schema gap (§1/§3F) will surface as a confusing bug, not a clean error, if online sync is activated before this plan's explicit deferral (§16) is honored.** A player on seat A submitting a Dash decision locally, expecting it to sync to seat B, will see nothing happen remotely — not because of a bug in this plan's render layer, but because `MatchService`/`MatchAdapter` genuinely cannot carry that action yet. This MUST be documented in-UI (e.g. an explicit "local-only" indicator during non-ESTIMATES phases) if online mode is ever enabled before the schema gap is closed, to avoid it reading as a broken render layer when it is actually a known, separately-scoped backend limitation.

**MEDIUM**
- **Two independent, non-identical UI contracts** (BiddingEngine has no `previewPlay()`-equivalent "what happens if I submit this" preview, only accept/reject via `canSubmit()`; TableEngine has both `canPlayCard()` AND `previewPlay()`). Task 5 will need to build its own "what would this bid result in" preview purely from already-public `state` fields (e.g., "if I bid 6 Spades, would I become the auction leader?" — answerable today by comparing `intent` against `state.auctionTop`/`auctionSuit` using the SAME `canSubmit()` call, not a new formula) — this is a UI convenience layer, not a new rule, and should be built as thin as `canSubmit()` already allows.
- **`table-engine.js` has no `busy`/AI-thinking flag** (§5) — Task 6-8 will need the renderer's own fixed-delay convention for AI turns during play, an asymmetry with Bidding's existing `state.busy` field.
- **No engine-level match-completion trigger exists** (§10, Task 11) — deferred, not silently invented.

**LOW**
- Card-visual-asset absence (§8) — lowest-priority gap, has a clear, cheap MVP answer (CSS-only cards).
- Three-turn-field ambiguity (§3C) — already well-documented; risk is a future engineer reading the WRONG turn field for a given phase, mitigated by this plan's explicit per-phase table.

---

## 13. Testing Strategy

- **Node, per task:** any NEW pure display-logic helper (e.g. a view-selection lookup function) gets a focused Node test, following the exact `tests/bidding-contract.test.cjs` convention (same require/harness pattern, `check()` helper, PASS/FAIL console output) — but the bulk of render-layer correctness is NOT Node-testable (it's DOM output), so Node tests here are narrow and few, by design.
- **Playwright, per task:** the primary verification tool for this entire plan, per Tasks 4/5/6/8/9/10/12/13's own acceptance criteria above — driving engine state directly via `emit()`/`resolveTrick()` calls (not simulated clicks, except in Task 5/10 where the click path itself is under test) and asserting DOM output matches.
- **Full regression, after every task:** re-run the full Node suite (baseline 1042, must stay 1042+ with zero regressions, exactly as every prior sprint in this project has required) — even though render-layer work shouldn't touch engine files at all, this is the cheap, fast confirmation that it genuinely didn't.
- **No new testing framework** — Node's plain `assert`-free `check()`/console-log convention and Playwright are the ONLY two tools this plan calls for, matching every prior sprint in this project's own established convention.

---

## 14. Definition of Done

The render layer is done only when ALL of the following are independently verified, not merely "the HTML looks good":

- [ ] Engine state renders correctly for every field enumerated in §4/§5.
- [ ] Every bidding control's enabled state and every submit action is gated by `BiddingEngine.canSubmit()` — confirmed by code review against §4's rule table, not just by testing happy paths.
- [ ] Every card control's enabled state and every play action is gated by `TableEngine.canPlayCard()`/`previewPlay()` — confirmed by code review against §5's rule table.
- [ ] Grep-level confirmation: zero occurrences of a re-derived formula for With-floor, Forbidden-13, Dash limits, suit strength, auction comparison, or Caller cap anywhere in the new render files.
- [ ] Remote updates render correctly (deferred to the §16 follow-on sprint's own Definition of Done — NOT claimed done by this plan's Tasks 1-13, which are offline-only).
- [ ] Local actions synchronize correctly (offline: engine state updates and re-renders; online: deferred to §16).
- [ ] Reload/resume works — a browser refresh mid-round correctly resumes via each engine's existing `initState()` resume logic, with the render layer correctly re-deriving its current view from the resumed `subPhase`/`phase`.
- [ ] No duplicate Firestore listeners — confirmed via the existing `onSnapshot` call-count check pattern already used in this project's own Playwright verification scripts.
- [ ] All 4 seats render correctly (names, dealer indicator, turn indicator).
- [ ] Hands render correctly (local face-up with real data, remote face-down with correct count and zero leaked data).
- [ ] Trick state renders correctly (in-progress plays, clears on resolution, winner highlighted).
- [ ] Score renders correctly (matches `GameSession.getMatchScores()`/`getLastRoundResult()` exactly, byte-for-byte, no rounding/re-derivation).
- [ ] Illegal actions are blocked (a disabled control cannot be clicked into an `emit()` call — verified by a Playwright test that attempts it anyway, e.g. via direct DOM event dispatch bypassing the disabled attribute, and confirms engine state is STILL unchanged).
- [ ] Legal actions work (the positive-path mirror of the above).
- [ ] No uncaught JS errors — explicitly EXCLUDING the pre-existing, still-expected `buildHand`/`bindStatic` errors ONLY IF this plan's Task 1 recommendation (never trigger the `DOMContentLoaded`→`advance()` path) is followed; if followed correctly, those errors should ALSO disappear, since nothing would call `initState()` via that path anymore — re-verify this explicitly rather than assuming it.
- [ ] Full Node suite passes at 1042+ (zero regressions).
- [ ] Full Playwright suite (Task 12/13's own suite) passes.

---

## 15. Explicit Out-of-Scope Items

- Online multiplayer sync activation (`start*Sync()` calls) — deferred to §16.
- The Dash/Auction/Confirm/Pass/DeclareTrump Firestore schema (currently `notImplemented()` stubs in `match-service.js`) — a genuine backend/schema design decision, not a rendering concern; this plan does not propose a schema.
- Match-completion rules (what ends a match — fixed round count? score threshold? per rules doc?) — flagged in Task 11 as an OPEN DECISION, not resolved here.
- Real visual/art design (card art, table backgrounds, sound) — §8 gives a functional MVP only; a separate design pass is explicitly out of this plan's scope.
- Any change to `bidding-engine.js`, `table-engine.js`, `session.js`, `cards.js`, `deck.js`, `dealer.js`, `scoring-engine.js`, `match-adapter.js`, `match-service.js` — forbidden per every task above.
- Firestore rules changes — nothing in this plan requires one (no new field is written by any task 1-13; §16's follow-on sync-activation sprint is where that question would first become relevant, and even then only if the Dash/Auction/Confirm schema gap is separately addressed).
- Mobile-native app, sound design, animations beyond basic CSS transitions, accessibility audit — none of these are addressed by this plan; noted as real future work, not silently assumed handled.

---

## 16. Recommended Sprint Sequence

1. **Sprint "Render Layer Foundation"** (Tasks 1-3) — shell + shared components + player/seat rendering. Lowest risk, establishes the pattern.
2. **Sprint "Bidding Render"** (Tasks 4-5) — can run in parallel with Sprint 3 below if two engineers are available (Bidding and Table are independent consumers, per Task 6's own dependency note).
3. **Sprint "Table Render"** (Tasks 6-8) — parallel-capable with Sprint 2.
4. **Sprint "Match Integration"** (Tasks 9-11, scoped per Task 11's correction) — requires both 2 and 3 complete.
5. **Sprint "Browser QA & Regression"** (Tasks 12-13) — requires Sprint 4 complete; establishes the durable Playwright suite for all future work on this screen.
6. **[SEPARATE, FUTURE, NOT PART OF THIS PLAN] Sprint "Online Bidding/Play Synchronization Activation"** — activates `start*Sync()`, and FIRST requires a product/architecture decision on the Dash/Auction/Confirm schema gap (§1/§3F/§15). This is deliberately sequenced LAST and kept OUT of this plan's own scope, since it depends on a decision this plan correctly does not have the authority or evidence to make unilaterally.

---

## FINAL RECOMMENDATION

**What we build first:** Sprint "Render Layer Foundation" (Tasks 1-3) — the Match Shell extension, shared UI component loading, and Player/Seat rendering. This is the lowest-risk, highest-confidence starting point: it touches only already-proven patterns (`match/index.html`'s existing shell, `shared-ui.css/js`'s existing components, `GameSession.getPlayers()`'s already-tested data), produces an immediately visible, verifiable result (4 real seats rendering), and de-risks every later task by proving the shell/view-swap mechanism works before any gameplay-rule-sensitive code (Bidding/Table controls) is written on top of it.

**What we deliberately do NOT build yet:** (1) Online sync activation (`start*Sync()`) — blocked on a real product decision about the Dash/Auction/Confirm Firestore schema gap this task discovered, which is not a rendering question and must not be resolved by inventing a schema unilaterally. (2) Match-completion rules (Task 11's scope correction) — genuinely undefined by any existing rules documentation or engine code; inventing round-count/score-threshold logic here would be exactly the kind of unauthorized rule invention this project has consistently avoided. (3) Real visual/art assets — a design-track concern, decoupled from the engine-wiring work this plan scopes.

**Why:** every prior sprint in this project succeeded by shipping the smallest additive, evidence-grounded change and stopping at genuine decision boundaries rather than guessing past them (the IIFE fix, the `canSubmit()` extraction, the equivalence verification). This plan's sequencing applies the same discipline to a much larger surface: prove the shell and data plumbing first, build the two gameplay renderers next (in either order, or parallel), integrate and verify last, and hold the line at the two genuine open product decisions (schema gap, match-completion rules) rather than resolving them by assumption.

**Exact next sprint:** "Render Layer Foundation — Match Shell, Shared Components & Player/Seat Rendering" (this plan's Tasks 1-3 exactly as scoped in §10).

**Exact effort level:** **MEDIUM** (Tasks 1-3 individually are each LOW; MEDIUM as their combined, single-sprint total, per §11's own per-phase breakdown — notably lower than the HIGH/HIGH/MEDIUM/MEDIUM effort of the Bidding/Table/Integration/QA sprints that follow it, since this first sprint deliberately touches no gameplay-rule-sensitive code at all).
