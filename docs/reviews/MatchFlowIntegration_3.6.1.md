# Match Flow Integration — Sprint 3.6.1 Addendum (Normal Dash Scoring Hotfix)

This is an addendum to `docs/reviews/MatchFlowIntegration_3.6.md`, which remains the primary Integration Report and is unchanged except for this pointer. Read that document first for full context (the pipeline, the minimum wiring change, and the original discovery of this bug in §3). This addendum documents exactly what Sprint 3.6.1 fixed, why, and what was verified.

## Root Cause (restated precisely, for this hotfix's record)

A player's final bidding estimate of exactly 0 tricks (a "Normal Dash") is legitimate, valid data — not missing data. The pipeline had two defects that together corrupted it:

1. **`bidding-engine.js`'s `extractEstimates()`** only copied `TRICKS`-type bids into the `estimates` map it returns. A Normal Dash is recorded as `{ type: "DASH", amount: 0 }` — a *different* type, not `TRICKS` — so it was entirely excluded. The affected player ended up **absent** from `GameSession.round.estimates`, indistinguishable from "never estimated at all."
2. **`table-engine.js`'s `resolveTrick()`** reconstructs each seat's bid for scoring via `state.estimates[id] === 0 ? "DASH" : "TRICKS"`. For the absent player, `state.estimates[id]` is `undefined`, and `undefined === 0` is `false` — so the reconstruction produced `{ type: "TRICKS", amount: undefined }`, silently wrong.
3. **`ScoringEngine.calculateRoundScore()`** then did arithmetic with that `undefined` amount, producing `NaN`.
4. **`ScoringEngine.applyRoundResult()`** accumulated match totals via `(current[id] || 0) + (result.deltas[id] || 0)`. Since `NaN` is falsy, that `|| 0` silently turned the visible `NaN` back into a plausible-looking `0` — a second, distinct masking effect on top of the first.

## Exact Fix

Both changes are additive, localized, and touch only the two points where the value was actually lost or the fallback actually masked corruption — no rule, no formula, no architecture was changed.

### 1. `design-ui/engine/bidding-engine.js` — `extractEstimates()`

```diff
 function extractEstimates(sparseBids) {
   const out = {};
-  Object.keys(sparseBids).forEach(id => { if (sparseBids[id].type === "TRICKS") out[id] = sparseBids[id].amount; });
+  Object.keys(sparseBids).forEach(id => {
+    const bid = sparseBids[id];
+    if (bid.type === "TRICKS" || bid.type === "DASH") out[id] = bid.amount;
+  });
   return out;
 }
```

This is the fix **at the source**, per the brief's explicit instruction ("The corruption must be fixed at its source. Do NOT compensate for it downstream."). Once a Normal Dash's `0` amount actually reaches `GameSession.round.estimates`, `table-engine.js`'s existing `state.estimates[id] === 0 ? "DASH" : "TRICKS"` reconstruction ternary works **correctly, unmodified** — it was never wrong logic, it was only ever being fed a missing value. This is confirmed by construction: `bidding-engine.js`'s own `SubmitFinalEstimate` handler *never* produces a `TRICKS`-type bid with `amount === 0` (`intent.tricks === 0 ? {type:"DASH",...} : {type:"TRICKS",...}` — the ternary is exhaustive and mutually exclusive), so after this fix, a stored value of literal `0` can only ever mean `DASH`, never an ambiguous case. `table-engine.js` was **not modified** — it didn't need to be.

`DASHCALL` (the pre-bidding Dash Call — a different, earlier bid type that never reaches the Final Estimates phase at all) is deliberately still excluded from this function, exactly as before — it's carried separately via `dashCallers`, unaffected by this fix.

### 2. `design-ui/engine/scoring-engine.js` — `applyRoundResult()`

```diff
 var current = GameSession.getMatchScores();
 var updated = {};
-Object.keys(current).forEach(function (id) { updated[id] = (current[id] || 0) + (result.deltas[id] || 0); });
+Object.keys(current).forEach(function (id) {
+  var hasDelta = Object.prototype.hasOwnProperty.call(result.deltas, id);
+  if (!hasDelta) {
+    console.warn("[ScoringEngine] applyRoundResult: no score delta recorded for seat " + id + " for round " + result.round + " — this indicates a real data-flow gap upstream, not a normal completed round. Treating as 0, but NOT silently.");
+  }
+  var delta = hasDelta ? result.deltas[id] : 0;
+  updated[id] = (current[id] || 0) + delta;
+});
 GameSession.setMatchScores(updated);
```

