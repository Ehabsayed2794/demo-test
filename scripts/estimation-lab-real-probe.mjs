#!/usr/bin/env node
// Real Firestore consistency probe for estimation-lab — isolated, no emulator, no production mutation
import path from "node:path";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { initializeApp, deleteApp } from "firebase/app";
import { getFirestore, doc, setDoc, getDoc, updateDoc, deleteDoc, onSnapshot, runTransaction, serverTimestamp, getDocFromServer } from "firebase/firestore";
import { getAuth, createUserWithEmailAndPassword, signInWithEmailAndPassword, deleteUser } from "firebase/auth";

const REAL_CONFIG = {
  apiKey: "AIzaSyCZdHyv0tC2nOJmPCJP5T4dR9ViD0d_KZs",
  authDomain: "estimation-lab.firebaseapp.com",
  projectId: "estimation-lab",
  storageBucket: "estimation-lab.firebasestorage.app",
  messagingSenderId: "597143777359",
  appId: "1:597143777359:web:9a4a75b55c2c17f9f23a3a"
};
const EVIDENCE_PATH = path.join(path.dirname(fileURLToPath(import.meta.url)), "estimation-lab-real-probe.evidence.jsonl");
// Ensure evidence file exists and is empty at start
try { fs.writeFileSync(EVIDENCE_PATH, ""); } catch {}

// Safety checks
if (process.env.FIRESTORE_EMULATOR_HOST) {
  console.error("FATAL: FIRESTORE_EMULATOR_HOST is set (" + process.env.FIRESTORE_EMULATOR_HOST + ") — refusing to run real probe with emulator host active");
  process.exit(1);
}
if (process.env.FIREBASE_AUTH_EMULATOR_HOST) {
  console.error("FATAL: FIREBASE_AUTH_EMULATOR_HOST is set — refusing to run real probe");
  process.exit(1);
}
if (REAL_CONFIG.projectId === "made---estimation-card-game") {
  console.error("FATAL: would touch production project");
  process.exit(1);
}
console.log(`FIREBASE_PROJECT = ${REAL_CONFIG.projectId}`);
console.log(`FIRESTORE_HOST = real / not emulator`);
console.log(`AUTH_EMULATOR_HOST = ${process.env.FIREBASE_AUTH_EMULATOR_HOST || "unset"}`);
console.log(`FIRESTORE_EMULATOR_HOST = ${process.env.FIRESTORE_EMULATOR_HOST || "unset"}`);
console.log("Isolated real Firestore check: PASS (not production, no emulator host)");

