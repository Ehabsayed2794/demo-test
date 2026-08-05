# Test Checklist — Sprint 3.6: Match Flow Integration

All tests below are real, executed tests. 156 checks in `tests/match-flow-integration.test.cjs`, 12 in `tests/match-flow-normal-dash-scoring-bug.test.cjs` (both new this sprint), plus zero-regression re-verification of the 128 pre-existing tests. 296 automated tests total.

## Pipeline stage: Deck → Dealer → GameSession (dealing)

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | Exactly 52 cards dealt for the round (13 × 4 seats) | **PASS** | `match-flow-integration.test.cjs` |
| 2 | All 52 dealt cards are unique | **PASS** | |
| 3 | The 52 dealt cards are exactly the 4-suit × 13-rank deck — none missing, none extra | **PASS** | |
| 4 | Table phase reuses the SAME deal bidding already saw (Card Engine centralization), not a re-deal | **PASS** | Confirmed via matching hand sizes and card identity across phases. |

## Pipeline stage: Bidding (BiddingEngine)

| # | Test | Result | Evidence |
|---|---|---|---|
| 5 | Round 1 (not a fast round) starts in the DASH sub-phase, waiting on the dealer | **PASS** | |
| 6 | Dash Call decline path transitions correctly to AUCTION with all 4 seats still active | **PASS** | |
| 7 | Auction bid/pass sequencing concludes correctly with the opener as Caller | **PASS** | |
| 8 | Winning bid (number + suit) recorded correctly | **PASS** | |
| 9 | Confirmation phase locks the declared trump correctly | **PASS** | |
| 10 | Final Estimates: each of the three non-Caller seats' estimate is accepted (not rejected) | **PASS** (3 tests) | |
| 11 | Bidding reaches DONE after all seats have a bid on record | **PASS** | |
| 12 | `GameSession.round.trump`/`callerId` committed correctly | **PASS** (2 tests) | |
| 13 | `GameSession.getTurn()` correctly stamped to the Caller (leads trick 1) | **PASS** | |

## Pipeline stage: Card Play / Trick Resolution (TableEngine)

| # | Test | Result | Evidence |
|---|---|---|---|
| 14 | Table phase seeds trump/Caller from the REAL bidding outcome, not a mock fallback | **PASS** (2 tests) | |
| 15 | Trick 1's leader is the Caller (Estimation convention) | **PASS** | |
| 16 | **Turn order preserved:** every play within every trick follows CCW order from the trick leader | **PASS** (52 tests — one per play) | |
| 17 | **Follow-suit enforced (positive):** every legal play submitted by the test is accepted, never rejected | **PASS** (52 tests) | |
| 18 | **Follow-suit enforced (negative, the critical proof):** a deliberate illegal play (holding the led suit, attempting to play off-suit) is REJECTED by the engine, and the player's hand is left unchanged by the rejected attempt | **PASS** (2 tests) | Proves enforcement is real, not merely "never tested." |
| 19 | Illegal-play enforcement was actually exercised at least once during the run | **PASS** | Guards against a false-positive "always avoided the check." |
| 20 | Exactly 13 tricks resolved | **PASS** | |
| 21 | **Trump rules:** every one of the 13 tricks' recorded winner independently cross-checked against a from-scratch recomputation of the same recorded plays (trump > follow-suit > rank) | **PASS** (13 tests) | |
| 22 | Exactly 52 cards played across the whole round, no card played twice | **PASS** (2 tests) | |
| 23 | All four hands reach exactly zero cards after 13 tricks | **PASS** | |
| 24 | `tricksWon` across all four seats sums to exactly 13 | **PASS** | |

## Pipeline stage: Scoring (ScoringEngine)

| # | Test | Result | Evidence |
|---|---|---|---|
| 25 | A real `ScoringEngine` result is computed automatically as a side effect of the 13th trick's resolution — no separate scoring call needed | **PASS** | |
| 26 | Every seat receives a finite score delta (guards the known Normal-Dash bug — this scenario deliberately avoids it) | **PASS** | |
| 27 | `GameSession.getMatchScores()` correctly reflects the applied round deltas | **PASS** | |
| 28 | `GameSession.getLastRoundResult()` correctly records the round (trump/callerId match) | **PASS** (2 tests) | |
| 29 | Re-deriving the score from the same reconstructed inputs `table-engine.js` used internally produces IDENTICAL deltas to what was actually applied | **PASS** | Confirms the pipeline didn't silently diverge from `ScoringEngine`'s own contract. |

