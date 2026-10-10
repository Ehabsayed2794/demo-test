package com.estemshan.game.ui.placement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.engine.PlacementMatchConfig
import com.estemshan.engine.PlacementMatchRecord
import com.estemshan.engine.PlacementRoundSignal
import com.estemshan.engine.beginPlacement
import com.estemshan.engine.isLastPlacementMatch
import com.estemshan.engine.placementMatch
import com.estemshan.engine.placementProgress
import com.estemshan.engine.RankedProfile
import com.estemshan.engine.recordPlacementMatch
import com.estemshan.game.ui.bot.BotRoster
import com.estemshan.game.ui.bot.BotSeat
import com.estemshan.game.ui.quickmatch.QuickMatchViewModel
import com.estemshan.game.ui.quickmatch.QuickRoundResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * S53 — the RD10 placement series driver: three scripted, system-controlled
 * bot matches (M1/M2 MINI Easy+Medium / Medium+Hard, M3 FULL Hard+Expert)
 * played through the existing quick-match machinery, with every match's
 * engine signals recorded for S54's 10/20/70 scoring.
 *
 * What this is and is not:
 *
 * - It IS the series driver. [startPlacement] seats the first scripted table,
 *   and each finished match advances to the next until the series hands its
 *   records to [PlacementUiState.Calculating] — the S49 "Calculating Rank"
 *   state S54 consumes and S50 reveals.
 * - It is NOT the gate. RD10/RD30's "may this account still start placement"
 *   is [com.estemshan.engine.canStartPlacement] over the *persisted* profile,
 *   which the S45/S47 two-path screen checks before calling [startPlacement].
 *   The profile write path does not exist yet, so this view model holds the
 *   series' in-memory profile in [profile] for the screens and defers
 *   persistence to that story.
 * - It is NOT the screens. S47 (the two-path choice), S48 (the 1/3–2/3–3/3
 *   progress), S49/S50 (calculating → reveal) bind to [state]; none exist yet
 *   and none are built here. The alternative "Start from Bronze" path needs no
 *   coordinator — it is the pure [com.estemshan.engine.skipPlacement] the
 *   screen applies directly.
 *
 * Reuses rather than reimplements: the roster seats into
 * [QuickMatchViewModel.startMatch] exactly as Choose Level does, the BotDriver
 * arms from the Bidding/Table composables exactly as quick match does, and the
 * per-round signals come from [QuickMatchViewModel.roundResults] — the one
 * additive hook this story adds — so a placement match clears the same legality
 * a tap clears and scores through the same [QuickMatchViewModel.onTableDone].
 */
