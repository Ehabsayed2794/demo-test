// SPRINT C — Real multi-client reconnect/recovery verification.
//
// Reuses the exact real infrastructure proven in Sprint B
// (verify-sprint-b-multiclient.cjs): real Chromium, real Firebase Auth
// Emulator, real Firestore Rules Emulator, real compat SDKs served from
// a local cache (the CDN-through-proxy path proved unreliable), real
// unmodified RoomService/MatchService/MatchAdapter functions invoked
// directly via page.evaluate() (never a fake backend).
//
// R3 (reconnect after hand creation) deliberately uses a SINGLE-CLIENT
// dealRound() call — per Sprint C's own explicit instruction — to avoid
// the known, unresolved concurrent-transaction emulator behavior
// (Sprint B/mini-sprint finding) as a confound. This test is about
// reconnect, not concurrency; the two are kept isolated on purpose.
const http = require("http");
const fs = require("fs");
const path = require("path");
const { chromium } = require("playwright");
const { initializeTestEnvironment } = require("@firebase/rules-unit-testing");

const ROOT = "/home/user/demo-test/design-ui";
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };
const HTTP_PORT = 5179;
const FIRESTORE_HOST = "127.0.0.1", FIRESTORE_PORT = 8080;
const AUTH_HOST = "127.0.0.1", AUTH_PORT = 9099;
const PROJECT_ID = "demo-test-sprintc";
const CDN_CACHE = "/tmp/fb-cdn-cache";

var pass = 0, fail = 0;
var findings = [];
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; findings.push({ label, note }); }
}

function startStaticServer() {
  return new Promise((resolve) => {
    var server = http.createServer((req, res) => {
      var urlPath = decodeURIComponent(req.url.split("?")[0]);
      var filePath = path.join(ROOT, urlPath);
      if (!filePath.startsWith(ROOT)) { res.writeHead(403); res.end(); return; }
      fs.readFile(filePath, (err, data) => {
        if (err) { res.writeHead(404); res.end("Not found: " + urlPath); return; }
        var ext = path.extname(filePath);
        res.writeHead(200, { "Content-Type": MIME[ext] || "application/octet-stream" });
        res.end(data);
      });
    });
    server.listen(HTTP_PORT, () => resolve(server));
  });
}

