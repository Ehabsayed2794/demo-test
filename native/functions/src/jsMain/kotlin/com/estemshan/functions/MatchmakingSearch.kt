package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.MatchmakingSession
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.isPoolEligible
import com.estemshan.engine.isSearchCompatible
import com.estemshan.engine.poolTierOrder
import com.estemshan.engine.rankAt
import com.estemshan.engine.rankLadder

/**
 * S56 — the Ranked queue: the server-only search document, the bounded
 * resolver pass, and the three callables that drive them.
 *
 * ------------------------------------------------------------------------
 * WHAT THE SERVER OWNS, AND WHY THE CLIENT OWNS NOTHING
 *
 * RD9 makes the eligible tier pool server-derived: the client may ask to be
 * matched and may say which GameType it wants (GM5), and it may never name a
 * tier, a division, a rank, an RP value, a pool, or an identity. So the tier
 * this file pools on is read from the CALLER'S OWN PROFILE, inside the
 * transaction that writes the search — never from the request, and never from
 * anything the client could have chosen.
 *
 * The queue is `rankedSearches/{uid}`, a top-level collection with NO
 * `firestore.rules` block: the catch-all deny makes it admin-only by
 * construction, exactly like `timeoutCounts`, `inactivity`, `inactiveTakeovers`
 * and `disconnectVotes`. Clients observe it only through the callables below,
 * so `firestore.rules` stays byte-identical for S56.
 *
 * ------------------------------------------------------------------------
 * THE DOCUMENT, AND WHY IT HAS EXACTLY THESE FIELDS
 *
 * `rankedSearches/{uid}`:
 *   uid         the searching player — equal to the document id, always
 *   tier        the server-derived tier the pool is computed from
 *   gameType    the search's own GameType (GM5 — orthogonal to the pool)
 *   status      WAITING | RESOLVED
 *   enqueuedAt  the server's arrival stamp — the queue's ordering key
 *   heartbeatAt the lease: the client is still there
 *   tableId     the terminal outcome pointer — the table this search formed
 *
 * Nothing else is stored, because the resolver reads nothing else. There is
 * no queue-size counter, no wait estimate and no ETA field: RD9 publishes no
 * wait estimate, and a field nobody reads is a field that can only rot.
 *
 * ------------------------------------------------------------------------
 * THE TABLE, AND WHY NO MATCH IS EVER CREATED HERE
 *
 * A pair forms a TABLE (`rooms/{roomId}`), written in the exact field shape
 * `RoomService.createRoom` writes — same collection, same keys, same values,
 * same defaults — so the frozen room schema, the client's own room parser and
 * `MatchService.startMatch` all accept it unchanged. The MATCH is created by
 * that existing path and by nothing else: `isValidNewMatch()` demands a room
 * whose every player is ready and whose `matchId` is bound in the same
 * write, which a resolver that seats two players cannot honestly satisfy. A
 * server-created match would be a match the existing validation rejects, so
 * this file creates none.
 *
 * ------------------------------------------------------------------------
 * DIRECTIONALITY — THE ONE OPEN QUESTION THIS FILE DOES NOT CLOSE
 *
 * RD9's pool is directional: a Gold searcher's pool admits a Silver
 * candidate, and a Silver searcher's pool never admits a Gold one. The
 * resolver composes the engine's own two rules — `isPoolEligible(searcher,
 * candidate)` and the session's absolute band — with the EARLIER searcher (in
 * the deterministic queue order) as the pool owner, which is the composition
 * `MatchmakingSession`'s own KDoc prescribes. A Gold who searched first is
 * served a Silver; a Silver who searched first is never served a Gold.
 *
 * That is the "leaves the code as-is" branch of O-15, the open question
 * whether the band is symmetric. An owner ruling of "symmetric" changes ONE
 * composition site (which searcher owns the pool) and nothing else here.
 *
 * ------------------------------------------------------------------------
 * IDEMPOTENCY
 *
 * A search resolves AT MOST ONCE. The resolution is a WAITING → RESOLVED
 * transition guarded by an in-transaction re-read of BOTH documents, so a
 * second pass over the same queue state finds both already RESOLVED and
 * writes nothing: no second table, no second match, no second room. Every
 * read precedes every write inside every transaction, as Firestore requires.
 */

