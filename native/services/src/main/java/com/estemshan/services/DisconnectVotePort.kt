package com.estemshan.services

import com.estemshan.engine.DisconnectVoteChoice

/**
 * S74 (DEC-DISC-01; A5.2/A5.3) — the seam over the disconnect flow's three
 * callables: the resolver poll, one ballot answer, and the owner's reconnect.
 *
 * Layering (docs/specs/02-engine-api.md §6):
 *   UI → DisconnectVotePort → (Cloud Functions) → Firestore
 *
 * WHY A SEAM AT ALL — [SeatReclaimPort]'s reason, exactly: this repo ships no
 * Firebase fake by design, so the interface is what lets a hermetic JVM test
 * stand in for the callables while the instrumented suite drives the real
 * thing. And the flow could not be a client write even if it wanted to be:
 * the marker (`matches/{id}.disconnectVote`), the provenance list
 * (`disconnectReplacedUids`) and every ballot
 * (`matches/{id}/disconnectVotes/{uid}`, which has no rules block at all) are
 * unwritable from any device — while the fact that DECIDES everything, the
 * player's own `players/{uid}.lastSeenAt` heartbeat, is unreadable to every
 * client by rule (R7). Detection, the window bounds, the majority and the
 * replacement instant are therefore all server facts computed inside one
 * transaction on the server's clock; this port is the only door.
 *
 * WHAT THE CLIENT IS ALLOWED TO KNOW — [SeatReclaimPort]'s doctrine again, and
 * the same three-key discipline: the response's `matchId`/`seatId`/`message`
 * are dropped, because the match snapshot already tells the caller which seat
 * is theirs and echoing it back would give the poll a second, possibly
 * disagreeing answer. No ballot content, no answered count, no heartbeat and
 * no clock reaches these outcomes — only the next action and the SERVER's own
 * next-look instant, because `lastSeenAt` is not on any readable document, so
 * a client could not derive the cadence even if it tried.
 *
 * WHY THE THREE CALLS SPLIT THIS WAY — `resolve` is the bounded poll every
 * seated client may drive (host-less by design: the seat that dropped cannot
 * poll for itself); `cast` is one answer by one eligible human and is
 * idempotent (a repeat answers [Cast.AlreadyRecorded] and rewrites nothing, so
 * "2 of 3" can never double-count); `restore` returns the caller's OWN seat
 * and can name no seat at all — it is derived from the verified uid through
 * the document's own seats map. In RANKED none of them ever asks the question:
 * [Resolve.NotApplicable] is the callable's own terminal answer there, and the
 * poll must stop rather than schedule another look.
 */
interface DisconnectVotePort {

  /**
   * One resolver pass, from any seated client. Every transition of the flow —
   * detection, the ballot opening, its close, the pause ending, the
   * replacement record — happens inside this call, so the poll is the flow's
   * only driver. Idempotent and safe to retry: the resolver re-derives
   * everything from the committed document inside its transaction, so a racer
   * finds the step already taken and answers a quiet [Resolve.Waiting] or
   * [Resolve.Advanced] rather than writing twice.
   */
  suspend fun resolve(matchId: String): Resolve

  /**
   * The caller's answer to the open ballot. The caller names the match and its
   * choice — never a seat, never a count: the seat under the question and the
   * caller's eligibility both come from the committed document, and the answer
   * is recorded under the verified uid. First answer stands.
   */
  suspend fun cast(matchId: String, choice: DisconnectVoteChoice): Cast

  /**
   * The caller's reconnect. Owner-only by construction — a restore can return
   * nothing but the caller's own seat. Reclaimable exactly while the table
   * waits: through the whole ballot and any granted pause; a borrowal is never
   * a departure and never a charge. A seat whose waiting already ended answers
   * [Restore.Replaced], terminal.
   */
  suspend fun restore(matchId: String): Restore

  /**
   * The resolver's outcomes, split by what the poll does next. [Advanced] and
   * [Waiting] both carry the SERVER's own next-look instant and differ only in
   * whether a transition was just committed — the distinction a bounded poll
   * wants for its backoff, and nothing more.
   */
  sealed interface Resolve {

    /**
     * A transition was committed (a seat held, a ballot opened or granted, a
     * pause ended, a replacement recorded). Re-arm on [retryAfterMillis] and
     * keep watching — the snapshot listener carries what changed.
     */
    data class Advanced(val retryAfterMillis: Long) : Resolve

    /**
     * Nothing was due on this pass. [retryAfterMillis] is the server's own
     * next-due instant — a live window's close, the pause's end, or the
     * earliest heartbeat that would cross the staleness threshold — never the
     * client's estimate, because the client cannot read a peer's heartbeat.
     */
    data class Waiting(val retryAfterMillis: Long) : Resolve

    /**
     * RANKED — or a mode the callable cannot trust. The question is never
     * asked there (A5.2:436) and the answer is terminal by design: this seat's
     * app must stop polling, not schedule another look, because no look will
     * ever have anything to do.
     */
    data object NotApplicable : Resolve

    /**
     * The callable refused the call — the caller is not a player here, the
     * match is gone, or the payload was malformed. Retrying cannot change any
     * of those, so the poll stops.
     */
    data object Denied : Resolve

    /**
     * A transient failure — a retryable transport error, no network, or a
     * response that is not the map this layer reads. Back off and try again,
     * up to the poll's own bound.
     */
    data object Retryable : Resolve
  }

  /** One cast attempt's outcome. Every variant but [Retryable] is terminal. */
  sealed interface Cast {

    /** The answer is on record. The snapshot count is the display's source. */
    data object Recorded : Cast

    /**
     * The answer was already on record (this device retried, or the call
     * raced). Not a failure and not a second vote: nothing was rewritten.
     */
    data object AlreadyRecorded : Cast

    /**
     * The 30 s window passed before the answer landed. The resolver's own poll
     * settles the ballot, so there is nothing to retry here.
     */
    data object BallotClosed : Cast

    /**
     * The ballot does not ask the caller — not one of the remaining eligible
     * humans, no ballot open at all, or a mode that never asks — with the
     * server's `reason` carried verbatim for the log. Terminal: asking again
     * cannot make a ballot open onto this caller.
     */
    data class Refused(val reason: String) : Cast

    /**
     * A transient failure — a retryable transport error, no network, or a
     * response that is not the map this layer reads.
     */
    data object Retryable : Cast
  }

  /** One reconnect attempt's outcome. */
  sealed interface Restore {

    /**
     * The seat is back. Nothing needs to be delivered — the snapshot listener
     * already carries the marker change, so the table shows it on its own.
     */
    data object Restored : Restore

    /**
     * The waiting ended without a reconnect: the seat is permanently the
     * configured bot's for the rest of the match. Terminal, and deliberately
     * not a wait — the charge table (UNRANKED's 2 Koz; ROOM's none; RANKED has
     * no path at all) applies downstream of the document's own record.
     */
    data object Replaced : Restore

    /**
     * Nobody held the caller's seat — the call arrived after another device's
     * restore, or the marker read fail-closed as "not held". Also the honest
     * reading of a Ranked document. Terminal: the seat is already the
     * caller's.
     */
    data object NothingHeld : Restore

    /** The callable refused the call. Terminal — asking again changes nothing. */
    data object Denied : Restore

    /** A transient failure — a retryable transport error or an unreadable response. */
    data object Retryable : Restore
  }
}
