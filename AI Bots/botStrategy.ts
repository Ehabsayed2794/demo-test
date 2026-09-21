// ============================================================================
//  botStrategy.ts  —  Estemshan Intelligent Bot System
//  PHASE 4:  Human-like Defensive / Spoiler Play  (FAIR successor to sabotage)
// ----------------------------------------------------------------------------
//  Real players, once they KNOW they've lost their own bid, switch to spoiling
//  the highest-value opponent — the Caller, a Super Caller, or a Dash Caller —
//  because those players score the most. This module reproduces that, with the
//  fairness rules we agreed on:
//
//   • Targets by POINTS AT STAKE, never by "is this the human". Applies to ALL
//     opponents symmetrically — a bot will spoil another bot's Super Call.
//   • Engages ONLY when the bot's own contract is already lost (busted). It
//     never sacrifices a makeable bid to spoil someone (bad players do that).
//   • Direction-aware: pushes the target AWAY from their exact bid (deny tricks
//     they need; force unwanted tricks onto a Dash Caller).
//   • Tier-gated: EASY never spoils; MEDIUM only spoils an obvious top target;
//     HARD/EXPERT do full value-ranked, direction-aware spoiling.
//
//  Returns null whenever spoiling does NOT apply, so the normal play logic in
//  botPlay.ts simply takes over.
//
//  Self-contained: imports only ./types, ./cardRules, and ./botEngine.
// ============================================================================

import { CardModel, PlayerModel, PlayedCard, Suit } from './types';
import { beats, legalMoves } from './cardRules';
import { BotTier } from './botEngine';

export interface SpoilerChoice {
  chosenCard: CardModel;
  reasoning: string;
}

const asc = (a: CardModel, b: CardModel) => a.value - b.value;
const desc = (a: CardModel, b: CardModel) => b.value - a.value;
const fmt = (c: CardModel) => `${c.rank} of ${c.suit}`;

/** Is THIS player out of contention for their own bid (nothing left to lose)? */
function isBusted(p: PlayerModel): boolean {
  const remaining = p.hand.length;
  const isDash = p.isDashCall || p.bid === 0;
  if (isDash) return p.tricksWon >= 1;                 // a dash that took a trick is lost
  return p.tricksWon > p.bid || p.tricksWon + remaining < p.bid;
}

type Mode = 'DENY' | 'FORCE';

interface TargetInfo {
  player: PlayerModel;
  mode: Mode;        // DENY = stop them winning needed tricks; FORCE = make them win unwanted ones
  value: number;     // points at stake — higher = juicier target
}

/** Classify an opponent as a (still-vulnerable) target, or null if not worth it. */
function classify(p: PlayerModel, callerId?: string): TargetInfo | null {
  const remaining = p.hand.length;
  // For MODE purposes a pre-bid Dash Call and a plain bid=0 estimator behave the
  // same (contract broken the instant they take a trick) — but they score under
  // DIFFERENT formulas (Dash Call: flat ±33/±25; plain bid=0: the generic 10+T
  // logic), so the VALUE tiering below must NOT treat them as equally juicy.
  const isDashLike = p.isDashCall || p.bid === 0;

  let mode: Mode | null = null;
  if (isDashLike) {
    // Dash-like player still alive (0 tricks): force a trick onto them. If they've
    // already taken one they're busted — no value.
    if (p.tricksWon === 0) mode = 'FORCE';
  } else {
    const needs = p.bid - p.tricksWon;
    if (needs > 0 && p.tricksWon + remaining >= p.bid) mode = 'DENY';   // still chasing — deny
    else if (needs === 0) mode = 'FORCE';                              // made it — force overshoot
    // needs < 0 => already busted, skip
  }
  if (!mode) return null;

  // Points at stake (whoever stands to gain most is the juiciest target).
  let value: number;
  if (p.isDashCall) value = 35;                             // true Dash Call — flat ±33/±25 swing
  else if (p.bid >= 8) value = 20 + p.bid;                  // Super Call
  else if (callerId && p.id === callerId) value = 15 + p.bid; // known Caller
  else if (p.isWazz) value = 8 + p.bid;                     // With
  else if (p.bid === 0) value = 12;                         // plain zero estimate — 10+T stakes, not Dash-tier
  else value = 10 + p.bid;                                  // standard (top bidder ≈ caller)

  return { player: p, mode, value };
}

/**
 * Choose a spoiler card, or return null if defensive play does not apply
 * (bot not busted, EASY tier, or no vulnerable target).
 */
