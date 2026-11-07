package com.estemshan.services

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.ScoringMode
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.RoomDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S70 (RD29/RD30) — what the client is allowed to learn from a Rank-down join.
 *
 * [RankDownJoinResponse] is the one place where the `joinRankDownRoom`
 * callable's answer becomes a client-facing shape, so it is where two contract
 * halves get pinned together: the denial vocabulary the server may return, and
 * RD29's privacy rule ("the client never reads another player's rank"). The
 * second half is why this file asserts on EVERY mapped message rather than only
 * the happy path — a tier name that leaks into a transport-error string is the
 * same leak as one in a payload, and no rule on the server prevents it.
 *
 * The wire vocabulary (`joined`, `room`, `reason`, `message`, the six server
 * reason tokens) is repeated here as literals because `:services` cannot see
 * `:functions` — the same unavoidable duplication [SeatReclaimResponseTest]
 * documents. Drift between the two copies is caught by
 * [com.estemshan.services.LeaveMatchEmulatorTest]-class instrumented runs and by
 * `PartyJoinEmulatorTest` in `:functions`, not by this file.
 *
 * Deliberately NOT here: the transport itself ([CallableRankDownJoin]'s two
 * catch arms, which need a real `FirebaseFunctionsException` the SDK cannot
 * build on a plain JVM), the room-write behaviour, and every eligibility
 * decision — those live behind the callable and are pinned in `:functions`.
 */
class RankDownJoinResponseTest {

  private companion object {

    /**
     * The ladder's tier words, hardcoded rather than imported so a regression in
     * the ladder table cannot silently disarm the leak check.
     */
    val TIERS = listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Royal", "King")

    /**
     * `FirebaseFunctionsException.Code`, spelled as NAMES — the enum's static
     * initializer needs the Android runtime, so this file (like
     * [SeatReclaimResponseTest]) exercises the name-taking half and the device
     * exercises the enum.
     */
    val ALL_SDK_CODE_NAMES = listOf(
      "CANCELLED",
      "UNKNOWN",
      "INVALID_ARGUMENT",
      "DEADLINE_EXCEEDED",
      "NOT_FOUND",
      "ALREADY_EXISTS",
      "PERMISSION_DENIED",
      "RESOURCE_EXHAUSTED",
      "FAILED_PRECONDITION",
      "ABORTED",
      "OUT_OF_RANGE",
      "UNIMPLEMENTED",
      "INTERNAL",
      "UNAVAILABLE",
      "DATA_LOSS",
      "UNAUTHENTICATED",
    )

    /** A permitted join exactly as the callable projects it. */
    fun joinedPayload(): Map<String, Any?> = linkedMapOf(
      "joined" to true,
      "room" to linkedMapOf(
        "roomId" to "AB12CD",
        "name" to "Thursday table",
        "status" to RoomDoc.STATUS_WAITING,
        "creator" to "uid-creator",
        "players" to listOf("uid-creator", "uid-joiner"),
        "readyPlayers" to listOf("uid-creator"),
        "mode" to MatchMode.RANKED.name,
        "rankDown" to true,
        "gameType" to GameType.FULL.name,
        "scoringMode" to ScoringMode.NORMAL.name,
        "matchId" to null,
      ),
    )

    fun deniedPayload(reason: Any?, message: Any?): Map<String, Any?> = linkedMapOf(
      "joined" to false,
      "reason" to reason,
      "message" to message,
    )
  }

  /**
   * Rank-free by construction: no tier word, no division roman numeral, no bare
   * number that could be an RP, no "rp" word, no uid.
   */
  private fun assertRankFree(message: String, context: String) {
    assertTrue("$context: a denial must carry a message", message.isNotBlank())
    val lower = message.lowercase()
    for (tier in TIERS) {
      assertFalse("$context: message names tier '$tier' -> $message", lower.contains(tier.lowercase()))
    }
    assertFalse("$context: message carries a number -> $message", message.any { it.isDigit() })
    assertFalse(
      "$context: message mentions RP -> $message",
      Regex("\\brp\\b", RegexOption.IGNORE_CASE).containsMatchIn(message),
    )
    assertFalse(
      "$context: message carries a division numeral -> $message",
      Regex("\\b(III|II|I)\\b").containsMatchIn(message),
    )
    assertFalse("$context: message carries a uid -> $message", lower.contains("uid"))
  }

  private fun deniedOf(result: RankDownJoin): RankDownJoin.Denied {
    assertTrue("expected a denial, got $result", result is RankDownJoin.Denied)
    return result as RankDownJoin.Denied
  }

  // ── the permitted join ───────────────────────────────────────────────────

