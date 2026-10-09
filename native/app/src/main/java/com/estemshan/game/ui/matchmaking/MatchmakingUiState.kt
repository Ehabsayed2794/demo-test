package com.estemshan.game.ui.matchmaking

/**
 * S55/S56 — the public Ranked solo search as one state machine.
 *
 * RD9 lifecycle: `IDLE → SEARCHING → MATCH_FOUND` (transition into the match),
 * or `SEARCHING → TIMED_OUT / FAILED` (retry, back to SEARCHING, or cancel).
 * Cancel is available in every state; there is no uncancelable spinner.
 *
 * The screen stays stateless and renders exactly this;
 * [MatchmakingViewModel] is its only writer.
 */
sealed interface MatchmakingUiState {

  /** Not searching — the search has not started, or the player backed out. */
  data object Idle : MatchmakingUiState

  /**
   * Searching (S55). [elapsedMillis] is the client-measured time on the
   * SEARCHING chip — elapsed only, **never a countdown**: the pool resolver
   * publishes no ETA, no queue size, and no wait estimate (the S55 design's
   * "no invented data" rule — the elapsed timer is the one live number).
   */
  data class Searching(val elapsedMillis: Long = 0L) : MatchmakingUiState

  /**
   * A table formed. Transitioning into [matchId] is a separate screen that the
   * S55/S56 design does not draw, so this state is the handoff: whoever shows
   * it navigates into the match.
   */
  data class MatchFound(val matchId: String) : MatchmakingUiState

  /** S56 — no players were found for the pool in time. Try again, or cancel. */
  data object TimedOut : MatchmakingUiState

  /** S56 — the search failed. Try again, or cancel. */
  data object Failed : MatchmakingUiState
}
