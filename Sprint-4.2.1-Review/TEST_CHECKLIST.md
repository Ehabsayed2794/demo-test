# Test Checklist — Sprint 4.2.1: Pre-Write Card Authority & Desync Safety

Every claim below is labeled MOCKED, SIMULATED, EMULATOR, or REAL — never mixed, never described as multiplayer or production validation. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 870 automated tests total.

## Pre-implementation verification (Task 2 prerequisite)

| # | Check | Label | Result |
|---|---|---|---|
| 1 | `table-engine.js` has a pure, non-mutating validation path (`isLegal()`/`legalCards()` already exist internally) | **REAL** (direct code reading) | **PASS — additive export added (`canPlayCard`), no Architecture Blocker needed** |

## Task 5's 11 required scenarios

| # | Requirement | Test(s) | Result |
|---|---|---|---|
| 1 | Wrong-turn player is rejected before any Firestore write | `submit-card.test.cjs` "Test #1" | **PASS** (3 checks — err.reason, version untouched, engine never asked) |
| 2 | Correct-turn player can proceed | `submit-card.test.cjs` "Test #2" | **PASS** (2 checks) |
| 3 | Illegal follow-suit card is rejected before persistence | `submit-card.test.cjs` "Test #3" | **PASS** (3 checks) |
| 4 | Card not owned by player is rejected before persistence, if the real engine can verify ownership | `submit-card.test.cjs` "Test #4" | **PASS** (1 check — same gate as follow-suit, since `isLegal()`'s `legalCards()` only ever returns cards from the claimed seat's own hand) |
| 5 | A legal card is written exactly once | `submit-card.test.cjs` "Test #5" | **PASS** |
| 6 | The local echo does not execute the card twice | `submit-card.test.cjs` "Test #6" + `card-sync.test.cjs` "local echo" (corrected architecture) | **PASS** (4 checks total) |
| 7 | Remote ENGINE_REJECTED does not advance the processed count past the rejected entry | `match-adapter.test.cjs` "Task 5 req #7" | **PASS** |
| 8 | Later entries are not processed after a rejected entry | `match-adapter.test.cjs` "Task 5 req #8" | **PASS** |
| 9 | Version is not marked applied after a rejected entry | `match-adapter.test.cjs` "Task 5 req #9" | **PASS** |
| 10 | No gameplay rules are duplicated outside TableEngine | `match-adapter.test.cjs` + `submit-card.test.cjs` structural source checks | **PASS** (4 checks total) |
| 11 | Full regression suite passes | Full suite re-run (below) | **PASS** (870/870) |

## Task 1 — Enforce Turn Before Write

| # | Test | Result |
|---|---|---|
| 1 | Authenticated uid → resolve seat through MatchAdapter → call the existing authority gate → reject before transaction/write | Code inspection (`resolveSeatAndAuthorize()` runs via `matchRef.get()`, before `runTransaction()`) | **PASS** |
| 2 | Structured error `reason = "NOT_YOUR_TURN"` | `submit-card.test.cjs` "Test #1" | **PASS** |
| 3 | Zero Firestore writes occur for the wrong seat | `submit-card.test.cjs` "Test #1" (`version`/`cardLog` provably untouched) | **PASS** |
| 4 | Does not reimplement turn logic inside MatchService — calls the existing `MatchAdapter.assertLocalTurn()` | Code inspection | **PASS** |
| 5 | Re-verified inside the transaction against a fresh read (defense in depth) | Code inspection (`resolveSeatAndAuthorize()` called a second time inside `runTransaction()`) | **PASS** |

## Task 2 — Validate Through the Real Engine Before Persistence

