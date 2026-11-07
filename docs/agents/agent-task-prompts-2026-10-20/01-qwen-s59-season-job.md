# Prompt 1 — Qwen 3.8 Flash — S59 season reset job

Copy everything below this line into the agent.

---

You are Qwen 3.8 Flash. Implement story **S59** from `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` in an isolated git worktree.

## Workspace

- Worktree: `C:\Users\ehab.sayed\AppData\Local\Temp\opencode\estemshan-agents\s59-season-job`
- Branch: `agent/v1-s59-season-job`
- Baseline: `397902868e5bb8b3ad8247eb4ff1dd200efb6944` (`origin/main`)
- Project: native Kotlin Multiplatform card game; `native/functions` is Kotlin/JS on Node 22, `native/services` is Android/JVM, `native/engine` is the shared pure engine.

`cd` to that worktree. Do **not** write in `C:\Users\ehab.sayed\Desktop\demo-test`; that checkout contains the user's uncommitted work.

## Read first

1. `AGENTS.md`.
2. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — the S59 row in the E6b table, R27, RD24, and the season/leaderboard notes.
3. `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetJob.kt` — the already-merged pure claim/plan core. Its semantics are frozen; extend around it.
4. `native/functions/src/jsMain/kotlin/com/estemshan/functions/FirebaseAdmin.kt` and `Firebase.kt` — the minimal Admin/callable interop.
5. `native/functions/index.js` — the export manifest and its discovery guard.
6. `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetJobTest.kt`, `SeasonLeaderboardEmulatorTest.kt`, `SettlementRpEmulatorTest.kt`.
7. `.github/workflows/android.yml` — the `functions` job and its `_EMULATOR_SKIPPED` fail-closed scan.

## Hard rules

- Work only in the worktree above. Do not commit, push, merge, rebase, reset, stash, clean, or checkout over changes.
- Do not edit `firestore.rules`, `firebase.json`, `.github/**`, or `docs/**`.
- Do not install Node or npm packages. Do not run `npm`, `npx`, or `npm ci`. Use `native\gradlew.bat` only; the Kotlin/JS plugin manages its own Node toolchain.
- Do not add dependencies or bump versions.
- Do not change `SeasonResetJob.kt`'s existing decision matrix or reset math. If you believe it is wrong, stop and report instead of editing it.
- Do not edit `LeaderboardDb.kt`, `RankedProfileDb.kt`, `PartyGate.kt`, `RoomService.kt`, or any `:services` file. Those belong to other parallel tasks.
- Leave all changes uncommitted. The reviewer will inspect the diff.

## Write scope

Production files you may create or edit:

- `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetJob.kt` (add only; do not alter existing declarations)
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetDb.kt` (new)
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeasonResetRun.kt` (new)
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/FirebaseAdmin.kt` (add batch + `Timestamp.fromMillis` interop only)
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/FirebaseScheduler.kt` (new; `firebase-functions/v2/scheduler` interop)
- `native/functions/src/jsMain/kotlin/SeasonResetExports.kt` (new; **no package declaration**, like `Functions.kt`)
- `native/functions/index.js` (one new export + accept `__endpoint` in the guard)

Tests you may create or edit:

- `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetRunTest.kt` (new)
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetDryRunEmulatorTest.kt` (new)
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonResetJobTest.kt` (additive only; existing expectations stay)

Anything else is out of scope.

## The shared season document contract

The document is `seasons/current` in the default Firestore database. Its fields are:

```text
seasonId:       String   // the live season, e.g. "s1"
status:         String   // "ACTIVE" | "RUNNING" | "COMPLETE"
targetSeasonId: String?  // set only while RUNNING
nextSeasonId:   String?  // the operator-configured next season
startsAt:       Timestamp
endsAt:         Timestamp
updatedAt:      Timestamp
```

Rules that every producer/consumer must honour:

- A reader that finds no document bootstraps to **`s1`**. That bootstrap belongs to the readers (another task); this job never creates `seasons/current` as a side effect.
- `status` is the one-way lifecycle: `ACTIVE` → `RUNNING` → (new season live). `COMPLETE` is terminal and means no further season will run. **On a successful reset this job writes `status = "ACTIVE"` with `seasonId = targetSeasonId`, so the 3-month cycle can repeat.** Do not write `COMPLETE` from the job; that would make a second reset impossible under the frozen decision matrix.
- Season IDs are lowercase (`s1`, `s2`). Do not reuse the uppercase `"S1"` strings from the pure unit tests in new production/test fixtures.
- The job derives `from` and `to` from the document (`seasonId`, `nextSeasonId`), never from a client request.

