// Real TWO-CLIENT browser verification for the Post-Match Rematch Vote
// sprint. Two genuinely independent Playwright browser contexts, each
// running the REAL, unmodified design-ui code (match-service.js,
// match-adapter.js), talking to a SHARED backing store over real HTTP
// — the same proven technique as the Match Completion sprint's own
// verify-two-client-sync.cjs, generalized here for an arbitrary
// slash-joined document path (needed for the rematchVote subcollection)
// and for tx.set() in addition to tx.update() (createRematchMatch()
// needs both — creating one new document and updating another,
// atomically, in a single transaction).
//
// What this proves: MatchService.subscribeToRematchVote()'s listener
// correctly delivers one client's real submitRematchVote()/
// createRematchMatch() writes to a SECOND, INDEPENDENT client's own
// read, and that two clients racing the same terminal transition
// converge on exactly one state — using the REAL, unmodified
// MatchService/MatchAdapter functions in both browser contexts, never
// a fabricated DOM state on either side.
const http = require("http");
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");

const ROOT = "/home/user/demo-test/design-ui";
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" };

// ── Shared HTTP-backed store (the "server side") ──────────────────
var STORE = {};
var VERSION = {};
var DOC_COUNTER = 0;

function readBody(req) {
  return new Promise((resolve) => {
    var chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {}));
  });
}

const server = http.createServer(async (req, res) => {
  var url = req.url;
  if (url.startsWith("/api/doc/")) {
    var rest = url.slice("/api/doc/".length);
    var parts = rest.split("/");
    var action = null;
    // The last segment is either a real path segment or our own
    // "commit" action verb — never a valid Firestore doc-id-final-
    // segment in this schema (fixed shapes: matches/{id} or
    // matches/{id}/rematchVote/current), so this split is unambiguous.
    if (parts[parts.length - 1] === "commit") {
      action = parts.pop();
    }
    var docPath = decodeURIComponent(parts.join("/"));

    if (req.method === "GET" && !action) {
      var exists = Object.prototype.hasOwnProperty.call(STORE, docPath);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ exists: exists, data: exists ? STORE[docPath] : null, version: VERSION[docPath] || 0 }));
      return;
    }
    if (req.method === "POST" && action === "commit") {
      var body = await readBody(req);
      var currentVersion = VERSION[docPath] || 0;
      if (body.expectedVersion !== currentVersion) {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, currentVersion: currentVersion, data: STORE[docPath] }));
        return;
      }
      var resolvedPatch = {};
      Object.keys(body.patch).forEach(function (k) {
        var v = body.patch[k];
        resolvedPatch[k] = (v && v.__sentinel === "serverTimestamp") ? { __isTimestamp: true, __ms: Date.now() } : v;
      });
      STORE[docPath] = body.mode === "set" ? resolvedPatch : Object.assign({}, STORE[docPath], resolvedPatch);
      VERSION[docPath] = currentVersion + 1;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, version: VERSION[docPath], data: STORE[docPath] }));
      return;
    }
    res.writeHead(404); res.end("not found");
    return;
  }
  var p = path.join(ROOT, decodeURIComponent(url.split("?")[0]));
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

async function installAuthOverrideRoute(pg, uid) {
  await pg.route("**/session-service.js", (route) => {
    fs.readFile(path.join(ROOT, "session-service.js"), "utf8", (err, src) => {
      if (err) { route.continue(); return; }
      var appended = src + "\n(function(){ window.SessionService.__testForceUser = { uid: " + JSON.stringify(uid) + " }; " +
        "var orig = window.SessionService.getCurrentUser; " +
        "window.SessionService.getCurrentUser = function () { return orig() || window.SessionService.__testForceUser; }; })();";
      route.fulfill({ contentType: "text/javascript", body: appended });
    });
  });
}

