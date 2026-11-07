// Router tests: the storage-backed callables go through a Durable Object
// when env.MATCH_DO is bound; otherwise the (unwired) bundle path answers
// with 501. The DO is exercised through a fake namespace/stub here — real
// DO-backed behaviour is covered by the workerd seeded E2E (docs).

import test from "node:test";
import assert from "node:assert/strict";

import { createWorker } from "../src/app.js";

const AUTH = Object.freeze({ uid: "router-uid" });
async function verifyToken(token) {
  return token === "test-token" ? AUTH : null;
}

function call(name, data, { token = "test-token" } = {}) {
  const headers = { "content-type": "application/json" };
  if (token !== null) {
    headers.authorization = `Bearer ${token}`;
  }
  return new Request(`https://worker.test/call/${name}`, {
    method: "POST",
    headers,
    body: JSON.stringify({ data }),
  });
}

/** A fake MATCH_DO namespace that records every call it receives. */
function fakeNamespace(fetchImpl) {
  const calls = [];
  const namespace = {
    idFromName(name) {
      calls.push({ kind: "idFromName", name });
      return `id:${name}`;
    },
    get(id) {
      calls.push({ kind: "get", id });
      return {
        fetch: async (url, init) => {
          calls.push({ kind: "fetch", id, url, init });
          return fetchImpl(url, init);
        },
      };
    },
  };
  return { namespace, calls };
}

function jsonRes(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

test("env.MATCH_DO routes settleMatch to the DO with the RAW matchId", async () => {
  const { namespace, calls } = fakeNamespace(async () =>
    jsonRes({ ok: true, settled: true, matchId: "MIXED-Case-1" }),
  );
  const w = createWorker({ verifyToken });
  const matchId = "MIXED-Case-1";

  const response = await w.fetch(
    call("settleMatch", { matchId }),
    { MATCH_DO: namespace },
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    result: { ok: true, settled: true, matchId: "MIXED-Case-1" },
  });
  assert.deepEqual(
    calls.map((c) => c.kind),
    ["idFromName", "get", "fetch"],
  );
  // Raw, case-sensitive, no normalisation, no newUniqueId.
  assert.equal(calls[0].name, `match:${matchId}`);
  assert.equal(calls[2].url, "https://match-do/rpc/settle");
  assert.equal(calls[2].init.method, "POST");
  assert.deepEqual(JSON.parse(calls[2].init.body), {
    uid: "router-uid",
    data: { matchId },
  });
});

test("resolveDecisionTimeout routes to /rpc/timeout", async () => {
  const { namespace, calls } = fakeNamespace(async () =>
    jsonRes({ ok: true, resolved: true }),
  );
  const w = createWorker({ verifyToken });

  const response = await w.fetch(
    call("resolveDecisionTimeout", { matchId: "m1" }),
    { MATCH_DO: namespace },
  );

  assert.equal(response.status, 200);
  assert.equal(calls[2].url, "https://match-do/rpc/timeout");
});

test("an invalid matchId is rejected with 400 before the DO is called", async () => {
  const bad = [42, null, "", "has space", "a/b", "a\\b", "ctl\x01char", "x".repeat(129)];
  for (const matchId of bad) {
    const { namespace, calls } = fakeNamespace(async () => jsonRes({ ok: true }));
    const w = createWorker({ verifyToken });

    const response = await w.fetch(
      call("settleMatch", { matchId }),
      { MATCH_DO: namespace },
    );

    assert.equal(response.status, 400, JSON.stringify(matchId));
    assert.equal((await response.json()).error.status, "INVALID_ARGUMENT", JSON.stringify(matchId));
    assert.equal(calls.length, 0, `the DO must not be called for ${JSON.stringify(matchId)}`);
  }
});

test("a missing matchId is rejected with 400 and the core's message", async () => {
  const { namespace, calls } = fakeNamespace(async () => jsonRes({ ok: true }));
  const w = createWorker({ verifyToken });

  const response = await w.fetch(call("settleMatch", {}), { MATCH_DO: namespace });

  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.message, "settleMatch: matchId is required.");
  assert.equal(calls.length, 0);
});

