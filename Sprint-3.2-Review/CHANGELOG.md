# Changelog — Sprint 3.2: Room Foundation

## Changed
- `design-ui/room-service.js`:
  - `createRoom(playerId, roomName)` — creates a `rooms/{roomId}` document (`status: "waiting"`, `creator`, `players: [playerId]`, `createdAt`/`updatedAt`), returns the new `roomId`. Syncs `currentRoomId` onto the creator's `players/{uid}` profile via `PlayerService.updatePlayerProfile()` (existing API), then `SessionService.refresh()` (existing API).
  - `joinRoom(roomId, playerId)` — transaction-guarded: validates the room exists and isn't closed/full (cap: 4 players), adds the player, idempotent if already a member. Syncs `currentRoomId` the same way.
  - `leaveRoom(roomId, playerId)` — transaction-guarded: removes the player; closes the room if it's now empty; transfers `creator` to the next remaining player if the creator left. Syncs `currentRoomId: null`. Idempotent for a room that's already gone.
  - `setReady`/`transferHost`/`closeRoom` remain `Not implemented` — unchanged, out of scope.
- `design-ui/lobby/index.html`:
  - `Create Room` button: removed its dead `onclick` (pointed at a screen that doesn't exist), added a real click handler calling `RoomService.createRoom`.
  - Added one new small text link, `Join a room by ID`, in the existing "Play with Friends" card — uses `window.prompt()` for the room ID, no new input field.
  - Both use plain `alert()` for success/error (Lobby doesn't load `shared-ui.js`'s toast helper).
  - Added `room-service.js` script include.

## Added
- `docs/implementation/RoomFoundation.md` — full implementation report, including the schema-shape reconciliation with the earlier speculative design and the honest rules-deployment limitation.
- This QA package.

## Not changed
`firestore.rules` (explicitly out of scope — the `rooms` collection has no rule granting it access yet; every `RoomService` call will `permission-denied` against the live project until a future, authorized sprint updates the rules), `PlayerService`/`SessionService` core logic (used only via their existing public methods), `GameState`, `GameSession`, Dealer, Cards, Scoring, Bidding, Match Engine, Login, Profile, and all other Sprint 2.7 service stubs. Verified via `git diff`.

## Not implemented (by design, this sprint)
Ready state, room listing/browsing, chat, matchmaking, a dedicated "Room" screen. Confirmation is via `alert()` only, per the brief's own "no complex UI yet."
