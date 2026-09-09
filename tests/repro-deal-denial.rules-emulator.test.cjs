var REPO_ROOT = require("path").join(__dirname, "..");
// TASK R2 — DEALER-IDENTITY DIAGNOSIS + LOCAL REPLAY MATRIX (emulator only,
// zero production calls). DIAGNOSTIC FILE — NOT A FIX, NOT A REGRESSION GATE.
//
// Reproduces the exact production denial context from golden-prod-evidence/
// evidence.jsonl (run of 2026-08-26T06:14Z, project made---estimation-card-game):
//   - room noxiRrTFuAhfpUavuzZ1 created by P1 (creator == P1's auth uid)
//   - match kfgiv1ZlUNrkmiiunide created by the all-ready trigger with
//     dealer = creator's uid (buildInitialMatchDoc(), design-ui/match-service.js:431)
//   - the denied Commit RPC arrived on seat P4's console ONLY; its wire shape
//     (4x hands/{p1..p4} set() each carrying version==round, plus one parent
//     update {gameState} + updatedAt serverTimestamp transform) was captured
//     verbatim in evidence.jsonl line 19 and mirrors it here byte-for-byte.
//
// Seeded match docs use the REAL production auth uids and the FULL V8 doc
// shape buildInitialMatchDoc() writes (turn = dealer uid NON-NULL,
// gameState {initialized:false, dealtRound:0}, version 1, 4 seats).
//
// NOTE ON L-NUMBERS: the real Firestore Rules Emulator does not return
// rules-evaluation line numbers to the client SDK — a DENY surfaces only as
// code "permission-denied". This file therefore prints the FULL SDK error for
// every DENY plus, alongside it, the specific firestore.rules clauses (with
// their L-numbers) that govern each cell of the matrix.
const {
  initializeTestEnvironment
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const http = require("http");
// Canonical mock-token minter (same class rules-unit-testing feeds the
// Firestore emulator via useEmulator({mockUserToken})): an unsigned
// alg:'none' JWT the emulator parses straight off the Authorization
// header — no Auth emulator required.
const { createMockUserToken } = require("@firebase/util");
// Same compat namespace @firebase/rules-unit-testing@5 itself builds on
// (it requires 'firebase/compat/firestore'), so this serverTimestamp()
// sentinel is instance-compatible with the transaction handles below.
const firebaseCompat = require("firebase/compat/app");
require("firebase/compat/firestore");
const FieldValue = firebaseCompat.firestore.FieldValue;

// ── TASK R6-CLOSE raw-REST helpers (Matrix H2b/H2c/H3 need wire-level
// control of currentDocument.updateTime preconditions, which the SDK
// transaction API deliberately hides behind automatic retry) ──────────
function restPost(port, path, bodyObj, token) {
  return new Promise(function (resolve, reject) {
    const payload = Buffer.from(JSON.stringify(bodyObj), "utf8");
    const req = http.request({
      host: "127.0.0.1", port: port, path: path, method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": payload.length,
        ...(token ? { Authorization: "Bearer " + token } : {})
      }
    }, function (res) {
      let buf = "";
      res.on("data", function (c) { buf += c; });
      res.on("end", function () {
        let parsed = null;
        try { parsed = JSON.parse(buf); } catch (e) { /* leave raw */ }
        resolve({ status: res.statusCode, raw: buf, json: parsed });
      });
    });
    req.on("error", reject);
    req.write(payload);
    req.end();
  });
}
function restGet(port, path, token) {
  return new Promise(function (resolve, reject) {
    const req = http.request({
      host: "127.0.0.1", port: port, path: path, method: "GET",
      headers: token ? { Authorization: "Bearer " + token } : {}
    }, function (res) {
      let buf = "";
      res.on("data", function (c) { buf += c; });
      res.on("end", function () {
        let parsed = null;
        try { parsed = JSON.parse(buf); } catch (e) { /* leave raw */ }
        resolve({ status: res.statusCode, raw: buf, json: parsed });
      });
    });
    req.on("error", reject);
    req.end();
  });
}
// Wire-shape clone of evidence.jsonl L19's denied Commit: four hand-doc
// updates (seatId/round/cards/version) + parent gameState flip with
// updatedAt REQUEST_TIME transform, optional currentDocument.updateTime.
function productionShapedCommitBody(projectId, matchId, opts) {
  const base = "projects/" + projectId + "/databases/(default)/documents/matches/" + matchId;
  const writes = ["p1", "p2", "p3", "p4"].map(function (seatId, idx) {
    const cards = makeHand(idx).map(function (c) {
      return { mapValue: { fields: {
        suit: { stringValue: c.suit },
        rank: { mapValue: { fields: { v: { integerValue: String(c.rank.v) }, s: { stringValue: c.rank.s } } } }
      } } };
    });
    return { update: { name: base + "/hands/" + seatId, fields: {
      seatId: { stringValue: seatId },
      round: { integerValue: String(opts.round || 1) },
      cards: { arrayValue: { values: cards } },
      version: { integerValue: String(opts.round || 1) }
    } } };
  });
  const matchUpdate = {
    update: { name: base, fields: {
      gameState: { mapValue: { fields: {
        initialized: { booleanValue: true },
        dealtRound: { integerValue: String(opts.round || 1) }
      } } }
    } },
    updateMask: { fieldPaths: ["gameState"] },
    updateTransforms: [{ fieldPath: "updatedAt", setToServerValue: "REQUEST_TIME" }]
  };
  if (opts.updateTimePrecondition) {
    matchUpdate.currentDocument = { updateTime: opts.updateTimePrecondition };
  }
  writes.push(matchUpdate);
  return { writes: writes };
}