test("without the binding, the call falls back to the fail-closed bundle", async () => {
  // engine: null pins the pre-engine-seam table → 501 UNIMPLEMENTED.
  const w = createWorker({ verifyToken, engine: null });

  const response = await w.fetch(call("settleMatch", { matchId: "m1" }));

  assert.equal(response.status, 501);
  assert.equal((await response.json()).error.status, "UNIMPLEMENTED");
});

test("with the binding present the bundle is NOT consulted for the routed callables", async () => {
  const { namespace } = fakeNamespace(async () =>
    jsonRes({ ok: true, settled: true }),
  );
  let engineCalled = false;
  const engine = {
    engineSmoke: async () => ({ ok: true }),
    settleMatch: async () => {
      engineCalled = true;
      return { ok: true };
    },
    resolveDecisionTimeout: async () => {
      engineCalled = true;
      return { ok: true };
    },
  };
  const w = createWorker({ verifyToken, engine });

  const response = await w.fetch(
    call("settleMatch", { matchId: "m1" }),
    { MATCH_DO: namespace },
  );

  assert.equal(response.status, 200);
  assert.equal(engineCalled, false);
});

test("the DO's failure envelope maps onto the callable envelope", async () => {
  const cases = [
    ["permission-denied", 403, "PERMISSION_DENIED"],
    ["not-found", 404, "NOT_FOUND"],
    ["failed-precondition", 400, "FAILED_PRECONDITION"],
    ["invalid-argument", 400, "INVALID_ARGUMENT"],
    ["unimplemented", 501, "UNIMPLEMENTED"],
    ["bogus", 500, "INTERNAL"],
  ];
  for (const [code, status, mapped] of cases) {
    const { namespace } = fakeNamespace(async () =>
      jsonRes({ ok: false, error: { code, message: `do said ${code}` } }),
    );
    const w = createWorker({ verifyToken });

    const response = await w.fetch(
      call("settleMatch", { matchId: "m1" }),
      { MATCH_DO: namespace },
    );

    assert.equal(response.status, status, code);
    const body = await response.json();
    assert.equal(body.error.status, mapped, code);
    assert.equal(body.error.message, `do said ${code}`, code);
  }
});

test("an unreachable DO yields 503 UNAVAILABLE, never a bare 500", async () => {
  const { namespace } = fakeNamespace(async () => {
    throw new Error("do not care");
  });
  const w = createWorker({ verifyToken });

  const response = await w.fetch(
    call("settleMatch", { matchId: "m1" }),
    { MATCH_DO: namespace },
  );

  assert.equal(response.status, 503);
  assert.equal((await response.json()).error.status, "UNAVAILABLE");
});

test("a non-JSON DO response yields 500 INTERNAL", async () => {
  const { namespace } = fakeNamespace(
    async () => new Response("not json", { status: 200 }),
  );
  const w = createWorker({ verifyToken });

  const response = await w.fetch(
    call("settleMatch", { matchId: "m1" }),
    { MATCH_DO: namespace },
  );

  assert.equal(response.status, 500);
  assert.equal((await response.json()).error.status, "INTERNAL");
});

test("a DO result that is not an object fails closed to 500 INTERNAL", async () => {
  const { namespace } = fakeNamespace(async () => jsonRes([1, 2, 3]));
  const w = createWorker({ verifyToken });

  const response = await w.fetch(
    call("settleMatch", { matchId: "m1" }),
    { MATCH_DO: namespace },
  );

  assert.equal(response.status, 500);
  assert.equal((await response.json()).error.status, "INTERNAL");
});

test("engineSmoke never touches the DO binding", async () => {
  const engine = {
    engineSmoke: async () => ({ ok: true, uniqueCards: 52 }),
    settleMatch: async () => { throw new Error("must not be called"); },
    resolveDecisionTimeout: async () => { throw new Error("must not be called"); },
  };
  const { namespace, calls } = fakeNamespace(async () => {
    throw new Error("the DO must not be called");
  });
  const w = createWorker({ verifyToken, engine });

  const response = await w.fetch(
    call("engineSmoke", {}),
    { MATCH_DO: namespace },
  );

  assert.equal(response.status, 200);
  assert.equal(calls.length, 0);
  assert.equal((await response.json()).result.uniqueCards, 52);
});
