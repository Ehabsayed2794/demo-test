package com.estemshan.functions

import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.SeatControl
import com.estemshan.engine.storedSeatControl
import kotlin.math.floor

/**
 * S73 Stage 2, C4 — DEC-RD19-04's return of control, as a pure decision.
 *
 * "The bot finishes the **current trick**, control returns at the **next decision
 * point**, in the **same seat**." The same seat is structural: the seat is derived
 * from the caller's verified uid through the document's own `seats` map
 * ([TimeoutResolution.seatOfUid]) and never taken from the request, so a reclaim
 * cannot name a seat at all. What this file decides is only WHETHER the bound is
 * met and WHAT the two writes are; the transaction that makes them atomic is
 * [reclaimOnce], and no Firestore type reaches here — the same split
 * [TimeoutResolution] keeps from [resolveTimeoutOnce].
 *
 * **The bound is derived, never stored.** [SeatControl] is read through
 * [storedSeatControl], which computes `trickFinished` from the marker's own
 * round/trick against the document's CURRENT round and card count — the one
 * comparison [InactiveTakeover.seatTrickKey] exists to make. So a takeover struck
 * mid-trick is bounded by that trick, one struck during the auction is bounded by
 * the first trick of that round resolving, and a round that advanced while the
 * seat was borrowed has by construction moved its key past the takeover's.
 * [SeatControl.reclaim] is the rule this gate enforces, and the clock this write
 * carries is `InactivityClock()` — the same value `reclaim()` yields for a
 * finished trick (DEC-RD19-01: returning control IS a genuine interaction inside
 * the player's own window, so the accumulation resets).
 *
 * **Fail-closed, in the direction that frees the player.** A marker that does not
 * parse reads as "not borrowed" and writes nothing: the seat is already shown as
 * its player's, and [TimeoutResolution.inactivityAfterExpiredWindow] keeps
 * advancing that seat's clock, so the next real takeover overwrites the garbage
 * with a well-formed entry. A document whose round or card log cannot be read is
 * the opposite case — the bound cannot be PROVED, so no write happens and the
 * caller is told to come back on the server's own retry bound. Neither refusal
 * can strand a human out of a seat that is theirs: the only state a reclaim
 * removes is its own marker.
 *
 * **What a reclaim never touches.** No timeout counter is read or written (RD20 —
 * the counter counts timeouts, and this is a return), no seat list, no removal
 * list, no status, no departure record: the marker removal plus the zeroed clock
 * and its baseline cursor are the whole of the change. A seat the table made
 * PERMANENTLY bot-driven — Vote Kick's `botSeats`, or a uid S61's 15-timeout
 * removal took over — is refused as not its player's to reclaim, because
 * DEC-RD19-04 returns a BORROWED seat and those holds are not borrowings.
 *
 * **Deliberately out of scope:** the configured tier/personality of the bot that
 * held the seat (C7), the client's bounded poll (C5/C6 — it re-arms on
 * [Decision.NoWrite.retryAfterMillis], never on its own estimate), and the
 * disconnect flow (S74).
 */
object SeatReclaim {

  /**
   * Canonical no-write reasons. Deliberately three, and deliberately none of
   * them an error: a reclaim that is not due yet is the normal outcome of a poll
   * that fired one trick too early, exactly as NOT_YET_EXPIRED is for the
   * resolver. The client switches on these, so the vocabulary is its contract.
   */
  object Reason {
    /** The caller's uid owns no reclaimable seat here — it occupies no seat, or
     *  the seat is permanently bot-driven, which is a different mechanism's hold. */
    const val NOT_OWNER = "NOT_OWNER"

    /** Nobody borrowed this seat; the human already holds it. */
    const val NOT_BORROWED = "NOT_BORROWED"

    /** Borrowed, but the trick in progress at takeover has not finished — or the
     *  document cannot prove that it has. [Decision.NoWrite.retryAfterMillis]
     *  carries when to look again. */
    const val TRICK_NOT_FINISHED = "TRICK_NOT_FINISHED"
  }

