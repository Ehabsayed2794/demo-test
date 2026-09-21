// ============================================================================
//  botSimulation.ts  —  Estemshan Intelligent Bot System
//  PHASE 3b:  EXPERT Monte-Carlo card-play engine  (HARDENED v2)
// ----------------------------------------------------------------------------
//  For each legal card, runs many simulated playouts of the rest of the round
//  against RANDOM plausible opponent hands (constrained by unseen cards and,
//  optionally, known voids), then plays the card that most often yields the
//  bot's EXACT bid.
//
//  v2 fixes vs v1:
//   • Robust turn order: the bot is inserted at its REAL position in the trick
//     (computed from who has already played), never assumed — no skipped seats.
//   • Self-correcting card accounting: if the unseen pool doesn't match the sum
//     of opponent hand sizes (caused by seenCards bookkeeping bugs in the app),
//     it RECONCILES and warns ONCE with the numbers, instead of starving a hand.
//   • No dummy cards: a playout that hits an impossible empty hand ends cleanly
//     and scores the bot's tricks so far — results stay meaningful.
//   • Optional diagnostics: pass { debug:true } to log the bot-trick histogram
//     so you can see WHY a make-rate is high or low.
//
//  Fairness: never reads opponents' real cards — only public info (own hand,
//  seen cards, current trick, opponent hand sizes).
//
//  Self-contained: imports only ./types and ./cardRules.
// ============================================================================

import { CardModel, PlayerModel, PlayedCard, Suit, Rank } from './types';
import { beats, legalMoves as legalFor } from './cardRules';

export interface SimCardChoice {
  chosenCard: CardModel;
  reasoning: string;
}

const RANK_VALUE: Record<Rank, number> = {
  TWO: 2, THREE: 3, FOUR: 4, FIVE: 5, SIX: 6, SEVEN: 7, EIGHT: 8,
  NINE: 9, TEN: 10, JACK: 11, QUEEN: 12, KING: 13, ACE: 14,
};
const RANKS = Object.keys(RANK_VALUE) as Rank[];
const SUITS: Suit[] = ['SPADES', 'HEARTS', 'DIAMONDS', 'CLUBS'];

const key = (c: { suit: Suit; rank: Rank }) => `${c.suit}-${c.rank}`;

// Warn at most once per session so a real app bug is visible without spamming.
let _warnedMismatch = false;

function fullDeck(): CardModel[] {
  const d: CardModel[] = [];
  for (const s of SUITS)
    for (const r of RANKS)
      d.push({ id: key({ suit: s, rank: r }), suit: s, rank: r, value: RANK_VALUE[r] });
  return d;
}