  @Test
  fun aPermittedJoinParsesTheProjectedRoomIntoARoomDoc() {
    val result = RankDownJoinResponse.forData(joinedPayload())

    assertTrue("expected Joined, got $result", result is RankDownJoin.Joined)
    val room = (result as RankDownJoin.Joined).room
    assertEquals("Thursday table", room.name)
    assertEquals(RoomDoc.STATUS_WAITING, room.status)
    assertEquals("uid-creator", room.creator)
    assertEquals(listOf("uid-creator", "uid-joiner"), room.players)
    assertEquals(listOf("uid-creator"), room.readyPlayers)
    assertEquals(MatchMode.RANKED, room.mode)
    assertTrue(room.rankDown)
    assertEquals(GameType.FULL, room.gameType)
    assertEquals(ScoringMode.NORMAL, room.scoringMode)
    assertFalse("the joiner is not yet ready", room.isReady("uid-joiner"))
    assertTrue(room.isMember("uid-joiner"))
  }

  @Test
  fun aJoinedPayloadWithNoRoomOrANonMapRoomDeniesRatherThanInventingAJoin() {
    val noRoom = deniedOf(RankDownJoinResponse.forData(linkedMapOf("joined" to true)))
    assertEquals(Reasons.RANK_DOWN_UNAVAILABLE, noRoom.reason)
    assertRankFree(noRoom.message, "room absent")

    val roomIsString = deniedOf(
      RankDownJoinResponse.forData(linkedMapOf("joined" to true, "room" to "AB12CD")),
    )
    assertEquals(Reasons.RANK_DOWN_UNAVAILABLE, roomIsString.reason)
    assertRankFree(roomIsString.message, "room not a map")

    // A room map that RoomDoc cannot parse (no creator) is the same refusal.
    val creatorless = deniedOf(
      RankDownJoinResponse.forData(
        linkedMapOf("joined" to true, "room" to linkedMapOf("status" to RoomDoc.STATUS_WAITING)),
      ),
    )
    assertEquals(Reasons.RANK_DOWN_UNAVAILABLE, creatorless.reason)
    assertRankFree(creatorless.message, "room unparseable")
  }

  @Test
  fun aPayloadThatIsNotAMapOrNeverAnswersJoinedIsAMalformedJoin() {
    for (data in listOf(null, "AB12CD", 7, linkedMapOf("room" to joinedPayload()["room"]))) {
      val denied = deniedOf(RankDownJoinResponse.forData(data))
      assertEquals(Reasons.RANK_DOWN_UNAVAILABLE, denied.reason)
      assertRankFree(denied.message, "joined key absent for $data")
    }
  }

  // ── the two eligibility denials, verbatim ────────────────────────────────

  @Test
  fun theTwoEligibilityDenialsPassThroughWithTheirServerSentence() {
    for ((reason, sentence) in listOf(
      Reasons.RANK_INCOMPATIBLE to
        "This player's rank is too far from the host's for a Ranked party.",
      Reasons.PROFILE_UNAVAILABLE to
        "Player ranks aren't available right now. Please try again.",
    )) {
      val denied = deniedOf(RankDownJoinResponse.forData(deniedPayload(reason, sentence)))

      assertEquals(reason, denied.reason)
      assertEquals("the server's own plain-language sentence is surfaced unchanged", sentence, denied.message)
    }
  }

  @Test
  fun anUnknownMissingOrBlankDenialReasonBecomesTheVersionSkewDenial() {
    val skewSentence = "Some future server sentence naming nothing the client may learn."
    for (reason in listOf(null, "", "   ", "ROOM_LOCKED", "rank_incompatible", "NOT_RANK_DOWN")) {
      val denied = deniedOf(RankDownJoinResponse.forData(deniedPayload(reason, skewSentence)))

      assertEquals("reason '$reason' is version skew", Reasons.RANK_DOWN_UNAVAILABLE, denied.reason)
      assertRankFree(denied.message, "unknown reason '$reason'")
      assertFalse(
        "a server sentence this build does not know is never surfaced",
        denied.message.contains(skewSentence),
      )
    }
  }

  @Test
  fun anEligibilityReasonWithABlankMessageFailsClosedToTheSkewDenial() {
    for (reason in listOf(Reasons.RANK_INCOMPATIBLE, Reasons.PROFILE_UNAVAILABLE)) {
      for (message in listOf(null, "", "   ")) {
        val denied = deniedOf(RankDownJoinResponse.forData(deniedPayload(reason, message)))

        assertEquals("$reason with a blank message is malformed", Reasons.RANK_DOWN_UNAVAILABLE, denied.reason)
        assertRankFree(denied.message, "blank message for $reason")
      }
    }
  }

  // ── the exception half: reason first, then code ─────────────────────────

