# native/worker — Cloudflare Worker + MatchDO (E3.1 + O-06 slice 1)

**Status: local only. Nothing here is deployed. No Cloudflare account, token, or
billing is involved.**

The approved runtime is Cloudflare Workers + Durable Objects (SQLite) + D1 on the Free plan
(ADR 0003 option B, closed by O-06 on 2026-10-30). This package is the second slice of
E3.1 plus **O-06 slice 1**: the Worker boots with the shared Kotlin authority core linked
in — the same `:engine` the JVM tests and the Firebase callables re-run — and the two
storage-backed callables now execute against a **real Durable Object SQLite database**
through the unchanged `SettlementDb` / `TimeoutDb` ports.

D1 (ledger / outbox) is **not** implemented; it is O-06 slice 2. The DO's SQLite is the
system of record and the authority path never reads D1.

## What this slice proves

| # | Claim | Evidence |
|---|---|---|
| 1 | The Worker boots under workerd with no bindings and no credentials | local `wrangler dev --local` smoke (this file + `docs/implementation/Stage0_E3.1_Engine_Seam.md`) |
| 2 | The callable table matches the Kotlin `@JsName` surface and the Firebase export map | `test/manifest.test.mjs` |
| 3 | Unauthenticated requests are rejected **before** a handler runs | `test/dispatch.test.mjs`, `test/engine-wiring.test.mjs` |
| 4 | The wire format is the Firebase callable envelope the Android client already speaks | `test/dispatch.test.mjs` |
| 5 | `engineSmoke` returns the **real** engine's deal (4×13 seeded, 52 unique cards) | `test/engine.test.mjs` (skips, does not fake, when the bundle is absent) |
| 6 | `settleMatch` / `resolveDecisionTimeout` route to `MATCH_DO` by `idFromName("match:" + matchId)` when the binding is present, and stay fail-closed `501` when it is absent | `test/router.test.mjs` |
| 7 | The DO's SQLite adapter runs the real core against real SQL: corrected settle, replay, ROOM no-op, non-player denial, corrupt-row fail-closed | `native/authority-core/src/jsTest/.../DoStorageTest.kt` |
| 8 | A seeded match round-trips through the **production** `/call/*` router under workerd: auth, DO hop, Kotlin core, SQLite, persisted claim | `scripts/e2e.mjs` (17 assertions) + `scripts/seed-e2e.mjs --verify --expect-settled` |
| 9 | One DO per match: each seeded SQLite file holds exactly its own fixture, with no foreign rows | `scripts/seed-e2e.mjs --verify` |
| 10 | The `:functions` Firebase bundle still loads, and every callable keeps its `__trigger` — the Worker API's `WorkerApi` namespace cannot collide with the Firebase export names | `test/functions-bundle.test.mjs` (skips honestly when the functions bundle is unbuilt) |

## What this slice does **not** prove

- **No D1.** There is no D1 binding, no outbox, no alarm drain. D1 is O-06 slice 2 and is
  never queried by the authority path.
- **No production token verification.** The default verifier rejects every request. The
  seeded E2E uses `scripts/e2e-entry.mjs`, a **test-only** entry whose `e2e:<uid>` token
  exists only for the local run; the production entry (`src/index.js`) is unchanged and
  still rejects every request.
- **No staging deployment.** No `account_id`, no routes, no `wrangler deploy`. A staging
  deployment needs explicit owner approval and a separate safety review.
- **No Firebase `index.js` UMD/ESM fix.** It remains a pre-deploy blocker on the Firebase
  path, unrelated to O-06.
- **No structured loader/verifier logging.** It lands with the production verifier.

## Routes

| Method | Path | Response |
|---|---|---|
| `GET` | `/health` | `200 { ok, service, runtime, callables }` |
| `POST` | `/call/<name>` | `200 { result }` or `{ error: { status, message, details? } }` |
| anything else | any | `404` / `405` callable envelope |

The callable request is `POST` with `Authorization: Bearer <token>` and a JSON body
`{ "data": ... }`. The response is `{ "result": ... }` on success or
`{ "error": { "status": "<canonical code>", "message": "...", "details": ... } }` with a non-2xx
status on failure. This is the Firebase callable protocol; the Android client does not need a
protocol rewrite when the runtime moves.

`MatchDO` is **not** publicly routable. It accepts only `POST /rpc/settle` and
`POST /rpc/timeout` from the Worker's `stub.fetch`, re-validates `uid` / `data` / `matchId`,
and rejects a payload whose `matchId` is not its own `idFromName` key.

## Building the engine bundle

The Worker imports the `:authority-core` production bundle — a build output, **not**
committed. Build it once after any core change:

```powershell
cd native
.\gradlew.bat :authority-core:jsProductionExecutableCompileSync
```

This also copies a `package.json` (no `"type"` field) into the bundle directory so the
repo-root `"type": "module"` does not reinterpret the UMD/CommonJS bundle as ESM. Without
that marker every `require()` of the bundle dies with a `this['kotlin-kotlin-stdlib']`
TypeError.

`native/worker` never loads the bundle at boot. `src/engine.js` does a lazy
`import("<literal bundle path>")` on the **first callable request** and caches the result;
bundlers (wrangler/esbuild) can inline it statically, Node treats a missing bundle as a
rejected promise, which becomes a `501 UNIMPLEMENTED` — fail closed, never a fake.

