/* ════════════════════════════════════════════════════════════════════
   Estimation — Card Model
   Defines what a card IS. No shuffling/dealing here — see deck.js /
   dealer.js. Every card produced by createCard() carries the full
   shape the Card Engine spec requires: suit, rank, display name,
   value, a unique id, an owner, and a played flag.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  var SUITS = {
    SANS:     { id: "SANS",     sym: "SN", strength: 5, red: false, sans: true,  name: "Sans" },
    SPADES:   { id: "SPADES",   sym: "♠",  strength: 4, red: false, sans: false, name: "Spades" },
    HEARTS:   { id: "HEARTS",   sym: "♥",  strength: 3, red: true,  sans: false, name: "Hearts" },
    DIAMONDS: { id: "DIAMONDS", sym: "♦",  strength: 2, red: true,  sans: false, name: "Diamonds" },
    CLUBS:    { id: "CLUBS",    sym: "♣",  strength: 1, red: false, sans: false, name: "Clubs" }
  };
  // playable suits in a standard 52-card deck (SANS is a trump mode, not a suit of cards)
  var DECK_SUITS = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
  var RANKS = [
    { v: 14, s: "A" }, { v: 13, s: "K" }, { v: 12, s: "Q" }, { v: 11, s: "J" },
    { v: 10, s: "10" }, { v: 9, s: "9" }, { v: 8, s: "8" }, { v: 7, s: "7" },
    { v: 6, s: "6" }, { v: 5, s: "5" }, { v: 4, s: "4" }, { v: 3, s: "3" }, { v: 2, s: "2" }
  ];

  var uidCounter = 0;

  /** Create one card. `owner` is optional at creation time — the Dealer
   *  assigns it when the card is dealt into a hand. */
  function createCard(suitKey, rank, owner) {
    var suit = SUITS[suitKey];
    uidCounter += 1;
    return {
      id: suitKey + "-" + rank.v + "-" + uidCounter,
      suit: suitKey,
      rank: rank,                              // {v, s}
      displayName: rank.s + " " + suit.sym,
      value: rank.v,
      owner: owner || null,
      played: false
    };
  }

  /** Standard display sort: strongest suit first, then highest rank. */
  function compareForSort(a, b) {
    return (SUITS[b.suit].strength - SUITS[a.suit].strength) || (b.rank.v - a.rank.v);
  }

  global.Cards = {
    SUITS: SUITS,
    DECK_SUITS: DECK_SUITS,
    RANKS: RANKS,
    createCard: createCard,
    compareForSort: compareForSort
  };
})(window);
