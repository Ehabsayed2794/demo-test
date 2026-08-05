# Test Checklist — Sprint 2.7: Service Layer Skeleton

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | `RoomService`'s 6 action methods (`createRoom`, `joinRoom`, `setReady`, `leaveRoom`, `transferHost`, `closeRoom`) each throw `Not implemented` | **PASS** | Automated test — each called with no args, error message matched against `/not implemented/i`. |
| 2 | `RoomService.subscribeToRoom()` returns a callable no-op function instead of throwing | **PASS** | Automated test — return value's `typeof === "function"`, confirmed callable. |
| 3 | `MatchService`'s 11 action methods each throw `Not implemented` | **PASS** | Same automated pattern as #1, all 11 methods. |
| 4 | `MatchService.subscribeToMatch()` returns a callable no-op function | **PASS** | Same pattern as #2. |
| 5 | `PresenceService.updateHeartbeat()` / `isOnline()` throw `Not implemented` | **PASS** | Automated test. |
| 6 | `PresenceService.subscribeToPresence()` returns a callable no-op function | **PASS** | Automated test. |
| 7 | `InventoryService`'s 3 methods throw `Not implemented` | **PASS** | Automated test. |
| 8 | `LeaderboardService`'s 3 methods throw `Not implemented` | **PASS** | Automated test. |
| 9 | `ShopService.getCatalog()` resolves to `[]` without throwing | **PASS** | Automated test — awaited the real returned promise, confirmed `Array.isArray(result) && result.length === 0`. |
| 10 | `ShopService.getItem()` resolves to `null` without throwing | **PASS** | Automated test. |
| 11 | `AnalyticsService.logEvent()` / `setUserProperties()` never throw | **PASS** | Automated test — called both with real arguments inside a try/catch, confirmed no exception. |
| 12 | All 7 service globals load and are defined as objects | **PASS** | Automated test — `require()`'d each real file under a `global.window = global` shim in Node and checked `typeof window.<Service> === "object"` for all seven. |
| 13 | No syntax errors in any of the 7 new files | **PASS** | `node --check` run against each file individually. **One real bug was found and fixed during this check**: a doc-comment in `match-service.js` contained the literal text `record*/complete*`, which prematurely closed the block comment and broke the whole file — corrected to remove the accidental `*/` sequence, then re-verified clean. |
| 14 | `PlayerService` was not modified | **PASS** | `git status`/`git diff` — `design-ui/player-service.js` does not appear in this sprint's change set. |
| 15 | `firebase-init.js` was not touched | **PASS** | Same `git status` check — file not in the change set. |
| 16 | Login screen was not modified | **PASS** | Same check — `design-ui/login/index.html` not in the change set. |
| 17 | Lobby screen was not modified | **PASS** | Same check — `design-ui/lobby/index.html` not in the change set. |
| 18 | `GameState`/`GameSession` were not modified | **PASS** | Same check — `login/game-state.js`, `lobby/game-state.js`, `engine/session.js` not in the change set. |
| 19 | No gameplay engine file (`bidding-engine.js`, `scoring-engine.js`, `dealer.js`, `cards.js`, `table-engine.js`) was modified | **PASS** | Same check — none of the five appear in the change set. |
| 20 | No new Firestore collections were created | **PASS** | No code in any of the 7 new files calls Firestore at all — confirmed by reading the files; there is no `firebase.firestore(`/`window.Db`/`.collection(` reference anywhere in them (this was true by construction, since every method either throws immediately or returns a static placeholder before any such call would occur). |
| 21 | No Firestore rules were deployed or modified | **PASS** | `firestore.rules` (from Sprint 2.6) does not appear in this sprint's change set; nothing was deployed this sprint (deployment requires a manual console step this session cannot perform, unchanged from Sprint 2.6's status). |
| 22 | No Cloud Functions, Cloud Run, paid Extensions, or Blaze-only feature was used | **PASS** | No such code exists anywhere in this sprint's files — confirmed by reading all 7 files in full; none reference any Firebase product beyond the plain object stubs themselves. |

## Not performed

None. Every test in scope for this sprint (an API-signature-only skeleton with no real logic) was directly executable and was actually run — there was no "requires live Firebase" or "requires a UI to click through" category this time, since nothing in this sprint touches Firestore, Auth, or any rendered screen.
