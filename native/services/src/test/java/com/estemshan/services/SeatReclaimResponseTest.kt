package com.estemshan.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S73 Stage 2, C5 — what the client is allowed to learn from a reclaim, and
 * what a failed call means for the poll.
 *
 * These cover the mapping in the order the poll acts on it: a delivered return
 * is terminal, an owed trick hands back the SERVER's window and nothing else, a
 * seat nobody borrowed is not a wait, `NOT_OWNER` is not a wait either even
 * when the response carries a number, a reason this build does not know is
 * terminal rather than a guess, and a response that is not a map is retried
 * rather than read. The three retryable transport codes are pinned against
 * every other code the SDK can hand back, because the difference between them
 * is the difference between a poll that stops and one that never will.
 *
 * The wire vocabulary (`reclaimed`, `reason`, `retryAfterMillis`,
 * `TRICK_NOT_FINISHED`, `NOT_BORROWED`) and the transport codes' NAMES are
 * repeated here as literals because `:services` cannot see `:functions`, and
 * `FirebaseFunctionsException.Code` cannot be initialized on a plain JVM at
 * all — the same unavoidable duplication [DecisionWindow] documents, and drift
 * between the copies is caught by the instrumented suite against the real
 * callable, not by this file.
 *
 * Deliberately NOT here: the poll's bound and backoff (C6, the ViewModel's own
 * budget), the reclaim's DUE-ness (the pure `SeatReclaim` gate and its
 * `SeatReclaimOnceTest` in `:functions`), and the throw paths, which need a
 * backend that can raise them (C8). Nothing in this file constructs a
 * `FirebaseFunctionsException` or touches the SDK's enum; [SeatReclaimResponse]
 * is exercised through its name-taking halves, and `forCode` — the one-line
 * delegation to them — is proven on a device.
 */
class SeatReclaimResponseTest {

