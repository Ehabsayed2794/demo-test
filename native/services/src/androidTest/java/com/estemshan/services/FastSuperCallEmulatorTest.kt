package com.estemshan.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.GameType
import com.estemshan.engine.Suit
import com.estemshan.engine.nextSeat
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.Reasons
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.model.ServiceException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-09 — the Fast Super Call's live declaration cycle, end to end: four real
 * `MatchService` instances, four signed-in seats, the real `firestore.rules`
 * and the REAL (not faked) Firestore serialization of `bids`, `biddingCycle`
 * and `biddingLog`.
 *
 * WHY THIS SUITE EXISTS. `FastSuperCallRestoreTest` proves the adapter can
 * rebuild a cycle from a document it constructs from engine states, and
 * `TimeoutResolutionTest` proves the resolver replays one. Neither can prove
 * the two halves MEET: that the service's atomic reset is what some real
 * Firestore actually stores, that the rules accept exactly that shape from
 * an authenticated seat (not `withSecurityRulesDisabled`), and that a client
 * which reloads afterwards restores the SAME cycle from those stored bytes.
 * This suite is that meeting point — the brief's "a green unit-test suite
 * alone is not enough if the live document still cannot persist and restore
 * the re-declarations".
 *
 * WHY MINI. The first fast round is the match's own game type's boundary —
 * FULL 14, MINI 6 — and a match cannot start mid-round. MINI's five ordinary
 * rounds put a real, rules-validated round 6 in reach; each of those
 * advances also proves, live, that the next round opens at `biddingCycle` 0
 * with an empty `bids` and a pruned log.
 *
 * WHY NO CARDS. Bidding is the subject. Every test stops at the auction's
 * DONE state; the round's 52 cards are a separate contract the existing
 * four-client suite already covers.
 *
 * INSTRUMENTED ONLY. This needs Device/Emulator hosts (Auth + Firestore),
 * so it runs in CI's instrumented job, never on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class FastSuperCallEmulatorTest {

  private companion object {
    val FAST_ROUND: Int = GameType.MINI.firstFastRound

    /** The opening Super Call's number: the lowest legal call. */
    const val SUPER_CALL = 8

    /** A re-declaration below the call — an ordinary declaration. */
    const val LOW = 2

    /** A re-declaration above the call — starts the NEXT cycle. */
    const val HIGHER_CALL = 9
  }

  @Before
  fun wipeEmulatorsBetweenTests() {
    EmulatorSuite.reset()
  }

  @After
  fun releaseOurFirebaseClients() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    EmulatorSuite.releaseFirestoreClients(context, "-t09_")
  }

  // ── setup: a real MINI match, played up to its first fast round ──────

  private suspend fun miniMatchAtTheFastRound(): ScriptedMatch {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val seating = SEAT_IDS.associateWith { EmulatorSuite.client(context, "t09_$it") }
    val match = ScriptedMatch(seating, seed = 909L)
    match.start(gameType = GameType.MINI)
    for (round in 1 until FAST_ROUND) {
      val report = match.playRound(round)
      assertEquals("round $round advanced to ${round + 1}", round + 1, report.currentRound)
    }
    val beforeDeal = match.standings()
    assertEquals("the match reached its first fast round", FAST_ROUND, beforeDeal.currentRound)
    // The advance is the live proof of the per-round reset: a new round can
    // never inherit the previous round's declared cycle.
    assertEquals("the round advance reset the declaration cycle", 0, beforeDeal.biddingCycle)
    assertTrue("the round advance cleared bids", beforeDeal.bids.values.all { it == null })
    assertTrue("the round advance pruned the bidding log", beforeDeal.biddingLog.isEmpty())
    return match
  }

  /** Deal the fast round and leave its auction open on the dealer. */
  private suspend fun openFastRound(match: ScriptedMatch): MatchDoc {
    val doc = match.dealAndOpenAuction(FAST_ROUND)
    assertEquals("the fast round is dealt", FAST_ROUND, doc.currentRound)
    assertEquals("a fresh round starts in cycle 0", 0, doc.biddingCycle)
    assertTrue(
      "no Super Call before anyone declared",
      confirms(doc).isEmpty(),
    )
    assertEquals("the fast round opens in ESTIMATES", BiddingPhase.ESTIMATES, match.biddingPhase())
    assertTrue("the auction is open", doc.biddingOpen)
    return doc
  }

  /**
   * The opening cycle: the FIRST seat declares [SUPER_CALL], everyone else
   * [LOW]. All four declarations complete the cycle, so the engine resolves
   * the Super Call and waits on its caller. Returns that caller.
   */
  private suspend fun openingDeclarations(match: ScriptedMatch): String {
    val first = match.waitingSeat() ?: error("no opening actor")
    var declared = 0
    while (match.biddingPhase() == BiddingPhase.ESTIMATES) {
      val actor = match.waitingSeat() ?: error("no actor for declaration $declared")
      match.declare(actor, if (declared == 0) SUPER_CALL else LOW)
      declared++
      if (declared > SEAT_IDS.size) error("the opening cycle never resolved")
    }
    assertEquals("every seat declared", SEAT_IDS.size, declared)
    assertEquals("the Super Call waits on a caller", BiddingPhase.CONFIRM, match.biddingPhase())
    val caller = match.waitingSeat() ?: error("CONFIRM with no caller")
    assertEquals("the Super Caller is the seat that declared $SUPER_CALL", first, caller)
    return caller
  }

  /** The seats that must re-declare, in the engine's own counter-clockwise order. */
  private fun redeclarationOrder(caller: String): List<String> =
    generateSequence(nextSeat(SEAT_IDS, caller)) { nextSeat(SEAT_IDS, it) }
      .take(SEAT_IDS.size - 1)
      .toList()

  private fun confirms(doc: MatchDoc): List<BiddingLogEntry> =
    doc.roundBids(doc.currentRound)
      .filter { it.actionType == BiddingLogEntry.ACTION_CONFIRM_CALL }

  // ── what "the same auction" means across four independent engines ────

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
  )

  private fun assertEveryClientAgrees(match: ScriptedMatch, label: String) {
    val views = SEAT_IDS.map { seat ->
      match.biddingState(seat)?.cycleView() ?: error("$seat has no auction state ($label)")
    }
    views.forEachIndexed { i, view ->
      assertEquals("$label: ${SEAT_IDS[i]} holds the same auction as ${SEAT_IDS.first()}", views.first(), view)
    }
  }

  // ════════════════════════════════════════════════════════════════════
  // 1. The live cycle: the atomic reset, its refusals, its restore, and
  //    its completion — all through the real service and the real rules.
  // ════════════════════════════════════════════════════════════════════

  @Test
  fun aFastSuperCallResetsPersistsRestoresAndCompletesTheLiveCycle() = runBlocking {
    val match = miniMatchAtTheFastRound()
    openFastRound(match)

    val caller = openingDeclarations(match)
    val beforeConfirm = match.standings()
    assertEquals(
      "four opening declarations are on the document",
      SEAT_IDS.size,
      beforeConfirm.bids.count { it.value != null },
    )
    assertEquals("no cycle has been confirmed", 0, beforeConfirm.biddingCycle)
    assertFalse("the last declaration closed the opening cycle", beforeConfirm.biddingOpen)
    val confirmVersion = beforeConfirm.version

    // ── the confirm commits the wipe atomically ──────────────────────
    // Sent raw and NOT synced, so the caller's own engine still holds the
    // CONFIRM phase — exactly the state a device is in when its response
    // is lost.
    val committed = match.submitConfirmRaw(caller, SUPER_CALL, Suit.SANS, expectedCycle = 0)
    assertEquals("the confirm commits the next cycle", 1, committed.biddingCycle)

    // A lost response is retried by the same client, still in CONFIRM. It
    // must be reported as applied — never written twice.
    val lostResponse = match.submitConfirmRaw(caller, SUPER_CALL, Suit.SANS, expectedCycle = 0)
    assertEquals("the retry reports the committed cycle", 1, lostResponse.biddingCycle)

    // ── boundary: a legacy client that names NO cycle ────────────────
    // The document has committed a Super Call, so a write that cannot say
    // which cycle its engine verdict came from must be refused — even while
    // this caller's local engine is still in CONFIRM (the lost-response
    // shape). Without the gate it would land in the active cycle.
    val noCycleConfirm = runCatching {
      match.submitConfirmRaw(caller, SUPER_CALL, Suit.SANS, expectedCycle = null)
    }
    val noCycleConfirmError = noCycleConfirm.exceptionOrNull() as? ServiceException
    assertNotNull("a cycle-less confirm must be refused, got $noCycleConfirm", noCycleConfirmError)
    assertEquals(Reasons.STALE_CYCLE, noCycleConfirmError!!.reason)

    val afterRetry = match.standings()
    assertEquals("the confirm is ONE write", confirmVersion + 1, afterRetry.version)
    assertEquals("the retry wrote no second entry", 1, confirms(afterRetry).size)

    val afterConfirm = match.syncFromDocument()
    assertEquals("the document is in cycle 1", 1, afterConfirm.biddingCycle)
    assertEquals(
      "the atomic reset keeps ONLY the caller's confirmed number",
      mapOf(caller to SUPER_CALL),
      afterConfirm.bids.filterValues { it != null },
    )
    assertTrue("the reset re-opens the auction", afterConfirm.biddingOpen)
    assertNull("the reset clears lastBidSeat", afterConfirm.lastBidSeat)
    val entry = confirms(afterConfirm).single()
    assertEquals("the Super Call's own log entry", caller, entry.seatId)
    assertEquals(SUPER_CALL, entry.tricks)
    assertEquals(Suit.SANS.name, entry.suit)

    // ── every engine, live replay or document rebuild, is in the cycle ─
    assertEquals("the confirm re-opens ESTIMATES", BiddingPhase.ESTIMATES, match.biddingPhase())
    assertEquals(
      "declarations resume counter-clockwise from the caller",
      nextSeat(SEAT_IDS, caller),
      match.waitingSeat(),
    )
    assertEveryClientAgrees(match, "after the Super Call")
    val liveCycle = match.biddingState(caller)!!.cycleView()
    assertEquals("the caller is locked at the confirmed number", SUPER_CALL, liveCycle.auctionTop)
    assertEquals("the trump is the confirmed suit", Suit.SANS, liveCycle.declaredTrump)
    assertEquals("the caller's own slot, and nobody else's",
      mapOf(caller to Bid(BidType.TRICKS, SUPER_CALL, Suit.SANS)), liveCycle.bids)
    assertTrue("the call is resolved", liveCycle.fastSuperResolved)

    val order = redeclarationOrder(caller)
    val versionBeforeRefusals = afterConfirm.version

    // ── boundary: a declaration validated against the OLD cycle ──────
    val stale = runCatching { match.submitDeclarationRaw(order[0], LOW, expectedCycle = 0) }
    val staleError = stale.exceptionOrNull() as? ServiceException
    assertNotNull("a stale-cycle declaration must be refused, got $stale", staleError)
    assertEquals(Reasons.STALE_CYCLE, staleError!!.reason)

    // ── boundary: a declaration that names NO cycle after the call ───
    val noCycleDeclaration = runCatching {
      match.submitDeclarationRaw(order[0], LOW, expectedCycle = null)
    }
    val noCycleDeclarationError = noCycleDeclaration.exceptionOrNull() as? ServiceException
    assertNotNull(
      "a cycle-less declaration must be refused, got $noCycleDeclaration",
      noCycleDeclarationError,
    )
    assertEquals(Reasons.STALE_CYCLE, noCycleDeclarationError!!.reason)

    // ── boundary: a COMPETING confirm from a non-caller ──────────────
    val competing = runCatching {
      match.submitConfirmRaw(order[0], SUPER_CALL, Suit.SANS, expectedCycle = 1)
    }
    val competingError = competing.exceptionOrNull() as? ServiceException
    assertNotNull("a non-caller's confirm must be refused, got $competing", competingError)
    assertEquals(Reasons.ILLEGAL_BIDDING_ACTION, competingError!!.reason)

    val afterRefusals = match.standings()
    assertEquals("refusals never bump the version", versionBeforeRefusals, afterRefusals.version)
    assertEquals("refusals never touch bids", afterConfirm.bids, afterRefusals.bids)
    assertEquals("refusals never extend the log", 1, confirms(afterRefusals).size)

    // ── a COLD client rebuilds the cycle from the document alone ─────
    val cold = order[0]
    match.reloadClient(cold)
    assertEveryClientAgrees(match, "after a cold reload")
    assertEquals(
      "the reloaded client rebuilt the ACTIVE cycle from stored bytes",
      liveCycle,
      match.biddingState(cold)!!.cycleView(),
    )

    // ── the re-declarations, each against the document's own cycle ───
    val afterFirstRedeclaration = match.declare(order[0], LOW)
    assertEquals("the re-declaration landed", order[0], afterFirstRedeclaration.lastBidSeat)
    assertEquals("the cycle did not move", 1, afterFirstRedeclaration.biddingCycle)
    assertEquals(
      "the caller plus one re-declaration",
      2,
      afterFirstRedeclaration.bids.count { it.value != null },
    )

    // A duplicate declaration is refused, not merged.
    val duplicate = runCatching {
      match.submitDeclarationRaw(order[0], LOW + 1, expectedCycle = 1)
    }
    val duplicateError = duplicate.exceptionOrNull() as? ServiceException
    assertNotNull("a duplicate declaration must be refused, got $duplicate", duplicateError)
    assertEquals(Reasons.ALREADY_BID, duplicateError!!.reason)

    for (seat in order.drop(1)) {
      match.declare(seat, LOW)
    }

    // ── completion: the FINAL cycle's caller, number and estimates ───
    assertEquals("the re-declarations complete the auction", BiddingPhase.DONE, match.biddingPhase())
    assertEveryClientAgrees(match, "at completion")
    val done = match.biddingState(caller)!!.cycleView()
    assertEquals("the final caller is the Super Caller", caller, done.callerId)
    assertEquals("the final number is the confirmed number", SUPER_CALL, done.auctionTop)
    assertEquals("the final trump", Suit.SANS, done.declaredTrump)
    val finalEstimates = mapOf(
      caller to SUPER_CALL,
      order[0] to LOW,
      order[1] to LOW,
      order[2] to LOW,
    )
    assertEquals(
      "the estimates are the ACTIVE cycle's declarations",
      finalEstimates,
      done.bids.mapValues { it.value.amount },
    )
    assertTrue("nobody matched $SUPER_CALL, so nobody is With", done.withPlayers.isEmpty())

    val outcome = match.auctionOutcome(caller) ?: error("the auction produced no outcome")
    assertEquals(caller, outcome.callerId)
    assertEquals(Suit.SANS, outcome.trump)
    assertEquals("the outcome's estimates are the final cycle's", finalEstimates, outcome.estimates)
    for (seat in SEAT_IDS) {
      assertEquals("$seat completed the same auction", outcome, match.auctionOutcome(seat))
    }

    val finalDoc = match.standings()
    assertEquals("the document never left cycle 1", 1, finalDoc.biddingCycle)
    assertEquals("one Super Call carried the whole round", 1, confirms(finalDoc).size)

    // ── a reload AFTER completion rebuilds the DONE auction ──────────
    match.reloadClient(SEAT_IDS.first())
    assertEquals(
      "the reloaded client is DONE",
      BiddingPhase.DONE,
      match.biddingState(SEAT_IDS.first())!!.subPhase,
    )
    assertEquals(
      "the reloaded client completed the same auction",
      outcome,
      match.auctionOutcome(SEAT_IDS.first()),
    )
  }

  // ════════════════════════════════════════════════════════════════════
  // 2. A higher re-declaration starts the NEXT cycle; only the FINAL
  //    completed cycle owns the round.
  // ════════════════════════════════════════════════════════════════════

  @Test
  fun aHigherSuperCallStartsTheNextCycleAndOnlyTheFinalCycleSettles() = runBlocking {
    val match = miniMatchAtTheFastRound()
    openFastRound(match)

    val firstCaller = openingDeclarations(match)
    match.confirmSuperCall(firstCaller, SUPER_CALL, Suit.SANS)
    val order = redeclarationOrder(firstCaller)

    // Two wiped seats declare under the confirmed call; the LAST one
    // declares HIGHER, which re-opens the Super Call on that seat.
    match.declare(order[0], LOW)
    match.declare(order[1], LOW)
    val beforeSecond = match.declare(order[2], HIGHER_CALL)
    assertEquals("the higher declaration starts no cycle by itself",
      1, beforeSecond.biddingCycle)
    assertEquals("the re-opened call waits on the higher declarer",
      BiddingPhase.CONFIRM, match.biddingPhase())
    assertEquals(order[2], match.waitingSeat())
    assertEquals("the declaration wrote no confirmation", 1, confirms(beforeSecond).size)

    val secondCaller = order[2]
    val afterSecond = match.confirmSuperCall(secondCaller, HIGHER_CALL, Suit.SPADES)
    assertEquals("the second Super Call commits cycle 2", 2, afterSecond.biddingCycle)
    assertEquals(
      "the second reset keeps ONLY the new caller's number",
      mapOf(secondCaller to HIGHER_CALL),
      afterSecond.bids.filterValues { it != null },
    )
    val entries = confirms(afterSecond)
    assertEquals("one confirmation per cycle", 2, entries.size)
    assertEquals(
      "the two calls, in order",
      listOf(SUPER_CALL to Suit.SANS, HIGHER_CALL to Suit.SPADES),
      entries.map { it.tricks to Suit.valueOf(it.suit!!) },
    )
    assertEquals(
      "each confirmation belongs to its own caller",
      listOf(firstCaller, secondCaller),
      entries.map { it.seatId },
    )

    assertEquals("the second call re-opens ESTIMATES", BiddingPhase.ESTIMATES, match.biddingPhase())
    assertEquals(
      "declarations resume from the second caller",
      nextSeat(SEAT_IDS, secondCaller),
      match.waitingSeat(),
    )
    assertEveryClientAgrees(match, "after the second Super Call")
    val secondCycle = match.biddingState(secondCaller)!!.cycleView()
    assertEquals("the second call owns the round", secondCaller, secondCycle.callerId)
    assertEquals("the second call's number", HIGHER_CALL, secondCycle.auctionTop)
    assertEquals("the second call's trump", Suit.SPADES, secondCycle.declaredTrump)
    assertEquals(
      "no first-cycle declaration survives",
      mapOf(secondCaller to Bid(BidType.TRICKS, HIGHER_CALL, Suit.SPADES)),
      secondCycle.bids,
    )

    // The document is the authority for the ACTIVE cycle only.
    match.reloadClient(firstCaller)
    assertEveryClientAgrees(match, "after reloading the first caller mid-cycle")
    assertEquals(
      "the reloaded first caller rebuilt the SECOND cycle, not a merge",
      secondCycle,
      match.biddingState(firstCaller)!!.cycleView(),
    )

    // ── the final cycle completes: 9 SPADES matches, 2s do not ───────
    val finalOrder = redeclarationOrder(secondCaller)
    match.declare(finalOrder[0], HIGHER_CALL)
    match.declare(finalOrder[1], LOW)
    match.declare(finalOrder[2], LOW)

    assertEquals(BiddingPhase.DONE, match.biddingPhase())
    assertEveryClientAgrees(match, "at completion of cycle 2")
    val done = match.biddingState(secondCaller)!!.cycleView()
    assertEquals("the final caller is the second Super Caller", secondCaller, done.callerId)
    assertEquals("the final number", HIGHER_CALL, done.auctionTop)
    assertEquals("the final trump", Suit.SPADES, done.declaredTrump)
    assertEquals(
      "With is the exact final number under the final trump",
      listOf(finalOrder[0]),
      done.withPlayers,
    )
    val outcome = match.auctionOutcome(secondCaller) ?: error("the auction produced no outcome")
    assertEquals(
      "the estimates are the FINAL cycle's",
      mapOf(
        secondCaller to HIGHER_CALL,
        finalOrder[0] to HIGHER_CALL,
        finalOrder[1] to LOW,
        finalOrder[2] to LOW,
      ),
      outcome.estimates,
    )
    assertEquals(listOf(finalOrder[0]), outcome.withPlayers)

    val finalDoc = match.standings()
    assertEquals("the document records the final cycle", 2, finalDoc.biddingCycle)
    assertEquals("both Super Calls are on the log", 2, confirms(finalDoc).size)

    // And a cold client still lands on the same completed auction.
    match.reloadClient(SEAT_IDS.last())
    assertEquals(
      BiddingPhase.DONE,
      match.biddingState(SEAT_IDS.last())!!.subPhase,
    )
    assertEquals(outcome, match.auctionOutcome(SEAT_IDS.last()))
  }
}
