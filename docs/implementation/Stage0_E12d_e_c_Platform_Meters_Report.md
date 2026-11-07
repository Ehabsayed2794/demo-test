# Stage 0 — E12-d/e/c: the WebSocket, duration, alarm and delete meters, measured on Cloudflare

**Status: MEASURED ON A REAL THROWAWAY CLOUDFLARE ACCOUNT, 2026-10-30.** This report closes the
three platform-side unknowns E6 showed were decisive — free-plan **WebSocket metering**, the
**duration** cost of an open match with and without hibernation, and the **alarm** and **cleanup**
costs — and it corrects one reading in `Stage0_E12a_Platform_Rows_Report.md` §8 that a second run
has now falsified.

**The four headline results [M]:**

1. **The Free plan bills WebSocket messages 1:1 — there is no 20:1 ratio.** Under the
   **hibernation API**, **1,134 DO requests for 1,130 messages** (the +4 is the four socket
   closes). Under the **plain API**, the same 1,130 messages cost **0 DO requests** (7 requests
   for the whole instance: the 4 upgrades plus 3 control calls).
2. **Outgoing messages are free in both modes.** 1,130 outgoing messages produced 0 additional
   DO requests and did not appear as Worker requests.
3. **An open match costs 0.128 GB·s per second in plain mode** — 180 s of an idle, connected
   table = **22.97 GB·s** in three 60-second buckets of 7.68. A 15-minute match = **115.2 GB·s**.
   **Hibernation removes the idle cost entirely** (0.0406 GB·s with a 180 s idle hold vs 0.0466
   without — the hold is not billed), at the price of the 1,134 requests above.
4. **Cleanup and alarms: `setAlarm()` costs 0 rows and 0 requests; the fire costs 1 DO request.**
   A row-by-row delete costs **1 row written and 2 rows read** per deleted row; **`DELETE FROM`
   costs 1 row written and 0 rows read**. Over a 428-row match that is 856 reads saved per match
   for one statement instead of 428.

**The correction [M]:** E12-a §8 reported that Cloudflare counted **430 rows read** where the DO's
cursor reported **0**, and hypothesised a read charged per write. **That hypothesis is false.**
The 430 was the harness's own `SELECT COUNT(*), SUM(LENGTH(payload))` scan of the 428 rows; **ten
inserts in this run cost 0 rows read**. The platform's read meter counts rows the engine actually
touches — explicit reads plus the row probe of a single-row `DELETE` — and the local cursor
reports only explicit reads. §7 has the arithmetic.

**Labels:** **[M] measured** · **[m] modelled** · **[u] unknown** · **[x] needs a real account**.

---

## 1. What the three experiments are

E12-a answered the rows-written question. These three answer the rest of what E6 could only model:

| Step | Question | Why it decides something |
|---|---|---|
| **E12-d** | 4 clients, one match's worth of messages (≈ 1,130): what do Cloudflare's **DO requests** and **Worker requests** counters show? | The Free plan's inbound ratio decides whether requests bind at **88 matches/day** (1:1) or not at all (20:1). Outbound billing decides the other half. |
| **E12-e** | A match-length socket held open, **with** and **without** hibernation: what does **GB·s duration** show? | If an open match costs duration, duration becomes the **binding meter** under the ×1 row shape E12-a confirmed. If hibernation works, it does not. |
| **E12-c** | What does **`setAlarm()`** cost (rows and requests)? What does a **row-by-row `DELETE`** cost against **`DELETE FROM`**? | The alarm is the inactivity timer (E7 §11) and the bot-continuation tick (E6 §5.2); the cleanup is E10's cost, and the two delete shapes differ by **856 rows read per match**. |

## 2. The account, the plan, and billing

The same **throwaway** account E12-a used: **`c6321eda25af9e06234358928272a0fa`**, **Free plan**,
**`subscriptions = []`**, **no billing activated** — the owner's one constraint, unchanged. The
primary account was never in scope: a fresh `XDG_CONFIG_HOME` kept its session out of reach and
every configuration file pinned this account id. No real data: synthetic messages and 10-row
tables. Full detail: `Stage0_E12a_Platform_Rows_Report.md` §2–§3.1.

