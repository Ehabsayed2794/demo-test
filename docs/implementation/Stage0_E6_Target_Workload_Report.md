# Stage 0 — E6: the target workload (100/125/150 matches/day) against the free tier

**Status: PASS WITH LIMITATIONS (2026-10-29).** The action counts, per-seat loads and CPU costs
are **measured** — locally, on the JVM and inside local `workerd`. The capacity numbers are
**modelled** from those measurements plus the owner's 2026-10-29 rulings; the platform-side
metering facts they depend on are **unverified** until a real Cloudflare account measures them
(§13). The pass criterion ("measured headroom or a documented overrun") is met as **headroom on
every meter under the documented readings — led by the binding meter, DO rows written, at only
1.6–2.3× — and a documented overrun under the conservative readings**, with each meter's usage,
headroom and thresholds in §5.

Branch `stage0/e6-target-workload`, base `e624879` (`stage0/e5-capacity-model` — E5 plus the
owner rulings A–G, `docs/decisions/2026-10-29-stage0-capacity-persistence-transport-failover.md`).

## 1. What the experiment is

E6 runs under the owner's rulings, which change what "the primary usage scenario" is:

- the target is the **planning range 100 / 125 / 150 completed matches per day** (three labelled
  scenarios, not a forecast — ruling D);
- the transport is **WebSocket-preferred** with server-side **bots inside the authority**
  (rulings B/C), so E5's "one request per action" premise no longer holds;
- card play persists **per completed Trick**, the auction per action (ruling A, conditional on
  E9 — see §7 for why that condition is load-bearing);
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
  server-side actions that executes without an intervening human message** — the quantity the
  10 ms CPU-per-request budget actually bounds. Chain CPU includes the brain and the reducer and
  conservatively attaches the server-only round-boundary work (trick resolution, round scoring,
  round advance) to the action that preceded it.

Batteries: seeds 1–50 FULL/NORMAL (primary) and 1–20 for the other combos. **All count
fingerprints are byte-identical between the JVM and workerd**: mix `ee328556` / `31cacdd4` /
`81ed9678` / `b1116c8d`; chain `f68532ca` / `2749a715`. Same Node/wrangler evidence rules as E5
(portable Node 22.14.0 by explicit path, pinned local wrangler 4.155.0, no installs, local HTTP
only, `WRANGLER_SEND_METRICS=false`, **0 crashes** in every log).

**A measurement caveat that matters for production (review item 6):** all CPU figures here are
local **wall-clock around synchronous calls**. In deployed Workers, `performance.now()` and
`Date.now()` do **not** advance during CPU work (Workers runtime docs), so this instrumentation
cannot be reused in production. Production CPU must come from Cloudflare's own CPU metrics; the
local figures are the shape of the cost, not the deployed number.

## 3. What one seat costs (measured, FULL/NORMAL, 50 seeds)

| Measure | p1 | p2 | p3 | p4 |
| --- | --- | --- | --- | --- |
| Card actions per match | 234 | 234 | 234 | 234 |
| Auction actions (mean / min / max) | 51.4 / 46 / 66 | 47.6 / 40 / 62 | 47.3 / 36 / 63 | 47.6 / 40 / 60 |
| Brain CPU per match, ms (mean / p90) | 5.6 / 11 | 7.3 / 13 | 15.7 / 26 | 15.7 / 24 |
| Apply CPU per match, ms (mean / p90) | 5.3 / 11 | 5.1 / 9 | 4.6 / 7 | 5.5 / 9 |

Totals: **1,129.9 actions/match** (mean; 936 cards + 193.9 auction actions, p90 218; 3.48
all-pass redeals/match), **234 trick checkpoints**, ~10.8 auction actions per round. Seats are
nearly balanced (each ≈ 25% of the traffic). MINI/NORMAL (20 seeds): 603.2 actions (520 cards,
83.2 auction, 130 checkpoints, 10 rounds).

**Chains (the 10 ms question, measured):**

| Attribution | Chain length (mean / p90 / max) | Chain CPU, ms (mean / max) |
| --- | --- | --- |
| 1 human, 3 bots (worst of 4 attributions) | 11.3 / 12 / **13** | 1.16 / **4.0** |
| 2 humans, 2 bots (worst of 6 pairs) | 4.8 / 7 / **8** | 0.63 / **1.5** |
| 0 humans — the whole match as one chain | 1,130 | **17.9 mean / 46 max** |

Two findings: (a) bot runs between human messages are **longer than one trick** — auction raise
wars plus trick/round boundaries chain up to 13 actions — but they still cost **≤ 4 ms**, inside
the 10 ms budget; (b) an **all-bot fast-forward (a whole match in one invocation) is 17.9–46 ms
and exceeds the budget — the design must chunk it** (per-trick or per-round alarm ticks). Each
`setAlarm()` costs **1 row written + 1 alarm invocation** (a DO request), so chunking granularity
is a capacity decision: per-trick ticks add ≈ 234 rows and ≈ 234 invocations per fast-forwarded
match; per-round ticks add ≈ 18. Normal play (≥ 1 human) needs **zero** alarms — bot moves ride
human messages.

