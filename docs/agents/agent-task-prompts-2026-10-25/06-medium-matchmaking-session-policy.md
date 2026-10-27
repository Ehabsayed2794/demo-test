# Medium task 3 — T-20: the pure Auto-Match session policy

You are one of six agents working in parallel on this repository. You have your own worktree and a
file-disjoint write scope. Do not touch any file outside it.

**Task class:** MEDIUM. One new pure engine file and one new test file. The risk is not complexity;
it is accidentally settling **O-15** (band directionality) or encoding a vote rule the owner did not
approve. Stay inside the policy slice described below.

## 1. Read first, in this order

1. `AGENTS.md`.
2. `docs/engineering/NODEJS_SAFE_USAGE.md`.
3. `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` — only: **Q7c/Q26**, **A8.6 / Q41**, **O-15**, and the
   **T-20** table (all eight rows).
4. `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` — §5.10 rows **E4.1** and **E4.2**.
5. `native/engine/src/commonMain/kotlin/com/estemshan/engine/MatchmakingPool.kt` and its test
   `native/engine/src/jvmTest/kotlin/com/estemshan/engine/MatchmakingPoolTest.kt` — read both, edit
   neither.

`docs/**` is read-only. `native/app/**` is out of scope. `firestore.rules` is frozen.

## 2. Worktree

```powershell
git -C D:\demo-test worktree add D:\worktrees\agent-matchmaking-session -b agent/matchmaking-session 8b13b12
```

If the branch exists, use `agent/matchmaking-session-2`. Never `--force`. Never work in `D:\demo-test`.

## 3. What exists today (verified at `8b13b12`)

- `MatchmakingPool.kt` is the **directional** RD9 policy: `poolTierOrder` is the ladder's seven
  distinct tiers, and `isPoolEligible(searcher, candidate)` accepts the same tier or exactly one
  **below**, never one above. That directionality is **O-15** and is frozen.
- There is no session, queue, fill timer, vote, bot-fill decision or human-only strength source
  anywhere in `:engine`.
- `MatchmakingPort.kt` is a stub, navigation hardcodes ROOM, and there is no matchmaking screen. The
  runtime and queue are E4.1/E4.2 work, not this task.

So this task builds the **pure policy slice** only: no Firestore, no callable, no services, no UI, no
clock read. Every time value is a parameter, so the tests are deterministic.

## 4. Required deliverable: `MatchmakingSession.kt`

Create `native/engine/src/commonMain/kotlin/com/estemshan/engine/MatchmakingSession.kt`. Package
`com.estemshan.engine`, stdlib only, no I/O. Follow the repo's engine style: KDoc that states the
rule and the decision it encodes, `require` for a foreign tier (matching `MatchmakingPool`), raw
`Long` millis (matching `InactivityClock`).

### 4.1 Constants and types

```kotlin
const val MATCHMAKING_SEATS: Int = 4
const val MATCHMAKING_FILL_MILLIS: Long = 120_000L
const val MATCHMAKING_VOTE_MILLIS: Long = 30_000L

enum class MatchmakingPhase { FILLING, VOTING, FILLED, REJECTED }
enum class MatchmakingRejectReason { VOTE_FAILED, ABANDONED }

data class WaitingHuman(val uid: String, val tier: String)

data class MatchmakingSession(
  val phase: MatchmakingPhase,
  val openedAtMillis: Long,
  val voteOpenedAtMillis: Long?,
  val humans: List<WaitingHuman>,
  val approvals: Set<String>,
  val rejections: Set<String>,
  val filledWithBots: Boolean,
  val rejectReason: MatchmakingRejectReason?,
)
```

Names are suggestions; if you choose others, use them consistently and say so in the report. The
semantics below are not optional.

### 4.2 Construction and transitions

All functions are **pure**: they return a new session and never mutate the receiver.

- `open(firstUid, tier, nowMillis)` — starts `FILLING` with exactly one human and
  `openedAtMillis = nowMillis`. A blank uid or a tier not in `poolTierOrder` fails loudly (`require`).
- `join(uid, tier, nowMillis)` — adds a human **only** while `FILLING`, the table has fewer than four
  humans, the uid is not already seated, and `bandAccepts(tier)` is true. Otherwise it is a no-op and
  returns the receiver unchanged (idempotent; a duplicate uid never changes tier). When the join makes
  four humans, the phase becomes `FILLED` immediately with `filledWithBots = false` — no vote.
