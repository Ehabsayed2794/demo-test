# Stage 0 — E3.1 first slice: Cloudflare Worker scaffold

**Date:** 2026-11-05
**Status:** first slice complete **locally**. Nothing is deployed. The scaffold reads no Cloudflare
token, calls no account API, and enables no billing.
**Plan of record:** E3.1 — *"Runtime scaffold for the approved architecture | New runtime package;
`index.js` export map | Callable responds in staging; manifest matches exports | 48–88 h"*.
**Approved runtime:** ADR 0003 option B, closed by O-06 on 2026-10-30 — Cloudflare Workers +
Durable Objects (SQLite) + D1, Free plan.

> This slice covers the **new runtime package** and **manifest parity**. The staging half —
> "callable responds in staging" — is **not** done: it needs a Worker-compatible Kotlin/JS engine
> seam and an explicit deployment approval. No claim below is a staging claim.

---

## 1. What was built

| File | Purpose | SHA-256 |
|---|---|---|
| `native/worker/package.json` | ESM package, Node 22, no dependencies | `4d964240207b1167bec82c6f809947108480db13ae0648b2bd5ee8b41d4d95f8` |
| `native/worker/wrangler.toml` | Worker entry, no account/routes/bindings | `601f9f4bf42241cf38a0d7bf7d6f86d2bd96ffb440b044d86444e36a322c31c4` |
| `native/worker/.gitignore` | Ignores `node_modules/`, `.wrangler/` | `ca6ffd3e41501dc5af979b218f4b8f4d5bdec4ec69947b58c9f5cb95e402e065` |
| `native/worker/README.md` | Scope, routes, commands, next slice | `ac52f6ac9e06d9a494b5123aa49121e140d2a99959487f1591600776485812c8` |
| `native/worker/src/index.js` | `GET /health` + `POST /call/<name>` dispatch | `2dd2daa75b85cb4e5d6dbb2d8b23c3fd0faabcaf77b0b702ba22482b20883546` |
| `native/worker/src/manifest.js` | `CALLABLES` + boot-time guard | `e1b06916b0da15f8fa4b78092aba0949b4aff5d516c26c5c643b82b201ff4f50` |
| `native/worker/src/callable.js` | Firebase-callable envelope + canonical codes | `acc0133c1112d027aef840b36835c25ffcd41920d307c144abd73343b5dad06f` |
| `native/worker/src/handlers.js` | Three fail-closed scaffold handlers | `4fdbea0db1eac2ca37245709e3eaf86923be3485e9079a542e14a365ae03f233` |
| `native/worker/test/manifest.test.mjs` | Three-way manifest parity + guard tests | `a6872c4560d5d8bb789920696e0d73bcbbde8b1eb20b9ee37736655721bf252b` |
| `native/worker/test/dispatch.test.mjs` | Wire format, auth gate, error mapping | `0055a9aefe9fa29700c85fac050e6d8d87378f53dd57c8de322f71e95e209fc6` |

The Worker has **no** Durable Object or D1 binding yet. That is deliberate: the scaffold proves
the shell, not the match object. `wrangler.toml` has no `account_id`, no routes and no vars.

---

## 2. Manifest contract

Three files must agree, and the test parses all three:

| Source | What it declares |
|---|---|
| `native/functions/src/jsMain/kotlin/Functions.kt` | the `@JsName("...")` callables — source of truth |
| `native/functions/index.js` | the Firebase export map |
| `native/worker/src/manifest.js` | the Worker `CALLABLES` table |

The current set is `engineSmoke`, `settleMatch`, `resolveDecisionTimeout` — the same three
callables the Firebase manifest publishes. `test/manifest.test.mjs` also pins that set, so
removing a callable from all three sources still fails the test until the contract is deliberately
updated.

`assertManifest()` runs at Worker boot and rejects:

- a declared callable with no handler (would be a runtime 500);
- a handler that is not declared (dead code that looks live).

---

## 3. Test evidence

**Working directory:** `D:/demo-test/native/worker`
**Command:**

```powershell
& "C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" --test
```

**Verbatim summary:**

