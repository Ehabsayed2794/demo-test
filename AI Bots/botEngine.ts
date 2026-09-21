// ============================================================================
//  botEngine.ts  —  Estemshan Intelligent Bot System
//  PHASE 1 / FOUNDATION:  Module A (Hand Evaluator) + 4-Tier Difficulty Core
// ----------------------------------------------------------------------------
//  This file is self-contained and additive. It does NOT replace your existing
//  utils.ts yet — App.tsx keeps working. You wire the new brain in gradually
//  (see WIRING NOTES at the bottom).
//
//  What this delivers right now:
//    1. A real probabilistic trick estimator (high-card tricks + trump length
//       tricks + ruffing value for short side suits) — far stronger than the
//       old A=1 / K=0.5 heuristic.
//    2. A 4-tier difficulty system (EASY / MEDIUM / HARD / EXPERT) that ACTUALLY
//       changes behaviour, via a single tuning table.
//    3. Correct, frequent Dash-Call detection.
//    4. Smart trump selection that agrees with the evaluation.
// ============================================================================

import { CardModel, Suit, Rank } from './types';

// ----------------------------------------------------------------------------
//  0. DIFFICULTY TIERS
// ----------------------------------------------------------------------------
//  We expand from 3 tiers to 4. Add 'HARD' to your DifficultyLevel type:
//    export type DifficultyLevel = 'EASY' | 'MEDIUM' | 'HARD' | 'EXPERT';
//  (EASY/MEDIUM/EXPERT are preserved so nothing else breaks.)

export type BotTier = 'EASY' | 'MEDIUM' | 'HARD' | 'EXPERT';

export interface TierConfig {
  /** Fraction of decisions deliberately made sub-optimal, so weak bots feel
   * human/beatable rather than just dumb. 0 = always optimal. */
  mistakeRate: number;
  /** How much length/ruffing value the bot "believes in" when bidding.
   * Low tiers under-count distribution (like a beginner), high tiers count it
   * fully. 0..1 multiplier on the distributional part of the estimate. */
  distributionConfidence: number;
  /** Random jitter (in tricks) added to the bid estimate to model uncertainty.
   * Weak bots are noisier. */
  bidNoise: number;
  /** Whether this tier is allowed to recognise & declare a Dash Call. */
  canDash: boolean;
  /** Whether this tier counts cards / tracks played cards during play
   * (consumed by Module C in a later phase). */
  countsCards: boolean;
  /** Whether this tier models opponents (consumed by Module D in a later phase). */
  modelsOpponents: boolean;
  /** Whether this tier runs Monte-Carlo simulation for card play (Module C, EXPERT). */
  usesSimulation: boolean;
}

export const TIER_CONFIG: Record<BotTier, TierConfig> = {
  EASY: {
    mistakeRate: 0.25,
    distributionConfidence: 0.3,
    bidNoise: 1.2,
    canDash: false,
    countsCards: false,
    modelsOpponents: false,
    usesSimulation: false,
  },
  MEDIUM: {
    mistakeRate: 0.10,
    distributionConfidence: 0.7,
    bidNoise: 0.6,
    canDash: true,
    countsCards: false,
    modelsOpponents: false,
    usesSimulation: false,
  },
  HARD: {
    mistakeRate: 0.03,
    distributionConfidence: 1.0,
    bidNoise: 0.2,
    canDash: true,
    countsCards: true,
    modelsOpponents: true,
    usesSimulation: false,
  },
  EXPERT: {
    mistakeRate: 0.0,
    distributionConfidence: 1.0,
    bidNoise: 0.0,
    canDash: true,
    countsCards: true,
    modelsOpponents: true,
    usesSimulation: true,
  },
};

/** Map the legacy 3-tier enum onto the new 4-tier system without breaking callers. */
export function normalizeTier(level: string | undefined): BotTier {
  switch (level) {
    case 'EASY': return 'EASY';
    case 'MEDIUM': return 'MEDIUM';
    case 'HARD': return 'HARD';
    case 'EXPERT': return 'EXPERT';
    default: return 'MEDIUM';
  }
}

// ----------------------------------------------------------------------------
//  1. MODULE A — THE HAND Evaluator
// ----------------------------------------------------------------------------
//  Estimate expected tricks for a hand under a specific trump (or Sans).
//  Three independent sources of tricks are combined:
//     (a) High-card tricks  — honors that win by rank (A, guarded K, etc.)
//     (b) Trump length tricks — long trump suits win extra tricks by length
//     (c) Ruffing tricks    — short SIDE suits let you trump in (only if a
//                             real trump exists; worthless in Sans)
// ----------------------------------------------------------------------------

