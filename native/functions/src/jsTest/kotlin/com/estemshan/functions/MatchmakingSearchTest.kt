package com.estemshan.functions

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S56 — the Ranked queue's authority half, on a fake [MatchmakingSearchDb].
 *
 * The pure policy (RD9's pool, GM5's GameType) is already pinned by
 * `MatchmakingPoolTest` and is NOT re-tested here; what is pinned here is the
 * part the policy alone cannot prove — that the SERVER is the only thing that
 * decides who is seated with whom.
 *
 * The eight properties this story has to leave provable:
 *
 *  1. **A client cannot name a tier, a pool or a player.** The request guard
 *     refuses those keys; it does not ignore them. A silently-ignored key is
 *     a key a client keeps sending while believing it works.
 *  2. **Directionality.** A Gold searcher may be served a Silver; a Silver
 *     searcher is never served a Gold. RD9 states it as a property of the
 *     POOL, and the pool belongs to whoever searched first.
 *  3. **GM5 is exact equality.** A Full search and a Mini search never share
 *     a table, and the GameType never widens the tier band.
 *  4. **A search resolves AT MOST ONCE.** Re-running the pass over the same
 *     queue state forms no second table, and a re-poll answers the same table.
 *  5. **Cancel is idempotent and cannot un-resolve.** A waiting search is
 *     removed; a resolved one stays resolved, because a table that formed
 *     cannot be un-formed.
 *  6. **A corrupt queue document aborts the pass**, with a fixed message —
 *     never repaired by guessing.
 *  7. **No error message carries a stored value or another player's identity.**
 *  8. **The resolver is deterministic**: the same queue state forms the same
 *     pairs, in the same order, every time.
 */
class MatchmakingSearchTest {

  // ── fixtures ─────────────────────────────────────────────────────────

  private val gold = "uid-gold"
  private val silver = "uid-silver"

  /** A mutable clock, so a lease can be aged without waiting for it. */
  private class TestClock(var now: Long = 1_000L) : DecisionClock {
    override fun nowMillis(): Long = now
  }

  /**
   * The queue's Firestore seam, on a fake.
   *
   * The writes are BUFFERED and applied only when the transaction completes,
   * so a transaction that throws leaves nothing behind — the same guarantee
   * Firestore gives, and the reason "a corrupt document writes nothing" is a
   * real assertion here and not an artefact of the fake.
   *
   * The event log records every touch in order, so read-before-write is
   * assertable rather than assumed, and [lastTransaction] isolates the
   * transaction under inspection from the ones around it.
   */
  private class FakeSearchDb(
    val searches: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
    val profiles: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
  ) : MatchmakingSearchDb {

    val events = mutableListOf<String>()
    val rooms = mutableMapOf<String, Map<String, Any?>>()
    private var roomCounter = 0

    /**
     * Uids that VANISH the moment a transaction reads them.
     *
     * This is the race the resolver is written for: the pass saw the document
     * in its scan, and by the time the transaction read it the other player
     * had already been seated elsewhere. The read answers "no document", the
     * pass is told "no table", and it must move on rather than spin.
     */
    val vanishInsideTransaction = mutableSetOf<String>()

    /** The events of the most recent transaction, and nothing after it. */
    var lastTransaction: List<String> = emptyList()
      private set

    /** How many transactions the pass opened. */
    val transactionCount: Int get() = events.count { it == "tx" }

    val writes: Int get() = events.count { it.startsWith("write") || it.startsWith("resolve") || it.startsWith("delete") }

    override suspend fun readSearch(uid: String): Map<String, Any?>? {
      events += "read:$uid"
      return searches[uid]
    }

    override suspend fun listWaitingSearches(limit: Int): List<SearchDocument> {
      events += "list"
      return searches.entries
        .filter { it.value["status"] == "WAITING" }
        .take(limit)
        .map { SearchDocument(it.key, it.value) }
    }

    override fun newRoomId(): String {
      roomCounter += 1
      return "room-$roomCounter"
    }

    override suspend fun <T> transact(block: suspend (MatchmakingSearchDb.Tx) -> T): T {
      val start = events.size
      events += "tx"
      val pending = mutableListOf<() -> Unit>()
      val outcome = block(object : MatchmakingSearchDb.Tx {
        override suspend fun readSearch(uid: String): Map<String, Any?>? {
          events += "read:$uid"
          if (vanishInsideTransaction.remove(uid)) {
            searches.remove(uid)
            return null
          }
          return searches[uid]
        }

        override suspend fun readProfile(uid: String): Map<String, Any?>? {
          events += "profile:$uid"
          return profiles[uid]
        }

        override fun putSearch(uid: String, fields: Map<String, Any?>) {
          events += "write-search:$uid"
          pending += { searches[uid] = fields }
        }

        override fun renewSearch(uid: String, heartbeatMillis: Long) {
          events += "renew:$uid"
          pending += {
            searches[uid] = (searches[uid] ?: emptyMap()) + ("heartbeatAt" to heartbeatMillis)
          }
        }

        override fun resolveSearch(uid: String, tableId: String) {
          events += "resolve:$uid"
          pending += {
            searches[uid] = (searches[uid] ?: emptyMap()) +
              ("status" to "RESOLVED") + ("tableId" to tableId)
          }
        }

        override fun deleteSearch(uid: String) {
          events += "delete:$uid"
          pending += { searches.remove(uid) }
        }

        override fun createRoom(roomId: String, fields: Map<String, Any?>) {
          events += "write-room:$roomId"
          pending += { rooms[roomId] = fields }
        }
      })
      lastTransaction = events.subList(start, events.size).toList()
      for (apply in pending) apply()
      return outcome
    }
  }