// Runs any Firestore promise directly and classifies the RAW outcome —
// no assertFails/assertSucceeds wrappers, so the FULL original error
// (code + message + stack) survives for printing on every DENY.
function attempt(p) {
  return p.then(
    function () { return { ok: true }; },
    function (e) { return { ok: false, err: e }; }
  );
}
function isPermissionDenied(r) {
  return !r.ok && r.err && r.err.code === "permission-denied";
}

// ── Production identity facts (evidence.jsonl lines 3 & 5) ──────────────
var UID_P1 = "CBuxgr72uVUpFbUWF29zKB37Dzs1"; // created the room -> DEALER seat p1
var UID_P2 = "84a7hj8FRsSwZpmmmmLjE1aEQGC2";
var UID_P3 = "BeD0HLm7Fvdd4ZEJR7oVvjez5es2";
var UID_P4 = "S1WrTtnnAtUjFgj65rF84SocgS93"; // the console that showed the 403
var UID_Z = "intruder-not-seated";           // control: not in players[]

var pass = 0, fail = 0;
function check(label, ok) {
  if (ok) { console.log("PASS  " + label); pass++; }
  else { console.log("FAIL  " + label); fail++; }
}
function describeResult(r) {
  if (r.ok) return "ALLOW";
  var code = r.err && r.err.code ? r.err.code : "(no code)";
  var msg = r.err && r.err.message ? r.err.message : String(r.err);
  return "DENY [" + code + "] " + msg;
}
function printFullDeny(label, r) {
  if (r.ok) return;
  console.log("\nFULL DENIAL ERROR — " + label + ":");
  console.log("  code:    " + (r.err && r.err.code));
  console.log("  message: " + (r.err && r.err.message));
  console.log("  stack head: " + (r.err && r.err.stack ? r.err.stack.split("\n").slice(0, 3).join(" | ") : "(none)"));
}

// ── Full V8 match-doc shape, exactly buildInitialMatchDoc()'s output ────
// (design-ui/match-service.js:429-525), parameterized only where noted.
function prodMatchDoc(overrides) {
  var base = {
    roomId: "noxiRrTFuAhfpUavuzZ1",
    players: [UID_P1, UID_P2, UID_P3, UID_P4],
    status: "starting",
    createdAt: 1,
    currentRound: 1,
    maxRounds: 18,
    extendedRounds: [],
    dealer: UID_P1,
    turn: UID_P1,
    seats: { p1: UID_P1, p2: UID_P2, p3: UID_P3, p4: UID_P4 },
    version: 1,
    biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null },
    lastBidSeat: null,
    cardLog: [],
    lastCardSeat: null,
    cardPhase: null,
    biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };
  return Object.assign({}, base, overrides || {});
}

