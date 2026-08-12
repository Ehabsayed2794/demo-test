# Gameplay Render Layer Audit

**Type:** Read-only architecture audit. No source code was modified. This is the only file created by this task.
**Method:** Direct source reading of every file named below, plus repo-wide `grep` for the identifier list in the brief. Historical `Sprint-*-Review/` directories were located but excluded from "live" conclusions — they are frozen snapshots of past deliverables, not currently-loaded code.
**Labeling convention (per the brief):** every claim below is tagged **FACT** (directly read in source), **DOCUMENTED** (asserted in a `docs/` file, not independently re-derived), or **INFERENCE** (a reasonable conclusion not directly stated anywhere). Conflicts between DOCUMENTED and FACT are reported side by side, never silently reconciled.

---

## 1. Executive Summary

**FACT:** `design-ui/match/index.html` is the only live Match screen. It loads all 8 engine files plus `MatchAdapter` (in the order established by the "Match Screen Engine Wiring" sprint) and successfully bootstraps `GameSession`, `BiddingEngine`, and `TableEngine` via `MatchAdapter.bootstrapGameSession()` and each engine's own `initState()`. This has been independently confirmed by a real Playwright browser run (documented in the prior sprint's report) — the redeclaration bug is fixed, all 8 globals load, and `ensureHandsDealt()`/`BiddingEngine.initState()`/`TableEngine.initState()` all succeed.

**FACT:** Neither `bidding-engine.js` nor `table-engine.js` renders anything to the DOM. Both files call a fixed set of UI/render functions (`render`, `buildHand`, `bindStatic`, `showDone`, `showRoundDone`, `showEscalationBanner`, `flashReject`, `sweepThenResolve`) that are **referenced but never defined anywhere in this repository** — not in either engine file, not in `match/index.html`, not in any other live `design-ui/` file, and not in any archived Sprint-Review snapshot either.

**FACT:** No card-game-specific render layer exists anywhere in the repository — no hand layout, no card image/sprite, no trick layout, no bidding controls, no trump/suit selector, no score display. The only reusable UI exists as `design-ui/login/shared-ui.css` + `shared-ui.js`, a small, generic Toast/Modal/Input/Skeleton kit with zero game-specific content, and it is not even loaded by `match/index.html` today.

