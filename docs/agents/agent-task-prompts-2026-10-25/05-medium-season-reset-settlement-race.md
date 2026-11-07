# Medium task 2 — E5.3: the reset/settlement race keeps one total order

You are one of six agents working in parallel on this repository. You have your own worktree and a
file-disjoint write scope. Do not touch any file outside it.

**Task class:** MEDIUM. This is **test-first**: the deliverable is a deterministic race harness and
the evidence it produces. A production change is allowed only if one of your tests demonstrates a
defect inside the two production files you own — and then only the minimal fix. If the defect is in
`SettlementDb.kt`, it is reported, never fixed here.

## 1. Read first, in this order

1. `AGENTS.md`.
2. `docs/engineering/NODEJS_SAFE_USAGE.md`.
3. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — only: **Q22**, the **T-19** row "Reset race"
   (`One total order; no RP lost or double-reset`), **F-15**, and the **E5.3** estimate row.
4. `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` — §5.10 row **E5.3** and the **S59** reset notes.
5. These files, fully:
   - `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetRun.kt`
   - `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetDb.kt`
   - `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetJob.kt` (read-only)
   - `native/functions/src/jsMain/kotlin/com/estemshan/functions/SettlementDb.kt` (read-only; only
     the season/lazy-reset and transaction paths)
   - `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetRunTest.kt` (the existing
     fake and fixtures; read it, do not rewrite it)

`docs/**` is read-only. `native/app/**` is out of scope. `firestore.rules` is frozen.

## 2. Worktree

```powershell
git -C D:\demo-test worktree add D:\worktrees\agent-season-race -b agent/season-race 8b13b12
```

If the branch exists, use `agent/season-race-2`. Never `--force`. Never work in `D:\demo-test`.

## 3. What exists today (verified at `8b13b12`)

- `runSeasonReset` runs four phases: gating read, claim transaction (re-read), plan + batch write,
  finalise transaction. The claim and finalise are transacted; the profile migration is **not**.
- `SeasonResetDb.writeProfiles` sends one batch per call. `AdminSeasonResetDb` builds **every**
  write before committing and conditions each on `lastUpdateTimePrecondition(updateTime)` read with
  the profile. A stale version fails the whole batch; no partial commit inside one call.
- `SeasonResetRun` chunks at `MAX_PROFILE_WRITES_PER_BATCH = 400`, counts `applied` only for
  committed chunks, and on a batch failure returns `PARTIAL` with the season left `RUNNING`. The next
  run is a `RESUME`: `collectPlan(resuming = true)` **skips** profiles already at the target.
- A replay after a successful finalise sees `seasonId = to`, status `ACTIVE`, no `nextSeasonId` →
  `NOT_CONFIGURED` with zero writes. That is the existing idempotence answer (see
  `aReplayAfterTheResetIsANoOpAndNeverDemotesTwice`).
- The finalise transaction writes `seasonId = to` **last**. Between the profile batch and the
  finalise, `seasons/current` still names `from` while migrated profiles already say `to`.
- A settlement reads `seasons/current` **inside its own transaction** and lazily resets a stale seat
  with `alignSeatedProfilesToSeason`, which calls `planSeasonReset(recordedSeason, currentSeason, …)`.
  `planSeasonReset` only checks that each profile is in `fromSeasonId`; it has **no forward-only
  ordering check**. So a reader that sees `currentSeason = from` and a profile at `to` would plan a
  reset `to → from` — backwards.
- The existing `FakeDb` records writes but **does not enforce** `expectedUpdateTime`. It cannot prove
  a lost-update property. Your harness must enforce versions, not just record them.

## 4. Required deliverable: a deterministic race harness

Create `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetRaceTest.kt` (kotlin.test
+ `kotlinx.coroutines.test.runTest`, matching `SeasonResetRunTest`). No `Thread.sleep`, no `delay`, no
real threads: use `CompletableDeferred` gates or suspend hooks so the interleaving is exact and the
suite is fast.

### 4.1 The shared, versioned store

Build a small in-memory store that both actors use:

- `season: MutableMap<String, Any?>?` with a version, and
  `profiles: MutableMap<String, StoredProfile>` where `StoredProfile(fields, version)`.
- `ResetPort(store) : SeasonResetDb`:
  - `transact` runs the block against a `Tx` that reads the current season and applies
    `writeSeason` as a merge + version bump. Transactions are serialized by the test's single
    coroutine, so no lock is needed; say so in a comment.
  - `readAllProfiles()` returns each profile's **current** fields and version.
  - `writeProfiles(updates)` validates **every** `expectedUpdateTime` against the current version
    **before applying any write**, then applies all writes and bumps versions. A missing or stale
    version throws and applies nothing. This is the batch atomicity the real port relies on.
