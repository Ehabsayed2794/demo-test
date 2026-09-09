var REPO_ROOT = require("path").join(__dirname, "..");
// P1-3 — reconnect E2E for the per-round-window world.
//
// Supersedes the abandoned root verify-sprint-c-reconnect.cjs (hard-coded
// /home/user paths, single-client deal to dodge emulator behavior, no
// mid-round / post-advance scenarios, prints the SKIPPED word this
// runner hard-fails on). That file is deleted alongside this landing.
//
// What this proves, against REAL Chromium pages + the REAL Firestore
// Rules Emulator (same bootstrap shape as scripts/golden-path.mjs):
//   R1 lobby reload keeps membership, no duplicate join.
//   R2 match reconnect pre-deal: same doc, same seat, single delivery.
//   R3 deal + hand re-read after reload, opponent hands still denied.
//   R4 (NEW) drop mid-round after trick 6, return mid-round, keep playing.
//   R5 (NEW) return post-advance: empty window + archive observed, and the
//       returned client participates in the new round (registry-rebase
//       path: match-service.js's shrink-to-0 rebase).
//   R9/R10 cheap listener + repeated-reload hygiene (kept from Sprint C).
//
// Card/bidding play is driven through the REAL TableEngine/BiddingEngine
// oracles + the REAL MatchService write paths (copied pattern-for-pattern
// from scripts/golden-path.mjs's own bots — never invented legality).
// No SKIPPED marker is ever printed: emulator-unreachable is a hard
// failure with a different message. Overall 150s abort guard keeps this
// file inside the runner's per-file timeout with a diagnosis, not a hang.
var http = require("http");
var fs = require("fs");
var path = require("path");
var chromium = require("playwright").chromium;
var initializeTestEnvironment = require("@firebase/rules-unit-testing").initializeTestEnvironment;
var resolveChromiumExecutablePath = require("../scripts/resolve-chromium.cjs").resolveChromiumExecutablePath;

var ROOT = path.resolve(REPO_ROOT, "design-ui");
var MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };
var HTTP_PORT = Number(process.env.RECONNECT_PORT || 5241);
var FIRESTORE_HOST = "127.0.0.1", FIRESTORE_PORT = 8080;
var AUTH_HOST = "127.0.0.1", AUTH_PORT = 9099;
// MUST match design-ui/firebase.json's own hardcoded projectId exactly.
var PROJECT_ID = "made---estimation-card-game";
var CDN_CACHE = path.join(REPO_ROOT, "tests", "fixtures", "firebase-cdn");
var CDN_MAP = {
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js": "firebase-app-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js": "firebase-auth-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore-compat.js": "firebase-firestore-compat.js"
};
var SEATS = ["p1", "p2", "p3", "p4"];
var STARTED_AT = Date.now();
var TIME_BUDGET_MS = 150000;

var pass = 0, fail = 0;
var findings = [];
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + JSON.stringify(note).slice(0, 300) : "")); fail++; findings.push(label); }
}
function outOfTime() { return Date.now() - STARTED_AT > TIME_BUDGET_MS; }
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

function startServer() {
  return new Promise(function (resolve) {
    var server = http.createServer(function (req, res) {
      var urlPath = decodeURIComponent(req.url.split("?")[0]);
      var filePath = path.resolve(ROOT, "." + urlPath);
      if (filePath !== ROOT && filePath.indexOf(ROOT + path.sep) !== 0) { res.writeHead(403); res.end(); return; }
      fs.readFile(filePath, function (err, data) {
        if (err) { res.writeHead(404); res.end("Not found: " + urlPath); return; }
        res.writeHead(200, { "Content-Type": MIME[path.extname(filePath)] || "application/octet-stream" });
        res.end(data);
      });
    });
    server.listen(HTTP_PORT, function () { resolve(server); });
  });
}

