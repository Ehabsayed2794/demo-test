export type PlayerRole =
  | 'NORMAL'
  | 'CALLER'
  | 'WIZZ'
  | 'RISK'
  | 'WIZZ_RISK'
  | 'SUPER_CALL'
  | 'DASH_CALL'
  | 'REG_DASH';

export type ScoringMode = 'NORMAL' | 'CLASSIC';

export interface Player {
  id: number;
  name: string;
  totalScore: number;
}

export interface RoundPlayerData {
  playerId: number;
  role: PlayerRole;
  bid: number;
  won: number;
}

export interface CompletedRound {
  roundNumber: number;
  maxTricks: number;
  totalBids: number;
  isSaayda: boolean;
  players: RoundPlayerData[];
  scores: Record<number, number>;
}

export interface CurrentRoundInput {
  roundNumber: number;
  maxTricks: number;
  isSaayda: boolean;
  players: Partial<RoundPlayerData>[];
}

export interface GameState {
  players: Player[];
  completedRounds: CompletedRound[];
  currentRound: CurrentRoundInput | null;
  scoringMode: ScoringMode;
  gameStarted: boolean;
}
