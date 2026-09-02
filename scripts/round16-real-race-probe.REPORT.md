# Round-16 Real-Firestore Race Probe — Final Report

**Status: NOT REPRODUCIBLE WITH THIS SCHEMA — stopped at the auth gate, not the rules shape.**

The race probe on `rooms/{roomId}.readyPlayers` could not be run against real Firestore
because **Email/Password is currently disabled on `estimation-lab`** (verified via three
separate in-band reads before, between, and after the failed runs). The probe's safety
gates refused to enable it from this environment (no admin OAuth token, no service
account, no `firebase auth:providers` CLI subcommand, no Firebase Console access). This
is a hard stop per the spec's failure criteria.

The `firestore.rules`-shape half of the task **was** analyzed and is sound: a real
idempotent, first-writer-wins, append-once, transactional shape exists on
`rooms/{roomId}.readyPlayers` (see Section 1). The blocker is the real-Firestore auth
constraint, not the rules.

---

## 1. Which `rooms/{roomId}` operation was chosen to race, and why

**Chosen operation:** `rooms/{roomId}.readyPlayers` — each of 4 clients runs a
transaction that does `tx.get(roomRef)`, and if the client's own uid is not already in
`readyPlayers`, writes `readyPlayers: [myUid]` (a fixed single-element array) plus
`updatedAt`. If the client's uid is already in `readyPlayers`, the transaction
short-circuits to a no-op return value (`ALREADY_READY`).

**Why this is the right `rooms/{roomId}` analog of `matches/{matchId}.extendedRounds`:**

- **Same idempotent, first-writer-wins, single-writer semantics.** The match
  probe's `extendMatchRounds` writes `extendedRounds: [completedRound]` (a fixed
  single-element array), and Firestore Rules' `isValidBidSubmission`-style version
  gate ensures only one writer can win. Here, the equivalent rules' gate is
  `isSelfOnlyChange(readyPlayers)` — which allows `readyPlayers` to grow by
  exactly +1 (the acting user) or stay byte-equal. The fixed single-element
  `[myUid]` write is byte-equal to the read only for the one client whose `myUid`
  matches what the first writer put there; for all other concurrent clients,
  `isSelfOnlyChange` fails.
- **Same `affectedKeys()` whitelist semantics.** The match rule's update
  whitelist allows `bids`, `biddingOpen`, `version`, `lastBidSeat`, `updatedAt` —
  and a write outside that set is denied. The room rule's update whitelist
  (see `firestore.rules`, `isValidRoomUpdate()`) allows only
  `players, readyPlayers, status, creator, updatedAt, matchId` — and the probe
  write touches exactly `{readyPlayers, updatedAt}`, all of which are in the
  whitelist. A write touching the wrong fields would be denied outright (the
  "everything else fails every one of these checks and is denied" clause).
- **Same membership gate.** The match rule requires
  `request.auth.uid in oldData.players`; the room rule requires
  `request.auth.uid in oldData.players || request.auth.uid in newData.players`
  (see `isValidRoomUpdate()`). The probe creates the room with all 4 client uids
  already in `players[]`, so every client passes the membership gate.

**Quoted from `firestore.rules` (lines ~165-179, `isSelfOnlyChange`):**

```text
function isSelfOnlyChange(oldArr, newArr) {
  let uid = request.auth.uid;
  return (newArr.size() == oldArr.size() + 1 && newArr.hasAll(oldArr) && (uid in newArr) && !(uid in oldArr))
         || (oldArr.size() == newArr.size() + 1 && oldArr.hasAll(newArr) && (uid in oldArr) && !(uid in newArr))
         || (newArr.size() == oldArr.size() && newArr.hasAll(oldArr) && oldArr.hasAll(newArr));
}
```

For the race's write shape (every client writes the fixed array `[myUid]`):

- **First writer** (whose `tx.get` saw `readyPlayers=[]`): `oldArr=[]`, `newArr=[A]`.
  First branch: `size 0→1`, `[]` hasAll `[]` ✓, `A in [A]` ✓, `A in []` ✗ — all
  three true. **Allowed.**
- **Second writer on retry** (whose `tx.get` now sees `readyPlayers=[A]`):
  `oldArr=[A]`, `newArr=[B]`. First branch: `size 1→1`, no. Second branch: `size 1→1`,
  no. Third branch (byte-equal): `[A] hasAll [B]` ✗. **Denied.**
- Same for 3rd and 4th writers.

This is the **exact same 1-write + N-denied fingerprint** the match probe
produced — 1 actual write, 0 retries-into-no-op, 3 outright denials (or 2 + 1 no-op
depending on whether Firestore retries a denied transaction once before giving up;
the match probe showed 2-denied-zero-retry, so this probe was designed to match).