## Discovered bug — dedicated regression test (documented, NOT fixed)

| # | Test | Result | Evidence |
|---|---|---|---|
| 30 | A Normal Dash (0 final estimate) is accepted as a legal bid by `bidding-engine.js` | **PASS** | `match-flow-normal-dash-scoring-bug.test.cjs` |
| 31 | **Root cause confirmed:** the affected player's Normal Dash is absent from `GameSession.round.estimates` | **PASS** | |
| 32 | **Bug confirmed:** the affected player's score delta is `NaN` | **PASS** | Deterministic — fixed-seed PRNG used for the deal. |
| 33 | The corruption is isolated to the affected player — every other seat still gets a finite delta | **PASS** | |
| 34 | **Second masking effect confirmed:** `applyRoundResult()`'s `\|\| 0` fallback silently turns the NaN into a plausible-looking `0` in `GameSession.getMatchScores()` | **PASS** | |
| — | Every check in this file is *expected* to pass — a pass confirms the bug is real and precisely scoped, not that anything was fixed. | **By design** | Matches the project's established "confirms the documented limitation" precedent (Sprint 3.3). |

## Scope / constraint verification

| # | Test | Result | Evidence |
|---|---|---|---|
| 35 | `scoring-engine.js` not modified | **PASS** | `git diff --stat` — empty. |
| 36 | `GameSession` (`session.js`, both copies) not modified | **PASS** | `git diff --stat` — empty. |
| 37 | No `game-state.js` (`GameState`) copy modified | **PASS** | `git diff --stat` — empty. |
| 38 | No Firestore/Services/UI file modified (`room-service.js`, `match-service.js`, `session-service.js`, `player-service.js`, `firestore.rules`, any screen's HTML) | **PASS** | `git diff --stat` — empty. |
| 39 | `bidding-engine.js`/`table-engine.js` changes are purely additive (an appended export object each) — zero lines removed, zero existing logic altered | **PASS** | `git diff --stat`: 2 files changed, 39 insertions(+), 0 deletions(-). |
| 40 | No bidding rule, scoring formula, or AI heuristic was changed | **PASS** | Confirmed by reading the full diff — both changes are export-object additions only. |
| 41 | No multiplayer/Firestore synchronization added | **PASS** | No `MatchService`/`RoomService` reference anywhere in the new code. |
| 42 | Zero regression on every pre-existing permanent test suite | **PASS** | `tests/deck.test.cjs` (39/39), `tests/match-service.test.cjs` (59/59), `tests/room-service.test.cjs` (31/31), `tests/rules-simulation.test.js` (61/61). |
| 43 | Primary integration test is stable (not flaky) across independently random deals | **PASS** | Re-run 8+ times with different random shuffles each time — 156/156 every time. |
| 44 | Bug-reproduction test is deterministic across repeated runs | **PASS** | Re-run 5+ times with a fixed-seed PRNG — 12/12 every time. |

## Not performed

- Multi-round match integration (Sprint 3.6 scoped "a complete match" to one full deal-to-score cycle — see the Integration Report §2 for the discovered per-process, one-round-per-`require()` architectural constraint that makes multi-round integration a distinct future decision, not attempted here).
- Fast rounds (14–18), Super Call, pre-bidding Dash Call success path, Sa'ayda escalation, Classic scoring mode — none of these are required by this sprint's concrete requirements list (52 cards / 13 tricks / turn order / follow-suit / trump / scoring), and adding exhaustive coverage of every bidding variant would be scope creep beyond "one playable match," per the brief's explicit "No scope creep" deliverable.
- Direct verification against `Estimation_Rules_v2_SingleSourceOfTruth.docx` — that file does not exist anywhere in this repository (confirmed again this sprint, matching Sprint 3.4.5's finding).
- Fixing the discovered Normal Dash scoring bug (§3 of the Integration Report) — deliberately not attempted, per the brief's explicit "stop and document" instruction.
