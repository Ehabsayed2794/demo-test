package com.estemshan.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Callable-auth's decision matrix. [CallableAuth.decide] is pure Kotlin, so
 * these run without loading the Firebase SDK — the throw path itself (the
 * HttpsError construction) is JS interop and is exercised by the emulator
 * suite in CI, not here.
 */
class CallableAuthTest {

  @Test
  fun allowsAuthenticatedRequest() {
    val request = fakeRequest(uid = "player-1")
    assertEquals(
      CallableAuth.Decision.Allowed("player-1"),
      CallableAuth.decide(request),
    )
  }

  @Test
  fun deniesWhenAuthIsAbsent() {
    val request = fakeRequest(uid = null)
    val decision = CallableAuth.decide(request)
    assertEquals(CallableAuth.Code.UNAUTHENTICATED, (decision as CallableAuth.Decision.Denied).code)
  }

  @Test
  fun deniesWhenUidIsEmpty() {
    val request = fakeRequest(uid = "")
    val decision = CallableAuth.decide(request)
    assertEquals(CallableAuth.Code.UNAUTHENTICATED, (decision as CallableAuth.Decision.Denied).code)
  }

  @Test
  fun allowsWhenCallerOwnsTheClaimedIdentity() {
    val request = fakeRequest(uid = "player-1")
    assertEquals(
      CallableAuth.Decision.Allowed("player-1"),
      CallableAuth.decide(request, claimedUid = "player-1"),
    )
  }

  @Test
  fun deniesWhenCallerClaimsAnotherIdentity() {
    // RD13: a client may settle only its own actions. The denial is
    // PERMISSION_DENIED, not UNAUTHENTICATED, so the client can tell the two
    // failure modes apart.
    val request = fakeRequest(uid = "player-1")
    val decision = CallableAuth.decide(request, claimedUid = "player-2")
    assertEquals(CallableAuth.Code.PERMISSION_DENIED, (decision as CallableAuth.Decision.Denied).code)
  }

  private fun fakeRequest(uid: String?): CallableRequest = object : CallableRequest {
    override val data: Any? = null
    override val auth: AuthData? = uid?.let { object : AuthData {
      override val uid: String = it
      override val token: AuthToken? = null
    } }
    override val instanceIdToken: String? = null
    override val rawRequest: Any? = null
    override val app: Any? = null
  }
}
