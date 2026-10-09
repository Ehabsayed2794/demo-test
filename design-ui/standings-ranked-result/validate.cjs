const fs = require("node:fs");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const path = require("node:path");
const read = name => fs.readFileSync(path.join(__dirname, name), "utf8");
const elements = {};
const context = vm.createContext({
  window: { addEventListener() {} },
  document: {
    querySelectorAll: () => [],
    getElementById: id => elements[id] ||= { classList: { toggle() {} }, addEventListener() {} },
  },
  GameSession: {
    getPlayers: () => ["p1", "p2", "p3", "p4"].map((id,i) => ({id, name: id, initial: id, isUser: i === 0})),
    get: () => ({ roundHistory: [] }),
    setWinner() {},
  },
  ScoringEngine: { calculateMatchScore: () => ({}) },
});
for (const name of ["ranked-result.js", "standings-render.js", "standings-engine.js"]) vm.runInContext(read(name), context);
const run = source => vm.runInContext(source, context);
assert.equal(run("RankedResult.ladder.length"), 19);
assert.equal(run("RankedResult.label(RankedResult.rankAt(3000))"), "King · ملك");
assert.equal(run("RankedResult.label(RankedResult.rankAt(0))"), "Bronze III · مبتدئ");
for (const [award, expected] of [[15,8],[-15,-8],[20,10],[-20,-10]]) assert.equal(run(`RankedResult.half(${award})`), expected);
assert.equal(run("RankedResult.resolve({previousRP:0,delta:-30}).delta"), 0);
assert.equal(run("RankedResult.resolve({previousRP:3050,delta:30}).rp"), 3080);
assert.equal(run("RankedResult.resolve({previousRP:3050,delta:30}).ceiling"), true);
assert.equal(run("RankedResult.resolve({previousRP:825,delta:-250}).movement"), -1);
assert.equal(run("RankedResult.label(RankedResult.resolve({previousRP:825,delta:-250}).next)"), "Gold III · معلم");
for (const rank of run("RankedResult.ladder")) {
  assert.equal(run(`RankedResult.rankAt(${rank.lower}).lower`), rank.lower);
}
const seen = new Set();
for (const outcome of ["win", "loss"]) {
  for (let i = 0; i < 16; i++) {
    run(`RankedResult.cycle("${outcome}"); generate(); applyBoundSample(); buildAll();`);
    const result = run("state.ranked");
    seen.add(result.label);
    assert.ok(result.rp >= 0);
    assert.ok(!elements.rewards.innerHTML.includes("♛"));
    assert.ok(!elements.rewards.innerHTML.includes("King III"));
    if (result.tie) assert.equal((elements.list.innerHTML.match(/class="rank crown"/g) || []).length, 2);
    if (result.reason) assert.ok(elements.rewards.innerHTML.includes(result.reason));
    if (result.mini) assert.equal(result.delta, 8);
  }
}
assert.equal(seen.size, 11);
const oldCSS = read("table-system.css");
assert.ok(!oldCSS.includes("--crimson"));
assert.ok(!read("standings-engine.js").includes("rewards:"));
console.log("PASS: 11 states, 19 ladder boundaries, Mini rounding, floor, ceiling, mixed-tier reasons, tied crowns, shared render.");
