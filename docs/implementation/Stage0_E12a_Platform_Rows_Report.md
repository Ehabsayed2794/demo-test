# Stage 0 — E12-a: the platform's rows-written meter vs the Durable Object's own

**Status: MEASURED ON A REAL THROWAWAY CLOUDFLARE ACCOUNT, 2026-10-30.** The E7b report measured
the Durable Object's rows-written multiplier **locally** (workerd's own SQL cursor) and said so:
*a local count is not proof of what Cloudflare bills.* This report closes that gap for the two
shapes that decide the ×2 question — **V1** (rowid table, no secondary index) and **V2** (the same
composite `PRIMARY KEY (match_id, seq)` on an ordinary rowid table).

**The headline [M]: Cloudflare's metered rows-written count is exactly the number of rows the DO
wrote. 428 inserts on V1 → 428 rows billed (×1). 428 inserts on V2 → 856 rows billed (×2).**

| Design | Rows written per write — **local cursor** | Rows written per write — **Cloudflare's counter** | Equal? |
|---|---|---|---|
| **V1** — rowid table, no secondary index | **1** (428) | **1** (428) | **yes** |
| **V2** — rowid table, composite `PRIMARY KEY (match_id, seq)` | **2** (856) | **2** (856) | **yes** |

**What this settles:** the **"×2 rows allowance"** in the E5/E6 capacity model is **not a
precaution and not a local artifact**. It is **what Cloudflare meters** — a composite primary key
on an ordinary rowid table is written **twice** (the table row and the separate UNIQUE index), and
Cloudflare counts both. The capacity model's rows-written axis is therefore **confirmed**, and the
×2 may now be applied **as a schema choice** rather than carried as a bound.

**The second finding — a divergence in the READ meter [M]:** Cloudflare counted **430 rows read**
on each write instance (428 + the 2 setup rows) where the DO's own cursor reported
**`rowsRead = 0`**. The read axis of the capacity model is **not** confirmed by this run; it now
has its own open step (§8).

**The account was a throwaway, the plan was Free, and no billing was enabled** (§2). The
**primary account was never touched**: a fresh `XDG_CONFIG_HOME` kept its OAuth session out of
scope, and every configuration file pinned `account_id` to the throwaway account (§3.1).
**No secret is in this repository** (§10).

**Labels:** **[M] measured** · **[m] modelled** · **[u] unknown** · **[x] needs a real account**.

---

## 1. What the experiment is

The capacity model (E5/E6) charges a Ranked match **428 DO writes** and then multiplies by an
unexplained **×2** to get the rows written against Cloudflare's daily limit. The local
measurement (E7b) explained *why* the factor exists — the schema shape — but could not say
whether Cloudflare meters it the same way.

E12-a runs the **same worker, the same schemas, the same 428 writes** on a real Cloudflare
account and reads Cloudflare's own counter for the very same Durable Object instances.

**It is a single question:** does the number Cloudflare meters equal the number the DO's cursor
reports?

## 2. The account, the plan, and the billing constraint

| | |
|---|---|
| Account | **`c6321eda25af9e06234358928272a0fa`** — the **throwaway** account, created by the owner for this purpose |
| The primary account | **`7e9c5a16c04380f81cfd730b76d8684b`** — **never used in this run** (§3.1) |
| Plan | **Free** — `GET /accounts/{id}/subscriptions` returns **`[]`** (an empty result ⇒ no paid plan) |
| **Billing** | **not activated** — the only constraint the owner set. A Free account with no subscriptions cannot incur charges; the run created one worker, one DO class, one KV namespace, and 428×2 rows of throwaway data. |
| Token | an **account-scoped API token**, held in `D:/cache/stage0/e12/cf.env` (**outside the repo**) and never echoed |
| Data | **no real data** — synthetic payloads of 100 bytes of the alphabet |

## 3. Method

### 3.1 What was deployed, and what was kept out of scope

