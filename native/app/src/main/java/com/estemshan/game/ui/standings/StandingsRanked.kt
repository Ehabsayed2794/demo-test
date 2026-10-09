package com.estemshan.game.ui.standings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.estemshan.engine.rankLabel
import com.estemshan.engine.resolveRanked
import com.estemshan.game.ui.theme.EstemshanColors

/**
 * S57 — the Ranked half of the Final Standings screen.
 *
 * Mirrors the JS production binding `{ previousRP, delta, reason,
 * season: { id, start, end } }` (README integration contract): every
 * field below is plain Kotlin — no engine or document types leak into
 * the state. The engine's [resolveRanked] runs once inside
 * [buildRankedResult] (the caller supplies the already-final delta, so
 * no award formula lives here), and only the resulting labels/ints are
 * stored.
 *
 * Augments [FinalStandingsUiState]; the base rows ([buildStandings] /
 * [StandingRow]) are reused unchanged.
 */
data class RankedSeasonUiState(
  val id: String,
  val start: String,
  val end: String,
)

data class RankedResultUiState(
  /** Previous RP, floored at 0 (RD3). */
  val previousRP: Int,
  /** EFFECTIVE signed delta AFTER the floor clamp (shows 0 at the floor). */
  val delta: Int,
  /** Floor-clamped RP after applying delta (≥ 0 always). */
  val rp: Int,
  /** Formatted by the ladder: "Gold III · معلم", "King · ملك". */
  val previousRankLabel: String,
  val nextRankLabel: String,
  /** +1 promote, 0 settled, −1 demote. */
  val movement: Int,
  /** Previous was King and RP grew — leaderboard-only overflow (RD27). */
  val ceiling: Boolean,
  /** Plain-language mixed-tier direction; null when absent. */
  val reason: String? = null,
  val season: RankedSeasonUiState,
  /** Which standings seat is "you" (gets the tier chip); null hides it. */
  val userSeat: String? = null,
)

/**
 * Build the presentation model from the production binding. [delta] is
 * the final RP-engine integer (mode + mixed-tier adjusted) — never
 * derived from match scores, never halved for Mini here.
 */
fun buildRankedResult(
  previousRP: Int,
  delta: Int,
  reason: String? = null,
  season: RankedSeasonUiState,
  userSeat: String? = null,
): RankedResultUiState {
  val r = resolveRanked(previousRP, delta)
  return RankedResultUiState(
    previousRP = r.previousRP,
    delta = r.delta,
    rp = r.rp,
    previousRankLabel = rankLabel(r.previous),
    nextRankLabel = rankLabel(r.next),
    movement = r.movement,
    ceiling = r.ceiling,
    reason = reason,
    season = season,
    userSeat = userSeat,
  )
}

/**
 * Signed delta exactly as standings-render.js tallies it: "+30", "−30"
 * (U+2212 MINUS SIGN, not a hyphen), "0".
 */
fun formatRpDelta(delta: Int): String = when {
  delta > 0 -> "+$delta"
  delta < 0 -> "−${-delta}"
  else -> "0"
}

/** Bound-sample season from ranked-result.js (preview only). */
fun sampleSeason(): RankedSeasonUiState =
  RankedSeasonUiState(id = "SAMPLE-S12", start = "2026-07-01", end = "2026-10-01")

/** The tier chip beside your name — your NEW rank, formatted by label(). */
@Composable
fun TierChip(label: String, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier
      .testTag("tierChip")
      .border(1.dp, EstemshanColors.GoldDim, RoundedCornerShape(6.dp)),
    color = Color.Transparent,
    shape = RoundedCornerShape(6.dp),
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

/**
 * The rewards strip: three chips per standings-render.js.
 *
 * [displayedDelta] is the animated RP tally value (0 → delta); the next
 * rank chip takes promoted/demoted/settled styling only when
 * [rankResolved] (set on RP-tally completion — see RankedReveal).
 */
@Composable
fun RankedRewardsStrip(
  ranked: RankedResultUiState,
  displayedDelta: Int,
  rankResolved: Boolean,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag("rankedRewards"),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // ── RANKED RP ──
    Surface(
      modifier = Modifier.weight(1.05f),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(12.dp),
    ) {
      Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(
          "RANKED RP",
          style = MaterialTheme.typography.labelLarge,
          color = EstemshanColors.InkFaint,
        )
        Text(
          formatRpDelta(displayedDelta),
          modifier = Modifier.testTag("rpDelta"),
          style = MaterialTheme.typography.headlineMedium,
          color = if (displayedDelta < 0) {
            MaterialTheme.colorScheme.error
          } else {
            EstemshanColors.Legal
          },
        )
        Text(
          "${ranked.rp} RP",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ranked.reason != null) {
          Text(
            ranked.reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (ranked.ceiling) {
          Text(
            "Above 3,000 RP · leaderboard-only",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    // ── RANK TRANSITION ──
    Surface(
      modifier = Modifier.weight(1.5f),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(12.dp),
    ) {
      Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(
          "RANK TRANSITION",
          style = MaterialTheme.typography.labelLarge,
          color = EstemshanColors.InkFaint,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          TierChip(ranked.previousRankLabel)
          Text(
            "→",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          val nextModifier = if (rankResolved) {
            Modifier.testTag(
              when {
                ranked.movement > 0 -> "nextRankPromoted"
                ranked.movement < 0 -> "nextRankDemoted"
                else -> "nextRankSettled"
              },
            )
          } else {
            Modifier.testTag("nextRank")
          }
          // 350 ms resolve transition (rankUp/rankDown in ranked-result.css).
          val resolveScale by animateFloatAsState(
            if (rankResolved) 1f else 0.92f,
            tween(RankedReveal.RankTransitionMillis, easing = RankedReveal.EaseOutCubic),
            label = "rankResolve",
          )
          Surface(
            modifier = nextModifier
              .graphicsLayer(scaleX = resolveScale, scaleY = resolveScale)
              .then(
              if (rankResolved && ranked.movement < 0) {
                Modifier.border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
              } else {
                Modifier.border(1.dp, EstemshanColors.GoldDim, RoundedCornerShape(6.dp))
              },
            ),
            color = Color.Transparent,
            shape = RoundedCornerShape(6.dp),
          ) {
            Text(
              ranked.nextRankLabel,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
              style = MaterialTheme.typography.labelLarge,
              color = when {
                rankResolved && ranked.movement < 0 -> MaterialTheme.colorScheme.error
                rankResolved && ranked.movement > 0 -> EstemshanColors.Legal
                else -> MaterialTheme.colorScheme.onSurface
              },
            )
          }
        }
      }
    }
    // ── SEASON ──
    Surface(
      modifier = Modifier.weight(0.85f),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(12.dp),
    ) {
      Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(
          "SEASON",
          style = MaterialTheme.typography.labelLarge,
          color = EstemshanColors.InkFaint,
        )
        Text(
          ranked.season.id,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "${ranked.season.start} → ${ranked.season.end}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
