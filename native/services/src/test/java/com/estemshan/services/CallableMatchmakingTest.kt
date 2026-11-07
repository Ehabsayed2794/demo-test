package com.estemshan.services

import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S56/S71 — the production [MatchmakingPort] over the solo queue, Ranked and
 * Unranked, and the answer vocabulary it is read in.
 *
 * Two things are proven here, and neither of them can be proven against the
 * Functions emulator:
 *
 *  1. **The poll loop's own rules** — the enqueue is one shot, the poll is
 *     paced on the SERVER's number, a denial is terminal, a transport failure
 *     is not, and the budget ends the search with a cancel before the terminal
 *     event. The fake transport stands in for the three callables, so the
 *     loop is hermetic — the repo ships no Firebase fake by design, and the
 *     seam IS the test hook.
 *  2. **What the client is allowed to learn.** The answers carry the caller's
 *     own status and the caller's own table, and nothing else: a payload that
 *     carries a tier changes nothing, an unknown status is refused rather than
 *     guessed at, and a `MATCH_FOUND` without a table id is not a match.
 *
 * S71 adds the third thing this file proves: **the mode is the player's own
 * choice and nothing more.** It rides along on the wire, a Room is refused
 * before anything reaches the wire at all, the budget it earns outlasts the
 * fill window the server promised it, and the one number the server publishes
 * for a waiting Unranked search — its fill deadline — reaches the screen
 * without the client inventing a countdown of its own.
 *
 * The wire vocabulary (`MATCH_FOUND`, `SEARCHING`, `IDLE`, `tableId`,
 * `retryAfterMillis`, `fillDeadlineMillis`) and the transport codes' names are
 * repeated here as literals because `:services` cannot see `:functions` — the
 * same unavoidable duplication `DisconnectVoteResponseTest` documents.
 *
 * What is NOT here: the server's own decision of who is seated with whom
 * (RD9's pool, GM5's GameType, the resolver pass), which is pinned by
 * `MatchmakingSearchTest` in `:functions` against the server's own seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallableMatchmakingTest {

  /**
   * The three callables, on a fake.
   *
   * The answers are scripted, and every call is recorded, so the test can
   * pin BOTH what the port did and what it did not: a poll that never
   * happened, a cancel that did, and — because the transport's own API takes
   * no uid — the proof that no identity can reach the wire.
   */
  private class FakeTransport(
    private val enqueueAnswer: MatchmakingState,
    private val pollAnswers: List<MatchmakingState> = emptyList(),
    private val onPoll: () -> Unit = {},
  ) : MatchmakingTransport {

    val calls = mutableListOf<String>()
    var cancels = 0

    override suspend fun enqueue(gameType: GameType, mode: MatchMode): MatchmakingState {
      calls += "enqueue:${gameType.name}:${mode.name}"
      return enqueueAnswer
    }

    override suspend fun poll(): MatchmakingState {
      calls += "poll"
      onPoll()
      return pollAnswers.getOrElse(calls.count { it == "poll" } - 1) {
        MatchmakingState.Searching(10)
      }
    }

    override suspend fun cancel() {
      calls += "cancel"
      cancels++
    }
  }

  private fun port(
    transport: MatchmakingTransport,
    budgetMillis: Long? = null,
  ): MatchmakingPort = CallableMatchmaking(
    transport,
    pollIntervalMillis = 10L,
    searchBudgetMillis = budgetMillis,
  )

  private suspend fun search(
    port: MatchmakingPort,
    mode: MatchMode = MatchMode.RANKED,
  ): List<MatchmakingEvent> {
    val events = mutableListOf<MatchmakingEvent>()
    port.search("uid-player", GameType.FULL, mode).collect { events += it }
    return events
  }

  // ── what the client sends ─────────────────────────────────────────────

  @Test
  fun theSearchSendsTheGameTypeAndNoIdentityAtAll() = runTest {
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Searching(10))
    val port = port(transport, budgetMillis = 50)

    search(port)

    assertTrue("the GameType rides along", transport.calls.contains("enqueue:FULL:RANKED"))
    for (call in transport.calls) {
      assertFalse("no identity reaches the wire: $call", call.contains("uid"))
      assertFalse("no tier reaches the wire: $call", call.contains("tier", ignoreCase = true))
      assertFalse("no pool reaches the wire: $call", call.contains("Gold", ignoreCase = true))
    }
  }

  // ── the poll loop ─────────────────────────────────────────────────────

  @Test
  fun aTableFormedDuringTheEnqueueAnswersWithoutPolling() = runTest {
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Matched("table-1"))
    val port = port(transport)

    val events = search(port)

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.Matched("table-1")), events)
    assertFalse("the enqueue's own answer was enough", transport.calls.contains("poll"))
  }

  @Test
  fun thePollAnswersTheTableTheServerFormed() = runTest {
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10),
      pollAnswers = listOf(MatchmakingState.Matched("table-7")),
    )
    val port = port(transport)

    val events = search(port)

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.Matched("table-7")), events)
    assertEquals(1, transport.calls.count { it == "poll" })
  }

  @Test
  fun thePortPacesItsPollsOnTheServersOwnNumber() = runTest {
    val pollTimes = mutableListOf<Long>()
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(30),
      pollAnswers = listOf(MatchmakingState.Matched("table-1")),
      onPoll = { pollTimes += testScheduler.currentTime },
    )
    // The port's own default is 10 ms; the server's number must win.
    val port = port(transport)

    search(port)

    assertEquals("the poll waits the server's delay, not the port's", listOf(30L), pollTimes)
  }

  @Test
  fun aDenialDuringTheEnqueueIsTerminal() = runTest {
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Denied)
    val port = port(transport)

    val events = search(port)

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.Failed), events)
    assertFalse("a refusal is not polled again", transport.calls.contains("poll"))
  }

  @Test
  fun anIdleEnqueueIsTerminal() = runTest {
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Idle)
    val port = port(transport)

    assertEquals(
      listOf<MatchmakingEvent>(MatchmakingEvent.Failed),
      search(port),
    )
  }

  @Test
  fun aTransportFailureDuringTheEnqueueIsTerminal() = runTest {
    // The enqueue is the one call the port cannot retry: until it answers,
    // the port cannot promise the player is in the queue at all.
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Retryable)
    val port = port(transport)

    assertEquals(
      listOf<MatchmakingEvent>(MatchmakingEvent.Failed),
      search(port),
    )
    assertFalse(transport.calls.contains("poll"))
  }

  @Test
  fun aDenialDuringThePollIsTerminal() = runTest {
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10),
      pollAnswers = listOf(MatchmakingState.Denied),
    )
    val port = port(transport)

    assertEquals(
      listOf<MatchmakingEvent>(MatchmakingEvent.Failed),
      search(port),
    )
    assertEquals(1, transport.calls.count { it == "poll" })
  }

  @Test
  fun aLostSearchIsTerminal() = runTest {
    // The server says the queue holds no search of the caller's: the lease
    // expired and the pass reclaimed it. There is nothing left to poll for.
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10),
      pollAnswers = listOf(MatchmakingState.Idle),
    )
    val port = port(transport)

    assertEquals(
      listOf<MatchmakingEvent>(MatchmakingEvent.Failed),
      search(port),
    )
  }

  @Test
  fun aTransportFailureDuringThePollKeepsPolling() = runTest {
    // The search may well still be queued — the callable was unreachable, not
    // refused — so the poll continues.
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10),
      pollAnswers = listOf(
        MatchmakingState.Retryable,
        MatchmakingState.Matched("table-2"),
      ),
    )
    val port = port(transport)

    assertEquals(
      listOf<MatchmakingEvent>(MatchmakingEvent.Matched("table-2")),
      search(port),
    )
    assertEquals(2, transport.calls.count { it == "poll" })
  }

  @Test
  fun theBudgetRunsOutIntoATimeoutAndCancelsTheSearch() = runTest {
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10),
      pollAnswers = List(50) { MatchmakingState.Searching(10) },
    )
    val port = port(transport, budgetMillis = 50)

    val events = search(port)

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.TimedOut), events)
    assertEquals("the search leaves the queue before the timeout", 1, transport.cancels)
  }

  @Test
  fun theBudgetRunsOutIntoAFailureWhenTheTransportAlsoFailed() = runTest {
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10),
      pollAnswers = List(50) { MatchmakingState.Retryable },
    )
    val port = port(transport, budgetMillis = 50)

    val events = search(port)

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.Failed), events)
    assertEquals(1, transport.cancels)
  }

  @Test
  fun cancellingRemovesTheSearchFromTheQueue() = runTest {
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Searching(10))
    val port = port(transport)

    port.cancel("uid-player")

    assertEquals(1, transport.cancels)
    // The cancel carries no identity either: the server knows who is asking.
    assertEquals(listOf("cancel"), transport.calls)
  }

  // ── the answer vocabulary ─────────────────────────────────────────────

  @Test
  fun aFoundStatusWithATableIdIsAMatch() {
    val state = MatchmakingResponse.forData(mapOf("status" to "MATCH_FOUND", "tableId" to "table-1"), 10)
    assertEquals(MatchmakingState.Matched("table-1"), state)
  }

  @Test
  fun aFoundStatusWithoutATableIdIsRefused() {
    // A match without a table id is not a match: answering "found" without
    // one hands the screen an id that opens nothing.
    assertEquals(
      MatchmakingState.Denied,
      MatchmakingResponse.forData(mapOf("status" to "MATCH_FOUND"), 10),
    )
    assertEquals(
      MatchmakingState.Denied,
      MatchmakingResponse.forData(mapOf("status" to "MATCH_FOUND", "tableId" to ""), 10),
    )
  }

  @Test
  fun aSearchingStatusTakesTheServersOwnDelay() {
    val state = MatchmakingResponse.forData(
      mapOf("status" to "SEARCHING", "retryAfterMillis" to 750),
      10,
    )
    assertEquals(MatchmakingState.Searching(750), state)
  }

  @Test
  fun aSearchingStatusWithoutADelayFallsBackOnTheLocalOne() {
    assertEquals(
      MatchmakingState.Searching(10),
      MatchmakingResponse.forData(mapOf("status" to "SEARCHING"), 10),
    )
    assertEquals(
      MatchmakingState.Searching(10),
      MatchmakingResponse.forData(mapOf("status" to "SEARCHING", "retryAfterMillis" to 0), 10),
    )
  }

  @Test
  fun anIdleStatusMeansThereIsNoSearch() {
    assertEquals(
      MatchmakingState.Idle,
      MatchmakingResponse.forData(mapOf("status" to "IDLE"), 10),
    )
  }

  @Test
  fun anUnknownStatusIsRefusedRatherThanGuessedAt() {
    for (status in listOf("PENDING", "MATCHED", "", "searching")) {
      assertEquals(
        "the status '$status' must not be read as anything",
        MatchmakingState.Denied,
        MatchmakingResponse.forData(mapOf("status" to status), 10),
      )
    }
  }

  @Test
  fun anAnswerThatIsNotAMapIsRefused() {
    for (answer in listOf(null, "MATCH_FOUND", 7, listOf("MATCH_FOUND"))) {
      assertEquals(
        MatchmakingState.Denied,
        MatchmakingResponse.forData(answer, 10),
      )
    }
  }

  @Test
  fun anAnswerCarryingATierTeachesTheClientNothing() {
    val state = MatchmakingResponse.forData(
      mapOf("status" to "SEARCHING", "tier" to "Gold", "rp" to 900, "uid" to "somebody-else"),
      10,
    )
    assertEquals(MatchmakingState.Searching(10), state)
    assertFalse("the client learns no tier", state.toString().contains("Gold"))
    assertFalse("the client learns nobody else", state.toString().contains("somebody-else"))
  }

  @Test
  fun theCodesThatMeanTheFunctionNeverAnsweredAreRetried() {
    for (code in listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")) {
      assertEquals(
        "the code '$code' must be retried",
        MatchmakingState.Retryable,
        MatchmakingResponse.forCodeName(code),
      )
    }
  }

  @Test
  fun everyOtherCodeIsTheServersOwnRefusal() {
    for (code in listOf(
      "PERMISSION_DENIED",
      "UNAUTHENTICATED",
      "FAILED_PRECONDITION",
      "INVALID_ARGUMENT",
      "NOT_FOUND",
      "UNKNOWN",
    )) {
      assertEquals(
        "the code '$code' must be a refusal",
        MatchmakingState.Denied,
        MatchmakingResponse.forCodeName(code),
      )
    }
  }

  // ── S71: the mode, the budget it earns, and the deadline it publishes ──

  @Test
  fun theModeRidesAlongOnTheWire() = runTest {
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Searching(10))
    val port = port(transport, budgetMillis = 50)

    search(port, MatchMode.UNRANKED)

    assertTrue(
      "the Unranked search says so",
      transport.calls.contains("enqueue:FULL:UNRANKED"),
    )
  }

  @Test
  fun aRoomIsRefusedRatherThanQueued() {
    // A Room is filled by invitation, never by a queue: a search for one
    // would sit in a pool that can never seat it.
    val transport = FakeTransport(enqueueAnswer = MatchmakingState.Searching(10))
    val port = port(transport)

    val failure = assertThrows(IllegalArgumentException::class.java) {
      port.search("uid-player", GameType.FULL, MatchMode.ROOM)
    }

    assertTrue(
      "the refusal names the mode: ${failure.message}",
      failure.message!!.contains("ROOM"),
    )
    assertTrue("nothing reaches the wire", transport.calls.isEmpty())
  }

  @Test
  fun aRankedSearchGivesUpAfterTheRankedBudget() = runTest {
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(1_000),
      pollAnswers = List(400) { MatchmakingState.Searching(1_000) },
    )
    val port = port(transport)

    val started = testScheduler.currentTime
    val events = search(port, MatchMode.RANKED)
    val waited = testScheduler.currentTime - started

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.TimedOut), events)
    assertEquals("the Ranked budget is two minutes", 120_000L, waited)
  }

  @Test
  fun anUnrankedSearchWaitsOutTheFillWindow() = runTest {
    // The client must outlast the server's own promise — the four-minute
    // fill window — or it would cancel a search the server was about to fill.
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(1_000),
      pollAnswers = List(400) { MatchmakingState.Searching(1_000) },
    )
    val port = port(transport)

    val started = testScheduler.currentTime
    val events = search(port, MatchMode.UNRANKED)
    val waited = testScheduler.currentTime - started

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.TimedOut), events)
    assertTrue(
      "the client waited out the four-minute fill window: $waited",
      waited >= 240_000L,
    )
    assertEquals("the window plus a grace for the last round trip", 300_000L, waited)
  }

  @Test
  fun anUnrankedSearchPublishesTheServersFillDeadline() = runTest {
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10, fillDeadlineMillis = 300_000L),
    )
    val port = port(transport, budgetMillis = 50)

    val events = search(port, MatchMode.UNRANKED)

    // The deadline is published, and the search goes on: the event is
    // progress, not an outcome.
    assertEquals(
      listOf<MatchmakingEvent>(
        MatchmakingEvent.Searching(300_000L),
        MatchmakingEvent.TimedOut,
      ),
      events,
    )
  }

  @Test
  fun aRankedSearchNeverPublishesAFillDeadline() = runTest {
    // A Ranked table is a pair, not a window: the port never invents a
    // deadline, even if a skewed server published one.
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10, fillDeadlineMillis = 300_000L),
    )
    val port = port(transport, budgetMillis = 50)

    val events = search(port, MatchMode.RANKED)

    assertEquals(listOf<MatchmakingEvent>(MatchmakingEvent.TimedOut), events)
  }

  @Test
  fun theDeadlineIsPublishedOnceHoweverOftenTheServerRepeatsIt() = runTest {
    val transport = FakeTransport(
      enqueueAnswer = MatchmakingState.Searching(10, fillDeadlineMillis = 300_000L),
      pollAnswers = List(10) { MatchmakingState.Searching(10, fillDeadlineMillis = 300_000L) },
    )
    val port = port(transport, budgetMillis = 50)

    val events = search(port, MatchMode.UNRANKED)

    assertEquals(
      "the deadline is a fact, not a per-poll event",
      1,
      events.count { it is MatchmakingEvent.Searching },
    )
  }

  @Test
  fun theAnswerCarriesTheServersFillDeadline() {
    assertEquals(
      MatchmakingState.Searching(750, 300_000L),
      MatchmakingResponse.forData(
        mapOf(
          "status" to "SEARCHING",
          "retryAfterMillis" to 750,
          "fillDeadlineMillis" to 300_000L,
        ),
        10,
      ),
    )
  }

  @Test
  fun anAnswerWithNoDeadlineIsNoDeadline() {
    // A Ranked search's normal answer: no deadline, and no invented one.
    assertEquals(
      MatchmakingState.Searching(10, null),
      MatchmakingResponse.forData(mapOf("status" to "SEARCHING"), 10),
    )
  }

  @Test
  fun aDeadlineThatIsNotANumberIsNoDeadline() {
    // A number the client cannot count down is not a deadline it may show.
    assertEquals(
      MatchmakingState.Searching(10, null),
      MatchmakingResponse.forData(
        mapOf("status" to "SEARCHING", "fillDeadlineMillis" to "soon"),
        10,
      ),
    )
    assertEquals(
      MatchmakingState.Searching(10, null),
      MatchmakingResponse.forData(
        mapOf("status" to "SEARCHING", "fillDeadlineMillis" to 0),
        10,
      ),
    )
  }
}
