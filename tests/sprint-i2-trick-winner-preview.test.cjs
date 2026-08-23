const path = require("path");
// Portability fix (established convention this session): never
// hardcode this sandbox's own absolute path.
const __REPO_ROOT__ = path.join(__dirname, "..");

// SPRINT I.2 (Turn Authority / Trick-Boundary Fix) — focused, real,
// executable unit tests for `TableEngine.previewPlay()`'s new
// trick-completing branch: when the pending (4th) card would complete
// a trick, `nextTurnSeat` must be the REAL trick winner (never `null`),
// computed by reusing the exact same `trickWinner()`/`cardValue()`
// logic `resolveTrick()` itself uses -- never a second, independent
// winner algorithm. Exercises the REAL, unmodified
// design-ui/engine/cards.js, deck.js, dealer.js, session.js,
// bidding-engine.js, scoring-engine.js, table-engine.js.
//
// Method: drive one real round through real bidding (same driver
// table-engine-foundation-fix.test.cjs/sprint-h-remote-hand-state.test.cjs
// already establish) to get a real, correctly-initialized TableEngine
// instance, then directly manipulate the state object `getState()`
// returns (a live reference, not a copy -- an existing, intentional
// export) to construct controlled trump/ledSuit/hand scenarios for
// each required edge case. This never calls a private/undocumented
// API -- `getState()` has always returned the live object.
global.window = global;
global.window.addEventListener = function () {};

require(__REPO_ROOT__ + "/design-ui/engine/cards.js");
require(__REPO_ROOT__ + "/design-ui/engine/deck.js");
require(__REPO_ROOT__ + "/design-ui/engine/dealer.js");
require(__REPO_ROOT__ + "/design-ui/engine/session.js");
require(__REPO_ROOT__ + "/design-ui/engine/bidding-engine.js");
require(__REPO_ROOT__ + "/design-ui/engine/scoring-engine.js");
require(__REPO_ROOT__ + "/design-ui/engine/table-engine.js");

var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var TableEngine = global.TableEngine;

var pass = 0, fail = 0;
function check(label, cond, extra) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (extra !== undefined ? " -- " + JSON.stringify(extra) : "")); fail++; }
}

function card(suit, v, s) { return { suit: suit, rank: { v: v, s: s || String(v) } }; }

function driveBiddingRound(tricks, suit) {
  for (var i = 0; i < 4; i++) {
    var s = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: s.waitingFor, declaredDashCall: false });
  }
  var s2 = BiddingEngine.getState();
  var opener = s2.waitingFor;
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: opener, tricks: tricks, suit: suit, isPass: false });
  var s3 = BiddingEngine.getState();
  while (s3.subPhase === "AUCTION") {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s3.waitingFor, isPass: true });
    s3 = BiddingEngine.getState();
  }
  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s3.waitingFor, tricks: s3.auctionTop, suit: s3.auctionSuit });
  var s4 = BiddingEngine.getState();
  while (s4.subPhase === "ESTIMATES") {
    BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: s4.waitingFor, tricks: 0 });
    s4 = BiddingEngine.getState();
  }
}

BiddingEngine.initState();
driveBiddingRound(7, "HEARTS"); // trump = HEARTS for every scenario below
GameSession.clearPlayState();
TableEngine.initState();

/** Reset the live state object to a controlled, mid-trick scenario:
 *  3 already-played cards (p1/p2/p3, in that order) plus a real hand
 *  for the pending 4th player (p4) containing exactly the pendingCard
 *  given (so canPlayCard()'s own legality check passes for real,
 *  never bypassed). ledSuit is set to the FIRST play's suit, matching
 *  emit()'s own real behavior. */
function setupTrick(plays, pendingSeat, pendingCard) {
  var st = TableEngine.getState();
  st.phase = "PLAY";
  st.trump = "HEARTS";
  st.ledSuit = plays[0].card.suit;
  st.plays = plays.map(function (p) { return { playerId: p.playerId, card: p.card }; });
  st.turn = pendingSeat;
  // Give the pending seat a real hand containing exactly the pending
  // card (plus filler that is NEVER of the led suit, so a "void
  // player" scenario is a genuine void, not accidentally holding a
  // led-suit card the follow-suit rule would then require instead) so
  // canPlayCard()'s follow-suit legality check is satisfied for real,
  // never bypassed.
  var fillerSuit = ["CLUBS", "DIAMONDS", "SPADES", "HEARTS"].filter(function (s) { return s !== st.ledSuit; })[0];
  var filler = [card(fillerSuit, 2), card(fillerSuit === "SPADES" ? "CLUBS" : "SPADES", 3)].filter(function (c) {
    return !(c.suit === pendingCard.suit && c.rank.v === pendingCard.rank.v) && c.suit !== st.ledSuit;
  });
  st.hands[pendingSeat] = [pendingCard].concat(filler);
}

