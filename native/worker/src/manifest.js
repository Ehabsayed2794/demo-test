// The Worker's callable surface.
//
// This is the Cloudflare-side twin of native/functions/index.js. The three
// names are the public API of the Ranked authority layer, and the Kotlin source
// is the single source of truth for them:
//
//   native/functions/src/jsMain/kotlin/Functions.kt  -- @JsName("...")
//   native/functions/index.js                        -- Firebase export map
//   native/worker/src/manifest.js                    -- this file
//
// test/manifest.test.mjs parses all three and fails if they drift. Keep the
// order identical to the Firebase manifest so a diff is readable.
//
// A name here is a promise that the Worker can dispatch it. The handler behind
// it may still be a fail-closed scaffold (see handlers.js); it must never be
// silently absent, because an absent callable is a 404 at runtime, not a build
// error.

export const CALLABLES = Object.freeze([
  "engineSmoke",
  "settleMatch",
  "resolveDecisionTimeout",
]);

/**
 * Fails loudly at module load if the dispatch table and the manifest disagree.
 *
 * Two failure modes matter:
 *  - a declared callable with no handler  -> requests would crash with a 500;
 *  - a handler not declared in the manifest -> dead code that looks live.
 *
 * The Worker boots only when the table is coherent. This is the Cloudflare
 * equivalent of the Firebase manifest guard in native/functions/index.js.
 */
export function assertManifest(handlers) {
  if (!Array.isArray(CALLABLES) || CALLABLES.length === 0) {
    throw new Error("worker manifest: no callables declared");
  }

  const declared = new Set();
  for (const name of CALLABLES) {
    if (typeof name !== "string" || name.length === 0) {
      throw new Error("worker manifest: callable names must be non-empty strings");
    }
    if (declared.has(name)) {
      throw new Error(`worker manifest: duplicate callable "${name}"`);
    }
    declared.add(name);

    if (typeof handlers[name] !== "function") {
      throw new Error(`worker manifest: no handler for callable "${name}"`);
    }
  }

  for (const name of Object.keys(handlers)) {
    if (!declared.has(name)) {
      throw new Error(
        `worker manifest: handler "${name}" is not declared in CALLABLES`,
      );
    }
  }

  return CALLABLES;
}
