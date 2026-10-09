package com.estemshan.game.ui.standings

import androidx.compose.animation.core.Easing
import kotlin.math.floor

/**
 * S57 — reveal-sequence constants ported from standings-render.js and
 * the README integration contract. Total ≈ 2.2 s:
 *
 * - rows stagger in from the bottom: (lastIndex − i) × 90 ms
 * - score tally: 950 ms, ease-out cubic, each row 0 → total
 * - champion pop on the winning row(s)
 * - 350 ms delay, then RP tally: 550 ms, ease-out cubic, 0 → delta
 * - on RP-tally completion the next rank chip resolves to
 *   promoted / demoted / settled (+ 350 ms transition)
 *
 * The animation itself lives in [FinalStandingsScreen] as
 * Animatable/LaunchedEffect scoped to the composition, so the sequence
 * cancels structurally on leave or recomposition — the S19 Heartbeat
 * seam decision applies: no self-perpetuating loop may escape onto the
 * unit-test clock. Tests drive the pure helpers below and the
 * VM/state half ([buildRankedResult], [formatRpDelta]), never the
 * animation.
 */
object RankedReveal {
  /** Per-row stagger: (lastIndex − i) × 90 ms. */
  const val RowStaggerMillis = 90L

  /** Score tally duration. */
  const val ScoreTallyMillis = 950

  /** Pause after the score tally (and champion pop) before the RP tally. */
  const val RpStartDelayMillis = 350L

  /** RP tally duration. */
  const val RpTallyMillis = 550

  /** Rank-chip resolve transition after the RP tally. */
  const val RankTransitionMillis = 350

  /** Ease-out cubic: 1 − (1 − t)³ — the exact JS easing. */
  val EaseOutCubic = Easing { t ->
    val u = 1f - t.coerceIn(0f, 1f)
    1f - u * u * u
  }

  /** Stagger delay for row [index] of [lastIndex]. */
  fun rowDelayMillis(index: Int, lastIndex: Int): Long =
    (lastIndex - index).coerceAtLeast(0) * RowStaggerMillis

  /** Pure ease-out cubic (unit-testable, no clock). */
  fun easeOutCubic(t: Float): Float {
    val u = 1f - t.coerceIn(0f, 1f)
    return 1f - u * u * u
  }
}

/**
 * JS Math.round semantics for tally frames (half up toward +∞).
 * Matches `Math.round(x)` in standings-render.js exactly.
 */
fun jsRoundFrame(x: Float): Int = floor(x + 0.5f).toInt()

/** Displayed score for a row at [progress] (0 → 1) of the 950 ms tally. */
fun tallyScoreFrame(total: Int, progress: Float): Int {
  val e = RankedReveal.easeOutCubic(progress)
  return jsRoundFrame(total * e)
}

/** Displayed RP delta at [progress] (0 → 1) of the 550 ms tally. */
fun tallyDeltaFrame(delta: Int, progress: Float): Int {
  val e = RankedReveal.easeOutCubic(progress)
  return jsRoundFrame(delta * e)
}