**Why this masking fallback specifically needed removing** (per the brief's "Remove masking" requirement): `calculateRoundScore()` always populates a real, numeric `deltas[id]` for every seat in `order` — a delta of exactly `0` (e.g. every seat in a Sa'ayda round) is legitimate data, and `0 || 0` happens to still equal `0` so that case was never actually broken. The masking only mattered for the one case that *shouldn't* happen after fix #1: a missing or `NaN` delta. `hasOwnProperty` distinguishes "no delta was recorded for this seat at all" (a genuine data-flow gap — now surfaced with a loud `console.warn`, never silent) from "a real, legitimate delta that happens to be falsy" (`0` or, if it should ever occur, `NaN` — which now propagates visibly into `GameSession.getMatchScores()` instead of being absorbed). **`current[id] || 0` was left untouched** — that fallback is an unrelated, legitimate default (a player's first-ever recorded running total starting from `0`), not corruption-masking, and the brief's own instruction was to fix *fallbacks that hide corrupted data*, not every `|| 0` in the file indiscriminately.

No other `value || 0`-shaped fallback in the reviewed estimate flow (creation → extraction → storage → lookup → score calculation) was found to be masking corrupted data. `current[id] || 0` in the same line, and every other `|| 0`/`|| []`/`|| null` default elsewhere in `bidding-engine.js`/`table-engine.js`/`scoring-engine.js`/`session.js`, were reviewed and are legitimate "missing key → sensible default" initializations, not places where a real, present-but-falsy value could be silently discarded.

## Estimate Flow Review (as requested)

| Stage | File / function | Before this hotfix | After |
|---|---|---|---|
| Creation | `bidding-engine.js`'s `SubmitFinalEstimate` handler | Already correct — `{type:"DASH",amount:0}` for a 0-trick estimate | Unchanged |
| Extraction | `bidding-engine.js`'s `extractEstimates()` | Dropped DASH-type bids entirely | **Fixed** — includes DASH alongside TRICKS |
| Storage | `session.js`'s `GameSession.completeBidding()`/`setRound()` | Stores whatever it's handed, unmodified | Unchanged (no bug here — confirmed, not touched) |
| Lookup / reconstruction | `table-engine.js`'s `resolveTrick()` | Ternary logic itself was always correct; fed a missing value | Unchanged (no longer fed a missing value) |
| Score calculation | `scoring-engine.js`'s `calculateRoundScore()` | Computes correctly for whatever input it's given (was never the bug) | Unchanged |
| Score persistence | `scoring-engine.js`'s `applyRoundResult()` | `\|\| 0` silently masked a `NaN` delta into a plausible `0` | **Fixed** — `hasOwnProperty` check + loud warning for genuinely missing data |

## Regression Safety — Verified

Per the brief's explicit list: **Normal bid, Dash, Sa'ayda, With, Without, Mixed rounds.**

