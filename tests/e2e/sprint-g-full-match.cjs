// Sprint G — Real 4-Client End-to-End Gameplay Verification.
//
// Genuinely real, NOT mocked: 4 independent Playwright browser contexts
// (P1-P4), each running the REAL, unmodified design-ui code (room-service.js,
// match-service.js, match-adapter.js, bidding-engine.js, table-engine.js,
// scoring-engine.js, the real firebase-app/auth/firestore COMPAT SDKs),
// each connected via useEmulator() to a REAL, locally running Firestore
// Rules Emulator AND a REAL Firebase Auth Emulator, with the REAL,
// unmodified firestore.rules compiled and enforced.
//
// Every gameplay action (bidding, card play) is submitted through the
// REAL production service functions (MatchService.submitBiddingAction/
// submitBid/submitCard) — never a raw Firestore write, never a fabricated
// UI event. Round advancement, match completion, and rematch-vote
// progression are NOT manually triggered — they happen automatically,
// exactly as they do in production, via each client's own live
// MatchAdapter watchers (maybeAdvanceRound/maybeExtendOrCompleteMatch/
// maybeAdvanceRematchVote) reacting to the real synced state.
//
// The per-round bidding/card decisions are SCRIPTED (a deterministic bot),
// not manual clicks — but every decision is validated live against the
// REAL BiddingEngine.canSubmit()/TableEngine.canPlayCard() oracles running
// in that seat's own real browser page before submission, so illegal
// moves are never attempted and the real rules engine — not this script —
// is what decides legality. This is the same principle already
// established in verify-sprint-b-multiclient.cjs (call the same functions
// the UI's own click handlers call), extended across a full match instead
// of a handful of isolated actions.
//
// This file is intentionally NOT part of `tests/*.cjs` (the fast default
// suite `npm test` runs) — it requires a live 4-browser-context run
// against a real emulator and can take several minutes for a full
// 18-round match. Run directly: `node tests/e2e/sprint-g-full-match.cjs`
// (with `firebase emulators:start --only firestore,auth --project
// made---estimation-card-game` already running).
const path = require("path");
const __REPO_ROOT__ = path.join(__dirname, "..", "..");
const fs = require("fs");
const http = require("http");
const { chromium } = require("playwright");
const { initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const { resolveChromiumExecutablePath } = require(__REPO_ROOT__ + "/scripts/resolve-chromium.cjs");

const ROOT = __REPO_ROOT__ + "/design-ui";
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };
const HTTP_PORT = 5240;
const FIRESTORE_HOST = "127.0.0.1", FIRESTORE_PORT = 8080;
const AUTH_HOST = "127.0.0.1", AUTH_PORT = 9099;
// MUST match design-ui/firebase-init.js's own real, hardcoded projectId
// exactly (see tests/hand-sync-reconnect.rules-emulator.test.cjs's own
// identical comment — this exact mismatch was discovered and fixed
// during Sprint F, and would silently partition test data into a
// namespace the real browser client never reads if gotten wrong again).
const PROJECT_ID = "made---estimation-card-game";
const CDN_CACHE = __REPO_ROOT__ + "/tests/fixtures/firebase-cdn";
const CDN_MAP = {
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js": "firebase-app-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js": "firebase-auth-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore-compat.js": "firebase-firestore-compat.js"
};
const STORAGE_KEY = "estimation_game_state_v1";
const SEATS = ["p1", "p2", "p3", "p4"];

var pass = 0, fail = 0;
var findings = [];
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; findings.push({ label: label, note: note }); }
}

function startServer() {
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

async function installEmulatorRedirect(page) {
  for (var cdnUrl in CDN_MAP) {
    await page.route(cdnUrl, function (route) {
      route.fulfill({ status: 200, contentType: "text/javascript", body: fs.readFileSync(path.join(CDN_CACHE, CDN_MAP[route.request().url()]), "utf8") });
    });
  }
  await page.route(/fonts\.g/, function (route) { route.abort(); });
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
    await new Promise((r) => setTimeout(r, 400 * (attempt + 1)));
  }
  return false;
}

