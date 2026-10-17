package com.estemshan.engine

import kotlin.math.floor

/**
 * S74 runtime — the persisted state of the disconnect vote: the public marker
 * on the match document, the private ballot document's field names, and the
 * read that turns the marker back into "who is driving this seat right now".
 *
 * Pure shape + parsing only, exactly like [InactiveTakeover]: no Firestore, no
 * clock source, no bot. It lives in `:engine` because BOTH authority sites need
 * the same names without a mirror — the Cloud Functions callables that WRITE
 * the marker (`:functions` depends on `:engine` and cannot see `:services`) and
 * the client that READS it to draw the table's state and hand the seat back
 * (`:app` → `:services` → `:engine`).
 *
 * **The flow, as ratified (CANONICAL_RULES A5.2/A5.3, owner closure 2026-10-16
 * §2, owner walk-through 2026-10-17 §6).** A seat's heartbeat goes quiet; from
 * the moment that is detected the seat is played by the match's CONFIGURED bot
 * profile (DEC-RD21-01) and play continues — no pause while anything is
 * pending. One 30-second ballot is put to the REMAINING ELIGIBLE HUMANS (the
 * configured bot default is the fallback, A5.2:434); majority = 2 of 3 answers
 * settles it; a winning WAIT pauses the match for a bounded 90 seconds
 * beginning AFTER the verdict; no agreement inside 30 s continues with the
 * configured bot. The seat is RECLAIMABLE — a borrowal, never a departure and
 * never a charge — while the table waits: through the whole 30 s ballot and
 * through any granted 90 s pause. Reconnect ends the flow; the seat goes back
 * to its player. In RANKED the question is never asked at all (A5.2:436) —
 * nothing here is ever written on a Ranked document, and the reads below
 * enforce the mode gate rather than assume it.
 *
 * **The phases.**
 * - `HOLD` — detected and bot-held, no ballot yet (a ballot is single: at most
 *   one flow is in `VOTE` or `WAIT` at a time; further dropped seats queue here
 *   — still held from the instant of detection, still reclaimable).
 * - `VOTE` — the 30 s ballot is open ([deadlineMillis] = its close). Play
 *   continues; the seat is reclaimable.
 * - `WAIT` — the ballot closed with a WAIT verdict; the bounded 90 s pause is
 *   running ([deadlineMillis] = its end). The seat stays reclaimable.
 * - `REPLACED` — the waiting ended without a reconnect: the seat is
 *   permanently the configured bot's for the rest of the match, the uid is
 *   recorded on [REPLACED_UIDS_FIELD] for the charge table, and nothing
 *   reclaims it any more.
 *
 * **Why one public field and one private collection.** The frozen
 * `firestore.rules` cannot be widened for S74 (the same zero-rules doctrine
 * `timeoutRemovedUids` and S73 proved): a field no client-facing validator
 * names is unwritable from any device while the match document's own
 * whole-document `allow get` still lets every seat read it, and
 * `matches/{id}/disconnectVotes/{uid}` has no rules block at all, which makes
 * every ballot admin-only by construction. The individual answers are keyed by
 * VOTER, like `timeoutCounts/{uid}` and `inactivity/{uid}`: how a named player
 * answered is not their opponents' business — the public marker carries only
 * the progress ([answeredCount]) that "2 of 3" already makes public.
 */
