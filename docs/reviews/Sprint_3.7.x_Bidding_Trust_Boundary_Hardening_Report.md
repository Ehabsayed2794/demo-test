# Sprint 3.7.x — Bidding Trust-Boundary Hardening Report

**Status: COMPLETE. Do not start Bidding Controls automatically — this report stops here per the sprint brief.**

## 1. Verification

Before implementing anything, the 3 findings carried over from the prior `/code-review` blocker report were re-verified directly against the actual current code (not blindly trusted):

| # | Finding | Verdict | Evidence |
|---|---|---|---|
| 1 | `BiddingEngine.canSubmit(intent)` misclassifies a malformed intent as legal | **CONFIRMED** | `node -e` reproduction: `canSubmit({type:"SubmitConfirmCall", playerId:"p1"})` (correct seat, correct CONFIRM phase, missing `tricks`/`suit`) returned `{"legal":true}`. Immediately calling `emit()` with the same intent then threw `Cannot read properties of undefined (reading 'name')`. Root cause: `bidBelowWinningCall(undefined, top)` → `undefined < top` → `false`; `confirmSuitTooWeak(undefined, undefined, ...)` short-circuits on `t === state.auctionTop` before ever touching `SUITS[undefined]` — every content-rule predicate silently no-ops on `undefined` instead of rejecting it. |
| 2 | `firestore.rules`' `isValidBiddingActionEntry()` doesn't require actionType-specific fields | **CONFIRMED** | Direct read: the function validated field TYPE/RANGE only *if present*, never REQUIRED presence per `actionType`, unlike its sibling `isValidGenericBiddingAction()` in `match-service.js`, which does. |
| 3 | `match-adapter.js`'s `applyRemoteBiddingAction()` has no try/catch around `canSubmit()`/`emit()` | **CONFIRMED** | Direct read: neither call was wrapped; an engine exception during remote replay would have escaped uncaught into the Firestore `onSnapshot` callback. |

The 2 non-actionable findings from the earlier report (no bidding controls yet; "files modified without stop-and-report") remain correctly dismissed — the first is expected pre-sprint state, the second ignores the actual, separately-authorized history of every prior sprint.

## 2. Implementation

**Fix 1 — `design-ui/engine/bidding-engine.js`:** added `isMalformedBiddingIntent(intent)`, a pure structural-presence check per intent type, using only the schemas already established by `emit()`'s own inline conditions (never inventing new ones). Called inside each of `canSubmit()`'s switch cases, **after** the existing phase/turn guards (not globally before the switch — an earlier draft placed it first and was caught breaking the existing "wrong phase" test at `tests/bidding-contract.test.cjs`, since a `SubmitAuctionBid` intent submitted during DASH has no `isPass` field and must still return `"Not the Auction phase"`, not `"Malformed intent"`). A malformed intent now returns `{legal:false, reason:"Malformed intent"}`.

**Fix 2 — `firestore.rules` (+ its JS mirror in `tests/rules-simulation.test.js`):** hardened `isValidBiddingActionEntry()` with 4 new required-field clauses, appended after the existing type/range checks, mirroring `isValidGenericBiddingAction()`'s own per-type requirements exactly:
- `SubmitDashCallDecision` requires `declaredDashCall`.
- `SubmitAuctionBid` requires `isPass`; if `isPass` is not `true`, also requires `tricks` and `suit`.
- `SubmitConfirmCall` requires `tricks` and `suit`.

No turn-order logic, no gameplay calculation (bid strength, suit strength, caller cap, With floor, Forbidden-13, Dash legality) was added — purely structural shape validation, as required.

