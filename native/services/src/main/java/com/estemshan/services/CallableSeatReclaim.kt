package com.estemshan.services

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * The production [SeatReclaimPort] — a thin typed read over the
 * `reclaimInactiveSeat` callable's response map.
 *
 * [CallableDecisionTimeout]'s discipline exactly, because this callable is
 * entered the same way: by a client whose clock is not the server's. The
 * reclaim re-derives its own bound inside a transaction and answers
 * [SeatReclaimPort.Reclaim.WaitingForTrick] for anything that raced, so this
 * layer adds NO client-side guarding of its own — no local "am I borrowed"
 * check against the last snapshot it may be holding, no pre-read of the marker,
 * no debounce. A client that guarded on its own snapshot would be asking its
 * stale copy whether the server's live document was ready, which is the one
 * question the stale copy cannot answer. Its job is the response → next-action
 * mapping, and the FAILURE-CODE → terminal-vs-retryable mapping, so the poll
 * never spins on a denial.
 *
 * The poll itself — how many looks, what backoff, when it stops on the match
 * ending — is the caller's, not this class's. That keeps a screen that rebuilds
 * from costing a fresh budget, and it keeps the port a single round-trip.
 */
class CallableSeatReclaim(
  private val functions: FirebaseFunctions,
) : SeatReclaimPort {

  private val callable = functions.getHttpsCallable(NAME)

  override suspend fun reclaim(matchId: String): SeatReclaimPort.Reclaim {
    val data = try {
      callable.call(mapOf(MATCH_ID to matchId)).await().getData()
    } catch (e: FirebaseFunctionsException) {
      return SeatReclaimResponse.forCode(e.code)
    } catch (e: Exception) {
      // Transport-level: no network, an interrupted await, or the callable
      // could not be reached at all. The poll's bounded backoff handles these,
      // and none of them is a server's answer about the seat.
      return SeatReclaimPort.Reclaim.Retryable
    }

    return SeatReclaimResponse.forData(data)
  }

  private companion object {
    const val NAME = "reclaimInactiveSeat"
    const val MATCH_ID = "matchId"
  }
}

/**
 * The callable's response → [SeatReclaimPort.Reclaim] mapping, split out of the
 * class so the two halves of this story's contract — which transport codes
 * revisit, and which keys of the response the client is allowed to know — are
 * pinned in a hermetic JVM test rather than only against the Functions
 * emulator. [CallableDecisionTimeout] keeps its copy inline and private because
 * nothing had yet had to prove that copy without a running backend; the
 * reclaim's copy is the one place where a wrong branch silently either strands
 * a player out of their seat (a permanent denial retried forever) or stops them
 * coming back for it (a real wait read as terminal).
 *
 * Both live in this file, beside the only caller, so the transport and its
 * reader cannot drift apart.
 */
object SeatReclaimResponse {

  /**
   * The resolver's and the reclaim's shared split: three codes mean "the
   * function did not get to answer", every other code is a denial the SERVER
   * chose — a missing match, a caller who is not a player, a payload with no
   * matchId, a document whose history it refuses to invent — none of which a
   * retry revisits. `INTERNAL` is retryable for the reason it is in
   * [CallableDecisionTimeout]: a thrown error from inside the transaction is
   * the emulator's abort or an unhandled write, both of which a fresh attempt
   * can genuinely succeed at. It cannot be confused with a reclaim outcome,
   * because a reclaim that decided anything at all returns a map, not a throw.
   *
   * The decision is made on the code's [String] name and delegated, because a
   * JVM unit test cannot initialize [FirebaseFunctionsException.Code] at all:
   * its static initializer needs the Android runtime, which is the same reason
   * [CallableDecisionTimeout] has never had a hermetic suite. Reading the name
   * costs nothing on the device and makes the one branch that decides whether a
   * poll stops forever testable without a backend.
   */
  fun forCode(code: FirebaseFunctionsException.Code): SeatReclaimPort.Reclaim =
    forCodeName(code.name)

  /** [forCode], with the SDK out of the way. An unrecognized name — one this
   *  build has never seen — is a denial, the terminal direction. */
  fun forCodeName(name: String): SeatReclaimPort.Reclaim =
    if (name in RETRYABLE_CODES) SeatReclaimPort.Reclaim.Retryable
    else SeatReclaimPort.Reclaim.Denied

  /**
   * The three codes the poll may revisit. Named here, once, and mirrored as
   * literals in the test because no shared constant exists across modules.
   */
  private val RETRYABLE_CODES = setOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")

  /**
   * The three keys this layer reads — `reclaimed`, `reason`, `retryAfterMillis`
   * — and no others. The response's `ok`, `message`, `matchId` and `seatId` are
   * deliberately dropped: the seat is the caller's own and the client already
   * has it from its snapshot, so echoing it back would give the poll a second,
   * possibly-disagreeing answer to "which seat is mine" — and the private
   * inactivity fields never reach a client at all, which is the whole point of
   * the marker being unwritable and the subcollection unreadable.
   */
  fun forData(data: Any?): SeatReclaimPort.Reclaim {
    val fields = data as? Map<*, *> ?: return SeatReclaimPort.Reclaim.Retryable
    return when {
      fields[RECLAIMED] == true -> SeatReclaimPort.Reclaim.Returned
      fields[REASON] == REASON_TRICK_NOT_FINISHED -> SeatReclaimPort.Reclaim.WaitingForTrick(
        (fields[RETRY_AFTER] as? Number)?.toLong() ?: FALLBACK_RETRY_AFTER_MILLIS,
      )
      fields[REASON] == REASON_NOT_BORROWED -> SeatReclaimPort.Reclaim.HumanHoldsSeat
      // `NOT_OWNER` arrives here, and so does any reason this build does not
      // know: terminal either way. A response that did not say the seat came
      // back has not given the client a wait to schedule.
      else -> SeatReclaimPort.Reclaim.Denied
    }
  }

  private const val RECLAIMED = "reclaimed"
  private const val REASON = "reason"
  private const val RETRY_AFTER = "retryAfterMillis"

  /** `SeatReclaim.Reason.TRICK_NOT_FINISHED` — borrowed, bound not yet met. */
  private const val REASON_TRICK_NOT_FINISHED = "TRICK_NOT_FINISHED"

  /** `SeatReclaim.Reason.NOT_BORROWED` — the human already holds the seat. */
  private const val REASON_NOT_BORROWED = "NOT_BORROWED"

  /**
   * The server floors every retry at one bot decision window, so a response
   * that says `TRICK_NOT_FINISHED` and carries no number is version skew, not
   * a zero — wait one window rather than tight-loop, and let the next call
   * correct it.
   *
   * Derived from [DecisionWindow.BOT_DECISION_SECONDS], the client-side mirror
   * that module already keeps of the functions constant, rather than a second
   * literal: `:services` cannot see `:functions`, so a drifted copy would be
   * caught only by the instrumented suite, and this is the one place in the
   * module where the number is a FLOOR rather than an estimate.
   */
  private const val FALLBACK_RETRY_AFTER_MILLIS = DecisionWindow.BOT_DECISION_SECONDS * 1_000L
}
