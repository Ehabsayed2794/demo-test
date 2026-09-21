// ============================================================================
//  botPlay.ts  —  Estemshan Intelligent Bot System
//  PHASE 2:  Module C (Card Play Engine) — counting, ducking, finesse,
//            tier-aware mistakes, and FAIR play-to-your-contract logic.
// ----------------------------------------------------------------------------
//  This REPLACES selectAIGameplayCard + suggestSabotageCard from utils.ts.
//  Key design change: bots now play to MAKE THEIR OWN CONTRACT, not to gang up
//  on the human. The old `+30` bias that hard-targeted player 'p1' is gone.
//
//  Self-contained: imports only from ./types, ./cardRules, and ./botEngine. It
//  does not depend on utils.ts, so it can't break if utils changes.
// ============================================================================

import { CardModel, PlayerModel, PlayedCard, Suit, Rank } from './types';
import { beats, legalMoves } from './cardRules';
import { BotTier, TIER_CONFIG } from './botEngine';
import { chooseSpoilerCard } from './botStrategy';
import { selectExpertCard } from './botSimulation';

export interface CardChoice {
  chosenCard: CardModel;
  reasoning: string;
}

const RANK_VALUE: Record<Rank, number> = {
  TWO: 2, THREE: 3, FOUR: 4, FIVE: 5, SIX: 6, SEVEN: 7, EIGHT: 8,
  NINE: 9, TEN: 10, JACK: 11, QUEEN: 12, KING: 13, ACE: 14,
};

/** Find the currently-winning play in a partial trick. */
function currentWinner(trick: PlayedCard[], ledSuit: Suit, trump: Suit | 'NONE'): PlayedCard {
  let win = trick[0];
  for (let i = 1; i < trick.length; i++) {
    if (beats(trick[i].card, win.card, ledSuit, trump)) win = trick[i];
  }
  return win;
}

const byValueAsc = (a: CardModel, b: CardModel) => a.value - b.value;
const byValueDesc = (a: CardModel, b: CardModel) => b.value - a.value;

// ----------------------------------------------------------------------------
//  Card counting (HARD / EXPERT) — "is this card a guaranteed winner?"
// ----------------------------------------------------------------------------
//  A card is a "boss" (top remaining) in its suit if every higher-ranked card
//  of that suit is already accounted for (seen on the table, or in my hand).
// ----------------------------------------------------------------------------
function isBoss(
  card: CardModel,
  knownIds: Set<string>,        // seen + my hand + current trick
  trump: Suit | 'NONE',
): boolean {
  // In a trump game, a non-trump "boss" can still be ruffed — treat as boss only
  // for its own suit; caller decides how much to trust it.
  for (const [rank, val] of Object.entries(RANK_VALUE)) {
    if (val <= card.value) continue;
    const higherId = `${card.suit}-${rank}`;
    // Is this specific higher card still unknown (i.e. an opponent might hold it)?
    let stillOut = true;
    for (const id of knownIds) {
      if (id.startsWith(higherId)) { stillOut = false; break; }
    }
    if (stillOut) return false; // a higher card is unaccounted for
  }
  return true;
}

function buildKnownIds(
  bot: PlayerModel,
  currentTrick: PlayedCard[],
  seenCards: CardModel[],
): Set<string> {
  const ids = new Set<string>();
  // We key by "SUIT-RANK" (not the unique id) so counting works across sources.
  const add = (c: CardModel) => ids.add(`${c.suit}-${c.rank}`);
  bot.hand.forEach(add);
  currentTrick.forEach(p => add(p.card));
  seenCards.forEach(add);
  return ids;
}

