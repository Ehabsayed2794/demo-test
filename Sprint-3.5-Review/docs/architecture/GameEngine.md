# Game Engine — Layering & Ownership

**New in Sprint 3.5 (Deck Implementation & Engine Integration).** This document exists because Sprint 3.4.5's audit (`docs/reviews/Engine_Dependency_Audit_3.4.5.md`) found no document describing how the card-dealing layer of `design-ui/engine/*` actually fits together — the referenced `GameSession.md`/`CardEngine.md` files that in-code comments point to do not exist in this repository. This document is scoped narrowly to the four layers below; it is **not** a full engine architecture doc (bidding/table/scoring rules remain undocumented here on purpose — out of scope for this sprint, see `docs/reviews/Engine_Dependency_Audit_3.4.5.md`'s own scope note).

## The chain

```
Cards  →  Deck  →  Dealer  →  GameSession
```

Each arrow is a "depends on and calls into," never the reverse — this is a strict, one-directional layering. No layer reaches back up to a layer above it.

### `cards.js` — owns what a card **is**

- **File:** `design-ui/engine/cards.js`.
- **Owns:** the suit table (`Cards.SUITS` — 5 entries, including `SANS`, a trump *mode*, not a physical suit), the playable-suit subset for a physical deck (`Cards.DECK_SUITS` — the 4 real suits), the rank table (`Cards.RANKS` — 13 ranks, `2`–`A`), the card **shape** itself (`Cards.createCard(suitKey, rank, owner)` — id/suit/rank/displayName/value/owner/played), and the standard display sort (`Cards.compareForSort`).
- **Does not own:** shuffling, dealing, deck state, or any notion of "how many cards are left." `cards.js`'s own header comment says so explicitly: *"No shuffling/dealing here — see deck.js / dealer.js."*
- **Depends on:** nothing. Self-contained.
- **Never modified** by Sprint 3.5 — every other layer reuses it as-is, exactly as required ("Cards remain owned by cards.js").

### `deck.js` — owns deck **state and behavior** (new in Sprint 3.5)

