# Changelog — Sprint 3.8.1: Bidding Validation & Rules Hardening

Small, isolated hardening pass. Not a feature sprint. No UI redesign, no Dealer/Deck/Cards/GameSession/Scoring/Table Engine/Bidding Engine changes, no card play, no turn authority, no trick resolution, no score sync, no matchmaking, no voice, no chat.

## Fixed
- **`design-ui/match-service.js` — Task 1.** `submitBid()` previously stored `bid` as a completely unvalidated opaque payload. Now rejects, before any Firestore access, via new `isValidGenericBidValue()`: `null`, `undefined`, `NaN`, `Infinity`, `-Infinity`, negative values, non-integers, values above `13`, and any non-numeric type (string/object/array/boolean — never coerced). Rejections carry `err.reason === "INVALID_BID_VALUE"` with a clear `.message`.
- **`firestore.rules` — Task 2.** `isValidBidSubmission()` gained an independent, server-side mirror of the identical check: `newData.bids[seat] is int && >= 0 && <= 13`. Neither layer trusts the other alone.

## Added (documentation)
- **`docs/architecture/BidValidation.md`** — new. Draws the explicit, permanent line between Generic Validation (implemented this sprint: "is this a well-formed trick-count-shaped number") and Gameplay Validation (still entirely `bidding-engine.js`'s job — turn order, auction legality, Dash/With/Sa'ayda shapes — untouched, unconsulted, unconnected).

## Not changed
- `bidding-engine.js`, `table-engine.js`, `scoring-engine.js`, `Dealer`, `Deck`, `Cards`, `GameSession` — untouched, not consulted anywhere in this sprint's new code.
- No gameplay legality was implemented — `13` still passes generic validation unconditionally regardless of whether it's actually a legal bid for the submitting seat right now.
- Seat ownership, no-double-submit, bidding-open, version-increment logic — all unchanged from Sprint 3.8.
- No card play, turn authority, trick resolution, score sync, matchmaking, voice, or chat work was started.

## Testing
- `tests/submit-bid.test.cjs` (+25 checks, 66 total, all **MOCKED**): `null`, `undefined`, `NaN`, `Infinity`, `-Infinity`, negative, `14`, string, object, non-integer (4.5) — each rejected with `INVALID_BID_VALUE`; a follow-up check confirms none of the ten invalid attempts reached Firestore at all; all fourteen valid values `0`–`13` individually confirmed accepted.
- `tests/rules-simulation.test.js` (+24 checks, 109 total, all **SIMULATED**): the identical value set exercised against the 1:1 CEL translation of `isValidBidSubmission()`.
- Full regression re-run, zero regression: `deck` (39), `match-service` (65), `match-sync` (58), `room-service` (31), `match-flow-integration` (156), `match-flow-normal-dash-scoring-fix` (16), `match-flow-scoring-scenarios` (31) — all MOCKED.
- **571 automated tests total, all passing.** Every test explicitly labeled MOCKED or SIMULATED — no test in this project, this sprint or any prior one, has run against the Firebase Emulator or real Firestore.

## Documentation
- `docs/architecture/BidValidation.md` — new, full Generic-vs-Gameplay writeup.
- `docs/architecture/MatchSynchronization.md` — new Sprint 3.8.1 section.
- `design-ui/match-service.js`, `firestore.rules` — header/inline comments updated to reference the new document and explain the sprint's exact scope.
- This QA package.
