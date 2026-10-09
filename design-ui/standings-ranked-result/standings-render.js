/* ════════════════════════════════════════════════════════════════════
   Estimation — Final Standings Render Layer
   Pure view: reads `state`, paints the Game Result panel. No logic here.
   ════════════════════════════════════════════════════════════════════ */

const RANK_LABEL = ["1ST", "2ND", "3RD", "4TH"];
const ROLE = ["King", "Minister", "Small Koz", "Koz"];
const KOOZ_BADGE = `<span class="rank kooz" title="Koz"><svg viewBox="14 12 19 22">
  <defs>
    <linearGradient id="mugFill" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#ffffff"></stop><stop offset="1" stop-color="#cfddea"></stop>
    </linearGradient>
  </defs>
  <path d="M27.5 21 h2.6 a3 3 0 0 1 0 6 H27.5" fill="none" stroke="#e2ecf5" stroke-width="2.4"></path>
  <rect x="16.5" y="18" width="11" height="15" rx="2.4" fill="url(#mugFill)" stroke="#aebfce" stroke-width="0.7"></rect>
  <circle cx="18.8" cy="17.2" r="2.5" fill="#ffffff"></circle>
  <circle cx="22.2" cy="16.1" r="2.9" fill="#ffffff"></circle>
  <circle cx="25.5" cy="17.4" r="2.3" fill="#ffffff"></circle>
  <rect x="19" y="20.6" width="1.5" height="10" rx="0.7" fill="rgba(120,150,180,0.5)"></rect>
  <rect x="23.3" y="20.6" width="1.5" height="10" rx="0.7" fill="rgba(120,150,180,0.35)"></rect>
</svg></span>`;

function bindStatic() {
  document.getElementById("rankedHomeBtn").addEventListener("click", () => {
    // S45 is a host binding; no destination screen was supplied.
    if (typeof window.returnToRanked === "function") window.returnToRanked("S45");
    else document.getElementById("navigationBinding").textContent = "S45 Ranked Home requested — destination binding required.";
  });
  document.getElementById("lobbyBtn").addEventListener("click", () => { GameState.goTo(GameState.STATES.LOBBY); });
  document.getElementById("shareBtn").addEventListener("click", () => {});
  document.getElementById("graphBtn").addEventListener("click", toggleGraph);
  document.querySelectorAll(".res-seg button").forEach(b =>
    b.addEventListener("click", () => setOutcome(b.dataset.out)));
}

// ── standings list + stats + rewards ──
function buildAll() {
  // outcome segmented control
  document.querySelectorAll(".res-seg button").forEach(b =>
    b.classList.toggle("on", b.dataset.out === state.outcome));

  // title = your final place title (King / Minister / Small Koz / Koz)
  document.getElementById("resTitle").textContent = ROLE[state.youRank - 1];
  document.getElementById("resTitle").classList.toggle("match-king", state.youRank === 1);

  // ranked rows
  document.getElementById("list").innerHTML = state.ranking.map((id, idx) => {
    const p = pById(id);
    const place = state.players.filter(other => other.total > p.total).length;
    const win = place === 0;
    const last = idx === state.ranking.length - 1 && state.players.filter(other => other.total === p.total).length === 1;
    const rankCell = win ? `<span class="rank crown">♛</span>`
      : last ? KOOZ_BADGE
      : `<span class="rank">${RANK_LABEL[place]}</span>`;
    const youTag = p.isUser ? `<span class="you-tag">YOU</span>` : "";
    const roleTag = `<span class="role-tag ${win ? "king" : ""}">${ROLE[last ? 3 : Math.min(place, 2)]}</span>`;
    return `<div class="res-row ${win ? "win" : ""} ${p.isUser ? "isyou" : ""}" data-id="${id}">
      ${rankCell}
      <div class="av-ring" style="${win ? "" : "background:linear-gradient(160deg,#6f5733,#3a2c19);"}"><div class="avatar">${p.initial}</div></div>
      <div class="row-name"><span class="rn-name">${p.name}</span>${youTag}${roleTag}${p.isUser ? `<span class="tier-chip">${RankedResult.label(state.ranked.next)}</span>` : ""}</div>
      <span class="row-score" data-total="${p.total}">0</span>
    </div>`;
  }).join("");

  // stats panel
  const s = state.stats;
  const sign = n => (n > 0 ? "+" : "") + n;
  document.getElementById("stats").innerHTML = `
    <div class="stat-head">SUMMARY</div>
    <div class="stat-row"><span class="sl">Rounds W / L</span><span class="sv"><b class="pos">${s.youWL.win}</b> <span class="sep">/</span> <b class="neg">${s.youWL.loss}</b></span></div>
    <div class="stat-row"><span class="sl">Biggest round</span><span class="sv">${s.biggestWin.who} <b class="pos">${sign(s.biggestWin.amt)}</b></span></div>
    <div class="stat-row"><span class="sl">Worst round</span><span class="sv">${s.biggestLoss.who} <b class="neg">${sign(s.biggestLoss.amt)}</b></span></div>
    <div class="stat-divider"></div>
    <div class="stat-row"><span class="sl">Your Calls won</span><span class="sv"><b>${s.callsWon}</b></span></div>
    <div class="stat-row"><span class="sl">Your Dash calls</span><span class="sv"><b>${s.dashes}</b></span></div>
    <div class="stat-row"><span class="sl">Final margin</span><span class="sv"><b class="${state.outcome === "win" ? "pos" : "neg"}">${state.outcome === "win" ? "+" : "−"}${state.margin}</b></span></div>`;

  // rewards strip
  const r = state.ranked;
  document.getElementById("rewards").innerHTML = `
    <div class="rw-chip rp-chip"><span class="rw-lab">RANKED RP</span><span class="rw-val ${r.delta < 0 ? "negative" : ""}" id="rpDelta">0</span><span class="rank-detail">${r.rp.toLocaleString()} RP${r.reason ? `<br>${r.reason}` : ""}${r.ceiling ? "<br>Above 3,000 RP · leaderboard-only" : ""}</span></div>
    <div class="rw-chip transition-chip"><span class="rw-lab">RANK TRANSITION</span><div class="rank-path"><span class="tier-chip">${RankedResult.label(r.previous)}</span><span>→</span><span class="tier-chip" id="nextRank">${RankedResult.label(r.next)}</span></div></div>
    <div class="rw-chip season-chip"><span class="rw-lab">SEASON${window.rankedResultBinding ? "" : " · BOUND SAMPLE"}</span><span class="rank-detail">${r.season.id}<br>${r.season.start} → ${r.season.end}</span></div>`;
  document.getElementById("sampleState").textContent = window.rankedResultBinding ? "Bound server result" : `Bound sample · ${r.label}`;

  buildGraph();
}

