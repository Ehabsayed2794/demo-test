# Stage 0 — E10: cleanup, retention and storage (local only, no account)

- **Date:** 2026-10-31
- **Branch:** `stage0/e10-retention` · **Base:** `ef801178f368398ac8c2b7f97acd32b1577fd146`
  (tip of `stage0/e8-d1-atomicity`)
- **Harness:** `D:\cache\stage0\e10\` (outside the repository). The E8 harness
  (`D:\cache\stage0\e8\`) was **not** modified — it is evidence.
- **Run:** `2026-10-31T14-59-47-565Z` · **65 checks, 0 failures, exit 0**
- **Runtime:** local Miniflare (`wrangler 4.155.0 dev --local --port 8986`) under
  Node `v22.14.0` (Gradle's portable Node, invoked by explicit path). No Cloudflare
  account, no token, no API call, no deploy, no `wrangler --remote`.
- **Labels:** **[M] measured** · **[m] modelled** · **[u] unknown** · **[x] needs a real account**

Every number below is read from a store **after** the operation it describes; nothing is
asserted from an in-flight promise. The state directory is wiped before every run.

---

## 1. The question and the pass criterion (verbatim)

**Question —** `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md:1181` (the Stage-0 table):

> Do retention and cleanup fit request and storage limits?

**Question —** `docs/implementation/Stage0_Remaining_Steps.md:161` (§3):

> Do retention and cleanup fit the request, rows and storage limits?

**Pass / decision criterion —** `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md:1181`:

> Fits free tier; retention policy costed

**Method —** `docs/implementation/Stage0_Remaining_Steps.md:168`:

> Model retention windows against the storage limit and the measured per-match row count;
> measure a real cleanup sweep on a throwaway account if the model shows pressure.

**Effort:** 6–10 h. **Blocks:** G5. (`docs/implementation/Stage0_Remaining_Steps.md:170`)

**Both forms of the question are quoted byte-exactly.** They differ by one word ("request and
storage limits" in the plan, "request, rows and storage limits" in the remaining-steps doc);
neither changed on 2026-10-31 — the last commit touching either file is `ef80117`
("Docs(E8): quote the limits page's exact spacing"), dated 2026-10-31.

**Did any of §0's five documents change since 2026-10-31?** No. All five
(`docs/engineering/NODEJS_SAFE_USAGE.md`, `docs/implementation/Stage0_Remaining_Steps.md`,
`docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md`, `docs/implementation/Stage0_E8_D1_Atomicity_Report.md`,
`docs/implementation/Stage0_E12d_e_c_Platform_Meters_Report.md`) were read **in full** at the
base SHA and were last modified by the E8 report's own commit on 2026-10-31.

---

## 2. Verdict

**PASS on the local evidence — "Fits free tier; retention policy costed."**

At the plan's target of **80 matches/day**:

- the **Durable Object rows-written meter** is the one under pressure: **68,320 rows/day =
  68.32%** of the free 100,000/day allowance (the cleanup's `DELETE FROM log` charges one
  metered row per deleted log row, which is *not* visible in E12-a's insert-only figure);
- **storage is not the constraint**: a full year of matches costs **1.55 GB** of DO storage
  (28.96% of the free 5 GB account allowance) and **8.4 MB** of D1 (0.157%);
- **the D1 rows-read meter is the one that breaks over time**: the settlement's derivation
  reads the whole ledger, the ledger is the players' running balance and is therefore never
  pruned, so the allowance is crossed after **≈195 days** at 80 matches/day — and **the
  retention window does not delay it**.

**The one-line binding constraint:**

> The DO rows-written meter binds in the steady state; the D1 rows-read meter bounds the
> product's age (~195 days at 80/day) and no retention window can move it.

The retention policy itself (the window) is **the owner's decision** — §11 costs four
windows (7 / 30 / 90 / 365 days) and recommends one.

---

## 3. Harness fidelity — four proofs

The E10 harness is the **E8 harness plus additions only**. Proofs:

### 3.1 The worker diff — no E8 line was removed

```
diff --unified=0 D:/cache/stage0/e8/worker.mjs D:/cache/stage0/e10/worker.mjs
  removed lines: 0
  added lines:   1,058   (1,029 non-blank + 29 blank)
  e8/worker.mjs:  1,486 lines
  e10/worker.mjs: 2,544 lines
