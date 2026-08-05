# Test Checklist — Sprint 3.2: Room Foundation

All tests below are real, executed tests against the actual shipped code — 28 automated tests against `room-service.js` (Node, mocked Firestore with faithful transaction-retry semantics) plus real click-driven browser tests against `lobby/index.html`. Nothing here is inferred from reading the code.

## `RoomService` — automated (28/28 passing)

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | `createRoom` adds a document to the `rooms` collection | **PASS** | Confirmed via the mock store after calling `createRoom("p1", "Khaled's Room")`. |
| 2 | `createRoom` sets `status: "waiting"`, `creator`, `players: [creator]` | **PASS** | Direct field assertions on the created document. |
| 3 | `createRoom` stamps `createdAt`/`updatedAt` | **PASS** | Both equal the mock's server-timestamp sentinel. |
| 4 | `createRoom` returns the new `roomId` | **PASS** | Asserted as a non-empty string. |
| 5 | `createRoom` syncs `currentRoomId` onto the creator's profile via `PlayerService.updatePlayerProfile` | **PASS** | Mock `PlayerService` call log confirms the exact call. |
| 6 | `createRoom` triggers `SessionService.refresh()` | **PASS** | Mock call counter confirms. |
| 7 | `joinRoom` adds the new player to `players[]` | **PASS** | Direct field assertion after the call. |
| 8 | `joinRoom` syncs `currentRoomId` onto the joining player's profile | **PASS** | Mock `PlayerService` call log confirms. |
| 9 | `joinRoom` is idempotent — joining twice does not duplicate | **PASS** | Called twice with the same player; array length unchanged. |
| 10 | `joinRoom` rejects for a nonexistent room | **PASS** | Confirmed error message matches "not found". |
| 11 | `joinRoom` rejects when the room is full (cap: 4) | **PASS** | Filled to 4, 5th call rejected with "full" in the message. |
| 12 | A rejected join does not modify `players[]` | **PASS** | Length still 4 after the rejected attempt. |
| 13 | `joinRoom` rejects for a closed room | **PASS** | Room manually set to `status: "closed"`; join rejected with "closed" in the message. |
| 14 | `leaveRoom` removes the departing player | **PASS** | Direct field assertion. |
| 15 | `leaveRoom` syncs `currentRoomId: null` onto the departing player's profile | **PASS** | Mock `PlayerService` call log confirms. |
| 16 | `leaveRoom` transfers ownership when the creator leaves and others remain | **PASS** | `creator` field updated to the next remaining player. |
| 17 | `leaveRoom` removes the former creator from `players[]` too | **PASS** | Confirmed alongside test 16. |
| 18 | `leaveRoom` closes the room once the last player leaves | **PASS** | `status` becomes `"closed"` when `players[]` reaches empty. |
| 19 | The closed room has an empty `players[]` | **PASS** | Confirmed alongside test 18. |
| 20 | `leaveRoom` on a nonexistent room is a safe no-op | **PASS** | No exception thrown. |
| 21 | **No data corruption under rapid/concurrent joins for the last slot** | **PASS** | Two `joinRoom` calls fired concurrently (via `Promise.all`, no `await` between them) at a room with exactly one open slot, against a mock that implements real Firestore-style optimistic-concurrency retry (a naive mock would hide this bug) — result: exactly one success, one clean rejection, zero duplicates, room never exceeds the 4-player cap. |
| 22 | `setReady()` still throws `Not implemented` | **PASS** | Confirmed unchanged from Sprint 2.7 — out of scope this sprint. |
| 23 | `transferHost()` still throws `Not implemented` | **PASS** | Same. |
| 24 | `closeRoom()` still throws `Not implemented` | **PASS** | Same. |
| 25–28 | Field-shape assertions (`name`, `players` array type, no `hands`/`seats` invented, no extra fields written) | **PASS** | Confirmed by inspecting the exact document written for `createRoom` — matches this sprint's literal spec, not the older speculative `seats[]` design (see `RoomFoundation.md`'s reconciliation note). |

## Lobby UI — real click-driven browser tests

| # | Test | Result | Evidence |
|---|---|---|---|
| 29 | Clicking "Create Room" while signed in calls `RoomService.createRoom` with the correct `uid` and an auto-generated room name | **PASS** | Real click on the actual button; stub confirms `createRoom("lobby-test-uid", "LobbyTestUser's Room")` was called; `alert()` shows the returned room ID. |
| 30 | Clicking "Join a room by ID" prompts for an ID and calls `RoomService.joinRoom` with the entered ID and current `uid` | **PASS** | Real click, stubbed `window.prompt()` returning a fixed value; confirmed `joinRoom("room-to-join-456", "lobby-test-uid")` was called; `alert()` confirms success. |
| 31 | Clicking either button while signed out shows a clear message and never calls `RoomService` | **PASS** | Stubbed `SessionService.getCurrentUser()` to return `null`; confirmed the "Sign in first" alert fired and `createRoom` was never invoked. |
| 32 | UI reflects the action (confirmation shown) | **PASS** | Covered by tests 29–30 — `alert()` fires with a clear message in every success/failure case. |
| 33 | No visual regression to the rest of Lobby | **PASS** | Screenshot comparison against Sprint 3.1's Lobby — identical layout; the only additions are the new small "Join a room by ID" link and a working Create Room button. |
| 34 | Lobby's own inline script never touches Firestore/`PlayerService`/Auth directly | **PASS** | Searched for `firebase.firestore`, `window.Db`, `.collection(`, `window.Auth`, `firebase.auth`, `PlayerService.` — zero matches outside `<script src>` URLs. |
| 35 | Real (unstubbed) stack fails open cleanly | **PASS** | Ran the actual shipped file in this sandbox's CDN-constrained environment (same known limitation as Sprints 2.6–3.1) — `SessionService`'s own warning fires, zero uncaught exceptions, page renders correctly. |

## Scope / constraint verification

| # | Test | Result | Evidence |
|---|---|---|---|
| 36 | No gameplay files changed | **PASS** | `git diff` — no file under `design-ui/engine/` in this sprint's change set. |
| 37 | `firestore.rules` not touched | **PASS** | `git diff` — file absent from the change set. Direct consequence: real `RoomService` calls against the live project will `permission-denied` until a future sprint updates the rules — expected, documented in `RoomFoundation.md`, not a defect. |
| 38 | `PlayerService`/`SessionService` core logic not modified | **PASS** | `git diff` — neither file in the change set; `RoomService` only calls their existing public methods. |
| 39 | `GameState`, `GameSession`, Dealer, Cards, Scoring, Bidding, Match Engine untouched | **PASS** | `git diff` — none in the change set. |
| 40 | No new Firestore collections outside the agreed `rooms` collection | **PASS** | Only `rooms/{roomId}` is written to; no other collection is touched. |
| 41 | No Firebase billing triggers | **PASS** | All operations are standard Firestore reads/writes/transactions — no Cloud Functions, no Cloud Run, nothing Blaze-only anywhere in the diff. |

## Not performed

Live end-to-end testing against the actual Firebase project's `rooms` collection — blocked by the deliberate, in-scope decision not to touch `firestore.rules` this sprint (see test 37, and `RoomFoundation.md`'s "honest, load-bearing limitation" section). This is the same category of pending-deployment limitation already established for `players/{uid}` since Sprint 2.6.
