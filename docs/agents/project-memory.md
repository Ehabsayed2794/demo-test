# Project Memory - Local vs GitHub Truth (2026-10-24)

Refreshed 2026-10-24 from the 2026-10-19 edition by the agent-ops docs pass
(branch `agent/agent-ops-docs`, worktree `D:\worktrees\agent-docs`, base
`88217a3`). Every count, SHA, branch and story status was re-derived from git
rather than carried forward. Claims that could not be re-derived from git or a
file on the base are marked UNVERIFIED with the date, not silently kept.
Superseded passages are left verbatim under dated pointer notes, per this
repo's convention. The purpose is unchanged: agent-facing operational context
about where truth lives — not a spec.

## Source of truth

- `origin/main` = `88217a3` ("Merge pull request #152 from
  Ehabsayed2794/agent/s1b-option-b-impl"), fetched 2026-10-24. This is the
  current truth. (The 2026-10-19 truth was `c42480f`, PR #131; twenty merge
  commits landed in between — see "Merged since PR #131" below.)
- Local `main` = `b557c3e` (stale, behind `origin/main`; re-checked 2026-10-24
  via `git rev-parse main` — unchanged since 2026-10-19). Do not use it as a
  source of truth.
- The owner's primary worktree is the `docs/open4-open5-closure-2026-10-16`
  branch at `cd02a71`. On 2026-10-24 the whole project moved from
  `C:\Users\ehab.sayed\Desktop` to `D:`; that tree is now `D:\demo-test`, and
  agent worktrees live under `D:\worktrees\` and `D:\demo-test-*` (re-derived
  from `git worktree list`, 2026-10-24). It still carries pre-existing
  uncommitted app/services/docs changes and untracked
  `docs/agents/project-memory.md`, `docs/engineering/`, and settlement-client
  files (re-checked via read-only `git status --porcelain`, 2026-10-24).
  These are user-owned; do not reset, stash, clean, or overwrite them.
- `firestore.rules` on `origin/main` (2026-10-24): 2,568 lines, blob
  `1e346f5c8f550c97761ad8d43ce8d24fcde3af79`, SHA-256 pin
  `c03bdfd4792d215ef97218073aed08a4edc97aa7e4458f2b018928b603a85590`
  (matches `firestore.rules.sha256`, blob `dd00f318`). Compare the LF blob,
  not a CRLF working-copy file. (Supersedes the 2026-10-19 values — blob
  `fae82f9b`, 2,339 lines, pin `a30c5a96` — the rules changed in the
  season-reset/config rounds merged after PR #131.)
- PR #131 did not change `firestore.rules`, so no rules re-pin was needed
  (2026-10-19 record; the rounds merged since did re-pin — see the bullet
  above).

## PR #131 merged (2026-10-19)

- Merge commit: `c42480f78dac5e0130067a029096fec0bedb0c94`.
  12 files changed, +2,769/-76. (Re-derived 2026-10-24 via
  `git diff --shortstat c42480f~1 c42480f` — matches.)
- Delivered:
  - ranked-profile repair (`RankedProfileDb.kt` / `getMyRankedProfile`)
  - settlement + RP progression (`SettlementDb.kt` / `settleMatch`)
  - seasonal leaderboard (`LeaderboardDb.kt` / `getRankedLeaderboard`)
- `native/functions/index.js` exposes exactly nine callables:
  `engineSmoke`, `settleMatch`, `resolveDecisionTimeout`,
  `reclaimInactiveSeat`, `resolveDisconnectVote`, `castDisconnectVote`,
  `restoreDisconnectedSeat`, `getMyRankedProfile`, `getRankedLeaderboard`.
  (True as of PR #131. UNVERIFIED for current `origin/main` as of 2026-10-24:
  not re-derived, because checking it would require reading `native/`, which
  doc maintenance avoids — and PRs #133-#144 added server slices, so do not
  trust "exactly nine" on `88217a3`.)
- Pre-merge evidence: all 9 required checks passed; 292 functions tests in 17
  suites against the live emulator; 453 engine tests; a Node smoke check saw
  all nine exports. Post-merge CI was still in progress immediately after the
  merge and was not independently captured here. (2026-10-19 run record,
  kept as history.)
- The merged PR branch was deleted, and the temporary integration/agent
  worktrees and their local branches were removed. Unrelated worktrees and
  branches were preserved, including `origin/e6b-rp-application` at `042eb83`
  (still at `042eb83`, re-checked via `git ls-remote` 2026-10-24).

## Merged since PR #131 (re-derived from git, 2026-10-24)

From `git log --merges --oneline c42480f..origin/main`:

- #132 `agent/ranked-hardening-batch` — the 2026-10-19 coding batch
  (foundation + settlement + profile-read hardening). MERGED at `8339794`.
- #133 `agent/s56-ranked-pool-policy`, #134 `agent/s57-ranked-search-guard`,
  #135 `agent/s59-season-reset-core`, #136
  `agent/s64-leaderboard-read-hardening`, #137 `agent/s70-party-gate`,
  #138/#139 emulator teardown/terminate fixes, #140 `agent/v1-s63-abuse-tests`,
  #141 `agent/v1-s64-leaderboard-client`, #142 `agent/v1-s70-join-gate`,
  #143 `agent/v1-s64-season-reads`, #144 `agent/v1-s59-season-job`,
  #145 `agent/v1-settlement-emulator-fix`, #146, #147
  `agent/v1-settlement-tier-recheck`, #148
  `agent/v1-config-season-reset-rules-test`, #149
  `agent/v1-season-reset-lost-update`, #150 `docs/plan-reorder-2026-10-21`,
  #151, #152 `agent/s1b-option-b-impl` (current tip `88217a3`).
- Direct integration commits also landed on main (not via PR merges):
  "Integrate S1 Core: General Pass wiring" (`0ae9864`), "Integrate S1 fix"
  (`397c163`), "Integrate S4: fast-bid ties" (`5edfb34`), "Integrate S2:
  Sa'ayda extension wiring" (`8881fe0`).

## Parked design (do not build UI)

- S39, S41-S42, and S72 artboards were rejected by the owner on 2026-10-19.
  They remain uncommitted in their separate worktrees at baseline `1544c11`.
- The S72/S73/S74 runtimes are already merged; only the visual direction is
  parked. Do not build client UI from those artboards.
- Any future design round needs an owner-approved art-direction reference and
  one approved reference frame before mass production.

> **Position update 2026-10-24 (pointer; the owner's rulings above stand
> unchanged).** The worktrees moved to `D:\worktrees\` and one base changed
> (re-derived from `git worktree list` plus read-only `git status`):
> `demo-test-s39` and `demo-test-s72-artboard` are still at `1544c11` and
> still hold their uncommitted artboard files; `demo-test-s41` has moved to
> `0c37062` and holds modified `native/app/...` files (uncommitted,
> user/agent-owned — leave them alone). The rule stands: do not build client
> UI from the rejected artboards.

## Known residuals after PR #131

- `RankedProfileDb.normalizeProfile` checks each field individually but does
  not verify the model invariant when `tier`/`division` and `rp` are both
  present: `rankAt(rp)` must equal the named rung. A mismatch is currently
  served instead of failing closed. Addressed in the Agent 3 worktree
  (uncommitted).
- Agent 3 coverage note (pre-existing): absent `rp` + a present Bronze III
  pair is reasoned safe (rp defaults 0, consistent, migrates) but is not
  pinned by a direct test.
- `SettlementDb` reads `rp`, the five stored counters, and the claim's
  `you` movement through `Number.toInt()`. Fractions are truncated and
  out-of-range values can wrap; the counter path only rejects negatives after
  conversion. There is also no King-seat settlement fixture. Addressed in the
  Agent 2 worktree (uncommitted); placement `IN_PROGRESS` RP behavior remains
  an owner decision.
- `LeaderboardDb` still has an unbounded season read, omits the Arabic title,
  writes the raw uid in `S64_LEADERBOARD_SKIP` logs, and defaults to the
  hard-coded season `"s1"`.

> **Superseded 2026-10-24 (pointer; the residuals above are left verbatim as
> the post-PR-#131 record).** The hardening batch that addressed the
> `RankedProfileDb` and `SettlementDb` residuals merged as PR #132
> (`8339794`). The `LeaderboardDb` bullets were the target of the S64 rounds
> merged since (#136, #141, #143); whether each specific residual is now
> closed was not verified — that would require reading `native/` — so treat
> each bullet's closure status as UNVERIFIED as of 2026-10-24.

## Next backend work specified by the project plan

- **S70** server-side Ranked party eligibility gate: pure tier math exists in
  `PartyEligibility.kt`, but there is no Cloud Function / join gate yet.
- **S59** server-side season reset: pure reset math exists in
  `SeasonReset.kt`, but there is no season job, season document,
  concurrency-safe one-way guard, or R27 dry-run.
- **S56** Ranked pool resolver: `MatchmakingPort` is only a seam; there is no
  production resolver or queue implementation.
- These are larger feature slices that add callables / shared manifest
  entries and need sequencing and owner decisions. They are not part of the
  current three-task batch.

> **Superseded 2026-10-24 (pointer; the text above is left verbatim as
> history).** All three slices have since merged (re-derived from merge
> commits): **S56** via PR #133 (`agent/s56-ranked-pool-policy`), **S59** via
> PRs #135 (`agent/s59-season-reset-core`) and #144
> (`agent/v1-s59-season-job`), with follow-up rounds #148 and #149; **S70**
> via PRs #137 (`agent/s70-party-gate`) and #142 (`agent/v1-s70-join-gate`).
> None of them is "next work" anymore. AGENTS.md on this base names
> `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` as the current native V1 plan.

## Current coding batch selected (2026-10-19)

1. **Foundation:** add a pure progression-invariant helper in `:engine`
   (`progressionMismatch` / `requireProgressionConsistent`) and tests.
2. **Settlement hardening:** safe whole-number parsing, invariant enforcement,
   and a King-seat settlement fixture.
3. **Ranked-profile read hardening:** enforce the same invariant at the
   `RankedProfileDb` boundary.

These are bounded backend tasks that close accepted data-integrity gaps. They
add no callables, no UI, and no rules changes. Task 1 must land first; tasks 2
and 3 then touch disjoint files and may run in parallel only from separate
isolated worktrees.

### Batch status (2026-10-19; re-verified against git 2026-10-24)

- Agent 1 (foundation) is complete and accepted in
  `C:\Users\ehab.sayed\Desktop\demo-test-agent1-progression` on branch
  `agent/ranked-progression-invariant`. It adds `progressionMismatch` and
  `requireProgressionConsistent` to `RankedProfile.kt` and 8 tests. Independent
  verification: exactly 2 changed files, 31 engine suites / 461 tests / 0
  failures / 0 errors / 0 skipped, `git diff --check` clean.
  (2026-10-24: that worktree no longer appears in `git worktree list` — the
  path and test numbers are the 2026-10-19 record. Commit `4048a18`
  re-verified from git: parent `c42480f`, 2 files / +180.)
- The owner authorized one local foundation commit (no push): `4048a18` on
  `agent/ranked-progression-invariant`, 2 files / +180, based on `c42480f`.
- Agent 2 and Agent 3 worktrees were created from `4048a18`; both contain the
  helper. The agents must never reimplement it and must stop with BLOCKED if
  it is missing. Reports are reviewed against the actual worktree state before
  acceptance. (2026-10-24: those worktrees are now
  `D:\worktrees\demo-test-agent2-settlement` and
  `D:\worktrees\demo-test-agent3-profile-read` — paths corrected in place
  after the project's move to D:, per `git worktree list`.)
- Agent 2 (settlement hardening) report received 2026-10-19 and independently
  verified; ACCEPTED (uncommitted; no commit authorized beyond `4048a18`).
  Worktree `C:\Users\ehab.sayed\Desktop\demo-test-agent2-settlement`, branch
  `agent/settlement-input-hardening`. Exactly 2 changed files
  (`SettlementDb.kt` +98, `RpApplicationTest.kt` +308/-22), `git diff --check`
  clean, untracked `docs/engineering/` only. Code: one `wholeNumberInRange`
  gate (finite/integral/in-range) for `rp` (0..Int.MAX), the five counters
  (Int range; negative message kept, negative check after the gate), and
  recorded `you` movements (`previousRp`/`rp` >= 0, `delta` full Int range);
  `progressionMismatch` enforced in `parseRankedProfile` before return, never
  repaired; absent/null movement keys still replay without `you`. Tests: 13
  added (55 total in `RpApplicationTest`); `expectDenied` /
  `expectWholeSettlementDenied` widened to varargs asserting every fragment.
  Independent rerun of `:functions:jsProductionExecutableCompileSync
  :functions:jsNodeTest --rerun-tasks`: BUILD SUCCESSFUL, 17 suites / 305
  testcases / 0 failures / 0 errors (XMLs written by that run).
- Agent 2 deviation, confirmed against the engine: the King-seat table
  settles 3066 / 3051 / 3000 / 3000, NOT 3028 / 3016 — `RpEngine.computeBaseDelta`
  clamps negative-base outcomes (THIRD/KOZ) to <= 0, so +36 strength cannot
  lift them above 3000. Do not "fix" this with an engine change.
- Agent 3 (profile-read hardening) had already run when next inspected: its
  worktree `C:\Users\ehab.sayed\Desktop\demo-test-agent3-profile-read`, branch
  `agent/ranked-profile-read-hardening`, had both target files modified at
  19:22 and a full 17-suite test run at 19:26. Independently verified;
  ACCEPTED (uncommitted). Exactly 2 changed files (`RankedProfileDb.kt` +7,
  `RankedProfileDbTest.kt` +68): `progressionMismatch(profile)?.let { corrupt(it) }`
  at the end of `normalizeProfile`, before `loadOnce`'s merge, with the absent-
  field migration path untouched; 3 new tests (both mismatch directions, a
  consistent King/""/3000 profile served with no write, and King with rp 2999
  refused). Independent rerun: BUILD SUCCESSFUL, 17 suites / 295 testcases /
  0 failures / 0 errors. Report received afterwards and reconciles exactly:
  same 2 files, same 3 tests, same 17/295/0/0 totals, 16 tests in
  `RankedProfileDbTest`, same 12 emulator skips, no commit. The report's
  self-audit is not counted as independent evidence; the verifier rerun above
  is. Verdict: PASS WITH LIMITATIONS — the absent-rp + present Bronze III pair
  is reasoned safe but untested (pre-existing gap), and the emulator suites
  remain skipped by design.
- Emulator-dependent tests in both reruns: S51 x5, S62 x4, S64 x3 = 12
  self-skipped with `*_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset`
  (markers in XML `<system-out>`; the JUnit XML counts them green because the
  bodies early-return). Report them as SKIPPED, never as passed.
- All three tasks now form the PR branch `agent/ranked-hardening-batch`
  (3 commits: `4048a18`, `cce7247`, `e6f8e56`) in worktree
  `C:\Users\ehab.sayed\Desktop\demo-test-ranked-batch`. Combined verification
  on that exact tree: engine 31 suites / 461 tests / 0 failures / 0 errors;
  functions 17 suites / 308 tests / 0 failures / 0 errors; `git diff --check`
  clean; 6 files / +639/-22 vs `origin/main`; `index.js` and the rules pin
  untouched. Branch pushed 2026-10-19. PR #132 open against `main`:
  https://github.com/Ehabsayed2794/demo-test/pull/132. CI all green
  (2026-10-19): 9/9 checks, including the `functions` job whose
  Firestore/Auth/Functions emulator tier completed and passed the
  `_EMULATOR_SKIPPED` scan; mergeable=clean. Not merged or deployed; merge
  remains the owner's decision.
- The Agent 2/3 worktrees still hold the same changes uncommitted; the PR
  copies were verified byte-identical by SHA-256. Do not clean them without
  the owner's say-so. (Re-verified 2026-10-24 via read-only
  `git status --porcelain` in both worktrees: same two modified files each,
  plus untracked `docs/engineering/`.)

> **Superseded 2026-10-24 (pointer; the batch record above is left verbatim
> as history).** PR #132 is MERGED: `8339794` ("Merge pull request #132 from
> Ehabsayed2794/agent/ranked-hardening-batch") is an ancestor of
> `origin/main` = `88217a3` (re-derived via `git merge-base --is-ancestor`).
> Every re-derivable number in the record matches git: 3 commits
> (`4048a18`, `cce7247`, `e6f8e56`); 6 files / +639/-22 vs `c42480f`. The
> branch still exists on origin at `e6f8e56` (`git ls-remote`, 2026-10-24).
> The worktree `C:\Users\ehab.sayed\Desktop\demo-test-ranked-batch` no longer
> appears in `git worktree list` (2026-10-24). CI/PR-page facts (9/9 checks,
> mergeable=clean) are the 2026-10-19 record — not re-checked (no `gh`, no
> API calls in this refresh).

## Traps

- `docs/engineering/NODEJS_SAFE_USAGE.md` is untracked and is not on
  `origin/main`; the Node.js safety rule in `AGENTS.md` is a local uncommitted
  edit too. Read the policy before any Node.js/Gradle-JS test. It has been
  copied as an untracked file into the Agent 2/Agent 3 worktrees; never commit
  or delete those copies. If the file is absent in a fresh worktree, do not
  install Node or dependencies; use the existing Gradle wrapper only if the
  approved Node runtime is already available, or report the policy/runtime as a
  blocker.
  (2026-10-24: still true on `origin/main` today. Local branch
  `agent/agent-ops-docs` — worktree `D:\worktrees\agent-docs` — now tracks
  both the policy (`252e24f`) and the `AGENTS.md` rule (`7ba39cf`), pending
  the owner's push; until that lands on `origin/main`, the rule above stands
  for fresh worktrees. The policy was re-audited against the machine on
  2026-10-24 and every verifiable claim held: CI pins Node 22
  (`test.yml`, `r1-golden-verification.yml`, `android.yml`), no
  `.nvmrc`/`.node-version`/`engines` pin exists, and there is still no
  `node`/`npm` on the shell PATH.)
- `demo-test-roadmap` and the design worktrees track older/different state.
  Never treat them as `origin/main`. (2026-10-24: they now live under
  `D:\worktrees\` after the project moved to D:.)
- Do not run `git clean`, `reset --hard`, or a blanket rebase in the main
  worktree: it contains user-owned uncommitted work.
- GitHub Issues currently has zero open issues (checked 2026-10-19 via the
  public API; UNVERIFIED 2026-10-24 — not re-checked in this refresh); the
  repository's documented tracker is the `gh` CLI.
- `gh` is not installed or on PATH in this environment, despite the tracker
  convention (re-verified 2026-10-24: still absent). PR #132 was opened via
  the GitHub REST API using the token from `git credential fill` (Windows
  Credential Manager holds `git:https://github.com` and `gh:github.com`
  entries); `git push` uses the GCM helper with `GCM_INTERACTIVE=never` and
  `GIT_TERMINAL_PROMPT=0`. Never print the token.
