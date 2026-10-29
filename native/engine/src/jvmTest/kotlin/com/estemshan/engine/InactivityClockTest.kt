package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S73 — the RD19 decision-point inactivity clock (roadmap closure 2026-10-14,
 * DEC-RD19-01…05): accumulation across consecutive ignored windows, the 50 s
 * threshold that a single window can never reach, the reset on genuine
 * interaction inside the player's own window, fail-closed stored validation, the
 * RD20 separation from the timeout counter, and DEC-RD19-04's
 * finish-the-trick-then-return reclaim.
 *
 * Window lengths are the CALLER's inputs, so the tests supply RD21's real
 * figures rather than re-declaring them as engine constants: `decisionTimerSeconds`
 * is bounded 5..20, and bidding/dash/estimates windows carry a +5 s offset — so
 * the longest window the product can produce is 25 s.
 */
class InactivityClockTest {

  private val cardWindow = 20_000L
  private val longestWindow = 25_000L

  // ── the closed threshold, and why it must accumulate ───────────────────

  @Test
  fun t16theTakeoverThresholdIsTheClosedFiftySeconds() {
    assertEquals("RD19: takeover fires at 50 s of accumulated inactivity",
      50_000L, InactivityClock.TAKEOVER_THRESHOLD_MILLIS)
  }

  @Test
  fun t16oneMaximumLengthWindowAloneCanNeverReachTheThreshold() {
    // R33's arithmetic, pinned: RD21's longest window is 20 s + the 5 s offset.
    val ignored = InactivityClock()
      .windowOpened(startMillis = 1_000L)
      .windowClosed(nowMillis = 1_000L + longestWindow, durationMillis = longestWindow)
    assertEquals(longestWindow, ignored.accumulatedMillis)
    assertFalse("a single window must not take a seat over",
      ignored.takeoverEligibleAt(1_000L + longestWindow))
  }

  @Test
  fun t16twoConsecutiveIgnoredMaxWindowsReachItExactly() {
    val first = InactivityClock().windowOpened(0L).windowClosed(longestWindow, longestWindow)
    val second = first.windowOpened(longestWindow).windowClosed(2 * longestWindow, longestWindow)
    assertEquals(2 * longestWindow, second.accumulatedMillis)
    assertTrue("50 s is inclusive — the threshold is >= 50 s",
      second.takeoverEligibleAt(2 * longestWindow))
  }

  @Test
  fun t16threeIgnoredTwentySecondWindowsOvershootWhereTwoFallShort() {
    var clock = InactivityClock()
    for (index in 0..1) {
      val start = index * cardWindow
      clock = clock.windowOpened(start).windowClosed(start + cardWindow, cardWindow)
    }
    assertEquals(40_000L, clock.accumulatedMillis)
    assertFalse(clock.takeoverEligibleAt(40_000L))

    clock = clock.windowOpened(40_000L).windowClosed(60_000L, cardWindow)
    assertEquals(60_000L, clock.accumulatedMillis)
    assertTrue(clock.takeoverEligibleAt(60_000L))
  }

  @Test
  fun t16severalPartiallyIgnoredWindowsAccumulateTheSameWay() {
    val clock = InactivityClock()
      .windowOpened(0L).windowClosed(8_000L, cardWindow)
      .windowOpened(20_000L).windowClosed(23_000L, cardWindow)
      .windowOpened(50_000L).windowClosed(95_000L, cardWindow)
      .windowOpened(100_000L).windowClosed(125_000L, cardWindow)
    assertEquals(
      "8 + 3 + 20 (capped) + 20 = 51 s of ignored window time; the 69 s of gaps counts nothing",
      51_000L, clock.accumulatedMillis,
    )
    assertTrue(clock.takeoverEligibleAt(125_000L))
  }

  // ── the MEDIUM resolution is not evidence of anything ──────────────────

  @Test
  fun t16aMediumTimeoutResolutionResetsNothing() {
    // DEC-RD19-01: the MEDIUM decision substitutes for the player's input, so
    // the window that ended in a timeout counts in full and resets nothing —
    // even though the same event increments S61's separate timeout counter.
    val clock = InactivityClock()
      .windowOpened(0L).windowClosed(longestWindow, longestWindow)
      .windowOpened(longestWindow).windowClosed(2 * longestWindow, longestWindow)
    assertEquals(2 * longestWindow, clock.accumulatedMillis)
    assertTrue(clock.takeoverEligibleAt(2 * longestWindow))
  }

