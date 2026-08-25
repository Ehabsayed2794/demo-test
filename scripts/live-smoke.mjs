#!/usr/bin/env node
/*
 * M2-PRE local rehearsal for the Thursday D4 smoke scenario.
 *
 * The default target is the local hosting-dist artifact served by this
 * script, with Firebase Auth/Firestore redirected to the local emulators.
 * Set BASE_URL to a deployed URL only after owner authorization is present.
 * The script deliberately stops after one legal bidding action per client.
 *
 * The production round-1 engine starts in DASH, not AUCTION. Therefore the
 * one-action-per-client rehearsal submits each client's first legal bidding
 * action in the actual current subphase and records that fact. If the
 * product later starts directly in AUCTION, the same candidate resolver
 * submits the first legal auction bid/pass instead.
 */
import fs from "node:fs";
import path from "node:path";
import http from "node:http";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { resolveChromiumExecutablePath } from "./resolve-chromium.cjs";

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const HOSTING_ROOT = path.join(ROOT, "hosting-dist");
const RULES_PATH = path.join(ROOT, "firestore.rules");
const PROJECT_ID = "made---estimation-card-game";
const HTTP_HOST = "127.0.0.1";
const HTTP_PORT = Number(process.env.SMOKE_PORT || 5241);
const FIRESTORE_HOST = "127.0.0.1";
const FIRESTORE_PORT = 8080;
const AUTH_HOST = "127.0.0.1";
const AUTH_PORT = 9099;
const BASE_URL = process.env.BASE_URL || `http://${HTTP_HOST}:${HTTP_PORT}`;
const EVIDENCE_DIR = process.env.EVIDENCE_DIR || "/tmp/m2pre-live-smoke";
const STORAGE_KEY = "estimation_game_state_v1";
const CDN_DIR = path.join(ROOT, "tests", "fixtures", "firebase-cdn");
const CDN_MAP = {
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js": "firebase-app-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js": "firebase-auth-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore-compat.js": "firebase-firestore-compat.js"
};

