package com.estemshan.functions

import com.estemshan.engine.DisconnectVote
import com.estemshan.engine.DisconnectVoteChoice
import com.estemshan.engine.DisconnectVotePhase
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.DisconnectVoteStatus
import com.estemshan.engine.MatchMode

/**
 * S74 runtime — the disconnect flow's pure decision: the heartbeat scan that
 * detects a dropped seat, the single-ballot queue, the close, the granted pause
 * and the replacement record. No Firestore type reaches here — the same split
 * [TimeoutResolution] keeps from `resolveTimeoutOnce`: this file decides WHAT
 * happens and WHAT is written; `DisconnectVoteDb.kt` owns the reads and the
 * transaction that makes the writes atomic.
 *
 * **What this flow is.** DEC-DISC-01 / A5.2-A5.3, ratified by the owner's
 * 2026-10-17 walk-through. A seat's heartbeat goes quiet; from the instant that
 * is detected the seat plays the match's CONFIGURED bot (DEC-RD21-01) and the
 * game continues. ONE 30-second ballot goes to the remaining eligible humans
 * (majority = 2 of 3, [DisconnectVote]), a winning WAIT grants a bounded 90 s
 * pause AFTER the verdict, no agreement inside 30 s continues with the bot, and
 * a reconnect takes the seat back while the table is still waiting. In RANKED
 * the question is never asked ([modeEligible] is the one mode gate every front
 * door consults).
 *
 * **The one-ballot queue.** The ratified mechanics describe a single ballot
 * over a live table, so this flow keeps exactly one: at most one entry sits in
 * `VOTE` or `WAIT`. A second seat that drops while a flow is live is still
 * held from its own instant of detection — `HOLD`, bot-played, reclaimable —
 * and its ballot opens when the table is free again. Nothing about the second
 * arrival changes the first ballot's electorate or its clock.
 *
 * **Who is asked ([electorateUids]).** Everyone seated except: the seat the
 * ballot is about; seats whose participation has already ended (Vote Kick's
 * `botSeats`/`removedUids`, the 15-timeout list); and seats this flow itself
 * already holds (including seats that are permanently replaced). An RD19
 * `inactiveTakeover` seat IS asked: it is a present-but-ignoring participant
 * under another mechanism, still its owner's to answer — the same reading
 * `MatchDoc` pins ("a borrowed seat is still the player's own"). The 2-of-3
 * threshold is fixed ([DisconnectVote.decideVote]); a smaller electorate can
 * still reach it, and can also fail to — no agreement is a ruled outcome, not
 * an error.
 *
 * **The heartbeat, and what detection is NOT.** [DisconnectVoteState.detectionDue]
 * counts silence on the player's OWN `players/{uid}.lastSeenAt` (S19's 30 s
 * cadence). It is deliberately not an app-lifecycle signal: a kill, a network
 * drop and a frozen process are indistinguishable from the outside (R7), the
 * graceful-exit signal would miss exactly the drop this flow exists for, and
 * the only consequence here is a BORROWAL — never a departure, never a charge.
 * The one thing this layer must never do is mistake a quiet-but-present player
 * for a gone one, which is why the threshold errs late and a missing heartbeat
 * never detects at all.
 */
object DisconnectVoteFlow {

  /** Canonical refusal reasons. The seam maps these to the client's next action. */
  object Reason {
    /** Nothing is held and nobody is stale; [retryAfterMillis] says when to look again. */
    const val NOT_DETECTED = "NOT_DETECTED"

    /** A flow exists (or a seat was held) but nothing is due right now. */
    const val NOT_YET_DUE = "NOT_YET_DUE"

    /** RANKED — or a mode the document does not name: the question is never asked (A5.2:436). */
    const val NOT_APPLICABLE = "NOT_APPLICABLE"

    /** A ballot is open but the caller is not one of the humans it asks. */
    const val NOT_ELIGIBLE = "NOT_ELIGIBLE"

    /** No ballot is open at all. */
    const val NO_BALLOT = "NO_BALLOT"

    /** The ballot's 30 s window has passed; the caller should ask the resolver to close it. */
    const val BALLOT_CLOSED = "BALLOT_CLOSED"

    /** Nobody is holding the caller's seat — nothing to return. */
    const val NOT_HELD = "NOT_HELD"

    /** The waiting ended: the seat is permanently the configured bot's. */
    const val REPLACED = "REPLACED"
  }

  /** The applied-step vocabulary, one name per transition, highest priority last. */
  object Action {
    const val HELD = "HELD"
    const val OPENED = "OPENED"
    const val WAITED = "WAITED"
    const val REPLACED = "REPLACED"