- **File:** `design-ui/engine/deck.js`.
- **Owns:** a single deck instance's remaining cards and their order. Nothing else.
- **Public surface (deliberately small):**
  - `new Deck()` — builds one fresh, full, unshuffled 52-card set by calling `Cards.createCard()` once per `(suit, rank)` pair over `Cards.DECK_SUITS × Cards.RANKS` (4 × 13).
  - `deck.shuffle(rng?)` — Fisher–Yates, in place, on whatever cards currently remain. `rng` is an optional injectable random source (a function returning a float in `[0, 1)`, matching `Math.random()`'s contract) — defaults to `Math.random` for real gameplay; tests can inject a fixed sequence for a fully deterministic, hand-verifiable result. This was the one open design gap the Sprint 3.4.5 audit flagged ("no deterministic/injectable shuffle exists") — resolved here, not left for a future sprint.
  - `deck.draw()` — removes and returns exactly one card. Throws a clear error if the deck is already empty (fail loud — drawing past a real deck's end is a caller bug, not a state to paper over).
  - `deck.remaining()` — how many cards are left.
  - `deck.reset()` — discards whatever remains and rebuilds a fresh, full 52-card set (brand-new card objects, never reused references).
- **Does not own:** what a card is (delegates every card's creation to `Cards.createCard()` — no suit/rank/card-shape duplication anywhere in this file), seating, dealing order, or any gameplay rule.
- **Depends on:** `Cards` only (must load after `cards.js`).
- **Instantiated per deal, not a shared singleton.** `new Deck()` is created fresh each time `Dealer.dealHands()` runs — there is no module-level shared deck state to accidentally leave stale between deals. This is a deliberate design choice over the shape the pre-Sprint-3.5 (never-working) calls implied — a `Deck.reset()`/`Deck.shuffle()` singleton object — precisely because a fresh instance has no shared state that could be forgotten to reset correctly.

### `dealer.js` — owns dealing **order and seats**

- **File:** `design-ui/engine/dealer.js`.
- **Owns:** the seat dealing order (`Dealer.DEAL_ORDER` — Player, AI Left, AI Top, AI Right), the seat-role labels (`Dealer.SEAT_ROLES`), and the one public operation, `Dealer.dealHands(seatOrder?)`: instantiate a `Deck`, shuffle it, draw 13 rounds × 4 seats (one card at a time, mirroring a real deal — not four pre-sliced 13-card blocks), stamp each card's `owner`, sort each hand for display via `Cards.compareForSort`, and return `{ p1: [...13 cards], p2: [...], p3: [...], p4: [...] }`.
- **Does not own:** card creation (delegates to `Deck`, which delegates to `Cards`) or shuffling (delegates to `Deck.prototype.shuffle`). Sprint 3.5's integration change was the minimum required to consume `Deck`'s real API — `dealHands()`'s body changed from calling a nonexistent `Deck.reset()`/`Deck.shuffle()`/`Deck.deal(1)` singleton to `new Deck()` + `deck.shuffle()` + `deck.draw()`. Nothing else in this file changed — same seat order, same seat roles, same return shape, same sort.
- **Depends on:** `Cards` (for `compareForSort`) and `Deck` (must load after both).

### `session.js` (`GameSession`) — owns the **persisted match state**

- **File:** `design-ui/engine/session.js` (byte-identical copy at `design-ui/lobby/session.js`, per this project's established per-screen-folder script-copy pattern).
- **Owns:** the single funnel every screen is meant to call instead of deciding for itself whether to reshuffle — `GameSession.ensureHandsDealt(opts?)`, which calls `GameSession.dealNewHands()` (which in turn calls `Dealer.dealHands()` and stores the result as `session.hands`) only if the current round has no valid deal yet, otherwise returns the already-stored hands. Also owns the deal metadata (`session.dealState` — round number, `completed`, `dealtAt`) used to decide "has this round already been dealt," independent of hand size (a player can legitimately reach zero cards by playing them all, and that must still count as dealt).
- **Does not own:** anything about what a card is, how a deck shuffles, or dealing order — it only calls `Dealer.dealHands()` and stores the result.
- **Depends on:** `Dealer` (must load after `Cards`/`Deck`/`Dealer`).
- **Not modified by Sprint 3.5** — `GameSession`'s side of this contract (`session.hands = Dealer.dealHands();`, and reading `session.hands[id]` afterward) was already exactly compatible with `Dealer.dealHands()`'s return shape before this sprint; the audit confirmed this (`docs/reviews/Engine_Dependency_Audit_3.4.5.md`, §7). Only the missing piece *below* `GameSession` in the chain (`Deck`) needed to be built.

## Who calls into this chain today

`bidding-engine.js` and `table-engine.js` both read hands exclusively through `GameSession.ensureHandsDealt()` — neither touches `Dealer`, `Deck`, or `Cards` directly. Neither file was modified by Sprint 3.5 (not required — the chain above was already exactly what they expect).

**Still true after this sprint, unchanged from the Sprint 3.4.5 audit:** no HTML screen in this repository currently loads any of `cards.js`/`deck.js`/`dealer.js`/`session.js`/`bidding-engine.js`/`table-engine.js`/`scoring-engine.js` via a `<script>` tag, and `MatchService` (the Firestore-backed multiplayer layer) does not call into this chain at all yet. Sprint 3.5's scope was explicitly "make the engine executable" (verified via real, executed Node tests loading these files directly — see `tests/deck.test.cjs`), **not** wiring it into a live screen or into `MatchService`. See `docs/reviews/Engine_Dependency_Audit_3.4.5.md`'s "Recommended Next Sprint" for what that would involve, and the two genuinely open, not-yet-decided questions it flagged that this sprint deliberately did not resolve:
- Which of the three (previously identically-valued, still-duplicated) `SUITS`/`RANKS` tables (`cards.js`'s, `bidding-engine.js`'s own local copy, `table-engine.js`'s own local copy) an eventual consolidation should treat as authoritative — `deck.js` itself already resolves this for its own purposes (it uses `Cards.DECK_SUITS`/`Cards.RANKS` exclusively, per the brief's explicit "do not duplicate" requirement), but `bidding-engine.js`/`table-engine.js`'s own separate local copies were out of this sprint's Engine Boundaries and were not touched.
- Whether real multiplayer dealing should live in `MatchService` (Firestore-direct) or bridge through `GameSession` (`sessionStorage`-only today) — still undecided, still not this sprint's job.
