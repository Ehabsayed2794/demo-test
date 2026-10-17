package com.estemshan.functions

import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.SeatControl
import com.estemshan.engine.storedSeatControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S73 Stage 2, C4 — DEC-RD19-04's return of control, as the pure decision.
 *
 * These cover the bound in the order the mechanism has to answer it: the trick
 * in progress when the seat was taken must finish; the seat comes back to the
 * SAME seat, which the caller never names; a seat nobody borrowed and a seat the
 * table made permanently bot-driven are not reclaimable; and every answer that is
 * not a return writes nothing. The retry bound is asserted against the document's
 * own server deadline, because the caller re-arms on it and on nothing else.
 *
 * The documents are kept internally consistent on purpose — a marker whose trick
 * has advanced is only ever paired with a card log that says so, and a document
 * that is mid-estimate carries no played cards. A fixture that disagreed with
 * itself would prove the bound on a state the table cannot be in.
 *
 * Deliberately NOT here: the transaction that makes the two writes atomic and the
 * response shape (SeatReclaimOnceTest), the throw paths, which are JS interop and
 * belong to the emulator suite exactly as CallableAuthTest documents, the
 * configured tier of the bot that held the seat (C7), and the client's bounded
 * poll (C5/C6).
 */
class SeatReclaimTest {

  private companion object {
    val order = listOf("p1", "p2", "p3", "p4")
    val seats: Map<String, String> =
      linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
    val players = listOf("u1", "u2", "u3", "u4")

    /** The deadline anchor, in epoch millis — the doc's `updatedAt`. */
    const val ANCHOR_MILLIS = 1_000_000L

    /** Round 14 of a FULL match: the human window is 15 + 5 = 20 s, and one
     *  borrowed seat's window is the fixed 2 s. */
    const val CURRENT_ROUND = 14
    const val HUMAN_WINDOW_MILLIS = 20_000L

    /** One bot window — the floor of every retry this mechanism can give. */
    const val BOT_WINDOW_MILLIS = 2_000L

    /** Cards one trick consumes, and all a round does. */
    const val CARDS_PER_TRICK = 4
    const val CARDS_IN_FULL_ROUND = 16
  }

  /** A marker entry as the document stores it. */
  private fun entry(
    takenOverAt: Any? = 5_000L,
    round: Any? = CURRENT_ROUND.toLong(),
    trick: Any? = 0L,
  ): Map<String, Any?> = linkedMapOf(
    InactiveTakeover.TAKEN_OVER_AT_FIELD to takenOverAt,
    InactiveTakeover.ROUND_AT_TAKEOVER_FIELD to round,
    InactiveTakeover.TRICK_AT_TAKEOVER_FIELD to trick,
  )

  private fun storedCard(seat: String, round: Int): Map<String, Any?> = mapOf(
    "seatId" to seat,
    "card" to mapOf("suit" to "SPADES", "rank" to mapOf("v" to 14, "s" to 1)),
    "round" to round,
  )

  /**
   * The shared shape: a dealt round-14 match. [biddingOpen] keeps the document
   * honest about which phase it describes — a document with cards played is no
   * longer bidding, and its turn belongs to whoever the last card left it to.
   */
  private fun doc(
    bids: Map<String, Int?> = emptyMap(),
    cardLog: List<Map<String, Any?>> = emptyList(),
    biddingLog: List<Map<String, Any?>> = emptyList(),
    takeovers: Map<String, Any?> = emptyMap(),
    botSeats: List<String> = emptyList(),
    timeoutRemovedUids: List<String> = emptyList(),
    updatedAt: Long = ANCHOR_MILLIS,
    round: Int = CURRENT_ROUND,
    biddingOpen: Boolean = true,
    cardPhase: String? = null,
    turn: String? = null,
    members: List<String> = players,
  ): Map<String, Any?> = mutableMapOf<String, Any?>(
    "roomId" to "room-1",
    "players" to members,
    "status" to "playing",
    "currentRound" to round,
    "maxRounds" to 18,
    "dealer" to seats.getValue("p1"),
    "turn" to turn,
    "seats" to seats,
    "version" to 7,
    "biddingOpen" to biddingOpen,
    "bids" to linkedMapOf<String, Int?>(*order.map { it to bids[it] }.toTypedArray()),
    "lastBidSeat" to bids.keys.lastOrNull(),
    "cardLog" to cardLog,
    "lastCardSeat" to cardLog.lastOrNull()?.get("seatId"),
    "cardPhase" to cardPhase,
    "biddingLog" to biddingLog,
    "gameState" to mapOf("initialized" to true, "dealtRound" to round),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to "RANKED",
    "botTier" to "MEDIUM",
    "botPersonality" to "BALANCED",
    "decisionTimerSeconds" to 15,
    "updatedAt" to updatedAt,
    InactiveTakeover.TAKEOVER_FIELD to takeovers,
    "botSeats" to botSeats,
    "timeoutRemovedUids" to timeoutRemovedUids,
  )

