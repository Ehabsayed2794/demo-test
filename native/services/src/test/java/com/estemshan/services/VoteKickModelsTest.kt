package com.estemshan.services

import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.eligibleVoteKickVoters
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.VoteKickCooldownData
import com.estemshan.services.model.VoteKickDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S60 — Vote Kick document model + the three seat-control fields on
 * [MatchDoc]. Pure parsing/round-trip tests (no Firestore): the
 * transaction behaviour is proven against the emulator, never here.
 */
class VoteKickModelsTest {

  private fun matchFields(vararg extra: Pair<String, Any?>): Map<String, Any?> {
    val base = linkedMapOf<String, Any?>(
      "roomId" to "ROOM01",
      "players" to listOf("u1", "u2", "u3", "u4"),
      "dealer" to "u1",
      "seats" to mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4"),
      "mode" to "RANKED",
    )
    for ((k, v) in extra) base[k] = v
    return base
  }

  /** The shape the JVM Firestore client hands back for a marker entry: whole numbers as Long. */
  private fun takeoverEntry(
    takenOverAt: Any? = 10_000L,
    round: Any? = 3L,
    trick: Any? = 2L,
  ): Map<String, Any?> = linkedMapOf(
    InactiveTakeover.TAKEN_OVER_AT_FIELD to takenOverAt,
    InactiveTakeover.ROUND_AT_TAKEOVER_FIELD to round,
    InactiveTakeover.TRICK_AT_TAKEOVER_FIELD to trick,
  )

  // ── MatchDoc seat-control defaults (backward compatible) ──────────

  @Test
  fun absentSeatControlParsesEmpty() {
    val doc = MatchDoc.fromFields(matchFields())!!
    assertEquals(emptyList<String>(), doc.botSeats)
    assertEquals(emptyList<String>(), doc.removedUids)
    assertNull(doc.failedVoteKickCooldown)
    assertFalse(doc.isRemoved("u4"))
    assertTrue(doc.isSeated("u4"))
    // S73: a doc written before the takeover field exists parses as "nobody is
    // borrowed" — no migration, and no field is invented for the old doc.
    assertTrue(doc.inactiveTakeovers.isEmpty())
    assertTrue(doc.inactiveTakeoverSeats().isEmpty())
  }

  @Test
  fun seatControlRoundTrips() {
    val doc = MatchDoc.fromFields(matchFields(
      "botSeats" to listOf("p4"),
      "removedUids" to listOf("u4"),
      "failedVoteKickCooldown" to mapOf("targetSeat" to "p4", "blockedUntilRound" to 13L),
    ))!!
    assertEquals(listOf("p4"), doc.botSeats)
    assertEquals(listOf("u4"), doc.removedUids)
    assertTrue(doc.isRemoved("u4"))
    assertFalse(doc.isSeated("u4"))
    assertTrue(doc.isSeated("u1"))
    assertEquals(VoteKickCooldownData("p4", 13), doc.failedVoteKickCooldown)
  }

  @Test
  fun malformedCooldownParsesNull() {
    val doc = MatchDoc.fromFields(matchFields(
      "failedVoteKickCooldown" to mapOf("targetSeat" to "p4"),
    ))!!
    assertNull(doc.failedVoteKickCooldown)
  }

  // ── S73 (RD19): the takeover's own provenance, separate by construction ──

  @Test
  fun inactiveTakeoverRoundTripsAndItsOwnerStaysSeated() {
    val doc = MatchDoc.fromFields(matchFields(
      "inactiveTakeovers" to mapOf("p2" to takeoverEntry()),
    ))!!
    assertEquals(
      mapOf("p2" to InactiveTakeover(takenOverAtMillis = 10_000L, roundAtTakeover = 3, trickAtTakeover = 2)),
      doc.inactiveTakeovers,
    )
    assertEquals(setOf("p2"), doc.inactiveTakeoverSeats())
    // DEC-RD19-04: borrowed, never lost. The owner stays seated and is not
    // removed by either removal mechanism.
    assertEquals("u2", doc.seatToUid("p2"))
    assertTrue(doc.isSeated("u2"))
    assertFalse(doc.isRemoved("u2"))
  }

  @Test
  fun aBorrowedSeatStaysOutOfBotControlledSeatsAndKeepsItsVoteSlot() {
    val doc = MatchDoc.fromFields(matchFields(
      "inactiveTakeovers" to mapOf("p2" to takeoverEntry()),
    ))!!
    // RD20's separation, pinned at the read side: folding a borrowed seat into
    // botControlledSeats() would strip its owner's Vote Kick slot and make them
    // unkickable, which is the opposite of what DEC-RD19-04 closes.
    assertTrue(doc.botControlledSeats().isEmpty())
    assertEquals(
      setOf("p1", "p2", "p4"),
      eligibleVoteKickVoters(doc.seats.keys, "p3", doc.botControlledSeats()),
    )
  }

