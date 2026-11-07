package com.estemshan.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S74 (DEC-DISC-01) — what a client is allowed to learn from each of the
 * disconnect flow's three callables, and what a failed call means for its poll.
 *
 * These cover the mapping in the order a screen acts on it: a committed
 * transition re-arms the poll at the SERVER's own number, a quiet pass waits at
 * the server's own number, RANKED's `NOT_APPLICABLE` is terminal and wins over
 * any number beside it, a cast that was recorded never double-counts, a closed
 * ballot is its own answer rather than a retry, the waiting ending is a
 * replacement and never a wait, and a response that is not a map is retried
 * rather than read. The three retryable transport codes are pinned against
 * every other code the SDK can hand back, per call, because the difference
 * between them is the difference between a poll that stops and one that never
 * will.
 *
 * The wire vocabulary (`changed`, `recorded`, `already`, `restored`, `reason`,
 * `retryAfterMillis`, `NOT_APPLICABLE`, `BALLOT_CLOSED`, `NOT_HELD`,
 * `REPLACED`) and the transport codes' NAMES are repeated here as literals
 * because `:services` cannot see `:functions`, and
 * `FirebaseFunctionsException.Code` cannot be initialized on a plain JVM at
 * all — the same unavoidable duplication [DecisionWindow] documents, and drift
 * between the copies is caught by the instrumented suite against the real
 * callables, not by this file.
 *
 * Deliberately NOT here: the poll's bound and backoff (the screen's own
 * budget), the flow's DUE-ness (the pure `DisconnectVoteFlow` and its
 * `DisconnectVoteFlowTest` in `:functions`), and the throw paths, which need a
 * backend that can raise them. Nothing in this file constructs a
 * `FirebaseFunctionsException` or touches the SDK's enum; [DisconnectVoteResponse]
 * is exercised through its name-taking halves, and the `for*Code` one-line
 * delegations are proven on a device.
 */
class DisconnectVoteResponseTest {

  private companion object {
    val NotApplicable = DisconnectVotePort.Resolve.NotApplicable
    val DeniedResolve = DisconnectVotePort.Resolve.Denied
    val RetryableResolve = DisconnectVotePort.Resolve.Retryable

    val Recorded = DisconnectVotePort.Cast.Recorded
    val AlreadyRecorded = DisconnectVotePort.Cast.AlreadyRecorded
    val BallotClosed = DisconnectVotePort.Cast.BallotClosed
    val RetryableCast = DisconnectVotePort.Cast.Retryable

    val Restored = DisconnectVotePort.Restore.Restored
    val Replaced = DisconnectVotePort.Restore.Replaced
    val NothingHeld = DisconnectVotePort.Restore.NothingHeld
    val DeniedRestore = DisconnectVotePort.Restore.Denied
    val RetryableRestore = DisconnectVotePort.Restore.Retryable

    /** One bot window — the floor the server puts under every retry. */
    const val BOT_WINDOW_MILLIS = DecisionWindow.BOT_DECISION_SECONDS * 1_000L

    /** The ruled 30 s ballot, exactly as the server returns it. */
    const val BALLOT_MILLIS = 30_000L

    /**
     * `FirebaseFunctionsException.Code`, spelled as NAMES — the SDK type is
     * loaded nowhere in this file because its enum cannot be initialized on a
     * plain JVM (its static initializer needs the Android runtime). The mapping
     * under test therefore takes the name; the `for*Code` delegations are the
     * one-line hops, and the device is where the enum itself is exercised.
     */
    val ALL_SDK_CODE_NAMES = listOf(
      "CANCELLED",
      "UNKNOWN",
      "INVALID_ARGUMENT",
      "DEADLINE_EXCEEDED",
      "NOT_FOUND",
      "ALREADY_EXISTS",
      "PERMISSION_DENIED",
      "RESOURCE_EXHAUSTED",
      "FAILED_PRECONDITION",
      "ABORTED",
      "OUT_OF_RANGE",
      "UNIMPLEMENTED",
      "INTERNAL",
      "UNAVAILABLE",
      "DATA_LOSS",
      "UNAUTHENTICATED",
    )
  }

