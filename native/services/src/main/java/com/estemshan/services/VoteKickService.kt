package com.estemshan.services

import com.estemshan.engine.VoteKickAvailability
import com.estemshan.engine.VoteKickChoice
import com.estemshan.engine.VoteKickCooldown
import com.estemshan.engine.VoteKickStatus
import com.estemshan.engine.VoteKickUnavailability
import com.estemshan.engine.VoteKickVote
import com.estemshan.engine.castVoteKick as engineCastVote
import com.estemshan.engine.cooldownAfterFailedVote
import com.estemshan.engine.eligibleVoteKickVoters
import com.estemshan.engine.openVoteKick as engineOpenVote
import com.estemshan.engine.removalForVoteKick
import com.estemshan.engine.resolveVoteKick
import com.estemshan.engine.voteKickAvailability
import com.estemshan.engine.voteKickThreshold
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.Reasons.ALREADY_VOTED
import com.estemshan.services.model.Reasons.INVALID_ARGUMENT
import com.estemshan.services.model.Reasons.MATCH_ALREADY_COMPLETE
import com.estemshan.services.model.Reasons.MATCH_NOT_FOUND
import com.estemshan.services.model.Reasons.PERMISSION_DENIED
import com.estemshan.services.model.Reasons.REMOVED_FROM_MATCH
import com.estemshan.services.model.Reasons.STALE_GAME_STATE
import com.estemshan.services.model.Reasons.UNAUTHENTICATED
import com.estemshan.services.model.Reasons.UNKNOWN_SEAT
import com.estemshan.services.model.Reasons.VOTE_ALREADY_OPEN
import com.estemshan.services.model.Reasons.VOTE_CLOSED
import com.estemshan.services.model.Reasons.VOTE_COOLDOWN_LIVE
import com.estemshan.services.model.Reasons.VOTE_INITIATOR_INELIGIBLE
import com.estemshan.services.model.Reasons.VOTE_INITIATOR_IS_TARGET
import com.estemshan.services.model.Reasons.VOTE_LOCKED
import com.estemshan.services.model.Reasons.VOTE_NOT_FOUND
import com.estemshan.services.model.Reasons.VOTE_NOT_RANKED
import com.estemshan.services.model.Reasons.VOTE_NO_UNIQUE_KOZ
import com.estemshan.services.model.Reasons.VOTE_ROUNDS_SHORT
import com.estemshan.services.model.Reasons.VOTE_TARGET_NOT_KOZ
import com.estemshan.services.model.Reasons.VOTE_TARGET_REMOVED
import com.estemshan.services.model.ServiceException
import com.estemshan.services.model.VoteKickCastResult
import com.estemshan.services.model.VoteKickCooldownData
import com.estemshan.services.model.VoteKickDoc
import com.estemshan.services.model.VoteKickOpenResult
import com.estemshan.services.session.AuthPort
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction

/**
 * S60 — manual Vote Kick (RD18, Ranked-only) writer. The ONE authority for
 * `matches/{matchId}/voteKick/current` plus the two parent fields it owns
 * (`botSeats`/`removedUids` on removal, `failedVoteKickCooldown` on
 * failure). Standalone class — MatchService is untouched except the
 * no-rejoin gate — following its conventions verbatim: acting uid from
 * [auth] never a parameter, generic shape validation before any Firestore
 * access, the REAL engine decides before any write, idempotent no-ops
 * RETURNED with a reason.
 *
 * Trust boundary (RD14, same as endMatch's claimed finalScores): the
 * initiator supplies claimed current standings; the shape is validated
 * (exactly the match seats, ints) but the numbers are not re-derived —
 * the converged history cannot prove them mid-match. Settlement corrects
 * final scores retrospectively; nothing here fabricates a Koz (a tied
 * last place refuses, RD17).
 *
 * OPEN-2 (owner-resolved): gameplay continues while a vote is open. This
 * service pauses nothing, freezes nothing except the vote's own target
 * seat, and never touches rounds, turns, timers, or the 15-timeout
 * counter (RD20 — S61's mechanism, no shared state).
 *
 * No Android imports; the only SDK surface used is Firestore.
 */
