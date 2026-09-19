package com.estemshan.game.ui.standings

/**
 * FinalStandings presentation model. Pure Kotlin, no Compose and no engine
 * imports: the caller (later GameSession, today the Table screen) supplies
 * plain totals + last-round deltas. Winners are every seat tied at the max,
 * mirroring the engine's computeWinner rule.
 */
data class StandingRow(
  val seat: String,
  val total: Int,
  val lastDelta: Int,
  val isWinner: Boolean,
  val saaydaBadge: Boolean,
)

data class FinalStandingsUiState(
  val title: String,
  val rows: List<StandingRow>,
)

fun buildStandings(
  totals: Map<String, Int>,
  lastDeltas: Map<String, Int> = emptyMap(),
  saaydaSeats: Set<String> = emptySet(),
  title: String = "Final Standings",
): FinalStandingsUiState {
  if (totals.isEmpty()) return FinalStandingsUiState(title, emptyList())
  val max = totals.values.maxOrNull() ?: return FinalStandingsUiState(title, emptyList())
  val rows = totals.entries
    .sortedByDescending { it.value }
    .map { (seat, total) ->
      StandingRow(
        seat = seat,
        total = total,
        lastDelta = lastDeltas[seat] ?: 0,
        isWinner = total == max,
        saaydaBadge = saaydaSeats.contains(seat),
      )
    }
  return FinalStandingsUiState(title, rows)
}
