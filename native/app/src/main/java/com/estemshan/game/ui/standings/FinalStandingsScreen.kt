package com.estemshan.game.ui.standings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.estemshan.game.ui.theme.EstemshanColors
import com.estemshan.game.ui.theme.EstemshanTheme
import kotlinx.coroutines.delay

/**
 * Read-only standings. Stateless: all data arrives as UiState.
 *
 * S57 adds the Ranked half as an opt-in nullable [ranked] parameter —
 * the same idiom as RoundStateProvider.HUMANS_ONLY / S15's RoomPort
 * seam: today's non-ranked callers pass nothing and render
 * byte-identically to before (the early-return path below is the
 * untouched table/quick-match rendering). The S45 destination does not
 * exist yet, so [onReturnToRanked] is a host-callback seam (mirroring
 * the reference's window.returnToRanked("S45")) that navigates nowhere
 * by default — no route is invented here.
 */
@Composable
fun FinalStandingsScreen(
  state: FinalStandingsUiState,
  ranked: RankedResultUiState? = null,
  onReturnToRanked: () -> Unit = {},
) {
  if (ranked == null) {
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
    return
  }
  RankedFinalStandings(state, ranked, onReturnToRanked)
}

/**
 * S57 ranked path: base rows (tallied) + rewards strip + Return to
 * Ranked CTA, played through the reveal sequence ported from
 * revealSequence() in standings-render.js.
 *
 * Animation is Animatable/LaunchedEffect scoped to this composition, so
 * leaving the screen or recomposing with a new result cancels the
 * sequence structurally (S19 Heartbeat seam rule). Unit tests drive
 * [buildRankedResult]/[formatRpDelta]/[RankedReveal] helpers, never
 * this clock.
 */
@Composable
private fun RankedFinalStandings(
  state: FinalStandingsUiState,
  ranked: RankedResultUiState,
  onReturnToRanked: () -> Unit,
) {
  val scoreProgress = remember(state, ranked) { Animatable(0f) }
  val rpProgress = remember(state, ranked) { Animatable(0f) }
  var rowsIn by remember(state, ranked) { mutableStateOf(false) }
  var rankResolved by remember(state, ranked) { mutableStateOf(false) }

  LaunchedEffect(state, ranked) {
    rowsIn = false
    rankResolved = false
    scoreProgress.snapTo(0f)
    rpProgress.snapTo(0f)
    rowsIn = true
    scoreProgress.animateTo(
      1f,
      tween(RankedReveal.ScoreTallyMillis, easing = RankedReveal.EaseOutCubic),
    )
    delay(RankedReveal.RpStartDelayMillis)
    rpProgress.animateTo(
      1f,
      tween(RankedReveal.RpTallyMillis, easing = RankedReveal.EaseOutCubic),
    )
    rankResolved = true
  }

  val scoreDone = scoreProgress.value >= 1f
  val lastIndex = (state.rows.size - 1).coerceAtLeast(0)
  val maxTotal = state.rows.maxOfOrNull { it.total }
  val minTotal = state.rows.minOfOrNull { it.total }
  // Unique last place only — ties share no Koz (RD8 / unique-Koz rule).
  val kozSeat = if (minTotal != null && state.rows.count { it.total == minTotal } == 1) {
    state.rows.first { it.total == minTotal }.seat
  } else {
    null
  }
  val userTotal = state.rows.firstOrNull { it.seat == ranked.userSeat }?.total
  val userIsMatchKing = userTotal != null && maxTotal != null && userTotal == maxTotal

  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      // Title crowns appear ONLY for Match King (RD8) — never for Rank
      // King, never for every outcome as the old title did.
      if (userIsMatchKing) {
        Text(
          "♛ ",
          modifier = Modifier.testTag("matchKingCrownTitle"),
          style = MaterialTheme.typography.headlineMedium,
          color = EstemshanColors.Gold,
        )
      }
      Text(state.title, style = MaterialTheme.typography.headlineMedium)
    }
    Spacer(Modifier.height(16.dp))
    LazyColumn(
      modifier = Modifier.fillMaxWidth().weight(1f).testTag("standingsList"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      itemsIndexed(state.rows, key = { _, row -> row.seat }) { index, row ->
        AnimatedVisibility(
          visible = rowsIn,
          enter = slideInVertically(
            initialOffsetY = { 42 },
            animationSpec = tween(
              durationMillis = 400,
              delayMillis = RankedReveal.rowDelayMillis(index, lastIndex).toInt(),
              easing = RankedReveal.EaseOutCubic,
            ),
          ) + fadeIn(
            animationSpec = tween(
              durationMillis = 400,
              delayMillis = RankedReveal.rowDelayMillis(index, lastIndex).toInt(),
            ),
          ),
        ) {
          RankedStandingRowCard(
            row = row,
            displayedTotal = tallyScoreFrame(row.total, scoreProgress.value),
            isMatchKing = row.isWinner,
            isKoz = row.seat == kozSeat,
            scoreDone = scoreDone,
            tierChip = if (row.seat == ranked.userSeat) ranked.nextRankLabel else null,
          )
        }
      }
    }
    Spacer(Modifier.height(16.dp))
    RankedRewardsStrip(
      ranked = ranked,
      displayedDelta = tallyDeltaFrame(ranked.delta, rpProgress.value),
      rankResolved = rankResolved,
    )
    Spacer(Modifier.height(16.dp))
    Button(
      onClick = onReturnToRanked,
      modifier = Modifier.fillMaxWidth().testTag("returnToRanked"),
    ) {
      Text("Return to Ranked")
    }
  }
}

