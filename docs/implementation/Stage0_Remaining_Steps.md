# Stage 0 — the remaining steps, in order, starting with E8

- **Date:** 2026-10-30 (updated the same day: E9's local legs ran and passed)
- **Status:** PLAN OF RECORD for the remainder of Stage 0, written after the runtime decision
  (`docs/decisions/2026-10-30-runtime-decision-o06-cloudflare.md`).
- **What is already done:** E1, E2, E3, E4, E5, E6, E7 (local), E7b (local), **E9 (local legs, all
  eight boundaries)**, and the E12 platform measurements **a / c / d / e** on a throwaway Free
  account. E9's report: `docs/implementation/Stage0_E9_Recovery_Report.md`.
- **What is left:** **E8** (the first remaining experiment), **E10**, the optional platform meters
  **E12-g/h/i** (and **b/f**, both skippable), then **E13** and **E11** — plus **E9's platform
  eviction leg [x]**, the only open E9 item.
- **Labels:** **[M] measured** · **[m] modelled** · **[u] unknown** · **[x] needs a real account**.

> **POINTER NOTE (2026-10-31 — E10 has now run locally; the "What is left" bullet above is left
> verbatim) [E10 wave — note 1 of 5].** E10 ran locally on branch `stage0/e10-retention`:
> **65 checks, 0 failures**. The verdict is that **retention and cleanup fit the free tier** — but
> the binding meter is **DO rows written (68.32% at 80 matches/day)**, not storage, and the
> **ledger (the player's balance) is never pruned**. The **retention window is the owner's
> decision** (the report recommends **30 days**). E10's platform legs [x] did not run. Report:
> `docs/implementation/Stage0_E10_Retention_Storage_Report.md`.

> **POINTER NOTE (2026-10-31 — E8's local legs have RUN and PASSED; the title and the "What is
> left" bullet above are left verbatim).** E8 ran locally on branch `stage0/e8-d1-atomicity`:
> **53/53 checks, 0 failures** — the settlement batch is **atomic at k = 0…4** (0 settlement rows
> and 0 ledger rows after a failed batch; the pre-settled duplicate path's 5 rows unchanged), and
> the **27-row steady protocol reproduced** (RP 10 + OUTBOX 5 + backup 12).
> **The platform legs [x] have not run**, so **E8 stays open by its own criterion** and **P3 is
> gated on E9's platform eviction leg AND E8's platform leg**. **E10 is now the first remaining
> local experiment**; E8's five-step platform checklist waits for per-step approval. Report:
> `docs/implementation/Stage0_E8_D1_Atomicity_Report.md`.

> **POINTER NOTE (2026-10-31 — E10 has now run locally; the sentence "E10 is now the first
> remaining local experiment" above is left verbatim) [E10 wave — note 2 of 5].** E10 is no
> longer pending: it ran on 2026-10-31 with **65 checks, 0 failures**, and the next remaining
> local experiments are **E13 and E11** (the two non-runtime ones); E12-g/h/i stay optional. The
> item the note above left open — the **retention window** — is now the owner's to choose; E10
> costed 7 / 30 / 90 / 365 days and recommends **30 days**. One new open item came out of E10:
> the **D1 rows-read meter is crossed after ≈195 days** at 80 matches/day, because the ledger is
> never pruned — and fixing that is a separate, deliberate change to `balances()`, not a retention
> patch. Report: `docs/implementation/Stage0_E10_Retention_Storage_Report.md` §9.1, §11, §12.

**The one sentence that matters: E9's local legs passed, and the only part of E9 that can still
change the architecture is its platform eviction leg.** Everything else either confirms a number
already modelled or is a policy question.

> **POINTER NOTE (2026-10-30 — E9's local legs have RUN and PASSED; the question, boundaries and
> method in §1 are left verbatim below the verdict.)** The evidence: **144/144 checks, 0
> failures**, 12 boots, **11 fault-injection kills at the eight boundaries + 1 final shutdown**,
> every assertion read from store contents after the kill —
> `docs/implementation/Stage0_E9_Recovery_Report.md` §3 (the eight boundaries), §6 (settlement),
> §8 (residual limits), §10 (reproduction). **The only open E9 item is §1.3's platform eviction
> leg [x]** on the retained throwaway account; it did not run, and the owner decides whether it
> must run before ruling A is closed for production (§1, §8.1, §9.1 of the report).

---

## 1. E9 — durable recovery after interruption — **PASS locally; the platform leg is open**

**Verdict (2026-10-30).** **PASS at all eight boundaries on the local `workerd` runtime** [M]:
**144/144 checks, 0 failures**, **12 boots**, **11 fault-injection kills at the eight boundaries +
1 final shutdown**, and at every boundary the recovered match is **byte-identical** to the
uninterrupted JVM run at the same prefix, **one settlement per match**, **0 duplicate and 0 lost
ledger rows** (E7's two negative controls included). **Design A was measured and not adopted; B
stands. The only remaining E9 item is the platform eviction leg [x]** in §1.3 step 2 — a real
eviction on the retained throwaway account, never run, needing its own per-step approval. **E8 is
now the first remaining experiment (§2).** Report:
`docs/implementation/Stage0_E9_Recovery_Report.md` §1, §3, §5, §6, §8, §9.

**The eight boundaries, in one table** (seed `50…57` = boundary `1…8`; the final digest is the
JVM log of the same seed, and the recovered object matches it row for row — except boundary 7,
whose match deliberately never finishes, so its evidence is the JVM's prefix-1 digest `c2bdb9f4`,
matched row for row):

| # | Boundary | Seed | Units | Rows at kill | Final digest | Settlement |
|---|---|---|---|---|---|---|
| 1 | before a Trick checkpoint | 50 | 484 | 11 = JVM prefix 11 | `b7b6c3a7` | 1 × 4 ledger, Δ 64 |
| 2 | after the checkpoint, before the ack | 51 | 410 | 13 | `dbcde317` | 1 × 4, Δ 67 |
| 3 | mid-auction | 52 | 438 | 5 | `e3a3aac5` | 1 × 4, Δ 81 |
| 4 | at the last trick (With/Koz) | 53 | 453 | 452, 0 settled | `063bdb6c` | 1 × 4, Δ 75 |
| 5 | during settlement | 54 | 432 | intent + 1 settlement + 4 ledger rows, outbox unacked | `76436647` | 1 × 4, Δ 82 |
| 6 | after settlement, before the result | 55 | 437 | settled | `005477b0` | 1 × 4, Δ 68 |
| 7 | during a bot takeover / alarm tick | 56 | 424 | 0 | `c2bdb9f4` = JVM prefix 1 (never finishes) | none — deliberately unfinished |
| 8 | at quota exhaustion | 57 | 439 | intent pending, 0 RP | `2e39c379` | 1 × 4, Δ 77 |

**Design A vs B (measured per seed):** **B stands** — B is 410–484 rows/match; **A1 = 1,346–1,420**
and **A2 = 1,112–1,186**, i.e. **107.7–113.6%** and **89.0–94.9%** of the 100,000-row hard limit at
80 matches/day (the 90,000 guardrail caps A1 at **63–66/day** and A2 at **75–80/day**). A was
**measured, not adopted**, so no recovery proof is owed for it
(`docs/implementation/Stage0_E9_Recovery_Report.md` §5).

**Under the verdict, the original text, verbatim:**

**Question.** Does a match survive an interruption at **every** boundary — the process, the
object, the network, the quota — and come back with the same state, exactly one settlement, and no
lost or double-applied action?

**Why it is first.** The owner's 2026-10-29 ruling A — persistence **per completed Trick** for card
play, **per action** for the auction — is *conditional on E9*. If E9 fails on any boundary, the
persistence granularity changes, and with it the rows-written model (428/match today) that the
whole capacity model and the 80/day target rest on.

### 1.1 The boundaries E9 must kill at

| # | Boundary | What must be true afterwards |
|---|---|---|
| 1 | **Before** a Trick checkpoint is written | the action log replays the incomplete trick; no partial checkpoint is ever visible |
| 2 | **After** the checkpoint write, **before** the ack to the clients | the checkpoint is the truth; a replay must not re-apply it |
| 3 | **Mid-auction** (per-action persistence) | the auction resumes at the last persisted action; no bid is lost or doubled |
| 4 | **At the last trick** (the With/Koz decision point) | the final state is either fully before or fully after — never half-settled |
| 5 | **During settlement** (RP + OUTBOX + backup, E7's protocol) | exactly one settlement; a retry is a no-op (E7 already proves the protocol locally) |
| 6 | **After settlement, before the result is read** | a reconnect returns the settled result, not a re-run |
| 7 | **During a bot takeover / an alarm tick** | the takeover is not duplicated; the alarm is idempotent |
| 8 | **At quota exhaustion** (the 90% guardrail) | the match is never cancelled; the intent stays pending and settles later (E7 S6/S6b) |

### 1.2 The two candidate designs E9 has to choose between

| | **A — checkpoint + action log (replay)** | **B — checkpoint only (snapshot at each Trick)** |
|---|---|---|
| Recovery | load the last checkpoint, replay the actions since | load the last checkpoint; the in-progress trick is lost and re-driven by the clients |
| Rows written | 428 checkpoints **+ the action log** (the auction is already per-action) | **428** (the measured figure) |
| Risk | replay must be idempotent per action — a new invariant to prove | a lost trick costs the **players'** in-flight actions; the client must re-send |
| Status | **[u] unproven** — E7 §5 lists the re-derivation option as unproven | the ruled design's current assumption |

**E9's job is to make this choice on evidence.** If A cannot be made idempotent cheaply, B stands
and the capacity model is already correct. If A is needed, the rows model changes and the 80/day
target is re-run.

### 1.3 How to run it

1. **Local `workerd` leg (no account):** build the DO with the real persistence shape —
   per-Trick checkpoint, per-action auction — and an injected kill at each of the eight boundaries
   (§1.1). Restart the runtime, reload the object, and compare the state and the settlement
   ledger against the uninterrupted run. The E7 harness already has the fault-injection pattern
   and the row accounting; reuse it.
2. **Platform leg [x]:** the same object on the throwaway account, with `ctx.abort()` (or an
   eviction) at the same boundaries, and the DO's own recovery observed through KV. Only this leg
   proves that a **real** eviction preserves the state. **The account is retained for exactly this
   purpose** (owner, 2026-10-30 — `docs/decisions/2026-10-30-test-account-retained.md`): the token
   stays valid, the deployed code is removed after each leg, no billing, and the leg still runs only
   on its own approval.
3. **The pass criterion:** at **every** boundary, the recovered match is byte-identical to the
   uninterrupted match, the settlement is applied **exactly once**, and the RP ledger has **0
   duplicate rows and 0 lost settlements** — the same two negative controls E7 used.

**Effort:** 10–18 h (plan §5.6, E1.9). **Needs:** no account for the local leg; the throwaway
account for the platform leg. **Blocks:** P3 (the Ranked authority layer), G4, and the
unconditional form of ruling A.

---

## 2. E8 — D1 atomicity, triggers and query limits

> **POINTER NOTE (2026-10-31 — E8's local legs have RUN and PASSED; the section below is left
> verbatim).** Measured on `wrangler dev --local` (Miniflare SQLite) with the settlement functions
> byte-identical to E9's: the **5-statement RP batch is atomic** — a trigger abort or duplicate PK
> at **k = 0…4** throws and leaves **0 settlement / 0 ledger rows** (k = 1…4) or the pre-existing
> 5 rows untouched (k = 0) — and the correct batch writes **5 table rows / 10 metered rows** exactly
> once. **The documented limits are not all enforced locally**: 100 bound parameters and the
> 100,000-byte SQL cap are; the **2 MB row size and the 50-query Free cap are not**. The real-D1
> leg remains **[x]**. Report: `docs/implementation/Stage0_E8_D1_Atomicity_Report.md`.

**Question.** Does D1 give the settlement the atomicity E7's protocol assumes — one batch that
applies RP + OUTBOX + backup or none of it — inside the documented limits?

**Why it is first now.** E7 proved the *protocol* on a local D1, and E9 proved it again at the real
DO→D1 binding under interruption; E8 proves the *store's* mechanism — that D1 applies the batch
atomically or not at all, inside the documented limits. The settlement is the one write that must
never half-apply, and it is the write the whole RP economy rests on.

**Method.** Batched settlement writes at the real row counts (10 RP + 5 OUTBOX + 12 backup = 27),
a failure injected inside the batch, trigger behaviour if triggers are used, and the query-limit
boundaries. Local `wrangler dev --local` first; a real-D1 leg **[x]** afterwards.

**Pass:** the settlement is atomic within documented limits; a failed batch leaves no partial row.
**Effort:** 6–12 h. **Blocks:** P3.

## 3. E10 — cleanup, retention, and storage

**Question.** Do retention and cleanup fit the request, rows and storage limits?

**Already measured [M]** (E12-c): cleanup of 428 rows costs **428 rows written, 0 rows read, 1
statement** with `DELETE FROM`; the row-by-row alternative costs **856 reads**. **Still open:** the
retention policy (how long finished matches are kept), the storage sizing that follows from it,
and the cleanup trigger (alarm vs sweep).

**Method.** Model retention windows against the storage limit and the measured per-match row count;
measure a real cleanup sweep on a throwaway account if the model shows pressure.
**Effort:** 6–10 h. **Blocks:** G5.

> **POINTER NOTE (2026-10-31 — E10 has now run; §3's question, method and effort are left
> verbatim) [E10 wave — note 3 of 5].** E10 ran locally on 2026-10-31 — **65 checks, 0 failures** —
> and answers §3's question **"yes"**: retention and cleanup **fit** the free tier. The
> **"still open" list above is now closed by measurement:** the retention policy is costed at
> 7 / 30 / 90 / 365 days (**the window is the owner's decision; the recommendation is 30 days**),
> the storage sizing that follows from it is modelled per store, and the cleanup trigger was
> measured four ways (the recommendation is a per-match alarm **armed once** at the end of the
> window). The measured numbers that changed the model: **854 DO rows written per match** (E12-a's
> 428 was insert-only and did not count the purge), **27 D1 rows per match** (29 on the first
> settlement), **53,248 B of DO file per match that a purge does not return**, and a **tombstone**
> of **76.889 B content / 127.7952 B physical** per match as the clean idempotency anchor — with
> the negative control proving that **without it the replay double-applies**. **The ledger and
> its backup copy are never pruned.** Report:
> `docs/implementation/Stage0_E10_Retention_Storage_Report.md`.

## 4. The optional platform meters — E12-g / h / i (and b / f)

| Step | Question | Status | Why it is optional now |
|---|---|---|---|
| **E12-g** | the read meter precisely: a point lookup, a count, a scan, an **upsert** | **[x]** | E12-c **explained the E12-a divergence** (it was the harness's own scan); the read axis is ≤ 1.8% of its limit at the target. Run it only if a read-heavy design appears |
| **E12-h** | V3 (`WITHOUT ROWID`) on the platform | **[x]** | the ×1 shape is already achieved by V1; V3 is an optimisation, not a requirement. Run it **before** adopting V3, never after |
| **E12-i** | D1's 27 rows per settlement on the real counter | **[x]** | 2.2% of the D1 limit at 80/day; the axis is not binding |
| **E12-b** | the KV-style API (`ctx.storage.put` / `delete`) | **[x]** | the durable state is SQL; the KV path is used only for small counters |
| **E12-f** | the repeat run | **[x]** | E12-c already repeated E12-a's method on a second table shape |

**None of these blocks P3.** Each becomes worth running when the design starts to lean on the
corresponding meter.

## 5. E13 and E11 — the two non-runtime experiments

| Step | Question | Why it is still listed |
|---|---|---|
| **E13** | account deletion and the current Play closed-testing policy | needed for **P10 / G8** and for the store submission; nothing to do with the runtime |
| **E11** | does anonymous→registered linking preserve the uid and the data? | **P6**; a Firebase-side question (the client's auth), unaffected by the runtime decision |

## 6. What the runtime decision unblocks immediately

| Item | State after O-06 |
|---|---|
| **E3.1 — runtime scaffold for the approved architecture** | **unblocked** (was gated on O-06); 48–88 h |
| **S8 / Play Billing verification** | **no longer blocked on the runtime** — it needs a deployable backend, and one is now chosen; it still needs the purchase design |
| **P3 — the Ranked authority layer** | **gated on E8** and on **E9's platform eviction leg [x]**; E9's local legs passed on 2026-10-30 (§1) |
| **The capacity model** | now carries **measured** meters at **80/day** (see the E12 reports §10) |

> **POINTER NOTE (2026-10-31 — the P3 row's E8 condition is now half-measured; the row is left
> verbatim).** E8's **local** legs PASS: the settlement batch is atomic within every documented
> limit it touches, and a failed batch leaves no partial row
> (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md` §4, §7, §12). **P3 stays gated on two
> platform legs** — E9's eviction and E8's real-D1 run; the local evidence closes neither.

## 7. The order, in one line

**E8 → E10 → (E12-g/h/i as needed) → E13 → E11**, with **E3.1** starting in parallel because the
runtime is decided, and **E9's platform eviction leg [x]** whenever the owner approves it. **E9's
local legs passed on 2026-10-30; only its platform leg can still change the architecture.**

> **POINTER NOTE (2026-10-31 — E8's local legs have run; the order line above is left verbatim).**
> On the local side the order is now **E10 → (E12-g/h/i as needed) → E13 → E11**; **E8's platform
> leg** and **E9's platform eviction leg** wait on their own per-step owner approval. E8 is no
> longer first on the local side.

> **POINTER NOTE (2026-10-31 — E10 has now run; the order line and the pointer above are left
> verbatim) [E10 wave — note 4 of 5].** The local order in the pointer above is **done**: E10 ran
> on 2026-10-31 with **65 checks, 0 failures**. **What is left on the local side is E13 and E11**
> (the two non-runtime experiments); E12-g/h/i stay optional. Two things are now waiting on the
> **owner** rather than on another experiment: the **retention window** (7 / 30 / 90 days — the
> report recommends **30**) and the **D1 rows-read crossing at ≈195 days**, which needs a
> deliberate change to `balances()`. Report:
> `docs/implementation/Stage0_E10_Retention_Storage_Report.md` §9.1, §11, §12.

## 8. Owner summary (Egyptian Arabic)

**اللي فاضل بالترتيب، وأول حاجة E8:**

1. **E9 — اتعملت محلياً ونجحت.** المباراة رجعت سليمة عند **التمانية حدود**، والتسوية اتحصلت
   **مرة واحدة** بس، ومن غير صفوف RP مكرّرة ولا تسوية ضايعة
   (`docs/implementation/Stage0_E9_Recovery_Report.md` §3). **الفاضل بس تجربة الإخلاء الحقيقي على
   الحساب المؤقت** — مش اتعملت. يعني قرار "نحفظ بعد كل حركة" بقى متأكد على الدليل المحلي.
2. **E8 — هل D1 بيكتب التسوية كلها مرة واحدة أو مفيش؟** (محلي الأول، وبعدين على الحساب).

> **POINTER NOTE (2026-10-31 — بند E8 العربي فوق اتساب زي ما هو).** E8 المحلي خلص ونجح:
> **53/53 فحص، صفر فشل**، والتسوية إما بتتكتب كلها أو مفيش حاجة — **صفر صف تسوية وصفر صف دفتر**
> بعد الفشل، والتسوية الصح **10 صفوف محسوبة**، والبروتوكول **27 صف**. الفاضل **تجربة D1 الحقيقية
> على الحساب المؤقت** و**تجربة الإخلاء بتاعة E9**، وكل واحدة بتستأذن لوحدها. التقرير:
> `docs/implementation/Stage0_E8_D1_Atomicity_Report.md`.

3. **E10 — الحذف والاحتفاظ والتخزين.** الحذف نفسه اتقاس (سطر واحد، صفر قراءات)، فاضل سياسة
   الاحتفاظ.

> **POINTER NOTE (2026-10-31 — بند E10 العربي فوق اتساب زي ما هو) [E10 wave — note 5 of 5].**
> E10 المحلي خلص ونجح: **65 فحص، صفر فشل**، والحذف والاحتفاظ **بيدخلوا في الخط المجاني**. الرقم
> اللي بيوجع مخك مش التخزين — ده **صفوف الـDO: 854 صف لكل ماتش** (المسح بيتحسب مع الكتابة)، يعني
> **68.3%** من الحد المجاني عند 80 ماتش/يوم. **ورصيد اللاعب (الـledger) ما ينمسحش أبدًا**، والفيتير
> (بصمة على العقد) هو اللي بيمنع التسوية مرتين. **الفاضل قرار منك:** نحتفظ بتفاصيل الماتش **7 ولا
> 30 ولا 90 يوم** — التوصية **30 يوم**. التقرير:
> `docs/implementation/Stage0_E10_Retention_Storage_Report.md`.

4. **E12-g/h/i — اختيارية.** مش بتوقف أي حاجة، والقراءات عند 1.8% من حدها.
5. **E13 و E11** — بتاعة المتجر وتسجيل الدخول، مش بتاعة السيرفر.

**وفي نفس الوقت، بما إن السيرفر اتحسم:** **E3.1** (تجهيز هيكل التشغيل) بقى مش موقوف، و**S8**
(الدفع) بقى مش موقوف على السيرفر — بس لسه موقوف على تصميم الشراء نفسه.
