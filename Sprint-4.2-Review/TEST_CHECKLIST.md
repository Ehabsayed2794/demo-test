# Test Checklist — Sprint 4.2: Online Card Synchronization (Engine Authority)

Every claim below is labeled MOCKED, SIMULATED, or REAL — never mixed, per this sprint's explicit instruction. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 842 automated tests total.

## Task 9 — Architecture Verification (performed first)

| # | Check | Label | Result |
|---|---|---|---|
| 1 | `table-engine.js` exposes `emit()`/`getState()` sufficient for card sync, mirroring `bidding-engine.js`'s proven Sprint 4.0 shape | **REAL** (direct code reading + a manual `node -e` smoke test against the real engine, before any formal test file existed) | **PASS — no missing API, no engine change needed** |

## Acceptance criteria, mapped to actual tests

| # | Criterion | Test(s) | Result |
|---|---|---|---|
| 1 | A legal card played by one player appears exactly once on every connected client | `card-sync.test.cjs` "remote card"/"multiple sequential cards" | **PASS** |
| 2 | The gameplay engine executes exactly once | `card-sync.test.cjs` "Acceptance" checks + `match-adapter.test.cjs` "exactly one emit() call" | **PASS** |
| 3 | No duplicated execution | `card-sync.test.cjs` "duplicate snapshot", `match-adapter.test.cjs` duplicate-version tests | **PASS** |
| 4 | No duplicated rendering | `card-sync.test.cjs` "duplicate snapshot" (plays/turn unchanged across redeliveries) | **PASS** |
| 5 | No duplicated listeners | `card-sync.test.cjs` "late subscriber" (onSnapshot call count stays 1) | **PASS** |
| 6 | No gameplay rules duplicated | Code inspection (`submitCard()`/`isValidCardSubmission()` are generic-shape-only) | **PASS** |
| 7 | No gameplay rules rewritten | Forbidden-scope sweep (`table-engine.js` untouched) | **PASS** |
| 8 | No Firestore validation of card legality | Code inspection (`isValidCardSubmission()` checks shape, not legality) | **PASS** |
| 9 | No Engine rewrite | Forbidden-scope sweep (empty diff on all engine files) | **PASS** |

## Task 7's required test list

| # | Requirement | Test(s) | Result |
|---|---|---|---|
| 1 | Valid card sync | `card-sync.test.cjs` "remote card" | **PASS** (5 checks) |
| 2 | Duplicate snapshot | `match-adapter.test.cjs` (unit) + `card-sync.test.cjs` (end-to-end) | **PASS** (4 checks) |
| 3 | Stale snapshot | `match-adapter.test.cjs` (unit) + `card-sync.test.cjs` (end-to-end, version rollback) | **PASS** (3 checks) |
| 4 | Late subscriber | `card-sync.test.cjs` "late subscriber" | **PASS** (3 checks) |
| 5 | Listener restart | `card-sync.test.cjs` "listener restart" (simulated disconnect/reconnect) | **PASS** (3 checks) |
| 6 | Listener duplicate | `card-sync.test.cjs` "listener duplicate event" | **PASS** (1 check) |
| 7 | Multiple sequential cards | `card-sync.test.cjs` "multiple sequential cards" (full 4-play trick) + `submit-card.test.cjs` | **PASS** (7 checks) |
| 8 | Remote card | `card-sync.test.cjs` "remote card" section | **PASS** |
| 9 | Local card | `card-sync.test.cjs` "local card vs. remote card" | **PASS** (2 checks) |
| 10 | Wrong turn rejection | `card-sync.test.cjs` "wrong turn rejection" (via `assertLocalTurn()`) | **PASS** (2 checks) |
| 11 | Adapter corruption | `match-adapter.test.cjs` (5 malformed-input cases) + `card-sync.test.cjs` (end-to-end) | **PASS** (7 checks) |
| 12 | GameSession consistency | `card-sync.test.cjs` "GameSession consistency" | **PASS** (2 checks) |
| 13 | Version rollback | `match-adapter.test.cjs` + `card-sync.test.cjs` | **PASS** (3 checks) |
| 14 | Regression | Full suite re-run (below) | **PASS** |

## Task 1 — Card Submission

| # | Test | Result |
|---|---|---|
| 1 | `submitCard()` uses a real Firestore transaction | `submit-card.test.cjs` "normal card"/conflict handling | **PASS** |
| 2 | Calls MatchAdapter only (uidToSeat, read-only) | Code inspection + `submit-card.test.cjs` "seat resolution" | **PASS** |
| 3 | Does NOT evaluate card legality | Code inspection (no follow-suit/hand/turn check anywhere in the function) | **PASS** |
| 4 | Only persists synchronized state (append to cardLog, set lastCardSeat, version+1) | `submit-card.test.cjs` "normal card"/"sequential cards" | **PASS** |