- **Normal bid** (plain `TRICKS` success/failure) — exercised throughout `tests/match-flow-integration.test.cjs` (the Caller's own confirmed bid, plus two Normal `TRICKS` estimators) and `tests/match-flow-scoring-scenarios.test.cjs`'s `with` scenario (p3, a Normal player, succeeds as sole winner).
- **Dash** (Normal Dash, the fixed bug) — `tests/match-flow-normal-dash-scoring-fix.test.cjs` (the direct, deterministic descendant of Sprint 3.6's bug-reproduction test, same seed, same scripted bids, now asserting the fix) and, live, inside `tests/match-flow-integration.test.cjs`'s own "happy path, complete match" scenario, which now deliberately includes a Normal Dash instead of routing around it.
- **Sa'ayda** (all four fail) — new, `tests/match-flow-scoring-scenarios.test.cjs`'s `saayda` scenario: confirms `successCount === 0`, `isSaayda === true`, every delta exactly `0`, multiplier escalates to `×2`, and `GameSession.getMatchScores()` is unaffected — genuinely computed as `0`, not silently defaulted.
- **With** (a matched auction bid) — new, `tests/match-flow-scoring-scenarios.test.cjs`'s `with` scenario: confirms the match is granted, the With player's failure adjustment is applied distinctly from the Caller's, and — in the same round — a sole-winner bonus and a nonzero Risk adjustment are both computed correctly.
- **Without** (no With player in the round — an uncontested auction) — already the default shape of `tests/match-flow-integration.test.cjs` and the `saayda` scenario above (`withPlayers: []` in both); not duplicated as a separate scenario.
- **Mixed rounds** (a combination of bid types in one round) — `tests/match-flow-integration.test.cjs`'s scenario now combines a `TRICKS` Caller bid, two `TRICKS` Normal estimates, and one `DASH` estimate in the same round; `tests/match-flow-scoring-scenarios.test.cjs`'s `with` scenario combines `TRICKS` (Caller, Normal ×2) with a `With`-flagged `TRICKS` bid.

**No existing scoring behavior changed except the bug.** Every scenario above that doesn't involve a `0` final estimate produces byte-identical results to Sprint 3.6 (confirmed by re-running `tests/match-flow-integration.test.cjs`'s original, non-Dash-avoiding checks — all of them, save the ones explicitly about the Dash value itself, are unchanged in structure and still pass), and `calculateRoundScore()`'s formulas were not touched at all.

## Testing

- **Deterministic reproduction of the original bug's exact scenario, now asserting the fix:** `tests/match-flow-normal-dash-scoring-fix.test.cjs` (16 checks) — same fixed-seed PRNG, same scripted bids as Sprint 3.6's bug-reproduction test; confirms the `0` survives extraction and lookup, no `NaN` anywhere, the score matches an independent re-derivation using the *correct* `{type:"DASH",amount:0}` bid, and running totals are correct.
- **Live regression inside the primary "complete match" test:** `tests/match-flow-integration.test.cjs` (156 checks, updated) — now includes a Normal Dash directly rather than avoiding it.
- **Other scoring scenarios:** `tests/match-flow-scoring-scenarios.test.cjs` (31 checks, new) — Sa'ayda and With, both deterministic (fixed-seed PRNG, hand-verified `tricksWon` distribution).
- **Full regression suite:** `tests/deck.test.cjs` (39), `tests/match-service.test.cjs` (59), `tests/room-service.test.cjs` (31), `tests/rules-simulation.test.js` (61) — all re-run, zero regression.
- **Total: 393 automated tests, all passing.**

## Files Changed

| File | Change |
|---|---|
| `design-ui/engine/bidding-engine.js` | One function body changed (`extractEstimates()`) — now includes `DASH`-type bids. No other line touched. |
| `design-ui/engine/scoring-engine.js` | One accumulation line in `applyRoundResult()` changed to use `hasOwnProperty` instead of `\|\| 0` for the delta specifically, plus a diagnostic `console.warn` for a genuinely missing delta. `calculateRoundScore()` (the actual scoring formulas) — **not touched**. |
| `design-ui/engine/table-engine.js` | **Not touched** — its reconstruction logic was already correct once fed non-corrupted input. |
| `design-ui/engine/dealer.js`, `deck.js`, `cards.js`, `session.js` (`GameSession`) | **Not touched.** |
| `design-ui/match-service.js`, `room-service.js`, `session-service.js`, `player-service.js`, `firestore.rules` | **Not touched** — no multiplayer/Firestore file was in scope for this hotfix. |
| `tests/match-flow-normal-dash-scoring-bug.test.cjs` | Removed — replaced by `tests/match-flow-normal-dash-scoring-fix.test.cjs` (same deterministic scenario, assertions flipped to verify the fix). |
| `tests/match-flow-integration.test.cjs` | Updated — now includes a live Normal Dash instead of avoiding it. |
| `tests/match-flow-scoring-scenarios.test.cjs` | New — Sa'ayda and With regression coverage. |

## GO / NO-GO

**Fixed. Deterministic tests pass. Full regression suite passes (393/393). No rule, formula, or architecture was changed.**

Stopping here per the brief — not continuing to AI, matchmaking, replay, leaderboards, network synchronization, or further gameplay enhancements.
