const path = require("path");
// Portability fix (established convention this session — see every
// other *.rules-emulator.test.cjs / browser test in this repo):
const __REPO_ROOT__ = path.join(__dirname, "..");
// Sprint F (Hand Synchronization on Reconnect) — REAL browser + REAL
// Firestore/Auth Emulator closure test.
//
// ROOT CAUSE this sprint fixes (confirmed by direct source read, not
// assumed): design-ui/match-adapter.js's MatchAdapter.startHandSync()
// — which flips GameSession into "firestore" hand-authority mode and
// wires the real matches/{matchId}/hands/{seatId} subscription — was
// fully implemented and already unit-tested (tests/hand-sync.test.cjs)
// but was NEVER actually called anywhere in design-ui/match/index.html.
// Confirmed via grep: zero occurrences of startHandSync/subscribeToHand/
// setAuthoritativeHand/setHandAuthorityMode in that file before this
// sprint. Every other start*Sync() function (startRoundSync,
// startBidSync, startCardSync, startTrickSync, etc.) WAS wired in —
// only this one was missing. Because of this, GameSession.
// ensureHandsDealt() (called both by bootstrapEngineOnce()'s own
// diagnostic and, every round, by BiddingEngine.initState()) ran with
// handAuthorityMode permanently stuck at its "local" default, dealing
// a brand-new, independently-random LOCAL hand via Dealer.dealHands()
// on every real match — not merely on reconnect, but on the very
// first load too.
//
// This file drives the REAL, unmodified design-ui/match/index.html
// (real Firebase compat SDKs, real Firestore Rules Emulator, real
// Auth Emulator) through Scenarios A-E from the sprint's own brief, to
// prove the fix (two small additions to match/index.html: an early
// GameSession.setHandAuthorityMode("firestore") call, plus a one-shot
// MatchAdapter.startHandSync(matchId, localSeatId) call once the local
// seat is known) actually closes the gap end-to-end, not just at the
// unit level.
//
// Match/hand documents for every scenario are seeded directly via
// testEnv.withSecurityRulesDisabled() (this project's own established
// convention for real-emulator test setup — see
// hand-sync.rules-emulator-rematch-fix.test.cjs) — this is Node-side
// setup only; the ACTUAL client-facing behavior under test always goes
// through the real page's own bootstrap code, the real
// MatchService.subscribeToMatch()/subscribeToHand(), and the real
// firestore.rules compiled by the real emulator.
const fs = require("fs");
const http = require("http");
const { chromium } = require("playwright");
const { initializeTestEnvironment } = require("@firebase/rules-unit-testing");
const { resolveChromiumExecutablePath } = require("../scripts/resolve-chromium.cjs");

const ROOT = __REPO_ROOT__ + "/design-ui";
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json" };
const HTTP_PORT = 5231;
const FIRESTORE_HOST = "127.0.0.1", FIRESTORE_PORT = 8080;
const AUTH_HOST = "127.0.0.1", AUTH_PORT = 9099;
// MUST match design-ui/firebase-init.js's own real, hardcoded
// `projectId` exactly — the real browser page never overrides that
// config field, only which HOST/PORT useEmulator() points the SDK at
// (see installEmulatorRedirect() below). The Firestore Emulator
// partitions data per-project; a mismatched projectId here would seed
// data into a namespace the browser's own Firestore client never
// reads from, at all — discovered directly (not assumed) by reading
// back a just-seeded document via testEnv immediately after seeding
// and finding it `undefined` until this was corrected.
const PROJECT_ID = "made---estimation-card-game";
const CDN_CACHE = __REPO_ROOT__ + "/tests/fixtures/firebase-cdn";
const CDN_MAP = {
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-app-compat.js": "firebase-app-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth-compat.js": "firebase-auth-compat.js",
  "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore-compat.js": "firebase-firestore-compat.js"
};
const STORAGE_KEY = "estimation_game_state_v1";
const SESSION_STORAGE_KEY = "estimation_game_session_v1";

var pass = 0, fail = 0;
function check(label, ok, note) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label + (note ? " -- " + note : "")); fail++; }
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