```

1,486 + 1,058 = 2,544 — the line counts agree.

Every addition is inside the `/e10` code paths (`e10*` methods, the `/e10/*` routes,
`e10RetentionApply`, `e10CleanupInline`, `e10SizeGauge`, the tombstone schema, the
`/e10/tombstone/summary` route and the `/e10/keyless/balances` route). The settlement
protocol — `/prelude`, `/state`, `/step`, `/settle/finalize|enqueue|relay|ack|backup|cleanup`
— was not touched.

| file | sha256 |
|---|---|
| `D:/cache/stage0/e8/worker.mjs` (the E8 worker this harness was copied from) | `51611b267ca0c9622d44f34047464733b3c3b959feadb49c0db8afe9ad595c0d` |
| `D:/cache/stage0/e10/worker.mjs` | `987dbf88c13f4f14311f49d57199c8478679e95590132c6eb76c9a9c7edb8102` |

### 3.2 The eleven settlement functions are byte-identical

Re-hashed from the E10 worker and compared with E8's own record
(`D:/cache/stage0/e8/results/function-hashes.json`) — **11/11 identical, 0 changed**:

| function | bytes | sha256 | identical to E8 |
|---|---|---|---|
| `d1Run` | 261 | `530b607b52d4b6bb0ee2356671b740a0acb2c450594b6fd026b1ca2fe7ef72b5` | yes |
| `d1Batch` | 311 | `846d245012d96b33a4f18161b5e1686a5e5ff77275811eccae4e8fefce6158dd` | yes |
| `balances` | 307 | `49d7d85a917be171859708556fc429c505ba6532acac2ec4f48b221e4b60222a` | yes |
| `enqueueOnce` | 1,104 | `48b5e1858ea155b96c685f710b409bc73ae5b4080d2130a1fe57736f4d864acb` | yes |
| `storedRows` | 227 | `69f61fde0d6ee75ca69b0f93716216d452d68a350a8017ee3a9c2f840fc2b729` | yes |
| `relayOnce` | 1,453 | `55057805ca926c8b6eb40c86c7617d08efb4dd659b52cb833cc2f8e8c75066a0` | yes |
| `ackOnce` | 260 | `200289636a995f0602ced3b800ecdc3841fcc5101cfa0a5ce159235e5cd90a83` | yes |
| `backupOnce` | 2,286 | `d2259365fef2e84bcf5dc7fb89c44d05a28359173c18632b019a18907b8fcabb` | yes |
| `deriveIntent` | 279 | `96ca19631e0487f7c520361583e5a4c8c0bd40c795f11057bef11ea8d0d4ad9c` | yes |
| `finishSettlement` | 272 | `b3286fefd5b8150f15b335e4d45b51ffacf7d4fa1f92b3919a9c29cf009a48c0` | yes |
| `reconcile` | 815 | `0bfeaef6111be29482f94f182b3a519ebd8b8528bd83060aa49f5003755da3d5` | yes |

### 3.3 The engine bundle is unchanged

All four bundle files are byte-identical between the E8 and the E10 harness:

| file | sha256 |
|---|---|
| `k/stage0.js` | `46153a843fa81132a8d22c17acc84403724c3e9c68ad4768f0746c6cf89ca372` |
| `k/Estemshan-engine.js` | `1599df0572a537487ce3fe54856a0f5d0d2a82173307503dfcca829721c7861f` |
| `k/kotlin-kotlin-stdlib.js` | `e5737061a6983cc54c0ea1017d44d4901de3e4cdbedc23675a6c0edf8d1d9b8a` |
| `k/kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js` | `dd4ef3b5bd828db9606eb1c4dd0b629e134e7935bb1631ba47bd773fb9deac0b` |

### 3.4 `wrangler.toml` — only the name and the placeholder database ids changed

```
$ diff D:/cache/stage0/e8/wrangler.toml D:/cache/stage0/e10/wrangler.toml
1c1
< name = "stage0-e8"
---
> name = "stage0-e10"
15,16c15,16
< database_name = "stage0-e8-rp"
< database_id = "00000000-0000-0000-0000-0000000000e8"
---
> database_name = "stage0-e10-rp"
> database_id = "00000000-0000-0000-0000-000000000e10"
20,21c20,21
< database_name = "stage0-e8-rp-backup"
< database_id = "00000000-0000-0000-0000-0000000000b8"
---
> database_name = "stage0-e10-rp-backup"
> database_id = "00000000-0000-0000-0000-000000000b10"
25,26c25,26
< database_name = "stage0-e8-outbox"
< database_id = "00000000-0000-0000-0000-000000000008"
---
> database_name = "stage0-e10-outbox"
> database_id = "00000000-0000-0000-0000-000000000010"
```

`wrangler.toml` sha256 `41bf933eb9984f6a6d10bf68602f54222bbabcbe8e8d4a0f26e902ffd1d214a4`.
The port is not in the file: it is passed on the command line (`--port 8986`), and the D1
ids are placeholders — **no Cloudflare database was created and no API call was made**.

### 3.5 Method discipline

- State is wiped before every run (`state/` removed, `.wrangler` removed).
- Every assertion is read from the store **after** the operation.
- The driver exits **0 only when every check passes**; this run: **65 checks, 0 failures**.
- No npm install, no npx, no global package, no PATH or registry change, nothing unzipped,
  nothing elevated. Node is Gradle's portable 22.14.0 invoked by explicit path; wrangler is
  the already-present `D:/cache/stage0/node_modules/wrangler/bin/wrangler.js`.

---

## 4. Footprint [M] — rows and bytes per match, per store

Three fully played matches (`e9-s42`, `e9-s43`, `e9-s44`) through the **real protocol**
(`/prelude`, `/state`, `/step`, `/settle/finalize|enqueue|relay|ack|backup|cleanup`).

### 4.1 Metered rows per match

| match | log steps | D1 rows total | RP | OUTBOX | BACKUP | DO rows written | of which inserts only |
|---|---|---|---|---|---|---|---|
| `e9-s42-full-normal` | 411 | **29** | 10 | 5 | 14 | **824** | 412 |
| `e9-s43-full-normal` | 426 | **27** | 10 | 5 | 12 | **854** | 427 |
| `e9-s44-full-normal` | 432 | **27** | 10 | 5 | 12 | **866** | 433 |

The E8 reconciliation **reproduced exactly**: the first settlement costs 29 D1 rows (the
first backup creates the two watermark rows) and the steady protocol costs 27 (RP 10 +
OUTBOX 5 + BACKUP 12); E9's "≈1 log row per step" reproduced (411 / 426 / 432).

**The DO rows-written meter charges the cleanup.** A match writes `2 × steps + 2` rows into
its Durable Object: `steps` log inserts, the same number again when the cleanup's
`DELETE FROM log` removes them, plus 2 bookkeeping rows. **E12-a's insert-only figure
(428 rows) is therefore not the number the meter sees — the metered figure is 854.**

### 4.2 Bytes per match — and the exact SQL for every number

**Content bytes** (the sum of the stored values, per table):

```sql
-- RP, per match
SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(match_id)),0) AS b FROM settlement WHERE match_id = ?;
SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(match_id) + LENGTH(seat) + LENGTH(uid)),0) AS b FROM ledger WHERE match_id = ?;
-- RP_BACKUP, per match
SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(match_id)),0) AS b FROM settlement_copy WHERE match_id = ?;
SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(match_id) + LENGTH(seat) + LENGTH(uid)),0) AS b FROM ledger_copy WHERE match_id = ?;
-- OUTBOX, per match
SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(match_id) + LENGTH(payload)),0) AS b FROM outbox WHERE match_id = ?;
-- the Durable Object, per match
SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(rec)),0) AS b FROM log;
SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(payload)),0) AS b FROM final;
```

**File bytes** (the page-granular file size), from the D1 binding:

```sql
PRAGMA page_size                                        -- 4096
SELECT * FROM pragma_page_size()                         -- 4096
PRAGMA freelist_count                                   -- refused: SQLITE_AUTH
SELECT * FROM pragma_freelist_count()                    -- refused: SQLITE_AUTH
SELECT COUNT(*) AS n FROM sqlite_master                 -- the size gauge (meta.size_after)
SELECT name, COUNT(*) AS pages, COALESCE(SUM(pgsize),0) AS bytes FROM dbstat GROUP BY name
                                                       -- refused: no such table: dbstat
```

**DO file bytes** are read on disk from a copy of the local file, with `node:sqlite`:

```sql
PRAGMA wal_checkpoint(TRUNCATE);
PRAGMA page_count;
PRAGMA page_size;      -- 4096
PRAGMA freelist_count;
```

**The DO's SQL cursor refuses `page_count`, `freelist_count` and `dbstat` under
`SQLITE_AUTH`** — only `page_size` is authorised. The D1 file bytes were therefore taken
from `node:sqlite` on a copy of the local file, and the per-table byte figures from the
content sums above. This is stated, not hidden: **the DO's per-table page attribution is
[u]**; the totals below are exact.

### 4.3 The measured bytes per match

| store | content bytes per match | file bytes before → after the match | file delta |
|---|---|---|---|
| **DO (SQLite, per object)** | log **19,710** + frozen intent **562** = **20,272** | 28,672 → **53,248** | **+24,576** |
| **DO after the protocol's cleanup** | log **0** (deleted), frozen intent 562 | 53,248 → **53,248** | **0** |
| **RP (D1)** | settlement 18 (1 row) + ledger 88 (4 rows) = **106** | 40,960 → 40,960 | **0** |
| **RP_BACKUP (D1)** | settlement_copy 18 (1 row) + ledger_copy 88 (4 rows) = **106** | 32,768 → 32,768 | **0** |
| **OUTBOX (D1)** | 580–584 at peak (1 row), **0 rows at rest** | 20,480 → 20,480 | **0** |
| **tombstone (D1 RP)** | **76.889** content per row (10,000-row average; a single live row measured 82) — the SQL sums `LENGTH(match_id) + LENGTH(payload_sha256)`, so the three integer columns are **not** counted; **127.7952 physical** per row | see §5.5 | — |

Two facts drive everything that follows:

1. **The D1 files do not grow per match** — they are page-granular and the pages are
   reused. The *content* grows (106 B per match in RP and in RP_BACKUP), the *file* does not.
2. **The DO's file keeps its high-water mark: 53,248 B per match, and deleting the log does
   not return the space** (measured: 53,248 B before the purge and 53,248 B after it). The
   pages become free list inside the object.

### 4.4 What one match therefore costs

| | DO rows written | D1 rows written | D1 rows read | DO bytes | D1 bytes |
|---|---|---|---|---|---|
| one steady match (426 steps) | **854** | **27** | 20 (the settlement's ledger read) | 53,248 (file, high-water) | 212 (106 RP + 106 backup, content) |
| + the purge (§7) | +1 | +4 | +7 | 0 (the file does not shrink) | 0 |

---

## 5. The sweep, the index and the tombstone [M]

### 5.1 The sweep cost at three sizes

A realistic match-log-shaped table (`e10_sweep`: `id`, a 600-byte `rec`, `created_ms`),
swept with **one parameterised statement**:

```sql
DELETE FROM e10_sweep WHERE created_ms < ?;      -- param 1715768000000
```

| rows in the table | without an index: rows read | rows written | ms | with an index: rows read | rows written | ms |
|---|---|---|---|---|---|---|
| 1,000 | 1,000 | 500 | 4 | **501** | 500 | 5 |
| 10,000 | 10,000 | 5,000 | 23 | **5,001** | 5,000 | 40 |
| 100,000 | 100,000 | 50,000 | 373 | **50,001** | 50,000 | 379 |

**Without an index the sweep is a full scan** (it reads the whole table); **with an index it
reads only the rows it deletes plus one** (the index's own descent). The rows written are
identical in both cases — a delete charges one metered row per deleted row, exactly as
E12-c measured (428 rows written, 0 rows read, 1 statement).

### 5.2 The index's own cost — created through the real metered `d1Run` path

```sql
CREATE INDEX e10_sweep_created ON e10_sweep (created_ms);   -- via d1Run("RP", ...)
```

| rows | metered rows written by `CREATE INDEX` | file bytes added | ms |
|---|---|---|---|
| 1,000 | 1,001 | 20,480 (20.5 B/row) | 2 |
| 10,000 | 10,001 | 147,456 (14.7 B/row) | 9 |
| 100,000 | 100,001 | 1,482,752 (14.8 B/row) | 137 |

**Insert cost, 100 inserts, same table:**

| | metered rows per insert | rows written for 100 inserts |
|---|---|---|
| without an index | **1** | 100 |
| with the `created_ms` index | **2** | 200 |

**The E12-a V2 ×2 shape reproduces exactly.** An index does not make the sweep cheaper in
rows written; it makes it cheaper in rows **read** (100,000 → 50,001 at 100k rows) and it
doubles every insert. The plan's ruling — **no second index** — is upheld by this
measurement, and the reason is sharper than before: the index's benefit is on the read axis
only, and the read axis is not the binding one.

### 5.3 The row-by-row alternative (one size only: 10,000 rows)

```sql
SELECT id FROM e10_sweep WHERE created_ms < ?;            -- one bulk SELECT
DELETE FROM e10_sweep WHERE id = ?;                       -- once per row
```

| | statements | rows deleted | rows written | metered rows read | ms |
|---|---|---|---|---|---|
| one `DELETE FROM` | **1** | 5,000 | 5,000 | 10,000 | **23** |
| row by row | **5,000** (`DELETE`s; +1 bulk `SELECT` the counter does not include) | 5,000 | 5,000 | 5,000 | **10,543** |

**What E12-c's 856-read shape is, and what reproduced.** E12-c measured the row-by-row
alternative on the platform as **856 reads for 428 deleted rows = 2 reads per deleted row**,
which is a `SELECT` and a `DELETE` per row. The local run used **one bulk `SELECT`** plus
one `DELETE` per row, so the accumulated counter shows **1 metered read per deleted row
(5,000)**; the bulk `SELECT`'s own read count was not accumulated **[u]**. The statement
shape is the same order of cost (a read and a write per deleted row) but the local figure
is **not literally E12-c's 2-per-row figure** — and the check says so in its own name: *"the
row-by-row shape reads one row per deleted row on the local harness (E12-c's 2-per-row is a
platform SELECT+DELETE shape)"*. Stated plainly rather than papered over.

What the row-by-row shape does reproduce, unambiguously: **458× the wall clock** (10,543 ms
vs 23 ms) and **5,000 `DELETE` statements instead of 1** (plus the one bulk `SELECT`, which the
counter does not include). The conclusion is unchanged — **use one `DELETE FROM`; never loop**.

### 5.4 `setAlarm()` and one alarm fire

| | measured |
|---|---|
| `setAlarm()` — SQL rows written | **0** |
| `setAlarm()` — the one change | the alarm state itself (`sqlChanges: 1`, `alarmArms: 0`) |
| an alarm fire | **observed locally**: `fires` 0 → **1**, the alarm disarmed (`armed: null`) |
| the fire's request count | **[u]** — a local alarm delivery's request count is not observable here |
| the handler's own cost on a match whose log was still full | **431 DO rows written** (430 log rows + the frozen intent) + **5 D1 rows written** (the OUTBOX row, the backup's `settlement_copy`, the RP `settlement`, and the tombstone's **2 metered** rows) + **8 D1 rows read** |
| what the handler deletes | the DO's log rows and the frozen intent, the OUTBOX row, the backup's `settlement_copy` and the RP `settlement` row — **and it leaves a tombstone and the 4 ledger rows** |

E12's "setAlarm() costs 0 rows and the fire costs 1 request" **reproduces** on the rows
axis; the request count of the fire is **[u]** locally and is labelled as such.

### 5.5 The tombstone's physical cost — measured on the real RP file

10,000 tombstones inserted through the metered path into the live RP database, then deleted
again:

| | value |
|---|---|
| metered rows written per tombstone | **2** (the row + its `TEXT PRIMARY KEY` index) |
| content bytes per tombstone | **76.889** (measured over 10,000 rows: 768,890 B) |
| physical bytes per tombstone (file delta ÷ rows) | **127.7952** (1,277,952 B over 10,000 rows) |
| the file after deleting all 10,000 | back to 40,960 B — **the pages become free list; the file does not shrink** |

**At 80 matches/day, one year:** 29,200 tombstones → **3,731,620 B (3.56 MiB) physical**
or **2,245,159 B (2.14 MiB) content** [m, extrapolated from the measured per-row figures].
That is the entire price of the idempotency anchor for a year of matches.

The tombstone's row, as written:

```sql
CREATE TABLE tombstone (match_id TEXT PRIMARY KEY, payload_sha256 TEXT NOT NULL,
  total_delta INTEGER NOT NULL, rows_applied INTEGER NOT NULL,
  finished_ms INTEGER NOT NULL, purge_after_ms INTEGER NOT NULL);
```

The `payload_sha256` is **the same SHA-256 of the frozen intent string** that E8's replay
path compares, so the tombstone is a drop-in for the deleted `settlement` row's key.

---

## 6. The cleanup trigger — four options and one recommendation

### 6.1 What was measured

The purge's **marginal** cost, measured inline on a normally settled match (the protocol's
own cleanup has already deleted the log): **1 DO row written, 4 D1 rows written, 7 D1 rows
read**. The alarm handler's own run on a match whose log was still full: **431 DO rows**,
**5 D1 rows**, **8 D1 reads** — that is the "purge *without* the protocol's cleanup" case.

### 6.2 The four options at 80 matches/day

| | requests/day | % of the free 100,000 DO requests/day | DO rows written/day | D1 rows written/day | D1 rows read/day (the purge's own) | % of the free 5,000,000 D1 rows read/day |
|---|---|---|---|---|---|---|
| **A — an hourly alarm on every match's DO** | **1,920** | 1.92% | 68,400 | 2,480 | 560 | 0.0112% |
| **B — a daily alarm on every match's DO** | **80** | 0.08% | 68,400 | 2,480 | 560 | 0.0112% |
| **C — the purge inline on `/settle/cleanup`** | **0** | 0% | 68,400 | 2,480 | 560 | 0.0112% |
| **D — one cron Worker that pokes every expired DO** | **81** | 0.081% | 68,400 | 2,480 | 560 | 0.0112% |

**Every option costs the same rows — only the requests differ.** The purge is priced by the
match, not by the clock: 80 × (854 + 1) = 68,400 DO rows/day and 80 × (27 + 4) = 2,480 D1
rows/day, whatever wakes the object up.

**The DO's rows read are [u] locally.** Miniflare's Durable Object SQL cursor does not
expose a rows-read counter for DML — it reported 1 row read for a `DELETE` that removed 430
rows. D1's `meta.rows_read` **is** a real counter, and it is what the table above uses.

### 6.3 Two corrections the raw table needs

1. **The request counts assume how often the alarm re-arms.** The table's A (1,920 = 24
   deliveries per object per day) is the price of a *re-arming* hourly alarm; B (80 = one
   delivery per match) is the price of an alarm **armed once**, at the end of the match's
   window. A re-arming **daily** alarm would cost `rate × window` deliveries/day
   (560/day at a 7-day window, 2,400/day at 30 days, 29,200/day at 365 days = 29.2% of the
   allowance), because every live object is woken every day.
2. **Option C is not a retention trigger — it is a zero-window policy.** The inline purge
   deletes the payload at settlement time. It is free in requests, but it keeps **nothing**;
   and if it is used instead as "also sweep the expired ones", the sweep must *find* the
   expired matches, which is a cross-object scan whose cost is **not measured here [u]**.

### 6.4 The recommendation

**B — a per-match Durable Object alarm, armed ONCE, at the end of the match's retention
window (`setAlarm(finished_ms + window)`).**

The arithmetic:

- **80 alarm deliveries/day** at 80 matches/day = **0.08%** of the free 100,000 DO
  requests/day — and it is the same 80 whether the window is 7 days or 365;
- **the rows are identical for every option**: 68,400 DO rows/day and 2,480 D1 rows/day;
- the decision is **local to the object** ("am I past my window?"), so there is no
  cross-object scan and no index of expired matches to maintain;
- the object is woken once, at the right moment, and the payload is gone.

Arming hourly instead (A) buys a ≤1-hour lag for 24× the deliveries and buys nothing else;
the cron (D) costs one extra request and needs a way to know which objects are expired;
the inline purge (C) is only compatible with a zero-day window.

---

## 7. Replay and retention are the same question

### 7.1 The negative control — cleanup with no tombstone, then replay

A completed match is purged **without** a tombstone (the `scope=unsafe` branch, which exists
only for this control and deletes the DO intent, the OUTBOX row, both backup copies and the
RP settlement **and ledger** rows), and the original payload is replayed:

| | observed |
|---|---|
| the replay is **accepted** | `applied: true`, `duplicate: false` |
| metered rows written by the replay | **10** (the settlement's 5-statement batch) |
| the keyed store afterwards | 1 settlement row, 4 ledger rows — **it cannot tell** the match was replayed |
| the keyless control table | 2 settlements, 8 ledger rows, total delta **146** against the expected **73** |
| the keyless ledger's balances after the replay | every seat's row written **twice**, its delta counted twice — p1/u1 2 rows, deltaSum **112** (2 × 56, rp 1052); p2/u2 2 rows, **0** (2 × 0, rp 1012); p3/u3 2 rows, **−16** (2 × −8, rp 1176); p4/u4 2 rows, **50** (2 × 25, rp 1197) — the stored `rp` is unchanged (spread 0 per seat) |
| a tombstone was created | **no** |

**This is the failure E10 had to catch, and it reproduced.** Without an anchor, the same
intent applies twice.

**The brief asked for the resulting balances, and the re-run records them.** The keyless
ledger is read **after** the replay, grouped per seat and per player: the **rows** doubled
and the **deltas** doubled, while the `rp` stored in each row did not move — the same payload
wrote the same balance twice, so the ledger doubled, not the number. (The first run recorded
only the doubled total delta, 146 against 73; the per-seat capture was added on the re-run.)

### 7.2 A simulated Durable Object loss — the D1 settlement row alone catches it

Only the DO's frozen intent is deleted (scope `do-intent`); the D1 rows are untouched, and
the payload is replayed:

| | observed |
|---|---|
| the replay | `applied: false`, `duplicate: true`, **0 rows written** |
| the refusal | `D1_ERROR: UNIQUE constraint failed: settlement.match_id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)` |
| the settlement and its 4 ledger rows | untouched |

**Yes — the D1 settlement row alone catches the DO loss**, with zero rows written. A DO
losing its object is therefore survivable *as long as the D1 settlement row still exists* —
which is exactly why the retention policy has to keep an anchor after it deletes that row.

### 7.3 The safe design — the tombstone

The purge writes the tombstone **first**, then deletes the payload:

```sql
INSERT INTO tombstone (match_id, payload_sha256, total_delta, rows_applied,
                       finished_ms, purge_after_ms) VALUES (?,?,?,?,?,?);
```

measured: **2 metered rows written** (the row + its primary-key index), payload digest
`b23b64c397ee203c3e1c51654f562c02b81924e9876a91ad2adf74c66bc6c760` — **the same SHA-256 of
the frozen intent string E8 compares**.

Then the payload rows are deleted and **the same E8 payload** is replayed:

| | observed |
|---|---|
| the replay | `mode: "tombstone"`, `applied: false`, `duplicate: true`, **rowsWritten: 0** |
| the balances | unchanged (`u1 1052, u2 1012, u3 1176, u4 1197` — measured before the purge and after the purge + replay) |
| the store afterwards | 4 ledger rows kept, the settlement row gone, **1 tombstone row** |

### 7.4 A second, accidental anchor — and why the tombstone is still needed

A fourth match was purged **without** a tombstone but **with** its ledger rows kept (the
guard match). The replay was then refused — but by the ledger's `(match_id, seat)` primary
key, not by the tombstone:

```
D1_ERROR: UNIQUE constraint failed: ledger.match_id, ledger.seat: SQLITE_CONSTRAINT
(extended: SQLITE_CONSTRAINT_PRIMARYKEY)
```

`applied: false`, `duplicate: true`, **0 rows written**, and the balances were untouched
(the whole batch rolled back). The guard match has **no tombstone**, so the refusal provably
came from the ledger.

**Why the tombstone is still the right anchor:** the ledger's constraint is an *accident* of
the current schema — it refuses the replay by throwing, not by returning a clean
"duplicate", and it disappears the moment the ledger is ever compacted. The tombstone
answers the question the product actually asks ("have I seen this intent before?") with a
row that is designed for it.

### 7.5 The delete order across the three stores — and what may never be deleted

1. **The tombstone first** (D1 `RP`) — written *before* any payload row is deleted, so the
   idempotency anchor survives the cleanup. Delete the payload first and the process dies
   before the tombstone lands, and the match can be replayed into a double-apply.
2. **The DO's log and frozen intent** — the payload-heavy part (19,710 B of log + 562 B of
   intent per match), per object, and redundant once the settlement is in D1.
3. **The OUTBOX row** — already drained in the steady protocol (0 rows at rest); deleted if
   present.
4. **RP_BACKUP `settlement_copy`** — a derived copy of the row the tombstone replaces.
5. **RP `settlement`** — the live idempotency row, deleted **last**, because the tombstone
   now carries the key.

**Never deleted: the RP `ledger` and the RP_BACKUP `ledger_copy`.** Their `rp` column **is**
every player's running balance — `balances()` reads it back with
`SELECT uid, rp FROM ledger WHERE match_id != ? ORDER BY rowid` — so pruning them would
zero the balances. **This was found and fixed inside E10 itself:** the first version of the
purge deleted the ledger, and the check "the purge leaves every player's running balance
unchanged" failed until the ledger was taken out of the delete set. The purge's cost after
the fix: **1 DO row + 4 D1 rows written, 7 D1 rows read**; the purge on a match whose log
was still full: 431 DO rows + 5 D1 rows.

---

## 8. The retention and storage model [m]

Built from the measured per-match figures: **27 D1 rows per match**, **854 DO rows written
per match**, **106 content bytes per match in RP and in RP_BACKUP**, **53,248 B of DO file
per match**, **76.889 content bytes and 127.7952 physical bytes per tombstone**. The
horizon is **365 days**. The **ledger never expires** (it is the balance) and the **tombstone
never expires** (one per match, for ever).

### 8.1 Rows per store

| rate | window | RP rows (ledger / settlement / tombstone) | BACKUP rows (ledger / settlement) | DO rows | D1 rows read/day (the settlement's derivation) |
|---|---|---|---|---|---|
| 50 | 7 | 183,200 (73,000 / 350 / 18,250) | 146,700 (73,000 / 350) | 350 | 3,649,800 |
| 50 | 30 | 185,500 (73,000 / 1,500 / 18,250) | 149,000 (73,000 / 1,500) | 1,500 | 3,649,800 |
| 50 | 90 | 191,500 (73,000 / 4,500 / 18,250) | 155,000 (73,000 / 4,500) | 4,500 | 3,649,800 |
| 50 | 365 | 219,000 (73,000 / 18,250 / 18,250) | 182,500 (73,000 / 18,250) | 18,250 | 3,649,800 |
| **80** | **7** | **293,120** (116,800 / 560 / 29,200) | 234,720 (116,800 / 560) | 560 | 9,343,680 |
| **80** | **30** | **296,800** (116,800 / 2,400 / 29,200) | 238,400 (116,800 / 2,400) | 2,400 | 9,343,680 |
| **80** | **90** | **306,400** (116,800 / 7,200 / 29,200) | 248,000 (116,800 / 7,200) | 7,200 | 9,343,680 |
| **80** | **365** | **350,400** (116,800 / 29,200 / 29,200) | 292,000 (116,800 / 29,200) | 29,200 | 9,343,680 |
| 88 | 7 | 322,432 (128,480 / 616 / 32,120) | 258,192 (128,480 / 616) | 616 | 11,305,888 |
| 88 | 30 | 326,480 (128,480 / 2,640 / 32,120) | 262,240 (128,480 / 2,640) | 2,640 | 11,305,888 |
| 88 | 90 | 337,040 (128,480 / 7,920 / 32,120) | 272,800 (128,480 / 7,920) | 7,920 | 11,305,888 |
| 88 | 365 | 385,440 (128,480 / 32,120 / 32,120) | 321,200 (128,480 / 32,120) | 32,120 | 11,305,888 |
| 150 | 7 | 549,600 (219,000 / 1,050 / 54,750) | 440,100 (219,000 / 1,050) | 1,050 | 32,849,400 |
| 150 | 30 | 556,500 (219,000 / 4,500 / 54,750) | 447,000 (219,000 / 4,500) | 4,500 | 32,849,400 |
| 150 | 90 | 574,500 (219,000 / 13,500 / 54,750) | 465,000 (219,000 / 13,500) | 13,500 | 32,849,400 |
| 150 | 365 | 657,000 (219,000 / 54,750 / 54,750) | 547,500 (219,000 / 54,750) | 54,750 | 32,849,400 |

**The two kinds of number in this table.** The **total** in each store's column is the
**metered** row count — the table row **plus its index entry** (×2), the same shape the
tombstone's own measurement showed (2 metered rows per tombstone). The **parenthesised**
figures are **logical** rows: `ledger / settlement / tombstone`. So `549,600` metered RP rows
at 150/day over 7 days is `2 × (219,000 + 1,050 + 54,750)`. The DO column is a logical row
count (one frozen intent per live match), because a Durable Object's rows are not indexed.

**The D1 rows-read column does not change with the window.** That is the whole point of the
"the ledger is never pruned" rule, and it is why the window cannot fix the read meter.

**OUTBOX is 0 rows and 0 bytes in every cell** — the steady protocol drains it — and is omitted
from both tables.

### 8.2 Bytes per store, and the % of each quoted limit

| rate | window | RP bytes | % of the 500 MB D1 database limit | % of the 5 GB D1 account limit | BACKUP bytes | % of the 5 GB account limit | DO bytes **if the object is deleted at the purge** | % of the 5 GB DO account limit | DO bytes **if the object survives** | % of the 5 GB DO account limit | total bytes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 50 | 7 | 3,015,524 | 0.575% | 0.056% | 1,612,300 | 0.030% | 18,636,800 | 0.347% | 971,776,000 | 18.101% | 23,264,624 |
| 50 | 30 | 3,036,224 | 0.579% | 0.057% | 1,633,000 | 0.030% | 79,872,000 | 1.488% | 971,776,000 | 18.101% | 84,541,224 |
| 50 | 90 | 3,090,224 | 0.589% | 0.058% | 1,687,000 | 0.031% | 239,616,000 | 4.463% | 971,776,000 | 18.101% | 244,393,224 |
| 50 | 365 | 3,337,724 | 0.637% | 0.062% | 1,934,500 | 0.036% | 971,776,000 | 18.101% | 971,776,000 | 18.101% | 977,048,224 |
| **80** | **7** | **4,824,839** | 0.920% | 0.090% | 2,579,680 | 0.048% | **29,818,880** | 0.555% | **1,554,841,600** | **28.961%** | 37,223,399 |
| **80** | **30** | **4,857,959** | 0.927% | 0.090% | 2,612,800 | 0.049% | **127,795,200** | 2.380% | **1,554,841,600** | **28.961%** | 135,265,959 |
| **80** | **90** | **4,944,359** | 0.943% | 0.092% | 2,699,200 | 0.050% | **383,385,600** | 7.141% | **1,554,841,600** | **28.961%** | 391,029,159 |
| **80** | **365** | **5,340,359** | 1.019% | 0.099% | 3,095,200 | 0.058% | **1,554,841,600** | 28.961% | **1,554,841,600** | 28.961% | 1,563,277,159 |
| 88 | 7 | 5,307,323 | 1.012% | 0.099% | 2,837,648 | 0.053% | 32,800,768 | 0.611% | 1,710,325,760 | 31.857% | 40,945,739 |
| 88 | 30 | 5,343,755 | 1.019% | 0.100% | 2,874,080 | 0.054% | 140,574,720 | 2.618% | 1,710,325,760 | 31.857% | 148,792,555 |
| 88 | 90 | 5,438,795 | 1.037% | 0.101% | 2,969,120 | 0.055% | 421,724,160 | 7.855% | 1,710,325,760 | 31.857% | 430,132,075 |
| 88 | 365 | 5,874,395 | 1.120% | 0.109% | 3,404,720 | 0.063% | 1,710,325,760 | 31.857% | 1,710,325,760 | 31.857% | 1,719,604,875 |
| 150 | 7 | 9,046,573 | 1.725% | 0.169% | 4,836,900 | 0.090% | 55,910,400 | 1.041% | 2,915,328,000 | 54.302% | 69,793,873 |
| 150 | 30 | 9,108,673 | 1.737% | 0.170% | 4,899,000 | 0.091% | 239,616,000 | 4.463% | 2,915,328,000 | 54.302% | 253,623,673 |
| 150 | 90 | 9,270,673 | 1.768% | 0.173% | 5,061,000 | 0.094% | 718,848,000 | 13.390% | 2,915,328,000 | 54.302% | 733,179,673 |
| 150 | 365 | 10,013,173 | 1.910% | 0.187% | 5,803,500 | 0.108% | 2,915,328,000 | 54.302% | 2,915,328,000 | 54.302% | 2,931,144,673 |

**The two bases differ deliberately.** The RP and BACKUP bytes are *content* bytes (the sum of
the stored values; the tombstone at 76.889 B/row); the DO columns are *file* bytes (53,248 B per
match). D1's file does not grow per match; the DO's high-water file does.

**Read the two DO columns together.** The purge does **not** shrink the DO's file (measured:
53,248 B before and after), so a retired match's object still holds its high-water file
**unless the Durable Object itself is deleted**. Deleting the object is a platform
capability that was **not** measured here **[x]**. The "if the object survives" column is
therefore the honest one for a design that only deletes rows, and it is **window-independent**
— 1.55 GB at 80/day, 2.92 GB at 150/day (54.3% of the free account allowance).

**The model's byte arithmetic is re-derivable from the measured per-row figures**, and the
run now asserts it: a ledger of N rows is N × 22 B, not N × 22 B per *match*. (An earlier
draft of the model multiplied the ledger's rows by the per-row figure once instead of four
times; the check "the model's ledger bytes are re-derivable from the measured bytes per
row" now catches exactly that class of slip, and it is one of the 65.)

### 8.3 What the model says about the rate

| rate | DO rows written/day | % of the free 100,000/day | D1 rows written/day | % of the free 100,000/day | D1 rows read/day at 365 days | % of the free 5,000,000/day |
|---|---|---|---|---|---|---|
| 50/day | 42,700 | 42.7% | 1,350 | 1.35% | 3,649,800 | 73.0% |
| **80/day** | **68,320** | **68.32%** | 2,160 | 2.16% | 9,343,680 | 186.9% |
| 88/day | 75,152 | 75.2% | 2,376 | 2.38% | 11,305,888 | 226.1% |
| 150/day | 128,100 | **128.1% — over** | 4,050 | 4.05% | 32,849,400 | **657% — over** |

**150 matches/day does not fit the free tier**: the DO rows-written meter is over by 28.1%
on its own. The plan's 80/day target with an 88/day refusal ceiling is consistent with the
measurement; 150/day was never viable on this meter.

---

## 9. The free-tier budget at 80 matches/day

Every allowance below is quoted from the archived page it came from (accessed
**2026-10-31**, §15.2); none is quoted from memory.

| meter | the quoted allowance | E10's figure at 80/day | % of the allowance |
|---|---|---|---|
| **DO rows written** | "Rows written 1,2,3,4 100,000 / day" | 68,320 (steady) / 68,400 (with the purge) | **68.32% / 68.4%** |
| **DO rows read** | "Rows reads 1,2 5 million / day" | **[u]** — not observable locally | — |
| **DO requests** | "Requests 100,000 / day" | 560 (7 per match), with the recommended alarm: **640 requests/day** | 0.56% / **0.64%** |
| **DO storage (account)** | "Storage per account Unlimited (Workers Paid) / 5GB (Free)" | 1,554,841,600 B after a year (if the objects survive) | **28.96%** |
| **DO storage (per object)** | "Storage per Durable Object 10 GB" | 53,248 B per match's object | 0.0005% per object |
| **D1 rows written** | "Rows written 100,000 / day" | 2,160 (steady) / 2,480 (with the purge) | 2.16% / 2.48% |
| **D1 rows read** | "Rows read 5 million / day" | 767,680 at day 30 → **9,343,680 at day 365** | 15.36% → **186.88%** |
| **D1 storage** | "Storage (per GB stored) 5 GB (total)" | 8,435,559 B after a year | 0.157% |
| **D1 database size** | "Maximum database size 10 GB (Workers Paid) / 500 MB (Free)" | 5,340,359 B (RP) | 1.019% |
| **Workers requests** | "Accounts on the Workers Free plan have a daily request limit of 100,000 requests, resetting at midnight UTC." | inside the DO request budget | — |

### 9.1 The day the D1 read meter is crossed

The settlement's derivation reads the **whole** ledger, every time:
`SELECT uid, rp FROM ledger WHERE match_id != ? ORDER BY rowid` — measured at **20 rows**
read on a 24-row ledger (i.e. the whole ledger minus the match's own 4 rows).

At 80 matches/day, on day *d* the ledger holds 4 × 80 × *d* rows, and the day reads
80 × (4 × 80 × *d*) rows. Setting that against the free 5,000,000 rows/day:

```
5,000,000 / (80 × 4 × 80) = 195.3 days
```

**The allowance is crossed after ≈195 days**, and because the ledger is never pruned, **no
retention window delays it**. At 50/day it is crossed after day 625; at 88/day after day
177; at 150/day it is over from day 1.

**This is not a cleanup problem and E10 cannot fix it.** The fix is a separate, approved
change to the balance derivation (a compacted balance table, or a `balances()` that reads one
row per player instead of the whole ledger) — and because `balances()` is one of the eleven
hash-pinned settlement functions (§3.2), that change must be made deliberately, not as a
side effect of a retention patch. **It is raised here as an open item for the owner (§12).**

---

## 10. The negative controls — what actually failed

Three controls were run. **All three failed as required**, which is the point.

### NC1 — cleanup with no tombstone, then replay → the double-apply

| | observed |
|---|---|
| expected | the replay is **ACCEPTED** — the double-apply |
| the replay | `applied: true`, `duplicate: false`, **10 metered rows written** |
| the keyed store | 1 settlement / 4 ledger rows — it cannot tell |
| the keyless control table | 2 settlements / 8 ledger rows, total delta **146** vs the expected **73** |
| a tombstone | **none** |

### NC2 — a sweep with the wrong predicate (`created_ms > ?`) → the wrong set

| | observed |
|---|---|
| expected | the wrong **set** is deleted; the row count alone cannot tell |
| deleted | **4,999** rows, against **5,000** for the correct predicate at the same size |
| rows read | 10,000 (the same as the correct sweep — the meter cannot see the difference either) |
| **caught by** | the `created_ms` edges: before {min 1700000000000, max 1731532846400}, after {min 1700000000000, **max 1715768000000**} — the surviving rows are the **older** ones and the maximum collapses |
| `failedAsRequired` | **true** |

**The count alone is indistinguishable** (4,999 vs 5,000). Only the edge check catches it. A
retention sweep that asserts "N rows were deleted" would have passed.

### NC3 — a function on the column (`created_ms + 0 < ?`) → the index is defeated

| | observed |
|---|---|
| expected | the index is not used and the rows read rise |
| rows read | **10,000**, against **501** for the same sweep with the index |
| `failedAsRequired` | **true** |

The rows written are identical (5,000) — **only the read meter shows the regression**.

---

## 11. The costed options — the window is the owner's decision

The window decides how long a finished match's **payload** is kept. It does **not** decide
the balance (never pruned), the tombstone (one row per match, for ever) or the DO file
(53,248 B per match, not returned by the purge).

At **80 matches/day**, the year-horizon cost of each window:

| window | what is kept | RP bytes | BACKUP bytes | DO bytes (if the object is deleted) | DO bytes (if the object survives) | total bytes | what you lose |
|---|---|---|---|---|---|---|---|
| **7 days** | one week of match payloads | 4,824,839 | 2,579,680 | 29,818,880 | 1,554,841,600 | 37,223,399 | every score, hand, replay and audit trail older than a week — a dispute from March cannot be examined in June |
| **30 days** | one month | 4,857,959 | 2,612,800 | 127,795,200 | 1,554,841,600 | 135,265,959 | anything older than a month — a monthly challenge, a mid-season review |
| **90 days** | one quarter | 4,944,359 | 2,699,200 | 383,385,600 | 1,554,841,600 | 391,029,159 | only the oldest quarter — a whole season's detail |
| **365 days** | a year | 5,340,359 | 3,095,200 | 1,554,841,600 | 1,554,841,600 | 1,563,277,159 | nothing — and it costs 4.2× the 30-day figure for a payload nobody reads after a season |

Note that the D1 cost barely moves with the window (4.82 MB → 5.34 MB) because the ledger and
the tombstones dominate; **the DO's high-water file is where the window's money is**, and only
if the objects are actually deleted.

**Recommendation: 30 days.** One month covers a monthly challenge, a season's disputes and
the "my friend says he won" conversation, costs **127.8 MB** of DO storage if the objects are
deleted (2.38% of the allowance), and the extra 60 days of 90-day window would cost
255.6 MB more to answer questions that a month-old answer already answers.

**If the objects are never deleted, the window costs nothing in storage** (the DO's 1.55 GB is
the same at every window) — in which case pick the window that answers the most
disputes, and the storage argument does not constrain the choice.

---

## 12. What E10 settles, and what it does not

### Settled (on local evidence)

1. **The cleanup's cost** — one `DELETE FROM` per match; the row-by-row alternative is
   458× slower and needs 5,000 `DELETE` statements (plus the one bulk `SELECT`); the sweep's
   read cost scales with the table unless a `created_ms` index exists.
2. **The index's cost** — a second index doubles every insert (E12-a's V2 ×2 shape
   reproduces) and adds ~15 B per row; it only buys reads. The plan's "no second index"
   ruling stands.
3. **The per-match footprint** — 27 D1 rows, 854 DO rows written, 106 content bytes per
   store per match, 53,248 B of DO file per match.
4. **That the DO's rows-written meter, not storage, is the steady-state constraint** at
   80/day (68.32%).
5. **That the ledger must never be pruned** — its `rp` column is the balance — and therefore
   that the D1 rows-read meter is crossed after ≈195 days whatever the window is.
6. **The tombstone design** — one compact row per match (76.889 content bytes measured over
   10,000 rows; 127.7952 physical), the same digest E8 compares, written **before** any payload
   row is deleted, and the delete order across the three stores.
7. **That a replay after the purge is refused** with 0 rows written, and **that without the
   anchor the same intent applies twice** (reproduced, not argued).

### Not settled

- **[x] Nothing on the platform.** D1's real metering, the real rows-read counter, the
  DO's real rows-read counter, the real storage accounting (does Cloudflare's DO storage
  shrink after a `DELETE`?), the Free-plan enforcement, and whether a Durable Object can be
  deleted to reclaim its 53,248 B — **none of it was measured**. No account, no token, no
  deploy.
- **[u] The DO's rows read.** Miniflare's DO SQL cursor does not expose a rows-read counter
  for DML. Every DO rows-read cell in this report is marked [u].
- **[u] The alarm delivery's request count** on the platform.
- **[u] The row-by-row sweep's SELECT-side read count** in the local run (§5.3) — the
  accumulated counter omits the bulk `SELECT`'s own reads.
- **[u] The cross-object scan** an inline "purge everything expired" sweep would need
  (§6.3).
- **[u] The purge's own eligibility check.** The harness's purge path is unconditional: the
  window is **recorded** (`purge_after_ms`) and **previewed** (`eligible: false` before the
  window), but the apply route does not yet refuse to purge early. Production must gate on
  it — the trigger in §6.4 is what does.
- **Open for the owner (not E10's to decide):** the D1 rows-read crossing at ≈195 days
  (§9.1) needs a balance-derivation change, and that change touches a hash-pinned function.

---

## 13. Owner summary (Egyptian Arabic)

**E10 خلص محليًا. الإجابة المختصرة: أيوه — الحذف والاحتفاظ بيدخلوا في الخط المجاني. بس
الرقم اللي هيوجع مخك مش التخزين… ده عدد الصفوف اللي الـDO بيكتبها.**

- **السؤال:** «Do retention and cleanup fit request and rows and storage limits؟» — الإجابة
  **أيوه**، وده بالقياس مش بالتخمين: **65 فحص، صفر فشل**، على 3 ماتشات كاملة لعبناها
  بالبروتوكول الحقيقي (`/prelude` → `/settle/backup` → `/settle/cleanup`).
- **الحذف نفسه رخيص جدًا.** سطر `DELETE FROM` واحد بيمسح 5,000 صف ويكتب 5,000 صف في
  **23 مللي**. لو مشينا صف‑صف: **5,000 جملة حذف** (وجملة اختيار واحدة) و**10.5 ثانية** — يعني
  **458 أضعاف**. فالقاعدة بسيطة: **سطر واحد دايمًا**، زي ما E12-c قال بالحرف.
- **اللي بيكلّف فعلًا هو الـlog، مش الحذف.** كل ماتش بيكتب 426 صف في الـDO (سطر لكل خطوة
  من الماتش)، وبعدين **المسح نفسه بيتحسب صف مكتوب تاني**، فالمجموع **854 صف في الـDO لكل
  ماتش**. عند 80 ماتش/يوم = **68,320 صف/يوم = 68.3%** من الـ100,000 المسموح في الخطة
  المجانية. **ده أعلى رقم في اللعبة كله.** (وE12-a هو اللي قال 428 — هو قاس الكتابة بس،
  نسي المسح.)
- **الـD1 أرخص بكتير:** **27 صف لكل ماتش** (10 سداد + 5 outbox + 12 backup)، يعني
  **2,160 صف/يوم = 2.16%** بس. (أول تسوية بتكون 29، عشان الـbackup الأول بيعمل صفّين
  زيادة.)
- **التخزين مش هو المشكلة.** بعد سنة كاملة بـ80 ماتش/يوم: الـDO بياخد **1.55 جيجا**
  (29% من الـ5 جيجا المجانية) والـD1 **8.4 ميجا** (0.16%). وكل ماتش بياخد **53 كيلوبايت**
  من غير ما يرجع — لأن المسح بيمسح **الصفوف** بس مش **المساحة** من الملف.
- **الفيتير — دي بصمة إيد على عقد.** بدل ما نخزّن نسخة العقد كلها عشان نعرف «اتعمل قبل
  كده ولا لأ»، بنحتفظ بسطر واحد فيه البصمة. سطر بحوالي **77 بايت** محتوى، بس على القرص
  بيبقى **127.8 بايت** لكل ماتش = **3.7 ميجا في السنة** لو 80 ماتش/يوم. وده بالظبط اللي
  بيمنع التسوية مرتين.

**الحاجات اللي اتمنعت فعلًا (3 تجارب سلبية، 3 فشلوا زي المفروض — وده النجاح مش الفشل):**

1. نمسح ونعيد التسوية **من غير فيتير** → **نفس النقاط اتطبقت مرتين** (146 بدل 73). ده اللي
   كنا عايزينه بالظبط.
2. مسح بالشرط الغلط (`created_ms >` بدل `<`) → **4,999 صف** بدل 5,000، يعني **العدد لوحده
   مش بيكشف الغلط**؛ اللي كشفه هو مقارنة أول وآخر تاريخ.
3. دالة على العمود (`created_ms + 0 < ?`) → الـindex اتبطّل و**10,000 صف اتقروا** بدل 501.

**ليه الـledger (دفتر الرصيد) ما ينمسحش أبدًا:** عمود `rp` فيه **رصيد اللاعب نفسه**، مش
مجرد تاريخ. لو مسحناه، رصيد اللاعب بيبقى صفر. فبقي **الـledger ما ينمسحش أبدًا** (وكمان
نسخته في الـbackup). وده كان أهم إصلاح عملناه في التقرير: أول نسخة من كود الحذف كانت
بتمسح الـledger، وفحص «الرصيد ما يتغيّرش» فشل لحد ما صلّحناها. ولو اتمسح الـledger بس،
الـD1 بيرفض إعادة التسوية لوحده بصفر كتابة — بس بيطلع كـ**خطأ** مش كـ«مكرر»، فـ**الفيتير
هو المرجع النضيف الوحيد** والـledger حمايتنا الثانية.

**موعد الحذف (الـalarm) تكلفته صفر صفوف:** `setAlarm()` نفسها **0 صفوف**؛ ولما المنبه اشتغل
فعلًا: **431 صف DO** (430 صف log + الـintent المخزّن) و**5 صفوف D1** و**8 صفوف قراءة D1**. وأربع طرق
للحذف كلهم بيكتبوا **نفس الصفوف** (68,400 صف DO/يوم و2,480 صف D1/يوم)؛ الفرق الوحيد في
**عدد الطلبات**: منبه ساعي 1,920 طلب/يوم، منبه يومي **80 طلب/يوم (0.08%)**، حذف فوري 0،
وcron واحد 81. **اختيارنا الهندسي: منبه واحد بيتسلّح مرة واحدة آخر مدة الماتش** — عشان
الكائن يصحى مرة واحدة في الوقت الصح.

---

## 13.1 القرار المطلوب منك: قد إيه نخزّن تفاصيل الماتش؟

**الاختيار ده سهل وبسيط: 7 أو 30 أو 90 يوم.**

| المدة | اللي بيتخزّن | اللي بيتخسر |
|---|---|---|
| **7 أيام** | أسبوع تفاصيل ماتشات | أي خلاف قديم — مثلاً زعل من مارس مش هينفع تراجعه في يونيو |
| **30 يوم** | شهر | أي حاجة أقدم من شهر — تحدي الشهر، أو مراجعة نص الموسم |
| **90 يوم** | ربع سنة | تفاصيل الموسم كامل — آخر حاجة تضيع |

**اللي بتخسره مش الرصيد أبدًا.** الرصيد (الـledger) والفيتير بيفضلوا موجودين **للأبد**.
اللي بيتمسح هو **تفاصيل الماتش بس**: النتيجة، توزيعات الأيدي، الـreplay، وسجلات التدقيق.
يعني لو حد جالك بعد شهرين saying «أنا كسبت»، تقدر تقوله «رصيدك صح»، بس مش هتعرف «إزاي
اتلعبت الماتش».

**توصيتنا: 30 يوم.** شهر بيكفي لتحدي الشهر ومراجعة المواسم، وكل جرعة زيادة بتكلّف فلوس على
حاجة محدش بيقراها بعد الموسم. ولو كائنات الـDO ما اتمسحتش (وده **[x]**، مش معروف لسه)، فالمدة
**مش بتفرق في التخزين خالص** — فاختار المدة اللي بتجاوب على أكثر خلافات.

---

**الحاجة الوحيدة اللي محتاجة قرار تاني (مش في E10):** عدّاد القراءة بتاع D1 بيتعدّى
5,000,000 صف/يوم بعد **≈195 يوم** من التشغيل بـ80 ماتش/يوم — وده لأن حساب الرصيد بيقرأ
الدفتر كله في كل تسوية. **مفيش مدة احتفاظ بتأخّر ده.** الحل تغيير في طريقة حساب الرصيد
(جدول رصيد مضغوط)، وده بيلمس دالة `balances()` — واحدة من الـ11 دالة متقفّل عليها بالـhash —
فلازم يتعمل **قرار متعمّد**، مش أثر جانبي لرقعة احتفاظ.

**الحاجات اللي فضلت [u] (ما اتقستش محليًا):** عدّاد قراءة الـDO، عدد طلبات المنبه على
المنصة، وعدّاد D1 الحقيقي.

**مفيش أي حاجة اتعملت على Cloudflare.** ولا حساب، ولا توكن، ولا نشر، ولا API call. كله على
جهازك.

---

## 14. Reproduction

Every command, in order, on a Windows shell with Git Bash:

```bash
# 1. The harness (outside the repository). Nothing is installed; nothing is
#    downloaded except the six Cloudflare documentation pages, which are
#    archived locally.
cd /d/cache/stage0/e10

# 2. Run the driver. It boots its own local Miniflare server, wipes the state
#    directory, plays the matches, runs the controls, models the retention, and
#    exits 0 only if every check passes.
"C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" \
  --experimental-sqlite driver.mjs

# 3. The run log and the boot log
logs/driver-run-165947.log
logs/boot-2026-10-31T14-59-47-565Z.log

# 4. The results
results/e10-results-2026-10-31T14-59-47-565Z.json   # the run's own file
results/e10-results-latest.json                      # the same bytes
results/e10-final.json                               # the same bytes

# 5. The harness fidelity proofs
diff --unified=0 /d/cache/stage0/e8/worker.mjs worker.mjs   # 0 removed lines
"C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" hash-functions-e10.mjs
sha256sum k/stage0.js worker.mjs driver.mjs wrangler.toml
```

The driver spawns the server itself:

```
node D:/cache/stage0/node_modules/wrangler/bin/wrangler.js dev --local \
  --port 8986 --ip 127.0.0.1 --persist-to D:/cache/stage0/e10/state
  (env: WRANGLER_SEND_METRICS=false, XDG_CONFIG_HOME=D:/cache/stage0/config)
```

**No `wrangler --remote`, no login, no account, no token, no deploy.**

---

## 15. Artifacts

### 15.1 The harness and the run (outside the repository, `D:\cache\stage0\e10\`)

| artefact | sha256 |
|---|---|
| `worker.mjs` (E8's worker + the E10 additions) | `987dbf88c13f4f14311f49d57199c8478679e95590132c6eb76c9a9c7edb8102` |
| `driver.mjs` (the E10 driver) | `57151d89618189086829da638c8a4115013b32ad87cbdea44dbffe286ef61289` |
| `wrangler.toml` | `41bf933eb9984f6a6d10bf68602f54222bbabcbe8e8d4a0f26e902ffd1d214a4` |
| `hash-functions-e10.mjs` (the 11-function hash check) | `375c343d7d8773f75b93d5c40b62a9d177e10f4607fd2bbca1439219b37d9273` |
| `results/function-hashes.json` (its output: **11/11 identical, 0 changed**) | `f22279586388eb2269e59b0bbe360de0b086eb5d5de242ac0036a8109c0eef37` |
| `extract.mjs` (the read-only number extractor) | `0743822c41ed1676a6d4c58ba509b6d2640f96c7a772d7df2621afb2716f4854` |
| `results/e10-results-2026-10-31T14-59-47-565Z.json` | `f06768514383d19047c94195c9ba612f6de862bf5b620a3df8f41fc8e513d29e` |
| `results/e10-results-latest.json` | `f06768514383d19047c94195c9ba612f6de862bf5b620a3df8f41fc8e513d29e` |
| `results/e10-final.json` | `f06768514383d19047c94195c9ba612f6de862bf5b620a3df8f41fc8e513d29e` |
| `logs/driver-run-165947.log` | `ed1de1bb29c57428f24c885211144bd94713b905e62fcf61c149bbf2aa029627` |
| `logs/boot-2026-10-31T14-59-47-565Z.log` | `0eac191ab8721f480152aff926daab57071317c0418c170430584289d50071c3` |
| `k/stage0.js` (the engine bundle) | `46153a843fa81132a8d22c17acc84403724c3e9c68ad4768f0746c6cf89ca372` |

### 15.2 The archived documentation pages (accessed 2026-10-31T14:59:47.569Z)

Each page was fetched at run time and archived as **HTML + text**, under
`D:\cache\stage0\e10\results\docs\`.

| page | url | status | sha256 (HTML) | sha256 (text) |
|---|---|---|---|---|
| D1 limits | https://developers.cloudflare.com/d1/platform/limits/ | 200 | `5c4b2d9316ee1a2eb5bc83b9b1d668ecbbcc27856ecc49358abe12c0898f52fb` | `ed8d98773b8eb8fa2f57644296ca550affd7812ab5b5ed071b66bc03439bf8e3` |
| D1 pricing | https://developers.cloudflare.com/d1/platform/pricing/ | 200 | `014d2107d1b8c476b88d034c63bbaf2b3fcfc3fa96db9c45078735602600b676` | `078ff6462478f29ae4a9f75760d73a80eafbbd7205058ff3b20841c25731db54` |
| DO limits | https://developers.cloudflare.com/durable-objects/platform/limits/ | 200 | `46a4a5b5296c2c8c6970663407d4bde0f730bb828ebd2d2319f2ae45ef7d424b` | `fd8ab6709156807e2f96987e9d09a2bd0aa98c4c97720b9f31a5eb6fc50f363f` |
| DO pricing | https://developers.cloudflare.com/durable-objects/platform/pricing/ | 200 | `337e993eed2aa427407c76c051c96557b226f42ded4cf0503569d73a5365ad0d` | `c2d57f167ccd4e6f149e134ee7fcef4b9874f2bffe13651f1f725bd2a32301d6` |
| Workers limits | https://developers.cloudflare.com/workers/platform/limits/ | 200 | `28eb4e8a29f0c8c685d2a676da9e84a8a7be17ad49bc5fe263ad03f1be75f00e` | `3051ea518d24ba3e6259389fb6b1912de1f09090a26fbf72c137e92f255374d1` |
| Workers pricing | https://developers.cloudflare.com/workers/platform/pricing/ | 200 | `9c147a7a70f799b38c43653c2ac78de5b5c11c27c4047f2519d4a5753f6d2d05` | `e238fbe42e2cfec30606a38a38e15ecc874bb7af7f8cc567b6c7d80dc2c0b5a7` |

The archives for this run are the `*-2026-10-31T14-59-47-565Z.*` files; earlier runs left
their own copies in the same directory.

### 15.3 The quoted limits, byte-exactly, with their access date

All accessed **2026-10-31** from the pages above.

**D1 limits** — https://developers.cloudflare.com/d1/platform/limits/

- "Maximum database size 10 GB (Workers Paid) / 500 MB (Free)"
- "Maximum storage per account 1 TB (Workers Paid) 1 / 5 GB (Free)"
- "Maximum number of columns per table 100"
- "Maximum string, BLOB or table row size 2,000,000 bytes (2 MB)"
- "Maximum SQL statement length 100,000 bytes (100 KB)"
- "Maximum bound parameters per query 100"
- "Queries per Worker invocation (read subrequest limits ) 1000 (Workers Paid) / 50 (Free)"

**D1 pricing** — https://developers.cloudflare.com/d1/platform/pricing/

- "Rows read 5 million / day"
- "Rows written 100,000 / day"
- "Storage (per GB stored) 5 GB (total)"
- "Databases 10"

**DO limits** — https://developers.cloudflare.com/durable-objects/platform/limits/

- "Storage per Durable Object 10 GB"
- "Storage per account Unlimited (Workers Paid) / 5GB (Free)"
- "Maximum Durable Object classes (per account) 500 (Workers Paid) / 100 (Free)"
- "Maximum string, BLOB or table row size 2 MB"

**DO pricing** — https://developers.cloudflare.com/durable-objects/platform/pricing/

- "Rows reads 1,2 5 million / day"
- "Rows written 1,2,3,4 100,000 / day"
- "Requests 100,000 / day"
- "Duration 3 13,000 GB-s / day"

**Workers limits** — https://developers.cloudflare.com/workers/platform/limits/

- "Accounts on the Workers Free plan have a daily request limit of 100,000 requests, resetting at midnight UTC."

### 15.4 What is in the repository

Only this report and the pointer notes dated 2026-10-31. **No harness file, no results JSON
and no log is committed.** The E8 harness and the E8 results were not modified.

---

*Nothing in this report was deployed, and nothing was measured on a Cloudflare account.*
