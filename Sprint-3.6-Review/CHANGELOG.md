# Changelog — Sprint 3.6: Match Flow Integration

## Added
- `tests/match-flow-integration.test.cjs` — 156 real, executed integration tests driving one complete round end-to-end through the actual engine files: `Deck → Dealer → GameSession → BiddingEngine → TableEngine → ScoringEngine`. Covers 52-card dealing, the full Dash Call → Auction → Confirmation → Final Estimates bidding sequence, 13 tricks of card play with turn order and follow-suit verification (including one deliberate illegal-play rejection, proving enforcement is real), an independent trick-winner cross-check for every trick, and scoring consistency (every seat's delta finite, `GameSession.getMatchScores()`/`getLastRoundResult()` correctly updated, and an independent re-derivation of the score matching what was actually applied). Stable across 8+ repeated runs against independently random deals.
- `tests/match-flow-normal-dash-scoring-bug.test.cjs` — 12 real, executed tests that discover, reproduce, and precisely isolate a real scoring bug found while writing the primary integration test (see below). Deterministic via a fixed-seed PRNG substituted for `Math.random` only for the duration of the deal (a test-harness-only technique — no source file touched).
- `docs/reviews/MatchFlowIntegration_3.6.md` — full Integration Report: the minimum wiring change and why it was unavoidable, the discovered scoring bug's exact root cause and a recommended (not implemented) fix, what was verified working with zero changes, and two further discovered-but-not-fixed architectural notes (a latent classic-script `SUITS`/`RANKS` collision risk, and a "one round per process" constraint on `bidding-engine.js`/`table-engine.js`'s top-level, require-time-computed state).
- This QA package.

## Changed
- `design-ui/engine/bidding-engine.js` — one additive export object (`window.BiddingEngine = { initState, emit, getState }`) appended at the end of the file. Zero lines removed, zero existing logic altered — this was the minimum change required to make the file's reducer callable from outside a browser page at all (it previously exposed no export of any kind).
- `design-ui/engine/table-engine.js` — the same treatment (`window.TableEngine = { initState, emit, resolveTrick, getState }`), same discipline, same reasoning.

## Discovered, NOT fixed (per the brief's explicit "stop and document" instruction)
**A real, blocking scoring bug:** a player whose final bidding estimate is exactly 0 tricks (a legal, ordinary "Normal Dash") is silently dropped from `GameSession.round.estimates` by `bidding-engine.js`'s `extractEstimates()` (which only carries `TRICKS`-type bids). `table-engine.js`'s `resolveTrick()` then reconstructs that player's bid type incorrectly (`undefined === 0` is `false`, so it's misclassified as `TRICKS` with `amount: undefined`), and `ScoringEngine.calculateRoundScore()` computes `NaN` for that player's score delta as a direct consequence — not a `ScoringEngine` bug; the corruption is entirely upstream. A second, distinct masking effect was also found: `applyRoundResult()`'s `(current||0)+(delta||0)` accumulation silently turns the visible `NaN` back into a plausible-looking `0` in `GameSession.getMatchScores()`, making the corruption invisible at the running-match-total level. Also confirmed: this bug is outcome-dependent — a Sa'ayda (all-fail) round never reaches the code path that triggers it, so it can be masked by chance on any given round. Full details, evidence, and a recommended fix (not implemented) are in the Integration Report §3.

Also discovered, documented, not fixed:
- `bidding-engine.js`/`table-engine.js` each declare their own top-level `SUITS`/`RANKS` as classic (non-module) `const` — loading both on the same real HTML page would throw a `SyntaxError` (a latent collision, never yet triggered since no page currently loads either file).
- Both files compute their `PLAYERS`/`TURN_ORDER`/`ROUND_CFG` once, at `require()`/script-load time — meaning each can only correctly drive one round per process/page-load, matching their original one-page-per-round browser design but a real constraint for any future multi-round integration.

## Not changed
- `design-ui/engine/scoring-engine.js` — not touched. No documented bug was found to live inside it; the discovered bug is entirely upstream (bidding-engine.js/table-engine.js's handoff).
- `design-ui/engine/session.js` (`GameSession`, both copies), any `game-state.js` copy (`GameState`) — not touched.
- `design-ui/engine/cards.js`, `design-ui/engine/deck.js`, `design-ui/engine/dealer.js` — not touched (Sprint 3.5's work; re-verified with zero regression).
- No Firestore/Services/UI file (`room-service.js`, `match-service.js`, `session-service.js`, `player-service.js`, `firestore.rules`, any screen's markup) — this was an engine-only sprint; no multiplayer/Firestore synchronization was added.
- No bidding rule, scoring formula, or AI heuristic was changed.

## Regression check
Re-ran every pre-existing permanent test suite after this sprint's two additive engine-file changes: `tests/deck.test.cjs` (39/39), `tests/match-service.test.cjs` (59/59), `tests/room-service.test.cjs` (31/31), `tests/rules-simulation.test.js` (61/61) — zero regression. 296 automated tests total across the whole project test suite.
