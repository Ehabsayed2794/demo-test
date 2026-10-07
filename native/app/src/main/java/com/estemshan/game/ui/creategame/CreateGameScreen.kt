package com.estemshan.game.ui.creategame

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.game.R
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * S36 — Create Game. The one configuration surface the host configures a
 * match through, reached from the lobby's Create Room. Stateless: it renders
 * [state] and forwards every selection to [CreateGameViewModel], so each of
 * the five groups has exactly one owner and the derived per-phase timers are
 * computed from the same state the pickers write to.
 *
 * The five groups are presented in the order the design fixes them — Game
 * Type and Calculation first, because they are match-scoped (they change the
 * round count, the Quick Round boundary, the escalation cap, and — in Ranked,
 * later — the RP delta), then the RD21 trio the screen was built around. Both
 * new groups state their consequences in one line each rather than raw
 * numbers (GM1–GM3), and neither offers a third option (GM1/GM4).
 *
 * Only the Room state is reachable today: the Ranked state (rank chip, RD9's
 * pool rule, the private/password toggle) arrives with E6b and gets no UI
 * before its phase — no dead controls.
 */
@Composable
fun CreateGameScreen(
  state: CreateGameUiState,
  busy: Boolean,
  createError: Boolean,
  onGameType: (GameType) -> Unit,
  onScoringMode: (ScoringMode) -> Unit,
  onBotTier: (BotTier) -> Unit,
  onBotPersonality: (BotPersonality) -> Unit,
  onDecisionTimerSeconds: (Int) -> Unit,
  onCreateRoom: () -> Unit,
  onCancel: () -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    item {
      Column {
        Text(
          stringResource(R.string.create_game_title),
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          stringResource(R.string.create_game_subtitle),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // GM1–GM3: the two Game Types, each with its consequences on one line.
    item {
      GroupCard(stringResource(R.string.create_game_section_game_type)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          LabeledOption(
            name = stringResource(R.string.create_game_option_full),
            detail = stringResource(
              R.string.create_game_full_detail,
              GameType.FULL.baseRounds,
              GameType.FULL.firstFastRound,
              GameType.FULL.maxExtensions,
            ),
            selected = state.gameType == GameType.FULL,
            modifier = Modifier.weight(1f),
            onClick = { onGameType(GameType.FULL) },
          )
          LabeledOption(
            name = stringResource(R.string.create_game_option_mini),
            detail = stringResource(
              R.string.create_game_mini_detail,
              GameType.MINI.baseRounds,
              GameType.MINI.firstFastRound,
            ),
            selected = state.gameType == GameType.MINI,
            modifier = Modifier.weight(1f),
            onClick = { onGameType(GameType.MINI) },
          )
        }
      }
    }

    // GM4: the two Calculation Modes; the escalation cap is the only
    // player-visible difference, so it is the only thing stated.
    item {
      GroupCard(stringResource(R.string.create_game_section_calculation)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          LabeledOption(
            name = stringResource(R.string.create_game_option_normal),
            detail = stringResource(R.string.create_game_normal_detail),
            selected = state.scoringMode == ScoringMode.NORMAL,
            modifier = Modifier.weight(1f),
            onClick = { onScoringMode(ScoringMode.NORMAL) },
          )
          LabeledOption(
            name = stringResource(R.string.create_game_option_classic),
            detail = stringResource(R.string.create_game_classic_detail),
            selected = state.scoringMode == ScoringMode.CLASSIC,
            modifier = Modifier.weight(1f),
            onClick = { onScoringMode(ScoringMode.CLASSIC) },
          )
        }
      }
    }

    // RD21: the bot tier and personality the room's bot seats will play at.
    // Labels come from the engine enums (see strings.xml's header note).
    item {
      GroupCard(stringResource(R.string.create_game_section_bot_difficulty)) {
        PickerRow(
          options = BotTier.entries,
          selected = state.botTier,
          labelFor = { it.displayName },
          onSelect = onBotTier,
        )
      }
    }

    item {
      GroupCard(stringResource(R.string.create_game_section_bot_personality)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          for (row in BotPersonality.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              for (personality in row) {
                LabeledOption(
                  name = personality.displayName,
                  detail = personality.blurb,
                  selected = state.botPersonality == personality,
                  modifier = Modifier.weight(1f),
                  onClick = { onBotPersonality(personality) },
                )
              }
            }
          }
        }
      }
    }

    item {
      GroupCard(stringResource(R.string.create_game_section_decision_timer)) {
        PickerRow(
          options = CreateGameUiState.TIMER_CHOICES,
          selected = state.decisionTimerSeconds,
          labelFor = { stringResource(R.string.create_game_timer_seconds, it) },
          onSelect = onDecisionTimerSeconds,
        )
      }
    }

    item { PhaseSummary(state) }

    if (createError) {
      item {
        Text(
          stringResource(R.string.create_game_error),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    item {
      Button(
        onClick = onCreateRoom,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) {
        if (busy) {
          CircularProgressIndicator(
            modifier = Modifier.height(20.dp),
            color = MaterialTheme.colorScheme.onPrimary,
          )
        } else {
          Text(stringResource(R.string.create_game_confirm))
        }
      }
    }
    item {
      OutlinedButton(
        onClick = onCancel,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
      ) { Text(stringResource(R.string.create_game_cancel)) }
    }
  }
}

/**
 * A group's panel: the monospace section label above the group's controls.
 * The container is [MaterialTheme.colorScheme.surface], which is the
 * artboard's panel colour verbatim.
 */
@Composable
private fun GroupCard(label: String, content: @Composable () -> Unit) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(label, style = MaterialTheme.typography.labelLarge)
      content()
    }
  }
}

