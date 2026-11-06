// Wire-format and auth-gate tests. No network, no workerd: Node 22's global
// Request/Response are enough to exercise the Worker's fetch handler directly.
//
// The injected verifier is a test double. The production verifier is still
// unwired; the default one rejects every request, and one test below proves it.

import test from "node:test";
import assert from "node:assert/strict";

import worker, { createWorker } from "../src/app.js";
import { HANDLERS } from "../src/handlers.js";
import { callableError } from "../src/callable.js";

const AUTH = Object.freeze({ uid: "test-uid" });

async function verifyToken(token) {
  return token === "test-token" ? AUTH : null;
}

function call(
  name,
  { method = "POST", token = "test-token", body = { data: {} } } = {},
) {
  const headers = {};
  if (token !== null) {
    headers.authorization = `Bearer ${token}`;
  }
  if (body !== undefined) {
    headers["content-type"] = "application/json";
  }

  return new Request(`https://worker.test/call/${name}`, {
    method,
    headers,
    body:
      method === "POST" && body !== undefined
        ? JSON.stringify(body)
        : undefined,
  });
}

test("GET /health reports liveness and the manifest", async () => {
  const response = await worker.fetch(
    new Request("https://worker.test/health"),
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    ok: true,
    service: "estemshan-ranked",
    runtime: "cloudflare-worker",
    callables: ["engineSmoke", "settleMatch", "resolveDecisionTimeout"],
  });
});

test("unknown callable -> 404 NOT_FOUND", async () => {
  const w = createWorker({ verifyToken });
  const response = await w.fetch(call("doesNotExist"));

  assert.equal(response.status, 404);
  assert.deepEqual(await response.json(), {
    error: {
      status: "NOT_FOUND",
      message: "Unknown callable: doesNotExist",
    },
  });
});

test("missing token -> 401 and the handler never runs", async () => {
  let ran = false;
  const w = createWorker({
    verifyToken,
    handlers: {
      ...HANDLERS,
      engineSmoke: async () => {
        ran = true;
        return { ok: true };
      },
    },
  });

  const response = await w.fetch(call("engineSmoke", { token: null }));

  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.status, "UNAUTHENTICATED");
  assert.equal(ran, false);
});

test("a token verifier that throws -> 503 UNAVAILABLE (JSON envelope)", async () => {
  // The production verifier does network I/O; when it is down the Worker must
  // degrade to a JSON envelope, never reject fetch into a bare workerd 500.
  const w = createWorker({
    verifyToken: async () => {
      throw new Error("jwks down");
    },
  });
  const response = await w.fetch(call("engineSmoke"));

  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.error.status, "UNAVAILABLE");
  assert.equal(body.error.message, "The token verifier is unavailable. Try again later.");
});

test("unverifiable token -> 401", async () => {
  const w = createWorker({ verifyToken });
  const response = await w.fetch(call("engineSmoke", { token: "wrong" }));

  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.status, "UNAUTHENTICATED");
});

test("authenticated callable -> 200 { result }", async () => {
  const w = createWorker({
    verifyToken,
    handlers: {
      ...HANDLERS,
      engineSmoke: async ({ data, auth }) => ({
        echo: data,
        uid: auth.uid,
      }),
    },
  });

  const response = await w.fetch(
    call("engineSmoke", { body: { data: { hello: "world" } } }),
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    result: { echo: { hello: "world" }, uid: "test-uid" },
  });
});

test("callable error keeps code, message and HTTP status", async () => {
  const w = createWorker({
    verifyToken,
    handlers: {
      ...HANDLERS,
      settleMatch: async () => {
        throw callableError(
          "FAILED_PRECONDITION",
          "match is not settled yet",
          { matchId: "m1" },
        );
      },
    },
  });

  const response = await w.fetch(call("settleMatch"));

  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), {
    error: {
      status: "FAILED_PRECONDITION",
      message: "match is not settled yet",
      details: { matchId: "m1" },
    },
  });
});

test("when the engine is not wired, callables fail closed with 501 UNIMPLEMENTED", async () => {
  // engine: null forces the pre-engine-seam fail-closed table deterministically;
  // the auto-load fallback behaves the same when the bundle is absent (covered
  // by engine.test.mjs).
  const w = createWorker({ verifyToken, engine: null });

  for (const name of [
    "engineSmoke",
    "settleMatch",
    "resolveDecisionTimeout",
  ]) {
    const response = await w.fetch(call(name));
    assert.equal(response.status, 501, name);
    assert.equal(
      (await response.json()).error.status,
      "UNIMPLEMENTED",
      name,
    );
  }
});

test("the default verifier rejects even a well-formed bearer token", async () => {
  // `worker` is the module's default export: no injected verifier, so the
  // safe default applies. A token that a test double would accept must not
  // authenticate here.
  const response = await worker.fetch(call("engineSmoke"));

  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.status, "UNAUTHENTICATED");
});

test("malformed JSON -> 400 INVALID_ARGUMENT", async () => {
  const w = createWorker({ verifyToken });
  const request = new Request("https://worker.test/call/engineSmoke", {
    method: "POST",
    headers: {
      authorization: "Bearer test-token",
      "content-type": "application/json",
    },
    body: "{not json",
  });

  const response = await w.fetch(request);

  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.status, "INVALID_ARGUMENT");
});

test("body without data -> 400 INVALID_ARGUMENT", async () => {
  const w = createWorker({ verifyToken });
  const response = await w.fetch(
    call("engineSmoke", { body: { notData: true } }),
  );

  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.status, "INVALID_ARGUMENT");
});

test("non-POST callable -> 405", async () => {
  const w = createWorker({ verifyToken });
  const response = await w.fetch(
    call("engineSmoke", { method: "GET", body: undefined }),
  );

  assert.equal(response.status, 405);
});

test("non-GET /health -> 405", async () => {
  const w = createWorker({ verifyToken });
  const response = await w.fetch(
    new Request("https://worker.test/health", { method: "POST" }),
  );

  assert.equal(response.status, 405);
});

test("unknown route -> 404", async () => {
  const w = createWorker({ verifyToken });
  const response = await w.fetch(
    new Request("https://worker.test/nope"),
  );

  assert.equal(response.status, 404);
  assert.equal((await response.json()).error.status, "NOT_FOUND");
});

test("malformed percent-encoding in a callable name -> 400", async () => {
  const w = createWorker({ verifyToken });
  const response = await w.fetch(
    new Request("https://worker.test/call/%E0%A4%A", { method: "POST" }),
  );

  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.status, "INVALID_ARGUMENT");
});

test("undefined handler result serializes as null", async () => {
  const w = createWorker({
    verifyToken,
    handlers: { ...HANDLERS, engineSmoke: async () => undefined },
  });

  const response = await w.fetch(call("engineSmoke"));

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { result: null });
});
