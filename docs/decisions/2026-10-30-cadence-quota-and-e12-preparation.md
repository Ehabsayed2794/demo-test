# Stage 0 — the backup cadence, the 90% guardrail, and E12 preparation — owner rulings

- **Date:** 2026-10-30
- **Status:** RECORDED (documentation only). Three rulings given together on 2026-10-30, after
  E7 step 4 and E7b were pushed (`78b6d8f`).
- **Labels:** **[D]** owner-confirmed.
- **Closes:** **D-E7-1** (the production backup cadence — see §2). **Partly closes D-E7-3**: the
  protocol figure the capacity model carries is now fixed (27 rows/settlement, §2.2); the
  three-databases-or-one half of that question stays open.
- **Reaffirms, changes nothing:** the 2026-10-29 90% quota guardrail (rulings E/F,
  `docs/decisions/2026-10-29-stage0-capacity-persistence-transport-failover.md`) and **Q13**
  (`OPEN_QUESTIONS.md` §D).
- **Does not close:** **O-06** (the runtime decision — unchanged), **D-E7-2** (where the intent
  queue lives), **D-E7-5** (whether the local evidence closes the E7 row), **D-E7-6** (who holds a
  pending intent), **D-E7-7** (which DO row layout).
- **No real-account step may start before the owner confirms it.** This record is the approval
  checklist (§4), not a permission slip.

---

## 1. Ruling A — E12 preparation is approved; every step is gated [D]

**Ruling.** The owner **approves the preparation** of the E12 free-account experiment: a
**throwaway Cloudflare Free account**, **no real data**, and the steps run **one at a time, each
approved by the owner before it runs**. **No real-account step starts before the owner confirms
it.**

**What this record does and does not authorise.**

| | |
|---|---|
| **Approved** | preparing the experiment: writing the worker, the scripts, the measurement plan, and the checklist below |
| **Approved** | creating a **throwaway** Free account and a **throwaway** Worker + Durable Object on it |
| **NOT approved yet** | **any** step in §4's checklist — each needs its own "yes" from the owner |
| **Not approved, ever** | touching the owner's primary account, production, real user data, the Firestore rules, or any paid plan |

**The preparation itself has not been started.** At the time of this record no account exists, no
worker has been written for E12, and no network call to Cloudflare has been made.

**The experiment's full scope** is in `docs/implementation/Stage0_E7b_DO_Rows_Report.md` §9 — the
seven platform-side facts that cannot be measured locally. **§4 below is the approval checklist for
it**, in the order that unblocks the capacity model fastest (step 1 first: it decides whether the
×2 rows allowance stands).

**Local experiments are stopped** pending further instruction from the owner.

## 2. Ruling B — the backup cadence is every settlement [D]

**Ruling.** The backup runs **after every settlement** — cadence **N = 1**. Not every 5, not every
10.

**Why this is now a number and not a preference.** E7 step 4 measured all three cadences against
the 60-match battery (`docs/implementation/Stage0_E7_Backup_Outbox_Report.md` §8.1):

| Cadence | Coverage at a fault (M = 60) | Backup job rows per run (first / steady) | Backup rows per match | Whole protocol, rows/settlement |
|---|---|---|---|---|
| **every 1 settlement ← CHOSEN** | **98.33%** | 14 / **12** | **12.0** | **27.00** |
| every 5 settlements | 91.67% | 54 / 52 | 10.4 | 25.40 |
| every 10 settlements | 83.33% ❌ | 104 / 102 | 10.2 | 25.20 |

**What the ruling changes in the numbers.**

1. **The protocol figure the capacity model carries is 27 rows/settlement** (RP 10 + OUTBOX 5 +
   RP_BACKUP 12), **not 25.4**. The 25.4 figure stays in the record as the cadence-5 measurement;
   it is simply not the chosen policy. (The old → new log is in the E7 report §10.4.)
2. **The settlement axis ceiling becomes 3,333 matches/day at the 90% guardrail and 3,703 at
   100%** — `floor(90,000 / 27)` and `floor(100,000 / 27)`. (At cadence 5 the figures were 3,543
   and 3,937.)
3. **The backup job runs once per settlement**: 12 rows per run in steady state (14 on the first
   run of a fresh copy, because the two watermark rows are created rather than updated).
4. **The backup stays incremental** — it copies only the rows above its watermark, never the whole
   table, so a per-settlement cadence still copies 1 settlement and 4 ledger rows per run.

