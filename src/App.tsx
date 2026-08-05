import { useState } from 'react';
import type {
  GameState,
  PlayerRole,
  RoundPlayerData,
  ScoringMode,
  CompletedRound,
} from './types';
import { calculateRoundScores, getRoleLabel, ROUND_MAX_TRICKS } from './utils';

const PLAYER_ROLES: PlayerRole[] = [
  'NORMAL',
  'CALLER',
  'WIZZ',
  'RISK',
  'WIZZ_RISK',
  'SUPER_CALL',
  'DASH_CALL',
  'REG_DASH',
];

const DEFAULT_PLAYER_NAMES = ['Player 1', 'Player 2', 'Player 3', 'Player 4'];

function initGame(names: string[], mode: ScoringMode): GameState {
  return {
    players: names.map((name, i) => ({ id: i + 1, name, totalScore: 0 })),
    completedRounds: [],
    currentRound: null,
    scoringMode: mode,
    gameStarted: true,
  };
}

function ScoreBadge({ score }: { score: number }) {
  const color =
    score > 0 ? 'text-green-400' : score < 0 ? 'text-red-400' : 'text-gray-400';
  return (
    <span className={`font-bold ${color}`}>
      {score > 0 ? '+' : ''}
      {score}
    </span>
  );
}

function emptyInputs() {
  return Array.from({ length: 4 }, () => ({
    role: 'NORMAL' as PlayerRole,
    bid: '',
    won: '',
  }));
}

