/* ════════════════════════════════════════════════════════════════════
   Estimation — Final Standings (Game Result) Engine
   Reads REAL results exclusively from GameSession (via ScoringEngine) —
   no synthetic/random scores. Every round shown here was actually played
   and scored by scoring-engine.js. See ScoringEngine.md.
   ════════════════════════════════════════════════════════════════════ */

const DECOR = { p1: { color: "#F4B860", dash: "" }, p2: { color: "#DE8A39", dash: "" }, p3: { color: "#C2A06A", dash: "7 5" }, p4: { color: "#9A7240", dash: "2 5" } };
const PLAYERS = GameSession.getPlayers().map(p => ({ id: p.id, name: p.name, initial: p.initial, isUser: p.isUser, ...DECOR[p.id] }));

let state = null;

function generate() {
  const history = GameSession.get().roundHistory;      // every round actually played + scored so far
  const matchScores = ScoringEngine.calculateMatchScore();

  const players = PLAYERS.map(p => {
    const deltas = history.map(r => (r.scoreDeltas && r.scoreDeltas[p.id]) || 0);
    let run = 0;
    const series = deltas.length ? deltas.map(d => (run += d)) : [0];
    return { ...p, deltas, series, total: matchScores[p.id] || 0 };
  });

  const ranking = [...players].sort((a, b) => b.total - a.total).map(p => p.id);
  const youRank = 1 + players.filter(p => p.total > players.find(p => p.id === "p1").total).length;
  const outcome = youRank === 1 ? "win" : "loss";        // derived from the real match score, never forced

  let bWin = { amt: -Infinity, who: "", round: 0 }, bLoss = { amt: Infinity, who: "", round: 0 };
  players.forEach(p => p.deltas.forEach((d, i) => {
    if (d > bWin.amt) bWin = { amt: d, who: p.name, round: i + 1 };
    if (d < bLoss.amt) bLoss = { amt: d, who: p.name, round: i + 1 };
  }));
  if (!isFinite(bWin.amt)) bWin = { amt: 0, who: "—", round: 0 };
  if (!isFinite(bLoss.amt)) bLoss = { amt: 0, who: "—", round: 0 };

  const you = players.find(p => p.id === "p1");
  const wins = you.deltas.filter(d => d > 0).length;
  const losses = you.deltas.filter(d => d < 0).length;    // a Sa'ayda round (delta 0) counts as neither
  const callsWon = history.filter(r => r.callerId === "p1").length;
  // Best-available signal for "Dash calls" — the committed estimates map
  // can't currently distinguish a pre-bidding Dash Call from a Normal
  // Dash (both collapse to 0) — see ScoringEngine.md § Open Rule Questions #1.
  const dashes = history.filter(r => r.estimates && r.estimates.p1 === 0).length;

  const byId = id => players.find(p => p.id === id);
  const margin = outcome === "win"
    ? byId(ranking[0]).total - byId(ranking[1]).total
    : byId(ranking[0]).total - you.total;

  GameSession.setWinner(ranking[0]);

  state = {
    rounds: Math.max(history.length, 1),   // at least 1 column so the graph never divides by zero
    players, ranking,
    stats: { youWL: { win: wins, loss: losses }, biggestWin: bWin, biggestLoss: bLoss, callsWon, dashes },
    outcome, youRank, margin,
    ranked: RankedResult.current(),
  };
  return state;
}

function pById(id) { return state.players.find(p => p.id === id); }

// The Win/Loss segmented control used to re-roll random data to preview
// the opposite outcome. Scores are now real, so there is nothing to
// re-roll — clicking the side that doesn't match the real result is a
// no-op (the control still exists and is clickable, per "no redesign").
function setOutcome(o) { RankedResult.cycle(o); generate(); applyBoundSample(); buildAll(); revealSequence(); }
function restart() { generate(); buildAll(); revealSequence(); }

// A standalone export has no completed session. Its deterministic scores are
// illustrative fixtures, not injected into the real scoring/session engine.
function applyBoundSample() {
  if (window.rankedResultBinding) return;
  const r = state.ranked;
  const totals = r.outcome === "win" ? [186, r.tie ? 186 : 142, 98, 64] : [98, 186, 142, 64];
  state.players.forEach((p, i) => {
    p.total = totals[i];
    p.series = [Math.round(p.total * .2), Math.round(p.total * .45), Math.round(p.total * .7), p.total];
  });
  state.rounds = 4;
  state.ranking = [...state.players].sort((a,b) => b.total-a.total).map(p => p.id);
  state.youRank = 1 + state.players.filter(p => p.total > totals[0]).length;
  state.outcome = r.outcome;
  state.margin = r.outcome === "win" ? totals[0] - pById(state.ranking[1]).total : pById(state.ranking[0]).total - totals[0];
  state.stats = { youWL: {win: 3, loss: 1}, biggestWin: {who: "Khaled_X", amt: 58},
    biggestLoss: {who: "Omar_K", amt: -24}, callsWon: 2, dashes: 1 };
}

window.addEventListener("DOMContentLoaded", () => {
  GameState.sync(GameState.STATES.FINAL_STANDINGS);
  generate();
  applyBoundSample();
  bindStatic();
  buildAll();
  revealSequence();
});
