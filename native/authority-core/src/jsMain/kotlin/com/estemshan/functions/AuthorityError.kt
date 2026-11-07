package com.estemshan.functions

/**
 * The one failure type the authority core throws (S50/S61).
 *
 * Codes are Firebase's canonical lowercase-dash strings (`invalid-argument`,
 * `not-found`, `failed-precondition`, `permission-denied`, `unauthenticated`,
 * `unimplemented`, `internal`) — exactly what the Firebase callable runtime
 * puts on the wire, so the :functions adapter maps this 1:1 onto `HttpsError`
 * and the client stays byte-compatible with the pre-extraction behaviour.
 *
 * The Worker adapter (native/worker) maps the same codes onto its
 * SCREAMING_SNAKE envelope codes (`INVALID_ARGUMENT`, `NOT_FOUND`, ...) at
 * the boundary; both mappings live in exactly one place.
 *
 * Anything that is not an [AuthorityError] is a bug, not a denial: the
 * adapters map it to `internal` and never leak a stack trace.
 */
class AuthorityError(val code: String, override val message: String) : Throwable(message)
