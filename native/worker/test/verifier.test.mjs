// Production-verifier tests against a REAL generated RSA keypair.
//
// The JWKS provider is stubbed (no network), but the signature, claims and
// project/issuer/audience checks are the genuine production code path: the
// test signs tokens with a private key it alone holds, so a verifier that
// skipped signature or claims checks would fail here on the negative cases.

import test from "node:test";
import assert from "node:assert/strict";

import { createFirebaseVerifier } from "../src/verifier.js";
import { createWorker } from "../src/app.js";

const PROJECT_ID = "estemshan-test";
const KID = "test-key-1";
const NOW = Math.floor(Date.now() / 1000);

let keyPair;
let publicJwk;

async function init() {
  keyPair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const exported = await crypto.subtle.exportKey("jwk", keyPair.publicKey);
  publicJwk = { ...exported, kid: KID, alg: "RS256", use: "sig" };
}

function toBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sign(payload, { header = {}, key = keyPair?.privateKey } = {}) {
  const fullHeader = { alg: "RS256", kid: KID, ...header };
  const head = toBase64Url(new TextEncoder().encode(JSON.stringify(fullHeader)));
  const body = toBase64Url(new TextEncoder().encode(JSON.stringify(payload)));
  const input = new TextEncoder().encode(`${head}.${body}`);
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    key,
    input,
  );
  return `${head}.${body}.${toBase64Url(new Uint8Array(signature))}`;
}

function claims(overrides = {}) {
  return {
    iss: `https://securetoken.googleapis.com/${PROJECT_ID}`,
    aud: PROJECT_ID,
    sub: "player-uid-1",
    exp: NOW + 3600,
    iat: NOW - 10,
    auth_time: NOW - 10,
    ...overrides,
  };
}

function jwksResponse() {
  return new Response(JSON.stringify({ keys: [publicJwk] }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function verifier() {
  let calls = 0;
  const fetchImpl = async () => {
    calls += 1;
    return jwksResponse();
  };
  return {
    verify: createFirebaseVerifier({ projectId: PROJECT_ID, fetchImpl }),
    calls: () => calls,
  };
}

test("a correctly signed token for this project verifies to its uid", async () => {
  await init();
  const { verify } = verifier();
  const token = await sign(claims());
  assert.deepEqual(await verify(token), { uid: "player-uid-1" });
});

test("a token with a tampered payload does not verify", async () => {
  await init();
  const { verify } = verifier();
  const token = await sign(claims({ sub: "attacker" }));
  const parts = token.split(".");
  const forgedBody = toBase64Url(
    new TextEncoder().encode(JSON.stringify(claims({ sub: "victim" }))),
  );
  assert.equal(await verify(`${parts[0]}.${forgedBody}.${parts[2]}`), null);
});

test("the wrong project id does not verify", async () => {
  await init();
  const { verify } = verifier();
  const token = await sign(
    claims({
      iss: "https://securetoken.googleapis.com/other-project",
      aud: "other-project",
    }),
  );
  assert.equal(await verify(token), null);
});

test("an expired token does not verify", async () => {
  await init();
  const { verify } = verifier();
  const token = await sign(claims({ exp: NOW - 1 }));
  assert.equal(await verify(token), null);
});

test("a non-RS256 algorithm is rejected without a signature check", async () => {
  await init();
  const { verify } = verifier();
  const head = toBase64Url(
    new TextEncoder().encode(JSON.stringify({ alg: "none", kid: KID })),
  );
  const body = toBase64Url(new TextEncoder().encode(JSON.stringify(claims())));
  assert.equal(await verify(`${head}.${body}.`), null);
});

test("a missing or unknown kid does not verify", async () => {
  await init();
  const { verify } = verifier();
  const noKid = await sign(claims(), { header: { kid: undefined } });
  assert.equal(await verify(noKid), null);
  const unknownKid = await sign(claims(), { header: { kid: "other-key" } });
  assert.equal(await verify(unknownKid), null);
});

test("a malformed token does not verify and never throws", async () => {
  await init();
  const { verify } = verifier();
  assert.equal(await verify("not-a-jwt"), null);
  assert.equal(await verify("a.b"), null);
  assert.equal(await verify("a.b.c"), null);
  assert.equal(await verify(null), null);
});

test("key fetch failures throw (the dispatch layer answers 503, not 401)", async () => {
  await init();
  const verify = createFirebaseVerifier({
    projectId: PROJECT_ID,
    fetchImpl: async () => {
      throw new Error("network down");
    },
  });
  const token = await sign(claims());
  await assert.rejects(() => verify(token), /network down/);
});

test("the verifier plugs into the Worker as an injected verifyToken", async () => {
  await init();
  const { verify } = verifier();
  const w = createWorker({ verifyToken: verify });
  const token = await sign(claims());
  const request = new Request("https://worker.test/call/engineSmoke", {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ data: {} }),
  });
  const response = await w.fetch(request);
  assert.equal(response.status, 200);
  const body = await response.json();
  // engineSmoke runs the bundle if built; at minimum it is not a 401.
  assert.notEqual(response.status, 401, JSON.stringify(body));
});

test("without a project id the production wiring denies every request", async () => {
  const w = createWorker();
  const request = new Request("https://worker.test/call/engineSmoke", {
    method: "POST",
    headers: {
      authorization: "Bearer anything-at-all",
      "content-type": "application/json",
    },
    body: JSON.stringify({ data: {} }),
  });
  const response = await w.fetch(request, {});
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.status, "UNAUTHENTICATED");
});

test("callable logs never carry the bearer token", async () => {
  await init();
  const { verify } = verifier();
  const w = createWorker({ verifyToken: verify });
  const token = await sign(claims());
  const lines = [];
  const original = console.log;
  console.log = (message) => {
    lines.push(String(message));
  };
  try {
    await w.fetch(
      new Request("https://worker.test/call/engineSmoke", {
        method: "POST",
        headers: {
          authorization: `Bearer ${token}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({ data: {} }),
      }),
    );
  } finally {
    console.log = original;
  }
  assert.ok(lines.length >= 1, "expected at least one log line");
  for (const line of lines) {
    assert.equal(line.includes(token), false, "the token must never be logged");
  }
  const record = JSON.parse(lines[0]);
  assert.equal(record.event, "callable");
  assert.equal(record.name, "engineSmoke");
  assert.equal(typeof record.status, "number");
});
