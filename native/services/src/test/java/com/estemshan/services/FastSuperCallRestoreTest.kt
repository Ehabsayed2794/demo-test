package com.estemshan.services

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameType
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.engine.emit
import com.estemshan.engine.initFastRound
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.GameState
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.RoundResultEntry
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.session.GameSessionPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-09 reconnect matrix: the Fast Super Call's live declaration cycle must be
 * rebuildable from the DOCUMENT alone.
 *
 * Fast declarations are never logged (they go through submitBid / bids), so a
 * client that arrives after a Super Call has nothing to replay. The document
 * is the authority: `biddingCycle` counts committed confirmations, the LAST
 * confirmation swept every earlier declaration, and `bids` holds only the
 * active cycle's declarations. [MatchAdapter.restoreFastBiddingCycle] rebuilds
 * that state through the REAL engine, from the same synthesized pre-confirm
 * shape Settlement.replayFastSuperCallChain uses.
 *
 * Every fixture here is DERIVED FROM THE ENGINE, not hand-written: the "live"
 * states are produced by emitting the real intents in the real order, and the
 * documents are then written from those states exactly as MatchService's
 * transactions would. A restore that agrees with them agrees with production.
 */
class FastSuperCallRestoreTest {

  private val seats: List<String> = SEAT_IDS
  private val seatUids: Map<String, String> =
    seats.mapIndexed { i, s -> s to "u${i + 1}" }.toMap()
  private val dealerSeat: String = seats.first()

  // ── hermetic harness (same shape as MatchAdapterReloadReplayTest) ──

  private class FakeGameSession : GameSessionPort {
    var bidding: BiddingState? = null
    private var _turn: String? = null

    override fun getPlayers(): List<String> = SEAT_IDS
    override fun getRoundNumber(): Int = 1
    override fun getMaxRounds(): Int = 18
    override fun getDealer(): String? = SEAT_IDS.first()
    override fun setDealer(seatId: String?) {}
    override fun getTurn(): String? = _turn
    override fun setTurn(seatId: String?) { _turn = seatId }
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

  // ── engine-driven live states ──────────────────────────────────────

  private fun fastStart(
    round: Int = 14,
    gameType: GameType = GameType.FULL,
    multiplier: Int = 1,
  ): BiddingState = initFastRound(
    round = round,
    dealer = dealerSeat,
    seats = seats,
    multiplier = multiplier,
    gameType = gameType,
    scoringMode = ScoringMode.NORMAL,
  )

  private fun applied(result: EmitResult): BiddingState = when (result) {
    is EmitResult.Applied -> result.state
    is EmitResult.Completed -> result.state
    is EmitResult.Rejected -> error("engine rejected: ${result.reason}")
    is EmitResult.GeneralPass -> error("unexpected general pass in a fast round")
  }

  private fun BiddingState.declare(seat: String, tricks: Int): BiddingState {
    assertEquals("$seat declares only on its own turn", seat, waitingFor)
    return applied(emit(this, BiddingIntent.FinalEstimate(seat, tricks)))
  }

  private fun BiddingState.confirm(seat: String, tricks: Int, suit: Suit): BiddingState {
    assertEquals("$seat confirms only on its own turn", seat, waitingFor)
    assertEquals("a Super Call confirm is unconstrained in suit", BiddingPhase.CONFIRM, subPhase)
    return applied(emit(this, BiddingIntent.ConfirmCall(seat, tricks, suit)))
  }

  /**
   * Cycle 1, live: p3 wins the opening Super Call at 10 (CUBS), which wipes
   * the four opening declarations; p4 and p1 re-declare, so the client waits
   * on p2. This is the shape a resumed client finds in the document.
   */
  private fun liveCycleOne(round: Int = 14, gameType: GameType = GameType.FULL): BiddingState {
    var s = fastStart(round, gameType)
    s = s.declare("p1", 8)
    s = s.declare("p2", 9)
    s = s.declare("p3", 10)
    s = s.declare("p4", 7)
    s = s.confirm("p3", 10, Suit.CLUBS)
    s = s.declare("p4", 6)
    s = s.declare("p1", 4)
    assertEquals("the opening cycle's re-declarations stop at p2", "p2", s.waitingFor)
    return s
  }

