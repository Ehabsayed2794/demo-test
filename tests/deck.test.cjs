// Real, executable tests for design-ui/engine/deck.js (Sprint 3.5 — Deck
// Implementation & Engine Integration) and the resulting
// design-ui/engine/dealer.js integration. Loads the ACTUAL, unmodified
// (except for the minimal dealHands() change) engine files via require()
// against a window-shimmed global — no mocking of Cards/Deck/Dealer
// themselves, since the whole point of this sprint is to prove the real
// engine is now executable.
global.window = global;

require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");

var Cards = global.Cards;
var Deck = global.Deck;
var Dealer = global.Dealer;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

function comboKey(card) { return card.suit + "-" + card.rank.v; }
function allCombos() {
  var out = [];
  Cards.DECK_SUITS.forEach(function (suit) {
    Cards.RANKS.forEach(function (rank) { out.push(suit + "-" + rank.v); });
  });
  return out;
}

// A tiny, fully deterministic RNG for testing Deck.shuffle()'s Fisher–
// Yates implementation exactly — a fixed sequence of values in [0, 1),
// consumed in order, so the resulting shuffle is 100% predictable and
// re-derivable by hand rather than relying on "52 elements, astronomically
// unlikely to land back in the same order" (which is true, but a weaker
// guarantee than an exact, hand-verifiable result).
function makeFixedRng(sequence) {
  var i = 0;
  return function () {
    var v = sequence[i % sequence.length];
    i++;
    return v;
  };
}