**The account is now empty.** At the end of this run all four workers were deleted (HTTP 200), the
scripts list returned `[]`, the schedule endpoints returned 404, the Durable Object namespaces
returned `[]`, and the `E12LOG` KV namespace was deleted (200; the KV list returns `[]`). The
records live in `D:/cache/stage0/e12/results/`, outside the repository.

## 3. Method

### 3.1 The WebSocket harness (E12-d, E12-e)

```
4 clients ──WS──▶ Worker (/ws) ──upgrade──▶ Durable Object (the match authority)
```

The Worker only forwards the upgrade; after that the client's WebSocket **is** the DO's socket, so
all 1,130 messages cross the DO boundary — which is where the "20 messages = 1 request" question
lives. Four clients send **282 / 282 / 283 / 283 = 1,130** messages and wait for an `ack` on each
(1,130 acks received in every run).

The mode is chosen by the DO's instance name and is the **only** difference between a pair:

| Mode | API | What the runtime may do |
|---|---|---|
| `hib` | `ctx.acceptWebSocket(server)` | evict the object while the socket stays connected (hibernation) |
| `plain` | `server.accept()` | keep the object alive for as long as any socket is open |

**The same `*.workers.dev` obstacle as E12-a applies** (§3.2 of the E12-a report): this machine
cannot complete a TLS handshake with a workers.dev hostname, so the **clients are themselves a
deployed Worker** driven by a cron trigger. Every byte of the measurement therefore crosses
Cloudflare's own network and runtime; nothing is measured from this machine.

### 3.2 The four runs

| Run | Mode | Idle hold after the messages | Started (UTC) | Instance |
|---|---|---|---|---|
| **d1** | `hib` | 0 s | 12:45 | `e12-ws-hib-d1` |
| **d2** | `plain` | 0 s | 12:54 | `e12-ws-plain-d2` |
| **d3** | `hib` | **180 s** | 12:57 → 13:00 | `e12-ws-hib-d3` |
| **d4** | `plain` | **180 s** | 13:01 → 13:04 | `e12-ws-plain-d4` |

The two pairs isolate the two questions: **d1 vs d2** is the metering of the same messages under
the two APIs (hold = 0, so duration is only the burst); **d1 vs d3** and **d2 vs d4** is what an
idle, connected table costs.

### 3.3 The step-3 harness (E12-c)

One cron tick creates **four fresh DO instances**, one per role, so the platform's per-instance
counter isolates exactly one operation:

| Instance | Work |
|---|---|
| **ctl** | `CREATE TABLE` + **10 single-row inserts** — the control that prices setup and the inserts |
| **rowwise** | the same, then **10 row-by-row `DELETE … WHERE id = ?`** |
| **delFrom** | the same, then **one `DELETE FROM t`** removing the same 10 rows |
| **alarm** | the same, then **one `setAlarm(now + 70 s)`**; the alarm fires ~70 s later, in its own minute bucket |

`CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)` — a rowid table, no secondary index (the ×1
shape). The DO's own `cursor.rowsWritten` / `rowsRead` are recorded next to every statement, so the
platform counter and the local cursor compare the same way E12-a compared them.

### 3.4 Which counters are read

| Counter | GraphQL source | Used for |
|---|---|---|
| DO rows written / read | `durableObjectsPeriodicGroups` by `objectId` | E12-c |
| DO requests by type | `durableObjectsInvocationsAdaptiveGroups`, dimension `type` (`http` / `hibernation` / `alarm`) | E12-d, E12-c |
| DO duration (GB·s), active time | `durableObjectsPeriodicGroups` | E12-e |
| Worker requests | `workersInvocationsAdaptive` by `scriptName` | E12-d |
| Inbound / outbound WebSocket messages | `durableObjectsPeriodicGroups` (`inboundWebsocketMsgCount`, `outboundWebsocketMsgCount`) | E12-d |

## 4. E12-d — the WebSocket meters [M]

**Platform counters for the four runs** (per instance, matched by `objectId`):

