import type { PlayerRole, RoundPlayerData, ScoringMode } from './types';

// --- NORMAL MODE SCORING ---
// Per the canonical rules doc (uploads/Estimation_Rules_v2_SingleSourceOfTruth.docx
// §4 "Official Scoring System") and design-ui/engine/scoring-engine.js
// (the reference implementation, NOT modified by this fix — see
// docs/bugs/Scoring-Divergence-Analysis.md for the full before/after):
// success: 10 + bid (= 10 + T, since T===bid on success)
// fail: -(|bid - won|)
// Caller bonus: +10 on success, -10 on fail (stacks)
// Wizz bonus: +10 on success, -10 on fail (stacks with Caller)
// Risk bonus: +10 on success, -10 on fail (stacks)
// WizzRisk: Wizz + Risk bonuses together (both +10 or both -10)
// SUPER_CALL: NOT a distinct scoring role in Normal mode — the doc's
// scoring section has no Super Call special case; a Super Call bidder
// is scored as an ordinary Caller via the SAME ballpark formula below
// (Super Call only changes auction/confirmation behavior, per §2.2,
// never Normal-mode point value). The previous fixed +20/-20 here was
// an invented rule with no basis in the doc — REMOVED.
// DASH_CALL (pre-bid Dash Call, §4 "Dash Call — Flat Scoring"): flat
// +33/-33 if the round's total bids finished AT OR UNDER 13 ("Under"),
// +25/-25 if OVER 13 ("Over") — independent of `won`, never Risk.
// REG_DASH (Normal Dash, a 0-estimate during Estimation, §4 "Normal
// Dash"): ordinary 10+T logic — success (0 tricks) is +10; failure is
// -(10 + tricks taken), NOT just -tricks.

function calcNormalScore(
  role: PlayerRole,
  bid: number,
  won: number,
  isSoleWinner: boolean,
  isSoleLoser: boolean,
  totalBids: number,
): number {
  const success = won === bid;

  let score: number;

  if (role === 'DASH_CALL') {
    // Flat scoring, independent of `won` — a pre-bid Dash Call never
    // depends on how many tricks were actually taken beyond win/lose.
    const isUnder = totalBids <= 13;
    score = success ? (isUnder ? 33 : 25) : (isUnder ? -33 : -25);
  } else if (role === 'REG_DASH') {
    score = success ? 10 : -(10 + won);
  } else {
    const miss = Math.abs(bid - won);
    // SUPER_CALL is included alongside CALLER here — matching
    // scoring-engine.js's TRICKS-branch treatment (isCaller is a raw
    // "is this seat the caller" boolean there, never role-gated), a
    // Super Call bidder IS the Caller, just one who bid 8+; it is not
    // a separate scoring category in Normal mode.
    // BUG FIX (Sprint 4.1, found via test — see tests/scoring-correction.test.cjs
    // #10): this used to add a SEPARATE `callerBonus` (10, gated on
    // CALLER/WIZZ/WIZZ_RISK) AND `wizzBonus` (10, gated on WIZZ/WIZZ_RISK),
    // double-counting +20 for a plain WIZZ. The canonical rule (§4:
    // "+10 if the player is the Caller OR a With") and
    // scoring-engine.js's own `if (isCaller || isWith) delta += 10`
    // both apply exactly ONE +10 for either role, never both — a
    // single `callerOrWithBonus` now covers CALLER/WIZZ/SUPER_CALL,
    // and Risk is the only bonus that ever stacks on top of it
    // (WIZZ_RISK = callerOrWithBonus + riskBonus, matching §4's
    // "Combined With + Risk" section).
    if (success) {
      const base = 10 + bid;
      const callerOrWithBonus =
        role === 'CALLER' || role === 'SUPER_CALL' || role === 'WIZZ' || role === 'WIZZ_RISK' ? 10 : 0;
      const riskBonus = role === 'RISK' || role === 'WIZZ_RISK' ? 10 : 0;
      score = base + callerOrWithBonus + riskBonus;
    } else {
      const callerOrWithPenalty =
        role === 'CALLER' || role === 'SUPER_CALL' || role === 'WIZZ' || role === 'WIZZ_RISK' ? 10 : 0;
      const riskPenalty = role === 'RISK' || role === 'WIZZ_RISK' ? 10 : 0;
      score = -(miss + callerOrWithPenalty + riskPenalty);
    }
  }

  if (success && isSoleWinner) score += 10;
  if (!success && isSoleLoser) score = Math.max(score * 2, -22);

  return score;
}

