// TASK R7 / R3 â€” REAL-BACKEND H-MATRIX (SCRATCH PROJECT ONLY â€” HARD-GARDED).
//
// PURPOSE: measure, against REAL Firestore infrastructure (not the local
// emulator), the error-ordering of a Commit that carries BOTH defects at
// once: (a) security-rules violations on its hand-doc writes, and
// (b) a stale currentDocument.updateTime precondition. The emulator checks
// the precondition FIRST (measured: HTTP 400 FAILED_PRECONDITION,
// tests/repro-deal-denial.rules-emulator.test.cjs Matrix H2b); production
// returned 403 permission-denied for exactly this shape (evidence.jsonl
// L19). This script decides which orderer the real backend uses.
//
// AUTH MODES:
//   - Preferred: FIREBASE_WEB_API_KEY env var -> anonymous sign-in directly.
//   - Or: SERVICE_ACCOUNT_KEY_PATH (default scratch-service-account.json at
//     repo root) -> mints an OAuth token, ensures a web app exists on the
//     scratch project, fetches its Web API Key, then signs in anonymously
//     AS A REAL END-USER (service-account tokens are ADMIN and would BYPASS
//     security rules entirely, which would invalidate the measurement), and
//     auto-deploys the repo firestore.rules to the scratch project so the
//     duplicate-deal cells genuinely violate isValidHandRedeal().
//
// SAFETY GUARDS (all must pass or the script exits without any network call):
//   1. FIREBASE_PROJECT_ID must NOT be the game project.
//   2. CONFIRM_SCRATCH env var must equal the literal string SCRATCH-ONLY.
//   3. Only anonymous-auth + Firestore/Rules/Firebase-Management REST calls
//      are made; only matches/mH-* documents under the given scratch project
//      are touched.
//
// USAGE:
//   set FIREBASE_PROJECT_ID=estemshan-lab
//   set CONFIRM_SCRATCH=SCRATCH-ONLY
//   set SERVICE_ACCOUNT_KEY_PATH=scratch-service-account.json   (optional)
//   node tests/h-matrix.real-backend.cjs

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const GAME_PROJECT = "made---estimation-card-game";
const PROJECT_ID = process.env.FIREBASE_PROJECT_ID || "";
const API_KEY = process.env.FIREBASE_WEB_API_KEY || "";
// Tolerate the double-extension variant some consoles produce on save.
const SA_CANDIDATES = [
  process.env.SERVICE_ACCOUNT_KEY_PATH,
  "scratch-service-account.json",
  "scratch-service-account.json.json"
].filter(Boolean);
const SA_PATH = path.join(__dirname, "..", SA_CANDIDATES.find(function (rel) {
  return fs.existsSync(path.join(__dirname, "..", rel));
}) || SA_CANDIDATES[0]);
const CONFIRM = process.env.CONFIRM_SCRATCH || "";
// Fresh doc id per run so repeated runs never collide with prior state.
// Module scope: dealCommitBody() below interpolates it into wire names.
const MATCH_ID = "mH-real-" + crypto.randomBytes(4).toString("hex");

function bail(msg) { console.error("REFUSED: " + msg); process.exit(2); }
// Runner-friendliness: with NO scratch config at all this file is a no-op
// that exits 0 (scripts/run-tests.mjs auto-discovers every tests/*.cjs).
// Deliberate misconfiguration (env PRESENT but unsafe) still hard-refuses.
if (!PROJECT_ID || !CONFIRM || !API_KEY && !fs.existsSync(SA_PATH)) {
  console.log("h-matrix.real-backend: scratch-project env not configured - nothing to do (exit 0).");
  console.log("To run: FIREBASE_PROJECT_ID=<scratch-id>, CONFIRM_SCRATCH=SCRATCH-ONLY, plus");
  console.log("FIREBASE_WEB_API_KEY or SERVICE_ACCOUNT_KEY_PATH. See docs/postmortem/h-matrix-real-backend-runbook.md");
  process.exit(0);
}
if (PROJECT_ID === GAME_PROJECT) bail("refusing to run against the production game project.");
if (/made---|card-game/i.test(PROJECT_ID)) bail("project id looks like a game project; use a throwaway scratch project.");
if (CONFIRM !== "SCRATCH-ONLY") bail("set CONFIRM_SCRATCH=SCRATCH-ONLY to acknowledge this runs against a THROWAWAY project.");

