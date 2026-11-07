// Engine-wiring tests with an injected fake engine â€” deterministic, no
// build artifacts involved. The real bundle is covered by engine.test.mjs.

import test from "node:test";
import assert from "node:assert/strict";

import { createWorker } from "../src/app.js";
import { authorityCode } from "../src/callable.js";

const AUTH = Object.freeze({ uid: "worker-test-uid" });

async function verifyToken(token) {
  return token === "test-token" ? AUTH : null;
}

/** A verifyToken that authenticates but returns no uid (misconfigured verifier). */
async function verifyTokenWithoutUid() {
  return { name: "no-uid-here" };
}

function call(name, { token = "test-token", body = { data: {} } } = {}) {
  const headers = { "content-type": "application/json" };
  if (token !== null) {
    headers.authorization = `Bearer ${token}`;
  }
  return new Request(`https://worker.test/call/${name}`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
}

function fakeEngine(overrides = {}) {
  const calls = [];
  const record = (name, impl) =>
    async (uid, data) => {
      calls.push({ name, uid, data });
      return impl();
    };
  return {
    calls,
    engine: {
      engineSmoke: record("engineSmoke", overrides.engineSmoke ?? (() => ({
        ok: true,
        uid: "worker-test-uid",
        seatCount: 4,
        totalCards: 52,
        uniqueCards: 52,
      }))),
      settleMatch: record("settleMatch", overrides.settleMatch ?? (() => ({
        ok: true,
        settled: true,
      }))),
      resolveDecisionTimeout: record(
        "resolveDecisionTimeout",
        overrides.resolveDecisionTimeout ?? (() => ({ ok: true, resolved: true })),
      ),
    },
  };
}

test("authorityCode maps the core's lowercase-dash codes", () => {
  assert.equal(authorityCode("invalid-argument"), "INVALID_ARGUMENT");
  assert.equal(authorityCode("not-found"), "NOT_FOUND");
  assert.equal(authorityCode("failed-precondition"), "FAILED_PRECONDITION");
  assert.equal(authorityCode("permission-denied"), "PERMISSION_DENIED");
  assert.equal(authorityCode("unauthenticated"), "UNAUTHENTICATED");
  assert.equal(authorityCode("unimplemented"), "UNIMPLEMENTED");
  assert.equal(authorityCode("internal"), "INTERNAL");
  assert.equal(authorityCode("bogus-code"), "INTERNAL");
  assert.equal(authorityCode(undefined), "INTERNAL");
});

test("a success envelope passes through as { result }", async () => {
  const { engine, calls } = fakeEngine();
  const w = createWorker({ verifyToken, engine });

  const response = await w.fetch(call("settleMatch", { body: { data: { matchId: "m1" } } }));

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { result: { ok: true, settled: true } });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].name, "settleMatch");
  assert.equal(calls[0].uid, "worker-test-uid");
  assert.deepEqual(calls[0].data, { matchId: "m1" });
});

test("the core's failure envelope maps onto the callable error contract", async () => {
  const cases = [
    ["invalid-argument", 400, "INVALID_ARGUMENT"],
    ["not-found", 404, "NOT_FOUND"],
    ["failed-precondition", 400, "FAILED_PRECONDITION"],
    ["permission-denied", 403, "PERMISSION_DENIED"],
    ["unimplemented", 501, "UNIMPLEMENTED"],
    ["bogus", 500, "INTERNAL"],
  ];
  for (const [code, status, statusName] of cases) {
    const { engine } = fakeEngine({
      settleMatch: () => ({ ok: false, error: { code, message: `core said ${code}` } }),
    });
    const w = createWorker({ verifyToken, engine });
    const response = await w.fetch(call("settleMatch"));
    assert.equal(response.status, status, code);
    const body = await response.json();
    assert.equal(body.error.status, statusName, code);
    assert.equal(body.error.message, `core said ${code}`, code);
  }
});

test("the auth gate runs before the engine is touched", async () => {
  const { engine, calls } = fakeEngine();
  const w = createWorker({ verifyToken, engine });

  const response = await w.fetch(call("settleMatch", { token: "wrong" }));

  assert.equal(response.status, 401);
  assert.equal(calls.length, 0);
});

test("a verifier that returns no uid is rejected before the engine is touched", async () => {
  const { engine, calls } = fakeEngine();
  const w = createWorker({ verifyToken: verifyTokenWithoutUid, engine });

  const response = await w.fetch(call("engineSmoke"));

  assert.equal(response.status, 401);
  assert.equal(calls.length, 0);
});

test("a malformed engine response fails closed with 500 INTERNAL", async () => {
  const { engine } = fakeEngine({ settleMatch: () => null });
  const w = createWorker({ verifyToken, engine });

  const response = await w.fetch(call("settleMatch"));

  assert.equal(response.status, 500);
  assert.equal((await response.json()).error.status, "INTERNAL");
});

test("engineSmoke passes the engine's deal through as { result }", async () => {
  const { engine } = fakeEngine();
  const w = createWorker({ verifyToken, engine });

  const response = await w.fetch(call("engineSmoke", { body: { data: {} } }));

  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.result.uniqueCards, 52);
});
