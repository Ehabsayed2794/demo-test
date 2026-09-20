# T-002 — design-ui/ as Root Build Artifact: Final Verification Report

**Date:** 2026-09-19
**Task:** TASK_T-002_INTEGRATE_DESIGN_UI_BUILD.md
**Verdict:** ✅ **All 6 acceptance criteria PASS.** No changes were required to
`scripts/build-hosting.mjs`, `firebase.json`, `package.json`, or any
`design-ui/` source file. The task was a **verification task**, not a
remediation task.

---

## 1. Acceptance criteria — verdicts

| # | Acceptance criterion | Result | Evidence |
|---|---|---|---|
| 1 | `npm run build:hosting` completes without error | ✅ PASS | exit 0; `BUILD_INFO` + `?v=<sha>` self-verified by the script |
| 2 | `hosting-dist/` structure matches the spec | ✅ PASS | tree audit below |
| 3 | Full navigation `/` → `/login/` → `/lobby/` → `/match/` → `/profile/` | ✅ PASS | 4/4 real browser clients, real button clicks, real navigation |
| 4 | Firebase Auth works (sign in, create account) | ✅ PASS | 4 accounts via the real create form against the emulator |
| 5 | Room create/join + match start against the emulator | ✅ PASS | room `GCWQVX`, match `N9yVwA3u8iKll65neKy5` |
| 6 | No console errors for module loading | ✅ PASS | 0 page errors, 0 failed resource loads, **0 × 404** |

Final smoke run: **21 passed, 0 failed** (`smoke-report.md`).

---

## 2. Criterion #2 — produced tree

`rm -rf hosting-dist dist && npm run build:hosting` produced exactly the
specified layout:

```
hosting-dist/
  index.html              meta-refresh + location.replace("login/index.html")
                          + <noscript> fallback — the firebase.json "/" rewrite
  login/index.html        login screen
  lobby/index.html        lobby screen
  match/index.html        game screen
  profile/index.html      profile screen
  engine/                 7 engine modules
  firebase-init.js        works from root (verified: reads config, no /design-ui/ refs)
  match-service.js match-adapter.js room-service.js session-service.js
  player-service.js presence-service.js shared-i18n.js analytics-service.js
  leaderboard-service.js inventory-service.js
  estemshan/              legacy src/ build, unchanged — index.html + assets/
```

- **Every** `<script src>` / `<link href>` in all 4 screens was audited: all
  paths are relative and resolve correctly from the hosting root. **No**
  absolute `/design-ui/...` or `/src/...` references anywhere in the JS.
- `estemshan/` asset refs are correct at `/estemshan/assets/...`.
- **All 42 files** in `hosting-dist/` return HTTP 200 (enumerated list +
  exhaustive `find`-based sweep; the two space-named images required `%20`).
- Root `index.html` carries `window.BUILD_INFO` (commit
  `3b62cd909bfb2fdb352a7318d09d6775574e6c25`) and `?v=3b62cd909bfb`
  cache-busting on local assets.

---

## 3. Criteria #3–#6 — how they were verified

A throwaway Playwright harness (since deleted) drove the **assembled
`hosting-dist/` artifact** — not the `design-ui/` source — over local HTTP,
against the real Firestore + Auth emulators, with 4 independent Chromium
contexts. To prove the artifact rather than the services, everything went
through the **real UI**:

- the real create-account form (`#createForm`), not a direct
  `createUserWithEmailAndPassword` call;
- the real `#createRoomBtn` / `#joinRoomBtn` / `#toggleReadyBtn` buttons, not
  direct `RoomService` calls — this matters because `handleMatchDiscovered()`,
  the only code that navigates to `/match/`, lives in the button click
  handler;
- the room id read out of the real `"Room created — ID: …"` alert;
- the join's `window.prompt()` answered exactly as a user would.

