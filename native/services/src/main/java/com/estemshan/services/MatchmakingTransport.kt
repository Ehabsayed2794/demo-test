package com.estemshan.services

import com.estemshan.engine.GameType

/**
 * S56 — the transport seam under the Ranked queue, and the vocabulary the
 * queue's answers are read in.
 *
 * **Why a seam.** This repo ships no Firebase fake by design, so the seam IS
 * the test hook: the poll loop — how long it waits, when it gives up, what a
 * denial does to the search — is pinned in a hermetic JVM test against a fake
 * transport, while the one class that touches the Firebase SDK
 * ([FirebaseMatchmakingTransport]) stays a single round trip per call.
 *
 * **What crosses it.** A GameType and nothing else. The uid the server acts on
 * is the callable's verified identity, so no transport method takes a player
 * id: a request that could name a player is a request that could name the
 * wrong one, and the server has no reason to trust it.
 */
interface MatchmakingTransport {

  /** `enqueueSearch` — asks the server to find a table for [gameType]. */
  suspend fun enqueue(gameType: GameType): MatchmakingState

  /** `pollSearch` — the caller's own state, and the queue's heartbeat. */
  suspend fun poll(): MatchmakingState

  /** `cancelSearch` — leaves the queue. Best-effort and idempotent. */
  suspend fun cancel()
}

/**
 * The queue's answer, read into a shape the port can act on.
 *
 * Every variant is a fact about the SERVER's state, never a guess: a
 * [Retryable] is the transport failing to reach the callable at all, which is
 * different from a [Denied], which is the server refusing — and only the
 * second one means the search cannot continue.
 */
sealed interface MatchmakingState {

  /**
   * Still in the queue.
   *
   * [retryAfterMillis] is the SERVER's own next-due instant — the cadence the
   * client re-arms on, instead of a local guess that would drift with clock
   * skew. RD9 publishes no wait estimate, so this is a delay, never an ETA.
   */
  data class Searching(val retryAfterMillis: Long) : MatchmakingState

  /** A table formed; [tableId] is the caller's own table. */
  data class Matched(val tableId: String) : MatchmakingState

  /**
   * The server says the caller has no live search — the queue never held one,
   * or its lease expired and the pass reclaimed it. Terminal: there is nothing
   * left to poll for.
   */
  data object Idle : MatchmakingState

  /**
   * The server refused, or answered a shape this build does not know.
   *
   * Terminal by design, and fail-closed: a response that did not say the
   * search is still queued has not said the search exists, so the port stops
   * rather than keep polling on a guess.
   */
  data object Denied : MatchmakingState

  /**
   * The callable could not be reached — no network, an interrupted await, or
   * the function could not be loaded. The search may well still be queued, so
   * this one is retried rather than answered.
   */
  data object Retryable : MatchmakingState
}

/**
 * The callable's answer → [MatchmakingState] mapping, split out of the
 * transport so both halves of the contract are pinned in a hermetic JVM test
 * rather than only against the Functions emulator — the same reason
 * [SeatReclaimResponse] and [RankDownJoinResponse] exist.
 *
 * Two things are proven here and nowhere else: which keys of the response the
 * client is allowed to know, and what a code or a shape this build has never
 * seen turns into. The answer carries the caller's own status and, once the
 * search resolved, the caller's own table — never a tier, a division, an RP
 * value, a queue size or another player's identity.
 */
object MatchmakingResponse {

  /**
   * The server's own answer.
   *
   * `MATCH_FOUND` needs a parsable table id: a match without one is not a
   * match, and answering "found" without it would hand the screen an id that
   * opens nothing. `SEARCHING` takes the server's retry delay when it carries
   * one and [fallbackRetryMillis] when it does not — a version-skew answer
   * still paces the poll, and a missing number is not a zero.
   *
   * Anything else — a payload that is not a map, an unknown status, a missing
   * table id — is [MatchmakingState.Denied]: the fail-closed direction.
   */
  fun forData(data: Any?, fallbackRetryMillis: Long): MatchmakingState {
    val fields = data as? Map<*, *> ?: return MatchmakingState.Denied
    return when (fields[STATUS]) {
      STATUS_MATCH_FOUND -> {
        val tableId = fields[TABLE_ID] as? String
        if (tableId.isNullOrEmpty()) MatchmakingState.Denied
        else MatchmakingState.Matched(tableId)
      }

      STATUS_SEARCHING -> {
        val retry = (fields[RETRY_AFTER] as? Number)?.toLong()
        MatchmakingState.Searching(retry?.takeIf { it > 0 } ?: fallbackRetryMillis)
      }

      STATUS_IDLE -> MatchmakingState.Idle
      else -> MatchmakingState.Denied
    }
  }

  /**
   * A transport code the callable refused with.
   *
   * The split is [CallableSeatReclaim]'s: a code that means "the function did
   * not get to answer" is retryable, and every other code is a refusal the
   * SERVER chose — a caller that is not signed in, a request the guard
   * refused, a profile that cannot be read — none of which a retry revisits.
   *
   * The decision is made on the code's NAME and delegated, because a JVM unit
   * test cannot initialize `FirebaseFunctionsException.Code` at all: its static
   * initializer needs the Android runtime.
   */
  fun forCodeName(name: String): MatchmakingState =
    if (name in RETRYABLE_CODES) MatchmakingState.Retryable else MatchmakingState.Denied

  /**
   * The three codes the poll may revisit. Named here, once, and mirrored as
   * literals in the test because no shared constant exists across modules.
   */
  private val RETRYABLE_CODES = setOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")

  private const val STATUS = "status"
  private const val TABLE_ID = "tableId"
  private const val RETRY_AFTER = "retryAfterMillis"

  private const val STATUS_IDLE = "IDLE"
  private const val STATUS_SEARCHING = "SEARCHING"
  private const val STATUS_MATCH_FOUND = "MATCH_FOUND"
}