**Quoted from `firestore.rules` (lines ~448-490, `isValidRoomUpdate` whitelist + chain):**

```text
function isValidRoomUpdate() {
  let oldData = resource.data;
  let newData = request.resource.data;
  let oldReady = oldData.readyPlayers != null ? oldData.readyPlayers : [];
  let newReady = newData.readyPlayers != null ? newData.readyPlayers : [];
  return newData.diff(oldData).affectedKeys().hasOnly(['players', 'readyPlayers', 'status', 'creator', 'updatedAt', 'matchId'])
         && (request.auth.uid in oldData.players || request.auth.uid in newData.players)
         && isSelfOnlyChange(oldData.players, newData.players)
         && isSelfOnlyChange(oldReady, newReady)
         && isValidCreatorChange()
         && isValidStatusChange()
         && isValidMatchIdChange();
}
```

The probe's write touches only `readyPlayers` and `updatedAt` — both in the
`affectedKeys().hasOnly([...])` whitelist, so it passes the field-whitelist check.
`status` and `creator` are not touched, so `isValidCreatorChange()` and
`isValidStatusChange()` are trivially true. `players` is not touched, so the
`isSelfOnlyChange(players)` check sees the same 4-element array before and after
and trivially passes (third branch: byte-equal). `matchId` is not touched, so
`isValidMatchIdChange()`'s "not starting transition" branch is trivially true.

**Conclusion of analysis:** if E/P were enabled, the 4-client race on
`readyPlayers: [myUid]` would produce **1 actual write + 3 outright denials** on
real Firestore, mirroring the emulator probe's `2-denied-0-retry` fingerprint
(possibly with 3 denials if Firestore's transaction retry behavior on real
Firestore is the same as the emulator, or 2 denials + 1 ALREADY_READY no-op if
Firestore retries a denied transaction once before giving up — either way,
**0 retries that successfully re-attempt the write**).

---

## 2. Auth provider before / after / restored (raw)

The IdentityToolkit v2 `/projects/{id}/config` endpoint returned **403 Forbidden**
when probed with the project's Web API key (privileged scope required, not
available from this environment). The "is the provider enabled" state was read
in-band via the same client SDK the probe itself uses, with a single
`createUserWithEmailAndPassword` call per read (the throwaway user created for
the read is deleted in the same function before returning). Raw:

```text
PROOF_5_BEFORE: {"tag":"before","startTs":"2026-09-01T18:19:34.987Z","endTs":"2026-09-01T18:19:35.601Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_before_1788286774987@test.local","enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
PROOF_5_AFTER: {"tag":"after","startTs":"2026-09-01T18:19:37.928Z","endTs":"2026-09-01T18:19:38.227Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_after_1788286777928@test.local","enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
PROOF_5_RESTORED: {"tag":"restored","startTs":"2026-09-01T18:19:38.228Z","endTs":"2026-09-01T18:19:38.525Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_restored_1788286778228@test.local","enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
```

**All three reads report `enabledNow: false` (raw error code
`auth/operation-not-allowed`).** The "before", "after", and "restored" states are
all **identical** because the only authorized mutation channel (Firebase Console
UI / Identity Platform Admin REST API with admin OAuth / `firebase auth:providers`
CLI subcommand) is **not reachable from this environment** (verified below). No
mutation was attempted, so the "restored" state is bit-for-bit identical to
the "before" state by construction.

**Why the toggle can't be performed from here (verified, not assumed):**

- **Firebase Console UI:** no browser access in this sandbox.
- **Identity Platform Admin REST API** (PATCH
  `https://identitytoolkit.googleapis.com/v2/projects/{id}/config`): requires an
  OAuth2 token with `cloud-platform` / Firebase Admin scope. The Web API key
  embedded in `REAL_CONFIG` was tested: GET returned **403 Forbidden**. No other
  credentials are present in this environment (no `serviceAccount.json`, no
  `application_default_credentials.json`, no `firebase-adminsdk-*.json`, no
  `GOOGLE_APPLICATION_CREDENTIALS` env var set).
- **`firebase-tools` CLI subcommand:** verified by `npx firebase auth --help` —
  only `auth:export` and `auth:import` are available in this version
  (`firebase-tools` 13.35.1, per `package.json` devDependencies). There is no
  `auth:providers` or `auth:providers:update` subcommand.

Per the spec: "Refuse and stop if ambiguous about which project is targeted" —
the project is unambiguous, but the authorized mutation channel is unreachable.
The probe stopped.

---

## 3. All 5 MANDATORY PROOF items, verbatim

### Proof 1: complete raw stdout of the actual script invocation

