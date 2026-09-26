package com.estemshan.game.ui.chooselevel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.game.R
import com.estemshan.game.ui.bot.BotTablePreset
import com.estemshan.game.ui.theme.EstemshanTheme

/**
 * One opponent chair as the Choose Level screen edits it: a [tier] (how well it
 * plays) and a [personality] (what it does with a hand), the two being
 * orthogonal — see [BotTier] and [BotPersonality]. [seatNumber] is UI chrome
 * for the chair's label; the engine's own seat id stays in [seat].
 */
data class OpponentConfig(
  val seat: String,
  val seatNumber: Int,
  val tier: BotTier,
  val personality: BotPersonality,
)

/**
 * The screen's whole state. [appliedPreset] is the table the player last picked
 * wholesale — `null` once they tune a seat by hand, which is exactly the
 * "Custom" state. Everything the list needs to render is here, so the
 * composable below stays stateless: the view model (S12 Step 3) owns mutations
 * and turns the resulting opponents into a [com.estemshan.game.ui.bot.BotRoster]
 * for [com.estemshan.game.ui.quickmatch.QuickMatchViewModel.startMatch].
 */
data class ChooseLevelUiState(
  val opponents: List<OpponentConfig>,
  val presets: List<BotTablePreset> = BotTablePreset.ALL,
  val appliedPreset: BotTablePreset? = null,
)

/**
 * Spec 02 — pick the table before the deal. The lobby's "Play vs AI" entry
 * lands here; the default quick-match entry stays human-only and never routes
 * through it.
 *
 * Tier and personality text is read straight off the engine enums'
 * `displayName` / `blurb`, so the screen can never show a bot the engine no
 * longer backs. Only structural text (titles, section headers, the CTA) goes
 * through [stringResource] — the app's first string resources, sized for a
 * `values-ar` sibling to translate later without touching Kotlin.
 */
@Composable
fun ChooseLevelScreen(
  state: ChooseLevelUiState,
  onPresetSelected: (BotTablePreset) -> Unit,
  onTierSelected: (OpponentConfig, BotTier) -> Unit,
  onPersonalitySelected: (OpponentConfig, BotPersonality) -> Unit,
  onStartMatch: () -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    item { HeaderArt() }
    item { TitleBlock() }
    item {
      SectionLabel(stringResource(R.string.choose_level_presets))
    }
    items(state.presets, key = { it.name }) { preset ->
      PresetCard(
        preset = preset,
        selected = preset == state.appliedPreset,
        onClick = { onPresetSelected(preset) },
      )
    }
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SectionLabel(stringResource(R.string.choose_level_opponents))
        if (state.appliedPreset == null) {
          Text(
            stringResource(R.string.choose_level_custom),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
    items(state.opponents, key = { it.seat }) { opponent ->
      OpponentCard(
        opponent = opponent,
        onTierSelected = { onTierSelected(opponent, it) },
        onPersonalitySelected = { onPersonalitySelected(opponent, it) },
      )
    }
    item {
      Button(
        onClick = onStartMatch,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
      ) {
        Text(stringResource(R.string.choose_level_start))
      }
    }
  }
}

/** The lobby's existing AI art, sized by height: the piece is portrait, so a
 *  full-width banner would crop it to a sliver. Aspect keeps it undistorted. */
@Composable
private fun HeaderArt() {
  Image(
    painter = painterResource(R.drawable.ai_header),
    contentDescription = null,
    modifier = Modifier.height(180.dp).aspectRatio(HEADER_ASPECT),
    contentScale = ContentScale.Crop,
  )
}

@Composable
private fun TitleBlock() {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      stringResource(R.string.choose_level_title),
      style = MaterialTheme.typography.headlineMedium,
    )
    Text(
      stringResource(R.string.choose_level_subtitle),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.fillMaxWidth(),
  )
}

/** A ready-made lineup: one tap seats all three opponents. Selection is a gold
 *  border plus a gold title, matching the filled-vs-outlined idiom the bidding
 *  controls use for a picked suit. */
