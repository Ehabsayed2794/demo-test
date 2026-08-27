var REPO_ROOT = require("path").join(__dirname, "..");

// Regression for the round-advance failure guard. This uses the real adapter
// through startTrickSync(); only Firestore subscription and service calls are
// mocked so the test isolates orchestration retry semantics.
global.window = global;
global.window.addEventListener = function () {};

global.TableEngine = {
  getState: function () { return { phase: "DONE", round: 1 }; },
  resolveTrick: function () {}
};

global.GameSession = null;
global.ScoringEngine = null;

var delivery;
var calls = 0;
var outcomes = [
  Promise.reject(Object.assign(new Error("temporary round failure"), { code: "ROUND_NOT_COMPLETE" })),
  Promise.resolve({ advanced: true })
];

global.MatchService = {
  subscribeToMatch: function (matchId, callback) {
    delivery = callback;
    return function () {};
  },
  advanceToNextRound: function () {
    calls++;
    return outcomes.shift() || Promise.resolve({ advanced: true });
  }
};

require(REPO_ROOT + "/design-ui/match-adapter.js");
var MatchAdapter = global.MatchAdapter;
var pass = 0;
var fail = 0;
function check(label, condition) {
  if (condition) {
    console.log("PASS  " + label);
    pass++;
  } else {
    console.log("FAIL  " + label);
    fail++;
  }
}
function flush() {
  return new Promise(function (resolve) { setImmediate(resolve); });
}

(async function run() {
  MatchAdapter.startTrickSync("m-retry", "p1");

  delivery({ cardLog: [], version: 1 });
  await flush();
  await flush();
  check("failed attempt is made once", calls === 1);

  // A later eligible delivery must retry the same round after the rejection.
  delivery({ cardLog: [], version: 2 });
  await flush();
  await flush();
  check("later delivery retries the same failed round", calls === 2);

  // Once the retry succeeds, further deliveries remain effectively single-attempt.
  delivery({ cardLog: [], version: 3 });
  await flush();
  await flush();
  check("successful retry remains idempotent", calls === 2);

  console.log("=== RESULTS ===");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail ? 1 : 0;
})().catch(function (error) {
  console.error(error);
  process.exitCode = 1;
});
