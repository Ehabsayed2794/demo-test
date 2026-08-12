# Post-Bidding Controls Quality Gate

**No code was modified during this review. No commit. No push.**

## 1. Skills Used

- **`/code-review`, `/ux-review`, `/consistency-check`, `/test-evidence-review`, `/scope-check`, `/gate-check`** — none of these are registered slash commands in this session (confirmed against the available-skills list). Per this same session's own established practice, their intent was satisfied directly: a fresh, line-level read of the actual current `design-ui/match/index.html` (not a re-summary of the prior sprint report), cross-checked against `match-service.js`/`match-adapter.js`/`bidding-engine.js`'s real current behavior, plus re-inspection of the Playwright test transcript's actual pass/fail detail (not just the final "30/30" tally).

## 2. Architecture Verdict

**PASS.**

- `design-ui/match/index.html` contains zero references to `firebase.firestore`, `db()`, `.collection(`, or `.doc(` — every write goes through `MatchService.submitBiddingAction()`/`submitBid()`. Confirmed by direct grep of the file: the only Firestore-adjacent identifiers present are `window.MatchService`/`window.MatchAdapter` calls.
- `BiddingEngine.emit(` does not appear anywhere in the interaction layer. `canSubmit(` appears in `submitBiddingIntent()`, `submitFinalEstimate()`, and each phase's own candidate-legality check (`renderAuctionControls()`/`renderConfirmControls()`/`renderEstimatesControls()`) — always read-only, never followed by a mutating call.
- No bid-strength, suit-strength, caller-cap, With-floor, Forbidden-13, or turn/phase formula is reimplemented in the UI file — every one of those questions is answered by asking `canSubmit()` with a candidate intent, confirmed by reading each control renderer in full.
- `MatchService` remains the sole submission authority (`submitBiddingAction`/`submitBid`), `MatchAdapter` remains the sole synchronization authority (`startBiddingActionSync`/`startBidSync`/`applyRemoteBiddingAction`/`applyRemoteBid`, all unmodified), `BiddingEngine` remains the sole legality authority (`canSubmit`/`emit`, unmodified this sprint).

## 3. Bidding Controls Verdict

**PASS**, with one **LOW**-severity behavioral note (see §10).

- **Turn ownership:** `renderBiddingControls()` renders nothing at all unless `state.waitingFor === localSeatId` — enforced structurally (an early `return`), not merely via disabled buttons. Confirmed by reading the function directly.
- **Phase ownership:** dispatch is a plain lookup table (`BIDDING_CONTROL_RENDERERS`), keyed by `state.subPhase`; an unrecognized subPhase renders nothing (falls through silently, consistent with the read-only renderer's own equivalent handling one line above it).
- **Disabled/pending states:** every button reads `pendingBiddingSubmission` for its `disabled` attribute; `submitBiddingIntent()`/`submitFinalEstimate()` re-render immediately after setting the flag, before the network call resolves — confirmed the flag is set and the disabling re-render happens synchronously, before any `await`.
- **Duplicate protection:** `if (pendingBiddingSubmission) return;` is the very first line of both submission functions, executed synchronously on click, before `canSubmit()` or any promise is created — a second click in the same tick or before the promise settles is a structural no-op. (Browser-level live-double-click evidence gap noted in §8/§10 — the code guarantee itself is sound.)
- **Error feedback:** `describeSubmissionError()` maps `STALE_GAME_STATE`/`UNAUTHENTICATED`/`PERMISSION_DENIED`/`UNAVAILABLE`/`ENGINE_UNAVAILABLE`/`MATCH_ADAPTER_UNAVAILABLE` to short human messages; every other code (including `ILLEGAL_BIDDING_ACTION`) falls through to the raw `err.message`, which already embeds the real engine's own rejection reason verbatim — correctly "use the engine's existing reason, never invent an alternate explanation."
- **Stale-state behavior:** a candidate becoming illegal mid-selection degrades to a disabled button + hint text (re-evaluated via `canSubmit()` on every render) rather than a stale-looking enabled control.
- **Synchronization behavior:** verified in the browser run — a remote seat's action reaches this client's engine and re-renders correctly without any local action being taken.

## 4. Trust Boundary Verdict

**PASS. Sprint 3.7.x hardening is not weakened.**

- `submitBiddingIntent()`/`submitFinalEstimate()` call `canSubmit()` fresh, immediately before submission — they rely on, and never bypass, the hardened `isMalformedBiddingIntent()` checks added in `bidding-engine.js`.
- Verified live: `canSubmit({type:"SubmitConfirmCall", playerId:"p1"})` (missing `tricks`/`suit`) still returns `{legal:false, reason:"Malformed intent"}` after this sprint's changes — re-confirmed in the browser run.
- No new validation logic was added anywhere that could drift from or duplicate `firestore.rules`' hardened `isValidBiddingActionEntry()` or `match-adapter.js`'s try/catch-wrapped `applyRemoteBiddingAction()` — this sprint touched none of those three files.

## 5. Synchronization Verdict

**PASS.**

- **Listener count: exactly 1** real Firestore `onSnapshot` registration for the match document, confirmed throughout the entire browser run (`window.__ONSNAPSHOT_CALLS === 1`) even with `startBiddingActionSync()`, `startBidSync()`, and the render subscription all active — `MatchService.subscribeToMatch()`'s existing ref-counting is what makes this true; this sprint did not alter that mechanism, only called it three times for the same `matchId`.
- **Ordering:** `startBiddingActionSync()`/`startBidSync()` are registered in `match/index.html` *before* the render-owning `subscribeToMatch()` call, so on every snapshot delivery the engine-mutating callbacks run before the render callback — confirmed by reading `MatchService.attachListener()`'s dispatch (`entry.listeners.slice().forEach(...)`, invoked in registration order) and by the browser evidence itself (every click's resulting state change was visible in the very next render pass, never one render behind).
- **No duplicate action application:** `applyRemoteBiddingAction()`'s own version/count gates (unmodified) are what prevent replay — this sprint's only interaction with them is causing them to be *exercised* by finally calling `startBiddingActionSync()`, not altering their logic.
- **No double rendering:** only one of the three registered callbacks (the render one) ever calls `renderBidding()`/`renderSeats()`; the other two only mutate engine state.
- **Local/remote echo:** not newly at risk — `applyRemoteBiddingAction()`'s existing `ALREADY_APPLIED_LOCALLY` handling (Sprint 3.7) is unchanged, and this sprint's own UI never calls `emit()` locally, so there is no local pre-emit to echo against in the first place — actually a *simpler* case than the echo scenario that code was originally built for.
- **Memory leaks:** none identified. `match/index.html` is a distinct multi-page document (not an SPA route) — leaving it for Lobby is a full page unload that destroys all listeners; the module's top-level registration code runs exactly once per page load. No unsubscribe is called for `startBiddingActionSync()`/`startBidSync()` on navigation-away, which would matter if this ever became an SPA-style in-page transition — noted as a forward-looking observation only, not a current defect (see §10, LOW).

