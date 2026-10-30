# Stage 0 — E7b: the Durable Object's rows-written per write

**Status: MEASURED LOCALLY, 2026-10-30.** The E5/E6 capacity model carries a per-match
Durable-Object rows-written figure of **427.9** (234 trick checkpoints + 193.9 auction actions)
and then applies an **unexplained "×2 rows allowance"** on top of it. E7b measures the real
multiplier for each candidate persistence design, so the ×2 becomes a **measurement** instead of a
precaution.

**The headline [M]:** the ×2 is **not a platform precaution — it is a schema choice.**

| Design | Rows written per write | Factor vs the 428/match model |
|---|---|---|
| **V1** — rowid table, no secondary index | **1** | **×1** |
| **V2** — rowid table, composite `PRIMARY KEY (match_id, seq)` | **2** | **×2** |
| **V3** — the same key, **`WITHOUT ROWID`** | **1** | **×1** |
| **V4** — V1 + a secondary index on `(match_id, seq)` | **2** | **×2** |
| **V5** — V1 + `UNIQUE (match_id, seq)` | **2** | **×2** |
| **V6** — one state row, updated in place | **1** (+1 row read) | **×1** |

**A LOCAL COUNT IS NOT PROOF OF WHAT CLOUDFARE BILLS.** Every number in this report comes from
the local `workerd` runtime's own counter. Whether Cloudflare's metered "rows written" is counted
the same way is **[x] — a real account is required** (§8, and the E12 add-on in §9).

> **POINTER NOTE (2026-10-30 — E12-a is DONE, on a throwaway account).** The write axis is now
> **measured on Cloudflare itself**, in
> [`Stage0_E12a_Platform_Rows_Report.md`](Stage0_E12a_Platform_Rows_Report.md): Cloudflare's
> metered rows-written count is **exactly** the DO's own cursor count — **V1 = 428 for 428
> inserts (×1)**, **V2 = 856 for 428 inserts (×2)** — read from the platform's own counter for the
> same instances, on the Free plan, with no billing. **So the ×2 above is confirmed as what
> Cloudflare bills, for the V2-shaped schema.** Two statements in this report's body are
> **superseded**: "a local count is not proof of what Cloudflare bills" (the header) is settled
> **on the write axis**, and "no real Cloudflare account" (below) is no longer true. **The read
> axis is NOT settled** — Cloudflare counted **430 rows read** where the DO's cursor reported
> **0** (E12-a §8), so the local read figures below still need their own step. The body below is
> otherwise unchanged.

**No real Cloudflare account, no deploy, no external quota, no merge, no PR.** Branch
`stage0/e7-backup-outbox`. The companion E7 report is
[`Stage0_E7_Backup_Outbox_Report.md`](Stage0_E7_Backup_Outbox_Report.md).

**Labels:** **[M] measured** · **[m] modelled** · **[u] unknown** · **[x] needs a real Cloudflare
account**.

---

## 1. What the experiment is

A Ranked match runs inside a Durable Object. Every trick checkpoint and every auction action is a
write. The capacity model says *how many* writes a match costs (428) but had to guess *how many
rows each write costs* — and it guessed "×2, to be safe".

The guess is now unnecessary: the DO's own SQL cursor reports `rowsWritten` and `rowsRead` for
every statement. E7b writes one full match's worth of events into six different table shapes and
reads the counter.

## 2. Method

| Piece | What it is |
|---|---|
| Runtime | `wrangler dev --local` (workerd **1.20261023.1**), **one SQLite-backed Durable Object** (`new_sqlite_classes = ["MatchDO"]`) |
| Account | **none** — no real Cloudflare account, no deploy, no external quota |
| Network | **127.0.0.1 only** (the wrangler dev server itself) |
| The counter | the DO SQL cursor's own **`rowsWritten` / `rowsRead`** (`ctx.storage.sql.exec(...)`) |
| The database size | the DO's SQLite file on disk (main + WAL) — **`PRAGMA` is not authorized in this runtime** (`SQLITE_AUTH`), so the size is measured on disk by the driver, not by `page_count` |
| Isolation | every operation runs against a **freshly named DO instance**, so no variant can see another's rows |