- `leave(uid)` — removes a human during `FILLING` or `VOTING`, and removes that uid from `approvals`
  and `rejections`. If the last human leaves, the phase becomes `REJECTED` with reason `ABANDONED`.
  In a terminal phase it is a no-op (the match already started; in-match departure is S7's domain).
- `approve(uid)` / `reject(uid)` — accepted only during `VOTING` and only for a current human. A uid
  votes **once**: a second `approve`/`reject` from the same uid is a no-op, so a voter cannot flip.
  A non-human or a call in `FILLING`/terminal is a no-op.
- `step(nowMillis)` — applies **every** transition whose deadline has passed, in order, in one call
  (a late tick must catch up, not stall):
  1. `FILLING`: at or after `openedAtMillis + MATCHMAKING_FILL_MILLIS`, if there are fewer than four
     humans, move to `VOTING` with `voteOpenedAtMillis = openedAtMillis + MATCHMAKING_FILL_MILLIS`
     (anchored to the timer, **not** to the tick that noticed it). If four humans are present, move
     to `FILLED` with no bots.
  2. `VOTING`: resolve early when the outcome is already decided (see §4.3), otherwise resolve at or
     after `voteOpenedAtMillis + MATCHMAKING_VOTE_MILLIS`. `FILLED` when a strict majority approved;
     otherwise `REJECTED` with reason `VOTE_FAILED`.
  3. Terminal phases are immutable: no transition ever re-opens `FILLED` or `REJECTED`.
  A `nowMillis` before `openedAtMillis` changes nothing (the clock never runs backwards).

### 4.3 The vote arithmetic (exact)

The electorate is the humans **currently waiting**, not a frozen snapshot. A human who leaves during
the vote is removed from the denominator and from both vote sets. Non-votes are rejections at the
deadline. The rule is exactly:

```kotlin
fun approved(session: MatchmakingSession): Boolean =
  session.approvals.size * 2 > session.humans.size
```

- 3 humans, 2 approvals → `FILLED` as soon as the second approval lands (`2*2 > 3`), with
  `filledWithBots = true`.
- 3 humans, 1 approval and 2 non-votes → **not** filled at any point; at the 30 s deadline the phase
  is `REJECTED` (`1*2 > 3` is false).
- 2 humans, 1 approval and 1 non-vote → at the deadline `REJECTED` (`1*2 > 2` is false).
- 1 human alone after the fill timer, 1 approval → `FILLED` (`1*2 > 1`); this is the "first player
  waits two minutes alone" case and it fills three bot seats.
- 4 humans before the timer → `FILLED` with `filledWithBots = false`; there is no vote and no bot.
- Early rejection is allowed only when **every** current human has voted (approvals + rejections ==
  humans) and there is no majority. A non-voter keeps the vote open until the deadline.

Expose the derived values the caller needs:

- `val botSeatsToFill: Int` — `MATCHMAKING_SEATS - humans.size` when `phase == FILLED &&
  filledWithBots`, else `0`.
- `val fillDeadlineMillis: Long` — `openedAtMillis + MATCHMAKING_FILL_MILLIS`.
- `val voteDeadlineMillis: Long?` — `voteOpenedAtMillis?.plus(MATCHMAKING_VOTE_MILLIS)`.
- `fun strengthTiers(): List<String>` — the tiers of the **humans only**, in join order. The session
  never stores a bot tier, so a bot can never enter the RP strength source. The RP math itself is out
  of scope.

### 4.4 The absolute band — and what it does **not** decide

```kotlin
fun bandAccepts(candidateTier: String): Boolean
```

- Uses `poolTierOrder`; a foreign tier fails loudly.
- Absolute distance: `max(index of humans + candidate) - min(index of humans + candidate) <= 1`.
  An empty session accepts any valid tier. Divisions never enter.
- **No time parameter and no widening path.** The answer is identical at 0 ms, 119 999 ms,
  120 000 ms, 150 000 ms, 180 000 ms, 180 001 ms and any later tick. The band is absolute (A8.6 /
  Q41), not a search that relaxes.
- This is a **necessary distance condition, not a pairing decision**. Do **not** add a symmetric
  `canPair(a, b)` that replaces `isPoolEligible`. The resolver composes
  `isPoolEligible(searcher, candidate) && session.bandAccepts(candidate.tier)`; which direction pairs
  is **O-15** and remains open. Do not edit `MatchmakingPool.kt`.
- `join` enforces the band as a safety net: a candidate two tiers away from any seated human is a
  no-op, so the table cannot become illegal through a direct call.

## 5. Tests

Create `native/engine/src/jvmTest/kotlin/com/estemshan/engine/MatchmakingSessionTest.kt` (JUnit 4,
matching `MatchmakingPoolTest`: `org.junit.Test` + `org.junit.Assert`). Cover at least:

1. The three constants are exactly 4, 120 000 and 30 000.
2. The fill timer starts at the **first** player: a second human joins at +60 000; `step` at
   +119 999 is still `FILLING`, and `step` at +120 000 is `VOTING` with
   `voteOpenedAtMillis == openedAtMillis + 120_000`.
3. A late tick catches up: `step` at +200 000 moves `FILLING → VOTING` and resolves the vote whose
   deadline (+150 000) has already passed, in the same call.
4. Four humans before the timer → `FILLED`, `filledWithBots == false`, `botSeatsToFill == 0`, no vote
   deadline.
5. 3 humans, 2 approvals → `FILLED` before the deadline, `filledWithBots == true`,
   `botSeatsToFill == 1`.
6. 3 humans, 1 approval and 2 non-votes → still `VOTING` at +149 999, `REJECTED(VOTE_FAILED)` at
   +150 000.
7. 2 humans, 1 approval and 1 non-vote → `REJECTED(VOTE_FAILED)` at the deadline.
8. 1 human, 1 approval → `FILLED`, `botSeatsToFill == 3`.
9. A double vote is a no-op; an explicit `reject` counts exactly like a non-vote; a non-human's vote
   is ignored.
10. A human who leaves during the vote is removed from the denominator and from both vote sets; the
    last human leaving is `REJECTED(ABANDONED)`.
11. Terminal phases ignore `join`, `approve`, `reject`, `leave` and `step` — no resurrection.
12. Band: same tier and one tier apart are accepted; **two tiers apart are rejected** in both
    directions; a candidate two tiers from any one seated human is rejected even when it is one tier
    from another; an empty session accepts any valid tier.
13. The band answer is unchanged after `step` at 120 000, 150 000, 180 000 and 180 001 ms — it never
    widens.
14. `strengthTiers()` returns only the humans, in join order; with 3 humans + 1 bot-filled seat it
    has exactly 3 entries, and a human who left is absent.
15. A foreign tier fails loudly on `open`, `join` and `bandAccepts`.

Do not assert a direction (Gold→Platinum vs Platinum→Gold) anywhere. Test 12 asserts **distance**,
never pairing direction; name it accordingly (e.g. `twoTiersApartIsNeverWithinTheBand`).

## 6. Write scope

**Allowed to create**

- `native/engine/src/commonMain/kotlin/com/estemshan/engine/MatchmakingSession.kt`
- `native/engine/src/jvmTest/kotlin/com/estemshan/engine/MatchmakingSessionTest.kt`

**Forbidden**

- `native/engine/src/commonMain/kotlin/com/estemshan/engine/MatchmakingPool.kt` and
  `MatchmakingPoolTest.kt` — directionality is O-15; do not change it or its tests.
- Every other `:engine` file, including `MatchmakingPort.kt` if it exists in your tree.
- `native/app/**`, `native/services/**`, `native/functions/**`.
- Any `*.gradle.kts`, `firestore.rules`, `firebase.json`, `.github/**`, `docs/**`.
- Any file that already differs from `8b13b12` in your worktree. If one does, stop and report it.

## 7. Acceptance criteria

Mark each `PASS` / `FAIL` / `NOT RUN` with the test that proves it.

1. `MatchmakingSession.kt` is pure: no I/O, no clock read, no mutable global state; every time value
   is a parameter.
2. The fill timer is anchored to the first player and is exactly 120 000 ms.
3. The vote is exactly 30 000 ms and opens at the fill deadline, not at the tick that noticed it.
4. A late `step` catches up across both deadlines in one call.
5. Four humans fill the table with no vote and no bots.
6. 3 humans + 2 approvals fill with one bot before the deadline.
7. 3 humans + 1 approval + 2 non-votes reject at the deadline.
8. 2 humans + 1 approval + 1 non-vote reject at the deadline.
9. 1 human + 1 approval fills with three bots.
10. Non-votes reject; a double vote cannot flip; a non-human vote is ignored.
11. A leaver is removed from the vote denominator; the last leaver abandons the session.
12. Terminal phases never re-open.
13. Two tiers apart is never within the band, at any session age, including 180 000 ms and later.
14. The band is a distance condition only; no test or code asserts a pairing direction (O-15).
15. `strengthTiers()` is humans-only and excludes leavers.
16. A foreign tier fails loudly.
17. `:engine:jvmTest` is green.

## 8. Deliberately not done (report these)

- **The queue and table join.** "Auto-Match joins a waiting table within one tier" and "no table →
  message + Create Table" need a server queue, a runtime and a screen. E4.1, not this task.
- **Bot profile selection and the actual fill.** `botProfileFor` lives in `:functions`; this task
  decides **how many** bot seats a filled vote creates, never which bot.
- **RP strength computation.** `strengthTiers()` is the human-only source; `resolveRanked` and the
  RP math are untouched.
- **The in-match 60 s all-gone cancel.** That is S7 / E4.3, a different clock. This session's
  `ABANDONED` is a pre-match lobby with no humans left.
- **O-15 directionality.** `isPoolEligible` is unchanged and no direction is asserted.
- **The 180 s widening question.** There is no widening code path at all; the test documents the
  invariant rather than proving a branch.

## 9. Commands

```powershell
native\gradlew.bat -p native :engine:jvmTest
```

Run only this. Do not run `npm`, `npx`, `npm ci`, or `node`. Do not run the full CI command.

## 10. Report

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

- the final API names and the exact vote formula;
- the decision you made for a leaver during the vote (denominator shrinks) and why;
- that the band is a distance condition and directionality stays O-15;
- the five deliberate gaps from §8;
- the exact `:engine:jvmTest` count and result.
