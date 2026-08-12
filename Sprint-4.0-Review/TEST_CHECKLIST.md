# Test Checklist — Sprint 4.0: Online Bidding Synchronization (Authority Layer)

Every test explicitly labeled MOCKED. No SIMULATED checks (this sprint touches no `firestore.rules`). **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 690 automated tests total.

## Acceptance criteria, mapped to actual tests

| # | Criterion | Test(s) | Result |
|---|---|---|---|
| 1 | A remote player submits a bid | `bid-sync.test.cjs` "remote bid" section | **PASS** |
| 2 | Every connected client receives exactly one synchronized update | `bid-sync.test.cjs` "late subscriber" (onSnapshot call count stays 1) | **PASS** |
| 3 | The bidding engine executes exactly once | `bid-sync.test.cjs` "Acceptance" checks + `match-adapter.test.cjs` "exactly one emit() call" | **PASS** |
| 4 | No duplicated execution | `bid-sync.test.cjs` "duplicate snapshot", `match-adapter.test.cjs` duplicate-version tests | **PASS** |
| 5 | No stale execution | `bid-sync.test.cjs` "stale snapshot / version rollback", `match-adapter.test.cjs` stale-version test | **PASS** |
| 6 | No duplicate rendering | `bid-sync.test.cjs` "duplicate snapshot" (estimate/turn unchanged across redeliveries) | **PASS** |
| 7 | No duplicated listeners | `bid-sync.test.cjs` "late subscriber" (onSnapshot call count) | **PASS** |
| 8 | No gameplay rule changes | Code inspection (`bidding-engine.js` untouched) + full regression suite | **PASS** |
| 9 | No Engine rewrite | Forbidden-scope sweep (empty diff on all engine files) | **PASS** |
| 10 | No Firestore writes outside MatchService | Code inspection (`applyRemoteBid()`/`startBidSync()` never call `db()`/`.update()`) | **PASS** |

## Task 6's required test list

| # | Requirement | Test(s) | Result |
|---|---|---|---|
| 1 | Duplicate snapshot | `match-adapter.test.cjs` (unit) + `bid-sync.test.cjs` (end-to-end) | **PASS** (4 checks) |
| 2 | Stale snapshot | `match-adapter.test.cjs` (unit) + `bid-sync.test.cjs` (end-to-end, version rollback) | **PASS** (4 checks) |
| 3 | New snapshot | `bid-sync.test.cjs` "remote bid" | **PASS** (5 checks) |
| 4 | Multiple sequential bids | `bid-sync.test.cjs` "multiple sequential bids" | **PASS** (6 checks) |
| 5 | Remote bid | `bid-sync.test.cjs` "remote bid" section | **PASS** |
| 6 | Local bid | `bid-sync.test.cjs` "local bid vs. remote bid" | **PASS** (3 checks) |
| 7 | Late subscriber | `bid-sync.test.cjs` "late subscriber" | **PASS** (3 checks) |
| 8 | Listener restart | `bid-sync.test.cjs` "listener restart" (simulated disconnect/reconnect) | **PASS** (3 checks) |
| 9 | Listener duplicate event | `bid-sync.test.cjs` "listener duplicate event" | **PASS** (1 check) |
| 10 | Adapter corruption | `match-adapter.test.cjs` "adapter corruption" (4 malformed-input cases) | **PASS** (5 checks) |
| 11 | Version rollback | `match-adapter.test.cjs` + `bid-sync.test.cjs` | **PASS** (4 checks) |
| 12 | GameSession consistency | `bid-sync.test.cjs` "GameSession consistency" | **PASS** (3 checks) |

## Task 1 — Bid Sync Pipeline

| # | Test | Result |
|---|---|---|
| 1 | `startBidSync()` delivers the initial snapshot without applying anything (no bid yet) | **PASS** |
| 2 | No second listener created across repeated `startBidSync()` calls for the same match | **PASS** |
| 3 | The pipeline reuses `MatchService.subscribeToMatch()` verbatim (no reimplemented sync logic) | Code inspection | **PASS** |

## Task 2 — Remote Bid Application

| # | Test | Result |
|---|---|---|
| 1 | A well-formed remote bid for the correct waiting seat is applied | **PASS** |
| 2 | Never calls any Firestore write path | Code inspection | **PASS** |
| 3 | Only updates GameSession, and only through bidding-engine.js | Code inspection + all end-to-end tests | **PASS** |
| 4 | Malformed snapshots rejected (non-object, missing version, non-numeric version, missing bids) | **PASS** (5 checks) |

## Task 3 — Version Validation

| # | Test | Result |
|---|---|---|
| 1 | Equal version rejected (no equality acceptance) | **PASS** |
| 2 | Lower version rejected (no rollback) | **PASS** |
| 3 | Version gap tolerated (only monotonic increase required) | **PASS** (implicit in sequential-bids test) |

## Task 4 — Duplicate Protection

| # | Test | Result |
|---|---|---|
| 1 | Identical snapshot delivered twice causes no re-render (estimate unchanged) | **PASS** |
| 2 | ...causes no re-run of bidding logic (emit() not called again) | **PASS** |
| 3 | ...causes no replayed engine state (turn pointer unchanged) | **PASS** |
| 4 | A local bid's own echo is not re-applied (content-level idempotency, distinct from version gating) | **PASS** |

## Task 5 — Engine Isolation

| # | Test | Result |
|---|---|---|
| 1 | `design-ui/match-service.js` has zero reference to GameSession/BiddingEngine/any engine file | Forbidden-scope sweep (`git diff --stat`, empty) | **PASS** |
| 2 | `match-adapter.js` is the only file calling `global.BiddingEngine.emit()` on behalf of remote sync | Code inspection | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (59/59 — 17 net new) |
| — | `tests/bid-sync.test.cjs` | MOCKED | **PASS** (39/39 — new) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (65/65) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (109/109) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **690/690** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| — | `bid-sync.test.cjs` stable across repeated runs (real, short timers for reconnect) | **PASS** | Re-run 3+ times, 39/39 every time |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore.
- No test exercises `DASH`/`AUCTION`/`CONFIRM` phase synchronization, because none is implemented — only `SubmitFinalEstimate` is wired, per this sprint's documented scope decision.
- No UI wiring test exists because no screen calls `startBidSync()`/`applyRemoteBid()` yet.
- No card play, trick resolution, scoring, or turn authority test exists, per the brief's explicit stop list.
