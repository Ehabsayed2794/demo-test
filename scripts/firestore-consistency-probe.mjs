#!/usr/bin/env node
// Minimal Firestore consistency probe for estimation-lab vs emulator
// No production game logic, no Auth, no rules deploy, no hosting
import { initializeApp, deleteApp } from "firebase/app";
import { getFirestore, doc, setDoc, getDoc, updateDoc, deleteDoc, onSnapshot, runTransaction, connectFirestoreEmulator } from "firebase/firestore";

// Config for estimation-lab (from firebase apps:sdkconfig)
const REAL_CONFIG = {
  apiKey: "AIzaSyCZdHyv0tC2nOJmPCJP5T4dR9ViD0d_KZs",
  authDomain: "estimation-lab.firebaseapp.com",
  projectId: "estimation-lab",
  storageBucket: "estimation-lab.firebasestorage.app",
  messagingSenderId: "597143777359",
  appId: "1:597143777359:web:9a4a75b55c2c17f9f23a3a"
};

const EMULATOR_HOST = "127.0.0.1";
const EMULATOR_PORT = 8080;

function assertIsolatedRealEnv() {
  const firestoreHost = process.env.FIRESTORE_EMULATOR_HOST;
  const authHost = process.env.FIREBASE_AUTH_EMULATOR_HOST;
  console.log(`FIREBASE_PROJECT = ${REAL_CONFIG.projectId}`);
  console.log(`FIRESTORE_HOST = ${firestoreHost ? firestoreHost + " (emulator)" : "real / not emulator"}`);
  console.log(`AUTH_EMULATOR_HOST = ${authHost || "unset"}`);
  console.log(`FIRESTORE_EMULATOR_HOST = ${firestoreHost || "unset"}`);
  if (firestoreHost || authHost) {
    console.log("ABORT: emulator host is active during real test - not isolated");
    process.exit(1);
  }
  if (REAL_CONFIG.projectId === "made---estimation-card-game") {
    console.log("ABORT: would touch production project");
    process.exit(1);
  }
  console.log("Isolated real Firestore check: PASS (not production, no emulator host)");
}

