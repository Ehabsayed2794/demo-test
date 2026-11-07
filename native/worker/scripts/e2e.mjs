// E2E driver for the seeded O-06 verification.
//
// Drives the PRODUCTION `/call/*` router of a locally running workerd
// (scripts/e2e-entry.mjs supplies the test-only `e2e:<uid>` credential), so
// the auth gate, the DO router, the callable envelope and the real MatchDO /
// Kotlin core / SQLite are all on the exercised path.
//
// Usage:
//   node scripts/e2e.mjs --preflight        # create the three DOs (fresh state)
//   node scripts/e2e.mjs                    # run the assertions
//   node scripts/e2e.mjs --base=http://127.0.0.1:8799
//
// The seeded fixtures live in scripts/seed-e2e.mjs. The procedure is
// documented in docs/implementation/Stage0_O06_DO_D1.md.

const args = process.argv.slice(2);
const PREFLIGHT = args.includes("--preflight");
const baseArg = args.find((a) => a.startsWith("--base="));
const BASE = baseArg ? baseArg.slice("--base=".length) : "http://127.0.0.1:8799";

const MATCHES = ["e2e-room", "e2e-incomplete", "e2e-golden"];
const GOLDEN_SCORES = { p1: 25, p2: -11, p3: 12, p4: 14 };

let passed = 0;
const failures = [];

function check(label, condition, detail) {
  if (condition) {
    passed++;
    console.log(`PASS  ${label}`);
  } else {
    failures.push(`${label}${detail === undefined ? "" : ` — ${detail}`}`);
    console.log(`FAIL  ${label}${detail === undefined ? "" : ` — ${detail}`}`);
  }
}

async function request(path, { method = "POST", body, token, headers = {} } = {}) {
  const finalHeaders = { ...headers };
  if (token !== undefined) finalHeaders.authorization = `Bearer ${token}`;
  if (body !== undefined) finalHeaders["content-type"] = "application/json";
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: finalHeaders,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    parsed = text;
  }
  return { status: response.status, body: parsed };
}

function call(name, data, { uid = "u1", token } = {}) {
  return request(`/call/${name}`, {
    body: { data },
    token: token ?? `e2e:${uid}`,
  });
}

function hasError(response, status, label) {
  const body = response.body;
  const ok =
    body &&
    typeof body === "object" &&
    body.error &&
    body.error.status === status &&
    typeof body.error.message === "string";
  check(label, ok, `status=${response.status} body=${JSON.stringify(body)}`);
}

async function preflight() {
  console.log(`preflight against ${BASE}`);
  for (const matchId of MATCHES) {
    // The DO is constructed on first access; an empty store answers NOT_FOUND
    // for a settle. Any well-formed envelope proves the object was created.
    const response = await call("settleMatch", { matchId });
    const wellFormed =
      response.body &&
      typeof response.body === "object" &&
      (Object.hasOwn(response.body, "result") || Object.hasOwn(response.body, "error"));
    check(
      `preflight ${matchId} creates the DO (HTTP ${response.status})`,
      wellFormed && response.status < 500,
      JSON.stringify(response.body),
    );
  }
  finish();
}

