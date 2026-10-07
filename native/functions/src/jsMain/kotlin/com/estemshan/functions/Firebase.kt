@file:JsModule("firebase-functions/v2/https")
@file:JsNonModule

package com.estemshan.functions

/**
 * Minimal JS interop for the two Firebase surfaces the authority layer needs.
 * Deliberately small: declare only what S42's scaffold and the downstream
 * Ranked callables (S50/S51/S70) use, and keep it typed rather than `dynamic`
 * so a wrong call shape fails at compile time.
 *
 * Firebase-side facts this encodes:
 *  - `onCall` registers a 2nd-gen callable; the Firebase CLI discovers it by
 *    requiring the module and inspecting the exported members.
 *  - `HttpsError` is the typed denial path — the client receives `code`,
 *    `message`, and `details`. S70's `RANK_INCOMPATIBLE` and every other
 *    denial reason rides on this.
 */

// ---- firebase-functions/v2/https ------------------------------------------

external object Https {
  /**
   * Defines a 2nd-gen callable. The returned value is exported from this
   * module (see Functions.kt) so the Firebase CLI picks it up.
   */
  fun onCall(handler: (CallableRequest) -> Any?): dynamic
}

/** The request the callable receives. `auth` is null for unauthenticated calls. */
external interface CallableRequest {
  val data: Any?
  val auth: AuthData?
  val instanceIdToken: String?
  val rawRequest: Any?
  val app: Any?
}

/** Firebase-verified caller identity. The Functions runtime validates this
 *  token — the client cannot forge it, which is the whole point of callable-auth. */
external interface AuthData {
  val uid: String
  val token: AuthToken?
}

external interface AuthToken {
  val email: String?
  val emailVerified: Boolean?
  val name: String?
  val picture: String?
  val issuer: String?
}

/**
 * The typed denial. `code` is a canonical string the client switches on.
 *
 * Extends [Throwable] as a *compile-time* claim only. Kotlin's `throw` accepts
 * only Throwable, but this is `external`, so Kotlin emits a plain
 * `require("firebase-functions/v2/https").HttpsError(...)` — the supertype
 * exists purely to satisfy the type checker and has no runtime effect. The
 * constructed object is the real firebase-functions HttpsError, so the
 * Functions runtime's own `instanceof` check still recognises it and the
 * client receives `code`/`message`/`details` rather than a generic INTERNAL.
 */
external class HttpsError(
  code: String,
  message: String,
  details: Any? = definedExternally,
) : Throwable
