// Match Completion UI sprint — browser QA harness. Reuses the SAME
// proven fake-Firestore/page-route scaffolding as
// scratchpad/verify-match-completion.cjs (prior sprint), extended with
// UI-specific assertions against #matchCompleteModal / MatchScreenDebug.
// NOT part of the repository.
//
// Approach: renderMatchCompletion() is a PURE renderer of the synced
// matchDoc (status/winnerIds/finalScores/...). Rather than driving a
// full round-by-round game to reach status:'complete' (expensive, and
// already exhaustively covered at the engine level by
// tests/match-completion.test.cjs), this harness seeds the fake
// Firestore document directly via __seedMatch() with hand-crafted
// matchDoc shapes and asserts what the UI renders — the same "direct
// call with production code executing in a real browser" technique the
// prior sprint's harness itself used for its ties/duplicate/race
// scenarios (see its own header comment, technique (2)).
const http = require("http");
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const ROOT = "/home/user/demo-test/design-ui";
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" };

const server = http.createServer((req, res) => {
  var p = path.join(ROOT, decodeURIComponent(req.url.split("?")[0]));
  fs.readFile(p, (err, data) => {
    if (err) { res.writeHead(404); res.end("not found: " + p); return; }
    var ext = path.extname(p);
    res.writeHead(200, { "Content-Type": MIME[ext] || "application/octet-stream" });
    res.end(data);
  });
});

var results = [];
function check(label, cond) { results.push({ label: label, ok: !!cond }); console.log((cond ? "PASS  " : "FAIL  ") + label); }
function info(label) { console.log("INFO  " + label); }

