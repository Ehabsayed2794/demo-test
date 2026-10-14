package com.estemshan.services

/**
 * S61 — the seam over the `resolveDecisionTimeout` callable.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → DecisionTimeoutPort → (Cloud Functions resolver) → Firestore
 *
 * WHY THE SEAM — the same reason every other service boundary in this module
 * is an interface: this repo ships no [com.google.firebase.firestore]
 * fake by design, and `FirebaseFunctions` is no different. The alarm in
 * `OnlineMatchViewModel` stands in for this interface in a hermetic JVM test
 * and drives the real callable against the Functions emulator in the
 * instrumented suite. The UI never touches the SDK.
 *
 * WHY A CALLABLE AT ALL — the deciding hand is owner-read-only
 * (`allow get: if ownsSeat()`), so the acting client cannot see the cards it
 * would need to pick a move. The decision is computed server-side with the
 * admin SDK and only the chosen card is written back; this port is how a
 * table that is still waiting gets that resolution started. Any player may
 * call it — the reliability constraint is that a resolution must NOT depend
 * on the acting player's foreground process, so the alarm arms for the seat
 * the table is waiting on regardless of whose client is running.
 *
 * COUNTER PRIVACY — [Resolution] deliberately carries no timeout count. The
 * counter is owner-read-only per seat and never rides the callable response;
 * the caller learns only whether a decision landed.
 */
interface DecisionTimeoutPort {

  /**
   * Asks the resolver to settle the match's currently-pending decision if its
   * deadline has passed. Idempotent: the resolver re-derives everything from
   * the committed document inside one transaction, so a raced, retried, or
   * early call resolves to a [Resolution.NotYetExpired] or a no-write rather
   * than a second move. Safe to call repeatedly and concurrently.
   */
  suspend fun resolve(matchId: String): Resolution

  /**
   * The outcomes, split by what the alarm should do next.
   */
  sealed interface Resolution {

    /**
     * The resolver applied a bot decision. The document update it caused is
     * the whole signal — the snapshot listener delivers it and re-arms for the
     * NEXT decision, so the alarm terminates here rather than polling.
     */
    data object Applied : Resolution

    /**
     * The deadline has not passed. [retryAfterMillis] is the SERVER's
     * remaining time, computed on the same clock that judged expiry and floored
     * so it is never zero or negative. The alarm re-arms on this value instead
     * of its own estimate: `MatchDoc` carries no `updatedAt`, so the client
     * cannot derive the anchor and its own estimate drifts with clock skew.
     * Not a failure — nothing is retried, the wait is rescheduled.
     */
    data class NotYetExpired(val retryAfterMillis: Long) : Resolution

    /**
     * The match is not waiting on a timed decision — the round is complete, the
     * match is complete, or the document is mid-transition. Terminal for this
     * alarm: the next snapshot that opens a decision re-arms.
     */
    data object NotActive : Resolution

    /**
     * A transient failure — the callable threw a retryable transport error.
     * The alarm backs off and tries again, up to its bound.
     */
    data object Retryable : Resolution

    /**
     * A permanent denial — the caller is not a player, the match is gone, or
     * the resolver failed closed on history it will not invent. Retrying cannot
     * change the outcome, so the alarm stops.
     */
    data object Denied : Resolution
  }
}

/**
 * S61 — the client-side mirror of the resolver's decision window, so the
 * alarm's FIRST arm is a sane upper bound on the deadline.
 *
 * The client cannot see the deadline anchor (`updatedAt` is not on
 * [com.estemshan.services.model.MatchDoc] and [com.estemshan.services.MatchSnapshot]
 * carries only the parsed model), so it estimates the deadline as
 * `snapshotArrival + window`. A snapshot always arrives after the write that
 * anchored it, so `snapshotArrival + window` is at or past the real deadline —
 * the alarm fires late rather than early, and if clock skew or a stale
 * snapshot still hands it an early moment, the callable answers
 * [DecisionTimeoutPort.Resolution.NotYetExpired] with the authoritative
 * remaining time and the alarm re-arms on THAT. The estimate is a convenience;
 * the server is the authority.
 *
 * The two constants mirror `TimeoutResolution`'s own (RD21's phase offset and
 * S61's bot pacing). `:functions` cannot see this module, so drift between the
 * copies is caught only by the instrumented suite — keep them in sync.
 */
object DecisionWindow {

  /** A bot-driven seat does not make the table wait the human window. */
  const val BOT_DECISION_SECONDS = 2

  /** RD21 (S37): every bidding decision gets five extra seconds. */
  const val BIDDING_OFFSET_SECONDS = 5

  /**
   * The window for a decision by [bidding] phase. A bot-controlled seat
   * (removed by Vote Kick or by the timeout counter) always gets the short
   * window — the pacing fix is pointless if the client still arms the alarm at
   * the human window and stalls the table for `timerSeconds` first.
   */
  fun durationSeconds(timerSeconds: Int, bidding: Boolean, botControlled: Boolean): Int =
    if (botControlled) BOT_DECISION_SECONDS
    else timerSeconds + if (bidding) BIDDING_OFFSET_SECONDS else 0
}
