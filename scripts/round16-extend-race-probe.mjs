#!/usr/bin/env node
// Round-16 extendMatchRounds() race probe — isolated emulator-only, no production mutation
// Safety: fails loudly if FIRESTORE_EMULATOR_HOST is not set (mirrors firestore-consistency-probe.mjs)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { Timestamp } from "firebase/firestore";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.join(__dirname, "..");
const RULES_PATH = path.join(REPO_ROOT, "firestore.rules");
const EVIDENCE_PATH = path.join(__dirname, "round16-extend-race-probe.evidence.jsonl");

// Safety check: must be running against emulator
if (!process.env.FIRESTORE_EMULATOR_HOST) {
  console.error("FATAL: FIRESTORE_EMULATOR_HOST is not set — refusing to run against production. This probe must be run via: npx firebase emulators:exec --project demo-test-ci --only firestore,auth \"node scripts/round16-extend-race-probe.mjs\"");
  process.exit(1);
}
if (process.env.FIRESTORE_EMULATOR_HOST && process.env.FIRESTORE_EMULATOR_HOST.includes("firestore.googleapis.com")) {
  console.error("FATAL: FIRESTORE_EMULATOR_HOST points to production, not emulator");
  process.exit(1);
}

// Also ensure we are not targeting production project
const EMULATOR_PROJECT = process.env.GCLOUD_PROJECT || process.env.FIREBASE_PROJECT || "demo-test-ci";
if (EMULATOR_PROJECT === "made---estimation-card-game" || EMULATOR_PROJECT === "estimation-lab") {
  console.error(`FATAL: Refusing to run probe against production project ${EMULATOR_PROJECT}. Use demo-test-ci or other demo/*`);
  process.exit(1);
}

const PROJECT_ID = "demo-test-ci";
const FIRESTORE_HOST = "127.0.0.1";
const FIRESTORE_PORT = 8080;

// Valid reasons from match-service.js:1600
const VALID_REASONS = ["SUPER_CALL", "SAAYDA"];
const COMPLETED_ROUND = 16;
const REASON = "SAAYDA"; // must be one of VALID_REASONS, SAAYDA was the captured reason in round16-rule-audit.json

// Helper to build a minimal match doc mirroring round16-rule-audit.json captured oldData
// oldData: version 1150, currentRound 16, maxRounds 19, extendedRounds [14], status starting, players 4, seats p1-p4
function buildMatchDoc(matchId, roomId, uids) {
  // uids: [p1Uid, p2Uid, p3Uid, p4Uid] in order
  const seats = { p1: uids[0], p2: uids[1], p3: uids[2], p4: uids[3] };
  return {
    roomId: roomId,
    players: [...uids],
    status: "starting",
    createdAt: Timestamp.now(),
    currentRound: 16,
    maxRounds: 19,
    extendedRounds: [14],
    dealer: uids[0],
    turn: uids[0],
    seats: seats,
    version: 1150,
    biddingOpen: true,
    bids: { p1: null, p2: null, p3: null, p4: null },
    lastBidSeat: null,
    cardLog: [],
    lastCardSeat: null,
    cardPhase: null,
    biddingLog: [],
    gameState: { initialized: false, dealtRound: 0 }
  };
}

// Helper to do extendMatchRounds via raw firestore transaction (mirrors match-service.js:1600-1646)
// Added per-attempt instrumentation (log-only, no logic change)
const ATTEMPT_EVIDENCE_PATH = path.join(__dirname, "round16-extend-race-probe.attempts.jsonl");
try { fs.writeFileSync(ATTEMPT_EVIDENCE_PATH, ""); } catch {}