class PlacementViewModel(
  private val quickMatch: QuickMatchViewModel,
  /** A fresh deal per match; injectable so a test replays a specific table. */
  private val seedSource: () -> Long = { Random.Default.nextLong() },
) : ViewModel() {

  /** The player's seat; the scripted opponents take the other three. */
  private val human: String get() = quickMatch.seats.first()

  private val _state = MutableStateFlow<PlacementUiState>(PlacementUiState.Idle)
  val state: StateFlow<PlacementUiState> = _state.asStateFlow()

  /**
   * The series' in-memory profile — [beginPlacement] on [startPlacement], so
   * [RankedProfile.placementState] is IN_PROGRESS for every match the driver
   * runs (RD10: those matches count toward no statistic).
   */
  private val _profile = MutableStateFlow<RankedProfile?>(null)
  val profile: StateFlow<RankedProfile?> = _profile.asStateFlow()

  /** The matches finished so far, in series order — S54's whole input. */
  private val _records = mutableListOf<PlacementMatchRecord>()
  val records: List<PlacementMatchRecord> get() = _records.toList()

  /** Which match is running, and its per-round signals while it does. */
  private var matchIndex = 0
  private val roundSignals = mutableListOf<PlacementRoundSignal>()
  private var observeJob: Job? = null

  /**
   * Begin the three scripted matches from [seasonId]. The caller has already
   * gated the account with [com.estemshan.engine.canStartPlacement] (RD10:
   * once per account, never per season; RD30: once skipped, never started).
   * Idempotent against a double call: a series already underway or finished
   * refuses to restart, because RD10 never repeats placement.
   */
  fun startPlacement(seasonId: String) {
    check(_state.value is PlacementUiState.Idle) {
      "placement cannot start from ${_state.value} — RD10 never repeats the series"
    }
    _profile.value = beginPlacement(seasonId)
    _records.clear()
    startMatch(0)
  }

  /** Seat the [index]-th scripted table and watch for its final round. */
  private fun startMatch(index: Int) {
    val config = placementMatch(index)
    matchIndex = index
    roundSignals.clear()
    // Observe before the match starts: no round is scored until the driver
    // moves a seat, so collecting first cannot miss the first emission.
    observeMatchEnd(config)
    quickMatch.startMatch(
      roster = scriptedRoster(config),
      matchSeed = seedSource(),
      gameType = config.gameType,
      scoringMode = config.scoringMode,
    )
    _state.value = PlacementUiState.MatchInProgress(placementProgress(index))
  }

  /**
   * Collect each scored round, record the player's estimate-vs-actual, and
   * close the match on its format's last round. Reading the match's end off
   * the *round result* itself — not a second flow — keeps the final round's
   * signal and the match-end detection on one emission, so the record can
   * never be built a round early or a round late.
   */
  private fun observeMatchEnd(config: PlacementMatchConfig) {
    observeJob?.cancel()
    observeJob = viewModelScope.launch {
      quickMatch.roundResults.collect { result ->
        if (result.round <= roundSignals.size) return@collect
        roundSignals += PlacementRoundSignal(
          round = result.round,
          estimate = result.estimates[human] ?: 0,
          actual = result.tricksWon[human] ?: 0,
        )
        if (result.round >= config.gameType.baseRounds) {
          _records += recordPlacementMatch(
            matchIndex = config.index,
            totals = quickMatch.totals.value,
            seat = human,
            rounds = roundSignals.toList(),
          )
          advance()
        }
      }
    }
  }

  /** Bank the finished match and either seat the next table or hand off. */
  private fun advance() {
    observeJob?.cancel()
    if (isLastPlacementMatch(matchIndex)) {
      _state.value = PlacementUiState.Calculating(records)
    } else {
      startMatch(matchIndex + 1)
    }
  }

  /** Seat the scripted opponents across from the player, in table order. */
  private fun scriptedRoster(config: PlacementMatchConfig): BotRoster {
    val opponents = quickMatch.seats.filter { it != human }
    return BotRoster.of(
      *config.opponents.mapIndexed { i, opponent ->
        opponents[i] to BotSeat(opponent.tier, opponent.personality)
      }.toTypedArray(),
    )
  }

  override fun onCleared() {
    observeJob?.cancel()
    super.onCleared()
  }
}

/**
 * Where the series stands — the whole surface the unbuilt S47–S50 screens
 * bind to. Deliberately carries no rank anywhere: RD10 shows only the match
 * progress (1/3, 2/3, 3/3) until the series is done, and never a provisional
 * one.
 */
sealed interface PlacementUiState {
  /** The series has not started — S47 renders the two-path choice here. */
  data object Idle : PlacementUiState

  /**
   * A scripted match is underway; [progress] is the 1–3 match number S48
   * displays. Never a rank, never a provisional result.
   */
  data class MatchInProgress(val progress: Int) : PlacementUiState

  /**
   * The third match is banked. This is S49's "Calculating Rank" state, and
   * [records] is the complete three-match input S54's 10/20/70 scoring
   * consumes and S50's reveal then shows.
   */
  data class Calculating(val records: List<PlacementMatchRecord>) : PlacementUiState
}
