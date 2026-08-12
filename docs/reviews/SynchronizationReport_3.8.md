# Synchronization Report — Sprint 3.8: Gameplay Synchronization (Bidding Authority)

This report covers Task 4 (Realtime Synchronization), Task 6 (Conflict Handling), and Task 7 (Failure Recovery) in depth — the runtime behavior verification companion to `ArchitectureReport_3.8.md`'s design decisions. Every claim below states plainly whether it was verified MOCKED, SIMULATED, against the Firebase Emulator, or against real Firestore — per this sprint's explicit instruction not to mix these terms. **None of this project's tests, this sprint or any prior one, have run against the Firebase Emulator or real Firestore.** Every result below is MOCKED (a hand-written fake Firestore exercising the real `design-ui/match-service.js` code) or SIMULATED (a 1:1 JS translation of `firestore.rules`' CEL, not the real rules engine).

## 1. Realtime Synchronization (Task 4)

**Requirement:** when one player submits a bid, every connected client receives the updated bid, version, and bidding state — no polling, using the existing `MatchService` subscription, no second listener.

**How this is achieved:** `bids`, `biddingOpen`, `version`, and `lastBidSeat` are ordinary fields on the same `matches/{matchId}` document `subscribeToMatch()` already watches. `submitBid()`'s transaction writes them via `tx.update()`; the moment that commits, the SAME `onSnapshot()` listener Sprint 3.7 established fires with the new document state. **Zero lines of `subscribeToMatch()` changed for this sprint.**

