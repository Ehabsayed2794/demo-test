package com.estemshan.services

import com.estemshan.engine.DisconnectVoteChoice
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * The production [DisconnectVotePort] — a thin typed read over the three
 * disconnect-flow callables' response maps.
 *
 * [CallableSeatReclaim]'s discipline exactly, because these callables are
 * entered the same way: by a client whose clock is not the server's and whose
 * snapshot may be stale. Every callable re-derives its own state inside a
 * transaction on the server's clock, so this layer adds NO client-side guarding
 * of its own — no local "am I held" check against the last snapshot, no
 * pre-read of the marker, no debounce. A client that guarded on its own
 * snapshot would be asking its stale copy whether the server's live document
 * was ready, which is the one question the stale copy cannot answer. Its job is
 * the response → next-action mapping, and the FAILURE-CODE → terminal-vs-
 * retryable mapping, so a poll never spins on a denial.
 *
 * Each call is one round-trip and one transaction. The polling itself — how
 * many looks, what backoff, when it stops on the match ending — is the
 * caller's, not this class's.
 */
class CallableDisconnectVote(
  private val functions: FirebaseFunctions,
) : DisconnectVotePort {

  private val resolveCallable = functions.getHttpsCallable(RESOLVE_NAME)
  private val castCallable = functions.getHttpsCallable(CAST_NAME)
  private val restoreCallable = functions.getHttpsCallable(RESTORE_NAME)

  override suspend fun resolve(matchId: String): DisconnectVotePort.Resolve {
    val data = try {
      resolveCallable.call(mapOf(MATCH_ID to matchId)).await().getData()
    } catch (e: FirebaseFunctionsException) {
      return DisconnectVoteResponse.forResolveCode(e.code)
    } catch (e: Exception) {
      // Transport-level: no network, an interrupted await, or the callable
      // could not be reached at all. The poll's bounded backoff handles these,
      // and none of them is a server's answer about the table.
      return DisconnectVotePort.Resolve.Retryable
    }

    return DisconnectVoteResponse.forResolveData(data)
  }

  override suspend fun cast(
    matchId: String,
    choice: DisconnectVoteChoice,
  ): DisconnectVotePort.Cast {
    val data = try {
      castCallable.call(mapOf(MATCH_ID to matchId, CHOICE to choice.name)).await().getData()
    } catch (e: FirebaseFunctionsException) {
      return DisconnectVoteResponse.forCastCode(e.code)
    } catch (e: Exception) {
      return DisconnectVotePort.Cast.Retryable
    }

    return DisconnectVoteResponse.forCastData(data)
  }

  override suspend fun restore(matchId: String): DisconnectVotePort.Restore {
    val data = try {
      restoreCallable.call(mapOf(MATCH_ID to matchId)).await().getData()
    } catch (e: FirebaseFunctionsException) {
      return DisconnectVoteResponse.forRestoreCode(e.code)
    } catch (e: Exception) {
      return DisconnectVotePort.Restore.Retryable
    }

    return DisconnectVoteResponse.forRestoreData(data)
  }

  private companion object {
    const val RESOLVE_NAME = "resolveDisconnectVote"
    const val CAST_NAME = "castDisconnectVote"
    const val RESTORE_NAME = "restoreDisconnectedSeat"
    const val MATCH_ID = "matchId"
    const val CHOICE = "choice"
  }
}

/**
 * The three callables' response → port-outcome mappings, split out of the class
 * so the two halves of this story's contract — which transport codes revisit,
 * and which keys of a response the client is allowed to know — are pinned in a
 * hermetic JVM test rather than only against the Functions emulator.
 *
 * The stakes are the S73 ones: a permanent denial retried forever, or a real
 * wait read as terminal and a seat given up while it was still reclaimable.
 * Both live in this file, beside the only caller, so the transport and its
 * reader cannot drift apart.
 *
 * The wire vocabulary (`changed`, `recorded`, `already`, `restored`, `reason`,
 * `retryAfterMillis`, `NOT_APPLICABLE`, `BALLOT_CLOSED`, `NOT_HELD`,
 * `REPLACED`) is mirrored here as literals: `:services` cannot see
 * `:functions`, and the emulator suite is what will catch a drifted copy. The
 * keys the response also carries — `ok`, `message`, `matchId`, `seatId`,
 * `action` — are deliberately dropped for [SeatReclaimResponse]'s reason: the
 * seat is the caller's own from its snapshot, and echoing it back would give
 * the poll a second, possibly-disagreeing answer. `action` is dropped on
 * purpose too: every transition the server can name is the same next action to
 * this poll — re-arm on the returned number and keep watching, because the
 * snapshot listener carries what actually changed.
 */
