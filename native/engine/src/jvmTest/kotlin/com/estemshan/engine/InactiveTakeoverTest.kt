package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S73 Stage 2 (C1) — the persisted shape of an RD19 takeover: the one public
 * marker field, the three fields of the private `inactivity/{uid}` document, the
 * trick-bounded reclaim of DEC-RD19-04 DERIVED from the round/trick rather than
 * stored as a flag, fail-closed parsing that can never strand a human out of
 * their own seat, and the surgical add/remove writes.
 *
 * RD20's separation is asserted here as a NAME-level fact: no field of either
 * document may read like a timeout count, a removal or a bot seat, because A4.2
 * row C closes the takeover as "not incremented" + "seat returns" while path A
 * and path E close as permanent. `InactivityClockTest`'s 28 goldens are untouched
 * — including `thePersistedStateIsExactlyTwoFieldsAndCarriesNoTimeoutCounter`,
 * which stays true of the CLOCK while the DOCUMENT it is stored in carries the
 * streak cursor beside it.
 */
class InactiveTakeoverTest {

  private fun entry(
    takenOverAt: Any? = 10_000L,
    round: Any? = 3,
    trick: Any? = 2,
  ): Map<String, Any?> = linkedMapOf<String, Any?>(
    InactiveTakeover.TAKEN_OVER_AT_FIELD to takenOverAt,
    InactiveTakeover.ROUND_AT_TAKEOVER_FIELD to round,
    InactiveTakeover.TRICK_AT_TAKEOVER_FIELD to trick,
  )

  // ── the names, in one home ────────────────────────────────────────────

  @Test
  fun theMarkerFieldTheCollectionAndTheCursorAreNamedOnceForBothAuthorities() {
    assertEquals("inactiveTakeovers", InactiveTakeover.TAKEOVER_FIELD)
    assertEquals("inactivity", InactiveTakeover.INACTIVITY_COLLECTION)
    assertEquals("inactiveSeatActionCursor", InactiveTakeover.CURSOR_FIELD)
  }

  @Test
  fun thePrivateDocumentIsExactlyThreeFieldsAndKeepsTheClocksTwoFirst() {
    // Fork 5: the CLOCK still owns two fields; the DOCUMENT that stores it adds
    // the streak cursor. Both halves are named here so :functions and :services
    // cannot each declare their own list.
    assertEquals(
      listOf("inactiveAccumulatedMillis", "inactiveWindowStartMillis", "inactiveSeatActionCursor"),
      InactiveTakeover.inactivityDocFieldNames(),
    )
    assertEquals(
      InactivityClock.storedFieldNames() + InactiveTakeover.CURSOR_FIELD,
      InactiveTakeover.inactivityDocFieldNames(),
    )
  }

  @Test
  fun noFieldNameReadsLikeATimeoutCountARemovalOrABotSeat() {
    // RD20 separation, extended to the whole persisted surface of S73.
    val names = InactiveTakeover.inactivityDocFieldNames() +
      InactiveTakeover.entryFieldNames() +
      InactiveTakeover.TAKEOVER_FIELD
    assertTrue("the takeover owns no state path A or path E could read as theirs",
      names.none { it.contains("timeout") || it.contains("removed") || it.contains("botSeats") })
    assertFalse("trickFinished is derived, never persisted",
      InactiveTakeover.entryFieldNames().any { it.contains("trickFinished") })
  }

  @Test
  fun aMarkerEntryOwnsExactlyThreeFieldsInOrder() {
    val fields = InactiveTakeover(roundAtTakeover = 3, trickAtTakeover = 2, takenOverAtMillis = 10_000L).toFields()
    assertEquals(InactiveTakeover.entryFieldNames(), fields.keys.toList())
    assertEquals(10_000L, fields[InactiveTakeover.TAKEN_OVER_AT_FIELD])
    assertEquals(3, fields[InactiveTakeover.ROUND_AT_TAKEOVER_FIELD])
    assertEquals(2, fields[InactiveTakeover.TRICK_AT_TAKEOVER_FIELD])
  }

  // ── who holds the seat ────────────────────────────────────────────────

  @Test
  fun anAbsentMarkerLeavesTheSeatHuman() {
    assertEquals(SeatControl.Human(), storedSeatControl(null, "p2", roundNow = 3, trickNoNow = 5))
    assertEquals(SeatControl.Human(), storedSeatControl(emptyMap<String, Any?>(), "p2", 3, 5))
    assertEquals(emptySet<String>(), InactiveTakeover.takeoverSeats(null))
  }

  @Test
  fun aMarkerForAnotherSeatLeavesThisSeatHuman() {
    val marker = mapOf("p4" to entry())
    assertEquals(
      SeatControl.Human(clock = InactivityClock(accumulatedMillis = 40_000L)),
      storedSeatControl(marker, "p2", 3, 5, clock = InactivityClock(accumulatedMillis = 40_000L)),
    )
    assertEquals(setOf("p4"), InactiveTakeover.takeoverSeats(marker))
  }

