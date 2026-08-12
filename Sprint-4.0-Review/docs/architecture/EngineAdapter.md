# Engine Adapter — Sprint 3.9 (Seat ↔ Engine Synchronization)

**Scope: identity translation only.** No gameplay logic, no rule changes, no new features. `design-ui/match-adapter.js` is the ONE new file this sprint adds; nothing else in the codebase was rewritten — see "Files changed" at the end of this document.

## Why this file exists

Every prior sprint through 3.8.1 built one side or the other of a bridge that never got built:

- The gameplay engine (`Cards` → `Deck` → `Dealer` → `GameSession` → `bidding-engine.js`/`table-engine.js`/`scoring-engine.js`) identifies players by canonical **seat id** (`p1`..`p4`) — see `docs/architecture/GameEngine.md`.
- `matches/{matchId}` (Sprint 3.4 onward) identifies players by real Firebase Auth **uid**, and — since Sprint 3.8 — also carries a `seats` map (`docs/architecture/SeatIdentityModel.md`) that is the sole authority for which uid owns which seat.
- Nothing translated between the two. `GameSession.getRemoteMatch()` (Sprint 3.7) returns the raw, uid-keyed Firestore document with no interpretation at all — a deliberate, documented gap in every sprint since 3.7 ("this remains the prerequisite for whichever future sprint wires `bidding-engine.js` itself to real multiplayer state").

Sprint 3.9 is that future sprint's *first* piece: the translation layer itself. It deliberately does not go further (no bid/card/trick sync, no turn authority) — see "Non-responsibilities" below.

## Responsibilities

1. **Seat resolution** (Task 2) — `uidToSeat`, `seatToUid`, `seatToPlayer`, `playerToSeat`. All four read `matches/{matchId}.seats` (passed in as part of a plain match-document object) fresh on every call — never a second, separately-maintained mapping. Deterministic on malformed/duplicate data (see "Identity translation" below) rather than throwing or behaving arbitrarily.
2. **Pure state translation** (Task 4) — `matchDocToEngineSnapshot(matchDoc)` and its exact inverse, `engineSnapshotToMatchPatch(snapshot)`. Both are pure functions: same input always produces the same output, the input is never mutated, and neither function has any side effect (no Firestore call, no GameSession call, no global state read or written). This is what makes the Task 6 round-trip requirement ("Firestore → Engine → Firestore must produce identical data") meaningfully testable — and it is tested, exactly, for every field this adapter touches (see `tests/match-adapter.test.cjs`).
3. **Engine bootstrap** (Task 3) — `bootstrapGameSession(matchDoc)`. The one function in this file with real, documented side effects: it translates a match document via `matchDocToEngineSnapshot()` and applies the result to the LOCAL `GameSession` using only `GameSession`'s existing, unmodified public setters (`setRound`, `setDealer`, `setTurn`). Returns the translated snapshot so a caller can see exactly what was (and wasn't) resolved.

## Non-responsibilities — stated explicitly, not left implicit

