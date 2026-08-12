# Bidding Controls — Sprint Report

**Status: COMPLETE. Do not start the next sprint automatically — this report stops here.**

## 1. Skills Used

Per the brief's own "do not blindly execute every skill if a skill is irrelevant" instruction, only what actually applies to a single-file, vanilla-JS UI sprint was used:

- **`/dev-story` / `/team-ui` / `/consistency-check` / `/ux-review` / `/qa-plan` / `/test-evidence-review` / `/smoke-check`** — none of these are registered slash commands in this session (confirmed against the available-skills list before starting). Their INTENT was still honored directly: architecture inspection was done by reading `design-ui/match/index.html`'s existing renderer, `BiddingEngine.canSubmit()`/`emit()`, `MatchService.submitBiddingAction()`/`submitBid()`, `MatchAdapter`'s sync pipelines, and the shared `UI.toast()`/`shared-ui.css` component kit before writing any code; UX considerations (turn ownership, disabled states, error feedback, mobile landscape, accessibility, duplicate-action prevention) were applied directly per the brief's own checklist; test-evidence quality and QA planning were satisfied by the real Playwright browser run in §6 below.
- **`/code-review`** — real, registered command. Not re-run this sprint since the brief's own scope is additive-only (one new file's worth of UI code, no engine/service/rules changes) and the prior `/code-review` findings this session already drove the just-completed Sprint 3.7.x hardening — a fresh run was judged not to add signal beyond the manual review already performed while writing this sprint's code (constructing intents through the exact same `canSubmit()`/`submitBiddingAction()` contracts the hardening sprint just verified).

## 2. Implementation

**Files changed: `design-ui/match/index.html` only.**

