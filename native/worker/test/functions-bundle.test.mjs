// Regression guard for the O-06 export collision.
//
// :functions depends on :authority-core, and Kotlin/JS re-exports a
// dependency's @JsExport declarations into the consumer bundle
// (`$jsExportAll$`). When :authority-core exported top-level `engineSmoke` /
// `settleMatch` / `resolveDecisionTimeout`, the :functions bundle threw at
// require() time:
//
//   TypeError: Cannot set property engineSmoke of #<Object>
//   which has only a getter
//
// — before native/functions/index.js's own guard could run. The Worker API is
// now a single `WorkerApi` namespace, so the Firebase callables keep their
// names and triggers. This test loads the REAL built bundle (with the Firebase
// SDKs stubbed: no network, no credentials, no emulator) and pins both halves.
//
// Skips (and reports the skip) when the :functions bundle has not been built —
// a skipped run is not a passing run.

import test from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import Module, { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);

const FUNCTIONS_BUNDLE = fileURLToPath(
  new URL(
    "../../functions/build/compileSync/js/main/productionExecutable/kotlin/functions.js",
    import.meta.url,
  ),
);
const FUNCTIONS_INDEX = fileURLToPath(
  new URL("../../functions/index.js", import.meta.url),
);

const skip = existsSync(FUNCTIONS_BUNDLE)
  ? false
  : "functions bundle not built (gradlew :functions:jsProductionExecutableCompileSync)";

// Minimal Firebase SDK stubs: just enough for the Kotlin bundle to load and
// for index.js to inspect the callables. Nothing is called.
class HttpsError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

const onCall = (fn) => {
  fn.__trigger = { httpsTrigger: {} };
  return fn;
};

const FieldValue = {
  serverTimestamp: () => ({ __sentinel: "serverTimestamp" }),
  arrayUnion: (...values) => ({ __sentinel: "arrayUnion", values }),
};

const makeDb = () => ({
  collection: () => ({
    doc: () => ({
      get: async () => ({ exists: false, data: () => undefined }),
      set: async () => {},
      update: async () => {},
    }),
  }),
  runTransaction: async (fn) => fn({ get: async () => ({ exists: false }) }),
});

const STUBS = {
  "firebase-functions/v2/https": { HttpsError, onCall },
  "firebase-admin/firestore": { getFirestore: makeDb, FieldValue },
  "firebase-admin": { initializeApp: () => ({}) },
};

function withStubbedFirebase(load) {
  const original = Module._load;
  Module._load = function (request, parent, isMain) {
    if (Object.hasOwn(STUBS, request)) return STUBS[request];
    return original.apply(this, arguments);
  };
  try {
    return load();
  } finally {
    Module._load = original;
  }
}

test("the :functions bundle loads and every callable keeps its __trigger", { skip }, () => {
  // index.js itself requires the bundle and throws if a callable lost its
  // trigger — so this is also the load-time collision check.
  const manifest = withStubbedFirebase(() => require(FUNCTIONS_INDEX));

  assert.deepEqual(Object.keys(manifest).sort(), [
    "engineSmoke",
    "resolveDecisionTimeout",
    "settleMatch",
  ]);
  for (const [name, fn] of Object.entries(manifest)) {
    assert.equal(typeof fn, "function", `${name} is not a function`);
    assert.ok(fn.__trigger, `${name} lost its __trigger`);
  }

  const kotlin = withStubbedFirebase(() => require(FUNCTIONS_BUNDLE));
  const api = kotlin.WorkerApi;
  assert.equal(typeof api, "object", "the bundle has no WorkerApi namespace");
  assert.equal(typeof api.DO_SCHEMA, "string", "WorkerApi.DO_SCHEMA is missing");
  for (const name of ["engineSmoke", "settleMatch", "resolveDecisionTimeout"]) {
    assert.equal(typeof api[name], "function", `WorkerApi.${name} is missing`);
  }

  // The Firebase callables must be :functions' own onCall wrappers, never
  // authority-core's WorkerApi members that shadowed them before the fix.
  for (const name of ["engineSmoke", "settleMatch", "resolveDecisionTimeout"]) {
    assert.notEqual(
      kotlin[name],
      api[name],
      `${name} is authority-core's export, not the Firebase callable`,
    );
  }
});