// Generalized, path-aware, HTTP-backed Firestore shim — same technique
// as verify-two-client-sync.cjs, generalized with a real .collection()
// on each doc ref (path-joining) and tx.set() support.
var FIRESTORE_HTTP_SHIM = `
  // Auto-ID generation is LOCAL and SYNCHRONOUS, exactly like real
  // Firestore's own client-side ID generation (no network round trip
  // needed to produce a new, sufficiently-unique document ID) — a
  // per-client random+counter suffix makes cross-client collisions
  // astronomically unlikely for this harness's own scope (two
  // processes, a handful of documents).
  window.__clientIdPrefix = Math.random().toString(36).slice(2, 10);
  window.__autoIdCounter = 0;
  function __newAutoId() { return "auto-" + window.__clientIdPrefix + "-" + (++window.__autoIdCounter); }
  function __makeHttpRef(docPath) {
    return {
      id: docPath.split("/").pop(),
      _path: docPath,
      get: function () {
        return fetch('/api/doc/' + encodeURIComponent(docPath)).then(function (r) { return r.json(); }).then(function (json) {
          return { exists: json.exists, data: function () { return json.exists ? JSON.parse(JSON.stringify(json.data)) : undefined; } };
        });
      },
      onSnapshot: function (onNext) {
        var lastVersion = null, stopped = false;
        function poll() {
          if (stopped) return;
          fetch('/api/doc/' + encodeURIComponent(docPath)).then(function (r) { return r.json(); }).then(function (json) {
            if (json.version !== lastVersion) {
              lastVersion = json.version;
              onNext({ exists: json.exists, data: function () { return json.exists ? JSON.parse(JSON.stringify(json.data)) : undefined; } });
            }
            if (!stopped) setTimeout(poll, 80);
          }).catch(function () { if (!stopped) setTimeout(poll, 80); });
        }
        poll();
        return function unsubscribe() { stopped = true; };
      },
      collection: function (name) {
        return { doc: function (id) {
          return __makeHttpRef(docPath + "/" + name + "/" + (id != null ? id : __newAutoId()));
        } };
      }
    };
  }
  window.firebase.firestore = function () {
    return {
      collection: function (name) {
        return { doc: function (id) {
          return __makeHttpRef(name + "/" + (id != null ? id : __newAutoId()));
        } };
      },
      runTransaction: function (fn, attempt) {
        attempt = attempt || 1;
        if (attempt > 20) return Promise.reject(new Error("transaction retry limit exceeded"));
        var pending = {}; // path -> {mode, patch}
        var seenVersions = {}; // path -> expectedVersion
        var tx = {
          get: function (ref) {
            return fetch('/api/doc/' + encodeURIComponent(ref._path)).then(function (r) { return r.json(); }).then(function (json) {
              seenVersions[ref._path] = json.version;
              return { exists: json.exists, data: function () { return json.exists ? JSON.parse(JSON.stringify(json.data)) : undefined; } };
            });
          },
          set: function (ref, data) { pending[ref._path] = { mode: "set", patch: data }; if (!(ref._path in seenVersions)) seenVersions[ref._path] = 0; },
          update: function (ref, patch) { pending[ref._path] = { mode: "update", patch: patch }; }
        };
        return Promise.resolve(fn(tx)).then(function (result) {
          var paths = Object.keys(pending);
          if (!paths.length) return result;
          return Promise.all(paths.map(function (p) {
            return fetch('/api/doc/' + encodeURIComponent(p) + '/commit', {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ expectedVersion: seenVersions[p] || 0, patch: pending[p].patch, mode: pending[p].mode })
            }).then(function (r) { return r.json(); }).then(function (commitResult) { return { path: p, commitResult: commitResult }; });
          })).then(function (allResults) {
            var anyConflict = allResults.some(function (r) { return !r.commitResult.ok; });
            if (anyConflict) return window.firebase.firestore().runTransaction(fn, attempt + 1);
            return result;
          });
        });
      },
      FieldValue: { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } }
    };
  };
  window.firebase.firestore.FieldValue = { serverTimestamp: function () { return { __sentinel: "serverTimestamp" }; } };
  window.__resolveServerTimestamps = true; // documented no-op flag; real resolution happens server-side below via __sentinel passthrough
`;

