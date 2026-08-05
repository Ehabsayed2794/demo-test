/* ════════════════════════════════════════════════════════════════════
   Estimation — Bidding Phase Engine
   A faithful, browser-side mirror of GameReducer.kt's bidding logic.
   Pattern: UI emits INTENTS → reduce(state, intent) → new STATE → render().
   This is a DESIGN PROTOTYPE — same shapes & rules as the Kotlin model,
   so a BiddingControls composable maps onto it 1:1.
   ════════════════════════════════════════════════════════════════════ */

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
// Final committed estimates only include TRICKS-type bids (pre-existing
// behavior, unchanged — see BiddingState.md § Open Rule Questions for
// the Dash-Call-as-final-estimate edge case this preserves as-is).
function extractEstimates(sparseBids) {
  const out = {};
  Object.keys(sparseBids).forEach(id => { if (sparseBids[id].type === "TRICKS") out[id] = sparseBids[id].amount; });
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
        const isWith = bidVal === state.auctionTop && bidSuit === state.auctionSuit &&
                       pId !== state.auctionBidder && state.auctionBidder != null;
        const beatsTop = bidVal > state.auctionTop ||
          (bidVal === state.auctionTop && SUITS[bidSuit]?.strength > (SUITS[state.auctionSuit]?.strength || 0));

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
      if (t < state.auctionTop) {
        pushLog("reject", `REJECTED: ${t} is below the winning call of ${state.auctionTop}.`, "SubmitConfirmCall");
        return { rejected: true, reason: "Can't lower your winning call" };
      }
      // A fast-round Super Call isn't outbidding anyone — it overrides the
      // forced trump outright, so the "equal-or-stronger suit" rule (which
      // only makes sense relative to a competing auction bid) doesn't apply.
      if (!state.noSuitConstraint && t === state.auctionTop && SUITS[s].strength < SUITS[state.auctionSuit].strength) {
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
        GameSession.recordBidAction({
          playerId: intent.playerId, actionType: "CONFIRM_TRUMP", value: t, suit: s,
          bids: sparseBidsToDense(state.bids), activeBidders: state.activeBidders,
          auctionTop: state.auctionTop, auctionSuit: state.auctionSuit, auctionBidderId: state.auctionBidder,
          withPlayers: state.withPlayers, phase: state.subPhase, turnId: state.waitingFor
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
      if (intent.tricks > cap) {
        pushLog("reject", `REJECTED: ${nameOf(pId)} tried ${intent.tricks} — exceeds Caller's cap of ${cap}.`, "SubmitFinalEstimate");
        return { rejected: true, reason: `Max is ${cap} (Caller's cap)` };
      }

      // R2b — a With (Wazz) player can't drop below the last number they
      // actually bid in the auction (their own floor); above that and up
      // to the Caller's cap (R2, just above) is fair game.
      if (state.withPlayers.includes(pId)) {
        const floor = withFloorFor(pId);
        if (floor != null && intent.tricks < floor) {
          pushLog("reject", `REJECTED: ${nameOf(pId)} tried ${intent.tricks} — With can't drop below their own ${floor}.`, "SubmitFinalEstimate");
          return { rejected: true, reason: `Min is ${floor} (your own With bid)` };
        }
      }

      // R1 — forbidden 13 for the last estimator
      const others = TURN_ORDER.filter(id => id !== pId && state.bids[id]);
      const isLast = others.length === PLAYERS.length - 1;
      if (isLast) {
        const otherSum = others.reduce((s, id) => s + (state.bids[id].type === "TRICKS" ? state.bids[id].amount : 0), 0);
        const forbidden = 13 - otherSum;
        if (intent.tricks === forbidden) {
          pushLog("reject", `REJECTED: ${nameOf(pId)} tried ${intent.tricks} — bids cannot total 13 (forbidden ${forbidden}).`, "SubmitFinalEstimate");
          return { rejected: true, reason: `Can't pick ${forbidden} — totals 13` };
        }
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
            pushLog("phase", "ESTIMATION COMPLETE");
            pushLog("intent", `Total bids: ${sum} (${diff > 0 ? "OVER +" + diff : "UNDER " + diff}). Forced trump stands — no Super Call.`, "TrickTaking");
            state.subPhase = "DONE";
            GameSession.completeBidding({
              trump: state.declaredTrump, callerId: null, withPlayers: [],
              estimates: extractEstimates(state.bids), dashCallers: [],   // fast rounds never have a Dash phase
              riskPlayerId: state.lastBidderId, leaderId: state.firstBidder
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
  getState: function () { return state; }
};
