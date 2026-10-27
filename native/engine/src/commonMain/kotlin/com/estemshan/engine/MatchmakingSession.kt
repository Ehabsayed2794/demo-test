package com.estemshan.engine

/**
 * T-20 (engine slice) — the pure Auto-Match session policy: one waiting
 * table's fill timer, join band, bot-fill vote and humans-only strength
 * source, held as one immutable value. This is the policy slice ONLY — no
 * Firestore, no callable, no queue, no services, no UI, no clock read
 * (every time value is a parameter, matching InactivityClock's pure
 * arithmetic), so tests are deterministic.
 *
 * The decisions this file encodes (and no others):
 *
 * - CANONICAL_RULES rule 9 + T-20 (Q7c/Q26): Auto-Match joins a
 *   player-created waiting table; **wait two minutes from the first player
 *   entering**; if ready humans are still missing, a **30-second vote**
 *   fills the table with the creator's bots when **more than half of the
 *   waiting humans approve** — the exact arithmetic is
 *   `approvals.size * 2 > humans.size` — and **non-votes count as
 *   rejection**. Four humans before the timer fill the table directly:
 *   no vote, no bots.
 * - A8.6 / Q41 (closes O-07): the join band is **absolute** — the seated
 *   humans plus the candidate may span at most one tier of
 *   [poolTierOrder], and the answer never depends on the session's age.
 *   It does not widen after 180 s; there is no widening code path at all.
 * - **O-15 is deliberately untouched.** [MatchmakingSession.bandAccepts]
 *   is a necessary DISTANCE condition, not a pairing decision: it never
 *   says which direction pairs. The resolver composes
 *   `isPoolEligible(searcher, candidate) && session.bandAccepts(candidate.tier)`;
 *   MatchmakingPool.isPoolEligible keeps its downward direction and is not
 *   edited here. No code or test in this file asserts Gold→Platinum or
 *   Platinum→Gold pairing.
 * - RP strength (T-20 "RP with bots" row): [MatchmakingSession.strengthTiers]
 *   exposes the seated HUMANS' tiers only, in join order. The session never
 *   stores a bot tier, so a bot can never enter the strength source. The
 *   RP math itself is out of scope.
 *
 * Out of scope (E4.1/E4.2 runtime work, not this file): the shared queue
 * and table join, "no table → message + Create Table", which bot fills a
 * seat (`botProfileFor` lives in :functions), resolveRanked and the RP
 * computation, and the in-match 60 s all-gone cancel (S7's clock — this
 * file's ABANDONED is a pre-match lobby with no humans left).
 *
 * Engineering judgment inside that closed envelope, pinned by
 * MatchmakingSessionTest:
 *
 * - The vote opens at the fill deadline ([openedAtMillis] +
 *   [MATCHMAKING_FILL_MILLIS]), never at the tick that noticed it, so a
 *   late [MatchmakingSession.step] catches up across both deadlines in one
 *   call.
 * - The electorate is the humans CURRENTLY waiting, never a frozen
 *   snapshot: a human who leaves during the vote is removed from the
 *   denominator and from both vote sets, and the remaining electorate
 *   re-evaluates immediately, so a leave can settle an already-decided
 *   vote early. The deadline outcome is identical either way — the shrink
 *   only moves the moment the exact same arithmetic fires.
 * - A uid votes once: a second approve/reject from the same uid is a
 *   no-op, so a voter cannot flip. An explicit rejection counts exactly
 *   like a non-vote on the approval side; it only completes the
 *   electorate, which permits early rejection when no majority exists.
 *   A non-voter keeps the vote open until the deadline.
 * - Terminal phases are immutable: FILLED and REJECTED ignore join,
 *   approve, reject, leave and step — no resurrection. In-match departure
 *   is S7's domain, not a lobby leave.
 * - [MatchmakingSession.join] takes `nowMillis` for signature uniformity
 *   with the other actions and deliberately never reads it: nothing in the
 *   join rule is time-dependent, and the band must be identical at every
 *   session age (A8.6).
 * - A blank uid cannot join (no-op); [MatchmakingSession.open] is the only
 *   identity check that fails loudly, because it creates the session.
 * - A foreign tier fails loudly via [require] on open, join and
 *   [MatchmakingSession.bandAccepts], matching MatchmakingPool.
 */

