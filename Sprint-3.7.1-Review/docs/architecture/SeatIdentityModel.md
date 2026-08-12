# Seat Identity Model — Sprint 3.7.1 (Documentation Only)

**Status: design only. Nothing in this document is implemented.** No code in this repository reads, writes, or validates a `seats` field today. This document exists because Sprint 3.7's synchronization layer exposed a real, previously-undocumented gap (see `docs/architecture/MatchSynchronization.md`'s "Known Limitation" note): the engine identifies players by canonical **seat id** (`p1`, `p2`, `p3`, `p4`), while Firestore identifies players by real **Firebase Auth uid**. Before any gameplay write (bidding, card play, turn authority) can be implemented, something has to own the mapping between the two. This document proposes and records that design so the next sprint that implements gameplay writes has a single place to start from — it does **not** implement any of it, per this sprint's explicit "documentation only" instruction.

## Why this gap exists

- The gameplay engine (`design-ui/engine/session.js`'s `GameSession`, `dealer.js`, `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`) was built first, offline, single-browser-tab, with a hardcoded `CANONICAL_ORDER = ["p1","p2","p3","p4"]` and no concept of a real user identity at all — see `docs/architecture/GameEngine.md`.
- `matches/{matchId}` (Sprint 3.4 onward) identifies players by real Firebase Auth `uid`, copied verbatim from `rooms/{roomId}.players` — see `docs/architecture/FirestoreSchema.md`.
- Nothing has ever needed to translate between the two, because no sprint through 3.7.1 has connected real multiplayer identity to the engine's gameplay logic. `GameSession.getRemoteMatch()` (Sprint 3.7) returns the raw, uid-keyed Firestore document with no merge into the engine's seat-keyed fields — a deliberate, documented scope boundary, not an oversight.

## Proposed mapping

A new field on the match document, `seats`:

```
matches/{matchId}.seats = {
  p1: "uidA",
  p2: "uidB",
  p3: "uidC",
  p4: "uidD"
}
```

A plain object, not an array — direct `seats.p1`/`seats["p1"]` lookup in both directions is the point: the engine already has a seat id and needs a uid (`seats[seatId]`); a sync/rules layer already has a uid (from `request.auth.uid`) and needs to know which seat it owns (the one reverse lookup, `Object.keys(seats).find(s => seats[s] === uid)`, done once per client per match — cheap, no index needed for 4 entries).

### Owner

**`MatchService`** would own this field, exactly like every other match-document field — no new service, no new collection. It is written once, by whichever code builds the initial match document (today: `buildInitialMatchDoc()` inside `startMatch()`), and never by any other code path.

### Lifetime

**Immutable for the life of the match**, assigned exactly once at match creation and never reassigned — including on reconnect. A player who disconnects and rejoins the SAME match resumes the SAME seat; this document does not model "seat abandonment" or "seat reassignment" at all (that belongs with `MatchLifecycle.md`'s already-documented `ABANDONED` state design, which is itself still design-only). This is a deliberate simplification: solving reconnection-to-the-same-seat is a prerequisite for gameplay writes, but reassigning a seat to a DIFFERENT person mid-match is explicitly out of scope for this model and is not something any current design in this project calls for.

### Creation

Assigned **positionally** from `rooms/{roomId}.players` at the exact moment `buildInitialMatchDoc()` runs, inside `startMatch()`'s existing single transaction (no new transaction, no new write) — `players[0] → p1`, `players[1] → p2`, `players[2] → p3`, `players[3] → p4`. `rooms/{roomId}.players` is already order-preserving (join order — see `docs/architecture/RoomLifecycle.md`), so this requires no new ordering concept, only a new field derived from data `startMatch()` already has in hand. `dealer`/`turn` (already uid-valued fields on the match document) would be looked up via the reverse mapping (`Object.keys(seats).find(...)`) wherever the engine needs a seat id instead of a uid, rather than duplicating the same uid→seat logic in multiple places.

### Synchronization

**No new sync mechanism.** `seats` is just one more field on the same `matches/{matchId}` document `MatchService.subscribeToMatch()` (Sprint 3.7/3.7.1) already delivers in real time to every subscriber — it needs no separate listener, no separate collection, and is covered by the exact same ref-counting, ordering-guard-if-`version`-is-ever-added, duplicate-content-guard, and retry-policy work already shipped. The one thing this DOES require of a future gameplay-write sprint: `GameSession` (or whatever layer bridges `MatchService` and the engine) needs a translation step — "given `getRemoteMatch().turn` (a uid) and `getRemoteMatch().seats` (the map), which seat id does the engine's `bidding-engine.js`/`table-engine.js` need to see as `state.turn`?" — this translation function does not exist yet and is exactly the piece Sprint 3.7.1 is flagging as a prerequisite, not building.

### Validation

Once any write path to `seats` exists (none does today — `firestore.rules`' `matches/{matchId}` block still correctly has `allow update: if false`, unchanged by this sprint), the create-time rule should require, mirroring the existing `isValidNewMatch()` pattern in `firestore.rules`:

- `seats` is present and `seats.keys().hasOnly(['p1','p2','p3','p4'])` — exactly four entries, no more, no fewer, exact names.
- Every value is a `string`.
- The **set** of values (`seats.values()` conceptually — CEL doesn't have this directly, so in practice: `[seats.p1, seats.p2, seats.p3, seats.p4]` treated as a list) exactly equals `data.players` treated as a set (no duplicate uid occupying two seats, no uid appearing that isn't actually in `data.players`, no player in `data.players` missing a seat).
- `seats` must be set in the SAME create write as the match document itself (it already would be, since `buildInitialMatchDoc()` is one object) — never addable or changeable afterward, which the existing `allow update: if false` already guarantees for free, with no extra rule needed, for as long as that stays true.

### Security implications

- **This does not create a new deanonymization risk beyond what already exists.** `matches/{matchId}.players` already lists every seated player's real uid, readable by every OTHER seated player (see `FirestoreSchema.md`'s "hands problem" note) — `seats` makes an already-visible fact (who is in this match) explicit and positionally stable rather than leaving it to be inferred from array order. It does not, by itself, expose anything that `players[]` didn't already expose.
- **It DOES matter once turn authority is implemented.** The moment any write path lets a client claim "I acted as seat `p2`," the rule validating that write MUST independently verify `seats.p2 == request.auth.uid` — never trust a client-supplied seat id alone. This mirrors the exact "neither layer trusts the other alone" principle already established in `docs/implementation/MatchInitialization.md` for room↔match binding, and is the single most important consequence of this model: **seat identity must be checked against the immutable `seats` map on every future gameplay write, not against whatever seat id the client's request body claims.**
- **Turn authority itself remains a client-authoritative-with-rules limitation on Spark**, same as every other gameplay concern this project has already honestly flagged (see `SecurityArchitecture.md`). `seats` lets a future rule verify WHO is allowed to claim they acted for a given seat; it does not, by itself, let rules verify that the ACTION itself (a legal bid, a legal card, whose turn it actually is right now) was computed correctly — that remains the same "soft-launch acceptable, ranked-launch blocking" gap already on record for dealing (`MatchLifecycle.md`) and would need the same Cloud Functions migration eventually to close fully.
- **No seat authority is implemented by this document.** "Which seat may act right now" (turn enforcement) is a gameplay-write concern, explicitly out of this sprint's scope, and is not designed here beyond noting that it depends on this mapping existing first.

## What this sprint does NOT do

- Does not add a `seats` field to any real match document (`buildInitialMatchDoc()` is unchanged).
- Does not add any rule referencing `seats` to `firestore.rules` (unchanged — `matches/{matchId}` update is still denied outright).
- Does not add any translation function to `GameSession`, `MatchService`, or any engine file.
- Does not implement turn authority, seat authority, or any gameplay write.

This is a documentation deliverable only, per the brief's explicit "Do NOT implement seat authority. Documentation only."
