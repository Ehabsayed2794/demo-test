# O-06 implementation — Durable Object SQLite storage (slice 1)

**Date:** 2026-11-06
**Status:** **implemented and verified locally. Not deployed.** No Cloudflare account,
token, billing, or plan change was touched. Nothing in this note was produced by a
deployment.
**Builds on:** `Stage0_O06_Authority_Storage_Design.md` (decisions D1–D4),
`Stage0_E3.1_Engine_Seam.md`, `Stage0_E3.1_Worker_Scaffold.md`.
**Plan of record:** ADR 0003 option B / O-06 (2026-10-30) — Workers + Durable Objects
(SQLite) + D1, Free plan.
**Scope:** O-06 **slice 1** only. D1 (ledger / outbox / alarm drain) is slice 2 and is not
implemented here.

---

## 1. Result in one paragraph

The two storage-backed callables (`settleMatch`, `resolveDecisionTimeout`) now execute
against a real Durable Object SQLite database on local workerd. The outer Worker validates
`data.matchId`, addresses one DO with `idFromName("match:" + matchId)`, and calls
`POST /rpc/settle` or `POST /rpc/timeout` over `stub.fetch`. The DO creates its schema
idempotently from the single `DO_SCHEMA` source of truth, re-validates the payload, rejects
a `matchId` that is not its own key, and runs the unchanged Kotlin core through
`DoSettlementDb` / `DoTimeoutDb`. A seeded E2E drives the **production** `/call/*` router
(auth gate, DO hop, callable envelope) and proves corrected settlement, replay,
ROOM no-op, non-player denial, timeout no-write, persisted claim, and one-DO-per-match
isolation.

---

## 2. What was built

### 2.1 `MatchDO`

`native/worker/src/match-do.js`:

- Extends `DurableObject`; exported from `src/index.js` (wrangler resolves DO classes only
  from the entry module).
- Constructor runs `ctx.storage.sql.exec(coreBundle.WorkerApi.DO_SCHEMA)` — idempotent
  `CREATE TABLE IF NOT EXISTS`.
- Stores its own key from `ctx.id.name` (`match:<matchId>`) and refuses a request whose
  `data.matchId` differs. This is defense in depth: the production router derives the key
  from the payload, so a mismatch cannot occur through `/call/*`.
- Accepts only `POST /rpc/settle` and `POST /rpc/timeout`; every other path is `404`.
- Validates `uid` (non-empty string, ≤ 128 chars), `data` (object), and `matchId`
  (printable ASCII, ≤ 128, no path separators) before touching the core.
- Returns the raw core result; the outer Worker maps it onto the callable envelope in one
  place (`unwrapCoreResult`). A thrown DO failure is a `500 INTERNAL` envelope.

### 2.2 Schema

`native/authority-core/src/jsMain/kotlin/com/estemshan/functions/DoSchema.kt` — the one
definition shared by the DO, the Kotlin adapter, and the `node:sqlite` adapter tests:

| Table | Shape |
|---|---|
| `match_state` | `match_id PK, version INTEGER, data TEXT(json), created_at, updated_at` |
| `round_archive` | `PK(match_id, round), data TEXT(json)` |
| `hands` | `PK(match_id, round, seat_id), data TEXT(json)` |
| `settlement_claim` | `match_id PK, data TEXT(json), created_at` |
| `timeout_counts` | `PK(match_id, uid), count INTEGER` |

`data` is the same Firestore-shaped plain map the core already parses — no relational
re-model. `match_state.version` and the JSON blob are always updated in **one** SQL
statement, so the column cannot become a false diagnostic.

### 2.3 Kotlin adapter and atomicity

`native/authority-core/src/jsMain/kotlin/com/estemshan/functions/DoStorage.kt`:

- `DoSettlementDb` / `DoTimeoutDb` implement the existing `SettlementDb` / `TimeoutDb`
  ports; the core is unchanged.
- Every call is synchronous: `sql.exec` is synchronous and the file contains no `await`.
  A whole settle/timeout sequence is therefore one uninterrupted reads → writes stretch,
  atomic by the DO's implicit-transaction semantics (design note D1).
