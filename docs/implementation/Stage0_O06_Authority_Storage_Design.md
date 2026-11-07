# O-06 design note — authority storage for the Cloudflare runtime

**Date:** 2026-11-06
**Status:** **approved with amendments (2026-11-06) — implementing slice 1.**
Nothing here is deployed or provisioned; no account, token or billing is touched, and
implementation proceeds only under the recorded constraints in §4.
**Builds on:** `Stage0_E3.1_Engine_Seam.md` (the engine seam, reviewed & accepted) and
`Stage0_E3.1_Worker_Scaffold.md` (the shell).
**Plan of record:** ADR 0003 option B / O-06 (2026-10-30) — Workers + Durable Objects
(SQLite) + D1, Free plan.
**Decides:** the four points the reviewer put on the table before any O-06 code, plus
the first slice's scope, the staging boundary, and three owner questions.

---

## 1. The four decisions

### D1 — DO transaction vs suspend port: **no signature change; adapter is fully synchronous; atomicity falls out of "reads → writes with no intervening await"**

**The platform facts (Cloudflare docs, fetched 2026-11-06):**

- `ctx.storage.sql.exec()` is **synchronous** (`SqlStorageCursor`). Cursors must be
  consumed inline (`.toArray()`), never held across an `await`.
  [sqlite-storage-api → exec](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api)
