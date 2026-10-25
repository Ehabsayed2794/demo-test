package com.estemshan.services.model

/**
 * Structured failure — the Kotlin form of match-service.js's
 * bidError(reason, message): a machine-checkable [reason] code alongside
 * a human-readable [message]. Callers branch on [reason], never parse
 * [message]. Thrown for genuine failures; idempotent no-ops are RETURNED
 * as result objects with their own reason codes instead.
 */
class ServiceException(
  val reason: String,
  override val message: String,
) : Exception(message) {

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ServiceException) return false
    return reason == other.reason && message == other.message
  }

  override fun hashCode(): Int = 31 * reason.hashCode() + message.hashCode()
}

/** Machine-checkable reason codes (match-service.js + room-service.js). */
object Reasons {
  // Argument / environment
  const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
  const val UNAVAILABLE = "UNAVAILABLE"
  const val UNAUTHENTICATED = "UNAUTHENTICATED"

  // Authority
  const val MATCH_NOT_FOUND = "MATCH_NOT_FOUND"
  const val ROOM_NOT_FOUND = "ROOM_NOT_FOUND"
  const val UNKNOWN_SEAT = "UNKNOWN_SEAT"
  const val PERMISSION_DENIED = "PERMISSION_DENIED"
  const val NOT_A_MEMBER = "NOT_A_MEMBER"
  const val ROOM_FULL = "ROOM_FULL"
  const val ROOM_CLOSED = "ROOM_CLOSED"

  // Bidding
  const val INVALID_BID_VALUE = "INVALID_BID_VALUE"
  const val BIDDING_CLOSED = "BIDDING_CLOSED"
  const val ALREADY_BID = "ALREADY_BID"
  const val INVALID_BIDDING_ACTION_VALUE = "INVALID_BIDDING_ACTION_VALUE"
  const val ILLEGAL_BIDDING_ACTION = "ILLEGAL_BIDDING_ACTION"

  // Cards
  const val INVALID_CARD_VALUE = "INVALID_CARD_VALUE"
  const val ILLEGAL_CARD = "ILLEGAL_CARD"
  const val NOT_YOUR_TURN = "NOT_YOUR_TURN"
  const val UNKNOWN_NEXT_SEAT = "UNKNOWN_NEXT_SEAT"
  const val UNKNOWN_OPENING_SEAT = "UNKNOWN_OPENING_SEAT"
  const val TRICK_WINNER_UNAVAILABLE = "TRICK_WINNER_UNAVAILABLE"

  // Cross-layer availability
  const val MATCH_ADAPTER_UNAVAILABLE = "MATCH_ADAPTER_UNAVAILABLE"
  const val ENGINE_UNAVAILABLE = "ENGINE_UNAVAILABLE"

  // Optimistic concurrency
  const val STALE_GAME_STATE = "STALE_GAME_STATE"

  // Round / match lifecycle (thrown)
  const val ROUND_NOT_COMPLETE = "ROUND_NOT_COMPLETE"
  const val INVALID_RESULT = "INVALID_RESULT"
  /** O-19: a General-Pass advance was asked for a window that is not the
   *  provable N-declines + N-passes shape (e.g. a seat declared a Dash
   *  call). Refused rather than advanced on a guess. */
  const val UNSUPPORTED_GENERAL_PASS_SHAPE = "UNSUPPORTED_GENERAL_PASS_SHAPE"
  /** F1 (owner ruling 2026-10-24): the closing round's log claims MORE than
   *  the strict two-Dash maximum (MAX_DASH_CALLS). The service's write
   *  boundary makes such a window unpersistable, so a document carrying it
   *  is refused BY NAME — never silently clamped to two. */
  const val TOO_MANY_DASH_CALLS = "TOO_MANY_DASH_CALLS"

  // Idempotent no-op reasons (RETURNED, never thrown)
  const val ALREADY_ADVANCED = "ALREADY_ADVANCED"
  const val MATCH_ALREADY_COMPLETE = "MATCH_ALREADY_COMPLETE"
  const val ALREADY_COMPLETE = "ALREADY_COMPLETE"
  const val MATCH_NOT_OVER = "MATCH_NOT_OVER"
  const val ALREADY_EXTENDED = "ALREADY_EXTENDED"
  const val ALREADY_DEALT = "ALREADY_DEALT"
  const val ALREADY_VOTED = "ALREADY_VOTED"
  const val VOTE_CLOSED = "VOTE_CLOSED"
  const val VOTE_LOCKED = "VOTE_LOCKED"
  const val VOTE_EXPIRED = "VOTE_EXPIRED"
  const val NOT_ALL_YES = "NOT_ALL_YES"
  const val ALREADY_RESOLVED = "ALREADY_RESOLVED"
  const val NOT_YET_EXPIRED = "NOT_YET_EXPIRED"
  const val DEADLINE_UNKNOWN = "DEADLINE_UNKNOWN"
  const val VOTE_NOT_FOUND = "VOTE_NOT_FOUND"
  const val MATCH_NOT_COMPLETE = "MATCH_NOT_COMPLETE"
  const val CREATED = "CREATED"
  const val RECORDED = "RECORDED"

