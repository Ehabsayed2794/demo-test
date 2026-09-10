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

- `reconnect.e2e.test.cjs` — tracked in issue #16. Moved here 2026-09-10
  after a green PR #15 went red on `main` with a NEW signature: the page
  reloaded mid-round resolved 6 phantom tricks (adapter `resolved:13`
  vs 7 on all other pages, tricksWon summing to 13 on a 28-card log),
  stalling R5. Suspected reload resume+replay race, flaky by timing.
  Re-entry criteria are in the issue. Do NOT move back without them.
