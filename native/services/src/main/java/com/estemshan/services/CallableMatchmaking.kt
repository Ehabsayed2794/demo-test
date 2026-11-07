package com.estemshan.services

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.UNRANKED_FILL_MILLIS
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

/**
 * S56/S71 — the production [MatchmakingPort], over the `enqueueSearch`,
 * `pollSearch` and `cancelSearch` callables.
 *
 * **What this class sends.** A GameType, a mode, and nothing else. RD9 makes
 * the eligible pool server-derived, so there is no tier here to compute, none
 * to send and none to read back: the answers carry a status and, once the
 * search resolved, the caller's own table. The uid the server acts on is the
 * callable's VERIFIED identity — `playerId` is therefore not put on the wire,
 * and there is nothing in this file that could derive a tier even by accident.
 * The mode is the player's own choice of WHAT TO PLAY (S71); it earns nothing,
 * because an Unranked search awards no RP (the tier, the division and the RP
 * stay Ranked-only) while its results count towards the career counters
 * (Q46/N-1, 2026-10-22).
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
 * seated in a table they left. The bound is per MODE: a Ranked search waits
 * the Ranked budget, while an Unranked one waits out the fill window it is
 * promised — the four minutes until the configured bots take the empty seats
 * — plus a grace for the last round trip.
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
  private val searchBudgetMillis: Long? = null,
) : MatchmakingPort {

  /**
   * The budget is the client's own, and it is per mode: it must outlast the
   * server's own promise to the search, or the client would give up on a
   * search the server is still filling.
   */
  private fun budgetFor(mode: MatchMode): Long = when (mode) {
    MatchMode.RANKED -> RANKED_SEARCH_BUDGET_MILLIS
    MatchMode.UNRANKED -> UNRANKED_SEARCH_BUDGET_MILLIS
    MatchMode.ROOM -> throw IllegalArgumentException(ROOM_IS_NOT_POOLABLE)
  }

  override fun search(
    playerId: String,
    gameType: GameType,
    mode: MatchMode,
  ): Flow<MatchmakingEvent> {
    // A Room is filled by invitation, never by a queue: a search for one would
    // sit in a pool that can never seat it. The refusal is loud and immediate,
    // at the call and not at the first poll.
    require(mode != MatchMode.ROOM) { ROOM_IS_NOT_POOLABLE }
    val budget = searchBudgetMillis ?: budgetFor(mode)

    return flow {
      var nextDelay = pollIntervalMillis
      var transportFailed = false
      var publishedDeadline: Long? = null

      // The enqueue, and its own answer: the server may have matched the caller
      // during the very pass the enqueue ran.
      when (val state = transport.enqueue(gameType, mode)) {
        is MatchmakingState.Matched -> {
          emit(MatchmakingEvent.Matched(state.tableId))
          return@flow
        }

        is MatchmakingState.Searching -> {
          nextDelay = state.retryAfterMillis
          // The server published the deadline this search's seat is filled by.
          // Only an Unranked search has one — a Ranked table is a pair, not a
          // window — and the port never invents one.
          val deadline = state.fillDeadlineMillis
          if (mode == MatchMode.UNRANKED && deadline != null) {
            publishedDeadline = deadline
            emit(MatchmakingEvent.Searching(deadline))
          }
        }

        MatchmakingState.Idle,
        MatchmakingState.Denied,
        MatchmakingState.Retryable,
        -> {
          emit(MatchmakingEvent.Failed)
          return@flow
        }
      }

      var waited = 0L
      while (waited < budget) {
        delay(nextDelay)
        waited += nextDelay
        when (val state = transport.poll()) {
          is MatchmakingState.Matched -> {
            emit(MatchmakingEvent.Matched(state.tableId))
            return@flow
          }

          is MatchmakingState.Searching -> {
            nextDelay = state.retryAfterMillis
            val deadline = state.fillDeadlineMillis
            if (mode == MatchMode.UNRANKED && deadline != null && deadline != publishedDeadline) {
              publishedDeadline = deadline
              emit(MatchmakingEvent.Searching(deadline))
            }
          }

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
     * How long a RANKED search waits before the client gives it up.
     *
     * The bound is the client's, because RD9 publishes no wait estimate and
     * the server cannot tell "the player is still watching" from "the player
     * walked away" — the poll's heartbeat tells it that, and the heartbeat
     * stops exactly when the client stops polling. Two minutes is long enough
     * for a pool to fill and short enough that a spinner is not a hostage.
     */
    const val RANKED_SEARCH_BUDGET_MILLIS = 120_000L

    /**
     * How long an UNRANKED search waits — the fill window plus a grace.
     *
     * The server promises the search a seat by the window's close, and the
     * configured bots take whatever is left then. The client must therefore
     * wait LONGER than the window, or it would cancel a search the server was
     * about to fill. The grace covers the last poll round trip.
     */
    const val UNRANKED_SEARCH_BUDGET_MILLIS = UNRANKED_FILL_MILLIS + 60_000L

    const val ROOM_IS_NOT_POOLABLE =
      "ROOM is not a poolable mode: a Room is filled by invitation, never by a queue."
  }
}

/**
 * The production [MatchmakingTransport] — the three callables, one round trip
 * each.
 *
 * The payloads are the whole point: `enqueueSearch` carries the GameType and
 * the MODE and nothing else, and `pollSearch` / `cancelSearch` carry NO field
 * at all — the server refuses a field it does not expect, and a request that
 * could name a player is a request that could name the wrong one.
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

  override suspend fun enqueue(gameType: GameType, mode: MatchMode): MatchmakingState =
    call(enqueueCallable, mapOf(GAME_TYPE to gameType.name, MODE to mode.name))

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
    const val MODE = "mode"

    /** Used only when the server's answer carries no delay of its own. */
    const val DEFAULT_RETRY_MILLIS = 3_000L
  }
}
