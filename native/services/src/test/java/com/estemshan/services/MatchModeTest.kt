package com.estemshan.services

import com.estemshan.engine.GameSession
import com.estemshan.engine.MatchMode
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.RoomDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S43 (E6b, RD28 final) — the mode authority key ROOM | RANKED |
 * UNRANKED, plus the room's private-access rankDown flag. Pure unit
 * tests: parsers treat an absent (or bogus) value as ROOM so old docs
 * parse with no migration; writers emit the enum names; the flag is
 * independent of mode; the engine holder round-trips. Nothing here
 * touches Firestore or firestore.rules — the allowlist half (accept all
 * three, deny a bogus fourth, rankDown must be bool) is proven by
 * tests/native-e43-mode-rules.test.cjs against the real emulator.
 */
class MatchModeTest {

  private val sentinel = object {}

  // ── parsers: absent ⇒ ROOM / false ─────────────────────────────────

  @Test
  fun roomAbsentParsesRoomAndNotRankDown() {
    val doc = RoomDoc.fromFields(
      mapOf("creator" to "u1", "players" to listOf("u1")),
    )!!
    assertEquals(MatchMode.ROOM, doc.mode)
    assertFalse(doc.rankDown)
  }

  @Test
  fun matchAbsentParsesRoom() {
    val doc = MatchDoc.fromFields(
      mapOf("roomId" to "ROOM01", "players" to listOf("u1"), "dealer" to "u1"),
    )!!
    assertEquals(MatchMode.ROOM, doc.mode)
  }

  @Test
  fun allThreeValuesRoundTripOnBothDocs() {
    for (mode in MatchMode.entries) {
      val room = RoomDoc.fromFields(
        mapOf("creator" to "u1", "players" to listOf("u1"), "mode" to mode.name),
      )!!
      assertEquals("room $mode", mode, room.mode)
      val match = MatchDoc.fromFields(
        mapOf(
          "roomId" to "ROOM01", "players" to listOf("u1"), "dealer" to "u1",
          "mode" to mode.name,
        ),
      )!!
      assertEquals("match $mode", mode, match.mode)
    }
  }

  @Test
  fun bogusFourthParsesRoomOnBothDocs() {
    val room = RoomDoc.fromFields(
      mapOf("creator" to "u1", "players" to listOf("u1"), "mode" to "MEGA"),
    )!!
    assertEquals(MatchMode.ROOM, room.mode)
    val match = MatchDoc.fromFields(
      mapOf(
        "roomId" to "ROOM01", "players" to listOf("u1"), "dealer" to "u1",
        "mode" to "MEGA",
      ),
    )!!
    assertEquals(MatchMode.ROOM, match.mode)
  }

  @Test
  fun rankDownIsIndependentOfMode() {
    for (mode in MatchMode.entries) {
      for (flag in listOf(true, false)) {
        val doc = RoomDoc.fromFields(
          mapOf(
            "creator" to "u1", "players" to listOf("u1"),
            "mode" to mode.name, "rankDown" to flag,
          ),
        )!!
        assertEquals("mode survives rankDown=$flag", mode, doc.mode)
        assertEquals("rankDown survives mode=$mode", flag, doc.rankDown)
      }
    }
  }

  // ── writers ────────────────────────────────────────────────────────

  @Test
  fun initialMatchFieldsDefaultToRoom() {
    val fields = buildInitialMatchFields(
      roomId = "ROOM01",
      players = listOf("u1", "u2"),
      creator = "u1",
      serverTimestamp = sentinel,
    )
    assertEquals("ROOM", fields["mode"])
  }

  @Test
  fun initialMatchFieldsWriteEachMode() {
    for (mode in MatchMode.entries) {
      val fields = buildInitialMatchFields(
        roomId = "ROOM01",
        players = listOf("u1", "u2"),
        creator = "u1",
        serverTimestamp = sentinel,
        mode = mode,
      )
      assertEquals(mode.name, fields["mode"])
    }
  }

  @Test
  fun rematchFieldsInheritTheOldMatchMode() {
    val seats = mapOf("p1" to "u1", "p2" to "u2")
    for (mode in MatchMode.entries) {
      val fields = buildRematchMatchFields(
        roomId = "ROOM01",
        seats = seats,
        oldDealerFallback = "u1",
        serverTimestamp = sentinel,
        rematchOfMatchId = "m0",
        mode = mode,
      )
      assertEquals("rematch inherits $mode", mode.name, fields["mode"])
    }
  }

  // ── engine authority holder ────────────────────────────────────────

  @Test
  fun freshSessionHoldsRoomAndRoundTrips() {
    val session = GameSession()
    assertEquals(MatchMode.ROOM, session.getMatchMode())
    session.setMatchMode(MatchMode.RANKED)
    assertEquals(MatchMode.RANKED, session.getMatchMode())
    assertEquals(MatchMode.RANKED, session.snapshot().matchMode)
    session.setMatchMode(MatchMode.UNRANKED)
    assertEquals(MatchMode.UNRANKED, session.snapshot().matchMode)
  }

  @Test
  fun legacyModeLabelIsUntouchedByTheAuthority() {
    val session = GameSession()
    session.init("ranked", force = true)
    assertEquals("ranked", session.getMode())
    assertEquals("authority still defaults ROOM", MatchMode.ROOM, session.getMatchMode())
  }
}