| Piece | Choice | Why |
|---|---|---|
| Worker | `stage0-e12-do-rows` — a **byte-identical copy** of the E7b worker (`worker.mjs` lines 1–379 verified with `diff`), plus a cron driver appended | the DO code path under test must be the one measured locally |
| Durable Object | one SQLite-backed class (`new_sqlite_classes = ["MatchDO"]`) | the same shape E7b used |
| Instance names | **unique per run** (`e12-v1-<ts>`, `e12-v2-<ts>`, `e12-ctl1-<ts>`, `e12-ctl2-<ts>`) | the platform groups its metrics **per instance (`objectId`)**, so attribution is exact |
| Readout channel | a **KV namespace** — the cron writes the cursor counts into it; the collector reads them through `api.cloudflare.com` | the worker could not be called from this machine (§3.2) |
| Platform counter | GraphQL `viewer.accounts.durableObjectsPeriodicGroups.sum.{rowsRead,rowsWritten}`, grouped by `dimensions.objectId` | this is Cloudflare's own metered number |
| Scope guards | `account_id` pinned in `wrangler.toml`; a **fresh** `XDG_CONFIG_HOME` (`D:/cache/stage0/e12/config`) | the primary account's OAuth session was never in scope; `wrangler login` was never run |

### 3.2 One environment obstacle, worth naming

**`*.workers.dev` cannot be reached from this machine.** The TLS handshake to the deployed
worker's own hostname fails with **alert 40** (handshake failure) — from Node's undici, from
curl/schannel, and from `openssl` alike — while `api.cloudflare.com` completes TLS 1.3 on the
same Cloudflare edge. The same edge IP accepts one SNI and rejects the other, and DNS resolution
is identical through the system resolver and through 1.1.1.1: **this is an SNI-based network
filter on this work computer, not a Cloudflare or account fault.**

**The workaround keeps the measurement honest.** A **cron trigger** (`* * * * *`, accepted on the
Free plan) fires **inside Cloudflare**, so the code under test executes on Cloudflare's runtime
and not on this machine. The cron writes the DO's own cursor counts into **KV**, and the
collector reads KV and the GraphQL counter through `api.cloudflare.com` — the one host this
machine reaches. **The counters compared are still Cloudflare's**: the DO's cursor as Cloudflare's
runtime saw it, and Cloudflare's metered counter for the same instances.

**The DO code was not modified for the workaround** — only the driver around it.

### 3.3 The runs

The cron performs, in one pass, on four fresh instances:

1. **`ctl1`** — V1 **setup only** (`CREATE TABLE`): the control that prices the schema.
2. **`ctl2`** — V2 **setup only** (`CREATE TABLE` with the composite `PRIMARY KEY`): the control.
3. **`v1`** — V1 setup + **428 single-row `INSERT`s**, payload 100 B.
4. **`v2`** — V2 setup + **428 single-row `INSERT`s**, payload 100 B.

The controls make the write cost separable from the schema cost: the platform counter is
cumulative per instance, so

```
write rows (platform) = platform(v) − platform(ctl)
```

**The workload is not retyped.** 428 writes and a 100-byte payload are the E7b/E5/E6 figures
themselves.

## 4. The measurement [M]

**Raw platform counter** (summed over the periodic groups of each instance, matched by
`objectId`):

| Instance | Local cursor `rowsWritten` | Local cursor `rowsRead` | **Platform `rowsWritten`** | **Platform `rowsRead`** |
|---|---|---|---|---|
| `e12-v1-…` (V1, setup + 428 inserts) | **428** | **0** | **430** | **430** |
| `e12-v2-…` (V2, setup + 428 inserts) | **856** | **0** | **859** | **430** |
| `e12-ctl1-…` (V1, setup only) | — | — | **2** | **2** |
| `e12-ctl2-…` (V2, setup only) | — | — | **3** | **2** |

**After subtracting the control (the setup cost):**

