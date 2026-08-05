# Test Checklist — Sprint 3.4: Match Initialization & Game Start

All tests below are real, executed tests — 50 automated tests in `tests/match-service.test.cjs`, 16 new automated tests in `tests/rules-simulation.test.js` (45 total with Sprint 3.2.1/3.3's preserved history), zero-regression re-run of the 22 `tests/room-service.test.cjs` tests, plus real click-driven browser tests (Playwright + headless Chromium) against the actual shipped `lobby/index.html` and the new `match/index.html`.

## `MatchService` — automated (`tests/match-service.test.cjs`)

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | `createMatch` resolves a matchId | **PASS** | |
| 2 | `createMatch`: document shape (`roomId`/`players`/`status`/`currentRound`/`dealer`/`turn`) matches the actual shipped fields | **PASS** | |
| 3 | `createMatch`: `gameState` is the documented TODO placeholder, not fabricated dealt hands | **PASS** | `gameState.initialized === false` and the TODO message mentions `Deck`. |
| 4 | `createMatch` does NOT write the room document | **PASS** | That guarantee belongs to `startMatch`. |
| 5 | `createMatch` rejects for a nonexistent room | **PASS** | |
| 6 | `startMatch`: happy path — matchId resolved, match document created | **PASS** | |
| 7 | `startMatch`: room status becomes `"in_game"`, `room.matchId` set | **PASS** | |
| 8 | `startMatch`: `currentMatchId` mirrored onto every player's profile, `SessionService.refresh()` called | **PASS** | |
| 9 | `startMatch` rejects when not everyone is ready (defense in depth) | **PASS** | Room left untouched on rejection. |
| 10 | `startMatch` rejects for a nonexistent room | **PASS** | |
| 11 | **Duplicate Start prevented** — `startMatch` called twice sequentially for the same room returns the same matchId, creates no second document | **PASS** | |
| 12 | **Two users pressing Ready simultaneously cannot create two matches** — two concurrent `startMatch` calls for the same room resolve to the same matchId and create exactly ONE match document | **PASS** | Against a Firestore-transaction-faithful mock supporting multi-collection transactions with real optimistic-concurrency retry. |
| 13 | `loadMatch` resolves the match document | **PASS** | |
| 14 | `loadMatch` resolves `null` (not an error) for a nonexistent match | **PASS** | |
| 15 | **`MatchService` subscriptions work** — `subscribeToMatch` delivers an immediate snapshot, then a second snapshot on change | **PASS** | |
| 16 | `subscribeToMatch`'s unsubscribe function actually stops delivery | **PASS** | |
| 17 | `subscribeToMatch` delivers `(null, null)` for a nonexistent match, not a thrown error | **PASS** | |
| 18 | Firestore-unavailable handling for `createMatch`/`startMatch`/`subscribeToMatch` | **PASS** (3 tests) | Clear rejections/error callbacks, never a hang or silent success. |
| 19 | **No gameplay engine modified** — all ten still-stubbed methods (`submitDashCall`/`submitBid`/`submitPass`/`declareTrump`/`submitEstimate`/`playCard`/`resolveTrick`/`completeRound`/`advanceToNextRound`/`endMatch`) still throw `Not implemented` | **PASS** (10 tests) | |

## Cross-service integration — automated (`tests/match-service.test.cjs`, same file)

| # | Test | Result | Evidence |
|---|---|---|---|
| 20 | Room not yet all-ready after only one player readies — no match triggered | **PASS** | |
| 21 | **`RoomService.setReady()` triggers `MatchService.startMatch()` automatically** (the real Game Start Flow, not `MatchService` called directly) | **PASS** | |
| 22 | Room status becomes `"in_game"` via the triggered `startMatch` | **PASS** | |
| 23 | The triggered match document actually exists, with the room's real players | **PASS** | |
| 24 | **Two players calling `setReady` concurrently (real trigger path) still produce exactly ONE match** | **PASS** | Proves the atomicity guarantee holds through the real trigger, not just direct `MatchService` calls. |
| 25 | Room ends up with a consistent `matchId` matching the one match document | **PASS** | |
| 26 | A partial-ready room (not everyone ready) never triggers `startMatch` | **PASS** | |

## Rules simulation — automated (`tests/rules-simulation.test.js`)

| # | Test | Result | Evidence |
|---|---|---|---|
| 27 | `startMatch()`'s own write — `status` becomes `"in_game"` and `matchId` set together, by a member — allowed | **PASS** | |
| 28 | **Security:** `status` becomes `"in_game"` WITHOUT `matchId` in the same write — denied | **PASS** | |
| 29 | **Security:** `matchId` set WITHOUT `status` becoming `"in_game"` — denied | **PASS** | |
| 30 | **Security:** `"in_game"` attempted from a room that wasn't `"waiting"` — denied | **PASS** | |
| 31 | `matchId`, once set, is immutable — changing it to a different value — denied | **PASS** | |
| 32 | A write that leaves matchId unchanged on an already-`in_game` room — allowed | **PASS** | |
| 33 | **Security:** an outsider cannot start a match on someone else's room | **PASS** | |
| 34 | `matches` create: a valid new match document from a seated player — allowed | **PASS** | |
| 35 | **Security:** the acting user isn't one of the match's players — denied | **PASS** | |
| 36 | **Security:** an extra, non-whitelisted field on match creation — denied | **PASS** | |
| 37 | `matches` create: `status` other than `"starting"` — denied | **PASS** | |
| 38 | `matches` create: `currentRound` other than 1 — denied | **PASS** | |
| 39 | **Security:** `dealer`/`turn` set to a uid who isn't a real member — denied (2 tests) | **PASS** | |
| 40 | `matches` get: a seated player may read the match — allowed | **PASS** | |
| 41 | **Security:** a non-seated authenticated user may NOT read the match | **PASS** | Match documents are not globally readable, unlike `rooms/{roomId}`. |
| — | 29 preserved Sprint 3.2.1/3.3 historical tests | **PASS** (unchanged) | Kept for context, not re-derived. |

## Lobby → Match navigation — real click-driven browser tests (Playwright + headless Chromium)

| # | Test | Result | Evidence |
|---|---|---|---|
| 42 | Clicking "Create Room" calls `RoomService.createRoom` with the signed-in uid | **PASS** | |
| 43 | Clicking "Toggle Ready" calls `RoomService.setReady(roomId, uid, true)` | **PASS** | |
| 44 | No navigation happens before a `matchId` is observed | **PASS** | |
| 45 | **Lobby navigates correctly** once `currentMatchId` appears on the profile | **PASS** | `GameState.goTo("Gameplay", { file: "../match/index.html", force: true, data: {...} })` called exactly once with the correct arguments. |
| 46 | `force: true` is used (Gameplay isn't a listed transition from Lobby in the mock `TRANSITIONS` graph) — `game-state.js` itself is unmodified | **PASS** | |
| 47 | The real (unmodified) `GameState.goTo()` actually applies the forced transition and persists the matchId via its existing data mechanism | **PASS** | |
| 48 | A second profile update with the same `matchId` does not trigger a second navigation | **PASS** | |
| 49 | **Regression/scope guard:** a fresh Lobby load with a stale `currentMatchId` but no in-tab `lastRoomId` does NOT auto-navigate | **PASS** | Confirms this is the in-session Game Start flow, not a reconnect feature (explicitly out of scope). |

## Match placeholder screen — real click-driven browser tests

| # | Test | Result | Evidence |
|---|---|---|---|
| 50 | Match screen calls `MatchService.subscribeToMatch` with the matchId from `GameState`'s data-handoff | **PASS** | |
| 51 | Match screen displays the real `status`/`matchId`/`currentRound`/`dealer` — no fabricated fields | **PASS** | |
| 52 | Match screen lists the real players and tags the actual dealer | **PASS** | |
| 53 | Match screen honestly states gameplay isn't implemented yet — no fabricated game table | **PASS** | |
| 54 | Opened with no matchId: shows a clear message, zero uncaught page errors | **PASS** | |
| 55 | Real (unstubbed) stack fails open cleanly | **PASS** | Same known sandbox CDN limitation as every prior sprint — `firebase is not defined`, zero crash, screen still renders. |

## Scope / constraint verification

| # | Test | Result | Evidence |
|---|---|---|---|
| 56 | No gameplay engine files changed | **PASS** | `git diff --stat` against `design-ui/engine/*` — empty. |
| 57 | `PlayerService`/`SessionService` unchanged | **PASS** | `git diff --stat` — neither file appears in the change set. |
| 58 | No bidding/estimation/card-play/matchmaking/spectator/reconnect/chat/room-browser code added | **PASS** | Confirmed by reading the full diff. |
| 59 | Firestore: no undocumented collection created | **PASS** | Only `matches/{matchId}`, already named/expected in `FirestoreSchema.md` since before this sprint. |
| 60 | `firestore.rules` structurally valid | **PASS** (structural check only) | Brace/paren balance confirmed; no live Firestore compiler available in this sandboxed session (same limitation noted since Sprint 2.6). |
| 61 | Zero regression on Sprint 3.2/3.3's `RoomService` suite | **PASS** | All 22 `tests/room-service.test.cjs` tests still pass after the `setReady`/`maybeStartMatch` change. |

## Not performed

Live deployment/testing against the actual Firebase project's `matches`/`rooms` collections — `firestore.rules` remains an undeployed, reviewable artifact (same pending-publish state established since Sprint 2.6). A full run against the real Firebase Rules Unit Testing emulator was also not performed — no Firebase CLI or local Java-backed emulator is available in this sandboxed session; the JS-based simulation is an honest, lower-fidelity substitute, documented as such. `Dealer.dealHands()` was not exercised (it cannot be — see `MatchInitialization.md`'s `Deck` finding); no test asserts real dealt-hand data because none exists yet.