**The workload is not retyped.** The driver reads the per-match write counts from the E6 probe
(`D:/cache/stage0/e6/workerd-mix-FULL-NORMAL.json`, 50 seeds, FULL/NORMAL) and uses them as they
are:

| | |
|---|---|
| Probe | `D:/cache/stage0/e6/workerd-mix-FULL-NORMAL.json` |
| Matches in the probe | 50 |
| **Mean trick checkpoints** | **234** |
| **Mean bid actions** | **193.92** |
| **Writes per match** | **427.92** → **428** statements executed (rounded up) |

The model figure the factors are measured against is **428** — the E5/E6 model's own rounding of
the same probe.

### 2.1 One trap worth naming

The cursor's counters are **only final after the cursor is drained**. Reading `cur.rowsWritten`
before consuming the cursor silently reports **0** for writes and **1** for a 428-row scan. Every
measurement here calls `cur.toArray()` first, and the write figures were re-verified unchanged
(428 / 856) after the fix.

## 3. The six table shapes

| Variant | Schema | Note |
|---|---|---|
| **V1** | `events(id INTEGER PRIMARY KEY, payload TEXT)` | an append-only log with a rowid and **no** secondary index |
| **V2** | `events(match_id, seq, payload, PRIMARY KEY (match_id, seq))` | an **ordinary rowid** table; a composite `PRIMARY KEY` that is not the rowid is implemented as a **separate UNIQUE index** |
| **V3** | the same key, **`WITHOUT ROWID`** | the table **is** the index |
| **V4** | V1 + `CREATE INDEX events_ms ON events (match_id, seq)` | an explicit secondary index |
| **V5** | V1 + `UNIQUE (match_id, seq)` | the shape a durable "exactly once" key takes |
| **V6** | `state(id INTEGER PRIMARY KEY, seq, payload)`, written with `ON CONFLICT(id) DO UPDATE` | **one** state row, updated in place on every write |

## 4. The measurement [M]

428 writes per match, at three payload sizes (100 / 300 / 1,000 bytes).

| Variant | Payload | Statements | **rowsWritten** | **rowsWritten / write** | **Factor vs 428** | **Factor is** | DB file after the match |
|---|---|---|---|---|---|---|---|
| **V1** | 100 B | 428 | **428** | **1.00** | **1** | **1** | 78,288 B |
| V1 | 300 B | 428 | 428 | 1.00 | 1 | 1 | 164,808 B |
| V1 | 1,000 B | 428 | 428 | 1.00 | 1 | 1 | 469,688 B |
| **V2** | 100 B | 428 | **856** | **2.00** | **2** | **2** | 98,888 B |
| V2 | 300 B | 428 | 856 | 2.00 | 2 | 2 | 189,528 B |
| V2 | 1,000 B | 428 | 856 | 2.00 | 2 | 2 | 486,168 B |
| **V3** | 100 B | 428 | **428** | **1.00** | **1** | **1** | 82,408 B |
| V3 | 300 B | 428 | 428 | 1.00 | 1 | 1 | 177,168 B |
| V3 | 1,000 B | 428 | 428 | 1.00 | 1 | 1 | **2,043,528 B** |
| **V4** | 100 B | 428 | **856** | **2.00** | **2** | **2** | 98,888 B |
| V4 | 300 B | 428 | 856 | 2.00 | 2 | 2 | 193,648 B |
| V4 | 1,000 B | 428 | 856 | 2.00 | 2 | 2 | 486,168 B |
| **V5** | 100 B | 428 | **856** | **2.00** | **2** | **2** | 98,888 B |
| V5 | 300 B | 428 | 856 | 2.00 | 2 | 2 | 193,648 B |
| V5 | 1,000 B | 428 | 856 | 2.00 | 2 | 2 | 486,168 B |
| **V6** | 100 B | 428 | **428** | **1.00** | **1** | **1** | 4,120 B growth |
| V6 | 300 B | 428 | 428 | 1.00 | 1 | 1 | 4,120 B growth |
| V6 | 1,000 B | 428 | 428 | 1.00 | 1 | 1 | 4,120 B growth |

