/* ════════════════════════════════════════════════════════════════════
   Estimation — Bidding Phase Engine
   A faithful, browser-side mirror of GameReducer.kt's bidding logic.
   Pattern: UI emits INTENTS → reduce(state, intent) → new STATE → render().
   This is a DESIGN PROTOTYPE — same shapes & rules as the Kotlin model,
   so a BiddingControls composable maps onto it 1:1.
   ════════════════════════════════════════════════════════════════════ */
(function (global) {

// ── Suit model (mirrors Suit enum: strength SANS5 > ♠4 > ♥3 > ♦2 > ♣1) ──
const SUITS = {
  SANS:     { id: "SANS",     sym: "SN", strength: 5, red: false, sans: true,  name: "Sans" },
  SPADES:   { id: "SPADES",   sym: "♠",  strength: 4, red: false, sans: false, name: "Spades" },
  HEARTS:   { id: "HEARTS",   sym: "♥",  strength: 3, red: true,  sans: false, name: "Hearts" },
  DIAMONDS: { id: "DIAMONDS", sym: "♦",  strength: 2, red: true,  sans: false, name: "Diamonds" },
  CLUBS:    { id: "CLUBS",    sym: "♣",  strength: 1, red: false, sans: false, name: "Clubs" },
};
// Auction selector order: weakest → strongest
const SUIT_ORDER = ["CLUBS", "DIAMONDS", "HEARTS", "SPADES", "SANS"];
const RANKS = [
  { v: 14, s: "A" }, { v: 13, s: "K" }, { v: 12, s: "Q" }, { v: 11, s: "J" },
  { v: 10, s: "10" }, { v: 9, s: "9" }, { v: 8, s: "8" }, { v: 7, s: "7" },
  { v: 6, s: "6" }, { v: 5, s: "5" }, { v: 4, s: "4" }, { v: 3, s: "3" }, { v: 2, s: "2" },
];

const MAX_DASH_CALLS = 2;
// Player roster now owned by GameSession — no local game variables.
const PLAYERS = GameSession.getPlayers();
// counter-clockwise turn order used by the table
const TURN_ORDER = PLAYERS.map(p => p.id);

function nextCCW(id) {
  const i = TURN_ORDER.indexOf(id);
  return TURN_ORDER[(i + 1) % TURN_ORDER.length];
}

// Fast rounds (14-18): trump is mandatory and fixed, cycling this order —
// round 14=Sans, 15=Spades, 16=Hearts, 17=Diamonds, 18=Clubs. Matches the
// rules doc's suit hierarchy top-to-bottom (uploads/kotlinCode.ts FIXED_SUITS).
const FIXED_SUITS = ["SANS", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
function isFastRound(roundNumber) { return roundNumber >= 14; }
function fixedTrumpFor(roundNumber) { return FIXED_SUITS[(roundNumber - 14) % FIXED_SUITS.length]; }

// ── State ──
let state = null;

// Risk player = the last estimator = the seat immediately BEFORE the caller
// (mirrors table-engine.js's identical, independently-computed formula).
// Only a fallback now — the real signal is state.lastBidderId (whoever
// actually submitted the round's final estimate), which also covers fast
// rounds with no caller at all. See BiddingState.md.
function computeRiskId(callerId) {
  let id = callerId, prev = id;
  for (let k = 0; k < 3; k++) prev = nextCCW(prev);   // 3 steps CCW lands on the seat before caller
  return prev;
}

// ════════════════════════════════════════════════════════════════════
//  Sprint 3.6.1 (Bidding Engine Contract): LEGALITY PREDICATES — pure,
//  read-only conditions extracted verbatim out of emit()'s own reducer
//  branches below, so canSubmit() (this file's new public export) and
//  emit() itself share exactly ONE source of truth for each rule.
//  Extracting an inline boolean/condition into a named function changes
//  nothing about WHEN or WHY a bid is accepted or rejected — every call
//  site below passes the exact same inputs emit() already had in scope
//  at that point and gets back the exact same value the inline
//  expression already produced. No gameplay rule is reinterpreted here.
// ════════════════════════════════════════════════════════════════════
function auctionBidBeatsTop(bidVal, bidSuit, auctionTop, auctionSuit) {
  return bidVal > auctionTop ||
    (bidVal === auctionTop && SUITS[bidSuit]?.strength > (SUITS[auctionSuit]?.strength || 0));
}
function auctionBidIsWith(pId, bidVal, bidSuit, auctionTop, auctionSuit, auctionBidder) {
  return bidVal === auctionTop && bidSuit === auctionSuit && pId !== auctionBidder && auctionBidder != null;
}
function bidBelowWinningCall(t, auctionTop) {
  return t < auctionTop;
}
function confirmSuitTooWeak(t, s, auctionTop, auctionSuit, noSuitConstraint) {
  return !noSuitConstraint && t === auctionTop && SUITS[s].strength < SUITS[auctionSuit].strength;
}
function estimateExceedsCap(tricks, cap) {
  return tricks > cap;
}
function estimateBelowWithFloor(pId, tricks, withPlayers) {
  if (!withPlayers.includes(pId)) return false;
  const floor = withFloorFor(pId);
  return floor != null && tricks < floor;
}
// Shared by estimateIsForbidden13() (below) and canSubmit()'s own
// SubmitFinalEstimate case — the single place "what number is
// forbidden for this seat right now" is computed, mirroring R1's
// original inline `13 - otherSum` exactly.
function forbiddenEstimateFor(pId, bids) {
  const others = TURN_ORDER.filter(id => id !== pId && bids[id]);
  if (others.length !== PLAYERS.length - 1) return null;
  const otherSum = others.reduce((s, id) => s + (bids[id].type === "TRICKS" ? bids[id].amount : 0), 0);
  return 13 - otherSum;
}
function estimateIsForbidden13(pId, tricks, bids) {
  const forbidden = forbiddenEstimateFor(pId, bids);
  return forbidden != null && tricks === forbidden;
}

// dense {p1,p2,p3,p4} ↔ sparse {id:{type,amount}} bid-map conversions,
// so the engine's existing sparse reducer shape and GameSession's
// documented dense biddingState.bids shape can both stay as-is.
function sparseBidsToDense(sparse) {
  const dense = {};
  TURN_ORDER.forEach(id => { dense[id] = sparse[id] || null; });
  return dense;
}
function denseBidsToSparse(dense) {
  const sparse = {};
  TURN_ORDER.forEach(id => { if (dense && dense[id] != null) sparse[id] = dense[id]; });
  return sparse;
}
// Sprint 3.6.1 (Normal Dash Scoring Hotfix): committed estimates include
// both TRICKS and DASH bids — a Normal Dash (a final estimate of exactly
// 0 tricks, submitted via SubmitFinalEstimate; see that handler a few
// lines below) is JUST AS VALID a final estimate as a TRICKS bid, and 0
// is legitimate data, not missing data. Sprint 3.6's integration testing
// found this function silently dropped DASH-type bids entirely, which
// left the affected seat completely ABSENT from GameSession.round.estimates
// — indistinguishable from "never estimated at all" — and that missing
// entry later corrupted table-engine.js's bids reconstruction into
// {type:"TRICKS", amount:undefined}, producing a NaN score. See
// docs/reviews/MatchFlowIntegration_3.6.md and
// docs/reviews/MatchFlowIntegration_3.6.1.md for the full root-cause
// writeup. Fixed at the SOURCE (here, where the value was actually being
// dropped) rather than compensated for downstream. DASHCALL (the
// pre-bidding Dash Call, a different, earlier bid type entirely — see
// dashCallerIds() below) is deliberately still excluded here; it never
// reaches the Final Estimates phase at all and is carried separately via
// dashCallers, exactly as before — this is not a behavior change for
// that path, only for the Normal Dash (post-auction, 0-trick final
// estimate) path.
function extractEstimates(sparseBids) {
  const out = {};
  Object.keys(sparseBids).forEach(id => {
    const bid = sparseBids[id];
    if (bid.type === "TRICKS" || bid.type === "DASH") out[id] = bid.amount;
  });
  return out;
}

function initState() {
  // Card Engine: shuffling/dealing now happens once, centrally, via the
  // Dealer + Deck modules behind GameSession.ensureHandsDealt() — this
  // screen no longer builds its own deck, and a refresh/re-entry of
  // this same round reuses the existing deal instead of redealing.
  const hands = GameSession.ensureHandsDealt();
  const dealer = GameSession.getDealer();
  const round = GameSession.getRound().number;
  const fastRound = isFastRound(round);

  if (GameSession.isBiddingStateValidForCurrentRound()) {
    // Bidding State Persistence: resume the exact auction in progress
    // instead of restarting it on refresh/re-entry.
    const bs = GameSession.getBiddingState();
    state = {
      round,
      subPhase: bs.phase,
      hands,
      waitingFor: bs.turnId,
      firstBidder: bs.openingPlayerId,
      bids: denseBidsToSparse(bs.bids),
      auctionTop: bs.auctionTop || 0,
      auctionSuit: bs.auctionSuit,
      auctionBidder: bs.auctionBidderId,
      activeBidders: (bs.activeBidders && bs.activeBidders.length) ? bs.activeBidders.slice() : TURN_ORDER.slice(),
      withPlayers: bs.withPlayers.slice(),
      callerId: bs.callerId,
      declaredTrump: bs.declaredTrump,
      lastBidderId: bs.lastBidderId || null,
      fastRound,
      // Only meaningful mid-CONFIRM in a fast round; harmless otherwise.
      noSuitConstraint: fastRound && bs.phase === "CONFIRM",
      logs: [],
      busy: false,
    };
    pushLog("phase", `ROUND ${state.round} · RESUMED · ${state.subPhase}`);
  } else if (fastRound) {
    // Fast rounds (14-18): no Dash Call, no auction — trump is mandatory
    // and fixed, and every player goes straight to a final estimate. A
    // bid of 8+ (Super Call) can still override the forced trump — see
    // the SubmitFinalEstimate/SubmitConfirmCall handlers below.
    const fixedTrump = fixedTrumpFor(round);
    state = {
      round,
      subPhase: "ESTIMATES",
      hands,
      waitingFor: dealer,
      firstBidder: dealer,
      bids: {},
      auctionTop: 13,            // sentinel: no per-player cap in fast rounds
      auctionSuit: fixedTrump,
      auctionBidder: null,
      activeBidders: TURN_ORDER.slice(),
      withPlayers: [],
      callerId: null,
      declaredTrump: fixedTrump,
      lastBidderId: null,
      fastRound: true,
      noSuitConstraint: false,
      logs: [],
      busy: false,
    };
    GameSession.initializeBiddingState({ openingPlayerId: dealer, phase: "ESTIMATES", declaredTrump: fixedTrump, auctionSuit: fixedTrump, auctionTop: 13 });
    pushLog("phase", `ROUND ${round} · FAST ROUND · ${SUITS[fixedTrump].name.toUpperCase()} IS THE FORCED TRUMP`);
    pushLog("", "No auction this round. Declare your final trick estimate directly — a bid of 8 or more is a Super Call and can override the forced trump.");
  } else {
    state = {
      round,
      subPhase: "DASH",          // DASH | AUCTION | CONFIRM | ESTIMATES | DONE
      hands,
      waitingFor: dealer,
      firstBidder: dealer,
      bids: {},                  // id -> {type:'DASHCALL'|'PASS'|'TRICKS'|'DASH', amount}
      auctionTop: 0,
      auctionSuit: null,
      auctionBidder: null,
      activeBidders: TURN_ORDER.slice(),
      withPlayers: [],
      callerId: null,
      declaredTrump: null,
      lastBidderId: null,
      fastRound: false,
      noSuitConstraint: false,
      logs: [],
      busy: false,               // true while an AI is "thinking"
    };
    GameSession.initializeBiddingState({ openingPlayerId: dealer });
    pushLog("phase", `ROUND ${state.round} · DASH-CALL ROUND`);
    pushLog("", "Each player may declare a Dash Call (0 tricks, pre-trump) or decline. Max 2 per round.");
  }
}

function pushLog(kind, text, intentTag) {
  state.logs.push({ kind, text, intentTag });
}

// ── Bid sum / over-under (mirrors GameState.overUnderValue) ──
function bidSum() {
  return Object.values(state.bids).reduce((s, b) => s + (b.type === "TRICKS" ? b.amount : 0), 0);
}

// ════════════════════════════════════════════════════════════════════
//  REDUCER  — reduce(state, intent) ⇒ mutates working copy + logs
//  Intent shapes mirror GameIntent sealed class.
// ════════════════════════════════════════════════════════════════════
function emit(intent) {
  if (state.subPhase === "DONE") return;

  switch (intent.type) {
    // ── SubmitDashCallDecision ──
    case "SubmitDashCallDecision": {
      if (state.subPhase !== "DASH") return;
      if (state.waitingFor !== intent.playerId) return;

      const existing = Object.values(state.bids).filter(b => b.type === "DASHCALL").length;
      const effective = intent.declaredDashCall && existing < MAX_DASH_CALLS;

      if (effective) {
        state.bids[intent.playerId] = { type: "DASHCALL", amount: 0 };
        pushLog("intent", `${nameOf(intent.playerId)} declares DASH CALL — 0 tricks, pre-trump.`, "SubmitDashCallDecision");
      } else if (intent.declaredDashCall && existing >= MAX_DASH_CALLS) {
        state.bids[intent.playerId] = { type: "PASS" };
        pushLog("reject", `${nameOf(intent.playerId)} wanted Dash Call but the 2-player limit is reached. Auto-declined.`, "SubmitDashCallDecision");
      } else {
        state.bids[intent.playerId] = { type: "PASS" };
        pushLog("intent", `${nameOf(intent.playerId)} declines Dash Call.`, "SubmitDashCallDecision");
      }

      const decided = Object.keys(state.bids).length;
      if (decided < PLAYERS.length) {
        state.waitingFor = nextCCW(intent.playerId);
      } else {
        // round complete → auction
        const active = TURN_ORDER.filter(id => state.bids[id]?.type !== "DASHCALL");
        // clear PASS markers (they re-enter the auction); keep DASHCALL
        Object.keys(state.bids).forEach(id => { if (state.bids[id].type === "PASS") delete state.bids[id]; });

        pushLog("phase", "DASH-CALL COMPLETE · CALL AUCTION");
        if (active.length === 0) {
          // all dashed → straight to play (out of scope here — see BiddingState.md § Open Rule Questions)
          state.subPhase = "DONE";
          state.declaredTrump = "SANS";
          pushLog("", "All four players declared Dash Call! SANS is trump — proceed to play.");
        } else {
          state.subPhase = "AUCTION";
          state.activeBidders = active;
          state.auctionTop = 0;
          state.auctionSuit = null;
          state.auctionBidder = null;
          state.waitingFor = active[0];
          pushLog("", "Bid ≥ 4 to compete for the Call. A pass eliminates you for the round. Ties break by suit strength.");
        }
      }

      GameSession.recordBidAction({
        playerId: intent.playerId, actionType: effective ? "DASHCALL" : "PASS",
        bids: sparseBidsToDense(state.bids), activeBidders: state.activeBidders,
        auctionTop: state.auctionTop, auctionSuit: state.auctionSuit, auctionBidderId: state.auctionBidder,
        withPlayers: state.withPlayers, phase: state.subPhase, turnId: state.waitingFor
      });
      if (state.subPhase === "DONE") {
        // All four dashed — no caller exists in this prototype's rules for
        // that edge case (see BiddingState.md § Open Rule Questions).
        // extractEstimates() only carries TRICKS-type bids, so it would
        // hand Game Table an empty {} here — seed an explicit 0 for every
        // seat instead, matching the codebase's existing convention that a
        // 0 estimate scores as a Normal Dash (see table-engine.js).
        const allDashEstimates = {};
        TURN_ORDER.forEach(id => { allDashEstimates[id] = 0; });
        GameSession.completeBidding({
          trump: state.declaredTrump, callerId: state.callerId, withPlayers: state.withPlayers,
          estimates: allDashEstimates, dashCallers: [], riskPlayerId: null, leaderId: GameSession.getDealer()
        });
      }
      break;
    }

    // ── SubmitAuctionBid ──
    case "SubmitAuctionBid": {
      if (state.subPhase !== "AUCTION") return;
      if (state.waitingFor !== intent.playerId) return;

      const pId = intent.playerId;
      let active = [...state.activeBidders];

      if (intent.isPass || intent.tricks == null || intent.tricks < 4 || intent.tricks > 13) {
        active = active.filter(x => x !== pId);
        // Passing only means "I can't/won't raise any further" — it does
        // NOT undo an earlier With match. A player who matched the top
        // bid and is later outbid within the SAME suit stays With even
        // after passing (they just can't out-bid it). With status is
        // only lost when the caller changes suit entirely, which the
        // beatsTop branch below already clears withPlayers for — pass
        // itself must never strip it.
        pushLog("intent", `${nameOf(pId)} passes — eliminated from the auction.`, "SubmitAuctionBid");
      } else {
        const bidVal = intent.tricks;
        const bidSuit = intent.suit;
        const isWith = auctionBidIsWith(pId, bidVal, bidSuit, state.auctionTop, state.auctionSuit, state.auctionBidder);
        const beatsTop = auctionBidBeatsTop(bidVal, bidSuit, state.auctionTop, state.auctionSuit);

        if (isWith) {
          if (!state.withPlayers.includes(pId)) state.withPlayers.push(pId);
          pushLog("intent", `${nameOf(pId)} matches ${bidVal} ${SUITS[bidSuit].name} — goes WITH the Caller (Wazz)!`, "SubmitAuctionBid");
          GameSession.updateWithSelection(state.withPlayers);
        } else if (beatsTop) {
          if (bidSuit !== state.auctionSuit) state.withPlayers = [];
          state.auctionTop = bidVal;
          state.auctionSuit = bidSuit;
          state.auctionBidder = pId;
          pushLog("intent", `${nameOf(pId)} bids ${bidVal} ${SUITS[bidSuit].name}.`, "SubmitAuctionBid");
        } else {
          if (pId !== state.auctionBidder) {
            active = active.filter(x => x !== pId);
            pushLog("reject", `${nameOf(pId)} bid ${bidVal} — not higher than ${state.auctionTop}. Forced to pass.`, "SubmitAuctionBid");
          } else {
            pushLog("", `${nameOf(pId)} holds the top bid.`);
          }
        }
      }

      state.activeBidders = active;

      // exit conditions
      if (active.length === 0 && state.auctionBidder == null) {
        // General pass has no rule-doc formula of its own — doubling is
        // this prototype's own stated intent (see the log line below),
        // reusing the Sa'ayda ladder's ×8 cap so a run of general passes
        // can't escalate without bound.
        const doubled = Math.min((GameSession.getRound().multiplier || 1) * 2, 8);
        GameSession.setRound({ multiplier: doubled });
        pushLog("phase", `GENERAL PASS · REDEAL ×${doubled}`);
        pushLog("", `Nobody bid. Cards are redealt at a doubled multiplier (×${doubled}).`);
        GameSession.recordPassAction({
          playerId: pId, passedPlayers: TURN_ORDER.filter(id => !state.activeBidders.includes(id)),
          activeBidders: state.activeBidders, phase: state.subPhase, turnId: null
        });
        // restart dash round for the prototype — an explicit, intended redeal
        setTimeout(() => restart(), 1400);
        return;
      } else if (active.length === 0 || (active.length === 1 && state.auctionBidder != null)) {
        // auction concluded → normal-round Confirmation Phase
        state.callerId = state.auctionBidder;

        // Auction Alignment (normal rounds, rules §2.2.1a): ANY player who
        // bid the SAME SUIT as the eventual winning Caller at any point
        // during the auction becomes With — even if their trick number
        // differed, and regardless of whether the winning bid was itself
        // a Super Call. This previously only ran when the winning bid was
        // 8+ tricks, silently dropping legitimate suit-aligned players in
        // every ordinary (<8) auction. This is separate from (and in
        // addition to) the live exact-match check above, which only
        // catches an exact number+suit match to the CURRENT top bid as it
        // happens. actionHistory reliably has every intermediate bid's
        // suit (each non-concluding bid/pass is recorded via
        // recordBidAction below before the auction ends).
        {
          const history = GameSession.getBiddingState().actionHistory || [];
          const suitMatchers = [...new Set(
            history.filter(a => a.actionType === "BID" && a.suit === state.auctionSuit && a.playerId !== state.callerId)
                   .map(a => a.playerId)
          )];
          const newlyWith = suitMatchers.filter(id => !state.withPlayers.includes(id));
          if (newlyWith.length) {
            state.withPlayers.push(...newlyWith);
            pushLog("intent", `${newlyWith.map(nameOf).join(", ")} bid ${SUITS[state.auctionSuit].name} earlier — granted WITH status by Auction Alignment (Wazz)!`, "SubmitAuctionBid");
            GameSession.updateWithSelection(state.withPlayers);
          }
        }

        pushLog("phase", "AUCTION WON · CONFIRMATION");
        pushLog("intent", `${nameOf(state.callerId)} wins the Call with ${state.auctionTop} ${SUITS[state.auctionSuit].name}.`, "DeclareTrump");
        state.subPhase = "CONFIRM";
        state.waitingFor = state.callerId;
        ui.confirmValue = state.auctionTop;
        ui.confirmSuit = state.auctionSuit;
        pushLog("", `${nameOf(state.callerId)} may keep the call, raise the number, or (at the same number) switch to an equal-or-stronger suit.`);
        GameSession.setAuctionWinner({
          callerId: state.callerId, auctionTop: state.auctionTop, auctionSuit: state.auctionSuit,
          phase: state.subPhase, turnId: state.waitingFor
        });
      } else {
        // continue: next active bidder CCW
        let cand = nextCCW(pId);
        let guard = 0;
        while (!active.includes(cand) && guard < 8) { cand = nextCCW(cand); guard++; }
        state.waitingFor = cand;
        GameSession.recordBidAction({
          playerId: pId, actionType: intent.isPass ? "PASS" : "BID", value: intent.tricks, suit: intent.suit,
          bids: sparseBidsToDense(state.bids), activeBidders: state.activeBidders,
          auctionTop: state.auctionTop, auctionSuit: state.auctionSuit, auctionBidderId: state.auctionBidder,
          withPlayers: state.withPlayers, phase: state.subPhase, turnId: state.waitingFor
        });
      }
      break;
    }

    // ── SubmitConfirmCall (Caller keeps / raises / switches suit) ──
    case "SubmitConfirmCall": {
      if (state.subPhase !== "CONFIRM") return;
      if (state.waitingFor !== intent.playerId) return;
      const t = intent.tricks, s = intent.suit;
      if (bidBelowWinningCall(t, state.auctionTop)) {
        pushLog("reject", `REJECTED: ${t} is below the winning call of ${state.auctionTop}.`, "SubmitConfirmCall");
        return { rejected: true, reason: "Can't lower your winning call" };
      }
      // A fast-round Super Call isn't outbidding anyone — it overrides the
      // forced trump outright, so the "equal-or-stronger suit" rule (which
      // only makes sense relative to a competing auction bid) doesn't apply.
      if (confirmSuitTooWeak(t, s, state.auctionTop, state.auctionSuit, state.noSuitConstraint)) {
        pushLog("reject", `REJECTED: at the same number, the suit must be equal or stronger than ${SUITS[state.auctionSuit].name}.`, "SubmitConfirmCall");
        return { rejected: true, reason: "Same number needs an equal or stronger suit" };
      }
      state.auctionTop = t;
      state.auctionSuit = s;
      state.declaredTrump = s;
      state.bids[intent.playerId] = { type: "TRICKS", amount: t };

      if (state.noSuitConstraint) {
        // Fast-round Super Call: every seat already submitted a final
        // estimate before the Super Call was detected (that's how it was
        // detected) — go straight to trick play instead of re-opening
        // Final Estimates. Mirrors kotlinCode.ts's DeclareTrump handler,
        // where firstEstimator is null once updatedBids already covers
        // every player.
        pushLog("intent", `${nameOf(intent.playerId)} locks ${t} ${SUITS[s].name} as trump (Super Call).`, "DeclareTrump");
        pushLog("phase", "SUPER CALL CONFIRMED · TRICK-TAKING");
        state.subPhase = "DONE";
        GameSession.completeBidding({
          trump: s, callerId: intent.playerId, withPlayers: [],
          estimates: extractEstimates(state.bids), dashCallers: [],   // fast rounds never have a Dash phase
          riskPlayerId: state.lastBidderId, leaderId: intent.playerId
        });
      } else {
        pushLog("intent", `${nameOf(intent.playerId)} locks ${t} ${SUITS[s].name} as trump.`, "DeclareTrump");
        state.subPhase = "ESTIMATES";
        // A Dash Caller already has a bid on record (from the DASH phase)
        // and never re-enters estimation — skip straight to the first
        // seat that doesn't have one yet, mirroring the same skip-logic
        // SubmitFinalEstimate itself already uses mid-phase. Without this,
        // the first Dash Caller CCW from the new Caller would be asked to
        // estimate again, and their submission would silently overwrite
        // (corrupt) their DASHCALL bid record.
        let firstEstimator = nextCCW(intent.playerId);
        let estGuard = 0;
        while (state.bids[firstEstimator] && estGuard < 8) { firstEstimator = nextCCW(firstEstimator); estGuard++; }
        state.waitingFor = firstEstimator;
        pushLog("phase", "FINAL ESTIMATES");
        pushLog("", `Everyone declares a final trick count ≤ ${t} (Caller's cap). The four bids may not total exactly 13.`);
        // Sprint J.9 (BID_TO_TURN_HANDOFF fix): `callerId` is ALREADY
        // known here — `intent.playerId` IS the confirming Caller — but
        // until now nothing propagated it into `GameSession.round`
        // (only into `biddingState`, via the earlier setAuctionWinner()
        // call, which `MatchAdapter.computeRoundStartLeaderUid()` never
        // reads). `session.round.callerId` stayed null until
        // `completeBidding()` ran — which only happens via this
        // client's own replay of its OWN just-accepted bidding action,
        // AFTER the later round-completing submitBid() transaction had
        // already run and fallen back to a stale GameSession.getTurn()
        // value (see Sprint J.8's forensic report for the full traced
        // race). Passing `callerId` through here makes
        // `GameSession.getRound().callerId` correct immediately —
        // exactly the same value `completeBidding()` will later confirm
        // — not a new or different one.
        GameSession.recordBidAction({
          playerId: intent.playerId, actionType: "CONFIRM_TRUMP", value: t, suit: s,
          bids: sparseBidsToDense(state.bids), activeBidders: state.activeBidders,
          auctionTop: state.auctionTop, auctionSuit: state.auctionSuit, auctionBidderId: state.auctionBidder,
          withPlayers: state.withPlayers, phase: state.subPhase, turnId: state.waitingFor,
          callerId: intent.playerId
        });
        GameSession.updateBiddingState({ declaredTrump: state.declaredTrump });
      }
      break;
    }

    // ── SubmitFinalEstimate ──
    case "SubmitFinalEstimate": {
      if (state.subPhase !== "ESTIMATES") return;
      if (state.waitingFor !== intent.playerId) return;

      const pId = intent.playerId;
      const cap = state.auctionTop;

      // R2 — caller bid cap
      if (estimateExceedsCap(intent.tricks, cap)) {
        pushLog("reject", `REJECTED: ${nameOf(pId)} tried ${intent.tricks} — exceeds Caller's cap of ${cap}.`, "SubmitFinalEstimate");
        return { rejected: true, reason: `Max is ${cap} (Caller's cap)` };
      }

      // R2b — a With (Wazz) player can't drop below the last number they
      // actually bid in the auction (their own floor); above that and up
      // to the Caller's cap (R2, just above) is fair game.
      if (estimateBelowWithFloor(pId, intent.tricks, state.withPlayers)) {
        const floor = withFloorFor(pId);
        pushLog("reject", `REJECTED: ${nameOf(pId)} tried ${intent.tricks} — With can't drop below their own ${floor}.`, "SubmitFinalEstimate");
        return { rejected: true, reason: `Min is ${floor} (your own With bid)` };
      }

      // R1 — forbidden 13 for the last estimator
      if (estimateIsForbidden13(pId, intent.tricks, state.bids)) {
        const forbidden = forbiddenEstimateFor(pId, state.bids);
        pushLog("reject", `REJECTED: ${nameOf(pId)} tried ${intent.tricks} — bids cannot total 13 (forbidden ${forbidden}).`, "SubmitFinalEstimate");
        return { rejected: true, reason: `Can't pick ${forbidden} — totals 13` };
      }

      state.bids[pId] = intent.tricks === 0 ? { type: "DASH", amount: 0 } : { type: "TRICKS", amount: intent.tricks };
      pushLog("intent", `${nameOf(pId)} estimates ${intent.tricks} trick${intent.tricks === 1 ? "" : "s"}.`, "SubmitFinalEstimate");

      // Estimation Jump-In (rules §2.2.1a): open to ANYONE, including a
      // player who passed the auction entirely — if their final estimate
      // exactly matches the Caller's locked number, they become With. The
      // "same suit" half of the rule is automatically satisfied here since
      // every estimator in a normal round is estimating under the single
      // trump the Caller already locked (state.auctionTop/declaredTrump) —
      // there is no separate suit choice at this stage. This was previously
      // unimplemented; only the live-auction exact-match and Auction
      // Alignment paths granted With before now.
      if (!state.fastRound && pId !== state.callerId && intent.tricks === state.auctionTop && !state.withPlayers.includes(pId)) {
        state.withPlayers.push(pId);
        pushLog("intent", `${nameOf(pId)} matches the Caller's ${intent.tricks} exactly — granted WITH status (Estimation Jump-In)!`, "SubmitFinalEstimate");
        GameSession.updateWithSelection(state.withPlayers);
      }

      const resolved = TURN_ORDER.filter(id => state.bids[id]).length;
      if (resolved < PLAYERS.length) {
        let cand = nextCCW(pId);
        let guard = 0;
        while (state.bids[cand] && guard < 8) { cand = nextCCW(cand); guard++; }
        state.waitingFor = cand;
        GameSession.recordEstimate({
          playerId: pId, tricks: intent.tricks, bids: sparseBidsToDense(state.bids),
          estimates: extractEstimates(state.bids), phase: state.subPhase, turnId: state.waitingFor
        });
      } else {
        // all in — this submission is, by construction, the last one made.
        state.lastBidderId = pId;
        const sum = bidSum();
        const diff = sum - 13;

        if (state.fastRound) {
          // Check for a Super Call (any final estimate of 8+). Ties break
          // toward the earliest bidder in THIS ROUND'S actual bidding order
          // (starting from state.firstBidder — the dealer — not the static
          // p1..p4 TURN_ORDER, which only matches the real sequence when
          // the dealer happens to be p1). Matches uploads/kotlinCode.ts's
          // tie-break ("first player to bid that number becomes the Caller").
          const biddingOrder = [];
          { let seat = state.firstBidder; for (let k = 0; k < PLAYERS.length; k++) { biddingOrder.push(seat); seat = nextCCW(seat); } }
          const superCandidates = TURN_ORDER
            .filter(id => state.bids[id].type === "TRICKS" && state.bids[id].amount >= 8)
            .sort((a, b) => (state.bids[b].amount - state.bids[a].amount) || (biddingOrder.indexOf(a) - biddingOrder.indexOf(b)));
          const superCallerId = superCandidates[0] || null;

          if (superCallerId) {
            const superBid = state.bids[superCallerId].amount;
            pushLog("phase", "ESTIMATION COMPLETE · SUPER CALL");
            pushLog("intent", `Total bids: ${sum} (${diff > 0 ? "OVER +" + diff : "UNDER " + diff}).`, "TrickTaking");
            pushLog("intent", `⚡ SUPER CALL! ${nameOf(superCallerId)} (${superBid} tricks) may override the forced trump.`, "DeclareTrump");
            state.callerId = superCallerId;
            state.auctionTop = superBid;
            state.subPhase = "CONFIRM";
            state.noSuitConstraint = true;
            state.waitingFor = superCallerId;
            GameSession.setAuctionWinner({
              callerId: superCallerId, auctionTop: superBid, auctionSuit: state.auctionSuit,
              phase: state.subPhase, turnId: state.waitingFor
            });
            GameSession.updateBiddingState({ lastBidderId: state.lastBidderId });
          } else {
            // BUG FIX (Sprint 4.0, Task B — "Fast-Round Caller" bug):
            // this branch previously completed bidding with
            // `callerId: null, withPlayers: []` unconditionally whenever
            // no Super Call (8+) occurred — but the rules doc (§3, "Caller
            // / With: the first player to bid the highest number is the
            // Caller; every other player who bid that same number becomes
            // 'With'") applies to EVERY fast round, not only Super Call
            // ones. Fixed to always resolve a real Caller/With from the
            // highest bid, using the SAME bidding-order tie-break already
            // established for the Super Call path above (first-to-bid
            // wins ties) — the Super Call branch above is now just the
            // special case where that highest bid happens to be 8+.
            const highestAmount = TURN_ORDER.reduce((max, id) =>
              state.bids[id].type === "TRICKS" ? Math.max(max, state.bids[id].amount) : max, 0);
            const highestCandidates = TURN_ORDER
              .filter(id => state.bids[id].type === "TRICKS" && state.bids[id].amount === highestAmount)
              .sort((a, b) => biddingOrder.indexOf(a) - biddingOrder.indexOf(b));
            const fastCallerId = highestCandidates[0] || null;
            const fastWithPlayers = highestCandidates.slice(1);

            pushLog("phase", "ESTIMATION COMPLETE");
            pushLog("intent", `Total bids: ${sum} (${diff > 0 ? "OVER +" + diff : "UNDER " + diff}). Forced trump stands — no Super Call.`, "TrickTaking");
            if (fastCallerId) {
              pushLog("intent", `${nameOf(fastCallerId)} (${highestAmount} tricks) is the Caller for this fast round.` +
                (fastWithPlayers.length ? ` With: ${fastWithPlayers.map(nameOf).join(", ")}.` : ""), "DeclareTrump");
            }
            state.subPhase = "DONE";
            GameSession.completeBidding({
              trump: state.declaredTrump, callerId: fastCallerId, withPlayers: fastWithPlayers,
              estimates: extractEstimates(state.bids), dashCallers: [],   // fast rounds never have a Dash phase
              riskPlayerId: state.lastBidderId, leaderId: fastCallerId != null ? fastCallerId : state.firstBidder
            });
          }
        } else {
          pushLog("phase", "ESTIMATION COMPLETE");
          pushLog("intent", `Total bids: ${sum} (${diff > 0 ? "OVER +" + diff : "UNDER " + diff}). Caller must make their bid; the table fights over the rest.`, "TrickTaking");
          state.subPhase = "DONE";
          GameSession.completeBidding({
            trump: state.declaredTrump, callerId: state.callerId, withPlayers: state.withPlayers,
            estimates: extractEstimates(state.bids), dashCallers: dashCallerIds(),
            riskPlayerId: state.lastBidderId != null ? state.lastBidderId : computeRiskId(state.callerId),
            leaderId: state.callerId
          });
        }
      }
      break;
    }
  }
  return { rejected: false };
}

function nameOf(id) { return PLAYERS.find(p => p.id === id).name; }

// ════════════════════════════════════════════════════════════════════
//  Sprint 3.6.1 (Bidding Engine Contract): canSubmit(intent) — the
//  minimum additive, PURE, read-only legality API a future Bidding UI
//  needs to ask "is this action legal right now?" without mutating
//  state and without calling emit() merely to probe it. Every branch
//  below calls the SAME predicate functions defined above emit() (or
//  re-checks the SAME phase/turn conditions emit()'s own switch cases
//  check first) — this function is a read-only PROJECTION of emit()'s
//  existing legality logic, never a second, independently-derived
//  copy of it. It never calls pushLog(), never mutates `state`, never
//  calls any GameSession setter, and never calls emit() itself.
//
//  Returns `{legal: true}` or `{legal: false, reason: "..."}` for
//  every one of the four real intent shapes emit() accepts
//  (SubmitDashCallDecision / SubmitAuctionBid / SubmitConfirmCall /
//  SubmitFinalEstimate); `{legal: false, reason: "Unknown intent type"}`
//  for anything else, and defensive `{legal: false, ...}` answers if
//  called before initState() or after bidding has already completed
//  (state.subPhase === "DONE") — neither of those is a "rule," they are
//  the same guard emit() itself has at the top of its own switch
//  (`if (state.subPhase === "DONE") return;`), reused here rather than
//  reinterpreted.
//
//  Deliberately NOT implemented for the two intents whose only
//  "legality" condition IS the phase/turn check (SubmitDashCallDecision
//  always processes — an over-the-limit Dash Call auto-converts to a
//  PASS inside emit(), it is never rejected — and SubmitAuctionBid's
//  own "does this bid beat the top" outcome is likewise never an
//  explicit `{rejected}` return in emit(), only a reinterpretation into
//  a forced pass). For these two, canSubmit() answers the SAME
//  question a UI actually needs ("would submitting this be accepted as
//  a real action, or silently reinterpreted into something else") using
//  the identical extracted predicates emit() itself now calls (see
//  auctionBidBeatsTop()/auctionBidIsWith() above) — not a new rule.
// ════════════════════════════════════════════════════════════════════
// Sprint 3.7.x (Bidding Trust-Boundary Hardening): STRUCTURAL presence
// validation for each intent type canSubmit() accepts — "does this
// intent even carry the fields its own type requires," never a
// gameplay-legality decision. Added because the content-rule
// predicates canSubmit() already calls (bidBelowWinningCall(),
// confirmSuitTooWeak(), estimateExceedsCap(), etc.) all short-circuit
// to `false`/no-op on an `undefined` field via ordinary JS comparison
// semantics (`undefined < x` is `false`; `a === b` with one side
// `undefined` is `false` unless BOTH are `undefined`) — meaning a
// malformed intent missing a required field was previously classified
// `{legal:true}` by canSubmit(), only to then crash emit() (e.g.
// `SUITS[undefined].name`) once actually applied. Fixing this at the
// SOURCE (here, the one place every caller already asks "is this
// legal") is the same "fix root cause, not downstream" convention
// this project used for the Sprint 3.6.1 Normal Dash Scoring Hotfix.
// Required fields are taken directly from the EXISTING intent shapes
// emit() already reads a few lines below in this same switch — no new
// schema invented, no gameplay rule touched, no legal/illegal outcome
// changed for any well-formed intent.
//
// Deliberately checked AFTER each case's own phase/turn guard, not
// before the switch — an intent for the wrong phase/turn must still be
// reported as "Not the X phase"/"Not this seat's turn" even if it also
// happens to be missing a field, exactly matching this file's own
// pre-existing, already-tested precedence (confirmed against
// tests/bidding-contract.test.cjs's own "DASH: an AUCTION-phase intent
// is illegal — wrong phase" case, which submits a SubmitAuctionBid
// intent with no `isPass` field at all and expects the PHASE reason,
// not a malformed-intent one).
function isMalformedBiddingIntent(intent) {
  switch (intent.type) {
    case "SubmitDashCallDecision":
      return typeof intent.declaredDashCall !== "boolean";
    case "SubmitAuctionBid":
      if (typeof intent.isPass !== "boolean") return true;
      if (intent.isPass) return false; // a pass carries no tricks/suit — nothing further required
      return typeof intent.tricks !== "number" || !Number.isFinite(intent.tricks) ||
        typeof intent.suit !== "string" || !SUITS[intent.suit];
    case "SubmitConfirmCall":
      return typeof intent.tricks !== "number" || !Number.isFinite(intent.tricks) ||
        typeof intent.suit !== "string" || !SUITS[intent.suit];
    case "SubmitFinalEstimate":
      return typeof intent.tricks !== "number" || !Number.isFinite(intent.tricks);
    default:
      return false; // an unrecognized type is handled by the switch's own default case, not here
  }
}

function canSubmit(intent) {
  if (!state) return { legal: false, reason: "Bidding has not been initialized yet" };
  if (!intent || typeof intent !== "object" || !intent.type) return { legal: false, reason: "Malformed intent" };
  if (state.subPhase === "DONE") return { legal: false, reason: "Bidding is already complete" };

  const pId = intent.playerId;

  switch (intent.type) {
    case "SubmitDashCallDecision": {
      if (state.subPhase !== "DASH") return { legal: false, reason: "Not the Dash-Call phase" };
      if (state.waitingFor !== pId) return { legal: false, reason: "Not this seat's turn" };
      if (isMalformedBiddingIntent(intent)) return { legal: false, reason: "Malformed intent" };
      return { legal: true };
    }

    case "SubmitAuctionBid": {
      if (state.subPhase !== "AUCTION") return { legal: false, reason: "Not the Auction phase" };
      if (state.waitingFor !== pId) return { legal: false, reason: "Not this seat's turn" };
      if (isMalformedBiddingIntent(intent)) return { legal: false, reason: "Malformed intent" };
      if (intent.isPass) return { legal: true };
      if (intent.tricks == null || intent.tricks < 4 || intent.tricks > 13) {
        return { legal: false, reason: "Bid must be between 4 and 13 tricks" };
      }
      const isWith = auctionBidIsWith(pId, intent.tricks, intent.suit, state.auctionTop, state.auctionSuit, state.auctionBidder);
      const beatsTop = auctionBidBeatsTop(intent.tricks, intent.suit, state.auctionTop, state.auctionSuit);
      if (isWith || beatsTop) return { legal: true };
      return {
        legal: false,
        reason: `Bid does not beat the current top bid of ${state.auctionTop}` +
          (state.auctionSuit ? ` ${SUITS[state.auctionSuit].name}` : "")
      };
    }

    case "SubmitConfirmCall": {
      if (state.subPhase !== "CONFIRM") return { legal: false, reason: "Not the Confirmation phase" };
      if (state.waitingFor !== pId) return { legal: false, reason: "Not this seat's turn" };
      if (isMalformedBiddingIntent(intent)) return { legal: false, reason: "Malformed intent" };
      if (bidBelowWinningCall(intent.tricks, state.auctionTop)) {
        return { legal: false, reason: "Can't lower your winning call" };
      }
      if (confirmSuitTooWeak(intent.tricks, intent.suit, state.auctionTop, state.auctionSuit, state.noSuitConstraint)) {
        return { legal: false, reason: "Same number needs an equal or stronger suit" };
      }
      return { legal: true };
    }

    case "SubmitFinalEstimate": {
      if (state.subPhase !== "ESTIMATES") return { legal: false, reason: "Not the Final Estimates phase" };
      if (state.waitingFor !== pId) return { legal: false, reason: "Not this seat's turn" };
      if (isMalformedBiddingIntent(intent)) return { legal: false, reason: "Malformed intent" };
      if (estimateExceedsCap(intent.tricks, state.auctionTop)) {
        return { legal: false, reason: `Max is ${state.auctionTop} (Caller's cap)` };
      }
      if (estimateBelowWithFloor(pId, intent.tricks, state.withPlayers)) {
        const floor = withFloorFor(pId);
        return { legal: false, reason: `Min is ${floor} (your own With bid)` };
      }
      if (estimateIsForbidden13(pId, intent.tricks, state.bids)) {
        const forbidden = forbiddenEstimateFor(pId, state.bids);
        return { legal: false, reason: `Can't pick ${forbidden} — totals 13` };
      }
      return { legal: true };
    }

    default:
      return { legal: false, reason: "Unknown intent type" };
  }
}

// Pre-bidding Dash Callers never re-enter estimation, so extractEstimates()
// (TRICKS-only) silently drops them — without this, a real (reachable, up
// to 2 per round) Dash Call bidder's type is lost by the time Game Table/
// scoring reconstructs bids, and they'd score as an undefined-amount TRICKS
// bid instead of a DASH_CALL. See BiddingState.md / ScoringEngine.md.
function dashCallerIds() {
  return TURN_ORDER.filter(id => state.bids[id] && state.bids[id].type === "DASHCALL");
}

// A With (Wazz) player's Final Estimate isn't free-choice like a regular
// estimator's — it's bounded below by the last real number THEY personally
// bid (whether their original match or a later re-match at a higher number,
// whichever happened most recently) and bounded above by the Caller's final
// locked number (already enforced separately by the existing cap check).
// Every auction bid that doesn't conclude the auction is persisted via
// recordBidAction (see the "continue" branch above), so the most recent one
// for this player is their current floor.
function withFloorFor(pId) {
  const history = GameSession.getBiddingState().actionHistory || [];
  const mine = history.filter(a => a.actionType === "BID" && a.playerId === pId);
  return mine.length ? mine[mine.length - 1].value : null;
}

// ════════════════════════════════════════════════════════════════════
//  AI — lightweight heuristics so the table feels alive
// ════════════════════════════════════════════════════════════════════
function handStrength(id) {
  // rough trick-taking potential: high cards + length
  const h = state.hands[id];
  let pts = 0;
  for (const c of h) {
    if (c.rank.v === 14) pts += 1.0;
    else if (c.rank.v === 13) pts += 0.7;
    else if (c.rank.v === 12) pts += 0.4;
    else if (c.rank.v >= 10) pts += 0.2;
  }
  // length bonus per suit
  const bySuit = {};
  for (const c of h) bySuit[c.suit] = (bySuit[c.suit] || 0) + 1;
  for (const k in bySuit) if (bySuit[k] >= 5) pts += (bySuit[k] - 4) * 0.5;
  return pts;
}
function aiBestSuit(id) {
  const bySuit = {};
  for (const c of state.hands[id]) bySuit[c.suit] = (bySuit[c.suit] || 0) + 1;
  let best = "SPADES", n = -1;
  for (const k of ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"]) {
    if ((bySuit[k] || 0) > n) { n = bySuit[k] || 0; best = k; }
  }
  return best;
}

function aiAct(id) {
  const est = handStrength(id);
  if (state.subPhase === "DASH") {
    // declare dash only with a very weak hand & slot available
    const existing = Object.values(state.bids).filter(b => b.type === "DASHCALL").length;
    const wantsDash = est < 1.2 && existing < MAX_DASH_CALLS && Math.random() < 0.6;
    emit({ type: "SubmitDashCallDecision", playerId: id, declaredDashCall: wantsDash });
  } else if (state.subPhase === "AUCTION") {
    const target = Math.min(13, Math.max(4, Math.round(4 + est * 0.9)));
    const suit = aiBestSuit(id);
    const minToBeat = state.auctionTop === 0 ? 4 :
      (SUITS[suit].strength > (SUITS[state.auctionSuit]?.strength || 0) ? state.auctionTop : state.auctionTop + 1);
    if (target >= minToBeat && target >= 4 && Math.random() < 0.85) {
      emit({ type: "SubmitAuctionBid", playerId: id, tricks: target, suit, isPass: false });
    } else if (state.auctionTop > 0 && target === state.auctionTop && suit === state.auctionSuit && Math.random() < 0.5) {
      emit({ type: "SubmitAuctionBid", playerId: id, tricks: target, suit, isPass: false }); // becomes With
    } else {
      emit({ type: "SubmitAuctionBid", playerId: id, isPass: true });
    }
  } else if (state.subPhase === "CONFIRM") {
    // AI callers just keep their winning call
    emit({ type: "SubmitConfirmCall", playerId: id, tricks: state.auctionTop, suit: state.auctionSuit });
  } else if (state.subPhase === "ESTIMATES") {
    const cap = state.auctionTop;
    let target = Math.min(cap, Math.max(0, Math.round(est * 0.85)));
    // avoid forbidden-13 if last
    const others = TURN_ORDER.filter(x => x !== id && state.bids[x]);
    if (others.length === PLAYERS.length - 1) {
      const otherSum = others.reduce((s, x) => s + (state.bids[x].type === "TRICKS" ? state.bids[x].amount : 0), 0);
      const forbidden = 13 - otherSum;
      if (target === forbidden) target = Math.max(0, target === cap ? target - 1 : target + 1);
      if (target === forbidden) target = forbidden === 0 ? 1 : forbidden - 1;
    }
    emit({ type: "SubmitFinalEstimate", playerId: id, tricks: Math.max(0, Math.min(cap, target)) });
  }
}

// ════════════════════════════════════════════════════════════════════
//  TURN LOOP
// ════════════════════════════════════════════════════════════════════
function advance() {
  render();
  if (state.subPhase === "DONE") { showDone(); return; }
  const wf = state.waitingFor;
  const player = PLAYERS.find(p => p.id === wf);
  if (player && !player.isUser) {
    state.busy = true;
    render();
    const delay = 750 + Math.random() * 650;
    setTimeout(() => {
      state.busy = false;
      aiAct(wf);
      advance();
    }, delay);
  }
}

// ── local UI selection for the user's controls ──
let ui = { bidValue: 4, bidSuit: "SPADES", estValue: 0, confirmValue: 4, confirmSuit: "SPADES" };

function restart() {
  GameSession.clearBiddingState();
  GameSession.ensureHandsDealt({ force: true });
  initState();
  ui = { bidValue: 4, bidSuit: "SPADES", estValue: 0, confirmValue: 4, confirmSuit: "SPADES" };
  document.getElementById("doneOverlay").classList.remove("show");
  advance();
}

// kick off
window.addEventListener("DOMContentLoaded", () => {
  GameState.sync(GameState.STATES.BIDDING);
  initState();
  buildHand();
  bindStatic();
  advance();
});

// Sprint 3.6 (Match Flow Integration): the minimum export required to
// make this file's reducer callable from outside a browser page —
// nothing above this line was touched. This file has no module
// wrapper and no export of any kind through Sprint 3.5; as delivered,
// every function above (including `state` itself) is unreachable once
// the file finishes loading, which made it impossible to drive from an
// automated test at all. `render`/`buildHand`/`bindStatic`/`showDone`
// remain undefined here exactly as before — real browser usage is
// unaffected (the DOMContentLoaded handler above still defines and
// calls them exactly as it always has, once loaded on a real page
// alongside its paired HTML screen's own inline script). Integration
// tests instead call `initState()`/`emit()` directly and never trigger
// `advance()`, so those UI-side functions are simply never invoked.
// See docs/reviews/MatchFlowIntegration_3.6.md for the full account of
// why this was the smallest viable change, and the discovered (not
// fixed) architectural notes this sprint did not attempt to resolve.
window.BiddingEngine = {
  initState: initState,
  emit: emit,
  getState: function () { return state; },
  canSubmit: canSubmit,
  // Match Completion sprint: exposed READ-ONLY so ScoringEngine/
  // MatchAdapter can determine "what was Rapid Round N's MANDATORY
  // trump" (needed for Super Call round-extension eligibility — rules
  // §5) without re-deriving or duplicating the FIXED_SUITS cycle
  // defined once, here. Pure functions — no state read/written.
  isFastRound: isFastRound,
  fixedTrumpFor: fixedTrumpFor
};

})(window);