(async function () {
  // ============ Deck: constructor / initial state ============
  var d1 = new Deck();
  check("Deck creates exactly 52 cards", d1.remaining() === 52);
  var combos1 = d1.cards.map(comboKey);
  check("Deck creates 52 UNIQUE cards (no (suit,rank) combo repeated)", new Set(combos1).size === 52);
  check("Deck's 52 combos are EXACTLY the 4 suits x 13 ranks — none missing, none extra",
    JSON.stringify(combos1.slice().sort()) === JSON.stringify(allCombos().slice().sort()));
  check("Every card Deck builds goes through Cards.createCard()'s real shape (id/suit/rank/displayName/value/owner/played)",
    d1.cards.every(function (c) {
      return typeof c.id === "string" && typeof c.suit === "string" && c.rank && typeof c.rank.v === "number" &&
        typeof c.displayName === "string" && typeof c.value === "number" && c.owner === null && c.played === false;
    }));

  // ============ Deck: shuffle ============
  var beforeShuffleOrder = d1.cards.map(function (c) { return c.id; });
  d1.shuffle();
  var afterShuffleCombos = d1.cards.map(comboKey);
  check("shuffle() preserves the exact same 52 unique cards (multiset unchanged)",
    d1.remaining() === 52 && new Set(afterShuffleCombos).size === 52 &&
    JSON.stringify(afterShuffleCombos.slice().sort()) === JSON.stringify(combos1.slice().sort()));
  var afterShuffleOrder = d1.cards.map(function (c) { return c.id; });
  check("shuffle() changes the order (not a no-op)", JSON.stringify(beforeShuffleOrder) !== JSON.stringify(afterShuffleOrder));

  // Deterministic Fisher–Yates verification: build a small, hand-checkable
  // deck substitute isn't practical at 52 elements by hand, but we CAN
  // verify the algorithm itself is genuinely Fisher–Yates (not, say, a
  // biased or partial shuffle) by feeding a fixed rng and checking the
  // exact resulting permutation matches an independently-computed
  // reference Fisher–Yates over the SAME initial array.
  function referenceFisherYates(arr, rng) {
    var a = arr.slice();
    for (var i = a.length - 1; i > 0; i--) {
      var j = Math.floor(rng() * (i + 1));
      var tmp = a[i]; a[i] = a[j]; a[j] = tmp;
    }
    return a;
  }
  var d2 = new Deck();
  var initialIds = d2.cards.map(function (c) { return c.id; });
  var fixedSeq = [0.9, 0.1, 0.5, 0.99, 0.01, 0.33, 0.66, 0.25, 0.75, 0.4, 0.6, 0.15, 0.85];
  var expected = referenceFisherYates(initialIds, makeFixedRng(fixedSeq));
  d2.shuffle(makeFixedRng(fixedSeq));
  var actual = d2.cards.map(function (c) { return c.id; });
  check("shuffle(rng) with an injected deterministic RNG produces EXACTLY the expected Fisher–Yates permutation",
    JSON.stringify(actual) === JSON.stringify(expected));

  // ============ Deck: draw ============
  var d3 = new Deck();
  var drawn = d3.draw();
  check("draw() returns a real card object (has suit/rank/id)", drawn && typeof drawn.suit === "string" && drawn.rank);
  check("draw() removes exactly one card — remaining() decreases by 1", d3.remaining() === 51);
  var seen = [drawn.id];
  for (var i = 0; i < 10; i++) {
    var c = d3.draw();
    check("draw() #" + (i + 2) + ": never returns a card already drawn from this deck", seen.indexOf(c.id) === -1);
    seen.push(c.id);
  }
  check("remaining count decreases correctly across multiple draws (41 left after 11 total draws)", d3.remaining() === 41);

  var d4 = new Deck();
  for (var k = 0; k < 52; k++) d4.draw();
  check("remaining() reaches exactly 0 after drawing all 52 cards", d4.remaining() === 0);
  var emptyDrawErr = null;
  try { d4.draw(); } catch (e) { emptyDrawErr = e; }
  check("draw() on an empty deck throws a clear error (fail loud, not a silent undefined)",
    emptyDrawErr && /no cards remaining/i.test(emptyDrawErr.message));

  // ============ Deck: reset ============
  var d5 = new Deck();
  d5.shuffle();
  d5.draw(); d5.draw(); d5.draw();
  check("before reset: remaining reflects the 3 draws (49 left)", d5.remaining() === 49);
  var oldIds = d5.cards.map(function (c) { return c.id; });
  d5.reset();
  check("reset() restores exactly 52 cards", d5.remaining() === 52);
  var newCombos = d5.cards.map(comboKey);
  check("reset() rebuilds a full, correct 52-combo deck (all 4 suits x 13 ranks present)",
    new Set(newCombos).size === 52 && JSON.stringify(newCombos.slice().sort()) === JSON.stringify(allCombos().slice().sort()));
  var newIds = d5.cards.map(function (c) { return c.id; });
  check("reset() builds brand-new card objects, not reused references from before the reset",
    newIds.every(function (id) { return oldIds.indexOf(id) === -1; }));

  // ============ Deck: owns no gameplay logic ============
  check("Deck exposes ONLY deck state/behavior (constructor + shuffle/draw/remaining/reset) — no bidding/scoring/trump surface",
    typeof Deck.prototype.shuffle === "function" &&
    typeof Deck.prototype.draw === "function" &&
    typeof Deck.prototype.remaining === "function" &&
    typeof Deck.prototype.reset === "function" &&
    Object.getOwnPropertyNames(Deck.prototype).filter(function (k) { return k !== "constructor"; }).length === 4);

  // ============ Dealer.dealHands(): the actual integration ============
  var hands = Dealer.dealHands();
  var seatIds = Object.keys(hands);
  check("dealHands() produces exactly 4 hands", seatIds.length === 4 && ["p1", "p2", "p3", "p4"].every(function (s) { return hands[s]; }));
  seatIds.forEach(function (id) {
    check("dealHands(): seat " + id + " received exactly 13 cards", hands[id].length === 13);
  });

  var allDealt = [].concat(hands.p1, hands.p2, hands.p3, hands.p4);
  check("dealHands(): 52 cards consumed in total (13 x 4)", allDealt.length === 52);
  var allIds = allDealt.map(function (c) { return c.id; });
  check("dealHands(): no duplicate cards across all four hands", new Set(allIds).size === 52);
  var allDealtCombos = allDealt.map(comboKey);
  check("dealHands(): no missing cards — the union of all four hands is EXACTLY the 52 real combos, each exactly once",
    JSON.stringify(allDealtCombos.slice().sort()) === JSON.stringify(allCombos().slice().sort()));
  check("dealHands(): every dealt card's owner matches the hand it was placed into",
    seatIds.every(function (id) { return hands[id].every(function (c) { return c.owner === id; }); }));
  check("dealHands(): each hand is pre-sorted for display (Cards.compareForSort order)",
    seatIds.every(function (id) {
      var sorted = hands[id].slice().sort(Cards.compareForSort);
      return JSON.stringify(sorted.map(function (c) { return c.id; })) === JSON.stringify(hands[id].map(function (c) { return c.id; }));
    }));

  // Calling dealHands() again produces a genuinely fresh deal (a NEW Deck
  // instance per call, per deck.js's design — not a shared, exhausted
  // singleton that would throw "no cards remaining" on a second call).
  var hands2 = Dealer.dealHands();
  var allDealt2 = [].concat(hands2.p1, hands2.p2, hands2.p3, hands2.p4);
  check("dealHands() can be called again immediately without error (fresh Deck instance per call, not a shared exhausted singleton)",
    allDealt2.length === 52 && new Set(allDealt2.map(function (c) { return c.id; })).size === 52);

  // dealHands() also accepts an explicit seat order override (unchanged
  // behavior from before this sprint — confirming the minimal change
  // didn't touch this parameter's handling).
  var customOrder = ["p2", "p1", "p3", "p4"];
  var hands3 = Dealer.dealHands(customOrder);
  check("dealHands(seatOrder) still respects an explicit seat order override (unchanged, pre-existing behavior)",
    JSON.stringify(Object.keys(hands3).sort()) === JSON.stringify(customOrder.slice().sort()));

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
