package com.estemshan.game.ui.matchmaking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S55 — the elapsed timer, the one number the search screen shows that the
 * client measures itself. Elapsed only, never a countdown: the pool resolver
 * publishes no ETA, so there is no instant to count down from and the format
 * has to stay plain m:ss however long the search runs.
 *
 * The screen itself is verified by compilation and its previews; the repo
 * carries no Compose UI test harness, so this covers the logic the composable
 * owns rather than its composition.
 */
class MatchmakingScreenTest {

  @Test
  fun theTimerStartsAtZero() {
    assertEquals("0:00", formatElapsed(0L))
  }

  @Test
  fun subSecondSearchTimeRoundsDownToZero() {
    assertEquals("0:00", formatElapsed(999L))
  }

  @Test
  fun theDesignsSampleSearchReadsTwentyFourSeconds() {
    assertEquals("0:24", formatElapsed(24_000L))
  }

  @Test
  fun secondsPadToOneDigitUnderTen() {
    assertEquals("0:01", formatElapsed(1_000L))
    assertEquals("1:05", formatElapsed(65_999L))
  }

  @Test
  fun theTimerCarriesPastAnHourRatherThanRolling() {
    assertEquals("60:00", formatElapsed(3_600_000L))
    assertEquals("61:01", formatElapsed(3_661_000L))
  }
}