## 6. UX Verdict

- **MEDIUM** — Stale candidate selection can persist across rounds. `renderAuctionControls()`/`renderConfirmControls()`/`renderEstimatesControls()` reset their scratch selection (`auctionSel`/`confirmSel`/`estimateSel`) only when the `subPhase:waitingFor` key changes. If the *same* seat is first-to-act in the *same* subPhase across two different rounds (e.g., the same dealer/opener two rounds in a row), the key is identical and the prior round's selected trick/suit value carries over as the new default — never an illegal submission (still re-validated live), but a potentially confusing stale default value shown to the player.
- **LOW** — `UI.toast()` (reused, not modified) has no `aria-live` region, so a rejection/error message is not announced to assistive tech — a pre-existing shared-component gap, out of this sprint's authorized scope to fix.
- **LOW** — AUCTION/CONFIRM present a suit-chip row *and* a separate stepper simultaneously; this is appropriately minimal per the brief's own "do not expose every combination" instruction, but does mean a player must adjust two controls to find a specific legal raise rather than seeing a filtered list of legal options — acceptable trade-off, not a defect.
- No CRITICAL or HIGH UX findings. Turn/phase clarity, disabled-state clarity, and click-target sizing (buttons/chips sized consistently with the existing `.primary-btn`/`.google-btn` precedent) are all sound.

## 7. Visual Consistency Verdict

**PASS**, no CRITICAL/HIGH findings.