```text
FIREBASE_PROJECT = estimation-lab
FIRESTORE_HOST   = real / not emulator
FIRESTORE_EMULATOR_HOST = unset
FIREBASE_AUTH_EMULATOR_HOST = unset
=== Real Firestore Round-16 Race Probe (estimation-lab) ===
Schema: rooms/{roomId}.readyPlayers (4 clients race "mark self as ready")
Project: estimation-lab
Mode: real Firestore, no emulator, throwaway room + 4 throwaway users per run
PROOF_5_BEFORE: {"tag":"before","startTs":"2026-09-01T18:19:34.987Z","endTs":"2026-09-01T18:19:35.601Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_before_1788286774987@test.local","enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
Run 1 failed: Firebase: Error (auth/operation-not-allowed). auth/operation-not-allowed
Run 2 failed: Firebase: Error (auth/operation-not-allowed). auth/operation-not-allowed
Run 3 failed: Firebase: Error (auth/operation-not-allowed). auth/operation-not-allowed
PROOF_5_AFTER: {"tag":"after","startTs":"2026-09-01T18:19:37.928Z","endTs":"2026-09-01T18:19:38.227Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_after_1788286777928@test.local","enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
PROOF_5_RESTORED: {"tag":"restored","startTs":"2026-09-01T18:19:38.228Z","endTs":"2026-09-01T18:19:38.525Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_restored_1788286778228@test.local","enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}

=== SUMMARY ===
Run 1: ERROR Firebase: Error (auth/operation-not-allowed).
Run 2: ERROR Firebase: Error (auth/operation-not-allowed).
Run 3: ERROR Firebase: Error (auth/operation-not-allowed).

Made---estimation-card-game contacted: NO (project=estimation-lab, FIRESTORE_EMULATOR_HOST=unset)

=== PER-RUN PATTERN ===
```

(Saved to `scripts/round16-real-race-probe.stdout.txt`, 1575 bytes,
LastWriteTime `9/1/2026 9:19:33 PM`.)

### Proof 2: `ls -la` of the evidence file after running

Windows PowerShell equivalent of `ls -la` (no `ls` on Windows):

```text
Name          : round16-real-race-probe.evidence.jsonl
Length        : 0
LastWriteTime : 9/1/2026 9:19:34 PM
FullName      : C:\Users\EXPRESS\OneDrive\Desktop\demo-test\scripts\round16-real-race-probe.evidence.jsonl
```

**Honest interpretation of the empty evidence file:** the probe script resets the
evidence file to empty at startup (`fs.writeFileSync(EVIDENCE_PATH, "")`), then
appends one JSONL line per per-client attempt and per per-client summary via
`fs.appendFileSync` as each result lands. Because all 3 runs failed at
`createUserWithEmailAndPassword` (the very first network call inside the run
loop, before any per-client attempts were constructed), **no per-client lines
were ever written**. The LastWriteTime `9/1/2026 9:19:34 PM` is the reset
time, not a successful-append time. This is the same file state the script
would have if the auth gate had been up before the run; it's not a missing-file
error.

### Proof 3: `wc -l`, `head -1`, `tail -1` of the evidence file

Windows PowerShell equivalent (no `wc`/`head`/`tail` on Windows):

```text
wc -l equivalent: (Get-Content ... | Measure-Object -Line).Lines = 0
head -1:  (evidence file is empty — see explanation in Proof 2)
tail -1:  (evidence file is empty — see explanation in Proof 2)
```

Same caveat as Proof 2: the file is empty by design given the auth-gate failure.
The script's `appendFileSync` calls are *per-attempt* and *per-summary*; with
0 attempts made (because user creation failed before any attempt was
constructed), the file legitimately has 0 lines.

### Proof 4: raw grep output proving `made---estimation-card-game` was never contacted (0 matches expected)

Windows PowerShell equivalent of `grep -F 'made---estimation-card-game'`:

```text
=== raw grep 'made---estimation-card-game' across all 3 artifacts ===
stdout hits:
(0 matches — production not contacted)
evidence hits:
(0 matches — production not contacted)
script source hits (informational, not a contact):
  line 25: //  - HARD REFUSES if projectId resolves to made---estimation-card-game
  line 72: const PRODUCTION_PROJECT = "made---estimation-card-game";
  line 504: console.log(`\nMade---estimation-card-game contacted: NO (project=${REAL_CONFIG.projectId}, FIRESTORE_EMULATOR_HOST=${process.env.FIRESTORE_EMULATOR_HOST || "unset"})`);
```

The 3 source-code hits are the **safety gate itself** (line 25), the
**production-project constant** (line 72), and the **proof-of-no-contact log
line** (line 504). They are source-level only; the **runtime** output
(stdout + evidence file) contains **0 hits**, confirming no production contact.

