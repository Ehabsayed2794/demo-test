# Prompt 5 — MiniMax M3.1 — S64 leaderboard client read port

Copy everything below this line into the agent.

---

You are MiniMax M3.1. Implement the **S64 leaderboard client read port** from `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` in an isolated git worktree.

## Workspace

- Worktree: `C:\Users\ehab.sayed\AppData\Local\Temp\opencode\estemshan-agents\s64-leaderboard-client`
- Branch: `agent/v1-s64-leaderboard-client`
- Baseline: `397902868e5bb8b3ad8247eb4ff1dd200efb6944` (`origin/main`)
- Project: native Kotlin Multiplatform card game; `native/services` is the Android/JVM service seam, `native/engine` is shared pure Kotlin, `native/functions` is Kotlin/JS (do not touch it).

`cd` to that worktree. Do **not** write in `C:\Users\ehab.sayed\Desktop\demo-test`; that checkout contains the user's uncommitted work.

## Read first

1. `AGENTS.md`.
2. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — S64, RD24, RD27, and the leaderboard notes.
3. `native/services/src/main/java/com/estemshan/services/CallableSeatReclaim.kt` — the response-mapping pattern to mirror: a thin callable class plus a separately testable pure mapper.
4. `native/services/src/main/java/com/estemshan/services/SeatReclaimPort.kt` — the sealed-outcome style.
5. `native/services/src/main/java/com/estemshan/services/CallableDecisionTimeout.kt`, `DecisionTimeoutPort.kt` — the callable naming and code-mapping conventions.
6. `native/services/src/main/java/com/estemshan/services/model/Results.kt` — reuse `Reasons`; do not add constants here.
7. `native/services/src/test/java/com/estemshan/services/SeatReclaimResponseTest.kt` — the hermetic test style.
8. `native/functions/src/jsMain/kotlin/com/estemshan/functions/LeaderboardDb.kt` and `native/functions/src/jsMain/kotlin/LeaderboardExports.kt` — the server's exact response shape and the callable name. Read only; do not edit.

## Hard rules

- Work only in the worktree above. Do not commit, push, merge, rebase, reset, stash, clean, or checkout over changes.
- Do not edit `firestore.rules`, `firebase.json`, `.github/**`, or `docs/**`.
- Do not install Node or npm packages. Do not run `npm`, `npx`, or `npm ci`.
- Do not add dependencies or bump versions.
- Do not edit `native/functions/**`, `native/app/**`, or `OnlineServices.kt`.
- Do not add a UI screen, composable, navigation entry, or ViewModel.
- Do not re-implement, mirror, or second-guess the engine's ordering. The client displays the server's rows in the order received.
- Leave all changes uncommitted.

## Why this port exists

`getRankedLeaderboard` is authenticated and returns display-safe rows that already carry their server-computed `position`. RD27 is explicit: **the client may read, never order.** This port is the typed read side of that sentence. It parses one response, rejects any shape it cannot trust, and hands the screen a page it can render verbatim. It must never sort, re-rank, recompute a position, clamp an RP, or repair a row.

There is no screen yet. This task ships the port and its hermetic mapper test only; the screen story wires it later. Do not add a caller just to avoid dead surface — `MatchmakingPort` set that precedent in this module.

## Write scope

- `native/services/src/main/java/com/estemshan/services/LeaderboardPort.kt` (new)
- `native/services/src/test/java/com/estemshan/services/LeaderboardResponseTest.kt` (new)

Anything else is out of scope. In particular, do not touch `Results.kt`, `OnlineServices.kt`, `RoomService.kt`, or any file another agent is editing.

## Server contract you are reading

Callable name: `getRankedLeaderboard`.

Request the port sends:

```text
{ seasonId?: String, limit?: Int }
```

- `seasonId` absent means "the server's current season". Do not send `"s1"` as a client-side default; the server resolves the live season and echoes it.
- `seasonId` present must be a non-blank string. The port does not validate it; the server rejects a blank or non-string with `invalid-argument`.
- `limit` defaults to `100` and the server accepts `1..100`. The port does not clamp or pre-validate it; the server rejects anything else with `invalid-argument`. A caller passing an out-of-range value has a programming error, and the port surfaces the server's rejection.