  // S60 manual Vote Kick (RD18) — gate denials, thrown. Each maps one
  // engine VoteKickUnavailability for S40's in-place explanations.
  // VOTE_NOT_RANKED is the constant's historical name: since the
  // 2026-10-17 owner clarification the vote is refused in ROOM only
  // (RANKED + UNRANKED both serve it), and the string stays stable for
  // clients.
  const val VOTE_NOT_RANKED = "VOTE_NOT_RANKED"
  const val VOTE_ROUNDS_SHORT = "VOTE_ROUNDS_SHORT"
  const val VOTE_NO_UNIQUE_KOZ = "VOTE_NO_UNIQUE_KOZ"
  const val VOTE_TARGET_NOT_KOZ = "VOTE_TARGET_NOT_KOZ"
  const val VOTE_TARGET_REMOVED = "VOTE_TARGET_REMOVED"
  const val VOTE_INITIATOR_IS_TARGET = "VOTE_INITIATOR_IS_TARGET"
  const val VOTE_INITIATOR_INELIGIBLE = "VOTE_INITIATOR_INELIGIBLE"
  const val VOTE_COOLDOWN_LIVE = "VOTE_COOLDOWN_LIVE"
  const val VOTE_ALREADY_OPEN = "VOTE_ALREADY_OPEN"

  // S60: a permanently-removed uid attempting any match write. Distinct
  // from PERMISSION_DENIED so S40 (and clients) can name the removal.
  const val REMOVED_FROM_MATCH = "REMOVED_FROM_MATCH"

  // S72: a left uid attempting any match write. Distinct from
  // REMOVED_FROM_MATCH so S40 can name the departure.
  const val LEFT_MATCH = "LEFT_MATCH"
  // S72: idempotent no-op when the seat has already been left.
  const val ALREADY_LEFT = "ALREADY_LEFT"

  // S70 (RD29/RD30) — the Rank-down join gate's vocabulary, kept deliberately
  // apart from the thrown codes above. RANK_INCOMPATIBLE and
  // PROFILE_UNAVAILABLE are the server's two ELIGIBILITY denials: they arrive
  // as a returned `joined: false` payload, never an HttpsError, because a
  // rank-too-far friend is an ordinary answer to an ordinary question and not
  // a failed call. RANK_DOWN_UNAVAILABLE is the CLIENT's own catch-all — the
  // reason a denial is rewritten to when the payload carries a reason this
  // build has never seen (version skew), so an unknown token from a newer
  // server can never reach a screen as if the client understood it.
  const val RANK_INCOMPATIBLE = "RANK_INCOMPATIBLE"
  const val PROFILE_UNAVAILABLE = "PROFILE_UNAVAILABLE"
  const val RANK_DOWN_UNAVAILABLE = "RANK_DOWN_UNAVAILABLE"
}

/** startMatch(roomId): the room↔match atomic write. */
data class StartMatchResult(
  val matchId: String,
  val created: Boolean,
  val players: List<String>,
)

/** RoomService.setReady's attached match-start outcome — never rejects. */
data class MatchStartResult(
  val allReady: Boolean,
  val started: Boolean,
  val matchId: String?,
  val error: ServiceException?,
) {
  companion object {
    val NOT_ALL_READY = MatchStartResult(
      allReady = false, started = false, matchId = null, error = null,
    )
  }
}

data class SubmitBidResult(
  val matchId: String,
  val seatId: String,
  val bid: Int,
  val version: Int,
  val biddingOpen: Boolean,
  val allSubmitted: Boolean,
)

data class SubmitBiddingActionResult(
  val matchId: String,
  val seatId: String,
  val actionType: String,
  val version: Int,
  val logLength: Int,
)

data class SubmitCardResult(
  val matchId: String,
  val seatId: String,
  val version: Int,
  val cardCount: Int,
  val nextTurnSeat: String?,
  val cardPhase: String?,
)

