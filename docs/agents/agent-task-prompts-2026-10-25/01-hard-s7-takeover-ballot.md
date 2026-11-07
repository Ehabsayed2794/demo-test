# Hard task 1 — S7: 7th takeover removes the seat, cancels an open ballot, charges once

You are one of six agents working in parallel on this repository. You have your own worktree and a
file-disjoint write scope. Do not touch any file outside it.

**Task class:** HARD. This changes persisted match state, a terminal ballot phase, and an
exactly-once removal. If you cannot finish, stop at a compiling, tested slice and report `PARTIAL`
with the uncompleted slice named. Do not claim a slice you did not test.

## 1. Read first, in this order

1. `AGENTS.md` (repo instructions).
2. `docs/engineering/NODEJS_SAFE_USAGE.md` (Node policy — you may not need Node at all).
3. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — only these rows: **O-03 / A7.3**, **O-03b / A8.2 /
   Q36**, **A8.3 / Q37**, and the **T-15** departure matrix + **T-16** 7th-takeover row.
4. `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` — §5.10 row **S7** and §5.3 **T-09/T-16** context.
5. The files in your write scope (below). Read them fully before editing; each carries design
   comments that are part of the contract.

`docs/**` is read-only for you. `firestore.rules` is frozen. `native/app/**` is out of scope.

## 2. Worktree

```powershell
git -C D:\demo-test worktree add D:\worktrees\agent-s7-takeover -b agent/s7-takeover 8b13b12
```

If the branch exists, use `agent/s7-takeover-2`. Never `--force`. The primary checkout
`D:\demo-test` is the user's dirty checkout at a stale commit — never work in it, never reset,
stash, or clean it. Baseline is the SHA `8b13b12`, not a branch name.

## 3. What exists today (verified at `8b13b12`)

Read these before designing; the line numbers are approximate but the functions are exact.

- **No takeover counter exists.** `InactivityClock` (`:engine`) persists exactly two fields —
  `inactiveAccumulatedMillis` and `inactiveWindowStartMillis` — and its own KDoc says it has no
  count to increment. Do **not** add a third field to `InactivityClock`; that would contradict its
  documented RD20 separation and its fail-closed parser.
- `TimeoutResolution.inactivityAfterExpiredWindow(...)` returns
  `InactivityUpdate(docFields, takeover)`. `docFields` is documented as the **COMPLETE replacement**
  for the private `matches/{id}/inactivity/{uid}` document (clock fields + cursor). It already reads
  `storedDoc`, so it can read a count from it.
- `TimeoutResolutionDb.resolveTimeoutOnce(...)` writes `tx.writeInactivity(matchId, seatUid,
  inactivity.docFields)` and merges `InactiveTakeover.withTakeover(...)` into the match document.
- `TimeoutResolutionDb.reclaimOnce(...)` calls `SeatReclaim.decide(fresh, uid, now)` and then
  `tx.writeInactivity(matchId, uid, write.inactivityFields)`. `SeatReclaim.zeroedDoc(...)` rebuilds
  that document from `InactivityClock().storedFields()` + the cursor baseline — so **reclaim today
  drops any field it does not know about**.
- `TimeoutResolution.botHeldSeat(...)` returns true for every disconnect-held seat, and
  `inactivityAfterExpiredWindow` returns `null` for it. So a ballot-held seat's clock is frozen and
  can never reach another takeover.
- `TimeoutResolutionDb.resolveTimeoutOnce` returns a no-write **before** the expiry check when
  `TimeoutResolution.disconnectPauseRemaining(...)` is non-null (a granted `WAIT`).
- `DisconnectVotePhase` is `enum class DisconnectVotePhase { HOLD, VOTE, WAIT, REPLACED }`
  (`DisconnectVoteState.kt`). `parse` validates each phase's shape in an exhaustive `when`;
  `heldSeats()` returns every key; `reclaimableSeats()` excludes only `REPLACED`;
  `waitDeadlineMillis()` and `liveBallot()` read `WAIT`/`VOTE` only.
