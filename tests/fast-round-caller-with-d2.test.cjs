const assert = require("node:assert");

global.window = global;
global.window.addEventListener = function () {};

require("../design-ui/engine/cards.js");
require("../design-ui/engine/deck.js");
require("../design-ui/engine/dealer.js");
require("../design-ui/engine/session.js");
require("../design-ui/engine/bidding-engine.js");
require("../design-ui/engine/scoring-engine.js");

const GameSession = global.GameSession;
const BiddingEngine = global.BiddingEngine;

function beginFastRound(roundNumber, dealerId) {
  GameSession.reset(null);
  if (dealerId) GameSession.setDealer(dealerId);
  GameSession.setRound({ number: roundNumber, maxRounds: 18 });
  BiddingEngine.initState();
  const state = BiddingEngine.getState();
  assert.equal(state.fastRound, true);
  assert.equal(state.subPhase, "ESTIMATES");
  return state;
}

function submitFastEstimates(roundNumber, estimates, dealerId) {
  beginFastRound(roundNumber, dealerId);
  for (const tricks of estimates) {
    const state = BiddingEngine.getState();
    const intent = { type: "SubmitFinalEstimate", playerId: state.waitingFor, tricks };
    assert.equal(BiddingEngine.canSubmit(intent).legal, true, "estimate should be legal for " + state.waitingFor);
    BiddingEngine.emit(intent);
  }
  return {
    local: BiddingEngine.getState(),
    bidding: GameSession.getBiddingState(),
    round: GameSession.getRound()
  };
}

// Ordinary fast round: a unique highest estimate is the Caller, and lower
// estimates do not become With.
let result = submitFastEstimates(14, [4, 7, 3, 5]);
assert.equal(result.local.subPhase, "DONE");
assert.equal(result.local.callerId, "p2");
assert.deepEqual(result.local.withPlayers, []);
assert.equal(result.bidding.callerId, "p2");
assert.deepEqual(result.bidding.withPlayers, []);
assert.equal(result.round.callerId, "p2");
assert.deepEqual(result.round.withPlayers, []);
assert.equal(result.round.trump, "SANS");
assert.equal(GameSession.getTurn(), "p2");

// Fast-round ties use the first bidder at the maximum as Caller; every other
// maximum bidder becomes With, capped at the other three seats.
result = submitFastEstimates(15, [7, 5, 7, 7], "p3");
assert.equal(result.local.callerId, "p3");
assert.deepEqual(result.local.withPlayers, ["p1", "p2"]);
assert.equal(result.bidding.callerId, "p3");
assert.deepEqual(result.bidding.withPlayers, ["p1", "p2"]);
assert.equal(result.round.callerId, "p3");
assert.deepEqual(result.round.withPlayers, ["p1", "p2"]);
assert.equal(result.round.trump, "SPADES");

result = submitFastEstimates(16, [6, 6, 6, 6]);
assert.equal(result.local.callerId, "p1");
assert.deepEqual(result.local.withPlayers, ["p2", "p3", "p4"]);
assert.equal(result.round.callerId, "p1");
assert.deepEqual(result.round.withPlayers, ["p2", "p3", "p4"]);

// A fast-round Super Call remains the separate confirmation path and only
// seats preceding the Super Caller are reset for re-estimation.
result = submitFastEstimates(17, [4, 8, 5, 6]);
assert.equal(result.local.subPhase, "CONFIRM");
assert.equal(result.local.callerId, "p2");
assert.equal(result.local.auctionTop, 8);
assert.equal(result.local.noSuitConstraint, true);
BiddingEngine.emit({ type: "SubmitConfirmCall", playerId: "p2", tricks: 8, suit: "HEARTS" });
assert.equal(BiddingEngine.getState().subPhase, "ESTIMATES");
assert.equal(BiddingEngine.getState().waitingFor, "p1");
assert.equal(BiddingEngine.getState().bids.p1, undefined);
assert.equal(BiddingEngine.getState().bids.p2.amount, 8);
assert.equal(BiddingEngine.getState().bids.p3.amount, 5);
assert.equal(BiddingEngine.getState().bids.p4.amount, 6);
BiddingEngine.emit({ type: "SubmitFinalEstimate", playerId: "p1", tricks: 3 });
assert.equal(BiddingEngine.getState().subPhase, "DONE");
assert.equal(GameSession.getRound().callerId, "p2");
assert.equal(GameSession.getRound().trump, "HEARTS");
assert.equal(GameSession.getRound().estimates.p1, 3);
assert.equal(GameSession.getRound().estimates.p2, 8);
assert.equal(GameSession.getRound().estimates.p3, 5);
assert.equal(GameSession.getRound().estimates.p4, 6);

assert.deepEqual(
  [14, 15, 16, 17, 18, 19, 20, 21, 22, 23].map((round) => BiddingEngine.fixedTrumpFor(round)),
  ["SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS", "SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"]
);

console.log("PASS  D-2 fast-round Caller/With, Super Call reset, and extension suit sequence");
