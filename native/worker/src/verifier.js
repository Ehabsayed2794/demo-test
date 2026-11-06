// Production Firebase ID-token verifier for the Worker.
//
// The default `verifyToken` in app.js rejects every request (the scaffold
// default). This module is the real replacement: it verifies a Firebase ID
// token (a Google-signed RS256 JWT) and returns `{ uid }` on success, or
// `null` when the token is invalid. Network failures while fetching keys
// THROW, so the dispatch layer can answer 503 UNAVAILABLE instead of 401.
//
// Wiring: src/index.js builds this verifier from env (`FIREBASE_PROJECT_ID`
// + optional `FIREBASE_JWKS_URL`, `FIREBASE_PROJECT_NUMBER` is not needed)
// and passes it to createWorker. When the project id is absent the verifier
// is not built and the default (reject-all) stays in force — fail closed.
//
// Checks (per Firebase "verify ID tokens" manual verification):
//   1. JWT shape (three base64url segments), header alg == RS256, kid present.
//   2. Signature verified over `header.payload` with the JWKS key for `kid`,
//      using WebCrypto RSASSA-PKCS1-v1_5 / SHA-256.
//   3. `iss` == https://securetoken.googleapis.com/{projectId}
//      `aud` == projectId
//      `exp` > now, `iat` <= now (+60s skew), `auth_time` <= now
//      `sub` non-empty (becomes the uid).
//
// Keys are cached per verifier instance and refreshed when a `kid` is unknown
// or the cache exceeds the TTL. No clock dependency is injected: the tests
// sign with a real generated RSA key and stub `fetch`, so time comes from
// `Date.now()`.

const DEFAULT_JWKS_URL =
  "https://www.googleapis.com/service_accounts/projects";
const JWKS_TTL_MS = 6 * 60 * 60 * 1000;
const CLOCK_SKEW_SECONDS = 60;

function base64UrlDecode(segment) {
  const normalized = segment.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function parseJsonSegment(segment) {
  const json = new TextDecoder().decode(base64UrlDecode(segment));
  return JSON.parse(json);
}

async function importPublicKey(jwk) {
  return crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
}

export function createFirebaseVerifier({
  projectId,
  jwksUrl = DEFAULT_JWKS_URL,
  fetchImpl = fetch,
} = {}) {
  if (typeof projectId !== "string" || projectId.length === 0) {
    throw new Error("createFirebaseVerifier requires a projectId.");
  }
  const expectedIssuer = `https://securetoken.googleapis.com/${projectId}`;
  let cache = null;

  async function fetchKeys() {
    const response = await fetchImpl(jwksUrl);
    if (!response.ok) {
      throw new Error("key fetch failed");
    }
    const body = await response.json();
    if (!body || !Array.isArray(body.keys)) {
      throw new Error("key fetch returned a malformed JWKS");
    }
    cache = { keys: body.keys, fetchedAt: Date.now() };
    return cache.keys;
  }

  async function keysFor(kid) {
    if (
      !cache ||
      Date.now() - cache.fetchedAt > JWKS_TTL_MS ||
      !cache.keys.some((key) => key.kid === kid)
    ) {
      await fetchKeys();
    }
    return cache.keys;
  }

  return async function verifyToken(token) {
    if (typeof token !== "string" || token.length === 0) {
      return null;
    }
    const parts = token.split(".");
    if (parts.length !== 3) {
      return null;
    }
    let header;
    let payload;
    try {
      header = parseJsonSegment(parts[0]);
      payload = parseJsonSegment(parts[1]);
    } catch {
      return null;
    }
    if (header?.alg !== "RS256" || typeof header?.kid !== "string") {
      return null;
    }
    const keys = await keysFor(header.kid);
    const jwk = keys.find((key) => key.kid === header.kid);
    if (!jwk || jwk.kty !== "RSA" || jwk.alg !== "RS256" || jwk.use !== "sig") {
      return null;
    }
    let key;
    try {
      key = await importPublicKey(jwk);
    } catch {
      return null;
    }
    let signature;
    let signingInput;
    try {
      signature = base64UrlDecode(parts[2]);
      signingInput = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
    } catch {
      return null;
    }
    let valid;
    try {
      valid = await crypto.subtle.verify(
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        key,
        signature,
        signingInput,
      );
    } catch {
      return null;
    }
    if (!valid) {
      return null;
    }
    const now = Math.floor(Date.now() / 1000);
    if (payload?.iss !== expectedIssuer) return null;
    if (payload?.aud !== projectId) return null;
    if (typeof payload?.sub !== "string" || payload.sub.length === 0) {
      return null;
    }
    if (typeof payload?.exp !== "number" || payload.exp <= now) return null;
    if (typeof payload?.iat !== "number" || payload.iat > now + CLOCK_SKEW_SECONDS) {
      return null;
    }
    if (typeof payload?.auth_time !== "number" || payload.auth_time > now) {
      return null;
    }
    return { uid: payload.sub };
  };
}
