# Stage 0 — E6: the target workload (100/125/150 matches/day) against the free tier

**Status: PASS WITH LIMITATIONS (2026-10-29; second review round applied 2026-10-30; third
review round applied 2026-10-30).** The
action counts, per-seat loads and CPU costs are **measured** — locally, on the JVM and inside
local `workerd`. The capacity numbers are **modelled** from those measurements plus the owner's
2026-10-29 rulings; the platform-side metering facts they depend on are **unverified** until a
real Cloudflare account measures them (§14). The pass criterion ("measured headroom or a
documented overrun") is met as **headroom on every meter under the documented readings — led by
the binding meter, DO rows written, at only 1.6–2.3× — and a documented overrun under the
conservative readings**, with each meter's usage, headroom and thresholds in §5.

Branch `stage0/e6-target-workload`, base `e624879` (`stage0/e5-capacity-model` — E5 plus the
owner rulings A–G, `docs/decisions/2026-10-29-stage0-capacity-persistence-transport-failover.md`).

**Rounding conventions (stated once, applied everywhere):** percentages round **half up to one
decimal**; absolute demands (rows, requests, GB-s) round **half up to integers** (112.5 → 113);
request-units per match use the **ceiling** (a fractional message is still a message); capacity,
trip points and per-match thresholds derived from a daily limit **floor** (667 messages × 150
would exceed a 100,000 limit, so the threshold is 666); CPU milliseconds round half up to
**two decimals**. **Arithmetic basis:** every row figure is computed from the unrounded measured
mean **427.92 rows per FULL match** — the "428" appearing in prose is its rounding, never the
computation input.

## 1. What the experiment is

E6 runs under the owner's rulings, which change what "the primary usage scenario" is:

- the target is the **planning range 100 / 125 / 150 completed matches per day** (three labelled
  scenarios, not a forecast — ruling D);
- the transport is **WebSocket-preferred** with server-side **bots inside the authority**
  (rulings B/C), so E5's "one request per action" premise no longer holds;
- card play persists **per completed Trick**, the auction per action (ruling A, conditional on
  E9 — see §8 for why that condition is load-bearing);
- the **90% quota guardrail** (ruling E) must be evaluated: does it protect ongoing matches, and
  when does it fire?

Every input is labelled **[M] measured**, **[m] modelled (assumption stated)**, **[u] unknown**,
or **[x] needs a real Cloudflare experiment**. No real Cloudflare account, deployment or
quota-consuming experiment was run.

## 2. Method — three self-contained probes

- **`CapacityProbe.kt`** (E5, untouched): totals per match.
- **`CapacityMixProbe.kt`** (E6): replays the same driver verbatim and attributes every action
  and every clock to the **acting seat** — the human/bot mix is applied as a model on top of
  measured per-seat loads; no distribution is invented.
- **`CapacityChainProbe.kt`** (E6, review item 6): measures the **longest consecutive run of
  server-side actions that executes without an intervening human message** — the quantity a
  CPU-per-request budget actually bounds. Chain CPU includes the brain and the reducer and
  conservatively attaches the server-only round-boundary work (trick resolution, round scoring,
  round advance) to the action that preceded it.

Batteries: seeds 1–50 FULL/NORMAL (primary) and 1–20 for the other combos. **All count
fingerprints are byte-identical between the JVM and workerd**: mix `ee328556` / `31cacdd4` /
`81ed9678` / `b1116c8d`; chain `f68532ca` / `2749a715`. Same Node/wrangler evidence rules as E5
(portable Node 22.14.0 by explicit path, pinned local wrangler 4.155.0, no installs, local HTTP
only, `WRANGLER_SEND_METRICS=false`, **0 crashes** in every log).

**Measurement caveats that matter for production:**

- All CPU figures here are local **wall-clock around synchronous engine calls**. In deployed
  Workers, `performance.now()` and `Date.now()` do **not** advance during CPU work (Workers
  runtime docs), so this instrumentation cannot be reused in production; production CPU must
  come from Cloudflare's own CPU metrics.
- The chain figures cover **engine work only** — no JSON (de)serialisation, no SQL execution, no
  WebSocket framing or auth. The real per-request margin is **smaller than the measured numbers
  look**, and its size is **[u] unmeasured** until a real deployment.
- Which CPU limit even applies is a platform question: the Workers Free plan states **10 ms CPU
  per invocation**, while the Durable Objects limits page states **30 s CPU per request**
  without separating Free from Paid. The chunking conclusion in §3 conservatively assumes the
  **10 ms** reading applies to match handling; **[x]** the real-account experiment (§14) decides
  which limit governs.

## 3. What one seat costs (measured, FULL/NORMAL, 50 seeds)

| Measure | p1 | p2 | p3 | p4 |
| --- | --- | --- | --- | --- |
| Card actions per match | 234 | 234 | 234 | 234 |
| Auction actions (mean / min / max) | 51.4 / 46 / 66 | 47.6 / 40 / 62 | 47.3 / 36 / 63 | 47.6 / 40 / 60 |
| Brain CPU per match, ms (mean / p90) | 5.6 / 11 | 7.3 / 13 | 15.7 / 26 | 15.7 / 24 |
| Apply CPU per match, ms (mean / p90) | 5.3 / 11 | 5.1 / 9 | 4.6 / 7 | 5.5 / 9 |

Totals: **1,129.9 actions/match** (mean; 936 cards + 193.9 auction actions, p90 218; 3.48
all-pass redeals/match), **234 trick checkpoints**, ~10.8 auction actions per round. Seats are
nearly balanced (each ≈ 25% of the traffic). MINI/NORMAL (20 seeds): 603.2 actions (520
cards, 83.2 auction, 130 checkpoints, 10 rounds).

**Chains (the CPU-budget question, measured):**

| Attribution | Chain length (mean / p90 / max) | Chain CPU, ms (mean / max) |
| --- | --- | --- |
| 1 human, 3 bots (worst of 4 attributions) | 11.3 / 12 / **13** | 1.16 / **4.0** |
| 2 humans, 2 bots (worst of 6 pairs) | 4.8 / 7 / **8** | 0.63 / **1.5** |
| 0 humans — the whole match as one chain | 1,130 | **17.9 mean / 46 max** |

Two findings: (a) bot runs between human messages are **longer than one trick** — auction raise
wars plus trick/round boundaries chain up to 13 actions — but they still cost **≤ 4 ms** of
engine time, inside the 10 ms budget *as far as the engine's share goes* (see the caveats above:
JSON/SQL/WS/auth are not in that number); (b) an **all-bot fast-forward (a whole match in one
invocation) is 17.9–46 ms and exceeds the budget on the conservative 10 ms reading — the design
must chunk it** (per-trick or per-round alarm ticks). Each `setAlarm()` costs **1 row written +
1 alarm invocation** (a DO request), so chunking granularity is a capacity decision — quantified
in §5.1. Normal play (≥ 1 human) needs **zero** alarms — bot moves ride human messages.