    internal val rank = mapOf(HELD to 0, OPENED to 1, WAITED to 2, REPLACED to 3)
  }

  /**
   * The resolver's retry floor, matching [TimeoutResolution.MIN_RETRY_AFTER_MILLIS]'s
   * reason: a client that just called must never be told to call again in 0 ms
   * because a boundary landed on its own clock, and the value is the SERVER's.
   */
  const val MIN_RETRY_AFTER_MILLIS = 1_000L

  /** A seat the resolver must read a heartbeat for. */
  data class Candidate(val seatId: String, val uid: String)

  /** One resolver pass's outcome. */
  sealed interface Step {

    /** Nothing to write, with the server's answer for the caller's poll. */
    data class NoWrite(
      val reason: String,
      val message: String,
      val retryAfterMillis: Long?,
    ) : Step

    /**
     * A transition to write: [marker] replaces the whole `disconnectVote`
     * field, [replacedUids] are appended to the flow's own provenance list,
     * and [ballotDeletes] are the voter uids whose ballots end with this
     * transition (the ballot's own close — a `HOLD`/`WAIT`/detection step has
     * none).
     */
    data class Applied(
      val action: String,
      val seatId: String,
      val marker: Map<String, Any?>,
      val replacedUids: List<String>,
      val ballotDeletes: List<String>,
      val retryAfterMillis: Long,
    ) : Step
  }

  // ── the one mode gate ────────────────────────────────────────────────

  /**
   * The RD28 canary, in ONE place: ROOM and UNRANKED ask; RANKED never does;
   * a mode the document does not name is treated like RANKED (fail-closed —
   * never open a flow on a document whose mode cannot be trusted). An absent
   * field reads as ROOM, the same no-migration default `MatchDoc` pins.
   */
  fun modeEligible(fields: Map<String, Any?>): Boolean {
    val modeName = fields["mode"] as? String ?: MatchMode.ROOM.name
    val mode = MatchMode.entries.firstOrNull { it.name == modeName } ?: return false
    return when (mode) {
      MatchMode.ROOM, MatchMode.UNRANKED -> true
      MatchMode.RANKED -> false
    }
  }

  // ── the reads the resolver needs before deciding ─────────────────────

  private fun seatsOf(fields: Map<String, Any?>): Map<String, String> {
    val raw = fields["seats"] as? Map<*, *> ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    for ((key, value) in raw) {
      val seat = key as? String ?: continue
      val uid = value as? String ?: continue
      out[seat] = uid
    }
    return out
  }

  private fun stringSet(raw: Any?): Set<String> =
    ((raw as? List<*>)?.mapNotNull { it as? String }).orEmpty().toSet()

  /**
   * The seats whose heartbeat the resolver must read: seated, no entry of
   * their own, and not already driven by a permanent mechanism (Vote Kick's
   * `botSeats`, the termination lists). The reads are bounded by the table.
   */
  fun candidates(fields: Map<String, Any?>): List<Candidate> =
    candidates(
      seatsOf(fields),
      DisconnectVoteState.parseAll(fields[DisconnectVoteState.VOTE_FIELD]),
      fields,
    )

  private fun candidates(
    seats: Map<String, String>,
    entries: Map<String, DisconnectVoteState>,
    fields: Map<String, Any?>,
  ): List<Candidate> {
    val terminated = terminatedUids(fields)
    val botSeats = stringSet(fields["botSeats"])
    return seats.entries
      .sortedBy { it.key }
      .mapNotNull { (seat, uid) ->
        when {
          entries.containsKey(seat) -> null
          seat in botSeats -> null
          uid in terminated -> null
          else -> Candidate(seat, uid)
        }
      }
  }

  /**
   * The humans a ballot about [departedSeatId] asks — see the file KDoc for
   * the exact exclusions. Deterministic: canonical seat order.
   */
  fun electorateUids(fields: Map<String, Any?>, departedSeatId: String): List<String> {
    val seats = seatsOf(fields)
    val entries = DisconnectVoteState.parseAll(fields[DisconnectVoteState.VOTE_FIELD])
    val terminated = terminatedUids(fields)
    val botSeats = stringSet(fields["botSeats"])
    return seats.entries
      .sortedBy { it.key }
      .mapNotNull { (seat, uid) ->
        when {
          seat == departedSeatId -> null
          entries.containsKey(seat) -> null
          seat in botSeats -> null
          uid in terminated -> null
          else -> uid
        }
      }
  }