- `DisconnectVoteFlow.restore(...)` has an exhaustive `when (entry.phase)` over all four phases.
- `DisconnectVoteFlow.candidates(...)` and `electorateUids(...)` compute `terminated` as
  `removedUids + timeoutRemovedUids`.
- `MatchDoc` lives in `native/services/src/main/java/com/estemshan/services/model/MatchModels.kt`.
  It has `removedUids`, `timeoutRemovedUids`, `leftUids`, `disconnectReplacedUids`, `botSeats`,
  `inactiveTakeovers`, `disconnectVote`. `isRemoved` unions `removedUids + timeoutRemovedUids`;
  `botControlledSeats()` unions `botSeats + seats(timeoutRemoved) + seats(leftUids)`;
  `disconnectHeldSeats()` is `disconnectVote.keys`; `disconnectReclaimableSeats()` excludes only
  `REPLACED`.
- `LeaveMatch.kt` (`:engine`) has `LeaveReason` with exactly four values and a `decideLeave(mode,
  reason)` table. `DepartureContractTest` (`:services:test`) asserts that four-row table, so adding a
  `LeaveReason` value would break a module outside your scope.
- `TimeoutDb.TimeoutTx` has no ballot-delete method. `DisconnectVoteDb.Tx.deleteBallot(matchId, uid)`
  exists and deletes `matches/{id}/disconnectVotes/{uid}`.

## 4. Required behaviour

### 4.1 A persisted per-seat takeover counter

- Store it in the private `matches/{id}/inactivity/{uid}` document as a **separate** field managed by
  `TimeoutResolution` (suggested name `inactiveTakeoverCount`). Do not put it in `InactivityClock`.
- It counts **takeover events**, i.e. calls where `inactivityAfterExpiredWindow` returns a non-null
  `takeover`. It does not count ordinary timeouts.
- `inactivityAfterExpiredWindow` must:
  - read the stored count from `storedDoc`;
  - treat absent as `0`;
  - treat present-but-invalid (non-number, fractional, negative, `> 7`) as **fail-closed**: return
    `null` (write nothing, take nobody over), the same convention `InactivityClock.fromStored` uses;
  - write `nextCount = stored + (if takeover != null) 1 else stored` into `docFields`, always — the
    document is a complete replacement, so omitting it on an ordinary expiry would erase it.
- Reclaim must **carry the count through**, not reset it. `SeatReclaim.decide` needs the stored
  document (add a parameter with a default of `null` so existing fakes/tests compile);
  `reclaimOnce` must `tx.readInactivity(matchId, uid)` inside the transaction and pass it;
  `zeroedDoc` must include the carried count. A reclaim resets the clock, never the match's takeover
  history.

### 4.2 The 7th takeover is terminal

When the count reaches `7`:

1. Remove the seat permanently: append the uid to a new `takeoverRemovedUids` list on the match
   document. Deduplicate; append-only.
2. Record the charge once in a new `takeoverRemovedCharges` map: `{uid: 1}` in `ROOM`,
   `{uid: 2}` in `RANKED` and `UNRANKED`. Read the mode with the existing
   `TimeoutResolution.storedMatchMode(fields)`.
3. Delete the seat's ballot document (`disconnectVotes/{uid}`) and every electorate ballot for that
   ballot (`DisconnectVoteFlow.electorateUids(fields, seatId)`), so no stale vote can complete later.
4. Remove the seat's `disconnectVote` entry from the match document (all phases, not only `VOTE`) and
   its `inactiveTakeovers` entry.
5. Bump `version` and stamp `updatedAt`, like every other match write.
6. The seat is now permanently bot-driven: it must be treated as removed by every existing gate.

Exactly once:

- Replaying the same call must not append a second entry, add a second charge key, delete twice, or
  increment the count past 7.
- A uid already in `takeoverRemovedUids` must return an applied/no-write response without another
  write.

Do **not**:

- write `timeoutRemovedUids`, or add an extra increment to the timeout counter (the existing
  increment for a genuine human expiry stays exactly as it is);
- add a `LeaveReason` value, or change `decideLeave`'s four-row table;
- write `removedUids`, `leftUids` or `disconnectReplacedUids`.

Add the charge table as a standalone function in `LeaveMatch.kt`, e.g.