  /**
   * Cycle 2, live: p2 re-declares 11 over the confirmed 10, so the Super Call
   * re-opens on p2; p2 confirms 11 with a NEW trump (HEARTS); p3 re-declares.
   */
  private fun liveCycleTwo(round: Int = 14): BiddingState {
    var s = liveCycleOne(round)
    s = s.declare("p2", 11)
    assertEquals(BiddingPhase.CONFIRM, s.subPhase)
    assertEquals("the higher declaration re-opens the call on p2", "p2", s.waitingFor)
    assertEquals(11, s.auctionTop)
    s = s.confirm("p2", 11, Suit.HEARTS)
    s = s.declare("p3", 5)
    assertEquals("the second cycle's re-declarations stop at p4", "p4", s.waitingFor)
    return s
  }

  // ── documents written from those live states ───────────────────────

  private fun doc(
    round: Int = 14,
    bids: Map<String, Int?> = emptyMap(),
    log: List<BiddingLogEntry> = emptyList(),
    cycle: Int = 0,
    version: Int = 20,
    gameType: GameType = GameType.FULL,
    multiplier: Int = 1,
  ): MatchDoc = MatchDoc(
    roomId = "room-t09",
    players = seats.map { seatUids.getValue(it) },
    status = MatchDoc.STATUS_STARTING,
    currentRound = round,
    maxRounds = 18,
    extendedRounds = emptyList(),
    dealer = seatUids.getValue(dealerSeat),
    turn = null,
    seats = seatUids,
    version = version,
    biddingOpen = true,
    bids = bids,
    lastBidSeat = null,
    cardLog = emptyList(),
    lastCardSeat = null,
    cardPhase = null,
    biddingLog = log,
    gameState = GameState(initialized = true, dealtRound = round),
    gameType = gameType,
    currentMultiplier = multiplier,
    biddingCycle = cycle,
  )

  private fun confirmEntry(
    seat: String,
    tricks: Int,
    suit: Suit,
    round: Int = 14,
  ) = BiddingLogEntry(
    seatId = seat,
    actionType = BiddingLogEntry.ACTION_CONFIRM_CALL,
    tricks = tricks,
    suit = suit.name,
    round = round,
  )

  /** The document the live states above would have committed. */
  private fun docFor(state: BiddingState, cycle: Int, log: List<BiddingLogEntry>, version: Int = 20): MatchDoc {
    assertEquals("the live state is mid-estimates", BiddingPhase.ESTIMATES, state.subPhase)
    return doc(
      round = state.round,
      bids = state.bids.mapValues { it.value.amount },
      log = log,
      cycle = cycle,
      version = version,
      gameType = state.gameType,
      multiplier = state.roundMultiplier,
    )
  }

  // ── semantic projection: what a restore must reproduce exactly ─────

  /**
   * Everything the rest of the round consumes. `actionHistory` is deliberately
   * excluded: the rebuild synthesizes the confirmation instead of replaying a
   * Dash/Auction history, and after a fast Super Call the history feeds no
   * legality rule (With floors are recomputed from `bids` at completion).
   */
  private data class CycleView(
    val subPhase: BiddingPhase,
    val waitingFor: String?,
    val auctionTop: Int,
    val auctionSuit: Suit?,
    val callerId: String?,
    val declaredTrump: Suit?,
    val bids: Map<String, Bid>,
    val withPlayers: List<String>,
    val fastSuperResolved: Boolean,
    val noSuitConstraint: Boolean,
    val lastBidderId: String?,
    val multiplier: Int,
  )

  private fun BiddingState.cycleView() = CycleView(
    subPhase = subPhase,
    waitingFor = waitingFor,
    auctionTop = auctionTop,
    auctionSuit = auctionSuit,
    callerId = callerId,
    declaredTrump = declaredTrump,
    bids = bids,
    withPlayers = withPlayers,
    fastSuperResolved = fastSuperResolved,
    noSuitConstraint = noSuitConstraint,
    lastBidderId = lastBidderId,
    multiplier = roundMultiplier,
  )

  private fun sessionAt(round: Int = 14, gameType: GameType = GameType.FULL, multiplier: Int = 1):
    FakeGameSession = FakeGameSession().also {
    it.bidding = fastStart(round, gameType, multiplier)
  }