function httpsJson(method, host, path, bodyObj, token) {
  return new Promise(function (resolve, reject) {
    const payload = bodyObj ? Buffer.from(JSON.stringify(bodyObj), "utf8") : null;
    const req = require("https").request({
      host: host, path: path, method: method,
      headers: Object.assign(
        { "Content-Type": "application/json" },
        payload ? { "Content-Length": payload.length } : {},
        token ? { Authorization: "Bearer " + token } : {}
      )
    }, function (res) {
      let buf = "";
      res.on("data", function (c) { buf += c; });
      res.on("end", function () {
        let json = null; try { json = JSON.parse(buf); } catch { json = null; }
        resolve({ status: res.statusCode, raw: buf.slice(0, 500), json: json });
      });
    });
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

const b64u = function (obj) {
  return Buffer.from(JSON.stringify(obj), "utf8").toString("base64")
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

// â”€â”€ Service-account bootstrap (only when no Web API Key was provided) â”€â”€
// SA OAuth tokens are ADMIN (bypass security rules), so they are used ONLY
// for: seeding the match doc, deploying the repo ruleset, and reading the
// project's Web API Key. Every measured cell runs on a REAL END-USER
// idToken obtained via anonymous sign-in.
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

async function mintOAuthToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  const jwtHead = b64u({ alg: "RS256", typ: "JWT" });
  const jwtClaims = b64u({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/firebase https://www.googleapis.com/auth/identitytoolkit https://www.googleapis.com/auth/firebase.database",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600
  });
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(jwtHead + "." + jwtClaims);
  const sig = signer.sign(sa.private_key, "base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const assertion = jwtHead + "." + jwtClaims + "." + sig;
  const form = "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") +
    "&assertion=" + encodeURIComponent(assertion);
  const res = await new Promise(function (resolve, reject) {
    const req = require("https").request({
      host: "oauth2.googleapis.com", path: "/token", method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" }
    }, function (res2) {
      let buf = ""; res2.on("data", function (c) { buf += c; });
      res2.on("end", function () { resolve({ status: res2.statusCode, raw: buf, json: JSON.parse(buf || "{}") }); });
    });
    req.on("error", reject); req.write(form); req.end();
  });
  if (res.status !== 200) bail("SA token exchange failed (" + res.status + "): " + res.raw);
  return res.json.access_token;
}

// Anonymous sign-in on the scratch project (Anonymous provider must be
// enabled â€” the owner has confirmed it is).
async function anonSignIn(apiKey) {
  const r = await httpsJson("POST", "identitytoolkit.googleapis.com",
    "/v1/accounts:signUp?key=" + apiKey, { returnSecureToken: true }, null);
  if (r.status !== 200) bail("anonymous sign-in failed (" + r.status + "). Did you enable the Anonymous provider? " + r.raw);
  return { uid: r.json.localId, idToken: r.json.idToken };
}

async function ensureWebApiKey(oauth) {
  const list = await httpsJson("GET", "firebase.googleapis.com",
    "/v1beta1/projects/" + PROJECT_ID + "/webApps", null, oauth);
  let appId = null;
  if (list.status === 200 && list.json.apps && list.json.apps.length) {
    appId = list.json.apps[0].name.split("/").pop();
  } else {
    console.log("no web app on scratch project - creating one...");
    const created = await httpsJson("POST", "firebase.googleapis.com",
      "/v1beta1/projects/" + PROJECT_ID + "/webApps", { displayName: "ordering-probe" }, oauth);
    if (created.status !== 200 && created.status !== 201) {
      // Fallback: skip app creation and use ANY existing API key via the
      // API Keys API (Firebase projects ship with one by default).
      console.log("webApp create denied (" + created.status + ") - trying API Keys API fallback...");
      const keys = await httpsJson("GET", "apikeys.googleapis.com",
        "/v2/projects/" + PROJECT_ID + "/locations/global/keys", null, oauth);
      if (keys.status === 200 && keys.json.keys && keys.json.keys.length) {
        const k = keys.json.keys.find(function (x) { return x.keyString; });
        if (k) { console.log("using existing API key via apikeys API"); return k.keyString; }
      }
      bail("no web app and no usable API key: " + created.raw + " | keys: " + keys.raw.slice(0, 200));
    }
    let op = created.json;
    const opName = op.name;
    for (let i = 0; i < 30 && op.done !== true; i++) {
      await sleep(2000);
      op = (await httpsJson("GET", "firebase.googleapis.com", "/v1/" + opName, null, oauth)).json || {};
    }
    if (op.done !== true) bail("webApp creation timed out");
    appId = (op.response || {}).appId;
  }
  if (!appId) bail("could not resolve web app id");
  const cfg = await httpsJson("GET", "firebase.googleapis.com",
    "/v1beta1/projects/" + PROJECT_ID + "/webApps/" + appId + "/config", null, oauth);
  if (cfg.status !== 200 || !cfg.json.apiKey) bail("webApp config fetch failed: " + cfg.status + " " + cfg.raw);
  return cfg.json.apiKey;
}

async function deployRepoRules(oauth) {
  const rulesContent = fs.readFileSync(path.join(__dirname, "..", "firestore.rules"), "utf8");
  const rs = await httpsJson("POST", "firebaserules.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/rulesets",
    { source: { files: [{ name: "firestore.rules", content: rulesContent }] } }, oauth);
  if (rs.status !== 200 && rs.status !== 201) bail("ruleset create failed: " + rs.status + " " + rs.raw);
  const rulesetName = rs.json.name;
  console.log("ruleset deployed: " + rulesetName);
  const relBody = {
    name: "projects/" + PROJECT_ID + "/releases/cloud.firestore",
    ruleset_name: rulesetName
  };
  let rel = await httpsJson("PATCH", "firebaserules.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/releases/cloud.firestore", relBody, oauth);
  if (rel.status !== 200) {
    rel = await httpsJson("POST", "firebaserules.googleapis.com",
      "/v1/projects/" + PROJECT_ID + "/releases", relBody, oauth);
  }
  if (rel.status !== 200 && rel.status !== 201) bail("release update failed: " + rel.status + " " + rel.raw);
  console.log("release cloud.firestore -> live. waiting 20s for propagation...");
  await sleep(20000);
}

// â”€â”€ Full V8 match-doc shape (buildInitialMatchDoc equivalent), uidX owns p4 â”€â”€
function v8Doc(uidP1, uidP4) {
  const players = [uidP1, "wireB", "wireC", uidP4];
  return {
    fields: {
      roomId: { stringValue: "room-h" },
      players: { arrayValue: { values: players.map(function (u) { return { stringValue: u }; }) } },
      status: { stringValue: "starting" },
      createdAt: { integerValue: "1" },
      currentRound: { integerValue: "1" },
      maxRounds: { integerValue: "18" },
      extendedRounds: { arrayValue: {} },
      dealer: { stringValue: uidP1 },
      turn: { stringValue: uidP1 },
      seats: { mapValue: { fields: {
        p1: { stringValue: uidP1 }, p2: { stringValue: "wireB" },
        p3: { stringValue: "wireC" }, p4: { stringValue: uidP4 } } } },
      version: { integerValue: "1" },
      biddingOpen: { booleanValue: true },
      bids: { mapValue: { fields: {
        p1: { nullValue: null }, p2: { nullValue: null },
        p3: { nullValue: null }, p4: { nullValue: null } } } },
      lastBidSeat: { nullValue: null },
      cardLog: { arrayValue: {} },
      lastCardSeat: { nullValue: null },
      cardPhase: { nullValue: null },
      biddingLog: { arrayValue: {} },
      gameState: { mapValue: { fields: {
        initialized: { booleanValue: false }, dealtRound: { integerValue: "0" } } } }
    }
  };
}

function dealCommitBody(updateTimePrecondition) {
  const base = "projects/" + PROJECT_ID + "/databases/(default)/documents/matches/" + MATCH_ID;
  const suits = ["SPADES", "HEARTS", "DIAMONDS", "CLUBS"];
  const writes = ["p1", "p2", "p3", "p4"].map(function (seatId, idx) {
    const cards = [];
    for (let k = 0; k < 13; k++) {
      const i = idx * 13 + k;
      const rankFields = {
        v: { integerValue: String(2 + (i % 13)) },
        s: { stringValue: String(2 + (i % 13)) }
      };
      const card = {
        mapValue: { fields: {
          suit: { stringValue: suits[Math.floor(i / 13)] },
          rank: { mapValue: { fields: rankFields } }
        } }
      };
      cards.push(card);
    }
    return { update: { name: base + "/hands/" + seatId, fields: {
      seatId: { stringValue: seatId },
      round: { integerValue: "1" },
      cards: { arrayValue: { values: cards } },
      version: { integerValue: "1" } } } };
  });  const matchUpdate = {
    update: { name: base, fields: { gameState: { mapValue: { fields: {
      initialized: { booleanValue: true }, dealtRound: { integerValue: "1" } } } } } },
    updateMask: { fieldPaths: ["gameState"] },
    updateTransforms: [{ fieldPath: "updatedAt", setToServerValue: "REQUEST_TIME" }]
  };
  if (updateTimePrecondition) matchUpdate.currentDocument = { updateTime: updateTimePrecondition };
  writes.push(matchUpdate);
  return { writes: writes };
}

(async function main() {
  console.log("Scratch project: " + PROJECT_ID);
  let apiKey = API_KEY, oauth = null;
  if (!apiKey) {
    const sa = JSON.parse(fs.readFileSync(SA_PATH, "utf8"));
    console.log("SA key loaded (" + sa.client_email + ") - minting OAuth token...");
    oauth = await mintOAuthToken(sa);
    apiKey = await ensureWebApiKey(oauth);
    console.log("web api key resolved via Firebase Management API");
    if (process.env.SKIP_RULES_DEPLOY === "1") {
      console.log("SKIP_RULES_DEPLOY=1 -> leaving project DEFAULT rules in place (open test-mode baseline).");
    } else {
      await deployRepoRules(oauth);
    }
  }
  const { uid: uidX, idToken } = await anonSignIn(apiKey);
  console.log("anonymous end-user identity (rules apply to it): " + uidX);

  // Seed mH-real with a SECOND identity as dealer so the first deal is by a
  // different seated member than the duplicating p4-owner (mirrors p1 vs p4).
  // Seeding uses the ADMIN token when available (rules bypassed â€” seeding is
  // setup, not a measured cell).
  const seedUidP1 = "seedP1-" + crypto.randomBytes(4).toString("hex");
  const seedBase = "projects/" + PROJECT_ID + "/databases/(default)/documents/matches/" + MATCH_ID;
  const seedBody = { writes: [{ update: { name: seedBase, fields: v8Doc(seedUidP1, uidX).fields } }] };
  const seed = await httpsJson("POST",
    "firestore.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit",
    seedBody, oauth || idToken);
  if (seed.status !== 200) bail("seed failed: " + seed.status + " " + seed.raw);

  const meta0 = await httpsJson("GET", "firestore.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents/matches/" + MATCH_ID, null, idToken);
  const t0 = meta0.json.updateTime;
  let t0v = t0; // pre-commit "stale" version used by Cell C (updated if A retried)
  console.log("\nseeded; creation updateTime t0 = " + t0);

  // CELL A â€” production-order FIRST deal, valid precondition (sanity ALLOW).
  // One retry after 30s in case the rules release was still propagating.
  async function cellA() {
    return httpsJson("POST", "firestore.googleapis.com",
      "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit",
      dealCommitBody(t0), idToken);
  }
  let cellARes = await cellA();
  if (cellARes.status === 403) {
    console.log("[A] got 403 â€” possible rules propagation lag; re-seeding and retrying once after 30s...");
    await httpsJson("DELETE", "firestore.googleapis.com",
      "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents/matches/" + MATCH_ID, null, oauth || idToken);
    await sleep(30000);
    const reseed = await httpsJson("POST",
      "firestore.googleapis.com",
      "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit",
      { writes: [{ update: { name: seedBase, fields: v8Doc(seedUidP1, uidX).fields } }] }, oauth || idToken);
    if (reseed.status !== 200) bail("re-seed failed: " + reseed.status + " " + reseed.raw);
    const m0b = await httpsJson("GET", "firestore.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents/matches/" + MATCH_ID, null, idToken);
    t0v = m0b.json.updateTime;
    cellARes = await httpsJson("POST", "firestore.googleapis.com",
      "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit",
      dealCommitBody(t0v), idToken);
  }
  console.log("\n[A] FIRST deal, valid precondition     -> HTTP " + cellARes.status +
    (cellARes.status === 200 ? " OK" : " " + cellARes.raw));
  if (cellARes.status !== 200) bail("Cell A did not succeed â€” matrix invalid; see body above.");

  const meta1 = await httpsJson("GET", "firestore.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents/matches/" + MATCH_ID, null, idToken);
  const t1 = meta1.json.updateTime;
  console.log("post-commit updateTime t1 = " + t1);

  // CELL C â€” DECISIVE: duplicate carrying the STALE pre-commit precondition
  // (t0). Real backend ordering question: rules-permission-denied (403) or
  // stale-precondition failure (400 FAILED_PRECONDITION / 409)?
  const bodyStale = dealCommitBody(t0v);
  const cellC = await httpsJson("POST", "firestore.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit",
    bodyStale, idToken);
  console.log("\n[C] DECISIVE duplicate, STALE t0 precondition -> HTTP " + cellC.status +
    "\n    " + cellC.raw);

  // CELL B â€” control: duplicate with REFRESHED t1 precondition.
  const cellB = await httpsJson("POST", "firestore.googleapis.com",
    "/v1/projects/" + PROJECT_ID + "/databases/(default)/documents:commit",
    dealCommitBody(t1), idToken);
  console.log("\n[B] duplicate, refreshed t1 precondition      -> HTTP " + cellB.status +
    "\n    " + cellB.raw);

  console.log("\n=== VERDICT INPUT ===");
  if (cellC.status === 403) {
    console.log("REAL BACKEND evaluates SECURITY RULES before/instead of the stale-version");
    console.log("precondition for this write-set -> production's 403 IS reproducible as the");
    console.log("forward-only idempotency denial of a lost double-deal race (rules-first order).");
  } else if (cellC.status === 400 || cellC.status === 409) {
    console.log("REAL BACKEND checks the stale precondition FIRST (parity with emulator)");
    console.log("-> production's observed 403 can NOT be this rejection; race-denial model");
    console.log("   stays refuted and an unidentified front-door layer remains implicated.");
  } else {
    console.log("UNEXPECTED status â€” paste the output above into the postmortem thread.");
  }
})().catch(function (e) { console.error("FATAL:", e && e.message || e); process.exit(1); });
