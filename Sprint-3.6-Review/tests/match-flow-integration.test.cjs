// Real, executable integration test for Sprint 3.6 (Match Flow
// Integration) — the complete gameplay pipeline for ONE round:
//   Deck -> Dealer -> GameSession -> Bidding -> Card Play -> Trick
//   Resolution -> Scoring
// Loads the ACTUAL, mostly-unmodified engine files via require() against
// a window-shimmed global. bidding-engine.js and table-engine.js each
// received the SMALLEST possible addition (one `window.BiddingEngine`/
// `window.TableEngine` export object appended at the very end of the
// file, after their existing DOMContentLoaded bootstrap) — see
// docs/reviews/MatchFlowIntegration_3.6.md for why that was the minimum
// unavoidable wiring, and for a real, DISCOVERED (not fixed) scoring
// bug this test's design deliberately avoids — see
// tests/match-flow-normal-dash-scoring-bug.test.cjs, a separate file,
// for that bug's own dedicated, documented regression test.
//
// Architectural note this test's structure works around (documented,
// not fixed — see the Integration Report): bidding-engine.js's and
// table-engine.js's PLAYERS/TURN_ORDER/ROUND_CFG are computed ONCE, at
// require()-time, from GameSession's state at that instant — these
// files were built for a one-page-load-per-round browser flow, not for
// being re-required mid-process for a second round. This test therefore
// exercises exactly ONE round end-to-end, matching this sprint's own
// scope ("a complete match" == one full deal-to-score cycle, per the
// concrete requirements list: 52 cards, 13 tricks, scoring) — not the
// full 18-round Estimation match structure.
global.window = global;
global.window.addEventListener = function () {}; // no-op: no real DOM/browser exists in this test process

require("/home/user/demo-test/design-ui/engine/cards.js");
require("/home/user/demo-test/design-ui/engine/deck.js");
require("/home/user/demo-test/design-ui/engine/dealer.js");
require("/home/user/demo-test/design-ui/engine/session.js");
require("/home/user/demo-test/design-ui/engine/bidding-engine.js");
require("/home/user/demo-test/design-ui/engine/scoring-engine.js");
// table-engine.js is required LATER, after bidding completes — its
// top-level ROUND_CFG is computed at require()-time from GameSession's
// bidding result, so requiring it before bidding finishes would freeze
// in the wrong (mock/fallback) round configuration.

var Cards = global.Cards;
var GameSession = global.GameSession;
var BiddingEngine = global.BiddingEngine;
var ScoringEngine = global.ScoringEngine;

var pass = 0, fail = 0;
function check(label, cond) {
  if (cond) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}

var TURN_ORDER = ["p1", "p2", "p3", "p4"]; // matches session.js's mockPlayers() order, confirmed by direct inspection
function nextCCW(id) { return TURN_ORDER[(TURN_ORDER.indexOf(id) + 1) % TURN_ORDER.length]; }
function comboKey(card) { return card.suit + "-" + card.rank.v; }
function allCombos() {
  var out = [];
  Cards.DECK_SUITS.forEach(function (suit) { Cards.RANKS.forEach(function (rank) { out.push(suit + "-" + rank.v); }); });
  return out;
}
// Local re-derivation of table-engine.js's cardValue()/trickWinner() —
// used ONLY to independently cross-check the engine's own trick-
// resolution output against a from-scratch recomputation of the exact
// same 4 recorded plays, never to decide what to play.
var SUIT_STRENGTH = { SANS: 5, SPADES: 4, HEARTS: 3, DIAMONDS: 2, CLUBS: 1 };
function cardValueFor(card, trump, ledSuit) {
  var isTrump = trump !== "SANS" && card.suit === trump;
  var follows = card.suit === ledSuit;
  return card.rank.v + (isTrump ? 1000 : (follows ? 100 : 0));
}
function expectedTrickWinner(plays, trump, ledSuit) {
  var best = plays[0];
  plays.forEach(function (p) { if (cardValueFor(p.card, trump, ledSuit) > cardValueFor(best.card, trump, ledSuit)) best = p; });
  return best.playerId;
}
function legalCardsFor(state, id) {
  var hand = state.hands[id];
  if (!state.ledSuit) return hand.slice();
  var inSuit = hand.filter(function (c) { return c.suit === state.ledSuit; });
  return inSuit.length ? inSuit : hand.slice();
}

