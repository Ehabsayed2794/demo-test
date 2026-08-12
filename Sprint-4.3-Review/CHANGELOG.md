# Changelog — Sprint 4.3: Trick Resolution Synchronization

**A STRICT implementation sprint.** Online trick-winner synchronization ONLY. NOT scoring, NOT next round, NOT match end, NOT a redesign, NOT a refactor. `table-engine.js` remains the single, unmodified authority for trick winner, follow suit, trump, played cards, trick completion, and next leader.

## Task 1 — Architecture Verification (no engine change needed)

Confirmed `table-engine.js` already exposes everything required: `getState().phase === "RESOLVING"` (the existing signal `emit()` sets on the trick's 4th card) and `resolveTrick()` (exported since Sprint 3.6, the SAME function the real offline turn loop already calls internally). No new export was added to `table-engine.js` — byte-for-byte unchanged, re-verified directly by two separate tests reading the real file's own source.

## Added

- **`design-ui/match-adapter.js` — `applyRemoteTrick(matchId, matchDoc)`**: resolves at most one completed trick per call. Its ONLY direct engine call is `TableEngine.resolveTrick()`; the returned `winnerId` is read back from `TableEngine.getState().lastTrick.winnerId` afterward — never computed, compared, or duplicated. Guarded by the engine's own `phase !== "RESOLVING"` precondition (an ordinary no-op) plus a dedicated `lastResolvedTrickNoByMatch` idempotency registry, deliberately NOT a `version`-number gate (documented in the function's own comment: a single delivery's `cardLog` can legitimately span multiple already-completed tricks). Also mirrors the resolved trick's next leader into `GameSession.setTurn()` (an existing, unmodified setter) — a necessary completion beyond the sprint's original wording, found during end-to-end testing (see "Architecture decisions" below).
- **`design-ui/match-adapter.js` — `startTrickSync(matchId)`**: the trick-sync analog of `startBidSync()`/`startTurnSync()`/`startCardSync()` — reuses `MatchService.subscribeToMatch()` verbatim, no second listener. Its callback LOOPS (capped at 13 iterations per delivery), alternating the existing, unmodified `applyRemoteCard()` and the new `applyRemoteTrick()`, because `cardLog` is append-only and never cleared across trick boundaries — documented as orchestration, not a new algorithm.
- **`design-ui/match-adapter.js` — `getLastResolvedTrickNo(matchId)`**: test/diagnostic-only accessor for the new registry, matching this file's established convention. `resetSyncState()` extended to also clear it.

## Not changed (Task 4/5, and the full forbidden-file list)

- **`design-ui/match-service.js` — NOT MODIFIED.** Justification: the trick winner, next leader, and updated `tricksWon` are ALL deterministically re-derivable, by every client, from data already synchronized (`cardLog` + the immutable rules `table-engine.js` already enforces identically everywhere) — synchronization by determinism, not by a broadcast write. No new fact needs a new Firestore write.
- **`firestore.rules` — NOT MODIFIED**, for the identical reason: no new field is ever written for trick resolution, so there is nothing new for a rule to permit or constrain.
- `table-engine.js`, `bidding-engine.js`, `scoring-engine.js`, `dealer.js`, `deck.js`, `cards.js`, `session.js` (GameSession/GameState) — byte-for-byte unchanged.
- No trick winner persistence to Firestore, no scoring synchronization, no next round, no match end, no replay, no voice chat, no AI, no matchmaking, no Cloud Functions.

## Architecture decision beyond the original brief (documented per Task 8)

`applyRemoteTrick()` also calls the EXISTING `GameSession.setTurn()` setter after a successful resolution (only when a genuine next trick exists to lead). This was NOT anticipated in the original Task 2 wording. It became NECESSARY once end-to-end testing showed that without it, `assertLocalTurn()`'s pre-existing fallback (Sprint 4.1, unmodified) would keep reporting a stale turn-holder after every trick resolution — since nothing writes the real next leader back into `matches/{matchId}.turn` (set to `null` at the resolving boundary, Sprint 4.2.2) — blocking all further play after trick 1. The fix reuses an EXISTING, unmodified `GameSession` setter this file already calls elsewhere for the identical purpose. A related, honestly-documented consequence: `firestore.rules`' own turn-ownership check is effectively inactive for the first card of every trick after the first (since `oldData.turn` is `null` at that point) — client-side `assertLocalTurn()` is what actually gates this today, a restated instance of this project's existing "gameplay legality remains client-authoritative in this Spark MVP" limitation, not a new category of risk. See `docs/reviews/TrickResolutionSync_4.3.md`'s Task 8 answer and `docs/architecture/SecurityArchitecture.md`'s new "Trick resolution authority" section for the full account.

## Testing (labeled MOCKED or SIMULATED — never mixed; no EMULATOR/REAL claims anywhere)

- `tests/match-adapter.test.cjs` (+32 checks, 147 total, MOCKED, against a controllable fake `TableEngine`): trick resolution success (winner read back, never computed), duplicate/stale-snapshot idempotency, `ALREADY_RESOLVED`, "trick not complete" as an ordinary no-op, multiple consecutive tricks with independent per-matchId registries, malformed input, `ENGINE_UNAVAILABLE` (3 shapes), the `ENGINE_REJECTED`/desync-reporting relationship to the pre-existing `applyRemoteCard()` detection, and structural "no duplicated gameplay rule" checks.
- `tests/trick-sync.test.cjs` (new, 45 checks, MOCKED, against the REAL `table-engine.js`/`bidding-engine.js`/`match-service.js`/`match-adapter.js`): all 11 required scenarios — trick completes after the 4th card; winner matches TableEngine (cross-checked against an INDEPENDENT, test-side re-computation of the real trump/follow-suit rule); duplicate snapshot ignored; stale snapshot ignored; reconnect; late subscriber (a genuine, real one-trick backlog); malformed trick; `ENGINE_REJECTED`; desync reporting; multiple consecutive tricks (2 full, real tricks played end-to-end); no regression.
- Full regression suite re-run, zero regression: `deck` (39), `bid-sync` (39), `turn-sync` (26), `match-service` (67), `match-sync` (58), `submit-bid` (66), `submit-card` (34), `card-sync` (41), `room-service` (31), `rules-simulation` (158), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31).
- **954 automated tests total, all passing** (up from 898).

## Documentation

Updated `EngineAdapter.md`, `MatchSynchronization.md`, `MatchLifecycle.md`, `ServiceArchitecture.md`, `SecurityArchitecture.md` (new "Trick resolution authority" section). New `docs/reviews/TrickResolutionSync_4.3.md` (full Implementation Report, including the Task 8 Mandatory Honesty Review with all 8 questions answered directly). No claim of real Firestore/Emulator validation is made anywhere.