  /**
   * S7: every uid whose participation has ended — Vote Kick's `removedUids`,
   * the 15-timeout list, and the 7th takeover's own `takeoverRemovedUids`
   * (A7.3: a takeover-removed uid cannot be elected to or vote in any later
   * ballot). Read-side union only, exactly like [TimeoutResolution.botControlledSeats].
   */
  private fun terminatedUids(fields: Map<String, Any?>): Set<String> =
    stringSet(fields["removedUids"]) +
      stringSet(fields["timeoutRemovedUids"]) +
      TimeoutResolution.takeoverRemovedUids(fields)

  /** The open ballot whose 30 s window has passed, if any — the close is due. */
  fun dueBallot(fields: Map<String, Any?>, nowMillis: Long): DisconnectVoteState? {
    val live = DisconnectVoteState.liveBallot(fields[DisconnectVoteState.VOTE_FIELD]) ?: return null
    val deadline = live.deadlineMillis ?: return null
    return if (nowMillis >= deadline) live else null
  }

  /** One ballot document's answer to [seatId], or null when it answers something else. */
  fun ballotChoice(raw: Any?, seatId: String): DisconnectVoteChoice? {
    val map = raw as? Map<*, *> ?: return null
    if (map[DisconnectVoteState.BALLOT_SEAT_FIELD] != seatId) return null
    val name = map[DisconnectVoteState.BALLOT_CHOICE_FIELD] as? String ?: return null
    return DisconnectVoteChoice.entries.firstOrNull { it.name == name }
  }

  // ── the resolver's one step ──────────────────────────────────────────

  /**
   * One resolver pass over the committed document, in the ratified order:
   * close a due ballot, end a due pause, detect newly dropped seats, then
   * open the first queued ballot if the table is free. Cascading within one
   * call is deliberate (a late poll settles the ballot AND starts the next
   * one); the transaction re-derives everything from the committed document,
   * so a racer at any point re-reads and finds the step already taken.
   *
   * [lastSeenByUid] carries the heartbeat reads for [candidates] and
   * [ballotChoices] the recorded answers for a due ballot's electorate.
   */
  fun step(
    fields: Map<String, Any?>,
    nowMillis: Long,
    lastSeenByUid: Map<String, Long?>,
    ballotChoices: Map<String, DisconnectVoteChoice>,
  ): Step {
    if (!modeEligible(fields)) {
      return Step.NoWrite(
        Reason.NOT_APPLICABLE,
        "resolveDisconnectVote: this mode never asks the question.",
        null,
      )
    }
    val seats = seatsOf(fields)
    if (seats.isEmpty()) {
      return Step.NoWrite(
        Reason.NOT_APPLICABLE,
        "resolveDisconnectVote: no seats on the document.",
        null,
      )
    }
    val raw = fields[DisconnectVoteState.VOTE_FIELD]
    val entries = LinkedHashMap(DisconnectVoteState.parseAll(raw))
    val changed = LinkedHashSet<DisconnectVoteState>()
    val replacedUids = mutableListOf<String>()
    val deletes = LinkedHashSet<String>()
    var action: String? = null
    var actionSeat: String? = null

    fun record(candidate: String, seatId: String) {
      val current = action
      if (current == null || Action.rank.getValue(candidate) > Action.rank.getValue(current)) {
        action = candidate
        actionSeat = seatId
      }
    }

    fun apply(state: DisconnectVoteState) {
      entries[state.seatId] = state
      changed += state
    }

    // 1. Close a due ballot — the 30 s window ends, the majority decides.
    val live = dueBallot(fields, nowMillis)
    if (live != null) {
      val closedAt = live.deadlineMillis ?: nowMillis
      val status = DisconnectVote.decideVote(ballotChoices.values.toList())
      // The waiting ends where the RULED window says it does: at the ballot's
      // own close for CONTINUE/NO_AGREEMENT, and at the granted pause's bound
      // for WAIT — never at this call's own clock, so a poll that arrives
      // after the waiting already elapsed (a late trigger, a racer, nobody
      // polling for minutes) records the ruled instants instead of re-granting
      // time the table already served.
      val replacementAt = DisconnectVoteState.replacementAtMillis(closedAt, status)
      deletes += electorateUids(fields, live.seatId)
      if (status == DisconnectVoteStatus.WAIT && nowMillis < replacementAt) {
        apply(live.copy(phase = DisconnectVotePhase.WAIT, deadlineMillis = replacementAt, verdict = status))
        record(Action.WAITED, live.seatId)
      } else {
        // CONTINUE / NO_AGREEMENT — or a WAIT whose granted pause already
        // elapsed while no one was polling: the waiting ends here.
        apply(live.copy(phase = DisconnectVotePhase.REPLACED, deadlineMillis = replacementAt, verdict = status))
        seats[live.seatId]?.let { replacedUids += it }
        record(Action.REPLACED, live.seatId)
      }
    }

    // 2. End a granted pause whose bound has passed: waiting excepted.
    for (waiting in entries.values.filter { it.phase == DisconnectVotePhase.WAIT }) {
      val deadline = waiting.deadlineMillis ?: continue
      if (nowMillis < deadline) continue
      apply(waiting.copy(phase = DisconnectVotePhase.REPLACED))
      seats[waiting.seatId]?.let { replacedUids += it }
      record(Action.REPLACED, waiting.seatId)
    }

    // 3. Detect newly dropped seats: held from this instant, queued.
    for (candidate in candidates(seats, entries, fields)) {
      if (DisconnectVoteState.detectionDue(lastSeenByUid[candidate.uid], nowMillis)) {
        apply(
          DisconnectVoteState(
            seatId = candidate.seatId,
            phase = DisconnectVotePhase.HOLD,
            detectedAtMillis = nowMillis,
          ),
        )
        record(Action.HELD, candidate.seatId)
      }
    }

    // 4. Open the first queued ballot if the table is free (single ballot).
    val liveNow = entries.values.any {
      it.phase == DisconnectVotePhase.VOTE || it.phase == DisconnectVotePhase.WAIT
    }
    if (!liveNow) {
      val queued = entries.entries.sortedBy { it.key }
        .firstOrNull { it.value.phase == DisconnectVotePhase.HOLD }
      if (queued != null) {
        apply(
          queued.value.copy(
            phase = DisconnectVotePhase.VOTE,
            deadlineMillis = nowMillis + DisconnectVote.voteWindowSec * 1_000L,
            answeredCount = 0,
            verdict = null,
          ),
        )
        record(Action.OPENED, queued.key)
      }
    }

    val nextDue = nextDueMillis(entries.values, seats, fields, lastSeenByUid, nowMillis)
    val retryAfter = (nextDue - nowMillis).coerceAtLeast(MIN_RETRY_AFTER_MILLIS)

    val settledAction = action
    if (settledAction == null) {
      return if (entries.isEmpty()) {
        Step.NoWrite(
          Reason.NOT_DETECTED,
          "resolveDisconnectVote: no seat is disconnected and no flow is pending.",
          retryAfter,
        )
      } else {
        Step.NoWrite(
          Reason.NOT_YET_DUE,
          "resolveDisconnectVote: a flow is pending but nothing is due yet.",
          retryAfter,
        )
      }
    }

    var marker: Any? = raw
    for (state in changed) marker = DisconnectVoteState.withEntry(marker, state)
    @Suppress("UNCHECKED_CAST")
    return Step.Applied(
      action = settledAction,
      seatId = actionSeat!!,
      marker = marker as Map<String, Any?>,
      replacedUids = replacedUids,
      ballotDeletes = deletes.toList(),
      retryAfterMillis = retryAfter,
    )
  }

