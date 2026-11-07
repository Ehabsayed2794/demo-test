# The runtime decision — O-06 closes on Cloudflare, with a 80/88 match/day target

- **Date:** 2026-10-30
- **Status:** **DECIDED (owner).** The owner approved the recommendation given after E12-d/e/c on
  2026-10-30 ("موافق على التوصية، نفذ التلاتة"). This record is the decision, the numbers it rests
  on, and what it changes in the plan documents.
- **Closes:** **O-06** — the runtime for the Ranked authority layer: **Option B, Cloudflare
  Workers + Durable Objects + SQLite + D1**, on the **Free plan**, with **no paid subscription**.
- **Supersedes, in part:** ruling **D** of 2026-10-29 (the 100/125/150 matches-per-day planning
  range → **80 target / 88 ceiling**) and the **failover half of ruling F / Q13** (new matches
  move to the backup server → **V1 has no backup server; new matches are refused**). The rest of
  rulings A–G stands.
- **Does not change:** the $0 / no-Blaze / no-paid-plan constraint; RD19's 50-second inactivity
  clock; the 90% guardrail (refuse new, never cancel a running match); the backup/restore protocol
  (E7) and its cadence (every settlement).
- **Evidence:** `docs/implementation/Stage0_E12a_Platform_Rows_Report.md`,
  `docs/implementation/Stage0_E12d_e_c_Platform_Meters_Report.md`, and E1–E7 as recorded in
  `docs/adr/0003-runtime-choice.md`.

---

## 1. The decision

| | |
|---|---|
| **Runtime** | **Cloudflare Workers + Durable Objects (SQLite-backed) + D1** — ADR 0003 option **B** |
| **Plan** | **Free.** No paid subscription, no billing activation; the $0 marginal-cost constraint is unchanged |
| **V1 planning target** | **80 completed matches per day** |
| **Refusal ceiling** | **88 completed matches per day** — above it the system refuses to admit a new match and **never** cancels a running one |
| **WebSocket mode** | **Plain (always-awake)**, `server.accept()`. **Hibernation is not used in V1** |
| **Backup / failover server** | **None in V1** — formally deferred beyond V1. The 88/day refusal is the protection |
| **Row layout** | **×1 only** — the match table carries **no second index**; V1's rowid table today, V3 (`WITHOUT ROWID`) only after E12-h measures it on the platform |
| **RD19 inactivity clock** | **50 seconds, unchanged** |
| **Alarm design** | alarms are the bot-continuation and inactivity-timer currency: **1 DO request per fire, 0 rows** |
| **Cleanup** | one **`DELETE FROM`**, never a row-by-row loop |
| **Socket hygiene** | a socket silent for **90 seconds** is closed; clients send a **heartbeat** |

The engineering rules in the last four rows are **engineering choices made under this decision**,
not new owner rulings. Each one is a measured cost in the E12 reports.

## 2. Why this runtime, on the measurements

Every meter the Stage 0 experiments could measure now has a number. The two that decide:

| | Firebase Cloud Functions (Spark) | **Cloudflare (Free)** |
|---|---|---|
| Engine hosting | Kotlin/JS compiled dependency (built, S42) | Kotlin/JS, the **same artifact**, byte-identical to the JVM (E2/E3) |
| Rows/writes per match | ≈ 450–1,100 Firestore writes [m] → ≈ 18–44 matches/day on 20,000 writes/day | **428 DO rows** (×1) + 27 D1 rows → ≈ 233 matches/day on rows |
| Open-match cost | per-invocation, no always-on object | **0.128 GB·s/s** always-awake → ≈ 112 matches/day |
| Cold start on the result screen | hundreds of ms to seconds (R24 risk) | warm median **6.5 ms**, cold median **36.3 ms** local (E4) |
| Durability story | Firestore transactions | DO single-threaded object + SQL (E7 locally proven; E9 open) |

> **POINTER NOTE (2026-10-30 — the E9 half of the durability row is now measured locally; the row
> is left verbatim).** E9 ran on 2026-10-30: the DO recovered byte-identically from a hard kill at
> all eight boundaries and settled **exactly once** with 0 duplicate and 0 lost ledger rows
> (`docs/implementation/Stage0_E9_Recovery_Report.md` §1, §3, §6). The **platform eviction leg
> [x]** — a real eviction, not a local process kill — remains open (§1, §8.1).

**Cloudflare is not merely cheaper — it is the only one of the two whose binding meter still has
headroom at the target.** The Firebase figures are a model [m], not a measurement; the Cloudflare
figures are measured on the platform [M].

## 3. The numbers the decision rests on

All at the ruled design (4 humans, per-Trick persistence, per-action auction, 15-minute matches):

| Meter | Limit/day | Per match | **At 80/day** | **At 88/day** | Daily ceiling |
|---|---|---|---|---|---|
| **DO duration (GB·s)** — the binding meter | 13,000 | 115.2 | **70.9%** | 78.0% | **112** (101 at 90%) |
| DO rows written | 100,000 | 428 | 34.2% | 37.7% | 233 (210 at 90%) |
| DO requests | 100,000 | ≈ 7 (+ alarm fires) | 0.6% | 0.6% | ≈ 14,000 |
| DO rows read | 5,000,000 | ≤ 1,130 [m] | 1.8% | 2.0% | ≥ 4,400 |
| D1 rows written | 100,000 | 27 | 2.2% | 2.4% | 3,703 (3,333 at 90%) |

