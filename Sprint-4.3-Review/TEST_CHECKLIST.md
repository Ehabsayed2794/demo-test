# Test Checklist — Sprint 4.3: Trick Resolution Synchronization

Every claim below is labeled MOCKED or SIMULATED — never mixed, never described as multiplayer or production validation. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 954 automated tests total.

## Task 1 — Architecture Verification

| # | Check | Label | Result |
|---|---|---|---|
| 1 | `table-engine.js` already exposes trick completion (`phase === "RESOLVING"`) without adding gameplay logic | **REAL** (direct code reading) | **PASS — no new export needed** |
| 2 | `table-engine.js` already exposes trick winner + next leader via existing `resolveTrick()`/`getState()` (Sprint 3.6) | **REAL** (direct code reading) | **PASS — no new export needed** |
| 3 | `table-engine.js`'s export object is byte-for-byte unchanged by this sprint | `tests/match-adapter.test.cjs` + `tests/trick-sync.test.cjs`, both reading the real file's source | **PASS** |

## Task 6's 11 required scenarios

| # | Requirement | Test(s) | Result |
|---|---|---|---|
| 1 | Trick completes after fourth card | `trick-sync.test.cjs` | **PASS** |
| 2 | Winner matches TableEngine | `trick-sync.test.cjs` (cross-checked against an independent test-side re-computation of the real rule) | **PASS** |
| 3 | Duplicate snapshot ignored | `match-adapter.test.cjs` + `trick-sync.test.cjs` | **PASS** |
| 4 | Stale snapshot ignored | `match-adapter.test.cjs` + `trick-sync.test.cjs` | **PASS** |
| 5 | Reconnect | `trick-sync.test.cjs` | **PASS** |
| 6 | Late subscriber | `trick-sync.test.cjs` (a genuine, real one-trick backlog, not forged) | **PASS** |
| 7 | Malformed trick | `match-adapter.test.cjs` + `trick-sync.test.cjs` | **PASS** |
| 8 | ENGINE_REJECTED | `trick-sync.test.cjs` (via the pre-existing `applyRemoteCard()` detection, never re-derived) | **PASS** |
| 9 | Desync reporting | `trick-sync.test.cjs` (full structured shape: matchId/index/seatId) | **PASS** |
| 10 | Multiple consecutive tricks | `match-adapter.test.cjs` (loop mechanism, fake engine) + `trick-sync.test.cjs` (2 real tricks, real engine) | **PASS** |
| 11 | No regression | Full suite re-run (below) | **PASS** (954/954) |

## Task 2 — `applyRemoteTrick()`

| # | Check | Result |
|---|---|---|
| 1 | Consumes remote trick state (the REAL engine state, reached only by replaying Firestore's own cardLog) | Code inspection + tests | **PASS** |
| 2 | Replays ONLY through TableEngine — its only direct engine call is `resolveTrick()` | Structural test (no `ledSuit`/`cardValue(`/`SUITS[` anywhere in match-adapter.js) | **PASS** |
| 3 | Never calculates winner itself — `winnerId` read back from `TableEngine.getState().lastTrick.winnerId` | `match-adapter.test.cjs` (fake engine, verified read-back) + `trick-sync.test.cjs` (real engine, independent cross-check) | **PASS** |
| 4 | Never duplicates engine logic | Structural test + code inspection | **PASS** |
| 5 | Detects ENGINE_REJECTED exactly like previous sync layers | `trick-sync.test.cjs` (surfaces the pre-existing `applyRemoteCard()` desync, never masks it) | **PASS** |

## Task 3 — `startTrickSync()`

| # | Check | Result |
|---|---|---|
| 1 | Same architecture as `startBidSync()`/`startTurnSync()`/`startCardSync()` | Code inspection | **PASS** |
| 2 | Reuses existing `MatchService.subscribeToMatch()` | Code inspection + `trick-sync.test.cjs` (`ONSNAPSHOT_CALLS === 1` even with a second subscription) | **PASS** |
| 3 | Does not create another listener | Same as above | **PASS** |

## Task 4 — MatchService: not modified, justified

| # | Check | Result |
|---|---|---|
| 1 | `MatchService.submitCard()`'s transaction patch is byte-for-byte unchanged | `trick-sync.test.cjs` (regex check against real source) | **PASS** |
| 2 | Justification documented: winner/leader/tricksWon are deterministically re-derivable by every client from already-synced data | `docs/reviews/TrickResolutionSync_4.3.md` §4 | **PASS (documented)** |

## Task 5 — firestore.rules: not modified, justified

| # | Check | Result |
|---|---|---|
| 1 | `isValidCardSubmission()`'s `affectedKeys().hasOnly([...])` allowlist is byte-for-byte unchanged | `trick-sync.test.cjs` (regex check against real source) | **PASS** |
| 2 | Justification documented: no new field is ever written for trick resolution | `docs/reviews/TrickResolutionSync_4.3.md` §5 + `SecurityArchitecture.md`'s "Trick resolution authority" section | **PASS (documented)** |

## Task 8 — Mandatory Honesty Review

| # | Question | Answer |
|---|---|---|
| 1 | Did you duplicate ANY gameplay rule? | **NO** |
| 2 | Did you modify table-engine.js? | **NO** |
| 3 | Did you calculate a trick winner outside TableEngine? | **NO** |
| 4 | Did you introduce another Firestore listener? | **NO** |
| 5 | Did you change Firestore schema? | **NO** |
| 6 | Did you touch forbidden files? | **NO** |
| 7 | Did you fake any test? | **NO** |
| 8 | Did any architecture decision deviate from this brief? | **YES — documented** (see CHANGELOG's own "Architecture decision beyond the original brief" section) |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/bid-sync.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/turn-sync.test.cjs` | MOCKED | **PASS** (26/26) |
| — | `tests/card-sync.test.cjs` | MOCKED | **PASS** (41/41) |
| — | `tests/trick-sync.test.cjs` | MOCKED | **PASS** (45/45 — new) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (147/147 — 32 net new) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (67/67) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/submit-card.test.cjs` | MOCKED | **PASS** (34/34) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (158/158) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **954/954** |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore.
- "Multiple consecutive tricks" is tested against the REAL, continuously-running pipeline (not a forged cold-start snapshot) to avoid this test needing to independently predict a real trick winner; the exact "N tricks in ONE cold-start snapshot" catch-up loop mechanism is instead unit-tested against a controllable fake, where predicting a fake's own simplistic winner carries no real-engine-divergence risk. Both together cover the requirement; see `docs/reviews/TrickResolutionSync_4.3.md` §6 for the full reasoning.
- `firestore.rules`' own turn-ownership check is effectively inactive for the first card of every trick after the first (documented, not fixed — see `SecurityArchitecture.md`'s "Trick resolution authority" section).
- `cardLog` prefix/order integrity (Sprint 4.2.1's Task 4 finding) is untouched, not re-assessed, this sprint — though its exact production risk description was updated to reflect that `cardLog` is now read for trick resolution (see `SecurityArchitecture.md`).
- No score synchronization, next round, match end, replay, voice chat, AI, matchmaking, or Cloud Functions test exists, per the brief's explicit stop list.
