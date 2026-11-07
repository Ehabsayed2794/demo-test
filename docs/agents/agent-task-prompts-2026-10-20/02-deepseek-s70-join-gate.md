# Prompt 2 — DeepSeek V4.1 Flash — S70 Rank-down join gate

Copy everything below this line into the agent.

---

You are DeepSeek V4.1 Flash. Implement story **S70** from `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` in an isolated git worktree.

## Workspace

- Worktree: `C:\Users\ehab.sayed\AppData\Local\Temp\opencode\estemshan-agents\s70-join-gate`
- Branch: `agent/v1-s70-join-gate`
- Baseline: `397902868e5bb8b3ad8247eb4ff1dd200efb6944` (`origin/main`)
- Project: native Kotlin Multiplatform card game; `native/functions` is Kotlin/JS (Node 22), `native/services` is Android/JVM, `native/engine` is shared pure Kotlin.

`cd` to that worktree. Do **not** write in `C:\Users\ehab.sayed\Desktop\demo-test`; that checkout contains the user's uncommitted work.

## Read first

1. `AGENTS.md`.
2. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — the S70 row in the E6b table, RD29, RD30, and R32.
3. `native/functions/src/jsMain/kotlin/com/estemshan/functions/PartyGate.kt` — the already-merged pure decision core. Its semantics are frozen.
4. `native/functions/src/jsMain/kotlin/com/estemshan/functions/FirebaseAdmin.kt`, `Firebase.kt`, `JsBridging.kt`, `CallableAuth.kt`.
5. `native/functions/index.js` and `native/functions/src/jsMain/kotlin/Functions.kt`.
6. `native/services/src/main/java/com/estemshan/services/RoomService.kt`, `model/RoomDoc.kt`, `model/Results.kt`, `CallableSeatReclaim.kt`, `SeatReclaimPort.kt`.
7. `native/app/src/main/java/com/estemshan/game/data/OnlineServices.kt`.
8. `native/services/src/androidTest/java/com/estemshan/services/LeaveMatchEmulatorTest.kt`, `ScriptedMatch.kt`.
9. `firestore.rules` — read only. The room **create** whitelist includes `mode`/`rankDown`; the room **update** whitelist is only `players`, `readyPlayers`, `status`, `creator`, `updatedAt`, `matchId`. Do not change the rules.

## Hard rules

- Work only in the worktree above. Do not commit, push, merge, rebase, reset, stash, clean, or checkout over changes.
- Do not edit `firestore.rules`, `firebase.json`, `.github/**`, or `docs/**`.
- Do not install Node or npm packages. Do not run `npm`, `npx`, or `npm ci`.
- Do not add dependencies or bump versions.
- Do not change `PartyGate.kt`, `SeasonResetJob.kt`, `LeaderboardDb.kt`, or `RankedProfileDb.kt`.
- Do not add a UI screen, composable, or navigation entry.
- Leave all changes uncommitted.

## Important residual risk — state it, do not hide it

The frozen `firestore.rules` room-update whitelist allows an authenticated client to write `players` directly. A modified client can therefore bypass this callable and self-join a room. S70 delivers the **authoritative server decision and the honest client path**, not a rules-level enforcement boundary. Add a short code comment on the callable stating this, and include it in your report. **Do not claim the bypass is closed.** Do not attempt to change the rules.

## Write scope

Functions:

- `native/functions/src/jsMain/kotlin/com/estemshan/functions/PartyJoinDb.kt` (new)
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/PartyJoin.kt` (new)
- `native/functions/src/jsMain/kotlin/PartyJoinExports.kt` (new; **no package declaration**)
- `native/functions/index.js` (one new export line)
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/PartyJoinTest.kt` (new)
- `native/functions/src/jsTest/kotlin/com/estemshan/functions/PartyJoinEmulatorTest.kt` (new)

Services:

- `native/services/src/main/java/com/estemshan/services/RankDownJoinPort.kt` (new)
- `native/services/src/main/java/com/estemshan/services/RoomService.kt` (routing only)
- `native/services/src/main/java/com/estemshan/services/model/Results.kt` (new reason constants only)
- `native/services/src/test/java/com/estemshan/services/RankDownJoinResponseTest.kt` (new)
- `native/app/src/main/java/com/estemshan/game/data/OnlineServices.kt` (wire the port; minimal edit)
- `native/services/src/androidTest/java/com/estemshan/services/LeaveMatchEmulatorTest.kt` and `ScriptedMatch.kt` (constructor updates only)

Anything else is out of scope.

## Server contract

### Callable

Name: `joinRankDownRoom` (flat export, `@JsExport @JsName("joinRankDownRoom")` in `PartyJoinExports.kt`).

Request: `{ roomId: String }` and nothing else.

Response on a permitted join:

```text
{ joined: true, room: { roomId, name, status, creator, players, readyPlayers,
                        mode, rankDown, gameType, scoringMode, matchId } }
```

Response on an eligibility denial:

```text
{ joined: false, reason: "RANK_INCOMPATIBLE" | "PROFILE_UNAVAILABLE", message: "<rank-free sentence>" }
```

`HttpsError` for everything that is not an eligibility decision:

| Case | Code | `details.reason` |
|---|---|---|
| malformed/missing `roomId`, unknown/forbidden key | `invalid-argument` / `permission-denied` | `INVALID_ARGUMENT` / `FORBIDDEN_KEY` |
| room not found | `not-found` | `ROOM_NOT_FOUND` |
| room closed or already `in_game` | `failed-precondition` | `ROOM_CLOSED` |
| room full | `resource-exhausted` | `ROOM_FULL` |
| not a Rank-down room (`mode != RANKED` or `rankDown != true`) | `failed-precondition` | `NOT_RANK_DOWN` |

`HttpsError`'s third argument carries `mapOf("reason" to <stable reason>)`. Do not put a tier, division, RP, or another player's uid in any message or details.

### Request parsing

- `CallableAuth.requireUid(request)` first; unauthenticated → `unauthenticated`.
- `request.data` is a **plain JS object**, not a Kotlin map. Use `dynamicToKotlin` exactly as the other callables do.
- Only `roomId` is allowed. A key in `{uid, playerId, tier, division, rank, rp, seasonId, mode, rankDown}` → `permission-denied` with the fixed sentence `identity, tier and room configuration are server-derived; '<key>' is not accepted.` Any other unknown key → `invalid-argument` naming the key. Never echo the value.
- `roomId` must be a non-blank string. Normalize it exactly as `RoomDoc.kt` does: trim, and uppercase when the trimmed length is `<= 6`. Duplicate that three-line policy locally with a comment explaining that `:functions` cannot depend on `:services`; add a unit test for both branches.
- Reject an empty/whitespace `roomId` before any Firestore read.

### Transaction and gate

`PartyJoinDb`:

```kotlin
interface PartyJoinDb {
  suspend fun <T> transact(block: suspend (Tx) -> T): T
  interface Tx {
    suspend fun readRoom(roomId: String): Map<String, Any?>?
    suspend fun readProfile(uid: String): Map<String, Any?>?
    fun writePlayers(roomId: String, players: List<String>) // also stamps updatedAt
  }
}
```

`AdminPartyJoinDb` uses `ensureDefaultAdminApp()` + `getFirestore()` and `runTransaction`. Its `readRoom` returns the document's fields plus `"roomId" to roomId` (the document id is not stored in the body), so the projection has a stable id to echo. Convert reads with `dynamicToKotlin` and writes with `toPlain`, as the other Admin ports do.

Inside **one** transaction, in this order (Firestore requires all reads before writes):

1. Read the room. Absent → throw the `ROOM_NOT_FOUND` `HttpsError`.
2. If `status == "closed"` or `status == "in_game"` → `ROOM_CLOSED`.
3. Parse `mode` and `rankDown` (absent ⇒ `ROOM`/`false`). If not (`RANKED` && `rankDown == true`) → `NOT_RANK_DOWN`. This check comes before membership so a non-Rank-down room can never answer `joined:true`.
4. If the caller is already in `players` → return `{joined:true, room}` immediately. This is idempotent and performs no write.
5. If `players.size >= 4` → `ROOM_FULL`.
6. Read `players/{creator}` and `players/{uid}`.
7. Convert each document to the engine's `RankedProfile` with RD30 defaults. Implement the parser locally in `PartyJoin.kt`; do not edit `RankedProfileDb.kt`.
   - `rp` absent → 0; present must be a whole, finite, non-negative number. An absent `rp` beside a tier/division above Bronze III is malformed, exactly as `RankedProfileDb.normalizeProfile` treats it (only the bottom rung is representable by the absent-RP default).
   - `tier` + `division` both absent → `rankAt(rp)` (an unplaced account is Bronze III).
   - both present → must name an exact `rankLadder` rung; exactly one present → malformed.
   - `seasonId` absent → `"s1"`; a present value is carried but **not** used for eligibility.
   - `highestRankTier`/`highestRankDivision` both absent → current; exactly one present → malformed; a stored highest below current raises to current; invalid rung → malformed.
   - `placementState` absent → `NOT_STARTED` when `rp == 0`, else `SKIPPED`; present must be a valid enum name.
   - `demotionShield` absent → `false`; present must be Boolean.
   - `progressionMismatch(profile)` must be null; a mismatch is malformed.
   - A missing document or any malformed field → `PartyGateDecision.Denied("PROFILE_UNAVAILABLE", <generic rank-free message>)`. Do not reveal which side failed.
