# Changelog — Sprint 3.7: Real-Time Match Synchronization

## Added
- **`design-ui/match-service.js`** — `subscribeToMatch()` rewritten in place into a production-ready real-time sync primitive (the brief's requested `subscribe(matchId)` — kept under its established name, see below): a ref-counted registry (one real Firestore `onSnapshot()` listener per `matchId` no matter how many local callers subscribe — never duplicated, never leaked), a snapshot-ordering guard (a numeric `version` field, when present, must be strictly newer than the last one seen or the snapshot is ignored), a duplicate-content guard (an identical re-delivery is never re-published), and automatic reconnect-with-backoff on disconnect (250ms→4s cap) that always keeps delivering the last known good data alongside any error, never `null`.
- **`design-ui/engine/session.js` (`GameSession`)** — new, additive-only API: `subscribeToRemoteMatch(matchId)`, `unsubscribeFromRemoteMatch()`, `getRemoteMatch()`, `getRemoteMatchError()`, `isSubscribedToRemoteMatch()`, `onRemoteMatchUpdate(callback)`. This is "GameSession consumes MatchService updates" — GameSession still never touches Firestore directly; every update arrives already-decoded through `MatchService.subscribeToMatch()`.
- **`tests/match-sync.test.cjs`** (new) — 50 real, executable checks covering every item in the brief's testing checklist (see `TEST_CHECKLIST.md`).
- **`docs/architecture/MatchSynchronization.md`** (new) — full Sprint 3.7 design/implementation report.

## Not changed
- Every gameplay rule, scoring formula, and bidding rule — untouched.
- `Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js` — untouched.
- `MatchService`'s gameplay methods (`submitDashCall`, `submitBid`, `submitPass`, `declareTrump`, `submitEstimate`, `playCard`, `resolveTrick`, `completeRound`, `advanceToNextRound`, `endMatch`) — still unimplemented stubs, unchanged. This sprint is synchronization only; no gameplay write path was added.
- `firestore.rules` — untouched. `matches/{matchId}` still correctly has `allow update: if false` (there is still no legitimate write path to a match document after creation).
- `SessionService`, `PlayerService`, `RoomService` — untouched.
- No AI, chat, voice, matchmaking, Ready-state improvement, replay, or leaderboard work was started, per the brief's explicit stop list.

## Known limitation (documented, not solved this sprint)
`matches/{matchId}` identifies players/dealer/turn by real Firebase Auth **uid**; the gameplay engine identifies seats by canonical **seat id** (`p1`..`p4`). `GameSession.getRemoteMatch()` returns the raw, uid-keyed document as published — no merge into the engine's seat-id-keyed fields is attempted. See `docs/architecture/MatchSynchronization.md`'s "Known Limitation" section for the full reasoning and why this is the correct scope boundary for a synchronization-only sprint.

## Testing
- `tests/match-sync.test.cjs` (new, 50 checks): two-tab synchronization, card-play/estimate/turn passthrough, duplicate-listener impossibility, memory-leak teardown/re-attach, offline recovery (including a sustained-outage backoff sequence), snapshot ordering (same-version and late-arriving-stale cases), and GameSession's consumption of all of the above. Re-run 4+ times with no flakiness.
- Full pre-existing regression suite re-run, zero regression: `tests/deck.test.cjs` (39), `tests/match-service.test.cjs` (59 — its own pre-existing `subscribeToMatch` tests now exercise the new implementation, unmodified and still passing), `tests/room-service.test.cjs` (31), `tests/rules-simulation.test.js` (61), `tests/match-flow-integration.test.cjs` (156), `tests/match-flow-normal-dash-scoring-fix.test.cjs` (16), `tests/match-flow-scoring-scenarios.test.cjs` (31).
- **443 automated tests total, all passing.**

## Documentation
- `docs/architecture/MatchSynchronization.md` (new) — full design/implementation report, including the API contract table, the Known Limitation, and the testing summary.
- `docs/architecture/ServiceArchitecture.md` — `MatchService.subscribeToMatch()`'s entry updated to describe the new production-ready behavior; header note updated to record the new `GameSession → MatchService` dependency edge.
- `docs/architecture/MatchLifecycle.md` — a Sprint 3.7 note added alongside the existing Sprint 3.4/3.4.1 notes, recording that the read side of the lifecycle is now real-time, while `DEALING` onward remains not implemented (unchanged).
- This QA package.