```kotlin
fun seventhTakeoverCharge(mode: MatchMode): KozCharge = when (mode) {
  MatchMode.ROOM -> KozCharge.Koz1
  MatchMode.RANKED, MatchMode.UNRANKED -> KozCharge.Koz2
}
```

Consumption of that charge by the settlement path is **out of scope** for this task; you record it
and report the gap.

### 4.3 A terminal `CANCELLED` ballot phase

Add `CANCELLED` to `DisconnectVotePhase` and make it terminal:

- `parse` accepts it only with **no** deadline and **no** verdict (a structural contradiction is
  corrupt state, as the other phases already are).
- It is **not** held: `heldSeats()` must exclude it, and so must
  `MatchDoc.disconnectHeldSeats()`.
- It is **not** reclaimable: `reclaimableSeats()` and
  `MatchDoc.disconnectReclaimableSeats()` must exclude it.
- It never appears in `liveBallot()` or `waitDeadlineMillis()` (both already filter to `VOTE`/`WAIT`
  — keep that).
- `DisconnectVoteFlow.restore(...)` must handle it explicitly and return `Restore.NothingHeld`; the
  `when` is exhaustive, so the compiler will force you.
- It is never re-opened or resurrected: `DisconnectVoteFlow.step`/`restore` must not move a
  `CANCELLED` entry back to `HOLD`/`VOTE`/`WAIT`/`REPLACED`.

Normal persisted matches should not keep a `CANCELLED` entry: the removal path deletes the ballot and
the marker. The phase exists so a partial or old write cannot be mistaken for a live ballot.

### 4.4 Firing during VOTE and WAIT

A7.3 requires the 7th takeover to fire **immediately, even with a 30 s ballot open or a 90 s WAIT
running**.

- **VOTE.** A disconnect-held seat must keep accruing its inactivity clock so a takeover can be
  earned while the ballot is open. Lift the clock's `botHeldSeat` suppression for the **inactivity
  clock only** — keep the timeout-counter suppression for held seats exactly as it is. This is the
  one deliberate semantic change: the hold is a borrowing, and RD19's clock measures the human's
  absence, so a held seat's clock is not the mechanism's own doing.
- **WAIT.** The pause gate returns before any expiry, so no takeover is evaluated while a `WAIT` is
  live. To satisfy A7.3, evaluate the 7th-takeover terminal condition **before** the pause gate, from
  the committed document plus the private inactivity document read in the same transaction. The
  evaluation must be read-only until it decides to remove; when the condition is false, the pause
  must behave exactly as today (no decision resolves, no deadline runs).
- **Other seats.** A 7th takeover of seat A must not cancel seat B's live `WAIT` or ballot. Only the
  removed seat's own ballot and marker are cancelled.

**Reachability honesty.** Before writing tests, establish for each of the three states (no ballot,
`VOTE`, `WAIT`) whether the 7th takeover is reachable with the existing trigger, and say so in your
report. If a state is not reachable without re-architecting the takeover trigger (E4.3: decision
timer + 10 s — explicitly out of scope), do **not** fake it with a test that calls a private helper
or hand-builds a state the runtime cannot produce. Implement what is reachable and report the rest as
`NOT DONE` with the reason.

### 4.5 Ballot-delete capability

Add to `TimeoutDb.TimeoutTx`:

```kotlin
suspend fun deleteBallot(matchId: String, uid: String) { /* default: no-op */ }
```

The default keeps every existing fake compiling. Implement it in `AdminTimeoutDb` against
`matches/{id}/disconnectVotes/{uid}`. In tests, assert the *calls* the fake recorded, not a Firestore
effect.

## 5. Write scope

**Allowed to modify**