  private fun advanced(millis: Long) = DisconnectVotePort.Resolve.Advanced(millis)
  private fun waiting(millis: Long) = DisconnectVotePort.Resolve.Waiting(millis)
  private fun refused(reason: String) = DisconnectVotePort.Cast.Refused(reason)

  // ── the resolver responses as the callable builds them ───────────────

  /** A committed transition: `changed: true`, an action, the server's number. */
  private fun applied(
    action: Any? = "OPENED",
    retryAfterMillis: Any? = BALLOT_MILLIS,
  ): Map<String, Any?> = buildMap {
    put("ok", true)
    put("changed", true)
    put("matchId", "m1")
    put("action", action)
    put("seatId", "p2")
    if (retryAfterMillis != null) put("retryAfterMillis", retryAfterMillis)
  }

  /** A pass with nothing due: `changed: false`, a reason, the server's number. */
  private fun quiet(
    reason: Any? = "NOT_DETECTED",
    retryAfterMillis: Any? = 89_000,
  ): Map<String, Any?> = buildMap {
    put("ok", true)
    put("changed", false)
    put("reason", reason)
    put("message", "resolveDisconnectVote: nothing was due.")
    if (retryAfterMillis != null) put("retryAfterMillis", retryAfterMillis)
  }

  // ── the cast responses ───────────────────────────────────────────────

  private fun recordedResponse(already: Boolean = false): Map<String, Any?> = buildMap {
    put("ok", true)
    put("recorded", true)
    put("matchId", "m1")
    put("seatId", "p2")
    if (already) put("already", true)
  }

  private fun castRefusal(reason: Any? = "NOT_ELIGIBLE"): Map<String, Any?> = mapOf(
    "ok" to true,
    "recorded" to false,
    "reason" to reason,
    "message" to "castDisconnectVote: the ballot does not ask this caller.",
  )

  // ── the restore responses ────────────────────────────────────────────

  private fun restoredResponse(): Map<String, Any?> = mapOf(
    "ok" to true,
    "restored" to true,
    "matchId" to "m1",
    "seatId" to "p2",
  )

  private fun restoreRefusal(reason: Any? = "NOT_HELD"): Map<String, Any?> = mapOf(
    "ok" to true,
    "restored" to false,
    "reason" to reason,
    "message" to "restoreDisconnectedSeat: no borrowal to end.",
  )

  private fun resolveOutcomeOf(data: Any?) = DisconnectVoteResponse.forResolveData(data)
  private fun castOutcomeOf(data: Any?) = DisconnectVoteResponse.forCastData(data)
  private fun restoreOutcomeOf(data: Any?) = DisconnectVoteResponse.forRestoreData(data)

  // ── the resolver: the answers the poll acts on ───────────────────────

  @Test
  fun aCommittedTransitionReArmsThePollAtTheServersOwnNumber() {
    val outcome = resolveOutcomeOf(applied(action = "REPLACED"))

    // The action and the seat are dropped: whatever transition happened, the
    // poll's next move is the same — wait the number the server computed and
    // keep watching, because the snapshot listener carries what changed.
    assertEquals(advanced(BALLOT_MILLIS), outcome)
    assertEquals(
      resolveOutcomeOf(applied(action = "OPENED")),
      resolveOutcomeOf(applied(action = "WAITED")),
    )
  }

  @Test
  fun aQuietPassWaitsAtTheServersOwnNumber() {
    val outcome = resolveOutcomeOf(quiet()) as DisconnectVotePort.Resolve.Waiting

    // The number is the SERVER's next-due instant — a window's close, a pause's
    // end, the earliest heartbeat that would cross the threshold — and it
    // arrives as an Int over the wire.
    assertEquals(89_000L, outcome.retryAfterMillis)
  }

