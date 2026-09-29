package com.estemshan.game.ui.splash

import com.estemshan.game.data.AuthBackend
import com.estemshan.game.data.AuthRepository
import com.estemshan.game.data.AuthUiState
import com.estemshan.services.PlayerPort
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

/**
 * Fake players/{uid}.currentMatchId — the reconnect pointer, in memory. The
 * splash only ever READS it, so the write half stays unsupported here; the
 * [throws] toggle pins the fail-open path against a seam that breaks its own
 * "never throws" contract.
 */
private class FakePlayers(
  val current: MutableMap<String, String?> = mutableMapOf(),
  val throws: Boolean = false,
) : PlayerPort {
  override suspend fun currentMatchId(uid: String): String? {
    if (throws) throw RuntimeException("profile unreadable")
    return current[uid]
  }

  override suspend fun setCurrentMatchId(uid: String, matchId: String?) {
    throw UnsupportedOperationException("the splash only reads currentMatchId")
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SplashViewModelTest {

  @Test
  fun persistedSessionRoutesToLobby() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend("uid-kept")), FakePlayers())
      advanceUntilIdle()
      assertEquals(SplashReady(AuthUiState.SignedIn("uid-kept")), vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun noSessionRoutesToLogin() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend(null)), FakePlayers())
      advanceUntilIdle()
      assertEquals(SplashReady(AuthUiState.SignedOut), vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun startsUnresolved() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend(null)), FakePlayers())
      assertNull(vm.ready.value)
      advanceUntilIdle()
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun liveCurrentMatchPublishesResumeId() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val players = FakePlayers(current = mutableMapOf("uid-kept" to "match-live"))
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend("uid-kept")), players)
      advanceUntilIdle()
      // The nav routes on this single value, so the matchId has to be in it —
      // and in it before the value goes non-null, or the resume never fires.
      assertEquals(
        SplashReady(AuthUiState.SignedIn("uid-kept"), "match-live"),
        vm.ready.value,
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun missingCurrentMatchIsNoResume() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      // The profile holds no currentMatchId at all.
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend("uid-kept")), FakePlayers())
      advanceUntilIdle()
      assertEquals(SplashReady(AuthUiState.SignedIn("uid-kept")), vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun blankCurrentMatchIsNoResume() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      // A whitespace-only id is not a match the route can carry, so the gate
      // treats it the same as absent.
      val players = FakePlayers(current = mutableMapOf("uid-kept" to "   "))
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend("uid-kept")), players)
      advanceUntilIdle()
      assertEquals(SplashReady(AuthUiState.SignedIn("uid-kept")), vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun failingPlayersIsNoResumeAndNeverThrows() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      val players = FakePlayers(throws = true)
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend("uid-kept")), players)
      advanceUntilIdle()
      // Fail-open: a broken read costs the resume, not the launch — the gate
      // still opens on the auth half, with no resume id, and the player lands
      // in the lobby.
      assertEquals(SplashReady(AuthUiState.SignedIn("uid-kept")), vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun signedOutReadsNoProfile() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
      // No session means no uid to read with, so the profile is never touched
      // and the resume id is null — the cold-start path is signed-in only.
      val players = FakePlayers(current = mutableMapOf("uid-anon" to "match-live"))
      val vm = SplashViewModel(AuthRepository(FakeSplashBackend(null)), players)
      advanceUntilIdle()
      assertEquals(SplashReady(AuthUiState.SignedOut), vm.ready.value)
    } finally {
      Dispatchers.resetMain()
    }
  }
}