object DisconnectVoteResponse {

  /**
   * The resolver's and the reclaim's shared split: three codes mean "the
   * function did not get to answer", every other code is a denial the SERVER
   * chose — a missing match, a caller who is not a player, a payload with no
   * matchId, a document that is not a match — none of which a retry revisits.
   * `INTERNAL` is retryable for the reason it is in [CallableSeatReclaim]: a
   * thrown error from inside the transaction is the emulator's abort or an
   * unhandled write, both of which a fresh attempt can genuinely succeed at.
   * It cannot be confused with a flow outcome, because a flow that decided
   * anything at all returns a map, not a throw.
   *
   * The decision is made on the code's [String] name and delegated, because a
   * JVM unit test cannot initialize [FirebaseFunctionsException.Code] at all:
   * its static initializer needs the Android runtime. Reading the name costs
   * nothing on the device and makes the one branch that decides whether a poll
   * stops forever testable without a backend.
   */
  fun forResolveCode(code: FirebaseFunctionsException.Code): DisconnectVotePort.Resolve =
    forResolveCodeName(code.name)

  /** [forResolveCode], with the SDK out of the way. An unrecognized name — one
   *  this build has never seen — is a denial, the terminal direction. */
  fun forResolveCodeName(name: String): DisconnectVotePort.Resolve =
    if (name in RETRYABLE_CODES) DisconnectVotePort.Resolve.Retryable
    else DisconnectVotePort.Resolve.Denied

  /** The cast's split. A refused cast is terminal with the code's own name as
   *  the carried reason — the diagnostic is worth keeping, the retry is not. */
  fun forCastCode(code: FirebaseFunctionsException.Code): DisconnectVotePort.Cast =
    forCastCodeName(code.name)

  /** [forCastCode], with the SDK out of the way. */
  fun forCastCodeName(name: String): DisconnectVotePort.Cast =
    if (name in RETRYABLE_CODES) DisconnectVotePort.Cast.Retryable
    else DisconnectVotePort.Cast.Refused(name)

  /** The restore's split: a refusal is terminal — asking again cannot make a
   *  replaced seat reclaimable, and a `NOT_HELD` answer is already the goal. */
  fun forRestoreCode(code: FirebaseFunctionsException.Code): DisconnectVotePort.Restore =
    forRestoreCodeName(code.name)

  /** [forRestoreCode], with the SDK out of the way. */
  fun forRestoreCodeName(name: String): DisconnectVotePort.Restore =
    if (name in RETRYABLE_CODES) DisconnectVotePort.Restore.Retryable
    else DisconnectVotePort.Restore.Denied

  /**
   * The resolver response's two readable facts — whether a transition was
   * committed, and the server's own next-look instant — and its one terminal
   * reason. `NOT_APPLICABLE` is checked FIRST and wins over everything else: it
   * is RANKED's own answer, and the poll must stop on it even if a future
   * version of the response grew a number beside it. A response that says
   * neither direction is not read as a wait: it is a denial, because a poll
   * that guessed "wait" from a shape it did not understand would keep looking
   * at a table it was never part of.
   */
  fun forResolveData(data: Any?): DisconnectVotePort.Resolve {
    val fields = data as? Map<*, *> ?: return DisconnectVotePort.Resolve.Retryable
    return when {
      fields[REASON] == REASON_NOT_APPLICABLE -> DisconnectVotePort.Resolve.NotApplicable
      fields[CHANGED] == true -> DisconnectVotePort.Resolve.Advanced(retryAfter(fields))
      fields[CHANGED] == false -> DisconnectVotePort.Resolve.Waiting(retryAfter(fields))
      else -> DisconnectVotePort.Resolve.Denied
    }
  }