## 4. The revised per-match cost model (the ruled design)

Per FULL match, by seat mix — WebSocket transport, per-Trick persistence, bots internal:

| Quantity | 4 humans | 3H+1B | 2H+2B | Label |
| --- | --- | --- | --- | --- |
| Inbound messages (human actions) | 1,129.9 | 847.4 | 565.0 | **[M]** |
| DO request-units — documented reading: ⌈inbound/20⌉ + 4 connects + 5 lifecycle | 66 | 52 | 38 | inbound **[M]**; 20:1 **[m]** paid-page figure — the free plan's enforcement is **[x]**; lifecycle **[m]** minimal shape |
| DO request-units — conservative reading: inbound 1:1 + connects + lifecycle | 1,139 | 852 | 574 | **[m]** the ambiguity case: the free-plan limit text counts "WebSocket messages" without publishing the ratio |
| Outbound messages (1 ack + 3 broadcasts per action) | 4,519.7 | 4,519.7 | 4,519.7 | **[M as message count]** — the pricing docs state outgoing messages are **not billed**; they are listed for design completeness, not for the request meter |
| DO rows written (234 trick checkpoints + 193.9 auction actions) | 427.9 | 427.9 | 427.9 | counts **[M]**; 1 row/write **[m]** — see the ×2 allowance in §5 |
| D1 rows written (settlement: 1 result + 4 RP) | 5 | 5 | 5 | **[m]** |
| DO rows read (state per action) | ≈ 1,130 | ≈ 990 | ≈ 850 | **[m]** |
| Server engine CPU, ms (per match; chains ≤ 4 ms per request) | 22.6 | 28.3 | 35.6 | **[M]** local wall-clock — §2 caveat |
| Alarms | 0 | 0 | 0 | **[m]** minimal design: bot moves ride human messages; chunking adds rows/requests only when a match runs all-bot (§3) |
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
| CPU per request (10 ms) | ≤ 4 ms worst chain — 40% | — | — | no, but see §3/§8 |

**The conservative requests reading (inbound 1:1)** — the free plan's ambiguity, not a billed
design: 113,892 / 142,365 / 170,838 requests → **113.9% / 142.4% / 170.8%** — **all three
scenarios overrun**, not just the upper ones. Whether the 20:1 ratio is *enforced* on the Free
plan or is only the paid billing ratio is **[x] — the docs are ambiguous**; E12 decides.

**The earlier "outbound counted 1:1" figure (≈ 5,659 units/match, 566–849%) is withdrawn: the
pricing docs state outgoing WebSocket messages are not billed, so that reading contradicts the
documentation and is no longer carried as any scenario's basis.**

**The ×2 rows allowance — what it stands for now (review item 3).** The written-rows figure
counts one row per logical write. Real multipliers, each labelled:

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

**What would turn each scenario into a clear PASS or a clear overrun (thresholds):**

| Meter | Clear PASS at/above | Clear overrun at/above |
| --- | --- | --- |
| DO requests | free plan honours 20:1 → all PASS (≤ 9.9%) | free plan bills 1:1 → all overrun (≥ 113.9%) |
| DO rows written | effective rows multiplier ≤ 1.4× → 150 at ≤ 60% | multiplier ≥ 1.6× → 150 over; ≥ 1.9× → 125 over; ≥ 2.34× → 100 over |
| DO duration | hibernation keeps active ≤ ≈ 0.75 GB-s/match → all PASS | ≥ 86.7 GB-s/match (≈ 11.6 min non-hibernated at 0.125 GB) → 150 over; ≥ 104 → 125; ≥ 130 → 100 |
| CPU per request | chains stay ≤ 4 ms measured → PASS | an unchunked all-bot fast-forward (17.9–46 ms) → per-request overrun |

## 6. The 90% guardrail (ruling E)

**Trip points** — matches/day at which a meter reaches 90% and new matches must stop (4H FULL):

| Meter | 90% trip at |
| --- | --- |
| DO requests, 20:1 | ≈ 1,363/day — never trips at target usage |
| DO requests, inbound 1:1 | **≈ 79/day — trips at all three scenarios** |
| DO rows written, 1× | ≈ 210/day — never trips at 100/125/150 (150/day peaks at 64%) |
| DO rows written, ×2 | ≈ 105/day — trips late at 125/150 |
| DO duration, no hibernation | ≈ 104/day — trips at 125/150 |

**Reserve sufficiency** (the honesty requirement in ruling E §5) — what the 10% reserve
(10,000 of each meter) covers when a trip fires, sized by the **remaining** cost of an in-progress
match (one more round + settlement ≈ 24 rows [M-derived] or its remaining inbound messages):

