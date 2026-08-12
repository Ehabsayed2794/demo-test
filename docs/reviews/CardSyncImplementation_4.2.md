> **UPDATE — Sprint 4.2.1 (Pre-Write Card Authority & Desync Safety):** a direct review of this sprint's shipped code found two Critical correctness defects — (1) `submitCard()` never checked whose turn it was before writing, and (2) card legality was checked only AFTER the card was already durably written, meaning an engine-rejected card stayed in `cardLog` permanently while the local adapter's own bookkeeping claimed the snapshot was fully synchronized. **Both are now fixed** — see `docs/reviews/CardAuthorityHotfix_4.2.1.md` for the full account. This document is left otherwise unedited, as the historical record of Sprint 4.2's own original (now-superseded) state — per this project's "correct forward, don't rewrite history" convention already used for Sprint 3.7→3.7.1.
>
> **FURTHER UPDATE — Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync Hardening):** `submitCard()` still never wrote the NEXT turn back to `matches/{matchId}.turn` even after Sprint 4.2.1's fixes — it appended the card but left the previous player named as the active turn, so the next player's own submission was incorrectly rejected. `applyRemoteCard()` also silently skipped a `MALFORMED_ENTRY` item and compared local-echo identity by seat only, not by card. **All three are now fixed** — see `docs/reviews/CardTurnProgressionHotfix_4.2.2.md` for the full account.

# Implementation Report — Sprint 4.2: Online Card Synchronization (Engine Authority)

**Sprint type:** synchronizes legal card plays while preserving the gameplay engine as the single authority. NOT trick resolution, NOT scoring, NOT winner detection. No `Dealer`/`Deck`/`Cards`/`ScoringEngine`/bidding-rules/turn-rules change, no `RoomService`/`PlayerService`/`SessionService` change, no Firestore collection-layout change, no Cloud Functions.

## 1. Task 9 — Architecture Verification (performed FIRST, before any implementation)

**Question:** does `table-engine.js` already expose the minimum API card synchronization needs?

