// The Kotlin/JS authority-core bundle loader.
//
// The bundle is the KMP production executable output of :authority-core.
// Its Worker-facing surface is ONE namespaced export:
//   WorkerApi.engineSmoke(uid)                            -> plain object
//   WorkerApi.settleMatch(uid, data, sqlHandle)           -> Promise<plain object>
//   WorkerApi.resolveDecisionTimeout(uid, data, sqlHandle)-> Promise<plain object>
//   WorkerApi.DO_SCHEMA                                   -> string
//
// The namespace is deliberate. :functions depends on :authority-core, and
// Kotlin/JS re-exports a dependency's @JsExport declarations into the
// consumer bundle; top-level `settleMatch` / `resolveDecisionTimeout` /
// `engineSmoke` here collided with the Firebase callables of the same name
// and made the :functions bundle throw at require() time. See WorkerApi.kt.
//
// `import("<literal>")` stays statically analyzable so bundlers (wrangler /
// esbuild) can inline the engine when the bundle has been built, while
// Node treats a missing file as a rejected promise — which the callers
// catch and turn into a fail-closed 501. The bundle is a BUILD OUTPUT and
// is intentionally not committed; build it with
//   gradlew :authority-core:jsProductionExecutableCompileSync
// The package.json copied into that output directory (no "type" field)
// marks the UMD bundle as CommonJS — the repo root is "type": "module",
// which would otherwise parse the UMD as ESM and explode on `require`.

let cached;

export function loadEngine() {
  if (cached === undefined) {
    cached = import(
      "../../authority-core/build/compileSync/js/main/productionExecutable/kotlin/authority-core.js"
    ).then((mod) => wrap(mod)).catch(() => null);
  }
  return cached;
}

/** Test hook: drop the cached promise so each test controls the outcome. */
export function resetEngineCache() {
  cached = undefined;
}

function wrap(mod) {
  // Node's ESM loader exposes a CJS default export; bundlers may also
  // synthesise named exports. Accept either shape, require the WorkerApi
  // namespace and its three entry points to exist, and refuse to guess
  // otherwise.
  const engine = mod?.default ?? mod;
  const api = engine?.WorkerApi;
  if (
    api &&
    typeof api.engineSmoke === "function" &&
    typeof api.settleMatch === "function" &&
    typeof api.resolveDecisionTimeout === "function"
  ) {
    // Flatten the namespace back to the handlers' internal engine shape.
    // The arrows bind the Kotlin singleton: its methods live on the
    // object's prototype and may reference `this`.
    return {
      engineSmoke: (uid) => api.engineSmoke(uid),
      settleMatch: (uid, data, sqlHandle) =>
        api.settleMatch(uid, data, sqlHandle),
      resolveDecisionTimeout: (uid, data, sqlHandle) =>
        api.resolveDecisionTimeout(uid, data, sqlHandle),
    };
  }
  return null;
}
