package com.estemshan.engine

/**
 * S60 — manual Vote Kick (RD18, RANKED-only): the pure state machine.
 *
 * A mid-match vote that permanently removes a griefer. Seven gates, an
 * immutable once-vote, majority over eligible HUMANS, a frozen target, a
 * 5-round same-target cooldown on failure, and terminal removal recorded
 * as a Koz outcome (D10).
 *
 * Layering: everything here is pure Kotlin over caller-supplied inputs —
 * no Firestore, no clock, no GameSession. The standings this reasons
 * about are the CALLER's claimed current totals (the initiator supplies
 * them at vote-open, exactly the way endMatch takes claimed finalScores:
 * structurally validated, RD14-residual, settlement-correctable later —
 * never invented here). :services translates documents into these inputs
 * and executes removals; S40 binds the exposed state (target, tally,
 * eligible, voted, unavailability reasons).
 *
 * OPEN-2 (owner-resolved per the S60 amendment): gameplay continues
 * uninterrupted while a vote is open — no pause primitives exist
 * anywhere in this file, eligibility is recomputed from the CURRENT bot
 * set on every resolve (a voter who goes bot simply leaves the eligible
 * set, D7), and the target, once frozen into the vote, is never
 * re-derived from shifting standings.
 *
 * RD20 separation: the automatic 15-timeout removal shares NOTHING with
 * this file — no counter reads, no shared types beyond MatchMode, no
 * shared tests. That mechanism is S61's.
 *
 * Pure functions, no I/O.
 */

/** Vote lifecycle: OPEN → PASSED / FAILED_NO / FAILED_TIMEOUT. Terminal states are final. */
enum class VoteKickStatus { OPEN, PASSED, FAILED_NO, FAILED_TIMEOUT }

/** One voter's choice. YES/NO once, immutable (the rematch vote's rule). */
enum class VoteKickChoice { YES, NO }

/**
 * Why a vote cannot open — the exact explainable states S40 renders
 * in-place (RD18/S40: never merely absent).
 */
enum class VoteKickUnavailability {
  /** mode != RANKED (Rooms + Unranked never vote — entry absent, RD18). */
  WRONG_MODE,

  /** Fewer than 7 fully complete rounds (vote opens Round 8+). */
  ROUNDS_SHORT,

  /** No single unique Koz — last place tied (RD17) or no scores. */
  NO_UNIQUE_KOZ,

  /** Named target is not the unique Koz. */
  TARGET_NOT_KOZ,

  /** Named target is already bot-held (removed) — it cannot be re-kicked. */
  TARGET_REMOVED,

  /** Initiator is the target. */
  INITIATOR_IS_TARGET,

  /** Initiator is not an eligible voter (bot-held or unknown seat). */
  INITIATOR_INELIGIBLE,

  /** A 5-round cooldown against this target is still live (D8). */
  COOLDOWN_LIVE,
}

/** D8: 5 rounds, same target only, measured in rounds, authoritative. */
data class VoteKickCooldown(val targetSeat: String, val blockedUntilRound: Int)

/**
 * One manual Vote Kick vote. [targetSeat] is frozen at open and never
 * re-derived. [votes] carries one slot per eligible human at open;
 * the target's slot is structurally absent, never null.
 */
data class VoteKickVote(
  val targetSeat: String,
  val initiatorSeat: String,
  val reason: String,
  val votes: Map<String, VoteKickChoice?>,
  val status: VoteKickStatus,
  val roundOpened: Int,
)

sealed interface VoteKickAvailability {
  data object Available : VoteKickAvailability
  data class Unavailable(val reason: VoteKickUnavailability) : VoteKickAvailability
}

/** Removal descriptor a PASSED vote resolves to (services executes it). */
data class VoteKickRemoval(val targetSeat: String, val targetUid: String)

/** Majority over eligible humans: 3 → 2, 2 → 2, 1 → 1. Never bot-padded (D7). */
fun voteKickThreshold(eligibleCount: Int): Int = eligibleCount / 2 + 1

/**
 * The unique current Koz (last place), or null when last place is tied
 * (RD17 — never fabricate a Koz) or there are no scores.
 */
fun uniqueKozSeat(standings: Map<String, Int>): String? {
  if (standings.isEmpty()) return null
  val floor = standings.values.minOrNull() ?: return null
  val last = standings.filterValues { it == floor }.keys.toList()
  return if (last.size == 1) last[0] else null
}

/**
 * Eligible voters: seated humans minus the target. Bot-held seats get no
 * slot and count toward no denominator (D7).
 */
fun eligibleVoteKickVoters(
  seats: Set<String>,
  targetSeat: String,
  botSeats: Set<String>,
): Set<String> = seats - targetSeat - botSeats

/** D8: failure in round N blocks that target until round N + 5. */
fun cooldownAfterFailedVote(targetSeat: String, failedRound: Int): VoteKickCooldown =
  VoteKickCooldown(targetSeat, failedRound + 5)

/**
 * D8: live while the current round is still below the blocked-until
 * round — a failure in Round 8 blocks until Round 13 (votable again at
 * 13). Same target only; a different target is never blocked by this.
 */
fun isCooldownLive(cooldown: VoteKickCooldown?, targetSeat: String, currentRound: Int): Boolean =
  cooldown != null && cooldown.targetSeat == targetSeat && currentRound < cooldown.blockedUntilRound

