package com.estemshan.functions

/**
 * Translates the authority core's storage sentinels into the real
 * firebase-admin transforms at the commit boundary (:functions only — this
 * file is where Admin-SDK territory begins).
 *
 * The core builds write maps containing inert [AuthoritySentinels] markers
 * because it cannot import the Firebase SDKs. Every write path converts
 * them here, before `toPlain`:
 *
 * - [ServerTimestampSentinel] → `FieldValue.serverTimestamp()`
 * - [ArrayUnionSentinel] → `FieldValue.arrayUnion(...)` with elements that
 *   are already plain JS (the core runs them through [toPlain] at
 *   construction, which is what Firestore's "no custom prototypes" rule
 *   demands)
 *
 * Anything sentinel-shaped that is NOT one of these two is a bug and fails
 * loudly rather than reaching Firestore as an unknown object.
 */
object Sentinels {

  fun translate(value: Any?): Any? = when (value) {
    is ServerTimestampSentinel -> FieldValue.serverTimestamp()
    is ArrayUnionSentinel -> FieldValue.arrayUnion(
      *value.elements.map { element -> translate(element) }.toTypedArray(),
    )
    is Map<*, *> -> {
      val out = LinkedHashMap<String, Any?>(value.size)
      for ((k, v) in value) {
        out[k as? String ?: continue] = translate(v)
      }
      out
    }
    is List<*> -> value.map { translate(it) }
    is Array<*> -> value.map { translate(it) }.toTypedArray()
    else -> value
  }
}
