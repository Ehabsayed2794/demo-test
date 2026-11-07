package com.estemshan.functions

import com.estemshan.engine.Card

/**
 * The Durable Object storage adapter (O-06).
 *
 * The same ports the Firebase adapter implements, over `ctx.storage.sql`
 * instead of the Admin SDK. **Every call is synchronous by construction** —
 * `sql.exec` is synchronous and this file contains no `await`, so a whole
 * settle/timeout sequence executes as one uninterrupted stretch (reads →
 * writes) and is atomic by the DO's implicit-transaction semantics (design
 * note D1). Cursors are drained inline; an `await` appearing here is a
 * merge-blocker.
 *
 * Rows store the exact Firestore-shaped plain maps the core parses, as JSON
 * text. The match document is read and rewritten wholesale on every write;
 * `biddingLog` / `cardLog` arrays are extended in place for the core's
 * ArrayUnionSentinel writes. `match_state.version` and the JSON blob are
 * always updated in ONE statement.
 *
 * Throws [AuthorityError] on every store failure — the exports layer maps
 * it like every other authority failure; raw SQLite messages never reach
 * a client.
 */

// ── SQL helpers ────────────────────────────────────────────────────────

private fun nowMillis(): Long = (js("Date.now()") as Double).toLong()

private fun bind(v: Any?): Any? = when (v) {
  // Kotlin Long becomes a heap object on JS ({low_1, high_1}); its raw
  // bytes would never compare equal in SQLite. Bind as a plain number.
  is Long -> v.toDouble()
  else -> v
}

private fun call(sql: dynamic, query: String, bindings: Array<out Any?>): dynamic {
  val params = arrayOfNulls<Any?>(bindings.size + 1)
  params[0] = query
  for (i in bindings.indices) params[i + 1] = bind(bindings[i])
  val result: dynamic = js("sql.exec.apply(sql, params)")
  return result
}

private fun exec(sql: dynamic, query: String, vararg bindings: Any?) {
  try {
    call(sql, query, bindings)
  } catch (e: dynamic) {
    throw AuthorityError("internal", "The match store rejected the operation.")
  }
}

private fun rows(sql: dynamic, query: String, vararg bindings: Any?): List<Map<String, Any?>> {
  val array = try {
    call(sql, query, bindings).toArray() as Array<dynamic>
  } catch (e: dynamic) {
    throw AuthorityError("internal", "The match store rejected the operation.")
  }
  return array.map { row -> rowToMap(row) }
}

/**
 * node:sqlite hands back rows with a NULL prototype — the isPlainObject
 * gate in dynamicToKotlin rejects them, and would pass them through as
 * opaque dynamic objects. Rows from this store contain only JSON text and
 * integers, so convert them field-by-field instead.
 */
private fun rowToMap(row: dynamic): Map<String, Any?> {
  if (row == null) throw AuthorityError("internal", "The match store returned a malformed row.")
  val entries = js("Object.entries(row)") as Array<Array<dynamic>>
  val out = LinkedHashMap<String, Any?>(entries.size)
  for (entry in entries) {
    val key = entry[0] as String
    out[key] = when (val v = entry[1]) {
      null, is String, is Boolean, is Number -> v
      else -> dynamicToKotlin(v)
    }
  }
  return out
}

private fun one(sql: dynamic, query: String, vararg bindings: Any?): Map<String, Any?>? {
  val found = rows(sql, query, *bindings)
  if (found.size > 1) {
    throw AuthorityError("internal", "The match store returned ambiguous rows.")
  }
  return found.firstOrNull()
}

// ── JSON helpers ───────────────────────────────────────────────────────

internal fun toJsonNative(value: Any?): Any? = when (value) {
  null, is String, is Boolean -> value
  is Long -> value.toDouble()
  is Number -> value
  is Map<*, *> -> {
    val out: dynamic = js("{}")
    for ((k, v) in value) {
      out[k as String] = toJsonNative(v)
    }
    out
  }
  is List<*> -> value.map { toJsonNative(it) }.toTypedArray()
  is Array<*> -> value.map { toJsonNative(it) }.toTypedArray()
  else -> value
}

internal fun toJson(value: Any?): String =
  kotlin.js.JSON.stringify(toJsonNative(value))

internal fun parseJson(text: String): Any? =
  dynamicToKotlin(kotlin.js.JSON.parse(text))

// ── match document read/write ──────────────────────────────────────────

private fun readMatchJson(sql: dynamic, matchId: String): Map<String, Any?>? =
  one(sql, "SELECT data FROM match_state WHERE match_id = ?", matchId)?.let { row ->
    parseRowJson(row["data"], "match")
  }