// Redirects both the Firestore and Auth compat SDK instances to the
// REAL local emulators, immediately after window.Db/window.Auth are
// set by the real firebase-init.js — a network-layer interception
// (Playwright route), never a change to any file on disk. Same
// technique already established and proven in
// verify-sprint-b-multiclient.cjs, ported to this session's
// already-fixed portability conventions (vendored CDN cache,
// resolveChromiumExecutablePath(), no hardcoded sandbox path).
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

function cardSetKey(cards) {
  return cards.map(function (c) { return c.suit + "-" + c.rank.v; }).sort().join(",");
}

function makeHand(seed) {
  // A deterministic, arbitrary-but-fixed 13-card hand distinguishable
  // from any OTHER seeded hand in this file by seed alone (never
  // relies on Math.random() — this is TEST DATA representing "whatever
  // Dealer.dealHands() would have produced," not a redeal).
  var suits = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS", "SANS"];
  var out = [];
  for (var i = 0; i < 13; i++) {
    var v = 2 + ((seed + i) % 13);
    out.push({ suit: suits[(seed + i) % suits.length], rank: { v: v, s: String(v) } });
  }
  return out;
}

function baseMatch(overrides) {
  return Object.assign({
    roomId: "room-f", status: "starting", createdAt: 1,
    currentRound: 1, maxRounds: 18, extendedRounds: [], version: 1, biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null }, lastBidSeat: null,
    cardLog: [], lastCardSeat: null, cardPhase: null, biddingLog: [],
    gameState: { initialized: true, dealtRound: 1 }
  }, overrides || {});
}