## 4. The revised per-match cost model (the ruled design)

Per FULL match, by seat mix — WebSocket transport, per-Trick persistence, bots internal:

| Quantity | 4 humans | 3H+1B | 2H+2B | Label |
| --- | --- | --- | --- | --- |
| Inbound messages (human actions) | 1,129.9 | 847.4 | 565.0 | **[M]** |
| DO request-units — documented reading: ⌈inbound/20⌉ + 4 connects + 5 lifecycle | 66 | 52 | 38 | inbound **[M]**; 20:1 **[m]** paid-page figure — the free plan's enforcement is **[x]**; lifecycle **[m]** minimal shape |
| DO request-units — conservative reading: inbound 1:1 + connects + lifecycle | 1,139 | 852 | 574 | **[m]** the ambiguity case: the free-plan limit text counts "WebSocket messages" without publishing the ratio |
| Outbound messages (1 ack + 3 broadcasts per action) | 4,519.7 | 4,519.7 | 4,519.7 | **[M as message count]** — the pricing docs state outgoing messages are **not billed**; listed for design completeness, not for the request meter |
| DO rows written (234 trick checkpoints + 193.9 auction actions) | 427.9 | 427.9 | 427.9 | counts **[M]**; 1 row/write **[m]** → **now [M] measured by E7b: the rows-per-write is 1 or 2 depending on the schema** — see the ×2 allowance in §5 and its E7b pointer |
| D1 rows written (settlement: 1 result + 4 RP) | 5 | 5 | 5 | **[m]** |
| DO rows read (state per action) | ≈ 1,130 | ≈ 990 | ≈ 850 | **[m]** |
| Server engine CPU, ms (per match; chains ≤ 4 ms per request) | 22.6 | 28.3 | 35.6 | **[M]** local wall-clock — §2 caveat |
| Alarms | 0 | 0 | 0 | **[m]** minimal design: bot moves ride human messages; chunking adds rows/requests only when a match runs all-bot (§3, §5.1) |
| Reconnects + snapshots | — | — | — | **[u]** rate unknown; each ≈ 1 inbound message + snapshot read |

MINI/NORMAL, 4 humans: 603.2 inbound / 40 request-units (documented reading) / 213.2 DO rows /
5 D1 rows / ≈ 12 ms CPU.

## 5. The three scenarios against the free tier — per meter

Daily demand = matches/day × per-match cost, FULL baseline (the assumption Decision D requires
stated: one completed match ≈ one E5/E6 FULL match). Limits verified 2026-10-29 (E5 report §4).
**The binding meter is DO rows written** — headroom 2.3× / 1.9× / 1.6× — not requests.

**4-human FULL matches, documented readings:**

