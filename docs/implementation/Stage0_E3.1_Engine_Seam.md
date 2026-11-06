# Stage 0 — E3.1 engine seam: the Worker boots with the shared Kotlin engine

**Date:** 2026-11-06
**Status:** complete **locally**. Nothing is deployed. No Cloudflare account, token or
billing is involved. **Complete locally** does not mean **ready for staging**: the
storage layer (O-06) and the token verifier are still not wired.
**Plan of record:** E3.1 — *"Runtime scaffold for the approved architecture | New runtime
package; `index.js` export map | Callable responds in staging; manifest matches exports |
48–88 h"*. Stage 0 closed the new-runtime-package half; this closes the engine seam half.
The staging half is untouched: it needs O-06 bindings **and** explicit owner approval.
**Approved runtime:** ADR 0003 option B, closed by O-06 on 2026-10-30 — Cloudflare
Workers + Durable Objects (SQLite) + D1, Free plan.

> This slice covers: the Worker-compatible Kotlin/JS entry points, the shared
> authority core extracted from the Firebase adapter, real engine execution under
> Node 22 and under workerd, fail-closed behaviour on every unwired path, and the
> Firebase path kept green. It does **not** cover: DO/D1 bindings, token
> verification, staging deployment.

---

## 1. What was built — the architecture

The Firebase bundle always imported `firebase-admin` / `firebase-functions`, which cannot
load on Workers. The fix is a **shared, firebase-free core** consumed by two adapters:

```
                  ┌─────────────────────────────────────────┐
                  │  :authority-core  (native/authority-core)│
                  │  pure Kotlin, ZERO firebase-* imports    │
                  │  · Settlement / TimeoutResolution (pure) │
                  │  · orchestrations (settleOnce …)         │
                  │  · typed ports SettlementDb / TimeoutDb  │
                  │  · sentinels (storage markers)           │
                  └───────┬─────────────────────┬───────────┘
              depends on  │                     │  build output imported by
                          ▼                     ▼
        ┌───────────────────────────┐   ┌──────────────────────────────┐
        │ :functions (Firebase)     │   │ native/worker (Cloudflare)   │
        │ AdminSettlementDb /       │   │ src/engine.js (lazy loader)  │
        │ AdminTimeoutDb            │   │ src/handlers.js (createHandlers)│
        │ Sentinels.translate →     │   │ src/index.js  (createWorker) │
        │ FieldValue transforms     │   │ DO/D1 adapter → O-06 slice   │
        └───────────────────────────┘   └──────────────────────────────┘
```

Everything the engine and the decision makers do is **the same code** on both runtimes —
never a re-implementation (RD12). Only storage and identity differ, behind ports.

### New module `native/authority-core`

- `WorkerApi.kt` — the Worker's **namespaced** `@JsExport` surface: one `WorkerApi`
  object exporting `engineSmoke` / `settleMatch` / `resolveDecisionTimeout` /
  `DO_SCHEMA` (no package declaration, so the namespace stays top-level). The namespace
  is required because `:functions` re-exports a dependency's `@JsExport` declarations;
  top-level names collided with the Firebase callables (see
  `Stage0_O06_DO_D1.md` §5.4).
