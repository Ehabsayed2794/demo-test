// ============================================================================
//  botPersonality.ts  —  Estemshan Intelligent Bot System
//  PHASE 3a:  Bot Personalities + Adaptive Difficulty
// ----------------------------------------------------------------------------
//  This is the "make it #1" layer. Two ideas no normal card app ships well:
//
//    1. PERSONALITIES — each bot has a recognisable style (aggressive,
//       conservative, trickster). Players remember "that bot always Dashes".
//       This is pure flavour ON TOP of the existing skill tiers: a bot can be
//       EXPERT+Aggressive or EASY+Conservative independently.
//
//    2. ADAPTIVE DIFFICULTY — silently keep the player in the fun zone. If they
//       lose repeatedly, ease off; if they steamroll, harden. The single best
//       retention lever in any single-player game.
//
//  Self-contained: imports only ./types and ./botEngine. Additive — nothing
//  breaks if you don't wire it.
// ============================================================================

import { CardModel, Suit } from './types';
import { BotTier, BotBidResult, evaluateBotBid } from './botEngine';
import { estimateBidBySimulation, estimateDashSuccess } from './botSimulation';

// ----------------------------------------------------------------------------
//  1. PERSONALITIES
// ----------------------------------------------------------------------------
export type Personality = 'BALANCED' | 'AGGRESSIVE' | 'CONSERVATIVE' | 'TRICKSTER';

export interface PersonalityProfile {
  key: Personality;
  /** Added to the raw trick estimate before rounding. + = bids higher. */
  bidBias: number;
  /** Scales how readily the bot declares a Dash. >1 = dashes more often. */
  dashEagerness: number;
  /** 0..1 — appetite for pushing a bid to a Super Call (8+) when close. */
  superCallAppetite: number;
  /** Player-facing label + one-line flavour. */
  displayName: string;
  blurb: string;
}

export const PERSONALITIES: Record<Personality, PersonalityProfile> = {
  BALANCED: {
    key: 'BALANCED',
    bidBias: 0,
    dashEagerness: 1.0,
    superCallAppetite: 0.3,
    displayName: 'Steady',
    blurb: 'Plays it straight. Bids what the hand is worth.',
  },
  AGGRESSIVE: {
    key: 'AGGRESSIVE',
    bidBias: +0.4,        // trimmed from +0.8 — a nudge, not a trap
    dashEagerness: 0.6,
    superCallAppetite: 0.7,
    displayName: 'The Shark',
    blurb: 'Bids a touch high, hunts Super Calls, pressures the table.',
  },
  CONSERVATIVE: {
    key: 'CONSERVATIVE',
    bidBias: -0.5,        // slightly softened
    dashEagerness: 1.6,
    superCallAppetite: 0.1,
    displayName: 'The Fox',
    blurb: 'Underbids for safety and loves a clean Dash.',
  },
  TRICKSTER: {
    key: 'TRICKSTER',
    bidBias: +0.15,       // trimmed from +0.3 — variance comes from Dashes, not overbidding
    dashEagerness: 1.4,
    superCallAppetite: 0.6,
    displayName: 'The Joker',
    blurb: 'High variance — unpredictable Dashes and bold calls.',
  },
};

/**
 * Apply a personality to a base bid. Skill (tier) decides HOW WELL the bot
 * evaluates; personality decides its STYLE on top of that evaluation.
 */