// ── the queue document's fields ───────────────────────────────────────────

internal const val QUEUE_FIELD_UID = "uid"
internal const val QUEUE_FIELD_TIER = "tier"
internal const val QUEUE_FIELD_GAME_TYPE = "gameType"
internal const val QUEUE_FIELD_STATUS = "status"
internal const val QUEUE_FIELD_ENQUEUED_AT = "enqueuedAt"
internal const val QUEUE_FIELD_HEARTBEAT_AT = "heartbeatAt"
internal const val QUEUE_FIELD_TABLE_ID = "tableId"

/** The profile's own fields — the tier the pool is derived from. */
private const val PROFILE_TIER = "tier"
private const val PROFILE_RP = "rp"

internal const val QUEUE_STATUS_WAITING = "WAITING"
internal const val QUEUE_STATUS_RESOLVED = "RESOLVED"

/** The lifecycle the client sees — the queue document's own status, plus the
 *  one state a client can be in without a search at all. */
private const val ANSWER_IDLE = "IDLE"
private const val ANSWER_SEARCHING = "SEARCHING"
private const val ANSWER_MATCH_FOUND = "MATCH_FOUND"

/**
 * How long a search stays in the queue without a heartbeat.
 *
 * The lease is what keeps a client that vanished — a closed app, a dead
 * network — from occupying a slot in the queue for ever, and from being
 * paired into a table it will never join. A client that polls renews it; a
 * client that stopped is simply not there any more, and the pass reclaims
 * its document.
 */
private const val SEARCH_LEASE_MILLIS = 60_000L

/**
 * The cadence the server hands a waiting client: how long until it should
 * come back. The client re-arms on the server's number instead of guessing
 * one — the same contract the disconnect resolver's `retryAfterMillis` is.
 * It is the server's next-due delay, NOT an estimate of when a table will
 * form: RD9 publishes no wait estimate.
 */
private const val SEARCH_RETRY_MILLIS = 3_000L

/**
 * The retry a CAPPED pass asks for: work is still waiting, so the next poll
 * comes back soon rather than at the full cadence.
 */
private const val CAPPED_RETRY_MILLIS = 500L

/** How many queue documents one pass may read. */
private const val MAX_SCANNED_SEARCHES = 100

/** How many pairs one pass may form. */
private const val MAX_PAIRS_PER_PASS = 10

/**
 * How many pair-forming transactions one pass may ATTEMPT.
 *
 * A transaction is the pass's real work — two reads and up to three writes —
 * so this is the cap that actually bounds it. The pairs cap alone does not: a
 * pass that loses a race is answered "no table", keeps its searcher in the
 * queue and moves on to the next candidate, so a contended pass would
 * otherwise spend one transaction per candidate pair in its window and could
 * run past the function's own deadline. The pass that runs out of attempts
 * simply leaves the rest of the queue to the next one.
 */
internal const val MAX_RESOLVE_ATTEMPTS_PER_PASS = 20

/**
 * The stable `details.reason` tokens these callables answer with.
 *
 * The two the queue owns are here; the two the request guard owns are
 * [RequestReason]'s, so one vocabulary spans all three callables.
 */
internal object QueueReason {
  const val PROFILE_UNAVAILABLE = "PROFILE_UNAVAILABLE"
  const val QUEUE_UNREADABLE = "QUEUE_UNREADABLE"
}

/**
 * One queue document, parsed.
 *
 * The parsed form carries the tier as a TIER NAME, never a division: RD9
 * writes the band in tiers, and divisions never enter the pool math.
 */
private data class QueuedSearch(
  val uid: String,
  val tier: String,
  val gameType: GameType,
  val enqueuedAtMillis: Long,
  val heartbeatAtMillis: Long,
)

