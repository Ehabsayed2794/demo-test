package com.estemshan.services

import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.services.model.DEFAULT_MAX_ROUNDS
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.RoomDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S66 (E7) — Game Type & Calculation Mode reach :services. Pure unit tests:
 * the writers emit the type-derived ceiling + both mode fields, and every
 * parser treats an absent (or unrecognised) value as FULL/NORMAL so docs
 * written before S66 parse with no migration. Nothing here touches
 * Firestore or firestore.rules (the MINI create/extend instrumented paths
 * are S67's — the current rules deny them, see the S66 report).
 */
class GameTypeServicesTest {

  private val sentinel = object {}

  // ── writers ──────────────────────────────────────────────────────────

  @Test
  fun initialMatchFieldsFullWrites18AndBothModes() {
    val fields = buildInitialMatchFields(
      roomId = "ROOM01",
      players = listOf("u1", "u2"),
      creator = "u1",
      serverTimestamp = sentinel,
    )
    assertEquals(18, fields["maxRounds"])
    assertEquals("FULL", fields["gameType"])
    assertEquals("NORMAL", fields["scoringMode"])
  }

  @Test
  fun initialMatchFieldsMiniWrites10AndBothModes() {
    val fields = buildInitialMatchFields(
      roomId = "ROOM01",
      players = listOf("u1", "u2"),
      creator = "u1",
      serverTimestamp = sentinel,
      gameType = GameType.MINI,
      scoringMode = ScoringMode.CLASSIC,
    )
    assertEquals(10, fields["maxRounds"])
    assertEquals("MINI", fields["gameType"])
    assertEquals("CLASSIC", fields["scoringMode"])
  }

  @Test
  fun defaultMaxRoundsStays18AsFullsConstant() {
    assertEquals(18, DEFAULT_MAX_ROUNDS)
    assertEquals(18, GameType.FULL.baseRounds)
  }

  @Test
  fun rematchFieldsInheritTheOldMatchType() {
    val seats = mapOf("p1" to "u1", "p2" to "u2")
    val fields = buildRematchMatchFields(
      roomId = "ROOM01",
      seats = seats,
      oldDealerFallback = "u1",
      serverTimestamp = sentinel,
      rematchOfMatchId = "m0",
      gameType = GameType.MINI,
      scoringMode = ScoringMode.CLASSIC,
    )
    assertEquals(10, fields["maxRounds"])
    assertEquals("MINI", fields["gameType"])
    assertEquals("CLASSIC", fields["scoringMode"])
    assertEquals("m0", fields["rematchOfMatchId"])
  }

  @Test
  fun rematchFieldsDefaultToFull() {
    val seats = mapOf("p1" to "u1", "p2" to "u2")
    val fields = buildRematchMatchFields(
      roomId = "ROOM01",
      seats = seats,
      oldDealerFallback = "u1",
      serverTimestamp = sentinel,
      rematchOfMatchId = "m0",
    )
    assertEquals(18, fields["maxRounds"])
    assertEquals("FULL", fields["gameType"])
    assertEquals("NORMAL", fields["scoringMode"])
  }

  // ── parsers: absent ⇒ FULL/NORMAL ────────────────────────────────────

  private fun matchMap(
    gameType: String? = null,
    scoringMode: String? = null,
    maxRounds: Long = 18L,
  ): Map<String, Any?> = buildMap {
    put("roomId", "ROOM01")
    put("players", listOf("u1", "u2"))
    put("dealer", "u1")
    put("currentRound", 1L)
    put("maxRounds", maxRounds)
    if (gameType != null) put("gameType", gameType)
    if (scoringMode != null) put("scoringMode", scoringMode)
  }

  @Test
  fun matchFromFieldsAbsentMeansFullNormal() {
    val doc = MatchDoc.fromFields(matchMap())!!
    assertEquals(GameType.FULL, doc.gameType)
    assertEquals(ScoringMode.NORMAL, doc.scoringMode)
    assertEquals(18, doc.maxRounds)
  }

  @Test
  fun matchFromFieldsParsesMiniClassic() {
    val doc = MatchDoc.fromFields(
      matchMap(gameType = "MINI", scoringMode = "CLASSIC", maxRounds = 10L),
    )!!
    assertEquals(GameType.MINI, doc.gameType)
    assertEquals(ScoringMode.CLASSIC, doc.scoringMode)
    assertEquals(10, doc.maxRounds)
  }

  @Test
  fun matchFromFieldsUnknownFallsBackToFullNormal() {
    val doc = MatchDoc.fromFields(matchMap(gameType = "MEGA", scoringMode = "WEIRD"))!!
    assertEquals(GameType.FULL, doc.gameType)
    assertEquals(ScoringMode.NORMAL, doc.scoringMode)
  }

  @Test
  fun roomFromFieldsAbsentMeansFullNormal() {
    val doc = RoomDoc.fromFields(
      mapOf("creator" to "u1", "players" to listOf("u1")),
    )!!
    assertEquals(GameType.FULL, doc.gameType)
    assertEquals(ScoringMode.NORMAL, doc.scoringMode)
  }

  @Test
  fun roomFromFieldsParsesMiniClassic() {
    val doc = RoomDoc.fromFields(
      mapOf(
        "creator" to "u1",
        "players" to listOf("u1"),
        "gameType" to "MINI",
        "scoringMode" to "CLASSIC",
      ),
    )!!
    assertEquals(GameType.MINI, doc.gameType)
    assertEquals(ScoringMode.CLASSIC, doc.scoringMode)
  }

  // ── rapid window ─────────────────────────────────────────────────────

  @Test
  fun rapidWindowIsTypeDerived() {
    for (round in 14..18) assertTrue("FULL $round", isRapidRound(round))
    assertFalse(isRapidRound(13))
    assertFalse(isRapidRound(19))
    for (round in 6..10) assertTrue("MINI $round", isRapidRound(round, GameType.MINI))
    assertFalse(isRapidRound(5, GameType.MINI))
    assertFalse(isRapidRound(11, GameType.MINI))
    // The default is FULL.
    assertTrue(isRapidRound(14))
    assertFalse(isRapidRound(6))
  }
}