**Fix 3 — `design-ui/match-adapter.js`:** wrapped both the `canSubmit(intent)` call and the `emit(intent)` call inside `applyRemoteBiddingAction()` in their own try/catch blocks. On an exception from either, the function now returns the same structured-failure shape every other failure path in this function already uses: `{applied:false, desync:true, reason:"ENGINE_THREW", matchId, index, seatId, engineReason: e.message, appliedCount, version, results}`, and advances `lastAppliedBiddingActionCountByMatch[matchId]` only up to (never past) the failing index — the version registry is never advanced, so a future delivery re-attempts the same stuck entry. No exception is silently swallowed, no success is fabricated, and engine state is never mutated after an exception (the `emit()` wrapper's catch runs before any further state read).

`MatchService.submitBiddingAction()` was **not modified** — verification found it already calls `isValidGenericBiddingAction()`, which already enforces per-actionType required fields correctly; the gap existed only in the Firestore-rules mirror and in `canSubmit()`, never in the service layer. This matches the brief's Critical Architecture Rule: no evidence proved the service was part of the blocker, so it was left untouched.

## 3. Architecture

The trust boundary is now: **Local structural validation (MatchService) → BiddingEngine legality authority (canSubmit/emit, now hardened against malformed input) → Firestore structural validation (rules, now requiring per-type fields) → Safe remote replay (MatchAdapter, now never lets an engine exception escape uncaught)**. Every layer independently checks; none trusts the others blindly — "neither layer trusts the other alone" continues to hold, now with the one previously-unguarded seam (a malformed intent reaching `canSubmit()`/`emit()` directly, bypassing `MatchService`'s own validation via a hand-crafted Firestore write or a future bug elsewhere) closed. Gameplay rules remain exclusively engine-owned: none of the three fixes added, changed, or duplicated any bid-strength, suit-strength, caller-cap, With-floor, Forbidden-13, or turn-order logic — Fix 1 and Fix 2 are both pure "is this shaped correctly?" checks, and Fix 3 only changes how an *unexpected* exception is reported, never what is legal.

## 4. Tests

- **Baseline actually observed before this sprint's edits:** 1090 passing (confirmed via a full run across all 17 test files before Fix 1 began).
- **Focused runs after each fix, before regression:**
  - `tests/bidding-contract.test.cjs`: 88 → **94** passed (0 failed) — added 6 new hardening assertions (scenarios A–E from the brief, plus a "pass must remain legal" non-over-rejection check).
  - `tests/bidding-action-sync.test.cjs`: 29 → **33** passed (0 failed) — added 4 new assertions (scenario group "O", covering K/L/M: a malformed-but-shape-valid remote `SubmitConfirmCall` entry does not throw, returns a structured desync, and leaves engine state unmutated).
  - `tests/rules-simulation.test.js`: 177 → **183** passed (0 failed) — added 6 new required-field assertions for the hardened `isValidBiddingActionEntry()`.
- **Full regression suite, final:** **1106 passed, 0 failed** across all 17 test files (1090 + 16 new assertions; 0 regressions).
- **Scenario coverage against the brief's lettered list (A–Q):** A–E (missing required fields per intent type) — new dedicated assertions in `bidding-contract.test.cjs`; F–J (null/undefined/missing-type/missing-playerId/unknown-actionType) — already covered by pre-existing assertions in the same file, re-verified passing; K/L/M (malformed Firestore entry replay doesn't throw / returns structured failure) — new assertions in `bidding-action-sync.test.cjs`; N (well-formed legal stays legal) — proven by every pre-existing legal-path assertion in both files still passing unchanged; O (well-formed illegal stays illegal) — proven by every pre-existing illegal-path assertion (wrong turn, wrong phase, over-cap, etc.) still passing unchanged; P (88 pre-existing bidding-contract assertions) — confirmed still passing, now 94 with the 6 additions; Q (bidding synchronization tests) — confirmed still passing, now 33 with the 4 additions.
- **BEFORE result === AFTER result, proven:** every pre-existing assertion in `tests/bidding-contract.test.cjs`, `tests/bidding-action-sync.test.cjs`, and `tests/rules-simulation.test.js` still passes with its original expected value — no existing test was weakened, altered, or removed to accommodate the fixes.

## 5. Browser QA

Real Playwright + headless Chromium (`/opt/pw-browsers/chromium-1194/chrome-linux/chrome`), same harness pattern as every prior sprint's verification (local static server, `page.route()` interception of the 3 Firebase CDN URLs with a hand-written fake `window.firebase`, `sessionStorage` seeded via `addInitScript()`):

1. **Valid bidding action still works:** a real `BiddingEngine.emit({type:"SubmitDashCallDecision", ...})` call correctly advanced the engine (`Decided 0/4 → 1/4`, turn moved to the next seat). ✅
2. **Renderer still updates:** `renderBidding()`/`renderSeats()` re-render reflected the new state immediately after. ✅
3. **A malformed remote action does not crash the page:** drove the real engine to CONFIRM, then delivered a simulated Firestore snapshot (via the page's actual registered `onSnapshot` callback) whose `biddingLog` contained a `SubmitConfirmCall` entry missing `tricks`/`suit` — the exact pre-fix crash shape. The callback returned normally (`threw: false`), the page remained fully alive (`document.body` and `#biddingPanel` both still present), and `BiddingEngine`'s own state was left at `CONFIRM`, unmutated. ✅
4. **No new console errors:** zero `console.error`-level messages were emitted. Two **pre-existing, already-documented** `pageerror`s (`buildHand is not defined`, `bindStatic is not defined`) did fire — these come from `bidding-engine.js`'s/`table-engine.js`'s own `DOMContentLoaded` listeners calling UI hooks that were never implemented in this repo (explicitly documented in `design-ui/match/index.html`'s own comments, citing `docs/reviews/MatchFlowIntegration_3.6.md`, as a known condition predating this sprint and explicitly out of scope to fix). Confirmed unrelated to this sprint's changes by inspecting that documentation directly; not a regression. ✅ (no *new* errors)
5. **Exactly one Firestore listener remains:** `window.__onSnapshotCallCount === 1`. ✅

## 6. Scope

**Modified:** `design-ui/engine/bidding-engine.js`, `firestore.rules`, `design-ui/match-adapter.js`, `tests/bidding-contract.test.cjs`, `tests/bidding-action-sync.test.cjs`, `tests/rules-simulation.test.js`, this report.
**Untouched (per the brief's explicit list):** `design-ui/engine/table-engine.js`, `design-ui/engine/scoring-engine.js`, `design-ui/engine/session.js`, `design-ui/engine/cards.js`, `design-ui/engine/deck.js`, `design-ui/engine/dealer.js`, `design-ui/match/index.html`, `design-ui/match-service.js`.
**Deviations from the brief:** none. No Bidding Controls implementation, no UI redesign, no Claude Design work was performed this sprint.

## 7. Risk

- **Remaining limitation:** `firestore.rules`' structural check (Fix 2), like all Firestore Rules, still cannot verify that every *earlier* `biddingLog` entry is unchanged — only that a new entry is shape-valid and the log has grown. This is a pre-existing, already-documented limitation (not introduced or worsened by this sprint).
- **Technical debt:** none introduced. `isMalformedBiddingIntent()` duplicates the *field-presence* shape already implicit in `emit()`'s own destructuring, but per the brief this was intentional (reusing existing schemas, not inventing new validation) rather than a new source of drift — if `emit()`'s own field requirements change in the future, this function will need a matching update, exactly as any of `emit()`'s existing inline conditions already would.

## 8. Recommendation

**Next sprint: Bidding Controls — HIGH.** The trust boundary is now hardened across all three layers, all three original findings are fixed and verified, 0 regressions, and browser QA confirms the malformed-replay path is now safe. Per the brief: **do NOT start it automatically — stop after this report** and wait for explicit authorization.
