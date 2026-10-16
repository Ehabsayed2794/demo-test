package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.functions.TimeoutResolution.DecisionPhase
import com.estemshan.functions.TimeoutResolution.DecisionWrite
import com.estemshan.functions.TimeoutResolution.Pending
import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S73 Stage 2, C3 — the RD19 takeover's read side and the resolver's clock /
 * marker write.
 *
 * What these cover, in the order the mechanism runs:
 *  - a BORROWED seat is paced at the fixed 2 s, because the configurable timer
 *    is for humans only and a bot keeps its pace whichever path put it in the
 *    seat (A4.2) — while the seat stays absent from the PERMANENT bot union,
 *    which is what keeps it kickable and keeps its Vote Kick slot;
 *  - corrupt takeover state reads as "nobody is borrowed", never as a seat the
 *    bot holds: the fail-closed direction is the player keeping their seat;
 *  - the seat-action cursor is the only reset input, derived from the moves
 *    already on the document, so no client writes anything toward RD19 and no
 *    permission is widened;
 *  - the clock charges each expired window exactly that window's length, resets
 *    when the seat's own committed actions moved, and never lets the resolver's
 *    OWN bot move look like the player having acted — that projection is what
 *    makes "three ignored windows" three instead of zero;
 *  - the takeover lands in the same write as the decision that earned it, with
 *    the clock zeroed (an accumulation at the threshold is one `fromStored`
 *    rejects, so leaving it would kill the seat's clock permanently) and with
 *    every other seat's entry carried over verbatim;
 *  - RD20's separation holds where it actually bites: a borrowed seat's expiries
 *    increment no timeout counter at all.
 *
 * The chain tests keep the DOCUMENT in step with the persisted cursor, because
 * that is what the real flow does — the resolver's move lands on the doc and the
 * cursor written next to it already counts that move. A test that fed a
 * projected cursor against an unchanged document would be asserting the false
 * reset the projection exists to prevent.
 *
 * Deliberately NOT here: the reclaim (C4), the configured tier/personality for
 * borrowed seats (C7 — every decision below is still MEDIUM, which is the
 * Stage-1/2 policy these tests must not silently change), and the client's seat
 * chip (:services' own suite).
 */
class InactiveTakeoverResolutionTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  private val order = listOf("p1", "p2", "p3", "p4")
  private val seats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
  private val players = listOf("u1", "u2", "u3", "u4")

  /** The deadline anchor, in epoch millis — the doc's `updatedAt`. */
  private val anchorMillis = 1_000_000L

  /** Round 14 of a FULL match: the window is 15 + 5 = 20 s, so three ignored
   *  windows overshoot RD19's 50 s and two do not reach it. */
  private val currentRound = 14
  private val humanWindowSeconds = 20

  /** The cursor of a round nobody has committed anything in: the round term alone. */
  private val cursorAtRoundStart = currentRound * 1_000_000L

  private fun gameState(round: Int): Map<String, Any?> =
    mapOf("initialized" to true, "dealtRound" to round)

  /** A marker entry as the document stores it: [InactiveTakeover]'s three fields. */
  private fun takeoverEntry(
    takenOverAt: Any? = 5_000L,
    round: Any? = currentRound.toLong(),
    trick: Any? = 0L,
  ): Map<String, Any?> = linkedMapOf(
    InactiveTakeover.TAKEN_OVER_AT_FIELD to takenOverAt,
    InactiveTakeover.ROUND_AT_TAKEOVER_FIELD to round,
    InactiveTakeover.TRICK_AT_TAKEOVER_FIELD to trick,
  )

  /** A dealt card as the document stores it, for the cursor and the trick stamp. */
  private fun storedCardEntry(seat: String, round: Int): Map<String, Any?> = mapOf(
    "seatId" to seat,
    "card" to mapOf("suit" to "SPADES", "rank" to mapOf("v" to 14, "s" to 1)),
    "round" to round,
  )

  private fun biddingEntry(seat: String, round: Int): Map<String, Any?> = mapOf(
    "seatId" to seat,
    "actionType" to "SubmitDashCallDecision",
    "declaredDashCall" to false,
    "round" to round,
  )

  /**
   * A dealt round-14 fast round, mid-estimate, waiting on p1 (the dealer) with
   * nobody's estimate committed — the same document the S61 suite resolves, so
   * the marker-absent path below is the path that suite already pins.
   */
  private fun fastRoundDoc(
    updatedAt: Long = anchorMillis,
    timerSeconds: Int = 15,
    bids: Map<String, Int?> = emptyMap(),
    cardLog: List<Map<String, Any?>> = emptyList(),
    biddingLog: List<Map<String, Any?>> = emptyList(),
    takeovers: Map<String, Any?> = emptyMap(),
    botSeats: List<String> = emptyList(),
    timeoutRemovedUids: List<String> = emptyList(),
  ): Map<String, Any?> = mutableMapOf(
    "roomId" to "room-1",
    "players" to players,
    "status" to "starting",
    "currentRound" to currentRound,
    "maxRounds" to 18,
    "extendedRounds" to emptyList<Int>(),
    "dealer" to seats.getValue("p1"),
    "turn" to null,
    "seats" to seats,
    "version" to 1,
    "biddingOpen" to true,
    "bids" to linkedMapOf<String, Int?>(*order.map { it to bids[it] }.toTypedArray()),
    "lastBidSeat" to bids.keys.lastOrNull(),
    "cardLog" to cardLog,
    "lastCardSeat" to null,
    "cardPhase" to null,
    "biddingLog" to biddingLog,
    "gameState" to gameState(currentRound),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to "RANKED",
    "botTier" to "MEDIUM",
    "botPersonality" to "BALANCED",
    "decisionTimerSeconds" to timerSeconds,
    "updatedAt" to updatedAt,
    InactiveTakeover.TAKEOVER_FIELD to takeovers,
    "botSeats" to botSeats,
    "timeoutRemovedUids" to timeoutRemovedUids,
  )

  /** The document after the resolver committed p1's estimate — what the cursor
   *  written by that resolution counts. */
  private fun docAfterEstimate(tricks: Int = 6): Map<String, Any?> =
    fastRoundDoc(bids = mapOf("p1" to tricks))

  private fun dealtHands(seed: Long = 11L): Map<String, List<Card>> = Dealer.dealHands(seed)

  /** The private document as this mechanism writes it. */
  private fun inactivityDoc(accumulated: Any?, cursor: Any?): Map<String, Any?> = linkedMapOf(
    InactivityClock.ACCUMULATED_FIELD to accumulated,
    InactivityClock.OPEN_WINDOW_FIELD to null,
    InactiveTakeover.CURSOR_FIELD to cursor,
  )

  /** A bidding-path write (appends a logged action) for the pure tests. */
  private fun biddingWrite(): DecisionWrite = DecisionWrite(
    updates = mapOf("biddingLog" to "arrayUnion(sentinel)"),
    decided = mapOf("seatId" to "p1"),
  )

  /** A card-path write (appends a card) for the pure tests. */
  private fun cardWrite(): DecisionWrite = DecisionWrite(
    updates = mapOf("cardLog" to "arrayUnion(sentinel)"),
    decided = mapOf("seatId" to "p1"),
  )

  /** The write `decideBidding` builds for an estimate: `bids/`, no log entry. */
  private fun estimateWrite(seat: String, tricks: Int): DecisionWrite = DecisionWrite(
    updates = mapOf("bids" to linkedMapOf(seat to tricks)),
    decided = mapOf("seatId" to seat),
  )

  private fun pending(
    phase: DecisionPhase,
    seatId: String,
    durationSeconds: Int,
    anchor: Long = anchorMillis,
  ): Pending = Pending(
    phase = phase,
    seatId = seatId,
    durationSeconds = durationSeconds,
    anchorMillis = anchor,
  )

  private fun humanPending(seatId: String = "p1"): Pending =
    pending(DecisionPhase.BIDDING, seatId, humanWindowSeconds)

  private fun advance(
    fields: Map<String, Any?>,
    pending: Pending,
    seatUid: String?,
    storedDoc: Map<String, Any?>?,
    nowMillis: Long,
    write: DecisionWrite = biddingWrite(),
  ): TimeoutResolution.InactivityUpdate? =
    TimeoutResolution.inactivityAfterExpiredWindow(
      fields = fields,
      pending = pending,
      seatUid = seatUid,
      storedDoc = storedDoc,
      nowMillis = nowMillis,
      write = write,
    )

  // ── the read side: who is borrowed, and what that costs the table ────

  @Test
  fun aBorrowedSeatIsTimedAtTheFixedTwoSeconds() {
    val doc = fastRoundDoc(takeovers = mapOf("p1" to takeoverEntry()))

    val pending = TimeoutResolution.pendingDecision(doc)!!

    assertEquals(DecisionPhase.BIDDING, pending.phase)
    assertEquals("p1", pending.seatId)
    // This document's human window is 15 + 5 = 20 s; a bot in the seat keeps the
    // fixed 2 s whatever the host configured (A4.2).
    assertEquals(TimeoutResolution.BOT_DECISION_SECONDS, pending.durationSeconds)
  }

  @Test
  fun aHumanSeatKeepsTheFullPhaseWindowAndNoMarkerIsNoMarkerAtAll() {
    val doc = fastRoundDoc()

    assertEquals(emptySet<String>(), TimeoutResolution.inactiveTakeoverSeats(doc))
    assertEquals(humanWindowSeconds, TimeoutResolution.pendingDecision(doc)!!.durationSeconds)
  }

  @Test
  fun aBorrowedSeatIsAbsentFromThePermanentBotUnion() {
    val doc = fastRoundDoc(takeovers = mapOf("p1" to takeoverEntry()))

    // The union that drives bot control stays Vote Kick + timeout removal only.
    // Folding a borrowed seat into it would make a still-present player
    // unkickable and unvotable, which is the opposite of DEC-RD19-04.
    assertEquals(setOf("p1"), TimeoutResolution.inactiveTakeoverSeats(doc))
    assertTrue(TimeoutResolution.botControlledSeats(doc).isEmpty())
  }

  @Test
  fun aMalformedMarkerEntryReadsAsNobodyBorrowed() {
    val doc = fastRoundDoc(
      takeovers = mapOf(
        "p1" to "borrowed",
        "p2" to takeoverEntry(),
        "p3" to takeoverEntry(round = 0L),
      ),
    )

    // p1's entry is not a map and p3's round is below 1; both are dropped and
    // p2's survives. p1 keeps its human window: garbage never takes a seat over.
    assertEquals(setOf("p2"), TimeoutResolution.inactiveTakeoverSeats(doc))
    val pending = TimeoutResolution.pendingDecision(doc)!!
    assertEquals("p1", pending.seatId)
    assertEquals(humanWindowSeconds, pending.durationSeconds)
  }

  // ── the cursor: the only reset input, and the move itself is the report ──

  @Test
  fun theCursorCountsThisSeatsOwnActionsInThisRound() {
    val doc = fastRoundDoc(
      cardLog = listOf(
        storedCardEntry("p1", currentRound),
        storedCardEntry("p2", currentRound),
        storedCardEntry("p1", currentRound - 1),
      ),
      biddingLog = listOf(
        biddingEntry("p1", currentRound),
        biddingEntry("p3", currentRound),
      ),
      bids = mapOf("p1" to 7),
    )

    // Round term + one card of THIS round + one logged action + one committed
    // estimate. Another seat's moves and a previous round's do not count, and
    // advanceToNextRound's pruning can never drag the cursor backwards.
    assertEquals(cursorAtRoundStart + 1_002L, TimeoutResolution.seatActionCursor(doc, "p1"))
    assertEquals(cursorAtRoundStart + 1_000L, TimeoutResolution.seatActionCursor(doc, "p2"))
    assertEquals(cursorAtRoundStart + 1L, TimeoutResolution.seatActionCursor(doc, "p3"))
    assertEquals(cursorAtRoundStart, TimeoutResolution.seatActionCursor(doc, "p4"))
  }

  @Test
  fun anUnattributableLogEntryRefusesTheCursor() {
    val cardWithoutRound = fastRoundDoc(cardLog = listOf(mapOf("seatId" to "p2")))
    val actionWithoutSeat = fastRoundDoc(biddingLog = listOf(mapOf("round" to currentRound)))

    // An entry this process cannot attribute might be the seat's own
    // unlooked-for action, so no cursor — and therefore no clock advance and no
    // takeover — is derived from it.
    assertNull(TimeoutResolution.seatActionCursor(cardWithoutRound, "p1"))
    assertNull(TimeoutResolution.seatActionCursor(actionWithoutSeat, "p1"))
    assertNotNull(TimeoutResolution.seatActionCursor(fastRoundDoc(), "p1"))
  }

  @Test
  fun aCommittedEstimateIsPartOfTheCursor() {
    val unanswered = assertNotNull(TimeoutResolution.seatActionCursor(fastRoundDoc(), "p1"))
    val answered = assertNotNull(TimeoutResolution.seatActionCursor(docAfterEstimate(), "p1"))

    // The one reason the estimate is in the count: a fast round's ONLY bidding
    // action is submitBid, which writes bids/{seat} and no log entry, so leaving
    // it out would make an on-time estimate invisible to the reset rule.
    assertEquals(1L, answered - unanswered)
  }

  // ── the advance: accumulate, reset, take over ────────────────────────

  @Test
  fun oneExpiredWindowAccumulatesExactlyItsOwnLength() {
    val update = assertNotNull(
      advance(fastRoundDoc(), humanPending(), "u1", null, anchorMillis + 20_000, estimateWrite("p1", 6)),
    )

    assertEquals(20_000L, update.docFields[InactivityClock.ACCUMULATED_FIELD])
    assertNull(update.docFields[InactivityClock.OPEN_WINDOW_FIELD])
    assertNull(update.takeover)
    // The write is the COMPLETE private document, and nothing else.
    assertEquals(InactiveTakeover.inactivityDocFieldNames(), update.docFields.keys.toList())
    assertEquals(
      cursorAtRoundStart + 1L,
      update.docFields[InactiveTakeover.CURSOR_FIELD],
    )
  }

  @Test
  fun aLateTriggerCannotManufactureInactivity() {
    // Forty seconds past the deadline still charges one window, not forty.
    val update = assertNotNull(
      advance(fastRoundDoc(), humanPending(), "u1", null, anchorMillis + 60_000, estimateWrite("p1", 6)),
    )

    assertEquals(20_000L, update.docFields[InactivityClock.ACCUMULATED_FIELD])
  }

  @Test
  fun twoIgnoredWindowsDoNotYetTakeTheSeatOver() {
    val first = assertNotNull(
      advance(fastRoundDoc(), humanPending(), "u1", null, anchorMillis + 20_000, estimateWrite("p1", 6)),
    )

    val second = assertNotNull(
      advance(
        docAfterEstimate(), humanPending(), "u1", first.docFields,
        anchorMillis + 40_000, estimateWrite("p1", 6),
      ),
    )

    assertEquals(40_000L, second.docFields[InactivityClock.ACCUMULATED_FIELD])
    assertNull(second.takeover)
    // The estimate is already committed, so the second write adds nothing to the
    // cursor the third window will be compared against.
    assertEquals(cursorAtRoundStart + 1L, second.docFields[InactiveTakeover.CURSOR_FIELD])
  }

  @Test
  fun theThirdIgnoredWindowTakesTheSeatOverAndZeroesTheClock() {
    val first = assertNotNull(
      advance(fastRoundDoc(), humanPending(), "u1", null, anchorMillis + 20_000, estimateWrite("p1", 6)),
    )
    val second = assertNotNull(
      advance(
        docAfterEstimate(), humanPending(), "u1", first.docFields,
        anchorMillis + 40_000, estimateWrite("p1", 6),
      ),
    )

    val now = anchorMillis + 60_000
    val third = assertNotNull(
      advance(
        docAfterEstimate(), humanPending(), "u1", second.docFields, now, estimateWrite("p1", 6),
      ),
    )

    val takeover = assertNotNull(third.takeover)
    assertEquals(now, takeover.takenOverAtMillis)
    assertEquals(currentRound, takeover.roundAtTakeover)
    assertEquals(0, takeover.trickAtTakeover)
    // Zeroed, and load-bearing: an accumulation at or above the threshold is one
    // InactivityClock.fromStored rejects, so leaving 60 s on the document would
    // kill this seat's clock forever and prevent any later takeover.
    assertEquals(0L, third.docFields[InactivityClock.ACCUMULATED_FIELD])
  }

  @Test
  fun theBotsOwnDecidedMoveNeverResetsTheSeatsClock() {
    val first = assertNotNull(
      advance(fastRoundDoc(), humanPending(), "u1", null, anchorMillis + 20_000, estimateWrite("p1", 6)),
    )

    // The document the next window is judged against already carries the bot's
    // estimate, and the persisted cursor already counted it: same numbers on
    // both sides means "nothing new happened", not "the player acted".
    val second = assertNotNull(
      advance(
        docAfterEstimate(), humanPending(), "u1", first.docFields,
        anchorMillis + 40_000, estimateWrite("p1", 6),
      ),
    )

    assertEquals(40_000L, second.docFields[InactivityClock.ACCUMULATED_FIELD])
    // Without the projection the streak would reset on every window and RD19
    // could never reach its threshold at all:
    val unpersisted = assertNotNull(
      advance(
        docAfterEstimate(), humanPending(), "u1",
        inactivityDoc(40_000L, cursorAtRoundStart), anchorMillis + 40_000, estimateWrite("p1", 6),
      ),
    )
    assertEquals(0L, unpersisted.docFields[InactivityClock.ACCUMULATED_FIELD])
  }

  @Test
  fun aSeatThatCommittedSomethingSinceTheLastLookResetsTheStreak() {
    val accumulated = inactivityDoc(40_000L, cursorAtRoundStart)
    val acted = fastRoundDoc(biddingLog = listOf(biddingEntry("p1", currentRound)))

    val update = assertNotNull(advance(acted, humanPending(), "u1", accumulated, anchorMillis + 60_000))

    // The document gained an action the resolver had not seen: a genuine
    // interaction inside the player's own window ends the streak (DEC-RD19-01),
    // whatever the elapsed time says.
    assertEquals(0L, update.docFields[InactivityClock.ACCUMULATED_FIELD])
    assertNull(update.takeover)
    assertEquals(cursorAtRoundStart + 2L, update.docFields[InactiveTakeover.CURSOR_FIELD])

    // The same stored clock against a document with no new action does earn the
    // takeover — the reset is the CHANGE, not the elapsed time.
    val silent = assertNotNull(
      advance(fastRoundDoc(), humanPending(), "u1", accumulated, anchorMillis + 60_000),
    )
    assertNotNull(silent.takeover)
    assertEquals(0L, silent.docFields[InactivityClock.ACCUMULATED_FIELD])
  }

  @Test
  fun theTakeoverStampsTheTrickTheDocumentHadBeforeItsOwnCard() {
    val doc = fastRoundDoc(
      cardLog = listOf(
        storedCardEntry("p2", currentRound),
        storedCardEntry("p3", currentRound),
        storedCardEntry("p4", currentRound),
        storedCardEntry("p1", currentRound),
      ),
    )

    val update = assertNotNull(
      advance(
        doc, pending(DecisionPhase.CARD, "p1", 15), "u1",
        inactivityDoc(49_000L, cursorAtRoundStart + 1_000L), anchorMillis + 15_000,
        write = cardWrite(),
      ),
    )

    val takeover = assertNotNull(update.takeover)
    // Four cards of the round are on the document, so the trick in progress at
    // takeover is the second (index 1): reclaim becomes due once THAT trick
    // resolves, not the one the bot's own card may have just completed.
    assertEquals(1, takeover.trickAtTakeover)
    assertEquals(currentRound, takeover.roundAtTakeover)
    assertEquals(cursorAtRoundStart + 2_000L, update.docFields[InactiveTakeover.CURSOR_FIELD])
  }

  // ── fail-closed: the clock never advances on state it cannot trust ───

  @Test
  fun aGarbageInactivityDocumentAdvancesNothing() {
    val doc = fastRoundDoc()

    // Present-but-unparseable accumulation, an accumulation at or above the
    // threshold (state this mechanism never leaves behind), and a cursor that is
    // not a whole number all refuse the write.
    assertNull(advance(doc, humanPending(), "u1",
      mapOf(InactivityClock.ACCUMULATED_FIELD to "forty seconds"), anchorMillis + 20_000))
    assertNull(advance(doc, humanPending(), "u1",
      inactivityDoc(50_000L, cursorAtRoundStart), anchorMillis + 20_000))
    assertNull(advance(doc, humanPending(), "u1",
      inactivityDoc(40_000L, "start of time"), anchorMillis + 20_000))
  }

  @Test
  fun anAlreadyBorrowedSeatAccumulatesNothingFurther() {
    val doc = fastRoundDoc(takeovers = mapOf("p1" to takeoverEntry()))

    // The streak stopped accruing the moment the seat was taken over and reclaim
    // owns restarting it; re-accumulating would re-stamp the takeover forever.
    assertNull(advance(
      doc, pending(DecisionPhase.BIDDING, "p1", 2), "u1",
      inactivityDoc(0L, cursorAtRoundStart), anchorMillis + 2_000,
    ))
  }

  @Test
  fun aPermanentlyBotDrivenSeatAccumulatesNothing() {
    val doc = fastRoundDoc(botSeats = listOf("p1"), timeoutRemovedUids = listOf("u2"))

    assertNull(advance(doc, pending(DecisionPhase.BIDDING, "p1", 2), "u1", null, anchorMillis + 2_000))
  }

  @Test
  fun aSeatNobodyOccupiesHasNoClockToAdvance() {
    assertNull(advance(fastRoundDoc(), humanPending(), null, null, anchorMillis + 20_000))
  }

  @Test
  fun anUnattributableCardLogRefusesTheClockToo() {
    val doc = fastRoundDoc(cardLog = listOf(mapOf("seatId" to "p1")))

    assertNull(advance(doc, humanPending(), "u1", null, anchorMillis + 20_000))
  }

  // ── the orchestrator: the marker write, and RD20's separation ────────

  /**
   * The seam, including the inactivity document the Stage-1/Stage-2 suites' fake
   * does not model. Records every side effect, so a test can prove that a seat
   * with no marker on it produces exactly the match write it produced before S73.
   */
  private class FakeDb(
    private val doc: Map<String, Any?>?,
    private val hands: Map<String, List<Card>>,
    /** The document the transaction sees; defaults to [doc]. */
    private val inTxDoc: Map<String, Any?>? = null,
    private val storedCounts: Map<String, Any?> = emptyMap(),
    /** uid → the private `inactivity/{uid}` document, or absent for none. */
    private val storedInactivity: Map<String, Map<String, Any?>> = emptyMap(),
  ) : TimeoutDb {
    var written: Map<String, Any?>? = null
      private set
    val writtenCounts: MutableMap<String, Any?> = mutableMapOf()
    val writtenInactivity: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    var inactivityReads = 0
      private set

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = doc
    override suspend fun readHands(
      matchId: String,
      round: Int,
      seats: List<String>,
    ): Map<String, List<Card>> = hands.filterKeys { it in seats }

    override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T {
      val txDoc = inTxDoc ?: doc
      return block(object : TimeoutDb.TimeoutTx {
        override suspend fun readMatch(matchId: String): Map<String, Any?>? = txDoc
        override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
          written = updates
        }
        override suspend fun readTimeoutCount(matchId: String, uid: String): Any? =
          storedCounts[uid]
        override suspend fun timeoutCountExists(matchId: String, uid: String): Boolean =
          uid in storedCounts
        override fun writeTimeoutCount(matchId: String, uid: String, count: Int) {
          writtenCounts[uid] = count
        }
        override suspend fun readInactivity(matchId: String, uid: String): Map<String, Any?>? {
          inactivityReads++
          return storedInactivity[uid]
        }
        override fun writeInactivity(matchId: String, uid: String, fields: Map<String, Any?>) {
          writtenInactivity[uid] = fields
        }
      })
    }
  }

  private fun fakeRequest(uid: String?, matchId: String = "m1"): CallableRequest =
    object : CallableRequest {
      // The runtime delivers data as a PLAIN JS OBJECT, never a Kotlin map.
      override val data: Any? = json("matchId" to matchId)
      override val auth: AuthData? = uid?.let {
        object : AuthData {
          override val uid: String = it
          override val token: AuthToken? = null
        }
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }

  private val expiredHumanWindow = DecisionClock { anchorMillis + 20_000 }
  private val expiredBorrowedWindow = DecisionClock { anchorMillis + 2_000 }

  @Suppress("UNCHECKED_CAST")
  private fun markerOf(written: Map<String, Any?>?): Map<String, Any?>? =
    written?.get(InactiveTakeover.TAKEOVER_FIELD) as? Map<String, Any?>

  /** One marker entry as the resolver wrote it — the map the document will hold. */
  @Suppress("UNCHECKED_CAST")
  private fun entryOf(marker: Map<String, Any?>, seatId: String): Map<String, Any?> =
    marker.getValue(seatId) as Map<String, Any?>

  @Test
  fun aFirstIgnoredWindowWritesTheClockButNoMarker() = runTest {
    val db = FakeDb(fastRoundDoc(), dealtHands())

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    assertEquals(true, response["resolved"])
    // The marker stays absent until the threshold, and the match write is the
    // estimate write it was before S73 — no extra key, no changed shape.
    assertTrue(InactiveTakeover.TAKEOVER_FIELD !in db.written!!,
      "the write must not carry the marker field")
    // The strongest form of "marker-absent path byte-identical": the match write
    // for a seat with no marker is exactly submitBid's own shape — these keys,
    // nothing added. The clock goes to its own private document.
    assertEquals(
      setOf("bids", "biddingOpen", "lastBidSeat", "version", "updatedAt"),
      db.written!!.keys,
    )
    val clock = db.writtenInactivity.getValue("u1")
    assertEquals(InactiveTakeover.inactivityDocFieldNames(), clock.keys.toList())
    assertEquals(20_000L, clock[InactivityClock.ACCUMULATED_FIELD])
    // RD20's counter is untouched by the clock: one human timeout, one increment.
    assertEquals(1, db.writtenCounts["u1"])
  }

  @Test
  fun theWindowThatEarnsTheThresholdWritesTheMarkerWithTheDecision() = runTest {
    val db = FakeDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart)),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    assertEquals(true, response["resolved"])
    val marker = assertNotNull(markerOf(db.written))
    val takeover = assertNotNull(InactiveTakeover.parse(marker["p1"]))
    assertEquals(anchorMillis + 20_000, takeover.takenOverAtMillis)
    assertEquals(currentRound, takeover.roundAtTakeover)
    assertEquals(0, takeover.trickAtTakeover)
    assertEquals(
      InactiveTakeover.entryFieldNames(),
      entryOf(marker, "p1").keys.toList(),
    )
    // Zeroed with the marker, in the same transaction as the decision.
    assertEquals(0L, db.writtenInactivity.getValue("u1")[InactivityClock.ACCUMULATED_FIELD])
    // The expiry that EARNED the takeover was a genuine human timeout, so it
    // still counts: RD20 separates the borrowed seat's later expiries, not this
    // one, and the marker written by this transaction is not yet in the read.
    assertEquals(1, db.writtenCounts["u1"])
  }

  @Test
  fun aTakeoverCarriesAnotherSeatsMarkerEntryVerbatim() = runTest {
    val otherSeatEntry = takeoverEntry(takenOverAt = 1_111L, round = 13L, trick = 5L)
    val db = FakeDb(
      doc = fastRoundDoc(takeovers = mapOf("p2" to otherSeatEntry)),
      hands = dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart)),
    )

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    val marker = assertNotNull(markerOf(db.written))
    assertEquals(setOf("p1", "p2"), marker.keys)
    assertNotNull(InactiveTakeover.parse(marker["p1"]))
    // p2's entry is carried, not reinterpreted: this resolver does not own it.
    assertSame(otherSeatEntry, marker["p2"])
  }

  @Test
  fun aBorrowedSeatExpiryIncrementsNoTimeoutCount() = runTest {
    val db = FakeDb(
      doc = fastRoundDoc(takeovers = mapOf("p1" to takeoverEntry())),
      hands = dealtHands(),
      storedCounts = mapOf("u1" to 7),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredBorrowedWindow)

    assertEquals(true, response["resolved"])
    assertEquals(TimeoutResolution.BOT_DECISION_SECONDS, response["deadlineSeconds"])
    // RD20's separation where it actually bites: without this, a 50 s pause
    // would march the player through removal at the fifteenth 2 s window —
    // under thirty seconds of table time.
    assertTrue(db.writtenCounts.isEmpty())
    assertEquals(0, db.inactivityReads)
    assertTrue(db.writtenInactivity.isEmpty())
    assertNull(db.written!!["timeoutRemovedUids"])
    // The marker is not rewritten by an expiry that only consumed it.
    assertNull(markerOf(db.written))
    // C7 owns the configured tier; C3 must not silently change it.
    @Suppress("UNCHECKED_CAST")
    assertEquals("MEDIUM", (response["decided"] as Map<String, Any?>)["botTier"])
  }

  @Test
  fun aGarbageMarkerKeepsTheHumanWindowAndTheCounter() = runTest {
    val db = FakeDb(
      doc = fastRoundDoc(takeovers = mapOf("p1" to "borrowed")),
      hands = dealtHands(),
      storedCounts = mapOf("u1" to 7),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    // Read as not-borrowed: the full window, the counter still advancing, and
    // the clock still accumulating so a genuine takeover overwrites the garbage.
    assertEquals(20, response["deadlineSeconds"])
    assertEquals(8, db.writtenCounts["u1"])
    assertEquals(20_000L, db.writtenInactivity.getValue("u1")[InactivityClock.ACCUMULATED_FIELD])
    assertNull(markerOf(db.written))
  }

  @Test
  fun anUnexpiredWindowWritesNeitherTheClockNorTheMarker() = runTest {
    val db = FakeDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart)),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), DecisionClock { anchorMillis + 19_999 })

    assertEquals(false, response["resolved"])
    assertNull(db.written)
    assertTrue(db.writtenCounts.isEmpty())
    assertTrue(db.writtenInactivity.isEmpty())
  }

  @Test
  fun aRacedDecisionReanchorsAndAdvancesNoClock() = runTest {
    // The player answered between the pre-check and the transaction. The fresh
    // document's own updatedAt re-anchors the deadline, so nothing is written —
    // including the clock, which must not charge a window the player answered.
    val db = FakeDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      inTxDoc = fastRoundDoc(updatedAt = anchorMillis + 30_000),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart)),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), DecisionClock { anchorMillis + 25_000 })

    assertEquals(false, response["resolved"])
    assertNotNull(response["retryAfterMillis"])
    assertNull(db.written)
    assertTrue(db.writtenInactivity.isEmpty())
    assertTrue(db.writtenCounts.isEmpty())
  }

  /** Fails when [key] is present at all, so an absent field cannot be mimicked by a null value. */
  private fun assertFalseKey(key: String, map: Map<String, Any?>) {
    assertTrue(key !in map, "the write must not carry '$key'")
  }
}
