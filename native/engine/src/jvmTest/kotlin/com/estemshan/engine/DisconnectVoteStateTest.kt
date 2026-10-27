package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S74 runtime — the persisted flow state: the detection predicate, the wire
 * shape the resolver writes and both readers parse, the surgical marker
 * writes, and THE permanence policy point.
 *
 * The decision core's own contract (the 30/90 windows, 2-of-3, the charge
 * table) is [DisconnectVoteTest]'s and is not re-pinned here. What this file
 * pins is everything the runtime layers on top of it: when a seat is DETECTED,
 * what the marker is allowed to say, what a reconnect is allowed to erase, and
 * the single function that decides when a dropped seat stops being
 * reclaimable — the reading the runtime report flags for owner confirmation.
 */
class DisconnectVoteStateTest {

  private fun vote(phase: DisconnectVotePhase, seat: String = "p2"): DisconnectVoteState =
    when (phase) {
      DisconnectVotePhase.HOLD -> DisconnectVoteState(
        seatId = seat,
        phase = phase,
        detectedAtMillis = 1_000L,
      )
      DisconnectVotePhase.VOTE -> DisconnectVoteState(
        seatId = seat,
        phase = phase,
        detectedAtMillis = 1_000L,
        deadlineMillis = 1_000L + DisconnectVote.voteWindowSec * 1_000L,
        answeredCount = 1,
      )
      DisconnectVotePhase.WAIT -> DisconnectVoteState(
        seatId = seat,
        phase = phase,
        detectedAtMillis = 1_000L,
        deadlineMillis = 31_000L + DisconnectVote.waitWindowSec * 1_000L,
        answeredCount = 2,
        verdict = DisconnectVoteStatus.WAIT,
      )
      DisconnectVotePhase.REPLACED -> DisconnectVoteState(
        seatId = seat,
        phase = phase,
        detectedAtMillis = 1_000L,
        deadlineMillis = 31_000L,
        answeredCount = 2,
        verdict = DisconnectVoteStatus.NO_AGREEMENT,
      )
      DisconnectVotePhase.CANCELLED -> DisconnectVoteState(
        seatId = seat,
        phase = phase,
        detectedAtMillis = 1_000L,
      )
    }

  // ── detection: the heartbeat's freeze, and nothing else ──────────────

  @Test
  fun theStalenessThresholdIsTheRuntimesOwnDefinition() {
    // The one place the runtime DEFINES the silence that counts as "gone":
    // three S19 heartbeat intervals, so a single missed beat (worst case
    // ~60 s of apparent silence) can never borrow a present player's seat.
    assertEquals(90_000L, DisconnectVoteState.HEARTBEAT_STALE_AFTER_MILLIS)
  }

  @Test
  fun detectionFiresAtTheRuledSilenceAndNotAMomentBefore() {
    val now = 1_000_000L
    val threshold = DisconnectVoteState.HEARTBEAT_STALE_AFTER_MILLIS

    assertFalse(
      "a heartbeat stamped a moment inside the threshold is a live player",
      DisconnectVoteState.detectionDue(now - threshold + 1, now),
    )
    assertTrue(
      "the instant the threshold is reached the signal is gone",
      DisconnectVoteState.detectionDue(now - threshold, now),
    )
    assertFalse(
      "a fresh beat (one interval old) is a live player",
      DisconnectVoteState.detectionDue(now - 30_000L, now),
    )
  }

  @Test
  fun aPlayerWhoseHeartbeatNeverExistedIsNeverDetected() {
    // Absence of evidence is not evidence of absence: no lastSeenAt (a
    // profile predating the heartbeat) must never put a ballot about a
    // possibly-present player to the table.
    assertFalse(DisconnectVoteState.detectionDue(null, 9_999_999L))
  }

  // ── the wire shape ───────────────────────────────────────────────────

  @Test
  fun everyPhaseRoundTripsThroughItsOwnFieldNames() {
    for (phase in DisconnectVotePhase.entries) {
      val state = vote(phase)
      val fields = state.toFields()
      assertTrue(
        "phase $phase writes only the entry's own fields",
        fields.keys.all { it in DisconnectVoteState.entryFieldNames() },
      )
      assertEquals(
        "phase $phase parses back byte-identical",
        state,
        DisconnectVoteState.parse(state.seatId, fields),
      )
    }
  }