| Meter (limit/day) | 100/day | 125/day | 150/day | Binding? |
| --- | --- | --- | --- | --- |
| Worker requests — connects + lifecycle (100,000) | 900 — 0.9% (111×) | 1,125 — 1.1% (89×) | 1,350 — 1.4% (74×) | no |
| DO requests, 20:1 inbound (100,000) | 6,600 — 6.6% (**15.2×**) | 8,250 — 8.3% (**12.1×**) | 9,900 — 9.9% (**10.1×**) | no |
| **DO rows written (100,000)** | **42,792 — 42.8% (2.3×)** | **53,490 — 53.5% (1.9×)** | **64,188 — 64.2% (1.6×)** | **yes — binding** |
| D1 rows written (100,000) | 500 — 0.5% (200×) | 625 — 0.6% (160×) | 750 — 0.8% (133×) | no |
| DO rows read (5,000,000) | 113,000 — 2.3% (44×) | 141,250 — 2.8% (35×) | 169,500 — 3.4% (30×) | no |
| DO duration, hibernated ≈ 0.75 GB-s/match (13,000 GB-s) [u] | 75 — 0.6% (173×) | 94 — 0.7% (139×) | 113 — 0.9% (116×) | no — if hibernation works |
| DO duration, **no hibernation**, 15-min match assumption (13,000 GB-s) [u] | 11,250 — 86.5% (1.2×) | 14,063 — **108% ✗** | 16,875 — **130% ✗** | **yes — if hibernation fails** |
| CPU per request (conservative 10 ms reading) | ≤ 4 ms worst engine chain — 40%, excluding JSON/SQL/WS/auth | — | — | no, but see §2/§8 |

**The conservative requests reading (inbound 1:1)** — the free plan's ambiguity, not a billed
design: 113,892 / 142,365 / 170,838 requests → **113.9% / 142.4% / 170.8%** — **all three
scenarios overrun**, not just the upper ones. Whether the 20:1 ratio is *enforced* on the Free
plan or is only the paid billing ratio is **[x] — the docs are ambiguous**; E12 decides.

**The earlier "outbound counted 1:1" figure (≈ 5,659 units/match, 566–849%) is withdrawn: the
pricing docs state outgoing WebSocket messages are not billed, so that reading contradicts the
documentation and is no longer carried as any scenario's basis.**

**The ×2 rows allowance — what it stands for (review item 3).** The written-rows figure counts
one row per logical write. Real multipliers, each labelled:

- **[x documented]** **Deletes count as rows written** — post-settlement cleanup of per-Trick
  rows would add up to one row written per deleted row.
- **[x documented]** **Each `setAlarm()` is one row written** — and chunked bot continuation
  (§3) would add one per tick.
- **[x documented]** **Every index update adds ≥ 1 extra row written** (DO SQL and D1) — the
  query shapes P3 designs determine this.
- **[u]** The KV-API (`put()`) → rows mapping is **not published**; the SQL `cursor.rowsWritten`
  counter **is observable** — E12 measures the real number.

The ×2 figure is a **rough allowance** for these multipliers combined, **not a verified
measurement**. At the ×2 bound: 85,584 / 106,980 / 128,376 rows → **85.6% / 107% / 128%** —
150 overruns outright, 125 overruns, 100 sits just under the 90% trip line.

> **Pointer, 2026-10-30 (E7b measured the ×2 rows allowance; the §5 figures above are left
> verbatim).** The **×2 rows allowance** is now **measured**, and it is **a schema choice, not a
> platform precaution**. E7b wrote one FULL match's worth of events (**428** writes — the counts
> read from the same probe file, not retyped) into six DO table shapes and read the DO SQL
> cursor's own `rowsWritten`:
>
> | Rows written per write | Designs | Rows per match |
> |---|---|---|
> | **×1** | **V1** — a rowid table with no secondary index; **V3** — `WITHOUT ROWID` with `PRIMARY KEY (match_id, seq)`; **V6** — one state row updated in place (428, plus 428 rows **read**) | **428** |
> | **×2** | **V2** — the same composite `PRIMARY KEY` on an **ordinary rowid** table; **V4** — an explicit **secondary index**; **V5** — a `UNIQUE` constraint | **856** |
>
> **So the ×2 is caused by a second index.** A composite `PRIMARY KEY` on an ordinary rowid table
> is implemented as a separate UNIQUE index, and each index entry is a second written row. A
> `WITHOUT ROWID` table carries the **same unique key** at **×1**.
>
> Also measured: **payload size does not change the row count** (100 / 300 / 1,000 B all report
> the same `rowsWritten` — only the file size moves); **batching does not change it either** (one
> statement, 5 chunks, 428 separate statements and `transactionSync` all report the same rows);
> **a delete costs 1 row per deleted row, with no index multiplier** (the ×2 is an insert-side
> cost only); and this runtime caps a statement at **100 bind variables** (so **33** three-column
> rows per statement, not 428).
>
> **A LOCAL COUNT IS NOT PROOF OF WHAT CLOUDFARE BILLS.** Whether Cloudflare's metered
> rows-written matches the local cursor is **[x]** — a real-account question (E12-a in the E7b
> report's add-on, to be run **with the owner's approval**).
>
> **The figures in this section are left verbatim.** The ×2 bound is **not** withdrawn; it becomes
> **conditional on the schema** the P3 design picks — **D-E7-7** in the E7 report. Report:
> `docs/implementation/Stage0_E7b_DO_Rows_Report.md`.