  /**
   * The next instant this flow could have anything to do: a live bound coming
   * due, or the earliest heartbeat that would cross the staleness threshold.
   * The server owns the client's next call — `lastSeenAt` is not on any
   * readable document, so a client could not derive this itself.
   */
  private fun nextDueMillis(
    entries: Collection<DisconnectVoteState>,
    seats: Map<String, String>,
    fields: Map<String, Any?>,
    lastSeenByUid: Map<String, Long?>,
    nowMillis: Long,
  ): Long {
    var next: Long? = null
    fun consider(candidate: Long) {
      if (candidate > nowMillis) next = minOf(next ?: candidate, candidate)
    }
    for (entry in entries) {
      val deadline = entry.deadlineMillis ?: continue
      if (entry.phase == DisconnectVotePhase.VOTE || entry.phase == DisconnectVotePhase.WAIT) {
        consider(deadline)
      }
    }
    val entryMap = entries.associateBy { it.seatId }
    for (candidate in candidates(seats, entryMap, fields)) {
      val lastSeen = lastSeenByUid[candidate.uid] ?: continue
      consider(lastSeen + DisconnectVoteState.HEARTBEAT_STALE_AFTER_MILLIS)
    }
    return next ?: (nowMillis + DisconnectVoteState.HEARTBEAT_STALE_AFTER_MILLIS)
  }

  // ── casting one ballot ───────────────────────────────────────────────

