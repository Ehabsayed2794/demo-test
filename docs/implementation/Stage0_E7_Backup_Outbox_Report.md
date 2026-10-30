# Stage 0 — E7: backup lag, the outbox, recovery, and duplicate RP

**Status: MEASURED LOCALLY, 2026-10-30.** The plan's E7 row
(`NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3) asks: *"Backup lag, outbox, recovery, duplicate RP
— can the backup policy meet ≥90% without a second RP truth?"* and sets the criterion *"No
duplicate RP; lag measured; recovery documented."* **All three are met, locally.** The fault
matrix, the backup lag, the recovery times, the write-failure window, the loss windows, the
recompute path and the split-brain behaviour are **measured** against a local D1 (§3). The
platform-side facts they depend on — how Cloudflare's D1 actually meters writes, how it behaves
under a real quota event — are **unknown** and need a real Cloudflare account (§12).

**This is not an approval of Cloudflare, and it does not close O-06.** The runtime decision
stays an open slot in `docs/adr/0003-runtime-choice.md`.

Branch `stage0/e7-backup-outbox`, base `21ee77d`. No real Cloudflare account, no deployment, no
external quota, no merge, no PR.

> **POINTER NOTE (2026-10-30, E7 step 4 + E7b).** This report was corrected after the owner's
> step-4 brief. What changed: **§4** gained the S6b scenario and the explicit statement of where
> the settlement intent lives when both stores reject writes; **§5** gained the re-derivation
> paragraph (unproven, E9 must test it); **§8** gained the measured cadence 1/5/10 table;
> **§10** gained the ceiling at both bases (3,543 at 90%, 3,937 at 100%) and one corrected
> figure (**29 → 27 rows/settlement at cadence 1**, logged in §10.4). The companion experiment
> **E7b** (`Stage0_E7b_DO_Rows_Report.md`) measures the Durable Object's rows-written per write
> and is the source of the ×2 explanation. **No earlier figure was silently rewritten** — §10.4
> carries the old → new log.
>
> **POINTER NOTE (2026-10-30, owner rulings).** After the measurements above, the owner ruled on
> the three open questions this report carried. The record is
> `docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md`:
> **D-E7-1 is DECIDED — the backup cadence is every settlement (N = 1)**, which fixes the protocol
> figure the capacity model carries at **27 rows/settlement** (not 25.4) and the settlement-axis
> ceiling at **3,333 matches/day at the 90% guardrail / 3,703 at the hard limit** (§8.1, §10,
> §10.4). The 90% behaviour is also ruled (**refuse new matches, never cancel running ones**).
> **No earlier figure was silently rewritten** — §10.4 carries the old → new log, and the
> earlier bodies are left verbatim.

**Labels used throughout:** **[M] measured** (on this machine, against a local D1) ·
**[m] modelled** (an assumption, stated) · **[u] unknown** · **[x] needs a real Cloudflare
experiment**. **A local D1 does not measure Cloudflare behaviour** — every platform-side claim in
this report is marked **[m]**, **[u]** or **[x]**.

---

## 1. What the experiment is

The owner's 2026-10-21 ruling names the property E7 has to prove: *"a stale backup cannot award
RP twice (single idempotency key authority)"*. A backup copy that can be restored is only safe if
restoring it cannot double-award RP, and only useful if the gap it leaves can be closed.

E7 therefore needs a **protocol**, not a snapshot. The protocol has three stores with three roles
that never blur:

| Store | Role | Writes | Reads |
|---|---|---|---|
| **RP** | the **one** RP source of truth — `settlement` (one row per match) and `ledger` (one row per match per seat) | the relay, and the restore | the relay, the backup job, the audit |
| **RP_BACKUP** | a **copy** of RP. It never accepts a write that did not come from RP, and it is never read by the settlement path | the backup job only (reads RP) | the restore only |
| **OUTBOX** | the match authority's **durable intent queue**. A queue, not an authority: it holds what a finished match owes the ledger, and it never computes or stores RP of its own | the match authority (enqueue), the relay (ack) | the relay |

Three properties make the protocol safe, and E7 measures all three:

1. **An idempotency key per unit of settlement** — `PRIMARY KEY (match_id, seat)` in the ledger,
   `match_id` in `settlement`, `UNIQUE (match_id)` in the outbox.
2. **Absolute rows, not deltas to apply** — every ledger row stores the resulting `rp`, not an
   increment. A replay therefore cannot compound, whatever the retry policy is.
3. **A restore point** — RP publishes its maximum `outbox_seq`; after a restore, exactly the
   outbox rows above that point may be replayed (§9).

The load is the **real** settlement stack, not a mock: `finalRpDelta` → `resolveRanked`, the
production formulas for placement, opponent strength and the RD29 one-tier band.

## 2. Method

### 2.1 The workload

One **60-match FULL/NORMAL battery** of settlement rows, produced by the Kotlin engine **inside
`workerd`** (the same artifact the runtime would run), with RP carried seat to seat per uid — so
the stream is a sequence of dependent writes, not 240 independent awards.

| Property | Value |
|---|---|
| Matches / ledger rows / total RP delta | 60 / 240 / 5,008 |
| JVM fingerprint | `cbfe5a72` |
| workerd fingerprint | `cbfe5a72` |
| Documents byte-identical | **yes** (the same gate E1–E3 use) |

The JVM leg is re-checked by `SettlementStreamTest` (8 tests, 0 failures, 0 skipped) and the
workerd leg by the driver; the driver **refuses to run** if the two fingerprints differ.

### 2.2 The harness

| Piece | What it is |
|---|---|
| `wrangler dev --local` | a local D1 (Miniflare/SQLite), three bindings (`RP`, `RP_BACKUP`, `OUTBOX`), `compatibility_date = 2026-10-29` |
| Per-scenario state | each scenario gets its own `--persist-to` directory, so no scenario can see another's rows |
| The kill | `taskkill /pid <pid> /T /F` — the process tree dies mid-flight, with no graceful shutdown |
| The fault | writes to a chosen binding are rejected with a modelled quota error (`D1_QUOTA_EXCEEDED`); **reads are never affected** |
| The row accounting | D1's own `meta.rows_written`, not a hand count |

Node.js is used by explicit path only (Gradle's portable Node 22.14.0), with built-in modules
and the pinned local wrangler — no installs, no PATH or admin changes, no network beyond
`127.0.0.1` (`docs/engineering/NODEJS_SAFE_USAGE.md`).

### 2.3 The matrix

| ID | Scenario | Criterion |
|---|---|---|
| S1 | kill **before** the relay | the replay drains everything; nothing lost |
| S2 | kill **after** the ledger write, before the outbox ack | the replay dedupes; nothing lost |
| S3 | primary wiped, restored from the backup, gap replayed | the restore point closes the gap |
| S3-control | the same, but the replay trusts the acked flags | **negative control** — the harness must SEE the loss |
| S4 | the stale backup restored, then a blind full replay, twice | no double award; the second replay is a no-op |
| S5n | the same 5 settlements re-applied under new match ids | **negative control** — the harness must SEE a double award |
| S6 | the write-failure window (a quota-exhaustion analogue) | after the writes come back: 0 lost, 0 duplicates |
| **S6b** | **both** stores reject writes, **and the process restarts** in that window | report what holds the intent, what is pending, what is lost if the caller is gone too, and whether a surviving caller lands it exactly once |
| S7 | the outbox wiped **with** the primary | the exact loss window, measured |
| S8 | recompute replay from the restored state | every recomputed row equals the original |
| S9 | two writers settling the same match | the same payload dedupes; a different payload alarms |

## 3. The fault matrix (S1–S4) and the two controls

All verdicts are against the same 60-match battery: **240 ledger rows, 5,008 RP delta**.

| ID | Injected fault | Outcome | Rows lost | Rows duplicated |
|---|---|---|---|---|
| S1 | kill with 20 settlements applied and **40 intents still pending** in the queue | the restart replays the 40 pending intents | **0** | **0** |
| S2 | kill after the ledger write of match 31, before its ack | the restart replays 30 intents; 29 apply, **1 dedupes** | **0** | **0** |
| S3 | primary wiped, restored from a copy stale by 5 matches | the restore point replays exactly the 5 missing matches | **0** | **0** |
| S3-control | the same, but the replay trusts the acked flags | **the harness sees the loss** | **20 rows / 476 delta** | 0 |
| S4 | the stale backup restored, then a blind replay, then another | 5 apply, 55 dedupe; the second replay applies 0 | **0** | **0** |
| S5n | the idempotency key is lost (same 5 settlements, new ids) | **the harness sees the double award** | 0 | **+375 delta** |

Two negative controls matter as much as the passes: they prove the harness can detect both
failure modes, so the zeros above are findings and not a blind spot.

**Timings [M]** (this machine, `wrangler dev --local`):

| Quantity | Value |
|---|---|
| Backup job (5 settlements + 20 ledger rows copied) | 24–30 ms over 11 runs |
| Spacing between two backups | 157–182 ms over 10 gaps |
| Time from the last backup to the primary wipe | 252 ms |
| S3: wipe → restored **and verified** | **194 ms** (restore + replay + audit) |
| S1: kill → recovered and verified | 3,857 ms, of which **3,008 ms is the cold `wrangler dev` boot** — the data path is ≈ 850 ms |
| S2: kill → recovered and verified | 3,942 ms (the same cold-boot share) |
| Kill → port down | 290–373 ms |

The boot time is a property of the local dev server, not of the protocol; the protocol's own
recovery cost is the **≈ 850 ms** replay path and the **194 ms** restore path.

## 4. S6 — the write-failure window (a quota-exhaustion analogue)

Reads keep working throughout; only writes are rejected. The two phases separate the two stores'
failure modes.

### Phase A — the OUTBOX rejects writes

| | |
|---|---|
| **Accepted** | 21 settlements in RP (84 rows, 1,597 delta) — the ledger kept the settlements it had already taken |
| **Pending** | 5 intents in the queue, unrelayed |
| **Lost at the stores** | **0** |
| **Not durable anywhere** | **match 26** — 4 rows, **+72 delta** — the enqueue was rejected, so **no store holds it** |

The counterfactual is the finding: **if the match authority drops the match when the enqueue is
rejected, that settlement is lost** — no store holds it and the ledger never learns about it.
So the requirement is on the *match side*: **the finished match's settlement must be retained
until the queue accepts it.** The queue is the durability boundary; anything upstream of it is
the authority's memory.

Phase A also exposes the cross-database seam under a quota error: the ledger write for match 21
**succeeded** and only its ack was rejected, so the store holds an applied-but-unacked settlement.
That is the same state S2 produces by killing the process, and it is benign — the replay
dedupes it (measured: `duplicates: 1`).

### Phase B — the RP rejects writes, the OUTBOX keeps accepting

| | |
|---|---|
| **Accepted** | 21 settlements (unchanged — the ledger did not move) |
| **Pending** | **7 intents durable in the queue** (the retained one re-submitted, plus a new one) |
| **Lost at the stores** | **0** |

The queue is the survivor: the intent is durable, the ledger write is simply not done yet.

### The recovery

Writes restored → the replay processed 7 intents: **6 applied, 1 deduped**, +462 delta. Final
state: **27 settlements, 0 lost, 0 duplicated, 0 alarms.** The match-26 intent, which was
retained by the authority through the failure, landed exactly once.

**What S6 establishes:** a write failure is **not** a settlement failure — provided the intent is
retained. The two places where it *can* become one are (a) the authority dropping a rejected
intent, and (b) the intent queue sharing a failure domain with the ledger (§5).

### S6b — both stores reject writes, and the process restarts in that window

Phase A rejects the OUTBOX write while the RP keeps accepting; Phase B rejects the RP write while
the OUTBOX keeps accepting. **S6b rejects both at once** and then kills and restarts the process,
which is the window the owner asked about: a match finishes while *every* durable store refuses
the write.

**Where the settlement intent lives in that window — the exact answer.** In S6b the enqueue of
match 21 is rejected, so the intent never reaches the OUTBOX; the OUTBOX therefore holds **no
pending row for it** (measured: `outboxRows: 20, unrelayed: 0` — every one of the 20 rows is an
already-acked intent, and match 21 is not among them). The RP never learns about it either
(measured: `rowsForTheRejectedMatch: 0`). So:

| | |
|---|---|
| **In the OUTBOX** | **nothing** — the write was rejected, so the row does not exist |
| **In the RP ledger** | **nothing** — the relay never received an intent to relay |
| **In any store** | **nothing** (`durableAnywhere: false`) |
| **Where it actually is** | **in the memory of whoever called the enqueue** — in the product, the match authority (the match DO) or the client |

**That holder is an UNRESOLVED DESIGN CHOICE.** The architecture in the plan does not name one for
this case: the plan's outbox row says *"Client queues result, process dies, outbox replays on next
launch"* (`NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` line 826) — which assumes the *outbox write
succeeded*. It says nothing about the case where the outbox write itself is refused, and this
report does not invent a holder. E7b cannot decide it either: it is a design question about the
match authority, not a measurement.

**What is pending, and what is lost if the process restarts in that window.**

| Measured after the restart | Value |
|---|---|
| Settlements in the RP | 20 (unchanged) |
| Rows in the OUTBOX | 20 (unchanged, all acked) |
| **Unrelayed (pending) intents** | **0** |
| **Rows in the ledger for the rejected match** | **0** |

The restart clears the injected fault, but it **cannot recover an intent no store ever accepted**.
So the loss window is entirely decided by whether the *caller* survives:

- **The caller is gone too (the counterfactual).** Nobody re-posts the intent, so the settlement
  never reaches the ledger: **1 settlement / 4 ledger rows / +73 RP delta lost**, silently. No
  store holds it, no alarm fires, no queue can replay it.
- **The caller survives.** It re-posts the intent, the enqueue accepts it (`enqueued: 1`), the
  replay applies it **exactly once** (`applied: 1, duplicates: 0, +73 delta`), the final state is
  21 settlements / 84 rows / 1,597 delta — the verdict **passes** with 0 lost and 0 duplicated.

**The ruling this is an instance of.** The owner's 2026-10-21 ruling §D ("Post-match failure
handling") requires, in order: *"persist the finalized result durably before any external write;
retry result/RP delivery safely; **keep the result pending until persistence is confirmed**; use
stable idempotency ids; show a clear status/error when not confirmed; never claim saved without
reliable confirmation."* S6b is the measurement of clause 3: **the "pending" state is real, it
lives in the caller, and it is the only thing standing between a refused write and a lost
settlement.** The measured cost of *not* honouring it is 1 settlement / 4 rows / +73 delta per
rejected enqueue.

**What S6b does not settle:** *who* holds the pending intent, and *for how long*. That is
D-E7-6 (§15) — a design decision for the owner, not a measurement.

## 5. S7 — the outbox lost with the primary

The primary and the queue are wiped **together**, then the primary is restored from the stale
backup. The queue is empty, so there is nothing to replay.

| | |
|---|---|
| Restored | 55 matches (`m-FULL-NORMAL-1` … `m-FULL-NORMAL-55`), 220 ledger rows |
| **Lost** | **5 settlements** (`m-FULL-NORMAL-56` … `m-FULL-NORMAL-60`) = **20 ledger rows** = **+476 RP delta** |
| Replay source | **none** — the intent queue went down with the primary |

The restore itself is faithful: the restored content is exactly the backup's content. The loss is
the window the backup never held, with no queue left to close it.

**The requirement this implies:** the intent queue **must not share the failure domain of the RP
primary's data**, or the intent must be re-derivable from somewhere that does. Two ways to satisfy
it, and E7 measures both:

- keep the queue outside the primary's failure domain (a separate store, region or
  availability domain) — the queue then survives a primary loss, which is what S3 exercises;
- make the intent **re-derivable** from a durable source that survives — which is what S8
  measures.

**The outbox may not need a separate provider at all. [u] — UNPROVEN, and E9 must test it.** The
two bullets above are not the only way to satisfy the requirement. If the settlement intent can be
**re-derived** from the match's own action log — the converged match document — then the queue is
not a durability boundary that needs its own failure domain, and it may not need to be a separate
provider from the RP primary at all. That is exactly the shape of the project's own
**correct-and-settle** design: settlement **re-runs the project's own rules engine over the
converged match document** to recompute the authoritative placement and scores (ADR 0002,
"correct-and-settle (RD12) must re-run the project's own rules engine over the converged match
document"; the plan's E3.3 row is worded the same way: *"Settlement re-runs the engine and folds
RP"*). If the action log survives the loss, the intent is recomputable rather than queued.

**This paragraph records an option; it does not choose it, and it does not claim the option
works.** S8 measures the *recompute* — that a recomputed settlement equals the original one
(0 differences across 9 fields) — but S8 recomputes from a **restored ledger state with the
missing matches' actions supplied by the harness**. It does **not** measure the harder half of the
option: that the action log itself survives whatever took the primary down, and that a
re-derivation is *complete* (no action missing from the log, no ordering gap). Until **E9**
("recovery at every interruption boundary") exercises an interrupted match end to end — action log
durability, log completeness, and re-derivation from the log alone — the claim **"the outbox does
not need a separate provider"** stays **[u] unknown**. It is a candidate answer to D-E7-2, not an
answer to it.

Note what this says about the current plan: the owner's 2026-10-29 ruling F already flags that
"the plan's 'backup' is today a backup/restore discipline (E7/G4) and **no independent,
quota-isolated secondary-server architecture exists yet**". **S7 quantifies what that gap costs
when it is not filled: 5 settlements / 20 rows / 476 delta per backup interval, for every
interval that is not covered.**

## 6. S8 — recompute replay

After the S7 restore, the missing settlements are **recomputed** by the engine from the state the
restored ledger actually holds — not copied from the original batch.

| | |
|---|---|
| Restored state (per uid) | `u1 2070, u2 2121, u3 2205, u4 2356` |
| Recomputed | seeds 56–60, in order, from that state (fingerprint `98f22704`) |
| Rows compared | **20** |
| Fields compared per row | `seat, uid, placement, outcome, delta, previousRp, rp, ladderIndex, movement` |
| **Differences** | **0** |
| After applying the recomputed rows | 60 settlements, 240 rows, 5,008 delta, **0 lost, 0 duplicated** |

The players who appear in several of the missing matches are covered: all four uids appear in
every match, and each uid's recomputed `previousRp` chains from its restored value through all
five missing matches — a mismatch in any seat of any match would have surfaced as a field
difference.

The recompute is the same engine code in both runtimes. The JVM leg proves it independently
(`recomputingTheTailFromTheRestoredStateReproducesTheOriginalRows`), and a control proves the
comparison is not vacuous: recomputing the same tail from the **wrong** base (`E7_STARTING_RP`)
**does** differ (`recomputingFromTheWrongBaseDiffers`). The JVM and workerd streams are
byte-identical, and each runtime's recompute equals its own original, so all four agree.

**This closes the S7 gap on one condition:** the *match documents* (the settled results) must
survive the loss. If the queue and the match log are lost together, there is nothing to
recompute from either.

## 7. S9 — dedupe with a payload check, and a split brain

The key alone is not enough. A key conflict must be decided by **comparing the payload**: the
same payload is a benign replay, a different payload is two writers.

| Case | Outcome |
|---|---|
| **Same payload** (10 intents replayed) | `duplicates: 10`, **0 alarms**, HTTP 200 — the benign dedupe |
| **The rogue writer second** (the real writer already settled the match) | the rogue is **rejected by the key** (`inserted: false, reason: "UNIQUE"`) and the stored payload is returned for comparison; **0 alarms** |
| **The rogue writer first** (a second writer bypasses the queue and writes the ledger directly) | the relay detects the different payload → **`PAYLOAD_MISMATCH` alarm, HTTP 409, the intent is NOT acked** and stays pending (49 unrelayed) |

The conflict is **visible, not silently ignored**. The alarm carries both payloads (stored and
incoming) and the match id, so an operator can see exactly which writer disagreed.

**What the protocol does — and does not do — to guarantee exactly one authority during a
restore.** The honest answer:

- **It guarantees** that a double-apply is **impossible** (the key) and that a *divergent*
  double-apply is **visible** (the payload check) — the 409 and the alarm, not a silent
  overwrite.
- **It does not elect an authority.** Exactly one writer is a **deployment** property (one relay
  process, one store authority); the payload check is the *detective* control behind it, not the
  preventive one. A rogue writer that reaches the ledger directly does damage the protocol cannot
  undo without a restore — the alarm tells the operator it happened.
- **During a restore** the authority is the **RP primary**: the backup is a read-only copy, the
  outbox is an intent queue that never writes RP on its own, the restore point (max
  `outbox_seq`) defines exactly which intents may be replayed onto the restored state, and the
  replay is a single-writer operation over that set.

## 8. Backup coverage — 91.67% is a result of the chosen cadence

**Measured:** with a backup after every **5** matches over a **60**-match battery, the copy holds
**55 of 60** settlements at the moment of the loss → **91.67% coverage**, stale by **5 matches /
20 rows / 476 delta**.

**This is not a natural measurement.** Coverage is a function of the *cadence relative to the
volume*, not of the backup mechanism:

> coverage = 1 − N/M, where **N** is the number of settlements between the last backup and the
> loss, and **M** is the number of settlements in that window.

At this battery (M = 60) the cadence N = 5 gives 91.67%; the same cadence over 40 matches would
give 87.5% — **below the 90% the plan asks for**. The mechanism was identical in both cases.
**The ≥90% answer is a property of the chosen cadence and the observed volume, not a property of
the protocol.**

The rule that follows: **to hold ≥90%, the stale window must be at most 10% of the settlements in
it.**

| Cadence (N) | 60-match battery | 150 matches/day |
|---|---|---|
| every 5 matches | 91.67% ✅ | 96.67% ✅ |
| every 10 matches | 83.3% ❌ | 93.3% ✅ |
| every 15 matches | 75% ❌ | 90% ✅ (exactly at the line) |
| every match | 98.3% ✅ | 99.3% ✅ |

The cadence also drives the backup's write cost (§10): backing up every match costs 12 rows per
settlement in steady state, every 5 matches costs 10.4 (§8.1 measures both directly).

### 8.1 The measured cadence table — 1, 5 and 10 settlements **[M]**

This is the measurement the owner's step-4 brief asked for. All three cadences were **run**, not
modelled: each one enqueued the same 60-match battery, relayed it, ran the backup on its own
cadence, then wiped the primary, restored from the copy, replayed the gap and verified the
result. **All three recovered with 0 lost rows and 0 duplicated rows** (60 settlements, 240
ledger rows, 5,008 delta restored).

**Does the backup copy the whole table or only the new rows?** **Only the new rows** — it is
**incremental**. Each run selects the settlements **above its own watermark** (`outbox_seq >
watermark`) and copies only those and their ledger rows, then advances the watermark. It never
re-copies what it already holds. The consequence is that a run's cost **grows with the interval it
covers**, and the total per match falls as the cadence lengthens.

| Cadence (N) | Runs over the battery | **Backup job rows per run** (first / later runs) | Matches copied per run | **Backup rows per match** (steady state) | **Coverage at a fault** (M = 60) | **Whole protocol, rows/settlement** (steady state) |
|---|---|---|---|---|---|---|
| **every 1 match** | 59 | **14** / **12** | 1 | **12.0** | N = 1 → **98.33%** ✅ | **27.00** |
| **every 5 matches** | 11 | **54** / **52** | 5 | **10.4** | N = 5 → **91.67%** ✅ | **25.40** |
| **every 10 matches** | 5 | **104** / **102** | 10 | **10.2** | N = 10 → **83.33%** ❌ | **25.20** |

**How to read the two "rows per run" numbers.** The **first** run of each battery is 2 rows
dearer than every later run, because it **creates** the two watermark rows (`watermark`,
`last_backup_ms`) where every later run only **updates** them. The steady state is therefore the
number that governs a running system, and it is the one carried into §10.

**The measured rows behind the table [M]** — for every cadence, the same three components: RP
**600** rows (10 per settlement) and OUTBOX **240** rows (enqueue + ack + delete = 5 per
settlement) are identical across all three; RP_BACKUP differs, because the backup runs a
different number of times: **710** rows total at cadence 1 (one run of 14 + 58 runs of 12),
**574** at cadence 5 (54 + 10 × 52) and **512** at cadence 10 (104 + 4 × 102). Dividing each by the
60 settlements gives the battery averages **11.83 / 9.57 / 8.53** rows per match, against the
steady-state **12.0 / 10.4 / 10.2** in the table — the difference is the one-off first run, which
creates the watermark rows instead of updating them.

**The formula, applied.** `coverage = 1 − N/M`, with **M = 60** settlements in the battery and
**N** the settlements since the last backup:

- N = 1 → 1 − 1/60 = **98.33%**
- N = 5 → 1 − 5/60 = **91.67%**
- N = 10 → 1 − 10/60 = **83.33%** — **below the 90% the plan asks for**

**The trade the table shows.** Longer intervals buy cheaper backups (12.0 → 10.2 rows per match,
and 27.00 → 25.20 rows per settlement for the whole protocol) but they **cost coverage** at the
same volume. On this battery only cadences 1 and 5 clear 90%; cadence 10 does not, and no amount
of protocol work changes that — it is arithmetic on N and M. **Which cadence production uses is
D-E7-1 (§15) and this report does not choose it.**

> **POINTER NOTE (2026-10-30 — D-E7-1 is DECIDED).** The owner chose **cadence N = 1: the backup
> runs after every settlement** (`docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md`
> §2). The table above is unchanged and remains the measurement; the *chosen* row is the **first**
> one: **98.33% coverage**, **12.0** backup rows per match, **27.00** rows per settlement for the
> whole protocol. The cadence-5 row (91.67% / 10.4 / 25.40) stays in this report as the
> measured alternative, not as the policy.

## 9. The restore-point rule

> **The rule.** RP publishes its maximum `outbox_seq` as the **restore point**. After a disaster
> restore, **every outbox row with `seq > restore_point` is replayed, regardless of its acked
> flag.** The acked flag is not consulted above the restore point, and is not trusted below it.

**Why.** An ack written *after* the restored point belongs to data that no longer exists. Trusting
it would silently skip a settlement that the restored ledger does not contain.

**The evidence:**

| | Restore point | Replayed | Result |
|---|---|---|---|
| **S3** — restore-point replay | 55 | the 5 rows above it | **0 lost, 0 duplicated** |
| **S3-control** — naive, acked-flags only | 55 | **0 rows** | **20 rows / 476 delta LOST** — the negative control |
| **S4** — blind full replay, twice | 55 | all 60 | 5 applied, 55 deduped; the **second replay applies 0** |

The control is the point: the rule is not a formality, and the loss it prevents is exactly the
window §5 measures. A blind replay is *also* safe (S4) — the key makes it so — but the
restore-point rule is what makes the replay **bounded and minimal** instead of re-scanning the
whole queue.

## 10. Rows written per settlement — the protocol figure

### 10.1 The measured per-settlement table

Measured from D1's own `meta.rows_written` (**[M]** on the local D1):

| Step | RP | RP_BACKUP | OUTBOX |
|---|---|---|---|
| enqueue one intent | — | — | **3** |
| relay one settlement (1 match row + 4 ledger rows) + its ack | **10** | — | **1** |
| back up one settlement — **first** run (1 + 4 rows + the 2 watermark rows **created**) | — | **14** | — |
| back up one settlement — **steady state** (the 2 watermark rows only **updated**) | — | **12** | — |
| delete one settled intent from the queue (cleanup) | — | — | **1** |
| **per settlement, backup every 5 matches** | **10** | **10.4** | **5** |
| **per settlement, total** | | | **25.4** |
| **per settlement, total, backing up every match** | 10 | 12 | 5 → **27** |

> **POINTER NOTE (2026-10-30 — the figure the model carries).** The owner ruled the cadence to
> **every settlement** (D-E7-1, §8.1), so **27 rows/settlement is the figure the capacity model
> carries** and the 25.4 row above is the *measured cadence-5 alternative*, kept for the record.
> The table is left verbatim; the old → new log is in §10.4.

**Why the E5 figure of 5 rows/match understates it.** D1's `rows_written` counts **table rows and
index rows**. The E5 figure counted the **5 table rows** of the settlement (1 match-result row +
4 RP ledger rows) and nothing else: it did not count the index rows D1 bills for, the intent
queue's insert/ack/delete, or the backup copy. So the **5 was never a protocol figure** — it was
the ledger's table-row count.

**The effect on the D1 daily figure** (limit: **100,000 rows written/day**, D1 Free, per
database):

| Matches/day | Old "5 rows" figure | **Measured protocol** (cadence 5) | % of the 100,000/day limit |
|---|---|---|---|
| 100 | 500 rows — 0.5% | **2,540 rows — 2.54%** | 2.54% |
| 125 | 625 rows — 0.625% | **3,175 rows — 3.175%** | 3.175% |
| 150 | 750 rows — 0.75% | **3,810 rows — 3.81%** | 3.81% |

(The percentages are exact — 25.4 × matches/day ÷ 100,000 — and are not rounded.)

> **POINTER NOTE (2026-10-30 — at the chosen cadence the row is 4,050, not 3,810).** The table
> above is the **cadence-5** measurement, left verbatim. At the ruled cadence (**every
> settlement**, 27 rows/settlement) the same three days read **2,700 rows — 2.70%** at 100
> matches/day, **3,375 — 3.375%** at 125 and **4,050 — 4.05%** at 150. The axis is **still far
> from binding**; only the number moves.

### 10.2 The settlement ceiling, at both bases

The protocol's per-settlement cost is **25.4 rows**, so the number of settlements a single database
can absorb in a day is:

| Base | Rows available | **Ceiling** |
|---|---|---|
| **the 90% guardrail** (the plan's operating target) | 90,000 rows/day ÷ 25.4 | **floor(90,000 / 25.4) = 3,543 matches/day** |
| **100%** (the hard D1 Free limit) | 100,000 rows/day ÷ 25.4 | **floor(100,000 / 25.4) = 3,937 matches/day** |

**Where 25.4 comes from, and what it does not prove.** 25.4 is the figure the **local D1's own
`meta.rows_written` counter** reported for the whole protocol at a 5-match cadence — measured on
this machine, against Miniflare's local SQLite, in the 60-match battery (§8.1, §10.1). **Cloudflare's
billing equivalence is UNVERIFIED [x].** The local counter demonstrably counts **index rows as well
as table rows** (§10.1), which is the mechanism the ×2 allowance in the E5/E6 models was
precautioning about — but **whether Cloudflare's metered "rows written" is counted the same way is
a real-account question that only a throwaway Free account can answer.** Until it is answered,
3,543 and 3,937 are **local-counter** ceilings, not Cloudflare ceilings, and they move if the
billing definition differs.

**The conclusion does not change:** the settlement axis is **not** the binding meter — E6's
binding meter is DO rows written at 1.6–2.3× headroom, while the settlement axis sits at
**≤ 3.81%** of its limit, and the E5 figure understates it by ≈ 5× without changing which meter
binds. What changes is the **number** the capacity model must carry, and the implied ceiling on
this axis (3,543 matches/day at the 90% guardrail, 3,937 at the hard limit — not 18,000).

> **POINTER NOTE (2026-10-30 — the ceiling at the ruled cadence is 3,333 / 3,703).** The ceiling
> above is computed on the **cadence-5** figure (25.4) and is left verbatim. The owner ruled the
> cadence to **every settlement** (D-E7-1), so the **figure the model now carries is 27
> rows/settlement**, and the ceiling on this axis becomes:
>
> | Base | Rows available | **Ceiling at 27 rows/settlement** |
> |---|---|---|
> | **the 90% guardrail** | 90,000 rows/day ÷ 27 | **floor(90,000 / 27) = 3,333 matches/day** |
> | **100%** (the hard D1 Free limit) | 100,000 rows/day ÷ 27 | **floor(100,000 / 27) = 3,703 matches/day** |
>
> The **caveat below is unchanged and still binds**: these are **local-counter** ceilings, not
> Cloudflare ceilings — **Cloudflare's billing equivalence is UNVERIFIED [x]**.

### 10.3 Two readings of the limit [m] — and the plan does not settle which one applies

- **three separate D1 databases** (RP, RP_BACKUP, OUTBOX): each has its own 100,000/day limit, so
  the binding figure is the **largest per-database** one (OUTBOX 500 rows/day at 150 matches/day),
  not the sum;
- **one D1 database with three tables**: the three figures **add up** against the single limit —
  3,810 rows/day at 150 matches/day, 3.81%.

Either way the axis is far from binding; the reading matters only for the ceiling, not for the
verdict.

### 10.4 Correction log — old → new

No earlier figure was silently rewritten. This is the log.

| Figure | Old | New | Why |
|---|---|---|---|
| **Rows per settlement, backing up every match** | **29** | **27** | The old 29 used the **first** backup run's cost (14 rows), which includes **creating** the two watermark rows. Every later run only **updates** them, so the steady-state run costs **12** → 10 + 12 + 5 = **27**. The **25.4** headline at cadence 5 was already the steady-state figure (52/5 = 10.4) and is **unchanged**. Measured directly in §8.1 (cadence 1: run 1 = 14, runs 2–59 = 12). |
| **Backup rows per match, cadence 1** | "14" (§8's closing line) | **12.0** steady state | Same cause. The old sentence quoted the first-run cost. |
| **Backup job rows per run** | not measured per cadence | **14 / 12 · 54 / 52 · 104 / 102** (cadence 1 / 5 / 10) | Now measured, with the first-run vs steady-state split stated (§8.1). |
| **The ×2 rows allowance** (E5/E6) | "conservative ×2, unexplained" | **explained**: it is caused by a **second index** (a composite `PRIMARY KEY` on an ordinary rowid table, or an explicit secondary index, or a `UNIQUE` constraint) | Measured in E7b (`Stage0_E7b_DO_Rows_Report.md`): V1 = ×1, V2 = ×2, V3 = ×1, V4 = ×2, V5 = ×2, V6 = ×1. Pointers added to the E5/E6 reports; the earlier figures are left in place with the pointer. |
| **The figure the capacity model carries** | **25.4** rows/settlement (cadence 5 — a candidate) | **27** rows/settlement (cadence 1 — the owner's decision) | **D-E7-1 decided 2026-10-30**: the backup runs after **every** settlement. §10.1, §10.2 and §8.1 carry the pointer; the old bodies stay verbatim. |
| **The settlement-axis ceiling** | 3,543 matches/day at 90% · 3,937 at 100% (on 25.4) | **3,333** at 90% · **3,703** at 100% (on 27) | Same ruling. `floor(90,000 / 27)` and `floor(100,000 / 27)`. Still **local-counter** figures — Cloudflare's billing equivalence stays **[x] unverified**. |

## 11. The 60 s all-humans-gone rule vs the quota/outage exemption

These are **different rules and must not be merged.** Neither is changed by E7; the distinction
is recorded here because S6 is the quota case.

| | The 60 s rule | The quota/outage exemption |
|---|---|---|
| Trigger | **the humans** left (all of them disconnected) | **the platform** refused the write (server or quota failure) |
| Source | Q27c / T-15: *"All humans disconnected: wait 60 s; if nobody returns, cancel and apply the same outcome"* | Q27(3): *"Server/quota failure — never charged"* |
| Outcome | the match is **cancelled** and the abandonment outcome applies (all Koz; RANKED deducts the engine's last-place RP loss) | **no charge at all** — the match is not the players' fault |
| What E7 measures | nothing (no human-left path is exercised here) | **S6**: the intent is retained, the settlement is applied later, exactly once, with **no charge** |

**The interaction to keep straight:** in the quota case the match **must not** be silently
converted into an abandoned match. S6 shows why: the store already holds the intent, and the
settlement is applied later with 0 duplicates and 0 losses. If a write rejection instead caused
the match to be cancelled as "everyone left", the players would be charged (all Koz + an RP
loss) for a platform failure — which Q27(3) forbids — **and** the settlement would be lost,
which §4 shows is not necessary.

**Pending owner decision D-E7-4** (§15) confirms this reading; E7 changes no ruling.

## 12. What E7 does NOT prove

1. **Nothing about Cloudflare D1's behaviour.** The store here is Miniflare's local SQLite.
   Batch atomicity, `meta.rows_written` semantics, the rows-per-index accounting, the Free-plan
   limits, the Free plan's enforcement of them, D1's behaviour under a real quota event, and
   D1's latency are **[x] — a real Cloudflare account is required.** The 25.4 rows/settlement is
   a local measurement; the billing definition is Cloudflare's, and it may differ. The same
   caveat applies to E7b's rows-per-write figures (`Stage0_E7b_DO_Rows_Report.md`): **a local
   count is not proof of what Cloudflare bills.**
2. **Nothing about quota-driven failover.** The fault was injected in-process; there was no 429,
   no account-level metering, no Free-plan enforcement, and no second server. The 90% guardrail
   and the "new matches move to the backup server" behaviour (rulings E and F, Q13) are **not**
   measured here, and the guardrail is E6/E12 territory.
3. **Nothing about the independent secondary server.** It does not exist. The backup here is a
   copy in the same local process on the same machine. S7's requirement — the queue must not share
   the primary's failure domain — is a requirement **on an architecture that has not been built**,
   and E7 is not evidence that it will be.
4. **E8, E9, E10, E12, E13 are not run.** D1's platform-level atomicity (E8), recovery at every
   interruption boundary (E9), retention and cleanup cost (E10), hibernation and duration (E12)
   and the account-deletion policy (E13) all remain open. E7 does not substitute for E9: it
   tested recovery from **store-level** faults, not from an interrupted match. **S6b tested a
   store-level restart, not an interrupted match** — the action-log re-derivation option in §5
   therefore stays **[u]** until E9 runs.
5. **Not a settlement-correctness proof for the product.** The battery is one seed range, one
   game type, one scoring mode, four bot seats. The RD7 demotion shield and the Highest-Rank
   fold are settlement-layer state that never reaches the RP ledger row E7 persists; they are
   covered by `SettlementRpEmulatorTest`, not here.
6. **The exactly-once property is scoped to the key.** It holds for the `(match_id, seat)` key
   with absolute RP rows. It says nothing about a settlement whose **match id is itself wrong**,
   and it does not survive a rogue writer that bypasses the queue — §7 shows that case is
   *detected*, not prevented.
7. **Not a design decision about who holds a pending intent.** S6b measures what happens when
   every store refuses the write; it does **not** decide who keeps the intent alive. That is an
   **unresolved design choice** (D-E7-6), and this report does not invent one.
8. **O-06 stays open.** A local PASS on E7 is evidence for the ADR's table; it is not the
   decision, and it is not an approval of Cloudflare.

## 13. Artifacts

| Artifact | Where |
|---|---|
| The report | `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` (this file) |
| The DO rows experiment (E7b) | `docs/implementation/Stage0_E7b_DO_Rows_Report.md` |
| The settlement stream probe | `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/SettlementStreamProbe.kt` |
| The recompute entry point | `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/SettlementStreamProbe.kt` (`runSettlementStreamFromJson`) → `native/stage0/src/jsMain/kotlin/Stage0Exports.kt` (`runSettlementStreamFrom`) |
| The JVM proof (8 tests) | `native/stage0/src/jvmTest/kotlin/com/estemshan/stage0/SettlementStreamTest.kt` |
| The worker, the driver, the evidence | `D:/cache/stage0/e7/` (scratch, outside the repo): `worker.mjs`, `driver.mjs`, `results/e7-evidence-step4.json` |
| The E7b worker, driver, evidence | `D:/cache/stage0/e7b/` (scratch, outside the repo): `worker.mjs`, `driver.mjs`, `results/e7b-do-rows-evidence.json` |
| The earlier evidence — **kept** | `results/e7-evidence.json`, `results/e7-evidence-step2.json` (S1–S5n), `results/e7-evidence-step3.json` (S6–S9), `results/e7-evidence-run1.json`, `results/e7-evidence-run2.json` |

## 14. The findings

1. **The protocol holds under every fault tested.** Across **11 injected-fault scenarios** (plus
   **3 measured cadence runs**): **0 duplicate RP rows and 0 lost settlements**, while two
   negative controls show the harness detects both failure modes (20 rows / 476 delta lost;
   +375 delta double-awarded).
2. **The single RP authority is preserved.** RP_BACKUP only ever receives copies, and OUTBOX only
   ever receives intents. No scenario needed a second RP truth.
3. **The restore-point rule is what closes the gap** — and the control proves the loss it
   prevents (20 rows / 476 delta).
4. **91.67% coverage is a property of the cadence, not a measurement of the mechanism.** The
   mechanism's contribution is that the gap is always *bounded and known*; the 90% answer depends
   on N ≤ 0.1 × M (§8). **Measured at three cadences** (§8.1): 98.33% / 91.67% / 83.33% for
   N = 1 / 5 / 10 on a 60-match battery.
5. **A write failure is not a settlement failure — if the intent is retained** (S6). The two ways
   it becomes one: the authority dropping a rejected intent, and the queue sharing the primary's
   failure domain.
6. **Losing the queue with the primary costs exactly the backup interval**: 5 settlements / 20
   rows / 476 delta per uncovered interval (S7).
7. **The gap is closable without the queue**, if the match documents survive: the recompute
   reproduces all 20 missing rows with **0 differences** across 9 fields (S8). **Whether the
   intent can be re-derived from the action log alone — and therefore whether the outbox needs a
   separate provider — is [u] unproven and is E9's to test** (§5).
8. **A key conflict is decided by the payload, never ignored** — the same payload dedupes
   silently, a different payload alarms and is not acked (S9).
9. **The settlement write cost is 25.4 rows per match, not 5** — D1 bills index rows too, and the
   old figure excluded the queue and the backup copy entirely. The axis is still not binding
   (≤ 3.81% at 150 matches/day) but the capacity model must carry the new figure (§10). **The
   ceiling on this axis is 3,543 matches/day at the 90% guardrail and 3,937 at the hard limit**,
   both derived from the local counter (§10.2).
10. **The protocol makes a double-apply impossible and a divergent one visible; it does not
    elect an authority** (§7).
11. **When every store refuses the write, the intent exists only in the caller's memory**
    (S6b) — no store holds it, and after a restart the ledger holds **0** rows for that match.
    A surviving caller lands it **exactly once**; a caller that is also gone loses **1
    settlement / 4 rows / +73 delta** silently. The holder is an **unresolved design choice**
    (§4, D-E7-6).
12. **The ×2 rows allowance in the E5/E6 models is not a platform precaution — it is a schema
    choice** (E7b): a rowid table with a composite `PRIMARY KEY`, an explicit secondary index, or
    a `UNIQUE` constraint all cost **2 rows per write**; a plain rowid table, a `WITHOUT ROWID`
    table, and a single-row upsert all cost **1**. See `Stage0_E7b_DO_Rows_Report.md`.

## 15. Pending owner decisions — listed, not decided

| ID | The question | Why it needs the owner |
|---|---|---|
| **D-E7-1** | **DECIDED 2026-10-30 — the backup cadence is every settlement (N = 1).** | The coverage is 1 − N/M, so the cadence must be chosen against the observed volume (§8). E7 measured the mechanism, not the policy. §8.1 now measures cadences 1, 5 and 10 directly: 98.33% / 91.67% / 83.33% coverage at 12.0 / 10.4 / 10.2 backup rows per match. **The owner chose cadence 1** — `docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md` §2. **Closed.** |
| **D-E7-2** | **Where must the intent queue live** — a separate store/region, or re-derivable from the match log? | S7's requirement; the plan already flags that no quota-isolated secondary exists (ruling F). **§5 now records a third possibility: the outbox may not need a separate provider at all if the intent is re-derivable from the action log (the correct-and-settle design) — that is [u] unproven and E9 must test it.** |
| **D-E7-3** | **Which protocol figure does the capacity model carry** — 25.4 rows/match (cadence 5) or 27 (every match)? And are the three stores three D1 databases or one? | §10; it changes the ceiling on the settlement axis (3,543 matches/day at the 90% guardrail, 3,937 at the hard limit, not 18,000) and the reading of the limit. **The figure for "every match" is corrected from 29 to 27 (§10.4).** **Partly closed 2026-10-30:** the owner ruled the cadence to every settlement, so the model carries **27 rows/settlement** and the ceiling is **3,333 @ 90% / 3,703 @ 100%** (§10.2). **Still open: three D1 databases or one** (the limit's reading, §10.3) — and note the ceiling stays a **local-counter** figure until a real account confirms the billing equivalence **[x]**. |
| **D-E7-4** | **CONFIRMED 2026-10-30** — a quota/write failure is never converted into an abandoned match (no charge, intent retained, settlement applied later). | §11; the existing rulings (Q27(3), Q27c) already separate the two cases — E7 changes nothing. The owner's ruling of 2026-10-30 states it explicitly: **at 90% the system refuses new matches and never cancels a running one** (`docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md` §3). **Closed.** |
| **D-E7-5** | **Is the local E7 evidence enough to close the E7 row**, or is a real-Cloudflare E7 leg required first? | §12; O-06 stays open either way, and M3's "exactly-once and recovery evidence" spans E7 + E9. |
| **D-E7-6** | **Who holds a settlement intent while every durable store refuses the write** — the match authority, the client, or neither? And how long is it held? | §4 (S6b). The architecture does not name a holder for this case; E7b cannot decide it. It is the same requirement as the ruling *"keep the result pending until persistence is confirmed"* — the pending state is real, but its holder is not designed yet. |
| **D-E7-7** | **Which DO row layout** does the Ranked match DO use — and does the capacity model keep the ×2 allowance or drop it? | E7b measures the factor as a **schema choice** (§14.12): ×1 for a plain rowid table / `WITHOUT ROWID` / single-row upsert, ×2 for a composite `PRIMARY KEY` / secondary index / `UNIQUE`. **This is a local count, not proof of what Cloudflare bills** — the E12 add-on in `Stage0_E7b_DO_Rows_Report.md` lists what a throwaway Free account must confirm, **with the owner's approval**. |

## 16. Reproduction

```bash
# JVM leg — the stream and the recompute proof (8 tests)
cd native && ./gradlew :stage0:jvmTest --tests "com.estemshan.stage0.SettlementStreamTest"

# the local fault matrix (JVM↔workerd parity gate, S1–S9 + S6b, the cadence table, the row accounting)
node D:/cache/stage0/e7/driver.mjs

# the DO rows-per-write experiment (V1–V9)
node D:/cache/stage0/e7b/driver.mjs
```

The driver refuses to run if the JVM and workerd settlement streams differ, and writes
`results/e7-evidence-step4.json` with every raw number behind this report. The E7b driver writes
`results/e7b-do-rows-evidence.json`.

## 17. Owner summary (Egyptian Arabic)

> The corrected summary the owner asked for. The sentence that used to read *"فلو مكنتش النتيجة
> دي مقنعة"* said something unclear; it now says what the control scenarios actually prove.

```
خلاص E7 خطوة 4 اتعملت، وكمان عملت التجربة الجديدة E7b. كله محلي (D1 محلي + Durable Object
محلي)، من غير حساب Cloudflare ولا نشر ولا استهلاك كوتا.

النتيجة: في كل السيناريوهات (11 سيناريو فشل + 3 تجارب cadence) صفر تكرار RP وصفر تسوية
ضايعة. وده مش لأن الفحص ضعيف - لأ، لأننا حطينا سيناريوهين ضبط (control) بنجبر الفشل عن
قصد عشان نشوف إن الفحص نفسه بيكشف الفشل فعلا: واحد كشف ضياع 20 سطر و476 RP، والتاني كشف
منح RP مرتين لنفس المباراة. يعني الأصفار دي نتيجة حقيقية، مش فراغ في الفحص.

أهم رقم جديد في S6b: لو الـ D1 رفض الكتابة في كل المتاجر (RP والـ outbox مع بعض)،
النية بتتحط في ذاكرة اللي نادى الكتابة بس (السيرفر نفسه أو الكلاينت) - ومفيش
متجر خالص ماسكها. ده اختيار معماري مش متسوّى (unresolved design choice). وبعد ما
السيرفر يعمل ريستارت، الـ ledger بيفضل فيه صفر سطر للمباراة دي. لو اللي نادى
الكتابة كمان ضاع، الخسارة 1 تسوية / 4 سطور / +73 RP من غير أي إنذار. لو فضل
حي، بيعيد إرسال النية وبتتسجّل مرة واحدة بالظبط.

النقطة التانية (S7): يمكن مفيش داعي للـ outbox يبقى عند مزود منفصل خالص، لو النية
تقدر تتشتق تاني من سجل الأكشنز (correct-and-settle). ده **لسه مش مثبوت**، وE9 هي
اللي المفروض تختبره. قرار مش قرار.

الأرقام الجديدة (كلها مقاسة):
- الـ cadence: 1 / 5 / 10 مباريات ← التغطية 98.33% / 91.67% / 83.33%، وتكلفة
  الـ backup 12.0 / 10.4 / 10.2 سطر لكل مباراة. الـ backup بينسخ الصفوف الجديدة
  بس (مش الجدول كله)، وده اللي خلّى التكلفة تنزل مع طول الفترة.
- سقف التسويات: 3,543 مباراة في اليوم عند حد الـ 90%، و3,937 عند 100%. الرقم
  25.4 ده جاي من عدّاد D1 المحلي، ومفيش تأكيد إن Cloudflare بيحسب بنفس الطريقة.

تجربة E7b (الـ DO SQLite): ده فسّر الـ ×2 اللي كان مش مفهوم في نموذج السعة.
التصميمات اللي في سطر واحد per write (×1): جدول rowid عادي (V1)، وجدول
WITHOUT ROWID (V3)، وتحديث سطر واحد في المكان (V6). التصميمات اللي بتكلف سطرين
(×2): PRIMARY KEY مركّب على جدول rowid (V2)، أو index ثانوي (V4)، أو UNIQUE (V5).
يعني الـ ×2 ده مش احتياط من المنصة - ده اختيار في تصميم الجدول. وحجم الداتا
مش بيأثر على عدد الصفوف، بيأثر على الحجم بس.

لسه مجهول (محتاج حساب حقيقي بموافقة المالك): هل عدّاد Cloudflare بيساوي
العدّاد المحلي، تكلفة ctx.storage.put()/delete()، تكلفة setAlarm، قياس
WebSocket (20:1) وطلبات الـ Worker، ومدة الـ GB-s والhibernation.

Report: docs/implementation/Stage0_E7_Backup_Outbox_Report.md
Report: docs/implementation/Stage0_E7b_DO_Rows_Report.md
```

> **POINTER NOTE (2026-10-30, the owner's three rulings — see the one-page Arabic checklist in
> `docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md` §4).**
>
> ```
> قرارات المالك (2026-10-30):
> 1. تجهيز تجربة E12 على حساب مؤقت اتapproved - مفيش بيانات حقيقية، وكل خطوة لوحدها
>    بموافقتك. مفيش أي خطوة اتنفّذت لحد دلوقتي.
> 2. الـ backup cadence = بعد كل تسوية (مش 5 ولا 10). ده اختار النسبة 98.33% تغطية
>    بدل 91.67%، وكلفة 27 سطر لكل تسوية بدل 25.4، وسقف التسويات 3,333 مباراة في اليوم
>    عند حد الـ 90% (و3,703 عند 100%) بدل 3,543 / 3,937.
> 3. عند 90% من الكوتا: نرفض مباريات جديدة، ومانلغيش أي مباراة شغالة.
>
> يعني D-E7-1 اتقفل، وD-E7-4 اتأكد. لسه مفتوح: D-E7-2، D-E7-3 (نصها الثاني: تلات
> قواعد بيانات ولا واحدة)، D-E7-5، D-E7-6، D-E7-7. وO-06 لسه مفتوح.
> ```