/**
 * Ranked row: Match King (1st place, ties share) keeps the crown in
 * Match-King gold #E8A33D; Rank King gets no crown and no divisions
 * (RD8 three-way distinction); unique last keeps the Koz mug; your row
 * carries your NEW rank's tier chip. The Gold tier chip never reuses
 * the Match-King gold — it is a GoldDim-bordered chip with ink text.
 */
@Composable
private fun RankedStandingRowCard(
  row: StandingRow,
  displayedTotal: Int,
  isMatchKing: Boolean,
  isKoz: Boolean,
  scoreDone: Boolean,
  tierChip: String?,
) {
  // Champion pop on the winning row(s) after the score tally.
  val crownScale by animateFloatAsState(
    if (scoreDone && isMatchKing) 1f else 0.3f,
    tween(durationMillis = 550, easing = RankedReveal.EaseOutCubic),
    label = "crownPop",
  )
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
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f),
    ) {
      if (isMatchKing) {
        Text(
          "♛",
          modifier = Modifier
            .testTag("matchKingCrown")
            .graphicsLayer(scaleX = crownScale, scaleY = crownScale),
          style = MaterialTheme.typography.titleMedium,
          color = EstemshanColors.Gold,
        )
      } else if (isKoz) {
        Text(
          "☕",
          modifier = Modifier.testTag("kozBadge"),
          style = MaterialTheme.typography.titleMedium,
        )
      }
      Text(
        row.seat + (if (row.saaydaBadge) " · Sa'ayda" else ""),
        style = MaterialTheme.typography.titleMedium,
        color = nameColor,
      )
      if (tierChip != null) TierChip(tierChip)
    }
    Text(
      "${if (row.lastDelta >= 0) "+" else ""}${row.lastDelta} · $displayedTotal",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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

@Preview(showBackground = true, backgroundColor = 0xFF0D0A07)
@Composable
private fun RankedStandingsPreview() {
  EstemshanTheme {
    FinalStandingsScreen(
      buildStandings(
        totals = mapOf("p1" to 186, "p2" to 142, "p3" to 98, "p4" to 64),
        lastDeltas = mapOf("p1" to 24, "p2" to -22, "p3" to 12, "p4" to 0),
      ),
      ranked = buildRankedResult(
        previousRP = 600,
        delta = 30,
        season = sampleSeason(),
        userSeat = "p1",
      ),
    )
  }
}
