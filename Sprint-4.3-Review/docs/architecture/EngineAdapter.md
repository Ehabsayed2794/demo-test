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

**Sprint 4.1 testing summary:** `tests/match-adapter.test.cjs` gained 23 new checks (82 total, MOCKED, against the REAL `GameSession` — no fake `BiddingEngine` needed since `applyRemoteTurn()` never touches it) covering new snapshot, duplicate, stale/rollback, turn advance, content-level idempotency, no-turn-yet, four adapter-corruption cases, GameSession-unavailable, `isLocalSeatsTurn`/`assertLocalTurn` (correct player accepted, wrong player rejected, null-seat handling, GameSession-mirror fallback), and independent-registry verification. `tests/turn-sync.test.cjs` (new, 26 checks, MOCKED, against the REAL `design-ui/match-service.js`) covering: new snapshot, turn advance (a 4-step sequence), duplicate snapshot, stale snapshot/version rollback, late subscriber, listener restart, listener duplicate event, correct/wrong player attempts (driven through the live subscription pipeline, not just unit-level matchDoc checks), GameSession consistency, adapter isolation, and Sprint 4.0 regression sanity. **718 automated tests total, all passing**, all labeled MOCKED (no SIMULATED checks — this sprint touches no `firestore.rules`).

## Sprint 4.2 (Online Card Synchronization: Engine Authority)

**Scope: synchronizing legal card plays while preserving the gameplay engine as the single authority — NOT trick resolution, NOT scoring, NOT winner detection.** This sprint's own Task 9 (Architecture Verification) required checking, BEFORE writing any code, whether `table-engine.js` already exposes the minimum API needed. It does: `TableEngine.emit({type:"PlayCard", playerId, card})` (returns `{rejected, reason}`, internally handling follow-suit legality, whose-turn checking, and trick/card bookkeeping — exactly the same "engine owns the decision, adapter only reads the response" shape `BiddingEngine.emit()` already proved out in Sprint 4.0) and `TableEngine.getState()` (exposes `phase`/`turn`/`plays`/`ledSuit`/`hands` — everything this adapter needs to read). `GameSession.getPlayState()` (pre-existing, unmodified since Sprint 3.6) independently exposes the same facts for any caller that doesn't want to touch `TableEngine` directly. **No missing API was found; no engine change was needed; this sprint's implementation proceeded.**

### The schema difference from bids/turns, and why it changes the design

A bid and a turn each change ONE opaque value per accepted write — `applyRemoteBid()`/`applyRemoteTurn()` each only ever need to react to "the current value changed." A card play is different: up to 13 tricks × 4 seats = 52 DISTINCT card plays happen per round, each one a permanent, ordered fact that must be delivered to every client — not a single mutable value. `matches/{matchId}.cardLog` is therefore an APPEND-ONLY array of `{seatId, card}` tuples (never rewritten, never cleared this sprint — trick/round-boundary clearing is trick-resolution territory, explicitly out of scope) rather than a single-slot map like `bids`. This is why `applyRemoteCard()` needs a SECOND piece of state beyond the usual version gate: not just "is this version newer," but "which INDICES in this array have I already replayed" (`lastAppliedCardCountByMatch`), so a late subscriber or a reconnect that missed several deliveries correctly replays every entry it hasn't seen yet, in order, exactly once — not just the most recent one.

### `MatchService.submitCard(matchId, card)` — Task 1

Deliberately `(matchId, card)` — no `seatId` parameter, unlike `submitBid(matchId, seatId, bid)`. The acting seat is resolved INTERNALLY from the calling uid via `MatchAdapter.uidToSeat(match, callingUid)` — Task 1's own "Calls MatchAdapter only." This is a NEW, documented dependency edge (`MatchService → MatchAdapter`, read-only, translation-only) — see "Adapter Isolation" below for why this does not reintroduce the "MatchService knows the engine" coupling this project has structurally avoided since Sprint 3.4. Runs inside a real Firestore transaction; does NOT evaluate card legality (a client could submit any structurally-valid card, not necessarily one in their hand or one that follows suit) — it only appends `{seatId, card}` to `cardLog`, sets `lastCardSeat`, and increments `version` — "must only persist synchronized state."

### `MatchAdapter.applyRemoteCard(matchId, matchDoc)` — Task 2/4/5

Replays every `cardLog` entry not yet applied, IN ORDER, through `TableEngine.emit({type:"PlayCard", playerId, card})` — the ONLY call this function makes into any engine file. Three independent guards, mirroring `applyRemoteBid()`'s own three-layer pattern:

- **Malformed-snapshot rejection** — non-object, missing/non-numeric `version`, non-array `cardLog`.
- **Version gate, its OWN independent registry** (`lastAppliedCardVersionByMatch`) — a THIRD gate alongside bid's and turn's, none shared, for the same reason already established in Sprint 4.1: a single delivery can carry a new bid AND a new turn AND new cards at once, and a shared gate would let whichever check ran first silently consume the version for the others.
- **Content-level idempotency, per entry** — before emitting, checks whether the engine's CURRENTLY OPEN trick already has a play recorded for that seat (covers the "local echo" case exactly like `applyRemoteBid()`'s `ALREADY_APPLIED_LOCALLY`); a malformed INDIVIDUAL entry within an otherwise well-formed log is skipped, not thrown.

`cardLog` entries are already SEAT-keyed by the time they reach Firestore (translated at WRITE time, inside `submitCard()`) — so `applyRemoteCard()` itself performs no uid↔seat translation at all; it reads `entry.seatId` directly, exactly like `applyRemoteBid()` reads `matchDoc.lastBidSeat` directly.

### Task 3 — Local Authority Validation: reused, not reinvented

Task 3 names the exact mechanism to use: `assertLocalTurn()` — Sprint 4.1's EXISTING function, called verbatim, with NO new function written for this sprint. **Honest, pre-existing limitation this reuse inherits, not introduced by this sprint:** `assertLocalTurn()` checks `matches/{matchId}.turn` (or `GameSession.getTurn()`'s mirror), and nothing in this codebase writes a computed turn back into that field during the PLAY phase — `TableEngine.emit()` correctly maintains ITS OWN turn tracking (`GameSession.getPlayState().turnId`, a THIRD, still-separate field, exactly analogous to `getBiddingState().turnId`), but nothing mirrors it into the top-level field either. This is the SAME class of gap Sprint 4.1 already documented for bidding, restated here rather than silently inherited — and it is explicitly NOT a blocker for this sprint (Task 3 only asks that the existing gate be consulted before a future write; fixing the underlying gap would mean building a new turn-computation/write-back path, itself Turn-Rotation-adjacent scope this sprint's stop list excludes).

### Task 6 — Adapter Isolation, and the one new dependency edge

`design-ui/match-adapter.js` remains the ONLY layer that calls `TableEngine.emit()`/`getState()` on behalf of a remote update — confirmed by this sprint's own forbidden-scope sweep. The one genuinely NEW thing this sprint introduces is `MatchService`'s own soft reference to `MatchAdapter.uidToSeat()` (see `submitCard()`'s comment above) — a READ-ONLY, translation-only call, never a write, never an engine call. This makes the `MatchAdapter ⇄ MatchService` reference bidirectional at the soft-global level (MatchAdapter → MatchService for read-only subscription, since Sprint 3.9; MatchService → MatchAdapter for read-only seat translation, new this sprint) but each direction serves a distinct, non-overlapping purpose, and NEITHER file gained a dependency on `GameSession`/`BiddingEngine`/`TableEngine` that it didn't already have — `MatchService` specifically still has ZERO reference to any engine file, confirmed by this sprint's own sweep.

### Honest, documented CEL limitation (firestore.rules)

`isValidCardSubmission()` verifies the log grew by exactly one entry and that the new entry is well-formed and correctly attributed — it does NOT independently re-verify that every earlier entry is byte-for-byte unchanged, because CEL (Firestore Rules, rules_version '2') has no built-in for comparing two lists index-by-index without a range()/zip() primitive. This is documented directly in `firestore.rules`' own comment, in the same "state a real gap plainly rather than invent an unverified workaround" spirit as `isValidSeatMap()`'s own precedent — the client-side version gate plus the real engine's own legality re-check remain the actual, meaningful protection for this project's own client.

### Where authority lives (Task 8's "engine authority" ask)

| Concern | Owner | Enforced by |
|---|---|---|
| Card legality (follow-suit, is this seat's turn, is the card in hand) | `table-engine.js` | Its own, unmodified `emit()` reducer — `applyRemoteCard()` only reads its response, never second-guesses it |
| Current trick state, played cards, next player | `table-engine.js` | `state.plays`/`state.turn`/`state.ledSuit`, read by `applyRemoteCard()`, never computed independently |
| Synchronization (delivering every play to every client) | Firestore, via `MatchService.subscribeToMatch()` | Unmodified |
| Persistence (writing an accepted card) | `MatchService.submitCard()` | New this sprint, generic-shape-only |
| Identity/format translation (uid ↔ seat) | `design-ui/match-adapter.js` | This file, called from BOTH `submitCard()` (write-time) and `applyRemoteCard()` (implicitly, since entries are already seat-keyed) |
| Rendering | UI | Not built yet |

**Why Firestore never validates card legality:** `submitCard()` never calls `TableEngine`, never checks a hand, never checks whose turn it is — it only appends an opaque, generically-shape-checked payload. `firestore.rules`' `isValidCardSubmission()` mirrors the same generic-only philosophy `isValidBidSubmission()` established in Sprint 3.8 — a real suit key and a real rank range, nothing about legality. Every actual legality decision is made by calling into the real, unmodified `table-engine.js` and reading what it says — exactly the same principle stated for bidding and restated for turns.

### Testing summary

`tests/match-adapter.test.cjs` gained 18 new checks (100 total, MOCKED, against a fake `TableEngine`) covering new card, duplicate, stale/rollback, multiple-sequential-in-one-delivery, local-card idempotency, engine-rejected, four adapter-corruption cases, and no-new-cards. `tests/submit-card.test.cjs` (new, 32 checks, MOCKED, against the REAL `match-service.js`) covering normal submission, sequential cards, seat resolution (never trusting a client-claimed seat), generic-shape rejection (9 cases), and every failure path (missing args, unauthenticated, not-found, Firestore-unavailable, MatchAdapter-unavailable), plus realtime-sync-through-the-unmodified-pipe verification. `tests/card-sync.test.cjs` (new, 41 checks, MOCKED, against the REAL `table-engine.js`/`bidding-engine.js`) covering the full acceptance criteria end-to-end: valid card sync, duplicate/stale/new snapshots, multiple sequential cards (a full 4-play trick), remote vs. local card, late subscriber, listener restart, listener duplicate event, wrong-turn rejection / correct-player-accepted (via `assertLocalTurn()`), adapter corruption, and GameSession consistency. `tests/rules-simulation.test.js` gained 31 new SIMULATED checks (140 total) for `isValidNewMatchV4`/`isValidCardShape`/`isValidCardSubmission`. **842 automated tests total, all passing.**

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

## Sprint 4.1 (Turn Authority & Remote Play Validation)

**Scope: determining WHO is allowed to act, and keeping that fact synchronized — not card play.** Sprint 4.0 completed the bidding-VALUE pipeline; this sprint is the separate, narrower question of whose TURN it is, synchronized the same way. Three new, additive functions, none of them touching `bidding-engine.js` or any other engine file:

### The critical distinction this sprint's design turns on

`GameSession` has TWO different fields that could plausibly be called "whose turn is it," and this sprint deliberately touches only one of them:

| Field | Owner | What it means | Touched this sprint? |
|---|---|---|---|
| `GameSession.getTurn()` / `.setTurn()` (top-level `session.turnId`) | A Firestore-facing MIRROR — previously written only once, at bootstrap (Sprint 3.9's `bootstrapGameSession()`) | "Whose turn is it, per whatever `matches/{matchId}.turn` currently says" | **Yes** — `applyRemoteTurn()` keeps this continuously current. |
| `GameSession.getBiddingState().turnId` | `bidding-engine.js`'s OWN reducer (`recordEstimate()` etc., unchanged since Sprint 3.6) | "Who bids next, during the bidding phase specifically" | **No** — untouched, exactly as it already was. |

These are genuinely different fields with genuinely different owners. Sprint 4.0's `applyRemoteBid()` already keeps the second one correct (by calling into `bidding-engine.js`'s own reducer, which updates its own `turnId` as a normal part of processing a bid — nothing new this sprint). The first one, the top-level mirror, was previously a one-shot value from bootstrap — this sprint makes it an ONGOING sync, which is what "synchronize the active seat... Firestore snapshots must update the local turn" (Task 1) actually asks for, using the general-purpose field that stays meaningful in every future phase (bidding, and eventually card play), not the bidding-phase-specific one that will become meaningless the moment bidding ends.

### `applyRemoteTurn(matchId, matchDoc)` — Task 2

Keeps `GameSession`'s top-level turn mirror continuously synchronized with `matches/{matchId}.turn`, translated uid → seat. Guarded by the same three-layer pattern `applyRemoteBid()` established:

- **Malformed-snapshot rejection** — a non-object, a missing/non-numeric `version`, or a `turn` uid that resolves to no real seat (`UNKNOWN_TURN_SEAT`) is rejected before any GameSession mutation.
- **Strict version gate** — its OWN independent per-matchId registry (`lastAppliedTurnVersionByMatch`), deliberately SEPARATE from `applyRemoteBid()`'s `lastAppliedVersionByMatch`. Both gate against the same underlying `matchDoc.version` counter, but they gate DIFFERENT effects (a bid application vs. a turn-mirror update) — a single shared registry would mean whichever function ran first for a given version silently blocks the other from ever seeing that version at all (e.g. one delivery that both advances the turn AND carries a new bid would only get one of the two effects applied). Verified directly: `MatchAdapter.getLastAppliedVersion()` and `MatchAdapter.getLastAppliedTurnVersion()` can and do report different values for the same matchId.
- **Content-level idempotency** — a genuinely newer version whose seat already matches `GameSession.getTurn()`'s current value is `ALREADY_CURRENT`, not re-applied (no gratuitous re-render).

**Never mutates Firestore, never touches `bidding-engine.js` or any other engine file.** The ONLY GameSession mutation this function performs is `GameSession.setTurn()` — an existing, unmodified public setter Sprint 3.9's `bootstrapGameSession()` already established as safe to call from this file.

### Why Firestore never decides whose turn it is (Task 7's explicit ask, verified in code)

`applyRemoteTurn()` never computes, infers, or advances a turn value of its own — it only ever COPIES whatever `matches/{matchId}.turn` already says, translated uid → seat. No line in this function contains a decision rule ("if X then it's seat Y's turn next") — only a lookup and a setter call. This is checked directly, not just asserted: read the function's own body in `design-ui/match-adapter.js` and confirm there is no branch that produces a turn value not already present verbatim in `matchDoc.turn`.

Whose turn is next REMAINS entirely the gameplay engine's decision — `bidding-engine.js`'s own reducer computes it (via its own `turnId`, untouched by this sprint), and no code in this codebase writes that computed value back into `matches/{matchId}.turn` yet. This is an honest, stated gap, not a hidden one: it means a REMOTE opponent's client sees a stale `matches/{matchId}.turn` (whatever it was set to at match creation) rather than the real, locally-advancing bidding turn, until a future sprint adds the write-back path. Recorded here rather than glossed over, matching this project's established documentation-honesty convention.

### `isLocalSeatsTurn(matchDoc, localSeat)` / `assertLocalTurn(matchDoc, localSeat)` — Task 3

The gate ANY future gameplay-write function (card play, once it exists) must call BEFORE attempting a Firestore write. Reads the current whose-turn signal — preferring `matchDoc.turn` (translated) when a matchDoc is given, falling back to `GameSession.getTurn()`'s own mirror otherwise — and compares it against `localSeat`. Deliberately does NOT read `BiddingEngine.getState().waitingFor`: that field is bidding-phase-specific and becomes meaningless outside bidding (e.g. during a future card-play phase this sprint does not implement), whereas the general-purpose `matches/{matchId}.turn` mirror stays meaningful across every future phase.

`isLocalSeatsTurn()` returns a plain boolean, never throws. `assertLocalTurn()` throws a structured `NOT_LOCAL_TURN` error otherwise — the client-side half of this project's established "neither layer trusts the other alone" defense-in-depth principle (the eventual server-side half is a future `firestore.rules` rule on whichever gameplay-write field gets added next, not built this sprint).

**Built and tested now, called by nothing yet** — no future gameplay-write function exists in this codebase to call it. This is the same "deliver the mechanism ahead of its first real caller" pattern Sprint 3.9's `bootstrapGameSession()` and Sprint 4.0's `applyRemoteBid()` each already established successfully.

### `startTurnSync(matchId)` — Task 1

The turn-sync analog of Sprint 4.0's `startBidSync()` — subscribes through the SAME `MatchService.subscribeToMatch()` (no second listener: Firestore/`MatchService` ref-counts by `matchId`, not by which adapter-level function subscribed — a page calling both `startBidSync()` AND `startTurnSync()` for the same match still gets exactly one underlying listener) and pipes every delivery through `applyRemoteTurn()` instead of `applyRemoteBid()`.

### Task 4 (Duplicate Protection) and Task 5 (Adapter Isolation), restated for this sprint

Task 4 is the same guarantee `applyRemoteBid()` already provides, applied to turn state: the version gate rejects a byte-identical redelivery, and the content-level check rejects a genuinely newer version that changes nothing observable — together, "receiving identical turn snapshots twice must not re-render, re-run engine logic, or advance turn" (verified directly in `tests/turn-sync.test.cjs`'s "duplicate snapshot" and "listener duplicate event" sections, not merely asserted).

Task 5: `match-adapter.js` remains the ONLY file that calls `GameSession.setTurn()` on behalf of a remote update — confirmed by this sprint's own forbidden-scope sweep (`design-ui/match-service.js` still has zero reference to `GameSession`/`setTurn`/any engine file).

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

**Sprint 4.1:**
- `design-ui/match-adapter.js` — additive: `applyRemoteTurn()`, `isLocalSeatsTurn()`, `assertLocalTurn()`, `startTurnSync()`, and a test-only accessor (`getLastAppliedTurnVersion`); `resetSyncState()` extended to also clear the new turn registry. Every Sprint 3.9/4.0 function unchanged.
- `tests/match-adapter.test.cjs` — additive: unit-level gating tests for `applyRemoteTurn()`/`isLocalSeatsTurn()`/`assertLocalTurn()` against the REAL `GameSession` (no fake needed — this function never touches `BiddingEngine`).
- `tests/turn-sync.test.cjs` — new: full end-to-end tests against the REAL `design-ui/match-service.js`/`design-ui/engine/session.js`.
- Nothing else. `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `Dealer`, `Deck`, `Cards`, `design-ui/match-service.js`, `firestore.rules`, and every UI screen remain byte-for-byte unchanged.

## Sprint 4.2.1 (Pre-Write Card Authority & Desync Safety)

**A hotfix, not a feature sprint.** A direct review of Sprint 4.2's shipped code found two Critical correctness defects, both closed this sprint — see `docs/reviews/CardAuthorityHotfix_4.2.1.md` for the full account. This section records the design/architecture consequences.

### The one, minimal, additive `table-engine.js` change

`table-engine.js` already contained `isLegal(id, card)`/`legalCards(id)` — pure, read-only internal functions `emit()` itself calls before it mutates anything — but never exposed them. Added `canPlayCard(playerId, card)`: a NEW function, composing ONLY the pre-existing conditions `emit()` already checks (`state.phase`, `state.turn`, `isLegal()`) — zero new rules, zero changes to `emit()`/`isLegal()`/`legalCards()` themselves — exported via one new line in the existing `window.TableEngine = {...}` object. This mirrors Sprint 3.6's own "minimum wiring export" precedent exactly (`resolveTrick`/`getState` were added the same way, for the same reason) and is why this is correctly characterized as additive, not a rewrite of `table-engine.js`.

### `MatchService.submitCard()` — turn authority + pre-write engine validation, BOTH now before any write

Two gates now run, in order, entirely BEFORE `runTransaction()` is ever invoked:
1. **Turn authority** (Task 1) — resolves the seat via `MatchAdapter.uidToSeat()`, then calls `MatchAdapter.assertLocalTurn()` — Sprint 4.1's EXISTING gate, called verbatim. Re-verified again inside the transaction against a freshly-read document (defense in depth against a race between the two reads).
2. **Pre-write engine validation** (Task 2) — calls `TableEngine.canPlayCard(seatId, card)`. Never mutates, never calls `emit()`, never duplicates `isLegal()`'s own rule. If `TableEngine` isn't reachable at all, `submitCard()` refuses to write blind (`ENGINE_UNAVAILABLE`) rather than skipping validation silently.

**Architectural consequence, discovered while writing this sprint's own tests, documented rather than hidden**: a UI pattern of "call `TableEngine.emit()` directly for instant local feedback, THEN call `submitCard()` to persist" is now INCORRECT — by the time `submitCard()` validates, the engine's own `state.turn` has already moved past that seat (the direct `emit()` call advanced it), so `canPlayCard()` correctly reports `NOT_THIS_SEATS_TURN` for the very play that was just optimistically applied. The corrected architecture: `submitCard()` validates-then-persists first, never mutating locally on its own; the actual mutation happens exactly once, uniformly, through this SAME client's own `applyRemoteCard()` echo — never via a separate, earlier, direct local call racing the pre-write gate.

### `MatchAdapter.applyRemoteCard()` — desync, not silent skip, on `ENGINE_REJECTED`

Sprint 4.2's original version pushed a rejection into its `results` array, kept looping, and unconditionally advanced BOTH registries past the ENTIRE delivered log at the end — meaning an engine-rejected entry stayed in `cardLog` forever while this adapter's own bookkeeping claimed the snapshot was fully synchronized. Fixed: on `ENGINE_REJECTED`, the function STOPS immediately, never looks at any later entry, advances `lastAppliedCardCountByMatch` only UP TO (never past) the rejected index, and does NOT advance `lastAppliedCardVersionByMatch` at all — so a future delivery correctly re-attempts from the SAME stuck index rather than treating that version as fully handled. Returns a structured `{desync: true, reason: "ENGINE_REJECTED", matchId, index, seatId, engineReason, appliedCount, results}` — never retries on its own.

### Task 4 — Card Log Integrity: assessed, demonstrated, documented, not fixed

CEL (Firestore Rules, `rules_version '2'`) has no primitive for index-by-index list comparison without a `range()`/slice() construct it lacks; the closest tool (`.all(x, x in newLog)`) proves multiset membership, not position — insufficient even to catch a reordering. **Determined this cannot be safely done with currently-supported CEL** — no unsupported construct was invented. `tests/rules-simulation.test.js` gained two SIMULATED checks proving, against this project's own 1:1 rules translation, that both rewriting and reordering earlier `cardLog` entries currently pass `isValidCardSubmission()` unchanged. `cardLog` is now explicitly marked client-authoritative, MVP-only — see `docs/architecture/SecurityArchitecture.md`'s "Card write authority" section for the full risk statement and the two documented (not built) future directions.

### Testing summary

`tests/match-adapter.test.cjs` gained 9 new checks (109 total) proving the desync-not-silent-skip semantics precisely (stuck index, no later-entry processing, version not advanced, durable redelivery behavior, recovery once resolved) plus a structural "no gameplay rules duplicated outside TableEngine" check. `tests/submit-card.test.cjs` was substantially rewritten (49 checks, up from 32) to cover both new gates against a controllable fake `TableEngine`. `tests/card-sync.test.cjs` (41 checks) was corrected to model the new, valid architecture (no direct local `emit()` racing the pre-write gate) and to keep `matches/{matchId}.turn` synchronized with the real engine's own turn for every scenario. `tests/rules-simulation.test.js` gained 2 new SIMULATED checks demonstrating the Task 4 finding. **870 automated tests total, all passing.**

## Sprint 4.2.2 (Atomic Card Turn Progression & Card-Log Desync Hardening)

**A hotfix, not a feature sprint.** A direct review of Sprint 4.2.1's shipped code found three remaining correctness defects, all closed this sprint — see `docs/reviews/CardTurnProgressionHotfix_4.2.2.md` for the full implementation report.

### The one, minimal, additive `table-engine.js` change

`table-engine.js` already had `canPlayCard()` (Sprint 4.2.1) and the exact `state.plays.length`/`nextCCW()` arithmetic `emit()` uses internally to decide the next seat/phase — but never exposed a way to ask "what happens next" WITHOUT mutating. Added `previewPlay(playerId, card)`: composes ONLY `canPlayCard()`'s existing legality answer plus that same pre-existing arithmetic — zero new rules, never calls `emit()`, never mutates `state`. Returns `{legal:false, reason}` or `{legal:true, nextTurnSeat, nextPhase}` (`nextTurnSeat: null, nextPhase: "RESOLVING"` on the 4th card). Exported via one new line in `window.TableEngine = {...}` — the same "minimum wiring export" precedent as `resolveTrick`/`getState` (Sprint 3.6) and `canPlayCard` (Sprint 4.2.1).

### `MatchService.submitCard()` — atomic turn write + stricter revalidation

Two remaining gaps closed:
1. **Atomic next-turn persistence** — `submitCard()` now calls `TableEngine.previewPlay()` before the transaction, resolves `preview.nextTurnSeat` to a real UID via `MatchAdapter.seatToUid()` (or leaves `turn: null` when `preview.nextPhase === "RESOLVING"`), and writes `cardLog`, `lastCardSeat`, `turn`, `cardPhase`, `version+1`, `updatedAt` — ALL in the same `tx.update()` call. This is the fix that makes p1→p2→p3→p4 sequential submission work in production, with no test-only turn mutation anywhere in the path.
2. **STALE_GAME_STATE revalidation** — an `expectedVersion` fingerprint is captured OUTSIDE the transaction, at the same moment the LOCAL engine preview was computed, and re-checked on EVERY invocation of the transaction callback (including any automatic Firestore SDK retry) before any other check runs. A version mismatch throws `STALE_GAME_STATE` and writes nothing — deliberately NOT an automatic retry with a freshly recomputed preview, per the brief's explicit instruction. This is a stricter departure from `submitBid()`'s own optimistic-retry pattern, because `previewPlay()`'s answer depends on LOCAL browser engine state Firestore's own retry mechanism cannot itself re-validate.

**Architectural consequence, not new but now closed rather than merely documented**: Sprint 4.2.1 correctly identified that "call `TableEngine.emit()` directly, then `submitCard()`" is invalid — the corrected architecture (validate-then-persist, mutate only via `applyRemoteCard()`'s own echo) already required `submitCard()` to know the intended next state; this sprint is what actually PERSISTS that known next state atomically, rather than leaving Firestore's own `turn` field to fall behind.

### `MatchAdapter.applyRemoteCard()` — two more desync paths, same pattern as `ENGINE_REJECTED`

- **`MALFORMED_ENTRY`**: previously skipped silently and kept processing (advancing the count/version registries past it). Now stops immediately, advances `lastAppliedCardCountByMatch` only up to (never past) the malformed index, never advances `lastAppliedCardVersionByMatch`, returns `{applied:false, desync:true, reason:"MALFORMED_ENTRY", matchId, index}` — identical shape/semantics to `ENGINE_REJECTED`'s existing (Sprint 4.2.1) contract.
- **`LOCAL_ECHO_MISMATCH`**: the local-echo check now finds the actual locally-applied play for that seat and compares `suit`/`rank.v` exactly against the remote entry, instead of just checking "does this seat already have any play." A genuine echo (same card) still resolves as the existing, benign `ALREADY_APPLIED_LOCALLY` skip. A DIFFERENT card from the same seat now produces a structured desync: stops immediately, does not process later entries, does not advance the version registry, returns `{applied:false, desync:true, reason:"LOCAL_ECHO_MISMATCH", matchId, index, seatId, localCard, remoteCard}`.

### Task 6 — `firestore.rules`, extended in place

`isValidCardSubmission()` (an UPDATE rule — evolved in place, per this project's convention, unlike CREATE rules which get versioned suffixes) now additionally verifies: `oldData.turn == request.auth.uid` (caller owned the previous active turn); the new `turn` is either `null` or a UID present among `oldData.seats`' own values, expressed via `oldData.seats.keys().exists(s, oldData.seats[s] == newData.turn)` since CEL has no `.values()` method (an already-established workaround pattern in this project, not a new gap); `newData.cardPhase in ['PLAY', 'RESOLVING']`. `isValidNewMatch()` gained a new versioned `isValidNewMatchV5` requiring `cardPhase: null` at creation.

**Honest limitation, restated**: this proves the new turn is STRUCTURALLY valid (a real seat UID or null), never that it is the CORRECT next seat per follow-suit/turn-order — that remains entirely client-authoritative in this Spark MVP, unchanged in kind from every prior sprint's own gameplay-legality framing.

### Testing summary

`tests/submit-card.test.cjs` (substantially rewritten against a `previewPlay()`-shaped fake `TableEngine` with an internal seat-rotation model) covers the full p1→p2→p3→p4→resolving sequence with zero manual turn mutation, wrong-turn zero-writes, and `STALE_GAME_STATE` via an intercepted first transaction invocation. `tests/match-adapter.test.cjs` gained new checks for `MALFORMED_ENTRY`'s stop-and-desync semantics and `LOCAL_ECHO_MISMATCH`'s content-comparison semantics, both mirroring `ENGINE_REJECTED`'s established contract. `tests/card-sync.test.cjs` was rewritten to remove the forbidden test-only `syncTurnFieldToRealEngine()` helper entirely — its ONE remaining direct turn assignment (`seedMockMatch()`) is a one-time setup seed, never a between-submissions mutation. `tests/rules-simulation.test.js` gained new SECURITY tests for the turn/cardPhase checks plus 3 new `isValidNewMatchV5` create-time tests. **889 automated tests total, all passing.**

## Sprint 4.2.3 (Firestore Rules Compile-Safe Card Turn Hotfix)

A small hotfix correcting `isValidCardSubmission()`'s turn-validation expression (`.exists()`, not part of Firestore Rules' officially documented List method surface) to explicit `Map.get(key, default)` lookups — `firestore.rules` and `tests/rules-simulation.test.js` only; no file this document otherwise covers was touched. See `docs/reviews/CardCompileSafeTurnHotfix_4.2.3.md` and `docs/architecture/SecurityArchitecture.md`'s "Compile-safe Rules syntax" section for the full account.

## Sprint 4.3 (Trick Resolution Synchronization)

**A STRICT implementation sprint: online trick-winner synchronization ONLY.** Not scoring, not next round, not match end. `table-engine.js` remains the single, unmodified authority for trick winner, follow suit, trump, played cards, trick completion, and next leader — see `docs/reviews/TrickResolutionSync_4.3.md` for the full implementation report.

### Task 1 (Architecture Verification) — no engine change needed

`table-engine.js` already exposed everything required: `getState().phase === "RESOLVING"` (the existing signal `emit()` sets on the 4th card) and `resolveTrick()` (exported since Sprint 3.6, the SAME function the real offline turn loop already calls internally) together fully determine trick completion, winner, and next leader. No new `table-engine.js` export was added — byte-for-byte unchanged.

### `MatchAdapter.applyRemoteTrick(matchId, matchDoc)` — Task 2

Resolves AT MOST ONE completed trick per call. Its ONLY direct engine call is `TableEngine.resolveTrick()`; the returned `winnerId` is read back from `TableEngine.getState().lastTrick.winnerId` AFTERWARD — never computed, compared, or duplicated by this function. Guarded by the engine's own `phase !== "RESOLVING"` precondition (an ordinary no-op — the SAME outcome whether the trick simply isn't done yet OR an upstream `applyRemoteCard()` desync correctly refused to complete it; this function never re-derives that detection) plus a dedicated `lastResolvedTrickNoByMatch` idempotency registry, deliberately NOT a `version`-number gate (a single delivery's `cardLog` can legitimately span multiple already-completed tricks — see `startTrickSync()` below — so the SAME `matchDoc.version` may legitimately need more than one resolution pass).

**Necessary completion beyond the original Task 2 wording, found during end-to-end testing and documented rather than silently added**: also calls `GameSession.setTurn()` — an EXISTING, unmodified public setter this file already established as safe to call (Sprint 3.9, Sprint 4.1) — with the engine's own post-resolution `state.turn`, ONLY when a genuine next trick exists to lead. Without this, `assertLocalTurn()`'s pre-existing fallback (Sprint 4.1, unmodified) would keep reporting a stale turn-holder after every resolution (since nothing writes the real next leader back into `matches/{matchId}.turn`, which `submitCard()` sets to `null` at the resolving boundary — Sprint 4.2.2 — and no Firestore field exists for the resolved leader), blocking all further play after trick 1. This is the SAME "mirror the engine's decision into GameSession, never compute one independently" pattern `applyRemoteTurn()` already established, applied to trick resolution instead of a turn-field change.

### `MatchAdapter.startTrickSync(matchId)` — Task 3

The trick-sync analog of `startBidSync()`/`startTurnSync()`/`startCardSync()` — reuses `MatchService.subscribeToMatch()` verbatim, no second listener. **One documented architectural necessity**: its callback LOOPS, alternating the existing `applyRemoteCard()` (safe to call again — fully idempotent) and the new `applyRemoteTrick()`, up to 13 times per delivery, because `cardLog` is append-only and never cleared across trick boundaries (Sprint 4.2's own design) — a single delivery (a late subscriber, or a reconnect) can legitimately carry multiple already-completed-but-unresolved tricks, and `table-engine.js`'s own `emit()` correctly refuses a new card while `phase === "RESOLVING"` (existing, unmodified behavior), so catching up on N backlogged tricks requires N alternating replay/resolve steps. This is orchestration, not a new algorithm.

### Task 4/5 — MatchService and firestore.rules: NOT MODIFIED

The trick winner, next leader, and updated `tricksWon` are ALL deterministically re-derivable, by every client, from data already being synchronized (`cardLog` + the immutable trump/seat rules `table-engine.js` already enforces identically everywhere) — "synchronization by determinism," not "synchronization by a broadcast write." No new Firestore field, no new write path, so neither file needed a change and neither was touched — re-verified directly by this sprint's own tests reading both files' real source.

### Testing summary

`tests/match-adapter.test.cjs` gained 32 new checks (147 total) unit-testing `applyRemoteTrick()`'s own gating logic against a controllable fake `TableEngine`. `tests/trick-sync.test.cjs` (new, 45 checks) covers all 11 required end-to-end scenarios against the REAL `table-engine.js`/`bidding-engine.js`/`match-service.js`/`match-adapter.js` — including an INDEPENDENT, test-side re-computation of the real trump/follow-suit rule to cross-check "winner matches TableEngine" beyond a tautological self-comparison, and a genuine (not forged) one-trick backlog for the "late subscriber" scenario. **954 automated tests total, all passing.**
