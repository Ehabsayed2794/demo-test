package com.estemshan.services

import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.MAX_DASH_CALLS
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.initNormalRound
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.GameState
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.RoundResultEntry
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.session.GameSessionPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 (owner ruling 2026-10-24, Option A — DECIDED): a mixed Dash-Call +
 * all-pass auction IS a valid General Pass, under the strict two-Dash
 * maximum. The four contract cases, pinned against the pure service proof
 * (the same functions MatchService.advanceAfterGeneralPass applies inside
 * its transaction) and the adapter's replay:
 *
 *  (1) a persisted window claiming THREE declared Dashes is refused with
 *      the NAMED defect TOO_MANY_DASH_CALLS (never a silent clamp);
 *  (2) a third Dash ATTEMPT after two effective ones is persisted as a
 *      decline (normaliseDashCallEntry at the write boundary) and the seat
 *      stays in the auction pool;
 *  (3) two effective Dashes + a pass from every remaining active seat
 *      ACCEPTS and advances (2N-k entries, zero cards);
 *  (4) replay excludes exactly the two effective dashers.
 *
 * Every test here is mutation-sensitive: the companion comments name the
 * single condition each assertion kills.
 */
class DashCallCapGeneralPassTest {

  private val seatUids: Map<String, String> =
    listOf("p1", "p2", "p3", "p4").mapIndexed { i, s -> s to "u${i + 1}" }.toMap()

  private fun match(
    seatMap: Map<String, String> = seatUids,
    round: Int = 3,
    multiplier: Int = 1,
    scoringMode: ScoringMode = ScoringMode.NORMAL,
    biddingLog: List<BiddingLogEntry> = emptyList(),
  ): MatchDoc = MatchDoc(
    roomId = "room-f1",
    players = seatMap.values.toList(),
    status = MatchDoc.STATUS_STARTING,
    currentRound = round,
    maxRounds = 18,
    extendedRounds = emptyList(),
    dealer = seatMap.values.first(),
    turn = null,
    seats = seatMap,
    version = 7,
    biddingOpen = true,
    bids = emptyMap(),
    lastBidSeat = null,
    cardLog = emptyList(),
    lastCardSeat = null,
    cardPhase = null,
    biddingLog = biddingLog,
    gameState = GameState(initialized = true, dealtRound = round),
    scoringMode = scoringMode,
    currentMultiplier = multiplier,
  )

  /** The F1 mixed window: N dash decisions (first k DECLARED), then the
   *  remaining seats' auction passes — total 2N-k entries. */
  private fun mixedWindow(
    seatIds: List<String>,
    round: Int,
    dashers: Set<String>,
    dasherAlsoPasses: String? = null,
    duplicateDashSeat: Boolean = false,
  ): List<BiddingLogEntry> = buildList {
    seatIds.forEachIndexed { i, seat ->
      add(
        BiddingLogEntry(
          seatId = if (duplicateDashSeat && i == 1) seatIds.first() else seat,
          actionType = BiddingLogEntry.ACTION_DASH_CALL,
          declaredDashCall = seat in dashers,
          round = round,
        ),
      )
    }
    seatIds.filter { it !in dashers }.forEach { seat ->
      add(BiddingLogEntry(seat, BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = round))
    }
    if (dasherAlsoPasses != null) {
      // The dasher "passes" too, replacing the final real passer's entry:
      // still N-k passes with distinct seats, but one belongs to a dasher.
      removeAt(size - 1)
      add(
        BiddingLogEntry(dasherAlsoPasses, BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = round),
      )
    }
  }

  // ── case 3 + the k ladder: k = 0/1/2 four-seat windows ACCEPT ────────