  @Test
  fun t16aClosedWindowContributesAtMostItsOwnLength() {
    val clock = InactivityClock()
      .windowOpened(0L).windowClosed(nowMillis = 40_000L, durationMillis = longestWindow)
    assertEquals("late delivery adds nothing beyond the window's own length",
      longestWindow, clock.accumulatedMillis)
  }

  @Test
  fun t16anOpenWindowPastItsDeadlineStillCounts() {
    val clock = InactivityClock().windowOpened(0L)
    assertEquals(40_000L, clock.inactiveMillis(40_000L))
    assertTrue("a window whose resolution is retrying is still ignored time",
      clock.takeoverEligibleAt(50_000L))
  }

  @Test
  fun t16timeWhenItIsNotTheirTurnCountsNothing() {
    val clock = InactivityClock().windowOpened(0L).windowClosed(longestWindow, longestWindow)
    assertEquals(longestWindow, clock.inactiveMillis(400_000L))
    assertFalse(clock.takeoverEligibleAt(400_000L))
  }

  // ── the reset rule ─────────────────────────────────────────────────────

  @Test
  fun t16aGenuineInteractionInsideTheOwnWindowResetsTheAccumulation() {
    val ignored = InactivityClock()
      .windowOpened(0L).windowClosed(longestWindow, longestWindow)
      .windowOpened(longestWindow).windowClosed(2 * longestWindow, longestWindow)
    val returned = ignored.windowOpened(60_000L).interactionDuringOwnWindow(65_000L)
    assertEquals(0L, returned.accumulatedMillis)
    assertFalse(returned.takeoverEligibleAt(65_000L))
  }

  @Test
  fun t16anInteractionReanchorsTheWindowSoEarlierTimeIsNotHeldAgainstThem() {
    val clock = InactivityClock().windowOpened(0L).interactionDuringOwnWindow(10_000L)
    assertEquals("the 10 s before the interaction is discarded, not accumulated",
      0L, clock.accumulatedMillis)
    assertEquals(5_000L, clock.inactiveMillis(15_000L))
  }

  @Test
  fun t16anInteractionOutsideAnOpenWindowIsNotAReset() {
    // DEC-RD19-01 scopes the reset to "within one of the player's own decision
    // windows", so a tap reported while another seat is acting proves nothing
    // about the accumulation — the call is refused rather than silently credited.
    val idle = InactivityClock(accumulatedMillis = 40_000L)
    val failure = assertThrows(IllegalStateException::class.java) {
      idle.interactionDuringOwnWindow(90_000L)
    }
    assertTrue(failure.message!!.contains("decision window"))
    assertEquals("the refused call changed nothing", 40_000L, idle.accumulatedMillis)
  }

  @Test
  fun t16anInteractionCannotPredateTheWindowItClaimsToBeInside() {
    assertThrows(IllegalArgumentException::class.java) {
      InactivityClock().windowOpened(50_000L).interactionDuringOwnWindow(40_000L)
    }
  }

  // ── state discipline ──────────────────────────────────────────────────

  @Test
  fun t16windowsNeverNest() {
    val open = InactivityClock().windowOpened(0L)
    val failure = assertThrows(IllegalStateException::class.java) { open.windowOpened(5_000L) }
    assertTrue(failure.message!!.contains("already open"))
  }

  @Test
  fun t16closingWithoutAnOpenWindowIsAnErrorNotSilentZero() {
    assertThrows(IllegalStateException::class.java) {
      InactivityClock(accumulatedMillis = 10_000L).windowClosed(20_000L, cardWindow)
    }
  }

  @Test
  fun t16thePersistedStateIsExactlyTwoFieldsAndCarriesNoTimeoutCounter() {
    // RD20 separation, made structural: the takeover mechanism owns two fields
    // and nothing that a 15-timeout removal or a Vote Kick could read as theirs.
    val names = InactivityClock.storedFieldNames()
    assertEquals(listOf("inactiveAccumulatedMillis", "inactiveWindowStartMillis"), names)
    val fields = InactivityClock(40_000L, 41_000L).storedFields()
    assertEquals(names, fields.keys.toList())
    assertEquals(40_000L, fields["inactiveAccumulatedMillis"])
    assertEquals(41_000L, fields["inactiveWindowStartMillis"])
    assertTrue("no counter, no removal, no kick field may ride along",
      names.none { it.contains("timeout") || it.contains("removed") || it.contains("bot") })
  }

  // ── fail-closed stored validation ─────────────────────────────────────

  @Test
  fun t16aMissingDocIsTheLegitimateFreshStart() {
    val fresh = InactivityClock.fromStored(accumulatedRaw = null, windowStartRaw = null, docExists = false)
    assertEquals(InactivityClock(), fresh)
  }