**Why 80 and not 88.** 80 is ~10% below the refusal ceiling and ~29% below the duration limit, so
a spike in match length or a burst of reconnects does not reach the guardrail. **The sensitivity
that matters: the 80/day target stays inside the 90% guardrail for average matches up to ≈ 19
minutes; at 88/day, up to ≈ 17.3 minutes.**

**Why plain mode and not hibernation.** Hibernation bills **1 DO request per inbound message**
(88/day ceiling) while plain bills **0** for the same messages and 0.128 GB·s/s of duration
(112/day ceiling). Plain wins on capacity — and the design's alarm-fired bot continuation would
incur a request per wake anyway, so hibernation does not deliver a request-free design either.

**Why the ×1 row shape is a rule.** E12-a measured the same 428 inserts at **428 rows** on a
rowid table with no second index and **856** with a composite primary key. The ×2 is Cloudflare's
number, not a local artefact.

## 4. What this decision changes in the documents

| Document | Change |
|---|---|
| `docs/adr/0003-runtime-choice.md` | Status PROPOSED → **ACCEPTED (B)**; the Decision slot is filled |
| `docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` | Pointers at the header, Stage 0 §5.4.3, and the O-06 row of §5.7.2 |
| `docs/implementation/Stage0_E5_Capacity_Model_Report.md` | Pointer: the platform meters are measured; the request axis is not what binds |
| `docs/implementation/Stage0_E6_Target_Workload_Report.md` | Pointer: the three decisive unknowns are measured; verdict per meter at 80/88 |
| `docs/implementation/Stage0_E12a_Platform_Rows_Report.md` | §9 pointer: the three follow-on steps ran; §8 correction |
| `docs/decisions/OPEN_QUESTIONS.md` | A decided row for the runtime; Q13's failover half marked superseded for V1 |
| `docs/implementation/Stage0_Remaining_Steps.md` | The remaining work, **E9 first** |

## 5. What the decision does NOT close

- **E9 — durable recovery after interruption.** The per-Trick persistence ruling is still
  **conditional on E9**. This decision does not bless the persistence granularity; it only fixes
  where it will run.

  > **POINTER NOTE (2026-10-30 — E9 has now run locally; the bullet is left verbatim).** On the
  > local runtime the per-Trick ruling validated at all eight boundaries
  > (`docs/implementation/Stage0_E9_Recovery_Report.md` §3, §9): byte-identical recovery, exactly
  > one settlement, 0 duplicate and 0 lost ledger rows. **The platform eviction leg [x] is the
  > one open E9 item** (§1, §8.1); the owner decides whether local evidence discharges the
  > condition for production.
- **E8 (D1 atomicity), E10 (cleanup/retention), E11 (anonymous→registered linking), E13 (account
  deletion / Play policy).**
- **E12-b/f/g/h/i** — the KV-style API, the repeat run, the precise read meter (its divergence is
  explained, §7 of the E12-d/e/c report), V3 on the platform, and D1's 27 rows/settlement.
- **D-E7-2, D-E7-3 (three-databases-or-one), D-E7-5, D-E7-6, D-E7-7** — the E7 report's open items.
- **The failover architecture.** V1 refuses above 88/day; a quota-isolated secondary server is
  **deferred beyond V1**. Nothing may be built assuming it exists.

> **POINTER NOTE (2026-10-31 — E8's local legs have RUN; the E8 bullet above is left verbatim).**
> E8's **local** atomicity, trigger and limit legs PASS (53/53 checks; 0 partial rows at k = 0…4;
> the documented 100-parameter and 100,000-byte limits enforced, the 2 MB row cap and 50-query Free
> cap **not** enforced locally). **E8's real-D1 leg is [x]**, so the item stays open; E10, E11,
> E13, E12-b/f/g/h/i and D-E7-2/3/5/6/7 are unchanged. Report:
> `docs/implementation/Stage0_E8_D1_Atomicity_Report.md`.