Successful response:

```text
{
  ok: true,
  seasonId: String,        // the season actually served
  rows: [ Row ],           // at most `limit`, already ordered and positioned
  you: Row | null,         // the caller's row, even when it is outside `rows`
  total: Int,              // the whole board's size, not `rows.size`
  skipped: Int             // profiles the board could not use
}

Row = {
  position: Int,           // 1-based competition rank; ties share and skip (1, 2, 2, 4)
  displayName: String,     // never blank; server fallback applied
  avatarInitial: String,   // never blank; server fallback applied
  tier: String,
  tierArabic: String,
  division: String,        // "" for King; not null
  rp: Int,
  seasonId: String,
  isCurrentPlayer: Boolean
}
```

There is **no `playerId`**, no uid, no email, no coins, no highest rank, and no placement state in a row. The server removed them deliberately. Do not add a field that expects them, and do not read them even if a response carries them.

## Deliverable 1 — `LeaderboardPort.kt`

Package `com.estemshan.services`.

### Public surface

```kotlin
interface LeaderboardPort {

  suspend fun board(seasonId: String? = null, limit: Int = DEFAULT_LIMIT): Outcome

  data class Page(
    val seasonId: String,
    val rows: List<Row>,
    val you: Row?,
    val total: Int,
    val skipped: Int,
  )

  data class Row(
    val position: Int,
    val displayName: String,
    val avatarInitial: String,
    val tier: String,
    val tierArabic: String,
    val division: String,
    val rp: Int,
    val seasonId: String,
    val isCurrentPlayer: Boolean,
  )

  sealed interface Outcome {
    data class Loaded(val page: Page) : Outcome
    data class Failed(val reason: String, val message: String) : Outcome
  }

  companion object {
    const val DEFAULT_LIMIT = 100
  }
}
```

Document on `board`:

- `seasonId = null` means the server's current season; the returned `Page.seasonId` is authoritative and is the only season the page describes.
- `seasonId` non-null is a historical/current board request; the response must echo the same value or the call fails closed.
- Rows are returned in the server's order, unchanged. The port does not sort.
- `Page.total` is the whole board, so `rows.size <= total` always.
- `Page.you` may be `null` when the caller has no profile in that season, and may not appear in `rows` when it is outside the requested limit.
- `Failed.reason` is one of `Reasons.INVALID_ARGUMENT`, `Reasons.UNAUTHENTICATED`, `Reasons.UNAVAILABLE`. Never parse `message`; branch on `reason`.

### `CallableLeaderboard`

```kotlin
class CallableLeaderboard(
  private val functions: FirebaseFunctions,
) : LeaderboardPort
```

- `private val callable = functions.getHttpsCallable(NAME)`, `NAME = "getRankedLeaderboard"`.
- Build the request with `buildMap`: put `"seasonId"` only when the argument is non-null, always put `"limit"`.
- `call(request).await().getData()`.
- On a returned value, `LeaderboardResponse.forData(data, seasonId)` — pass the requested season so an echoed mismatch fails closed.
- On `FirebaseFunctionsException` → `LeaderboardResponse.forCodeName(e.code.name)`. Read the name, not the enum, so the mapper stays hermetically testable; the one-line delegation is the only part that needs the SDK.
- On any other exception → `Outcome.Failed(Reasons.UNAVAILABLE, UNAVAILABLE_MESSAGE)`.

Keep this class deliberately thin: no retry loop, no cache, no local ordering, no snapshot listener.

### `LeaderboardResponse`

A public `object` in the same file, beside its only caller, exactly like `SeatReclaimResponse`:

```kotlin
object LeaderboardResponse {
  fun forData(data: Any?, requestedSeasonId: String?): LeaderboardPort.Outcome
  fun forCodeName(name: String): LeaderboardPort.Outcome
}
```

`forData` rules, in this order:

1. `data` must be a `Map<*, *>`; otherwise `Failed(UNAVAILABLE, MALFORMED_MESSAGE)`.
2. `ok` must be exactly `true`; otherwise malformed.
3. `seasonId` must be a non-blank `String`; otherwise malformed.
4. If `requestedSeasonId != null`, the echoed season must be **exactly equal** (case-sensitive); otherwise malformed. This is the one check that prevents a caller asking for `s2` and silently rendering `s1`.
5. `rows` must be a `List<*>`; every element must parse as a valid `Row` (below); otherwise malformed. An empty list is valid.
6. `total` and `skipped` must be whole, finite, non-negative numbers. Reject a `Double` with a fractional part; accept the integer widths the wire may use (`Int`, `Long`, `Double` with an integral value).
7. `total >= rows.size`; otherwise malformed.
8. `you` must be `null` or a valid `Row`; otherwise malformed.
9. Row order checks:
   - the first row's `position` is `1` when `rows` is non-empty;
   - positions are non-decreasing in the received order;
   - every row's `position <= total`; if `you != null`, `you.position <= total` (a position beyond the board's own size is not a page the client may render);
   - at most one row has `isCurrentPlayer == true`;
   - if a row is the current player, `you` must be non-null and equal to that row;
   - if `you != null`, `you.isCurrentPlayer` must be `true`.
   A list that fails any of these is not re-sorted or repaired; it is malformed. A descending or shuffled list proves the client refuses to order.
10. A `Row` is valid only when:
    - `position` is a whole number `>= 1`;
    - `displayName` and `avatarInitial` are non-blank strings;
    - `tier` and `tierArabic` are non-blank strings;
    - `division` is a `String` (empty is valid — King has no division);
    - `rp` is a whole, non-negative number;
    - `seasonId` is a non-blank string **equal to the page's `seasonId`**;
    - `isCurrentPlayer` is a `Boolean`.
    Do not validate tier/division against a ladder copy in `:services`; the server resolved the rung, and `:services` must not keep a second ladder table.

All malformed outcomes use the same generic message: `"The leaderboard response was not readable."` Never echo a field value, a uid, or a row's content into the message.

`forCodeName` mapping:

| Code name | Reason | Message |
|---|---|---|
| `INVALID_ARGUMENT` | `Reasons.INVALID_ARGUMENT` | `"The leaderboard request was rejected."` |
| `UNAUTHENTICATED`, `PERMISSION_DENIED` | `Reasons.UNAUTHENTICATED` | `"Sign in to view the leaderboard."` |
| `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`, `ABORTED`, `CANCELLED` | `Reasons.UNAVAILABLE` | `"The leaderboard is unavailable right now. Please try again."` |
| `RESOURCE_EXHAUSTED`, `FAILED_PRECONDITION`, `NOT_FOUND`, anything else | `Reasons.UNAVAILABLE` | `"The leaderboard is unavailable right now. Please try again."` |

`RESOURCE_EXHAUSTED` is the server's oversized-season refusal: the board is too large for one read, and retrying the same request will not change that. It is still `UNAVAILABLE` to the screen — there is no partial board to show. Do not invent a fourth reason.

Every message must be rank-free, uid-free, and safe to display.

## Deliverable 2 — `LeaderboardResponseTest.kt`

Hermetic JVM test against `LeaderboardResponse` only. Do **not** construct `FirebaseFunctionsException` or touch `FirebaseFunctions`; the SDK enum cannot initialize on a plain JVM, which is exactly why the mapper takes the code **name**.

Use plain Kotlin maps and lists, like `SeatReclaimResponseTest`. Pin at least:

### Happy paths

- a full page (`ok`, season, three positioned rows, `you` inside the top rows, `total = 3`, `skipped = 0`) → `Loaded` with every field preserved exactly and rows in the same order;
- `you` outside `rows` (e.g. position 42, `rows` only 2) → `Loaded`, `you` preserved, `total = 42`;
- `you = null` and an empty `rows` list (`total = 0`, `skipped = 0`) → `Loaded` with an empty board (a fresh season);
- King row with `division = ""` and a non-blank `tierArabic` → `Loaded`;
- tie positions `1, 2, 2, 4` → `Loaded` unchanged (the client does not renumber);
- extra private keys on the response and on a row (`playerId`, `uid`, `email`, `rank`, `highestRank`, `coins`) → the resulting `Page` is **equal** to the lean page, proving the mapper reads only the contracted keys.

### Season isolation

