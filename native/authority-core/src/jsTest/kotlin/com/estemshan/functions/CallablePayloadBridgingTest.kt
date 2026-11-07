package com.estemshan.functions

import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The callable request boundary, and the class of bug that lives there.
 *
 * The Functions runtime hands a handler `request.data` as a PLAIN JS OBJECT —
 * never a Kotlin LinkedHashMap. A plain object does not implement
 * kotlin.collections.Map, so `request.data as? Map<*, *>` returns null and the
 * callable silently rejects every real call with "matchId is required".
 *
 * That is not hypothetical: it is exactly what S61's instrumented suite
 * reported as `Denied` on every resolver call, and nothing else in the repo
 * could see it — the JVM unit tests build `mapOf(...)`, which IS a Kotlin Map,
 * and the S42 smoke test sends an empty payload (auth only). The runtime shape
 * had no coverage at all until this test.
 *
 * The fix is [dynamicToKotlin] at the boundary, mirroring the Admin read path;
 * the fakes in [TimeoutResolutionTest] and [SettlementTest] now build
 * `json(...)` so the whole suite exercises the runtime shape too. This test
 * pins the boundary itself, so a future callable that skips the converter
 * fails here rather than in an emulator.
 */
class CallablePayloadBridgingTest {

  @Test
  fun aPlainJavascriptObjectIsNotAKotlinMap() {
    // The two ways a plain JS object reaches Kotlin code: an object literal and
    // kotlin.js.json. Both must fail the raw cast — this is the trap.
    val literal: Any? = js("({ matchId: 'm1' })")
    val fromJson: Any? = json("matchId" to "m1")

    assertNull(literal as? Map<*, *>, "an object literal must not cast to a Kotlin Map")
    assertNull(fromJson as? Map<*, *>, "kotlin.js.json must not cast to a Kotlin Map")
  }

  @Test
  fun dynamicToKotlinMakesAPlainJavascriptObjectReadableAsAMap() {
    val converted = dynamicToKotlin(json("matchId" to "m1")) as? Map<*, *>

    assertNotNull(converted, "dynamicToKotlin must convert a plain JS object to a map")
    assertEquals("m1", converted["matchId"])
  }

  /**
   * The deadline anchor. A committed `FieldValue.serverTimestamp()` reaches the
   * callable as a firebase-admin Timestamp — an object whose `toDate` returns a
   * JS Date. This is the only conversion in the resolver that runs on real
   * admin data and had no test, and it is the one thing the JVM suite cannot
   * shape: `1_000_000L`-style fixtures are the plain-number path.
   */
  @Test
  fun anAdminTimestampConvertsToEpochMillisThroughItsToDate() {
    val adminTimestamp: Any? = js("({ toDate: function() { return new Date(1750000000000); } })")

    assertEquals(1_750_000_000_000L, timestampToMillis(adminTimestamp))
  }
}