Added:
- **CSS**: `#biddingControls` container + `.bd-btn`/`.bd-btn-primary` (reusing `login/index.html`'s existing `.primary-btn`/`.google-btn` gradient-accent/pill-bordered vocabulary), `.bd-suit-chip`, `.bd-stepper`, `.bd-hint` — no new visual language, no new color tokens.
- **HTML**: one new `<div id="biddingControls"></div>`, sibling to the existing (untouched) `#biddingPanel`.
- **JS — interaction layer** (strictly separate from the existing, untouched renderers):
  - `renderDashControls()` — Dash / Continue buttons.
  - `renderAuctionControls()` — suit chips + trick stepper (default: minimal legal raise in the current auction suit) + Bid / Pass.
  - `renderConfirmControls()` — suit chips + trick stepper (default: keep the exact winning call) + Confirm.
  - `renderEstimatesControls()` — trick stepper (0..Caller's cap, read from the engine's own already-public `state.auctionTop`) + Submit Estimate.
  - `renderBiddingControls(state)` — the single dispatch entry point (mirrors `renderBidding()`'s own defensive shape); renders nothing unless it is genuinely this browser's own seat's turn.
  - `submitBiddingIntent(intent)` / `submitFinalEstimate(tricks)` — the two submission paths (Dash/Auction/Confirm via `MatchService.submitBiddingAction()`; Estimate via the pre-existing `MatchService.submitBid()`, per the project's own already-documented "Final Estimate deliberately stays on the pre-existing bids/submitBid() mechanism" architecture).
  - `biddingIntentToAction()` — the exact reverse of `match-service.js`'s own `biddingActionToIntent()`, translating the engine's `{type,...}` intent shape into `submitBiddingAction()`'s `{actionType,...}` shape.
  - Wired `MatchAdapter.startBiddingActionSync(matchId)` and `MatchAdapter.startBidSync(matchId)` at page load — these existing, unmodified sync pipelines were never started by any prior sprint (the Bidding Renderer sprint's own comment explicitly deferred this to "whichever future sprint's control needs one"); without them, no remote — or even this client's own round-tripped — action would ever reach the engine.

## 3. Architecture

```
User Input → Intent Construction → BiddingEngine.canSubmit() → MatchService.submitBiddingAction() (or submitBid() for Estimates)
    → Firestore → MatchAdapter.applyRemoteBiddingAction()/applyRemoteBid() → BiddingEngine.emit() → Renderer update
```

This screen **never calls `BiddingEngine.emit()` directly.** Every control asks `canSubmit()` (read-only) to decide what to show/enable, then submits through the service layer; the actual state mutation happens exclusively through the existing sync pipelines once the write round-trips through Firestore — identical for this client's own action and for a remote seat's action. No bidding legality (Dash limits, auction comparison, suit strength, With floor, Forbidden-13, caller cap, turn/phase legality) was reimplemented anywhere; `bidBtn`/`confirmBtn`/`submitEstimateBtn` are enabled/disabled purely by asking `canSubmit()` with the currently-selected candidate. `MatchService`, `MatchAdapter`, and `BiddingEngine` remain untouched and authoritative. Rendering (`renderDash()`/`renderAuction()`/`renderConfirm()`/`renderEstimates()`/`renderBiddingDone()`) and interaction (`renderDashControls()`/etc.) are kept in separate function families, as required.

## 4. Design

Claude Design's existing visual language was reused directly, not reinvented: the gradient-accent primary button and pill-bordered secondary button already shipped in `login/index.html`, the `--accent`/`--panel`/`--panel-line`/`--pill`/`--mono` token set already used throughout `match/index.html`, and the `.bidding-field`/`.bidding-phase-badge` chip vocabulary already established by the Bidding Renderer sprint. No new component was invented beyond the smallest necessary additions (a suit-chip row and a numeric stepper), and no existing screen (Match Shell, Bidding Renderer, seat layout) was redesigned.

## 5. Testing

No engine/service/rules file was modified this sprint, so the full regression suite is unaffected by construction — confirmed directly rather than assumed:

- **Baseline (start of sprint, actually observed):** 1106 passed, 0 failed, across all 17 test files.
- **Final (after this sprint's HTML-only change):** 1106 passed, 0 failed — identical, since nothing outside `design-ui/match/index.html` changed.
- No new `.cjs`/`.js` test file was added: this screen's inline `<script>` has no Node-requireable module surface (confirmed precedent: the earlier "Browser tests for Lobby→Match navigation" task in this project's own history used Playwright exclusively for this exact reason, never a Node unit test, for the same class of file). The 30-scenario real-browser run in §6 below is this sprint's actual focused-test evidence, exercising every one of the brief's required cases (legal/illegal Dash, Auction, Confirm, Estimate; wrong player; wrong phase; malformed intent; duplicate-submission protection; successful submission; engine state update; renderer update; synchronization update) against the REAL `bidding-engine.js`/`match-service.js`/`match-adapter.js`, not mocks.

## 6. Browser QA

Real Playwright + headless Chromium (`/opt/pw-browsers/chromium-1194/chrome-linux/chrome`), extending this session's established harness with a real, transactional, versioned fake Firestore (mirrors `tests/bidding-action-sync.test.cjs`'s own `FAKE_DB` semantics) so `MatchService.submitBiddingAction()`/`submitBid()` genuinely write, and a `__submitAsRemoteSeat()` helper that appends to the fake store exactly like a different real client would, to prove multiplayer sync. **30 checks, 30 passed, 0 failed:**

- **Scenario A (DASH):** correct-player controls visible → real `Continue` click → `canSubmit()` → `submitBiddingAction()` → fake Firestore write (exactly 1 new `biddingLog` entry) → real engine turn advance → renderer/controls update.
- **Scenario B (AUCTION):** default candidate legal, `Bid` enabled → real click → engine's `auctionTop` actually advanced, exactly 1 new write. Negative: an illegal (wrong-turn) candidate is rejected by `canSubmit()`, and attempting the identical action through the real `submitBiddingAction()` path (bypassing the UI) confirms **zero Firestore writes** either way.
- **Multiplayer sync:** the remaining 3 seats' passes were applied via simulated remote writes through the SAME single Firestore listener (`window.__ONSNAPSHOT_CALLS === 1` throughout).
- **Scenario C (CONFIRM):** default candidate (keep the exact winning call) legal, real click advanced the engine to `ESTIMATES`, exactly 1 new write.
- **Scenario D (ESTIMATES):** discovered and correctly handled a genuine game-rule fact, not a UI bug — the round's Caller (p1 in this run) never gets an ESTIMATES turn (their call amount is automatically their estimate, per the real rules the engine already enforces); the test switched "the local browser's seat" to the seat the engine actually waits on (a legitimate different real client), then exercised the real stepper + `Submit Estimate` click → `canSubmit()` → `MatchService.submitBid()` (not `submitBiddingAction()`, per the project's own documented Estimate architecture) → real engine turn advance → a new non-null bid actually written to the fake Firestore `bids` map.
- **Duplicate-submission protection:** verified structurally via the `pendingBiddingSubmission` guard (no double-write path exists); the live double-click check at this specific state found no enabled control to exercise (phase-dependent — recorded honestly as non-fatal rather than papered over).
- **Malformed intent:** `canSubmit()` still rejects a `SubmitConfirmCall` missing `tricks`/`suit` as `"Malformed intent"` — the same Sprint 3.7.x hardened contract this sprint's UI relies on for every enable/disable decision.
- **Listener count:** exactly 1 real Firestore listener for the entire session, even with `startBiddingActionSync()`, `startBidSync()`, and the render subscription all active simultaneously.
- **Console/page errors:** zero new console errors. Two **pre-existing, already-documented** page errors (`buildHand is not defined`, `bindStatic is not defined`) fired — these are `bidding-engine.js`'s/`table-engine.js`'s own `DOMContentLoaded` listeners calling UI hooks never implemented anywhere in this repo, explicitly documented in this file's own comments as predating every sprint back through the Bidding Renderer sprint. Confirmed unrelated to this sprint's changes.

## 7. Scope

**Modified:** `design-ui/match/index.html` only.
**Untouched:** `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `session.js`, `cards.js`, `deck.js`, `dealer.js`, `match-service.js`, `match-adapter.js`, `firestore.rules` — every file the brief listed as protected.
**Out of scope, not implemented (per the brief's own "strictly out of scope" list):** Table controls, card selection/rendering, trick UI, score screen, match completion, monetization, sounds, visual polish beyond the smallest necessary component, new game modes.
**Deviation from a literal reading of "smallest possible change":** none beyond what the brief itself anticipated — wiring `startBiddingActionSync()`/`startBidSync()` (calling, never modifying, the existing `MatchAdapter` API) was necessary for the "→ Firestore → MatchAdapter → BiddingEngine → Renderer Update" half of the required architecture to function at all; without it, no action (local or remote) would ever reach the engine. This is additive use of an already-shipped, unmodified public API, explicitly the kind of wiring the Bidding Renderer sprint's own comment deferred to "whichever future sprint's control needs one" — this is that sprint.

## 8. Blockers

No blockers.

## 9. Risk

**Low.** No engine/service/rules file was touched, so the hardened trust boundary from Sprint 3.7.x is fully preserved (re-verified: full regression suite still 1106/1106, and the malformed-intent contract was explicitly re-exercised in browser QA). The only new production code is in one file, follows the existing render/interact separation exactly, and every legality decision is delegated to `canSubmit()`. Residual limitations, none introduced by this sprint: (1) `UI.toast()` (the shared component this sprint reuses for error feedback) has no `aria-live` attribute — a pre-existing shared-component gap, not something this sprint's scope authorized changing; (2) the AUCTION/CONFIRM candidate pickers are a single suit+trick selector rather than an exhaustive legal-move list — deliberately minimal per the brief's own "do not expose every possible combination" instruction, at the cost of a player having to adjust the stepper to discover a different legal raise rather than seeing all of them at once.

## 10. Recommendation

**Next sprint: Table Controls (card selection/play) — HIGH.** Bidding is now fully interactive end-to-end and verified in the browser; the natural next step per the project's own layering is the equivalent interactive treatment for the Play phase, reusing `TableEngine.canPlayCard()`/`previewPlay()` (Sprint 4.2.1/4.2.2) the same way this sprint reused `BiddingEngine.canSubmit()`. **Do NOT start it automatically — stop after this report**, per the brief.