- Buttons reuse the exact gradient-accent (`.bd-btn-primary` ≈ `login/index.html`'s `.primary-btn`) and pill-bordered-secondary (`.bd-btn` ≈ `.google-btn`) treatments — same radius, same font stack, same hover/active transitions.
- Typography/spacing tokens (`--accent`, `--panel`, `--panel-line`, `--pill`, `--mono`, `"Saira"`) are all pre-existing; no new color or font was introduced.
- **LOW** — `.bd-suit-chip` is a genuinely new selectable-chip pattern (Lobby's `.rank-chip`/`.badge` are display-only, not selectable, so this isn't a duplicate of an existing interactive component) — styled consistently with the existing token system, but is the one net-new visual pattern this sprint introduced. Worth knowing about if a future Table Controls sprint needs a similar picker, so it reuses `.bd-suit-chip` rather than inventing a third variant.
- No inconsistent modal/toast behavior — `UI.toast()`/`UI.openModal()` are reused exactly as-is, no second notification system was created.

## 8. Test Evidence Verdict

**Sufficient for a PASS, with one identified gap.**

The 30 browser checks do provide concrete evidence, not just a pass count, for: local valid action (Scenarios A–D, each with a real click through the real service call), local invalid action (illegal-candidate hints/disabled buttons plus a direct illegal `submitBiddingAction()` attempt confirmed to write nothing), wrong turn (a direct `canSubmit()` check for the non-waiting seat, plus the illegal-submission-writes-nothing check), wrong phase (implicitly covered by the DASH→AUCTION→CONFIRM→ESTIMATES phase-gated dispatch itself; not separately isolated as its own assertion), synchronization/remote application (the 3-seat pass-drive in AUCTION applied via simulated remote writes through the one shared listener), renderer update (panel snapshots before/after every real click), and Firestore write behavior + listener count (both directly asserted).

**Identified gap:** the one **duplicate-submission** check in the live browser run did not exercise a genuine double-click — it took the harness's own non-fatal fallback branch ("no enabled control found to double-click at this state") rather than actually firing two near-simultaneous clicks against an enabled button and confirming exactly one write resulted. The structural code guarantee (§3/§10) is sound and was verified by direct code reading, but **browser-level evidence specifically for the double-click race is incomplete**, not merely repeated as "30/30 passed" without qualification.

## 9. Scope Verdict

**PASS. Scope was respected.**

- Only `design-ui/match/index.html` was modified. `git diff --stat` for this sprint's actual change confirms no other file changed as part of Bidding Controls (the pre-existing uncommitted diffs in `table-engine.js`/`match-service.js` visible in `git status` predate this sprint — from the Sprint 3.6.1 IIFE fix and Sprint 3.7/3.8 bidding-synchronization work respectively — and were not touched again this sprint).
- No Table Controls, card rendering, trick system, score system, monetization, or sound code exists anywhere in the diff.
- Enabling `startBiddingActionSync()`/`startBidSync()` was genuinely required, not scope creep: without them, the "→ Firestore → MatchAdapter → BiddingEngine → Renderer Update" half of the brief's own required architecture would not function at all — a submitted action would write to Firestore but never reach the local engine, since nothing was listening for it. This is calling an existing, unmodified public API, not new architecture.

## 10. Issues

**CRITICAL:** none.

**HIGH:** none.

**MEDIUM:**
1. **Issue:** Stale candidate-selection default can carry over across rounds when the same seat is first-to-act in the same subPhase twice in a row.
   **Affected file:** `design-ui/match/index.html` (`renderAuctionControls()`/`renderConfirmControls()`/`renderEstimatesControls()`, the `lastControlsKey` reset condition).
   **Evidence:** direct code reading — the reset key is `subPhase + ":" + waitingFor`, with no round number included.
   **Smallest fix:** include `state.round` in the reset key (e.g., `state.round + ":" + state.subPhase + ":" + state.waitingFor`).
   **Effort:** trivial (one-line change to the key computation, times 1 shared helper if extracted).
   **Regression risk:** none — this only affects which stale value a stepper defaults to on first render of a turn; every submission is still independently re-validated via `canSubmit()`.
2. **Issue:** The live duplicate-submission browser check did not exercise a genuine double-click.
   **Affected file:** test evidence only (no product file) — `verify-bidding-controls.cjs` (scratchpad, not part of the repo).
   **Evidence:** §8 above; the check's own label states it took the non-fatal fallback path.
   **Smallest fix:** re-run the double-click assertion at a state where the target seat's control is guaranteed enabled (e.g., immediately after Scenario A's fresh DASH state, before driving away from it) rather than reusing whatever phase remains at the end of the whole sequence.
   **Effort:** small (test-harness-only change; no product code).
   **Regression risk:** none.

**LOW:**
1. `UI.toast()` has no `aria-live` — pre-existing shared-component gap, not introduced by or in scope for this sprint.
2. No `unsubscribe()` call for `startBiddingActionSync()`/`startBidSync()` on navigation-away — currently harmless (full-page-unload architecture), but worth revisiting if match/index.html ever becomes part of an SPA-style route.
3. `.bd-suit-chip` is a new interactive pattern (not a duplicate of an existing one) — worth reusing, not reinventing, in a future Table Controls sprint if a similar picker is needed.

## 11. Gate Decision

# YELLOW — READY WITH REQUIRED FOLLOW-UP

No CRITICAL or HIGH issues exist, and the architecture, trust boundary, and synchronization are all sound and correctly verified. The two MEDIUM findings are both small, well-scoped, and low-risk, but per this gate's own instruction ("YELLOW means the project can proceed only after clearly identified follow-up work") they should be closed — or explicitly deferred by name — before layering Table Controls on top of this same interaction pattern, since Table Controls will likely reuse the same `subPhase:waitingFor`-keyed reset pattern and the same double-click-protection design; fixing/verifying them now is cheaper than propagating the same gap into a second control family.

## 12. Next Sprint Recommendation

If the two MEDIUM follow-ups are closed (or explicitly deferred by name with the user's sign-off): **Table Controls (card selection/play) — HIGH**, reusing `TableEngine.canPlayCard()`/`previewPlay()` the same way this sprint reused `BiddingEngine.canSubmit()`.

**Not starting it now.** Per this gate's own instruction: no implementation, no fixes applied, stopping after this report.