- absent request season (`requestedSeasonId = null`) and echoed `s2` → `Loaded` with `Page.seasonId = "s2"`; the client never substitutes `s1`;
- explicit request `s2` and echoed `s2` → `Loaded`;
- explicit request `s2` and echoed `s1` → `Failed(UNAVAILABLE)`; this is the only season check and it is case-sensitive (`"S2"` vs `"s2"` fails);
- a row whose `seasonId` differs from the page's → `Failed(UNAVAILABLE)`.

### Malformed shapes → `Failed(UNAVAILABLE, MALFORMED_MESSAGE)`

- `data` is `null`, a string, a number, a list, or a boolean;
- `ok` absent, `false`, or the string `"true"`;
- `seasonId` absent, blank, or non-string;
- `rows` absent or not a list;
- `total` or `skipped` absent, negative, fractional, or non-numeric;
- `total < rows.size`;
- a row position beyond `total`, and a `you` position beyond `total`;
- `you` is a non-map, or a map missing a required row field;
- a row missing each required key in turn;
- a row with `position = 0`, `position = -1`, a fractional position, or a string position;
- a row with a negative or fractional `rp`;
- a row with a blank `displayName`, blank `avatarInitial`, blank `tier`, or blank `tierArabic`;
- a row with `division = null`;
- a row with `isCurrentPlayer` missing or a string;
- rows out of position order (`3, 2, 1`) and a first position that is not `1` (`2, 3`);
- two rows with `isCurrentPlayer = true`;
- `you` present but `isCurrentPlayer = false`, or `you` present while a different row is current.

For each malformed case assert the **message is exactly the generic one**, not a value from the payload.

### Code mapping

- `INVALID_ARGUMENT` → `Reasons.INVALID_ARGUMENT`;
- `UNAUTHENTICATED` and `PERMISSION_DENIED` → `Reasons.UNAUTHENTICATED`;
- `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`, `ABORTED`, `CANCELLED` → `Reasons.UNAVAILABLE`;
- `RESOURCE_EXHAUSTED`, `FAILED_PRECONDITION`, `NOT_FOUND`, `ALREADY_EXISTS`, `OUT_OF_RANGE`, `UNIMPLEMENTED`, `DATA_LOSS`, `UNKNOWN`, and an unknown future name → `Reasons.UNAVAILABLE`;
- every mapped message is non-blank and contains no `"s1"`/`"s2"` season literal, uid, tier, or RP.

That is the whole test matrix. Do not add an emulator test: this task has no production callable change, and the response mapper is the seam.

## Acceptance criteria

- `native\gradlew.bat -p native :services:test --stacktrace` passes.
- `native\gradlew.bat -p native :services:assembleDebugAndroidTest --stacktrace` passes (no instrumented test change, but the module must still compile).
- Only the two listed files are added; no production file outside them changes.
- No `native/functions/**`, `native/app/**`, `OnlineServices.kt`, `Results.kt`, or UI change.
- `LeaderboardPort.kt` imports only `com.google.firebase.functions.*`, `kotlinx.coroutines.tasks.await`, and `com.estemshan.services.model.Reasons`.
- The port never sorts, re-ranks, clamps, or repairs a row.
- No response field outside the contracted shape reaches `Page`/`Row`.
- Emulator-gated tests are not applicable here; if any existing `:services` test is emulator-gated, report it as **skipped locally**, never passed.

## Verification

From the worktree root:

```powershell
native\gradlew.bat -p native :services:test --stacktrace
native\gradlew.bat -p native :services:assembleDebugAndroidTest --stacktrace
```

If a command fails outside your scope, stop and report. Do not install anything.

## Report back

```text
STATUS: COMPLETE | PARTIAL | BLOCKED
Branch: agent/v1-s64-leaderboard-client
HEAD: <sha> (uncommitted changes expected)

Files added:
- LeaderboardPort.kt:
- LeaderboardResponseTest.kt:

Public surface (copy the declarations):
Response mapping matrix (valid / malformed / season-mismatch):
Code mapping matrix:

Commands run (exact) and result:
- <command> -> PASS/FAIL, test count

Emulator-gated tests:
- <class> -> EXECUTED / SKIPPED (marker) / NOT RUN / NOT APPLICABLE

Deviations from this brief:
Known risks / follow-ups:
```

If blocked, name the exact file and invariant. Do not widen scope.