(function () {
  // ============ Bidding phase ============
  BiddingEngine.initState();
  var s = BiddingEngine.getState();
  check("Bidding starts in DASH sub-phase for round 1 (not a fast round)", s.subPhase === "DASH");
  check("Bidding waits on the dealer first", s.waitingFor === GameSession.getDealer());

  // GameSession dealt real hands via Dealer -> Deck -> Cards (Sprint 3.5's
  // chain) as a side effect of BiddingEngine.initState()'s own
  // GameSession.ensureHandsDealt() call.
  var dealtHands = GameSession.getHands();
  var allDealt = TURN_ORDER.reduce(function (acc, id) { return acc.concat(dealtHands[id]); }, []);
  check("Exactly 52 cards were dealt for this round (13 x 4 seats)", allDealt.length === 52);
  check("All 52 dealt cards are unique (no duplicate card ids)", new Set(allDealt.map(function (c) { return c.id; })).size === 52);
  check("The 52 dealt cards are EXACTLY the 4-suit x 13-rank deck — none missing, none extra",
    JSON.stringify(allDealt.map(comboKey).sort()) === JSON.stringify(allCombos().sort()));

  // DASH: every player declines (simplest deterministic path through this sub-phase).
  for (var i = 0; i < 4; i++) {
    var cur = BiddingEngine.getState();
    BiddingEngine.emit({ type: "SubmitDashCallDecision", playerId: cur.waitingFor, declaredDashCall: false });
  }
  s = BiddingEngine.getState();
  check("After all 4 decline, bidding moves to AUCTION with all 4 still active", s.subPhase === "AUCTION" && s.activeBidders.length === 4);

  // AUCTION: dealer (p1) bids 4 Spades; everyone else passes; auction
  // concludes uncontested with p1 as Caller.
  var auctionOpener = s.waitingFor;
  BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: auctionOpener, tricks: 4, suit: "SPADES", isPass: false });
  s = BiddingEngine.getState();
  var auctionSteps = 0;
  while (s.subPhase === "AUCTION" && auctionSteps < 10) {
    BiddingEngine.emit({ type: "SubmitAuctionBid", playerId: s.waitingFor, isPass: true });
    s = BiddingEngine.getState();
    auctionSteps++;
  }
  check("Auction concludes in CONFIRM with the opener as Caller", s.subPhase === "CONFIRM" && s.callerId === auctionOpener);
  check("Winning bid recorded correctly (4 Spades)", s.auctionTop === 4 && s.auctionSuit === "SPADES");

  // CONFIRM: Caller keeps their winning call as-is.
  BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: s.waitingFor, tricks: s.auctionTop, suit: s.auctionSuit });
  s = BiddingEngine.getState();
  check("Trump is locked to the confirmed suit", s.declaredTrump === "SPADES");
  check("Bidding moves to ESTIMATES after trump is confirmed", s.subPhase === "ESTIMATES");

  // ESTIMATES: the three non-Caller seats submit final estimates.
  // Deliberately avoids a 0 (Normal Dash) final estimate here — see this
  // file's header comment and tests/match-flow-normal-dash-scoring-bug.test.cjs
  // for the discovered, documented (not fixed) bug that path triggers.
  var picks = {}; picks[nextCCW(auctionOpener)] = 2;
  var estimateOrder = [];
  var estimateSteps = 0;
  while (s.subPhase === "ESTIMATES" && estimateSteps < 10) {
    var who = s.waitingFor;
    estimateOrder.push(who);
    var tricks = picks[who] != null ? picks[who] : 1;
    var res = BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: who, tricks: tricks });
    check("Final estimate for " + who + " (" + tricks + ") is accepted, not rejected", !res.rejected);
    s = BiddingEngine.getState();
    estimateSteps++;
  }
  check("Bidding completes (DONE) after all three remaining seats estimate", s.subPhase === "DONE");
  check("Exactly 3 seats submitted an explicit final estimate (Caller's confirm counts as their own bid)", estimateOrder.length === 3);

  var biddingResultRound = GameSession.getRound();
  check("GameSession.round.trump committed correctly", biddingResultRound.trump === "SPADES");
  check("GameSession.round.callerId committed correctly", biddingResultRound.callerId === auctionOpener);
  check("GameSession.getTurn() is stamped to the Caller (leads trick 1)", GameSession.getTurn() === auctionOpener);
  check("No estimate in this scripted round is exactly 0 (deliberately avoiding the known Normal-Dash bug)",
    Object.keys(biddingResultRound.estimates).every(function (id) { return biddingResultRound.estimates[id] !== 0; }));

  // ============ Card Play + Trick Resolution phase ============
  require("/home/user/demo-test/design-ui/engine/table-engine.js");
  var TableEngine = global.TableEngine;
  TableEngine.initState();
  var t = TableEngine.getState();
  check("Table phase seeds trump from the real bidding outcome (not a mock fallback)", t.trump === "SPADES");
  check("Table phase seeds the Caller from the real bidding outcome", t.callerId === auctionOpener);
  check("Trick 1's leader is the Caller (Estimation convention: Caller leads)", t.leaderId === auctionOpener);
  check("Table phase reuses the SAME 52-card deal bidding already saw (Card Engine centralization) — same hand sizes",
    TURN_ORDER.every(function (id) { return t.hands[id].length === 13; }));

  // ---- Deliberate illegal-play check: prove follow-suit is genuinely
  // ENFORCED by the engine, not merely respected by this test's own
  // card choices. Only meaningful if the current leader's hand actually
  // has more than one suit represented after the FIRST card of trick 1
  // is led by someone else — so this check runs against trick 1's
  // SECOND player specifically, who has a real ledSuit constraint. ----
  var illegalCheckDone = false;

  var allPlayedIds = [];
  var turnSequenceThisTrick = [];
  var tricksResolved = 0;
  var crossCheckedTricks = 0;
  var guard = 0;

  while (true) {
    guard++;
    if (guard > 500) { check("Trick-play loop terminated within a sane number of steps (no infinite loop)", false); break; }
    t = TableEngine.getState();
    if (t.phase === "DONE") break;

    if (t.phase === "RESOLVING") {
      var playsForThisTrick = t.plays.length ? t.plays : (t.lastTrick ? t.lastTrick.plays : []);
      TableEngine.resolveTrick();
      tricksResolved++;
      var afterResolve = TableEngine.getState();
      if (afterResolve.lastTrick) {
        var expectedWinner = expectedTrickWinner(afterResolve.lastTrick.plays, "SPADES", afterResolve.lastTrick.ledSuit);
        check("Trick " + tricksResolved + ": engine's recorded winner matches an independent recomputation from the actual recorded plays",
          afterResolve.lastTrick.winnerId === expectedWinner);
        crossCheckedTricks++;
      }
      turnSequenceThisTrick = [];
      continue;
    }

    // PLAY phase
    var who = t.turn;
    check("Trick " + (t.trickNo) + ": turn order is CCW from the leader for each play within the trick",
      turnSequenceThisTrick.length === 0 || who === nextCCW(turnSequenceThisTrick[turnSequenceThisTrick.length - 1]));
    turnSequenceThisTrick.push(who);

    var legal = legalCardsFor(t, who);

    // The one deliberate illegal-play attempt — fires at the FIRST
    // opportunity across the whole round (any non-leading play whose
    // player holds both the led suit and an off-suit card), not pinned
    // to trick 1 specifically, since a random deal might not offer that
    // exact opportunity on trick 1's second play.
    if (!illegalCheckDone && t.plays.length >= 1 && t.ledSuit) {
      var offSuit = t.hands[who].find(function (c) { return c.suit !== t.ledSuit; });
      var hasLedSuit = t.hands[who].some(function (c) { return c.suit === t.ledSuit; });
      if (offSuit && hasLedSuit) {
        var handBefore = t.hands[who].length;
        var illegalRes = TableEngine.emit({ type: "PlayCard", playerId: who, card: offSuit });
        check("Deliberate illegal play (holding the led suit, attempting to play off-suit) is REJECTED by the engine", illegalRes.rejected === true);
        var stateAfterIllegal = TableEngine.getState();
        check("A rejected illegal play leaves the player's hand size unchanged", stateAfterIllegal.hands[who].length === handBefore);
        illegalCheckDone = true;
        t = stateAfterIllegal; // legal remains valid — hands/turn unchanged by the rejected attempt
      }
    }

    var card = legal[0];
    var playRes = TableEngine.emit({ type: "PlayCard", playerId: who, card: card });
    check("Legal play for " + who + " is accepted, never rejected", playRes.rejected === false);
    allPlayedIds.push(card.id);
  }

  check("Illegal-play enforcement was actually exercised at least once during this test run", illegalCheckDone);
  check("Exactly 13 tricks were resolved", tricksResolved === 13);
  check("Every resolved trick's winner was independently cross-checked", crossCheckedTricks === 13);
  check("Exactly 52 cards were played across the whole round (13 tricks x 4 plays)", allPlayedIds.length === 52);
  check("No card was played twice across the whole round", new Set(allPlayedIds).size === 52);

  var finalState = TableEngine.getState();
  check("All four hands are empty after 13 tricks", TURN_ORDER.every(function (id) { return finalState.hands[id].length === 0; }));
  check("tricksWon across all four seats sums to exactly 13", TURN_ORDER.reduce(function (s2, id) { return s2 + finalState.tricksWon[id]; }, 0) === 13);
  check("Round phase reaches DONE", finalState.phase === "DONE");

  // ============ Scoring phase ============
  check("resolveTrick()'s 13th call computed a real ScoringEngine result as a side effect (no separate scoring call needed)",
    !!finalState._scoreResult && typeof finalState._scoreResult.deltas === "object");
  var deltas = finalState._scoreResult.deltas;
  check("Every seat received a finite score delta (guards the known Normal-Dash NaN bug — this scenario deliberately avoids it)",
    TURN_ORDER.every(function (id) { return typeof deltas[id] === "number" && Number.isFinite(deltas[id]); }));

  var matchScores = GameSession.getMatchScores();
  check("GameSession.getMatchScores() reflects the applied round deltas for every seat",
    TURN_ORDER.every(function (id) { return matchScores[id] === deltas[id]; }));

  var lastRound = GameSession.getLastRoundResult();
  check("GameSession.getLastRoundResult() recorded this round (round 1)", !!lastRound && lastRound.round === 1);
  check("The recorded round result's trump/callerId match what was actually played", lastRound.trump === "SPADES" && lastRound.callerId === auctionOpener);

  // Independent consistency check: re-deriving ScoringEngine's result from
  // the SAME reconstructed inputs table-engine.js used internally must
  // agree exactly with what was actually stored — proves the pipeline
  // wiring didn't silently diverge from ScoringEngine's own contract.
  var recheck = ScoringEngine.calculateRoundScore({
    round: 1, turnOrder: TURN_ORDER,
    bids: (function () {
      var estimates = biddingResultRound.estimates;
      var out = {};
      TURN_ORDER.forEach(function (id) { out[id] = { type: estimates[id] === 0 ? "DASH" : "TRICKS", amount: estimates[id] }; });
      return out;
    })(),
    tricksWon: finalState.tricksWon, callerId: auctionOpener, withPlayers: biddingResultRound.withPlayers,
    multiplier: 1, riskPlayerId: GameSession.getBiddingState().riskPlayerId,
    scoringMode: GameSession.getScoringMode(), escalationCap: 8
  });
  check("Re-deriving the score from the same inputs produces IDENTICAL deltas to what was actually applied",
    JSON.stringify(recheck.deltas) === JSON.stringify(deltas));

  console.log("\n" + pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})();
