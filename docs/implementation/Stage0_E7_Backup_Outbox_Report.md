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

The cadence also drives the backup's write cost (§10): backing up every match costs 14 rows per
settlement, every 5 matches costs 10.4.

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

Measured from D1's own `meta.rows_written` (**[M]** on the local D1):

| Step | RP | RP_BACKUP | OUTBOX |
|---|---|---|---|
| enqueue one intent | — | — | **3** |
| relay one settlement (1 match row + 4 ledger rows) + its ack | **10** | — | **1** |
| back up one settlement (1 + 4 rows + 2 watermark rows) | — | **14** | — |
| delete one settled intent from the queue (cleanup) | — | — | **1** |
| **per settlement, backup every 5 matches** | **10** | **10.4** | **5** |
| **per settlement, total** | | | **25.4** |
| **per settlement, total, backing up every match** | 10 | 14 | 5 → **29** |

**Why the E5 figure of 5 rows/match understates it.** D1's `rows_written` counts **table rows and
index rows**. The E5 figure counted the **5 table rows** of the settlement (1 match-result row +
4 RP ledger rows) and nothing else: it did not count the index rows D1 bills for, the intent
queue's insert/ack/delete, or the backup copy. So the **5 was never a protocol figure** — it was
the ledger's table-row count.

**The effect on the D1 daily figure** (limit: **100,000 rows written/day**, D1 Free, per
database):

| Matches/day | Old "5 rows" figure | **Measured protocol** (cadence 5) | At 90% of the limit |
|---|---|---|---|
| 100 | 500 rows — 0.5% | **2,540 rows — 2.54%** | 3,543 matches/day |
| 125 | 625 rows — 0.625% | **3,175 rows — 3.175%** | 3,543 matches/day |
| 150 | 750 rows — 0.75% | **3,810 rows — 3.81%** | 3,543 matches/day |

(The percentages are exact — 25.4 × matches/day ÷ 100,000 — and are not rounded.)

**The conclusion does not change:** the settlement axis is **not** the binding meter — E6's
binding meter is DO rows written at 1.6–2.3× headroom, while the settlement axis sits at
**≤ 3.81%** of its limit, and the E5 figure understates it by ≈ 5× without changing which meter
binds. What changes is the **number** the capacity model must carry, and the implied ceiling on
this axis (3,543 matches/day at the 90% guardrail, not 18,000).

**Two readings of the limit [m]** — and the plan does not settle which one applies:

- **three separate D1 databases** (RP, RP_BACKUP, OUTBOX): each has its own 100,000/day limit, so
  the binding figure is the **largest per-database** one (OUTBOX 500 rows/day at 150 matches/day),
  not the sum;
- **one D1 database with three tables**: the three figures **add up** against the single limit —
  3,810 rows/day at 150 matches/day, 3.81%.

Either way the axis is far from binding; the reading matters only for the ceiling, not for the
verdict.

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
   a local measurement; the billing definition is Cloudflare's, and it may differ.
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
   tested recovery from **store-level** faults, not from an interrupted match.
5. **Not a settlement-correctness proof for the product.** The battery is one seed range, one
   game type, one scoring mode, four bot seats. The RD7 demotion shield and the Highest-Rank
   fold are settlement-layer state that never reaches the RP ledger row E7 persists; they are
   covered by `SettlementRpEmulatorTest`, not here.
6. **The exactly-once property is scoped to the key.** It holds for the `(match_id, seat)` key
   with absolute RP rows. It says nothing about a settlement whose **match id is itself wrong**,
   and it does not survive a rogue writer that bypasses the queue — §7 shows that case is
   *detected*, not prevented.
7. **O-06 stays open.** A local PASS on E7 is evidence for the ADR's table; it is not the
   decision, and it is not an approval of Cloudflare.

## 13. Artifacts

