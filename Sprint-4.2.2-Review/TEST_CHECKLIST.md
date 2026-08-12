# Test Checklist — Sprint 4.2.2: Atomic Card Turn Progression & Card-Log Desync Hardening

Every claim below is labeled MOCKED, SIMULATED, EMULATOR, or REAL — never mixed, never described as multiplayer or production validation. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 889 automated tests total.

## Pre-implementation verification (Task 1 prerequisite)

| # | Check | Label | Result |
|---|---|---|---|
| 1 | `table-engine.js` supports a pure, non-mutating "is this legal, what happens next" preview without a rewrite | **REAL** (direct code reading + manual smoke test) | **PASS — additive export added (`previewPlay`), no Architecture Blocker needed** |

## Task 7's 14 required scenarios

| # | Requirement | Test(s) | Result |
|---|---|---|---|
| 1 | p1 submits a valid card and Firestore turn becomes p2's UID | `submit-card.test.cjs` full sequence test | **PASS** |
| 2 | p2 can submit immediately without any test-only `setTurn()` call | `submit-card.test.cjs` full sequence test | **PASS** |
| 3 | p3 can submit immediately afterward | `submit-card.test.cjs` full sequence test | **PASS** |
| 4 | p4 can submit the fourth card and Firestore moves to resolving/null turn | `submit-card.test.cjs` full sequence test | **PASS** |
| 5 | Wrong-turn submission performs zero writes | `submit-card.test.cjs` wrong-turn test | **PASS** |
| 6 | Stale preview produces `STALE_GAME_STATE` and zero writes | `submit-card.test.cjs` STALE_GAME_STATE test | **PASS** |
| 7 | Transaction retry cannot reuse an obsolete preview against changed state | `submit-card.test.cjs` STALE_GAME_STATE test (intercepts the transaction callback's first invocation) | **PASS** |
| 8 | `MALFORMED_ENTRY` stops processing and does not advance count/version | `match-adapter.test.cjs` MALFORMED_ENTRY desync tests | **PASS** |
| 9 | Entries after `MALFORMED_ENTRY` are not processed | `match-adapter.test.cjs` MALFORMED_ENTRY desync tests | **PASS** |
| 10 | Same-seat + same-card echo is ignored safely | `match-adapter.test.cjs` echo-match test | **PASS** |
| 11 | Same-seat + different-card echo produces `LOCAL_ECHO_MISMATCH` | `match-adapter.test.cjs` echo-mismatch test | **PASS** |
| 12 | Entries after an echo mismatch are not processed | `match-adapter.test.cjs` echo-mismatch test | **PASS** |
| 13 | Full four-card sequence works without manually editing Firestore turn | `submit-card.test.cjs` full sequence test + `card-sync.test.cjs` "multiple sequential cards" | **PASS** |
| 14 | Full regression suite passes | Full suite re-run (below) | **PASS** (889/889) |

## Task 1 — Engine-Owned Next-Turn Preview

| # | Check | Result |
|---|---|---|
| 1 | Reuses `canPlayCard()`'s existing legality answer, adds no new rule | Code inspection | **PASS** |
| 2 | Reuses the exact `state.plays.length`/`nextCCW()` arithmetic `emit()` already performs | Code inspection | **PASS** |
| 3 | Never calls `emit()`, never mutates `state`, never removes a card | Code inspection | **PASS** |
| 4 | Returns `nextTurnSeat:null, nextPhase:"RESOLVING"` on the 4th card | Manual smoke test against the real engine, all 4 plays of a trick | **PASS** |
| 5 | Does not invent winner/trick-resolution behavior | Code inspection | **PASS** |

## Task 2/3 — Atomic Persist + Transaction Revalidation

| # | Check | Result |
|---|---|---|
| 1 | Card append + next turn + phase + version all written in ONE `tx.update()` call | Code inspection + `submit-card.test.cjs` atomic-write-shape test | **PASS** |
| 2 | `matches/{matchId}.seats` is the only seat-to-UID mapping consulted | Code inspection | **PASS** |
| 3 | Sequential p1→p2→p3→p4→null/resolving submission works without a test helper | `submit-card.test.cjs` full sequence test | **PASS** |
| 4 | `expectedVersion` captured before the transaction, re-checked on every callback invocation | Code inspection + intercepted-retry test | **PASS** |
| 5 | A version mismatch throws `STALE_GAME_STATE` and writes nothing | `submit-card.test.cjs` STALE_GAME_STATE test | **PASS** |
| 6 | Never automatically retries a gameplay action against changed engine state | Code inspection (no recompute-and-retry loop anywhere in `submitCard()`) | **PASS** |

## Task 4 — MALFORMED_ENTRY Must Be Desync

| # | Check | Result |
|---|---|---|
| 1 | Stops processing immediately | `match-adapter.test.cjs` | **PASS** |
| 2 | Does not process later entries | `match-adapter.test.cjs` | **PASS** |
| 3 | Does not advance count past the malformed index | `match-adapter.test.cjs` | **PASS** |
| 4 | Does not update `lastAppliedCardVersionByMatch` | `match-adapter.test.cjs` | **PASS** |
| 5 | Returns `{applied:false, desync:true, reason:"MALFORMED_ENTRY", matchId, index}` | `match-adapter.test.cjs` | **PASS** |

## Task 5 — Local Echo Content Verification

| # | Check | Result |
|---|---|---|
| 1 | Same seat + same suit + same rank.v → benign `ALREADY_APPLIED_LOCALLY` skip | `match-adapter.test.cjs` | **PASS** |
| 2 | Same seat + different card → `LOCAL_ECHO_MISMATCH`, not silently accepted | `match-adapter.test.cjs` | **PASS** |
| 3 | Mismatch result includes `seatId, localCard, remoteCard` diagnostics | `match-adapter.test.cjs` | **PASS** |
| 4 | Does not advance beyond a mismatch, does not mark version applied | `match-adapter.test.cjs` | **PASS** |
| 5 | Does not process entries after a mismatch | `match-adapter.test.cjs` | **PASS** |

## Task 6 — Firestore Rules

| # | Check | Result |
|---|---|---|
| 1 | Caller owns the previous active turn (`oldData.turn == request.auth.uid`) | `rules-simulation.test.js` SECURITY tests | **PASS** |
| 2 | Exactly one card appended, `lastCardSeat` is caller's seat, version increments exactly once | `rules-simulation.test.js` (pre-existing checks, still passing) | **PASS** |
| 3 | New turn is either a UID in `seats`' own values, or `null` for the resolving boundary | `rules-simulation.test.js` SECURITY tests (valid + unknown-uid-rejected + RESOLVING-boundary-allowed) | **PASS** |
| 4 | `cardPhase` is a valid enum value (`PLAY`/`RESOLVING`) | `rules-simulation.test.js` SECURITY tests | **PASS** |
| 5 | Immutable fields (`players`/`seats`/`dealer`/`status`/`currentRound`/`gameState`/`roomId`) unchanged | `rules-simulation.test.js` (pre-existing checks, still passing) | **PASS** |
| 6 | Does not claim rules can independently validate follow-suit or compute the correct next seat | Documented in `SecurityArchitecture.md`/`EngineAdapter.md` | **PASS (honest limitation stated)** |
| 7 | No unsupported CEL invented (`.keys().exists()` used for map-value membership, not `.values()`) | Code inspection | **PASS** |
| 8 | New `isValidNewMatchV5` requires `cardPhase: null` at creation | `rules-simulation.test.js` V5 create-time tests | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/bid-sync.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/card-sync.test.cjs` | MOCKED | **PASS** (41/41 — rewritten, no test-only turn mutation) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (119/119) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (67/67) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/submit-card.test.cjs` | MOCKED | **PASS** (34/34 — rewritten) |
| — | `tests/turn-sync.test.cjs` | MOCKED | **PASS** (26/26) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (149/149 — net new) |
| — | **Total** | — | **889/889** |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore.
- `previewPlay()`'s single pre-transaction check (not re-computed per retry) leaves a narrow, inherent race for a genuinely concurrent LOCAL engine mutation between the preview and the transaction's commit — documented as a residual limitation, not tested as a false negative (would require a real concurrency harness beyond this project's mocked-Firestore test methodology).
- No test proves `firestore.rules` can verify the new turn is the CORRECT next seat — it cannot, and no test claims otherwise; the SECURITY tests only prove it correctly rejects a STRUCTURALLY invalid turn.
- `cardLog` prefix/order integrity (Sprint 4.2.1's Task 4 finding) is untouched, not re-assessed, this sprint.
- No trick resolution, trick winner persistence, scoring, next round, match end, replay, voice chat, AI, or matchmaking test exists, per the brief's explicit stop list.
