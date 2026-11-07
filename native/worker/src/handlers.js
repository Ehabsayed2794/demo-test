// The callable handlers.
//
// Routing (O-06): for the two storage-backed callables, the worker goes
// through a Durable Object when `env.MATCH_DO` is bound — one DO per match,
// keyed `idFromName("match:" + rawMatchId)`. When the binding is absent,
// the handler falls back to the bundle's unwired path (fail-closed 501).
// `engineSmoke` always calls the bundle directly (no storage needed).
//
// Three wirings of the engine, all fail-closed by default:
//
//   engine = null        -> every handler throws 501 UNIMPLEMENTED
//                           (the scaffold contract; pinned by the tests).
//   engine = undefined    -> the built :authority-core bundle is loaded
//                           lazily; if it is missing, built stale, or
//                           unreadable, every handler throws 501
//                           UNIMPLEMENTED — never a fabricated result.
//   engine = <object>    -> your engine; used by tests.
//
// The engine interface (src/engine.js flattens the bundle's `WorkerApi`
// namespace onto these names; no firebase-* imports):
//   engineSmoke(uid)                       -> object
//   settleMatch(uid, data, sqlHandle)      -> Promise<object>
//   resolveDecisionTimeout(uid, data, sqlHandle) -> Promise<object>
// `sqlHandle` is the Durable Object's `ctx.storage.sql` under workerd, a
// hand-rolled fake in tests, or `null` at the HTTP edge (no database —
// the callable fails closed with UNIMPLEMENTED there).

import { authorityCode, callableError, isValidMatchId } from "./callable.js";
import { loadEngine } from "./engine.js";

const NOT_WIRED =
  "The Cloudflare runtime is not wired to the Kotlin/JS authority core. " +
  "Build :authority-core (gradlew :authority-core:jsProductionExecutableCompileSync) " +
  "or see native/worker/README.md.";

async function notWired() {
  throw callableError("UNIMPLEMENTED", NOT_WIRED);
}

const NOT_WIRED_HANDLERS = Object.freeze({
  engineSmoke: notWired,
  settleMatch: notWired,
  resolveDecisionTimeout: notWired,
});

function verifiedUid(auth) {
  const uid = auth?.uid;
  if (typeof uid !== "string" || uid.length === 0) {
    throw callableError(
      "UNAUTHENTICATED",
      "The request does not carry a verified identity.",
    );
  }
  return uid;
}

/**
 * The single place the core's result becomes either a callable result or a
 * thrown callableError. `{ ok: false, error: { code, message } }` maps onto
 * the envelope (lowercase-dash codes via authorityCode); any non-object
 * result fails closed to INTERNAL; anything else passes through untouched.
 */
export function unwrapCoreResult(result) {
  if (result === null || typeof result !== "object" || Array.isArray(result)) {
    throw callableError(
      "INTERNAL",
      "The authority layer returned a malformed response.",
    );
  }
  if (
    result.ok === false &&
    result.error &&
    typeof result.error.code === "string"
  ) {
    throw callableError(
      authorityCode(result.error.code),
      typeof result.error.message === "string" && result.error.message.length > 0
        ? result.error.message
        : result.error.code,
    );
  }
  return result;
}

async function callEngine(resolveEngine, name, { data, auth }) {
  const engine = await resolveEngine();
  if (!engine) {
    throw callableError("UNIMPLEMENTED", NOT_WIRED);
  }
  const uid = verifiedUid(auth);
  // The HTTP edge has no database of its own — always null here.
  const result = await engine[name](uid, data, null);
  return unwrapCoreResult(result);
}

export function createHandlers({ engine } = {}) {
  if (engine === null) {
    return NOT_WIRED_HANDLERS;
  }
  const resolveEngine =
    engine === undefined ? loadEngine : async () => engine;
  return Object.freeze({
    engineSmoke: (req) => callEngine(resolveEngine, "engineSmoke", req),
    settleMatch: (req) => callEngine(resolveEngine, "settleMatch", req),
    resolveDecisionTimeout: (req) =>
      callEngine(resolveEngine, "resolveDecisionTimeout", req),
  });
}

export const HANDLERS = createHandlers();

/**
 * The Durable Object RPC path. Selected when `env.MATCH_DO` is bound; the
 * bundle path is the fallback otherwise (fail-closed). The DO itself keeps
 * NO unauthenticated surface — it is only ever reached via stub.fetch from
 * this Worker, and it re-validates uid / data / matchId on every call.
 */
export function createDoHandlers({ namespace }) {
  const handlerFor = (name) => async ({ data, auth }) => {
    const uid = verifiedUid(auth);
    const matchId = data?.matchId;
    if (!isValidMatchId(matchId)) {
      throw callableError("INVALID_ARGUMENT", `${name}: matchId is required.`);
    }
    const path = name === "settleMatch" ? "/rpc/settle" : "/rpc/timeout";
    const stub = namespace.get(namespace.idFromName("match:" + matchId));

    let response;
    try {
      response = await stub.fetch(`https://match-do${path}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ uid, data }),
      });
    } catch {
      throw callableError(
        "UNAVAILABLE",
        "The match object is unavailable. Try again later.",
      );
    }

    let result;
    try {
      result = await response.json();
    } catch {
      throw callableError(
        "INTERNAL",
        "The match object returned an unreadable response.",
      );
    }
    return unwrapCoreResult(result);
  };

  return Object.freeze({
    settleMatch: handlerFor("settleMatch"),
    resolveDecisionTimeout: handlerFor("resolveDecisionTimeout"),
  });
}

/** env.MATCH_DO present → DO RPC; otherwise the bundle path (unwired). */
export function resolveHandlers({ env, fallback }) {
  const namespace = env?.MATCH_DO;
  if (!namespace) {
    return fallback;
  }
  return { ...fallback, ...createDoHandlers({ namespace }) };
}
