# Stage 0 — E5: measured per-match cost and the free-tier capacity model

**Status: MEASURED 2026-10-29.** This is the exit evidence for the plan's E5 row
(`NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3): "Instrument a full match; build a per-match cost
model" — pass criterion "Model exists; capacity at target usage computed".

Branch `stage0/e5-capacity-model`, base `64bd2e9` (main after PR #189).

> **Addendum, 2026-10-29 (owner rulings A–G; the sections below are left verbatim).** After this
> report was measured, the owner ruled on the design assumptions it was built under
> (`docs/decisions/2026-10-29-stage0-capacity-persistence-transport-failover.md`): persistence
> per **completed Trick** (conditional on E9's recovery validation) with the auction still
> per-action; **bots inside the authoritative server** (not counted as inbound client requests);
> **WebSocket transport preferred**; a **100 / 125 / 150 matches-per-day planning range**; the
> **90% quota guardrail** with backup failover for new matches; and **Cloudflare preferred on
> cost grounds** (O-06 unchanged). Consequence: **§3–§5 below are the preliminary
> action-count-based model.** The ≈ 1,135 requests/match figure and the ≈ 1,135 writes/match
> (and its ×2 bound) are **not** confirmed production values — the requests figure is the
> measured engine action stream of an all-bot replay, and the writes are modelled. E6 must
> recalculate for the WebSocket + server-side-bot design across the three target scenarios, and
> label every input measured, modelled, unknown, or needing a real Cloudflare experiment
> (record §8).

## 1. What the experiment is

One question: **what does one complete match cost the proposed runtime in requests, CPU and
storage, and how many matches a day does the free tier carry?** It feeds gate **G5** ($0 marginal
infrastructure) and risk **R-04** (free-tier limits exceeded by real usage), and it is the model
E6 (synthetic 90% workload), E10 (retention/cleanup) and E12 (hibernation) all build on.

The runtime under test is the ADR's option B — Cloudflare Workers + SQLite-backed Durable
Objects + D1 (`docs/adr/0003-runtime-choice.md`), which is still a **PROPOSAL** (O-06).

## 2. Method

### 2.1 The probe

`native/stage0/src/commonMain/kotlin/com/estemshan/stage0/CapacityProbe.kt` replays the E1/E2
match driver verbatim — same seeded deals, same bot roster, same guard rails — with a monotonic
clock around each engine call. Per match it records:

- **requests** — every `emit`/`emitPlay` the driver submits is one client action, and in the
  Worker→DO architecture every client action is one inbound message (HTTP POST or WebSocket
  message). The probe counts what the engine produces; it invents no traffic.
- **CPU** — `decideNs` (the bot brain: server-side work for a bot-filled seat), `applyNs`
  (reducer + session update: the server's validate → reduce → persist point for *any* seat),
  and `transitionNs` (server-only work between requests: bidding close, round scoring, round
  advance). Wall-clock over synchronous compute, which on a single-threaded isolate is the CPU
  figure to model with.
- The battery document splits a deterministic `counts` section (FNV-1a fingerprinted, so the JVM
  and workerd batteries must agree bytewise on every count) from a `timings` section (never
  fingerprinted — wall-clock is the quantity that legitimately differs).

Storage is **modelled, not measured** — no Durable Object exists yet (E9 builds the first one).
The ADR's option B persists the match state once per applied action (that is what makes recovery
at every boundary possible, which E9 will test) and settles once per match into D1. The one thing
the model needs from the engine, the size of the persisted state, is bounded by the rendered E2
match document — measured, per seed, on the JVM leg.

### 2.2 Runtimes and artifacts

| Piece | Version |
| --- | --- |
| Base commit | `64bd2e9` (main, merge of PR #189) |
| Gradle / Kotlin / JDK | 8.14.3 / 2.0.21 / Temurin 17.0.20.1 |
| Kotlin/JS target | `compileProductionExecutableKotlinJs` (plain UMD) |
| Node (wrangler host) | 22.14.0 portable, `C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe` |
| wrangler | 4.155.0 (`D:/cache/stage0/node_modules`, pinned lockfile; no packages installed) |
| workerd | local `wrangler dev --local`, `compatibility_date = "2026-10-29"` |

Artifact sha256 (built from this branch). Against E3's build: the engine's *source* is unchanged
(the E3 range only renamed test functions, which do not compile into the production bundle), so
`Estemshan-engine.js`'s 3-byte difference is codegen layout, not code
(`3e1172c7a9bad34d93adb2147d5a9a368d447756bb39588eb1b13f67b32e5dab`). The stdlib part grew
195,010 → 218,292 bytes because the probe pulls in `kotlin.time`, and `stage0.js`
(`ff0f6e07c772a01041d30382d22cad60e8fdf81e5f61d6a88b1686962eedce03`) grew with the probe itself;
bundle-size questions remain E4's, not this experiment's.

**Node.js usage evidence** (per `docs/engineering/NODEJS_SAFE_USAGE.md` §F): portable Node
22.14.0 by explicit path; the only scripts executed were the pinned local `wrangler.js` and
inline `node -e` aggregation reading local JSON with built-in modules only; **no packages
installed, no network access beyond local HTTP on `127.0.0.1:8974`**; `WRANGLER_SEND_METRICS=false`;
files written only under `D:/cache/stage0/e5/` and the Gradle build directory. The wrangler log
records **zero** `crashed unexpectedly` lines inside the measurement window (one clean start,
stopped deliberately after the run).

### 2.3 The batteries

| Battery | Seeds | Fingerprint (counts) |
| --- | --- | --- |
| FULL / NORMAL (primary) | 1–50 | `c8248057` |
| FULL / CLASSIC | 1–20 | `8a86e840` |
| MINI / NORMAL | 1–20 | `b450e725` |
| MINI / CLASSIC | 1–20 | `6ed8efd8` |

**All four fingerprints are identical on the JVM and inside workerd** — the request counts are
pure functions of the seed, exactly as E2/E3 require. Three warm-up matches preceded the measured
JVM run (JIT); on workerd the first request (cold, 1,747 ms wall for the whole 50-match battery)
and the second (warm, 2,356 ms) were both recorded, and the per-action clocks inside the document
are the CPU figures modelled below.

## 3. Result — what one match costs

### 3.1 Requests (measured, both runtimes agree)

| Battery | Rounds | Requests / match (mean / p50 / p90 / max) | Of which bidding | Of which cards |
| --- | --- | --- | --- | --- |
| FULL / NORMAL | 18 | **1,130 / 1,126 / 1,154 / 1,186** | 194 (incl. 3.5 redeals/match) | 936 (exactly 52 × 18) |
| FULL / CLASSIC | 18 | 1,125 / 1,121 / 1,143 / 1,166 | 189 | 936 |
| MINI / NORMAL | 10 | 603 / 602 / 618 / 621 | 83 | 520 |
| MINI / CLASSIC | 10 | 603 / 602 / 618 / 621 | 83 | 520 |

The card count is exact by the rules (13 tricks × 4 seats per round). The bidding count includes
the all-pass redeals (174 across the 50 primary seeds) — real traffic the product pays for.
A match also costs a fixed, modelled lifecycle of **5 requests** (1 create + 4 joins), so the
planning figure is **≈ 1,135 requests per FULL match**.

### 3.2 CPU (workerd — the production runtime; JVM figures in parentheses)

| Measure | mean | p50 | p90 | max |
| --- | --- | --- | --- | --- |
| Apply only, per request (a human action) — ms | 0.008 | 0.007 | 0.013 | **0.020** |
| Decide + apply, per request (a bot-filled seat) — ms | 0.031 | — | 0.057 | **0.067** |
| Whole match, apply only — ms | 8.8 | — | 15 | 22 |
| Whole match, with four bot brains — ms | 35.4 | — | 63 | 75 |
| Server-only round transitions per match — ms (JVM leg) | 0.66 | — | 0.79 | 1.0 |

(0.005 / 0.027 / 5.9 / 30.3 on the JVM — the same story one notch faster.)

**Verdict: CPU is not a constraint.** The worst single measured action is 0.067 ms — **0.7% of
the free plan's 10 ms CPU/request limit**, three orders of magnitude of headroom. Even the whole
engine work of a match (75 ms p90 with four bot brains) fits inside the CPU budget of eight
requests. The probe measures engine CPU only — the Worker glue (routing, JSON, auth) adds a small
constant on top, which is exactly what E6's synthetic workload will exercise end-to-end.

### 3.3 Storage (modelled on the measured counts)

| Item | Value | Basis |
| --- | --- | --- |
| State persists per match | ≈ 1,130 rows | 1 row written per applied action (the durability requirement E9 tests) |
| Settlement per match | 5 rows | 1 match-result row + 4 RP ledger rows in D1 |
| **Rows written per match** | **≈ 1,135** (conservative ×2 → 2,270) | Cloudflare bills KV-style `put()` as rows written via a hidden SQLite table and does not publish the rows-per-put mapping, so both bounds are carried |
| Rows read per match | ≈ 1,135 | 1 state-row read per request + settlement reads (budget 5M/day — never binding) |
| State size per persist | ≤ 5.5 KB | Measured upper bound: the rendered match document (mean 5,488 / max 5,514 chars, identical on both runtimes) |

## 4. The capacity table

Free-tier limits verified against Cloudflare's docs on 2026-10-29: Workers — 100,000 requests/day,
10 ms CPU/request; Durable Objects (SQLite, Free) — 100,000 requests/day *including WebSocket
messages* (incoming metered 20:1 on the paid page; the free page lists no separate ratio),
13,000 GB-s duration/day, 5,000,000 rows read/day, **100,000 rows written/day**, 5 GB storage,
30 s CPU/request; D1 (Free) — 5,000,000 rows read/day, 100,000 rows written/day, 500 MB/database.

Capacity = floor(limit ÷ per-match cost), at 100% and at the 90% G5 asks for:

| Meter | Per-match cost | Limit/day | Matches/day | At 90% |
| --- | --- | --- | --- | --- |
| Worker requests, HTTP-per-action transport | 1,135 | 100,000 | **88** | 79 |
| DO requests, WebSocket transport (20:1 incoming ratio) | ≈ 66 req-units (57 message-units + 4 connections + lifecycle) | 100,000 | ≈ 1,515 | ≈ 1,363 |
| DO rows written, **per-action durability** | 1,135 (×2 bound: 2,270) | 100,000 | **88** (44) | 79 (40) |
| DO rows written, per-trick in play + per-action in bidding | ≈ 433 (866) | 100,000 | 230 (115) | 207 (103) |
| D1 rows written (settlement only) | 5 | 100,000 | 20,000 | 18,000 |
| CPU per request | 0.067 ms worst | 10 ms | not binding | — |
| DO duration | — | 13,000 GB-s | E12's question (hibernation) | — |
| D1 / DO storage | ≤ 5.5 KB/snapshot | 5 GB | E10's question (retention) | — |

> **Pointer, 2026-10-30 (E7 measured the settlement write protocol; the rows above are left
> verbatim).** The **"D1 rows written (settlement only) = 5"** row and the **"Settlement per match
> = 5 rows"** row are **not a protocol figure** — they are the ledger's **table** rows (1
> match-result row + 4 RP rows), and they exclude two things the protocol actually writes:
> the **intent queue** (insert + ack + cleanup delete) and the **backup copy**. E7 measured the
> whole settlement protocol against a local D1, from D1's own `meta.rows_written` — which counts
> **table rows and index rows**: **RP 10 + OUTBOX 5 + RP_BACKUP 10.4 = 25.4 rows per match** at a
> 5-match backup cadence (**29** backing up every match), against the **5** above. The effect on
> this table's D1 row: **2,540 / 3,175 / 3,810 rows/day at 100 / 125 / 150 matches/day —
> 2.54% / 3.175% / 3.81% of the 100,000/day limit** — so **this axis is still not the binding
> meter** (E6's binding meter is DO rows written at 1.6–2.3×), but the **ceiling on this axis
> falls from 18,000 to 3,543 matches/day** at the 90% guardrail. **Modelled, not measured on
> Cloudflare:** the local D1's `rows_written` semantics and the Free plan's enforcement are
> **[x] — a real Cloudflare account**. Report:
> `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` §10 (pending owner decision D-E7-3:
> which figure the model carries, and whether the three stores are three databases or one).

## 5. The finding

1. **CPU is a non-problem** — three orders of magnitude of headroom per request, on both legs.
   Any capacity limit this runtime has is a **counting** limit (requests/day, rows written/day),
   never a compute limit.
2. **Under per-action durability the free tier carries ≈ 88 matches/day** (~79 at 90%) —
   ≈ 352 seat-sessions, or roughly **25–30 actively playing users/day at 3–4 matches each**.
   The binding meter is **rows written/day**, and it binds under *every* transport: a WebSocket
   (20:1 metering) lifts the requests axis to ≈ 1,500 matches/day but cannot reduce the writes,
   because the per-action persist *is* the recovery guarantee E9 will test.
3. **The one real lever is write granularity.** Batching the play-phase persist per trick
   (4 actions → 1 write) while keeping bidding per-action cuts a FULL match to ≈ 433 rows and
   lifts capacity to ≈ 230 matches/day (≈ 115 at the ×2 bound). That is an architecture trade —
   a crash between tricks loses up to three cards, recoverable only through the client's durable
   intent + idempotent replay (§5.6) — and it is E9's criterion that decides whether the trade is
   safe. Per-action and per-trick both fit the *rules*; the recovery window is a design decision,
   not a measurement.
4. **The plan names no numeric target usage**, so "capacity at target usage" is presented as the
   table above: capacity per meter. Picking the target (e.g. "500 matches/day") and therefore
   whether per-action durability is affordable on the free tier is **owner input that E6 needs
   before it can run**. For scale: 500 matches/day ≈ 567k rows written/day — 5.7× the free
   tier, inside Workers Paid's included 50M rows/month — a plan-B fact for O-06, not a G5 pass.

## 6. What E5 does **not** prove

- **No Durable Object exists yet.** The storage model is modelled from the ADR's architecture and
  the measured action counts, not measured against a real DO. E9 (recovery) and E10 (retention/
  cleanup) own the first real storage measurements; the rows-per-put mapping for KV-style writes
  is not published by Cloudflare, hence the ×2 bound.
- **The probe measures engine traffic only** — matchmaking, disconnect votes, takeover clocks,
  and profile/stats traffic are outside "one complete match" and add real requests. E6's
  synthetic workload must add them.
- **Local workerd is not production.** No network RTTs, no multi-tenant noisy neighbours, no
  production duration accounting (13,000 GB-s/day depends on hibernation behaviour — E12).
- The WebSocket 20:1 metering is quoted from the paid pricing page; the free plan's limit text
  says messages count as requests without stating the ratio. E12 should confirm on a real free
  account.

## 7. How to reproduce

```bash
# ── JVM leg ─────────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:jvmTest --rerun-tasks --no-build-cache
#   → native/stage0/build/stage0-e5/capacity-jvm-*.json, state-size-jvm.json

# ── Server leg ──────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:compileProductionExecutableKotlinJs --rerun-tasks --no-build-cache
cp native/stage0/build/compileSync/js/main/productionExecutable/kotlin/*.js D:/cache/stage0/e5/
cd D:/cache/stage0/e5
WRANGLER_SEND_METRICS=false XDG_CONFIG_HOME=D:/cache/stage0/config \
  "C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" \
  D:/cache/stage0/node_modules/wrangler/bin/wrangler.js dev --local --port 8974 --ip 127.0.0.1
curl "http://127.0.0.1:8974/?what=capacity&first=1&count=50&gt=FULL&sm=NORMAL" \
  -o workerd-capacity-FULL-NORMAL.json

# ── Compare ────────────────────────────────────────────────────────────────
# counts.fingerprint must be c8248057 on both legs (and 8a86e840 / b450e725 / 6ed8efd8
# for the secondary batteries). Timings are compared against the 10 ms budget, not each other.
```

## 8. What the owner still has to decide

1. **The target usage number** (matches/day or daily players) — the input E6's 90% question is
   defined against, and the number that decides whether the free tier is sufficient at all.
2. **The durability granularity** (per-action vs per-trick persist) — an architecture decision
   inside P3 that moves capacity by 2.6× and is validated by E9's recovery criterion.
3. **The transport** (HTTP-per-action vs WebSocket) — decides whether the requests/day axis
   binds (~88/day) or not (~1,500/day); E12 confirms the free-plan WS metering.
4. **O-06 itself** — unchanged: this model is evidence, not a decision. The ADR slot stays open
   until E6–E13 give it the rest of its inputs.