  /** Mid-estimate, nothing played: the auction waits on p1 until its estimate is
   *  committed, and then on p2. */
  private fun biddingDoc(
    bids: Map<String, Int?> = emptyMap(),
    takeovers: Map<String, Any?> = mapOf("p1" to entry()),
    botSeats: List<String> = emptyList(),
    timeoutRemovedUids: List<String> = emptyList(),
    round: Int = CURRENT_ROUND,
    members: List<String> = players,
  ): Map<String, Any?> = doc(
    bids = bids,
    takeovers = takeovers,
    botSeats = botSeats,
    timeoutRemovedUids = timeoutRemovedUids,
    round = round,
    members = members,
  )

  /** Mid-card-play: [cards] cards of the current round are on the document in
   *  seat order, p2 holds the turn, and p1's seat was taken over at the trick
   *  named by [trickAtTakeover]. */
  private fun playDoc(
    cards: Int,
    trickAtTakeover: Long = 0L,
    roundAtTakeover: Long = CURRENT_ROUND.toLong(),
  ): Map<String, Any?> = doc(
    bids = order.associateWith { 6 },
    cardLog = (0 until cards).map { storedCard(order[it % order.size], CURRENT_ROUND) },
    takeovers = mapOf("p1" to entry(round = roundAtTakeover, trick = trickAtTakeover)),
    biddingOpen = false,
    cardPhase = "PLAY",
    turn = seats.getValue("p2"),
  )

  private fun reclaim(
    fields: Map<String, Any?>,
    uid: String = "u1",
    nowMillis: Long = ANCHOR_MILLIS + 30_000,
  ): SeatReclaim.Decision = SeatReclaim.decide(fields, uid, nowMillis)

  private fun noWriteOf(decision: SeatReclaim.Decision): SeatReclaim.Decision.NoWrite {
    assertTrue(decision is SeatReclaim.Decision.NoWrite, "expected a no-write, was $decision")
    return decision
  }

  private fun reclaimOf(decision: SeatReclaim.Decision): SeatReclaim.Write {
    assertTrue(decision is SeatReclaim.Decision.Reclaim, "expected control to return")
    return decision.write
  }

  // ── the bound: the trick in progress at takeover has to finish ─────────

  @Test
  fun theTrickInProgressAtTakeoverMustFinishBeforeControlReturns() {
    // Three of the four cards of the opening trick are down; the bot owes the
    // fourth, and that is the half of DEC-RD19-04 that keeps the trick honest.
    val owed = noWriteOf(reclaim(playDoc(cards = 3)))

    assertEquals(SeatReclaim.Reason.TRICK_NOT_FINISHED, owed.reason)
    assertTrue(owed.retryAfterMillis != null, "a retry has to be named, or the poll stops")
  }

  @Test
  fun controlReturnsTheMomentThatTrickIsDown() {
    val write = reclaimOf(reclaim(playDoc(cards = CARDS_PER_TRICK)))

    assertEquals("p1", write.seatId)
    assertTrue("p1" !in write.marker, "the returning seat's entry must be gone")
  }

  @Test
  fun aTakeoverStruckMidTrickIsBoundedByThatTrickCompleting() {
    // The takeover happened after the first card of trick 1, so trick 1 finishing
    // — not the next one starting — is the bound.
    val stillThatTrick = noWriteOf(reclaim(playDoc(cards = 5, trickAtTakeover = 1L)))
    val nextTrickBegun = reclaimOf(reclaim(playDoc(cards = 8, trickAtTakeover = 1L)))

    assertEquals(SeatReclaim.Reason.TRICK_NOT_FINISHED, stillThatTrick.reason)
    assertEquals("p1", nextTrickBegun.seatId)
  }

  @Test
  fun aRoundThatAdvancedWhileTheSeatWasBorrowedHasFinishedItsTrick() {
    // advanceToNextRound prunes both logs, so the new round has no cards and its
    // trick index is 0 — yet the key still moved past round 13's trick 7.
    val write = reclaimOf(reclaim(biddingDoc(takeovers = mapOf("p1" to entry(round = 13L, trick = 7L)))))

    assertEquals("p1", write.seatId)
  }