  /** The two writes a successful reclaim makes, both complete in themselves. */
  data class Write(
    /** The seat whose control returns — for the response, never a request echo. */
    val seatId: String,
    /** The marker to write over [InactiveTakeover.TAKEOVER_FIELD]: this seat's
     *  entry removed, every other seat's entry carried over VERBATIM
     *  ([InactiveTakeover.withoutTakeover]), so one reclaim cannot discard
     *  another seat's takeover. */
    val marker: Map<String, Any?>,
    /** The COMPLETE replacement for `matches/{id}/inactivity/{uid}`: the zeroed
     *  clock and the cursor baseline it restarts from. Absent cursor field means
     *  "no baseline recorded", which the resolver reads as the seat's first
     *  window rather than as a change of state — the fail-closed reading, used
     *  when this document's logs will not yield a cursor. */
    val inactivityFields: Map<String, Any?>,
  )

  sealed class Decision {
    /** Nothing is written. [retryAfterMillis] is present only on
     *  [Reason.TRICK_NOT_FINISHED] — the server's own bound on when the trick
     *  can have finished, which the caller re-arms its poll on. */
    data class NoWrite(val reason: String, val message: String, val retryAfterMillis: Long?) : Decision()

    /** Control returns; [write] is the atomic pair. */
    data class Reclaim(val write: Write) : Decision()
  }

  /**
   * Whether [uid] may take [uid]'s own seat back from the bot right now, over the
   * document as it is COMMITTED (the caller re-reads inside its transaction, so a
   * decision that landed a moment ago is seen here, never assumed).
   *
   * The gates run in the order their answers are cheapest and most certain:
   * ownership, then the permanent-bot union, then the marker, then the trick
   * bound — so a seat nobody borrowed answers [Reason.NOT_BORROWED] even when the
   * log that would prove the bound is unreadable.
   */
  fun decide(fields: Map<String, Any?>, uid: String, nowMillis: Long): Decision {
    val seatId = TimeoutResolution.seatOfUid(fields, uid)
      ?: return noWrite(
        Reason.NOT_OWNER,
        "reclaimInactiveSeat: '$uid' occupies no seat in this match.",
      )
    if (seatId in TimeoutResolution.botControlledSeats(fields)) {
      return noWrite(
        Reason.NOT_OWNER,
        "reclaimInactiveSeat: seat '$seatId' is permanently bot-driven, so no " +
          "inactivity borrow can return it.",
      )
    }

    val takeoverRaw = fields[InactiveTakeover.TAKEOVER_FIELD]
    val round = whole(fields["currentRound"])
    val cardsInRound = round?.let { cardsPlayedInRound(fields, it) }
    if (round != null && cardsInRound != null) {
      when (
        val control = storedSeatControl(
          takeoverRaw, seatId, round, InactiveTakeover.trickNo(cardsInRound),
        )
      ) {
        is SeatControl.Human -> return noWrite(
          Reason.NOT_BORROWED,
          "reclaimInactiveSeat: seat '$seatId' is not borrowed.",
        )
        is SeatControl.Borrowed -> {
          if (!control.trickFinished) {
            return notFinished(
              fields, nowMillis,
              "the bot still owes the trick in progress when seat '$seatId' was " +
                "taken over (DEC-RD19-04)",
            )
          }
          return Decision.Reclaim(
            Write(
              seatId = seatId,
              marker = InactiveTakeover.withoutTakeover(takeoverRaw, seatId),
              inactivityFields = zeroedDoc(fields, seatId),
            ),
          )
        }
      }
    }

    // The bound cannot be proved from this document. Say which half of RD19's
    // state is actually missing, so a caller that holds its own seat is never
    // told to keep polling for a borrow that does not exist.
    val borrowed = InactiveTakeover.parseAll(takeoverRaw)[seatId] != null
    return if (borrowed) {
      notFinished(
        fields, nowMillis,
        "seat '$seatId''s round or card log cannot be read, so the trick bound " +
          "cannot be proved",
      )
    } else {
      noWrite(
        Reason.NOT_BORROWED,
        "reclaimInactiveSeat: seat '$seatId' is not borrowed.",
      )
    }
  }

