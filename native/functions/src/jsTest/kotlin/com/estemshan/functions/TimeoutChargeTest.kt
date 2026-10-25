package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.MatchMode
import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S5 (A8.3 / Q37 + RD31 / Q14d(1), closing IB-22) — the 15-timeout removal's
 * own Koz charge, recorded at the moment the removal removes the seat.
 *
 * What these pin, and why each one is here:
 *  - the AMOUNT is the engine table's, per mode: ROOM 1, RANKED 2, UNRANKED 2
 *    (LeaveMatch.decideLeave × FIFTEEN_TIMEOUT — T-15's row), with the
 *    absent-mode legacy document charging ROOM's 1 the way every other mode
 *    reader in this codebase treats a pre-S43 doc;
 *  - the TIMING is the removal's own: the charge rides the SAME transaction
 *    as `timeoutRemovedUids` and the deciding move, so it can never land
 *    without the removal and a non-removal timeout (or a fail-closed counter)
 *    records no charge at all;
 *  - the IDEMPOTENCE is the removal's own: once the uid is in
 *    `timeoutRemovedUids` the seat is bot-held, the counter branch never runs
 *    again, and a replay of the same removal cannot charge twice — the
 *    Stage-1 proof (every write re-anchors `updatedAt`; the transaction
 *    re-derives from the committed doc) carries the charge with it;
 *  - the ISOLATION is per mechanism: Vote Kick's `removedUids`/`botSeats` and
 *    the leave path's `leftUids` are never touched, and a seat Vote Kick
 *    already removed is never charged by this path;
 *  - the SHAPE is admin-only like `timeoutRemovedUids` itself: a uid → Koz
 *    map on the match document, carried forward verbatim so two removals
 *    accumulate and a same-uid rewrite is value-identical, never additive.
 *
 * Settlement's consumption of the charge is another lane and is deliberately
 * NOT modelled here — this suite stops at "the charge is recorded, once, in
 * the ruled amount".
 */
class TimeoutChargeTest {

  // ── fixtures (mirrored from TimeoutResolutionTest, which this suite must
  // not edit; trimmed to the removal path's own needs) ────────────────────

  private val order = listOf("p1", "p2", "p3", "p4")
  private val seats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
  private val players = listOf("u1", "u2", "u3", "u4")

  /** A server timestamp's epoch-millis; the deadline anchor. */
  private val anchorMillis = 1_000_000L

  /** Past every phase window the fixtures derive (20 s BIDDING at 15 s). */
  private val expiredClock = DecisionClock { anchorMillis + 20_000 }

  /**
   * A round-14 fast round (FULL), dealt, waiting on the dealer's first
   * estimate — the state whose expiry is p1's (u1's) timed decision. The
   * other seats' estimates are committed so ONE resolution is the whole
   * story for this suite's replays.
   */
  private fun removalDoc(mode: String?): Map<String, Any?> {
    val doc = mutableMapOf(
      "roomId" to "room-1",
      "players" to players,
      "status" to "starting",
      "currentRound" to 14,
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
      "cardLog" to emptyList<Map<String, Any?>>(),
      "lastCardSeat" to null,
      "cardPhase" to null,
      "biddingLog" to emptyList<Map<String, Any?>>(),
      "gameState" to mapOf("initialized" to true, "dealtRound" to 14),
      "gameType" to "FULL",
      "scoringMode" to "NORMAL",
      "botTier" to "MEDIUM",
      "botPersonality" to "BALANCED",
      "decisionTimerSeconds" to 15,
      "updatedAt" to anchorMillis,
    )
    // Absent mode ⇒ the pre-S43 legacy document; every other test names it.
    if (mode != null) doc["mode"] = mode
    return doc
  }

  /** The round's dealt hands from the real dealer — 4×13 unique cards. */
  private fun dealtHands(): Map<String, List<Card>> = Dealer.dealHands(11L)

  /**
   * The document as Firestore would hold it after [updates] committed: every
   * entry overlaid, with the serverTimestamp sentinel replaced by a fresh
   * Long anchor — the write re-anchored `updatedAt`, which is exactly the
   * Stage-1 idempotency mechanism a replay has to survive.
   */
  private fun committedDoc(
    doc: Map<String, Any?>,
    updates: Map<String, Any?>,
    newAnchorMillis: Long,
  ): Map<String, Any?> {
    val out = doc.toMutableMap()
    out.putAll(updates)
    out["updatedAt"] = newAnchorMillis
    return out
  }

  private class FakeTimeoutDb(
    private val doc: Map<String, Any?>?,
    private val hands: Map<String, List<Card>>,
    private val storedCounts: Map<String, Any?> = emptyMap(),
  ) : TimeoutDb {
    var written: Map<String, Any?>? = null
      private set
    val writtenCounts: MutableMap<String, Any?> = mutableMapOf()

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
      })
  }

  private fun fakeRequest(uid: String?, matchId: String = "m1"): CallableRequest =
    object : CallableRequest {
      // The Functions runtime delivers data as a PLAIN JS OBJECT, never a
      // Kotlin map — json() is the runtime shape (TimeoutResolutionTest's idiom).
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

  // ── the ruled amounts (T-15's row, A8.3 / Q37 + RD31 / Q14d(1)) ────────

  @Test
  fun thePerModeTableMatchesTheRuledAmounts() {
    assertEquals(1, TimeoutResolution.removalChargeKoz(MatchMode.ROOM))
    assertEquals(2, TimeoutResolution.removalChargeKoz(MatchMode.RANKED))
    assertEquals(2, TimeoutResolution.removalChargeKoz(MatchMode.UNRANKED))
  }

  @Test
  fun theFifteenthTimeoutRecordsOneKozInRoom() = runTest {
    val db = FakeTimeoutDb(removalDoc(mode = "ROOM"), dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(mapOf("u1" to 1), db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  @Test
  fun theFifteenthTimeoutRecordsTwoKozInRanked() = runTest {
    val db = FakeTimeoutDb(removalDoc(mode = "RANKED"), dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(mapOf("u1" to 2), db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  @Test
  fun theFifteenthTimeoutRecordsTwoKozInUnranked() = runTest {
    val db = FakeTimeoutDb(removalDoc(mode = "UNRANKED"), dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(mapOf("u1" to 2), db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  @Test
  fun aLegacyDocWithNoModeChargesTheRoomAmount() = runTest {
    // Pre-S43 documents carry no `mode`; every mode reader in this codebase
    // (Settlement.parseMatchMode, RoomDoc.parseMatchMode, storedMatchMode)
    // reads those as ROOM, so the removal charges ROOM's 1 Koz.
    val db = FakeTimeoutDb(removalDoc(mode = null), dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(mapOf("u1" to 1), db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  @Test
  fun anUnrecognisedModeFallsBackToTheRoomAmount() = runTest {
    val db = FakeTimeoutDb(removalDoc(mode = "WEIRD"), dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(mapOf("u1" to 1), db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  // ── timing: with the removal, never without it ─────────────────────────

  @Test
  fun theChargeRidesTheSameWriteAsTheRemovalAndTheDecidingMove() = runTest {
    val db = FakeTimeoutDb(removalDoc(mode = "RANKED"), dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    val updates = db.written!!
    // The removal list and the charge map land in ONE update, together with
    // the deciding move's own fields — one transaction, atomically.
    assertEquals(listOf("u1"), updates["timeoutRemovedUids"])
    assertEquals(mapOf("u1" to 2), updates[TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
    assertEquals(15, db.writtenCounts["u1"])
    assertTrue("bids" in updates && "version" in updates && "updatedAt" in updates)
  }

  @Test
  fun aMidMatchTimeoutRecordsNoCharge() = runTest {
    val db = FakeTimeoutDb(removalDoc(mode = "ROOM"), dealtHands(), storedCounts = mapOf("u1" to 7))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // The 8th timeout removes nobody: the decision lands, the counter ticks,
    // and no charge field is even present in the write.
    assertEquals(8, db.writtenCounts["u1"])
    assertNull(db.written!!["timeoutRemovedUids"])
    assertNull(db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  @Test
  fun aFailClosedCounterRecordsNoCharge() = runTest {
    val db = FakeTimeoutDb(
      removalDoc(mode = "RANKED"),
      dealtHands(),
      storedCounts = mapOf("u1" to "not-a-number"),
    )

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // Corrupt state can never buy a removal — or a charge.
    assertNull(db.writtenCounts["u1"])
    assertNull(db.written!!["timeoutRemovedUids"])
    assertNull(db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  @Test
  fun aRemovalChargeNeverWritesAnotherMechanismsState() = runTest {
    val db = FakeTimeoutDb(removalDoc(mode = "ROOM"), dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // The timeout mechanism's OWN fields only — never Vote Kick's
    // removedUids/botSeats, never the leave path's leftUids.
    assertNull(db.written!!["removedUids"])
    assertNull(db.written!!["botSeats"])
    assertNull(db.written!!["leftUids"])
  }

  // ── idempotence: a replay of the same removal cannot charge twice ──────

  @Test
  fun aReplayOfTheSameRemovalCannotChargeTwice() = runTest {
    val first = FakeTimeoutDb(
      removalDoc(mode = "ROOM"),
      dealtHands(),
      storedCounts = mapOf("u1" to 14),
    )
    resolveTimeoutOnce(first, fakeRequest("u3"), expiredClock)
    val committed = committedDoc(
      doc = removalDoc(mode = "ROOM"),
      updates = first.written!!,
      newAnchorMillis = anchorMillis + 20_000,
    )
    // The committed document now carries the removal AND its charge.
    assertEquals(mapOf("u1" to 1), committed[TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])

    // The same deadline's re-resolution re-reads that committed doc. u1 is
    // in timeoutRemovedUids, so the seat is bot-held: the counter branch —
    // and with it the charge branch — never runs again. Whether the replay
    // finds a later decision to make or nothing to do, it writes no charge.
    val replay = FakeTimeoutDb(
      committed,
      dealtHands(),
      storedCounts = mapOf("u1" to 15), // the count the first write committed
    )
    val replayClock = DecisionClock { anchorMillis + 60_000 }
    resolveTimeoutOnce(replay, fakeRequest("u3"), replayClock)

    assertNull(replay.writtenCounts["u1"])
    assertTrue(
      replay.written?.get(TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD) == null,
      "a replay must not re-write a removal charge: ${replay.written}",
    )
    assertTrue(
      replay.written?.get("timeoutRemovedUids") == null,
      "a replay must not re-write the removal: ${replay.written}",
    )
  }

  @Test
  fun aSeatVoteKickAlreadyRemovedIsNeverChargedByThisPath() = runTest {
    // Vote Kick got there first: the seat is bot-held, so the counter counts
    // nothing, the 15th never fires for it, and this path records no charge
    // — one removal event, one charge, owned by the mechanism that made it.
    val doc = removalDoc(mode = "RANKED").toMutableMap()
    doc["botSeats"] = listOf("p1")
    val db = FakeTimeoutDb(doc, dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertNull(db.writtenCounts["u1"])
    assertNull(db.written!!["timeoutRemovedUids"])
    assertNull(db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD])
  }

  // ── the persisted shape: uid → Koz, carried forward verbatim ───────────

  @Test
  fun anEarlierRemovalsChargeIsCarriedVerbatim() = runTest {
    // u4 was removed in an earlier round (RANKED ⇒ 2); u1's 15th lands now.
    // The write carries u4's entry untouched and adds u1's — one map, both
    // charges, no clobber.
    val doc = removalDoc(mode = "RANKED").toMutableMap()
    doc["timeoutRemovedUids"] = listOf("u4")
    doc[TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD] = mapOf("u4" to 2)
    val db = FakeTimeoutDb(doc, dealtHands(), storedCounts = mapOf("u1" to 14))

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(
      mapOf("u4" to 2, "u1" to 2),
      db.written!![TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD],
    )
    assertEquals(listOf("u4", "u1"), db.written!!["timeoutRemovedUids"])
  }

  @Test
  fun aSameUidRewriteIsValueIdenticalNeverAdditive() {
    // The write is a SET by uid, not an append: re-deriving u1's charge over
    // an already-recorded u1 cannot double it — the amount is a pure
    // function of the create-only mode.
    val doc = removalDoc(mode = "ROOM").toMutableMap()
    doc["timeoutRemovedUids"] = listOf("u1")
    doc[TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD] = mapOf("u1" to 1)

    assertEquals(
      mapOf("u1" to 1),
      TimeoutResolution.removalChargeWrite(doc, "u1", koz = 1),
    )
  }

  @Test
  fun aGarbageChargeMapIsParsedFailClosed() {
    // Not a map at all ⇒ nothing recorded.
    assertEquals(
      emptyMap(),
      TimeoutResolution.timeoutRemovedCharges(mapOf(TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD to listOf("u1"))),
    )
    // Malformed entries drop; well-formed ones survive — a dropped entry
    // means "not re-recorded", never a guessed amount.
    assertEquals(
      mapOf("u2" to 2),
      TimeoutResolution.timeoutRemovedCharges(
        mapOf(
          TimeoutResolution.TIMEOUT_REMOVED_CHARGES_FIELD to
            mapOf("u2" to 2, "u4" to "not-a-number"),
        ),
      ),
      )
  }
}

