package com.estemshan.game.ui.matchmaking

import com.estemshan.engine.GameType
import com.estemshan.services.MatchmakingEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S55/S56 — the Ranked solo search's own concerns: the RD9 lifecycle
 * (IDLE → SEARCHING → MATCH_FOUND, or → TIMED_OUT/FAILED), cancel from every
 * state, and the client-measured elapsed clock.
 *
 * These run against [FakeMatchmaking], an in-memory stand-in for the queue.
 * The pool itself is derived server-side (RD9), so no test here asserts a
 * tier — [MatchmakingPort.search] accepts none, and that absence is the
 * invariant. There is no SDK fake anywhere in this repo by design (see
 * LobbyViewModelTest), and the pool resolver does not exist yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchmakingViewModelTest {

  private val me = "uid-me"

  private lateinit var queue: FakeMatchmaking
  private lateinit var vm: MatchmakingViewModel

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** A fresh search on the test's own dispatcher, so each test starts clean. */
  private fun TestScope.openSearch() {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    queue = FakeMatchmaking()
    vm = MatchmakingViewModel(queue)
  }

  // ==========================================================================
  //  RD9 lifecycle: the search and its three outcomes
  // ==========================================================================

  @Test
  fun aSearchStartsSearching() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Searching(), vm.state.value)
    assertEquals(me, queue.lastPlayerId)
  }

  @Test
  fun theSearchCarriesTheGameTypeAndNoTier() = runTest {
    openSearch()
    vm.search(me, GameType.MINI)
    advanceUntilIdle()

    // GM5: the game type rides along…
    assertEquals(GameType.MINI, queue.lastGameType)
    // …and the request carries no tier, because the port takes none (RD9).
    // A pool cannot be requested even by accident — see MatchmakingPort.search.
  }

  @Test
  fun aMatchedSearchTransitionsToMatchFound() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.Matched("match-1"))
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.MatchFound("match-1"), vm.state.value)
  }

  @Test
  fun aTimedOutSearchTransitionsToTimedOut() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.TimedOut)
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.TimedOut, vm.state.value)
  }

  @Test
  fun aFailedSearchTransitionsToFailed() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.Failed)
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Failed, vm.state.value)
  }

  // ==========================================================================
  //  S56: retry — back to SEARCHING at the same game type
  // ==========================================================================

  @Test
  fun tryAgainRestartsTheSearchAtTheSameGameType() = runTest {
    openSearch()
    vm.search(me, GameType.MINI)
    advanceUntilIdle()
    queue.publish(MatchmakingEvent.TimedOut)
    advanceUntilIdle()
    assertEquals(MatchmakingUiState.TimedOut, vm.state.value)

    vm.tryAgain()
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Searching(), vm.state.value)
    // The retry carries the game type the player launched with, not a default.
    assertEquals(GameType.MINI, queue.lastGameType)

    queue.publish(MatchmakingEvent.Matched("match-2"))
    advanceUntilIdle()
    assertEquals(MatchmakingUiState.MatchFound("match-2"), vm.state.value)
  }

  @Test
  fun tryAgainBeforeAnySearchIsANoop() = runTest {
    openSearch()

    vm.tryAgain()
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Idle, vm.state.value)
    assertNull(queue.lastPlayerId)
  }

  // ==========================================================================
  //  Cancel — available in every state, no uncancelable spinner (RD9)
  // ==========================================================================

  @Test
  fun cancelWhileSearchingReturnsToIdleAndLeavesTheQueue() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()

    vm.cancel()
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Idle, vm.state.value)
    assertEquals(1, queue.cancelCount)
  }

  @Test
  fun cancelAfterTimedOutReturnsToIdle() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()
    queue.publish(MatchmakingEvent.TimedOut)
    advanceUntilIdle()

    vm.cancel()
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Idle, vm.state.value)
  }

  @Test
  fun cancelAfterFailureReturnsToIdle() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()
    queue.publish(MatchmakingEvent.Failed)
    advanceUntilIdle()

    vm.cancel()
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Idle, vm.state.value)
  }

  @Test
  fun cancelAfterMatchFoundIsHarmless() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()
    queue.publish(MatchmakingEvent.Matched("match-1"))
    advanceUntilIdle()

    vm.cancel()
    advanceUntilIdle()

    // The search had already ended; cancelling tears nothing down.
    assertEquals(MatchmakingUiState.Idle, vm.state.value)
  }

  @Test
  fun startingASearchReplacesTheOneRunning() = runTest {
    openSearch()
    vm.search(me, GameType.FULL)
    advanceUntilIdle()

    vm.search(me, GameType.MINI)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.Matched("match-2"))
    advanceUntilIdle()

    // Only the second search is live to receive the outcome.
    assertEquals(MatchmakingUiState.MatchFound("match-2"), vm.state.value)
    assertEquals(GameType.MINI, queue.lastGameType)
  }

  // ==========================================================================
  //  The elapsed clock — client-measured, and it stops when the search ends
  // ==========================================================================

  @Test
  fun theTickerDrivesTheSearchingState() = runTest {
    openSearch()
    val ticking = MatchmakingViewModel(
      queue,
      ElapsedTicker { flow { emit(1_000L); emit(2_000L) } },
    )

    ticking.search(me, GameType.FULL)
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Searching(2_000L), ticking.state.value)
  }

  @Test
  fun withoutATickerTheSearchingStateStaysAtZero() = runTest {
    openSearch()

    vm.search(me, GameType.FULL)
    advanceUntilIdle()

    // The default seam never ticks, so a test reaches a terminal state
    // without advancing a clock — the reason ElapsedTicker is injected.
    assertEquals(0L, (vm.state.value as MatchmakingUiState.Searching).elapsedMillis)
  }

  @Test
  fun aMatchedSearchSupersedesTheElapsedState() = runTest {
    openSearch()
    val ticking = MatchmakingViewModel(
      queue,
      ElapsedTicker { flow { emit(1_000L) } },
    )
    ticking.search(me, GameType.FULL)
    advanceUntilIdle()
    assertEquals(MatchmakingUiState.Searching(1_000L), ticking.state.value)

    queue.publish(MatchmakingEvent.Matched("match-1"))
    advanceUntilIdle()

    // The terminal state wins and the completed clock does not resurrect it.
    assertEquals(MatchmakingUiState.MatchFound("match-1"), ticking.state.value)
  }
}