  @Test
  fun `k0 k1 k2 four seat windows accept with 2N-k entries and advance the ladder`() {
    val seatIds = seatUids.keys.toList()
    val shapes = listOf(
      0 to 8, // 2N - 0
      1 to 7, // 2N - 1
      2 to 6, // 2N - 2
    )
    for ((k, expectedEntries) in shapes) {
      val dashers = seatIds.take(k).toSet()
      val log = mixedWindow(seatIds, round = 3, dashers = dashers)
      assertEquals("k=$k window must hold exactly 2N-k entries", expectedEntries, log.size)
      val doc = match(biddingLog = log, multiplier = 4)
      assertNull(
        "k=$k window must be canonical (kills the k<=min(2,N-1) cap mutant when k<=2)",
        generalPassWindowDefect(doc, 3),
      )
      val decision = decideGeneralPassAdvance(doc, 3, emptyMap(), emptyList())
      assertEquals(
        "k=$k window must advance and ladder 4 -> 6",
        GeneralPassAdvance.Advance(nextRound = 4, nextMultiplier = 6),
        decision,
      )
    }
  }

  @Test
  fun `k1 window at x1 opens the ladder at x2`() {
    val log = mixedWindow(seatUids.keys.toList(), 3, dashers = setOf("p3"))
    val decision = decideGeneralPassAdvance(match(biddingLog = log, multiplier = 1), 3, emptyMap(), emptyList())
    assertEquals(GeneralPassAdvance.Advance(nextRound = 4, nextMultiplier = 2), decision)
  }

  @Test
  fun `classic mixed window stays at one`() {
    val log = mixedWindow(seatUids.keys.toList(), 3, dashers = setOf("p1", "p4"))
    val decision = decideGeneralPassAdvance(
      match(biddingLog = log, scoringMode = ScoringMode.CLASSIC), 3, emptyMap(), emptyList(),
    )
    assertEquals(GeneralPassAdvance.Advance(nextRound = 4, nextMultiplier = 1), decision)
  }

  // ── case 1: three declared Dashes refuse with the NAMED defect ──────

  @Test
  fun `three declared dashes are refused by name never clamped`() {
    val log = mixedWindow(seatUids.keys.toList(), 3, dashers = setOf("p1", "p2", "p3"))
    assertEquals(5, log.size) // 4 dash decisions + exactly one pass
    val doc = match(biddingLog = log)
    assertEquals(
      "the named defect must fire before any structural guess (kills the count > MAX_DASH_CALLS mutant)",
      GeneralPassWindowDefect.TOO_MANY_DASH_CALLS,
      generalPassWindowDefect(doc, 3),
    )
    assertFalse(isCanonicalGeneralPassWindow(doc, 3))
    assertEquals(
      GeneralPassAdvance.TooManyDashCalls,
      decideGeneralPassAdvance(doc, 3, emptyMap(), emptyList()),
    )
    // The service surfaces its own named reason for this decision branch.
    assertEquals("TOO_MANY_DASH_CALLS", Reasons.TOO_MANY_DASH_CALLS)
  }

  // ── case 2: the write boundary normalises a third Dash ATTEMPT ──────

  @Test
  fun `a third dash attempt persists as a decline once two are effective`() {
    val attempt = BiddingLogEntry(
      seatId = "p4",
      actionType = BiddingLogEntry.ACTION_DASH_CALL,
      declaredDashCall = true,
      round = 3,
    )
    val normalised = normaliseDashCallEntry(attempt, existingEffectiveDashes = MAX_DASH_CALLS)
    assertEquals(
      "over-cap attempt must persist declaredDashCall=false (kills the >= MAX_DASH_CALLS mutant)",
      false,
      normalised.declaredDashCall,
    )
    assertEquals(BiddingLogEntry.ACTION_DASH_CALL, normalised.actionType)
    assertEquals("p4", normalised.seatId)
  }

  @Test
  fun `declared attempts under the cap persist as declared`() {
    for (existing in 0 until MAX_DASH_CALLS) {
      val attempt = BiddingLogEntry("p4", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 3)
      assertEquals(
        "existing=$existing must keep the declaration",
        true,
        normaliseDashCallEntry(attempt, existing).declaredDashCall,
      )
    }
  }

  @Test
  fun `a decline is never flipped to a declaration`() {
    val decline = BiddingLogEntry("p4", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false, round = 3)
    assertEquals(false, normaliseDashCallEntry(decline, existingEffectiveDashes = 0).declaredDashCall)
  }