## Deliverables

### 1. `SeasonResetDb.kt` — the Admin port

Define a port with:

```kotlin
data class SeasonProfile(val playerId: String, val fields: Map<String, Any?>)

interface SeasonResetDb {
  suspend fun <T> transact(block: suspend (Tx) -> T): T
  interface Tx {
    suspend fun readSeason(): Map<String, Any?>?
    fun writeSeason(fields: Map<String, Any?>)
  }
  suspend fun readAllProfiles(): List<SeasonProfile>
  /** Writes exactly this list as ONE Admin batch. The caller chunks. */
  suspend fun writeProfiles(updates: List<Pair<String, Map<String, Any?>>>)
}
```

- `AdminSeasonResetDb`:
  - Call `ensureDefaultAdminApp()` in `init`, then `getFirestore()`.
  - `transact` bridges the Admin callback with `promise { block(AdminTx(tx)) }` and awaits it. The SDK may invoke the callback more than once on contention, so the block must be idempotent, must perform every read before its first write, and must not carry mutable state across invocations.
  - `readSeason()` reads `seasons/current` inside the transaction and converts with `dynamicToKotlin`; profile reads do the same. Writes pass through `toPlain`.
  - `readAllProfiles()` runs **one** `players` collection query with `.limit(MAX_RESET_PROFILES + 1)` where `MAX_RESET_PROFILES = 10_000`. Do not filter by season: the job must be able to see profiles left on a foreign season. Over the cap, throw `HttpsError("resource-exhausted", ...)` and write nothing.
  - `writeProfiles()` writes exactly the list it is given as **one** Admin batch: one `db.batch()`, one `update` per entry, one `commit()`. It does not chunk; the orchestrator does. Each write is a field-level update on `players/{uid}`. Never `set()` a whole document.
- Add to `FirebaseAdmin.kt` only:
  - `FirestoreDb.batch(): WriteBatch`
  - `WriteBatch.update(ref: DocRef, data: Any?)`
  - `WriteBatch.commit(): Promise<Any?>`
  - `Timestamp.fromMillis(milliseconds: Double): Any`
- Add `FirebaseScheduler.kt` with `onSchedule(schedule: Any?, handler: (Any?) -> Any?): dynamic` from `firebase-functions/v2/scheduler`. Keep it minimal and typed.

### 2. `SeasonResetRun.kt` — the orchestrator

Expose:

```kotlin
enum class SeasonResetMode { DRY_RUN, APPLY }

data class SeasonResetReport(
  val mode: SeasonResetMode,
  val decision: String,          // CLAIMED, RESUME, WOULD_CLAIM, WOULD_RESUME, PARTIAL,
                                 // ALREADY_COMPLETE, IN_PROGRESS_OTHER_SEASON, REJECTED,
                                 // NOT_CONFIGURED, NOT_DUE
  val fromSeasonId: String?,
  val toSeasonId: String?,
  val planned: Int,
  val applied: Int,
  val skippedAlreadyTarget: Int,
  val failures: List<SeasonResetFailure>,
  val message: String,
)

suspend fun runSeasonReset(
  db: SeasonResetDb,
  nowMillis: Long,
  mode: SeasonResetMode,
): SeasonResetReport
```

Behaviour, in order:

1. Read `seasons/current` in a transaction.
   - Absent → `NOT_CONFIGURED`, no writes.
   - Present but `seasonId` blank/non-string, or `status` not one of the three names → `NOT_CONFIGURED` or `REJECTED`; no writes.
   - `status == COMPLETE` → `ALREADY_COMPLETE`, no writes. Completion is terminal and `nextSeasonId` is not required; a replayed scheduler run must be a no-op, not a configuration error.
   - `status != COMPLETE` and `nextSeasonId` blank/missing → `NOT_CONFIGURED` or `REJECTED`; no writes. Never invent a target.
