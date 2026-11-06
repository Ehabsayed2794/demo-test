// Firebase-callable wire format, kept deliberately small.
//
// The client already speaks the Firebase callable protocol: POST with a
// `Authorization: Bearer <id-token>` header and a `{ "data": ... }` body; the
// server answers `{ "result": ... }` on success or `{ "error": { status,
// message, details? } }` with a non-2xx status on failure. Keeping that shape
// means the Android client can switch runtimes without a protocol rewrite.
//
// The codes are Firebase's canonical callable codes. `httpStatusFor` maps them
// to the HTTP status the Firebase SDK would produce.

const HTTP_STATUS = Object.freeze({
  OK: 200,
  CANCELLED: 499,
  UNKNOWN: 500,
  INVALID_ARGUMENT: 400,
  DEADLINE_EXCEEDED: 504,
  NOT_FOUND: 404,
  ALREADY_EXISTS: 409,
  PERMISSION_DENIED: 403,
  RESOURCE_EXHAUSTED: 429,
  FAILED_PRECONDITION: 400,
  ABORTED: 409,
  OUT_OF_RANGE: 400,
  UNIMPLEMENTED: 501,
  INTERNAL: 500,
  UNAVAILABLE: 503,
  DATA_LOSS: 500,
  UNAUTHENTICATED: 401,
});

export function httpStatusFor(code) {
  return HTTP_STATUS[code] ?? 500;
}

/**
 * Maps a lowercase-dash canonical code from the Kotlin authority core
 * (`invalid-argument`, `failed-precondition`, ...) onto the
 * SCREAMING_SNAKE envelope codes this worker speaks. Unknown codes fail
 * closed to INTERNAL rather than passing through unmapped.
 */
export function authorityCode(code) {
  const mapped = String(code ?? "").trim().toUpperCase().replace(/-/g, "_");
  return Object.prototype.hasOwnProperty.call(HTTP_STATUS, mapped)
    ? mapped
    : "INTERNAL";
}

export function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

export function ok(result) {
  // `undefined` is not JSON; the callable protocol's empty result is null.
  return jsonResponse({ result: result === undefined ? null : result });
}

export function fail(code, message, details) {
  const error = { status: code, message };
  if (details !== undefined) {
    error.details = details;
  }
  return jsonResponse({ error }, httpStatusFor(code));
}

/**
 * A handler throws this to return a typed callable error instead of a 500.
 * The dispatcher preserves `code`, `message` and `details`.
 */
export function callableError(code, message, details) {
  const error = new Error(message ?? code);
  error.name = "CallableError";
  error.code = code;
  if (details !== undefined) {
    error.details = details;
  }
  return error;
}

/**
 * A matchId is safe to route on iff it is a short, printable, separator-
 * and space-free string. Used by the router (before any DO hop) and
 * re-validated inside the Durable Object — both fail closed otherwise.
 */
export function isValidMatchId(value) {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= 128 &&
    // Printable ASCII excluding the space character (0x20) and anything
    // below it; plus no path separators after the range check.
    /^[!-~]{1,128}$/.test(value) &&
    !/[/\\]/.test(value)
  );
}

export function bearerToken(request) {
  const header = request.headers.get("authorization");
  if (!header) {
    return null;
  }
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match ? match[1].trim() : null;
}

export function methodNotAllowed(message) {
  return jsonResponse(
    { error: { status: "INVALID_ARGUMENT", message } },
    405,
  );
}
