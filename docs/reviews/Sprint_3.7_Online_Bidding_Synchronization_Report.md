# Sprint 3.7 — Online Bidding Synchronization Contract

**Note on the sprint number:** this repository already has a historical "Sprint 3.7" (Real-Time Match Synchronization — `GameSession.subscribeToRemoteMatch()`, Sprint 3.7.1's Synchronization Hardening). This brief explicitly assigned the SAME number to a new, unrelated sprint. Both are recorded honestly under their own filenames; this report does not renumber or overwrite the earlier one.

## 1. Executive Decision

Dash Call, Auction Bid, and Confirm Call are now fully synchronizable through Firestore, using the SAME architectural pattern this project already established and proved for card play (`cardLog`, Sprint 4.2): an append-only action log, replayed through the real, unmodified `bidding-engine.js` reducer on every client via `BiddingEngine.canSubmit()`/`emit()`. Final Estimate is deliberately untouched — `submitBid()`/`bids`/`applyRemoteBid()` (Sprint 3.8/4.0) remain its exclusive mechanism, unchanged. No gameplay rule was invented, altered, or duplicated. `bidding-engine.js` itself was **not modified** — the sprint's own audited conclusion (§7) was that its existing `canSubmit()`/`emit()` contract was already sufficient.

## 2. Current Bidding Flow (audited directly from current source, this sprint)

| Action | Engine intent (`bidding-engine.js`) | Public API before this sprint | Firestore write before | Listener/adapter before |
|---|---|---|---|---|
| Dash Call | `SubmitDashCallDecision` | none | none | none |
| Auction Bid | `SubmitAuctionBid` | none | none | none |
| Confirm Call | `SubmitConfirmCall` | none | none | none |
| Final Estimate | `SubmitFinalEstimate` | `MatchService.submitBid(matchId, seatId, bid)` (bare integer, Sprint 3.8) | `bids`/`biddingOpen`/`lastBidSeat`/`version` | `MatchAdapter.applyRemoteBid()`/`startBidSync()` (Sprint 4.0) |

Every one of the first three had a real, tested, working `BiddingEngine.emit()`/`canSubmit()` (Sprint 3.6.1) reducer path — confirmed by direct read of `bidding-engine.js`'s switch statement and by re-running `tests/bidding-contract.test.cjs` (88/88 passing, unchanged) — but **zero transport**: no MatchService API, no Firestore field, no listener, no MatchAdapter translation. `tests/bid-sync.test.cjs`'s own `runBiddingToEstimates()` helper explicitly drives Dash/Auction/Confirm via **direct `emit()` calls**, never through any sync path — direct, first-party confirmation of the gap this sprint closes.

## 3. Current Synchronization Gap (as found, before this sprint's changes)

Exactly as the authorizing brief stated: `submitBid()` writes a bare integer, one value per seat, write-once — correct for Final Estimate, structurally wrong for Dash/Auction/Confirm, each of which is a **repeatable, ordered action** a seat may take multiple times per round (e.g. several raises before elimination). No rule was invented to fix this — the fix is transport-layer only, mirroring `cardLog`'s own proven solution to the identical shape problem for card plays.

## 4. Firestore Schema

**New field:** `biddingLog: []` (array, append-only, added to `buildInitialMatchDoc()`).

| Field | Type | Purpose | Producer | Consumer | Authoritative/Derived | Required | Example |
|---|---|---|---|---|---|---|---|
| `biddingLog` | array | Ordered history of every accepted Dash/Auction/Confirm action | `MatchService.submitBiddingAction()` | `MatchAdapter.applyRemoteBiddingAction()` | **Derived** (a replay log — the real state lives in each client's own `BiddingEngine`, never in Firestore itself) | Yes | `[]` at creation |
| `biddingLog[i].seatId` | string (`p1`-`p4`) | Which seat acted | same | same | Authoritative (identity claim, cross-checked against `seats`) | Yes | `"p1"` |
| `biddingLog[i].actionType` | string | Which of the 3 intents — **reuses `bidding-engine.js`'s own `intent.type` string verbatim**, not a second vocabulary | same | same | Authoritative | Yes | `"SubmitAuctionBid"` |
| `biddingLog[i].declaredDashCall` | boolean | Dash Call payload | same | same | Authoritative | Only for `SubmitDashCallDecision` | `false` |
| `biddingLog[i].isPass` | boolean | Auction Bid payload | same | same | Authoritative | Only for `SubmitAuctionBid` | `false` |
| `biddingLog[i].tricks` | int 0-13 | Auction Bid / Confirm Call payload | same | same | Authoritative | For `SubmitAuctionBid` (when not pass) / `SubmitConfirmCall` | `5` |
| `biddingLog[i].suit` | string (5 suit ids) | Auction Bid / Confirm Call payload | same | same | Authoritative | Same as `tricks` | `"SPADES"` |

**REMOTE COMMAND vs. REMOTE DERIVED STATE:** every `biddingLog` entry is a COMMAND (a raw, opaque record of "seat X took action Y") — never a snapshot of `BiddingEngine`'s own internal `state` (which remains entirely client-local, in-memory, derived independently by every client from the SAME command log). Nothing in this schema mirrors `subPhase`/`auctionTop`/`withPlayers`/etc. into Firestore — that would be REMOTE DERIVED STATE, and this sprint deliberately does not introduce it (see §7 for why).

`bids`/`biddingOpen`/`lastBidSeat` (Final Estimate) are **completely unchanged** — no field renamed, no field repurposed.

## 5. MatchService Contract

**New:** `MatchService.submitBiddingAction(matchId, action)` — `action: {actionType, declaredDashCall?, isPass?, tricks?, suit?}`.

- **Not** an extension of `submitBid()` — decided against per the brief's own instruction to base this on existing architecture, not naming preference: `submitBid()`'s "one value per seat, write-once" semantics are correct for Final Estimate and would be a breaking, confusing overload for a repeatable action. Mirrors `submitCard(matchId, card)`'s exact precedent instead (Sprint 4.2.1): no `seatId` parameter — resolved internally via `MatchAdapter.uidToSeat()`.
- **Input shape:** generically validated by a new `isValidGenericBiddingAction()` (mirrors `isValidGenericBidValue()`/`isValidGenericCardValue()`'s established "shape, not legality" line exactly).
- **Firestore write:** a real transaction, append-exactly-one-entry, `version + 1`, re-verifying the pre-transaction version against a fresh in-transaction read (`STALE_GAME_STATE` on mismatch) — the SAME pattern `submitCard()` established in Sprint 4.2.2, reused verbatim, for the identical reason (the legality check was computed against LOCAL engine state, outside Firestore's own transaction machinery).
- **Listener behavior:** none of its own — consumed via the existing `MatchService.subscribeToMatch()`, ref-counted, no new listener type.
- **Concurrency behavior:** two racing writes for the same seat/action are serialized by the version guard — exactly one succeeds, the other is rejected `STALE_GAME_STATE` (verified, §10.L).
- **Error behavior:** `INVALID_ARGUMENT` (no matchId), `INVALID_BIDDING_ACTION_VALUE` (malformed shape), `UNAUTHENTICATED`, `MATCH_ADAPTER_UNAVAILABLE`/`ENGINE_UNAVAILABLE`, `PERMISSION_DENIED` (unowned seat), `ILLEGAL_BIDDING_ACTION` (rejected by the real engine — includes the exact engine reason string), `MATCH_NOT_FOUND`, `STALE_GAME_STATE`.

**Pre-write authority/legality gate, one call, not two:** `submitBiddingAction()` asks `BiddingEngine.canSubmit()` — which already checks turn AND phase AND every content rule internally — BEFORE any Firestore access. It deliberately does **not** also call `MatchAdapter.assertLocalTurn()` (the SEPARATE gate `submitCard()` uses in addition to `previewPlay()`): `assertLocalTurn()` checks `matches/{matchId}.turn`, a field nothing in this codebase ever advances through the Dash/Auction/Confirm sub-phases (a pre-existing, already-documented gap — see `match-adapter.js`'s own header). Using it here would incorrectly reject every action past the first one in every match. This is not a shortcut — `canSubmit()` unifies what card sync needed two separate gates for, because Sprint 3.6.1 built it as one combined turn+legality check from the start.

## 6. MatchAdapter Contract

**New:** `applyRemoteBiddingAction(matchId, matchDoc)` + `startBiddingActionSync(matchId)` + diagnostic accessors, mirroring `applyRemoteCard()`/`startCardSync()`'s exact structure — a **fourth** independent version+count registry (separate from bids/turn/cards, same reasoning as every prior sprint's identical design choice).

- **Translates Firestore data into engine intents:** `biddingLogEntryToIntent()` — a direct field passthrough (`actionType` IS `intent.type`).
- **Prevents duplicate application:** dual version+count gate, identical to `applyRemoteCard()`'s.
- **Preserves ordering:** sequential replay, index by index, stop-at-first-problem — never skips ahead.
- **Handles reconnect/reload:** a fresh subscribe replays the FULL existing log from index 0 (verified, §10.J).
- **Distinguishes local from remote action / avoids replaying an already-applied action:** the one genuinely new piece of logic this sprint adds. `bidding-engine.js` has no single field (unlike `TableEngine`'s `state.plays`) that directly answers "is this entry already applied" across all three action types uniformly. Instead, `applyRemoteBiddingAction()` calls `BiddingEngine.canSubmit()` on every entry before `emit()`; if the rejection reason is specifically a phase/turn-guard string (matched against the exact literal strings `canSubmit()`'s own source uses — never a heuristic), it is treated as a benign, expected `ALREADY_APPLIED_LOCALLY` skip, never a desync. Any OTHER rejection reason (a genuine content-rule mismatch) is a real desync, stopped immediately, exactly like `applyRemoteCard()`'s `ENGINE_REJECTED`.
- **Never duplicates Firestore listeners:** reuses `MatchService.subscribeToMatch()` verbatim — verified (§10.K): 3 different sync pipelines on the same matchId produce exactly 1 real `onSnapshot` registration.

**Existing adapter infrastructure was extended, not duplicated** — every new function follows an established sibling's exact shape (`applyRemoteCard`/`startCardSync` for the replay-log pattern, `applyRemoteBid` for the "ask the engine before writing" pattern).

## 7. Command vs. State Decision

**Chosen: C — Action log + derived state.**

| Option | Verdict |
|---|---|
| A. Replicated commands only (no log) | Rejected — cannot represent a repeatable action's full history for a late joiner without data loss; a "last command" field can't reconstruct 4 seats' worth of Dash decisions or an N-bid auction. |
| B. Replicated complete `BiddingEngine` state | Rejected — would mean ONE client's local engine becomes authoritative and broadcasts a snapshot others must trust, breaking this project's own established principle that every client's own engine independently decides accept/reject by replaying the SAME commands (never by trusting a peer's claimed outcome). Also couples Firestore's schema to `bidding-engine.js`'s internal shape, which `MatchService` must never know (non-negotiable per this sprint's own brief). |
| **C. Action log + derived state** | **Chosen.** Directly reuses `cardLog`'s already-proven Sprint 4.2 pattern for the identical shape problem. Every client replays the SAME log through the SAME unmodified reducer — convergence is structural, not something a broadcast snapshot has to be trusted to preserve. Handles reconnect/late-join naturally (replay from index 0). Debugging is trivial (the log IS the audit trail). Directly extensible to any future TableEngine-adjacent sync need the same way `cardLog` already was. |
| D. Other | Not evaluated further — C already directly matches this project's own precedent; no evidence favored inventing a fifth pattern. |

## 8. Concurrency Model

| Scenario | Where authority lives | Verified |
|---|---|---|
| Two players submit simultaneously (different seats) | `BiddingEngine.canSubmit()`'s own turn check — only the seat currently `waitingFor` can pass; the other is rejected pre-write, zero Firestore access | §10.F |
| Two submissions for the SAME seat/action race | Firestore's transaction version-guard (mirrors `submitCard()`'s Sprint 4.2.2 pattern) — exactly one commits, the other gets `STALE_GAME_STATE` | §10.L |
| Stale client state | The pre-transaction `canSubmit()` check + the in-transaction version re-check both use CURRENT reads, never cached | §10.I |
| Duplicate click / duplicate delivery | `applyRemoteBiddingAction()`'s version+count dual gate | §10.H |
| Reconnect / page reload | Fresh subscribe replays the full log from scratch | §10.J |
| Listener replay (same snapshot delivered twice) | Same dual gate | §10.H |
| Same action arriving twice | Same dual gate, plus the phase/turn-mismatch → `ALREADY_APPLIED_LOCALLY` classification | §10.M |
| Action arriving out of order | Cannot happen by construction — `biddingLog` is append-only and replayed strictly by array index; Firestore's own transaction serializes concurrent appends | §10.L (indirectly) |
| Local engine already applied action (self-echo) | `canSubmit()`'s phase/turn-mismatch classification | §10.M |
| Remote action arriving after local action | Same as above — the log is the single ordering authority; "local" and "remote" converge to the same replay once the round-trip completes | §10.N |

**Concurrency is never solved by trusting the UI** — every gate above is either inside `BiddingEngine.canSubmit()` (the real engine) or inside a real Firestore transaction's version check. A future renderer disabling a button is, at most, a UX nicety; every path above is enforced with the renderer entirely absent.

## 9. Implementation Changes

- **`design-ui/match-service.js`** — added `biddingLog: []` to `buildInitialMatchDoc()`; added `isValidGenericBiddingAction()`, `buildBiddingLogEntry()`, `biddingActionToIntent()`, `submitBiddingAction()`; added to the export object.
- **`design-ui/match-adapter.js`** — added `applyRemoteBiddingAction()`, `biddingLogEntryToIntent()`, `isPhaseOrTurnMismatchReason()`, `startBiddingActionSync()`, `getLastAppliedBiddingActionVersion()`, `getLastAppliedBiddingActionCount()`; extended `resetSyncState()`; added to the export object.
- **`firestore.rules`** — added `biddingLog` to `isValidNewMatch()`'s allowed keys + `data.biddingLog == []` at creation; added `isValidBiddingActionEntry()` + `isValidBiddingActionSubmission()`; added to the `allow update` OR-chain.
- **`design-ui/engine/bidding-engine.js`** — **NOT modified.** The audit (§2) confirmed its existing `canSubmit()`/`emit()` contract (Sprint 3.6.1) was already fully sufficient to drive this sprint's entire synchronization contract — no genuine synchronization-contract issue required touching it.
- **`table-engine.js`, `session.js`, `cards.js`, `deck.js`, `dealer.js`, `scoring-engine.js`** — **NOT modified.** No evidence found requiring any of them.
- **No renderer/UI file was created.**

## 10. Test Coverage

New file `tests/bidding-action-sync.test.cjs` (29 assertions, real code from `match-service.js`/`match-adapter.js`/`bidding-engine.js`/`session.js` against a hand-written fake Firestore, mirroring `tests/bid-sync.test.cjs`'s own established harness):

| Req. | Scenario | Result |
|---|---|---|
| A | Dash Call sync | PASS |
| B | Auction Bid sync | PASS |
| C | Confirm Call sync | PASS |
| D | Final Estimate boundary (rejected by `submitBiddingAction`, stays on `submitBid()`) | PASS |
| E | Malformed action (8 distinct malformed shapes) | PASS |
| F | Wrong player (unseated uid; real seat, wrong turn) | PASS |
| G | Wrong phase | PASS |
| H | Duplicate action (re-delivered identical snapshot) | PASS |
| I | Stale action (older version delivered after a newer one) | PASS |
| J | Reload/resume (fresh subscribe replays the full log) | PASS |
| K | Listener duplication (3 sync pipelines, 1 matchId → 1 real listener) | PASS |
| L | Concurrent submission (2 parallel calls, same seat/action → exactly 1 succeeds) | PASS |
| M | Local vs. remote application (self-echo recognized, not double-applied) | PASS |
| N | State convergence (remote-only sequence matches direct-`emit()` equivalent) | PASS |

`tests/rules-simulation.test.js` extended with `isValidNewMatchV6` (create-time `biddingLog`) and a JS mirror of `isValidBiddingActionSubmission()` — 19 new assertions covering: valid create, rejected old shape, rejected pre-filled log, all 3 action types + pass-shape, sequential appends, unowned/fabricated seat, malformed entries (unknown type, extra key, out-of-range tricks, unknown suit), version-must-be-+1, growth-must-be-exactly-1 (both directions), "reject every other write," and one explicitly labeled **KNOWN, DOCUMENTED LIMITATION** test (mirroring `isValidCardSubmission()`'s own honesty convention) proving the rules layer alone does not enforce turn order — by design, since `matches/{matchId}.turn` is never advanced through bidding sub-phases.

## 11. Browser Verification

Real headless Chromium (Playwright), same harness technique as every prior sprint's own verification this session (local static server for `design-ui/`, stubbed Firebase compat scripts, seeded `sessionStorage`).

**Confirmed, in a real browser, loading the actual shipped files:**
- All 5 new/relevant public API functions exist as real functions: `window.MatchService.submitBiddingAction`, `window.MatchAdapter.applyRemoteBiddingAction`, `.startBiddingActionSync`, `.getLastAppliedBiddingActionVersion`, and `window.BiddingEngine.canSubmit`.
- `MatchService.submitBiddingAction()`, called live in-browser, correctly performed a real (stubbed) Firestore transaction: version advanced 1→2, `biddingLog` grew from 0→1 entries, with the exact submitted action's shape.
- `MatchAdapter.applyRemoteBiddingAction()`, called directly against the resulting document, correctly replayed the entry through the REAL, browser-loaded `bidding-engine.js`: the engine's `bids.p1` became populated and `waitingFor` correctly advanced to `p2` — proving the actual shipped reducer, not a stub, executed correctly in a real browser JS engine.
- `startBiddingActionSync()` + 2 other sync pipelines on the same matchId produced exactly 1 real `onSnapshot` registration (no duplicate listener), confirmed via the page's own instrumented Firestore stub.
- No NEW uncaught console error was introduced — the only two page errors observed (`buildHand is not defined`, `bindStatic is not defined`) are the already-known, already-documented, explicitly out-of-scope missing-render-layer errors, unchanged from every prior sprint's own honest reporting.

**Honest limitation of this specific browser check, stated plainly rather than overclaimed:** the ad hoc Firestore stub used for this one script's automatic-listener-delivery path did not reproduce `MatchService.subscribeToMatch()`'s full internal behavior with enough fidelity to independently re-confirm, IN THE BROWSER, that `startBiddingActionSync()`'s subscription callback fires automatically end-to-end without an explicit manual trigger. This exact path (automatic delivery through the real, unstubbed `subscribeToMatch()` logic) IS thoroughly covered — 14 scenarios, including duplicate/stale/reconnect/listener-count — by `tests/bidding-action-sync.test.cjs`'s own more faithful fake-Firestore harness (Node, not browser). The browser check's unique, successfully-demonstrated value was proving the real files parse, load, and execute correctly as actual browser JS, and that the write + replay halves of the pipeline each independently produce correct results against the real engine.

## 12. Regression Results

- `node --check` on every modified JS file (`match-service.js`, `match-adapter.js`) — **OK**. `bidding-engine.js` re-checked — **OK**, byte-for-byte unchanged from Sprint 3.6.1.
- Full suite, all 17 test files, actually executed: **1090 passed, 0 failed.**
- **Baseline 1042 → Final 1090** (1042 + 29 new in `tests/bidding-action-sync.test.cjs` + 19 new in `tests/rules-simulation.test.js`, zero regressions).

## 13. Git Scope

**Modified this sprint:**
- `design-ui/match-service.js`
- `design-ui/match-adapter.js`
- `firestore.rules`
- `tests/rules-simulation.test.js` (extended)

**Created this sprint:**
- `tests/bidding-action-sync.test.cjs`
- `docs/reviews/Sprint_3.7_Online_Bidding_Synchronization_Report.md` (this file)

**Unchanged by this sprint** (confirmed via `git diff --stat` — identical to their pre-sprint state): `design-ui/engine/bidding-engine.js`, `table-engine.js`, `session.js`, `cards.js`, `deck.js`, `dealer.js`, `scoring-engine.js`.

**Pre-existing, unrelated uncommitted files** (from prior sprints, not touched by this task): `design-ui/match/index.html`, `docs/architecture/MatchLifecycle.md`, `tests/bidding-contract.test.cjs`, and 4 files under `docs/reviews/` from earlier audits/plans.

No unrelated cleanup, no aesthetic refactoring, no render work, no navigation work, no visual design work was performed.

## 14. Remaining Risks

**MEDIUM**
- The Firestore rules layer for `biddingLog` (like `cardLog` before it) does not independently re-verify that every EARLIER log entry is byte-for-byte unchanged when a new one is appended — the same documented CEL-expressiveness gap `isValidCardSubmission()` already carries, restated (not newly introduced) for `biddingLog`.
- The rules layer also does not enforce turn order at all for bidding actions (deliberate, documented, §6/§9's honest-limitation test) — real enforcement is 100% client-side (`canSubmit()` + every client's own re-validation via `applyRemoteBiddingAction()`), consistent with this project's existing Spark-tier, no-Cloud-Functions constraint.

**LOW**
- The ad hoc browser verification's stub-fidelity gap (§11) — bounded, and already covered by the Node suite's more faithful harness.
- `applyRemoteBiddingAction()`'s phase/turn-mismatch string-matching (`isPhaseOrTurnMismatchReason()`) is coupled to `canSubmit()`'s exact literal reason strings — if `bidding-engine.js` is ever touched in a future sprint and those strings change, this matcher would need updating too. Flagged for future maintainers; not a defect today (verified against the current, real strings).

## 15. Render-Layer Readiness

The Dash/Auction/Confirm/Estimate synchronization contract is now complete and symmetric — every bidding sub-phase has a real transport, a real replay path, and zero gameplay-rule duplication anywhere in the transport layer. This closes the load-bearing gap the Gameplay Render Layer Implementation Plan identified as a blocker. Nothing about the render layer itself was built or designed further this sprint (out of scope, per this sprint's own brief).

---

## FINAL STATUS:
**PASS**

## RENDER LAYER:
**READY**

## NEXT SPRINT:
**Render Layer Foundation — Match Shell, Shared Components & Player/Seat Rendering** (per the previously-authorized Gameplay Render Layer Implementation Plan's own Tasks 1-3)

## EFFORT:
**MEDIUM**
