# Prompt 3 — GLM 5.3 Flash — S64 season-aware reads

Copy everything below this line into the agent.

---

You are GLM 5.3 Flash. Implement the **S64 season-aware read** slice from `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` in an isolated git worktree.

## Workspace

- Worktree: `C:\Users\ehab.sayed\AppData\Local\Temp\opencode\estemshan-agents\s64-season-reads`
- Branch: `agent/v1-s64-season-reads`
- Baseline: `397902868e5bb8b3ad8247eb4ff1dd200efb6944` (`origin/main`)
- Project: native Kotlin Multiplatform; `native/functions` is Kotlin/JS (Node 22), `native/engine` is the shared pure engine.

`cd` to that worktree. Do **not** write in `C:\Users\ehab.sayed\Desktop\demo-test`; that checkout contains the user's uncommitted work.

## Read first

1. `AGENTS.md`.
2. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — S64, S59, RD24, RD27, and the leaderboard notes.
3. `native/functions/src/jsMain/kotlin/com/estemshan/functions/LeaderboardDb.kt` — the S64 read path, `DEFAULT_SEASON_ID = "s1"`, and its TODO.
4. `native/functions/src/jsMain/kotlin/com/estemshan/functions/RankedProfileDb.kt` — the lazy profile initialization and its own `DEFAULT_SEASON_ID = "s1"`.
5. `native/functions/src/jsMain/kotlin/com/estemshan/functions/FirebaseAdmin.kt`, `JsBridging.kt`, `CallableAuth.kt`.
6. `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonLeaderboardTest.kt`, `SeasonLeaderboardEmulatorTest.kt`, `RankedProfileDbTest.kt`.

## Hard rules

- Work only in the worktree above. Do not commit, push, merge, rebase, reset, stash, clean, or checkout over changes.
- Do not edit `firestore.rules`, `firebase.json`, `.github/**`, or `docs/**`.
- Do not install Node or npm packages. Do not run `npm`, `npx`, or `npm ci`.
- Do not add dependencies or bump versions.
- Do not change `SeasonResetJob.kt`, `SeasonResetDb.kt`, `PartyGate.kt`, or any `:services`/`:app` file.
- Do not change `SettlementDb.kt`'s `"s1"` default. That is a legacy settlement default, not live-season resolution.
- Leave all changes uncommitted.

## Write scope

Production files (exactly these two):