| | Setup cost (platform) | **Write rows (platform)** | **Write rows (local cursor)** | **Rows per write** | Equal? |
|---|---|---|---|---|---|
| **V1** | 2 | **428** | **428** | **×1.0** | **yes** |
| **V2** | 3 | **856** | **856** | **×2.0** | **yes** |

**The setup cost is itself a schema signal [M]:** V1's `CREATE TABLE` costs **2** metered rows
written, V2's costs **3** — the composite primary key adds one more metered row at schema time,
the same extra index that costs one more row per insert.

## 5. What this settles

1. **The ×2 is real and it is Cloudflare's number. [M]** 428 inserts cost 856 metered rows on V2
   and 428 on V1. The E5/E6 model's ×2 allowance is not a precaution that can be argued away; it
   is the metered cost of the schema it assumed. **The rows-written axis of the capacity model
   is confirmed.**
2. **The factor is a schema choice, not a platform behaviour. [M]** The same platform, the same
   runtime, the same 428 statements: **1 row** on V1, **2 rows** on V2. The choice is entirely in
   the table definition, and `WITHOUT ROWID` (V3) is the local measurement's ×1 candidate for the
   same uniqueness guarantee.
3. **The local counter is trustworthy for the write axis. [M]** In this run the DO's own cursor
   and Cloudflare's meter agreed to the row, on both schemas. A local E7b-style measurement is
   therefore a **valid proxy for the write axis** — which is what makes the rest of Stage 0
   legible without a real account.
4. **The DO instance is the unit of accounting. [M]** The platform groups by `objectId`; the
   uniquely named instances made the attribution exact, with no cross-talk.

## 6. What this does NOT settle

**The owner deferred E12-g (the read meter), E12-h (V3 on the platform) and E12-i (D1's 27
rows/settlement) on 2026-10-30** — "far from binding" — in favour of the three steps in §9.
They stay open; they are not cancelled.

1. **The read meter. [u]** Cloudflare counted **430 rows read** per write instance where the DO's
   cursor reported **0**. The two counters disagree on reads (§8) — so the E6 read figure
   (≈ 1,130 DO rows read per match) and the "reconnect snapshot = 1 row read" assumption are
   **not** confirmed by this run. **Its own step: E12-g** (§9).
2. **V3 on the platform. [x]** The ×1 `WITHOUT ROWID` design is a **local** measurement. Whether
   Cloudflare also meters it at ×1 is the obvious next question — it is the design the E7b report
   recommends (§10 of that report), and it is the one that would let the capacity model use ×1.
   **Its own step: E12-h** (§9).
3. **D1 rows written. [x]** The **27 rows per settlement** figure (10 RP + 5 OUTBOX + 12
   RP_BACKUP) is a **D1** figure, and D1's rows-written meter is a different counter from the DO
   one. It is measurable on the same account. **Its own step: E12-i** (§9).
4. **Duration, hibernation, requests, WebSocket metering, the free-plan request ratio. [x]**
   Untouched by this run — they are E12-b…E12-f.
5. **Billing behaviour. [x]** This run shows what the counter **says**; it does not price a
   month. (The account has no subscriptions at all, so there is nothing to price.)

## 7. What it changes in the capacity model

**The measurement sits on the per-Trick axis.** 428 writes per match = **234 trick checkpoints +
193.9 auction actions** — the ruled persistence design ("persist per completed Trick, auction per
action", the 2026-10-29 ruling). The E5/E6 model's **per-action-durability** axis (1,135
rows/match) is a **different axis**: it charges a state write for *every* applied action, and it
is the variant **E9** must test. **It is not what E12-a measured**, and the figures below do not
use it.

### 7.1 The per-Trick axis — now measured on Cloudflare