/** What one resolver pass did. */
internal data class SearchPassSummary(
  /** How many tables this pass formed. */
  val pairsFormed: Int,
  /** How many queue documents this pass read. */
  val scanned: Int,
  /** Whether the pass stopped at its work cap and more is waiting. */
  val capped: Boolean,
  /** The server's own next-due delay for the client that triggered the pass. */
  val nextRetryAfterMillis: Long,
)

// ── the callables ─────────────────────────────────────────────────────────

/**
 * `enqueueSearch` — asks the server to find a table.
 *
 * The request may carry a GameType and nothing else; the tier is derived from
 * the caller's own profile inside the same transaction that writes the search,
 * so a client can neither claim a tier nor be told one. The write is the
 * caller's own search document — one live search per player — and the pass
 * that follows is the queue's own matcher, not a special favour for whoever
 * just asked.
 */
suspend fun enqueueSearchOnce(
  db: MatchmakingSearchDb,
  request: CallableRequest,
  clock: DecisionClock,
): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)
  // The pre-queue guard (S57): a forbidden identity/tier key is REFUSED with
  // permission-denied, never ignored, and an unknown key is refused too.
  val search = parseRankedSearchRequest(request.data)

  db.transact { tx ->
    // Reads before writes, and the profile read is the first of them: the tier
    // is the pool's input, so a profile that cannot be read must stop the
    // enqueue before a search document exists.
    val profile = tx.readProfile(uid)
      ?: throw refusal(
        "failed-precondition",
        QueueReason.PROFILE_UNAVAILABLE,
        "enqueueSearch: no ranked profile exists for this account.",
      )
    val tier = tierOf(profile)
      ?: throw refusal(
        "failed-precondition",
        QueueReason.PROFILE_UNAVAILABLE,
        "enqueueSearch: the ranked profile for this account cannot be read.",
      )
    val now = clock.nowMillis()
    tx.putSearch(
      uid,
      linkedMapOf<String, Any?>(
        QUEUE_FIELD_UID to uid,
        QUEUE_FIELD_TIER to tier,
        QUEUE_FIELD_GAME_TYPE to search.gameType.name,
        QUEUE_FIELD_STATUS to QUEUE_STATUS_WAITING,
        QUEUE_FIELD_ENQUEUED_AT to now,
        QUEUE_FIELD_HEARTBEAT_AT to now,
      ),
    )
  }

  val pass = runSearchPass(db, clock)
  return answerOf(db.readSearch(uid), pass.nextRetryAfterMillis)
}

/**
 * `pollSearch` — the caller's own state, and the queue's heartbeat.
 *
 * The caller learns only about ITSELF: its status, and — once the search
 * resolved — the table it was placed in. No other player's identity, tier or
 * search is reachable from this callable, and none is returned.
 *
 * The poll also runs a resolver pass, because the queue is host-less: the
 * waiting clients are the ones that drive it, exactly as the disconnect
 * resolver's poll drives that flow. A client that has no search at all still
 * polls; it simply has nothing to be told.
 */
suspend fun pollSearchOnce(
  db: MatchmakingSearchDb,
  request: CallableRequest,
  clock: DecisionClock,
): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)
  requireNoClientFields(request.data, "pollSearch")

  val pass = runSearchPass(db, clock)

  // The lease renewal and the answer come from ONE in-transaction read, so a
  // search that resolved while the pass was running is reported resolved and
  // is NOT given a fresh lease — a finished search has no lease to renew.
  val answer = db.transact { tx ->
    val document = tx.readSearch(uid)
    if (document != null && statusOf(document) == QUEUE_STATUS_WAITING) {
      tx.renewSearch(uid, clock.nowMillis())
    }
    document
  }
  return answerOf(answer, pass.nextRetryAfterMillis)
}

/**
 * `cancelSearch` — leaves the queue.
 *
 * Idempotent: cancelling a search that never started, or one that was
 * already cancelled, is a no-op. A RESOLVED search is NOT un-resolved: the
 * table it formed exists, and a cancel cannot take it back — the answer says
 * so instead of pretending the search is still open.
 */
