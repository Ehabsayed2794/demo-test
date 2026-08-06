# Changelog — Sprint 3.7.1: Synchronization Hardening & Identity Foundation

Correctness/hardening sprint. No gameplay feature, bidding write, card-play write, turn authority, matchmaking, replay, chat, voice, or UI redesign. Spark-compatible.

## Fixed
- **`design-ui/match-service.js` — Task 1, Retry Policy Hardening.** `subscribeToMatch()`'s reconnect logic retried EVERY `onSnapshot` error forever, including permanent ones (e.g. `permission-denied`). Now classifies every error by its Firestore `.code`: `unavailable`/`deadline-exceeded`/`internal`/`unknown`/`resource-exhausted` retry (unchanged exponential backoff, 250ms→4s cap); `permission-denied`/`unauthenticated`/`invalid-argument`/`failed-precondition`/`not-found` — or any unrecognized/missing code, treated the same by design — stop immediately and record a terminal error, exposed to every current subscriber and any late joiner. The error is still always delivered alongside the last known good data, in both cases.
- **`tests/match-sync.test.cjs` — a real bug in the test mock itself.** `simulateDisconnect()` fired a mocked error without ever actually detaching the mocked listener, so Sprint 3.7's "reconnect" tests could pass without genuinely exercising MatchService's re-attach logic. Fixed — the mock now truly detaches before firing the error, and accepts an explicit error-code parameter.

## Corrected (documentation, no code change)
- **Ordering guard status.** Sprint 3.7's documentation described the `version`-based ordering guard in ways that could be read as active protection. Confirmed via repository-wide search: no write path anywhere sets `version`. The guard's code is correct but inactive; every reference to it across `match-service.js`, `ServiceArchitecture.md`, and `MatchSynchronization.md` now says so explicitly.
- **Testing-claim honesty.** Every test-result description that previously read as an unqualified fact ("Two browser instances stay synchronized," "Reconnect restores synchronization") now explicitly says "mocked"/"simulated" and clarifies that no real Firestore project, Firebase emulator, or real browser instance was ever used.

## Added (documentation only — no implementation)
- **`docs/architecture/SeatIdentityModel.md`** — the official design for mapping the engine's `p1`..`p4` seat ids onto real Firebase Auth uids: a `matches/{matchId}.seats` map, owner (`MatchService`), lifetime (immutable per match), creation (positional, at `startMatch()` time), synchronization (free, via the existing sync pipe), validation (future rule sketch), and security implications (seat claims must be checked against this map, never trusted from a client). Nothing implemented — no field added to any real document, no rule added, no translation code added.
- **`docs/reviews/SynchronizationHardeningReport_3.7.1.md`** — the full report covering all six tasks.

## Cleaned up (Task 5 — no behavioral change)
- **`design-ui/engine/session.js`** — one duplicated try/catch ("safely invoke a remote-match listener callback") consolidated into a shared `safeInvokeRemoteMatchListener()` helper. Verified via the full 452-test suite passing identically before and after.
- No other dead code, duplicated branches, or obsolete comments were found worth removing in either Sprint 3.7 file — reported honestly rather than manufacturing cleanup work.

## Not changed
- `firestore.rules` — untouched. `matches/{matchId}` update remains denied.
- `Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js` — untouched.
- `MatchService`'s gameplay methods — still unimplemented stubs.
- `RoomService`, `SessionService`, `PlayerService` — untouched.
- No gameplay feature, bidding write, card-play write, turn authority, matchmaking, replay, chat, voice, or UI redesign was started, per the brief's explicit stop list.
- The ordering guard's LOGIC — unchanged, only its documented status corrected.
- `MatchService.subscribeToMatch()`'s duplicate-content guard, ref-counted registry, and fail-open "keep local game alive" behavior — unchanged.

## Testing
- `tests/match-sync.test.cjs`: 59 checks (50 from Sprint 3.7 + 9 new for retry classification), all against a mocked Firestore. Re-run 4+ times, no flakiness.
- Full regression suite re-run, zero regression: `deck` (39), `match-service` (59), `room-service` (31), `rules-simulation` (61), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31).
- **452 automated tests total, all passing. All mocked/simulated — explicitly, no real Firestore/emulator/browser was used.**

## Documentation
- `docs/architecture/MatchSynchronization.md` — corrected in place (Tasks 1, 2, 6) with a prominent Sprint 3.7.1 corrections notice at the top.
- `docs/architecture/SeatIdentityModel.md` — new (Task 3).
- `docs/architecture/ServiceArchitecture.md`, `docs/architecture/MatchLifecycle.md` — updated with Sprint 3.7.1 notes.
- `docs/reviews/SynchronizationHardeningReport_3.7.1.md` — new, full report.
- This QA package.
