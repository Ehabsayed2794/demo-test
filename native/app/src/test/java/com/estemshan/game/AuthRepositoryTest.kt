package com.estemshan.game

import com.estemshan.game.data.AuthBackend
import com.estemshan.game.data.AuthRepository
import com.estemshan.game.data.AuthUiState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake backend so these JVM tests never touch Firebase. */
private class FakeBackend(
  var anonymousResult: Result<String> = Result.success("uid-anon"),
  var emailResult: Result<String> = Result.success("uid-mail"),
  var persistedUid: String? = null,
  var signOutCalls: Int = 0,
) : AuthBackend {
  var lastEmail: String? = null

  override suspend fun signInAnonymously(): Result<String> = anonymousResult

  override suspend fun signInWithEmail(email: String, password: String): Result<String> {
    lastEmail = email
    return emailResult
  }

  override fun currentUid(): String? = persistedUid

  override fun signOut() {
    signOutCalls++
  }
}

class AuthRepositoryTest {

  @Test
  fun startsSignedOut() = runTest {
    val repo = AuthRepository(FakeBackend())
    assertEquals(AuthUiState.SignedOut, repo.state.value)
  }

  @Test
  fun anonymousSuccessSignsIn() = runTest {
    val repo = AuthRepository(FakeBackend())
    repo.signInAnonymously()
    assertEquals(AuthUiState.SignedIn("uid-anon"), repo.state.value)
  }

  @Test
  fun anonymousFailureSurfacesError() = runTest {
    val repo = AuthRepository(FakeBackend(anonymousResult = Result.failure(RuntimeException("nope"))))
    repo.signInAnonymously()
    val state = repo.state.value
    assertTrue(state is AuthUiState.Error)
    assertEquals("nope", (state as AuthUiState.Error).message)
  }

  @Test
  fun emailSuccessSignsInAndForwardsAddress() = runTest {
    val backend = FakeBackend()
    val repo = AuthRepository(backend)
    repo.signInWithEmail("a@b.c", "pw")
    assertEquals(AuthUiState.SignedIn("uid-mail"), repo.state.value)
    assertEquals("a@b.c", backend.lastEmail)
  }

  @Test
  fun signOutResetsToSignedOut() = runTest {
    val backend = FakeBackend()
    val repo = AuthRepository(backend)
    repo.signInAnonymously()
    repo.signOut()
    assertEquals(AuthUiState.SignedOut, repo.state.value)
    assertEquals(1, backend.signOutCalls)
  }

  @Test
  fun checkSessionRestoresPersistedUid() = runTest {
    val repo = AuthRepository(FakeBackend(persistedUid = "uid-kept"))
    repo.checkSession()
    assertEquals(AuthUiState.SignedIn("uid-kept"), repo.state.value)
  }

  @Test
  fun checkSessionWithoutPersistedUidStaysSignedOut() = runTest {
    val repo = AuthRepository(FakeBackend(persistedUid = null))
    repo.checkSession()
    assertEquals(AuthUiState.SignedOut, repo.state.value)
  }
}