  /**
   * The private document as a returned seat starts over: the accumulation at 0
   * with no open window, and the seat's CURRENT committed-action count as the
   * baseline the next window is compared against.
   *
   * Recording that baseline is what makes the return clean. While the seat was
   * borrowed the bot's own moves accumulated in the very logs the cursor counts,
   * so leaving the pre-takeover cursor stored would make the first window after
   * reclaim look like the player had acted — a reset the player did not earn, and
   * one that would quietly cost the seat the whole 50 s again.
   */
  private fun zeroedDoc(fields: Map<String, Any?>, seatId: String): Map<String, Any?> {
    val baseline = TimeoutResolution.seatActionCursor(fields, seatId)
    return InactivityClock().storedFields() +
      if (baseline == null) emptyMap<String, Any?>()
      else mapOf(InactiveTakeover.CURSOR_FIELD to baseline)
  }

  private fun noWrite(reason: String, message: String): Decision.NoWrite =
    Decision.NoWrite(reason = reason, message = message, retryAfterMillis = null)

  private fun notFinished(
    fields: Map<String, Any?>,
    nowMillis: Long,
    cause: String,
  ): Decision.NoWrite = Decision.NoWrite(
    reason = Reason.TRICK_NOT_FINISHED,
    message = "reclaimInactiveSeat: $cause.",
    retryAfterMillis = retryAfterMillis(fields, nowMillis),
  )

  /**
   * The server's own bound on when to ask again, in the resolver's idiom: the
   * deadline of whatever decision the document is waiting on, read from that
   * document's server-stamped `updatedAt` through
   * [TimeoutResolution.millisUntilDeadline] — never a client estimate, and never
   * the caller's guess at the anchor (`updatedAt` is not on `MatchDoc`).
   *
   * Floored at one bot window rather than at [TimeoutResolution.MIN_RETRY_AFTER_MILLIS]:
   * a document whose deadline has already passed must not be polled again 250 ms
   * later, because nothing at this table can move faster than the borrowed seat's
   * own pace. A document with no timed decision pending (a round that played out
   * and waits on the advance) gets that same window, since the trick can only be
   * finished by the writes that follow.
   */
  private fun retryAfterMillis(fields: Map<String, Any?>, nowMillis: Long): Long {
    val pending = TimeoutResolution.pendingDecision(fields)
    val until = pending?.let { TimeoutResolution.millisUntilDeadline(it, nowMillis) }
      ?: BOT_WINDOW_MILLIS
    return until.coerceAtLeast(BOT_WINDOW_MILLIS)
  }

  /** Cards committed in [round] — the numerator of the trick in progress. */
  private fun cardsPlayedInRound(fields: Map<String, Any?>, round: Int): Int? {
    var count = 0
    for (entry in (fields["cardLog"] as? List<*>).orEmpty()) {
      val map = entry as? Map<*, *> ?: return null
      val entryRound = whole(map["round"]) ?: return null
      if (entryRound == round) count++
    }
    return count
  }

  /** A whole number, or null — Firestore hands JS numbers in as [Double]. */
  private fun whole(value: Any?): Int? {
    val number = value as? Number ?: return null
    val asDouble = number.toDouble()
    if (asDouble.isNaN() || asDouble.isInfinite() || asDouble != floor(asDouble)) return null
    return number.toInt()
  }

  private const val MILLIS_PER_SECOND = 1000L

  /** One borrowed seat's decision window — the shortest unit the table runs on,
   *  and therefore the floor of any retry this mechanism can honestly give. */
  private val BOT_WINDOW_MILLIS: Long =
    TimeoutResolution.BOT_DECISION_SECONDS.toLong() * MILLIS_PER_SECOND
}