/**
 * The full seven-gate check, in spec order (RD18/S40). Pure so both the
 * services enforcement and S40's future in-place explanation read the
 * same verdict: [completedRounds] is fully-complete rounds
 * (currentRound − 1), [currentRound] the in-progress round for the
 * cooldown clock.
 */
fun voteKickAvailability(
  mode: MatchMode,
  completedRounds: Int,
  standings: Map<String, Int>,
  targetSeat: String,
  initiatorSeat: String,
  botSeats: Set<String>,
  cooldown: VoteKickCooldown?,
  currentRound: Int,
): VoteKickAvailability {
  if (mode != MatchMode.RANKED) return VoteKickAvailability.Unavailable(VoteKickUnavailability.WRONG_MODE)
  if (completedRounds < 7) return VoteKickAvailability.Unavailable(VoteKickUnavailability.ROUNDS_SHORT)
  val koz = uniqueKozSeat(standings)
    ?: return VoteKickAvailability.Unavailable(VoteKickUnavailability.NO_UNIQUE_KOZ)
  if (targetSeat != koz) return VoteKickAvailability.Unavailable(VoteKickUnavailability.TARGET_NOT_KOZ)
  if (targetSeat in botSeats) return VoteKickAvailability.Unavailable(VoteKickUnavailability.TARGET_REMOVED)
  if (initiatorSeat == targetSeat) {
    return VoteKickAvailability.Unavailable(VoteKickUnavailability.INITIATOR_IS_TARGET)
  }
  val eligible = eligibleVoteKickVoters(standings.keys + targetSeat, targetSeat, botSeats)
  if (initiatorSeat !in eligible) {
    return VoteKickAvailability.Unavailable(VoteKickUnavailability.INITIATOR_INELIGIBLE)
  }
  if (isCooldownLive(cooldown, targetSeat, currentRound)) {
    return VoteKickAvailability.Unavailable(VoteKickUnavailability.COOLDOWN_LIVE)
  }
  return VoteKickAvailability.Available
}

/**
 * Opens a vote over pre-checked gates. Vote slots cover exactly the
 * eligible humans; the target's slot is structurally absent. Gameplay
 * is untouched — opening writes no scores, moves no rounds, pauses
 * nothing.
 */
fun openVoteKick(
  targetSeat: String,
  initiatorSeat: String,
  reason: String,
  eligible: Set<String>,
  roundOpened: Int,
): VoteKickVote = VoteKickVote(
  targetSeat = targetSeat,
  initiatorSeat = initiatorSeat,
  reason = reason,
  votes = eligible.associateWith { null },
  status = VoteKickStatus.OPEN,
  roundOpened = roundOpened,
)

/**
 * Casts one immutable vote. Returns null on any violation (vote not
 * open, seat not eligible, seat already voted, target voting) — the
 * caller maps null to its denial. The target has no slot by
 * construction, so a target ballot is structurally impossible.
 */
fun castVoteKick(
  vote: VoteKickVote,
  seat: String,
  choice: VoteKickChoice,
  eligible: Set<String>,
): VoteKickVote? {
  if (vote.status != VoteKickStatus.OPEN) return null
  if (seat !in eligible) return null
  if (vote.votes[seat] != null) return null
  if (seat == vote.targetSeat) return null
  return vote.copy(votes = vote.votes + (seat to choice))
}

/**
 * Resolves an OPEN vote against the CURRENT eligible set (recomputed by
 * the caller every time — a voter who went bot leaves the set and their
 * cast ballot leaves the tally with them, D7). Tally counts ballots
 * from currently-eligible seats only: ghost votes never decide.
 *
 * - YES ≥ threshold → PASSED.
 * - YES unreachable (yes + outstanding < threshold) → FAILED_NO.
 * - matchComplete with no tally decision → FAILED_TIMEOUT ("no majority
 *   by timeout = no", RD18). No wall-clock deadline is invented: the
 *   timeout here is overtaking completion only.
 * - else OPEN.
 *
 * Terminal votes stay terminal (re-resolution is identity).
 */
fun resolveVoteKick(
  vote: VoteKickVote,
  eligible: Set<String>,
  matchComplete: Boolean,
): VoteKickStatus {
  if (vote.status != VoteKickStatus.OPEN) return vote.status
  val threshold = voteKickThreshold(eligible.size)
  val yes = vote.votes.count { (seat, choice) -> seat in eligible && choice == VoteKickChoice.YES }
  val outstanding = (eligible - vote.votes.filterValues { it != null }.keys).size
  if (yes >= threshold) return VoteKickStatus.PASSED
  if (yes + outstanding < threshold) return VoteKickStatus.FAILED_NO
  if (matchComplete) return VoteKickStatus.FAILED_TIMEOUT
  return VoteKickStatus.OPEN
}

/**
 * The removal a PASSED vote resolves to, for services to execute: the
 * seat goes bot-held, the uid is banned from rejoin, and the removal is
 * recorded as a Koz outcome (D10 — feed [MatchOutcome.KOZ] to
 * [accumulateRankedStat] wherever the 9 statistics settle).
 */
fun removalForVoteKick(
  vote: VoteKickVote,
  seats: Map<String, String>,
): VoteKickRemoval? {
  if (vote.status != VoteKickStatus.PASSED) return null
  val uid = seats[vote.targetSeat] ?: return null
  return VoteKickRemoval(vote.targetSeat, uid)
}
