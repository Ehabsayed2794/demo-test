package com.estemshan.functions

import com.estemshan.engine.Card
import com.estemshan.engine.DisconnectVoteChoice
import com.estemshan.engine.DisconnectVotePhase
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.DisconnectVoteStatus
import kotlinx.coroutines.test.runTest
import kotlin.js.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S74 runtime — the disconnect flow end to end at the decision layer: the
 * heartbeat scan, the single-ballot queue, the close at the ruled instants,
 * the granted pause, the cast, the reconnect, the resolver's one-transaction
 * write, and the pause the decision machine obeys.
 *
 * The clock is injected and every window is driven by arithmetic on
 * [anchor] — CI cannot wait minutes, and the windows ARE the contract. What
 * the JVM engine tests cannot see lives here: who is asked, where the ballots
 * are deleted, what the replacement records, and that a Ranked document costs
 * no heartbeat read at all.
 *
 * The mode gate is exercised on EVERY front door (resolve, cast, the pacing
 * reads, the pause) because DEC-DISC-01's "in RANKED the question is not asked
 * at all" is only as strong as its weakest entry — and the canary for that
 * lives in one function ([DisconnectVoteFlow.modeEligible]).
 */
class DisconnectVoteFlowTest {

  private val seats: Map<String, String> =
    linkedMapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4")
  private val players = listOf("u1", "u2", "u3", "u4")

  /** The server clock's anchor, in epoch millis. */
  private val anchor = 1_000_000L

  private fun entry(
    phase: DisconnectVotePhase,
    seat: String,
    detectedAt: Long = anchor,
    deadline: Long? = null,
    answered: Int = 0,
    verdict: DisconnectVoteStatus? = null,
  ): Map<String, Any?> = DisconnectVoteState(
    seatId = seat,
    phase = phase,
    detectedAtMillis = detectedAt,
    deadlineMillis = deadline,
    answeredCount = answered,
    verdict = verdict,
  ).toFields()

  private fun held(seat: String, detectedAt: Long = anchor): Map<String, Any?> =
    entry(DisconnectVotePhase.HOLD, seat, detectedAt = detectedAt)

  private fun openVote(
    seat: String,
    closesAt: Long = anchor + 30_000L,
    detectedAt: Long = anchor,
    answered: Int = 0,
  ): Map<String, Any?> =
    entry(DisconnectVotePhase.VOTE, seat, detectedAt = detectedAt, deadline = closesAt, answered = answered)

  private fun grantedWait(seat: String, endsAt: Long = anchor + 120_000L): Map<String, Any?> =
    entry(
      DisconnectVotePhase.WAIT,
      seat,
      deadline = endsAt,
      answered = 2,
      verdict = DisconnectVoteStatus.WAIT,
    )

  private fun replacedEntry(seat: String, endedAt: Long = anchor + 30_000L): Map<String, Any?> =
    entry(
      DisconnectVotePhase.REPLACED,
      seat,
      deadline = endedAt,
      answered = 2,
      verdict = DisconnectVoteStatus.NO_AGREEMENT,
    )

  private fun ballot(seat: String, choice: DisconnectVoteChoice, castAt: Long = anchor): Map<String, Any?> =
    DisconnectVoteState.ballotFields(seat, choice, castAt)

