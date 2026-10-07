package com.estemshan.functions

/**
 * Callable-auth (S42): every authority-layer callable verifies the caller's
 * Firebase-verified identity before touching anything. The Functions runtime
 * validates [AuthToken] before the handler runs, so [AuthData.uid] is
 * trustworthy in a way a client-supplied claim is not — this is the seam
 * RD13's "authenticated identity" rests on, and it is why S70 can compare
 * ranks server-side without the client ever reading another player's rank.
 *
 * Split deliberately: [decide] is pure Kotlin with no JS dependency, so the
 * whole denial matrix is unit-testable on the JS target without loading the
 * Firebase SDK; [requireUid]/[requireUidFor] are the thin effectful seam the
 * callables use, turning a [Decision.Denied] into a thrown [HttpsError].
 */
object CallableAuth {

  /** Canonical denial codes the client switches on. */
  object Code {
    /** No verified identity on the request at all. */
    const val UNAUTHENTICATED = "unauthenticated"

    /** Authenticated, but acting on a resource that is not theirs (RD13). */
    const val PERMISSION_DENIED = "permission-denied"
  }

  sealed class Decision<out T> {
    /** Identity verified; the caller is [uid]. */
    data class Allowed<T>(val uid: T) : Decision<T>()

    /** Identity missing or wrong. [code] is one of [Code]. */
    data class Denied(val code: String, val message: String) : Decision<Nothing>()
  }

  /**
   * Pure: verifies the request without touching JS. Returns the caller's uid,
   * or the denial. Pass [claimedUid] to additionally assert the caller owns
   * that identity — RD13: a client may settle only its own actions.
   *
   * Fails with [Code.PERMISSION_DENIED] rather than [Code.UNAUTHENTICATED] on
   * an ownership mismatch, so the client can tell "not signed in" from
   * "signed in as someone else".
   */
  fun decide(request: CallableRequest, claimedUid: String? = null): Decision<String> {
    val uid = request.auth?.uid
    if (uid.isNullOrEmpty()) {
      return Decision.Denied(Code.UNAUTHENTICATED, "This action requires you to be signed in.")
    }
    if (claimedUid != null && uid != claimedUid) {
      return Decision.Denied(
        Code.PERMISSION_DENIED,
        "Identity mismatch: this action belongs to a different player.",
      )
    }
    return Decision.Allowed(uid)
  }

  /**
   * The caller's verified uid, or denies with [Code.UNAUTHENTICATED]. Use when
   * being signed in is the only requirement.
   */
  fun requireUid(request: CallableRequest): String = when (val d = decide(request)) {
    is Decision.Allowed -> d.uid
    is Decision.Denied -> throw HttpsError(d.code, d.message)
  }

  /**
   * The caller's verified uid, asserting it owns [claimedUid]. RD13: one
   * player cannot submit or settle another's result.
   */
  fun requireUidFor(request: CallableRequest, claimedUid: String): String =
    when (val d = decide(request, claimedUid)) {
      is Decision.Allowed -> d.uid
      is Decision.Denied -> throw HttpsError(d.code, d.message)
    }
}