| # | Test | Result |
|---|---|---|
| 1 | Smallest integration path asking the real, existing TableEngine whether a proposed action is legal | `table-engine.js`'s new `canPlayCard()` — composes existing `isLegal()`/`state.turn`/`state.phase`, zero new rules | **PASS** |
| 2 | Does not permanently mutate local engine state merely to validate | Code inspection (`canPlayCard()` only reads `state`, never assigns to it) | **PASS** |
| 3 | Does not emit the play twice | `submit-card.test.cjs` "Task 2: the engine was asked exactly once" checks (×3 scenarios) | **PASS** |
| 4 | Does not invent a second card-rule implementation | Code inspection + `match-adapter.test.cjs`/`submit-card.test.cjs` structural "no `ledSuit` logic outside TableEngine" checks | **PASS** |
| 5 | Does not write first and validate later | Code inspection (`canPlayCard()` call happens BEFORE `runTransaction()`) | **PASS** |
| 6 | An unreachable engine refuses to write blind (does not fake validation, does not skip silently) | `submit-card.test.cjs` "ENGINE_UNAVAILABLE" test | **PASS** |

## Task 3 — Remote Rejection Must Cause Desync, Not Silent Skip

| # | Test | Result |
|---|---|---|
| 1 | Stops processing later entries on ENGINE_REJECTED | `match-adapter.test.cjs` "Task 5 req #8" | **PASS** |
| 2 | Does not advance lastAppliedCardCount beyond the rejected index | `match-adapter.test.cjs` "Task 5 req #7" | **PASS** |
| 3 | Does not claim the snapshot was successfully synchronized | `match-adapter.test.cjs` (desync result: `applied: false, desync: true`) | **PASS** |
| 4 | Returns a structured DESYNC / ENGINE_REJECTED result | `match-adapter.test.cjs` (`{desync:true, reason:"ENGINE_REJECTED", ...}`) | **PASS** |
| 5 | Exposes matchId, index, seatId, engine reason diagnostics | `match-adapter.test.cjs` (all four fields asserted directly) | **PASS** |
| 6 | Does not retry forever automatically | Code inspection (function returns once, per call; no internal loop/timer) + "redelivery re-attempts the same stuck index" test | **PASS** |
| 7 | Does not silently substitute state | `match-adapter.test.cjs` "recovery once resolved" test proves the SAME stuck index is re-attempted, never skipped or faked | **PASS** |

## Task 4 — Card Log Integrity Assessment

| # | Test | Result |
|---|---|---|
| 1 | Determine whether Firestore Rules can safely prove prefix immutability with supported, emulator-verifiable syntax | Formal assessment (documented in `SecurityArchitecture.md`/`EngineAdapter.md`/this sprint's implementation report) — **CANNOT be done safely with current CEL** | **PASS (assessment complete)** |
| 2 | Do not invent unsupported CEL | No rules change was made | **PASS** |
| 3 | Document the exact production risk | `SecurityArchitecture.md`'s "Card write authority" section | **PASS** |
| 4 | Mark cardLog as client-authoritative MVP only | Same section, explicit statement | **PASS** |
| 5 | State that ranked/competitive play requires an authoritative backend or safer event-storage design | Same section — two concrete future directions named | **PASS** |
| 6 | Do not claim this limitation is harmless | Same section — exact exploitable scenario stated | **PASS** |
| 7 | The gap is demonstrated, not just asserted | `rules-simulation.test.js`'s two "KNOWN VULNERABILITY" SIMULATED checks | **PASS** (2 checks) |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (109/109 — 9 net new) |
| — | `tests/bid-sync.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/turn-sync.test.cjs` | MOCKED | **PASS** (26/26) |
| — | `tests/card-sync.test.cjs` | MOCKED | **PASS** (41/41 — corrected) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (67/67) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/submit-card.test.cjs` | MOCKED | **PASS** (49/49 — substantially rewritten) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (142/142 — 2 net new) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **870/870** |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore.
- `canPlayCard()`'s single pre-transaction check (not re-checked per retry) leaves a narrow, inherent race for a genuinely concurrent local engine mutation between validation and commit — documented as a residual limitation, not tested as a false negative (would require a real concurrency harness beyond this project's mocked-Firestore test methodology).
- No test proves `firestore.rules`' `cardLog` prefix-integrity gap is FIXED, because it is not fixed this sprint — the two SIMULATED tests instead prove the gap EXISTS, which is the correct thing to test given Task 4's own conclusion.
- No trick resolution, winner detection, score synchronization, end match, replay, voice chat, AI, or matchmaking test exists, per the brief's explicit stop list.