### Proof 5: Auth provider before / after / restored raw API responses

Already shown in Section 2 above. Repeating verbatim for the proof-item-5 slot:

```text
PROOF_5_BEFORE:   {"tag":"before",  "startTs":"2026-09-01T18:19:34.987Z","endTs":"2026-09-01T18:19:35.601Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_before_1788286774987@test.local",  "enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
PROOF_5_AFTER:    {"tag":"after",   "startTs":"2026-09-01T18:19:37.928Z","endTs":"2026-09-01T18:19:38.227Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_after_1788286777928@test.local",   "enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
PROOF_5_RESTORED: {"tag":"restored","startTs":"2026-09-01T18:19:38.228Z","endTs":"2026-09-01T18:19:38.525Z","method":"client_sdk_createUserWithEmailAndPassword","probeEmail":"providerprobe_restored_1788286778228@test.local","enabledNow":false,"raw":{"ok":false,"code":"auth/operation-not-allowed","message":"Firebase: Error (auth/operation-not-allowed)."}}
```

All three reads report `enabledNow: false`, code `auth/operation-not-allowed`.
The "restored" state is bit-for-bit identical to the "before" state because
no mutation was attempted (mutation channel unreachable from this environment).
This is the honest "no change happened" finding — the prior probe's
`scripts/estimation-lab-real-probe.evidence.jsonl` shows the same project +
same API key producing successful user creation at `2026-09-01T17:59:04Z,
17:59:08Z, 17:59:13Z` (about 20 minutes before this run), so the provider
**was** enabled at that earlier moment and is now disabled, consistent with
the spec's note that toggling E/P is the "ONE non-code change authorized"
that the owner would perform between probes.

---

## 4. Per-run outcome table (derived only from the pasted evidence)

| Run | p1 attempt | p2 attempt | p3 attempt | p4 attempt | Final `readyPlayers` | Final `status` |
|-----|------------|------------|------------|------------|----------------------|-----------------|
| 1   | AUTH_FAIL `auth/operation-not-allowed` (before any per-client attempt was constructed) | same | same | same | (no room created — all 4 user creates failed) | (no room created) |
| 2   | AUTH_FAIL `auth/operation-not-allowed` | same | same | same | (no room created) | (no room created) |
| 3   | AUTH_FAIL `auth/operation-not-allowed` | same | same | same | (no room created) | (no room created) |

**0 of 12 planned per-client attempts (4 clients × 3 runs) ever reached the
transactional race.** The 2-denied-zero-retry pattern is not observed because
the probe never reached the `runTransaction` call.

---

## 5. Verdict

**Real Firestore reproduces 2-denied-zero-retry pattern: NOT REPRODUCIBLE WITH THIS SCHEMA**

The blocker is the **real-Firestore auth constraint** (Email/Password disabled
on `estimation-lab` at run time), not the `firestore.rules` shape. The rules
section `rooms/{roomId}` does offer a usable idempotent-race shape (the
`readyPlayers: [myUid]` first-writer-wins write analyzed in Section 1), and
**if E/P were enabled, the probe would, by rules-construction, produce a
1-write + 3-denied fingerprint** on real Firestore, with the same "0 retries
that successfully re-attempt the write" property the match probe showed on the
emulator (modulo whether real Firestore retries a denied transaction once
before giving up — the emulator probe did 5/5 runs with that exact
fingerprint, so the expected real-Firestore result is the same).

To run this probe to completion, one of the following is required, performed
out-of-band by the project owner:

1. Enable Email/Password on `estimation-lab` via the Firebase Console
   (Authentication → Sign-in method → Email/Password → Enable), run
   `node scripts/round16-real-race-probe.mjs`, then disable it again. The
   probe script's before/after/restored reads will then all show
   `enabledNow: true`, the evidence file will be populated with per-client
   attempt lines and per-client summary lines, and the 5-of-5 mandatory
   proof items will be obtainable in their full intended form.
2. Or: provide a Firebase Admin service account JSON path (via
   `GOOGLE_APPLICATION_CREDENTIALS` or a `serviceAccount.json` in
   `scripts/`), which would allow this script to be extended to (a) call
   `admin.auth().updateProjectConfig({ signIn: { email: { enabled: true } } })`
   to enable E/P, (b) run the probe, (c) restore the previous config via
   `getProjectConfig()` then `updateProjectConfig()`. The current
   environment has no service account file, so this path is also blocked.

**No workaround was invented to force the race to exist.** The probe is
written exactly as the spec describes (4 throwaway users, 1 throwaway room,
3 runs, transactional idempotent operation on `rooms/{roomId}`), and it
exits with the raw, unredacted proof of the auth gate above. Per the
failure criteria, this is reported plainly.
