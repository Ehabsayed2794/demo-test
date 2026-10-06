package com.estemshan.game.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * S28 motion specs for the Table screen. Shared durations only — no game
 * state, no intent logic. Control-state interactions stay <= 200 ms; the
 * 900 ms trick-resolution beat (TableScreen) is untouched and the sweep
 * (450 ms) always runs *within* it, never lengthening it.
 */
object EstemshanMotion {
  /** Card-play enter (hand -> table). Must stay <= 200 ms. */
  const val CardPlayMillis = 180

  /** Winner highlight scale pulse. Must stay <= 200 ms. */
  const val WinnerHighlightMillis = 180

  /**
   * Trick-collection sweep (four cards clearing toward the winner).
   * Runs inside the 900 ms RESOLVING beat — never gates onResolve().
   */
  const val SweepMillis = 450

  /** The existing TableScreen beat. Duplicated here for documentation only. */
  const val TrickBeatMillis = 900

  fun floatControlSpec(): FiniteAnimationSpec<Float> =
    tween(durationMillis = CardPlayMillis, easing = FastOutSlowInEasing)

  fun floatWinnerSpec(): FiniteAnimationSpec<Float> =
    tween(durationMillis = WinnerHighlightMillis, easing = FastOutSlowInEasing)

  fun floatSweepSpec(): FiniteAnimationSpec<Float> =
    tween(durationMillis = SweepMillis, easing = FastOutSlowInEasing)

  fun offsetCardPlaySpec(): FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = CardPlayMillis, easing = FastOutSlowInEasing)
}
