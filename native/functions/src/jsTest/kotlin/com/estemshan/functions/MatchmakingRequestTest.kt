package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S71 — the mode-aware search-request guard (RD9 + RD28 as amended).
 *
 * The guard is the anti-abuse boundary in front of S56's pool derivation: the
 * client may say which GameType it wants to play and WHICH MODE it wants to
 * play, and it may say nothing else. These tests pin four things.
 *
 *  1. **The accepted shape** — two fields, `gameType` and `mode`, whose values
 *     are exactly `"FULL"`/`"MINI"` and `"RANKED"`/`"UNRANKED"`, in both
 *     shapes the boundary actually sees: a Kotlin map (what a test fixture
 *     builds) and a **plain JS object** (what the Functions runtime delivers —
 *     see `CallablePayloadBridgingTest`).
 *  2. **The refusal matrix** — a missing/null/non-object/array payload, a
 *     missing/null/malformed `gameType`, a missing/null/malformed `mode`, an
 *     unknown key, and each of the twelve forbidden identity/tier keys.
 *  3. **The mode rules** — `ROOM` is a real mode that this queue does not
 *     serve, so it is refused BY NAME with its own reason token; the refusal
 *     is `invalid-argument` (a malformed request), and the reason tells the
 *     client which of the two it was.
 *  4. **The two ordering rules** that make the guard worth having: a
 *     forbidden or unknown key is refused *before* the `gameType` and `mode`
 *     values are read, and no error message ever echoes a value the client
 *     sent.
 *
 * The returned type is asserted to carry no identity at all: the request is
 * the mode and the GameType and nothing else, because the caller's uid comes
 * from `CallableAuth.requireUid(request)` at the callable boundary.
 */
class MatchmakingRequestTest {

  // ── helpers ─────────────────────────────────────────────────────────

  /**
   * The (code, message) of the thrown HttpsError, or a failed assertion.
   *
   * `HttpsError` is an external class, so the code and the message are read
   * off the constructed object the way the Functions runtime reads them.
   */
  private fun thrownError(block: () -> Any?): Pair<String, String> {
    try {
      block()
    } catch (e: Throwable) {
      val code = e.asDynamic().code as? String
        ?: throw AssertionError("expected an HttpsError, got $e")
      val message = e.asDynamic().message as? String ?: e.message ?: ""
      return code to message
    }
    throw AssertionError("expected an HttpsError")
  }

  private fun assertDenied(
    expectedCode: String,
    block: () -> Any?,
  ): String {
    val (code, message) = thrownError(block)
    assertEquals(expectedCode, code)
    return message
  }

  /** The reason token carried in the error's plain `details` object. */
  private fun thrownReason(block: () -> Any?): String? {
    try {
      block()
    } catch (e: Throwable) {
      return e.asDynamic().details?.reason as? String
    }
    throw AssertionError("expected an HttpsError")
  }

  /** The exact denial sentence the S57 contract fixes for a forbidden key. */
  private fun expectedForbiddenMessage(key: String): String =
    "identity and tier are server-derived; '$key' is not accepted."

  /** A Ranked request — the shape every pre-S71 client already sends. */
  private fun ranked(vararg extra: Pair<String, Any?>): Map<String, Any?> =
    linkedMapOf<String, Any?>("gameType" to "FULL", "mode" to "RANKED").apply {
      for ((k, v) in extra) put(k, v)
    }

  // ── the accepted request ────────────────────────────────────────────

  @Test
  fun aKotlinMapRequestingFullIsAccepted() {
    val request = parseSearchRequest(ranked())

    assertSame(GameType.FULL, request.gameType)
    assertSame(MatchMode.RANKED, request.mode)
  }

  @Test
  fun aKotlinMapRequestingMiniIsAccepted() {
    val request = parseSearchRequest(ranked("gameType" to "MINI"))

    assertSame(GameType.MINI, request.gameType)
  }

  @Test
  fun anUnrankedRequestIsAccepted() {
    val request = parseSearchRequest(ranked("mode" to "UNRANKED"))

    assertSame(MatchMode.UNRANKED, request.mode)
    assertSame(GameType.FULL, request.gameType)
  }