  /**
   * A profile exactly as S50's write path stores one: the rung and its `rp`
   * agree (RD3), because a fixture that breaks that is indistinguishable at
   * the gate from a genuinely corrupt document.
   */
  private fun profile(tier: String, rp: Int): Map<String, Any?> = linkedMapOf(
    "tier" to tier,
    "division" to "",
    "rp" to rp,
    "seasonId" to "s1",
  )

  private fun db(): FakeSearchDb = FakeSearchDb().apply {
    profiles[gold] = profile("Gold", 575)
    profiles[silver] = profile("Silver", 250)
  }

  private fun request(
    uid: String,
    fields: Map<String, Any?> = linkedMapOf("gameType" to "FULL"),
  ): CallableRequest {
    val data: dynamic = js("({})")
    for ((key, value) in fields) data[key] = value
    return object : CallableRequest {
      override val data: Any? = data
      override val auth: AuthData? = object : AuthData {
        override val uid: String = uid
        override val token: AuthToken? = null
      }
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }
  }

  /** A refusal, sampled from ONE call. */
  private class Refusal(val code: String, val reason: String?, val message: String)

  private suspend fun refused(block: suspend () -> Any?): Refusal {
    try {
      block()
    } catch (e: Throwable) {
      val code = e.asDynamic().code as? String
        ?: throw AssertionError("expected an HttpsError, got $e")
      return Refusal(code, e.asDynamic().details?.reason as? String, e.message ?: "")
    }
    throw AssertionError("expected HttpsError")
  }

  private suspend fun enqueue(
    db: FakeSearchDb,
    uid: String,
    gameType: String = "FULL",
    clock: TestClock = TestClock(),
  ): Map<String, Any?> = enqueueSearchOnce(
    db,
    request(uid, linkedMapOf("gameType" to gameType)),
    clock,
  )

  /** The pairs a queue formed: each table's two players, sorted. */
  private fun pairsOf(db: FakeSearchDb): List<List<String>> = db.rooms.values
    .map { room -> (room["players"] as List<*>).map { it as String }.sorted() }
    .sortedBy { it.joinToString(",") }

  // ── 1. the request guard ─────────────────────────────────────────────