> **Pointer, 2026-10-30 (E12-a measured the ×2 ON CLOUDFLARE — the "not proof" line above is
> superseded on the write axis).** E12-a ran on the owner's **throwaway** Cloudflare account (Free
> plan, **no subscriptions, no billing**) and read **Cloudflare's own metered counter** for the
> same Durable Object instances: **V1 = 428 rows billed for 428 inserts (×1)**, **V2 = 856 rows
> billed for 428 inserts (×2)** — **exactly** the DO's own cursor counts. So the **×2 allowance is
> what Cloudflare bills for the V2-shaped schema**, and the DO-rows-written axis is **confirmed**,
> not a bound. **V3 (`WITHOUT ROWID`) is still [x]** — not yet measured on the platform, and it
> is the ×1 candidate. **The read axis is NOT confirmed**: Cloudflare counted **430 rows read**
> where the DO's cursor reported **0** (the platform meter counts a read per write; the local
> cursor does not), so the "DO rows read (state per action) ≈ 1,130" row above may be low by
> ~428 per match — **far inside the 5,000,000/day limit, so not a capacity blocker**, but a
> **correctness** item with its own step. The §4/§5 tables above are **left verbatim**. Report:
> `docs/implementation/Stage0_E12a_Platform_Rows_Report.md`.

> **Pointer, 2026-10-30 (E7 measured the settlement write protocol; the D1 rows rows above are
> left verbatim).** The **5 D1 rows/match** in §4 and the **D1 rows written** row in §5 are the
> ledger's **table** rows only — they exclude the **intent queue** (insert + ack + cleanup) and
> the **backup copy**, and D1's `rows_written` counts **index rows** too. E7 measured the whole
> settlement protocol against a local D1: **RP 10 + OUTBOX 5 + RP_BACKUP 10.4 = 25.4 rows per
> match** at a 5-match backup cadence (**29** backing up every match), so the D1 axis reads
> **2,540 / 3,175 / 3,810 rows/day — 2.54% / 3.175% / 3.81%** at 100 / 125 / 150 matches/day.
> **The verdict of this report does not change**: the D1 axis was never the binding meter (DO
> rows written is, at 42.8% / 53.5% / 64.2%), and the settlement axis stays far from its limit.
> What changes is the **figure the model carries** and the **ceiling on this axis** — 3,543
> matches/day at the 90% guardrail, not 18,000. **[m]** (modelled on a local D1; the Free plan's
> real metering is **[x]** — a real Cloudflare account). Report:
> `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` §10; pending owner decision D-E7-3.
>
> **Correction (2026-10-30, E7 step 4) — the "29" in this pointer is superseded by "27".** The
> old 29 used the **first** backup run's cost (14 rows, which includes **creating** the two
> watermark rows). Every later run only **updates** them, so the steady-state run costs **12** →
> 10 + 12 + 5 = **27** rows per settlement. The **25.4** headline at a 5-match cadence was already
> the steady-state figure and is **unchanged**. Measured directly at cadences 1 / 5 / 10
> (backup job rows per run 14/12, 54/52, 104/102; coverage 98.33% / 91.67% / 83.33%).
> Report: `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` §8.1 and §10.4.
>
> **Pointer, 2026-10-30 (the owner ruled the cadence — the model now carries 27, not 25.4).**
> **D-E7-1 is decided**: the backup runs after **every** settlement
> (`docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md` §2), so the protocol figure
> is **RP 10 + OUTBOX 5 + RP_BACKUP 12 = 27 rows per settlement**, and the D1 axis reads
> **2,700 / 3,375 / 4,050 rows/day — 2.70% / 3.375% / 4.05%** at 100 / 125 / 150 matches/day. The
> **ceiling on this axis is 3,333 matches/day at the 90% guardrail** (**3,703** at the hard limit),
> not 3,543 / 3,937. **The verdict of this report does not change**: the D1 axis is still not the
> binding meter (DO rows written is, at 42.8% / 53.5% / 64.2%). The **25.4 / 29 figures above
> are left verbatim** — they are the measured cadence-5 alternative and the superseded first-run
> figure. The **[x] real-account caveat is unchanged**.

### 5.1 PASS/overrun thresholds at both bases (review item 2)

Every threshold restated at **100% of the limit** and at the **90% guardrail**, for all three
scenarios. A scenario PASSes on a meter while its per-match cost stays under the threshold, and
overruns once it exceeds it.

| Threshold per match | Basis | 100/day | 125/day | 150/day |
| --- | --- | --- | --- | --- |
| DO rows written — multiplier over the measured 427.9 | 100% of limit | ≤ 2.34× | ≤ 1.87× | ≤ 1.56× |
| DO rows written — multiplier | **90% guardrail** | ≤ 2.10× | ≤ 1.68× | ≤ 1.40× |
| DO duration (GB-s/match) | 100% of limit | ≤ 130.0 | ≤ 104.0 | ≤ 86.7 |
| DO duration (GB-s/match) | **90% guardrail** | ≤ 117.0 | ≤ 93.6 | ≤ 78.0 |
| DO request-units (per match) | 100% of limit | ≤ 1,000 | ≤ 800 | ≤ 666 |
| DO request-units (per match) | **90% guardrail** | ≤ 900 | ≤ 720 | ≤ 600 |

Read against the model: the documented request reading (66 units) passes both bases with
9.1–15.2× margin; the conservative reading (1,139 units) exceeds even the 100% threshold at
every scenario; rows pass both bases at 1× but 150/125 fail the 90% base at ×2 (1.40× / 1.68×
thresholds), and 100 sits within 4.4 percentage points of its 90% line at ×2.

### 5.2 The cost of chunking the all-bot fast-forward (review item 3)

