# Seat Identity Model — designed Sprint 3.7.1, implemented Sprint 3.8

> **Status update (Sprint 3.8 — Gameplay Synchronization: Bidding Authority): IMPLEMENTED.** Everything this document originally proposed as documentation-only design in Sprint 3.7.1 is now real code: `design-ui/match-service.js`'s `buildSeatMap()`/`buildInitialMatchDoc()` writes `seats` on every new match; `firestore.rules`' `isValidSeatMap()`/`isValidNewMatch()` validates it at creation; `isValidBidSubmission()` is the first (and, as of this sprint, only) write path that reads it as an authority. The sections below are updated in place to describe the ACTUAL implementation, not just the original proposal — where the shipped code differs from the original proposal in some detail, that's called out explicitly rather than silently rewritten as if the proposal always matched. See `docs/architecture/MatchSynchronization.md`'s Sprint 3.8 section and `docs/architecture/SecurityArchitecture.md`'s "Bidding write authority" section for the full account.

No code in this repository read, wrote, or validated a `seats` field before this sprint. This document exists because Sprint 3.7's synchronization layer exposed a real, previously-undocumented gap (see `docs/architecture/MatchSynchronization.md`'s "Known Limitation" note): the engine identifies players by canonical **seat id** (`p1`, `p2`, `p3`, `p4`), while Firestore identifies players by real **Firebase Auth uid**. Before any gameplay write (bidding, card play, turn authority) could be implemented, something had to own the mapping between the two. Sprint 3.7.1 proposed and recorded that design; Sprint 3.8 implements the part of it that bidding needs (the mapping itself, and its use as write authority) — turn authority and card-play seat usage remain undesigned-in-code beyond this document, per Sprint 3.8's own "only synchronize bidding" scope.

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

**`MatchService`** owns this field, exactly like every other match-document field — no new service, no new collection, confirmed as implemented: `design-ui/match-service.js`'s `buildInitialMatchDoc()` is the only code path that ever writes `seats`, via `buildSeatMap()`. No other file writes it. `firestore.rules`' `isValidNewMatch()`/`isValidSeatMap()` is the independent, server-side re-check that no other write path — including a raw client bypassing `MatchService` entirely — can introduce or alter it.

### Lifetime

