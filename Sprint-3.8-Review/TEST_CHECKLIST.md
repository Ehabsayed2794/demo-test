# Test Checklist — Sprint 3.8: Gameplay Synchronization (Bidding Authority)

Every test below explicitly states MOCKED, SIMULATED, Firebase Emulator, or Real Firestore — per the brief's instruction not to mix these terms. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 522 automated tests total.

## Brief's required testing checklist

| # | Requirement | Test(s) | Kind | Result |
|---|---|---|---|---|
| 1 | Normal bid | `tests/submit-bid.test.cjs` — full happy-path, both seats, closing behavior | MOCKED | **PASS** (8 checks) |
| 2 | Duplicate bid | `tests/submit-bid.test.cjs` "duplicate bid" section | MOCKED | **PASS** (3 checks) |
| | | `tests/rules-simulation.test.js` "duplicate bid" test | SIMULATED | **PASS** (1 check) |
| 3 | Out-of-order version | `tests/submit-bid.test.cjs` "out-of-order version" (transactional guarantee) | MOCKED | **PASS** (1 check) |
| | | `tests/rules-simulation.test.js` "out-of-order version"/"stale version" | SIMULATED | **PASS** (2 checks) |
| 4 | Wrong seat | `tests/submit-bid.test.cjs` "wrong seat" | MOCKED | **PASS** (2 checks) |
| | | `tests/rules-simulation.test.js` "wrong seat" | SIMULATED | **PASS** (1 check) |
| 5 | Wrong uid | `tests/submit-bid.test.cjs` "wrong uid" | MOCKED | **PASS** (1 check) |
| | | `tests/rules-simulation.test.js` "wrong uid" | SIMULATED | **PASS** (1 check) |
| 6 | Permission denied | `tests/submit-bid.test.cjs` "permission denied" (structured error + unauthenticated) | MOCKED | **PASS** (2 checks) |
| | | `tests/rules-simulation.test.js` "PERMISSION DENIED equivalent" | SIMULATED | **PASS** (1 check) |
| 7 | Offline retry | `tests/submit-bid.test.cjs` "offline retry / failure recovery" | MOCKED | **PASS** (3 checks) |
| 8 | Reconnect | `tests/submit-bid.test.cjs` "reconnect" (caller-driven retry + genuine disconnect carrying a real bid) | MOCKED | **PASS** (3 checks) |
| 9 | Two simultaneous bidders | `tests/submit-bid.test.cjs` "two simultaneous bidders" (different seats + same seat) | MOCKED | **PASS** (6 checks) |
| 10 | Late subscriber | `tests/submit-bid.test.cjs` "late subscriber" | MOCKED | **PASS** (2 checks) |
| 11 | Stale snapshot | `tests/submit-bid.test.cjs` "stale snapshot" (real bid data, ordering guard now live) | MOCKED | **PASS** (1 check) |
| 12 | Duplicate snapshot | `tests/submit-bid.test.cjs` "duplicate snapshot" | MOCKED | **PASS** (1 check) |
| 13 | Listener cleanup | `tests/submit-bid.test.cjs` "listener cleanup" | MOCKED | **PASS** (1 check) |
| 14 | Memory leak | `tests/submit-bid.test.cjs` "memory leak" | MOCKED | **PASS** (2 checks) |
| 15 | Regression | Full suite re-run (see below) | MOCKED + SIMULATED | **PASS** (522/522) |

## Task 1 — Seat Identity

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | Seats assigned positionally from room.players | MOCKED (`match-service.test.cjs`) | **PASS** |
| 2 | Only real seats exist — no fabricated p3/p4 for a 2-player match | MOCKED | **PASS** |
| 3 | A fabricated seat for a non-player is denied | SIMULATED (`rules-simulation.test.js`) | **PASS** |
| 4 | Two seats sharing a uid is denied | SIMULATED | **PASS** |
| 5 | A real player with no seat at all is denied | SIMULATED | **PASS** |
| 6 | An invalid seat name is denied | SIMULATED | **PASS** |