  private fun matchFields(
    mode: String = "UNRANKED",
    vote: Map<String, Any?>? = null,
    replaced: List<String>? = null,
    version: Any? = 7,
    botSeats: List<String> = emptyList(),
    removedUids: List<String> = emptyList(),
    timeoutRemovedUids: List<String> = emptyList(),
    inactiveTakeovers: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> = mutableMapOf<String, Any?>(
    "players" to players,
    "status" to "starting",
    "seats" to seats,
    "mode" to mode,
    "version" to version,
    "updatedAt" to anchor,
    "botSeats" to botSeats,
    "removedUids" to removedUids,
    "timeoutRemovedUids" to timeoutRemovedUids,
    "inactiveTakeovers" to inactiveTakeovers,
  ).apply {
    if (vote != null) put(DisconnectVoteState.VOTE_FIELD, vote)
    if (replaced != null) put(DisconnectVoteState.REPLACED_UIDS_FIELD, replaced)
  }

  /** Every seated uid's heartbeat, fresh unless overridden. */
  private fun hearts(vararg overrides: Pair<String, Long?>): Map<String, Long?> {
    val out = linkedMapOf<String, Long?>()
    for (uid in players) out[uid] = anchor - 1_000L
    for ((uid, millis) in overrides) out[uid] = millis
    return out
  }

  @Suppress("UNCHECKED_CAST")
  private fun markerOf(written: Map<String, Any?>?): Map<String, Any?>? =
    written?.get(DisconnectVoteState.VOTE_FIELD) as? Map<String, Any?>

  @Suppress("UNCHECKED_CAST")
  private fun entryOf(marker: Map<String, Any?>, seatId: String): Map<String, Any?> =
    marker.getValue(seatId) as Map<String, Any?>

  // ── detection: the frozen heartbeat, and nothing else ────────────────

  @Test
  fun theFirstSilenceOpensTheBallotAndHoldsTheSeatFromThatInstant() {
    val fields = matchFields()
    val step = DisconnectVoteFlow.step(
      fields = fields,
      nowMillis = anchor,
      lastSeenByUid = hearts("u2" to anchor - 90_000L),
      ballotChoices = emptyMap(),
    )

    val applied = step as DisconnectVoteFlow.Step.Applied
    assertEquals(DisconnectVoteFlow.Action.OPENED, applied.action)
    assertEquals("p2", applied.seatId)
    val entry = entryOf(applied.marker, "p2")
    assertEquals(DisconnectVotePhase.VOTE.name, entry[DisconnectVoteState.PHASE_FIELD])
    assertEquals(anchor, entry[DisconnectVoteState.DETECTED_AT_FIELD])
    assertEquals(
      anchor + 30_000L,
      entry[DisconnectVoteState.DEADLINE_FIELD],
      "the window is the ruled 30 s, opened from the detection instant",
    )
    assertFalse(DisconnectVoteState.ANSWERED_FIELD in entry)
    assertEquals(30_000L, applied.retryAfterMillis, "the poll re-arms on the window's own close")
  }

  @Test
  fun freshHeartbeatsAndMissingOnesNeverOpenAnything() {
    val fresh = DisconnectVoteFlow.step(
      matchFields(),
      anchor,
      hearts(),
      emptyMap(),
    ) as DisconnectVoteFlow.Step.NoWrite
    assertEquals(DisconnectVoteFlow.Reason.NOT_DETECTED, fresh.reason)
    assertEquals(
      89_000L,
      fresh.retryAfterMillis,
      "the server hands back the earliest heartbeat that would go stale",
    )

    // A missing heartbeat never detects — absence of evidence is not
    // evidence of absence — and it never shortens the poll either.
    val missing = DisconnectVoteFlow.step(
      matchFields(),
      anchor,
      hearts("u2" to null),
      emptyMap(),
    ) as DisconnectVoteFlow.Step.NoWrite
    assertEquals(DisconnectVoteFlow.Reason.NOT_DETECTED, missing.reason)
    assertEquals(89_000L, missing.retryAfterMillis)
  }

  @Test
  fun aSeatAlreadyUnderAnotherMechanismIsNeverDetected() {
    // Vote-kicked and timeout-removed seats have no human to be absent.
    val step = DisconnectVoteFlow.step(
      matchFields(botSeats = listOf("p2"), removedUids = listOf("u3"), timeoutRemovedUids = listOf("u4")),
      anchor,
      hearts(
        "u2" to anchor - 200_000L,
        "u3" to anchor - 200_000L,
        "u4" to anchor - 200_000L,
      ),
      emptyMap(),
    ) as DisconnectVoteFlow.Step.NoWrite
    assertEquals(DisconnectVoteFlow.Reason.NOT_DETECTED, step.reason)
  }

  // ── the mode gate: RANKED never asks ─────────────────────────────────

  @Test
  fun rankedNeverAsksAndNeverHolds() {
    val ranked = matchFields(mode = "RANKED")

    val step = DisconnectVoteFlow.step(
      ranked,
      anchor,
      hearts("u2" to anchor - 200_000L),
      emptyMap(),
    ) as DisconnectVoteFlow.Step.NoWrite
    assertEquals(DisconnectVoteFlow.Reason.NOT_APPLICABLE, step.reason)
    assertNull(step.retryAfterMillis, "not applicable is terminal — no poll interval")

    // The pacing reads are gated too: even a marker that somehow sits on a
    // Ranked document changes no seat's pace and stops no decision.
    val poisoned = matchFields(
      mode = "RANKED",
      vote = mapOf("p2" to held("p2")),
    )
    assertTrue(TimeoutResolution.disconnectHeldSeats(poisoned).isEmpty())
    val poisonedWait = matchFields(mode = "RANKED", vote = mapOf("p2" to grantedWait("p2")))
    assertNull(TimeoutResolution.disconnectPauseRemaining(poisonedWait, anchor))
  }

  // ── the single-ballot queue ──────────────────────────────────────────

  @Test
  fun aSecondDroppedSeatIsHeldButQueuesBehindTheLiveBallot() {
    val fields = matchFields(vote = mapOf("p2" to openVote("p2")))

    val step = DisconnectVoteFlow.step(
      fields,
      anchor,
      hearts("u3" to anchor - 90_000L),
      emptyMap(),
    ) as DisconnectVoteFlow.Step.Applied

    assertEquals(DisconnectVoteFlow.Action.HELD, step.action)
    assertEquals("p3", step.seatId)
    assertEquals(openVote("p2"), entryOf(step.marker, "p2"), "the live ballot is carried over verbatim")
    assertEquals(DisconnectVotePhase.HOLD.name, entryOf(step.marker, "p3")[DisconnectVoteState.PHASE_FIELD])
    assertEquals(30_000L, step.retryAfterMillis, "the poll still re-arms on the live window's close")
  }

  // ── the close: at the ruled instants, from the ruled answers ─────────

  @Test
  fun twoOfThreeEndsTheWaitingAtTheBallotsOwnClose() {
    val closesAt = anchor + 30_000L
    val fields = matchFields(vote = mapOf("p2" to openVote("p2", closesAt = closesAt)))

    val step = DisconnectVoteFlow.step(
      fields,
      closesAt,
      hearts(),
      mapOf("u1" to DisconnectVoteChoice.CONTINUE, "u3" to DisconnectVoteChoice.CONTINUE),
    ) as DisconnectVoteFlow.Step.Applied

    assertEquals(DisconnectVoteFlow.Action.REPLACED, step.action)
    assertEquals(listOf("u2"), step.replacedUids)
    assertEquals(listOf("u1", "u3", "u4"), step.ballotDeletes, "every ballot of this vote ends with it")
    val entry = entryOf(step.marker, "p2")
    assertEquals(DisconnectVotePhase.REPLACED.name, entry[DisconnectVoteState.PHASE_FIELD])
    assertEquals(DisconnectVoteStatus.CONTINUE.name, entry[DisconnectVoteState.VERDICT_FIELD])
    assertEquals(
      closesAt,
      entry[DisconnectVoteState.DEADLINE_FIELD],
      "the replacement instant is the ballot's own close",
    )
  }

  @Test
  fun noAgreementAndDisagreeingTablesContinueWithTheConfiguredBot() {
    val closesAt = anchor + 30_000L
    val fields = matchFields(vote = mapOf("p2" to openVote("p2", closesAt = closesAt)))
    val ballots = listOf(
      emptyMap(),
      mapOf("u1" to DisconnectVoteChoice.CONTINUE),
      mapOf("u1" to DisconnectVoteChoice.CONTINUE, "u3" to DisconnectVoteChoice.WAIT),
    )

    for (choices in ballots) {
      val step = DisconnectVoteFlow.step(fields, closesAt, hearts(), choices)
        as DisconnectVoteFlow.Step.Applied
      assertEquals(DisconnectVoteFlow.Action.REPLACED, step.action)
      val entry = entryOf(step.marker, "p2")
      assertEquals(
        DisconnectVoteStatus.NO_AGREEMENT.name,
        entry[DisconnectVoteState.VERDICT_FIELD],
        "no agreement is a ruled outcome, not an error",
      )
      assertEquals(listOf("u2"), step.replacedUids)
    }
  }

  @Test
  fun aWaitVerdictGrantsTheRuledPauseAndTheReplacementWaitsForItsEnd() {
    val closesAt = anchor + 30_000L
    val fields = matchFields(vote = mapOf("p2" to openVote("p2", closesAt = closesAt)))
    val choices = mapOf("u1" to DisconnectVoteChoice.WAIT, "u3" to DisconnectVoteChoice.WAIT)

    val waited = DisconnectVoteFlow.step(fields, closesAt, hearts(), choices)
      as DisconnectVoteFlow.Step.Applied
    assertEquals(DisconnectVoteFlow.Action.WAITED, waited.action)
    assertTrue(waited.replacedUids.isEmpty(), "a granted pause replaces nobody yet")
    val waitEntry = entryOf(waited.marker, "p2")
    assertEquals(DisconnectVotePhase.WAIT.name, waitEntry[DisconnectVoteState.PHASE_FIELD])
    assertEquals(
      closesAt + 90_000L,
      waitEntry[DisconnectVoteState.DEADLINE_FIELD],
      "the pause begins AFTER the verdict and is the ruled 90 s",
    )

    // The next poll finds the pause ended: the replacement lands then, and
    // the recorded instant is still the pause's bound — never the poll's.
    val ended = DisconnectVoteFlow.step(
      matchFields(vote = mapOf("p2" to waitEntry)),
      closesAt + 90_000L,
      hearts(),
      emptyMap(),
    ) as DisconnectVoteFlow.Step.Applied
    assertEquals(DisconnectVoteFlow.Action.REPLACED, ended.action)
    assertEquals(listOf("u2"), ended.replacedUids)
    assertEquals(
      closesAt + 90_000L,
      entryOf(ended.marker, "p2")[DisconnectVoteState.DEADLINE_FIELD],
    )
  }

  @Test
  fun aPauseWhoseBoundAlreadyElapsedCollapsesStraightToReplacement() {
    // Nobody polled for over two minutes: the waiting the table would have
    // served is already over, so the flow records the ruled instants instead
    // of granting a fresh pause nobody watched for.
    val closesAt = anchor + 30_000L
    val late = closesAt + 90_000L + 5_000L
    val fields = matchFields(vote = mapOf("p2" to openVote("p2", closesAt = closesAt)))

    val step = DisconnectVoteFlow.step(
      fields,
      late,
      hearts(),
      mapOf("u1" to DisconnectVoteChoice.WAIT, "u3" to DisconnectVoteChoice.WAIT),
    ) as DisconnectVoteFlow.Step.Applied

    assertEquals(DisconnectVoteFlow.Action.REPLACED, step.action)
    val entry = entryOf(step.marker, "p2")
    assertEquals(DisconnectVotePhase.REPLACED.name, entry[DisconnectVoteState.PHASE_FIELD])
    assertEquals(DisconnectVoteStatus.WAIT.name, entry[DisconnectVoteState.VERDICT_FIELD])
    assertEquals(
      closesAt + 90_000L,
      entry[DisconnectVoteState.DEADLINE_FIELD],
      "the recorded bound is the close plus the ruled pause",
    )
  }

  @Test
  fun aReplacedSeatNeverQueuesAgain() {
    // "Fresh" is relative to the clock being asked about: the seated humans
    // are beating normally at THIS poll, and the replaced seat's own frozen
    // heartbeat still opens nothing — its entry excludes it from the scan.
    val now = anchor + 200_000L
    val fields = matchFields(vote = mapOf("p2" to replacedEntry("p2")))
    val step = DisconnectVoteFlow.step(
      fields,
      now,
      mapOf(
        "u1" to now - 1_000L,
        "u2" to anchor - 300_000L,
        "u3" to now - 1_000L,
        "u4" to now - 1_000L,
      ),
      emptyMap(),
    ) as DisconnectVoteFlow.Step.NoWrite
    assertEquals(DisconnectVoteFlow.Reason.NOT_YET_DUE, step.reason)
  }

  // ── who is asked ─────────────────────────────────────────────────────

  @Test
  fun theBallotAsksTheRemainingEligibleHumansOnly() {
    fun electorate(
      vote: Map<String, Any?>? = mapOf("p2" to held("p2")),
      botSeats: List<String> = emptyList(),
      removedUids: List<String> = emptyList(),
      timeoutRemovedUids: List<String> = emptyList(),
      inactiveTakeovers: Map<String, Any?> = emptyMap(),
    ): List<String> = DisconnectVoteFlow.electorateUids(
      matchFields(
        vote = vote,
        botSeats = botSeats,
        removedUids = removedUids,
        timeoutRemovedUids = timeoutRemovedUids,
        inactiveTakeovers = inactiveTakeovers,
      ),
      departedSeatId = "p2",
    )

    assertEquals(listOf("u1", "u3", "u4"), electorate())
    assertEquals(listOf("u1", "u4"), electorate(botSeats = listOf("p3")), "a kicked seat is bot-driven, not a voter")
    assertEquals(listOf("u1", "u3"), electorate(removedUids = listOf("u4")), "a kick-removed uid is gone")
    assertEquals(
      listOf("u1", "u4"),
      electorate(timeoutRemovedUids = listOf("u3")),
      "a timeout-removed uid is gone",
    )
    assertEquals(
      listOf("u1", "u4"),
      electorate(vote = mapOf("p2" to held("p2"), "p3" to held("p3"))),
      "a seat this flow already holds is not asked",
    )
    assertEquals(
      listOf("u1", "u3", "u4"),
      electorate(
        inactiveTakeovers = mapOf(
          "p3" to mapOf(
            "takenOverAtMillis" to 5_000L,
            "roundAtTakeover" to 1L,
            "trickAtTakeover" to 0L,
          ),
        ),
      ),
      "an RD19-borrowed seat is still its owner's and still answers",
    )
  }

  // ── casting ──────────────────────────────────────────────────────────

  @Test
  fun castingCountsOneAnswerPerHumanAndAnswersAlreadyAfterThat() {
    val fields = matchFields(vote = mapOf("p2" to openVote("p2", answered = 1)))

    val first = DisconnectVoteFlow.cast(
      fields, "u3", DisconnectVoteChoice.WAIT, anchor + 1_000L, hasExistingBallot = false,
    ) as DisconnectVoteFlow.Cast.Recorded
    assertEquals("p2", first.seatId)
    assertEquals(
      2,
      entryOf(first.marker, "p2")[DisconnectVoteState.ANSWERED_FIELD],
      "the public count is what the table already knows: n of 3",
    )

    val second = DisconnectVoteFlow.cast(
      fields, "u3", DisconnectVoteChoice.CONTINUE, anchor + 2_000L, hasExistingBallot = true,
    )
    assertEquals(DisconnectVoteFlow.Cast.AlreadyRecorded("p2"), second)
  }

  @Test
  fun castingRefusesEveryoneTheBallotDoesNotAsk() {
    val fields = matchFields(vote = mapOf("p2" to openVote("p2")))
    fun refusal(uid: String, at: Long = anchor + 1_000L): String? =
      (DisconnectVoteFlow.cast(fields, uid, DisconnectVoteChoice.WAIT, at, false)
        as DisconnectVoteFlow.Cast.NoWrite).reason

    assertEquals(DisconnectVoteFlow.Reason.NOT_ELIGIBLE, refusal("u2")) // the subject
    assertEquals(DisconnectVoteFlow.Reason.BALLOT_CLOSED, refusal("u1", anchor + 30_000L))
    assertEquals(
      DisconnectVoteFlow.Reason.NO_BALLOT,
      (DisconnectVoteFlow.cast(matchFields(), "u1", DisconnectVoteChoice.WAIT, anchor, false)
        as DisconnectVoteFlow.Cast.NoWrite).reason,
    )
    assertEquals(
      DisconnectVoteFlow.Reason.NOT_APPLICABLE,
      (DisconnectVoteFlow.cast(
        matchFields(mode = "RANKED", vote = mapOf("p2" to openVote("p2"))),
        "u1",
        DisconnectVoteChoice.WAIT,
        anchor,
        false,
      ) as DisconnectVoteFlow.Cast.NoWrite).reason,
    )

    val kicked = matchFields(
      vote = mapOf("p2" to openVote("p2")),
      botSeats = listOf("p4"),
    )
    assertEquals(
      DisconnectVoteFlow.Reason.NOT_ELIGIBLE,
      (DisconnectVoteFlow.cast(kicked, "u4", DisconnectVoteChoice.WAIT, anchor, false)
        as DisconnectVoteFlow.Cast.NoWrite).reason,
    )
  }

  // ── the reconnect ────────────────────────────────────────────────────

  @Test
  fun aReconnectReturnsTheSeatThroughEveryWaitingPhase() {
    for (phase in listOf(DisconnectVotePhase.HOLD, DisconnectVotePhase.VOTE, DisconnectVotePhase.WAIT)) {
      val fields = matchFields(
        vote = mapOf(
          "p2" to when (phase) {
            DisconnectVotePhase.HOLD -> held("p2")
            DisconnectVotePhase.VOTE -> openVote("p2")
            else -> grantedWait("p2")
          },
          "p1" to replacedEntry("p1"),
        ),
      )

      val restored = DisconnectVoteFlow.restore(fields, "u2") as DisconnectVoteFlow.Restore.Restored
      assertEquals("p2", restored.seatId)
      assertFalse("p2" in restored.marker, "the seat's own entry is gone")
      assertEquals(
        replacedEntry("p1"),
        restored.marker["p1"],
        "another seat's flow is carried over verbatim",
      )
      if (phase == DisconnectVotePhase.VOTE) {
        assertEquals(
          listOf("u3", "u4"),
          restored.ballotDeletes,
          "p1's own replaced flow is not asked either — the electorate is u3 and u4",
        )
      } else {
        assertTrue(restored.ballotDeletes.isEmpty(), "no ballots exist outside the vote phase")
      }
    }
  }

  @Test
  fun aReconnectAfterTheWaitingEndedIsRefusedAsReplaced() {
    val fields = matchFields(vote = mapOf("p2" to replacedEntry("p2")))
    assertEquals(DisconnectVoteFlow.Restore.Replaced("p2"), DisconnectVoteFlow.restore(fields, "u2"))

    assertEquals(
      DisconnectVoteFlow.Restore.NothingHeld,
      DisconnectVoteFlow.restore(fields, "u9"),
      "a caller who holds no seat has nothing to return",
    )
    assertEquals(
      DisconnectVoteFlow.Restore.NothingHeld,
      DisconnectVoteFlow.restore(matchFields(mode = "RANKED", vote = mapOf("p2" to held("p2"))), "u2"),
      "a Ranked document can never owe a seat return",
    )
  }

  // ── the resolver's one transaction ───────────────────────────────────

  /** The seam: reads and writes recorded, the doc swappable between passes. */
  private class FakeDb(
    private val doc: Map<String, Any?>?,
    private val inTxDoc: Map<String, Any?>? = null,
    private val hearts: Map<String, Long?> = emptyMap(),
    private val ballots: Map<String, Map<String, Any?>> = emptyMap(),
  ) : DisconnectVoteDb {
    var written: Map<String, Any?>? = null
      private set
    val writtenBallots: MutableMap<String, Map<String, Any?>> = mutableMapOf()
    val deletedBallots: MutableList<String> = mutableListOf()
    var heartbeatReads = 0
      private set
    var transitions = 0
      private set

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = doc

    override suspend fun <T> transact(block: suspend (DisconnectVoteDb.Tx) -> T): T {
      transitions++
      val txDoc = inTxDoc ?: doc
      return block(object : DisconnectVoteDb.Tx {
        override suspend fun readMatch(matchId: String): Map<String, Any?>? = txDoc
        override suspend fun readLastSeenMillis(uid: String): Long? {
          heartbeatReads++
          return hearts[uid]
        }

        override suspend fun readBallot(matchId: String, uid: String): Map<String, Any?>? = ballots[uid]
        override fun updateMatch(matchId: String, updates: Map<String, Any?>) {
          written = updates
        }

        override fun writeBallot(matchId: String, uid: String, fields: Map<String, Any?>) {
          writtenBallots[uid] = fields
        }

        override fun deleteBallot(matchId: String, uid: String) {
          deletedBallots += uid
        }
      })
    }
  }

  private fun fakeRequest(uid: String?, matchId: String = "m1", choice: String? = null): CallableRequest =
    object : CallableRequest {
      // The runtime delivers data as a PLAIN JS OBJECT, never a Kotlin map.
      override val data: Any? = json("matchId" to matchId, "choice" to (choice ?: ""))
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

  private val clockAtAnchor = DecisionClock { anchor }

  @Test
  fun theResolverWritesOneMarkerAndTicksTheVersion() = runTest {
    val db = FakeDb(
      doc = matchFields(version = 7),
      hearts = hearts("u2" to anchor - 90_000L),
    )

    val response = resolveDisconnectOnce(db, fakeRequest("u3"), clockAtAnchor)

    assertEquals(true, response["changed"])
    assertEquals(DisconnectVoteFlow.Action.OPENED, response["action"])
    assertEquals("p2", response["seatId"])
    assertEquals(30_000L, response["retryAfterMillis"])

    val written = db.written!!
    assertEquals(
      setOf(
        DisconnectVoteState.VOTE_FIELD,
        "version",
        "updatedAt",
      ),
      written.keys,
    )
    assertEquals(8, written["version"])
    val entry = entryOf(markerOf(written)!!, "p2")
    assertEquals(DisconnectVotePhase.VOTE.name, entry[DisconnectVoteState.PHASE_FIELD])
    assertFalse(
      DisconnectVoteState.REPLACED_UIDS_FIELD in written,
      "no replacement happened, so the provenance list is untouched",
    )
    assertEquals(1, db.transitions, "one transaction, one write")
  }

  @Test
  fun theCloseRidesOneWriteWithItsBallotsAndTheReplacementList() = runTest {
    val closesAt = anchor + 30_000L
    val db = FakeDb(
      doc = matchFields(vote = mapOf("p2" to openVote("p2", closesAt = closesAt)), version = 3),
      hearts = hearts(),
      ballots = mapOf(
        "u1" to ballot("p2", DisconnectVoteChoice.WAIT),
        "u3" to ballot("p2", DisconnectVoteChoice.WAIT),
        "u4" to ballot("p2", DisconnectVoteChoice.CONTINUE),
      ),
    )

    val response = resolveDisconnectOnce(db, fakeRequest("u2"), DecisionClock { closesAt })

    assertEquals(DisconnectVoteFlow.Action.WAITED, response["action"])
    val entry = entryOf(markerOf(db.written!!)!!, "p2")
    assertEquals(DisconnectVotePhase.WAIT.name, entry[DisconnectVoteState.PHASE_FIELD])
    assertEquals(closesAt + 90_000L, entry[DisconnectVoteState.DEADLINE_FIELD])
    assertEquals(4, db.written!!["version"])
    assertEquals(
      listOf("u1", "u3", "u4"),
      db.deletedBallots,
      "every ballot ends with the vote that owned it",
    )

    // The second pass — the pause ended — records the replacement on the
    // flow's OWN list, appending to whatever the list already held.
    val ended = FakeDb(doc = matchFields(vote = mapOf("p2" to entry), replaced = listOf("u0")))
    val replaced = resolveDisconnectOnce(
      ended,
      fakeRequest("u2"),
      DecisionClock { closesAt + 90_000L },
    )
    assertEquals(DisconnectVoteFlow.Action.REPLACED, replaced["action"])
    assertEquals(
      listOf("u0", "u2"),
      ended.written!![DisconnectVoteState.REPLACED_UIDS_FIELD],
    )
    assertTrue(ended.deletedBallots.isEmpty())
  }

  @Test
  fun aRankedPollCostsNoHeartbeatReadAndWritesNothing() = runTest {
    val db = FakeDb(
      doc = matchFields(mode = "RANKED"),
      hearts = hearts("u2" to anchor - 300_000L),
    )

    val response = resolveDisconnectOnce(db, fakeRequest("u3"), clockAtAnchor)

    assertEquals(false, response["changed"])
    assertEquals(DisconnectVoteFlow.Reason.NOT_APPLICABLE, response["reason"])
    assertNull(response["retryAfterMillis"])
    assertEquals(0, db.heartbeatReads)
    assertNull(db.written)
  }

  /**
   * The HttpsError code a call throws, sampled exactly like SettlementTest's
   * unauthenticated case: constructing the error is JS interop, so the code
   * rides on the thrown object rather than on an `assertFailsWith`.
   */
  private suspend fun thrownCode(block: suspend () -> Any?): String {
    try {
      block()
    } catch (e: Throwable) {
      return e.asDynamic().code as String
    }
    throw AssertionError("expected HttpsError")
  }

  @Test
  fun theOuterGatesHold() = runTest {
    val db = FakeDb(doc = matchFields())

    assertEquals(
      "invalid-argument",
      thrownCode { resolveDisconnectOnce(db, fakeRequest("u3", matchId = ""), clockAtAnchor) },
    )
    assertEquals(
      "not-found",
      thrownCode { resolveDisconnectOnce(FakeDb(doc = null), fakeRequest("u3"), clockAtAnchor) },
    )
    assertEquals(
      "permission-denied",
      thrownCode { resolveDisconnectOnce(db, fakeRequest("u9"), clockAtAnchor) },
    )
    assertEquals(
      "permission-denied",
      thrownCode { castDisconnectOnce(db, fakeRequest("u9", choice = "WAIT"), clockAtAnchor) },
    )
    assertEquals(
      "permission-denied",
      thrownCode { restoreDisconnectOnce(db, fakeRequest("u9")) },
    )
  }

  @Test
  fun aCastWritesTheBallotAndTheCountInOneTransaction() = runTest {
    val db = FakeDb(
      doc = matchFields(vote = mapOf("p2" to openVote("p2"))),
      hearts = hearts(),
    )

    val response = castDisconnectOnce(db, fakeRequest("u3", choice = "WAIT"), clockAtAnchor)

    assertEquals(true, response["recorded"])
    assertEquals("p2", response["seatId"])
    assertEquals(
      ballot("p2", DisconnectVoteChoice.WAIT, castAt = anchor),
      db.writtenBallots["u3"],
    )
    assertEquals(1, entryOf(markerOf(db.written!!)!!, "p2")[DisconnectVoteState.ANSWERED_FIELD])

    // A second attempt is `already` and writes nothing new.
    val again = FakeDb(
      doc = matchFields(vote = mapOf("p2" to openVote("p2", answered = 1))),
      hearts = hearts(),
      ballots = mapOf("u3" to ballot("p2", DisconnectVoteChoice.WAIT)),
    )
    val already = castDisconnectOnce(again, fakeRequest("u3", choice = "CONTINUE"), clockAtAnchor)
    assertEquals(true, already["recorded"])
    assertEquals(true, already["already"])
    assertNull(again.written)
  }

  @Test
  fun aRestoreEndsTheBallotsAndChargesNothing() = runTest {
    val db = FakeDb(
      doc = matchFields(vote = mapOf("p2" to openVote("p2")), version = 5),
      hearts = hearts(),
      ballots = mapOf(
        "u1" to ballot("p2", DisconnectVoteChoice.WAIT),
        "u3" to ballot("p2", DisconnectVoteChoice.WAIT),
      ),
    )

    val response = restoreDisconnectOnce(db, fakeRequest("u2"))

    assertEquals(true, response["restored"])
    assertEquals("p2", response["seatId"])
    assertEquals(emptyMap<String, Any?>(), markerOf(db.written!!))
    assertEquals(6, db.written!!["version"])
    assertFalse(
      DisconnectVoteState.REPLACED_UIDS_FIELD in db.written!!,
      "a borrowal is not a departure — no provenance, no charge",
    )
    assertEquals(listOf("u1", "u3", "u4"), db.deletedBallots)
  }

  // ── the pause the decision machine obeys ─────────────────────────────

  @Test
  fun thePacingReadsSeeEveryPhaseAndThePauseKnowsItsBound() {
    for (phase in DisconnectVotePhase.entries) {
      val fields = matchFields(
        vote = mapOf(
          "p2" to when (phase) {
            DisconnectVotePhase.HOLD -> held("p2")
            DisconnectVotePhase.VOTE -> openVote("p2")
            DisconnectVotePhase.WAIT -> grantedWait("p2", endsAt = anchor + 90_000L)
            DisconnectVotePhase.REPLACED -> replacedEntry("p2")
          },
        ),
      )
      assertEquals(
        setOf("p2"),
        TimeoutResolution.disconnectHeldSeats(fields),
        "every phase is a bot-held seat from the instant of detection",
      )
      if (phase == DisconnectVotePhase.WAIT) {
        assertEquals(90_000L, TimeoutResolution.disconnectPauseRemaining(fields, anchor))
        assertNull(
          TimeoutResolution.disconnectPauseRemaining(fields, anchor + 90_000L),
          "the pause is bounded: at its own end it stops stopping time",
        )
        assertNull(TimeoutResolution.disconnectPauseRemaining(fields, anchor + 95_000L))
      } else {
        assertNull(TimeoutResolution.disconnectPauseRemaining(fields, anchor))
      }
    }
  }

  /**
   * A dealt round-14 fast round, mid-estimate, waiting on p1 — the same
   * document the S61/S73 suites resolve, plus an optional S74 pause.
   */
  private fun pendingDoc(pause: Map<String, Any?>?): Map<String, Any?> = mutableMapOf<String, Any?>(
    "roomId" to "room-1",
    "players" to players,
    "status" to "starting",
    "currentRound" to 14,
    "maxRounds" to 18,
    "extendedRounds" to emptyList<Int>(),
    "dealer" to "u1",
    "turn" to null,
    "seats" to seats,
    "version" to 1,
    "biddingOpen" to true,
    "bids" to linkedMapOf<String, Int?>("p1" to null, "p2" to null, "p3" to null, "p4" to null),
    "lastBidSeat" to null,
    "cardLog" to emptyList<Map<String, Any?>>(),
    "lastCardSeat" to null,
    "cardPhase" to null,
    "biddingLog" to emptyList<Map<String, Any?>>(),
    "gameState" to mapOf("initialized" to true, "dealtRound" to 14),
    "gameType" to "FULL",
    "scoringMode" to "NORMAL",
    "mode" to "UNRANKED",
    "botTier" to "MEDIUM",
    "botPersonality" to "BALANCED",
    "decisionTimerSeconds" to 15,
    "updatedAt" to anchor,
    "botSeats" to emptyList<String>(),
    "timeoutRemovedUids" to emptyList<String>(),
  ).apply {
    if (pause != null) put(DisconnectVoteState.VOTE_FIELD, mapOf("p2" to pause))
  }

  /** The decision machine's seam — it must never get this far while paused. */
  private class PausedTimeoutDb(private val doc: Map<String, Any?>) : TimeoutDb {
    var transitions = 0
      private set

    override suspend fun readMatch(matchId: String): Map<String, Any?>? = doc

    override suspend fun readHands(
      matchId: String,
      round: Int,
      seats: List<String>,
    ): Map<String, List<Card>> =
      error("a granted pause must stop the resolver before any hand is read")

    override suspend fun <T> transact(block: suspend (TimeoutDb.TimeoutTx) -> T): T {
      transitions++
      error("a granted pause must stop the resolver before its transaction")
    }
  }

  @Test
  fun aGrantedPauseStopsTheDecisionMachineBeforeItsHands() = runTest {
    // The pending estimate (20 s bid window) is LONG expired at this clock —
    // and the pause still owns the table: the resolver answers with the
    // pause's own remaining millis and touches nothing.
    val doc = pendingDoc(pause = grantedWait("p2", endsAt = anchor + 60_000L))
    val db = PausedTimeoutDb(doc)

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), DecisionClock { anchor + 20_000L })

    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, response["reason"])
    assertEquals(
      40_000L,
      response["retryAfterMillis"],
      "the number is the pause's, and it is the server's",
    )
    assertEquals(0, db.transitions)
  }

  @Test
  fun aWaitBorrowedSeatStillResolvesNothingInThePausedTable() = runTest {
    // The pause gates the WHOLE table, not just the seat it is about: the
    // pending decision belongs to p1 while p2's grant runs.
    val doc = pendingDoc(pause = grantedWait("p2", endsAt = anchor + 20_000L))
    val db = PausedTimeoutDb(doc)

    val response = resolveTimeoutOnce(db, fakeRequest("u1"), DecisionClock { anchor + 19_000L })

    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, response["reason"])
    assertEquals(1_000L, response["retryAfterMillis"])
    assertEquals(0, db.transitions)
  }

  @Test
  fun anEndedGrantStopsStoppingTheTable() {
    val doc = pendingDoc(pause = grantedWait("p2", endsAt = anchor))
    assertNull(
      TimeoutResolution.disconnectPauseRemaining(doc, anchor),
      "the bound is the bound: at its end the pause is over even before the marker flips",
    )
  }
}