/** The Auto-Match table size: a match is always four seats (rule 9; T-20). */
const val MATCHMAKING_SEATS: Int = 4

/**
 * The bot-fill window: two minutes from the FIRST player entering the
 * table, not from the last join (Q8; rule 9; T-20 "Bot fill timer").
 */
const val MATCHMAKING_FILL_MILLIS: Long = 120_000L

/** The bot-fill vote stays open 30 s; non-votes reject at its close (rule 9). */
const val MATCHMAKING_VOTE_MILLIS: Long = 30_000L

/** Where a waiting table stands in the T-20 lifecycle. */
enum class MatchmakingPhase { FILLING, VOTING, FILLED, REJECTED }

/**
 * Why a table that never started play died. ABANDONED is a pre-match
 * lobby with no humans left; the in-match 60 s all-gone cancel is S7's
 * clock and lives elsewhere.
 */
enum class MatchmakingRejectReason { VOTE_FAILED, ABANDONED }

/** One seated human and the tier they joined with — the only strength input a session keeps. */
data class WaitingHuman(
  val uid: String,
  val tier: String,
)

/**
 * One Auto-Match waiting table as a pure value. Every transition function
 * returns a new session and never mutates the receiver; a no-op returns
 * the receiver unchanged.
 */
data class MatchmakingSession(
  val phase: MatchmakingPhase,
  val openedAtMillis: Long,
  val voteOpenedAtMillis: Long?,
  val humans: List<WaitingHuman>,
  val approvals: Set<String>,
  val rejections: Set<String>,
  val filledWithBots: Boolean,
  val rejectReason: MatchmakingRejectReason?,
) {

  /** The fill timer's close: two minutes after the first player entered. */
  val fillDeadlineMillis: Long
    get() = openedAtMillis + MATCHMAKING_FILL_MILLIS

  /** When the bot-fill vote closes, or null while no vote is open. */
  val voteDeadlineMillis: Long?
    get() = voteOpenedAtMillis?.plus(MATCHMAKING_VOTE_MILLIS)

  /**
   * How many bot seats a filled vote creates — the session decides HOW
   * MANY, never WHICH bot (`botProfileFor` is :functions' decision).
   */
  val botSeatsToFill: Int
    get() = if (phase == MatchmakingPhase.FILLED && filledWithBots) {
      MATCHMAKING_SEATS - humans.size
    } else {
      0
    }

  /**
   * T-20 "RP with bots": opponent strength from the humans only, in join
   * order. A bot seat never appears here because the session never stores
   * a bot tier; the RP math that consumes the list is out of scope.
   */
  fun strengthTiers(): List<String> = humans.map { it.tier }

  /**
   * A8.6 / Q41: the ABSOLUTE join band. The seated humans plus
   * [candidateTier] may span at most one tier of [poolTierOrder]; an
   * empty session accepts any valid tier. No time parameter and no
   * widening path — the answer is identical at 0 ms, 119 999 ms, 120 000
   * ms, 150 000 ms, 180 000 ms, 180 001 ms and every later tick.
   *
   * This is a necessary distance condition, NOT a pairing decision: the
   * resolver composes `isPoolEligible(searcher, candidate) &&
   * session.bandAccepts(candidate.tier)`, and which direction pairs stays
   * O-15. Divisions never enter (tiers only, like the whole band family).
   * A foreign tier fails loudly.
   */
  fun bandAccepts(candidateTier: String): Boolean {
    val order = poolTierOrder
    val candidateIndex = order.indexOf(candidateTier)
    require(candidateIndex >= 0) {
      "The matchmaking band needs a rung of the single ladder, was tier '$candidateTier'"
    }
    if (humans.isEmpty()) return true
    var lowest = candidateIndex
    var highest = candidateIndex
    for (human in humans) {
      val index = order.indexOf(human.tier)
      require(index >= 0) {
        "The matchmaking band needs a rung of the single ladder, " +
          "was seated tier '${human.tier}'"
      }
      if (index < lowest) lowest = index
      if (index > highest) highest = index
    }
    return highest - lowest <= 1
  }

  /**
   * Seats [uid] while FILLING, the table has fewer than four humans, the
   * uid is not already seated (a duplicate uid never changes tier), and
   * [bandAccepts] holds — the band as a safety net, so the table cannot
   * become illegal through a direct call. Otherwise a no-op: the receiver
   * is returned unchanged. When the join makes four humans the phase
   * becomes FILLED immediately, with filledWithBots = false — no vote.
   * `nowMillis` is unread by design (see the file KDoc).
   */
  fun join(uid: String, tier: String, nowMillis: Long): MatchmakingSession {
    if (phase != MatchmakingPhase.FILLING) return this
    if (humans.size >= MATCHMAKING_SEATS) return this
    if (uid.isBlank()) return this
    if (humans.any { it.uid == uid }) return this
    if (!bandAccepts(tier)) return this
    val joined = copy(humans = humans + WaitingHuman(uid, tier))
    return if (joined.humans.size == MATCHMAKING_SEATS) {
      joined.copy(phase = MatchmakingPhase.FILLED, filledWithBots = false)
    } else {
      joined
    }
  }

  /**
   * Removes [uid] during FILLING or VOTING, scrubbing it from approvals
   * and rejections so the electorate stays the humans currently waiting.
   * The last human leaving rejects the session as ABANDONED (a pre-match
   * lobby with nobody in it). In a terminal phase a no-op — the match
   * already started, and in-match departure is S7's domain.
   */
  fun leave(uid: String): MatchmakingSession {
    if (phase != MatchmakingPhase.FILLING && phase != MatchmakingPhase.VOTING) return this
    if (humans.none { it.uid == uid }) return this
    val remaining = humans.filter { it.uid != uid }
    if (remaining.isEmpty()) {
      return copy(
        humans = remaining,
        approvals = emptySet(),
        rejections = emptySet(),
        phase = MatchmakingPhase.REJECTED,
        rejectReason = MatchmakingRejectReason.ABANDONED,
      )
    }
    return copy(
      humans = remaining,
      approvals = approvals - uid,
      rejections = rejections - uid,
    ).resolveVoteIfDecided()
  }

  /**
   * Records [uid]'s approval during VOTING for a current human. A uid
   * votes once: a second approve or reject from the same uid is a no-op,
   * so a voter cannot flip. A non-human's vote and a call outside VOTING
   * are no-ops. When the approval reaches a strict majority
   * (`approvals.size * 2 > humans.size`) the table fills immediately
   * with bots — before the deadline, without waiting for remaining
   * voters (2 of 3 lands the moment the second approval arrives).
   */
  fun approve(uid: String): MatchmakingSession {
    if (phase != MatchmakingPhase.VOTING) return this
    if (humans.none { it.uid == uid }) return this
    if (uid in approvals || uid in rejections) return this
    return copy(approvals = approvals + uid).resolveVoteIfDecided()
  }

  /**
   * Records [uid]'s rejection under the same once-only rule as
   * [approve]. The rejection adds nothing to the approval side — it
   * counts exactly like a non-vote there — but it does complete the
   * electorate, so a rejection that leaves every human voted and no
   * majority rejects the session early.
   */
  fun reject(uid: String): MatchmakingSession {
    if (phase != MatchmakingPhase.VOTING) return this
    if (humans.none { it.uid == uid }) return this
    if (uid in approvals || uid in rejections) return this
    return copy(rejections = rejections + uid).resolveVoteIfDecided()
  }

  /**
   * Applies every transition whose deadline has passed, in order, in one
   * call — a late tick catches up, it never stalls:
   *
   * 1. FILLING at or after [fillDeadlineMillis]: fewer than four humans
   *    opens the vote with voteOpenedAtMillis anchored to the TIMER
   *    (openedAtMillis + [MATCHMAKING_FILL_MILLIS]), not to this tick;
   *    four humans (unreachable through the pure API, handled
   *    defensively) fill directly with no bots.
   * 2. VOTING: early resolution when the outcome is already decided,
   *    otherwise at or after [voteDeadlineMillis] — FILLED on a strict
   *    majority of approvals, otherwise REJECTED(VOTE_FAILED), the
   *    non-votes having counted as rejections.
   *
   * A nowMillis before [openedAtMillis] changes nothing (the clock never
   * runs backwards). Terminal phases are immutable.
   */
  fun step(nowMillis: Long): MatchmakingSession {
    if (phase != MatchmakingPhase.FILLING && phase != MatchmakingPhase.VOTING) return this
    if (nowMillis < openedAtMillis) return this
    var next = this
    if (next.phase == MatchmakingPhase.FILLING && nowMillis >= next.fillDeadlineMillis) {
      next = if (next.humans.size >= MATCHMAKING_SEATS) {
        next.copy(phase = MatchmakingPhase.FILLED, filledWithBots = false)
      } else {
        next.copy(
          phase = MatchmakingPhase.VOTING,
          voteOpenedAtMillis = next.fillDeadlineMillis,
        )
      }
    }
    next = next.resolveVoteIfDecided()
    if (next.phase == MatchmakingPhase.VOTING) {
      val deadline = next.voteDeadlineMillis
      if (deadline != null && nowMillis >= deadline) {
        next = if (next.approvals.size * 2 > next.humans.size) {
          next.copy(phase = MatchmakingPhase.FILLED, filledWithBots = true)
        } else {
          next.copy(
            phase = MatchmakingPhase.REJECTED,
            rejectReason = MatchmakingRejectReason.VOTE_FAILED,
          )
        }
      }
    }
    return next
  }

  /**
   * The early-resolution rule, shared by approve/reject/leave/step:
   * resolve the open vote the moment its outcome is already decided —
   * a strict majority fills with bots regardless of outstanding voters;
   * an electorate that has all voted with no majority rejects. A
   * non-voter keeps the vote open until the deadline.
   */
  private fun resolveVoteIfDecided(): MatchmakingSession {
    if (phase != MatchmakingPhase.VOTING) return this
    val majorityReached = approvals.size * 2 > humans.size
    val everyoneVoted = humans.all { it.uid in approvals || it.uid in rejections }
    return when {
      majorityReached -> copy(phase = MatchmakingPhase.FILLED, filledWithBots = true)
      everyoneVoted -> copy(
        phase = MatchmakingPhase.REJECTED,
        rejectReason = MatchmakingRejectReason.VOTE_FAILED,
      )
      else -> this
    }
  }

  companion object {
    /**
     * Opens a table with exactly one human: FILLING, opened at
     * [nowMillis]. A blank uid or a tier not in [poolTierOrder] fails
     * loudly — the only identity check that must, because it creates the
     * session every later rule reads.
     */
    fun open(firstUid: String, tier: String, nowMillis: Long): MatchmakingSession {
      require(firstUid.isNotBlank()) { "Auto-Match needs a first player, was a blank uid" }
      require(poolTierOrder.contains(tier)) {
        "Auto-Match needs a rung of the single ladder, was tier '$tier'"
      }
      return MatchmakingSession(
        phase = MatchmakingPhase.FILLING,
        openedAtMillis = nowMillis,
        voteOpenedAtMillis = null,
        humans = listOf(WaitingHuman(firstUid, tier)),
        approvals = emptySet(),
        rejections = emptySet(),
        filledWithBots = false,
        rejectReason = null,
      )
    }
  }
}