**DOCUMENTED vs FACT conflict, found and reported (not reconciled):** `design-ui/SHARED_COMPONENTS.md` describes consolidating toasts/modals from files named `room.js`, `shop.js`, `Estimation Room.html`, `Estimation Shop.html`, a `bidding-render.js` (with its own `waitToast`), a `table-system.css`, and a "Game Table" `#modal` / "Final Standings" `.res-modal`. **None of these files exist anywhere in this repository** (confirmed by exhaustive filename search — see §7). This documentation describes a larger, more complete parallel project (matching the "Implementation Roadmap.md" / Claude Design planning-doc universe referenced in this session's plan) that was never actually imported into `design-ui/`. Treat every claim in `SHARED_COMPONENTS.md` about a render layer's *existing* shape as **unverified against this repository's actual contents**.

**Bottom line:** the engine (Deck → Dealer → GameSession → BiddingEngine/TableEngine) is real, tested, and now loads correctly in a browser. Everything between "engine state changes" and "a human sees or interacts with cards on screen" is completely absent. This is not a bug to patch — it is a missing architectural layer that was never built, and the two engine files' own `DOMContentLoaded` handlers were written assuming a paired screen would supply it.

---

## 2. Current Match Screen

`design-ui/match/index.html` — the only file in `design-ui/match/`.

| Aspect | Finding |
|---|---|
| Purpose | Confirms a match document was created in Firestore and shows its raw fields (status, matchId, round, dealer, player uid list). Originally a Sprint 3.4 placeholder; extended by the "Match Screen Engine Wiring" sprint to also load and bootstrap the engine. |
| Production/live? | **Yes** — it is the real, reachable screen Lobby navigates to (`GameState.goTo(GameState.STATES.GAMEPLAY, ...)`). Not a mockup. |
| Loads engine scripts? | **Yes** — all 8: `cards.js`, `deck.js`, `dealer.js`, `session.js`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `match-adapter.js`, loaded in that dependency order, after the existing Firebase/service scripts. |
| Renders gameplay? | **No.** It renders only: match status text, matchId, round number, dealer uid, a plain list of player uids, and one diagnostic line (`#matchEngineDiag`) reporting engine-load/init status as text. No hand, no bid, no card, no trick, no trump is ever displayed. |
| Inline UI logic? | Yes, a single inline `<script>` (~150 lines): screen-fit scaling, `renderMatch()` (writes the 5 plain-text fields above), `bootstrapEngineOnce()` (the diagnostic bridge described in §6), and a back-button handler. No game-board rendering logic exists in it. |
| Placeholder? | **Partially.** The Firestore-status half is real and functioning as designed (Sprint 3.4's explicit brief: "Placeholder screen is acceptable"). The engine-wiring half is real (not a placeholder) but produces no visible game — it is instrumentation, explicitly commented as "Diagnostic only — never a substitute for real gameplay UI." |

No other file exists in `design-ui/match/` besides this `index.html` and its own copy of `game-state.js` (identical navigation helper used by every screen, not game-related).

---

## 3. BiddingEngine UI Contract

Source: `design-ui/engine/bidding-engine.js` (full file read).

| Function | Called from | Called when | Data/state it needs | What the UI is expected to do | Exists in repo? | Documented? |
|---|---|---|---|---|---|---|
| `render()` | `advance()` (top of the function, every call) and implicitly whenever `advance()` runs | After every accepted intent, and once at boot (via `advance()` in the `DOMContentLoaded` handler) | The full working `state` object (`subPhase`, `waitingFor`, `hands`, `bids`, `auctionTop/Suit/Bidder`, `activeBidders`, `withPlayers`, `callerId`, `declaredTrump`, `logs`, `busy`) | Redraw the entire bidding screen from `state` | **No** — not defined anywhere (live or archived) | No |
| `buildHand()` | `DOMContentLoaded` handler, once, right after `initState()` | Once at page load | The user's dealt hand (`state.hands[userId]`) | Build the on-screen hand-of-cards UI for the human player | **No** | No |
| `bindStatic()` | `DOMContentLoaded` handler, once, right after `buildHand()` | Once at page load | None directly — expected to attach static DOM event listeners (bid buttons, suit selector, confirm button) that call `emit()` | Wire up one-time UI controls to `emit()` intents | **No** | No |
| `showDone()` | `advance()`, only when `state.subPhase === "DONE"` | Once, when bidding concludes | Final `state` (trump, caller, estimates) | Show a "bidding is over, proceeding to play" transition/overlay | **No** | No |
| `document.getElementById("doneOverlay")` | `restart()` | On an explicit restart | — | Assumes a `#doneOverlay` element exists in the paired HTML | **No** such element exists in `match/index.html` or anywhere live | No |

**State the UI must display (Q4), FACT — only fields that actually exist in `state`:**
- `subPhase` — `"DASH" | "AUCTION" | "CONFIRM" | "ESTIMATES" | "DONE"`
- `round` (number), `fastRound` (bool)
- `waitingFor` — current player id (whose turn to act)
- `hands` — `{ p1..p4: Card[] }`
- `bids` — sparse `{ id: {type, amount} }` (`DASHCALL | PASS | TRICKS | DASH`)
- `auctionTop`, `auctionSuit`, `auctionBidder`, `activeBidders[]`
- `withPlayers[]`, `callerId`, `declaredTrump`
- `lastBidderId`, `noSuitConstraint`
- `logs[]` — `{kind, text, intentTag}` — an existing, structured event log, directly usable as a UI activity feed with zero translation
- `busy` (bool) — true while an AI "thinks" — an existing hook for a loading/thinking indicator
- Legal actions are **not exposed as a field** — they are implicit in `subPhase` + whatever validation each `emit()` intent independently re-checks (e.g., `SubmitAuctionBid` requires `tricks >= 4`, `SubmitFinalEstimate` requires `tricks <= auctionTop`). A render layer must reconstruct legality client-side or call `emit()` speculatively and read back `{rejected, reason}`.

`emit(intent)` itself (`SubmitDashCallDecision`, `SubmitAuctionBid`, `SubmitConfirmCall`, `SubmitFinalEstimate`) is the one real, working, already-tested input contract — this is what a render layer's controls should call. It returns `{rejected: bool, reason?: string}` for the three intents with hard validation, and returns `undefined` (not `{rejected:false}`) for the paths that mutate state directly without returning early — this is a genuine minor inconsistency, noted as **FACT**, not a blocker.

---

## 4. TableEngine UI Contract

Source: `design-ui/engine/table-engine.js` (full file read).

| Function | Called from | Called when | Data/state it needs | What the UI is expected to do | Exists in repo? | Documented? |
|---|---|---|---|---|---|---|
| `render()` | `advance()`, every call | After every accepted play/resolution, and once at boot | Full `state` (`phase`, `turn`, `hands`, `currentPlays`, `trickNo`, `tricksWon`, `lastTrick`) | Redraw the table (hands, played cards, trick pile) | **No** | No |
| `bindStatic()` | `DOMContentLoaded` handler, once | Once at page load | — | Wire up static play-area controls | **No** | No |
| `showEscalationBanner()` | `advance()`, when a Sa'ayda escalation condition is reached (per that branch's own comment) | Mid-round, conditionally | Escalation/multiplier state | Show a banner for the doubled-stakes event | **No** | No |
| `showRoundDone()` | `advance()`, when `phase === "DONE"` | Once, at round completion | Final `tricksWon`, scoring result | Show round-end summary | **No** | No |
| `sweepThenResolve()` | `advance()`, when a trick is complete (4 plays in) | Once per completed trick | The 4 completed plays | Animate the "sweep" of cards into the trick pile, THEN call `resolveTrick()` | **No** | No |
| `flashReject()` | `playFromHand()`, when a play is illegal | On a rejected card-play attempt | The rejected card/reason | Flash an error indicator | **No** | No |
| `document.querySelector(".round-done")` | `restart()` | On explicit restart | — | Assumes a `.round-done` element exists | **No** such element exists live | No |

**State the UI must display (Q4), FACT:**
- `phase` — `"PLAY" | "RESOLVING" | "DONE"`
- `round` (number), `trickNumber`, `leaderId`, `turn` (current player id)
- `hands` — `{p1..p4: Card[]}` (mutates as cards are played)
- `currentPlays` — array of `{playerId, card}` for the in-progress trick
- `ledSuit`
- `tricksWon` — `{p1..p4: count}`
- `lastTrick` — `{winnerId, plays, ...}` after `resolveTrick()`
- `voids` — `{p1..p4: [suits already shown void]}` — an existing hook that lets a renderer show "can't follow suit" state without re-deriving it
- Score-related state: **not held here** — `TableEngine` never computes score; that is `ScoringEngine`'s job, invoked from inside `resolveTrick()` at trick 13 only (see §6/§8 boundary).
- Legal actions: exposed via **two pure, already-tested public exports** — `canPlayCard(playerId, card)` and `previewPlay(playerId, card)` (both added in Sprints 4.2.1/4.2.2 specifically so a render layer or MatchAdapter could ask "is this legal?" without duplicating rules). This is a materially better contract than BiddingEngine's — a render layer can grey out illegal cards using `canPlayCard()` directly, with zero engine mutation.

---

## 5. GameSession State Contract

Source: `design-ui/engine/session.js` (full file read).

- **Players/seats:** `getPlayers()` returns a fixed 4-entry array of rich mock objects: `{id: "p1".."p4", name, initial, isUser, isAI, isRemote, rank, rp, wins, streak, level, coins, gems}`. `getPlayer(id)` looks up one. This is the ONLY place player display names/avatars/initials currently exist — a render layer would read this, not invent its own roster.
- **Hands:** `getHands()`/`getHand(id)` — `{p1..p4: Card[]}`, persisted in `sessionStorage`. `ensureHandsDealt()` is the funnel every screen should call (deals once per round, idempotent otherwise).
- **Dealer:** `getDealer()`/`setDealer(id)`/`rotateDealer()` — a plain seat id string.
- **Turn state:** THREE independent turn fields exist, not one — `getTurn()`/`setTurn()` (top-level, Firestore-mirror-facing, populated by `MatchAdapter.applyRemoteTurn()`), `getBiddingState().turnId` (owned by `bidding-engine.js`'s own reducer), and `getPlayState().turnId` (owned by `table-engine.js`'s own reducer). This is a **FACT**, directly confirmed by reading all three call sites — `MatchAdapter`'s own header comment (§6 below) independently documents this exact same finding, so FACT and DOCUMENTED agree here.
- **Current game state access:** `get()` returns the entire raw session object; more useful in practice are the scoped getters above plus `getRound()` (number/multiplier/trump/callerId/withPlayers/estimates/dashCallers), `getPlayState()`, `getBiddingState()`.
- **Public API a renderer would actually need:** `getPlayers()`, `getPlayer()`, `getDealer()`, `getRound()`, `getHand(id)`/`getHands()`, `getBiddingState()`, `getPlayState()`, plus `onRemoteMatchUpdate(callback)` — GameSession's own local pub/sub, already built (Sprint 3.7) specifically so a UI layer never needs to poll or reach into `MatchService`/`MatchAdapter` directly. **This is the one existing hook a render layer could subscribe to today with zero new engine code** — nothing currently calls it from `match/index.html`.

---

## 6. MatchAdapter → Engine Flow

Source: `design-ui/match-adapter.js` (full file read — 1400 lines; the portion beyond line ~955 covers `startBidSync`/`startTurnSync`/`startCardSync`/`startTrickSync`, all following the identical shape already documented in this project's own Sprint 4.0–4.3 reports, consistent with the header comment read in full).

**Bootstrap (one-shot, Sprint 3.9):**
`bootstrapGameSession(matchDoc)` — pure translation via `matchDocToEngineSnapshot()`, then writes `GameSession.setRound({number})`, `setDealer()`, `setTurn()`. Called once by `match/index.html` today. Does not touch `BiddingEngine`/`TableEngine` at all — it only seeds `GameSession`.

**Sync (ongoing, additive per sprint):**
- `startBidSync(matchId)` → `applyRemoteBid()` → the ONLY caller of `BiddingEngine.emit({type:"SubmitFinalEstimate", ...})` in the codebase. Version-gated, phase-gated (`subPhase === "ESTIMATES"` only), turn-gated.
- `startTurnSync(matchId)` → `applyRemoteTurn()` → only ever calls `GameSession.setTurn()` (a mirror copy, never a decision).
- `startCardSync(matchId)` → `applyRemoteCard()` → the ONLY caller of `TableEngine.emit({type:"PlayCard", ...})`.
- `startTrickSync(matchId)` → `applyRemoteTrick()` → the ONLY caller of `TableEngine.resolveTrick()`.

**None of these four sync pipelines is started by `match/index.html` today** (FACT — confirmed by reading the file in full in §2; only `bootstrapGameSession()` is called). This is an explicit, documented design choice from the "Match Screen Engine Wiring" sprint, not an oversight: starting a sync pipeline with no render layer to observe its effect "risks being mistaken for real functionality it doesn't have."

**What should a render layer listen to?** Per the adapter's own layering (never touch `MatchAdapter` or `MatchService` directly from a renderer — restated as a hard rule in §12), a render layer should subscribe to:
1. `GameSession.onRemoteMatchUpdate()` for the raw Firestore mirror (connection status, existence).
2. `BiddingEngine.getState()` / `TableEngine.getState()`, re-read after every engine mutation, to know what changed. Neither engine exposes a change-event/pub-sub of its own (**FACT** — grep confirms zero `addEventListener`/callback-registration pattern in either file besides the one `DOMContentLoaded` listener each registers on itself). A render layer must either poll `getState()` after calling `emit()` itself, or be invoked by a thin wrapper that calls `render()` after each `MatchAdapter.applyRemote*()` call — which is exactly what the undefined `render()` hook inside each engine's own `advance()` was originally meant to be.

**Should MatchAdapter change?** No repository evidence suggests this is necessary. Every `applyRemote*()` function's own doc comment is explicit about scope, and the file's header states its philosophy ("translates identities — nothing else") consistently across 5 sprints. The render layer's job is to consume `MatchAdapter`'s existing outputs (translated `GameSession`/engine state), not to receive new outputs from it.

---

## 7. Existing UI Assets

Exhaustive search of `design-ui/` (live) for: card components, card images/backs, avatars, table backgrounds, bidding/trump controls, hand/trick layouts, score displays, buttons, modals, toasts, shared components, CSS design systems.

**A) EXISTING AND USABLE**
- `design-ui/login/shared-ui.css` + `shared-ui.js` — generic `UI.toast()`, `UI.openModal()/closeModal()/bindModalDismiss()`, `.ui-field`/`.ui-input` text input, `.ui-skel` loading skeleton. Framework-agnostic, uses the shared `--accent`/`--panel`/`--ink`/`--mono` design tokens every screen (login, lobby, profile, match) already uses. **Directly reusable** for a future Bidding/Table screen's toasts, confirm dialogs, and loading states — but contains **zero** card-game-specific markup.
- The shared design-token system itself (`--accent`, `--panel`, `--ink`, `--mono`, the Marcellus/Saira/Spline-Sans-Mono font stack) — consistently present in every live screen including `match/index.html` — is a real, usable visual foundation any new render layer should extend rather than reinvent.

**B) EXISTING BUT DISCONNECTED**
- `shared-ui.css`/`shared-ui.js` themselves, relative to `match/index.html` specifically — they are usable (A), but **not currently `<script>`/`<link>`-loaded by `match/index.html`** (confirmed by re-reading its full `<head>`/script list in §2). "Disconnected from Match" and "usable in general" are both true at once.

