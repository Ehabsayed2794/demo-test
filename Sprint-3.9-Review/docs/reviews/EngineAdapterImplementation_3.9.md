# Implementation Report — Sprint 3.9: Engine Adapter Layer (Seat ↔ Engine Synchronization)

**Sprint type:** new isolation layer. Not a feature sprint. No gameplay logic, no rule changes, no Firestore rules changes, no UI redesign, no bid/card/trick synchronization, no turn authority.

## 1. Executive Summary

Delivered exactly one new file, `design-ui/match-adapter.js`, implementing all seven tasks the brief specified: seat resolution (Task 2), pure state translation (Task 4), engine bootstrap (Task 3), and isolation (Task 5) — packaged together in one adapter (Task 1), tested (Task 6, 42 checks, all MOCKED), and documented (Task 7, `docs/architecture/EngineAdapter.md`). Zero existing files were rewritten. `GameSession`, `Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `match-service.js`, and `firestore.rules` are byte-for-byte unchanged.

## 2. Key design decisions, and why

**Decision 1 — bootstrap does not write `players` or bidding sub-state into `GameSession`, despite Task 3 naming both as responsibilities.** Both `session.players` and `session.biddingState` have established, non-trivial shapes owned elsewhere (`mockPlayers()`'s rich profile shape; `bidding-engine.js`'s reducer shape). Firestore's corresponding data (`players: uid[]`, `bids: {seatId: rawValue}`) is differently shaped and would corrupt those contracts if written in directly. "Load" is satisfied by including both in the bootstrap function's RETURNED snapshot; "write into GameSession" is not attempted for these two fields. This is the single most important scope decision this sprint made, and it is documented in three places (this report, `EngineAdapter.md`, and `match-adapter.js`'s own doc comment) rather than only one, given how easy "load bidding state" is to misread as "wire up live bid sync" — which the stop list explicitly forbids.

**Decision 2 — duplicate-seat resolution is deterministic, not an error.** `firestore.rules`' `isValidSeatMap()` (Sprint 3.8) already prevents a duplicate uid across two seats from ever being written legitimately. Rather than assume that guarantee always holds (and throw or behave unpredictably if it somehow didn't — malformed data reaching this layer via a bug elsewhere, a test fixture, or future code this adapter doesn't control), `uidToSeat()` resolves to a well-defined, sorted "first match" — canonical seat order, non-canonical names after. This is the same "neither layer trusts the other alone" principle already established since Sprint 3.4.1, applied to a translation layer instead of a security rule.

**Decision 3 — `seatToPlayer()` returns a minimal `{seatId, uid}` descriptor, not a rich profile.** The match document has no name/rank/avatar data at all (that lives in `players/{uid}`, a separate collection `PlayerService` owns). Fetching it would add a new, real dependency on `PlayerService` — expanding scope beyond "translate identities" into "aggregate profile data." Recorded as a named future extension point instead.

**Decision 4 — the pure/impure split (Tasks 3 vs. 4) is a real code boundary, not just a description.** `matchDocToEngineSnapshot()`/`engineSnapshotToMatchPatch()` are pure by construction — no I/O, no global reference, no side effect, verified directly by a determinism test (same input twice → byte-identical output) and a no-mutation test (the input object is untouched after translation). `bootstrapGameSession()` is the one function permitted side effects, and its only side effects are calls to `GameSession`'s own pre-existing setters — nothing hidden.

## 3. What was verified, and how (honesty statement)

Every one of the 42 new tests is labeled **MOCKED** — real `match-adapter.js` and `session.js` code, exercised against hand-constructed plain JavaScript objects standing in for Firestore documents. No Firestore mock was needed (this adapter never touches Firestore), no Firebase Emulator, no real Firestore project, no SIMULATED (rules-translation) tests — this sprint touches no `firestore.rules` at all. This is consistent with every prior sprint's own honesty statements (Sprint 3.7.1 onward) restating that no test in this project has ever run against a real Firestore backend.

The isolation requirement (Task 5) is verified structurally: a test reads `match-adapter.js`'s own source text and confirms it contains no `require()` of `match-service.js`, `session.js`, or any `engine/` file — the file only ever references `global.GameSession` lazily, inside function bodies, at call time, exactly matching this codebase's established soft-coupling idiom (the same pattern `match-service.js` already uses for `global.SessionService`).

## 4. Round-trip proof (Task 6's explicit requirement)

`matchDocToEngineSnapshot()` followed by `engineSnapshotToMatchPatch()` was verified to reproduce every field this adapter is responsible for, exactly: `players`, `seats`, `dealer` (uid, via seat translation and back), `turn` (uid, via seat translation and back), `currentRound`, `version`, `biddingOpen`, `bids` (including a real `null` slot, distinguished from a missing key), and `lastBidSeat`. Verified for both a full 4-player match and a partial 2-player match. This is not asserted — it's checked with real deep-equality comparisons in `tests/match-adapter.test.cjs`.

## 5. Honest limitations / what remains for a future sprint

- No wiring exists yet from any real screen to `bootstrapGameSession()` — it is delivered, tested, and documented, but not called anywhere in the UI. That's deliberate (this sprint's brief never asked for a UI change) but worth stating plainly rather than implying integration is complete.
- Turn/dealer translation carries values through with no NEW meaning — `turn` still means nothing gameplay-wise, exactly as every prior sprint has said.
- `seatToPlayer()`'s minimal descriptor is not a substitute for real profile data — a future sprint integrating `PlayerService` is a real, separate piece of work, not a trivial follow-up.
- Bidding sub-state is loaded once, not synchronized — the next sprint that wires `bidding-engine.js` to real bids will need to solve the shape-mismatch problem this report's Decision 1 flags, not just call this adapter more often.

## 6. Regression

Full suite re-run after the new file was added: `deck` (39), `match-service` (65), `match-sync` (58), `submit-bid` (66), `room-service` (31), `rules-simulation` (109, SIMULATED), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31) — all unchanged, zero regression. **613 automated tests total, all passing** (571 pre-existing + 42 new).

## 7. Conclusion

The adapter layer is complete, minimal, isolated, and honestly documented, including the one place its scope intentionally stops short of what a literal reading of Task 3 might suggest. Stopping here per the brief's stop condition — no bid/card/trick synchronization, no turn authority, no gameplay writes were started. Waiting for review.