const ALL_SUITS: Suit[] = ['SPADES', 'HEARTS', 'DIAMONDS', 'CLUBS'];

function rankVal(r: Rank): number {
  const order: Record<Rank, number> = {
    TWO: 2, THREE: 3, FOUR: 4, FIVE: 5, SIX: 6, SEVEN: 7, EIGHT: 8,
    NINE: 9, TEN: 10, JACK: 11, QUEEN: 12, KING: 13, ACE: 14,
  };
  return order[r];
}

export interface SuitBreakdown {
  suit: Suit;
  count: number;
  highCardTricks: number;
  lengthTricks: number;
  ruffTricks: number;
  hasAce: boolean;
  hasKing: boolean;
  hasQueen: boolean;
}

export interface HandEvaluation {
  trump: Suit | 'NONE';
  expectedTricks: number;       // final, with distribution confidence applied
  rawExpected: number;          // before confidence scaling
  breakdown: SuitBreakdown[];
  reasoning: string;
}

/**
 * High-card tricks for ONE suit, given how many cards are held in it.
 * Uses standard trick-taking honor combinations. Length matters because a King
 * is only a trick if it is "guarded" (has a low card behind it), etc.
 */
function highCardTricksForSuit(cards: CardModel[]): number {
  const has = (r: Rank) => cards.some(c => c.rank === r);
  const n = cards.length;
  const A = has('ACE'), K = has('KING'), Q = has('QUEEN'), J = has('JACK');
  let t = 0;
  // Ace: almost always a trick.
  if (A) t += 1.0;
  // King: a trick only if guarded (2+ cards) OR backed by the Ace.
  if (K) {
    if (A) t += 1.0;            // A-K solid two tricks
    else if (n >= 2) t += 0.5;  // guarded king ~ half a trick
    // singleton bare King ~ 0 (will be captured)
  }
  // Queen: a trick only if well guarded and supported by a higher honor.
  if (Q) {
    if (A && K) t += 1.0;       // A-K-Q = three solid
    else if ((A || K) && n >= 3) t += 0.5; // supported & guarded
    else if (n >= 3) t += 0.25; // long-suit chance
  }
  // Jack: marginal, only with Q support and length.
  if (J && Q && n >= 4) t += 0.25;
  return t;
}

/**
 * Length tricks: in a long suit, low cards become winners once the high cards
 * everyone else holds are exhausted. Conservative model: cards beyond the 4th
 * in a suit are increasingly likely winners.
 */
function lengthTricksForSuit(count: number): number {
  if (count <= 4) return 0;
  // Calibrated DOWN (was 0.5 + 0.75/card): opponents also hold high cards, so
  // long-suit low cards win less often than a naive count suggests.
  return 0.4 + (count - 5) * 0.6;
}

/**
 * Ruffing value: short SIDE suits (not the trump) let the bot trump in.
 * Only meaningful when a real trump exists and the bot has trumps to ruff with.
 * Void = strongest, singleton = good, doubleton = mild.
 */
function ruffTricksForSideSuit(sideCount: number, trumpCount: number): number {
  if (trumpCount === 0) return 0;
  // You can't ruff more times than you have spare trumps.
  const spareTrumps = Math.max(0, trumpCount - 2); // keep ~2 trumps for drawing
  let raw = 0;
  // Calibrated DOWN: ruffs are often pre-empted (suit led before you're void,
  // opponents over-ruff, you must follow). Don't bank a void as 2 sure tricks.
  if (sideCount === 0) raw = 1.5;       // void: multiple ruffs (was 2.0)
  else if (sideCount === 1) raw = 0.75; // singleton: one likely ruff (was 1.0)
  else if (sideCount === 2) raw = 0.15; // doubleton: occasional (was 0.25)
  return Math.min(raw, spareTrumps + 0.5);
}

/**
 * Evaluate a hand under a specific trump declaration.
 */