- Sentinel resolution at the DO boundary: `ServerTimestampSentinel` → epoch millis,
  `ArrayUnionSentinel` → the JSON array is extended in place.
- **Fail-closed reads:** corrupt `match_state`, `round_archive`, `hands`, or
  `settlement_claim` rows raise `AuthorityError("failed-precondition", ...)`. A corrupt
  hand card, a hand with no `cards` array, and a hand whose stored round disagrees with
  its row are all rejected — a partial hand would decide tricks on wrong data.

### 2.4 Worker routing and injection

- `WorkerApi.kt` gains `sqlHandle: dynamic` as an explicit third argument on both
  exports. `null` / `undefined` → the existing `UnwiredSettlementDb` /
  `UnwiredTimeoutDb` → `501 UNIMPLEMENTED`. No factory, global, or hidden env plumbing.
  The exports live under the `WorkerApi` namespace (see §2.5).
- `handlers.js` adds `createDoHandlers({ namespace })`:
  - validates `matchId` **before** the DO hop (`400` when absent/invalid);
  - `namespace.get(namespace.idFromName("match:" + matchId))`;
  - `stub.fetch("https://match-do/rpc/settle" | "/rpc/timeout")`;
  - maps DO `{ok:false,error}` onto the callable envelope; unreachable DO → `503
    UNAVAILABLE`; non-JSON/malformed DO result → `500 INTERNAL`;
  - `env.MATCH_DO` absent → the bundle fallback (fail-closed `501`), never a crash.
- `engineSmoke` always calls the bundle directly; it never touches the DO binding.
- `wrangler.toml` carries the `MATCH_DO` binding **and** the
  `[[migrations]] tag = "v1" new_sqlite_classes = ["MatchDO"]` migration.

### 2.5 Export namespace (Firebase collision fix)

`WorkerApi.kt` exports its entry points as members of ONE `WorkerApi` object
(`WorkerApi.engineSmoke`, `WorkerApi.settleMatch`, `WorkerApi.resolveDecisionTimeout`,
`WorkerApi.DO_SCHEMA`) — not as top-level names.

Why: `:functions` depends on `:authority-core`, and Kotlin/JS re-exports a dependency's
`@JsExport` declarations into the consumer bundle (`$jsExportAll$`). With top-level names,
the `:functions` bundle:

1. defined getter-only properties for its own `engineSmoke` / `settleMatch` /
   `resolveDecisionTimeout` callables;
2. called `authority-core.$jsExportAll$(_)`, which assigns the same three names onto the
   same object.

In strict mode that assignment throws
`TypeError: Cannot set property engineSmoke of #<Object> which has only a getter`, so the
Firebase bundle failed at `require()` time — before `index.js`'s trigger guard could run.
The namespace removes the collision structurally: the only top-level export from
`:authority-core` is `WorkerApi`, a name no Firebase callable uses.

`src/engine.js` unwraps the namespace and flattens it onto the handlers' internal engine
interface, so callable names and bundle member names stay decoupled. `src/match-do.js`
reads `coreBundle.WorkerApi.DO_SCHEMA` and calls `WorkerApi.settleMatch` /
`WorkerApi.resolveDecisionTimeout`.

---

## 3. Files

### Added

| File | Purpose |
|---|---|
| `native/worker/src/match-do.js` | `MatchDO`: schema, RPC validation, own-key guard |
| `native/worker/scripts/seed-e2e.mjs` | seeds one fixture per DO via `node:sqlite`; `--verify` |
| `native/worker/scripts/e2e.mjs` | 17-assertion driver over the production `/call/*` router |
| `native/worker/scripts/e2e-entry.mjs` | **test-only** workerd entry with the `e2e:<uid>` verifier |
| `native/worker/wrangler.e2e.toml` | **test-only** local config (same DO binding/migration) |
| `native/authority-core/.../DoSchema.kt` | single `DO_SCHEMA` definition |
| `native/authority-core/.../DoStorage.kt` | DO-backed `SettlementDb` / `TimeoutDb` |
| `native/authority-core/.../AuthorityError.kt` | typed authority error |
| `native/authority-core/.../AuthoritySentinels.kt` | sentinel values at the write boundary |
| `native/authority-core/.../StoredCard.kt` | shared stored-card parser |
| `native/authority-core/.../UnwiredStorage.kt` | deliberate fail-closed ports |
| `native/authority-core/.../WorkerApi.kt` | `WorkerApi` namespace (`engineSmoke`, `settleMatch`, `resolveDecisionTimeout`, `DO_SCHEMA`) |
| `native/authority-core/.../Fixtures.kt` | shared Kotlin test fixtures |
| `native/authority-core/.../DoStorageTest.kt` | 15 SQL-backed adapter tests |
| `native/authority-core/.../CallablePayloadBridgingTest.kt` | 3 JS-object payload tests |
| `native/worker/test/router.test.mjs` | fake-namespace routing/error tests |
| `native/worker/test/functions-bundle.test.mjs` | `:functions` bundle load + `__trigger` regression guard |

