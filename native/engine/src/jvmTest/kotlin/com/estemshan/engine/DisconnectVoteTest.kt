package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * S74 — the disconnect vote (DEC-DISC-01; closure 2026-10-16): the 30 s window over a live
 * table, majority = 2 of 3 on the remaining humans' ballots, the no-agreement default, and
 * the permanent-replacement charge per mode — UNRANKED = 2 Koz, ROOM = none, RANKED = the
 * entry does not exist. Pure engine, no I/O; the vote document, the reconnect flow and the
 * seat reclaim are :services' shape, never here. The immediate configured-bot takeover and
 * the no-pause-while-open rule are structural in the domain file: it models no seat control
 * and no in-window pause, so every settlement below keeps the seat with the configured bot.
 */
class DisconnectVoteTest {

  // ── the ruled windows (DEC-DISC-01 / closure §2) ──────────────────

  @Test
  fun t16windowsAreTheRuledSeconds() {
    assertEquals(30, DisconnectVote.voteWindowSec)
    assertEquals(90, DisconnectVote.waitWindowSec)
  }

  // ── agreement = majority = 2 of 3 ─────────────────────────────────

  @Test
  fun t16twoOfThreeWinsEitherWay() {
    assertEquals(
      DisconnectVoteStatus.WAIT,
      DisconnectVote.decideVote(
        listOf(DisconnectVoteChoice.WAIT, DisconnectVoteChoice.WAIT, DisconnectVoteChoice.CONTINUE),
      ),
    )
    assertEquals(
      DisconnectVoteStatus.CONTINUE,
      DisconnectVote.decideVote(
        listOf(DisconnectVoteChoice.CONTINUE, DisconnectVoteChoice.CONTINUE, DisconnectVoteChoice.WAIT),
      ),
    )
  }

  @Test
  fun t16unanimousTableSettlesEitherWay() {
    assertEquals(
      DisconnectVoteStatus.WAIT,
      DisconnectVote.decideVote(List(3) { DisconnectVoteChoice.WAIT }),
    )
    assertEquals(
      DisconnectVoteStatus.CONTINUE,
      DisconnectVote.decideVote(List(3) { DisconnectVoteChoice.CONTINUE }),
    )
  }

  @Test
  fun t16abstentionDoesNotBlockAgreement() {
    // The third human may simply not answer — 2 of 3 answers still win.
    assertEquals(
      DisconnectVoteStatus.WAIT,
      DisconnectVote.decideVote(listOf(DisconnectVoteChoice.WAIT, DisconnectVoteChoice.WAIT)),
    )
    assertEquals(
      DisconnectVoteStatus.CONTINUE,
      DisconnectVote.decideVote(listOf(DisconnectVoteChoice.CONTINUE, DisconnectVoteChoice.CONTINUE)),
    )
  }

  @Test
  fun t16oneOneSplitIsNoAgreement() {
    // 1 of 2 is below the ruled majority: continue with the configured bot.
    assertEquals(
      DisconnectVoteStatus.NO_AGREEMENT,
      DisconnectVote.decideVote(listOf(DisconnectVoteChoice.WAIT, DisconnectVoteChoice.CONTINUE)),
    )
  }

  @Test
  fun t16singleOrEmptyBallotIsNoAgreement() {
    assertEquals(
      DisconnectVoteStatus.NO_AGREEMENT,
      DisconnectVote.decideVote(listOf(DisconnectVoteChoice.WAIT)),
    )
    assertEquals(
      DisconnectVoteStatus.NO_AGREEMENT,
      DisconnectVote.decideVote(listOf(DisconnectVoteChoice.CONTINUE)),
    )
    assertEquals(DisconnectVoteStatus.NO_AGREEMENT, DisconnectVote.decideVote(emptyList()))
  }

  @Test
  fun t16aFourthBallotCannotExist() {
    // Four seats, one disconnected: at most three humans, and bots hold no ballot.
    assertThrows(IllegalArgumentException::class.java) {
      DisconnectVote.decideVote(List(4) { DisconnectVoteChoice.WAIT })
    }
  }

  // ── permanent replacement charge (closure §1) ─────────────────────

  @Test
  fun t16unrankedPermanentReplacementChargesTwoKoz() {
    assertEquals(DisconnectVoteCharge.KOZ2, DisconnectVote.permanentCharge(MatchMode.UNRANKED))
  }

  @Test
  fun t16roomPermanentReplacementDrawsNoKoz() {
    assertEquals(DisconnectVoteCharge.NONE, DisconnectVote.permanentCharge(MatchMode.ROOM))
  }

  @Test
  fun t16rankedHasNoEntry() {
    // DEC-DISC-01: the flow exists in ROOM + UNRANKED only — RANKED's entry does not exist.
    assertNull(DisconnectVote.permanentCharge(MatchMode.RANKED))
  }
}