  // ═══════════════════════════════════════════════════════════════════
  // 1. Cold start after one Super Call restores the ACTIVE cycle
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun coldRestoreRebuildsTheActiveCycleAndWaitsForTheNextSeat() {
    val live = liveCycleOne()
    val document = docFor(live, cycle = 1, log = listOf(confirmEntry("p3", 10, Suit.CLUBS)))

    val session = sessionAt()
    val adapter = MatchAdapter(session)
    val outcome = adapter.restoreFastBiddingCycle("match-one", document)

    assertTrue("the active cycle was restored: ${outcome.reason}", outcome.applied)
    assertEquals("APPLIED", outcome.reason)
    assertNull("the cycle is incomplete, so it surfaces no outcome", outcome.outcome)

    val restored = session.bidding!!
    assertEquals(
      "the restored engine is the live engine's own state",
      live.cycleView(), restored.cycleView(),
    )
    // Spelled out for the reader: the caller is LOCKED at the confirmed
    // number, the previous cycle's opening declarations are gone, and both
    // seats that declared before the call (p1) and after it (p4) are present.
    assertEquals(BiddingPhase.ESTIMATES, restored.subPhase)
    assertEquals("p2", restored.waitingFor)
    assertEquals("p3", restored.callerId)
    assertEquals(10, restored.auctionTop)
    assertEquals(Suit.CLUBS, restored.auctionSuit)
    assertEquals(Suit.CLUBS, restored.declaredTrump)
    assertTrue("the Super Call is resolved", restored.fastSuperResolved)
    assertFalse("the synthesized confirm is suit-constrained", restored.noSuitConstraint)
    assertEquals(
      "bids hold ONLY the active cycle (caller's lock + re-declarations)",
      mapOf(
        "p3" to Bid(BidType.TRICKS, 10, Suit.CLUBS),
        "p4" to Bid(BidType.TRICKS, 6, Suit.CLUBS),
        "p1" to Bid(BidType.TRICKS, 4, Suit.CLUBS),
      ),
      restored.bids,
    )
  }

  @Test
  fun coldRestoreUsesNoBiddingLogEntriesFromOtherRounds() {
    val live = liveCycleOne()
    val document = docFor(
      live, cycle = 1,
      log = listOf(
        // The previous round's confirm is still in a client-side log window
        // (the advancement resets it, but a delivery can straddle the reset).
        confirmEntry("p1", 9, Suit.SPADES, round = 13),
        confirmEntry("p3", 10, Suit.CLUBS, round = 14),
      ),
    )

    val session = sessionAt()
    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-round-filter", document)

    assertTrue("the round's own confirm was found: ${outcome.reason}", outcome.applied)
    assertEquals(live.cycleView(), session.bidding!!.cycleView())
  }

  @Test
  fun coldRestoreCarriesTheRoundMultiplier() {
    val live = liveCycleOne()
    val document = docFor(live, cycle = 1, log = listOf(confirmEntry("p3", 10, Suit.CLUBS)))
      .copy(currentMultiplier = 4)

    val session = sessionAt(multiplier = 4)
    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-mult", document)

    assertTrue(outcome.applied)
    assertEquals("the pass ladder's multiplier survives the restore", 4, session.bidding!!.roundMultiplier)
    assertEquals(live.cycleView().copy(multiplier = 4), session.bidding!!.cycleView())
  }

  // ═══════════════════════════════════════════════════════════════════
  // 2. A second Super Call restores ONLY the final cycle
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun coldRestoreAfterASecondSuperCallUsesOnlyTheLastCycle() {
    val live = liveCycleTwo()
    val document = docFor(
      live, cycle = 2,
      log = listOf(
        confirmEntry("p3", 10, Suit.CLUBS),
        confirmEntry("p2", 11, Suit.HEARTS),
      ),
    )

    val session = sessionAt()
    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-two", document)

    assertTrue("the second cycle was restored: ${outcome.reason}", outcome.applied)
    val restored = session.bidding!!
    assertEquals(live.cycleView(), restored.cycleView())
    assertEquals("the new Super Call owns the round", "p2", restored.callerId)
    assertEquals("the final call's number, not the first", 11, restored.auctionTop)
    assertEquals("the replacement trump", Suit.HEARTS, restored.declaredTrump)
    assertEquals("p4", restored.waitingFor)
    assertEquals(
      "the first cycle's declarations were wiped, not merged",
      mapOf("p2" to Bid(BidType.TRICKS, 11, Suit.HEARTS), "p3" to Bid(BidType.TRICKS, 5, Suit.HEARTS)),
      restored.bids,
    )
  }