## Task 2 — Remote Card Application

| # | Test | Result |
|---|---|---|
| 1 | Receives a Firestore snapshot | `card-sync.test.cjs` full pipeline | **PASS** |
| 2 | Translates through MatchAdapter | Code inspection (entries already seat-keyed at write time — see EngineAdapter.md) | **PASS** |
| 3 | Calls ONLY the existing gameplay engine (`TableEngine.emit()`) | Code inspection + `match-adapter.test.cjs` "exactly one emit() call" | **PASS** |
| 4 | Updates GameSession | `card-sync.test.cjs` "Acceptance" (via TableEngine's own reducer) | **PASS** |
| 5 | Never mutates Firestore | Code inspection (no db()/write-path reference) | **PASS** |

## Task 3 — Authority Gate

| # | Test | Result |
|---|---|---|
| 1 | Verifies `assertLocalTurn()` (Sprint 4.1's EXISTING function, reused verbatim — no new function) | `card-sync.test.cjs` "wrong turn rejection"/"correct player accepted" | **PASS** |
| 2 | If false: reject locally, no Firestore write | Code inspection (assertLocalTurn() throws before any write path is ever reached) | **PASS** |

## Task 4 — Duplicate Protection

| # | Test | Result |
|---|---|---|
| 1 | Identical snapshot delivered twice causes no additional play | `card-sync.test.cjs` "duplicate snapshot" | **PASS** |
| 2 | ...causes no additional turn advance | `card-sync.test.cjs` "duplicate snapshot" | **PASS** |
| 3 | ...causes no additional GameSession modification | Trivial — no re-emit occurs (code inspection) | **PASS** |
| 4 | ...causes no re-render (three redundant re-deliveries) | `card-sync.test.cjs` "listener duplicate event" | **PASS** |

## Task 5 — Version Gate

| # | Test | Result |
|---|---|---|
| 1 | Older versions rejected, never rolled back | `match-adapter.test.cjs` + `card-sync.test.cjs` "version rollback" | **PASS** |
| 2 | Equal versions rejected (duplicate) | `match-adapter.test.cjs` "Task 4 (duplicate)" | **PASS** |
| 3 | Malformed snapshots rejected (non-object, non-numeric version, non-array cardLog, missing matchId) | `match-adapter.test.cjs` "adapter corruption" | **PASS** (5 checks) |

## Task 6 — Adapter Isolation

| # | Test | Result |
|---|---|---|
| 1 | `design-ui/match-adapter.js` is the ONLY file calling `TableEngine.emit()`/`getState()` on behalf of remote sync | Code inspection | **PASS** |
| 2 | `design-ui/match-service.js` has zero reference to `GameSession`/`BiddingEngine`/`TableEngine` | Forbidden-scope sweep (`git diff --stat`, empty on all engine files) | **PASS** |
| 3 | The one new `MatchService → MatchAdapter` edge is read-only, translation-only | Code inspection (`uidToSeat()` is pure, no engine/Firestore call) | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (100/100 — 18 net new) |
| — | `tests/bid-sync.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/turn-sync.test.cjs` | MOCKED | **PASS** (26/26) |
| — | `tests/card-sync.test.cjs` | MOCKED | **PASS** (41/41 — new) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (67/67 — 2 net new) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/submit-card.test.cjs` | MOCKED | **PASS** (32/32 — new) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (140/140 — 31 net new) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **842/842** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| — | `card-sync.test.cjs` stable across repeated runs (real, short timers for reconnect) | **PASS** | Re-run 3+ times, 41/41 every time |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore.
- No test exercises a real write-back of `table-engine.js`'s own computed turn into `matches/{matchId}.turn` — only the mirror-synchronization direction, inherited from Sprint 4.1, is implemented.
- No test exercises trick resolution or scoring driven BY this sprint's own sync pipeline (a full trick's 4 plays ARE tested — resolving it uses `TableEngine.resolveTrick()` directly, the pre-existing, unmodified function, not anything new this sprint).
- `firestore.rules`' `isValidCardSubmission()` does not independently re-verify every earlier `cardLog` entry is unchanged (a documented CEL expressiveness gap) — no SIMULATED test can prove what the rule itself does not check; this is stated as a real limitation, not tested as a false negative.
- No trick resolution, winner detection, score synchronization, end-match, replay, voice chat, AI, matchmaking, or Cloud Functions test exists, per the brief's explicit stop list.
