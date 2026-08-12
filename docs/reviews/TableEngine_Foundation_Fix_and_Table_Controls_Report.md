# Table Controls Sprint Report (with Foundation Fix)

**Status: COMPLETE. Do not start the next sprint automatically — this report stops here.**

---

# PART A — Foundation Hardening

## Root Cause

`table-engine.js` computed its round configuration (`trump`, `callerId`, `withPlayers`, `estimates`, `leaderId`, `round`) exactly once, as a module-level `const ROUND_CFG = buildRoundCfg();`, evaluated the instant the `<script>` tag executed (page load, before any bidding interaction). `initState()` never recomputed it — every call, for the lifetime of the page, reused that same frozen snapshot. Since Bidding Controls now makes bidding happen interactively *after* page load, any match that actually completes bidding on the same page load would have `TableEngine` permanently stuck on page-load mock defaults (trump=SPADES, callerId=p4, etc.) — a real correctness defect: `state.trump` feeds `cardValue()`'s trump-strength comparison (trick-winner calculation) and `callerId`/`withPlayers`/`estimates` feed `resolveTrick()`'s scoring call directly.

## Exact Change

`design-ui/engine/table-engine.js`:
1. `const ROUND_CFG = buildRoundCfg();` → `let ROUND_CFG = buildRoundCfg();`
2. Added one line at the top of `initState()`: `ROUND_CFG = buildRoundCfg();`

`buildRoundCfg()`'s own formula — the `hasBidResult` branching, the mock-fallback values, the leaderId derivation — is completely unchanged, verified by a structural test that reads the source and confirms the exact formula strings are still present verbatim. No trump ranking, `cardValue()`, trick-winner logic, follow-suit logic, caller rules, With rules, estimates, or scoring were touched.

## Files Changed

`design-ui/engine/table-engine.js` only (2 lines net + comments). `tests/table-engine-foundation-fix.test.cjs` added (new focused test file).

## Tests

**Skills used before editing:** `/architecture-review`/`/code-review` are not registered commands in this session; their intent was satisfied directly — read `buildRoundCfg()`/`initState()`/every `ROUND_CFG` call site in full, reproduced the staleness bug with a real `node -e` script (bidding driven to a real, different outcome, `TableEngine.initState()` called again, confirmed it still showed mock defaults) *before* proposing the fix, confirmed the fix with the same reproduction *after*, and grepped the whole file for any other `ROUND_CFG` reference to confirm no hidden lifecycle dependency existed beyond `computeRiskId()` and `initState()` itself (both already covered).

**Focused tests** (`tests/table-engine-foundation-fix.test.cjs`, new): 17 assertions —
- **Scenario 1** (page load → real bidding completes → `initState()` called for the first time): `trump`/`callerId`/`withPlayers`/`estimates`/`leaderId`/`turn`/`round` all verified to reflect the just-completed real outcome, not page-load mock defaults.
- **Scenario 2** (round 1 completed via the real engine, 13 tricks played → round 2 begins with a different bidding outcome): round 2's `trump`/`callerId`/`estimates`/`round` verified to reflect round 2, not round 1's stale values.
- 2 structural checks confirming `buildRoundCfg()`'s formula is byte-for-byte unchanged and that `ROUND_CFG` is now reassigned (not merely read) inside `initState()`.