@Composable
private fun PresetCard(preset: BotTablePreset, selected: Boolean, onClick: () -> Unit) {
  Card(
    onClick = onClick,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        preset.name,
        style = MaterialTheme.typography.titleMedium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      )
      Text(preset.blurb, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** One chair: a skill picker, a style picker, and the blurb of whatever is
 *  currently picked — read off the enum, never copied into the screen. */
@Composable
private fun OpponentCard(
  opponent: OpponentConfig,
  onTierSelected: (BotTier) -> Unit,
  onPersonalitySelected: (BotPersonality) -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        stringResource(R.string.choose_level_seat, opponent.seatNumber),
        style = MaterialTheme.typography.labelLarge,
      )
      PickerRow(
        label = stringResource(R.string.choose_level_skill),
        options = BotTier.entries,
        selected = opponent.tier,
        labelFor = { it.displayName },
        onSelect = onTierSelected,
      )
      Text(opponent.tier.blurb, style = MaterialTheme.typography.bodyMedium)
      PickerRow(
        label = stringResource(R.string.choose_level_style),
        options = BotPersonality.entries,
        selected = opponent.personality,
        labelFor = { it.displayName },
        onSelect = onPersonalitySelected,
      )
      Text(opponent.personality.blurb, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** The app's pick-one-of-N idiom (see the bidding screen's suit picker): the
 *  chosen option is a filled button, the rest outlined. */
@Composable
private fun <T> PickerRow(
  label: String,
  options: Collection<T>,
  selected: T,
  labelFor: (T) -> String,
  onSelect: (T) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      for (option in options) {
        if (option == selected) {
          Button(
            onClick = { onSelect(option) },
            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
          ) {
            Text(labelFor(option))
          }
        } else {
          OutlinedButton(
            onClick = { onSelect(option) },
            modifier = Modifier.weight(1f).heightIn(min = 44.dp),
          ) {
            Text(labelFor(option))
          }
        }
      }
    }
  }
}

// The header art is 784x1168; kept here so a reswap stays honest about shape.
private const val HEADER_ASPECT = 784f / 1168f

// ===========================================================================
//  Previews — one per state the screen can hold.
// ===========================================================================

private fun customOpponents(): List<OpponentConfig> = listOf(
  OpponentConfig("p2", 2, BotTier.MEDIUM, BotPersonality.BALANCED),
  OpponentConfig("p3", 3, BotTier.EXPERT, BotPersonality.AGGRESSIVE),
  OpponentConfig("p4", 4, BotTier.EASY, BotPersonality.TRICKSTER),
)

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun ChooseLevelDefaultPreview() {
  EstemshanTheme {
    ChooseLevelScreen(
      state = ChooseLevelUiState(
        opponents = opponentsFrom(BotTablePreset.DEFAULT, DEFAULT_SEATS, DEFAULT_SEATS.first()),
        appliedPreset = BotTablePreset.DEFAULT,
      ),
      onPresetSelected = {},
      onTierSelected = { _, _ -> },
      onPersonalitySelected = { _, _ -> },
      onStartMatch = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun ChooseLevelGentlePreview() {
  EstemshanTheme {
    ChooseLevelScreen(
      state = ChooseLevelUiState(
        opponents = opponentsFrom(BotTablePreset.Gentle, DEFAULT_SEATS, DEFAULT_SEATS.first()),
        appliedPreset = BotTablePreset.Gentle,
      ),
      onPresetSelected = {},
      onTierSelected = { _, _ -> },
      onPersonalitySelected = { _, _ -> },
      onStartMatch = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun ChooseLevelSharkTankPreview() {
  EstemshanTheme {
    ChooseLevelScreen(
      state = ChooseLevelUiState(
        opponents = opponentsFrom(BotTablePreset.SharkTank, DEFAULT_SEATS, DEFAULT_SEATS.first()),
        appliedPreset = BotTablePreset.SharkTank,
      ),
      onPresetSelected = {},
      onTierSelected = { _, _ -> },
      onPersonalitySelected = { _, _ -> },
      onStartMatch = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun ChooseLevelCustomPreview() {
  EstemshanTheme {
    ChooseLevelScreen(
      state = ChooseLevelUiState(opponents = customOpponents(), appliedPreset = null),
      onPresetSelected = {},
      onTierSelected = { _, _ -> },
      onPersonalitySelected = { _, _ -> },
      onStartMatch = {},
    )
  }
}
