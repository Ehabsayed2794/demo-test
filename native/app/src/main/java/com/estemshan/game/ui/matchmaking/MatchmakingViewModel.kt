package com.estemshan.game.ui.matchmaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.engine.GameType
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
 * S55/S56 — drives the Ranked solo search behind [MatchmakingUiState].
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
 * There is no production [MatchmakingPort] yet, so the port is
 * constructor-injected with no default; nothing wires one in until the pool
 * resolver lands.
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

  /**
   * Starts a Ranked solo search for [playerId] at [gameType], replacing any
   * search already running. The request carries the game type and nothing
   * else — never a tier (RD9).
   */
  fun search(playerId: String, gameType: GameType) {
    this.playerId = playerId
    this.gameType = gameType
    start()
  }

  /** S56: retries the last search at the same game type. A no-op if none ran. */
  fun tryAgain() {
    start()
  }

  private fun start() {
    val type = gameType ?: return
    searchJob?.cancel()
    _state.value = MatchmakingUiState.Searching()
    searchJob = viewModelScope.launch {
      val elapsedJob = launch {
        ticker.ticks().collect { ms ->
          _state.value = MatchmakingUiState.Searching(ms)
        }
      }
      matchmaking.search(playerId, type).collect { event ->
        _state.value = when (event) {
          is MatchmakingEvent.Matched -> MatchmakingUiState.MatchFound(event.matchId)
          MatchmakingEvent.TimedOut -> MatchmakingUiState.TimedOut
          MatchmakingEvent.Failed -> MatchmakingUiState.Failed
        }
      }
      // The search flow completed — one terminal event ends it — so the clock stops.
      elapsedJob.cancel()
    }
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
 */
fun interface ElapsedTicker {
  fun ticks(): Flow<Long>

  companion object {
    val None = ElapsedTicker { emptyFlow() }
  }
}
