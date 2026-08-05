# Changelog — Sprint 3.4: Match Initialization & Game Start

## Added
- `design-ui/match-service.js`:
  - `createMatch(roomId) → matchId` — the lower-level primitive; reads `rooms/{roomId}` (read-only), creates a new `matches/{matchId}` document. No ready-gate, no duplicate-prevention.
  - `startMatch(roomId) → matchId` — the safe, orchestrated entry point. A single Firestore transaction spans **both** `rooms/{roomId}` and `matches/{matchId}` — the concrete mechanism behind "two players pressing Ready simultaneously cannot create two matches." Idempotent (returns the existing matchId if the room already has one). Re-validates all-ready itself (defense in depth).
  - `loadMatch(matchId) → matchDataOrNull` — read-only, resolves `null` (not a rejection) if not found.
  - `subscribeToMatch(matchId, callback) → unsubscribe` — real `onSnapshot` listener (was a no-op stub through Sprint 3.3).
  - `submitDashCall`/`submitBid`/`submitPass`/`declareTrump`/`submitEstimate`/`playCard`/`resolveTrick`/`completeRound`/`advanceToNextRound`/`endMatch` remain `Not implemented` — bidding/estimation/card-play are explicitly out of scope this sprint.
- `design-ui/room-service.js`:
  - `setReady()` now detects "every player in the room is ready" after its own transaction commits and fires `MatchService.startMatch(roomId)` as a non-blocking, fail-open follow-up (`maybeStartMatch()`). New, one-directional `RoomService → MatchService` dependency — no circular dependency introduced.
- `design-ui/match/index.html` (+ its own `game-state.js` copy, byte-identical to Lobby's/Profile's/Login's) — a new placeholder Match screen, following the exact visual pattern from Profile (Sprint 3.0). Subscribes to the real match document via `MatchService.subscribeToMatch()` and displays its actual `status`/`currentRound`/`dealer`/`players`; states plainly that gameplay isn't implemented yet.
- `design-ui/lobby/index.html` — Lobby now detects a match start entirely through `SessionService` (no new RoomService/MatchService subscription added): its existing `SessionService.subscribe()` callback checks the incoming profile for `currentMatchId` and, only if this tab also knows its own `lastRoomId` (a deliberate reconnect-avoidance guard), navigates via `GameState.goTo(GameState.STATES.GAMEPLAY, { file: "../match/index.html", force: true, data: {...} })`. A light 4s poll (`SessionService.refresh()`) covers the player who wasn't the one whose `setReady()` call crossed the "everyone ready" threshold. `game-state.js` itself was **not** modified — `force: true` is an existing, already-designed escape hatch, used here instead of adding `Gameplay` to Lobby's transition graph.
- `firestore.rules`:
  - `isValidStatusChange()` extended to permit `"in_game"` (only reachable from `"waiting"` or staying `"in_game"`).
  - New `isValidMatchIdChange()` — on the exact write that flips `status` from `"waiting"` to `"in_game"`, `matchId` must go from absent/`null` to a string in that same write; on every other write, `matchId` must be completely unchanged (immutable once set).
  - `rooms/{roomId}`'s update field whitelist gains `matchId`.
  - New `matches/{matchId}` block: `get` restricted to a player listed in the match's own `players[]` (not a global authenticated read); `list` denied; `create` requires the acting user be one of the new document's `players[]`, field-whitelisted to the actual shipped shape, `status == "starting"`, `currentRound == 1`, `dealer`/`turn` both real members; `update` denied outright (no gameplay write path exists yet); `delete` denied.
  - This was a **required** addition, not optional scope creep — without it, `startMatch()`'s own write would be denied the moment the rules file is ever published (see `MatchInitialization.md`'s dedicated section).
- `tests/match-service.test.cjs` — new permanent test file, 50 tests: `createMatch` document shape (including the `gameState` TODO-placeholder assertion), `startMatch` happy path + room/profile side effects, not-all-ready rejection, nonexistent-room rejection, sequential duplicate-start idempotency, two-concurrent-`startMatch`-calls-produce-exactly-one-match, `loadMatch` found/not-found, `subscribeToMatch` immediate-snapshot/update/unsubscribe/not-found/offline, Firestore-unavailable handling for every method, all ten still-stubbed gameplay methods, and a dedicated cross-service integration section proving `RoomService.setReady()` triggers `MatchService.startMatch()` end-to-end (including a genuine concurrent-ready race) through the same fake multi-collection-transaction-faithful Firestore mock.
- `tests/rules-simulation.test.js` — extended with 16 new tests (45 total, preserving Sprint 3.2.1/3.3 history) covering the `matchId`/`in_game` transition rules and the new `matches/{matchId}` create/get rules.
- `docs/implementation/MatchInitialization.md` — full implementation report, including the `Dealer.dealHands()`/missing-`Deck` finding and the atomicity-boundary reasoning.
- This QA package.

## Changed (documentation sync)
- `docs/architecture/ServiceArchitecture.md` — `MatchService`'s method list re-synced to the actual shipped signatures (Implemented/Not-yet-implemented split); `RoomService`'s `setReady` entry notes the new `startMatch` trigger.
- `docs/architecture/MatchLifecycle.md` — added an "Implementation status" callout: only LOBBY → WAITING → match-created → room `in_game` is real; everything from DEALING onward remains design-only, with two explicit, documented deviations from the original diagram (no room `"ready"` status; room goes straight `waiting → in_game`).
- `docs/architecture/FirestoreSchema.md` — `rooms/{roomId}` gains `matchId`; `status` gains `"in_game"`; `matches/{matchId}` re-synced to the actual, minimal shipped shape, with the richer speculative future-gameplay draft retained underneath, clearly labeled as not implemented.
- `docs/architecture/SecurityArchitecture.md` — `matches/{matchId}` row re-synced to the actual deployed (but undeployed-to-production) rule; the richer per-field-path turn-order design is now explicitly marked as future work, not current behavior.

## Not changed
No gameplay engine file (`bidding-engine.js`/`scoring-engine.js`/`dealer.js`/`cards.js`/`table-engine.js`), `PlayerService`, or `SessionService` — verified via `git diff`. No bidding, estimation, card-play, matchmaking, spectators, reconnect, chat, or room browser code was added.

## Regression check
Re-ran the full Sprint 3.3 `tests/room-service.test.cjs` suite (22 tests) after modifying `room-service.js` — all still pass, with two expected new `console.warn` lines from the pre-existing "concurrent setReady from two players" test (its mock scope has no `global.MatchService`, confirming the new fail-open guard works without breaking anything). Re-ran the full pre-existing `tests/rules-simulation.test.js` history — all still pass.
