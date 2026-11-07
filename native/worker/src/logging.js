// Structured logging for the Worker — one JSON object per line on stdout.
//
// Logs carry only operational fields (event, callable/status, error code,
// duration). They never carry the bearer token, the raw request body, or
// free-text error messages from the engine (which may embed document
// contents). `uid` is logged because it is the verifier's output, not a
// secret — the token that produced it is never logged.

export function logEvent(event, fields = {}) {
  const record = { event, ...fields };
  console.log(JSON.stringify(record));
}