### Modified

| File | Change |
|---|---|
| `native/worker/src/app.js` | `resolveHandlers({ env, fallback })`, last-resort envelope, verifier `503` |
| `native/worker/src/handlers.js` | `createDoHandlers`, `unwrapCoreResult`, explicit `sqlHandle` |
| `native/worker/src/index.js` | exports `MatchDO` |
| `native/worker/src/callable.js` | `isValidMatchId`, envelope helpers |
| `native/worker/wrangler.toml` | DO binding + SQLite migration |
| `native/worker/package.json` | `dev:e2e`, `e2e`, `e2e:preflight`, `e2e:seed`, `e2e:verify`, `e2e:verify-settled` |
| `native/worker/README.md` | O-06 slice 1 status, wiring, E2E procedure |
| `native/authority-core/.../Settlement.kt`, `SettlementDb.kt`, `TimeoutResolution.kt`, `TimeoutResolutionDb.kt` | port-based refactor; behaviour unchanged |
| `native/authority-core/.../SettlementTest.kt`, `TimeoutResolutionTest.kt` | adapted to the ports; coverage retained |

---

## 4. E2E procedure (re-runnable)

Prerequisites: Node 22.14.0, wrangler 4.155.0 (local), the `:authority-core` bundle built,
port 8799 free. Use a **throwaway** state directory; the seed script resets its five
authority tables.

1. Build the bundle:
   `cd native && .\gradlew.bat :authority-core:jsProductionExecutableCompileSync`
2. Fresh state:
   `D:/cache/stage0/<run>-state` (the `--persist-to` argument is `<run>-state/sm`).
3. Boot #1 with the test-only config:
   `wrangler dev --local --ip 127.0.0.1 --port 8799 --config wrangler.e2e.toml --persist-to <run>-state/sm`
4. Preflight (creates the three DOs; an empty store answers `404 NOT_FOUND`):
   `node scripts/e2e.mjs --preflight`
5. Stop wrangler; confirm port 8799 is closed.
6. Seed:
   `node scripts/seed-e2e.mjs <run>-state`
7. Verify isolation (before the assertions):
   `node scripts/seed-e2e.mjs --verify <run>-state`
8. Boot #2 (same command as step 3).
9. Assertions:
   `node scripts/e2e.mjs`
10. Stop wrangler; verify the persisted outcome:
    `node scripts/seed-e2e.mjs --verify --expect-settled <run>-state`
11. Delete the state directory and the wrangler config/cache dirs.

### Why `__miniflare_do_name`

The DO SQLite file name is a hash; this script does **not** re-implement workerd's ID
derivation. Miniflare writes the `idFromName()` name into each DO's own
`__miniflare_do_name` table, so the seeder reads the name from the file and seeds exactly
that match's fixture. A DB with the match schema but no single recorded name is an error,
never a bucket for every fixture. The E2E's `NOT_FOUND` probe creates a fourth, empty DO;
`--verify` skips a DO whose five tables are all empty and fails on any DO that holds data
but has no fixture.

### Test-only boundary

`scripts/e2e-entry.mjs` and `wrangler.e2e.toml` are never deployed and are not selected by
`wrangler.toml`. The production entry keeps the default verifier that rejects every
request. There is no env var or flag in `src/` that enables the `e2e:<uid>` token.

