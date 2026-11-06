// The manifest contract: three sources, one callable set.
//
//   Kotlin  native/functions/src/jsMain/kotlin/Functions.kt   @JsName("...")
//   Firebase native/functions/index.js                        export map
//   Worker  native/worker/src/manifest.js                     CALLABLES
//
// The Kotlin source is the source of truth; the other two are projections of
// it. This test fails if any projection drifts, so adding a callable means
// touching all three (and the pinned expectation below) in the same change.
//
// Built-in modules only: node:test, node:assert, node:fs, node:path, node:url.
// No dependencies, no network.

import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { CALLABLES, assertManifest } from "../src/manifest.js";
import { HANDLERS } from "../src/handlers.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const KOTLIN = path.resolve(
  HERE,
  "../../functions/src/jsMain/kotlin/Functions.kt",
);
const FIREBASE = path.resolve(HERE, "../../functions/index.js");

// The V1 callable surface. Adding a callable is a deliberate contract change:
// update this pin, the Kotlin source, the Firebase manifest and the Worker
// manifest together.
const EXPECTED = [
  "engineSmoke",
  "resolveDecisionTimeout",
  "settleMatch",
];

function sorted(values) {
  return [...values].sort();
}

function kotlinCallableNames(source) {
  const lines = source.split(/\r?\n/);
  const names = [];

  for (let index = 0; index < lines.length; index += 1) {
    const match = /^\s*@JsName\("([^"]+)"\)\s*$/.exec(lines[index]);
    if (!match) {
      continue;
    }

    // A @JsName without @JsExport is not a callable entry point. Fail rather
    // than silently widening the contract.
    const window = lines
      .slice(Math.max(0, index - 3), index + 3)
      .join("\n");
    assert.match(
      window,
      /@JsExport/,
      `@JsName("${match[1]}") at Functions.kt:${index + 1} has no @JsExport`,
    );

    names.push(match[1]);
  }

  return names;
}

function firebaseCallableNames(source) {
  const block = /const callables = \{([\s\S]*?)\n\};/.exec(source);
  assert.ok(block, "native/functions/index.js has no `const callables = { ... }` block");

  return [...block[1].matchAll(/^\s*([A-Za-z_$][\w$]*)\s*:/gm)].map(
    (match) => match[1],
  );
}

test("Worker manifest matches the Kotlin @JsName callables", async () => {
  const source = await readFile(KOTLIN, "utf8");
  const kotlin = kotlinCallableNames(source);

  assert.ok(kotlin.length > 0, "no @JsName callables found in Functions.kt");
  assert.deepEqual(sorted(CALLABLES), sorted(kotlin));
});

test("Worker manifest matches the Firebase export map", async () => {
  const source = await readFile(FIREBASE, "utf8");
  const firebase = firebaseCallableNames(source);

  assert.ok(firebase.length > 0, "no callables found in native/functions/index.js");
  assert.deepEqual(sorted(CALLABLES), sorted(firebase));
});

test("the callable set is the pinned V1 surface", () => {
  assert.deepEqual(sorted(CALLABLES), EXPECTED);
});

test("every declared callable has a handler and no handler is undeclared", () => {
  assert.doesNotThrow(() => assertManifest(HANDLERS));
});

test("assertManifest rejects a declared callable with no handler", () => {
  assert.throws(
    () => assertManifest({ ...HANDLERS, settleMatch: undefined }),
    /no handler for callable "settleMatch"/,
  );
});

test("assertManifest rejects a handler that is not declared", () => {
  assert.throws(
    () => assertManifest({ ...HANDLERS, extraCallable: async () => ({}) }),
    /handler "extraCallable" is not declared/,
  );
});