2. Due check: `endsAt` must be a real timestamp (use `timestampToMillis`) and `<= nowMillis`. Missing/future `endsAt` → `NOT_DUE`, no writes. `startsAt` is not a gate.
3. **DRY_RUN**: no transaction write of any kind, no claim, no profile write. Compute the same `decideSeasonResetClaim` read-only. A terminal decision (`ALREADY_COMPLETE`, `IN_PROGRESS_OTHER_SEASON`, `REJECTED`) returns under its own name without reading profiles. `CLAIMED` reports `WOULD_CLAIM` and `RESUME` reports `WOULD_RESUME`; for those two, read and plan exactly as the apply path would, and return the report (`planned`, `failures`, `skippedAlreadyTarget`). This is the R27 dry run.
4. **APPLY claim**: in one transaction, **re-read** `seasons/current` (do not reuse the step-1 snapshot), re-check `endsAt <= nowMillis` on the fresh document (a moved deadline → `NOT_DUE`, zero writes), and call the existing `decideSeasonResetClaim(currentSeasonId, currentStatus, runningTargetSeasonId, requestedFrom = seasonId, requestedTo = nextSeasonId)` on the fresh values.
   - `CLAIMED`: write `status = "RUNNING"`, `targetSeasonId = nextSeasonId`, `updatedAt = serverTimestamp()`.
   - `RESUME`: proceed without rewriting the claim (or rewrite the same values idempotently).
   - `ALREADY_COMPLETE`, `IN_PROGRESS_OTHER_SEASON`, `REJECTED`: return the matching decision with zero writes.
   - If the fresh `nextSeasonId` is not the same string as the `to` this run planned against, return `REJECTED` with zero writes: the operator changed the target between the read and the claim, and a stale target must not be claimed.
5. Read all profiles once. Classify each:
   - `seasonId == from` → parse and plan.
   - `seasonId == to` → on `RESUME`, count in `skippedAlreadyTarget` and skip. On `CLAIMED`, it is a failure: a fresh transition must not find profiles already in the target.
   - absent `seasonId` → treat as legacy `"s1"` only when `from == "s1"`; otherwise failure.
   - any other season, missing `playerId`, or malformed profile → failure.
6. Parse each planned profile into the engine's `RankedProfile` using the same defaults `RankedProfileDb.kt` uses, but implemented locally in this task (do not edit that file):
   - `rp` absent → 0; present must be a whole, finite, non-negative number within `Int`. An absent `rp` beside a tier/division above Bronze III is a failure, exactly as `RankedProfileDb.normalizeProfile` treats it (only the bottom rung is representable by the absent-RP default).
   - `tier` + `division` both absent → `rankAt(rp)`.
   - both present → must name an exact `rankLadder` rung.
   - exactly one present → failure.
   - `seasonId` absent → `"s1"`.
   - `highestRankTier`/`highestRankDivision` both absent → current; exactly one present → failure; a stored highest below current raises to current; invalid rung → failure.
   - `placementState` absent → `NOT_STARTED` when `rp == 0`, else `SKIPPED`; present must be a valid enum name.
   - `demotionShield` absent → `false`; present must be Boolean.
   - Run `progressionMismatch(profile)`; a mismatch is a failure. Do not repair it.
7. Call the existing `planSeasonReset(from, to, fromProfiles)`. Do not reimplement the demotion math.
   - If `plan.failures` is non-empty, or your classification produced any failure:
     - `DRY_RUN`: return the failures; write nothing.
     - `APPLY` on a fresh `CLAIMED` transition: **before writing any profile**, transactionally revert the season document to `status = "ACTIVE"`, `targetSeasonId = null`, `updatedAt = serverTimestamp()` (only if it still says `from`/`RUNNING`/`to`), then return the failures. A fresh claim that cannot plan must not stay claimed.
     - `APPLY` on a `RESUME`: do **not** revert and do **not** clear the target. Earlier runs may already have moved some profiles, so un-claiming would make a mixed state look live and a fresh claim would then fail on the already-moved profiles. Leave `RUNNING`/`from`/`to`, return a `PARTIAL` report with the failures, and reserve the fix for an operator. This is the R27 irreversible direction.