/**
 * A selectable option that carries a name and a one-line consequence —
 * Game Type, Calculation, and each bot personality. Selected takes the gold
 * border; the detail line is what makes the choice informed (GM1–GM4).
 */
@Composable
private fun LabeledOption(
  name: String,
  detail: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(
      containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
      else MaterialTheme.colorScheme.surface,
    ),
    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    modifier = modifier.fillMaxWidth(),
    onClick = onClick,
  ) {
    Column(
      Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        name,
        style = MaterialTheme.typography.titleMedium,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
      )
      Text(
        detail,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * A row of mutually-exclusive options where only the label differs — Bot
 * Difficulty and Decision Timer. The chosen option is a filled button, the
 * rest outlined, so the current value reads at a glance. This is the
 * Choose Level screen's idiom, unchanged.
 */
@Composable
private fun <T> PickerRow(
  options: List<T>,
  selected: T,
  labelFor: @Composable (T) -> String,
  onSelect: (T) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    for (option in options) {
      if (option == selected) {
        Button(
          onClick = { onSelect(option) },
          modifier = Modifier.weight(1f).heightIn(min = 44.dp),
        ) { Text(labelFor(option)) }
      } else {
        OutlinedButton(
          onClick = { onSelect(option) },
          modifier = Modifier.weight(1f).heightIn(min = 44.dp),
        ) { Text(labelFor(option)) }
      }
    }
  }
}

/**
 * The derived per-phase times for the current Decision Timer, with the +5
 * rule stated plainly: card play uses the selection, the other three phases
 * get five seconds more. The rule must be visible here, not discovered
 * mid-match (S36). Card Play is highlighted because it is the one phase the
 * selection applies to directly.
 */
@Composable
private fun PhaseSummary(state: CreateGameUiState) {
  GroupCard(stringResource(R.string.create_game_timer_summary)) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((phase, seconds) in state.phaseDecisionSeconds) {
          PhaseChip(
            name = phaseLabel(phase),
            seconds = seconds,
            highlighted = phase == CreateGameUiState.PHASE_CARD_PLAY,
            modifier = Modifier.weight(1f),
          )
        }
      }
      Text(
        stringResource(
          R.string.create_game_timer_summary_hint,
          CreateGameUiState.TIMER_BONUS_SECONDS,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** One phase and the time a decision in it gets. */
@Composable
private fun PhaseChip(
  name: String,
  seconds: Int,
  highlighted: Boolean,
  modifier: Modifier = Modifier,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    border = if (highlighted) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    modifier = modifier,
  ) {
    Column(
      Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(name, style = MaterialTheme.typography.labelLarge)
      Text(
        stringResource(R.string.create_game_timer_seconds, seconds),
        style = MaterialTheme.typography.titleMedium,
        color = if (highlighted) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

/** The one place a phase key becomes its label. */
@Composable
private fun phaseLabel(key: String): String = when (key) {
  CreateGameUiState.PHASE_DASH -> stringResource(R.string.create_game_phase_dash)
  CreateGameUiState.PHASE_BIDDING -> stringResource(R.string.create_game_phase_bidding)
  CreateGameUiState.PHASE_ESTIMATES -> stringResource(R.string.create_game_phase_estimates)
  CreateGameUiState.PHASE_CARD_PLAY -> stringResource(R.string.create_game_phase_card_play)
  else -> key
}

// ── Previews: one per state the screen can hold ─────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun CreateGameDefaultsPreview() {
  // The frozen pre-fill: FULL / NORMAL / MEDIUM / BALANCED / 15 s.
  EstemshanTheme {
    CreateGameScreen(
      state = CreateGameUiState(),
      busy = false,
      createError = false,
      onGameType = {},
      onScoringMode = {},
      onBotTier = {},
      onBotPersonality = {},
      onDecisionTimerSeconds = {},
      onCreateRoom = {},
      onCancel = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun CreateGameMiniClassicPreview() {
  // The host picked the short, tight format: MINI / CLASSIC / MASTER / TRICKSTER / 20 s.
  EstemshanTheme {
    CreateGameScreen(
      state = CreateGameUiState(
        gameType = GameType.MINI,
        scoringMode = ScoringMode.CLASSIC,
        botTier = BotTier.EXPERT,
        botPersonality = BotPersonality.TRICKSTER,
        decisionTimerSeconds = 20,
      ),
      busy = false,
      createError = false,
      onGameType = {},
      onScoringMode = {},
      onBotTier = {},
      onBotPersonality = {},
      onDecisionTimerSeconds = {},
      onCreateRoom = {},
      onCancel = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun CreateGameBusyPreview() {
  // Confirm tapped: the create is in flight, so the selections are locked.
  EstemshanTheme {
    CreateGameScreen(
      state = CreateGameUiState(),
      busy = true,
      createError = false,
      onGameType = {},
      onScoringMode = {},
      onBotTier = {},
      onBotPersonality = {},
      onDecisionTimerSeconds = {},
      onCreateRoom = {},
      onCancel = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun CreateGameErrorPreview() {
  // A create that failed at the transport; the selections survive it.
  EstemshanTheme {
    CreateGameScreen(
      state = CreateGameUiState(gameType = GameType.MINI),
      busy = false,
      createError = true,
      onGameType = {},
      onScoringMode = {},
      onBotTier = {},
      onBotPersonality = {},
      onDecisionTimerSeconds = {},
      onCreateRoom = {},
      onCancel = {},
    )
  }
}
