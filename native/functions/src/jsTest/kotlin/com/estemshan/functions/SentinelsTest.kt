package com.estemshan.functions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Pins the sentinel → firebase-admin transform boundary. This is where the
 * "plain JS objects, never Kotlin maps" contract is enforced for writes:
 * the core wraps intent in inert sentinels, this module resolves them into
 * the real Admin-SDK transforms right before the commit.
 *
 * Runs against the pinned firebase-admin (13.10.0) — the same SDK the
 * emulator and production use.
 */
class SentinelsTest {

  @Test
  fun arrayUnionSentinelBecomesTheAdminArrayUnionTransform() {
    val entry = toPlain(mapOf("seatId" to "p1", "actionType" to "SubmitDashCallDecision", "round" to 1))

    val translated = Sentinels.translate(AuthoritySentinels.arrayUnion(entry))

    val transform = translated?.asDynamic()
      ?: error("expected the admin arrayUnion transform, got null")
    assertEquals(
      "FieldValue.arrayUnion",
      transform.methodName as String,
      "the log update must be an arrayUnion transform",
    )
    @Suppress("UNCHECKED_CAST")
    val elements = transform.elements as Array<*>
    assertEquals(1, elements.size)
    val first = elements[0]
    assertTrue(
      isPlainJsObject(first),
      "the transform's elements must be plain JS objects",
    )
    assertEquals("p1", (dynamicToKotlin(first) as Map<*, *>)["seatId"])
  }

  @Test
  fun serverTimestampSentinelBecomesANonPlainAdminTransform() {
    val sentinel = AuthoritySentinels.serverTimestamp()

    val translated = Sentinels.translate(mapOf("updatedAt" to sentinel))

    @Suppress("UNCHECKED_CAST")
    val map = translated as Map<String, Any?>
    val resolved = map["updatedAt"]
    // A class instance created by the SDK — never the inert sentinel, never
    // a plain object (Firestore would reject both).
    assertNotSame(sentinel, resolved)
    assertTrue(
      !isPlainJsObject(resolved),
      "the serverTimestamp transform must be an SDK class instance",
    )
  }

  @Test
  fun sentinelsNestedInsideMapsAndListsAreTranslatedDeep() {
    val input = mapOf(
      "outer" to mapOf(
        "list" to listOf(mapOf("stamp" to AuthoritySentinels.serverTimestamp())),
      ),
      "plain" to "untouched",
    )

    val translated = Sentinels.translate(input) as Map<*, *>

    @Suppress("UNCHECKED_CAST")
    val outer = translated["outer"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val list = outer["list"] as List<*>
    @Suppress("UNCHECKED_CAST")
    val stamp = (list.single() as Map<String, Any?>)["stamp"]
    assertTrue(
      !isPlainJsObject(stamp),
      "a nested sentinel must be resolved",
    )
    assertEquals("untouched", translated["plain"])
  }

  /** Same prototype check the production bridging uses — a plain JS object. */
  private fun isPlainJsObject(value: Any?): Boolean {
    if (value == null) return false
    return js("Object.getPrototypeOf(value) === Object.prototype") as Boolean
  }
}
