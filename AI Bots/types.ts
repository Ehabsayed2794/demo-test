export type Suit = 'HEARTS' | 'DIAMONDS' | 'CLUBS' | 'SPADES';

export type Rank = 
  | 'TWO' | 'THREE' | 'FOUR' | 'FIVE' | 'SIX' | 'SEVEN' | 'EIGHT' | 'NINE' | 'TEN'
  | 'JACK' | 'QUEEN' | 'KING' | 'ACE';

export type DifficultyLevel = 'EASY' | 'MEDIUM' | 'HARD' | 'EXPERT';

export type ScoringMode = 'NORMAL' | 'CLASSIC';

export interface CardModel {
  id: string;
  suit: Suit;
  rank: Rank;
  value: number; // 2 to 14
}

import { Personality } from './botPersonality';

export interface PlayerModel {
  id: string;
  name: string;
  hand: CardModel[];
  bid: number; // -1 means no bid yet
  isDashCall: boolean;
  isBiddingActive: boolean;
  isWazz?: boolean;
  tricksWon: number;
  score: number;
  isUser: boolean;
  hasAvoid?: boolean;
  difficulty?: DifficultyLevel;
  personality?: Personality;
}

export interface PlayedCard {
  playerId: string;
  playerName: string;
  card: CardModel;
}

// Added 'DASH_CALL_DECISION' to match our new game flow
export type GamePhase = 
  | 'DEALING' 
  | 'HAND_REVEAL' 
  | 'DASH_CALL_DECISION' 
  | 'CALL_PHASE' 
  | 'TRUMP_DECLARATION'
  | 'FOLLOWING_BIDS' 
  | 'PLAYING' 
  | 'ROUND_OVER'
  | 'GAME_OVER';

export interface RoundPlayerStat {
  id: string;
  name: string;
  bid: number;
  isDashCall: boolean;
  tricksWon: number;
  finalScore: number;
  newCumulativeScore: number;
  isSuccess: boolean;
}

export interface RoundStat {
  round: number;
  trumpSuit: Suit | 'NONE';
  multiplier: number;
  playerStats: RoundPlayerStat[];
}

export interface LogEntry {
  message: string;
  stats?: RoundStat;
}

export interface AndroidProjectFile {
  name: string;
  path: string;
  language: 'kotlin' | 'xml' | 'gradle';
  category: 'model' | 'engine' | 'ui' | 'di' | 'config';
  code: string;
  description: string;
}