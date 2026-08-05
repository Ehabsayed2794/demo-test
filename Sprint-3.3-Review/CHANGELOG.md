# Changelog — Sprint 3.3: Ready State Foundation

## Added
- `design-ui/room-service.js`:
  - `setReady(roomId, playerId, ready)` — transaction-guarded, idempotent (no write if already in the desired state), rejects for a nonexistent/closed room or a non-member.
  - `createRoom` now initializes `readyPlayers: []`.
  - `leaveRoom` now also removes the departing player from `readyPlayers`.
- `firestore.rules`:
  - `isValidNewRoom()` — added a field whitelist (closes audit finding F6) and tightened `players.size() >= 1` to `== 1` (closes F7).
  - Replaced `isExistingOrIncomingMember()` with `isValidRoomUpdate()` — field-whitelisted (`players`/`readyPlayers`/`status`/`creator`/`updatedAt` only), with `players`/`readyPlayers` restricted to self-only add/remove via the new `isSelfOnlyChange()` helper, `creator` restricted to reassignment-to-a-current-member via `isValidCreatorChange()`, and `status` restricted to the two real values with a correct-transition check via `isValidStatusChange()`. Closes audit finding F3.
  - **One discovered, documented, deliberately unclosed gap:** an existing member can still reassign `creator` to themself outside of a real leave — recorded inline in the rules file, in `tests/rules-simulation.test.js`, and in `docs/implementation/ReadyStateFoundation.md`. Not fixed because it doesn't block this sprint and fully closing it needs Cloud Functions.
- `design-ui/lobby/index.html` — one new small button, `Toggle Ready (current room)`, next to the existing "Join a room by ID" link. Operates on `lastRoomId`, a plain in-memory variable (no Room screen exists yet). Plain `alert()` confirmations, matching Sprint 3.2's established pattern.
- `tests/room-service.test.cjs` — new permanent test file (renamed from the prior sprints' scratchpad-only pattern): 22 tests covering `createRoom`/`joinRoom`/`leaveRoom` regression plus all of `setReady`'s new scenarios (ready true/false, multiple players, idempotency, non-member/closed-room rejection, two concurrency scenarios against a Firestore-transaction-faithful mock, Firestore-unavailable handling, leave-cleans-readyPlayers).
- `tests/rules-simulation.test.js` — extended with 18 new tests translating the Sprint 3.3 rules 1:1 into JS (29 total, preserving the Sprint 3.2.1 historical tests unchanged for context).
- `docs/implementation/ReadyStateFoundation.md` — full implementation report.
- This QA package.

## Changed (documentation sync — closes audit findings F2, F4, F5)
- `docs/architecture/SecurityArchitecture.md` — `rooms/{roomId}` row rewritten to match the actual deployed rules (was still describing `hostUid`/seats).
- `docs/architecture/ServiceArchitecture.md` — `RoomService`'s method list corrected to match real signatures (`createRoom(playerId, roomName)`, `joinRoom` returns the room object not a seat index, `setReady` marked implemented).
- `docs/architecture/RoomLifecycle.md` — rewritten in full; every section now labeled **Implemented** or **Not Yet Implemented**.
- `docs/architecture/FirestoreSchema.md` — `rooms/{roomId}` gains `readyPlayers`; security requirements re-synced.

## Not changed
No gameplay engine file, `GameState`/`GameSession`, `PlayerService`, or `SessionService` — verified via `git diff`. No Matchmaking, Chat, Room Browser, Game Start, Card Dealing, or Realtime Gameplay code was added.

## Regression check
Re-ran the original Sprint 3.2 create/join/leave scenarios (now inside `tests/room-service.test.cjs`) — all still pass alongside the new `setReady` tests.