## Task 2 — Versioned Match Writes

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | version starts at 1 | MOCKED | **PASS** |
| 2 | version increments by exactly 1 per accepted write | MOCKED | **PASS** |
| 3 | submitBid() always computes version from a fresh read, never cached/stale | MOCKED | **PASS** |
| 4 | Rules reject version != current+1 (skip-ahead and stale-same-version) | SIMULATED | **PASS** (2 checks) |
| 5 | version != 1 at creation is denied | SIMULATED | **PASS** |
| 6 | Ordering guard inside subscribeToMatch() is now genuinely exercised by real data | MOCKED (`submit-bid.test.cjs` stale/duplicate snapshot tests) | **PASS** |

## Task 3 — Submit Bid

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | Only the seat owner may submit | MOCKED + SIMULATED | **PASS** |
| 2 | Cannot submit twice | MOCKED + SIMULATED | **PASS** |
| 3 | Cannot submit after bidding closes | SIMULATED | **PASS** |
| 4 | Cannot submit another player's bid | MOCKED + SIMULATED | **PASS** |
| 5 | Executes inside a Firestore transaction | Code inspection + MOCKED (transaction-retry tests exercise it) | **PASS** |
| 6 | submitBid() with missing arguments rejects with a structured error | MOCKED | **PASS** |

## Task 4 — Realtime Synchronization

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | Already-subscribed client receives the update automatically | MOCKED | **PASS** |
| 2 | Update carries the new version | MOCKED | **PASS** |
| 3 | No new listener created — same onSnapshot() call count before/after | MOCKED | **PASS** |
| 4 | No polling exists in the delivery path | Code inspection | **PASS** |

## Task 5 — Firestore Rules

| # | Test | Kind | Result |
|---|---|---|---|
| 1-9 | isValidSeatMap / isValidNewMatchV3 create-time validation (9 scenarios) | SIMULATED | **PASS** (9 checks) |
| 10-15 | isValidBidSubmission update-time validation (normal, closes-bidding, lies-about-closing, duplicate, out-of-order x2, wrong seat, wrong uid, unauthenticated, bidding-closed, modifies-other-seat, changes-forbidden-field, non-member) | SIMULATED | **PASS** (15 checks) |

## Task 6 — Conflict Handling

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | Two different seats concurrent — both succeed | MOCKED | **PASS** (3 checks) |
| 2 | Two same-seat concurrent — exactly one succeeds | MOCKED | **PASS** (3 checks) |
| 3 | Duplicate bid ignored (not corrupting state) | MOCKED | **PASS** |
| 4 | Stale version rejected | SIMULATED | **PASS** |
| 5 | Permission denied never retries (structural: no retry loop exists in submitBid()) | Code inspection + MOCKED | **PASS** |

## Task 7 — Failure Recovery

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | Structured error exposed (`err.reason`) on every failure path | MOCKED | **PASS** (4+ checks) |
| 2 | Local state (GameSession) cannot be corrupted by a failed write | Structural (code inspection) — no code path connects a failed submitBid() to GameSession | **PASS** |
| 3 | Server state never overwritten — every write is a narrow tx.update() from a fresh read | Code inspection | **PASS** |
| 4 | No silent infinite retry — submitBid() contains no retry loop; caller-driven retry succeeds once available | MOCKED | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (65/65 — 6 net new) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58 — 1 net removed, stub loop adjustment) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (41/41 — new) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (85/85 — 24 net new) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **522/522** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| — | `submit-bid.test.cjs` stable across repeated runs (real, short timers for reconnect) | **PASS** | Re-run 3+ times, 41/41 every time |
| — | Full suite re-run after every doc-only edit to confirm zero incidental regression | **PASS** | 522/522 every time |

## Not performed / honest limitations

- No test in this project has ever run against the Firebase Emulator or real Firestore — restated explicitly per this sprint's instruction.
- `firestore.rules`' `.keys()`/`.all()`/nested-`.diff()` usage (new to this file) is unverified against a real CEL engine — see `ArchitectureReport_3.8.md` §5 and `SynchronizationReport_3.8.md` §5 for the full, explicit caveat and recommendation.
- Bid-value legality (a real trick count, Dash/With shape, auction order) is not tested because it is not implemented at this layer — `bidding-engine.js` is untouched.
- No card-play, trick-resolution, scoring-update, or turn-rotation test exists because none of that was implemented, per the brief's explicit stop list.