- **No gameplay logic of any kind.** No bid legality, no auction resolution, no trick-winner determination, no scoring. `bidding-engine.js`, `table-engine.js`, and `scoring-engine.js` are never imported, required, or called by this file.
- **No dealing.** `Dealer`/`Deck`/`Cards` are never imported, required, or called by this file.
- **No turn authority.** Translating a raw `turn` uid into a seat id via `uidToSeat` and storing it via `GameSession.setTurn()` (an existing setter that already accepted any string) is not enforcement of whose turn it is — it carries a value through, unchanged in meaning, exactly matching Task 1's "it only translates identities." `turn` still has no real gameplay meaning, unchanged from every prior sprint's own honest framing.
- **Bootstrap does NOT write `players` or bidding sub-state into `GameSession`, even though Task 3 lists "Load players" and "Load bidding state" as bootstrap responsibilities.** This is a deliberate, reasoned scope decision, not an oversight:
  - `session.players` has an established RICH shape (`{id, name, initial, isUser, isAI, isRemote, rank, rp, wins, streak, level, coins, gems}` — see `session.js`'s `mockPlayers()`). The Firestore match document has none of that data (only a flat uid array) — populating it would mean inventing fake names/ranks/stats, which this adapter has no authority or data to do correctly. "Load players" is satisfied by the returned snapshot's own `players`/`seats` fields, available for a correctly-scoped future integration to consume — not by corrupting an existing field with fabricated data.
  - `session.biddingState` has an established shape OWNED by `bidding-engine.js`'s own reducer (`phase`, `bids: {seatId: {type, amount}}`, `activeBidders`, `actionHistory`, ...). Firestore's `bids` map is differently shaped (`{seatId: rawOpaqueValue}` — see `docs/architecture/BidValidation.md`). Writing one into the other would silently corrupt a contract `bidding-engine.js` depends on. "Load bidding state" is satisfied by the returned snapshot's `biddingOpen`/`bidsBySeat`/`lastBidSeat` fields — a one-time, read-only inclusion in the bootstrap OUTPUT, not a write into `GameSession`.
  - This is also exactly why this is "load," not "synchronize": the stop list says "DO NOT synchronize bids." A snapshot returned once, at bootstrap time, is not an ongoing sync mechanism — there is no subscription, no live update loop, nothing that keeps `bidsBySeat` current after the one call. Wiring that up for real is exactly the kind of thing the next, correctly-scoped sprint should do, once bidding-engine.js's own reducer is ready to accept externally-supplied seat-keyed bids without corrupting its own state machine.
- **No new Firestore writes, no new listener.** This file never calls `db()`, never calls `MatchService.subscribeToMatch()`, and is not itself a data source — it only processes whatever plain match-document object its caller already has (from `MatchService.loadMatch()`, or from a `subscribeToMatch()`/`GameSession.getRemoteMatch()` callback).
- **No UI change.** Nothing in any screen calls this file yet — it is delivered, tested, and documented as a standalone layer, not wired into any existing flow. Wiring it into an actual gameplay screen is future work.

## Identity translation — the exact rules

| Direction | Function | Behavior on success | Behavior on miss/malformed input |
|---|---|---|---|
| uid → seat | `uidToSeat(matchDoc, uid)` | The seat whose value equals `uid` | `null` — never throws for an unrecognized uid |
| seat → uid | `seatToUid(matchDoc, seatId)` | `matchDoc.seats[seatId]` | `null` for a seat that doesn't exist in THIS match (e.g. `p3` in a 2-player match) or an unrecognized name entirely |
| seat → player | `seatToPlayer(matchDoc, seatId)` | `{ seatId, uid }` — a MINIMAL identity descriptor, not a profile | `null` |
| player → seat | `playerToSeat(matchDoc, player)` | Accepts a raw uid string, or an object with `.uid` or `.id` | `null` |

**On duplicate seats** (two different seat keys mapping to the same uid — should never occur through a legitimate write, since `firestore.rules`' `isValidSeatMap()` enforces uniqueness at creation; see `SeatIdentityModel.md`): `uidToSeat` resolves deterministically to whichever seat sorts first in canonical order (`p1` before `p2` before `p3` before `p4`, non-canonical names sorted alphabetically after all four) — never an arbitrary `Object.keys()` ordering accident, and never a thrown error for data that, however malformed, is still just data. This is a defensive, documented choice: this adapter never trusts its input blindly, matching this project's established "neither layer trusts the other alone" principle, even though the layer that would normally prevent this (rules) already exists and is trusted to have done its job.

## Data ownership

- **`matches/{matchId}.seats` is read-only from this file's perspective.** This adapter never writes it, never invents a value for it, and never maintains a second copy anywhere. Every seat-resolution call re-reads it from whatever match-document object was passed in.
- **`GameSession`'s existing fields (`round`, `dealerId`, `turnId`) are the only GameSession state this sprint ever writes**, and only via `GameSession`'s own pre-existing setters — this file introduces no new GameSession field, no new sessionStorage key, and required no change to `session.js` at all.
- **This file is the only one that interprets the Firestore match-document shape AND calls into `GameSession`'s API in the same place.** `design-ui/match-service.js` continues to know nothing about `GameSession`; `design-ui/engine/session.js` and every other engine file continue to know nothing about Firestore's document shape. The one honest, pre-existing exception (`GameSession.subscribeToRemoteMatch()`, Sprint 3.7, calling `MatchService.subscribeToMatch()` directly) is recorded in `match-adapter.js`'s own header comment rather than hidden — that call never interprets the Firestore shape (it stores the raw document opaquely), so it does not conflict with this file being the only INTERPRETER of both schemas, even though it is not the only CALLER across the boundary.

## Future extension points

- **Real player profile enrichment.** `seatToPlayer()` currently returns `{seatId, uid}` only. A future sprint could extend it to also fetch/merge `players/{uid}` (via `PlayerService.getPlayerProfile`) for a real display name/avatar/rank — this would need a new, explicit dependency on `PlayerService` (currently zero), and should get its own sprint rather than being folded into this one.
- **Wiring `bidsBySeat`/`biddingOpen` into `bidding-engine.js`.** Once that engine's reducer is ready to accept seat-keyed, externally-supplied bid values without conflicting with its own internal `bids: {seatId: {type, amount}}` shape, `bootstrapGameSession()`'s returned snapshot already has everything a future integration needs — no change to THIS file would be required, only a new caller.
- **Live re-translation on every `subscribeToMatch()` update.** Today, `bootstrapGameSession()` is a one-shot call. A future sprint could call `matchDocToEngineSnapshot()` again on every live update (via `GameSession.onRemoteMatchUpdate()`, already available since Sprint 3.7) to keep a translated view continuously current — this is exactly "synchronize," deliberately not built here.
- **Turn authority.** Once whose-turn-it-is enforcement is designed, it will need `uidToSeat`/`seatToUid` exactly as they exist today — no change anticipated to this file, only a new caller layer built on top.

## Testing summary

`tests/match-adapter.test.cjs` — 42 checks, all **MOCKED** (real `match-adapter.js` and `session.js` code, hand-constructed plain-object match documents standing in for Firestore data — no Firestore mock is needed since this file never touches Firestore). Covers: seat resolution (all four helpers, both directions), missing seat (a 2-player match), duplicate seat (deterministic, non-throwing), unknown uid, unknown seat, bootstrap success (including verifying `GameSession`'s existing `players`/`biddingState` fields are left completely untouched), bootstrap with invalid data (null/non-object matchDoc, missing seats, `GameSession` unavailable), full round-trip determinism and exactness, and a structural isolation check (no `require()` of `match-service.js`/`session.js`/any engine file anywhere in `match-adapter.js`'s own source).

Full regression suite re-run, zero regression: `deck` (39), `match-service` (65), `match-sync` (58), `submit-bid` (66), `room-service` (31), `rules-simulation` (109, SIMULATED), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31). **613 automated tests total, all passing.**

## Sprint 4.0 (Online Bidding Synchronization: Authority Layer)

Sprint 3.9 built the translation layer and named "wiring `bidsBySeat`/`biddingOpen` into `bidding-engine.js`... once that engine's reducer is ready" as a future extension point. Sprint 4.0 is that point, for exactly one case.

### `applyRemoteBid(matchId, matchDoc)` — Task 2/3/4

Translates the latest accepted bid on a Firestore match document into exactly one `bidding-engine.js` action — `SubmitFinalEstimate` — and nothing else. Guarded by three independent checks, all documented in the function's own comment in `match-adapter.js`:

- **Malformed-snapshot rejection** (Task 2) — a non-object, a missing/non-numeric `version`, or a missing `bids` map is rejected before any version bookkeeping or engine call happens at all.
- **Strict version gate** (Task 3) — `incoming.version > current.version`, checked with this adapter's OWN small per-matchId registry (`lastAppliedVersionByMatch`). Equal versions (a duplicate delivery) and lower versions (a stale/rolled-back delivery) are both rejected, never applied, never treated as an error.
- **Content-level idempotency** (Task 4, the "local bid" case) — even a genuinely NEW version is not re-applied if the local engine already has ANY bid recorded for that seat (`engineState.bids[seatId] != null`). This is what makes a client's own bid, echoed back through its own Firestore subscription after already being applied locally and directly (the normal, responsive-UI pattern), a no-op rather than a double execution.

**This is a deliberate instance of "defense in depth," not a duplication of `MatchService.subscribeToMatch()`'s own, separate ordering/duplicate guard (Sprint 3.7, activated 3.8).** That guard protects Firestore DELIVERY — does this client even get told about a stale/duplicate snapshot. This one protects the ENGINE specifically — given a delivery (whether or not it already passed the other guard), should it be replayed into `bidding-engine.js` right now. Two different failure surfaces, two independent checks, the same "neither layer trusts the other alone" principle this project has applied at every other boundary since Sprint 3.4.1.

### Why only `SubmitFinalEstimate` — the exact scope boundary, restated

`MatchService.submitBid()`'s Firestore schema (Sprint 3.8, range-hardened 3.8.1) stores exactly one opaque, generically-validated integer (0-13) per seat. That shape matches ONE `bidding-engine.js` action precisely — a final trick estimate. It does not, and cannot without a schema change, represent a DASH-call decision (a boolean), an auction bid (a trick count AND a suit AND an isPass flag), or a confirm-call (a trick count AND a suit, in a different phase with different legality rules). Wiring any of THOSE would mean this adapter GUESSING what a bare number means in a context it wasn't designed for — inventing a mapping, which is exactly the "duplicated/invented gameplay rule" every hard constraint in this sprint (and the one before it) forbids. So: this sprint wires the one phase whose data shape already exists. The other phases remain exactly as unconnected as Sprint 3.9 left them, and this is stated as a real, current limitation, not implied to be solved.

### `startBidSync(matchId)` — Task 1, the full pipeline in one call

```
Player -> submitBid() -> Firestore -> MatchService listener -> Engine Adapter -> bidding-engine.js -> GameSession -> UI
```

`startBidSync()` is literally this: it calls `MatchService.subscribeToMatch()` (unmodified, Sprint 3.7/3.7.1/3.8's ref-counted, ordering-guarded, auto-reconnecting listener — reused exactly as any other caller would use it, not reimplemented) and pipes every delivery through `applyRemoteBid()`. No second listener. No new sync logic. The "MatchService listener" stage of the pipeline is Sprint 3.7's own code, called, not duplicated.

### Where authority lives (Task 7's explicit ask)

| Concern | Owner | Enforced by |
|---|---|---|
| Bid legality (is this trick count allowed for this seat right now) | `bidding-engine.js` | Its own, unmodified `emit()` reducer — `applyRemoteBid()` only reads its response (`rejected`/`reason`), never second-guesses it |
| Bid order (whose turn) | `bidding-engine.js` | `state.waitingFor`, read by `applyRemoteBid()`, never computed independently |
| Auction state (phase, caller, trump, With) | `bidding-engine.js` | `state.subPhase` and friends — `applyRemoteBid()` only checks `subPhase === "ESTIMATES"` as a GATE, never advances or infers a phase itself |
| Synchronization (delivering the latest write to every client) | Firestore, via `MatchService.subscribeToMatch()` | Sprint 3.7/3.7.1/3.8, unmodified |
| Persistence (writing an accepted bid) | `MatchService.submitBid()` | Sprint 3.8/3.8.1, unmodified |
| Identity/format translation (uid ↔ seat, Firestore shape ↔ engine action) | `match-adapter.js` | This file, and only this file |
| Rendering | UI | Not built yet — no screen calls any of this |

**Why `bidding-engine.js` remains the single source of truth:** every fact about whether a bid is legal, whose turn it is, and what phase the auction is in comes from calling into the real, unmodified engine and reading what it says — never from re-deriving or assuming any of that inside the adapter. If the engine rejects an action (`engineResult.rejected`), `applyRemoteBid()` reports `ENGINE_REJECTED` and stops — it does not retry with a different shape, does not fall back to writing GameSession directly, and does not treat the rejection as this adapter's problem to work around. The engine's decision is final, exactly as if a local player had triggered the same illegal action offline.

## Files changed

**Sprint 3.9:**
- `design-ui/match-adapter.js` — new.
- `tests/match-adapter.test.cjs` — new.
- `docs/architecture/EngineAdapter.md` — new (this document).

**Sprint 4.0:**
- `design-ui/match-adapter.js` — additive: `applyRemoteBid()`, `startBidSync()`, and two test-only accessors (`getLastAppliedVersion`, `resetSyncState`). Every Sprint 3.9 function unchanged.
- `tests/match-adapter.test.cjs` — additive: unit-level gating tests for `applyRemoteBid()` against a fake `BiddingEngine`.
- `tests/bid-sync.test.cjs` — new: full end-to-end tests against the REAL `bidding-engine.js`.
- Nothing else. `design-ui/engine/session.js` (`GameSession`), `Dealer`, `Deck`, `Cards`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `design-ui/match-service.js`, `firestore.rules`, and every UI screen remain byte-for-byte unchanged across both sprints.