  // ═══════════════════════════════════════════════════════════════════
  // 3. Idempotency: a repeat delivery never rebuilds twice
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun repeatDeliveryIsAlreadyRestored() {
    val live = liveCycleOne()
    val document = docFor(live, cycle = 1, log = listOf(confirmEntry("p3", 10, Suit.CLUBS)))
    val session = sessionAt()
    val adapter = MatchAdapter(session)

    assertTrue(adapter.restoreFastBiddingCycle("match-repeat", document).applied)
    val afterFirst = session.bidding!!

    val again = adapter.restoreFastBiddingCycle("match-repeat", document)
    assertFalse("a repeated delivery applies nothing", again.applied)
    assertEquals("ALREADY_RESTORED", again.reason)
    assertSameState("the engine state is untouched", afterFirst, session.bidding!!)

    // A NEW cycle is a new key and must restore again.
    val cycleTwoDoc = docFor(
      liveCycleTwo(), cycle = 2,
      log = listOf(confirmEntry("p3", 10, Suit.CLUBS), confirmEntry("p2", 11, Suit.HEARTS)),
    )
    val next = adapter.restoreFastBiddingCycle("match-repeat", cycleTwoDoc)
    assertTrue("the next cycle restores", next.applied)
    assertEquals("p2", session.bidding!!.callerId)
    assertEquals(11, session.bidding!!.auctionTop)
  }

  private fun assertSameState(label: String, expected: BiddingState, actual: BiddingState) {
    assertEquals(label, expected, actual)
  }

  // ═══════════════════════════════════════════════════════════════════
  // 4. Fail-closed shapes: never reinterpret a document that cannot prove
  //    the cycle it claims to be in
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun legacyDocumentWithAConfirmButNoCycleFailsClosed() {
    // The pre-persistence shape: a Super Call happened, but the document
    // never recorded the cycle, so re-declarations could not have been
    // written either. Refuse by name instead of pretending it is cycle 1.
    val document = doc(
      bids = mapOf("p3" to 10),
      log = listOf(confirmEntry("p3", 10, Suit.CLUBS)),
      cycle = 0,
    )
    val session = sessionAt()
    val before = session.bidding!!

    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-legacy", document)

    assertFalse("an unproven cycle is not restored", outcome.applied)
    assertTrue("the caller must refuse to play on it", outcome.desync)
    assertEquals("FAST_CYCLE_UNPERSISTED", outcome.reason)
    assertEquals("the engine is untouched", before, session.bidding!!)
  }

  @Test
  fun logCycleDisagreementFailsClosed() {
    val document = doc(
      bids = mapOf("p2" to 11, "p3" to 5),
      log = listOf(confirmEntry("p3", 10, Suit.CLUBS)),
      cycle = 2,
    )
    val session = sessionAt()

    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-log", document)

    assertFalse(outcome.applied)
    assertTrue(outcome.desync)
    assertEquals("CYCLE_LOG_MISMATCH", outcome.reason)
    assertEquals("the engine stays at the round's fresh start", 13, session.bidding!!.auctionTop)
  }

  @Test
  fun callerSlotWithoutTheConfirmedNumberFailsClosed() {
    // The confirmation transaction writes the caller's own number atomically
    // with the log entry; a document where that slot disagrees cannot have
    // been produced by the live path.
    val document = doc(
      bids = mapOf("p3" to 9),
      log = listOf(confirmEntry("p3", 10, Suit.CLUBS)),
      cycle = 1,
    )
    val session = sessionAt()

    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-bids", document)

    assertFalse(outcome.applied)
    assertTrue(outcome.desync)
    assertEquals("CYCLE_BIDS_MISMATCH", outcome.reason)
  }