**V6 also reads 428 rows** (one per upsert: the conflict target is read before the update). That
is a read cost, not a write cost, and reads are not the metered axis here.

### 4.1 Which designs cause the ×2 — and which do not

| Causes **×2** | Why |
|---|---|
| **V2** — composite `PRIMARY KEY` on an ordinary rowid table | the key is a **separate UNIQUE index**; the row is written once in the table and once in the index |
| **V4** — an explicit secondary index | the index entry is a second written row |
| **V5** — a `UNIQUE` constraint | same mechanism: a second index |

| Does **not** cause the ×2 | Why |
|---|---|
| **V1** — a plain rowid table | `INTEGER PRIMARY KEY` **is** the rowid; there is no second structure to write |
| **V3** — `WITHOUT ROWID` with the same composite key | the table **is** the index — one b-tree, one written row, and the uniqueness still holds |
| **V6** — one state row upserted in place | one row updated |

**The useful consequence: V3 buys both things at ×1.** A `WITHOUT ROWID` table with
`PRIMARY KEY (match_id, seq)` has a **unique** key — which is exactly what the exactly-once
settlement protocol needs — and still costs **one** row per write. V2 has the same uniqueness and
costs **two**.

### 4.2 Payload size moves the file, not the row count [M]

| Payload | rowsWritten / write | **V1** file | **V3** file |
|---|---|---|---|
| 100 B | 1 (V1, V3, V6) / 2 (V2, V4, V5) | 78,288 B | 82,408 B |
| 300 B | unchanged | 164,808 B | 177,168 B |
| 1,000 B | unchanged | 469,688 B | **2,043,528 B** |

**The meter counts rows, not bytes** — the row count is identical at every payload size. Payload
size moves the **database size** and the **read cost** only.

