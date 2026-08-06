# Bid Validation — Generic vs. Gameplay (Sprint 3.8.1)

**Sprint type:** small, isolated hardening. Sprint 3.8 gave `submitBid()` WHO/WHEN authority (seat ownership, no double-submit, bidding-open) but never looked at the bid VALUE itself — a client could submit `-5`, `"four"`, `NaN`, or `999` and it would sail through, stored as an opaque payload. This document exists to draw an explicit, permanent line between the two kinds of validation this system has (and will have), so nobody later reads "bid validation" and assumes more happened than actually did.

## The line, stated once, precisely

**Generic validation** answers: *"Is this a well-formed value that could possibly represent a trick count, in ANY round of this game, for ANY seat, at ANY point?"* It knows nothing about auctions, turns, Dash/With/Sa'ayda, or which round is being played. It is pure data-shape hygiene — the same kind of check that would apply if this were a generic form field, not a card game. Implemented in exactly two places, kept in independent, deliberate sync:

- `design-ui/match-service.js`'s `isValidGenericBidValue(bid)` — client-side, fast-fail, before any Firestore access.
- `firestore.rules`' `isValidBidSubmission()`'s `newData.bids[seat] is int && >= 0 && <= 13` clause — server-side, the actual enforcement boundary.

**The generic rule, in full:** `bid` must be a finite integer between `0` and `13` inclusive. That's it. Nothing else is checked at this layer.

**Gameplay validation** answers: *"Is THIS specific value a LEGAL bid for THIS seat, right now, in THIS auction, under the real Estimation rules?"* This depends on things generic validation cannot and does not know: whose turn it is, what the current auction top bid is, whether this seat already passed, whether a Dash Call is in play, whether a fast round (14-18) forces a specific trump, the Auction Alignment / Call Cap / Super Call rules, and more — all of it already implemented, entirely offline, entirely disconnected from any of this, in `design-ui/engine/bidding-engine.js`. **This sprint does not implement, consult, call, import, or otherwise touch `bidding-engine.js` in any way.** Gameplay validation for real multiplayer bids remains that engine's job, to be wired up in a future sprint — not this one, and not by accident here.

## Why this distinction is drawn this way, not some other way

A generic layer that also tried to be "a little bit gameplay-aware" (e.g., "reject a bid higher than 13 minus however many tricks are already accounted for") would blur exactly the line this document exists to keep sharp, and would mean re-deriving pieces of `bidding-engine.js`'s logic in a second place — the opposite of this project's established "don't duplicate rules logic across files" discipline (see `docs/architecture/GameEngine.md`'s Cards/Deck/Dealer layering rationale, and `docs/architecture/BidValidation.md`'s own sibling documents on scope discipline). Keeping generic validation to "is this shape even sane" and nothing more is what makes it safe to implement HERE, permanently, with no risk of drifting out of sync with whatever `bidding-engine.js` does or later changes — a structural fact about trick counts (there are 13 cards in a round; a bid can't be negative) is never going to change even if the auction rules themselves ever do.

## What this means concretely, for `bid = 13`

`13` passes generic validation unconditionally — it is a structurally possible trick count. Whether `13` is actually a LEGAL bid for the specific seat submitting it, at the specific moment they submit it (an obviously extreme bid that would almost always fail an auction ceiling check, a Call Cap, or simply not be this seat's turn) is entirely undetermined by this layer, and will remain so until a future sprint wires real gameplay validation in. This is a deliberate, accepted gap for THIS sprint, not an oversight — restated in `docs/architecture/MatchSynchronization.md` and `docs/architecture/SecurityArchitecture.md`'s existing "soft approximation" framing, which already flagged this exact gap before this sprint existed.

## Where this sits in the existing "soft vs. strong" framing

`docs/architecture/SecurityArchitecture.md`'s "Where rules-only enforcement is a real guarantee vs. a soft approximation" section already listed "`matches/{matchId}` turn-order/legality" as a soft, Cloud-Functions-eventually gap. Generic bid-range validation is a NEW entry in the STRONG category (an exact, structural, permanently-sufficient rules-only check — "is this an integer 0-13" needs no engine, no Cloud Function, ever). Gameplay legality remains exactly where it already was: soft, approximate, explicitly flagged as a Ranked-Match blocker until a real server-authoritative migration happens. This sprint moves ONE narrow slice from "not checked at all" to "strong," and moves nothing from "soft" to "strong" — that would require the engine integration this sprint explicitly does not do.

## Implementation summary

| Layer | Function | Checks |
|---|---|---|
| Client (`match-service.js`) | `isValidGenericBidValue(bid)` | `typeof bid === "number" && Number.isFinite(bid) && Number.isInteger(bid) && bid >= 0 && bid <= 13` |
| Server (`firestore.rules`) | `isValidBidSubmission()`'s new clause | `newData.bids[seat] is int && newData.bids[seat] >= 0 && newData.bids[seat] <= 13` |
| Neither | Bid legality, turn order, auction rules | Not implemented anywhere. `bidding-engine.js` untouched, unconsulted. |

Both layers reject the identical set of values: `null`, `undefined`, `NaN`, `Infinity`, `-Infinity`, any negative number, any value above `13`, any non-integer, and any non-numeric type (string, object, array, boolean) — with zero coercion (`"4"` is rejected, never parsed into `4`). See `tests/submit-bid.test.cjs` (MOCKED) and `tests/rules-simulation.test.js` (SIMULATED) for the executable proof, covering every value Sprint 3.8.1's brief named by name, plus all fourteen valid values `0`–`13`.