**Immutable for the life of the match**, assigned exactly once at match creation and never reassigned — confirmed by `firestore.rules`' `isValidBidSubmission()` (Sprint 3.8's only real update rule), whose `affectedKeys().hasOnly([...])` allow-list does not include `seats` at all — any write attempting to change it, for any reason, is denied outright. A player who disconnects and rejoins the SAME match resumes the SAME seat (nothing re-derives or reassigns it); this document still does not model "seat abandonment" or "seat reassignment" at all (that remains `MatchLifecycle.md`'s already-documented, still-design-only `ABANDONED` state).

### Creation

Assigned **positionally** from `rooms/{roomId}.players` at the exact moment `buildInitialMatchDoc()` runs, inside `startMatch()`'s existing single transaction (no new transaction, no new write) — `players[0] → p1`, `players[1] → p2`, and so on, implemented exactly as proposed, via a small `buildSeatMap()` helper. One correction versus the original Sprint 3.7.1 proposal, made honestly rather than silently: this project's room system does not enforce a minimum of 4 players (`RoomService.MAX_PLAYERS` caps at 4, but nothing requires reaching it), and several of this project's own pre-existing tests exercise 2-player matches. Rather than fabricate a seat for a player who was never actually in the room — which would mean inventing an AI/placeholder identity, explicitly out of scope ("DO NOT implement AI") — `buildSeatMap()` maps only the seats that have a REAL player. A 2-player match gets exactly seats `p1`/`p2`; `p3`/`p4` simply do not exist in the map for that match. `dealer`/`turn` (uid-valued fields) are NOT translated through this mapping by any code yet — see "Synchronization" below, unchanged from the original proposal.

### Synchronization

**No new sync mechanism, confirmed.** `seats` is just one more field on the same `matches/{matchId}` document `MatchService.subscribeToMatch()` (Sprint 3.7/3.7.1, unmodified this sprint) already delivers in real time to every subscriber. The bidding-sync fields (`version`/`biddingOpen`/`bids`/`lastBidSeat`) this sprint ALSO added ride the exact same, unmodified pipe — this is the concrete proof the original proposal's claim held: no sync code changed to support any of Sprint 3.8's new fields. The translation step this document originally flagged as not-yet-existing ("given a uid, which seat id does the engine need to see") **still does not exist** — `GameSession.getRemoteMatch()` still returns the raw, uid-keyed document with no merge into the engine's seat-id-keyed fields. This remains the prerequisite for whichever future sprint wires `bidding-engine.js` itself to real multiplayer state; Sprint 3.8 only wires the Firestore-facing sync/authority layer, not the engine-facing consumption layer.

### Validation

**Implemented, with one deliberate, documented deviation from the original proposal's exact wording.** The original proposal suggested requiring `seats.keys().hasOnly(['p1','p2','p3','p4'])` with exactly four entries. Since real matches can have fewer than 4 players (see "Creation" above), the shipped `isValidSeatMap()` instead requires:

- Only real seat names (`seats.keys().hasOnly(['p1','p2','p3','p4'])` — unchanged from the proposal, just without the "exactly four" part).
- `seats.keys().size() == players.size()` — exactly one seat per real player, no more, no fewer.
- Every seat's value is actually one of `players[]` (`seats[s] in players`).
- No two different seats share the same uid.

Combined, these four conditions force `seats` to be a genuine bijection between some subset of `{p1,p2,p3,p4}` (sized to match `players.size()`) and `players` itself — equivalent in spirit to the original "set of values equals players, treated as a set" proposal, expressed via membership + size + uniqueness instead of a direct set-equality operator (CEL has no native set-equality on arbitrary lists; this is the practical equivalent). `seats` is set in the SAME create write as the match document itself (`buildInitialMatchDoc()` is one object, unchanged from the proposal) — and, confirmed rather than merely expected, `isValidBidSubmission()`'s `affectedKeys()` allow-list makes it genuinely unchangeable afterward.

### Security implications

- **Confirmed, not just proposed: this does not create a new deanonymization risk beyond what already existed.** `matches/{matchId}.players` already lists every seated player's real uid, readable by every OTHER seated player — `seats` makes an already-visible fact explicit and positionally stable rather than leaving it to be inferred from array order.
- **Confirmed for bidding, the ONE gameplay action implemented so far: seat claims ARE independently checked against this map, never trusted from a client alone.** `isValidBidSubmission()`'s single most important line, `oldData.seats[seat] == request.auth.uid`, is exactly the enforcement this document called for before any code existed. `design-ui/match-service.js`'s `submitBid()` performs the identical check client-side too (for a fast, clear error) — "neither layer trusts the other alone," and now this is demonstrated in working code, not just asserted in a design doc.
- **Turn authority remains undesigned beyond this mapping's existence.** This sprint implements seat OWNERSHIP verification for bidding; it does not implement "whose turn it is" for anything (explicitly out of scope — "DO NOT implement Turn Rotation after bidding"). The `turn` field on the match document still has no real meaning.
- **The bid VALUE itself is not validated against auction rules by this layer.** `isValidSeatMap`/`isValidBidSubmission` verify WHO may write WHERE; they say nothing about whether a submitted bid amount is itself a legal move — that remains `bidding-engine.js`'s job, untouched and unconnected to this document, still a client-authoritative-with-rules limitation exactly as `SecurityArchitecture.md` already honestly frames every other such gap.

## What Sprint 3.8 implemented (vs. what remains undesigned/unimplemented)

**Implemented this sprint:** `seats` written at creation; `seats` validated (bijection, not exact positional re-derivation — see "Validation" above) at creation; `seats` made structurally immutable via the bidding update rule's field allow-list; seat ownership checked, both client-side and server-side, as the authority for "may this uid submit this bid."

**Still not implemented, by explicit scope boundary, not oversight:** any translation from `seats`/uid-keyed remote state into the engine's seat-id-keyed local state (`GameSession`, `bidding-engine.js`); turn authority/turn rotation; card-play seat usage; bid-value legality validation. These remain exactly what this document always said they were: dependent on this mapping existing, and now it does — but nothing downstream consumes it yet beyond bidding's own write-authority check.