f = the share of matches that need an all-bot fast-forward (abandoned-human continuation) —
**[u] unknown**; closed-testing telemetry (M10's disconnect and takeover counts) is what would
measure it. Per **affected** match: per-trick alarm ticks add **+234 rows and +234 DO requests**
(each `setAlarm()` = 1 row written; each invocation = 1 DO request); per-round ticks add
**+18 rows and +18 requests**. Rows/day = S × (427.9 + f × 234) per-trick, S × (427.9 + f × 18)
per-round; the DO-request axis stays far below its limit in every cell (worst case: 150 ×
(66 + 234) = 45,000 — 45% under the documented reading). Cells crossing the **90% guardrail**
marked ✗:

| Rows/day (% of 100,000) | f = 0% | f = 5% | f = 10% | f = 25% | f = 100% |
| --- | --- | --- | --- | --- | --- |
| **Per-trick ticks**, 100/day | 42,792 (42.8%) | 43,962 (44.0%) | 45,132 (45.1%) | 48,642 (48.6%) | 66,192 (66.2%) |
| **Per-trick ticks**, 125/day | 53,490 (53.5%) | 54,953 (55.0%) | 56,415 (56.4%) | 60,803 (60.8%) | 82,740 (82.7%) |
| **Per-trick ticks**, 150/day | 64,188 (64.2%) | 65,943 (65.9%) | 67,698 (67.7%) | 72,963 (73.0%) | **99,288 (99.3%) ✗** |
| **Per-round ticks**, 150/day | 64,188 (64.2%) | 64,335 (64.3%) | 64,470 (64.5%) | 64,875 (64.9%) | 66,888 (66.9%) |
| **Per-round ticks**, 100–125/day | 42.8% / 53.5% | ≤ 44.0% | ≤ 43.9% | ≤ 44.5% | ≤ 44.6% / 55.8% |

Only one cell crosses the guardrail: **150/day with per-trick ticks on every match (f = 100%)**.
Per-round ticks never do. If real abandonment telemetry lands near f = 100% *and* per-trick
chunking is chosen, 150/day trips the guardrail in the model.

**Where each scenario crosses (review item 2):** with rows per match = 427.9 + 234·f, a scenario
crosses the 90% guardrail when S × (427.9 + 234·f) ≥ 90,000, i.e. **f\* = (90,000/S − 427.9)/234**:

| Scenario | f\* | Meaning |
| --- | --- | --- |
| 100/day | (900 − 427.9)/234 = **2.02 → never crosses** | even fast-forwarding every match stays at 66.2% |
| 125/day | (720 − 427.9)/234 = **1.25 → never crosses** | even fast-forwarding every match stays at 82.7% |
| 150/day | (600 − 427.9)/234 = **0.74** | the guardrail trips only if roughly **three quarters of all matches** need a per-trick fast-forward |

f remains **[u]** — nothing here says how likely f = 0.74 is; closed-testing disconnect and
takeover telemetry is what would measure it.

**An alternative the owner may choose (check only — not decided, not implemented).** The plan
already records an **all-humans-gone rule**: ADR 0003's E1 section lists "the 60-second
all-humans-gone wait" among the rules the engine does not implement because the backend decides
them, and the E3 report §5.3 names the row precisely — `t16.s12.all-humans-gone`: "the 60 s wait
before a table with no humans is cancelled — A services timer. The engine has no abandonment
path at all." If the backend applied that rule — **abandon the match after the 60 s wait**
instead of fast-forwarding it with bots — then the chunking cost of §5.2 **largely disappears**:
a match whose humans have all gone is cancelled, not played out, so no per-trick/per-round alarm
ticks are spent on it (what remains is one abandonment write plus the cleanup deletes, which
themselves count as rows written, and one timer/alarm to wake the idle DO when the wait expires —
1 row + 1 invocation, not 234). What **remains** in every case: **partial takeover** (1–3 humans
leave) still runs bot seats through human-message piggybacking — the measured ≤ 4 ms chains —
with zero alarms; only a fully-abandoned tail (the last human leaving mid-match) hits the 60 s
path instead of a fast-forward. This is presented as an owner alternative alongside chunked
continuation, not as a decision.

## 6. The 90% guardrail (ruling E)

**Trip points** — matches/day at which a meter reaches 90% and new matches must stop (4H FULL):

| Meter | 90% trip at |
| --- | --- |
| DO requests, 20:1 | ≈ 1,363/day — never trips at target usage |
| DO requests, inbound 1:1 | **≈ 79/day — trips at all three scenarios** |
| DO rows written, 1× | ≈ 210/day — never trips at 100/125/150 (150/day peaks at 64.2%) |
| DO rows written, ×2 | ≈ 105/day — trips late at 125/150 |
| DO duration, no hibernation | ≈ 104/day — trips at 125/150 |

**Reserve sufficiency (review item 1; worst case and average case, one stated basis each).**
When a trip fires, the matches already in progress keep consuming. How much one ongoing match
still needs depends on where it is:

- **Worst case** — the match had **just started** when the trip fired: its remaining cost is its
  **whole-match cost**. This is the basis every figure below used since the second review round;
  it is now labelled as the worst case everywhere.
- **Average case [m]** — an ongoing match is **halfway through** on average, so its remaining
  cost is **half** a match's. The "halfway" split is a **modelling assumption, not a
  measurement**; the halves derive directly from the measured per-match figures: rows
  427.92 / 2 = **214** (×1) and 855.84 / 2 = **428** (×2); requests 66 / 2 = **33** (20:1) and
  1,138.92 / 2 = **570** (1:1, ceiling — a fractional message is still a message).

The 10% reserve is 10,000 of each meter; tables covered = floor(10,000 ÷ remaining cost):

| Reading | Remaining cost — worst case | Tables — worst case | Remaining cost — average case [m] | Tables — average case |
| --- | --- | --- | --- | --- |
| DO rows written, 1× | 428 rows | **23** | 214 rows | **46** |
| DO rows written, ×2 allowance | 856 rows | **11** | 428 rows | **23** |
| DO requests, 20:1 documented | 66 units | **151** | 33 units | **303** |
| DO requests, inbound 1:1 | 1,139 units | **8** | 570 units | **17** |

For scale only: the illustrative peak used earlier (all 150 matches overlapping within one hour)
is ≈ 25 concurrent tables — **[u] illustrative, not measured**; real concurrency is what E12 /
closed testing would measure (match wall duration and play-time distribution).

**Verdict — honest in both directions:** in the **worst case**, rows stay **marginal** (23 / 11
tables) and the conservative requests reading stays **insufficient** (8) — nothing in this round
softens that. In the **average case [m]**, rows ×1 rises above the illustrative peak (46) while
**rows ×2 (23) and requests 1:1 (17) stay below it**. Neither case is the guarantee: which one
reality resembles depends on the real concurrent-match count and how far matches typically are
when a trip fires — **[u] until E12 / closed testing measure it**. What is unconditional: each
meter must be monitored independently (ruling E §5.1), rerouted *new* matches need the backup
server ruling F flags as **not yet designed**, and without it the correct trip behaviour is the
explicit refuse/queue response of ruling E §5.6 — a product decision to design.

## 7. Traffic not in the model — the remaining rows budget (review item 5)

Rows written binds at 42.8% / 53.5% / 64.2% of the daily limit, so the rest of the product must
fit in what remains. Budget left **at the 90% guardrail** (the planning line, not the hard
limit): rows left = limit × 0.9 − rows used;

**rows left per match = (limit × 0.9 − rows used) / matches**:

| Scenario | Rows used | Left below the guardrail | Left per match |
| --- | --- | --- | --- |
| 100/day | 42,792 | 47,208 | **472.1** |
| 125/day | 53,490 | 36,510 | **292.1** |
| 150/day | 64,188 | 25,812 | **172.1** |

What must still fit there (no numbers invented; per-item effect and label):

| Traffic | Rows written effect | DO requests effect | Worker requests effect |
| --- | --- | --- | --- |
| Matchmaking (queue, pairing, seat offers) | **[u]** — queue/pool writes per search, unknown rate | **[u]** | **[u]** — or rides the WS where the design allows |
| Reconnect + snapshot | **[u]** — snapshot restore is mostly reads; any snapshot persist is a write | **[u]** — 1 inbound message + the reconnect itself | **[u]** — a fresh connection request |
| Profile / statistics writes | **[u]** — post-match stat updates; frequency unknown | **[u]** | **[u]** |
| Retries / idempotency bookkeeping | **[u]** — a replayed action that appends dedup state costs rows | **[u]** — retries are extra inbound messages | **[u]** |
| Schema / migration writes | **[x one-off]** — deployment-time, not per-day traffic | — | — |
| Cleanup deletes after settlement | **[x documented]** — deletes count as rows written (§5) | **[u]** | — |
| Alarms (chunked bot continuation) | **[m]** — 1 row per `setAlarm()`; §5.2's sensitivity table prices it | **[m]** — 1 DO request per invocation | — |

The 150/day scenario has the least slack: **172.1 rows per match** for everything above. If the
[u] items together exceed that, either the FULL baseline shrinks (MINI-heavy mix), the guardrail
trips earlier in the day, or a paid plan enters O-06. None of these is measurable from a laptop.

## 8. The per-Trick persistence window is a ruling, not a validated design (review item 5)

Ruling A prefers persisting at the completed Trick. Everything in this section is **[u] —
unvalidated design surface that E9 owns**; none of it is settled by E6's measurements:

- **The window.** Up to **3 accepted card actions** may exist after the last completed-Trick
  checkpoint (the 4th card completes the trick and is itself the checkpoint). Auction state is
  NOT in the window — ruling A keeps auction persistence per action, so bid state is always
  committed. But "accepted card actions" span state beyond the trick: turn order, trick-contest
  state, any With/Risk bookkeeping attached to those cards.
- **Hibernation is a loss path without a crash.** An idle Durable Object can be **evicted**; its
  in-memory state is then gone even though nothing failed. A design that keeps the trick window
  in memory must either persist before idling or be able to rebuild the window on wake — and the
  rebuild must be indistinguishable from recovery after a crash. E9 must test eviction, not only
  kill -9.
- **Output gating conflicts.** If a card is **broadcast to peers before it is persisted**, peers
  have seen an action the checkpoint does not contain; after a restart the match would resume
  behind what players saw. If broadcast waits for persistence, the per-Trick boundary adds
  visible latency to every card. The design must choose — broadcast-then-durably-confirm,
  persist-then-broadcast, or per-Trick broadcast with per-card visual echo — and E9 must test
  whichever is chosen against the "players saw an uncommitted action" case.
- **Idempotency and ordering.** Safe replay needs **idempotency keys** (client-generated action
  ids) and **sequence numbers** (server-assigned order) so a retried card after reconnect is
  deduplicated exactly once and out-of-order deliveries cannot double-apply.
- **What a disconnected client cannot know.** A client that sent card 3 of a trick and
  disconnected cannot know whether the server durably accepted it; on reconnect it must learn
  the committed state from the server snapshot (sequence numbers), never from local memory.

**E9's question list:** interruption mid-trick; eviction mid-trick; last-checkpoint recovery;
reconciliation of seen-but-uncommitted actions; idempotency/sequence replay; reconnect state
discovery; hibernation/eviction restart; and one deterministic recovery test proving exactly one
valid continuation with no duplicated and no lost committed actions. Deterministic engine replay
(E2/E3) does **not** discharge any of these — it recomputes state; it says nothing about which
network actions were durably accepted.

## 9. Players per day (review item 7) — formulas, no invented distribution

With S = completed matches/day, h = human seats per match, m = completed matches per player:

- seat-participations/day = **4·S** (the table's four seats, human or bot);
- human seat-participations/day = **h·S**;
- unique players/day = **h·S / m**.

| Scenario S | Seat-participations (h = 4) | Players/day at m = 3 | Players/day at m = 4 |
| --- | --- | --- | --- |
| 100 | 400 | ≈ 133.3 | 100 |
| 125 | 500 | ≈ 166.7 | 125 |
| 150 | 600 | ≈ 200 | 150 |

For h < 4 the player counts scale down linearly (U = h·S/m) while the **request model stays at
the 4-human cost** — the stated worst case for inbound messages (ruling B puts bot actions
inside the server). The FULL baseline (Decision D) makes these upper bounds; a real FULL/MINI
mix shifts demand down (MINI costs ≈ half per match). The h and m distributions are **[u]** —
owner knowledge after launch, not invented here.

## 10. The finding

1. **The binding meter is DO rows written: 42.8% / 53.5% / 64.2% of the daily limit at
   100/125/150 — headroom 2.3× / 1.9× / 1.6×.** Requests carry 10–15× headroom **only under
   the documented 20:1 reading**; under the conservative inbound-1:1 reading all three scenarios
   overrun (114–171%). Duration is either negligible (hibernation works) or an overrun (it does
   not). CPU is a non-problem for normal play **as far as the engine's share goes** (chains
   ≤ 4 ms measured, engine-only) but **forbids an unchunked all-bot fast-forward** (17.9–46 ms)
   on the conservative 10 ms reading; the DO page's 30 s figure would not bind — [x] which
   applies.
2. E5's ≈ 1,135 requests/match and ≈ 1,135 rows/match are **superseded as planning numbers** by
   the ruled design's ≈ 66 request-units (documented reading) and ≈ 428 rows.
3. The 90% guardrail's reserve, on the uniform **worst-case** basis (an ongoing match needs its
   whole remaining cost), protects **requests only under the 20:1 reading (151 tables)**; rows
   are marginal (23 / 11 tables at 1× / ×2) and the conservative requests reading is
   insufficient (8). On the **average-case** halfway assumption [m] it becomes 303 / 46 / 23 /
   17 — rows ×1 rises above the illustrative ≈ 25-table peak, rows ×2 and the conservative
   requests reading stay below it. All of it is conditional on the unknown real concurrent-match
   peak [u]. The earlier "protected under the documented readings" claim for
   rows was an artefact of a mixed basis and is withdrawn.

