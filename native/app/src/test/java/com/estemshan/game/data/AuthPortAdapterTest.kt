package com.estemshan.game.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S14: [AuthPortAdapter] reads the uid straight through [AuthRepository], so
 * the services layer resolves the calling uid from the app's own session
 * state. The fake keeps these JVM tests off Firebase exactly the way
 * AuthRepositoryTest's does.
 */
class AuthPortAdapterTest {

  /** Minimal [AuthBackend]: a fixed uid, or null when signed out. */
  private class FakeBackend(private val uid: String?) : AuthBackend {
    override suspend fun signInAnonymously(): Result<String> = Result.success(uid ?: "uid-anon")

    override suspend fun signInWithEmail(email: String, password: String): Result<String> =
      Result.success(uid ?: "uid-mail")

    override fun currentUid(): String? = uid

    override fun signOut() = Unit
  }

  @Test
  fun currentUidReadsThroughTheRepository() {
    val adapter = AuthPortAdapter(AuthRepository(FakeBackend("uid-42")))
    assertEquals("uid-42", adapter.currentUid())
  }

  @Test
  fun signedOutUidReadsThroughAsNull() {
    val adapter = AuthPortAdapter(AuthRepository(FakeBackend(null)))
    assertNull(adapter.currentUid())
  }
}
