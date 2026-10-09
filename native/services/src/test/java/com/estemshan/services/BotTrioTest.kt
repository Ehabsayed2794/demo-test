package com.estemshan.services

import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.services.model.DEFAULT_DECISION_TIMER_SECONDS
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.RoomDoc
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RD21 — the bot trio persistence: botTier (EASY/MEDIUM/HARD/EXPERT),
 * botPersonality (BALANCED/AGGRESSIVE/CONSERVATIVE/TRICKSTER), and
 * decisionTimerSeconds (5..20). Pure unit tests: parsers treat an absent (or
 * unrecognised) value as the RD21 default (MEDIUM/BALANCED/15s) so old docs
 * parse with no migration; writers emit the enum names; the three axes are
 * independent; the match builders carry the trio. Nothing here touches
 * Firestore or firestore.rules — the allowlist half (accept the trio, deny a
 * bogus tier/personality or an out-of-range timer, and the rematch
 * inheritance checks) is proven by tests/native-rd21-trio-rules.test.cjs
 * against the real emulator.
 */
class BotTrioTest {

  private val sentinel = object {}

  // ── parsers: absent ⇒ MEDIUM / BALANCED / 15s ──────────────────────

  @Test
  fun roomAbsentParsesTheRD21Defaults() {
    val doc = RoomDoc.fromFields(
      mapOf("creator" to "u1", "players" to listOf("u1")),
    )!!
    assertEquals(BotTier.MEDIUM, doc.botTier)
    assertEquals(BotPersonality.BALANCED, doc.botPersonality)
    assertEquals(DEFAULT_DECISION_TIMER_SECONDS, doc.decisionTimerSeconds)
  }

  @Test
  fun matchAbsentParsesTheRD21Defaults() {
    val doc = MatchDoc.fromFields(
      mapOf("roomId" to "ROOM01", "players" to listOf("u1"), "dealer" to "u1"),
    )!!
    assertEquals(BotTier.MEDIUM, doc.botTier)
    assertEquals(BotPersonality.BALANCED, doc.botPersonality)
    assertEquals(DEFAULT_DECISION_TIMER_SECONDS, doc.decisionTimerSeconds)
  }

  @Test
  fun everyTierAndPersonalityRoundTripsOnBothDocs() {
    for (tier in BotTier.entries) {
      for (personality in BotPersonality.entries) {
        val room = RoomDoc.fromFields(
          mapOf(
            "creator" to "u1", "players" to listOf("u1"),
            "botTier" to tier.name, "botPersonality" to personality.name,
          ),
        )!!
        assertEquals("room $tier", tier, room.botTier)
        assertEquals("room $personality", personality, room.botPersonality)
        val match = MatchDoc.fromFields(
          mapOf(
            "roomId" to "ROOM01", "players" to listOf("u1"), "dealer" to "u1",
            "botTier" to tier.name, "botPersonality" to personality.name,
          ),
        )!!
        assertEquals("match $tier", tier, match.botTier)
        assertEquals("match $personality", personality, match.botPersonality)
      }
    }
  }

  @Test
  fun everyTimerChoiceRoundTripsOnBothDocs() {
    for (seconds in listOf(5, 10, 15, 20)) {
      val room = RoomDoc.fromFields(
        mapOf("creator" to "u1", "players" to listOf("u1"), "decisionTimerSeconds" to seconds),
      )!!
      assertEquals("room ${seconds}s", seconds, room.decisionTimerSeconds)
      val match = MatchDoc.fromFields(
        mapOf(
          "roomId" to "ROOM01", "players" to listOf("u1"), "dealer" to "u1",
          "decisionTimerSeconds" to seconds,
        ),
      )!!
      assertEquals("match ${seconds}s", seconds, match.decisionTimerSeconds)
    }
  }

  @Test
  fun timerRoundTripsThroughTheFirestoreLongShape() {
    // Firestore stores every integer as a 64-bit Long; the parser must read
    // that shape so a timer createRoom writes reads back unchanged.
    val doc = MatchDoc.fromFields(
      mapOf(
        "roomId" to "ROOM01", "players" to listOf("u1"), "dealer" to "u1",
        "decisionTimerSeconds" to 20L,
      ),
    )!!
    assertEquals(20, doc.decisionTimerSeconds)
  }

  @Test
  fun bogusTierAndPersonalityParseAsDefaults() {
    val room = RoomDoc.fromFields(
      mapOf(
        "creator" to "u1", "players" to listOf("u1"),
        "botTier" to "NIGHTMARE", "botPersonality" to "CHAOTIC",
      ),
    )!!
    assertEquals(BotTier.MEDIUM, room.botTier)
    assertEquals(BotPersonality.BALANCED, room.botPersonality)
  }

  @Test
  fun outOfRangeTimerParsesAsTheDefault() {
    for (seconds in listOf(4, 21, 0, 100)) {
      val doc = RoomDoc.fromFields(
        mapOf("creator" to "u1", "players" to listOf("u1"), "decisionTimerSeconds" to seconds),
      )!!
      assertEquals("$seconds parses as the default", DEFAULT_DECISION_TIMER_SECONDS, doc.decisionTimerSeconds)
    }
  }

  @Test
  fun trioIsIndependentPerAxis() {
    val doc = RoomDoc.fromFields(
      mapOf(
        "creator" to "u1", "players" to listOf("u1"),
        "botTier" to "EXPERT", "botPersonality" to "TRICKSTER", "decisionTimerSeconds" to 20,
      ),
    )!!
    assertEquals(BotTier.EXPERT, doc.botTier)
    assertEquals(BotPersonality.TRICKSTER, doc.botPersonality)
    assertEquals(20, doc.decisionTimerSeconds)
  }

  // ── writers ────────────────────────────────────────────────────────

  @Test
  fun initialMatchFieldsDefaultTheTrio() {
    val fields = buildInitialMatchFields(
      roomId = "ROOM01",
      players = listOf("u1", "u2"),
      creator = "u1",
      serverTimestamp = sentinel,
    )
    assertEquals("MEDIUM", fields["botTier"])
    assertEquals("BALANCED", fields["botPersonality"])
    assertEquals(DEFAULT_DECISION_TIMER_SECONDS, fields["decisionTimerSeconds"])
  }

  @Test
  fun initialMatchFieldsWriteTheTrio() {
    val fields = buildInitialMatchFields(
      roomId = "ROOM01",
      players = listOf("u1", "u2"),
      creator = "u1",
      serverTimestamp = sentinel,
      botTier = BotTier.EXPERT,
      botPersonality = BotPersonality.TRICKSTER,
      decisionTimerSeconds = 20,
    )
    assertEquals("EXPERT", fields["botTier"])
    assertEquals("TRICKSTER", fields["botPersonality"])
    assertEquals(20, fields["decisionTimerSeconds"])
  }

  @Test
  fun rematchFieldsInheritTheTrio() {
    val seats = mapOf("p1" to "u1", "p2" to "u2")
    val fields = buildRematchMatchFields(
      roomId = "ROOM01",
      seats = seats,
      oldDealerFallback = "u1",
      serverTimestamp = sentinel,
      rematchOfMatchId = "m0",
      botTier = BotTier.HARD,
      botPersonality = BotPersonality.AGGRESSIVE,
      decisionTimerSeconds = 10,
    )
    assertEquals("HARD", fields["botTier"])
    assertEquals("AGGRESSIVE", fields["botPersonality"])
    assertEquals(10, fields["decisionTimerSeconds"])
  }
}
