// T-003 regression test: the signup double-bootstrap race.
//
// ROOT CAUGHT BY THIS TEST (T-003, 2026-09-19): creating an account signs
// the user in immediately, so login/index.html's create handler AND its
// onAuthStateChanged listener BOTH called bootstrapProfile() for the same
// brand-new uid. Two concurrent ensurePlayerProfile() transactions each
// read players/{uid} as missing, so each tx.set() carried an exists:false
// precondition and the loser committed into 409 ALREADY_EXISTS — on every
// signup, in production. Worse, when the auth-state path won outright it
// derived the profile before updateProfile() landed and persisted
// displayName "Player" permanently.
//
// The fix (design-ui/login/index.html): the form handlers set
// formFlowInFlight synchronously — before the first await, so it is already
// true when the auth-state event fires — and onAuthStateChanged defers,
// leaving the form as the sole bootstrap caller.
//
// WHY THIS TEST EXISTS: the existing 51-file suite signed in with a direct
// createUserWithEmailAndPassword() (see reconnect.e2e.test.cjs:498) and
// never touched the real create form, so the race had NO coverage and
// survived. This drives the actual form.
//
// Covers:
//   R1  no 409 across N fresh signups (the race is dead)
//   R2  exactly one :commit per signup (no duplicate bootstrap)
//   R3  the TYPED display name is what gets persisted (the silent-corruption
//       half of the same bug — a fix that only kills the 409 can regress
//       this, and the first attempt at the fix did exactly that)
//   R4  lastSeenAt is present (the returning-user contract still holds)
//   R5  the flow still navigates to /lobby/ (no regression on happy path)
//
// Emulator + browser required; exits non-zero (never silently 0) if either
// is unavailable. NOTE: deliberately avoids the literal word "SKIPPED" —
// scripts/run-tests.mjs hard-fails the whole run on it.
var http = require("http");
var fs = require("fs");
var path = require("path");
var { initializeTestEnvironment } = require("@firebase/rules-unit-testing");
var chromium = require("playwright").chromium;
var resolveChromiumExecutablePath = require("../scripts/resolve-chromium.cjs").resolveChromiumExecutablePath;

var REPO_ROOT = path.resolve(__dirname, "..");
var ROOT = path.resolve(REPO_ROOT, "design-ui"); // source, not the built
// artifact — matches reconnect.e2e.test.cjs, so this runs in CI without a
// build step and fails the moment login/index.html regresses at the source.
var CDN_CACHE = path.join(REPO_ROOT, "tests", "fixtures", "firebase-cdn");
var MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml" };
var HTTP_PORT = Number(process.env.SIGNUP_RACE_PORT || 5263);
var FIRESTORE_HOST = "127.0.0.1", FIRESTORE_PORT = 8080;
var AUTH_HOST = "127.0.0.1", AUTH_PORT = 9099;
var PROJECT_ID = "made---estimation-card-game";
var SIGNUPS = Number(process.env.SIGNUP_RACE_N || 5);
var TIME_BUDGET_MS = 150000; // 5 signups in well under this; the runner's
// own per-file cap is 3min, and this guard turns a hang into a diagnosed
// failure rather than a silent timeout.
var CDN_MAP = {
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js": "firebase-app-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js": "firebase-auth-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore-compat.js": "firebase-firestore-compat.js"
};

var pass = 0, fail = 0;
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + String(note).slice(0, 300) : "")); fail++; }
}
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

function startServer() {
  return new Promise(function (resolve) {
    var server = http.createServer(function (req, res) {
      var urlPath = decodeURIComponent(req.url.split("?")[0]);
      if (urlPath === "/") urlPath = "/login/index.html";
      var filePath = path.resolve(ROOT, "." + urlPath);
      if (filePath !== ROOT && filePath.indexOf(ROOT + path.sep) !== 0) { res.writeHead(403); res.end(); return; }
      fs.stat(filePath, function (err, st) {
        if (err || !st.isFile()) { res.writeHead(404); res.end(); return; }
        res.writeHead(200, { "Content-Type": MIME[path.extname(filePath)] || "application/octet-stream" });
        fs.createReadStream(filePath).pipe(res);
      });
    });
    server.listen(HTTP_PORT, function () { resolve(server); });
  });
}