- `UnwiredStorage.kt` — fail-closed storage: every read/transact throws
  `AuthorityError("unimplemented", …)`. **Deliberately not** an in-memory fake
  (isolates don't share memory; exactly-once would break while looking healthy).
- `AuthoritySentinels.kt` — inert storage markers (`ServerTimestampSentinel`,
  `ArrayUnionSentinel`). Firestore commit-time transforms live behind each adapter.
- `AuthorityError.kt` — the one failure type; codes are Firebase's canonical
  lowercase-dash strings, so the Firebase adapter maps 1:1 onto `HttpsError` and the
  Worker maps onto the SCREAMING_SNAKE envelope with one `toUpperCase().replace("-","_")`.
- `build.gradle.kts` — copies the KMP-generated `package.json` (no `"type"` field) into
  the bundle output so the repo root's `"type": "module"` does not parse the UMD bundle
  as ESM (without it, `require` of the bundle dies with `this['kotlin-kotlin-stdlib']`).
- Moved from `:functions` (git mv): `JsBridging.kt`, `Settlement.kt`,
  `TimeoutResolution.kt`, `SettlementDb.kt`, `TimeoutResolutionDb.kt` and their tests.

### New / changed in `:functions` (Firebase adapter)

- `AdminSettlementDb.kt`, `AdminTimeoutDb.kt` — the Admin-SDK implementations, moved
  verbatim except writes now go through `Sentinels.translate` before `toPlain`.
- `Sentinels.kt` — the only file that knows `FieldValue.*`: translates sentinels at the
  commit boundary (`serverTimestamp()` / `arrayUnion()`), deep-walks maps/lists.
- `Functions.kt` — callables unchanged in behaviour; adapter maps `AuthorityError` 1:1
  onto `HttpsError` (byte-compatible wire codes for the client).
- New `SentinelsTest.kt` — pins the boundary against the real pinned admin SDK
  (`arrayUnion` transform `methodName === "FieldValue.arrayUnion"`; elements plain).

### `native/worker` (the Cloudflare side)

- `src/engine.js` — lazy `import("<literal bundle path>")` on **first callable request**
  (never at boot), cached; validates the three entry points exist. Missing/broken bundle
  → `null` → fail-closed 501.
- `src/handlers.js` — `createHandlers({ engine })`: `engine: null` pins the pre-seam
  fail-closed table (tests); `engine` omitted auto-loads the built bundle; an object
  with the three functions injects an adapter (O-06 uses this). `auth.uid` must be a
  non-empty string — anything else is `401` before the engine runs. Malformed engine
  responses (non-object) fail closed to `500 INTERNAL`. `{ ok:false, error:{code,…} }`
  maps to the callable envelope via `authorityCode()`.
- `src/index.js` — `createWorker({ verifyToken, handlers, engine })`; boot-time
  `assertManifest` unchanged.
- `src/callable.js` — added `authorityCode()`; unknown codes → `INTERNAL`.

---

## 2. The bundle package.json trap (found and fixed here)

The repo root declares `"type": "module"`. Node and bundlers decide a `.js` file's module
type from the **nearest** `package.json` — so the UMD (CommonJS) KMP bundle was parsed
as ESM and every load died with

```
TypeError: Cannot read properties of undefined (reading 'kotlin-kotlin-stdlib')
```

(the UMD `else` branch doing `this['kotlin-kotlin-stdlib']` with ESM's `this === undefined`).
The build now drops KMP's own marker (`build/tmp/jsPublicPackageJson/package.json`, no
`"type"` field) into the bundle directory — the same file it uses for publication. The
bundle directory is gitignored build output; nothing is committed.

Same trap means `native/functions/index.js`'s own `require(BUNDLE)` would fail the same
way on a fresh machine — noted for the Firebase deploy path, not changed here.

---

## 3. Manifest contract — unchanged and still pinned

Three files still agree; `test/manifest.test.mjs` parses all three (unchanged). The
Worker's HTTP callable names stay flat (`engineSmoke`, `settleMatch`,
`resolveDecisionTimeout`); `src/engine.js` flattens the bundle's `WorkerApi` namespace
onto that internal interface. The bundle's top-level exports are the namespace itself
(no package declaration, so it stays top-level), so they cannot collide with the
Firebase callables.

---

## 4. Test evidence

Working directory: `D:/demo-test/native`

### 4.1 The pure core — `:authority-core:jsNodeTest`

```
<testsuite name="com.estemshan.functions.CallablePayloadBridgingTest" tests="3" failures="0" errors="0"/>
<testsuite name="com.estemshan.functions.SettlementTest"               tests="28" failures="0" errors="0"/>
<testsuite name="com.estemshan.functions.TimeoutResolutionTest"        tests="45" failures="0" errors="0"/>
```

