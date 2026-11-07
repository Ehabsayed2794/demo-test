# Stage 0 — E2: seeded corpus, JVM ↔ server parity

**Status: MEASURED 2026-10-29.** This is the exit evidence for the plan's E2 row
(`NATIVE_V1_IMPLEMENTATION_PLAN_V2.md`, T-23 / V1-C2: "≥200 seeds × FULL/MINI × Normal/Classic produce
zero unexplained JVM/server diffs").

Branch `stage0/e2-seed-corpus`, base `ddfeb0b` (main after PR #187).

## 1. What the experiment is

One Kotlin corpus harness compiled twice — once for the JVM, once as the exact Kotlin/JS production
artifact shape that `native/functions/index.js` consumes — and the two byte outputs compared. The point
is not "the engine is fast"; it is that the engine that runs **inside the server runtime** produces
identical decisions to the engine that runs **in tests and on device**.

- Harness: `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/MatchHarness.kt`
  (`playMatch` / `playCorpus`, a deterministic `JsonWriter`, and an FNV-1a 32-bit fingerprint over the
  rendered JSON text).
- Export surface: `native/stage0/src/jsMain/kotlin/Stage0Exports.kt` (`runMatch`, `runCorpus`).
- JVM driver: `native/stage0/src/jvmTest/kotlin/com/estemshan/stage0/MatchHarnessTest.kt` — 200 seeds per
  column, writes `build/stage0-parity/jvm-corpus-<GAMETYPE>-<MODE>.json`.
- Server driver: `wrangler dev` (workerd) serving the same four calls from the built JS artifact.

A match is played end to end (Dash phase → auction → confirm → estimates → card play → scoring) by four
bots (`BidBrain` + `PlayBrain`); only integers and seat ids are rendered, and maps are written seat-major
so the JSON has one canonical shape.

## 2. Runtime versions

| Piece | Version |
| --- | --- |
| Kotlin/JS | Kotlin 1.9.25, `compileProductionExecutableKotlinJs` (plain UMD artifacts) |
| Node | 22.14.0 (Gradle toolchain, `%USERPROFILE%\.gradle\nodejs\node-v22.14.0-win-x64`) |
| wrangler | 4.155.0 |
| workerd | 2026-10-23, `compatibility_date = "2026-10-23"` |

Artifact hashes at the time of the run: `stage0.js` `a13a4cdf00ad8d56e9b27244b8ae26c85695f65529152b6502523f7c89cc9b66`,
`Estemshan-engine.js` `f44a83483c988660d7de9bf703c49fde7b9756d7dd5e075b2cc71227376d6689`.

## 3. Result — 4 columns × 200 seeds, zero failures, byte-identical

| Game type / mode | Seeds | Matches completed | Rounds | Failures | JVM sha256 = workerd sha256 | workerd wall time |
| --- | --- | --- | --- | --- | --- | --- |
| FULL / NORMAL | 200 | 200 | 3,600 | 0 | `4c9aa66a1c52381ddd5d3c91c7c7e6c61f8f5524aa390b2723ff21ae6d3fc662` | 4.07 s |
| FULL / CLASSIC | 200 | 200 | 3,600 | 0 | `7ae36e7a8b79729db624915740c0c2c9640aee6e6c3f129caceb049f9e853a78` | 3.84 s |
| MINI / NORMAL | 200 | 200 | 2,000 | 0 | `a6c0477373a993dfc353486661ec2aa04b0467103a0c2f66759cfc14f6e27128` | 2.23 s |
| MINI / CLASSIC | 200 | 200 | 2,000 | 0 | `6a84a2e5220c0c4bc54597b1840230b394055be05bd1aafdfff044f846d3cffa` | 2.10 s |

Corpus fingerprints (FNV-1a over the rendered body): FULL/NORMAL `e32053de`, FULL/CLASSIC `15da8a34`,
MINI/NORMAL `2c62f9fe`, MINI/CLASSIC `646e1fef`.

`cmp` reports the two files identical, not merely equivalent: **zero** diffs, explained or otherwise, so
the plan's V1-C2 line is met.

## 4. The one defect the corpus found — and its fix

The first run completed **199 of 200** seeds in every column. Seed **194, round 5** stalled in all four,
always at the same seat: the Caller confirmed 6♠, a With seat matched at 6, a fourth seat had declared a
pre-bid Dash Call and never estimates — and the plain counter-clockwise estimation walk left the **With
seat last**, where the Caller's cap (6), its own-bid floor (6) and the forbidden-13 rule (13 − 7 = 6)
between them admitted no legal number.

The owner ruled the intended rule on 2026-10-29 — **the With players are asked first** — and the engine was
changed accordingly. Record: `docs/decisions/2026-10-29-with-first-estimation-order.md`. The re-run in §3
is **after** that change: seed 194 completes, and the four columns are now 200/200.

## 5. Caveats

- `:stage0` is a Stage 0 probe module. **No CI workflow runs `:stage0:*`** — this corpus is recorded
  evidence, not a gate. Re-run it by hand (below) when the engine or the bidding rules change.
- The harness records a per-seed failure instead of aborting the run, so one bad seed cannot hide the other
  199; the failure list is printed and stored in the JSON (`failures`).
- The comparison is JVM vs workerd. It says nothing about Android device behaviour; the same `:engine`
  code runs there, which is what makes the JVM leg meaningful.
- The corpus exercises bot decisions, so a change to `BidBrain` can move every hash. The numbers above are
  reproducible only against the same commit and the same bot code.

## 6. How to reproduce

```bash
# JVM corpus (writes native/stage0/build/stage0-parity/*.json)
native/gradlew -p native :stage0:jvmTest --rerun-tasks --no-build-cache

# Server corpus
native/gradlew -p native :stage0:compileProductionExecutableKotlinJs --rerun-tasks --no-build-cache
cp native/stage0/build/compileSync/js/main/productionExecutable/kotlin/*.js <wrangler-dir>/
#   <wrangler-dir>/worker.mjs calls stage0.runCorpus(first, count, gameType, scoringMode)
WRANGLER_SEND_METRICS=false node <wrangler>/bin/wrangler.js dev --port 8791 --ip 127.0.0.1
curl "http://127.0.0.1:8791/?first=1&count=200&gt=FULL&sm=NORMAL" -o workerd-FULL-NORMAL.json
cmp native/stage0/build/stage0-parity/jvm-corpus-FULL-NORMAL.json workerd-FULL-NORMAL.json
```