Every Firebase SDK module loaded from a local CDN fixture cache; the emulator
was pointed at by intercepting `firebase-init.js` and injecting
`useEmulator()`. **The source file was never modified.**

---

## 4. Bugs found in the test harness, not the artifact

Recorded so the same dead ends aren't re-explored. **None of these were
artifact defects**, and none required changing the build:

1. **The emulator injection was silently dropped.** `build-hosting.mjs`
   cache-busts local assets, so the request URL is
   `firebase-init.js?v=<sha>`. Playwright globs must match the *whole* URL
   including the query string, so `"**/firebase-init.js"` did **not** match.
   The injection never ran and the page talked to **production** Firestore.
   Caught by adding a response-URL tracer; fixed with `"**/firebase-init.js*"`.
   **The trailing `*` is load-bearing for anyone reproducing this.**
2. `/` returned 404 from the test server (resolved to a *directory*, failing
   `isFile()`) — the test server must mirror the firebase.json rewrite.
3. Calling `RoomService` from `/profile/` or `/match/` fails: those screens
   don't load `room-service.js`. All clients must be on `/lobby/` first.
4. Creating Auth users without `ensurePlayerProfile` leaves `players/{uid}`
   absent, so `updatePlayerProfile`'s `.update()` correctly denies the
   `currentRoomId` sync. Letting the real login form run fixes it.
5. `var seat` in a loop captured the last value into every listener — all
   errors were mislabeled `p4`. Fixed with an IIFE.
6. Two dialog handlers on one page throw `dialog.accept: already handled`.
   One handler per page, with a per-seat prompt-answer slot.
7. **Never pass `--base=/estemshan/` through Git Bash** — MSYS rewrites it to
   `/Program Files/Git/estemshan/` and bakes a 404 asset path into the build.
   Use PowerShell.

---

## 5. Genuine app-level findings (NOT T-002 defects)

Both surfaced only because the real UI was driven end to end. Neither is a
build/routing/auth defect, so neither blocks T-002. Both need their own task.

### 5a. Every signup emits a 409 ALREADY_EXISTS on `players/{uid}`

**Reproducible, deterministic, and production-affecting** — the same 409
appeared in four clients on every run, both against the emulator and against
production.

**Root cause (proven, not assumed):** `PlayerService.ensurePlayerProfile` is
called **twice concurrently for the same brand-new uid**:

- `design-ui/login/index.html:266` — the create form's own
  `await bootstrapProfile(cred.user)`, and
- `design-ui/login/index.html:181` — the `onAuthStateChanged` handler, which
  fires because `createUserWithEmailAndPassword` signs the user in
  immediately.

Both calls run `runTransaction`, both read `players/{uid}` as missing, so
both `tx.set()` attach an `exists:false` precondition. The first commit wins;
**the loser commits into `409 ALREADY_EXISTS`** — verified against the
emulator: `entity already exists: … path=/players/<uid>`.

This directly contradicts the contract documented at
`design-ui/player-service.js:76-80`, which claims the race is handled —
"one wins the create, the other observes the result and just touches
lastSeenAt like any other returning-user call." A get-then-set transaction
does **not** deliver that; the loser's precondition fails instead.

**Impact:** non-fatal by design (`login/index.html:165` catches it and
proceeds offline), so the user still reaches the lobby. But it logs a console
error on every signup and makes the loser's profile come back `null`.

**Candidate minimal fix (for a separate task):** `tx.set(ref, profile,
{ merge: true })` drops the `exists:false` precondition, so the loser's commit
becomes an idempotent upsert — matching the documented contract. Both calls
compute the same profile from the same user, so merge semantics are exactly
right.

### 5b. `dealRound()` fails after match start

One client logs `[MatchAdapter] dealRound() attempt failed (swallowed,
non-fatal): permission-denied` with `evaluation error at L1941:26 for
'create'` — i.e. the CEL expression **threw** rather than returning false.
`L1941` is `allow create: if isValidNewHand()`; the throw propagates from
inside it.