## Commands

Run the tests from this directory with the approved Node 22 binary:

```powershell
& "C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" --test
```

No dependencies, no network. The tests use only Node built-ins (`node:test`, `node:assert`,
`node:fs`, `node:path`, `node:url`) and Node 22's global `Request`/`Response`.
`test/engine.test.mjs` skips (and reports the skip) when the bundle has not been built —
it never fabricates a pass.

Run the local Worker (no login, no token, no deployment) — **requires the bundle** for
engine calls to do anything but fail closed:

```powershell
wrangler dev --local --ip 127.0.0.1 --port 8799
```

Then:

```powershell
Invoke-RestMethod http://127.0.0.1:8799/health
```

A call without a token returns `401 UNAUTHENTICATED`; the same call with any token also
returns `401` until a real verifier is injected.

## Seeded E2E (O-06 slice 1)

The full procedure is documented in `docs/implementation/Stage0_O06_DO_D1.md`. Summary:

1. Build the bundle (above).
2. Start a **fresh** state dir with the test-only config:
   `wrangler dev --local --config wrangler.e2e.toml --persist-to <state>/sm`
3. Create the three DOs: `node scripts/e2e.mjs --preflight` (expected `404` on an empty
   store — the DO is constructed either way).
4. Stop wrangler; confirm port 8799 is free.
5. Seed: `node scripts/seed-e2e.mjs <state>` — maps each SQLite file back to its match via
   miniflare's `__miniflare_do_name` table, resets the five authority tables, and writes
   **one** fixture per DO.
6. Verify isolation: `node scripts/seed-e2e.mjs --verify <state>`.
7. Start wrangler again; run `node scripts/e2e.mjs` (17 assertions through `/call/*`).
8. Stop; `node scripts/seed-e2e.mjs --verify --expect-settled <state>` (golden claim,
   version 8, corrected scores, no foreign rows).
9. Delete the throwaway state dir.

`scripts/e2e-entry.mjs` and `wrangler.e2e.toml` are **test-only**: never deployed, never
selected by the production config. `node:sqlite` is experimental in Node 22.14 (warning
only, no flag).

## How the wiring works

- `src/engine.js` — lazy loader + `wrap` that validates the bundle's `WorkerApi`
  namespace and its three entry points (`engineSmoke`, `settleMatch`,
  `resolveDecisionTimeout`), then flattens them onto the handlers' internal interface.
  The namespace exists because `:functions` re-exports `:authority-core`'s `@JsExport`
  declarations; top-level names collided with the Firebase callables and made the
  Firebase bundle throw at `require()` time (see
  `docs/implementation/Stage0_O06_DO_D1.md` §5.4).
- `src/handlers.js` — `createHandlers({ engine })` for the direct bundle path and
  `createDoHandlers({ namespace })` for the DO path. `engine: null` pins the pre-seam
  fail-closed table (used by the tests). `auth.uid` must be a non-empty string or the call
  is rejected `401` before the engine runs. The DO path validates `matchId` before the hop,
  maps DO failures onto the callable envelope in one place, and returns `501` when
  `env.MATCH_DO` is absent.
- `src/match-do.js` — the `MatchDO` class: idempotent `WorkerApi.DO_SCHEMA` on first
  construction, only `/rpc/settle` and `/rpc/timeout`, own-key guard, all storage through
  the Kotlin adapter via `sqlHandle`.
- `src/index.js` — exports the application and the `MatchDO` class (wrangler discovers DO
  classes only from the entry module).
- `native/authority-core/.../DoStorage.kt` — `DoSettlementDb` / `DoTimeoutDb`: synchronous
  `sql.exec` calls, no `await` between the first read and the last write, JSON blobs in the
  same Firestore shape the core parses. Corrupt match / archive / hand / claim rows fail
  closed (`failed-precondition`), never silently skipped.

## Safety

- No `CLOUDFLARE_API_TOKEN` is read, printed, or stored by this package.
- No billing is enabled and no plan is changed.
- No network access is needed for the tests; local `wrangler dev` listens on `127.0.0.1` only.
- `.wrangler/` and `node_modules/` are gitignored, and so is the bundle (`../authority-core/build/`).
- `wrangler.toml` deliberately has no `account_id`, routes, vars, or secrets.

## Next slice (O-06 slice 2)

1. D1 `ranked_events` schema + `match_outbox` + alarm drain (`event_key` UNIQUE,
   `ON CONFLICT DO NOTHING`, bounded backoff) per design note D3.
2. Production token verifier + structured loader/verifier logging.
3. Firebase `index.js` UMD/ESM fix — **before any Firebase deploy**.
4. Ask the owner for explicit approval before any staging deployment.

## Manifest contract

Three files must agree:

| Source | What it declares |
|---|---|
| `native/functions/src/jsMain/kotlin/Functions.kt` | the `@JsName("...")` callables — the source of truth |
| `native/functions/index.js` | the Firebase export map |
| `native/worker/src/manifest.js` | the Worker `CALLABLES` table |

`test/manifest.test.mjs` parses all three and fails on drift. Adding a callable is a deliberate
contract change: update the Kotlin source, the Firebase manifest, the Worker manifest and the
pinned `EXPECTED` list in the test together.