  private companion object {
    val Returned = SeatReclaimPort.Reclaim.Returned
    val HumanHoldsSeat = SeatReclaimPort.Reclaim.HumanHoldsSeat
    val Retryable = SeatReclaimPort.Reclaim.Retryable
    val Denied = SeatReclaimPort.Reclaim.Denied

    /** One bot window — the floor the server puts under every retry. */
    const val BOT_WINDOW_MILLIS = DecisionWindow.BOT_DECISION_SECONDS * 1_000L

    /** The document is waiting on a HUMAN seat: 15 s configured + 5 s of RD21. */
    const val HUMAN_WINDOW_MILLIS = 15_000

    /**
     * `FirebaseFunctionsException.Code`, spelled as NAMES — the SDK type is
     * loaded nowhere in this file because its enum cannot be initialized on a
     * plain JVM (its static initializer needs the Android runtime, which is why
     * [CallableDecisionTimeout] has never had a hermetic suite either). The
     * mapping under test therefore takes the name; [SeatReclaimResponse.forCode]
     * is the one-line delegation, and the device is where the enum itself is
     * exercised.
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

  /** The no-write response as the callable builds it. */
  private fun waiting(
    reason: Any? = "TRICK_NOT_FINISHED",
    retryAfterMillis: Any? = HUMAN_WINDOW_MILLIS,
  ): Map<String, Any?> = buildMap {
    put("ok", true)
    put("reclaimed", false)
    put("reason", reason)
    put("message", "reclaimInactiveSeat: the bot still owes the trick.")
    if (retryAfterMillis != null) put("retryAfterMillis", retryAfterMillis)
  }

  /** The success response as the callable builds it. */
  private fun reclaimed(seatId: String = "p1"): Map<String, Any?> = mapOf(
    "ok" to true,
    "reclaimed" to true,
    "matchId" to "m1",
    "seatId" to seatId,
  )

  private fun outcomeOf(data: Any?) = SeatReclaimResponse.forData(data)

  // ── the answers the poll acts on ─────────────────────────────────────

  @Test
  fun aDeliveredReclaimIsTerminalAndCarriesNoEchoOfTheSeat() {
    val outcome = outcomeOf(reclaimed())

    // The singleton, not a copy: the seat id the server named is dropped, so
    // the poll never ends up with a second, possibly-disagreeing answer to
    // "which seat is mine". The snapshot is the only source of that.
    assertEquals(Returned, outcome)
    assertTrue(outcome === Returned)
  }

  @Test
  fun anOwedTrickHandsBackTheServersWindowAndNothingElse() {
    val waiting = outcomeOf(waiting()) as SeatReclaimPort.Reclaim.WaitingForTrick

    // The number is the SERVER's remaining window, verbatim, and it arrives as
    // an Int over the wire — a client that read it as Long would silently miss
    // every retry this mechanism can give.
    assertEquals(HUMAN_WINDOW_MILLIS.toLong(), waiting.retryAfterMillis)
  }

  @Test
  fun aWindowThatArrivesAsALongReadsTheSameAsAnInt() {
    val fromInt = outcomeOf(waiting(retryAfterMillis = 2_000))
    val fromLong = outcomeOf(waiting(retryAfterMillis = 2_000L))
    val fromDouble = outcomeOf(waiting(retryAfterMillis = 2_000.0))

    // Firestore hands whole numbers to different clients as different widths;
    // all three are the same wait.
    assertEquals(
      SeatReclaimPort.Reclaim.WaitingForTrick(BOT_WINDOW_MILLIS),
      fromInt,
    )
    assertEquals(fromInt, fromLong)
    assertEquals(fromInt, fromDouble)
  }

  @Test
  fun theServersWindowIsPassedThroughRatherThanEditorializedAbout() {
    // The floor is the callable's contract, so a client that clamped it would be
    // second-guessing the one clock that decides DUE-ness. Shorter than the
    // fallback still means the server said "look again sooner".
    val waiting = outcomeOf(waiting(retryAfterMillis = 500)) as SeatReclaimPort.Reclaim.WaitingForTrick

    assertEquals(500L, waiting.retryAfterMillis)
  }

  @Test
  fun aMissingOrUnreadableWindowFallsBackToOneBotWindow() {
    val absent = outcomeOf(waiting(retryAfterMillis = null))
    val nullValue = outcomeOf(waiting(retryAfterMillis = "15 seconds"))
    val zeroWidth = outcomeOf(waiting(retryAfterMillis = true))

    // A `TRICK_NOT_FINISHED` with no number is version skew, not a zero: the
    // poll waits the shortest unit the table runs on instead of tight-looping.
    for (outcome in listOf(absent, nullValue, zeroWidth)) {
      assertEquals(SeatReclaimPort.Reclaim.WaitingForTrick(BOT_WINDOW_MILLIS), outcome)
    }
  }

  @Test
  fun aSeatNobodyBorrowedEndsThePoll() {
    val outcome = outcomeOf(waiting(reason = "NOT_BORROWED", retryAfterMillis = null))

    // Either the reclaim already landed, or another device got it, or the marker
    // was garbage and read fail-closed as "not borrowed". All three mean the
    // human holds the seat now, so there is nothing left to ask.
    assertEquals(HumanHoldsSeat, outcome)
  }

  @Test
  fun notOwnerIsPermanentEvenWhenTheResponseCarriesAWindow() {
    val outcome = outcomeOf(waiting(reason = "NOT_OWNER"))

    // The reason decides, not the presence of a number: a seat this mechanism
    // never borrowed — because the caller occupies no seat, or because Vote Kick
    // or the 15-timeout removal made it permanently bot-driven — is not returned
    // by asking again.
    assertEquals(Denied, outcome)
  }

  @Test
  fun aReasonThisBuildDoesNotKnowIsTerminalRatherThanAGuess() {
    val renamed = outcomeOf(waiting(reason = "SOMETHING_NEW"))
    val noReason = outcomeOf(waiting(reason = null, retryAfterMillis = null))
    val reasonIsANumber = outcomeOf(waiting(reason = 7))

    // Three shapes that are not a reclaim and not a wait. Looping on them would
    // spend calls to learn nothing new, and a response that never said the seat
    // came back has not given the client a seat to hold.
    assertEquals(listOf(Denied, Denied, Denied), listOf(renamed, noReason, reasonIsANumber))
  }

  @Test
  fun aResponseThatIsNotAMapIsRetried() {
    val outcomes = listOf(null, "reclaimed", 1, 0.5, emptyList<Any>(), true).map(::outcomeOf)

    // Not a denial: this layer cannot read a verdict out of a body that is not
    // the response shape, and the callable is idempotent, so a fresh look is
    // safe and a permanent stop is not.
    assertEquals(List(6) { Retryable }, outcomes)
  }

  @Test
  fun reclaimedIsReadAsABooleanAndNothingElseIs() {
    val truthy = listOf(
      reclaimed(),
      mapOf("reclaimed" to true),
      mapOf("reclaimed" to true, "reason" to "TRICK_NOT_FINISHED", "retryAfterMillis" to 2_000),
    ).map(::outcomeOf)

    // `reclaimed: true` wins over any reason beside it, and needs no `ok`, no
    // matchId and no seatId — the response's minimum is the one key.
    assertEquals(List(3) { Returned }, truthy)
    assertEquals(HumanHoldsSeat, outcomeOf(mapOf("reclaimed" to false, "reason" to "NOT_BORROWED")))
    // A truthy-looking string is not a boolean: only the real flag returns.
    assertEquals(Denied, outcomeOf(mapOf("reclaimed" to "true")))
  }

  // ── no accumulator, no counter reaches a typed outcome ───────────────

  @Test
  fun thePrivateFieldsAreNotReadEvenWhenAResponseCarriesThem() {
    val leaked = outcomeOf(
      waiting() + mapOf(
        "accumulatedMillis" to 60_000L,
        "openWindowStartedAt" to 40_000L,
        "inactiveSeatActionCursor" to 14_001_001L,
        "timeoutCount" to 13,
        "takenOverAtMillis" to 5_000L,
        "roundAtTakeover" to 14L,
        "trickAtTakeover" to 1L,
        "updatedAt" to 1_000_000L,
        "departureReason" to "INACTIVE",
      ),
    )
    val leakedReturn = outcomeOf(reclaimed() + mapOf("timeoutCount" to 15, "accumulatedMillis" to 60_000L))

    // Data-class equality says it plainly: an outcome loaded with every private
    // field the mechanism keeps is INDISTINGUISHABLE from the lean one, because
    // this layer reads three keys and no accumulator, cursor, counter or marker
    // field can find its way into the poll's vocabulary. RD19's clock and RD20's
    // counter stay where the rules put them.
    assertEquals(outcomeOf(waiting()), leaked)
    assertEquals(Returned, leakedReturn)
  }

  @Test
  fun theOutcomeVocabularyIsExactlyTheFiveThePollActsOn() {
    val outcomes = listOf(
      Returned,
      SeatReclaimPort.Reclaim.WaitingForTrick(BOT_WINDOW_MILLIS),
      HumanHoldsSeat,
      Retryable,
      Denied,
    )

    // Exhaustive with no else: an added variant stops compiling rather than
    // silently becoming a `Denied`, and the only value ANY variant can carry is
    // the wait — which is why no accumulator or counter has anywhere to go.
    val described = outcomes.map {
      when (it) {
        is SeatReclaimPort.Reclaim.Returned -> "returned"
        is SeatReclaimPort.Reclaim.WaitingForTrick -> "waiting:${it.retryAfterMillis}"
        is SeatReclaimPort.Reclaim.HumanHoldsSeat -> "holds"
        is SeatReclaimPort.Reclaim.Retryable -> "retry"
        is SeatReclaimPort.Reclaim.Denied -> "denied"
      }
    }
    assertEquals(listOf("returned", "waiting:2000", "holds", "retry", "denied"), described)
  }

  // ── the codes: which failures come back ─────────────────────────────

  @Test
  fun theThreeRetryableCodesAreTheTransportOnes() {
    for (name in listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")) {
      assertEquals("$name must be retried", Retryable, SeatReclaimResponse.forCodeName(name))
    }
  }

  @Test
  fun everyOtherCodeTheServerCanChooseIsPermanent() {
    val retryable = listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")

    // The reclaim throws for the rest: a missing match, a caller who is not a
    // player, a payload with no matchId, a document whose history it refuses to
    // invent. None is revisited by asking again, and a poll that treated one as
    // transient would keep a player from learning their seat is not coming back.
    val denials = ALL_SDK_CODE_NAMES - retryable
    assertEquals(ALL_SDK_CODE_NAMES.size - 3, denials.size)
    for (name in denials) {
      assertEquals("$name must stop the poll", Denied, SeatReclaimResponse.forCodeName(name))
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
  fun noCodeEverAnswersForTheSeat() {
    for (name in ALL_SDK_CODE_NAMES) {
      val outcome = SeatReclaimResponse.forCodeName(name)

      // A transport failure is not a verdict about a borrow: it can never read
      // as a return, a wait, or "you already hold it", or a poll would either
      // stop a player who is coming back or keep one who has no seat asking.
      assertTrue(
        "$name must not answer about the seat, was $outcome",
        outcome === Retryable || outcome === Denied,
      )
    }
  }

  @Test
  fun aCodeNameThisBuildHasNeverSeenIsTerminal() {
    assertEquals(Denied, SeatReclaimResponse.forCodeName("A_CODE_ADDED_IN_A_LATER_SDK"))

    // The direction the else-branch chooses: an unknown code stops the poll. If
    // the SDK ever grows a transient code, this is where it must be named — and
    // until then a client does not loop on a failure it cannot classify.
  }
}
