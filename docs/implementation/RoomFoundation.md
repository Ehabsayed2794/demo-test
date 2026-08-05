# Room Foundation — Sprint 3.2 Implementation Report

**Scope actually implemented:** `RoomService.createRoom` / `joinRoom` / `leaveRoom` activated with real Firestore logic, wired to two small, existing-card additions in Lobby (`Create Room` button now does something; a new `Join a room by ID` text link). No new screen, no Ready state, no chat, no matchmaking, no gameplay file touched.

## Files changed

| File | Change |
|---|---|
| `design-ui/room-service.js` | `createRoom`/`joinRoom`/`leaveRoom` implemented for real (Firestore transactions). `setReady`/`transferHost`/`closeRoom` remain `Not implemented` stubs — out of scope this sprint. |
| `design-ui/lobby/index.html` | `Create Room` button's dead `onclick` (pointed at a state with no screen) replaced with a real click handler calling `RoomService.createRoom`. One new small text link, `Join a room by ID`, added to the same existing "Play with Friends" card, using `window.prompt()` for the room ID (no new input field). Both use plain `alert()` for success/error. |

No other file was touched.

## Schema reconciliation — read this before extending `rooms/{roomId}` further

This sprint's literal spec calls for `{ creator, players: [] }`. The earlier, purely speculative `rooms/{roomId}` design in `docs/architecture/FirestoreSchema.md` (written during the Sprint "design only" phase, before any Room UI existed) used a different shape (`hostUid`, a fixed-length `seats[]` array). This implementation follows **this sprint's explicit instruction** (`creator`, `players: []`) rather than the older speculative draft, because the literal, current, approved task instruction is more authoritative than a design note written before any real usage existed to validate it against.

This is flagged here as a **known, deliberate divergence** rather than silently implemented as if no conflict existed. `docs/architecture/FirestoreSchema.md`'s `rooms/{roomId}` section should be updated to match this sprint's shape the next time that document is revisited — not done as part of this sprint, since rewriting an architecture document wasn't requested and doing so casually would be exactly the kind of scope creep this project has consistently avoided.

## What "SessionService is aware of the current roomId" means here — and how it was done without touching either service

Neither `PlayerService` nor `SessionService`'s own code was modified. Instead:
1. `players/{uid}` already has a `currentRoomId` field, already in `PlayerService`'s `ALLOWED_UPDATE_FIELDS` whitelist, since Sprint 2.6.
2. After a successful `createRoom`/`joinRoom`/`leaveRoom`, `RoomService` calls `PlayerService.updatePlayerProfile(playerId, { currentRoomId: roomId })` — an **existing public method**, called exactly as any other caller would.
3. It then calls `SessionService.refresh()` — also an **existing public method** — so the session's cached profile picks up the new value.

Both calls are wrapped so a failure in this sync step is logged and swallowed, never surfacing as a failure of the room action itself, which has already succeeded by that point.

## The honest, load-bearing limitation: Firestore Security Rules were not touched

Per this sprint's explicit instruction, `firestore.rules` was not modified. The `rooms` collection has no rule granting it any access — every path not explicitly listed in that file is denied by the existing catch-all. This means:

- **All 28 automated tests below pass against real, executed `room-service.js` code** — using an in-memory Firestore stub that faithfully mimics Firestore's transaction retry semantics (see Tests), not a re-implementation.
- **Against the actual live Firebase project, every `RoomService` call will fail with `permission-denied`** until a future, explicitly-authorized sprint updates the rules to grant `rooms` collection access. This is expected, not a bug — it's the direct, correct consequence of "do not touch Firebase Security Rules" being honored rather than quietly worked around. It is the same category of honest limitation already established in Sprints 2.6–3.1 for `players/{uid}`'s rules deployment.

## Deliberate scope decisions

- **No new "Room" screen.** The brief explicitly offered a choice ("navigates to a placeholder screen or shows confirmation"); this implementation uses `alert()` confirmations — the simpler option, and the one that adds zero new screens or navigation states, matching "no complex UI yet."
- **`window.prompt()` instead of a new input field.** Lobby has no existing join-by-ID control. Rather than add a text input (a larger, more redesign-adjacent change), the browser's native prompt is used — zero new form markup, one small button.
- **Plain `alert()`, not `UI.toast()`.** Lobby never loaded `shared-ui.js` (only Login/Profile do). Adding it just for this would be a bigger footprint than necessary; the brief explicitly permits a plain alert.
- **`setReady`/`transferHost`/`closeRoom` remain stubs.** Ready state and explicit host controls are explicitly out of scope. `leaveRoom` implements its own minimal, inline ownership-transfer/cleanup logic (see below) rather than calling those still-unimplemented methods.

## Ownership transfer and cleanup — "simplest valid strategy"

Implemented inline inside `leaveRoom`, inside the same transaction that removes the player:
- If the departing player was the **last** one in the room, the room's `status` becomes `"closed"`.
- If the departing player was the **creator** and others remain, `creator` transfers to the next remaining player in array order.
- Both are computed from the same transaction read that removes the player — no separate read, no window for a stale decision.

## Concurrency / rapid-action safety

`joinRoom` and `leaveRoom` both run inside `runTransaction()`. The test suite specifically verifies this isn't just decorative: two simulated concurrent `joinRoom` calls racing for a room's last open slot were run against a mock Firestore that implements real optimistic-concurrency retry (not just a plain read-then-write) — the result is exactly one success and one clean `"full"` rejection, with no duplicate entries and no slot overrun. See Tests.

## Tests performed

See `TEST_CHECKLIST.md` in the QA package. Summary: 28 automated tests against the real `room-service.js` code (create/join/leave, idempotency, not-found/closed/full rejections, ownership transfer, empty-room closure, and the concurrency test above), plus real click-driven browser tests proving Lobby's actual buttons call `RoomService` with the correct arguments and show the correct confirmation, including the signed-out guard path. `git diff` confirms only `room-service.js` and `lobby/index.html` were modified — every forbidden file, and `firestore.rules`, remain untouched.