// ── score tally + crown reveal ──
let tallyRAF = null;
let rankedRAF = null;
let rankedTimer = null;
function revealSequence() {
  const rows = [...document.querySelectorAll(".res-row")];
  rows.forEach((row, i) => { row.style.setProperty("--d", (rows.length - 1 - i) * 0.09 + "s"); row.classList.remove("in"); void row.offsetWidth; row.classList.add("in"); });

  cancelAnimationFrame(tallyRAF);
  cancelAnimationFrame(rankedRAF);
  clearTimeout(rankedTimer);
  const start = performance.now();
  const dur = 950;
  const els = rows.map(r => r.querySelector(".row-score"));
  function step(now) {
    const t = Math.min(1, (now - start) / dur);
    const e = 1 - Math.pow(1 - t, 3);
    els.forEach(el => { el.textContent = Math.round(parseInt(el.dataset.total, 10) * e); });
    if (t < 1) tallyRAF = requestAnimationFrame(step);
    else {
      els.forEach(el => el.textContent = el.dataset.total);
      document.querySelectorAll(".res-row.win").forEach(champ => { champ.classList.remove("crowned"); void champ.offsetWidth; champ.classList.add("crowned"); });
      rankedTimer = setTimeout(() => {
        const startRP = performance.now(), r = state.ranked;
        function tallyRP(now) {
          const t = Math.min(1, (now - startRP) / 550);
          const value = Math.round(r.delta * (1 - Math.pow(1-t, 3)));
          document.getElementById("rpDelta").textContent = (value > 0 ? "+" : value < 0 ? "−" : "") + Math.abs(value);
          if (t < 1) rankedRAF = requestAnimationFrame(tallyRP);
          else document.getElementById("nextRank").classList.add(r.movement > 0 ? "promoted" : r.movement < 0 ? "demoted" : "settled");
        }
        rankedRAF = requestAnimationFrame(tallyRP);
      }, 350);
    }
  }
  tallyRAF = requestAnimationFrame(step);
}

// ── round-by-round graph ──
function buildGraph() {
  const W = 824, H = 250, padL = 44, padR = 14, padT = 18, padB = 26;
  const all = state.players.flatMap(p => p.series).concat([0]);
  const max = Math.max(...all), min = Math.min(...all);
  const denom = Math.max(state.rounds - 1, 1); // guard divide-by-zero when only 1 real round has been played
  const x = i => padL + (i / denom) * (W - padL - padR);
  const y = v => padT + (max - v) / (max - min || 1) * (H - padT - padB);

  const zeroY = y(0);
  const lines = state.players.map(p => {
    const pts = p.series.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ");
    return `<polyline points="${pts}" fill="none" stroke="${p.color}" stroke-width="${p.isUser ? 3.5 : 2}" stroke-dasharray="${p.dash}" stroke-linejoin="round" stroke-linecap="round" opacity="${p.isUser ? 1 : 0.92}"></polyline>`;
  }).join("");
  const dots = state.players.map(p => {
    const i = state.rounds - 1;
    return `<circle cx="${x(i).toFixed(1)}" cy="${y(p.series[i]).toFixed(1)}" r="${p.isUser ? 4 : 3}" fill="${p.color}"></circle>`;
  }).join("");
  const legend = state.players.map(p =>
    `<span class="lg-item"><i style="background:${p.color};${p.dash ? "opacity:.9" : ""}"></i>${p.name}</span>`).join("");

  document.getElementById("graph").innerHTML = `
    <div class="graph-head"><span class="gh-title">Score by round</span><div class="graph-legend">${legend}</div><button class="graph-close" id="graphClose">Close ▴</button></div>
    <svg viewBox="0 0 ${W} ${H}" class="graph-svg" data-om-raster>
      <line x1="${padL}" y1="${zeroY}" x2="${W - padR}" y2="${zeroY}" stroke="rgba(255,240,210,0.18)" stroke-dasharray="3 4"></line>
      <text x="6" y="${zeroY + 3}" class="gx">0</text>
      <text x="6" y="${padT + 4}" class="gx">${max}</text>
      <text x="6" y="${H - padB + 12}" class="gx">${min}</text>
      <text x="${padL}" y="${H - 6}" class="gx">R1</text>
      <text x="${x(state.rounds - 1) - 14}" y="${H - 6}" class="gx">R${state.rounds}</text>
      ${lines}${dots}
    </svg>`;
  document.getElementById("graphClose").addEventListener("click", toggleGraph);
}

function toggleGraph() {
  const g = document.getElementById("graph");
  const open = g.classList.toggle("show");
  document.getElementById("graphBtn").textContent = open ? "Graph ▴" : "Graph ▾";
}
