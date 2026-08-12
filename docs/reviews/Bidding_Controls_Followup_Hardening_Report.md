# Bidding Controls Follow-up Hardening Report

**Status: COMPLETE. Do not start Table Controls automatically — this report stops here.**

## 1. Skills Used

- **`/code-review`, `/qa-plan`, `/test-evidence-review`, `/smoke-check`, `/consistency-check`** — none are registered slash commands in this session. Their intent was satisfied directly: the fix was read back line-by-line against the original Quality Gate finding before testing (code review), a deterministic test plan was designed per Finding #2's own numbered requirements before writing any test code (qa-plan), the new browser evidence was checked for whether it actually proves what it claims rather than just tallying passes (test-evidence-review), the full regression suite plus the original 30-scenario Bidding Controls suite were both re-run end-to-end (smoke-check), and the new UI-side diagnostic additions were checked against the existing `MatchScreenDebug`/diagnostic-accessor convention already used throughout this codebase for visual/pattern consistency (consistency-check).
- **`/gate-check`** — run at the end; see §7.
- **`/dev-story` / `/team-ui`** — correctly not used, per the brief's own instruction (this is a fix, not a new feature).

## 2. Finding #1 — Round Reset

**Root cause:** `renderBiddingControls()`'s candidate-selection reset key was `state.subPhase + ":" + state.waitingFor`, omitting the round number. When the same seat is first-to-act in the same subPhase across two different rounds, the key is identical between rounds, so `auctionSel`/`confirmSel`/`estimateSel` are never reset and the prior round's selected trick/suit value is shown as the new round's default.

**Exact fix:** the reset key now includes `state.round`:
```js
var key = state.round + ":" + state.subPhase + ":" + state.waitingFor;
```
No other line in `renderBiddingControls()` changed. No bidding legality, no engine/service/adapter/rules file was touched — this is a pure UI-scratch-state key change in `design-ui/match/index.html`.

**Files changed:** `design-ui/match/index.html` only (one line + doc comment).

**Test evidence:** a new, real Playwright/Chromium test (below) drove the real engine through round 1's AUCTION as p1, changed the selection away from its default (suit → Hearts, tricks → 7) via real UI clicks, then advanced to round 2 via `GameSession.setRound({number:2})` + `BiddingEngine.initState()` (both pre-existing, unmodified public APIs — the smallest faithful way to reach "a new round, same first-to-act seat" without building Table Controls, which remains explicitly out of scope) and drove back to AUCTION with p1 waiting again. Confirmed: round 2's selection is back to the fresh default (`suit !== "Hearts ♥"`, `tricks !== "7"`, matching the freshly-computed default exactly) — **2/2 assertions passed**.

## 3. Finding #2 — Double Click

**Previous evidence limitation:** the prior browser run's duplicate-submission check landed in its own non-fatal fallback branch ("no enabled control found to double-click at this state") — it never actually fired two clicks against a genuinely enabled control, so it proved nothing about the double-click race specifically, even though the code-level guarantee (the `pendingBiddingSubmission` check executing synchronously before any `await`) was already sound by direct reading.

**New browser test — genuinely exercises the real DOM handler:** reached a guaranteed-enabled `Bid` control (round 2's fresh AUCTION state from Finding #1's own setup), then dispatched **two real `.click()` calls on the SAME DOM button element reference, synchronously, in one browser task** — not Playwright's actionability-checked `locator.click()` called twice (which, on investigation, doesn't actually exercise the race at all: by the time a second `locator.click()` re-resolves the element, the first click's synchronous handler has already disabled/replaced the button, and a real browser refuses to dispatch `"click"` to a disabled element in the first place — that path would have proven native `disabled`-attribute semantics, not this file's own guard). Grabbing the element once and calling `.click()` on it twice fires the actual registered `submitBiddingIntent()` listener twice, back-to-back, before the DOM has any chance to intervene — the genuine race the guard exists to survive, and exactly the fallback the brief itself authorized ("two programmatic click dispatches in the same browser task... if that still exercises the real DOM event handler").