export function applyPersonalityToBid(
  base: BotBidResult,
  personality: Personality,
  callerBid: number = -1,
): BotBidResult {
  const p = PERSONALITIES[personality];
  // A conservative bot is more willing to keep a Dash; an aggressive one less so.
  // We re-decide Dash using the personality's eagerness against a soft threshold.
  let isDashCall = base.isDashCall;
  if (base.isDashCall && p.dashEagerness < 1.0) {
    // Aggressive types sometimes convert a marginal Dash into a real bid.
    if (base.rawFloat * (1 / p.dashEagerness) >= 3) isDashCall = false;
  }
  let bid = base.bid;
  if (!isDashCall) {
    let adjusted = base.rawFloat + p.bidBias;
    bid = Math.round(adjusted);
    // Super-call appetite: if close to 8 and appetite is high, push for it.
    if (bid >= 7 && bid < 8 + 0 && p.superCallAppetite >= 0.6 && base.rawFloat >= 7.0) {
      bid = 8;
    }
    // Clamp only to [0..13]. The minimum-4 auction rule is NOT applied here —
    // the caller treats a bid < 4 as a PASS in the auction, and uses it directly
    // as a low estimate in the Estimation phase. (Forcing 4 made weak hands bid
    // unmakeable contracts.)
    if (bid < 0) bid = 0;
    if (bid > 13) bid = 13;
    if (callerBid >= 0 && bid > callerBid) bid = callerBid; // respect Call Cap
  } else {
    bid = 0;
  }
  return {
    ...base,
    bid,
    isDashCall,
    // Recompute — personality can change bid/isDashCall above, and this is a
    // derived field, not something that should carry over stale from `base`.
    isCallPhaseLegal: isDashCall || bid >= 4,
    reasoning: `${base.reasoning} | personality=${p.displayName}(${p.key}) -> bid ${isDashCall ? 'DASH' : bid}`,
  };
}

/**
 * Convenience: evaluate a bid with BOTH skill tier and personality in one call.
 */
export function evaluateBotBidWithPersonality(
  hand: CardModel[],
  tier: BotTier,
  personality: Personality,
  options: {
    otherBids?: number[];
    callerBid?: number;
    playerId?: string;
    chosenTrump?: Suit | 'NONE' | 'POTENTIAL';
    mandatoryTrump?: Suit | 'NONE' | null;
  } = {},
): BotBidResult {
  let base = evaluateBotBid(hand, tier, options);
  // HARD and EXPERT use SIMULATION for both the Dash decision and the bid number.
  if (tier === 'EXPERT' || tier === 'HARD') {
    const samples = tier === 'EXPERT' ? 150 : 100; // HARD a touch lighter
    // 1) VERIFY a heuristic Dash by simulating its real success probability.
    //    Expected-tricks is only a proxy; a hand rated ~0.5 can still be forced
    //    to win a couple of tricks. If P(win 0) is too low, cancel the Dash.
    if (base.isDashCall) {
      const dashP = estimateDashSuccess(hand, { samples });
      const threshold = tier === 'EXPERT' ? 0.85 : 0.80;
      if (dashP < threshold) {
        base = {
          ...base,
          isDashCall: false,
          reasoning: base.reasoning + ` | dash REJECTED (P(0)=${(dashP * 100).toFixed(0)}% < ${threshold * 100}%)`,
        };
      } else {
        base = { ...base, reasoning: base.reasoning + ` | dash OK (P(0)=${(dashP * 100).toFixed(0)}%)` };
      }
    }
    // 2) If not (or no longer) dashing, bid the trick count it can most reliably
    //    make under the assumed trump. (Essential for HARD: at full confidence it
    //    trusted the optimistic heuristic and over-bid. EXPERT still pulls ahead
    //    via its Monte-Carlo CARD PLAY. MEDIUM/EASY keep the heuristic on purpose.)
    if (!base.isDashCall) {
      const assumedTrump: Suit | 'NONE' =
        options.chosenTrump && options.chosenTrump !== 'POTENTIAL'
          ? options.chosenTrump
          : base.potentialTrump;
      const sim = estimateBidBySimulation(hand, assumedTrump, { samples });
      base = {
        ...base,
        rawFloat: sim.bid,
        intendedBid: sim.bid,
        bid: sim.bid,
        isCallPhaseLegal: base.isDashCall || sim.bid >= 4,
        reasoning: base.reasoning + ` | ${tier} sim-bid=${sim.bid} (modal P=${(sim.confidence * 100).toFixed(0)}%)`,
      };
    }
  }
  return applyPersonalityToBid(base, personality, options.callerBid ?? -1);
}

