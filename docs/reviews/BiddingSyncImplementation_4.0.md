# Implementation Report — Sprint 4.0: Online Bidding Synchronization (Authority Layer)

**Sprint type:** completes the pipeline Sprints 3.7–3.9 each built one piece of. Not a bidding-rules sprint. No gameplay logic added, no `bidding-engine.js` rewrite, no Firestore rules change, no card play/trick/scoring/turn-authority work.

## 1. Executive Summary

`design-ui/match-adapter.js` gained two new functions: `applyRemoteBid(matchId, matchDoc)` (Tasks 2/3/4) and `startBidSync(matchId)` (Task 1). Together they complete the pipeline:

```
Player -> submitBid() -> Firestore -> MatchService listener -> Engine Adapter -> bidding-engine.js -> GameSession -> UI
```

for exactly one case: the ESTIMATES phase's `SubmitFinalEstimate` action, the only `bidding-engine.js` action whose shape matches what Firestore's `submitBid()` schema (Sprint 3.8, hardened 3.8.1) can represent. No existing file was rewritten. `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `GameSession`, `Dealer`, `Deck`, `Cards`, `match-service.js`, and `firestore.rules` are byte-for-byte unchanged.

## 2. The central scope decision, and why it's correct

The brief's pipeline diagram names `bidding-engine.js` as a stage without qualifying WHICH of its actions apply. `bidding-engine.js` has (at least) four distinct action shapes across its phases — `SubmitDashCallDecision` (a boolean), `SubmitAuctionBid` (a trick count + suit + isPass flag), `SubmitConfirmCall` (a trick count + suit, different legality), and `SubmitFinalEstimate` (a bare trick count). Firestore's `submitBid()` schema stores exactly one opaque, range-validated integer per seat — matching ONLY the last of these four shapes.

Wiring any of the other three without a schema change would require this adapter to GUESS what a bare number means outside the one context it actually represents (e.g., is `4` a Dash decision, an auction raise, or a confirm call?) — which is precisely the "duplicated/invented gameplay rule" this sprint's hard constraints forbid ("DO NOT duplicate bidding rules"). The correct, minimal, honest choice is to wire the ONE case the existing schema actually supports, and state plainly that the other three remain unconnected — not to invent a workaround, and not to silently claim more than was built. This is recorded in three places (`match-adapter.js`'s own comments, `EngineAdapter.md`, `MatchSynchronization.md`) rather than one.

## 3. Task-by-task verification

- **Task 1 (Bid Sync Pipeline):** `startBidSync()` is the pipeline, literally — it calls the existing `MatchService.subscribeToMatch()` (unmodified) and pipes deliveries through `applyRemoteBid()`. No duplicate listener, no reimplemented sync logic — verified directly via the mock's `onSnapshot()` call counter staying at 1 across multiple `startBidSync()` calls for the same match (see the "late subscriber" test).
- **Task 2 (Remote Bid Application):** `applyRemoteBid()` never calls any Firestore write path (confirmed by code inspection — no `db()`/`.update()`/`.set()` reference anywhere in the function) and only ever updates `GameSession`, and only THROUGH `bidding-engine.js`'s own reducer (`emit()`), never via a direct `GameSession` setter for bid data.
- **Task 3 (Version Validation):** strict `>` only, checked in `applyRemoteBid()`'s own gate, independent of (and layered on top of) `MatchService.subscribeToMatch()`'s existing, unmodified ordering guard. Verified: an equal version is rejected (`DUPLICATE_VERSION`), a lower version is rejected (`STALE_VERSION`), and both leave the engine's state completely unchanged (checked directly against `bidding-engine.js`'s real `waitingFor` pointer and `GameSession`'s real `estimates`, not inferred).
- **Task 4 (Duplicate Protection):** two independent layers — the version gate (rejects a byte-identical redelivery of an already-applied version) and a content-level check (`engineState.bids[seatId] != null`, rejecting a genuinely NEWER version whose bid the engine already has, which is what makes the "local bid echo" case safe).
- **Task 5 (Engine Isolation):** `design-ui/match-service.js` still has zero reference to `GameSession`, `BiddingEngine`, or any engine file — confirmed by this sprint's own forbidden-scope sweep (`git diff` shows it untouched). `match-adapter.js` is the only file in this codebase's diff history that references `global.BiddingEngine.emit()`.

## 4. Honest verification method

Every one of the 56 new checks (17 unit-level in `tests/match-adapter.test.cjs`, 39 end-to-end in `tests/bid-sync.test.cjs`) is labeled **MOCKED** — no SIMULATED checks (this sprint touches no `firestore.rules`), no Firebase Emulator, no real Firestore project, no real browser. The end-to-end suite is the more significant of the two: it loads and exercises the REAL `bidding-engine.js` (not a stub, not a fake) alongside a hand-written fake Firestore, proving the full chain — a mocked `submitBid()` write genuinely drives the real auction reducer forward, advances its real `waitingFor` pointer, and produces a real, engine-computed `estimates` map in `GameSession` — not merely that the right function was called.

One real implementation detail surfaced only by writing these tests, not anticipated in advance: `bidding-engine.js`'s own "Forbidden 13" and "Caller's cap" rules rejected several of this test file's first-draft bid values (a normal, correct consequence of the REAL engine actually running its own legality checks against test data that didn't respect them) — the test data was corrected to respect those rules, not the engine relaxed to accept the test data. This is exactly the intended behavior: the engine's real rules apply to synchronized bids exactly as they apply to local ones, with no special case for where a bid came from.

## 5. Regression

Full suite re-run after every change: `deck` (39), `match-service` (65), `match-sync` (58), `submit-bid` (66), `room-service` (31), `rules-simulation` (109, SIMULATED), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31) — all unchanged, zero regression. **690 automated tests total, all passing** (634 pre-existing + 56 new).

## 6. Honest limitations / what remains

- `DASH`/`AUCTION`/`CONFIRM` phases remain completely unconnected to Firestore — only `ESTIMATES` is wired. A future sprint solving the schema-shape question (a richer, phase-aware bid payload, or a separate field per action type) is a prerequisite before those can be wired without inventing gameplay rules in this adapter.
- No UI calls `startBidSync()`/`applyRemoteBid()` yet — delivered, tested, documented, not wired into any screen.
- Bid VALUE legality is still entirely `bidding-engine.js`'s job (unchanged from Sprint 3.8.1) — this sprint adds WHO/WHEN/HOW-OFTEN gating around calling the engine, not WHAT the engine itself decides.
- `firestore.rules`' own CEL constructs (from Sprint 3.8/3.8.1) remain unverified against a real Firestore emulator — unchanged, pre-existing limitation, not touched this sprint.

## 7. Conclusion

The acceptance criteria are met and tested: a remote bid drives the real bidding engine exactly once, every connected client (simulated via independent subscriptions in the test suite) converges on the same state, no duplicate or stale execution occurs, no duplicate listener is created, and no gameplay rule was changed, duplicated, or invented. Stopping here per the brief's stop condition — no card/turn/trick/scoring synchronization, voice, replay, reconnect improvements, AI, matchmaking, or Cloud Functions were started. Waiting for review.