- `native/functions/src/jsMain/kotlin/com/estemshan/functions/LeaderboardDb.kt`
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/RankedProfileDb.kt`

Tests (create or edit):

- `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonLeaderboardTest.kt`
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/RankedProfileDbTest.kt`
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeasonDocumentTest.kt` (new, optional if the existing files can hold the cases)

Do not edit `LeaderboardExports.kt` or `RankedProfileExports.kt`: both call the existing functions, and the Admin implementations will resolve the season internally.

## The shared season document contract

`seasons/current` has:

```text
seasonId:       String   // live season, e.g. "s1"
status:         String   // "ACTIVE" | "RUNNING" | "COMPLETE"
targetSeasonId: String?
nextSeasonId:   String?
startsAt:       Timestamp
endsAt:         Timestamp
updatedAt:      Timestamp
```

Read-side rules:

- No document → **`s1`**. This is the only bootstrap. An existing document is authoritative.
- An existing document with a blank/non-string `seasonId`, or data that is not an object, fails closed with `HttpsError("failed-precondition", ...)`.
- `status`, `targetSeasonId`, `nextSeasonId`, `startsAt`, and `endsAt` are **not** consulted by these reads. The served season is always `seasonId`. During a reset (`RUNNING`) the outgoing season remains the served season until the job commits the new one; do not serve `targetSeasonId`.
- The season ID is case-sensitive. `"S1"` and `"s1"` are different names; do not normalize.

## Deliverable 1 — `LeaderboardDb.kt`

1. Keep `DEFAULT_SEASON_ID = "s1"` as the bootstrap constant.
2. Add a small pure parser:

```kotlin
internal fun currentSeasonIdOf(exists: Boolean, data: Any?): String
```

- `!exists` → `"s1"`.
- `exists` and `data` is a Kotlin map with a non-blank String `seasonId` → that value. The Admin implementations convert the JS object with `dynamicToKotlin(snap.data())` before calling this helper, exactly as `readSeasonProfiles` does; the helper itself does not touch `dynamic`.
- Anything else → `HttpsError("failed-precondition", "seasons/current has no usable seasonId.")`.
- Do not echo the malformed value in the message.

3. Extend `LeaderboardDb` with a defaulted method:

```kotlin
suspend fun currentSeasonId(): String = DEFAULT_SEASON_ID
```

The default keeps every existing fake compiling. `AdminLeaderboardDb` overrides it:

```kotlin
override suspend fun currentSeasonId(): String {
  val snap = db.collection("seasons").doc("current").get().await()
  return currentSeasonIdOf(snap.exists, dynamicToKotlin(snap.data()))
}
```

4. Change `leaderboardOnce` so an **absent** or **explicitly null** `seasonId` resolves via `db.currentSeasonId()`. A present value must still be a non-blank string; blank/non-string is `invalid-argument` **before** the current-season read and before the profile read.
   - Order the argument checks so `limit` is parsed **before** the season is resolved: a rejected `limit` must cost zero `currentSeasonId()` calls and zero profile reads, even when `seasonId` is absent.
   - Parsing an explicit season performs no read at all; only the absent/null case reaches `currentSeasonId()`.
   - `resolveSeason` becomes `suspend`, takes the `LeaderboardDb`, validates an explicit value first, and only then falls back to `db.currentSeasonId()`.
   - The response's `seasonId` is the resolved value, so a caller is never shown one board while believing another.
5. Keep explicit-season behavior: a caller that names `s2` gets the `s2` board even if `seasons/current` says `s3`. That is a historical board, not the default. Do not check explicit seasons against the current one.
6. Do not change ordering, the 10,000 cap, the row projection, the skip log, or the `limit` rules.

## Deliverable 2 — `RankedProfileDb.kt`

1. Keep the file's own `DEFAULT_SEASON_ID = "s1"` as the bootstrap.
2. Extend `RankedProfileDb.Tx` with a defaulted method:

```kotlin
suspend fun currentSeasonId(): String = DEFAULT_SEASON_ID
```

The default keeps every existing fake compiling. `AdminTx` overrides it by reading `seasons/current` **inside the same transaction**, before any write:

```kotlin
override suspend fun currentSeasonId(): String {
  val snap = tx.get(db.collection("seasons").doc("current")).await()
  return currentSeasonIdOf(snap.exists, dynamicToKotlin(snap.data()))
}
```

Reuse the `currentSeasonIdOf` helper from `LeaderboardDb.kt` (same package, `internal`). Do not duplicate the parser.
3. `loadOnce` must read the profile first, then the current season, then merge. All reads happen before the merge, as Firestore requires.
4. Pass the resolved season into `normalizeProfile(stored, currentSeasonId)`:
   - `seasonId` absent → use `currentSeasonId` and **merge it** (lazy initialization).
   - `seasonId` present and equal to `currentSeasonId` → use it; no season merge.
   - `seasonId` present but blank → corrupt, no merge.
   - `seasonId` present but **different** from `currentSeasonId` → `HttpsError("failed-precondition", "getMyRankedProfile: profile season '...' is not the current season '...'.")`; no merge, no write. A stale profile is a missed reset and must be loud, not silently re-stamped.
5. Do not change any other normalization rule, the stats, the response shape, or the transaction shape.

## Tests

### `SeasonLeaderboardTest.kt`

Add a fake `LeaderboardDb` variant with an overridable current season and pin:

- absent `seasonId` → `currentSeasonId()` is called once, the named season's profiles are read, and the response echoes it;
- explicit `null` takes the same path;
- explicit `"s2"` does **not** call `currentSeasonId()` and serves `s2`;
- blank/non-string season still `invalid-argument` with zero current-season reads and zero profile reads;
- an out-of-range `limit` with an absent `seasonId` is `invalid-argument` with zero current-season reads and zero profile reads; an out-of-range `limit` with an explicit valid season is also zero profile reads;
- a current-season document that is missing/malformed → `failed-precondition` before any profile read;
- the existing explicit-season, cap, limit, projection, and tie tests still pass unchanged.

### `RankedProfileDbTest.kt`

Add a fake `Tx` that returns a configurable current season and pin:

- a legacy profile with no `seasonId` under current `s2` gets `seasonId = s2` merged, `migrated = true`, and the rest of its fields untouched;
- a profile with `seasonId = s2` under current `s2` is served with no season merge;
- a profile with `seasonId = s1` under current `s2` fails `failed-precondition` and performs **zero** merges;
- a blank `seasonId` fails and performs zero merges;
- the read order is profile → season → merge (assert the fake's event log);
- the existing s1/default cases still pass.

### `SeasonDocumentTest.kt` (or the existing files)

Test `currentSeasonIdOf` directly with Kotlin maps/plain values:

- `exists = false` → `s1`;
- `{seasonId: "s2"}` → `s2`;
- `{}`, `{seasonId: ""}`, `{seasonId: 7}`, `null`, and a non-object → `failed-precondition`;
- the error message does not contain the malformed value.

Do **not** write `seasons/current` in `SeasonLeaderboardEmulatorTest`; that document is a singleton in the shared emulator database. The pure parser and the fake ports are the right seams.

## Acceptance criteria

- `native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace` passes.
- No production file outside the two listed files changes.
- `SettlementDb.kt`'s `"s1"` is untouched.
- An absent `seasons/current` still behaves exactly as `s1`; a malformed document fails closed.
- A stale profile season is never silently rewritten.
- Emulator-gated tests are reported as **skipped locally** unless an emulator was actually running.

## Verification

From the worktree root:

```powershell
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace
```

If it fails outside your scope, stop and report.

## Report back

```text
STATUS: COMPLETE | PARTIAL | BLOCKED
Branch: agent/v1-s64-season-reads
HEAD: <sha> (uncommitted changes expected)

Files changed:
- LeaderboardDb.kt:
- RankedProfileDb.kt:
Tests changed/added:

Behaviour matrix (absent / valid / malformed / stale):
Commands run (exact) and result:
- <command> -> PASS/FAIL, test count

Emulator-gated tests:
- <class> -> EXECUTED / SKIPPED (marker) / NOT RUN

Deviations from this brief:
Known risks / follow-ups:
```

If blocked, name the exact file and invariant. Do not widen scope.
