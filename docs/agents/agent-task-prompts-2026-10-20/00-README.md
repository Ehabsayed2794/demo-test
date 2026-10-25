# Five parallel agent tasks — 2026-10-20

> **Historical record — noted 2026-10-25 (pointer; the body below is left
> verbatim).** This package is the 2026-10-20 briefing for the five-agent
> batch and is kept as history, not as a runnable brief — all five tasks
> have since merged (PRs #140, #141, #142, #143, #144). Its baseline
> `origin/main` at `3979028` is long superseded; the tip at the base of the
> branch that tracked this package is `88217a3`. Its paths are stale too:
> `C:\Users\ehab.sayed\Desktop\demo-test` no longer holds the repository
> (it is an empty directory as of 2026-10-25); the primary worktree is
> `D:\demo-test`, and the five task worktrees named below now live under
> `D:\worktrees\` (`s59-season-job`, `s70-join-gate`, `s63-abuse-tests`,
> `s64-leaderboard-client`, `s64-season-reads` — re-derived from
> `git worktree list`, 2026-10-25). The plan document this package cites,
> `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`, is superseded by
> `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` per `AGENTS.md`.

All five tasks are sourced from `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (E6b, S59/S63/S64/S70 and R27/RD29/RD30). None of them is a design task, and none converts a design mock to Android.

## Why these five

| # | Agent | Story | Write scope | Why it is safe to parallelize |
|---|---|---|---|---|
| 1 | Qwen 3.8 Flash | S59 — season document, transactional claim, reset job, R27 dry-run | `:functions` season files + `index.js` | Own new `Season*` files; only shared file is one `index.js` line |
| 2 | DeepSeek V4.1 Flash | S70 — server join gate + services join routing | `:functions` party-join files, `:services` join port/RoomService, `OnlineServices.kt` | Own new `PartyJoin*`/`RankDownJoin*` files; one `index.js` line |
| 3 | GLM 5.3 Flash | S64 — season-aware reads for leaderboard + ranked profile | `LeaderboardDb.kt`, `RankedProfileDb.kt` + their tests | No new production files; optional new `SeasonDocumentTest.kt`; does not touch the reset job |
| 4 | Mimo V.6 Flash | S63 — abuse/edge-case coverage tests only | New `S63AbuseCoverageTest.kt` only | Test-only; one new file; no production files |
| 5 | MiniMax M3.1 | S64 — typed leaderboard client read port | New `LeaderboardPort.kt` + its test | New `:services` file only; no UI, no `OnlineServices.kt` |

## Deliberately excluded

- **S56 client matchmaking screen** — the Matchmaking screen does not exist, so there is nothing to port.
- **S56 production pool resolution** — no match-creation builder exists in `:functions`; the pure policy is already merged.
- **S58 password gate and S71 "Play Unranked" conversion** — both need a `firestore.rules` field/transition change, and the rules are frozen. Do not ask any agent to touch them.

## Shared constraints, repeated inside every prompt

- Work only in the assigned worktree. Never write in `C:\Users\ehab.sayed\Desktop\demo-test` (the user's dirty checkout).
- Baseline is `origin/main` at `3979028`.
- Do not commit, push, merge, rebase, reset, stash, clean, or run destructive Git commands.
- Do not edit `firestore.rules`, `firebase.json`, `.github/**`, or `docs/**`.
- Do not install Node/npm packages or run `npm`/`npx`/`npm ci`. The Gradle wrapper manages the Kotlin/JS toolchain.
- Emulator-gated tests self-skip locally. Report them as **skipped/not run**, never passed.
- Leave every change uncommitted for review.

## Prompt files

| # | Agent | File |
|---|---|---|
| 1 | Qwen 3.8 Flash | [`01-qwen-s59-season-job.md`](01-qwen-s59-season-job.md) |
| 2 | DeepSeek V4.1 Flash | [`02-deepseek-s70-join-gate.md`](02-deepseek-s70-join-gate.md) |
| 3 | GLM 5.3 Flash | [`03-glm-s64-season-reads.md`](03-glm-s64-season-reads.md) |
| 4 | Mimo V.6 Flash | [`04-mimo-s63-abuse-tests.md`](04-mimo-s63-abuse-tests.md) |
| 5 | MiniMax M3.1 | [`05-minimax-s64-leaderboard-client.md`](05-minimax-s64-leaderboard-client.md) |

## Expected `index.js` conflict

Tasks 1 and 2 each add one line to `native/functions/index.js`. That is the only intentional overlap; resolve it by keeping both lines. Task 1 also changes the manifest guard to accept `__endpoint` (scheduled functions), which is a two-line change in the same file.
