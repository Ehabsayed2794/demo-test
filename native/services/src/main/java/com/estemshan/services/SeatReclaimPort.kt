package com.estemshan.services

/**
 * S73 (RD19) — the seam over the `reclaimInactiveSeat` callable.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → SeatReclaimPort → (Cloud Functions reclaim) → Firestore
 *
 * WHY THE SEAM — [DecisionTimeoutPort]'s reason, and a stronger one. This repo
 * ships no Firebase fake by design, so the interface is what lets a hermetic
 * JVM test stand in for the callable while the instrumented suite drives the
 * real thing. But the reclaim also could not be a client write even if it
 * wanted to be: the two documents it touches are the takeover marker on
 * `matches/{id}` — a field no client-facing rule names, so it is unwritable
 * from a device — and `matches/{id}/inactivity/{uid}`, which has no rules
 * block at all and is therefore deny-everything for clients. Both are written
 * by the Admin SDK, which bypasses rules. And whether they MAY be written is a
 * fact about the committed document ("the trick in progress when the seat was
 * borrowed has finished") that only the server can read and prove inside one
 * transaction, on the server's clock. So this seam is not a convenience layer
 * over a client capability; it is the only door.
 *
 * WHY OWNER-ONLY — the callable derives the seat from the CALLER'S VERIFIED UID
 * through the document's own `seats` map, so a reclaim request cannot name a
 * seat at all: there is no "give me p2", and therefore nothing to spoof. Only
 * the player whose seat is borrowed can ask for it back, and a caller who
 * occupies no seat — or whose seat the table made PERMANENTLY bot-driven by
 * Vote Kick or by the 15-timeout removal, which are holds and not borrowings —
 * is refused. [Reclaim.Denied] is that refusal, and it is terminal by design:
 * a seat this mechanism never took cannot be returned by asking again.
 *
 * NO ACCUMULATOR, NO COUNTER — [Reclaim] carries the poll's next action and
 * nothing else. The accumulation, its open window, the baseline cursor and the
 * S61 timeout counter are owner-read-only per seat and never ride this
 * response; a return of control is not a timeout, so it neither reads nor
 * writes that counter, and the player getting their seat back has no need of
 * the number that lost it. Compare [DecisionTimeoutPort]'s counter privacy,
 * which this mirrors for the same reason. [CallableSeatReclaim] enforces it by
 * construction: it reads three keys off the response and no field of [Reclaim]
 * is anything but the next action.
 */
interface SeatReclaimPort {

  /**
   * Asks the backend to return the CALLER'S OWN seat, once, if the bot's
   * borrowed trick is down.
   *
   * Idempotent and safe to retry: the reclaim re-derives the bound from the
   * document as it is COMMITTED inside its transaction, so a call that raced
   * the bot's card answers [Reclaim.WaitingForTrick], a repeat of a reclaim
   * that already landed answers [Reclaim.HumanHoldsSeat], and neither writes
   * anything a second time. A poll that fires twice, out of order, or after a
   * reconnect therefore cannot double-return or double-zero a clock.
   */
  suspend fun reclaim(matchId: String): Reclaim

  /**
   * The outcomes, split by what the caller's bounded poll does next — the same
   * shape as [DecisionTimeoutPort.Resolution], because the two mechanisms are
   * paced by one document and must not invent a second vocabulary for "wait,
   * stop, or done".
   */
  sealed interface Reclaim {

    /**
     * Control returned: the marker lost this seat's entry and the seat's clock
     * was replaced by a fresh one in the same transaction. Nothing here needs
     * to be delivered — the snapshot listener already carries the marker
     * removal, so the S39 chip flips back on its own and the poll terminates
     * rather than watching for it.
     */
    data object Returned : Reclaim

    /**
     * The bot still owes the trick that was in progress when the seat was
     * borrowed — or the document cannot prove that trick has finished, which
     * is answered the same way because the fail-closed direction is "stay
     * borrowed one more look" rather than "return mid-trick".
     *
     * [retryAfterMillis] is the SERVER's bound on when the trick can have
     * moved: the remaining window of whatever decision the document is waiting
     * on, computed on the clock that judged it and floored at one bot decision
     * window. The poll re-arms on THIS and never on its own estimate —
     * `MatchDoc` carries no `updatedAt`, so the client cannot derive the
     * anchor and its own guess drifts with clock skew. Not a failure: nothing
     * is retried, the wait is rescheduled.
     */
    data class WaitingForTrick(val retryAfterMillis: Long) : Reclaim

    /**
     * Nobody has this seat borrowed — the player already holds it. That is the
     * normal answer to a poll that arrived after another device's reclaim, and
     * the honest reading of a marker this process cannot parse (fail-closed:
     * garbage never strands a human out of their own seat). Terminal: stop the
     * poll, the seat is already shown as the player's.
     */
    data object HumanHoldsSeat : Reclaim

    /**
     * A transient failure — the callable threw a retryable transport error,
     * the network was unreachable, or the response was not the map this layer
     * reads. The poll backs off and tries again, up to its own bound.
     */
    data object Retryable : Reclaim

    /**
     * A permanent denial — the caller occupies no seat here, the seat is
     * permanently bot-driven, the match is gone, the payload was malformed, or
     * the response said `reclaimed: false` with a reason this layer does not
     * know. Retrying cannot change any of those, so the poll stops. Deliberately
     * terminal even for an unknown shape: a reclaim that did not say it
     * returned the seat has not returned it, and looping on that guess would
     * spend calls to learn nothing new.
     */
    data object Denied : Reclaim
  }
}