function previewedWinner(pendingSeat, pendingCard) {
  var res = TableEngine.previewPlay(pendingSeat, pendingCard);
  return res;
}

/** Cross-check: apply the SAME 4 plays for real via emit()/resolveTrick()
 *  and read back the actual winner resolveTrick() computed -- proving
 *  previewedWinner === resolvedWinner, not just "some seat." */
function resolvedWinnerFor(plays, pendingSeat, pendingCard) {
  var st = TableEngine.getState();
  st.phase = "PLAY";
  st.trump = "HEARTS";
  st.ledSuit = plays[0].card.suit;
  st.plays = plays.map(function (p) { return { playerId: p.playerId, card: Object.assign({}, p.card) }; });
  st.turn = pendingSeat;
  st.hands[pendingSeat] = [pendingCard];
  st.voids = { p1: [], p2: [], p3: [], p4: [] };
  st.tricksWon = { p1: 0, p2: 0, p3: 0, p4: 0 };
  var emitResult = TableEngine.emit({ type: "PlayCard", playerId: pendingSeat, card: pendingCard });
  if (!emitResult || emitResult.rejected) return { rejected: true };
  TableEngine.resolveTrick();
  return { winnerId: TableEngine.getState().lastTrick.winnerId };
}

// ════════════════════════════════════════════════════════════════
// EDGE CASE 1 — Normal trick completion, no trump involved: highest
// card of the led suit wins.
// ════════════════════════════════════════════════════════════════
(function () {
  var plays = [
    { playerId: "p1", card: card("SPADES", 10, "10") },
    { playerId: "p2", card: card("SPADES", 5, "5") },
    { playerId: "p3", card: card("SPADES", 8, "8") }
  ];
  var pendingCard = card("SPADES", 12, "Q"); // p4 plays the Q of spades -- highest led-suit card
  setupTrick(plays, "p4", pendingCard);
  var preview = previewedWinner("p4", pendingCard);
  check("EDGE 1 (normal completion): previewPlay() reports this as trick-completing (nextPhase RESOLVING)", preview.legal && preview.nextPhase === "RESOLVING", preview);
  check("EDGE 1 (normal completion): the highest led-suit card (p4's Q-spades) wins, no trump in play", preview.nextTurnSeat === "p4", preview);

  var resolved = resolvedWinnerFor(plays, "p4", pendingCard);
  check("EDGE 1: previewedWinner === resolvedWinner (real resolveTrick() cross-check)", preview.nextTurnSeat === resolved.winnerId, { preview: preview.nextTurnSeat, resolved: resolved.winnerId });
})();

// ════════════════════════════════════════════════════════════════
// EDGE CASE 2 — Trump winner: a trump card wins over the led suit,
// regardless of rank.
// ════════════════════════════════════════════════════════════════
(function () {
  var plays = [
    { playerId: "p1", card: card("SPADES", 14, "A") }, // led suit, highest possible rank
    { playerId: "p2", card: card("SPADES", 5, "5") },
    { playerId: "p3", card: card("CLUBS", 2, "2") }     // off-suit, void, no trump
  ];
  var pendingCard = card("HEARTS", 2, "2"); // trump suit, LOWEST rank -- must still beat the Ace of spades
  setupTrick(plays, "p4", pendingCard);
  var preview = previewedWinner("p4", pendingCard);
  check("EDGE 2 (trump winner): a low trump card (2 of hearts) beats the led suit's Ace", preview.nextTurnSeat === "p4", preview);

  var resolved = resolvedWinnerFor(plays, "p4", pendingCard);
  check("EDGE 2: previewedWinner === resolvedWinner", preview.nextTurnSeat === resolved.winnerId, { preview: preview.nextTurnSeat, resolved: resolved.winnerId });
})();

// ════════════════════════════════════════════════════════════════
// EDGE CASE 3 — Higher same-suit card: among two led-suit cards, the
// higher rank wins (no trump anywhere in the trick).
// ════════════════════════════════════════════════════════════════
(function () {
  var plays = [
    { playerId: "p1", card: card("CLUBS", 7, "7") },
    { playerId: "p2", card: card("CLUBS", 9, "9") },
    { playerId: "p3", card: card("CLUBS", 4, "4") }
  ];
  var pendingCard = card("CLUBS", 6, "6"); // led suit, but LOWER than p2's 9 -- p2 should still win
  setupTrick(plays, "p4", pendingCard);
  var preview = previewedWinner("p4", pendingCard);
  check("EDGE 3 (higher same-suit card): p2's 9-of-clubs remains the winner over p4's lower 6-of-clubs", preview.nextTurnSeat === "p2", preview);

  var resolved = resolvedWinnerFor(plays, "p4", pendingCard);
  check("EDGE 3: previewedWinner === resolvedWinner", preview.nextTurnSeat === resolved.winnerId, { preview: preview.nextTurnSeat, resolved: resolved.winnerId });
})();