| Run | Mode | Hold | Messages sent | **DO requests: http** | **DO requests: hibernation** | **DO requests: total** | Platform inbound WS msgs | Platform outbound WS msgs | **Worker requests (server script)** | DO errors |
|---|---|---|---|---|---|---|---|---|---|---|
| **d1** | hib | 0 s | 1,130 | 7 | **1,134** | **1,141** | 0 (not reported for this API) | 1,130 | 23 (+4 +4 +12) = **43** | 0 |
| **d2** | plain | 0 s | 1,130 | 7 | 0 | **7** | 1,134 | 1,134 | 3 (+4) = **7** | 2 |
| **d3** | hib | 180 s | 1,130 | 2 | **1,130** | **1,132** | 0 | 1,130 | **2** | 0 |
| **d4** | plain | 180 s | 1,130 | 7 | 0 | **7** | 1,130 | 1,130 | 4 (+2) = **6** | 0 |

### 4.1 The Free plan applies no inbound ratio — messages are 1:1 under hibernation [M]

**d1: 1,134 hibernation invocations for 1,130 messages.** The +4 is the four socket closes — d2
reports the same +4 explicitly, as **inbound 1,134 / outbound 1,134** against 1,130 client
messages, i.e. one close frame each way per socket [m]. **d3: 1,130 invocations for the same
1,130 messages** (the close events landed after the read window).

**The 20:1 allowance does not exist on the Free plan.** Every inbound message wakes the object and
is billed as one DO request. E6's "conservative" reading — 1:1 — is the **real** reading, and the
"documented" 20:1 reading is **withdrawn for this design**.

### 4.2 The plain API costs zero requests for the same messages [M]

**d2 and d4: 7 DO requests each** — the four upgrades plus three control calls — while the DO
reported **1,130 / 1,134 inbound messages**. The object stays resident and handles each message
in-process. **This is the cheapest request path measured in Stage 0**: a whole match costs ≈ 7
requests instead of ≈ 1,134.

### 4.3 Outgoing messages are free in both modes [M]

**1,130 outgoing messages in every run, 0 requests for them.** d1's counter reports 1,130 outbound
under hibernation and d4's reports 1,130 under plain, against the same 7 / 1,141 request totals.
The paid-page statement that outgoing messages are not billed holds on the Free plan too.

### 4.4 Messages are not Worker requests [M]

The server Worker saw **7, 43, 2 and 6 requests** across the four runs — never anything near
1,130. The 4 connections and the upgrade path are Worker requests; the messages themselves are
not. (d1's 12 `scriptThrewException` rows come from that first run's **close** path; the later runs
close cleanly — d4 has **0 DO errors**. None of them touches the meters: the clients received all
**1,130 acks** in every run.)

## 5. E12-e — duration and hibernation [M]

| Run | Mode | Hold | **Duration (GB·s)** | Active time | Rate | What it shows |
|---|---|---|---|---|---|---|
| **d1** | hib | 0 s | **0.0466** | 0.364 s | — | the burst alone |
| **d3** | hib | **180 s** | **0.0406** | 0.317 s | **≈ 0** | **the 180 s idle hold is not billed** |
| **d2** | plain | 0 s | **0.0355** | 0.277 s | 0.128 GB·s/s | the burst alone |
| **d4** | plain | **180 s** | **22.97** | 179.46 s | **0.128 GB·s/s** | three 60-s buckets: 7.680 + 7.680 + 7.610 |

**The rate is 0.128 GB·s per second — a 128 MB allocation held for the wall-clock time the object
is alive.** It appears three independent ways: 7.680 / 60 = 0.128; 22.97 / 179.46 = 0.128; and
d2's burst, 0.0355 / 0.277 = 0.128.

**Hibernation works as advertised, and its price is in §4.** d3 held the same four sockets idle for
180 s and spent **less** duration than d1 (0.0406 vs 0.0466) because the object was evicted while
the sockets stayed attached. The savings are real — and they are bought with **1,130 DO requests
per match**, which is a worse trade (§6.3).

### 5.1 What a match costs at each mode

| | **Plain (always-awake)** | **Hibernation** |
|---|---|---|
| DO requests per match (4 humans, 1 match) | **≈ 7** | **≈ 1,134** |
| Duration per **15-minute** match | **115.2 GB·s** (900 × 0.128) | **≈ 0.04 GB·s** |
| Daily ceiling at 13,000 GB·s | **112 matches/day** (101 at 90%) | not binding (≈ 320,000) |
| Daily ceiling at 100,000 DO requests | not binding (≈ 14,000) | **88 matches/day** (79 at 90%) |
| **Binding meter** | **duration** | **requests** |

