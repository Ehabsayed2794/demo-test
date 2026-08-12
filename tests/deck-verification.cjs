// Deck verification script (as requested) — exercises the REAL,
// already-existing design-ui/engine/cards.js + deck.js + dealer.js,
// unmodified. No new Deck/Dealer implementation was needed or written;
// this file only proves the existing one satisfies the requested
// checks. See console output below for results.
var Cards, Deck, Dealer;
(function () {
  var fs = require("fs");
  var vm = require("vm");
  // ctx itself becomes the vm's global object once createContext() runs,
  // so ctx.window = ctx makes every engine file's `(function (global) {
  // ... global.Cards = ...; })(window)` IIFE set a property directly on
  // that same global object — exactly like `window` in a real browser,
  // where `window === globalThis`. This lets deck.js's bare `Cards`
  // reference (no `window.` prefix) resolve correctly, matching how it
  // actually runs in match/index.html.
  var ctx = { console: console };
  ctx.window = ctx;
  vm.createContext(ctx);
  ["design-ui/engine/cards.js", "design-ui/engine/deck.js", "design-ui/engine/dealer.js"].forEach(function (f) {
    vm.runInContext(fs.readFileSync(f, "utf8"), ctx, { filename: f });
  });
  Cards = ctx.Cards;
  Deck = ctx.Deck;
  Dealer = ctx.Dealer;
})();

var pass = 0, fail = 0;
function check(label, ok) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

// 1. Instantiate a new Deck.
var deck = new Deck();

// 2. Exactly 52 cards.
check("1. New Deck has exactly 52 cards", deck.remaining() === 52);

// 3. All cards unique (no duplicates).
var seen = {};
var dupFound = false;
deck.cards.forEach(function (c) {
  var key = c.suit + "-" + c.rank.v;
  if (seen[key]) dupFound = true;
  seen[key] = true;
});
check("2. All 52 cards are unique (no duplicates)", !dupFound && Object.keys(seen).length === 52);

// 4. Shuffle.
var beforeOrder = deck.cards.map(function (c) { return c.suit + c.rank.v; }).join(",");
deck.shuffle();
var afterOrder = deck.cards.map(function (c) { return c.suit + c.rank.v; }).join(",");
check("3. shuffle() reorders the deck in place (order changed)", beforeOrder !== afterOrder);
check("4. shuffle() does not change the card count", deck.remaining() === 52);

// 5. Draw all 52 cards; none null until truly empty.
var drawn = [];
var sawNullEarly = false;
for (var i = 0; i < 52; i++) {
  var c = deck.draw();
  if (c === null || c === undefined) sawNullEarly = true;
  drawn.push(c);
}
check("5. Drew 52 cards, none null/undefined", !sawNullEarly && drawn.length === 52);
check("6. Deck is empty after drawing all 52", deck.remaining() === 0);

// 6. Simulate dealing 4 hands (13 cards each), no cards remain.
var freshDeck = new Deck().shuffle();
var hands = { p1: [], p2: [], p3: [], p4: [] };
var seatOrder = ["p1", "p2", "p3", "p4"];
var seatIdx = 0;
while (freshDeck.remaining() > 0) {
  hands[seatOrder[seatIdx % 4]].push(freshDeck.draw());
  seatIdx++;
}
check("7. Each of 4 hands has exactly 13 cards",
  seatOrder.every(function (s) { return hands[s].length === 13; }));
check("8. No cards remain in the deck after dealing 4x13", freshDeck.remaining() === 0);

// 7. Cross-check against the REAL, unmodified Dealer.dealHands() too.
var dealt = Dealer.dealHands(seatOrder);
check("9. Dealer.dealHands() returns exactly 4 seats", Object.keys(dealt).length === 4);
check("10. Dealer.dealHands() gives each seat exactly 13 cards",
  seatOrder.every(function (s) { return dealt[s].length === 13; }));
var allDealtCards = seatOrder.reduce(function (acc, s) { return acc.concat(dealt[s]); }, []);
var dealtSeen = {};
allDealtCards.forEach(function (c) { dealtSeen[c.suit + "-" + c.rank.v] = true; });
check("11. Dealer.dealHands() output is 52 unique cards total",
  allDealtCards.length === 52 && Object.keys(dealtSeen).length === 52);

console.log("\n=== RESULTS ===\n");
console.log(pass + " passed, " + fail + " failed");
process.exitCode = fail > 0 ? 1 : 0;
