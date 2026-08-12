# Test Checklist — Sprint 3.7: Real-Time Match Synchronization

All tests below are real, executed tests. 50 checks in `tests/match-sync.test.cjs` (new), plus zero-regression re-verification of 393 pre-existing tests. **443 automated tests total.**

## Brief's required checklist, mapped to actual tests

| # | Requirement | Test(s) | Result |
|---|---|---|---|
| 1 | Two browser instances stay synchronized | Two independent `subscribeToMatch()` callers ("two tabs") both receive every change to the same matchId | **PASS** (3 checks) |
| 2 | Card play appears remotely | A `gameState.lastCardPlayed`-shaped change is delivered to every subscriber | **PASS** |
| 3 | Estimates synchronize | A `gameState.estimates`-shaped change (including a real `0`) is delivered to every subscriber | **PASS** |
| 4 | Turn changes synchronize | A `turn` field change is delivered to every subscriber | **PASS** |
| 5 | Reconnect restores synchronization | A simulated mid-session disconnect is followed by an automatic, unattended resubscribe that resumes delivering real updates | **PASS** (2 checks) |
| 6 | Duplicate listeners are impossible | Two `subscribeToMatch()` calls for the same matchId create exactly ONE real Firestore listener (directly counted) | **PASS** |
| 7 | Memory leak check | Unsubscribing the last local listener tears down the real Firestore listener; a fresh subscribe afterward creates a genuinely new one | **PASS** (4 checks) |
| 8 | Offline recovery | A disconnect delivers an error without crashing, keeps the last known good data, and a sustained outage's repeated backoff attempts eventually succeed once the outage clears | **PASS** (4 checks) |
| 9 | Snapshot ordering | A stale/out-of-order snapshot (same or lower `version`) is ignored, including one arriving AFTER a newer one was already applied; a genuinely newer version is applied | **PASS** (5 checks) |
| 10 | Zero gameplay rule changes | Every `MatchService` gameplay method is still an unimplemented stub; full pre-existing regression suite re-run unchanged | **PASS** (10 + 393 checks) |

## Detail: MatchService.subscribeToMatch()

| # | Test | Result |
|---|---|---|
| 1 | Two independent subscribers to the same matchId each get an immediate snapshot | **PASS** |
| 2 | Exactly one real Firestore listener is created for two subscribers to the same matchId | **PASS** |
| 3 | Both tabs observe a `turn` change | **PASS** |
| 4 | Both tabs observe an `estimates` change, including a real zero | **PASS** |
| 5 | Both tabs observe a `lastCardPlayed` change | **PASS** |
| 6 | An identical re-delivery (no real change) is never re-published | **PASS** |
| 7 | The first snapshot (version 1) is delivered | **PASS** |
| 8 | A stale snapshot at the SAME version never overwrites state | **PASS** |
| 9 | A genuinely newer version (2 > 1) is applied | **PASS** |
| 10 | A stale snapshot arriving AFTER a newer one was already applied is still ignored | **PASS** |
| 11 | Ordering tolerates version gaps (5 after 2) | **PASS** |
| 12 | Unsubscribing one of two listeners leaves the shared listener attached for the other | **PASS** |
| 13 | The remaining subscriber still receives updates | **PASS** |
| 14 | The unsubscribed listener receives nothing further | **PASS** |
| 15 | The last unsubscribe tears down the real Firestore listener | **PASS** |
| 16 | A change with zero subscribers touches nothing | **PASS** |
| 17 | A fresh subscribe after full teardown creates a genuinely new listener | **PASS** |
| 18 | Initial snapshot received before disconnect test | **PASS** |
| 19 | A disconnect delivers an error without crashing | **PASS** |
| 20 | The last known good data survives the disconnect (not nulled) | **PASS** |
| 21 | Automatic reconnect resumes delivering real updates, unattended | **PASS** |
| 22 | A sustained outage's disconnect is reported safely | **PASS** |
| 23 | Repeated reconnect attempts during a sustained outage keep failing safely | **PASS** |
| 24 | The backoff loop eventually reconnects once the outage clears | **PASS** |
| 25–34 | Every gameplay stub (`submitDashCall`...`endMatch`) is still `Not implemented`, unchanged | **PASS** (10 checks) |

## Detail: GameSession consumption of MatchService

| # | Test | Result |
|---|---|---|
| 35 | `getRemoteMatch()` is `null` before subscribing | **PASS** |
| 36 | `isSubscribedToRemoteMatch()` is `false` before subscribing | **PASS** |
| 37 | `subscribeToRemoteMatch()` immediately mirrors the current match data | **PASS** |
| 38 | `isSubscribedToRemoteMatch()` reflects the active subscription | **PASS** |
| 39 | GameSession never touched Firestore directly — only ever called `MatchService.subscribeToMatch` (verified via the underlying call counter) | **PASS** |
| 40 | `onRemoteMatchUpdate` fires immediately with the current value | **PASS** |
| 41 | `getRemoteMatch()` reflects a live change | **PASS** |
| 42 | `onRemoteMatchUpdate` listeners are notified of the same change | **PASS** |
| 43 | A repeat `subscribeToRemoteMatch()` call for the same matchId is idempotent — no duplicate subscription | **PASS** |
| 44 | Switching matchId cleanly tears down the old subscription | **PASS** |
| 45 | ...and attaches to the new one | **PASS** |
| 46 | `unsubscribeFromRemoteMatch()` fully tears down the underlying subscription | **PASS** |
| 47 | ...and `isSubscribedToRemoteMatch()` reflects it | **PASS** |
| 48 | ...but `getRemoteMatch()` still returns the last known data (not wiped) | **PASS** |
| 49 | `getRemoteMatchError()` surfaces a remote disconnect | **PASS** |
| 50 | GameSession's mirrored data survives a disconnect (fail-open) | **PASS** |

## Full regression suite (zero-regression re-verification)

| # | Suite | Result |
|---|---|---|
| — | `tests/deck.test.cjs` | **PASS** (39/39) |
| — | `tests/match-service.test.cjs` | **PASS** (59/59 — including its own pre-existing `subscribeToMatch` tests, now exercising the new implementation) |
| — | `tests/room-service.test.cjs` | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | **PASS** (61/61) |
| — | `tests/match-flow-integration.test.cjs` | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | **PASS** (31/31) |
| — | **Total** | **443/443** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| — | `match-sync.test.cjs` is stable across repeated runs (uses real, short timers for reconnect — not mocked time) | **PASS** | Re-run 4+ times, 50/50 every time |

## Not performed

- No fix/change was made to any gameplay rule, scoring formula, engine file, `firestore.rules`, or any other Service, per the brief's explicit scope and stop list.
- No live Firebase Rules emulator or real Firestore environment — this sprint's sync logic is verified against a dedicated mock Firestore with faithful `onSnapshot`/error/reconnect semantics, consistent with this project's established test methodology.
- No UI screen was rewired to use the new `GameSession.subscribeToRemoteMatch()` — the existing placeholder Match screen (`design-ui/match/index.html`) continues to call `MatchService.subscribeToMatch()` directly (and transparently benefits from all of this sprint's new guards/reconnect logic, since that method was enhanced in place, not renamed). Wiring the placeholder screen through `GameSession` instead is optional follow-up, not required by this sprint's scope — see `docs/architecture/MatchSynchronization.md`.