// ----------------------------------------------------------------------------
//  MAIN: select a card to play
// ----------------------------------------------------------------------------
export function selectBotCard(
  bot: PlayerModel,
  allPlayers: PlayerModel[],
  currentTrick: PlayedCard[],
  trumpSuit: Suit | 'NONE',
  tier: BotTier = 'MEDIUM',
  opts: { seenCards?: CardModel[]; callerId?: string } = {},
): CardChoice {
  if (bot.hand.length === 0) {
    throw new Error(`selectBotCard called for ${bot.id} with an empty hand — caller/turn-order bug.`);
  }
  if (bot.bid < 0) {
    throw new Error(`selectBotCard called for ${bot.id} before its bid was set (bid=${bot.bid}).`);
  }

  const cfg = TIER_CONFIG[tier];
  const ledSuit: Suit | null = currentTrick.length > 0 ? currentTrick[0].card.suit : null;
  const legal = legalMoves(bot.hand, ledSuit);

  if (legal.length === 0) {
    // Unreachable given legalMoves()'s contract (bot.hand is non-empty here), but
    // never synthesize a fake card if it somehow happens.
    return { chosenCard: bot.hand[bot.hand.length - 1], reasoning: 'Failsafe: no legal card found, using last card.' };
  }
  if (legal.length === 1) {
    return { chosenCard: legal[0], reasoning: 'Only one legal card.' };
  }

  // ---- Tier mistake injection: weak bots sometimes play a random legal card.
  if (cfg.mistakeRate > 0 && Math.random() < cfg.mistakeRate) {
    const r = legal[Math.floor(Math.random() * legal.length)];
    return { chosenCard: r, reasoning: `[${tier}] Mistake (rate ${cfg.mistakeRate}): random legal card.` };
  }

  // ---- Fair, value-based spoiler — only fires when this bot is already busted.
  const spoil = chooseSpoilerCard(bot, allPlayers, currentTrick, trumpSuit, tier, { callerId: opts.callerId });
  if (spoil) return spoil;

  // ---- EXPERT plays via Monte-Carlo simulation (heuristic path for the rest).
  if (cfg.usesSimulation && legal.length > 1) {
    const mc = selectExpertCard(bot, allPlayers, currentTrick, trumpSuit, {
      seenCards: opts.seenCards,
      samples: 200,
    });
    if (mc?.chosenCard) return mc;
  }

  // ---- Contract goal & PACING ---------------------------------------------
  const need = bot.bid - bot.tricksWon;     // Dash players bid 0 -> need <= 0
  const cardsLeft = bot.hand.length;
  const slack = cardsLeft - need;           // tricks we can afford to LOSE

  // PACING (all tiers, whenever there's slack): play to WIN only when we have NO
  // slack left — i.e. we must win every remaining trick. While slack remains we
  // DUCK: offload low cards now (while opponents can still beat them) and hold
  // our winners for late. This is how a human paces a round with surplus tricks —
  // it stops the "cash everything early, then get trapped leading low into a
  // field of duckers" overshoot, and spreads our wins toward the round's end.
  const playToWin = need > 0 && slack <= 0;
  const pacing = need > 0 && slack > 0;     // still need tricks, but not yet

  const knownIds = cfg.countsCards
    ? buildKnownIds(bot, currentTrick, opts.seenCards ?? [])
    : null;

  // =========================================================================
  //  LEADING (trick is empty)
  // =========================================================================
  if (currentTrick.length === 0 || !ledSuit) {
    if (playToWin) {
      // No slack left — must win every remaining trick. Take this one forcefully.
      // 1) Cash a guaranteed winner if counting confirms one (HARD/EXPERT).
      if (knownIds) {
        const bosses = legal
          .filter(c => trumpSuit === 'NONE' || c.suit !== trumpSuit) // side-suit bosses first
          .filter(c => isBoss(c, knownIds, trumpSuit))
          .sort(byValueDesc);
        if (bosses.length > 0) {
          return { chosenCard: bosses[0], reasoning: `[${tier}] Lead: must-win, cash guaranteed ${fmt(bosses[0])}.` };
        }
      }
      // 2) Lead the top trump if we hold any (guarantees the trick).
      const trumps = legal.filter(c => trumpSuit !== 'NONE' && c.suit === trumpSuit);
      if (trumps.length > 0) {
        const hi = [...trumps].sort(byValueDesc)[0];
        return { chosenCard: hi, reasoning: `[${tier}] Lead: must-win, top trump ${fmt(hi)}.` };
      }
      // 3) Otherwise lead the highest card to try to win.
      const hi = [...legal].sort(byValueDesc)[0];
      return { chosenCard: hi, reasoning: `[${tier}] Lead: must-win, high card ${fmt(hi)}.` };
    } else {
      // PACING (still need tricks but have slack) OR already met our bid:
      // lead LOW to offload a weak card now and KEEP our winners for late —
      // never rush wins, never draw trumps early. Prefer a low non-trump.
      const nonTrumps = legal.filter(c => trumpSuit === 'NONE' || c.suit !== trumpSuit);
      const pool = nonTrumps.length > 0 ? nonTrumps : legal;
      const lo = [...pool].sort(byValueAsc)[0];
      const why = pacing ? 'pacing, hold winners for late' : 'avoiding tricks';
      return { chosenCard: lo, reasoning: `[${tier}] Lead: ${why} — lead low ${fmt(lo)}.` };
    }
  }

  // =========================================================================
  //  FOLLOWING (responding to a partial trick)
  // =========================================================================
  const winner = currentWinner(currentTrick, ledSuit, trumpSuit);
  const winners = legal.filter(c => beats(c, winner.card, ledSuit, trumpSuit));
  const losers = legal.filter(c => !beats(c, winner.card, ledSuit, trumpSuit));

  if (need > 0) {
    if (winners.length > 0) {
      // BOSS-AWARE CONTROL RETENTION (counting tiers, while we have slack):
      // if we already hold enough GUARANTEED winners (boss cards = highest left
      // in their suit) to make our bid LATER, duck this trick now and keep
      // control — there's no need to take a trick we can lock in at the end.
      // We only hold back when the late win is GUARANTEED, so this trims
      // overshoot without the undershoot that blind ducking caused.
      if (cfg.countsCards && slack > 0 && knownIds) {
        const bossesInHand = bot.hand.filter(c => isBoss(c, knownIds, trumpSuit)).length;
        if (bossesInHand >= need) {
          const lo = [...legal].sort(byValueAsc)[0];
          return {
            chosenCard: lo,
            reasoning: `[${tier}] Follow: hold control (${bossesInHand} sure winners ≥ need ${need}), duck low ${fmt(lo)}.`,
          };
        }
      }
      // Otherwise TAKE the trick with our CHEAPEST winner — bank it now (the late
      // win isn't guaranteed). Anti-rush discipline still lives in the LEADING
      // branch: when pacing we never lead winners out early.
      const cheapest = [...winners].sort(byValueAsc)[0];
      return { chosenCard: cheapest, reasoning: `[${tier}] Follow: win cheaply with ${fmt(cheapest)}.` };
    }
    // Can't win -> duck low, preserve high cards for later.
    const lo = [...legal].sort(byValueAsc)[0];
    return { chosenCard: lo, reasoning: `[${tier}] Follow: can't win, duck low ${fmt(lo)}.` };
  }

  // AVOID winning (bid already met): shed dangerous high cards while it's safe.
  if (losers.length > 0) {
    // Shed the HIGHEST card that still safely loses — dump dangerous high cards
    // now while they can't win. Prefer dumping non-trump high cards.
    const sortedLosers = [...losers].sort(byValueDesc);
    const nonTrumpLoser = sortedLosers.find(c => trumpSuit === 'NONE' || c.suit !== trumpSuit);
    const shed = nonTrumpLoser ?? sortedLosers[0];
    return { chosenCard: shed, reasoning: `[${tier}] Follow: avoid, safely shed high ${fmt(shed)}.` };
  }
  // Forced to win (every legal card beats the winner) -> win as cheaply as possible.
  const cheapestForced = [...legal].sort(byValueAsc)[0];
  return { chosenCard: cheapestForced, reasoning: `[${tier}] Follow: forced to win, minimize with ${fmt(cheapestForced)}.` };
}