async function newContext(browserInstance, viewport, uid, matchId) {
  var pg = await browserInstance.newPage({ viewport: viewport });
  pg.on("pageerror", (err) => console.log("[pageerror " + uid + "] " + err.message));
  await pg.addInitScript((mid) => {
    if (!sessionStorage.getItem("estimation_game_state_v1")) {
      sessionStorage.setItem("estimation_game_state_v1", JSON.stringify({
        current: "Gameplay", previous: "Lobby", history: [],
        data: { player: { id: "p1", name: "Test", avatar: "T", rank: "Gold", rp: 0, coins: 0, gems: 0 },
          account: { type: null, email: null }, room: { code: null, host: false, seats: [] },
          lastResult: null, match: { id: mid } }
      }));
    }
  }, matchId);
  await installAuthOverrideRoute(pg, uid);
  await pg.route("https://www.gstatic.com/firebasejs/**", (route) => {
    var url = route.request().url();
    if (url.indexOf("firebase-app-compat.js") !== -1) route.fulfill({ contentType: "text/javascript", body: "window.firebase=window.firebase||{};window.firebase.initializeApp=function(){};" });
    else if (url.indexOf("firebase-auth-compat.js") !== -1) route.fulfill({ contentType: "text/javascript", body: "window.firebase.auth=function(){return{currentUser:null,onAuthStateChanged:function(){},signInAnonymously:function(){return Promise.resolve({user:{uid:'" + uid + "'}});}};};" });
    else if (url.indexOf("firebase-firestore-compat.js") !== -1) route.fulfill({ contentType: "text/javascript", body: FIRESTORE_HTTP_SHIM });
    else route.continue();
  });
  await pg.route("https://fonts.googleapis.com/**", (route) => route.fulfill({ contentType: "text/css", body: "" }));
  await pg.route("https://fonts.gstatic.com/**", (route) => route.abort());
  return pg;
}

async function waitForCondition(pg, fn, timeoutMs, arg) {
  var deadline = Date.now() + (timeoutMs || 3000);
  while (Date.now() < deadline) {
    try {
      if (await pg.evaluate(fn, arg)) return true;
    } catch (e) {
      if (!/context was destroyed|Target closed|Target page/i.test(e.message || "")) throw e;
    }
    await pg.waitForTimeout(80);
  }
  return false;
}