// --- CLASSIC MODE SCORING ---
// Based on reverse-engineered formula verified against 18 rounds of real game data.
//
// SUCCESS (won === bid):
//   Normal:         bid + 13
//   Caller/Wizz:    bid + 13 + 10
//   Risk:           bid + 13 + 10
//   WizzRisk:       bid + 13 + 10 + 10 = bid + 33
//   SuperCall:      +42 (fixed)
//   DashCall Under: +33 (fixed)   [totalBids < 13]
//   DashCall Over:  +23 (fixed)   [totalBids > 13]
//   RegDash Under:  +23 (fixed)
//   RegDash Over:   +13 (fixed)
//   + Sole Winner:  +10 bonus
//
// FAILURE (won !== bid):
//   Normal:         -|won - bid|
//   Caller/Wizz:    -(|won - bid| + callerPenalty)
//     where callerPenalty = 10 normally, 20 when totalBids <= 11 (under by 2+)
//   Risk:           -(|won - bid| + 10)
//   WizzRisk:       -(|won - bid| + 20)
//   SuperCall:      -20 (fixed)
//   DashCall Under: -20 (fixed)
//   DashCall Over:  -10 (fixed)
//   RegDash Under:  -10 (fixed)
//   RegDash Over:   -|won| (tricks they took, penalised)
//   + Sole Loser:   base_fail × 2, capped at -22

function calcClassicScore(
  role: PlayerRole,
  bid: number,
  won: number,
  totalBids: number,
  isSoleWinner: boolean,
  isSoleLoser: boolean,
): number {
  const success = won === bid;
  const isGameOver = totalBids > 13;
  const isDeepUnder = totalBids <= 11; // under by 2+ → caller penalty doubles

  const miss = Math.abs(won - bid);

  let score: number;

  if (role === 'SUPER_CALL') {
    score = success ? 42 : -20;
  } else if (role === 'DASH_CALL') {
    if (success) {
      score = isGameOver ? 23 : 33;
    } else {
      score = isGameOver ? -10 : -20;
    }
  } else if (role === 'REG_DASH') {
    if (success) {
      score = isGameOver ? 13 : 23;
    } else {
      score = isGameOver ? -won : -10;
    }
  } else if (role === 'WIZZ_RISK') {
    if (success) {
      score = bid + 13 + 10 + 10;
    } else {
      score = -(miss + 10 + 10);
    }
  } else if (role === 'CALLER' || role === 'WIZZ') {
    if (success) {
      score = bid + 13 + 10;
    } else {
      const callerPenalty = isDeepUnder ? 20 : 10;
      score = -(miss + callerPenalty);
    }
  } else if (role === 'RISK') {
    if (success) {
      score = bid + 13 + 10;
    } else {
      score = -(miss + 10);
    }
  } else {
    // NORMAL player
    if (success) {
      score = bid + 13;
    } else {
      score = -miss;
    }
  }

  if (success && isSoleWinner) score += 10;
  if (!success && isSoleLoser) score = Math.max(score * 2, -22);

  return score;
}

// --- ROUND SCORES CALCULATOR ---
export function calculateRoundScores(
  players: RoundPlayerData[],
  totalBids: number,
  scoringMode: ScoringMode,
): Record<number, number> {
  const successCount = players.filter((p) => p.won === p.bid).length;
  const failCount = players.filter((p) => p.won !== p.bid).length;

  const isSoleWinner = (p: RoundPlayerData) =>
    successCount === 1 && p.won === p.bid;
  const isSoleLoser = (p: RoundPlayerData) =>
    failCount === 1 && p.won !== p.bid;

  const scores: Record<number, number> = {};

  for (const p of players) {
    if (scoringMode === 'CLASSIC') {
      scores[p.playerId] = calcClassicScore(
        p.role,
        p.bid,
        p.won,
        totalBids,
        isSoleWinner(p),
        isSoleLoser(p),
      );
    } else {
      scores[p.playerId] = calcNormalScore(
        p.role,
        p.bid,
        p.won,
        isSoleWinner(p),
        isSoleLoser(p),
        totalBids,
      );
    }
  }

  return scores;
}

// Sa'ayda multiplies total accumulated scores by 2 at the end of the sa'ayda round.
// The caller does not score during sa'ayda rounds; scores are suspended and doubled at end.
// Implementation: the round score is 0, and after sa'ayda ends, current totals ×2.
// Simpler approach used here: sa'ayda rounds contribute 0 to score, and when sa'ayda flag
// toggles off, the existing total is doubled. This matches the competitor app behaviour.

export const ROUND_MAX_TRICKS: number[] = [
  1, 2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3, 2, 1, 1, 1, 1,
];

export function getRoleLabel(role: PlayerRole): string {
  const labels: Record<PlayerRole, string> = {
    NORMAL: 'Normal',
    CALLER: 'Caller',
    WIZZ: 'Wizz',
    RISK: 'Risk',
    WIZZ_RISK: 'Wizz+Risk',
    SUPER_CALL: 'Super Call',
    DASH_CALL: 'Dash Call',
    REG_DASH: 'Dash',
  };
  return labels[role];
}