async function runOnce(runNum) {
  const runId = `realprobe_${Date.now()}_${runNum}`;
  const roomId = `probe_room_${runId}`;
  const emailA = `probeA_${runId}@test.local`;
  const emailB = `probeB_${runId}@test.local`;
  const password = "TestProbe123!";
  let appA, appB, dbA, dbB, authA, authB, userA, userB;
  let roomRefA, roomRefB;
  let listenerUnsub = null;
  let listenerVersion = null;
  let listenerFirstSeenAt = null;

  const evidence = [];

  try {
    // Create two isolated apps for Client A and Client B (to simulate two independent clients)
    appA = initializeApp(REAL_CONFIG, `probeA-${runId}`);
    appB = initializeApp(REAL_CONFIG, `probeB-${runId}`);
    dbA = getFirestore(appA);
    dbB = getFirestore(appB);
    authA = getAuth(appA);
    authB = getAuth(appB);

    // Create two test users
    const credA = await createUserWithEmailAndPassword(authA, emailA, password);
    userA = credA.user;
    console.log(`Run ${runNum}: Created userA ${userA.uid} ${emailA}`);
    const credB = await createUserWithEmailAndPassword(authB, emailB, password);
    userB = credB.user;
    console.log(`Run ${runNum}: Created userB ${userB.uid} ${emailB}`);

    // Create room as userA (creator)
    roomRefA = doc(dbA, "rooms", roomId);
    roomRefB = doc(dbB, "rooms", roomId);
    const roomData = {
      creator: userA.uid,
      players: [userA.uid],
      readyPlayers: [],
      status: "waiting",
      name: "probe room",
      createdAt: serverTimestamp(),
      updatedAt: serverTimestamp()
    };
    await setDoc(roomRefA, roomData);
    console.log(`Run ${runNum}: Created room ${roomId} with players [${userA.uid}]`);

    // Setup listener as client A before the race
    listenerVersion = null;
    listenerFirstSeenAt = null;
    await new Promise((resolve) => {
      listenerUnsub = onSnapshot(roomRefA, (snap) => {
        if (snap.exists()) {
          const data = snap.data();
          const ver = data.players ? data.players.length : null; // use players length as version proxy
          if (ver !== listenerVersion) {
            listenerVersion = ver;
            if (!listenerFirstSeenAt && ver === 2) {
              listenerFirstSeenAt = Date.now();
            }
          }
        }
        resolve();
      }, (err) => {
        console.log(`Listener error: ${err.message}`);
        resolve();
      });
      setTimeout(resolve, 1000);
    });
    await new Promise(r => setTimeout(r, 200));

    // Outer read N as Client A
    const outerSnap = await getDoc(roomRefA);
    const outerVersion = outerSnap.data()?.players?.length;
    const outerPlayers = outerSnap.data()?.players;
    const outerFromCache = outerSnap.metadata.fromCache;
    const outerHasPending = outerSnap.metadata.hasPendingWrites;
    const outerTime = Date.now();
    console.log(`Outer read: players ${JSON.stringify(outerPlayers)} length ${outerVersion}, fromCache ${outerFromCache} at ${outerTime}`);

    // Competing commit N+1 as Client B (join the room)
    const competingStart = Date.now();
    // Client B joins: update players to add userB.uid (must be self-only change, so B adds self)
    // For B to join, B must be the caller, so we use dbB
    await updateDoc(roomRefB, {
      players: [userA.uid, userB.uid],
      updatedAt: serverTimestamp()
    });
    const competingEnd = Date.now();
    console.log(`Competing commit N+1 (B joins) at ${competingStart} -> ${competingEnd}`);

    await new Promise(r => setTimeout(r, 10));

    // Client A begins transaction and does tx.get
    const txStart = Date.now();
    let txVersion = null;
    let txPlayersLength = null;
    try {
      await runTransaction(dbA, async (tx) => {
        const txSnap = await tx.get(roomRefA);
        txVersion = txSnap.data()?.players?.length;
        txPlayersLength = txSnap.data()?.players?.length;
        // Do not write, just observe - abort to not change state further
        throw new Error("PROBE_ABORT");
      });
    } catch (e) {
      if (e.message !== "PROBE_ABORT") {
        console.log(`Transaction error: ${e.message}`);
      }
    }
    const txEnd = Date.now();
    console.log(`tx.get version ${txVersion} at ${txStart} -> ${txEnd}, outer was ${outerVersion}`);

    // Immediately after, do normal and server gets
    const normalSnap = await getDoc(roomRefA);
    const normalVersion = normalSnap.data()?.players?.length;
    const normalFromCache = normalSnap.metadata.fromCache;
    const normalTime = Date.now();

    let serverVersion = null, serverFromCache = null;
    try {
      const sSnap = await getDocFromServer(roomRefA);
      serverVersion = sSnap.data()?.players?.length;
      serverFromCache = sSnap.metadata.fromCache;
    } catch (e) {
      serverVersion = normalVersion;
    }
    const serverTime = Date.now();

    console.log(`Normal get after tx: version ${normalVersion}, fromCache ${normalFromCache} at ${normalTime}`);
    console.log(`Server get after tx: version ${serverVersion}, fromCache ${serverFromCache} at ${serverTime}`);
    console.log(`Listener version at this moment: ${listenerVersion} (first seen at ${listenerFirstSeenAt})`);

    // Poll for up to 5000ms
    const pollIntervals = [0,100,250,500,750,1000,1500,2000,3000,4000,5000];
    let firstNormalConvergence = null;
    let firstServerConvergence = null;
    let firstListenerConvergence = null;
    let prev = 0;
    for (let w of pollIntervals) {
      if (w > prev) await new Promise(r => setTimeout(r, w - prev));
      prev = w;
      const pSnap = await getDoc(roomRefA);
      const pVer = pSnap.data()?.players?.length;
      let pServerVer = null;
      try {
        const ps = await getDocFromServer(roomRefA);
        pServerVer = ps.data()?.players?.length;
      } catch {}
      const currentListenerVer = listenerVersion;
      const now = Date.now();
      console.log(`Poll ${w}ms: normal ${pVer} (fromCache ${pSnap.metadata.fromCache}), server ${pServerVer}, listener ${currentListenerVer}`);
      if (pVer > outerVersion && firstNormalConvergence === null) firstNormalConvergence = w;
      if (pServerVer !== null && pServerVer > outerVersion && firstServerConvergence === null) firstServerConvergence = w;
      if (currentListenerVer !== null && currentListenerVer > outerVersion && firstListenerConvergence === null) firstListenerConvergence = w;
      const pollLine = {
        run: runNum,
        waitMs: w,
        timestamp: new Date().toISOString(),
        expectedVersion: outerVersion,
        loadMatchVersion: pVer,
        serverVersion: pServerVer,
        listenerVersion: currentListenerVer,
        real: true
      };
      evidence.push(pollLine);
      try { fs.appendFileSync(EVIDENCE_PATH, JSON.stringify(pollLine) + "\n"); } catch {}
      if (pVer > outerVersion && pServerVer > outerVersion && currentListenerVer > outerVersion) break;
    }

    const result = {
      run: runNum,
      outerVersion,
      txVersion,
      normalConvergenceMs: firstNormalConvergence,
      serverConvergenceMs: firstServerConvergence,
      listenerConvergenceMs: firstListenerConvergence,
      outerPlayers,
      txPlayersLength,
      normalVersion: (await getDoc(roomRefA)).data()?.players?.length,
      finalListenerVersion: listenerVersion
    };
    console.log(`Run ${runNum} result:`, JSON.stringify(result));

    // Cleanup: delete room and users
    if (listenerUnsub) listenerUnsub();
    try {
      await deleteDoc(roomRefA);
      console.log(`Cleaned up room ${roomId}`);
    } catch (e) {
      console.log(`Cleanup room failed: ${e.message} - trying via admin (but we have no admin, so just log)`);
      // Try to leave room instead (set players to empty, status closed) as per rules
      try {
        await updateDoc(roomRefA, { players: [], status: "closed", updatedAt: serverTimestamp() });
        console.log(`Closed room via update instead`);
      } catch (e2) {
        console.log(`Close also failed: ${e2.message}`);
      }
    }
    try {
      await deleteUser(userA);
      console.log(`Deleted userA`);
    } catch (e) {
      console.log(`Delete userA failed: ${e.message}`);
    }
    try {
      await deleteUser(userB);
      console.log(`Deleted userB`);
    } catch (e) {
      console.log(`Delete userB failed: ${e.message}`);
    }

    return result;

  } catch (e) {
    console.log(`Run ${runNum} failed: ${e.message} ${e.code}`);
    if (listenerUnsub) listenerUnsub();
    // Try cleanup even on failure
    try {
      if (roomRefA) await deleteDoc(roomRefA).catch(()=>{});
    } catch {}
    try {
      if (userA) await deleteUser(userA).catch(()=>{});
    } catch {}
    try {
      if (userB) await deleteUser(userB).catch(()=>{});
    } catch {}
    return { run: runNum, error: e.message, code: e.code };
  } finally {
    if (listenerUnsub) try { listenerUnsub(); } catch {}
    try { await deleteApp(appA); } catch {}
    try { await deleteApp(appB); } catch {}
  }
}

async function main() {
  console.log(`=== Real Firestore Probe (estimation-lab) ===`);
  console.log(`Project: ${REAL_CONFIG.projectId}`);
  const results = [];
  for (let i = 1; i <= 3; i++) {
    const res = await runOnce(i);
    results.push(res);
    await new Promise(r => setTimeout(r, 200));
  }
  console.log(`\n=== SUMMARY ===`);
  results.forEach(r => {
    if (r.error) console.log(`Run ${r.run} error: ${r.error}`);
    else console.log(`Run ${r.run}: outer ${r.outerVersion} tx ${r.txVersion} normalConvergence ${r.normalConvergenceMs} server ${r.serverConvergenceMs} listener ${r.listenerConvergenceMs}`);
  });
  // Verify no emulator host was contacted
  console.log(`\nMade---estimation-card-game contacted: NO (project was ${REAL_CONFIG.projectId}, FIRESTORE_EMULATOR_HOST=${process.env.FIRESTORE_EMULATOR_HOST || "unset"})`);
}

main().catch(e=>{ console.error(e); process.exit(1); });