**Verified (MOCKED, `tests/submit-bid.test.cjs`):**
- A client already subscribed before a bid is submitted receives the update automatically, including the new bid value and version (2 checks).
- The underlying mock's `onSnapshot()` call counter is asserted to stay flat across the write — direct proof no second listener was created, not an inference from absence of errors.
- A LATE subscriber (joining after the bid was already accepted) immediately receives the current, post-bid state — not stale or empty data (2 checks).
- No polling exists anywhere in the data path — confirmed by code inspection (there is no `setInterval`/timer in `submitBid()` or anywhere in the realtime delivery path; the one timer in the whole file, `scheduleReconnect`'s backoff, is unrelated to this sprint and unchanged).

## 2. Conflict Handling (Task 6)

**Requirement:** two simultaneous bids — only one succeeds where they conflict; the second retries only if appropriate; a duplicate bid is ignored; a stale version is rejected; a permission-denied error never retries.

**Two DIFFERENT seats, concurrent (MOCKED):** `Promise.all([submitBid(seatA), submitBid(seatB)])` against the same match. Firestore's own transaction mechanism serializes the two writes — whichever commits first wins that version number; the other's transaction callback is automatically re-run by the mock's (faithful) optimistic-concurrency simulation against the now-current document. Both succeed. Verified: both bids present, version advanced by exactly 2, `biddingOpen` correctly closes once both real seats have bid (3 checks).

**The SAME seat, concurrent (MOCKED) — the literal "only one succeeds" case:** two `submitBid()` calls for the identical seat, raced via `Promise.all`. Exactly one resolves successfully; the other rejects `ALREADY_BID` — its transaction retry re-read the winner's already-committed bid and correctly refused to double-submit, rather than silently overwriting it or corrupting the seat's final value into some mixed state (3 checks, including an explicit check that the final stored value is EXACTLY one of the two racing values, never a corrupted blend).

**Stale version (SIMULATED, `tests/rules-simulation.test.js`):** a write claiming `version == currentVersion` (unchanged) or a version that skips ahead — both denied by `isValidBidSubmission()`'s translated logic (2 checks). This complements a MOCKED check in `submit-bid.test.cjs` that `submitBid()`'s own transaction can never PRODUCE a stale/skipped version in the first place, since it always computes `version = freshRead + 1`.

**Permission denied, no retry:** `submitBid()` contains no retry logic of its own at all — verified by code inspection (there is no loop, no `setTimeout`, no recursive call anywhere in the function). A permission violation (wrong seat, wrong uid, unauthenticated) is a thrown application `Error` inside the transaction callback, which causes the OUTER `runTransaction()` promise to reject immediately — this is structurally different from a Firestore-level write-conflict (which the SDK retries automatically); an application-thrown error is never subject to that retry machinery. Confirmed (MOCKED): a wrong-seat/wrong-uid/unauthenticated call rejects exactly once, with no delayed second attempt observable in the test (4 checks across the wrong-seat/wrong-uid/permission-denied/unauthenticated scenarios).

## 3. Failure Recovery (Task 7)

**Requirement:** if a transaction fails, expose a structured error; do not corrupt local state; do not overwrite server state; do not silently retry forever.

**Structured error (MOCKED + code inspection):** every rejection carries `err.reason` — one of `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `UNAVAILABLE`, `MATCH_NOT_FOUND`, `UNKNOWN_SEAT`, `PERMISSION_DENIED`, `BIDDING_CLOSED`, `ALREADY_BID` — alongside a human-readable `.message`. Verified directly for the `ALREADY_BID`/`PERMISSION_DENIED`/`UNAUTHENTICATED`/`INVALID_ARGUMENT` cases (4+ checks).

**No local-state corruption (structural, verified by code inspection, not just testing):** `submitBid()` never touches `GameSession` or any local mirror at all — it only calls Firestore. `GameSession.applyRemoteMatchUpdate` (Sprint 3.7) only ever runs as a callback from a REAL, successful `subscribeToMatch()` delivery — there is no code path connecting a failed `submitBid()` call to any local-state mutation. This is a structural guarantee: local state literally cannot be corrupted by a failed write, because nothing wires them together in the failure direction. (MOCKED, confirmed for the offline-failure case: the stored document is completely unchanged after a rejected offline write — version and bids untouched, 1 check.)

**No server-state overwrite:** every write is a `tx.update()` with a specific, narrow patch (`bids`, `biddingOpen`, `version`, `lastBidSeat`, `updatedAt`) computed from a FRESH transactional read — never a blind `.set()` of a whole document, and never based on possibly-stale locally-cached data.

**No silent infinite retry:** confirmed by code inspection (no loop of any kind in `submitBid()`) and by test (MOCKED: offline failure produces exactly one rejected promise per call; the CALLER retrying explicitly, after Firestore becomes available again, is what succeeds — 1 dedicated "reconnect" check demonstrating caller-driven, not automatic, retry).

## 4. Regression

Full pre-existing suite re-run after every Sprint 3.8 change, unchanged pass/fail status apart from the expected, deliberate, documented updates to `tests/match-service.test.cjs` (new doc-shape assertions, `submitBid` removed from the generic "still a stub" loop) and `tests/match-sync.test.cjs` (same stub-loop adjustment) — both changes are additive/corrective, not weakening any existing assertion. See `TEST_CHECKLIST.md` for the itemized before/after count per file. **522 automated tests total, all passing** (MOCKED: `deck` 39, `match-service` 65, `match-sync` 58, `submit-bid` 41, `room-service` 31, `match-flow-integration` 156, `match-flow-normal-dash-scoring-fix` 16, `match-flow-scoring-scenarios` 31; SIMULATED: `rules-simulation` 85).

## 5. Honesty statement

No test in this file, this sprint, or any prior sprint in this project has run against the Firebase Emulator or a real Firestore project. `firestore.rules`' new bidding logic uses three CEL constructs (`.keys()` on a nested map, the `.all()` macro, `.diff()` on a nested map value) that are new to this file — standard, documented Firestore Rules features, but unverified against a real rules engine, exactly like every other rule in this file's history. This is recorded here plainly, not to undermine confidence in the design (the SIMULATED tests do verify the INTENDED logic is internally consistent and correctly rejects every required failure case), but because claiming otherwise would be exactly the kind of overstatement this sprint's brief explicitly asked not to make.

## 6. Conclusion

Bidding synchronizes in real time, exactly once, in order, without conflicts, with honest, tested failure recovery — verified as thoroughly as this project's MOCKED/SIMULATED-only methodology allows. Stopping here, per the brief's stop condition.
