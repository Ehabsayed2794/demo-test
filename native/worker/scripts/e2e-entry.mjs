// TEST-ONLY workerd entry for the seeded O-06 E2E. NEVER DEPLOYED.
//
// The production entry is `src/index.js`, selected by `wrangler.toml` and
// unchanged by this file. The E2E selects this entry explicitly with
// `wrangler dev --config wrangler.e2e.toml`, whose `main` points here.
//
// It wraps the production `createWorker` with a verifier that accepts
// `Authorization: Bearer e2e:<uid>` — a credential that exists only in this
// file. The production default verifier still rejects every request; there is
// no env var, flag, or code path in `src/` that enables this token.
//
// Everything else is the real thing: the real router, the real auth gate, the
// real callable envelope, the real MatchDO, the real Kotlin core, real SQLite.

import { createWorker } from "../src/app.js";
import { MatchDO } from "../src/match-do.js";

export { MatchDO };

const worker = createWorker({
  verifyToken: async (token) => {
    if (!token.startsWith("e2e:")) return null;
    const uid = token.slice("e2e:".length);
    return uid.length > 0 && uid.length <= 128 ? { uid } : null;
  },
});

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    // Test-only probe for the DO's own-key guard: address the golden object
    // but send a different matchId. The production router can never produce
    // this pair (it derives the key from the payload), so this route lives in
    // the TEST entry — not in the DO — and calls the same RPC surface.
    if (url.pathname === "/e2e/mismatch") {
      const doName = url.searchParams.get("do") ?? "";
      const dataMatchId = url.searchParams.get("match") ?? "";
      const stub = env.MATCH_DO.get(env.MATCH_DO.idFromName("match:" + doName));
      return stub.fetch("https://match-do/rpc/settle", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ uid: "u1", data: { matchId: dataMatchId } }),
      });
    }
    return worker.fetch(request, env, ctx);
  },
};