export function chooseSpoilerCard(
  bot: PlayerModel,
  allPlayers: PlayerModel[],
  currentTrick: PlayedCard[],
  trumpSuit: Suit | 'NONE',
  tier: BotTier,
  opts: { callerId?: string } = {},
): SpoilerChoice | null {
  if (tier === 'EASY') return null;              // beginners don't think defensively
  if (!isBusted(bot)) return null;               // only spoil when nothing to lose

  const ledSuit: Suit | null = currentTrick.length > 0 ? currentTrick[0].card.suit : null;
  const legal = legalMoves(bot.hand, ledSuit);
  if (legal.length === 0) return null;

  // Build & rank vulnerable targets.
  let targets = allPlayers
    .filter(p => p.id !== bot.id)
    .map(p => classify(p, opts.callerId))
    .filter((t): t is TargetInfo => t !== null)
    .sort((a, b) => b.value - a.value);

  if (targets.length === 0) return null;

  // MEDIUM only engages an OBVIOUS top target (Dash / Super / known Caller).
  if (tier === 'MEDIUM') {
    const obvious = targets.find(t =>
      t.player.isDashCall || t.player.bid === 0 || t.player.bid >= 8 ||
      (opts.callerId && t.player.id === opts.callerId));
    if (!obvious) return null;
    targets = [obvious];
  }

  const target = targets[0];
  const tname = target.player.name;

  // Who is currently winning the trick in progress?
  let winner: PlayedCard | null = null;
  if (currentTrick.length > 0 && ledSuit) {
    winner = currentTrick[0];
    for (let i = 1; i < currentTrick.length; i++) {
      if (beats(currentTrick[i].card, winner.card, ledSuit, trumpSuit)) winner = currentTrick[i];
    }
  }
  const targetIsWinning = winner?.playerId === target.player.id;

  // ----- LEADING -----------------------------------------------------------
  if (currentTrick.length === 0 || !ledSuit) {
    if (target.mode === 'DENY') {
      // Take the trick ourselves so the target can't get a trick it needs.
      const card = [...legal].sort(desc)[0];
      return { chosenCard: card, reasoning: `🛡️ Spoil(DENY ${tname}): lead high ${fmt(card)} to grab the trick.` };
    }
    // FORCE: bait the target into winning by leading low (esp. vs a Dash caller).
    const nonTrump = legal.filter(c => trumpSuit === 'NONE' || c.suit !== trumpSuit);
    const card = [...(nonTrump.length ? nonTrump : legal)].sort(asc)[0];
    return { chosenCard: card, reasoning: `🛡️ Spoil(FORCE ${tname}): lead low ${fmt(card)} to push a trick onto them.` };
  }

  // ----- FOLLOWING ---------------------------------------------------------
  const winners = legal.filter(c => beats(c, winner!.card, ledSuit, trumpSuit));
  const losers = legal.filter(c => !beats(c, winner!.card, ledSuit, trumpSuit));

  if (target.mode === 'DENY') {
    // Deny the target: if they're winning, steal it; otherwise take it cheaply.
    if (winners.length > 0) {
      const card = [...winners].sort(asc)[0]; // cheapest card that wins
      return {
        chosenCard: card,
        reasoning: targetIsWinning
          ? `🛡️ Spoil(DENY ${tname}): steal the trick with ${fmt(card)}.`
          : `🛡️ Spoil(DENY ${tname}): take the trick with ${fmt(card)} so they can't.`,
      };
    }
    const card = [...legal].sort(asc)[0]; // can't win — duck low, save cards
    return { chosenCard: card, reasoning: `🛡️ Spoil(DENY ${tname}): can't take it, duck ${fmt(card)}.` };
  }

  // FORCE: we want the target to WIN this (unwanted) trick.
  if (targetIsWinning) {
    // Let them keep it — play a card that does NOT beat them (highest safe loser).
    if (losers.length > 0) {
      const card = [...losers].sort(desc)[0]; // shed a high card safely while they're stuck
      return { chosenCard: card, reasoning: `🛡️ Spoil(FORCE ${tname}): let them keep the trick, shed ${fmt(card)}.` };
    }
    const card = [...legal].sort(asc)[0]; // forced to overtake — minimize
    return { chosenCard: card, reasoning: `🛡️ Spoil(FORCE ${tname}): forced to overtake, minimal ${fmt(card)}.` };
  }
  // Target isn't on top yet — don't take the trick ourselves; duck low and hope
  // it falls to them (or a later seat pushes it their way).
  const card = [...legal].sort(asc)[0];
  return { chosenCard: card, reasoning: `🛡️ Spoil(FORCE ${tname}): duck ${fmt(card)} to avoid taking it ourselves.` };
}
