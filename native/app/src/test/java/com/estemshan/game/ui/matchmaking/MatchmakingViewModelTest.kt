package com.estemshan.game.ui.matchmaking

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
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
 * S55/S56/S71 — the solo search's own concerns: the RD9 lifecycle
 * (IDLE → SEARCHING → MATCH_FOUND, or → TIMED_OUT/FAILED), cancel from every
 * state, the client-measured elapsed clock, and the S71 mode and fill
 * deadline.
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
    vm.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Searching(), vm.state.value)
    assertEquals(me, queue.lastPlayerId)
  }

  @Test
  fun theSearchCarriesTheGameTypeAndNoTier() = runTest {
    openSearch()
    vm.search(me, GameType.MINI, MatchMode.RANKED)
    advanceUntilIdle()

    // GM5: the game type rides along…
    assertEquals(GameType.MINI, queue.lastGameType)
    // …and the request carries no tier, because the port takes none (RD9).
    // A pool cannot be requested even by accident — see MatchmakingPort.search.
  }

  @Test
  fun aMatchedSearchTransitionsToMatchFound() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.Matched("match-1"))
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.MatchFound("match-1"), vm.state.value)
  }

  @Test
  fun aTimedOutSearchTransitionsToTimedOut() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.TimedOut)
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.TimedOut, vm.state.value)
  }

  @Test
  fun aFailedSearchTransitionsToFailed() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.RANKED)
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
    vm.search(me, GameType.MINI, MatchMode.RANKED)
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
    vm.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()

    vm.cancel()
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Idle, vm.state.value)
    assertEquals(1, queue.cancelCount)
  }

  @Test
  fun cancelAfterTimedOutReturnsToIdle() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.RANKED)
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
    vm.search(me, GameType.FULL, MatchMode.RANKED)
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
    vm.search(me, GameType.FULL, MatchMode.RANKED)
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
    vm.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()

    vm.search(me, GameType.MINI, MatchMode.RANKED)
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

    ticking.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.Searching(2_000L), ticking.state.value)
  }

  @Test
  fun withoutATickerTheSearchingStateStaysAtZero() = runTest {
    openSearch()

    vm.search(me, GameType.FULL, MatchMode.RANKED)
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
    ticking.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()
    assertEquals(MatchmakingUiState.Searching(1_000L), ticking.state.value)

    queue.publish(MatchmakingEvent.Matched("match-1"))
    advanceUntilIdle()

    // The terminal state wins and the completed clock does not resurrect it.
    assertEquals(MatchmakingUiState.MatchFound("match-1"), ticking.state.value)
  }

  // ==========================================================================
  //  S71 — the mode, and the fill deadline the server publishes for it
  // ==========================================================================

  @Test
  fun theSearchCarriesTheModeThePlayerChose() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.UNRANKED)
    advanceUntilIdle()

    // The mode is the player's own choice of what to play, and it is the
    // only thing that separates the two pools — see MatchmakingPort.search.
    assertEquals(MatchMode.UNRANKED, queue.lastMode)
  }

  @Test
  fun theRetryKeepsTheModeThePlayerChose() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.UNRANKED)
    advanceUntilIdle()
    queue.publish(MatchmakingEvent.TimedOut)
    advanceUntilIdle()

    vm.tryAgain()
    advanceUntilIdle()

    // A retry must not fall back to the other pool.
    assertEquals(MatchMode.UNRANKED, queue.lastMode)
  }

  @Test
  fun thePublishedFillDeadlineReachesTheState() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.UNRANKED)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.Searching(300_000L))
    advanceUntilIdle()

    assertEquals(
      MatchmakingUiState.Searching(elapsedMillis = 0L, fillDeadlineMillis = 300_000L),
      vm.state.value,
    )
  }

  @Test
  fun thePublishedDeadlineIsNotATerminalOutcome() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.UNRANKED)
    advanceUntilIdle()

    queue.publish(MatchmakingEvent.Searching(300_000L))
    advanceUntilIdle()
    // Progress, not an outcome: the search is still queued.
    assertEquals(MatchmakingUiState.Searching(0L, 300_000L), vm.state.value)

    queue.publish(MatchmakingEvent.Matched("table-1"))
    advanceUntilIdle()

    assertEquals(MatchmakingUiState.MatchFound("table-1"), vm.state.value)
  }

  @Test
  fun theElapsedClockAndTheDeadlineAreReadTogether() = runTest {
    openSearch()
    val ticking = MatchmakingViewModel(
      queue,
      ElapsedTicker { flow { emit(1_000L); emit(2_000L) } },
    )
    ticking.search(me, GameType.FULL, MatchMode.UNRANKED)
    advanceUntilIdle()
    assertEquals(MatchmakingUiState.Searching(2_000L, null), ticking.state.value)

    queue.publish(MatchmakingEvent.Searching(300_000L))
    advanceUntilIdle()

    // The elapsed clock is the client's and the deadline is the server's;
    // neither writer may drop the other's number.
    assertEquals(MatchmakingUiState.Searching(2_000L, 300_000L), ticking.state.value)
  }

  @Test
  fun aRankedSearchCarriesNoDeadline() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.RANKED)
    advanceUntilIdle()

    // A Ranked table is a pair, not a window: there is no fill deadline to
    // count down, and none is invented.
    assertNull((vm.state.value as MatchmakingUiState.Searching).fillDeadlineMillis)
  }

  @Test
  fun aCancelledSearchForgetsItsDeadline() = runTest {
    openSearch()
    vm.search(me, GameType.FULL, MatchMode.UNRANKED)
    advanceUntilIdle()
    queue.publish(MatchmakingEvent.Searching(300_000L))
    advanceUntilIdle()

    vm.cancel()
    advanceUntilIdle()

    // The next search must not inherit the previous one's deadline.
    assertEquals(MatchmakingUiState.Idle, vm.state.value)
    vm.search(me, GameType.FULL, MatchMode.UNRANKED)
    advanceUntilIdle()
    assertEquals(MatchmakingUiState.Searching(0L, null), vm.state.value)
  }
}
