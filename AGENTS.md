## Agent skills

### Native v1 plan + estimates

Before scoping or estimating native work, read `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (2026-09-21): the
hour-level breakdown of remaining work to release, the critical path, owner decisions D1–D4, and the
toolchain/API-36 blockers. It is the project's only plan document — the older migration/master/architecture
plans were superseded and removed (recoverable from git history if needed).

### Owner rulings of record (2026-10-20)

Read `docs/decisions/2026-10-20-profile-story-s75-and-firebase-free-plan.md`: Profile editing is its own open story **S75**, and the project stays on the **free Firebase plan** (plan decision D5 — with an unresolved Cloud Functions/Blaze conflict to verify before S42 deploys).

### Owner rulings of record (2026-10-21, later)

Read `docs/decisions/2026-10-21-server-authority-and-owner-rulings.md` (server-authoritative Ranked, rule rulings A6, stats, backup, retention) and `docs/decisions/OPEN_QUESTIONS.md` before scoping Ranked, backend, stats, or season work. Items marked proposal or open there are **not** owner-approved.

### Issue tracker

Issues live in GitHub Issues via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five canonical labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout (`CONTEXT.md` + `docs/adr/` at root). See `docs/agents/domain.md`.
