# Test Checklist — Sprint 3.3: Ready State Foundation

All tests below are real, executed tests — 22 automated tests in `tests/room-service.test.cjs`, 18 new automated tests in `tests/rules-simulation.test.js` (29 total with Sprint 3.2.1's preserved history), plus real click-driven browser tests against the actual shipped `lobby/index.html`.

## `RoomService.setReady` — automated (`tests/room-service.test.cjs`)

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Ready true | **PASS** | `setReady(roomId, "p1", true)` adds `p1` to `readyPlayers`; return value reflects it. |
| 2 | Ready false | **PASS** | `setReady(roomId, "p1", false)` removes `p1` from `readyPlayers`; other ready members unaffected. |
| 3 | Multiple players | **PASS** | `p1` and `p2` both ready, `p3` not — confirmed via direct array assertion. |
| 4 | Idempotent no-op writes | **PASS** | Calling `setReady` twice with the same value performs zero additional writes (transaction version counter unchanged) — direct response to the audit's "unnecessary writes" review item. |
| 5 | `setReady` only touches `readyPlayers`/`updatedAt` | **PASS** | `players`/`creator`/`status`/`name` all confirmed unchanged after a ready-toggle. |
| 6 | Rejects for a non-member | **PASS** | A uid never added via `joinRoom` is rejected with a clear "not a member" error. |
| 7 | Rejects for a nonexistent room | **PASS** | Clear "not found" error. |
| 8 | Rejects for a closed room | **PASS** | Clear "closed" error. |
| 9 | Concurrent ready updates (two different players) | **PASS** | Two `setReady` calls from different players fired via `Promise.all` — both land, neither is lost, against a mock with real Firestore-style transaction retry (not a naive mock that would hide this). |
| 10 | Concurrent ready updates (same player, racing) | **PASS** | Two identical `setReady(true)` calls from the same player racing — final `readyPlayers` has no duplicate entry. |
| 11 | `leaveRoom` keeps `readyPlayers` consistent | **PASS** | A player who was ready and then leaves is removed from `readyPlayers`, not just `players`. |
| 12 | Firestore unavailable | **PASS** | `setReady` surfaces a clear rejection (not a hang, not a silent success) when the underlying transaction rejects. |
| 13 | Zero regression on Sprint 3.2's create/join/leave | **PASS** | All original scenarios (returns roomId, correct initial fields, idempotent join, full/closed/not-found rejections, ownership transfer, empty-room closure, concurrent-join race safety) re-verified passing in the same file. |
| 14 | `transferHost`/`closeRoom` remain stubs | **PASS** | Confirmed still throw `Not implemented` — unchanged, out of scope. |

## Rules simulation — automated (`tests/rules-simulation.test.js`)

| # | Test | Result | Evidence |
|---|---|---|---|
| 15 | `create` v2: valid new room allowed | **PASS** | `creator`/`players`/`readyPlayers`/`status` all correct — allowed. |
| 16 | `create` v2: extra non-whitelisted field denied (closes F6) | **PASS** | A fabricated extra field (e.g. `matchId`) denied. |
| 17 | `create` v2: `players.length == 2` at creation denied (closes F7) | **PASS** | Fabricating a second "member" who never joined — denied. |
| 18 | `create` v2: non-empty `readyPlayers` at creation denied | **PASS** | |
| 19 | `create` v2: `status` other than `"waiting"` at creation denied | **PASS** | |
| 20 | `update` v2: marking yourself ready — allowed | **PASS** | |
| 21 | `update` v2: un-readying yourself — allowed | **PASS** | |
| 22 | **Security: forging another member's ready flag — denied** | **PASS** | `userC` attempting to mark `userB` ready is denied by `isSelfOnlyChange()`. |
| 23 | **Security: marking two different uids ready in one write — denied** | **PASS** | Confirms the rule blocks bulk edits, not just wrong-target edits. |
| 24 | **Security (F3): renaming the room — denied** | **PASS** | `name` is not in the update field whitelist. |
| 25 | **Security (F3): rewriting `createdAt` — denied** | **PASS** | Not in the update field whitelist. |
| 26 | **Known, documented, unclosed gap: self-promotion to `creator` without leaving — currently ALLOWED** | **PASS** (confirms the documented limitation, not a failure) | `userC`, already a member, reassigns `creator` to themself with no membership change — allowed, because rules cannot distinguish this from a legitimate transfer without Cloud Functions. Recorded in `firestore.rules`, this test, and `ReadyStateFoundation.md` — not silently left unverified. |
| 27 | `update` v2: legitimate ownership transfer on leave — allowed | **PASS** | |
| 28 | Security: reassigning `creator` to a fabricated non-member uid — denied | **PASS** | |
| 29 | Security: arbitrary `status` string — denied | **PASS** | |
| 30 | Security: closing a non-empty room — denied | **PASS** | Matches `leaveRoom()`'s actual only path to `"closed"`. |
| 31 | `update` v2: last player leaving legitimately closes the room — allowed | **PASS** | |
| 32 | Security: a true non-member (absent from both old and new `players[]`) denied | **PASS** | |
| — | 11 preserved Sprint 3.2.1 historical tests (literal-brief-rule proof, etc.) | **PASS** (unchanged) | Kept for context, not re-derived. |

## Lobby integration — real click-driven browser tests

| # | Test | Result | Evidence |
|---|---|---|---|
| 33 | Clicking "Toggle Ready" after creating a room calls `RoomService.setReady(roomId, uid, true)`, then `false` on a second click | **PASS** | Real clicks on the actual buttons; stub confirms both calls with correct, alternating arguments; `alert()` shows the correct state each time. |
| 34 | Clicking "Toggle Ready" with no room yet shows a clear message and never calls `RoomService` | **PASS** | Confirmed `setReady` was never invoked; correct alert shown. |
| 35 | No visual regression to the rest of Lobby | **PASS** | Screenshot comparison against Sprint 3.2 — identical layout aside from the one new small button in the existing card. |
| 36 | Lobby's own inline script never touches Firestore/`PlayerService`/Auth directly | **PASS** | Searched for `firebase.firestore`, `window.Db`, `.collection(`, `window.Auth`, `firebase.auth`, `PlayerService.` — zero matches outside `<script src>` URLs. |
| 37 | Real (unstubbed) stack fails open cleanly | **PASS** | Same known sandbox CDN limitation as every prior sprint — `SessionService`'s own warning fires, zero uncaught exceptions, page renders correctly. |

## Scope / constraint verification

| # | Test | Result | Evidence |
|---|---|---|---|
| 38 | No gameplay files changed | **PASS** | `git diff` — no file under `design-ui/engine/` in this sprint's change set. |
| 39 | No service boundary violations | **PASS** | `PlayerService`/`SessionService` untouched; `RoomService` still only calls their existing public methods. |
| 40 | `PlayerService`/`SessionService` unchanged | **PASS** | `git diff` — neither file appears in the change set. |
| 41 | No Matchmaking/Chat/Room Browser/Game Start/Card Dealing/Realtime Gameplay code added | **PASS** | Confirmed by reading the full diff. |
| 42 | `firestore.rules` structurally valid | **PASS** (structural check only) | Brace/paren balance confirmed; no live Firestore compiler available in this sandboxed session (same limitation noted since Sprint 2.6). |

## Not performed

Live deployment/testing against the actual Firebase project's `rooms` collection — `firestore.rules` remains an undeployed, reviewable artifact (same pending-publish state established since Sprint 2.6). A full run against the real Firebase Rules Unit Testing emulator was also not performed — no Firebase CLI or local Java-backed emulator is available in this sandboxed session; the JS-based simulation is an honest, lower-fidelity substitute, documented as such.