**Finding: YES.** `window.TableEngine = { initState, emit, resolveTrick, getState }` (unchanged since Sprint 3.6's minimum-export treatment):
- `emit({type:"PlayCard", playerId, card})` — internally handles follow-suit legality (`isLegal()`), whose-turn checking (`state.turn !== playerId` → rejected), and all trick/hand bookkeeping. Returns `{rejected, reason}` — the EXACT same "engine owns the decision, adapter only reads the response" shape `BiddingEngine.emit()` already proved correct in Sprint 4.0.
- `getState()` — exposes `phase`/`turn`/`plays`/`ledSuit`/`hands`, everything a translation layer needs to read.
- `GameSession.getPlayState()` (pre-existing, unmodified since Sprint 3.6) independently exposes the same facts for any caller that doesn't want to touch `TableEngine` directly.

**No missing API was found. No engine change was needed. Implementation proceeded per the brief's own "if sufficient, proceed" branch.** This finding is REAL (verified by reading `table-engine.js` in full and, separately, by a manual `node -e` smoke test driving a real bidding round to completion, requiring `table-engine.js`, and calling `applyRemoteCard()` against it end-to-end before any formal test file was written) — not MOCKED, not SIMULATED.

## 2. Executive Summary

`design-ui/match-service.js` gained one new method — `submitCard(matchId, card)` (Task 1). `design-ui/match-adapter.js` gained `applyRemoteCard(matchId, matchDoc)` (Task 2/4/5) and `startCardSync(matchId)` (pipeline wiring). `firestore.rules` gained `isValidCardSubmission()` (the second real `matches/{matchId}` update rule). Task 3 ("Authority Gate... verify assertLocalTurn()") reuses Sprint 4.1's EXISTING `assertLocalTurn()` VERBATIM — no new authority function was written. `table-engine.js`, `bidding-engine.js`, `scoring-engine.js`, `Dealer`, `Deck`, `Cards`, `GameSession`'s existing API, `RoomService`, `PlayerService`, `SessionService` are byte-for-byte unchanged.

## 3. The schema difference from bids/turns, and why it changes the design

A bid and a turn each change ONE opaque value per accepted write. A card play is different: up to 13 tricks × 4 seats = 52 distinct, permanent, ordered facts per round. `matches/{matchId}.cardLog` is therefore an APPEND-ONLY array of `{seatId, card}` tuples, not a single-slot map. This required a SECOND piece of adapter state beyond the usual version gate — `lastAppliedCardCountByMatch` (how many array entries have been replayed) alongside `lastAppliedCardVersionByMatch` (the usual document-version gate) — so a late subscriber or a reconnect that missed several deliveries correctly replays every entry it hasn't seen, in order, exactly once.

## 4. Task-by-task verification

- **Task 1 (Card Submission):** `submitCard(matchId, card)` uses a real Firestore transaction, calls `MatchAdapter.uidToSeat()` (and ONLY that — never `TableEngine`, never `GameSession`), never evaluates card legality (confirmed by code inspection — no follow-suit check, no hand check, no turn check anywhere in the function), and only persists (`cardLog` append, `lastCardSeat`, `version++`).
- **Task 2 (Remote Card Application):** `applyRemoteCard()` receives a Firestore snapshot, translates through the SAME entries `submitCard()` already seat-keyed at write time (no separate read-time translation needed — see §5), calls ONLY `TableEngine.emit()`, updates `GameSession` only through that call, never mutates Firestore (confirmed — no `db()`/write-path reference anywhere in the function).
- **Task 3 (Authority Gate):** `MatchAdapter.assertLocalTurn()` — Sprint 4.1's existing function, called verbatim. Verified directly in `tests/card-sync.test.cjs`'s "wrong turn rejection"/"correct player accepted" checks, which construct a matchDoc and confirm the SAME pre-existing function rejects the wrong seat and accepts the correct one, exactly as it did in Sprint 4.1 — no behavior change, no new function.
- **Task 4 (Duplicate Protection):** verified directly — a duplicate snapshot delivery, a triple redundant re-notify after a listener restart, and a stale/rolled-back forged snapshot all leave `TableEngine.getState().plays`/`.turn` completely unchanged, checked against the REAL engine's own state, not inferred.
- **Task 5 (Version Gate):** malformed snapshots (non-object, non-numeric version, non-array `cardLog`) rejected before any engine call; equal/lower versions rejected against `applyRemoteCard()`'s own independent registry; verified with a forged, truncated `cardLog` at a lower version that the engine never rolls back.
- **Task 6 (Adapter Isolation):** `design-ui/match-adapter.js` remains the ONLY file calling `TableEngine.emit()`/`getState()` on behalf of a remote update — confirmed by this sprint's own forbidden-scope sweep. The one new dependency edge — `MatchService.submitCard()`'s own read-only call into `MatchAdapter.uidToSeat()` — is translation-only, never an engine call; `MatchService` still has ZERO reference to `GameSession`/`BiddingEngine`/`TableEngine`, confirmed the same way.

## 5. Why `applyRemoteCard()` needs no uid→seat translation of its own

`cardLog` entries are seat-keyed (not uid-keyed) BY THE TIME they reach Firestore — the ONE uid→seat translation this sprint needs happens at WRITE time, inside `submitCard()`, via `MatchAdapter.uidToSeat()`. `applyRemoteCard()` itself reads `entry.seatId` directly and passes it straight to `TableEngine.emit()` — exactly analogous to how `applyRemoteBid()` reads `matchDoc.lastBidSeat` directly, with no translation step of its own either.

## 6. Honest verification method

Every one of the 122 new checks (18 unit-level in `tests/match-adapter.test.cjs`, 32 in `tests/submit-card.test.cjs`, 41 end-to-end in `tests/card-sync.test.cjs`, 31 SIMULATED in `tests/rules-simulation.test.js`) is labeled per this sprint's exact instruction — MOCKED, SIMULATED, or REAL, never mixed. The end-to-end suite (`tests/card-sync.test.cjs`) is the most significant: it drives the REAL `bidding-engine.js` to a committed round, requires the REAL `table-engine.js` (deliberately AFTER bidding completes — table-engine.js's `PLAYERS`/`ROUND_CFG` are computed once at require()-time, the same documented constraint `match-flow-integration.test.cjs` already worked around in Sprint 3.6), and proves a mocked `submitCard()` write genuinely drives the real trick-taking reducer forward — legal follow-suit resolution, real turn advancement, a real completed trick — not merely that the right function was called.