function fmt(c: CardModel): string {
  return `${c.rank} of ${c.suit}`;
}

// ----------------------------------------------------------------------------
//  Backwards-compatible shim: matches the OLD selectAIGameplayCard signature
//  so you can swap the call site with minimal edits.
//    old: selectAIGameplayCard(bot, allPlayers, currentTrick, ledSuitParam, trumpSuit)
//    new: selectAIGameplayCardV2(bot, allPlayers, currentTrick, ledSuitParam, trumpSuit, tier, seenCards)
// ----------------------------------------------------------------------------
export function selectAIGameplayCardV2(
  bot: PlayerModel,
  allPlayers: PlayerModel[],
  currentTrick: PlayedCard[],
  _ledSuitParam: Suit | null,        // ignored: derived from the trick internally
  trumpSuit: Suit | 'NONE',
  tier: BotTier = 'MEDIUM',
  seenCards: CardModel[] = [],
  callerId?: string,
): CardChoice {
  return selectBotCard(bot, allPlayers, currentTrick, trumpSuit, tier, { seenCards, callerId });
}

// ============================================================================
//  WHAT CHANGED vs. the old logic
// ----------------------------------------------------------------------------
//  • REMOVED the sabotage engine and its `+30` bias that hard-targeted the human
//    player 'p1'. Bots now optimise their OWN contract — fair and trustworthy.
//  • Bots WIN CHEAPLY (lowest winning card) instead of crashing aces unnecessarily.
//  • Bots DUCK correctly when they've met their bid (avoid mode), shedding their
//    most dangerous high cards while it's safe.
//  • HARD/EXPERT COUNT CARDS: they know when a card is a guaranteed winner ("boss")
//    and cash it, instead of guessing.
//  • Tier mistake-rate makes EASY/MEDIUM feel human (occasional slips), while
//    HARD/EXPERT play tight.
//
//  STILL TO COME (Phase 3):
//  • EXPERT Monte-Carlo: simulate opponent hands and pick the play that maximises
//    P(making the exact bid). Currently EXPERT == HARD logic with zero mistakes.
//  • Module D opponent modelling: infer voids from the Avoid tags + play history.
//  • Bot "personalities" (aggressive / conservative / trickster).
//
//  WIRING:
//   1) import { selectAIGameplayCardV2 } from './botPlay';
//   2) Replace the call to selectAIGameplayCard(...) in App.tsx with
//      selectAIGameplayCardV2(bot, allPlayers, currentTrick, ledSuit, trumpSuit,
//                             normalizeTier(bot.difficulty), seenCardsThisRound);
//      If you don't track seenCardsThisRound yet, pass [] — HARD/EXPERT simply
//      fall back to in-trick reasoning (still strong). To unlock full counting,
//      accumulate every played card of the current round into an array and pass it.
//   3) You can delete suggestSabotageCard from utils.ts — it is no longer used.
// ============================================================================