# Test Checklist — Sprint 4.1: Turn Authority & Remote Play Validation

Every test explicitly labeled MOCKED. No SIMULATED checks (this sprint touches no `firestore.rules`). **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 718 automated tests total.

## Acceptance criteria, mapped to actual tests

| # | Criterion | Test(s) | Result |
|---|---|---|---|
| 1 | Only the player whose turn it is may perform gameplay actions | `match-adapter.test.cjs` "Local Authority" section + `turn-sync.test.cjs` "correct/wrong player attempts action" | **PASS** |
| 2 | Remote turn changes synchronize exactly once | `turn-sync.test.cjs` "new snapshot" / "turn advance" | **PASS** |
| 3 | No duplicated engine execution | `applyRemoteTurn()` never calls an engine reducer at all (code inspection) + duplicate-snapshot tests confirm zero state change | **PASS** |
| 4 | No duplicated rendering | `turn-sync.test.cjs` "duplicate snapshot" (GameSession.getTurn() unchanged across redeliveries) | **PASS** |
| 5 | No duplicated listeners | `turn-sync.test.cjs` "late subscriber" (onSnapshot call count stays 1) | **PASS** |
| 6 | No gameplay rule changes | Code inspection (`bidding-engine.js` untouched) + full regression suite | **PASS** |
| 7 | No Engine rewrite | Forbidden-scope sweep (empty diff on all engine files) | **PASS** |

## Task 6's required test list

| # | Requirement | Test(s) | Result |
|---|---|---|---|
| 1 | Duplicate snapshot | `match-adapter.test.cjs` (unit) + `turn-sync.test.cjs` (end-to-end) | **PASS** (4 checks) |
| 2 | Stale snapshot | `match-adapter.test.cjs` (unit) + `turn-sync.test.cjs` (end-to-end, version rollback) | **PASS** (3 checks) |
| 3 | New snapshot | `turn-sync.test.cjs` "new snapshot" | **PASS** (2 checks) |
| 4 | Turn advance | `turn-sync.test.cjs` "turn advance" (4-step sequence) + `match-adapter.test.cjs` | **PASS** (5 checks) |
| 5 | Late subscriber | `turn-sync.test.cjs` "late subscriber" | **PASS** (2 checks) |
| 6 | Wrong player attempts action | `match-adapter.test.cjs` + `turn-sync.test.cjs` "wrong player attempts action" | **PASS** (5 checks) |
| 7 | Correct player accepted | `match-adapter.test.cjs` + `turn-sync.test.cjs` "correct player accepted" | **PASS** (4 checks) |
| 8 | Listener restart | `turn-sync.test.cjs` "listener restart" (simulated disconnect/reconnect) | **PASS** (3 checks) |
| 9 | Listener duplicate | `turn-sync.test.cjs` "listener duplicate event" | **PASS** (1 check) |
| 10 | Adapter corruption | `match-adapter.test.cjs` "adapter corruption" (4 malformed-input cases) | **PASS** (5 checks) |
| 11 | Version rollback | `match-adapter.test.cjs` + `turn-sync.test.cjs` | **PASS** (3 checks) |
| 12 | GameSession consistency | `turn-sync.test.cjs` "GameSession consistency" | **PASS** (2 checks) |

## Task 1 — Turn Synchronization

| # | Test | Result |
|---|---|---|
| 1 | `startTurnSync()` delivers the initial snapshot and correctly applies the seeded turn owner | **PASS** |
| 2 | No second listener created across `startBidSync()` + `startTurnSync()` for the same match | **PASS** |
| 3 | No gameplay logic inside `MatchService` — code inspection confirms zero reference to `GameSession`/`setTurn`/any engine file | **PASS** |

## Task 2 — Remote Turn Application

| # | Test | Result |
|---|---|---|
| 1 | A well-formed turn update is applied and translated uid→seat | **PASS** |
| 2 | Never calls any Firestore write path | Code inspection | **PASS** |
| 3 | Only updates GameSession, only via `setTurn()` | Code inspection + all end-to-end tests | **PASS** |
| 4 | Malformed snapshots rejected (non-object, missing/non-numeric version, unresolvable turn uid, missing matchId) | **PASS** (5 checks) |

## Task 3 — Local Authority Validation

| # | Test | Result |
|---|---|---|
| 1 | `isLocalSeatsTurn()` true for the correct seat, false for every other seat | **PASS** |
| 2 | `assertLocalTurn()` throws `NOT_LOCAL_TURN` for the wrong seat, does not throw for the correct seat | **PASS** |
| 3 | Falls back to GameSession's own mirror when no matchDoc is given | **PASS** |
| 4 | Reads the general-purpose `matches/{matchId}.turn`, not the bidding-phase-specific `waitingFor` (design verification via code inspection) | **PASS** |

## Task 4 — Duplicate Protection

| # | Test | Result |
|---|---|---|
| 1 | Identical snapshot delivered twice causes no change to GameSession's turn mirror | **PASS** |
| 2 | ...causes no advancement of the adapter's own turn version gate | **PASS** |
| 3 | ...causes no re-run of engine logic (trivial — function never calls a reducer) | Code inspection | **PASS** |

## Task 5 — Adapter Isolation

| # | Test | Result |
|---|---|---|
| 1 | `design-ui/match-service.js` has zero reference to GameSession/setTurn/any engine file | Forbidden-scope sweep (`git diff --stat`, empty) + `turn-sync.test.cjs` source check | **PASS** |
| 2 | `match-adapter.js` is the only file calling `GameSession.setTurn()` on behalf of remote sync | Code inspection | **PASS** |

## Independent version registries

| # | Test | Result |
|---|---|---|
| 1 | `applyRemoteBid()`'s and `applyRemoteTurn()`'s version gates for the same matchId/version don't interfere | **PASS** |
| 2 | `resetSyncState(matchId)` clears BOTH registries for a single matchId | **PASS** |
| 3 | `resetSyncState()` (no argument) clears both registries globally | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (82/82 — 23 net new) |
| — | `tests/bid-sync.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/turn-sync.test.cjs` | MOCKED | **PASS** (26/26 — new) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (65/65) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (109/109) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **718/718** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| — | `turn-sync.test.cjs` stable across repeated runs (real, short timers for reconnect) | **PASS** | Re-run 3+ times, 26/26 every time |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore.
- No test exercises a real write-back of `bidding-engine.js`'s own computed turn into `matches/{matchId}.turn`, because no such write path exists yet — only the mirror-synchronization direction (Firestore → GameSession) is implemented and tested.
- No test exercises `isLocalSeatsTurn()`/`assertLocalTurn()` being called from an actual gameplay-write function, because none exists yet — only the gate itself is tested, called directly.
- No card play, trick resolution, score synchronization, or replay test exists, per the brief's explicit stop list.