---

## 5. Evidence (run 2026-11-06, local)

### 5.1 Unit / integration suites

| Suite | Result |
|---|---|
| `native/worker` `node --test` | **44/44**, 0 failures, 0 skipped |
| `:authority-core:jsNodeTest` | **94** (3 + 18 + 28 + 45), 0 failures/errors, 0 skipped |
| `:functions:jsNodeTest` | **13** (5 + 5 + 3), 0 failures/errors, 0 skipped |
| **Total** | **151**, 0 failures, 0 skipped |

The 18 `DoStorageTest` cases include the three fail-closed tests (corrupt archive,
corrupt hand, unreadable card) and three full timeout-resolution tests through real
SQLite (expired BIDDING resolves and bumps version/counter, unexpired writes nothing,
corrupt hand fails closed). The 44th Worker test is the `:functions` bundle load +
`__trigger` regression guard (§5.4).

### 5.2 Seeded E2E through the production `/call/*` router

`node scripts/e2e.mjs` → **17 passed, 0 failed**:

```
PASS  GET /health -> 200 with the callable manifest
PASS  no bearer token -> 401 UNAUTHENTICATED
PASS  unknown callable -> 404 NOT_FOUND
PASS  empty body -> 400 INVALID_ARGUMENT
PASS  missing matchId -> 400 INVALID_ARGUMENT
PASS  matchId with a path separator -> 400 INVALID_ARGUMENT
PASS  engineSmoke -> 52 unique cards (direct bundle path, no DO)
PASS  ROOM settle -> 200 no-op (NOT_RANKED)
PASS  incomplete RANKED settle -> 400 FAILED_PRECONDITION
PASS  non-player settle -> 403 PERMISSION_DENIED
PASS  non-player denial happens before any write
PASS  golden settle -> corrected finalScores
PASS  replay -> replayed:true with the same corrected scores
PASS  timeout with no pending decision -> NOT_ACTIVE no-write
PASS  non-player timeout -> 403 PERMISSION_DENIED
PASS  unknown match timeout -> 404 NOT_FOUND
PASS  DO rejects a matchId that is not its own key
```

Golden corrected result observed:

```json
{"ok":true,"settled":true,"replayed":false,"matchId":"e2e-golden","corrected":true,
 "finalScores":{"p1":25,"p2":-11,"p3":12,"p4":14},"winnerIds":["p1"],
 "claimedScores":{"p1":25,"p2":-1,"p3":12,"p4":14},"claimedWinners":["p1"],"rounds":1}
```

### 5.3 Persisted outcome

`node scripts/seed-e2e.mjs --verify --expect-settled <state>` → exit 0:

- `match:e2e-room` → one `match_state` row (`e2e-room`, v7), no archive/claim/hands/timeouts.
- `match:e2e-incomplete` → one `match_state` row (`e2e-incomplete`, v7), nothing else.
- `match:e2e-golden` → one `match_state` row (`e2e-golden`, **v8**), one round-1 archive,
  exactly **one** `settlement_claim`, corrected `finalScores` `{25,-11,12,14}`.
- No foreign rows in any DO; the empty `e2e-missing` probe DO is skipped.
- The claim lives only in the golden DO file; the room and incomplete files have none.

### 5.4 Firebase bundle collision (found and fixed)

Before the namespace fix, requiring the built `:functions` bundle (with the Firebase SDKs
stubbed; no emulator, no credentials) failed at load time:

```
LOAD FAILED: TypeError: Cannot set property engineSmoke of #<Object> which has only a getter
    at Object.$jsExportAll$ (.../authority-core.js:5090:19)
    at .../functions.js:1314:35
```

After the fix, the same probe reports:

```
LOADED OK
  engineSmoke: function __trigger=true
  settleMatch: function __trigger=true
  resolveDecisionTimeout: function __trigger=true
  WorkerApi: object DO_SCHEMA=string members=engineSmoke,settleMatch,resolveDecisionTimeout
  index.js manifest keys: ["engineSmoke","settleMatch","resolveDecisionTimeout"]
```

