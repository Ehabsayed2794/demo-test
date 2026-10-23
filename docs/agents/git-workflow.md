# Git workflow (ratified 2026-10-23, O-20)

Owner-approved workflow from the 2026-10-23 direction record
(`docs/decisions/2026-10-23-direction-multiplier-rounds-git.md` §4). It replaces any earlier assumption
that push, pull requests or merges are prohibited. No Git permission limit beyond the owner's standing
constraints may be invented.

## The one hard fact

**Git does not publish uncommitted changes from a machine's local worktree to other machines.** A commit is
the unit that travels; `push` publishes it; `fetch` receives it. Separate worktrees — even on the same disk
and sharing one object database — do not see each other's uncommitted files or index. Cross-environment
sharing therefore requires **commit + push + fetch**. Pushing an agent branch is the visibility mechanism;
merging early is not.

## Branch model

- **One shared integration branch:** `main`. It is the only line every agent and environment fetches.
- **One feature branch and one worktree per agent/sprint:** `agent/sN-<slug>` under `D:\demo-test-<sprint>`,
  cut from the current `origin/main`.
- Keep branches short-lived. Rebase on `origin/main` before handoff; never force-push a shared branch.
- The owner merges into `main` through the web UI; `gh` is not installed and must not be installed.

## Commit and push cadence

- Commit small, coherent, green checkpoints with the mandated identity:
  `-c user.name="Ehab Sayed" -c user.email="82008246+Ehabsayed2794@users.noreply.github.com"`.
- Push the agent branch at every checkpoint and always before handing off. Do not wait for a final report.
- Never use `git add -A` or `git add .`; stage named files so unrelated or secret files cannot slip in.
- Report the commit hashes you pushed.

## Seeing other agents' work

```sh
git fetch origin
git rebase origin/main   # or the branch named in the handoff
git log --oneline -10
git status
```

Never read another worktree's uncommitted files as if they were published, and never build on them.

## Shared-file discipline

One owner per shared file at a time: `QuickMatchFlow.kt`, `QuickMatchViewModel.kt`,
`OnlineMatchViewModel.kt`, `MatchService.kt`, `firestore.rules`. State the files you touch in your checkpoint
before you change them. When two branches touch the same file, land the coordinated change first and rebase
the others. No broad reformatting inside a feature change.

## Integration gate

- Targeted sprint tests **plus** the affected module suites (engine/app/services/functions) must pass.
- Firestore-crossing changes require the rules-emulator or instrumented suite.
- Report exact `tests` / `failures` / `skipped` counts from JUnit XML, not console summaries.
- No merge on red. Never merge unverified changes into `main` just to make them visible sooner.
- Isolated, fully green work may merge directly after review; rules, settlement and schema changes go
  through a pull request.

## Rollback

Revert with a new commit. Do not rewrite shared history. Keep migration and deletion actions in their own
auditable commits with the exact file list verified before and after.
