# Changelog — Sprint 4.2.1: Pre-Write Card Authority & Desync Safety

**A hotfix, not a feature sprint.** Closes two Critical correctness defects a direct review of Sprint 4.2's shipped code found. NOT Trick Resolution, NOT Winner Detection, NOT Scoring, NOT a UI redesign, NOT a `table-engine.js` rewrite, NOT Cloud Functions. Spark only.

## The two Critical defects closed

1. **`MatchService.submitCard()` never called `assertLocalTurn()` and never verified the authenticated player's seat owned the current turn before writing.** Any seat-owner could write regardless of turn order.
2. **Card legality was checked only after the card was already appended to Firestore.** An engine-rejected card stayed in `cardLog` permanently while the local adapter advanced its processed count/version past it — Firestore history and local engine state could silently diverge.

## Added

- **`design-ui/engine/table-engine.js` — `canPlayCard(playerId, card)`**: ONE new, purely additive, non-mutating export — the pure validation path Task 2 required. Composes ONLY the exact conditions `emit()` already checks (`state.phase`, `state.turn`, the pre-existing internal `isLegal()`) — zero new rules, zero changes to `emit()`/`isLegal()`/`legalCards()`. Mirrors Sprint 3.6's own "minimum wiring export" precedent exactly.
- **`design-ui/match-service.js` — `submitCard()` hardened** (Task 1 + Task 2): now calls `MatchAdapter.assertLocalTurn()` (Sprint 4.1's existing gate, reused verbatim — never reimplemented) and `TableEngine.canPlayCard()` — BOTH before `runTransaction()` is ever invoked. A wrong-turn caller is rejected `NOT_YOUR_TURN`; an illegal card is rejected `ILLEGAL_CARD`; an unreachable engine is rejected `ENGINE_UNAVAILABLE` (refuses to write blind). Zero Firestore writes occur for any of these three cases — proven directly in tests.
- **`design-ui/match-adapter.js` — `applyRemoteCard()` hardened** (Task 3): on `ENGINE_REJECTED`, now stops processing immediately, never looks at a later entry in that delivery, advances `lastAppliedCardCountByMatch` only up to (never past) the rejected index, and does NOT advance `lastAppliedCardVersionByMatch` at all. Returns a structured `{desync: true, reason: "ENGINE_REJECTED", matchId, index, seatId, engineReason, appliedCount, results}`. Never auto-retries.

## Task 4 — Card Log Integrity Assessment (documented, not fixed — by design)

Formally assessed whether `firestore.rules` can safely prove `cardLog`'s prefix (every entry before the newly-appended one) is unchanged and unreordered. **Conclusion: it cannot, with currently-supported CEL** — `rules_version '2'` has no index-by-index list-comparison primitive (no `range()`/slice()); the closest tool, `.all(x, x in newLog)`, proves only multiset membership, not position, so it would not even catch a reordering. No unsupported CEL was invented. **Demonstrated, not merely asserted**: two new SIMULATED tests in `tests/rules-simulation.test.js` prove a rewrite AND a reordering of earlier `cardLog` entries both currently pass `isValidCardSubmission()` unchanged. `cardLog` is now explicitly marked **client-authoritative, MVP-only** — not suitable for ranked/competitive play. Two future directions documented (not built): a Cloud Function owning all `cardLog` writes server-side, or restructuring card plays as an append-only `matches/{matchId}/plays/{autoId}` subcollection (`create`-only, `update`/`delete` denied — Firestore's own native immutability, no CEL trick needed, Spark-compatible, the more promising direction).

## Architectural consequence discovered while writing this sprint's own tests

A UI pattern of "call `TableEngine.emit()` directly for instant local feedback, THEN call `submitCard()` to persist the same play" is now INCORRECT — by the time `submitCard()` validates, the engine's own `state.turn` has already moved past that seat. The corrected architecture: `submitCard()` validates-then-persists first (never mutating locally on its own); the actual mutation happens exactly once, uniformly, through this SAME client's own `applyRemoteCard()` echo. Documented in `EngineAdapter.md`'s Sprint 4.2.1 section and `docs/reviews/CardAuthorityHotfix_4.2.1.md`.

## Not changed
- `bidding-engine.js`, `scoring-engine.js`, `Dealer`, `Deck`, `Cards` — byte-for-byte unchanged.
- `RoomService`, `PlayerService`, `SessionService` — byte-for-byte unchanged.
- `firestore.rules` — byte-for-byte unchanged (Task 4 concluded no safe fix exists there; the fix lives entirely client-side in `submitCard()`).
- `table-engine.js` above the new `canPlayCard()` addition — byte-for-byte unchanged; no gameplay rule was duplicated, rewritten, or reimplemented anywhere.
- No trick resolution, winner detection, score synchronization, end match, replay, voice chat, AI, or matchmaking work was started.

## Testing (labeled MOCKED, SIMULATED, EMULATOR, or REAL — never mixed; no EMULATOR/production-validation claims made anywhere)
- `tests/match-adapter.test.cjs` (+9 checks, 109 total, MOCKED): proves ENGINE_REJECTED desync semantics precisely — stuck index, no later-entry processing, version not advanced, durable redelivery, recovery once resolved — plus a structural "no gameplay rules duplicated outside TableEngine" check.
- `tests/submit-card.test.cjs` (substantially rewritten, 49 checks, up from 32, MOCKED, against a controllable fake `TableEngine`): wrong-turn rejection, correct-turn success, illegal-card rejection before persistence, ownership rejection, exactly-once write, engine-consulted-exactly-once, ENGINE_UNAVAILABLE refuses to write blind, plus every pre-existing check re-verified against the new gates.
- `tests/card-sync.test.cjs` (corrected, 41 checks, MOCKED, against the REAL `table-engine.js`): every scenario now keeps `matches/{matchId}.turn` synchronized with the real engine's own turn; the "local echo" scenario corrected to model the new, valid architecture (no direct local `emit()` racing the pre-write gate).
- `tests/rules-simulation.test.js` (+2 checks, 142 total, SIMULATED): demonstrates the Task 4 finding directly.
- Full regression suite re-run, zero regression beyond the deliberate, documented test corrections above: `deck` (39), `bid-sync` (39), `turn-sync` (26), `match-service` (67), `match-sync` (58), `submit-bid` (66), `room-service` (31), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31).
- **870 automated tests total, all passing.**
- Two test-file design flaws found and fixed while writing this hotfix's own tests (both TEST bugs, not production bugs): `submit-card.test.cjs`/`card-sync.test.cjs` needed to seed/update `matches/{matchId}.turn` correctly for every submission (Sprint 4.2's original tests never exercised the turn field, since nothing checked it); `card-sync.test.cjs`'s "local card" scenario modeled an architecture pattern the hotfix correctly makes invalid.

## Documentation
- `docs/reviews/CardAuthorityHotfix_4.2.1.md` — new, dedicated implementation report.
- `docs/reviews/CardSyncImplementation_4.2.md` — corrective pointer added at the top (history left unedited, per this project's "correct forward, don't rewrite history" convention).
- `docs/architecture/EngineAdapter.md` — new Sprint 4.2.1 section.
- `docs/architecture/MatchSynchronization.md` — correction notice + new Sprint 4.2.1 section.
- `docs/architecture/ServiceArchitecture.md` — `submitCard()` entry updated with both new gates.
- `docs/architecture/MatchLifecycle.md` — Sprint 4.2.1 note added to the sprint-by-sprint callout.
- `docs/architecture/SecurityArchitecture.md` — new "Card write authority" section, updated `matches/{matchId}` table row, cardLog risk added to the soft-approximation list.
- This QA package.
