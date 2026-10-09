package com.estemshan.functions

/**
 * Plain-object bridging for Admin-SDK data (S50).
 *
 * Lives in its own file because @file:JsModule files (FirebaseAdmin.kt)
 * may only contain external declarations. Admin-SDK reads return PLAIN
 * JS objects/arrays — Kotlin `as? Map` casts fail on them, and Kotlin
 * maps written back serialize as `{}`. These two converters are the
 * single choke point in both directions, unit-tested in SettlementTest.
 */

/**
 * Deep-converts Admin-SDK plain data into Kotlin LinkedHashMaps/Lists.
 * Primitives, Timestamps and sentinels pass through; anything else that
 * is not a plain object or array passes through untouched (so Kotlin maps
 * built in tests survive the round trip unmodified).
 */
fun dynamicToKotlin(value: Any?): Any? {
  if (value == null || value is String || value is Number || value is Boolean) return value
  if (value is Array<*>) return value.map { dynamicToKotlin(it) }
  if (value is List<*>) return value.map { dynamicToKotlin(it) }
  if (value is Map<*, *>) return value
  if (!isPlainObject(value)) return value
  val out = LinkedHashMap<String, Any?>()
  val entries = js("Object.entries(value)") as Array<Array<Any?>>
  for (entry in entries) {
    out[entry[0] as String] = dynamicToKotlin(entry[1])
  }
  return out
}

private fun isPlainObject(value: Any?): Boolean {
  if (value == null) return false
  if (value is String || value is Number || value is Boolean) return false
  if (value is Array<*> || value is List<*> || value is Map<*, *>) return false
  return js("Object.getPrototypeOf(value) === Object.prototype") as Boolean
}

/**
 * Deep-converts Kotlin maps/lists into plain JS data for Admin writes and
 * callable responses (which the runtime JSON-serializes). Sentinels such
 * as serverTimestamp() pass through by reference.
 */
fun toPlain(value: Any?): Any? = when {
  value == null || value is String || value is Number || value is Boolean -> value
  value is Map<*, *> -> {
    val out: dynamic = js("{}")
    for ((k, v) in value) {
      out[k as String] = toPlain(v)
    }
    out
  }
  value is List<*> -> value.map { toPlain(it) }.toTypedArray()
  value is Array<*> -> value.map { toPlain(it) }.toTypedArray()
  else -> value
}
