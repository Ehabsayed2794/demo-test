# RUNBOOK — Real-backend H-matrix (scratch project only)

**Purpose:** empirically measure Firestore's REAL commit error-ordering —
security-rules denial (403) vs stale `currentDocument.updateTime` precondition
failure (400/409) — for the exact double-deal payload shape from the
2026-08-26 incident. The local emulator checks the precondition first
(HTTP 400, Matrix H2b); production returned 403. This experiment decides.

**Cost:** ~2 minutes + one throwaway Spark project. **Zero risk to the game:**
the script hard-refuses to run against any game-looking project id and touches
only `matches/mH-real` inside the scratch project.

## Owner steps

1. console.firebase.google.com → **Add project** → name it e.g. `ordering-probe`
   (disable Analytics; Spark plan is fine).
2. In the scratch project: **Build → Firestore Database → Create database**
   (production mode or test mode, either works — the script authenticates as a
   real anonymous user and the repo rules will be seeded next).
3. **Build → Authentication → Sign-in method → enable Anonymous.**
4. Optionally deploy the repo rules for full fidelity (skip if you want default
   open rules — Cell A/B/C conclusions about ORDERING do not require the game
   rules; the decisive Cell C uses rules-legal writes except the stale
   precondition):
   - `firebase deploy --only firestore:rules --project <scratch-id>` from this
     repo (accept replacing firestore.rules project binding), OR paste
     `firestore.rules` into Console → Rules.
5. Scratch project → Settings → General → copy the **Web API Key**.
6. From this repo:
   ```
   set FIREBASE_PROJECT_ID=<scratch-project-id>
   set FIREBASE_WEB_API_KEY=<web-api-key>
   set CONFIRM_SCRATCH=SCRATCH-ONLY
   node tests\h-matrix.real-backend.cjs
   ```
7. Paste the full stdout back into the postmortem thread.

## Reading the output

| Cell | Meaning |
|---|---|
| A — FIRST deal, valid precondition | sanity; expect HTTP 200 |
| C — duplicate carrying STALE pre-commit precondition | **DECISIVE**: HTTP 403 ⇒ real backend evaluates rules before/instead of the version check ⇒ production's 403 IS the forward-only idempotency denial of the lost deal race (race model confirmed). HTTP 400/409 ⇒ precondition-first on real backend too ⇒ race model stays refuted, unidentified front-door layer implicated |
| B — duplicate, refreshed precondition | control; expect 403 permission-denied (bare message — real backend does not print rules L-numbers) |