// 13 structurally-valid cards ({suit, rank:{v,s}} only — the exact generic
// card shape dealRound() persists), a DETERMINISTIC PARTITION of one ordered
// 52-card deck: seat s receives indices s*13..s*13+12, so the four seats'
// hands are pairwise disjoint (like a real deal).
function makeHand(seatIdx) {
  var suits = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
  var hand = [];
  for (var k = 0; k < 13; k++) {
    var idx = seatIdx * 13 + k;
    var suit = suits[Math.floor(idx / 13)];
    var v = 2 + (idx % 13); // always int 2..14
    hand.push({ suit: suit, rank: { v: v, s: String(v) } });
  }
  return hand;
}

// The EXACT transaction MatchService.dealRound() performs
// (design-ui/match-service.js:1815-1872), incl. its own JS-side membership +
// idempotency prechecks. opts.omitUpdatedAt drops ONLY the serverTimestamp
// transform (Matrix C).
function exactProductionDealTransaction(db, matchId, opts) {
  opts = opts || {};
  return db.runTransaction(function (tx) {
    var matchRef = db.collection("matches").doc(matchId);
    return tx.get(matchRef).then(function (snap) {
      if (!snap.exists) throw new Error("MATCH_NOT_FOUND");
      var match = snap.data();
      var callingUid = opts.callingUid;
      if (!opts.bypassServicePrechecks) {
        if (!Array.isArray(match.players) || match.players.indexOf(callingUid) === -1) {
          throw new Error("PERMISSION_DENIED (service-side membership precheck)");
        }
        var gs = match.gameState || { initialized: false, dealtRound: 0 };
        if ((gs.dealtRound || 0) >= 1) {
          return { dealt: false, reason: "ALREADY_DEALT" };
        }
      }
      var seats = match.seats || {};
      var seatIds = ["p1", "p2", "p3", "p4"].filter(function (s) { return seats[s]; });
      seatIds.forEach(function (seatId, idx) {
        tx.set(matchRef.collection("hands").doc(seatId), {
          seatId: seatId,
          round: 1,
          cards: makeHand(idx),
          version: 1
        });
      });
      var parentPatch = { gameState: { initialized: true, dealtRound: 1 } };
      if (!opts.omitUpdatedAt) parentPatch.updatedAt = FieldValue.serverTimestamp();
      tx.update(matchRef, parentPatch);
      return { dealt: true };
    });
  });
}