  @Test
  fun theServersNumberIsPassedThroughRatherThanEditorializedAbout() {
    // The floor is the callable's contract, so a client that clamped it would
    // be second-guessing the one clock that decides DUE-ness. Shorter than the
    // fallback still means the server said "look again sooner" — a heartbeat
    // about to cross the staleness threshold is exactly that.
    val outcome = resolveOutcomeOf(quiet(retryAfterMillis = 500)) as DisconnectVotePort.Resolve.Waiting

    assertEquals(500L, outcome.retryAfterMillis)
  }

  @Test
  fun aWindowThatArrivesAsALongReadsTheSameAsAnInt() {
    val fromInt = resolveOutcomeOf(quiet(retryAfterMillis = 2_000))
    val fromLong = resolveOutcomeOf(quiet(retryAfterMillis = 2_000L))
    val fromDouble = resolveOutcomeOf(quiet(retryAfterMillis = 2_000.0))

    assertEquals(waiting(BOT_WINDOW_MILLIS), fromInt)
    assertEquals(fromInt, fromLong)
    assertEquals(fromInt, fromDouble)
  }

  @Test
  fun aMissingOrUnreadableWindowFallsBackToOneBotWindow() {
    val absent = resolveOutcomeOf(quiet(retryAfterMillis = null))
    val unreadable = resolveOutcomeOf(quiet(retryAfterMillis = "30 seconds"))
    val truthy = resolveOutcomeOf(quiet(retryAfterMillis = true))

    // A quiet pass with no number is version skew, not a zero: the poll waits
    // the shortest unit the table runs on instead of tight-looping.
    for (outcome in listOf(absent, unreadable, truthy)) {
      assertEquals(waiting(BOT_WINDOW_MILLIS), outcome)
    }
  }

  @Test
  fun rankedNeverAsksAgainEvenBesideANumber() {
    val bare = resolveOutcomeOf(quiet(reason = "NOT_APPLICABLE", retryAfterMillis = null))
    val withNumber = resolveOutcomeOf(quiet(reason = "NOT_APPLICABLE", retryAfterMillis = 30_000))

    // The reason decides, not the presence of a number, and the answer is
    // terminal by design: no future look in RANKED will ever have anything to
    // do, so scheduling one would poll forever for a question never asked.
    assertEquals(NotApplicable, bare)
    assertEquals(NotApplicable, withNumber)
    assertTrue(withNumber === NotApplicable)
  }

  @Test
  fun aResponseThatSaysNeitherDirectionIsADenialRatherThanAWait() {
    val okOnly = resolveOutcomeOf(mapOf("ok" to true))
    val changedIsAString = resolveOutcomeOf(mapOf("changed" to "true"))
    val changedIsANumber = resolveOutcomeOf(mapOf("changed" to 1))

    // A poll that guessed "wait" from a shape it did not understand would keep
    // looking at a table it was never part of; the terminal direction is the
    // honest one.
    assertEquals(listOf(DeniedResolve, DeniedResolve, DeniedResolve), listOf(okOnly, changedIsAString, changedIsANumber))
  }

  @Test
  fun aResponseThatIsNotAMapIsRetriedForEveryCall() {
    val shapes: List<Any?> = listOf(null, "recorded", 1, 0.5, emptyList<Any>(), true)

    // Not a denial: this layer cannot read a verdict out of a body that is not
    // the response shape, and every callable is idempotent, so a fresh look is
    // safe and a permanent stop is not.
    assertEquals(List(6) { RetryableResolve }, shapes.map(::resolveOutcomeOf))
    assertEquals(List(6) { RetryableCast }, shapes.map(::castOutcomeOf))
    assertEquals(List(6) { RetryableRestore }, shapes.map(::restoreOutcomeOf))
  }