suspend fun cancelSearchOnce(
  db: MatchmakingSearchDb,
  request: CallableRequest,
): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)
  requireNoClientFields(request.data, "cancelSearch")

  val answer = db.transact { tx ->
    val document = tx.readSearch(uid)
    when {
      document == null -> CancelAnswer(ANSWER_IDLE, null, true)
      statusOf(document) == QUEUE_STATUS_RESOLVED -> CancelAnswer(ANSWER_MATCH_FOUND, tableIdOf(document), false)
      else -> {
        tx.deleteSearch(uid)
        CancelAnswer(ANSWER_IDLE, null, true)
      }
    }
  }
  return linkedMapOf(
    "ok" to true,
    "cancelled" to answer.cancelled,
    "status" to answer.status,
  )
}

/** What a cancellation did — the answer, and whether the search left. */
private data class CancelAnswer(
  val status: String,
  val tableId: String?,
  val cancelled: Boolean,
)

// ── the resolver pass ─────────────────────────────────────────────────────

/**
 * One bounded resolver pass over the queue.
 *
 * The pass reads the queue, pairs what the policy allows, and forms one table
 * per pair. Every decision is re-derived INSIDE the transaction that writes
 * it, so a pass that races another pass finds the work already done and
 * writes nothing: the second table never happens.
 *
 * The work is capped — [MAX_SCANNED_SEARCHES] documents read,
 * [MAX_PAIRS_PER_PASS] pairs formed, [MAX_RESOLVE_ATTEMPTS_PER_PASS]
 * transactions attempted — and the cap is reported, so the caller comes back
 * sooner rather than letting one pass grow without bound.
 */
internal suspend fun runSearchPass(
  db: MatchmakingSearchDb,
  clock: DecisionClock,
): SearchPassSummary {
  val now = clock.nowMillis()
  val documents = db.listWaitingSearches(MAX_SCANNED_SEARCHES)

  val waiting = ArrayList<QueuedSearch>(documents.size)
  val expired = ArrayList<String>()
  for (document in documents) {
    val parsed = parseSearchDocument(document.id, document.fields) ?: continue
    // A search whose lease has run out is not waiting any more — the client
    // that owned it is gone. Its document is reclaimed below, so a vanished
    // client cannot hold a slot in the queue's window for ever.
    if (now - parsed.heartbeatAtMillis > SEARCH_LEASE_MILLIS) {
      expired += parsed.uid
      continue
    }
    waiting += parsed
  }
  reclaimExpired(db, expired, now)

  // The queue's order: arrival first, uid as the tie-break. Two passes over
  // the same queue state therefore see the same order and form the same
  // pairs — the resolver has no randomness in it at all.
  val ordered = waiting.sortedWith(compareBy({ it.enqueuedAtMillis }, { it.uid }))

  var formed = 0
  var attempts = 0
  val taken = HashSet<String>()
  for (hostIndex in ordered.indices) {
    if (attempts >= MAX_RESOLVE_ATTEMPTS_PER_PASS) break
    val host = ordered[hostIndex]
    if (host.uid in taken) continue
    // The pool owner is the EARLIER searcher, so only the searches that came
    // after it are candidates: a pair is considered exactly once, with its
    // earlier half owning the pool.
    for (guestIndex in hostIndex + 1 until ordered.size) {
      if (attempts >= MAX_RESOLVE_ATTEMPTS_PER_PASS) break
      val guest = ordered[guestIndex]
      if (guest.uid in taken) continue
      if (!isPairable(host, guest, now)) continue
      attempts++
      val tableId = resolvePair(db, host, guest, now)
      if (tableId == null) continue
      formed++
      taken += host.uid
      taken += guest.uid
      break
    }
    if (formed >= MAX_PAIRS_PER_PASS) break
  }

  val capped = formed >= MAX_PAIRS_PER_PASS ||
    documents.size >= MAX_SCANNED_SEARCHES ||
    attempts >= MAX_RESOLVE_ATTEMPTS_PER_PASS
  return SearchPassSummary(
    pairsFormed = formed,
    scanned = documents.size,
    capped = capped,
    nextRetryAfterMillis = if (capped) CAPPED_RETRY_MILLIS else SEARCH_RETRY_MILLIS,
  )
}