```
1..21
# tests 21
# suites 0
# pass 21
# fail 0
# cancelled 0
# skipped 0
# todo 0
# duration_ms 427.4822
EXIT=0
```

Re-ran the same command after deleting `.wrangler/`: **21/21 PASS, exit 0**
(`# duration_ms 327.7691`). The suite has no dependency on local wrangler state.

The 21 tests cover: `/health` manifest, unknown callable 404, missing token 401 (handler never
runs), unverifiable token 401, authenticated `{result}` envelope, typed callable error mapping,
all three handlers failing closed with 501, the default verifier rejecting a well-formed bearer
token, malformed JSON 400, body without `data` 400, non-POST 405, non-GET `/health` 405, unknown
route 404, malformed percent-encoding 400, `undefined` result → `null`, Kotlin↔Worker parity,
Firebase↔Worker parity, the pinned V1 surface, and the two `assertManifest` rejection cases.

`node --check` also passed on all six `.js`/`.mjs` source and test files before the run.

---

## 4. Local workerd smoke

**Command shape** (working directory `D:/demo-test/native/worker`):

```powershell
& "C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe" `
  "D:/cache/stage0/node_modules/wrangler/bin/wrangler.js" `
  dev --local --ip 127.0.0.1 --port 8799 --show-interactive-dev-session=false
```

Child environment: `WRANGLER_SEND_METRICS=false`, `CI=1`, `NO_COLOR=1`,
`XDG_CONFIG_HOME=D:/cache/stage0/e31-config`, `XDG_CACHE_HOME=D:/cache/stage0/e31-cache`,
`MSYS_NO_PATHCONV=1`. **No** `CLOUDFLARE_API_TOKEN`, **no** `CLOUDFLARE_ACCOUNT_ID`.

**Verbatim responses:**

```
GET /health -> 200
{"ok":true,"service":"estemshan-ranked","runtime":"cloudflare-worker","callables":["engineSmoke","settleMatch","resolveDecisionTimeout"]}

POST /call/engineSmoke (no token) -> 401
{"error":{"status":"UNAUTHENTICATED","message":"The request does not carry a verified identity."}}

POST /call/engineSmoke (bogus token) -> 401
{"error":{"status":"UNAUTHENTICATED","message":"The request does not carry a verified identity."}}

GET /call/engineSmoke (wrong method) -> 405
{"error":{"status":"INVALID_ARGUMENT","message":"Callable requests must use POST."}}
```

The dev process tree was killed with `taskkill /PID <pid> /T /F`; the port had no listener
afterwards and no `workerd` or `node ... wrangler` process remained.

**Smoke log:** `D:/cache/stage0/e31-config/wrangler.out.log` — 2,120 bytes, 25 lines,
SHA-256 `6cad5c36132b5f03caeac23b3e47326958f1fb9224fb51b4bc5a1c8a37c5075d`. It contains only
`127.0.0.1` URLs; no outbound request is recorded.

Wrangler copied its cached `request.cf` fixture from
`D:/cache/stage0/node_modules/.mf/cf.json` (mtime 2026-10-29) into
`native/worker/.wrangler/cache/cf.json`. That copy — which contains local geolocation metadata —
was removed with the whole `.wrangler/` directory after the smoke. `.wrangler/` is gitignored.

---

## 5. Node.js policy report

| Item | Value |
|---|---|
| Node version | `v22.14.0` |
| Executable | `C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe` (explicit path, no PATH change) |
| Test command | `node --test` from `D:/demo-test/native/worker` |
| Dependencies installed | **none** |
| Network needed by tests | **none** — `node:test`, `node:assert`, `node:fs`, `node:path`, `node:url` only |
| Local smoke | wrangler 4.155.0 + workerd `1.20261023.1`, both already in `D:/cache/stage0`; no `npm install`, no `npx` |
| Smoke network | localhost only; metrics disabled; log records no outbound request |
| Files created | `native/worker/` (10 files), the smoke log, `D:/cache/stage0/e31-repo-scan.mjs` |
| Files removed | `native/worker/.wrangler/` (generated by the smoke) |
| Unresolved limitations | engine not wired; token verifier not wired; no DO/D1 bindings; no staging deployment |