**C) HISTORICAL / ARCHIVED**
- `Sprint-3.4-Review/design-ui/match/index.html` and similar snapshots under `Sprint-*-Review/` — frozen copies of past deliverables for audit trails, not live code. They mirror what's now in `design-ui/` (or an earlier version of it) but are never loaded by anything.

**D) MISSING** (repo-wide — none of the following exist anywhere, live or archived)
- Any card image, card-back image, or card sprite/SVG set.
- Any player-avatar asset (the `initial` field in `GameSession.getPlayers()` — e.g. "Y", "L", "F", "O" — is the only "avatar" today: a single letter, styled inline elsewhere in Lobby, not a reusable component).
- Any table-background asset.
- Any bidding-control markup (number pad, suit selector) — not even a rough draft.
- Any trump/suit indicator control.
- Any hand-layout or trick-layout CSS/component.
- Any score display component.
- `table-system.css`, `bidding-render.js`, `room.js`, `shop.js`, `Estimation Room.html`, `Estimation Shop.html` — all **referenced by `design-ui/SHARED_COMPONENTS.md`** but confirmed absent from this repository by direct filename search (see Executive Summary's DOCUMENTED-vs-FACT conflict).

---

## 8. Missing Render Layer

Precisely restating what's absent, grounded in §3/§4's tables: a layer that (1) reads `BiddingEngine.getState()`/`TableEngine.getState()`/`GameSession.getPlayers()` and draws them, (2) provides the 12 undefined functions (`render`×2, `buildHand`, `bindStatic`×2, `showDone`, `showRoundDone`, `showEscalationBanner`, `flashReject`, `sweepThenResolve`) or replaces the code paths that call them, and (3) turns user clicks into `emit()`/`canPlayCard()`/`previewPlay()` calls. Nothing in the repository provides any part of this today — confirmed by the same grep sweep used in §7 (zero hits for `BiddingRenderer`/`TableRenderer`/`HandRenderer`/`CardRenderer`/etc. anywhere, live or archived).

---

## 9. Minimum Render Architecture

Responsibilities actually implied by the two engines' own call sites (§3/§4) — not invented, not designed, just named from what's already being called:

| Responsibility | Evidence it's needed | Maps to which undefined call(s) |
|---|---|---|
| Read `BiddingEngine`/`TableEngine` state and reflect it in the DOM | Both `advance()` functions call `render()` after every state change | `render()` (×2, distinct per engine) |
| Build/update the human player's visible hand | `bidding-engine.js`'s boot sequence calls `buildHand()` once; `TableEngine`'s `hands` field mutates every play | `buildHand()` |
| Attach one-time control event listeners → `emit()`/`canPlayCard()`/`previewPlay()` | Both `DOMContentLoaded` handlers call `bindStatic()` once | `bindStatic()` (×2) |
| Phase-transition UI (bidding done → play; round done → next round) | `showDone()`, `showRoundDone()` | phase-boundary display |
| Escalation/Sa'ayda banner | `showEscalationBanner()` | one specific game event |
| Trick-collection animation before resolving | `sweepThenResolve()` wraps `resolveTrick()` | one specific game event |
| Illegal-move feedback | `flashReject()`, and `canPlayCard()`'s existing boolean | input validation feedback |

This does **not** require inventing named classes like `MatchRenderer`/`CardRenderer`/`ActionController` — the repository gives no evidence either engine expects a class-based structure (both are plain closures with plain function calls). The minimum functional shape is: **one render/bind module per engine screen** (a Bidding screen's own inline script satisfying `bidding-engine.js`'s 3 hooks; a Table screen's own inline script satisfying `table-engine.js`'s 6 hooks), each free to internally factor into smaller pieces (hand, trick, score sub-renders) — but nothing in the repo requires that decomposition; it's a reasonable implementation choice, not a contract requirement. **This paragraph is INFERENCE**, clearly separated from the FACT table above it.

---

## 10. MVP Playable Match

Per §5's `emit()`/`canPlayCard()`/`previewPlay()`/`getState()` contracts, the smallest functional slice:

1. **Can four players see their hands?** Partially possible today: `GameSession.getHands()` returns real dealt hands (FACT, confirmed live in Playwright: `p1:13 p2:13 p3:13 p4:13`). No UI exists to draw them (§8). **Blocked by**: missing render layer only, not missing engine data.
2. **Can the current player see legal actions?** Bidding: no explicit legal-action list exists (§3) — a renderer must derive it from `subPhase` + intent-shape knowledge, or attempt-and-read-`{rejected,reason}`. Table: **yes**, cleanly — `canPlayCard(playerId, card)` is a ready-made pure legality check (§4). **Blocked by**: missing render layer; Bidding's contract is weaker than Table's but not blocking.
3. **Can a player submit a bid?** Yes — `BiddingEngine.emit({type:"SubmitDashCallDecision"/...})` is fully implemented and tested (954/954 suite). **Not blocked** at the engine layer; blocked only by no UI control existing to call it.
4. **Can the UI reflect the updated bid state?** No — requires `render()`, which doesn't exist (§3). **Blocked by**: missing render layer.
5. **Can a player play a card?** Yes — `TableEngine.emit({type:"PlayCard", ...})`, tested. **Not blocked** at the engine layer.
6. **Can the UI show the trick?** No — `currentPlays` exists in state (§4) but nothing draws it. **Blocked by**: missing render layer.
7. **Can the UI advance to the next turn?** The engine advances `turn`/`waitingFor` correctly on every accepted `emit()` (tested); nothing re-renders to show it. **Blocked by**: missing render layer.
8. **Can the UI show the trick winner?** `resolveTrick()` computes and stores `lastTrick.winnerId` correctly (tested, and independently re-verified this session's Sprint 4.3 work); nothing displays it. **Blocked by**: missing render layer.
9. **Can the UI reach the next game phase?** `GameSession.nextRound()` exists and is correct (tested); nothing in the UI calls it — no screen currently transitions Bidding → Table → next round at all, since neither screen exists. **Blocked by**: missing render layer AND missing screen-to-screen navigation wiring (a `GameState.goTo()` call from a paired Bidding/Table HTML screen, which does not exist).

**Summary:** every single item is blocked by the SAME root cause — the missing render layer — not by any missing or broken engine capability. This is the audit's central, repeatable finding, restated once more plainly: **the engine is MVP-ready; the UI is not.**

---

## 11. Files Required for Implementation

(Named for responsibility, not designed — per the brief, no code, no visual design.)

**To be created:**
- A Bidding screen HTML file (its own inline script, analogous to `match/index.html`'s own pattern) satisfying `bidding-engine.js`'s 3 undefined hooks (`render`, `buildHand`, `bindStatic`) and providing `#doneOverlay`.
- A Table/Play screen HTML file satisfying `table-engine.js`'s 6 undefined hooks (`render`, `bindStatic`, `showEscalationBanner`, `showRoundDone`, `sweepThenResolve`, `flashReject`) and providing `.round-done`.
- Card-facing visual assets (or a CSS-only card representation — no evidence either way is required by the repo) — currently zero exist (§7D).
- A navigation path connecting Match → Bidding → Table → (next round or Final Standings), since `GameState.goTo()` calls only exist for screens that already exist.

**Likely to need MODIFICATION (not creation), to actually wire a new screen in — flagged, not decided:**
- `design-ui/match/index.html` — if the intended flow is "Match screen redirects to Bidding/Table once bootstrapped" rather than staying a standalone diagnostic page, its `bootstrapEngineOnce()` would need a navigation call added. This is a design decision, not something this audit can resolve from evidence alone.

---

## 12. Files That Must Not Be Modified

Per this project's own established, repeatedly-restated architecture rules (confirmed FACT via each file's own header comment, consistent across Sprints 3.9–4.3):
- `design-ui/engine/bidding-engine.js`, `design-ui/engine/table-engine.js`, `design-ui/engine/scoring-engine.js`, `design-ui/engine/session.js`, `design-ui/engine/dealer.js`, `design-ui/engine/deck.js`, `design-ui/engine/cards.js` — gameplay rules must not be duplicated or altered by a render layer; a renderer only calls existing public APIs (`emit`, `canPlayCard`, `previewPlay`, `getState`, `GameSession.get*`).
- `design-ui/match-adapter.js` — the sole Firestore↔engine seam; a renderer never calls Firestore/`MatchService` directly and never re-implements translation logic already here.
- `design-ui/match-service.js` — Firestore-only; must never `require`/reference `GameSession` or any engine file (its own established, tested invariant).
- `firestore.rules` — no new render-only need justifies a schema/rule change; nothing in this audit found evidence otherwise.

---

## 13. Risks

**CRITICAL**
- None found that block *starting* render-layer work — the engine layer is sound and tested. (No Critical risk identified in this audit's scope.)

**HIGH**
- **Two independent, non-identical UI contracts.** `TableEngine` exposes clean pure-function legality checks (`canPlayCard`, `previewPlay`); `BiddingEngine` exposes none — a renderer must re-derive bid legality or rely on trial-and-error against `emit()`. Building both screens without normalizing this gap risks inconsistent code quality/duplicate validation logic between the two screens.
- **No engine-side render/change-event hook.** Both engines assume the OLD synchronous `advance() → render()` model. Reconciling that with the NEW asynchronous `MatchAdapter.applyRemote*()` sync model (which calls `emit()`/`resolveTrick()` from a Firestore callback, not from a `DOMContentLoaded`-driven turn loop) means a render layer must be designed to be called from BOTH triggers (local UI action AND remote sync delivery) — a real design decision, not a trivial wrapper.
- **`SHARED_COMPONENTS.md` describes a fictional larger codebase.** Any implementer trusting that doc's claims about existing Room/Shop/Game-Table conventions without first verifying against the actual repo (as this audit did) will build against files that don't exist.

**MEDIUM**
- **Three separate turn fields** (`GameSession.getTurn()`, `getBiddingState().turnId`, `getPlayState().turnId`) — a render layer must know which one is authoritative for which phase; this project's own docs already flag this as a known gap (§5/§6), not fixed by this audit.
- **`emit()`'s inconsistent return shape** in `bidding-engine.js` (some paths return `{rejected}`, others return `undefined` implicitly) — a renderer checking `.rejected` on every call must guard against `undefined`.
- **No screen exists to receive `GameState.goTo()`** for Bidding/Table — building the render layer without also deciding the navigation story (single-page vs. multi-screen) risks rework.

**LOW**
- Card-visual-asset absence (§7D) is a content/design gap, not an architectural one — lowest priority to resolve relative to the structural gaps above.
- `doneOverlay`/`.round-done` element-name assumptions baked into `restart()` in both engines are trivial to satisfy once a screen exists.

---

## 14. Recommended Implementation Sequence

(Sequencing only — no design, no code, per the brief.)

1. Decide the navigation/screen-count model (one combined Bidding+Table screen vs. two, and how Match → that screen → Final Standings connects) — this is a prerequisite decision, not inferable from evidence alone.
2. Build the render/bind layer for ONE engine first (recommend `table-engine.js` — its `canPlayCard`/`previewPlay` contract is materially more render-ready than `BiddingEngine`'s), satisfying its undefined hooks against real `getState()` data, reusing `shared-ui.css`/`shared-ui.js` where applicable (toasts, modals) rather than rebuilding them.
3. Build the equivalent layer for `bidding-engine.js`, likely first normalizing its `emit()` return-shape and/or adding a legality-preview export mirroring `TableEngine.canPlayCard()`'s pattern (a small, additive, non-breaking change if pursued — a future sprint's decision, not this audit's).
4. Wire `MatchAdapter`'s `start*Sync()` pipelines (currently unstarted from any live screen — §6) into whichever screen(s) result from step 1, once there's a render layer to observably benefit from them.
5. Re-run the full Node suite + a real Playwright pass after each step, exactly as every prior sprint in this project has done.

---

## 15. GO / NO-GO

**ENGINE/API GAP BLOCKS RENDER IMPLEMENTATION**

(Specifically: not a defect in the engine's own logic — all 954 tests pass and the engine loads/initializes correctly in a real browser — but the engine's own hook contract, as written, unconditionally calls 12 UI functions that exist nowhere in the repository, and no navigation path or screen exists to host them. Implementation cannot proceed directly to "write the render layer" without first resolving §14 Step 1's screen/navigation model decision and, secondarily, deciding whether to leave `BiddingEngine`'s weaker legality contract as-is or extend it to match `TableEngine`'s — both genuine open decisions this audit could not resolve from repository evidence alone.)