export default function App() {
  const [setupMode, setSetupMode] = useState(true);
  const [playerNames, setPlayerNames] = useState(DEFAULT_PLAYER_NAMES);
  const [scoringMode, setScoringMode] = useState<ScoringMode>('NORMAL');
  const [game, setGame] = useState<GameState | null>(null);
  const [saayda, setSaayda] = useState(false);
  const [saaydaActive, setSaaydaActive] = useState(false);
  const [roundInputs, setRoundInputs] = useState(emptyInputs());
  const [roundError, setRoundError] = useState('');

  const completedCount = game?.completedRounds.length ?? 0;
  const currentRoundIdx = Math.min(completedCount, 17);
  const maxTricks = ROUND_MAX_TRICKS[currentRoundIdx];
  const roundNumber = currentRoundIdx + 1;
  const gameOver = completedCount >= 18;

  function startGame() {
    const names = playerNames.map((n, i) => n.trim() || `Player ${i + 1}`);
    setGame(initGame(names, scoringMode));
    setSetupMode(false);
    setSaayda(false);
    setSaaydaActive(false);
    setRoundInputs(emptyInputs());
    setRoundError('');
  }

  function updateInput(
    playerIdx: number,
    field: 'role' | 'bid' | 'won',
    value: string,
  ) {
    setRoundInputs((prev) => {
      const next = [...prev];
      next[playerIdx] = { ...next[playerIdx], [field]: value };
      return next;
    });
    setRoundError('');
  }

  function submitRound() {
    if (!game) return;

    for (let i = 0; i < 4; i++) {
      const inp = roundInputs[i];
      const bid = parseInt(inp.bid);
      const won = parseInt(inp.won);
      if (isNaN(bid) || isNaN(won)) {
        setRoundError(`Fill in bid and tricks won for ${game.players[i].name}`);
        return;
      }
      if (bid < 0 || bid > maxTricks) {
        setRoundError(`${game.players[i].name}: bid must be 0–${maxTricks}`);
        return;
      }
      if (won < 0 || won > maxTricks) {
        setRoundError(`${game.players[i].name}: tricks won must be 0–${maxTricks}`);
        return;
      }
    }

    const totalWon = roundInputs.reduce((s, p) => s + parseInt(p.won), 0);
    if (totalWon !== maxTricks) {
      setRoundError(
        `Total tricks won must equal ${maxTricks} (currently ${totalWon})`,
      );
      return;
    }

    const totalBids = roundInputs.reduce((s, p) => s + parseInt(p.bid), 0);
    const playerData: RoundPlayerData[] = roundInputs.map((inp, i) => ({
      playerId: game.players[i].id,
      role: inp.role,
      bid: parseInt(inp.bid),
      won: parseInt(inp.won),
    }));

    const roundScores = saayda
      ? Object.fromEntries(game.players.map((p) => [p.id, 0]))
      : calculateRoundScores(playerData, totalBids, game.scoringMode);

    const completedRound: CompletedRound = {
      roundNumber,
      maxTricks,
      totalBids,
      isSaayda: saayda,
      players: playerData,
      scores: roundScores,
    };

    setGame((prev) => {
      if (!prev) return prev;
      const updatedPlayers = prev.players.map((p) => ({
        ...p,
        totalScore: p.totalScore + (roundScores[p.id] ?? 0),
      }));
      return {
        ...prev,
        players: updatedPlayers,
        completedRounds: [...prev.completedRounds, completedRound],
      };
    });

    if (saayda) setSaaydaActive(true);
    setSaayda(false);
    setRoundInputs(emptyInputs());
    setRoundError('');
  }

  function applyDoubling() {
    setGame((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        players: prev.players.map((p) => ({ ...p, totalScore: p.totalScore * 2 })),
      };
    });
    setSaaydaActive(false);
  }

  function resetGame() {
    setSetupMode(true);
    setGame(null);
    setRoundInputs(emptyInputs());
    setRoundError('');
    setSaayda(false);
    setSaaydaActive(false);
  }

  // ── Setup Screen ──────────────────────────────────────────────
  if (setupMode) {
    return (
      <div className="min-h-screen bg-gray-900 text-white flex items-center justify-center p-4">
        <div className="w-full max-w-md bg-gray-800 rounded-2xl p-6 shadow-xl">
          <h1 className="text-3xl font-bold text-center mb-1">Estemshan</h1>
          <p className="text-gray-400 text-center mb-6">Score Tracker</p>

          <div className="mb-6">
            <p className="text-sm text-gray-400 mb-2">Scoring Mode</p>
            <div className="flex rounded-xl overflow-hidden border border-gray-600">
              <button
                className={`flex-1 py-2 text-sm font-semibold transition-colors ${
                  scoringMode === 'NORMAL'
                    ? 'bg-blue-600 text-white'
                    : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
                }`}
                onClick={() => setScoringMode('NORMAL')}
              >
                Normal
              </button>
              <button
                className={`flex-1 py-2 text-sm font-semibold transition-colors ${
                  scoringMode === 'CLASSIC'
                    ? 'bg-amber-600 text-white'
                    : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
                }`}
                onClick={() => setScoringMode('CLASSIC')}
              >
                Classic
              </button>
            </div>
            {scoringMode === 'CLASSIC' && (
              <p className="text-xs text-amber-400 mt-1">
                Classic mode — success base +13, normal fail = -1
              </p>
            )}
          </div>

          <div className="space-y-3 mb-6">
            <p className="text-sm text-gray-400">Player Names</p>
            {playerNames.map((name, i) => (
              <input
                key={i}
                className="w-full bg-gray-700 rounded-lg px-3 py-2 text-white placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder={`Player ${i + 1}`}
                value={name}
                onChange={(e) => {
                  const next = [...playerNames];
                  next[i] = e.target.value;
                  setPlayerNames(next);
                }}
              />
            ))}
          </div>

          <button
            className="w-full bg-blue-600 hover:bg-blue-500 text-white font-bold py-3 rounded-xl transition-colors"
            onClick={startGame}
          >
            Start Game
          </button>
        </div>
      </div>
    );
  }

  if (!game) return null;

  // ── Game Over Screen ──────────────────────────────────────────
  if (gameOver) {
    const sorted = [...game.players].sort((a, b) => b.totalScore - a.totalScore);
    return (
      <div className="min-h-screen bg-gray-900 text-white flex items-center justify-center p-4">
        <div className="w-full max-w-md bg-gray-800 rounded-2xl p-6 shadow-xl text-center">
          <h2 className="text-2xl font-bold mb-4">Game Complete!</h2>
          <ol className="space-y-2 mb-6">
            {sorted.map((p, i) => (
              <li
                key={p.id}
                className="flex justify-between items-center bg-gray-700 rounded-lg px-4 py-2"
              >
                <span className="font-bold">
                  {i === 0 ? '🏆 ' : `${i + 1}. `}
                  {p.name}
                </span>
                <ScoreBadge score={p.totalScore} />
              </li>
            ))}
          </ol>
          <button
            className="bg-blue-600 hover:bg-blue-500 text-white font-bold py-2 px-6 rounded-xl transition-colors"
            onClick={resetGame}
          >
            Play Again
          </button>
        </div>
      </div>
    );
  }

  // ── Active Game Screen ────────────────────────────────────────
  return (
    <div className="min-h-screen bg-gray-900 text-white p-4">
      <div className="max-w-2xl mx-auto">
        {/* Header */}
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold">Estemshan</h1>
            <span
              className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                game.scoringMode === 'CLASSIC'
                  ? 'bg-amber-600 text-white'
                  : 'bg-blue-600 text-white'
              }`}
            >
              {game.scoringMode}
            </span>
          </div>
          <button
            className="text-sm text-gray-400 hover:text-white transition-colors"
            onClick={resetGame}
          >
            New Game
          </button>
        </div>

        {/* Scoreboard */}
        <div className="bg-gray-800 rounded-2xl p-4 mb-4 shadow">
          <div className="grid grid-cols-4 gap-2">
            {game.players.map((p) => (
              <div key={p.id} className="text-center">
                <p className="text-xs text-gray-400 truncate">{p.name}</p>
                <p className="text-2xl font-bold">
                  <ScoreBadge score={p.totalScore} />
                </p>
              </div>
            ))}
          </div>
        </div>

        {/* Sa'ayda pending doubling banner */}
        {saaydaActive && (
          <div className="bg-purple-900 border border-purple-500 rounded-2xl p-3 mb-4 flex items-center justify-between">
            <span className="text-sm text-purple-200">Sa'ayda ended — apply ×2?</span>
            <button
              className="bg-purple-600 hover:bg-purple-500 text-white text-sm font-bold px-4 py-1 rounded-lg"
              onClick={applyDoubling}
            >
              ×2 All Scores
            </button>
          </div>
        )}

        {/* Round Input */}
        <div className="bg-gray-800 rounded-2xl p-4 mb-4 shadow">
          <div className="flex items-center justify-between mb-3">
            <h2 className="font-semibold">
              Round {roundNumber}
              <span className="text-gray-400 text-sm ml-2">
                ({maxTricks} trick{maxTricks !== 1 ? 's' : ''})
              </span>
            </h2>
            {/* Sa'ayda toggle */}
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <span className="text-sm text-gray-400">Sa'ayda</span>
              <div
                className={`w-10 h-6 rounded-full relative transition-colors cursor-pointer ${
                  saayda ? 'bg-purple-600' : 'bg-gray-600'
                }`}
                onClick={() => setSaayda((v) => !v)}
              >
                <div
                  className={`absolute top-1 w-4 h-4 bg-white rounded-full shadow transition-transform ${
                    saayda ? 'translate-x-5' : 'translate-x-1'
                  }`}
                />
              </div>
            </label>
          </div>

          {saayda && (
            <div className="bg-purple-900 rounded-xl p-2 mb-3 text-xs text-purple-200">
              Sa'ayda active — scores will be 0 for this round. Apply ×2 after sa'ayda ends.
            </div>
          )}

          {/* Column headers */}
          <div className="grid grid-cols-4 gap-2 px-1 mb-1 text-xs text-gray-500">
            <span>Player</span>
            <span>Role</span>
            <span>Bid</span>
            <span>Won</span>
          </div>

          <div className="space-y-2">
            {game.players.map((player, i) => (
              <div key={player.id} className="grid grid-cols-4 gap-2 items-center">
                <p className="text-sm font-medium truncate">{player.name}</p>
                <select
                  className="bg-gray-700 rounded-lg px-1 py-1.5 text-xs focus:outline-none focus:ring-2 focus:ring-blue-500"
                  value={roundInputs[i].role}
                  onChange={(e) => updateInput(i, 'role', e.target.value)}
                >
                  {PLAYER_ROLES.map((r) => (
                    <option key={r} value={r}>
                      {getRoleLabel(r)}
                    </option>
                  ))}
                </select>
                <input
                  type="number"
                  min={0}
                  max={maxTricks}
                  className="bg-gray-700 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 w-full"
                  placeholder="0"
                  value={roundInputs[i].bid}
                  onChange={(e) => updateInput(i, 'bid', e.target.value)}
                />
                <input
                  type="number"
                  min={0}
                  max={maxTricks}
                  className="bg-gray-700 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 w-full"
                  placeholder="0"
                  value={roundInputs[i].won}
                  onChange={(e) => updateInput(i, 'won', e.target.value)}
                />
              </div>
            ))}
          </div>

          {roundError && (
            <p className="text-red-400 text-xs mt-2">{roundError}</p>
          )}

          <button
            className="w-full mt-4 bg-green-600 hover:bg-green-500 text-white font-bold py-2 rounded-xl transition-colors"
            onClick={submitRound}
          >
            Submit Round {roundNumber}
          </button>
        </div>

        {/* Round History */}
        {game.completedRounds.length > 0 && (
          <div className="bg-gray-800 rounded-2xl p-4 shadow">
            <h2 className="text-sm font-semibold text-gray-400 mb-3">History</h2>
            <div className="space-y-2 max-h-80 overflow-y-auto">
              {[...game.completedRounds].reverse().map((r) => (
                <div key={r.roundNumber} className="bg-gray-700 rounded-xl p-3">
                  <div className="flex justify-between text-xs text-gray-400 mb-1">
                    <span>
                      Round {r.roundNumber}
                      {r.isSaayda && (
                        <span className="text-purple-400 ml-1">Sa'ayda</span>
                      )}
                    </span>
                    <span>
                      Bids {r.totalBids}/{r.maxTricks}
                      {r.totalBids > 13 && (
                        <span className="text-orange-400 ml-1">Over</span>
                      )}
                      {r.totalBids < 13 && (
                        <span className="text-blue-400 ml-1">Under</span>
                      )}
                    </span>
                  </div>
                  <div className="grid grid-cols-4 gap-1 text-xs">
                    {game.players.map((p) => {
                      const pd = r.players.find((pp) => pp.playerId === p.id);
                      return (
                        <div key={p.id} className="text-center">
                          <p className="text-gray-400 truncate text-xs">{p.name}</p>
                          {pd && (
                            <p className="text-gray-300 text-xs">
                              {getRoleLabel(pd.role)} {pd.bid}/{pd.won}
                            </p>
                          )}
                          <ScoreBadge score={r.scores[p.id] ?? 0} />
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
