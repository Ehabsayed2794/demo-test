package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-20 (engine slice) — the pure Auto-Match session policy, pinned:
 * constants 4 / 120 000 / 30 000; the fill timer anchored to the first
 * player; the vote opening at the fill deadline and closing at 30 s on
 * the exact `approvals.size * 2 > humans.size` arithmetic with non-votes
 * rejecting; a late tick catching up in one call; four humans filling
 * without a vote; the leaver removed from the denominator and both vote
 * sets; terminal immutability; the ABSOLUTE one-tier band (A8.6 / Q41) —
 * asserted as distance only, never a pairing direction (O-15); humans-only
 * strength tiers; a foreign tier failing loudly. Every time value is a
 * literal parameter, so nothing here reads a clock.
 */
class MatchmakingSessionTest {

  private fun threeHumansVoting(): MatchmakingSession =
    MatchmakingSession.open("u1", "Gold", 0L)
      .join("u2", "Gold", 10_000L)
      .join("u3", "Silver", 20_000L)
      .step(120_000L)

  private fun twoHumansVoting(): MatchmakingSession =
    MatchmakingSession.open("u1", "Gold", 0L)
      .join("u2", "Gold", 10_000L)
      .step(120_000L)

  @Test
  fun theThreeConstantsAreExactlyFourSeatsTwoMinutesAndThirtySeconds() {
    assertEquals(4, MATCHMAKING_SEATS)
    assertEquals(120_000L, MATCHMAKING_FILL_MILLIS)
    assertEquals(30_000L, MATCHMAKING_VOTE_MILLIS)
  }

  @Test
  fun theFillTimerIsAnchoredToTheFirstPlayerNotTheLastJoin() {
    val session = MatchmakingSession.open("u1", "Gold", 0L)
      .join("u2", "Gold", 60_000L)
    assertEquals(MatchmakingPhase.FILLING, session.step(119_999L).phase)
    val stepped = session.step(120_000L)
    assertEquals(MatchmakingPhase.VOTING, stepped.phase)
    assertEquals(
      "the vote opens at the fill deadline, not at the tick that noticed it",
      0L + 120_000L,
      stepped.voteOpenedAtMillis,
    )
    assertEquals(150_000L, stepped.voteDeadlineMillis)
  }

  @Test
  fun aLateTickCatchesUpAcrossBothDeadlinesInOneCall() {
    // Opened at 0 with one human: step at +200 000 must open the vote at
    // the 120 000 fill deadline AND resolve it (its 150 000 deadline has
    // long passed; no approvals), all in the same call.
    val stepped = MatchmakingSession.open("u1", "Gold", 0L).step(200_000L)
    assertEquals(120_000L, stepped.voteOpenedAtMillis)
    assertEquals(MatchmakingPhase.REJECTED, stepped.phase)
    assertEquals(MatchmakingRejectReason.VOTE_FAILED, stepped.rejectReason)
  }

  @Test
  fun fourHumansBeforeTheTimerFillWithNoVoteAndNoBots() {
    val session = MatchmakingSession.open("u1", "Gold", 0L)
      .join("u2", "Gold", 10_000L)
      .join("u3", "Silver", 20_000L)
      .join("u4", "Gold", 30_000L)
    assertEquals(MatchmakingPhase.FILLED, session.phase)
    assertFalse(session.filledWithBots)
    assertEquals(0, session.botSeatsToFill)
    assertNull(session.voteOpenedAtMillis)
    assertNull(session.voteDeadlineMillis)
    assertEquals(MatchmakingPhase.FILLED, session.step(120_000L).phase)
  }

  @Test
  fun threeHumansWithTwoApprovalsFillEarlyWithOneBotSeat() {
    val oneVote = threeHumansVoting().approve("u1")
    assertEquals(MatchmakingPhase.VOTING, oneVote.phase)
    val twoVotes = oneVote.approve("u2")
    assertEquals("2*2 > 3 lands the moment the second approval arrives",
      MatchmakingPhase.FILLED, twoVotes.phase)
    assertTrue(twoVotes.filledWithBots)
    assertEquals(1, twoVotes.botSeatsToFill)
    val after = twoVotes.step(150_000L)
    assertEquals(MatchmakingPhase.FILLED, after.phase)
    assertEquals(1, after.botSeatsToFill)
  }