  @Test
  fun timeoutRemovalAndInactivityTakeoverKeepSeparateProvenance() {
    val doc = MatchDoc.fromFields(matchFields(
      "botSeats" to listOf("p4"),
      "timeoutRemovedUids" to listOf("u3"),
      "inactiveTakeovers" to mapOf("p2" to takeoverEntry()),
    ))!!
    assertEquals(setOf("p4", "p3"), doc.botControlledSeats())
    assertEquals(setOf("p2"), doc.inactiveTakeoverSeats())
    assertEquals(listOf("u3"), doc.timeoutRemovedUids)
    assertEquals(emptyList<String>(), doc.removedUids)
    assertTrue(doc.isRemoved("u3"))
    assertFalse(doc.isRemoved("u2"))
  }

  @Test
  fun aMalformedTakeoverEntryParsesAsNoTakeover() {
    val doc = MatchDoc.fromFields(matchFields(
      "inactiveTakeovers" to mapOf("p2" to takeoverEntry(takenOverAt = "soon")),
    ))!!
    assertTrue(doc.inactiveTakeovers.isEmpty())
    assertTrue(doc.isSeated("u2"))
    // A garbage entry for one seat must not discard a well-formed entry for
    // another — one player's corrupt state is not everyone else's takeover.
    val mixed = MatchDoc.fromFields(matchFields(
      "inactiveTakeovers" to mapOf(
        "p2" to takeoverEntry(),
        "p3" to takeoverEntry(round = 0L),
      ),
    ))!!
    assertEquals(setOf("p2"), mixed.inactiveTakeoverSeats())
  }

  @Test
  fun aNonMapTakeoverFieldParsesAsNoTakeover() {
    val doc = MatchDoc.fromFields(matchFields(
      "inactiveTakeovers" to listOf("p2"),
    ))!!
    assertTrue(doc.inactiveTakeovers.isEmpty())
    assertTrue(doc.inactiveTakeoverSeats().isEmpty())
  }

  // ── VoteKickDoc ───────────────────────────────────────────────────

  private fun voteFields(): Map<String, Any?> = mapOf(
    "matchId" to "m1",
    "targetSeat" to "p4",
    "initiatorSeat" to "p1",
    "reason" to "griefing",
    "votes" to linkedMapOf("p1" to "YES", "p2" to null, "p3" to null),
    "status" to "OPEN",
    "roundOpened" to 8L,
    "version" to 3L,
  )

  @Test
  fun voteRoundTripsWithNullSlots() {
    val doc = VoteKickDoc.fromFields(voteFields())!!
    assertEquals("m1", doc.matchId)
    assertEquals("p4", doc.targetSeat)
    assertEquals("p1", doc.initiatorSeat)
    assertEquals("griefing", doc.reason)
    assertEquals(setOf("p1"), doc.votedSeats())
    assertEquals(1, doc.yesCount())
    assertEquals(0, doc.noCount())
    assertFalse(doc.isTerminal())
    assertEquals(8, doc.roundOpened)
    assertEquals(3, doc.version)
  }

  @Test
  fun bogusStatusFailsParse() {
    assertNull(VoteKickDoc.fromFields(voteFields() + ("status" to "MAYBE")))
  }

  @Test
  fun missingTargetFailsParse() {
    val fields = voteFields().toMutableMap()
    fields.remove("targetSeat")
    assertNull(VoteKickDoc.fromFields(fields))
  }

  @Test
  fun terminalStatusesParse() {
    for (status in listOf("PASSED", "FAILED_NO", "FAILED_TIMEOUT")) {
      val doc = VoteKickDoc.fromFields(voteFields() + ("status" to status))!!
      assertTrue(doc.isTerminal())
    }
  }

  @Test
  fun choiceMapping() {
    assertEquals(
      com.estemshan.engine.VoteKickChoice.YES,
      VoteKickDoc.choiceOf("YES"),
    )
    assertEquals(
      com.estemshan.engine.VoteKickChoice.NO,
      VoteKickDoc.choiceOf("NO"),
    )
    assertNull(VoteKickDoc.choiceOf(null))
    assertNull(VoteKickDoc.choiceOf("MAYBE"))
    assertEquals("YES", VoteKickDoc.choiceName(com.estemshan.engine.VoteKickChoice.YES))
  }

  @Test
  fun cooldownDataRoundTrips() {
    val data = VoteKickCooldownData.fromFields(
      mapOf("targetSeat" to "p2", "blockedUntilRound" to 14L),
    )!!
    assertEquals(VoteKickCooldownData("p2", 14), data)
    assertEquals(
      mapOf("targetSeat" to "p2", "blockedUntilRound" to 14),
      data.toFields(),
    )
  }
}
