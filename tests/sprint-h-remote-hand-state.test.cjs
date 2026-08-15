const path = require("path");
// Portability fix (established convention this session — see every
// other tests/*.test.cjs file): never hardcode this sandbox's own
// absolute path.
const __REPO_ROOT__ = path.join(__dirname, "..");

// Focused, real, executable tests for Sprint H (Remote Hand State /
// Table Engine Initialization Fix). Exercises the REAL, mostly-
// unmodified design-ui/engine/cards.js, deck.js, dealer.js,
// session.js, bidding-engine.js, table-engine.js — not stubs, not
// mocks — plus this sprint's own two changes:
//   1. session.js's setHandAuthorityMode(): discards any hands already
//      sitting in session.hands the moment a page transitions from
//      "local" into "firestore" authority (the confirmed root cause of
//      TableEngine ending up with a fabricated, non-authoritative hand
//      for every non-local seat — see that function's own comment).
//   2. table-engine.js's emit(): accepts intent.trusted (set only by
//      match-adapter.js's applyRemoteCard() for real cardLog replay) to
//      skip re-deriving follow-suit legality against a seat's own
//      hand data this client may legitimately never have; and a
//      defensive fix so a REJECTED first-card play (state.ledSuit ===
//      null) never throws instead of cleanly rejecting.
//
// This file cannot exercise table-engine.js's own DOMContentLoaded
// auto-init directly (these are plain Node unit tests, not a real
// browser DOM — global.window.addEventListener below is a no-op stub,
// exactly like every other *.test.cjs file in this repo; only the real
// Playwright-driven tests/e2e/sprint-g-full-match.cjs exercises an
// actual `document`). Instead, each scenario reproduces the EXACT
// observable precondition that auto-init call chain would have left
// behind (a full 4-seat fabricated deal already sitting in
// session.hands while handAuthorityMode is still "local") and proves
// this sprint's fix handles it correctly from that point forward —
// this is the same "reproduce the precondition, not the browser
// plumbing" approach already used by
// tests/table-engine-foundation-fix.test.cjs for an analogous
// page-load-ordering bug.
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

/** Same deterministic real-bidding driver as
 *  table-engine-foundation-fix.test.cjs (everyone Dash-declines, the
 *  first bidder bids tricks/suit, everyone else passes, confirms,
 *  every non-caller estimates 0). */
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
  return { finalState: s4, opener: opener };
}

function legalCardFor(seatId) {
  var st = TableEngine.getState();
  var hand = st.hands[seatId];
  if (!st.ledSuit) return hand[0];
  var inSuit = hand.filter(function (c) { return c.suit === st.ledSuit; });
  return (inSuit.length ? inSuit : hand)[0];
}

/** Real driver for a full 13-trick round, identical in shape to
 *  table-engine-foundation-fix.test.cjs's own driveFullRound(). */
function driveFullRound() {
  var guard = 0;
  while (TableEngine.getState().phase !== "DONE" && guard < 4000) {
    var st = TableEngine.getState();
    if (st.phase === "PLAY") {
      TableEngine.emit({ type: "PlayCard", playerId: st.turn, card: legalCardFor(st.turn) });
    } else if (st.phase === "RESOLVING") {
      TableEngine.resolveTrick();
    }
    guard++;
  }
}