- `native/engine/src/commonMain/kotlin/com/estemshan/engine/DisconnectVoteState.kt`
- `native/engine/src/commonMain/kotlin/com/estemshan/engine/LeaveMatch.kt`
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/TimeoutResolution.kt`
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/TimeoutResolutionDb.kt`
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/SeatReclaim.kt`
- `native/functions/src/jsMain/kotlin/com/estemshan/functions/DisconnectVoteFlow.kt`
- `native/services/src/main/java/com/estemshan/services/model/MatchModels.kt`
- Tests in those modules:
  `native/engine/src/jvmTest/kotlin/com/estemshan/engine/DisconnectVoteStateTest.kt`,
  `native/engine/src/jvmTest/kotlin/com/estemshan/engine/LeaveMatchTest.kt`,
  `native/functions/src/jsTest/kotlin/com/estemshan/functions/TimeoutChargeTest.kt`,
  `native/functions/src/jsTest/kotlin/com/estemshan/functions/DisconnectVoteFlowTest.kt`
- **New** test files you own, e.g.
  `native/functions/src/jsTest/kotlin/com/estemshan/functions/SeventhTakeoverTest.kt`
  and, if useful, `native/services/src/test/java/com/estemshan/services/model/MatchModelsTest.kt`.

**Forbidden**

- `native/app/**` (all of it), `native/functions/index.js`, any `*.gradle.kts`, `firestore.rules`,
  `firebase.json`, `.github/**`, `docs/**`.
- `Settlement.kt`, `SettlementDb.kt`, `SeasonReset*.kt` (other agents own them).
- `Bidding.kt`, `BiddingTest.kt` (another agent owns them).
- `InactivityClock.kt` — do not add the counter there.
- `DisconnectVote.kt` — its `permanentCharge` table is a different mechanism.
- Any file that already differs from `8b13b12` in your worktree. If one does, stop and report it.

## 6. Acceptance criteria

Mark each `PASS` / `FAIL` / `NOT RUN` with the test that proves it.

1. The counter is persisted in `inactivity/{uid}`, incremented only on a takeover event, and survives
   an ordinary expiry unchanged.
2. A reclaim preserves the counter (and still resets the clock and the cursor).
3. At count 6, the next takeover removes the seat: one `takeoverRemovedUids` entry, one
   `takeoverRemovedCharges` entry, no `timeoutRemovedUids` write, no timeout-counter increment added.
4. `ROOM` records `1`; `RANKED` and `UNRANKED` record `2`.
5. Replaying the 7th-takeover call writes nothing new (same list length, same charge map, count stays
   7).
6. A uid already in `takeoverRemovedUids` cannot be taken over, reclaimed, elected, or voted for.
7. The removed seat's ballot document is deleted, and so are its electorate's ballots.
8. The removed seat's `disconnectVote` and `inactiveTakeovers` entries are gone.
9. `CANCELLED` parses with no deadline and no verdict; a `CANCELLED` entry with either is rejected.
10. `CANCELLED` is excluded from held seats and reclaimable seats in both
    `DisconnectVoteState` and `MatchDoc`.
11. `DisconnectVoteFlow.restore` returns `NothingHeld` for `CANCELLED`, and no path re-opens it.
12. The 7th takeover fires with a `VOTE` ballot open (or the report states, with evidence, why it is
    unreachable).
13. The 7th takeover fires with a `WAIT` live, or the report states, with evidence, why it is
    unreachable and what E4.3 would have to change.
14. A 7th takeover of seat A does not cancel seat B's live `WAIT`.
15. `:engine:jvmTest` is green.
16. `:services:test` is green.
17. `:functions:jsNodeTest` is green, or `NOT RUN` with the runtime error. If it fails only on a test
    that encodes the old one-shot behaviour, name the test and report `PARTIAL` — do not edit a file
    outside your scope to make it pass.

## 7. Commands

Run from your worktree root, in this order, and paste real output into your report:

```powershell
native\gradlew.bat -p native :engine:jvmTest
native\gradlew.bat -p native :services:test
native\gradlew.bat -p native :functions:jsProductionExecutableCompileSync :functions:jsNodeTest
```

Do not run `npm`, `npx`, `npm ci`, or `node`. Do not run the full CI command.

## 8. Report

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

- the exact field names you added and their shapes;
- which of the three firing states (no ballot / `VOTE` / `WAIT`) you proved and which you did not;
- that the charge is **recorded but not consumed** — the consumer is a later task;
- whether `E4.3`'s decision-timer trigger remains unimplemented (it does; say so);
- anything a reviewer must check in `MatchModels.kt` because the client reads that union.