**76/76 PASS.** (Run also before the final comment fix — same totals.)

### 4.2 The Firebase adapter — `:functions:jsNodeTest`

```
<testsuite name="com.estemshan.functions.CallableAuthTest"   tests="5" failures="0" errors="0"/>
<testsuite name="com.estemshan.functions.IdempotencyGuardTest" tests="5" failures="0" errors="0"/>
<testsuite name="com.estemshan.functions.SentinelsTest"      tests="3" failures="0" errors="0"/>
```

**13/13 PASS.** The previously co-located engine suites moved **with** the code they test
(`SettlementTest`, `TimeoutResolutionTest`, `CallablePayloadBridgingTest`) — same tests,
updated call sites only.

### 4.3 The Worker — `node --test` (Node v22.14.0, explicit path)

```
1..31
# tests 31
# pass 31
# fail 0
# duration_ms 616.7242
```

`test/engine.test.mjs` **executed** (bundle was built), and reports its own evidence:

```
ok 23 - the built bundle deals a 52-card engineSmoke through the default wiring
ok 24 - the built bundle rejects a missing matchId with 400 INVALID_ARGUMENT
ok 25 - the built bundle fails closed with 501 UNIMPLEMENTED until storage exists
```

If the bundle is absent, those three skip with an explicit note — a skip is never claimed
as a pass.

**Totals: 120 automated checks, all green** (76 + 13 + 31).

### 4.4 Test-integrity notes (what moved, what did not)

No test was deleted. Deliberate retargets, each in the direction of *more* pinning:

- `settleOnce(db, request)` / `resolveTimeoutOnce(db, request, clock)` →
  `settleOnce(db, uid, data)` / `resolveTimeoutOnce(db, uid, data, clock)`; adapters own
  auth. Fakes now pass `json("matchId" to …)` (plain JS object — the runtime shape).
- `TimeoutResolutionTest.arrayUnionEntries` now reads the core's `ArrayUnionSentinel` and
  **also** asserts each element's prototype is `Object.prototype`. The
  `"FieldValue.arrayUnion"` assertion **moved to `:functions` SentinelsTest.kt** — same
  pin, one layer out, against the real SDK.
- `unauthenticatedSettleThrows` → `aSettlerOutsideTheMatchIsDenied`; authentication is an
  adapter concern now (callable-auth / Worker bearer gate — both 401-before-work, both
  covered by their own suites). The orchestration's remaining gate (membership) is asserted
  with `permission-denied`.
- `anUnauthenticatedCallerIsRejectedBeforeAnyRead` → `aCallerOutsideTheMatchIsRejectedBeforeAnyRead`
  (+ `handReads == 0`).
- `aMatchIdIsRequired` strengthened: asserts `code === "invalid-argument"` and the message.
- `anAppendedLogEntryIsAPlainObjectNotAKotlinMap` rewritten against the sentinel — same
  assertion, same name.

---

## 5. workerd proof — the engine actually runs on the target runtime

`node --test` already proved the bundle loads (CJS under Node ESM) and deals. To prove
**workerd** executes it — without any token backdoor — a throwaway entry
`native/worker/.smoke.mjs` was created, bundled and deleted afterwards:

```js
import coreBundle from "../authority-core/build/compileSync/js/main/productionExecutable/kotlin/authority-core.js";
export default { async fetch() {
  const deal = coreBundle.engineSmoke("workerd-smoke");
  const missing = await coreBundle.settleMatch("workerd-smoke", {});
  const unwired = await coreBundle.settleMatch("workerd-smoke", { matchId: "m1" });
  return Response.json({ deal, missing, unwired });
} };
```

`wrangler dev --local --port 8801 .smoke.mjs` booted clean; `curl.exe http://127.0.0.1:8801/`
answered verbatim:

```json
{"deal":{"ok":true,"uid":"workerd-smoke","seatCount":4,"totalCards":52,"uniqueCards":52},
 "missing":{"ok":false,"error":{"code":"invalid-argument","message":"settleMatch: matchId is required."}},
 "unwired":{"ok":false,"error":{"code":"unimplemented","message":"The Cloudflare storage layer (Durable Object + D1, O-06) is not wired in this build; the callable fails closed."}}}
HTTP 200
```

This proves, on the actual runtime: the UMD bundle loads under wrangler's bundler, the
Kotlin Multiplatform JS runtime runs, coroutines/promises work for the `suspend` exports,
and the error-envelope contract is intact. **`.smoke.mjs` and `.wrangler/` were deleted
afterwards; ports 8799/8801 are free; no workerd/node process remains.**

### Standard scaffold smoke (unchanged behaviour)

```
GET /health -> 200
{"ok":true,"service":"estemshan-ranked","runtime":"cloudflare-worker","callables":["engineSmoke","settleMatch","resolveDecisionTimeout"]}

POST /call/engineSmoke (no token)   -> 401
{"error":{"status":"UNAUTHENTICATED","message":"The request does not carry a verified identity."}}

POST /call/engineSmoke (bogus token) -> 401
{"error":{"status":"UNAUTHENTICATED","message":"The request does not carry a verified identity."}}

GET /call/engineSmoke (wrong method) -> 405
{"error":{"status":"INVALID_ARGUMENT","message":"Callable requests must use POST."}}
```

`wrangler.out.log`: `D:/cache/stage0/e32-config/wrangler.out.log` (Ready on
`http://127.0.0.1:8799`; only 127.0.0.1 URLs; metrics disabled).

**Not verified (by design):** the authenticated HTTP path — no token verifier exists to
verify with. No temporary backdoor verifier was added. Engine-under-workerd is proven via
the throwaway entry instead, as above.

---

## 6. What this closes and what it does not

**Closes:**

- a Worker-compatible Kotlin/JS entry point exists and **loads and runs on workerd and
  Node 22**;
- the shared engine is wired end-to-end: `engineSmoke` deals the real 52-card hand through
  the same `:engine` the JVM golden tests assert;
- storage-backed callables fail closed with `501 UNIMPLEMENTED` and **still run the real
  validation** (missing `matchId` → `400 INVALID_ARGUMENT`, same message as production);
- the Firebase path is unchanged in behaviour and fully green (13/13) with the boundary
  pinned by new adapter-layer tests;
- the bundle package.json trap is fixed by the build, not by hand.

**Does not close:**

- **DO/D1 bindings (O-06).** Settlement/timeout still cannot persist. The ports
  (`SettlementDb`/`TimeoutDb`) are the exact seam the DO/D1 adapter implements; no fake
  is committed.
- **Token verification.** The default verifier rejects everything; production verifier
  not wired.
- **Staging deployment.** Needs the O-06 slice **and** explicit owner approval.
- **The `native/functions` deploy bootstrap** — the same root `"type": "module"` trap
  applies to its own `require(bundle)`; fix belongs to the Firebase deploy hardening
  (S-series), not this slice.

---

## 7. Node.js policy report

| Item | Value |
|---|---|
| Node version | `v22.14.0` |
| Executable | `C:/Users/ehab.sayed/.gradle/nodejs/node-v22.14.0-win-x64/node.exe` (tests, smokes; explicit path) |
| Test command | `node --test` from `D:/demo-test/native/worker` |
| Gradle JS tests | `:authority-core:jsNodeTest`, `:functions:jsNodeTest` (`--offline`, all deps cached) |
| Dependencies installed | **none** — no `npm`/`npx` runs; worker tests use built-ins only |
| Local smokes | wrangler 4.155.0 from `D:/cache/stage0` (cached, as the previous slice); localhost only; metrics disabled |
| Network needed by tests | **none** |
| Smoke network | localhost only; log records no outbound request |
| Files created | `native/authority-core/` (build file + 6 sources + 3 moved test suites), worker `src/engine.js`, worker `test/engine-wiring.test.mjs`, `test/engine.test.mjs`, docs file, smoke logs in `D:/cache/stage0/e32-config` |
| Files removed | `native/worker/.smoke.mjs`, `native/worker/.wrangler/` (smoke residue) |
| Ports after smokes | 8799/8801 — **no listener**; no `workerd`/`node … wrangler` process remains |