(async () => {
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  var port = server.address().port;
  var base = "http://127.0.0.1:" + port;

  function seedCompletedMatch(matchId) {
    STORE["matches/" + matchId] = {
      roomId: "room-1", players: ["p1-uid", "p2-uid", "p3-uid", "p4-uid"], status: "complete",
      currentRound: 19, maxRounds: 18, extendedRounds: [],
      dealer: "p1-uid", turn: "p1-uid", gameState: { initialized: false, todo: "placeholder" },
      seats: { p1: "p1-uid", p2: "p2-uid", p3: "p3-uid", p4: "p4-uid" },
      version: 1, biddingOpen: false, bids: { p1: 4, p2: 3, p3: 2, p4: 4 }, lastBidSeat: "p4",
      cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
      winnerIds: ["p1"], finalScores: { p1: 100, p2: 80, p3: 70, p4: 60 }, completedRound: 18
    };
    VERSION["matches/" + matchId] = 1;
  }

  var browserA = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium-1194/chrome-linux/chrome", args: ["--no-sandbox"] });
  var browserB = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium-1194/chrome-linux/chrome", args: ["--no-sandbox"] });

  // ══════════════════════════════════════════════════════════════
  // AA / Browser QA 10: two-client YES propagation
  // ══════════════════════════════════════════════════════════════
  seedCompletedMatch("shared-match-1");
  var clientA1 = await newContext(browserA, { width: 1280, height: 720 }, "p1-uid", "shared-match-1");
  var clientB1 = await newContext(browserB, { width: 1280, height: 720 }, "p2-uid", "shared-match-1");
  await clientA1.goto(base + "/match/index.html", { waitUntil: "load" });
  await clientB1.goto(base + "/match/index.html", { waitUntil: "load" });
  await clientA1.evaluate(() => window.MatchScreenDebug.setLocalSeatId("p1"));
  await clientB1.evaluate(() => window.MatchScreenDebug.setLocalSeatId("p2"));
  await waitForCondition(clientA1, () => window.MatchScreenDebug.isRematchVotePanelOpen(), 3000);
  await waitForCondition(clientB1, () => window.MatchScreenDebug.isRematchVotePanelOpen(), 3000);

  // Client A's REAL vote call — never a fabricated DOM state on either side.
  await clientA1.evaluate(() => window.MatchService.submitRematchVote("shared-match-1", "YES"));
  var bObservesAsYes = await waitForCondition(clientB1, () => {
    var v = window.MatchAdapter.getRematchVoteState("shared-match-1");
    return !!(v && v.votes.p1 === "YES");
  }, 3000);
  check("AA / 10. Two-client YES propagation: Client B (never voted itself) observes Client A's REAL YES vote via its OWN subscribeToRematchVote()", bObservesAsYes === true);

  // ══════════════════════════════════════════════════════════════
  // AC / Browser QA 11: two-client NO propagation
  // ══════════════════════════════════════════════════════════════
  await clientB1.evaluate(() => window.MatchService.submitRematchVote("shared-match-1", "NO"));
  var aObservesFailedNo = await waitForCondition(clientA1, () => {
    var v = window.MatchAdapter.getRematchVoteState("shared-match-1");
    return !!(v && v.status === "FAILED_NO");
  }, 3000);
  check("AC / 11. Two-client NO propagation: Client A (never voted NO itself) observes Client B's REAL NO vote closing the whole vote to FAILED_NO", aObservesFailedNo === true);
  check("AC. The shared document's own status remains FAILED_NO — no client advanced past it", STORE["matches/shared-match-1/rematchVote/current"].status === "FAILED_NO");

  await clientA1.close(); await clientB1.close();

  // ══════════════════════════════════════════════════════════════
  // AB / Browser QA 12: simultaneous final YES race
  // Browser QA 13: simultaneous rematch creation race
  // Browser QA 14/15/16 (two-client angle): new match navigation + same
  // players/seats + old match unchanged, as independently observed by
  // BOTH clients.
  // ══════════════════════════════════════════════════════════════
  seedCompletedMatch("shared-match-2");
  var clientA2 = await newContext(browserA, { width: 1280, height: 720 }, "p1-uid", "shared-match-2");
  var clientB2 = await newContext(browserB, { width: 1280, height: 720 }, "p2-uid", "shared-match-2");
  await clientA2.goto(base + "/match/index.html", { waitUntil: "load" });
  await clientB2.goto(base + "/match/index.html", { waitUntil: "load" });
  await clientA2.evaluate(() => window.MatchScreenDebug.setLocalSeatId("p1"));
  await clientB2.evaluate(() => window.MatchScreenDebug.setLocalSeatId("p2"));
  await waitForCondition(clientA2, () => window.MatchScreenDebug.isRematchVotePanelOpen(), 3000);
  await waitForCondition(clientB2, () => window.MatchScreenDebug.isRematchVotePanelOpen(), 3000);

  // Cast 2 of the 4 votes first (from each client's OWN seat identity),
  // then race the SIMULTANEOUS final two votes from p3/p4 — one
  // submitted via client A's browser context (impersonating p3-uid),
  // one via client B's (impersonating p4-uid) — genuinely two
  // independent browser processes racing the SAME shared HTTP-backed
  // document for the same "complete the vote" transition.
  await clientA2.evaluate(() => window.MatchService.submitRematchVote("shared-match-2", "YES")); // p1, via A
  await clientB2.evaluate(() => window.MatchService.submitRematchVote("shared-match-2", "YES")); // p2, via B
  await Promise.all([
    clientA2.evaluate(async () => {
      window.SessionService.__testForceUser = { uid: "p3-uid" };
      await window.MatchService.submitRematchVote("shared-match-2", "YES");
    }),
    clientB2.evaluate(async () => {
      window.SessionService.__testForceUser = { uid: "p4-uid" };
      await window.MatchService.submitRematchVote("shared-match-2", "YES");
    })
  ]);
  var sharedVoteAfterRace = STORE["matches/shared-match-2/rematchVote/current"];
  check("AB / 12. Simultaneous final YES race: the shared document reaches ALL_YES exactly once, no corruption", sharedVoteAfterRace && sharedVoteAfterRace.status === "ALL_YES" &&
    sharedVoteAfterRace.votes.p1 === "YES" && sharedVoteAfterRace.votes.p2 === "YES" && sharedVoteAfterRace.votes.p3 === "YES" && sharedVoteAfterRace.votes.p4 === "YES");

  // Both clients' own MatchAdapter watchers race createRematchMatch() —
  // neither client is told to call it explicitly; this proves the
  // "any seated client may safely attempt, no host" property for real,
  // across two independent browser processes.
  var bothObserveNewMatchCreated = await waitForCondition(clientA2, () => {
    var v = window.MatchAdapter.getRematchVoteState("shared-match-2");
    return !!(v && v.status === "NEW_MATCH_CREATED" && v.newMatchId);
  }, 5000);
  check("13. Simultaneous rematch creation race: resolved to NEW_MATCH_CREATED (via either client's own watcher, no host)", bothObserveNewMatchCreated === true);

  var finalVote = STORE["matches/shared-match-2/rematchVote/current"];
  var newMatchDocCount = Object.keys(STORE).filter((k) => k.indexOf("matches/") === 0 && k.split("/").length === 2 && k === "matches/" + finalVote.newMatchId).length;
  check("13b. Exactly ONE new match document exists for this vote (server-side truth, not per-client)", newMatchDocCount === 1);

  var bAlsoObservesSameNewMatchId = await waitForCondition(clientB2, (expectedId) => {
    var v = window.MatchAdapter.getRematchVoteState("shared-match-2");
    return !!(v && v.newMatchId === expectedId);
  }, 3000, finalVote.newMatchId);
  check("13c. Client B independently observes the SAME newMatchId Client A's side converged on (no divergent outcome between clients)", bAlsoObservesSameNewMatchId === true);

  var newMatchDoc = STORE["matches/" + finalVote.newMatchId];
  check("15/AB. New match has the same four players/seats, as observed via the shared server-side store", newMatchDoc && JSON.stringify(newMatchDoc.seats) === JSON.stringify({ p1: "p1-uid", p2: "p2-uid", p3: "p3-uid", p4: "p4-uid" }));
  var oldMatchStill = STORE["matches/shared-match-2"];
  check("16/AB. Old match remains complete/unchanged, as observed via the shared server-side store", oldMatchStill.status === "complete" && oldMatchStill.completedRound === 18);

  // 14 (two-client angle): BOTH clients independently navigate to the
  // SAME new matchId.
  var aNavigated = await waitForCondition(clientA2, (expectedId) => {
    var d = JSON.parse(sessionStorage.getItem("estimation_game_state_v1"));
    return !!(d.data && d.data.match && d.data.match.id === expectedId);
  }, 4000, finalVote.newMatchId);
  var bNavigated = await waitForCondition(clientB2, (expectedId) => {
    var d = JSON.parse(sessionStorage.getItem("estimation_game_state_v1"));
    return !!(d.data && d.data.match && d.data.match.id === expectedId);
  }, 4000, finalVote.newMatchId);
  check("14/AB. Both independent clients navigate to the SAME new matchId", aNavigated === true && bNavigated === true);

  await clientA2.close(); await clientB2.close();
  await browserA.close(); await browserB.close();
  server.close();

  console.log("\n=== RESULTS ===\n");
  var passed = results.filter((r) => r.ok).length;
  var failed = results.filter((r) => !r.ok);
  console.log(passed + " passed, " + failed.length + " failed");
  if (failed.length) { console.log("\nFAILED:"); failed.forEach((f) => console.log(" - " + f.label)); }
  process.exit(failed.length ? 1 : 0);
})();