## 11. What E6 does **not** prove

- No real Cloudflare account, DO, or WebSocket was exercised — the platform-side facts (20:1 on
  Free, rows-per-write, deletes/alarms/index multipliers, hibernation, duration accounting, the
  applicable CPU limit) are **[u]/[x]**, owned by E12/E9 (§14).
- The outbound shape (4 per action), lifecycle shape (5 HTTP + 4 connects), alarm design (0 in
  normal play) and the 15-minute match duration are **[m]** assumptions.
- Matchmaking, disconnect votes, takeover clocks and profile traffic are outside one match's
  cost; §7 prices the budget they must fit into.
- The per-Trick persistence design itself is **unvalidated** (§8) — ruling A is conditional on
  E9 for exactly this reason.
- The chain-test bound (≤ 16 actions against a measured max of 13) is a **regression guard set
  from one measurement, not a proof** of the true maximum.

## 12. How to reproduce

```bash
# ── JVM leg ─────────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:jvmTest --rerun-tasks --no-build-cache
#   → native/stage0/build/stage0-e6/capacity-mix-jvm-*.json, capacity-chain-jvm-*.json

# ── Server legs ─────────────────────────────────────────────────────────────
cd native && ./gradlew :stage0:compileProductionExecutableKotlinJs --rerun-tasks --no-build-cache
cp native/stage0/build/compileSync/js/main/productionExecutable/kotlin/*.js D:/cache/stage0/e6/
cd D:/cache/stage0/e6
WRANGLER_SEND_METRICS=false XDG_CONFIG_HOME=D:/cache/stage0/config \
  "C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" \
  D:/cache/stage0/node_modules/wrangler/bin/wrangler.js dev --local --port 8975 --ip 127.0.0.1
curl "http://127.0.0.1:8975/?what=capacity-mix&first=1&count=50&gt=FULL&sm=NORMAL" \
  -o workerd-mix-FULL-NORMAL.json
# chain probe: same recipe with runCapacityChain / what=capacity-chain (port 8976)

# ── Compare ────────────────────────────────────────────────────────────────
# counts.fingerprint must be ee328556 (mix) / f68532ca (chain) on both legs.
# Timings are compared against the 10 ms budget, not each other.
```