---

## 8. Security and account checks

| Check | Result |
|---|---|
| Cloudflare token in any smoke env | absent |
| Cloudflare account id in any smoke env | absent |
| Account A / B touched | **no** — no account API call was made at all |
| Billing enabled / plan changed | **no** |
| Deployment | **none** |
| `D:/cache/stage0/e9s4` touched | **no** |
| Test-skip fabricated as pass | **no** — engine.test.mjs skips explicitly when the bundle is absent |
| Scan verdict | **PASS** |

---

## 9. Owner summary (Egyptian Arabic)

**اللي اتعمل:**
1. **المحرك نفسه اتوصل للـWorker.** عملنا module جديد من غير أي اعتماديات Firebase
   (`authority-core`) فيه نفس كود القواعد ونفس الـorchestrations اللي
   settlement/timeout بيستخدموها. الـFirebase فاضل كما هو، والـWorker بقى يقدر
   يشوف نفس الكود.
2. **`engineSmoke` بقى بيدّي كروت حقيقية جوّه الـWorker نفسه** — جربناه على
   workerd الحقيقي: ٤ seats، ٥٢ كارت، ٥٢ كارت من غير تكرار، ونفس النتيجة اللي
   الـJVM tests بتوقعها.
3. **الـcallables اللي محتاجة قاعدة بيانات بترجع 501 مغلق** (مش نجاح وهمي)،
   لكن **الـvalidation الحقيقي شغال**: مثلاً طلب من غير `matchId` بيرجع
   `400 INVALID_ARGUMENT` بنفس رسالة Firebase بالظبط.
4. **كل ده محلي ١٠٠٪**: مفيش نشر، مفيش Cloudflare account، مفيش token، مفيش billing.
   الاختبارات: **١٢٠ / ١٢٠ ناح** (٧٦ للـcore + ١٣ للـFirebase + ٣١ للـWorker).
5. **وحدة الاختبارات ما اتمسحش ولا انهارت** — اللي اتنقل اتنقل مع كوده، والـassertions
   اللي بتفحص شكل الـ`arrayUnion` اتنقلت لـ`SentinelsTest.kt` على نفس الـSDK المثبت.

**اللي لسه:**
- **التخزين لسه مش متوصل** — الـDurable Object (SQLite) وD1 بتوع O-06 هم الشريحة
  الجاية؛ لحد ما يوصلوا، settlement/timeout بيرجعوا 501.
  > **تحديث O-06 (2026-11-06):** الـDO SQLite اتوصل محليًا في slice 1 واتحقق منه
  > (settlement/timeout بيشتغلوا عبر `MATCH_DO`)؛ D1 لسه slice 2. التفاصيل في
  > `Stage0_O06_DO_D1.md`.
- **التحقق من الـtoken مش متوصل** — الافتراضي بيرفض أي طلب (ده الآمن).
- **مفيش staging deployment** — مش هعمله من غير موافقتك الصريحة.

**القرار المطلوب:** نكمل لشريحة O-06 (Durable Object + D1)؟ ولا توقّف هنا لحين
مراجعتك للـengine seam؟

---

## Appendix — SHA-256 of files touched (full set)

> **Snapshot note (2026-11-06):** this appendix is the E3.1 evidence set. O-06 slice 1
> later changed `WorkerApi.kt` (namespaced `WorkerApi`) and `engine.js` (namespace
> unwrap); the current hashes are in `Stage0_O06_DO_D1.md` §8. Everything else here is
> the E3.1 record, not a claim about the current tree.

