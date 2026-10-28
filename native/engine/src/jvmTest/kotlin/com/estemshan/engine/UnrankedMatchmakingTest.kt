package com.estemshan.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S71 (engine slice) — the rank-blind Unranked pool, pinned:
 * the four-minute window against the Ranked two; the timer anchored to the
 * first player; the boundary exact at 240 000 ms; four humans filling
 * without a bot; a fifth and a duplicate refused; the bot seats counted
 * only after the window closes; the session and the candidate carrying NO
 * rank field at all; the rank-blind pool accepting any two searches while
 * the Ranked band stays RD9's symmetric one tier; and the two pools never
 * pairing with each other. Every time value is a literal parameter, so
 * nothing here reads a clock.
 */
class UnrankedMatchmakingTest {

  private fun candidate(
    uid: String,
    gameType: GameType = GameType.FULL,
    enqueuedAtMillis: Long = 0L,
  ) = UnrankedCandidate(uid, gameType, enqueuedAtMillis)

  // ── the constants ────────────────────────────────────────────────────────

  @Test
  fun theUnrankedWindowIsFourMinutesWhileTheRankedOneStaysTwo() {
    assertEquals(240_000L, UNRANKED_FILL_MILLIS)
    assertEquals(120_000L, MATCHMAKING_FILL_MILLIS)
    assertEquals(4, MATCHMAKING_SEATS)
  }

  // ── the session ──────────────────────────────────────────────────────────

  @Test
  fun theFillTimerIsAnchoredToTheFirstPlayerNotTheLastJoin() {
    val session = UnrankedMatchmakingSession.open("u1", 0L)
      .join("u2", 60_000L)
    assertEquals(240_000L, session.fillDeadlineMillis)
    assertEquals(UnrankedPhase.FILLING, session.step(239_999L).phase)
    assertEquals(UnrankedPhase.FILLED, session.step(240_000L).phase)
  }

  @Test
  fun theWindowClosesExactlyOnTheDeadline() {
    val session = UnrankedMatchmakingSession.open("u1", 0L)
    assertEquals(UnrankedPhase.FILLING, session.step(239_999L).phase)
    val stepped = session.step(240_000L)
    assertEquals(UnrankedPhase.FILLED, stepped.phase)
    assertTrue(stepped.filledWithBots)
    assertEquals(3, stepped.botSeatsToFill)
  }

  @Test
  fun aLateTickCatchesUpInOneCall() {
    val session = UnrankedMatchmakingSession.open("u1", 0L)
    val stepped = session.step(9_999_999L)
    assertEquals(UnrankedPhase.FILLED, stepped.phase)
    assertEquals(3, stepped.botSeatsToFill)
  }

  @Test
  fun fourHumansFillTheTableWithoutBots() {
    val session = UnrankedMatchmakingSession.open("u1", 0L)
      .join("u2", 10L)
      .join("u3", 20L)
      .join("u4", 30L)
    assertEquals(UnrankedPhase.FILLED, session.phase)
    assertFalse(session.filledWithBots)
    assertEquals(0, session.botSeatsToFill)
  }

  @Test
  fun aFifthHumanNeverTakesASeat() {
    val full = UnrankedMatchmakingSession.open("u1", 0L)
      .join("u2", 10L)
      .join("u3", 20L)
      .join("u4", 30L)
    assertEquals(4, full.humans.size)
    val fifth = full.join("u5", 40L)
    assertEquals(4, fifth.humans.size)
    assertSame(full, fifth)
  }

  @Test
  fun aDuplicateUidNeverTakesASecondSeat() {
    val session = UnrankedMatchmakingSession.open("u1", 0L)
    val again = session.join("u1", 10L)
    assertEquals(1, again.humans.size)
    assertSame(session, again)
  }

  @Test
  fun theBotSeatsAreCountedOnlyAfterTheWindowCloses() {
    val session = UnrankedMatchmakingSession.open("u1", 0L).join("u2", 10L)
    assertEquals(0, session.botSeatsToFill)
    val closed = session.step(240_000L)
    assertEquals(2, closed.botSeatsToFill)
    assertTrue(closed.filledWithBots)
  }

  @Test
  fun aLeavingHumanFreesTheSeatWithoutFillingIt() {
    val session = UnrankedMatchmakingSession.open("u1", 0L).join("u2", 10L)
    val left = session.leave("u1")
    assertEquals(1, left.humans.size)
    assertEquals("u2", left.humans.first().uid)
    assertSame(session, session.leave("nobody"))
  }

  @Test
  fun aFilledTableIsTerminal() {
    val filled = UnrankedMatchmakingSession.open("u1", 0L).step(240_000L)
    assertSame(filled, filled.step(500_000L))
    assertSame(filled, filled.join("u2", 500_000L))
  }

  // ── rank-blindness, structurally ────────────────────────────────────────

  @Test
  fun neitherTheSeatedHumanNorTheCandidateHasARankField() {
    val seatedFields = UnrankedWaitingHuman::class.java.declaredFields.map { it.name }.sorted()
    assertEquals(listOf("uid"), seatedFields)
    val candidateFields = UnrankedCandidate::class.java.declaredFields.map { it.name }.sorted()
    assertEquals(listOf("enqueuedAtMillis", "gameType", "uid"), candidateFields)
  }

  // ── the mode-aware pool key ─────────────────────────────────────────────

  @Test
  fun anUnrankedSearchHasNoTierWhateverTheProfileSays() {
    assertEquals(MatchPoolKey.RankBlind, poolKeyOf(MatchMode.UNRANKED, null))
    assertEquals(MatchPoolKey.RankBlind, poolKeyOf(MatchMode.UNRANKED, "King"))
  }