## 13. What the owner still has to decide

1. **Authorize the E12 free-account experiment** (§14) — it decides requests (20:1 vs 1:1), rows
   (real rows-per-write), duration (hibernation), and with them whether the scenarios PASS or
   overrun. Needs the owner's approval and a real account; this record runs nothing.
2. **The bot-continuation chunking granularity and the all-bot endgame** — three owner
   alternatives, not decided here: per-trick alarm ticks (≈ +234 rows, +234 invocations per
   fast-forwarded match; trips the guardrail at 150/day if f = 100%), per-round ticks
   (≈ +18; never trips), or applying the plan's **60 s all-humans-gone abandonment rule**
   (§5.2 — abandons the match instead of fast-forwarding it; the chunking cost then largely
   disappears).
3. **How an all-bot match is handled at all** — chunked continuation with the extra rows above,
   or a time-limited/terminated abandoned match (a product ruling the capacity model must then
   reflect).
4. **The refuse/queue response** at a guardrail trip (ruling E §5.6) — product behaviour to
   design (copy, UX, retry) before P3 builds the guardrail.
5. **The failover architecture** (ruling F's flagged prerequisite) — load-bearing exactly when
   the conservative readings hold, since the trip then fires mid-day.
6. **O-06** — unchanged; E6 is evidence with its limitations stated, not a decision.

## 14. E12 protocol — free-account platform-metering experiment (NOT RUN; requires owner approval)

**Status: documentation only. Nothing here has been executed; it needs the owner's explicit
approval, a real (throwaway) Cloudflare account, and its own acceptance before running.**

**Goal.** Replace E6's [u]/[x] platform assumptions with measured numbers, using a throwaway
SQLite-backed Durable Object that mimics the proposed match-DO shape.

**Setup.** One Worker + one DO class (SQLite-backed), deployed to the throwaway account; the DO
uses the **exact proposed persistence API** — the per-Trick checkpoint write, the per-action
auction write, and the settlement batch — implemented for the experiment with the same
key/row shapes P3 will use. No real user data; no production namespace.

**Steps.**

1. Create the DO. Add a **secondary index** on the checkpoint table (to measure the index-update
   multiplier).
2. Connect **4 WebSocket clients** to one DO; drive **one match's worth of actions** (≈ 1,130
   messages: 936 card actions in 234 trick checkpoints + ≈ 194 auction actions) through the
   exact persistence design.
