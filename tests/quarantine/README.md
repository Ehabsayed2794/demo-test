# Quarantine — E2E tests excluded from CI discovery

`scripts/run-tests.mjs` collects only top-level `tests/*.cjs|*.test.js`
(non-recursive), so files in this directory never run in CI. They remain
runnable manually from the repo root, e.g.:

```
node tests/quarantine/reconnect.e2e.test.cjs
```

(run under `firebase emulators:exec` like the rest of the suite; see
`package.json` `test:ci`.)

## Active quarantines

None.

## Returned (issue #16 re-entry)

- `reconnect.e2e.test.cjs` — quarantined here 2026-09-10 (mid-round
  reload resolved 6 phantom tricks: adapter `resolved:13` vs 7,
  tricksWon summing to 13 on a 28-card log, stalling R5), returned to
  `tests/` after the reload resume+replay product fix in
  `design-ui/match/index.html`'s `maybeEnterPlayPhase()` (discard
  persisted playState before `TableEngine.initState()`, so the
  from-scratch authoritative replay always converges onto a
  round-fresh engine) plus the deterministic regression coverage in
  `tests/reload-resume-replay.test.cjs`. Root cause was NOT timing:
  the reloaded trick's leader == caller == trick-1 leader (~1/4 of
  deals) aligns the replayed history turn-for-turn, re-resolving every
  historical trick. Close issue #16 with the fixing commit + CI
  evidence (criterion 3); criterion 2 needs repeated green CI runs.