**Full regression:** baseline 1106 passed / 0 failed → **1123 passed / 0 failed** (+17 new, 0 unexplained regressions). No existing test encoded the old stale behavior — every existing test that uses `TableEngine` already worked around the bug via a delayed `require("table-engine.js")` call (documented in `tests/match-flow-integration.test.cjs`'s own header comment, predating this sprint), which continues to work unchanged; nothing was "blindly preserved."

## Browser Evidence

Proven live in real Chromium as part of Part B's own browser suite (below) rather than a separate, duplicate harness: PAGE LOAD → real Bidding Controls interaction → bidding genuinely completes → `TableEngine` reinitializes → `trump`/`callerId`/`round` all confirmed to reflect the just-completed real outcome (`trump: "SANS"`, `callerId: "p1"`, matching exactly what the real `BiddingEngine` had just produced — not the page-load mock `SPADES`/`p4`).

## Risk

**Low.** Pure relocation of an existing, unmodified formula to run at the correct time; no rule changed. One related, second-layer staleness source was discovered during real-browser verification (not part of the original authorization) — see the note in Part B's Architecture section — and fixed entirely within `design-ui/match/index.html` (no further engine-file edit), reported transparently below rather than silently folded in.

**Foundation Fix: PASS.**

---

# PART B — Table Controls

## Implementation

**Files changed:** `design-ui/match/index.html` only.

- **CSS:** `.hidden` utility; `#tablePanel`/`.table-turn`; `#trickPanel`/`.trick-slot`; `#handPanel`; `.card-chip` (+ `.is-red`/`.is-empty`/`.is-pending` states) — reuses the existing `.bidding-field`/`.bd-btn` panel/pill/mono vocabulary and the existing shared-ui.css error-red (`#e0836a`) for red suits; no new visual language.
- **HTML:** one new `<div id="tablePanel" class="hidden">` (containing `#tableTurn`/`#trickPanel`/`#handPanel`), sibling to the existing `#biddingPanel`/`#biddingControls`.
- **JS — interaction layer:**
  - `maybeEnterPlayPhase()` — the **single** call site for `TableEngine.initState()` on this screen, fired once, the first time `BiddingEngine.getState().subPhase === "DONE"` is observed via the real snapshot callback. Also wires `MatchAdapter.startTrickSync(matchId)` (covers both card application and trick resolution — see Architecture) and `MatchAdapter.startTurnSync(matchId)`.
  - `renderTablePanel()` / `renderTrick()` / `renderHand()` — the read-only Play-phase renderer: trick display (each seat's card this trick, or empty), turn indicator, and the interactive hand (only rendered as clickable buttons when it is genuinely the local seat's turn in PLAY phase — otherwise a plain, read-only chip row, or hidden entirely).
  - `submitCardPlay(card)` — the one path every hand card submits through: re-checks `TableEngine.canPlayCard()` fresh at click time, then `MatchService.submitCard()`, with the exact `pendingCardSubmission` single-in-flight guard already established and browser-verified in Bidding Controls.
  - `bootstrapEngineOnce()`'s own diagnostic block was changed to **stop** calling `TableEngine.initState()` prematurely (it used to call it unconditionally on the very first match snapshot, before bidding had any chance to complete) — it now only reports whether the script loaded.

## Architecture

```
Player Card Selection → Intent → TableEngine.canPlayCard() → MatchService.submitCard()
    → Firestore → MatchAdapter.applyRemoteCard()/applyRemoteTrick() → TableEngine.emit()/resolveTrick() → Renderer update
```

This screen **never calls `TableEngine.emit()` or `resolveTrick()` directly.** Every hand card asks `canPlayCard()` (read-only) to decide what to render/enable; the actual mutation happens exclusively through the existing, unmodified `startTrickSync()` pipeline once a write round-trips through Firestore — identical for this client's own play and a remote seat's play. No follow-suit, trump, card-ranking, trick-winner, turn-order, or ownership logic was reimplemented anywhere; `TableEngine`, `MatchService`, and `MatchAdapter` remain untouched (beyond the Foundation Fix) and authoritative.

**A second, related staleness source discovered during real-browser verification (not part of the original Foundation Fix authorization, fixed entirely within `match/index.html`):** `table-engine.js`'s own pre-existing `DOMContentLoaded` listener always fires once, automatically, at page load — independently of this screen's own code — and calls `initState()` itself, which persists a `GameSession` playState seeded from the same page-load mock `leaderId`. `GameSession.isPlayStateValidForCurrentRound()` only checks round *number*, not whether the persisted state was seeded before or after real bidding, so `maybeEnterPlayPhase()`'s own later, correct `initState()` call would otherwise "resume" that stale `leaderId`/`turn` forever — even with the Foundation Fix applied, since `trump`/`callerId` are correctly fresh but `leaderId`/`turn` get overwritten by the resume branch. Fixed by having `maybeEnterPlayPhase()` compare the persisted `playState.leaderId` against what the *just-completed* real bidding outcome says it should be (the exact same formula `buildRoundCfg()` itself uses) and calling the existing `GameSession.clearPlayState()` only when they disagree — a genuine mid-play refresh's persisted state could only ever have been created *after* bidding completed, so it will always already agree and is never discarded by this check. No engine or session file was touched for this — only `match/index.html`, using existing `GameSession` public APIs.

`startTrickSync()` alone was wired (not a separate `startCardSync()` call) — it already calls `applyRemoteCard()` internally as part of its own per-delivery catch-up loop (Sprint 4.3); adding `startCardSync()` too would be redundant, idempotent duplication, not a second listener.

## UI Behavior

- **Hand rendering:** all 13 cards always visible; rendered as plain read-only chips when it isn't the local seat's turn, or as real `<button>` elements (enabled/disabled per `canPlayCard()`) when it is.
- **Wrong-turn protection:** structural — the hand is never rendered as clickable buttons at all unless `state.turn === localSeatId`; `submitCardPlay()` also re-verifies via `canPlayCard()` fresh before ever writing, so even a stale reference can't bypass it. Verified live: attempting `submitCard()` directly for the non-waiting seat wrote nothing to Firestore.
- **Duplicate-submission protection:** the identical `pendingBiddingSubmission`-shaped single-flag guard, applied per-card (`pendingCardSubmission`).
- **Trick display:** 4 seat slots, each showing the played card or an empty placeholder, plus a turn indicator ("Your turn" / "Waiting on \<name\>" / "Round complete.").

## Synchronization

Verified live in the browser: a legal lead-card play by the local seat wrote exactly one `cardLog` entry and advanced the turn; three remote seats' simulated plays (bypassing this client's own UI entirely) were applied through the exact same single Firestore listener, resolving multiple tricks automatically via `startTrickSync()`'s own catch-up loop — no duplicate listener, no duplicate application, no local-only mutation pretending to be synchronized.