/**
 * Removes the documents whose lease ran out.
 *
 * The lease is only worth having if it reclaims the seat: a client that
 * vanished leaves a document behind, and without this the document would sit
 * in the queue's window for ever, hiding live searches behind it.
 *
 * One transaction, all reads before all writes: each document is re-read and
 * re-judged inside it, so a search that was renewed between the scan and the
 * sweep is NOT deleted — the client is still there.
 */
private suspend fun reclaimExpired(
  db: MatchmakingSearchDb,
  uids: List<String>,
  now: Long,
) {
  if (uids.isEmpty()) return
  db.transact { tx ->
    val expired = ArrayList<String>()
    for (uid in uids) {
      val document = tx.readSearch(uid) ?: continue
      if (statusOf(document) != QUEUE_STATUS_WAITING) continue
      val heartbeat = timestampToMillis(document[QUEUE_FIELD_HEARTBEAT_AT]) ?: corruptQueue()
      if (now - heartbeat > SEARCH_LEASE_MILLIS) expired += uid
    }
    for (uid in expired) tx.deleteSearch(uid)
  }
}

/**
 * The pairing rule — RD9's pool, GM5's GameType, and the absolute band.
 *
 * The EARLIER searcher owns the pool: its pool must admit the later one.
 * That is the directionality RD9 states — a Gold searcher may be served a
 * Silver, a Silver searcher is never served a Gold — and it is the
 * composition `MatchmakingSession`'s own KDoc prescribes:
 * `isPoolEligible(searcher, candidate) && session.bandAccepts(candidate.tier)`.
 *
 * GM5 rides alongside and is orthogonal: the GameType never widens or narrows
 * the tier pool, it only decides whether two searches may be in the same
 * table at all — exact equality, so a Full and a Mini never resolve together.
 */
private fun isPairable(host: QueuedSearch, guest: QueuedSearch, now: Long): Boolean {
  // Never a player with itself.
  if (host.uid == guest.uid) return false
  // GM5: exact GameType equality.
  if (!isSearchCompatible(host.gameType, guest.gameType)) return false
  // The absolute band: the table may span at most one tier.
  val session = MatchmakingSession.open(host.uid, host.tier, now)
  if (session.join(guest.uid, guest.tier, now).humans.size < 2) return false
  // RD9's direction: the pool owner's pool must admit the candidate.
  return isPoolEligible(rungOf(host.tier), rungOf(guest.tier))
}

/**
 * Forms one table for a pair, inside one transaction.
 *
 * The two search documents are read again inside the transaction, and the
 * pairing rule is re-applied to what was read: a pass that raced another
 * pass, or a caller that cancelled while the pass was running, is answered
 * with "no table" and writes nothing at all.
 *
 * The table id is minted BEFORE the transaction, so a transaction retry keeps
 * the same id — a retry can never leave two tables behind.
 */
private suspend fun resolvePair(
  db: MatchmakingSearchDb,
  host: QueuedSearch,
  guest: QueuedSearch,
  now: Long,
): String? {
  val tableId = db.newRoomId()
  return db.transact { tx ->
    // Both reads, before any write.
    val hostDocument = tx.readSearch(host.uid)
    val guestDocument = tx.readSearch(guest.uid)
    if (hostDocument == null || guestDocument == null) return@transact null
    val freshHost = parseSearchDocument(host.uid, hostDocument) ?: return@transact null
    val freshGuest = parseSearchDocument(guest.uid, guestDocument) ?: return@transact null
    if (!isPairable(freshHost, freshGuest, now)) return@transact null

    tx.createRoom(tableId, tableFields(freshHost, freshGuest))
    tx.resolveSearch(freshHost.uid, tableId)
    tx.resolveSearch(freshGuest.uid, tableId)
    tableId
  }
}

