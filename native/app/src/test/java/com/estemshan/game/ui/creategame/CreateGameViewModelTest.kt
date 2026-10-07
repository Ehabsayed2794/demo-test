package com.estemshan.game.ui.creategame

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S36's tests for the config state behind the Create Game screen.
 *
 * Two things only this view model can get wrong, so both are pinned here:
 * the screen opens on the frozen owner defaults every time (RD21 + GM7 — a
 * player who taps straight through Confirm gets the same match every time),
 * and the five groups are independent, so tuning one never silently moves
 * another. The derived per-phase timers are also pinned: the +5 rule is the
 * one place the screen's promise ("see what the timer means before you
 * commit") can drift from the arithmetic.
 */
class CreateGameViewModelTest {

  private val vm = CreateGameViewModel()

  @Test
  fun theScreenOpensOnTheFrozenDefaults() {
    val state = vm.state.value

    // RD21 + GM7, the pre-fill every arrival starts from.
    assertEquals("default Game Type is FULL", GameType.FULL, state.gameType)
    assertEquals("default Calculation Mode is NORMAL", ScoringMode.NORMAL, state.scoringMode)
    assertEquals("default bot tier is MEDIUM", BotTier.MEDIUM, state.botTier)
    assertEquals("default personality is BALANCED", BotPersonality.BALANCED, state.botPersonality)
    assertEquals("default decision timer is 15 s",
      CreateGameUiState.DEFAULT_DECISION_TIMER_SECONDS, state.decisionTimerSeconds)
  }

  @Test
  fun eachGroupIsSelectableWithoutDisturbingTheOthers() {
    vm.onGameType(GameType.MINI)
    vm.onScoringMode(ScoringMode.CLASSIC)
    vm.onBotTier(BotTier.EXPERT)
    vm.onBotPersonality(BotPersonality.TRICKSTER)
    vm.onDecisionTimerSeconds(20)

    val state = vm.state.value
    // Every selection landed…
    assertEquals(GameType.MINI, state.gameType)
    assertEquals(ScoringMode.CLASSIC, state.scoringMode)
    assertEquals(BotTier.EXPERT, state.botTier)
    assertEquals(BotPersonality.TRICKSTER, state.botPersonality)
    assertEquals(20, state.decisionTimerSeconds)

    // …and re-tuning one group leaves the other four where the host put them.
    vm.onDecisionTimerSeconds(5)
    assertEquals("the timer change did not disturb the Game Type",
      GameType.MINI, vm.state.value.gameType)
    assertEquals("the timer change did not disturb the Calculation Mode",
      ScoringMode.CLASSIC, vm.state.value.scoringMode)
    assertEquals("the timer change did not disturb the bot tier",
      BotTier.EXPERT, vm.state.value.botTier)
    assertEquals("the timer change did not disturb the personality",
      BotPersonality.TRICKSTER, vm.state.value.botPersonality)
    assertEquals(5, vm.state.value.decisionTimerSeconds)
  }

  @Test
  fun theDerivedPhaseTimesGiveCardPlayTheSelectionAndEverythingElseFiveMore() {
    // Under the 15 s default: Dash / Bidding / Estimates 20 s, Card Play 15 s.
    val defaults = vm.state.value.phaseDecisionSeconds.map { it.second }
    assertEquals(
      "card play uses the selection; the other three phases get +5",
      listOf(20, 20, 20, 15),
      defaults,
    )
    // Card Play is the last phase and holds the selection itself.
    val cardPlay = vm.state.value.phaseDecisionSeconds.last()
    assertEquals(CreateGameUiState.PHASE_CARD_PLAY, cardPlay.first)
    assertEquals(15, cardPlay.second)
  }

  @Test
  fun changingTheDecisionTimerMovesEveryDerivedPhaseWithIt() {
    vm.onDecisionTimerSeconds(20)

    assertEquals(
      "a 20 s selection makes the +5 phases 25 s and card play 20 s",
      listOf(25, 25, 25, 20),
      vm.state.value.phaseDecisionSeconds.map { it.second },
    )
  }
}