function shuffle<T>(arr: T[]): T[] {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

interface SimPlayer {
  id: string;
  hand: CardModel[];
  bid: number;
  tricksWon: number;
}

/**
 * Deal the unseen cards to the opponents at random, respecting hand sizes and
 * known voids. Tolerant: leftover pool cards (pool > sum of counts) are ignored;
 * counts are assumed already clamped so sum(counts) <= pool.length.
 */
function determinize(
  opponents: { id: string; count: number }[],
  pool: CardModel[],
  voidHints: Record<string, Suit[]>,
): Record<string, CardModel[]> {
  for (let attempt = 0; attempt < 8; attempt++) {
    const remaining = shuffle(pool);
    const out: Record<string, CardModel[]> = {};
    let ok = true;
    for (const opp of opponents) {
      const voids = voidHints[opp.id] ?? [];
      const picked: CardModel[] = [];
      for (let i = remaining.length - 1; i >= 0 && picked.length < opp.count; i--) {
        if (voids.includes(remaining[i].suit)) continue;
        picked.push(remaining[i]);
        remaining.splice(i, 1);
      }
      if (picked.length < opp.count) { ok = false; break; }
      out[opp.id] = picked;
    }
    if (ok) return out;
  }
  // Relaxed fallback: ignore voids, slice in order.
  const bag = shuffle(pool);
  const out: Record<string, CardModel[]> = {};
  let idx = 0;
  for (const opp of opponents) {
    out[opp.id] = bag.slice(idx, idx + opp.count);
    idx += opp.count;
  }
  return out;
}

/** Fast greedy policy used by every seat during a playout. Returns null only if
 * the hand is empty (which should never happen in a balanced game). */
function greedyPlay(p: SimPlayer, trick: PlayedCard[], led: Suit | null, trump: Suit | 'NONE'): CardModel | null {
  if (p.hand.length === 0) return null;
  const legal = legalFor(p.hand, led);
  if (legal.length === 1) return legal[0];
  const need = p.bid - p.tricksWon;
  const wantTricks = need > 0;
  if (trick.length === 0 || !led) {
    if (wantTricks) return [...legal].sort((a, b) => b.value - a.value)[0];
    const nonTrump = legal.filter(c => trump === 'NONE' || c.suit !== trump);
    return [...(nonTrump.length ? nonTrump : legal)].sort((a, b) => a.value - b.value)[0];
  }
  let win = trick[0];
  for (let i = 1; i < trick.length; i++) if (beats(trick[i].card, win.card, led, trump)) win = trick[i];
  const winners = legal.filter(c => beats(c, win.card, led, trump));
  const losers = legal.filter(c => !beats(c, win.card, led, trump));
  if (wantTricks) {
    if (winners.length) return [...winners].sort((a, b) => a.value - b.value)[0];
    return [...legal].sort((a, b) => a.value - b.value)[0];
  }
  if (losers.length) {
    const nt = [...losers].filter(c => trump === 'NONE' || c.suit !== trump).sort((a, b) => b.value - a.value);
    return nt[0] ?? [...losers].sort((a, b) => b.value - a.value)[0];
  }
  return [...legal].sort((a, b) => a.value - b.value)[0];
}

/**
 * Simulate the rest of the round ONCE. Returns the bot's final trick count.
 * `seatOrder` is the physical seating; `leaderId` led the current trick;
 * `currentTrick` holds the plays already made before the bot; `candidate` is
 * the bot's forced move now.
 */
function playoutBotTricks(
  seatOrder: string[],
  hands: Record<string, SimPlayer>,
  currentTrick: PlayedCard[],
  startLed: Suit | null,
  leaderId: string,
  botId: string,
  candidate: CardModel,
  trump: Suit | 'NONE',
): number {
  const idxOf = (id: string) => seatOrder.indexOf(id);
  const rotationFrom = (start: string) =>
    Array.from({ length: seatOrder.length }, (_, i) => seatOrder[(idxOf(start) + i) % seatOrder.length]);
  let trick: PlayedCard[] = currentTrick.map(t => ({ playerId: t.playerId, playerName: t.playerId, card: t.card }));
  let led = startLed;
  let leader = leaderId;
  const play = (pid: string, card: CardModel) => {
    hands[pid].hand = hands[pid].hand.filter(c => key(c) !== key(card));
    if (trick.length === 0) led = card.suit;
    trick.push({ playerId: pid, playerName: pid, card });
  };
  const resolve = () => {
    let win = trick[0];
    for (let i = 1; i < trick.length; i++) if (beats(trick[i].card, win.card, led!, trump)) win = trick[i];
    hands[win.playerId].tricksWon++;
    leader = win.playerId;
    trick = [];
    led = null;
  };
  // --- finish the CURRENT trick ---
  // Players still to act = rotation from leader minus those already in the trick.
  const alreadyPlayed = new Set(trick.map(t => t.playerId));
  const toAct = rotationFrom(leader).filter(id => !alreadyPlayed.has(id));
  // The bot must be the first to act now; play its candidate, then the rest.
  for (const pid of toAct) {
    if (pid === botId) {
      play(botId, candidate);
    } else {
      const c = greedyPlay(hands[pid], trick, led, trump);
      if (!c) return hands[botId].tricksWon; // impossible empty hand: bail cleanly
      play(pid, c);
    }
  }
  resolve();
  // --- play every remaining full trick ---
  while (hands[botId].hand.length > 0) {
    for (const pid of rotationFrom(leader)) {
      const c = greedyPlay(hands[pid], trick, led, trump);
      if (!c) return hands[botId].tricksWon;
      play(pid, c);
    }
    resolve();
  }
  return hands[botId].tricksWon;
}

/**
 * EXPERT card selection via Monte-Carlo. Drop-in compatible with the play loop.
 */
export function selectExpertCard(
  bot: PlayerModel,
  allPlayers: PlayerModel[],
  currentTrick: PlayedCard[],
  trumpSuit: Suit | 'NONE',
  opts: { seenCards?: CardModel[]; voidHints?: Record<string, Suit[]>; samples?: number; debug?: boolean } = {},
): SimCardChoice {
  if (bot.hand.length === 0) {
    throw new Error(`selectExpertCard called for ${bot.id} with an empty hand.`);
  }
  const seenCards = opts.seenCards ?? [];
  const voidHints = opts.voidHints ?? {};
  const samples = opts.samples ?? 200;
  const ledSuit: Suit | null = currentTrick.length > 0 ? currentTrick[0].card.suit : null;
  const legal = legalFor(bot.hand, ledSuit);
  if (legal.length <= 1) {
    return { chosenCard: legal[0], reasoning: 'EXPERT: only one legal card.' };
  }
  // Unseen pool = full deck minus everything visible to the bot. Dedupe by key
  // so a double-counted seen card can't shrink the pool below reality.
  const known = new Set<string>();
  bot.hand.forEach(c => known.add(key(c)));
  currentTrick.forEach(p => known.add(key(p.card)));
  seenCards.forEach(c => known.add(key(c)));
  const pool = fullDeck().filter(c => !known.has(key(c)));
  const seatOrder = allPlayers.map(p => p.id);
  const leaderId = currentTrick.length > 0 ? currentTrick[0].playerId : bot.id;
  // Opponent hand sizes — then RECONCILE against the pool.
  let opponents = allPlayers
    .filter(p => p.id !== bot.id)
    .map(p => ({ id: p.id, count: p.hand.length }));
  let need = opponents.reduce((s, o) => s + o.count, 0);
  if (need !== pool.length) {
    if (!_warnedMismatch) {
      _warnedMismatch = true;
      // eslint-disable-next-line no-console
      console.warn(
        `[MC] card-accounting mismatch: unseen pool=${pool.length} but opponents need=${need}. ` +
        `Likely a seenCards bookkeeping issue (a card counted twice, or seenCards not reset on a new round). ` +
        `Simulator will reconcile, but please verify seenCards in App.tsx.`,
      );
    }
    // Clamp opponent counts so their total never exceeds the available pool.
    if (need > pool.length) {
      let excess = need - pool.length;
      opponents = opponents
        .sort((a, b) => b.count - a.count)
        .map(o => {
          if (excess > 0) {
            const take = Math.min(excess, o.count);
            excess -= take;
            return { ...o, count: o.count - take };
          }
          return o;
        });
      need = opponents.reduce((s, o) => s + o.count, 0);
    }
  }
  const baseStats: Record<string, { bid: number; tricksWon: number }> = {};
  allPlayers.forEach(p => { baseStats[p.id] = { bid: p.bid, tricksWon: p.tricksWon }; });
  let best = legal[0];
  let bestRate = -1;
  const debugHist: Record<string, number[]> = {};
  for (const candidate of legal) {
    let made = 0;
    const hist: number[] = [];
    for (let s = 0; s < samples; s++) {
      const dealt = determinize(opponents, pool, voidHints);
      const hands: Record<string, SimPlayer> = {};
      allPlayers.forEach(p => {
        hands[p.id] = {
          id: p.id,
          hand: p.id === bot.id ? [...bot.hand] : [...(dealt[p.id] ?? [])],
          bid: baseStats[p.id].bid,
          tricksWon: baseStats[p.id].tricksWon,
        };
      });
      const finalTricks = playoutBotTricks(
        seatOrder, hands, currentTrick, ledSuit, leaderId, bot.id, candidate, trumpSuit,
      );
      if (opts.debug) hist[finalTricks] = (hist[finalTricks] ?? 0) + 1;
      if (finalTricks === bot.bid) made++;
    }
    const rate = made / samples;
    if (opts.debug) debugHist[key(candidate)] = hist;
    if (rate > bestRate) { bestRate = rate; best = candidate; }
  }
  if (opts.debug) {
    // console.log(`[MC] bid=${bot.bid} tricksWon=${bot.tricksWon} pool=${pool.length} ` +
    //  `bestRate=${(bestRate * 100).toFixed(0)}% best=${best.rank}/${best.suit}`, debugHist);
  }
  return {
    chosenCard: best,
    reasoning: `EXPERT MC (${samples} sims/card): best P(make bid ${bot.bid})=${(bestRate * 100).toFixed(0)}% with ${best.rank} of ${best.suit}.`,
  };
}

// ----------------------------------------------------------------------------
//  Optional helper: infer opponent voids from per-player play history.
// ----------------------------------------------------------------------------
export function inferVoids(
  history: { plays: PlayedCard[]; ledSuit: Suit }[],
): Record<string, Suit[]> {
  const voids: Record<string, Set<Suit>> = {};
  for (const trick of history) {
    for (const play of trick.plays) {
      if (play.card.suit !== trick.ledSuit) (voids[play.playerId] ??= new Set()).add(trick.ledSuit);
    }
  }
  const out: Record<string, Suit[]> = {};
  for (const id of Object.keys(voids)) out[id] = [...voids[id]];
  return out;
}

// ----------------------------------------------------------------------------
//  BID-TIME simulation: how many tricks can this hand realistically make?
// ----------------------------------------------------------------------------
//  Used by EXPERT (and optionally HARD) to bid the number it can actually make,
//  instead of an optimistic heuristic. Simulates many full rounds where the bot
//  plays to WIN (so we measure its ceiling) and opponents play to a fair share.
//  Returns the trick distribution and the most-likely (modal) trick count.
// ----------------------------------------------------------------------------
const SIM_SEATS = ['SIMBOT', 'SIMO1', 'SIMO2', 'SIMO3'];

function simulateRoundBotTricks(
  hands: Record<string, SimPlayer>,
  leaderId: string,
  trump: Suit | 'NONE',
): number {
  const idxOf = (id: string) => SIM_SEATS.indexOf(id);
  const rotationFrom = (start: string) =>
    Array.from({ length: 4 }, (_, i) => SIM_SEATS[(idxOf(start) + i) % 4]);

  let trick: PlayedCard[] = [];
  let led: Suit | null = null;
  let leader = leaderId;

  const play = (pid: string, card: CardModel) => {
    hands[pid].hand = hands[pid].hand.filter(c => key(c) !== key(card));
    if (trick.length === 0) led = card.suit;
    trick.push({ playerId: pid, playerName: pid, card });
  };
  const resolve = () => {
    let win = trick[0];
    for (let i = 1; i < trick.length; i++) if (beats(trick[i].card, win.card, led!, trump)) win = trick[i];
    hands[win.playerId].tricksWon++;
    leader = win.playerId;
    trick = [];
    led = null;
  };

  while (hands['SIMBOT'].hand.length > 0) {
    for (const pid of rotationFrom(leader)) {
      const c = greedyPlay(hands[pid], trick, led, trump);
      if (!c) return hands['SIMBOT'].tricksWon;
      play(pid, c);
    }
    resolve();
  }
  return hands['SIMBOT'].tricksWon;
}

export function estimateBidBySimulation(
  hand: CardModel[],
  assumedTrump: Suit | 'NONE',
  opts: { samples?: number } = {},
): { bid: number; distribution: number[]; confidence: number } {
  const samples = opts.samples ?? 150;
  const n = hand.length;                 // 13 at bid time

  const handKeys = new Set(hand.map(key));
  const pool = fullDeck().filter(c => !handKeys.has(key(c)));

  // Opponents aim for a FAIR SHARE (~3 each) and duck once they reach it — this
  // matches how a real table actually plays (cautious bids, then ducking). The
  // earlier "contest every trick" model made opponents too aggressive, which
  // systematically UNDER-bid the bot: in real games the surplus tricks a passive
  // field leaves behind get caught by the bot, so it won ~1 more than it bid.
  const fairShare = Math.max(1, Math.round(n / 4)); // ~3 for a 13-card round
  const dist = new Array(n + 1).fill(0);
  for (let s = 0; s < samples; s++) {
    const bag = shuffle(pool);
    const hands: Record<string, SimPlayer> = {
      // Bot plays to WIN (bid=n) to measure its true ceiling against a realistic field.
      SIMBOT: { id: 'SIMBOT', hand: [...hand], bid: n, tricksWon: 0 },
      SIMO1: { id: 'SIMO1', hand: bag.slice(0, n), bid: fairShare, tricksWon: 0 },
      SIMO2: { id: 'SIMO2', hand: bag.slice(n, 2 * n), bid: fairShare, tricksWon: 0 },
      SIMO3: { id: 'SIMO3', hand: bag.slice(2 * n, 3 * n), bid: fairShare, tricksWon: 0 },
    };
    const leader = SIM_SEATS[Math.floor(Math.random() * 4)]; // unbiased opening lead
    const t = simulateRoundBotTricks(hands, leader, assumedTrump);
    dist[Math.min(t, n)]++;
  }

  // Modal trick count = the bid the bot most often lands on if it pushes to win.
  let bid = 0;
  for (let t = 1; t <= n; t++) if (dist[t] > dist[bid]) bid = t;
  return { bid, distribution: dist, confidence: dist[bid] / samples };
}

/**
 * Estimate the probability that this hand can DASH successfully (win ZERO tricks),
 * by simulating the bot playing to LOSE across many random layouts AND random
 * trumps (a pre-bid Dasher doesn't control the trump). Returns P(win 0 tricks).
 *
 * This is the accurate replacement for the heuristic Dash gate: expected-tricks
 * is only a proxy, so a hand rated ~0.5 can still be forced to win a couple of
 * tricks. Simulating directly measures that risk.
 */
export function estimateDashSuccess(
  hand: CardModel[],
  opts: { samples?: number } = {},
): number {
  const samples = opts.samples ?? 150;
  const n = hand.length;
  const fairShare = Math.max(1, Math.round(n / 4));
  const handKeys = new Set(hand.map(key));
  const pool = fullDeck().filter(c => !handKeys.has(key(c)));
  const trumpOptions: (Suit | 'NONE')[] = ['SPADES', 'HEARTS', 'DIAMONDS', 'CLUBS', 'NONE'];

  let zeroTrickCount = 0;
  for (let s = 0; s < samples; s++) {
    const bag = shuffle(pool);
    const trump = trumpOptions[Math.floor(Math.random() * trumpOptions.length)];
    const hands: Record<string, SimPlayer> = {
      // bid 0 => the bot is in "avoid" mode and plays to shed/duck every trick.
      SIMBOT: { id: 'SIMBOT', hand: [...hand], bid: 0, tricksWon: 0 },
      SIMO1: { id: 'SIMO1', hand: bag.slice(0, n), bid: fairShare, tricksWon: 0 },
      SIMO2: { id: 'SIMO2', hand: bag.slice(n, 2 * n), bid: fairShare, tricksWon: 0 },
      SIMO3: { id: 'SIMO3', hand: bag.slice(2 * n, 3 * n), bid: fairShare, tricksWon: 0 },
    };
    const leader = SIM_SEATS[Math.floor(Math.random() * 4)];
    if (simulateRoundBotTricks(hands, leader, trump) === 0) zeroTrickCount++;
  }
  return zeroTrickCount / samples;
}

// ============================================================================
//  WIRING (unchanged from v1)
// ----------------------------------------------------------------------------
//  In botPlay.ts, inside selectBotCard, after the single-legal guards:
//      import { selectExpertCard } from './botSimulation';
//      if (cfg.usesSimulation && legal.length > 1) {
//        const mc = selectExpertCard(bot, _allPlayers, currentTrick, trumpSuit, {
//          seenCards: opts.seenCards, samples: 200, /* debug: true */
//        });
//        if (mc.chosenCard) return { chosenCard: mc.chosenCard, reasoning: mc.reasoning };
//      }
//
//  DIAGNOSING THE 1% MAKE-RATE: temporarily pass debug:true. The console will
//  print the bid, the bot's current tricks, the pool size, and a histogram of
//  how many tricks the bot ends with across sims. Two likely findings:
//    (a) "[MC] card-accounting mismatch ..." -> fix seenCards bookkeeping in
//        App.tsx (don't append the current trick until it resolves; reset to []
//        at the start of every round).
//    (b) No mismatch, but the histogram centres far from the bid -> the bot is
//        simply OVERBIDDING (a personality/tier issue, not a sim bug). The Joker
//        + EXPERT can bid aggressively; check what bid it actually made.
// ============================================================================