  @Test
  fun thePrivateFieldsAreNotReadEvenWhenAResponseCarriesThem() {
    val leaked = resolveOutcomeOf(
      applied() + mapOf(
        "lastSeenAt" to 1_000_000L,
        "detectedAtMillis" to 1_000_000L,
        "deadlineMillis" to 1_030_000L,
        "replacementAtMillis" to 1_120_000L,
        "answeredCount" to 2,
        "ballotChoice" to "WAIT",
        "verdict" to "WAIT",
        "disconnectReplacedUids" to listOf("u9"),
        "timeoutCount" to 13,
        "updatedAt" to 1_000_000L,
      ),
    )
    val leakedWait = resolveOutcomeOf(quiet() + mapOf("answeredCount" to 3, "lastSeenAt" to 42L))

    // Data-class equality says it plainly: an outcome loaded with every private
    // field the mechanism keeps is INDISTINGUISHABLE from the lean one, because
    // this layer reads two keys and no heartbeat, ballot, count or marker field
    // can find its way into the poll's vocabulary. The heartbeat in particular
    // is unreadable to every client by rule, so a response that carried one
    // would still not reach a screen through this layer.
    assertEquals(resolveOutcomeOf(applied()), leaked)
    assertEquals(resolveOutcomeOf(quiet()), leakedWait)
  }

  @Test
  fun theResolveVocabularyIsExactlyTheFiveThePollActsOn() {
    val outcomes = listOf(
      advanced(BALLOT_MILLIS),
      waiting(BOT_WINDOW_MILLIS),
      NotApplicable,
      DeniedResolve,
      RetryableResolve,
    )

    // Exhaustive with no else: an added variant stops compiling rather than
    // silently becoming a `Denied`, and the only value ANY variant can carry is
    // a wait — which is why no count, no ballot and no charge has anywhere to go.
    val described = outcomes.map {
      when (it) {
        is DisconnectVotePort.Resolve.Advanced -> "advanced:${it.retryAfterMillis}"
        is DisconnectVotePort.Resolve.Waiting -> "waiting:${it.retryAfterMillis}"
        is DisconnectVotePort.Resolve.NotApplicable -> "ranked"
        is DisconnectVotePort.Resolve.Denied -> "denied"
        is DisconnectVotePort.Resolve.Retryable -> "retry"
      }
    }
    assertEquals(listOf("advanced:30000", "waiting:2000", "ranked", "denied", "retry"), described)
  }

  // ── the cast: one answer, counted once ───────────────────────────────

  @Test
  fun recordedIsTheAnswerAndAlreadyIsTheIdempotentRepeat() {
    val first = castOutcomeOf(recordedResponse())
    val repeat = castOutcomeOf(recordedResponse(already = true))

    // The singleton, not a copy: the seat id the server named is dropped, so
    // the caller never ends up with a second answer to "which ballot is mine".
    assertEquals(Recorded, first)
    assertTrue(first === Recorded)
    assertEquals(AlreadyRecorded, repeat)
  }

  @Test
  fun recordedWinsOverAnyReasonBesideIt() {
    val truthy = listOf(
      recordedResponse(),
      mapOf("recorded" to true),
      mapOf("recorded" to true, "reason" to "BALLOT_CLOSED", "retryAfterMillis" to 30_000),
    ).map(::castOutcomeOf)

    // `recorded: true` wins over any reason beside it, and needs no `ok`, no
    // matchId and no seatId — the response's minimum is the one key.
    assertEquals(List(3) { Recorded }, truthy)
    // A truthy-looking string is not a boolean: only the real flag records.
    assertEquals(refused("UNKNOWN"), castOutcomeOf(mapOf("recorded" to "true")))
  }

  @Test
  fun aClosedBallotIsItsOwnAnswerRatherThanARetry() {
    val outcome = castOutcomeOf(castRefusal(reason = "BALLOT_CLOSED"))

    // The 30 s window passed before the answer landed. The resolver's own poll
    // settles the ballot, so there is nothing here to retry — and treating the
    // refusal as Unknown would only confuse the log.
    assertEquals(BallotClosed, outcome)
  }