(async () => {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  var port = server.address().port;
  var base = "http://127.0.0.1:" + port;

  var browser = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium-1194/chrome-linux/chrome", args: ["--no-sandbox"] });
  var page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  page.on("pageerror", (err) => console.log("[pageerror] " + err.message));

  await page.addInitScript(() => {
    // HARNESS BUG FIX (same class as verify-rematch-vote.cjs's own fix):
    // addInitScript() re-runs on EVERY navigation within this page,
    // including the real reload a Return-to-Lobby navigation triggers —
    // unconditionally re-seeding sessionStorage here would silently
    // stomp that reload's own legitimately-persisted state right back
    // to the ORIGINAL fixture value, making a real navigation look like
    // it never happened. Guard: only seed the FIRST time this page/
    // context has never had this key at all.
    if (!sessionStorage.getItem("estimation_game_state_v1")) {
      sessionStorage.setItem("estimation_game_state_v1", JSON.stringify({
        current: "Gameplay", previous: "Lobby", history: [],
        data: {
          player: { id: "p1", name: "Test", avatar: "T", rank: "Gold", rp: 0, coins: 0, gems: 0 },
          account: { type: null, email: null }, room: { code: null, host: false, seats: [] },
          lastResult: null, match: { id: "test-match-1" }
        }
      }));
    }
  });

  function baseDoc(overrides) {
    return Object.assign({
      roomId: "room-1", players: ["p1-uid", "p2-uid", "p3-uid", "p4-uid"], status: "in_progress",
      currentRound: 5, maxRounds: 18, extendedRounds: [],
      dealer: "p1-uid", turn: "p1-uid",
      gameState: { initialized: false, todo: "placeholder" },
      seats: { p1: "p1-uid", p2: "p2-uid", p3: "p3-uid", p4: "p4-uid" },
      version: 1, biddingOpen: true, bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: []
    }, overrides || {});
  }

  var FIRESTORE_FAKE_SRC = `
    window.__STORE = { "matches/test-match-1": ${JSON.stringify(baseDoc())} };
    window.__VERSION = { "matches/test-match-1": 1 };
    window.__LISTENERS = {};
    window.__DOC_COUNTER = 0;
    function __notify(k) {
      (window.__LISTENERS[k] || []).forEach(function (cb) {
        var exists = Object.prototype.hasOwnProperty.call(window.__STORE, k);
        cb({ exists: exists, data: function () { return exists ? Object.assign({}, window.__STORE[k]) : undefined; } });
      });
    }
    // Real Firestore's own DocumentReference.collection(name).doc(id)
    // chaining, generalized over an arbitrary slash-joined path — this
    // is what Post-Match Rematch Vote sprint's matches/{matchId}/
    // rematchVote/current subcollection needs and the ORIGINAL shim
    // (pre-dating that sprint) never supported.
    function __makeRef(path) {
      var segs = path.split("/");
      return {
        id: segs[segs.length - 1], _key: path,
        get: function () {
          var exists = Object.prototype.hasOwnProperty.call(window.__STORE, path);
          return Promise.resolve({ exists: exists, data: function () { return exists ? Object.assign({}, window.__STORE[path]) : undefined; } });
        },
        onSnapshot: function (onNext) {
          window.__LISTENERS[path] = window.__LISTENERS[path] || [];
          window.__LISTENERS[path].push(onNext);
          // Existence/data re-checked AT DELIVERY TIME, not captured at
          // registration time — see verify-rematch-vote.cjs's own fix
          // comment for the exact bug this closes (a write committing
          // within this artificial delay was being clobbered by a
          // stale "didn't exist yet" snapshot arriving after it).
          setTimeout(function () { var exists = Object.prototype.hasOwnProperty.call(window.__STORE, path); onNext({ exists: exists, data: function () { return exists ? Object.assign({}, window.__STORE[path]) : undefined; } }); }, 5);
          return function unsubscribe() {
            window.__LISTENERS[path] = (window.__LISTENERS[path] || []).filter(function (cb) { return cb !== onNext; });
          };
        },
        collection: function (name) {
          return {
            doc: function (id) {
              if (id == null) { id = "auto" + (++window.__DOC_COUNTER); }
              return __makeRef(path + "/" + name + "/" + id);
            }
          };
        }
      };
    }
    function __resolveSentinels(data) {
      var out = {};
      Object.keys(data).forEach(function (k) {
        var v = data[k];
        out[k] = (v && v.__sentinel === "serverTimestamp") ? { __isTimestamp: true, toMillis: function () { return Date.now(); } } : v;
      });
      return out;
    }
    window.firebase.firestore = function () {
      return {
        collection: function (name) { return { doc: function (id) { if (id == null) id = "auto" + (++window.__DOC_COUNTER); return __makeRef(name + "/" + id); } }; },
        // A real, generalized transaction: get() records the version
        // seen; set()/update() queue a write; on commit, any key whose
        // version moved since it was read forces a retry (capped),
        // exactly mirroring real Firestore's own optimistic-concurrency
        // semantics — the same technique the original single-key shim
        // used, generalized to N arbitrary keys per transaction (needed
        // for createRematchMatch()'s own "create one doc + update
        // another, atomically" shape).
        runTransaction: function (fn, attempt) {
          attempt = attempt || 1;
          if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
          var seenVersions = {}, pending = {};
          var tx = {
            get: function (ref) { seenVersions[ref._key] = window.__VERSION[ref._key] || 0; return ref.get(); },
            set: function (ref, data) { pending[ref._key] = { ref: ref, mode: "set", data: data }; },
            update: function (ref, patch) { pending[ref._key] = { ref: ref, mode: "update", data: patch }; }
          };
          return Promise.resolve(fn(tx)).then(function (result) {
            var conflict = Object.keys(seenVersions).some(function (k) { return (window.__VERSION[k] || 0) !== seenVersions[k]; });
            if (conflict) return window.firebase.firestore().runTransaction(fn, attempt + 1);
            Object.keys(pending).forEach(function (k) {
              var entry = pending[k];
              var resolved = __resolveSentinels(entry.data);
              window.__STORE[k] = entry.mode === "set" ? resolved : Object.assign({}, window.__STORE[k], resolved);
              window.__VERSION[k] = (window.__VERSION[k] || 0) + 1;
            });
            Object.keys(pending).forEach(function (k) { __notify(k); });
            return result;
          });
        },
        FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } }
      };
    };
    window.firebase.firestore.FieldValue = { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } };
    window.__seedMatch = function (matchId, doc) {
      var k = "matches/" + matchId;
      var prevVersion = window.__VERSION[k] || 0;
      var seededVersion = Math.max(doc.version || 1, prevVersion + 1);
      var seededDoc = Object.assign({}, doc, { version: seededVersion });
      window.__STORE[k] = seededDoc;
      window.__VERSION[k] = seededVersion;
      __notify(k);
    };
  `;

  await page.route("https://www.gstatic.com/firebasejs/**", (route) => {
    var url = route.request().url();
    if (url.indexOf("firebase-app-compat.js") !== -1) {
      route.fulfill({ contentType: "text/javascript", body: "window.firebase = window.firebase || {}; window.firebase.initializeApp = function () {};" });
    } else if (url.indexOf("firebase-auth-compat.js") !== -1) {
      route.fulfill({ contentType: "text/javascript", body: "window.firebase.auth = function () { return { currentUser: null, onAuthStateChanged: function () {}, signInAnonymously: function () { return Promise.resolve({ user: { uid: 'p1-uid' } }); } }; };" });
    } else if (url.indexOf("firebase-firestore-compat.js") !== -1) {
      route.fulfill({ contentType: "text/javascript", body: FIRESTORE_FAKE_SRC });
    } else { route.continue(); }
  });
  await page.route("https://fonts.googleapis.com/**", (route) => route.fulfill({ contentType: "text/css", body: "" }));
  await page.route("https://fonts.gstatic.com/**", (route) => route.abort());

  await page.goto(base + "/match/index.html", { waitUntil: "load" });
  await page.waitForTimeout(500);

  await page.evaluate(() => {
    window.SessionService.__testForceUser = { uid: "p1-uid" };
    var orig = window.SessionService.getCurrentUser;
    window.SessionService.getCurrentUser = function () { return orig() || window.SessionService.__testForceUser; };
    window.MatchScreenDebug.setLocalSeatId("p1");
  });

  // ── Scenario A: active match (status:'in_progress') -> modal hidden.
  var openAfterActive = await page.evaluate(() => window.MatchScreenDebug.isMatchCompleteModalOpen());
  check("A. Active match: completion UI hidden", openAfterActive === false);

  // ── Scenario B: round complete but match NOT complete -> still hidden.
  await page.evaluate(() => {
    window.__seedMatch("test-match-1", Object.assign({}, window.__STORE["matches/test-match-1"], {
      status: "round_complete", currentRound: 6
    }));
  });
  await page.waitForTimeout(100);
  var openAfterRoundComplete = await page.evaluate(() => window.MatchScreenDebug.isMatchCompleteModalOpen());
  check("B. Round-complete (match not complete): completion UI hidden", openAfterRoundComplete === false);

  // ── Scenario C/D: normal completion, single King.
  await page.evaluate(() => {
    window.__seedMatch("test-match-1", Object.assign({}, window.__STORE["matches/test-match-1"], {
      status: "complete", completedRound: 18, winnerIds: ["p1"],
      finalScores: { p1: 145, p2: 90, p3: 60, p4: 30 }
    }));
  });
  await page.waitForTimeout(150);
  var stateC = await page.evaluate(() => ({
    open: window.MatchScreenDebug.isMatchCompleteModalOpen(),
    label: document.getElementById("mcKingLabel").textContent.trim(),
    names: document.getElementById("mcKingNames").textContent.trim(),
    modalCount: document.querySelectorAll("#matchCompleteModal").length
  }));
  check("C. Normal completion: completion UI shown", stateC.open === true);
  check("D. Single King: label says 'King' (singular)", /^King$/i.test(stateC.label));
  info("D. #mcKingNames raw text: '" + stateC.names + "'");
  check("D. Single King: winner identity is rendered (non-empty, not placeholder)", !!stateC.names && stateC.names !== "—");

  // ── Scenario Q/R: primary action + King/Kings result are actually
  // visible on screen (not just present in the DOM) — real bounding
  // box check, not merely "hidden attribute absent".
  var visibility = await page.evaluate(() => {
    function isVisible(el) {
      if (!el) return false;
      var r = el.getBoundingClientRect();
      var cs = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" && cs.display !== "none";
    }
    return {
      lobbyBtnVisible: isVisible(document.getElementById("mcLobbyBtn")),
      kingLabelVisible: isVisible(document.getElementById("mcKingLabel")),
      kingNamesVisible: isVisible(document.getElementById("mcKingNames"))
    };
  });
  check("Q. Primary action (Return to Lobby) is visible", visibility.lobbyBtnVisible === true);
  check("R. King/Kings result is visible", visibility.kingLabelVisible === true && visibility.kingNamesVisible === true);

  // ── Scenario H: no false winner — p2/p3/p4 not marked as King.
  var kingBadges = await page.evaluate(() => {
    var rows = Array.prototype.slice.call(document.querySelectorAll("#mcScoreboard .mc-row"));
    return rows.map((r) => ({ text: r.textContent, isKing: r.classList.contains("is-king") }));
  });
  var kingRowCount = kingBadges.filter((r) => r.isKing).length;
  check("H. No false winner: exactly 1 seat marked King", kingRowCount === 1);

  // ── Scenario I: scoreboard has all 4 seats with correct scores.
  var scoreboardText = await page.evaluate(() => document.getElementById("mcScoreboard").textContent);
  check("I. Scoreboard shows all 4 seats' scores", ["145", "90", "60", "30"].every((s) => scoreboardText.indexOf(s) !== -1));

  // ── Scenario J: repeated identical snapshot delivery -> no duplicate DOM.
  await page.evaluate(() => {
    // re-notify listeners with the SAME doc/version (simulates a
    // duplicate delivery, e.g. a reconnect replay) without bumping version.
    var k = "matches/test-match-1";
    (window.__LISTENERS[k] || []).forEach(function (cb) {
      cb({ exists: true, data: function () { return Object.assign({}, window.__STORE[k]); } });
    });
  });
  await page.waitForTimeout(100);
  var modalCountAfterRepeat = await page.evaluate(() => document.querySelectorAll("#matchCompleteModal").length);
  check("H. Repeated identical snapshot: no duplicate #matchCompleteModal", modalCountAfterRepeat === 1);

  // ── Scenario S: no accidental second overlay open simultaneously.
  var openModalCount = await page.evaluate(() => document.querySelectorAll(".ui-modal-backdrop.is-open").length);
  check("S. No accidental second modal open simultaneously", openModalCount === 1);

  // ── Scenario E/F/G: 2-way, 3-way, 4-way ties.
  async function seedTie(winnerIds, finalScores) {
    await page.evaluate((args) => {
      var k = "matches/test-match-1";
      window.__seedMatch("test-match-1", Object.assign({}, window.__STORE[k], {
        status: "complete", winnerIds: args.winnerIds, finalScores: args.finalScores, completedRound: 18
      }));
    }, { winnerIds, finalScores });
    await page.waitForTimeout(120);
    return page.evaluate(() => ({
      label: document.getElementById("mcKingLabel").textContent.trim(),
      names: document.getElementById("mcKingNames").textContent.trim(),
      kingRows: Array.prototype.slice.call(document.querySelectorAll("#mcScoreboard .mc-row")).filter((r) => r.classList.contains("is-king")).length
    }));
  }

  var stateE = await seedTie(["p1", "p2"], { p1: 100, p2: 100, p3: 80, p4: 70 });
  check("E. 2-way tie: label says 'Kings' (plural)", /kings/i.test(stateE.label));
  check("E. 2-way tie: exactly 2 King rows marked", stateE.kingRows === 2);

  var stateF = await seedTie(["p1", "p2", "p3"], { p1: 100, p2: 100, p3: 100, p4: 70 });
  check("F. 3-way tie: label says 'Kings' (plural)", /kings/i.test(stateF.label));
  check("F. 3-way tie: exactly 3 King rows marked", stateF.kingRows === 3);

  var stateG = await seedTie(["p1", "p2", "p3", "p4"], { p1: 100, p2: 100, p3: 100, p4: 100 });
  check("G. 4-way tie: label says 'Kings' (plural)", /kings/i.test(stateG.label));
  check("G. 4-way tie: exactly 4 King rows marked", stateG.kingRows === 4);

  // ── Scenario L: Play Again is now REAL (Post-Match Rematch Vote
  // sprint) — the old disabled-button element no longer exists by
  // design (see match/index.html's own comment at this removal).
  // Superseded by the dedicated verify-rematch-vote.cjs harness; this
  // check now only confirms the old element was deliberately removed,
  // not left as stale dead markup.
  var oldPlayAgainBtnGone = await page.evaluate(() => document.getElementById("mcPlayAgainBtn") === null);
  check("L. Old disabled Play Again button removed (superseded by real rematch vote)", oldPlayAgainBtnGone === true);

  // ── Scenario N: Super Call extension — absent extendedRounds -> section hidden.
  var extHiddenWhenEmpty = await page.evaluate(() => document.getElementById("mcExtension").hidden);
  check("N. No extension data: #mcExtension hidden (not fabricated)", extHiddenWhenEmpty === true);

  await page.evaluate(() => {
    window.__seedMatch("test-match-1", Object.assign({}, window.__STORE["matches/test-match-1"], {
      status: "complete", winnerIds: ["p1"], finalScores: { p1: 100, p2: 90, p3: 80, p4: 70 },
      completedRound: 20, maxRounds: 20, extendedRounds: [{ triggeredAtRound: 18, newMaxRounds: 20 }]
    }));
  });
  await page.waitForTimeout(120);
  var extShownWhenPresent = await page.evaluate(() => document.getElementById("mcExtension").hidden === false);
  check("N. Extension data present: #mcExtension shown", extShownWhenPresent === true);

  // ── Scenario M: landscape viewports — no horizontal overflow, no clipped buttons.
  var viewports = [[800, 480], [854, 480], [1280, 720]];
  var overflowResults = [];
  for (var vp of viewports) {
    await page.setViewportSize({ width: vp[0], height: vp[1] });
    await page.waitForTimeout(80);
    var r = await page.evaluate(() => {
      var box = document.querySelector("#matchCompleteModal .ui-modal-box");
      var rect = box ? box.getBoundingClientRect() : null;
      var btns = Array.prototype.slice.call(document.querySelectorAll(".mc-actions button"));
      var btnRects = btns.map((b) => b.getBoundingClientRect());
      var hOverflow = document.documentElement.scrollWidth > window.innerWidth + 1;
      var clipped = btnRects.some((br) => br.width === 0 || br.height === 0);
      var withinViewport = rect ? (rect.right <= window.innerWidth + 1 && rect.left >= -1) : false;
      return { hOverflow, clipped, withinViewport, hasRect: !!rect };
    });
    overflowResults.push({ vp, r });
  }
  overflowResults.forEach(({ vp, r }) => {
    check("M. Viewport " + vp[0] + "x" + vp[1] + ": no horizontal overflow", r.hOverflow === false);
    check("M. Viewport " + vp[0] + "x" + vp[1] + ": action buttons not clipped", r.clipped === false);
    check("M. Viewport " + vp[0] + "x" + vp[1] + ": modal box within viewport", r.withinViewport === true);
  });

  // ── Scenario K: Return to Lobby uses existing navigation. Run LAST —
  // goTo() performs a real window.location.href navigation (per
  // game-state.js's own goTo() implementation), so the match screen's
  // DOM/globals are gone afterward; nothing else can run after this.
  await page.evaluate(() => { document.getElementById("mcLobbyBtn").click(); });
  await page.waitForTimeout(150);
  var currentScreen = await page.evaluate(() => JSON.parse(sessionStorage.getItem("estimation_game_state_v1")).current).catch(() => null);
  check("K. Return to Lobby: GameState navigates to Lobby", currentScreen === "Lobby");

  console.log("\n=== RESULTS ===\n");
  var passed = results.filter((r) => r.ok).length;
  var failed = results.filter((r) => !r.ok);
  console.log(passed + " passed, " + failed.length + " failed");
  if (failed.length) { console.log("\nFAILED:"); failed.forEach((f) => console.log(" - " + f.label)); }

  await browser.close();
  server.close();
  process.exit(failed.length ? 1 : 0);
})();