3. Issue **one `setAlarm()`** and let it fire once (inactivity-timer shape).
4. **Delete** the per-Trick rows after the synthetic settlement (cleanup shape).
5. Read `ctx.storage.sql` **`cursor.rowsWritten`** around every step; compare with the
   dashboard's **rows-written** counter for the same window.
6. Record the dashboard's **DO requests** and **Worker requests** counters for: the 4 connection
   upgrades, the ≈ 1,130 incoming messages, the outgoing messages, the alarm invocation, and a
   reconnect with snapshot.
7. Record **GB-s duration** for an idle DO held open for a full match-length window (the
   hibernation question): with the WS attached and idle, after disconnect, and across a forced
   eviction.
8. **At-limit behaviour:** continue past 100% of one daily meter on the throwaway account and
   record what actually fails, in what order, with what error — so the 90% guardrail's failure
   model is real, not assumed.

**What each result decides.**

| Result | Decides |
| --- | --- |
| Dashboard rows-written ÷ logical writes | the real rows-per-write multiplier — the ×2 allowance stands or falls; whether 125/150 PASS or overrun on rows |
| Dashboard DO-request delta per incoming WS message | whether the Free plan honours 20:1 or bills 1:1 — requests PASS (≤ 9.9%) or overrun (≥ 114%) at all scenarios |
| Dashboard DO-request delta for outgoing messages | confirms outgoing are free (paid-page) or not on Free |
| Alarm invocation counter + rows | the real cost of chunked bot continuation |
| Index-write delta | the index multiplier the P3 schema design must price |
| GB-s for idle-with-WS vs disconnected | whether hibernation makes duration negligible (PASS) or matches hold GB-s (overrun at 125/150) |
| Which CPU limit applies to DO-handled WS messages (10 ms Worker vs 30 s DO) | whether the chunking requirement is real or the fast-forward can run in one invocation |
| At-limit failure order and errors | what the 90% guardrail must monitor first, and what its refuse/queue response must look like |

**Safety.** Throwaway account only; no billing enabled; no user data; torn down after the run.
Requires the owner's approval before any deployment (ruling G: no paid plan, no production
resources).
