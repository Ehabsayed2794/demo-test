# Match Flow Integration — Sprint 3.6 Integration Report

**Scope actually implemented:** end-to-end integration of the existing engine files for ONE complete round — `Deck → Dealer → GameSession → BiddingEngine → TableEngine (card play, trick resolution) → ScoringEngine`. No game rule, bidding rule, scoring formula, or AI heuristic was changed. No UI file was touched. No multiplayer/Firestore code was added. The only source changes are the minimum wiring described in §2 below.

## 1. Executive Summary

The pipeline is now genuinely integrated and testable end-to-end, proven by 296 real, executed automated tests across the full test suite (156 new for this sprint's primary integration path, 12 more for a dedicated bug-reproduction test, 128 pre-existing and re-verified with zero regression).

Getting there required two things beyond "just calling the existing files":
1. **A minimum, additive export change** to `bidding-engine.js` and `table-engine.js` (§2) — as delivered, neither file exposed *any* way to reach its own logic from outside a browser page; this was an unavoidable prerequisite for "add automated integration tests" to be possible at all.
2. **A discovered, real, blocking scoring bug** (§3) in the interaction between `bidding-engine.js`'s `extractEstimates()` and `table-engine.js`'s `resolveTrick()` bids-reconstruction — a legal, ordinary outcome (a player's final estimate being exactly 0 tricks, a "Normal Dash") silently corrupts that player's score into `NaN`. Per the brief's explicit instruction, **this was not fixed** — it is documented here, reproduced by a dedicated permanent test, and left for a future sprint's decision.

Everything else in the pipeline — dealing, hand storage, bidding logic (Dash Call, Auction, Confirmation, Final Estimates, With/Wazz, Auction Alignment, Estimation Jump-In), card play, follow-suit enforcement, trick resolution, and scoring for every path that doesn't hit the bug above — was verified working exactly as already implemented, with zero code changes.

## 2. The Minimum Wiring Change (and why it was unavoidable)

`bidding-engine.js` and `table-engine.js` are classic (non-module) scripts: every function and the module's own working `state` variable live at plain top-level scope, with **no export of any kind** — no `module.exports`, no `global.BiddingEngine = {...}`, nothing. Both files end with:

```js
window.addEventListener("DOMContentLoaded", () => {
  GameState.sync(GameState.STATES.BIDDING);   // or GAMEPLAY
  initState();
  buildHand();      // or bindStatic() only, for table-engine.js
  bindStatic();
  advance();
});
```

`render`, `buildHand`, `bindStatic`, `showDone`/`showRoundDone`/`showEscalationBanner`/`flashReject`/`sweepThenResolve`, and every `document.getElementById`/`document.querySelector` call are referenced but **never defined in either file** — they were evidently meant to be defined in each file's paired HTML screen's own inline script. Sprint 3.4.5's audit already established that no such HTML screen exists anywhere in this repository.

**Consequence:** as delivered, there was no way to call `initState()`/`emit()` from a test, or from anything outside the file itself. This is not a rules problem — it's a structural integration blocker that directly prevents this sprint's own explicit deliverable ("add automated integration tests covering a complete match").

**The change made (identical shape in both files):** one small object literal appended at the very end of each file, after the existing `DOMContentLoaded` block — nothing above that line was touched:

```js
// bidding-engine.js
window.BiddingEngine = {
  initState: initState,
  emit: emit,
  getState: function () { return state; }
};

// table-engine.js
window.TableEngine = {
  initState: initState,
  emit: emit,
  resolveTrick: resolveTrick,
  getState: function () { return state; }
};
```

This is the same class of change (and the same discipline) already applied to `dealer.js` in Sprint 3.5 — an additive export, zero logic changes. Real browser behavior is completely unaffected: the `DOMContentLoaded` handler still exists, still calls `initState()`/`buildHand()`/`bindStatic()`/`advance()` exactly as before, on a real page with its paired inline script defining the render-side functions. Integration tests instead call `initState()`/`emit()`/`resolveTrick()` directly and never call `advance()` — so the still-undefined `render`/`buildHand`/`bindStatic`/`showDone`/etc. are simply never invoked by a test, and needed no stub or shim of any kind, in the source or in the test harness.

**One additional, source-file-free adjustment was needed in the test harness only** (not in either engine file): both files' `DOMContentLoaded` registration line executes at `require()`-time (it's top-level code, not deferred), and calls `window.addEventListener(...)`. In a Node process, `window` (shimmed as `global`) has no real `addEventListener`, so `require()`-ing either file would throw immediately without this. The tests provide a one-line no-op (`global.window.addEventListener = function () {};`) before requiring these two files — the same class of environment shim this project's tests have always used (e.g. `global.firebase = {...}` in the Firestore-facing test suites), touching no source file.

### A related, discovered-but-not-fixed architectural note

Both `bidding-engine.js` and `table-engine.js` independently declare their own top-level `const SUITS`/`const RANKS` (byte-identical values to each other and structurally identical to `cards.js`'s `Cards.SUITS`/`Cards.RANKS`, but three separate copies — already flagged in `docs/architecture/GameEngine.md`/Sprint 3.5's own notes). As **classic** (non-module) `<script>` tags, if both files were ever loaded on the same real HTML page, the second `<script>` tag's `const SUITS = ...` would throw `SyntaxError: Identifier 'SUITS' has already been declared` — a real, latent browser-side collision. This has never manifested because (per Sprint 3.4.5's audit, still true) no HTML page in this repository loads either file at all. Fixing this would mean wrapping both files in their own IIFEs — a larger footprint than "minimum wiring" for a sprint whose own Engine Boundaries explicitly ask for the smallest possible touch to these two files. **Documented, not fixed** — worth resolving before any future sprint ever loads both scripts on one real page.