/**
 * The table a pair forms, in `RoomService.createRoom`'s exact field shape.
 *
 * Same collection, same keys, same values, same defaults — so the frozen
 * room schema, the client's own room parser and `MatchService.startMatch`
 * accept it unchanged, and the resolver is not a second, divergent room
 * writer. RD21's bot trio and GM7's scoring mode are the defaults the
 * existing path already uses; the GameType is the search's own.
 */
private fun tableFields(host: QueuedSearch, guest: QueuedSearch): Map<String, Any?> = linkedMapOf(
  "name" to null,
  "status" to "waiting",
  "creator" to host.uid,
  "players" to listOf(host.uid, guest.uid),
  "readyPlayers" to emptyList<String>(),
  "gameType" to host.gameType.name,
  "scoringMode" to ScoringMode.NORMAL.name,
  "mode" to MatchMode.RANKED.name,
  "rankDown" to false,
  "botTier" to BotTier.MEDIUM.name,
  "botPersonality" to BotPersonality.BALANCED.name,
  "decisionTimerSeconds" to 15,
)

// ── the tier the pool is derived from ─────────────────────────────────────

/**
 * The caller's tier, read from their own profile — never from the request.
 *
 * A profile that names no tier falls back to its RP, and a profile whose
 * tier and RP disagree is CORRUPT rather than silently resolved: the tier
 * and the RP name the same rung (RD3), so a disagreement is a document this
 * server cannot read, and a pool built on it would be a guess. Divisions
 * never enter the pool math, so they never enter this check either.
 */
private fun tierOf(profile: Map<String, Any?>): String? {
  val storedTier = profile[PROFILE_TIER] as? String
  val rp = (profile[PROFILE_RP] as? Number)?.toDouble()
  if (rp != null && (!rp.isFinite() || rp < 0.0 || rp % 1.0 != 0.0)) return null
  val rpTier = rp?.let { rankAt(it.toInt()).tier }

  if (storedTier != null) {
    if (storedTier !in poolTierOrder) return null
    if (rpTier != null && rpTier != storedTier) return null
    return storedTier
  }
  // No tier on the profile: the RP alone decides, and a profile with neither
  // cannot be pooled on.
  return rpTier
}

/** A ladder rung for a tier name — the pool math is in tiers, never divisions. */
private fun rungOf(tier: String) = rankLadder.first { it.tier == tier }

// ── parsing the queue ─────────────────────────────────────────────────────

/**
 * One queue document, parsed — or null when the document is a RESOLVED
 * search, which is not a candidate any more.
 *
 * A document this server cannot read ABORTS the pass. It is never repaired by
 * guessing: a queue whose documents disagree with the model is a queue the
 * server does not understand, and continuing would mean pairing on a tier it
 * had to invent. The abort carries a fixed message — never a stored value and
 * never a player's identity.
 */
private fun parseSearchDocument(id: String, fields: Map<String, Any?>): QueuedSearch? {
  val status = statusOf(fields)
  if (status == QUEUE_STATUS_RESOLVED) return null

  // The document must speak for itself: its own uid field must be the id it
  // is stored under, or the queue is not describing who is waiting.
  val uid = fields[QUEUE_FIELD_UID] as? String ?: corruptQueue()
  if (uid != id) corruptQueue()
  // A blank uid is not a player, and the session's own open() would fail
  // loudly on it — so it is refused here, with the queue's own refusal.
  if (uid.isBlank()) corruptQueue()

  val tier = fields[QUEUE_FIELD_TIER] as? String ?: corruptQueue()
  if (tier !in poolTierOrder) corruptQueue()

  val gameTypeName = fields[QUEUE_FIELD_GAME_TYPE] as? String ?: corruptQueue()
  val gameType = GameType.values().firstOrNull { it.name == gameTypeName } ?: corruptQueue()

  val enqueuedAt = timestampToMillis(fields[QUEUE_FIELD_ENQUEUED_AT]) ?: corruptQueue()
  val heartbeatAt = timestampToMillis(fields[QUEUE_FIELD_HEARTBEAT_AT]) ?: corruptQueue()

  return QueuedSearch(
    uid = uid,
    tier = tier,
    gameType = gameType,
    enqueuedAtMillis = enqueuedAt,
    heartbeatAtMillis = heartbeatAt,
  )
}