  @Test
  fun aCompleteEntryBorrowsTheSeatAndCarriesTheTakeoverStamp() {
    val control = storedSeatControl(mapOf("p2" to entry()), "p2", roundNow = 3, trickNoNow = 2)
    assertEquals(SeatControl.Borrowed(takenOverAtMillis = 10_000L, trickFinished = false), control)
  }

  @Test
  fun theClockSurvivesTheReadSoReclaimIsOneExpression() {
    val control = storedSeatControl(null, "p2", 3, 2, clock = InactivityClock(accumulatedMillis = 49_999L, openWindowStartMillis = 50_000L))
    val clock = (control as SeatControl.Human).clock
    assertEquals(49_999L, clock.accumulatedMillis)
    assertEquals(50_000L, clock.openWindowStartMillis)
  }

  // ── DEC-RD19-04's trick bound, derived ────────────────────────────────

  @Test
  fun trickFinishedComesFromTheTrickNotFromAStoredFlag() {
    val owed = mapOf("p2" to entry(round = 3, trick = 2))
    assertFalse((storedSeatControl(owed, "p2", 3, 2) as SeatControl.Borrowed).trickFinished)
    assertTrue((storedSeatControl(owed, "p2", 3, 3) as SeatControl.Borrowed).trickFinished)
    assertThrows(IllegalStateException::class.java) { storedSeatControl(owed, "p2", 3, 2).reclaim() }
    assertEquals(SeatControl.Human(), storedSeatControl(owed, "p2", 3, 3).reclaim())
  }

  @Test
  fun aTakeoverDuringTheAuctionWaitsForTheFirstTrickOfThatRound() {
    // No trick was in progress when the seat was taken, so "the current trick"
    // is trick 0 and the bound is the first trick resolving.
    val duringAuction = mapOf("p2" to entry(round = 4, trick = InactiveTakeover.trickNo(0)))
    assertFalse((storedSeatControl(duringAuction, "p2", 4, InactiveTakeover.trickNo(0)) as SeatControl.Borrowed).trickFinished)
    assertFalse((storedSeatControl(duringAuction, "p2", 4, InactiveTakeover.trickNo(3)) as SeatControl.Borrowed).trickFinished)
    assertTrue((storedSeatControl(duringAuction, "p2", 4, InactiveTakeover.trickNo(4)) as SeatControl.Borrowed).trickFinished)
  }

  @Test
  fun aLaterRoundAlwaysMeansTheOwedTrickFinished() {
    val endOfRound = mapOf("p2" to entry(round = 5, trick = InactiveTakeover.TRICKS_PER_ROUND - 1))
    assertTrue((storedSeatControl(endOfRound, "p2", 6, 0) as SeatControl.Borrowed).trickFinished)
  }

  @Test
  fun trickNoAndSeatTrickKeyOrderAnEntireRound() {
    assertEquals(0, InactiveTakeover.trickNo(0))
    assertEquals(0, InactiveTakeover.trickNo(3))
    assertEquals(1, InactiveTakeover.trickNo(4))
    assertEquals(13, InactiveTakeover.trickNo(52))
    val keys = (1..52 step 4).map { InactiveTakeover.seatTrickKey(2, InactiveTakeover.trickNo(it)) }
    assertEquals(keys.sorted(), keys)
    assertTrue("the round term dominates, so a round advance can never look like a step back",
      InactiveTakeover.seatTrickKey(3, 0) > InactiveTakeover.seatTrickKey(2, 12))
  }

  // ── fail-closed parsing ───────────────────────────────────────────────

  @Test
  fun corruptEntriesFailClosedToHumanAndNeverToBorrowed() {
    assertNull(InactiveTakeover.parse(null))
    assertNull(InactiveTakeover.parse("p2"))
    assertNull(InactiveTakeover.parse(listOf(entry())))
    assertNull(InactiveTakeover.parse(entry(takenOverAt = -1L)))
    assertNull(InactiveTakeover.parse(entry(takenOverAt = "10000")))
    assertNull(InactiveTakeover.parse(entry(takenOverAt = 10_000.5)))
    assertNull(InactiveTakeover.parse(entry(takenOverAt = null)))
    assertNull(InactiveTakeover.parse(entry(round = 0)))
    assertNull(InactiveTakeover.parse(entry(trick = -1)))
    // A trick that can never finish would strand the owner out of the seat for
    // the rest of the match, so it is outside the round's 13 and untrusted.
    assertNull(InactiveTakeover.parse(entry(trick = InactiveTakeover.TRICKS_PER_ROUND)))
    assertNull(InactiveTakeover.parse(entry(trick = 99)))
    val marker = mapOf("p2" to entry(trick = 99))
    assertEquals(SeatControl.Human(), storedSeatControl(marker, "p2", 3, 2))
    assertEquals(emptySet<String>(), InactiveTakeover.takeoverSeats(marker))
  }