To make this observable without racing the fake transaction's own near-instant round-trip timing, one tiny diagnostic-only addition was made (mirrors this project's existing `MatchScreenDebug`/`getLastAppliedVersion()`-style convention exactly, does not change the guard's behavior): a `blockedDuplicateSubmissionAttempts` counter, incremented at the exact point the existing `if (pendingBiddingSubmission)` early-return fires, exposed via `MatchScreenDebug.getBlockedDuplicateSubmissionAttempts()`. The pending mechanism itself was not redesigned, no new state machine was introduced — this is a one-line counter increment inside the already-existing guard.

**Result:**
- **Actual submission count:** exactly 1 call reached the (fake) Firestore transaction layer (`window.__SUBMIT_CALLS` delta === 1).
- **Pending behavior:** the second `.click()` was genuinely intercepted by the real guard (`blockedDuplicateSubmissionAttempts >= 1`, confirmed `=== 1`).
- **Resulting writes/state:** exactly 1 new `biddingLog` entry; exactly 1 engine state transition (`auctionTop` advanced once, turn moved off `p1` exactly once).
- **All 5 of the brief's required sub-checks for this scenario passed.**

## 4. Regression

- **Baseline (start of this follow-up, actually observed):** 1106 passed, 0 failed.
- **Final (after the Finding #1 fix + Finding #2 diagnostic addition):** **1106 passed, 0 failed** — identical. No engine/service/rules test file changed, and none was expected to, since `design-ui/match/index.html` is the only file modified.
- **Original 30-scenario Bidding Controls browser suite:** re-run in full — **30/30 still passing**, confirming the round-reset key change and the new diagnostic counter introduced no regression in DASH/AUCTION/CONFIRM/ESTIMATES, wrong-turn/wrong-phase rejection, or synchronization.
- **New follow-up browser suite:** **15/15 passing** (2 for Finding #1, 5 for Finding #2, 4 regression spot-checks re-confirming wrong-turn/wrong-phase/malformed-intent/listener-count, plus 4 setup assertions).

## 5. Browser QA

- **Chromium:** `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`, headless, real DOM events throughout.
- **Scenarios executed:** round-1 AUCTION selection → round-2 AUCTION default reset (Finding #1); genuine double-click on a real, enabled `Bid` control (Finding #2); plus regression spot-checks for wrong-turn, wrong-phase, malformed-intent, and listener count.
- **Listener count:** exactly 1 Firestore listener throughout the entire run (`window.__ONSNAPSHOT_CALLS === 1`), unchanged by this follow-up.
- **Console errors:** none.
- **Page errors:** the same two pre-existing, already-documented errors (`buildHand is not defined`, `bindStatic is not defined`) — confirmed unrelated to this follow-up (unchanged from every prior sprint's own browser run in this session).

## 6. Scope

**Modified:** `design-ui/match/index.html` only — the one-line reset-key fix (Finding #1), a one-line diagnostic counter + its two increment sites + its diagnostic accessor (Finding #2's test-evidence requirement), and this report.
**Untouched (protected, confirmed by re-reading, not modified):** `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `session.js`, `cards.js`, `deck.js`, `dealer.js`, `match-service.js`, `match-adapter.js`, `firestore.rules`.
**Not expanded into:** Table Controls, cards, tricks, scoring UI, animations, sounds, monetization, redesign — none of these were touched or added.

## 7. Quality Gate

Both Quality Gate MEDIUM findings are now closed:
- **Finding #1 (round reset):** closed — fix applied, verified with 2 new passing browser assertions proving the exact before/after behavior the gate described.
- **Finding #2 (double-click evidence):** closed — the evidence gap itself is what was fixed (a genuine double-click now actually exercises the real DOM handler and is proven to result in exactly one submission), not a product-code change beyond the tiny diagnostic counter the brief's own item 5 requires ("verify the control enters pending state").

No new issues were found during this follow-up. Full regression (1106/1106) and the original Bidding Controls browser suite (30/30) both remain green.

# GREEN — READY FOR NEXT SPRINT

**Not starting Table Controls.** Per the brief: stopping after this report. No commit, no push.
