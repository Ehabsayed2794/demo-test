# Changelog — Sprint 3.9: Engine Adapter Layer (Seat ↔ Engine Synchronization)

New isolation layer. Not a feature sprint. No gameplay logic, no rule changes, no Firestore rules changes, no UI redesign.

## Added
- **`design-ui/match-adapter.js`** (new) — the ONE file in this codebase permitted to know both the Firestore match-document schema and the gameplay engine's schema:
  - **Task 2, Seat Resolution:** `uidToSeat`, `seatToUid`, `seatToPlayer` (returns a minimal `{seatId, uid}` descriptor — not a full profile), `playerToSeat` (accepts a raw uid or a `{uid}`/`{id}` object). All read `matches/{matchId}.seats` fresh every call; no separate mapping maintained anywhere.
  - **Task 4, State Translation:** `matchDocToEngineSnapshot(matchDoc)` and its exact inverse `engineSnapshotToMatchPatch(snapshot)` — both pure functions (no I/O, no mutation, no side effects), verified via a determinism test and an exact round-trip test.
  - **Task 3, Engine Bootstrap:** `bootstrapGameSession(matchDoc)` — translates a match document and applies dealer/turn/round metadata to the LOCAL `GameSession` via its existing, unmodified public setters only. Deliberately does NOT write `players` or bidding sub-state into `GameSession` (see below).
- **`tests/match-adapter.test.cjs`** (new, 42 checks, all MOCKED).
- **`docs/architecture/EngineAdapter.md`** (new) — responsibilities, non-responsibilities, identity translation rules, data ownership, future extension points.
- **`docs/reviews/EngineAdapterImplementation_3.9.md`** (new) — implementation report.

## Deliberate scope decisions (documented, not oversights)
- `bootstrapGameSession()` does NOT write `players` (Firestore's flat uid array) into `GameSession.players` (an established rich profile shape with name/rank/etc. that doesn't exist on the match document) — the translated data is available on the returned snapshot instead.
- `bootstrapGameSession()` does NOT write bidding sub-state (`biddingOpen`/`bids`/`lastBidSeat`) into `GameSession.biddingState` (an established shape owned by `bidding-engine.js`'s own reducer, differently shaped from Firestore's raw values) — again, available on the returned snapshot only. This is "load," a one-time read, not "synchronize," an ongoing two-way sync — explicitly out of this sprint's stop list.
- Duplicate-seat data (which should never occur given `firestore.rules`' existing `isValidSeatMap()`) resolves deterministically (canonical seat order) rather than throwing or behaving arbitrarily.
- `seatToPlayer()` returns a minimal identity descriptor, not a full profile — real profile enrichment would need a new `PlayerService` dependency, named as a future extension point, not built here.

## Not changed
- `design-ui/engine/session.js` (`GameSession`), `Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js` — byte-for-byte unchanged.
- `design-ui/match-service.js`, `firestore.rules` — byte-for-byte unchanged.
- No UI screen calls this adapter yet — delivered, tested, and documented as a standalone layer.
- No bid/card/trick synchronization, no turn authority, no gameplay writes were started, per the brief's explicit stop list.

## Testing
- `tests/match-adapter.test.cjs` (new, 42 checks, all **MOCKED** — no Firestore mock needed, since this adapter never touches Firestore; no SIMULATED checks, since this sprint touches no `firestore.rules`): seat resolution (all four helpers), missing seat, duplicate seat, unknown uid, unknown seat, bootstrap success (including confirming `GameSession`'s existing `players`/`biddingState` fields are left untouched), bootstrap with invalid data (null/non-object matchDoc, missing seats, `GameSession` unavailable), full round-trip exactness (4-player and 2-player matches), determinism, no-mutation, and a structural isolation check (no `require()` of `match-service.js`/`session.js`/any engine file in `match-adapter.js`'s own source).
- Full regression suite re-run, zero regression: `deck` (39), `match-service` (65), `match-sync` (58), `submit-bid` (66), `room-service` (31), `rules-simulation` (109, SIMULATED), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31).
- **613 automated tests total, all passing.**

## Documentation
- `docs/architecture/EngineAdapter.md` — new, full design.
- `docs/reviews/EngineAdapterImplementation_3.9.md` — new, implementation report.
- This QA package.