  /**
   * The cast response: `recorded` is the answer, `already` the idempotent
   * repeat, `BALLOT_CLOSED` its own outcome (the resolver's poll settles the
   * ballot, so nothing here retries), and every other refusal terminal with the
   * server's `reason` carried verbatim. A refusal the server did not explain
   * still records nothing — the honest default is [DisconnectVotePort.Cast.Refused],
   * never a retry that would spin.
   */
  fun forCastData(data: Any?): DisconnectVotePort.Cast {
    val fields = data as? Map<*, *> ?: return DisconnectVotePort.Cast.Retryable
    return when {
      fields[RECORDED] == true ->
        if (fields[ALREADY] == true) DisconnectVotePort.Cast.AlreadyRecorded
        else DisconnectVotePort.Cast.Recorded
      fields[REASON] == REASON_BALLOT_CLOSED -> DisconnectVotePort.Cast.BallotClosed
      else -> DisconnectVotePort.Cast.Refused((fields[REASON] as? String) ?: UNKNOWN_REASON)
    }
  }

  /**
   * The restore response: a returned seat is [DisconnectVotePort.Restore.Restored],
   * the waiting ending first is [DisconnectVotePort.Restore.Replaced] (terminal,
   * and no charge is ever carried — the document records the termination, the
   * charge table is applied downstream), a seat nobody held is
   * [DisconnectVotePort.Restore.NothingHeld], and anything else is a denial. A
   * response that did not say the seat came back has not given the client a
   * success to apply.
   */
  fun forRestoreData(data: Any?): DisconnectVotePort.Restore {
    val fields = data as? Map<*, *> ?: return DisconnectVotePort.Restore.Retryable
    return when {
      fields[RESTORED] == true -> DisconnectVotePort.Restore.Restored
      fields[REASON] == REASON_NOT_HELD -> DisconnectVotePort.Restore.NothingHeld
      fields[REASON] == REASON_REPLACED -> DisconnectVotePort.Restore.Replaced
      else -> DisconnectVotePort.Restore.Denied
    }
  }

  /**
   * The server's own next-look instant, with [SeatReclaimResponse]'s floor for
   * version skew: a response whose number is missing or unreadable waits one
   * bot decision window rather than tight-looping, and lets the next call
   * correct it. Never a zero, never a client-side estimate — the client cannot
   * read a peer's heartbeat even if it wanted to.
   */
  private fun retryAfter(fields: Map<*, *>): Long =
    (fields[RETRY_AFTER] as? Number)?.toLong() ?: FALLBACK_RETRY_AFTER_MILLIS

  /**
   * The three codes a poll may revisit. Named here, once, and mirrored as
   * literals in the test because no shared constant exists across modules.
   */
  private val RETRYABLE_CODES = setOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")

  private const val CHANGED = "changed"
  private const val RECORDED = "recorded"
  private const val ALREADY = "already"
  private const val RESTORED = "restored"
  private const val REASON = "reason"
  private const val RETRY_AFTER = "retryAfterMillis"

  /** RANKED's terminal answer — the mode never asks the question at all. */
  private const val REASON_NOT_APPLICABLE = "NOT_APPLICABLE"

  /** The 30 s window passed; the resolver's poll settles the ballot. */
  private const val REASON_BALLOT_CLOSED = "BALLOT_CLOSED"

  /** Nobody held this seat. */
  private const val REASON_NOT_HELD = "NOT_HELD"

  /** The waiting ended without a reconnect: permanently the configured bot's. */
  private const val REASON_REPLACED = "REPLACED"

  /** A refusal this build cannot name is still a refusal, not a guess. */
  private const val UNKNOWN_REASON = "UNKNOWN"

  /**
   * The client-side mirror of the functions' floor for a retry the server did
   * not bound, derived from [DecisionWindow.BOT_DECISION_SECONDS] rather than a
   * second literal — the same trade [SeatReclaimResponse] makes, for the same
   * reason: `:services` cannot see `:functions`.
   */
  private const val FALLBACK_RETRY_AFTER_MILLIS = DecisionWindow.BOT_DECISION_SECONDS * 1_000L
}