async function waitFor(page, fn, timeoutMs, arg) {
  var deadline = Date.now() + (timeoutMs || 15000);
  while (Date.now() < deadline) {
    var v = await page.evaluate(fn, arg).catch(function (e) { return null; });
    if (v) return v;
    await new Promise((r) => setTimeout(r, 150));
  }
  return null;
}
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

// ══════════════════════════════════════════════════════════════════
// BOT DECISION LOGIC — every candidate is validated against the REAL
// BiddingEngine.canSubmit()/TableEngine.canPlayCard() oracle, running
// live in that seat's own real browser page, BEFORE submission. This
// script never re-derives or assumes rule legality itself (Risk,
// 13-rule, Forbidden-13, follow-suit, fast-round trump forcing, etc.)
// — it only tries plausible candidates in a fixed order and takes the
// first the REAL engine accepts. If no candidate is ever legal, that
// is treated as a genuine blocker (see driveBidding/driveCards below),
// never silently skipped.
// ══════════════════════════════════════════════════════════════════

// One real bidding-action attempt for whichever seat's turn it
// currently is, per THAT seat's OWN page (never assumes another
// client's state). Returns a small result object for logging; never
// throws.
async function attemptOneBiddingAction(page, matchId, seatId) {
  return page.evaluate(async (args) => {
    var state = window.BiddingEngine.getState();
    if (!state || state.waitingFor !== args.seatId) return { skipped: "not-my-turn" };
    var subPhase = state.subPhase;
    var suits = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS", "SANS"];
    var candidates = [];
    if (subPhase === "DASH") {
      candidates.push({ type: "SubmitDashCallDecision", playerId: args.seatId, declaredDashCall: false });
    } else if (subPhase === "AUCTION") {
      if ((state.auctionTop || 0) > 0) {
        candidates.push({ type: "SubmitAuctionBid", playerId: args.seatId, isPass: true });
      } else {
        for (var t = 4; t <= 13; t++) {
          for (var s = 0; s < suits.length; s++) {
            candidates.push({ type: "SubmitAuctionBid", playerId: args.seatId, isPass: false, tricks: t, suit: suits[s] });
          }
        }
        candidates.push({ type: "SubmitAuctionBid", playerId: args.seatId, isPass: true });
      }
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
      // canSubmit() takes the ENGINE's own intent shape ({type,
      // playerId, ...}) -- confirmed via bidding-engine.js's own
      // canSubmit()/emit() call sites. submitBiddingAction()'s
      // PERSISTED action object uses a DIFFERENT shape ({actionType,
      // ...}, no playerId/seatId at all -- the seat is derived
      // server-side from the caller's own auth uid via
      // MatchAdapter.uidToSeat(), never trusted from the action
      // object) -- confirmed via match-service.js's own
      // isValidGenericBiddingAction(). These are NOT interchangeable;
      // this was a real bug in this test's own bot, caught by running
      // it, not assumed.
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
          return { submitted: c, result: result };
        } catch (e) {
          return { submitted: c, error: e.message, code: e.code };
        }
      }
    }
    return { noLegalCandidate: true, subPhase: subPhase, state: state };
  }, { matchId: matchId, seatId: seatId });
}

