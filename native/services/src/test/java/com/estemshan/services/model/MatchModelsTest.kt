package com.estemshan.services.model

import com.estemshan.engine.DisconnectVotePhase
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.DisconnectVoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 (A7.3 / A8.2) — what the CLIENT-side model reads off the match document
 * the 7th takeover wrote: its own removal list and charge map as a third
 * permanent-removal provenance beside Vote Kick's and the 15-timeout's, and
 * the CANCELLED ballot phase that must never read as a live flow.
 *
 * The client consumes these through the READ-SIDE UNIONS: [MatchDoc.isRemoved]
 * gates every write path (a takeover-removed uid can no longer play, vote or
 * rejoin), and [MatchDoc.botControlledSeats] paces the seat. A reviewer's
 * eye on those two unions is what this file exists to hold.
 */
class MatchModelsTest {

  private val seats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")

  /** The minimum a MatchDoc needs to parse, plus S7's fields when asked for. */
  private fun fields(
    takeoverRemovedUids: List<String>? = null,
    takeoverRemovedCharges: Map<String, Any?>? = null,
    disconnectVote: Map<String, Any?>? = null,
  ): Map<String, Any?> = mutableMapOf<String, Any?>(
    "roomId" to "room-1",
    "players" to listOf("u1", "u2", "u3", "u4"),
    "status" to "starting",
    "dealer" to "u1",
    "seats" to seats,
  ).apply {
    if (takeoverRemovedUids != null) put("takeoverRemovedUids", takeoverRemovedUids)
    if (takeoverRemovedCharges != null) put("takeoverRemovedCharges", takeoverRemovedCharges)
    if (disconnectVote != null) put("disconnectVote", disconnectVote)
  }

  private fun voteEntry(
    seat: String,
    phase: DisconnectVotePhase,
    deadline: Long? = null,
    answered: Int = 0,
    verdict: DisconnectVoteStatus? = null,
  ): Map<String, Any?> = DisconnectVoteState(
    seatId = seat,
    phase = phase,
    detectedAtMillis = 1_000L,
    deadlineMillis = deadline,
    answeredCount = answered,
    verdict = verdict,
  ).toFields()

  @Test
  fun aTakeoverRemovalParsesAndIsRecognizedByEveryGate() {
    val doc = MatchDoc.fromFields(
      fields(
        takeoverRemovedUids = listOf("u3"),
        takeoverRemovedCharges = mapOf("u3" to 2L),
      ),
    )!!

    assertEquals(listOf("u3"), doc.takeoverRemovedUids)
    assertEquals(mapOf("u3" to 2), doc.takeoverRemovedCharges)
    // The read-side unions the client actually gates on:
    assertTrue("a takeover-removed uid is removed (A7.3)", doc.isRemoved("u3"))
    assertFalse("and therefore no longer seated", doc.isSeated("u3"))
    assertEquals(setOf("p3"), doc.botControlledSeats())
    // The other mechanisms' lists are untouched by the union — recognition
    // is read-side only, never merged persisted state.
    assertTrue(doc.isRemoved("u9").not())
  }

  @Test
  fun anOldDocumentWithoutS7FieldsReadsAsNobodyTakeoverRemoved() {
    val doc = MatchDoc.fromFields(fields())!!

    assertEquals(emptyList<String>(), doc.takeoverRemovedUids)
    assertEquals(emptyMap<String, Int>(), doc.takeoverRemovedCharges)
    assertFalse(doc.isRemoved("u1"))
    assertTrue(doc.botControlledSeats().isEmpty())
  }

  @Test
  fun aGarbageChargeMapParsesFailClosed() {
    val doc = MatchDoc.fromFields(
      fields(
        takeoverRemovedUids = listOf("u3"),
        takeoverRemovedCharges = mapOf("u3" to "two", "u4" to 1L),
      ),
    )!!

    // Malformed entries drop, well-formed ones survive — a dropped entry
    // means "not recorded", never a guessed amount (S5's convention).
    assertEquals(mapOf("u4" to 1), doc.takeoverRemovedCharges)
  }

  @Test
  fun aCancelledEntryIsHeldByNobodyAndReclaimableByNobody() {
    val doc = MatchDoc.fromFields(
      fields(
        disconnectVote = mapOf(
          "p1" to voteEntry("p1", DisconnectVotePhase.CANCELLED),
          "p2" to voteEntry(
            "p2", DisconnectVotePhase.VOTE,
            deadline = 31_000L, answered = 1,
          ),
          "p3" to voteEntry(
            "p3", DisconnectVotePhase.REPLACED,
            deadline = 31_000L, answered = 2,
            verdict = DisconnectVoteStatus.NO_AGREEMENT,
          ),
        ),
      ),
    )!!

    // A8.2 / Q36: CANCELLED is a dead flow — not held (no bot pace borrowed
    // through the marker), not reclaimable (the seat was removed outright).
    assertEquals(setOf("p2", "p3"), doc.disconnectHeldSeats())
    assertEquals(setOf("p2"), doc.disconnectReclaimableSeats())
  }
}