  @Test
  fun aPlainJavascriptObjectRequestingFullIsAccepted() {
    // The runtime shape: an object literal is NOT a kotlin.collections.Map.
    val payload: Any? = js("({ gameType: 'FULL', mode: 'RANKED' })")

    val request = parseSearchRequest(payload)

    assertSame(GameType.FULL, request.gameType)
    assertSame(MatchMode.RANKED, request.mode)
  }

  @Test
  fun aPlainJavascriptObjectRequestingMiniIsAccepted() {
    // kotlin.js.json builds the other plain-object shape the runtime can
    // deliver.
    val payload: Any? = json("gameType" to "MINI", "mode" to "RANKED")

    val request = parseSearchRequest(payload)

    assertSame(GameType.MINI, request.gameType)
  }

  @Test
  fun theAcceptedEnumIsExactlyTheRequestedGameType() {
    assertEquals("FULL", GameType.FULL.name)
    assertEquals("MINI", GameType.MINI.name)

    assertSame(GameType.FULL, parseSearchRequest(ranked()).gameType)
    assertSame(GameType.MINI, parseSearchRequest(ranked("gameType" to "MINI")).gameType)
  }

  @Test
  fun theAcceptedRequestCarriesNoIdentity() {
    // The data class prints every property it holds, so the string is the
    // proof that a uid (or a tier) is not in there: the guard's output is the
    // mode and the GameType, and the caller's identity is the callable's to
    // read.
    val request = parseSearchRequest(ranked())

    assertEquals("MatchSearchRequest(mode=RANKED, gameType=FULL)", request.toString())
    assertFalse(request.toString().contains("uid", ignoreCase = true))
  }

  @Test
  fun theAcceptedRequestCarriesNoTier() {
    // The Unranked pool is rank-blind: the request that names a mode never
    // names a rank, and the parsed request holds no tier field to hold one.
    val request = parseSearchRequest(ranked("mode" to "UNRANKED"))

    assertEquals("MatchSearchRequest(mode=UNRANKED, gameType=FULL)", request.toString())
    assertFalse(request.toString().contains("tier", ignoreCase = true))
    assertFalse(request.toString().contains("rank=", ignoreCase = true))
  }

  // ── a payload that is not an object ─────────────────────────────────

  @Test
  fun aRequestWithNoDataIsRejected() {
    // A callable that was called with no payload at all.
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(null)
    }

