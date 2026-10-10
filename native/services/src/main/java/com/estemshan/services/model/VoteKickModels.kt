package com.estemshan.services.model

import com.estemshan.engine.VoteKickChoice

/**
 * S60 — manual Vote Kick (RD18, Ranked-only) document model.
 *
 * One vote at a time per match at `matches/{matchId}/voteKick/current`
 * (the rematchVote/current single-doc pattern): the target is frozen at
 * open and never re-derived, the target's slot is structurally absent
 * from [votes] (never null-valued), bot-held seats hold no slot (D7),
 * and terminal statuses are immutable. The resolved PASSED record IS the
 * match's record of the Koz removal (D10) — settlement/statistics read
 * it; nothing here fabricates placements.
 *
 * OPEN-2 (owner-resolved): gameplay continues while a vote is open. This
 * model carries no pause, no deadline clock, and no match-mutation
 * beyond the terminal writes VoteKickService performs.
 */
data class VoteKickDoc(
  val matchId: String,
  val targetSeat: String,
  val initiatorSeat: String,
  val reason: String,
  val votes: Map<String, String?>,
  val status: String,
  val roundOpened: Int,
  val version: Int,
) {

  /** Seats that have cast (non-null slots only). */
  fun votedSeats(): Set<String> = votes.filterValues { it != null }.keys

  fun yesCount(): Int = votes.count { it.value == VOTE_YES }

  fun noCount(): Int = votes.count { it.value == VOTE_NO }

  fun isTerminal(): Boolean = status != STATUS_OPEN

  fun toFields(): Map<String, Any?> = mapOf(
    "matchId" to matchId,
    "targetSeat" to targetSeat,
    "initiatorSeat" to initiatorSeat,
    "reason" to reason,
    "votes" to votes.toMap(),
    "status" to status,
    "roundOpened" to roundOpened,
    "version" to version,
  )

  companion object {
    const val STATUS_OPEN = "OPEN"
    const val STATUS_PASSED = "PASSED"
    const val STATUS_FAILED_NO = "FAILED_NO"
    const val STATUS_FAILED_TIMEOUT = "FAILED_TIMEOUT"

    const val VOTE_YES = "YES"
    const val VOTE_NO = "NO"

    /** The single vote slot per match (one open vote at a time). */
    const val CURRENT_DOC_ID = "current"

    fun choiceOf(value: String?): VoteKickChoice? = when (value) {
      VOTE_YES -> VoteKickChoice.YES
      VOTE_NO -> VoteKickChoice.NO
      else -> null
    }

    fun choiceName(choice: VoteKickChoice): String = when (choice) {
      VoteKickChoice.YES -> VOTE_YES
      VoteKickChoice.NO -> VOTE_NO
    }

    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Map<String, Any?>): VoteKickDoc? {
      val matchId = fields["matchId"] as? String ?: return null
      val targetSeat = fields["targetSeat"] as? String ?: return null
      val initiatorSeat = fields["initiatorSeat"] as? String ?: return null
      val reason = fields["reason"] as? String ?: return null
      val votes = (fields["votes"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        key to (v as? String)
      }?.toMap() ?: return null
      val status = fields["status"] as? String ?: STATUS_OPEN
      if (status != STATUS_OPEN && status != STATUS_PASSED &&
        status != STATUS_FAILED_NO && status != STATUS_FAILED_TIMEOUT
      ) {
        return null
      }
      val roundOpened = (fields["roundOpened"] as? Long)?.toInt() ?: return null
      val version = (fields["version"] as? Long)?.toInt() ?: 0
      return VoteKickDoc(
        matchId = matchId,
        targetSeat = targetSeat,
        initiatorSeat = initiatorSeat,
        reason = reason,
        votes = votes,
        status = status,
        roundOpened = roundOpened,
        version = version,
      )
    }
  }
}

/**
 * D8: the 5-round same-target cooldown, in authoritative match state.
 * Named verbatim from the roadmap (`failedVoteKickCooldown:
 * { targetSeat, blockedUntilRound }`). Single slot by spec: a newer
 * failure overwrites it — flagged as a spec corner in S60's report,
 * never silently "fixed" into a map.
 */
data class VoteKickCooldownData(
  val targetSeat: String,
  val blockedUntilRound: Int,
) {

  fun toFields(): Map<String, Any?> = mapOf(
    "targetSeat" to targetSeat,
    "blockedUntilRound" to blockedUntilRound,
  )

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Any?): VoteKickCooldownData? {
      val map = fields as? Map<String, Any?> ?: return null
      val targetSeat = map["targetSeat"] as? String ?: return null
      val blockedUntilRound = (map["blockedUntilRound"] as? Long)?.toInt() ?: return null
      return VoteKickCooldownData(targetSeat, blockedUntilRound)
    }
  }
}

/** openVoteKick result — created converges, refusals throw. */
data class VoteKickOpenResult(
  val created: Boolean,
  val matchId: String,
  val vote: VoteKickDoc?,
)

/**
 * castVoteKick result. [resolution] is the vote's status after this
 * write (possibly still OPEN); the tally triple is what S40 binds:
 * live tally, eligible count, and the majority threshold.
 */
data class VoteKickCastResult(
  val matchId: String,
  val vote: VoteKickDoc,
  val resolution: String,
  val yesCount: Int,
  val noCount: Int,
  val eligibleCount: Int,
  val threshold: Int,
  /** Convergent-read reason (ALREADY_VOTED / VOTE_CLOSED), null on a live cast. */
  val reason: String? = null,
  /** True when this write applied the terminal removal (PASSED). */
  val removed: Boolean = false,
  /** True when this write recorded a failure cooldown. */
  val cooledDown: Boolean = false,
)