private fun parseRowJson(text: Any?, what: String): Map<String, Any?> {
  val s = text as? String
    ?: throw AuthorityError("failed-precondition", "the stored $what is not JSON text.")
  val parsed = try {
    parseJson(s)
  } catch (e: dynamic) {
    throw AuthorityError("failed-precondition", "the stored $what is corrupt.")
  }
  return parsed as? Map<String, Any?>
    ?: throw AuthorityError("failed-precondition", "the stored $what is not an object.")
}

private fun readMatchWithVersion(
  sql: dynamic,
  matchId: String,
): Pair<Map<String, Any?>, Long>? {
  val row = one(sql, "SELECT data, version FROM match_state WHERE match_id = ?", matchId)
    ?: return null
  val parsed = parseRowJson(row["data"], "match")
  return parsed to ((row["version"] as? Number)?.toLong() ?: 0L)
}

/**
 * Applies the core's partial update map onto the persisted document and
 * returns the merged document. Sentinel semantics resolved here, at the
 * commit boundary:
 * - ServerTimestampSentinel → wall-clock epoch millis;
 * - ArrayUnionSentinel → the named JSON array is EXTENDED in place;
 * - everything else → wholesale replacement of the field (mirrors the
 *   Firestore `update` behaviour the logic was written against).
 */
private fun applyMatchUpdates(
  sql: dynamic,
  matchId: String,
  updates: Map<String, Any?>,
): Map<String, Any?> {
  val doc = readMatchWithVersion(sql, matchId)?.first
    ?: throw AuthorityError(
      "failed-precondition",
      "match '$matchId' vanished between read and write.",
    )
  val out = LinkedHashMap(doc)
  for ((k, v) in updates) {
    when (v) {
      is ServerTimestampSentinel -> out[k] = nowMillis()
      is ArrayUnionSentinel -> {
        val current = (out[k] as? List<Any?>) ?: emptyList()
        out[k] = current + v.elements
      }
      else -> out[k] = v
    }
  }
  return out
}

/** One statement for the JSON blob and the duplicated version column. */
private fun writeMatchState(
  sql: dynamic,
  matchId: String,
  doc: Map<String, Any?>,
  version: Long,
) {
  exec(
    sql,
    "UPDATE match_state SET data = ?, version = ?, updated_at = ? WHERE match_id = ?",
    toJson(doc), version, nowMillis(), matchId,
  )
}

private fun versionOf(doc: Map<String, Any?>): Long =
  (doc["version"] as? Number)?.toLong() ?: 0L

// ── settlement adapter ─────────────────────────────────────────────────

class DoSettlementDb(private val sql: dynamic) : SettlementDb {

  override suspend fun readMatch(matchId: String): Map<String, Any?>? =
    readMatchJson(sql, matchId)

  override suspend fun readArchives(matchId: String): Map<String, Map<String, Any?>> {
    val out = LinkedHashMap<String, Map<String, Any?>>()
    for (row in rows(
      sql,
      "SELECT round, data FROM round_archive WHERE match_id = ? ORDER BY round",
      matchId,
    )) {
      // A corrupt archive must fail closed: silently dropping it would make a
      // settled round look unplayed and could change the recomputed scores.
      val round = (row["round"] as? Number)?.toInt()
        ?: throw AuthorityError("failed-precondition", "a stored archive round is not a number.")
      out[round.toString()] = parseRowJson(row["data"], "archive")
    }
    return out
  }

  override suspend fun <T> transact(block: suspend (SettlementDb.SettlementTx) -> T): T =
    block(DoSettlementTx(sql))

  // O-06 D7 (Step 3): the ranked audit ledger / corrections window ride D1's
  // ranked_events + match_outbox, which this slice does not wire yet. Fail
  // closed rather than fabricate an audit trail; the core's best-effort hooks
  // catch this and log, so it never changes a settlement outcome.
  override suspend fun appendLedger(record: Map<String, Any?>) {
    throw AuthorityError(
      "unimplemented",
      "the ranked audit ledger is not wired into the Durable Object store yet (O-06 D7, Step 3).",
    )
  }

  override suspend fun readCorrectionWindow(): List<Map<String, Any?>> {
    throw AuthorityError(
      "unimplemented",
      "the corrections window is not wired into the Durable Object store yet (O-06 D7, Step 3).",
    )
  }

  private class DoSettlementTx(private val sql: dynamic) : SettlementDb.SettlementTx {

    override suspend fun readClaim(matchId: String): Map<String, Any?>? =
      one(sql, "SELECT data FROM settlement_claim WHERE match_id = ?", matchId)?.let { row ->
        // A corrupt claim must NOT look like "no claim yet" — that would
        // let a second settlement double-apply.
        parseRowJson(row["data"], "claim")
      }

