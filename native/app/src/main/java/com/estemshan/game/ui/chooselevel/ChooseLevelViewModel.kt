package com.estemshan.game.ui.chooselevel

import androidx.lifecycle.ViewModel
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.game.ui.bot.BotRoster
import com.estemshan.game.ui.bot.BotSeat
import com.estemshan.game.ui.bot.BotTablePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * S12 Step 3 — the state behind the Choose Level screen. Owns every mutation
 * the screen's four callbacks stand for and turns the edited chairs into the
 * [BotRoster] [com.estemshan.game.ui.quickmatch.QuickMatchViewModel.startMatch]
 * receives, which arms the S11 driver to actually move those seats.
 *
 * The screen stays stateless: it renders [ChooseLevelUiState] and forwards
 * intents here, so the seating (and nothing else) has one owner. A preset
 * seats all three opponents at once; a single hand-tuned chair leaves the
 * presets behind — [ChooseLevelUiState.appliedPreset] going `null` is exactly
 * the Custom state the screen badges — and re-selecting a preset restores it.
 */
class ChooseLevelViewModel : ViewModel() {

  /** The four seats quick-match deals, in seat order. */
  val seats: List<String> = DEFAULT_SEATS

  /** The chair the player takes; every other seat is an opponent. */
  val human: String = seats.first()

  private val _state = MutableStateFlow(
    ChooseLevelUiState(
      opponents = opponentsFrom(BotTablePreset.DEFAULT, seats, human),
      appliedPreset = BotTablePreset.DEFAULT,
    ),
  )
  val state: StateFlow<ChooseLevelUiState> = _state.asStateFlow()

  /** Seat the whole table from [preset] in one tap. */
  fun onPresetSelected(preset: BotTablePreset) {
    _state.value = ChooseLevelUiState(
      opponents = opponentsFrom(preset, seats, human),
      appliedPreset = preset,
    )
  }

  /** Change one chair's skill. Any hand-tuning leaves the presets (Custom). */
  fun onTierSelected(opponent: OpponentConfig, tier: BotTier) = tune(opponent.seat, tier = tier)

  /** Change one chair's style. Likewise leaves the presets. */
  fun onPersonalitySelected(opponent: OpponentConfig, personality: BotPersonality) =
    tune(opponent.seat, personality = personality)

  /**
   * The seating [startMatch][com.estemshan.game.ui.quickmatch.QuickMatchViewModel.startMatch]
   * receives: one [BotSeat] per opponent chair, at exactly the tier and style
   * the screen is showing. The human's own chair is simply absent — that is
   * what makes it human. Seating is fixed at match start, so this is a
   * snapshot of the edited chairs, not a flow.
   */
  fun roster(): BotRoster = BotRoster.of(
    *_state.value.opponents.map { it.seat to BotSeat(it.tier, it.personality) }.toTypedArray(),
  )

  private fun tune(seat: String, tier: BotTier? = null, personality: BotPersonality? = null) {
    val current = _state.value
    _state.value = current.copy(
      opponents = current.opponents.map { opponent ->
        if (opponent.seat != seat) {
          opponent
        } else {
          opponent.copy(
            tier = tier ?: opponent.tier,
            personality = personality ?: opponent.personality,
          )
        }
      },
      // One hand-tuned chair and the table is no longer the preset's.
      appliedPreset = null,
    )
  }
}

/**
 * Seat [preset]'s lineup against [human]: the opponent chairs of [seats], in
 * seat order, each taking the next entry of [preset]'s bots — the same
 * cycling [BotTablePreset.roster] uses, so a freshly-applied preset and the
 * roster it builds agree seat for seat. Shared with the screen's previews, so
 * a static fixture and the live view model can never drift apart.
 */
fun opponentsFrom(preset: BotTablePreset, seats: List<String>, human: String): List<OpponentConfig> {
  require(human in seats) { "the human must be seated: '$human' is not in $seats" }
  return seats.filter { it != human }.mapIndexed { i, seat ->
    val bot = preset.bots[i % preset.bots.size]
    OpponentConfig(
      seat = seat,
      seatNumber = seats.indexOf(seat) + 1,
      tier = bot.tier,
      personality = bot.personality,
    )
  }
}
