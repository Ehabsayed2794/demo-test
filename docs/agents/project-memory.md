# Project Memory — Local vs GitHub Truth (2026-10-15)

## Source of truth
- `origin/main` = `b7d9092` (Merge PR #112, 2026-10-14). FRESH after `fetch --prune`.
- Local `demo-test/main` = `6c67a62` (2026-10-07), 106 behind, strict ancestor of origin/main. STALE — never use as rules/docs source.
- `firestore.rules` truth = 2369 lines / SHA `e636d75238f8d807` (blob `70dcba0d13`). Stale copy in demo-test = 2042 lines / `4181a139`.
- CRLF on disk vs LF in git: compare blobs after normalization, not raw file sha256. Rules pin must hash the blob.

## S73 state (updated 2026-10-15 — Step 1+PR DONE)
- Code `45ff4c3` MERGED to main via PR #113 as `e98695a` (10 checks passed). Main now includes InactivityClock + tests.
- Spec `7dece9c` PUSHED 2026-10-15 to `origin/docs/roadmap-closure-decisions` (main untouched then, now merged code references resolvable spec).
- `45ff4c3` and `7dece9c` were disjoint (no file overlap, no conflict).
- Competing uncommitted draft in `demo-test-roadmap` (460/718/54 lines vs 7dece9c) — DO NOT MERGE per standing note. `7dece9c` is the only candidate truth.
- Partial stale edit in `demo-test` working tree (AGENTS.md +4, NATIVE_V1 +324, UI_UX +538) on old base — ignore for S73.

## Traps
- `docs/roadmap-closure` and `docs/roadmap-closure-decisions` both track `origin/main` (mislabeled upstream). Plain `push` refused, but `push origin HEAD` would hit main. Always push with explicit `origin <branch>:<branch>`.
- `docs/engineering/NODEJS_SAFE_USAGE.md` (10824 bytes) is untracked + server-absent. `AGENTS.md` on origin has 0 mentions; the +4-line rule exists only in demo-test working tree. One worktree loss deletes both.
- 5 prunable worktree registrations (crashlytics, e7-rules, e7-services, e7-wiring, s27-sound). 7 local-only branches (incl. pr-107, backup/phase2-before-sync).
- `botSeats` untouched by timeout path by design; `lastSeenAt` IS written every 30s by native heartbeat (`Heartbeat.kt:59` INTERVAL 30s via `PlayerPort.markActive`, armed `OnlineMatchViewModel.kt:242`, cancelled :260) but NEVER read back (no accessor) — so still unusable for RD19 decision proof. Legacy docs claiming "never written" describe old design-ui, wrong for native.
- Plan artifact: `steady-fjord-snipe.md` (Qoder plans dir, outside repo). Repo untouched. `7dece9c` now on `origin/docs/roadmap-closure-decisions` (Step 1 done); PR #113 merged as `e98695a` (10 checks).

## Safe order (requires owner approval per step)
1. Review + push `7dece9c` docs first (explicit branch push, no main).
2. Rebase `45ff4c3` onto new docs tip if needed, then PR (fast-forwardable now).
3. Stage 2 plan only after 1-2. No `lastSeenAt` heartbeat for RD19.