  @Test
  fun openingCycleIsLeftToTheOrdinarySeeding() {
    val document = doc(
      bids = mapOf("p1" to 8, "p2" to 9),
      log = emptyList(),
      cycle = 0,
    )
    val session = sessionAt()

    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-open", document)

    assertFalse(outcome.applied)
    assertFalse("no Super Call yet is not a desync", outcome.desync)
    assertEquals("NO_CYCLE_TO_RESTORE", outcome.reason)
  }

  @Test
  fun normalRoundIsNotRestored() {
    val document = doc(
      round = 13,
      bids = mapOf("p1" to 8),
      log = emptyList(),
      cycle = 0,
    )
    val session = FakeGameSession().also {
      it.bidding = com.estemshan.engine.initNormalRound(13, dealerSeat, seats)
    }

    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-normal", document)

    assertFalse(outcome.applied)
    assertFalse(outcome.desync)
    assertEquals("NOT_FAST_ROUND", outcome.reason)
  }

  @Test
  fun miniGameTypeUsesItsOwnFastRoundBoundary() {
    // MINI's fast rounds start at 6, not 14; the restore must follow the
    // DOCUMENT's game type rather than the legacy FULL constant.
    val live = liveCycleOne(round = 6, gameType = GameType.MINI)
    val document = docFor(live, cycle = 1, log = listOf(confirmEntry("p3", 10, Suit.CLUBS, round = 6)))

    val session = sessionAt(round = 6, gameType = GameType.MINI)
    val outcome = MatchAdapter(session).restoreFastBiddingCycle("match-mini", document)

    assertTrue("MINI round 6 is a fast round: ${outcome.reason}", outcome.applied)
    assertEquals(live.cycleView(), session.bidding!!.cycleView())
  }

  // ═══════════════════════════════════════════════════════════════════
  // 5. The restored state is not a look-alike: it CONTINUES identically
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun restoredCycleContinuesExactlyLikeTheLiveCycle() {
    val liveStart = liveCycleTwo()
    val document = docFor(
      liveStart, cycle = 2,
      log = listOf(confirmEntry("p3", 10, Suit.CLUBS), confirmEntry("p2", 11, Suit.HEARTS)),
    )
    val session = sessionAt()
    assertTrue(MatchAdapter(session).restoreFastBiddingCycle("match-continue", document).applied)
    var restored = session.bidding!!

    var live = liveStart
    assertEquals("same state before the next declaration", live.cycleView(), restored.cycleView())

    // The remaining seats re-declare in the engine's own order on BOTH
    // engines; each step must stay identical, including the With set and
    // the completion outcome computed from bids (not from history).
    var liveOutcome: BiddingOutcome? = null
    var restoredOutcome: BiddingOutcome? = null
    for ((seat, value) in listOf("p4" to 3, "p1" to 2)) {
      val liveStep = step(live, BiddingIntent.FinalEstimate(seat, value))
      val restoredStep = step(restored, BiddingIntent.FinalEstimate(seat, value))
      live = liveStep.first
      restored = restoredStep.first
      liveOutcome = liveStep.second ?: liveOutcome
      restoredOutcome = restoredStep.second ?: restoredOutcome
      assertEquals("after $seat declares $value", live.cycleView(), restored.cycleView())
    }

    // p2 declared 11 HEARTS in cycle 2; the remaining seats declared lower
    // numbers, so the live completion and the restored completion must be
    // the same outcome.
    assertEquals(BiddingPhase.DONE, live.subPhase)
    assertEquals(BiddingPhase.DONE, restored.subPhase)
    assertEquals("the completed declaration cycle", liveOutcome, restoredOutcome)
    assertEquals("p2", liveOutcome!!.callerId)
    assertEquals(Suit.HEARTS, liveOutcome.trump)
    assertEquals("nobody matched 11 HEARTS", emptyList<String>(), liveOutcome.withPlayers)
    assertEquals(
      "the final estimates are the active cycle's declarations",
      mapOf("p2" to 11, "p3" to 5, "p4" to 3, "p1" to 2),
      liveOutcome.estimates,
    )
  }

  private fun step(state: BiddingState, intent: BiddingIntent): Pair<BiddingState, BiddingOutcome?> =
    when (val result = emit(state, intent)) {
      is EmitResult.Applied -> result.state to null
      is EmitResult.Completed -> result.state to result.outcome
      is EmitResult.Rejected -> error("engine rejected: ${result.reason}")
      is EmitResult.GeneralPass -> error("unexpected general pass in a fast round")
    }