  @Test
  fun `effectiveDashCallCount reads only the round's declared dashes`() {
    val log = listOf(
      BiddingLogEntry("p1", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 3),
      BiddingLogEntry("p2", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 3),
      BiddingLogEntry("p3", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = false, round = 3),
      BiddingLogEntry("p4", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 2),
      BiddingLogEntry("p4", BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 3),
    )
    assertEquals(
      "round 3 counts p1+p2 only (kills the round-tag filter mutant)",
      2,
      effectiveDashCallCount(log, 3),
    )
    assertEquals(1, effectiveDashCallCount(log, 2))
    assertEquals(0, effectiveDashCallCount(log, 4))
  }

  @Test
  fun `the normalised third attempt keeps its seat in the auction pool`() {
    // The full k=2 window INCLUDING a third attempt by p4 (normalised to a
    // decline at the boundary) still accepts — p4 declined, p4 passes.
    val seatIds = seatUids.keys.toList()
    val log = mixedWindow(seatIds, 3, dashers = setOf("p1", "p2"))
    val thirdAttempt = normaliseDashCallEntry(
      BiddingLogEntry("p4", BiddingLogEntry.ACTION_DASH_CALL, declaredDashCall = true, round = 3),
      effectiveDashCallCount(log, 3),
    )
    assertEquals(false, thirdAttempt.declaredDashCall)
    assertNull(generalPassWindowDefect(match(biddingLog = log), 3))
  }

  // ── under-four tables: N=2 admits k<=1, N=3 admits k<=2 ─────────────

  @Test
  fun `two seat table admits one declared dash`() {
    val two = mapOf("p1" to "u1", "p2" to "u2")
    val log = mixedWindow(two.keys.toList(), 3, dashers = setOf("p2"))
    assertEquals(3, log.size) // 2N - k = 3
    assertNull(generalPassWindowDefect(match(seatMap = two, biddingLog = log), 3))
  }

  @Test
  fun `two seat all dash table is sans not a general pass`() {
    val two = mapOf("p1" to "u1", "p2" to "u2")
    val log = mixedWindow(two.keys.toList(), 3, dashers = setOf("p1", "p2"))
    assertEquals(
      "k = N is the SANS shape (kills the k <= min(2, N-1) mutant)",
      GeneralPassWindowDefect.NOT_A_CLOSED_ALL_PASS_WINDOW,
      generalPassWindowDefect(match(seatMap = two, biddingLog = log), 3),
    )
  }

  @Test
  fun `three seat table admits two declared dashes`() {
    val three = mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3")
    val log = mixedWindow(three.keys.toList(), 3, dashers = setOf("p1", "p3"))
    assertEquals(4, log.size) // 2N - k = 4
    assertNull(generalPassWindowDefect(match(seatMap = three, biddingLog = log), 3))
  }

  @Test
  fun `three declared dashes on a three seat table refuse by name`() {
    val three = mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3")
    val log = mixedWindow(three.keys.toList(), 3, dashers = setOf("p1", "p2", "p3"))
    assertEquals(
      GeneralPassWindowDefect.TOO_MANY_DASH_CALLS,
      generalPassWindowDefect(match(seatMap = three, biddingLog = log), 3),
    )
  }

  // ── dasher-pass and duplicate seats refuse ──────────────────────────

  @Test
  fun `a pass from a declared dasher's seat is refused`() {
    val log = mixedWindow(seatUids.keys.toList(), 3, dashers = setOf("p1"), dasherAlsoPasses = "p1")
    assertEquals(7, log.size) // still 2N-k with k=1
    assertEquals(
      "the dasher's pass must break the window (kills the passBlock-none-in-dasherSeats mutant)",
      GeneralPassWindowDefect.NOT_A_CLOSED_ALL_PASS_WINDOW,
      generalPassWindowDefect(match(biddingLog = log), 3),
    )
  }

  @Test
  fun `a duplicate seat in the dash block is refused`() {
    val log = mixedWindow(seatUids.keys.toList(), 3, dashers = setOf("p1"), duplicateDashSeat = true)
    assertEquals(
      "two dash entries from one seat must break the bijection",
      GeneralPassWindowDefect.NOT_A_CLOSED_ALL_PASS_WINDOW,
      generalPassWindowDefect(match(biddingLog = log), 3),
    )
  }