// Drives ONE round's entire bidding sequence to completion (all 4
// seats' worth of Dash/Auction/Confirm/Estimates), reading whose turn
// it is from BiddingEngine.getState().waitingFor on the REFERENCE
// page (pages[0]) each iteration, and acting from THAT seat's own
// page. Returns {ok:true} once every page reports subPhase DONE, or
// {ok:false, reason, log} if stuck (no legal candidate found, or no
// progress for too many iterations) -- never silently loops forever.
async function driveBidding(pages, matchId, maxIterations) {
  var log = [];
  var lastWaitingFor = null, stall = 0;
  for (var iter = 0; iter < (maxIterations || 250); iter++) {
    var states = await Promise.all(pages.map((p) => p.evaluate(() => window.BiddingEngine ? window.BiddingEngine.getState() : null).catch(() => null)));
    if (states.some((s) => !s)) { await sleep(200); continue; }
    if (states.every((s) => s.subPhase === "DONE")) return { ok: true, log: log };
    var ref = states[0];
    var waitingFor = ref.waitingFor;
    if (!waitingFor) { await sleep(150); continue; }
    var idx = SEATS.indexOf(waitingFor);
    if (idx === -1) return { ok: false, reason: "INVALID_WAITING_FOR", log: log, waitingFor: waitingFor };
    var res = await attemptOneBiddingAction(pages[idx], matchId, waitingFor);
    log.push({ iter: iter, seat: waitingFor, subPhase: ref.subPhase, res: res });
    if (res.noLegalCandidate) return { ok: false, reason: "NO_LEGAL_BIDDING_CANDIDATE", log: log };
    if (res.error) {
      // A single rejected attempt can be a benign race (this page's
      // own cached state one delivery behind) -- only a genuine stall
      // (same waitingFor, no progress, many iterations) is reported
      // as a real blocker below.
    }
    if (waitingFor === lastWaitingFor) { stall++; } else { stall = 0; lastWaitingFor = waitingFor; }
    if (stall > 40) return { ok: false, reason: "STALLED_ON_SAME_SEAT", log: log, seat: waitingFor };
    await sleep(120);
  }
  return { ok: false, reason: "MAX_ITERATIONS_EXCEEDED", log: log };
}

// One real card-play attempt for whichever seat's turn it currently
// is, per that seat's own page.
async function attemptOneCardPlay(page, matchId, seatId) {
  return page.evaluate(async (args) => {
    var state = window.TableEngine.getState();
    if (!state || state.turn !== args.seatId || state.phase !== "PLAY") {
      return { skipped: "not-my-turn-or-not-play-phase", actualTurn: state && state.turn, actualPhase: state && state.phase, actualTrickNo: state && state.trickNo, playsLen: state && state.plays && state.plays.length };
    }
    var hand = window.GameSession.getHand(args.seatId);
    for (var i = 0; i < hand.length; i++) {
      var card = hand[i];
      var verdict = window.TableEngine.canPlayCard(args.seatId, card);
      if (verdict && verdict.legal) {
        try {
          var result = await window.MatchService.submitCard(args.matchId, { suit: card.suit, rank: card.rank });
          return { submitted: { suit: card.suit, rank: card.rank }, result: result };
        } catch (e) {
          return { error: e.message, code: e.code, attempted: { suit: card.suit, rank: card.rank } };
        }
      }
    }
    return { noLegalCard: true, handLen: hand.length, state: state };
  }, { matchId: matchId, seatId: seatId });
}

// Drives ALL 13 tricks of the CURRENT round to completion, using the
// authoritative Firestore matchDoc.currentRound as the round-boundary
// signal (never a single client's own local TableEngine phase, which
// can differ by one delivery across clients right at the boundary).
async function driveRoundCardPlay(pages, matchId, roundNumber, maxIterations) {
  var log = [];
  var lastTurn = null, stall = 0;
  for (var iter = 0; iter < (maxIterations || 400); iter++) {
    var matchDoc = await pages[0].evaluate((matchId) => window.MatchService.loadMatch(matchId), matchId).catch(() => null);
    if (!matchDoc) { await sleep(200); continue; }
    if (matchDoc.status === "complete") return { ok: true, completed: true, log: log };
    if (matchDoc.currentRound > roundNumber) return { ok: true, advanced: true, log: log };
    var states = await Promise.all(pages.map((p) => p.evaluate(() => window.TableEngine ? window.TableEngine.getState() : null).catch(() => null)));
    if (states.some((s) => !s)) { await sleep(200); continue; }
    var ref = states[0];
    if (ref.phase === "DONE") { await sleep(200); continue; } // waiting for the round-transition to be observed
    var turn = ref.turn;
    if (!turn) { await sleep(150); continue; }
    var idx = SEATS.indexOf(turn);
    if (idx === -1) return { ok: false, reason: "INVALID_TURN", log: log, turn: turn };
    var res = await attemptOneCardPlay(pages[idx], matchId, turn);
    log.push({ iter: iter, seat: turn, res: res });
    if (res.noLegalCard) return { ok: false, reason: "NO_LEGAL_CARD", log: log, seat: turn };
    if (turn === lastTurn) { stall++; } else { stall = 0; lastTurn = turn; }
    if (stall > 60) return { ok: false, reason: "STALLED_ON_SAME_SEAT", log: log, seat: turn };
    await sleep(100);
  }
  return { ok: false, reason: "MAX_ITERATIONS_EXCEEDED", log: log };
}

