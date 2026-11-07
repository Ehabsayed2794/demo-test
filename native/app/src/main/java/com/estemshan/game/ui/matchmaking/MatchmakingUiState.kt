package com.estemshan.game.ui.matchmaking

/**
 * S55/S56/S71 — the public solo search as one state machine.
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
   *
   * [fillDeadlineMillis] is the S71 addition, and it is the ONE number the
   * server does publish for a waiting search: the instant by which this
   * search's seat is filled — by humans, or by the configured bots when the
   * four-minute fill window closes. It is the server's own epoch millis, a
   * bound and never an estimate (the table may fill sooner), and it is null
   * for a RANKED search, whose table is a pair rather than a window. A null
   * here means "no deadline was published" — never "the wait is unbounded".
   */
  data class Searching(
    val elapsedMillis: Long = 0L,
    val fillDeadlineMillis: Long? = null,
  ) : MatchmakingUiState

  /**
   * A table formed. Transitioning into [tableId] is a separate screen that the
   * S55/S56 design does not draw, so this state is the handoff: whoever shows
   * it navigates into the table.
   */
  data class MatchFound(val tableId: String) : MatchmakingUiState

  /** S56 — no players were found for the pool in time. Try again, or cancel. */
  data object TimedOut : MatchmakingUiState

  /** S56 — the search failed. Try again, or cancel. */
  data object Failed : MatchmakingUiState
}