  @Test
  fun onlyTheRuledShapeParses() {
    val healthy = vote(DisconnectVotePhase.VOTE, seat = "p1").toFields()

    assertNull("a non-map is no flow", DisconnectVoteState.parse("p1", "OPEN"))
    assertNull("an unknown phase is no flow", DisconnectVoteState.parse("p1", healthy + (DisconnectVoteState.PHASE_FIELD to "PAUSED")))
    assertNull("a missing detection instant is no flow", DisconnectVoteState.parse("p1", healthy - DisconnectVoteState.DETECTED_AT_FIELD))
    assertNull("a fractional instant is no flow", DisconnectVoteState.parse("p1", healthy + (DisconnectVoteState.DETECTED_AT_FIELD to 1.5)))
    assertNull(
      "a deadline before the detection is a contradiction",
      DisconnectVoteState.parse("p1", healthy + (DisconnectVoteState.DEADLINE_FIELD to 999L)),
    )
    assertNull(
      "an open ballot carrying a verdict is a contradiction",
      DisconnectVoteState.parse("p1", healthy + (DisconnectVoteState.VERDICT_FIELD to "WAIT")),
    )
    assertNull(
      "a ballot without its close is a contradiction",
      DisconnectVoteState.parse("p1", healthy - DisconnectVoteState.DEADLINE_FIELD),
    )
    assertNull(
      "a hold with a bound is a contradiction",
      DisconnectVoteState.parse(
        "p1",
        vote(DisconnectVotePhase.HOLD, seat = "p1").toFields() + (DisconnectVoteState.DEADLINE_FIELD to 5L),
      ),
    )
    assertNull(
      "a wait without its own verdict is a contradiction",
      DisconnectVoteState.parse(
        "p1",
        vote(DisconnectVotePhase.WAIT, seat = "p1").toFields() + (DisconnectVoteState.VERDICT_FIELD to "CONTINUE"),
      ),
    )
    assertNull(
      "a replacement without its verdict is a contradiction",
      DisconnectVoteState.parse("p1", vote(DisconnectVotePhase.REPLACED, seat = "p1").toFields() - DisconnectVoteState.VERDICT_FIELD),
    )
    assertNull(
      "more answers than the table has humans is a contradiction",
      DisconnectVoteState.parse("p1", healthy + (DisconnectVoteState.ANSWERED_FIELD to 4)),
    )
    // A8.2 / Q36: CANCELLED is the dead-flow terminal shape — no deadline,
    // no verdict, and either one is a contradiction exactly as a `HOLD` with a
    // bound is.
    val cancelled = vote(DisconnectVotePhase.CANCELLED, seat = "p1").toFields()
    assertEquals(
      "a clean cancelled entry parses",
      vote(DisconnectVotePhase.CANCELLED, seat = "p1"),
      DisconnectVoteState.parse("p1", cancelled),
    )
    assertNull(
      "a cancelled entry with a deadline is a contradiction",
      DisconnectVoteState.parse("p1", cancelled + (DisconnectVoteState.DEADLINE_FIELD to 5_000L)),
    )
    assertNull(
      "a cancelled entry with a verdict is a contradiction",
      DisconnectVoteState.parse("p1", cancelled + (DisconnectVoteState.VERDICT_FIELD to "WAIT")),
    )
  }

  @Test
  fun corruptEntriesAreDroppedAndNeverStrandOrBorrow() {
    // Fail-closed, the InactiveTakeover direction: a corrupt entry reads as
    // "no flow" — the seat keeps its ordinary pace and the next resolver pass
    // re-detects a genuinely dropped player. Corruption cannot brick a seat.
    val raw = mapOf(
      "p1" to vote(DisconnectVotePhase.REPLACED, seat = "p1").toFields(),
      "p2" to vote(DisconnectVotePhase.WAIT, seat = "p2").toFields(),
      "p3" to "garbage",
      "p4" to 42L,
    )

    assertEquals(setOf("p1", "p2"), DisconnectVoteState.heldSeats(raw))
    assertEquals(
      "a replaced seat is held but no longer reclaimable",
      setOf("p2"),
      DisconnectVoteState.reclaimableSeats(raw),
    )
  }

