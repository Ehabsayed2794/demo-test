# Changelog — Sprint 4.2: Online Card Synchronization (Engine Authority)

**Synchronizes legal card plays while preserving the gameplay engine as the single authority.** NOT trick resolution, NOT scoring, NOT winner detection. No `Dealer`/`Deck`/`Cards`/`ScoringEngine`/bidding-rules/turn-rules change, no `RoomService`/`PlayerService`/`SessionService` change, no Firestore collection-layout change, no Cloud Functions.

## Task 9 — Architecture Verification (performed first, before any implementation)

Verified `table-engine.js` already exposes the minimum API needed: `TableEngine.emit({type:"PlayCard", playerId, card})` (internally handles follow-suit legality, whose-turn checking, trick bookkeeping — returns `{rejected, reason}`) and `TableEngine.getState()` (exposes `phase`/`turn`/`plays`/`ledSuit`/`hands`) — the exact same shape `BiddingEngine.emit()`/`getState()` already proved out for bids in Sprint 4.0. **No missing API found; no engine change needed; implementation proceeded.**

## Added
- **`design-ui/match-service.js` — `submitCard(matchId, card)`** (Task 1): a second real gameplay write. Deliberately `(matchId, card)` — no `seatId` parameter, unlike `submitBid()`. The acting seat is resolved internally from the calling uid via `MatchAdapter.uidToSeat()` (a new, read-only, translation-only dependency edge — "Calls MatchAdapter only," per Task 1's own instruction). Runs inside a real Firestore transaction; does NOT evaluate card legality — only a GENERIC shape check (`isValidGenericCardValue()`); appends `{seatId, card}` to a new, append-only `matches/{matchId}.cardLog` field, sets `lastCardSeat`, increments `version` by exactly 1.
- **`design-ui/match-adapter.js` — `applyRemoteCard(matchId, matchDoc)`** (Task 2/4/5): replays every not-yet-applied `cardLog` entry, IN ORDER, through `TableEngine.emit()` — the ONLY call this function makes into any engine file. Gated by a THIRD independent version registry (`lastAppliedCardVersionByMatch`, alongside bid's and turn's — none shared) PLUS a second "how many entries replayed" counter (`lastAppliedCardCountByMatch` — needed because, unlike a bid or turn, a card delivery can carry multiple new entries at once). Per-entry content-level idempotency (checks the engine's CURRENT trick for an existing play from that seat before re-emitting). Never mutates Firestore.
- **`design-ui/match-adapter.js` — `startCardSync(matchId)`**: the card-sync analog of `startBidSync()`/`startTurnSync()` — reuses `MatchService.subscribeToMatch()` verbatim.
- Two new test-only accessors: `getLastAppliedCardVersion(matchId)`, `getLastAppliedCardCount(matchId)`. `resetSyncState()` extended to also clear both new registries.
- **`firestore.rules` — `isValidCardSubmission()`**: the second real `matches/{matchId}` update rule (after `isValidBidSubmission()`). Verifies authentication, match membership, seat ownership, a generic card shape, version-increment-by-exactly-1, and that the log grew by exactly one entry. **Honest, documented limitation**: does NOT independently re-verify every earlier log entry is byte-for-byte unchanged (CEL has no index-by-index list-comparison primitive) — see the rule's own comment for the full account, mirroring `isValidSeatMap()`'s own precedent for stating a real CEL gap plainly.
- **`docs/reviews/CardSyncImplementation_4.2.md`** (new) — implementation report, including the Architecture Verification finding.

## Task 3 (Authority Gate) — reused, not reinvented

"Before sending any card: verify assertLocalTurn(). If false: reject locally. No Firestore write" is satisfied by Sprint 4.1's EXISTING `assertLocalTurn()`, called verbatim. **No new authority function was written.** This reuse inherits (does not introduce) Sprint 4.1's own documented gap: the top-level turn mirror isn't yet kept current during the PLAY phase, since nothing writes `table-engine.js`'s own real turn order back into `matches/{matchId}.turn`.

## The one new dependency edge (documented, not hidden)

`MatchService.submitCard()` now has a soft, read-only reference to `MatchAdapter.uidToSeat()` — introduced because Task 1 explicitly requires it ("Calls MatchAdapter only"). This makes the `MatchAdapter ⇄ MatchService` reference bidirectional at the soft-global level, but each direction serves a distinct, non-overlapping purpose (MatchAdapter → MatchService: read-only subscription, since Sprint 3.9; MatchService → MatchAdapter: read-only seat translation, new this sprint) and neither file gained a dependency on any ENGINE file it didn't already have. `MatchService` still has ZERO reference to `GameSession`/`BiddingEngine`/`TableEngine`, confirmed by this sprint's own forbidden-scope sweep.

## Where authority lives (Task 8)
| Concern | Owner |
|---|---|
| Card legality, follow-suit, trick state, played cards, next player | `table-engine.js` (unmodified — only called, never re-implemented) |
| Synchronization | Firestore, via `MatchService.subscribeToMatch()` (unmodified) |
| Persistence | `MatchService.submitCard()` (new, generic-shape-only) |
| Identity/format translation + sync gating | `design-ui/match-adapter.js` |
| Local authority check | `design-ui/match-adapter.js`'s `assertLocalTurn()` (REUSED from Sprint 4.1) |
| Rendering | UI (not built yet) |

## Not changed
- `table-engine.js`, `bidding-engine.js`, `scoring-engine.js`, `Dealer`, `Deck`, `Cards`, `GameSession`'s existing API — byte-for-byte unchanged.
- `RoomService`, `PlayerService`, `SessionService` — byte-for-byte unchanged.
- No trick resolution, winner detection, score synchronization, end match, replay, voice chat, reconnect improvements, AI, leaderboard, or Cloud Functions were started.

## Testing (labeled MOCKED, SIMULATED, or REAL — never mixed, per this sprint's explicit instruction)
- **REAL**: Task 9's Architecture Verification finding itself (read `table-engine.js` in full; confirmed via a manual `node -e` smoke test against the real engine before any formal test file existed).
- `tests/match-adapter.test.cjs` (+18 checks, 100 total, MOCKED, against a fake `TableEngine`): new card, duplicate, stale/rollback, multiple-sequential-in-one-delivery, local-card idempotency, engine-rejected, four adapter-corruption cases, no-new-cards.
- `tests/submit-card.test.cjs` (new, 32 checks, MOCKED, against the REAL `match-service.js`): normal submission, sequential cards, seat resolution (never trusting a client-claimed seat), 9 generic-shape rejection cases, every failure path, realtime-sync-through-the-unmodified-pipe.
- `tests/card-sync.test.cjs` (new, 41 checks, MOCKED, against the REAL `table-engine.js`/`bidding-engine.js`): the full acceptance criteria end-to-end — valid card sync, duplicate/stale/new snapshots, multiple sequential cards (a full 4-play trick), remote vs. local card, late subscriber, listener restart, listener duplicate event, wrong-turn rejection/correct-player-accepted, adapter corruption, GameSession consistency.
- `tests/rules-simulation.test.js` (+31 checks, 140 total, SIMULATED): `isValidNewMatchV4`, `isValidCardShape`, `isValidCardSubmission`.
- `tests/match-service.test.cjs` (+2 checks, 67 total, MOCKED): `cardLog`/`lastCardSeat` initial-shape checks.
- Full regression suite re-run, zero regression: `deck` (39), `bid-sync` (39), `turn-sync` (26), `match-sync` (58), `submit-bid` (66), `room-service` (31), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31).
- **842 automated tests total, all passing.**
- One test-harness-only fix along the way: `tests/turn-sync.test.cjs`'s "adapter isolation" check had a bare-word regex that false-positived on this sprint's own comment prose naming `GameSession` — fixed to check actual usage patterns instead. No production behavior change.

## Documentation
- `docs/architecture/EngineAdapter.md` — new Sprint 4.2 section (Architecture Verification finding, schema-shape rationale, per-function design, honest CEL limitation).
- `docs/architecture/MatchSynchronization.md` — new Sprint 4.2 section (task-by-task verification, authority table).
- `docs/architecture/ServiceArchitecture.md` — `MatchService.submitCard()` entry added; `MatchAdapter` entry extended with the two new functions.
- `docs/architecture/MatchLifecycle.md` — Sprint 4.2 note added to the existing sprint-by-sprint callout.
- `docs/reviews/CardSyncImplementation_4.2.md` — new, implementation report.
- This QA package.
