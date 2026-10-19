package com.estemshan.functions

import com.estemshan.engine.GameType
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S57 — the Ranked search-request guard (RD9).
 *
 * The guard is the anti-abuse boundary in front of S56's pool derivation: the
 * client may say which GameType it wants to play, and it may say nothing
 * else. These tests pin three things.
 *
 *  1. **The accepted shape** — one field, `gameType`, whose value is exactly
 *     `"FULL"` or `"MINI"`, in both shapes the boundary actually sees: a
 *     Kotlin map (what a test fixture builds) and a **plain JS object** (what
 *     the Functions runtime delivers — see `CallablePayloadBridgingTest`).
 *  2. **The refusal matrix** — a missing/null/non-object/array payload, a
 *     missing/null/malformed `gameType`, an unknown key, and each of the
 *     twelve forbidden identity/tier keys.
 *  3. **The two ordering rules** that make the guard worth having: a
 *     forbidden or unknown key is refused *before* the `gameType` value is
 *     read, and no error message ever echoes the value the client sent.
 *
 * The returned type is asserted to carry no identity at all: the request is
 * the GameType and nothing else, because the caller's uid comes from
 * `CallableAuth.requireUid(request)` at the callable boundary — which S57
 * does not implement.
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

  /** The exact denial sentence the S57 contract fixes for a forbidden key. */
  private fun expectedForbiddenMessage(key: String): String =
    "identity and tier are server-derived; '$key' is not accepted."

  // ── the accepted request ────────────────────────────────────────────

  @Test
  fun aKotlinMapRequestingFullIsAccepted() {
    val request = parseRankedSearchRequest(mapOf("gameType" to "FULL"))

    assertSame(GameType.FULL, request.gameType)
  }

  @Test
  fun aKotlinMapRequestingMiniIsAccepted() {
    val request = parseRankedSearchRequest(mapOf("gameType" to "MINI"))

    assertSame(GameType.MINI, request.gameType)
  }

  @Test
  fun aPlainJavascriptObjectRequestingFullIsAccepted() {
    // The runtime shape: an object literal is NOT a kotlin.collections.Map.
    val payload: Any? = js("({ gameType: 'FULL' })")

    val request = parseRankedSearchRequest(payload)

    assertSame(GameType.FULL, request.gameType)
  }

  @Test
  fun aPlainJavascriptObjectRequestingMiniIsAccepted() {
    // kotlin.js.json builds the other plain-object shape the runtime can
    // deliver.
    val payload: Any? = json("gameType" to "MINI")

    val request = parseRankedSearchRequest(payload)

    assertSame(GameType.MINI, request.gameType)
  }

  @Test
  fun theAcceptedEnumIsExactlyTheRequestedGameType() {
    assertEquals("FULL", GameType.FULL.name)
    assertEquals("MINI", GameType.MINI.name)

    assertSame(GameType.FULL, parseRankedSearchRequest(mapOf("gameType" to "FULL")).gameType)
    assertSame(GameType.MINI, parseRankedSearchRequest(mapOf("gameType" to "MINI")).gameType)
  }

  @Test
  fun theAcceptedRequestCarriesNoIdentity() {
    // The data class prints every property it holds, so the string is the
    // proof that a uid (or a tier) is not in there: the guard's output is the
    // GameType, and the caller's identity is the callable's to read.
    val request = parseRankedSearchRequest(mapOf("gameType" to "FULL"))

    assertEquals("RankedSearchRequest(gameType=FULL)", request.toString())
    assertFalse(request.toString().contains("uid", ignoreCase = true))
  }

  // ── a payload that is not an object ─────────────────────────────────

  @Test
  fun aRequestWithNoDataIsRejected() {
    // A callable that was called with no payload at all.
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(null)
    }

    assertTrue(message.contains("object"), "the message should say what was expected: $message")
  }

  @Test
  fun aStringPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest("FULL")
    }
  }

  @Test
  fun aNumberPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(7)
    }
  }

  @Test
  fun aBooleanPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(true)
    }
  }

  @Test
  fun aKotlinListPayloadIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(listOf("FULL"))
    }
  }

  @Test
  fun aPlainJavascriptArrayPayloadIsRejected() {
    val payload: Any? = js("(['FULL'])")

    assertDenied("invalid-argument") {
      parseRankedSearchRequest(payload)
    }
  }

  @Test
  fun anEmptyObjectIsRejected() {
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf<String, Any?>())
    }

    assertTrue(message.contains("gameType"), "the message should name the field: $message")
  }

  // ── the gameType field ──────────────────────────────────────────────

  @Test
  fun aRequestWithoutAGameTypeFieldIsRejected() {
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf<String, Any?>())
    }

    assertTrue(message.contains("'gameType'"), "the message should name the field: $message")
  }

  @Test
  fun aNullGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to null))
    }
  }

  @Test
  fun aNonStringGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to 7))
    }
  }

  @Test
  fun aBooleanGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to true))
    }
  }

  @Test
  fun aLowercaseGameTypeIsRejected() {
    // "full" is a different string from "FULL" — the enum names are the
    // contract, not a forgiving alias.
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to "full"))
    }

    assertFalse(message.contains("full"), "the raw value must not be echoed: $message")
  }

  @Test
  fun anUnrecognisedGameTypeIsRejected() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to "TRIO"))
    }
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
        parseRankedSearchRequest(mapOf("gameType" to "FULL", key to "Gold"))
      }
      assertEquals(expectedForbiddenMessage(key), message)
    }
  }

  @Test
  fun aForbiddenKeyIsRefusedWithTheContractSentence() {
    val message = assertDenied("permission-denied") {
      parseRankedSearchRequest(mapOf("gameType" to "MINI", "tier" to "Gold"))
    }

    assertEquals("identity and tier are server-derived; 'tier' is not accepted.", message)
  }

  @Test
  fun aForbiddenKeyIsRefusedBeforeTheGameTypeValueIsRead() {
    // The key rules run first: a forged tier claim beside a malformed
    // gameType is an abuse attempt (permission-denied), not a typo.
    val message = assertDenied("permission-denied") {
      parseRankedSearchRequest(mapOf("gameType" to "not-a-game-type", "tier" to "Gold"))
    }

    assertEquals(expectedForbiddenMessage("tier"), message)
  }

  @Test
  fun aForbiddenKeyIsRefusedEvenWhenTheGameTypeIsValid() {
    assertDenied("permission-denied") {
      parseRankedSearchRequest(mapOf("gameType" to "FULL", "uid" to "someone-else"))
    }
  }

  @Test
  fun aForbiddenKeyIsRefusedBeforeAnUnknownKey() {
    // Refusal order among keys does not depend on the order the client
    // serialised them in: the abuse attempt is reported as the abuse.
    val message = assertDenied("permission-denied") {
      parseRankedSearchRequest(
        linkedMapOf("mystery" to 1, "gameType" to "FULL", "optUp" to true),
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
      parseRankedSearchRequest(mapOf("gameType" to "FULL", "Tier" to "Gold"))
    }

    assertTrue(message.contains("'Tier'"), "the message should name the key: $message")
  }

  // ── unknown keys ────────────────────────────────────────────────────

  @Test
  fun anUnknownExtraKeyIsRefused() {
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to "FULL", "seasonId" to "s1"))
    }

    assertTrue(message.contains("'seasonId'"), "the message should name the key: $message")
  }

  @Test
  fun anUnknownKeyIsRefusedBeforeTheGameTypeValueIsRead() {
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to "not-a-game-type", "seasonId" to "s1"))
    }

    assertTrue(message.contains("'seasonId'"), "the message should name the key: $message")
  }

  @Test
  fun aNonStringFieldNameIsRefused() {
    assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf(1 to "FULL"))
    }
  }

  // ── the values are never echoed ─────────────────────────────────────

  @Test
  fun aForbiddenKeyValueIsNeverEchoedInTheError() {
    val message = assertDenied("permission-denied") {
      parseRankedSearchRequest(mapOf("gameType" to "FULL", "tier" to "SENTINEL_TIER_VALUE"))
    }

    assertFalse(message.contains("SENTINEL_TIER_VALUE"), "the value must not be echoed: $message")
    assertFalse(message.contains("SENTINEL"), "no part of the value may be echoed: $message")
  }

  @Test
  fun anUnknownKeyValueIsNeverEchoedInTheError() {
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to "FULL", "seasonId" to "SENTINEL_UNKNOWN_VALUE"))
    }

    assertFalse(message.contains("SENTINEL"), "the value must not be echoed: $message")
    assertTrue(message.contains("'seasonId'"), "the key itself is named: $message")
  }

  @Test
  fun anInvalidGameTypeValueIsNeverEchoedInTheError() {
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to "SENTINEL_GAME_TYPE"))
    }

    assertFalse(message.contains("SENTINEL"), "the value must not be echoed: $message")
  }

  @Test
  fun aNonStringGameTypeValueIsNeverEchoedInTheError() {
    val message = assertDenied("invalid-argument") {
      parseRankedSearchRequest(mapOf("gameType" to 123456))
    }

    assertFalse(message.contains("123456"), "the value must not be echoed: $message")
    // Absence alone is not enough: the message must still tell the client
    // which field is wrong.
    assertTrue(message.contains("'gameType'"), "the message should name the field: $message")
  }
}