- `ProgressionWriter(store)`: models the settlement's profile write, not its math. `settle(uid,
  rpDelta)` reads the season and the profile, applies the delta to `rp` and the five stored counters
  deterministically, and writes with the read version. A stale version throws (the settlement
  transaction would retry). Do **not** import, fake or reimplement `SettlementDb`/`Settlement`; the
  property under test is the reset's ordering, not the settlement's arithmetic.

Use production-shaped profile fields (`tier`, `division`, `rp`, `seasonId`, `highestRankTier`,
`highestRankDivision`, `placementState`, `demotionShield`) so the real parser and the real
`applySeasonReset` run. Do not hand-compute the expected RP: call `applySeasonReset(profile, to)` in
the assertion.

### 4.2 The cases the harness must pin

Name the tests for the property, not the implementation.

1. **A progression write lands between the plan read and the batch.**
   - Reset reads the profile at version `v1`, plans.
   - Before `writeProfiles` runs, `ProgressionWriter.settle(uid, +30)` writes at `v2`.
   - The reset batch must fail its `v1` precondition and apply **zero** writes.
   - Report: `PARTIAL`, `applied == 0`, season still `RUNNING` toward `to`.
   - Run the reset again: `RESUME`, applies the reset **once** on the settled profile.
   - Assert final `rp == applySeasonReset(settledProfile, to).rp`, and assert it differs from
     `applySeasonReset(originalProfile, to).rp` — the settlement's +30 was not lost.

2. **A settlement that read before the reset and writes after is rejected and retried.**
   - `ProgressionWriter` reads the profile at `v1` and pauses.
   - The reset runs to completion (batch at `v2`, finalise to `to`).
   - The writer resumes with `v1`: its write must throw. Retry: re-read `v2`, settle on the reset
     profile, write at `v3`.
   - Assert the final profile reflects exactly one reset and one settlement, in that order, and that
     the reset's tier/season were not overwritten by the stale retry.

3. **Two resets race across a partial migration.**
   - Seed **401** profiles so the reset has two chunks (400 + 1).
   - Run reset **A**; gate it after its first chunk commits.
   - Run reset **B** to completion. Assert `B.decision == "RESUME"`, `B.skippedAlreadyTarget == 400`,
     `B.applied == 1`, and that B wrote **no** season claim (it must not rewrite A's claim).
   - Release A. Its second chunk is now stale: assert `A.decision == "PARTIAL"`,
     `A.applied == 400`, and that A did not finalise.
   - Run reset **C**. Assert it writes nothing (`NOT_CONFIGURED`, zero season writes, zero profile
     writes) and reads no profiles.
   - Assert every one of the 401 profiles is at `to` with `rp == applySeasonReset(original, to).rp`
     — one demotion each, no double reset.

4. **The finalise window is visible.**
   - Gate the reset after `writeProfiles` returns and before the finalise transaction.
   - Assert the season document still reads `seasonId == from` while the migrated profile already
     reads `to`.
   - This is a **characterization test of the current contract**, not a desired end state. Its KDoc
     must state the consequence: a lazy reader in this window would plan `to → from` backwards.
   - Do **not** assert the backwards reset as correct, and do **not** fix the reader here — it lives
     in `SettlementDb.kt`, which is out of scope (see §7).

5. **A replay is a no-op on the shared store.** After case 1 or 3 finalises, a further run is
   `NOT_CONFIGURED`, with zero profile writes and zero `readAllProfiles` calls.

6. **Every write carries the exact read version.** Assert the `expectedUpdateTime` passed for each
   profile equals the version `readAllProfiles` returned, and that a deliberately missing version
   makes `writeProfiles` throw with zero writes.

You may add cases, but these six properties must be present and named.

### 4.3 Production changes — conditional, minimal

- If a test fails because of a defect in `SeasonResetRun.kt` or `SeasonResetDb.kt`, fix **only** that
  defect, keep the change minimal, and show both runs in the report: the failing run and the passing
  run after the fix.
- If a test fails because of a defect in `SettlementDb.kt`, `Settlement.kt`, `SeasonResetJob.kt`,
  `LeaderboardDb.kt` or `RankedProfileDb.kt`, do **not** edit those files. Keep the test that
  demonstrates the defect (mark it clearly as a characterization/defect test if it must pass), and
  report the file, symbol and proposed guard in `HANDOFF NOTES`.
- If no test fails, change no production file. A green, test-only diff is the expected outcome.

## 5. Write scope

**Allowed to modify**

- `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetRun.kt`
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetDb.kt`
- **New** `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetRaceTest.kt`
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetRunTest.kt` — **only** if a
  production change makes an existing assertion stale. Justify it in `SCOPE DEVIATIONS`; do not
  delete or weaken an existing test.

**Forbidden**

- `SettlementDb.kt`, `Settlement.kt`, `SettlementTest.kt`, `RpApplicationTest.kt` — Agent 2 owns the
  first two and the stats path; the lazy-reset reader is a reported defect here.
- `SeasonResetJob.kt` — the pure claim matrix and `planSeasonReset` are frozen for this task.
- `LeaderboardDb.kt`, `RankedProfileDb.kt` — `currentSeasonIdOf` deliberately serves the outgoing
  season during `RUNNING`; changing it would flip the live board mid-reset.
- `native/app/**`, `native/engine/**`, `native/services/**`, `native/functions/index.js`,
  `native/functions/src/jsMain/kotlin/SeasonResetExports.kt`, `SeasonResetGate.kt`.
- Any `*.gradle.kts`, `firestore.rules`, `firebase.json`, `.github/**`, `docs/**`.
- Any file that already differs from `8b13b12` in your worktree. If one does, stop and report it.

## 6. Acceptance criteria

Mark each `PASS` / `FAIL` / `NOT RUN` with the test that proves it.

1. The new race file compiles and uses no wall-clock sleep, `delay`, or real thread; the interleaving
   is driven by an explicit gate or suspend hook.
2. Case 1: a progression write between the plan read and the batch makes the batch fail atomically
   (`applied == 0`, season `RUNNING`), and the resume applies exactly one reset on the settled RP.
3. Case 2: a writer holding a pre-reset version is rejected, retries onto the reset profile, and the
   final state contains exactly one reset and one settlement.
4. Case 3: with 401 profiles, B reports `RESUME` / `skipped 400` / `applied 1`, writes no claim; A
   reports `PARTIAL` / `applied 400`; C writes nothing; every profile is demoted exactly once.
5. Case 4: the finalise window is pinned (`seasonId == from`, profile `seasonId == to`), and the
   KDoc names the backwards-reset consequence without asserting it as correct.
6. Case 5: a replay after finalise is `NOT_CONFIGURED` with zero profile writes and zero profile reads.
7. Case 6: every profile write carries the exact version read, and a missing version fails the whole
   batch with zero writes.
8. `lastUpdateTimePrecondition` is still used by `AdminSeasonResetDb.writeProfiles`; if
   `SeasonResetDb.kt` is unchanged, say so explicitly, and if it changed, quote the hunk.
9. No production file outside the two allowed ones changed.
10. `:functions:jsNodeTest` is green, or `NOT RUN` with the exact runtime error and every JS test
    marked `NOT RUN`. A compile-only run is not a test pass.

## 7. Deliberately not fixed (report these)

- **The lazy-reset reader can move a profile backwards during the finalise window.**
  `alignSeatedProfilesToSeason` calls `planSeasonReset(recordedSeason, currentSeason, …)` with no
  forward-only guard, and `planSeasonReset` accepts any `from != to`. A settlement that commits
  between the reset's batch and its finalise would see `currentSeason = from` and a profile at `to`
  and plan `to → from`. Report the exact symbols and a proposed guard (for example: refuse when the
  season document is `RUNNING` and the profile is already at `targetSeasonId`, or use
  `targetSeasonId` as the effective season while `RUNNING`). This is **SettlementDb** work, not this
  task.
- **The bulk sweep is not transactional across chunks.** A failure after a committed chunk leaves
  the season `RUNNING` and needs the next run. That is the designed R27 behaviour; do not turn it
  into a rollback.
- **`MAX_RESET_PROFILES = 10_000`** still refuses rather than paginates. Out of scope.
- **The real Firestore retry semantics** are modelled, not executed. The test store enforces the
  same version contract; the Admin SDK's own retry is covered by `SeasonResetDryRunEmulatorTest`,
  which self-skips without an emulator. Report that distinction.

## 8. Commands

```powershell
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest
```

Run only this. Do not run `npm`, `npx`, `npm ci`, or `node`. Do not run the full CI command.

If `:functions:jsNodeTest` cannot run because no approved Node 22 runtime is present, report it
`NOT RUN` with the exact error. Do not install Node. A compile-only run proves compilation, never
test behaviour.

## 9. Report

End with exactly these headings, in this order, each on its own line:

```
STATUS: DONE | PARTIAL | BLOCKED
CHANGED FILES: <one path per line, each with +added/-removed>
CHANGE SUMMARY: <per file: what changed and why>
ACCEPTANCE CRITERIA: <every numbered criterion above, marked PASS / FAIL / NOT RUN, with the
                      test name or command that proves it>
COMMANDS RUN: <exact command -> result, including test counts>
NOT RUN: <exact command -> why>
KNOWN RISKS / ASSUMPTIONS: <including anything you could not verify>
SCOPE DEVIATIONS: <none, or file + justification>
HANDOFF NOTES: <what a reviewer or the next agent must know>
```

`DONE` requires every criterion `PASS`. If any is `FAIL` or `NOT RUN`, the status is `PARTIAL`
(or `BLOCKED` when you could not compile). Never round up.

In `HANDOFF NOTES`, state:

- which of the six properties are proven, and which are only modelled (and why that is enough or not);
- the exact finalise-window hazard, with the file/symbol that must guard it, and whether you added a
  characterization test for it;
- whether any production file changed; if none did, say "test-only diff";
- the version/precondition contract your fake enforces, and the one it cannot (real Firestore retry);
- every command you did not run and why.