  // ═══════════════════════════════════════════════════════════════════
  // 6. The live replay path records the cycle it applied (registry bridge)
  // ═══════════════════════════════════════════════════════════════════

  @Test
  fun liveReplayOfTheConfirmMarksTheCycleRestored() {
    // A client that has been following the round locally sits in CONFIRM
    // when the Super Call's own entry is delivered. Replaying it applies the
    // wipe THROUGH THE ENGINE, and it must also record the cycle so the
    // restore does not rebuild the state (and discard the live history).
    var live = fastStart()
    live = live.declare("p1", 8)
    live = live.declare("p2", 9)
    live = live.declare("p3", 10)
    live = live.declare("p4", 7)
    assertEquals(BiddingPhase.CONFIRM, live.subPhase)

    val session = FakeGameSession().also { it.bidding = live }
    val adapter = MatchAdapter(session)
    val document = doc(
      bids = mapOf("p3" to 10),
      log = listOf(confirmEntry("p3", 10, Suit.CLUBS)),
      cycle = 1,
      version = 31,
    )

    val replay = adapter.applyRemoteBiddingAction("match-live", document)
    assertTrue("the confirm replayed through the engine: ${replay.reason}", replay.applied)
    val afterReplay = session.bidding!!
    assertEquals(BiddingPhase.ESTIMATES, afterReplay.subPhase)
    assertEquals("p3", afterReplay.callerId)
    assertEquals(10, afterReplay.auctionTop)
    assertEquals(Suit.CLUBS, afterReplay.declaredTrump)
    assertEquals("p4", afterReplay.waitingFor)
    assertEquals(mapOf("p3" to Bid(BidType.TRICKS, 10, Suit.CLUBS)), afterReplay.bids)

    val restore = adapter.restoreFastBiddingCycle("match-live", document)
    assertFalse("the replay already holds this exact cycle", restore.applied)
    assertEquals("ALREADY_RESTORED", restore.reason)
    assertEquals("the engine still holds the replayed state", afterReplay, session.bidding!!)
  }

  @Test
  fun resetSyncStateForgetsTheRestoredCycle() {
    // A structural reload wipes the registries; the same document must be
    // restorable again (the page fix re-inits the engine before replaying).
    val live = liveCycleOne()
    val document = docFor(live, cycle = 1, log = listOf(confirmEntry("p3", 10, Suit.CLUBS)))
    val session = sessionAt()
    val adapter = MatchAdapter(session)

    assertTrue(adapter.restoreFastBiddingCycle("match-reset", document).applied)
    assertEquals("ALREADY_RESTORED", adapter.restoreFastBiddingCycle("match-reset", document).reason)

    adapter.resetSyncState("match-reset")
    session.bidding = fastStart()

    val afterReset = adapter.restoreFastBiddingCycle("match-reset", document)
    assertTrue("a fresh registry restores from the document again", afterReset.applied)
    assertEquals(live.cycleView(), session.bidding!!.cycleView())
  }

  @Test
  fun twoMatchesKeepIndependentCycleRegistries() {
    val liveOne = liveCycleOne()
    val docOne = docFor(liveOne, cycle = 1, log = listOf(confirmEntry("p3", 10, Suit.CLUBS)))
    val liveTwo = liveCycleTwo()
    val docTwo = docFor(
      liveTwo, cycle = 2,
      log = listOf(confirmEntry("p3", 10, Suit.CLUBS), confirmEntry("p2", 11, Suit.HEARTS)),
    )

    val sessionA = sessionAt()
    val adapterA = MatchAdapter(sessionA)
    val sessionB = sessionAt()
    val adapterB = MatchAdapter(sessionB)

    assertTrue(adapterA.restoreFastBiddingCycle("match-a", docOne).applied)
    assertTrue(adapterB.restoreFastBiddingCycle("match-b", docTwo).applied)

    assertEquals("p3", sessionA.bidding!!.callerId)
    assertEquals("p2", sessionB.bidding!!.callerId)
    assertEquals(10, sessionA.bidding!!.auctionTop)
    assertEquals(11, sessionB.bidding!!.auctionTop)

    // Re-delivering match A's document to adapter B is a DIFFERENT match id:
    // it must restore under its own key, not inherit A's.
    val sessionC = sessionAt()
    assertTrue(MatchAdapter(sessionC).restoreFastBiddingCycle("match-a", docOne).applied)
    assertNotNull(sessionC.bidding)
  }

