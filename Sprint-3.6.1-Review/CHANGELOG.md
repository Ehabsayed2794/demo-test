# Changelog — Sprint 3.6.1: Normal Dash Scoring Hotfix

## Fixed
- **`design-ui/engine/bidding-engine.js`** — `extractEstimates()` now includes `DASH`-type bids (a legal "Normal Dash," a final estimate of exactly 0 tricks) alongside `TRICKS`-type bids, instead of dropping them entirely. This is the source-level fix: the value was being lost at extraction, so it's fixed at extraction, not compensated for downstream. `DASHCALL` (the separate, pre-bidding Dash Call type) remains correctly excluded, unchanged.
- **`design-ui/engine/scoring-engine.js`** — `applyRoundResult()`'s match-total accumulation no longer uses `result.deltas[id] || 0` (which silently converted a corrupted `NaN` delta into a plausible-looking `0`, masking the original bug's effect on running totals). Replaced with an `Object.prototype.hasOwnProperty` check that adds a real delta (including a legitimate `0`, e.g. a Sa'ayda round) as-is, and emits a loud `console.warn` — never a silent substitution — if a delta is ever genuinely missing for a seat. `current[id] || 0` (an unrelated, legitimate "first-ever score starts at 0" default) was left untouched.

## Not changed (per the brief's explicit constraints)
- `calculateRoundScore()` and every other scoring formula in `scoring-engine.js` — no rule, no formula.
- `design-ui/engine/table-engine.js` — its bid-type reconstruction ternary (`state.estimates[id] === 0 ? "DASH" : "TRICKS"`) was already correct; it only ever received a missing value, which the extraction fix eliminates. Not modified.
- `design-ui/engine/dealer.js`, `deck.js`, `cards.js`, `design-ui/engine/session.js` (`GameSession`) — not touched.
- `design-ui/match-service.js`, `room-service.js`, `session-service.js`, `player-service.js`, `firestore.rules` — not touched; this was an engine-only hotfix.
- No architecture redesign, no new abstraction, no unrelated refactor.

## Testing
- `tests/match-flow-normal-dash-scoring-bug.test.cjs` (Sprint 3.6) **removed**, replaced by `tests/match-flow-normal-dash-scoring-fix.test.cjs` (new) — the exact same deterministic scenario (same fixed-seed PRNG, same scripted bids), now asserting the fix: the `0` survives extraction and lookup, no `NaN` anywhere in the score result, the score matches an independent re-derivation using the correct `{type:"DASH",amount:0}` bid, and running totals are correct and unmasked. 16 checks.
- `tests/match-flow-integration.test.cjs` (Sprint 3.6, updated) — the primary "happy path, complete match" scenario now deliberately includes a live Normal Dash estimate instead of avoiding it, doubling as regression coverage for the fix inside a Mixed round. 156 checks (unchanged count, updated content), stable across repeated runs with independently random deals.
- `tests/match-flow-scoring-scenarios.test.cjs` (new) — deterministic Sa'ayda and With regression scenarios (spawned as isolated child processes per scenario, since bidding-engine.js/table-engine.js can only correctly drive one round per process). 31 checks: Sa'ayda (`successCount===0`, every delta exactly 0, ×2 escalation, unmasked zero totals) and With (a matched auction bid, a sole-winner bonus, a Risk adjustment, and the With/Caller failure adjustments applied distinctly) — both confirmed free of `NaN`.
- Full regression: `tests/deck.test.cjs` (39), `tests/match-service.test.cjs` (59), `tests/room-service.test.cjs` (31), `tests/rules-simulation.test.js` (61) — all re-run, zero regression.
- **393 automated tests total, all passing.**

## Documentation
- `docs/reviews/MatchFlowIntegration_3.6.1.md` (new) — full hotfix writeup: exact root cause, exact fix (with before/after diffs), the complete estimate-flow review (creation → extraction → storage → lookup → score calculation) requested by the brief, and regression-safety confirmation for every scenario the brief named (Normal bid, Dash, Sa'ayda, With, Without, Mixed rounds).
- `docs/reviews/MatchFlowIntegration_3.6.md` — a superseded-in-part notice added at the top and at §3, pointing to the addendum; the rest of the document is otherwise unchanged (per this project's "document deviations, don't hide them" convention — nothing is deleted, the outdated "NOT FIXED" status is annotated, not silently rewritten as if it always said "fixed").
- This QA package.

## Regression check
Every pre-existing permanent test suite was re-run after both source changes: `tests/deck.test.cjs` (39/39), `tests/match-service.test.cjs` (59/59), `tests/room-service.test.cjs` (31/31), `tests/rules-simulation.test.js` (61/61) — zero regression. The updated `tests/match-flow-integration.test.cjs` was additionally re-run 5+ times against independently random deals with no flakiness observed.
