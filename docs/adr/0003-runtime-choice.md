# ADR 0003 — The server runtime for the Ranked authority layer

- **Status:** PROPOSED — **the decision is the owner's (O-06).** This ADR is the
  skeleton and the evidence; the *Decision* section below is deliberately empty.
- **Date:** 2026-10-29 (draft)
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
| Measured today | E1 ✅, E2 ✅ | E1 ✅, E2 ✅, E3 ✅, E4 ✅ (this ADR's evidence) |

The Stage 0 experiments were designed to answer "does the engine run correctly
in a server runtime, and what does it cost to start there" without touching
either vendor's production surface.

## Decision

> **SLOT — the owner's decision (O-06).** Nothing below this line is a
> recommendation that has been accepted; the measurements are the inputs.
>
> - [ ] **A — stay on Firebase Cloud Functions (Node.js 22)**, and close O-06 with
>       the Stage 0 evidence recorded in this ADR.
> - [ ] **B — move the authority layer to Cloudflare Workers + Durable Objects.**
> - [ ] **Neither yet** — name the missing experiment (E5…E13) that has to run
>       first.
>
> **Decided by:** ____________________ **Date:** ____________

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
§5.3.3 vectors were run as a **table of 153 named vectors** across all 19
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

On the plan's own wording, 3 of the 153 rows **deviate**: the seat order decides an
equal-highest fast call (the known S4 gap), a third Dash Call is converted to a
PASS rather than rejected, and the 13-rule force starts one step earlier than the
plan says. All three are the two runtimes agreeing with each other; they are
reported, not fixed, and the owner has to decide whether the plan's sentences or
the engine's behaviour should change. Detail: the report §4.

### E4 — does the bundle fit the platform, and how fast does it start? ✅ MEASURED

> **SLOT — the owner's reading of these numbers** (they are a local-dev
> measurement, not a production SLO; see the caveats in the report).

| Measure | Value |
|---|---|
| Worker bundle, raw | **625,718 bytes** (611 KB) — 4 files |
| Worker bundle, gzip (level 9) | **103,890 bytes** (101.5 KB) |
| Engine only (without the probe driver), raw / gzip | 491,636 / 77,520 bytes |
| Cold start — first request after start (16 runs) | median **36.3 ms**, min 16.0, p90 50.5, max 54.7 |
| Cold start — server-ready → first response | median **106 ms**, min 82, max 128 |
| Warm start — request on a live isolate (80 samples) | median **6.5 ms**, min 4.8, max 31.7 |
| A request that runs the whole rule-vector table (153 vectors) | median **41.8 ms** |

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

### What is still NOT measured

Stage 0 is thirteen experiments, not one. E5 (measured resources vs the
capacity model), E6 (primary usage at 90% of the free tier), E7 (backup lag /
outbox / recovery), E8 (D1 atomicity), E9 (durable recovery after an
interruption), E10 (write/cleanup cost), E12 (hibernation and duration cost)
and E13 (account deletion and store policy) are **not** run. A choice between
the two runtimes that depends on those numbers — especially E5/E6 (does the
usage fit the free tier?) and E9/E7 (does the state survive?) — is **not yet
decidable on evidence**.

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
- E8 — the D1-equivalent atomicity guarantee on Firestore transactions for
  the settlement write.

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
Stage 0 E1–E4 above are the evidence gathered so far, and they are positive.

**What it needs before it can be adopted:**
- E5/E6 — the measured cost per match and the free-tier headroom **on
  Cloudflare's limits** (Workers requests/day, CPU time per request, Durable
  Object storage and duration limits).
- E9 — a Durable Object's state is per-object and single-threaded, which is a
  *better* fit for exactly-once settlement than a Firestore transaction, but it
  is a different durability story and has to be demonstrated, not assumed.
- E7 — the backup/restore story for Durable Object storage.
- **A second data model.** The product's state lives in Firestore today. A
  Workers+DO authority layer means the match state, the settlement and the
  statistics live somewhere else, and the two must agree. That is a migration
  cost with no measured benefit attached to it yet.
- The settlement path's Firestore writes must be re-expressed: the Admin SDK
  runs on Node.js today, and Workers has a different Admin SDK surface.

**Strengths:** E1–E4 show the engine runs there, agrees with the JVM to the
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

- **If E5/E6 show the primary usage scenario does not fit either free tier**,
  the constraint that picked the winner has changed and the ADR re-opens on
  cost, not on preference.
- **If the engine's JS artifact outgrows the platform's limits** — the
  uncompressed limit is the binding one today, at ~1% of the 64 MiB ceiling —
  then E4's row re-opens.
- **If a second data model is created** (Workers+DO alongside Firestore) and
  the two ever disagree about a settled match, that is a settlement-correctness
  incident, and the runtime choice is what caused it.