    // O-06 D6 (Step 2): player profiles are account state the DO will own in a
    // `profiles` table snapshotted at match start. Until that lands, a Ranked
    // settlement on the DO path fails closed here — inside the transaction,
    // before any write — rather than fold RP against a fabricated pre-image.
    override suspend fun readProfile(uid: String): Map<String, Any?>? {
      throw AuthorityError(
        "unimplemented",
        "player profiles are not wired into the Durable Object store yet (O-06 D6, Step 2).",
      )
    }

    override fun writeProfile(uid: String, fields: Map<String, Any?>) {
      throw AuthorityError(
        "unimplemented",
        "player profiles are not wired into the Durable Object store yet (O-06 D6, Step 2).",
      )
    }

    override fun writeCorrection(
      matchId: String,
      scores: Map<String, Int>,
      winners: List<String>,
      version: Int,
    ) {
      val merged = applyMatchUpdates(
        sql,
        matchId,
        mapOf(
          "finalScores" to LinkedHashMap(scores),
          "winnerIds" to winners,
          "version" to version,
        ),
      )
      writeMatchState(sql, matchId, merged, version.toLong())
    }

    override fun writeClaim(matchId: String, record: Map<String, Any?>) {
      val resolved = LinkedHashMap(record)
      val settledAt = resolved["settledAt"]
      if (settledAt is ServerTimestampSentinel) {
        resolved["settledAt"] = nowMillis()
      }
      exec(
        sql,
        "INSERT INTO settlement_claim (match_id, data, created_at) VALUES (?, ?, ?)",
        matchId, toJson(resolved), nowMillis(),
      )
    }
  }
}

// ── timeout adapter ────────────────────────────────────────────────────

class DoTimeoutDb(private val sql: dynamic) : TimeoutDb {

  override suspend fun readMatch(matchId: String): Map<String, Any?>? =
    readMatchJson(sql, matchId)

  override suspend fun readHands(
    matchId: String,
    round: Int,
    seats: List<String>,
  ): Map<String, List<Card>> {
    if (seats.isEmpty()) return emptyMap()
    val placeholders = seats.joinToString(",") { "?" }
    val out = LinkedHashMap<String, List<Card>>()
    for (row in rows(
      sql,
      "SELECT seat_id, data FROM hands WHERE match_id = ? AND round = ? AND seat_id IN ($placeholders)",
      matchId,
      round,
      *seats.toTypedArray(),
    )) {
      // A corrupt hand must fail closed: dropping a card would decide tricks
      // on partial data, which is worse than refusing to resolve the timeout.
      val seat = row["seat_id"] as? String
        ?: throw AuthorityError("failed-precondition", "a stored hand has no seat.")
      val fields = parseRowJson(row["data"], "hand")
      val handRound = (fields["round"] as? Number)?.toInt()
        ?: throw AuthorityError("failed-precondition", "the stored hand for '$seat' has no round.")
      if (handRound != round) {
        throw AuthorityError(
          "failed-precondition",
          "the stored hand for '$seat' is for round $handRound, not $round.",
        )
      }
      val rawCards = fields["cards"] as? List<*>
        ?: throw AuthorityError("failed-precondition", "the stored hand for '$seat' has no cards.")
      val cards = rawCards.map { raw ->
        parseStoredCard(raw)
          ?: throw AuthorityError(
            "failed-precondition",
            "the stored hand for '$seat' contains an unreadable card.",
          )
      }
      out[seat] = cards
    }
    return out
  }

  override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T =
    block(DoTimeoutTx(sql))

  private class DoTimeoutTx(private val sql: dynamic) : TimeoutDb.TimeoutTx {

    override suspend fun readMatch(matchId: String): Map<String, Any?>? =
      readMatchJson(sql, matchId)

    override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
      val merged = applyMatchUpdates(sql, matchId, updates)
      writeMatchState(sql, matchId, merged, versionOf(merged))
    }

    override suspend fun readTimeoutCount(matchId: String, uid: String): Any? =
      one(sql, "SELECT count FROM timeout_counts WHERE match_id = ? AND uid = ?", matchId, uid)?.get("count")

    override suspend fun timeoutCountExists(matchId: String, uid: String): Boolean =
      one(sql, "SELECT count FROM timeout_counts WHERE match_id = ? AND uid = ?", matchId, uid) != null

    override fun writeTimeoutCount(matchId: String, uid: String, count: Int) {
      exec(
        sql,
        "INSERT INTO timeout_counts (match_id, uid, count) VALUES (?, ?, ?) " +
          "ON CONFLICT(match_id, uid) DO UPDATE SET count = excluded.count",
        matchId, uid, count,
      )
    }
  }
}