  @Test
  fun oneGarbageEntryDoesNotDiscardAValidOne() {
    val marker: Map<Any, Any?> = mapOf(
      "p1" to entry(takenOverAt = 1_000L, round = 2, trick = 1),
      "p2" to entry(takenOverAt = "soon"),
      7 to entry(),
    )
    assertEquals(setOf("p1"), InactiveTakeover.takeoverSeats(marker))
    assertTrue(storedSeatControl(marker, "p1", 2, 2) is SeatControl.Borrowed)
    assertTrue(storedSeatControl(marker, "p2", 3, 2) is SeatControl.Human)
  }

  @Test
  fun anyWholeNumberShapeTheTransportMayHandOverIsTrusted() {
    // Kotlin/JS receives Firestore numbers as Double, the JVM as Int/Long.
    val expected = InactiveTakeover(takenOverAtMillis = 10_000L, roundAtTakeover = 3, trickAtTakeover = 2)
    assertEquals(expected, InactiveTakeover.parse(entry(takenOverAt = 10_000, round = 3, trick = 2)))
    assertEquals(expected, InactiveTakeover.parse(entry(takenOverAt = 10_000.0, round = 3.0, trick = 2.0)))
    assertEquals(expected, InactiveTakeover.parse(entry(takenOverAt = 10_000L, round = 3L, trick = 2L)))
  }

  // ── the writes ────────────────────────────────────────────────────────

  @Test
  fun withTakeoverReplacesOneSeatAndCarriesTheOthersVerbatim() {
    val stranger = mapOf("uninterpreted" to "shape")
    val written = InactiveTakeover.withTakeover(
      takeoverRaw = mapOf("p1" to entry(), "p9" to stranger),
      seatId = "p2",
      takeover = InactiveTakeover(20_000L, 4, 0),
    )
    assertEquals(listOf("p1", "p9", "p2"), written.keys.toList())
    assertEquals(stranger, written["p9"])
    // Pinned through the reader rather than a whole-map literal: a `mapOf(A to
    // 20_000L, B to 4)` literal coerces the Int literals to Long, which would
    // compare unequal against the Int round/trick the entry actually stores.
    val target = written["p2"] as Map<*, *>
    assertEquals(InactiveTakeover.entryFieldNames(), target.keys.toList())
    assertEquals(20_000L, target[InactiveTakeover.TAKEN_OVER_AT_FIELD])
    assertEquals(InactiveTakeover(20_000L, 4, 0), InactiveTakeover.parse(written["p2"]))
    assertTrue(storedSeatControl(written, "p2", 4, 1) is SeatControl.Borrowed)
  }

  @Test
  fun withoutTakeoverRemovesOnlyItsOwnSeat() {
    val marker = mapOf("p1" to entry(), "p2" to entry())
    val cleared = InactiveTakeover.withoutTakeover(marker, "p2")
    assertEquals(setOf("p1"), InactiveTakeover.takeoverSeats(cleared))
    assertTrue(storedSeatControl(cleared, "p2", 3, 2) is SeatControl.Human)
    // Re-delivered reclaim on a seat that is not borrowed must not disturb the
    // live takeover of another one.
    assertEquals(marker.keys - "p2", InactiveTakeover.withoutTakeover(cleared, "p2").keys)
  }

  // ── why the takeover write must zero the clock ────────────────────────

  @Test
  fun theQualifyingAccumulationIsNeverLeftOnTheDocument() {
    // R33's trap, pinned at the shape level: InactivityClock.fromStored refuses
    // any value at or above the threshold, so a takeover that leaves 50 s on the
    // doc produces state the NEXT look cannot parse — the clock dies for the rest
    // of the match and the seat is never taken over again after reclaim. The
    // write that sets the marker must persist a zeroed clock in the same
    // transaction, and reclaim zeroes it for the same reason.
    val qualifying = InactivityClock(accumulatedMillis = InactivityClock.TAKEOVER_THRESHOLD_MILLIS)
    assertNull("at-threshold is not state this mechanism produced",
      InactivityClock.fromStored(qualifying.accumulatedMillis, null))
    // So the only clock state that may sit beside a marker on that document is a
    // zeroed one: it is the only value the next look can still parse.
    val zeroed = InactivityClock().storedFields()
    assertEquals(
      InactivityClock(),
      InactivityClock.fromStored(
        accumulatedRaw = zeroed[InactivityClock.ACCUMULATED_FIELD],
        windowStartRaw = zeroed[InactivityClock.OPEN_WINDOW_FIELD],
      ),
    )
    val borrowed = SeatControl.Human(
      clock = InactivityClock(accumulatedMillis = 49_999L, openWindowStartMillis = 0L),
    ).takeOver(50_000L)
    assertFalse("a takeover does not finish the trick it owed", borrowed.trickFinished)
    assertThrows(IllegalStateException::class.java) { borrowed.reclaim() }
    assertEquals(InactivityClock(), (borrowed.markTrickFinished().reclaim() as SeatControl.Human).clock)
  }
}