export function evaluateHand(
  hand: CardModel[],
  trump: Suit | 'NONE',
  confidence: number = 1.0,
): HandEvaluation {
  const isSans = trump === 'NONE';
  const trumpCount = isSans ? 0 : hand.filter(c => c.suit === trump).length;
  const breakdown: SuitBreakdown[] = [];
  let highCard = 0;
  let length = 0;
  let ruff = 0;
  for (const s of ALL_SUITS) {
    const cards = hand.filter(c => c.suit === s);
    const count = cards.length;
    const isTrumpSuit = !isSans && s === trump;
    const hc = highCardTricksForSuit(cards);
    // Length tricks: trump length is most reliable; side-suit length only in Sans.
    const len = isTrumpSuit
      ? lengthTricksForSuit(count)
      : (isSans ? lengthTricksForSuit(count) : 0);
    // Ruffing only counts for SIDE suits when a trump exists.
    const rf = (!isSans && !isTrumpSuit) ? ruffTricksForSideSuit(count, trumpCount) : 0;
    highCard += hc;
    length += len;
    ruff += rf;
    breakdown.push({
      suit: s,
      count,
      highCardTricks: round2(hc),
      lengthTricks: round2(len),
      ruffTricks: round2(rf),
      hasAce: cards.some(c => c.rank === 'ACE'),
      hasKing: cards.some(c => c.rank === 'KING'),
      hasQueen: cards.some(c => c.rank === 'QUEEN'),
    });
  }
  // High-card tricks are "real" regardless of skill. Distribution (length+ruff)
  // is what weak players under-count, so confidence only scales that part.
  const distributional = (length + ruff) * confidence;
  const rawExpected = highCard + (length + ruff);
  const expectedTricks = highCard + distributional;
  const reasoning =
    `Trump=${trump} | HighCard=${round2(highCard)} ` +
    `Length=${round2(length)} Ruff=${round2(ruff)} ` +
    `(conf ${confidence}) => exp ${round2(expectedTricks)}`;
  return { trump, expectedTricks, rawExpected, breakdown, reasoning };
}

/**
 * Try every possible trump (4 suits + Sans) and return the evaluation for each,
 * sorted strongest-first. This is the basis for both trump choice and bidding.
 */
export function evaluateAllTrumps(
  hand: CardModel[],
  confidence: number = 1.0,
): HandEvaluation[] {
  const options: (Suit | 'NONE')[] = ['SPADES', 'HEARTS', 'DIAMONDS', 'CLUBS', 'NONE'];
  return options
    .map(t => evaluateHand(hand, t, confidence))
    .sort((a, b) => b.expectedTricks - a.expectedTricks);
}

// ----------------------------------------------------------------------------
//  2. SMART TRUMP CHOICE  (replaces evaluateAITrump)
// ----------------------------------------------------------------------------
export function chooseBestTrump(hand: CardModel[], tier: BotTier = 'MEDIUM'): Suit {
  const cfg = TIER_CONFIG[tier];
  const evals = evaluateAllTrumps(hand, cfg.distributionConfidence)
    .filter(e => e.trump !== 'NONE'); // a declared trump suit, not Sans
  return (evals[0]?.trump as Suit) ?? 'SPADES';
}

// ----------------------------------------------------------------------------
//  3. SMART BID  (drop-in upgrade for evaluateAIBid)
// ----------------------------------------------------------------------------
//  Returns the same shape your App.tsx already consumes, plus a tier-aware,
//  distribution-aware estimate and reliable Dash detection.
// ----------------------------------------------------------------------------

export interface BotBidResult {
  bid: number;
  isDashCall: boolean;
  rawFloat: number;
  intendedBid: number;
  potentialTrump: Suit;
  reasoning: string;
  /** True if `bid` is legal to submit directly as an auction Call (>= 4, or a
   * Dash). If false, the caller must treat it as a pass in the auction (it's
   * still a valid low ESTIMATE for the Estimation phase). Making this explicit
   * removes the old comment-only "<4 means pass" contract. */
  isCallPhaseLegal: boolean;
}

/**
 * Deterministic pseudo-jitter so the same hand+seat doesn't flip-flop, but
 * different bots vary. (We avoid Math.random for reproducibility/testing.)
 */
function seatJitter(playerId: string | undefined, magnitude: number): number {
  if (!playerId || magnitude === 0) return 0;
  let h = 0;
  for (let i = 0; i < playerId.length; i++) h = (h * 31 + playerId.charCodeAt(i)) | 0;
  const unit = ((h % 1000) / 1000) * 2 - 1; // -1..1
  return unit * magnitude;
}