**Neither mode is free at both meters.** The design has to pick which meter binds — and then keep
the other one far from binding.

## 6. E12-c — the alarm and the two delete shapes [M]

**Platform counters, per instance** (the control prices the setup and the inserts; every other row
is subtracted from it):

| Instance | Work | Local cursor (written / read) | **Platform written** | **Platform read** | Δ vs ctl (written / read) | DO requests |
|---|---|---|---|---|---|---|
| **ctl** | setup + 10 inserts | 12 / 1 | **12** | **1** | — | 2 |
| **rowwise** | + 10 row-by-row deletes | 22 / 11 | **22** | **21** | **+10 / +20** | 3 |
| **delFrom** | + one `DELETE FROM` | 22 / 1 | **22** | **1** | **+10 / +0** | 3 |
| **alarm** | + one `setAlarm(70 s)` | 12 / 1 | **12** | **1** | **+0 / +0** | 3 http + **1 alarm** |

### 6.1 The alarm: 0 rows, 0 requests to set; 1 request to fire [M]

`setAlarm()` wrote **no rows** and issued **no request** (the alarm instance's rows and requests
are identical to the control's). The **fire** appears as **one DO request of `type: "alarm"`**,
19.4 ms wall time, **0 rows written** — the alarm handler persisted nothing, so the request price
is the runtime's own bookkeeping.

**This corrects the "one row written" the documents imply.** The alarm is a **request**, not a
row. E7 §11's inactivity timer and E6 §5.2's bot-continuation tick are therefore request-priced:
**234 ticks per fast-forwarded match = 234 DO requests, 0 rows** — which is a much better trade
than the E6 model assumed (it charged a row per tick).

### 6.2 The two delete shapes: same rows, 856 reads apart [M]

- **Row-by-row:** 10 statements → **+10 rows written, +20 rows read**. Ten of those reads are the
  explicit `SELECT id FROM t`; the other ten are the **row probe each single-row `DELETE`
  performs**, which the DO's own cursor does not report.
- **`DELETE FROM`:** 1 statement → **+10 rows written, +0 rows read**.

Over a 428-row match cleanup: **both shapes write 428 rows; row-by-row issues 428 statements and
reads ≈ 856 rows, `DELETE FROM` issues 1 statement and reads 0.**

**Platform equals the local cursor on the write axis in both shapes** — E12-a's finding repeats
here, on a different table and a different operation.

### 6.3 Why this settles the transport mode

The three measured facts point the same way:

1. hibernation turns each message into a request (**88 matches/day ceiling**);
2. the plain API turns the same messages into **zero** requests but bills **0.128 GB·s per
   second** (**112 matches/day ceiling** — higher);
3. the design already needs **alarm-fired work** (bot continuation, inactivity timers), and under
   hibernation **every alarm fire also wakes the object as a request** — so hibernation does not
   even buy a request-free design.

**Plain (always-awake) wins on capacity and on simplicity.** Its cost is duration, which is
predictable and linear — and which is why §7.3's guardrail has to exist.

## 7. The read meter — the E12-a divergence resolved [M]

**E12-a §8 reported 430 platform rows read against 0 on the DO's cursor, and hypothesised a read
charged per write.** This run falsifies that hypothesis, and the arithmetic explains the 430
exactly:

- **Ten inserts cost 0 rows read** on the platform (ctl: 1 read, which is the setup).
- E12-a's write path ran `SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(payload)),0) FROM events`
  **after** the 428 inserts — a **full scan of 428 rows**. 2 setup reads + 428 scanned rows =
  **430**, identically for V1 and V2 because both tables held the same 428 rows. The DO's reported
  `rowsRead = 0` summed only the *insert* cursors and never attributed the scan.
- A **single-row `DELETE`** does read the row it deletes: +1 implied read per statement (§6.2).
  **`DELETE FROM`** does not: +0.

**The corrected rule [M]:** Cloudflare's read meter counts **rows the engine touches** — explicit
result rows plus the row probe of a single-row `DELETE` — and the local cursor reports only
explicit reads. A plain `INSERT` touches nothing.

**What this changes:** the "≈ 38 % growth of the read axis" E12-a §8 warned about **does not
happen**, and E12-a §8's testable prediction (V6 on the platform ≈ 856 reads) is **withdrawn** —
the plain-insert half of it is already disproved. The read axis E6 carries (≈ 1,130 rows/match)
stays an **upper bound**, not an undercount. **E12-g** keeps its value but loses its urgency: it
now pins the *upsert* and *point-lookup* shapes (the design uses an upsert per Trick checkpoint,
and the local cursor does report a read for it), not a capacity-scale correction.

## 8. What this settles

1. **The Free plan bills inbound WebSocket messages 1:1 under hibernation, and 0 under the plain
   API. [M]** There is no 20:1 ratio. E6's conservative reading is the real one; the documented
   reading is withdrawn.
2. **Outgoing messages are free, and messages are never Worker requests. [M]**
3. **An open match costs 0.128 GB·s per second in plain mode; hibernation removes the idle cost
   but prices every message as a request. [M]** The two modes trade requests against duration, and
   **plain mode carries the higher ceiling** (112 vs 88 matches/day).
4. **`setAlarm()` is free; the fire is one DO request and zero rows. [M]** Bot-continuation ticks
   are request-priced, not row-priced.
5. **`DELETE FROM` and row-by-row deletes write the same rows; row-by-row costs ≈ 2 reads per row
   deleted. [M]** The cleanup shape is a one-line choice worth **856 rows read per match**.
6. **The read-meter divergence E12-a reported was the harness's own scan. [M]** No per-write read
   charge exists; E12-a §8's hypothesis and its V6 prediction are withdrawn.

## 9. What this does NOT settle

1. **Billing behaviour [x].** The counters are measured; a month is not priced. The account has no
   subscriptions at all, so there is nothing to price.
2. **E12-b — the KV-style API (`ctx.storage.put` / `delete`) [x].** Not run. Its relevance is
   reduced by the design: the durable state is SQL, and the WS harness's incidental `put` calls
   (16 rows written for the counters) are not a controlled measurement.
3. **E12-f — the repeat run [x].** Not run. E12-c is itself a repeat of E12-a's method on a second
   table shape, which is the separation E12-f was meant to buy.
4. **E12-g — the read meter, precisely [x].** §7 closes the *divergence*; the upsert and
   point-lookup shapes are still unmeasured. Low urgency: the axis is ~1.8% of its limit at the
   target (§10).
5. **E12-h — V3 (`WITHOUT ROWID`) on the platform [x].** Still unmeasured; the ×1 shape used here
   is V1.
6. **E12-i — D1's 27 rows/settlement [x].** A different counter on a different product; untouched.
7. **At-limit behaviour [x].** Nothing was driven past 100% of a meter, so the *failure order* the
   90% guardrail must watch is still unmeasured (E6 §14 step 8).
8. **Production duration at other match lengths.** 0.128 GB·s/s is linear and measured; the
   capacity figures below assume 15-minute matches (§10.1 gives the sensitivity).

## 10. The capacity model at the owner's target

**Owner ruling, 2026-10-30:** the V1 planning target is **80 completed matches per day**, with a
**refusal ceiling of 88/day**; the mode is **plain (always-awake)**; the row shape is **×1 (no
second index)**. Record: `docs/decisions/2026-10-30-runtime-decision-o06-cloudflare.md`.

| Meter | Free-plan limit | Per match | **At 80/day** | % of limit | Daily ceiling (hard / at 90%) | Binding? |
|---|---|---|---|---|---|---|
| **DO duration (GB·s)** | 13,000 | **115.2** | **9,216** | **70.9%** | **112 / 101** | **YES** |
| DO rows written | 100,000 | 428 | 34,240 | 34.2% | 233 / 210 | no |
| DO requests (WebSocket) | 100,000 | ≈ 7 | ≈ 560 | 0.6% | ≈ 14,000 | no |
| DO rows read | 5,000,000 | ≤ 1,130 [m] | ≤ 90,400 | 1.8% | ≥ 4,400 | no |
| D1 rows written (settlement) | 100,000 | 27 | 2,160 | 2.2% | 3,703 / 3,333 | no |
| Worker requests | 100,000 | ≤ 7 | ≤ 560 | 0.6% | ≥ 14,000 | no |

**The binding meter is duration, exactly as E6 predicted for the ×1 shape without hibernation**
(E6 §5, revised by E12-a §7.1). **80/day sits at 71% of the duration limit** — 10% below the 88/day
refusal ceiling and 29% below the hard limit.

**Two properties of the 88/day ceiling worth stating:**

- it is **exactly the hibernation-mode request ceiling** (100,000 / 1,134 = 88.2), so the same
  number is safe if the mode ever has to change;
- at 88/day, duration is **78.0%** of its limit and **86.6%** of the 90% guardrail — still under
  the line where the system stops admitting new matches.

### 10.1 The sensitivity the target has to carry: match length

Duration is linear in wall-clock time, so the target is only as safe as the **average match
length** it assumes:

| Average match length | GB·s per match | 80/day | 88/day |
|---|---|---|---|
| 10 min | 76.8 | 47.3% | 52.0% |
| **15 min (the model's assumption)** | **115.2** | **70.9%** | **78.0%** |
| 19 min 3 s | 146.3 | **90.0% — the guardrail** | 99.0% |
| 20 min | 153.6 | 94.5% ✗ | 104% ✗ |

**The 80/day target holds inside the 90% guardrail for average matches up to ≈ 19 minutes**; at
88/day, up to ≈ 17.3 minutes. Sa'ayda extensions and bidding-heavy tables lengthen matches, so the
guardrail — not the 88 ceiling — is what protects the tier on a long-match day. This is the single
number to watch in closed testing.

### 10.2 The engineering rules this measurement makes non-negotiable

1. **A silent socket is closed after 90 seconds, with a client heartbeat.** Duration is charged
   while **any** socket is attached, so one zombie socket keeps a whole table's 0.128 GB·s/s
   running. RD19's bot takeover frees the *seat*, not the *socket*.
2. **Cleanup is one `DELETE FROM`, never a loop.** 856 rows read saved per match, one statement
   instead of 428.
3. **No second index on the match table.** The ×1 shape is the measured 428 rows/match; a second
   index doubles it (E12-a: V2 = 856).
4. **Alarm ticks are the bot-continuation currency** — 1 request each, 0 rows. The chunking choice
   (E6 §5.2) is a request-budget question, not a rows one.

## 11. Artifacts

| Artifact | Where |
|---|---|
| This report | `docs/implementation/Stage0_E12d_e_c_Platform_Meters_Report.md` (this file) |
| E12-d results (4 runs) | `D:/cache/stage0/e12/results/e12-d-d1-results.json` … `-d4-results.json` (scratch, outside the repo) |
| E12-c results | `D:/cache/stage0/e12/results/e12-c-results.json` |
| The WebSocket harness | `D:/cache/stage0/e12/ws/` — `server.mjs`, `client.mjs`, `collect-d.mjs`, `instance-rows.mjs`, the two `wrangler-*.toml` |
| The step-3 harness | `D:/cache/stage0/e12/alarm/` — `worker.mjs`, `wrangler.toml`, `collect-c.mjs`, `one-instance.mjs`, `sched-invocations.mjs` |
| The token (never in the repo) | `D:/cache/stage0/e12/cf.env` |
| The companion reports | `Stage0_E12a_Platform_Rows_Report.md`, `Stage0_E7b_DO_Rows_Report.md`, `Stage0_E7_Backup_Outbox_Report.md`, `Stage0_E5_Capacity_Model_Report.md`, `Stage0_E6_Target_Workload_Report.md` |

**No secret is in this repository**, and **the account is empty**: four workers deleted, the KV
namespace deleted, no Durable Object namespaces left. The API token is the owner's to revoke.

> **Correction (owner, 2026-10-30 — the account is NOT retired).** The account is **kept**: it is the
> standing test surface every platform leg runs on (E9's included), and **the token stays valid**
> (outside the repository, never in a report or a message). The line above stays true about the
> **code** — workers and KV are deleted after each experiment — and is withdrawn about the account
> itself. `docs/decisions/2026-10-30-test-account-retained.md`.

## 12. Reproduction

```bash
# ── E12-d / E12-e: deploy the WS server + client, let the cron drive one run, collect ──
cd /d/cache/stage0/e12/ws && . ../cf.env && \
  node D:/cache/stage0/node_modules/wrangler/bin/wrangler.js deploy -c wrangler-server.toml && \
  node D:/cache/stage0/node_modules/wrangler/bin/wrangler.js deploy -c wrangler-client.toml
node collect-d.mjs d4        # writes ../results/e12-d-d4-results.json

# ── E12-c: deploy the cron worker, wait for the record, collect ──
cd /d/cache/stage0/e12/alarm && . ../cf.env && \
  node D:/cache/stage0/node_modules/wrangler/bin/wrangler.js deploy -c wrangler.toml
node watch.mjs 180000        # prints the KV record when the cron writes it
node collect-c.mjs           # writes ../results/e12-c-results.json
```

All scripts use Node built-ins and the pinned local wrangler only — no installs, no PATH or admin
changes (`docs/engineering/NODEJS_SAFE_USAGE.md`).

## 13. Owner summary (Egyptian Arabic)

**الثلاث خطوات خلصوا، والنتيجة بتغيّر قرارين كبار.**

1. **الرسايل على WebSocket بتتحسب واحدة بواحدة، مش 20 بواحد.** في وضع الـ hibernation،
   **1,130 رسالة = 1,134 طلب** على الـDO (الـ4 الزيادة هما قفل الـ4 اتصالات). يعني رقم الـ20:1
   اللي كان في الخطة **مش موجود على الخطة المجانية**.
2. **الوضع العادي (always-awake) بيكلّف صفر طلبات للرسايل.** نفس الـ1,130 رسالة = **7 طلبات بس**
   (الـ4 اتصالات + 3 نداءات تحكم). المقابل: المباراة المفتوحة بتكلّف **مدة**.
3. **المباراة المفتوحة بتكلّف 0.128 جيجا-ثانية في الثانية.** 180 ثانية سكتة = **22.97 GB·s**،
   ومباراة 15 دقيقة = **115.2 GB·s**. الـ hibernation بيشيل تكلفة السكتة خالص (0.0406 مقابل
   0.0466) — بس بيدفع تمنها 1,130 طلب لكل مباراة.
4. **يعني الوضعين مش ببلاش في العدّادين:** الـ hibernation سقفه **88 مباراة/يوم** (طلبات)،
   والوضع العادي سقفه **112 مباراة/يوم** (مدة). **الوضع العادي أفضل** — وكمان التصميم محتاج
   مؤقتات للبوتات، وكل مؤقت في وضع الـ hibernation بيصحّي الـDO بطلب كمان.
5. **الـ`setAlarm` ببلاش؛ اللي بيتحاسب هو الإطلاق: طلب واحد، صفر صفوف.** يعني مؤقتات البوتات
   **طلبات** مش صفوف — أرخص من اللي النموذج كان فاكره.
6. **الحذف: نفس عدد الصفوف، بس `DELETE FROM` بيوفّر 856 قراءة لكل مباراة** (سطر واحد بدل 428 سطر).
7. **تصحيح مهم:** الـ"430 قراءة" اللي في تقرير E12-a مكانتش رسوم على كل كتابة — كانت **الاستعلام
   الختامي بتاع السكربت نفسه** اللي عدّ الـ428 صف. عشرة إدخالات هنا كلّفت **صفر قراءات**. يعني
   عدّاد القراءات مش هيقفز 38% زي ما كنا خايفين.

**بالأرقام عند هدفك (80 مباراة/يوم):** المدة **71%** من الحد (يعني ده اللي بيحكم)، الصفوف المكتوبة
34%، الطلبات أقل من 1%، وD1 2%. وسقف الرفض 88/يوم بيخليك تحت خط الـ90% حتى لو المباريات طالت
لحد **17 دقيقة** في المتوسط — ولو المتوسط وصل 19 دقيقة، خط الـ90% هو اللي بيحميك مش الـ88.