One real implementation detail surfaced only by writing these tests, not anticipated in advance: `table-engine.js`'s `state` is a SHARED, module-level singleton (unlike `bidding-engine.js`'s `initState()`, which can be called fresh per scenario within one process) — every card-sync scenario in `tests/card-sync.test.cjs` plays into the SAME underlying trick unless explicitly finished first. This required a test-only `finishCurrentTrick()` helper (direct, non-synced engine calls to complete a trick between scenarios) — a test-harness detail, not a production code change, and not a bug in `table-engine.js` itself (the same "single-round-per-process" constraint Sprint 3.6's own integration test already documented).

## 7. Regression

Full suite re-run after every change: `deck` (39), `match-adapter` (100, up from 82), `bid-sync` (39), `turn-sync` (26), `card-sync` (41, new), `match-service` (67, up from 65), `match-sync` (58), `submit-bid` (66), `submit-card` (32, new), `room-service` (31), `rules-simulation` (140, up from 109, SIMULATED), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31) — all unchanged except the ones this sprint touched, zero regression. **842 automated tests total, all passing** (718 pre-existing + 124 new/changed).

One test-only bug found and fixed while writing this sprint's tests: `tests/turn-sync.test.cjs`'s own "adapter isolation" check used a bare `/GameSession/` regex against `match-service.js`'s source, which false-positived on this sprint's own comment prose (naming `GameSession` while explaining the file still never calls it). Fixed to check actual usage patterns (`GameSession.` / `.setTurn(`) instead of a bare word match — a test-harness fix, not a production behavior change.

## 8. Honest limitations / what remains

- `matches/{matchId}.turn` still does not reflect `table-engine.js`'s own real, locally-advancing turn order during the PLAY phase — the same class of gap Sprint 4.1 documented for bidding, inherited (not introduced) by this sprint's reuse of `assertLocalTurn()`.
- `cardLog` is append-only and never cleared, even across trick/round boundaries — trick-boundary or round-boundary clearing is trick-resolution/round-transition territory, explicitly out of this sprint's scope. Bounded in practice to ≤52 entries per round, since no Firestore write path advances `currentRound` yet either.
- `firestore.rules`' `isValidCardSubmission()` verifies the log grew by exactly one entry and that entry is well-formed, but does NOT independently re-verify every earlier entry is byte-for-byte unchanged — CEL has no built-in for index-by-index list comparison without a range()/zip() primitive. Documented directly in `firestore.rules`' own comment, same "state a real gap plainly" convention as `isValidSeatMap()`'s own precedent. The client-side version gate plus the real engine's own legality re-check remain the actual, meaningful protection.
- No UI calls `startCardSync()`/`submitCard()`/`assertLocalTurn()` yet — delivered, tested, documented, not wired into any screen.
- `firestore.rules`' own CEL constructs remain unverified against a real Firestore emulator — unchanged, pre-existing limitation, not touched this sprint.

## 9. Conclusion

The acceptance criteria are met and tested: a legal card played by one player appears exactly once on every connected client (verified via `tests/card-sync.test.cjs`'s full end-to-end pipeline against the real engine); the engine executes exactly once per genuinely new card; no duplicated execution, rendering, or listener occurs (verified across duplicate/stale/late-subscriber/listener-restart/listener-duplicate scenarios); no gameplay rule was duplicated or rewritten; `firestore.rules` never validates card legality (only a generic shape). Stopping here per the brief's stop condition — no trick resolution, winner detection, score synchronization, end match, replay, voice chat, reconnect improvements, AI, leaderboard, or Cloud Functions were started. Waiting for review.