  @Test
  fun anAuctionTakeoverIsBoundedByTheFirstTrickOfThatRound() {
    // Trick 0 while the auction was open reads as "the opening trick resolves".
    val nothingPlayed = noWriteOf(reclaim(playDoc(cards = 0)))
    val openingTrickDown = reclaimOf(reclaim(playDoc(cards = CARDS_PER_TRICK)))

    assertEquals(SeatReclaim.Reason.TRICK_NOT_FINISHED, nothingPlayed.reason)
    assertEquals("p1", openingTrickDown.seatId)
  }

  // ── the two writes ───────────────────────────────────────────────────

  @Test
  fun theWrittenClockIsTheFreshOneSeatControlReclaimYields() {
    val fields = playDoc(cards = CARDS_PER_TRICK)
    val write = reclaimOf(reclaim(fields))
    val control = storedSeatControl(
      fields[InactiveTakeover.TAKEOVER_FIELD], "p1", CURRENT_ROUND,
      InactiveTakeover.trickNo(CARDS_PER_TRICK),
    )

    // The engine's own rule, pinned beside this write: a finished trick hands
    // back a Human seat with a zeroed clock, and these are that clock's fields.
    assertEquals(SeatControl.Human(), control.reclaim())
    assertEquals(0L, write.inactivityFields[InactivityClock.ACCUMULATED_FIELD])
    assertEquals(null, write.inactivityFields[InactivityClock.OPEN_WINDOW_FIELD])
    assertEquals(InactiveTakeover.inactivityDocFieldNames(), write.inactivityFields.keys.toList())
  }

  @Test
  fun theBaselineCursorCountsTheBotsOwnMovesDuringTheBorrow() {
    val fields = playDoc(cards = CARDS_PER_TRICK)
    val write = reclaimOf(reclaim(fields))

    // p1 played one of those four cards and committed an estimate: round term +
    // one card + one committed bid. Recorded NOW, so the pre-takeover value can
    // never read as a change the player caused on the way back in.
    assertEquals(
      CURRENT_ROUND * 1_000_000L + 1_000L + 1L,
      write.inactivityFields[InactiveTakeover.CURSOR_FIELD],
    )
    assertEquals(
      TimeoutResolution.seatActionCursor(fields, "p1"),
      write.inactivityFields[InactiveTakeover.CURSOR_FIELD],
    )
  }

  @Test
  fun anUnattributableActionLogLeavesNoBaselineAndStillReturnsTheSeat() {
    val fields = playDoc(cards = CARDS_PER_TRICK).toMutableMap().apply {
      // An entry this process cannot attribute: it might be p1's own move.
      this["biddingLog"] = listOf(mapOf("actionType" to "SubmitAuctionBid", "round" to CURRENT_ROUND))
    }
    val write = reclaimOf(reclaim(fields))

    // No baseline is recorded rather than a guessed one, and the clock still
    // zeroes: the seat comes back either way.
    assertNullFields(write)
    assertEquals(InactivityClock.storedFieldNames(), write.inactivityFields.keys.toList())
  }

  private fun assertNullFields(write: SeatReclaim.Write) {
    assertEquals(0L, write.inactivityFields[InactivityClock.ACCUMULATED_FIELD])
    assertEquals(null, write.inactivityFields[InactivityClock.OPEN_WINDOW_FIELD])
  }

  @Test
  fun theRemovalCarriesEveryOtherSeatsEntryVerbatim() {
    val other = entry(round = CURRENT_ROUND.toLong(), trick = 3L)
    val fields = biddingDoc(takeovers = mapOf("p1" to entry(round = 13L, trick = 7L), "p2" to other))
    val write = reclaimOf(reclaim(fields))

    // Surgical: this reclaim owns p1's entry and nobody else's state.
    assertEquals(setOf("p2"), write.marker.keys)
    assertSame(other, write.marker["p2"])
  }

  // ── the refusals: nothing is written, and nobody is stranded ──────────

  @Test
  fun aSeatNobodyBorrowedIsNotReclaimable() {
    val denial = noWriteOf(reclaim(biddingDoc(takeovers = emptyMap())))

    assertEquals(SeatReclaim.Reason.NOT_BORROWED, denial.reason)
    assertEquals(null, denial.retryAfterMillis)
  }