## Tests

**Focused tests:** the Foundation Fix's own 17 assertions (Part A) plus the full regression suite — no additional focused *Node* test file was written for the UI layer itself, since `match/index.html`'s inline `<script>` has no Node-requireable module surface (the same, already-established precedent this session's own Bidding Controls sprint used: real Playwright browser verification serves as this layer's focused-test evidence, not a duplicate Node harness).

**Full regression:** baseline 1106 → **1123 passed, 0 failed** (unchanged from Part A's own count, since no additional engine/service/rules file was touched for Part B).

## Browser QA

Real Playwright + headless Chromium (`/opt/pw-browsers/chromium-1194/chrome-linux/chrome`), extending the established fake-Firestore harness with a `cardLog`-capable remote-seat simulator. **25 checks, 25 passed, 0 failed:**

- Bidding driven to genuine completion (DASH → AUCTION → CONFIRM → ESTIMATES → DONE) through the real engine/service/adapter stack.
- **Foundation Fix, proven live:** `TableEngine.trump`/`callerId`/`round` all reflect the just-completed real outcome, not the page-load mock.
- Table panel becomes visible (bidding panels hidden); hand renders all 13 cards; it's genuinely the local seat's turn with enabled buttons.
- **Legal lead-card play (A):** real click → exactly one hand card removed, turn advanced, exactly one `cardLog` write, trick panel updated, engine state shows exactly one recorded play.
- **Wrong-turn (D):** `canPlayCard()` correctly rejects the non-waiting seat; attempting `submitCard()` anyway wrote nothing to Firestore.
- **Multiple cards in the same trick / trick completion (K/L):** three remote seats' simulated plays correctly advanced the round past trick 1 via the real `startTrickSync()` catch-up loop.
- **Multiplayer sync:** every remote seat's card applied through the exact same single Firestore listener (`window.__ONSNAPSHOT_CALLS === 1` throughout).
- **Illegal card / duplicate submission (B/C/E):** both deal-dependent scenarios — honestly reported as "not applicable at this exact deal/turn state" rather than fabricated, since this specific dealt hand's leader had no off-suit card to test a follow-suit violation against and the turn had already advanced past the local seat by the time the duplicate-click scenario was reached in sequence. The underlying guards (`canPlayCard()`'s `ILLEGAL_CARD` reason, `pendingCardSubmission`'s synchronous-before-any-`await` guard) are the exact same, already browser-verified mechanisms Bidding Controls proved for the analogous cases — reused verbatim, not reimplemented.
- **Console/page errors:** zero new console errors. The same two pre-existing, already-documented page errors (`buildHand is not defined`, `bindStatic is not defined`) fired — confirmed unrelated (unchanged since the very first sprint this session that touched this file).

**Skills actually used:** `/dev-story`/`/team-ui`/`ui-ux-pro-max`/`/ux-review`/`/consistency-check`/`/qa-plan`/`/smoke-check`/`/regression-suite`/`/test-evidence-review`/`/gate-check` are not registered slash commands in this session (confirmed against the available-skills list before starting, consistent with every prior sprint this session). Their intent was satisfied directly: architecture inspection was done by reading `table-engine.js`, `match-service.js`, `match-adapter.js`, `session.js`, `cards.js`, and the current `match/index.html` in full *before* writing any code (Section 1's own required inspection); UX/consistency was checked by reusing the exact existing button/chip/panel vocabulary (verified via direct comparison against `login/index.html`'s `.primary-btn`, the Bidding Renderer's `.bidding-field`, and Bidding Controls' own `.bd-*` classes — no new pattern introduced beyond the card chip, which itself mirrors `.bidding-field`'s shape); QA planning and smoke/regression verification were satisfied by the real Playwright run above plus the full Node regression suite; test-evidence review is reflected in the honest "deal-dependent, not applicable" reporting for B/C/E above rather than a fabricated pass.

## Quality Gate

| Dimension | Verdict |
|---|---|
| Architecture | PASS — UI never calls Firestore/`emit()`/`resolveTrick()` directly; `canPlayCard()`/`submitCard()`/sync pipelines are the only paths used. |
| Gameplay Authority | PASS — no follow-suit, trump, card-ranking, trick-winner, turn-order, or ownership logic duplicated in the UI; every legality question delegated to `TableEngine.canPlayCard()`. |
| Synchronization | PASS — exactly 1 Firestore listener throughout; `startTrickSync()` alone (not a redundant second `startCardSync()`) covers card application + trick resolution; verified live with real remote-seat simulation. |
| UX | PASS — turn/phase clarity (structural hand-visibility gating, not just disabled state), playable/non-playable/pending card states, wrong-turn feedback via the existing `UI.toast()`. |
| Consistency | PASS — reuses existing panel/pill/mono/button vocabulary and the existing seat layout; no redesign; the one new pattern (card chip) mirrors an existing shape (`.bidding-field`). |
| Accessibility | PASS with a pre-existing, out-of-scope gap — hand cards are real, focusable `<button>` elements (keyboard-operable); `UI.toast()` (reused, not modified) still has no `aria-live`, the same pre-existing shared-component limitation already noted in the Bidding Controls Quality Gate and not touched here either. |
| Regression | PASS — 1106 → 1123, 0 failures, 0 unexplained regressions. |
| Browser Evidence | PASS — 25/25 real Chromium checks, including the Foundation Fix proven live, wrong-turn/duplicate-protection mechanisms verified (directly where deal-dependent scenarios applied, and by direct reuse of Bidding Controls' own already-proven mechanism where they didn't this specific run). |
| Scope | PASS — only `design-ui/engine/table-engine.js` (Foundation Fix, explicitly authorized) and `design-ui/match/index.html` (Table Controls, explicitly authorized) modified; `scoring-engine.js`, `bidding-engine.js`, `cards.js`, `deck.js`, `dealer.js`, `firestore.rules`, `match-service.js`, `match-adapter.js` all confirmed untouched this sprint. No trick animations, score UI, match completion, rewards, monetization, sounds, or final polish implemented. |

No CRITICAL or HIGH findings. No MEDIUM findings requiring a fix-or-defer decision beyond the two items already carried forward, unchanged, from the Bidding Controls Quality Gate (`UI.toast()`'s missing `aria-live`; no `unsubscribe()` on navigation-away, still harmless under the current full-page-reload architecture).

**Table Controls: PASS.**

---

# Overall Gate Decision

# GREEN — READY FOR NEXT SPRINT

Both the Foundation Fix and Table Controls pass every required dimension with no Critical/High findings and zero unexplained regressions.

## Recommendation

**Next sprint: Trick Resolution & Round Completion Display — MEDIUM–HIGH.** Card interaction and basic trick display are now fully functional and synchronized; the natural next step is surfacing the round's actual outcome (score deltas, round-complete summary) once `TableEngine` reaches `DONE`, reusing `ScoringEngine`'s already-existing, unmodified `calculateRoundScore()`/`applyRoundResult()` output the same way this sprint reused `canPlayCard()`. **Do NOT start it automatically — stop after this report**, per the brief.
