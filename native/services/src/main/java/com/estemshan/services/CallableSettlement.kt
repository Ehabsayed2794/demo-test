package com.estemshan.services

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

/**
 * The production [SettlementPort] — a thin typed read over the `settleMatch`
 * callable's response map.
 *
 * [CallableDecisionTimeout]'s discipline exactly, because the callable is
 * entered the same way: the settler re-derives everything inside one
 * transaction on the server's own clock, so this layer adds NO client-side
 * guarding of its own — no local "is it complete" check against the last
 * snapshot, no debounce, no claim probe. A client that guarded on its own
 * snapshot would be asking its stale copy whether the server's live document
 * was ready, which is the one question the stale copy cannot answer. Its one
 * job is mapping the response to the caller's next action, and mapping
 * FAILURE CODES to terminal-vs-retryable so a caller never spins on a denial.
 */
class CallableSettlement(
  private val functions: FirebaseFunctions,
) : SettlementPort {

  private val callable = functions.getHttpsCallable(NAME)

  override suspend fun settle(matchId: String): SettlementPort.SettlementOutcome {
    val data = try {
      callable.call(mapOf(MATCH_ID to matchId)).await().getData()
    } catch (e: FirebaseFunctionsException) {
      // A denial the server chose to raise. The settler raises these for a
      // missing match, a non-member, a bad argument, and a fail-closed
      // history — none of which a retry revisits.
      return SettlementResponse.forCode(e.code)
    } catch (e: Exception) {
      // Transport-level: no network, an interrupted await, or the callable
      // could not be reached at all. The caller's bounded backoff handles
      // these, and none of them is a server's answer about the match.
      return SettlementPort.SettlementOutcome.Retryable
    }

    return SettlementResponse.forData(data)
  }

  private companion object {
    const val NAME = "settleMatch"
    const val MATCH_ID = "matchId"
  }
}

/**
 * The settle response → port-outcome mapping, split out of the class so the
 * two halves of this story's contract — which transport codes may be
 * retried, and which keys of a response the client is allowed to know — are
 * pinned in a hermetic JVM test rather than only against the Functions
 * emulator.
 *
 * The stakes are the RD12/RD13 ones: a denial retried forever, or an absent
 * `you` read as a zero movement and a reveal built from numbers the client
 * invented. Both live in this file, beside the only caller, so the transport
 * and its reader cannot drift apart.
 *
 * The wire vocabulary this layer reads (`settled`, `replayed`, `reason`,
 * `mode`, `NOT_RANKED`, `you`, `previousRp`, `delta`) is mirrored here as
 * literals: `:services` cannot see `:functions`. The copy is pinned by the
 * JVM mapping test only — no settlement emulator or device suite exists yet,
 * and the server half that ships the vocabulary is itself unshipped — so
 * nothing else catches a drifted copy today. The keys the response also
 * carries — `ok`, `matchId`, `corrected`, `finalScores`, `winnerIds`,
 * `claimedScores`, `claimedWinners`, `rounds` — are deliberately dropped:
 * the standings come from the match document (the authority every client
 * already replays), so echoing a second copy of them would hand the screen
 * two possibly-disagreeing answers. `rp` is dropped the way those are, but
 * for the opposite reason: by the contract rp − previousRp = delta, the raw
 * `rp` is the pair the client does read restated — parsing it would put a
 * dead field in the model, and carrying it would hand the reveal a second
 * number to disagree with the first.
 */
object SettlementResponse {

  /** The failure-code split, with the SDK's enum out of the way. */
  fun forCode(code: FirebaseFunctionsException.Code): SettlementPort.SettlementOutcome =
    forCodeName(code.name)

  /**
   * [forCode], by the code's [String] name — a JVM unit test cannot
   * initialize [FirebaseFunctionsException.Code] at all: its static
   * initializer needs the Android runtime. Reading the name costs nothing on
   * the device and makes the one branch that decides whether a caller stops
   * forever testable without a backend. An unrecognized name — one this
   * build has never seen — is a denial, the terminal direction.
   */
  fun forCodeName(name: String): SettlementPort.SettlementOutcome =
    if (name in RETRYABLE_CODES) SettlementPort.SettlementOutcome.Retryable
    else SettlementPort.SettlementOutcome.Denied

  /**
   * The response's two readable facts — whether the match settled, and the
   * caller's own movement — and its one terminal no-op reason. `settled:
   * false` with `NOT_RANKED` is the mode gate's own answer (ROOM/UNRANKED
   * never settle, RD28): terminal, with the mode kept for the log. A
   * response that says neither direction is not read as either: it is a
   * denial, because a client that guessed "settled" from a shape it did not
   * understand would reveal a movement the server never awarded.
   */
  fun forData(data: Any?): SettlementPort.SettlementOutcome {
    val fields = data as? Map<*, *> ?: return SettlementPort.SettlementOutcome.Retryable
    return when {
      fields[SETTLED] == true -> SettlementPort.SettlementOutcome.Settled(
        replayed = fields[REPLAYED] == true,
        you = you(fields),
      )
      fields[SETTLED] == false && fields[REASON] == REASON_NOT_RANKED ->
        SettlementPort.SettlementOutcome.NotRanked((fields[MODE] as? String) ?: UNKNOWN_MODE)
      else -> SettlementPort.SettlementOutcome.Denied
    }
  }

  /**
   * The caller's own movement, read only when the response carries it whole:
   * `you` absent (forward-looking wire — no shipped build carries it yet),
   * not a map, or missing/unreadable in either of its two fields is "no
   * movement to reveal" — never a zero, never a partial guess. The reveal it
   * feeds is the one place the client shows rank state, and a reveal built
   * from invented numbers is worse than none.
   */
  private fun you(fields: Map<*, *>): RpMovement? {
    val raw = fields[YOU] as? Map<*, *> ?: return null
    val previousRp = (raw[PREVIOUS_RP] as? Number)?.toInt() ?: return null
    val delta = (raw[DELTA] as? Number)?.toInt() ?: return null
    return RpMovement(previousRp = previousRp, delta = delta)
  }

  /**
   * The three codes a caller may revisit. Named here, once, and mirrored as
   * literals in the test because no shared constant exists across modules.
   */
  private val RETRYABLE_CODES = setOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")

  private const val SETTLED = "settled"
  private const val REPLAYED = "replayed"
  private const val REASON = "reason"
  private const val MODE = "mode"
  private const val YOU = "you"
  private const val PREVIOUS_RP = "previousRp"
  private const val DELTA = "delta"

  /** The mode gate's no-op reason — ROOM/UNRANKED never settle (RD28). */
  private const val REASON_NOT_RANKED = "NOT_RANKED"

  /** A mode this build cannot read is still a no-op, logged as UNKNOWN. */
  private const val UNKNOWN_MODE = "UNKNOWN"
}