| Artifact | Where |
|---|---|
| The report | `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` (this file) |
| The settlement stream probe | `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/SettlementStreamProbe.kt` |
| The recompute entry point | `native/stage0/src/commonMain/kotlin/com/estemshan/stage0/SettlementStreamProbe.kt` (`runSettlementStreamFromJson`) → `native/stage0/src/jsMain/kotlin/Stage0Exports.kt` (`runSettlementStreamFrom`) |
| The JVM proof (8 tests) | `native/stage0/src/jvmTest/kotlin/com/estemshan/stage0/SettlementStreamTest.kt` |
| The worker, the driver, the evidence | `D:/cache/stage0/e7/` (scratch, outside the repo): `worker.mjs`, `driver.mjs`, `results/e7-evidence-step3.json` |
| The earlier evidence | `results/e7-evidence-step2.json` (S1–S5n), `results/e7-evidence-run1.json`, `results/e7-evidence-run2.json` |

## 14. The findings

1. **The protocol holds under every fault tested.** Across 10 scenarios: **0 duplicate RP rows
   and 0 lost settlements**, while two negative controls show the harness detects both failure
   modes (20 rows / 476 delta lost; +375 delta double-awarded).
2. **The single RP authority is preserved.** RP_BACKUP only ever receives copies, and OUTBOX only
   ever receives intents. No scenario needed a second RP truth.
3. **The restore-point rule is what closes the gap** — and the control proves the loss it
   prevents (20 rows / 476 delta).
4. **91.67% coverage is a property of the cadence, not a measurement of the mechanism.** The
   mechanism's contribution is that the gap is always *bounded and known*; the 90% answer depends
   on N ≤ 0.1 × M (§8).
5. **A write failure is not a settlement failure — if the intent is retained** (S6). The two ways
   it becomes one: the authority dropping a rejected intent, and the queue sharing the primary's
   failure domain.
6. **Losing the queue with the primary costs exactly the backup interval**: 5 settlements / 20
   rows / 476 delta per uncovered interval (S7).
7. **The gap is closable without the queue**, if the match documents survive: the recompute
   reproduces all 20 missing rows with **0 differences** across 9 fields (S8).
8. **A key conflict is decided by the payload, never ignored** — the same payload dedupes
   silently, a different payload alarms and is not acked (S9).
9. **The settlement write cost is 25.4 rows per match, not 5** — D1 bills index rows too, and the
   old figure excluded the queue and the backup copy entirely. The axis is still not binding
   (≤ 3.81% at 150 matches/day) but the capacity model must carry the new figure (§10).
10. **The protocol makes a double-apply impossible and a divergent one visible; it does not
    elect an authority** (§7).

## 15. Pending owner decisions — listed, not decided

| ID | The question | Why it needs the owner |
|---|---|---|
| **D-E7-1** | **What backup cadence** does the ≥90% coverage target mean in production? | The coverage is 1 − N/M, so the cadence must be chosen against the observed volume (§8). E7 measured the mechanism, not the policy. |
| **D-E7-2** | **Where must the intent queue live** — a separate store/region, or re-derivable from the match log? | S7's requirement; the plan already flags that no quota-isolated secondary exists (ruling F). |
| **D-E7-3** | **Which protocol figure does the capacity model carry** — 25.4 rows/match (cadence 5) or 29 (every match)? And are the three stores three D1 databases or one? | §10; it changes the ceiling on the settlement axis (3,543 matches/day, not 18,000) and the reading of the limit. |
| **D-E7-4** | **Confirm** that a quota/write failure is never converted into an abandoned match (no charge, intent retained, settlement applied later). | §11; the existing rulings (Q27(3), Q27c) already separate the two cases — E7 changes nothing and asks only for confirmation. |
| **D-E7-5** | **Is the local E7 evidence enough to close the E7 row**, or is a real-Cloudflare E7 leg required first? | §12; O-06 stays open either way, and M3's "exactly-once and recovery evidence" spans E7 + E9. |

## 16. Reproduction

```bash
# JVM leg — the stream and the recompute proof (8 tests)
cd native && ./gradlew :stage0:jvmTest --tests "com.estemshan.stage0.SettlementStreamTest"

# the local fault matrix (JVM↔workerd parity gate, S1–S9, the row accounting)
node D:/cache/stage0/e7/driver.mjs
```

The driver refuses to run if the JVM and workerd settlement streams differ, and writes
`results/e7-evidence-step3.json` with every raw number behind this report.