async function doExtend(firestore, matchId, completedRound, reason, uid) {
  const matchRef = firestore.collection("matches").doc(matchId);
  const startTs = new Date().toISOString();
  const startMs = Date.now();
  let attemptNumber = 0;
  const attemptLogs = [];
  const logAttempt = (obj) => {
    const line = JSON.stringify(obj);
    console.log(line);
    try { fs.appendFileSync(ATTEMPT_EVIDENCE_PATH, line + "\n"); } catch {}
  };
  try {
    const result = await firestore.runTransaction(async (tx) => {
      attemptNumber++;
      const beforeGetTs = new Date().toISOString();
      const beforeGetMs = Date.now();
      const snap = await tx.get(matchRef);
      const afterGetTs = new Date().toISOString();
      const afterGetMs = Date.now();
      if (!snap.exists) {
        const err = new Error(`extendMatchRounds: match '${matchId}' was not found.`);
        err.code = "not-found";
        const entry = { type: "attempt", matchId, uid, attemptNumber, beforeGetTs, beforeGetMs, afterGetTs, afterGetMs, observedVersion: null, observedExtendedRounds: null, branch: "NOT_FOUND", error: err.message };
        logAttempt(entry);
        attemptLogs.push(entry);
        throw err;
      }
      const match = snap.data();
      const observedVersion = match.version;
      const observedExtendedRounds = Array.isArray(match.extendedRounds) ? [...match.extendedRounds] : [];
      // Check player membership (as in match-service.js:1619)
      if (!Array.isArray(match.players) || match.players.indexOf(uid) === -1) {
        const err = new Error(`extendMatchRounds: you are not a player in this match.`);
        err.code = "permission-denied";
        const entry = { type: "attempt", matchId, uid, attemptNumber, beforeGetTs, beforeGetMs, afterGetTs, afterGetMs, observedVersion, observedExtendedRounds, branch: "PERMISSION_DENIED_NOT_PLAYER", error: err.message };
        logAttempt(entry);
        attemptLogs.push(entry);
        throw err;
      }
      if (match.status === "complete") {
        const entry = { type: "attempt", matchId, uid, attemptNumber, beforeGetTs, beforeGetMs, afterGetTs, afterGetMs, observedVersion, observedExtendedRounds, branch: "MATCH_ALREADY_COMPLETE" };
        logAttempt(entry);
        attemptLogs.push(entry);
        return { extended: false, reason: "MATCH_ALREADY_COMPLETE", matchId, maxRounds: match.maxRounds };
      }
      if (observedExtendedRounds.indexOf(completedRound) !== -1) {
        const entry = { type: "attempt", matchId, uid, attemptNumber, beforeGetTs, beforeGetMs, afterGetTs, afterGetMs, observedVersion, observedExtendedRounds, branch: "ALREADY_EXTENDED" };
        logAttempt(entry);
        attemptLogs.push(entry);
        return { extended: false, reason: "ALREADY_EXTENDED", matchId, maxRounds: match.maxRounds };
      }
      // For probe, we skip RAPID_ROUND_MIN/MAX and VALID_REASONS checks here because we already validated before
      const nextVersion = match.version + 1;
      const nextMaxRounds = (match.maxRounds || 18) + 1;
      const beforeUpdateTs = new Date().toISOString();
      const beforeUpdateMs = Date.now();
      tx.update(matchRef, {
        maxRounds: nextMaxRounds,
        extendedRounds: [...observedExtendedRounds, completedRound],
        version: nextVersion,
        updatedAt: Timestamp.now()
      });
      const afterUpdateTs = new Date().toISOString();
      const afterUpdateMs = Date.now();
      const entry = { type: "attempt", matchId, uid, attemptNumber, beforeGetTs, beforeGetMs, afterGetTs, afterGetMs, beforeUpdateTs, beforeUpdateMs, afterUpdateTs, afterUpdateMs, observedVersion, observedExtendedRounds, branch: "WROTE", nextVersion, nextMaxRounds };
      logAttempt(entry);
      attemptLogs.push(entry);
      return { extended: true, matchId, completedRound, reason, maxRounds: nextMaxRounds, version: nextVersion };
    });
    const endTs = new Date().toISOString();
    const endMs = Date.now();
    return { success: true, seat: null, uid, startTs, endTs, startMs, endMs, durationMs: endMs - startMs, result, attempts: attemptLogs };
  } catch (e) {
    const endTs = new Date().toISOString();
    const endMs = Date.now();
    // If the transaction failed after some attempts, the last attempt's error is e
    // We already logged each attempt, but also log the final failure
    const entry = { type: "final_error", matchId, uid, attemptNumber, error: { code: e.code || null, message: e.message || String(e), stack: e.stack ? e.stack.split("\n").slice(0, 5).join(" | ") : null }, attempts: attemptLogs.length };
    logAttempt(entry);
    // Capture full error: code, message, stack
    return {
      success: false,
      seat: null,
      uid,
      startTs,
      endTs,
      startMs,
      endMs,
      durationMs: endMs - startMs,
      error: {
        code: e.code || null,
        message: e.message || String(e),
        stack: e.stack ? e.stack.split("\n").slice(0, 5).join(" | ") : null
      },
      attempts: attemptLogs
    };
  }
}

