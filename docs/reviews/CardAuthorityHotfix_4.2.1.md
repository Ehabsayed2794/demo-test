# Implementation Report — Sprint 4.2.1: Pre-Write Card Authority & Desync Safety

**Sprint type:** a hotfix closing two Critical correctness defects a direct review of Sprint 4.2's shipped code found. NOT Trick Resolution, NOT Winner Detection, NOT Scoring, NOT a UI redesign, NOT a `table-engine.js` rewrite. Spark only.

## 1. The two Critical defects, restated precisely

1. **`MatchService.submitCard()` never called any turn-authority check.** Any authenticated player who owned a seat could write a card to `matches/{matchId}.cardLog` regardless of whose turn it actually was — the function checked seat OWNERSHIP but never seat TURN.
2. **Card legality was checked only after the card was already durably written.** `submitCard()` wrote first (an opaque, generically-shape-checked payload); legality was only ever evaluated later, by whichever client's `applyRemoteCard()` replayed the log through `TableEngine.emit()`. If the engine rejected it, the invalid entry remained permanently inside `cardLog` — nothing removed it — while the rejecting adapter's own bookkeeping (before this hotfix) advanced its processed count/version PAST it, silently claiming the snapshot was fully synchronized.

Together, these meant Firestore's own history and every client's local engine state could permanently diverge, with no mechanism to detect or halt on it.

## 2. Task 9-equivalent verification (performed before Task 2's implementation): does `table-engine.js` support pre-write validation without mutation?

**Finding: YES**, once one small, purely additive export is added. `table-engine.js` ALREADY contains `isLegal(id, card)`/`legalCards(id)` — pure, read-only internal functions `emit()` itself calls before it mutates anything. These were simply never exposed on `window.TableEngine`. Added `canPlayCard(playerId, card)` — a new function, in the SAME file, that composes ONLY the pre-existing conditions `emit()` already checks (`state.phase`, `state.turn`, `isLegal()`) — zero new rules, zero changes to `emit()`/`isLegal()`/`legalCards()`, and exported via ONE new line in the existing `window.TableEngine = {...}` object, mirroring Sprint 3.6's own "minimum wiring export" precedent (`resolveTrick`/`getState` were added the exact same way, for the exact same reason: making already-existing internal state/logic reachable from outside without rewriting anything). This is NOT a rewrite of `table-engine.js` — nothing above the new function was touched.

**Conclusion: a genuine, pure, non-mutating validation path exists. No Architecture Blocker was required.**

## 3. Task 1 — Turn Authority, implemented

`submitCard()` now resolves the acting seat via `MatchAdapter.uidToSeat()` and calls `MatchAdapter.assertLocalTurn()` — Sprint 4.1's EXISTING authority gate, called verbatim, never reimplemented — via a plain (non-transactional) `matchRef.get()` BEFORE `runTransaction()` is ever invoked. A wrong-turn caller is rejected `NOT_YOUR_TURN` with the transaction never started at all. The SAME check is re-run inside the transaction against a freshly-read document (defense in depth against a race between the two reads — this project's established "neither layer trusts the other alone" principle, applied here for the first time to a check that must happen BEFORE, not merely alongside, the write).

**Verified directly**: `tests/submit-card.test.cjs`'s "Test #1 (wrong-turn rejection)" confirms a wrong-turn attempt leaves `version`/`cardLog` completely untouched AND never even reaches the engine-validation gate (Task 2) at all.

## 4. Task 2 — Pre-Write Engine Validation, implemented

`submitCard()` now calls `global.TableEngine.canPlayCard(seatId, card)` — after the Task 1 gate passes, BEFORE `runTransaction()` is invoked. An illegal card (wrong suit, not in hand — `canPlayCard()`'s `isLegal()` check covers both, since `legalCards()` only ever returns cards from the claimed seat's OWN hand) is rejected `ILLEGAL_CARD` with zero writes attempted. If `TableEngine`/`canPlayCard` isn't reachable at all, `submitCard()` refuses to write blind (`ENGINE_UNAVAILABLE`) rather than silently skipping validation.

**Verified directly**: `tests/submit-card.test.cjs`'s "Test #3"/"Test #4" confirm an engine-rejected card never reaches `cardLog` — `STORE[...].cardLog.length` stays at `0`, unlike Sprint 4.2's original defect where the entry would have been written and stayed there permanently.

