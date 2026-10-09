// The deploy manifest for the Kotlin/JS authority layer.
//
// The Firebase CLI discovers callables by requiring this module and walking its
// ENUMERABLE exports (firebase-tools' extractTriggers.js: Object.keys(mod)),
// keeping any member that is a function carrying a __trigger. Kotlin/JS's
// @JsExport instead defines each export as a NON-enumerable getter (kotlin's
// defineProp is configurable-only, so enumerable defaults to false), which
// makes Object.keys return an empty key set — the CLI then registers nothing
// and every call 404s with functions/not-found. This file re-publishes each
// callable as a plain enumerable property, which is the shape the CLI needs.
//
// Keep this in sync when a callable is added to Functions.kt. The guard below
// makes drift fail LOUDLY at emulator start — a callable listed here but not
// exported by the bundle, or one that lost its trigger metadata — instead of
// silently deploying an empty module.

const BUNDLE = "./build/compileSync/js/main/productionExecutable/kotlin/functions.js";
const kotlin = require(BUNDLE);

const callables = {
  engineSmoke: kotlin.engineSmoke,
};

for (const [name, fn] of Object.entries(callables)) {
  if (typeof fn !== "function") {
    throw new Error(
      `functions manifest: "${name}" is not exported by the Kotlin bundle — ` +
      `build it first (gradlew :functions:jsProductionExecutableCompileSync) ` +
      `or add the callable to native/functions/src/jsMain/kotlin/Functions.kt.`,
    );
  }
  if (!fn.__trigger) {
    throw new Error(
      `functions manifest: "${name}" has no __trigger — it is not a Firebase ` +
      `callable. Declare it with onCall(...) in Functions.kt.`,
    );
  }
}

module.exports = callables;
