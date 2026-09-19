package com.estemshan.game.ui.standings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.game.ui.theme.EstemshanTheme

/** Read-only standings. Stateless: all data arrives as UiState. */
@Composable
fun FinalStandingsScreen(state: FinalStandingsUiState) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(state.title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(16.dp))
    LazyColumn(
      modifier = Modifier.fillMaxWidth().testTag("standingsList"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(state.rows, key = { it.seat }) { row ->
        StandingRowCard(row)
      }
    }
  }
}

@Composable
private fun StandingRowCard(row: StandingRow) {
  val nameColor = if (row.isWinner) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.onSurface
  }
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      (if (row.isWinner) "1 · " else "") + row.seat +
        (if (row.saaydaBadge) " · Sa'ayda" else ""),
      style = MaterialTheme.typography.titleMedium,
      color = nameColor,
    )
    Text(
      "${if (row.lastDelta >= 0) "+" else ""}${row.lastDelta} · ${row.total}",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun StandingsPreview() {
  EstemshanTheme {
    FinalStandingsScreen(
      buildStandings(
        totals = mapOf("p1" to 48, "p2" to 44, "p3" to 44, "p4" to 12),
        lastDeltas = mapOf("p1" to 24, "p2" to -22, "p3" to 12, "p4" to 0),
        saaydaSeats = setOf("p4"),
      ),
    )
  }
}
