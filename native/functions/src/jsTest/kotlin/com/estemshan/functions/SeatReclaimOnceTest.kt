package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * S73 Stage 2, C4 — the reclaim as the callable runs it: the transaction that
 * makes DEC-RD19-04's two writes atomic, the response a client switches on, and
 * the state a reclaim must never touch.
 *
 * [SeatReclaimTest] pins WHICH answer the document gives; this pins what the
 * server DOES with it. The seam is the resolver's own, so the discipline is the
 * resolver's: the decision is re-derived from the in-transaction read — a marker
 * that appeared or vanished between the pre-check and the transaction is seen,
 * never assumed — and the private document is replaced, not merged.
 *
 * Deliberately NOT here: the throw paths (a missing matchId, a match that is not
 * a match document, a caller who is not a player). Constructing an `HttpsError`
 * is JS interop and cannot load in a jsNode test, exactly as [CallableAuthTest]
 * documents, so those belong to the emulator suite; the equivalent refusals that
 * ARE representable here — an ownerless caller, a permanently bot-driven seat —
 * are asserted as no-writes below.
 */
class SeatReclaimOnceTest {

  private companion object {
    val order = listOf("p1", "p2", "p3", "p4")
    val seats: Map<String, String> =
      linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
    val players = listOf("u1", "u2", "u3", "u4")

    const val ANCHOR_MILLIS = 1_000_000L
    const val CURRENT_ROUND = 14
    const val VERSION_ON_DOC = 7

    /** Cards one trick consumes. */
    const val CARDS_PER_TRICK = 4
  }

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
   * A round-14 match mid-card-play: every estimate committed, [cards] cards of
   * this round down in seat order, and p1's seat borrowed at
   * [trickAtTakeover] unless [takeovers] says otherwise.
   */
  private fun doc(
    cards: Int = CARDS_PER_TRICK,
    trickAtTakeover: Long = 0L,
    takeovers: Map<String, Any?> = mapOf("p1" to entry(trick = trickAtTakeover)),
    botSeats: List<String> = emptyList(),
    timeoutRemovedUids: List<String> = emptyList(),
    members: List<String> = players,
  ): Map<String, Any?> = mutableMapOf<String, Any?>(
    "roomId" to "room-1",
    "players" to members,
    "status" to "playing",
    "currentRound" to CURRENT_ROUND,
    "maxRounds" to 18,
    "dealer" to seats.getValue("p1"),
    "turn" to seats.getValue("p2"),
    "seats" to seats,
    "version" to VERSION_ON_DOC,
    "biddingOpen" to false,
    "bids" to linkedMapOf<String, Int?>(*order.map { it to 6 }.toTypedArray()),
    "lastBidSeat" to "p4",
    "cardLog" to (0 until cards).map { storedCard(order[it % order.size], CURRENT_ROUND) },
    "lastCardSeat" to order[(cards - 1).coerceAtLeast(0) % order.size],
    "cardPhase" to "PLAY",
    "biddingLog" to order.map { seat ->
      mapOf(
        "seatId" to seat,
        "actionType" to "SubmitDashCallDecision",
        "declaredDashCall" to false,
        "round" to CURRENT_ROUND,
      )
    },
    "gameState" to mapOf("initialized" to true, "dealtRound" to CURRENT_ROUND),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to "RANKED",
    "botTier" to "MEDIUM",
    "botPersonality" to "BALANCED",
    "decisionTimerSeconds" to 15,
    "updatedAt" to ANCHOR_MILLIS,
    InactiveTakeover.TAKEOVER_FIELD to takeovers,
    "botSeats" to botSeats,
    "timeoutRemovedUids" to timeoutRemovedUids,
  )

