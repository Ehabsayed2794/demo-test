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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.estemshan.engine.rankLabel
import com.estemshan.engine.resolveRanked
import com.estemshan.game.ui.theme.BodyFamily
import com.estemshan.game.ui.theme.EstemshanColors
import com.estemshan.game.ui.theme.MonoFamily

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

// ── Exact ranked-block text specs (ranked-result.css, px → sp 1:1) ──

/** .rw-lab — mono 9px/600, letter-spacing .06em, ink-faint. */
private val RewardLabelStyle = TextStyle(
  fontFamily = MonoFamily,
  fontWeight = FontWeight.SemiBold,
  fontSize = 9.sp,
  letterSpacing = 0.54.sp,
  color = EstemshanColors.InkFaint,
)

/** .rank-detail — Saira 10px, line-height 1.55, ink-dim. */
private val RankDetailStyle = TextStyle(
  fontFamily = BodyFamily,
  fontSize = 10.sp,
  lineHeight = 15.5.sp,
  color = EstemshanColors.InkDim,
)

/** .rp-chip .rw-val — mono 23px/700. */
private fun rpValueStyle(negative: Boolean) = TextStyle(
  fontFamily = MonoFamily,
  fontWeight = FontWeight.Bold,
  fontSize = 23.sp,
  color = if (negative) EstemshanColors.Error else EstemshanColors.Legal,
)

/** .tier-chip — Saira 10px ink (9px beside a row name). */
private fun tierChipStyle(size: Int) = TextStyle(
  fontFamily = BodyFamily,
  fontSize = size.sp,
  color = EstemshanColors.Ink,
)

/**
 * The tier chip beside your name — your NEW rank, formatted by label().
 * Exact .tier-chip: GoldDim 1dp border, 6dp radius, 7×3dp padding.
 */
@Composable
fun TierChip(label: String, fontSize: Int = 10, modifier: Modifier = Modifier) {
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
      style = tierChipStyle(fontSize),
      maxLines = 1,
      softWrap = false,
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
    // ── RANKED RP (.rp-chip: flex 1.05, value absolute top-right) ──
    Surface(
      modifier = Modifier
        .weight(1.05f)
        .border(1.dp, EstemshanColors.PanelLine, RoundedCornerShape(12.dp)),
      color = EstemshanColors.Pill,
      shape = RoundedCornerShape(12.dp),
    ) {
      Column(
        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text("RANKED RP", style = RewardLabelStyle)
        Text(
          formatRpDelta(displayedDelta),
          modifier = Modifier.testTag("rpDelta"),
          style = rpValueStyle(displayedDelta < 0),
        )
        Text("${ranked.rp} RP", style = RankDetailStyle)
        if (ranked.reason != null) {
          Text(ranked.reason, style = RankDetailStyle)
        }
        if (ranked.ceiling) {
          Text("Above 3,000 RP · leaderboard-only", style = RankDetailStyle)
        }
      }
    }
    // ── RANK TRANSITION (.transition-chip: flex 1.5) ──
    Surface(
      modifier = Modifier
        .weight(1.5f)
        .border(1.dp, EstemshanColors.PanelLine, RoundedCornerShape(12.dp)),
      color = EstemshanColors.Pill,
      shape = RoundedCornerShape(12.dp),
    ) {
      Column(
        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text("RANK TRANSITION", style = RewardLabelStyle)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          TierChip(ranked.previousRankLabel)
          Text(
            "→",
            style = RankDetailStyle,
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
                Modifier.border(1.dp, EstemshanColors.Error, RoundedCornerShape(6.dp))
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
              style = tierChipStyle(10).copy(
                color = when {
                  rankResolved && ranked.movement < 0 -> EstemshanColors.Error
                  rankResolved && ranked.movement > 0 -> EstemshanColors.Legal
                  else -> EstemshanColors.Ink
                },
              ),
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }
    // ── SEASON (.season-chip: flex .85) ──
    Surface(
      modifier = Modifier
        .weight(0.85f)
        .border(1.dp, EstemshanColors.PanelLine, RoundedCornerShape(12.dp)),
      color = EstemshanColors.Pill,
      shape = RoundedCornerShape(12.dp),
    ) {
      Column(
        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text("SEASON", style = RewardLabelStyle)
        Text(ranked.season.id, style = RankDetailStyle)
        Text("${ranked.season.start} → ${ranked.season.end}", style = RankDetailStyle)
      }
    }
  }
}