export function evaluateBotBid(
  hand: CardModel[],
  tier: BotTier = 'MEDIUM',
  options: {
    otherBids?: number[];
    callerBid?: number;
    playerId?: string;
    chosenTrump?: Suit | 'NONE' | 'POTENTIAL';
    /** Fast-round mandatory trump. When set and the intended bid is < 8, the
     * bot's suit MUST be this suit — it has no free choice (Super Calls, 8+,
     * still choose freely). */
    mandatoryTrump?: Suit | 'NONE' | null;
  } = {},
): BotBidResult {
  const cfg = TIER_CONFIG[tier];
  const { playerId, chosenTrump = 'POTENTIAL', callerBid = -1, otherBids = [], mandatoryTrump = null } = options;

  // Determine the trump we're evaluating under.
  let trump: Suit | 'NONE';
  let potentialTrump: Suit;
  if (chosenTrump === 'POTENTIAL') {
    const best = evaluateAllTrumps(hand, cfg.distributionConfidence)
      .filter(e => e.trump !== 'NONE')[0];
    potentialTrump = (best?.trump as Suit) ?? 'SPADES';
    trump = potentialTrump;
  } else {
    trump = chosenTrump;
    potentialTrump = (chosenTrump === 'NONE' ? 'SPADES' : chosenTrump) as Suit;
  }

  const evalResult = evaluateHand(hand, trump, cfg.distributionConfidence);
  const rawFloat = evalResult.expectedTricks;

  // Tier noise: weak bots are less precise.
  const noisy = rawFloat + seatJitter(playerId, cfg.bidNoise);
  let intendedBid = Math.round(noisy);

  // ---- Dash detection (only tiers that are allowed) ----------------------
  // A Dash commits to winning ZERO tricks. What matters is NOT how many tricks
  // you *could* win if you tried (length/ruff tricks are optional), but how many
  // you'd be FORCED to win — i.e. unduckable high cards. So we score forced
  // tricks: aces are near-unduckable, kings a bit, long high-card runs add risk.
  let isDashCall = false;
  const aces = hand.filter(c => c.rank === 'ACE').length;
  const kings = hand.filter(c => c.rank === 'KING').length;
  const forcedTricks = aces * 1.0 + kings * 0.4;
  let dashMaxExpected = 0;
  if (cfg.canDash && chosenTrump === 'POTENTIAL') {
    // A pre-bid Dash doesn't control the trump, so the DANGER case is the trump
    // under which this hand wins the MOST tricks (e.g. your long suit becoming
    // trump forces you to win with it). Only Dash if even that best-case ceiling
    // is tiny — otherwise a "no-ace" hand with trump length wins several tricks
    // and busts the Dash (the bug where a Dash won 5).
    dashMaxExpected = Math.max(
      ...evaluateAllTrumps(hand, cfg.distributionConfidence).map(e => e.expectedTricks),
    );
    if (aces === 0 && forcedTricks < 0.75 && dashMaxExpected <= 1.5) {
      isDashCall = true;
    }
  }

  // Return the HONEST estimate clamped only to [0..13]. We deliberately DON'T
  // force a minimum of 4 here: that minimum applies to the AUCTION (the Call),
  // not the Estimation phase where a player may legitimately bid 1, 2 or 0. A
  // weak hand should PASS the auction and estimate low — never call 4 on a
  // 1-trick hand. `isCallPhaseLegal` below tells the caller explicitly whether
  // this bid is submittable as an auction Call or must be treated as a pass.
  let bid = intendedBid;
  if (isDashCall) {
    bid = 0;
  } else {
    if (bid < 0) bid = 0;
    if (bid > 13) bid = 13;
    if (callerBid >= 0 && bid > callerBid) bid = callerBid; // respect Call Cap
  }

  // Fast-round mandatory trump: bids under 8 have no free suit choice.
  if (mandatoryTrump && bid < 8) {
    trump = mandatoryTrump;
    if (mandatoryTrump !== 'NONE') potentialTrump = mandatoryTrump;
  }

  // Forbidden-13 avoidance: if this bot is the last of 4 to declare and its
  // honest bid would make the round total exactly 13, nudge it off — mirrors
  // validateBidding's rule locally so this engine never proposes an illegal bid.
  let forbidden13Adjusted = false;
  if (!isDashCall && otherBids.filter(b => b >= 0).length === 3) {
    const sumOfOthers = otherBids.filter(b => b >= 0).reduce((s, b) => s + b, 0);
    if (sumOfOthers + bid === 13) {
      const ceil = callerBid >= 0 ? callerBid : 13;
      if (bid > 0) bid -= 1;
      else if (bid < ceil) bid += 1;
      forbidden13Adjusted = true;
    }
  }

  const reasoning =
    `[${tier}] ${evalResult.reasoning} | noisy=${round2(noisy)} ` +
    `intended=${intendedBid}` +
    (isDashCall ? ` | DASH (forced=${round2(forcedTricks)}, maxExp=${round2(dashMaxExpected)}, ${aces} aces)` : '') +
    (forbidden13Adjusted ? ` | adjusted to avoid Forbidden-13` : '');

  return {
    bid,
    isDashCall,
    rawFloat,
    intendedBid,
    potentialTrump,
    reasoning,
    isCallPhaseLegal: isDashCall || bid >= 4,
  };
}

// ----------------------------------------------------------------------------
//  helpers
// ----------------------------------------------------------------------------
function round2(n: number): number {
  return Math.round(n * 100) / 100;
}