async function assertions() {
  console.log(`E2E against ${BASE}`);

  const health = await request("/health", { method: "GET" });
  check(
    "GET /health -> 200 with the callable manifest",
    health.status === 200 &&
      health.body?.ok === true &&
      Array.isArray(health.body?.callables) &&
      ["engineSmoke", "settleMatch", "resolveDecisionTimeout"].every((n) =>
        health.body.callables.includes(n),
      ),
    JSON.stringify(health.body),
  );

  const unauthenticated = await request("/call/settleMatch", {
    body: { data: { matchId: "e2e-golden" } },
  });
  check(
    "no bearer token -> 401 UNAUTHENTICATED",
    unauthenticated.status === 401 &&
      unauthenticated.body?.error?.status === "UNAUTHENTICATED",
    JSON.stringify(unauthenticated.body),
  );

  const unknown = await call("notACallable", {});
  check(
    "unknown callable -> 404 NOT_FOUND",
    unknown.status === 404 && unknown.body?.error?.status === "NOT_FOUND",
    JSON.stringify(unknown.body),
  );

  const notJson = await request("/call/settleMatch", {
    body: undefined,
    token: "e2e:u1",
    headers: { "content-type": "application/json" },
  });
  check(
    "empty body -> 400 INVALID_ARGUMENT",
    notJson.status === 400 && notJson.body?.error?.status === "INVALID_ARGUMENT",
    JSON.stringify(notJson.body),
  );

  const missingMatchId = await call("settleMatch", {});
  check(
    "missing matchId -> 400 INVALID_ARGUMENT",
    missingMatchId.status === 400 &&
      missingMatchId.body?.error?.status === "INVALID_ARGUMENT",
    JSON.stringify(missingMatchId.body),
  );

  const badMatchId = await call("settleMatch", { matchId: "a/b" });
  check(
    "matchId with a path separator -> 400 INVALID_ARGUMENT",
    badMatchId.status === 400 &&
      badMatchId.body?.error?.status === "INVALID_ARGUMENT",
    JSON.stringify(badMatchId.body),
  );

  const smoke = await call("engineSmoke", {});
  check(
    "engineSmoke -> 52 unique cards (direct bundle path, no DO)",
    smoke.status === 200 &&
      smoke.body?.result?.totalCards === 52 &&
      smoke.body?.result?.uniqueCards === 52,
    JSON.stringify(smoke.body),
  );

  const room = await call("settleMatch", { matchId: "e2e-room" });
  check(
    "ROOM settle -> 200 no-op (NOT_RANKED)",
    room.status === 200 &&
      room.body?.result?.ok === true &&
      room.body?.result?.settled === false &&
      room.body?.result?.reason === "NOT_RANKED" &&
      room.body?.result?.mode === "ROOM",
    JSON.stringify(room.body),
  );

  const incomplete = await call("settleMatch", { matchId: "e2e-incomplete" });
  check(
    "incomplete RANKED settle -> 400 FAILED_PRECONDITION",
    incomplete.status === 400 &&
      incomplete.body?.error?.status === "FAILED_PRECONDITION" &&
      /not complete/.test(incomplete.body?.error?.message ?? ""),
    JSON.stringify(incomplete.body),
  );

  const intruder = await call("settleMatch", { matchId: "e2e-golden" }, { uid: "intruder" });
  hasError(intruder, "PERMISSION_DENIED", "non-player settle -> 403 PERMISSION_DENIED");
  check(
    "non-player denial happens before any write",
    /not a player/.test(intruder.body?.error?.message ?? ""),
    JSON.stringify(intruder.body),
  );

  const golden = await call("settleMatch", { matchId: "e2e-golden" }, { uid: "u1" });
  check(
    "golden settle -> corrected finalScores",
    golden.status === 200 &&
      golden.body?.result?.ok === true &&
      golden.body?.result?.settled === true &&
      golden.body?.result?.replayed === false &&
      golden.body?.result?.corrected === true &&
      JSON.stringify(golden.body?.result?.finalScores) === JSON.stringify(GOLDEN_SCORES) &&
      JSON.stringify(golden.body?.result?.winnerIds) === JSON.stringify(["p1"]),
    JSON.stringify(golden.body),
  );

  const replay = await call("settleMatch", { matchId: "e2e-golden" }, { uid: "u2" });
  check(
    "replay -> replayed:true with the same corrected scores",
    replay.status === 200 &&
      replay.body?.result?.replayed === true &&
      JSON.stringify(replay.body?.result?.finalScores) === JSON.stringify(GOLDEN_SCORES),
    JSON.stringify(replay.body),
  );

  const timeout = await call("resolveDecisionTimeout", { matchId: "e2e-room" });
  check(
    "timeout with no pending decision -> NOT_ACTIVE no-write",
    timeout.status === 200 &&
      timeout.body?.result?.ok === true &&
      timeout.body?.result?.resolved === false &&
      timeout.body?.result?.reason === "NOT_ACTIVE",
    JSON.stringify(timeout.body),
  );

  const timeoutIntruder = await call(
    "resolveDecisionTimeout",
    { matchId: "e2e-room" },
    { uid: "intruder" },
  );
  hasError(
    timeoutIntruder,
    "PERMISSION_DENIED",
    "non-player timeout -> 403 PERMISSION_DENIED",
  );

  const timeoutMissing = await call("resolveDecisionTimeout", { matchId: "e2e-missing" });
  hasError(timeoutMissing, "NOT_FOUND", "unknown match timeout -> 404 NOT_FOUND");

  const mismatch = await request("/e2e/mismatch?do=e2e-golden&match=e2e-room", {
    method: "POST",
  });
  check(
    "DO rejects a matchId that is not its own key",
    mismatch.status === 400 &&
      mismatch.body?.error?.status === "INVALID_ARGUMENT" &&
      /does not belong/.test(mismatch.body?.error?.message ?? ""),
    JSON.stringify(mismatch.body),
  );

  finish();
}

function finish() {
  console.log(`\n${passed} passed, ${failures.length} failed`);
  if (failures.length > 0) {
    for (const failure of failures) console.error(`  - ${failure}`);
    process.exit(1);
  }
}

(PREFLIGHT ? preflight() : assertions()).catch((error) => {
  console.error(`E2E driver crashed: ${error?.stack ?? error}`);
  process.exit(1);
});
