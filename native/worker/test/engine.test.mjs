// Integration test against the REAL built :authority-core bundle.
//
// The bundle is a build output and is not committed, so every test here
// skips with a note when it has not been built (run
//   gradlew :authority-core:jsProductionExecutableCompileSync
// first). The skip is reported in the test output â€” a skipped run is not a
// passing run, and nothing here is claimed unless it executed.

import test from "node:test";
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { createWorker } from "../src/app.js";

const BUNDLE = fileURLToPath(
  new URL(
    "../../authority-core/build/compileSync/js/main/productionExecutable/kotlin/authority-core.js",
    import.meta.url,
  ),
);

const skip = existsSync(BUNDLE)
  ? false
  : "authority-core bundle not built (gradlew :authority-core:jsProductionExecutableCompileSync)";

const AUTH = Object.freeze({ uid: "smoke-uid" });
async function verifyToken(token) {
  return token === "test-token" ? AUTH : null;
}

function call(name, data) {
  return new Request(`https://worker.test/call/${name}`, {
    method: "POST",
    headers: {
      authorization: "Bearer test-token",
      "content-type": "application/json",
    },
    body: JSON.stringify({ data }),
  });
}

test("the built bundle deals a 52-card engineSmoke through the default wiring", { skip }, async () => {
  const w = createWorker({ verifyToken }); // engine omitted â†’ auto-load

  const response = await w.fetch(call("engineSmoke", {}));

  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.result.ok, true);
  assert.equal(body.result.uid, "smoke-uid");
  assert.equal(body.result.seatCount, 4);
  assert.equal(body.result.totalCards, 52);
  assert.equal(body.result.uniqueCards, 52);
});

test("the built bundle rejects a missing matchId with 400 INVALID_ARGUMENT", { skip }, async () => {
  const w = createWorker({ verifyToken });

  for (const name of ["settleMatch", "resolveDecisionTimeout"]) {
    const response = await w.fetch(call(name, {}));
    assert.equal(response.status, 400, name);
    const body = await response.json();
    assert.equal(body.error.status, "INVALID_ARGUMENT", name);
    assert.match(body.error.message, /matchId is required/, name);
  }
});

test("the built bundle fails closed with 501 UNIMPLEMENTED until storage exists", { skip }, async () => {
  const w = createWorker({ verifyToken });

  for (const name of ["settleMatch", "resolveDecisionTimeout"]) {
    const response = await w.fetch(call(name, { matchId: "m1" }));
    assert.equal(response.status, 501, name);
    const body = await response.json();
    assert.equal(body.error.status, "UNIMPLEMENTED", name);
    assert.match(body.error.message, /storage layer/i, name);
  }
});
