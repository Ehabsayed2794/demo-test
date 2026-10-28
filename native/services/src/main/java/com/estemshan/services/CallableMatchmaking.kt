package com.estemshan.services

import com.estemshan.engine.GameType
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

/**
 * S56 — the production [MatchmakingPort], over the `enqueueSearch`,
 * `pollSearch` and `cancelSearch` callables.
 *
 * **What this class sends.** A GameType, and nothing else. RD9 makes the
 * eligible pool server-derived, so there is no tier here to compute, none to
 * send and none to read back: the answers carry a status and, once the search
 * resolved, the caller's own table. The uid the server acts on is the
 * callable's VERIFIED identity — `playerId` is therefore not put on the wire,
 * and there is nothing in this file that could derive a tier even by accident.
 *
 * **The search lifecycle.** Enqueue once, then poll on the SERVER's cadence
 * until it resolves, is refused, or the budget runs out. The queue is
 * host-less — the waiting clients are the ones that drive it, exactly as the
 * disconnect resolver's poll drives that flow — so the poll is not a courtesy
 * to the server; it IS the server's work.
 *
 * **The budget.** The client bounds its own wait, because the server publishes
 * no ETA (RD9) and the screen cannot show a spinner for ever. When the budget
 * runs out the search is CANCELLED server-side before the terminal event: a
 * player who gave up must not still be sitting in the queue, waiting to be
 * seated in a table they left.
 *
 * A transport failure is retried inside the budget, because the search may
 * well still be queued; a refusal is terminal, because the server has already
 * decided. The one exception is the enqueue itself: a failure there leaves the
 * port unable to promise the player is in the queue at all, so it answers
 * [MatchmakingEvent.Failed] and lets the screen offer a retry.
 */
class CallableMatchmaking(
  private val transport: MatchmakingTransport,
  private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
  private val searchBudgetMillis: Long = DEFAULT_SEARCH_BUDGET_MILLIS,
) : MatchmakingPort {

  override fun search(playerId: String, gameType: GameType): Flow<MatchmakingEvent> = flow {
    var nextDelay = pollIntervalMillis
    var transportFailed = false

    // The enqueue, and its own answer: the server may have matched the caller
    // during the very pass the enqueue ran.
    when (val state = transport.enqueue(gameType)) {
      is MatchmakingState.Matched -> {
        emit(MatchmakingEvent.Matched(state.tableId))
        return@flow
      }

      is MatchmakingState.Searching -> nextDelay = state.retryAfterMillis
      MatchmakingState.Idle,
      MatchmakingState.Denied,
      MatchmakingState.Retryable,
      -> {
        emit(MatchmakingEvent.Failed)
        return@flow
      }
    }

    var waited = 0L
    while (waited < searchBudgetMillis) {
      delay(nextDelay)
      waited += nextDelay
      when (val state = transport.poll()) {
        is MatchmakingState.Matched -> {
          emit(MatchmakingEvent.Matched(state.tableId))
          return@flow
        }

        is MatchmakingState.Searching -> nextDelay = state.retryAfterMillis

        MatchmakingState.Idle -> {
          // The queue no longer holds this search — the lease expired and the
          // pass reclaimed it. There is nothing left to poll for.
          emit(MatchmakingEvent.Failed)
          return@flow
        }

        MatchmakingState.Denied -> {
          emit(MatchmakingEvent.Failed)
          return@flow
        }

        MatchmakingState.Retryable -> transportFailed = true
      }
    }

    // The budget is spent: leave the queue before answering, so the search
    // cannot outlive the player's patience.
    transport.cancel()
    emit(if (transportFailed) MatchmakingEvent.Failed else MatchmakingEvent.TimedOut)
  }

  override suspend fun cancel(playerId: String) {
    transport.cancel()
  }

  private companion object {
    /**
     * The fallback poll cadence, used only until the server answers with its
     * own. It mirrors the server's `SEARCH_RETRY_MILLIS`, but the server's
     * number always wins — a drifted copy here would only matter for the one
     * round trip that happens before the first answer.
     */
    const val DEFAULT_POLL_INTERVAL_MILLIS = 3_000L

    /**
     * How long the client waits before it gives the search up.
     *
     * The bound is the client's, because RD9 publishes no wait estimate and
     * the server cannot tell "the player is still watching" from "the player
     * walked away" — the poll's heartbeat tells it that, and the heartbeat
     * stops exactly when the client stops polling. Two minutes is long enough
     * for a pool to fill and short enough that a spinner is not a hostage.
     */
    const val DEFAULT_SEARCH_BUDGET_MILLIS = 120_000L
  }
}

/**
 * The production [MatchmakingTransport] — the three callables, one round trip
 * each.
 *
 * The payloads are the whole point: `enqueueSearch` carries the GameType and
 * nothing else, and `pollSearch` / `cancelSearch` carry NO field at all —
 * the server refuses a field it does not expect, and a request that could name
 * a player is a request that could name the wrong one.
 *
 * The error split is [MatchmakingResponse]'s: a code that means "the function
 * did not get to answer" is retryable, and everything else is the server's own
 * refusal.
 */
class FirebaseMatchmakingTransport(
  private val functions: FirebaseFunctions,
) : MatchmakingTransport {

  private val enqueueCallable = functions.getHttpsCallable(ENQUEUE_NAME)
  private val pollCallable = functions.getHttpsCallable(POLL_NAME)
  private val cancelCallable = functions.getHttpsCallable(CANCEL_NAME)

  override suspend fun enqueue(gameType: GameType): MatchmakingState =
    call(enqueueCallable, mapOf(GAME_TYPE to gameType.name))

  override suspend fun poll(): MatchmakingState = call(pollCallable, emptyMap())

  override suspend fun cancel() {
    try {
      cancelCallable.call(emptyMap<String, Any?>()).await()
    } catch (e: FirebaseFunctionsException) {
      // A cancellation that could not be delivered is not the player's
      // problem: the server's lease sweep reclaims the search anyway.
    } catch (e: Exception) {
      // Same: the search expires on its own if the cancel never lands.
    }
  }

  private suspend fun call(
    callable: HttpsCallableReference,
    payload: Map<String, Any?>,
  ): MatchmakingState = try {
    MatchmakingResponse.forData(callable.call(payload).await().getData(), DEFAULT_RETRY_MILLIS)
  } catch (e: FirebaseFunctionsException) {
    MatchmakingResponse.forCodeName(e.code.name)
  } catch (e: Exception) {
    // Transport-level: no network, an interrupted await, or the callable could
    // not be reached at all. Never read as a server's answer about the search.
    MatchmakingState.Retryable
  }

  private companion object {
    const val ENQUEUE_NAME = "enqueueSearch"
    const val POLL_NAME = "pollSearch"
    const val CANCEL_NAME = "cancelSearch"
    const val GAME_TYPE = "gameType"

    /** Used only when the server's answer carries no delay of its own. */
    const val DEFAULT_RETRY_MILLIS = 3_000L
  }
}
