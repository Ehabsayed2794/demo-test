# Medium task 1 — E4.4 slice: a ROOM match starts only with four ready players

You are one of six agents working in parallel on this repository. You have your own worktree and a
file-disjoint write scope. Do not touch any file outside it.

**Task class:** MEDIUM. One predicate, two call sites, one new test file. The risk is ripple into the
app, not complexity.

## 1. Read first, in this order

1. `AGENTS.md`.
2. `docs/engineering/NODEJS_SAFE_USAGE.md`.
3. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — the ROOM-lifecycle row (E4.4) and the **T-17** room-size
   row.
4. `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` — §5.10 row **E4.4**.
5. `native/services/src/main/java/com/estemshan/services/model/RoomDoc.kt`,
   `native/services/src/main/java/com/estemshan/services/RoomService.kt` (the join / setReady /
   maybeStartMatch paths) and `native/services/src/main/java/com/estemshan/services/MatchService.kt`
   (the start path).

`docs/**` is read-only. `native/app/**` is out of scope. `firestore.rules` is frozen.

## 2. Worktree

```powershell
git -C D:\demo-test worktree add D:\worktrees\agent-room-start-gate -b agent/room-start-gate 8b13b12
```

If the branch exists, use `agent/room-start-gate-2`. Never `--force`. Never work in `D:\demo-test`.

## 3. The defect (verified at `8b13b12`)

- `RoomDoc.allReady` is `players.isNotEmpty() && players.all { it in readyPlayers }`. It does **not**
  require a full table.
- `RoomDoc.isFull` is `players.size >= MAX_PLAYERS` and `MAX_PLAYERS = 4`.
- `RoomService.maybeStartMatch` starts a match when
  `room.status == STATUS_WAITING && room.allReady` — so a 2-player room where both are ready starts
  a 2-player match.
- `MatchService.startMatch` gates on `!room.allReady` with the message "Not all players are ready." —
  same hole, different entry point.
- `RoomService.joinRoom` already rejects `isFull` and `STATUS_CLOSED`. It does **not** reject
  `STATUS_IN_GAME`; that is a separate hole, out of scope here (see §7).

`allReady` is also read by `native/app/.../RoomScreen.kt` as a **cosmetic hint** ("starting" vs
"waiting") and by `RoomViewModelTest`'s fake to decide whether to call start. Changing `allReady`'s
meaning would ripple into the app; this task deliberately does not.

## 4. Required change

### 4.1 Add a full-table start predicate

In `RoomDoc`, next to `isFull` / `allReady`:

```kotlin
/**
 * A table may start only when it is FULL and every seated player is ready.
 * `allReady` deliberately keeps its "everyone present is ready" meaning — the
 * room screen uses it as a hint, and a non-full room can be all-ready.
 * Status is the caller's gate (`STATUS_WAITING`), not this predicate's.
 */
val canStart: Boolean get() = isFull && allReady
```

Name it `canStart` unless a name already exists; if you choose another name, use it consistently and
say so in the report.

### 4.2 Gate both server start paths

- `RoomService.maybeStartMatch`: replace `!room.allReady` with `!room.canStart`. Leave the
  `STATUS_WAITING` check and the creator-only rule exactly as they are.
- `MatchService.startMatch`: replace `!room.allReady` with `!room.canStart`, and change the error
  message from "Not all players are ready." to something true for both causes, e.g.
  "A table needs four ready players to start." Keep the `INVALID_ARGUMENT` code and the existing
  `matchId` idempotence branch above it.

Do not change `allReady`, `isFull`, `MAX_PLAYERS`, `joinRoom`, `setReady`, `leaveRoom`, or the room
status constants.

### 4.3 Tests