`bidding-engine.js`'s and `table-engine.js`'s `PLAYERS`/`TURN_ORDER` (and `table-engine.js`'s `ROUND_CFG`) are computed **once, at `require()`/script-load time**, from a single snapshot of `GameSession`'s state at that instant. This means, within one Node process, each file can only correctly drive **one round** — a second `require()` of the same path returns Node's cached module without re-running that top-level computation, so `ROUND_CFG` would stay frozen on the first round's values. This matches exactly how these files were built to be used in a real browser (one page load per round, with a full page navigation between rounds) — it is not a new limitation, just a newly-confirmed one. This sprint's tests each use a **fresh process per round** (two separate test files, each its own `node` invocation), which is the correct, honest way to test this today. **Multi-round match integration** (Sprint 3.7+, if pursued) would need to either accept "one process per round" as the real design, or refactor these files' top-level constants into re-callable functions — a design decision, not something this sprint should invent a fix for.

## 3. Discovered Bug: Normal Dash (0-estimate) Corrupts Scoring — NOT FIXED

**Found while writing the primary integration test** — not a stale or previously-known issue, a fresh discovery from actually running the pipeline end-to-end for the first time.

**Root cause, precisely:**
- `bidding-engine.js`'s `SubmitFinalEstimate` handler correctly records a final estimate of exactly 0 tricks as `{ type: "DASH", amount: 0 }` (a legal, ordinary "Normal Dash" — not a pre-bidding Dash Call).
- `extractEstimates()` — the function whose output `GameSession.completeBidding()` persists into `GameSession.round.estimates` — only carries **`TRICKS`-type** bids:
  ```js
  function extractEstimates(sparseBids) {
    const out = {};
    Object.keys(sparseBids).forEach(id => { if (sparseBids[id].type === "TRICKS") out[id] = sparseBids[id].amount; });
    return out;
  }
  ```
  A Normal Dash is silently **dropped** — `GameSession.round.estimates` cannot distinguish "this player estimated 0" from "this player never estimated at all"; both are simply absent from the map.
- `table-engine.js`'s `resolveTrick()` reconstructs the `bids` map for `ScoringEngine.calculateRoundScore()` **from that same lossy `estimates` map**:
  ```js
  bids[id] = dashCallers.includes(id)
    ? { type: "DASHCALL", amount: 0 }
    : { type: state.estimates[id] === 0 ? "DASH" : "TRICKS", amount: state.estimates[id] };
  ```
  For the affected player, `state.estimates[id]` is `undefined`. `undefined === 0` is `false`, so this line **misclassifies** the player as `{ type: "TRICKS", amount: undefined }` instead of the real `{ type: "DASH", amount: 0 }`.
- `ScoringEngine.calculateRoundScore()` then does arithmetic with `bid.amount` (`undefined`) in the `TRICKS` branch (`10 + bid.amount` on success, `-Math.abs(T - bid.amount)` on failure) — either path produces `NaN`. **This is not a ScoringEngine bug** — it computes exactly the (wrong) thing a correct implementation would for the corrupted input it's handed. The defect is entirely upstream, in the `bidding-engine.js` ↔ `table-engine.js` handoff.

**A second, distinct masking effect (same root cause, different layer):** `ScoringEngine.applyRoundResult()` accumulates match totals via `(current[id] || 0) + (result.deltas[id] || 0)`. Since `NaN` is falsy in JavaScript, that `|| 0` silently turns the visible `NaN` back into a plain `0` the instant it reaches `GameSession.getMatchScores()`. The corruption is visible in the round's own `_scoreResult.deltas`, but **invisible** in the running match total — which ends up looking like an entirely ordinary "scored 0 this round" instead of a broken calculation. Arguably worse than an overt `NaN`, since nothing about it looks wrong.

**A third property, confirmed by testing:** this bug is **outcome-dependent, not merely value-dependent**. `ScoringEngine.calculateRoundScore()` short-circuits to a flat 0-for-everyone Sa'ayda result whenever every player's bid fails (`successCount === 0`) — a Sa'ayda round never reaches the `TRICKS`-branch arithmetic this bug lives in at all, so an unlucky (or lucky, depending on perspective) random deal can fully mask the bug's presence on any given round. `tests/match-flow-normal-dash-scoring-bug.test.cjs` uses a fixed, seeded PRNG (substituted for `Math.random` only for the duration of the deal, then restored — a test-harness-only technique, no source file touched) specifically so its reproduction is 100%, deterministically repeatable rather than a coin flip.

**Why this was not fixed this sprint:** per the brief — "Do not rewrite ScoringEngine unless a documented bug blocks integration" (this bug isn't in ScoringEngine) and the Engine Boundaries section's "if anything else blocks [integration], stop and document it instead of redesigning" (fixing this requires a logic change, not a wiring change, in one or both of `bidding-engine.js`/`table-engine.js` — files this sprint was told to touch only for "absolutely unavoidable" reasons). This is squarely a "stop and document" situation, not a "small unavoidable fix" one.

**How this sprint's primary integration test avoids it:** the scripted bidding scenario in `tests/match-flow-integration.test.cjs` deliberately ensures no player's final estimate is exactly 0 — this is explicit, commented, and asserted (`check("No estimate in this scripted round is exactly 0 (deliberately avoiding the known Normal-Dash bug)", ...)`), not a silent workaround.

**Recommended fix (for a future sprint's decision, not implemented here):** the cleanest fix is almost certainly in `table-engine.js`'s `resolveTrick()` bids-reconstruction — rather than reconstructing bid *type* by testing `state.estimates[id] === 0` (which cannot distinguish absent from zero), it should check `Object.prototype.hasOwnProperty.call(state.estimates, id)` to detect "estimated 0" vs. "never estimated," OR (more robustly) `bidding-engine.js`'s `completeBidding()` call should pass a bids/estimates shape that doesn't lose type information in the first place — e.g. persisting the full `{type, amount}` per seat into `GameSession.round`, not just a bare `amount`-only `estimates` map. Either change touches exactly one of the two protected files' actual logic, which is why this sprint didn't make it unilaterally.

## 4. Verified Working, Unchanged (no bug found)

All of the following were exercised by real, executed tests this sprint and found to already work correctly, with zero source changes beyond §2's wiring:

- **Deck → Dealer → GameSession:** every round deals exactly 52 unique cards via the real `Cards`/`Deck`/`Dealer` chain (Sprint 3.5); `GameSession.ensureHandsDealt()` correctly reuses the same deal across the Bidding and Table phases (the "Card Engine centralization" the code comments describe) rather than re-dealing.
- **Bidding phase:** Dash Call decline path, Auction (bid/pass/beat-top/With-via-exact-match), Auction Alignment (suit-match granting With after the fact), Confirmation (keep the winning call), Final Estimates (cap enforcement, forbidden-13 check), and the transition into `GameSession.completeBidding()` all produced correct, self-consistent state through a real scripted round.
- **Card play / trick resolution:** turn order is preserved CCW around the table for every play; follow-suit is genuinely *enforced* by the engine (confirmed via a deliberate illegal-play attempt that was rejected, not merely "happened to be avoided" by the test's own card choices); every one of 13 tricks' recorded winner was independently cross-checked against a from-scratch recomputation of the same recorded plays; exactly 52 cards were played across the round with no repeats; all four hands correctly reach zero cards.
- **Scoring (non-Normal-Dash paths):** `ScoringEngine.calculateRoundScore()`'s result for the scripted scenario was independently re-derived from the same reconstructed inputs `table-engine.js` used internally and found to match exactly; `GameSession.getMatchScores()` and `GameSession.getLastRoundResult()` were both correctly updated as a direct, automatic side effect of the 13th trick's `resolveTrick()` call — no separate scoring call was needed from the test, confirming the existing `resolveTrick() → ScoringEngine.calculateRoundScore() → applyRoundResult() → GameSession.completeRound()` chain is already correctly wired internally.
- **Trump rules:** trump-suit strength comparison (`SANS` > `SPADES` > `HEARTS` > `DIAMONDS` > `CLUBS`) was exercised via the independent trick-winner cross-check on every trick. Direct verification against `Estimation_Rules_v2_SingleSourceOfTruth.docx` itself remains impossible — that file does not exist anywhere in this repository (confirmed again this sprint, matching Sprint 3.4.5's own finding); this report relies on the engine's own extensively rules-section-cited code comments (e.g. "rules §2.2.1a," "rules §2.3," "rules §4") as the best available reference, same honest limitation as every prior sprint that touched this area.

## 5. Files Changed

| File | Change |
|---|---|
| `design-ui/engine/bidding-engine.js` | One additive export object appended at the end of the file. No logic above it touched. |
| `design-ui/engine/table-engine.js` | One additive export object appended at the end of the file. No logic above it touched. |
| `design-ui/engine/scoring-engine.js` | **Not touched.** |
| `design-ui/engine/session.js`, `design-ui/lobby/session.js` (`GameSession`) | **Not touched.** |
| `design-ui/engine/cards.js`, `design-ui/engine/deck.js`, `design-ui/engine/dealer.js` | **Not touched** (Sprint 3.5's work, re-verified with zero regression). |
| Any `game-state.js` copy (`GameState`) | **Not touched.** |
| Any Firestore/Services/UI file | **Not touched** — no multiplayer/Firestore synchronization was added, per the brief. |

## 6. Test Report

See `TEST_CHECKLIST.md` in the QA package for the itemized list. Summary:

- `tests/match-flow-integration.test.cjs` (new) — **156 checks, all passing**, across 8+ repeated runs against fresh random deals (no flakiness observed): full-round pipeline integration, 52-card dealing, turn order, follow-suit enforcement (including one deliberate illegal-play rejection), all 13 tricks independently cross-checked, scoring consistency.
- `tests/match-flow-normal-dash-scoring-bug.test.cjs` (new) — **12 checks, all passing**, deterministic across repeated runs (fixed-seed PRNG): reproduces and precisely isolates the discovered bug (§3). Every check in this file is *expected* to pass — a pass confirms the bug is real and correctly scoped, matching this project's established "confirms the documented limitation, not a failure" precedent (e.g. Sprint 3.3's `creator` self-promotion gap).
- `tests/deck.test.cjs` (39), `tests/match-service.test.cjs` (59), `tests/room-service.test.cjs` (31), `tests/rules-simulation.test.js` (61) — all pre-existing, all re-run, **zero regression** (296 tests total across the whole suite).

## 7. Recommended Next Sprint

1. **Decide and implement the fix for §3's Normal Dash scoring bug.** This is the single highest-priority follow-up — it silently corrupts real match scores for a completely ordinary, legal player choice.
2. **Decide whether/how to support multiple rounds within one process** (§2's "one round per require()" architectural note) before attempting a full 18-round match integration.
3. **Decide whether to wrap `bidding-engine.js`/`table-engine.js` in IIFEs** to close the latent classic-script `SUITS`/`RANKS` collision (§2) — needed before both can ever be loaded on the same real HTML page.
4. Continue deferring: bidding/scoring rule changes beyond the one documented bug, AI improvements, UI wiring, and multiplayer/Firestore integration — all explicitly out of this sprint's scope and not attempted.

## 8. GO / NO-GO

**Engine is executable end-to-end for one complete round. One real, blocking scoring bug was discovered, reproduced, and documented — not fixed, per the brief's explicit instruction.**

Recommendation: proceed to plan the fix for §3 as its own small, targeted follow-up (touching only `table-engine.js`'s bids-reconstruction, or `bidding-engine.js`'s `extractEstimates`/`completeBidding` call, per §3's recommended fix) before any further gameplay-pipeline work builds on top of scoring.
