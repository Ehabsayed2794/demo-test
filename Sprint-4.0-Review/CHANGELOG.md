# Changelog — Sprint 4.0: Online Bidding Synchronization (Authority Layer)

Completes the pipeline Sprints 3.7–3.9 each built one piece of. Not a bidding-rules sprint. No `bidding-engine.js` rewrite, no gameplay logic added, no Firestore rules change.

## Added
- **`design-ui/match-adapter.js` — `applyRemoteBid(matchId, matchDoc)`** (Tasks 2/3/4): translates an accepted Firestore bid into exactly one `bidding-engine.js` action (`SubmitFinalEstimate` — the only action shape the existing schema can represent; see `docs/architecture/BidValidation.md`). Guarded by: a malformed-snapshot check; a strict version gate (`incoming.version > current.version`, no equality, no rollback); and a content-level idempotency check (an already-recorded local bid — covers both a duplicate delivery and the originating client's own bid echoing back). Never writes Firestore; only ever updates `GameSession`, and only through `bidding-engine.js`'s own unmodified reducer.
- **`design-ui/match-adapter.js` — `startBidSync(matchId)`** (Task 1): wires the complete pipeline (`Player → submitBid() → Firestore → MatchService listener → Engine Adapter → bidding-engine.js → GameSession → UI`) in one call, reusing `MatchService.subscribeToMatch()` verbatim — no second listener, no duplicated sync logic.
- Two test-only accessors: `getLastAppliedVersion(matchId)`, `resetSyncState(matchId)`.
- **`docs/reviews/BiddingSyncImplementation_4.0.md`** (new) — implementation report.

## The one deliberate scope decision (documented, not an oversight)
`applyRemoteBid()` wires ONLY the ESTIMATES phase's `SubmitFinalEstimate` action. `DASH`/`AUCTION`/`CONFIRM` actions need shapes (a boolean, a trick-count+suit+isPass combination, a different trick-count+suit combination under different legality) that Firestore's existing `bids: {seatId: rawInteger}` schema cannot represent without a schema change — explicitly out of this sprint's "do not duplicate bidding rules" scope. Wiring those would mean this adapter guessing what a bare number means outside the one context it actually represents. Documented in `match-adapter.js`, `EngineAdapter.md`, `MatchSynchronization.md`, and `MatchLifecycle.md`.

## Where authority lives (Task 7)
| Concern | Owner |
|---|---|
| Bid legality, bid order, auction/phase state | `bidding-engine.js` (unmodified — only called, never re-implemented) |
| Synchronization | Firestore, via `MatchService.subscribeToMatch()` (unmodified) |
| Persistence | `MatchService.submitBid()` (unmodified) |
| Identity/format translation + sync gating | `design-ui/match-adapter.js` |
| Rendering | UI (not built yet) |

## Not changed
- `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `GameSession`, `Dealer`, `Deck`, `Cards` — byte-for-byte unchanged.
- `design-ui/match-service.js`, `firestore.rules` — byte-for-byte unchanged. `MatchService` still has zero reference to `GameSession`/`BiddingEngine`/any engine file.
- No card play, trick resolution, scoring, turn authority/rotation, voice, replay, reconnect improvements, AI, matchmaking, or Cloud Functions were started.

## Testing (all labeled MOCKED — no SIMULATED checks; this sprint touches no `firestore.rules`; no Firebase Emulator or real Firestore was used, consistent with every prior sprint)
- `tests/match-adapter.test.cjs` (+17 checks, 59 total): unit-level gating logic against a FAKE `BiddingEngine` — engine unavailable, normal application, duplicate version, stale version/rollback, phase mismatch, wrong-seat's-turn, already-applied-locally, engine-rejected, and four "adapter corruption" (malformed snapshot) cases.
- `tests/bid-sync.test.cjs` (new, 39 checks): full end-to-end pipeline against the REAL `bidding-engine.js` — new snapshot (remote bid, real engine execution verified), duplicate snapshot, stale snapshot/version rollback, multiple sequential bids with GameSession consistency, local bid vs. remote bid (echo idempotency), late subscriber, listener restart (simulated disconnect/reconnect), listener duplicate event, and a full regression sanity pass.
- Full regression suite re-run, zero regression: `deck` (39), `match-service` (65), `match-sync` (58), `submit-bid` (66), `room-service` (31), `rules-simulation` (109, SIMULATED), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31).
- **690 automated tests total, all passing.**

## Documentation
- `docs/architecture/EngineAdapter.md` — new Sprint 4.0 section (scope decision, authority table).
- `docs/architecture/MatchSynchronization.md` — new Sprint 3.9 and 4.0 sections.
- `docs/architecture/ServiceArchitecture.md` — new "MatchAdapter (Engine Adapter)" service entry (previously missing entirely).
- `docs/architecture/MatchLifecycle.md` — Sprint 3.9/4.0 notes added to the existing sprint-by-sprint callout.
- `docs/reviews/BiddingSyncImplementation_4.0.md` — new, implementation report.
- This QA package.