8. Apply every `PlannedReset` in chunks of at most **400**, calling `writeProfiles` once per chunk and incrementing `applied` only after that chunk's commit succeeds. Each write merges exactly these six fields into `players/{uid}`:
   `tier`, `division`, `rp`, `seasonId`, `highestRankTier`, `highestRankDivision`.
   Never write `placementState`, `demotionShield`, the nine career statistics, display fields, or the legacy `rank` string. The plan's `RankedProfile` carries placement/shield/highest; read them but only the six fields above go to Firestore.
   - If a batch fails after some batches committed, leave `status = "RUNNING"` and return a `PARTIAL` report with the applied count and the transport error. Do **not** revert; the next run resumes and finishes. This is the R27 safe direction.
9. Finalise in one transaction: re-read `seasons/current`; require `seasonId == from`, `status == RUNNING`, `targetSeasonId == to`. Then write:
   `seasonId = to`, `status = "ACTIVE"`, `targetSeasonId = null`, `nextSeasonId = null`,
   `startsAt = serverTimestamp()`, `endsAt = Timestamp.fromMillis(nowMillis + 90 days)`,
   `updatedAt = serverTimestamp()`.
   If the precondition fails, do not guess and do not write; return a failure report.
10. `runSeasonReset` returns a report for every domain outcome. It may throw only for an SDK/transport failure that prevents it from knowing the state (and then it must not claim a write happened).

### 3. Scheduled export

- New `native/functions/src/jsMain/kotlin/SeasonResetExports.kt` with **no package declaration**.
- `@JsExport @JsName("scheduledSeasonReset") val scheduledSeasonReset = onSchedule(js("({ schedule: '0 3 * * *', timeZone: 'UTC' })"), handler)`.
- The handler bridges the suspend call with a module `CoroutineScope(SupervisorJob() + Dispatchers.Default).promise { ... }`, exactly as `LeaderboardExports.kt` does. It runs `runSeasonReset(AdminSeasonResetDb(), Date.now().toLong(), SeasonResetMode.APPLY)`, logs one structured summary line (decision, from, to, planned, applied, skipped, failure count), and resolves with `toPlain(report)`.
- It must never throw for a domain no-op; the scheduler should not retry a `NOT_DUE` or an `IN_PROGRESS_OTHER_SEASON` forever. A genuine transport failure may propagate so the scheduler retries, but it must not be converted into a fake success report.
- `index.js`: add `scheduledSeasonReset: kotlin.scheduledSeasonReset` to the manifest. Change the guard to accept a member that has **either** `__trigger` (callable) **or** `__endpoint` (scheduled), with an error message that says "not a Firebase function" rather than "not a callable".

## Tests

### `SeasonResetRunTest.kt` (fake port, no emulator)

Pin at least:

- absent document → `NOT_CONFIGURED`, zero transaction writes;
- malformed document (no `seasonId`, bad `status`) → no writes; a non-`COMPLETE` document with no `nextSeasonId` → `NOT_CONFIGURED`, no writes; a `COMPLETE` document with no `nextSeasonId` → `ALREADY_COMPLETE`, no writes;
- `endsAt` in the future / absent → `NOT_DUE`;
- a deadline moved after the first read but before the claim transaction → `NOT_DUE` with zero claim writes (the fresh read wins);
- a `nextSeasonId` changed after the first read but before the claim transaction → `REJECTED` with zero writes (never claim the stale target);
- `ACTIVE` + matching from → `CLAIMED`; `RUNNING` + same target → `RESUME`; `COMPLETE` (with or without `nextSeasonId`) → `ALREADY_COMPLETE`; a different in-flight target → `IN_PROGRESS_OTHER_SEASON`; stale from → `REJECTED`;
- dry run plans but writes nothing (assert both the season write count and profile write count are 0), reporting `WOULD_CLAIM` on an `ACTIVE` season and `WOULD_RESUME` on the matching `RUNNING` state;
- plan failure on a fresh claim reverts `RUNNING` → `ACTIVE`, clears `targetSeasonId`, and writes no profiles;
- plan failure on a `RESUME` leaves `RUNNING`/`from`/`to` intact, writes nothing, and reports the failures for an operator;
- a failure after the first batch leaves `RUNNING` and reports `PARTIAL` (simulate a throwing batch);
- legacy profile with no `seasonId` is included only when from == `s1`;
- foreign season on a fresh claim is a failure; on resume a profile already at `to` is skipped and never demoted twice;
- the merged field set is exactly the six fields; stats, placement, shield and display fields are absent from every write;
- 401 and 501 profiles exercise the 400-write chunking (assert the fake's `writeProfiles` call count and chunk sizes: 400+1 and 400+101, and the reported `applied`);
- final document is `ACTIVE`, `seasonId = to`, `targetSeasonId = null`, `nextSeasonId = null`, and `endsAt` is 90 days after `now`;
- a replay after a completed reset is a no-op (`NOT_CONFIGURED` because `nextSeasonId` is cleared) and does not re-demote anyone.

### `SeasonResetDryRunEmulatorTest.kt` (real Firestore emulator, self-skipping)

Follow the existing emulator-test pattern exactly:

- `FIRESTORE_EMULATOR_HOST` absent → `console.log("S59_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")` and return.
- Nonce-tag every season ID and player ID (`s59e-<nonce>-...`) because the emulator DB is shared across runs.
- Seed with the Admin SDK, using a transaction `set` or the new batch API.
- Test 1 — **production-shaped dry run**: seed `seasons/current` with `seasonId = s59e-<nonce>-a`, `nextSeasonId = s59e-<nonce>-b`, `status = ACTIVE`, `endsAt` in the past, plus exactly 250 profiles total — across all 19 rungs, mixed placement/shield/highest/stats, including at least 5 legacy-shaped profiles that carry `seasonId = from` but no `tier`/`division`/`rp`/`placementState`/`demotionShield`/highest fields (so the absent-field defaults are exercised; a profile with no `seasonId` at all is covered in the unit suite's `from == "s1"` case, not here). Run `DRY_RUN`. Assert `planned == 250`, `applied == 0`, `failures` empty, and every profile document plus `seasons/current` is byte-for-byte unchanged.
- Test 2 — **apply across the batch boundary**: seed 501 profiles (all `from`, plus one at `to` only if you are testing resume; for the fresh apply, keep them all `from`). Run `APPLY`. Assert all 501 moved exactly two rungs (floor at Bronze III), RP equals the new rung's lower bound, King → Royal I, `seasonId = to` on every profile, the nine stats and placement/shield/highest survived, and `seasons/current` is `ACTIVE`/`to` with `targetSeasonId == null`.
- Test 3 — **resume**: run `APPLY`, then simulate a crash by transactionally resetting three profiles to `from` (pre-reset rungs) and the season document to `RUNNING`/`from`/`to`, restoring `nextSeasonId = to` as well (the successful run cleared it). Run `APPLY` again. Assert only those three move, the other 498 do not demote twice, `skippedAlreadyTarget == 498`, and the final document is correct.
- The class must print `S59_EMULATOR_SKIPPED` on skip so CI's `_EMULATOR_SKIPPED` scan fails closed if the emulator is unreachable.

## Acceptance criteria

- `native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace` passes.
- `index.js` loads the new scheduled export, and the guard accepts `__endpoint`.
- No `firestore.rules`, `firebase.json`, dependency, or version change.
- Every profile write is a merge of the six progression fields only.
- A plan failure never leaves the season claimed; a mid-apply failure leaves it resumable.
- The dry run writes nothing, including the claim.
- All emulator-only tests are reported as **skipped locally** unless you actually ran an emulator. Do not claim they passed.

## Verification

Run from the worktree root:

```powershell
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace
```

If that fails for a reason you can fix inside the allowed files, fix it and rerun. If it fails outside your scope, stop.

Optional, only if an emulator is already running and `FIRESTORE_EMULATOR_HOST` is set: rerun `:functions:jsNodeTest` and report which emulator tests executed. Do not install firebase-tools.

## Report back

Use exactly this structure:

```text
STATUS: COMPLETE | PARTIAL | BLOCKED
Branch: agent/v1-s59-season-job
HEAD: <sha> (uncommitted changes expected)

Files added:
Files changed:

Deliverables:
1. SeasonResetDb:
2. SeasonResetRun:
3. Scheduled export + index.js:
4. Tests:

Commands run (exact) and result:
- <command> -> PASS/FAIL, test count

Emulator-gated tests:
- <class> -> EXECUTED / SKIPPED (marker) / NOT RUN

Deviations from this brief:
Known risks / follow-ups:
```

If you are blocked, say exactly which file and which invariant blocks you. Do not silently widen the scope.
