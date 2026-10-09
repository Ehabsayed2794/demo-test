/* Preview fixtures only. Production supplies window.rankedResultBinding:
   { previousRP, delta (final RP-engine integer), season: {id,start,end}, reason }.
   Never derive a production award from match scores. */
const RankedResult = (() => {
  const tiers = [
    ["Bronze", "مبتدئ", [0, 75, 150]],
    ["Silver", "لاعب", [250, 350, 450]],
    ["Gold", "معلم", [575, 700, 825]],
    ["Platinum", "وزير", [1000, 1200, 1400]],
    ["Diamond", "أمير", [1600, 1800, 2000]],
    ["Royal", "سلطان", [2250, 2500, 2750]],
    ["King", "ملك", [3000]],
  ];
  const ladder = tiers.flatMap(([name, arabic, bounds]) =>
    bounds.map((lower, i) => ({ name, arabic, lower, division: name === "King" ? "" : ["III", "II", "I"][i] })));
  const rankAt = rp => ladder.filter(r => r.lower <= Math.max(0, rp)).at(-1);
  const half = n => Math.sign(n) * Math.floor(Math.abs(n) / 2 + 0.5);
  const season = { id: "SAMPLE-S12", start: "2026-07-01", end: "2026-10-01" };
  const samples = [
    { label: "Within-tier promotion", outcome: "win", previousRP: 430, full: 30 },
    { label: "Higher-tier promotion", outcome: "win", previousRP: 980, full: 30 },
    { label: "One-division demotion", outcome: "loss", previousRP: 710, full: -30 },
    { label: "Ladder floor", outcome: "loss", previousRP: 0, full: -30 },
    { label: "Mixed-tier loss", outcome: "loss", previousRP: 740, full: -45, reason: "Loss increased, you were the higher tier" },
    { label: "Mini", outcome: "win", previousRP: 600, full: 15, mini: true },
    { label: "King ceiling", outcome: "win", previousRP: 3050, full: 30 },
    { label: "Match King · Gold rank", outcome: "win", previousRP: 600, full: 30 },
    { label: "Tied Match Kings", outcome: "win", previousRP: 600, full: 30, tie: true },
    { label: "Mixed-tier win", outcome: "win", previousRP: 600, full: 15, reason: "Win reduced, you were the higher tier" },
    { label: "Lower-tier win", outcome: "win", previousRP: 430, full: 45, reason: "Win increased, you beat a higher tier" },
  ];
  let index = 7;
  function resolve(input) {
    const previousRP = Math.max(0, input.previousRP);
    const award = input.delta ?? (input.mini ? half(input.full) : input.full);
    const rp = Math.max(0, previousRP + award);
    const previous = rankAt(previousRP), next = rankAt(rp);
    return { ...input, previousRP, rp, delta: rp - previousRP, previous, next,
      movement: Math.sign(ladder.indexOf(next) - ladder.indexOf(previous)),
      ceiling: previous.name === "King" && rp > previousRP };
  }
  function current() { return resolve(window.rankedResultBinding || { ...samples[index], season }); }
  function cycle(outcome) {
    if (window.rankedResultBinding) return;
    do { index = (index + 1) % samples.length; } while (samples[index].outcome !== outcome);
  }
  function label(rank) { return `${rank.name}${rank.division ? " " + rank.division : ""} · ${rank.arabic}`; }
  return { ladder, rankAt, half, resolve, current, cycle, label };
})();
