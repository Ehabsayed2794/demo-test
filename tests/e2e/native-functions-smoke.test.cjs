// S42 — the Functions authority layer, verified end to end against the real
// Firebase Functions + Auth emulators.
//
// This is the only place the compiled Kotlin/JS actually runs, so it is the
// only test that can prove the three things S42 exists to prove:
//   1. the module loads and exports a callable the emulator discovers;
//   2. callable-auth denies an unauthenticated request before any work runs;
//   3. the REAL :engine runs under Node — a seeded deal yields 4 seats and
//      52 unique cards, exactly what the JVM golden tests assert. If the
//      multiplatform port ever drifts from the JVM engine, these counts move.
//
// Intentionally NOT part of `tests/*.cjs` (the fast default `npm test` suite):
// it needs the Functions emulator, which that suite never starts, plus a
// built :functions artifact. Run like the CI job does:
//
//   native/gradlew -p native :functions:jsProductionExecutableCompileSync
//   (cd native/functions && npm install)
//   firebase emulators:start --project demo-test-ci --only functions,auth
//   node tests/e2e/native-functions-smoke.test.cjs
//
// No silent skips: an unreachable emulator, a missing build, or a wrong count
// all exit non-zero.

const path = require("path");
const http = require("http");

const REPO_ROOT = path.join(__dirname, "..", "..");
const FUNCTIONS_HOST = "127.0.0.1", FUNCTIONS_PORT = 5001;
const AUTH_HOST = "127.0.0.1", AUTH_PORT = 9099;
// The CI emulator project (android.yml's own convention).
const PROJECT_ID = "demo-test-ci";
const FN_NAME = "engineSmoke";

const { initializeApp } = require(REPO_ROOT + "/node_modules/firebase/app");
const { getAuth, signInAnonymously, connectAuthEmulator } = require(REPO_ROOT + "/node_modules/firebase/auth");
const {
  getFunctions,
  httpsCallable,
  connectFunctionsEmulator,
} = require(REPO_ROOT + "/node_modules/firebase/functions");

let failures = 0;
function check(label, condition, detail) {
  if (condition) {
    console.log("  PASS  " + label);
  } else {
    failures++;
    console.log("  FAIL  " + label + (detail ? "  -- " + detail : ""));
  }
}

// Emulators answer any HTTP request (even 404) once running, so probe once
// with a bounded retry rather than racing many sockets.
function probePort(port, label, attempts) {
  return new Promise((resolve) => {
    let tries = 0;
    const attempt = () => {
      const req = http.get({ host: "127.0.0.1", port, path: "/", timeout: 1000 }, (res) => {
        res.resume();
        resolve(true);
      });
      req.on("error", () => {
        tries++;
        if (tries >= attempts) resolve(false);
        else setTimeout(attempt, 500);
      });
      req.on("timeout", () => {
        req.destroy();
        tries++;
        if (tries >= attempts) resolve(false);
        else setTimeout(attempt, 500);
      });
    };
    attempt();
  });
}

// The emulator's HTTP port answers BEFORE the Kotlin/JS module finishes
// loading and firebase-tools discovers its triggers: until discovery
// completes, every call returns functions/not-found with an empty registry
// ("valid functions are:" — observed for ~5s on a warm machine, longer on a
// cold CI runner). No port probe can see that, so this waits for the
// callable itself. The unauthenticated denial is both the readiness signal
// and check [1]'s assertion, so it returns the error code for the first
// response that is NOT not-found.
async function waitForTrigger(callable, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      await callable({});
      // An unauthenticated call succeeding would itself be a bug — let the
      // caller's check report it instead of looping here.
      return "callable-answered";
    } catch (err) {
      const code = (err && err.code) || "unknown";
      if (code !== "functions/not-found") return code;
      last = err;
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  throw new Error(
    "engineSmoke was never registered within " + timeoutMs +
    "ms (last error: " + (last && last.message) + "). The Functions emulator " +
    "port was open, so the Kotlin/JS module likely failed to load or failed " +
    "to export a callable the CLI discovers — read the emulator log.",
  );
}

async function main() {
  const up = await probePort(FUNCTIONS_PORT, "Functions emulator", 120);
  if (!up) {
    console.error("Functions emulator on " + FUNCTIONS_PORT + " is not reachable.");
    console.error("Start it first: firebase emulators:start --project " + PROJECT_ID + " --only functions,auth");
    process.exit(1);
  }
  console.log("Functions emulator is answering on " + FUNCTIONS_PORT + ".");

  // The client Auth SDK requires an apiKey to even initialize, but the Auth
  // emulator never validates it — connectAuthEmulator is what actually routes
  // traffic — so a fixed placeholder is enough. Never a real key in the repo.
  const app = initializeApp({ projectId: PROJECT_ID, apiKey: "emulator-only-placeholder" });
  const auth = getAuth(app);
  connectAuthEmulator(auth, "http://" + AUTH_HOST + ":" + AUTH_PORT, false);
  const functions = getFunctions(app, "us-central1");
  connectFunctionsEmulator(functions, FUNCTIONS_HOST, FUNCTIONS_PORT);
  const smoke = httpsCallable(functions, FN_NAME);

  // 1. An unauthenticated call must be denied before the engine ever runs.
  //    waitForTrigger also blocks until the module has loaded and registered.
  console.log("\n[1] unauthenticated engineSmoke is denied:");
  const denial = await waitForTrigger(smoke, 60000);
  check(
    "denies with the unauthenticated code",
    denial === "functions/unauthenticated",
    "got " + denial,
  );

  // 2. Signed in, the real engine runs under Node.
  console.log("\n[2] authenticated engineSmoke runs the real engine:");
  const cred = await signInAnonymously(auth);
  const uid = cred.user.uid;
  let result;
  try {
    result = (await smoke({})).data;
  } catch (err) {
    check("authenticated engineSmoke succeeds", false, (err && err.message) || String(err));
    console.error("\n" + failures + " check(s) failed.");
    process.exit(1);
  }
  check("authenticated engineSmoke succeeds", result && result.ok === true, JSON.stringify(result));
  check("the caller's verified uid is returned", result && result.uid === uid, "got " + (result && result.uid));
  check("deals 4 seats", result && result.seatCount === 4, "got " + (result && result.seatCount));
  check("deals 52 cards total", result && result.totalCards === 52, "got " + (result && result.totalCards));
  check("deals 52 UNIQUE cards", result && result.uniqueCards === 52, "got " + (result && result.uniqueCards));

  console.log("\n" + (failures === 0 ? "All engineSmoke checks passed." : failures + " check(s) failed."));
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error("Unexpected failure:", err);
  process.exit(1);
});
