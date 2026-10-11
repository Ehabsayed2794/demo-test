package com.estemshan.services

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

/**
 * The production [DecisionTimeoutPort] — a thin typed read over the
 * `resolveDecisionTimeout` callable's response map.
 *
 * The resolver's own contract is what makes this safe to call freely: it
 * re-derives the pending decision inside a transaction and answers
 * [DecisionTimeoutPort.Resolution.NotYetExpired] for anything that raced, so
 * this layer adds no client-side guarding of its own. Its one job is mapping
 * the response to the alarm's next action, and mapping FAILURE CODES to
 * terminal-vs-retryable so the alarm never spins on a denial.
 */
class CallableDecisionTimeout(
  private val functions: FirebaseFunctions,
) : DecisionTimeoutPort {

  private val callable = functions.getHttpsCallable(NAME)

  override suspend fun resolve(matchId: String): DecisionTimeoutPort.Resolution {
    val data = try {
      callable.call(mapOf(MATCH_ID to matchId)).await().getData()
    } catch (e: FirebaseFunctionsException) {
      // A denial the server chose to raise. The resolver raises these for a
      // missing match, a non-member, a bad argument, and a fail-closed history
      // — none of which a retry revisits.
      return if (e.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
        e.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
        e.code == FirebaseFunctionsException.Code.INTERNAL
      ) {
        DecisionTimeoutPort.Resolution.Retryable
      } else {
        DecisionTimeoutPort.Resolution.Denied
      }
    } catch (e: Exception) {
      // Transport-level: no network, interrupted, or the callable could not be
      // reached at all. The alarm's bounded backoff handles these.
      return DecisionTimeoutPort.Resolution.Retryable
    }

    val fields = data as? Map<*, *> ?: return DecisionTimeoutPort.Resolution.Retryable
    return when {
      fields[RESOLVED] == true -> DecisionTimeoutPort.Resolution.Applied
      fields[REASON] == REASON_NOT_YET_EXPIRED -> DecisionTimeoutPort.Resolution.NotYetExpired(
        (fields[RETRY_AFTER] as? Number)?.toLong() ?: FALLBACK_RETRY_AFTER_MILLIS,
      )
      fields[REASON] == REASON_NOT_ACTIVE -> DecisionTimeoutPort.Resolution.NotActive
      // resolved=false with any other reason is a shape the alarm should not
      // loop on; treat it as terminal rather than guessing.
      else -> DecisionTimeoutPort.Resolution.Denied
    }
  }

  private companion object {
    const val NAME = "resolveDecisionTimeout"
    const val MATCH_ID = "matchId"
    const val RESOLVED = "resolved"
    const val REASON = "reason"
    const val RETRY_AFTER = "retryAfterMillis"
    const val REASON_NOT_YET_EXPIRED = "NOT_YET_EXPIRED"
    const val REASON_NOT_ACTIVE = "NOT_ACTIVE"

    /**
     * The server floors its own retry delay, so a missing field here is a
     * version skew, not a zero — fall back to a short wait rather than a tight
     * loop and let the next call correct it.
     */
    const val FALLBACK_RETRY_AFTER_MILLIS = 1_000L
  }
}