async function installRedirect(page) {
  for (var cdnUrl in CDN_MAP) {
    (function (url, file) {
      return page.route(url, function (route) {
        route.fulfill({ status: 200, contentType: "text/javascript", body: fs.readFileSync(path.join(CDN_CACHE, file), "utf8") });
      });
    })(cdnUrl, CDN_MAP[cdnUrl]);
  }
  await page.route("**/firebase-init.js", async function (route) {
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

async function gotoReady(page, url) {
  for (var attempt = 0; attempt < 4; attempt++) {
    await page.goto(url, { waitUntil: "load", timeout: 60000 });
    var ready = await page.evaluate(function () { return typeof firebase !== "undefined" && typeof firebase.auth === "function" && typeof firebase.firestore === "function"; }).catch(function () { return false; });
    if (ready) return true;
    await sleep(400 * (attempt + 1));
  }
  return false;
}

async function waitFor(page, fn, timeoutMs, arg) {
  var deadline = Date.now() + (timeoutMs || 10000);
  while (Date.now() < deadline) {
    var v = await page.evaluate(fn, arg).catch(function () { return null; });
    if (v) return v;
    await sleep(120);
  }
  return null;
}

// One real bidding action for the WAITING seat, executed on THAT seat's
// own page. Pattern-for-pattern from scripts/golden-path.mjs.
async function attemptOneBiddingAction(page, matchId, seatId) {
  return page.evaluate(async function (args) {
    var state = window.BiddingEngine.getState();
    if (!state || state.waitingFor !== args.seatId) return { skipped: "not-my-turn" };
    var subPhase = state.subPhase;
    var suits = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS", "SANS"];
    var candidates = [];
    if (subPhase === "DASH") {
      candidates.push({ type: "SubmitDashCallDecision", playerId: args.seatId, declaredDashCall: false });
    } else if (subPhase === "AUCTION") {
      var auctionStart = Math.max(4, (state.auctionTop || 0) + 1);
      for (var t = auctionStart; t <= 13; t++) {
        for (var s = 0; s < suits.length; s++) {
          candidates.push({ type: "SubmitAuctionBid", playerId: args.seatId, isPass: false, tricks: t, suit: suits[s] });
        }
      }
      candidates.push({ type: "SubmitAuctionBid", playerId: args.seatId, isPass: true });
    } else if (subPhase === "CONFIRM") {
      var startT = state.auctionTop || 4;
      for (var t2 = startT; t2 <= 13; t2++) {
        for (var s2 = 0; s2 < suits.length; s2++) {
          candidates.push({ type: "SubmitConfirmCall", playerId: args.seatId, tricks: t2, suit: suits[s2] });
        }
      }
    } else if (subPhase === "ESTIMATES") {
      for (var t3 = 0; t3 <= 13; t3++) {
        candidates.push({ type: "SubmitFinalEstimate", playerId: args.seatId, tricks: t3 });
      }
    } else {
      return { skipped: "subphase-" + subPhase };
    }
    for (var i = 0; i < candidates.length; i++) {
      var c = candidates[i];
      var verdict = window.BiddingEngine.canSubmit(c);
      if (verdict && verdict.legal) {
        try {
          var result;
          if (c.type === "SubmitFinalEstimate") {
            result = await window.MatchService.submitBid(args.matchId, args.seatId, c.tricks);
          } else {
            var action = { actionType: c.type };
            if (c.declaredDashCall !== undefined) action.declaredDashCall = c.declaredDashCall;
            if (c.isPass !== undefined) action.isPass = c.isPass;
            if (c.tricks !== undefined) action.tricks = c.tricks;
            if (c.suit !== undefined) action.suit = c.suit;
            result = await window.MatchService.submitBiddingAction(args.matchId, action);
          }
          return { submitted: c.type, result: true };
        } catch (e) { return { submitted: c.type, error: e.message }; }
      }
    }
    return { noLegalCandidate: true, subPhase: subPhase };
  }, { matchId: matchId, seatId: seatId });
}

// One real card play for the CURRENT turn seat, executed on its own page.
async function attemptOneCardPlay(page, matchId, seatId) {
  return page.evaluate(async function (args) {
    var state = window.TableEngine.getState();
    if (!state || state.turn !== args.seatId || state.phase !== "PLAY") return { skipped: "not-my-turn" };
    var hand = window.GameSession.getHand(args.seatId);
    for (var i = 0; i < hand.length; i++) {
      var card = hand[i];
      var verdict = window.TableEngine.canPlayCard(args.seatId, card);
      if (verdict && verdict.legal) {
        try {
          await window.MatchService.submitCard(args.matchId, { suit: card.suit, rank: card.rank });
          return { submitted: true, seat: args.seatId };
        } catch (e) { return { error: e.message }; }
      }
    }
    return { noLegalCard: true };
  }, { matchId: matchId, seatId: seatId });
}

function seatIndex(seat) { return SEATS.indexOf(seat); }

// Drives bidding until DONE (or budget out). Records which SEAT each
// accepted action came from into submittedBy (a seat -> count map) so
// callers can prove a SPECIFIC reconnected page participated.
async function driveBidding(pages, matchId, submittedBy, maxIters) {
  var stall = 0, lastWaiting = null;
  for (var iter = 0; iter < (maxIters || 120); iter++) {
    if (outOfTime()) return { ok: false, reason: "TIME_BUDGET" };
    var states = await Promise.all(pages.map(function (p) {
      return p.evaluate(function () { return window.BiddingEngine ? window.BiddingEngine.getState() : null; }).catch(function () { return null; });
    }));
    if (states.some(function (s) { return !s; })) { await sleep(150); continue; }
    if (states.every(function (s) { return s.subPhase === "DONE"; })) return { ok: true };
    var ref = states[0];
    var converged = states.every(function (s) { return s.round === ref.round && s.subPhase === ref.subPhase && s.waitingFor === ref.waitingFor; });
    if (!converged || !ref.waitingFor) { await sleep(120); continue; }
    var idx = seatIndex(ref.waitingFor);
    if (idx === -1) return { ok: false, reason: "INVALID_WAITING_FOR" };
    var res = await attemptOneBiddingAction(pages[idx], matchId, ref.waitingFor);
    if (res && res.submitted && !res.error) {
      submittedBy[ref.waitingFor] = (submittedBy[ref.waitingFor] || 0) + 1;
      stall = 0;
    } else if (ref.waitingFor === lastWaiting) { stall++; } else { stall = 0; }
    lastWaiting = ref.waitingFor;
    if (stall > 25) return { ok: false, reason: "STALLED" };
    await sleep(80);
  }
  return { ok: false, reason: "MAX_ITERS" };
}

// Drives card plays until totalPlaysSubmitted reaches target (or the
// round advances/completes). Tracks per-seat submissions the same way.
async function driveCards(pages, matchId, roundNumber, targetPlays, submittedBy, maxIters) {
  var plays = 0, stall = 0, lastTurn = null;
  for (var iter = 0; iter < (maxIters || 220); iter++) {
    if (outOfTime()) return { ok: false, reason: "TIME_BUDGET", plays: plays };
    if (plays >= targetPlays) return { ok: true, plays: plays };
    var doc = await pages[0].evaluate(function (id) { return window.MatchService.loadMatch(id); }, matchId).catch(function () { return null; });
    if (!doc) { await sleep(150); continue; }
    if (doc.currentRound !== roundNumber || doc.status === "complete") return { ok: true, plays: plays, movedOn: true };
    var states = await Promise.all(pages.map(function (p) {
      return p.evaluate(function () { return window.TableEngine ? window.TableEngine.getState() : null; }).catch(function () { return null; });
    }));
    if (states.some(function (s) { return !s; })) { await sleep(150); continue; }
    var turn = states[0].turn;
    if (!turn || states[0].phase !== "PLAY") { await sleep(120); continue; }
    var idx = seatIndex(turn);
    if (idx === -1) return { ok: false, reason: "INVALID_TURN", plays: plays };
    var res = await attemptOneCardPlay(pages[idx], matchId, turn);
    if (res && res.submitted) {
      plays++;
      submittedBy[turn] = (submittedBy[turn] || 0) + 1;
      stall = 0;
    } else if (turn === lastTurn) { stall++; } else { stall = 0; }
    lastTurn = turn;
    if (stall > 30) return { ok: false, reason: "STALLED", plays: plays };
    await sleep(60);
  }
  return { ok: false, reason: "MAX_ITERS", plays: plays };
}

async function reloadPage(contexts, pages, i, url) {
  var oldPage = pages[i];
  var fresh = await contexts[i].newPage();
  await installRedirect(fresh);
  await gotoReady(fresh, MATCH_URL);
  pages[i] = fresh;
  await oldPage.close();
  return fresh;
}

async function main() {
  console.log("=== P1-3 reconnect E2E (window world): 4 real clients vs emulator ===\n");
  var rulesText;
  try { rulesText = fs.readFileSync(path.join(REPO_ROOT, "firestore.rules"), "utf8"); }
  catch (e) { console.log("CANNOT READ firestore.rules: " + e.message); process.exitCode = 2; return; }

  var bootEnv;
  try {
    bootEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: { rules: rulesText, host: FIRESTORE_HOST, port: FIRESTORE_PORT }
    });
  } catch (e) {
    // NOTE: deliberately NOT the word SKIPPED — the runner hard-fails on it.
    console.log("EMULATOR UNREACHABLE (bootstrap): " + e.message);
    process.exitCode = 2; return;
  }
  await bootEnv.cleanup();

  var server = await startServer();
  var browser;
  try {
    browser = await chromium.launch({ executablePath: resolveChromiumExecutablePath() });
  } catch (e) {
    console.log("BROWSER LAUNCH FAILED: " + e.message);
    process.exitCode = 2; return;
  }

  var BASE = "http://127.0.0.1:" + HTTP_PORT;
  var LOBBY_URL = BASE + "/lobby/index.html";
  var MATCH_URL = BASE + "/match/index.html";
  var STORAGE_KEY = "estimation_game_state_v1";
  var contexts = [], pages = [], uids = {};
  var contextsToClose = [];
  async function cleanup(code) {
    for (var c of contextsToClose) { try { await c.close(); } catch (e) {} }
    try { await browser.close(); } catch (e) {}
    try { server.close(); } catch (e) {}
    console.log("\n=== RESULTS ===\n" + pass + " passed, " + fail + " failed");
    process.exitCode = code;
  }

  try {
    // Boot on LOBBY (like scripts/golden-path.mjs): match/index.html needs
    // the GameState handoff to bootstrap engines/seat for a match.
    for (var i = 0; i < 4; i++) {
      var ctx = await browser.newContext();
      var page = await ctx.newPage();
      await installRedirect(page);
      if (!await gotoReady(page, LOBBY_URL)) throw new Error("page " + SEATS[i] + " never ready");
      contexts.push(ctx); pages.push(page); contextsToClose.push(ctx);
    }
    for (var j = 0; j < 4; j++) {
      var email = "recon-" + SEATS[j] + "-" + Date.now() + "-" + j + "@test.local";
      uids[SEATS[j]] = await pages[j].evaluate(async function (em) {
        var cred = await window.Auth.createUserWithEmailAndPassword(em, "TestPass123!");
        return cred.user.uid;
      }, email);
    }

    // ── R1: lobby reload keeps membership, join stays idempotent ──
    var roomId = await pages[0].evaluate(async function (uid) { return window.RoomService.createRoom(uid, "Reconnect Room"); }, uids.p1);
    for (var k = 1; k < 4; k++) {
      await pages[k].evaluate(async function (a) { return window.RoomService.joinRoom(a.roomId, a.uid); }, { roomId: roomId, uid: uids[SEATS[k]] });
    }
    await reloadPage(contexts, pages, 1, LOBBY_URL);
    var roomAfter = await pages[1].evaluate(async function (id) { return window.RoomService.loadRoom(id); }, roomId);
    check("R1 reloaded P2 still a member, membership unchanged (4)",
      !!roomAfter && roomAfter.players.indexOf(uids.p2) !== -1 && roomAfter.players.length === 4);
    var dupCount = await pages[1].evaluate(async function (a) {
      await window.RoomService.joinRoom(a.roomId, a.uid);
      var r = await window.RoomService.loadRoom(a.roomId);
      return r.players.filter(function (p) { return p === a.uid; }).length;
    }, { roomId: roomId, uid: uids.p2 });
    check("R1 re-join after reload is idempotent (no duplicate)", dupCount === 1);

    // ── R2: match start (non-creators first, creator last — exercises the
    // creator-only startMatch guard) + GameState handoff into match pages ──
    for (var m = 1; m < 4; m++) {
      await pages[m].evaluate(async function (a) { return window.RoomService.setReady(a.roomId, a.uid, true); }, { roomId: roomId, uid: uids[SEATS[m]] });
    }
    var creatorReady = await pages[0].evaluate(async function (a) { return window.RoomService.setReady(a.roomId, a.uid, true); }, { roomId: roomId, uid: uids.p1 });
    var matchId = creatorReady && creatorReady.matchStart && creatorReady.matchStart.matchId;
    if (!matchId) {
      var roomNow = await pages[0].evaluate(async function (id) { return window.RoomService.loadRoom(id); }, roomId);
      matchId = roomNow && roomNow.matchId;
    }
    check("R2.pre match started via all-ready", !!matchId);
    if (!matchId) { await cleanup(1); return; }
    // Same handoff shape the real lobby uses (scripts/golden-path.mjs).
    for (var h = 0; h < 4; h++) {
      await pages[h].evaluate(function (args) {
        var data = {
          current: "Gameplay", previous: "Lobby", history: ["Lobby"],
          data: {
            player: { id: args.uid, name: "Player " + args.seat },
            account: { type: "test", email: null }, room: { code: null, host: false, seats: [] }, lastResult: null,
            match: { id: args.matchId, roomId: args.roomId }
          }
        };
        window.sessionStorage.setItem(args.storageKey, JSON.stringify(data));
      }, { matchId: matchId, roomId: roomId, uid: uids[SEATS[h]], seat: SEATS[h], storageKey: STORAGE_KEY });
      await pages[h].goto(MATCH_URL, { waitUntil: "load" });
    }
    for (var w = 0; w < 4; w++) {
      await waitFor(pages[w], function () { return !!(window.SessionService && window.SessionService.getCurrentUser()); }, 8000);
    }
    var seatsOf = function (doc, uid) { return Object.keys(doc.seats).find(function (s) { return doc.seats[s] === uid; }); };
    var p3seat = seatsOf(await pages[0].evaluate(async function (x) { return window.MatchService.loadMatch(x); }, matchId), uids.p3);
    await reloadPage(contexts, pages, 2, MATCH_URL);
    var matchP3 = await pages[2].evaluate(async function (x) { return window.MatchService.loadMatch(x); }, matchId);
    check("R2 reconnected P3 observes the same match doc", !!matchP3 && matchP3.roomId === roomId);
    check("R2 P3 seat identity unchanged after reconnect", !!matchP3 && matchP3.seats[p3seat] === uids.p3);
    var dbl = await pages[2].evaluate(async function (x) {
      var n = [0, 0];
      var u1 = window.MatchService.subscribeToMatch(x, function () { n[0]++; });
      var u2 = window.MatchService.subscribeToMatch(x, function () { n[1]++; });
      await new Promise(function (rr) { setTimeout(rr, 600); });
      u1(); u2();
      return n;
    }, matchId);
    check("R2 two local subs share one listener (1 delivery each)", dbl[0] === 1 && dbl[1] === 1, dbl);

    // ── R3: real deal, hand re-read after reload, opponent denied ──
    var deal = await pages[0].evaluate(async function (x) {
      try { return await window.MatchService.dealRound(x, 1); } catch (e) { return { error: e.message }; }
    }, matchId);
    check("R3.pre dealRound(1) commits (any seated member may deal)", deal && deal.dealt === true, deal && (deal.error || deal.reason));
    if (!deal || !deal.dealt) { await cleanup(1); return; }
    var p2seat = seatsOf(await pages[0].evaluate(async function (x) { return window.MatchService.loadMatch(x); }, matchId), uids.p2);
    var handBefore = await pages[1].evaluate(async function (a) {
      var s = await window.Db.collection("matches").doc(a.matchId).collection("hands").doc(a.seat).get();
      return s.exists ? s.data() : null;
    }, { matchId: matchId, seat: p2seat });
    await reloadPage(contexts, pages, 1, MATCH_URL);
    var handAfter = await pages[1].evaluate(async function (a) {
      var s = await window.Db.collection("matches").doc(a.matchId).collection("hands").doc(a.seat).get();
      return s.exists ? s.data() : null;
    }, { matchId: matchId, seat: p2seat });
    check("R3 reloaded P2 reads its own hand identical to pre-reload server state",
      !!handAfter && !!handBefore && JSON.stringify(handAfter.cards) === JSON.stringify(handBefore.cards));
    var otherSeat = SEATS.find(function (s) { return s !== p2seat; });
    var leak = await pages[1].evaluate(async function (a) {
      try { var s = await window.Db.collection("matches").doc(a.matchId).collection("hands").doc(a.seat).get(); return { denied: false }; }
      catch (e) { return { denied: true }; }
    }, { matchId: matchId, seat: otherSeat });
    check("R3 reconnected P2 still cannot read an opponent hand", leak.denied === true);

    // ── R4 (NEW): drop mid-round after trick 6, return mid-round ──
    var bidSubs = {};
    var bidRes = await driveBidding(pages, matchId, bidSubs, 150);
    check("R4.pre round-1 bidding completes via the real path", bidRes.ok === true, bidRes.reason);
    if (!bidRes.ok) { await cleanup(1); return; }
    var cardSubs = {};
    var sixTricks = await driveCards(pages, matchId, 1, 24, cardSubs, 200);
    check("R4.pre 6 tricks (24 plays) driven pre-drop", sixTricks.ok === true && sixTricks.plays >= 24, sixTricks);
    if (!sixTricks.ok) { await cleanup(1); return; }
    await reloadPage(contexts, pages, 1, MATCH_URL);
    var p2view = await pages[1].evaluate(async function (x) { return window.MatchService.loadMatch(x); }, matchId);
    check("R4 reloaded P2 mid-round observes the live round (still round 1)", !!p2view && p2view.currentRound === 1);
    var p2handView = await pages[1].evaluate(async function (a) {
      var s = await window.Db.collection("matches").doc(a.matchId).collection("hands").doc(a.seat).get();
      return s.exists ? s.data() : null;
    }, { matchId: matchId, seat: p2seat });
    check("R4 reloaded P2 hand doc is the live round-1 hand", !!p2handView && p2handView.round === 1 && p2handView.cards.length === 13);
    var postSubs = {};
    // NOTE: driveCards() counts from 0 per call; pre-drop drove exactly 24,
    // so 4 more here then 24 in R5 lands the round at exactly 52 plays.
    var afterDrop = await driveCards(pages, matchId, 1, 4, postSubs, 120);
    check("R4 play continues after the drop (4 more plays accepted)", afterDrop.ok === true && afterDrop.plays === 4, afterDrop);
    var p2PostPlays = (postSubs[p2seat] || 0);
    check("R4 reloaded P2 itself submitted post-return (participates, not just observes)", p2PostPlays >= 1, postSubs);
    var p2Count = await pages[1].evaluate(async function (x) { return window.MatchAdapter.getLastAppliedCardCount(x); }, matchId);
    var p1Count = await pages[0].evaluate(async function (x) { return window.MatchAdapter.getLastAppliedCardCount(x); }, matchId);
    check("R4 reloaded P2 adapter caught up to a control client (count parity)", p2Count !== null && p2Count === p1Count, { p2: p2Count, p1: p1Count });

    // ── R5 (NEW): return post-advance — empty window + archive + new-round play ──
    // 24 (pre-drop) + 4 (post-drop) already played; 24 more completes 52.
    var rest = await driveCards(pages, matchId, 1, 24, {}, 260);
    check("R5.pre round 1 reaches 52 plays", rest.ok === true && rest.plays === 24, rest);
    if (!rest.ok) { await cleanup(1); return; }
    var adv = await pages[0].evaluate(async function (x) {
      try { return await window.MatchService.advanceToNextRound(x, 1); } catch (e) { return { error: e.message }; }
    }, matchId);
    check("R5.pre advance to round 2 commits", adv && adv.advanced === true, adv && (adv.error || adv.reason));
    if (!adv || !adv.advanced) { await cleanup(1); return; }
    await reloadPage(contexts, pages, 2, MATCH_URL);
    var p3r2 = await pages[2].evaluate(async function (x) { return window.MatchService.loadMatch(x); }, matchId);
    check("R5 reloaded P3 post-advance sees round 2", !!p3r2 && p3r2.currentRound === 2);
    check("R5 reloaded P3 sees the reset parent window (empty logs)", !!p3r2 && p3r2.cardLog.length === 0 && p3r2.biddingLog.length === 0);
    var arch = await pages[2].evaluate(async function (x) {
      var s = await window.Db.collection("matches").doc(x).collection("roundArchive").doc("1").get();
      return s.exists ? s.data() : null;
    }, matchId);
    check("R5 reloaded P3 reads roundArchive/1 with round 1's 52 plays", !!arch && arch.round === 1 && arch.cardLog.length === 52);
    var r2subs = {};
    var r2done = await driveBidding(pages, matchId, r2subs, 150);
    check("R5.pre round-2 bidding completes", r2done.ok === true, r2done.reason);
    check("R5 reloaded P3 submitted in the new round (registry-rebase path works)", (r2subs[p3seat] || 0) >= 1, r2subs);

    // ── R9/R10: cheap listener + repeated-reload hygiene ──
    var dbl2 = await pages[0].evaluate(async function (x) {
      var n = [0, 0, 0];
      var u1 = window.MatchService.subscribeToMatch(x, function () { n[0]++; });
      var u2 = window.MatchService.subscribeToMatch(x, function () { n[1]++; });
      await new Promise(function (rr) { setTimeout(rr, 400); });
      u1(); u2();
      var u3 = window.MatchService.subscribeToMatch(x, function () { n[2]++; });
      await new Promise(function (rr) { setTimeout(rr, 400); });
      u3();
      return n;
    }, matchId);
    check("R9 subscribe/unsubscribe/resubscribe: exactly one delivery per active sub", dbl2.every(function (c) { return c === 1; }), dbl2);
    var seenRooms = [];
    for (var rr = 0; rr < 3; rr++) {
      await reloadPage(contexts, pages, 0, MATCH_URL);
      var mm = await pages[0].evaluate(async function (x) { return window.MatchService.loadMatch(x); }, matchId);
      seenRooms.push(mm && mm.roomId);
    }
    check("R10 3x reload: same roomId every time (identity never regresses)", seenRooms.every(function (r) { return r === roomId; }), seenRooms);
  } catch (e) {
    console.log("HARNESS CRASHED: " + ((e && e.stack) || e));
    process.exitCode = 3; return;
  }
  await cleanup(fail > 0 ? 1 : 0);
}

main().catch(function (e) { console.log("HARNESS CRASHED: " + ((e && e.stack) || e)); process.exitCode = 3; });