The repo scan read the API token from `D:/cache/stage0/e12/cf.env` **in-process**, compared it, and
printed only counts. The token was never printed, echoed, written or committed. The smoke itself
did not read the token.

---

## 6. Security and account checks

| Check | Result |
|---|---|
| Repo scan for the API token | **0 hits / 930 files** |
| Repo scan for account A | **0 hits / 930 files** |
| Account A called | **no** — no account API call was made at all |
| Cloudflare token in the smoke env | absent |
| Cloudflare account id in the smoke env | absent |
| Billing enabled / plan changed | **no** |
| `D:/cache/stage0/e9s4` touched | **no** |
| Deployment | **none** |
| Scan verdict | **PASS** |

Scan command: `node D:/cache/stage0/e31-repo-scan.mjs`. It skips `.git`, `node_modules`,
`.wrangler`, `.gradle`, `build`, `dist` and `hosting-dist`; it reads the token in-process and
writes nothing.

---

## 7. What this closes and what it does not

**Closes (local):**

- a new runtime package exists under `native/worker/`;
- the callable manifest matches the Kotlin source and the Firebase export map, enforced by test;
- the Worker boots under workerd with no bindings and no credentials;
- the Firebase callable wire format is implemented, including the auth-before-handler gate;
- every unimplemented callable fails closed with `501 UNIMPLEMENTED`, never a fabricated success.

**Does not close:**

- **"Callable responds in staging"** — no deployment exists.
- **The engine seam** — the current Kotlin/JS bundle imports `firebase-admin` /
  `firebase-functions`, which cannot load on Workers. A Worker-compatible Kotlin/JS entry point
  that exports plain functions is the next slice.
- **Token verification** — the default verifier rejects everything. A real Firebase ID-token
  verifier (or the chosen identity provider) is not wired.
- **Durable Object / D1 bindings** — the match object, ledger, backup and outbox are not in this
  package yet.
- **E3.1's 48–88 h estimate** — this is the first slice, not the whole experiment.

**Next slice:** add the Worker-compatible Kotlin/JS engine export, inject it into `handlers.js`,
then add the O-06 bindings. Ask the owner for explicit approval before any staging deployment.

---

## 8. Owner summary (Egyptian Arabic)

**اللي اتعمل:**

1. **scaffold محلي جديد** في `native/worker/` — Worker بيشتغل بـworkerd، فيه `GET /health` و
   `POST /call/<name>` بنفس شكل Firebase callable اللي الأندرويد بيكلمه.
2. **الـmanifest متطابق** مع Kotlin `@JsName` ومع `native/functions/index.js` — 3 callables:
   `engineSmoke`, `settleMatch`, `resolveDecisionTimeout`. فيه test بيفشل لو أي واحد فيهم اختلف.
3. **21/21 اختبار PASS** — منها: بدون token → 401، token غلط → 401، method غلط → 405،
   callable مش معروف → 404، وأي callable لسه مش موصول → 501 (مش نجاح وهمي).
4. **smoke محلي ناجح**: `/health` رجّع 200، والطلب بدون token رجّع 401، والعملية اتقفلت
   ومفيش حاجة شغالة على البورت.
5. **مفيش token ولا Account A ولا billing ولا نشر.** فحص الـrepo: **930 ملف، 0 token،
   0 Account A**.

**اللي لسه:**

- **المحرك نفسه مش موصول.** الـKotlin/JS الحالي بيعتمد على `firebase-admin`/`firebase-functions`
  ومش بيشتغل على Workers. محتاج entry point جديد يصدّر functions عادية، وبعدها نوصله بالـhandlers.
- **التحقق من الـtoken مش متوصل.** الافتراضي بيرفض كل حاجة (ده الآمن دلوقتي).
- **DO و D1 مش مضافين.** دول slice تانية.
- **مفيش staging deployment.** مش هحاول أنشر قبل موافقتك الصريحة.

**القرار المطلوب:** لما المحرك يتوصل، أوافق على نشر staging على Account B؟ مش دلوقتي — دلوقتي
الـslice محلية بالكامل.
