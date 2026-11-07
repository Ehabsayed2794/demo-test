package com.estemshan.game.ui.matchmaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.services.MatchmakingEvent
import com.estemshan.services.MatchmakingPort
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * S55/S56/S71 — drives the solo search, Ranked or Unranked, behind
 * [MatchmakingUiState].
 *
 * Owns exactly the search lifecycle and nothing else. The eligible pool is
 * derived server-side (RD9 — see [MatchmakingPort], which takes no tier), so
 * this class never computes, stores, or asserts a tier. Its mutations are the
 * screen's callbacks: [search], [tryAgain], and [cancel], plus the terminal
 * transition the search flow delivers on its own.
 *
 * **The elapsed timer is a seam, not a live loop.** [ElapsedTicker] is
 * injected with a [ElapsedTicker.None] default — the same way S19's Heartbeat
 * is — so a JVM unit test reaches a terminal state without advancing a clock,
 * and the real clock stays off the test scheduler.
 *
 * **The mode is the player's choice, and it is the only pool selector.**
 * S71 adds UNRANKED beside RANKED: the two sit in separate pools, an
 * Unranked table awards no RP (the tier, the division and the RP stay
 * Ranked-only) while its results count towards the career counters
 * (Q46/N-1, 2026-10-22), and a Room
 * is not poolable at all — so a Room never reaches this class, and the port
 * refuses it.
 *
 * The port is constructor-injected with no default, so a JVM test can stand in
 * for it; the production one is [com.estemshan.services.CallableMatchmaking],
 * wired in `OnlineServices`.
 */
class MatchmakingViewModel(
  private val matchmaking: MatchmakingPort,
  private val ticker: ElapsedTicker = ElapsedTicker.None,
) : ViewModel() {

  private val _state = MutableStateFlow<MatchmakingUiState>(MatchmakingUiState.Idle)
  val state: StateFlow<MatchmakingUiState> = _state.asStateFlow()

  private var searchJob: Job? = null
  private var playerId: String = ""
  private var gameType: GameType? = null
  private var mode: MatchMode? = null

  /** The two numbers the searching state carries, each owned by one writer. */
  private var elapsedMillis: Long = 0L
  private var fillDeadlineMillis: Long? = null

  /**
   * Starts a solo search for [playerId] at [gameType] in [mode], replacing any
   * search already running. The request carries the game type and the mode and
   * nothing else — never a tier (RD9).
   */
  fun search(playerId: String, gameType: GameType, mode: MatchMode) {
    this.playerId = playerId
    this.gameType = gameType
    this.mode = mode
    start()
  }

  /** S56: retries the last search at the same game type and mode. A no-op if none ran. */
  fun tryAgain() {
    start()
  }

  private fun start() {
    val type = gameType ?: return
    val chosenMode = mode ?: return
    searchJob?.cancel()
    elapsedMillis = 0L
    fillDeadlineMillis = null
    _state.value = MatchmakingUiState.Searching()
    searchJob = viewModelScope.launch {
      val elapsedJob = launch {
        ticker.ticks().collect { ms -> searching(ms, null) }
      }
      matchmaking.search(playerId, type, chosenMode).collect { event ->
        when (event) {
          is MatchmakingEvent.Matched -> _state.value = MatchmakingUiState.MatchFound(event.tableId)
          MatchmakingEvent.TimedOut -> _state.value = MatchmakingUiState.TimedOut
          MatchmakingEvent.Failed -> _state.value = MatchmakingUiState.Failed
          // NOT terminal: the search is still queued, and the server published
          // the deadline this seat is filled by.
          is MatchmakingEvent.Searching -> searching(elapsedMillis, event.fillDeadlineMillis)
        }
      }
      // The search flow completed — one terminal event ends it — so the clock stops.
      elapsedJob.cancel()
    }
  }

  /**
   * Publishes the searching state, keeping the two numbers the searching state
   * carries and each of the two writers owns: the ticker owns the elapsed
   * clock, the server owns the fill deadline. Neither writer may drop the
   * other's number — the elapsed chip and the countdown are read together.
   */
  private fun searching(elapsed: Long, deadline: Long?) {
    elapsedMillis = elapsed
    if (deadline != null) fillDeadlineMillis = deadline
    _state.value = MatchmakingUiState.Searching(elapsedMillis, fillDeadlineMillis)
  }

  /**
   * S55/S56: cancels the search from any state. Tears down the local
   * subscription, removes the player from the server queue, and returns to
   * [MatchmakingUiState.Idle] — the screen pops back to the Ranked config it
   * launched from (S36), never to a dead end.
   */
  fun cancel() {
    searchJob?.cancel()
    searchJob = null
    elapsedMillis = 0L
    fillDeadlineMillis = null
    val id = playerId
    if (id.isNotEmpty()) {
      launch { matchmaking.cancel(id) }
    }
    _state.value = MatchmakingUiState.Idle
  }

  override fun onCleared() {
    searchJob?.cancel()
  }

  private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
}

/**
 * The client-measured elapsed time shown on S55's SEARCHING chip. A seam so
 * the ticking lives outside the view model's own scheduler: [None] never ticks
 * (the unit-test default) and the production implementation emits real
 * elapsed milliseconds. Elapsed only — never a countdown, and never an ETA
 * (RD9 publishes no wait estimate).
 *
 * The S71 countdown is NOT this ticker: it is derived from the server's own
 * fill deadline, which arrives with the search's own answer.
 */
fun interface ElapsedTicker {
  fun ticks(): Flow<Long>

  companion object {
    val None = ElapsedTicker { emptyFlow() }
  }
}