(function () {
  BiddingEngine.initState();

  // ════════════════════════════════════════════════════════════════
  // SETUP — real bidding, exactly like every other engine test.
  // ════════════════════════════════════════════════════════════════
  driveBiddingRound(7, "HEARTS");
  var round = GameSession.getRound();
  check("Setup: real bidding completed (trump=HEARTS, caller=p1)", round.trump === "HEARTS" && round.callerId === "p1");

  // ════════════════════════════════════════════════════════════════
  // Reproduce the EXACT precondition table-engine.js's own
  // DOMContentLoaded auto-init leaves behind: initState() called while
  // handAuthorityMode is still its "local" default, BEFORE the page
  // has any chance to flip it to "firestore" — GameSession.
  // ensureHandsDealt() fabricates a full, independently-random 4-seat
  // hand and persists it, believing itself to be in ordinary
  // offline/local play.
  // ════════════════════════════════════════════════════════════════
  check("Setup: handAuthorityMode starts at its default, 'local'", GameSession.getHandAuthorityMode() === "local");
  var fabricated = GameSession.ensureHandsDealt();
  check("Setup: the reproduced bug precondition is real -- all 4 seats fabricated with 13 cards each",
    ["p1", "p2", "p3", "p4"].every(function (s) { return fabricated[s] && fabricated[s].length === 13; }),
    Object.keys(fabricated).map(function (s) { return s + ":" + fabricated[s].length; }));

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 1 — multiplayer initialization must not leave fabricated
  // opponent hands in place: the moment the page transitions into real
  // "firestore" hand authority, any hands dealt while still "local"
  // must be discarded, not silently trusted forever.
  // ════════════════════════════════════════════════════════════════
  GameSession.setHandAuthorityMode("firestore");
  var afterSwitch = GameSession.getHands();
  check("Scenario 1 (THE FIX): switching to 'firestore' mode discards the pre-existing fabricated hands for ALL seats",
    Object.keys(afterSwitch).length === 0, afterSwitch);
  check("Scenario 1: p1's fabricated hand specifically is gone, not just relabeled", GameSession.getHand("p1").length === 0);
  check("Scenario 1: p2/p3/p4's fabricated hands are gone too (not just the local seat)",
    GameSession.getHand("p2").length === 0 && GameSession.getHand("p3").length === 0 && GameSession.getHand("p4").length === 0);

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 2 — this client's OWN authoritative hand, once delivered
  // via the real Hand Sync path (setAuthoritativeHand(), unchanged by
  // this sprint), remains exactly correct — the fix must not throw the
  // baby out with the bathwater.
  // ════════════════════════════════════════════════════════════════
  var realP1Hand = fabricated.p1.slice(0, 13); // stand-in for a real Firestore-delivered hand doc
  GameSession.setAuthoritativeHand("p1", realP1Hand, round.number);
  check("Scenario 2 (own hand correctness): p1's authoritative hand is exactly what Hand Sync delivered",
    JSON.stringify(GameSession.getHand("p1")) === JSON.stringify(realP1Hand));
  check("Scenario 2: setAuthoritativeHand() for p1 does NOT resurrect p2/p3/p4's fabricated hands",
    GameSession.getHand("p2").length === 0 && GameSession.getHand("p3").length === 0 && GameSession.getHand("p4").length === 0);

  // ════════════════════════════════════════════════════════════════
  // Real multiplayer TableEngine.initState(), exactly as
  // maybeEnterPlayPhase() calls it in match/index.html — now against
  // GameSession state that has a real p1 hand and NO data at all for
  // p2/p3/p4 (matching the actual multiplayer architecture: this
  // client never holds another seat's private cards).
  // ════════════════════════════════════════════════════════════════
  GameSession.clearPlayState();
  TableEngine.initState();
  var st0 = TableEngine.getState();
  check("Setup: multiplayer TableEngine state has p1's real hand", st0.hands.p1 && st0.hands.p1.length === 13);
  check("Setup: multiplayer TableEngine state holds NO fabricated data for p2/p3/p4 (undefined or empty, never 13 random cards)",
    ["p2", "p3", "p4"].every(function (s) { return !st0.hands[s] || st0.hands[s].length === 0; }),
    st0.hands);

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 3 — remote card application must not require (or crash
  // without) opponent private cards. p1 leads (real, own hand); then
  // apply p2's remote card the way match-adapter.js's
  // applyRemoteCard() now does, via intent.trusted, even though
  // TableEngine has no private data at all for p2.
  // ════════════════════════════════════════════════════════════════
  var p1Card = legalCardFor("p1");
  var p1Result = TableEngine.emit({ type: "PlayCard", playerId: "p1", card: p1Card });
  check("Setup: p1's own real first-trick lead is accepted", p1Result && p1Result.rejected === false);

  var p2RemoteCard = { suit: "SPADES", rank: { v: 10, s: "10" } }; // an arbitrary real card p2 legitimately played -- this client has no way to know it was in p2's hand
  var beforeP2Hand = TableEngine.getState().hands.p2;
  check("Scenario 3 setup: TableEngine genuinely has no private data for p2 before this apply", !beforeP2Hand || beforeP2Hand.length === 0);
  var p2Result = TableEngine.emit({ type: "PlayCard", playerId: "p2", card: p2RemoteCard, trusted: true });
  check("Scenario 3 (THE FIX): a trusted remote apply for p2 is accepted even with zero private hand data for p2",
    p2Result && p2Result.rejected === false, p2Result);

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 4 — every client converges on the same PUBLIC trick
  // state: p2's real card is now observable via state.plays (the
  // public, shared record every client's own local TableEngine
  // maintains identically), not via any private hand data.
  // ════════════════════════════════════════════════════════════════
  var st1 = TableEngine.getState();
  var p2Play = st1.plays.filter(function (p) { return p.playerId === "p2"; })[0];
  check("Scenario 4: p2's real card is now visible in the shared public trick state",
    !!p2Play && p2Play.card.suit === "SPADES" && p2Play.card.rank.v === 10, st1.plays);

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 5 — the specific defect this sprint fixes: a real remote
  // card must NEVER be spuriously rejected merely because it's absent
  // from this client's (correctly empty, not fabricated) local copy of
  // that seat's hand. Re-run the exact same apply UNTRUSTED to prove
  // the OLD failure mode is real (would have rejected this legitimate
  // card), then confirm the actual (trusted) path used in production
  // does not hit it.
  // ════════════════════════════════════════════════════════════════
  var p3RemoteCard = { suit: "SPADES", rank: { v: 9, s: "9" } };
  var untrustedProbe = (function () {
    // Read-only probe: canPlayCard() never mutates state, so this is
    // safe to call before the real (trusted) apply below.
    return TableEngine.canPlayCard("p3", p3RemoteCard);
  })();
  check("Scenario 5 (proves the bug is real, not hypothetical): an UNTRUSTED legality check for a real remote card, against this client's empty local copy of p3's hand, is spuriously illegal",
    untrustedProbe.legal === false, untrustedProbe);
  var p3Result = TableEngine.emit({ type: "PlayCard", playerId: "p3", card: p3RemoteCard, trusted: true });
  check("Scenario 5 (THE FIX): the SAME card, applied the way applyRemoteCard() actually applies it (trusted), is correctly accepted, not spuriously rejected",
    p3Result && p3Result.rejected === false, p3Result);

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 6 — Phase 3's defensive fix: a genuinely rejected
  // first-card play (state.ledSuit is null -- this IS the first card
  // of the trick, since p1/p2/p3 above were all trusted applies that
  // never touched state.ledSuit... wait, p1's own lead DID set
  // ledSuit. Use a FRESH round/trick with a real illegal untrusted
  // attempt instead, so ledSuit is null at the moment of rejection.
  // ════════════════════════════════════════════════════════════════
  GameSession.setRound({ number: round.number + 1 });
  BiddingEngine.initState();
  driveBiddingRound(4, "CLUBS");
  var round2 = GameSession.getRound();
  GameSession.setHandAuthorityMode("firestore"); // already firestore; harmless no-op re-affirmation, matches real double-call sites
  var Dealer = global.Dealer;
  var freshDeal = Dealer.dealHands();
  GameSession.setAuthoritativeHand("p1", freshDeal.p1, round2.number);
  GameSession.clearPlayState();
  TableEngine.initState();
  var st2 = TableEngine.getState();
  check("Scenario 6 setup: round 2's first trick has not started yet -- ledSuit is null", st2.ledSuit === null);
  check("Scenario 6 setup: it is p1's own turn (the leader) and p1 has a real hand", st2.turn === "p1" && st2.hands.p1 && st2.hands.p1.length === 13);
  // A card guaranteed absent from p1's own real 13-card hand: build one
  // from a suit/rank combination not present in freshDeal.p1.
  var allSameSuitRankCombos = [];
  ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"].forEach(function (suit) {
    for (var v = 1; v <= 13; v++) allSameSuitRankCombos.push({ suit: suit, rank: { v: v, s: String(v) } });
  });
  var illegalCard = allSameSuitRankCombos.filter(function (c) {
    return !freshDeal.p1.some(function (own) { return own.suit === c.suit && own.rank.v === c.rank.v; });
  })[0];
  var threw = false, rejectResult = null;
  try {
    rejectResult = TableEngine.emit({ type: "PlayCard", playerId: "p1", card: illegalCard });
  } catch (e) {
    threw = true;
  }
  check("Scenario 6 (THE FIX): a rejected first-card play (ledSuit === null) does not throw", threw === false);
  check("Scenario 6: it is still correctly reported as a rejection", rejectResult && rejectResult.rejected === true, rejectResult);
  check("Scenario 6: the rejection reason is a clean, no-suit-name message ('Illegal play'), not a crash-avoidance placeholder for a legitimate follow-suit case",
    rejectResult && rejectResult.reason === "Illegal play", rejectResult);

  // ════════════════════════════════════════════════════════════════
  // SCENARIO 7 — local single-player/offline behavior (handAuthorityMode
  // never switched away from its "local" default) is completely
  // unaffected by this sprint's fix: a full round still deals real
  // hands for every seat immediately and plays out normally.
  // ════════════════════════════════════════════════════════════════
  GameSession.reset(null); // brand-new session, matching a genuinely fresh offline page load
  // handAuthorityMode is a separate, module-level flag (not part of the
  // persisted session object reset() replaces) — a genuinely fresh
  // offline page never calls setHandAuthorityMode() at all, so it
  // simply never leaves its "local" default. This test's own process
  // already flipped it to "firestore" for the earlier multiplayer
  // scenarios above, so it's set back explicitly here to reproduce
  // that same untouched-default starting condition.
  GameSession.setHandAuthorityMode("local");
  check("Scenario 7 setup: a fresh session defaults to 'local' hand authority", GameSession.getHandAuthorityMode() === "local");
  BiddingEngine.initState();
  driveBiddingRound(6, "DIAMONDS");
  GameSession.clearPlayState();
  TableEngine.initState();
  var offlineState = TableEngine.getState();
  check("Scenario 7 (no regression): offline/local mode still deals a real, full 13-card hand for every seat up front",
    ["p1", "p2", "p3", "p4"].every(function (s) { return offlineState.hands[s] && offlineState.hands[s].length === 13; }));
  driveFullRound();
  check("Scenario 7 (no regression): a full local/offline round still plays out to completion (13 tricks) exactly as before this sprint",
    TableEngine.getState().phase === "DONE");

  console.log("\n=== Sprint H: Remote Hand State / Table Engine Initialization Fix ===\n");
  console.log(pass + " passed, " + fail + " failed");
  if (fail > 0) process.exit(1);
})();
