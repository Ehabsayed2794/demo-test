import { useEffect, useRef, useState } from 'react';
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
  const symbol = score > 0 ? '▲ ' : score < 0 ? '▼ ' : '';
  const accessibleLabel =
    score > 0
      ? `Score plus ${score} points`
      : score < 0
        ? `Score minus ${Math.abs(score)} points`
        : 'Score zero points';
  return (
    <span
      className={`font-bold tabular-nums shrink-0 ${color}`}
      role="img"
      aria-label={accessibleLabel}
    >
      <span aria-hidden="true">
        {symbol}
        {score > 0 ? '+' : ''}
        {score}
      </span>
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

const STORAGE_KEY = 'estemshan-save-v1';

/* ── Design tokens ───────────────────────────────────────────────────
   Single source for surface + control styling (consistency,
   elevation-consistent). Radius scale: cards 2xl · rows xl ·
   inputs & small buttons lg · primary CTAs xl.
   Button hierarchy (primary-action): one solid primary per screen
   (Start / Submit / Play Again); everything else is outline or
   secondary so the primary never competes. */
const SURFACE = 'bg-gray-800 rounded-2xl shadow-lg border border-gray-700/60';
const ROW = 'bg-gray-700 rounded-xl p-3';
const NUMBER_INPUT =
  'bg-gray-700 rounded-lg px-2 py-1.5 min-h-[44px] text-base sm:text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 w-full';

type PersistedState = {
  playerNames: string[];
  scoringMode: ScoringMode;
  game: GameState | null;
  setupMode: boolean;
  saayda: boolean;
  saaydaActive: boolean;
  roundInputs: { role: PlayerRole; bid: string; won: string }[];
};

function loadPersistedState(): Partial<PersistedState> {
  try {
    if (typeof localStorage === 'undefined') return {};
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Partial<PersistedState>;
    if (
      parsed.game !== undefined &&
      parsed.game !== null &&
      (!Array.isArray(parsed.game.players) || parsed.game.players.length !== 4)
    )
      return {};
    if (
      parsed.playerNames !== undefined &&
      (!Array.isArray(parsed.playerNames) || parsed.playerNames.length !== 4)
    )
      return {};
    if (
      parsed.roundInputs !== undefined &&
      (!Array.isArray(parsed.roundInputs) || parsed.roundInputs.length !== 4)
    )
      return {};
    if (parsed.scoringMode !== undefined && parsed.scoringMode !== 'NORMAL' && parsed.scoringMode !== 'CLASSIC')
      delete parsed.scoringMode;
    if (parsed.roundInputs) {
      parsed.roundInputs = parsed.roundInputs.map((r) => ({
        role: PLAYER_ROLES.includes(r.role) ? r.role : 'NORMAL',
        bid: typeof r.bid === 'string' ? r.bid : '',
        won: typeof r.won === 'string' ? r.won : '',
      }));
    }
    return parsed;
  } catch {
    return {};
  }
}

export default function App() {
  const [bootstrapped] = useState(loadPersistedState);
  // A save without a game always lands on setup, even if storage was
  // hand-edited into an inconsistent state (never render a blank screen).
  const [setupMode, setSetupMode] = useState(
    bootstrapped.game ? (bootstrapped.setupMode ?? false) : true,
  );
  const [playerNames, setPlayerNames] = useState(
    bootstrapped.playerNames ?? DEFAULT_PLAYER_NAMES,
  );
  const [scoringMode, setScoringMode] = useState<ScoringMode>(
    bootstrapped.scoringMode ?? 'NORMAL',
  );
  const [game, setGame] = useState<GameState | null>(
    bootstrapped.game ?? null,
  );
  const [saayda, setSaayda] = useState(bootstrapped.saayda ?? false);
  const [saaydaActive, setSaaydaActive] = useState(
    bootstrapped.saaydaActive ?? false,
  );
  const [roundInputs, setRoundInputs] = useState(
    bootstrapped.roundInputs ?? emptyInputs(),
  );
  const [roundError, setRoundError] = useState('');
  const [confirmReset, setConfirmReset] = useState(false);
  // Help overlay; transient UI state, intentionally excluded from autosave.
  const [showHelp, setShowHelp] = useState(false);
  // Pre-doubling totals snapshot; non-null means "doubled, undo available".
  // Short-lived by design: cleared by the next scored round or a reset.
  const [undoDouble, setUndoDouble] = useState<Record<number, number> | null>(
    null,
  );
  const errorRef = useRef<HTMLParagraphElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const newGameBtnRef = useRef<HTMLButtonElement>(null);
  const cancelResetRef = useRef<HTMLButtonElement>(null);
  const undoBtnRef = useRef<HTMLButtonElement>(null);
  const scoreboardRef = useRef<HTMLDivElement>(null);
  const submitRef = useRef<HTMLButtonElement>(null);
  // Navigation refs, synced each render so the mount-once popstate
  // handler below always sees current values without re-subscribing.
  const gameRef = useRef(game);
  gameRef.current = game;

  const completedCount = game?.completedRounds.length ?? 0;
  const currentRoundIdx = Math.min(completedCount, 17);
  const maxTricks = ROUND_MAX_TRICKS[currentRoundIdx];
  const roundNumber = currentRoundIdx + 1;
  const gameOver = completedCount >= 18;

  // Sole current leader, highlighted once at least one round is scored.
  // Ties show no leader to avoid visual noise.
  const maxScore = game ? Math.max(...game.players.map((p) => p.totalScore)) : 0;
  const soleLeaderId =
    game !== null &&
    completedCount > 0 &&
    game.players.filter((p) => p.totalScore === maxScore).length === 1
      ? (game.players.find((p) => p.totalScore === maxScore)?.id ?? null)
      : null;

  // Move focus to validation errors so screen readers announce them.
  useEffect(() => {
    if (roundError) errorRef.current?.focus();
  }, [roundError]);

  // Move focus to the new screen heading on route (screen) change.
  useEffect(() => {
    headingRef.current?.focus();
  }, [setupMode, gameOver, showHelp]);

  // Autosave: persist the full game draft so a refresh never loses progress.
  useEffect(() => {
    try {
      if (typeof localStorage === 'undefined') return;
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          playerNames,
          scoringMode,
          game,
          setupMode,
          saayda,
          saaydaActive,
          roundInputs,
          savedAt: Date.now(),
        }),
      );
    } catch {
      // Private-mode quota etc. — game still works in memory.
    }
  }, [
    playerNames,
    scoringMode,
    game,
    setupMode,
    saayda,
    saaydaActive,
    roundInputs,
  ]);

  // Confirm-dialog focus: land on Cancel, Escape dismisses and restores focus.
  useEffect(() => {
    if (!confirmReset) return;
    cancelResetRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setConfirmReset(false);
        newGameBtnRef.current?.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [confirmReset]);

  // The ×2 prompt unmounts when applied; move focus to its Undo
  // replacement so keyboard/screen-reader users aren't left on <body>.
  useEffect(() => {
    if (undoDouble && !saaydaActive) undoBtnRef.current?.focus();
  }, [undoDouble, saaydaActive]);

  // URL reflects the current SCREEN only (#setup/#game/#over) — never
  // player data or game state. replaceState: moving between screens must
  // not multiply history entries; pushes happen at meaningful transitions
  // (startGame, game completion, help overlay) instead.
  useEffect(() => {
    try {
      const screen = setupMode ? 'setup' : gameOver ? 'over' : 'game';
      history.replaceState({ screen }, '', `#${screen}`);
    } catch {
      // Non-browser/test runtimes — navigation is a no-op there.
    }
  }, [setupMode, gameOver]);

  // One history entry for game completion so Back from Game Over lands on
  // the final game state instead of exiting. Ref-guarded (incl. StrictMode).
  useEffect(() => {
    if (gameOver && !setupMode && !pushedOverRef.current) {
      pushedOverRef.current = true;
      try {
        history.pushState({ screen: 'over' }, '', '#over');
      } catch {
        // Non-browser/test runtimes — navigation is a no-op there.
      }
    }
    if (!gameOver) pushedOverRef.current = false;
  }, [gameOver, setupMode]);

  // Back/forward handling (mount-once; reads via refs above).
  // - Help overlay open: Back closes the overlay, nothing else happens.
  // - Target #setup with an untouched game: reset is harmless, allow it.
  // - Target #setup with progress: NEVER destroy silently — show the same
  //   confirm dialog as New Game and restore the #game entry on cancel.
  // - Any other hash with no game (deep link to #game/#over, stale entry):
  //   stay and let the sync effect normalize the URL to the real screen.
  useEffect(() => {
    const onPopState = () => {
      try {
        if (helpEntryRef.current) {
          helpEntryRef.current = false;
          setShowHelp(false);
          return;
        }
        if (location.hash === '#setup') {
          if (!gameRef.current || !hasProgressRef.current) {
            resetGame();
          } else {
            setConfirmReset(true);
            history.pushState({ screen: 'game' }, '', '#game');
          }
        } else if (!gameRef.current) {
          history.replaceState({ screen: 'setup' }, '', '#setup');
        }
      } catch {
        // Non-browser/test runtimes — navigation is a no-op there.
      }
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  function startGame() {
    const names = playerNames.map((n, i) => n.trim() || `Player ${i + 1}`);
    setGame(initGame(names, scoringMode));
    setSetupMode(false);
    setSaayda(false);
    setSaaydaActive(false);
    setRoundInputs(emptyInputs());
    setRoundError('');
    pushedOverRef.current = false;
    try {
      history.pushState({ screen: 'game' }, '', '#game');
    } catch {
      // Non-browser/test runtimes — navigation is a no-op there.
    }
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
    // A newly scored round supersedes any pending doubling-undo.
    setUndoDouble(null);
  }

  function applyDoubling() {
    if (!game) return;
    setUndoDouble(
      Object.fromEntries(game.players.map((p) => [p.id, p.totalScore])),
    );
    setGame((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        players: prev.players.map((p) => ({ ...p, totalScore: p.totalScore * 2 })),
      };
    });
    setSaaydaActive(false);
  }

  function undoDoubling() {
    if (!undoDouble) return;
    const snapshot = undoDouble;
    setGame((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        players: prev.players.map((p) => ({
          ...p,
          totalScore: snapshot[p.id] ?? p.totalScore,
        })),
      };
    });
    setUndoDouble(null);
    // The Undo button unmounts with its banner; land focus on the
    // scoreboard so the restored totals are announced, not silence.
    setTimeout(() => scoreboardRef.current?.focus(), 0);
  }

  // Remove the last scored round and give its points back. Single-tap by
  // design (undo-support beats confirmation): re-entering one round is
  // cheap, and a dialog on every correction would punish normal use.
  // Also clears any pending ×2 — it belonged to a round that no longer
  // exists — and focuses Submit so the round can be re-entered at once.
  function undoLastRound() {
    setGame((prev) => {
      if (!prev || prev.completedRounds.length === 0) return prev;
      const last = prev.completedRounds[prev.completedRounds.length - 1];
      return {
        ...prev,
        players: prev.players.map((p) => ({
          ...p,
          totalScore: p.totalScore - (last.scores[p.id] ?? 0),
        })),
        completedRounds: prev.completedRounds.slice(0, -1),
      };
    });
    setSaaydaActive(false);
    setUndoDouble(null);
    setRoundError('');
    setTimeout(() => submitRef.current?.focus(), 0);
  }

  function resetGame() {
    setSetupMode(true);
    setGame(null);
    setRoundInputs(emptyInputs());
    setRoundError('');
    setSaayda(false);
    setSaaydaActive(false);
    setConfirmReset(false);
    setUndoDouble(null);
  }

  const hasProgress =
    (game?.completedRounds.length ?? 0) > 0 ||
    saayda ||
    saaydaActive ||
    roundInputs.some((r) => r.bid !== '' || r.won !== '' || r.role !== 'NORMAL');
  const hasProgressRef = useRef(hasProgress);
  hasProgressRef.current = hasProgress;
  // Tracks whether the open help overlay owns a history entry (pushed in
  // openHelp so browser Back closes the overlay first), and whether the
  // game-over entry was already pushed for the current game.
  const helpEntryRef = useRef(false);
  const pushedOverRef = useRef(false);

  // Destructive reset goes through the confirm dialog when progress exists.
  function requestReset() {
    if (!game || !hasProgress) {
      resetGame();
      return;
    }
    setConfirmReset(true);
  }

  function cancelReset() {
    setConfirmReset(false);
    newGameBtnRef.current?.focus();
  }

  function openHelp() {
    setShowHelp(true);
    // Own history entry so browser Back closes the overlay first instead
    // of navigating the underlying screen away. Same-hash push: no URL
    // change, just a back-stop. Consumed by the popstate handler.
    helpEntryRef.current = true;
    try {
      history.pushState({ help: true }, '', location.hash || '#setup');
    } catch {
      helpEntryRef.current = false;
    }
  }

  function closeHelp() {
    // When the overlay owns a history entry, go Back and let popstate do
    // the closing (keeps entry accounting exact). Otherwise close directly.
    // Focus is restored by the heading-focus effect on showHelp change.
    if (helpEntryRef.current) {
      try {
        history.back();
        return;
      } catch {
        helpEntryRef.current = false;
      }
    }
    setShowHelp(false);
  }

  // ── Help Screen (overlay; underlying game state is preserved) ───
  if (showHelp) {
    return (
      <div className="min-h-dvh bg-gray-900 text-white flex justify-center p-4 pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)] pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))] overflow-y-auto">
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:bg-white focus:text-gray-900 focus:px-3 focus:py-2 focus:rounded-lg"
        >
          Skip to guide
        </a>
        <div id="main-content" className={`m-auto w-full max-w-md ${SURFACE} p-6 screen-enter`}>
          <p className="text-xs font-semibold tracking-widest text-amber-300 mb-1">
            GUIDE
          </p>
          <h1
            ref={headingRef}
            tabIndex={-1}
            className="text-2xl font-bold mb-4 focus:outline-none"
          >
            How scoring works
          </h1>

          <div className="space-y-5 text-sm">
            <section>
              <h2 className="font-semibold text-amber-300 mb-1">The game</h2>
              <p className="text-gray-300">
                Estemshan tracks an 18-round Estimation game for 4 players.
                Highest total at the end wins. Tricks climb 1 up to 8, fall
                back to 1, then finish with four 1-trick rounds.
              </p>
            </section>

            <section>
              <h2 className="font-semibold text-amber-300 mb-1">
                Entering a round
              </h2>
              <p className="text-gray-300">
                For every player enter their role, bid, and tricks won. The
                won column must add up to the round&apos;s trick count, or the
                round won&apos;t submit.
              </p>
            </section>

            <section>
              <h2 className="font-semibold text-amber-300 mb-2">Roles</h2>
              <ul className="space-y-2 text-gray-300">
                <li>
                  <span className="font-semibold text-white">Normal</span> —
                  hit your bid: 10 + bid. Miss: minus the difference.
                </li>
                <li>
                  <span className="font-semibold text-white">Caller</span> —
                  called the round: +10 bonus on success, −10 extra on fail.
                </li>
                <li>
                  <span className="font-semibold text-white">Wizz</span> —
                  caller credit plus another +10 on success.
                </li>
                <li>
                  <span className="font-semibold text-white">Risk</span> — +10
                  on success, −10 extra on fail.
                </li>
                <li>
                  <span className="font-semibold text-white">Wizz + Risk</span>{' '}
                  — both bonuses combined.
                </li>
                <li>
                  <span className="font-semibold text-white">Super Call</span>{' '}
                  — high stakes: +20 on success, −20 on fail.
                </li>
                <li>
                  <span className="font-semibold text-white">Dash Call</span> /{' '}
                  <span className="font-semibold text-white">Dash</span> — bid
                  zero and take zero: +10 on success.
                </li>
              </ul>
            </section>

            <section>
              <h2 className="font-semibold text-amber-300 mb-1">
                Sole winner &amp; loser
              </h2>
              <p className="text-gray-300">
                Only player to hit their bid: +10. Only player to miss: the
                fail score is doubled (floored at −22).
              </p>
            </section>

            <section>
              <h2 className="font-semibold text-amber-300 mb-1">
                Classic mode
              </h2>
              <p className="text-gray-300">
                Higher bases: success starts at bid +13, Super Call pays 42,
                Dash Call pays 33 (or 23 when the table is over). History flags
                rounds where total bids run over or under 13.
              </p>
            </section>

            <section>
              <h2 className="font-semibold text-amber-300 mb-1">Sa&apos;ayda</h2>
              <p className="text-gray-300">
                A zero round — nobody scores. When it ends, confirm ×2 to
                double every total.
              </p>
            </section>
          </div>

          <button
            type="button"
            onClick={closeHelp}
            className="w-full mt-6 bg-gray-700 hover:bg-gray-600 active:bg-gray-600 text-white font-bold py-2 min-h-[48px] rounded-xl transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-gray-800"
          >
            Back
          </button>
        </div>
      </div>
    );
  }

  // ── Setup Screen ──────────────────────────────────────────────
  if (setupMode) {
    return (
      <div className="min-h-dvh bg-gray-900 text-white flex items-center justify-center p-4 pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)] pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))]">
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:bg-white focus:text-gray-900 focus:px-3 focus:py-2 focus:rounded-lg"
        >
          Skip to main content
        </a>
        <div id="main-content" className={`w-full max-w-md ${SURFACE} p-6 screen-enter`}>
          <h1
            ref={headingRef}
            tabIndex={-1}
            className="text-3xl font-bold text-center mb-1 focus:outline-none"
          >
            <span className="bg-gradient-to-r from-amber-200 via-amber-400 to-orange-400 bg-clip-text text-transparent">
              Estemshan
            </span>
          </h1>
          <p className="text-gray-300 text-center mb-6">Score Tracker</p>

          <div className="mb-6">
            <p id="scoring-mode-label" className="text-sm text-gray-300 mb-2">
              Scoring Mode
            </p>
            <div
              role="radiogroup"
              aria-labelledby="scoring-mode-label"
              className="flex rounded-xl overflow-hidden border border-gray-600"
            >
              <button
                type="button"
                role="radio"
                aria-checked={scoringMode === 'NORMAL'}
                className={`flex-1 py-2 min-h-[44px] text-sm font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-inset ${
                  scoringMode === 'NORMAL'
                    ? 'bg-blue-600 text-white'
                    : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
                }`}
                onClick={() => setScoringMode('NORMAL')}
              >
                Normal
              </button>
              <button
                type="button"
                role="radio"
                aria-checked={scoringMode === 'CLASSIC'}
                className={`flex-1 py-2 min-h-[44px] text-sm font-semibold transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-inset ${
                  scoringMode === 'CLASSIC'
                    ? 'bg-amber-700 text-white'
                    : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
                }`}
                onClick={() => setScoringMode('CLASSIC')}
              >
                Classic
              </button>
            </div>
            {scoringMode === 'CLASSIC' && (
              <p className="text-xs text-amber-300 mt-1">
                Classic mode — success base +13, normal fail = -1
              </p>
            )}
          </div>

          <fieldset className="space-y-3 mb-6">
            <legend className="text-sm text-gray-300 mb-2">Player Names</legend>
            {playerNames.map((name, i) => (
              <div key={i}>
                <label htmlFor={`player-name-${i}`} className="sr-only">
                  Player {i + 1} name
                </label>
                <input
                  id={`player-name-${i}`}
                  type="text"
                  autoComplete="nickname"
                  maxLength={30}
                  className="w-full bg-gray-700 rounded-lg px-3 py-2 min-h-[44px] text-white placeholder-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder={`Player ${i + 1}`}
                  value={name}
                  onChange={(e) => {
                    const next = [...playerNames];
                    next[i] = e.target.value;
                    setPlayerNames(next);
                  }}
                />
              </div>
            ))}
          </fieldset>

          <button
            type="button"
            className="w-full bg-blue-600 hover:bg-blue-500 active:bg-blue-700 text-white font-bold py-3 min-h-[48px] rounded-xl transition-colors shadow-lg shadow-blue-600/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-gray-800"
            onClick={startGame}
          >
            Start Game
          </button>
          <button
            type="button"
            onClick={openHelp}
            className="w-full mt-1 min-h-[44px] text-sm text-gray-300 hover:text-white underline underline-offset-4 rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
          >
            How scoring works
          </button>
          <p className="text-xs text-gray-400 text-center mt-3">
            Progress auto-saves on this device
          </p>
        </div>
      </div>
    );
  }

  if (!game) return null;

  // ── Game Over Screen ──────────────────────────────────────────
  if (gameOver) {
    const sorted = [...game.players].sort((a, b) => b.totalScore - a.totalScore);
    return (
      <div className="min-h-dvh bg-gray-900 text-white flex items-center justify-center p-4 pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)] pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))]">
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:bg-white focus:text-gray-900 focus:px-3 focus:py-2 focus:rounded-lg"
        >
          Skip to results
        </a>
        <div id="main-content" className={`w-full max-w-md ${SURFACE} p-6 text-center screen-enter`}>
          <h1
            ref={headingRef}
            tabIndex={-1}
            className="text-2xl font-bold mb-4 focus:outline-none"
          >
            Game Complete!
          </h1>
          <ol className="space-y-2 mb-6" aria-label="Final standings">
            {sorted.map((p, i) => (
              <li
                key={p.id}
                className="flex justify-between items-center bg-gray-700 rounded-xl px-4 py-2 min-h-[44px]"
              >
                <span className="font-bold min-w-0 truncate" title={p.name}>
                  {i === 0 ? (
                    <>
                      <svg
                        aria-hidden="true"
                        xmlns="http://www.w3.org/2000/svg"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        className="inline w-4 h-4 text-amber-300 align-[-2px]"
                      >
                        <path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6" />
                        <path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18" />
                        <path d="M4 22h16" />
                        <path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22" />
                        <path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22" />
                        <path d="M18 2H6v7a6 6 0 0 0 12 0V2Z" />
                      </svg>{' '}
                      <span className="sr-only">Winner: </span>
                    </>
                  ) : (
                    `${i + 1}. `
                  )}
                  {p.name}
                </span>
                <ScoreBadge score={p.totalScore} />
              </li>
            ))}
          </ol>
          <button
            type="button"
            className="bg-blue-600 hover:bg-blue-500 active:bg-blue-700 text-white font-bold py-2 px-6 min-h-[44px] rounded-xl transition-colors shadow-lg shadow-blue-600/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-gray-800"
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
    <div className="min-h-dvh bg-gray-900 text-white p-4 pb-[env(safe-area-inset-bottom)] pt-[env(safe-area-inset-top)] pl-[max(1rem,env(safe-area-inset-left))] pr-[max(1rem,env(safe-area-inset-right))]">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:bg-white focus:text-gray-900 focus:px-3 focus:py-2 focus:rounded-lg"
      >
        Skip to round input
      </a>
      <div id="main-content" className="max-w-2xl mx-auto screen-enter">
        {/* Header */}
        <div className="flex items-center justify-between gap-2 mb-4">
          <div className="flex items-center gap-2 min-w-0">
            <h1
              ref={headingRef}
              tabIndex={-1}
              className="text-xl sm:text-2xl font-bold truncate focus:outline-none"
            >
              Estemshan
            </h1>
            <span
              className={`shrink-0 text-xs font-semibold px-2 py-0.5 rounded-full ${
                game.scoringMode === 'CLASSIC'
                  ? 'bg-amber-700 text-white'
                  : 'bg-blue-600 text-white'
              }`}
            >
              {game.scoringMode}
            </span>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              aria-label="How scoring works"
              onClick={openHelp}
              className="text-gray-300 hover:text-white transition-colors min-h-[44px] min-w-[44px] flex items-center justify-center rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
            >
              <svg
                aria-hidden="true"
                xmlns="http://www.w3.org/2000/svg"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                className="w-5 h-5"
              >
                <circle cx="12" cy="12" r="10" />
                <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
                <path d="M12 17h.01" />
              </svg>
            </button>
            <button
              ref={newGameBtnRef}
              type="button"
              className="text-sm text-gray-300 hover:text-red-200 transition-colors min-h-[44px] min-w-[44px] px-3 rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
              onClick={requestReset}
            >
              New Game
            </button>
          </div>
        </div>

        {/* Scoreboard */}
        <div
          ref={scoreboardRef}
          tabIndex={-1}
          className={`${SURFACE} p-4 mb-4 focus:outline-none`}
          role="region"
          aria-label="Current scores"
        >
          <div className="grid grid-cols-4 gap-2">
            {game.players.map((p) => {
              const isLeader = soleLeaderId === p.id;
              return (
                <div
                  key={p.id}
                  className={`text-center rounded-lg px-1 py-0.5 ${
                    isLeader ? 'bg-gray-700/60 ring-1 ring-amber-400/50' : ''
                  }`}
                >
                  <p
                    className={`text-xs truncate ${
                      isLeader ? 'text-amber-300 font-semibold' : 'text-gray-300'
                    }`}
                    title={p.name}
                  >
                    {isLeader && (
                      <>
                        <svg
                          aria-hidden="true"
                          xmlns="http://www.w3.org/2000/svg"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          className="inline w-3 h-3 mr-0.5 align-[-1px]"
                        >
                          <path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6" />
                          <path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18" />
                          <path d="M4 22h16" />
                          <path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22" />
                          <path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22" />
                          <path d="M18 2H6v7a6 6 0 0 0 12 0V2Z" />
                        </svg>
                        <span className="sr-only">Current leader: </span>
                      </>
                    )}
                    {p.name}
                  </p>
                  <p className="text-2xl font-bold">
                    <ScoreBadge score={p.totalScore} />
                  </p>
                </div>
              );
            })}
          </div>
        </div>

        {/* Game progress */}
        <div
          className="mb-4"
          role="progressbar"
          aria-valuenow={completedCount}
          aria-valuemin={0}
          aria-valuemax={18}
          aria-label={`Round ${roundNumber} of 18`}
        >
          <div className="flex justify-between text-xs text-gray-400 mb-1">
            <span>
              Round {roundNumber} of 18
            </span>
            <span>{completedCount} scored</span>
          </div>
          <div className="h-2 rounded-full bg-gray-700 overflow-hidden">
            <div
              className="h-full rounded-full bg-gradient-to-r from-blue-500 to-green-400 transition-all"
              style={{ width: `${(completedCount / 18) * 100}%` }}
            />
          </div>
        </div>

        {/* Sa'ayda pending doubling banner */}
        {saaydaActive ? (
          <div className="bg-purple-900 border border-purple-500 rounded-2xl p-3 mb-4 flex items-center justify-between gap-2">
            <span className="text-sm text-purple-100 min-w-0">
              Sa&apos;ayda ended — apply ×2?
            </span>
            <button
              type="button"
              className="shrink-0 border border-purple-400/60 bg-purple-900/40 text-purple-100 hover:bg-purple-900/70 active:bg-purple-900 text-sm font-semibold px-4 py-2 min-h-[44px] rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-300"
              onClick={applyDoubling}
            >
              ×2 All Scores
            </button>
          </div>
        ) : (
          undoDouble && (
            <div
              role="status"
              className="bg-gray-800 border border-gray-700 rounded-2xl p-3 mb-4 flex items-center justify-between gap-2"
            >
              <span className="text-sm text-gray-300 min-w-0">Scores doubled.</span>
              <button
                ref={undoBtnRef}
                type="button"
                className="shrink-0 border border-gray-500 text-gray-200 hover:bg-gray-700 active:bg-gray-600 text-sm font-semibold px-4 py-2 min-h-[44px] rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
                onClick={undoDoubling}
              >
                Undo
              </button>
            </div>
          )
        )}

        {/* Round Input */}
        <div className={`${SURFACE} p-4 mb-4`}>
          <div className="flex items-center justify-between flex-wrap gap-2 mb-3">
            <h2 className="font-semibold">
              Round {roundNumber}
              <span className="text-gray-300 text-sm ml-2">
                ({maxTricks} trick{maxTricks !== 1 ? 's' : ''})
              </span>
            </h2>
            {/* Sa'ayda toggle */}
            <div className="flex items-center gap-1">
              <span id="saayda-label" className="text-sm text-gray-300">
                Sa&apos;ayda
              </span>
              <button
                type="button"
                role="switch"
                aria-checked={saayda}
                aria-labelledby="saayda-label"
                onClick={() => setSaayda((v) => !v)}
                className="min-h-[44px] min-w-[44px] flex items-center justify-center rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-400"
              >
                <span
                  aria-hidden="true"
                  className={`w-10 h-6 rounded-full relative transition-colors ${
                    saayda ? 'bg-purple-600' : 'bg-gray-600'
                  }`}
                >
                  <span
                    className={`absolute top-1 w-4 h-4 bg-white rounded-full shadow transition-transform ${
                      saayda ? 'translate-x-5' : 'translate-x-1'
                    }`}
                  />
                </span>
              </button>
            </div>
          </div>

          {saayda && (
            <div className="bg-purple-900 rounded-xl p-2 mb-3 text-xs text-purple-100">
              Sa&apos;ayda active — scores will be 0 for this round. Apply ×2
              after sa&apos;ayda ends.
            </div>
          )}

          {/* Column headers — desktop only; mobile cards use per-field labels */}
          <div
            aria-hidden="true"
            className="hidden sm:grid grid-cols-4 gap-2 px-1 mb-1 text-xs text-gray-400"
          >
            <span>Player</span>
            <span>Role</span>
            <span>Bid</span>
            <span>Won</span>
          </div>

          <div className="space-y-3 sm:space-y-2">
            {game.players.map((player, i) => (
              <div
                key={player.id}
                className="grid grid-cols-2 sm:grid-cols-4 gap-x-2 gap-y-1 sm:gap-2 sm:items-center rounded-xl bg-gray-700/40 p-2 sm:bg-transparent sm:p-0"
              >
                <p
                  className="col-span-2 sm:col-span-1 text-sm font-bold sm:font-medium break-words sm:truncate"
                  title={player.name}
                >
                  {player.name}
                </p>
                <div className="col-span-2 sm:col-span-1 min-w-0">
                  <label
                    htmlFor={`role-${player.id}`}
                    className="mb-1 block text-xs text-gray-400 sm:sr-only"
                  >
                    Role{' '}
                    <span className="sr-only">for {player.name}</span>
                  </label>
                  <select
                    id={`role-${player.id}`}
                    className="bg-gray-700 rounded-lg px-1 py-1.5 min-h-[44px] w-full text-sm sm:text-xs focus:outline-none focus:ring-2 focus:ring-blue-500"
                    value={roundInputs[i].role}
                    onChange={(e) => updateInput(i, 'role', e.target.value)}
                  >
                    {PLAYER_ROLES.map((r) => (
                      <option key={r} value={r}>
                        {getRoleLabel(r)}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="min-w-0">
                  <label
                    htmlFor={`bid-${player.id}`}
                    className="mb-1 block text-xs text-gray-400 sm:sr-only"
                  >
                    Bid{' '}
                    <span className="sr-only">
                      for {player.name}, 0 to {maxTricks}
                    </span>
                  </label>
                  <input
                    id={`bid-${player.id}`}
                    type="number"
                    inputMode="numeric"
                    min={0}
                    max={maxTricks}
                    className={NUMBER_INPUT}
                    placeholder="0"
                    value={roundInputs[i].bid}
                    onChange={(e) => updateInput(i, 'bid', e.target.value)}
                  />
                </div>
                <div className="min-w-0">
                  <label
                    htmlFor={`won-${player.id}`}
                    className="mb-1 block text-xs text-gray-400 sm:sr-only"
                  >
                    Won{' '}
                    <span className="sr-only">
                      tricks for {player.name}, 0 to {maxTricks}
                    </span>
                  </label>
                  <input
                    id={`won-${player.id}`}
                    type="number"
                    inputMode="numeric"
                    min={0}
                    max={maxTricks}
                    className={NUMBER_INPUT}
                    placeholder="0"
                    value={roundInputs[i].won}
                    onChange={(e) => updateInput(i, 'won', e.target.value)}
                  />
                </div>
              </div>
            ))}
          </div>

          {roundError && (
            <p
              ref={errorRef}
              tabIndex={-1}
              role="alert"
              className="text-red-300 text-sm mt-2 focus:outline-none"
            >
              {roundError}
            </p>
          )}

          <button
            ref={submitRef}
            type="button"
            className="w-full mt-4 bg-green-700 hover:bg-green-800 active:bg-green-900 text-white font-bold py-2 min-h-[48px] rounded-xl transition-colors shadow-lg shadow-green-700/25 focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-gray-800"
            onClick={submitRound}
          >
            Submit Round {roundNumber}
          </button>
        </div>

        {/* Round History */}
        <div className={`${SURFACE} p-4`} role="region" aria-label="Round history">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-300">History</h2>
            {game.completedRounds.length > 0 && (
              <button
                type="button"
                onClick={undoLastRound}
                aria-label={`Undo round ${roundNumber - 1}, the last scored round`}
                className="text-xs text-gray-400 hover:text-red-200 transition-colors min-h-[44px] px-2 -my-2 rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
              >
                Undo last round
              </button>
            )}
          </div>
          {game.completedRounds.length === 0 ? (
            <p className="text-sm text-gray-400">
              No rounds scored yet — enter bids above and submit Round 1.
            </p>
          ) : (
            <div className="space-y-2 max-h-80 overflow-y-auto">
              {[...game.completedRounds].reverse().map((r) => (
                <div key={r.roundNumber} className={ROW}>
                  <div className="flex justify-between text-xs text-gray-300 mb-1">
                    <span>
                      Round {r.roundNumber}
                      {r.isSaayda && (
                        <span className="text-purple-300 ml-1">Sa&apos;ayda</span>
                      )}
                    </span>
                    <span>
                      Bids {r.totalBids}/{r.maxTricks}
                      {r.totalBids > 13 && (
                        <span className="text-orange-300 ml-1">Over</span>
                      )}
                      {r.totalBids < 13 && (
                        <span className="text-blue-300 ml-1">Under</span>
                      )}
                    </span>
                  </div>
                  <div className="grid grid-cols-4 gap-1 text-xs">
                    {game.players.map((p) => {
                      const pd = r.players.find((pp) => pp.playerId === p.id);
                      return (
                        <div key={p.id} className="text-center min-w-0">
                          <p
                            className="text-gray-300 truncate text-xs"
                            title={p.name}
                          >
                            {p.name}
                          </p>
                          {pd && (
                            <p className="text-gray-300 text-xs break-words">
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
          )}
        </div>
        <p className="text-xs text-gray-400 text-center mt-4" role="status">
          Progress auto-saves on this device
        </p>
      </div>

      {/* Destructive-reset confirmation */}
      {confirmReset && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 overflow-y-auto"
          onClick={cancelReset}
        >
          <div
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="reset-title"
            aria-describedby="reset-desc"
            className="w-full max-w-sm bg-gray-800 rounded-2xl p-6 shadow-xl border border-gray-700 m-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <h2 id="reset-title" className="text-lg font-bold mb-2">
              Start a new game?
            </h2>
            <p id="reset-desc" className="text-sm text-gray-300 mb-6">
              This discards Round {roundNumber} progress (
              {completedCount} round{completedCount === 1 ? '' : 's'} scored).
              This can&apos;t be undone.
            </p>
            <div className="flex flex-col-reverse sm:flex-row sm:justify-end gap-2">
              <button
                ref={cancelResetRef}
                type="button"
                className="flex-1 sm:flex-none px-4 py-2 min-h-[44px] rounded-xl font-semibold bg-gray-700 hover:bg-gray-600 active:bg-gray-600 text-white transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-white"
                onClick={cancelReset}
              >
                Keep playing
              </button>
              <button
                type="button"
                className="flex-1 sm:flex-none px-4 py-2 min-h-[44px] rounded-xl font-bold bg-red-600 hover:bg-red-500 active:bg-red-700 text-white transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2 focus-visible:ring-offset-gray-800"
                onClick={resetGame}
              >
                Discard &amp; start new
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
