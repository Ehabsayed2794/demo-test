# Changelog — Sprint 3.8: Gameplay Synchronization (Bidding Authority)

First real multiplayer gameplay write. Scope: bidding synchronization only. No UI redesign, no Dealer/Deck/Cards/Scoring changes, no Cloud Functions, no Blaze, no chat/voice/replay/matchmaking/AI, no card play, no trick resolution, no scoring updates, no turn rotation after bidding.

## Added
- **`design-ui/match-service.js` — Task 1 (Seat Identity).** `buildSeatMap()`/`buildInitialMatchDoc()` now establish `matches/{matchId}.seats = {p1..p4: uid}`, assigned positionally from `players[]`, once, at creation — implementing `docs/architecture/SeatIdentityModel.md`'s design (documentation-only through Sprint 3.7.1) for real. Only real seats are mapped — no fabricated seat for a room with fewer than 4 players.
- **`design-ui/match-service.js` — Task 2 (Versioned Writes).** `version` starts at `1` at creation; every accepted write increments it by exactly `1`, computed from a fresh transactional read every time. This activates Sprint 3.7's dormant `version`-based ordering guard inside `subscribeToMatch()` with zero changes to that function.
- **`design-ui/match-service.js` — Task 3 (Submit Bid).** `submitBid(matchId, seatId, bid)` implemented for real (was an unimplemented stub since Sprint 2.7). Enforces seat ownership, no-double-submit, bidding-still-open, in a real Firestore transaction; returns a structured error (`err.reason`) on every failure path. `bid` is stored as an opaque payload — no bid-legality validation (that remains `bidding-engine.js`'s untouched job).
- **`firestore.rules` — Task 5.** `isValidSeatMap()` (new); `isValidNewMatch()` extended to validate `seats`/`version`/`biddingOpen`/`bids`/`lastBidSeat` at creation; `isValidBidSubmission()` (new) — the FIRST real `matches/{matchId}` update rule this collection has ever had, narrowly scoped to exactly the bidding-submission shape.
- **`docs/architecture/SeatIdentityModel.md`** — updated in place from "documentation only" to "implemented," with one documented deviation from the original proposal (bijection validation instead of exact positional re-derivation; partial seat maps for under-4-player matches).
- **`docs/reviews/ArchitectureReport_3.8.md`**, **`docs/reviews/SynchronizationReport_3.8.md`** — new, full reports.

## Not changed
- Every gameplay rule, scoring formula, bidding VALUE-legality rule — untouched. `bid` is opaque at this layer.
- `Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js` — untouched.
- `MatchService.subscribeToMatch()` — zero changes (Task 4: the existing pipe already carries the new fields).
- Every other `MatchService` gameplay method (`submitDashCall`, `submitPass`, `declareTrump`, `submitEstimate`, `playCard`, `resolveTrick`, `completeRound`, `advanceToNextRound`, `endMatch`) — still unimplemented stubs.
- `RoomService`, `SessionService`, `PlayerService` — untouched.
- No card play, trick resolution, scoring update, turn rotation, matchmaking, replay, chat, voice, AI, leaderboards, tournament, or Cloud Functions work was started, per the brief's explicit stop list.

## Testing
- `tests/submit-bid.test.cjs` (new, 41 checks, all **MOCKED**): normal bid, duplicate bid, out-of-order version, wrong seat, wrong uid, permission denied, offline retry, reconnect, two simultaneous bidders (both same-seat and different-seat races), late subscriber, stale snapshot, duplicate snapshot, listener cleanup, memory leak.
- `tests/rules-simulation.test.js` (+24 checks, 85 total, all **SIMULATED**): `isValidSeatMap`/`isValidNewMatchV3`/`isValidBidSubmission` 1:1 CEL translations, covering every Task 5 requirement plus the same failure scenarios from the rules-layer angle.
- `tests/match-service.test.cjs` (+6 net checks, 65 total, **MOCKED**): new document-shape assertions for `seats`/`version`/`biddingOpen`/`bids`/`lastBidSeat`; `submitBid` removed from the generic "still a stub" loop (it's real now) and given its own argument-validation check; two pre-existing `subscribeToMatch` tests updated to bump the document's real `version` field, now that the (previously dormant) ordering guard is live for every match document.
- `tests/match-sync.test.cjs` (-1 check, 58 total, **MOCKED**): `submitBid` removed from its own generic stub-loop for the same reason.
- Full regression: `deck` (39), `room-service` (31), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31) — all **MOCKED**, all unchanged, zero regression.
- **522 automated tests total, all passing.** No test in this project, this sprint or any prior one, has run against the Firebase Emulator or real Firestore — every result is MOCKED or SIMULATED, labeled as such throughout.

## Documentation
- `docs/architecture/MatchSynchronization.md` — new Sprint 3.8 section (Tasks 1-7, Known Limitation restated); testing/files-changed summaries updated.
- `docs/architecture/ServiceArchitecture.md` — `submitBid()` documented in full; moved out of "not yet implemented."
- `docs/architecture/MatchLifecycle.md` — Sprint 3.8 note added, clarifying this is a narrow bidding-sync slice, not the full `bidding-engine.js` state machine.
- `docs/architecture/SeatIdentityModel.md` — status updated to implemented, per-section corrections.
- `docs/architecture/SecurityArchitecture.md` — `matches/{matchId}` row updated; new "Bidding write authority" section (who owns bids, who may write, who may read, how version works, how concurrency works).
- `docs/architecture/FirestoreSchema.md` — `matches/{matchId}` field list updated with all five new fields.
- `docs/reviews/ArchitectureReport_3.8.md`, `docs/reviews/SynchronizationReport_3.8.md` — new.
- This QA package.

## Honest limitations (stated explicitly, per the brief's instruction)
- `firestore.rules`' new bidding rules use three CEL constructs new to this file (`.keys()` on a nested map, `.all()`, nested `.diff()`) — standard, documented features, but — like every rule in this project's history — never verified against a real Firestore emulator or real Firestore project. Recommended action: a real `firebase emulators:start` + Rules Unit Testing pass before any production deployment.
- The bid VALUE is not validated for game legality at this layer — only WHO may write WHERE/WHEN. `bidding-engine.js` remains disconnected.
- No translation exists from the uid-keyed `seats`/`bids` map into the engine's seat-id-keyed local state (`GameSession`, `bidding-engine.js`) — that remains the next gameplay-write sprint's prerequisite.
