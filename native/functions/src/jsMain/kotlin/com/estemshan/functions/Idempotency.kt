package com.estemshan.functions

/**
 * Idempotency (S42): a Ranked operation applies exactly once — replays and
 * retries included. RD13 names this for RP ("a match settles exactly once"):
 * a client that retries a settled match, or a function invoked twice by the
 * runtime, must not award progression twice.
 *
 * S42 ships the *primitive*; S51 composes it into RP application and S50 into
 * settlement, where the real Firestore write path is defined. The store is an
 * interface so the decision logic is unit-testable against an in-memory double
 * — a Firestore-backed implementation (a claim document per key, committed in
 * the same transaction as the write it guards) comes with those stories.
 */

/** The outcome of an idempotent run. */
sealed class IdempotentResult<out T> {

  /** This invocation did the work and produced [result]. */
  class Executed<T>(val result: T) : IdempotentResult<T>()

  /**
   * A earlier invocation already applied this operation, so this one was a
   * no-op. Carries the outcome that first invocation recorded, so a retrying
   * client gets a consistent answer rather than an error — that is the whole
   * point of idempotency under RD13.
   */
  class Replayed(val recordedResult: Any?) : IdempotentResult<Nothing>()
}

/**
 * The claim [IdempotencyStore.claim] returns for a key.
 */
sealed class Claim {

  /** This invocation owns the key: run the block, then commit the outcome. */
  class First : Claim()

  /** A prior invocation already completed the key; [outcome] is its result. */
  class Already(val outcome: Any?) : Claim()
}

/**
 * Once-only storage keyed by an idempotency key — a match ID plus operation,
 * e.g. `"match-42:settle"`.
 *
 * **Atomicity is the contract.** Concurrent [claim] calls on one key must
 * return exactly one [Claim.First]; the rest get [Claim.Already]. The
 * in-process JS event loop makes a simple map sufficient for tests; a real
 * implementation must use a compare-and-set or transaction.
 */
interface IdempotencyStore {

  suspend fun claim(key: String): Claim

  /** Records the outcome of the invocation that won [Claim.First]. */
  suspend fun commit(key: String, result: Any?)
}

/**
 * Runs [block] at most once per [key].
 *
 * First invocation: claims, runs, commits → [IdempotentResult.Executed].
 * Any later invocation: → [IdempotentResult.Replayed] with the recorded
 * outcome, and [block] is never called. That is the RD13 no-op-on-replay.
 */
class IdempotencyGuard(private val store: IdempotencyStore) {

  suspend fun <T> runOnce(key: String, block: suspend () -> T): IdempotentResult<T> =
    when (val claim = store.claim(key)) {
      is Claim.First -> {
        val result = block()
        store.commit(key, result)
        IdempotentResult.Executed(result)
      }
      is Claim.Already -> IdempotentResult.Replayed(claim.outcome)
    }
}