**Root cause NOT established — deliberately not over-claimed.** The deal path
*is* covered and passes in the green 51/51 suite
(`tests/repro-deal-denial.rules-emulator.test.cjs`,
`tests/hand-sync.rules-emulator-mvp-deal-authority.test.cjs`), and `dealRound`
itself uses `runTransaction` (`match-service.js:1958`), which is what
`isValidPairedDeal()`'s `getAfter()` requires. The leading suspect is an
**artifact of this harness's environment**, not a rules bug: the emulator was
configured for project `demo-test-ci` while the app's baked-in config targets
`made---estimation-card-game`, which the emulator flags as a single-project-mode
mismatch. That must be eliminated as a cause before this can be called a real
defect.

---

## 6. T-001 — dealer rotation

**Closed by the branch sync, verified against the emulator.**

- `git pull --ff-only origin main` fast-forwarded 13 commits, 0 conflicts.
  Upstream commit `b1cdc64` performed the required rename to
  `tests/dealer-rotation-rules.rules-emulator.test.cjs` — option 1 of T-001's
  own fix list, no logic change.
- Ran against the live emulator: **18/18 checks PASS, exit 0** — 4 canonical
  4-seat transitions including p4→p1 wraparound, 2/3/1-seat shapes, 4
  negative transitions (skip/repeat/backward/non-roster), 5 creation shapes.
- **No genuine rules bug.** `firestore.rules` and `firestore.rules.sha256`
  were correctly left untouched; the pinned SHA still matches.

---

## 7. Regression

| Check | Baseline (Step 0) | After all work |
|---|---|---|
| `npm run test:ci` | 51/51 files, exit 0 | **51/51 files, exit 0** |
| `firestore.rules` SHA | `9d8662e3…b516` | `9d8662e3…b516` (unchanged) |
| Legacy `src/` dev server | — | root 200, `/src/main.tsx` 200 |
| `hosting-dist/` 404s | — | 0 |

**No regressions.**

---

## 8. Files changed

| Path | Change |
|---|---|
| `docs/reviews/Design_Ui_Root_Build_Evidence/` | **new** — this report, `smoke-report.md`, 8 screenshots |
| `hosting-dist/`, `dist/` | regenerated build outputs (gitignored) |
| `firestore-debug.log` | emulator runtime log (gitignored) |
| *everything else* | **untouched** |

Not modified: `scripts/build-hosting.mjs`, `firebase.json`, `package.json`,
`firestore.rules`, `firestore.rules.sha256`, and **all** `design-ui/` sources.
The throwaway harness (`scripts/_t002-*.cjs`) was deleted; it was
underscore-prefixed specifically to keep it out of the tree.

The pre-existing in-flight work is intact and untouched:
`design-ui/match/index.html`, `scripts/golden-path.mjs`,
`design-ui/match/table-render.js`, `docs/reviews/Table_Render_Layer_Evidence/`.

---

## 9. Action required: production data written by the harness

**This needs a decision from you.**

Because of harness bug #1 above (the dropped emulator injection), the smoke
runs executed against the **live production** project
`made---estimation-card-game` for several iterations before the fix. Real
data was written there:

- Auth users matching `t002-p*-<timestamp>*@test.local` and
  `probe-<timestamp>@test.local`;
- matching `players/{uid}` documents;
- at least one `rooms/{id}` (and possibly a `matches/{id}`).

All subsequent runs were correctly emulator-only. **The leftover production
data has not been removed** — deleting it needs admin credentials or console
access that this session doesn't have, and I won't touch a live database
without your say-so. Please confirm how you'd like it cleaned up.

---

## 10. Conclusion

T-002 required **no code changes**. The build pipeline, routing, entry point,
and asset paths were already correct — they had simply never been exercised
end to end. That exercise is now done, with evidence, against the real
emulator, through the real UI.
