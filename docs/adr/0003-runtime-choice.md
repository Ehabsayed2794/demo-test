# ADR 0003 — The server runtime for the Ranked authority layer

- **Status:** **ACCEPTED — option B, Cloudflare Workers + Durable Objects + D1 (owner, 2026-10-30).**
  O-06 is closed. Record: `docs/decisions/2026-10-30-runtime-decision-o06-cloudflare.md`.
- **Date:** 2026-10-29 (draft); **decided 2026-10-30**
- **Decides:** which runtime hosts the Ranked authority layer — the settlement,
  the match state and the statistics. The plan's Stage 0 experiments were run
  to make this decision on measurements rather than on preference.
- **Related:** ADR 0002 (`docs/adr/0002-functions-runtime.md`) — *how* the
  Functions layer is written; this ADR is *where* it runs. ADR 0002's pointer
  note records that **O-06 requires a Stage 0 runtime decision on measured
  evidence before anything is deployed**, and that **Cloudflare Workers +
  Durable Objects + D1 is not approved.**

## Context

The Ranked authority layer has to do three things that the Android client
cannot be trusted to do: re-run the project's own rules engine over the
converged match document (RD12 "correct-and-settle"), write the settlement
exactly once (RD18 / O-11), and survive a restart without double-applying
anything (E9).

Those jobs need a **server**. Which server is not a preference question: the
product is built under a **$0-marginal-cost constraint** (no Blaze, no paid
tier), which rules some options out on price before they are ruled out on
merit.

Two candidates are on the table:

| | **A — Firebase Cloud Functions (Node.js 22)** | **B — Cloudflare Workers + Durable Objects** |
|---|---|---|
| Engine hosting | Kotlin/JS, `:engine` as a compiled dependency — already built (ADR 0002, S42) | Kotlin/JS, the same artifact shape |
| State | Firestore | Durable Objects |
| Settlement idempotency | Firestore transactions | Durable Object storage |
| Cost at this scale | Free tier (Spark) | Free tier |
| Measured today | E1 ✅, E2 ✅ | E1 ✅, E2 ✅, E3 ✅, E4 ✅, E5 ✅, E6 ✅, E7 ✅ (local D1), **E12-a/c/d/e ✅ (real Free account)** (this ADR's evidence) |

The Stage 0 experiments were designed to answer "does the engine run correctly
in a server runtime, and what does it cost to start there" without touching
either vendor's production surface.

## Decision

> **DECIDED — option B. The Ranked authority layer runs on Cloudflare Workers + Durable Objects
> (SQLite-backed) + D1, on the Free plan, with no paid subscription.**
>
> - [ ] A — stay on Firebase Cloud Functions (Node.js 22)
> - [x] **B — move the authority layer to Cloudflare Workers + Durable Objects**
> - [ ] Neither yet
>
> **Decided by:** the owner (Ehab Sayed) **Date:** 2026-10-30
>
> **The decision's own numbers** (they are part of the decision, not defaults):
> a **V1 target of 80 completed matches/day** with a **refusal ceiling of 88/day**; the
> **plain (always-awake) WebSocket API**, not hibernation; **no backup/failover server in V1**
> (formally deferred); the ×1 row shape (no second index); RD19's 50-second clock unchanged.
> Full record, evidence and consequences:
> `docs/decisions/2026-10-30-runtime-decision-o06-cloudflare.md`.
>
> **What this decision does not decide:** the persistence granularity (still conditional on E9),
> D1 atomicity (E8), cleanup/retention (E10), and the failover architecture (post-V1). E9 is the
> first remaining step: `docs/implementation/Stage0_Remaining_Steps.md`.

> **POINTER NOTE (2026-10-31 — E8's local legs have RUN; the blockquote above is left verbatim).**
> D1 atomicity (E8) is now measured **locally**: the settlement's 5-statement batch commits or rolls
> back whole with **0 partial rows**, and the 27-row protocol reproduced
> (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md` §4, §7, §9). **E8's real-D1 leg is still
> open**, as is E9's platform eviction leg; E10 and the failover architecture are unchanged.

> **POINTER NOTE (2026-10-30 — E9 has now run locally; the blockquote above is left verbatim).**
> **E9's local legs PASS at all eight interruption boundaries**: a match recovers byte-identically
> to the uninterrupted JVM run, settles **exactly once**, and leaves **0 duplicate ledger rows and
> 0 lost settlements** (`docs/implementation/Stage0_E9_Recovery_Report.md` §3, §5, §9). The
> per-Trick persistence ruling therefore **stands on local evidence**. What remains open is the
> **platform eviction leg [x]** on the retained throwaway account (§1, §8.1 of that report) — the
> owner decides whether local evidence is enough. **E9 is no longer the first remaining step: E8
> is** (`docs/implementation/Stage0_Remaining_Steps.md` §2).

> **POINTER NOTE (2026-10-31 — E8's local legs have now run; the pointer above is left verbatim).**
> **E10 is the first remaining local experiment.** E8's **platform** legs — real-D1 atomicity,
> triggers, limits and the cross-database question — wait on per-step approval
> (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md` §13).

## Evidence

Each row is a measurement with its own artifact. The Stage 0 plan
(`docs/NATIVE_V1_IMPLEMENTATION_PLAN_V2.md` §5.4.3) defines the pass criterion
for each experiment; none of these is a claim about production behaviour.

### E1 — does the engine run in workerd at all? ✅ PASS

The Kotlin/JS engine artifact loads and plays a **complete** match inside
`workerd` with no unsupported API. Report: `docs/implementation/Stage0_E2_Corpus_Parity_Report.md` §3
(the corpus supersedes the single-match smoke test).

### E2 — do the two runtimes agree on whole matches? ✅ PASS

200 seeds × FULL/MINI × Normal/Classic = **800 matches, 11,200 rounds**, every
column **byte-identical** between the JVM and workerd (`cmp` + matching
sha256). Zero diffs, explained or otherwise.

### E3 — do the two runtimes agree on every RULE VECTOR? ✅ PASS

The sharper question: a corpus can agree while one rule disagrees, because a
match only exercises the rules its cards happen to reach. So the plan's
§5.3.3 vectors were run as a **table of 153 named vectors** across all 18
in-scope vector ids (T-01…T-16, T-22, T-24), each with a known expected
answer.

| Outcome | Count | Meaning |
|---|---|---|
| **IDENTICAL** | 139 | both runtimes produced the same answer, and it is recorded |
| **DIFFERENT** | 0 | the two runtimes disagreed — **none** |
| **NOT IMPLEMENTED** | 14 | the engine has no such behaviour; the missing behaviour is named |

The two rendered documents are **byte-identical**: 29,385 bytes, sha256
`f52451cf3adef1fc08d78ae9afaeed2c9b7d2728c1bac09e12cb07c5285c4ff3` on both
sides, `cmp` exit 0.

The 14 not-implemented rows are **not** engine defects: they are rows whose
subject is decided by the backend rather than by the rules engine (a server
or quota failure, when a charge is recorded, the 60-second all-humans-gone
wait, the 15-timeout counter, a fast declaration carrying its own suit). Each
names the missing behaviour and where the behaviour actually lives. Full table:
`docs/implementation/Stage0_E3_Vector_Parity_Report.md` §5.

On the plan's own wording, 3 of the 153 rows **deviate** — three deviating rows, not three
open decisions. One is **engine work**: the seat order decides an equal-highest fast call (the
known S4 gap), which needs the S4 story. Two are **documentation fixes**: a third Dash Call is
converted to a PASS, which the owner already ruled on 2026-10-24
(`docs/decisions/2026-10-24-f1-mixed-dash-general-pass.md` §2, **[D]**; the engine implements
the ruling and the plan's "rejected" sentence is stale), and the 13-rule force starts when the
others sum to 12, where the engine follows `CANONICAL_RULES.md` §2.3 and the plan's one-line
summary is the narrow reading. All three are the two runtimes agreeing with each other; they are
reported, not fixed, and none re-opens an owner decision. Detail: the report §4.

### E4 — does the bundle fit the platform, and how fast does it start? ✅ MEASURED

> **SLOT — the owner's reading of these numbers** (they are a local-dev
> measurement, not a production SLO; see the caveats in the report).

| Measure | Value |
|---|---|
| Worker bundle, raw | **625,718 bytes** (611 KB) — 4 files |
| Worker bundle, gzip (level 9, one stream over the four files) | **102,750 bytes** (100.3 KB) |
| Engine only (without the probe driver), raw / gzip | 491,636 / 76,974 bytes |
| Cold start — first request after start (16 runs) | median **36.3 ms**, min 16.0, p90 50.5, max 54.7 |
| Cold start — server-ready → first response | median **106 ms**, min 82, max 128 |
| Warm start — request on a live isolate (80 samples) | median **6.5 ms**, min 4.8, max 31.7 |
| A request that runs the whole rule-vector table (153 vectors) | median **41.8 ms** |

The gzip figure is `cat <files> | gzip -9 | wc -c` — one stream over the four files, which is
how the artifact is actually transferred. Gzipping the files one at a time instead gives 103,783
and 77,423; both numbers are in the report §6.1.

Cloudflare **removed the compressed-size limits on 2026-09-04** (3 MB Free / 10 MB paid) and now
checks **only the uncompressed bundle size, at 64 MiB on every plan** — so the 625,718-byte raw
bundle is about **0.9%** of the limit. The gzip figure is kept because it is the size the artifact
is transferred at, not because it constrains anything. Source:
[changelog 2026-09-04](https://developers.cloudflare.com/changelog/post/2026-09-04-increased-worker-size-limit/),
accessed 2026-10-29.

**Caveats that belong with these numbers:** the measurement is `wrangler dev
--local` on one machine, so it excludes edge routing, TLS, the real network
hop and the production isolate scheduler. It bounds the *local* start-up cost;
it is not a production latency SLO.

### E5 — what does one match cost, and what does the free tier carry? ✅ MEASURED

Full evidence: `docs/implementation/Stage0_E5_Capacity_Model_Report.md` (2026-10-29). The
short form: a FULL match costs **≈ 1,135 requests** (936 cards + ≈ 194 bidding actions +
lifecycle, measured over 50 seeds with JVM↔workerd count fingerprints identical) and, under
per-action durability, **≈ 1,135 rows written**. CPU is a non-problem: the worst measured
action is **0.067 ms** against the 10 ms/request budget — three orders of magnitude of
headroom. The binding free-tier meters are **requests/day** (HTTP-per-action transport) and
**rows written/day** (every transport): **≈ 88 matches/day** (≈ 79 at 90%), or ≈ 230/day with
per-trick write batching and ≈ 1,500/day on a WebSocket transport. The plan names no numeric
target usage, so capacity is presented per meter; the target is owner input E6 needs.

> **SLOT — the owner's reading of these numbers**, in particular whether the free tier's
> ≈ 88–230 matches/day is sufficient for V1's primary scenario, and which write granularity
> the recovery criteria (E9) should bless.
>
> **Pointer, 2026-10-29 (owner rulings A–G; the SLOT above stays open for O-06).** The owner
> has ruled on the assumptions this model must now use
> (`docs/decisions/2026-10-29-stage0-capacity-persistence-transport-failover.md`): persistence
> per **completed Trick** for card play, **conditional on E9's recovery validation** (the
> auction stays per-action); **bots inside the authoritative server**; **WebSocket transport
> preferred**; a **100 / 125 / 150 completed-matches-per-day planning range** (three labelled
> scenarios, not a forecast); the **90% quota guardrail** with new matches moving to the backup
> server per Q13 (the independent quota-isolated secondary-server architecture is flagged as
> missing — an implementation prerequisite); and **Cloudflare preferred on cost grounds** — a
> preference, **not** an O-06 approval, with the no-Blaze/$0 constraint and no-paid-plan
> position unchanged. E5's ≈ 1,135 requests/match and ≈ 1,135 writes/match are the preliminary
> action-count-based model; E6 recalculates for the WebSocket + server-side-bot design across
> the three scenarios.

### E6 — does the target workload fit the free tier at 90%? ✅ PASS WITH LIMITATIONS

Full evidence: `docs/implementation/Stage0_E6_Target_Workload_Report.md` (2026-10-29), built on
per-seat measured loads and measured server-side action chains (all count fingerprints
byte-identical JVM↔workerd); the capacity is modelled and the platform metering is unverified.
Under the ruled design — WebSocket transport, bots inside the authority, per-Trick persistence —
a FULL match costs **≈ 66 request-units (documented reading) and ≈ 428 rows written** (4 humans;
superseding E5's per-action figures as planning numbers). Against the owner's 100/125/150
scenarios the answer forks exactly on the platform facts that are not yet published or measured:

- **Documented readings**: the **binding meter is DO rows written — 42.8% / 53.5% / 64.2% of the
  daily limit (headroom 2.3× / 1.9× / 1.6×)**; DO requests carry 10–15× headroom under the 20:1
  reading; Worker requests, D1 rows and rows read are ≤ 3.4%; duration is negligible **if**
  hibernation works. The 90% guardrail never fires at target usage, and its 10% reserve — on the
  uniform **worst-case** basis (an ongoing match needs its whole remaining cost) — covers **151
  tables on requests (20:1) but only 23 / 11 on rows (1× / ×2)**; on the **average-case**
  halfway assumption [m] it covers **303 / 46 / 23 / 17** — rows stay marginal in the worst
  case against an unknown real concurrency peak [u].
- **Conservative readings** (free-plan WebSocket incoming messages billed 1:1 — the docs are
  ambiguous about Free-plan enforcement; the rows multipliers from deletes, `setAlarm()` and
  index updates combined; no hibernation): **requests overrun at all three scenarios**
  (114% / 142% / 171%), rows overrun at 125/150 (107% / 128%), duration at 125/150 (108% /
  130%); the guardrail trips early and its reserve does **not** protect ongoing matches
  (≈ 8 tables on requests, 11 on rows) — a documented gap per ruling E. (The earlier "outbound
  messages counted 1:1" figure was withdrawn: the pricing docs state outgoing messages are not
  billed.)

CPU is a non-problem for normal play as far as the engine's share goes (measured worst
inter-human bot chain: 13 actions ≈ 4 ms local wall-clock, excluding JSON/SQL/WS/auth) — but an
**unchunked all-bot fast-forward (a whole match in one invocation) is 17.9–46 ms and exceeds the
budget on the conservative 10 ms Workers reading** (the DO page states 30 s CPU per request
without separating plans — [x] which applies): the design must chunk it with alarms (each 1 row
written + 1 invocation). The decisive unknowns — free-plan WebSocket metering, the rows
multipliers, hibernation/duration, the applicable CPU limit — are owned by **E12/E9 on a real
Cloudflare account** (protocol in the E6 report §14); O-06 stays open until they are measured.

### E7 — does the backup/outbox/recovery protocol hold without a second RP truth? ✅ PASS (local)

Full evidence: `docs/implementation/Stage0_E7_Backup_Outbox_Report.md` (2026-10-30). Measured
against a **local** D1 (`wrangler dev --local`, three bindings — RP, RP_BACKUP, OUTBOX), over a
60-match battery of **real** settlement rows produced by the Kotlin engine inside `workerd`
(fingerprint `cbfe5a72`, byte-identical to the JVM leg).

The protocol keeps **one** RP authority: RP is the source of truth, RP_BACKUP only ever receives
copies, and the OUTBOX only ever receives intents. Across a 10-scenario fault matrix — kill
before the relay, kill after the ledger write before the ack, primary wipe and restore, a stale
backup restored twice, a write-failure window (a quota-exhaustion analogue), the outbox lost with
the primary, a recompute replay, and a split-brain conflict — the result is **0 duplicate RP rows
and 0 lost settlements**, with **two negative controls** proving the harness detects both failure
modes (20 rows / 476 delta lost; +375 delta double-awarded).

Measured: the backup lag (5 matches / 20 rows / 476 delta stale at a 5-match cadence), the
**restore-point rule** (replay every outbox row with `seq > max(outbox_seq)`, regardless of the
acked flag — the control that trusts the acked flags loses exactly that window), the write-failure
window (a rejected write is not a lost settlement, provided the intent is retained), the loss
window when the queue is lost with the primary (**5 settlements / 20 rows / 476 delta per
uncovered interval**), the recompute path (the missing settlements recomputed from the restored
state reproduce all 20 rows with **0 differences** across 9 fields), and the split-brain
behaviour (the same payload dedupes; a different payload raises a `PAYLOAD_MISMATCH` alarm and is
**not** acked).

Two figures the capacity model must now carry, both stated rather than silently replaced:

- the **91.67% backup coverage is a result of the chosen cadence, not a natural measurement**
  (coverage is `1 − N/M`; ≥90% requires the stale window to be at most 10% of the settlements in
  it);
- the **settlement write cost is 25.4 rows per match** for the whole protocol (RP + OUTBOX incl.
  ack/delete + the amortised backup copy) against E5's **5 rows** — D1 bills index rows too, and
  the old figure counted the ledger's table rows only. The axis stays far from binding (**≤ 3.81%
  of the D1 daily limit at 150 matches/day**); the ceiling on it falls from 18,000 to **3,543
  matches/day**.

> **SLOT — the owner's reading of these numbers.** In particular: the production backup cadence
> (D-E7-1), where the intent queue must live so that it does not share the primary's failure
> domain (D-E7-2), the protocol figure and the three-databases-or-one question (D-E7-3), the
> confirmation that a quota/write failure is never converted into an abandoned match (D-E7-4), and
> whether the local evidence closes the E7 row or a real-Cloudflare leg is required first
> (D-E7-5). All five are **listed, not decided**
> (`docs/implementation/Stage0_E7_Backup_Outbox_Report.md` §15).

> **POINTER NOTE (2026-10-30, owner rulings).** Two of the five slots are now filled:
> **D-E7-1 is DECIDED** — the production backup cadence is **every settlement** (N = 1) — and
> **D-E7-4 is CONFIRMED**: at the 90% guardrail the system **refuses new matches and never
> cancels a running one**, so a quota or write failure is never converted into an abandoned
> match. The protocol figure is therefore **27 rows per match** and the settlement-axis ceiling
> **3,333 matches/day at 90% / 3,703 at the hard limit** (the 25.4 / 3,543 figures above are the
> measured cadence-5 alternative, left verbatim). Record:
> `docs/decisions/2026-10-30-cadence-quota-and-e12-preparation.md`. **D-E7-2, D-E7-3, D-E7-5,
> D-E7-6 and D-E7-7 stay open, and this ADR stays PROPOSED — O-06 is not closed.**
>
> **Superseded later the same day (owner, 2026-10-30): O-06 IS closed and this ADR is ACCEPTED —
> option B, Cloudflare.** The sentence above and the paragraph below it are left verbatim as the
> state of the evidence at that moment; the decision is in *Decision* at the top of this ADR.

> **What this does NOT settle.** A local D1 does not measure Cloudflare behaviour: the real
> metering, the real atomicity, a real quota event and a real Free-plan enforcement are all
> **[x] — a real Cloudflare account**. The independent quota-isolated secondary server **does not
> exist**, so the requirement the loss window implies is a requirement **on an architecture that
> has not been built**. **E7 does not approve Cloudflare and does not close O-06.**

### E12 — the platform meters, measured on a real Free account ✅ DONE (a/c/d/e)

Two reports, one throwaway account, **no billing**:

- **E12-a — the rows-written meter** (`docs/implementation/Stage0_E12a_Platform_Rows_Report.md`):
  Cloudflare's metered count **equals** the DO's own cursor — **V1 = 428 (×1)**, **V2 = 856 (×2)**
  for the same 428 inserts. The ×2 is the platform's number, and it is a **schema choice**.
- **E12-d/e/c — the WebSocket, duration, alarm and delete meters**
  (`docs/implementation/Stage0_E12d_e_c_Platform_Meters_Report.md`):

| Fact | Measurement |
|---|---|
| Free-plan inbound WebSocket ratio | **1:1 under hibernation** (1,134 DO requests for 1,130 messages); **0 under the plain API** (7 requests) — **no 20:1 ratio exists** |
| Outgoing messages | **free** in both modes (1,130 outbound, 0 requests); messages are **not** Worker requests |
| Duration, always-awake | **0.128 GB·s per second** (180 s idle = 22.97 GB·s; a 15-min match = **115.2 GB·s**) |
| Duration, hibernated | **≈ 0** for the idle hold (0.0406 vs 0.0466 GB·s) — bought with 1,130 requests |
| `setAlarm()` | **0 rows, 0 requests**; the fire = **1 DO request**, 0 rows, 19.4 ms |
| Cleanup | `DELETE FROM` = **428 rows written, 0 reads, 1 statement**; row-by-row = 428 rows written, **856 reads**, 428 statements |
| The read meter | E12-a §8's per-write-read hypothesis is **falsified**: ten inserts cost **0 reads**; the 430 was the harness's own full scan |

**What that leaves as ceilings:** plain mode **112 matches/day** (duration), hibernation
**88 matches/day** (requests), rows-written **233/day** at ×1. **Duration binds; rows do not.**

### What is still NOT measured

Stage 0 is thirteen experiments, not one. **E8** (D1 atomicity), **E9** (durable recovery after an
interruption), **E10** (retention and cleanup), **E11** (anonymous→registered linking), **E13**
(account deletion and store policy), and the optional platform meters **E12-b/f/g/h/i** are **not
run**. **E9 is the only remaining experiment that can still change the architecture**: the
per-Trick persistence ruling is conditional on it. The order and scope:
`docs/implementation/Stage0_Remaining_Steps.md`.

> **POINTER NOTE (2026-10-30 — E9 is no longer unrun; the paragraph above is left verbatim).**
> **E9 ran locally on 2026-10-30 and PASSED at all eight boundaries** — recovery, exactly-one
> settlement, 0 duplicate and 0 lost ledger rows
> (`docs/implementation/Stage0_E9_Recovery_Report.md` §1, §3). The **platform eviction leg [x]**
> is the only open E9 item (§1, §8.1). The paragraph's other five experiments — E8, E10, E11, E13
> and E12-b/f/g/h/i — are still unrun, and **E8 is now the first remaining experiment**
> (`docs/implementation/Stage0_Remaining_Steps.md` §2).

> **POINTER NOTE (2026-10-31 — E8 is no longer wholly unrun; the paragraph above is left
> verbatim).** **E8's local legs PASS** — batch atomicity at k = 0…4, trigger abort and query
> limits measured on the local D1, the 27-row protocol reproduced
> (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md`). **E8's platform leg** and **E9's
> platform eviction leg** remain unrun; so do E10, E11, E13 and E12-b/f/g/h/i.

## Alternatives considered

### A — Firebase Cloud Functions on Node.js 22 (the status quo)

**Status:** implemented and green (ADR 0002, S42, S50). Chosen by default in
ADR 0002; this ADR does not reopen it unless the owner says so.

**What it needs before it can be adopted as the answer:**
- E5/E6 — the measured cost of one match, and whether the primary usage
  scenario fits the free tier **on Firebase's limits** (calls/month, function
  invocations, Firestore reads/writes, and the 60-min function timeout).
- E7/E9 — the backup, outbox and recovery story on Firestore: the duplicate-RP
  guarantee (RD18) and the exactly-once settlement path under interruption.
  *(E7's protocol is now measured — locally, on a local D1 — but **not** on Firestore: the
  duplicate-RP guarantee holds because the rows are absolute and the key is
  `(match_id, seat)`, which is a property of the rows, not of the store. E9 remains open.)*

  > **POINTER NOTE (2026-10-30 — E9 has now run, on the ruled runtime; the bullet is left
  > verbatim).** E9's local legs PASS at all eight interruption boundaries
  > (`docs/implementation/Stage0_E9_Recovery_Report.md` §1, §3): byte-identical recovery,
  > exactly one settlement, 0 duplicate and 0 lost RP rows. **This is evidence about the
  > Cloudflare/DO runtime, not about a Firestore re-implementation** — moving the authority back
  > to Firestore would need its own E7/E9 equivalents, so "E9 remains open" stays true **for this
  > alternative**.
- E8 — the D1-equivalent atomicity guarantee on Firestore transactions for
  the settlement write.

  > **POINTER NOTE (2026-10-31 — E8's D1 half is measured locally; the bullet is left verbatim).**
  > E8 proves D1's batch atomicity on the **local** runtime only; it says nothing about Firestore
  > transactions, so this bullet stays open **for alternative A**
  > (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md` §12).

**Strengths:** already built and shipped in the repo; the Firebase emulator and
CI are wired; Firestore is the store the whole product already reads and
writes, so there is **one** data model rather than two.

**Weaknesses:** the Firebase emulator's free-tier modelling is weak, so E5/E6
would have to be argued from first principles rather than measured; and the
Node 22 function cold start (hundreds of milliseconds to seconds) lands on
the Ranked result screen — the plan's risk R24, "a 'settling…' wait that feels
broken at the most satisfying moment in the game".

### B — Cloudflare Workers + Durable Objects

**Status:** **not approved** (O-06, 2026-10-22, `docs/decisions/OPEN_QUESTIONS.md`).
Stage 0 E1–E6 above are the evidence gathered so far, and they are positive.

> **POINTER NOTE (2026-10-30 — this paragraph is history).** **Option B is ACCEPTED** (see
> *Decision* above). The E12 meters the "what it needs" list below still names are now measured
> (§E12), the cost evidence exists, and the remaining gates are **E9 and E8**, not O-06.

> **POINTER NOTE (2026-10-31 — E8's local half measured; the pointer above is left verbatim).**
> The gates are now the **platform legs**: E9's real eviction and E8's real-D1 run. E8's local legs
> passed on 2026-10-31 (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md`).

**What it needs before it can be adopted:**
- E12 — the **real free-account verification** of the three meters E6 showed are decisive:
  free-plan WebSocket metering, the rows-per-write multipliers, and hibernation/duration. Under
  the documented readings E6 measured headroom for the 100/125/150 scenarios led by rows written
  at 1.6–2.3× (requests 10–15×); under the conservative inbound-1:1 reading all three scenarios
  overrun requests. E12 decides which world is real.

  > **Pointer, 2026-10-30 (E12-a ran: the rows-per-write multiplier is now measured on
  > Cloudflare; the other two meters are still [x]).** The owner's **throwaway** Free account (no
  > subscriptions, **no billing**) confirmed the **rows-per-write multiplier**: Cloudflare's
  > metered rows-written count **equals** the DO's own cursor count — **V1 = 428 (×1)**,
  > **V2 = 856 (×2)** — so the **×2 in the capacity model is a schema choice, not a precaution**,
  > and the "led by rows written" headroom above is now **measured on the platform**, not
  > modelled. **Still open:** the free-plan WebSocket metering and the hibernation/duration cost
  > (E12-d/e), the read meter (E12-g), V3 on the platform (E12-h), and D1's 27 rows/settlement
  > (E12-i). Report: `docs/implementation/Stage0_E12a_Platform_Rows_Report.md`. **This ADR stays
  > PROPOSED and O-06 stays open** — one meter measured is not an adoption.
- E9 — a Durable Object's state is per-object and single-threaded, which is a
  *better* fit for exactly-once settlement than a Firestore transaction, but it
  is a different durability story and has to be demonstrated, not assumed.
- E7 — the backup/restore story for Durable Object storage.
  *(E7's protocol is measured — locally, on a local D1 — but **not** on Durable Object
  storage: the restore-point rule, the absolute rows and the `(match_id, seat)` key are
  store-independent, while the store-side facts — atomicity across the three bindings, the
  real row metering, a real quota event — are **not**. E8 and E9 remain open.)*

  > **POINTER NOTE (2026-10-30 — the E9 half is measured locally; the two bullets are left
  > verbatim).** E9 ran on **this** runtime — a Durable Object's recovery from a hard kill at all
  > eight boundaries — and PASSED, with the settlement applied exactly once and 0 duplicate / 0
  > lost ledger rows (`docs/implementation/Stage0_E9_Recovery_Report.md` §1, §3, §6). **The
  > platform eviction leg [x]** is the only E9 item still open (§1, §8.1); a real Cloudflare
  > eviction did not run. **E8 remains open** by its own criterion (§8.7).

  > **POINTER NOTE (2026-10-31 — E8's local half measured; the pointer above is left verbatim).**
  > E8's local report exists and passes its criterion **on the local runtime**
  > (`docs/implementation/Stage0_E8_D1_Atomicity_Report.md` §4, §12). **E8 remains open** for the
  > platform, and the three-bindings question (D-E7-3) is informed, not decided — three databases
  > cannot share one atomic batch (§6 of that report).

- **A second data model.** The product's state lives in Firestore today. A
  Workers+DO authority layer means the match state, the settlement and the
  statistics live somewhere else, and the two must agree. That is a migration
  cost with no measured benefit attached to it yet.
- The settlement path's Firestore writes must be re-expressed: the Admin SDK
  runs on Node.js today, and Workers has a different Admin SDK surface.

**Strengths:** E1–E6 show the engine runs there, agrees with the JVM to the
byte, fits the bundle limit with ~99% headroom, and starts in tens of
milliseconds locally — the R24 "settling…" wait is the weakest of the
alternatives on that measure.

**Weaknesses:** none of the cost or durability evidence exists yet; a second
store means a second consistency story; and Cloudflare was **not approved**
in the first place.

### C — a third runtime (AWS Lambda / Fly.io / a self-hosted container)

**Status:** rejected on price and on scope.

**What it needs:** nothing — it is not pursued. Each candidate either
introduces a marginal cost the $0 constraint forbids, or requires operational
surface (a running server, its own deploy pipeline, its own secrets handling)
that the team has no capacity to own for a project of this size. It also
re-opens the engine-hosting question that ADR 0002 already answered.

## Consequences

Whatever is chosen, these do not change:

- **The rules engine stays Kotlin and stays shared.** The engine is compiled
  twice from one source tree (JVM and JS), so a scoring change lands in the
  app and in settlement together, by construction. ADR 0002's integrity
  property is a property of the *build*, not of the runtime.
- **`firestore.rules` stays frozen.** Any change needs an owner-approved
  change and a lockstep re-pin of `firestore.rules.sha256`.
- **Stage 0 does not decide the architecture.** It produces measurements and a
  recommendation; the owner approves or rejects the runtime through this ADR
  (plan §5.4.3, closing line).
- **The measurements are evidence, not a gate.** No CI workflow runs
  `:stage0:*`. Re-run E2/E3 by hand when the engine or the bidding rules
  change; the E3 report carries the recipe.

## When to reverse this decision

- **If real usage exceeds the 88/day refusal ceiling** — the measured ceiling is **112 matches/day**
  on duration and 233 on rows, so the ceiling is a product choice with headroom under it, not a
  platform wall. Exceeding it is a trigger to reopen the **failover** question (deferred), not this
  runtime choice.
- **If average match length pushes duration past the 90% guardrail** (≈ 19 minutes at 80/day) —
  the binding meter would then need the failover or a paid plan, and both re-open the $0 premise.
- **If E5/E6 show the primary usage scenario does not fit either free tier**,
  the constraint that picked the winner has changed and the ADR re-opens on
  cost, not on preference.
- **If the engine's JS artifact outgrows the platform's limits** — the
  uncompressed limit is the binding one today, at ~1% of the 64 MiB ceiling —
  then E4's row re-opens.
- **If a second data model is created** (Workers+DO alongside Firestore) and
  the two ever disagree about a settled match, that is a settlement-correctness
  incident, and the runtime choice is what caused it.