  @Test
  fun threeHumansWithOneApprovalAndTwoNonVotesRejectAtTheDeadline() {
    val voting = threeHumansVoting().approve("u1")
    assertEquals(MatchmakingPhase.VOTING, voting.step(149_999L).phase)
    val resolved = voting.step(150_000L)
    assertEquals(MatchmakingPhase.REJECTED, resolved.phase)
    assertEquals(MatchmakingRejectReason.VOTE_FAILED, resolved.rejectReason)
    assertFalse(resolved.filledWithBots)
    assertEquals(0, resolved.botSeatsToFill)
  }

  @Test
  fun twoHumansWithOneApprovalAndOneNonVoteRejectAtTheDeadline() {
    val resolved = twoHumansVoting().approve("u1").step(150_000L)
    assertEquals(MatchmakingPhase.REJECTED, resolved.phase)
    assertEquals(MatchmakingRejectReason.VOTE_FAILED, resolved.rejectReason)
  }

  @Test
  fun oneHumanAloneWhoApprovesFillsThreeBotSeats() {
    val voting = MatchmakingSession.open("u1", "Gold", 0L).step(120_000L)
    assertEquals(MatchmakingPhase.VOTING, voting.phase)
    val filled = voting.approve("u1")
    assertEquals("1*2 > 1 — the first player waits two minutes alone and fills",
      MatchmakingPhase.FILLED, filled.phase)
    assertTrue(filled.filledWithBots)
    assertEquals(3, filled.botSeatsToFill)
  }

  @Test
  fun aSecondVoteFromTheSameUidIsANoopAndCannotFlipIt() {
    val voted = twoHumansVoting().approve("u1").reject("u1").approve("u1")
    assertEquals(setOf("u1"), voted.approvals)
    assertEquals(emptySet<String>(), voted.rejections)
    assertEquals(MatchmakingPhase.VOTING, voted.phase)
    val resolved = voted.step(150_000L)
    assertEquals("one approval of two humans still loses at the deadline",
      MatchmakingPhase.REJECTED, resolved.phase)
  }

  @Test
  fun anExplicitRejectCountsExactlyLikeANonVote() {
    // u1 approves, u2 rejects, u3 abstains: the rejection adds nothing to
    // the approval side, so the outcome is the deadline rejection a
    // non-vote would have produced.
    val voting = threeHumansVoting().approve("u1").reject("u2")
    assertEquals(MatchmakingPhase.VOTING, voting.step(149_999L).phase)
    val resolved = voting.step(150_000L)
    assertEquals(MatchmakingPhase.REJECTED, resolved.phase)
    assertEquals(MatchmakingRejectReason.VOTE_FAILED, resolved.rejectReason)
  }

  @Test
  fun whenEveryHumanHasVotedAndNoMajorityExistsTheVoteRejectsEarly() {
    val voting = threeHumansVoting().approve("u1").reject("u2")
    val resolved = voting.reject("u3")
    assertEquals("the electorate is complete with no majority — resolve now",
      MatchmakingPhase.REJECTED, resolved.phase)
    assertEquals(MatchmakingRejectReason.VOTE_FAILED, resolved.rejectReason)
  }

  @Test
  fun aNonVoterKeepsTheVoteOpenUntilTheDeadline() {
    val voting = threeHumansVoting().approve("u1")
    assertEquals(MatchmakingPhase.VOTING, voting.step(149_999L).phase)
    assertEquals(MatchmakingPhase.REJECTED, voting.step(150_000L).phase)
  }

  @Test
  fun aNonHumansVoteIsIgnored() {
    val voted = twoHumansVoting().approve("ghost").reject("ghost")
    assertEquals(emptySet<String>(), voted.approvals)
    assertEquals(emptySet<String>(), voted.rejections)
    assertEquals(MatchmakingPhase.VOTING, voted.phase)
  }

