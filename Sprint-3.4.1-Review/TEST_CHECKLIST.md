# Test Checklist — Sprint 3.4.1: Match Start Consistency & Security Hotfix

All tests below are real, executed tests — 59 automated tests in `tests/match-service.test.cjs`, 31 in `tests/room-service.test.cjs`, 61 in `tests/rules-simulation.test.js` (151 total), plus real click-driven Playwright browser tests. Mapped directly to the brief's 11 required testing scenarios.

## Requirement #1 — one client cannot update another player's profile

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | `players` update: a user updating their OWN `currentMatchId` — ALLOWED | **PASS** | `tests/rules-simulation.test.js`, direct 1:1 translation of `players/{uid}`'s existing, unchanged `isOwner(uid)` rule. |
| 2 | **SECURITY: userA attempts to update userB's `players/{userB}` document — DENIED** | **PASS** | Same file — this is the actual, real rules-layer enforcement point; explicitly named as the exact write shape the Sprint 3.4 bug attempted for every non-initiating room player. |

## Requirement #2 — MatchService no longer attempts cross-user profile writes

| # | Test | Result | Evidence |
|---|---|---|---|
| 3 | `startMatch`: never attempts to write `currentMatchId` via `PlayerService.updatePlayerProfile` at all | **PASS** | `tests/match-service.test.cjs` — a regression guard records every `PlayerService.updatePlayerProfile` call across the WHOLE test file and asserts none ever touched `currentMatchId`; checked immediately after the happy path AND again as a final whole-run assertion. |
| 4 | `startMatch` self-syncs currentMatchId via `SessionService.setCurrentMatchId(matchId)` — self only | **PASS** | Same file — confirms the correct, safe replacement path is actually used. |
| 5 | Idempotent (already-existing-match) `startMatch` return still self-syncs the caller | **PASS** | Same file. |
| 6 | Cross-service integration: the triggering client's own profile is self-synced via the real `RoomService.setReady()` → `MatchService.startMatch()` path | **PASS** | Same file, integration section. |

## Requirement #3 — each client can discover matchId from the room

| # | Test | Result | Evidence |
|---|---|---|---|
| 7 | `RoomService.loadRoom(roomId)` resolves the room document, including `matchId` once set | **PASS** | `tests/room-service.test.cjs` |
| 8 | `loadRoom` resolves `null` (not an error) for a nonexistent room | **PASS** | Same file. |
| 9 | `loadRoom` rejects clearly when `roomId` is missing, and when Firestore is unavailable | **PASS** (2 tests) | Same file. |
| 10 | Browser: a non-triggering tab discovers the match via `RoomService.loadRoom()` polling (not `SessionService.refresh()`) and self-syncs via `SessionService.setCurrentMatchId()` | **PASS** | Real click-driven Playwright test, Scenario 2. |
| 11 | Browser: the triggering tab navigates immediately from `setReady()`'s own `room.matchStart`, without waiting for the poll | **PASS** | Real click-driven Playwright test, Scenario 1. |

## Requirement #4 — a fabricated match with an unrelated roomId is denied

| # | Test | Result | Evidence |
|---|---|---|---|
| 12 | **SECURITY: `matches` create with `roomId` pointing at a room that doesn't exist — DENIED** | **PASS** | `tests/rules-simulation.test.js`, `isValidNewMatchV2`. |
| 13 | **SECURITY: room-side `matchId` change where the referenced match doesn't exist post-commit at all — DENIED** | **PASS** | Same file, `isValidMatchIdChangeV2` — proves setting `status`/`matchId` without actually creating the match in the same atomic write is rejected. |

## Requirement #5 — a non-room-member cannot create a match for that room

| # | Test | Result | Evidence |
|---|---|---|---|
| 14 | **SECURITY: `matches` create where the acting user is not in the room's `players[]` — DENIED** | **PASS** | `tests/rules-simulation.test.js`, `isValidNewMatchV2`. |
| 15 | **SECURITY: room-side starting transition where the acting user is not actually a room member — DENIED** | **PASS** | Same file, `isValidMatchIdChangeV2`. |

## Requirement #6 — a match cannot start while any player is not ready

| # | Test | Result | Evidence |
|---|---|---|---|
| 16 | **SECURITY: `matches` create where a room player is not in `readyPlayers` — DENIED** | **PASS** | `tests/rules-simulation.test.js`, `isValidNewMatchV2`. |
| 17 | **SECURITY: room-side starting transition attempted while a player isn't ready — DENIED** | **PASS** | Same file, `isValidMatchIdChangeV2`. |
| 18 | JS-layer: `startMatch` rejects when not everyone is ready (defense in depth, unchanged from Sprint 3.4, re-verified) | **PASS** | `tests/match-service.test.cjs`. |

## Requirement #7 — room.matchId must equal the new match document ID

| # | Test | Result | Evidence |
|---|---|---|---|
| 19 | **SECURITY: `matches` create where the room's post-commit `matchId` (simulated `getAfter()`) points at a DIFFERENT match — DENIED** | **PASS** | `tests/rules-simulation.test.js`, `isValidNewMatchV2`. |
| 20 | **SECURITY: room-side `getAfter(match).roomId` points at a DIFFERENT room — DENIED** | **PASS** | Same file, `isValidMatchIdChangeV2`. |

## Requirement #8 — match players must match room players

