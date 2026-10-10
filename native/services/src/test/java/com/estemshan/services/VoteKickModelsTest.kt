package com.estemshan.services

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

  // ── MatchDoc seat-control defaults (backward compatible) ──────────

  @Test
  fun absentSeatControlParsesEmpty() {
    val doc = MatchDoc.fromFields(matchFields())!!
    assertEquals(emptyList<String>(), doc.botSeats)
    assertEquals(emptyList<String>(), doc.removedUids)
    assertNull(doc.failedVoteKickCooldown)
    assertFalse(doc.isRemoved("u4"))
    assertTrue(doc.isSeated("u4"))
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