**Honest, documented limitation**: `canPlayCard()` is checked ONCE, against the local browser's own `TableEngine` instance, before the transaction begins — not re-checked on every transaction retry. This is correct for what a Firestore version conflict on this document actually represents (a concurrent WRITE, not a change to this client's own local hand), but a genuinely concurrent LOCAL mutation (a remote card arriving via `applyRemoteCard()` between this validation and the transaction's commit) remains a residual race this Spark-only, client-authoritative design cannot fully close without a Cloud Function serializing authority and persistence in one atomic server step — explicitly out of this hotfix's scope.

**Architectural consequence discovered while writing tests, documented rather than hidden**: this hotfix makes a previously-plausible UI pattern — "call `TableEngine.emit()` directly for instant local feedback, THEN call `submitCard()` to persist the same play" — now INCORRECT, because by the time `submitCard()` asks the engine to validate, the engine's own `state.turn` has already advanced past that seat (the direct `emit()` call moved it). This is a deliberate, correct consequence of closing the two Critical defects, not a new bug. The corrected architecture: `submitCard()` validates-then-persists first (never mutating on its own), and the actual `TableEngine.emit()` mutation happens exactly once, uniformly, through this SAME client's own `applyRemoteCard()` echo — never via a separate, earlier, direct local `emit()` call racing the pre-write gate. `tests/card-sync.test.cjs`'s "local echo" test was corrected to model this accurately.

## 5. Task 3 — Remote Rejection Causes Desync, Not Silent Skip

`applyRemoteCard()` now STOPS processing immediately on an `ENGINE_REJECTED` entry. It does not look at any later entry in that delivery. `lastAppliedCardCountByMatch` advances only up to (not past) the rejected index — entries genuinely applied earlier in the SAME call are never re-emitted on a future call, but the rejected entry and everything after it remain unresolved. `lastAppliedCardVersionByMatch` is NOT advanced at all on a desync, so a future delivery (a retry, a reconnect, or simply the next live update) correctly re-attempts from the SAME stuck index rather than being treated as "already fully handled." The function never retries on its own — it returns a structured result (`{desync: true, reason: "ENGINE_REJECTED", matchId, index, seatId, engineReason, appliedCount, results}`) and leaves the retry decision entirely to whatever calls it.

**Verified directly**: `tests/match-adapter.test.cjs`'s three new Task-5 checks (requirements #7/#8/#9) construct a THREE-entry delivery where the MIDDLE entry is deterministically rejected (by card identity, not call count) and prove: the count stops at the rejected index (not 0, not 2, not 3); the engine is never asked about the entry AFTER the rejected one; the version registry stays `null` (not marked as fully synchronized); a redelivery of the same stuck snapshot re-attempts the SAME index (not silently skipped); and once the underlying condition is fixed, processing resumes normally through to the end.

## 6. Task 4 — Card Log Integrity Assessment

**Formally assessed, not assumed.** CEL (Firestore Rules, `rules_version '2'`) has no documented primitive for comparing two lists index-by-index without a `range()`/slice() construct it does not have. The closest available tool — `oldLog.all(x, x in newLog)` — proves only multiset membership, not position, so it would not even catch a REORDERING of two existing entries (only an outright removal). **Conclusion: it cannot be done safely with currently-supported CEL syntax.** No unsupported construct was invented.

**The gap is demonstrated, not merely asserted**: two new SIMULATED tests in `tests/rules-simulation.test.js` prove, against this project's own 1:1 rules translation, that (a) rewriting an earlier entry's card value and (b) reordering two earlier entries BOTH currently pass `isValidCardSubmission()` unchanged, provided exactly one new, well-formed entry is also appended.

**`cardLog` is marked client-authoritative, MVP-only** as of this sprint — explicitly not harmless, explicitly not sufficient for ranked/competitive play. Two concrete, NOT-implemented-this-sprint directions are documented for a future sprint: a Cloud Function owning all `cardLog` writes server-side (Blaze), or restructuring card plays as a `matches/{matchId}/plays/{autoId}` subcollection with `create`-only permissions (Firestore's own native immutability guarantee — no CEL trick needed, Spark-compatible, the more promising direction) — see `docs/architecture/SecurityArchitecture.md`'s "Card write authority" section for the full account.

## 7. Regression

Full suite re-run after every change: `deck` (39), `match-adapter` (109, up from 100), `bid-sync` (39), `turn-sync` (26), `card-sync` (41, corrected for the new architecture), `match-service` (67), `match-sync` (58), `submit-bid` (66), `submit-card` (49, up from 32, substantially rewritten for the new gates), `room-service` (31), `rules-simulation` (142, up from 140, 2 new vulnerability-demonstration checks), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31) — all passing. **870 automated tests total.**

Two test-file design flaws were found and fixed while writing this hotfix's own tests (both are TEST bugs, not production bugs):
- `tests/submit-card.test.cjs` and `tests/card-sync.test.cjs` needed to seed/update `matches/{matchId}.turn` correctly for every submission under test — Sprint 4.2's original tests never exercised the turn field at all, since nothing checked it.
- `tests/card-sync.test.cjs`'s "local card" scenario modeled an invalid post-hotfix sequence (direct `emit()` before `submitCard()`) — corrected per §4 above.

## 8. Honest limitations / what remains

- `matches/{matchId}.turn` still does not reflect `table-engine.js`'s own real, locally-advancing turn order during the PLAY phase (Sprint 4.1's pre-existing, documented gap, inherited unchanged).
- `canPlayCard()`'s single pre-transaction check (not re-checked per retry) leaves a narrow, inherent race for a genuinely concurrent local engine mutation — documented, not closed (would need a Cloud Function).
- `cardLog` prefix/order integrity is NOT provable by `firestore.rules` alone — documented and demonstrated, not fixed, per Task 4's own explicit scope.
- No trick resolution, winner detection, score synchronization, end match, replay, voice chat, AI, or matchmaking work was started, per this hotfix's explicit stop list.

## 9. Conclusion

Both Critical defects named in this sprint's Context are closed: `submitCard()` now enforces turn authority AND card legality BEFORE any Firestore write, using only the existing `MatchAdapter` authority API and one minimal, additive `TableEngine` export — no gameplay rule was duplicated, no engine file was rewritten. `applyRemoteCard()` now treats an engine rejection as a durable, diagnosable desync rather than a silent skip. The one rules-layer limitation that cannot be safely closed with current CEL is assessed, demonstrated, and documented rather than papered over. Stopping here per the brief's stop condition — no Trick Resolution, Winner Detection, Score Synchronization, End Match, Replay, Voice Chat, AI, or Matchmaking. Waiting for review.
