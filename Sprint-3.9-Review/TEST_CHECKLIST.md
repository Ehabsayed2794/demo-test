# Test Checklist — Sprint 3.9: Engine Adapter Layer (Seat ↔ Engine Synchronization)

Every test explicitly labeled MOCKED or SIMULATED. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 613 automated tests total.

## Task 6's required coverage, mapped to actual tests

| # | Requirement | Test(s) | Kind | Result |
|---|---|---|---|---|
| 1 | Seat resolution | uid→seat, seat→uid, seat→player, player→seat, symmetric round-trip across all 4 seats | MOCKED | **PASS** (7 checks) |
| 2 | Missing seat | `seatToUid`/`seatToPlayer` for p3/p4 on a 2-player match | MOCKED | **PASS** (3 checks) |
| 3 | Duplicate seat | Two seats sharing a uid — deterministic first-match resolution, repeatable | MOCKED | **PASS** (2 checks) |
| 4 | Unknown uid | A uid owning no seat; null/undefined input | MOCKED | **PASS** (3 checks) |
| 5 | Unknown seat | A seat name outside p1..p4; empty/null input | MOCKED | **PASS** (3 checks) |
| 6 | Bootstrap success | Full translated snapshot; GameSession dealer/turn/round correctly set; existing players/biddingState left untouched | MOCKED | **PASS** (6 checks) |
| 7 | Bootstrap with invalid data | null matchDoc, non-object matchDoc, missing seats (graceful degrade, no overwrite with garbage), GameSession unavailable | MOCKED | **PASS** (5 checks) |
| 8 | Translation round-trip | Firestore → Engine → Firestore identical for every adapter-owned field, full and partial matches, determinism, no-mutation | MOCKED | **PASS** (13 checks) |

## Task 1/5 — Adapter shape & Isolation

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | Adapter contains no gameplay logic (code inspection — no reference to bidding-engine.js/table-engine.js/scoring-engine.js/Dealer/Deck/Cards anywhere) | Code inspection | **PASS** |
| 2 | Adapter's own source contains no `require()` of match-service.js, session.js, or any engine file | MOCKED (structural test reading the file's own source) | **PASS** |
| 3 | `global.GameSession` is only ever referenced lazily, inside function bodies (soft coupling, matching this codebase's established idiom) | Code inspection | **PASS** |

## Task 2 — Seat Resolution detail

| # | Test | Result |
|---|---|---|
| 1 | uid → seat resolves correctly | **PASS** |
| 2 | seat → uid resolves correctly | **PASS** |
| 3 | seat → player returns `{seatId, uid}` | **PASS** |
| 4 | player → seat accepts a `{uid}` object | **PASS** |
| 5 | player → seat accepts a `{id}` object | **PASS** |
| 6 | player → seat accepts a raw uid string | **PASS** |
| 7 | Round-trip symmetry across all 4 real seats | **PASS** |

## Task 3 — Engine Bootstrap detail

| # | Test | Result |
|---|---|---|
| 1 | Returns the full translated snapshot | **PASS** |
| 2 | GameSession.getDealer() reflects the translated seat id | **PASS** |
| 3 | GameSession.getTurn() reflects the translated seat id | **PASS** |
| 4 | GameSession.getRound().number reflects translated round metadata | **PASS** |
| 5 | GameSession's existing `players` field left completely untouched | **PASS** |
| 6 | GameSession's existing `biddingState` field left completely untouched | **PASS** |
| 7 | null matchDoc throws `INVALID_MATCH_DOC` | **PASS** |
| 8 | Non-object matchDoc throws `INVALID_MATCH_DOC` | **PASS** |
| 9 | Missing `seats` degrades to null resolutions, does not throw | **PASS** |
| 10 | When a seat can't be resolved, GameSession's existing dealer/turn are left unchanged, never overwritten with garbage | **PASS** |
| 11 | GameSession unavailable throws `GAME_SESSION_UNAVAILABLE`, never silently no-ops | **PASS** |

## Task 4 — Translation round-trip detail

| # | Test | Result |
|---|---|---|
| 1 | players[] survives unchanged | **PASS** |
| 2 | seats survives unchanged | **PASS** |
| 3 | dealer (uid) survives via seat translation and back | **PASS** |
| 4 | turn (uid) survives via seat translation and back | **PASS** |
| 5 | currentRound survives unchanged | **PASS** |
| 6 | version survives unchanged | **PASS** |
| 7 | biddingOpen survives unchanged | **PASS** |
| 8 | bids (including a real null slot) survives unchanged | **PASS** |
| 9 | lastBidSeat survives unchanged | **PASS** |
| 10 | Partial (2-player) match round-trips correctly | **PASS** |
| 11 | Determinism: same input twice produces identical output | **PASS** |
| 12 | No mutation: original matchDoc untouched after translation | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (42/42 — new) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (65/65) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (109/109) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **613/613** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| — | `match-adapter.test.cjs` stable across repeated runs | **PASS** | Re-run 3+ times, 42/42 every time |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore — this adapter never touches Firestore at all, so this category doesn't apply to it, but is restated for consistency with every prior sprint's honesty statement.
- No UI wiring test exists because no UI calls this adapter yet, per this sprint's explicit scope.
- No test exercises real `PlayerService` profile enrichment because none is implemented — `seatToPlayer()` intentionally returns only a minimal identity descriptor.
- No bid/card/trick synchronization or turn authority test exists because none was implemented, per the brief's explicit stop list.