> **POINTER NOTE (2026-10-31 — E10 has now run locally; the E10 bullet above is left verbatim)
> [E10 wave — note 1 of 2].** E10 ran locally on 2026-10-31 — **65 checks, 0 failures** — and
> **retention and cleanup fit the free tier**. The binding meter is **DO rows written (854 per
> match, 68.32% of the free allowance at 80 matches/day)**; storage is not binding (D1 0.157%,
> DO 28.96% after a year). The **ledger (the player's balance) and its backup copy are never
> pruned**, and a **tombstone** row per match (76.889 B content / 127.7952 B physical) is the
> idempotency anchor — the negative control measured the double-apply without it. The
> **retention window is the owner's decision** (the report recommends **30 days**). E10's platform
> legs [x] did not run; E11, E13, E12-b/f/g/h/i and D-E7-2/3/5/6/7 are unchanged. Report:
> `docs/implementation/Stage0_E10_Retention_Storage_Report.md`.

## 6. Consequences and risks

| Risk | What this decision does about it |
|---|---|
| **Duration is the binding meter** | 80/day at 71%; the 90 s silent-socket close + heartbeat; the 90% guardrail refuses new matches |
| **Long matches** (Sa'ayda extensions) | The guardrail is reached at ≈ 19 min average at 80/day — measure average match length in closed testing |
| **A zombie socket keeps a table's duration running** | 90 s silent-socket close, enforced in the DO |
| **A second index doubles the rows meter** | No second index; V3 only after E12-h |
| **Row-by-row cleanup costs 856 reads/match** | `DELETE FROM` only |
| **Recovery is unproven** | E9 is the first remaining step; the per-Trick ruling stays conditional |
| **Above 88/day the product refuses matches** | That is the designed behaviour at this scale; the failover server is the post-V1 answer |

> **POINTER NOTE (2026-10-30 — the "Recovery is unproven" row is measured locally; the table is
> left verbatim).** E9's local legs PASS at all eight boundaries with exactly one settlement
> (`docs/implementation/Stage0_E9_Recovery_Report.md` §3). **Recovery on the platform (a real
> eviction) is still unmeasured** and is the only open E9 item (§1, §8.1); **E8 is now the first
> remaining experiment** (`docs/implementation/Stage0_Remaining_Steps.md` §2).

> **POINTER NOTE (2026-10-31 — E8's local legs have run; the pointer above is left verbatim).**
> **E10 is now the first remaining local experiment**; E8's real-D1 leg and E9's platform eviction
> leg wait on per-step approval (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md` §13).

> **POINTER NOTE (2026-10-31 — E10 has now run locally; the pointer above is left verbatim)
> [E10 wave — note 2 of 2].** E10 is no longer pending: it ran on 2026-10-31 with **65 checks,
> 0 failures** and **retention and cleanup fit the free tier**
> (`docs/implementation/Stage0_E10_Retention_Storage_Report.md` §2, §9). The next remaining local
> experiments are **E13 and E11**. Two things are now the owner's rather than an experiment's: the
> **retention window** (the report recommends **30 days**) and the **D1 rows-read crossing at
> ≈195 days** at 80 matches/day, which needs a separate, deliberate change to `balances()`
> (report §9.1, §11, §12). E8's real-D1 leg and E9's platform eviction leg wait on per-step
> approval.

## 7. Artifacts

| Artifact | Where |
|---|---|
| This record | `docs/decisions/2026-10-30-runtime-decision-o06-cloudflare.md` (this file) |
| The ADR it fills | `docs/adr/0003-runtime-choice.md` |
| The platform measurements | `docs/implementation/Stage0_E12a_Platform_Rows_Report.md`, `docs/implementation/Stage0_E12d_e_c_Platform_Meters_Report.md` |
| The capacity model | `docs/implementation/Stage0_E5_Capacity_Model_Report.md`, `docs/implementation/Stage0_E6_Target_Workload_Report.md` |
| The remaining work | `docs/implementation/Stage0_Remaining_Steps.md` |

## 8. Owner summary (Egyptian Arabic)

**قرار السيرفر اتحسم: Cloudflare، الخطة المجانية، من غير أي دفع.**

| | |
|---|---|
| السيرفر | **Cloudflare Workers + Durable Objects + D1** |
| الهدف | **80 مباراة/يوم** |
| سقف الرفض | **88 مباراة/يوم** — فوق كده بنرفض مباراة جديدة، ومش بنلغي مباراة شغالة أبداً |
| وضع الاتصال | **always-awake** (مش hibernation) |
| سيرفر احتياطي | **مفيش في V1** — مؤجل رسمياً |
| وقت الطرد بسبب عدم الاستجابة (RD19) | **50 ثانية — زي ما هو، مش هيتغير** |
| شكل الجدول | **×1 بس** — مفيش فهرس تاني على جدول المباراة |

**ليه؟** عشان المدة هي العدّاد اللي بيحكم دلوقتي (71% عند 80 مباراة)، والصفوف عند 34%، والطلبات
أقل من 1%. والاحتياطي مش محتاجينه على الحجم ده — الرفض عند 88 هو الحماية.

**اللي لسه شغال ومحتاج قياس:** **E9** (هل المباراة بترجع سليمة بعد أي انقطاع) — وده **أول خطوة
جاية**، وأي قرار عن حفظ الحالة بعد كل حركة لسه معلّق عليه.

> **POINTER NOTE (2026-10-30 — E9 اشتغلت محلياً؛ السطر فوق زي ما هو).** المباراة رجعت سليمة عند
> **التمانية حدود**، والتسوية اتحصلت **مرة واحدة** بس، ومن غير صفوف RP مكرّرة ولا تسوية ضايعة
> (`docs/implementation/Stage0_E9_Recovery_Report.md` §3). **الفاضل حاجة واحدة في E9:** تجربة
> **الإخلاء الحقيقي على الحساب المؤقت** (§1، §8.1) — مش مقاسة لحد دلوقتي.
