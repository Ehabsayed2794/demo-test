# Stage 0 — E8: D1 atomicity, triggers and query limits

- **Date:** 2026-10-31
- **Branch:** `stage0/e8-d1-atomicity`, based on E9's tip `fbcd8022f659f53e2fe44a740a5fe05ad83ad81d`.
- **Status:** **LOCAL LEGS MEASURED AND PASSED [M].** **The platform legs are [x] — not run.** They are
  written as a five-step, separately approved checklist in §13; nothing in this report touched
  Cloudflare, created an account, or read a token.

> **POINTER NOTE (2026-11-01 — platform steps 0–3 have now RUN; the Status bullet above is left
> verbatim) [E8 platform wave — note 1 of 3].** The bullet's clause *"nothing in this report touched
> Cloudflare, created an account, or read a token"* is **superseded**: the §13 platform checklist is
> no longer unwritten. **Step 0** (account B confirmed) and **step 1** (the three D1 databases
> created) ran on **2026-10-31**; **step 2** (the deploy to account B) ran on **2026-11-01** and was
> **verified byte-identical** to the local harness; **step 3** (the real-D1 measurement) ran on
> **2026-11-01** against that same deployed module with **no redeploy** — 53/53 checks, 0 failures.
> See §13's dated notes below. **Step 4 (cleanup) remains open.**
> **The platform leg IS now measured**, and it **confirms** §2: the batch is a transaction on real
> D1, the 27-row protocol reproduces, and the two documented caps the local run did not enforce
> (the 2 MB row and the 50-query Free cap) **are not enforced on the platform either** — a
> documentation-versus-runtime gap, identical on both runtimes, recorded in §13's note 3 and in the
> §14 pointer. **E8 is closed on its criterion, and P3 stays gated on E9's eviction leg alone.**

- **Harness:** `D:/cache/stage0/e8/` — outside the repository, nothing from it is committed. It is
  E9's harness with **only additions** (§3).
- **Canonical run:** `2026-10-31T11-29-13-395Z`, **53/53 checks, 0 failures** on
  `wrangler dev --local` (wrangler 4.155.0, Miniflare SQLite), port 8985, state wiped before boot.

> **One-sentence answer.** On the local runtime, `db.batch()` is the transaction the settlement
> needs — one failing statement rolls back all five and leaves **no partial row** — and the
> documented 100-parameter and 100,000-byte limits are enforced exactly as written; the **2 MB
> row cap and the Free plan's 50-query cap are not enforced locally**, so those two are precisely
> what the platform leg in §13 must decide.

---

## 1. The question and the pass criterion, verbatim

From `docs/implementation/Stage0_Remaining_Steps.md` §2:

> **Question.** Does D1 give the settlement the atomicity E7's protocol assumes — one batch that
> applies RP + OUTBOX + backup or none of it — inside the documented limits?
>
> **Pass:** the settlement is atomic within documented limits; a failed batch leaves no partial row.

The plan's E8 row (`docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3) and T5 row (§5.7.3) carry the
same test: "Settlement can be atomic within documented limits", measured by "batch atomicity tests,
trigger behaviour, query-limit tests".

**Effort:** 6–12 h. **Blocks:** P3.

---

## 2. Verdict

**PASS on the local runtime, in the local half of the criterion only.**

1. **The settlement batch is a transaction.** The real `relayOnce` batch is **5 statements**
   (1 `settlement` row + 4 `ledger` rows). A failure at **every** position — the first statement
   (duplicate primary key) and the second through the fifth (a `BEFORE INSERT` trigger abort on
   ledger row k) — throws, and the store afterwards holds **0 settlement rows and 0 ledger rows**
   for that match (k = 1…4), or the **pre-existing 5 rows unchanged** (k = 0). No partial row was
   ever committed.
2. **The correct batch is exactly 5 table rows / 10 metered rows**, applied once, and a replay is
   a **duplicate with 0 writes**.
3. **The documented limits the settlement path touches** are enforced locally for **100 bound
   parameters** and **100,000-byte SQL**, and are **not** enforced locally for the **2 MB row/string
   size** and the **50-query Free cap** (§8).
4. **The protocol's 27-row steady-state shape reproduces**: RP 10 + OUTBOX 5 + RP_BACKUP 12 = **27**
   on a second settlement; the first settlement costs **29** because the first backup run is 14
   (it creates the two watermark rows).
5. **Cross-database atomicity does not exist**: a batch bound to `env.RP` that contains a statement
   prepared from `env.OUTBOX` is executed **against RP's database**; read-only statements succeed,
   writes fail `no such table`, and no row changes. The three-database layout therefore cannot put
   "RP + OUTBOX + backup" in one atomic batch — the protocol's protection is the frozen intent plus
   idempotent replay, not a cross-store transaction (§6). **D-E7-3 stays open.**

> **POINTER NOTE (2026-11-01 — the verdict has now been measured on real D1; the list above is left
> verbatim) [E8 platform wave — note 1 of 3].** Step 3 ran the same 53 assertions against the
> **deployed** module on account B: **53/53 passed**, and every number in items 1, 2, 4 and 5
> reproduced exactly (5 statements / 10 metered rows; `UNIQUE constraint failed:
> settlement.match_id` at k = 0; `e8-sentinel-abort` at k = 1…4 with **0 settlement / 0 ledger**;
> **27** and **29** rows; `no such table` on the cross-binding write). Item 3's *"enforced
> locally"* now reads **"enforced on both runtimes"**: the 100-parameter and 100 KB caps are
> enforced on the platform too, and the **2 MB row and the 50-query Free cap are enforced on
> neither** — the platform is not stricter than the local simulator, it is the same engine. **The
> verdict therefore holds on the platform: PASS on both runtime halves of the criterion.** Step 4
> (cleanup) is the only step left; see §13's note 3.

---

## 3. The harness and its fidelity to E9

**What was copied unchanged.** `D:/cache/stage0/e9s4/{worker.mjs, wrangler.toml, k/}` was copied to
`D:/cache/stage0/e8/`; only `/e8/*` routes and E8-only methods were appended.