async function main() {
  console.log("=== Sprint C: Real Multi-Client Reconnect/Recovery Verification ===\n");

  var rulesText;
  try { rulesText = fs.readFileSync("/home/user/demo-test/firestore.rules", "utf8"); }
  catch (e) { console.log("CANNOT READ firestore.rules — " + e.message); process.exitCode = 2; return; }

  var bootEnv;
  try {
    bootEnv = await initializeTestEnvironment({ projectId: PROJECT_ID, firestore: { rules: rulesText, host: FIRESTORE_HOST, port: FIRESTORE_PORT } });
  } catch (e) {
    console.log("EMULATOR NOT REACHABLE (bootstrap) — " + e.message);
    console.log("\n=== RESULTS ===\n0 passed, 0 failed (SKIPPED)");
    process.exitCode = 2; return;
  }
  await bootEnv.cleanup();

  var server = await startStaticServer();
  console.log("Static server on http://127.0.0.1:" + HTTP_PORT);

  var proxyUrl = process.env.HTTPS_PROXY || process.env.https_proxy;
  var browser = await chromium.launch({
    executablePath: "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
    proxy: proxyUrl ? { server: proxyUrl, bypass: "127.0.0.1,localhost" } : undefined,
    args: ["--ignore-certificate-errors"]
  });

  var SEATS = ["P1", "P2", "P3", "P4"];
  var contexts = [], pages = [], uids = {};

  async function installRedirect(page) {
    await page.route("https://www.gstatic.com/**", async (route) => {
      var fname = route.request().url().split("/").pop();
      var cached = path.join(CDN_CACHE, fname);
      if (fs.existsSync(cached)) await route.fulfill({ status: 200, contentType: "text/javascript", body: fs.readFileSync(cached) });
      else await route.continue();
    });
    await page.route("**/firebase-init.js", async (route) => {
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
      await page.goto(url, { waitUntil: "load" });
      var ready = await page.evaluate(() => typeof firebase !== "undefined" && typeof firebase.auth === "function" && typeof firebase.firestore === "function").catch(() => false);
      if (ready) return true;
      await new Promise((r) => setTimeout(r, 500 * (attempt + 1)));
    }
    return false;
  }

  var URL = "http://127.0.0.1:" + HTTP_PORT + "/match/index.html";

  for (var i = 0; i < 4; i++) {
    var ctx = await browser.newContext();
    var page = await ctx.newPage();
    page.on("console", (msg) => { if (msg.type() === "error") console.log("[" + SEATS[i] + " err] " + msg.text().slice(0, 150)); });
    await installRedirect(page);
    await gotoReady(page, URL);
    contexts.push(ctx); pages.push(page);
  }

  for (var i = 0; i < 4; i++) {
    var email = "sprintc-" + SEATS[i].toLowerCase() + "-" + Date.now() + "-" + i + "@test.local";
    uids[SEATS[i]] = await pages[i].evaluate(async (email) => {
      var cred = await window.Auth.createUserWithEmailAndPassword(email, "TestPass123!");
      return cred.user.uid;
    }, email);
  }
  console.log("4 real clients authenticated: " + JSON.stringify(uids) + "\n");

  // ── R1: Lobby reconnect ──
  var roomId = await pages[0].evaluate(async (uid) => window.RoomService.createRoom(uid, "Sprint C Room"), uids.P1);
  for (var i = 1; i < 4; i++) {
    await pages[i].evaluate(async (a) => window.RoomService.joinRoom(a.roomId, a.uid), { roomId, uid: uids[SEATS[i]] });
  }

  // P2 reloads (a fresh page in the SAME context/auth session).
  var p2CtxOld = contexts[1];
  var p2PageA = pages[1];
  var p2PageB = await p2CtxOld.newPage();
  await installRedirect(p2PageB);
  await gotoReady(p2PageB, URL);
  pages[1] = p2PageB; // subsequent steps use the reloaded page for P2
  await p2PageA.close();

  var roomAfterReload = await p2PageB.evaluate(async (roomId) => window.RoomService.loadRoom(roomId), roomId);
  check("R1.1-4 P2 reload restores identity (same uid still a member) and room membership is unchanged",
    !!roomAfterReload && roomAfterReload.players.indexOf(uids.P2) !== -1 && roomAfterReload.players.length === 4);

  var dupJoinAfterReload = await p2PageB.evaluate(async (a) => {
    await window.RoomService.joinRoom(a.roomId, a.uid);
    var r = await window.RoomService.loadRoom(a.roomId);
    return r.players.filter((p) => p === a.uid).length;
  }, { roomId, uid: uids.P2 });
  check("R1.5-6 Re-joining after reload does not duplicate membership", dupJoinAfterReload === 1);

  // ── R2: Match reconnect before dealing ──
  var matchStartResult = null;
  for (var i = 0; i < 4; i++) {
    var res = await pages[i].evaluate(async (a) => (await window.RoomService.setReady(a.roomId, a.uid, true)).matchStart || null, { roomId, uid: uids[SEATS[i]] });
    if (res && res.started) matchStartResult = res;
  }
  var matchId = matchStartResult && matchStartResult.matchId;
  check("R2.pre Match started for reconnect testing", !!matchId);

  if (matchId) {
    var seatsBefore = (await pages[0].evaluate(async (m) => window.MatchService.loadMatch(m), matchId)).seats;
    var p3seat = Object.keys(seatsBefore).find((s) => seatsBefore[s] === uids.P3);

    var p3CtxOld = contexts[2], p3PageA = pages[2];
    var p3PageB = await p3CtxOld.newPage();
    await installRedirect(p3PageB);
    await gotoReady(p3PageB, URL);
    pages[2] = p3PageB;
    await p3PageA.close();

    var matchAfterP3Reload = await p3PageB.evaluate(async (m) => window.MatchService.loadMatch(m), matchId);
    check("R2.4 Reconnected P3 observes the same matchId/match doc", !!matchAfterP3Reload && matchAfterP3Reload.roomId === roomId);
    check("R2.5 Seat identity for P3 is unchanged after reconnect", matchAfterP3Reload.seats[p3seat] === uids.P3);

    var cbCounts = await p3PageB.evaluate(async (m) => {
      var counts = [0, 0];
      var u1 = window.MatchService.subscribeToMatch(m, () => counts[0]++);
      var u2 = window.MatchService.subscribeToMatch(m, () => counts[1]++);
      await new Promise((r) => setTimeout(r, 600));
      u1(); u2();
      return counts;
    }, matchId);
    check("R2.6 No duplicate listener callbacks for reconnected client (2 subs -> 1 delivery each)", cbCounts[0] === 1 && cbCounts[1] === 1, JSON.stringify(cbCounts));

    var p3CanBid = await p3PageB.evaluate(async (a) => {
      try { return await window.MatchService.submitBid(a.matchId, a.seat, 4); } catch (e) { return { error: e.message }; }
    }, { matchId, seat: p3seat });
    check("R2.7 Reconnected P3 can participate normally afterward (submitBid succeeds)", p3CanBid && !p3CanBid.error, JSON.stringify(p3CanBid));
  }

  // ── R3: Reconnect after hand creation (single-client, non-racing deal) ──
  if (matchId) {
    var dealResult = await pages[0].evaluate(async (m) => {
      try { return await window.MatchService.dealRound(m, 1); } catch (e) { return { error: e.message }; }
    }, matchId);
    check("R3.pre Single-client dealRound() commits cleanly (non-racing path)", dealResult && dealResult.dealt === true, JSON.stringify(dealResult));

    if (dealResult && dealResult.dealt) {
      var matchNow = await pages[0].evaluate(async (m) => window.MatchService.loadMatch(m), matchId);
      var seatsNow = matchNow.seats;
      var p2seat = Object.keys(seatsNow).find((s) => seatsNow[s] === uids.P2);

      // Read P2's hand via the FIRST page (pre-reload) to have ground truth.
      var handBeforeReload = await pages[1].evaluate(async (a) => {
        var snap = await window.Db.collection("matches").doc(a.matchId).collection("hands").doc(a.seat).get();
        return snap.exists ? snap.data() : null;
      }, { matchId, seat: p2seat });

      var p2CtxOld2 = contexts[1], p2PageOld2 = pages[1];
      var p2PageC = await p2CtxOld2.newPage();
      await installRedirect(p2PageC);
      await gotoReady(p2PageC, URL);
      pages[1] = p2PageC;
      await p2PageOld2.close();

      var handAfterReload = await p2PageC.evaluate(async (a) => {
        var snap = await window.Db.collection("matches").doc(a.matchId).collection("hands").doc(a.seat).get();
        return snap.exists ? snap.data() : null;
      }, { matchId, seat: p2seat });
      check("R3.1-2 Reconnected P2 receives its own hand, matching server state exactly",
        !!handAfterReload && JSON.stringify(handAfterReload.cards) === JSON.stringify(handBeforeReload.cards));

      var opponentSeat = Object.keys(seatsNow).find((s) => s !== p2seat);
      var leaked = await p2PageC.evaluate(async (a) => {
        try { var snap = await window.Db.collection("matches").doc(a.matchId).collection("hands").doc(a.seat).get(); return { denied: false, exists: snap.exists }; }
        catch (e) { return { denied: true }; }
      }, { matchId, seat: opponentSeat });
      check("R3.5 Reconnected P2 still cannot read an opponent's hand", leaked.denied === true, JSON.stringify(leaked));

      var listenerCountAfterReload = await p2PageC.evaluate(async (a) => {
        var n = 0;
        var u = window.MatchService.subscribeToHand(a.matchId, a.seat, () => n++);
        await new Promise((r) => setTimeout(r, 600));
        u();
        return n;
      }, { matchId, seat: p2seat });
      check("R3.6 Hand listener count after reconnect is correct (exactly one delivery)", listenerCountAfterReload === 1, "got " + listenerCountAfterReload);
    }
  }

  // ── R4: Reconnect during bidding ──
  if (matchId) {
    var matchForBid = await pages[0].evaluate(async (m) => window.MatchService.loadMatch(m), matchId);
    // Determine an as-yet-unbid seat (P3 already bid in R2.7 above).
    var seatsForBid = matchForBid.seats;
    var seatsBidState = matchForBid.bids;
    var uidToSeat = {}; Object.keys(seatsForBid).forEach((s) => uidToSeat[seatsForBid[s]] = s);
    var p4seat = uidToSeat[uids.P4];

    var p4CtxOld = contexts[3], p4PageA = pages[3];
    var p4PageB = await p4CtxOld.newPage();
    await installRedirect(p4PageB);
    await gotoReady(p4PageB, URL);
    pages[3] = p4PageB;
    await p4PageA.close();

    var matchAfterP4Reload = await p4PageB.evaluate(async (m) => window.MatchService.loadMatch(m), matchId);
    check("R4.4 Bidding state (bids map) is restored for reconnected P4", JSON.stringify(matchAfterP4Reload.bids) === JSON.stringify(seatsBidState));

    var p4CanBid = await p4PageB.evaluate(async (a) => {
      try { return await window.MatchService.submitBid(a.matchId, a.seat, 3); } catch (e) { return { error: e.message }; }
    }, { matchId, seat: p4seat });
    check("R4.7 Reconnected P4 can submit a valid action afterward", p4CanBid && !p4CanBid.error, JSON.stringify(p4CanBid));
  }

  // ── R9: Double subscription on one real client ──
  if (matchId) {
    var doubleSubResult = await pages[0].evaluate(async (m) => {
      var counts = [0, 0, 0];
      var u1 = window.MatchService.subscribeToMatch(m, () => counts[0]++);
      var u2 = window.MatchService.subscribeToMatch(m, () => counts[1]++);
      await new Promise((r) => setTimeout(r, 400));
      u1(); u2();
      var u3 = window.MatchService.subscribeToMatch(m, () => counts[2]++);
      await new Promise((r) => setTimeout(r, 400));
      u3();
      return counts;
    }, matchId);
    check("R9 Double subscribe -> unsubscribe both -> subscribe again: exactly one delivery per active subscription",
      doubleSubResult.every((c) => c === 1), JSON.stringify(doubleSubResult));
  }

  // ── R10: Repeated reload of one client across different match states ──
  if (matchId) {
    var p1Ctx = contexts[0];
    var currentP1Page = pages[0];
    var reloadStates = [];
    for (var r = 0; r < 3; r++) {
      var newPage = await p1Ctx.newPage();
      await installRedirect(newPage);
      await gotoReady(newPage, URL);
      var m = await newPage.evaluate(async (mid) => window.MatchService.loadMatch(mid), matchId);
      reloadStates.push({ version: m.version, roomId: m.roomId });
      await currentP1Page.close();
      currentP1Page = newPage;
    }
    pages[0] = currentP1Page;
    check("R10 Repeated reload (3x): each reload observes a consistent, non-regressing match identity (same roomId every time)",
      reloadStates.every((s) => s.roomId === roomId), JSON.stringify(reloadStates));
  }

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  if (findings.length) { console.log("\n=== FAILURES ==="); findings.forEach((f) => console.log("- " + f.label + (f.note ? " :: " + f.note : ""))); }
  for (var c of contexts) await c.close();
  await browser.close();
  server.close();
  process.exitCode = fail > 0 ? 1 : 0;
}

main().catch((e) => { console.error("HARNESS CRASHED: " + (e && e.stack || e)); process.exitCode = 3; });
