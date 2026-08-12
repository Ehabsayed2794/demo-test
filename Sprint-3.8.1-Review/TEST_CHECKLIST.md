# Test Checklist — Sprint 3.8.1: Bidding Validation & Rules Hardening

Every test explicitly states MOCKED or SIMULATED, per this sprint's instruction not to mix these terms. **No test in this project, this sprint or any prior one, has run against the Firebase Emulator or Real Firestore.** 571 automated tests total.

## Task 4's required value set, mapped to actual tests

| # | Value | MOCKED (`submit-bid.test.cjs`) | SIMULATED (`rules-simulation.test.js`) |
|---|---|---|---|
| 1 | `null` | **PASS** | **PASS** |
| 2 | `undefined` | **PASS** | **PASS** |
| 3 | `NaN` | **PASS** | **PASS** |
| 4 | `Infinity` | **PASS** | **PASS** |
| 5 | `-Infinity` | **PASS** | **PASS** |
| 6 | negative (`-1`) | **PASS** | **PASS** |
| 7 | `14` (above max) | **PASS** | **PASS** |
| 8 | string (`"4"`) | **PASS** | **PASS** |
| 9 | object (`{}`) | **PASS** | **PASS** |
| 10 | duplicate bid | **PASS** (pre-existing, re-verified) | **PASS** (pre-existing, re-verified) |
| 11 | valid `0` | **PASS** | **PASS** |
| 12 | valid `1` | **PASS** | **PASS** |
| 13 | valid `2` | **PASS** | **PASS** |
| 14 | valid `3` | **PASS** | **PASS** |
| 15 | valid `4` | **PASS** | **PASS** |
| 16 | valid `5` | **PASS** | **PASS** |
| 17 | valid `6` | **PASS** | **PASS** |
| 18 | valid `7` | **PASS** | **PASS** |
| 19 | valid `8` | **PASS** | **PASS** |
| 20 | valid `9` | **PASS** | **PASS** |
| 21 | valid `10` | **PASS** | **PASS** |
| 22 | valid `11` | **PASS** | **PASS** |
| 23 | valid `12` | **PASS** | **PASS** |
| 24 | valid `13` | **PASS** | **PASS** |

Also covered, beyond the brief's named list: a non-integer (`4.5`) is rejected (both MOCKED and SIMULATED) — a natural extension of "non-numeric" given trick counts are whole numbers.

## Task 1 — Bid Range Validation (design-ui/match-service.js)

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | Each invalid value rejected with `err.reason === "INVALID_BID_VALUE"` | MOCKED | **PASS** (10 checks) |
| 2 | None of the ten invalid attempts reached Firestore — document completely untouched | MOCKED | **PASS** |
| 3 | Each of the 14 valid values (`0`-`13`) individually accepted and stored correctly | MOCKED | **PASS** (14 checks) |
| 4 | Validation happens before any Firestore/transaction access (fail-fast) | Code inspection + MOCKED (confirmed via untouched-document check above) | **PASS** |
| 5 | No coercion — a numeric-looking string is rejected, never parsed | MOCKED + SIMULATED | **PASS** |
| 6 | `bidding-engine.js` is never imported, required, or referenced by the new validation code | Code inspection | **PASS** |

## Task 2 — Firestore Rules Hardening

| # | Test | Kind | Result |
|---|---|---|---|
| 1 | `isValidBidSubmission()` rejects non-integer types (`is int` check) | SIMULATED | **PASS** |
| 2 | Rejects negative values | SIMULATED | **PASS** |
| 3 | Rejects values > 13 | SIMULATED | **PASS** |
| 4 | Rejects missing values (already covered by the pre-existing "must actually be set" check, re-verified unaffected) | SIMULATED | **PASS** |
| 5 | Accepts all 14 valid values | SIMULATED | **PASS** (14 checks) |
| 6 | No gameplay rule (turn order, auction legality) is encoded in the new clause | Code inspection (`firestore.rules`' own comments) | **PASS** |

## Task 3 — Documentation

| # | Check | Result |
|---|---|---|
| 1 | `docs/architecture/BidValidation.md` exists and explicitly defines Generic Validation | **PASS** |
| 2 | Explicitly defines Gameplay Validation | **PASS** |
| 3 | States clearly that gameplay legality remains bidding-engine.js's future responsibility | **PASS** |
| 4 | Cross-referenced from `match-service.js`, `firestore.rules`, and `MatchSynchronization.md` | **PASS** |

## Full regression suite

| # | Suite | Kind | Result |
|---|---|---|---|
| — | `tests/deck.test.cjs` | MOCKED | **PASS** (39/39) |
| — | `tests/match-service.test.cjs` | MOCKED | **PASS** (65/65) |
| — | `tests/match-sync.test.cjs` | MOCKED | **PASS** (58/58) |
| — | `tests/submit-bid.test.cjs` | MOCKED | **PASS** (66/66 — 25 net new) |
| — | `tests/room-service.test.cjs` | MOCKED | **PASS** (31/31) |
| — | `tests/rules-simulation.test.js` | SIMULATED | **PASS** (109/109 — 24 net new) |
| — | `tests/match-flow-integration.test.cjs` | MOCKED | **PASS** (156/156) |
| — | `tests/match-flow-normal-dash-scoring-fix.test.cjs` | MOCKED | **PASS** (16/16) |
| — | `tests/match-flow-scoring-scenarios.test.cjs` | MOCKED | **PASS** (31/31) |
| — | **Total** | — | **571/571** |

## Stability checks

| # | Test | Result | Evidence |
|---|---|---|---|
| — | `submit-bid.test.cjs` stable across repeated runs | **PASS** | Re-run 3+ times, 66/66 every time |

## Not performed / honest limitations

- No test against the Firebase Emulator or real Firestore — restated explicitly, consistent with every prior sprint.
- `firestore.rules`' `is int` type check is unverified against a real CEL engine (same pre-existing limitation recorded in Sprint 3.8's reports — not new to this sprint).
- No gameplay legality (turn order, auction rules, whether 13 is actually a legal bid right now) is tested, because none is implemented, per this sprint's explicit scope.
