# Test Checklist — Sprint 4.2.3: Firestore Rules Compile-Safe Card Turn Hotfix

Every claim below is labeled MOCKED, SIMULATED, EMULATOR, or REAL — never mixed. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or a real Rules compiler.** 898 automated tests total.

## Task 1 — Remove unsupported List.exists usage

| # | Check | Result |
|---|---|---|
| 1 | `oldData.seats.keys().exists(...)` no longer appears anywhere in `firestore.rules` (code, not comments) | Direct grep audit | **PASS — confirmed absent** |
| 2 | Replacement uses `Map.get(key, default)` against fixed p1-p4 keys, no loops/lambdas/`.all()`/`.exists()` | Code inspection | **PASS** |
| 3 | `newData.turn == null` still allowed at the resolving boundary | `rules-simulation.test.js` Task 3 (#1) | **PASS** |
| 4 | Each of p1/p2/p3/p4's own uid still allowed as the next turn | `rules-simulation.test.js` Task 3 (#2-#5) | **PASS** |
| 5 | An uid owning no seat in this match is still rejected | `rules-simulation.test.js` Task 3 (#6) | **PASS** |

## Task 2 — Full-file audit for unsupported constructs

| # | Occurrence found | Verified against official syntax | Action | Result |
|---|---|---|---|---|
| 1 | `isValidCardSubmission()`'s `.exists(` (Task 1 target) | Not in official List method reference | Replaced with `Map.get()` | **FIXED** |
| 2 | `isValidSeatMap()`'s `seatKeys.all(s, seats[s] in players)` | Not in official List method reference | Replaced with explicit per-seat checks | **FIXED** |
| 3 | `isValidSeatMap()`'s nested `seatKeys.all(s1, seatKeys.all(s2, ...))` | Not in official List method reference | Replaced with 6 explicit pairwise checks | **FIXED** |
| 4 | `isValidNewMatch()`'s `data.seats.keys().all(s, data.bids[s] == null)` | Not in official List method reference | Replaced with explicit per-seat checks | **FIXED** |
| 5 | `isValidBidSubmission()`'s `oldData.seats.keys().all(s, s in newData.bids && newData.bids[s] != null)` | Not in official List method reference | Replaced with precomputed per-seat `let` bindings | **FIXED** |
| 6 | Every `.diff(...).affectedKeys().hasOnly([...])` occurrence (multiple functions) | `Map.diff()`/`MapDiff.affectedKeys()`/`List.hasOnly()` — all officially documented | No change needed | **VERIFIED FINE** |
| 7 | `exists(/databases/.../rooms/$(data.roomId))` in `isValidNewMatch()` | A DIFFERENT, officially-documented top-level path-existence function, not the List method this finding concerns | No change needed | **VERIFIED FINE, not miscategorized** |
| 8 | Lambda-style syntax anywhere in the file | Grep audit found none | N/A | **NONE FOUND** |
| 9 | Any remaining `.all(`/`.exists(`/`.any(` in actual code (not comments) | Grep audit after all fixes | N/A | **CONFIRMED ABSENT** |

## Task 3 — Tests (all 8 required scenarios + 1 extra)

| # | Scenario | Result |
|---|---|---|
| 1 | Null turn allowed at resolving boundary | **PASS** |
| 2 | p1 uid allowed | **PASS** |
| 3 | p2 uid allowed | **PASS** |
| 4 | p3 uid allowed | **PASS** |
| 5 | p4 uid allowed | **PASS** |
| 6 | Unknown uid rejected | **PASS** |
| 7 | Empty string rejected | **PASS** |
| 8 | Partial seats map (only p1/p2) behaves safely — p2's own uid still allowed | **PASS** |
| 8b | Partial seats map does not crash simulated logic when checked against an unknown uid | **PASS** |

All 9 labeled SIMULATED. No claim of Firebase Rules compile verification is made anywhere in this test file or this checklist — an actual Firebase Emulator or Rules compiler has never been run against this project.

## Task 4 — Documentation honesty

| # | Check | Result |
|---|---|---|
| 1 | `SecurityArchitecture.md` states the JS simulation verifies logical intent only | New "Compile-safe Rules syntax" section | **PASS** |
| 2 | `SecurityArchitecture.md` states the simulation does not compile or execute `firestore.rules` | Same section | **PASS** |
| 3 | `SecurityArchitecture.md` states real Emulator verification remains pending | Same section | **PASS** |
| 4 | `CardTurnProgressionHotfix_4.2.2.md` updated with a superseding banner | Top-of-file banner added | **PASS** |
| 5 | `CHANGELOG.md` (this package) states the same three facts | See "Testing" section above | **PASS** |
| 6 | `TEST_CHECKLIST.md` (this file) states the same three facts | This section + header | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/bid-sync.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/card-sync.test.cjs` | MOCKED | **PASS** (41/41) |
| — | `tests/match-adapter.test.cjs` | MOCKED | **PASS** (119/119) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (67/67) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66) |
| — | `tests/submit-card.test.cjs` | MOCKED | **PASS** (34/34) |
| — | `tests/turn-sync.test.cjs` | MOCKED | **PASS** (26/26) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (158/158 — 9 net new) |
| — | **Total** | — | **898/898** |

## Not performed / honest limitations

- No test against the Firebase Emulator or a real Rules compiler — this sprint's own explicit point is that this gap exists and is unclosed.
- This sprint's fixes are syntax-level only — no new gameplay legality, turn-order, or scoring check was added; `isValidCardSubmission()`'s pre-existing honest limitation (cannot verify the CORRECT next seat, only structural validity) is unchanged.
- `cardLog` prefix/order integrity (Sprint 4.2.1's Task 4 finding) is untouched, not re-assessed, this sprint.
- No trick resolution, trick winner persistence, scoring, next round, match end, replay, voice chat, AI, or matchmaking test exists, per the brief's explicit stop list.