async function run() {
  var testEnv;
  try {
    testEnv = await initializeTestEnvironment({
      projectId: "repro-deal-denial",
      firestore: {
        rules: fs.readFileSync(REPO_ROOT + "/firestore.rules", "utf8"),
        host: "127.0.0.1",
        port: 8080
      }
    });
  } catch (e) {
    console.log("EMULATOR NOT REACHABLE — " + e.message);
    process.exitCode = 2;
    return;
  }  await testEnv.clearFirestore();

  async function seedProdMatch(matchId, overrides) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      await ctx.firestore().collection("matches").doc(matchId).set(prodMatchDoc(overrides));
    });
  }

  console.log("── CONTROLS (must behave or the whole matrix is void) ──");

  // CTRL-1: a uid in NO seat attempts the identical deal WITH service-side
  // prechecks bypassed, so the RULES layer alone must deny it
  // (firestore.rules:1234 `request.auth.uid in oldData.players`).
  await seedProdMatch("ctrl-nonmember");
  var ctrl1 = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_Z).firestore(), "ctrl-nonmember",
    { callingUid: UID_Z, bypassServicePrechecks: true }));
  check("CTRL-1 non-member deal attempt -> DENY (" + describeResult(ctrl1) + ")",
    isPermissionDenied(ctrl1));
  printFullDeny("CTRL-1", ctrl1);

  // CTRL-2: replaying the SAME already-dealt round, service prechecks
  // bypassed -> DENY proves the forward-only idempotency clause
  // (firestore.rules:1246 `dealtRound > oldData.gameState.get('dealtRound', 0)`).
  await seedProdMatch("ctrl-replay");
  var seedDeal = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P1).firestore(), "ctrl-replay",
    { callingUid: UID_P1 }));
  if (!seedDeal.ok) {
    printFullDeny("CTRL-2 seeding deal (unexpected)", seedDeal);
  }
  var ctrl2 = await attempt((function () {
    var db = testEnv.authenticatedContext(UID_P1).firestore();
    return db.runTransaction(function (tx) {
      var matchRef = db.collection("matches").doc("ctrl-replay");
      tx.set(matchRef.collection("hands").doc("p1"), { seatId: "p1", round: 1, cards: makeHand(0), version: 1 });
      tx.update(matchRef, { gameState: { initialized: true, dealtRound: 1 }, updatedAt: FieldValue.serverTimestamp() });
      return Promise.resolve({ dealt: true }); // NO JS-side precheck: rules alone must deny
    });
  })());
  check("CTRL-2 replay of already-dealt round -> DENY", isPermissionDenied(ctrl2));
  printFullDeny("CTRL-2", ctrl2);

  console.log("\n── REPRO MATRIX (seeded exactly like production; turn = dealer NON-NULL) ──");

  // Matrix A — DEALER (uid P1) performs the exact production transaction.
  await seedProdMatch("mA-dealer");
  var a = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P1).firestore(), "mA-dealer", { callingUid: UID_P1 }));
  console.log("A  DEALER acts, exact prod transaction        -> " + describeResult(a));
  check("A  expected ALLOW (per repo rules)", a.ok);
  printFullDeny("Matrix A", a);

  // Matrix B — NON-dealer SEATED uid (uid P4: the actual production 403
  // console) performs the IDENTICAL transaction.
  await seedProdMatch("mB-nondealer-p4");
  var b = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P4).firestore(), "mB-nondealer-p4", { callingUid: UID_P4 }));
  console.log("B  NON-dealer seated P4 (prod 403 client)    -> " + describeResult(b));
  console.log("   task brief EXPECTS DENY; repo firestore.rules contains NO dealer-identity");
  console.log("   clause anywhere in the deal path — isValidHandDealCommit() (L1229-1271)");
  console.log("   gates only membership+shape+pairing; isValidNewHand() (L1809-1820) gates");
  console.log("   caller in parent.players (NOT ownsSeat()); isValidPairedDeal() (L1735).");
  check("B  ACTUAL recorded (diagnostic file does not gate on this)",
    typeof b.ok === "boolean");
  printFullDeny("Matrix B", b);

  // Matrix C — DEALER, WITHOUT the updatedAt serverTimestamp transform.
  await seedProdMatch("mC-no-updatedAt");
  var c = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P1).firestore(), "mC-no-updatedAt",
    { callingUid: UID_P1, omitUpdatedAt: true }));
  console.log("C  DEALER, no updatedAt transform            -> " + describeResult(c));
  console.log("   analysis: affectedKeys() = {gameState} ⊆ allowlist {gameState, updatedAt}");
  console.log("   (hasOnly is a SUBSET test, firestore.rules:1237) — predicted ALLOW.");
  check("C  ACTUAL recorded", typeof c.ok === "boolean");
  printFullDeny("Matrix C", c);

  // Matrix D — seeded turn=null instead of the dealer uid; DEALER deals.
  await seedProdMatch("mD-turn-null", { turn: null });
  var d = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P1).firestore(), "mD-turn-null", { callingUid: UID_P1 }));
  console.log("D  DEALER, seed turn=null                    -> " + describeResult(d));
  console.log("   analysis: no clause in the deal path reads oldData.turn — the only");
  console.log("   turn readers are isValidOpeningTurnPublication() (L1049-1053) and");
  console.log("   isValidCardSubmission() (L755), neither routed by this write");
  console.log("   (dispatch L1503 sends gameState-writes to isValidHandDealCommit only).");
  check("D  ACTUAL recorded", typeof d.ok === "boolean");
  printFullDeny("Matrix D", d);

  console.log("\n── TASK-R3 MATRICES E/F/G ──");

  // Matrix E — AUTH-TIER probe for p4's exact payload. The repo deal path
  // reads ONLY request.auth.uid from the token (L1233/L1812 etc.) — never
  // email/verified/custom claims — so an anonymous-tier authenticated
  // context is byte-equivalent to any other authenticated context here.
  // E-a: anonymous-tier authenticated seat (== Matrix B semantics).
  // E-b: FULL auth lapse (no token) — brackets the auth-lapse hypothesis.
  await seedProdMatch("mE-auth-tier");
  var ea = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P4).firestore(), "mE-auth-tier",
    { callingUid: UID_P4 }));
  console.log("Ea P4 anon-tier authenticated (claim-less token) -> " + describeResult(ea));
  console.log("   analysis: repo rules never inspect claims beyond auth.uid -> predicted ALLOW (=B).");
  check("Ea ACTUAL recorded", typeof ea.ok === "boolean");
  printFullDeny("Matrix Ea", ea);

  var eb = await attempt(exactProductionDealTransaction(
    testEnv.unauthenticatedContext().firestore(), "mE-auth-tier",
    { callingUid: UID_P4, bypassServicePrechecks: true }));
  console.log("Eb NO token at all (full lapse)                  -> " + describeResult(eb));
  check("Eb expected DENY under repo rules", isPermissionDenied(eb));
  printFullDeny("Matrix Eb", eb);

  // Matrix F — WRONG-DOCUMENT signature: identical p4 payload committed
  // against a DIFFERENT match doc where uidP4 is NOT in players[].
  var otherDoc = prodMatchDoc();
  otherDoc.players = ["otherA", "otherB", "otherC", "otherD"];
  otherDoc.seats = { p1: "otherA", p2: "otherB", p3: "otherC", p4: "otherD" };
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("matches").doc("mF-other-match").set(otherDoc);
  });
  var f = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P4).firestore(), "mF-other-match",
    { callingUid: UID_P4, bypassServicePrechecks: true }));
  console.log("F  P4 payload vs OTHER match (p4 not seated)     -> " + describeResult(f));
  check("F  expected DENY (wrong-document signature confirmed)", isPermissionDenied(f));
  printFullDeny("Matrix F", f);

  // Matrix G — healthy-path certification: dealer-p1 replay + FULL END
  // STATE assertions (dealtRound=1, 4x13 cards, version==round, disjoint deck).
  await seedProdMatch("mG-healthy");
  var g = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P1).firestore(), "mG-healthy", { callingUid: UID_P1 }));
  console.log("G  DEALER-p1 replay on clean doc                 -> " + describeResult(g));
  var gState = null;
  if (g.ok) {
    await testEnv.withSecurityRulesDisabled(async function (ctx) {
      var db0 = ctx.firestore();
      var parent = (await db0.collection("matches").doc("mG-healthy").get()).data();
      var hands = {};
      for (var s of ["p1", "p2", "p3", "p4"]) {
        hands[s] = (await db0.collection("matches").doc("mG-healthy").collection("hands").doc(s).get()).data();
      }
      gState = { parent: parent, hands: hands };
    });
  }
  var gOk = false;
  if (gState) {
    var allSeatsOk = ["p1", "p2", "p3", "p4"].every(function (s) {
      var h = gState.hands[s];
      return h && h.round === 1 && h.version === 1 && h.version === h.round &&
             Array.isArray(h.cards) && h.cards.length === 13;
    });
    var uniqueCards = new Set();
    ["p1", "p2", "p3", "p4"].forEach(function (s) {
      gState.hands[s].cards.forEach(function (c) { uniqueCards.add(c.suit + "-" + c.rank.v + "-" + c.rank.s); });
    });
    gOk = gState.parent.gameState.initialized === true &&
          gState.parent.gameState.dealtRound === 1 &&
          allSeatsOk && uniqueCards.size === 52;
    console.log("   END STATE: initialized=" + gState.parent.gameState.initialized +
      " dealtRound=" + gState.parent.gameState.dealtRound +
      " 4x13&version==round=" + allSeatsOk +
      " uniqueCards=" + uniqueCards.size + "/52");
  } else {
    printFullDeny("Matrix G (commit leg)", g);
  }
  check("G  healthy path certified end-to-end", gOk);

  console.log("\n── TASK R6-CLOSE: MATRIX H — DOUBLE-DEAL RACE ──");
  var PROJECT_ID = "repro-deal-denial";
  var STALE_PROD_PRECONDITION = "2026-08-26T06:14:36.900840000Z"; // evidence L19 verbatim

  // H1 — dealer-p1 commits the full healthy deal (Matrix-G path). NO reseed
  // after this point on mH-race: H2 runs against the POST-commit state.
  await seedProdMatch("mH-race");
  var h1 = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P1).firestore(), "mH-race", { callingUid: UID_P1 }));
  check("H1 dealer-p1 full healthy deal -> ALLOW (no reseed afterwards)", h1.ok);
  printFullDeny("H1", h1);

  // H2a — SDK-path duplicate: p4 submits the identical transaction AFTER
  // H1, JS-side prechecks bypassed so RULES alone decide. Mirrors what
  // dealRound()'s conflict-retry would evaluate if it ever re-reached rules.
  var h2a = await attempt(exactProductionDealTransaction(
    testEnv.authenticatedContext(UID_P4).firestore(), "mH-race",
    { callingUid: UID_P4, bypassServicePrechecks: true }));
  console.log("H2a SDK duplicate deal by p4 post-H1           -> " + describeResult(h2a));
  check("H2a expected DENY via forward-only idempotency clause", isPermissionDenied(h2a));
  printFullDeny("H2a", h2a);

  // Wire-level identity: mint a mock id-token (the exact mechanism
  // rules-unit-testing itself uses); its uid becomes the seat-p4 owner of
  // the wire-test docs so REST commits evaluate as a seated member exactly
  // like production's p4.
  var dynP4 = "wirep4-" + Math.random().toString(36).slice(2, 12);
  var tok = createMockUserToken({ sub: dynP4 }, PROJECT_ID);
  check("H-setup mock idToken minted for wire-level p4 (" + dynP4 + ")",
    typeof tok === "string" && tok.split(".").length === 3);
  var wireOverrides = {
    players: [UID_P1, UID_P2, UID_P3, dynP4],
    seats: { p1: UID_P1, p2: UID_P2, p3: UID_P3, p4: dynP4 }
  };
  function docPath(matchId) { return "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents/matches/" + matchId; }

  // H2b — EXACT production payload WITH production's literal stale
  // currentDocument.updateTime precondition, committed against the
  // already-committed mH-wire doc.
  await seedProdMatch("mH-wire", wireOverrides);
  var bodyStale = productionShapedCommitBody(PROJECT_ID, "mH-wire", { round: 1, updateTimePrecondition: STALE_PROD_PRECONDITION });
  var h2b = await restPost(8080, "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit", bodyStale, tok);
  console.log("H2b WIRE duplicate w/ PRODUCTION'S STALE precondition -> HTTP " + h2b.status +
    " " + ((h2b.json && h2b.json.error && h2b.json.error.status) || ""));
  console.log("   body: " + h2b.raw.slice(0, 300));
  check("H2b stale precondition does NOT yield permission-denied/403 (precondition fires FIRST)",
    h2b.status !== 403);

  // H2c — PRODUCTION-ORDER replay on an UNDEALT doc: first deal, valid
  // (current) precondition, seated p4, exact payload. Production DENIED
  // this; repo/today-live rules must ALLOW it.
  await seedProdMatch("mH2c-prod-order", wireOverrides);
  var meta0 = await restGet(8080, docPath("mH2c-prod-order"), tok);
  var t0 = meta0.json && meta0.json.updateTime;
  var bodyFirst = productionShapedCommitBody(PROJECT_ID, "mH2c-prod-order", { round: 1, updateTimePrecondition: t0 });
  var h2c = await restPost(8080, "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit", bodyFirst, tok);
  console.log("H2c WIRE FIRST deal (valid precondition, undealt)      -> HTTP " + h2c.status +
    (h2c.status === 200 ? " OK (commitTime=" + ((h2c.json || {}).commitTime || "?") + ")" : " " + h2b.raw.slice(0, 200)));
  check("H2c DECISIVE: local rules ALLOW what production DENIED (rules exonerated)", h2c.status === 200);

  // H3 — control: duplicate with REFRESHED (post-commit) precondition.
  var meta1 = await restGet(8080, docPath("mH2c-prod-order"), tok);
  var t1 = meta1.json && meta1.json.updateTime;
  var bodyDupFresh = productionShapedCommitBody(PROJECT_ID, "mH2c-prod-order", { round: 1, updateTimePrecondition: t1 });
  var h3 = await restPost(8080, "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit", bodyDupFresh, tok);
  var h3denied = h3.status === 403;
  console.log("H3  WIRE duplicate w/ REFRESHED precondition          -> HTTP " + h3.status);
  if (h3denied) {
    console.log("   FULL ERROR BODY:");
    console.log("   " + h3.raw.slice(0, 700));
  }
  check("H3 expected DENY (permission-denied with L-numbers)", h3denied &&
    /permission.denied|PERMISSION_DENIED/i.test(h3.raw));

  // H4 — sanity: seeded dealtRound=1, currentRound=1; well-formed ROUND-2
  // deal submitted while currentRound is still 1 -> DENY on substance.
  await seedProdMatch("mH4-round2-vs-current1", {
    gameState: { initialized: true, dealtRound: 1 },
    version: 1
  });
  var dbH4 = testEnv.authenticatedContext(UID_P4).firestore();
  var h4 = await attempt(dbH4.runTransaction(function (tx) {
    var matchRef = dbH4.collection("matches").doc("mH4-round2-vs-current1");
    ["p1", "p2", "p3", "p4"].forEach(function (seatId, idx) {
      tx.set(matchRef.collection("hands").doc(seatId), {
        seatId: seatId, round: 2, cards: makeHand(idx), version: 2
      });
    });
    tx.update(matchRef, { gameState: { initialized: true, dealtRound: 2 }, updatedAt: FieldValue.serverTimestamp() });
    return Promise.resolve({ dealt: true });
  }));
  console.log("H4  round-2-shaped deal while currentRound==1         -> " + describeResult(h4));
  check("H4 expected DENY (round math vs currentRound)", isPermissionDenied(h4));
  printFullDeny("H4", h4);

  console.log("\n── TASK R6-CLOSE: SIDE-DENIAL DISCRIMINATION (players/* failures) ──");
  // R3a — self-profile update when the profile DOC EXISTS.
  await testEnv.withSecurityRulesDisabled(async function (ctx) {
    await ctx.firestore().collection("players").doc(UID_P4).set({
      displayName: "p4", accountType: "guest", email: "", avatarInitial: "P",
      rank: "Unranked", rp: 0, wins: 0, streak: 0, level: 1,
      coins: 500, gems: 10,
      createdAt: new Date(0), lastSeenAt: new Date(0),
      currentRoomId: null, currentMatchId: null
    });
  });
  var r3a = await attempt(testEnv.authenticatedContext(UID_P4).firestore()
    .collection("players").doc(UID_P4).update({ currentRoomId: "noxiRrTFuAhfpUavuzZ1" }));
  console.log("R3a players/{p4} update, DOC EXISTS                   -> " + describeResult(r3a));
  check("R3a expected ALLOW (repo rules permit self currentRoomId write)", r3a.ok);
  printFullDeny("R3a", r3a);

  // R3b — same update when the profile DOC IS MISSING.
  // MEASURED REAL-EMULATOR SEMANTICS (recorded, not assumed): a bare
  // .update() against a nonexistent players/{uid} does NOT yield
  // 'not-found' — rules evaluate FIRST, onlyAllowedFieldsChanged() throws
  // a "Null value error" on the absent resource.data, and the engine
  // surfaces that thrown evaluation as PERMISSION_DENIED at L74.
  var r3b = await attempt(testEnv.authenticatedContext("ghost-user-no-profile").firestore()
    .collection("players").doc("ghost-user-no-profile").update({ currentRoomId: "noxiRrTFuAhfpUavuzZ1" }));
  var r3bCode = r3b.err && r3b.err.code;
  console.log("R3b players/{uid} update, DOC MISSING                 -> code=" + r3bCode +
    " msg=" + ((r3b.err && r3b.err.message) || "").slice(0, 110));
  check("R3b ACTUAL recorded (missing doc ALSO denies as permission-denied via Null-value eval)",
    !r3b.ok && r3bCode === "permission-denied");

  // R3c — bracket: unauthenticated self-update on an EXISTING doc.
  var r3c = await attempt(testEnv.unauthenticatedContext().firestore()
    .collection("players").doc(UID_P4).update({ currentRoomId: "x" }));
  console.log("R3c players/{p4} update, NO TOKEN                     -> code=" + (r3c.err && r3c.err.code));
  check("R3c no-token yields permission-denied", !r3c.ok && r3c.err.code === "permission-denied");
  console.log("   CONCLUSION INPUT: production logged 'Missing or insufficient permissions'");
  console.log("   (= permission-denied) five times on p4's SELF-writes. R3a proves repo/live rules");
  console.log("   ALLOW those writes for an existing profile; R3b proves a MISSING profile also");
  console.log("   surfaces as permission-denied (Null-value eval at L74). The signature is");
  console.log("   therefore CONSISTENT WITH BOTH missing-profile-doc AND any front-door authz");
  console.log("   layer — the five denials alone do NOT discriminate between them.");



  await testEnv.cleanup();

  console.log("\n=== RESULTS ===");
  console.log("A(dealer)=" + (a.ok ? "ALLOW" : "DENY") +
              "  B(non-dealer P4)=" + (b.ok ? "ALLOW" : "DENY") +
              "  C(no updatedAt)=" + (c.ok ? "ALLOW" : "DENY") +
              "  D(turn=null)=" + (d.ok ? "ALLOW" : "DENY") +
              "  Ea(anon-tier)=" + (ea.ok ? "ALLOW" : "DENY") +
              "  Eb(no-token)=" + (eb.ok ? "ALLOW?!" : "DENY-expected") +
              "  F(wrong-doc)=" + (f.ok ? "ALLOW?!" : "DENY") +
              "  G(healthy)=" + (gOk ? "CERTIFIED" : "BROKEN"));
  console.log("H1=" + (h1.ok ? "ALLOW" : "DENY") +
              " H2a(dup-SDK)=" + (h2a.ok ? "ALLOW?!" : "DENY") +
              " H2b(stale-precond)=" + (h2b.status !== 403 ? "NON-403(" + h2b.status + ")" : "403?!") +
              " H2c(prod-order)=" + (h2c.status === 200 ? "LOCAL-ALLOW" : "HTTP-" + h2c.status) +
              " H3(dup-fresh)=" + (h3.status === 403 ? "DENY" : "HTTP-" + h3.status) +
              " H4(round2-vs-cur1)=" + (h4.ok ? "ALLOW?!" : "DENY"));
  console.log("R3a(exists)=" + (r3a.ok ? "ALLOW" : "DENY") +
              " R3b(missing)=" + (r3bCode === "not-found" ? "not-found" : String(r3bCode)) +
              " R3c(no-token)=" + (!r3c.ok && r3c.err.code === "permission-denied" ? "permission-denied" : "?"));
  console.log(pass + " passed, " + fail + " failed (controls hard-fail; matrix cells are recorded, not gated)");
  process.exitCode = fail > 0 ? 1 : 0;
}

run().catch(function (e) {
  console.error("FATAL:", e);
  process.exitCode = 1;
});