class VoteKickService(
  private val db: FirebaseFirestore,
  private val auth: AuthPort,
) {

  private val matches get() = db.collection("matches")

  private fun voteRef(matchId: String) =
    matches.document(matchId).collection("voteKick").document(VoteKickDoc.CURRENT_DOC_ID)

  // ── 1. openVoteKick ───────────────────────────────────────────────

  /**
   * Opens a manual Vote Kick against [targetSeat] with the initiator's
   * claimed current [standings]. One open vote per match: a same-target
   * OPEN vote converges (returned, not rewritten); a different-target
   * OPEN vote refuses (VOTE_ALREADY_OPEN). A terminal vote may be
   * replaced by a fresh one (version restarts at 1).
   */
  suspend fun openVoteKick(
    matchId: String,
    targetSeat: String,
    reason: String,
    claimedStandings: Map<String, Int>,
  ): VoteKickOpenResult {
    require(matchId.isNotEmpty()) { "openVoteKick: matchId is required." }
    require(targetSeat.isNotEmpty()) { "openVoteKick: targetSeat is required." }
    if (reason.isBlank()) {
      throw ServiceException(INVALID_ARGUMENT, "openVoteKick: reason is required.")
    }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "openVoteKick: no signed-in user.")
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val match = readMatch(tx, matchRef)
        ?: return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_FOUND,
          "openVoteKick: match '$matchId' was not found."))
      if (!match.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "openVoteKick: you are not a player in this match."))
      }
      if (match.isRemoved(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(REMOVED_FROM_MATCH,
          "openVoteKick: you were removed from this match and cannot vote."))
      }
      val existing = readVote(tx, matchId)
      if (match.isComplete) {
        if (existing != null && existing.status == VoteKickDoc.STATUS_OPEN) {
          sweepStaleVote(tx, matchId, match, existing)
        }
        return@runTx TxOutcome.Err(ServiceException(MATCH_ALREADY_COMPLETE,
          "openVoteKick: match '$matchId' has already completed."))
      }
      if (existing != null && existing.status == VoteKickDoc.STATUS_OPEN) {
        if (existing.targetSeat == targetSeat) {
          return@runTx TxOutcome.Ok(
            VoteKickOpenResult(created = false, matchId = matchId, vote = existing),
          )
        }
        return@runTx TxOutcome.Err(ServiceException(VOTE_ALREADY_OPEN,
          "openVoteKick: a vote against '${existing.targetSeat}' is already open."))
      }
      if (targetSeat !in match.seats.keys) {
        throw ServiceException(UNKNOWN_SEAT,
          "openVoteKick: seat '$targetSeat' does not exist in this match.")
      }
      if (claimedStandings.keys.sorted() != match.seats.keys.sorted()) {
        throw ServiceException(INVALID_ARGUMENT,
          "openVoteKick: claimed standings must cover exactly the match seats.")
      }
      val initiatorSeat = match.uidToSeat(callingUid)
      val availability = voteKickAvailability(
        mode = match.mode,
        completedRounds = match.currentRound - 1,
        standings = claimedStandings,
        targetSeat = targetSeat,
        initiatorSeat = initiatorSeat ?: "",
        botSeats = match.botSeats.toSet(),
        cooldown = match.failedVoteKickCooldown?.let {
          VoteKickCooldown(it.targetSeat, it.blockedUntilRound)
        },
        currentRound = match.currentRound,
      )
      if (availability is VoteKickAvailability.Unavailable) {
        throw ServiceException(
          availabilityReason(availability.reason),
          "openVoteKick: ${availabilityMessage(availability.reason, targetSeat)}",
        )
      }
      val initiator = initiatorSeat ?: throw ServiceException(VOTE_INITIATOR_INELIGIBLE,
        "openVoteKick: you do not own a seat in this match.")
      val eligible = eligibleVoteKickVoters(match.seats.keys, targetSeat, match.botSeats.toSet())
      val vote = engineOpenVote(
        targetSeat = targetSeat,
        initiatorSeat = initiator,
        reason = reason,
        eligible = eligible,
        roundOpened = match.currentRound,
      )
      tx.set(voteRef(matchId), toDoc(vote, matchId).toFields())
      TxOutcome.Ok(
        VoteKickOpenResult(created = true, matchId = matchId, vote = toDoc(vote, matchId)),
      )
    }
  }

  // ── 2. castVoteKick ───────────────────────────────────────────────

  /**
   * Casts the caller's YES/NO. Tally resolves in this same write
   * (PASSED → removal writes; FAILED_* → cooldown write). A vote left
   * OPEN when its match completed is swept to FAILED_TIMEOUT first —
   * the only timeout path, with no invented duration. Terminal votes
   * converge (VOTE_CLOSED, no write); same-value re-votes converge
   * (ALREADY_VOTED, no write); conflicting re-votes are VOTE_LOCKED.
   */
  suspend fun castVoteKick(matchId: String, choice: String): VoteKickCastResult {
    require(matchId.isNotEmpty()) { "castVoteKick: matchId is required." }
    if (choice != VoteKickDoc.VOTE_YES && choice != VoteKickDoc.VOTE_NO) {
      throw ServiceException(INVALID_ARGUMENT,
        "castVoteKick: choice must be one of ${VoteKickDoc.VOTE_YES}/${VoteKickDoc.VOTE_NO}.")
    }
    val callingUid = auth.currentUid()
      ?: throw ServiceException(UNAUTHENTICATED, "castVoteKick: no signed-in user.")
    val matchRef = matches.document(matchId)
    return runTx(db) { tx ->
      val match = readMatch(tx, matchRef)
        ?: return@runTx TxOutcome.Err(ServiceException(MATCH_NOT_FOUND,
          "castVoteKick: match '$matchId' was not found."))
      if (!match.isPlayer(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "castVoteKick: you are not a player in this match."))
      }
      if (match.isRemoved(callingUid)) {
        return@runTx TxOutcome.Err(ServiceException(REMOVED_FROM_MATCH,
          "castVoteKick: you were removed from this match and cannot vote."))
      }
      var vote = readVote(tx, matchId)
        ?: return@runTx TxOutcome.Err(ServiceException(VOTE_NOT_FOUND,
          "castVoteKick: no vote exists for match '$matchId'."))
      if (match.isComplete && vote.status == VoteKickDoc.STATUS_OPEN) {
        sweepStaleVote(tx, matchId, match, vote)
        vote = vote.copy(status = VoteKickDoc.STATUS_FAILED_TIMEOUT)
      }
      if (vote.status != VoteKickDoc.STATUS_OPEN) {
        return@runTx TxOutcome.Ok(castResult(matchId, vote, match, reason = VOTE_CLOSED))
      }
      val seat = match.uidToSeat(callingUid)
        ?: return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "castVoteKick: you do not own a seat in this match."))
      val eligible = eligibleVoteKickVoters(match.seats.keys, vote.targetSeat, match.botSeats.toSet())
      if (seat !in eligible) {
        return@runTx TxOutcome.Err(ServiceException(PERMISSION_DENIED,
          "castVoteKick: seat '$seat' is not an eligible voter in this vote."))
      }
      val existing = vote.votes[seat]
      if (existing != null) {
        if (existing == choice) {
          return@runTx TxOutcome.Ok(castResult(matchId, vote, match, reason = ALREADY_VOTED))
        }
        throw ServiceException(VOTE_LOCKED,
          "castVoteKick: seat '$seat' already voted — votes are immutable.")
      }
      val updated = engineCastVote(
        toEngine(vote),
        seat,
        if (choice == VoteKickDoc.VOTE_YES) {
          VoteKickChoice.YES
        } else {
          VoteKickChoice.NO
        },
        eligible,
      ) ?: throw ServiceException(STALE_GAME_STATE,
        "castVoteKick: the vote changed under you — re-fetch and retry.")
      val resolution = resolveVoteKick(updated, eligible, match.isComplete)
      val nextVersion = vote.version + 1
      var removed = false
      var cooledDown = false
      when (resolution) {
        VoteKickStatus.PASSED -> {
          val removal = removalForVoteKick(
            updated.copy(status = VoteKickStatus.PASSED),
            match.seats,
          ) ?: throw ServiceException(STALE_GAME_STATE,
            "castVoteKick: the vote changed under you — re-fetch and retry.")
          tx.update(matchRef, mapOf(
            "botSeats" to (match.botSeats + removal.targetSeat).distinct(),
            "removedUids" to (match.removedUids + removal.targetUid).distinct(),
            "version" to match.version + 1,
            "updatedAt" to FieldValue.serverTimestamp(),
          ))
          tx.update(voteRef(matchId), mapOf(
            "votes" to updated.votes.mapValues { (_, v) -> v?.let { VoteKickDoc.choiceName(it) } },
            "status" to VoteKickDoc.STATUS_PASSED,
            "version" to nextVersion,
          ))
          removed = true
        }
        VoteKickStatus.FAILED_NO, VoteKickStatus.FAILED_TIMEOUT -> {
          val cooldown = cooldownAfterFailedVote(vote.targetSeat, match.currentRound)
          tx.update(matchRef, mapOf(
            "failedVoteKickCooldown" to VoteKickCooldownData(
              cooldown.targetSeat,
              cooldown.blockedUntilRound,
            ).toFields(),
            "version" to match.version + 1,
            "updatedAt" to FieldValue.serverTimestamp(),
          ))
          tx.update(voteRef(matchId), mapOf(
            "votes" to updated.votes.mapValues { (_, v) -> v?.let { VoteKickDoc.choiceName(it) } },
            "status" to voteStatusName(resolution),
            "version" to nextVersion,
          ))
          cooledDown = true
        }
        VoteKickStatus.OPEN -> {
          tx.update(voteRef(matchId), mapOf(
            "votes" to updated.votes.mapValues { (_, v) -> v?.let { VoteKickDoc.choiceName(it) } },
            "version" to nextVersion,
          ))
        }
      }
      val committed = vote.copy(
        votes = updated.votes.mapValues { (_, v) -> v?.let { VoteKickDoc.choiceName(it) } },
        status = voteStatusName(resolution),
        version = nextVersion,
      )
      TxOutcome.Ok(castResult(matchId, committed, match, removed = removed, cooledDown = cooledDown))
    }
  }

  // ── helpers ───────────────────────────────────────────────────────

  /**
   * Completion sweep (the only FAILED_TIMEOUT path — no invented
   * duration): a vote still OPEN when its match completed resolves
   * FAILED_TIMEOUT ("no majority by timeout = no", RD18) and records
   * the same-target cooldown. The match is over, so the cooldown is
   * moot going forward — it keeps the every-failure-writes-cooldown
   * invariant total for the audit trail.
   */
  private fun sweepStaleVote(
    tx: Transaction,
    matchId: String,
    match: MatchDoc,
    vote: VoteKickDoc,
  ) {
    tx.update(voteRef(matchId), mapOf(
      "status" to VoteKickDoc.STATUS_FAILED_TIMEOUT,
      "version" to vote.version + 1,
    ))
    val cooldown = cooldownAfterFailedVote(vote.targetSeat, match.currentRound)
    tx.update(matches.document(matchId), mapOf(
      "failedVoteKickCooldown" to VoteKickCooldownData(
        cooldown.targetSeat,
        cooldown.blockedUntilRound,
      ).toFields(),
      "version" to match.version + 1,
      "updatedAt" to FieldValue.serverTimestamp(),
    ))
  }

  private fun readMatch(
    tx: Transaction,
    matchRef: com.google.firebase.firestore.DocumentReference,
  ): MatchDoc? {
    val snap = tx.getBlocking(matchRef)
    if (!snap.exists()) return null
    return MatchDoc.fromFields(snap.data ?: emptyMap())
  }

  private fun readVote(
    tx: Transaction,
    matchId: String,
  ): VoteKickDoc? {
    val snap = tx.getBlocking(voteRef(matchId))
    if (!snap.exists()) return null
    return VoteKickDoc.fromFields(snap.data ?: emptyMap())
  }

  private fun toEngine(vote: VoteKickDoc): VoteKickVote = VoteKickVote(
    targetSeat = vote.targetSeat,
    initiatorSeat = vote.initiatorSeat,
    reason = vote.reason,
    votes = vote.votes.mapValues { (_, v) -> v?.let { VoteKickDoc.choiceOf(it) } },
    status = when (vote.status) {
      VoteKickDoc.STATUS_PASSED -> VoteKickStatus.PASSED
      VoteKickDoc.STATUS_FAILED_NO -> VoteKickStatus.FAILED_NO
      VoteKickDoc.STATUS_FAILED_TIMEOUT -> VoteKickStatus.FAILED_TIMEOUT
      else -> VoteKickStatus.OPEN
    },
    roundOpened = vote.roundOpened,
  )

  private fun toDoc(vote: VoteKickVote, matchId: String): VoteKickDoc = VoteKickDoc(
    matchId = matchId,
    targetSeat = vote.targetSeat,
    initiatorSeat = vote.initiatorSeat,
    reason = vote.reason,
    votes = vote.votes.mapValues { (_, v) -> v?.let { VoteKickDoc.choiceName(it) } },
    status = voteStatusName(vote.status),
    roundOpened = vote.roundOpened,
    version = 1,
  )

  private fun voteStatusName(status: VoteKickStatus): String = when (status) {
    VoteKickStatus.OPEN -> VoteKickDoc.STATUS_OPEN
    VoteKickStatus.PASSED -> VoteKickDoc.STATUS_PASSED
    VoteKickStatus.FAILED_NO -> VoteKickDoc.STATUS_FAILED_NO
    VoteKickStatus.FAILED_TIMEOUT -> VoteKickDoc.STATUS_FAILED_TIMEOUT
  }

  private fun castResult(
    matchId: String,
    vote: VoteKickDoc,
    match: MatchDoc,
    reason: String? = null,
    removed: Boolean = false,
    cooledDown: Boolean = false,
  ): VoteKickCastResult {
    val eligible = eligibleVoteKickVoters(match.seats.keys, vote.targetSeat, match.botSeats.toSet())
    val engineVotes = vote.votes.mapValues { (_, v) -> v?.let { VoteKickDoc.choiceOf(it) } }
    return VoteKickCastResult(
      matchId = matchId,
      vote = vote,
      resolution = vote.status,
      yesCount = engineVotes.count { (seat, v) -> seat in eligible && v == VoteKickChoice.YES },
      noCount = engineVotes.count { (seat, v) -> seat in eligible && v == VoteKickChoice.NO },
      eligibleCount = eligible.size,
      threshold = com.estemshan.engine.voteKickThreshold(eligible.size),
      reason = reason,
      removed = removed,
      cooledDown = cooledDown,
    )
  }

  private fun availabilityReason(reason: VoteKickUnavailability): String = when (reason) {
    VoteKickUnavailability.WRONG_MODE -> VOTE_NOT_RANKED
    VoteKickUnavailability.ROUNDS_SHORT -> VOTE_ROUNDS_SHORT
    VoteKickUnavailability.NO_UNIQUE_KOZ -> VOTE_NO_UNIQUE_KOZ
    VoteKickUnavailability.TARGET_NOT_KOZ -> VOTE_TARGET_NOT_KOZ
    VoteKickUnavailability.TARGET_REMOVED -> VOTE_TARGET_REMOVED
    VoteKickUnavailability.INITIATOR_IS_TARGET -> VOTE_INITIATOR_IS_TARGET
    VoteKickUnavailability.INITIATOR_INELIGIBLE -> VOTE_INITIATOR_INELIGIBLE
    VoteKickUnavailability.COOLDOWN_LIVE -> VOTE_COOLDOWN_LIVE
  }

  private fun availabilityMessage(reason: VoteKickUnavailability, targetSeat: String): String = when (reason) {
    VoteKickUnavailability.WRONG_MODE ->
      "Vote Kick is Ranked-only."
    VoteKickUnavailability.ROUNDS_SHORT ->
      "Vote Kick opens once Round 7 has fully completed."
    VoteKickUnavailability.NO_UNIQUE_KOZ ->
      "Vote Kick needs a single unique Koz — last place is tied."
    VoteKickUnavailability.TARGET_NOT_KOZ ->
      "Only the current unique Koz ('$targetSeat') can be voted."
    VoteKickUnavailability.TARGET_REMOVED ->
      "Seat '$targetSeat' was already removed and cannot be voted again."
    VoteKickUnavailability.INITIATOR_IS_TARGET ->
      "You cannot open a vote against yourself."
    VoteKickUnavailability.INITIATOR_INELIGIBLE ->
      "Only an eligible human voter can open a vote."
    VoteKickUnavailability.COOLDOWN_LIVE ->
      "A recent failed vote still cools down '$targetSeat'."
  }
}