| Reading | Reserve covers |
| --- | --- |
| DO rows, 1× (trip never fires at target usage) | ≈ 416 concurrent tables |
| DO requests, 20:1 | ≈ 151 tables |
| DO rows, ×2 allowance (remaining ≈ 48 rows) | ≈ 208 tables |
| DO requests, inbound 1:1 | **≈ 8 tables — insufficient** |

**Verdict: the guardrail protects ongoing matches under the documented readings** (and never
fires there at target usage). Under the **conservative inbound-1:1 reading it does not** — the
reserve covers ≈ 8 tables against a plausible ≈ 25 concurrent — and that gap is **documented
here** per ruling E's own honesty requirement. If E12 confirms 1:1 free-plan metering, protecting
ongoing matches requires either the backup/failover path (ruling F's missing architecture) or a
smaller message shape — a design consequence, not a measurement. In every reading: each meter is
monitored independently (ruling E §5.1), and rerouted *new* matches need the backup server ruling
F flags as **not yet designed** — without it, the correct trip behaviour is the explicit
refuse/queue response of ruling E §5.6, a product decision to design.

## 7. The per-Trick persistence window is a ruling, not a validated design (review item 5)

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

## 8. Players per day (review item 7) — formulas, no invented distribution

With S = completed matches/day, h = human seats per match, m = completed matches per player:

- seat-participations/day = **4·S** (the table's four seats, human or bot);
- human seat-participations/day = **h·S**;
- unique players/day = **h·S / m**.

| Scenario S | Seat-participations (h = 4) | Players/day at m = 3 | Players/day at m = 4 |
| --- | --- | --- | --- |
| 100 | 400 | ≈ 133 | 100 |
| 125 | 500 | ≈ 167 | 125 |
| 150 | 600 | ≈ 200 | 150 |

For h < 4 the player counts scale down linearly (U = h·S/m) while the **request model stays at
the 4-human cost** — the stated worst case for inbound messages (ruling B puts bot actions
inside the server). The FULL baseline (Decision D) makes these upper bounds; a real FULL/MINI
mix shifts demand down (MINI costs ≈ half per match). The h and m distributions are **[u]** —
owner knowledge after launch, not invented here.

## 9. The finding

1. **The binding meter is DO rows written: 42.8% / 53.5% / 64.2% of the daily limit at
   100/125/150 — headroom 2.3× / 1.9× / 1.6×.** Requests carry the 10–15× headroom **only under
   the documented 20:1 reading**; under the conservative inbound-1:1 reading all three scenarios
   overrun (114–171%). Duration is either negligible (hibernation works) or an overrun
   (it does not). CPU is a non-problem for normal play (chains ≤ 4 ms) but **forbids an
   unchunked all-bot fast-forward** (17.9–46 ms).
2. E5's ≈ 1,135 requests/match and ≈ 1,135 rows/match are **superseded as planning numbers** by
   the ruled design's ≈ 66 request-units (documented reading) and ≈ 428 rows.
3. The 90% guardrail protects ongoing matches under the documented readings and **does not**
   under the conservative requests reading (≈ 8 tables) — documented per ruling E.

## 10. What E6 does **not** prove

- No real Cloudflare account, DO, or WebSocket was exercised — the platform-side facts (20:1 on
  Free, rows-per-write, deletes/alarms/index multipliers, hibernation, duration accounting) are
  **[u]/[x]**, owned by E12/E9 (§13).
- The outbound shape (4 per action), lifecycle shape (5 HTTP + 4 connects), alarm design (0 in
  normal play) and the 15-minute match duration are **[m]** assumptions.
- Matchmaking, disconnect votes, takeover clocks and profile traffic are outside one match's
  cost; E6's follow-ups must add them.
- The per-Trick persistence design itself is **unvalidated** (§7) — ruling A is conditional on
  E9 for exactly this reason.

## 11. How to reproduce

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

## 12. What the owner still has to decide

1. **Authorize the E12 free-account experiment** (§13) — it decides requests (20:1 vs 1:1), rows
   (real rows-per-write), duration (hibernation), and with them whether the scenarios PASS or
   overrun. Needs the owner's approval and a real account; this record runs nothing.
2. **The bot-continuation chunking granularity** — per-trick ticks (≈ +234 rows, +234 invocations
   per fast-forwarded match) vs per-round (≈ +18): a design decision with capacity consequences.
3. **The refuse/queue response** at a guardrail trip (ruling E §5.6) — product behaviour to
   design (copy, UX, retry) before P3 builds the guardrail.
4. **The failover architecture** (ruling F's flagged prerequisite) — load-bearing exactly when
   the conservative readings hold, since the trip then fires mid-day.
5. **O-06** — unchanged; E6 is evidence with its limitations stated, not a decision.

## 13. E12 protocol — free-account platform-metering experiment (NOT RUN; requires owner approval)

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
| At-limit failure order and errors | what the 90% guardrail must monitor first, and what its refuse/queue response must look like |

**Safety.** Throwaway account only; no billing enabled; no user data; torn down after the run.
Requires the owner's approval before any deployment (ruling G: no paid plan, no production
resources).
