import React, { useState, useEffect, useRef } from "react";
import {
  Play,
  RotateCcw,
  Trophy,
  Award,
  AlertCircle,
  Check,
  Terminal,
  Crown,
  User as UserIcon,
  Cpu,
  Sun,
  FileCode,
  Smartphone,
  Download,
  Copy,
  ChevronRight,
  HelpCircle,
  ShieldAlert,
  History,
  Save,
} from "lucide-react";
import { motion, AnimatePresence } from "motion/react";
import {
  Suit,
  PlayerModel,
  PlayedCard,
  GamePhase,
  CardModel,
  LogEntry,
  RoundStat,
  RoundPlayerStat,
  DifficultyLevel,
  ScoringMode,
} from "./types";
import {
  createNewDeck,
  shuffleDeck,
  determineWinner,
  isBidStronger,
  validateBidding,
  getSuitStrength,
  calculateEstemshanScore,
  PlayerRole,
  calculateClassicScore,
  sortHand,
  updateBotLearningBiases,
  generateLegalBids,
} from "./utils";
import { evaluateBotBid, chooseBestTrump, normalizeTier } from "./botEngine";
import { evaluateBotBidWithPersonality, PERSONALITIES } from "./botPersonality";
import { selectAIGameplayCardV2 } from "./botPlay";
import { kotlinProjectFiles } from "./kotlinCode";
import { AuctionStage } from "./components/AuctionStage";

// --- CUSTOM HIGH-FIDELITY DIGITAL AVATARS (Exactly Matching Image Screenshots) ---
const BarbryyAvatar = () => (
  <svg
    viewBox="0 0 100 100"
    className="w-full h-full rounded-xl overflow-hidden shadow-inner select-none pointer-events-none"
  >
    <rect width="100" height="100" fill="#1B3B6F" />
    <path
      d="M0,0 L100,100 M100,0 L0,100"
      stroke="#0B132B"
      strokeWidth="2"
      strokeOpacity="0.25"
    />

    {/* Body / Shoulders */}
    <path
      d="M12 90 C 12 70, 24 62, 50 62 C 76 62, 88 70, 88 90 Z"
      fill="#311111"
    />
    <path
      d="M28 62 L 50 88 L 72 62"
      fill="none"
      stroke="#5C1D1D"
      strokeWidth="3"
    />

    {/* Keffiyeh (checkered red/white Arab draped headdress) */}
    <path
      d="M20,33 C18,12 82,12 80,33 C77,53 84,73 84,84 L72,84 C72,68 66,57 50,57 C34,57 28,68 28,84 L16,84 C16,73 23,53 20,33 Z"
      fill="#FFF"
    />
    <path
      d="M24 23 L38 48 M38 16 L58 48 M58 16 L73 38"
      stroke="#DC2626"
      strokeWidth="1.5"
      strokeOpacity="0.5"
    />
    <path
      d="M76 23 L62 48 M62 16 L42 48 M42 16 L27 38"
      stroke="#DC2626"
      strokeWidth="1.5"
      strokeOpacity="0.5"
    />
    <path
      d="M16 52 H84 M16 62 H84 M16 72 H84"
      stroke="#DC2626"
      strokeWidth="1.5"
      strokeOpacity="0.5"
    />

    {/* Face Skin */}
    <ellipse
      cx="50"
      cy="44"
      rx="19"
      ry="22"
      fill="#EAB308"
      fillOpacity="0.1"
      stroke="#FDE047"
      strokeWidth="1"
      strokeOpacity="0.1"
    />
    <ellipse cx="50" cy="44" rx="18" ry="21" fill="#FBCFE8" />

    {/* Agal (Double black cord keeping Keffiyeh in place) */}
    <path d="M22,29 Q50,19 78,29" stroke="#111" strokeWidth="3.5" fill="none" />
    <path d="M23,25 Q50,15 77,25" stroke="#222" strokeWidth="3.5" fill="none" />

    {/* Black traditional thick beard and mustache */}
    <path
      d="M31 42 C 31 62, 69 62, 69 42 C 69 43, 64 50, 50 54 C 36 50, 31 43, 31 42 Z"
      fill="#18181B"
    />
    <path
      d="M35 38 C43 38, 48 43, 50 46 C52 43, 57 38, 65 38 C57 41, 53 41, 50 43 C47 41, 43 41, 35 38 Z"
      fill="#09090B"
    />

    {/* Eyes and Brows */}
    <path
      d="M35 32 L45 31 M65 32 L55 31"
      stroke="#000"
      strokeWidth="2.5"
      strokeLinecap="round"
    />
    <circle cx="42" cy="37" r="2.5" fill="#000" />
    <circle cx="58" cy="37" r="2.5" fill="#000" />
    <circle cx="43" cy="36" r="0.8" fill="#FFF" />
    <circle cx="59" cy="36" r="0.8" fill="#FFF" />

    {/* Nose and smiling mouth */}
    <path
      d="M48,42 L50,44 L52,42"
      stroke="#FDA4AF"
      strokeWidth="1.5"
      strokeLinecap="round"
      fill="none"
    />
    <path
      d="M46 47 Q50 49 54 47"
      stroke="#050505"
      strokeWidth="2"
      fill="none"
      strokeLinecap="round"
    />
  </svg>
);

const Bot3Avatar = () => (
  <svg
    viewBox="0 0 100 100"
    className="w-full h-full rounded-xl overflow-hidden shadow-inner select-none pointer-events-none"
  >
    <rect width="100" height="100" fill="#3B0764" />
    {/* Tribal Nemes details */}
    <path d="M22 28 L10 48 L20 68 L28 38 Z" fill="#F59E0B" />
    <path d="M78 28 L90 48 L80 68 L72 38 Z" fill="#F59E0B" />

    {/* Crown cap */}
    <path
      d="M30 18 L50 4 L70 18 Z"
      fill="#D97706"
      stroke="#F59E0B"
      strokeWidth="1.5"
    />
    <circle cx="50" cy="11" r="2.5" fill="#38BDF8" />

    {/* Pharaoh mask plate */}
    <rect
      x="28"
      y="26"
      width="44"
      height="44"
      rx="8"
      fill="#1E1B4B"
      stroke="#F59E0B"
      strokeWidth="2"
    />

    {/* Glowing cyan visor strip */}
    <rect
      x="34"
      y="38"
      width="32"
      height="7"
      rx="3.5"
      fill="#38BDF8"
      className="animate-pulse"
    />
    <line
      x1="38"
      y1="41.5"
      x2="62"
      y2="41.5"
      stroke="#FFF"
      strokeWidth="1.5"
      strokeLinecap="round"
    />

    {/* Robotic chin beard */}
    <polygon
      points="44,70 56,70 53,86 47,86"
      fill="#D97706"
      stroke="#78350F"
      strokeWidth="1"
    />
  </svg>
);

const Bot2Avatar = () => (
  <svg
    viewBox="0 0 100 100"
    className="w-full h-full rounded-xl overflow-hidden shadow-inner select-none pointer-events-none"
  >
    <rect width="100" height="100" fill="#020617" />

    {/* Steel cylinder canister */}
    <rect
      x="25"
      y="42"
      width="50"
      height="44"
      rx="4"
      fill="url(#metalCan)"
      stroke="#64748B"
      strokeWidth="2"
    />

    {/* Handle of the canister mug */}
    <path
      d="M25 48 H 13 V 72 H 25"
      fill="none"
      stroke="#64748B"
      strokeWidth="3.5"
      strokeLinecap="round"
    />

    {/* Cybernetic dome inside the can */}
    <rect
      x="33"
      y="22"
      width="34"
      height="24"
      rx="6"
      fill="#1E293B"
      stroke="#475569"
      strokeWidth="1.5"
    />

    {/* Green warning light scanner */}
    <rect
      x="39"
      y="28"
      width="22"
      height="6"
      rx="3"
      fill="#22C55E"
      className="animate-pulse"
    />
    <circle cx="44" cy="31" r="1" fill="#FFF" />
    <circle cx="56" cy="31" r="1" fill="#FFF" />

    {/* External antenna with flashing tip */}
    <line x1="50" y1="22" x2="50" y2="10" stroke="#475569" strokeWidth="1.5" />
    <circle cx="50" cy="8" r="2.5" fill="#22C55E" />

    <defs>
      <linearGradient id="metalCan" x1="0%" y1="0%" x2="100%" y2="0%">
        <stop offset="0%" stopColor="#334155" />
        <stop offset="50%" stopColor="#94A3B8" />
        <stop offset="100%" stopColor="#1E293B" />
      </linearGradient>
    </defs>
  </svg>
);

const Bot1Avatar = () => (
  <svg
    viewBox="0 0 100 100"
    className="w-full h-full rounded-xl overflow-hidden shadow-inner select-none pointer-events-none"
  >
    <rect width="100" height="100" fill="#4C0519" />

    {/* Cute Clown / Birthday cone party hat */}
    <path d="M33 22 L50 4 L67 22 Z" fill="#EC4899" />
    {/* Multi colored vertical stripes */}
    <path d="M41 13 L50 22" stroke="#EAB308" strokeWidth="2" />
    <path d="M59 13 L50 22" stroke="#3B82F6" strokeWidth="2" />
    <circle cx="50" cy="3" r="2.5" fill="#F43F5E" />

    {/* Love robot sleek face */}
    <ellipse
      cx="50"
      cy="45"
      rx="23"
      ry="19"
      fill="#F472B6"
      stroke="#BE185D"
      strokeWidth="2"
    />

    {/* Neon heart shaped optical visors */}
    <path
      d="M37 40 C35 38, 31 38, 31 41 C31 44, 37 47, 37 47 C37 47, 43 44, 43 41 C43 38, 39 38, 37 40 Z"
      fill="#F43F5E"
      className="animate-pulse"
    />
    <path
      d="M63 40 C61 38, 57 38, 57 41 C57 44, 63 47, 63 47 C63 47, 69 44, 69 41 C69 38, 65 38, 63 40 Z"
      fill="#F43F5E"
      className="animate-pulse"
    />

    {/* Smiling digital face details */}
    <circle cx="32" cy="48" r="2.5" fill="#FDA4AF" />
    <circle cx="68" cy="48" r="2.5" fill="#FDA4AF" />
    <path
      d="M47 50 Q50 53 53 50"
      stroke="#FFF"
      strokeWidth="1.5"
      strokeLinecap="round"
      fill="none"
    />
  </svg>
);

// --- VISUAL STYLING HELPERS (Matched to Video) ---
const getSuitUI = (suit: Suit) => {
  switch (suit) {
    case "HEARTS":
      return { char: "♥", color: "text-[#B3252C]" };
    case "DIAMONDS":
      return { char: "♦", color: "text-[#B3252C]" };
    case "SPADES":
      return { char: "♠", color: "text-[#1D1A17]" };
    case "CLUBS":
      return { char: "♣", color: "text-[#1D1A17]" };
    default:
      return { char: "?", color: "text-gray-300" };
  }
};

const getTrumpInfo = (suit: Suit | "NONE") => {
  switch (suit) {
    case "SPADES":
      return {
        char: "♠",
        textColor: "text-[#1D1A17]",
        bgColor: "bg-[#E8D5B0]",
        textName: "SPADES",
      };
    case "HEARTS":
      return {
        char: "♥",
        textColor: "text-[#B3252C]",
        bgColor: "bg-[#E8D5B0]",
        textName: "HEARTS",
      };
    case "DIAMONDS":
      return {
        char: "♦",
        textColor: "text-[#B3252C]",
        bgColor: "bg-[#E8D5B0]",
        textName: "DIAMONDS",
      };
    case "CLUBS":
      return {
        char: "♣",
        textColor: "text-[#1D1A17]",
        bgColor: "bg-[#E8D5B0]",
        textName: "CLUBS",
      };
    default:
      return {
        char: "S",
        textColor: "text-[#1D1A17]",
        bgColor: "bg-[#D4AF37]",
        textName: "SANS",
      };
  }
};

const getRankUI = (rank: string) => {
  const map: any = {
    ACE: "A",
    KING: "K",
    QUEEN: "Q",
    JACK: "J",
    TEN: "10",
    NINE: "9",
    EIGHT: "8",
    SEVEN: "7",
    SIX: "6",
    FIVE: "5",
    FOUR: "4",
    THREE: "3",
    TWO: "2",
  };
  return map[rank] || rank;
};

// --- ANDROID CODE HIGH-CONTRAST SYNTAX HIGHLIGHTER & EXPLANATION MAPS ---
function renderCustomKotlinHighlight(code: string) {
  if (!code) return "";

  let html = code
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");

  // Comments
  html = html.replace(
    /(\/\/.*)/g,
    '<span class="text-emerald-500/70">$1</span>',
  );

  // Keywords
  const keywords = [
    "package",
    "import",
    "sealed class",
    "class",
    "interface",
    "data class",
    "object",
    "enum class",
    "fun ",
    "val ",
    "var ",
    "private ",
    "internal ",
    "if ",
    "else ",
    "when ",
    "is ",
    "return ",
    "override ",
    "get\\(\\)",
    "by ",
    "remember",
    "rememberCoroutineScope",
    "launch",
    "delay",
  ];

  keywords.forEach((keyword) => {
    const rKey = keyword.replace(" ", "\\s+");
    const regex = new RegExp(`\\b(${rKey})`, "g");
    html = html.replace(
      regex,
      '<span class="text-amber-500 font-bold">$1</span>',
    );
  });

  // State Types & Suit mapping
  const types = [
    "GameState",
    "GameUiState",
    "GameIntent",
    "GamePhase",
    "GameEffect",
    "Suit",
    "Rank",
    "Card",
    "Bid",
    "PlayerId",
    "DashCall",
    "DashCallStatus",
    "Trick",
    "RoundResult",
    "PlayerState",
    "AnimatingCard",
    "GameViewModel",
    "Color",
    "Modifier",
    "Composable",
    "MutableStateFlow",
    "StateFlow",
  ];
  types.forEach((type) => {
    const regex = new RegExp(`\\b(${type})\\b`, "g");
    html = html.replace(
      regex,
      '<span class="text-cyan-400 font-extrabold">$1</span>',
    );
  });

  // Numbers (Integers & floats like 1.dp, 13, 0xFFEF4444)
  html = html.replace(
    /\b(\d+L?|\d+\.dp|\d+f)\b/g,
    '<span class="text-indigo-400 font-black">$1</span>',
  );
  html = html.replace(
    /\b(0x[0-9A-Fa-f]+)\b/g,
    '<span class="text-indigo-400 font-black">$1</span>',
  );

  // Strings (quoted text)
  html = html.replace(
    /("[^"]*")/g,
    '<span class="text-emerald-400 font-medium">$1</span>',
  );

  return <span dangerouslySetInnerHTML={{ __html: html }} />;
}

function getFileImplementationMapping(fileName: string): string {
  switch (fileName) {
    case "GameModels.kt":
      return "Defines key Domain entities for the Arabic/Egypt card game Estemshan. Model entities translate game-specific bidding states, from the standard risk-evaluated Bid (Pass, Tricks amount, Dash Call) to the DashCallStatus lifecycle (PENDING, DECLINED, DECLARED). State is kept immutable through the container GameState class.";
    case "GameReducer.kt":
      return "Contains pure Reducer logic translating player actions (GameIntent) into new GameState instances. Fully implements Egypt Estemshan bidding overrides, SANS (No Trump Color) higher hierarchy, automatic Double-Multiplier on 4-Player General Pass rule, and trick tracking matching exact Egyptian playing constraints.";
    case "GameViewModel.kt":
      return "The MVI-aligned controller managing coroutine state flows and bot-intelligence heuristics. Automatically observes game conditions to trigger CPU bot bidding assessments, calculates card suit strengths, and adapts bids dynamically with a localized offline reinforcement learning loop (bot learning/biases).";
    case "PlayingCard.kt":
      return "A custom Jetpack Compose composable rendering a single 3D card layout. Uses smooth gradient shading, layered text indexes, drop shadows, and subtle geometric ornaments mimicking hand-held game cards in high-density mobile viewports.";
    case "GameScreen.kt":
      return "The master UI coordinator mirroring the actual physical card table. Built from smooth vector canvas layers with mahogany velvet carpets and rustic gold borders, and arranges players in a 4-point compass cross (You at bottom, Omar at right, Fatima at left, Amir at top).";
    default:
      return "Provides high-quality Android Kotlin production file matching Egyption card game rules & specifications.";
  }
}

const MOVIE_MEMES = [
  { label: "Tari2a Meme (طريقة) 🌶️", quote: "يا فاشل يا فاشل! 🌶️" },
  { label: "Shocked Meme (صدمة) 😮", quote: "هو كله ضرب ضرب مفيش شتيمة؟ 😮" },
  { label: "Angry Meme (غضب) 😡", quote: "أنا لا أسمح لك! 😡" },
  {
    label: "Celebrating Meme (احتفال) 🎉",
    quote: "الله أكبر عليك.. إيه الحلاوة دي! 🎉",
  },
];

