# M0 baseline confirmation — 2026-10-29

- **Date:** 2026-10-29
- **Status:** MEASURED — closes the baseline half of M0 (E0.1). The decisions half was
  already closed by the 2026-10-22/23 rulings.
- **Baseline commit:** `ab7e8cb4b247946127295230645c9dfda4906eea` (`origin/main`, the merge of
  PR #185). A fresh worktree was created from it; nothing from a working checkout was reused.

## Why this record exists

`docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.4 defines M0 as "Baseline accepted" and records
that the decisions half is done but **"M0 still needs the baseline confirmation itself"**.
E0.1 asks for a clean build and test baseline whose counts reproduce the recorded ones.

## Method

```
git worktree add -b docs/m0-baseline <path> origin/main      # ab7e8cb, clean tree
native/gradlew :engine:jvmTest :services:testDebugUnitTest \
                :app:testDebugUnitTest --rerun-tasks --no-build-cache
native/gradlew :functions:jsNodeTest --no-build-cache        # results dir removed first
```

`--rerun-tasks --no-build-cache` is load-bearing: `--rerun` alone did **not** force execution on
this Gradle 8.14.3 setup — both the engine and services test tasks reported `FROM-CACHE`/`UP-TO-DATE`
on the first two attempts. The final run shows all four tasks executing with no cache hits.
Counts were read from the JUnit XML under each module's `build/test-results/`, not from console
output.

## Measured baseline

| Module | Task | Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|
| engine | `:engine:jvmTest` | 38 | 617 | 0 | 0 | 0 |
| services | `:services:testDebugUnitTest` | 21 | 298 | 0 | 0 | 0 |
| app | `:app:testDebugUnitTest` | 24 | 242 | 0 | 0 | 0 |
| functions | `:functions:jsNodeTest` | 38 | 760 | 0 | 0 | 0 |
| **total** | | **121** | **1,917** | **0** | **0** | **0** |

Rules pin pair at the same commit — file hash equals the pin, so the audited bytes are the tested
bytes:

```
firestore.rules.sha256: 64d2374435a1ef87922c8f2b058d27d59c75f4e6b351fc4131fd3b5d49852a67
sha256sum firestore.rules: 64d2374435a1ef87922c8f2b058d27d59c75f4e6b351fc4131fd3b5d49852a67
firestore.rules: 2,780 lines
```

CI on the same commit (all four post-merge runs `completed` / `success`):

| Workflow | Run |
|---|---|
| Android (Native Kotlin) | https://github.com/Ehabsayed2794/demo-test/actions/runs/40824328236 |
| Test (Real Emulator Enforced) | https://github.com/Ehabsayed2794/demo-test/actions/runs/40824328238 |
| deploy-production | https://github.com/Ehabsayed2794/demo-test/actions/runs/40824328234 |
| R1 Golden Verification (Manual) | https://github.com/Ehabsayed2794/demo-test/actions/runs/40824328340 |

## What this confirms, and what it does not

**Confirmed.** All four module suites build and pass from a clean tree at `ab7e8cb`, with zero
failures, zero errors and zero skipped tests in the JUnit XML, and the rules pin pair is consistent.

**Not confirmed locally — emulator tier.** The local functions run had no Firestore emulator
(there is no `firebase-tools` on this machine), so 24 emulator-gated tests self-skipped and printed
`_EMULATOR_SKIPPED host=FIRESTORE_EMULATOR_HOST-unset`. Their JUnit `skipped` attribute stays `0`;
the markers in `<system-out>` are the evidence. The authoritative emulator execution is CI's
`Test (Real Emulator Enforced)` run above, which starts Firestore and Auth through
`emulators:exec` and fails closed on those markers.

## Defect found: the plan's recorded counts are stale

The plan still carries the counts from its authoring date:

| Location | Recorded | Measured 2026-10-29 | Delta |
|---|---|---|---|
| §5.2 / V1-C3 / §5.7 scale rows | engine 473, services 189, app 183, functions 516 (1,361) | 617 / 298 / 242 / 760 (1,917) | +556 |

The plan body is left verbatim; this record is the correction. Any future "reproduce the recorded
counts" check must use the table above, not the 1,361 figure.

## Two operational notes for the next run

- The plan's E0.1 command names `:services:test`, which is a lifecycle task that runs **both**
  `testDebugUnitTest` and `testReleaseUnitTest` — the services suite executes twice. Use
  `:services:testDebugUnitTest` for a single execution (it is the variant CI runs).
- `--rerun` alone is not enough on this Gradle version; see Method above.

## What M0 still needs

- **E0.3** — wire T-01…T-25 into the task tracker.
- **E0.4** — draft the runtime ADR skeleton that Stage 0's memo will fill in.

Neither needs an owner decision. The six still-open owner items (O-12, O-13, O-15, O-16, O-17,
O-18) do not block P0 close-out or Stage 0 (§5.4.2 P1 entry: "P0; access to a free-tier account").
