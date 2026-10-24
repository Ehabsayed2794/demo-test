## Agent skills

### Native v1 plan + estimates

Before scoping or estimating native work, read `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md`
(2026-10-22, baseline `7a7e65c`): the current plan for native V1, covering rules completion,
Ranked runtime and settlement, work breakdown, estimates, open decisions and release gates.
`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md` (2026-09-21) is retained as historical context only — do
not use it for scope, sequencing or estimates.

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