/**
 * The document's status — or the corrupt-queue abort.
 *
 * A status this server does not know is NOT a status it may guess at: a
 * document that says "PENDING" is either a search that is not a search yet or
 * a document from a future this server does not understand, and answering
 * "still searching" for it would tell a client to keep waiting for something
 * nobody will ever do.
 */
private fun statusOf(fields: Map<String, Any?>): String {
  val status = fields[QUEUE_FIELD_STATUS] as? String ?: corruptQueue()
  if (status != QUEUE_STATUS_WAITING && status != QUEUE_STATUS_RESOLVED) corruptQueue()
  return status
}

/** The table this search resolved to, when it names one. */
private fun tableIdOf(fields: Map<String, Any?>): String? = fields[QUEUE_FIELD_TABLE_ID] as? String

/** Aborts the pass: a fixed message, no stored value and no identity. */
private fun corruptQueue(): Nothing = throw refusal(
  "failed-precondition",
  QueueReason.QUEUE_UNREADABLE,
  "The ranked queue holds a document this server cannot read.",
)

// ── the answers ───────────────────────────────────────────────────────────

/**
 * The caller's own state.
 *
 * The projection is the caller's own search and nothing else: its status and,
 * once it resolved, the table it was placed in. No tier, no division, no RP,
 * no other player's identity — and no queue size or wait estimate, because
 * RD9 publishes no estimate.
 */
private fun answerOf(document: Map<String, Any?>?, retryAfterMillis: Long): Map<String, Any?> {
  if (document == null) {
    return linkedMapOf("ok" to true, "status" to ANSWER_IDLE)
  }
  return when (statusOf(document)) {
    QUEUE_STATUS_RESOLVED -> linkedMapOf(
      "ok" to true,
      "status" to ANSWER_MATCH_FOUND,
      "tableId" to tableIdOf(document),
    )

    else -> linkedMapOf(
      "ok" to true,
      "status" to ANSWER_SEARCHING,
      "retryAfterMillis" to retryAfterMillis,
    )
  }
}

// ── the request guard for the callables that take no fields ───────────────

/**
 * `pollSearch` and `cancelSearch` carry no field at all: the caller is the
 * only input. A field that arrives is refused rather than ignored — a client
 * that sends `uid` and is answered "ok" believes it chose the identity it
 * was served under.
 */
private fun requireNoClientFields(data: Any?, callable: String) {
  val fields = dynamicToKotlin(data)
  if (fields == null) return
  if (fields !is Map<*, *>) {
    throw refusal(
      "invalid-argument",
      RequestReason.INVALID_ARGUMENT,
      "$callable: the request must be an object with no fields.",
    )
  }
  for (key in fields.keys) {
    val name = key as? String
      ?: throw refusal(
        "invalid-argument",
        RequestReason.INVALID_ARGUMENT,
        "$callable: request field names must be strings.",
      )
    if (name in FORBIDDEN_REQUEST_KEYS) {
      throw refusal(
        "permission-denied",
        RequestReason.FORBIDDEN_KEY,
        "identity and tier are server-derived; '$name' is not accepted.",
      )
    }
    throw refusal(
      "invalid-argument",
      RequestReason.INVALID_ARGUMENT,
      "$callable: unknown field '$name'.",
    )
  }
}

private fun refusal(code: String, reason: String, message: String): HttpsError = HttpsError(
  code,
  message,
  // toPlain, not the Kotlin map: JSON.stringify of a Kotlin map yields `{}`, so
  // an unconverted `details` would reach the client as an empty object and the
  // stable reason would be lost precisely where the client needs it.
  toPlain(linkedMapOf<String, Any?>("reason" to reason)),
)
