package com.estemshan.game.ui.splash

import com.estemshan.game.data.AuthBackend
import com.estemshan.game.data.AuthRepository
import com.estemshan.game.data.AuthUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fake backend so these JVM tests never touch Firebase. */
private class FakeSplashBackend(val persistedUid: String?) : AuthBackend {
  override suspend fun signInAnonymously(): Result<String> = Result.success("uid-anon")
  override suspend fun signInWithEmail(email: String, password: String): Result<String> =
    Result.success("uid-mail")
  override fun currentUid(): String? = persistedUid
  override fun signOut() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

  @Test
  fun persistedSessionRoutesToLobby() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend("uid-kept")))
      advanceUntilIdle()
      assertEquals(AuthUiState.SignedIn("uid-kept"), vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun noSessionRoutesToLogin() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend(null)))
      advanceUntilIdle()
      assertEquals(AuthUiState.SignedOut, vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun startsUnresolved() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend(null)))
      assertNull(vm.ready.value)
      advanceUntilIdle()
    } finally {
      Dispatchers.resetMain()
    }
  }
}