  // ═══════════════════════════════════════════════════════════════════
  // 7. Real serialization: the document's own field types, parsed and
  //    restored end to end (never a hand-built MatchDoc)
  // ═══════════════════════════════════════════════════════════════════

  /** The raw field map a Firestore snapshot hands to `MatchDoc.fromFields`:
   *  numbers arrive as Long, `bids` holds only the active cycle's seats, and
   *  the confirmation carries the reset's own numbers. */
  private fun rawFields(cycle: Long? = 1L): Map<String, Any?> = buildMap<String, Any?> {
    put("roomId", "room-live")
    put("players", seatUids.values.toList())
    put("status", MatchDoc.STATUS_STARTING)
    put("currentRound", 14L)
    put("maxRounds", 18L)
    put("extendedRounds", emptyList<Long>())
    put("dealer", seatUids.getValue(dealerSeat))
    put("turn", null)
    put("seats", seatUids)
    put("version", 7L)
    put("biddingOpen", true)
    put("bids", mapOf("p3" to 10L))
    put("lastBidSeat", null)
    put("cardLog", emptyList<Map<String, Any?>>())
    put("lastCardSeat", null)
    put("cardPhase", null)
    put("biddingLog", listOf(
      mapOf(
        "seatId" to "p3",
        "actionType" to BiddingLogEntry.ACTION_CONFIRM_CALL,
        "tricks" to 10L,
        "suit" to Suit.CLUBS.name,
        "round" to 14L,
      ),
    ))
    put("gameState", mapOf("initialized" to true, "dealtRound" to 14L))
    put("gameType", GameType.FULL.name)
    put("scoringMode", ScoringMode.NORMAL.name)
    put("currentMultiplier", 1L)
    if (cycle != null) put("biddingCycle", cycle)
  }

  @Test
  fun theStoredCycleParsesAndRestoresEndToEnd() {
    // §6: real serialization — the SAME field types Firestore stores (Long
    // counters, a wiped bids map that omits the other seats) must parse into
    // MatchDoc AND drive the restore to the exact live state. A hand-built
    // MatchDoc would prove neither half.
    val doc = MatchDoc.fromFields(rawFields())!!
    assertEquals(1, doc.biddingCycle)
    assertEquals(mapOf("p3" to 10), doc.bids)
    assertEquals(1, doc.biddingLog.size)

    val session = sessionAt()
    val restored = MatchAdapter(session).restoreFastBiddingCycle("match-parsed", doc)
    assertTrue("a parsed document restores: ${restored.reason}", restored.applied)
    val state = session.bidding!!
    assertEquals(BiddingPhase.ESTIMATES, state.subPhase)
    assertEquals("p4", state.waitingFor)
    assertEquals("p3", state.callerId)
    assertEquals(10, state.auctionTop)
    assertEquals(Suit.CLUBS, state.declaredTrump)
    assertEquals(mapOf("p3" to Bid(BidType.TRICKS, 10, Suit.CLUBS)), state.bids)
  }

  @Test
  fun aParsedLegacyDocumentReadsCycleZeroAndRefusesTheRestore() {
    // The pre-T-09 shape as it exists on disk: a confirm on record but no
    // `biddingCycle` field at all. The parser must read 0 and the restore
    // must refuse by name — never treat the pre-confirm bids as the active
    // cycle's re-declarations.
    val doc = MatchDoc.fromFields(rawFields(cycle = null))!!
    assertEquals(0, doc.biddingCycle)
    val session = sessionAt()
    val before = session.bidding
    val restored = MatchAdapter(session).restoreFastBiddingCycle("match-legacy", doc)
    assertFalse(restored.applied)
    assertEquals("FAST_CYCLE_UNPERSISTED", restored.reason)
    assertTrue(restored.desync)
    assertEquals("the engine is left untouched", before, session.bidding)
  }
}