  @Test
  fun aLeaverIsRemovedFromTheDenominatorAndFromBothVoteSets() {
    val voting = threeHumansVoting().approve("u1").reject("u2")
    val after = voting.leave("u2")
    assertEquals(listOf("u1", "u3"), after.humans.map { it.uid })
    assertEquals(setOf("u1"), after.approvals)
    assertEquals(emptySet<String>(), after.rejections)
    assertEquals(MatchmakingPhase.VOTING, after.phase)
    assertEquals("1*2 > 2 is false — the shrunken denominator still loses",
      MatchmakingPhase.REJECTED, after.step(150_000L).phase)
  }

  @Test
  fun theLastHumanLeavingAbandonsTheSession() {
    val voting = MatchmakingSession.open("u1", "Gold", 0L).step(120_000L)
    val abandoned = voting.leave("u1")
    assertEquals(MatchmakingPhase.REJECTED, abandoned.phase)
    assertEquals(MatchmakingRejectReason.ABANDONED, abandoned.rejectReason)
    assertTrue(abandoned.humans.isEmpty())
    val abandonedFilling = MatchmakingSession.open("u2", "Gold", 0L).leave("u2")
    assertEquals(MatchmakingRejectReason.ABANDONED, abandonedFilling.rejectReason)
  }

  @Test
  fun aLeaverShrinksTheDenominatorAndCanSettleTheVote() {
    // Engineering judgment pinned here: the electorate is the humans
    // currently waiting. After u2 leaves, the one remaining human's
    // approval IS a strict majority of one — exactly the "first player
    // waits two minutes alone" arithmetic (1*2 > 1) — so the leave
    // settles the vote early at the same outcome the deadline reaches.
    val filled = twoHumansVoting().approve("u1").leave("u2")
    assertEquals(MatchmakingPhase.FILLED, filled.phase)
    assertTrue(filled.filledWithBots)
    assertEquals(3, filled.botSeatsToFill)
  }

  @Test
  fun terminalPhasesIgnoreJoinApproveRejectLeaveAndStep() {
    val filled = MatchmakingSession.open("u1", "Gold", 0L)
      .join("u2", "Gold", 10_000L)
      .join("u3", "Silver", 20_000L)
      .join("u4", "Gold", 30_000L)
    assertEquals(MatchmakingPhase.FILLED, filled.phase)
    assertEquals(filled, filled.join("u5", "Gold", 40_000L))
    assertEquals(filled, filled.approve("u1"))
    assertEquals(filled, filled.reject("u1"))
    assertEquals(filled, filled.leave("u1"))
    assertEquals(filled, filled.step(999_999L))

    val rejected = threeHumansVoting().step(150_000L)
    assertEquals(MatchmakingPhase.REJECTED, rejected.phase)
    assertEquals(rejected, rejected.join("u5", "Gold", 160_000L))
    assertEquals(rejected, rejected.approve("u1"))
    assertEquals(rejected, rejected.reject("u1"))
    assertEquals(rejected, rejected.leave("u1"))
    assertEquals(rejected, rejected.step(999_999L))
  }

  @Test
  fun sameTierAndOneTierApartAreInsideTheBand() {
    val session = MatchmakingSession.open("u1", "Gold", 0L)
    assertTrue(session.bandAccepts("Gold"))
    assertTrue(session.bandAccepts("Silver"))
    assertTrue(session.bandAccepts("Platinum"))
  }

  @Test
  fun twoTiersApartIsNeverWithinTheBand() {
    // Distance only, never direction (O-15): two tiers away is outside
    // the band in whichever arrangement the pair is read.
    val gold = MatchmakingSession.open("u1", "Gold", 0L)
    assertFalse(gold.bandAccepts("Bronze"))
    assertFalse(gold.bandAccepts("Diamond"))
    val bronze = MatchmakingSession.open("u1", "Bronze", 0L)
    assertFalse(bronze.bandAccepts("Gold"))
    val platinum = MatchmakingSession.open("u1", "Platinum", 0L)
    assertFalse(platinum.bandAccepts("Silver"))
  }