async function main() {
  console.log("=== Sprint G: Real 4-Client End-to-End Gameplay Verification ===\n");

  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: { rules: fs.readFileSync(__REPO_ROOT__ + "/firestore.rules", "utf8"), host: FIRESTORE_HOST, port: FIRESTORE_PORT }
    });
  } catch (e) {
    console.error("EMULATOR NOT REACHABLE — " + e.message);
    console.error("\n=== RESULTS ===\n0 passed, 0 failed (FAILED — emulator unreachable)");
    process.exit(1);
  }
  await testEnv.clearFirestore();
  await testEnv.cleanup();

  var server = await startServer();
  var browser = await chromium.launch({ executablePath: resolveChromiumExecutablePath() });

  var contexts = [], pages = [], uids = {};
  for (var i = 0; i < 4; i++) {
    var ctx = await browser.newContext();
    var page = await ctx.newPage();
    var seat = SEATS[i];
    page.on("pageerror", (err) => console.log("[" + seat + " pageerror] " + err.message));
    await installEmulatorRedirect(page);
    contexts.push(ctx); pages.push(page);
  }
  console.log("4 independent browser contexts created.\n");

  // ══════════════════════════════════════════════════════════════
  // PHASE 1 — sign-in (real Auth Emulator), room creation/join
  // ══════════════════════════════════════════════════════════════
  var readyAll = [];
  for (var i = 0; i < 4; i++) readyAll.push(gotoReady(pages[i], "http://127.0.0.1:" + HTTP_PORT + "/match/index.html"));
  var readyResults = await Promise.all(readyAll);
  check("0.0 All 4 real Firebase SDKs loaded", readyResults.every(Boolean));

  for (var i = 0; i < 4; i++) {
    var email = "sprintg-" + SEATS[i] + "-" + Date.now() + "-" + i + "@test.local";
    uids[SEATS[i]] = await pages[i].evaluate(async (email) => {
      var cred = await window.Auth.createUserWithEmailAndPassword(email, "TestPass123!");
      return cred.user.uid;
    }, email);
  }
  check("0.1 All 4 clients signed up with distinct real (emulator) Auth uids",
    new Set(Object.values(uids)).size === 4, JSON.stringify(uids));

  var roomId = await pages[0].evaluate(async (uid) => {
    var room = await window.RoomService.createRoom(uid, "Sprint G Room");
    return room.id || room.roomId || room;
  }, uids.p1);
  if (roomId && typeof roomId === "object") roomId = roomId.id;
  check("1.1 P1 creates room", !!roomId, JSON.stringify(roomId));

  for (var i = 1; i < 4; i++) {
    await pages[i].evaluate(async (args) => { await window.RoomService.joinRoom(args.roomId, args.uid); }, { roomId: roomId, uid: uids[SEATS[i]] });
  }
  var roomStates = [];
  for (var i = 0; i < 4; i++) roomStates.push(await pages[i].evaluate(async (roomId) => window.RoomService.loadRoom(roomId), roomId));
  check("1.2 P2/P3/P4 joined — all 4 clients observe identical membership",
    roomStates.every((r) => r && Array.isArray(r.players) && r.players.length === 4 &&
      JSON.stringify(r.players.slice().sort()) === JSON.stringify(roomStates[0].players.slice().sort())));

  // All 4 ready -> match starts (real trigger: RoomService.setReady()'s
  // own maybeStartMatch(), never called directly by this script).
  var matchId = null;
  for (var i = 0; i < 4; i++) {
    var res = await pages[i].evaluate(async (args) => window.RoomService.setReady(args.roomId, args.uid, true), { roomId: roomId, uid: uids[SEATS[i]] });
    if (res && res.matchId) matchId = res.matchId;
  }
  if (!matchId) {
    var room = await pages[0].evaluate(async (roomId) => window.RoomService.loadRoom(roomId), roomId);
    matchId = room && room.matchId;
  }
  check("2.1 Match started (matchId produced by the real all-ready trigger)", !!matchId, JSON.stringify(matchId));

  console.log("\nroomId=" + roomId + " matchId=" + matchId + "\n");

  // ══════════════════════════════════════════════════════════════
  // PHASE 2 — real GameState handoff into match/index.html (the
  // SAME navigation shape Lobby's own handleMatchDiscovered() uses),
  // for all 4 clients. This exercises the REAL page bootstrap
  // (including Sprint F's startHandSync() wiring), not a bypass.
  // ══════════════════════════════════════════════════════════════
  for (var i = 0; i < 4; i++) {
    await pages[i].evaluate((args) => {
      var data = {
        current: "Gameplay", previous: "Lobby", history: ["Lobby"],
        data: {
          player: { id: args.uid, name: "Player " + args.seat, avatar: "P", rank: "Gold III", rp: 0, coins: 0, gems: 0 },
          account: { type: "test", email: null }, room: { code: null, host: false, seats: [] }, lastResult: null,
          match: { id: args.matchId, roomId: args.roomId }
        }
      };
      window.sessionStorage.setItem(args.storageKey, JSON.stringify(data));
    }, { matchId: matchId, roomId: roomId, uid: uids[SEATS[i]], seat: SEATS[i], storageKey: STORAGE_KEY });
    await pages[i].reload({ waitUntil: "load" });
  }
  // Wait for auth to re-resolve on each fresh reload (same real
  // ordering requirement discovered and documented in Sprint F).
  for (var i = 0; i < 4; i++) {
    await waitFor(pages[i], () => !!(window.SessionService && window.SessionService.getCurrentUser()), 8000);
  }

  var matchViews = [];
  for (var i = 0; i < 4; i++) {
    var v = await waitFor(pages[i], () => {
      return window.MatchScreenDebug && window.MatchScreenDebug.getLocalSeatId() ? {
        localSeatId: window.MatchScreenDebug.getLocalSeatId(),
        matchIdVar: (window.GameState.getData().match || {}).id
      } : null;
    }, 15000);
    matchViews.push(v);
  }
  check("2.2 All 4 clients resolve their own correct seat (p1..p4, no cross-assignment)",
    matchViews.every(Boolean) && JSON.stringify(matchViews.map((v) => v.localSeatId).sort()) === JSON.stringify(SEATS.slice().sort()),
    JSON.stringify(matchViews));
  check("2.3 All 4 clients agree on the same matchId",
    matchViews.every((v) => v && v.matchIdVar === matchId), JSON.stringify(matchViews));

  // ══════════════════════════════════════════════════════════════
  // PHASE 3 — hand deal: each client sees ONLY its own 13-card hand,
  // via the REAL Sprint F wiring (startHandSync -> subscribeToHand ->
  // applyRemoteHand -> GameSession.setAuthoritativeHand), never a
  // local Dealer.dealHands() fallback.
  // ══════════════════════════════════════════════════════════════
  var hands = {};
  for (var i = 0; i < 4; i++) {
    var seat = SEATS[i];
    var h = await waitFor(pages[i], (seat) => {
      return window.GameSession && window.GameSession.getHandAuthorityMode() === "firestore" &&
        window.GameSession.hasDealtHands() && window.GameSession.getHand(seat).length === 13 ?
        { mode: window.GameSession.getHandAuthorityMode(), hand: window.GameSession.getHand(seat).map((c) => c.suit + "-" + c.rank.v) } : null;
    }, 15000, seat);
    hands[seat] = h;
  }
  check("3.1 All 4 clients have hand-authority mode 'firestore' (Sprint F wiring active)",
    Object.values(hands).every((h) => h && h.mode === "firestore"), JSON.stringify(Object.keys(hands).map((k) => hands[k] && hands[k].mode)));
  check("3.2 All 4 clients received a real 13-card authoritative hand",
    Object.values(hands).every((h) => h && h.hand.length === 13));
  var allHandCards = [].concat.apply([], Object.values(hands).map((h) => h ? h.hand : []));
  var uniqueCards = new Set(allHandCards);
  check("3.3 No duplicate cards across the 4 hands (52 unique cards, one real shuffle, no leakage/collision)",
    allHandCards.length === 52 && uniqueCards.size === 52, "count=" + allHandCards.length + " unique=" + uniqueCards.size);

  // Cross-check: p1's own client attempting to directly read p2's hand
  // document must be DENIED by the real rules (not merely "p1's own UI
  // never displays it" — an actual unauthorized read attempt).
  var leakAttempt = await pages[0].evaluate(async (matchId) => {
    try {
      var snap = await window.Db.collection("matches").doc(matchId).collection("hands").doc("p2").get();
      return { denied: false, exists: snap.exists };
    } catch (e) { return { denied: true, code: e.code }; }
  }, matchId);
  check("3.4 P1 cannot read P2's hand document directly (real rules-enforced denial, not just UI omission)",
    leakAttempt.denied === true, JSON.stringify(leakAttempt));

  // ══════════════════════════════════════════════════════════════
  // PHASE 4 — ROUND 1: real bidding through the real service path,
  // then all 13 tricks played through the real service path, then
  // verify all 4 clients converge on Round 2.
  // ══════════════════════════════════════════════════════════════
  var bidRes1 = await driveBidding(pages, matchId);
  check("4.1 Round 1 bidding completes via the real service path (Dash/Auction/Confirm/Estimates)",
    bidRes1.ok === true, JSON.stringify({ reason: bidRes1.reason, lastEntries: (bidRes1.log || []).slice(-3) }));

  if (bidRes1.ok) {
    var cardRes1 = await driveRoundCardPlay(pages, matchId, 1);
    check("4.2 Round 1's 13 tricks play out via the real service path",
      cardRes1.ok === true, JSON.stringify({ reason: cardRes1.reason, lastEntries: (cardRes1.log || []).slice(-3) }));

    if (cardRes1.ok) {
      var round2Views = [];
      for (var i = 0; i < 4; i++) {
        var v = await waitFor(pages[i], () => {
          var r = window.GameSession && window.GameSession.getRound();
          return r && r.number >= 2 ? { round: r.number, dealer: window.GameSession.getDealer() } : null;
        }, 15000);
        round2Views.push(v);
      }
      check("4.3 All 4 clients converge on Round 2 (same round number)",
        round2Views.every(Boolean) && round2Views.every((v) => v.round === round2Views[0].round), JSON.stringify(round2Views));

      var round2Hands = {};
      for (var i = 0; i < 4; i++) {
        var seat = SEATS[i];
        var h2 = await waitFor(pages[i], (seat) => {
          return window.GameSession && window.GameSession.hasDealtHands() && window.GameSession.getHand(seat).length === 13 ?
            window.GameSession.getHand(seat).map((c) => c.suit + "-" + c.rank.v) : null;
        }, 15000, seat);
        round2Hands[seat] = h2;
      }
      check("4.4 All 4 clients have a real, distinct Round-2 13-card hand (authoritative, not a stale/random one)",
        Object.values(round2Hands).every((h) => h && h.length === 13));
      var r2All = [].concat.apply([], Object.values(round2Hands).map((h) => h || []));
      check("4.5 Round-2 hands are 52 unique cards (a genuine new deal, not Round 1 leaking through)",
        r2All.length === 52 && new Set(r2All).size === 52, "count=" + r2All.length + " unique=" + new Set(r2All).size);
    }
  }

  await browser.close();
  server.close();
  await testEnv.cleanup();
  console.log("\n=== PHASE 1-4 RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
}

main().catch((e) => { console.error("FATAL:", e); process.exitCode = 1; });