// ----------------------------------------------------------------------------
//  2. ADAPTIVE DIFFICULTY
// ----------------------------------------------------------------------------
//  Track the human's recent results and nudge bot strength to keep games close.
//  Store `recentHumanWins` as a rolling window of booleans (true = human won
//  the round / placed top). Keep this in your game state or localStorage.
// ----------------------------------------------------------------------------

const TIER_ORDER: BotTier[] = ['EASY', 'MEDIUM', 'HARD', 'EXPERT'];

function shift(tier: BotTier, delta: number): BotTier {
  const i = TIER_ORDER.indexOf(tier);
  const j = Math.max(0, Math.min(TIER_ORDER.length - 1, i + delta));
  return TIER_ORDER[j];
}

/**
 * Given the base tier and the human's recent win-rate (0..1 over the last N
 * rounds), return the tier the bot should actually use this round.
 * 
 * winRate < 0.25  -> ease off one tier (player is struggling)
 * winRate > 0.75  -> harden one tier (player is dominating)
 * otherwise        -> keep base tier (good balance)
 * 
 * `floorTier`/`ceilTier` let you clamp so e.g. an EXPERT seat never drops below
 * HARD, or an EASY tutorial bot never rises above MEDIUM.
 */
export function adaptTier(
  baseTier: BotTier,
  humanRecentWinRate: number,
  bounds: { floor?: BotTier; ceil?: BotTier } = {},
): BotTier {
  let t = baseTier;
  if (humanRecentWinRate < 0.25) t = shift(t, -1);
  else if (humanRecentWinRate > 0.75) t = shift(t, +1);
  if (bounds.floor && TIER_ORDER.indexOf(t) < TIER_ORDER.indexOf(bounds.floor)) t = bounds.floor;
  if (bounds.ceil && TIER_ORDER.indexOf(t) > TIER_ORDER.indexOf(bounds.ceil)) t = bounds.ceil;
  return t;
}

/** Maintain a rolling window of the human's recent round outcomes. */
export function pushHumanResult(window: boolean[], humanWonRound: boolean, size = 6): boolean[] {
  const next = [...window, humanWonRound];
  return next.slice(-size);
}

export function winRate(window: boolean[]): number {
  if (window.length === 0) return 0.5; // neutral until we have data
  return window.filter(Boolean).length / window.length;
}

// ----------------------------------------------------------------------------
//  3. SUGGESTED TABLE SETUP
// ----------------------------------------------------------------------------
//  A ready-made trio of opponents that feels varied and memorable. Assign these
//  at game init alongside each bot's skill tier.
// ----------------------------------------------------------------------------
export interface BotIdentity {
  tier: BotTier;
  personality: Personality;
}

export const SUGGESTED_TABLE: Record<string, BotIdentity> = {
  p2: { tier: 'EASY',   personality: 'CONSERVATIVE' }, // "The Fox" — gentle, cautious
  p3: { tier: 'HARD',   personality: 'AGGRESSIVE'   }, // "The Shark" — real pressure
  p4: { tier: 'EXPERT', personality: 'TRICKSTER'    }, // "The Joker" — wildcard genius
};

// ============================================================================
//  WIRING
// ----------------------------------------------------------------------------
//  • Add optional fields to PlayerModel:
//       personality?: Personality;
//       baseTier?: DifficultyLevel;     // if you want adaptive to remember the seat's base
//  • At bid time, instead of evaluateBotBid(...), call:
//       evaluateBotBidWithPersonality(hand, tier, bot.personality ?? 'BALANCED', {...})
//  • For adaptive difficulty, keep a rolling window in game state:
//       humanResults = pushHumanResult(humanResults, humanPlacedTopThisRound);
//    then each round compute the live tier:
//       const liveTier = adaptTier(bot.baseTier, winRate(humanResults),
//                                  { floor: 'EASY', ceil: 'EXPERT' });
//  • Show PERSONALITIES[p].displayName + blurb in the UI next to each bot for
//    instant character (e.g. "The Shark 🦈").
// ============================================================================