    assertTrue(message.contains("object"), "the message should say what was expected: $message")
  }

  @Test
  fun aStringPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest("FULL")
    }
  }

  @Test
  fun aNumberPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(7)
    }
  }

  @Test
  fun aBooleanPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(true)
    }
  }

  @Test
  fun aKotlinListPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(listOf("FULL"))
    }
  }

  @Test
  fun aPlainJavascriptArrayPayloadIsRejected() {
    val payload: Any? = js("(['FULL'])")

    assertDenied("invalid-argument") {
      parseSearchRequest(payload)
    }
  }

  @Test
  fun anEmptyObjectIsRejected() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(mapOf<String, Any?>())
    }

    assertTrue(message.contains("gameType"), "the message should name the field: $message")
  }

  // ── the gameType field ──────────────────────────────────────────────

  @Test
  fun aRequestWithoutAGameTypeFieldIsRejected() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(mapOf<String, Any?>("mode" to "RANKED"))
    }

    assertTrue(message.contains("'gameType'"), "the message should name the field: $message")
  }

  @Test
  fun aNullGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to null))
    }
  }

  @Test
  fun aNonStringGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to 7))
    }
  }

  @Test
  fun aBooleanGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to true))
    }
  }

  @Test
  fun aLowercaseGameTypeIsRejected() {
    // "full" is a different string from "FULL" — the enum names are the
    // contract, not a forgiving alias.
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to "full"))
    }

    assertFalse(message.contains("full"), "the raw value must not be echoed: $message")
  }

  @Test
  fun anUnrecognisedGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to "TRIO"))
    }
  }

  // ── the mode field (S71) ────────────────────────────────────────────

  @Test
  fun aRequestWithoutAModeFieldIsRejected() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(mapOf<String, Any?>("gameType" to "FULL"))
    }

    assertTrue(message.contains("'mode'"), "the message should name the field: $message")
  }

  @Test
  fun aNullModeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("mode" to null))
    }
  }

  @Test
  fun aNonStringModeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("mode" to 7))
    }
  }

  @Test
  fun aBooleanModeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("mode" to true))
    }
  }

  @Test
  fun aLowercaseModeIsRejected() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("mode" to "ranked"))
    }

    assertFalse(message.contains("ranked"), "the raw value must not be echoed: $message")
  }

  @Test
  fun anUnrecognisedModeIsRejected() {
    assertDenied("invalid-argument") {
      parseSearchRequest(ranked("mode" to "CASUAL"))
    }
  }

  @Test
  fun theRoomModeIsRefusedBecauseTheQueueDoesNotServeIt() {
    // ROOM is a real mode — the third value of MatchMode — and this queue
    // does not serve it: a Room is filled by invitation, never by a pool. It
    // is refused by name, with its own reason token, so the client can tell
    // "wrong mode for this queue" from "malformed request".
    val error = thrownError {
      parseSearchRequest(ranked("mode" to "ROOM"))
    }

    assertEquals("invalid-argument", error.first)
    assertTrue(error.second.contains("mode"), "the message should name the field: ${error.second}")
  }

  @Test
  fun theRoomModeIsRefusedWithTheUnsupportedModeReason() {
    // The reason token is what tells the client "this queue does not serve
    // ROOM" apart from "your request was malformed" — the two are both
    // invalid-argument at the HTTP level, but only one is a wrong-mode
    // request.
    val reason = thrownReason {
      parseSearchRequest(ranked("mode" to "ROOM"))
    }

    assertEquals(RequestReason.UNSUPPORTED_MODE, reason)
  }

  @Test
  fun anUnrecognisedModeIsRefusedWithTheInvalidArgumentReason() {
    // Not a mode the queue knows at all — that is malformed, not a mode the
    // queue declines to serve. The two must not share a reason.
    val reason = thrownReason {
      parseSearchRequest(ranked("mode" to "CASUAL"))
    }

    assertEquals(RequestReason.INVALID_ARGUMENT, reason)
  }

  // ── the forbidden identity/tier keys (RD9) ──────────────────────────

  @Test
  fun everyForbiddenIdentityOrTierKeyIsRefused() {
    val forbidden = listOf(
      "uid",
      "playerId",
      "tier",
      "division",
      "rank",
      "rp",
      "pool",
      "minTier",
      "maxTier",
      "optUp",
      "tierClaim",
      "rankClaim",
    )

    // The list IS the coverage: assert its size, so a future edit that drops
    // a key from it fails here instead of quietly narrowing what is tested.
    assertEquals(12, forbidden.size, "every forbidden key must stay in the coverage list")

    for (key in forbidden) {
      val message = assertDenied("permission-denied") {
        parseSearchRequest(ranked(key to "Gold"))
      }
      assertEquals(expectedForbiddenMessage(key), message)
    }
  }

  @Test
  fun aForbiddenKeyIsRefusedWithTheContractSentence() {
    val message = assertDenied("permission-denied") {
      parseSearchRequest(ranked("gameType" to "MINI", "tier" to "Gold"))
    }

    assertEquals("identity and tier are server-derived; 'tier' is not accepted.", message)
  }

  @Test
  fun aForbiddenKeyIsRefusedBeforeTheGameTypeValueIsRead() {
    // The key rules run first: a forged tier claim beside a malformed
    // gameType is an abuse attempt (permission-denied), not a typo.
    val message = assertDenied("permission-denied") {
      parseSearchRequest(ranked("gameType" to "not-a-game-type", "tier" to "Gold"))
    }

    assertEquals(expectedForbiddenMessage("tier"), message)
  }

  @Test
  fun aForbiddenKeyIsRefusedBeforeTheModeValueIsRead() {
    // The same order holds for the mode: a forged tier claim is reported as
    // the abuse attempt, not as "your mode was malformed".
    val message = assertDenied("permission-denied") {
      parseSearchRequest(ranked("mode" to "ROOM", "tier" to "Gold"))
    }

    assertEquals(expectedForbiddenMessage("tier"), message)
  }

  @Test
  fun aForbiddenKeyIsRefusedEvenWhenTheGameTypeIsValid() {
    assertDenied("permission-denied") {
      parseSearchRequest(ranked("uid" to "someone-else"))
    }
  }

  @Test
  fun aForbiddenKeyIsRefusedBeforeAnUnknownKey() {
    // Refusal order among keys does not depend on the order the client
    // serialised them in: the abuse attempt is reported as the abuse.
    val message = assertDenied("permission-denied") {
      parseSearchRequest(
        linkedMapOf("mystery" to 1, "gameType" to "FULL", "mode" to "RANKED", "optUp" to true),
      )
    }

    assertEquals(expectedForbiddenMessage("optUp"), message)
  }

  @Test
  fun aDifferentlyCasedTierKeyIsStillRefused() {
    // The forbidden set is matched on the exact key name, so "Tier" is not in
    // it — but it is still refused, one step later, as a field nobody knows.
    // The property that matters is that it is never accepted.
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("Tier" to "Gold"))
    }

    assertTrue(message.contains("'Tier'"), "the message should name the key: $message")
  }

  // ── unknown keys ────────────────────────────────────────────────────

  @Test
  fun anUnknownExtraKeyIsRefused() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("seasonId" to "s1"))
    }

    assertTrue(message.contains("'seasonId'"), "the message should name the key: $message")
  }

  @Test
  fun anUnknownKeyIsRefusedBeforeTheGameTypeValueIsRead() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to "not-a-game-type", "seasonId" to "s1"))
    }

    assertTrue(message.contains("'seasonId'"), "the message should name the key: $message")
  }

  @Test
  fun aNonStringFieldNameIsRefused() {
    assertDenied("invalid-argument") {
      parseSearchRequest(mapOf(1 to "FULL"))
    }
  }

  // ── the values are never echoed ─────────────────────────────────────

  @Test
  fun aForbiddenKeyValueIsNeverEchoedInTheError() {
    val message = assertDenied("permission-denied") {
      parseSearchRequest(ranked("tier" to "SENTINEL_TIER_VALUE"))
    }

    assertFalse(message.contains("SENTINEL_TIER_VALUE"), "the value must not be echoed: $message")
    assertFalse(message.contains("SENTINEL"), "no part of the value may be echoed: $message")
  }

  @Test
  fun anUnknownKeyValueIsNeverEchoedInTheError() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("seasonId" to "SENTINEL_UNKNOWN_VALUE"))
    }

    assertFalse(message.contains("SENTINEL"), "the value must not be echoed: $message")
    assertTrue(message.contains("'seasonId'"), "the key itself is named: $message")
  }

  @Test
  fun anInvalidGameTypeValueIsNeverEchoedInTheError() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to "SENTINEL_GAME_TYPE"))
    }

    assertFalse(message.contains("SENTINEL"), "the value must not be echoed: $message")
  }

  @Test
  fun aNonStringGameTypeValueIsNeverEchoedInTheError() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("gameType" to 123456))
    }

    assertFalse(message.contains("123456"), "the value must not be echoed: $message")
    // Absence alone is not enough: the message must still tell the client
    // which field is wrong.
    assertTrue(message.contains("'gameType'"), "the message should name the field: $message")
  }

  @Test
  fun anInvalidModeValueIsNeverEchoedInTheError() {
    val message = assertDenied("invalid-argument") {
      parseSearchRequest(ranked("mode" to "SENTINEL_MODE"))
    }

    assertFalse(message.contains("SENTINEL"), "the value must not be echoed: $message")
    assertTrue(message.contains("'mode'"), "the message should name the field: $message")
  }
}
