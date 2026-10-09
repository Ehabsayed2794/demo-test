package com.estemshan.functions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The idempotency guard's once-only semantics (RD13), against an in-memory
 * store. The Firestore-backed implementation arrives with S50/S51; this
 * proves the *decision logic* a replay never re-runs the block and a retrying
 * caller sees the first invocation's outcome.
 */
class IdempotencyGuardTest {

  @Test
  fun firstInvocationExecutesTheBlock() = runTest {
    val store = InMemoryStore()
    val guard = IdempotencyGuard(store)

    val result = guard.runOnce("match-1:settle") { "settled" }

    assertIs<IdempotentResult.Executed<String>>(result)
    assertEquals("settled", result.result)
    assertEquals("settled", store.committed["match-1:settle"])
  }

  @Test
  fun aReplayDoesNotReRunTheBlock() = runTest {
    val store = InMemoryStore()
    val guard = IdempotencyGuard(store)
    var invocations = 0

    guard.runOnce("match-1:settle") { invocations++; "settled" }
    val replay = guard.runOnce("match-1:settle") { invocations++; "should-not-run" }

    assertIs<IdempotentResult.Replayed>(replay)
    assertEquals(1, invocations, "the block must run exactly once across replays")
  }

  @Test
  fun aReplayReturnsTheFirstInvocationOutcome() = runTest {
    val guard = IdempotencyGuard(InMemoryStore())

    guard.runOnce("match-1:settle") { mapOf("winner" to "player-1") }
    val replay = guard.runOnce("match-1:settle") { mapOf("winner" to "player-2") }

    assertIs<IdempotentResult.Replayed>(replay)
    // A retrying client sees the ORIGINAL outcome, not an error and not the
    // second block's value.
    assertEquals(mapOf("winner" to "player-1"), replay.recordedResult)
  }

  @Test
  fun differentKeysAreIndependent() = runTest {
    val guard = IdempotencyGuard(InMemoryStore())

    val a = guard.runOnce("match-1:settle") { "a" }
    val b = guard.runOnce("match-2:settle") { "b" }

    assertIs<IdempotentResult.Executed<String>>(a)
    assertIs<IdempotentResult.Executed<String>>(b)
    assertEquals("a", a.result)
    assertEquals("b", b.result)
  }

  @Test
  fun onlyOneConcurrentClaimWins() = runTest {
    val store = InMemoryStore()
    val guard = IdempotencyGuard(store)

    // Two claims on the same key before either commits: the store's atomicity
    // contract is that exactly one wins.
    store.claim("match-1:settle")
    val second = guard.runOnce("match-1:settle") { "should-not-run" }

    assertIs<IdempotentResult.Replayed>(second)
    assertTrue(store.committed.isEmpty(), "the losing claim must not have committed")
  }

  /**
   * A test double. The JS event loop is single-threaded, so a plain map honours
   * the atomicity contract in-process; a real implementation needs a
   * compare-and-set or transaction (S50).
   */
  private class InMemoryStore : IdempotencyStore {
    val committed = mutableMapOf<String, Any?>()
    private val claimed = mutableSetOf<String>()

    override suspend fun claim(key: String): Claim {
      if (committed.containsKey(key)) return Claim.Already(committed[key])
      if (!claimed.add(key)) return Claim.Already(committed[key])
      return Claim.First()
    }

    override suspend fun commit(key: String, result: Any?) {
      committed[key] = result
    }
  }
}