  @Test
  fun refusalsCarryTheirServerReasonVerbatim() {
    val notEligible = castOutcomeOf(castRefusal(reason = "NOT_ELIGIBLE"))
    val noBallot = castOutcomeOf(castRefusal(reason = "NO_BALLOT"))
    val notApplicable = castOutcomeOf(castRefusal(reason = "NOT_APPLICABLE"))

    // Three dead ends for the cast button, three names kept for the log: a
    // ballot that does not ask this caller, no ballot open at all, a mode that
    // never asks. All terminal — asking again cannot make a ballot open onto
    // this caller.
    assertEquals(refused("NOT_ELIGIBLE"), notEligible)
    assertEquals(refused("NO_BALLOT"), noBallot)
    assertEquals(refused("NOT_APPLICABLE"), notApplicable)
  }

  @Test
  fun aRefusalWithNoReadableReasonIsStillARefusal() {
    val noReason = castOutcomeOf(mapOf("ok" to true, "recorded" to false))
    val reasonIsANumber = castOutcomeOf(castRefusal(reason = 7))

    // Nothing was recorded and nothing explained why: the honest default is a
    // refusal, never a retry that would spin a UI off a malformed body.
    assertEquals(listOf(refused("UNKNOWN"), refused("UNKNOWN")), listOf(noReason, reasonIsANumber))
  }

  // ── the restore: coming back, or not ─────────────────────────────────

  @Test
  fun aReturnedSeatIsTerminalAndCarriesNoEchoOfTheSeat() {
    val outcome = restoreOutcomeOf(restoredResponse())

    // The seat id the server named is dropped; the snapshot is the only source
    // of "which seat is mine". Restored is also terminal: nothing needs to be
    // delivered — the marker change rides the snapshot listener.
    assertEquals(Restored, outcome)
    assertTrue(outcome === Restored)
  }

  @Test
  fun restoredWinsOverAnyReasonBesideIt() {
    val leaked = restoreOutcomeOf(restoredResponse() + mapOf("reason" to "REPLACED"))

    assertEquals(Restored, leaked)
  }

  @Test
  fun theWaitingEndedIsReplacedAndNeverAWait() {
    val outcome = restoreOutcomeOf(restoreRefusal(reason = "REPLACED"))

    // Permanence: the ballot and any granted pause ended without a reconnect,
    // so the seat is the configured bot's for the rest of the match. Terminal
    // by design, and carrying nothing: the document records the termination,
    // and the charge table (UNRANKED's 2 Koz; ROOM's none; RANKED has no path)
    // is applied downstream of the record, never through this outcome.
    assertEquals(Replaced, outcome)
  }

  @Test
  fun aSeatNobodyHeldIsTheGoalNotAFailure() {
    val outcome = restoreOutcomeOf(restoreRefusal(reason = "NOT_HELD"))

    // Either another device's restore already landed or the marker read
    // fail-closed as "not held" — both mean the seat is the caller's, so there
    // is nothing left to ask.
    assertEquals(NothingHeld, outcome)
  }

  @Test
  fun aRestoreReasonThisBuildDoesNotKnowIsTerminalRatherThanAGuess() {
    val renamed = restoreOutcomeOf(restoreRefusal(reason = "SOMETHING_NEW"))
    val noReason = restoreOutcomeOf(mapOf("ok" to true, "restored" to false))
    val reasonIsANumber = restoreOutcomeOf(restoreRefusal(reason = 7))

    // Three shapes that are not a return and not a wait. A response that never
    // said the seat came back has not given the client a seat to hold.
    assertEquals(listOf(DeniedRestore, DeniedRestore, DeniedRestore), listOf(renamed, noReason, reasonIsANumber))
  }

  @Test
  fun theRestoreVocabularyIsExactlyTheFiveTheUIActsOn() {
    val outcomes = listOf(Restored, Replaced, NothingHeld, DeniedRestore, RetryableRestore)

    val described = outcomes.map {
      when (it) {
        is DisconnectVotePort.Restore.Restored -> "restored"
        is DisconnectVotePort.Restore.Replaced -> "replaced"
        is DisconnectVotePort.Restore.NothingHeld -> "not-held"
        is DisconnectVotePort.Restore.Denied -> "denied"
        is DisconnectVotePort.Restore.Retryable -> "retry"
      }
    }
    assertEquals(listOf("restored", "replaced", "not-held", "denied", "retry"), described)
  }