async function installRedirect(page) {
  for (var cdnUrl in CDN_MAP) {
    (function (url, file) {
      page.route(url, function (route) {
        route.fulfill({ status: 200, contentType: "text/javascript", body: fs.readFileSync(path.join(CDN_CACHE, file), "utf8") });
      });
    })(cdnUrl, CDN_MAP[cdnUrl]);
  }
  // The trailing "*" is load-bearing when serving the BUILT artifact: the
  // build cache-busts local assets so the URL is firebase-init.js?v=<sha>,
  // and "**/firebase-init.js" would not match it — silently pointing the
  // page at production instead of the emulator. Kept here for that reason.
  await page.route("**/firebase-init.js*", async function (route) {
    var body = fs.readFileSync(path.join(ROOT, "firebase-init.js"), "utf8");
    var injected = body.replace(
      "window.Db = (typeof firebase.firestore === \"function\") ? firebase.firestore() : null;",
      "window.Db = (typeof firebase.firestore === \"function\") ? firebase.firestore() : null;\n" +
      "  if (window.Db) window.Db.useEmulator(\"" + FIRESTORE_HOST + "\", " + FIRESTORE_PORT + ");\n" +
      "  if (window.Auth) window.Auth.useEmulator(\"http://" + AUTH_HOST + ":" + AUTH_PORT + "\");"
    );
    await route.fulfill({ status: 200, contentType: "text/javascript", body: injected });
  });
}

// Dig the resolved connection host out of the live SDK object — the guard
// must check what the page will ACTUALLY talk to, not what we intended.
function resolvedDbHost(page) {
  return page.evaluate(function () {
    function dig(o, depth) {
      if (!o || typeof o !== "object" || depth > 4) return null;
      if (typeof o.host === "string") return o.host;
      for (var k in o) {
        if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
        var v = dig(o[k], depth + 1);
        if (v) return v;
      }
      return null;
    }
    return window.Db ? dig(window.Db, 0) : null;
  }).catch(function () { return null; });
}