**What the ruling does not change.** The axis is still **far from binding**: 27 rows/settlement is
**4.05%** of the D1 daily limit at 150 matches/day, and the binding meter remains **DO rows
written** (E6's 1.6–2.3× headroom), not the settlement axis. The ceiling falls from 3,543 to
**3,333** matches/day — still far above the 100/125/150 planning range, and still **not** what
binds.

**Why cadence 1 is a reasonable choice and what it costs.** It buys coverage (98.33% instead of
83.33% at cadence 10) at 1.6 extra rows per settlement. The price is real but small, and the
coverage number is the one the plan's G-gates ask about.

## 3. Ruling C — at 90% of quota, refuse new matches and never cancel running ones [D]

**Ruling.** At **90%** of the daily quota: **stop admitting new matches**, and **never terminate a
match that is already running.** A running match runs to its end; its result is settled afterwards.

**This reaffirms, and does not change, the 2026-10-29 guardrail** (ruling E/F) and **Q13**:
*"at 90% the backup hosts new matches only; running matches stay; results wait for one central RP
database."* It is recorded here because the owner stated it explicitly on 2026-10-30.

**What the ruling forbids, in the project's own terms.**

| | |
|---|---|
| **Allowed** | refusing to **admit** a new match once the 90% line is reached |
| **Allowed** | letting a running match finish, then settling its result later |
| **Forbidden** | **cancelling** a running match because of a quota or platform failure |
| **Forbidden** | converting a quota failure into an **abandoned match** — which would charge the players (all Koz + an RP loss) for a platform failure, and **Q27(3)** forbids the charge |

**This is exactly what E7 §4 and §11 measured.** S6/S6b show that a refused write is **not** a lost
settlement provided the intent is retained: the intent stays pending, the settlement is applied
later, **exactly once**, with **no charge**. The ruling and the measurement agree; neither changes
the other.

**Two things the ruling does not settle** (they stay open, unchanged):

- **How the 90% line is read** — in-code counter vs dashboard. **T3** in `OPEN_QUESTIONS.md`
  ("can usage be read in-code at 90%?"; counter error vs dashboard) is exactly this, and it is
  **E12-a** in §4's checklist.
- **Where the new matches go** — Q13 says the backup hosts them, but the independent,
  quota-isolated secondary server **does not exist yet**. That remains a prerequisite
  (the owner's 2026-10-29 ruling F), and E7 is not evidence that it will.

---

## 4. The E12 approval checklist — one page, simple Arabic

**الحالة: مستنيّ. ولا خطوة واحدة اتنفّذت، ومفيش أي حساب حقيقي اتعمل. كل خطوة
هتستأذن منك لوحدها، وأنا بقف عند حدّ الخطوة اللي بتوافق عليها.**

### القرارات التلاتة (2026-10-30)

1. **تجهيز تجربة E12 على حساب مؤقت** — مفيش بيانات حقيقية، وكل خطوة لوحدها بموافقتك.
2. **الـ backup cadence = بعد كل تسوية** (مش كل 5 ولا كل 10).
3. **عند 90% من الكوتا: نرفض مباريات جديدة، ومانلغيش أي مباراة شغالة.**

### الأرقام اللي اتغيّرت بسبب القرار (2)

| | قبل | دلوقتي |
|---|---|---|
| الـ cadence | 5 (مرشح) | **1 — بعد كل تسوية** |
| التغطية وقت الحادثة | 91.67% | **98.33%** |
| سطر لكل تسوية | 25.4 | **27** (RP 10 + OUTBOX 5 + BACKUP 12) |
| سقف التسويات عند 90% | 3,543 | **3,333** مباراة/يوم |
| سقف التسويات عند 100% | 3,937 | **3,703** مباراة/يوم |

الرقم 25.4 بيفضل مسجّل في التقرير كقياس الـ cadence 5 — بس مش هو اللي بنمشي عليه.

### تشيك ليست E12 — كل خطوة محتاجة موافقتك

**⏸ إيقاف — لحد ما تقول "موافق على خطوة N"، محدشش يعمل الخطوة دي.**

✅ **خطوة 0 — الحساب — تم ✅**
الحساب المؤقت وصل، و**اتأكد** إنه **Free** من غير أي اشتراك (`subscriptions = []`)، يعني **مفيش
billing**. والحساب الأساسي **ما اتمسش** خالص.

✅ **خطوة 1 (E12-a) — أهم خطوة: هل عدّاد Cloudflare زي عدّادنا المحلي؟ — تم ✅**
نكتب **428** سطر في جدول عادي (V1) و**428** سطر في جدول مفتاحه مركّب (V2)، ونقارن
عدّاد الـ dashboard بـ **428** و **856**.
**بتجيب إيه:** هل الـ ×2 في نموذج السعة يفضل ولا يشال. دي الخطوة اللي بتفصل كل حاجة.
**النتيجة: Cloudflare عدّ نفس اللي الـDO كاتبه بالظبط — V1 = 428 (×1) و V2 = 856 (×2).**
يعني **الـ ×2 يفضل، مش يتشال** — بس بقى **شكل جدول** مش تخمين. تقرير كامل:
`docs/implementation/Stage0_E12a_Platform_Rows_Report.md`.

☐ **خطوة 2 (E12-b) — تكلفة `ctx.storage.put()` / `delete()`**
**100** put + **100** delete، ونقارن عدّاد الـ dashboard.
**بتجيب إيه:** سعر الـ API ده — العدّاد المحلي ميكشفشوش خالص، فمفيش غير الحساب الحقيقي.

☐ **خطوة 2-ج (E12-g) — جديدة — عدّاد القراءات**
Cloudflare عدّ **430 صف مقروء** في خطوة 1، والـDO سجّل **صفر**. نعمل نفس الكتابة مع **قرار
قراءة متعمد** (point lookup / count / scan / upsert) عشان نعرف Cloudflare بيعدّ إيه.
**بتجيب إيه:** رقم القراءة في نموذج السعة (≈1,130 صف قراءة لكل مباراة) صح ولا ناقص.

☐ **خطوة 3 (E12-c) — تكلفة `setAlarm()`**
`setAlarm` واحد، ونشوف الفرق في الـ dashboard.
**بتجيب إيه:** هل فعلاً بيكلف سطر واحد زي ما الوثائق بتقول.

☐ **خطوة 4 (E12-d) — الـ WebSocket والطلبات**
**4** اتصالات على DO واحد، ورسالة بحجم مباراة كاملة (**≈1,130**)، ونقارن **DO requests**
و **Worker requests** في الـ dashboard.
**بتجيب إيه:** هل الـ **20:1** بيتطبّق فعلاً، وهل الرسايل اللي بتطلع بتتحسب ولا لأ.

☐ **خطوة 5 (E12-e) — المدة والـ hibernation**
DO شغال فاضي والـ WS متصل لمدة مباراة، وبعدين تفصل، وبعدين evict قسري، ونقارن **GB-s**.
**بتجيب إيه:** هل المباراة المفتوحة بتكلّف مدة، وهل الـ hibernation بيشتغل.

☐ **خطوة 6 (E12-f) — تكرار خطوة 1 للتأكّد**
نفس الكتابة تاني على الحساب الحقيقي.
**بتجيب إيه:** نفرق بين "العدّاد غلط" و"العدّاد صح بس تعريف Cloudflare مختلف".

### الضوابط (قبل ما نبدأ)

- **مفيش بيانات حقيقية** — كل حاجة synthetic. مفيش مستخدمين حقيقيين ولا هوت.
- **النشر على الحساب المؤقت بس** — مفيش نشر على الإنتاج ولا على حسابك الأساسي.
- **مفيش تغيير على قواعد Firestore** ولا على الـ `firestore.rules.sha256` (البصمة بتفضل زي ما هي).
- **مفيش خطة مدفوعة** — Free tier بس، والكوتا اللي بيتستهلك من الحساب المؤقت، مش من حسابك.
- الحساب المؤقت بيتحذف بعد آخر خطوة (**E13** — سياسة حذف الحساب — تبقى تانية لوحدها).

### لسه مستنيّ منك

1. **موافقة على الحساب المؤقت (خطوة 0).**
2. بعد كل خطوة: **"موافق على خطوة N"** — وأنا بقف عند حدّ الخطوة دي بالظبط.

---

## 5. What this record does not change

- **O-06 stays open.** Approving the *preparation* of E12 is not approving Cloudflare, and the
  E7/E7b local evidence is still not a runtime decision.
- **No ruling was changed.** Ruling C reaffirms the 2026-10-29 guardrail and Q13.
- **D-E7-2, D-E7-5, D-E7-6 and D-E7-7 stay open** — the intent queue's home, whether the local
  evidence closes the E7 row, who holds a pending intent, and which DO row layout.
- **E9 is still E9's.** The action-log re-derivation option (E7 §5) stays **[u] unproven**.

## 6. Artifacts

| Artifact | Where |
|---|---|
| This record + the Arabic checklist | `docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md` (this file) |
| The E12 scope it gates | `docs/implementation/Stage0_E7b_DO_Rows_Report.md` §9 |
| The measured cadence table | `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` §8.1 |
| The protocol rows + ceilings + the old → new log | `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` §10 |
| The capacity models the new figure feeds | `docs/implementation/Stage0_E5_Capacity_Model_Report.md`, `docs/implementation/Stage0_E6_Target_Workload_Report.md` |
| The E12-a result (step 1 — done) | `docs/implementation/Stage0_E12a_Platform_Rows_Report.md` |
