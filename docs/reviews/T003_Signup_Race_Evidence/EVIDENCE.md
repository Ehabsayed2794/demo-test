# T-003 — Signup double-bootstrap 409 race: evidence record

**Date:** 2026-09-19
**Scope:** `design-ui/login/index.html` only. `firestore.rules` and
`firestore.rules.sha256` **untouched**. Finding 5b (`dealRound` permission-denied)
remains **parked** per task scope — root cause still unproven.

---

## 1. Root cause (proven, not assumed)

`createUserWithEmailAndPassword()` signs the user in **immediately**. So on every
signup, `bootstrapProfile()` ran **twice concurrently for the same brand-new uid**:

- `design-ui/login/index.html` — the create form's own `await bootstrapProfile(cred.user)`
- `design-ui/login/index.html` — the `onAuthStateChanged` listener, which fires
  because creation implies sign-in

Both calls reach `PlayerService.ensurePlayerProfile()`
(`design-ui/player-service.js:85`), whose body is a get-then-set
`runTransaction`. Both transactions read `players/{uid}` as **missing**, so both
`tx.set()` calls attach a `currentDocument: { exists: false }` precondition. The
first commit wins; **the loser commits into `409 ALREADY_EXISTS`** — on every
signup, including production.

This contradicts the idempotency contract documented at
`design-ui/player-service.js:76-80`, which claims "one wins the create, the other
observes the result and just touches lastSeenAt." A get-then-set transaction does
not deliver that: the loser's precondition fails instead of observing.

### A second, silent symptom

The 409 was non-fatal (the caller catches it and proceeds), so it could have been
shipped. But the *same* race has a worse half: when the auth-state path won
outright, it derived the profile **before `updateProfile()` had landed**, so
`user.displayName` was still null and `mapAuthUserToProfileDefaults()`
(`design-ui/player-service.js:39-40`) persisted `displayName: "Player"`
**permanently** — the document then exists, so the form's own transaction only
stamps `lastSeenAt` and never corrects the name. The player's typed name is
silently discarded forever.

---

## 2. Why `{ merge: true }` was rejected

The obvious fix — `tx.set(ref, profile, { merge: true })` — was **rejected after
reading the rules**, not applied blindly, as required.

`firestore.rules` on `players/{uid}`:

- `allow create: if isOwner(uid) && isValidNewProfile()`
- `allow update: if isOwner(uid) && onlyAllowedFieldsChanged()`

`onlyAllowedFieldsChanged()` permits only `displayName`, `avatarInitial`,
`lastSeenAt`, `currentRoomId`, `currentMatchId`. A merge write carries
`createdAt`, `rank`, `rp`, `wins`, `streak`, `level`, `coins`, `gems` — protected
fields that can **never** change via client update. So merge semantics would not
idempotently upsert; it would be re-applying defaults on a document that now
exists, which the rules evaluate as an **update and deny with 403** — trading one
error code for another, and on a path that also silently re-applies progression
defaults. Worse, it would do nothing about the name corruption, which is a race
in *ordering*, not in write semantics.

The documented contract (first call creates with defaults, later calls touch only
`lastSeenAt`) is only satisfiable by **ensuring there is one caller**. That is the
fix.

---

## 3. The fix

`design-ui/login/index.html` (+46/−2), one file:

1. **`formFlowInFlight`** — the create and sign-in handlers set it to `true`
   **synchronously, before their first `await`**. The auth-state event fires when
   the credential resolves, which is *after* that line has already executed, so
   the flag is deterministically already set. This ordering is what separates a
   real fix from a smaller race.
2. **`onAuthStateChanged` defers** — `if (formFlowInFlight) return;` The form,
   which holds the typed name and awaits `updateProfile()` before bootstrapping,
   becomes the sole bootstrap caller and owns the navigation.
3. **`bootstrapInFlight`** — a per-uid promise memo as a second, independent
   guard, so even a future third caller cannot start a second transaction for a
   uid already in flight. Entries clear on settle, so a later genuine returning
   user is not blocked.

---

## 4. Evidence

### 4a. Negative control — the new test against the PRE-FIX code

`tests/signup-bootstrap-race.e2e.test.cjs` was run against the reverted
(`git checkout --`) `design-ui/login/index.html`. It caught **both** symptoms:

```
PASS  harness reached the emulator (signups resolved: 5/5)
FAIL  R1 no 409 ALREADY_EXISTS across 5 fresh signups -- 0:[200,409] 1:[200,409] 2:[200,409] 3:[200,409] 4:[200]
FAIL  R2 exactly one :commit per signup (no duplicate bootstrap) -- 0=2,1=2,2=2,3=2
FAIL  R3 the TYPED display name is persisted (not "Player") -- 4=Player
PASS  R4 lastSeenAt present (returning-user contract intact)
PASS  R5 every signup still navigates to /lobby/

3 passed, 3 failed

signup 0: commits=[200,409] 409s=1 name=Race0
signup 1: commits=[200,409] 409s=1 name=Race1
signup 2: commits=[200,409] 409s=1 name=Race2
signup 3: commits=[200,409] 409s=1 name=Race3
signup 4: commits=[200] 409s=0 name=Player
```

Signup 4 is the silent half of the bug in a single cell: no 409, but the typed
name was replaced by `"Player"`. A fix that only killed the 409 would still leave
that row failing — and the first fix attempt did exactly that.

### 4b. Post-fix

`tests/signup-bootstrap-race.e2e.test.cjs` against the fixed source:

```
PASS  harness reached the emulator (signups resolved: 5/5)
PASS  R1 no 409 ALREADY_EXISTS across 5 fresh signups
PASS  R2 exactly one :commit per signup (no duplicate bootstrap)
PASS  R3 the TYPED display name is persisted (not "Player")
PASS  R4 lastSeenAt present (returning-user contract intact)
PASS  R5 every signup still navigates to /lobby/

6 passed, 0 failed
```

Earlier full-size repro (10 signups, assembled `hosting-dist/` artifact) after the
fix: **0 × 409, 10 commits total, 10/10 typed names, 10/10 lastSeenAt** —
`t003-repro.md`. Commit count dropped from 21 to 10 across 10 signups, which is
the second bootstrap call no longer happening at all.

### 4c. Regression

See the T-003 PR. `npm run test:ci` compared against the T-002 51/51 baseline;
`firestore.rules` SHA `9d8662e3…b516` unchanged.

---

## 5. Test added

`tests/signup-bootstrap-race.e2e.test.cjs` — permanent regression test. Drives
the **real create form** (the existing 51-file suite signed in via a direct
`createUserWithEmailAndPassword()` and never touched it, which is why this race
had zero coverage and survived). Serves `design-ui/` source rather than the built
artifact, so it runs in CI with no build step and fails the moment the source
regresses.

Guards: emulator-gated via `initializeTestEnvironment`; exits 2 (never silently 0)
if the emulator or browser is unavailable; fail-closed resolved-host check (abort
unless `Db` host is `127.0.0.1` — the harness bug from T-002 that wrote to
production); a time budget that converts a hang into a diagnosed failure; and a
vacuous-pass guard. Deliberately avoids the literal word "SKIPPED", which
`scripts/run-tests.mjs` treats as a hard failure.

## 6. Temp artifacts removed

`scripts/_t003-repro.cjs` — the throwaway repro harness, deleted. Its result is
preserved in `t003-repro.md`.
