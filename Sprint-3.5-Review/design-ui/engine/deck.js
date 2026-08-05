/* ════════════════════════════════════════════════════════════════════
   Estimation — Deck
   Sprint 3.5 (Deck Implementation & Engine Integration): the module
   dealer.js's own header comment has always said it depends on
   ("Depends on cards.js + deck.js.") but that was never delivered —
   see docs/reviews/Engine_Dependency_Audit_3.4.5.md for the full audit
   that confirmed this was a genuinely missing file, not a naming/
   loading/export bug.

   Ownership boundary (read before touching either file): Cards owns
   what a card IS — suit/rank tables, `createCard()`, display sort.
   Deck owns nothing about what a card is; it owns only DECK STATE
   (which 52 cards remain, in what order) and DECK BEHAVIOR (build,
   shuffle, draw, how many remain). Deck builds its 52 cards by calling
   Cards.createCard() once per (suit, rank) pair over Cards.DECK_SUITS ×
   Cards.RANKS — it does NOT redefine suits, ranks, or the card shape
   itself anywhere in this file. No gameplay logic (bidding, scoring,
   trump, dealing ORDER/seats) belongs here — that remains dealer.js's
   job and beyond.

   Requires cards.js to already be loaded first (script order — see
   dealer.js/the screen that eventually includes both).
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  /** Builds one fresh, ordered, exactly-52-card set — one Cards.createCard()
   *  call per (suit, rank) combination over Cards.DECK_SUITS × Cards.RANKS
   *  (4 × 13). Pure — no shuffling here; reset()/the constructor call this
   *  and then leave ordering to shuffle(). */
  function buildFullDeck() {
    var cards = [];
    Cards.DECK_SUITS.forEach(function (suitKey) {
      Cards.RANKS.forEach(function (rank) {
        cards.push(Cards.createCard(suitKey, rank));
      });
    });
    return cards;
  }

  /** Standard Fisher–Yates (in place, last-to-first). `rng` is an
   *  optional injectable random source — a function returning a float
   *  in [0, 1), matching Math.random()'s contract — defaulting to
   *  Math.random itself. This is the one deliberate design decision
   *  flagged as open in the Sprint 3.4.5 audit ("no deterministic/
   *  injectable shuffle exists"): real gameplay always uses the
   *  default (Math.random, unchanged behavior); automated tests can
   *  pass a fixed/seeded `rng` to get a fully deterministic, exactly-
   *  predictable resulting order without needing to fake Math.random
   *  globally. */
  function fisherYates(cards, rng) {
    rng = rng || Math.random;
    for (var i = cards.length - 1; i > 0; i--) {
      var j = Math.floor(rng() * (i + 1));
      var tmp = cards[i];
      cards[i] = cards[j];
      cards[j] = tmp;
    }
    return cards;
  }

  /** Deck() — a fresh, full, unshuffled 52-card deck. Instantiated per
   *  deal (`new Deck()`), not a shared global singleton — this is a
   *  deliberate improvement over the shape dealer.js's old, never-
   *  working calls implied (a `Deck.reset()`/`Deck.shuffle()` singleton
   *  API): a fresh instance per deal has no stale-shared-state to reset
   *  correctly in the first place. See dealer.js's updated usage. */
  function Deck() {
    this.cards = buildFullDeck();
  }

  /** Shuffle this deck's remaining cards in place (Fisher–Yates). Does
   *  NOT rebuild or top up the deck — shuffling a partially-drawn deck
   *  shuffles only what's left, which is the correct semantics for a
   *  real card deck. Returns `this` for chaining (`new Deck().shuffle()`). */
  Deck.prototype.shuffle = function (rng) {
    fisherYates(this.cards, rng);
    return this;
  };

  /** Remove and return exactly one card from the top of the deck.
   *  Throws a clear error if the deck is already empty — drawing past
   *  a real 52-card deck's end is a caller bug, not a valid state to
   *  silently paper over (fail loud, matching this project's existing
   *  "clear error, not a silent success" convention — see e.g.
   *  design-ui/room-service.js's required-argument checks). */
  Deck.prototype.draw = function () {
    if (this.cards.length === 0) throw new Error("Deck.draw(): no cards remaining.");
    return this.cards.pop();
  };

  /** How many cards are still in the deck (not yet drawn). */
  Deck.prototype.remaining = function () {
    return this.cards.length;
  };

  /** Rebuild this deck back to a fresh, full, unshuffled 52-card set —
   *  discards whatever was left/drawn. Every card produced is a brand-
   *  new object (a fresh Cards.createCard() call), never a reused
   *  reference to a previously-drawn card. Returns `this` for chaining. */
  Deck.prototype.reset = function () {
    this.cards = buildFullDeck();
    return this;
  };

  global.Deck = Deck;
})(window);