| Check | Evidence |
|---|---|
| No E9 line removed or changed | `diff -u e9s4/worker.mjs e8/worker.mjs`: **0 removed lines**, **299 insertions**, two hunks (`@@ -783,6 +783,201 @@`, `@@ -1140,6 +1335,110 @@`). |
| E9 file | `sha256 39a4bb2ae29f0c7d7bcafc3344b0a34e05992430758ce1a8ad4f318d95850ae2` |
| E8 file | `sha256 51611b267ca0c9622d44f34047464733b3c3b959feadb49c0db8afe9ad595c0d` |
| Wrangler config | `sha256 70bd14ece1d9a0946fcac3d92cc61a87087e14881508cf076a4d6503146dc1cf` |
| Engine bundle (`k/stage0.js`) | `sha256 46153a843fa81132a8d22c17acc84403724c3e9c68ad4768f0746c6cf89ca372` (the same hash E9's report cites) |

**The settlement functions are byte-identical to E9's**, proved at function level by
`hash-functions.mjs` (brace-matching extractor, SHA-256 of each body, exit 0 when all match;
saved at `D:/cache/stage0/e8/results/function-hashes.json`):

| Function | SHA-256 | Identical to E9 |
|---|---|---|
| `d1Run` | `530b607b52d4b6bb0ee2356671b740a0acb2c450594b6fd026b1ca2fe7ef72b5` | yes |
| `d1Batch` | `846d245012d96b33a4f18161b5e1686a5e5ff77275811eccae4e8fefce6158dd` | yes |
| `balances` | `49d7d85a917be171859708556fc429c505ba6532acac2ec4f48b221e4b60222a` | yes |
| `enqueueOnce` | `48b5e1858ea155b96c685f710b409bc73ae5b4080d2130a1fe57736f4d864acb` | yes |
| `storedRows` | `69f61fde0d6ee75ca69b0f93716216d452d68a350a8017ee3a9c2f840fc2b729` | yes |
| `relayOnce` | `55057805ca926c8b6eb40c86c7617d08efb4dd659b52cb833cc2f8e8c75066a0` | yes |
| `ackOnce` | `200289636a995f0602ced3b800ecdc3841fcc5101cfa0a5ce159235e5cd90a83` | yes |
| `backupOnce` | `d2259365fef2e84bcf5dc7fb89c44d05a28359173c18632b019a18907b8fcabb` | yes |
| `deriveIntent` | `96ca19631e0487f7c520361583e5a4c8c0bd40c795f11057bef11ea8d0d4ad9c` | yes |
| `finishSettlement` | `b3286fefd5b8150f15b335e4d45b51ffacf7d4fa1f92b3919a9c29cf009a48c0` | yes |
| `reconcile` | `0bfeaef6111be29482f94f182b3a519ebd8b8528bd83060aa49f5003755da3d5` | yes |

**How the batch under test is guaranteed to be the real one.** `e8Capture()` replaces `this.d1Batch`
for exactly one call and lets the **real** `relayOnce` build its statements; the captured array is
then executed by the failure case. No second settlement implementation exists — the probe batch is
production code. `e8Observed()` runs the same real `relayOnce` with the normal `d1Batch` and records
each statement's `meta`.

**Layout.** Three **separate** local D1 databases, as E7/E9: `stage0-e8-rp` (`…e8`),
`stage0-e8-rp-backup` (`…b8`), `stage0-e8-outbox` (`…08`); the state directory holds three distinct
SQLite files. Port **8985**, `--persist-to state`, state wiped before every run, unique DO names per
case (`e9-s900…e9-s904`), `WRANGLER_SEND_METRICS=false`, a scratch `XDG_CONFIG_HOME`.

---

## 4. Atomicity at the real shape [M]

The batch: `INSERT INTO settlement` + 4 × `INSERT INTO ledger` = **5 statements**, all on `env.RP`.
The payload is the real seed-43 settlement (totalDelta 71) under fresh match ids. Two failure
injections:

- **k = 0** — the match is settled first, then the **same batch** is re-run; statement 1 fails on the
  primary key.
- **k = 1…4** — a temporary trigger
  `CREATE TRIGGER e8_abort BEFORE INSERT ON ledger WHEN NEW.delta = 7777 BEGIN SELECT RAISE(ABORT, 'e8-sentinel-abort'); END`
  aborts when ledger row **k** carries the sentinel delta 7777 (the payload's `totalDelta` is
  recomputed so the batch is internally consistent). Created in setup, dropped after the case.
  k = 4 is the **last** statement.

| k | Failing statement | Error (exact) | Rows after | Globals before = after |
|---|---|---|---|---|
| 0 | 1 — `settlement` PK | `D1_ERROR: UNIQUE constraint failed: settlement.match_id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)` | pre-existing **1 settlement / 4 ledger**, unchanged | yes |
| 1 | 2 — ledger row 1 | `D1_ERROR: e8-sentinel-abort: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_TRIGGER)` | **0 / 0** | yes |
| 2 | 3 — ledger row 2 | same sentinel error | **0 / 0** | yes |
| 3 | 4 — ledger row 3 | same sentinel error | **0 / 0** | yes |
| 4 | 5 — ledger row 4 (last) | same sentinel error | **0 / 0** | yes |

**What the error names.** The error shape is
`{name: "Error", message, code: null, cause, keys: [], stackHead}`; `message`/`cause` name the
**constraint or the trigger** (`settlement.match_id`, `e8-sentinel-abort`) and the SQLite extended
code — **never a statement index**. A caller cannot learn which of the five statements failed from
the error alone; it can only classify by the error text. This matters for the relay's retry policy:
"duplicate" and "constraint failure" must be distinguished by content, not position.

**The correct batch, after each failed attempt.** The trigger is dropped, the same real
`relayOnce` runs, and the store reads:
`5 table rows / 10 metered rows`, once. Per-statement `meta`:

| statement | `rows_written` | `changes` |
|---|---|---|
| settlement | 2 | 1 |
| ledger ×4 | 2 each | 1 each |
| **total** | **10** | **5** |

The ×2 is table row + primary-key index row — the same shape E12-a measured on the platform and E7's
10-rows-per-relay figure. The billing meaning of `meta.rows_written` is still **[x]** (E7 §12.1).

**Replay and split-brain, every k.** Re-running the stored payload returns
`{applied: false, duplicate: true, unchanged: true, rowsWritten: 0}` with the same UNIQUE error on
the duplicate path. A **modified** payload (one ledger row's `delta` and `rp` +1, `totalDelta` +1) is
refused with HTTP 503: `split brain: e9-s90k-full-normal already holds a different settlement`.

**Zero-statement batch.** `db.batch([])` is refused:
`D1_ERROR: No SQL statements detected.` A builder must not emit an empty batch.

---

## 5. What the batch contains — and what it does not

The **atomic unit is one database**: 5 statements on RP. The 27-row protocol is **not** one batch.
It is four separate calls, each idempotent, on three databases:

| Step | Store | Statements / rows |
|---|---|---|
| freeze intent | DO SQLite | 1 row (not D1) |
| enqueue | OUTBOX | 3 rows |
| **relay (the settlement)** | **RP** | **5 statements → 10 metered rows** |
| ack | OUTBOX | 1 row |
| backup | RP_BACKUP | 14 first / 12 steady |
| cleanup | DO SQLite + OUTBOX | 1 mark (DO) + the log delete (DO) + 1 outbox delete (OUTBOX) |

So the answer to §1's "one batch that applies RP + OUTBOX + backup or none of it" is: **no single
batch exists**; what exists is an atomic RP batch preceded by a durable intent and followed by
idempotent outbox/backup steps. The atomicity that matters — "the settlement half-applies" — is the
RP batch, and it is measured atomic in §4.

---

## 6. Cross-database reality [M]

Four probes, each a **batch bound to one binding containing a statement prepared from another**:

| Probe | Batch on | Statement prepared from | Result |
|---|---|---|---|
| read | RP | OUTBOX (`SELECT 1`) | **accepted** — executed against RP's database |
| read | OUTBOX | RP (`SELECT 1`) | **accepted** — executed against OUTBOX's database |
| write | RP | OUTBOX (`INSERT INTO outbox …`) | **rejected**: `D1_ERROR: no such table: outbox: SQLITE_ERROR` |
| write | OUTBOX | RP (`INSERT INTO ledger …`) | **rejected**: `D1_ERROR: no such table: ledger: SQLITE_ERROR` |

Before/after global counts are identical for all four (settlements 7 / ledger 28 / outbox 0 / backup
8 at this point). **Miniflare ignores the prepared statement's binding and executes it against the
batch's database**; a foreign statement only reveals itself when it names a foreign table. This is
the local behaviour; the platform leg must confirm production returns an explicit error (the likely
outcome) rather than silently accepting a statement — the divergence, if any, is recorded in §14.

**The consequence, stated plainly:** while RP, RP_BACKUP and OUTBOX are three databases, *"RP +
OUTBOX + backup in one atomic batch" is impossible*. Cross-store consistency rests on the **frozen
intent** (written before any D1 write) plus **idempotent replay** — proven by E7 and E9 — not on a
cross-store transaction. **D-E7-3 (three databases or one) stays open: E8 informs it, it does not
decide it.** One database would make the cross-store batch possible; it is not required by anything
measured here.

---

## 7. Triggers — capability, not requirement [M]

No trigger exists in the design. The trigger was used as a fault injector, and its behaviour is now
measured:

1. It fires **inside** `batch()` — the error is returned by the batch call, and the whole sequence
   rolls back (k = 1 shows statement 1 already executed and then rolled back).
2. `CREATE TRIGGER` costs **1 row locally** (`rowsWritten: 1`); `DROP TRIGGER` costs **0**.
3. After the drop, normal settlement writes resume: **10 metered rows**, once; replay duplicate,
   0 writes; a modified payload refused as split-brain.
4. The trigger rolls back **at every position**, including the statement immediately after it and the
   last statement (k = 4).

Conclusion: if a trigger is ever added, it is a batch-wide abort, not a row-local rejection. The
design does not need one, so this is capability evidence only.

---

## 8. Limits [M local vs documented]

Both pages were fetched by the driver at run time and archived with hashes (§16):

- `https://developers.cloudflare.com/d1/platform/limits/` — **last updated Oct 30, 2026**,
  **accessed 2026-10-31**.
- `https://developers.cloudflare.com/d1/worker-api/d1-database/` (`batch()`) — **last updated
  Jun 22, 2026**, **accessed 2026-10-31**.

Documented vocabulary, quoted verbatim:

> "Maximum string, BLOB or table row size 2,000,000 bytes (2 MB) Maximum SQL statement length
> 100,000 bytes (100 KB) Maximum bound parameters per query 100 … Maximum SQL query duration 30
> seconds … Maximum query result size 50 MB" — D1 limits.

> "Queries per Worker invocation (read subrequest limits ) 1000 (Workers Paid) / 50 (Free)" — same page.

> "Limits for individual queries (listed above) apply to each individual statement contained within
> a batch statement. … The Queries per Worker invocation limit is an exception: a `db.batch()` call
> counts as a single query against it, not one query per statement. For example, a batch containing
> 2,000 statements executes in a single Worker invocation, while issuing 2,000 individual queries in
> one invocation fails with `Too many API requests by single Worker invocation`." — same page,
> "Batch limits". **No row states a maximum number of statements per batch, or of parameters per
> batch.**

> "Batched statements are SQL transactions. If a statement in the sequence fails, then an error is
> returned for that specific statement, and it aborts or rolls back the entire sequence." — D1
> Database API, `batch()`.

| Limit (settlement path) | Documented | Local [M] | Difference |
|---|---|---|---|
| Statements per `batch()` | no maximum listed; 2,000-statement batch is the docs' example | 100 / 101 / 1,000 / 1,001 / 2,000 / 2,001 / **10,000** all accepted; 10,000 in 426 ms | none testable — no local cap |
| Bound parameters per **query** | 100 | 100 accepted; **101 and 1,000 rejected**: `D1_ERROR: too many SQL variables at offset 207: SQLITE_ERROR` | **matches** |
| Bound parameters per **batch** | not listed | 100 × 100 = 10,000 accepted; 200 × 100 = 20,000 accepted; 100 × 101 fails only on the per-statement 100 | no batch-level cap locally |
| SQL statement length | 100,000 bytes | 100,000 accepted; **100,001 rejected**: `D1_ERROR: statement too long: SQLITE_TOOBIG` | **matches** |
| String / BLOB / row size | 2,000,000 bytes | 2,000,000 accepted; **2,000,001 also accepted and stored** (`storedLength: 2000001`) | **local does not enforce** |
| Queries per Worker invocation | 1000 Paid / 50 Free; a batch counts as **one** | 50 and **51** individual queries accepted; 50 and 51 batches accepted | **local does not enforce** |
| Batch = 1 query against the invocation cap | documented (quoted above) | not locally meterable | **platform leg** |
| Query duration | 30 s | not exercised (every settlement statement is ~0 ms) | not measured |
| Query result size | 50 MB | not exercised | not measured |
| Columns per table | 100 | schemas use 1–6 columns | not exercised |
| LIKE/GLOB pattern length | 50 bytes | not touched by the settlement path | not exercised |
| Bindings per Worker script | ≈ 5,000 | 4 (3 D1 + 1 DO) | n/a |

**The two divergences are the point of the platform leg.** Locally, the 2 MB row cap and the
50-query Free cap are not enforced, so a design that accidentally crosses either would pass locally
and fail in production. The settlement path itself uses 5 statements (1 query) and rows of a few
hundred bytes, so it is far from both — but "far from" on an unenforced local limit is not evidence.

---

## 9. The full protocol at E7's 27-row shape [M]

Two complete matches on one boot, each through `finalize → enqueue → relay → ack → backup →
cleanup`:

| Run | Log steps | finalize¹ | enqueue | relay | ack | backup | D1 rows (settlement) | RP / OUTBOX / BACKUP |
|---|---|---|---|---|---|---|---|---|
| A — seed 42 (first settlement) | 411 | 1 | 3 | **10** | 1 | **14** | **29** | 10 / 5 / 14 |
| B — seed 43 (steady settlement) | 426 | 1 | 3 | **10** | 1 | **12** | **27** | 10 / 5 / 12 |

¹ `finalize` is a DO SQLite row, not D1. **Cleanup is outside the 27:** the mark is 1 DO row, the
log delete is 411 / 426 DO rows, and the outbox delete is 1 OUTBOX row. The capacity model's 27
counts **RP 10 + OUTBOX 3+1+1 = 5 + RP_BACKUP 12**, which is the steady-state figure. The first
settlement is 29 because the first backup copy is 14 (it **creates** the two watermark rows; every
later run only updates them) — the same first-vs-steady split E7 measured (§8.1 there).
Handler cursors agree: `d1Writes` for B reads **RP 10 / RP_BACKUP 12 / OUTBOX 5**. The store after
both: settlement 2 / ledger 8 / backup 8 / outbox 0, deltas equal (73 and 71, total 144).

This reproduces the 2026-10-30 cadence-1 figure; it is **not** a re-measurement of E12-a and does not
change the capacity model's 27.

---

## 10. The failing control (the suite is not blind)

The same settlement payload is written **twice** into keyless copies of the two tables
(`settlement_nokey`, `ledger_nokey`), which have no primary key:

```
{ status: "duplicated", settlements: 2, ledgerRows: 8, ledgerSeats: 4,
  totalDelta: 142, expectedDelta: 71 }
```

The keyed settlement simultaneously reads **1 / 4** — untouched. So:
the atomicity check **can** fail (a store without the key really does double-apply), and the
exactly-once check is reading the keyed store, not a coincidentally-empty one. This is the E7
negative-control pattern, executed here **before** the atomicity matrix so it cannot be confused with
the real settlements.

---

## 11. Residual limits of this report

1. **Local runtime only.** Miniflare's SQLite inside `wrangler dev --local`, not Cloudflare D1: no
   queueing, no replication, no network, no real meter.
2. **Two documented limits are not enforced locally** (2 MB row size, 50-query Free cap) — §8. The
   platform leg decides them.
3. **Batch accounting is documented, not metered.** "One `db.batch()` = one query" is quoted; the
   local runtime cannot count invocation queries.
4. **`meta.rows_written` is local semantics.** The ×2 shape matches E12-a's platform measurement,
   but the billing definition remains **[x]** (E7 §12.1).
5. **No concurrency, no real quota event.** One DO, one client, no 429, no account metering.
6. **One shape:** FULL/NORMAL, four bot seats; seeds 42, 43 and the atomicity payload derived from
   seed 43.
7. **Triggers are capability evidence**, not a design requirement (§7).
8. **The three-database layout is the harness's**, matching E7/E9. The platform checklist creates
   three databases; if the platform accepts a cross-binding statement, that changes the D-E7-3
   question, not the atomicity result.
9. **The duplicate path is detected, not prevented** for a rogue writer that bypasses the queue
   (E7 §12.6 stands).

---

## 12. What this settles — and what it does not

**Settles (local, [M]):**

1. **E8's pass criterion on the local runtime:** the settlement batch is atomic within every
   documented limit it touches; a failed batch leaves no partial row — at the first, middle and last
   statement, and via a duplicate key.
2. **The exact failure semantics** the relay must handle: one error for the whole batch, naming the
   constraint or trigger (not a statement index), with the store unchanged; replay duplicate with 0
   writes; modified payload refused as split-brain.
3. **The 27-row steady protocol** and the first-settlement 29 (backup 14).
4. **The cross-database consequence:** no single atomic batch spans the three stores; consistency is
   the frozen intent + idempotent replay.
5. **Two limits are unenforced locally** and must be measured on the platform.

**Does not settle:**

1. **E8 is not closed.** The real-D1 leg is **[x]**: platform enforcement of the 2 MB row cap and
   the 50-query Free cap, the batch-vs-query accounting, the real row meter, and the platform's
   cross-binding behaviour.
2. **P3 stays gated on two platform legs:** **E9's platform eviction leg** and **E8's platform leg**.
   The local results do not close either.
3. **D-E7-3 stays open.** E8 informs it; it does not decide whether the three databases become one.
4. **No architecture change.** E8 adds one measured fact — the RP batch is a transaction locally —
   and one measured consequence — three databases cannot be one transaction.

---

## 13. The platform checklist — written, not run

**الحالة: مستنيّ. ولا خطوة واحدة اتنفّذت، ومفيش أي حاجة اتعملت على Cloudflare.**

**⏸ إيقاف — لحد ما تقول "موافق على خطوة N"، محدش يعمل الخطوة دي.**

> **POINTER NOTE (2026-11-01 — step 2 ran on account B and was verified byte-identical; the checklist
> above is left verbatim) [E8 platform wave — note 2 of 3].** **خطوة 2 اتنفذت على الحساب B بس.**
> The **☐ step 2** box above has been executed; steps 0 and 1 ran on 2026-10-31, and **steps 3 and 4
> remain open.**
>
> | | |
> |---|---|
> | Worker | `stage0-e8`, on **account B** `c6321eda25af9e06234358928272a0fa` — **account A `7e9c5a16c04380f81cfd730b76d8684b` was never called** (every request in the run asserts it) |
> | Deployment | `d0a91f82-3742-466b-a62f-b5f168009ef0`, created **2026-11-01T07:49:55.333572Z** (source `wrangler`, strategy `percentage`, 100%) |
> | Version | `e3251b85-c283-4039-a146-9fc204c07c9d` — number **1**, 100% |
> | URL | `https://stage0-e8.estemshan-e12.workers.dev` — the `estemshan-e12` workers.dev subdomain **already existed from E12**, so it was **reused, not created** |
> | Source deployed | `worker.mjs` — **sha256 `51611b267ca0c9622d44f34047464733b3c3b959feadb49c0db8afe9ad595c0d`**, **62,076 B** — the exact file the local run measured (§16) |
> | Dry-run bundle | `worker.js` — **sha256 `4177f5b61d53d75b3c4eac1e5ea2de1e8d1d5723c5cf5793bbdd88f7600c7c5b`**, **998,854 B** |
> | Live module | the `worker.js` part **fetched back from the API** has the **same 998,854 B and the same sha256** — **the deployed executable is byte-identical to the local one** |
> | Engine bundle | `k/stage0.js` — **sha256 `46153a843fa81132a8d22c17acc84403724c3e9c68ad4768f0746c6cf89ca372`**, **303,457 B** — unchanged |
> | Bindings | `MATCH_DO` → durable_object_namespace `ac49ba2d564a4c7fb32e17d81b2ed624` (SQLite class `MatchDO`); `RP` → `fc7cb238-975a-4ab6-b75b-7cb52fca66ee`; `RP_BACKUP` → `a308600e-d171-4658-a755-d78655a1dc6a`; `OUTBOX` → `9c849bdf-d681-4d66-91c3-f5a45a0f37e6` — the three D1 ids **match the step-1 creation evidence** |
> | KV namespaces | `[]` |
> | Config equivalence | `compatibility_date = "2026-10-29"` and `migrations.new_sqlite_classes = ["MatchDO"]` are **identical to the harness**; only the real D1 ids, the pinned `account_id` and `workers_dev = true` differ |
>
> **What this step proves, and what it does not.** It proves the **deploy is faithful** — the bytes
> that run on Cloudflare are the bytes measured locally — and **nothing about D1's behaviour**. The
> settlement has not been run against real D1, so **§14's numbers are still unmeasured**.
>
> **Corrected figures for this step** (these supersede the earlier chat report):
>
> - **Token scan.** The scan covered **17 files written today** under `D:/cache/stage0/e8` — **25**
>   written after step 0 and **117** in total under that tree — and **zero files contain the token**.
> - **Source maps.** The **raw live** `worker.js.map` part is
>   `5d394166695ffa17e5bd036cd1fcd07e2fc2ce996533c5b7d56bfaf06be44e40` (**1,511,606 B**); the local
>   map is `64b4e060340d566fb754af59205069f90d51c6e2ef9a4c7cfc8099858b56788d` (**1,505,598 B**) — the
>   raw bytes differ by **6,008 B** (path encoding, and a `file` field present in the live map). The
>   **decoded `mappings` and `sourcesContent` are identical**, so the code is the same — but the raw
>   map parts are **not** byte-identical. The `liveSha256`
>   `55de90997a191dbca68f4fed8e51fba37c268919d331808b52b020777a445a16` in the delta file is the
>   **re-serialized** map, **not** the raw part.
> - **DO namespace listing.** The delta file's account-level listing called
>   `/accounts/{B}/durable_objects/namespaces` — **missing the `/workers` segment** — and returned
>   **404**. The namespace id is proven by the **script-settings binding**; **the 404 is not
>   evidence.**
> - **Billing.** **No billing action was taken** in this step. (The plan check was step 0's; this
>   step deployed and verified bytes.)
>
> **Evidence (outside the repository):**
> `D:/cache/stage0/e8/results/platform-step2-preflight-2026-11-01T07-48-16-735Z.json`,
> `D:/cache/stage0/e8/results/bundle-step2-2026-11-01T07-49-36-608Z/worker.js` (and `worker.js.map`),
> `D:/cache/stage0/e8/logs/platform-step2-dryrun-2026-11-01T07-49-36-608Z.txt`,
> `D:/cache/stage0/e8/logs/platform-step2-deploy-2026-11-01T07-49-46-212Z.txt`,
> `D:/cache/stage0/e8/results/platform-step2-verify-2026-11-01T07-50-37-090Z.json`,
> `D:/cache/stage0/e8/results/platform-step2-delta-2026-11-01T07-51-03-429Z.json`, and step 1's
> `D:/cache/stage0/e8/results/platform-step1-2026-10-31T12-06-02-697Z.json`.

> **POINTER NOTE (2026-11-01 — step 3 ran the protocol against real D1 on the deployed module; the
> checklist above is left verbatim) [E8 platform wave — note 3 of 3].** **خطوة 3 اتنفذت على الحساب B
> بس، والـmodule اللي اتقاس هو نفسه اللي اتنشر — مفيش إعادة نشر.** Ran **2026-11-01
> 09:01:59→09:04:54Z**: **53/53 checks, 0 failures**, exit 0. The code under test was the live module
> `4177f5b6…` (998,854 B) — re-hashed at **08:51:47** and again at **09:13:10**, **identical**, with
> the worker's `modified_on` still `2026-11-01T07:49:57Z` and version `e3251b85…` / deployment
> `d0a91f82…` unchanged. **Nothing was redeployed, edited, deleted or billed; account A was never
> called.**
>
> **The numbers.** `/prelude` → `{RP:4, RP_BACKUP:3, OUTBOX:1}`; `/e8/info` → bindings
> `["RP","RP_BACKUP","OUTBOX"]`, triggers `[]`, all eight globals **0**. `/summary` after the run →
> **7 settlements / 28 ledger rows**, Δ **499** on both sides, outbox **0**, backup **8** ledger
> rows. The independent audit agrees seat by seat: match A `56/−8/25/0` (Σ **73**), match B
> `40/0/39/−8` (Σ **71**), k=0…4 **71** each; RP is continuous across A→B (956→996, 1145→1184,
> 1180→1172); keyless control **2 settlements / 8 rows**; `e8_big` **2**; outbox **0**; backup
> watermark **2**. The clean-state proof taken **before** the first write shows all **nine** E8
> tables **absent** in all three databases.
>
> | §14 row | Expected | **Actual on real D1** |
> |---|---|---|
> | one correct settlement batch | 5 stmts / 10 metered rows, applied once | **5 / `[2,2,2,2,2]` = 10 / `changes [1,1,1,1,1]`** |
> | replay | duplicate, 0 writes | **duplicate, 0 writes** |
> | k = 0 duplicate | `UNIQUE constraint failed: settlement.match_id`; 5 rows unchanged | **`D1_ERROR: UNIQUE constraint failed: settlement.match_id: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_PRIMARYKEY)`**; globals byte-identical |
> | k = 1…4 trigger abort | 0 settlement / 0 ledger; globals unchanged | **`D1_ERROR: e8-sentinel-abort: SQLITE_CONSTRAINT (extended: SQLITE_CONSTRAINT_TRIGGER)`**; **0 / 0**; globals byte-identical |
> | 100 / 101 params | accepted / rejected | **accepted / `D1_ERROR: too many SQL variables at offset 207: SQLITE_ERROR`** |
> | SQL 100,000 / 100,001 | accepted / rejected | **accepted / `D1_ERROR: statement too long: SQLITE_TOOBIG`** |
> | row 2,000,000 / 2,000,001 B | accepted / **rejected** | **accepted / accepted** — see A4 |
> | 50 / 51 queries in one invocation | accepted / **rejected** | **accepted / accepted** — see A4 |
> | 5-statement batch = 1 query | **[doc]** | **[doc]** — the archived limits page: *"a `db.batch()` call counts as a single query against it, not one query per statement"* |
> | cross-binding statement | explicit error, not silent | **`no such table: outbox` / `no such table: ledger`** |
> | second settlement | 27 (RP 10 / OUTBOX 5 / BACKUP 12) | **27** |
> | first settlement | 29 (backup 14) | **29** |
>
> **A1 — the transport premise changed, and the run used HTTP.** E12a §3.2 recorded
> `*.workers.dev` as unreachable from this machine (an SNI filter). On 2026-11-01 the deployed
> worker's `/health` answered **200 `{"ok":true}`**, so the protocol ran **over HTTP against the
> deployed module** — no cron driver, no KV outbox, no redeploy, and therefore **no change to the
> code under test**. `wrangler d1 execute --remote` was used only as an **independent audit read**.
> This supersedes the step-3 sentence above that named it the primary path.
>
> **A2 — the RUN_TAG could not be a suffix (the brief was wrong).** The worker accepts a Durable
> Object name only if it matches `/^e9-s(\d+)-(full|mini)-(normal|classic)$/` (`worker.mjs:49`) and
> poisons any other instance (`worker.mjs:100-103`). Measured live: `…-p1` and `p1-…` both return
> **503 `unrecognised DO name`**, while zero-padded seeds reach the DO. The run therefore tagged by
> **zero-padding the seed digits** (`e9-s42-…` → `e9-s0042-…`): the seed (the match) is unchanged,
> the instance id and the `match_id` are new, and a retry gets clean state without a redeploy.
>
> **A3 — 21 limit cases, not 22.** The brief's count was wrong: 7 `stmts` + 3 `params` + 3
> `batchparams` + 2 `sql` + 2 `row` + 2 `queries` + 2 `batches` = **21**, plus the 5 atomicity cases
> = the 26-row scorecard. All 21 ran.
>
> **A4 — the two "expected rejections" did not happen, and they did not happen locally either.**
> The **2,000,001-byte row** was stored in full (`storedLength 2,000,001`) and **51 queries** (and 51
> batches) completed in one invocation. **All 21 limit outcomes are identical between the local and
> platform runs** — same accept/reject verdict and the same error text. So the divergence is
> **documented cap versus runtime**, not local versus platform. Account B is **Workers Free** (step
> 0's owner dashboard, sha256 `8cc78cee…`, re-hashed in this audit), so the 51-query result is
> against the documented **Free 50** cap, not the Paid 1,000.
>
> **A5 — no platform layer refused anything.** There was no `1101`, no `1102`, and no "too many
> subrequests" anywhere in the run; the only five non-200 responses were the **expected**
> split-brain `503`s at k = 0…4. Where the runtime refused, the refusing layer was always **D1**.
>
> **A6 — one extra write, disclosed and measured.** Before the tagging strategy was settled, a
> **3-step manual probe** was sent to match A's Durable Object. It wrote **only to the DO's own log**
> and **zero D1 rows**; verified independently — match A's D1 ledger still equals the driver's
> payload seat for seat (Σ 73). The log was removed by the cleanup rule; the instance now reads
> **rows 0 / applied 0 / final.cleaned 1**. The `applied 3` visible in the 09:08Z snapshot was
> **in-memory only** and is gone after reconstruction.
>
> **A7 — two corrections to the chat report.** (i) The keyless `2/8`, `e8_big` `2` and ledger-total
> `28` figures come from `platform-step3-postread-…`, **not** from the `integrity` file (which holds
> the seven settlements, the A/B ledgers, the backup rows, `meta` and an empty outbox). (ii) The
> "plan unreadable" caveat is unnecessary — step 0 established **Workers Free** from the owner's
> dashboard, so the Free caps are the right comparison.
>
> **A8 — token scan.** The step's own scan covered the **49 files written that day** (149 in the
> tree); this audit re-scanned **all 176 files** under the harness after every artefact existed —
> **0 hits** (token sha256 prefix `fe1038b0…`, never printed, never written).
>
> **What this changes for the plan.** The settlement path touches **none** of the caps that went
> unenforced — 5 statements, ≤ 100 parameters, ≤ 100 KB of SQL — so **D-E7-3 and the E7/E9
> protocol stand**, and the two gaps are a **capacity-model caveat**: anything that later relies on
> the 2 MB row cap or the 50-query Free cap must not assume the runtime enforces them.
>
> **Evidence (outside the repository):**
> `D:/cache/stage0/e8/results/e8-platform-results-2026-11-01T09-02-00-408Z.json` (53 checks),
> `…/platform-step3-scorecard-2026-11-01T09-02-00-408Z.{json,md}` (26 rows),
> `…/platform-step3-run-2026-11-01T09-01-59-466Z.json` (prelude → info → driver → summary),
> `…/platform-step3-preflight-api-2026-11-01T08-52-37-859Z.json`,
> `…/platform-step3-cleanstate-2026-11-01T08-52-45-002Z.json`,
> `…/platform-step3-nameprobe-2026-11-01T08-54-56-625Z.json`,
> `…/platform-step3-postread-2026-11-01T09-08-17-326Z.json`,
> `…/platform-step3-integrity-2026-11-01T09-12-31-305Z.json`,
> `…/platform-step3-tokenscan-2026-11-01T09-08-16-958Z.json`,
> `…/platform-step2-verify-2026-11-01T09-13-10-419Z.json` (post-run re-hash),
> `D:/cache/stage0/e8/logs/platform-step3-2026-11-01T09-02-00-408Z.txt`, and
> `D:/cache/stage0/e8/logs/driver-platform-diff.txt` (transport only; the five test functions and
> all 26 `check(` call sites are byte-identical to `driver.local-copy.mjs`).

### القرارات (2026-10-31)

1. **التجربة على الحساب المؤقت B بس** — مفيش بيانات حقيقية، ومفيش أي نشر على الإنتاج ولا على الحساب
   الأساسي A.
2. **3 قواعد D1 منفصلة على B** — زي ما E7/E9 اشتغلوا محليًا بالظبط: RP وRP_BACKUP وOUTBOX.
3. **كل خطوة بتستأذن لوحدها**، وأنا بقف عند حدّ الخطوة اللي بتوافق عليها.

### الأرقام اللي التجربة لازم تطلعها (المتوقع من المحلي [M])

| الفحص | المتوقع على المنصة |
|---|---|
| تسوية صح | **5 statements، 10 صفوف محسوبة** (2 لكل صف: جدول + فهرس المفتاح) |
| إعادة نفس التسوية | duplicate، **صفر كتابة** |
| فشل عند أول statement (مفتاح مكرر) | رسالة فيها `UNIQUE constraint failed: settlement.match_id`، والصفوف القديمة **5 زي ما هي** |
| فشل عند statement 2 / 3 / 4 / 5 | **صفر صف تسوية وصفر صف دفتر** — مفيش صف نصّي |
| بروتوكول كامل (تسوية تانية) | **27 صف**: RP 10 + OUTBOX 5 (3+1+1) + BACKUP 12 |
| أول تسوية | **29 صف** (الـbackup الأول 14) |
| 100 باراميتر / 101 | 100 يعدي، **101 يفشل** |
| SQL 100,000 بايت / 100,001 | 100,000 يعدي، **100,001 يفشل** |
| صف 2,000,000 بايت / 2,000,001 | **[x] يتأكد هنا** — محليًا الاتنين عدّوا |
| 50 استعلام / 51 في نفس الـinvocation | **[x] يتأكد هنا** — محليًا الاتنين عدّوا |
| batch فيه 5 statements = كام استعلام؟ | **[x] يتأكد هنا** — المتوقع **1** حسب الوثائق |
| batch فيه statement من قاعدة تانية | **[x] يتأكد هنا** — محليًا: القراءة عدّت، والكتابة فشلت `no such table` |

### تشيك ليست E8 — كل خطوة محتاجة موافقتك

☐ **خطوة 0 — الحساب المؤقت B.**
نتأكد إن الحساب B (`c6321eda25af9e06234358928272a0fa`) هو المقصود، والاشتراكات `[]` — يعني **مفيش
billing**. والحساب الأساسي A (`7e9c5a16c04380f81cfd730b76d8684b`) **ما اتمسش** خالص.

☐ **خطوة 1 — إنشاء 3 قواعد D1 على B (Free فقط).**
`stage0-e8-rp-<date>` و`stage0-e8-rp-backup-<date>` و`stage0-e8-outbox-<date>` — تلات قواعد منفصلة،
وأسماء فيها تاريخ التشغيل. تتأكد إن الخطة Free قبل الإنشاء.

☐ **خطوة 2 — نشر الـworker على B.**
نفس `worker.mjs` اللي اتشغّل محليًا (هاش `51611b26…`)، بـ`account_id` مثبّت، و`XDG_CONFIG_HOME` جديد،
والتوكن بيتقرأ من `D:/cache/stage0/e12/cf.env` — **مش بيتطبع ولا بيتحفظ في الريبو**. مفيش نشر على
الإنتاج ولا على A.

☐ **خطوة 3 — تشغيل الاختبارات وقراءة الدليل.**
atomicity (k=0..4) + triggers + الحدود + بروتوكول 27 صف. القراءة الأساسية بـ
`wrangler d1 execute <db> --remote --command ...` من نفس الجهاز؛ KV/REST احتياطي بس.
**ملاحظة:** `*.workers.dev` مش بيتوصل من الجهاز ده (E12a §3.2)، فالقراءة من القاعدة مباشرة هي الطريق.

☐ **خطوة 4 — التنظيف والتأكيد.**
مسح الـworker + القواعد + KV، والتأكد إن كله فاضي: `scripts []`، DB list `[]`، KV list `[]`.

### الضوابط (قبل ما نبدأ)

- **مفيش بيانات حقيقية** — كل حاجة synthetic. مفيش مستخدمين حقيقيين ولا هوت.
- **مفيش billing** — Free tier بس، والكوتا من الحساب المؤقت B.
- **مفيش لمس للحساب الأساسي A** — ولا نشر، ولا قراءة، ولا توكن.
- **مفيش تغيير على قواعد Firestore** ولا على `firestore.rules.sha256`.
- **مفيش خطة مدفوعة.**
- **كل خطوة بتستأذن لوحدها** — "موافق على خطوة N" وبس.

---

## 14. The exact numbers the platform run must produce

If the platform behaves like the local runtime, the platform leg's evidence file should read:

| Check | Expected platform value | If it differs, record |
|---|---|---|
| one correct settlement batch | `5 statements`, `10` metered rows, applied once | the real per-statement `meta` |
| replay | duplicate, 0 writes, 0 new rows | — |
| k = 0 duplicate | error contains `UNIQUE constraint failed: settlement.match_id`; the pre-existing 5 rows unchanged | the exact error text |
| k = 1…4 trigger abort | 0 settlement / 0 ledger after the failure; globals unchanged | the exact error text |
| 100 params | accepted | — |
| 101 params | rejected, `too many SQL variables` | the exact error |
| SQL 100,000 bytes | accepted | — |
| SQL 100,001 bytes | rejected, `statement too long` | the exact error |
| row 2,000,000 bytes | accepted | — |
| **row 2,000,001 bytes** | **rejected** (documented) — local accepted it | **a local/platform divergence to record** |
| **51 queries in one invocation (Free)** | **rejected** (documented) — local accepted | **a local/platform divergence to record** |
| 5-statement batch | **1 query** against the invocation cap | how it was counted (dashboard/GraphQL) |
| cross-binding statement | explicit error (expected), not silent execution | the exact behaviour |
| protocol, second settlement | 27 rows (RP 10 / OUTBOX 5 / BACKUP 12) | the real row meter per store |
| first settlement | 29 rows (backup 14) | the real backup cost |

Two of these are **expected to diverge** from the local run (the 2 MB row cap and the 50-query cap).
A platform result identical to local on those two would mean the platform is not enforcing its own
documented limits — a finding to record, not a pass.

> **POINTER NOTE (2026-11-01 — this table has now been measured; the text above is left verbatim)
> [E8 platform wave — note 3 above].** Step 3 produced every row on real D1 (53/53 checks): **12 of
> the 15 rows matched exactly**; **the two "expected to diverge" rows did not diverge** — the 2,000,001-byte
> row and the 51-query invocation were **accepted on the platform and on the local runtime alike**,
> so the failing assumption is the **documented cap**, not the platform. The "5-statement batch = 1
> query" row stays **[doc]**: it is not observable over HTTP, and the archived limits page states it
> directly. Full numbers and the five other corrections are in §13's note 3 (A1–A8).

---

## 15. Reproduction

Everything below ran locally on 2026-10-31: **no account, no token, no deploy, no installs.** Node is
Gradle's portable 22.14.0; wrangler is the existing 4.155.0 under `D:/cache/stage0/node_modules`.

```bash
NODE="C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe"

# 1. prove the settlement functions are E9's, byte for byte (exit 0 = all identical)
"$NODE" D:/cache/stage0/e8/hash-functions.mjs

# 2. the full run: wipes state, fetches and archives both docs pages, boots
#    wrangler dev --local on 127.0.0.1:8985, plays two matches, runs the
#    negative control, atomicity k=0..4, empty batch, cross-DB, limits, and
#    writes results/e8-results-latest.json
"$NODE" D:/cache/stage0/e8/driver.mjs

# 3. the harness diff: 0 removed lines, 299 insertions
diff -u D:/cache/stage0/e9s4/worker.mjs D:/cache/stage0/e8/worker.mjs
```

The driver is deterministic in its assertions (it uses fixed seeds and fresh DO names); the only
volatile fields are timings, boot digests and the run timestamp.

---

## 16. Artifacts

| Artifact | Path | SHA-256 |
|---|---|---|
| Harness worker (E8) | `D:/cache/stage0/e8/worker.mjs` | `51611b267ca0c9622d44f34047464733b3c3b959feadb49c0db8afe9ad595c0d` |
| E9 harness (reference) | `D:/cache/stage0/e9s4/worker.mjs` | `39a4bb2ae29f0c7d7bcafc3344b0a34e05992430758ce1a8ad4f318d95850ae2` |
| Wrangler config | `D:/cache/stage0/e8/wrangler.toml` | `70bd14ece1d9a0946fcac3d92cc61a87087e14881508cf076a4d6503146dc1cf` |
| Engine bundle | `D:/cache/stage0/e8/k/stage0.js` | `46153a843fa81132a8d22c17acc84403724c3e9c68ad4768f0746c6cf89ca372` |
| Function hashes | `D:/cache/stage0/e8/results/function-hashes.json` | all 11 `same: true` |
| Canonical results | `D:/cache/stage0/e8/results/e8-results-latest.json` | `e6d506e3e02add199aff4cf4418dc620a978003d030b28de8edcac6b07df12c1` |
| Run log | `D:/cache/stage0/e8/driver-run.log` | `2a7bdb62d91d59b9b96d6b03043465fa2ff0cdcc433b366eaf5bb27ab38b13d7` |
| D1 limits page (archived) | `D:/cache/stage0/e8/results/docs/limits-2026-10-31T11-29-13-395Z.html` | `5c4b2d9316ee1a2eb5bc83b9b1d668ecbbcc27856ecc49358abe12c0898f52fb` |
| `batch()` page (archived) | `D:/cache/stage0/e8/results/docs/batch-2026-10-31T11-29-13-395Z.html` | `2e775103df4f71dffed893c61eda0f92d29e33fd2255c4163626d138181f8072` |

**Nothing in this list is committed.** The repository carries only this report and the pointer
notes dated 2026-10-31.

---

## 17. Owner summary (Egyptian Arabic)

**E8 المحلي خلص ونجح — والتسوية بقت مضمونة محليًا.**

- **السؤال كان:** لما D1 يكتب التسوية، بيكتبها كلها أو مفيش؟ **الإجابة: كلها أو مفيش.** جرّبنا فشل
  عند أول سطر وعند السطر التاني والتالت والرابع والخامس — وكل مرة القاعدة بترجع **صفر صف تسوية وصفر
  صف دفتر**، مفيش صف نصّي. وده بالظبط شرط النجاح بتاع E8.
- **التسوية الصح** بتكتب **5 صفوف = 10 صفوف محسوبة** (الصف + فهرسه)، ومرة واحدة بس؛ وإعادة نفس
  التسوية بترجع "مكرر" بصفر كتابة؛ ولو البيانات اتغيّرت بترفضها "split brain".
- **البروتوكول كله** اتأكد تاني: **27 صف** للتسوية العادية (RP 10 + OUTBOX 5 + BACKUP 12)، وأول
  تسوية **29** لأن الـbackup الأول أغلى.
- **اللي محتاج الحساب الحقيقي:** حد حجم الصف (2 ميجا) وحد الـ50 استعلام في الخطة المجانية —
  **الاتنين مش بيتطبقوا محليًا**، فمش عارفين على Cloudflare بيتطبقوا ولا لأ. وبرضه عدّاد الصفوف
  الحقيقي، وهل الـbatch بيتحسب استعلام واحد.
- **حاجة مهمة:** القواعد التلاتة (RP / OUTBOX / backup) منفصلة، يعني **مستحيل** تكتبهم كلهم في
  معاملة واحدة. الأمان جاي من إن الـintent بيتحفظ الأول، وإعادة المحاولة آمنة — وده اللي E7 وE9
  أثبتوه. **D-E7-3 لسه مفتوح**؛ E8 بيغذّيه، مش بيحسمه.
- **P3 لسه موقوف** على حاجتين على المنصة: **تجربة الإخلاء بتاعة E9** و**تجربة D1 الحقيقية بتاعة E8**.
- **مفيش أي حاجة اتعملت على Cloudflare** — لا حساب، لا نشر، لا توكن. المستنيّ منك: "موافق على خطوة
  0" وبعدها كل خطوة لوحدها من التشيك ليست اللي فوق.
- **اللي بعده محليًا:** **E10** (الحذف والاحتفاظ والتخزين).

> **POINTER NOTE (2026-10-31 — E10 المحلي خلص ونجح؛ السطر فوق اتساب زي ما هو) [E10 wave — note
> 1 of 1].** E10 اتقاس محليًا على branch `stage0/e10-retention`: **65 فحص، صفر فشل**، والحذف
> والاحتفاظ **بيدخلوا في الخط المجاني**. الرقم اللي بيوجع مخك مش التخزين — ده **صفوف الـDO: 854 صف
> لكل ماتش** (المسح بيتحسب مع الكتابة)، يعني **68.3%** من الحد المجاني عند 80 ماتش/يوم.
> **ورصيد اللاعب (الـledger) ما ينمسحش أبدًا**، والفيتير (بصمة على العقد) هو اللي بيمنع التسوية
> مرتين — من غيره التسوية اتطبقت مرتين وده اتقاس. **الفاضل قرار منك:** نحتفظ بتفاصيل الماتش **7 ولا
> 30 ولا 90 يوم** — التوصية **30 يوم**. التقرير:
> `docs/implementation/Stage0_E10_Retention_Storage_Report.md`.
