// Application wiring for the Estemshan Ranked authority Worker.
//
// This module is imported by the Worker's entry point (src/index.js, which
// also exports the MatchDO class for wrangler) and by the tests. It never
// imports "cloudflare:workers", so plain Node can load it.
//
// Routes:
//   GET  /health          -> liveness + the callable manifest
//   POST /call/<name>     -> Firebase-callable envelope
//
// The two storage-backed callables go to the Durable Object when
// env.MATCH_DO is bound; otherwise they fall back to the (unwired) bundle,
// which fails closed with 501. engineSmoke always calls the bundle
// directly (no storage needed).

import { CALLABLES, assertManifest } from "./manifest.js";
import { createHandlers, resolveHandlers, HANDLERS } from "./handlers.js";
import {
  bearerToken,
  fail,
  jsonResponse,
  methodNotAllowed,
  ok,
} from "./callable.js";

const SERVICE = "estemshan-ranked";

// Boot-time guard: a manifest/handler mismatch must stop the Worker, not turn
// into a runtime 500 on the first request.
assertManifest(HANDLERS);

function health() {
  return jsonResponse({
    ok: true,
    service: SERVICE,
    runtime: "cloudflare-worker",
    callables: CALLABLES,
  });
}

async function dispatch(request, name, verifyToken, handlers) {
  if (request.method !== "POST") {
    return methodNotAllowed("Callable requests must use POST.");
  }

  if (!Object.hasOwn(handlers, name)) {
    return fail("NOT_FOUND", `Unknown callable: ${name}`);
  }

  // Auth is checked before the body is read and before the handler runs. The
  // verifier is injected so the scaffold has no token dependency; the default
  // verifier returns null and therefore rejects everything. A verifier that
  // THROWS (its network is down, it can't reach the identity provider, ...) must
  // not reject the whole fetch into a non-JSON 500 — degrade to a JSON envelope.
  let auth;
  try {
    const token = bearerToken(request);
    auth = token ? await verifyToken(token) : null;
  } catch {
    return fail(
      "UNAVAILABLE",
      "The token verifier is unavailable. Try again later.",
    );
  }
  if (!auth) {
    return fail(
      "UNAUTHENTICATED",
      "The request does not carry a verified identity.",
    );
  }

  let body;
  try {
    body = await request.json();
  } catch {
    return fail("INVALID_ARGUMENT", "Request body is not valid JSON.");
  }

  if (
    body === null ||
    typeof body !== "object" ||
    Array.isArray(body) ||
    !Object.hasOwn(body, "data")
  ) {
    return fail(
      "INVALID_ARGUMENT",
      "Request body must be a JSON object with a data field.",
    );
  }

  try {
    const result = await handlers[name]({ data: body.data, auth, request });
    return ok(result);
  } catch (error) {
    if (error && typeof error === "object" && typeof error.code === "string") {
      return fail(
        error.code,
        typeof error.message === "string" && error.message.length > 0
          ? error.message
          : error.code,
        error.details,
      );
    }
    return fail("INTERNAL", "The callable failed.");
  }
}

/**
 * Builds a Worker with an injectable token verifier and handler table.
 *
 * Production wiring will pass a verifier that checks the caller's Firebase ID
 * token (or the chosen identity provider) and returns `{ uid, ... }`. The
 * default verifier rejects every request, which is the safe default for a
 * scaffold with no credentials.
 *
 * `engine`: pass `null` to force fail-closed handlers (the pre-engine-seam
 * behaviour, pinned by the tests), an object with the three entry points to
 * inject your own engine (the O-06 slice passes a DO/D1-backed adapter),
 * or omit it to load the built :authority-core bundle lazily — falling
 * back to 501 UNIMPLEMENTED when the bundle is absent.
 *
 * The Durable Object route activates per request when `env.MATCH_DO` is
 * bound (wrangler.toml) — no restart or code change needed.
 */
export function createWorker({
  verifyToken = async () => null,
  handlers,
  engine,
} = {}) {
  const table = handlers ?? createHandlers({ engine });
  assertManifest(table);

  return {
    async fetch(request, env, ctx) {
      // Last-resort envelope: an unexpected error anywhere above must never
      // escape as a non-JSON workerd 500 — clients switch on this shape.
      try {
        const url = new URL(request.url);

        if (url.pathname === "/health") {
          if (request.method !== "GET") {
            return methodNotAllowed("Use GET /health.");
          }
          return health();
        }

        if (url.pathname.startsWith("/call/")) {
          const raw = url.pathname.slice("/call/".length);
          let name;
          try {
            name = decodeURIComponent(raw);
          } catch {
            return fail("INVALID_ARGUMENT", "Malformed callable name.");
          }
          // Route storage-backed callables to the Durable Object when the
          // binding is present; otherwise the fallback table is used.
          return await dispatch(
            request,
            name,
            verifyToken,
            resolveHandlers({ env, fallback: table }),
          );
        }

        return fail("NOT_FOUND", `No route for ${url.pathname}.`);
      } catch {
        return fail("INTERNAL", "The callable failed.");
      }
    },
  };
}

export { HANDLERS };
export default createWorker();