async function runProbe(useEmulator) {
  const env = useEmulator ? "emulator" : "real";
  console.log(`\n=== PROBE ENV: ${env} ===`);
  if (useEmulator) {
    // Emulator will be started externally via emulators:exec, so we just connect
    console.log(`Using emulator at ${EMULATOR_HOST}:${EMULATOR_PORT}`);
  } else {
    assertIsolatedRealEnv();
  }

  // For emulator, we need to handle both cases: if running via emulators:exec, the Firestore emulator is already running at 8080
  // For real, we don't connect to emulator
  const runs = 5;
  const results = [];

  for (let run = 1; run <= runs; run++) {
    const runId = `probe_${Date.now()}_${run}_${Math.random().toString(36).slice(2,6)}`;
    const docId = `consistency_probe/probe_${runId}`;
    console.log(`\n--- Run ${run} doc: ${docId} ---`);

    // Create isolated app per run to avoid cross-run cache pollution? Use single app but clear
    const app = useEmulator ? initializeApp({projectId: "demo-test-probe"}, `probe-app-${run}-${Date.now()}`) : initializeApp(REAL_CONFIG, `probe-real-${run}-${Date.now()}`);
    const db = getFirestore(app);
    if (useEmulator) {
      try { connectFirestoreEmulator(db, EMULATOR_HOST, EMULATOR_PORT); } catch(e) {}
    }

    let docRef;
    let listenerUnsub = null;
    let listenerVersion = null;
    let listenerFirstSeenAt = null;
    let listenerCardLogLength = null;

    const startListener = () => {
      return new Promise((resolve) => {
        const ref = doc(db, docId);
        listenerUnsub = onSnapshot(ref, (snap) => {
          if (snap.exists()) {
            const data = snap.data();
            const ver = data?.version;
            if (ver !== undefined && listenerVersion !== ver) {
              listenerVersion = ver;
              if (!listenerFirstSeenAt) {
                const now = Date.now();
                listenerFirstSeenAt = now;
                // Also capture cardLog length if exists
                listenerCardLogLength = data?.cardLog?.length || 0;
                // Resolve on first snapshot after creation? But we need to track N+1
              }
            }
          }
          // Resolve after first snapshot (could be empty)
          resolve();
        }, (err) => {
          console.log(`Listener error: ${err.message}`);
          resolve();
        });
        // Timeout for initial setup
        setTimeout(resolve, 1000);
      });
    };

    try {
      // Setup listener before creating doc
      await startListener();
      // Small delay to let listener attach
      await new Promise(r => setTimeout(r, 100));

      docRef = doc(db, docId);
      // Create initial doc version 1
      const outerStart = Date.now();
      await setDoc(docRef, {version: 1, writer: "probe", createdAt: Date.now(), runId});
      console.log(`T0 setDoc version 1 at ${outerStart}`);

      // Outer read N
      const outerSnap = await getDoc(docRef);
      const outerVersion = outerSnap.data()?.version;
      const outerCardLogLength = outerSnap.data()?.cardLog?.length || 0;
      const outerTurn = outerSnap.data()?.turn;
      const outerFromCache = outerSnap.metadata.fromCache;
      const outerHasPending = outerSnap.metadata.hasPendingWrites;
      const outerTime = Date.now();
      console.log(`Outer read: version ${outerVersion}, fromCache ${outerFromCache}, hasPending ${outerHasPending} at ${outerTime}`);

      // Also try server get
      let serverSnap = null;
      try {
        serverSnap = await getDoc(doc(doc(db, docId), {source: "server"}));
        // Actually getDoc with source option is not standard; for modular SDK, use getDocFromServer
        // Try alternative: use getDoc with options via doc ref? Let's just use normal get for now
      } catch(e) {}

      // Competing commit N+1 - simulate Client B
      const competingStart = Date.now();
      await updateDoc(docRef, {version: 2, writer: "probe-B", updatedAt: Date.now()});
      const competingEnd = Date.now();
      console.log(`Competing commit N+1 (2) at ${competingStart} -> ${competingEnd}`);

      // Small delay to ensure commit is processed
      await new Promise(r => setTimeout(r, 10));

      // Client A begins transaction and does tx.get
      const txStart = Date.now();
      let txVersion = null;
      let txCardLogLength = null;
      let txTurn = null;
      try {
        await runTransaction(db, async (tx) => {
          const txSnap = await tx.get(docRef);
          txVersion = txSnap.data()?.version;
          txCardLogLength = txSnap.data()?.cardLog?.length || 0;
          txTurn = txSnap.data()?.turn;
          // Do not write, just read to observe
          // Throw to abort transaction without write, so we can observe read
          throw new Error("PROBE_ABORT");
        });
      } catch (e) {
        if (e.message !== "PROBE_ABORT") {
          console.log(`Transaction error (unexpected): ${e.message}`);
        }
      }
      const txEnd = Date.now();
      console.log(`tx.get version ${txVersion} at ${txStart} -> ${txEnd}, outer was ${outerVersion}`);

      // Immediately after tx, do normal and server gets
      const normalSnap = await getDoc(docRef);
      const normalVersion = normalSnap.data()?.version;
      const normalFromCache = normalSnap.metadata.fromCache;
      const normalHasPending = normalSnap.metadata.hasPendingWrites;
      const normalTime = Date.now();

      // Try server get via getDocFromServer if available
      let serverVersion = null, serverFromCache = null, serverHasPending = null;
      try {
        const { getDocFromServer } = await import("firebase/firestore");
        const sSnap = await getDocFromServer(docRef);
        serverVersion = sSnap.data()?.version;
        serverFromCache = sSnap.metadata.fromCache;
        serverHasPending = sSnap.metadata.hasPendingWrites;
      } catch (e) {
        serverVersion = normalVersion; // fallback
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
        const pSnap = await getDoc(docRef);
        const pVer = pSnap.data()?.version;
        let pServerVer = null;
        try {
          const { getDocFromServer } = await import("firebase/firestore");
          const ps = await getDocFromServer(docRef);
          pServerVer = ps.data()?.version;
        } catch {}
        // listenerVersion is updated via onSnapshot callback, already tracked
        if (pVer > outerVersion && firstNormalConvergence === null) firstNormalConvergence = w;
        if (pServerVer !== null && pServerVer > outerVersion && firstServerConvergence === null) firstServerConvergence = w;
        if (listenerVersion !== null && listenerVersion > outerVersion && firstListenerConvergence === null) firstListenerConvergence = w;
        console.log(`Poll ${w}ms: normal ${pVer} (fromCache ${pSnap.metadata.fromCache}), server ${pServerVer}, listener ${listenerVersion}`);
        if (pVer > outerVersion && pServerVer > outerVersion && listenerVersion > outerVersion) break;
      }

      const result = {
        run,
        outerVersion,
        txVersion,
        normalConvergenceMs: firstNormalConvergence,
        serverConvergenceMs: firstServerConvergence,
        listenerConvergenceMs: firstListenerConvergence,
        normalFromCache: normalFromCache,
        serverFromCache: serverFromCache
      };
      console.log(`Run ${run} result:`, JSON.stringify(result));
      results.push(result);

    } catch (e) {
      if (e.message && e.message.includes("permission-denied")) {
        console.log(`Probe blocked by rules: ${e.message} - cannot write to ${docId} without auth or rules deploy`);
        console.log(`Skipping real Firestore probe - would require deploying rules or enabling Auth`);
        if (listenerUnsub) listenerUnsub();
        try { await deleteApp(app); } catch {}
        return {blocked: true, reason: "permission-denied", docId};
      }
      console.log(`Run ${run} failed: ${e.message} ${e.code}`);
      results.push({run, error: e.message});
    } finally {
      // Cleanup
      if (listenerUnsub) listenerUnsub();
      try {
        const ref = doc(db, docId);
        await deleteDoc(ref);
        const check = await getDoc(ref);
        console.log(`Cleanup ${docId}: exists after delete? ${check.exists()}`);
      } catch (e) {
        console.log(`Cleanup failed for ${docId}: ${e.message}`);
      }
      try { await deleteApp(app); } catch {}
      // Small delay between runs
      await new Promise(r => setTimeout(r, 100));
    }
  }

  console.log(`\n=== ${env} SUMMARY ===`);
  results.forEach(r => {
    if (r.blocked) console.log(`Blocked: ${r.reason}`);
    else if (r.error) console.log(`Run ${r.run} error: ${r.error}`);
    else console.log(`Run ${r.run}: outer ${r.outerVersion} tx ${r.txVersion} normalConvergence ${r.normalConvergenceMs} server ${r.serverConvergenceMs} listener ${r.listenerConvergenceMs}`);
  });
  return results;
}

async function main() {
  const useEmulator = process.env.USE_EMULATOR !== "false";
  // For this probe, we will run emulator first, then real if possible
  // Check env
  console.log(`USE_EMULATOR=${useEmulator}`);
  if (useEmulator) {
    console.log("Running emulator probe (requires emulators:exec or emulators:start)");
    // For emulator, we assume Firestore emulator is at 127.0.0.1:8080 via FIRESTORE_EMULATOR_HOST
    // But for this standalone test, we will just use the default (which will be emulator if FIRESTORE_EMULATOR_HOST is set)
    // If not set, we will try to connect to emulator via connectFirestoreEmulator
    await runProbe(true);
  } else {
    await runProbe(false);
  }
}

main().catch(e=>{ console.error(e); process.exit(1); });
