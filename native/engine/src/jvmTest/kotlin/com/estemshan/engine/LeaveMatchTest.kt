package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S72 — LeaveMatch domain goldens for OPEN-4 option (a) (roadmap closure
 * 2026-10-16): explicit leave + 15-timeout record +2 Koz in RANKED and
 * UNRANKED (ROOM excluded), Vote Kick records +1 Koz final, and a reclaimable
 * return records none.
 */
class LeaveMatchTest {

  @Test
  fun explicitLeaveInRankedRecordsKoz2() {
    assertEquals("leave RANKED = Koz2",
      KozCharge.Koz2, LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.EXPLICIT_LEAVE))
  }

  @Test
  fun explicitLeaveInUnrankedRecordsKoz2() {
    assertEquals("leave UNRANKED = Koz2",
      KozCharge.Koz2, LeaveMatch.decideLeave(MatchMode.UNRANKED, LeaveReason.EXPLICIT_LEAVE))
  }

  @Test
  fun explicitLeaveInRoomRecordsNone() {
    assertEquals("leave ROOM = None",
      KozCharge.None, LeaveMatch.decideLeave(MatchMode.ROOM, LeaveReason.EXPLICIT_LEAVE))
  }

  @Test
  fun fifteenTimeoutInRankedRecordsKoz2() {
    assertEquals("15-timeout RANKED = Koz2",
      KozCharge.Koz2, LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.FIFTEEN_TIMEOUT))
  }

  @Test
  fun fifteenTimeoutInUnrankedRecordsKoz2() {
    assertEquals("15-timeout UNRANKED = Koz2",
      KozCharge.Koz2, LeaveMatch.decideLeave(MatchMode.UNRANKED, LeaveReason.FIFTEEN_TIMEOUT))
  }

  @Test
  fun fifteenTimeoutInRoomRecordsNone() {
    assertEquals("15-timeout ROOM = None",
      KozCharge.None, LeaveMatch.decideLeave(MatchMode.ROOM, LeaveReason.FIFTEEN_TIMEOUT))
  }

  @Test
  fun voteKickRecordsKoz1() {
    assertEquals("kick = Koz1 (C1: restores RD18, repeals RD31 doubling)",
      KozCharge.Koz1, LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.VOTE_KICK))
  }

  @Test
  fun voteKickInUnrankedRecordsKoz1() {
    // The vote holds in UNRANKED since the 2026-10-17 owner clarification
    // (ROOM is the only excluded mode) — same 1 Koz, final.
    assertEquals("kick UNRANKED = Koz1",
      KozCharge.Koz1, LeaveMatch.decideLeave(MatchMode.UNRANKED, LeaveReason.VOTE_KICK))
  }

  @Test
  fun returnedAllowedSeatRecordsNone() {
    assertEquals("returned-allowed = None (no departure)",
      KozCharge.None, LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.RETURNED_ALLOWED))
  }
}