  @Test
  fun everyServerReasonMapsToItsStableClientReason() {
    val cases = listOf(
      "ROOM_NOT_FOUND" to Reasons.ROOM_NOT_FOUND,
      "ROOM_CLOSED" to Reasons.ROOM_CLOSED,
      "ROOM_FULL" to Reasons.ROOM_FULL,
      "NOT_RANK_DOWN" to Reasons.RANK_DOWN_UNAVAILABLE,
      "MALFORMED_RESPONSE" to Reasons.RANK_DOWN_UNAVAILABLE,
      "UNAVAILABLE" to Reasons.UNAVAILABLE,
      "DEADLINE_EXCEEDED" to Reasons.UNAVAILABLE,
      "INTERNAL" to Reasons.UNAVAILABLE,
      // RANK_INCOMPATIBLE is never an HttpsError; if a future server ever sends
      // one, the client must not invent an eligibility denial it did not read.
      "RANK_INCOMPATIBLE" to Reasons.RANK_DOWN_UNAVAILABLE,
      "PROFILE_UNAVAILABLE" to Reasons.RANK_DOWN_UNAVAILABLE,
      // Not in the reason table at all: falls to the by-code table below, and
      // FAILED_PRECONDITION is the RANK_DOWN_UNAVAILABLE direction.
      "FORBIDDEN_KEY" to Reasons.RANK_DOWN_UNAVAILABLE,
    )
    for ((serverReason, expected) in cases) {
      val denied = deniedOf(
        RankDownJoinResponse.forException(
          "FAILED_PRECONDITION",
          linkedMapOf("reason" to serverReason),
        ),
      )
      assertEquals("reason '$serverReason'", expected, denied.reason)
      assertRankFree(denied.message, "reason '$serverReason'")
    }
  }

  @Test
  fun anUnknownOrMissingReasonMapsByTransportCode() {
    val cases = listOf(
      "NOT_FOUND" to Reasons.ROOM_NOT_FOUND,
      "FAILED_PRECONDITION" to Reasons.RANK_DOWN_UNAVAILABLE,
      "RESOURCE_EXHAUSTED" to Reasons.ROOM_FULL,
      "INVALID_ARGUMENT" to Reasons.INVALID_ARGUMENT,
      "UNAUTHENTICATED" to Reasons.UNAUTHENTICATED,
      "PERMISSION_DENIED" to Reasons.UNAUTHENTICATED,
    )
    for ((code, expected) in cases) {
      for (details in listOf<Any?>(null, "not a map", linkedMapOf<String, Any?>(), linkedMapOf("reason" to 7))) {
        val denied = deniedOf(RankDownJoinResponse.forException(code, details))

        assertEquals("code '$code' with details '$details'", expected, denied.reason)
        assertRankFree(denied.message, "code '$code'")
      }
    }
  }

  @Test
  fun everyCodeTheSdkCanHandBackDeniesRankFreeAndNeverCrashes() {
    for (code in ALL_SDK_CODE_NAMES) {
      val denied = deniedOf(RankDownJoinResponse.forException(code, null))

      assertTrue(
        "code '$code' must land on a known client reason, got '${denied.reason}'",
        denied.reason in KNOWN_CLIENT_REASONS,
      )
      assertRankFree(denied.message, "code '$code'")
    }
  }

  /** Every reason this mapping is allowed to produce — an invented one is a bug. */
  private val KNOWN_CLIENT_REASONS = setOf(
    Reasons.ROOM_NOT_FOUND,
    Reasons.ROOM_CLOSED,
    Reasons.ROOM_FULL,
    Reasons.RANK_DOWN_UNAVAILABLE,
    Reasons.UNAVAILABLE,
    Reasons.INVALID_ARGUMENT,
    Reasons.UNAUTHENTICATED,
    Reasons.RANK_INCOMPATIBLE,
    Reasons.PROFILE_UNAVAILABLE,
  )

  @Test
  fun theTransientCodesAreTheOnlyOnesWhoseSentenceInvitesARetry() {
    // UNAVAILABLE/DEADLINE_EXCEEDED/INTERNAL are "the function never answered",
    // so their sentence invites a retry; a room that is gone or full does not.
    for (code in listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL")) {
      val denied = deniedOf(RankDownJoinResponse.forException(code, null))
      assertEquals(Reasons.UNAVAILABLE, denied.reason)
      assertTrue("'$code' is retryable: ${denied.message}", denied.message.lowercase().contains("try again"))
    }
    for (code in listOf("NOT_FOUND", "RESOURCE_EXHAUSTED")) {
      val denied = deniedOf(RankDownJoinResponse.forException(code, null))
      assertFalse(
        "'$code' is terminal, its sentence must not invite a retry: ${denied.message}",
        denied.message.lowercase().contains("try again"),
      )
    }
  }

  @Test
  fun theJoinedPathNeverProducesADenialForAFullValidProjection() {
    // The mirror of the checks above: a correct payload must not be read as skew.
    val result = RankDownJoinResponse.forData(joinedPayload())
    assertFalse(result is RankDownJoin.Denied)
  }
}