  @Test
  fun aClientSuppliedTierPoolOrIdentityIsRefusedAndNothingIsQueued() = runTest {
    val forbidden = listOf(
      "uid" to "somebody-else",
      "playerId" to "somebody-else",
      "tier" to "Gold",
      "division" to "I",
      "rank" to "Gold I",
      "rp" to 3000,
      "pool" to "Gold",
      "minTier" to "Silver",
      "maxTier" to "King",
      "optUp" to true,
      "tierClaim" to "Gold",
      "rankClaim" to "Gold I",
    )

    for ((key, value) in forbidden) {
      val db = db()
      val clock = TestClock()

      val refusal = refused {
        enqueueSearchOnce(
          db,
          request(gold, linkedMapOf("gameType" to "FULL", key to value)),
          clock,
        )
      }

      assertEquals("permission-denied", refusal.code, "'$key' is server-derived")
      assertEquals("FORBIDDEN_KEY", refusal.reason, "'$key' must name its stable reason")
      assertFalse(
        refusal.message.contains("$value"),
        "'$key' must not echo its value: ${refusal.message}",
      )
      assertTrue(db.searches.isEmpty(), "'$key' must be refused before anything is queued")
      assertEquals(0, db.writes, "'$key' must be refused before any write")
    }
  }

  @Test
  fun thePollAndTheCancelRefuseAClientSuppliedFieldToo() = runTest {
    for (callable in listOf("pollSearch", "cancelSearch")) {
      val db = db()
      db.searches[gold] = waiting(gold, "Gold")

      val refusal = refused {
        if (callable == "pollSearch") {
          pollSearchOnce(db, request(gold, linkedMapOf("uid" to "somebody-else")), TestClock())
        } else {
          cancelSearchOnce(db, request(gold, linkedMapOf("tier" to "Gold")))
        }
      }

      assertEquals("permission-denied", refusal.code, callable)
      assertEquals("FORBIDDEN_KEY", refusal.reason, callable)
      assertFalse(refusal.message.contains("somebody-else"), callable)
      assertFalse(refusal.message.contains("Gold"), "$callable must not echo a tier")
      assertNotNull(db.searches[gold], "$callable must not remove the search")
    }
  }

  @Test
  fun thePollAndTheCancelRefuseAnUnknownField() = runTest {
    for (callable in listOf("pollSearch", "cancelSearch")) {
      val db = db()

      val refusal = refused {
        if (callable == "pollSearch") {
          pollSearchOnce(db, request(gold, linkedMapOf("seasonId" to "s1")), TestClock())
        } else {
          cancelSearchOnce(db, request(gold, linkedMapOf("seasonId" to "s1")))
        }
      }

      assertEquals("invalid-argument", refusal.code, callable)
      assertEquals("INVALID_ARGUMENT", refusal.reason, callable)
      assertTrue(refusal.message.contains("'seasonId'"), "$callable must name the key")
      assertFalse(refusal.message.contains("s1"), "$callable must not echo the value")
    }
  }

  @Test
  fun anUnauthenticatedCallerIsRefusedBeforeAnyRead() = runTest {
    val db = db()
    val anonymous = object : CallableRequest {
      override val data: Any? = js("({ gameType: 'FULL' })")
      override val auth: AuthData? = null
      override val instanceIdToken: String? = null
      override val rawRequest: Any? = null
      override val app: Any? = null
    }

    val refusal = refused { enqueueSearchOnce(db, anonymous, TestClock()) }

    assertEquals("unauthenticated", refusal.code)
    assertEquals(emptyList(), db.events)
  }

  // ── 2. the direction of the pool ─────────────────────────────────────

  @Test
  fun aGoldSearcherIsServedASilver() = runTest {
    val db = db()
    val clock = TestClock()

    // The Gold search arrives first, so the Gold pool owns the pairing.
    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    assertEquals(1, db.rooms.size, "a Gold pool admits a Silver")
    val table = db.rooms.values.first()
    assertEquals(listOf(gold, silver), table["players"])
    assertEquals("RESOLVED", db.searches.getValue(gold)["status"])
    assertEquals("RESOLVED", db.searches.getValue(silver)["status"])
    assertEquals(db.searches.getValue(gold)["tableId"], db.searches.getValue(silver)["tableId"])
  }