  @Test
  fun aCandidateTwoTiersFromAnyOneSeatedHumanIsRejected() {
    val session = MatchmakingSession.open("u1", "Gold", 0L)
      .join("u2", "Platinum", 10_000L)
    assertFalse("Silver is one tier from Gold but two from Platinum",
      session.bandAccepts("Silver"))
    val mirrored = MatchmakingSession.open("u1", "Silver", 0L)
      .join("u2", "Gold", 10_000L)
    assertFalse("Platinum is one tier from Gold but two from Silver",
      mirrored.bandAccepts("Platinum"))
  }

  @Test
  fun aCandidateTwoTiersAwayCannotJoinTheTableDirectly() {
    // The band as a join safety net: the table cannot become illegal
    // through a direct call.
    val session = MatchmakingSession.open("u1", "Gold", 0L)
    assertEquals(session, session.join("u2", "Bronze", 10_000L))
  }

  @Test
  fun anEmptySessionAcceptsAnyValidTier() {
    val abandoned = MatchmakingSession.open("u1", "Gold", 0L).leave("u1")
    assertTrue(abandoned.humans.isEmpty())
    for (tier in poolTierOrder) {
      assertTrue("empty session accepts $tier", abandoned.bandAccepts(tier))
    }
  }

  @Test
  fun theBandAnswerNeverWidensWithSessionAge() {
    // A8.6 / Q41: the band is absolute. There is no widening branch to
    // prove — this pins the invariant across the fill deadline (120 000),
    // the vote deadline (150 000) and beyond it (180 000, 180 001).
    var session = MatchmakingSession.open("u1", "Gold", 0L)
    for (now in listOf(120_000L, 150_000L, 180_000L, 180_001L)) {
      session = session.step(now)
      assertFalse("band at $now", session.bandAccepts("Bronze"))
      assertTrue("band at $now", session.bandAccepts("Silver"))
      assertFalse("band at $now", session.bandAccepts("Diamond"))
    }
  }

  @Test
  fun openFailsLoudlyOnABlankUid() {
    assertThrows(IllegalArgumentException::class.java) {
      MatchmakingSession.open("  ", "Gold", 0L)
    }
  }

  @Test
  fun foreignTiersFailLoudlyOnOpenJoinAndBandAccepts() {
    assertThrows(IllegalArgumentException::class.java) {
      MatchmakingSession.open("u1", "Mithril", 0L)
    }
    val session = MatchmakingSession.open("u1", "Gold", 0L)
    assertThrows(IllegalArgumentException::class.java) {
      session.join("u2", "Mithril", 10_000L)
    }
    assertThrows(IllegalArgumentException::class.java) {
      session.bandAccepts("Mithril")
    }
  }

  @Test
  fun strengthTiersAreTheHumansOnlyInJoinOrder() {
    val filled = threeHumansVoting().approve("u1").approve("u2")
    assertEquals(MatchmakingPhase.FILLED, filled.phase)
    assertEquals(1, filled.botSeatsToFill)
    assertEquals("three humans plus one bot seat — three strength entries",
      listOf("Gold", "Gold", "Silver"), filled.strengthTiers())
  }

  @Test
  fun aHumanWhoLeftIsAbsentFromStrengthTiers() {
    val session = MatchmakingSession.open("u1", "Gold", 0L)
      .join("u2", "Silver", 10_000L)
      .join("u3", "Gold", 20_000L)
      .leave("u3")
    val filled = session.step(120_000L).approve("u1").approve("u2")
    assertEquals(MatchmakingPhase.FILLED, filled.phase)
    assertEquals(listOf("Gold", "Silver"), filled.strengthTiers())
    assertEquals(2, filled.botSeatsToFill)
  }

  @Test
  fun aDuplicateUidJoinIsANoopAndNeverChangesTier() {
    val session = MatchmakingSession.open("u1", "Gold", 0L)
    val again = session.join("u1", "Silver", 60_000L)
    assertEquals(listOf(WaitingHuman("u1", "Gold")), again.humans)
    assertEquals(MatchmakingPhase.FILLING, again.phase)
  }

  @Test
  fun aBlankUidCannotJoin() {
    val session = MatchmakingSession.open("u1", "Gold", 0L)
    assertEquals(session, session.join("  ", "Gold", 10_000L))
  }
}
