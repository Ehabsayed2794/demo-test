# Prompt 4 — Mimo V.6 Flash — S63 abuse/edge-case coverage (tests only)

Copy everything below this line into the agent.

---

You are Mimo V.6 Flash. Implement the **S63 abuse/edge-case coverage** slice from `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` in an isolated git worktree.

## Workspace

- Worktree: `C:\Users\ehab.sayed\AppData\Local\Temp\opencode\estemshan-agents\s63-abuse-tests`
- Branch: `agent/v1-s63-abuse-tests`
- Baseline: `397902868e5bb8b3ad8247eb4ff1dd200efb6944` (`origin/main`)
- Project: native Kotlin Multiplatform; `native/functions` is Kotlin/JS (Node 22).

`cd` to that worktree. Do **not** write in `C:\Users\ehab.sayed\Desktop\demo-test`; that checkout contains the user's uncommitted work.

## Read first

1. `AGENTS.md`.
2. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — the S63 row ("forged results, replayed settlements, cross-tier injection, tie edge cases") and the abuse/edge-case notes.
3. `native/functions/src/jsTest/kotlin/com/estemshan/functions/SettlementTest.kt`, `IdempotencyGuardTest.kt`, `SeasonLeaderboardTest.kt`, `RankedProfileDbTest.kt`, `MatchmakingRequestTest.kt`, `PartyGateTest.kt`, `SettlementFixtures.kt`.
4. `native/functions/src/jsMain/kotlin/com/estemshan/functions/SettlementDb.kt`, `Settlement.kt`, `LeaderboardDb.kt`, `RankedProfileDb.kt`, `MatchmakingRequest.kt`.

## Hard rules

- **Test-only task.** Do not change any production file. If a test exposes a real defect, do not fix it; report the exact failing assertion and mark the task `PARTIAL` or `BLOCKED`.
- Work only in the worktree above. Do not commit, push, merge, rebase, reset, stash, clean, or checkout over changes.
- Do not edit `firestore.rules`, `firebase.json`, `.github/**`, or `docs/**`.
- Do not install Node or npm packages. Do not run `npm`, `npx`, or `npm ci`.
- Do not add dependencies.
- Leave all changes uncommitted.

## Write scope

- `native/functions/src/jsTest/kotlin/com/estemshan/functions/S63AbuseCoverageTest.kt` (new)

Nothing else. Do not edit `SeasonLeaderboardTest.kt`, `SettlementTest.kt`, `RankedProfileDbTest.kt`, or `MatchmakingRequestTest.kt`; another agent owns `SeasonLeaderboardTest.kt` in this same batch, and the existing suites already cover their own angles. Put every new case — including the leaderboard limit abuse — in the one new file with a small local fake port. Do not duplicate the existing settlement replay test, the existing `tiedTopSettlesBothKings` test, or the existing leaderboard tie tests; reference them in comments and add only the missing angles below.

## What S63 must cover here

The story names four things. Two are already well covered; this task adds the missing **boundary/identity** angles:

### A. Forged results (settlement)

A caller can put anything in the request, but `settleMatch` must derive the result from the match document and the archived rounds, never from the request.

Add a minimal fake `SettlementDb` in `S63AbuseCoverageTest.kt` (model it on `SettlementTest.FakeDb`, but keep only what these tests need) and pin:

- A request carrying `matchId` plus forged `finalScores`, `winnerIds`, `uid`, `tier`, `rp`, and `position` fields settles on the **recomputed** values. The forged fields appear nowhere in the response, the claim record, or the profile writes.
- The claim's `settledBy` is the authenticated uid, not the `uid` in the payload.
- A request with a forged `finalScores` map that covers the wrong seats is still corrected or failed closed by the document's own claim/shape; the payload never supplies the seat set.
- A request with a non-string `matchId` or a missing `matchId` is `invalid-argument` before any read.

Use `SettlementFixtures.matchFields(...)` and `SettlementFixtures.round1ArchiveFields()` so the fixture is the same golden table the existing suite uses.

### B. Replayed settlements

- Settle once, record the claim and profile writes, then call again with a **different forged payload**. Assert `replayed == true`, the first outcome and the first caller's `you` are returned, no second correction/claim/profile write occurs, and the forged payload changes nothing.
- A second caller replaying the same claim sees their own recorded movement, not the first caller's. (This is adjacent to `SettlementRpEmulatorTest`, which proves it against a real Firestore; here it is the fake-port contract, which is cheap and runs everywhere.)
- Do not re-test the generic `IdempotencyGuard`; that is already covered.