// ════════════════════════════════════════════════════════════════
// EDGE CASE 4 — Void player: the pending player has no card in the led
// suit and legally plays an off-suit, non-trump card (must not win).
// ════════════════════════════════════════════════════════════════
(function () {
  var plays = [
    { playerId: "p1", card: card("DIAMONDS", 10, "10") },
    { playerId: "p2", card: card("DIAMONDS", 5, "5") },
    { playerId: "p3", card: card("DIAMONDS", 8, "8") }
  ];
  var pendingCard = card("CLUBS", 14, "A"); // void in diamonds, off-suit, non-trump -- cannot win no matter the rank
  setupTrick(plays, "p4", pendingCard);
  var preview = previewedWinner("p4", pendingCard);
  check("EDGE 4 (void player): p4's off-suit Ace of clubs (void in led suit, not trump) does NOT win -- p1's led 10-of-diamonds still wins", preview.nextTurnSeat === "p1", preview);

  var resolved = resolvedWinnerFor(plays, "p4", pendingCard);
  check("EDGE 4: previewedWinner === resolvedWinner", preview.nextTurnSeat === resolved.winnerId, { preview: preview.nextTurnSeat, resolved: resolved.winnerId });
})();

// ════════════════════════════════════════════════════════════════
// EDGE CASE 5 — Trump lead: the FIRST card of the trick is itself
// trump; a later, non-trump card (even a high one) must not beat it,
// but a later HIGHER trump must.
// ════════════════════════════════════════════════════════════════
(function () {
  var plays = [
    { playerId: "p1", card: card("HEARTS", 5, "5") },  // trump lead
    { playerId: "p2", card: card("SPADES", 14, "A") }, // off-suit, non-trump -- irrelevant rank
    { playerId: "p3", card: card("HEARTS", 9, "9") }   // higher trump -- currently winning
  ];
  var pendingCard = card("HEARTS", 3, "3"); // lower trump than p3's -- must NOT overtake
  setupTrick(plays, "p4", pendingCard);
  var preview = previewedWinner("p4", pendingCard);
  check("EDGE 5 (trump lead): p3's higher trump (9 of hearts) still wins over p4's lower trump (3 of hearts)", preview.nextTurnSeat === "p3", preview);

  var resolved = resolvedWinnerFor(plays, "p4", pendingCard);
  check("EDGE 5: previewedWinner === resolvedWinner", preview.nextTurnSeat === resolved.winnerId, { preview: preview.nextTurnSeat, resolved: resolved.winnerId });
})();

// ════════════════════════════════════════════════════════════════
// EDGE CASE 6 — non-trick-completing play (only 1 play already made,
// this is the 2nd of 4): previewPlay() must be COMPLETELY unaffected
// by this sprint's change -- still returns nextCCW(), not a winner.
// ════════════════════════════════════════════════════════════════
(function () {
  var plays = [{ playerId: "p1", card: card("SPADES", 10, "10") }];
  var pendingCard = card("SPADES", 5, "5");
  setupTrick(plays, "p2", pendingCard);
  var preview = previewedWinner("p2", pendingCard);
  check("EDGE 6 (no regression): a non-trick-completing play (2nd of 4) still returns nextPhase PLAY, not RESOLVING",
    preview.legal && preview.nextPhase === "PLAY", preview);
  check("EDGE 6: nextTurnSeat is still the ordinary next-seat-in-order answer (p3), not a computed winner",
    preview.nextTurnSeat === "p3", preview);
})();

// ════════════════════════════════════════════════════════════════
// EDGE CASE 7 — later trick / later round number: the SAME logic must
// hold identically regardless of which trick or round number this is
// (trickNo/round are never consulted by trickWinner()/cardValue() at
// all -- proving this by using trickNo 9 mid-round, an arbitrary
// non-first value).
// ════════════════════════════════════════════════════════════════
(function () {
  var st = TableEngine.getState();
  st.trickNo = 9;
  var plays = [
    { playerId: "p2", card: card("DIAMONDS", 11, "J") },
    { playerId: "p3", card: card("DIAMONDS", 4, "4") },
    { playerId: "p4", card: card("CLUBS", 14, "A") } // void, non-trump, cannot win
  ];
  var pendingCard = card("DIAMONDS", 13, "K");
  setupTrick(plays, "p1", pendingCard);
  var preview = previewedWinner("p1", pendingCard);
  check("EDGE 7 (later trick, trickNo=9): p1's higher led-suit King wins, identical logic regardless of trick number",
    preview.nextTurnSeat === "p1", preview);
})();

console.log("\n=== Sprint I.2: Trick Winner Preview (previewPlay() trick-completing branch) ===\n");
console.log(pass + " passed, " + fail + " failed");
if (fail > 0) process.exit(1);