  /**
   * The seam, recording every side effect so a test can prove BOTH that the two
   * writes ride one transaction and that nothing else on the document was
   * touched. The counter and hand seams are implemented as read-denying traps
   * for one test only — see [aReclaimReadsNoTimeoutCounterAndNoHands]; a reclaim
   * has no business asking, and a fake that quietly answers would hide it.
   */
  private class FakeReclaimDb(
    private val doc: Map<String, Any?>?,
    /** The document the transaction sees; defaults to [doc]. */
    private val inTxDoc: Map<String, Any?>? = null,
  ) : TimeoutDb {
    var written: Map<String, Any?>? = null
      private set
    val writtenInactivity: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    val writtenCounts: MutableMap<String, Any?> = mutableMapOf()
    val writes: MutableList<String> = mutableListOf()
    var transactions = 0
      private set
    var preReads = 0
      private set
    var txReads = 0
      private set
    var counterReads = 0
      private set
    var counterExistsReads = 0
      private set
    var inactivityReads = 0
      private set
    var handReads = 0
      private set

    override suspend fun readMatch(matchId: String): Map<String, Any?>? {
      preReads++
      return doc
    }

    override suspend fun readHands(
      matchId: String,
      round: Int,
      seats: List<String>,
    ): Map<String, List<Card>> {
      handReads++
      return emptyMap()
    }

    override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T {
      transactions++
      val txDoc = inTxDoc ?: doc
      return block(object : TimeoutDb.TimeoutTx {
        override suspend fun readMatch(matchId: String): Map<String, Any?>? {
          txReads++
          return txDoc
        }

        override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
          written = updates
          writes += "matches"
        }

        override suspend fun readTimeoutCount(matchId: String, uid: String): Any? {
          counterReads++
          return null
        }

        override suspend fun timeoutCountExists(matchId: String, uid: String): Boolean {
          counterExistsReads++
          return false
        }

        override fun writeTimeoutCount(matchId: String, uid: String, count: Int) {
          writtenCounts[uid] = count
          writes += "timeoutCounts"
        }

        override suspend fun readInactivity(matchId: String, uid: String): Map<String, Any?>? {
          inactivityReads++
          return null
        }

        override fun writeInactivity(matchId: String, uid: String, fields: Map<String, Any?>) {
          writtenInactivity[uid] = fields
          writes += "inactivity"
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

  /** Well past the doc's own deadline, so any retry bound is at its floor. */
  private val later = DecisionClock { ANCHOR_MILLIS + 30_000 }

  @Suppress("UNCHECKED_CAST")
  private fun markerOf(written: Map<String, Any?>?): Map<String, Any?> =
    written?.get(InactiveTakeover.TAKEOVER_FIELD) as? Map<String, Any?> ?: emptyMap()

  private suspend fun reclaim(
    db: FakeReclaimDb,
    uid: String = "u1",
  ): Map<String, Any?> = reclaimOnce(db, fakeRequest(uid), later)

  // ── the atomic pair ───────────────────────────────────────────────────

  @Test
  fun aDueReclaimWritesTheMarkerAndTheClockInOneTransaction() = runTest {
    val fields = doc()
    val db = FakeReclaimDb(fields)

    val response = reclaim(db)

    assertEquals(true, response["reclaimed"])
    assertEquals("m1", response["matchId"])
    // The seat the DOCUMENT gave this uid, never an echo of a client claim —
    // which is also why a reclaim cannot be pointed at somebody else's seat.
    assertEquals("p1", response["seatId"])
    assertEquals(1, db.transactions)
    assertEquals(listOf("matches", "inactivity"), db.writes)

    // The whole match write: the marker replaced, the version ticked, the
    // deadline anchor re-stamped. Nothing else on the document moves.
    assertEquals(
      setOf(InactiveTakeover.TAKEOVER_FIELD, "version", "updatedAt"),
      db.written!!.keys,
    )
    assertEquals(VERSION_ON_DOC + 1, db.written!!["version"] as Int)
    assertTrue("p1" !in markerOf(db.written), "the returning seat's entry must be gone")

    // The private document is REPLACED by the complete fresh state — zeroed
    // accumulation, no open window, and the cursor baseline that restarts it.
    val clock = db.writtenInactivity.getValue("u1")
    assertEquals(InactiveTakeover.inactivityDocFieldNames(), clock.keys.toList())
    assertEquals(0L, clock[InactivityClock.ACCUMULATED_FIELD])
    assertEquals(null, clock[InactivityClock.OPEN_WINDOW_FIELD])
    assertEquals(TimeoutResolution.seatActionCursor(fields, "p1"), clock[InactiveTakeover.CURSOR_FIELD])
  }

  @Test
  fun aReclaimCarriesAnotherSeatsMarkerEntryVerbatim() = runTest {
    val other = entry(round = CURRENT_ROUND.toLong(), trick = 2L)
    val db = FakeReclaimDb(doc(takeovers = linkedMapOf("p1" to entry(), "p2" to other)))

    val response = reclaim(db)

    assertEquals(true, response["reclaimed"])
    val marker = markerOf(db.written)
    assertEquals(setOf("p2"), marker.keys)
    // p2's borrow is another mechanism's business; this reclaim does not own it
    // and must not reinterpret it.
    assertSame(other, marker["p2"])
  }

  // ── the no-writes: each refusal leaves the document exactly as it was ──

  @Test
  fun aTrickStillOwedWritesNothingAndNamesTheServersRetry() = runTest {
    val db = FakeReclaimDb(doc(cards = 3))

    val response = reclaim(db)

    assertEquals(false, response["reclaimed"])
    assertEquals(SeatReclaim.Reason.TRICK_NOT_FINISHED, response["reason"])
    // The client re-arms its poll on THIS and on nothing else — it cannot derive
    // the deadline anchor, which is not on MatchDoc.
    assertTrue(response["retryAfterMillis"] is Number, "a retry must be a number")
    assertNullWrites(db)
  }

  @Test
  fun aSeatNobodyBorrowedWritesNothingAndPromisesNoRetry() = runTest {
    val db = FakeReclaimDb(doc(takeovers = emptyMap()))

    val response = reclaim(db)

    assertEquals(SeatReclaim.Reason.NOT_BORROWED, response["reason"])
    // Deliberate: `retryAfterMillis` is present ONLY when the server knows the
    // seat is owed. A player holding their own seat must not be told to poll.
    assertTrue("retryAfterMillis" !in response, "a non-owed seat has no retry bound")
    assertNullWrites(db)
  }

  @Test
  fun aGarbageMarkerWritesNothingAndKeepsTheBorrowRecoverable() = runTest {
    val db = FakeReclaimDb(doc(takeovers = mapOf("p1" to "borrowed")))

    val response = reclaim(db)

    // Fail-closed toward the player: the seat reads as their own, so nothing is
    // written and the next real accumulation overwrites the garbage.
    assertEquals(SeatReclaim.Reason.NOT_BORROWED, response["reason"])
    assertNullWrites(db)
  }

  @Test
  fun aCallerWhoOccupiesNoSeatIsRefusedWithoutAWrite() = runTest {
    // A player of the match with no seat: membership passes, so the refusal is
    // the pure gate's — and the request never names a seat to try anyway.
    val db = FakeReclaimDb(doc(members = players + "u5"))

    val response = reclaim(db, uid = "u5")

    assertEquals(SeatReclaim.Reason.NOT_OWNER, response["reason"])
    assertNullWrites(db)
  }

  @Test
  fun aSeatTheTableRemovedIsNotReturnedThroughTheCallable() = runTest {
    val removed = FakeReclaimDb(doc(timeoutRemovedUids = listOf("u1")))
    val kicked = FakeReclaimDb(doc(botSeats = listOf("p1")))

    val removalResponse = reclaim(removed)
    val kickResponse = reclaim(kicked)

    // A 15-timeout removal or a Vote Kick is not a borrow, so DEC-RD19-04 has
    // nothing to return: a player the table took over cannot reclaim past it.
    assertEquals(SeatReclaim.Reason.NOT_OWNER, removalResponse["reason"])
    assertEquals(SeatReclaim.Reason.NOT_OWNER, kickResponse["reason"])
    assertNullWrites(removed)
    assertNullWrites(kicked)
  }

  // ── the transaction is the source of truth ────────────────────────────

  @Test
  fun aMarkerThatVanishedBeforeTheTransactionIsSeenNotAssumed() = runTest {
    val db = FakeReclaimDb(
      doc = doc(),
      inTxDoc = doc(takeovers = emptyMap()),
    )

    val response = reclaim(db)

    // The pre-read said "reclaim now"; the committed doc says the seat is
    // already the player's. The transaction answers, so no second removal and
    // no version tick on a document that moved.
    assertEquals(SeatReclaim.Reason.NOT_BORROWED, response["reason"])
    assertNullWrites(db)
    assertEquals(1, db.txReads)
  }

  @Test
  fun aTakeoverThatLandedAfterThePreReadIsReclaimedAnyway() = runTest {
    val db = FakeReclaimDb(
      doc = doc(takeovers = emptyMap()),
      inTxDoc = doc(),
    )

    val response = reclaim(db)

    // The reverse direction of the same rule: the pre-read is a membership
    // check only, so a takeover that struck in between is seen rather than
    // missed — and the caller's own poll still returns their seat.
    assertEquals(true, response["reclaimed"])
    assertEquals(listOf("matches", "inactivity"), db.writes)
  }

  @Test
  fun aRetryOfADeliveredReclaimFindsNothingToReturn() = runTest {
    val first = FakeReclaimDb(doc())
    assertEquals(true, reclaim(first)["reclaimed"])

    // What the document holds after that write is what a redelivery reads: no
    // entry of mine, so a no-write. Two delivered reclaims would otherwise be
    // two version ticks and two cursor baselines.
    val redelivery = FakeReclaimDb(doc(takeovers = markerOf(first.written)))
    val response = reclaim(redelivery)

    assertEquals(SeatReclaim.Reason.NOT_BORROWED, response["reason"])
    assertNullWrites(redelivery)
  }

  // ── what a reclaim never touches ──────────────────────────────────────

  @Test
  fun aReclaimReadsNoTimeoutCounterAndNoHands() = runTest {
    val db = FakeReclaimDb(doc())

    assertEquals(true, reclaim(db)["reclaimed"])

    // RD20's separation, at the seam that could break it: a return of control is
    // not a timeout, so the counter is not even READ, let alone incremented.
    assertEquals(0, db.counterReads)
    assertEquals(0, db.counterExistsReads)
    assertTrue(db.writtenCounts.isEmpty())
    // Since S7 the private document IS read — exactly once, under the same
    // lock — but only to CARRY the takeover count into the replacement
    // (A7.3: a reclaim resets the clock, never the seat's takeover history).
    // The clock's validity is still not consulted: the read feeds the carry,
    // never the decision.
    assertEquals(1, db.inactivityReads)
    // Reconstruction is the resolver's job; a reclaim decides from the match
    // document alone, so no hand leaves the subcollection.
    assertEquals(0, db.handReads)
    assertEquals(1, db.preReads)
    assertEquals(1, db.txReads)
  }

  @Test
  fun aReclaimRecordsNoDepartureAndChangesNoMembership() = runTest {
    val db = FakeReclaimDb(doc())

    // RD31 revisit — the removal-shaped fields a reader might expect a takeover
    // lifecycle to write, asserted absent rather than described, because this
    // mechanism has no departure to record: the player never left, was never
    // removed, and the table never re-seated anyone.
    val response = reclaim(db)
    val written = db.written!!

    assertEquals(true, response["reclaimed"])
    assertEquals(listOf("matches", "inactivity"), db.writes)
    for (forbidden in listOf(
      "removedUids", "timeoutRemovedUids", "botSeats", "status",
      "players", "seats", "gameState", "departureReason",
    )) {
      assertTrue(forbidden !in written, "the reclaim write must not carry '$forbidden'")
    }
    assertTrue("reason" !in response, "a successful reclaim has no departure to name")
  }

  @Test
  fun theResponseGivesAwayNoTimingStateBeyondTheRetry() = runTest {
    val due = reclaim(FakeReclaimDb(doc()))
    val owed = reclaim(FakeReclaimDb(doc(cards = 3)))

    // Four keys on a return, five on a retry — and never the accumulation, the
    // cursor, the timeout count or the deadline anchor, which belong to the
    // private document and to no client at all.
    assertEquals(setOf("ok", "reclaimed", "matchId", "seatId"), due.keys)
    assertEquals(setOf("ok", "reclaimed", "reason", "message", "retryAfterMillis"), owed.keys)
    for (secret in listOf("accumulatedMillis", "openWindowStartedAt", "count", "updatedAt")) {
      assertTrue(secret !in due && secret !in owed, "the response must not leak '$secret'")
    }
  }

  /** One transaction, no side effects, and no document written. */
  private fun assertNullWrites(db: FakeReclaimDb) {
    assertTrue(db.writes.isEmpty(), "a refusal must write nothing, wrote ${db.writes}")
    assertEquals(1, db.transactions)
  }
}