### C. Cross-tier injection (the missing boundary angle)

S57's `MatchmakingRequestTest` already pins the forbidden `tier`/`division`/`rank`/`rp`/`pool`/`optUp` keys. Do not duplicate it. Add the **read-side** identity angle instead:

- `getMyRankedProfile`: authenticate as `alice`, send `{uid: "bob"}` (and `{playerId: "bob"}`). The fake `RankedProfileDb.Tx.read` must receive `alice`; `bob` is never read, never merged, and never returned.
- `getRankedLeaderboard`: authenticate as `alice`, send `{seasonId: <season>, uid: "bob", playerId: "bob", tier: "King", division: "", rp: 9999, position: 1}`. Only `alice`'s row is `isCurrentPlayer`; `bob` is not highlighted, the board order is the server's, and no forged value appears in any row.
- A caller naming another season explicitly is allowed (historical board), but the response must contain only that season's rows. A hostile-looking season string is an **exact name**, never a pattern: seed profiles under `s1`, `s2`, and `s*`, request `s*`, and assert only the literal `s*` profile returns.

### D. Tie edge cases

Already covered in `SeasonLeaderboardTest` (competition positions 1, 2, 2, 4) and `SettlementTest.tiedTopSettlesBothKings`. Do not duplicate. Add one **boundary** tie only if it is genuinely missing: a two-player tie at the very top of the leaderboard and a tie at the cap boundary, asserting positions and `total` remain stable. If the existing tests already cover it, skip and say so in the report.

### E. Numeric/argument abuse on the leaderboard

Add these to `S63AbuseCoverageTest.kt` with a small local fake `LeaderboardDb` (the same pattern as the settlement fake — count reads, name the season):

- `Double.NaN`, `Double.POSITIVE_INFINITY`, `Double.NEGATIVE_INFINITY`,
- `Int.MAX_VALUE + 1.0`, `Long.MAX_VALUE.toDouble()`,
- a numeric string and a Boolean (likely already covered; keep only if not).

All must be `invalid-argument` and must cost **zero** profile reads. Also pin that a rejected `seasonId` costs zero reads even when a valid `limit` is present, and that a rejected `limit` costs zero reads even when a valid `seasonId` is present. Do not edit the existing suite to add these: keep them beside the other abuse cases in the new file, and name the local fake so the two files cannot be confused when someone runs the suite.

## Test style

- Use `kotlinx.coroutines.test.runTest` and `kotlin.test`.
- Build callable requests as **plain JS objects** (`js("({...})")` or `json(...)`), never Kotlin maps, because the runtime delivers plain objects.
- Read `HttpsError` codes via `e.asDynamic().code`, as the existing suites do.
- Keep every test deterministic and hermetic; no emulator, no network, no sleeps.
- Name tests as sentences that state the abuse and the defence, e.g. `aForgedFinalScoresPayloadCannotSteerTheSettlement`.
- Add a short KDoc at the top of `S63AbuseCoverageTest.kt` listing what S63 means, which cases are new here, and which existing classes already own the rest.

## Acceptance criteria

- `native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace` passes.
- Only `S63AbuseCoverageTest.kt` is added; no other file changes.
- No production behaviour is modified to make a test pass.
- The new tests fail if the defence is removed: for the identity-spoofing cases, the fake records the uid it was asked to read; for the settlement cases, the fake records the claim and profile writes; for the limit cases, the fake counts profile reads.
- The report states which S63 cases were already covered and deliberately not duplicated.

## Verification

From the worktree root:

```powershell
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace
```

If a new test fails because the baseline production code has a real defect, do **not** patch production. Report the failing test, the exact assertion, and the minimal reproduction. If the failure is a mistake in your test, fix the test and rerun.

## Report back

```text
STATUS: COMPLETE | PARTIAL | BLOCKED
Branch: agent/v1-s63-abuse-tests
HEAD: <sha> (uncommitted changes expected)

Files added/changed:
Tests added (name -> what it pins):

S63 coverage matrix:
- forged results: NEW / ALREADY COVERED (class)
- replayed settlements: NEW / ALREADY COVERED (class)
- cross-tier injection: NEW ANGLE / ALREADY COVERED (class)
- tie edge cases: NEW / ALREADY COVERED (class)
- numeric/argument abuse: NEW

Commands run (exact) and result:
- <command> -> PASS/FAIL, test count

Any real defect found (do not fix):
Deviations from this brief:
Known risks / follow-ups:
```

If blocked, name the exact test and assertion. Do not widen scope.
