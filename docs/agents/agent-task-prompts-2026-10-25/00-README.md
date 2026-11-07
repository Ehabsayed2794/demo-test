# Six parallel agent tasks — 2026-10-25

Six copy-paste prompts for six agents working **at the same time**, in six isolated worktrees,
on six file-disjoint slices of the remaining V1 work. Three are hard, three are medium. None is a
UI task and none converts a design mock to Compose.

Sources: `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.10 (S6, S7, E2.2, E2.3), §5.3 (T-09,
T-11, T-12, T-19, T-20, T-24) and the E4.4 ROOM-lifecycle row. The owner decisions they depend on
are already closed (A7.2, A7.3, A7.5, A8.1, A8.2, A8.4, A8.6) — no prompt waits on an owner.

## Baseline and worktrees

- Baseline is **`origin/main` at `8b13b12`**. Pin the SHA, not the branch name.
- The primary checkout `D:\demo-test` is at `cd02a71` — **stale and dirty**. Never work in it, never
  read its file contents as the baseline, never reset/stash/clean it.
- One worktree per agent, outside the repo:

  ```powershell
  git -C D:\demo-test worktree add D:\worktrees\agent-s7-takeover -b agent/s7-takeover 8b13b12
  ```

  If the branch already exists, pick a new suffix (`agent/s7-takeover-2`); never force.
- Every agent leaves its work **uncommitted**. No commit, push, merge, rebase, reset, stash, clean.

## The six tasks

| # | Difficulty | Story | Deliverable | Write scope (module) | Verification |
|---|---|---|---|---|---|
| 1 | **Hard** | **S7** — O-03b: 7th takeover cancels an open ballot and fires during VOTE/WAIT | Persisted takeover counter, exactly-once 7th removal + charge, terminal `CANCELLED` ballot | `:engine`, `:functions`, `:services/model` | `:engine:jvmTest`, `:services:test`, `:functions:jsNodeTest` |
| 2 | **Hard** | **S6** — O-04b: ROOM bot fill, departed human scored Koz, no bot attribution | Stats-only ROOM/UNRANKED settlement + forced-KOZ attribution override | `:functions` (`Settlement*`) | `:functions:jsNodeTest` |
| 3 | **Hard** | **E2.2 + E2.3** — suit-aware With floor, Estimation Jump-In, repeated higher fast Super Call | `withFloorFor(…, winningSuit)`, explicit number-and-suit jump-in, re-openable fast Super Call | `:engine` (`Bidding.kt`) | `:engine:jvmTest` (+ report `:functions:jsNodeTest`) |
| 4 | Medium | **E4.4** slice — four-player ROOM start gate | Full-table start predicate + both server start paths gated | `:services` (`RoomDoc`, `RoomService`, `MatchService`) | `:services:test` |
| 5 | Medium | **E5.3** — reset/settlement race and idempotence | Deterministic race tests; reconciliation only if a test proves a defect | `:functions` (`SeasonReset*`) | `:functions:jsNodeTest` |
| 6 | Medium | **T-20** — Auto-Match session policy (pure) | New `MatchmakingSession.kt`: 2-min fill, 30-s vote, absolute tier band, human-only strength | `:engine` (new files only) | `:engine:jvmTest` |

## Write-scope disjointness

The six scopes share modules, never files. This is the property that makes simultaneous work safe.

| Shared module | Agent 1 (S7) | Agent 2 (S6) | Agent 3 (bidding) | Agent 4 (room gate) | Agent 5 (reset race) | Agent 6 (matchmaking) |
|---|---|---|---|---|---|---|
| `:engine` | `DisconnectVoteState.kt`, `LeaveMatch.kt` + their tests | — | `Bidding.kt`, `BiddingTest.kt` | — | — | **new** `MatchmakingSession.kt` + test |
| `:functions` | `TimeoutResolution.kt`, `TimeoutResolutionDb.kt`, `SeatReclaim.kt`, `DisconnectVoteFlow.kt` + their tests | `Settlement.kt`, `SettlementDb.kt`, `SettlementTest.kt`, `RpApplicationTest.kt` | — | — | `SeasonResetRun.kt`, `SeasonResetDb.kt` + a **new** race test | — |
| `:services` | `model/MatchModels.kt` | — | — | `model/RoomDoc.kt`, `RoomService.kt`, `MatchService.kt` + **new** `model/RoomDocTest.kt` | — | — |
| `:app` | — | — | — | — | — | — |

Rules that keep it disjoint:

- **No agent edits `native/functions/index.js`.** All six reuse existing callables; none adds an export.
- **No agent edits a Gradle file, `firestore.rules`, `firebase.json`, `.github/**`, or `docs/**`.**
- **No agent edits `native/app/**`.** Agent 4's gate is server-side; the cosmetic Start hint is reported, not fixed.
- Agents 1, 2, 3 and 5 all run `:engine:jvmTest` or `:functions:jsNodeTest`. Concurrent *runs* in
  separate worktrees are fine; concurrent *edits* are what the table forbids.

## Shared contract: the 7th-takeover provenance fields

Agents 1 and 2 are the only two that touch the same data, and they never touch the same file.
Freeze this contract:

| Field on `matches/{id}` | Type | Written by | Read by | Meaning |
|---|---|---|---|---|
| `takeoverRemovedUids` | `List<String>` (uids) | **Agent 1 only** | Agent 2, as **absent ⇒ empty** | Uids permanently removed by the 7th takeover |
| `takeoverRemovedCharges` | `Map<uid, Int>` | **Agent 1 only** | nobody in this wave | Recorded Koz: `1` in ROOM, `2` in RANKED/UNRANKED |

Hard invariants:

- Neither field is `removedUids` (Vote Kick), `timeoutRemovedUids` (15-timeout), `leftUids`
  (explicit leave) or `disconnectReplacedUids` (disconnect replacement). The mechanisms never write
  each other's provenance.
- Agent 1 must **not** write `timeoutRemovedUids` and must not increment `timeoutCounts/{uid}`.
- Agent 2 must read `takeoverRemovedUids` from the raw match map and must treat absent as empty, so
  it works whether or not Agent 1's field exists yet. Agent 2 writes neither list.
- Because the agents run in separate worktrees, Agent 2's worktree will not contain Agent 1's
  `MatchModels.kt` change. That is expected: Agent 2 parses the raw field, not the `MatchDoc` type.

## Shared constraints (repeated inside every prompt)

1. **Read first, in this order:** `AGENTS.md`, `docs/engineering/NODEJS_SAFE_USAGE.md`,
   `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (only the rows your prompt names), then the files in your
   write scope. `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` is the authority for story boundaries.
2. **Work only in your worktree.** The primary checkout is the user's; touching it is a failure.
3. **Preserve pre-existing changes.** If a file in your scope already differs from `8b13b12`, stop
   and report it — do not overwrite it.
4. **Do not commit, push, merge, rebase, reset, stash, clean, or delete branches.**
5. **Do not install Node/npm.** No `npm`, `npx`, `npm ci`, `node`. The Gradle wrapper owns the
   Kotlin/JS toolchain. `:functions:jsNodeTest` needs an already-approved Node 22 runtime; if it is
   absent, report it **NOT RUN** with the exact error. Never fake it.
6. **Emulator-gated tests self-skip locally.** Report them as **skipped**, never passed. A skipped
   emulator test is not evidence.
7. **Run only the commands your prompt lists.** No exploratory full builds.
8. **Report in the exact nine-field format below.** A missing field is an incomplete task.

## Required report format

Every agent ends its final message with exactly these headings, in this order:

```
STATUS: DONE | PARTIAL | BLOCKED
CHANGED FILES: <one path per line, each with +added/-removed>
CHANGE SUMMARY: <per file: what changed and why>
ACCEPTANCE CRITERIA: <every numbered criterion from the prompt, marked PASS / FAIL / NOT RUN,
                      with the test name or command that proves it>
COMMANDS RUN: <exact command -> result, including test counts>
NOT RUN: <exact command -> why>
KNOWN RISKS / ASSUMPTIONS: <including anything you could not verify>
SCOPE DEVIATIONS: <none, or file + justification>
HANDOFF NOTES: <what a reviewer or the next agent must know>
```

`DONE` requires every criterion `PASS`. If any is `FAIL` or `NOT RUN`, the status is `PARTIAL`
(or `BLOCKED` when you could not compile). Never round up.

## Deliberately excluded

- **Every UI task**, including the Room Start hint, the matchmaking screen, and the disconnect
  ballot UI. Agent 4 fixes the server gate only.
- **E4.3's takeover-trigger re-architecture** (decision timer + 10 s). Agent 1 counts and acts on
  the existing 50 s `InactivityClock` takeover; it does not replace the trigger. This is reported as
  a known gap, not silently claimed.
- **Four-report settlement convergence.** Agent 2 uses the match document's already-converged
  `finalScores` / `winnerIds`.
- **UNRANKED departure-charge consumption.** Agent 2 records the outcome only; the charge consumer is
  a later task. Reported.
- **O-15 matchmaking directionality.** Agent 6 uses absolute distance and does not change
  `isPoolEligible`'s direction.
- **`firestore.rules` changes.** Frozen. Admin SDK writes bypass rules; client writes do not, so no
  prompt asks a client to write a new field.

## Prompt files

| # | Difficulty | File |
|---|---|---|
| 1 | Hard | [`01-hard-s7-takeover-ballot.md`](01-hard-s7-takeover-ballot.md) |
| 2 | Hard | [`02-hard-s6-room-stats-attribution.md`](02-hard-s6-room-stats-attribution.md) |
| 3 | Hard | [`03-hard-bidding-floor-fast-super.md`](03-hard-bidding-floor-fast-super.md) |
| 4 | Medium | [`04-medium-room-four-player-start.md`](04-medium-room-four-player-start.md) |
| 5 | Medium | [`05-medium-season-reset-settlement-race.md`](05-medium-season-reset-settlement-race.md) |
| 6 | Medium | [`06-medium-matchmaking-session-policy.md`](06-medium-matchmaking-session-policy.md) |

## How to use a prompt

1. Create the worktree with the command in the prompt.
2. Start a fresh agent with the worktree as its working directory.
3. Paste the **entire** prompt file, unedited.
4. Do not paste this README — each prompt is self-contained.
5. When the agent reports, compare `ACCEPTANCE CRITERIA` against the prompt. Treat `PARTIAL` as
   normal for a hard task, not as failure.