```
28377c8e17ba7d0405bd95cb00db41f2c790c3c12e9d35f11643d47ca4a705ea  native/authority-core/build.gradle.kts
f715c3caaa8f07dcaa77e0932349d16661b07380d2fd0c40fd6b1e8587f6bf15  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/AuthorityError.kt
6e298873c0454d366857fa72f8c5f83cc921aaeea40d0e82d8920510e9f2af20  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/AuthoritySentinels.kt
284f89120e2098ab7d00fff10b67df9cbecd4da3b39ebff0f2465a0576e091c9  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/JsBridging.kt
706d73239fe340276d5ae47faf18bbf32c6df3409292d63965092f3cc7f47ea4  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/Settlement.kt
7e5df239fa6d866bc65a98f5a48194538daef16dbf604300c5b9242a6cd74efb  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/SettlementDb.kt
152459e1c820613260b39f5b2bdf29c8fa62752f44990681896c44a8b21b03f9  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/TimeoutResolution.kt
e6110945e303856b7f78416d3ec402ac02652ecd0083abf25b5b1ab0ee84e405  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/TimeoutResolutionDb.kt
844e0d95a0c83b98a8fb61a66c3b2cd6116c284fc00ed552860b5dfee523df36  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/UnwiredStorage.kt
b4f3a64360b3b52c5061d95459b0115d9ed676f229c9b44f734af0425474dd62  native/authority-core/src/jsMain/kotlin/com/estemshan/functions/WorkerApi.kt
a93b8af55ee89eb6038dca81300f792265b63bfebc54d82de3ec2481776fc6fb  native/functions/src/jsMain/kotlin/Functions.kt
46c640ef62cd68c6bbd8cd1d10bbf65bff8f3fc2cf9276a537d9d497a01d0b5d  native/functions/src/jsMain/kotlin/com/estemshan/functions/AdminSettlementDb.kt
c756d6b83d0be7f423dd845b238c05dbf08cad6b6acaef750beb732c2094a14d  native/functions/src/jsMain/kotlin/com/estemshan/functions/AdminTimeoutDb.kt
c2ba753c6bc56bae5fdfda1f5eed372793a7356f180d5a58bcefb550038acc6e  native/functions/src/jsMain/kotlin/com/estemshan/functions/Sentinels.kt
45c04b326e5910b3d0a139980a0f803c12c56c25de9a04149f07ac9abb5380d9  native/functions/build.gradle.kts
8230990be08ad33ad7f92a84a1dd98e10a2c50bfa4783f99eaf5c914849f53df  native/functions/src/jsTest/kotlin/com/estemshan/functions/SentinelsTest.kt
fad430b6e1d7e6ea0f850451b2ed6db672bcfe586f5a7cb822f63a19a6c8aa45  native/settings.gradle.kts
4f3bd3bf2253909a86206186116b92210106198d39868c58215affaf0ec74c60  native/worker/src/engine.js
5c675059cdb8607f000e3f331102fa56b39052a96b4b9b06f14e321f2dd343c9  native/worker/src/handlers.js
12e74c337030bdab919d86f2554ee9c494e67eac43d44aac6ca29ffbdea6feec  native/worker/src/index.js
6aa024b631ef4f9c82cf89e9e147f2bff673eccdccdbc619637e9cb3b75fdb62  native/worker/src/callable.js
d4814bd68a0da7021edc442a50d4da296d69b6603b305f12dec0f6db6f12a975  native/worker/test/engine-wiring.test.mjs
ddfeaccfbaa3f4ddbc6ad8b0f759ed51e3ed22fd3d7dd8bfd9420dfeb1c369f9  native/worker/test/engine.test.mjs
71b518197890d0bb66263a3763a686a21532f28aa3b5e9a2ccd43c28bb0686ef  native/worker/README.md
```