**V3 is the largest file at every payload size** (2.04 MB vs V1's 0.47 MB at 1,000 B). The
mechanism — a `WITHOUT ROWID` b-tree keyed by `(match_id, seq)` carrying the payload in the key
b-tree — is a **[m] model**; the size itself is **[M] measured**. The production key spread is one
match per DO, which is what was measured, so the shape is representative; the exact bytes are not
a production forecast.

## 5. V7 — batching does not change the row count [M]

The same 428 rows, written three ways.

| Variant | Mode | Statements | **rowsWritten** | Time |
|---|---|---|---|---|
| **V1** | **one** statement, all rows | **0** — rejected: *"too many SQL variables at offset 437: SQLITE_ERROR"* | 0 | — |
| V1 | **chunked** (100 tuples per statement) | 5 | **428** | 1 ms |
| V1 | **428 separate statements** | 428 | **428** | 5 ms |
| V1 | **428 statements inside `transactionSync`** | 428 | **428** | 4 ms |
| **V2** | **one** statement, all rows | **0** — rejected: *"too many SQL variables at offset 318: SQLITE_ERROR"* | 0 | — |
| V2 | **chunked** (33 tuples per statement) | 13 | **856** | 1 ms |
| V2 | **428 separate statements** | 428 | **856** | 6 ms |

**Batching is a latency optimisation, not a metering one.** The row count is identical whether the
rows go in one statement, in five, or in 428 — and `transactionSync` does not change it either.
What batching buys is ~5 ms of wall-clock per match.

### 5.1 The runtime's bind-variable cap [M]

The single-statement form fails, and the failure is a hard cap: **100 bind variables per
statement**. Measured by bisection:

| Variant | Variables per row | **Max tuples per statement** | Variables used |
|---|---|---|---|
| V1 | 1 | **100** | 100 |
| V2 | 3 | **33** | 99 |

**The budget is variables, not rows** — so a 3-column table fits 33 rows per statement where a
1-column table fits 100. Any production batching must chunk by **variables ÷ variables-per-row**,
not by rows.

## 6. V8 — cleanup after settlement [M]

| Variant | Mode | Statements | **rowsWritten** | File before → after | Note |
|---|---|---|---|---|---|
| V1 | **row by row** (`DELETE … WHERE key = ?`) | 428 | **428** | 78,288 → 131,848 B | **1 row written per deleted row — no index multiplier** |
| V1 | **`DELETE FROM events`** | **1** | **428** | 78,288 → 90,648 B | same row count, one statement |
| V1 | **`ctx.storage.deleteAll()`** | 1 | **0** | 78,288 → **4,096 B** | **it destroys the SQL tables** |
| V2 | row by row | 428 | 428 | 98,888 → 168,928 B | **no index multiplier on delete** |
| V2 | `DELETE FROM events` | 1 | 428 | 98,888 → 115,368 B | |
| V2 | `deleteAll()` | 1 | 0 | 98,888 → **4,096 B** | |
| V4 | row by row | 428 | 428 | 98,888 → 168,928 B | **no index multiplier on delete** |
| V4 | `DELETE FROM events` | 1 | 428 | 98,888 → 115,368 B | |
| V4 | `deleteAll()` | 1 | 0 | 98,888 → **4,096 B** | |

**Three findings:**

1. **A delete costs 1 row written per deleted row, with no index multiplier** — V4 (which costs
   ×2 to insert) deletes at ×1. The ×2 is an **insert-side** cost only.
2. **`DELETE FROM table` is strictly better than row-by-row**: the same 428 rows written, **one**
   statement instead of 428, and a smaller file afterwards.
3. **`ctx.storage.deleteAll()` does not delete SQL rows — it destroys the SQL tables.** After it,
   the content query fails with **`no such table: events: SQLITE_ERROR`**, the cursor reports
   **0** rows written, and the file shrinks to **4,096 bytes**. It is a KV-style "wipe the
   storage" call, and on a SQLite-backed DO it takes the schema with it. **It is not a cleanup
   mechanism for a SQL-backed DO.**

## 7. V9 — the reconnect snapshot read [M]

| Variant | **"last state"** (the reconnect snapshot) | **"all events"** (the full log) | A `COUNT(*)` |
|---|---|---|---|
| V1 | **1 row read** | 428 rows read | 428 rows read |
| V2 | **1 row read** | 428 rows read | 428 rows read |
| V3 | **1 row read** | 428 rows read | 428 rows read |
| V6 | **1 row read** | **1 row read** | 1 row read |

**The reconnect snapshot is 1 row read in every variant** — provided the `ORDER BY` is written so
the index can serve it. That is not automatic: on V2/V3, an `ORDER BY match_id, seq DESC` (mixed
directions) **cannot** use the index and costs **856 rows read** instead of 1 — the whole table is
scanned. The working form is **`ORDER BY match_id DESC, seq DESC`** (both columns the same
direction), which the index serves for **1 row read**.

A `COUNT(*)` is a **full scan** (428 rows read) even though it returns one row.

## 8. What E7b does NOT prove

| # | The question | Why it cannot be answered locally |
|---|---|---|
| 1 | **Does Cloudflare's metered "rows written" equal the local cursor's count?** | The local counter demonstrably counts **index rows** as well as table rows, but the billing definition is Cloudflare's. **The 25.4 rows/settlement in E7 and the ×1/×2 factors here are local counts.** |
| 2 | **What does `ctx.storage.put()` / `ctx.storage.delete()` cost?** | The KV-style API **does not expose a row counter at all** — there is no `rowsWritten` on it. Only a real account's dashboard can price it. |
| 3 | **What does `setAlarm()` cost?** | Documented as **one row written**; the SQL cursor cannot see it (alarms are not SQL). A dashboard diff can. |
| 4 | **How are WebSocket messages metered (the 20:1 allowance)?** | Not a SQL counter at all. Needs the real account's counters. |
| 5 | **How are Worker requests counted?** | Same — the local server has no billing counter. |
| 6 | **What does duration (GB-s) cost, and does hibernation work?** | The local runtime does not bill duration and does not reproduce hibernation under load. |
| 7 | **Does the DO's own free tier behave differently?** | The local runtime has no quota, no Free-plan enforcement, no eviction policy. |

**None of these is guessed at anywhere in this report.** They are listed, with the exact run each
one needs, in §9.

## 9. E12 add-on — what to run on a throwaway Free account, **with the owner's approval**

**This section is a request, not a plan of action. Nothing here has been run, and nothing here may
be run without the owner's explicit approval.** It requires creating a **throwaway Cloudflare
Free account** and deploying a **throwaway Worker + DO** to it. It touches the owner's account
and consumes that account's quota, which is exactly what the current brief forbids.

> **POINTER NOTE (2026-10-30 — the preparation is approved; every step is still gated).** The
> owner ruled on 2026-10-30 that **preparing** E12 is approved (a **throwaway** Free account, **no
> real data**, the steps **one at a time, each approved before it runs**), and that **no
> real-account step may start before the owner confirms it**. The **step-by-step approval
> checklist is §4 of `docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md`** (the
> one-page Arabic version is in that record).
>
> **POINTER NOTE (2026-10-30 — E12-a HAS NOW RUN; the body below is stale on that point).** The
> owner confirmed the throwaway account (`c6321eda25af9e06234358928272a0fa`, **Free plan, no
> subscriptions, no billing**) and approved **E12-a alone**. It ran, and the result is in
> [`Stage0_E12a_Platform_Rows_Report.md`](Stage0_E12a_Platform_Rows_Report.md): **Cloudflare's
> metered rows-written count equals the DO's own cursor count exactly — V1 = 428 (×1), V2 = 856
> (×2)** — so the sentence above ("nothing here has been run, no account exists, no network call
> to Cloudflare has been made") is **no longer true for E12-a**, and the body below is **stale on
> that point**. **E12-b…E12-f have NOT run** and remain gated, one step at a time. The body
> below is otherwise unchanged.

**E12-a — does the local counter equal the billed rows? [x]**
Deploy one DO with the **V1** and **V2** schemas on a throwaway account. Write exactly **428
single-row events** into each. Read the account's **dashboard rows-written** counter before and
after, and compare with the local cursor's **428** (V1) and **856** (V2).
**Decides:** whether the ×1/×2 factors in §4 are Cloudflare's factors or only this runtime's. If
the dashboard shows 428/856, the capacity model can drop the ×2 allowance; if it shows something
else, the whole §4 table is re-measured.

**E12-b — the KV-style API. [x]**
Call `ctx.storage.put()` and `ctx.storage.delete()` a known number of times (say 100 each) and
read the dashboard's rows-written delta.
**Decides:** the price of the KV-style path, which **no local counter exposes**.

**E12-c — alarms. [x]**
Issue **one** `setAlarm()`, let it fire, and read the dashboard's rows-written delta.
**Decides:** whether the documented "one row written" per alarm is what is billed — the number
E7's §11 inactivity-timer design would carry.

**E12-d — WebSocket and Worker requests. [x]**
Open **4** WebSocket connections to one DO, push **one match's worth** of messages (≈ 1,130: 936
card actions + 194 auction actions), and read the dashboard's **DO requests** and **Worker
requests** counters.
**Decides:** whether the Free plan honours the 20:1 allowance for incoming messages, and whether
outgoing messages are billed — the two E6 figures that are still modelled.

**E12-e — duration and hibernation. [x]**
Hold a DO idle with a WebSocket attached for a full match-length window, then disconnect it, then
force an eviction, and read the dashboard's **GB-s duration** and **hibernation** indicators.
**Decides:** whether holding a match open costs duration (the E12 row of the plan) — which is
also the cost of the 60 s all-humans-gone rule's idle window.

**E12-f — the local counter, one more time. [x]**
Repeat E12-a's write on the **throwaway account** and diff the dashboard against the same
cursor's numbers, to separate "the counter is wrong" from "the counter is right and the
definition differs".

**Only E12-a is needed to close the ×2 question.** E12-b…E12-f are the remaining
platform-side unknowns listed in §8, in the order they block the E5/E6 capacity model.

## 10. The recommended row layout

**One design clearly wins on the metered axis: `WITHOUT ROWID` with a composite primary key.**

```sql
CREATE TABLE events (
  match_id TEXT NOT NULL,
  seq      INTEGER NOT NULL,
  payload  TEXT NOT NULL,
  PRIMARY KEY (match_id, seq)
) WITHOUT ROWID;
```

**Why [M]:**

- it costs **1 row written per write** (V3 = 428 for 428 writes), against **2** for the same key
  on an ordinary rowid table (V2 = 856);
- the primary key is still **unique**, which is what the exactly-once settlement protocol needs —
  V3 keeps the guarantee that V2 keeps, at half the write cost;
- the reconnect snapshot is **1 row read** (§7), provided the `ORDER BY` puts both key columns in
  the same direction.

**The caveats, in order of weight:**

1. **This is a local count, not proof of what Cloudflare bills.** E12-a must confirm it (§9).

   > **POINTER NOTE (2026-10-30 — confirmed for V1/V2; V3 is still [x]).** E12-a ran on a
   > throwaway Free account: Cloudflare's metered rows-written count **equals** the DO's own
   > cursor count — **V1 = 428 (×1)**, **V2 = 856 (×2)** — so the local count **is** what
   > Cloudflare bills for those two shapes
   > ([`Stage0_E12a_Platform_Rows_Report.md`](Stage0_E12a_Platform_Rows_Report.md)). **The ×2 is
   > confirmed, not a precaution.** **What is still unproven is V3** — that `WITHOUT ROWID` also
   > meters at ×1 on the platform — so caveat 1 now applies to **V3 alone**, and the
   > recommendation below is unchanged: it still rests on V3's local ×1 until **E12-h** measures
   > it on the platform.

2. **V3 was the largest file at every payload size** (§4.2) — 2.04 MB vs V1's 0.47 MB at 1,000 B
   payloads. If the DO's storage budget is tight, that is the price of the ×1.
3. **Any additional lookup index adds ×1 back.** If the design needs a second key (by uid, by
   round), that index is a second written row per write — the ×2 returns.
4. **Batching must respect the 100-variable cap** (§5.1), not a row count.
5. **Cleanup must be `DELETE FROM …`, never `deleteAll()`** (§6).
6. **The single-state-row design (V6) is ×1 but is not a substitute** — it keeps no event log, so
   "what happened at seq 12?" is unanswerable, and it reads a row on every write.

**This is a recommendation, not a decision.** Which layout the Ranked match DO uses is
**D-E7-7** in the E7 report (§15) — the owner's to make.

## 11. Artifacts

| Artifact | Where |
|---|---|
| The report | `docs/implementation/Stage0_E7b_DO_Rows_Report.md` (this file) |
| The worker, the driver, the evidence | `D:/cache/stage0/e7b/` (scratch, outside the repo): `worker.mjs`, `driver.mjs`, `wrangler.toml`, `results/e7b-do-rows-evidence.json` |
| The console logs (3 runs) | `D:/cache/stage0/e7b/logs/run1-console.txt`, `run2-console.txt`, `run3-console.txt` |
| The workload probe (the counts, not retyped) | `D:/cache/stage0/e6/workerd-mix-FULL-NORMAL.json` |
| The companion E7 report | `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` |

## 12. Reproduction

```bash
# the DO rows-per-write experiment (V1–V9); writes results/e7b-do-rows-evidence.json
node D:/cache/stage0/e7b/driver.mjs
```

The driver spawns a local `wrangler dev --local`, runs each variant against a **freshly named** DO
instance, and records the cursor counters and the on-disk file size for every operation. It uses
only Node built-ins and the pinned local wrangler — no installs, no PATH or admin changes, no
network beyond `127.0.0.1` (`docs/engineering/NODEJS_SAFE_USAGE.md`).
