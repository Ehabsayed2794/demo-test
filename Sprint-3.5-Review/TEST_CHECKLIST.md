# Test Checklist — Sprint 3.5: Deck Implementation & Engine Integration

All tests below are real, executed tests — 39 new automated tests in `tests/deck.test.cjs`, loading the actual `cards.js`/`deck.js`/`dealer.js` files directly (no engine mocking) — plus a full regression re-run of every pre-existing permanent test suite (190 tests total).

## `Deck` — construction & structure

| # | Test | Result | Evidence |
|---|---|---|---|
| 1 | `new Deck()` creates exactly 52 cards | **PASS** | |
| 2 | The 52 cards are unique — no `(suit, rank)` combo repeated | **PASS** | |
| 3 | The 52 combos are EXACTLY the 4 real suits × 13 ranks — none missing, none extra | **PASS** | |
| 4 | Every card built goes through `Cards.createCard()`'s real shape (id/suit/rank/displayName/value/owner/played) | **PASS** | Confirms `Deck` never duplicates the card shape — it reuses `Cards.createCard()` for every single card. |

## `Deck.prototype.shuffle`

| # | Test | Result | Evidence |
|---|---|---|---|
| 5 | `shuffle()` preserves the exact same 52 unique cards (multiset unchanged) | **PASS** | |
| 6 | `shuffle()` changes the order (not a no-op) | **PASS** | |
| 7 | `shuffle(rng)` with an injected deterministic RNG produces EXACTLY the expected Fisher–Yates permutation, verified against an independently-computed reference implementation over the same fixed RNG sequence | **PASS** | Proves the algorithm is genuinely Fisher–Yates, not merely "order changed" — resolves the Sprint 3.4.5 audit's flagged "no deterministic/injectable shuffle" gap. |

## `Deck.prototype.draw` / `remaining`

| # | Test | Result | Evidence |
|---|---|---|---|
| 8 | `draw()` returns a real card object | **PASS** | |
| 9 | `draw()` removes exactly one card — `remaining()` decreases by 1 | **PASS** | |
| 10 | 10 further draws: never returns a card already drawn from this deck | **PASS** (10 tests) | |
| 11 | `remaining()` decreases correctly across multiple draws (41 left after 11 total draws) | **PASS** | |
| 12 | `remaining()` reaches exactly 0 after drawing all 52 cards | **PASS** | |
| 13 | `draw()` on an empty deck throws a clear error (fail loud, not a silent `undefined`) | **PASS** | |

## `Deck.prototype.reset`

| # | Test | Result | Evidence |
|---|---|---|---|
| 14 | Before reset: `remaining()` correctly reflects prior draws | **PASS** | |
| 15 | `reset()` restores exactly 52 cards | **PASS** | |
| 16 | `reset()` rebuilds a full, correct 52-combo deck (all 4 suits × 13 ranks present) | **PASS** | |
| 17 | `reset()` builds brand-new card objects, not reused references from before the reset | **PASS** | |

## `Deck` owns no gameplay logic

| # | Test | Result | Evidence |
|---|---|---|---|
| 18 | `Deck`'s public surface is exactly `shuffle`/`draw`/`remaining`/`reset` — no bidding/scoring/trump surface leaked in | **PASS** | |

## `Dealer.dealHands()` — the real integration

| # | Test | Result | Evidence |
|---|---|---|---|
| 19 | Produces exactly 4 hands (`p1`–`p4`) | **PASS** | |
| 20 | Each hand has exactly 13 cards | **PASS** (4 tests) | |
| 21 | 52 cards consumed in total (13 × 4) | **PASS** | |
| 22 | No duplicate cards across all four hands | **PASS** | |
| 23 | No missing cards — the union of all four hands is EXACTLY the 52 real combos, each exactly once | **PASS** | |
| 24 | Every dealt card's `owner` matches the hand it was placed into | **PASS** | |
| 25 | Each hand is pre-sorted for display (`Cards.compareForSort` order) — unchanged, pre-existing behavior | **PASS** | |
| 26 | `dealHands()` can be called again immediately without error — a fresh `Deck` instance per call, not a shared exhausted singleton | **PASS** | |
| 27 | `dealHands(seatOrder)` still respects an explicit seat-order override — unchanged, pre-existing behavior | **PASS** | |

## Scope / constraint verification

| # | Test | Result | Evidence |
|---|---|---|---|
| 28 | No gameplay rules changed — `bidding-engine.js`/`table-engine.js`/`scoring-engine.js` untouched | **PASS** | `git diff --stat` — empty. |
| 29 | `GameSession` (`session.js`, both copies) and every `GameState`/`game-state.js` copy untouched | **PASS** | `git diff --stat` — empty. |
| 30 | No Firestore/Services/UI file touched (`room-service.js`, `match-service.js`, `session-service.js`, `player-service.js`, `firestore.rules`, any screen markup) | **PASS** | `git diff --stat` — empty. |
| 31 | `Deck` reuses `cards.js` exclusively — no duplicated card/suit/rank definitions anywhere in `deck.js` | **PASS** | Confirmed by direct inspection: `deck.js`'s `buildFullDeck()` calls only `Cards.DECK_SUITS`/`Cards.RANKS`/`Cards.createCard()`; no local suit/rank table exists in the file. |
| 32 | `MatchService` was NOT connected to `Deck`/`Dealer` this sprint | **PASS** | `git diff --stat` on `design-ui/match-service.js` — empty; `matches/{matchId}.gameState` remains the Sprint 3.4 placeholder. |
| 33 | No additional missing engine dependency was discovered beyond `Deck` | **PASS** | `Dealer.dealHands()` integration succeeded cleanly on the first real run — no further gap surfaced; nothing was stopped-and-documented because nothing else blocked it. |
| 34 | Zero regression on every pre-existing permanent test suite | **PASS** | `tests/match-service.test.cjs` (59/59), `tests/room-service.test.cjs` (31/31), `tests/rules-simulation.test.js` (61/61) — all still pass, none of these files were touched this sprint. |

## Not performed

Nothing to disclose beyond the project's standing limitations (no live Firebase/Firestore environment is exercised by this sprint at all — engine-only work, no Services/Firestore/UI touched). All tests in this sprint are real, executed Node.js tests against the actual engine files (`require()`, not simulated/asserted-without-running).
