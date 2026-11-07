package com.estemshan.functions

/**
 * Storage-layer sentinels (S50/S61).
 *
 * Firestore's commit-time transforms — `FieldValue.serverTimestamp()` and
 * `FieldValue.arrayUnion(...)` — are Firebase objects, and the authority
 * core must not import the Firebase SDKs: the same core compiles into the
 * Worker bundle, where firebase-admin / firebase-functions cannot load.
 * So writes carry these inert markers instead, and each runtime adapter
 * translates them at its own commit boundary:
 *
 * - the Firebase adapter (:functions) turns them into the real Admin-SDK
 *   transforms right before `tx.set` / `tx.update` (see Sentinels.kt there);
 * - the Cloudflare adapter (O-06 slice) will resolve them at SQL write time
 *   (e.g. `updatedAt = CURRENT_TIMESTAMP`, or an equivalent epoch-millis
 *   bind) inside the Durable Object transaction.
 *
 * A sentinel must never reach a client: responses never carry them, and
 * `toPlain` passes them through by reference on purpose so a missing
 * adapter translation is loud (the storage rejects the unknown object)
 * rather than silent.
 */
class ServerTimestampSentinel private constructor() {
  companion object {
    val INSTANCE: ServerTimestampSentinel = ServerTimestampSentinel()
  }
}

/**
 * Append-only log write (`biddingLog` / `cardLog`). [elements] are already
 * plain data — callers run them through [toPlain] at construction, which is
 * what Firestore's "no custom prototypes" rule demands.
 */
class ArrayUnionSentinel(val elements: List<Any?>)

object AuthoritySentinels {
  fun serverTimestamp(): Any = ServerTimestampSentinel.INSTANCE

  fun arrayUnion(vararg elements: Any?): Any = ArrayUnionSentinel(elements.toList())
}
