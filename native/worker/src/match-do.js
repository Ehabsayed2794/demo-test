// MatchDO — the Durable Object holding one match's authority state.
//
// One DO per match, addressed by idFromName("match:" + rawMatchId) from the
// outer Worker's router. Never publicly routable — only stub.fetch from the
// same Worker reaches it, and it re-validates everything it is given.
//
// Storage: its own private SQLite (exec). The schema is created idempotently
// on first construction from the bundle's single DO_SCHEMA source of truth
// (:authority-core — WorkerApi.kt exports it under the WorkerApi namespace).
// The Kotlin adapter (DoSettlementDb / DoTimeoutDb) does all reads/writes
// through sqlHandle — every call runs in one uninterrupted synchronous
// stretch, so reads and writes are atomic per the DO's implicit-transaction
// semantics.

import { DurableObject } from "cloudflare:workers";
import coreBundle from "../../authority-core/build/compileSync/js/main/productionExecutable/kotlin/authority-core.js";
import { isValidMatchId, jsonResponse } from "./callable.js";

export class MatchDO extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // First construction creates the tables; subsequent ones are no-ops.
    ctx.storage.sql.exec(coreBundle.WorkerApi.DO_SCHEMA);
    this.sql = ctx.storage.sql;
    // The object's own key, from idFromName("match:" + matchId). Kept as a
    // defense-in-depth check: a router bug must not let one match's request
    // rewrite another match's row even if it somehow reaches the wrong stub.
    const name = ctx.id?.name;
    this.matchId =
      typeof name === "string" && name.startsWith("match:")
        ? name.slice("match:".length)
        : null;
  }

  async fetch(request) {
    try {
      const url = new URL(request.url);
      const path = url.pathname;

      if (request.method !== "POST") {
        return jsonResponse(
          { error: { status: "INVALID_ARGUMENT", message: "RPC must use POST." } },
          405,
        );
      }
      const isSettle = path === "/rpc/settle";
      const isTimeout = path === "/rpc/timeout";
      if (!isSettle && !isTimeout) {
        return jsonResponse(
          { error: { status: "NOT_FOUND", message: `No route for ${path}.` } },
          404,
        );
      }

      let body;
      try {
        body = await request.json();
      } catch {
        return jsonResponse(
          { error: { status: "INVALID_ARGUMENT", message: "Request body is not valid JSON." } },
          400,
        );
      }
      if (body === null || typeof body !== "object" || Array.isArray(body)) {
        return jsonResponse(
          { error: { status: "INVALID_ARGUMENT", message: "Request body must be a JSON object." } },
          400,
        );
      }

      // The outer Worker has already authenticated; we still verify the
      // shape — a trusted caller can be buggy, not malicious.
      const uid = body.uid;
      if (typeof uid !== "string" || uid.trim().length === 0 || uid.length > 128) {
        return jsonResponse(
          { error: { status: "UNAUTHENTICATED", message: "The match object requires a trusted, well-formed uid." } },
          401,
        );
      }

      const data = body.data;
      if (data === null || typeof data !== "object" || Array.isArray(data)) {
        return jsonResponse(
          { error: { status: "INVALID_ARGUMENT", message: "data must be a JSON object." } },
          400,
        );
      }

      const name = isSettle ? "settleMatch" : "resolveDecisionTimeout";
      if (!isValidMatchId(data.matchId)) {
        return jsonResponse(
          { error: { status: "INVALID_ARGUMENT", message: `${name}: matchId is required.` } },
          400,
        );
      }
      if (this.matchId === null) {
        return jsonResponse(
          { error: { status: "INTERNAL", message: "The match object has no name." } },
          500,
        );
      }
      if (this.matchId !== data.matchId) {
        return jsonResponse(
          {
            error: {
              status: "INVALID_ARGUMENT",
              message: `${name}: matchId does not belong to this match object.`,
            },
          },
          400,
        );
      }

      const result = isSettle
        ? await coreBundle.WorkerApi.settleMatch(uid, data, this.sql)
        : await coreBundle.WorkerApi.resolveDecisionTimeout(uid, data, this.sql);

      // The raw core result — the outer Worker maps it onto the callable
      // envelope (one place, see unwrapCoreResult).
      return jsonResponse(result, 200);
    } catch {
      return jsonResponse(
        { error: { status: "INTERNAL", message: "The match object failed." } },
        500,
      );
    }
  }
}