  @Test
  fun `a duplicate seat in the pass block is refused`() {
    val log = mixedWindow(seatUids.keys.toList(), 3, dashers = setOf("p1")).let {
      it.dropLast(1) + BiddingLogEntry("p2", BiddingLogEntry.ACTION_AUCTION_BID, isPass = true, round = 3)
    }
    assertEquals(
      "two passes from one seat must break the bijection",
      GeneralPassWindowDefect.NOT_A_CLOSED_ALL_PASS_WINDOW,
      generalPassWindowDefect(match(biddingLog = log), 3),
    )
  }

  @Test
  fun `a mixed window riding played cards is refused`() {
    val log = mixedWindow(seatUids.keys.toList(), 3, dashers = setOf("p1", "p2"))
    val doc = match(biddingLog = log).copy(
      cardLog = listOf(
        com.estemshan.services.model.CardLogEntry(
          seatId = "p1",
          card = com.estemshan.services.model.StoredCard(
            "HEARTS", com.estemshan.services.model.StoredRank(14, "A"),
          ),
          round = 3,
        ),
      ),
    )
    assertEquals(
      "zero committed cards is part of the contract (kills the roundCardCount mutant)",
      GeneralPassWindowDefect.NOT_A_CLOSED_ALL_PASS_WINDOW,
      generalPassWindowDefect(doc, 3),
    )
  }

  // ── case 4: the replay excludes exactly the two effective dashers ───

  /** The minimal session the adapter's replay path needs (same double as
   *  MatchAdapterReloadReplayTest's, scoped to this file). */
  private class ReplaySession : GameSessionPort {
    var bidding: BiddingState? = null
    override fun getPlayers(): List<String> = SEAT_IDS
    override fun getRoundNumber(): Int = 1
    override fun getMaxRounds(): Int = 18
    override fun getDealer(): String? = SEAT_IDS.first()
    override fun setDealer(seatId: String?) {}
    override fun getTurn(): String? = null
    override fun setTurn(seatId: String?) {}
    override fun nextRound(): Int = 2
    override fun getPlayState() = null
    override fun updatePlayState(state: com.estemshan.engine.TableState) {}
    override fun getBiddingState(): BiddingState? = bidding
    override fun updateBiddingState(state: BiddingState) { bidding = state }
    override fun getMatchScores(): Map<String, Int> = emptyMap()
    override fun setMatchScores(scores: Map<String, Int>) {}
    override fun recordRoundResult(entry: RoundResultEntry) {}
    override fun getLastRoundResult(): RoundResultEntry? = null
    override fun setWinnerIds(ids: List<String>) {}
    override fun getWinnerIds(): List<String> = emptyList()
    override fun isMatchComplete(): Boolean = false
  }

  @Test
  fun `replay excludes exactly the two effective dashers`() {
    val session = ReplaySession()
    session.bidding = initNormalRound(1, "p1", SEAT_IDS)
    val adapter = MatchAdapter(session)
    val dashers = setOf("p2", "p4")
    val log = mixedWindow(SEAT_IDS, 1, dashers = dashers)
    assertEquals(6, log.size)
    val doc = match(biddingLog = log, round = 1)

    val outcome = adapter.applyRemoteBiddingAction("match-f1-mixed", doc)

    assertTrue("the mixed window must replay legally", outcome.applied)
    assertEquals(
      "the pass must surface the next multiplier (kills the GeneralPass surfacing mutant)",
      2,
      outcome.generalPassMultiplier,
    )
    assertNull("a general pass closes no auction", outcome.outcome)
    val replayed = session.bidding!!
    assertEquals(
      "the cancelled round keeps its own phase (the engine's contract)",
      BiddingPhase.AUCTION,
      replayed.subPhase,
    )
    assertNull("the cancelled round waits on nobody", replayed.waitingFor)
    val dashBids = replayed.bids.filterValues { it.type == BidType.DASHCALL }
    assertEquals(
      "exactly the two declared seats hold DASHCALL bids — every other seat replayed back into the auction and passed (kills the engine-cap replay mutant)",
      dashers,
      dashBids.keys.toSet(),
    )
    assertTrue("the auction pool is empty at the pass", replayed.activeBidders.isEmpty())
  }
}