async function main() {
  var server = null, browser = null;
  // Convert any hang into a diagnosed failure instead of a silent timeout
  // (mirrors reconnect.e2e.test.cjs's own guard).
  var abortTimer = setTimeout(function () {
    console.log("ABORT: time budget exceeded -- forcing exit, not a hang.");
    try { if (server) server.close(); } catch (e) {}
    if (browser) { try { browser.close().catch(function () {}); } catch (e) {} }
    setTimeout(function () { process.exit(1); }, 1000);
  }, TIME_BUDGET_MS + 10000);

  var rulesText;
  try { rulesText = fs.readFileSync(path.join(REPO_ROOT, "firestore.rules"), "utf8"); }
  catch (e) { clearTimeout(abortTimer); console.log("CANNOT READ firestore.rules: " + e.message); process.exitCode = 2; return; }

  var bootEnv;
  try {
    bootEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: { rules: rulesText, host: FIRESTORE_HOST, port: FIRESTORE_PORT }
    });
  } catch (e) {
    clearTimeout(abortTimer);
    console.log("EMULATOR UNREACHABLE (bootstrap): " + e.message);
    process.exitCode = 2; return;
  }
  await bootEnv.cleanup();

  server = await startServer();
  try {
    browser = await chromium.launch({ executablePath: resolveChromiumExecutablePath() });
  } catch (e) {
    clearTimeout(abortTimer);
    console.log("BROWSER LAUNCH FAILED: " + e.message);
    try { if (server) server.close(); } catch (ee) {}
    process.exitCode = 2; return;
  }

  var BASE = "http://127.0.0.1:" + HTTP_PORT;
  var results = [];

  for (var i = 0; i < SIGNUPS; i++) {
    var ctx = await browser.newContext();
    var page = await ctx.newPage();
    await installRedirect(page);

    var commits = [];
    page.on("response", function (res) {
      if (/:commit$/.test(res.url())) commits.push(res.status());
    });
    page.on("dialog", function (d) { d.dismiss().catch(function () {}); });

    var typed = "Race" + i;
    var uid = null, profile = null;
    try {
      await page.goto(BASE + "/login/index.html", { waitUntil: "load", timeout: 60000 });
      await sleep(400);

      // FAIL-CLOSED: if the resolved host is not the emulator, this signup
      // flood would be writing to production. Stop before any submit.
      var host = await resolvedDbHost(page);
      if (host !== FIRESTORE_HOST + ":" + FIRESTORE_PORT) {
        console.log("ABORT (fail-closed): resolved Db host is " + host + ", expected " + FIRESTORE_HOST + ":" + FIRESTORE_PORT);
        await ctx.close().catch(function () {});
        clearTimeout(abortTimer);
        try { if (server) server.close(); } catch (e) {}
        await browser.close().catch(function () {});
        process.exitCode = 2;
        return;
      }

      await page.fill("#displayName", typed);
      await page.fill("#createEmail", "signup-race-" + Date.now() + "-" + i + "@test.local");
      await page.fill("#createPassword", "TestPass123!");
      await page.click("#createForm .primary-btn");

      // The form's proceedToLobby() navigates to /lobby/. The uid becomes
      // available the moment the credential resolves — BEFORE that
      // navigation — so wait for the URL to settle before reading it,
      // otherwise R5 samples a stale URL and reports a phantom failure.
      try {
        await page.waitForURL(/\/lobby\/index\.html/, { timeout: 20000 });
      } catch (e) { /* navigation didn't land; R5 reports it precisely */ }

      for (var w = 0; w < 50 && !uid; w++) {
        await sleep(200);
        uid = await page.evaluate(function () {
          if (window.Auth && window.Auth.currentUser) return window.Auth.currentUser.uid;
          if (window.SessionService) { var u = window.SessionService.getCurrentUser(); if (u) return u.uid; }
          return null;
        }).catch(function () { return null; });
      }

      for (var p = 0; p < 20 && !profile; p++) {
        profile = await page.evaluate(async function (uid) {
          var pr = await window.PlayerService.getPlayerProfile(uid);
          // Timestamps serialize as {} over the wire; presence is the signal.
          return pr ? { displayName: pr.displayName, lastSeenAt: pr.lastSeenAt ? "present" : "absent" } : null;
        }, uid).catch(function () { return null; });
        if (!profile) await sleep(200);
      }

      results.push({
        commits: commits.slice(), n409: commits.filter(function (s) { return s === 409; }).length,
        uid: uid, profile: profile, typed: typed,
        onLobby: /\/lobby\/index\.html/.test(page.url())
      });
    } catch (err) {
      results.push({ commits: commits.slice(), n409: -1, uid: null, profile: null, typed: typed,
        onLobby: false, error: err.message || String(err) });
    } finally {
      await ctx.close().catch(function () {});
    }
  }

  clearTimeout(abortTimer);
  try { if (server) server.close(); } catch (e) {}
  await browser.close().catch(function () {});

  // Vacuous-pass guard: if no signup produced a uid, the harness never
  // reached the emulator and every check below would pass meaninglessly.
  var attempted = results.filter(function (r) { return r.uid; }).length;
  check("harness reached the emulator (signups resolved: " + attempted + "/" + results.length + ")",
    attempted === results.length,
    results.map(function (r) { return r.error || "no uid"; }).filter(Boolean).slice(0, 3).join(" | "));

  var total409 = results.reduce(function (a, r) { return a + Math.max(0, r.n409); }, 0);
  check("R1 no 409 ALREADY_EXISTS across " + results.length + " fresh signups", total409 === 0,
    results.map(function (r, i) { return i + ":" + JSON.stringify(r.commits); }).join(" "));

  // Exactly one commit per signup is the strongest form of the assertion:
  // it proves the SECOND bootstrap call no longer happens at all.
  var doubled = results.map(function (r, i) { return r.commits.length !== 1 ? i + "=" + r.commits.length : null; })
                       .filter(function (x) { return x !== null; });
  check("R2 exactly one :commit per signup (no duplicate bootstrap)", doubled.length === 0, doubled.join(","));

  var wrongName = results.map(function (r, i) {
    return (r.profile && r.profile.displayName === r.typed) ? null : i + "=" + (r.profile ? r.profile.displayName : "no-profile");
  }).filter(function (x) { return x !== null; });
  check("R3 the TYPED display name is persisted (not \"Player\")", wrongName.length === 0, wrongName.join(","));

  var noLastSeen = results.map(function (r, i) {
    return (r.profile && r.profile.lastSeenAt === "present") ? null : i;
  }).filter(function (x) { return x !== null; });
  check("R4 lastSeenAt present (returning-user contract intact)", noLastSeen.length === 0, noLastSeen.join(","));

  var navigated = results.filter(function (r) { return r.onLobby; }).length;
  check("R5 every signup still navigates to /lobby/", navigated === results.length, navigated + "/" + results.length);

  console.log("\n" + pass + " passed, " + fail + " failed");
  if (fail) {
    console.log("\n--- per-signup commits ---");
    results.forEach(function (r, i) { console.log("signup " + i + ": commits=" + JSON.stringify(r.commits) + " 409s=" + r.n409 + " name=" + (r.profile ? r.profile.displayName : "-") + (r.error ? " ERROR " + r.error : "")); });
  }
  process.exitCode = fail ? 1 : 0;
}

main().catch(function (e) {
  console.log("HARNESS CRASHED: " + ((e && e.stack) || e));
  try { process.exit(3); } catch (ee) { process.exitCode = 3; }
});
module.exports = {};
