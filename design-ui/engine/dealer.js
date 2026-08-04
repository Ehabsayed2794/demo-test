/* ════════════════════════════════════════════════════════════════════
   Estimation — Dealer
   Deals a fresh deck out to the table in the correct seat order.
   Depends on cards.js + deck.js.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {
  // Dealing order: Player, AI Left, AI Top, AI Right.
  // (Table seat layout is p1 bottom / p2 right / p3 top / p4 left — the
  // deal walks Player then around the AI seats left→top→right.)
  var DEAL_ORDER = ["p1", "p4", "p3", "p2"];
  var SEAT_ROLES = { p1: "Player", p4: "AI Left", p3: "AI Top", p2: "AI Right" };

  function sortHand(cards) { return cards.slice().sort(Cards.compareForSort); }

  /** Shuffle a fresh deck and deal 13 cards to each seat, one card at a
   *  time in seat order (mirrors a real deal, not four 13-card slices).
   *  Returns { p1:[...13 cards], p2:[...], p3:[...], p4:[...] }, each
   *  hand pre-sorted for display. */
  function dealHands(seatOrder) {
    var order = (seatOrder && seatOrder.length) ? seatOrder : DEAL_ORDER;
    Deck.reset();
    Deck.shuffle();
    var hands = {};
    order.forEach(function (id) { hands[id] = []; });
    for (var round = 0; round < 13; round++) {
      order.forEach(function (id) {
        var card = Deck.deal(1)[0];
        card.owner = id;
        hands[id].push(card);
      });
    }
    order.forEach(function (id) { hands[id] = sortHand(hands[id]); });
    return hands;
  }

  global.Dealer = { DEAL_ORDER: DEAL_ORDER, SEAT_ROLES: SEAT_ROLES, dealHands: dealHands, sortHand: sortHand };
})(window);
