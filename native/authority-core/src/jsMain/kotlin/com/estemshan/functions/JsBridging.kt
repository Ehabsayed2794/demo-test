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
 *
 * [Long] is special-cased before the [Number] branch it would otherwise match:
 * on Kotlin/JS a `Long` is a `Number` subclass carrying own enumerable
 * `low_1`/`high_1` fields and no `toJSON`, so `JSON.stringify` emits an object
 * (`{"low_1":..,"high_1":..}`), not a number. A `retryAfterMillis` that
 * arrived like that failed the client's `as? Number` and fell back to the
 * constant retry delay. Doubles stay exact for every value this module
 * serializes — epoch millis and durations sit ~1.75e12, far below 2^53.
 */
fun toPlain(value: Any?): Any? = when {
  value == null || value is String || value is Boolean -> value
  value is Long -> value.toDouble()
  value is Number -> value
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

/**
 * S59: build the Admin-SDK `Precondition` object for one profile batch write.
 *
 * `lastUpdateTime` is the document's committed `updateTime` from the read that
 * planned the reset. Firestore compares it atomically at commit: a settlement
 * that lands in between changes the version, the whole batch fails with
 * FAILED_PRECONDITION, and the reset cannot overwrite the newer progression
 * with its stale plan. This is a plain object literal because that is the only
 * shape the Admin SDK accepts.
 */
fun lastUpdateTimePrecondition(updateTime: Any): Any? {
  val precondition: dynamic = js("{}")
  precondition.lastUpdateTime = updateTime
  return precondition
}

/**
 * S61 Stage 1 — a server timestamp as epoch millis, the anchor the decision
 * timer's deadline is derived from (the doc's `updatedAt`).
 *
 * Admin reads return committed documents, so a `FieldValue.serverTimestamp()`
 * written by MatchService is always a resolved `firebase-admin` Timestamp by
 * the time it reaches us. That Timestamp's public conversion is `toDate()`;
 * the plain-number path exists so unit fixtures can supply millis directly.
 * Any other shape returns null — a deadline is never guessed from a value that
 * is not a real server timestamp (the rematch vote's own rule).
 */
fun timestampToMillis(value: Any?): Long? {
  if (value == null) return null
  if (value is Number) return value.toLong()
  val toDate: dynamic = value.asDynamic().toDate
  if (toDate != null) {
    // runCatching infers `dynamic` for this local, and `x.asDynamic()` on a
    // `dynamic` receiver is NOT the kotlin.js.asDynamic intrinsic — it compiles
    // to a real JS method call, and a firebase-admin Timestamp's Date has no
    // `asDynamic`, so it throws and the conversion dies inside the runCatching.
    // Pinning the type to Any? makes the extension resolve and elide to the
    // receiver. Left unchecked this returns null for every real server timestamp
    // and the deadline can never be derived.
    val date: Any? = runCatching { toDate.call(value) }.getOrNull() ?: return null
    val millis = runCatching { date.asDynamic().getTime() as Number }.getOrNull()
    if (millis != null) return millis.toLong()
  }
  return null
}
