# Test Checklist — Sprint 3.6.1: Normal Dash Scoring Hotfix

All tests below are real, executed tests. 16 checks in `tests/match-flow-normal-dash-scoring-fix.test.cjs` (new), 31 in `tests/match-flow-scoring-scenarios.test.cjs` (new), 156 in `tests/match-flow-integration.test.cjs` (updated), plus zero-regression re-verification of 189 pre-existing tests. **393 automated tests total.**

## Fix verification: bid = 0 survives every stage

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | A Normal Dash (0 final estimate) is accepted as a legal bid | **PASS** | `match-flow-normal-dash-scoring-fix.test.cjs` |
| 2 | **Creation:** the bid is recorded internally as `{type:"DASH", amount:0}` | **PASS** | |
| 3 | **Extraction (the fix):** the value now SURVIVES — present in `GameSession.round.estimates`, not absent | **PASS** | |
| 4 | The surviving value is exactly `0` and is a real number (not `undefined`/`null`/`false`/a string) | **PASS** | |
| 5 | All 4 seats have an entry in `estimates` — no seat is spuriously absent anymore | **PASS** | |
| 6 | **Lookup:** `table-engine.js`'s own `state.estimates` also carries the surviving `0` | **PASS** | |

## Fix verification: no NaN anywhere

| # | Test | Result | Evidence |
|---|---|---|---|
| 7 | The affected player's score delta is a real, finite number — not `NaN` | **PASS** | `match-flow-normal-dash-scoring-fix.test.cjs` |
| 8 | Every seat's delta is finite (no `NaN` anywhere in the result) | **PASS** | |
| 9 | Every seat received a finite delta in the primary "complete match" test, WITH a live Normal Dash in the mix | **PASS** | `match-flow-integration.test.cjs` |
| 10 | No `NaN` anywhere in either new regression scenario (Sa'ayda, With) | **PASS** (2 tests) | `match-flow-scoring-scenarios.test.cjs` |

## Fix verification: no hidden fallback / score matches official rules

| # | Test | Result | Evidence |
|---|---|---|---|
| 11 | The actual, engine-produced score exactly matches an independent re-derivation using the CORRECT `{type:"DASH",amount:0}` bid | **PASS** | `match-flow-normal-dash-scoring-fix.test.cjs` |
| 12 | The affected player's delta is computed via the real DASH formula (never the TRICKS formula) | **PASS** | |
| 13 | `GameSession.getMatchScores()` reflects the REAL delta — never silently masked to `0` | **PASS** | |
| 14 | Every seat's running total matches its round delta exactly | **PASS** | |
| 15 | **Sa'ayda:** `GameSession.getMatchScores()` is genuinely computed as `0` for every seat (not silently defaulted) | **PASS** | `match-flow-scoring-scenarios.test.cjs` |
| 16 | **With:** `GameSession.getMatchScores()` reflects the real, non-Sa'ayda deltas for every seat (none silently zeroed) | **PASS** | |

## Regression safety — required scenarios

| # | Scenario | Test | Result | Evidence |
|---|---|---|---|---|
| 17 | Normal bid (success) | Caller's own confirmed bid + a Normal player as sole winner | **PASS** | `match-flow-integration.test.cjs`, `match-flow-scoring-scenarios.test.cjs` (`with`) |
| 18 | Normal bid (failure) | Multiple Normal `TRICKS` failures | **PASS** | Both files above |
| 19 | Dash (the fixed bug) | Same deterministic scenario as the original bug repro, now fixed | **PASS** | `match-flow-normal-dash-scoring-fix.test.cjs` |
| 20 | Dash, live in a Mixed round | A Normal Dash alongside `TRICKS` bids in the same round | **PASS** | `match-flow-integration.test.cjs` |
| 21 | Sa'ayda (all four fail) | `successCount===0`, `isSaayda===true`, every delta exactly 0, ×2 escalation | **PASS** (4 tests) | `match-flow-scoring-scenarios.test.cjs` (`saayda`) |
| 22 | With (matched auction bid) | With status granted, With-specific failure adjustment, distinct from Caller's | **PASS** (2 tests) | `match-flow-scoring-scenarios.test.cjs` (`with`) |
| 23 | Sole winner + Risk (within the `with` scenario) | +10 sole-winner bonus; nonzero Risk adjustment for the last bidder | **PASS** (2 tests) | Same file |
| 24 | Without (no With player — uncontested auction) | `withPlayers: []` | **PASS** | `match-flow-integration.test.cjs`, `match-flow-scoring-scenarios.test.cjs` (`saayda`) — both already exercise this shape |
| 25 | Mixed rounds | `TRICKS` + `DASH` combined; `TRICKS` + With combined | **PASS** (2 files) | `match-flow-integration.test.cjs`, `match-flow-scoring-scenarios.test.cjs` |

## No existing scoring behavior changed except the bug

| # | Test | Result | Evidence |
|---|---|---|---|
| 26 | `calculateRoundScore()`'s formulas are byte-unchanged | **PASS** | `git diff` — `scoring-engine.js`'s only change is inside `applyRoundResult()`, not `calculateRoundScore()`. |
| 27 | `table-engine.js` is completely untouched | **PASS** | `git diff --stat` — empty. |
| 28 | `GameSession`, `Dealer`, `Deck`, `Cards` are completely untouched | **PASS** | `git diff --stat` — empty. |
| 29 | No Firestore/Services/UI file touched | **PASS** | `git diff --stat` — empty. |

## Full regression suite

| # | Suite | Result |
|---|---|---|
| 30 | `tests/deck.test.cjs` | **PASS** (39/39) |
| 31 | `tests/match-service.test.cjs` | **PASS** (59/59) |
| 32 | `tests/room-service.test.cjs` | **PASS** (31/31) |
| 33 | `tests/rules-simulation.test.js` | **PASS** (61/61) |
| — | **Total** | **393/393** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| 34 | `match-flow-integration.test.cjs` is stable across independently random deals | **PASS** | Re-run 5+ times, 156/156 every time. |
| 35 | `match-flow-normal-dash-scoring-fix.test.cjs` is deterministic across repeated runs | **PASS** | Re-run 3+ times, 16/16 every time (fixed-seed PRNG). |
| 36 | `match-flow-scoring-scenarios.test.cjs` is deterministic across repeated runs | **PASS** | Re-run 3+ times, 31/31 every time (fixed-seed PRNG). |

## Not performed

- No fix was attempted for anything beyond the Normal Dash corruption — no AI, matchmaking, replay, leaderboards, network synchronization, or other gameplay enhancement was touched, per the brief's explicit Stop Condition.
- No live Firebase Rules emulator or Firestore environment — this was an engine-only hotfix with no Services/Firestore/UI involvement.