  @Test
  fun aSilverSearcherIsNeverServedAGold() = runTest {
    val db = db()
    val clock = TestClock()

    // The Silver search arrives first, so the SILVER pool owns the pairing —
    // and a Silver pool never admits a Gold.
    enqueue(db, silver, clock = clock)
    clock.now += 1_000
    enqueue(db, gold, clock = clock)

    assertTrue(db.rooms.isEmpty(), "a Silver searcher must never be served a Gold")
    assertEquals("WAITING", db.searches.getValue(silver)["status"])
    assertEquals("WAITING", db.searches.getValue(gold)["status"])
  }

  @Test
  fun twoTiersApartNeverShareATable() = runTest {
    val db = db()
    val clock = TestClock()
    db.profiles["uid-platinum"] = profile("Platinum", 1000)

    enqueue(db, "uid-platinum", clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    assertTrue(db.rooms.isEmpty(), "the band is one tier, never two")
  }

  @Test
  fun aPlayerIsNeverPairedWithItself() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)

    assertTrue(db.rooms.isEmpty(), "one search is not a table")
    assertEquals("WAITING", db.searches.getValue(gold)["status"])
  }

  // ── 3. GM5: the GameType is exact equality ───────────────────────────

  @Test
  fun aFullSearchAndAMiniSearchNeverResolveTogether() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, gameType = "FULL", clock = clock)
    clock.now += 1_000
    enqueue(db, silver, gameType = "MINI", clock = clock)

    assertTrue(db.rooms.isEmpty(), "FULL and MINI never share a table")
    assertEquals("WAITING", db.searches.getValue(gold)["status"])
    assertEquals("WAITING", db.searches.getValue(silver)["status"])
  }

  @Test
  fun theGameTypeNeverWidensTheTierBand() = runTest {
    // A Mini Gold and a Mini Silver are exactly as pairable as a Full Gold
    // and a Full Silver: GM5 is orthogonal to the pool.
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, gameType = "MINI", clock = clock)
    clock.now += 1_000
    enqueue(db, silver, gameType = "MINI", clock = clock)

    assertEquals(1, db.rooms.size, "the GameType never narrows the band")
    assertEquals("MINI", db.rooms.values.first()["gameType"])
  }

  // ── 4. a search resolves at most once ────────────────────────────────

  @Test
  fun aSecondPassOverTheSameQueueStateFormsNoSecondTable() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)
    assertEquals(1, db.rooms.size)

    // The very same pass, run again over the very same queue.
    val summary = runSearchPass(db, clock)

    assertEquals(0, summary.pairsFormed, "a resolved search never resolves twice")
    assertEquals(1, db.rooms.size, "no second table")
    val tableId = db.searches.getValue(gold)["tableId"]
    assertEquals(tableId, db.searches.getValue(silver)["tableId"])
  }

  @Test
  fun rePollingIsIdempotentAndAnswersTheSameTable() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    val first = pollSearchOnce(db, request(gold, emptyMap()), clock)
    val second = pollSearchOnce(db, request(gold, emptyMap()), clock)

    assertEquals("MATCH_FOUND", first["status"])
    assertEquals("MATCH_FOUND", second["status"])
    assertEquals(first["tableId"], second["tableId"], "a re-poll answers the same table")
    assertEquals(1, db.rooms.size, "polling never forms a table of its own")
  }

  @Test
  fun aResolvedSearchIsNotGivenAFreshLease() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    val heartbeatBefore = db.searches.getValue(gold)["heartbeatAt"]
    pollSearchOnce(db, request(gold, emptyMap()), clock)

    val document = db.searches.getValue(gold)
    assertEquals("RESOLVED", document["status"])
    assertEquals(heartbeatBefore, document["heartbeatAt"], "a finished search has no lease to renew")
    assertNotEquals(clock.now, document["heartbeatAt"], "the poll must not renew a finished search")
  }

  // ── 5. the cancellation ───────────────────────────────────────────────

  @Test
  fun cancellingAWaitingSearchRemovesIt() = runTest {
    val db = db()
    val clock = TestClock()
    enqueue(db, gold, clock = clock)

    val answer = cancelSearchOnce(db, request(gold, emptyMap()))

    assertEquals(true, answer["cancelled"])
    assertEquals("IDLE", answer["status"])
    assertNull(db.searches[gold], "a cancelled search leaves the queue")
  }

  @Test
  fun cancellingIsIdempotent() = runTest {
    val db = db()
    val clock = TestClock()
    enqueue(db, gold, clock = clock)

    val first = cancelSearchOnce(db, request(gold, emptyMap()))
    val second = cancelSearchOnce(db, request(gold, emptyMap()))

    assertEquals(true, first["cancelled"])
    assertEquals(true, second["cancelled"])
    assertEquals("IDLE", second["status"])
  }

  @Test
  fun cancellingAResolvedSearchDoesNotUnResolveIt() = runTest {
    val db = db()
    val clock = TestClock()
    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)
    val tableId = db.searches.getValue(gold)["tableId"]

    val answer = cancelSearchOnce(db, request(gold, emptyMap()))

    assertEquals(false, answer["cancelled"], "a formed table cannot be un-formed")
    assertEquals("MATCH_FOUND", answer["status"])
    assertEquals("RESOLVED", db.searches.getValue(gold)["status"])
    assertEquals(tableId, db.searches.getValue(gold)["tableId"])
    assertEquals(1, db.rooms.size)
  }

  // ── 6. a corrupt queue document fails the pass closed ────────────────

  @Test
  fun aCorruptQueueDocumentAbortsThePassAndWritesNothing() = runTest {
    val corrupt = listOf(
      "a missing tier" to linkedMapOf(
        "uid" to gold, "gameType" to "FULL",
        "status" to "WAITING", "enqueuedAt" to 1_000L, "heartbeatAt" to 1_000L,
      ),
      "an unknown tier" to linkedMapOf(
        "uid" to gold, "tier" to "Obsidian", "gameType" to "FULL",
        "status" to "WAITING", "enqueuedAt" to 1_000L, "heartbeatAt" to 1_000L,
      ),
      "an unknown game type" to linkedMapOf(
        "uid" to gold, "tier" to "Gold", "gameType" to "TRIO",
        "status" to "WAITING", "enqueuedAt" to 1_000L, "heartbeatAt" to 1_000L,
      ),
      "a missing arrival stamp" to linkedMapOf(
        "uid" to gold, "tier" to "Gold", "gameType" to "FULL",
        "status" to "WAITING", "heartbeatAt" to 1_000L,
      ),
      "an unreadable arrival stamp" to linkedMapOf(
        "uid" to gold, "tier" to "Gold", "gameType" to "FULL",
        "status" to "WAITING", "enqueuedAt" to "yesterday", "heartbeatAt" to 1_000L,
      ),
      "a uid that disagrees with the document id" to linkedMapOf(
        "uid" to silver, "tier" to "Gold", "gameType" to "FULL",
        "status" to "WAITING", "enqueuedAt" to 1_000L, "heartbeatAt" to 1_000L,
      ),
      "a blank uid" to linkedMapOf(
        "uid" to "  ", "tier" to "Gold", "gameType" to "FULL",
        "status" to "WAITING", "enqueuedAt" to 1_000L, "heartbeatAt" to 1_000L,
      ),
    )

    for ((label, document) in corrupt) {
      val db = db()
      db.searches[gold] = document
      db.searches[silver] = waiting(silver, "Silver", enqueuedAt = 2_000L)

      val refusal = refused { runSearchPass(db, TestClock()) }

      assertEquals("failed-precondition", refusal.code, label)
      assertEquals("QUEUE_UNREADABLE", refusal.reason, label)
      assertEquals(
        "The ranked queue holds a document this server cannot read.",
        refusal.message,
        "$label must carry the fixed message",
      )
      assertTrue(db.rooms.isEmpty(), "$label must form no table")
      assertEquals(0, db.writes, "$label must write nothing")
    }
  }

  @Test
  fun aDocumentWithAnUnknownStatusIsNoCandidateAndItsOwnerIsRefused() = runTest {
    // The queue's query asks for WAITING, so a document whose status is
    // something else is not a candidate at all: the pass never reads it, and
    // never guesses what it meant.
    val db = db()
    db.searches[gold] = linkedMapOf(
      "uid" to gold, "tier" to "Gold", "gameType" to "FULL",
      "status" to "PENDING", "enqueuedAt" to 1_000L, "heartbeatAt" to 1_000L,
    )
    db.searches[silver] = waiting(silver, "Silver", enqueuedAt = 2_000L)

    val summary = runSearchPass(db, TestClock())

    assertEquals(0, summary.pairsFormed, "an unreadable status is never paired")
    assertTrue(db.rooms.isEmpty())
    assertEquals(0, db.writes)

    // The owner of that document is still refused, with the same fixed
    // message: the client's own state it cannot read is a closed door, not a
    // guess.
    val refusal = refused { pollSearchOnce(db, request(gold, emptyMap()), TestClock()) }

    assertEquals("failed-precondition", refusal.code)
    assertEquals("QUEUE_UNREADABLE", refusal.reason)
    assertEquals(
      "The ranked queue holds a document this server cannot read.",
      refusal.message,
    )
  }

  // ── 7. no stored value and no other player's identity in a message ────

  @Test
  fun aRefusalNeverCarriesAStoredValueOrAnotherPlayersIdentity() = runTest {
    val db = db()
    db.searches[gold] = linkedMapOf(
      "uid" to gold, "tier" to "Obsidian", "gameType" to "FULL",
      "status" to "WAITING", "enqueuedAt" to 1_000L, "heartbeatAt" to 1_000L,
    )
    db.searches[silver] = waiting(silver, "Silver", enqueuedAt = 2_000L)

    val refusal = refused { runSearchPass(db, TestClock()) }

    for (leak in listOf("Obsidian", gold, silver, "uid-silver")) {
      assertFalse(refusal.message.contains(leak), "the refusal leaks '$leak': ${refusal.message}")
    }
  }

  @Test
  fun theAnswerCarriesTheCallersOwnStateAndNoBodyElses() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    val answer = pollSearchOnce(db, request(gold, emptyMap()), clock)

    assertEquals(setOf("ok", "status", "tableId"), answer.keys)
    assertEquals("MATCH_FOUND", answer["status"])
    for (leak in listOf("Gold", "Silver", silver, "tier", "division", "rp")) {
      assertFalse(
        answer.toString().contains(leak, ignoreCase = true),
        "the answer leaks '$leak': $answer",
      )
    }
  }

  // ── 8. the resolver is deterministic ──────────────────────────────────

  @Test
  fun theSameQueueStateAlwaysFormsTheSamePairs() = runTest {
    // The SAME four documents, written into the queue in two different
    // orders — Firestore promises no order for an equality query, so the
    // resolver must not inherit one either.
    val documents = listOf(
      waiting("uid-a", "Gold", enqueuedAt = 1_000L),
      waiting("uid-b", "Silver", enqueuedAt = 1_000L),
      waiting("uid-c", "Gold", enqueuedAt = 2_000L, gameType = "MINI"),
      waiting("uid-d", "Silver", enqueuedAt = 2_000L, gameType = "MINI"),
    )

    val forward = db().also { it.searches.putAll(documents.associateBy { it["uid"] as String }) }
    val backward = db().also {
      it.searches.putAll(documents.reversed().associateBy { it["uid"] as String })
    }

    runSearchPass(forward, TestClock())
    runSearchPass(backward, TestClock())

    assertEquals(2, forward.rooms.size, "two compatible pairs")
    assertEquals(pairsOf(forward), pairsOf(backward), "the same queue state must form the same pairs")
    assertEquals(
      listOf(listOf("uid-a", "uid-b"), listOf("uid-c", "uid-d")),
      pairsOf(forward),
    )
    // Each pair's two searches name the SAME table — the resolution is one
    // outcome, not two.
    for (pair in pairsOf(forward)) {
      val tableIds = pair.map { forward.searches.getValue(it)["tableId"] }.distinct()
      assertEquals(1, tableIds.size, "both searches of $pair name one table")
    }
  }

  @Test
  fun theQueueOrderIsArrivalThenUidAndNeverRandom() = runTest {
    // 1. The ARRIVAL stamp comes first: a later arrival with an earlier uid
    //    still queues second, and the Gold that arrived first owns the pool.
    val byArrival = db()
    byArrival.searches["uid-z"] = waiting("uid-z", "Gold", enqueuedAt = 1_000L)
    byArrival.searches["uid-a"] = waiting("uid-a", "Silver", enqueuedAt = 2_000L)

    runSearchPass(byArrival, TestClock())

    assertEquals(1, byArrival.rooms.size, "the earlier arrival owns the pool")
    assertEquals(
      listOf("uid-a", "uid-z"),
      (byArrival.rooms.values.first()["players"] as List<*>).map { it as String }.sorted(),
    )
    assertEquals("uid-z", byArrival.rooms.values.first()["creator"], "the earlier arrival created the table")

    // 2. The UID only breaks a TIE: at the same arrival stamp the Silver
    //    sorts first, so the SILVER pool owns the pairing — and a Silver pool
    //    never admits a Gold.
    val byUid = db()
    byUid.searches["uid-a"] = waiting("uid-a", "Silver", enqueuedAt = 1_000L)
    byUid.searches["uid-z"] = waiting("uid-z", "Gold", enqueuedAt = 1_000L)

    runSearchPass(byUid, TestClock())

    assertTrue(byUid.rooms.isEmpty(), "the uid tie-break put the Silver first, so it owns the pool")
  }

  // ── 9. the pass's work is bounded ────────────────────────────────────

  @Test
  fun aPassStopsAtItsAttemptCapAndLeavesTheRestForTheNextOne() = runTest {
    // Thirty Gold searchers, every one of them vanishing the moment the pass
    // reads it inside its transaction: the pass loses every race, so it keeps
    // looking for another candidate. Without a cap it would spend one
    // transaction per candidate pair in its window and could outlast the
    // function's own deadline.
    val db = db()
    val clock = TestClock()
    val uids = (0 until 30).map { "uid-gold-$it" }
    for (uid in uids) {
      db.profiles[uid] = profile("Gold", 575)
      db.searches[uid] = waiting(uid, "Gold", enqueuedAt = 1_000L)
    }
    db.vanishInsideTransaction += uids

    val summary = runSearchPass(db, clock)

    assertEquals(0, summary.pairsFormed, "a lost race forms no table")
    assertTrue(db.rooms.isEmpty(), "a lost race writes no room")
    assertEquals(
      MAX_RESOLVE_ATTEMPTS_PER_PASS,
      db.transactionCount,
      "the pass must not open more transactions than its attempt cap",
    )
    assertTrue(summary.capped, "a pass that ran out of attempts must say so")
    assertEquals(500L, summary.nextRetryAfterMillis, "a capped pass comes back sooner")
  }

  @Test
  fun aPassThatIsNotCappedAsksForTheFullCadence() = runTest {
    val db = db()
    val clock = TestClock()
    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    val summary = runSearchPass(db, clock)

    assertFalse(summary.capped, "a quiet queue is not a capped pass")
    assertEquals(3_000L, summary.nextRetryAfterMillis, "the cadence is the server's own")
  }

  // ── the queue document and the table it forms ────────────────────────

  @Test
  fun theSearchDocumentCarriesExactlyTheFieldsTheResolverReads() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)

    val document = db.searches.getValue(gold)
    assertEquals(
      setOf("uid", "tier", "gameType", "status", "enqueuedAt", "heartbeatAt"),
      document.keys,
    )
    assertEquals(gold, document["uid"])
    assertEquals("Gold", document["tier"])
    assertEquals("FULL", document["gameType"])
    assertEquals("WAITING", document["status"])
    assertEquals(clock.now, document["enqueuedAt"])
  }

  @Test
  fun theTableIsWrittenInTheRoomsOwnShape() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    val table = db.rooms.values.single()
    assertEquals(
      setOf(
        "name", "status", "creator", "players", "readyPlayers", "gameType",
        "scoringMode", "mode", "rankDown", "botTier", "botPersonality",
        "decisionTimerSeconds",
      ),
      table.keys,
    )
    assertEquals("waiting", table["status"])
    assertEquals(gold, table["creator"])
    assertEquals(listOf(gold, silver), table["players"])
    assertEquals(emptyList<String>(), table["readyPlayers"])
    assertEquals("FULL", table["gameType"])
    assertEquals("NORMAL", table["scoringMode"])
    assertEquals("RANKED", table["mode"])
    assertEquals(false, table["rankDown"])
    assertEquals("MEDIUM", table["botTier"])
    assertEquals("BALANCED", table["botPersonality"])
    assertEquals(15, table["decisionTimerSeconds"])
  }

  @Test
  fun aSearchWithoutAProfileIsRefusedAndNothingIsQueued() = runTest {
    val db = db()
    db.profiles.remove(gold)

    val refusal = refused { enqueue(db, gold) }

    assertEquals("failed-precondition", refusal.code)
    assertEquals("PROFILE_UNAVAILABLE", refusal.reason)
    assertTrue(db.searches.isEmpty())
  }

  @Test
  fun aProfileWhoseTierAndRpDisagreeIsRefusedAndNothingIsQueued() = runTest {
    val db = db()
    // RD3: 150 RP is Bronze III, so a profile that also claims Gold is
    // corrupt — and a pool built on it would be a guess.
    db.profiles[gold] = profile("Gold", 150)

    val refusal = refused { enqueue(db, gold) }

    assertEquals("failed-precondition", refusal.code)
    assertEquals("PROFILE_UNAVAILABLE", refusal.reason)
    assertTrue(db.searches.isEmpty())
  }

  @Test
  fun anExpiredLeaseIsReclaimedSoAVanishedClientCannotHoldTheQueue() = runTest {
    val db = db()
    val clock = TestClock()
    // A search whose client stopped polling long ago, beside a live one.
    db.searches[gold] = waiting(gold, "Gold", enqueuedAt = 1_000L, heartbeatAt = 1_000L)
    db.searches[silver] = waiting(silver, "Silver", enqueuedAt = 120_000L, heartbeatAt = 120_000L)

    clock.now = 121_000L
    runSearchPass(db, clock)

    assertNull(db.searches[gold], "an expired lease is reclaimed")
    assertNotNull(db.searches[silver], "a live search stays")
    assertTrue(db.rooms.isEmpty(), "a reclaimed search is never paired")
  }

  @Test
  fun everyTransactionReadsBeforeItWrites() = runTest {
    val db = db()
    val clock = TestClock()

    enqueue(db, gold, clock = clock)
    clock.now += 1_000
    enqueue(db, silver, clock = clock)

    // In the pair-forming transaction the two searches are read, then the
    // table and the two resolutions are written.
    assertEquals(
      listOf(
        "tx",
        "read:uid-gold",
        "read:uid-silver",
        "write-room:room-1",
        "resolve:uid-gold",
        "resolve:uid-silver",
      ),
      db.lastTransaction,
    )
  }

  // ── helpers ──────────────────────────────────────────────────────────

  private fun waiting(
    uid: String,
    tier: String,
    enqueuedAt: Long = 1_000L,
    heartbeatAt: Long = enqueuedAt,
    gameType: String = "FULL",
  ): Map<String, Any?> = linkedMapOf(
    "uid" to uid,
    "tier" to tier,
    "gameType" to gameType,
    "status" to "WAITING",
    "enqueuedAt" to enqueuedAt,
    "heartbeatAt" to heartbeatAt,
  )
}