| # | Test | Result | Evidence |
|---|---|---|---|
| 21 | **SECURITY: `matches` create where `match.players` omits a real room member — DENIED** | **PASS** | `tests/rules-simulation.test.js`, `isValidNewMatchV2`. |
| 22 | **SECURITY: room-side `getAfter(match).players` doesn't match the room's own `players[]` — DENIED** | **PASS** | Same file, `isValidMatchIdChangeV2`. |

## Requirement #9 — two concurrent valid starts still create exactly one match

| # | Test | Result | Evidence |
|---|---|---|---|
| 23 | Two concurrent `MatchService.startMatch()` calls for the same room resolve to the same matchId and create exactly one document | **PASS** | `tests/match-service.test.cjs` (unchanged mechanism from Sprint 3.4, re-verified against the Sprint 3.4.1 code). |
| 24 | Two concurrent `RoomService.setReady()` calls (the real trigger path) still produce exactly one match; each result's own `matchStart` is either absent or correct, never mismatched | **PASS** (2 tests) | Same file, integration section. |

## Requirement #10 — existing RoomService and MatchService tests have no regression

| # | Test | Result | Evidence |
|---|---|---|---|
| 25 | Full Sprint 3.2/3.3 `RoomService` suite (createRoom/joinRoom/leaveRoom/setReady, concurrency, Firestore-unavailable) | **PASS** (22/22 unchanged) | `tests/room-service.test.cjs`. |
| 26 | Full Sprint 3.4 `MatchService`/integration suite, adapted for the new `matchStart`/self-sync contract | **PASS** (all re-verified) | `tests/match-service.test.cjs`. |
| 27 | Full Sprint 3.2.1/3.3/3.4 rules-simulation history | **PASS** (unchanged, preserved) | `tests/rules-simulation.test.js`. |

## Requirement #11 — no gameplay engine file changed

| # | Test | Result | Evidence |
|---|---|---|---|
| 28 | `git diff --stat` against `design-ui/engine/*` | **PASS** | Empty — confirmed no gameplay engine file appears in this sprint's change set. |
| 29 | `player-service.js` and the Match placeholder screen (`design-ui/match/`) unchanged | **PASS** | `git diff --stat` — neither appears in the change set. |
| 30 | No bidding/estimation/card-play/scoring/reconnect/matchmaking/chat/spectator code added | **PASS** | Confirmed by reading the full diff. |

## Task 3 — public API cleanup

| # | Test | Result | Evidence |
|---|---|---|---|
| 31 | `MatchService.createMatch` is `undefined` — removed from the public API | **PASS** | `tests/match-service.test.cjs`. |
| 32 | The tightened `firestore.rules` structurally reject the write shape `createMatch()` used to produce (a match created without a same-transaction room binding) | **PASS** | Direct consequence of Requirement #4/#7's rules tests — verified by inspection, not a separate isolated test. |

## Task 4 — match-start failure handling

| # | Test | Result | Evidence |
|---|---|---|---|
| 33 | A simulated transient `startMatch()` failure is observable via `room.matchStart.error`, not silently swallowed | **PASS** | `tests/match-service.test.cjs`. |
| 34 | `setReady()` itself still resolves (never rejects) even when the match-start attempt failed | **PASS** | Same file. |
| 35 | A failed attempt leaves the room genuinely retryable (`status` still `"waiting"`, no `matchId`) | **PASS** | Same file. |
| 36 | Retrying via the same idempotent `setReady(true)` call succeeds once the failure clears | **PASS** | Same file. |
| 37 | `room.matchStart` is always present on `setReady()`'s resolved value (not-all-ready, all-ready-but-unavailable, all-ready-and-available cases) | **PASS** (3 tests) | `tests/room-service.test.cjs`. |

## Scope / constraint verification

| # | Test | Result | Evidence |
|---|---|---|---|
| 38 | No UI redesign — Lobby's existing `alert()` pattern extended, not replaced; no new component | **PASS** | Confirmed by reading the diff. |
| 39 | Stays Firebase Spark-compatible — no Cloud Functions, no Blaze features | **PASS** | Confirmed by reading the diff; alternatives requiring Cloud Functions were explicitly considered and rejected (see `MatchInitialization.md`'s Issue 1). |
| 40 | `firestore.rules` structurally valid | **PASS** (structural check only) | Brace/paren balance confirmed; no live Firestore compiler available in this sandboxed session (same limitation noted since Sprint 2.6). |
| 41 | Real (unstubbed) Lobby stack still fails open cleanly | **PASS** | Same known sandbox CDN limitation as every prior sprint (`firebase is not defined`), zero crash, screen still renders. |

## Not performed (stated explicitly, per the brief's own honesty requirement)

- **Live Firebase Rules Unit Testing / emulator execution was NOT performed.** No Firebase CLI or local Java-backed emulator is available in this sandboxed session (same limitation noted since Sprint 2.6). `tests/rules-simulation.test.js` is a hand-translated JS simulation of the rule logic — it is **not equivalent to** actually executing Firestore's rule evaluator, and this sprint's `get()`/`exists()`/`getAfter()` checks add a further layer of approximation on top of that (they are modeled as explicit test parameters representing "what a real read would return," not by exercising real Firestore read semantics — memoization behavior, the exact error-vs-deny handling of a missing document's field access, the ~20-get()-call quota, etc.). This is disclosed here and in `MatchInitialization.md`'s Issue 2 — not claimed as live verification.
- `firestore.rules` remains an undeployed, reviewable artifact — the pending-manual-publish state established since Sprint 2.6 is unchanged.
