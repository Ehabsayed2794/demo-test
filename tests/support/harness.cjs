// Shared test bootstrap for tests/*.cjs — the common setup every test file
// in this suite repeats: the browser-global window shim plus the
// repo-relative require list. One small interface, zero product code,
// zero behavior of its own:
//
//   var Harness = require("./support/harness.cjs");
//   Harness.makeWindow();                                        // window shim
//   Harness.loadModules([...repo-relative paths...]);            // requires
//   var counter = Harness.createCounter();                      // PASS counting
//   var check = counter.check;
//   ...
//   counter.summary();   // prints "N passed, M failed", exit 1 on failure
//
// Deliberately bootstrap-only: each file's own fake Firestore, fake
// services, and scenario helpers stay in that file (unifying those
// would change what the tests prove — a separate, later decision).
// New test files should start from here instead of copying the block.
var path = require("path");

var REPO_ROOT = path.join(__dirname, "..", "..");

function makeWindow() {
  global.window = global;
  global.window.addEventListener = function () {};
  return global.window;
}

function loadModules(relPaths) {
  (relPaths || []).forEach(function (p) {
    require(REPO_ROOT + "/" + p);
  });
  return global;
}

function createCounter() {
  var pass = 0, fail = 0;
  return {
    check: function (label, cond) {
      if (cond) { console.log("PASS  " + label); pass++; }
      else { console.log("FAIL  " + label); fail++; }
    },
    summary: function () {
      console.log("\n" + pass + " passed, " + fail + " failed");
      process.exitCode = fail ? 1 : 0;
      return { pass: pass, fail: fail };
    }
  };
}

module.exports = {
  REPO_ROOT: REPO_ROOT,
  makeWindow: makeWindow,
  loadModules: loadModules,
  createCounter: createCounter
};