  /** One cast attempt's outcome. */
  sealed interface Cast {
    data class NoWrite(val reason: String, val message: String) : Cast
    data class AlreadyRecorded(val seatId: String) : Cast
    data class Recorded(
      val seatId: String,
      val choice: DisconnectVoteChoice,
      val marker: Map<String, Any?>,
      val castAtMillis: Long,
    ) : Cast
  }

  /**
   * One remaining eligible human's answer. First answer stands: a second
   * attempt mid-window is [Cast.AlreadyRecorded] and rewrites nothing, so a
   * retried call can never double-count. Nothing here settles the ballot —
   * the majority is the resolver's close, and the window is the seat's.
   */
  fun cast(
    fields: Map<String, Any?>,
    uid: String,
    choice: DisconnectVoteChoice,
    nowMillis: Long,
    hasExistingBallot: Boolean,
  ): Cast {
    if (!modeEligible(fields)) {
      return Cast.NoWrite(Reason.NOT_APPLICABLE, "castDisconnectVote: this mode never asks the question.")
    }
    val live = DisconnectVoteState.liveBallot(fields[DisconnectVoteState.VOTE_FIELD])
      ?: return Cast.NoWrite(Reason.NO_BALLOT, "castDisconnectVote: no ballot is open.")
    val deadline = live.deadlineMillis
      ?: return Cast.NoWrite(Reason.NO_BALLOT, "castDisconnectVote: no ballot is open.")
    if (nowMillis >= deadline) {
      return Cast.NoWrite(Reason.BALLOT_CLOSED, "castDisconnectVote: the ballot's window has passed.")
    }
    if (uid !in electorateUids(fields, live.seatId)) {
      return Cast.NoWrite(Reason.NOT_ELIGIBLE, "castDisconnectVote: this ballot does not ask you.")
    }
    if (hasExistingBallot) return Cast.AlreadyRecorded(live.seatId)
    return Cast.Recorded(
      seatId = live.seatId,
      choice = choice,
      marker = DisconnectVoteState.withEntry(
        fields[DisconnectVoteState.VOTE_FIELD],
        live.copy(answeredCount = live.answeredCount + 1),
      ),
      castAtMillis = nowMillis,
    )
  }

  // ── taking the seat back ─────────────────────────────────────────────

  /** One reconnect attempt's outcome. */
  sealed interface Restore {
    /** Nobody is holding the caller's seat (already back, or never taken). */
    data object NothingHeld : Restore

    /** The waiting ended without a reconnect: the seat is permanently the bot's. */
    data class Replaced(val seatId: String) : Restore

    /**
     * The seat returns: its entry is removed (every other seat's kept
     * verbatim), and if its ballot was open the ballots end with it. No
     * charge: a borrowal is not a departure (A5.3).
     */
    data class Restored(
      val seatId: String,
      val marker: Map<String, Any?>,
      val ballotDeletes: List<String>,
    ) : Restore
  }

  /**
   * Owner-only by construction: the seat comes from the CALLER'S verified uid
   * through the document's own `seats` map — a restore cannot name a seat.
   * Reclaimable exactly while the table waits: `HOLD`, the whole `VOTE`
   * window, and the granted `WAIT` pause. A `REPLACED` seat is not — and the
   * answer says so instead of pretending otherwise.
   */
  fun restore(fields: Map<String, Any?>, uid: String): Restore {
    val seats = seatsOf(fields)
    val seat = seats.entries.firstOrNull { it.value == uid }?.key ?: return Restore.NothingHeld
    if (!modeEligible(fields)) return Restore.NothingHeld
    val entry = DisconnectVoteState.parseAll(fields[DisconnectVoteState.VOTE_FIELD])[seat]
      ?: return Restore.NothingHeld
    return when (entry.phase) {
      DisconnectVotePhase.REPLACED -> Restore.Replaced(seat)
      // A8.2 / Q36: a CANCELLED entry is a dead flow, not a held seat — the
      // 7th takeover removed the seat and this marker is the leftover. Nothing
      // is held, nothing is reclaimable, and no path below ever writes a
      // CANCELLED entry back into a live phase.
      DisconnectVotePhase.CANCELLED -> Restore.NothingHeld
      DisconnectVotePhase.VOTE -> Restore.Restored(
        seatId = seat,
        marker = DisconnectVoteState.withoutSeat(fields[DisconnectVoteState.VOTE_FIELD], seat),
        ballotDeletes = electorateUids(fields, seat),
      )
      DisconnectVotePhase.HOLD, DisconnectVotePhase.WAIT -> Restore.Restored(
        seatId = seat,
        marker = DisconnectVoteState.withoutSeat(fields[DisconnectVoteState.VOTE_FIELD], seat),
        ballotDeletes = emptyList(),
      )
    }
  }
}