  @Test
  fun aGarbageMarkerEntryIsNotABorrow() {
    // Corrupt takeover state reads as the human holding their own seat — the
    // fail-closed direction. Nothing is written, and the seat's next real
    // accumulation overwrites the garbage with a well-formed entry.
    val denial = noWriteOf(reclaim(biddingDoc(takeovers = mapOf("p1" to "borrowed"))))

    assertEquals(SeatReclaim.Reason.NOT_BORROWED, denial.reason)
  }

  @Test
  fun aCallerWhoOccupiesNoSeatIsNotTheOwner() {
    val denial = noWriteOf(reclaim(biddingDoc(members = players + "u5"), uid = "u5"))

    assertEquals(SeatReclaim.Reason.NOT_OWNER, denial.reason)
  }

  @Test
  fun aSeatTheTableMadePermanentlyBotDrivenIsNotReturned() {
    val kicked = noWriteOf(reclaim(biddingDoc(botSeats = listOf("p1"))))
    val removed = noWriteOf(reclaim(biddingDoc(timeoutRemovedUids = listOf("u1"))))

    // Vote Kick's botSeats and S61's 15-timeout removal are not borrowings, so
    // there is nothing for DEC-RD19-04 to return — and a player the table removed
    // must not be able to reclaim past that.
    assertEquals(SeatReclaim.Reason.NOT_OWNER, kicked.reason)
    assertEquals(SeatReclaim.Reason.NOT_OWNER, removed.reason)
  }

  @Test
  fun anUnreadableRoundKeepsTheSeatBorrowedAndAsksForARetry() {
    val fields = playDoc(cards = 0).toMutableMap().apply { this["currentRound"] = "fourteen" }
    val denial = noWriteOf(reclaim(fields))

    // The bound cannot be PROVED, so no write happens; the marker survives and
    // the poll comes back on the server's bound rather than returning a seat the
    // bot may still owe a trick on.
    assertEquals(SeatReclaim.Reason.TRICK_NOT_FINISHED, denial.reason)
    assertEquals(BOT_WINDOW_MILLIS, denial.retryAfterMillis)
  }

  // ── the retry bound: the server's own deadline, never a client guess ───

  @Test
  fun theRetryIsTheServersOwnRemainingWindow() {
    // p1's estimate is committed, so the document waits on p2 — a HUMAN seat whose
    // window is 20 s. The poll comes back when that deadline is due.
    val fields = biddingDoc(bids = mapOf("p1" to 6))

    val denial = noWriteOf(reclaim(fields, nowMillis = ANCHOR_MILLIS + 5_000))

    assertEquals(SeatReclaim.Reason.TRICK_NOT_FINISHED, denial.reason)
    assertEquals(HUMAN_WINDOW_MILLIS - 5_000L, denial.retryAfterMillis)
  }

  @Test
  fun aDeadlineThatHasAlreadyPassedStillWaitsOneBotWindow() {
    val fields = biddingDoc(bids = mapOf("p1" to 6))

    val denial = noWriteOf(reclaim(fields, nowMillis = ANCHOR_MILLIS + 25_000))

    // millisUntilDeadline floors at MIN_RETRY_AFTER_MILLIS for the resolver's
    // alarm; a reclaim has nothing to observe faster than the table's fastest
    // window, so its floor is that window.
    assertEquals(BOT_WINDOW_MILLIS, denial.retryAfterMillis)
  }

  @Test
  fun aBorrowedSeatOwnsTheShortWindowItself() {
    // Nothing committed: the document waits on p1, and p1 is borrowed, so the
    // pending window is the fixed 2 s — with 1 s of it left, 2 s is still the
    // soonest the trick can have moved.
    val fields = biddingDoc()

    val denial = noWriteOf(reclaim(fields, nowMillis = ANCHOR_MILLIS + 1_000))

    assertEquals(BOT_WINDOW_MILLIS, denial.retryAfterMillis)
  }

  @Test
  fun aDocumentWithNoTimedDecisionGetsOneBotWindow() {
    // The round has played out and waits on the advance, so no deadline exists;
    // the trick the bot owes can only finish through the writes that follow.
    val fields = playDoc(cards = CARDS_IN_FULL_ROUND, trickAtTakeover = 4L)

    val denial = noWriteOf(reclaim(fields))

    assertEquals(SeatReclaim.Reason.TRICK_NOT_FINISHED, denial.reason)
    assertEquals(BOT_WINDOW_MILLIS, denial.retryAfterMillis)
  }
}
