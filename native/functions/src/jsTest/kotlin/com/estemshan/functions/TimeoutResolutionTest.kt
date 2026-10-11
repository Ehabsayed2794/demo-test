package com.estemshan.functions

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.EmitResult
import com.estemshan.engine.emit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * S61 Stage 1 — the decision-timer authority's focused tests.
 *
 * What these cover, and why each one is here:
 *  - the deadline is DERIVED (S37's synchronized-countdown requirement): the
 *    anchor is the doc's own server timestamp and the duration is RD21's
 *    phase rule, so the four clients and this callable all see the same
 *    expiry;
 *  - the MEDIUM bot's decision lands in exactly the shape the human path
 *    writes, because the doc a client reads afterwards must be the doc it
 *    would have read had the player tapped;
 *  - no hand ever reaches a client — the hand is read server-side, and only
 *    the DECISION is written back or returned;
 *  - the trigger is host-less and idempotent: any seated player may fire it,
 *    a late or raced trigger re-anchors on the fresh document and writes
 *    nothing, and nothing requires the acting player's process to be alive.
 *
 * The specific bid/card the MEDIUM bot picks is deliberately NOT golden-tested
 * here: BidBrain/PlayBrain have their own suites, and the resolver's contract
 * is that whatever they produce is legal and applied — which is asserted by
 * re-emitting the decision through the real engine.
 */
class TimeoutResolutionTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  private val order = listOf("p1", "p2", "p3", "p4")
  private val seats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
  private val players = listOf("u1", "u2", "u3", "u4")

  /** A server timestamp's epoch-millis; the deadline anchor. */
  private val anchorMillis = 1_000_000L

  private fun gameState(round: Int): Map<String, Any?> =
    mapOf("initialized" to true, "dealtRound" to round)

  private fun storedCard(seat: String, card: Card, round: Int): Map<String, Any?> = mapOf(
    "seatId" to seat,
    "card" to mapOf("suit" to card.suit.name, "rank" to mapOf("v" to card.rank.v, "s" to card.rank.s)),
    "round" to round,
  )

  /**
   * A round-14 fast round (FULL), dealt, mid-estimate: the dealer is the seat
   * the engine waits on. [bidsOverride] models estimates already committed by
   * other seats; [biddingOpen] models the window still being open.
   */
  private fun fastRoundDoc(
    bidsOverride: Map<String, Int?> = emptyMap(),
    biddingOpen: Boolean = true,
    updatedAt: Long = anchorMillis,
    timerSeconds: Int = 15,
    status: String = "starting",
    dealtRound: Int = 14,
    currentRound: Int = 14,
    cardLog: List<Map<String, Any?>> = emptyList(),
    cardPhase: String? = null,
    turn: String? = null,
    biddingLog: List<Map<String, Any?>> = emptyList(),
    version: Int = 1,
  ): Map<String, Any?> = mutableMapOf(
    "roomId" to "room-1",
    "players" to players,
    "status" to status,
    "currentRound" to currentRound,
    "maxRounds" to 18,
    "completedRound" to 0,
    "extendedRounds" to emptyList<Int>(),
    "dealer" to seats.getValue("p1"),
    "turn" to turn,
    "seats" to seats,
    "version" to version,
    "biddingOpen" to biddingOpen,
    "bids" to linkedMapOf<String, Int?>(*order.map { it to bidsOverride[it] }.toTypedArray()),
    "lastBidSeat" to bidsOverride.keys.lastOrNull(),
    "cardLog" to cardLog,
    "lastCardSeat" to null,
    "cardPhase" to cardPhase,
    "biddingLog" to biddingLog,
    "gameState" to gameState(dealtRound),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to "RANKED",
    "botTier" to "MEDIUM",
    "botPersonality" to "BALANCED",
    "decisionTimerSeconds" to timerSeconds,
    "updatedAt" to updatedAt,
  )

  /** The round's dealt hands from the real dealer — 4×13 unique cards. */
  private fun dealtHands(seed: Long = 11L): Map<String, List<Card>> = Dealer.dealHands(seed)

  // ── deadline derivation (S37: synchronized, not client-cosmetic) ─────

  @Test
  fun aBiddingDecisionIsTimedAtTheConfiguredTimerPlusFive() {
    val pending = TimeoutResolution.pendingDecision(fastRoundDoc())!!
    assertEquals(TimeoutResolution.DecisionPhase.BIDDING, pending.phase)
    // initFastRound waits on the dealer for the first estimate.
    assertEquals("p1", pending.seatId)
    assertEquals(20, pending.durationSeconds) // 15s + 5s (RD21/S37)
  }

  @Test
  fun aCardDecisionIsTimedAtTheConfiguredTimerAlone() {
    val doc = fastRoundDoc(
      biddingOpen = false,
      cardPhase = "PLAY",
      turn = seats.getValue("p2"),
      bidsOverride = order.associateWith { 3 },
    )
    val pending = TimeoutResolution.pendingDecision(doc)!!
    assertEquals(TimeoutResolution.DecisionPhase.CARD, pending.phase)
    assertEquals("p2", pending.seatId)
    assertEquals(15, pending.durationSeconds) // no +5 on Card Play (S19/S37)
  }

  @Test
  fun theDeadlineIsTheAnchorPlusThePhaseDuration() {
    val pending = TimeoutResolution.Pending(
      TimeoutResolution.DecisionPhase.BIDDING, "p1", 20, anchorMillis,
    )
    assertFalse(TimeoutResolution.isExpired(pending, anchorMillis + 19_999))
    assertTrue(TimeoutResolution.isExpired(pending, anchorMillis + 20_000))
  }

  @Test
  fun anOutOfRangeTimerFallsBackToTheFifteenSecondDefault() {
    // A doc can never legally carry 99 — the rules deny it — but a read must
    // never surface a timer the host never picked (RoomDoc's own contract).
    assertEquals(15, TimeoutResolution.parseDecisionTimerSeconds(99))
    assertEquals(15, TimeoutResolution.parseDecisionTimerSeconds(null))
    assertEquals(20, TimeoutResolution.DecisionPhase.BIDDING.durationSeconds(15))
    assertEquals(25, TimeoutResolution.DecisionPhase.BIDDING.durationSeconds(20))
    assertEquals(20, TimeoutResolution.DecisionPhase.CARD.durationSeconds(20))
  }

  @Test
  fun aMissingServerTimestampAnchorIsNeverGuessed() {
    val doc = fastRoundDoc().toMutableMap().apply { remove("updatedAt") }
    assertNull(TimeoutResolution.pendingDecision(doc))
  }

  // ── nothing to time out ──────────────────────────────────────────────

  @Test
  fun aCompleteMatchHasNoTimedDecision() {
    assertNull(TimeoutResolution.pendingDecision(fastRoundDoc(status = "complete")))
  }

  @Test
  fun anUndealtRoundHasNoTimedDecision() {
    assertNull(TimeoutResolution.pendingDecision(fastRoundDoc(dealtRound = 13)))
  }

  @Test
  fun aPlayedOutRoundWaitingOnAdvanceHasNoTimedDecision() {
    // 52 round-tagged cards is maybeCompleteRound's own structural "round is
    // done" signal; what follows is advanceToNextRound, not a decision.
    val hands = dealtHands()
    val fullLog = order.flatMap { seat -> hands.getValue(seat).map { storedCard(seat, it, 14) } }
    assertEquals(52, fullLog.size)
    // Bidding is closed and the round is played out — the only thing left is
    // advanceToNextRound, which is not a decision, so there is nothing to time.
    val doc = fastRoundDoc(
      biddingOpen = false,
      cardLog = fullLog,
      cardPhase = "RESOLVING",
      turn = seats.getValue("p1"),
    )
    assertNull(TimeoutResolution.pendingDecision(doc))
  }

  // ── the MEDIUM bot's decision, in the human path's own write shape ──

  @Test
  fun anExpiredEstimateIsWrittenExactlyAsSubmitBidWouldWriteIt() {
    val doc = fastRoundDoc()
    val hands = dealtHands()
    val pending = TimeoutResolution.pendingDecision(doc)!!
    val resolution = TimeoutResolution.resolveExpired(doc, hands, pending)

    assertIs<TimeoutResolution.Resolution.Applied>(resolution)
    val updates = resolution.write.updates
    assertEquals(
      setOf("bids", "biddingOpen", "lastBidSeat", "version", "updatedAt"),
      updates.keys,
    )
    assertEquals("p1", updates["lastBidSeat"])
    assertEquals(2, updates["version"])

    val bids = updates["bids"] as Map<*, *>
    val estimate = bids["p1"] as Int
    assertTrue(estimate in 0..13, "an estimate is always 0..13")

    // The decision is legal: feeding it back through the real engine accepts it.
    val state = TimeoutResolution.replayBidding(doc, order, 14)!!
    val reemit = emit(state, BiddingIntent.FinalEstimate("p1", estimate))
    assertTrue(reemit is EmitResult.Applied || reemit is EmitResult.Completed)
  }

  @Test
  fun anExpiredOpeningCardPublishesTheTurnAndPlaysInOneWrite() {
    val doc = fastRoundDoc(
      biddingOpen = false,
      bidsOverride = order.associateWith { 3 },
    )
    val hands = dealtHands()
    val pending = TimeoutResolution.pendingDecision(doc)!!
    assertEquals(TimeoutResolution.DecisionPhase.CARD, pending.phase)
    assertTrue(pending.seatId in order)

    val resolution = TimeoutResolution.resolveExpired(doc, hands, pending)
    assertIs<TimeoutResolution.Resolution.Applied>(resolution)
    val updates = resolution.write.updates
    assertEquals(
      setOf("cardLog", "lastCardSeat", "turn", "cardPhase", "version", "updatedAt"),
      updates.keys,
    )
    // The publication the card ruleset demands and the card itself land
    // together, so a seat whose app died before publishing still unblocks.
    assertEquals("PLAY", updates["cardPhase"])
    assertNotNull(updates["turn"])

    val entry = arrayUnionEntries(updates["cardLog"]).single()
    assertEquals(pending.seatId, entry["seatId"])
    assertEquals(14, entry["round"])
    val played = entry["card"] as Map<*, *>
    val hand = hands.getValue(pending.seatId)
    assertTrue(hand.any { it.suit.name == played["suit"] && it.rank.v == (played["rank"] as Map<*, *>)["v"] })
  }

  // ── hand privacy: the hand never reaches a client ────────────────────

  @Test
  fun neitherTheWriteNorTheResponseCarriesAHand() {
    val doc = fastRoundDoc()
    val hands = dealtHands()
    val resolution = TimeoutResolution.resolveExpired(
      doc, hands, TimeoutResolution.pendingDecision(doc)!!,
    )
    assertIs<TimeoutResolution.Resolution.Applied>(resolution)

    // The write touches only the decision fields; the acknowledgement reports
    // the move itself, never the thirteen cards it came from.
    assertFalse(resolution.write.updates.containsKey("hands"))
    assertFalse(resolution.write.decided.containsKey("hand"))
    assertFalse(resolution.write.decided.containsKey("hands"))
    val decided = resolution.write.decided
    assertEquals(4, decided.size) // phase, seatId, estimate, botTier
    assertEquals("MEDIUM", decided["botTier"])
  }

  @Test
  fun aCardDecisionReportsExactlyOneCardNeverTheHand() {
    val doc = fastRoundDoc(biddingOpen = false, bidsOverride = order.associateWith { 3 })
    val resolution = TimeoutResolution.resolveExpired(
      doc, dealtHands(), TimeoutResolution.pendingDecision(doc)!!,
    )
    assertIs<TimeoutResolution.Resolution.Applied>(resolution)
    val card = resolution.write.decided["card"] as Map<*, *>
    assertEquals(2, card.size) // suit + rank — one card, one shape
    assertFalse(resolution.write.decided.containsKey("hand"))
  }

  // ── fail closed, never invent a decision ─────────────────────────────

  @Test
  fun aSeatWithoutADealtHandFailsClosed() {
    val doc = fastRoundDoc()
    val hands = dealtHands() - "p1" // the acting seat's hand is missing
    val resolution = TimeoutResolution.resolveExpired(
      doc, hands, TimeoutResolution.pendingDecision(doc)!!,
    )
    assertIs<TimeoutResolution.Resolution.NoWrite>(resolution)
    assertEquals(TimeoutResolution.Reason.HISTORY_UNAVAILABLE, resolution.reason)
  }

  // ── the orchestration seam ───────────────────────────────────────────

  /** Records every side effect so a test can prove a no-op wrote nothing. */
  private class FakeTimeoutDb(
    private val doc: Map<String, Any?>?,
    private val hands: Map<String, List<Card>>,
    /** The document the transaction sees; defaults to the pre-check doc. A
     *  different value models a decision landing between the two reads. */
    private val inTxDoc: Map<String, Any?>? = null,
  ) : TimeoutDb {
    var handReads = 0
      private set
    var written: Map<String, Any?>? = null
      private set

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = doc
    override suspend fun readHands(matchId: String, round: Int, seats: List<String>): Map<String, List<Card>> {
      handReads++
      return hands.filterKeys { it in seats }
    }
    override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T {
      val txDoc = inTxDoc ?: doc
      return block(object : TimeoutDb.TimeoutTx {
        override suspend fun readMatch(matchId: String): Map<String, Any?>? = txDoc
        override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
          written = updates
        }
      })
    }
  }

  private fun fakeRequest(uid: String?, matchId: String = "m1"): CallableRequest =
    object : CallableRequest {
      override val data: Any? = mapOf("matchId" to matchId)
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

  /**
   * Reads back the entries an arrayUnion transform appends.
   *
   * `FieldValue.arrayUnion(entry)` is a firebase-admin transform OBJECT, not an
   * array, so the log update cannot be inspected with a plain cast — it has to
   * be expanded through the SDK's own `elements` field. That field holds the
   * very Kotlin maps decideCard handed in, which is what makes the write-shape
   * assertions below observable at all. Test-only, against the pinned SDK.
   */
  private fun arrayUnionEntries(value: Any?): List<Map<String, Any?>> {
    val transform = value?.asDynamic() ?: return emptyList()
    assertEquals("FieldValue.arrayUnion", transform.methodName, "the log update must be an arrayUnion")
    @Suppress("UNCHECKED_CAST")
    return (transform.elements as Array<*>).map { it as Map<String, Any?> }
  }

  @Test
  fun anUnexpiredDeadlineWritesNothingAndReadsNoHands() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(), dealtHands())
    val oneMillisecondEarly = DecisionClock { anchorMillis + 19_999 }

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), oneMillisecondEarly)

    assertEquals(false, response["resolved"])
    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, response["reason"])
    assertEquals(0, db.handReads)
    assertNull(db.written)
  }

  @Test
  fun anExpiredDecisionIsResolvedByAnySeatedPlayer() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(), dealtHands())
    val expired = DecisionClock { anchorMillis + 20_000 }

    // u3 triggers it — not the acting seat, whose app may be gone entirely.
    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expired)

    assertEquals(true, response["resolved"])
    assertEquals("p1", response["seatId"])
    assertEquals("BIDDING", response["deadlinePhase"])
    assertEquals(20, response["deadlineSeconds"])
    assertNotNull(db.written)
    assertEquals(1, db.handReads)
  }

  @Test
  fun aRacedDecisionReanchorsOnTheFreshDocumentAndWritesNothing() = runTest {
    // The player decided a moment before the transaction: the in-tx document
    // already carries p1's estimate at a later server timestamp, so the
    // engine now waits on p2 whose deadline is still in the future.
    val racedDoc = fastRoundDoc(
      bidsOverride = mapOf("p1" to 4),
      updatedAt = anchorMillis + 5_000,
    )
    val db = FakeTimeoutDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      inTxDoc = racedDoc,
    )
    val expiredForTheOldDecision = DecisionClock { anchorMillis + 20_000 }

    val response = resolveTimeoutOnce(db, fakeRequest("u2"), expiredForTheOldDecision)

    assertEquals(false, response["resolved"])
    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, response["reason"])
    assertNull(db.written)
  }

  @Test
  fun aPlayerOutsideTheMatchCannotTriggerResolution() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(), dealtHands())
    val expired = DecisionClock { anchorMillis + 20_000 }

    val error = assertFailsWith<Throwable> {
      resolveTimeoutOnce(db, fakeRequest("not-a-player"), expired)
    }
    assertNotNull(error.message)
    assertTrue(error.message!!.contains("not a player in this match"))
    assertNull(db.written)
  }

  @Test
  fun anUnauthenticatedCallerIsRejectedBeforeAnyRead() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(), dealtHands())

    assertFailsWith<Throwable> {
      resolveTimeoutOnce(db, fakeRequest(null), DecisionClock { anchorMillis + 20_000 })
    }
    assertNull(db.written)
  }

  @Test
  fun aMatchIdIsRequired() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(), dealtHands())
    val request = object : CallableRequest {
      override val data: Any? = null
      override val auth: AuthData? = object : AuthData {
        override val uid: String = "u1"
        override val token: AuthToken? = null
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }
    assertFailsWith<Throwable> { resolveTimeoutOnce(db, request, DecisionClock { anchorMillis }) }
    assertNull(db.written)
  }
}
