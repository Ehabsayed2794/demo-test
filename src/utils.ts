import type { PlayerRole, RoundPlayerData, ScoringMode } from './types';

// --- NORMAL MODE SCORING ---
// Official Scoring System (Standard Players):
// success: 10 + bid (+10 Caller/Wizz/Risk bonuses if applicable)
// failure: -|bid - won| (-10 Caller/Wizz/Risk penalties if applicable)
// Sole Winner: +10 bonus on success
// Sole Loser: score × 2, capped at -22
// Super Call: uses Standard formula (no special ±20 bonus in Official mode)
// Pre-Bidding Dash Call (DASH_CALL): Under (<13) ±33, Over (>13) ±25, never receives Risk
// Normal Dash (REG_DASH): success +10, failure -(10 + tricks), can receive Risk

function calcNormalScore(
  role: PlayerRole,
  bid: number,
  won: number,
  totalBids: number,
  isSoleWinner: boolean,
  isSoleLoser: boolean,
): number {
  const success = won === bid;
  
  let score: number;

  if (role === 'SUPER_CALL') {
    // Official Super Call uses Standard Player formula with Caller bonus
    const miss = Math.abs(bid - won);
    if (success) {
      const base = 10 + bid;
      score = base + 10; // Caller bonus
    } else {
      score = -(miss + 10); // Caller penalty
    }
  } else if (role === 'DASH_CALL') {
    // Pre-Bidding Dash Call: NEVER receives Risk
    // Under (totalBids < 13): ±33
    // Over (totalBids > 13): ±25
    // totalBids === 13 is invalid rules state
    const dashValue = totalBids < 13 ? 33 : 25;
    score = success ? dashValue : -dashValue;
  } else if (role === 'REG_DASH') {
    // Normal Dash during Estimation: standard formula WITH Risk
    // success: +10 (+ Risk bonus if applicable)
    // failure: -(10 + tricks) (- Risk penalty if applicable)
    if (success) {
      score = 10;
    } else {
      score = -(10 + won);
    }
  } else {
    // Standard players: NORMAL, CALLER, WIZZ, RISK, WIZZ_RISK
    const miss = Math.abs(bid - won);
    if (success) {
      const base = 10 + bid;
      const callerBonus =
        role === 'CALLER' || role === 'WIZZ' || role === 'WIZZ_RISK' ? 10 : 0;
      const wizzBonus = role === 'WIZZ' || role === 'WIZZ_RISK' ? 10 : 0;
      const riskBonus = role === 'RISK' || role === 'WIZZ_RISK' ? 10 : 0;
      score = base + callerBonus + wizzBonus + riskBonus;
    } else {
      const callerPenalty =
        role === 'CALLER' || role === 'WIZZ' || role === 'WIZZ_RISK' ? 10 : 0;
      const riskPenalty = role === 'RISK' || role === 'WIZZ_RISK' ? 10 : 0;
      score = -(miss + callerPenalty + riskPenalty);
    }
  }

  if (success && isSoleWinner) score += 10;
  if (!success && isSoleLoser) score = Math.max(score * 2, -22);

  return score;
}
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
        totalBids,
        isSoleWinner(p),
        isSoleLoser(p),
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