async function main() {
  console.log("=== Sprint F: Hand Synchronization on Reconnect — Real Browser + Real Emulator Verification ===\n");

  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: { rules: fs.readFileSync(__REPO_ROOT__ + "/firestore.rules", "utf8"), host: FIRESTORE_HOST, port: FIRESTORE_PORT }
    });
  } catch (e) {
    console.error("EMULATOR NOT REACHABLE — " + e.message);
    console.error("\nFATAL: the Firestore Rules Emulator must be running on 127.0.0.1:8080 for this test to run. This is a HARD FAILURE, not a skip.");
    console.error("\n=== RESULTS ===\n0 passed, 0 failed (FAILED — emulator unreachable)");
    process.exit(1);
  }
  await testEnv.clearFirestore();

  var server = await startServer();
  var browser = await chromium.launch({ executablePath: resolveChromiumExecutablePath() });

  async function seed(fn) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await fn(ctx.firestore());
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

  // Real users always reach the Match screen already signed in (via
  // Login) — MatchService.subscribeToMatch() is attached synchronously
  // at page bootstrap, before waiting on auth, and firestore.rules
  // correctly denies an unauthenticated read outright. Discovered
  // directly (not assumed): attaching the subscription BEFORE sign-in
  // hits that permission-denied path, which MatchService's own
  // non-retryable-error classification then locks out PERMANENTLY for
  // that subscription (by design — see match-service.js's own
  // classifyError()/isRetryable()) — signing in afterward does not
  // un-stick it. The realistic (and only workable) sequence a real
  // signed-in user's browser has is: sign in FIRST, THEN load the
  // match screen with a real matchId already in GameState, with the
  // match document ITSELF already existing in Firestore before that
  // navigation ever happens — exactly how a real client only ever
  // reaches this screen after MatchService.startMatch() already
  // created the match document. Discovered directly (not assumed):
  // subscribing to a matchId that does NOT YET exist crashes rules
  // evaluation (a null-resource evaluation error, surfaced by the SDK
  // as `permission-denied`) rather than cleanly resolving "not found"
  // — and MatchService's own non-retryable-error classification then
  // locks that subscription out PERMANENTLY, even once the document is
  // later created. This is a real Firestore/rules-evaluation
  // brittleness, but it is NOT a scenario a real client can ever
  // actually hit (the match document always already exists by the
  // time GameState hands off a matchId) — out of THIS sprint's scope
  // (no firestore.rules change), and worked around here simply by
  // seeding in the correct, realistic order: sign in → seed the match
  // document → THEN navigate/reload into it.
  //
  // signInOnly(): loads the page with no matchId (so no match
  // subscription attaches yet), signs in (or signs back in) for real.
  async function signInOnly(page, url, email) {
    await gotoReady(page, url);
    return page.evaluate(async (email) => {
      var cred;
      try {
        cred = await window.Auth.createUserWithEmailAndPassword(email, "TestPass123!");
      } catch (e) {
        cred = await window.Auth.signInWithEmailAndPassword(email, "TestPass123!");
      }
      return cred.user.uid;
    }, email);
  }

  // loadMatchScreen(): seeds GameState's own sessionStorage key with
  // the real matchId, then reloads — mirroring a real signed-in user
  // navigating to their (already-existing) match — and waits for
  // Firebase Auth to actually re-resolve the persisted session on this
  // fresh page load before returning, since the match document's real
  // "document created"/first-read delivery can otherwise race ahead
  // of SessionService's own onAuthStateChanged resolution.
  async function loadMatchScreen(page, matchId) {
    await page.evaluate((args) => {
      var data = {
        current: "Gameplay", previous: "Lobby", history: ["Lobby"],
        data: {
          player: { id: "test", name: "Test Player", avatar: "T", rank: "Gold III", rp: 0, coins: 0, gems: 0 },
          account: { type: "test", email: null },
          room: { code: null, host: false, seats: [] },
          lastResult: null,
          match: { id: args.matchId }
        }
      };
      window.sessionStorage.setItem(args.storageKey, JSON.stringify(data));
    }, { matchId: matchId, storageKey: STORAGE_KEY });
    await page.reload({ waitUntil: "load" });
    var deadline = Date.now() + 8000;
    while (Date.now() < deadline) {
      var resolved = await page.evaluate(() => {
        return !!(window.SessionService && window.SessionService.getCurrentUser());
      }).catch(function () { return false; });
      if (resolved) break;
      await new Promise((r) => setTimeout(r, 100));
    }
  }

  async function waitFor(page, fn, timeoutMs) {
    var deadline = Date.now() + (timeoutMs || 8000);
    while (Date.now() < deadline) {
      var v = await page.evaluate(fn).catch(function () { return null; });
      if (v) return v;
      await new Promise((r) => setTimeout(r, 100));
    }
    return null;
  }

  // ══════════════════════════════════════════════════════════════
  // SCENARIO A — Reload during current round.
  // ══════════════════════════════════════════════════════════════
  var matchIdA = "match-a";
  var handA = makeHand(1);
  var ctxA = await browser.newContext();
  var pageA = await ctxA.newPage();
  await installEmulatorRedirect(pageA);

  var emailA = "hs-a-" + Date.now() + "@test.local";
  var uidA = await signInOnly(pageA, "http://127.0.0.1:" + HTTP_PORT + "/match/index.html", emailA);
  check("A.0 Real Firebase SDK loaded and signed-in user resolved", !!uidA, JSON.stringify(uidA));

  // The match document (with THIS real uid as p1) already exists
  // BEFORE this client ever navigates to it — exactly mirroring real
  // production ordering (MatchService.startMatch() always creates the
  // match document before any client's GameState handoff to this
  // screen occurs).
  await seed(async (db) => {
    await db.collection("matches").doc(matchIdA).set(baseMatch({
      players: [uidA, "otherB", "otherC", "otherD"],
      seats: { p1: uidA, p2: "otherB", p3: "otherC", p4: "otherD" },
      dealer: uidA, turn: uidA
    }));
    await db.collection("matches").doc(matchIdA).collection("hands").doc("p1").set({
      seatId: "p1", round: 1, cards: handA, version: 1
    });
  });
  await loadMatchScreen(pageA, matchIdA);

  var afterInitial = await waitFor(pageA, () => {
    return window.MatchScreenDebug && window.MatchScreenDebug.getHandSyncStarted() &&
      window.GameSession && window.GameSession.hasDealtHands() &&
      window.GameSession.getHand("p1").length === 13 ? window.GameSession.getHand("p1") : null;
  });
  check("A.1 Initial load: real per-seat hand sync started (MatchAdapter.startHandSync wired in)",
    !!afterInitial, JSON.stringify(afterInitial));
  check("A.2 Initial load: the CLIENT's hand is exactly the authoritative seeded hand H1 (never a local random deal)",
    !!afterInitial && cardSetKey(afterInitial) === cardSetKey(handA));

  // Clear the LOCAL GameSession cache before reloading — otherwise a
  // plain reload would trivially "pass" by reading its own stale
  // sessionStorage cache rather than genuinely re-deriving the hand
  // from the live Firestore resync. This is the rigorous form of
  // "reload" the sprint brief's Scenario A actually requires.
  await pageA.evaluate((key) => { window.sessionStorage.removeItem(key); }, SESSION_STORAGE_KEY);
  await pageA.reload({ waitUntil: "load" });
  await waitFor(pageA, () => !!(window.SessionService && window.SessionService.getCurrentUser()), 8000);

  var afterReload = await waitFor(pageA, () => {
    return window.GameSession && window.GameSession.hasDealtHands() &&
      window.GameSession.getHand("p1").length === 13 ? window.GameSession.getHand("p1") : null;
  });
  check("A.3 Reload (with local cache cleared) restores exactly H1 — never a newly-generated local hand",
    !!afterReload && cardSetKey(afterReload) === cardSetKey(handA), JSON.stringify(afterReload));

  // ══════════════════════════════════════════════════════════════
  // SCENARIO B — Disconnect then reconnect (no round change).
  // ══════════════════════════════════════════════════════════════
  await pageA.context().setOffline(true);
  await new Promise((r) => setTimeout(r, 500));
  await pageA.context().setOffline(false);
  var afterReconnect = await waitFor(pageA, () => {
    return window.GameSession && window.GameSession.getHand("p1").length === 13 ? window.GameSession.getHand("p1") : null;
  });
  check("B.1 After a real disconnect+reconnect (no round change), the hand is still exactly H1",
    !!afterReconnect && cardSetKey(afterReconnect) === cardSetKey(handA), JSON.stringify(afterReconnect));

  // ══════════════════════════════════════════════════════════════
  // SCENARIO C — Round transition while disconnected.
  // ══════════════════════════════════════════════════════════════
  var handA2 = makeHand(2);
  await pageA.context().setOffline(true);
  await seed(async (db) => {
    await db.collection("matches").doc(matchIdA).update({ currentRound: 2, version: 2, dealer: "otherB", turn: "otherB", gameState: { initialized: true, dealtRound: 2 } });
    await db.collection("matches").doc(matchIdA).collection("hands").doc("p1").set({
      seatId: "p1", round: 2, cards: handA2, version: 2
    });
  });
  await pageA.context().setOffline(false);

  var afterRoundTransition = await waitFor(pageA, () => {
    var state = window.GameSession && window.GameSession.getRound();
    var hand = window.GameSession && window.GameSession.getHand("p1");
    return state && state.number === 2 && hand && hand.length === 13 ? { round: state.number, hand: hand } : null;
  }, 12000);
  check("C.1 After reconnect, GameSession's round genuinely advances to Round 2 (real round-transition sync, unrelated to this fix, confirmed still working)",
    !!afterRoundTransition, JSON.stringify(afterRoundTransition));
  check("C.2 Round 2's hand is exactly the AUTHORITATIVE Round-2 hand H2 (never a freshly-generated local Round-2 hand)",
    !!afterRoundTransition && cardSetKey(afterRoundTransition.hand) === cardSetKey(handA2),
    JSON.stringify({ got: afterRoundTransition && cardSetKey(afterRoundTransition.hand), want: cardSetKey(handA2) }));
  check("C.3 The restored Round-2 hand is NOT the stale Round-1 hand H1 (proves this isn't just a cache hit)",
    !!afterRoundTransition && cardSetKey(afterRoundTransition.hand) !== cardSetKey(handA));

  await pageA.close();
  await ctxA.close();

  // ══════════════════════════════════════════════════════════════
  // SCENARIO D — Late join: a client that never loaded before this
  // point subscribes AFTER the hand already exists (seat p2, its own
  // Round-2 hand, seeded at the same time as p1's above).
  // ══════════════════════════════════════════════════════════════
  var handD = makeHand(3);
  var ctxD = await browser.newContext();
  var pageD = await ctxD.newPage();
  await installEmulatorRedirect(pageD);
  // Sign up P2 FIRST, with no matchId yet — before any subscribeToMatch
  // ever attaches for this client. Same reason as signInOnly()'s own
  // doc comment: a subscription attached before this seat is even
  // listed in the match's own players[] would hit the SAME permanent
  // permission-denied lockout.
  var emailD = "hs-d-" + Date.now() + "@test.local";
  var uidP2 = await signInOnly(pageD, "http://127.0.0.1:" + HTTP_PORT + "/match/index.html", emailD);

  // Only NOW — after P2 is real, signed in, AND already a listed
  // player/seat — does the browser's eventual subscription have
  // anything it's actually allowed to read.
  await seed(async (db) => {
    await db.collection("matches").doc(matchIdA).update({ players: [uidA, uidP2, "otherC", "otherD"], seats: { p1: uidA, p2: uidP2, p3: "otherC", p4: "otherD" } });
    await db.collection("matches").doc(matchIdA).collection("hands").doc("p2").set({
      seatId: "p2", round: 2, cards: handD, version: 2
    });
  });
  await loadMatchScreen(pageD, matchIdA);

  var lateJoinHand = await waitFor(pageD, () => {
    return window.GameSession && window.GameSession.getHand("p2").length === 13 ? window.GameSession.getHand("p2") : null;
  }, 12000);
  check("D.1 Late-joining client (never loaded before) receives its own hand directly from Firestore",
    !!lateJoinHand, JSON.stringify(lateJoinHand));
  check("D.2 The late-joined hand is exactly the seeded authoritative hand, not a local deal",
    !!lateJoinHand && cardSetKey(lateJoinHand) === cardSetKey(handD));
  await pageD.close();
  await ctxD.close();

  // ══════════════════════════════════════════════════════════════
  // SCENARIO E — Rematch: a brand-new match must get its OWN new
  // hand, never the previous match's hand.
  // ══════════════════════════════════════════════════════════════
  var matchIdE = "match-e-new";
  var handE = makeHand(4);
  await seed(async (db) => {
    await db.collection("matches").doc(matchIdE).set(baseMatch({
      players: [uidA, "otherB", "otherC", "otherD"],
      seats: { p1: uidA, p2: "otherB", p3: "otherC", p4: "otherD" },
      dealer: uidA, turn: uidA, roomId: "room-f2"
    }));
    await db.collection("matches").doc(matchIdE).collection("hands").doc("p1").set({
      seatId: "p1", round: 1, cards: handE, version: 1
    });
  });
  var ctxE = await browser.newContext();
  var pageE = await ctxE.newPage();
  await installEmulatorRedirect(pageE);
  // Reuse uidA (same real player, new match — exactly the rematch
  // scenario: same person, a freshly created match document). matchIdE
  // already has uidA listed in players[]/seats BEFORE this client ever
  // subscribes (seeded above).
  var uidAAgain = await signInOnly(pageE, "http://127.0.0.1:" + HTTP_PORT + "/match/index.html", emailA);
  check("E.0 Rematch client signs back in as the SAME real uid", uidAAgain === uidA, JSON.stringify({ uidA: uidA, uidAAgain: uidAAgain }));
  await loadMatchScreen(pageE, matchIdE);

  var rematchHand = await waitFor(pageE, () => {
    return window.GameSession && window.GameSession.getHand("p1").length === 13 ? window.GameSession.getHand("p1") : null;
  }, 12000);
  check("E.1 A new match (rematch) delivers a hand for this client",
    !!rematchHand, JSON.stringify(rematchHand));
  check("E.2 The new match's hand is its OWN seeded hand H_E, never the previous match's H1",
    !!rematchHand && cardSetKey(rematchHand) === cardSetKey(handE) && cardSetKey(rematchHand) !== cardSetKey(handA));

  await pageE.close();
  await ctxE.close();

  await browser.close();
  server.close();
  await testEnv.cleanup();

  console.log("\n=== RESULTS ===\n");
  console.log(pass + " passed, " + fail + " failed");
  process.exitCode = fail > 0 ? 1 : 0;
}

main().catch((e) => { console.error("FATAL:", e); process.exitCode = 1; });
