package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.DisconnectVotePhase
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.DisconnectVoteStatus
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.MatchMode
import com.estemshan.engine.seventhTakeoverCharge
import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S7 (A7.3 / Q32 + A8.2 / Q36) — the per-seat takeover counter, the 7th
 * takeover's terminal removal, and the ballot it cancels.
 *
 * What these pin, and why each one is here:
 *  - the COUNTER lives in `inactivity/{uid}` as its own field, parsed
 *    fail-closed (absent ⇒ 0; anything invalid ⇒ the advance refuses), it
 *    increments ONLY on a takeover event, and it survives an ordinary expiry
 *    and a reclaim unchanged — a reclaim resets the clock and the cursor,
 *    never the seat's takeover history;
 *  - the CHARGE is the engine table's, per mode: ROOM 1, RANKED 2, UNRANKED 2
 *    ([seventhTakeoverCharge] — A7.3's row of T-15/T-16), recorded once in a
 *    SIBLING map of S5's `timeoutRemovedCharges`, never a widening of it;
 *  - the REMOVAL is terminal and exactly once: one `takeoverRemovedUids`
 *    entry, one charge key, the borrowed marker gone, the seat's ballot and
 *    its electorate's ballots deleted, `timeoutRemovedUids` and the timeout
 *    counter untouched beyond their ordinary behaviour, and a replay of the
 *    same call writes nothing new — after the commit the seat is bot-held, so
 *    the branch cannot re-run (the removal's own idempotency proof, the same
 *    shape S5 rode);
 *  - the COLLISION (owner ruling 2026-10-27, Q-S7-4): when one expiry is
 *    BOTH the 15th timeout and the 7th takeover, the takeover owns the
 *    removal — one reason, one charge, never both — so no uid can ever
 *    appear in both charge maps ("no 4 Koz" by construction);
 *  - the VOTE-state firing (A7.3's "immediately, even with a 30 s ballot
 *    open"): a disconnect-held seat's INACTIVITY clock keeps accruing — the
 *    one deliberate semantic change — while the RD20 timeout counter keeps
 *    its held-seat suppression exactly, and a REPLACED hold keeps its
 *    pre-S7 freeze (that flow already made the seat permanently its own);
 *  - the ISOLATION: a 7th takeover of seat A cancels A's ballot and A's
 *    markers and nobody else's — seat B's live entry and ballot survive.
 *
 * Reachability honesty (task §4.4): the no-ballot and VOTE firings below are
 * reached through the REAL trigger — `resolveTimeoutOnce` on an expired
 * window. The WAIT firing is implemented (the read-only evaluation before the
 * pause gate) but is NOT reachable on a document this runtime can produce:
 * every persisted window close nulls the open-window anchor and the stored
 * accumulation is always below the 50 s threshold (the crossing expiry fires
 * the takeover in the same write), so the predicate cannot become true during
 * a live pause. [aPauseWithoutTheConditionAnswersExactlyAsBefore] and
 * [seventhTakeoverOverdueIsFalseForEveryStateTheRuntimeCanLeave] pin that
 * with evidence; making the condition live during a WAIT is E4.3's
 * decision-timer re-architecture, out of scope here. No test hand-builds an
 * unreachable stored state to fake it.
 */
class SeventhTakeoverTest {

  // ── fixtures (mirrored from TimeoutResolutionTest / InactiveTakeoverResolutionTest) ──

  private val order = listOf("p1", "p2", "p3", "p4")
  private val seats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
  private val players = listOf("u1", "u2", "u3", "u4")

  /** The deadline anchor, in epoch millis — the doc's `updatedAt`. */
  private val anchorMillis = 1_000_000L

  /** Round 14 of a FULL match: the human window is 15 + 5 = 20 s. */
  private val currentRound = 14
  private val humanWindowSeconds = 20

  /** The cursor of a round nobody has committed anything in: the round term alone. */
  private val cursorAtRoundStart = currentRound * 1_000_000L

  private fun storedCardEntry(seat: String, round: Int): Map<String, Any?> = mapOf(
    "seatId" to seat,
    "card" to mapOf("suit" to "SPADES", "rank" to mapOf("v" to 14, "s" to 1)),
    "round" to round,
  )

  /**
   * A dealt round-14 fast round, mid-estimate, waiting on p1 (the dealer)
   * with the other three estimates committed. [mode] defaults to ROOM — the
   * mode A7.3's 1-Koz cell names; [vote] models a disconnect-vote marker.
   */
  private fun fastRoundDoc(
    mode: String = "ROOM",
    takeovers: Map<String, Any?> = emptyMap(),
    vote: Map<String, Any?>? = null,
    takeoverRemovedUids: List<String> = emptyList(),
    cardLog: List<Map<String, Any?>> = emptyList(),
  ): Map<String, Any?> = mutableMapOf(
    "roomId" to "room-1",
    "players" to players,
    "status" to "starting",
    "currentRound" to currentRound,
    "maxRounds" to 18,
    "completedRound" to 0,
    "extendedRounds" to emptyList<Int>(),
    "dealer" to seats.getValue("p1"),
    "turn" to null,
    "seats" to seats,
    "version" to 1,
    "biddingOpen" to true,
    "bids" to linkedMapOf<String, Int?>(
      "p1" to null, "p2" to 3, "p3" to 3, "p4" to 3,
    ),
    "lastBidSeat" to "p4",
    "cardLog" to cardLog,
    "lastCardSeat" to null,
    "cardPhase" to null,
    "biddingLog" to emptyList<Map<String, Any?>>(),
    "gameState" to mapOf("initialized" to true, "dealtRound" to currentRound),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to mode,
    "botTier" to "MEDIUM",
    "botPersonality" to "BALANCED",
    "decisionTimerSeconds" to 15,
    "updatedAt" to anchorMillis,
    InactiveTakeover.TAKEOVER_FIELD to takeovers,
    "botSeats" to emptyList<String>(),
    "timeoutRemovedUids" to emptyList<String>(),
    TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD to takeoverRemovedUids,
  ).apply {
    if (vote != null) put(DisconnectVoteState.VOTE_FIELD, vote)
  }

  private fun voteEntry(
    seat: String,
    phase: DisconnectVotePhase,
    deadline: Long? = null,
    answered: Int = 0,
    verdict: DisconnectVoteStatus? = null,
  ): Map<String, Any?> = DisconnectVoteState(
    seatId = seat,
    phase = phase,
    detectedAtMillis = anchorMillis,
    deadlineMillis = deadline,
    answeredCount = answered,
    verdict = verdict,
  ).toFields()

  /** The private `inactivity/{uid}` document, with S7's count field. */
  private fun inactivityDoc(
    accumulated: Any?,
    cursor: Any?,
    count: Any? = null,
    openWindow: Any? = null,
  ): Map<String, Any?> = linkedMapOf(
    InactivityClock.ACCUMULATED_FIELD to accumulated,
    InactivityClock.OPEN_WINDOW_FIELD to openWindow,
    InactiveTakeover.CURSOR_FIELD to cursor,
  ).apply {
    if (count != null) put(TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD, count)
  }

  private fun dealtHands(): Map<String, List<Card>> = Dealer.dealHands(11L)

  /** One expired human window (20 s) and one expired held window (2 s). */
  private val expiredHumanWindow = DecisionClock { anchorMillis + 20_000 }
  private val expiredHeldWindow = DecisionClock { anchorMillis + 2_000 }

  /**
   * The seam, recording every side effect the 7th takeover can make — the
   * match write, the counter write, the inactivity replacement and the
   * ballot deletes.
   */
  private class FakeDb(
    private val doc: Map<String, Any?>?,
    private val hands: Map<String, List<Card>>,
    private val storedInactivity: Map<String, Map<String, Any?>> = emptyMap(),
    private val storedCounts: Map<String, Any?> = emptyMap(),
  ) : TimeoutDb {
    var written: Map<String, Any?>? = null
      private set
    val writtenCounts: MutableMap<String, Any?> = mutableMapOf()
    val writtenInactivity: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    val deletedBallots: MutableList<String> = mutableListOf()
    var inactivityReads = 0
      private set
    val readInactivityFor: MutableList<String> = mutableListOf()

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = doc
    override suspend fun readHands(
      matchId: String,
      round: Int,
      seats: List<String>,
    ): Map<String, List<Card>> = hands.filterKeys { it in seats }

    override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T =
      block(object : TimeoutDb.TimeoutTx {
        override suspend fun readMatch(matchId: String): Map<String, Any?>? = doc
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
          readInactivityFor += uid
          return storedInactivity[uid]
        }
        override fun writeInactivity(matchId: String, uid: String, fields: Map<String, Any?>) {
          writtenInactivity[uid] = fields
        }
        override suspend fun deleteBallot(matchId: String, uid: String) {
          deletedBallots += uid
        }
      })
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

  // ── the stored count: shape, fail-closed parse, survival ──────────────

  @Test
  fun theStoredCountParsesFailClosed() {
    // Absent is the trusted 0 — a document written before S7 — and 7 is VALID
    // (the terminal value the removal itself persists); everything else a
    // number cannot be trusted as refuses the whole advance.
    assertEquals(0, TimeoutResolution.storedTakeoverCount(null))
    assertEquals(0, TimeoutResolution.storedTakeoverCount(mapOf("other" to 1)))
    assertEquals(6, TimeoutResolution.storedTakeoverCount(inactivityDoc(0L, 0L, 6)))
    assertEquals(7, TimeoutResolution.storedTakeoverCount(inactivityDoc(0L, 0L, 7)))
    assertNull(TimeoutResolution.storedTakeoverCount(inactivityDoc(0L, 0L, "six")))
    assertNull(TimeoutResolution.storedTakeoverCount(inactivityDoc(0L, 0L, 1.5)))
    assertNull(TimeoutResolution.storedTakeoverCount(inactivityDoc(0L, 0L, -1)))
    assertNull(TimeoutResolution.storedTakeoverCount(inactivityDoc(0L, 0L, 8)))
  }

  @Test
  fun anOrdinaryExpiryCarriesAnExistingCountUnchangedAndAFirstWriteKeepsS73sShape() {
    val doc = fastRoundDoc()
    val pending = TimeoutResolution.Pending(
      TimeoutResolution.DecisionPhase.BIDDING, "p1", humanWindowSeconds, anchorMillis,
    )
    val estimateWrite = TimeoutResolution.DecisionWrite(
      updates = mapOf("bids" to linkedMapOf("p1" to 6)),
      decided = emptyMap(),
    )

    // A stored document exists (count 3): the replacement CARRIES the count,
    // unchanged — an ordinary expiry neither increments nor erases it.
    val carried = TimeoutResolution.inactivityAfterExpiredWindow(
      doc, pending, "u1", inactivityDoc(20_000L, cursorAtRoundStart, 3),
      anchorMillis + 20_000, estimateWrite,
    )!!
    assertEquals(3, carried.docFields[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD])
    assertEquals(40_000L, carried.docFields[InactivityClock.ACCUMULATED_FIELD])
    assertNull(carried.takeover)

    // A first write (no stored document): S73's exact three-field document —
    // there is no count to erase, and the field reads as 0 until one exists.
    val first = TimeoutResolution.inactivityAfterExpiredWindow(
      doc, pending, "u1", null, anchorMillis + 20_000, estimateWrite,
    )!!
    assertEquals(
      InactiveTakeover.inactivityDocFieldNames(),
      first.docFields.keys.toList(),
    )
  }

  @Test
  fun aTakeoverIncrementsTheCountAndTheSixthIsNotTerminal() = runTest {
    // 40 s stored + a 20 s window = the takeover; count 5 → 6, one short of
    // the removal — the marker is written, the seat is BORROWED, nothing is
    // removed and nothing is charged.
    val db = FakeDb(
      fastRoundDoc(mode = "ROOM"),
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 5)),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    assertEquals(true, response["resolved"])
    assertNull(db.written!![TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD])
    assertNull(db.written!![TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD])
    val marker = db.written!![InactiveTakeover.TAKEOVER_FIELD] as Map<*, *>
    assertTrue(
      marker.containsKey("p1"),
      "the sixth takeover borrows the seat",
    )
    assertEquals(6, db.writtenInactivity.getValue("u1")[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD])
    assertTrue(db.deletedBallots.isEmpty())
  }

  // ── the removal: terminal, charged once, ballot cancelled ─────────────

  @Test
  fun theSeventhTakeoverRemovesTheSeatAndRecordsOneKozInRoom() = runTest {
    val db = FakeDb(
      fastRoundDoc(mode = "ROOM"),
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    assertEquals(true, response["resolved"])
    val updates = db.written!!
    // One entry, one charge — the engine table's ROOM amount (A7.3 / Q32).
    assertEquals(listOf("u1"), updates[TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD])
    assertEquals(mapOf("u1" to 1), updates[TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD])
    // The timeout mechanism's fields are never touched, nor any other
    // mechanism's list — and the ordinary human-expiry counter increment is
    // exactly what it was before S7 (the expiry that EARNED the takeover was
    // a genuine human timeout).
    assertNull(updates["timeoutRemovedUids"])
    assertNull(updates[TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
    assertNull(updates["removedUids"])
    assertNull(updates["botSeats"])
    assertNull(updates["leftUids"])
    assertNull(updates[DisconnectVoteState.REPLACED_UIDS_FIELD])
    assertEquals(1, db.writtenCounts["u1"])
    // The seat is REMOVED, not borrowed: its inactiveTakeovers entry (absent
    // here) would leave with the same write — the field is the empty surgery.
    assertEquals(emptyMap<String, Any?>(), updates[InactiveTakeover.TAKEOVER_FIELD])
    // version rides the decision write's own bump.
    assertEquals(2, updates["version"])
    // The ballot dies with the seat: its own document and its whole
    // electorate's, in the same transaction.
    assertEquals(listOf("u1", "u2", "u3", "u4"), db.deletedBallots)
    // The count stops at 7, the clock is zeroed, and the seat is bot-held
    // from this commit on.
    val stored = db.writtenInactivity.getValue("u1")
    assertEquals(7, stored[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD])
    assertEquals(0L, stored[InactivityClock.ACCUMULATED_FIELD])
    // The removal makes the seat permanently bot-driven for every gate.
    val committed = fastRoundDoc(mode = "ROOM", takeoverRemovedUids = listOf("u1"))
    assertEquals(setOf("p1"), TimeoutResolution.botControlledSeats(committed))
  }

  // ── the collision: the 15th timeout and the 7th takeover ─────────────

  /**
   * The owner's ruling (2026-10-27, Q-S7-4): when ONE expiry is both the 15th
   * timeout and the 7th takeover, the takeover wins — one removal, one charge.
   * The pre-state is one the runtime can leave (six takeovers earned, the clock
   * at 40 s after the reclaim's two 20 s windows, the counter at 14), not a
   * hand-built impossibility: it is the fixture of
   * [theSeventhTakeoverRemovesTheSeatAndRecordsOneKozInRoom] plus S5's 15th.
   */
  @Test
  fun theSeventhTakeoverWinsTheCollisionWithTheFifteenthTimeout() = runTest {
    val db = FakeDb(
      fastRoundDoc(mode = "ROOM"),
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
      storedCounts = mapOf("u1" to 14),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    assertEquals(true, response["resolved"])
    val updates = db.written!!
    // ONE reason, ONE charge: the takeover's. The 15th's own fields stay
    // absent even though the counter reached 15 in this very transaction.
    assertEquals(listOf("u1"), updates[TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD])
    assertEquals(mapOf("u1" to 1), updates[TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD])
    assertNull(updates["timeoutRemovedUids"])
    assertNull(updates[TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
    // The counter still advances — the 15th timeout DID happen — but it
    // removes and charges nothing.
    assertEquals(15, db.writtenCounts["u1"])
    // The terminal takeover's own state: count 7, clock zeroed, marker gone,
    // and the ballot cancelled in the same transaction.
    val stored = db.writtenInactivity.getValue("u1")
    assertEquals(7, stored[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD])
    assertEquals(0L, stored[InactivityClock.ACCUMULATED_FIELD])
    assertEquals(emptyMap<String, Any?>(), updates[InactiveTakeover.TAKEOVER_FIELD])
    assertEquals(listOf("u1", "u2", "u3", "u4"), db.deletedBallots)
    // The owner's hard invariant: the seat's total is 1 Koz in ROOM, never 2.
    assertEquals(
      1,
      (TimeoutResolution.timeoutRemovedCharges(updates)["u1"] ?: 0) +
        (TimeoutResolution.takeoverRemovedCharges(updates)["u1"] ?: 0),
    )
  }

  /**
   * The owner's invariant, pinned across every reachable state: no resolution
   * may ever name one uid in BOTH removal lists or BOTH charge maps. This is
   * what keeps "4 Koz instead of 2" from coming back if a later change
   * reintroduces the overlap.
   */
  @Test
  fun noResolutionEverRecordsTwoRemovalReasonsOrTwoChargesForOneUid() = runTest {
    suspend fun assertSingleReason(db: FakeDb) {
      val updates = db.written ?: error("the state must resolve and write")
      val timeoutUids = TimeoutResolution.timeoutRemovedUids(updates)
      val takeoverUids = TimeoutResolution.takeoverRemovedUids(updates)
      assertTrue(
        (timeoutUids intersect takeoverUids).isEmpty(),
        "one uid removed by two mechanisms: $timeoutUids vs $takeoverUids",
      )
      val timeoutCharges = TimeoutResolution.timeoutRemovedCharges(updates)
      val takeoverCharges = TimeoutResolution.takeoverRemovedCharges(updates)
      assertTrue(
        (timeoutCharges.keys intersect takeoverCharges.keys).isEmpty(),
        "one uid charged twice: $timeoutCharges vs $takeoverCharges",
      )
    }

    // The 15th alone.
    val fifteenth = FakeDb(
      fastRoundDoc(mode = "RANKED"),
      dealtHands(),
      storedCounts = mapOf("u1" to 14),
    )
    resolveTimeoutOnce(fifteenth, fakeRequest("u3"), expiredHumanWindow)
    assertSingleReason(fifteenth)

    // The 7th alone.
    val seventh = FakeDb(
      fastRoundDoc(mode = "RANKED"),
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
    )
    resolveTimeoutOnce(seventh, fakeRequest("u3"), expiredHumanWindow)
    assertSingleReason(seventh)

    // Both at once.
    val collision = FakeDb(
      fastRoundDoc(mode = "RANKED"),
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
      storedCounts = mapOf("u1" to 14),
    )
    resolveTimeoutOnce(collision, fakeRequest("u3"), expiredHumanWindow)
    assertSingleReason(collision)
  }

  @Test
  fun theSeventhTakeoverChargesTwoOutsideRoom() = runTest {
    for (mode in listOf("RANKED", "UNRANKED")) {
      val db = FakeDb(
        fastRoundDoc(mode = mode),
        dealtHands(),
        storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
      )

      resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

      assertEquals(
        mapOf("u1" to 2),
        db.written!![TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD],
        "mode $mode records A7.3's 2 Koz",
      )
    }
    // The engine table itself, once, at the source of truth.
    assertEquals(1, TimeoutResolution.takeoverRemovalChargeKoz(MatchMode.ROOM))
    assertEquals(2, TimeoutResolution.takeoverRemovalChargeKoz(MatchMode.RANKED))
    assertEquals(2, TimeoutResolution.takeoverRemovalChargeKoz(MatchMode.UNRANKED))
  }

  @Test
  fun anEarlierRemovalsChargeIsCarriedAndASameUidRewriteIsValueIdentical() {
    // Two removals accumulate in one map; a same-uid SET is never additive —
    // the amount is a pure function of the create-only mode.
    val doc = fastRoundDoc(mode = "RANKED", takeoverRemovedUids = listOf("u4"))
      .toMutableMap()
      .apply { put(TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD, mapOf("u4" to 2)) }

    assertEquals(
      mapOf("u4" to 2, "u1" to 2),
      TimeoutResolution.takeoverRemovalChargeWrite(doc, "u1", koz = 2),
    )
    assertEquals(
      mapOf("u4" to 2),
      TimeoutResolution.takeoverRemovalChargeWrite(doc, "u4", koz = 2),
    )
    // Fail-closed parse, the same convention S5's map uses.
    assertEquals(
      emptyMap(),
      TimeoutResolution.takeoverRemovedCharges(
        mapOf(TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD to listOf("u1")),
      ),
    )
    assertEquals(
      mapOf("u2" to 2),
      TimeoutResolution.takeoverRemovedCharges(
        mapOf(
          TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD to
            mapOf("u2" to 2, "u4" to "not-a-number"),
        ),
      ),
    )
  }

  @Test
  fun aReplayOfTheSeventhWritesNothingNew() = runTest {
    val doc = fastRoundDoc(mode = "ROOM")
    val first = FakeDb(
      doc,
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
    )
    resolveTimeoutOnce(first, fakeRequest("u3"), expiredHumanWindow)
    assertEquals(listOf("u1"), first.written!![TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD])

    // The committed document: the removal, the charge, the decision, and a
    // fresh anchor — the write re-anchored `updatedAt`, which is the Stage-1
    // idempotency mechanism a replay must survive.
    val committed = doc.toMutableMap().apply {
      putAll(first.written!!)
      put("updatedAt", anchorMillis + 20_000)
    }

    val replay = FakeDb(
      committed,
      dealtHands(),
      storedInactivity = mapOf(
        "u1" to first.writtenInactivity.getValue("u1"), // count 7, as committed
      ),
      storedCounts = mapOf("u1" to 1), // the ordinary increment, as committed
    )
    resolveTimeoutOnce(replay, fakeRequest("u3"), DecisionClock { anchorMillis + 60_000 })

    // Whatever the replay finds to do, it is nothing S7: no second entry, no
    // second charge key, no delete, and u1's count stays exactly 7 (the seat
    // is bot-held, so neither its clock nor its counter branch ever re-runs).
    assertTrue(
      replay.written?.get(TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD) == null,
      "a replay must not re-write the removal: ${replay.written}",
    )
    assertTrue(
      replay.written?.get(TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD) == null,
      "a replay must not re-write the charge: ${replay.written}",
    )
    assertNull(replay.writtenCounts["u1"])
    assertTrue("u1" !in replay.writtenInactivity)
    assertTrue(replay.deletedBallots.isEmpty())
  }

  // ── A7.3's VOTE state: the accrual lift and the mid-ballot firing ─────

  @Test
  fun aVoteHeldSeatAccruesAndEarnsItsSeventhMidBallot() = runTest {
    val doc = fastRoundDoc(
      mode = "ROOM",
      vote = mapOf(
        "p1" to voteEntry(
          "p1", DisconnectVotePhase.VOTE,
          deadline = anchorMillis + 30_000, answered = 1,
        ),
      ),
    )

    // The pure advance first: a held seat's clock is NOT suppressed while the
    // ballot about it is open — 48 s stored + the 2 s held window = 50 s, the
    // threshold. Before S7 this call returned null for every held seat.
    val pending = TimeoutResolution.Pending(
      TimeoutResolution.DecisionPhase.BIDDING, "p1", 2, anchorMillis,
    )
    val estimateWrite = TimeoutResolution.DecisionWrite(
      updates = mapOf("bids" to linkedMapOf("p1" to 6)),
      decided = emptyMap(),
    )
    val advance = TimeoutResolution.inactivityAfterExpiredWindow(
      doc, pending, "u1", inactivityDoc(48_000L, cursorAtRoundStart, 6),
      anchorMillis + 2_000, estimateWrite,
    )!!
    assertTrue(
      advance.takeover != null,
      "the held seat earns its takeover mid-ballot",
    )
    assertEquals(7, advance.docFields[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD])

    // End to end through the real trigger: the held seat's window is the 2 s
    // bot pace, the seventh fires, and the OPEN BALLOT IS CANCELLED with it.
    val db = FakeDb(
      doc,
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(48_000L, cursorAtRoundStart, 6)),
    )
    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHeldWindow)

    assertEquals(true, response["resolved"])
    assertEquals(TimeoutResolution.BOT_DECISION_SECONDS, response["deadlineSeconds"])
    assertEquals(listOf("u1"), db.written!![TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD])
    assertEquals(mapOf("u1" to 1), db.written!![TimeoutResolution.TAKEOVER_REMOVED_CHARGES_FIELD])
    // A8.2: the seat's own marker entry is deleted — no verdict, no re-open.
    assertEquals(emptyMap<String, Any?>(), db.written!![DisconnectVoteState.VOTE_FIELD])
    assertEquals(listOf("u1", "u2", "u3", "u4"), db.deletedBallots)
    assertEquals(7, db.writtenInactivity.getValue("u1")[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD])
    // The timeout counter's held-seat suppression is untouched by the lift.
    assertTrue(db.writtenCounts.isEmpty())
  }

  @Test
  fun aReplacedDisconnectSeatStillAccruesNothing() = runTest {
    // The lift covers the BORROWING phases only: a REPLACED hold already made
    // the seat permanently that flow's own, and stacking a 7th-takeover
    // removal (and charge) on it would double-charge a seat another mechanism
    // replaced — against A8.2's no-double-charge. The freeze stays.
    val doc = fastRoundDoc(
      mode = "ROOM",
      vote = mapOf(
        "p1" to voteEntry(
          "p1", DisconnectVotePhase.REPLACED,
          deadline = anchorMillis + 30_000, answered = 2,
          verdict = DisconnectVoteStatus.NO_AGREEMENT,
        ),
      ),
    )
    val pending = TimeoutResolution.Pending(
      TimeoutResolution.DecisionPhase.BIDDING, "p1", 2, anchorMillis,
    )
    val estimateWrite = TimeoutResolution.DecisionWrite(
      updates = mapOf("bids" to linkedMapOf("p1" to 6)),
      decided = emptyMap(),
    )
    assertNull(
      TimeoutResolution.inactivityAfterExpiredWindow(
        doc, pending, "u1", inactivityDoc(40_000L, cursorAtRoundStart, 6),
        anchorMillis + 2_000, estimateWrite,
      ),
    )

    val db = FakeDb(
      doc,
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
    )
    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHeldWindow)

    // The bot's decision lands (the table must not stall); nothing S7 does.
    assertEquals(true, response["resolved"])
    assertNull(db.written!![TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD])
    assertNull(db.written!![InactiveTakeover.TAKEOVER_FIELD])
    assertTrue(db.writtenInactivity.isEmpty())
    assertTrue(db.deletedBallots.isEmpty())
  }

  @Test
  fun theSeventhOfSeatADoesNotCancelSeatBsBallot() = runTest {
    // p2's ballot is open; p1 (unheld, human-paced) earns its 7th. Only p1's
    // own ballot and markers die: p2's entry is carried VERBATIM, p2 holds no
    // ballot in p1's electorate (a held seat is never asked), and p2's uid is
    // absent from the deletes.
    val p2Entry = voteEntry(
      "p2", DisconnectVotePhase.VOTE,
      deadline = anchorMillis + 30_000, answered = 1,
    )
    val db = FakeDb(
      fastRoundDoc(mode = "ROOM", vote = mapOf("p2" to p2Entry)),
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredHumanWindow)

    assertEquals(true, response["resolved"])
    assertEquals(listOf("u1"), db.written!![TimeoutResolution.TAKEOVER_REMOVED_UIDS_FIELD])
    val marker = db.written!![DisconnectVoteState.VOTE_FIELD] as Map<*, *>
    assertSame(
      p2Entry,
      marker["p2"],
      "p2's entry is carried, not reinterpreted",
    )
    assertEquals(listOf("u1", "u3", "u4"), db.deletedBallots)

    // B's live WAIT: the granted pause freezes the whole table (S74's own
    // semantics, unchanged), so with p2 in WAIT the pause answers before any
    // expiry and NOTHING of p1's fires — B's WAIT is untouched by
    // construction. Once the pause's bound passes and the disconnect resolver
    // re-stamps `updatedAt`, p1's window re-anchors and the same trigger as
    // the VOTE case fires p1's 7th; the same surgical builders carry p2's
    // entry verbatim whatever its phase (pinned at the engine layer by
    // DisconnectVoteStateTest.aTransitionNeverTouchesAnotherSeatsEntry).
    val waitDoc = fastRoundDoc(
      mode = "ROOM",
      vote = mapOf(
        "p2" to voteEntry(
          "p2", DisconnectVotePhase.WAIT,
          deadline = anchorMillis + 120_000, answered = 2,
          verdict = DisconnectVoteStatus.WAIT,
        ),
      ),
    )
    val pausedDb = FakeDb(
      waitDoc,
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(40_000L, cursorAtRoundStart, 6)),
    )
    val pausedResponse = resolveTimeoutOnce(
      pausedDb, fakeRequest("u3"), DecisionClock { anchorMillis + 20_000 },
    )

    assertEquals(false, pausedResponse["resolved"])
    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, pausedResponse["reason"])
    assertNull(pausedDb.written, "the pause wrote nothing — B's WAIT is untouched")
    assertEquals(
      listOf("u2"),
      pausedDb.readInactivityFor,
      "only the WAIT's own seat was looked at — p1's condition is not even evaluated",
    )
    assertTrue(pausedDb.deletedBallots.isEmpty())
  }

  // ── the reclaim: the clock resets, the takeover history does not ──────

  @Test
  fun aReclaimCarriesTheCountAndResetsTheClockAndCursor() = runTest {
    // A borrowed seat whose trick bound has passed (4 cards of the round =>
    // trick 1 done vs. a takeover stamped at trick 0), reclaiming with a
    // stored count of 4.
    val doc = fastRoundDoc(
      takeovers = mapOf(
        "p1" to mapOf(
          InactiveTakeover.TAKEN_OVER_AT_FIELD to 5_000L,
          InactiveTakeover.ROUND_AT_TAKEOVER_FIELD to currentRound.toLong(),
          InactiveTakeover.TRICK_AT_TAKEOVER_FIELD to 0L,
        ),
      ),
      cardLog = List(4) { storedCardEntry("p2", currentRound) },
    )
    val db = FakeDb(
      doc,
      dealtHands(),
      storedInactivity = mapOf(
        "u1" to inactivityDoc(0L, cursorAtRoundStart + 4_000L, 4),
      ),
    )

    val response = reclaimOnce(db, fakeRequest("u1"), DecisionClock { anchorMillis + 60_000 })

    assertEquals(true, response["reclaimed"])
    val stored = db.writtenInactivity.getValue("u1")
    // Clock zeroed, cursor re-baselined to the seat's own committed count
    // (p1 committed nothing this round — the four cards are p2's)…
    assertEquals(0L, stored[InactivityClock.ACCUMULATED_FIELD])
    assertEquals(cursorAtRoundStart, stored[InactiveTakeover.CURSOR_FIELD])
    // …and the takeover history carried verbatim: a return is not a reset.
    assertEquals(4, stored[TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD])
    assertEquals(emptyMap<String, Any?>(), db.written!![InactiveTakeover.TAKEOVER_FIELD])

    // The pure decision agrees without a document (the defaulted parameter is
    // the pre-S7 call shape old fakes still use).
    val decision = SeatReclaim.decide(doc, "u1", anchorMillis + 60_000)
    assertTrue(decision is SeatReclaim.Decision.Reclaim)
    assertNull(
      (decision as SeatReclaim.Decision.Reclaim).write.inactivityFields[
        TimeoutResolution.INACTIVITY_TAKEOVER_COUNT_FIELD,
      ],
    )
  }

  @Test
  fun aTakeoverRemovedSeatCannotBeReclaimed() {
    // The removal is permanent: the seat is in the bot-controlled union, so
    // the reclaim gate answers NOT_OWNER exactly as it does for Vote Kick's
    // and the 15-timeout's seats.
    val doc = fastRoundDoc(takeoverRemovedUids = listOf("u1"))
    val decision = SeatReclaim.decide(doc, "u1", anchorMillis + 60_000)
    assertTrue(decision is SeatReclaim.Decision.NoWrite)
    assertEquals(SeatReclaim.Reason.NOT_OWNER, (decision as SeatReclaim.Decision.NoWrite).reason)
  }

  // ── the WAIT state: implemented before the pause gate, honestly ───────

  @Test
  fun aPauseWithoutTheConditionAnswersExactlyAsBefore() = runTest {
    // A live WAIT about p1, the stored count already at 6, and the largest
    // accumulation a runtime write can leave below the threshold (49_999 —
    // fromStored rejects anything at or above 50 s). The evaluation runs
    // (one inactivity read, inside the transaction), the condition is false,
    // and the pause answers exactly as it did before S7: no decision
    // resolves, nothing is written, nothing is deleted.
    val db = FakeDb(
      fastRoundDoc(
        mode = "ROOM",
        vote = mapOf(
          "p1" to voteEntry(
            "p1", DisconnectVotePhase.WAIT,
            deadline = anchorMillis + 120_000, answered = 2,
            verdict = DisconnectVoteStatus.WAIT,
          ),
        ),
      ),
      dealtHands(),
      storedInactivity = mapOf("u1" to inactivityDoc(49_999L, cursorAtRoundStart, 6)),
    )

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), DecisionClock { anchorMillis + 30_000 })

    assertEquals(false, response["resolved"])
    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, response["reason"])
    assertEquals(90_000L, response["retryAfterMillis"])
    assertEquals(1, db.inactivityReads)
    assertNull(db.written)
    assertTrue(db.writtenInactivity.isEmpty())
    assertTrue(db.deletedBallots.isEmpty())
    assertTrue(db.writtenCounts.isEmpty())
  }

  @Test
  fun seventhTakeoverOverdueIsFalseForEveryStateTheRuntimeCanLeave() {
    // The evidence half of the WAIT reachability report: on a committed
    // document plus inactivity document this runtime can produce, the
    // pre-pause predicate is false in every combination — the stored
    // accumulation tops out one millis below the threshold, the open-window
    // anchor is nulled by every persisted close, and the expiry that crosses
    // the threshold fires the takeover in the same write (never leaving the
    // count at 6 with an eligible clock). Making the predicate true during a
    // live pause is E4.3's re-architecture.
    val waitDoc = fastRoundDoc(
      mode = "ROOM",
      vote = mapOf(
        "p1" to voteEntry(
          "p1", DisconnectVotePhase.WAIT,
          deadline = anchorMillis + 120_000, answered = 2,
          verdict = DisconnectVoteStatus.WAIT,
        ),
      ),
    )
    val now = anchorMillis + 30_000

    assertFalse(
      TimeoutResolution.seventhTakeoverOverdue(
        waitDoc, "p1", inactivityDoc(49_999L, cursorAtRoundStart, 6), now,
      ),
      "the largest sub-threshold accumulation never reaches the condition",
    )
    assertFalse(
      TimeoutResolution.seventhTakeoverOverdue(
        waitDoc, "p1", inactivityDoc(49_999L, cursorAtRoundStart, 5), now,
      ),
      "a count short of 6 is not the terminal window",
    )
    assertFalse(
      TimeoutResolution.seventhTakeoverOverdue(
        fastRoundDoc(takeoverRemovedUids = listOf("u1")),
        "p1", inactivityDoc(49_999L, cursorAtRoundStart, 6), now,
      ),
      "an already-removed uid is the replay case, not a new removal",
    )
    assertFalse(
      TimeoutResolution.seventhTakeoverOverdue(
        waitDoc, "p1", inactivityDoc(49_999L, cursorAtRoundStart, "six"), now,
      ),
      "an untrustworthy count fails closed",
    )
    assertFalse(
      TimeoutResolution.seventhTakeoverOverdue(
        fastRoundDoc(
          mode = "ROOM",
          vote = mapOf(
            "p1" to voteEntry(
              "p1", DisconnectVotePhase.REPLACED,
              deadline = anchorMillis, answered = 2,
              verdict = DisconnectVoteStatus.NO_AGREEMENT,
            ),
          ),
        ),
        "p1", inactivityDoc(49_999L, cursorAtRoundStart, 6), now,
      ),
      "a seat the disconnect flow replaced is not the WAIT's borrowing",
    )
  }
}