const evidence = [];
const findings = [];
let passed = 0;
let failed = 0;
function logEvent(event, data = {}) {
  const entry = { ts: new Date().toISOString(), event, ...data };
  evidence.push(entry);
  console.log(JSON.stringify(entry));
}
function check(label, ok, data = {}) {
  if (ok) passed += 1; else { failed += 1; findings.push({ label, ...data }); }
  logEvent(ok ? "PASS" : "FAIL", { label, ...data });
}
function sleep(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
async function waitFor(page, fn, timeoutMs = 15000, arg) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = await page.evaluate(fn, arg).catch(() => null);
    if (value) return value;
    await sleep(150);
  }
  return null;
}
function startStaticServer() {
  if (!fs.existsSync(path.join(HOSTING_ROOT, "index.html"))) {
    throw new Error(`hosting-dist/index.html is missing; run npm run build:hosting first`);
  }
  const mime = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".png": "image/png", ".jpg": "image/jpeg", ".svg": "image/svg+xml" };
  const server = http.createServer((req, res) => {
    const requested = decodeURIComponent((req.url || "/").split("?")[0]);
    const relative = requested === "/" ? "/index.html" : requested;
    const filePath = path.resolve(HOSTING_ROOT, "." + relative);
    if (!filePath.startsWith(path.resolve(HOSTING_ROOT) + path.sep)) {
      res.writeHead(403); res.end("Forbidden"); return;
    }
    fs.readFile(filePath, (error, data) => {
      if (error) { res.writeHead(404); res.end("Not found"); return; }
      res.writeHead(200, { "Content-Type": mime[path.extname(filePath)] || "application/octet-stream" });
      res.end(data);
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(HTTP_PORT, HTTP_HOST, () => resolve(server));
  });
}
async function installEmulatorRedirect(page) {
  await page.addInitScript(({ firestoreHost, firestorePort, authHost, authPort }) => {
    window.__ESTEMSHAN_EMULATOR__ = { firestoreHost, firestorePort, authHost, authPort };
  }, { firestoreHost: FIRESTORE_HOST, firestorePort: FIRESTORE_PORT, authHost: AUTH_HOST, authPort: AUTH_PORT });
  for (const [cdnUrl, localName] of Object.entries(CDN_MAP)) {
    await page.route(cdnUrl, async (route) => {
      const body = fs.readFileSync(path.join(CDN_DIR, localName), "utf8");
      await route.fulfill({ status: 200, contentType: "text/javascript", body });
    });
  }
  await page.route(/fonts\.g/, (route) => route.abort());
  await page.route("**/firebase-init.js", async (route) => {
    const body = fs.readFileSync(path.join(ROOT, "design-ui", "firebase-init.js"), "utf8");
    const injected = body.replace(
      "window.Db = (typeof firebase.firestore === \"function\") ? firebase.firestore() : null;",
      "window.Db = (typeof firebase.firestore === \"function\") ? firebase.firestore() : null;\n" +
      `  if (window.Db) window.Db.useEmulator(window.__ESTEMSHAN_EMULATOR__.firestoreHost, window.__ESTEMSHAN_EMULATOR__.firestorePort);\n` +
      `  if (window.Auth) window.Auth.useEmulator(\"http://\" + window.__ESTEMSHAN_EMULATOR__.authHost + \":\" + window.__ESTEMSHAN_EMULATOR__.authPort);`
    );
    await route.fulfill({ status: 200, contentType: "text/javascript", body: injected });
  });
}
async function acceptDialogs(page, promptValue, action) {
  const dialogs = [];
  const handler = async (dialog) => {
    dialogs.push({ type: dialog.type(), message: dialog.message() });
    await dialog.accept(dialog.type() === "prompt" ? promptValue : undefined);
  };
  page.on("dialog", handler);
  try { return await action(dialogs); } finally { page.off("dialog", handler); }
}
async function signUp(page, label, email, password) {
  await page.goto(`${BASE_URL}/login/index.html`, { waitUntil: "load" });
  const loaded = await waitFor(page, () => typeof window.Auth === "object" && !!document.querySelector("#createForm"), 12000);
  check(`${label} login loaded with Firebase Auth`, !!loaded, { url: page.url() });
  await page.locator("#displayName").fill(`M2 ${label}`);
  await page.locator("#createEmail").fill(email);
  await page.locator("#createPassword").fill(password);
  await page.locator("#createForm button[type=submit]").click();
  await page.waitForURL(/\/lobby\/index\.html$/, { timeout: 15000 });
  const ready = await waitFor(page, () => !!(window.SessionService && window.SessionService.getCurrentUser && window.SessionService.getCurrentUser()), 10000);
  check(`${label} reaches Lobby after real email/password signup`, !!ready, { url: page.url() });
  // Current Lobby markup omits the existing match-service.js script even
  // though room-service.js calls global.MatchService when all players ready.
  // Index files are protected in M2-PRE, so the rehearsal injects only the
  // already-committed service and records this as a production integration
  // gap; Thursday's live run must confirm whether the deployed artifact has
  // been corrected by an owner-approved change.
  const matchServiceMissing = await page.evaluate(() => !window.MatchService);
  if (matchServiceMissing) {
    logEvent("PRODUCTION_GAP", { client: label, gap: "lobby/index.html does not load match-service.js", workaround: "Playwright-only injection of the existing unmodified design-ui/match-service.js" });
    await page.addScriptTag({ path: path.join(ROOT, "design-ui", "match-service.js") });
  }
  check(`${label} has MatchService available for the real ready-to-start path`, !!(await page.evaluate(() => !!window.MatchService)), { injected: matchServiceMissing });
  await page.screenshot({ path: path.join(EVIDENCE_DIR, `${label.toLowerCase()}-lobby.png`), fullPage: true });
  return await page.evaluate(() => ({ uid: window.SessionService.getCurrentUser().uid, name: document.querySelector("#playerName")?.textContent || null }));
}
async function createRoom(page) {
  let roomId = null;
  const dialogs = await acceptDialogs(page, null, async (seen) => {
    await page.locator("#createRoomBtn").click();
    await waitFor(page, () => seen.some((d) => d.type === "alert"), 8000);
    const alert = seen.find((d) => d.type === "alert");
    const match = alert && alert.message.match(/ID:\s*([A-Za-z0-9_-]+)/);
    roomId = match ? match[1] : null;
    return seen;
  });
  check("A creates a private room through Lobby UI", !!roomId, { dialogs, roomId });
  return roomId;
}
async function joinRoom(page, roomId) {
  const dialogs = await acceptDialogs(page, roomId, async (seen) => {
    await page.locator("#joinRoomBtn").click();
    await waitFor(page, () => seen.some((d) => d.type === "alert"), 8000);
    return seen;
  });
  check("B joins A's private room through Lobby UI", dialogs.some((d) => d.type === "alert" && d.message.includes(roomId)), { dialogs, roomId });
}
async function setReady(page, label) {
  const dialogs = await acceptDialogs(page, null, async (seen) => {
    await page.locator("#toggleReadyBtn").click();
    await Promise.race([
      waitFor(page, () => seen.some((d) => d.type === "alert"), 8000),
      page.waitForURL(/\/match\/index\.html$/, { timeout: 8000 }).catch(() => null)
    ]);
    return seen;
  });
  const readyDialog = dialogs.some((d) => d.type === "alert" && /Ready/.test(d.message));
  const navigated = /\/match\/index\.html$/.test(new URL(page.url()).pathname);
  check(`${label} marks ready through Lobby UI`, readyDialog || navigated, { dialogs, navigated, url: page.url() });
}
async function waitForBothActionSync(pages, timeoutMs = 12000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const states = await Promise.all(pages.map((page) => page.evaluate(() => {
      const state = window.BiddingEngine && window.BiddingEngine.getState ? window.BiddingEngine.getState() : null;
      return state ? { phase: state.subPhase, bids: state.bids } : null;
    }).catch(() => null)));
    const counts = states.map((state) => state && Object.keys(state.bids || {}).length);
    if (counts.every((count) => count >= 2)) return { states, counts };
    await sleep(150);
  }
  return null;
}
async function firstLegalIntent(page, matchId) {
  return page.evaluate(async ({ matchId }) => {
    const state = window.BiddingEngine.getState();
    const seatId = window.MatchScreenDebug.getLocalSeatId();
    if (!state || !seatId) return { error: "engine-or-seat-not-ready", state, seatId };
    if (state.waitingFor !== seatId) return { skipped: "not-my-turn", seatId, waitingFor: state.waitingFor, phase: state.subPhase };
    const candidates = [];
    if (state.subPhase === "DASH") {
      candidates.push({ type: "SubmitDashCallDecision", playerId: seatId, declaredDashCall: false });
    } else if (state.subPhase === "AUCTION") {
      if (state.auctionTop > 0) candidates.push({ type: "SubmitAuctionBid", playerId: seatId, isPass: true });
      for (let tricks = 4; tricks <= 13; tricks++) {
        for (const suit of ["CLUBS", "DIAMONDS", "HEARTS", "SPADES", "SANS"]) {
          candidates.push({ type: "SubmitAuctionBid", playerId: seatId, isPass: false, tricks, suit });
        }
      }
      candidates.push({ type: "SubmitAuctionBid", playerId: seatId, isPass: true });
    } else {
      return { error: "unexpected-subphase", subPhase: state.subPhase, seatId };
    }
    for (const intent of candidates) {
      const verdict = window.BiddingEngine.canSubmit(intent);
      if (!verdict || !verdict.legal) continue;
      const action = { actionType: intent.type };
      if (intent.declaredDashCall !== undefined) action.declaredDashCall = intent.declaredDashCall;
      if (intent.isPass !== undefined) action.isPass = intent.isPass;
      if (intent.tricks !== undefined) action.tricks = intent.tricks;
      if (intent.suit !== undefined) action.suit = intent.suit;
      const result = await window.MatchService.submitBiddingAction(matchId, action);
      return { submitted: intent, persistedAction: action, result, phase: state.subPhase };
    }
    return { error: "no-legal-candidate", seatId, phase: state.subPhase, state };
  }, { matchId });
}
async function main() {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  logEvent("START", { baseUrl: BASE_URL, projectId: PROJECT_ID, evidenceDir: EVIDENCE_DIR });
  const testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: fs.readFileSync(RULES_PATH, "utf8"), host: FIRESTORE_HOST, port: FIRESTORE_PORT }
  });
  await testEnv.clearFirestore();
  await testEnv.cleanup();
  const server = await startStaticServer();
  const browser = await chromium.launch({ executablePath: resolveChromiumExecutablePath() });
  const contexts = [];
  try {
    const pages = [];
    for (const label of ["A", "B"]) {
      const context = await browser.newContext();
      const page = await context.newPage();
      page.on("console", (message) => logEvent("console", { client: label, type: message.type(), text: message.text() }));
      page.on("pageerror", (error) => logEvent("pageerror", { client: label, text: error.message }));
      await installEmulatorRedirect(page);
      contexts.push(context);
      pages.push(page);
    }
    const password = process.env.SMOKE_PASSWORD || "TestPass123!";
    const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const a = await signUp(pages[0], "A", `m2pre-smoke-a-${suffix}@test.local`, password);
    const b = await signUp(pages[1], "B", `m2pre-smoke-b-${suffix}@test.local`, password);
    logEvent("AUTH_USERS", { a, b, passwordRecordedAs: "<set>" });

    const roomId = await createRoom(pages[0]);
    await joinRoom(pages[1], roomId);
    const roster = await waitFor(pages[0], async (id) => {
      const room = await window.RoomService.loadRoom(id);
      return room && room.players && room.players.length === 2 ? room : null;
    }, 12000, roomId);
    check("Both clients observe the two-player room roster", !!roster, { roomId, roster });
    await pages[1].screenshot({ path: path.join(EVIDENCE_DIR, "b-room-joined.png"), fullPage: true });

    await setReady(pages[0], "A");
    await setReady(pages[1], "B");
    const started = await waitFor(pages[0], async (id) => {
      const room = await window.RoomService.loadRoom(id);
      return room && room.matchId ? room : null;
    }, 15000, roomId);
    const matchId = started && started.matchId;
    check("All-ready flow creates exactly one matchId", !!matchId, { roomId, matchId, started });
    logEvent("ROOM_MATCH", { roomId, matchId, roster: started && started.players });

    await Promise.all(pages.map((page) => page.waitForURL(/\/match\/index\.html$/, { timeout: 20000 }).catch(() => null)));
    const matchViews = await Promise.all(pages.map((page) => waitFor(page, () => {
      const data = window.GameState && window.GameState.getData ? window.GameState.getData() : null;
      const debug = window.MatchScreenDebug;
      return data && data.match && data.match.id && debug && debug.getLocalSeatId() ? { matchId: data.match.id, seatId: debug.getLocalSeatId() } : null;
    }, 15000)));
    check("Both clients land on the same match screen and matchId", matchViews.every((v) => v && v.matchId === matchId), { matchId, matchViews });
    await Promise.all(pages.map((page, index) => page.screenshot({ path: path.join(EVIDENCE_DIR, `${index === 0 ? "a" : "b"}-match-before-action.png`), fullPage: true })));

    const engineStates = await Promise.all(pages.map((page) => waitFor(page, () => {
      const state = window.BiddingEngine && window.BiddingEngine.getState ? window.BiddingEngine.getState() : null;
      const seatId = window.MatchScreenDebug && window.MatchScreenDebug.getLocalSeatId ? window.MatchScreenDebug.getLocalSeatId() : null;
      return state && seatId ? { subPhase: state.subPhase, waitingFor: state.waitingFor, seatId, bids: state.bids } : null;
    }, 15000)));
    check("Both clients initialize the real bidding engine", engineStates.every(Boolean), { engineStates });
    logEvent("INITIAL_BIDDING_STATE", { matchId, engineStates });

    const submissions = [];
    for (let i = 0; i < 2; i++) {
      let result = null;
      for (let attempt = 0; attempt < 30 && !result?.submitted; attempt++) {
        const page = pages[i];
        const candidate = await firstLegalIntent(page, matchId).catch((error) => ({ error: error.message }));
        if (candidate.submitted) { result = candidate; break; }
        await sleep(250);
      }
      submissions.push({ client: i === 0 ? "A" : "B", result });
      check(`${i === 0 ? "A" : "B"} submits exactly one first legal bidding action`, !!result?.submitted, { result });
      if (!result?.submitted) break;
      await sleep(500);
    }
    logEvent("ONE_ACTION_EACH", { matchId, submissions, note: "Round 1 begins in DASH in the current production engine; the recorded first actions are therefore DASH decisions, not fabricated Auction bids." });

    const synced = await waitForBothActionSync(pages, 12000);
    check("Both clients observe both single legal actions", !!synced, { synced });
    await Promise.all(pages.map((page, index) => page.screenshot({ path: path.join(EVIDENCE_DIR, `${index === 0 ? "a" : "b"}-match-after-one-action.png`), fullPage: true })));
    logEvent("STOP", { reason: "M2-PRE requires exactly one legal action per client; no full match was played." });
  } finally {
    fs.writeFileSync(path.join(EVIDENCE_DIR, "evidence.jsonl"), evidence.map((entry) => JSON.stringify(entry)).join("\n") + "\n");
    fs.writeFileSync(path.join(EVIDENCE_DIR, "findings.json"), JSON.stringify(findings, null, 2) + "\n");
    for (const context of contexts) await context.close().catch(() => {});
    await browser.close().catch(() => {});
    await new Promise((resolve) => server.close(resolve));
  }
  logEvent("SUMMARY", { passed, failed, evidenceDir: EVIDENCE_DIR });
  process.exitCode = failed ? 1 : 0;
}
main().catch((error) => {
  logEvent("FATAL", { message: error.message, stack: error.stack });
  process.exitCode = 1;
});
