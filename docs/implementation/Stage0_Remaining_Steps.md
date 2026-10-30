# Stage 0 — the remaining steps, in order, starting with E9

- **Date:** 2026-10-30
- **Status:** PLAN OF RECORD for the remainder of Stage 0, written after the runtime decision
  (`docs/decisions/2026-10-30-runtime-decision-o06-cloudflare.md`).
- **What is already done:** E1, E2, E3, E4, E5, E6, E7 (local), E7b (local), and the E12 platform
  measurements **a / c / d / e** on a throwaway Free account.
- **What is left:** **E9** (the gate), **E8**, **E10**, then the optional platform meters
  **E12-g/h/i** (and **b/f**, both skippable), then **E13** and **E11**.
- **Labels:** **[M] measured** · **[m] modelled** · **[u] unknown** · **[x] needs a real account**.

**The one sentence that matters: E9 is the only remaining experiment that can still change the
architecture.** Everything else either confirms a number already modelled or is a policy question.

---

## 1. E9 — durable recovery after interruption — **the gate**

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
   proves that a **real** eviction preserves the state.
3. **The pass criterion:** at **every** boundary, the recovered match is byte-identical to the
   uninterrupted match, the settlement is applied **exactly once**, and the RP ledger has **0
   duplicate rows and 0 lost settlements** — the same two negative controls E7 used.

**Effort:** 10–18 h (plan §5.6, E1.9). **Needs:** no account for the local leg; the throwaway
account for the platform leg. **Blocks:** P3 (the Ranked authority layer), G4, and the
unconditional form of ruling A.

---

## 2. E8 — D1 atomicity, triggers and query limits

**Question.** Does D1 give the settlement the atomicity E7's protocol assumes — one batch that
applies RP + OUTBOX + backup or none of it — inside the documented limits?

**Why after E9.** E7 proved the *protocol* on a local D1; E8 proves the *store*. The settlement is
the one write that must never half-apply, and it is the write the whole RP economy rests on.

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
| **P3 — the Ranked authority layer** | **gated on E9**, not on O-06 |
| **The capacity model** | now carries **measured** meters at **80/day** (see the E12 reports §10) |

## 7. The order, in one line

**E9 → E8 → E10 → (E12-g/h/i as needed) → E13 → E11**, with **E3.1** starting in parallel because
the runtime is decided. **E9 is the only one that can still change the architecture.**

## 8. Owner summary (Egyptian Arabic)

**اللي فاضل بالترتيب، وأول حاجة E9:**

1. **E9 — هل المباراة بترجع سليمة بعد أي انقطاع؟** بنقطّع عند كل نقطة ممكنة (قبل الحفظ، بعد
   الحفظ، في نص المزاد، عند التسوية، في نص مؤقت البوت) ونتأكد إن الحالة رجعت زي ما هي، والتسوية
   اتحصلت **مرة واحدة** بس. **دي الحاجة الوحيدة اللي لسه تقدر تغيّر التصميم** — لأن قرار "نحفظ بعد
   كل حركة" لسه معلّق عليها.
2. **E8 — هل D1 بيكتب التسوية كلها مرة واحدة أو مفيش؟** (محلي الأول، وبعدين على الحساب).
3. **E10 — الحذف والاحتفاظ والتخزين.** الحذف نفسه اتقاس (سطر واحد، صفر قراءات)، فاضل سياسة
   الاحتفاظ.
4. **E12-g/h/i — اختيارية.** مش بتوقف أي حاجة، والقراءات عند 1.8% من حدها.
5. **E13 و E11** — بتاعة المتجر وتسجيل الدخول، مش بتاعة السيرفر.

**وفي نفس الوقت، بما إن السيرفر اتحسم:** **E3.1** (تجهيز هيكل التشغيل) بقى مش موقوف، و**S8**
(الدفع) بقى مش موقوف على السيرفر — بس لسه موقوف على تصميم الشراء نفسه.
