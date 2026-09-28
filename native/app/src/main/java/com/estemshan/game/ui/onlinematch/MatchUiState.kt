package com.estemshan.game.ui.onlinematch

import com.estemshan.engine.BiddingState
import com.estemshan.engine.TableState
import com.estemshan.game.ui.standings.FinalStandingsUiState

/**
 * One screen's worth of online-match state — everything [OnlineMatchViewModel]
 * publishes, and the ONLY thing the online Bidding/Table/Standings screens
 * read. The screens themselves stay stateless (S17's contract): each is handed
 * a state plus its own seat, the last rejection text, and pure callbacks that
 * route straight back to the view model.
 *
 * States are exclusive and exhaustive over the match document's own phases:
 * the document is the authority, so this mirrors it rather than predicting it.
 * A state is only ever published AFTER the engines have caught up to the
 * document that produced it, so a screen never observes a state its callbacks
 * cannot act on.
 */
sealed interface MatchUiState {

  /** Subscribed, no snapshot yet. The overlay's "Reconnecting…" twin. */
  data object Connecting : MatchUiState

  /**
   * The auction for the current round. [forbidden]/[floor] are the same
   * pure-engine hints the offline Bidding screen consumes; the online screen
   * renders them identically.
   */
  data class Bidding(
    val state: BiddingState,
    val userSeat: String,
    val rejection: String?,
    val forbidden: Int?,
    val floor: Int?,
  ) : MatchUiState

  /**
   * Trick play for the current round. [userSeat] is where the local player
   * sits; the table config holds ONLY that seat's hand, so an opponent's hand
   * row renders empty by construction (never a peekable card).
   */
  data class Table(
    val state: TableState,
    val userSeat: String,
    val rejection: String?,
  ) : MatchUiState

  /**
   * A round just scored and the next one has not opened on the document yet.
   * [isLastRound] is the "this was the last round" signal a Continue button
   * keys off — after it, the next snapshot is [MatchComplete], not a new
   * auction. Bumped once per scored round, so a re-delivery cannot double-show.
   */
  data class RoundStandings(
    val standings: FinalStandingsUiState,
    val round: Int,
    val isLastRound: Boolean,
  ) : MatchUiState

  /** Terminal: status:complete on the document. Kings and their totals. */
  data class MatchComplete(val standings: FinalStandingsUiState) : MatchUiState

  /**
   * The signed-in player holds no seat in this match, or the match document
   * is gone. The nav graph reacts by leaving; it is not an error to land here
   * (backing out of a room whose match already ended is the ordinary path).
   */
  data object NotInMatch : MatchUiState

  /** A terminal, non-retryable listener failure (or no match at all). */
  data class Failed(val message: String) : MatchUiState
}
