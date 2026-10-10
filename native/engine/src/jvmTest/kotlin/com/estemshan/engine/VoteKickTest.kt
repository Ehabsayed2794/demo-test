package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S60 — manual Vote Kick (RD18): the seven gates, immutable once-votes,
 * human-majority tally with bot recompute, frozen target, 5-round
 * same-target cooldown, terminal removal, and the D10 Koz recording
 * point. Pure engine, no I/O; the Firestore shape (vote doc, cooldown
 * field, removal writes) is proven in :services, never here.
 */
class VoteKickTest {

  private val seats = setOf("p1", "p2", "p3", "p4")

  /** Unique Koz p4; initiator p1; no bots; no cooldown. */
  private fun available(
    mode: MatchMode = MatchMode.RANKED,
    completedRounds: Int = 7,
    standings: Map<String, Int> = mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77),
    targetSeat: String = "p4",
    initiatorSeat: String = "p1",
    botSeats: Set<String> = emptySet(),
    cooldown: VoteKickCooldown? = null,
    currentRound: Int = 8,
  ) = voteKickAvailability(
    mode, completedRounds, standings, targetSeat, initiatorSeat, botSeats, cooldown, currentRound,
  )

  // ── unique Koz (RD17) ─────────────────────────────────────────────

  @Test
  fun uniqueLastPlaceResolves() {
    assertEquals("p4", uniqueKozSeat(mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77)))
  }

  @Test
  fun tiedLastPlaceHasNoKoz() {
    assertNull(uniqueKozSeat(mapOf("p1" to 44, "p2" to 44, "p3" to 60, "p4" to 60)))
    assertNull(uniqueKozSeat(mapOf("p1" to 10, "p2" to 10, "p3" to 10, "p4" to 10)))
    assertNull(uniqueKozSeat(emptyMap()))
  }

  // ── eligibility + threshold (D7) ──────────────────────────────────

  @Test
  fun eligibleIsHumansMinusTarget() {
    assertEquals(
      setOf("p1", "p2", "p3"),
      eligibleVoteKickVoters(seats, "p4", emptySet()),
    )
  }

  @Test
  fun botSeatsHoldNoSlotAndShrinkTheDenominator() {
    assertEquals(
      setOf("p1", "p2"),
      eligibleVoteKickVoters(seats, "p4", setOf("p3")),
    )
    // The UI shows the HUMAN eligible count, never bot-padded.
    assertEquals(2, eligibleVoteKickVoters(seats, "p4", setOf("p3")).size)
  }

  @Test
  fun majorityThresholdOverHumans() {
    assertEquals(2, voteKickThreshold(3))
    assertEquals(2, voteKickThreshold(2))
    assertEquals(1, voteKickThreshold(1))
  }

  // ── the seven gates ───────────────────────────────────────────────

  @Test
  fun happyPathIsAvailable() {
    assertEquals(VoteKickAvailability.Available, available())
  }

  @Test
  fun rankDownIsRanked() {
    // Rank-down is RANKED + a private flag, never a mode — same gate.
    assertEquals(VoteKickAvailability.Available, available(mode = MatchMode.RANKED))
  }

  @Test
  fun roomsAndUnrankedNeverVote() {
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.WRONG_MODE),
      available(mode = MatchMode.ROOM),
    )
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.WRONG_MODE),
      available(mode = MatchMode.UNRANKED),
    )
  }

  @Test
  fun roundSevenInProgressIsRefused() {
    // Round 7 must have FULLY completed: 6 complete rounds is short,
    // 7 opens Round 8+.
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.ROUNDS_SHORT),
      available(completedRounds = 6, currentRound = 7),
    )
    assertEquals(VoteKickAvailability.Available, available(completedRounds = 7, currentRound = 8))
  }

  @Test
  fun tiedLastBlocksWithReason() {
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.NO_UNIQUE_KOZ),
      available(standings = mapOf("p1" to 44, "p2" to 44, "p3" to 60, "p4" to 60)),
    )
  }

  @Test
  fun nonKozTargetIsRefused() {
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.TARGET_NOT_KOZ),
      available(targetSeat = "p2"),
    )
  }

  @Test
  fun removedTargetCannotBeRekicked() {
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.TARGET_REMOVED),
      available(targetSeat = "p4", botSeats = setOf("p4")),
    )
  }

  @Test
  fun initiatorCannotBeTheTarget() {
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.INITIATOR_IS_TARGET),
      available(initiatorSeat = "p4"),
    )
  }

  @Test
  fun botInitiatorIsIneligible() {
    assertEquals(
      VoteKickAvailability.Unavailable(VoteKickUnavailability.INITIATOR_INELIGIBLE),
      available(initiatorSeat = "p3", botSeats = setOf("p3")),
    )
  }

  @Test
  fun liveCooldownBlocksSameTargetOnly() {
    // Failure in Round 8 blocks that target until Round 13.
    val cooldown = cooldownAfterFailedVote("p4", failedRound = 8)
    assertEquals(VoteKickCooldown("p4", 13), cooldown)
    for (round in 8..12) {
      assertTrue(isCooldownLive(cooldown, "p4", round))
      assertEquals(
        VoteKickAvailability.Unavailable(VoteKickUnavailability.COOLDOWN_LIVE),
        available(cooldown = cooldown, currentRound = round),
      )
    }
    assertFalse(isCooldownLive(cooldown, "p4", 13))
    assertEquals(VoteKickAvailability.Available, available(cooldown = cooldown, currentRound = 13))
    // A different target is never blocked by this cooldown.
    assertFalse(isCooldownLive(cooldown, "p2", 8))
  }

  // ── casting: once, immutable, eligible-only ───────────────────────

  private fun openVote(): VoteKickVote = openVoteKick(
    targetSeat = "p4",
    initiatorSeat = "p1",
    reason = "griefing: throwing tricks on purpose",
    eligible = setOf("p1", "p2", "p3"),
    roundOpened = 8,
  )

  @Test
  fun openVoteHasNullSlotsAndFrozenTarget() {
    val vote = openVote()
    assertEquals(VoteKickStatus.OPEN, vote.status)
    assertEquals("p4", vote.targetSeat)
    assertEquals(mapOf("p1" to null, "p2" to null, "p3" to null), vote.votes)
    assertFalse(vote.votes.containsKey("p4"))
  }

  @Test
  fun voteIsOnceAndImmutable() {
    val vote = openVote()
    val cast = castVoteKick(vote, "p1", VoteKickChoice.YES, setOf("p1", "p2", "p3"))!!
    assertEquals(VoteKickChoice.YES, cast.votes["p1"])
    // Same seat again — even the same value — is refused.
    assertNull(castVoteKick(cast, "p1", VoteKickChoice.YES, setOf("p1", "p2", "p3")))
    assertNull(castVoteKick(cast, "p1", VoteKickChoice.NO, setOf("p1", "p2", "p3")))
  }

  @Test
  fun targetAndBotsAndStrangersCannotVote() {
    val vote = openVote()
    val eligible = setOf("p1", "p2", "p3")
    assertNull(castVoteKick(vote, "p4", VoteKickChoice.NO, eligible))
    assertNull(castVoteKick(vote, "p3", VoteKickChoice.YES, setOf("p1", "p2")))
    assertNull(castVoteKick(vote, "ghost", VoteKickChoice.YES, eligible))
  }

  @Test
  fun closedVoteRejectsCasts() {
    val vote = openVote().copy(status = VoteKickStatus.PASSED)
    assertNull(castVoteKick(vote, "p1", VoteKickChoice.YES, setOf("p1", "p2", "p3")))
  }

  // ── resolution ────────────────────────────────────────────────────

  private fun voted(vararg choices: Pair<String, VoteKickChoice>): VoteKickVote {
    var vote = openVote()
    for ((seat, choice) in choices) {
      vote = castVoteKick(vote, seat, choice, setOf("p1", "p2", "p3"))!!
    }
    return vote
  }

  @Test
  fun twoYesPasses() {
    val vote = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.YES)
    assertEquals(
      VoteKickStatus.PASSED,
      resolveVoteKick(vote, setOf("p1", "p2", "p3"), matchComplete = false),
    )
  }

  @Test
  fun twoNoFails() {
    val vote = voted("p1" to VoteKickChoice.NO, "p2" to VoteKickChoice.NO)
    assertEquals(
      VoteKickStatus.FAILED_NO,
      resolveVoteKick(vote, setOf("p1", "p2", "p3"), matchComplete = false),
    )
  }

  @Test
  fun splitPendingStaysOpen() {
    val vote = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.NO)
    assertEquals(
      VoteKickStatus.OPEN,
      resolveVoteKick(vote, setOf("p1", "p2", "p3"), matchComplete = false),
    )
  }

  @Test
  fun twoEligibleHumansDecideAlone() {
    // A seat went bot: threshold recomputes over the 2 remaining humans.
    val vote = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.YES)
    assertEquals(
      VoteKickStatus.PASSED,
      resolveVoteKick(vote, setOf("p1", "p2"), matchComplete = false),
    )
    val split = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.NO)
    assertEquals(
      VoteKickStatus.FAILED_NO,
      resolveVoteKick(split, setOf("p1", "p2"), matchComplete = false),
    )
  }

  @Test
  fun departedYesVoterLeavesTheTally() {
    // p1 voted YES, then went bot: the ballot leaves the eligible set
    // with the seat (D7) — ghost votes never decide.
    val vote = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.NO)
    assertEquals(
      VoteKickStatus.FAILED_NO,
      resolveVoteKick(vote, setOf("p2", "p3"), matchComplete = false),
    )
  }

  @Test
  fun completionSweepTimesOutUndecidedVotes() {
    // "No majority by timeout = no" (RD18): an undecided vote overtaken
    // by match completion resolves FAILED_TIMEOUT — no clock invented.
    val vote = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.NO)
    assertEquals(
      VoteKickStatus.FAILED_TIMEOUT,
      resolveVoteKick(vote, setOf("p1", "p2", "p3"), matchComplete = true),
    )
  }

  @Test
  fun decidedTallySurvivesCompletion() {
    // A tally that already decided keeps its verdict at completion.
    val passed = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.YES)
    assertEquals(
      VoteKickStatus.PASSED,
      resolveVoteKick(passed, setOf("p1", "p2", "p3"), matchComplete = true),
    )
  }

  @Test
  fun terminalVotesStayTerminal() {
    val passed = openVote().copy(status = VoteKickStatus.PASSED)
    assertEquals(
      VoteKickStatus.PASSED,
      resolveVoteKick(passed, setOf("p1", "p2", "p3"), matchComplete = false),
    )
  }

  // ── frozen target (OPEN-2) ────────────────────────────────────────

  @Test
  fun targetNeverRederivesFromShiftingStandings() {
    // Rankings move mid-vote; the frozen target does not follow them.
    val vote = voted("p1" to VoteKickChoice.YES)
    assertEquals("p4", vote.targetSeat)
    val shifted = mapOf("p1" to 77, "p2" to 98, "p3" to 134, "p4" to 120)
    assertEquals("p1", uniqueKozSeat(shifted))
    assertEquals("p4", vote.targetSeat)
    assertEquals(
      VoteKickStatus.OPEN,
      resolveVoteKick(vote, setOf("p1", "p2", "p3"), matchComplete = false),
    )
  }

  // ── removal + D10 recording point ─────────────────────────────────

  @Test
  fun passedVoteResolvesRemovalDescriptor() {
    val vote = voted("p1" to VoteKickChoice.YES, "p2" to VoteKickChoice.YES)
      .copy(status = VoteKickStatus.PASSED)
    val seats = mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
    assertEquals(VoteKickRemoval("p4", "u4"), removalForVoteKick(vote, seats))
  }

  @Test
  fun nonPassedVotesResolveNoRemoval() {
    val seats = mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
    assertNull(removalForVoteKick(openVote(), seats))
    assertNull(
      removalForVoteKick(openVote().copy(status = VoteKickStatus.FAILED_NO), seats),
    )
  }

  @Test
  fun kickedRemovalAccumulatesAsKoz() {
    // D10: the removal lands in the 9 career statistics as a KOZ outcome.
    val profile = RankedProfile(seasonId = "S12")
    val after = accumulateRankedStat(RankedStats(), MatchOutcome.KOZ, profile, MatchMode.RANKED)
    assertEquals(1, after.gamesPlayed)
    assertEquals(1, after.kozCount)
    assertEquals(100, after.kozPct)
  }
}