8. Call the existing `decidePartyJoin(mode, rankDown, creatorProfile, joinerProfile)`.
   - `NotApplicable` → `NOT_RANK_DOWN`.
   - `Denied` → return `{joined:false, reason, message}`, **no write**.
   - `Allowed` → append the caller to `players` (re-check not already a member and not full), write `players` + `updatedAt = FieldValue.serverTimestamp()`, and return the room projection.
9. The room projection must include only: `roomId`, `name`, `status`, `creator`, `players`, `readyPlayers`, `mode`, `rankDown`, `gameType`, `scoringMode`, `matchId`. It must not include any rank, RP, season, placement, shield, highest rank, email, coins, or token.

`index.js`: add `joinRankDownRoom: kotlin.joinRankDownRoom` to the manifest. Do not touch the guard; a parallel task owns it and will extend it for scheduled functions.

## Client contract (`:services`)

### `RankDownJoinPort.kt`

```kotlin
sealed interface RankDownJoin {
  data class Joined(val room: RoomDoc) : RankDownJoin
  data class Denied(val reason: String, val message: String) : RankDownJoin
}

interface RankDownJoinPort {
  suspend fun join(roomId: String): RankDownJoin
}
```

`CallableRankDownJoin(functions: FirebaseFunctions)`:

- Calls `joinRankDownRoom` with `mapOf("roomId" to roomId)`.
- On success, `RankDownJoinResponse.forData(data)`.
- On `FirebaseFunctionsException`, `RankDownJoinResponse.forException(e.code.name, e.details)`.
- On any other exception, `RankDownJoin.Denied(Reasons.UNAVAILABLE, "Rank-down is unavailable right now. Please try again.")`.

`RankDownJoinResponse` (same file, hermetically testable like `SeatReclaimResponse`):

- `forData`: `joined == true` → parse `room` through `RoomDoc.fromFields`; malformed → `Denied(RANK_DOWN_UNAVAILABLE, ...)`.
- `forData`: `joined == false` → require a non-blank `reason` and `message`. Accept `RANK_INCOMPATIBLE` and `PROFILE_UNAVAILABLE` verbatim; any other reason is version skew and becomes `Denied(RANK_DOWN_UNAVAILABLE, "...")` with a generic, rank-free message. Never surface an unknown reason to the screen.
- `forException(codeName, details)`: read `details.reason` when `details` is a `Map<*, *>`; map:
  - `ROOM_NOT_FOUND` → `Reasons.ROOM_NOT_FOUND`
  - `ROOM_CLOSED` → `Reasons.ROOM_CLOSED`
  - `ROOM_FULL` → `Reasons.ROOM_FULL`
  - `NOT_RANK_DOWN` → `Reasons.RANK_DOWN_UNAVAILABLE`
  - `MALFORMED_RESPONSE` → `Reasons.RANK_DOWN_UNAVAILABLE`
  - `RANK_INCOMPATIBLE` stays a returned denial, not an exception.
  - `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL` → `Reasons.UNAVAILABLE`
  - unknown/missing reason → map by code: `NOT_FOUND` → `ROOM_NOT_FOUND`, `FAILED_PRECONDITION` → `RANK_DOWN_UNAVAILABLE`, `RESOURCE_EXHAUSTED` → `ROOM_FULL`, `INVALID_ARGUMENT` → `INVALID_ARGUMENT`, `UNAUTHENTICATED`/`PERMISSION_DENIED` → `UNAUTHENTICATED`, otherwise `UNAVAILABLE`.
- Every message is rank-free.

Add to `Results.kt` (only these constants):

```kotlin
const val RANK_INCOMPATIBLE = "RANK_INCOMPATIBLE"
const val PROFILE_UNAVAILABLE = "PROFILE_UNAVAILABLE"
const val RANK_DOWN_UNAVAILABLE = "RANK_DOWN_UNAVAILABLE"
```

### `RoomService`

- Add a required constructor parameter `private val rankDownJoiner: RankDownJoinPort` after `matchStarter`.
- In `joinRoom`, before the existing direct transaction, read the room with `loadRoom(roomId)`:
  - If the room is `mode == MatchMode.RANKED && rankDown`, and the caller is already a member, return the room (idempotent, no callable).
  - Otherwise, if Rank-down, call `rankDownJoiner.join(roomId)`.
  - `Joined` → `syncProfile(playerId, roomId)`, return the room.
  - `Denied` → throw `ServiceException(reason, message)`.
  - If `loadRoom` returns null, fall through to the existing transaction so the canonical `ROOM_NOT_FOUND` path is unchanged.
- Every non-Rank-down room keeps the existing direct transaction byte-for-byte in behaviour.
- Do not read any other player's profile on the client. Do not add a rank field to `RoomDoc`.

Update all construction sites:

- `OnlineServices.kt`: add `val rankDownJoins: RankDownJoinPort by lazy { CallableRankDownJoin(FirebaseModule.functions) }`, and pass it to `RoomService`. Keep the edit minimal; do not reformat the file.
- `LeaveMatchEmulatorTest.kt` (two sites) and `ScriptedMatch.kt` (one site): pass a small test fake that returns `RankDownJoin.Denied(Reasons.RANK_DOWN_UNAVAILABLE, "...")`. These tests do not exercise Rank-down joins; a throwing fake is not acceptable because it would turn an accidental Rank-down path into a crash instead of a product denial.

## Tests

### `PartyJoinTest.kt` (fake port, no emulator)

Pin at least:

- malformed/missing/blank `roomId` → `invalid-argument`, zero reads;
- unknown key → `invalid-argument`; `uid`/`tier`/`rp`/`seasonId`/`mode` → `permission-denied`, and the value is never echoed;
- room absent → `ROOM_NOT_FOUND`; closed → `ROOM_CLOSED`; `in_game` → `ROOM_CLOSED`; full → `ROOM_FULL`;
- not Ranked / `rankDown == false` → `NOT_RANK_DOWN`;
- already a member → `joined:true`, no write;
- same-tier division gap (Gold I + Gold III) → allowed, one write, players appended once;
- one-tier-apart pair (Gold I + Silver III) → allowed;
- two-tiers-apart pair (Gold + Bronze) → `joined:false`, `RANK_INCOMPATIBLE`, no write;
- King + Royal I → allowed; King + Diamond → denied;
- unplaced profile (no `rp`/tier/division) resolves to Bronze III and is eligible with Silver;
- missing profile → `PROFILE_UNAVAILABLE`, no write; malformed tier/rp/season → `PROFILE_UNAVAILABLE`, no write;
- response projection has no `tier`, `division`, `rp`, `seasonId`, `highestRank`, `placementState`, or `demotionShield` anywhere;
- the transaction reads room → creator → joiner before writing (assert the fake's event order).

### `PartyJoinEmulatorTest.kt` (real Firestore, self-skipping)

- `FIRESTORE_EMULATOR_HOST` absent → `console.log("S70_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset")` and return.
- Nonce-tag room IDs and uids.
- Seed two players and a Rank-down room; call `joinRankDownRoom` through `AdminPartyJoinDb` (or the orchestrator). Assert the room's `players` gained the caller exactly once, `updatedAt` moved, and the response has no rank fields.
- Seed an incompatible pair; assert `joined:false`, `RANK_INCOMPATIBLE`, and the room document is unchanged.
- Call twice; assert the second is idempotent and does not duplicate the uid.
- The class must print `S70_EMULATOR_SKIPPED` on skip.

### `RankDownJoinResponseTest.kt` (hermetic JVM)

- valid joined payload → `Joined` with the correct `RoomDoc` fields;
- denied payload → `Denied` with the same reason/message for `RANK_INCOMPATIBLE` and `PROFILE_UNAVAILABLE`;
- denied payload with a missing, blank, or unknown reason → `Denied(RANK_DOWN_UNAVAILABLE)` with the generic message;
- malformed joined payload (`room` missing/not a map) → `Denied(RANK_DOWN_UNAVAILABLE)`;
- every code/reason mapping above, including unknown codes and unknown reasons;
- no tier/rp/season in any mapped message.

## Acceptance criteria

- `native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace` passes.
- `native\gradlew.bat -p native :services:test :services:assembleDebugAndroidTest --stacktrace` passes.
- The app still builds: `native\gradlew.bat -p native :app:assembleDebug --stacktrace`.
- No `firestore.rules` change; no new dependency; no UI.
- The callable never returns another player's rank, and the client never reads another player's profile.
- The residual rules bypass is documented in code and in the report, not hidden.
- Emulator-only tests are reported as **skipped locally** unless an emulator was actually running.

## Verification

From the worktree root:

```powershell
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest --stacktrace
native\gradlew.bat -p native :services:test :services:assembleDebugAndroidTest --stacktrace
native\gradlew.bat -p native :app:assembleDebug --stacktrace
```

If a command fails outside your scope, stop and report. Do not install anything.

## Report back

```text
STATUS: COMPLETE | PARTIAL | BLOCKED
Branch: agent/v1-s70-join-gate
HEAD: <sha> (uncommitted changes expected)

Files added:
Files changed:

Server callable contract implemented:
Client routing implemented:
Constructor sites updated:

Commands run (exact) and result:
- <command> -> PASS/FAIL, test count

Emulator-gated tests:
- <class> -> EXECUTED / SKIPPED (marker) / NOT RUN

Residual risk (rules bypass):
Deviations from this brief:
Known risks / follow-ups:
```

If blocked, name the exact file and invariant. Do not widen scope.
