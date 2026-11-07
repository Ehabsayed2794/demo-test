package com.estemshan.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settlement — what a client is allowed to learn from the `settleMatch`
 * callable's response, and what a failed call means for its one attempt.
 *
 * These cover the mapping in the order the screen acts on it: a settlement
 * carries the caller's OWN movement and the server's replay marker and
 * nothing else, an absent or malformed `you` is "nothing to reveal" rather
 * than a guess, the legacy pre-S6 `NOT_RANKED` no-op is still terminal with
 * its mode kept for the log, a response that says neither direction is a
 * denial, and a response that is not a map is retried rather than read. The
 * three retryable transport codes are pinned against every other code the
 * SDK can hand back, because the difference between them is the difference
 * between a first attempt that stops and one that never will.
 *
 * The wire vocabulary (`settled`, `replayed`, `reason`, `mode`, `NOT_RANKED`
 * (legacy pre-S6), `you`, `previousRp`, `delta`) and the transport codes'
 * NAMES are repeated here as literals because `:services` cannot see
 * `:functions`, and `FirebaseFunctionsException.Code` cannot be initialized
 * on a plain JVM at all — the same unavoidable duplication [DecisionWindow]
 * documents. And this file is the wire contract's ONLY guard today: no
 * settlement emulator or device suite exists, and the server half that ships
 * the vocabulary is itself unshipped, so a drifted copy is caught here or
 * nowhere.
 *
 * Deliberately NOT here: the view model's once-per-session trigger and its
 * retry bound (the screen's own budget), the settlement's recompute (the
 * pure `Settlement` core and its tests in `:functions`), and the throw
 * paths, which need a backend that can raise them. Nothing in this file
 * constructs a `FirebaseFunctionsException` or touches the SDK's enum;
 * [SettlementResponse] is exercised through its name-taking halves, and the
 * `forCode` delegation — one line, reading the enum's own name back — is
 * pinned by inspection alone until a device or emulator suite can reach it.
 */
class SettlementResponseTest {

  private companion object {
    val Denied = SettlementPort.SettlementOutcome.Denied
    val Retryable = SettlementPort.SettlementOutcome.Retryable

    /**
     * `FirebaseFunctionsException.Code`, spelled as NAMES — the SDK type is
     * loaded nowhere in this file because its enum cannot be initialized on
     * a plain JVM (its static initializer needs the Android runtime). The
     * mapping under test therefore takes the name; the `forCode` hop reads
     * the same name back, and no suite on any runtime exercises it yet.
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

  // ── the settlement response as the callable builds it ────────────────

  /** RD12's authority verdict: the recomputed result, plus the caller's `you`. */
  private fun settledResponse(
    replayed: Any? = false,
    you: Any? = youResponse(),
  ): Map<String, Any?> = buildMap {
    put("ok", true)
    put("settled", true)
    if (replayed != null) put("replayed", replayed)
    put("matchId", "m1")
    put("corrected", false)
    put("finalScores", mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77))
    put("winnerIds", listOf("p3"))
    put("claimedScores", mapOf("p1" to 120, "p2" to 98, "p3" to 134, "p4" to 77))
    put("claimedWinners", listOf("p3"))
    put("rounds", 1)
    if (you != null) put("you", you)
  }

  /**
   * The CALLING player's own movement — `you` on the wire. The raw `rp` the
   * contract carries beside the pair is not parameterized here: the mapping
   * is meant to be indifferent to it, and the dedicated test below drives
   * `rp` present and `rp` nonsense through raw maps instead.
   */
  private fun youResponse(
    previousRp: Any? = 600,
    delta: Any? = 30,
  ): Map<String, Any?> = buildMap {
    if (previousRp != null) put("previousRp", previousRp)
    if (delta != null) put("delta", delta)
  }

  /**
   * A legacy pre-S6 mode-gate no-op (RD28): the current server settles
   * ROOM/UNRANKED stats-only, but a stored response or an older deployed
   * function can still answer this shape, and the client must read it.
   */
  private fun notRankedResponse(mode: Any? = "ROOM"): Map<String, Any?> = mapOf(
    "ok" to true,
    "settled" to false,
    "reason" to "NOT_RANKED",
    "mode" to mode,
  )

  private fun outcomeOf(data: Any?) = SettlementResponse.forData(data)

  private fun movementOf(outcome: Any?): RpMovement? =
    (outcome as? SettlementPort.SettlementOutcome.Settled)?.you

  // ── the settled: one movement, the caller's own ──────────────────────

  @Test
  fun aSettlementCarriesTheCallersOwnMovementAndTheReplayMarker() {
    val first = outcomeOf(settledResponse(replayed = false))
    val replay = outcomeOf(settledResponse(replayed = true))

    // The movement is the caller's, read whole from `you`; the replay marker
    // is the server's own record of which kind of answer this was. Nothing
    // about other players' ranks (RD13) has anywhere to arrive through, so
    // no field of the outcome can carry one.
    assertEquals(RpMovement(600, 30), movementOf(first))
    assertEquals(
      SettlementPort.SettlementOutcome.Settled(replayed = false, you = RpMovement(600, 30)),
      first,
    )
    assertEquals(
      SettlementPort.SettlementOutcome.Settled(replayed = true, you = RpMovement(600, 30)),
      replay,
    )
  }

  @Test
  fun aReplayOfTheRecordedMovementReadsTheSameAsAFirstExecution() {
    // The claim record replays the recorded outcome with `replayed: true` —
    // a redelivery's second call learns the same movement, which is what
    // lets the reveal survive a rebind. Only the marker differs.
    val first = outcomeOf(settledResponse(replayed = false))
    val replay = outcomeOf(settledResponse(replayed = true))

    assertEquals(movementOf(first), movementOf(replay))
    assertEquals(
      (first as SettlementPort.SettlementOutcome.Settled).replayed,
      false,
    )
    assertEquals(
      (replay as SettlementPort.SettlementOutcome.Settled).replayed,
      true,
    )
  }

  @Test
  fun theMovementIsReadWhateverTheNumericWireType() {
    val fromInt = outcomeOf(settledResponse())
    val fromLong = outcomeOf(settledResponse(you = youResponse(600L, 30L)))
    val fromDouble = outcomeOf(settledResponse(you = youResponse(600.0, 30.0)))

    // Firestore stores integers as 64-bit and callables cross the wire as
    // JSON numbers; the reveal is the same pair of integers either way.
    assertEquals(RpMovement(600, 30), movementOf(fromInt))
    assertEquals(movementOf(fromInt), movementOf(fromLong))
    assertEquals(movementOf(fromInt), movementOf(fromDouble))
  }

  @Test
  fun anAbsentOrUnreadableYouIsNoMovementRatherThanAGuess() {
    val keyAbsent = outcomeOf(settledResponse(you = null))
    val keyExplicitNull = outcomeOf(mapOf("ok" to true, "settled" to true, "you" to null))
    val notAMap = outcomeOf(settledResponse(you = "600/630/30"))
    val aList = outcomeOf(settledResponse(you = listOf(600, 630, 30)))

    // `you` ABSENT is the realistic shape today: forward-looking wire, no
    // shipped server build carries it yet (and the wire form of an explicit
    // null arrives the same). A reveal built from numbers the
    // client invented would be worse than plain standings, so the honest
    // reading is "nothing to reveal", never a zero.
    for (outcome in listOf(keyAbsent, keyExplicitNull, notAMap, aList)) {
      assertTrue(outcome is SettlementPort.SettlementOutcome.Settled)
      assertNull(movementOf(outcome))
    }
  }

  @Test
  fun aPartialYouIsNoMovement() {
    val noPrevious = outcomeOf(settledResponse(you = youResponse(previousRp = null)))
    val noDelta = outcomeOf(settledResponse(you = youResponse(delta = null)))
    val extraOnly = outcomeOf(settledResponse(you = mapOf("delta" to 30, "seasonId" to "S12")))
    val stringNumber = outcomeOf(settledResponse(you = youResponse(delta = "30")))
    val unreadable = outcomeOf(settledResponse(you = youResponse(previousRp = true)))

    // One missing or unreadable field voids the whole movement: a partial
    // `you` is a shape this build cannot read, and revealing a delta whose
    // anchor RP is unknown would be fabricating half an answer.
    for (outcome in listOf(noPrevious, noDelta, extraOnly, stringNumber, unreadable)) {
      assertNull(movementOf(outcome))
    }
  }

  @Test
  fun theRawRpBesideThePairIsNeverWhatTheRevealReads() {
    val rpPresent = outcomeOf(
      settledResponse(you = mapOf("previousRp" to 600, "rp" to 630, "delta" to 30)),
    )
    val rpJunk = outcomeOf(
      settledResponse(you = mapOf("previousRp" to 600, "rp" to "unreadable", "delta" to 30)),
    )

    // rp − previousRp = delta is the contract, so the raw `rp` is the pair
    // the mapping reads restated: parsing it would put a dead field in the
    // model, and carrying it would hand the reveal a second, possibly-
    // disagreeing number. Present, absent, or nonsense — only the pair is
    // the movement.
    assertEquals(RpMovement(600, 30), movementOf(rpPresent))
    assertEquals(RpMovement(600, 30), movementOf(rpJunk))
  }

  @Test
  fun theMovementCarriesNoEchoOfTheSettlementRecord() {
    // Data-class equality says it plainly: an outcome is INDISTINGUISHABLE
    // whether the response also carried the recomputed authority (`ok`,
    // `matchId`, `corrected`, `finalScores`, `winnerIds`, `claimedScores`,
    // `claimedWinners`, `rounds`) or the leanest legal `settled: true`.
    // The standings come from the match document, so a second copy of them
    // in the outcome would only hand the screen two answers to disagree.
    val lean = outcomeOf(mapOf("settled" to true, "you" to youResponse()))
    assertEquals(outcomeOf(settledResponse()), lean)
  }

  // ── the legacy mode-gate no-op ───────────────────────────────────────

  @Test
  fun legacyNotRankedIsTheTerminalNoOpWithTheServersMode() {
    val room = outcomeOf(notRankedResponse(mode = "ROOM"))
    val unranked = outcomeOf(notRankedResponse(mode = "UNRANKED"))

    // The legacy gate never settled these modes, so the no-op is terminal:
    // it cannot be retried and the reveal has nothing to show. The mode's
    // own name is kept for the log — beyond that the outcome carries nothing.
    assertEquals(SettlementPort.SettlementOutcome.NotRanked("ROOM"), room)
    assertEquals(SettlementPort.SettlementOutcome.NotRanked("UNRANKED"), unranked)
  }

  @Test
  fun anUnreadableModeIsStillTheNoOp() {
    val absent = outcomeOf(notRankedResponse(mode = null))
    val notAString = outcomeOf(notRankedResponse(mode = 3))

    // A no-op that cannot name its mode is still a no-op — the reason
    // decides, not the readability of the name beside it.
    assertEquals(SettlementPort.SettlementOutcome.NotRanked("UNKNOWN"), absent)
    assertEquals(SettlementPort.SettlementOutcome.NotRanked("UNKNOWN"), notAString)
  }

  @Test
  fun settledFalseWithAnyOtherReasonIsADenial() {
    val renamed = outcomeOf(
      mapOf("ok" to true, "settled" to false, "reason" to "SOMETHING_NEW", "mode" to "RANKED"),
    )
    val noReason = outcomeOf(mapOf("ok" to true, "settled" to false))

    // `NOT_RANKED` is the one legacy no-op the mode gate named. A settled:false the
    // server did not explain is not read as one — a client that guessed
    // would quietly skip a settlement that failed for a reason its own
    // build could have handled.
    assertEquals(Denied, renamed)
    assertEquals(Denied, noReason)
  }

  // ── the shapes that say neither direction ────────────────────────────

  @Test
  fun aResponseThatSaysNeitherDirectionIsADenialRatherThanAGuess() {
    val okOnly = outcomeOf(mapOf("ok" to true))
    val settledIsAString = outcomeOf(mapOf("settled" to "true"))
    val settledIsANumber = outcomeOf(mapOf("settled" to 1))

    // A truthy-looking string is not a boolean: only the real flag settles.
    // A client that guessed "settled" from a shape it did not understand
    // would reveal a movement the server never awarded.
    assertEquals(listOf(Denied, Denied, Denied), listOf(okOnly, settledIsAString, settledIsANumber))
  }

  @Test
  fun aResponseThatIsNotAMapIsRetriedForEveryCase() {
    val shapes: List<Any?> = listOf(null, "settled", 1, 0.5, emptyList<Any>(), true)

    // Not a denial: this layer cannot read a verdict out of a body that is
    // not the response shape, the callable is idempotent (a replay returns
    // the recorded outcome), so a fresh look is safe and a permanent stop
    // is not.
    assertEquals(List(6) { Retryable }, shapes.map(::outcomeOf))
  }

  @Test
  fun noOutcomeIsBuiltFromClaimsAboutTheMatch() {
    // The one thing the reveal must never do is show a movement the server
    // did not send. Malformed shapes land on Retryable or Denied — never on
    // Settled, and never on NotRanked.
    val malformed: List<Any?> = listOf(
      null,
      mapOf("ok" to true),
      mapOf("settled" to "true"),
      mapOf("settled" to false, "reason" to 7),
      listOf("settled", true),
    )
    for (shape in malformed) {
      val outcome = outcomeOf(shape)
      assertTrue(
        "a malformed shape must not read as a settlement",
        outcome === Retryable || outcome === Denied,
      )
    }
  }

  // ── the codes: which failures come back ──────────────────────────────

  @Test
  fun theThreeRetryableCodesAreTheTransportOnes() {
    for (name in listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")) {
      assertEquals("$name must be retried by the settlement", Retryable, SettlementResponse.forCodeName(name))
    }
  }

  @Test
  fun everyOtherCodeTheServerCanChooseIsTerminal() {
    val retryable = listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")

    // The callable throws for the rest: a missing match (NOT_FOUND), a caller
    // who is not a player (PERMISSION_DENIED), a match that is not complete
    // or whose history failed closed (FAILED_PRECONDITION), a payload with
    // no matchId (INVALID_ARGUMENT) — none is revisited by asking again,
    // because the server's claim is once-only and a retry of a settled
    // match replays, never re-runs.
    val denials = ALL_SDK_CODE_NAMES - retryable
    assertEquals(ALL_SDK_CODE_NAMES.size - 3, denials.size)
    for (name in denials) {
      assertEquals("$name must end the settlement attempt", Denied, SettlementResponse.forCodeName(name))
    }
  }

  @Test
  fun theSixteenCodeNamesMapIndividuallyThroughForCodeName() {
    // Each name driven through production `forCodeName` against the
    // direction the mirrored vocabulary says it takes: reclassifying one —
    // INTERNAL to a denial, say — fails by name, and a list entry that is
    // dropped, duplicated, or mistyped fails the count pin rather than
    // slipping quietly out of the loops above.
    val retried = listOf("DEADLINE_EXCEEDED", "UNAVAILABLE", "INTERNAL")
    val expected = ALL_SDK_CODE_NAMES.associateWith { name ->
      if (name in retried) Retryable else Denied
    }
    val actual = ALL_SDK_CODE_NAMES.associateWith { name -> SettlementResponse.forCodeName(name) }

    assertEquals(16, ALL_SDK_CODE_NAMES.distinct().size)
    assertEquals(expected, actual)
  }

  @Test
  fun noCodeEverAnswersForTheSettlement() {
    for (name in ALL_SDK_CODE_NAMES) {
      val outcome = SettlementResponse.forCodeName(name)

      // A transport failure is not a verdict about the match: it can never
      // read as a settlement with a movement, or as the mode gate's no-op —
      // or a caller would either reveal RP the server never awarded or skip
      // a real denial's terminal stop.
      assertTrue(
        "$name must not settle the match",
        outcome === Retryable || outcome === Denied,
      )
    }
  }

  @Test
  fun aCodeNameThisBuildHasNeverSeenIsTerminal() {
    val name = "A_CODE_ADDED_IN_A_LATER_SDK"

    // The direction the else-branch chooses: an unknown code stops. If the
    // SDK ever grows a transient code, this is where it must be named —
    // and until then a client does not loop on a failure it cannot classify.
    assertEquals(Denied, SettlementResponse.forCodeName(name))
  }
}