  @Test
  fun t16aPresentButUntrustedValueYieldsNullSoNoTakeoverCanFire() {
    assertNull(InactivityClock.fromStored(-1L, null))
    assertNull(InactivityClock.fromStored(50_000L, null))
    assertNull(InactivityClock.fromStored(999_999L, null))
    assertNull(InactivityClock.fromStored(20_000.5, null))
    assertNull(InactivityClock.fromStored("20000", null))
    assertNull(InactivityClock.fromStored(mapOf("v" to 20_000), null))
    assertNull(InactivityClock.fromStored(20_000L, "soon"))
  }

  @Test
  fun t16anyWholeNumberShapeTheTransportMayHandOverIsTrusted() {
    assertEquals(20_000L, InactivityClock.fromStored(20_000, null)!!.accumulatedMillis)
    assertEquals(20_000L, InactivityClock.fromStored(20_000L, null)!!.accumulatedMillis)
    assertEquals(20_000L, InactivityClock.fromStored(20_000.0, null)!!.accumulatedMillis)
    assertEquals(49_999L, InactivityClock.fromStored(49_999L, null)!!.accumulatedMillis)
  }

  @Test
  fun t16aValidStoredWindowAnchorSurvivesTheRoundTrip() {
    val stored = InactivityClock(30_000L, 44_000L)
    assertEquals(stored, InactivityClock.fromStored(30_000L, 44_000L))
    assertEquals(36_000L, stored.inactiveMillis(50_000L))
  }

  // ── takeover / reclaim (DEC-RD19-04) ──────────────────────────────────

  @Test
  fun t16takeoverIsRefusedBelowTheThreshold() {
    val seat = SeatControl.Human(InactivityClock(accumulatedMillis = 49_999L))
    val failure = assertThrows(IllegalStateException::class.java) { seat.takeOver(50_000L) }
    assertTrue(failure.message!!.contains("below the"))
  }

  @Test
  fun t16takeoverBorrowsTheSeatAndStopsTheHumanClock() {
    val seat = SeatControl.Human(InactivityClock(accumulatedMillis = 50_000L))
    val borrowed = seat.takeOver(60_000L)
    assertEquals(60_000L, borrowed.takenOverAtMillis)
    assertFalse(borrowed.trickFinished)
  }

  @Test
  fun t16takeoverIsIdempotentSoRedeliveryCannotReStampIt() {
    val seat = SeatControl.Human(InactivityClock(accumulatedMillis = 50_000L))
    val first = seat.takeOver(60_000L)
    assertEquals("a later delivery leaves the original takeover time standing",
      first, first.takeOver(90_000L))
  }

  @Test
  fun t16reclaimIsRefusedWhileTheBotStillOwesTheCurrentTrick() {
    val borrowed = SeatControl.Borrowed(takenOverAtMillis = 60_000L)
    val failure = assertThrows(IllegalStateException::class.java) { borrowed.reclaim() }
    assertTrue(failure.message!!.contains("current trick"))
  }

  @Test
  fun t16reclaimReturnsTheSeatToTheHumanWithAZeroedClock() {
    val reclaimed = SeatControl.Borrowed(takenOverAtMillis = 60_000L)
      .markTrickFinished()
      .reclaim()
    assertTrue(reclaimed is SeatControl.Human)
    assertEquals(InactivityClock(), (reclaimed as SeatControl.Human).clock)
  }

  @Test
  fun t16reclaimIsANoOpOnASeatTheHumanAlreadyHolds() {
    val seat = SeatControl.Human(InactivityClock(accumulatedMillis = 10_000L))
    assertEquals(
      "a caller that re-arms on every delivery must not reset a live seat's clock",
      seat, seat.reclaim(),
    )
  }

  @Test
  fun t16aReclaimedSeatAccumulatesAgainFromZero() {
    val borrowed = SeatControl.Human(InactivityClock(accumulatedMillis = 50_000L))
      .takeOver(50_000L)
    val returned = borrowed.markTrickFinished().reclaim() as SeatControl.Human
    val next = returned.clock.windowOpened(60_000L).windowClosed(80_000L, cardWindow)
    assertEquals("reclaim is not a strike carried into the next stretch",
      cardWindow, next.accumulatedMillis)
    assertFalse(next.takeoverEligibleAt(80_000L))
  }

  @Test
  fun t16markTrickFinishedIsAHostedSeatNoOp() {
    val seat = SeatControl.Human(InactivityClock(accumulatedMillis = 5_000L))
    assertEquals(seat, seat.markTrickFinished())
  }
}