Create `native/services/src/test/java/com/estemshan/services/model/RoomDocTest.kt` (JUnit 4, matching
`DepartureContractTest`'s style: `org.junit.Test` + `org.junit.Assert`). Cover at least:

- 0, 1, 2 and 3 players, all ready → `allReady == true`, `isFull == false`, `canStart == false`.
- 4 players, all ready → `canStart == true`.
- 4 players, 3 ready → `canStart == false`.
- 4 players, 0 ready → `canStart == false`.
- 5 players (a legacy over-full document), all ready → `canStart == true` (the predicate is `>= 4`,
  matching `isFull`).
- `allReady` keeps its old meaning in the 3-player case, so the app hint is unchanged.

If you want an emulator-level proof of the two call sites, add
`native/services/src/androidTest/java/com/estemshan/services/RoomStartGateEmulatorTest.kt`. It will
self-skip without an emulator — report it as **skipped**, never passed. It is optional; the predicate
test is the required evidence.

## 5. Write scope

**Allowed to modify**

- `native/services/src/main/java/com/estemshan/services/model/RoomDoc.kt`
- `native/services/src/main/java/com/estemshan/services/RoomService.kt`
- `native/services/src/main/java/com/estemshan/services/MatchService.kt`
- **New** `native/services/src/test/java/com/estemshan/services/model/RoomDocTest.kt`
- **Optional new** `native/services/src/androidTest/java/com/estemshan/services/RoomStartGateEmulatorTest.kt`

**Forbidden**

- `native/app/**` (all of it, including `RoomScreen.kt` and `RoomViewModelTest.kt`).
- `native/engine/**`, `native/functions/**`.
- `native/services/src/main/java/com/estemshan/services/model/Results.kt` — do not add a reason
  constant for this.
- `native/services/src/main/java/com/estemshan/services/model/MatchModels.kt` (another agent owns it).
- Any `*.gradle.kts`, `firestore.rules`, `firebase.json`, `.github/**`, `docs/**`.
- Any file that already differs from `8b13b12` in your worktree. If one does, stop and report it.

## 6. Acceptance criteria

Mark each `PASS` / `FAIL` / `NOT RUN` with the test that proves it.

1. `RoomDoc.canStart` is `false` for 0–3 players even when all are ready.
2. `RoomDoc.canStart` is `true` for exactly 4 ready players.
3. `RoomDoc.canStart` is `false` when any of 4 players is not ready.
4. `RoomDoc.allReady` is unchanged in every one of those cases.
5. `RoomService.maybeStartMatch` no longer starts a non-full room (predicate swap; cite the diff).
6. `MatchService.startMatch` no longer starts a non-full room, and its error message is true for a
   non-full room and for an unready full room (cite the diff).
7. The creator-only rule, the `STATUS_WAITING` gate, and the existing `matchId` idempotence branch are
   unchanged.
8. `:services:test` is green.
9. `:app:testDebugUnitTest` is green — proof that not changing `allReady` did not ripple into the app.
10. If you added the emulator test, it is reported as skipped (no emulator) or passed (with one);
    either way, never claim a local pass without a run.

## 7. Deliberately not fixed (report these)

- **`joinRoom` accepts `STATUS_IN_GAME`.** A started room that has lost a player can be rejoined by a
  new player. Fixing it needs a Firestore fake or an emulator test, neither of which exists; it is a
  separate task. Say so in `HANDOFF NOTES`.
- **The room screen's "starting" hint** still keys on `allReady`, so a 2-player all-ready room shows
  the hint and then does not start. That is a UI fix, explicitly out of scope.
- **`MatchStartResult`** has no "not enough players" reason, so `maybeStartMatch` returns
  `NOT_ALL_READY` for a non-full room. A better result shape is a follow-up.

## 8. Commands

```powershell
native\gradlew.bat -p native :services:test
native\gradlew.bat -p native :app:testDebugUnitTest
```

If you added the emulator test and an emulator is available:

```powershell
native\gradlew.bat -p native :services:connectedDebugAndroidTest
```

Otherwise report it `NOT RUN` with the reason. Do not install an emulator or Node. Do not run the
full CI command.

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

In `HANDOFF NOTES`, state the predicate's final name, both call sites you changed, and the three
deliberate gaps from §7.