  @Test
  fun aRankedSearchKeepsItsDerivedTierAndARoomHasNoPool() {
    assertEquals(MatchPoolKey.Tiered("Gold"), poolKeyOf(MatchMode.RANKED, "Gold"))
    assertNull(poolKeyOf(MatchMode.ROOM, null))
  }

  @Test
  fun aRankedSearchWithoutATierFailsLoudly() {
    assertThrows(IllegalArgumentException::class.java) {
      poolKeyOf(MatchMode.RANKED, null)
    }
  }

  @Test
  fun theRankBlindPoolAcceptsAnyTwoSearches() {
    assertTrue(isPoolCompatible(MatchPoolKey.RankBlind, MatchPoolKey.RankBlind))
  }

  @Test
  fun theRankedBandIsUnchangedByTheThirdValue() {
    val gold = MatchPoolKey.Tiered("Gold")
    val silver = MatchPoolKey.Tiered("Silver")
    val bronze = MatchPoolKey.Tiered("Bronze")
    val king = MatchPoolKey.Tiered("King")
    val royal = MatchPoolKey.Tiered("Royal")
    assertTrue(isPoolCompatible(gold, silver))
    assertTrue(isPoolCompatible(silver, gold))
    assertTrue(isPoolCompatible(gold, gold))
    assertTrue(isPoolCompatible(king, royal))
    assertFalse("two tiers apart never pair", isPoolCompatible(gold, bronze))
    assertFalse("two tiers apart never pair, either direction", isPoolCompatible(bronze, gold))
  }

  @Test
  fun theTwoPoolsNeverPairWithEachOther() {
    assertFalse(
      isPoolCompatible(MatchPoolKey.Tiered("Gold"), MatchPoolKey.RankBlind),
    )
    assertFalse(
      isPoolCompatible(MatchPoolKey.RankBlind, MatchPoolKey.Tiered("Gold")),
    )
  }

  // ── forming the tables ──────────────────────────────────────────────────

  @Test
  fun fourHumansFormATableImmediatelyWithNoBots() {
    val tables = formUnrankedTables(
      listOf(
        candidate("u1", enqueuedAtMillis = 0L),
        candidate("u2", enqueuedAtMillis = 1_000L),
        candidate("u3", enqueuedAtMillis = 2_000L),
        candidate("u4", enqueuedAtMillis = 3_000L),
      ),
      nowMillis = 3_000L,
    )
    assertEquals(1, tables.size)
    assertEquals(0, tables.first().botSeats)
    assertFalse(tables.first().filledWithBots)
  }

  @Test
  fun aPartTableIsNotFormedWhileItsWindowIsStillOpen() {
    val tables = formUnrankedTables(
      listOf(
        candidate("u1", enqueuedAtMillis = 0L),
        candidate("u2", enqueuedAtMillis = 1_000L),
      ),
      nowMillis = 239_999L,
    )
    assertTrue("the pool keeps offering those seats to any human", tables.isEmpty())
  }

  @Test
  fun aPartTableIsFormedWithBotsOnceItsWindowCloses() {
    val tables = formUnrankedTables(
      listOf(
        candidate("u1", enqueuedAtMillis = 0L),
        candidate("u2", enqueuedAtMillis = 1_000L),
      ),
      nowMillis = 240_000L,
    )
    assertEquals(1, tables.size)
    assertEquals(2, tables.first().botSeats)
    assertTrue(tables.first().filledWithBots)
  }

  @Test
  fun aFullAndAMiniSearcherNeverShareATable() {
    val tables = formUnrankedTables(
      listOf(
        candidate("u1", gameType = GameType.FULL, enqueuedAtMillis = 0L),
        candidate("u2", gameType = GameType.MINI, enqueuedAtMillis = 0L),
      ),
      nowMillis = 240_000L,
    )
    assertEquals("one table per GameType, never a shared one", 2, tables.size)
    assertTrue(tables.all { it.humans.size == 1 })
  }

  @Test
  fun theTableIsFourSeatsAndTheRestGoesToTheBots() {
    val tables = formUnrankedTables(
      listOf(candidate("u1", enqueuedAtMillis = 0L)),
      nowMillis = 240_000L,
    )
    assertEquals(3, tables.first().botSeats)
    assertEquals(4, tables.first().humans.size + tables.first().botSeats)
  }

  @Test
  fun arrivalOrderDecidesWhoSharesTheTable() {
    val tables = formUnrankedTables(
      listOf(
        candidate("u2", enqueuedAtMillis = 1_000L),
        candidate("u1", enqueuedAtMillis = 0L),
      ),
      nowMillis = 240_000L,
    )
    assertEquals(listOf("u1", "u2"), tables.first().humans.map { it.uid })
  }

  @Test
  fun aFifthSearcherStartsTheNextTable() {
    val waiting = listOf(
      candidate("u1", enqueuedAtMillis = 0L),
      candidate("u2", enqueuedAtMillis = 1L),
      candidate("u3", enqueuedAtMillis = 2L),
      candidate("u4", enqueuedAtMillis = 3L),
      candidate("u5", enqueuedAtMillis = 4L),
    )
    val first = formUnrankedTables(waiting, nowMillis = 240_000L)
    assertEquals(1, first.size)
    assertEquals(4, first[0].humans.size)
    assertEquals(0, first[0].botSeats)

    // The fifth searcher's own window is anchored to ITS arrival, so it
    // closes 4 ms after the first four's — it is not swept into their table
    // and it is not left waiting on their deadline.
    val second = formUnrankedTables(waiting, nowMillis = 240_003L)
    assertEquals(1, second.size)
    val third = formUnrankedTables(waiting, nowMillis = 240_004L)
    assertEquals(2, third.size)
    assertEquals(listOf("u5"), third[1].humans.map { it.uid })
    assertEquals(3, third[1].botSeats)
  }
}
