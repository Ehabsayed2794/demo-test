package com.estemshan.game.ui.creategame

import androidx.lifecycle.ViewModel
import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * S36 — the state behind the Create Game screen. Configuration only: this
 * holds the five match-scoped selections and nothing else. Creating the room
 * is the lobby's concern ([com.estemshan.game.ui.lobby.LobbyViewModel]), so
 * this class never touches a service — it is the one place the host's choices
 * live between entering the screen and Confirm, which is also why a failed
 * create costs the player none of them (S36: "the selections must persist").
 *
 * The five groups are the ones the screen presents, in order: Game Type,
 * Calculation, then the RD21 trio (Bot Difficulty, Bot Personality, Decision
 * Timer). Defaults are the frozen owner decision D5 + GM7 — FULL / NORMAL /
 * MEDIUM / BALANCED / 15 s — pre-filled every time the screen opens.
 */
class CreateGameViewModel : ViewModel() {

  private val _state = MutableStateFlow(CreateGameUiState())
  val state: StateFlow<CreateGameUiState> = _state.asStateFlow()

  /** GM1: FULL and MINI are the only two Game Types — no third exists. */
  fun onGameType(gameType: GameType) {
    _state.value = _state.value.copy(gameType = gameType)
  }

  /** GM4: NORMAL and CLASSIC are the only two Calculation Modes. */
  fun onScoringMode(scoringMode: ScoringMode) {
    _state.value = _state.value.copy(scoringMode = scoringMode)
  }

  fun onBotTier(tier: BotTier) {
    _state.value = _state.value.copy(botTier = tier)
  }

  fun onBotPersonality(personality: BotPersonality) {
    _state.value = _state.value.copy(botPersonality = personality)
  }

  fun onDecisionTimerSeconds(seconds: Int) {
    _state.value = _state.value.copy(decisionTimerSeconds = seconds)
  }
}

/**
 * The host's five match-scoped selections. Every default is the frozen
 * pre-fill (RD21 + GM7), so a player who taps straight through Confirm gets
 * the same match every time.
 */
data class CreateGameUiState(
  val gameType: GameType = GameType.FULL,
  val scoringMode: ScoringMode = ScoringMode.NORMAL,
  val botTier: BotTier = BotTier.MEDIUM,
  val botPersonality: BotPersonality = BotPersonality.BALANCED,
  val decisionTimerSeconds: Int = DEFAULT_DECISION_TIMER_SECONDS,
) {

  /**
   * The phases' derived per-decision times. Card play uses the selection;
   * Dash, Bidding, and Estimates get five seconds more (RD21's +5 rule). The
   * screen shows these live so the rule is legible before the match, not
   * discovered mid-match.
   */
  val phaseDecisionSeconds: List<Pair<String, Int>>
    get() = listOf(
      PHASE_DASH to decisionTimerSeconds + TIMER_BONUS_SECONDS,
      PHASE_BIDDING to decisionTimerSeconds + TIMER_BONUS_SECONDS,
      PHASE_ESTIMATES to decisionTimerSeconds + TIMER_BONUS_SECONDS,
      PHASE_CARD_PLAY to decisionTimerSeconds,
    )

  companion object {
    const val DEFAULT_DECISION_TIMER_SECONDS = 15
    const val TIMER_BONUS_SECONDS = 5

    /** The four times the Decision Timer group offers — no fifth value. */
    val TIMER_CHOICES: List<Int> = listOf(5, 10, 15, 20)

    // Phase keys — the screen maps these to strings, so a phase's label has
    // exactly one owner here and one resource there.
    const val PHASE_DASH = "dash"
    const val PHASE_BIDDING = "bidding"
    const val PHASE_ESTIMATES = "estimates"
    const val PHASE_CARD_PLAY = "cardPlay"
  }
}
