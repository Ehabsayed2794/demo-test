# Changelog — Sprint 4.1: Turn Authority & Remote Play Validation

**This sprint is NOT about card play. This sprint is ONLY about determining WHO is allowed to act.** No `bidding-engine.js`/`table-engine.js`/`scoring-engine.js`/`Dealer`/`Deck`/`Cards` change, no gameplay rule change, no Firestore rules change, no Cloud Functions, no card/trick/score synchronization.

## Added
- **`design-ui/match-adapter.js` — `applyRemoteTurn(matchId, matchDoc)`** (Task 2): keeps `GameSession`'s top-level turn mirror (`getTurn()`/`setTurn()` — DISTINCT from `GameSession.getBiddingState().turnId`, the bidding-phase-specific field `bidding-engine.js`'s own reducer already owns, untouched by this function) continuously synchronized with `matches/{matchId}.turn`, translated uid → seat. Gated by its OWN independent version registry (`lastAppliedTurnVersionByMatch`, deliberately separate from `applyRemoteBid()`'s own — see this function's own comment for why one shared gate would be wrong) plus a content-level idempotency check. Never mutates Firestore; only ever updates `GameSession`, via its own existing `setTurn()` setter — no other engine file is called.
- **`design-ui/match-adapter.js` — `isLocalSeatsTurn(matchDoc, localSeat)` / `assertLocalTurn(matchDoc, localSeat)`** (Task 3): the gate any FUTURE gameplay-write function must call before attempting a write — "verify currentPlayer == localSeat... if false: reject locally... do not send writes." Reads the general-purpose `matches/{matchId}.turn` mirror, not the bidding-phase-specific `waitingFor` pointer, so it stays meaningful past bidding. `assertLocalTurn()` throws a structured `NOT_LOCAL_TURN` error on mismatch. Delivered ahead of its first real caller — no such write function exists in this codebase yet, same pattern as `bootstrapGameSession()` (3.9) and `applyRemoteBid()` (4.0).
- **`design-ui/match-adapter.js` — `startTurnSync(matchId)`** (Task 1): the turn-sync analog of `startBidSync()` — reuses `MatchService.subscribeToMatch()` verbatim (no second listener) and pipes deliveries through `applyRemoteTurn()`.
- One new test-only accessor: `getLastAppliedTurnVersion(matchId)`. `resetSyncState(matchId)` extended to also clear the new turn registry (both per-matchId and globally).
- **`docs/reviews/TurnAuthorityImplementation_4.1.md`** (new) — implementation report.

## The one deliberate design decision (documented, not an oversight)

`GameSession` has two turn-related fields: the top-level `turnId` mirror (previously one-shot, now ongoing per this sprint) and `GameSession.getBiddingState().turnId` (bidding-phase-specific, owned by `bidding-engine.js`'s own reducer since Sprint 3.6). This sprint synchronizes ONLY the first, and Task 3's local-authority check reads ONLY the first — because the second field becomes meaningless outside bidding (a future card-play phase has no `waitingFor`), while the first stays meaningful across every future phase. See `docs/architecture/EngineAdapter.md`'s Sprint 4.1 section for the full account.

## Why Firestore never decides whose turn it is

`applyRemoteTurn()` contains no decision rule — it only ever copies whatever `matches/{matchId}.turn` already says, translated uid → seat. Whose turn is next remains entirely the gameplay engine's decision (via `bidding-engine.js`'s own `turnId`, unchanged). Honestly stated: nothing in this codebase yet writes that computed decision back into `matches/{matchId}.turn`, so a remote opponent's client currently only sees whatever `turn` was set to at match creation — closing this gap is future work.

## Where authority lives (Task 7)
| Concern | Owner |
|---|---|
| Whose turn is next (the actual decision) | `bidding-engine.js` (bidding phase); a future card-play engine (later) |
| Turn mirror synchronization | `design-ui/match-adapter.js`'s `applyRemoteTurn()` |
| Local authority check (may THIS client act now) | `design-ui/match-adapter.js`'s `isLocalSeatsTurn()`/`assertLocalTurn()` |
| Delivery | Firestore, via `MatchService.subscribeToMatch()` (unmodified) |
| Persistence of `turn` itself | `MatchService` (existing field, set once at creation — unchanged) |

## Not changed
- `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `GameSession`'s existing API, `Dealer`, `Deck`, `Cards` — byte-for-byte unchanged.
- `design-ui/match-service.js`, `firestore.rules` — byte-for-byte unchanged. `MatchService` still has zero reference to `GameSession`/`setTurn`/any engine file.
- No card play, trick resolution, score synchronization, turn rotation *logic* (only mirror synchronization), voice, replay, reconnect improvements, AI, matchmaking, or Cloud Functions were started.

## Testing (all labeled MOCKED — no SIMULATED checks; this sprint touches no `firestore.rules`; no Firebase Emulator or real Firestore was used, consistent with every prior sprint)
- `tests/match-adapter.test.cjs` (+23 checks, 82 total): unit-level gating logic for `applyRemoteTurn()`/`isLocalSeatsTurn()`/`assertLocalTurn()` against the REAL `GameSession` (no fake needed — this function never touches `BiddingEngine`) — new snapshot, duplicate version, stale version/rollback, turn advance, content-level idempotency, no-turn-yet, four adapter-corruption cases, GameSession-unavailable, correct/wrong player, GameSession-mirror fallback, and independent-registry verification.
- `tests/turn-sync.test.cjs` (new, 26 checks): full end-to-end pipeline against the REAL `design-ui/match-service.js` and `design-ui/engine/session.js` — new snapshot, turn advance (4-step sequence), duplicate snapshot, stale snapshot/version rollback, late subscriber, listener restart (simulated disconnect/reconnect), listener duplicate event, correct/wrong player attempts (driven through the live subscription pipeline), GameSession consistency, adapter isolation, and Sprint 4.0 regression sanity.
- Full regression suite re-run, zero regression: `deck` (39), `bid-sync` (39), `match-service` (65), `match-sync` (58), `submit-bid` (66), `room-service` (31), `rules-simulation` (109, SIMULATED), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31).
- **718 automated tests total, all passing.**

## Documentation
- `docs/architecture/EngineAdapter.md` — new Sprint 4.1 section (the top-level-vs-bidding-phase turn field distinction, per-function design, "why Firestore never decides" verified in code).
- `docs/architecture/MatchSynchronization.md` — new Sprint 4.1 section (task-by-task verification, authority table).
- `docs/architecture/ServiceArchitecture.md` — MatchAdapter entry extended with the four new functions.
- `docs/architecture/MatchLifecycle.md` — Sprint 4.1 note added to the existing sprint-by-sprint callout.
- `docs/reviews/TurnAuthorityImplementation_4.1.md` — new, implementation report.
- This QA package.
