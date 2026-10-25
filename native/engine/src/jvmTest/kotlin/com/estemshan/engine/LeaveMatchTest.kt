package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S72 — LeaveMatch domain goldens for the ratified charge register
 * (docs/decisions/OPEN_QUESTIONS.md as of 2026-10-23; rulings of 2026-10-22):
 * explicit leave records 2 Koz in RANKED and UNRANKED and 1 Koz in ROOM
 * (Q14b, confirmed by the A6.12 / Q14b(1) record), Vote Kick records 2 Koz in
 * both votable modes (Q14d(1) — supersedes A5.1's 1 Koz and the repealed
 * 2026-10-16 OPEN-4 option (a) / C1 closure), the 15-timeout rows keep their
 * own charge (Q14b(1)) — 2 Koz in RANKED/UNRANKED and, since Q37 closed
 * O-03d (CANONICAL_RULES.md's "Pointer, 2026-10-23" on the O-03d cell), 1 Koz
 * in ROOM — and a reclaimable return records none.
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
  fun explicitLeaveInRoomRecordsKoz1() {
    assertEquals("leave ROOM = Koz1 (Q14b: ROOM leave = 1 Koz)",
      KozCharge.Koz1, LeaveMatch.decideLeave(MatchMode.ROOM, LeaveReason.EXPLICIT_LEAVE))
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
  fun fifteenTimeoutInRoomRecordsKoz1() {
    assertEquals("15-timeout ROOM = Koz1 (Q37 closes O-03d: 1 Koz, not repealed to zero)",
      KozCharge.Koz1, LeaveMatch.decideLeave(MatchMode.ROOM, LeaveReason.FIFTEEN_TIMEOUT))
  }

  @Test
  fun voteKickRecordsKoz2() {
    assertEquals("kick = Koz2 (Q14d(1): supersedes A5.1's 1 Koz and the repealed C1 closure)",
      KozCharge.Koz2, LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.VOTE_KICK))
  }

  @Test
  fun voteKickInUnrankedRecordsKoz2() {
    // The vote holds in UNRANKED since the 2026-10-17 owner clarification
    // (ROOM is the only excluded mode) — now 2 Koz per Q14d(1).
    assertEquals("kick UNRANKED = Koz2 (Q14d(1))",
      KozCharge.Koz2, LeaveMatch.decideLeave(MatchMode.UNRANKED, LeaveReason.VOTE_KICK))
  }

  @Test
  fun returnedAllowedSeatRecordsNone() {
    assertEquals("returned-allowed = None (no departure)",
      KozCharge.None, LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.RETURNED_ALLOWED))
  }
}