data class DisconnectVoteState(
  /** The seat the flow holds. The map key is authoritative; this field is echoed for readers. */
  val seatId: String,
  val phase: DisconnectVotePhase,
  /** Server-clock millis at which the seat was detected (the flow's opening instant). */
  val detectedAtMillis: Long,
  /**
   * The phase's single bound, server-clock millis: the ballot's close while
   * [DisconnectVotePhase.VOTE], the pause's end while [DisconnectVotePhase.WAIT],
   * and the instant the waiting ended (the replacement moment) once
   * [DisconnectVotePhase.REPLACED]. Absent in [DisconnectVotePhase.HOLD].
   */
  val deadlineMillis: Long? = null,
  /** Ballots recorded so far while the ballot is open; the public "n of 3". */
  val answeredCount: Int = 0,
  /** The closed ballot's verdict; present from the close on (`WAIT`, `CONTINUE`, `NO_AGREEMENT`). */
  val verdict: DisconnectVoteStatus? = null,
) {

  fun toFields(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    put(SEAT_FIELD, seatId)
    put(PHASE_FIELD, phase.name)
    put(DETECTED_AT_FIELD, detectedAtMillis)
    if (deadlineMillis != null) put(DEADLINE_FIELD, deadlineMillis)
    if (answeredCount != 0) put(ANSWERED_FIELD, answeredCount)
    if (verdict != null) put(VERDICT_FIELD, verdict.name)
  }

  companion object {

    /**
     * `matches/{matchId}.disconnectVote` — the one public field: a map of seat
     * id to the entry above. Absent while no seat is held. Set by the resolver
     * on detection/transitions, cleared seat-by-seat by a reconnect.
     */
    const val VOTE_FIELD: String = "disconnectVote"

    /** `matches/{matchId}/disconnectVotes/{uid}` — the private ballot document. */
    const val BALLOT_COLLECTION: String = "disconnectVotes"

    /**
     * `matches/{matchId}.disconnectReplacedUids` — the disconnect flow's OWN
     * provenance list, keyed by uid and kept separate from Vote Kick's
     * `removedUids`, RD20's `timeoutRemovedUids` and RD19's `inactiveTakeovers`.
     * Appended at the [DisconnectVotePhase.REPLACED] transition; the charge
     * table (`DisconnectVote.permanentCharge`) is applied to it downstream, so
     * the runtime records the termination and never guesses the penalty.
     */
    const val REPLACED_UIDS_FIELD: String = "disconnectReplacedUids"

    /**
     * The heartbeat-staleness threshold: [detectionDue] fires at or after this
     * much silence. S19 re-stamps `players/{uid}.lastSeenAt` every 30 s while
     * the player's match app is alive, so 90 s tolerates two consecutive missed
     * beats (a single missed beat is ~60 s of silence in the worst case) and
     * still opens the ballot within about three intervals of a real drop.
     *
     * The direction of the error is deliberate: a false "disconnected" would
     * borrow a PRESENT player's seat and put a ballot about them to the table —
     * the costliest mistake this mechanism can make — so the threshold errs
     * late. (A signal that never existed — no `lastSeenAt` — never detects:
     * absence of evidence is not evidence of absence, and the seat keeps its
     * ordinary decision timeouts instead of being taken from someone whose
     * profile simply predates the heartbeat.)
     */
    const val HEARTBEAT_STALE_AFTER_MILLIS: Long = 90_000L

    const val SEAT_FIELD: String = "seatId"
    const val PHASE_FIELD: String = "phase"
    const val DETECTED_AT_FIELD: String = "detectedAtMillis"
    const val DEADLINE_FIELD: String = "deadlineMillis"
    const val ANSWERED_FIELD: String = "answeredCount"
    const val VERDICT_FIELD: String = "verdict"

    /** The ballot document's fields: which flow it answers, what, and when. */
    const val BALLOT_SEAT_FIELD: String = "seatId"
    const val BALLOT_CHOICE_FIELD: String = "choice"
    const val BALLOT_CAST_AT_FIELD: String = "castAtMillis"

    /** The marker entry's fields, in write order. */
    fun entryFieldNames(): List<String> = listOf(
      SEAT_FIELD,
      PHASE_FIELD,
      DETECTED_AT_FIELD,
      DEADLINE_FIELD,
      ANSWERED_FIELD,
      VERDICT_FIELD,
    )

    /** One ballot document's payload. */
    fun ballotFields(
      seatId: String,
      choice: DisconnectVoteChoice,
      castAtMillis: Long,
    ): Map<String, Any?> = linkedMapOf(
      BALLOT_SEAT_FIELD to seatId,
      BALLOT_CHOICE_FIELD to choice.name,
      BALLOT_CAST_AT_FIELD to castAtMillis,
    )

    /**
     * S74's detection predicate, and the whole of it: the player's OWN
     * heartbeat has been silent for at least [HEARTBEAT_STALE_AFTER_MILLIS].
     *
     * This is the connection-faithful signal, deliberately not an app-lifecycle
     * one: an app kill, a network drop and a frozen process are
     * indistinguishable from the outside (R7 — clients can never read a peer's
     * profile), and the flow must not try to tell them apart, because a
     * graceful-exit signal would MISS a network drop and MISFIRE on a
     * backgrounded app. What the flow does distinguish — the one distinction
     * that changes behaviour — is "temporarily gone" from "departed": a frozen
     * heartbeat borrows the seat ([DisconnectVotePhase.HOLD] is a borrowing,
     * never a removal) and only the ruled end of the waiting, with no
     * reconnect, makes the replacement permanent. [lastSeenMillis] null means
     * no signal: no detection.
     */
    fun detectionDue(lastSeenMillis: Long?, nowMillis: Long): Boolean =
      lastSeenMillis != null && nowMillis >= lastSeenMillis + HEARTBEAT_STALE_AFTER_MILLIS

    /**
     * THE PERMANENCE POLICY POINT — the one place the runtime decides WHEN a
     * disconnected seat becomes permanently replaced. Owner confirmation of
     * this reading is flagged in the S74 runtime report: the documents fix the
     * charge table and the reclaimable-while-waiting rule, but not the exact
     * transition instant, and this is the narrowest reading consistent with the
     * ratified walk-through (2026-10-17 §6): the seat stays reclaimable through
     * the whole 30 s ballot and through any granted 90 s pause, and the
     * replacement fires only when that waiting ends with no reconnect — at the
     * ballot's close for a `CONTINUE`/`NO_AGREEMENT` verdict, and at the pause's
     * bound for a `WAIT` verdict. Every other site derives from this function;
     * none re-derives the moment itself.
     */
    fun replacementAtMillis(closedAtMillis: Long, verdict: DisconnectVoteStatus): Long =
      when (verdict) {
        DisconnectVoteStatus.WAIT -> closedAtMillis + DisconnectVote.waitWindowSec * 1_000L
        DisconnectVoteStatus.CONTINUE, DisconnectVoteStatus.NO_AGREEMENT -> closedAtMillis
      }

    /**
     * One trusted entry, or null when it is absent OR untrustworthy. Fail-closed
     * in the direction [InactiveTakeover] documents: a corrupt entry reads as
     * "no flow", the seat keeps its ordinary pace, and a genuinely dropped
     * player is re-detected and re-held by the next resolver pass — corruption
     * can neither strand a human out of their seat nor hand a bot one.
     */
    fun parse(seatId: String, raw: Any?): DisconnectVoteState? {
      val entry = raw as? Map<*, *> ?: return null
      val phaseName = entry[PHASE_FIELD] as? String ?: return null
      val phase = DisconnectVotePhase.entries.firstOrNull { it.name == phaseName } ?: return null
      val detectedAt = asWholeLong(entry[DETECTED_AT_FIELD]) ?: return null
      if (detectedAt < 0L) return null
      val deadline = asWholeLong(entry[DEADLINE_FIELD])
      if (deadline != null && deadline < detectedAt) return null
      val answered = asWholeLong(entry[ANSWERED_FIELD]) ?: 0L
      if (answered < 0L || answered > 3L) return null
      val verdictName = entry[VERDICT_FIELD] as? String
      val verdict = verdictName?.let { name ->
        DisconnectVoteStatus.entries.firstOrNull { it.name == name }
      }
      if (verdictName != null && verdict == null) return null

      // The shape each phase is allowed to be — a structural contradiction is
      // corrupt state, not a phase to normalise.
      when (phase) {
        DisconnectVotePhase.HOLD ->
          if (deadline != null || verdict != null) return null
        DisconnectVotePhase.VOTE ->
          if (deadline == null || verdict != null) return null
        DisconnectVotePhase.WAIT ->
          if (deadline == null || verdict != DisconnectVoteStatus.WAIT) return null
        DisconnectVotePhase.REPLACED ->
          if (deadline == null || verdict == null) return null
      }
      return DisconnectVoteState(
        seatId = seatId,
        phase = phase,
        detectedAtMillis = detectedAt,
        deadlineMillis = deadline,
        answeredCount = answered.toInt(),
        verdict = verdict,
      )
    }

    /** Trusted entries of the whole map, keyed by seat id (the key is the seat). */
    fun parseAll(voteRaw: Any?): Map<String, DisconnectVoteState> {
      val map = voteRaw as? Map<*, *> ?: return emptyMap()
      val out = LinkedHashMap<String, DisconnectVoteState>()
      for ((seatId, raw) in map) {
        val key = seatId as? String ?: continue
        parse(key, raw)?.let { out[key] = it }
      }
      return out
    }

    /** The seats the flow drives right now — every phase is a bot-held seat. */
    fun heldSeats(voteRaw: Any?): Set<String> = parseAll(voteRaw).keys

    /**
     * The seats still RECLAIMABLE by their owner: held in `HOLD`, `VOTE` or
     * `WAIT`. A `REPLACED` seat is not — the waiting ended without a reconnect.
     */
    fun reclaimableSeats(voteRaw: Any?): Set<String> =
      parseAll(voteRaw).filterValues { it.phase != DisconnectVotePhase.REPLACED }.keys

    /**
     * The pause's end while ANY seat's ballot sits in its granted `WAIT`
     * window, else null. This is what the decision resolver consults: during
     * the pause no decision is resolved and no deadline runs — the pause is
     * "the table waits for its player" (A5.3 item 4), the one moment play is
     * genuinely stopped.
     */
    fun waitDeadlineMillis(voteRaw: Any?): Long? =
      parseAll(voteRaw).values.firstOrNull { it.phase == DisconnectVotePhase.WAIT }
        ?.deadlineMillis

    /**
     * The active ballot, if any: the single entry in `VOTE` (canonical seat
     * order when several somehow exist, so the choice is deterministic).
     */
    fun liveBallot(voteRaw: Any?): DisconnectVoteState? =
      parseAll(voteRaw).entries.sortedBy { it.key }
        .firstOrNull { it.value.phase == DisconnectVotePhase.VOTE }
        ?.value

    /**
     * The write that carries [state]'s seat's entry to `state`'s phase: the
     * target replaced, every other entry carried over VERBATIM — surgical like
     * [InactiveTakeover.withTakeover], so one transition can neither interpret
     * nor silently discard another seat's state.
     */
    fun withEntry(voteRaw: Any?, state: DisconnectVoteState): Map<String, Any?> =
      copyEntries(voteRaw).apply { put(state.seatId, state.toFields()) }

    /**
     * The write that ends [seatId]'s flow: its entry removed, every other
     * entry carried over verbatim. Removing an absent seat is a no-op, so a
     * re-delivered reconnect cannot disturb another seat's flow.
     */
    fun withoutSeat(voteRaw: Any?, seatId: String): Map<String, Any?> =
      copyEntries(voteRaw).apply { remove(seatId) }

    private fun copyEntries(voteRaw: Any?): LinkedHashMap<String, Any?> {
      val out = LinkedHashMap<String, Any?>()
      (voteRaw as? Map<*, *>)?.forEach { (key, value) ->
        if (key is String) out[key] = value
      }
      return out
    }

    /**
     * Firestore hands a JS `number` to Kotlin/JS as a `Double` and to the JVM as
     * an `Int`/`Long`, so any whole [Number] is trusted; a string, a nested map
     * or a fractional value is not. Null passes through (absence is meaningful
     * for [DEADLINE_FIELD] and [VERDICT_FIELD]).
     */
    private fun asWholeLong(raw: Any?): Long? {
      if (raw == null) return null
      val number = raw as? Number ?: return null
      val value = number.toDouble()
      if (value.isNaN() || value.isInfinite()) return null
      if (value != floor(value)) return null
      return number.toLong()
    }
  }
}

/**
 * The four phases of one seat's disconnect-vote entry, in lifecycle order:
 * held from detection, the ballot, the granted pause, the permanent
 * replacement. Serialized as the enum names.
 */
enum class DisconnectVotePhase { HOLD, VOTE, WAIT, REPLACED }