  // ── the codes: which failures come back, per call ────────────────────

  @Test
  fun theThreeRetryableCodesAreTheTransportOnes() {
    for (name in listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")) {
      assertEquals("$name must be retried by resolve", RetryableResolve, DisconnectVoteResponse.forResolveCodeName(name))
      assertEquals("$name must be retried by cast", RetryableCast, DisconnectVoteResponse.forCastCodeName(name))
      assertEquals("$name must be retried by restore", RetryableRestore, DisconnectVoteResponse.forRestoreCodeName(name))
    }
  }

  @Test
  fun everyOtherCodeTheServerCanChooseIsTerminal() {
    val retryable = listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")

    // The callables throw for the rest: a missing match, a caller who is not a
    // player, a payload with no matchId, a document that is not a match — none
    // is revisited by asking again. The cast keeps the code's own name as its
    // refusal reason; nothing about a transport failure records an answer, and
    // a poll that treated denial as transient would keep a player from learning
    // their seat is not coming back.
    val denials = ALL_SDK_CODE_NAMES - retryable
    assertEquals(ALL_SDK_CODE_NAMES.size - 3, denials.size)
    for (name in denials) {
      assertEquals("$name must stop the resolve poll", DeniedResolve, DisconnectVoteResponse.forResolveCodeName(name))
      assertEquals("$name must refuse the cast", refused(name), DisconnectVoteResponse.forCastCodeName(name))
      assertEquals("$name must stop the restore poll", DeniedRestore, DisconnectVoteResponse.forRestoreCodeName(name))
    }
  }

  @Test
  fun theCodeNamesMirroredHereAreSixteenDistinctOnes() {
    // This list is the SDK's enum spelled as names, so its own completeness is
    // worth a pin: a duplicated or mistyped entry would silently drop a code
    // from the loop above rather than fail.
    assertEquals(16, ALL_SDK_CODE_NAMES.size)
    assertEquals(ALL_SDK_CODE_NAMES.size, ALL_SDK_CODE_NAMES.distinct().size)
  }

  @Test
  fun noCodeEverAnswersForTheFlow() {
    for (name in ALL_SDK_CODE_NAMES) {
      val resolve = DisconnectVoteResponse.forResolveCodeName(name)
      val cast = DisconnectVoteResponse.forCastCodeName(name)
      val restore = DisconnectVoteResponse.forRestoreCodeName(name)

      // A transport failure is not a verdict about the table: it can never read
      // as a committed transition, as RANKED's terminal answer, as a recorded
      // ballot, as a closed ballot, or as a seat returned or replaced — or a
      // poll would either stop a player whose seat is coming back or keep one
      // whose seat is gone asking forever.
      assertTrue("$name must not advance the resolve poll", resolve === RetryableResolve || resolve === DeniedResolve)
      assertTrue("$name must not answer the ballot", cast === RetryableCast || cast is DisconnectVotePort.Cast.Refused)
      assertTrue(
        "$name must not answer about the seat",
        restore === RetryableRestore || restore === DeniedRestore,
      )
    }
  }

  @Test
  fun aCodeNameThisBuildHasNeverSeenIsTerminal() {
    val name = "A_CODE_ADDED_IN_A_LATER_SDK"

    // The direction the else-branches choose: an unknown code stops the poll.
    // If the SDK ever grows a transient code, this is where it must be named —
    // and until then a client does not loop on a failure it cannot classify.
    assertEquals(DeniedResolve, DisconnectVoteResponse.forResolveCodeName(name))
    assertEquals(refused(name), DisconnectVoteResponse.forCastCodeName(name))
    assertEquals(DeniedRestore, DisconnectVoteResponse.forRestoreCodeName(name))
  }
}
