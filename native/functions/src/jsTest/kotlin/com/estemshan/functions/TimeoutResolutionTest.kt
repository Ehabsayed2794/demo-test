package com.estemshan.functions

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.Card
import com.estemshan.engine.Dealer
import com.estemshan.engine.EmitResult
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.Suit
import com.estemshan.engine.emit
import kotlinx.coroutines.test.runTest
import kotlin.js.json
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

  /** One round's worth of timed decisions: 52 cards plus the auction, the
   *  confirmation and the four estimates. Past this the chain is looping. */
  private val chainSteps = 80

  private fun gameState(round: Int): Map<String, Any?> =
    mapOf("initialized" to true, "dealtRound" to round)

  private fun storedCard(seat: String, card: Card, round: Int): Map<String, Any?> = mapOf(
    "seatId" to seat,
    "card" to mapOf("suit" to card.suit.name, "rank" to mapOf("v" to card.rank.v, "s" to card.rank.s)),
    "round" to round,
  )

  private fun confirmEntry(seat: String, tricks: Int, suit: String, round: Int): Map<String, Any?> =
    mapOf(
      "seatId" to seat,
      "actionType" to "SubmitConfirmCall",
      "tricks" to tricks,
      "suit" to suit,
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

  /**
   * A round-1 NORMAL round (FULL), dealt, waiting on the dealer's opening Dash
   * Call — the state every real match's first round is in, and the one
   * [fastRoundDoc] cannot model: round 14 is past FULL's fast boundary, so it
   * opens on ESTIMATES instead. Same document, different sub-phase, which is
   * the whole difference in what a timeout writes.
   */
  private fun normalRoundDoc(
    biddingOpen: Boolean = true,
    updatedAt: Long = anchorMillis,
    timerSeconds: Int = 15,
    biddingLog: List<Map<String, Any?>> = emptyList(),
    version: Int = 1,
  ): Map<String, Any?> = fastRoundDoc(
    biddingOpen = biddingOpen,
    updatedAt = updatedAt,
    timerSeconds = timerSeconds,
    dealtRound = 1,
    currentRound = 1,
    cardLog = emptyList(),
    cardPhase = null,
    turn = null,
    biddingLog = biddingLog,
    version = version,
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

  /**
   * S61 Stage 2 — a LIVE match document. buildMatchFields never writes
   * `completedRound` (only endMatch does), so the membership gate must not
   * require it. Settlement.parseMatch does, which denied every mid-match
   * resolution in the emulator suite with FAILED_PRECONDITION.
   */
  @Test
  fun aLiveMatchDocumentHasPlayersWithoutACompletedRound() {
    val live = fastRoundDoc().toMutableMap().apply { remove("completedRound") }
    assertEquals(players, TimeoutResolution.parsePlayers(live))
  }

  @Test
  fun aDocumentWithoutPlayersIsNotAMatch() {
    assertNull(TimeoutResolution.parsePlayers(fastRoundDoc().toMutableMap().apply { remove("players") }))
  }

  @Test
  fun aDocumentWithoutStatusIsNotAMatch() {
    assertNull(TimeoutResolution.parsePlayers(fastRoundDoc().toMutableMap().apply { remove("status") }))
  }

  @Test
  fun aDocumentWithNoPlayersIsNotAMatch() {
    assertNull(TimeoutResolution.parsePlayers(fastRoundDoc().toMutableMap().apply { this["players"] = emptyList<String>() }))
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

  /**
   * S1 (A8.7 / Q51): a general pass CANCELS the round instead of re-dealing
   * it. The round's own log ends in the pass, so the resolver's replay keeps
   * the engine's cancelled-round state: nobody waits on anything, so no
   * decision exists to resolve until the document advances to the round the
   * host arms next. The old replay re-initialised the same round at the
   * doubled multiplier, which re-litigated a round S1 had already consumed.
   */
  @Test
  fun aPassedRoundReplaysToADecisionlessCancelledRound() {
    val log = buildList {
      order.forEach { seat ->
        add(mapOf(
          "seatId" to seat,
          "actionType" to "SubmitDashCallDecision",
          "declaredDashCall" to false,
          "round" to 1,
        ))
      }
      order.forEach { seat ->
        add(mapOf(
          "seatId" to seat,
          "actionType" to "SubmitAuctionBid",
          "isPass" to true,
          "round" to 1,
        ))
      }
    }
    val doc = normalRoundDoc(biddingLog = log, version = 2)

    // An all-pass round has no seat waiting to decide.
    assertNull(TimeoutResolution.pendingDecision(doc))
    val state = TimeoutResolution.replayBidding(doc, order, 1)!!
    assertEquals(1, state.round)
    assertNull(state.waitingFor)
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
  fun aFirstSeatFastSuperCallIsReplayable() {
    // Caller p1 is first in bidding order, so CONFIRM completes the auction
    // with no wiped seat and the document's bids are the final values. p2's
    // matching 8 SANS is With.
    val doc = fastRoundDoc(
      bidsOverride = mapOf("p1" to 8, "p2" to 8, "p3" to 3, "p4" to 2),
      biddingOpen = false,
      biddingLog = listOf(confirmEntry("p1", 8, "SANS", 14)),
    )
    val state = TimeoutResolution.replayBidding(doc, order, 14)!!
    assertEquals(BiddingPhase.DONE, state.subPhase)
    assertEquals("p1", state.callerId)
    assertEquals(listOf("p2"), state.withPlayers)
    assertEquals(Suit.SANS, state.declaredTrump)
    assertEquals(8, state.bids["p1"]?.amount)
    assertEquals(Suit.SANS, state.bids["p1"]?.suit)
  }

  @Test
  fun aFastSuperCallWithWipedSeatsFailsClosedOnTheLiveDocument() {
    // p2's confirm wipes p1. The document's bids/p1 still holds p1's
    // PRE-confirm value: submitBid is one value per seat and CONFIRM does not
    // reopen bidding (MatchService.submitBid; firestore.rules), so there is
    // no authoritative post-confirm value to replay. Fabricating one would
    // score a stale estimate and could start card play on a wrong auction;
    // fail closed instead. Archives take the Settlement path, where the
    // values are final.
    val doc = fastRoundDoc(
      bidsOverride = mapOf("p1" to 2, "p2" to 8, "p3" to 3, "p4" to 2),
      biddingOpen = false,
      biddingLog = listOf(confirmEntry("p2", 8, "SANS", 14)),
    )
    assertNull(TimeoutResolution.replayBidding(doc, order, 14))
  }

  /**
   * The normal-round counterpart of the estimate test above. A NORMAL round
   * (anything before the game type's fast boundary) opens on the DASH
   * decision, so the resolver's move is a SubmitDashCallDecision appended to
   * `biddingLog` — submitBiddingAction's own channel. `bids/` is never touched
   * by it: that field belongs to the Fast-Round estimates.
   *
   * Every fixture here defaults to round 14, which is a FAST round and opens on
   * ESTIMATES; without this case the DASH path had no coverage at all, and the
   * instrumented suite was the only thing that exercised it — which is how a
   * test asserting `bids` after a dash resolution reached CI.
   */
  @Test
  fun anExpiredDashCallIsWrittenExactlyAsSubmitBiddingActionWouldWriteIt() {
    val doc = normalRoundDoc()
    val hands = dealtHands()
    val pending = TimeoutResolution.pendingDecision(doc)!!
    assertEquals(TimeoutResolution.DecisionPhase.BIDDING, pending.phase)
    // initNormalRound waits on the dealer for the dash call.
    assertEquals("p1", pending.seatId)

    val resolution = TimeoutResolution.resolveExpired(doc, hands, pending)

    assertIs<TimeoutResolution.Resolution.Applied>(resolution)
    val updates = resolution.write.updates
    assertEquals(
      setOf("biddingLog", "version", "updatedAt"),
      updates.keys,
    )
    assertFalse(updates.containsKey("bids"), "a dash call never writes bids/")
    assertEquals(2, updates["version"])

    val entry = arrayUnionEntries(updates["biddingLog"]).single()
    assertEquals("p1", entry["seatId"])
    assertEquals("SubmitDashCallDecision", entry["actionType"])
    assertEquals(1, entry["round"])
    val declared = entry["declaredDashCall"]
    assertTrue(declared is Boolean, "declaredDashCall is the dash entry's one payload field")

    // The decision is legal: feeding it back through the real engine accepts it.
    val state = TimeoutResolution.replayBidding(doc, order, 1)!!
    assertEquals(BiddingPhase.DASH, state.subPhase)
    val reemit = emit(state, BiddingIntent.DashCallDecision("p1", declared as Boolean))
    assertTrue(reemit is EmitResult.Applied || reemit is EmitResult.Completed)
  }

  /**
   * S73 Stage 2 (RD19) — the resolver's own chain end to end, in memory.
   *
   * Every case above hands the resolver a document already sitting on one
   * expired decision. RD19 needs something neither those cases nor the
   * instrumented drive can assume: that a table where NOBODY plays is still
   * owned, decision after decision, until the round is played out — because a
   * seat's clock only charges for windows the resolver actually closes, and one
   * round of them is what has to add up to the 50s threshold.
   *
   * This runs the callable's own two steps in a loop — detect, then resolve —
   * feeding every write back into the document, and stops when detection finds
   * nothing. Silence here is the bug it is looking for: a live, dealt,
   * un-finished round whose detection returns null is a table no timeout can
   * ever move again.
   *
   * What this found, and what it now keeps found: the document it starts from
   * carries `biddingOpen == true` with the replayed auction already DONE, which
   * is not a corruption but what every round with a Dash Caller really carries —
   * both writers derive the flag from "every seat has a bid" and a Dash Caller
   * has no estimate to submit, so its `bids` entry stays null for the rest of
   * the round. Detection reads its own replay, not that flag, so the chain runs
   * to the 52nd card with no help from this test, and the untouched flag in the
   * second assertion is the proof that no help was given.
   */
  @Test
  fun theResolverOwnsEveryDecisionOfANormalRoundToTheLastCard() {
    val hands = dealtHands()
    var doc = normalRoundDoc(timerSeconds = 5)
    val ignored = linkedMapOf<String, Int>()
    val trace = mutableListOf<String>()
    var stop: String? = null

    while (trace.size < chainSteps) {
      val pending = TimeoutResolution.pendingDecision(doc)
      if (pending == null) {
        stop = "detection found no timed decision while ${describeChain(doc)}"
        break
      }
      val resolution = TimeoutResolution.resolveExpired(doc, hands, pending)
      if (resolution !is TimeoutResolution.Resolution.Applied) {
        stop = "seat ${pending.seatId}'s ${pending.phase} decision was not applied: $resolution"
        break
      }
      ignored[pending.seatId] = (ignored[pending.seatId] ?: 0) + pending.durationSeconds
      trace += "${pending.seatId}/${pending.phase.name.first()}${pending.durationSeconds}"
      doc = chainApply(doc, resolution.write.updates)
    }

    val cards = (doc["cardLog"] as? List<Any?>).orEmpty().size
    assertTrue(
      cards == 52,
      "the chain did not play the round out: ${trace.size} steps ($trace), and it stopped because " +
        "$stop",
    )
    assertTrue(
      doc["biddingOpen"] == true,
      "something other than this test cleared the flag mid-chain, so nothing here is playing " +
        "out a round that still carries a stale-open document",
    )

    val richest = ignored.maxByOrNull { it.value }
    assertTrue(
      richest != null && richest.value * 1_000L >= InactivityClock.TAKEOVER_THRESHOLD_MILLIS,
      "one resolver-owned round charges a seat only $ignored seconds and RD19 needs " +
        "${InactivityClock.TAKEOVER_THRESHOLD_MILLIS / 1_000} (decisions: $trace)",
    )
  }

  /**
   * The guard on the fix above. A round with a Dash Caller carries `biddingOpen
   * == true` through the whole auction — the estimate write derives the flag
   * from "every seat has a bid" and a Dash Caller has no bid to submit — so the
   * stale value and the `false` a reader that knew the auction closed would have
   * written are two different documents until the replay reaches DONE. Before
   * that, `false` is not inert: it says "look at the cards", and a round still
   * in its auction has no card to look at.
   *
   * So the chain is first driven to DONE on the stale copy alone, with no help
   * at all from this test — any silence there is the stall itself. Only then is
   * one flag flipped on an identical snapshot, and the two copies run in
   * lockstep: detection is asked on both and the answers must be equal, which
   * is what fails first if anything ever makes the flag phase authority again
   * (the stale copy would go silent at the auction while the other picked up the
   * opening lead). Every decision is then resolved against BOTH, covering the
   * half the fix depends on downstream — reconstruction, legality and write
   * shapes stay blind to the flag to the 52nd card, and the two finished
   * documents differ in exactly that one key.
   */
  @Test
  fun theStaleOpenFlagChangesNothingAfterTheAuctionCloses() {
    val hands = dealtHands()
    var stale = normalRoundDoc(timerSeconds = 5)
    val auction = mutableListOf<String>()

    while (TimeoutResolution.replayBidding(stale, order, 1)?.subPhase != BiddingPhase.DONE) {
      assertTrue(
        auction.size < chainSteps,
        "the auction never closed ($auction) while ${describeChain(stale)}",
      )
      val pending = TimeoutResolution.pendingDecision(stale)
        ?: error("detection went silent inside the auction ($auction), so the two copies below " +
          "never reach a comparable state: ${describeChain(stale)}")
      val resolution = TimeoutResolution.resolveExpired(stale, hands, pending)
      if (resolution !is TimeoutResolution.Resolution.Applied) {
        error("seat ${pending.seatId}'s ${pending.phase} decision was not applied: $resolution")
      }
      auction += "${pending.seatId}/${pending.phase.name.first()}"
      stale = chainApply(stale, resolution.write.updates)
    }
    assertTrue(
      stale["biddingOpen"] == true,
      "the auction closed by clearing the flag, so this is no longer a stale-open document",
    )

    var cleared: Map<String, Any?> = stale.toMutableMap().apply { this["biddingOpen"] = false }
    val trace = mutableListOf<String>()
    var stop: String? = null

    while (trace.size < chainSteps) {
      val onStale = TimeoutResolution.pendingDecision(stale)
      val onCleared = TimeoutResolution.pendingDecision(cleared)
      assertEquals(
        onCleared,
        onStale,
        "detection read the two copies apart at decision ${trace.size + 1} ($auction then $trace) " +
          "— the flag is load-bearing again, while ${describeChain(stale)}",
      )
      val pending = onStale ?: break
      val writeStale = TimeoutResolution.resolveExpired(stale, hands, pending)
      val writeCleared = TimeoutResolution.resolveExpired(cleared, hands, pending)
      if (writeStale !is TimeoutResolution.Resolution.Applied ||
        writeCleared !is TimeoutResolution.Resolution.Applied
      ) {
        stop = "seat ${pending.seatId}'s ${pending.phase} decision was not applied on both copies: " +
          "stale=$writeStale cleared=$writeCleared"
        break
      }
      assertEquals(
        writeCleared.write.toString(),
        writeStale.write.toString(),
        "the flag changed the decision for seat ${pending.seatId}'s ${pending.phase} turn ($trace)",
      )
      trace += "${pending.seatId}/${pending.phase.name.first()}${pending.durationSeconds}"
      stale = chainApply(stale, writeStale.write.updates)
      cleared = chainApply(cleared, writeCleared.write.updates)
    }

    val cardsOnStale = (stale["cardLog"] as? List<Any?>).orEmpty().size
    val cardsOnCleared = (cleared["cardLog"] as? List<Any?>).orEmpty().size
    assertTrue(
      cardsOnStale == 52 && cardsOnCleared == 52,
      "the round did not play out on both copies (stale=$cardsOnStale cleared=$cardsOnCleared, " +
        "${trace.size} steps ($trace), stopped because $stop)",
    )
    val differs = (stale.keys + cleared.keys).distinct()
      .filter { stale[it].toString() != cleared[it].toString() }
    assertEquals(
      listOf("biddingOpen"),
      differs,
      "the flag was supposed to be the only difference between the two tables",
    )
    assertTrue(stale["biddingOpen"] == true, "the copy that was never touched still reads open")
  }

  /** One write, applied the way Firestore would apply it to the document. */
  private fun chainApply(doc: Map<String, Any?>, updates: Map<String, Any?>): Map<String, Any?> {
    val next = doc.toMutableMap()
    for ((key, value) in updates) {
      next[key] = when (key) {
        "biddingLog", "cardLog" -> (doc[key] as? List<Any?>).orEmpty() + arrayUnionEntries(value)
        // serverTimestamp() carries no millis of its own in a double, and the
        // chain's anchor deliberately does not move: every window it opens is
        // therefore already expired when the next detection runs.
        "updatedAt" -> anchorMillis
        else -> value
      }
    }
    return next
  }

  /** The document at the moment detection went silent, in exactly the fields
   *  [TimeoutResolution.pendingDecision] branches on — which of its null
   *  returns fired is the difference between "the round is over" and "nothing
   *  can ever move this table again". */
  private fun describeChain(doc: Map<String, Any?>): String {
    val replayed = TimeoutResolution.replayBidding(doc, order, 1)
    return "biddingLog=${(doc["biddingLog"] as? List<Any?>).orEmpty().size} " +
      "cards=${(doc["cardLog"] as? List<Any?>).orEmpty().size} " +
      "biddingOpen=${doc["biddingOpen"]} bids=${doc["bids"]} " +
      "replayed=${replayed?.subPhase}/${replayed?.waitingFor} " +
      "cardPhase=${doc["cardPhase"]} turn=${doc["turn"]}"
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

  /**
   * Firestore rejects a Kotlin map inside arrayUnion — "Firestore doesn't
   * support JavaScript objects with custom prototypes" — and the arrayUnion
   * sentinel passes toPlain by reference, so the entry has to be plain JS
   * before it is wrapped. This asserts the prototype directly because the
   * failure is in the SDK's serializer, which no unit double reproduces.
   */
  @Test
  fun anAppendedLogEntryIsAPlainObjectNotAKotlinMap() {
    val doc = fastRoundDoc(biddingOpen = false, bidsOverride = order.associateWith { 3 })
    val pending = TimeoutResolution.pendingDecision(doc)!!
    val resolution = TimeoutResolution.resolveExpired(doc, dealtHands(), pending)
    assertIs<TimeoutResolution.Resolution.Applied>(resolution)

    val element = (resolution.write.updates["cardLog"]?.asDynamic()?.elements as Array<*>).single()
    assertNull(element as? Map<*, *>, "a Kotlin map's prototype is what Firestore rejects")
    assertTrue(
      js("Object.getPrototypeOf(element) === Object.prototype") as Boolean,
      "the entry must be a plain object",
    )
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
    /** The stored `timeoutCounts/{uid}` values the transaction reads. A uid
     *  absent from this map is a MISSING counter doc (a first timeout); a
     *  uid present with a garbage value is a PRESENT-but-invalid one, which
     *  must fail closed rather than read as 0. */
    private val storedCounts: Map<String, Any?> = emptyMap(),
  ) : TimeoutDb {
    var handReads = 0
      private set
    var written: Map<String, Any?>? = null
      private set
    /** The counter writes committed, keyed by uid. */
    val writtenCounts: MutableMap<String, Any?> = mutableMapOf()

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
        override suspend fun readTimeoutCount(matchId: String, uid: String): Any? =
          storedCounts[uid]
        override suspend fun timeoutCountExists(matchId: String, uid: String): Boolean =
          uid in storedCounts
        override fun writeTimeoutCount(matchId: String, uid: String, count: Int) {
          writtenCounts[uid] = count
        }
      })
    }
  }

  private fun fakeRequest(uid: String?, matchId: String = "m1"): CallableRequest =
    object : CallableRequest {
      // The Functions runtime delivers data as a PLAIN JS OBJECT, never a Kotlin
      // map — `mapOf` here would let a broken `as? Map` cast pass (the S61
      // instrumented suite caught exactly that). json() is the runtime shape.
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

  /**
   * Reads back the entries an arrayUnion transform appends.
   *
   * `FieldValue.arrayUnion(entry)` is a firebase-admin transform OBJECT, not an
   * array, so the log update cannot be inspected with a plain cast — it has to
   * be expanded through the SDK's own `elements` field. Those elements are the
   * PLAIN JS objects the resolver now hands arrayUnion (a Kotlin map's custom
   * prototype is what Firestore rejects), so they come back through
   * dynamicToKotlin. Test-only, against the pinned SDK.
   */
  private fun arrayUnionEntries(value: Any?): List<Map<String, Any?>> {
    val transform = value?.asDynamic() ?: return emptyList()
    assertEquals("FieldValue.arrayUnion", transform.methodName, "the log update must be an arrayUnion")
    @Suppress("UNCHECKED_CAST")
    return (transform.elements as Array<*>).map { dynamicToKotlin(it) as Map<String, Any?> }
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

  /**
   * S61 Stage 2 regression — the emulator suite caught this. A live match
   * document carries no `completedRound`, and the gate then used
   * Settlement.parseMatch, which requires it: every mid-match resolution died
   * at the pre-check with FAILED_PRECONDITION before the deadline was ever
   * consulted. The gate now takes only what a live doc has, so a doc shaped
   * like the real writer's output reaches the deadline check and reports
   * NOT_YET_EXPIRED rather than a denial.
   */
  @Test
  fun aLiveMatchDocumentWithoutACompletedRoundReachesTheDeadlineCheck() = runTest {
    val liveDoc = fastRoundDoc().toMutableMap().apply { remove("completedRound") }
    val db = FakeTimeoutDb(liveDoc, dealtHands())
    val oneMillisecondEarly = DecisionClock { anchorMillis + 19_999 }

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), oneMillisecondEarly)

    assertEquals(false, response["resolved"])
    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, response["reason"])
    assertEquals(0, db.handReads)
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

  // ── Stage 2: the per-seat timeout counter + the 15-timeout removal ──────

  private val expiredClock = DecisionClock { anchorMillis + 20_000 }

  @Test
  fun aFirstTimeoutCreatesTheCounterAtOne() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(), dealtHands())

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // p1 is the acting seat; u1 owns it. The counter is keyed by the PLAYER.
    assertEquals(1, db.writtenCounts["u1"])
    // The decision write itself is unchanged from Stage 1.
    assertNotNull(db.written)
    assertNull(db.written!!["timeoutRemovedUids"])
  }

  @Test
  fun anExistingTimeoutCountIncrementsByExactlyOne() = runTest {
    val db = FakeTimeoutDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      storedCounts = mapOf("u1" to 7),
    )

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(8, db.writtenCounts["u1"])
    assertNull(db.written!!["timeoutRemovedUids"])
  }

  @Test
  fun theFifteenthTimeoutAtomicallyRecordsTheRemovalWithTheDecision() = runTest {
    val db = FakeTimeoutDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      storedCounts = mapOf("u1" to 14),
    )

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // The count reaches the threshold and the removal lands in the SAME write
    // as the deciding move — never before it.
    assertEquals(15, db.writtenCounts["u1"])
    val removed = db.written!!["timeoutRemovedUids"]
    assertEquals(listOf("u1"), removed)
  }

  @Test
  fun aRemovalNeverWritesVoteKicksState() = runTest {
    val db = FakeTimeoutDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      storedCounts = mapOf("u1" to 14),
    )

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // The timeout mechanism's OWN field only — never botSeats, never
    // removedUids (Vote Kick's state stays untouched).
    assertNull(db.written!!["botSeats"])
    assertNull(db.written!!["removedUids"])
    assertNotNull(db.written!!["timeoutRemovedUids"])
  }

  @Test
  fun aMalformedStoredCountFailsClosedAndNeverRemoves() = runTest {
    val db = FakeTimeoutDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      storedCounts = mapOf("u1" to "not-a-number"),
    )

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // The decision still applies — the match must progress — but the counter
    // is neither trusted nor written, and no removal can fire.
    assertNotNull(db.written)
    assertNull(db.writtenCounts["u1"])
    assertNull(db.written!!["timeoutRemovedUids"])
  }

  @Test
  fun anAboveRangeStoredCountAlsoFailsClosed() = runTest {
    val db = FakeTimeoutDb(
      doc = fastRoundDoc(),
      hands = dealtHands(),
      storedCounts = mapOf("u1" to 99),
    )

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    // A stored value above the threshold cannot leap straight to a removal.
    assertNull(db.writtenCounts["u1"])
    assertNull(db.written!!["timeoutRemovedUids"])
  }

  @Test
  fun anAlreadyRemovedSeatStillDecidesButIncrementsNothing() = runTest {
    // A seat the 15-timeout counter already removed: the table must still see
    // its bot move (or it stalls), but the counter counts HUMAN timeouts and
    // this seat is no longer human-driven.
    val doc = fastRoundDoc().toMutableMap()
    doc["timeoutRemovedUids"] = listOf("u1")
    val db = FakeTimeoutDb(doc, dealtHands())

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertEquals(true, response["resolved"])
    assertNotNull(db.written)
    assertNull(db.writtenCounts["u1"])
  }

  @Test
  fun aVoteKickRemovedSeatStillDecidesButIncrementsNothing() = runTest {
    // The same holds for S60's removal: Vote Kick and timeout removal are
    // separate persisted state, but a bot-held seat is a bot-held seat.
    val doc = fastRoundDoc().toMutableMap()
    doc["botSeats"] = listOf("p1")
    val db = FakeTimeoutDb(doc, dealtHands())

    resolveTimeoutOnce(db, fakeRequest("u3"), expiredClock)

    assertNotNull(db.written)
    assertNull(db.writtenCounts["u1"])
  }

  @Test
  fun aBotControlledSeatGetsTheShortDecisionWindow() = runTest {
    val doc = fastRoundDoc().toMutableMap()
    doc["timeoutRemovedUids"] = listOf("u1")
    val pending = TimeoutResolution.pendingDecision(doc)

    assertNotNull(pending)
    assertEquals(TimeoutResolution.BOT_DECISION_SECONDS, pending.durationSeconds)
  }

  @Test
  fun aHumanSeatKeepsTheFullPhaseWindow() = runTest {
    val pending = TimeoutResolution.pendingDecision(fastRoundDoc(timerSeconds = 10))

    assertNotNull(pending)
    // BIDDING is timer + 5 (RD21's rule), not the bot's short window.
    assertEquals(15, pending.durationSeconds)
  }

  @Test
  fun botControlledSeatsUnionsVoteKickAndTimeoutRemoval() = runTest {
    val doc = fastRoundDoc().toMutableMap()
    doc["botSeats"] = listOf("p2")
    doc["timeoutRemovedUids"] = listOf("u1")

    // p2 via Vote Kick's botSeats, p1 because its uid (u1) was timeout-removed
    // — resolved through the RETAINED seats map.
    assertEquals(setOf("p1", "p2"), TimeoutResolution.botControlledSeats(doc))
  }

  @Test
  fun botControlledSeatsIsEmptyWhenNobodyWasRemoved() = runTest {
    assertEquals(emptySet<String>(), TimeoutResolution.botControlledSeats(fastRoundDoc()))
  }

  @Test
  fun anUnexpiredDeadlineReportsTheServerAuthoritativeRetryDelay() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(timerSeconds = 10), dealtHands())
    // 1 s before a 15 s BIDDING window (10 + 5) — the client called early.
    val clock = DecisionClock { anchorMillis + 14_000 }

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), clock)

    assertEquals(false, response["resolved"])
    assertEquals(TimeoutResolution.Reason.NOT_YET_EXPIRED, response["reason"])
    // The client re-arms on THIS, never its own estimate.
    assertEquals(1_000L, response["retryAfterMillis"])
    assertNull(db.written)
  }

  @Test
  fun aNearlyExpiredDeadlineReportsTheFlooredRetryDelay() = runTest {
    val db = FakeTimeoutDb(fastRoundDoc(), dealtHands())
    // 1 ms before a 20 s window — the raw remainder is below the retry floor,
    // so the client is handed the floor, never a near-zero delay it could
    // spin on.
    val clock = DecisionClock { anchorMillis + 19_999 }

    val response = resolveTimeoutOnce(db, fakeRequest("u3"), clock)

    assertEquals(false, response["resolved"])
    assertEquals(
      TimeoutResolution.MIN_RETRY_AFTER_MILLIS,
      response["retryAfterMillis"],
    )
    assertNull(db.written)
  }

  @Test
  fun aJustExpiredDeadlineReportsTheFlooredRetryDelay() = runTest {
    val pending = TimeoutResolution.Pending(
      TimeoutResolution.DecisionPhase.BIDDING,
      "p1",
      20,
      anchorMillis,
    )
    // Clock already AT the deadline: the raw remainder is 0, the floor keeps
    // the client from spinning on a zero delay.
    assertEquals(
      TimeoutResolution.MIN_RETRY_AFTER_MILLIS,
      TimeoutResolution.millisUntilDeadline(pending, anchorMillis + 20_000),
    )
  }

  @Test
  fun theCounterNeverExceedsTheRemovalThreshold() = runTest {
    // A stored count already AT the threshold (a seat that was removed and
    // somehow has no removal recorded): the clamp keeps the written value at
    // 15, never 16.
    val counter = TimeoutResolution.TimeoutCounter.fromStored(15)
    assertEquals(15, counter!!.nextCount)
    assertFalse(counter.shouldRemove)
  }

  @Test
  fun onlyTheCountFourteenTriggersRemoval() = runTest {
    assertFalse(TimeoutResolution.TimeoutCounter.fromStored(13)!!.shouldRemove)
    assertTrue(TimeoutResolution.TimeoutCounter.fromStored(14)!!.shouldRemove)
    // Even 15 (already removed) must not re-remove.
    assertFalse(TimeoutResolution.TimeoutCounter.fromStored(15)!!.shouldRemove)
  }

  @Test
  fun aNegativeStoredCountIsRejected() {
    assertNull(TimeoutResolution.TimeoutCounter.fromStored(-1))
  }

  @Test
  fun aNonNumericStoredCountIsRejected() {
    assertNull(TimeoutResolution.TimeoutCounter.fromStored(mapOf("count" to "x")))
  }
}
