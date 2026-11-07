package com.estemshan.functions

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.MatchPoolKey
import com.estemshan.engine.MatchmakingSession
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.UNRANKED_FILL_MILLIS
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.isPoolCompatible
import com.estemshan.engine.isSearchCompatible
import com.estemshan.engine.poolTierOrder
import com.estemshan.engine.rankAt

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
 * ONE QUEUE, TWO MODES (S71)
 *
 * The owner ruled ONE mode-aware queue, not two queues: the Unranked pool is
 * the same queue with a different mode on the document, so a player who
 * searches Unranked and a player who searches Ranked share one collection,
 * one pass and one lifecycle.
 *
 * The mode is SERVER-DERIVED per search and written on the document, and it
 * is the only thing that separates the two pools:
 *
 *   RANKED   the document carries the tier the server derived from the
 *            caller's own profile, and the pool is the tiered one (RD9).
 *   UNRANKED the document carries NO tier at all — the pool is rank-blind,
 *            so there is no field to hold a rank. A document that carried
 *            one is a document this server cannot read, and the parse
 *            refuses it rather than ignoring it.
 *
 * That absence is structural, not a convention: the Unranked branch never
 * reads a profile, so a player with no ranked profile may still play
 * Unranked, and a bug that consulted a rank would have nothing to consult.
 *
 * A document written before S71 carries no `mode`: the queue's only mode
 * then was RANKED, so an absent mode reads as RANKED — a legacy document,
 * not a corrupt one. ROOM never pools, so a ROOM document is corrupt.
 *
 * ------------------------------------------------------------------------
 * THE DOCUMENT, AND WHY IT HAS EXACTLY THESE FIELDS
 *
 * `rankedSearches/{uid}`:
 *   uid         the searching player — equal to the document id, always
 *   mode        RANKED | UNRANKED — the pool this search belongs to
 *   tier        the server-derived tier — RANKED searches only
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
 * THE TABLE, AND WHO CREATES THE MATCH
 *
 * A pair forms a TABLE (`rooms/{roomId}`), written in the exact field shape
 * `RoomService.createRoom` writes — same collection, same keys, same values,
 * same defaults — so the frozen room schema, the client's own room parser and
 * `MatchService.startMatch` all accept it unchanged.
 *
 * The MATCH is created by `MatchService.startMatch` — by the client path, and
 * by nothing else. `isValidNewMatch()` demands a room whose every player is
 * ready and whose `matchId` is bound in the same write, which a resolver that
 * seats two players cannot honestly satisfy. A server-created match would be
 * a match the existing validation rejects, so the RANKED branch creates none.
 *
 * The UNRANKED branch is the ONE exception, and it exists because of what a
 * bot seat is: `isValidSeatMap()` demands `seats.keys().size() ==
 * players.size()` and every seat's uid to be one of the `players`, so a bot
 * seat cannot be written by a client at all. When an Unranked table's fill
 * window shuts with fewer than four humans, the CONFIGURED BOTS take the free
 * seats — and only the Admin SDK, which the rules do not bind, can write
 * them. The server therefore writes that match itself, at the close
 * ([UnrankedFill]).
 *
 * ------------------------------------------------------------------------
 * DIRECTIONALITY — CLOSED 2026-10-28 (O-15): THE BAND IS SYMMETRIC
 *
 * The owner ruled the pairing band **symmetric, exactly one tier, up or
 * down** — `docs/decisions/2026-10-28-matchmaking-owner-rulings.md`. So the
 * table rule here is absolute distance: the same tier, or exactly one tier
 * above or below, and never two. A Silver who searched first is served a
 * Gold exactly as a Gold who searched first is served a Silver.
 *
 * This does NOT relax RD9. `isPoolEligible` keeps its downward direction and
 * still answers "which tiers may a searcher of tier T be served from" — the
 * player never picks a higher pool. The resolver composes BOTH directions
 * ([tiersAreAdjacent]), which is the server forming the mixed match RD9
 * already permits ("do not write 'Gold players can only be matched in
 * Gold'"). The session's own absolute band stays the distance condition, so
 * the two rules agree by construction.
 *
 * Arrival order still decides the table's creator and the deterministic
 * order of the pass; it no longer decides whether a pair is legal.
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
internal const val QUEUE_FIELD_MODE = "mode"
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
 *
 * 30 s is the owner's ruling (2026-10-28): long enough that a normal poll
 * cadence of 3 s survives a slow round trip or a brief hiccup, short enough
 * that a dead seat does not strand a live partner for a minute. The boundary
 * is exact — exactly 30 s of silence is still a live search, 30 s and 1 ms is
 * not (`>`, not `>=`), pinned by
 * `theSearchLeaseIsThirtySecondsAndTheBoundaryIsExact`.
 */
private const val SEARCH_LEASE_MILLIS = 30_000L

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
 * writes the band in tiers, and divisions never enter the pool math. The
 * tier is NULL for an UNRANKED search — the rank-blind pool has no tier to
 * read, so there is no field to hold one — and non-null for a RANKED one.
 */
internal data class QueuedSearch(
  val uid: String,
  val mode: MatchMode,
  val tier: String?,
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
 * The request may carry a GameType and a mode and nothing else; the tier is
 * derived from the caller's own profile inside the same transaction that
 * writes the search, so a client can neither claim a tier nor be told one.
 * The write is the caller's own search document — one live search per
 * player — and the pass that follows is the queue's own matcher, not a
 * special favour for whoever just asked.
 *
 * The mode decides whether a profile is read at all: a RANKED search reads
 * the caller's profile (the tier IS the pool), an UNRANKED search reads
 * nothing (the pool is rank-blind), and a ROOM search never reaches here —
 * the request guard refuses it.
 */
suspend fun enqueueSearchOnce(
  db: MatchmakingSearchDb,
  request: CallableRequest,
  clock: DecisionClock,
): Map<String, Any?> {
  val uid = CallableAuth.requireUid(request)
  // The pre-queue guard (S57/S71): a forbidden identity/tier key is REFUSED
  // with permission-denied, never ignored, and an unknown key is refused too.
  val search = parseSearchRequest(request.data)

  db.transact { tx ->
    // Reads before writes, and the profile read is the first of them — but
    // only for a RANKED search. The Unranked pool is rank-blind, so there is
    // no tier to derive: a profile that cannot be read must not stop an
    // Unranked search, because a player with no ranked profile may still
    // play Unranked.
    val tier = if (search.mode == MatchMode.RANKED) {
      val profile = tx.readProfile(uid)
        ?: throw refusal(
          "failed-precondition",
          QueueReason.PROFILE_UNAVAILABLE,
          "enqueueSearch: no ranked profile exists for this account.",
        )
      tierOf(profile)
        ?: throw refusal(
          "failed-precondition",
          QueueReason.PROFILE_UNAVAILABLE,
          "enqueueSearch: the ranked profile for this account cannot be read.",
        )
    } else {
      null
    }
    val now = clock.nowMillis()
    val fields = linkedMapOf<String, Any?>(
      QUEUE_FIELD_UID to uid,
      QUEUE_FIELD_MODE to search.mode.name,
      QUEUE_FIELD_GAME_TYPE to search.gameType.name,
      QUEUE_FIELD_STATUS to QUEUE_STATUS_WAITING,
      QUEUE_FIELD_ENQUEUED_AT to now,
      QUEUE_FIELD_HEARTBEAT_AT to now,
    )
    // The tier is OMITTED for an Unranked search, never written as a null: a
    // document that carried a tier could not be rank-blind, and the parse
    // refuses one.
    if (tier != null) fields[QUEUE_FIELD_TIER] = tier
    tx.putSearch(uid, fields)
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
 * The pass's shared budget.
 *
 * One cap spans both mode branches: a pass that spent its whole budget on
 * Unranked tables has none left for Ranked, and vice versa. The cap counts
 * TRANSACTIONS — a transaction is the pass's real work, two reads and up to
 * three writes — so it is what actually bounds the pass, and the pairs cap
 * alone does not.
 */
internal class PassBudget(private val maxAttempts: Int) {

  /** How many transactions this pass has attempted. */
  var attempts: Int = 0
    private set

  /** How many tables this pass has formed. */
  var formed: Int = 0
    private set

  /** The uids this pass has seated — a search is seated at most once. */
  val seated: MutableSet<String> = mutableSetOf()

  /** Whether another transaction may be attempted. */
  fun allowsAnotherAttempt(): Boolean =
    attempts < maxAttempts && formed < MAX_PAIRS_PER_PASS

  /** Records one attempted transaction — a failed one costs its attempt too. */
  fun spendAttempt() {
    attempts++
  }

  /** Records one formed table. */
  fun recordTable() {
    formed++
  }
}

/**
 * One bounded resolver pass over the queue.
 *
 * The pass reads the queue, pairs what the policy allows, and forms one table
 * per pair. Every decision is re-derived INSIDE the transaction that writes
 * it, so a pass that races another pass finds the work already done and
 * writes nothing: the second table never happens.
 *
 * The pass is split by MODE (S71): the two pools are separate, and a search
 * is only ever seated with searches of its own mode. The Unranked branch
 * runs first — an open Unranked room carries a running fill window, while
 * the pool's own tables have nobody waiting on them yet.
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

  val budget = PassBudget(MAX_RESOLVE_ATTEMPTS_PER_PASS)

  // The Unranked branch: the open rooms' free seats first, then the pool's
  // own tables.
  resolveUnrankedPool(
    db,
    ordered.filter { it.mode == MatchMode.UNRANKED },
    now,
    budget,
  )

  // The Ranked branch: the pair loop, unchanged in every rule it applies.
  resolveRankedPairs(
    db,
    ordered.filter { it.mode == MatchMode.RANKED },
    now,
    budget,
  )

  val capped = budget.formed >= MAX_PAIRS_PER_PASS ||
    documents.size >= MAX_SCANNED_SEARCHES ||
    budget.attempts >= MAX_RESOLVE_ATTEMPTS_PER_PASS
  return SearchPassSummary(
    pairsFormed = budget.formed,
    scanned = documents.size,
    capped = capped,
    nextRetryAfterMillis = if (capped) CAPPED_RETRY_MILLIS else SEARCH_RETRY_MILLIS,
  )
}

/**
 * The RANKED branch of one pass — the pair loop.
 *
 * Every later search is a candidate. The pair is considered exactly once,
 * with the earlier half becoming the table's creator (O-15: which half is
 * earlier no longer decides whether the pair is legal).
 */
private suspend fun resolveRankedPairs(
  db: MatchmakingSearchDb,
  ranked: List<QueuedSearch>,
  now: Long,
  budget: PassBudget,
) {
  for (hostIndex in ranked.indices) {
    if (!budget.allowsAnotherAttempt()) break
    val host = ranked[hostIndex]
    if (host.uid in budget.seated) continue
    for (guestIndex in hostIndex + 1 until ranked.size) {
      if (!budget.allowsAnotherAttempt()) break
      val guest = ranked[guestIndex]
      if (guest.uid in budget.seated) continue
      if (!isPairable(host, guest, now)) continue
      budget.spendAttempt()
      val tableId = resolvePair(db, host, guest, now)
      if (tableId == null) continue
      budget.recordTable()
      budget.seated += host.uid
      budget.seated += guest.uid
      break
    }
  }
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
 * The pairing rule — RD9's pool (both directions), GM5's GameType, and the
 * absolute band.
 *
 * O-15 (closed 2026-10-28) makes the band SYMMETRIC: the same tier, or
 * exactly one tier up OR down, and never two. The engine owns that rule
 * ([isPoolCompatible], over the two searches' mode-aware pool keys), so the
 * resolver does not restate it: there is exactly one definition of "one
 * tier" in the codebase.
 *
 * The session's [MatchmakingSession.bandAccepts] is the same distance
 * condition applied to the seats already at the table, so the two rules
 * cannot disagree.
 *
 * GM5 rides alongside and is orthogonal: the GameType never widens or narrows
 * the tier pool, it only decides whether two searches may be in the same
 * table at all — exact equality, so a Full and a Mini never resolve together.
 *
 * The tier is null only for an UNRANKED search, and an Unranked search never
 * reaches this rule — the Unranked branch is a table of up to four, not a
 * pair. The null is refused rather than guessed, so a bug that sent an
 * Unranked search here would form no table instead of a wrong one.
 */
private fun isPairable(host: QueuedSearch, guest: QueuedSearch, now: Long): Boolean {
  // Never a player with itself.
  if (host.uid == guest.uid) return false
  // GM5: exact GameType equality.
  if (!isSearchCompatible(host.gameType, guest.gameType)) return false
  val hostTier = host.tier ?: return false
  val guestTier = guest.tier ?: return false
  // The absolute band: the table may span at most one tier.
  val session = MatchmakingSession.open(host.uid, hostTier, now)
  if (session.join(guest.uid, guestTier, now).humans.size < 2) return false
  // O-15: one tier apart is a pair in either direction, and two searches in
  // different modes never pair — the engine's own rule.
  return isPoolCompatible(
    MatchPoolKey.Tiered(hostTier),
    MatchPoolKey.Tiered(guestTier),
  )
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

    tx.createRoom(tableId, rankedTableFields(freshHost, freshGuest))
    tx.resolveSearch(freshHost.uid, tableId)
    tx.resolveSearch(freshGuest.uid, tableId)
    tableId
  }
}

/**
 * The table a RANKED pair forms, in `RoomService.createRoom`'s exact field
 * shape.
 *
 * Same collection, same keys, same values, same defaults — so the frozen
 * room schema, the client's own room parser and `MatchService.startMatch`
 * accept it unchanged, and the resolver is not a second, divergent room
 * writer. RD21's bot trio and GM7's scoring mode are the defaults the
 * existing path already uses; the GameType is the search's own.
 */
private fun rankedTableFields(host: QueuedSearch, guest: QueuedSearch): Map<String, Any?> = linkedMapOf(
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
internal fun parseSearchDocument(id: String, fields: Map<String, Any?>): QueuedSearch? {
  val status = statusOf(fields)
  if (status == QUEUE_STATUS_RESOLVED) return null

  // The document must speak for itself: its own uid field must be the id it
  // is stored under, or the queue is not describing who is waiting.
  val uid = fields[QUEUE_FIELD_UID] as? String ?: corruptQueue()
  if (uid != id) corruptQueue()
  // A blank uid is not a player, and the session's own open() would fail
  // loudly on it — so it is refused here, with the queue's own refusal.
  if (uid.isBlank()) corruptQueue()

  // The mode. A document written before S71 carries none: the queue's only
  // mode then was RANKED, so an absent mode reads as RANKED — a legacy
  // document, not a corrupt one. A mode this server does not know is a
  // document it cannot read, and so is ROOM, which never pools.
  val mode = when (val raw = fields[QUEUE_FIELD_MODE]) {
    null -> MatchMode.RANKED
    is String -> MatchMode.values().firstOrNull { it.name == raw }
      ?.takeIf { it != MatchMode.ROOM }
      ?: corruptQueue()
    else -> corruptQueue()
  }

  // The tier. A RANKED document carries the tier the server derived; an
  // UNRANKED document carries NONE — the rank-blind pool has no tier, and a
  // document that carried one could not be rank-blind, so it is refused
  // rather than ignored.
  val tier = when (mode) {
    MatchMode.RANKED -> {
      val stored = fields[QUEUE_FIELD_TIER] as? String ?: corruptQueue()
      if (stored !in poolTierOrder) corruptQueue()
      stored
    }
    MatchMode.UNRANKED -> {
      if (fields.containsKey(QUEUE_FIELD_TIER)) corruptQueue()
      null
    }
    MatchMode.ROOM -> corruptQueue()
  }

  val gameTypeName = fields[QUEUE_FIELD_GAME_TYPE] as? String ?: corruptQueue()
  val gameType = GameType.values().firstOrNull { it.name == gameTypeName } ?: corruptQueue()

  val enqueuedAt = timestampToMillis(fields[QUEUE_FIELD_ENQUEUED_AT]) ?: corruptQueue()
  val heartbeatAt = timestampToMillis(fields[QUEUE_FIELD_HEARTBEAT_AT]) ?: corruptQueue()

  return QueuedSearch(
    uid = uid,
    mode = mode,
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

    else -> {
      val answer = linkedMapOf<String, Any?>(
        "ok" to true,
        "status" to ANSWER_SEARCHING,
        "retryAfterMillis" to retryAfterMillis,
      )
      fillDeadlineOf(document)?.let { answer["fillDeadlineMillis"] = it }
      answer
    }
  }
}

/**
 * The deadline this search's seat is filled by — its own arrival plus the
 * Unranked fill window.
 *
 * It is the SEARCH's own window, and it is a bound and not an estimate: the
 * table this search joins may fill SOONER (a table's window is anchored on
 * its first searcher, so a table this search joins second closes earlier),
 * never later. A RANKED search has no such deadline — the Ranked queue forms
 * a table of two, and the fill window is not its rule — so none is
 * published.
 */
private fun fillDeadlineOf(document: Map<String, Any?>): Long? {
  if (document[QUEUE_FIELD_MODE] != MatchMode.UNRANKED.name) return null
  val enqueuedAt = timestampToMillis(document[QUEUE_FIELD_ENQUEUED_AT]) ?: return null
  return enqueuedAt + UNRANKED_FILL_MILLIS
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