/** advanceToNextRound / advanceAfterGeneralPass — [advanced] false means an
 *  idempotent no-op with [reason] (ALREADY_ADVANCED / MATCH_ALREADY_COMPLETE).
 *  O-19: [completed] marks a General-Pass round that consumed the planned
 *  final round and ended the match; [currentMultiplier] is the value the
 *  document now carries for [currentRound] (null on idempotent no-ops). */
data class AdvanceResult(
  val advanced: Boolean,
  val reason: String?,
  val matchId: String,
  val previousRound: Int? = null,
  val currentRound: Int,
  val archivedRound: Int? = null,
  val version: Int? = null,
  val completed: Boolean = false,
  val currentMultiplier: Int? = null,
) {
  companion object {
    fun alreadyAdvanced(matchId: String, currentRound: Int) =
      AdvanceResult(false, Reasons.ALREADY_ADVANCED, matchId, currentRound = currentRound)

    fun alreadyComplete(matchId: String, currentRound: Int) =
      AdvanceResult(false, Reasons.MATCH_ALREADY_COMPLETE, matchId, currentRound = currentRound)
  }
}

data class ExtendResult(
  val extended: Boolean,
  val reason: String?,
  val matchId: String,
  val completedRound: Int? = null,
  val maxRounds: Int,
  val version: Int? = null,
) {
  companion object {
    fun alreadyExtended(matchId: String, maxRounds: Int) =
      ExtendResult(false, Reasons.ALREADY_EXTENDED, matchId, maxRounds = maxRounds)

    fun alreadyComplete(matchId: String, maxRounds: Int) =
      ExtendResult(false, Reasons.MATCH_ALREADY_COMPLETE, matchId, maxRounds = maxRounds)
  }
}

data class EndMatchResult(
  val complete: Boolean,
  val reason: String?,
  val matchId: String,
  val completedRound: Int? = null,
  val archivedRound: Int? = null,
  val winnerIds: List<String>,
  val finalScores: Map<String, Int>,
  val version: Int? = null,
  val currentRound: Int? = null,
) {
  companion object {
    fun alreadyComplete(matchId: String, winnerIds: List<String>, finalScores: Map<String, Int>) =
      EndMatchResult(false, Reasons.ALREADY_COMPLETE, matchId,
        winnerIds = winnerIds, finalScores = finalScores)

    fun alreadyAdvanced(matchId: String, currentRound: Int) =
      EndMatchResult(false, Reasons.ALREADY_ADVANCED, matchId,
        winnerIds = emptyList(), finalScores = emptyMap(), currentRound = currentRound)

    fun notOver(matchId: String, currentRound: Int, maxRounds: Int) =
      EndMatchResult(false, Reasons.MATCH_NOT_OVER, matchId,
        winnerIds = emptyList(), finalScores = emptyMap(), currentRound = currentRound)
  }
}

data class DealResult(
  val dealt: Boolean,
  val reason: String?,
  val matchId: String,
  val dealtRound: Int,
  val seats: List<String> = emptyList(),
) {
  companion object {
    fun alreadyDealt(matchId: String, dealtRound: Int) =
      DealResult(false, Reasons.ALREADY_DEALT, matchId, dealtRound)
  }
}

data class VoteCreateResult(
  val created: Boolean,
  val matchId: String,
  /** The vote document as it now exists (created here, or the pre-existing
   *  one for the idempotent path). */
  val vote: VoteDoc? = null,
)

data class VoteSubmitResult(
  val accepted: Boolean,
  val reason: String,
  val matchId: String,
  val seatId: String,
  val choice: String? = null,
  val status: String? = null,
  val version: Int? = null,
  val existing: String? = null,
)

data class VoteTimeoutResult(
  val resolved: Boolean,
  val reason: String?,
  val matchId: String,
  val status: String? = null,
  val version: Int? = null,
)

data class RematchCreateResult(
  val created: Boolean,
  val matchId: String,
  val newMatchId: String?,
  val reason: String? = null,
  val status: String? = null,
)

/** S72 — explicit leave outcome: the departed seat, its Koz charge, and the
 *  next match version after the leave write. */
data class LeaveResult(
  val matchId: String,
  val seatId: String,
  val uid: String,
  val kozCharge: com.estemshan.engine.KozCharge,
  val version: Int,
  /** ALREADY_LEFT on the idempotent replay no-op; null on a successful first leave. */
  val reason: String? = null,
)