The regression test `native/worker/test/functions-bundle.test.mjs` now pins this in the
normal `node --test` run: it loads the real bundle with stubbed SDKs, asserts the three
Firebase callables still carry `__trigger`, asserts the `WorkerApi` namespace exists, and
asserts the Firebase callables are not authority-core's members. It skips (and reports the
skip) when the `:functions` bundle has not been built.

This is a **load-time crash**, not just an export-precedence preference: `index.js`
requires the bundle at module load, so any Firebase emulator start or deploy would have
died before the trigger guard ran. The fix is in `WorkerApi.kt` (§2.5), not in the
Firebase callables.

---

## 6. Failure behaviour (fail closed)

| Condition | Behaviour |
|---|---|
| `env.MATCH_DO` not bound | bundle fallback → `501 UNIMPLEMENTED` |
| `matchId` missing/invalid at the edge | `400 INVALID_ARGUMENT`, no DO hop |
| DO unreachable / throws on fetch | `503 UNAVAILABLE` envelope |
| DO returns non-JSON or non-object | `500 INTERNAL` envelope |
| `uid` missing/empty/too long in the DO | `401 UNAUTHENTICATED` |
| `data` not an object in the DO | `400 INVALID_ARGUMENT` |
| `data.matchId` ≠ the DO's own key | `400 INVALID_ARGUMENT` |
| corrupt match / archive / hand / claim | `failed-precondition`, no write |
| unwired bundle (`sqlHandle` null) | `501 UNIMPLEMENTED` |

---

## 7. Explicitly not covered (slice 2+)

- D1 `ranked_events`, `match_outbox`, `alarm()` drain, `event_key` dedupe, bounded backoff.
- Production token verifier + structured loader/verifier logging.
- Staging deployment (needs explicit owner approval; no `account_id`/routes exist).
- Migrating existing Firestore matches to DO (separate migration plan).
- Firebase `index.js` UMD/ESM trap — **pre-deploy blocker** on the Firebase path.
- The Firebase Admin adapter still uses `mapNotNull` on stored cards (pre-existing
  behaviour, unchanged by O-06). The DO adapter is now strict; aligning the Admin adapter
  is a follow-up, not part of this slice.

---

## 8. SHA-256 appendix

Hashes of the files that define the verified behaviour (SHA-256, uppercase):

| File | SHA-256 |
|---|---|
| `native/worker/src/match-do.js` | `4AF5FDAAD6A2DA46A56E1AC12365D8A31413E5365B6C7046E3F766534925D25C` |
| `native/worker/src/handlers.js` | `A240B5EFCD69C996841C31216C1925B8A89B2E6541A794B3074D496DD40E3797` |
| `native/worker/src/app.js` | `B02D2A51376855C488D52CD0F9438DF3834789EEE88A9F6D50D674A795C66ED2` |
| `native/worker/src/engine.js` | `F346001F32A75E0D3C3F100D9FA44764799E87B623825821062566430C38CA5F` |
| `native/worker/scripts/seed-e2e.mjs` | `A0CD1572A10D880D53449DAB279C6EDEA9D59C2A82E3E39E6D17841BDE56D8FC` |
| `native/worker/scripts/e2e.mjs` | `9373A85882169D0A5F663C1431A834FF8C544ED46308BF712D8065EB8E646DFA` |
| `native/worker/test/functions-bundle.test.mjs` | `8C8A7F4E8E2C975A60FE23F5E705DB423153B031FA16394344E599A4E43722F5` |
| `native/authority-core/src/jsMain/kotlin/com/estemshan/functions/DoStorage.kt` | `E57B1AE46ADEBE91AEFFEB2BED745845AA99A4ED346AE1D4F89B1B13EC82F6F4` |
| `native/authority-core/src/jsMain/kotlin/com/estemshan/functions/DoSchema.kt` | `43C8B9741F95159EB5DB93037BA5B4E21BA9E7A36E7A722B3AD02D9DFA4A4C41` |
| `native/authority-core/src/jsMain/kotlin/com/estemshan/functions/WorkerApi.kt` | `3846E49F0DBE423AFF7FBA27486DB1FA830E79E07453DE2133D59CC0A3359C73` |