| Schema shape | Rows per match | 100 matches/day | 125/day | 150/day |
|---|---|---|---|---|
| **×1** — V1 rowid, or V3 `WITHOUT ROWID` | **428** | 42,792 — **42.8%** (2.3× headroom) | 53,490 — **53.5%** (1.9×) | 64,188 — **64.2%** (1.6×) |
| **×2** — V2 composite `PRIMARY KEY` on a rowid table | **856** | 85,584 — **85.6%** (1.2×) | 106,980 — **107% ✗** | 128,376 — **128% ✗** |

(limit: **100,000 DO rows written/day**, Free tier — E5 §4. The daily figures are E6 §5's own
numbers for the three scenarios, at the model's canonical 427.92 rows/match; **428 is that
figure's rounding**.)

**Both rows were modelled before this run; both are measured now.** The ×2 was an *allowance*
carried "to be safe" — it is now **what Cloudflare meters**.

**What the shape decides:**

- under **×1**, all three scenarios fit with headroom, and **DO rows written stops being the
  binding meter**. The binding meter becomes **DO duration if hibernation does not work**
  (86.5% / **108% ✗** / **130% ✗** — E6 §5), i.e. **E12-e**;
- under **×2**, rows written binds again: 100 matches/day sits at 85.6%, **just under the 90% trip
  line**, and **125 and 150 overrun the limit outright**.

### 7.2 The per-action-durability axis — a different axis, still unmeasured

The E5 model's other rows-written axis (**1 row per applied action = 1,135 rows/match**) was
**not** measured by E12-a. The **schema factor applies to it identically** — the same second index
— so it would read **1,135 (×1) or 2,270 (×2)** rows/match, i.e. **113.5% / 141.9% / 170.3%** at
×1 and **227% / 283.8% / 340.5%** at ×2 against the 100,000/day limit: **it would overrun at every
scenario under either shape**. Whether that axis applies at all is **E9's** question — the ruled
design persists per Trick, not per action.

### 7.3 Document by document

| Document | Before this run | After |
|---|---|---|
| E6 §5 "DO rows written (100,000)" row | 42,792 / 53,490 / 64,188 at ×1, modelled from a local counter; the ×2 carried as an allowance | **both rows measured on Cloudflare**: ×1 = 428/match (42.8% / 53.5% / 64.2%), ×2 = 856/match (85.6% / 107% / 128%) |
| E5 §4 "per-trick in play + per-action in bidding" row | ≈ 433 (866) → 230 (115) matches/day | the model figure stands; **the factor behind it is measured**, not carried |
| E5 §4 "per-action durability" row | 1,135 (×2: 2,270) → 88 (44) matches/day | **unchanged** — a different axis, still unmeasured (§7.2) |
| E7b §10 | "This is a local count, not proof of what Cloudflare bills" | **superseded on the write axis** — it is now proof for V1/V2; the read axis stays open |

**The direction of the decision has changed, not the decision.** Under the composite key on an
ordinary rowid table (V2), the free tier carries **44 matches/day** on the rows-written axis
alone; under an ×1 shape it carries **233**. The owner ruling on the row layout (**D-E7-7**) is
worth materially more than it was yesterday — but it is still the owner's to make, and **V3's
platform number is still [x]**.

## 8. The read-meter divergence — the one open thing this run created

**Measured [M]:** Cloudflare's `rowsRead` for a write instance = **430**; the DO's own cursor
`rowsRead` = **0**. The gap is **exactly the 428 inserts** plus the 2 setup rows, and it is the
**same 430 for V1 and V2** — so it tracks the number of **`INSERT` statements**, not the number of
rows written (V2 wrote 856 rows but read 430).

**Hypothesis [m]:** Cloudflare's read meter counts the row read that SQLite performs internally
per write (checking the key/index), which the local runtime's cursor does not surface. It fits
the local V6 measurement, where the upsert **did** report 428 reads (the upsert reads the row it
replaces) — the local counter reports *explicit* reads, the platform meter reports *all* reads.
**The prediction, if the hypothesis holds: V6 on the platform should read ≈ 856** (428 upsert
reads + 428 write-implied reads) — **a testable prediction, not a measurement.**

**Why it matters:** E6 charges ≈ 1,130 rows **read** per match and E7b's recommended snapshot
design assumes **1 row read** for a reconnect. If the platform also charges a read per write, the
read axis grows by **≈ 428 per match** — a ~38 % change to a figure that currently sits far
inside the limit, so it is **not** a capacity blocker. It is a **correctness** issue: the model
must describe the meter Cloudflare actually bills.

**The step that would settle it: E12-g** (§9) — the same cron harness, with the read shapes
measured separately (a point lookup, a count, a full scan, an insert, an upsert), so the read
meter's definition is measured instead of inferred.

## 9. The next steps — the owner's list, one at a time

**The owner set the priority order on 2026-10-30 (after E12-a).** These run on the same
throwaway account, one at a time, with a report after each, and **no billing**. The E12b
report's §9 already spends **b** on the KV-style API, **c** on alarms, **d** on WebSockets and
requests, **e** on duration/hibernation, and **f** on the repeat run; **E12-g/h/i are deferred by
the owner** (g = the read meter of §8, h = V3 on the platform, i = D1's 27 rows/settlement —
"far from binding").

| # | Step | Question | Why it matters |
|---|---|---|---|
| **1** | **E12-d — WebSocket metering** | 4 clients, one match's worth of messages (≈ 1,130): what do Cloudflare's **DO requests** and **Worker requests** counters show? | tests the **20:1 inbound ratio on the Free plan** and whether the messages are also counted as Worker requests — two E6 figures that are still modelled |
| **2** | **E12-e — duration (GB-s)** | a match-length connection, **with** and **without** hibernation | whether an open match costs duration; under an ×1 row shape this becomes the **binding meter** (§7.1) |
| **3** | **E12-c + the delete cost** | what does **`setAlarm()`** cost, and what does a **row-by-row `DELETE`** cost vs **`DELETE FROM`**? | the alarm figure E7's §11 inactivity-timer design carries, and the cleanup cost E10 needs — both measured on the platform's own counter |

**Nothing above has been run.** The deployed worker stays in place as the E12 harness for these
steps: its cron still fires every minute, but the run flag makes it a **no-op** — no writes, no new
DO instances, no rows. The four DO instances and their rows remain on the throwaway account until
the account is deleted (the account-deletion policy is **E13**, a separate step). **When the three
steps are done, the cron and the worker are removed and the owner is asked to revoke the token.**

## 10. Artifacts

| Artifact | Where |
|---|---|
| This report | `docs/implementation/Stage0_E12a_Platform_Rows_Report.md` (this file) |
| The measured result | `D:/cache/stage0/e12/results/e12-a-results.json` (scratch, outside the repo) — the raw KV record, the raw platform groups, and the derived comparison |
| The worker + cron driver | `D:/cache/stage0/e12/worker.mjs`, `worker-cron.mjs` |
| The collector | `D:/cache/stage0/e12/collect.mjs` |
| The configuration (account id, **no token**) | `D:/cache/stage0/e12/wrangler-cron.toml` |
| The token (**never in the repo**) | `D:/cache/stage0/e12/cf.env` |
| The deploy logs | `D:/cache/stage0/e12/logs/deploy-1.txt`…`deploy-4.txt`, `run-1.txt`, `collect-1.txt` |
| The companion reports | `docs/implementation/Stage0_E7b_DO_Rows_Report.md`, `Stage0_E7_Backup_Outbox_Report.md`, `Stage0_E5_Capacity_Model_Report.md`, `Stage0_E6_Target_Workload_Report.md` |

**No secret is in this repository.** The API token lives only in `D:/cache/stage0/e12/cf.env`,
which is outside the worktree, and no token, key or session file is tracked.

## 11. Reproduction

```bash
# 1. deploy (throwaway account only; the token is read from the environment)
cd /d/cache/stage0/e12 && . ./cf.env && \
  node D:/cache/stage0/node_modules/wrangler/bin/wrangler.js deploy -c wrangler-cron.toml

# 2. wait for the cron to fire, then read KV + the platform counter and diff them
node collect.mjs        # writes results/e12-a-results.json
```

The collector polls KV for the cron's record, polls the GraphQL counter until it shows all four
instances, then pairs them **by `objectId`** and writes the comparison. It uses only Node
built-ins and the pinned local wrangler — no installs, no PATH or admin changes
(`docs/engineering/NODEJS_SAFE_USAGE.md`).

## 12. Owner summary (Egyptian Arabic)

**E12-a خلص، والنتيجة واضحة.**

1. **رقم Cloudflare هو نفسه اللي الـDO كاتبه.** كتبت 428 سطر في جدول عادي (V1) → Cloudflare عدّ
   **428** صف مدفوع (يعني ×1). نفس الـ428 سطر في جدول بمفتاح أساسي مركّب (V2) → Cloudflare عدّ
   **856** (يعني ×2).
2. **يعني الـ"×2" اللي في نموذج السعة مش تخمين — ده اللي بيتحاسب فعلاً.** والسبب هو **شكل
   الجدول** مش سلوك المنصة: نفس المنصة، نفس الكود، الفرق بس إن V2 عنده فهرس تاني بيخلي السطر
   يتكتب مرتين. لو استخدمنا `WITHOUT ROWID` (V3) هنرجع لـ×1 — **بس ده لسه متقيسش على المنصة**.
2-ج. **التأثير على السعة (بأرقام per-Trick، مش per-action):** المباراة = **428** صف بـ×1 و**856**
   صف بـ×2. يعني عند **100 / 125 / 150** مباراة في اليوم: **42.8% / 53.5% / 64.2%** من حدّ
   الـ100,000 صف/يوم تحت ×1، و**85.6% / 107% / 128%** تحت ×2 — يعني **125 و150 بتتجاوز الحد**
   لو اخترنا الشكل ×2. (محور الـper-action — 1,135/2,270 — ده محور تاني مش متقيس، وE9 هو
   اللي بيحسم هل هو أصلاً applies.)
3. **في اختلاف جديد في عدّ القراءات:** Cloudflare عدّ **430 صف مقروء** في كل instance، بينما الـDO
   نفسه سجّل **صفر**. يعني عدّ القراءات محتاج قياس لوحده (E12-g) — لكن ده **مش** مشكلة سعة
   (القراءة بعيدة تحت الحد)، بس الرقم في الموديل لازم يتظبط.
4. **الحساب: تجريبي، Free، ومفيش أي billing.** `GET /accounts/{id}/subscriptions` رجع **فاضي**.
   **حساب A الأساسي ما اتلمسش خالص** — عملت `XDG_CONFIG_HOME` جديد وربطت `account_id` بالحساب
   التجريبي بس.
5. **الخطوات الجاية (بترتيب أولويتك):** (1) **عدّ الـ WebSocket** على المنصة الحقيقية، (2) **المدة
   GB-s** مع ومن غير hibernation، (3) **تكلفة `setAlarm` والحذف**. و**E12-g/h/i متأجلين** زي ما
   اتفقنا.

English: Cloudflare's metered rows-written equals the DO's own count exactly — V1 ×1, V2 ×2 — so
the capacity model's ×2 is confirmed and is a schema choice, not a platform precaution. On the
per-Trick axis that is 428 rows/match (×1) or 856 (×2), i.e. 42.8% / 53.5% / 64.2% and
85.6% / 107% / 128% of the 100,000 rows/day limit at 100 / 125 / 150 matches per day. The read
meter diverges (430 vs 0) and is deferred. The throwaway account is on the Free plan with no
subscriptions; the primary account was never touched. The next steps are the owner's list:
WebSocket metering, duration, then setAlarm/delete.