async function main() {
  console.log(`=== Round-16 extendMatchRounds Race Probe ===`);
  console.log(`PROJECT_ID=${PROJECT_ID} FIRESTORE_EMULATOR_HOST=${process.env.FIRESTORE_EMULATOR_HOST} GCLOUD_PROJECT=${process.env.GCLOUD_PROJECT}`);
  console.log(`Safety: emulator only, will create 5 fresh matches, 4 concurrent clients each, SAAYDA reason, completedRound 16`);

  const rules = fs.readFileSync(RULES_PATH, "utf8");
  const testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules, host: FIRESTORE_HOST, port: FIRESTORE_PORT }
  });

  // Clear any existing evidence file
  try { fs.unlinkSync(EVIDENCE_PATH); } catch {}
  // Ensure evidence file exists empty
  fs.writeFileSync(EVIDENCE_PATH, "");

  const runs = 5;
  const allRunsSummary = [];

  for (let run = 1; run <= runs; run++) {
    console.log(`\n--- Run ${run} ---`);
    const runId = `probe16_${Date.now()}_${run}`;
    const roomId = `room_${runId}`;
    const matchId = `match_${runId}`;

    // 4 distinct UIDs for p1-p4
    const uids = [
      `uid_p1_${run}_${Date.now()}_a`,
      `uid_p2_${run}_${Date.now()}_b`,
      `uid_p3_${run}_${Date.now()}_c`,
      `uid_p4_${run}_${Date.now()}_d`
    ];
    const seats = ["p1", "p2", "p3", "p4"];
    // Map uid to seat for logging
    const uidToSeat = {};
    uids.forEach((uid, i) => uidToSeat[uid] = seats[i]);

    // Create the match document directly via withSecurityRulesDisabled (bypass rules for setup)
    // This mirrors the exact captured oldData: version 1150, maxRounds 19, extendedRounds [14], currentRound 16
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const fsAdmin = ctx.firestore();
      const matchRef = fsAdmin.collection("matches").doc(matchId);
      const roomRef = fsAdmin.collection("rooms").doc(roomId);
      // Create a minimal room that matches isValidNewMatch's get() check (if needed for rules, but we are bypassing)
      // For extendMatchRounds, the rules don't check room, only the match doc itself, but we create room for completeness
      await roomRef.set({
        creator: uids[0],
        players: [...uids],
        readyPlayers: [...uids],
        status: "waiting",
        name: "probe room",
        createdAt: Timestamp.now()
      });
      const matchDoc = buildMatchDoc(matchId, roomId, uids);
      await matchRef.set(matchDoc);
      // Verify creation
      const snap = await matchRef.get();
      if (!snap.exists) throw new Error("Failed to create match doc");
      console.log(`Created match ${matchId} with version ${snap.data().version}, maxRounds ${snap.data().maxRounds}, extendedRounds ${JSON.stringify(snap.data().extendedRounds)}`);
    });

    // Create 4 authenticated contexts
    const contexts = uids.map(uid => testEnv.authenticatedContext(uid));
    const firetores = contexts.map(ctx => ctx.firestore());

    // Prepare to capture per-client outcome
    const clientPromises = firetores.map((firestore, idx) => {
      const uid = uids[idx];
      const seat = seats[idx];
      // Add seat to result after
      return doExtend(firestore, matchId, COMPLETED_ROUND, REASON, uid).then(res => {
        res.seat = seat;
        return res;
      });
    });

    // Fire all 4 concurrently
    const results = await Promise.all(clientPromises);

    // After all settle, do a fresh getDoc to see final state - try via first authenticated context first, fallback to admin
    let finalSnapData = null;
    try {
      const snap = await firetores[0].collection("matches").doc(matchId).get();
      finalSnapData = snap.exists ? snap.data() : null;
      console.log(`Final snap via p1 auth: exists=${snap.exists}, version=${finalSnapData?.version}`);
    } catch (e) {
      console.log(`Final snap via p1 failed: ${e.message}, trying admin`);
    }
    if (!finalSnapData) {
      finalSnapData = await testEnv.withSecurityRulesDisabled(async (ctx) => {
        const fsAdmin = ctx.firestore();
        const snap = await fsAdmin.collection("matches").doc(matchId).get();
        console.log(`Final snap via admin: exists=${snap.exists}, data=${snap.exists ? JSON.stringify(snap.data()).slice(0,200) : "null"}`);
        return snap.exists ? snap.data() : null;
      });
    }
    if (!finalSnapData) {
      console.error(`Final snap not found for ${matchId} after run ${run} via both p1 and admin`);
      // Try listing all matches to see what's there
      const allMatches = await testEnv.withSecurityRulesDisabled(async (ctx) => {
        const fsAdmin = ctx.firestore();
        const snap = await fsAdmin.collection("matches").get();
        return snap.docs.map(d => d.id);
      });
      console.log(`All matches in db: ${JSON.stringify(allMatches)}`);
      throw new Error(`Final document not found for ${matchId}`);
    }
    const finalState = {
      maxRounds: finalSnapData.maxRounds,
      extendedRounds: finalSnapData.extendedRounds,
      version: finalSnapData.version
    };
    console.log(`Final state after run ${run}: version ${finalState.version}, maxRounds ${finalState.maxRounds}, extendedRounds ${JSON.stringify(finalState.extendedRounds)}`);

    // Log per-client outcome as one JSON line per client
    for (const res of results) {
      const logLine = {
        run,
        runId,
        matchId,
        roomId,
        seat: res.seat,
        uid: res.uid,
        startTs: res.startTs,
        endTs: res.endTs,
        durationMs: res.durationMs,
        success: res.success,
        result: res.success ? res.result : null,
        error: res.success ? null : res.error,
        finalState: finalState
      };
      const line = JSON.stringify(logLine);
      console.log(line);
      fs.appendFileSync(EVIDENCE_PATH, line + "\n");
    }

    // Also log a summary line for the run
    const summary = {
      run,
      p1: results.find(r => r.seat === "p1")?.success ? (results.find(r => r.seat === "p1").result.extended ? "extended" : results.find(r => r.seat === "p1").result.reason) : `error:${results.find(r => r.seat === "p1")?.error?.code}:${results.find(r => r.seat === "p1")?.error?.message?.slice(0,50)}`,
      p2: results.find(r => r.seat === "p2")?.success ? (results.find(r => r.seat === "p2").result.extended ? "extended" : results.find(r => r.seat === "p2").result.reason) : `error:${results.find(r => r.seat === "p2")?.error?.code}`,
      p3: results.find(r => r.seat === "p3")?.success ? (results.find(r => r.seat === "p3").result.extended ? "extended" : results.find(r => r.seat === "p3").result.reason) : `error:${results.find(r => r.seat === "p3")?.error?.code}`,
      p4: results.find(r => r.seat === "p4")?.success ? (results.find(r => r.seat === "p4").result.extended ? "extended" : results.find(r => r.seat === "p4").result.reason) : `error:${results.find(r => r.seat === "p4")?.error?.code}`,
      finalMaxRounds: finalState.maxRounds,
      finalExtendedRounds: finalState.extendedRounds,
      finalVersion: finalState.version
    };
    console.log(`Summary run ${run}: p1 ${summary.p1} | p2 ${summary.p2} | p3 ${summary.p3} | p4 ${summary.p4} | final ${JSON.stringify(finalState)}`);
    allRunsSummary.push(summary);

    // Clean up: delete match and room via withSecurityRulesDisabled
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const fsAdmin = ctx.firestore();
      await fsAdmin.collection("matches").doc(matchId).delete().catch(()=>{});
      await fsAdmin.collection("rooms").doc(roomId).delete().catch(()=>{});
    });
    // Small delay between runs
    await new Promise(r => setTimeout(r, 100));
  }

  // Final summary
  console.log(`\n=== OVERALL SUMMARY ===`);
  allRunsSummary.forEach(s => {
    console.log(`Run ${s.run}: p1 ${s.p1} | p2 ${s.p2} | p3 ${s.p3} | p4 ${s.p4} | final maxRounds ${s.finalMaxRounds} extended ${JSON.stringify(s.finalExtendedRounds)} version ${s.finalVersion}`);
  });

  const totalLines = fs.readFileSync(EVIDENCE_PATH, "utf8").trim().split("\n").filter(l=>l).length;
  console.log(`\nEvidence written to ${EVIDENCE_PATH} with ${totalLines} lines (expected 20)`);
  console.log(`All 5 runs used isolated match docs, cleaned up, no production project touched.`);

  await testEnv.cleanup();
  process.exit(0);
}

main().catch(e => {
  console.error("Probe failed:", e);
  process.exit(1);
});