- Every storage method is implicitly wrapped in a transaction (per-call atomicity), but
  the important guarantee is composite:

  > "Any series of write operations with no intervening `await` will automatically be
  > submitted atomically, and the system will prevent concurrent events from executing
  > while `await` a read operation \[…\] a series of reads followed by a series of
  > writes (with no other intervening I/O) are automatically atomic and behave like a
  > transaction."

  [sqlite-storage-api → transaction()](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/#transaction)
- `transactionSync(callback)` exists, but its callback must complete **synchronously**
  (only `sql.exec()` and synchronous KV may be inside it) — a Kotlin/JS `promise { }`
  coroutine never qualifies. [transactionSync](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/#transactionsync)
- `BEGIN TRANSACTION` / `SAVEPOINT` cannot be run through `sql.exec`. D1 has no
  interactive transactions at all — the atomic unit there is a single statement or a
  `db.batch()`. [d1-database](https://developers.cloudflare.com/d1/worker-api/d1-database)

**The mechanical fact:** a Kotlin/JS coroutine always surfaces to JS as a `Promise` —
there is no way to invoke one and synchronously get its result, and no way to run one
"inside" a synchronous JS callback like `transactionSync`. Therefore **a Kotlin suspend
block can never execute inside `transactionSync`** — and Firebase's `runTransaction` has
no DO counterpart either (no closure). Note also (measured on this build):
`Dispatchers.Default` is a `NodeDispatcher` whose first dispatch goes through
`setTimeout(0)` — the coroutine is scheduled **once, before the first read**. It does
**not** yield inside the critical region; it just places the whole run after one tick,
which is exactly why the real invariant is rule 1 below.

The correct seam is not a wrapper; it is **the absence of actual suspension inside the
critical region**:

1. The DO handler does one `await` — the core call.
2. Every port call underneath is fully synchronous Kotlin over straight-line JS SQL —
   **zero `await` between the first read and the last write** → the whole sequence
   (pre-check reads → recompute → claim re-read → writes) executes without yielding to
   the event loop → no other request interleaves → atomic by the platform rule above.
   One `setTimeout(0)` happens BEFORE the first read; never between them.
3. If an actual suspension ever appears inside a region (e.g. a surviving await in a
   future edit), the **claim re-read protocol** still bounds the blast radius — the
   decision never double-applies; it just loses gapless isolation for that call.
   `blockConcurrencyWhile` around the handler is an accepted, cheap extra belt if
   desired (not required for correctness).

**Written rules (go in code review checklists):**
1. DO port methods never introduce an `await` between their first read and their last
   write; cursors drain inline only.
2. `allowConcurrency: true` is forbidden in authority-storage paths.
3. Only SQL may cross the critical region. No KV, no fetch, no async storage.
4. Any PR that violates 1–3 fails review regardless of tests passing.

**Rejected alternatives:**
- *Non-suspend ports + async shims for Firebase* — duplicates the orchestration, drifts,
  violates RD12. Firebase path would fork for zero benefit.
- *`transactionSync` around the Kotlin coroutine* — impossible (suspend ⇒ Promise), and
  SQL can't join it anyway.
- *Re-implementing the write path in JS inside the DO* — drift risk no local test can
  meaningfully pin; RD12 forbids re-implementation of decision logic.

**Consequences:** `SettlementDb` / `TimeoutDb` signatures **stay as they are**; the
Firebase adapter is untouched; `WorkerApi` gains an injected storage argument (D2).

---

### D2 — Storage injection: **explicit `sqlHandle` argument on the two DB-backed exports; unsigned means unwired**

```ts
// Kotlin @JsExport (WorkerApi.kt), the only change to the surface:
settleMatch(uid: String, data: dynamic, sqlHandle: dynamic): Promise<dynamic>
resolveDecisionTimeout(uid: String, data: dynamic, sqlHandle: dynamic): Promise<dynamic>
```

> **Implementation note (2026-11-06):** these entry points are exported as members of a
> single `WorkerApi` object — `WorkerApi.settleMatch(...)`, not top-level names. Kotlin/JS
> re-exports a dependency's `@JsExport` declarations into the `:functions` bundle, so
> top-level names collided with the Firebase callables and made the Firebase bundle throw
> at `require()` time. The namespaced export is collision-proof. See
> `Stage0_O06_DO_D1.md` §5.4.

- `sqlHandle` is **`dynamic`** (the raw `ctx.storage.sql` JS object), never a Kotlin
  interface — a plain JS object cannot satisfy one. `null`/`undefined` means unwired.
- **The caller always passes the argument explicitly** — no default value is relied on
  across the JS boundary (defaults don't survive external calls reliably); the Worker's
  handlers pass `null` on purpose when no DO/storage is available.
- **Omitted / null** → the existing `UnwiredSettlementDb` / `UnwiredTimeoutDb` answer
  (`unimplemented` → 501). Today's HTTP `/call/*` behaviour is unchanged, and so is every
  test that relies on it.
- **Present** → the handle is wrapped by the Kotlin adapter `DoSettlementDb` /
  `DoTimeoutDb` and passed into the unchanged core.
- **No factory, no global, no hidden env plumbing.** The handle is passed by the caller,
  per request. This is the same injection style the Worker already uses for
  `verifyToken`, and it works identically under plain `node --test` with a **JS fake**
  (a hand-rolled `exec` over in-memory tables) — coverage without workerd.
- The Firebase path never touches `WorkerApi`; it keeps its Admin adapters.

**`wrangler.toml` must carry the DO binding *and* a SQLite migration**, otherwise the
DO is not created locally (and the deploy would fail later):

```toml
[[durable_objects]]
bindings = [ { name = "MATCH_DO", class_name = "MatchDO" } ]

[[migrations]]
tag = "v1"
new_sqlite_classes = ["MatchDO"]
```

`MatchDO` is exported from `src/index.js` (wrangler resolves DO classes only from the
entry module).

**How the outer Worker reaches a DO:** `/call/settleMatch` and
`/call/resolveDecisionTimeout` become **routers**: validate `data.matchId` (400 when
absent — before any DO hop), then `env.MATCH_DO.get(idFromName("match:" + matchId))` and
`stub.fetch("/rpc/settle"|"/rpc/timeout")`. The DO is reachable **only** via stub.fetch
from this Worker — never as public HTTP. `engineSmoke` stays a direct bundle call.

**Kotlin-side or JS-side adapter?** Kotlin (`DoSettlementDb`/`DoTimeoutDb` inside
`:authority-core`), calling `sqlHandle.exec(...)` through `dynamic` interop — the JS side
is a dumb SQL channel with zero logic; everything worth testing stays in Kotlin and is
already covered by the existing core suites.

---

### D3 — D1 outbox: **DO SQLite is the system of record; D1 is an at-least-once replica; guarantee written here**

**Guarantee (verbatim):**

> If a settle or timeout decision commits in a match's Durable Object, then it appears
> in D1 exactly once, eventually. Between the DO commit and a healthy D1 drain, D1 may
> lag, but it never gates, blocks or corrupts settlement: the DO serves from its own
> SQLite throughout. No committed decision is ever lost; no uncommitted decision ever
> reaches D1; duplicates are absorbed by the `event_key` unique constraint.

**Mechanism (slice 2, not slice 1):**

1. The DO commits the decision in its synchronous run, and in the **same stretch** inserts
   the outbox row into `match_outbox` (inside the match's DO SQLite — atomic with the
   write, by the same reads→writes no-intervening-await rule).
2. Every enqueue `setAlarm(now + 1s)` (coalesced by storage — repeated sets replace).
3. `alarm()` drains in one D1 `db.batch()`:
   `INSERT INTO ranked_events(event_key, kind, match_id, payload, occurred_at)
    VALUES (?, ?, ?, ?, ?) ON CONFLICT(event_key) DO NOTHING`
   where `event_key = "settle:{matchId}"` / `"timeout:{matchId}:{version}"` and `version`
   is the **post-decision** document version — two different decisions can never collide,
   and the same decision re-delivered maps to the same key (the outbox stores that
   version in its row; the schema is written out with slice 2).
   `batch()` is one D1 transaction, all-or-nothing; the dedupe constraint makes redelivery
   harmless. Success → delete drained rows from `match_outbox`; failure → leave rows,
   re-arm alarm with capped backoff (1s → 2s → … → 5min cap), alert log line.
4. Crash between DO commit and drain: outbox row persists; next alarm re-delivers. No
   data loss, no code path that reads D1 for authority.

**Explicitly not promised:** read-your-writes on D1; cross-match event ordering; D1 as a
backup of record (the DO's own PITR — 30 days — covers restore; out of scope here).
**D1 is never queried by the authority path.**

Slice 2 scope: `match_outbox` table + alarm drain + `ranked_events` schema + one
integration test on local workerd with D1 bound locally. Slice 1 creates no D1 resources.

---

### D4 — First slice (O-06, this step): **DO schema + DO adapters + seeded-match proof on local workerd**

**In scope:**

1. `MatchDO` (SQLite-backed class) exported from `native/worker/src/index.js`; bound in
   `wrangler.toml` as `MATCH_DO`.
2. Idempotent schema in the DO constructor:
   | Table | Shape |
   |---|---|
   | `match_state` | `match_id PK, version INTEGER, data TEXT(json), created_at, updated_at` |
   | `round_archive` | `PK(match_id, round), data TEXT(json)` |
   | `hands` | `PK(match_id, round, seat_id), data TEXT(json)` |
   | `settlement_claim` | `match_id PK, data TEXT(json), created_at` |
   | `timeout_counts` | `PK(match_id, uid), count INTEGER` |
   | `match_outbox` | **slice 2 only** |

   `data` = the same Firestore-shaped plain maps the core already parses — **no
   relational re-model**; row counts are trivial (≤ 20/match); JSON round-trips through
   `dynamicToKotlin`/`toPlain` untouched. `version` is duplicated into a column and
   **always updated in the SAME SQL statement as the JSON write** — a separate "update
   version" statement could fail halfway and leave a false diagnostic.
3. `DoSettlementDb` / `DoTimeoutDb` in `:authority-core` (Kotlin, dynamic `sql.exec`
   interop). Sentinel resolution at the DO boundary: `serverTimestamp()` → epoch-millis
   bound by the adapter; `arrayUnion` → the match JSON's `biddingLog`/`cardLog` arrays
   are extended in place (element already plain via `toPlain`).
4. `WorkerApi` gains the `sqlHandle: dynamic` argument (D2). Unwired default unchanged.
   Callers always pass it explicitly (`null` when unbound) — no reliance on JS-visible
   defaults.
5. Outer Worker routes the two storage-backed callables into the DO (D2 — **approved,
   see §4.1**). `engineSmoke` unchanged.
6. Verification, no deploy:
   - unit: JS-fake `exec` storage → full lifecycle: corrected settle (claim written),
     replay (exactly-once), ROOM no-op, non-player `permission-denied`, timeout bid write,
     15th-timeout removal, malformed archive fail-closed. All against the **real core**.
   - integration: `wrangler dev --local --persist-to <fixed dir>`; **seed via an external
     Node script using `node:sqlite`** (available in Node 22.14.0, warning only, no flag)
     writing the same rows the adapter reads — **no seed/test-only routes inside the
     DO**. Procedure: boot once (DO constructs + creates schema) → stop wrangler →
     seed → boot → drive `/call/*` assertions → stop, delete state, ports verified
     free. The seed script is **committed** (`native/worker/scripts/seed-e2e.mjs`) so
     the E2E is re-runnable. The E2E asserts the deterministic paths (ROOM no-op,
     RANKED-not-complete `failed-precondition`, NOT_ACTIVE timeout, non-player
     `permission-denied`, 400 shapes) — the full golden recompute stays proven by the
     76 Kotlin tests; there is intentionally **no** attempt to port the 52-play fixture
     to JS by hand.
   - all existing suites stay green; `node --test` target ≥ 32 checks.
7. Docs: this note extends into `Stage0_O06_DO_D1.md` after the owner reviews; README
   updated; SHA-256 appendix; no `account_id`, no routes, no `deploy`.

**Explicitly out of scope (slice 2+):** D1 / `match_outbox` / alarm drain (D3), token
verifier wiring + structured loader/verifier logging (bears the reviewer's note #1 and
#2), Firebase `index.js` UMD/ESM trap — **pre-deploy blocker** on the Firebase path,
unrelated to O-06, must be fixed before any Firebase deploy, PITR runbook, staging
deploy (needs owner's explicit approval), migrating existing Firestore matches → DO
(separate migration plan, its own review).

---

## 2. What the reviewer flagged, mapped

| Reviewer note | Where it stands |
|---|---|
| 1. `verifyToken` throw → bare 500 | **Fixed** (`native/worker/src/index.js` + 1 test; 32/32 green; 503 `UNAVAILABLE` JSON envelope) |
| 2. silent loader/guard | **Carried into slice 3** with the verifier wiring: structured log line on load failure and on verifier failure-open; not in this design note's code |
| 3. Firebase `index.js` UMD trap | **Acknowledged — pre-deploy blocker**; fix belongs to the Firebase deploy hardening before any deploy; not O-06 |
| 4. one-off workerd smoke | Accepted as evidence; permanent proof = `test/engine.test.mjs`; the seeded-match workerd run in this slice is repeatable (seed script committed or documented) |

Also added: last-resort JSON envelope on the whole `fetch` (defense-in-depth; **no
dedicated test — it guards paths unreachable through the public API today**; stated
plainly rather than claimed as covered).

---

## 3. Free plan / ops notes (informational)

- DO SQLite storage billing began **2026-01-07**; reads/writes counted per row; our
  volumes are ~1 claim + ≤ 20 row writes per settle — noise next to the Free allowances.
- PITR covers accidental DO corruption (30 days); not a D1 substitute, not in scope.
- No D1 provisioned; nothing for the billing to attach to in slice 1.

---

## 4. Recorded decisions (review 2026-11-06)

The reviewer approved the design with two corrections (applied above) and these rulings:

1. **Route into `MATCH_DO` — APPROVED**, with six constraints:
   a. the outer Worker parses `matchId` before any DO hop; `400 INVALID_ARGUMENT` when
      absent, not a string, or longer than a sane cap (we use ≤ 128 chars, printable
      ASCII, no control chars or `/`);
   b. the DO accepts `POST /rpc/settle` and `POST /rpc/timeout` only — any other path
      answers `404`; the DO is never publicly routable (stub.fetch only);
   c. auth stays at the outer Worker; the DO receives a trusted `uid` and re-validates
      its shape (non-empty string, `data` is a plain object) and fails closed otherwise;
   d. error mapping lives in exactly one place (the outer handler) — the DO returns the
      core's raw `{ ok, error? }` result;
   e. `env.MATCH_DO` unbound → falls back to the bundle's unwired path →
      `501 UNIMPLEMENTED`, never a crash;
   f. tests: a fake `MATCH_DO` binding for the router (validation, routing, envelope
      mapping, binding-absent fallback) **plus** the seeded workerd E2E; `engineSmoke`
      stays direct.
2. **DO routing key** — `idFromName("match:" + matchId)` **approved**: one DO per
   match, deterministic and sticky; the matchId is used raw and case-sensitive, with a
   simple max length; `newUniqueId()` is forbidden.
3. **Seed via `node:sqlite`** — **approved**: works in Node 22.14.0 with no flag
   (ExperimentalWarning only). The procedure is fixed: **boot once (DO constructs and
   creates the schema) → stop wrangler → seed → boot → assert → stop**; `--persist-to`
   points at a fixed throwaway directory; seeding happens with wrangler stopped (no
   `SQLITE_BUSY`); the seed script is **committed** (`native/worker/scripts/seed-e2e.mjs`)
   so the E2E is repeatable.
4. **`version` as a SQL column** — approved, and it is always updated in the **same
   statement** as the JSON blob (never a separate statement).
5. Corrections applied in this revision: the Kotlin dispatch wording (one
   `setTimeout(0)` **before** the first read, never between reads and writes — the
   atomicity condition is rule 1 in §1/D1), the `[[migrations]]` requirement for the DO
   binding, `sqlHandle` as **`dynamic`, passed explicitly by callers** (no reliance on
   JS-visible default arguments), and the D3 `event_key` version being the
   **post-decision** document version, defined in the schema.

**Staging stays untouched until you say so.** No deploy, no account, no token, no billing.