  @Test
  fun aCancelledEntryIsHeldByNobodyAndReclaimableByNobody() {
    // A8.2 / Q36: the cancelled ballot is a dead flow. The seat it describes
    // was removed by the 7th takeover — the removal list, not this marker,
    // drives it — so the entry must not pace the seat, hold it, or offer it
    // back. Normal persisted matches never carry one (the removal deletes the
    // entry); this is the partial/old-write guard.
    val raw = mapOf(
      "p1" to vote(DisconnectVotePhase.CANCELLED, seat = "p1").toFields(),
      "p2" to vote(DisconnectVotePhase.HOLD, seat = "p2").toFields(),
    )

    assertEquals(setOf("p2"), DisconnectVoteState.heldSeats(raw))
    assertEquals(setOf("p2"), DisconnectVoteState.reclaimableSeats(raw))
    assertNull("a cancelled entry is not a pause", DisconnectVoteState.waitDeadlineMillis(raw))
    assertNull("a cancelled entry is not a live ballot", DisconnectVoteState.liveBallot(raw))
  }

  @Test
  fun thePauseIsReadFromTheGrantedWaitWindowOnly() {
    assertNull(
      "an open ballot is not a pause",
      DisconnectVoteState.waitDeadlineMillis(
        mapOf("p2" to vote(DisconnectVotePhase.VOTE).toFields()),
      ),
    )
    assertNull(
      "a replaced seat is not a pause",
      DisconnectVoteState.waitDeadlineMillis(
        mapOf("p2" to vote(DisconnectVotePhase.REPLACED).toFields()),
      ),
    )
    assertEquals(
      31_000L + DisconnectVote.waitWindowSec * 1_000L,
      DisconnectVoteState.waitDeadlineMillis(
        mapOf("p2" to vote(DisconnectVotePhase.WAIT).toFields()),
      ),
    )
  }

  @Test
  fun theLiveBallotIsTheCanonicalLowestSeat() {
    val raw = mapOf(
      "p3" to vote(DisconnectVotePhase.VOTE, seat = "p3").toFields(),
      "p1" to vote(DisconnectVotePhase.VOTE, seat = "p1").toFields(),
    )
    assertEquals("p1", DisconnectVoteState.liveBallot(raw)?.seatId)
  }

  // ── the surgical marker writes ───────────────────────────────────────

  @Test
  fun aTransitionNeverTouchesAnotherSeatsEntry() {
    val waiting = vote(DisconnectVotePhase.WAIT, seat = "p2")
    val replaced = vote(DisconnectVotePhase.REPLACED, seat = "p1")
    val corrupt = "not a flow"
    val raw = mapOf("p1" to replaced.toFields(), "p4" to corrupt)

    val afterHold = DisconnectVoteState.withEntry(raw, waiting)
    assertEquals(replaced.toFields(), afterHold["p1"])
    assertEquals(corrupt, afterHold["p4"])
    assertEquals(waiting.toFields(), afterHold["p2"])

    val afterRestore = DisconnectVoteState.withoutSeat(afterHold, "p2")
    assertEquals(replaced.toFields(), afterRestore["p1"])
    assertEquals(corrupt, afterRestore["p4"])
    assertFalse("the restored seat's entry is gone", afterRestore.containsKey("p2"))

    assertEquals(
      "removing an absent seat is a no-op on the map",
      afterRestore,
      DisconnectVoteState.withoutSeat(afterRestore, "p3"),
    )
  }

  // ── THE policy point (flagged for owner confirmation) ────────────────

  @Test
  fun theReplacementInstantIsOneFunctionAndTheNarrowestReading() {
    // The documents fix the charge table and "reclaimable while the table
    // waits", but not the exact transition instant. The reading implemented
    // (and flagged for owner confirmation): the seat stays reclaimable through
    // the whole 30 s ballot and through any granted 90 s pause, and the
    // replacement fires only when that waiting ends with no reconnect — at
    // the ballot's close for CONTINUE/NO_AGREEMENT, at the pause's bound for
    // WAIT. Every site derives from this one function.
    val closed = 500_000L

    assertEquals(
      "a CONTINUE verdict ends the waiting at the ballot's close",
      closed,
      DisconnectVoteState.replacementAtMillis(closed, DisconnectVoteStatus.CONTINUE),
    )
    assertEquals(
      "no agreement ends the waiting at the ballot's close too",
      closed,
      DisconnectVoteState.replacementAtMillis(closed, DisconnectVoteStatus.NO_AGREEMENT),
    )
    assertEquals(
      "a WAIT verdict grants the ruled pause before permanence",
      closed + DisconnectVote.waitWindowSec * 1_000L,
      DisconnectVoteState.replacementAtMillis(closed, DisconnectVoteStatus.WAIT),
    )
    assertEquals(
      "the pause is exactly the ruled 90 seconds",
      closed + 90_000L,
      DisconnectVoteState.replacementAtMillis(closed, DisconnectVoteStatus.WAIT),
    )
  }
}
