# Ready State Foundation — Sprint 3.3 Implementation Report

**Scope actually implemented:** `RoomService.setReady()`, a tightened `firestore.rules` addressing the Sprint 3.2.5 Architecture Audit's findings F3/F6/F7, a minimal Lobby "Toggle Ready" control, and a full documentation sync closing findings F2/F4/F5. No gameplay engine, `GameState`/`GameSession`, `PlayerService`, or `SessionService` code was touched.

## `RoomService.setReady(roomId, playerId, ready)`

Transaction-guarded, matching `joinRoom`/`leaveRoom`'s existing pattern exactly:
- Rejects if the room doesn't exist, is closed, or `playerId` isn't a member.
- Idempotent: setting the same value twice performs **zero** writes the second time (checked before the transaction commits) — a direct, deliberate response to the audit's Spark-compatibility review item ("identify unnecessary writes").
- Touches only `readyPlayers` and `updatedAt` — never `players`, `creator`, `status`, or `name`.

`leaveRoom` was extended (not redesigned) to also remove the departing player from `readyPlayers`, so a player who leaves is never left showing as "ready" in a room they're no longer in. `createRoom` was extended to initialize `readyPlayers: []`. Both are minimal, additive changes to existing functions, not new algorithms.

**Not implemented, on purpose:** no automatic `status` transition when every member is ready. That edges into Game Start, explicitly out of scope. Reaching "everyone ready" today has no visible effect beyond the `readyPlayers` array itself.

## Firestore Rules — closing F3, F6, F7

**F6/F7 (create):** `isValidNewRoom()` now has a full field whitelist (`keys().hasOnly([...])`) and requires `players.size() == 1` exactly, instead of `>= 1`. A client can no longer attach arbitrary extra fields or fabricate extra "members" at creation who never actually joined.

**F3 (update) — the significant one:** the Sprint 3.2.1 rule (`isExistingOrIncomingMember`) correctly gated *who* could write but placed no restriction on *what* they wrote. Replaced with `isValidRoomUpdate()`, which combines:
- A field whitelist (`players`, `readyPlayers`, `status`, `creator`, `updatedAt` only — `name`/`createdAt` are now genuinely locked).
- `isSelfOnlyChange()` — a shared helper applied to **both** `players` and `readyPlayers`: the acting user may only add or remove *themself*, never anyone else, never more than one entry per write. This closes the "forge someone else's ready flag" and "kick another member via a raw write" gaps in one principled rule, verified in `tests/rules-simulation.test.js` (a user attempting to mark two different uids ready in one write, or to un-ready someone else, is denied).
- `isValidCreatorChange()` — `creator` may only be reassigned to someone still present in `players`.
- `isValidStatusChange()` — `status` may only be `"waiting"`/`"closed"`, and may only *become* `"closed"` when `players` is now empty.

### A discovered issue, documented rather than silently fixed or silently left in place

While writing `isValidCreatorChange()`, testing surfaced a real, residual gap: **an existing member can currently reassign `creator` to themself without actually leaving the room.** `isValidCreatorChange()` only verifies the *new* creator is a real member — it cannot verify that a genuine leave-with-transfer occurred, because Firestore rules see only the before/after document diff, not the *kind* of client operation that produced it. Fully closing this would require a Cloud Function as the sole writer of `creator` (per `MigrationPlan.md`'s already-established staging), which is out of scope for a Spark-only sprint.

Per this sprint's explicit instruction ("if you discover any architectural issue while implementing, do not silently fix it — document it, only fix if it blocks this sprint"): this does **not** block Ready State from working, so it was not force-fixed with a workaround. It's recorded in three places instead — `firestore.rules`' own inline comment at `isValidCreatorChange()`, a dedicated confirming test in `tests/rules-simulation.test.js`, and `SecurityArchitecture.md`/`FirestoreSchema.md`'s updated rows.

## Documentation Sync — closing F2, F4, F5

- **`SecurityArchitecture.md`** — the `rooms/{roomId}` row rewritten to match the real, deployed rules exactly (was still describing `hostUid`/seats). Every other row in that table is unchanged and still explicitly labeled as forward-looking design for collections that don't exist in code.
- **`ServiceArchitecture.md`** — `RoomService`'s method list corrected (`createRoom(playerId, roomName) → roomId`, not `createRoom(hostUid) → roomId (join code)`; `joinRoom` resolves the room object, not a seat index; `setReady` marked implemented; `transferHost`/`closeRoom`/`subscribeToRoom` marked still-not-implemented with precise scope notes).
- **`RoomLifecycle.md`** — rewritten in full. Every section now explicitly labeled **Implemented** or **Not Yet Implemented**, rather than presenting a single speculative narrative as if it were shipped behavior. The Reconnect, Host Transfer (beyond leave), Room Close (beyond empty-room), and Room Expiration sections are preserved as forward-looking design (per "do not invent future architecture" — nothing new was added, but nothing honest was deleted either) but are now unambiguous about not being built yet.
- **`FirestoreSchema.md`** — `rooms/{roomId}`'s field list gains `readyPlayers`; security requirements re-synced to the tightened rules above.

No architecture document beyond these four was touched — `MigrationPlan.md`, `ArchitectureDecisionLog.md`, `PlayerLifecycle.md`, and `BackendArchitecture.md` were re-read during this sprint's audit-sync pass and found to need no correction.

## Lobby Integration

One new small button, `Toggle Ready (current room)`, added next to the existing `Join a room by ID` link (same card, same visual style, no redesign). It operates on `lastRoomId` — a plain JS variable set when `createRoom`/`joinRoom` succeeds, reset on page reload — since no Room screen exists to show a real "current room" concept yet. This is explicitly the smallest possible implementation, not a preview of a future Room screen: no live display of who else is ready, no real-time update if another member's state changes. Clicking it calls `RoomService.setReady(lastRoomId, uid, !lastKnownReadyLocalFlag)` and shows a plain `alert()` confirming the new state — consistent with Sprint 3.2's established "no `UI.toast()` in Lobby" decision.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package. Summary: 22 automated tests in `tests/room-service.test.cjs` (Ready true/false, multiple players, idempotent no-op writes, non-member rejection, closed-room rejection, two genuinely concurrent scenarios against a Firestore-transaction-faithful mock, Firestore-unavailable handling, permission-adjacent rejection paths, leave-cleans-readyPlayers, zero regression on the original 3.2 create/join/leave suite); 18 new rules-simulation tests in `tests/rules-simulation.test.js` (29 total with the preserved 3.2.1 history) covering every legitimate and illegitimate mutation shape discussed above, including the one that surfaced the documented residual gap; real click-driven browser tests proving Lobby's new button calls `RoomService.setReady` correctly, toggles state correctly across two clicks, and refuses to act with a clear message when no room is known yet.