export default function App() {
  const [activeTab, setActiveTab] = useState<
    "simulation" | "android-generator"
  >("simulation");
  const [selectedFileIndex, setSelectedFileIndex] = useState(0);
  const [projectFiles, setProjectFiles] = useState(() => {
    const saved =
      typeof window !== "undefined"
        ? localStorage.getItem("estimation_android_project_files")
        : null;
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        if (Array.isArray(parsed) && parsed.length > 0) {
          return parsed;
        }
      } catch (e) {
        console.error("Failed to restore project files", e);
      }
    }
    return kotlinProjectFiles;
  });
  const [isEditingMode, setIsEditingMode] = useState(false);
  const [copiedIndex, setCopiedIndex] = useState<number | null>(null);
  const [savedFileIndex, setSavedFileIndex] = useState<number | null>(null);
  const [activeReaction, setActiveReaction] = useState<{ text: string } | null>(
    null,
  );
  const [showReactionMenu, setShowReactionMenu] = useState(false);
  const [round, setRound] = useState(1);
  const [maxRounds, setMaxRounds] = useState(18);
  const [scoringMode, setScoringMode] = useState<ScoringMode>("NORMAL");
  const [phase, setPhase] = useState<GamePhase>("DEALING");
  const [multiplier, setMultiplier] = useState(1);
  const [players, setPlayers] = useState<PlayerModel[]>([
    {
      id: "p1",
      name: "You (باشا)",
      hand: [],
      bid: -1,
      isDashCall: false,
      isBiddingActive: true,
      tricksWon: 0,
      score: 0,
      isUser: true,
    },
    {
      id: "p2",
      name: "Fatima (جدع)",
      hand: [],
      bid: -1,
      isDashCall: false,
      isBiddingActive: true,
      tricksWon: 0,
      score: 0,
      isUser: false,
      difficulty: "EASY",
      personality: "CONSERVATIVE",
    },
    {
      id: "p3",
      name: "Amir (حريف)",
      hand: [],
      bid: -1,
      isDashCall: false,
      isBiddingActive: true,
      tricksWon: 0,
      score: 0,
      isUser: false,
      difficulty: "HARD",
      personality: "AGGRESSIVE",
    },
    {
      id: "p4",
      name: "Omar (الأسطورة)",
      hand: [],
      bid: -1,
      isDashCall: false,
      isBiddingActive: true,
      tricksWon: 0,
      score: 0,
      isUser: false,
      difficulty: "EXPERT",
      personality: "TRICKSTER",
    },
  ]);

  const updatePlayerField = (id: string, field: string, val: any) => {
    setPlayers((prev) =>
      prev.map((p) => (p.id === id ? { ...p, [field]: val } : p))
    );
  };

  const [showHowToPlayModal, setShowHowToPlayModal] = useState(false);
  const [helpPage, setHelpPage] = useState<"ui" | "rules">("ui");

  const [trumpSuit, setTrumpSuit] = useState<Suit | "NONE">("NONE");
  const [activePlayerIdx, setActivePlayerIdx] = useState(0);
  const [biddingTurnIdx, setBiddingTurnIdx] = useState(0);
  const [bidsPlacedCount, setBidsPlacedCount] = useState(0);
  const [dashDecisionMap, setDashDecisionMap] = useState<
    Record<string, "PENDING" | "YES" | "NO">
  >({
    p1: "PENDING",
    p2: "PENDING",
    p3: "PENDING",
    p4: "PENDING",
  });
  const [highestBidderId, setHighestBidderId] = useState<string | null>(null);
  const [highestBidTricks, setHighestBidTricks] = useState<number>(0);
  const [highestBidSuit, setHighestBidSuit] = useState<Suit | "NONE">("NONE");
  const [selectedBidTricks, setSelectedBidTricks] = useState<number | null>(
    null,
  );
  const [selectedBidSuit, setSelectedBidSuit] = useState<Suit | "NONE" | null>(
    null,
  );
  const [selectedEstimateSuit, setSelectedEstimateSuit] = useState<
    Suit | "NONE"
  >("NONE");

  // Refs to prevent race conditions or stale timeouts executing bot actions out of turn
  const biddingTurnIdxRef = useRef(biddingTurnIdx);
  biddingTurnIdxRef.current = biddingTurnIdx;
  const activePlayerIdxRef = useRef(activePlayerIdx);
  activePlayerIdxRef.current = activePlayerIdx;
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  const [currentTrick, setCurrentTrick] = useState<PlayedCard[]>([]);
  const [seenCards, setSeenCards] = useState<CardModel[]>([]);
  const seenCardsRef = useRef<CardModel[]>([]);
  useEffect(() => { seenCardsRef.current = seenCards; }, [seenCards]);
  const playersRef = useRef<PlayerModel[]>(players);
  useEffect(() => { playersRef.current = players; }, [players]);
  const [ledSuit, setLedSuit] = useState<Suit | null>(null);
  const [log, setLog] = useState<LogEntry[]>([
    { message: "Welcome! Dealer is ready." },
  ]);
  const [roundResults, setRoundResults] = useState<any[] | null>(null);
  const [copiedText, setCopiedText] = useState<string | null>(null);
  const [auctionBids, setAuctionBids] = useState<
    Record<string, { bid: number; suit: Suit | "NONE" }>
  >({});

  const [showAuditModal, setShowAuditModal] = useState(false);
  const [auditModalLogs, setAuditModalLogs] = useState<string[]>([]);

  const [lastCompletedTrick, setLastCompletedTrick] = useState<
    PlayedCard[] | null
  >(null);
  const [showLastTrickModal, setShowLastTrickModal] = useState(false);
  const [showLedgerModal, setShowLedgerModal] = useState(false);
  const [showScoresDropdown, setShowScoresDropdown] = useState(false);
  const [showHistoryModal, setShowHistoryModal] = useState(false);
  const [botDecisionsHistory, setBotDecisionsHistory] = useState<any[]>([]);
  const [showBotDebuggerModal, setShowBotDebuggerModal] = useState(false);
  const [selectedBotDecision, setSelectedBotDecision] = useState<any | null>(
    null,
  );
  const [turnTimer, setTurnTimer] = useState(15);
  const [lastBidderState, setLastBidderState] = useState<string>("");

  const derivedLedSuit =
    currentTrick.length > 0 ? currentTrick[0].card.suit : null;

  const hasSuperCall = highestBidTricks >= 8;
  const hasAllBids = players.every((p) => p.bid !== -1);
  const overUnderValue = hasAllBids
    ? players.reduce((sum, p) => sum + p.bid, 0) - 13
    : null;

  const copyCSVToClipboard = () => {
    const roundStatsList = log
      .map((entry) => entry.stats)
      .filter((s): s is RoundStat => !!s);
    let csv =
      "Round,Trump,Multiplier,You (Bid),You (Won),You (ScoreDelta),Fatima (Bid),Fatima (Won),Fatima (ScoreDelta),Amir (Bid),Amir (Won),Amir (ScoreDelta),Omar (Bid),Omar (Won),Omar (ScoreDelta)\n";
    roundStatsList.forEach((rs) => {
      const p1 = rs.playerStats.find((ps) => ps.id === "p1");
      const p2 = rs.playerStats.find((ps) => ps.id === "p2");
      const p3 = rs.playerStats.find((ps) => ps.id === "p3");
      const p4 = rs.playerStats.find((ps) => ps.id === "p4");

      csv += `${rs.round},${rs.trumpSuit},x${rs.multiplier},`;
      csv += `${p1?.isDashCall ? "DASH" : p1?.bid},${p1?.tricksWon},${p1?.finalScore},`;
      csv += `${p2?.isDashCall ? "DASH" : p2?.bid},${p2?.tricksWon},${p2?.finalScore},`;
      csv += `${p3?.isDashCall ? "DASH" : p3?.bid},${p3?.tricksWon},${p3?.finalScore},`;
      csv += `${p4?.isDashCall ? "DASH" : p4?.bid},${p4?.tricksWon},${p4?.finalScore}\n`;
    });

    navigator.clipboard.writeText(csv);
    setCopiedText("Copied CSV!");
    setTimeout(() => setCopiedText(null), 2500);
  };

  const copyTextReportToClipboard = () => {
    const sortedStandings = [...players].sort((a, b) => b.score - a.score);
    const champ = sortedStandings[0] || { name: "None", score: 0 };
    const roundStatsList = log
      .map((entry) => entry.stats)
      .filter((s): s is RoundStat => !!s);
    const accuracyData = players.map((p) => {
      const playerRounds = roundStatsList
        .map((rs) => rs.playerStats.find((ps) => ps.id === p.id))
        .filter(Boolean);
      const successCount = playerRounds.filter((r) => r?.isSuccess).length;
      const accuracy =
        playerRounds.length > 0
          ? (successCount / playerRounds.length) * 100
          : 0;
      return {
        id: p.id,
        name: p.name,
        accuracy,
        successCount,
        total: playerRounds.length,
      };
    });

    let text = `🏆 ESTEMSHAN BOARD ARENA MATCH SUMMARY 🏆\n=======================================\n`;
    text += `GRAND CHAMPION: ${champ.name} (${champ.score} PTS)\n\n`;
    text += `FINAL STANDINGS:\n`;
    sortedStandings.forEach((p, idx) => {
      text += `#${idx + 1} ${p.name}: ${p.score} PTS\n`;
    });
    text += `\nBIDDING ACCURACY:\n`;
    accuracyData.forEach((p) => {
      text += `${p.name}: ${p.accuracy.toFixed(1)}% (${p.successCount}/${p.total} Rounds)\n`;
    });
    text += `\nROUND-BY-ROUND STATS:\n=====================\n`;
    roundStatsList.forEach((rs) => {
      text += `Round ${rs.round} (Trump: ${rs.trumpSuit}, Multiplier: x${rs.multiplier})\n`;
      rs.playerStats.forEach((ps) => {
        text += `  - ${ps.name}: Bid ${ps.isDashCall ? "DASH" : ps.bid}, Won ${ps.tricksWon}, Delta ${ps.finalScore >= 0 ? "+" : ""}${ps.finalScore} PTS\n`;
      });
      text += `\n`;
    });

    navigator.clipboard.writeText(text);
    setCopiedText("Copied Report!");
    setTimeout(() => setCopiedText(null), 2500);
  };

  // Reset user's selected bid on turn or phase transition, pre-selecting their previous bid if they are the returning caller
  useEffect(() => {
    if (phase === "CALL_PHASE" && biddingTurnIdx === 0) {
      if (highestBidderId === "p1" && highestBidTricks > 0) {
        setSelectedBidTricks(highestBidTricks);
        setSelectedBidSuit(highestBidSuit);
      } else {
        setSelectedBidTricks(null);
        setSelectedBidSuit(null);
      }
    } else if (phase === "FOLLOWING_BIDS" && biddingTurnIdx === 0) {
      setSelectedBidTricks(null);
      setSelectedBidSuit(null);
      setSelectedEstimateSuit("NONE");
    }
  }, [
    phase,
    biddingTurnIdx,
    highestBidderId,
    highestBidTricks,
    highestBidSuit,
  ]);

  const getEffectiveTrumpSuit = (): Suit | "NONE" => {
    if (round >= 14) {
      if (hasSuperCall) {
        return highestBidSuit;
      }
      const fixedSequence: (Suit | "NONE")[] = [
        "NONE",
        "SPADES",
        "HEARTS",
        "DIAMONDS",
        "CLUBS",
      ];
      return fixedSequence[(round - 14) % fixedSequence.length];
    }
    return trumpSuit;
  };
  const effectiveTrumpSuit = getEffectiveTrumpSuit();
  const showTrumpWidget = ["FOLLOWING_BIDS", "PLAYING", "ROUND_OVER"].includes(
    phase,
  );

  const nextTurn = (idx: number) => (idx + 3) % 4;
  const pushLog = (msg: string, stats?: RoundStat) =>
    setLog((prev) => [...prev, { message: msg, stats }]);

  // Rule: Must follow suit if any card of led suit is present in hand
  const isPlayLegal = (
    card: CardModel,
    hand: CardModel[],
    trickCards: PlayedCard[],
  ): boolean => {
    const activeLed = trickCards.length > 0 ? trickCards[0].card.suit : null;
    if (!activeLed) return true;
    const hasLedSuit = hand.some((c) => c.suit === activeLed);
    if (hasLedSuit) {
      return card.suit === activeLed;
    }
    return true;
  };

  const dealCards = () => {
    const deck = shuffleDeck(createNewDeck());
    const newHands = [
      sortHand(deck.slice(0, 13)),
      sortHand(deck.slice(13, 26)),
      sortHand(deck.slice(26, 39)),
      sortHand(deck.slice(39, 52)),
    ];

    const checkAvoid = (hand: CardModel[]): boolean => {
      const suits: Suit[] = ["HEARTS", "DIAMONDS", "CLUBS", "SPADES"];
      return suits.some((s) => !hand.some((c) => c.suit === s));
    };

    setPlayers((prev) =>
      prev.map((p, i) => {
        const hand = newHands[i];
        const hasAvoid = checkAvoid(hand);
        return {
          ...p,
          hand,
          tricksWon: 0,
          bid: -1,
          isDashCall: false,
          hasAvoid,
          isBiddingActive: true,
          isWazz: false,
        };
      }),
    );
    setSelectedBidTricks(null);
    setSelectedBidSuit(null);
    setLastBidderState("");
    setDashDecisionMap({
      p1: "PENDING",
      p2: "PENDING",
      p3: "PENDING",
      p4: "PENDING",
    });

    const dealerIdx = (((1 - round) % 4) + 4) % 4;
    if (round >= 14) {
      setPhase("CALL_PHASE");
      setBiddingTurnIdx(dealerIdx);
    } else {
      setPhase("DASH_CALL_DECISION");
      setBiddingTurnIdx(dealerIdx);
    }

    setBidsPlacedCount(0);
    setHighestBidderId(null);
    setHighestBidTricks(0);
    setHighestBidSuit("NONE");
    setTrumpSuit("NONE");
    setLedSuit(null);
    setCurrentTrick([]);
    setSeenCards([]);
    setAuctionBids({});
    pushLog(`--- Starting Round ${round} ---`);
  };

  const submitDashCall = (playerId: string, wantsDash: boolean) => {
    if (wantsDash) {
      const dashCount = players.filter(
        (p) => p.isDashCall && p.id !== playerId,
      ).length;
      if (dashCount >= 2) return;
    }
    // 1. Immediately record dash selection on the player model
    setPlayers((prev) =>
      prev.map((p) =>
        p.id === playerId
          ? { ...p, isDashCall: wantsDash, bid: wantsDash ? 0 : -1 }
          : p,
      ),
    );

    // 2. Safely log the choice (YES or NO) under explicit map to avoid race/counter corruptions
    setDashDecisionMap((prev) => {
      const updatedMap = {
        ...prev,
        [playerId]: (wantsDash ? "YES" : "NO") as "YES" | "NO",
      };

      const allCasted = Object.values(updatedMap).every(
        (status) => status !== "PENDING",
      );

      if (!allCasted) {
        // Increment the virtual count and step to next counter-clockwise active bidder
        setBiddingTurnIdx(nextTurn(biddingTurnIdx));
        setBidsPlacedCount((curr) => curr + 1);
      } else {
        // All players have explicitly answered YES or NO (decline) - proceed to CALL_PHASE
        setPhase("CALL_PHASE");
        const dealerIdx = (((1 - round) % 4) + 4) % 4;
        const firstBidderIdx = dealerIdx;
        setBiddingTurnIdx(firstBidderIdx);
        setBidsPlacedCount(0);

        setPlayers((prevPlayers) =>
          prevPlayers.map((p) => {
            const isDash = updatedMap[p.id] === "YES";
            return {
              ...p,
              isDashCall: isDash,
              bid: isDash ? 0 : -1,
              isBiddingActive: !isDash,
              isWazz: false,
            };
          }),
        );
      }
      return updatedMap;
    });
  };

  const finalizeAuction = (
    winnerId: string,
    winnerBidTricks: number,
    winnerBidSuit: Suit | "NONE",
    finalPlayersList: PlayerModel[],
  ) => {
    pushLog(
      `🏁 Bidding completed! Caller is ${winnerId === "p1" ? "You" : finalPlayersList.find((p) => p.id === winnerId)?.name || ""} with ${winnerBidTricks} on ${winnerBidSuit === "NONE" ? "SANS (No Color)" : winnerBidSuit}. Entering Trump Declaration phase (Upgrades allowed).`,
    );

    setPhase("TRUMP_DECLARATION");

    const callerIdx = finalPlayersList.findIndex((p) => p.id === winnerId);
    setBiddingTurnIdx(callerIdx);
  };

  const submitTrumpDeclaration = (
    finalBidTricks: number,
    finalBidSuit: Suit | "NONE",
  ) => {
    // Defense-in-depth: reject an illegal upgrade even if the UI somehow allowed it.
    const isConfirmOriginal =
      finalBidTricks === highestBidTricks && finalBidSuit === highestBidSuit;
    if (
      !isConfirmOriginal &&
      !isBidStronger(
        finalBidTricks,
        finalBidSuit,
        highestBidTricks,
        highestBidSuit,
      )
    ) {
      pushLog(
        `🚨 Rejected illegal Trump Declaration: ${finalBidTricks} ${finalBidSuit === "NONE" ? "SANS" : finalBidSuit} is not stronger than ${highestBidTricks} ${highestBidSuit === "NONE" ? "SANS" : highestBidSuit}.`,
      );
      return;
    }

    pushLog(
      `📢 Trump Declaration Phase Completed! Caller ${highestBidderId === "p1" ? "You" : players.find((p) => p.id === highestBidderId)?.name || ""} declared final contract of ${finalBidTricks} tricks on ${finalBidSuit === "NONE" ? "SANS (No Color)" : finalBidSuit}.`,
    );

    setHighestBidTricks(finalBidTricks);
    setHighestBidSuit(finalBidSuit);
    setTrumpSuit(finalBidSuit);

    const finalizedPlayers = players.map((p) => {
      if (p.id === highestBidderId) {
        return { ...p, bid: finalBidTricks };
      }

      // "With" via Auction Alignment (rules §2.2.1a): a player who bid the SAME SUIT
      // as the eventual winning Caller at any point during the auction becomes With,
      // regardless of their own trick number. This is judged on the suit of their last
      // real (non-passed) auction bid, so a player who later switched to a defeated
      // suit correctly loses alignment (Defeated Suit Rule).
      const playerAuction = auctionBids[p.id];
      const isAuctionAligned =
        playerAuction &&
        playerAuction.bid !== -1 &&
        playerAuction.suit === finalBidSuit;

      if (isAuctionAligned) {
        return { ...p, isWazz: true, bid: finalBidTricks };
      }

      if (p.isDashCall) {
        return { ...p, isWazz: false, bid: 0 };
      }
      return { ...p, isWazz: false, bid: -1 };
    });

    setPlayers(finalizedPlayers);

    // Find the first player who still needs to estimate, starting left of the Caller.
    // If everyone's bid was already resolved above (Caller + Dash Callers + any
    // Auction-Aligned With players can cover all 4 seats), skip straight to PLAYING
    // instead of softlocking in FOLLOWING_BIDS with nobody left to act.
    const callerIdx = finalizedPlayers.findIndex(
      (p) => p.id === highestBidderId,
    );
    let nextIdx = nextTurn(callerIdx);
    let loops = 0;
    let found = false;
    while (loops < 4) {
      if (finalizedPlayers[nextIdx].bid === -1) {
        found = true;
        break;
      }
      nextIdx = nextTurn(nextIdx);
      loops++;
    }

    if (found) {
      setPhase("FOLLOWING_BIDS");
      setBiddingTurnIdx(nextIdx);
    } else {
      setPhase("PLAYING");
      setActivePlayerIdx(callerIdx);
      setLastBidderState(finalizedPlayers[(callerIdx + 1) % 4].id);
      pushLog(
        `🏁 All estimations already resolved! Caller leads the first trick.`,
      );
    }
  };

  const submitCall = (
    playerId: string,
    bidVal: number,
    suit: Suit | "NONE",
    isEqualize = false,
  ) => {
    if (
      bidVal === -1 &&
      playerId === highestBidderId &&
      highestBidderId !== null
    ) {
      pushLog(
        `🚨 Rejected illegal pass! As the current Caller with a bid of ${highestBidTricks}, you cannot pass. You must confirm or upgrade your contract.`,
      );
      return;
    }

    const playerIdx = players.findIndex((p) => p.id === playerId);
    const otherBids = players
      .filter((p) => p.id !== playerId && p.bid !== -1)
      .map((p) => p.bid);
    const isInitialFastRoundPass = round >= 14 && highestBidderId === null;
    const validation = validateBidding(
      bidVal,
      playerIdx,
      otherBids,
      "CALL_PHASE",
      highestBidTricks,
      phase,
      biddingTurnIdx,
      players[playerIdx]?.isDashCall,
      isInitialFastRoundPass,
    );
    if (!validation.valid) {
      pushLog(
        `🚨 Rejected illegal call! ${players.find((p) => p.id === playerId)?.name || playerId} tried to call ${bidVal}, but: ${validation.error}`,
      );
      return;
    }

    // 1. Calculate locally updated player states first for accurate state prediction
    let updatedPlayersLocal = players.map((p) => {
      if (p.id === playerId) {
        if (bidVal === -1) {
          return { ...p, isBiddingActive: false, isWazz: false };
        } else if (isEqualize) {
          return { ...p, isWazz: true, bid: bidVal };
        } else {
          return { ...p, bid: bidVal };
        }
      }
      // A new raise invalidates any prior equalizing Wazz whenever the raise changes
      // EITHER the suit OR the trick count — a same-suit number raise still means the
      // previously-equalized player's bid no longer matches the new highest contract.
      if (bidVal !== -1 && !isEqualize && p.id !== playerId) {
        if (suit !== highestBidSuit || bidVal !== p.bid) {
          return { ...p, isWazz: false };
        }
      }
      return p;
    });

    setPlayers(updatedPlayersLocal);

    const isFastRound = round >= 14;
    // Initial pass of typical fast round is when highestBidderId is null
    if (isFastRound && highestBidderId === null) {
      let updatedBidsMap = {
        ...auctionBids,
        [playerId]: { bid: bidVal, suit },
      };
      setAuctionBids(updatedBidsMap);

      setPlayers(updatedPlayersLocal);

      const allPlayersPlacedBids = updatedPlayersLocal.every(
        (p) => p.bid !== -1 || p.isBiddingActive === false,
      );
      if (allPlayersPlacedBids) {
        // Handle case where everyone passed
        if (updatedPlayersLocal.every((p) => p.isBiddingActive === false)) {
          const seq = [1, 2, 4, 6, 8];
          const nextMult =
            seq[Math.min(seq.indexOf(multiplier) + 1, seq.length - 1)];
          setMultiplier(nextMult);
          pushLog(
            `⚠️ All players passed in Fast Round! Round skipped. Next round x${nextMult} multiplier`,
          );
          setRound((r) => r + 1);
          setPhase("DEALING");
          return;
        }

        // Bidding order starting from dealerIdx
        const dealerIdx = (((1 - round) % 4) + 4) % 4;
        const orderIds: string[] = [];
        let tempIdx = dealerIdx;
        for (let i = 0; i < 4; i++) {
          orderIds.push(players[tempIdx].id);
          tempIdx = nextTurn(tempIdx);
        }

        // Check for Super Callers (>= 8)
        const superCallers = orderIds.filter((pId) => {
          const p = updatedPlayersLocal.find((x) => x.id === pId);
          return p && p.bid >= 8;
        });

        if (superCallers.length >= 2) {
          pushLog(
            `🚨 SUPER CALL MINI-AUCTION! ${superCallers.length} players bidded >= 8. An exclusive auction opens up between them!`,
          );

          let maxBid = -1;
          let maxStrength = -1;
          let fastCallerId = superCallers[0];

          superCallers.forEach((pId) => {
            const b = updatedBidsMap[pId].bid;
            const s = updatedBidsMap[pId].suit;
            const strength = getSuitStrength(s);
            if (b > maxBid || (b === maxBid && strength > maxStrength)) {
              maxBid = b;
              maxStrength = strength;
              fastCallerId = pId;
            }
          });

          const auctionPlayers = updatedPlayersLocal.map((p) => {
            if (superCallers.includes(p.id)) {
              // If they are the highest bidder so far, keep their bid. Else, reset to wait for their next action.
              if (p.id === fastCallerId) {
                return { ...p, isBiddingActive: true, bid: maxBid };
              } else {
                return { ...p, isBiddingActive: true, bid: -1 };
              }
            } else {
              // Passed players permanently withdraw from the mini-auction
              return { ...p, isBiddingActive: false, isWazz: false };
            }
          });

          setPlayers(auctionPlayers);
          setHighestBidderId(fastCallerId);
          setHighestBidTricks(maxBid);
          setHighestBidSuit(updatedBidsMap[fastCallerId].suit);
          setTrumpSuit(updatedBidsMap[fastCallerId].suit);

          const mandatory = ["NONE", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"][
            (round - 14) % 5
          ] as Suit | "NONE";
          const callerFinalSuit = updatedBidsMap[fastCallerId]?.suit || "NONE";
          if (maxBid >= 8 && callerFinalSuit !== mandatory) {
            setMaxRounds((m) => m + 1);
            pushLog(
              `🔄 Super Call overrode the forced suit — the game is extended by one round.`,
            );
          }

          // Find the turn of the first super caller who is NOT the current leader (or start from beginning)
          // Wait, the rule: "The auction starts with the first player to request a Super Call"
          // Since the first super caller might not be the highest, we start with the first super caller.
          const firstSuperCallerIdx = players.findIndex(
            (p) => p.id === superCallers[0],
          );
          if (fastCallerId !== superCallers[0]) {
            setBiddingTurnIdx(firstSuperCallerIdx);
          } else {
            // If first is highest, move to the next super caller
            const nextSuperCallerIdx = players.findIndex(
              (p) => p.id === superCallers[1],
            );
            setBiddingTurnIdx(nextSuperCallerIdx);
          }
          return;
        }

        // NO MINI-AUCTION (0 or 1 super caller)
        // Find the highest bid amount among all 4 players
        const maxBid = Math.max(...updatedPlayersLocal.map((p) => p.bid));

        // Find all players who bid this highest number
        const topBidders = orderIds.filter((pId) => {
          const p = updatedPlayersLocal.find((x) => x.id === pId);
          return p && p.bid === maxBid;
        });

        // Fast-round Caller/With rule (§3): the FIRST player to bid the highest number
        // is the Caller — no suit-strength tiebreak here (that only applies to the
        // separate multi-Super-Call mini-auction above). Every other player who bid
        // that same number becomes With.
        const fastCallerId = topBidders[0];
        const fastWithIds = topBidders.filter((pId) => pId !== fastCallerId);

        pushLog(
          `🏁 Fast Round Call Complete! Highest Bid is ${maxBid} by ${fastCallerId === "p1" ? "You" : players.find((p) => p.id === fastCallerId)?.name}.`,
        );
        if (fastWithIds.length > 0) {
          const names = fastWithIds
            .map((id) =>
              id === "p1" ? "You" : players.find((p) => p.id === id)?.name,
            )
            .join(", ");
          pushLog(`🤝 ${names} bid the same number and became WITH partners!`);
        }

        // Assign roles and transition to FOLLOWING_BIDS
        setHighestBidderId(fastCallerId);
        setHighestBidTricks(maxBid);
        setHighestBidSuit(updatedBidsMap[fastCallerId]?.suit || "NONE");
        setTrumpSuit(updatedBidsMap[fastCallerId]?.suit || "NONE");

        const mandatory = ["NONE", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"][
          (round - 14) % 5
        ] as Suit | "NONE";
        const callerFinalSuit = updatedBidsMap[fastCallerId]?.suit || "NONE";
        if (maxBid >= 8 && callerFinalSuit !== mandatory) {
          setMaxRounds((m) => m + 1);
          pushLog(
            `🔄 Super Call overrode the forced suit — the game is extended by one round.`,
          );
        }

        // To determine who needs to re-estimate, we find players who preceded the super caller
        const superCallerIdxInOrder = orderIds.indexOf(fastCallerId);
        const playersNeedingReEstimate = orderIds.slice(
          0,
          superCallerIdxInOrder,
        );

        const finalPlayers = updatedPlayersLocal.map((p) => {
          if (p.id === fastCallerId) {
            return { ...p, bid: maxBid, isWazz: false };
          }
          if (fastWithIds.includes(p.id)) {
            return { ...p, bid: maxBid, isWazz: true };
          }

          // The rest keep their original estimation number, UNLESS a Super Call changed the context!
          // Only players who bid before the super call need to re-estimate.
          if (maxBid >= 8 && playersNeedingReEstimate.includes(p.id)) {
            return { ...p, bid: -1, isWazz: false };
          }
          return { ...p, isWazz: false };
        });

        setPlayers(finalPlayers);
        setPhase("FOLLOWING_BIDS");

        // Following Bids logic: Find the first player who needs to estimate starting left of the Caller
        const callerIdx = finalPlayers.findIndex((p) => p.id === fastCallerId);
        let nextIdx = nextTurn(callerIdx);
        let loops = 0;
        let found = false;
        while (loops < 4) {
          if (finalPlayers[nextIdx].bid === -1) {
            found = true;
            break;
          }
          nextIdx = nextTurn(nextIdx);
          loops++;
        }
        if (found) {
          setBiddingTurnIdx(nextIdx);
        } else {
          // Immediately play if everyone already has valid bids?
          // Yes, in fast rounds they all already bid during the first pass!
          setPhase("PLAYING");
          setActivePlayerIdx(callerIdx);
          setLastBidderState(orderIds[3]);
          const leaderName =
            callerIdx === 0 ? "You" : finalPlayers[callerIdx].name;
          pushLog(
            `🏁 Estimations completed! Caller is ${leaderName} and leads the first trick.`,
          );
        }
        return;
      }

      // Find the circular next player to bid who has bid === -1
      const currentBidderIdx = updatedPlayersLocal.findIndex(
        (p) => p.id === playerId,
      );
      let nextIdx = nextTurn(currentBidderIdx);
      let loops = 0;
      let found = false;
      while (loops < 4) {
        if (updatedPlayersLocal[nextIdx].bid === -1) {
          found = true;
          break;
        }
        nextIdx = nextTurn(nextIdx);
        loops++;
      }
      if (found) {
        setBiddingTurnIdx(nextIdx);
      }
      return;
    }

    let nextBidderId = highestBidderId;
    let nextBidTricks = highestBidTricks;
    let nextBidSuit = highestBidSuit;

    if (bidVal !== -1) {
      if (isEqualize) {
        pushLog(
          `🤝 ${playerId === "p1" ? "You" : players.find((p) => p.id === playerId)?.name} called WAZZ (Equalized) on ${bidVal} ${suit === "NONE" ? "SANS (No Color)" : suit}!`,
        );
        // For visualizing badge in the GUI
        setAuctionBids((prev) => ({
          ...prev,
          [playerId]: { bid: bidVal, suit },
        }));
      } else {
        nextBidderId = playerId;
        nextBidTricks = bidVal;
        nextBidSuit = suit;
        setHighestBidderId(playerId);
        setHighestBidTricks(bidVal);
        setHighestBidSuit(suit);
        setTrumpSuit(suit);

        setAuctionBids((prev) => ({
          ...prev,
          [playerId]: { bid: bidVal, suit },
        }));

        pushLog(
          `👑 ${playerId === "p1" ? "You" : players.find((p) => p.id === playerId)?.name} Bid: ${bidVal} on ${suit === "NONE" ? "SANS (No Color)" : suit}`,
        );
      }
    } else {
      pushLog(
        `❌ ${playerId === "p1" ? "You" : players.find((p) => p.id === playerId)?.name} passed.`,
      );
      if (playerId === highestBidderId) {
        // Look for any other player who is still active and has isWazz === true
        const wazzPartnerIdx = updatedPlayersLocal.findIndex(
          (p) => p.isBiddingActive && p.isWazz,
        );
        if (wazzPartnerIdx !== -1) {
          const wazzPartner = updatedPlayersLocal[wazzPartnerIdx];

          nextBidderId = wazzPartner.id;
          nextBidTricks = highestBidTricks; // Keep current high bid details
          nextBidSuit = highestBidSuit;

          setHighestBidderId(wazzPartner.id);
          pushLog(
            `🔄 Wazz partner ${wazzPartner.id === "p1" ? "You" : wazzPartner.name} is promoted to Caller to defend the standing contract of ${highestBidTricks} ${highestBidSuit === "NONE" ? "SANS (No Color)" : highestBidSuit}!`,
          );

          // Demote the promoted player's isWazz status because they are now the primary caller
          updatedPlayersLocal = updatedPlayersLocal.map((p, idx) => {
            if (idx === wazzPartnerIdx) {
              return { ...p, isWazz: false };
            }
            return p;
          });
          setPlayers(updatedPlayersLocal);
        } else {
          nextBidderId = null;
          nextBidTricks = 0;
          nextBidSuit = "NONE";
          setHighestBidderId(null);
          setHighestBidTricks(0);
          setHighestBidSuit("NONE");
          setTrumpSuit("NONE");
        }
      }
      setAuctionBids((prev) => ({
        ...prev,
        [playerId]: { bid: -1, suit: "NONE" },
      }));
    }

    const activeBidders = updatedPlayersLocal.filter((p) => p.isBiddingActive);

    // Helper to print remaining active contenders
    const activeNames = activeBidders
      .map((p) => (p.id === "p1" ? "You" : p.name.split(" ")[0]))
      .join(", ");
    pushLog(`📢 Referee Check — Active Contenders left: [ ${activeNames} ]`);

    // If everyone passed and nobody bid:
    if (activeBidders.length === 0 && !nextBidderId) {
      const seq = [1, 2, 4, 6, 8];
      const nextMult =
        seq[Math.min(seq.indexOf(multiplier) + 1, seq.length - 1)];
      setMultiplier(nextMult);
      pushLog(
        `⚠️ All players passed! Round ${round} skipped. Next round starts with score multiplier (x${nextMult})`,
      );
      setRound((r) => r + 1);
      setPhase("DEALING");
      return;
    }

    // Rule: If everyone else passed, turn must return to the initial caller to confirm or raise.
    if (activeBidders.length === 1 && nextBidderId) {
      const highestBidderIdx = updatedPlayersLocal.findIndex(
        (p) => p.id === nextBidderId,
      );

      if (round >= 14) {
        // Fast round mini-auction ends directly, no confirmation phase.
        pushLog(
          `🏁 Fast Round Mini-Auction Complete! Caller is ${nextBidderId === "p1" ? "You" : updatedPlayersLocal[highestBidderIdx].name} with ${nextBidTricks} on ${nextBidSuit === "NONE" ? "SANS (No Color)" : nextBidSuit}.`,
        );
        setHighestBidderId(nextBidderId);
        setHighestBidTricks(nextBidTricks);
        setHighestBidSuit(nextBidSuit);
        setTrumpSuit(nextBidSuit);

        const mandatory = ["NONE", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"][
          (round - 14) % 5
        ] as Suit | "NONE";
        if (nextBidTricks >= 8 && nextBidSuit !== mandatory) {
          setMaxRounds((m) => m + 1);
          pushLog(
            `🔄 Super Call overrode the forced suit — the game is extended by one round.`,
          );
        }

        const dealerIdx = (((1 - round) % 4) + 4) % 4;
        const orderIds: string[] = [];
        let tempIdx = dealerIdx;
        for (let i = 0; i < 4; i++) {
          orderIds.push(updatedPlayersLocal[tempIdx].id);
          tempIdx = nextTurn(tempIdx);
        }

        const superCallerIdxInOrder = orderIds.indexOf(nextBidderId);
        const playersNeedingReEstimate = orderIds.slice(
          0,
          superCallerIdxInOrder,
        );

        const finalPlayers = updatedPlayersLocal.map((p) => {
          if (p.id === nextBidderId) {
            return { ...p, bid: nextBidTricks, isWazz: false };
          }
          if (nextBidTricks >= 8 && playersNeedingReEstimate.includes(p.id)) {
            return { ...p, bid: -1, isWazz: false };
          }
          // The rest keep their original estimation number
          return { ...p, isWazz: false };
        });

        setPlayers(finalPlayers);
        setPhase("FOLLOWING_BIDS");

        const callerIdx = finalPlayers.findIndex((p) => p.id === nextBidderId);
        let nextIdx = nextTurn(callerIdx);
        let loops = 0;
        let found = false;
        while (loops < 4) {
          if (finalPlayers[nextIdx].bid === -1) {
            found = true;
            break;
          }
          nextIdx = nextTurn(nextIdx);
          loops++;
        }
        if (found) {
          setBiddingTurnIdx(nextIdx);
        } else {
          setPhase("PLAYING");
          setActivePlayerIdx(callerIdx);
          setLastBidderState(orderIds[3]);
          const leaderName =
            callerIdx === 0 ? "You" : finalPlayers[callerIdx].name;
          pushLog(
            `🏁 Estimations completed! Caller is ${leaderName} and leads the first trick.`,
          );
        }
        return;
      }

      if (biddingTurnIdx === highestBidderIdx) {
        // Turn is already on the caller, so this is their final decision (confirmed or raised)
        pushLog(
          `🏁 Bidding completed! Caller ${nextBidderId === "p1" ? "You" : updatedPlayersLocal[highestBidderIdx].name} confirmed contract of ${nextBidTricks} on ${nextBidSuit === "NONE" ? "SANS (No Color)" : nextBidSuit}.`,
        );
        finalizeAuction(
          nextBidderId,
          nextBidTricks,
          nextBidSuit,
          updatedPlayersLocal,
        );
        return;
      } else {
        // Return turn to the caller to confirm or raise!
        setBiddingTurnIdx(highestBidderIdx);
        pushLog(
          `🔄 Tournament referee returns the turn to Caller ${nextBidderId === "p1" ? "You" : updatedPlayersLocal[highestBidderIdx].name} to confirm or raise their contract.`,
        );
        return;
      }
    }

    // Advance to the next active player counter-clockwise
    let nextIdx = nextTurn(biddingTurnIdx);
    let loops = 0;
    while (!updatedPlayersLocal[nextIdx].isBiddingActive && loops < 4) {
      nextIdx = nextTurn(nextIdx);
      loops++;
    }

    setBiddingTurnIdx(nextIdx);
  };

  const submitEstimate = (
    playerId: string,
    val: number,
    suit?: Suit | "NONE",
  ) => {
    const playerIdx = players.findIndex((p) => p.id === playerId);
    const otherBids = players
      .filter((p) => p.id !== playerId && p.bid !== -1)
      .map((p) => p.bid);
    const validation = validateBidding(
      val,
      playerIdx,
      otherBids,
      "FOLLOWING_BIDS",
      highestBidTricks,
      phase,
      biddingTurnIdx,
      players[playerIdx]?.isDashCall,
      false,
      round >= 14,
    );
    if (!validation.valid) {
      pushLog(
        `🚨 Rejected illegal bid! ${players.find((p) => p.id === playerId)?.name || playerId} tried to estimate ${val} tricks, but: ${validation.error}`,
      );
      return;
    }

    const resolvedSuit =
      suit !== undefined ? suit : auctionBids[playerId]?.suit || "NONE";

    setPlayers((prev) => {
      const updated = prev.map((p) =>
        p.id === playerId
          ? {
              ...p,
              bid: val,
              // Any player (including jump-ins) becomes/remains Wazz if:
              // 1. They are not the caller (highestBidderId)
              // 2. Their bid is exactly equal to the Caller's bid (highestBidTricks)
              // 3. Their estimated/auction suit matches the Caller's declared trump suit (highestBidSuit)
              isWazz:
                p.id !== highestBidderId &&
                val === highestBidTricks &&
                resolvedSuit === highestBidSuit,
            }
          : p,
      );
      setTimeout(() => {
        const allEstimated = updated.every((p) => p.bid !== -1);
        if (allEstimated) {
          setPhase("PLAYING");
          setLastBidderState(playerId);
          const winnerIdx = updated.findIndex((p) => p.id === highestBidderId);
          const firstLead = winnerIdx !== -1 ? winnerIdx : 0;
          setActivePlayerIdx(firstLead);
          const leaderName = firstLead === 0 ? "You" : updated[firstLead].name;
          pushLog(
            `🏁 Bidding completed! Caller is ${leaderName} and leads the first trick.`,
          );
        } else {
          const currIdx = updated.findIndex((p) => p.id === playerId);
          let nextIdx = nextTurn(currIdx);
          let loops = 0;
          while (updated[nextIdx].bid !== -1 && loops < 4) {
            nextIdx = nextTurn(nextIdx);
            loops++;
          }
          setBiddingTurnIdx(nextIdx);
        }
      }, 0);
      return updated;
    });
  };

  const runCompliantSimulationAudit = () => {
    const deck = shuffleDeck(createNewDeck());
    const initialHands = [
      sortHand(deck.slice(0, 13)),
      sortHand(deck.slice(13, 26)),
      sortHand(deck.slice(26, 39)),
      sortHand(deck.slice(39, 52)),
    ];

    const auditTrump: Suit | "NONE" = "HEARTS";

    let simPlayers: PlayerModel[] = [
      {
        id: "p1",
        name: "You (باشا)",
        hand: initialHands[0],
        bid: 4,
        isDashCall: false,
        isBiddingActive: false,
        tricksWon: 0,
        score: 0,
        isUser: true,
      },
      {
        id: "p2",
        name: "Fatima (جدع)",
        hand: [...initialHands[1]],
        bid: 3,
        isDashCall: false,
        isBiddingActive: false,
        tricksWon: 0,
        score: 0,
        isUser: false,
      },
      {
        id: "p3",
        name: "Amir (حريف)",
        hand: [...initialHands[2]],
        bid: 2,
        isDashCall: false,
        isBiddingActive: false,
        tricksWon: 0,
        score: 0,
        isUser: false,
      },
      {
        id: "p4",
        name: "Omar (الأسطورة)",
        hand: [...initialHands[3]],
        bid: 3,
        isDashCall: false,
        isBiddingActive: false,
        tricksWon: 0,
        score: 0,
        isUser: false,
      },
    ];

    const auditLogs: string[] = [];
    auditLogs.push(`🔬 4-TRICK GAME TICK CODE-VERIFICATION AUDIT`);
    auditLogs.push(`👑 Declared Trump Suit: HEARTS`);

    let currentLeadIdx = 0;

    for (let trickNum = 1; trickNum <= 4; trickNum++) {
      auditLogs.push(`🔮 TRICK ${trickNum} STARTED!`);
      const trickPlays: PlayedCard[] = [];
      let activeLeadSuit: Suit | null = null;

      for (let step = 0; step < 4; step++) {
        const playerIdx = (currentLeadIdx - step + 4) % 4;
        const bot = {
          ...simPlayers[playerIdx],
          hand: [...simPlayers[playerIdx].hand],
        };

        const decision = selectAIGameplayCardV2(
          bot,
          simPlayers,
          trickPlays,
          null,
          auditTrump,
          normalizeTier(bot.difficulty),
          [],
          highestBidderId || undefined
        );
        const playedCard = decision.chosenCard;

        simPlayers[playerIdx].hand = simPlayers[playerIdx].hand.filter(
          (c) => c.id !== playedCard.id,
        );

        let validationStatus = "";
        if (!activeLeadSuit) {
          activeLeadSuit = playedCard.suit;
          validationStatus = `👑 LEAD CARD (sets suit to ${playedCard.suit})`;
        } else {
          const hasLedSuitInHand = bot.hand.some(
            (c) => c.suit === activeLeadSuit,
          );
          if (playedCard.suit === activeLeadSuit) {
            validationStatus = `✅ Legally Followed Lead Suit (${activeLeadSuit})`;
          } else if (!hasLedSuitInHand) {
            if (playedCard.suit === auditTrump) {
              validationStatus = `🔥 Cut with Trump (${auditTrump}) (Broke suit legally)`;
            } else {
              validationStatus = `⚠️ Sluffed/Discarded (${playedCard.suit}) (Broke suit legally)`;
            }
          } else {
            validationStatus = `❌ ERROR: ILLEGAL PLAY - Broke Lead Suit!`;
          }
        }

        trickPlays.push({
          playerId: bot.id,
          playerName: bot.name,
          card: playedCard,
        });
        auditLogs.push(
          `👉 [Turn ${step + 1}] ${bot.name} plays ${getRankUI(playedCard.rank)}${getSuitUI(playedCard.suit).char} | ${validationStatus}`,
        );
      }

      const winner = determineWinner(trickPlays, activeLeadSuit!, auditTrump);
      const winnerIdx = simPlayers.findIndex((p) => p.id === winner.playerId);
      simPlayers[winnerIdx].tricksWon += 1;

      auditLogs.push(`🏁 Lead Suit: ${activeLeadSuit}`);
      auditLogs.push(
        `🏆 Trick ${trickNum} Winner: ${winner.playerName} with ${getRankUI(winner.card.rank)}${getSuitUI(winner.card.suit).char}!`,
      );
      auditLogs.push(`────────────────────────────────────────`);

      currentLeadIdx = winnerIdx;
    }

    auditLogs.push(
      `🎉 Audit Complete! Tricks won status: You ${simPlayers[0].tricksWon}, Fatima ${simPlayers[1].tricksWon}, Amir ${simPlayers[2].tricksWon}, Omar ${simPlayers[3].tricksWon}`,
    );

    setLog((prev) => [
      ...prev,
      { message: "--- START OF CODE AUDIT ---" },
      ...auditLogs.map((m) => ({ message: m })),
      { message: "--- END OF CODE AUDIT ---" },
    ]);

    setPlayers(simPlayers);
    setTrumpSuit(auditTrump);
    setPhase("PLAYING");
    setCurrentTrick([]);
    setBiddingTurnIdx(0);
    setActivePlayerIdx(currentLeadIdx);
    setHighestBidderId("p1");
    setHighestBidTricks(4);
    setHighestBidSuit("HEARTS");

    setAuditModalLogs(auditLogs);
    setShowAuditModal(true);
  };

  const playCard = (playerId: string, card: CardModel) => {
    if (
      !card ||
      phase !== "PLAYING" ||
      players[activePlayerIdx]?.id !== playerId
    )
      return;
    const hand = players.find((p) => p.id === playerId)?.hand ?? [];
    if (!hand.some((c) => c.id === card.id)) return;
    if (!isPlayLegal(card, hand, currentTrick)) return;

    const nextPlayersState = players.map((p) =>
      p.id === playerId
        ? { ...p, hand: p.hand.filter((c) => c.id !== card.id) }
        : p,
    );
    setPlayers(nextPlayersState);

    const newTrick = [
      ...currentTrick,
      { playerId, playerName: playerId, card },
    ];
    setCurrentTrick(newTrick);
    const activeLed =
      currentTrick.length > 0 ? currentTrick[0].card.suit : card.suit;
    setLedSuit(activeLed);

    if (newTrick.length === 4) {
      setTimeout(
        () => resolveTrick(newTrick, nextPlayersState, activeLed),
        1000,
      );
    } else {
      setActivePlayerIdx(nextTurn(activePlayerIdx));
    }
  };

  const resolveTrick = (
    trick: PlayedCard[],
    latestPlayers: PlayerModel[],
    activeLedSuit: Suit,
  ) => {
    const winner = determineWinner(trick, activeLedSuit, effectiveTrumpSuit);
    const winnerIdx = latestPlayers.findIndex((p) => p.id === winner.playerId);
    const roundFinished = latestPlayers.every((p) => p.hand.length === 0);

    setPlayers((prev) =>
      prev.map((p) =>
        p.id === winner.playerId ? { ...p, tricksWon: p.tricksWon + 1 } : p,
      ),
    );
    setLastCompletedTrick(trick);
    setSeenCards((prev) => [...prev, ...trick.map((p) => p.card)]);

    setCurrentTrick([]);
    setLedSuit(null);

    const winnerName =
      winner.playerId === "p1"
        ? "You"
        : latestPlayers.find((p) => p.id === winner.playerId)?.name ||
          "Computer";
    pushLog(
      `✔ Trick won by ${winnerName} with ${getRankUI(winner.card.rank)}${getSuitUI(winner.card.suit).char}`,
    );

    if (roundFinished) {
      setPhase("ROUND_OVER");
    } else {
      setActivePlayerIdx(winnerIdx);
    }
  };

  // Active Turn Timer Countdown Loop
  useEffect(() => {
    if (phase !== "PLAYING") return;
    setTurnTimer(15);
    const interval = setInterval(() => {
      setTurnTimer((t) => (t > 1 ? t - 1 : 15));
    }, 1000);
    return () => clearInterval(interval);
  }, [activePlayerIdx, phase]);

  // AI Triggers & Automated Turn Skipping
  useEffect(() => {
    const activePlayer = players[biddingTurnIdx];
    if (!activePlayer) return;

    if (phase === "DASH_CALL_DECISION" && biddingTurnIdx !== 0) {
      const activeAI = activePlayer;
      const currentBiddingTurnIdx = biddingTurnIdx;
      const timer = setTimeout(() => {
        if (
          biddingTurnIdxRef.current !== currentBiddingTurnIdx ||
          phaseRef.current !== "DASH_CALL_DECISION"
        )
          return;

        const currentPlayers = playersRef.current;
        const currentActiveAI = currentPlayers[currentBiddingTurnIdx] || activeAI;

        const tier = normalizeTier(currentActiveAI.difficulty);
        const aiBidDecision = evaluateBotBidWithPersonality(
          currentActiveAI.hand,
          tier,
          currentActiveAI.personality ?? "BALANCED",
          { playerId: currentActiveAI.id },
        );
        const dashCount = currentPlayers.filter((p) => p.isDashCall).length;
        const finalIsDash = aiBidDecision.isDashCall && dashCount < 2;

        pushLog(
          `🧠 [DASH BRAIN] ${currentActiveAI.name} Hand Check: cards count per suit has max rank value ${Math.max(...currentActiveAI.hand.map((c) => c.value), 0)}. Dash Call: ${finalIsDash ? "YES" : "NO"}`,
        );
        submitDashCall(currentActiveAI.id, finalIsDash);
      }, 1000);
      return () => clearTimeout(timer);
    }

    if (phase === "TRUMP_DECLARATION" && biddingTurnIdx !== 0) {
      const activeAI = activePlayer;
      const currentBiddingTurnIdx = biddingTurnIdx;
      const timer = setTimeout(() => {
        if (
          biddingTurnIdxRef.current !== currentBiddingTurnIdx ||
          phaseRef.current !== "TRUMP_DECLARATION"
        )
          return;

        const currentPlayers = playersRef.current;
        const currentActiveAI = currentPlayers[currentBiddingTurnIdx] || activeAI;

        const tier = normalizeTier(currentActiveAI.difficulty);
        const aiBidDecision = evaluateBotBidWithPersonality(
          currentActiveAI.hand,
          tier,
          currentActiveAI.personality ?? "BALANCED",
          { playerId: currentActiveAI.id, chosenTrump: "POTENTIAL" },
        );
        const aiTrump = aiBidDecision.potentialTrump;
        const maxCapability = aiBidDecision.bid;

        if (
          maxCapability > highestBidTricks &&
          isBidStronger(
            maxCapability,
            aiTrump,
            highestBidTricks,
            highestBidSuit,
          )
        ) {
          pushLog(
            `🧠 [TRUMP DECLARATION BRAIN] Caller ${currentActiveAI.name} chooses to UPGRADE their contract from ${highestBidTricks} ${highestBidSuit === "NONE" ? "SANS" : highestBidSuit} to ${maxCapability} ${(aiTrump as Suit | "NONE") === "NONE" ? "SANS" : aiTrump}!`,
          );
          submitTrumpDeclaration(maxCapability, aiTrump);
        } else {
          pushLog(
            `🧠 [TRUMP DECLARATION BRAIN] Caller ${currentActiveAI.name} decides to CONFIRM their winning auction bid.`,
          );
          submitTrumpDeclaration(highestBidTricks, highestBidSuit);
        }
      }, 1500);
      return () => clearTimeout(timer);
    }

    if (phase === "CALL_PHASE") {
      if (activePlayer.isDashCall) {
        // Dash callers cannot bid on trump, so they automatically pass in CALL_PHASE
        submitCall(activePlayer.id, -1, "NONE");
        return;
      }
      if (biddingTurnIdx !== 0) {
        const activeAI = activePlayer;
        const currentBiddingTurnIdx = biddingTurnIdx;
        const timer = setTimeout(() => {
          if (
            biddingTurnIdxRef.current !== currentBiddingTurnIdx ||
            phaseRef.current !== "CALL_PHASE"
          )
            return;
          // Check if there are any active Wazzes
          const hasWazzChallengers = players.some(
            (p) => p.id !== activeAI.id && p.isWazz,
          );

          if (activeAI.id === highestBidderId) {
            if (hasWazzChallengers) {
              // Caller needs to decide: raise/change suit to shake off, or accept
              // Find second-best suit
              const suits: Suit[] = ["SPADES", "HEARTS", "CLUBS", "DIAMONDS"];
              const alternativeSuits = suits.filter(
                (s) => s !== highestBidSuit,
              );
              let bestAltSuit: Suit = alternativeSuits[0];
              let maxAltCount = 0;
              alternativeSuits.forEach((s) => {
                const cnt = activeAI.hand.filter((c) => c.suit === s).length;
                if (cnt > maxAltCount) {
                  maxAltCount = cnt;
                  bestAltSuit = s;
                }
              });

              // Evaluate alternative bid
              const tier = normalizeTier(activeAI.difficulty);
              const altDecision = evaluateBotBidWithPersonality(
                activeAI.hand,
                tier,
                activeAI.personality ?? "BALANCED",
                { playerId: activeAI.id, chosenTrump: bestAltSuit },
              );
              const altBid = altDecision.bid;

              const canShakeOff = altBid > highestBidTricks; // Can bid higher on different suit
              if (canShakeOff) {
                pushLog(
                  `🧠 [SHAKE OFF] ${activeAI.name} raises bid to ${altBid} on ${bestAltSuit} to shake off the Wazz challengers!`,
                );
                submitCall(activeAI.id, altBid, bestAltSuit);
              } else {
                pushLog(
                  `🧠 [ACCEPT CHALLENGE] ${activeAI.name} accepts the challenge and terminates the auction.`,
                );
                finalizeAuction(
                  activeAI.id,
                  highestBidTricks,
                  highestBidSuit,
                  players,
                );
              }
            } else {
              // Safety: End bidding
              finalizeAuction(
                activeAI.id,
                highestBidTricks,
                highestBidSuit,
                players,
              );
            }
            return;
          }

          // Otherwise, active AI is NOT the current Caller
          if (round >= 14 && highestBidderId === null) {
            // Check for potential Super Call (can we bid 8+ on another suit?)
            const tier = normalizeTier(activeAI.difficulty);
            const potentialBids = evaluateBotBidWithPersonality(
              activeAI.hand,
              tier,
              activeAI.personality ?? "BALANCED",
              { playerId: activeAI.id, chosenTrump: "POTENTIAL" },
            );
            const potTrump = potentialBids.potentialTrump;
            const potBid = potentialBids.bid;

            const fixedSequence: (Suit | "NONE")[] = [
              "NONE",
              "SPADES",
              "HEARTS",
              "DIAMONDS",
              "CLUBS",
            ];
            const mandatoryTrump = fixedSequence[(round - 14) % 5];

            if (potBid >= 8) {
              pushLog(
                `🧠 [SUPER CALL BRAIN] ${activeAI.name} has a strong hand! Decides to launch a Super Call: ${potBid} on ${potTrump}!`,
              );
              submitCall(activeAI.id, potBid, potTrump);
              return;
            }

            // Otherwise, standard bid on the mandatory trump of the round
            const evaluation = evaluateBotBidWithPersonality(
              activeAI.hand,
              tier,
              activeAI.personality ?? "BALANCED",
              { playerId: activeAI.id, chosenTrump: mandatoryTrump },
            );
            // Default to minimal safe boundaries, but if evaluation bids < 8, just use it normally.
            // In Fast Rounds they can technically bid whatever, but any normal bid is on the mandatory trump.
            let bidVal = Math.max(0, Math.min(13, evaluation.bid));

            const otherBids = players
              .filter((p) => p.id !== activeAI.id && p.bid !== -1)
              .map((p) => p.bid);
            const validOthers = otherBids.filter((b) => b >= 0);
            if (validOthers.length === 3) {
              const sumOfOthers = validOthers.reduce((s, b) => s + b, 0);
              if (sumOfOthers + bidVal === 13) {
                // Adjust AI bid strictly to avoid 13
                bidVal = bidVal > 0 ? bidVal - 1 : bidVal + 1;
              }
            }

            pushLog(
              `🧠 [FAST ROUND] ${activeAI.name} bids ${bidVal} tricks on ${mandatoryTrump === "NONE" ? "SANS" : mandatoryTrump}.`,
            );
            submitCall(activeAI.id, bidVal, mandatoryTrump);
            return;
          }

          const tier = normalizeTier(activeAI.difficulty);
          const aiBidDecision = evaluateBotBidWithPersonality(
            activeAI.hand,
            tier,
            activeAI.personality ?? "BALANCED",
            { playerId: activeAI.id, chosenTrump: "POTENTIAL" },
          );
          const aiTrump = aiBidDecision.potentialTrump;
          const maxCapability = aiBidDecision.bid;

          // Check if they want to raise the bid
          if (
            maxCapability >= 4 &&
            isBidStronger(
              maxCapability,
              aiTrump,
              highestBidTricks,
              highestBidSuit,
            )
          ) {
            pushLog(
              `🧠 [BID BRAIN] ${activeAI.name} chooses to call ${maxCapability} on ${aiTrump}!`,
            );
            submitCall(activeAI.id, maxCapability, aiTrump);
            return;
          }

          // Check if they want to equalize / Wazz
          if (highestBidTricks >= 4) {
            const suitCount = activeAI.hand.filter(
              (c) => c.suit === highestBidSuit,
            ).length;
            const isFavorableWazz =
              (maxCapability === highestBidTricks &&
                aiTrump === highestBidSuit) ||
              (maxCapability >= highestBidTricks - 1 && suitCount >= 4);

            if (isFavorableWazz) {
              pushLog(
                `🧠 [BID BRAIN] ${activeAI.name} calls WAZZ (Equalize) to match the contract of ${highestBidTricks} ${highestBidSuit === "NONE" ? "SANS" : highestBidSuit}!`,
              );
              submitCall(activeAI.id, highestBidTricks, highestBidSuit, true);
              return;
            }
          }

          // Otherwise, PASS
          pushLog(`🧠 [BID BRAIN] ${activeAI.name} chooses to PASS.`);
          submitCall(activeAI.id, -1, "NONE");
        }, 1200);
        return () => clearTimeout(timer);
      }
    }

    if (phase === "FOLLOWING_BIDS") {
      if (activePlayer.isDashCall) {
        // Dash callers already have an implicit estimate bid of 0, so automatically submit 0
        submitEstimate(activePlayer.id, 0, "NONE");
        return;
      }
      if (activePlayer.id === highestBidderId) {
        // Caller has already established their bid during CALL_PHASE, so automatically submit it
        submitEstimate(activePlayer.id, highestBidTricks, highestBidSuit);
        return;
      }
      if (biddingTurnIdx !== 0) {
        const activeAI = activePlayer;
        const currentBiddingTurnIdx = biddingTurnIdx;
        const timer = setTimeout(() => {
          if (
            biddingTurnIdxRef.current !== currentBiddingTurnIdx ||
            phaseRef.current !== "FOLLOWING_BIDS"
          )
            return;

          const currentPlayers = playersRef.current;
          const currentActiveAI = currentPlayers[currentBiddingTurnIdx] || activeAI;

          const otherBids = currentPlayers
            .filter((p, i) => i !== currentBiddingTurnIdx && p.bid !== -1)
            .map((p) => p.bid);
          const tier = normalizeTier(currentActiveAI.difficulty);
          const aiBidDecision = evaluateBotBidWithPersonality(
            currentActiveAI.hand,
            tier,
            currentActiveAI.personality ?? "BALANCED",
            {
              otherBids: otherBids,
              callerBid: highestBidTricks,
              playerId: currentActiveAI.id,
              chosenTrump: effectiveTrumpSuit,
            },
          );
          let finalBid = aiBidDecision.bid;

          const isFastRoundNow = round >= 14;
          // Call Cap only applies in normal rounds — fast rounds (14-18) have no cap.
          const ceil =
            !isFastRoundNow && highestBidTricks > 0 ? highestBidTricks : 13;
          finalBid = Math.max(0, Math.min(ceil, finalBid));

          // Ensure estimation matches rule validator as a safety safeguard
          let attempts = 0;
          let adjustedByConstraint = false;
          while (
            attempts < 20 &&
            !validateBidding(
              finalBid,
              currentBiddingTurnIdx,
              otherBids,
              "FOLLOWING_BIDS",
              highestBidTricks,
              undefined,
              undefined,
              currentActiveAI.isDashCall,
              false,
              isFastRoundNow,
            ).valid
          ) {
            adjustedByConstraint = true;
            if (finalBid > 0) {
              finalBid--;
            } else if (finalBid < ceil) {
              finalBid++;
            } else {
              break;
            }
            attempts++;
          }

          let logReasoning = `🧠 [ESTIMATE BRAIN] ${currentActiveAI.name} evaluation (Trump: ${effectiveTrumpSuit}): ${aiBidDecision.reasoning}`;
          if (adjustedByConstraint) {
            logReasoning += ` (Adjusted to ${finalBid} tricks due to Sum-of-Bids not equal to 13 constraint)`;
          }
          pushLog(logReasoning);
          submitEstimate(
            currentActiveAI.id,
            finalBid,
            auctionBids[currentActiveAI.id]?.suit || "NONE",
          );
        }, 1250);
        return () => clearTimeout(timer);
      }
    }

    if (
      phase === "PLAYING" &&
      activePlayerIdx !== 0 &&
      currentTrick.length < 4
    ) {
      const p = players[activePlayerIdx];
      if (p && p.hand.length > 0) {
        const currentActivePlayerIdx = activePlayerIdx;
        const timer = setTimeout(() => {
          if (
            activePlayerIdxRef.current !== currentActivePlayerIdx ||
            phaseRef.current !== "PLAYING"
          )
            return;
          const decision = selectAIGameplayCardV2(
            p,
            players,
            currentTrick,
            null,
            effectiveTrumpSuit,
            normalizeTier(p.difficulty),
            seenCardsRef.current,
            highestBidderId || undefined
          );

          const legalCards = p.hand.filter((c) => {
            if (!derivedLedSuit) return true;
            const hasLedSuit = p.hand.some((hc) => hc.suit === derivedLedSuit);
            if (hasLedSuit) return c.suit === derivedLedSuit;
            return true;
          });

          const debugRecord = {
            id:
              String(Date.now()) +
              "-" +
              p.id +
              "-" +
              Math.random().toString(36).substr(2, 4),
            botId: p.id,
            botName: p.name,
            handBefore: [...p.hand],
            legalCards,
            trickBefore: [...currentTrick],
            ledSuit: derivedLedSuit,
            trumpSuit: effectiveTrumpSuit,
            chosenCard: decision.chosenCard,
            reasoning: decision.reasoning,
            timestamp: new Date().toLocaleTimeString(),
            tricksWon: p.tricksWon,
            bid: p.bid,
            isDashCall: p.isDashCall,
          };

          setBotDecisionsHistory((prev) => [debugRecord, ...prev].slice(0, 50));
          setSelectedBotDecision(debugRecord);

          pushLog(
            `🤖 ${p.name.split(" ")[0]} plays: ${getRankUI(decision.chosenCard.rank)}${getSuitUI(decision.chosenCard.suit).char} | ${decision.reasoning}`,
          );
          playCard(p.id, decision.chosenCard);
        }, 1200);
        return () => clearTimeout(timer);
      }
    }
  }, [
    biddingTurnIdx,
    activePlayerIdx,
    phase,
    derivedLedSuit,
    highestBidTricks,
    highestBidSuit,
    players,
    currentTrick,
  ]);

  // --- SCORE CALCULATION & LIFECYCLE ---
  useEffect(() => {
    if (phase === "ROUND_OVER") {
      const totalEstimatedTricks = players.reduce(
        (sum, p) => sum + (p.bid === -1 ? 0 : p.bid),
        0,
      );

      let lastBidderId = lastBidderState;
      const callerIdx = players.findIndex((p) => p.id === highestBidderId);
      if (callerIdx !== -1) {
        // Find the correct Risk player: start clockwise of the caller (bidding last)
        // and traverse clockwise (backward in bidding order) until we find a non-Dash Call player
        let checkIdx = (callerIdx + 1) % 4;
        let foundRisk = false;
        for (let i = 0; i < 4; i++) {
          const currentP = players[checkIdx];
          if (!currentP.isDashCall) {
            lastBidderId = currentP.id;
            foundRisk = true;
            break;
          }
          checkIdx = (checkIdx + 1) % 4;
        }
        if (!foundRisk) {
          lastBidderId = players[checkIdx].id;
        }
      }

      // Check success
      const playerSuccessStatuses = players.map((p) => {
        const won = p.tricksWon === p.bid;
        return { id: p.id, won };
      });

      const successCount = playerSuccessStatuses.filter((ps) => ps.won).length;
      const failedCount = 4 - successCount;

      const isSingleWinner = successCount === 1;
      const isSingleLoser = failedCount === 1;
      const allFailed = successCount === 0;

      const diffFrom13 = Math.abs(totalEstimatedTricks - 13);
      let riskValCalculated = 0;
      if (diffFrom13 === 2 || diffFrom13 === 3) {
        riskValCalculated = 10;
      } else if (diffFrom13 === 4 || diffFrom13 === 5) {
        riskValCalculated = 20;
      } else if (diffFrom13 >= 6) {
        riskValCalculated = 30;
      }

      const results = players.map((p) => {
        const isCaller = p.id === highestBidderId;
        const isWithPlayer = !!p.isWazz;
        const isSoleWinner = isSingleWinner && p.tricksWon === p.bid;
        const isSoleLoser = isSingleLoser && p.tricksWon !== p.bid;
        const isLast = p.id === lastBidderId;

        let role: PlayerRole = "NORMAL";
        if (p.isDashCall || p.bid === 0) {
          role = isCaller ? "DASH_CALL" : "REG_DASH";
        } else if (isCaller) {
          role = p.bid >= 8 ? "SUPER_CALL" : "CALLER";
        } else if (isWithPlayer && isLast && riskValCalculated > 0) {
          role = "WIZZ_RISK";
        } else if (isWithPlayer) {
          role = "WIZZ";
        } else if (isLast && riskValCalculated > 0) {
          role = "RISK";
        }

        const baseScore = scoringMode === "CLASSIC" 
          ? calculateClassicScore({
              role,
              bid: p.bid,
              won: p.tricksWon,
              totalBids: totalEstimatedTricks,
              isSoleWinner,
              isSoleLoser
            })
          : calculateEstemshanScore(
              p.bid,
              p.isDashCall,
              p.tricksWon,
              isCaller,
              isWithPlayer,
              totalEstimatedTricks,
              isSoleWinner,
              isSoleLoser,
              isLast
            );

        // Under Sa'ayda (Escalation Round), if all 4 players failed, they score zero this round
        const finalScore = allFailed ? 0 : baseScore * multiplier;

        return {
          id: p.id,
          name: p.name,
          bid: p.bid,
          isDashCall: p.isDashCall,
          tricksWon: p.tricksWon,
          isCaller,
          isWith: isWithPlayer,
          isSuccess: p.tricksWon === p.bid,
          isSoleWinner,
          isSoleLoser,
          isLast,
          riskApplied: isLast ? riskValCalculated : 0,
          baseScore: allFailed ? 0 : baseScore,
          multiplier,
          finalScore,
          newCumulativeScore: p.score + finalScore,
        };
      });

      setRoundResults(results);
      updateBotLearningBiases(results);
    }
  }, [
    phase,
    players,
    highestBidderId,
    highestBidTricks,
    multiplier,
    lastBidderState,
  ]);

  // Auto-advance ROUND_OVER to next round in 4 seconds
  useEffect(() => {
    if (phase === "ROUND_OVER" && roundResults) {
      const timer = setTimeout(() => {
        proceedToNextRound();
      }, 4000);
      return () => clearTimeout(timer);
    }
  }, [phase, roundResults]);

  const proceedToNextRound = () => {
    if (!roundResults) return;

    // Build round-by-round statistics profile
    const stats: RoundStat = {
      round,
      trumpSuit: effectiveTrumpSuit,
      multiplier,
      playerStats: roundResults.map((r) => ({
        id: r.id,
        name: r.name,
        bid: r.bid,
        isDashCall: r.isDashCall,
        tricksWon: r.tricksWon,
        finalScore: r.finalScore,
        newCumulativeScore: r.newCumulativeScore,
        isSuccess: r.isSuccess,
      })),
    };

    pushLog(
      `Round ${round} Stats — Trump: ${effectiveTrumpSuit}, Multiplier: x${multiplier}. Scores: ${roundResults.map((r) => `${r.name}: ${r.finalScore >= 0 ? "+" : ""}${r.finalScore} PTS`).join(", ")}`,
      stats,
    );

    // Apply cumulative score updates
    setPlayers((prev) =>
      prev.map((p) => {
        const match = roundResults.find((r) => r.id === p.id);
        return match ? { ...p, score: match.newCumulativeScore } : p;
      }),
    );

    // Reset multiplier to 1 after a finished normal round, OR use [1, 2, 4, 6, 8] sequence if everyone failed (Sa'ayda)
    const allFailed = roundResults.every((r) => !r.isSuccess);
    if (allFailed) {
      const seq = [1, 2, 4, 6, 8];
      const nextMult =
        seq[Math.min(seq.indexOf(multiplier) + 1, seq.length - 1)];
      setMultiplier(nextMult);
      pushLog(
        `⚡ [ESCALATION ROUND - SA'AYDA] All 4 players failed their bids this round! Next round starts with a points multiplier: x${nextMult}.`,
      );
    } else {
      setMultiplier(1);
    }

    // Clear temp round results
    setRoundResults(null);

    const reachedEnd = round >= maxRounds;
    if (allFailed && reachedEnd) {
      setMaxRounds(round + 1); // force one more round
      setRound((r) => r + 1);
      setPhase("DEALING");
    } else if (reachedEnd) {
      setPhase("GAME_OVER");
    } else {
      setRound((r) => r + 1);
      setPhase("DEALING");
    }
  };

  const restartGame = () => {
    setRound(1);
    setMaxRounds(18);
    setMultiplier(1);
    setPlayers((prev) =>
      prev.map((p) => ({
        ...p,
        score: 0,
        tricksWon: 0,
        bid: -1,
        isDashCall: false,
        hand: [],
        isBiddingActive: true,
        isWazz: false,
      })),
    );
    setTrumpSuit("NONE");
    setHighestBidderId(null);
    setHighestBidTricks(0);
    setHighestBidSuit("NONE");
    setSelectedBidTricks(null);
    setSelectedBidSuit(null);
    setActivePlayerIdx(0);
    setBiddingTurnIdx(0);
    setBidsPlacedCount(0);
    setDashDecisionMap({
      p1: "PENDING",
      p2: "PENDING",
      p3: "PENDING",
      p4: "PENDING",
    });
    setRoundResults(null);
    setLog([{ message: "Game restarted! Dealer is ready." }]);
    setPhase("DEALING");
  };

  // --- COMPONENT: PREMIUM CARD STYLING ---
  const Card = ({
    card,
    onClick,
    disabled,
    isLead,
    faceDown,
    size = "normal",
  }: {
    card?: CardModel;
    onClick?: () => void;
    disabled?: boolean;
    isLead?: boolean;
    faceDown?: boolean;
    size?: "normal" | "small";
  }) => {
    if (faceDown) {
      return (
        <div className="relative w-12 h-18 rounded-md bg-[#101A30] border-2 border-[#D4AF37] shadow-lg overflow-hidden flex items-center justify-center p-0.5 select-none transform transition-all duration-300 hover:scale-105">
          <div className="w-full h-full border border-[#F3D26A]/45 rounded-[3px] bg-gradient-to-b from-[#182440] to-[#0A1020] flex items-center justify-center relative">
            {/* Geometric star design in center of card back */}
            <span className="text-sm font-black text-[#F3D26A] select-none">
              ✦
            </span>
          </div>
        </div>
      );
    }

    if (!card) return null;
    const ui = getSuitUI(card.suit);
    const rank = getRankUI(card.rank);

    const isSmall = size === "small";

    // Enabled/Playable gets BrightGold border and high-fidelity lift. Disabled gets quiet parchment borders.
    const borderStyle = !disabled
      ? "border-[#F3D26A] border-2 shadow-[0_0_12px_rgba(243,210,106,0.45)]"
      : "border-[#DFC5A9]/35 border";

    return (
      <motion.div
        layoutId={card.id}
        whileHover={!disabled && !isSmall ? { y: -14, scale: 1.05 } : {}}
        onClick={!disabled ? onClick : undefined}
        className={`
          relative bg-gradient-to-b from-[#FAF5E8] to-[#F1E8D2] flex flex-col select-none overflow-hidden transition-all duration-300 ${borderStyle}
          ${isSmall ? "w-16 h-24 p-1 rounded-lg shadow-[0_3px_8px_rgba(27,15,11,0.18)]" : "w-22 h-34 p-2 rounded-xl shadow-[0_6px_14px_rgba(27,15,11,0.22)]"}
          ${disabled && !isLead ? "brightness-[0.93] contrast-[0.95]" : "cursor-pointer"}
          ${isLead ? "ring-2 ring-[#D4AF37] shadow-[#D4AF37]/30" : ""}
        `}
      >
        {/* Top-Left Corner Index */}
        <div
          className={`absolute ${isSmall ? "top-1 left-1" : "top-1.5 left-1.5"} flex flex-col items-center leading-[0.8] ${ui.color}`}
        >
          <span
            className={`${isSmall ? "text-xs font-black" : "text-lg font-black"} tracking-tighter`}
          >
            {rank}
          </span>
          <span className={isSmall ? "text-[8px]" : "text-xs"}>{ui.char}</span>
        </div>

        {/* Center Suit Symbol */}
        <div
          className={`absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 opacity-[0.95] ${ui.color} ${isSmall ? "text-2xl" : "text-5xl"}`}
        >
          {ui.char}
        </div>

        {/* Bottom-Right Corner Index (Reversed) */}
        <div
          className={`absolute ${isSmall ? "bottom-1 right-1" : "bottom-1.5 right-1.5"} flex flex-col items-center leading-[0.8] rotate-180 ${ui.color}`}
        >
          <span
            className={`${isSmall ? "text-xs font-black" : "text-lg font-black"} tracking-tighter`}
          >
            {rank}
          </span>
          <span className={isSmall ? "text-[8px]" : "text-xs"}>{ui.char}</span>
        </div>

        {/* Subtle Card Texture/Gloss */}
        <div className="absolute inset-0 bg-gradient-to-tr from-[#DFC5A9]/10 via-transparent to-white/10 pointer-events-none" />
      </motion.div>
    );
  };

  return (
    <div className="min-h-screen bg-gradient-to-b from-[#150D0A] via-[#0F0A06] to-[#050302] text-[#FAF5E8] p-4 font-sans selection:bg-[#D4AF37]/30 overflow-x-hidden">
      <header className="flex justify-between items-center mb-6 bg-[#2C1A0E]/92 p-4 rounded-2xl border border-[#BFA77E]/25 shadow-2xl max-w-7xl mx-auto backdrop-blur-md">
        <h1 className="text-xl font-black tracking-tighter flex items-center gap-2 italic text-[#F3D26A]">
          <Trophy className="text-[#D4AF37] fill-[#D4AF37]/10" /> ESTEMSHAN
          BOARD ARENA • المزاد الموقر
        </h1>

        {/* TOP CENTERED ROUND COUNTER (RECREATED CALENDAR WITH POP-UP DROPDOWN SCOREBOARD) */}
        <div className="relative">
          <button
            onClick={() => setShowScoresDropdown(!showScoresDropdown)}
            className="flex flex-col items-center bg-[#2C1A0E] rounded-2xl border-[3.5px] border-[#D4AF37] w-22 h-24 overflow-hidden shadow-[0_8px_20px_rgba(0,0,0,0.65)] hover:scale-105 active:scale-95 transition-all cursor-pointer select-none focus:outline-none z-10"
            title="Click to view round-by-round scoreboard history!"
          >
            {/* Antique Gold top bar with 3 ivory binder holes */}
            <div className="bg-[#D4AF37] w-full py-1.5 flex justify-center items-center gap-2 border-b-[3px] border-[#2C1A0E]">
              <span className="w-1.5 h-1.5 rounded-full bg-[#FAF5E8] shadow-inner" />
              <span className="w-1.5 h-1.5 rounded-full bg-[#FAF5E8] shadow-inner" />
              <span className="w-1.5 h-1.5 rounded-full bg-[#FAF5E8] shadow-inner" />
            </div>

            {/* Dark wood and gold card background */}
            <div className="bg-[#2C1A0E] flex-1 w-full flex flex-col items-center justify-between pb-2">
              <span className="text-[#E8D5B0] font-black tracking-widest text-[10px] uppercase mt-1 leading-none select-none">
                ROUND
              </span>
              <span className="text-[#F3D26A] font-black text-2xl leading-none font-mono tracking-tight select-none pb-0.5">
                {round}
              </span>
            </div>
          </button>

          {/* DROPDOWN POP-UP SCOREBOARD */}
          <AnimatePresence>
            {showScoresDropdown && (
              <>
                {/* Invisible backdrop to close the dropdown on outer click (no blur!) */}
                <div
                  className="fixed inset-0 z-40 cursor-default"
                  onClick={() => setShowScoresDropdown(false)}
                />

                <motion.div
                  initial={{ opacity: 0, y: 15, scale: 0.95 }}
                  animate={{ opacity: 1, y: 0, scale: 1 }}
                  exit={{ opacity: 0, y: 15, scale: 0.95 }}
                  transition={{ duration: 0.2 }}
                  className="absolute top-full mt-3 left-1/2 -translate-x-1/2 z-50 bg-[#0c120e]/95 border-3 border-amber-500/80 p-4 rounded-3xl w-[350px] md:w-[480px] shadow-[0_20px_50px_rgba(0,0,0,0.95)] flex flex-col gap-3 font-sans"
                >
                  {/* Dropdown Header */}
                  <div className="flex justify-between items-center border-b border-white/10 pb-1.5">
                    <div className="flex flex-col text-left">
                      <h4 className="text-[11px] font-black tracking-widest text-amber-500 uppercase">
                        SCOREBOARD HISTORY
                      </h4>
                      <p className="text-[9px] text-zinc-400 uppercase">
                        Live point tracking round by round
                      </p>
                    </div>
                    <button
                      onClick={() => setShowScoresDropdown(false)}
                      className="w-6 h-6 flex items-center justify-center rounded-lg bg-white/5 hover:bg-rose-500/20 text-zinc-400 hover:text-rose-400 transition-colors text-xs font-bold font-sans cursor-pointer active:scale-90"
                    >
                      ✕
                    </button>
                  </div>

                  {/* Standing Cumulative Leaderboard (Compact row) */}
                  <div className="flex flex-col gap-1.5 text-left">
                    <span className="text-[9px] font-black tracking-wider text-zinc-400 uppercase font-sans">
                      Current Leaderboard standings
                    </span>
                    <div className="grid grid-cols-4 gap-1.5">
                      {[...players]
                        .sort((a, b) => b.score - a.score)
                        .map((p, idx) => {
                          const playerScores = players.map((pl) => pl.score);
                          const maxScore = Math.max(...playerScores);
                          const isKing = p.score === maxScore && maxScore > 0;
                          const isKooz =
                            p.score === Math.min(...playerScores) &&
                            p.score < maxScore;
                          return (
                            <div
                              key={p.id}
                              className={`flex flex-col items-center justify-between p-2 rounded-xl border text-center transition-all ${
                                p.isUser
                                  ? "bg-sky-500/10 border-sky-500/30"
                                  : isKing
                                    ? "bg-amber-500/10 border-amber-500/30"
                                    : "bg-zinc-950/60 border-white/5"
                              }`}
                            >
                              <span className="text-[8px] font-black text-zinc-500 block">
                                #{idx + 1}
                              </span>
                              <span className="text-[10px] font-bold text-slate-100 truncate w-full flex items-center justify-center gap-0.5 mt-0.5">
                                {p.name.split(" ")[0]} {isKing && "👑"}
                                {isKooz && "🧲"}
                              </span>
                              <span className="text-xs font-black text-emerald-400 font-mono mt-1">
                                {p.score} pt
                              </span>
                            </div>
                          );
                        })}
                    </div>
                  </div>

                  {/* Round-by-Round Log Details */}
                  <div className="flex flex-col gap-1.5 text-left">
                    <span className="text-[9px] font-black tracking-wider text-zinc-400 uppercase font-sans">
                      Rounds stats timeline
                    </span>
                    <div className="overflow-x-auto w-full border border-white/5 rounded-2xl bg-black/40 max-h-[190px] overflow-y-auto">
                      <table className="w-full text-left border-collapse text-[10px]">
                        <thead className="bg-zinc-900 border-b border-white/10 text-[8px] text-zinc-400 uppercase tracking-wider sticky top-0 z-10">
                          <tr>
                            <th className="py-1.5 px-2 text-center">Rnd</th>
                            <th className="py-1.5 px-2">Trump</th>
                            <th className="py-1.5 px-2 text-sky-400">You</th>
                            <th className="py-1.5 px-2 text-zinc-300">
                              Fatima
                            </th>
                            <th className="py-1.5 px-2 text-zinc-300">Amir</th>
                            <th className="py-1.5 px-2 text-zinc-300">Omar</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-white/5 font-mono text-zinc-350">
                          {(() => {
                            const dropdownStats = log
                              .map((entry) => entry.stats)
                              .filter((s): s is RoundStat => !!s);
                            if (dropdownStats.length === 0) {
                              return (
                                <tr>
                                  <td
                                    colSpan={6}
                                    className="py-8 text-center text-zinc-500 italic text-[9px] uppercase tracking-wider"
                                  >
                                    No completed round stats yet. Play this
                                    round to update!
                                  </td>
                                </tr>
                              );
                            }
                            return dropdownStats.map((rs) => {
                              const pUser = rs.playerStats.find(
                                (ps) => ps.id === "p1",
                              );
                              const pFatima = rs.playerStats.find(
                                (ps) => ps.id === "p2",
                              );
                              const pAmir = rs.playerStats.find(
                                (ps) => ps.id === "p3",
                              );
                              const pOmar = rs.playerStats.find(
                                (ps) => ps.id === "p4",
                              );

                              return (
                                <tr
                                  key={rs.round}
                                  className="hover:bg-white/5 transition-colors font-mono"
                                >
                                  <td className="py-1 px-1.5 text-center font-bold text-zinc-400">
                                    #{rs.round}
                                  </td>
                                  <td className="py-1 px-1.5 text-center">
                                    <span
                                      className={`inline-flex items-center justify-center font-sans font-bold px-1.5 py-0.5 rounded text-[8px] ${
                                        rs.trumpSuit === "NONE"
                                          ? "bg-amber-500/10 text-amber-500"
                                          : "bg-white/10 text-zinc-200"
                                      }`}
                                    >
                                      {rs.trumpSuit === "NONE"
                                        ? "SANS"
                                        : getSuitUI(rs.trumpSuit).char}
                                    </span>
                                  </td>

                                  {/* User */}
                                  <td
                                    className={`py-1 px-1.5 ${pUser?.isSuccess ? "text-emerald-400 font-bold bg-emerald-500/5" : "text-red-400 bg-red-500/5"}`}
                                  >
                                    <div className="text-[9px] leading-tight">
                                      {pUser?.isDashCall
                                        ? "DASH"
                                        : `${pUser?.bid}/${pUser?.tricksWon}`}
                                    </div>
                                    <div className="text-[8px] opacity-75">
                                      {pUser?.finalScore &&
                                      pUser.finalScore >= 0
                                        ? "+"
                                        : ""}
                                      {pUser?.finalScore}
                                    </div>
                                  </td>

                                  {/* Fatima */}
                                  <td
                                    className={`py-1 px-1.5 ${pFatima?.isSuccess ? "text-emerald-400 bg-emerald-500/5" : "text-red-400 bg-red-500/5"}`}
                                  >
                                    <div className="text-[9px] leading-tight">
                                      {pFatima?.isDashCall
                                        ? "DASH"
                                        : `${pFatima?.bid}/${pFatima?.tricksWon}`}
                                    </div>
                                    <div className="text-[8px] opacity-75">
                                      {pFatima?.finalScore &&
                                      pFatima.finalScore >= 0
                                        ? "+"
                                        : ""}
                                      {pFatima?.finalScore}
                                    </div>
                                  </td>

                                  {/* Amir */}
                                  <td
                                    className={`py-1 px-1.5 ${pAmir?.isSuccess ? "text-emerald-400 bg-emerald-500/5" : "text-red-400 bg-red-500/5"}`}
                                  >
                                    <div className="text-[9px] leading-tight">
                                      {pAmir?.isDashCall
                                        ? "DASH"
                                        : `${pAmir?.bid}/${pAmir?.tricksWon}`}
                                    </div>
                                    <div className="text-[8px] opacity-75">
                                      {pAmir?.finalScore &&
                                      pAmir.finalScore >= 0
                                        ? "+"
                                        : ""}
                                      {pAmir?.finalScore}
                                    </div>
                                  </td>

                                  {/* Omar */}
                                  <td
                                    className={`py-1 px-1.5 ${pOmar?.isSuccess ? "text-emerald-400 bg-emerald-500/5" : "text-red-400 bg-red-500/5"}`}
                                  >
                                    <div className="text-[9px] leading-tight">
                                      {pOmar?.isDashCall
                                        ? "DASH"
                                        : `${pOmar?.bid}/${pOmar?.tricksWon}`}
                                    </div>
                                    <div className="text-[8px] opacity-75">
                                      {pOmar?.finalScore &&
                                      pOmar.finalScore >= 0
                                        ? "+"
                                        : ""}
                                      {pOmar?.finalScore}
                                    </div>
                                  </td>
                                </tr>
                              );
                            });
                          })()}
                        </tbody>
                      </table>
                    </div>
                  </div>

                  {/* Subtle Footer info */}
                  <div className="text-center text-[8px] text-zinc-500 uppercase tracking-widest font-black mt-1 font-sans">
                    ESTEMSHAN LEAGUE • CURRENT MULTIPLIER x{multiplier}
                  </div>
                </motion.div>
              </>
            )}
          </AnimatePresence>
        </div>

        <div className="flex gap-2 sm:gap-4 items-center">
          {round >= 14 && (
            <div className="text-[10px] uppercase tracking-widest font-black bg-indigo-950/60 text-indigo-300 px-3 py-1.5 rounded-full border border-indigo-500/20 shadow-md">
              🎯 Fixed Trump:{" "}
              {effectiveTrumpSuit === "NONE" ? "SANS" : effectiveTrumpSuit}
            </div>
          )}
          <div className="text-[10px] uppercase tracking-widest font-black bg-amber-500/10 text-amber-400 px-3 py-1.5 rounded-full border border-amber-500/20">
            MUL x{multiplier}
          </div>
          <button
            onClick={() => setShowHistoryModal(true)}
            className="flex items-center gap-1.5 text-[10px] uppercase tracking-widest font-black bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-400 border border-emerald-500/30 hover:border-emerald-500/50 px-3 py-1.5 rounded-full shadow-lg transition-all cursor-pointer active:scale-95"
            title="Open comprehensive chronological round history details!"
          >
            <History size={12} />
            <span>History</span>
          </button>
          <button
            onClick={() => setShowBotDebuggerModal(true)}
            className="flex items-center gap-1.5 text-[10px] uppercase tracking-widest font-black bg-blue-500/20 hover:bg-blue-500/30 text-blue-400 border border-blue-500/30 hover:border-blue-500/50 px-3 py-1.5 rounded-full shadow-lg transition-all cursor-pointer active:scale-95"
            title="Open Bot Decision reasoning debugger and simulator analysis!"
          >
            <Cpu size={12} />
            <span>Bot Debugger</span>
          </button>
        </div>
      </header>

      {/* TOP-LEVEL TAB DIRECTIVE */}
      <div className="max-w-7xl mx-auto mb-6 flex flex-col md:flex-row items-center justify-between gap-4 bg-slate-900/60 p-3 rounded-2xl border border-slate-800 shadow-xl backdrop-blur-md">
        <div className="flex bg-slate-950 p-1.5 rounded-xl border border-slate-800 w-full md:w-auto">
          <button
            onClick={() => setActiveTab("simulation")}
            className={`flex-1 md:flex-none flex items-center justify-center gap-2 px-6 py-2.5 rounded-lg text-xs font-black uppercase tracking-wider transition-all duration-300 cursor-pointer ${
              activeTab === "simulation"
                ? "bg-gradient-to-r from-amber-500 to-amber-600 text-stone-950 shadow-md shadow-amber-500/10"
                : "text-stone-400 hover:text-stone-200"
            }`}
          >
            🕹️ Game Simulator
          </button>
          <button
            onClick={() => setActiveTab("android-generator")}
            className={`flex-1 md:flex-none flex items-center justify-center gap-2 px-6 py-2.5 rounded-lg text-xs font-black uppercase tracking-wider transition-all duration-300 cursor-pointer ${
              activeTab === "android-generator"
                ? "bg-gradient-to-r from-amber-500 to-amber-600 text-stone-950 shadow-md shadow-amber-500/10"
                : "text-stone-400 hover:text-stone-200"
            }`}
          >
            📱 Android Kotlin Files
          </button>
        </div>

        <div className="text-[11px] font-mono text-stone-400 text-center md:text-right hidden sm:block">
          {activeTab === "simulation" ? (
            <span className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              MVI State Machine Engine Active
            </span>
          ) : (
            <span className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
              Jetpack Compose Blueprint Generator
            </span>
          )}
        </div>
      </div>

      {activeTab === "simulation" ? (
        <>
          <main className="grid grid-cols-12 gap-6 max-w-7xl mx-auto items-stretch">
            {/* GAMING TABLE */}
            <div className="col-span-12 lg:col-span-8 rounded-[3.5rem] border-[12px] border-[#3D2516] h-[610px] relative shadow-[inset_0_4px_40px_rgba(0,0,0,0.55),0_12px_45px_rgba(0,0,0,0.65)] flex items-center justify-center overflow-hidden">
              {phase === "DASH_CALL_DECISION" ||
              phase === "CALL_PHASE" ||
              phase === "TRUMP_DECLARATION" ? (
                <AuctionStage
                  phase={phase}
                  players={players}
                  biddingTurnIdx={biddingTurnIdx}
                  highestBidTricks={highestBidTricks}
                  highestBidSuit={highestBidSuit}
                  highestBidderId={highestBidderId}
                  auctionBids={auctionBids}
                  submitDashCall={submitDashCall}
                  submitCall={submitCall}
                  submitTrumpDeclaration={submitTrumpDeclaration}
                  restartGame={restartGame}
                  generateLegalBids={generateLegalBids}
                  selectedBidTricks={selectedBidTricks}
                  setSelectedBidTricks={setSelectedBidTricks}
                  selectedBidSuit={selectedBidSuit}
                  setSelectedBidSuit={setSelectedBidSuit}
                  userHand={players[0].hand}
                  mandatoryTrump={
                    round >= 14
                      ? (["NONE", "SPADES", "HEARTS", "DIAMONDS", "CLUBS"][
                          (round - 14) % 5
                        ] as Suit | "NONE")
                      : null
                  }
                  isInitialFastRoundPass={
                    round >= 14 && highestBidderId === null
                  }
                  renderAvatar={(playerId) => {
                    if (playerId === "p1") return <BarbryyAvatar />;
                    if (playerId === "p2") return <Bot3Avatar />;
                    if (playerId === "p3") return <Bot2Avatar />;
                    if (playerId === "p4") return <Bot1Avatar />;
                    return null;
                  }}
                />
              ) : (
                <>
                  {/* High-fidelity Refined Egyptian Card Table Background */}
                  <div
                    className="absolute inset-0 pointer-events-none select-none bg-cover bg-center"
                    style={{
                      backgroundImage: `radial-gradient(ellipse 70% 60% at 50% 50%, rgba(107, 66, 38, 0.45) 0%, rgba(26, 15, 8, 0.95) 100%), url('/src/assets/images/wooden_table_bg_1780614538974.png')`,
                      backgroundColor: "#1A0F08",
                    }}
                  />

                  {/* LEFT COMPACT UTILITY SIDEBAR CONTROLS (Exactly styled red buttons as seen on left shoulder of Bot 3 in Image 1) */}
                  <div className="absolute left-4 bottom-22 flex flex-col gap-3.5 z-25 items-center pointer-events-auto">
                    {/* Setting Gear Red Box Button */}
                    <button
                      onClick={restartGame}
                      className="w-9 h-9 flex items-center justify-center rounded-lg bg-[#C22F2F] hover:bg-[#A12323] text-white hover:scale-105 active:scale-95 transition-all cursor-pointer shadow-md shadow-black/40 border border-stone-800/20 text-sm select-none"
                      title="Reset Game Match"
                    >
                      ⚙
                    </button>
                    {/* Dynamic Speak Chat Red Capsule Button */}
                    <button
                      onClick={() => setShowReactionMenu(!showReactionMenu)}
                      className="w-9 h-18 flex flex-col items-center justify-center rounded-xl bg-[#C22F2F] hover:bg-[#A12323] text-white hover:scale-105 active:scale-95 transition-all cursor-pointer shadow-md shadow-black/40 border border-stone-800/20 relative"
                      title="Speak Reactions"
                    >
                      <span className="text-xs leading-none select-none">
                        ▶
                      </span>
                      {showReactionMenu && (
                        <span className="absolute -top-1 -right-1 w-2.5 h-2.5 bg-yellow-400 rounded-full border border-slate-900" />
                      )}
                    </button>
                  </div>

                  {/* TOP RIGHT WIDGET PANEL (Round Status & Round Trump Sanz indicator) */}
                  <div className="absolute top-4 right-36 z-25 flex items-center gap-1.5 pointer-events-auto select-none">
                    {/* ROUND TRUMP (Sanz / Suit) */}
                    {showTrumpWidget && (
                      <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-slate-900/90 border border-white/10 shadow-lg">
                        <span className="text-[8px] uppercase tracking-wider font-black text-slate-400">
                          TRUMP
                        </span>
                        {(() => {
                          const info = getTrumpInfo(effectiveTrumpSuit);
                          return (
                            <div className="flex items-center gap-1">
                              {effectiveTrumpSuit === "NONE" ? (
                                <span className="text-yellow-400 text-xs font-bold leading-none">
                                  ☀️
                                </span>
                              ) : (
                                <span
                                  className={`${info.textColor} font-black text-[11px] leading-none`}
                                >
                                  {info.char}
                                </span>
                              )}
                              <span className="text-[9px] font-black text-stone-100 uppercase">
                                {effectiveTrumpSuit === "NONE"
                                  ? "SANZ"
                                  : info.textName}
                              </span>
                            </div>
                          );
                        })()}
                      </div>
                    )}

                    {/* ROUND STATUS SCORE DELTA */}
                    {overUnderValue !== null && (
                      <div className="px-3 py-1.5 rounded-xl bg-slate-900/90 border border-white/10 shadow-lg flex items-center gap-1.5">
                        <span className="text-[10px] font-black text-slate-400">
                          ROUND
                        </span>
                        <span
                          className={`text-[11px] font-black font-mono ${overUnderValue >= 0 ? "text-emerald-400" : "text-rose-400"}`}
                        >
                          {overUnderValue >= 0
                            ? `+${overUnderValue}`
                            : overUnderValue}
                        </span>
                      </div>
                    )}
                  </div>

                  {/* TRICK INDICATOR MINIMAP (Top Right) */}
                  <div className="absolute top-4 right-4 z-25 w-28 h-28 bg-[#090D14]/95 border-2 border-amber-500/30 rounded-2xl shadow-2xl flex flex-col items-center p-2 pointer-events-auto select-none">
                    <span className="text-[7px] font-black text-[#DFC5A9] uppercase tracking-wider mb-2 text-center w-full">
                      TRICK MINIMAP
                    </span>

                    {/* Cross-shaped 4-dot grid arrangement mimicking the video tracker */}
                    <div className="relative w-14 h-14 bg-black/45 rounded-lg border border-stone-800 flex items-center justify-center">
                      {(() => {
                        const hasP1 = currentTrick.some(
                          (pt) => pt.playerId === "p1",
                        );
                        const hasP2 = currentTrick.some(
                          (pt) => pt.playerId === "p2",
                        );
                        const hasP3 = currentTrick.some(
                          (pt) => pt.playerId === "p3",
                        );
                        const hasP4 = currentTrick.some(
                          (pt) => pt.playerId === "p4",
                        );

                        // Find played suit character to show in dot if active, or initials
                        const p1Card = currentTrick.find(
                          (pt) => pt.playerId === "p1",
                        )?.card;
                        const p2Card = currentTrick.find(
                          (pt) => pt.playerId === "p2",
                        )?.card;
                        const p3Card = currentTrick.find(
                          (pt) => pt.playerId === "p3",
                        )?.card;
                        const p4Card = currentTrick.find(
                          (pt) => pt.playerId === "p4",
                        )?.card;

                        const getDotContent = (
                          hasPlayed: boolean,
                          card?: CardModel,
                          initials = "",
                        ) => {
                          if (!hasPlayed)
                            return (
                              <span className="opacity-40">{initials}</span>
                            );
                          if (!card)
                            return (
                              <span className="text-emerald-400 font-extrabold font-mono">
                                ●
                              </span>
                            );
                          const ui = getSuitUI(card.suit);
                          return (
                            <span
                              className={`${ui.color} font-bold text-[9px] leading-none`}
                            >
                              {ui.char}
                            </span>
                          );
                        };

                        return (
                          <>
                            {/* Top Dot: Amir (p3) */}
                            <div
                              className={`absolute top-0.5 left-1/2 -translate-x-1/2 w-4 h-4 rounded-full flex items-center justify-center text-[7px] font-black transition-all duration-300 ${
                                hasP3
                                  ? "bg-emerald-500/20 border border-emerald-400 text-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.4)]"
                                  : "bg-stone-800 border border-stone-700 text-stone-500"
                              }`}
                              title={hasP3 ? `Amir played` : `Amir waiting`}
                            >
                              {getDotContent(hasP3, p3Card, "A")}
                            </div>

                            {/* Bottom Dot: You (p1) */}
                            <div
                              className={`absolute bottom-0.5 left-1/2 -translate-x-1/2 w-4 h-4 rounded-full flex items-center justify-center text-[7px] font-black transition-all duration-300 ${
                                hasP1
                                  ? "bg-emerald-500/20 border border-emerald-400 text-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.4)]"
                                  : "bg-stone-800 border border-stone-700 text-stone-500"
                              }`}
                              title={hasP1 ? `You played` : `You waiting`}
                            >
                              {getDotContent(hasP1, p1Card, "Y")}
                            </div>

                            {/* Left Dot: Fatima (p2) */}
                            <div
                              className={`absolute left-0.5 top-1/2 -translate-y-1/2 w-4 h-4 rounded-full flex items-center justify-center text-[7px] font-black transition-all duration-300 ${
                                hasP2
                                  ? "bg-emerald-500/20 border border-emerald-400 text-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.4)]"
                                  : "bg-stone-800 border border-stone-700 text-stone-500"
                              }`}
                              title={hasP2 ? `Fatima played` : `Fatima waiting`}
                            >
                              {getDotContent(hasP2, p2Card, "F")}
                            </div>

                            {/* Right Dot: Omar (p4) */}
                            <div
                              className={`absolute right-0.5 top-1/2 -translate-y-1/2 w-4 h-4 rounded-full flex items-center justify-center text-[7px] font-black transition-all duration-300 ${
                                hasP4
                                  ? "bg-emerald-500/20 border border-emerald-400 text-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.4)]"
                                  : "bg-stone-800 border border-stone-700 text-stone-500"
                              }`}
                              title={hasP4 ? `Omar played` : `Omar waiting`}
                            >
                              {getDotContent(hasP4, p4Card, "O")}
                            </div>
                          </>
                        );
                      })()}
                    </div>

                    {/* Quick Button to see full last trick view */}
                    <button
                      onClick={() => setShowLastTrickModal(!showLastTrickModal)}
                      className="mt-1 px-2 py-0.5 rounded bg-[#DFC5A9]/10 hover:bg-[#DFC5A9]/30 text-[7px] text-[#DFC5A9] border border-[#DFC5A9]/25 font-black hover:scale-105 active:scale-95 transition-all w-full text-center"
                    >
                      👀 REPLAY LAST
                    </button>
                  </div>

                  {/* ROUND CALENDAR STATUS RECORD INDICATOR (Top center/left next to Bot 2 - exactly like Image 1) */}
                  <div className="absolute top-4 left-[28%] md:left-[32%] z-25 w-12 h-14 rounded-lg bg-[#2C1A0E] border border-[#D4AF37] shadow-md flex flex-col overflow-hidden select-none">
                    {/* Brass Header */}
                    <div className="bg-[#D4AF37] h-4.5 w-full flex items-center justify-center">
                      <span className="text-[7.5px] font-black text-[#2A1505] tracking-widest uppercase">
                        ROUND
                      </span>
                    </div>
                    {/* Brass Body displaying Round number */}
                    <div className="flex-1 flex flex-col items-center justify-center">
                      <span className="text-[13px] font-black text-[#E8D5B0] leading-none font-mono">
                        {round}
                      </span>
                      <span className="text-[5.5px] text-[#BFA77E]/70 mt-0.5 tracking-tighter">
                        OF {maxRounds}
                      </span>
                    </div>
                  </div>

                  {/* 4-PLAYER POSITIONS MAP (Directly placed around table matching Image 1 & Image 2) */}
                  {players.map((p, i) => {
                    // Coordinate definitions representing specified layout
                    let posClass = "";
                    let rankLvl = 13;

                    if (i === 0) {
                      // Player 1 (You - Sheikh Barbryy) -> Top Left
                      posClass = "top-4 left-4 lg:left-8";
                      rankLvl = 396;
                    } else if (i === 2) {
                      // Player 3 (Bot 2 - Amir) -> Top Center
                      posClass = "top-4 left-1/2 -translate-x-1/2";
                      rankLvl = 231;
                    } else if (i === 1) {
                      // Player 4 (Bot 3 - Fatima) -> Left Center (Perfect stack under Barbryy)
                      posClass = "top-[235px] left-4 lg:left-8 shadow-2xl";
                      rankLvl = 13;
                    } else if (i === 3) {
                      // Player 2 (Bot 1 - Omar) -> Right Center inside table area
                      posClass = "top-[235px] right-4 lg:right-8 shadow-2xl";
                      rankLvl = 49;
                    }

                    const isOpponent = i !== 0;
                    const isCaller = p.id === highestBidderId;

                    // Define overall match leaders (King) and current last-place scorer (Kooz)
                    const playerScores = players.map((pl) => pl.score);
                    const maxScore = Math.max(...playerScores);
                    const minScore = Math.min(...playerScores);

                    const isKing = p.score === maxScore && maxScore > 0;
                    const isKooz = p.score === minScore && minScore < maxScore;

                    const isWithPlayer =
                      p.id === "p1" && phase === "FOLLOWING_BIDS"
                        ? selectedBidTricks !== null
                          ? p.isWazz &&
                            (selectedBidTricks === highestBidTricks ||
                              selectedBidTricks ===
                                (auctionBids["p1"]?.bid ?? -1))
                          : !!p.isWazz
                        : !!p.isWazz;

                    // Turn thinking highlight status with glowing frame
                    const isThinking =
                      activePlayerIdx === i && phase === "PLAYING";

                    return (
                      <div
                        key={p.id}
                        className={`absolute ${posClass} z-20 flex flex-col items-center select-none`}
                      >
                        <div
                          onClick={() => {
                            if (i === 0) setShowReactionMenu(!showReactionMenu);
                          }}
                          className={`w-20 h-20 md:w-[84px] md:h-[84px] rounded-2xl bg-[#1A0F08] border-2 shadow-2xl select-none relative transition-all duration-300 ${
                            isWithPlayer
                              ? "border-[#D4AF37] ring-4 ring-[#D4AF37]/65 scale-[1.03] shadow-[0_0_20px_rgba(212,175,55,0.8)] animate-pulse"
                              : isThinking
                                ? "border-[#D4AF37] ring-2 ring-[#FFC94D]/45 scale-[1.02] shadow-[0_0_15px_rgba(255,201,77,0.45)]"
                                : "border-[#B08D57]/70"
                          } ${i === 0 ? "cursor-pointer hover:border-[#F3D26A] hover:scale-[1.04]" : ""}`}
                        >
                          {/* Avatar Svg Graphic */}
                          {i === 0 && <BarbryyAvatar />}
                          {i === 1 && <Bot3Avatar />}
                          {i === 2 && <Bot2Avatar />}
                          {i === 3 && <Bot1Avatar />}

                          {/* Rank Level circular metallic badge overlapping bottom-left (Exactly like Image) */}
                          <div className="absolute -bottom-2 -left-2 w-7 h-7 sm:w-8 sm:h-8 rounded-full bg-gradient-to-b from-[#7A5C33] via-[#3A2410] to-[#1A1005] border-2 border-[#D4AF37] flex items-center justify-center shadow-lg select-none z-10">
                            <div className="absolute inset-[1px] rounded-full border border-stone-900/60 flex items-center justify-center">
                              <span className="text-[9px] sm:text-[10px] font-black text-[#FAF5E8] font-sans tracking-tight drop-shadow-[0_1px_2px_rgba(0,0,0,0.8)]">
                                {rankLvl}
                              </span>
                            </div>
                          </div>

                          {/* Risk Player Avatar mini-badge in bottom-right overlapping */}
                          {(() => {
                            const callerIdx = players.findIndex(
                              (pl) => pl.id === highestBidderId,
                            );
                            const riskCandidateId =
                              lastBidderState ||
                              (callerIdx !== -1
                                ? players[(callerIdx + 1) % 4].id
                                : "");
                            const isRiskPlayer = p.id === riskCandidateId;
                            if (
                              isRiskPlayer &&
                              (phase === "FOLLOWING_BIDS" ||
                                phase === "PLAYING" ||
                                phase === "ROUND_OVER")
                            ) {
                              return (
                                <div className="absolute -bottom-2 -right-2 w-7 h-7 sm:w-8 sm:h-8 rounded-full bg-gradient-to-br from-amber-400 via-amber-500 to-amber-600 border-2 border-amber-300 flex items-center justify-center shadow-[0_0_10px_rgba(245,158,11,0.95)] z-15 select-none animate-pulse">
                                  <span className="text-[11px] sm:text-[12px] font-black text-amber-950 font-sans tracking-tight drop-shadow-[0_1px_1px_rgba(255,255,255,0.25)]">
                                    R
                                  </span>
                                </div>
                              );
                            }
                            return null;
                          })()}

                          {/* Chat bubble icon in top right (clicking triggers react menu, matching 💬 in Image) */}
                          <div
                            onClick={(e) => {
                              if (i === 0) {
                                e.stopPropagation();
                                setShowReactionMenu(!showReactionMenu);
                              }
                            }}
                            className="absolute -top-2.5 -right-2.5 w-6 h-6 sm:w-7 sm:h-7 bg-[#E8D5B0] rounded-full flex items-center justify-center shadow-md border border-[#B08D57] cursor-pointer hover:scale-110 active:scale-95 transition-all z-20"
                          >
                            {/* Small speech balloon pointer */}
                            <div className="absolute bottom-[-1.5px] left-[2.5px] w-1.5 h-1.5 bg-[#E8D5B0] rotate-45 border-r border-b border-[#B08D57]" />
                            <span className="text-zinc-900 text-[9px] font-black tracking-widest leading-none z-10 select-none pb-0.5">
                              ...
                            </span>
                          </div>

                          {/* Thinking / Turn countdown timer overlay */}
                          {isThinking && (
                            <div className="absolute -top-6 left-1/2 -translate-x-1/2 bg-[#FFC94D] text-[#2A1505] px-1.5 py-0.5 rounded-full border border-[#FAF5E8] text-[8px] font-black shadow-lg select-none flex items-center gap-0.5 z-25 animate-pulse">
                              ⏳{turnTimer}
                            </div>
                          )}

                          {/* Dynamic Bidding/Estimation Reaction Badges/Speech Flag inside table */}
                          {(() => {
                            const bidItem = auctionBids[p.id];
                            const hasBidValue = bidItem !== undefined;
                            const hasPassed = hasBidValue && bidItem.bid === -1;

                            if (hasPassed && phase === "CALL_PHASE") {
                              return (
                                <div className="absolute top-2 -right-10 bg-[#EAB308] text-black text-[11px] font-black px-2 py-0.5 rounded border-2 border-black/80 shadow-lg select-none z-35 tracking-tight animate-fade-in font-mono">
                                  PASS
                                </div>
                              );
                            } else if (
                              hasBidValue &&
                              bidItem.bid > 0 &&
                              phase === "CALL_PHASE"
                            ) {
                              const suitUi = getSuitUI(bidItem.suit);
                              return (
                                <div className="absolute top-2 -right-14 bg-rose-700/90 text-white text-[11px] font-black px-2.5 py-0.5 rounded border-2 border-white/20 shadow-lg select-none z-35 flex items-center gap-0.5 animate-fade-in font-mono">
                                  <span>{bidItem.bid}</span>
                                  <span className={suitUi.color}>
                                    {suitUi.char}
                                  </span>
                                </div>
                              );
                            }
                            return null;
                          })()}

                          {/* High Quality Side Badges: Sports-Style Ratio Tracker, Bold WITH, Bold AVOID (Matching Images) */}
                          {(() => {
                            const isWithPlayer =
                              p.id === "p1" && phase === "FOLLOWING_BIDS"
                                ? selectedBidTricks !== null
                                  ? p.isWazz &&
                                    (selectedBidTricks === highestBidTricks ||
                                      selectedBidTricks ===
                                        (auctionBids["p1"]?.bid ?? -1))
                                  : !!p.isWazz
                                : !!p.isWazz;
                            const showTricks =
                              (phase === "PLAYING" ||
                                phase === "FOLLOWING_BIDS") &&
                              p.bid >= 0;
                            const isLeftSide = i === 3; // Bot 1 (on the right side of the table) puts side items on the left side to prevent screen clipping

                            const callerIdx = players.findIndex(
                              (pl) => pl.id === highestBidderId,
                            );
                            const riskCandidateId =
                              lastBidderState ||
                              (callerIdx !== -1
                                ? players[(callerIdx + 1) % 4].id
                                : "");
                            const isRiskPlayer = p.id === riskCandidateId;

                            if (
                              !p.hasAvoid &&
                              !isWithPlayer &&
                              !showTricks &&
                              !isRiskPlayer
                            ) {
                              return null;
                            }

                            return (
                              <div
                                className={`absolute ${
                                  isLeftSide
                                    ? "right-full pr-3"
                                    : "left-full pl-3"
                                } top-1/2 -translate-y-1/2 flex flex-col ${
                                  isLeftSide ? "items-end" : "items-start"
                                } gap-1 z-35 select-none pointer-events-none`}
                              >
                                {/* Giant bold high-contrast tricks tracker matching image */}
                                {showTricks && (
                                  <div className="text-white text-3xl md:text-4xl font-extrabold tracking-tight drop-shadow-[0_2px_4px_rgba(0,0,0,0.95)] select-none">
                                    {p.tricksWon}/{p.bid}
                                  </div>
                                )}

                                {/* WITH Badge matching image */}
                                {isWithPlayer && (
                                  <div className="bg-[#3B82F6] text-slate-950 px-3 py-1 text-xs md:text-sm font-black uppercase tracking-wider rounded-md border-2 border-stone-900/90 shadow-md select-none">
                                    WITH
                                  </div>
                                )}

                                {/* RISK Badge with glow effect and distinct amber color */}
                                {isRiskPlayer &&
                                  (phase === "FOLLOWING_BIDS" ||
                                    phase === "PLAYING" ||
                                    phase === "ROUND_OVER") && (
                                    <div className="bg-gradient-to-r from-amber-400 via-amber-500 to-amber-600 text-slate-950 px-3 py-1 text-xs sm:text-xs font-black uppercase tracking-wider rounded-md border-2 border-amber-300 shadow-[0_0_12px_rgba(245,158,11,0.85)] select-none animate-pulse">
                                      ⚡ RISK
                                    </div>
                                  )}

                                {/* AVOID Badge matching image */}
                                {p.hasAvoid && (
                                  <div className="bg-[#EF4444] text-white px-3 py-1 text-xs md:text-sm font-black uppercase tracking-wider rounded-md border-2 border-stone-800 shadow-md select-none animate-pulse">
                                    AVOID
                                  </div>
                                )}
                              </div>
                            );
                          })()}

                          {/* Barbryy speaker reacting bubbles */}
                          {i === 0 && activeReaction && (
                            <div className="absolute -top-16 left-1/2 -translate-x-1/2 bg-[#FFFBEB] text-slate-950 px-3 py-1.5 rounded-xl shadow-2xl border-2 border-amber-500 text-[10px] font-black whitespace-nowrap z-50 animate-bounce flex items-center justify-center select-none">
                              <span>{activeReaction.text}</span>
                              <span className="absolute bottom-[-6px] left-1/2 -translate-x-1/2 w-2.5 h-2.5 bg-[#FFFBEB] border-r-2 border-b-2 border-amber-500 rotate-45" />
                            </div>
                          )}

                          {/* Pop over film reaction selector */}
                          {i === 0 && showReactionMenu && (
                            <div className="absolute bottom-16 left-1/2 -translate-x-1/2 bg-slate-950 border-2 border-[#BE185D] shadow-2xl p-2 rounded-xl z-55 w-52 grid grid-cols-1 gap-1 pointer-events-auto">
                              <div className="text-[8px] font-black text-rose-400 text-center uppercase tracking-wider mb-0.5 border-b border-white/5 pb-1 flex justify-between items-center px-1">
                                <span>Film quotes (إفهات)</span>
                                <button
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setShowReactionMenu(false);
                                  }}
                                  className="text-stone-400 hover:text-red-500 text-[9px]"
                                >
                                  ✕
                                </button>
                              </div>
                              {MOVIE_MEMES.map((meme, idx) => (
                                <button
                                  key={idx}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    setActiveReaction({ text: meme.quote });
                                    setShowReactionMenu(false);
                                    setTimeout(
                                      () => setActiveReaction(null),
                                      2500,
                                    );
                                  }}
                                  className="text-[9px] text-slate-100 bg-neutral-900 border border-slate-800 hover:border-rose-500 hover:bg-rose-500/20 px-2.5 py-1 rounded-md text-left transition-all font-semibold cursor-pointer truncate"
                                >
                                  {meme.label}
                                </button>
                              ))}
                            </div>
                          )}
                        </div>

                        {/* Name & Cumulative Score centered beneath Avatar (Floating on table background, exactly like image) */}
                        <div className="mt-2 text-center flex flex-col items-center select-none font-sans">
                          <span
                            className="text-stone-100 text-[15px] md:text-[17px] font-black tracking-wide select-none drop-shadow-[0_2.5px_4px_rgba(0,0,0,0.98)]"
                            title={
                              p.personality
                                ? PERSONALITIES[p.personality].blurb
                                : ""
                            }
                          >
                            {p.name}{" "}
                            {p.personality
                              ? `[${PERSONALITIES[p.personality].displayName}]`
                              : ""}{" "}
                            ({p.score})
                          </span>

                          {/* High quality role badge list below name */}
                          <div className="flex gap-1 justify-center mt-1">
                            {isCaller && (
                              <span
                                className="text-[8px] bg-amber-500 text-slate-950 font-black px-1.5 py-0.5 rounded shadow animate-pulse"
                                title="Caller"
                              >
                                C
                              </span>
                            )}
                            {p.isDashCall && (
                              <span className="text-[8px] bg-purple-500/40 text-purple-100 border border-purple-500/20 font-black px-1.5 py-0.5 rounded shadow">
                                DASH
                              </span>
                            )}
                            {isKing && (
                              <span
                                className="text-[8px] bg-yellow-500 text-black px-1.5 py-0.5 rounded shadow"
                                title="Leader"
                              >
                                👑
                              </span>
                            )}
                            {isKooz && (
                              <span
                                className="text-[8px] bg-stone-500 text-black px-1.5 py-0.5 rounded shadow"
                                title="Kooz"
                              >
                                🧲
                              </span>
                            )}
                          </div>
                        </div>
                      </div>
                    );
                  })}

                  {/* CENTRAL CARD FIELD: 4-CARD PLAYING ZONE IN CROSS FORMATION */}
                  <div className="absolute top-[180px] left-1/2 -translate-x-1/2 w-80 h-[260px] z-10 select-none pointer-events-none">
                    {/* The 4 Played / Empty Card Slots mapped directly to players */}
                    {(() => {
                      const directions = [
                        {
                          playerId: "p3",
                          direction: "TOP",
                          label: "Amir",
                          class: "absolute top-0 left-1/2 -translate-x-1/2",
                        },
                        {
                          playerId: "p4",
                          direction: "RIGHT",
                          label: "Omar",
                          class: "absolute right-2 top-1/2 -translate-y-1/2",
                        },
                        {
                          playerId: "p1",
                          direction: "BOTTOM",
                          label: "You",
                          class: "absolute bottom-0 left-1/2 -translate-x-1/2",
                        },
                        {
                          playerId: "p2",
                          direction: "LEFT",
                          label: "Fatima",
                          class: "absolute left-2 top-1/2 -translate-y-1/2",
                        },
                      ];

                      return directions.map((dir) => {
                        const playedObj = currentTrick.find(
                          (pt) => pt.playerId === dir.playerId,
                        );
                        const isCurrentTurn =
                          activePlayerIdx !== -1 &&
                          players[activePlayerIdx]?.id === dir.playerId &&
                          phase === "PLAYING";

                        return (
                          <div
                            key={dir.playerId}
                            className={`${dir.class} z-20 pointer-events-auto`}
                          >
                            <AnimatePresence mode="wait">
                              {playedObj ? (
                                <motion.div
                                  key="card"
                                  initial={{ scale: 0.8, opacity: 0, y: 10 }}
                                  animate={{ scale: 1, opacity: 1, y: 0 }}
                                  exit={{ scale: 0.8, opacity: 0 }}
                                  transition={{
                                    type: "spring",
                                    stiffness: 300,
                                    damping: 20,
                                  }}
                                >
                                  <Card
                                    card={playedObj.card}
                                    size="small"
                                    isLead={
                                      playedObj.card.suit === derivedLedSuit
                                    }
                                    disabled
                                  />
                                </motion.div>
                              ) : (
                                <div
                                  key="placeholder"
                                  className={`w-16 h-24 rounded-lg border-2 border-dashed flex flex-col justify-between p-1.5 transition-all duration-300 ${
                                    isCurrentTurn
                                      ? "border-emerald-400 bg-emerald-500/15 shadow-[0_0_15px_rgba(52,211,153,0.35)] text-emerald-450 animate-pulse"
                                      : "border-[#DFC5A9]/20 bg-black/45 text-[#DFC5A9]/35"
                                  }`}
                                >
                                  <div className="text-[6.5px] font-black tracking-widest text-center uppercase">
                                    {dir.direction}
                                  </div>
                                  <div className="text-[8.5px] font-black text-center truncate w-full uppercase">
                                    {dir.label}
                                  </div>
                                  <div className="text-[6px] text-center opacity-65">
                                    SLOT
                                  </div>
                                </div>
                              )}
                            </AnimatePresence>
                          </div>
                        );
                      });
                    })()}

                    {/* Turn status indicator centered on top of the cross slots */}
                    {phase === "PLAYING" && (
                      <div className="absolute -top-12 left-1/2 -translate-x-1/2 bg-slate-900 border border-slate-800 px-4 py-1.5 rounded-full shadow-2xl text-[9px] uppercase tracking-widest font-black text-amber-500 flex items-center gap-1.5 whitespace-nowrap z-35 animate-fade-in">
                        <span className="w-1.5 h-1.5 bg-emerald-500 rounded-full animate-ping" />
                        {activePlayerIdx === 0
                          ? "👉 Your Turn"
                          : `🤖 ${players[activePlayerIdx]?.name.split(" ")[0]} plays...`}
                      </div>
                    )}

                    {currentTrick.length === 0 && phase !== "DEALING" && (
                      <div className="absolute top-[42%] left-1/2 -translate-x-1/2 bg-slate-900/30 px-3 py-1 rounded-md text-stone-300 text-[10px] uppercase font-black tracking-widest text-center">
                        {effectiveTrumpSuit === "NONE"
                          ? "SANZ"
                          : effectiveTrumpSuit}
                      </div>
                    )}

                    {phase === "DEALING" && (
                      <div className="absolute inset-0 bg-slate-950/80 backdrop-blur-md z-30 rounded-[1.8rem] flex flex-col pointer-events-auto border border-amber-500/20 p-5 overflow-hidden justify-between">
                        {/* LOBBY HEADER */}
                        <div className="flex items-center justify-between border-b border-white/5 pb-2">
                          <div>
                            <h2 className="text-sm font-black tracking-widest text-amber-500 uppercase flex items-center gap-1">
                              👑 ESTEMSHAN GAMING LOBBY
                            </h2>
                            <p className="text-[10px] text-stone-400 font-mono">
                              Configure bot difficulty, profiles & match parameters
                            </p>
                          </div>
                          
                          {/* PRESETS */}
                          <div className="flex gap-1.5">
                            <button
                              onClick={() => {
                                setMaxRounds(18);
                                setMultiplier(1);
                                setPlayers([
                                  { ...players[0], name: "You (باشا)" },
                                  { ...players[1], name: "Fatima (جدع)", difficulty: "EASY", personality: "CONSERVATIVE" },
                                  { ...players[2], name: "Amir (حريف)", difficulty: "HARD", personality: "AGGRESSIVE" },
                                  { ...players[3], name: "Omar (الأسطورة)", difficulty: "EXPERT", personality: "TRICKSTER" },
                                ]);
                              }}
                              className="px-2 py-1 text-[8px] uppercase tracking-wider font-bold bg-slate-900 hover:bg-slate-850 border border-slate-800 hover:border-amber-500/50 text-amber-500/85 hover:text-amber-400 rounded-md cursor-pointer transition-all active:scale-95"
                              title="Standard Match (Easy/Hard/Expert bots, 18 Rounds)"
                            >
                              Standard Presets
                            </button>
                            <button
                              onClick={() => {
                                setMaxRounds(18);
                                setMultiplier(2);
                                setPlayers([
                                  { ...players[0], name: "You (باشا)" },
                                  { ...players[1], name: "Fatima (جدع)", difficulty: "EXPERT", personality: "AGGRESSIVE" },
                                  { ...players[2], name: "Amir (حريف)", difficulty: "EXPERT", personality: "AGGRESSIVE" },
                                  { ...players[3], name: "Omar (الأسطورة)", difficulty: "EXPERT", personality: "TRICKSTER" },
                                ]);
                              }}
                              className="px-2 py-1 text-[8px] uppercase tracking-wider font-bold bg-red-950 hover:bg-red-900 border border-red-900 hover:border-red-500/50 text-red-400 rounded-md cursor-pointer transition-all active:scale-95"
                              title="Extreme expert match (All expert bots, 2x Multiplier)"
                            >
                              Expert Challenge
                            </button>
                            <button
                              onClick={() => {
                                setMaxRounds(5);
                                setMultiplier(1);
                                setPlayers([
                                  { ...players[0], name: "You (باشا)" },
                                  { ...players[1], name: "Fatima (جدع)", difficulty: "EASY", personality: "BALANCED" },
                                  { ...players[2], name: "Amir (حريف)", difficulty: "MEDIUM", personality: "BALANCED" },
                                  { ...players[3], name: "Omar (الأسطورة)", difficulty: "HARD", personality: "BALANCED" },
                                ]);
                              }}
                              className="px-2 py-1 text-[8px] uppercase tracking-wider font-bold bg-blue-950/50 hover:bg-blue-900/50 border border-blue-900/50 hover:border-blue-500/50 text-blue-400 rounded-md cursor-pointer transition-all active:scale-95"
                              title="Quick test match (5 Rounds, balanced personalities)"
                            >
                              Quick Test
                            </button>
                          </div>
                        </div>

                        {/* LOBBY CONTENT SCROLLABLE ZONE */}
                        <div className="grid grid-cols-12 gap-4 my-2.5 overflow-y-auto pr-1 flex-1 max-h-[400px]">
                          {/* MATCH SETTINGS CARD */}
                          <div className="col-span-12 md:col-span-4 bg-slate-900/40 border border-white/5 rounded-2xl p-3 flex flex-col gap-3.5">
                            <span className="text-[9px] font-black uppercase tracking-widest text-amber-500/80 block border-b border-white/5 pb-1">
                              Match Configuration
                            </span>
                            
                            {/* MAX ROUNDS */}
                            <div className="flex flex-col gap-1.5">
                              <label className="text-[8px] font-mono tracking-widest text-stone-400 uppercase">
                                Match Length:
                              </label>
                              <div className="grid grid-cols-4 gap-1">
                                {[5, 9, 14, 18].map((r) => (
                                  <button
                                    key={r}
                                    onClick={() => setMaxRounds(r)}
                                    className={`py-1 rounded text-[9px] font-black cursor-pointer transition-all ${
                                      maxRounds === r
                                        ? "bg-amber-500 text-stone-950"
                                        : "bg-slate-950 text-stone-400 border border-white/5 hover:bg-slate-900 hover:text-white"
                                    }`}
                                  >
                                    {r} R
                                  </button>
                                ))}
                              </div>
                            </div>

                            {/* BASE MULTIPLIER */}
                            <div className="flex flex-col gap-1.5">
                              <label className="text-[8px] font-mono tracking-widest text-stone-400 uppercase">
                                Base Multiplier:
                              </label>
                              <div className="grid grid-cols-3 gap-1">
                                {[1, 2, 3].map((m) => (
                                  <button
                                    key={m}
                                    onClick={() => setMultiplier(m)}
                                    className={`py-1 rounded text-[9px] font-black cursor-pointer transition-all ${
                                      multiplier === m
                                        ? "bg-amber-500 text-stone-950"
                                        : "bg-slate-950 text-stone-400 border border-white/5 hover:bg-slate-900 hover:text-white"
                                    }`}
                                  >
                                    x{m}
                                  </button>
                                ))}
                              </div>
                            </div>

                            {/* SCORING MODE */}
                            <div className="flex flex-col gap-1.5">
                              <label className="text-[8px] font-mono tracking-widest text-stone-400 uppercase">
                                Scoring Engine:
                              </label>
                              <div className="grid grid-cols-2 gap-1">
                                {(["NORMAL", "CLASSIC"] as ScoringMode[]).map((mode) => (
                                  <button
                                    key={mode}
                                    onClick={() => setScoringMode(mode)}
                                    className={`py-1 rounded text-[9px] font-black cursor-pointer transition-all ${
                                      scoringMode === mode
                                        ? "bg-amber-500 text-stone-950"
                                        : "bg-slate-950 text-stone-400 border border-white/5 hover:bg-slate-900 hover:text-white"
                                    }`}
                                  >
                                    {mode}
                                  </button>
                                ))}
                              </div>
                            </div>

                            {/* GRAPHICAL SUMMARY OR FUN INFO */}
                            <div className="bg-slate-950/40 p-2.5 rounded-xl border border-white/5 text-[9px] text-stone-400 leading-normal font-mono mt-auto">
                              <span className="text-amber-400/90 font-bold block mb-1">💡 GAME PLAY RULES:</span>
                              Bid correctly to win points! In Rounds 1-13, a Dash call yields +25 points (x mult) on success, or -25 on fail. Normal bids yield 10 + bid points, or -10 x bid if you over/under.
                            </div>
                          </div>

                          {/* SEAT CONFIGURATIONS CARD */}
                          <div className="col-span-12 md:col-span-8 bg-slate-900/40 border border-white/5 rounded-2xl p-3 flex flex-col gap-2.5">
                            <span className="text-[9px] font-black uppercase tracking-widest text-amber-500/80 block border-b border-white/5 pb-1">
                              Player Seats setup
                            </span>

                            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                              {players.map((p, pIdx) => {
                                return (
                                  <div
                                    key={p.id}
                                    className="bg-slate-950/80 border border-white/5 rounded-xl p-2.5 flex flex-col gap-2 shadow-md hover:border-amber-500/10 transition-all"
                                  >
                                    {/* Seat title */}
                                    <div className="flex items-center justify-between text-[8px] font-mono uppercase tracking-widest border-b border-white/5 pb-1">
                                      <span className={p.isUser ? "text-amber-400 font-bold" : "text-stone-400"}>
                                        {p.isUser ? "Seat 1: YOU (باشا)" : `Seat ${pIdx + 1}: BOT`}
                                      </span>
                                      <span className="text-stone-500 text-[7px]">
                                        {p.isUser ? "User controlled" : "AI controlled"}
                                      </span>
                                    </div>

                                    {/* Player Name input */}
                                    <div className="flex flex-col gap-1">
                                      <label className="text-[7px] text-stone-400 uppercase font-bold tracking-wider">
                                        Display Name
                                      </label>
                                      <input
                                        type="text"
                                        value={p.name}
                                        onChange={(e) => updatePlayerField(p.id, "name", e.target.value)}
                                        placeholder="Display Name"
                                        className="bg-slate-900 text-[10px] text-white px-2 py-1 rounded border border-white/10 focus:border-amber-500 focus:outline-none w-full transition-all font-sans font-medium"
                                      />
                                    </div>

                                    {!p.isUser && (
                                      <>
                                        {/* AI DIFFICULTY BUTTONS */}
                                        <div className="flex flex-col gap-1">
                                          <div className="flex items-center justify-between text-[7px] text-stone-400 uppercase font-bold tracking-wider">
                                            <span>AI Skill level</span>
                                            <span className="text-amber-500 font-bold text-[6px]">
                                              {p.difficulty === "EXPERT" ? "🔥 MONTE-CARLO MC" : "RULE_BASED"}
                                            </span>
                                          </div>
                                          <div className="grid grid-cols-4 gap-1">
                                            {(["EASY", "MEDIUM", "HARD", "EXPERT"] as DifficultyLevel[]).map((d) => (
                                              <button
                                                key={d}
                                                onClick={() => updatePlayerField(p.id, "difficulty", d)}
                                                className={`py-0.5 rounded text-[8px] font-black cursor-pointer transition-all ${
                                                  p.difficulty === d
                                                    ? "bg-amber-500/20 text-amber-400 border border-amber-500/40"
                                                    : "bg-slate-900 text-stone-500 border border-white/5 hover:text-stone-300 hover:bg-slate-850"
                                                }`}
                                              >
                                                {d}
                                              </button>
                                            ))}
                                          </div>
                                        </div>

                                        {/* AI PERSONALITY BUTTONS */}
                                        <div className="flex flex-col gap-1">
                                          <div className="flex items-center justify-between text-[7px] text-stone-400 uppercase font-bold tracking-wider">
                                            <span>Personality Profile</span>
                                            <span className="text-stone-500 text-[6px] normal-case">
                                              {p.personality === "BALANCED" && "Balanced bid"}
                                              {p.personality === "AGGRESSIVE" && "+0.4 aggressive"}
                                              {p.personality === "CONSERVATIVE" && "-0.5 cautious"}
                                              {p.personality === "TRICKSTER" && "Dashes often"}
                                            </span>
                                          </div>
                                          <div className="grid grid-cols-4 gap-1">
                                            {(["BALANCED", "AGGRESSIVE", "CONSERVATIVE", "TRICKSTER"] as const).map((key) => {
                                              const profile = PERSONALITIES[key];
                                              return (
                                                <button
                                                  key={key}
                                                  onClick={() => updatePlayerField(p.id, "personality", key)}
                                                  className={`py-0.5 rounded text-[7px] font-bold cursor-pointer transition-all uppercase tracking-tight ${
                                                    p.personality === key
                                                      ? "bg-amber-500/20 text-amber-400 border border-amber-500/40"
                                                      : "bg-slate-900 text-stone-500 border border-white/5 hover:text-stone-300 hover:bg-slate-850"
                                                  }`}
                                                  title={profile.blurb}
                                                >
                                                  {profile.displayName.split(" ")[0]}
                                                </button>
                                              );
                                            })}
                                          </div>
                                        </div>
                                      </>
                                    )}
                                  </div>
                                );
                              })}
                            </div>
                          </div>
                        </div>

                        {/* START ACTION BAR */}
                        <div className="flex flex-col sm:flex-row items-center justify-between gap-4 pt-3 border-t border-white/5">
                          <div className="text-[10px] text-stone-500 font-mono text-center sm:text-left">
                            ⚡ Estemshan Engine fully wired. Click deal to begin Round 1/18.
                          </div>
                          
                          <motion.button
                            initial={{ scale: 0.96 }}
                            animate={{ scale: [0.98, 1.02, 0.98] }}
                            transition={{
                              repeat: Infinity,
                              duration: 2,
                              ease: "easeInOut",
                            }}
                            onClick={dealCards}
                            className="bg-gradient-to-r from-amber-500 via-yellow-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-stone-950 font-black px-8 py-3 rounded-xl shadow-lg tracking-widest text-xs uppercase cursor-pointer border border-amber-300 hover:border-white transition-all active:scale-95 flex items-center gap-2"
                          >
                            <span>🎴 START MATCH & DEAL</span>
                          </motion.button>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* INFO BUTTON FOR HOW TO PLAY SCREEN (Item 16 in Labeled Diagram, bottom-right) */}
                  <button
                    onClick={() => setShowHowToPlayModal(true)}
                    className="absolute bottom-4 right-4 z-25 p-1.5 rounded-full bg-black/60 border border-white/15 hover:border-amber-400 text-stone-200 hover:text-white hover:scale-110 active:scale-95 shadow-md flex items-center justify-center w-8 h-8 text-xs font-black cursor-pointer select-none"
                    title="How to Play Diagram Help"
                  >
                    ⓘ
                  </button>

                  {/* COMPACT PLAYER HAND IN WIDE HORIZONTAL BOTTOM ZONE */}
                  <div className="absolute bottom-3 left-1/2 -translate-x-1/2 z-50 w-full max-w-2xl px-6 pointer-events-auto">
                    <div className="flex -space-x-7 justify-center items-end min-h-[135px] bg-[#0c0d12]/60 backdrop-blur-md px-8 py-2 rounded-2xl border border-white/5 shadow-[0_-8px_30px_rgba(0,0,0,0.65)]">
                      {sortHand(players[0].hand).map((card, cIndex) => {
                        const myTurn =
                          phase === "PLAYING" &&
                          activePlayerIdx === 0 &&
                          currentTrick.length < 4;
                        const allowed = isPlayLegal(
                          card,
                          players[0].hand,
                          currentTrick,
                        );
                        return (
                          <div
                            key={card.id}
                            className="relative transition-transform duration-300 hover:z-40 hover:-translate-y-6"
                          >
                            <Card
                              card={card}
                              disabled={!myTurn || !allowed}
                              onClick={() => {
                                if (!myTurn) return;
                                if (!allowed) {
                                  pushLog(
                                    `⚠️ Follow Suit! You must play ${derivedLedSuit} if available.`,
                                  );
                                  return;
                                }
                                playCard("p1", card);
                              }}
                            />
                          </div>
                        );
                      })}
                      {players[0].hand.length === 0 && (
                        <div className="text-[10px] text-zinc-500 font-bold uppercase tracking-widest py-8 font-mono">
                          No Cards in Hand
                        </div>
                      )}
                    </div>
                  </div>
                </>
              )}

              {/* POPUPS & SCREEN MODAL OVERLAYS */}
              <AnimatePresence>
                {/* PREVIOUS COMPLETED TRICK VIEWER POPUP OVERLAY */}
                {showLastTrickModal && (
                  <div className="absolute top-20 right-6 z-[60] bg-slate-950/95 border-2 border-amber-500/30 rounded-2xl p-4 shadow-2xl w-60">
                    <div className="flex justify-between items-center mb-2.5 border-b border-white/5 pb-1.5 select-none">
                      <span className="text-[10px] font-black text-amber-500 tracking-wider">
                        PREVIOUS COMPLETED TRICK
                      </span>
                      <button
                        onClick={() => setShowLastTrickModal(false)}
                        className="text-xs hover:text-red-500 text-stone-400 font-bold"
                      >
                        ✕
                      </button>
                    </div>
                    {lastCompletedTrick ? (
                      <div className="flex gap-2 justify-center py-2.5">
                        {lastCompletedTrick.map((played, idx) => {
                          const pName =
                            players.find((p) => p.id === played.playerId)
                              ?.name || played.playerName;
                          return (
                            <div
                              key={idx}
                              className="flex flex-col items-center gap-1"
                            >
                              <span className="text-[8px] text-zinc-400 font-mono truncate max-w-[52px] select-none">
                                {pName}
                              </span>
                              <Card card={played.card} size="small" disabled />
                            </div>
                          );
                        })}
                      </div>
                    ) : (
                      <div className="text-stone-405 text-[10px] italic py-5 text-center select-none font-mono">
                        No tricks completed in this round yet.
                      </div>
                    )}
                  </div>
                )}

                {/* IN-GAME EXPLANARY METADATA HOW-TO-PLAY MODAL (Exactly styled as Image 2) */}
                {showHowToPlayModal && (
                  <div className="absolute inset-0 bg-[#0F1D32] bg-[radial-gradient(#1E3555_1.5px,transparent_1.5px)] [background-size:24px_24px] z-[100] flex items-center justify-center p-3 select-none">
                    <div className="bg-slate-950 border-[5px] border-[#DFC5A9]/25 p-5 md:p-6 rounded-[2.5rem] w-full max-w-4xl h-[95%] overflow-y-auto shadow-2xl relative select-none flex flex-col justify-between">
                      {/* Top Bar Close button and tab switcher */}
                      <div className="flex justify-between items-center border-b border-white/5 pb-3">
                        <div className="flex gap-2">
                          <button
                            onClick={() => setHelpPage("ui")}
                            className={`px-4 py-1.5 rounded-full text-[10px] font-black uppercase tracking-wider transition-all select-none cursor-pointer ${
                              helpPage === "ui"
                                ? "bg-[#C22F2F] text-white shadow-md"
                                : "bg-slate-900 text-stone-400 hover:text-white"
                            }`}
                          >
                            GAMEPLAY UI MAP
                          </button>
                          <button
                            onClick={() => setHelpPage("rules")}
                            className={`px-4 py-1.5 rounded-full text-[10px] font-black uppercase tracking-wider transition-all select-none cursor-pointer ${
                              helpPage === "rules"
                                ? "bg-[#C22F2F] text-white shadow-md"
                                : "bg-slate-900 text-stone-400 hover:text-white"
                            }`}
                          >
                            ESTEMSHAN RULES
                          </button>
                        </div>

                        <div className="text-center">
                          <h2 className="text-base md:text-xl font-black text-amber-500 tracking-widest font-sans uppercase">
                            HOW TO PLAY
                          </h2>
                          <p className="text-[8px] text-slate-400 uppercase tracking-widest font-bold">
                            GAMEPLAY INTERACTIVE SCHEMATIC
                          </p>
                        </div>

                        <button
                          onClick={() => setShowHowToPlayModal(false)}
                          className="w-8 h-8 rounded-full bg-slate-900 hover:bg-[#C22F2F] text-stone-400 hover:text-white transition-colors flex items-center justify-center text-sm font-black cursor-pointer"
                        >
                          ✕
                        </button>
                      </div>

                      {helpPage === "ui" ? (
                        <div className="grid grid-cols-12 gap-4 my-4 flex-1 items-stretch">
                          {/* Left list (Items 1 to 8) */}
                          <div className="col-span-12 md:col-span-3 space-y-2.5 flex flex-col justify-around">
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ① Player-1 (You):
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Sheikh avatar named "Barbryy", defaults with
                                high rank status.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ② Player-2 (Bot-1):
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Pink heart-eyed cyber bot with a reactive neon
                                halo frame.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ③ Player-3 (Bot-2):
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Metallic can bot positioned near table top
                                center.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ④ Player-4 (Bot-3):
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Pharaoh-inspired gold cyborg above bottom hand.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑤ Player Score:
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Cumulative game scores recorded under each
                                player.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑥ Player Rank Level:
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Individual player levels shown inside each mini
                                headcard.
                              </p>
                            </div>
                          </div>

                          {/* Middle tabletop schematic mockup */}
                          <div className="col-span-12 md:col-span-6 bg-slate-950 p-4.5 rounded-[2rem] border-2 border-stone-850 flex flex-col justify-center items-center relative min-h-[300px]">
                            <div className="absolute inset-0 flex items-center justify-center opacity-10 pointer-events-none select-none text-[#DFC5A9]">
                              <span className="text-8xl">🎴</span>
                            </div>

                            {/* Title of diagram */}
                            <span className="text-[10px] font-black tracking-widest text-[#DFC5A9]/50 uppercase mb-4">
                              Board Placement Outline
                            </span>

                            {/* Traditional styled mini tabletop with tags mock markup */}
                            <div className="w-full max-w-sm h-52 bg-[#DFC5A9]/20 border-4 border-stone-800 rounded-3xl relative flex items-center justify-center">
                              {/* Compass indicator */}
                              <div className="w-24 h-24 rounded-full border-2 border-[#92400E]/20 flex items-center justify-center opacity-50">
                                <span className="text-xs font-black text-[#DFC5A9]/45">
                                  ✦
                                </span>
                              </div>

                              {/* Labeled player markers as clickable hotspots or simple bullet arrows referencing list items */}
                              <div className="absolute top-2 left-4 px-2 py-1 bg-stone-900 rounded-lg text-[8px] font-black border border-[#DFC5A9]/20 flex items-center gap-1">
                                <span className="w-3 h-3 bg-rose-500 rounded-full flex items-center justify-center text-white text-[7px]">
                                  1
                                </span>
                                <span>You (باشا)</span>
                              </div>

                              <div className="absolute top-2 right-4 px-2 py-1 bg-stone-900 rounded-lg text-[8px] font-black border border-[#DFC5A9]/20 flex items-center gap-1">
                                <span className="w-3 h-3 bg-rose-500 rounded-full flex items-center justify-center text-white text-[7px]">
                                  3
                                </span>
                                <span>Amir (حريف)</span>
                              </div>

                              <div className="absolute bottom-2 left-4 px-2 py-1 bg-stone-900 rounded-lg text-[8px] font-black border border-[#DFC5A9]/20 flex items-center gap-1">
                                <span className="w-3 h-3 bg-rose-500 rounded-full flex items-center justify-center text-white text-[7px]">
                                  4
                                </span>
                                <span>Fatima (جدع)</span>
                              </div>

                              <div className="absolute top-1/2 -translate-y-1/2 right-2 px-2 py-1 bg-stone-900 rounded-lg text-[8px] font-black border border-[#DFC5A9]/20 flex items-center gap-1">
                                <span className="w-3 h-3 bg-rose-500 rounded-full flex items-center justify-center text-white text-[7px]">
                                  2
                                </span>
                                <span>Omar (الأسطورة)</span>
                              </div>

                              {/* Top-Right heap stack indicator 9 */}
                              <div
                                className="absolute top-2 right-12 scale-[0.75] bg-[#C22F2F] w-4.5 h-4.5 rounded-full flex items-center justify-center text-[8px] font-black text-white shadow-lg animate-bounce"
                                title="Last Trick Stack (9)"
                              >
                                9
                              </div>

                              {/* Top-Left scorecard 10 */}
                              <div
                                className="absolute top-4 left-16 scale-[0.75] bg-[#C22F2F] w-4.5 h-4.5 rounded-full flex items-center justify-center text-[8px] font-black text-white shadow-lg"
                                title="Round Status (10)"
                              >
                                10
                              </div>

                              {/* Center suit wheel 15 */}
                              <div
                                className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 bg-[#C22F2F] w-4.5 h-4.5 rounded-full flex items-center justify-center text-[8px] font-black text-white shadow-lg"
                                title="Center Suit indicator (15)"
                              >
                                15
                              </div>

                              {/* bottom left buttons 12, 13 */}
                              <div className="absolute bottom-2 left-16 flex gap-1 scale-[0.75]">
                                <div className="bg-[#C22F2F] w-4.5 h-4.5 rounded-full flex items-center justify-center text-[7px] font-black text-white">
                                  12
                                </div>
                                <div className="bg-[#C22F2F] w-4.5 h-4.5 rounded-full flex items-center justify-center text-[7px] font-black text-white">
                                  13
                                </div>
                              </div>
                            </div>

                            {/* Legend summary banner */}
                            <div className="mt-4 bg-slate-900/60 p-2 rounded-xl text-[8.5px] text-zinc-400 text-center w-full max-w-sm border border-slate-800">
                              Red badges{" "}
                              <span className="bg-rose-500 text-white px-1 py-0.2 rounded font-mono text-[7.5px]">
                                1-16
                              </span>{" "}
                              correspond to the interface objects listed around
                              this diagram.
                            </div>
                          </div>

                          {/* Right list (Items 9 to 16) */}
                          <div className="col-span-12 md:col-span-3 space-y-2.5 flex flex-col justify-around">
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑨ Last Trick:
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Displays the 4 cards of the previous trick in a
                                miniature real-time cross.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑩ Round Status (Delta):
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Tracks active score delta (differential) and
                                math values for this round.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑪ Round Trump:
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Displays chosen Trump card suit. Shows yellow
                                Sun symbol for Sanz rounds.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑫ Settings Gear:
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Red square button next to Bot 3 which allows
                                resetting the simulator match.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑬ Chat Speak Drawer:
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Red drawer capsule next to settings to fire
                                Arabic film quote speech bubbles.
                              </p>
                            </div>
                            <div className="p-2 bg-slate-900/60 rounded-xl border border-slate-800">
                              <span className="text-[11px] font-black text-rose-400">
                                ⑮ Center Table Wheel:
                              </span>
                              <p className="text-[9px] text-zinc-300">
                                Shows suit symbols beneath played cards where
                                active tricks sit.
                              </p>
                            </div>
                          </div>
                        </div>
                      ) : (
                        <div className="my-5 flex-1 select-none space-y-4 max-w-2xl mx-auto text-left py-4">
                          <div className="border-l-4 border-amber-500 pl-3">
                            <span className="text-stone-105 font-black text-xs uppercase select-none">
                              Bidding Phase (Call Decision)
                            </span>
                            <p className="text-[11px] text-zinc-300 leading-relaxed mt-0.5">
                              Every match starts with the bidding round. Players
                              inspect their cards and submit a bid estimate. The
                              highest bidder gets to declare the Round Trump
                              color. You can choose any of the 4 suits, OR
                              choose "Sanz" (S/☀️) for no-trump gameplay.
                            </p>
                          </div>

                          <div className="border-l-4 border-amber-500 pl-3">
                            <span className="text-stone-105 font-black text-xs uppercase select-none">
                              Tricks and Gameplay (Following Suits)
                            </span>
                            <p className="text-[11px] text-zinc-300 leading-relaxed mt-0.5">
                              During play, the active actor lays a card.
                              Opponents must follow suit if they can. If they
                              cannot follow suit, they can either bleed a
                              generic color or play a Trump card to hijack/win
                              the trick.
                            </p>
                          </div>

                          <div className="border-l-4 border-[#C22F2F] pl-3">
                            <span className="text-stone-105 font-black text-xs uppercase select-none">
                              Special Hand Rules: Dash Call (With exactly zero
                              tricks)
                            </span>
                            <p className="text-[11px] text-zinc-300 leading-relaxed mt-0.5">
                              By calling Dash Call, you bid to win absolutely
                              zero tricks. If succeeded, your team scores a
                              major premium payout; if you mistakenly win even
                              one trick, you take a heavy penalty!
                            </p>
                          </div>

                          <div className="border-l-4 border-amber-500 pl-3">
                            <span className="text-stone-105 font-black text-xs uppercase select-none">
                              Score Arithmetic (King & Kooz tags)
                            </span>
                            <p className="text-[11px] text-zinc-300 leading-relaxed mt-0.5">
                              If players secure EXACTLY the amount of tricks
                              they bid, they score positive points. The player
                              with the highest total score is crowned 👑 King,
                              while the player in last place wears the 🧲 Kooz
                              tag!
                            </p>
                          </div>
                        </div>
                      )}

                      {/* Footnote */}
                      <div className="bg-[#DFC5A9]/10 border border-[#DFC5A9]/15 text-[#DFC5A9]/80 p-3 rounded-2xl text-[9px] font-black text-center uppercase tracking-wider select-none leading-relaxed mt-1 flex items-center justify-center gap-1.5">
                        <span>📢 KING & KOOZ LABELS:</span>
                        <span className="text-white bg-amber-500 text-slate-950 px-1 rounded">
                          [C] Caller Badge:
                        </span>
                        <span>Indicates bidder who chooses Trump.</span>
                        <span className="text-white bg-[#C22F2F] px-1 rounded font-normal">
                          👑 King: Match Leader.
                        </span>
                        <span className="text-white bg-[#C22F2F] px-1 rounded font-normal">
                          🧲 Kooz: Last place score holder.
                        </span>
                      </div>
                    </div>
                  </div>
                )}

                {/* STANDING CUMULATIVE SCORE LEDGER MODAL */}
                {showLedgerModal && (
                  <div className="absolute inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
                    <div className="bg-slate-900 border-2 border-[#DFC5A9]/20 p-5 rounded-[2rem] w-full max-w-sm shadow-2xl select-none">
                      <div className="flex justify-between items-center mb-3 border-b border-white/5 pb-2">
                        <h3 className="text-sm font-black text-amber-500 tracking-wider">
                          GAME SCORES LEDGER
                        </h3>
                        <button
                          onClick={() => setShowLedgerModal(false)}
                          className="text-stone-400 hover:text-red-500 transition-colors text-base font-bold"
                        >
                          ✕
                        </button>
                      </div>

                      <div className="space-y-2 mb-5">
                        {[...players]
                          .sort((a, b) => b.score - a.score)
                          .map((p, idx) => {
                            const playerScores = players.map((pl) => pl.score);
                            const maxScore = Math.max(...playerScores);
                            const isK = idx === 0 && maxScore > 0;
                            return (
                              <div
                                key={p.id}
                                className="flex items-center justify-between p-2.5 bg-slate-950 border border-slate-800 rounded-xl"
                              >
                                <div className="flex items-center gap-2">
                                  <span className="text-slate-500 font-mono text-[10px] font-black">
                                    #{idx + 1}
                                  </span>
                                  <span className="text-xs font-bold text-slate-100 flex items-center gap-1.5">
                                    {p.name}
                                    {isK && "👑"}
                                    {p.isUser && (
                                      <span className="text-[8px] bg-sky-500/10 text-sky-400 px-1 py-0.2 rounded font-black">
                                        YOU
                                      </span>
                                    )}
                                  </span>
                                </div>
                                <span className="text-xs font-black text-emerald-400 font-mono">
                                  {p.score} pts
                                </span>
                              </div>
                            );
                          })}
                      </div>

                      <div className="text-center text-[9px] text-[#A7F3D0] font-black">
                        ROUND {round} STATUS • MULTIPLIER x{multiplier}
                      </div>

                      <button
                        onClick={() => setShowLedgerModal(false)}
                        className="w-full mt-4 bg-amber-600 hover:bg-amber-500 text-black font-black uppercase tracking-widest text-[9px] py-3.5 rounded-xl transition-all cursor-pointer"
                      >
                        CLOSE LEDGER VIEW
                      </button>
                    </div>
                  </div>
                )}

                {phase === "FOLLOWING_BIDS" &&
                  biddingTurnIdx === 0 &&
                  (() => {
                    const otherBids = players
                      .filter((p, i) => i !== 0 && p.bid !== -1)
                      .map((p) => p.bid);
                    const isUserLastBidder =
                      players.filter((p) => p.bid === -1).length === 1;

                    const userCeil = highestBidTricks;

                    // Ensure initial selected bid is within limits
                    if (
                      selectedBidTricks !== null &&
                      (selectedBidTricks < 0 || selectedBidTricks > userCeil)
                    ) {
                      setTimeout(() => setSelectedBidTricks(0), 0);
                    }

                    return (
                      <motion.div
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        className="absolute inset-0 bg-black/15 z-40 flex items-center justify-center animate-fade-in pointer-events-auto rounded-[3.8rem]"
                      >
                        <motion.div
                          initial={{ scale: 0.9, y: 20 }}
                          animate={{ scale: 1, y: 0 }}
                          exit={{ scale: 0.9, y: 20 }}
                          className="bg-[#0b130e]/95 border-[3px] border-amber-500/70 p-3 rounded-[1.5rem] w-[290px] shadow-[0_15px_45px_rgba(0,0,0,0.9)] flex flex-col gap-2.5 select-none text-center"
                        >
                          <div>
                            <h3 className="text-xs font-black text-amber-500 uppercase tracking-widest">
                              ESTIMATE TRICKS
                            </h3>
                            <div className="text-[9px] text-zinc-400 mt-0.5 uppercase flex gap-3 justify-center animate-fade-in">
                              <span>
                                Trump:{" "}
                                <span className="text-emerald-500 font-black">
                                  {effectiveTrumpSuit === "NONE"
                                    ? "SANS"
                                    : effectiveTrumpSuit}
                                </span>
                              </span>
                              <span>
                                Caller:{" "}
                                <span className="text-amber-500 font-bold">
                                  {highestBidTricks}
                                </span>
                              </span>
                            </div>
                          </div>

                          <div className="bg-[#1e293b]/50 border border-white/5 rounded-xl px-2 py-1.5 text-center text-[9px] text-zinc-400 font-extrabold uppercase tracking-wider animate-fade-in">
                            🔒 CALLER BID CAP: MAX {highestBidTricks} TRICKS
                            ALLOWED
                          </div>

                          {/* Row 1 & 2 (Tricks Selection Grid) */}
                          <div className="flex flex-col gap-1 text-left">
                            <div className="grid grid-cols-7 gap-1">
                              {[0, 1, 2, 3, 4, 5, 6].map((n) => {
                                const { valid, error } = validateBidding(
                                  n,
                                  0,
                                  otherBids,
                                  "FOLLOWING_BIDS",
                                  highestBidTricks,
                                  undefined,
                                  undefined,
                                  players[0]?.isDashCall,
                                  false,
                                  round >= 14,
                                );
                                const isBtnDisabled = !valid;
                                const isSelected = selectedBidTricks === n;
                                const titleText = error;
                                return (
                                  <button
                                    key={n}
                                    disabled={isBtnDisabled}
                                    title={titleText}
                                    onClick={() => setSelectedBidTricks(n)}
                                    className={`h-8 rounded-lg font-black text-xs transition-all cursor-pointer ${
                                      isSelected
                                        ? "bg-amber-500 text-black shadow-lg shadow-amber-500/20"
                                        : !isBtnDisabled
                                          ? "bg-zinc-900 text-zinc-350 hover:bg-zinc-800 border border-white/5"
                                          : "bg-rose-955/20 text-rose-700/50 cursor-not-allowed border border-rose-900/10 opacity-30"
                                    }`}
                                  >
                                    {n > highestBidTricks ? "🔒" : n}
                                  </button>
                                );
                              })}
                            </div>
                            <div className="grid grid-cols-7 gap-1 mt-0.5">
                              {[7, 8, 9, 10, 11, 12, 13].map((n) => {
                                const { valid, error } = validateBidding(
                                  n,
                                  0,
                                  otherBids,
                                  "FOLLOWING_BIDS",
                                  highestBidTricks,
                                  undefined,
                                  undefined,
                                  players[0]?.isDashCall,
                                  false,
                                  round >= 14,
                                );
                                const isBtnDisabled = !valid;
                                const isSelected = selectedBidTricks === n;
                                const titleText = error;
                                return (
                                  <button
                                    key={n}
                                    disabled={isBtnDisabled}
                                    title={titleText}
                                    onClick={() => setSelectedBidTricks(n)}
                                    className={`h-8 rounded-lg font-black text-xs transition-all cursor-pointer ${
                                      isSelected
                                        ? "bg-amber-500 text-black shadow-lg shadow-amber-500/20"
                                        : !isBtnDisabled
                                          ? "bg-zinc-900 text-zinc-350 hover:bg-zinc-800 border border-white/5"
                                          : "bg-rose-955/20 text-rose-700/50 cursor-not-allowed border border-rose-900/10 opacity-30"
                                    }`}
                                  >
                                    {n > highestBidTricks ? "🔒" : n}
                                  </button>
                                );
                              })}
                            </div>
                          </div>

                          {/* Row 3 (Suit Selection Row for Estimates) */}
                          <div className="flex flex-col gap-1 text-left my-1">
                            <div className="flex justify-between items-center px-0.5">
                              {[
                                {
                                  suit: "NONE",
                                  label: "SANS",
                                  char: "☀",
                                  color: "text-yellow-500",
                                },
                                {
                                  suit: "SPADES",
                                  label: "Spades",
                                  char: "♠",
                                  color: "text-zinc-200",
                                },
                                {
                                  suit: "HEARTS",
                                  label: "Hearts",
                                  char: "♥",
                                  color: "text-red-500",
                                },
                                {
                                  suit: "DIAMONDS",
                                  label: "Diamonds",
                                  char: "♦",
                                  color: "text-red-500",
                                },
                                {
                                  suit: "CLUBS",
                                  label: "Clubs",
                                  char: "♣",
                                  color: "text-emerald-500",
                                },
                              ].map((t) => {
                                const isSelected =
                                  selectedEstimateSuit === t.suit;
                                return (
                                  <button
                                    key={t.suit}
                                    onClick={() =>
                                      setSelectedEstimateSuit(
                                        t.suit as Suit | "NONE",
                                      )
                                    }
                                    className={`w-11 h-11 rounded-full flex flex-col items-center justify-center transition-all cursor-pointer ${
                                      isSelected
                                        ? "border-2 border-amber-500 bg-amber-500/15 shadow-md shadow-amber-500/10"
                                        : "border border-white/5 hover:border-white/10 bg-zinc-900"
                                    }`}
                                  >
                                    <span
                                      className={`text-base leading-none ${t.color}`}
                                    >
                                      {t.char}
                                    </span>
                                    <span className="text-[6.5px] font-black tracking-tighter uppercase mt-0.5 text-zinc-455">
                                      {t.suit === "NONE"
                                        ? "SANS"
                                        : t.suit.slice(0, 3)}
                                    </span>
                                  </button>
                                );
                              })}
                            </div>
                          </div>

                          {/* Row 4 (Action confirmations & error indicators) */}
                          {(() => {
                            if (selectedBidTricks === null) {
                              return (
                                <div className="flex flex-col gap-1 px-1">
                                  <button
                                    disabled={true}
                                    className="w-full py-2.5 rounded-xl font-black text-xs tracking-wider uppercase transition-all bg-zinc-900 text-zinc-650 border border-white/5 cursor-not-allowed opacity-30 shadow-none mt-1"
                                  >
                                    SELECT AN ESTIMATE
                                  </button>
                                </div>
                              );
                            }
                            const { valid, error } = validateBidding(
                              selectedBidTricks,
                              0,
                              otherBids,
                              "FOLLOWING_BIDS",
                              highestBidTricks,
                              undefined,
                              undefined,
                              players[0]?.isDashCall,
                              false,
                              round >= 14,
                            );
                            const isProceedValid = valid;
                            return (
                              <div className="flex flex-col gap-1 px-1">
                                {isUserLastBidder && (
                                  <div className="text-[8px] text-amber-400 font-bold bg-amber-500/10 border border-amber-500/20 py-1 rounded-xl uppercase tracking-wider text-center select-none animate-fade-in">
                                    ⚠️ Sum of bids can't equal 13!
                                  </div>
                                )}
                                {!valid && (
                                  <div className="text-[8px] text-rose-400 font-bold bg-rose-500/10 border border-rose-500/20 py-1 rounded-xl uppercase tracking-wider text-center select-none animate-fade-in">
                                    ⚠️ ERROR: {error}
                                  </div>
                                )}
                                <button
                                  disabled={!isProceedValid}
                                  onClick={() => {
                                    if (selectedBidTricks !== null) {
                                      submitEstimate(
                                        "p1",
                                        selectedBidTricks,
                                        selectedEstimateSuit,
                                      );
                                    }
                                  }}
                                  className={`w-full py-2.5 rounded-xl font-black text-xs tracking-wider uppercase transition-all active:scale-95 shadow-md cursor-pointer mt-1 ${
                                    isProceedValid
                                      ? "bg-[#EF4444] hover:bg-red-500 text-white shadow-lg"
                                      : "bg-zinc-900 text-zinc-650 border border-white/5 cursor-not-allowed opacity-30 shadow-none"
                                  }`}
                                >
                                  ESTIMATE {selectedBidTricks} TRICKS
                                </button>
                              </div>
                            );
                          })()}
                        </motion.div>
                      </motion.div>
                    );
                  })()}

                {phase === "ROUND_OVER" &&
                  roundResults &&
                  (() => {
                    const totalEst = roundResults.reduce(
                      (s, r) => s + (r.bid === -1 ? 0 : r.bid),
                      0,
                    );
                    const isOver = totalEst >= 14;
                    return (
                      <motion.div
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        className="absolute inset-0 bg-black/90 backdrop-blur-lg z-40 flex items-center justify-center p-4 overflow-y-auto"
                      >
                        <motion.div
                          initial={{ scale: 0.9, y: 30 }}
                          animate={{ scale: 1, y: 0 }}
                          exit={{ scale: 0.9, y: 30 }}
                          className="bg-[#0b130e] border-[3px] border-zinc-800 p-6 md:p-8 rounded-[3rem] w-full max-w-[560px] shadow-[0_0_100px_rgba(0,0,0,0.95)] flex flex-col gap-6 my-auto"
                        >
                          {/* Header */}
                          <div className="text-center">
                            <div className="flex items-center justify-center gap-2">
                              <Trophy className="text-amber-400 w-5 h-5 animate-bounce" />
                              <h3 className="text-lg font-black tracking-widest text-zinc-400 uppercase">
                                ROUND {round} RESULTS
                              </h3>
                            </div>

                            <div className="mt-3 flex items-center justify-center gap-3">
                              <span className="text-[10px] font-black uppercase px-3 py-1 rounded-full bg-zinc-800 border border-white/5 text-zinc-350">
                                Multiplier: x{multiplier}
                              </span>
                              {isOver ? (
                                <span className="text-[10px] font-black uppercase px-3 py-1 rounded-full bg-amber-500/10 border border-amber-500/30 text-amber-400">
                                  +OVER ROUND ({totalEst} tricks)
                                </span>
                              ) : (
                                <span className="text-[10px] font-black uppercase px-3 py-1 rounded-full bg-cyan-500/10 border border-cyan-500/30 text-cyan-400">
                                  -UNDER ROUND ({totalEst} tricks)
                                </span>
                              )}
                            </div>
                          </div>

                          {/* Results Grid */}
                          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-h-[280px] overflow-y-auto pr-1">
                            {roundResults.map((r) => (
                              <div
                                key={r.id}
                                className={`p-4 rounded-2xl border flex flex-col gap-2 relative overflow-hidden transition-all ${
                                  r.isSuccess
                                    ? "bg-emerald-950/20 border-emerald-900/30 shadow-[0_4px_12px_rgba(16,185,129,0.05)]"
                                    : "bg-rose-950/10 border-rose-900/20"
                                }`}
                              >
                                <div className="flex items-center justify-between">
                                  <span className="text-xs font-black text-white truncate max-w-[120px]">
                                    {r.name}
                                  </span>
                                  {r.isSuccess ? (
                                    <span className="text-[8px] font-extrabold uppercase bg-emerald-500 text-black px-1.5 py-0.5 rounded">
                                      SUCCESS
                                    </span>
                                  ) : (
                                    <span className="text-[8px] font-extrabold uppercase bg-rose-600 text-white px-1.5 py-0.5 rounded">
                                      FAILED
                                    </span>
                                  )}
                                </div>

                                {/* Role Badges */}
                                <div className="flex flex-wrap gap-1">
                                  {r.isCaller && (
                                    <span className="text-[8px] font-black uppercase bg-amber-400 text-black px-1.5 py-0.5 rounded">
                                      👑 THE CALLER
                                    </span>
                                  )}
                                  {r.isWith && (
                                    <span className="text-[8px] font-black uppercase bg-yellow-500 text-black px-1.5 py-0.5 rounded">
                                      ⌚ WITH
                                    </span>
                                  )}
                                  {r.isDashCall && (
                                    <span className="text-[8px] font-black uppercase bg-purple-600 text-white px-1.5 py-0.5 rounded">
                                      ⚡ DASH CALL
                                    </span>
                                  )}
                                  {r.isLast && (
                                    <span className="text-[7px] font-mono uppercase bg-zinc-800 text-zinc-400 px-1.5 py-0.5 rounded">
                                      LAST BIDDER
                                    </span>
                                  )}
                                </div>

                                <div className="flex justify-between items-center text-[10px] text-zinc-400 border-t border-white/5 pt-2 mt-1">
                                  <div>
                                    <span>Bid: </span>
                                    <span className="text-white font-bold">
                                      {r.bid}
                                    </span>
                                  </div>
                                  <div>
                                    <span>Won: </span>
                                    <span className="text-white font-bold">
                                      {r.tricksWon}
                                    </span>
                                  </div>
                                </div>

                                {/* Point breakdown if they have any of the special modifiers */}
                                {(r.isWith ||
                                  r.isCaller ||
                                  (r.isLast && r.riskApplied > 0)) && (
                                  <div className="text-[8.5px] bg-black/40 p-2 rounded-xl border border-white/5 space-y-0.5 text-zinc-400 font-mono">
                                    {(r.isCaller || r.isWith) && (
                                      <div className="flex justify-between">
                                        <span>
                                          {r.isCaller
                                            ? "Caller Bonus/Deduction"
                                            : "With Bonus/Deduction"}
                                          :
                                        </span>
                                        <span
                                          className={
                                            r.isSuccess
                                              ? "text-emerald-400 font-semibold"
                                              : "text-rose-400 font-semibold"
                                          }
                                        >
                                          {r.isSuccess
                                            ? `+${10 * r.multiplier}`
                                            : `-${10 * r.multiplier}`}{" "}
                                          PTS
                                        </span>
                                      </div>
                                    )}
                                    {r.isLast && r.riskApplied > 0 && (
                                      <div className="flex justify-between">
                                        <span>
                                          Risk (
                                          {totalEst > 13 ? "Over" : "Under"}):
                                        </span>
                                        <span
                                          className={
                                            r.isSuccess
                                              ? "text-emerald-400 font-semibold"
                                              : "text-rose-400 font-semibold"
                                          }
                                        >
                                          {r.isSuccess
                                            ? `+${r.riskApplied * r.multiplier}`
                                            : `-${r.riskApplied * r.multiplier}`}{" "}
                                          PTS
                                        </span>
                                      </div>
                                    )}
                                  </div>
                                )}

                                <div className="flex justify-between items-center text-[10px] mt-1">
                                  <span
                                    className={`font-black ${r.finalScore >= 0 ? "text-emerald-400" : "text-rose-500"}`}
                                  >
                                    {r.finalScore >= 0
                                      ? `+${r.finalScore}`
                                      : r.finalScore}{" "}
                                    PTS
                                  </span>
                                  <span className="text-[9px] text-zinc-500 font-mono">
                                    Total: {r.newCumulativeScore}
                                  </span>
                                </div>
                              </div>
                            ))}
                          </div>

                          {/* Button */}
                          <button
                            onClick={proceedToNextRound}
                            className="w-full bg-gradient-to-r from-emerald-500 to-teal-500 text-black font-black uppercase py-4 rounded-2xl tracking-widest text-xs hover:brightness-110 active:scale-95 transition-all cursor-pointer shadow-lg shadow-emerald-900/20 animate-pulse"
                          >
                            {round >= maxRounds
                              ? "FINISH GAME & VERIFY CHAMPION"
                              : `PROCEED TO ROUND ${round + 1}`}
                          </button>
                        </motion.div>
                      </motion.div>
                    );
                  })()}

                {phase === "GAME_OVER" &&
                  (() => {
                    const sortedStandings = [...players].sort(
                      (a, b) => b.score - a.score,
                    );
                    const champ = sortedStandings[0];
                    const roundStatsList = log
                      .map((entry) => entry.stats)
                      .filter((s): s is RoundStat => !!s);

                    // Calculate accuracy: success estimate count / active estimate count
                    const accuracyData = players.map((p) => {
                      const playerRounds = roundStatsList
                        .map((rs) =>
                          rs.playerStats.find((ps) => ps.id === p.id),
                        )
                        .filter(Boolean);
                      const successCount = playerRounds.filter(
                        (r) => r?.isSuccess,
                      ).length;
                      const accuracy =
                        playerRounds.length > 0
                          ? (successCount / playerRounds.length) * 100
                          : 0;
                      return {
                        id: p.id,
                        name: p.name,
                        accuracy,
                        successCount,
                        total: playerRounds.length,
                      };
                    });
                    const sortedAccuracy = [...accuracyData].sort(
                      (a, b) => b.accuracy - a.accuracy,
                    );
                    const bestAccuracy = sortedAccuracy[0];

                    return (
                      <motion.div
                        initial={{ opacity: 0 }}
                        animate={{ opacity: 1 }}
                        exit={{ opacity: 0 }}
                        className="absolute inset-0 bg-black/95 backdrop-blur-xl z-50 flex items-center justify-center p-4 overflow-y-auto"
                      >
                        <motion.div
                          initial={{ scale: 0.9, y: 30 }}
                          animate={{ scale: 1, y: 0 }}
                          exit={{ scale: 0.9, y: 30 }}
                          className="bg-[#0b130e] border-[4px] border-[#DFC5A9]/20 p-6 md:p-8 rounded-[3rem] w-full max-w-[950px] shadow-[0_0_120px_rgba(245,158,11,0.15)] flex flex-col gap-5"
                        >
                          <div className="flex flex-col items-center text-center">
                            <div className="relative mb-1">
                              <Trophy
                                size={40}
                                className="text-amber-500 animate-pulse fill-amber-500/10"
                              />
                              <Crown
                                size={20}
                                className="text-yellow-400 absolute -top-3 left-1/2 -translate-x-1/2 rotate-12"
                              />
                            </div>
                            <h3 className="text-xl md:text-2xl font-black text-white tracking-widest uppercase italic">
                              BOLA FINISHED!
                            </h3>
                            <p className="text-[10px] text-zinc-400 mt-0.5 uppercase tracking-wider">
                              After {maxRounds} intense rounds of Estemshan
                              Match Play
                            </p>
                          </div>

                          <div className="grid grid-cols-1 md:grid-cols-12 gap-6 w-full items-start">
                            {/* Left: Standing spotlight */}
                            <div className="md:col-span-4 flex flex-col gap-4 w-full">
                              <div className="bg-amber-500/10 border border-amber-500/30 p-4 rounded-2xl w-full text-center flex flex-col items-center gap-1 shadow-inner">
                                <span className="text-[9px] font-black text-amber-500 uppercase tracking-widest">
                                  🏆 GRAND CHAMPION 🏆
                                </span>
                                <span className="text-lg font-black text-white italic">
                                  {champ.name}
                                </span>
                                <span className="text-2xl font-extrabold text-amber-400 tracking-tight">
                                  {champ.score} PTS
                                </span>
                              </div>

                              <div className="w-full flex flex-col gap-2">
                                <span className="text-[9px] font-black tracking-wider text-zinc-500 uppercase pl-1">
                                  Final Standings
                                </span>
                                <div className="flex flex-col gap-1.5">
                                  {sortedStandings.map((p, idx) => (
                                    <div
                                      key={p.id}
                                      className={`flex items-center justify-between p-3 rounded-xl border ${
                                        idx === 0
                                          ? "bg-amber-500/5 border-amber-500/20 text-white"
                                          : "bg-zinc-900 border-white/5 text-zinc-300"
                                      }`}
                                    >
                                      <div className="flex items-center gap-2">
                                        <span className="font-mono text-[10px] font-black text-zinc-500 min-w-4">
                                          #{idx + 1}
                                        </span>
                                        <span className="text-xs font-black">
                                          {p.name} {idx === 0 && "👑"}
                                        </span>
                                      </div>
                                      <span className="text-xs font-mono font-black tabular-nums">
                                        {p.score} PTS
                                      </span>
                                    </div>
                                  ))}
                                </div>
                              </div>

                              <div className="bg-zinc-900/60 border border-white/5 p-3.5 rounded-xl">
                                <div className="text-[9px] font-black text-zinc-500 tracking-wider uppercase mb-1 pl-1">
                                  Bidding Precision
                                </div>
                                {bestAccuracy && (
                                  <div className="flex items-center justify-between">
                                    <span className="text-xs text-zinc-200 font-bold">
                                      {bestAccuracy.name}
                                    </span>
                                    <span className="text-xs text-amber-400 font-mono font-bold">
                                      {bestAccuracy.accuracy.toFixed(0)}%
                                      correctness
                                    </span>
                                  </div>
                                )}
                              </div>

                              <button
                                onClick={restartGame}
                                className="w-full bg-gradient-to-r from-amber-500 to-yellow-500 text-black font-black uppercase py-4 rounded-xl tracking-widest text-xs hover:brightness-110 active:scale-95 transition-all cursor-pointer shadow-lg shadow-amber-950/20"
                              >
                                PLAY AGAIN (NEW BOLA)
                              </button>
                            </div>

                            {/* Right: Scoreboard Log Table */}
                            <div className="md:col-span-8 flex flex-col gap-3 w-full">
                              <div className="flex items-center justify-between">
                                <span className="text-[10px] font-black text-zinc-400 tracking-wider uppercase">
                                  ROUND-BY-ROUND METADATA STATS
                                </span>
                                <div className="flex items-center gap-2">
                                  <span className="text-[10px] text-emerald-400 font-bold mr-1">
                                    {copiedText}
                                  </span>
                                  <button
                                    onClick={copyCSVToClipboard}
                                    className="px-2.5 py-1 text-[10px] font-black uppercase tracking-wider bg-zinc-800 hover:bg-zinc-700 text-zinc-300 rounded-lg flex items-center gap-1 border border-white/5 cursor-pointer active:scale-95 transition-all"
                                    title="Copy stats as Comma Separated Values CSV to clipboard"
                                  >
                                    <Copy size={10} /> CSV
                                  </button>
                                  <button
                                    onClick={copyTextReportToClipboard}
                                    className="px-2.5 py-1 text-[10px] font-black uppercase tracking-wider bg-zinc-800 hover:bg-zinc-700 text-zinc-300 rounded-lg flex items-center gap-1 border border-white/5 cursor-pointer active:scale-95 transition-all"
                                    title="Copy elegant plain text match scoreboard report"
                                  >
                                    <Copy size={10} /> REPORT
                                  </button>
                                </div>
                              </div>

                              <div className="overflow-x-auto w-full border border-white/5 rounded-2xl bg-black/40 max-h-[320px] overflow-y-auto">
                                <table className="w-full text-left border-collapse font-sans text-xs">
                                  <thead className="bg-zinc-900 border-b border-white/10 text-[9px] text-zinc-400 uppercase tracking-wider sticky top-0 z-10">
                                    <tr>
                                      <th className="py-2 px-3">Rnd</th>
                                      <th className="py-2 px-3">Trump</th>
                                      <th className="py-2 px-3">Mult</th>
                                      <th className="py-2 px-3 text-emerald-400">
                                        You
                                      </th>
                                      <th className="py-2 px-3 text-zinc-300">
                                        Fatima
                                      </th>
                                      <th className="py-2 px-3 text-zinc-300">
                                        Amir
                                      </th>
                                      <th className="py-2 px-3 text-zinc-300">
                                        Omar
                                      </th>
                                    </tr>
                                  </thead>
                                  <tbody className="divide-y divide-white/5 font-mono text-zinc-300">
                                    {roundStatsList.length === 0 ? (
                                      <tr>
                                        <td
                                          colSpan={7}
                                          className="py-12 text-center text-zinc-500 italic"
                                        >
                                          No rounded stats recorded. Play a full
                                          game to capture details!
                                        </td>
                                      </tr>
                                    ) : (
                                      roundStatsList.map((rs) => {
                                        const p1 = rs.playerStats.find(
                                          (ps) => ps.id === "p1",
                                        );
                                        const p2 = rs.playerStats.find(
                                          (ps) => ps.id === "p2",
                                        );
                                        const p3 = rs.playerStats.find(
                                          (ps) => ps.id === "p3",
                                        );
                                        const p4 = rs.playerStats.find(
                                          (ps) => ps.id === "p4",
                                        );

                                        return (
                                          <tr
                                            key={rs.round}
                                            className="hover:bg-white/5 transition-colors"
                                          >
                                            <td className="py-2 px-3 font-bold text-zinc-400">
                                              #{rs.round}
                                            </td>
                                            <td className="py-2 px-3">
                                              <span
                                                className={`inline-flex items-center justify-center font-sans font-bold px-1.5 py-0.5 rounded text-[10px] ${
                                                  rs.trumpSuit === "NONE"
                                                    ? "bg-amber-500/10 text-amber-500"
                                                    : "bg-white/10 text-zinc-200"
                                                }`}
                                              >
                                                {rs.trumpSuit === "NONE"
                                                  ? "SANS"
                                                  : getSuitUI(rs.trumpSuit)
                                                      .char}
                                              </span>
                                            </td>
                                            <td className="py-2 px-3 text-[10px] text-zinc-500">
                                              x{rs.multiplier}
                                            </td>

                                            <td
                                              className={`py-1.5 px-3 text-[11px] ${p1?.isSuccess ? "bg-emerald-500/10 text-emerald-400" : "bg-red-500/10 text-red-500"}`}
                                            >
                                              <div className="font-bold">
                                                {p1?.isDashCall
                                                  ? "DASH"
                                                  : `${p1?.bid}/${p1?.tricksWon}`}
                                              </div>
                                              <div className="text-[9px] opacity-80">
                                                {p1?.finalScore >= 0 ? "+" : ""}
                                                {p1?.finalScore}
                                              </div>
                                            </td>
                                            <td
                                              className={`py-1.5 px-3 text-[11px] ${p2?.isSuccess ? "bg-emerald-500/5 text-zinc-200" : "bg-red-500/5 text-zinc-400"}`}
                                            >
                                              <div>
                                                {p2?.isDashCall
                                                  ? "DASH"
                                                  : `${p2?.bid}/${p2?.tricksWon}`}
                                              </div>
                                              <div className="text-[9px] opacity-80">
                                                {p2?.finalScore >= 0 ? "+" : ""}
                                                {p2?.finalScore}
                                              </div>
                                            </td>
                                            <td
                                              className={`py-1.5 px-3 text-[11px] ${p3?.isSuccess ? "bg-emerald-500/5 text-zinc-200" : "bg-red-500/5 text-zinc-400"}`}
                                            >
                                              <div>
                                                {p3?.isDashCall
                                                  ? "DASH"
                                                  : `${p3?.bid}/${p3?.tricksWon}`}
                                              </div>
                                              <div className="text-[9px] opacity-80">
                                                {p3?.finalScore >= 0 ? "+" : ""}
                                                {p3?.finalScore}
                                              </div>
                                            </td>
                                            <td
                                              className={`py-1.5 px-3 text-[11px] ${p4?.isSuccess ? "bg-emerald-500/5 text-zinc-200" : "bg-red-500/5 text-zinc-400"}`}
                                            >
                                              <div>
                                                {p4?.isDashCall
                                                  ? "DASH"
                                                  : `${p4?.bid}/${p4?.tricksWon}`}
                                              </div>
                                              <div className="text-[9px] opacity-80">
                                                {p4?.finalScore >= 0 ? "+" : ""}
                                                {p4?.finalScore}
                                              </div>
                                            </td>
                                          </tr>
                                        );
                                      })
                                    )}
                                  </tbody>
                                </table>
                              </div>
                            </div>
                          </div>
                        </motion.div>
                      </motion.div>
                    );
                  })()}

                {showAuditModal && (
                  <motion.div
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    exit={{ opacity: 0 }}
                    className="fixed inset-0 bg-black/80 backdrop-blur-md z-999 flex items-center justify-center p-4 pointer-events-auto"
                  >
                    <motion.div
                      initial={{ scale: 0.95, y: 20 }}
                      animate={{ scale: 1, y: 0 }}
                      exit={{ scale: 0.95, y: 20 }}
                      className="bg-zinc-950 border border-amber-500/30 rounded-[2.5rem] w-full max-w-2xl overflow-hidden shadow-2xl flex flex-col max-h-[85vh]"
                    >
                      <div className="bg-gradient-to-r from-amber-600 to-amber-900 px-6 py-5 text-white flex justify-between items-center border-b border-white/10">
                        <div className="flex items-center gap-3">
                          <ShieldAlert
                            className="text-amber-300 animate-pulse"
                            size={20}
                          />
                          <div>
                            <h2 className="font-extrabold text-sm uppercase tracking-widest text-[#FFFBEB]">
                              Code Verification Audit
                            </h2>
                            <p className="text-[10px] text-zinc-300">
                              Evaluating 4 consecutive tricks using
                              trick-by-trick execution
                            </p>
                          </div>
                        </div>
                        <button
                          onClick={() => setShowAuditModal(false)}
                          className="bg-black/30 hover:bg-black/50 text-amber-200 hover:text-white px-3 py-1.5 rounded-full text-[10px] font-black uppercase tracking-wider transition-all"
                        >
                          Dismiss
                        </button>
                      </div>
                      <div className="flex-1 overflow-y-auto p-6 font-mono text-zinc-300 text-xs space-y-2 bg-black/40 shadow-inner leading-relaxed text-left">
                        {auditModalLogs.map((logLine, idx) => {
                          let colorClass = "text-zinc-300";
                          if (logLine.includes("🔮 TRICK"))
                            colorClass =
                              "text-amber-400 font-bold border-t border-zinc-800 pt-2 mt-2";
                          if (logLine.includes("🏆 Trick"))
                            colorClass = "text-teal-400 font-extrabold";
                          if (logLine.includes("✅ Legally Followed Lead Suit"))
                            colorClass = "text-emerald-400";
                          if (
                            logLine.includes("👑 LEAD CARD") ||
                            logLine.includes("👑 Declared Trump")
                          )
                            colorClass = "text-amber-350 font-bold";
                          if (logLine.includes("🔥 Cut with Trump"))
                            colorClass = "text-orange-400 font-bold";
                          if (logLine.includes("⚠️ Sluffed/Discarded"))
                            colorClass = "text-cyan-400";
                          if (logLine.includes("❌ ERROR"))
                            colorClass =
                              "text-red-500 font-bold bg-red-950/20 px-2 py-0.5 rounded";
                          if (logLine.includes("🎉 Audit Complete!"))
                            colorClass =
                              "text-amber-400 font-black text-center py-2 bg-zinc-900 border border-amber-500/20 rounded-xl mt-4";
                          if (logLine.includes("🔬 4-TRICK"))
                            colorClass =
                              "text-amber-400 font-black text-center text-sm py-1";

                          return (
                            <div
                              key={idx}
                              className={`${colorClass} whitespace-pre-wrap flex items-start gap-2`}
                            >
                              <span>{logLine}</span>
                            </div>
                          );
                        })}
                      </div>
                      <div className="p-4 bg-zinc-900 border-t border-white/5 flex justify-end gap-3">
                        <button
                          onClick={() => setShowAuditModal(false)}
                          className="bg-amber-600 hover:bg-amber-500 text-black font-black uppercase tracking-widest text-[10px] py-3 px-6 rounded-xl transition-all cursor-pointer"
                        >
                          Inspect Live Table State
                        </button>
                      </div>
                    </motion.div>
                  </motion.div>
                )}

                {showHistoryModal && (
                  <motion.div
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    exit={{ opacity: 0 }}
                    className="fixed inset-0 bg-black/85 backdrop-blur-md z-999 flex items-center justify-center p-4 pointer-events-auto"
                  >
                    <motion.div
                      initial={{ scale: 0.95, y: 20 }}
                      animate={{ scale: 1, y: 0 }}
                      exit={{ scale: 0.95, y: 20 }}
                      className="bg-[#0b1320] border border-emerald-500/30 rounded-[2.5rem] w-full max-w-4xl overflow-hidden shadow-2xl flex flex-col max-h-[85vh]"
                    >
                      {/* Modal Header */}
                      <div className="bg-gradient-to-r from-emerald-800 to-slate-900 px-6 py-5 text-white flex justify-between items-center border-b border-white/10 shadow-lg">
                        <div className="flex items-center gap-3 font-sans">
                          <div className="p-2.5 bg-emerald-500/20 rounded-xl border border-emerald-500/35">
                            <History
                              className="text-emerald-400 animate-pulse"
                              size={22}
                            />
                          </div>
                          <div className="text-left">
                            <h2 className="font-black text-base uppercase tracking-wider text-emerald-300">
                              Chronological Round History
                            </h2>
                            <p className="text-[11px] text-zinc-300">
                              Complete chronological record of all previous
                              round outcomes
                            </p>
                          </div>
                        </div>

                        <button
                          onClick={() => setShowHistoryModal(false)}
                          className="bg-black/40 hover:bg-rose-950/40 text-[#ffffff] hover:text-rose-400 w-8 h-8 rounded-full flex items-center justify-center border border-white/10 transition-all cursor-pointer active:scale-90"
                        >
                          ✕
                        </button>
                      </div>

                      {/* Modal Content - Scrollable list */}
                      <div className="flex-1 overflow-y-auto p-6 space-y-6 bg-slate-950/50">
                        {(() => {
                          const historyStats = log
                            .map((entry) => entry.stats)
                            .filter((s): s is RoundStat => !!s);
                          if (historyStats.length === 0) {
                            return (
                              <div className="py-16 text-center flex flex-col items-center justify-center space-y-4 font-sans">
                                <div className="w-16 h-16 rounded-full bg-slate-900 border-2 border-dashed border-zinc-700 flex items-center justify-center text-zinc-500">
                                  <History size={32} className="opacity-40" />
                                </div>
                                <div>
                                  <h3 className="text-sm font-black text-white uppercase tracking-widest">
                                    No rounds played yet
                                  </h3>
                                  <p className="text-xs text-zinc-400 mt-1 max-w-sm mx-auto">
                                    Once you start the match and submit bids,
                                    round outcomes and score distributions will
                                    be dynamically logged here.
                                  </p>
                                </div>
                              </div>
                            );
                          }

                          return (
                            <div className="space-y-4 font-sans">
                              {/* Chronological Stats Header Cards */}
                              <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-2">
                                <div className="bg-slate-900/40 border border-white/5 rounded-2xl p-4 text-center">
                                  <span className="text-[9px] uppercase font-black tracking-widest text-[#94A3B8] block">
                                    Total Played Runs
                                  </span>
                                  <span className="text-2xl font-black text-white font-mono">
                                    {historyStats.length} Rounds
                                  </span>
                                </div>
                                <div className="bg-slate-900/40 border border-white/5 rounded-2xl p-4 text-center">
                                  <span className="text-[9px] uppercase font-black tracking-widest text-[#94A3B8] block">
                                    Current Multiplier
                                  </span>
                                  <span className="text-2xl font-black text-amber-500 font-mono">
                                    x{multiplier}
                                  </span>
                                </div>
                                <div className="bg-[#10B981]/10 border border-[#10B981]/25 rounded-2xl p-4 text-center">
                                  <span className="text-[9px] uppercase font-black tracking-widest text-emerald-400 block">
                                    Game Status
                                  </span>
                                  <span className="text-xl font-bold text-[#E2E8F0] uppercase tracking-wide">
                                    Round {round} Active
                                  </span>
                                </div>
                              </div>

                              {/* Historical Timeline list */}
                              <div className="relative border-l-2 border-emerald-500/20 pl-4 py-2 ml-2 space-y-6">
                                {historyStats.map((rs) => {
                                  // Find round MVP (highest player delta in this round)
                                  const winnerPlayer = rs.playerStats.reduce(
                                    (prev: RoundPlayerStat | null, cur) => {
                                      if (!prev) return cur;
                                      return cur.finalScore > prev.finalScore
                                        ? cur
                                        : prev;
                                    },
                                    null as RoundPlayerStat | null,
                                  );

                                  return (
                                    <div
                                      key={rs.round}
                                      className="relative group text-left"
                                    >
                                      {/* Timeline marker node */}
                                      <div className="absolute -left-[25px] top-1.5 w-4.5 h-4.5 rounded-full bg-slate-950 border-3 border-emerald-500 flex items-center justify-center transition-transform group-hover:scale-125">
                                        <span className="w-1 h-1 rounded-full bg-emerald-400" />
                                      </div>

                                      {/* Card Body */}
                                      <div className="bg-slate-900/60 border border-white/5 hover:border-emerald-500/20 rounded-2xl p-5 transition-all shadow-lg hover:shadow-emerald-900/5">
                                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-white/5 pb-3 mb-4">
                                          <div className="flex flex-wrap items-center gap-3">
                                            <span className="bg-emerald-950 text-emerald-400 font-black text-xs px-3 py-1 rounded-lg border border-emerald-500/20 font-mono">
                                              ROUND #{rs.round}
                                            </span>
                                            <div className="flex items-center gap-2">
                                              <span className="text-[10px] text-zinc-400 uppercase font-black tracking-wider">
                                                Trump Suit:
                                              </span>
                                              <span
                                                className={`font-bold px-2 py-0.5 rounded text-xs leading-none font-sans ${
                                                  rs.trumpSuit === "NONE"
                                                    ? "bg-amber-500/10 text-amber-500"
                                                    : "bg-white/10 text-zinc-200"
                                                }`}
                                              >
                                                {rs.trumpSuit === "NONE"
                                                  ? "SANS 🃏"
                                                  : `${rs.trumpSuit} ${getSuitUI(rs.trumpSuit as Suit).char}`}
                                              </span>
                                            </div>
                                            <div className="flex items-center gap-1.5 text-[10px] text-zinc-500 font-bold font-mono">
                                              <span>•</span>
                                              <span>
                                                Multiplier: x{rs.multiplier}
                                              </span>
                                            </div>
                                          </div>

                                          {winnerPlayer &&
                                            winnerPlayer.finalScore > 0 && (
                                              <div className="flex items-center gap-1.5 bg-amber-500/10 text-amber-400 border border-amber-500/20 px-2.5 py-1 rounded-lg text-xs font-bold leading-none">
                                                <Crown
                                                  size={12}
                                                  className="text-amber-500"
                                                />
                                                <span className="uppercase text-[9px] tracking-widest text-[#FFFBEB]">
                                                  Round MVP:
                                                </span>
                                                <span>
                                                  {
                                                    winnerPlayer.name.split(
                                                      " ",
                                                    )[0]
                                                  }{" "}
                                                  (+{winnerPlayer.finalScore})
                                                </span>
                                              </div>
                                            )}
                                        </div>

                                        {/* Player scores statistics in this round */}
                                        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
                                          {rs.playerStats.map((pStat) => {
                                            const isWinner = pStat.isSuccess;
                                            return (
                                              <div
                                                key={pStat.id}
                                                className={`p-3 rounded-xl border flex flex-col justify-between gap-2.5 transition-colors ${
                                                  isWinner
                                                    ? "bg-emerald-500/5 border-emerald-500/15"
                                                    : "bg-red-500/5 border-red-500/15"
                                                }`}
                                              >
                                                <div className="flex justify-between items-center">
                                                  <span className="text-xs font-bold text-white tracking-tight flex items-center gap-1 truncate max-w-[110px]">
                                                    {pStat.name
                                                      .replace(" (باشا)", "")
                                                      .replace(" (جدع)", "")
                                                      .replace(" (حريف)", "")
                                                      .replace(
                                                        " (الأسطورة)",
                                                        "",
                                                      )}
                                                  </span>
                                                  <span
                                                    className={`text-[9px] font-mono font-bold px-1.5 py-0.5 rounded uppercase ${
                                                      isWinner
                                                        ? "bg-emerald-500/20 text-emerald-400"
                                                        : "bg-rose-500/20 text-rose-400"
                                                    }`}
                                                  >
                                                    {isWinner
                                                      ? "PASS ✓"
                                                      : "FAIL ✕"}
                                                  </span>
                                                </div>

                                                <div className="flex justify-between items-end font-mono">
                                                  <div className="flex flex-col text-left">
                                                    <span className="text-[8px] uppercase tracking-wider text-zinc-500">
                                                      Declared VB
                                                    </span>
                                                    <span className="text-[11px] font-bold text-zinc-300">
                                                      {pStat.isDashCall
                                                        ? "DASH CALL"
                                                        : `Bid: ${pStat.bid} / Won: ${pStat.tricksWon}`}
                                                    </span>
                                                  </div>

                                                  <div className="flex flex-col items-end">
                                                    <span
                                                      className={`text-sm font-black ${
                                                        pStat.finalScore >= 0
                                                          ? "text-emerald-400"
                                                          : "text-rose-500"
                                                      }`}
                                                    >
                                                      {pStat.finalScore >= 0
                                                        ? "+"
                                                        : ""}
                                                      {pStat.finalScore}
                                                    </span>
                                                    <span className="text-[9px] text-zinc-500">
                                                      Cum:{" "}
                                                      {pStat.newCumulativeScore}{" "}
                                                      pt
                                                    </span>
                                                  </div>
                                                </div>
                                              </div>
                                            );
                                          })}
                                        </div>
                                      </div>
                                    </div>
                                  );
                                })}
                              </div>
                            </div>
                          );
                        })()}
                      </div>

                      {/* Modal Footer actions */}
                      <div className="p-5 bg-zinc-900 border-t border-white/5 flex flex-col sm:flex-row justify-between items-center gap-3 font-sans">
                        <div className="flex items-center gap-2">
                          <button
                            onClick={copyCSVToClipboard}
                            className="bg-slate-800 hover:bg-slate-700 text-zinc-300 font-extrabold uppercase tracking-widest text-[10px] py-3 px-5 rounded-xl transition-all cursor-pointer flex items-center gap-1.5 active:scale-95"
                            title="Copy scores history dataset in CSV format"
                          >
                            <Copy size={12} /> Copy CSV dataset
                          </button>
                          <button
                            onClick={copyTextReportToClipboard}
                            className="bg-slate-800 hover:bg-slate-700 text-zinc-300 font-extrabold uppercase tracking-widest text-[10px] py-3 px-5 rounded-xl transition-all cursor-pointer flex items-center gap-1.5 active:scale-95"
                            title="Copy fully detailed text report scorecard"
                          >
                            <Copy size={12} /> COPY BRIEF REPORT
                          </button>
                        </div>

                        <button
                          onClick={() => setShowHistoryModal(false)}
                          className="w-full sm:w-auto bg-emerald-600 hover:bg-emerald-500 text-black font-black uppercase tracking-widest text-[10px] py-3.5 px-8 rounded-xl transition-all cursor-pointer shadow-lg active:scale-95"
                        >
                          Done Viewing
                        </button>
                      </div>
                    </motion.div>
                  </motion.div>
                )}

                {showBotDebuggerModal && (
                  <motion.div
                    initial={{ opacity: 0 }}
                    animate={{ opacity: 1 }}
                    exit={{ opacity: 0 }}
                    className="fixed inset-0 bg-black/85 backdrop-blur-md z-999 flex items-center justify-center p-4 pointer-events-auto"
                  >
                    <motion.div
                      initial={{ scale: 0.95, y: 20 }}
                      animate={{ scale: 1, y: 0 }}
                      exit={{ scale: 0.95, y: 20 }}
                      className="bg-[#0b121f] border border-blue-500/30 rounded-[2.5rem] w-full max-w-5xl overflow-hidden shadow-2xl flex flex-col max-h-[85vh]"
                    >
                      {/* Modal Header */}
                      <div className="bg-gradient-to-r from-blue-800 to-slate-900 px-6 py-5 text-white flex justify-between items-center border-b border-white/10 shadow-lg">
                        <div className="flex items-center gap-3 font-sans">
                          <div className="p-2.5 bg-blue-500/20 rounded-xl border border-blue-500/35">
                            <Cpu
                              className="text-blue-400 animate-pulse"
                              size={22}
                            />
                          </div>
                          <div>
                            <h2 className="font-extrabold text-sm uppercase tracking-widest text-[#F0FDF4] flex items-center gap-2">
                              Bot Decision Debugger
                              <span className="bg-blue-500/20 text-blue-300 text-[9px] px-2 py-0.5 rounded-full border border-blue-500/35">
                                LIVE ENGINE
                              </span>
                            </h2>
                            <p className="text-[10px] text-zinc-400">
                              Chronological analysis of AI gameplay card
                              selection & rules evaluation
                            </p>
                          </div>
                        </div>
                        <button
                          onClick={() => setShowBotDebuggerModal(false)}
                          className="bg-black/30 hover:bg-black/50 text-blue-200 hover:text-white px-3 py-1.5 rounded-full text-[10px] font-black uppercase tracking-wider transition-all"
                        >
                          Dismiss
                        </button>
                      </div>

                      {/* Modal Body */}
                      <div className="flex-1 overflow-hidden flex flex-col md:flex-row pb-0">
                        {/* Left Column: Decision History List */}
                        <div className="w-full md:w-80 border-r border-white/5 bg-black/30 flex flex-col h-full overflow-hidden">
                          <div className="p-4 border-b border-zinc-800/60 bg-zinc-950/20 flex justify-between items-center">
                            <span className="text-[10px] font-black text-zinc-400 uppercase tracking-widest">
                              Decision Log ({botDecisionsHistory.length})
                            </span>
                            {botDecisionsHistory.length > 0 && (
                              <button
                                onClick={() => {
                                  setBotDecisionsHistory([]);
                                  setSelectedBotDecision(null);
                                }}
                                className="text-[9px] text-red-400 hover:text-red-300 uppercase font-black tracking-wider transition-colors"
                              >
                                Clear Feed
                              </button>
                            )}
                          </div>

                          <div className="flex-1 overflow-y-auto divide-y divide-zinc-900">
                            {botDecisionsHistory.length === 0 ? (
                              <div className="p-6 text-center space-y-3">
                                <span className="inline-block p-3 rounded-full bg-zinc-900 border border-zinc-800 text-zinc-500">
                                  ⚙️
                                </span>
                                <p className="text-[11px] text-zinc-500 leading-normal">
                                  No gameplay decisions recorded yet. Start
                                  simulation or play a round to capture active
                                  decision trees in real time!
                                </p>
                              </div>
                            ) : (
                              botDecisionsHistory.map((dec) => {
                                const isSelected =
                                  selectedBotDecision?.id === dec.id;
                                const suitUI = getSuitUI(dec.chosenCard.suit);
                                return (
                                  <button
                                    key={dec.id}
                                    onClick={() => setSelectedBotDecision(dec)}
                                    className={`w-full p-3.5 text-left transition-all flex items-center gap-3 border-l-2 cursor-pointer ${
                                      isSelected
                                        ? "bg-blue-500/10 border-blue-500 text-white"
                                        : "border-transparent text-zinc-400 hover:bg-white/5"
                                    }`}
                                  >
                                    {/* Mini Avatar svg */}
                                    <div className="scale-75 origin-center shrink-0">
                                      {dec.botId === "p2" && <Bot3Avatar />}
                                      {dec.botId === "p3" && <Bot2Avatar />}
                                      {dec.botId === "p4" && <Bot1Avatar />}
                                    </div>
                                    <div className="flex-1 min-w-0">
                                      <div className="flex justify-between items-baseline mb-0.5">
                                        <span className="font-extrabold text-xs text-stone-200 truncate">
                                          {dec.botName.split(" ")[0]}
                                        </span>
                                        <span className="text-[8px] font-mono opacity-60 text-zinc-400">
                                          {dec.timestamp}
                                        </span>
                                      </div>
                                      <div className="truncate text-[10px] opacity-80 text-zinc-400">
                                        Played card:{" "}
                                        <span
                                          className={`${suitUI.color} font-black`}
                                        >
                                          {getRankUI(dec.chosenCard.rank)}
                                          {suitUI.char}
                                        </span>
                                      </div>
                                    </div>
                                    <ChevronRight
                                      size={14}
                                      className={
                                        isSelected
                                          ? "text-blue-400"
                                          : "text-zinc-600"
                                      }
                                    />
                                  </button>
                                );
                              })
                            )}
                          </div>
                        </div>

                        {/* Right Column: Detailed Inspector */}
                        <div className="flex-1 overflow-y-auto bg-[#0a0f19] p-6 flex flex-col">
                          {!selectedBotDecision ? (
                            <div className="flex-1 flex flex-col items-center justify-center p-12 text-center text-zinc-500 select-none">
                              <Cpu
                                size={48}
                                className="text-zinc-700 mb-4 stroke-[1.2]"
                              />
                              <h4 className="text-xs uppercase tracking-wider text-zinc-400 font-bold mb-1">
                                State Inspector Standby
                              </h4>
                              <p className="text-[11px] max-w-sm leading-relaxed">
                                Select an AI card decision from the left panel
                                to unpack rules and analyze follow-suit play
                                legality.
                              </p>
                            </div>
                          ) : (
                            <div className="space-y-6 text-left">
                              {/* Heading summary */}
                              <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-4 bg-zinc-950/60 rounded-2xl border border-white/5 shadow-md">
                                <div className="flex items-center gap-3">
                                  <div className="scale-75 origin-center shrink-0">
                                    {selectedBotDecision.botId === "p2" && (
                                      <Bot3Avatar />
                                    )}
                                    {selectedBotDecision.botId === "p3" && (
                                      <Bot2Avatar />
                                    )}
                                    {selectedBotDecision.botId === "p4" && (
                                      <Bot1Avatar />
                                    )}
                                  </div>
                                  <div>
                                    <h3 className="font-extrabold text-sm text-white">
                                      {selectedBotDecision.botName}
                                    </h3>
                                    <p className="text-[10px] font-semibold text-zinc-400">
                                      Target/Bid:{" "}
                                      <span className="text-amber-400 font-bold">
                                        {selectedBotDecision.isDashCall
                                          ? "DASH"
                                          : `${selectedBotDecision.bid} Tricks`}
                                      </span>
                                      {" • "}
                                      Tricks Won:{" "}
                                      <span className="text-cyan-400 font-bold">
                                        {selectedBotDecision.tricksWon} Won
                                      </span>
                                    </p>
                                  </div>
                                </div>

                                <div className="flex gap-2">
                                  {/* Trump representation */}
                                  <div className="bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-1 text-center">
                                    <span className="block text-[8px] uppercase tracking-widest text-zinc-500 font-black">
                                      Trump
                                    </span>
                                    <span className="font-bold text-[10px] text-emerald-400 uppercase flex items-center gap-1 justify-center">
                                      {selectedBotDecision.trumpSuit ===
                                      "NONE" ? (
                                        "SANZ 🃏"
                                      ) : (
                                        <>
                                          <span>
                                            {selectedBotDecision.trumpSuit}
                                          </span>
                                          <span
                                            className={
                                              getSuitUI(
                                                selectedBotDecision.trumpSuit,
                                              ).color
                                            }
                                          >
                                            {
                                              getSuitUI(
                                                selectedBotDecision.trumpSuit,
                                              ).char
                                            }
                                          </span>
                                        </>
                                      )}
                                    </span>
                                  </div>
                                  {/* Led Suit representation */}
                                  <div className="bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-1 text-center">
                                    <span className="block text-[8px] uppercase tracking-widest text-zinc-500 font-black">
                                      Led Suit
                                    </span>
                                    <span className="font-bold text-[10px] text-amber-400 uppercase flex items-center gap-1 justify-center">
                                      {selectedBotDecision.ledSuit === null ? (
                                        "Self-Lead"
                                      ) : (
                                        <>
                                          <span>
                                            {selectedBotDecision.ledSuit}
                                          </span>
                                          <span
                                            className={
                                              getSuitUI(
                                                selectedBotDecision.ledSuit,
                                              ).color
                                            }
                                          >
                                            {
                                              getSuitUI(
                                                selectedBotDecision.ledSuit,
                                              ).char
                                            }
                                          </span>
                                        </>
                                      )}
                                    </span>
                                  </div>
                                </div>
                              </div>

                              {/* Played Card & Reasoning Block */}
                              <div className="grid grid-cols-1 md:grid-cols-12 gap-5 items-stretch">
                                {/* Card illustration container */}
                                <div className="md:col-span-3 flex flex-col items-center justify-center p-4 bg-zinc-950/65 rounded-2xl border border-white/5 relative overflow-hidden">
                                  <span className="text-[9px] font-black text-blue-400 uppercase tracking-widest mb-3">
                                    Chosen Action
                                  </span>
                                  <div className="relative">
                                    <Card
                                      card={selectedBotDecision.chosenCard}
                                      disabled={true}
                                    />
                                    {/* Glow pulse effect behind chosen card */}
                                    <div className="absolute inset-0 bg-blue-500/10 rounded-xl blur-md -z-10 animate-pulse" />
                                  </div>
                                </div>

                                {/* Reasoning Container */}
                                <div className="md:col-span-9 flex flex-col justify-between p-5 bg-zinc-950/40 rounded-2xl border border-blue-500/25 shadow-inner">
                                  <div>
                                    <span className="text-[9px] font-black text-rose-400 uppercase tracking-widest block mb-1">
                                      Engine Reasoning
                                    </span>
                                    <div className="bg-[#050912] border border-blue-950/60 rounded-xl p-3 shadow-md font-mono text-[11.5px] text-stone-200 leading-relaxed max-h-24 overflow-y-auto">
                                      <span className="text-blue-400 font-semibold">
                                        &gt;{" "}
                                      </span>
                                      {selectedBotDecision.reasoning}
                                    </div>
                                  </div>

                                  <div className="mt-4 pt-3 border-t border-white/5 text-[10px] text-zinc-400 space-y-1">
                                    <span className="block font-black uppercase text-[8px] text-zinc-500 tracking-wider">
                                      Play Objectives:
                                    </span>
                                    {selectedBotDecision.isDashCall ? (
                                      <span className="block text-amber-400">
                                        🚨 DASH CALL: Bot must win exactly 0
                                        tricks. Avoid taking control!
                                      </span>
                                    ) : selectedBotDecision.tricksWon <
                                      selectedBotDecision.bid ? (
                                      <span className="block text-emerald-400">
                                        🔥 WIN SEEKING: Won{" "}
                                        {selectedBotDecision.tricksWon}/
                                        {selectedBotDecision.bid} tricks.
                                        Prioritizing point accumulation.
                                      </span>
                                    ) : (
                                      <span className="block text-cyan-400">
                                        ❄️ LOSS SEEKING: Target bid reached (
                                        {selectedBotDecision.bid}/
                                        {selectedBotDecision.bid}). Discarding
                                        winners to avoid penalty.
                                      </span>
                                    )}
                                  </div>
                                </div>
                              </div>

                              {/* Desk Play Table sitting BEFORE */}
                              <div className="bg-zinc-950/40 p-4 rounded-2xl border border-white/5">
                                <span className="text-[9px] font-black text-zinc-400 uppercase tracking-widest block mb-3">
                                  Trick Sandbox State Before Play (
                                  {selectedBotDecision.trickBefore.length}/3
                                  opponent cards played)
                                </span>
                                {selectedBotDecision.trickBefore.length ===
                                0 ? (
                                  <div className="text-[10px] font-medium text-amber-400/90 italic bg-amber-500/5 py-3.5 px-4 rounded-xl border border-amber-500/10">
                                    👑 Leading Turn: Bot led the trick segment
                                    and wasn't forced to match opponent cards.
                                  </div>
                                ) : (
                                  <div className="grid grid-cols-3 gap-3">
                                    {selectedBotDecision.trickBefore.map(
                                      (played: PlayedCard, pIdx: number) => {
                                        const name =
                                          players.find(
                                            (pl) => pl.id === played.playerId,
                                          )?.name || played.playerId;
                                        return (
                                          <div
                                            key={pIdx}
                                            className="bg-[#090f19] p-2.5 rounded-xl border border-zinc-800/80 flex items-center justify-between"
                                          >
                                            <div className="min-w-0">
                                              <div className="text-[10px] text-stone-300 font-bold truncate">
                                                {name.split(" ")[0]}
                                              </div>
                                              <span className="text-[8px] text-zinc-500 font-medium font-sans">
                                                Seat {pIdx + 1}
                                              </span>
                                            </div>
                                            <div className="scale-75 origin-right">
                                              <Card
                                                card={played.card}
                                                disabled={true}
                                                size="small"
                                              />
                                            </div>
                                          </div>
                                        );
                                      },
                                    )}
                                  </div>
                                )}
                              </div>

                              {/* Complete Hand Analysis */}
                              <div className="bg-zinc-950/60 p-5 rounded-2xl border border-white/5 space-y-3">
                                <div className="flex justify-between items-center mb-1">
                                  <div>
                                    <span className="text-[9px] font-black text-zinc-400 uppercase tracking-widest block">
                                      Hand Evaluation State (MVI Rule Vectoring)
                                    </span>
                                    <span className="text-[10px] text-zinc-500">
                                      Legal hand options matching game-engine
                                      validation guidelines
                                    </span>
                                  </div>
                                  <span className="bg-emerald-500/10 text-emerald-300 text-[10px] font-bold px-2 py-0.5 rounded border border-emerald-500/20">
                                    {selectedBotDecision.legalCards.length}{" "}
                                    Legal plays
                                  </span>
                                </div>

                                <div className="flex flex-wrap gap-2 pt-2 bg-black/30 p-4 rounded-xl shadow-inner min-h-[90px] items-center justify-center">
                                  {selectedBotDecision.handBefore.map(
                                    (card: CardModel) => {
                                      const isLegal =
                                        selectedBotDecision.legalCards.some(
                                          (lc: CardModel) => lc.id === card.id,
                                        );
                                      const isChosen =
                                        selectedBotDecision.chosenCard.id ===
                                        card.id;

                                      return (
                                        <div key={card.id} className="relative">
                                          <div
                                            className={`transition-all ${
                                              isChosen
                                                ? "ring-2 ring-yellow-500 ring-offset-2 ring-offset-[#0b121f] scale-105 z-20"
                                                : !isLegal
                                                  ? "opacity-35 grayscale contrast-[0.95]"
                                                  : "hover:scale-105 hover:-translate-y-1"
                                            }`}
                                          >
                                            <Card
                                              card={card}
                                              size="small"
                                              disabled={true}
                                            />
                                          </div>

                                          {isChosen && (
                                            <span className="absolute -top-1.5 -right-1.5 bg-yellow-500 text-stone-950 font-black text-[7px] w-4 h-4 rounded-full flex items-center justify-center border border-white shadow-md uppercase">
                                              ✓
                                            </span>
                                          )}

                                          {!isLegal && (
                                            <span
                                              className="absolute inset-0 bg-transparent rounded-lg flex items-center justify-center cursor-not-allowed select-none"
                                              title="Illegal option: Breaks follow suit constraints!"
                                            >
                                              <span className="bg-red-950/80 text-rose-300 font-extrabold text-[6px] tracking-tight py-0.5 px-1 rounded uppercase border border-rose-500/20 shadow-md">
                                                Restricted
                                              </span>
                                            </span>
                                          )}
                                        </div>
                                      );
                                    },
                                  )}
                                </div>
                              </div>
                            </div>
                          )}
                        </div>
                      </div>

                      {/* Modal Footer */}
                      <div className="p-4 bg-zinc-900 border-t border-white/5 flex flex-col sm:flex-row justify-between items-center gap-3">
                        <div className="text-[9px] font-mono text-zinc-400">
                          🚨 AI Bot learning biases active:{" "}
                          <span className="text-blue-400 font-extrabold">
                            True (Rule Engine v1.8.2)
                          </span>
                        </div>
                        <button
                          onClick={() => setShowBotDebuggerModal(false)}
                          className="bg-blue-600 hover:bg-blue-500 text-black font-black uppercase tracking-widest text-[10px] py-3.5 px-8 rounded-xl transition-all cursor-pointer shadow-lg active:scale-95"
                        >
                          Close Debugger
                        </button>
                      </div>
                    </motion.div>
                  </motion.div>
                )}
              </AnimatePresence>
            </div>

            {/* LOGS & ACTIONS */}
            <div className="col-span-12 lg:col-span-4 flex flex-col gap-4">
              <div className="bg-zinc-900/90 p-6 rounded-[2.5rem] border border-white/5 flex-1 flex flex-col shadow-2xl backdrop-blur-xl">
                <h3 className="hidden text-[10px] font-black text-zinc-500 uppercase tracking-[0.2em] mb-4 flex items-center gap-2">
                  <Terminal size={12} /> FEEDER_LOG
                </h3>
                <div className="hidden flex-1 bg-black/60 rounded-3xl p-5 overflow-y-auto font-mono text-[11px] text-emerald-400 space-y-2 border border-white/5 shadow-inner">
                  {log.map((m, i) => (
                    <div key={i} className="opacity-80">
                      <span className="text-zinc-600 mr-2">[{i}]</span>{" "}
                      {m.message}
                    </div>
                  ))}
                </div>

                <div className="mt-6">
                  {phase === "DEALING" && (
                    <div className="flex flex-col gap-3">
                      <button
                        onClick={dealCards}
                        className="group w-full bg-emerald-600 hover:bg-emerald-500 py-5 rounded-[1.5rem] font-black text-sm tracking-widest transition-all shadow-xl shadow-emerald-900/20 active:scale-95"
                      >
                        START MATCH
                      </button>
                      <button
                        onClick={runCompliantSimulationAudit}
                        className="group w-full bg-amber-600 hover:bg-amber-500 py-4 rounded-[1.5rem] font-black text-xs tracking-widest transition-all shadow-xl shadow-amber-900/15 active:scale-95 uppercase flex items-center justify-center gap-2"
                      >
                        ⚡ RUN 4-TRICK COMPLIANT AUDIT
                      </button>
                    </div>
                  )}
                  {phase === "DASH_CALL_DECISION" && biddingTurnIdx === 0 && (
                    <div className="bg-amber-500/10 border border-amber-500/25 rounded-2xl p-4 text-center">
                      <span className="text-amber-400 font-black text-xs uppercase tracking-wider block mb-1">
                        DASH CALL DECISION
                      </span>
                      <span className="text-[10px] text-zinc-400">
                        Choose <span className="text-white font-bold">YES</span>{" "}
                        or <span className="text-white font-bold">NO</span> on
                        the screen modal overlay.
                      </span>
                    </div>
                  )}
                  {phase === "CALL_PHASE" && biddingTurnIdx === 0 && (
                    <div className="bg-amber-500/10 border border-amber-500/25 rounded-2xl p-4 text-center">
                      <span className="text-amber-400 font-black text-xs uppercase tracking-wider block mb-1">
                        CALL YOUR TRUMP
                      </span>
                      <span className="text-[10px] text-zinc-400">
                        Configure your custom bid and suit on the interactive
                        table selector.
                      </span>
                    </div>
                  )}
                  {phase === "FOLLOWING_BIDS" && biddingTurnIdx === 0 && (
                    <div className="bg-amber-500/10 border border-amber-500/25 rounded-2xl p-4 text-center">
                      <span className="text-amber-400 font-black text-xs uppercase tracking-wider block mb-1">
                        ESTIMATE TRICKS
                      </span>
                      <span className="text-[10px] text-zinc-400">
                        Choose how many tricks you can win using the table grid.
                      </span>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </main>

          {/* COMPACT FOOTER STATUS NOTE */}
          <footer className="mt-4 max-w-7xl mx-auto text-center">
            <span className="text-[10px] uppercase font-black tracking-widest text-[#DFC5A9]/40 select-none">
              Estemshan Professional Board Arena — Immersive Landscape Sandbox
              Client
            </span>
          </footer>
        </>
      ) : (
        /* OUR GORGEOUS AND HIGHLY POLISHED ANDROID PROJECT FILES GENERATOR EXPANDED VIEW */
        <div className="max-w-7xl mx-auto grid grid-cols-12 gap-6 items-stretch min-h-[750px] animate-fade-in relative z-10 pb-12">
          {/* LEFT COLUMN: FILE EXPLORER (col-span-12 lg:col-span-4) */}
          <div className="col-span-12 lg:col-span-4 bg-[#292524] p-6 rounded-[2.5rem] border border-stone-800 flex flex-col gap-4 shadow-2xl">
            <div>
              <span className="text-[10px] font-black tracking-widest text-[#FBBF24] uppercase block">
                EXPLORER
              </span>
              <h2 className="text-xl font-extrabold tracking-tight text-white mt-1">
                Project Directory
              </h2>
              <p className="text-xs text-stone-400 mt-1 text-left">
                Select an Android source file to view implementation schemas and
                compile custom codeblocks.
              </p>
            </div>

            {/* QUICK STATS CARD */}
            <div className="bg-[#1C1917]/80 p-4 rounded-2xl border border-stone-850 flex gap-3 items-center">
              <Smartphone className="text-amber-500 w-8 h-8 shrink-0" />
              <div className="flex-1 text-left">
                <span className="text-[8px] font-bold text-zinc-500 uppercase tracking-widest block">
                  TARGET ARCHITECTURE
                </span>
                <span className="text-xs font-bold text-stone-200 block">
                  Android Jetpack Compose
                </span>
                <span className="text-[10px] text-zinc-500 block">
                  {projectFiles.length} modular classes ready
                </span>
              </div>
            </div>

            {/* INTERACTIVE FILE SELECTION LIST */}
            <div className="flex-1 overflow-y-auto space-y-2.5 max-h-[480px] pr-1.5 scrollbar-thin">
              {projectFiles.map((file, idx) => {
                const isSelected = selectedFileIndex === idx;
                const categoryColors: Record<string, string> = {
                  model:
                    "bg-indigo-500/10 text-indigo-400 border-indigo-500/20",
                  engine:
                    "bg-emerald-500/10 text-emerald-400 border-emerald-500/20",
                  ui: "bg-amber-500/10 text-amber-500 border-amber-500/20",
                  di: "bg-purple-500/10 text-purple-400 border-purple-500/20",
                };

                return (
                  <div
                    key={file.name}
                    onClick={() => {
                      setSelectedFileIndex(idx);
                      setCopiedIndex(null);
                    }}
                    className={`p-3.5 rounded-2xl border transition-all duration-300 cursor-pointer flex flex-col gap-1.5 text-left ${
                      isSelected
                        ? "bg-[#3A1F11]/60 border-amber-500 shadow-md ring-1 ring-amber-500/20"
                        : "bg-[#1C1917]/40 border-stone-850 hover:bg-[#1C1817]/60"
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-black text-stone-100">
                          {file.name}
                        </span>
                        <span
                          className={`text-[8px] font-black uppercase px-2 py-0.5 rounded-full border ${categoryColors[file.category] || "bg-stone-800 text-stone-350 border-white/5"}`}
                        >
                          {file.category}
                        </span>
                      </div>
                      <ChevronRight
                        className={`w-3.5 h-3.5 transition-transform ${isSelected ? "translate-x-0.5 text-amber-500" : "text-stone-600"}`}
                      />
                    </div>
                    <span className="text-[9px] font-mono text-stone-500 truncate">
                      {file.path}
                    </span>
                    <span className="text-[11px] text-stone-400 leading-normal line-clamp-2">
                      {file.description}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>

          {/* RIGHT COLUMN: RICH CODE VIEWER & RULE MAPPER (col-span-12 lg:col-span-8) */}
          <div className="col-span-12 lg:col-span-8 bg-[#292524] p-6 rounded-[2.5rem] border border-stone-800 flex flex-col gap-4 shadow-2xl relative">
            {/* FILE HEADER / ACTIONS TOOLBAR */}
            {(() => {
              const file = projectFiles[selectedFileIndex];
              if (!file) return null;

              const isModified =
                file.code !== kotlinProjectFiles[selectedFileIndex].code;

              const updateFileCode = (newCode: string) => {
                setProjectFiles((prev) =>
                  prev.map((f, idx) =>
                    idx === selectedFileIndex ? { ...f, code: newCode } : f,
                  ),
                );
              };

              // Clear save state timer reference
              const handleSave = () => {
                try {
                  localStorage.setItem(
                    "estimation_android_project_files",
                    JSON.stringify(projectFiles),
                  );
                  setSavedFileIndex(selectedFileIndex);
                  setTimeout(() => setSavedFileIndex(null), 2500);
                } catch (e) {
                  console.error("Failed to save project files", e);
                }
              };

              // Simple client-side copy implementation
              const handleCopy = () => {
                navigator.clipboard.writeText(file.code);
                setCopiedIndex(selectedFileIndex);
                setTimeout(() => setCopiedIndex(null), 2000);
              };

              // Genuine client-side file download logic
              const handleDownload = () => {
                const element = document.createElement("a");
                const blob = new Blob([file.code], { type: "text/plain" });
                element.href = URL.createObjectURL(blob);
                element.download = file.name;
                document.body.appendChild(element);
                element.click();
                document.body.removeChild(element);
              };

              return (
                <>
                  <div className="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 border-b border-stone-800 pb-4">
                    <div className="text-left">
                      <div className="flex items-center gap-2.5">
                        <FileCode className="text-amber-500 w-5 h-5" />
                        <h3 className="text-base font-black text-white">
                          {file.name}
                        </h3>
                        <span className="text-[8px] font-extrabold uppercase bg-[#1C1917]/80 border border-stone-800 px-2 py-0.5 rounded text-stone-400 font-mono tracking-wider">
                          {file.language}
                        </span>
                      </div>
                      <span className="text-[10px] font-mono text-stone-500 block mt-1">
                        {file.path}
                      </span>
                    </div>

                    <div className="flex flex-wrap items-center gap-2.5 w-full sm:w-auto">
                      {/* PREVIEW VS EDIT MODE TABS */}
                      <div className="flex bg-[#1C1917] rounded-xl border border-stone-850 p-1">
                        <button
                          onClick={() => setIsEditingMode(false)}
                          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition-all cursor-pointer ${
                            !isEditingMode
                              ? "bg-[#3A1F11] text-[#FBBF24] shadow-inner font-black"
                              : "text-stone-400 hover:text-stone-200"
                          }`}
                        >
                          Preview
                        </button>
                        <button
                          onClick={() => setIsEditingMode(true)}
                          className={`px-3 py-1.5 rounded-lg text-xs font-bold transition-all cursor-pointer ${
                            isEditingMode
                              ? "bg-[#3A1F11] text-[#FBBF24] shadow-inner font-black"
                              : "text-stone-400 hover:text-stone-200"
                          }`}
                        >
                          Edit
                        </button>
                      </div>

                      {/* EXPLICIT SAVE BUTTON */}
                      {isEditingMode && (
                        <button
                          onClick={handleSave}
                          className="flex-1 sm:flex-none flex items-center justify-center gap-2 bg-[#FBBF24] hover:bg-[#FBBF24]/90 active:scale-95 text-stone-950 px-4 py-2.5 rounded-xl text-xs font-black transition-all cursor-pointer shadow-md shadow-amber-950/20"
                          title="Save edited file securely"
                        >
                          {savedFileIndex === selectedFileIndex ? (
                            <>
                              <Check className="w-3.5 h-3.5 text-stone-950" />
                              <span>Saved!</span>
                            </>
                          ) : (
                            <>
                              <Save className="w-3.5 h-3.5 text-stone-950" />
                              <span>Save Changes</span>
                            </>
                          )}
                        </button>
                      )}

                      <button
                        onClick={handleCopy}
                        className="flex-1 sm:flex-none flex items-center justify-center gap-2 bg-[#1C1917] hover:bg-[#1c1917]/80 px-4 py-2.5 rounded-xl border border-stone-850 text-xs text-stone-300 font-bold transition-all cursor-pointer active:scale-95"
                      >
                        {copiedIndex === selectedFileIndex ? (
                          <>
                            <Check className="text-emerald-500 w-3.5 h-3.5" />
                            <span className="text-emerald-400 font-black">
                              Copied!
                            </span>
                          </>
                        ) : (
                          <>
                            <Copy className="text-[#FBBF24] w-3.5 h-3.5" />
                            <span>Copy Source</span>
                          </>
                        )}
                      </button>
                      <button
                        onClick={handleDownload}
                        className="flex-1 sm:flex-none flex items-center justify-center gap-2 bg-gradient-to-r from-emerald-600 to-teal-600 hover:brightness-110 px-5 py-2.5 rounded-xl text-xs text-stone-950 font-black transition-all cursor-pointer active:scale-95 shadow-md shadow-emerald-950/20"
                      >
                        <Download className="w-3.5 h-3.5 text-stone-950" />
                        <span>Download Class</span>
                      </button>
                    </div>
                  </div>

                  {/* CODE CONTAINER */}
                  <div
                    className={`flex-1 bg-[#121110] p-6 rounded-3xl border border-stone-850/80 max-h-[480px] min-h-[380px] font-mono text-[11px] leading-relaxed scrollbar-thin shadow-inner relative text-left ${isEditingMode ? "overflow-hidden flex flex-col" : "overflow-auto"}`}
                  >
                    {/* Floating mini status indicator and Reset button */}
                    <div className="absolute top-4 right-4 flex items-center gap-2 z-10 font-sans">
                      {isModified && (
                        <button
                          onClick={() => {
                            const originalCode =
                              kotlinProjectFiles[selectedFileIndex].code;
                            setProjectFiles((prev) => {
                              const updated = prev.map((f, idx) =>
                                idx === selectedFileIndex
                                  ? { ...f, code: originalCode }
                                  : f,
                              );
                              try {
                                localStorage.setItem(
                                  "estimation_android_project_files",
                                  JSON.stringify(updated),
                                );
                              } catch (e) {
                                console.error(e);
                              }
                              return updated;
                            });
                          }}
                          className="flex items-center gap-1 bg-[#442115] hover:bg-[#5a2c1a] px-2.5 py-1 rounded-full text-[9px] text-[#FBBF24] font-bold border border-amber-900/40 transition-all cursor-pointer shadow-sm active:scale-95"
                          title="Reset file code to original template"
                        >
                          <RotateCcw className="w-2.5 h-2.5 text-amber-500" />
                          <span>Reset</span>
                        </button>
                      )}
                      <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-[#1C1917]/90 text-[8px] font-mono uppercase text-[#FBBF24] border border-white/5 select-none">
                        <div
                          className={`w-1.5 h-1.5 rounded-full ${isModified ? "bg-amber-500 animate-pulse" : "bg-emerald-500"}`}
                        />
                        {isModified ? "Edited State" : "Original Layout"}
                      </div>
                    </div>

                    {isEditingMode ? (
                      <textarea
                        value={file.code}
                        onChange={(e) => updateFileCode(e.target.value)}
                        className="w-full flex-1 min-h-[320px] bg-transparent text-stone-200 font-mono text-[11px] leading-relaxed focus:outline-none resize-none overflow-y-auto scrollbar-thin border-none outline-none select-text pr-1.5 focus:ring-0 whitespace-pre pt-4"
                        placeholder="// Type or edit Kotlin code here..."
                        spellCheck={false}
                      />
                    ) : (
                      <pre className="text-zinc-300 select-all whitespace-pre text-left">
                        <code>{renderCustomKotlinHighlight(file.code)}</code>
                      </pre>
                    )}
                  </div>

                  {/* DESIGN Rationale / Android-to-Egypt translation card */}
                  <div className="bg-[#1C1917]/60 p-4 rounded-xl border border-stone-850 flex gap-3 text-left">
                    <HelpCircle className="text-amber-500 w-5 h-5 shrink-0 mt-0.5" />
                    <div>
                      <h4 className="text-xs font-black text-[#FBBF24] uppercase tracking-wider">
                        Arabic Estemshan Implementation Mapping
                      </h4>
                      <p className="text-[11px] text-stone-400 leading-normal mt-1 pr-4">
                        {getFileImplementationMapping(file.name)}
                      </p>
                    </div>
                  </div>
                </>
              );
            })()}
          </div>
        </div>
      )}
    </div>
  );
}
