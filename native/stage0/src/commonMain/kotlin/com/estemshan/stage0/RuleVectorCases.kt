package com.estemshan.stage0

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.DisconnectVote
import com.estemshan.engine.DisconnectVoteChoice
import com.estemshan.engine.DisconnectVotePhase
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.DisconnectVoteStatus
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameSession
import com.estemshan.engine.GameType
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.LeaveMatch
import com.estemshan.engine.LeaveReason
import com.estemshan.engine.MatchMode
import com.estemshan.engine.MatchOutcome
import com.estemshan.engine.RoundExtension
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.Role
import com.estemshan.engine.RpEngineConfig
import com.estemshan.engine.RpMatchInput
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.engine.VoteKickChoice
import com.estemshan.engine.VoteKickStatus
import com.estemshan.engine.calculateClassicScore
import com.estemshan.engine.calculateNormalScore
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.canSubmit
import com.estemshan.engine.castVoteKick
import com.estemshan.engine.computeBaseDelta
import com.estemshan.engine.computeRoundExtension
import com.estemshan.engine.computeWinner
import com.estemshan.engine.dashCallerIds
import com.estemshan.engine.emit
import com.estemshan.engine.fixedTrumpFor
import com.estemshan.engine.forbiddenEstimateFor
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.normalRoleFor
import com.estemshan.engine.openVoteKick
import com.estemshan.engine.placementOutcome
import com.estemshan.engine.removalForVoteKick
import com.estemshan.engine.resolveVoteKick
import com.estemshan.engine.seventhTakeoverCharge
import com.estemshan.engine.voteKickAvailability
import com.estemshan.engine.voteKickThreshold

/**
 * E3 — the vector table itself.
 *
 * Every row of the plan's §5.3.3 vectors that E3 covers is written here as a
 * NAMED CASE with a hand-built state and a hand-derived expectation. The
 * runner renders one line per case; the comparison between two runtimes is
 * then a byte comparison, and the difference between the engine's answer and
 * the plan's answer is a separate, visible axis.
 *
 * The rule the table follows: a case states what the ENGINE did, never what
 * it should have done. Where the engine and the plan disagree, the value
 * carries the engine's answer and the expectation carries the plan's — the
 * verdict then reads DEVIATES, and the disagreement is the finding.
 *
 * Nothing here changes engine behaviour: every case is a read of the engine's
 * own answer to a hand-built state.
 */
private val SEATS: List<String> = listOf("p1", "p2", "p3", "p4")

// ── driving helpers ──────────────────────────────────────────────────────────

/** One engine step: the new state and, when the step finished the round, the outcome. */
private class Step(val state: BiddingState, val outcome: BiddingOutcome?)

private fun step(state: BiddingState, intent: BiddingIntent): Step =
  when (val r = emit(state, intent)) {
    is EmitResult.Applied -> Step(r.state, null)
    is EmitResult.Completed -> Step(r.state, r.outcome)
    is EmitResult.Rejected -> error("the engine rejected ${intent::class.simpleName}: ${r.reason}")
    is EmitResult.GeneralPass -> error("the engine returned a general pass")
  }

/** The dash phase answered by every seat with "no dash call"; the auction opens. */
private fun openAuction(
  round: Int = 6,
  dealer: String = "p1",
  seats: List<String> = SEATS,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): BiddingState {
  var s = initNormalRound(round, dealer, seats, 1, GameType.FULL, scoringMode)
  var guard = 0
  while (s.subPhase == BiddingPhase.DASH && guard++ < 8) {
    val seat = s.waitingFor ?: error("no seat is waiting in the dash phase")
    s = step(s, BiddingIntent.DashCallDecision(seat, false)).state
  }
  check(s.subPhase == BiddingPhase.AUCTION) { "the dash phase did not open the auction" }
  return s
}

/** One auction action: a bid (tricks + suit) or a pass (tricks = null). */
private fun auction(
  state: BiddingState,
  seat: String,
  tricks: Int?,
  suit: Suit? = null,
): BiddingState {
  check(state.waitingFor == seat) { "expected $seat to act, but ${state.waitingFor} does" }
  val intent = if (tricks == null) {
    BiddingIntent.AuctionBid(seat, isPass = true)
  } else {
    BiddingIntent.AuctionBid(seat, isPass = false, tricks = tricks, suit = suit)
  }
  return step(state, intent).state
}

/** The remaining seats pass until the auction closes on the caller. */
private fun closeAuction(state: BiddingState): BiddingState {
  var s = state
  var guard = 0
  while (s.subPhase == BiddingPhase.AUCTION && guard++ < 12) {
    val seat = s.waitingFor ?: error("the auction has no seat waiting")
    s = auction(s, seat, null)
  }
  check(s.subPhase == BiddingPhase.CONFIRM) { "the auction did not close" }
  return s
}

/** The caller's confirmation, then every seat's estimate, then the outcome. */
private fun settle(
  state: BiddingState,
  callerSeat: String,
  tricks: Int,
  suit: Suit,
  estimates: Map<String, Int>,
): BiddingOutcome {
  var s = step(state, BiddingIntent.ConfirmCall(callerSeat, tricks, suit)).state
  var outcome: BiddingOutcome? = null
  var guard = 0
  while (s.subPhase == BiddingPhase.ESTIMATES && guard++ < 12) {
    val seat = s.waitingFor ?: error("the estimate phase has no seat waiting")
    val value = estimates[seat] ?: error("no estimate was supplied for $seat")
    val r = step(s, BiddingIntent.FinalEstimate(seat, value))
    s = r.state
    if (r.outcome != null) outcome = r.outcome
  }
  check(s.subPhase == BiddingPhase.DONE) { "the round did not complete" }
  return outcome ?: error("the round completed without an outcome")
}

/** The whole auction: the listed actions, then the closing passes. */
private fun runAuction(actions: List<Triple<String, Int?, Suit?>>): BiddingState {
  var s = openAuction()
  for ((seat, tricks, suit) in actions) s = auction(s, seat, tricks, suit)
  return closeAuction(s)
}

// ── the fast-round driver ────────────────────────────────────────────────────

/** One action in a fast round: a declaration or the Super Caller's confirmation. */
private sealed interface FastAction {
  val seat: String

  data class Declare(override val seat: String, val tricks: Int) : FastAction
  data class Confirm(override val seat: String, val tricks: Int, val suit: Suit) : FastAction
}

private class FastStep(val asked: String, val action: FastAction, val state: BiddingState)

private class FastRun(
  val steps: List<FastStep>,
  val outcome: BiddingOutcome?,
  val finalState: BiddingState,
) {
  /**
   * The seats that acted after the action at [index], up to the NEXT
   * confirmation. One leg is one re-declaration round: the seats between two
   * confirmations are the ones that re-declared under the new trump.
   */
  fun legAfter(index: Int): List<String> {
    val out = ArrayList<String>()
    for (i in index + 1 until steps.size) {
      if (steps[i].action is FastAction.Confirm) break
      out += steps[i].asked
    }
    return out
  }
}

/**
 * Drives a fast round through the given actions, in order, refusing to move
 * on when the engine asked a different seat. The refusal is the point: the
 * asking order IS a measured fact (the counter-clockwise re-declaration order
 * of T-09 and T-11), so a wrong order must fail loudly rather than be
 * papered over.
 */
private fun driveFast(round: Int, dealer: String, actions: List<FastAction>): FastRun {
  var s = initFastRound(round, dealer, SEATS, 1, GameType.FULL, ScoringMode.NORMAL)
  val steps = ArrayList<FastStep>()
  var outcome: BiddingOutcome? = null
  for (a in actions) {
    val asked = s.waitingFor
    check(asked == a.seat) { "round $round: expected ${a.seat} to act, but $asked does" }
    val intent = when (a) {
      is FastAction.Declare -> BiddingIntent.FinalEstimate(a.seat, a.tricks)
      is FastAction.Confirm -> BiddingIntent.ConfirmCall(a.seat, a.tricks, a.suit)
    }
    val r = step(s, intent)
    s = r.state
    if (r.outcome != null) outcome = r.outcome
    steps += FastStep(asked, a, s)
  }
  return FastRun(steps, outcome, s)
}

/** The state right after the action at [index]. */
private fun FastRun.stateAfter(index: Int): BiddingState = steps[index].state

// ── the scoring helpers ──────────────────────────────────────────────────────

/** The score of one hand. */
private fun score(
  round: Int = 1,
  bids: Map<String, Bid>,
  tricksWon: Map<String, Int>,
  callerId: String? = null,
  withPlayers: List<String> = emptyList(),
  multiplier: Int = 1,
  riskPlayerId: String? = null,
  classic: Boolean = false,
  escalationCap: Int = 8,
) = calculateRoundScore(
  RoundScoreInput(
    round = round,
    order = SEATS,
    bids = bids,
    tricksWon = tricksWon,
    callerId = callerId,
    withPlayers = withPlayers,
    multiplier = multiplier,
    riskPlayerId = riskPlayerId,
    classic = classic,
    escalationCap = escalationCap,
  ),
)

/**
 * A Normal-Dash table: seat p1 declares the Dash (bid 0) and the other three
 * seats estimate [otherBids] and take [otherTricks]. There is no caller — a
 * Dash round has none — so [riskPlayerId] is the only thing that decides which
 * seat carries the Risk obligation.
 *
 * The table is refused unless the tricks add up to 13: a Dash row that lost
 * or invented tricks would change the sole-winner / sole-loser flags and
 * silently answer a different question than the plan's row asks.
 */
private fun dashTable(
  otherBids: List<Int>,
  otherTricks: List<Int>,
  dashTricks: Int,
  risk: Boolean,
  classic: Boolean = false,
) = score(
  bids = mapOf(
    "p1" to Bid(BidType.DASH, 0),
    "p2" to Bid(BidType.TRICKS, otherBids[0]),
    "p3" to Bid(BidType.TRICKS, otherBids[1]),
    "p4" to Bid(BidType.TRICKS, otherBids[2]),
  ),
  tricksWon = mapOf(
    "p1" to dashTricks,
    "p2" to otherTricks[0],
    "p3" to otherTricks[1],
    "p4" to otherTricks[2],
  ),
  callerId = null,
  riskPlayerId = if (risk) "p1" else null,
  classic = classic,
  escalationCap = if (classic) 2 else 8,
).also {
  check(dashTricks + otherTricks.sum() == 13) {
    "the dash table must account for all 13 tricks, not ${dashTricks + otherTricks.sum()}"
  }
}

/** An all-fail round: the Sa'ayda escalation the next round is scored at. */
private fun saaydaNextMultiplier(multiplier: Int, cap: Int = 8): Int {  val r = score(
    round = 1,
    bids = SEATS.associateWith { Bid(BidType.TRICKS, 4) },
    tricksWon = SEATS.associateWith { 0 },
    callerId = "p1",
    multiplier = multiplier,
    escalationCap = cap,
  )
  return r.nextMultiplier
}

/** A round with one success: the ladder resets to x1. */
private fun successNextMultiplier(): Int {
  val r = score(
    round = 1,
    bids = mapOf("p1" to Bid(BidType.TRICKS, 4), "p2" to Bid(BidType.TRICKS, 4)),
    tricksWon = mapOf("p1" to 4, "p2" to 0, "p3" to 0, "p4" to 0),
    callerId = "p1",
    multiplier = 8,
  )
  return r.nextMultiplier
}

/** The multiplier ladder of consecutive all-pass rounds. */
private fun passLadder(scoringMode: ScoringMode, rounds: Int): List<Int> {
  var multiplier = 1
  val out = ArrayList<Int>()
  for (round in 1..rounds) {
    var s = initNormalRound(round, "p1", SEATS, multiplier, GameType.FULL, scoringMode)
    var guard = 0
    while (s.subPhase == BiddingPhase.DASH && guard++ < 8) {
      val seat = s.waitingFor ?: error("no seat is waiting in the dash phase")
      s = step(s, BiddingIntent.DashCallDecision(seat, false)).state
    }
    var next = -1
    var auctionGuard = 0
    while (next < 0 && auctionGuard++ < 12) {
      val seat = s.waitingFor ?: error("the auction has no seat waiting")
      when (val r = emit(s, BiddingIntent.AuctionBid(seat, isPass = true))) {
        is EmitResult.GeneralPass -> { next = r.doubledMultiplier; s = r.state }
        is EmitResult.Applied -> s = r.state
        is EmitResult.Completed -> error("the auction completed instead of passing")
        is EmitResult.Rejected -> error("the engine rejected a pass: ${r.reason}")
      }
    }
    out += next
    multiplier = next
  }
  return out
}

/**
 * The match's planned last round: the base ceiling plus one extension per
 * counted Sa'ayda, walked round by round through the engine's own
 * [computeRoundExtension].
 */
private fun plannedLastRound(gameType: GameType, saaydaRounds: Set<Int>): Int {
  var maxRounds = gameType.baseRounds
  var round = 1
  var guard = 0
  while (guard++ < 120) {
    val isSaayda = round in saaydaRounds
    val extension = computeRoundExtension(
      round = round,
      maxRounds = maxRounds,
      callerId = "p1",
      trump = fixedTrumpFor(round, gameType),
      callerSucceeded = !isSaayda,
      isSaayda = isSaayda,
      gameType = gameType,
      callerBidAmount = 5,
    )
    if (extension.extend) maxRounds += 1
    round += 1
    if (round > maxRounds) break
  }
  return maxRounds
}

/** The extension of one round, with the multiplier the next round is scored at. */
private fun extensionOf(
  round: Int,
  maxRounds: Int,
  isSaayda: Boolean,
  multiplier: Int,
): Pair<RoundExtension, Int> {
  val extension = computeRoundExtension(
    round = round,
    maxRounds = maxRounds,
    callerId = "p1",
    trump = fixedTrumpFor(round, GameType.FULL),
    callerSucceeded = !isSaayda,
    isSaayda = isSaayda,
    gameType = GameType.FULL,
    callerBidAmount = 5,
  )
  val next = if (isSaayda) saaydaNextMultiplier(multiplier) else successNextMultiplier()
  return extension to next
}

// ── the table ────────────────────────────────────────────────────────────────

/** Every vector case E3 covers, in the plan's order. */
fun ruleVectorCases(): List<RuleVectorCase> {
  val cases = ArrayList<RuleVectorCase>()

  fun measured(
    id: String,
    caseId: String,
    value: String,
    expected: String,
    note: String,
  ) = cases.add(RuleVectorCase(id, caseId, RuleVectorStatus.MEASURED, value, expected, note))

  fun missing(id: String, caseId: String, note: String) = cases.add(
    RuleVectorCase(id, caseId, RuleVectorStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "NOT_IMPLEMENTED", note),
  )

  // ══ T-01 — General Pass, Normal Calculation ═════════════════════════════
  val normalPass = passLadder(ScoringMode.NORMAL, 5)
  val session = GameSession(clock = { 0L })
  session.init("ai", force = true)
  session.setRound(session.getRound().copy(number = 6))
  session.completeRound(emptyMap(), normalPass[0])
  val roundAfter = session.nextRound()
  val callerScore = score(
    round = 7,
    bids = mapOf(
      "p1" to Bid(BidType.TRICKS, 5), "p2" to Bid(BidType.TRICKS, 4),
      "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 2),
    ),
    tricksWon = mapOf("p1" to 5, "p2" to 4, "p3" to 3, "p4" to 1),
    callerId = "p1",
    multiplier = 2,
  )
  measured(
    "T-01", "t01.s1.pass-cancels-the-round", "GENERAL_PASS,next=${normalPass[0]}", "GENERAL_PASS,next=2",
    "Round 6 all-pass: the auction returns GeneralPass, so the round is cancelled and consumed, no scores are written for it, and the round that follows is scored at x2 (A8.7 / Q51).",
  )
  measured(
    "T-01", "t01.s2.next-round-number-and-multiplier",
    "round=$roundAfter,mult=${session.getRound().multiplier}", "round=7,mult=2",
    "The consumed round is followed by a NEW round number (the session advances it) at the pass's multiplier.",
  )
  measured(
    "T-01", "t01.s3.dealer-rotates", "dealer=${session.getDealer()}", "dealer=p2",
    "The dealer rotates when the cancelled round is consumed.",
  )
  measured(
    "T-01", "t01.s4.caller-5-takes-5-at-x2",
    "delta=${callerScore.deltas["p1"]},mult=${callerScore.appliedMultiplier}", "delta=50,mult=2",
    "Caller bids 5, takes 5, not sole: (10 + 5 + 10) x 2 = 50 exactly.",
  )
  measured(
    "T-01", "t01.s5.second-pass-x4", "next=${normalPass[1]}", "next=4",
    "A second consecutive all-pass round takes the next round to x4.",
  )
  measured(
    "T-01", "t01.s6.third-pass-x6", "next=${normalPass[2]}", "next=6",
    "A third consecutive all-pass round takes the next round to x6, not x8: the ladder adds 2 per pass (Q51), so the plan's 'minOf(prev x 2, 8)' defect note is stale — the engine already walks 2, 4, 6, 8, 8.",
  )
  measured(
    "T-01", "t01.s7.fourth-pass-x8", "next=${normalPass[3]}", "next=8",
    "A fourth consecutive all-pass round takes the next round to x8.",
  )
  measured(
    "T-01", "t01.s8.fifth-pass-x8-cap", "next=${normalPass[4]}", "next=8",
    "A fifth consecutive all-pass round keeps the next round at x8 — the multiplier caps at 8.",
  )

  // ══ T-02 — General Pass, Classic Calculation ══════════════════════════════
  val classicPass = passLadder(ScoringMode.CLASSIC, 3)
  val classicCaller = score(
    round = 7,
    bids = mapOf(
      "p1" to Bid(BidType.TRICKS, 5), "p2" to Bid(BidType.TRICKS, 4),
      "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 2),
    ),
    tricksWon = mapOf("p1" to 5, "p2" to 4, "p3" to 3, "p4" to 1),
    callerId = "p1",
    multiplier = 1,
    classic = true,
    escalationCap = 2,
  )
  measured(
    "T-02", "t02.s1.classic-pass-cancels-the-round", "GENERAL_PASS,next=${classicPass[0]}", "GENERAL_PASS,next=1",
    "A Classic all-pass round is cancelled and consumed exactly like a Normal one — but the round that follows carries no multiplier (A8.7).",
  )
  measured(
    "T-02", "t02.s2.classic-caller-5-takes-5",
    "delta=${classicCaller.deltas["p1"]},mult=${classicCaller.appliedMultiplier}", "delta=28,mult=1",
    "Classic caller bids 5, takes 5, not sole: 5 + 13 + 10 = 28, with NO multiplier. The plan's '56 (doubles)' row describes the superseded doubling defect — the Classic pass ladder no longer doubles.",
  )
  measured(
    "T-02", "t02.s3.classic-never-accumulates", "next=${classicPass.joinToString(",")}", "next=1,1,1",
    "Two Classic passes in a row: still x1 on the third round — Classic never accumulates a multiplier (A8.7).",
  )

  // ══ T-03 — Sa'ayda count and Game Type ════════════════════════════════════
  measured(
    "T-03", "t03.s1.full-one-saayda", plannedLastRound(GameType.FULL, setOf(14)).toString(), "19",
    "FULL, one counted Sa'ayda in round 14: the planned last round is 19 (Q48).",
  )
  measured(
    "T-03", "t03.s2.full-two-saaydas", plannedLastRound(GameType.FULL, setOf(14, 15)).toString(), "20",
    "FULL, Sa'aydas in rounds 14 and 15: the planned last round is 20.",
  )
  measured(
    "T-03", "t03.s3.full-two-non-adjacent", plannedLastRound(GameType.FULL, setOf(14, 16)).toString(), "20",
    "FULL, Sa'aydas in rounds 14 and 16: 18 + 2 = 20.",
  )
  measured(
    "T-03", "t03.s4.full-three-saaydas", plannedLastRound(GameType.FULL, setOf(14, 15, 16)).toString(), "21",
    "FULL, Sa'aydas in rounds 14, 15 and 16: the planned last round is 21.",
  )
  measured(
    "T-03", "t03.s5.saayda-before-the-window", plannedLastRound(GameType.FULL, setOf(13)).toString(), "18",
    "FULL, a Sa'ayda in round 13 only: the planned last round stays 18 — a pre-14 Sa'ayda never extends (Q48).",
  )
  measured(
    "T-03", "t03.s6.mini-one-saayda", plannedLastRound(GameType.MINI, setOf(6)).toString(), "11",
    "MINI, a Sa'ayda in round 6: the planned last round is 11.",
  )
  measured(
    "T-03", "t03.s7.mini-two-saaydas", plannedLastRound(GameType.MINI, setOf(6, 8)).toString(), "12",
    "MINI, Sa'aydas in rounds 6 and 8: 10 + 2 = 12.",
  )
  measured(
    "T-03", "t03.s8.post-18-non-final-adds-nothing",
    plannedLastRound(GameType.FULL, setOf(14, 15, 16, 20)).toString(), "21",
    "FULL, Sa'aydas in 14, 15 and 16 plus a Sa'ayda in round 20: the round-20 Sa'ayda is NOT the final round, so it adds nothing (A6.2) and the planned last round stays 21.",
  )
  measured(
    "T-03", "t03.s9.cap-removal",
    plannedLastRound(GameType.FULL, setOf(14, 15, 16, 17, 18)).toString(), "23",
    "FULL, five Sa'aydas in rounds 14-18: the planned last round is 23. There is no ALREADY_EXTENDED rejection — the extension count is uncapped (Q2c).",
  )

  // ══ T-04 — Final-round repeat ══════════════════════════════════════════════
  val ext18 = extensionOf(18, 18, isSaayda = true, multiplier = 1)
  val ext19 = extensionOf(19, 19, isSaayda = true, multiplier = 2)
  val ext20 = extensionOf(20, 20, isSaayda = true, multiplier = 4)
  val ext21Ok = extensionOf(21, 21, isSaayda = false, multiplier = 6)
  val ext21Fail = extensionOf(21, 21, isSaayda = true, multiplier = 8)
  measured(
    "T-04", "t04.s1.round-18-saayda-adds-19",
    "extend=${ext18.first.extend},reason=${ext18.first.reason},next=${ext18.second}",
    "extend=true,reason=SAAYDA,next=2",
    "Round 18 all-fail: round 19 is added and the round after it is scored at x2.",
  )
  measured(
    "T-04", "t04.s2.round-19-saayda-adds-20",
    "extend=${ext19.first.extend},reason=${ext19.first.reason},next=${ext19.second}",
    "extend=true,reason=SAAYDA,next=4",
    "Round 19 all-fail: round 20 is added, at x4.",
  )
  measured(
    "T-04", "t04.s3.round-20-saayda-adds-21",
    "extend=${ext20.first.extend},reason=${ext20.first.reason},next=${ext20.second}",
    "extend=true,reason=SAAYDA,next=6",
    "Round 20 all-fail: round 21 is added, at x6.",
  )
  measured(
    "T-04", "t04.s4.round-21-success-ends-the-match",
    "extend=${ext21Ok.first.extend},next=${ext21Ok.second}", "extend=false,next=1",
    "Round 21 with at least one success: the match ends there — no further round, and the escalation ladder resets to x1.",
  )
  measured(
    "T-04", "t04.s5.round-21-saayda-adds-22",
    "extend=${ext21Fail.first.extend},reason=${ext21Fail.first.reason},next=${ext21Fail.second}",
    "extend=true,reason=SAAYDA,next=8",
    "Round 21 all-fail: round 22 is added, at x8 (the cap).",
  )
  val tiedTop = computeWinner(mapOf("p1" to 100, "p2" to 100, "p3" to 50, "p4" to 20))
  measured(
    "T-04", "t04.s6.tied-top-is-a-winner", tiedTop.joinToString(","), "p1,p2",
    "A final round whose top scores are tied counts as having a winner: both seats are Kings.",
  )

  // ══ T-05 — Super Call scoring and extension ════════════════════════════════
  val superBids = mapOf(
    "p1" to Bid(BidType.TRICKS, 8), "p2" to Bid(BidType.TRICKS, 4),
    "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 3),
  )
  val superWin = score(
    bids = superBids,
    tricksWon = mapOf("p1" to 8, "p2" to 4, "p3" to 2, "p4" to 1),
    callerId = "p1",
  )
  val superWinSole = score(
    bids = superBids,
    tricksWon = mapOf("p1" to 8, "p2" to 0, "p3" to 0, "p4" to 0),
    callerId = "p1",
  )
  val superLoss = score(
    bids = superBids,
    tricksWon = mapOf("p1" to 7, "p2" to 4, "p3" to 2, "p4" to 1),
    callerId = "p1",
  )
  val superLossSole = score(
    bids = superBids,
    tricksWon = mapOf("p1" to 7, "p2" to 4, "p3" to 4, "p4" to 3),
    callerId = "p1",
  )
  val classicSuperWin = score(
    bids = superBids,
    tricksWon = mapOf("p1" to 8, "p2" to 4, "p3" to 2, "p4" to 1),
    callerId = "p1",
    classic = true,
    escalationCap = 2,
  )
  val classicSuperLoss = score(
    bids = superBids,
    tricksWon = mapOf("p1" to 7, "p2" to 4, "p3" to 2, "p4" to 1),
    callerId = "p1",
    classic = true,
    escalationCap = 2,
  )
  val withOnSuper = score(
    bids = mapOf(
      "p1" to Bid(BidType.TRICKS, 8), "p2" to Bid(BidType.TRICKS, 8),
      "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 4),
    ),
    tricksWon = mapOf("p1" to 8, "p2" to 8, "p3" to 2, "p4" to 2),
    callerId = "p1",
    withPlayers = listOf("p2"),
  )
  val superExtOk = computeRoundExtension(
    round = 15, maxRounds = 18, callerId = "p1", trump = Suit.HEARTS,
    callerSucceeded = true, isSaayda = false, gameType = GameType.FULL, callerBidAmount = 8,
  )
  val superExtFail = computeRoundExtension(
    round = 15, maxRounds = 18, callerId = "p1", trump = Suit.HEARTS,
    callerSucceeded = false, isSaayda = false, gameType = GameType.FULL, callerBidAmount = 8,
  )
  measured(
    "T-05", "t05.s1.super-win", superWin.deltas["p1"].toString(), "64",
    "Normal, bid 8, takes 8, another player succeeds: 64 (bid squared — the Super Call row carries no separate Caller +10).",
  )
  measured(
    "T-05", "t05.s2.super-win-sole", superWinSole.deltas["p1"].toString(), "74",
    "Normal, bid 8, takes 8, everyone else fails: 64 + 10 (sole winner) = 74.",
  )
  measured(
    "T-05", "t05.s3.super-loss", superLoss.deltas["p1"].toString(), "-32",
    "Normal, bid 8, takes 7, another player succeeds: -32 (half of 64, rounded half up).",
  )
  measured(
    "T-05", "t05.s4.super-loss-sole", superLossSole.deltas["p1"].toString(), "-42",
    "Normal, bid 8, takes 7, everyone else succeeds: -32 - 10 (sole loser) = -42.",
  )
  measured(
    "T-05", "t05.s5.extension-on-success",
    "extend=${superExtOk.extend},reason=${superExtOk.reason}", "extend=true,reason=SUPER_CALL",
    "Fast round, bid 8, takes 8: one round is added.",
  )
  measured(
    "T-05", "t05.s6.no-extension-on-failure",
    "extend=${superExtFail.extend}", "extend=false",
    "Fast round, bid 8, takes 6: no extension.",
  )
  measured(
    "T-05", "t05.s7.classic-super-win", classicSuperWin.deltas["p1"].toString(), "42",
    "Classic, bid 8, makes: +42, with no separate Caller +10.",
  )
  measured(
    "T-05", "t05.s8.classic-super-loss", classicSuperLoss.deltas["p1"].toString(), "-20",
    "Classic, bid 8, misses: -20.",
  )
  measured(
    "T-05", "t05.s9.with-on-a-super-call",
    "role=${withOnSuper.breakdown["p2"]?.role},delta=${withOnSuper.deltas["p2"]}", "role=WIZZ,delta=28",
    "Another player shares the Super Call's number: the With seat keeps the ordinary Wizz formula (10 + 8 + 10 = 28), not the Super Call formula.",
  )

  // ══ T-06 — Normal Dash + Risk ══════════════════════════════════════════════
  // Every table below accounts for all 13 tricks and is built so that the
  // sole-winner / sole-loser flags are exactly the ones the plan's row names.
  val dash15Win = dashTable(listOf(5, 5, 5), listOf(5, 4, 4), dashTricks = 0, risk = true)
  val dash15Lose = dashTable(listOf(5, 5, 5), listOf(5, 4, 2), dashTricks = 2, risk = true)
  val dash15SoleWin = dashTable(listOf(5, 5, 5), listOf(7, 4, 2), dashTricks = 0, risk = true)
  val dash11SoleLose = dashTable(listOf(5, 5, 1), listOf(5, 5, 1), dashTricks = 2, risk = true)
  val dash17Win = dashTable(listOf(6, 6, 5), listOf(6, 5, 2), dashTricks = 0, risk = true)
  val dash17Lose = dashTable(listOf(6, 6, 5), listOf(6, 5, 0), dashTricks = 2, risk = true)
  val dash19Win = dashTable(listOf(7, 6, 6), listOf(7, 6, 0), dashTricks = 0, risk = true)
  val dash19Lose = dashTable(listOf(7, 6, 6), listOf(7, 4, 0), dashTricks = 2, risk = true)
  measured(
    "T-06", "t06.s1.dash-success", "total=${dash15Win.totalBids},delta=${dash15Win.deltas["p1"]}",
    "total=15,delta=20",
    "Total bids 15, risk value 10, the seat is a Normal Dash and takes 0: +10 (dash) + 10 (risk) = +20. One of the other three seats also made its bid, so the dash seat is not the sole winner.",
  )
  measured(
    "T-06", "t06.s2.dash-failure", "total=${dash15Lose.totalBids},delta=${dash15Lose.deltas["p1"]}",
    "total=15,delta=-22",
    "The same seat takes 2: -(10 + 2) - 10 = -22. Two of the other three seats also missed, so the dash seat is not the sole loser.",
  )
  measured(
    "T-06", "t06.s3.dash-sole-winner", "total=${dash15SoleWin.totalBids},delta=${dash15SoleWin.deltas["p1"]}",
    "total=15,delta=30",
    "The dash seat takes 0 and everyone else fails: +10 + 10 + 10 (sole) = +30.",
  )
  measured(
    "T-06", "t06.s4.dash-sole-loser", "total=${dash11SoleLose.totalBids},delta=${dash11SoleLose.deltas["p1"]}",
    "total=11,delta=-32",
    "The dash seat takes 2 and everyone else succeeds: -12 - 10 - 10 = -32. The plan's row continues the 15-bid table, and there a sole loser is UNREACHABLE: the other three seats bid 15 between them but only 11 tricks are left, so at least one of them must miss too. The reachable table is the one measured here — the other three bid 11 between them, which still puts the risk value at 10 — and the engine then scores the plan's -32.",
  )
  measured(
    "T-06", "t06.s5.risk-20-success", "total=${dash17Win.totalBids},delta=${dash17Win.deltas["p1"]}",
    "total=17,delta=30",
    "Risk value 20 on the same seat: +10 + 20 = +30.",
  )
  measured(
    "T-06", "t06.s6.risk-20-failure", "total=${dash17Lose.totalBids},delta=${dash17Lose.deltas["p1"]}",
    "total=17,delta=-32",
    "Risk value 20 on the same seat, failure: -(10 + 2) - 20 = -32.",
  )
  measured(
    "T-06", "t06.s7.risk-30-success", "total=${dash19Win.totalBids},delta=${dash19Win.deltas["p1"]}",
    "total=19,delta=40",
    "Risk value 30 on the same seat: +10 + 30 = +40.",
  )
  measured(
    "T-06", "t06.s8.risk-30-failure", "total=${dash19Lose.totalBids},delta=${dash19Lose.deltas["p1"]}",
    "total=19,delta=-42",
    "Risk value 30 on the same seat, failure: -(10 + 2) - 30 = -42.",
  )

  // ══ T-07 — Classic Dash + Risk ══════════════════════════════════════════════
  val cDashUnder = dashTable(listOf(4, 4, 4), listOf(5, 4, 4), dashTricks = 0, risk = false, classic = true)
  val cDashOver = dashTable(listOf(5, 5, 5), listOf(5, 4, 4), dashTricks = 0, risk = false, classic = true)
  val cDashOverFail = dashTable(listOf(5, 5, 5), listOf(5, 4, 2), dashTricks = 2, risk = false, classic = true)
  val cDashUnderFail = dashTable(listOf(4, 4, 4), listOf(4, 4, 3), dashTricks = 2, risk = false, classic = true)
  val cCombinedFail = dashTable(listOf(5, 5, 5), listOf(5, 4, 2), dashTricks = 2, risk = true, classic = true)
  val cCombinedOver = dashTable(listOf(5, 5, 5), listOf(5, 4, 4), dashTricks = 0, risk = true, classic = true)
  val cCombinedUnder = dashTable(listOf(5, 5, 1), listOf(5, 4, 4), dashTricks = 0, risk = true, classic = true)
  val cCombinedSoleOver = dashTable(listOf(5, 5, 5), listOf(7, 4, 2), dashTricks = 0, risk = true, classic = true)
  val cCombinedSoleUnder = dashTable(listOf(5, 5, 1), listOf(4, 4, 5), dashTricks = 0, risk = true, classic = true)
  val cCombinedSoleFail = dashTable(listOf(5, 5, 1), listOf(5, 5, 1), dashTricks = 2, risk = true, classic = true)
  val cRisk20Fail = dashTable(listOf(6, 6, 5), listOf(6, 5, 0), dashTricks = 2, risk = true, classic = true)
  val cRisk30Fail = dashTable(listOf(7, 6, 6), listOf(7, 4, 0), dashTricks = 2, risk = true, classic = true)
  val cRisk20Over = dashTable(listOf(6, 6, 5), listOf(6, 5, 2), dashTricks = 0, risk = true, classic = true)
  val cRisk30Over = dashTable(listOf(7, 6, 6), listOf(7, 6, 0), dashTricks = 0, risk = true, classic = true)
  measured(
    "T-07", "t07.s1.dash-success-under", "total=${cDashUnder.totalBids},delta=${cDashUnder.deltas["p1"]}",
    "total=12,delta=23",
    "Classic Normal Dash, success under (total <= 13): +23. One of the other three seats also made its bid, so the dash seat is not the sole winner.",
  )
  measured(
    "T-07", "t07.s2.dash-success-over", "total=${cDashOver.totalBids},delta=${cDashOver.deltas["p1"]}",
    "total=15,delta=13",
    "Classic Normal Dash, success over (total > 13): +13.",
  )
  measured(
    "T-07", "t07.s3.dash-failure-over", "total=${cDashOverFail.totalBids},delta=${cDashOverFail.deltas["p1"]}",
    "total=15,delta=-2",
    "Classic Normal Dash, failure over: -2 (the failure value is the tricks taken).",
  )
  measured(
    "T-07", "t07.s4.dash-failure-under", "total=${cDashUnderFail.totalBids},delta=${cDashUnderFail.deltas["p1"]}",
    "total=12,delta=-10",
    "Classic Normal Dash, failure under: -10.",
  )
  val classicRiskComponent = classicRiskComponentValue()
  measured(
    "T-07", "t07.s5.risk-component-success", "component=${classicRiskComponent.successComponent}", "component=+10",
    "A Classic Normal Dash seat that is also the Risk seat scores its base plus a flat +10 risk component: the same table scored without the Risk obligation gives ${classicRiskComponent.ordinarySuccess} and with it ${classicRiskComponent.riskSuccess}.",
  )
  measured(
    "T-07", "t07.s6.risk-component-failure", "component=${classicRiskComponent.failureComponent}", "component=-10",
    "The same table failing: without the Risk obligation ${classicRiskComponent.ordinaryFailure} and with it ${classicRiskComponent.riskFailure}.",
  )
  measured(
    "T-07", "t07.s7.combined-failure-over", "total=${cCombinedFail.totalBids},delta=${cCombinedFail.deltas["p1"]}",
    "total=15,delta=-12",
    "Total bids 15 (over), risk value 10, a Normal Dash seat whose estimate is 0 takes 2: -2 (dash) - 10 (risk) = -12, not -14.",
  )
  measured(
    "T-07", "t07.s8.combined-success-over", "total=${cCombinedOver.totalBids},delta=${cCombinedOver.deltas["p1"]}",
    "total=15,delta=23",
    "The same table, the dash seat takes 0: +13 + 10 = +23.",
  )
  measured(
    "T-07", "t07.s9.combined-success-under", "total=${cCombinedUnder.totalBids},delta=${cCombinedUnder.deltas["p1"]}",
    "total=11,delta=33",
    "Total bids <= 13, the dash seat takes 0: +23 + 10 = +33. The plan's row only fixes the total as \"<= 13\" while assuming a risk value of 10, and the risk ladder is 0 at a total of 12 or 13, so the reachable table that carries the risk value the row asks for is total 11 (the other three seats bid 5, 5, 1 between them; one of them also made its bid, so the dash seat is not the sole winner).",
  )
  measured(
    "T-07", "t07.s10.combined-sole-winner-over",
    "total=${cCombinedSoleOver.totalBids},delta=${cCombinedSoleOver.deltas["p1"]}", "total=15,delta=33",
    "Combined role, takes 0, everyone else fails: +13 + 10 + 10 (sole) = +33.",
  )
  measured(
    "T-07", "t07.s11.combined-sole-winner-under",
    "total=${cCombinedSoleUnder.totalBids},delta=${cCombinedSoleUnder.deltas["p1"]}", "total=11,delta=43",
    "Combined role, takes 0, everyone else fails, total <= 13: +23 + 10 + 10 = +43. As in the row above, the reachable table that carries the plan's risk value of 10 while staying under 13 is total 11 (the other three seats bid 5, 5, 1 and take 4, 4, 5, so every one of them fails and the dash seat is the sole winner).",
  )
  measured(
    "T-07", "t07.s12.combined-sole-loser", "total=${cCombinedSoleFail.totalBids},delta=${cCombinedSoleFail.deltas["p1"]}",
    "total=11,delta=-22",
    "The combined role fails and everyone else succeeds: 2 x (-12) = -24, floored at -22. As in T-06 the plan's 15-bid table cannot make the dash seat the sole loser (the other three seats bid 15 between them and only 11 tricks are left), so the reachable table is the one measured here — the other three bid 11 between them, which still puts the risk value at 10 — and the engine still floors the score at -22.",
  )
  measured(
    "T-07", "t07.s13.risk-ladder-20-failure", "total=${cRisk20Fail.totalBids},delta=${cRisk20Fail.deltas["p1"]}",
    "total=17,delta=-22",
    "Risk value 20 instead of 10, failure over: -2 - 20 = -22.",
  )
  measured(
    "T-07", "t07.s14.risk-ladder-30-failure", "total=${cRisk30Fail.totalBids},delta=${cRisk30Fail.deltas["p1"]}",
    "total=19,delta=-32",
    "Risk value 30 instead of 10, failure over: -2 - 30 = -32.",
  )
  measured(
    "T-07", "t07.s15.risk-ladder-20-success", "total=${cRisk20Over.totalBids},delta=${cRisk20Over.deltas["p1"]}",
    "total=17,delta=33",
    "Risk value 20, success over: +13 + 20 = +33.",
  )
  measured(
    "T-07", "t07.s16.risk-ladder-30-success", "total=${cRisk30Over.totalBids},delta=${cRisk30Over.deltas["p1"]}",
    "total=19,delta=43",
    "Risk value 30, success over: +13 + 30 = +43.",
  )

  // ══ T-08 — Normal With (owner examples, verbatim) ══════════════════════════
  val with1 = settle(
    runAuction(
      listOf(
        Triple("p1", 5, Suit.HEARTS), Triple("p2", 5, Suit.HEARTS),
        Triple("p3", null, null), Triple("p4", null, null), Triple("p1", null, null),
      ),
    ), "p1", 5, Suit.HEARTS, mapOf("p2" to 5, "p3" to 4, "p4" to 4),
  )
  val with2 = settle(
    runAuction(
      listOf(
        Triple("p1", 5, Suit.HEARTS), Triple("p2", 6, Suit.HEARTS),
        Triple("p3", null, null), Triple("p4", null, null), Triple("p1", null, null),
      ),
    ), "p2", 6, Suit.HEARTS, mapOf("p1" to 5, "p3" to 4, "p4" to 4),
  )
  val with3 = settle(
    runAuction(
      listOf(
        Triple("p1", 6, Suit.SPADES), Triple("p2", 6, Suit.HEARTS),
        Triple("p3", null, null), Triple("p4", null, null),
      ),
    ), "p1", 6, Suit.SPADES, mapOf("p2" to 5, "p3" to 4, "p4" to 4),
  )
  val with4 = settle(
    runAuction(
      listOf(
        Triple("p1", 6, Suit.HEARTS), Triple("p2", 7, Suit.HEARTS),
        Triple("p3", 4, Suit.SPADES), Triple("p4", null, null), Triple("p1", 5, Suit.SPADES),
      ),
    ), "p2", 7, Suit.HEARTS, mapOf("p1" to 6, "p3" to 4, "p4" to 4),
  )
  measured(
    "T-08", "t08.r1.ahmed-5h-osama-5h",
    "caller=${with1.callerId},with=${with1.withPlayers.joinToString(",")}", "caller=p1,with=p2",
    "Ahmed 5H, Osama 5H: Ahmed is the Caller and Osama is With.",
  )
  measured(
    "T-08", "t08.r2.osama-raises-to-6h",
    "caller=${with2.callerId},with=${with2.withPlayers.joinToString(",")}", "caller=p2,with=p1",
    "Osama then raises to 6H: Osama is the Caller and Ahmed is With (Ahmed's last H bid was 5).",
  )
  measured(
    "T-08", "t08.r3.a-matching-number-is-not-enough",
    "caller=${with3.callerId},with=${with3.withPlayers.joinToString(",")}", "caller=p1,with=",
    "Ahmed wins with 6S; the other seat never bid S, so a matching number in another suit is not With. (The engine's ladder puts S above H, so a 6H cannot outbid a 6S: the plan's own wording is reached with the seats the ladder allows.)",
  )
  measured(
    "T-08", "t08.r4.with-at-the-last-heart-bid",
    "caller=${with4.callerId},with=${with4.withPlayers.joinToString(",")},at=6", "caller=p2,with=p1,at=6",
    "Osama wins with 7H and Ahmed's last H bid was 6: Ahmed is With at 6, even though Ahmed later bid another suit.",
  )
  val with5 = score(
    bids = mapOf(
      "p1" to Bid(BidType.TRICKS, 7), "p2" to Bid(BidType.TRICKS, 5),
      "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 4),
    ),
    tricksWon = mapOf("p1" to 7, "p2" to 5, "p3" to 3, "p4" to 4),
    callerId = "p1",
    withPlayers = listOf("p2"),
  )
  val with6 = score(
    bids = mapOf(
      "p1" to Bid(BidType.TRICKS, 7), "p2" to Bid(BidType.TRICKS, 5),
      "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 4),
    ),
    tricksWon = mapOf("p1" to 7, "p2" to 7, "p3" to 3, "p4" to 4),
    callerId = "p1",
    withPlayers = listOf("p2"),
  )
  measured(
    "T-08", "t08.r5.with-5-takes-5", "delta=${with5.deltas["p2"]}", "delta=25",
    "With at 5, takes 5, not sole: 10 + 5 + 10 = 25.",
  )
  measured(
    "T-08", "t08.r6.with-5-misses-by-two", "delta=${with6.deltas["p2"]}", "delta=-12",
    "With at 5, takes 7 (misses by 2): -2 - 10 = -12.",
  )

  // ══ T-09 — Fast Super Call and re-declaration ═════════════════════════════
  val t09 = driveFast(
    14, "p1",
    listOf(
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 4),
      FastAction.Declare("p3", 5),
      FastAction.Declare("p4", 6),
      FastAction.Confirm("p1", 8, Suit.HEARTS),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 7),
      FastAction.Declare("p4", 6),
      FastAction.Confirm("p2", 9, Suit.SPADES),
      FastAction.Declare("p3", 10),
      FastAction.Declare("p4", 7),
      FastAction.Declare("p1", 6),
      FastAction.Confirm("p3", 10, Suit.CLUBS),
      FastAction.Declare("p4", 5),
      FastAction.Declare("p1", 4),
      FastAction.Declare("p2", 3),
    ),
  )
  val t09Leg1 = t09.legAfter(4)
  val t09Leg2 = t09.legAfter(8)
  val t09Leg3 = t09.legAfter(12)
  val t09After1 = t09.stateAfter(4)
  val t09After2 = t09.stateAfter(8)
  val t09After3 = t09.stateAfter(12)
  val t09AnyTrump = driveFast(
    14, "p1",
    listOf(
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 4),
      FastAction.Declare("p3", 5),
      FastAction.Declare("p4", 6),
      FastAction.Confirm("p1", 8, Suit.DIAMONDS),
    ),
  )
  measured(
    "T-09", "t09.s1.step-1-call-8",
    "caller=${t09After1.callerId},top=${t09After1.auctionTop},trump=${t09After1.declaredTrump},next=${t09After1.waitingFor}",
    "caller=p1,top=8,trump=HEARTS,next=p2",
    "A declares 8 and takes Hearts (changed from the forced Sans of round 14): B, C and D re-declare, A does not.",
  )
  measured(
    "T-09", "t09.s2.step-2-call-9",
    "caller=${t09After2.callerId},top=${t09After2.auctionTop},trump=${t09After2.declaredTrump},next=${t09After2.waitingFor}",
    "caller=p2,top=9,trump=SPADES,next=p3",
    "B declares 9 and takes Spades: C, D and A re-declare, B does not — A re-declares despite having declared after the first Super Call.",
  )
  measured(
    "T-09", "t09.s3.step-3-call-10",
    "caller=${t09After3.callerId},top=${t09After3.auctionTop},trump=${t09After3.declaredTrump},next=${t09After3.waitingFor}",
    "caller=p3,top=10,trump=CLUBS,next=p4",
    "C declares 10 and takes Clubs: D, A and B re-declare.",
  )
  measured(
    "T-09", "t09.s4.no-higher-call-ends-the-auction",
    "caller=${t09.outcome?.callerId},trump=${t09.outcome?.trump}", "caller=p3,trump=CLUBS",
    "No higher call: the auction ends and C is the Caller.",
  )
  measured(
    "T-09", "t09.s5.any-trump-may-be-chosen",
    "accepted,trump=${t09AnyTrump.stateAfter(4).declaredTrump}", "accepted,trump=DIAMONDS",
    "Any 8+ declarer may choose any trump (A6.10): the confirmation with the weakest suit is accepted in a fast round.",
  )
  measured(
    "T-09", "t09.s6.each-higher-call-wipes-the-other-seats",
    "wiped=${t09After1.bids.keys.joinToString(",")},${t09After2.bids.keys.joinToString(",")},${t09After3.bids.keys.joinToString(",")}",
    "wiped=p1,p2,p3",
    "Each higher call repeats the wipe: after every confirmation only the Super Caller's own record survives.",
  )
  measured(
    "T-09", "t09.s7.the-declaration-order-is-counter-clockwise",
    "order=${t09Leg1.joinToString(",")}/${t09Leg2.joinToString(",")}/${t09Leg3.joinToString(",")}",
    "order=p2,p3,p4/p3,p4,p1/p4,p1,p2",
    "Every other seat re-declares counter-clockwise from the Super Caller, and the Super Caller declares again last.",
  )
  measured(
    "T-09", "t09.s8.a-seat-that-re-declared-declares-again",
    "carried=${t09Leg2.intersect(t09Leg3.toSet()).sorted().joinToString(",")}",
    "carried=p1,p4",
    "The seats that had already re-declared after the second Super Call (C, D and A) re-declare again after the third one.",
  )

  // ══ T-10 — Fast With / Wazz ════════════════════════════════════════════════
  val t10r1 = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 9),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
      FastAction.Confirm("p1", 9, Suit.SPADES),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
    ),
  )
  val t10r2 = driveFast(
    14, "p1",
    listOf(
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 7),
      FastAction.Declare("p4", 7),
      FastAction.Confirm("p1", 8, Suit.SANS),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 7),
      FastAction.Declare("p4", 7),
    ),
  )
  val t10r3 = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 9),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
      FastAction.Confirm("p1", 9, Suit.HEARTS),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
    ),
  )
  val t10r4 = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 9),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
      FastAction.Confirm("p1", 9, Suit.HEARTS),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
    ),
  )
  val t10r6 = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 10),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
      FastAction.Confirm("p1", 10, Suit.SPADES),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 8),
      FastAction.Declare("p4", 8),
    ),
  )
  measured(
    "T-10", "t10.r1.highest-9-is-with",
    "caller=${t10r1.outcome?.callerId},with=${t10r1.outcome?.withPlayers?.joinToString(",")}",
    "caller=p1,with=p2",
    "No trump change: the other seat that declared the final 9 is With; the 8 is not.",
  )
  measured(
    "T-10", "t10.r2.super-call-trump-unchanged",
    "caller=${t10r2.outcome?.callerId},with=${t10r2.outcome?.withPlayers?.joinToString(",")},trump=${t10r2.outcome?.trump}",
    "caller=p1,with=p2,trump=SANS",
    "The Caller keeps Sans at 8 and another seat declares 8: that other 8 is With.",
  )
  measured(
    "T-10", "t10.r3.super-call-trump-changed",
    "caller=${t10r3.outcome?.callerId},with=${t10r3.outcome?.withPlayers?.joinToString(",")},trump=${t10r3.outcome?.trump}",
    "caller=p1,with=p2,trump=HEARTS",
    "Final trump Hearts, highest 9, and the other seat declares 9 after the change: it is With.",
  )
  measured(
    "T-10", "t10.r4.trump-changed-earlier-estimate",
    "caller=${t10r4.outcome?.callerId},with=${t10r4.outcome?.withPlayers?.joinToString(",")},trump=${t10r4.outcome?.trump}",
    "caller=p1,with=,trump=HEARTS",
    "The other seat declared 9 under Spades before the change to Hearts and did not re-declare 9: it is not With, because the estimate was not made under the final trump.",
  )
  missing(
    "T-10", "t10.r5.number-only-is-not-with",
    "The plan's row asks for a seat that declares '9 in another trump' while the final trump is a third one. A fast declaration carries the round's CURRENT trump, never a suit of its own, so the state machine cannot produce a declaration under a per-seat suit. The reachable form of the same fact — a seat that declared the number before the trump changed and did not re-declare it — is measured in t10.r4, and the same number re-declared under the final trump IS With (t10.r3).",
  )
  measured(
    "T-10", "t10.r6.eight-while-the-highest-is-ten",
    "caller=${t10r6.outcome?.callerId},with=${t10r6.outcome?.withPlayers?.joinToString(",")}",
    "caller=p1,with=",
    "Another seat declares 8 while the highest is 10: it is not With.",
  )

  // ══ T-11 — Equal-highest fast Super Call ═══════════════════════════════════
  missing(
    "T-11", "t11.r1.two-8s-different-trumps",
    "A fast declaration carries the round's trump, not the declarer's own suit: the estimate intent carries a number only and the engine stamps every fast bid with the round's declared trump. The suit half of this row (a different-suit tie broken by the canonical ladder) therefore has no engine behaviour to compare — it is the S4 gap the plan names.",
  )
  val t11r2 = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Confirm("p2", 9, Suit.HEARTS),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Declare("p1", 8),
    ),
  )
  measured(
    "T-11", "t11.r2.suit-does-not-outrank-number",
    "caller=${t11r2.outcome?.callerId},top=${t11r2.outcome?.estimates?.get("p2")},trump=${t11r2.outcome?.trump}",
    "caller=p2,top=9,trump=HEARTS",
    "A declares 8, B declares 9: the higher number wins whatever the suit.",
  )
  missing(
    "T-11", "t11.r3.sans-beats-a-suit-on-a-tie",
    "Same missing behaviour as t11.r1: a fast declaration cannot carry its own suit, so the canonical ladder cannot decide a tie between a Sans declaration and a suited one. The engine's own resolveFastCall implements the ladder, but the state machine never produces a different-suit fast declaration.",
  )
  missing(
    "T-11", "t11.r4.loser-of-a-suit-tie-is-not-with",
    "Same missing behaviour as t11.r1: the 'loser of a suit tie' is not expressible, because no fast declaration carries a suit of its own.",
  )
  val t11r5a = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Confirm("p1", 8, Suit.SPADES),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
    ),
  )
  val t11r5b = driveFast(
    15, "p2",
    listOf(
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Declare("p1", 8),
      FastAction.Confirm("p2", 8, Suit.SPADES),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Declare("p1", 8),
    ),
  )
  measured(
    "T-11", "t11.r5.seat-order-must-not-decide",
    if (t11r5a.outcome?.callerId == t11r5b.outcome?.callerId) "stable" else "unstable", "stable",
    "The same two 8 declarations, re-seated in the opposite order (the second table deals from the other seat): the Caller and the trump must be identical. The engine's fast call resolution takes the first declaration among the exact duplicates, so re-seating the table changes the Caller.",
  )
  val t11r6 = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 9),
      FastAction.Declare("p4", 4),
      FastAction.Confirm("p3", 9, Suit.HEARTS),
      FastAction.Declare("p4", 4),
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 8),
    ),
  )
  measured(
    "T-11", "t11.r6.two-8s-then-a-9", "caller=${t11r6.outcome?.callerId}", "caller=p3",
    "C declares 9: C is the Caller — the A/B tie no longer decides the call, and With still depends on the final number and the final trump.",
  )
  measured(
    "T-11", "t11.r7.two-8s-same-suit",
    "caller=${t11r5a.outcome?.callerId},with=${t11r5a.outcome?.withPlayers?.joinToString(",")}", "caller=p1,with=p2",
    "Two 8s in the same suit are legal, not blocked (A8.1 / Q35): the first declarer is the Caller and the later exact duplicate is With.",
  )
  missing(
    "T-11", "t11.r8.three-ways-on-one-number",
    "Same missing behaviour as t11.r1: 'higher suit first' needs three declarations with three different suits, and no fast declaration carries a suit of its own.",
  )
  val t11r9 = driveFast(
    15, "p1",
    listOf(
      FastAction.Declare("p1", 8),
      FastAction.Declare("p2", 8),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Confirm("p1", 8, Suit.SPADES),
      FastAction.Declare("p2", 9),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Confirm("p2", 9, Suit.HEARTS),
      FastAction.Declare("p3", 4),
      FastAction.Declare("p4", 4),
      FastAction.Declare("p1", 8),
    ),
  )
  measured(
    "T-11", "t11.r9.exact-duplicate-then-a-raise",
    "caller=${t11r9.outcome?.callerId},with=${t11r9.outcome?.withPlayers?.joinToString(",")},trump=${t11r9.outcome?.trump}",
    "caller=p2,with=,trump=HEARTS",
    "An 8 Caller and an 8 With, then B declares 9 and takes Hearts: B is the Caller at 9, the duplicate no longer decides anything, and With is recomputed on the final number and suit.",
  )

  // ══ T-12 — With edge cases ══════════════════════════════════════════════════
  val t12r1 = settle(
    runAuction(
      listOf(
        Triple("p1", 5, Suit.HEARTS), Triple("p2", 7, Suit.HEARTS),
        Triple("p3", null, null), Triple("p4", null, null), Triple("p1", 6, Suit.SPADES),
      ),
    ), "p2", 7, Suit.HEARTS, mapOf("p1" to 5, "p3" to 4, "p4" to 4),
  )
  val t12r2 = settle(
    runAuction(
      listOf(
        Triple("p1", 5, Suit.SPADES), Triple("p2", 7, Suit.HEARTS),
        Triple("p3", null, null), Triple("p4", null, null), Triple("p1", 6, Suit.SPADES),
      ),
    ), "p2", 7, Suit.HEARTS, mapOf("p3" to 4, "p4" to 4, "p1" to 4),
  )
  val t12r3 = settle(
    runAuction(
      listOf(
        Triple("p1", 5, Suit.SPADES), Triple("p2", 5, Suit.HEARTS),
        Triple("p3", null, null), Triple("p4", null, null),
      ),
    ), "p1", 5, Suit.SPADES, mapOf("p2" to 4, "p3" to 4, "p4" to 4),
  )
  val t12r4 = settle(
    runAuction(
      listOf(
        Triple("p1", 5, Suit.HEARTS), Triple("p2", 8, Suit.HEARTS),
        Triple("p3", null, null), Triple("p4", null, null), Triple("p1", 7, Suit.SPADES),
      ),
    ), "p2", 8, Suit.HEARTS, mapOf("p1" to 5, "p3" to 4, "p4" to 4),
  )
  val t12r5 = score(
    bids = mapOf(
      "p1" to Bid(BidType.TRICKS, 7), "p2" to Bid(BidType.TRICKS, 6),
      "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 4),
    ),
    tricksWon = mapOf("p1" to 7, "p2" to 6, "p3" to 3, "p4" to 4),
    callerId = "p1",
    withPlayers = listOf("p2"),
  )
  val t12r6 = score(
    bids = mapOf(
      "p1" to Bid(BidType.TRICKS, 7), "p2" to Bid(BidType.TRICKS, 6),
      "p3" to Bid(BidType.TRICKS, 4), "p4" to Bid(BidType.TRICKS, 4),
    ),
    tricksWon = mapOf("p1" to 7, "p2" to 5, "p3" to 3, "p4" to 4),
    callerId = "p1",
    withPlayers = listOf("p2"),
  )
  measured(
    "T-12", "t12.r1.suit-at-any-time", "with=${t12r1.withPlayers.joinToString(",")},at=5", "with=p1,at=5",
    "Ahmed bids 5H and later 6S; Osama wins 7H: Ahmed is With at 5 — the last number Ahmed bid in the winning suit.",
  )
  measured(
    "T-12", "t12.r2.suit-never-bid", "with=${t12r2.withPlayers.joinToString(",")}", "with=",
    "Ahmed only bid spades; Osama wins hearts: Ahmed is not With.",
  )
  measured(
    "T-12", "t12.r3.same-number-different-suit", "with=${t12r3.withPlayers.joinToString(",")}", "with=",
    "The same number in a different suit is not With in Normal (the suit governs). The plan's own layout — a 5H winning over a 5S — is unreachable under the canonical ladder (S outranks H), so the engine's reachable form is measured: the 5H seat is eliminated and is not With.",
  )
  measured(
    "T-12", "t12.r4.superseded-call", "with=${t12r4.withPlayers.joinToString(",")},at=5", "with=p1,at=5",
    "The seat's last bid is a higher number in another suit (7S) yet it is still With in the winning suit, at the last number it bid in that suit (5).",
  )
  measured(
    "T-12", "t12.r5.with-scoring-makes-it", "delta=${t12r5.deltas["p2"]}", "delta=26",
    "With at 6 makes 6: +26.",
  )
  measured(
    "T-12", "t12.r6.with-scoring-misses-it", "delta=${t12r6.deltas["p2"]}", "delta=-11",
    "With at 6 misses by 1: -11.",
  )

  // ══ T-13 — Shared King and shared Koz ═══════════════════════════════════════
  fun placements(totals: Map<String, Int>): String =
    SEATS.joinToString(",") { "$it=${placementOutcome(totals, it)}" }
  measured(
    "T-13", "t13.r1.all-tied", placements(mapOf("p1" to 100, "p2" to 100, "p3" to 100, "p4" to 100)),
    "p1=KING,p2=KING,p3=KING,p4=KING",
    "All four totals equal: all four are Kings and there is no Koz.",
  )
  measured(
    "T-13", "t13.r2.shared-bottom-of-three", placements(mapOf("p1" to 100, "p2" to 99, "p3" to 99, "p4" to 99)),
    "p1=KING,p2=KOZ,p3=KOZ,p4=KOZ",
    "Every player tied at the lowest is Koz, not Second.",
  )
  measured(
    "T-13", "t13.r3.shared-lowest", placements(mapOf("p1" to 100, "p2" to 99, "p3" to 10, "p4" to 10)),
    "p1=KING,p2=SECOND,p3=KOZ,p4=KOZ",
    "A shared lowest is a shared Koz.",
  )
  measured(
    "T-13", "t13.r4.tied-top-and-a-unique-lowest",
    placements(mapOf("p1" to 100, "p2" to 100, "p3" to 50, "p4" to 20)), "p1=KING,p2=KING,p3=THIRD,p4=KOZ",
    "Two Kings, one Third and one Koz.",
  )
  measured(
    "T-13", "t13.r5.shared-middle", placements(mapOf("p1" to 100, "p2" to 90, "p3" to 90, "p4" to 80)),
    "p1=KING,p2=SECOND,p3=SECOND,p4=KOZ",
    "A shared middle, a unique lowest.",
  )
  measured(
    "T-13", "t13.r6.ordinary-ladder", placements(mapOf("p1" to 100, "p2" to 99, "p3" to 50, "p4" to 20)),
    "p1=KING,p2=SECOND,p3=THIRD,p4=KOZ",
    "The ordinary ladder.",
  )
  val rpBases = listOf(
    MatchOutcome.KING to 30, MatchOutcome.SECOND to 15, MatchOutcome.THIRD to -8, MatchOutcome.KOZ to -20,
  ).joinToString(",") { (outcome, _) ->
    val delta = computeBaseDelta(
      RpMatchInput(outcome = outcome, playerOrdinal = 0, opponentStrength = 0, gameType = GameType.FULL),
      RpEngineConfig(),
    )
    "${outcome.name.lowercase()}=$delta"
  }
  measured(
    "T-13", "t13.r7.rp-base-per-seat", rpBases, "king=30,second=15,third=-8,koz=-20",
    "The RP base values with the strength and tier differences set to zero: King +30, Second +15, Third -8, Koz -20.",
  )

  // ══ T-14 — Vote Kick ═══════════════════════════════════════════════════════
  val standings = mapOf("p1" to 100, "p2" to 60, "p3" to 40, "p4" to 10)
  val voteAvailable = voteKickAvailability(
    mode = MatchMode.RANKED,
    completedRounds = 7,
    standings = standings,
    targetSeat = "p4",
    initiatorSeat = "p1",
    botSeats = emptySet(),
    cooldown = null,
    currentRound = 8,
  )
  val voteSharedKoz = voteKickAvailability(
    mode = MatchMode.RANKED,
    completedRounds = 7,
    standings = mapOf("p1" to 100, "p2" to 60, "p3" to 10, "p4" to 10),
    targetSeat = "p3",
    initiatorSeat = "p1",
    botSeats = emptySet(),
    cooldown = null,
    currentRound = 8,
  )
  val voteRoom = voteKickAvailability(
    mode = MatchMode.ROOM,
    completedRounds = 7,
    standings = standings,
    targetSeat = "p4",
    initiatorSeat = "p1",
    botSeats = emptySet(),
    cooldown = null,
    currentRound = 8,
  )
  val openedVote = openVoteKick(
    targetSeat = "p4",
    initiatorSeat = "p1",
    reason = "griefing",
    eligible = setOf("p1", "p2", "p3"),
    roundOpened = 8,
  )
  val castVote = castVoteKick(openedVote, "p1", VoteKickChoice.YES, setOf("p1", "p2", "p3"))!!
  val castVote2 = castVoteKick(castVote, "p2", VoteKickChoice.YES, setOf("p1", "p2", "p3"))!!
  val voteResolved = resolveVoteKick(castVote2, setOf("p1", "p2", "p3"), matchComplete = false)
  val removal = removalForVoteKick(
    castVote2.copy(status = VoteKickStatus.PASSED),
    mapOf("p1" to "u1", "p2" to "u2", "p3" to "u3", "p4" to "u4"),
  )
  measured(
    "T-14", "t14.s1.charge-on-a-passed-vote",
    LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.VOTE_KICK).toString(), "Koz2",
    "A Vote Kick that passes against a unique Koz records 2 Koz (Q14d(1), superseding A5.1's 1 Koz). The plan's note that the engine still returns Koz1 is stale — the engine answers 2 today.",
  )
  measured(
    "T-14", "t14.s2.shared-koz", voteSharedKoz.toString(), "Unavailable(reason=NO_UNIQUE_KOZ)",
    "Two players tied at the bottom: the vote is unavailable and there is no kick target.",
  )
  measured(
    "T-14", "t14.s3.room-mode", voteRoom.toString(), "Unavailable(reason=WRONG_MODE)",
    "ROOM is not a Vote Kick mode.",
  )
  measured(
    "T-14", "t14.s4.the-vote-opens", voteAvailable.toString(), "Available",
    "RANKED, a unique Koz, seven completed rounds: the vote is available.",
  )
  measured(
    "T-14", "t14.s5.the-tally", "status=$voteResolved,threshold=${voteKickThreshold(3)}", "status=PASSED,threshold=2",
    "Two YES ballots out of three eligible humans pass the vote; the threshold is a majority of the eligible humans.",
  )
  measured(
    "T-14", "t14.s6.the-removal", "seat=${removal?.targetSeat},uid=${removal?.targetUid}", "seat=p4,uid=u4",
    "A passed vote resolves to the removal the services layer executes: the seat goes bot-held and the uid is banned from rejoining.",
  )
  measured(
    "T-14", "t14.s7.return-before-the-finish",
    LeaveMatch.decideLeave(MatchMode.RANKED, LeaveReason.RETURNED_ALLOWED).toString(), "None",
    "A target that reconnects and finishes the match records no charge.",
  )
  missing(
    "T-14", "t14.s8.rp-uses-the-match-outcome",
    "Whether a Vote Kick departure ALSO costs a second last-place penalty is decided by the settlement path (:services / :functions), which never calls the engine: the engine computes the RP for a seat's placement (t13.r7) and has no notion of a departure penalty on top. An engine-parity vector cannot compare a settlement decision.",
  )

  // ══ T-15 — Departure charge matrix ══════════════════════════════════════════
  fun charge(mode: MatchMode, reason: LeaveReason) = LeaveMatch.decideLeave(mode, reason).toString()
  measured(
    "T-15", "t15.s1.explicit-leave",
    listOf(MatchMode.ROOM, MatchMode.UNRANKED, MatchMode.RANKED)
      .joinToString(",") { "$it=${charge(it, LeaveReason.EXPLICIT_LEAVE)}" },
    "ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2",
    "Explicit Leave Match: 1 Koz in ROOM, 2 Koz in UNRANKED and RANKED.",
  )
  measured(
    "T-15", "t15.s2.fifteen-timeout",
    listOf(MatchMode.ROOM, MatchMode.UNRANKED, MatchMode.RANKED)
      .joinToString(",") { "$it=${charge(it, LeaveReason.FIFTEEN_TIMEOUT)}" },
    "ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2",
    "The automatic 15-timeout removal: 1 Koz in ROOM (A8.3 / Q37), 2 Koz in UNRANKED and RANKED.",
  )
  measured(
    "T-15", "t15.s3.vote-kick", charge(MatchMode.RANKED, LeaveReason.VOTE_KICK), "Koz2",
    "A passed Vote Kick: 2 Koz in RANKED and UNRANKED; ROOM has no vote at all.",
  )
  measured(
    "T-15", "t15.s4.permanent-disconnect-replacement",
    listOf(MatchMode.UNRANKED, MatchMode.ROOM, MatchMode.RANKED)
      .joinToString(",") { "$it=${DisconnectVote.permanentCharge(it)}" },
    "UNRANKED=KOZ2,ROOM=NONE,RANKED=null",
    "A permanently replaced seat: 2 Koz in UNRANKED (A7.4), nothing in ROOM, and no entry at all in RANKED.",
  )
  measured(
    "T-15", "t15.s5.return-before-the-finish",
    charge(MatchMode.RANKED, LeaveReason.RETURNED_ALLOWED), "None",
    "A seat that returns before the finish records nothing.",
  )
  measured(
    "T-15", "t15.s6.seventh-bot-takeover",
    listOf(MatchMode.ROOM, MatchMode.UNRANKED, MatchMode.RANKED)
      .joinToString(",") { "$it=${seventhTakeoverCharge(it)}" },
    "ROOM=Koz1,UNRANKED=Koz2,RANKED=Koz2",
    "The 7th bot takeover: 1 Koz in ROOM (A7.3 / Q32), 2 Koz in UNRANKED and RANKED.",
  )
  missing(
    "T-15", "t15.s7.server-or-quota-failure",
    "The engine has no failure-of-the-server path: a server or free-quota failure is decided by the backend that runs the match, and the engine is never told. The engine's charge table covers four leave reasons and cannot express 'the server failed', so there is nothing here to compare.",
  )
  missing(
    "T-15", "t15.s8.charge-timing",
    "WHEN the charge is recorded is a settlement decision (the services layer writes it when the match finishes and the player did not return); the engine decides only the AMOUNT, and it does so from the mode and the reason alone. The timing half of this row is not an engine behaviour.",
  )
  missing(
    "T-15", "t15.s9.abandonment",
    "Abandonment is a separate outcome, not a departure charge: the last human leaving cancels the match and every seat is recorded Koz. The engine has no abandonment path — the cancellation and the 'all four seats are Koz' outcome are decided by the settlement layer.",
  )
  missing(
    "T-15", "t15.s10.rd31-two-koz-rule",
    "RD31's 2-Koz DEPARTURE RESULT (a last-place result for a departed seat) lives in the statistics layer (:services RankedStats), which is RANKED-only and does not consult the engine. The engine prices a departure as a career Koz count (t15.s1 to t15.s6) and knows nothing about a forced last-place result.",
  )

  // ══ T-16 — Disconnect, takeover, reconnect and cancellation ════════════════
  measured(
    "T-16", "t16.s1.the-ballot-window", DisconnectVote.voteWindowSec.toString(), "30",
    "The disconnect ballot runs for 30 seconds over a live table.",
  )
  measured(
    "T-16", "t16.s2.the-pause-window", DisconnectVote.waitWindowSec.toString(), "90",
    "A winning WAIT pauses the match for a bounded 90 seconds after the verdict.",
  )
  measured(
    "T-16", "t16.s3.two-of-three-wait",
    DisconnectVote.decideVote(listOf(DisconnectVoteChoice.WAIT, DisconnectVoteChoice.WAIT, DisconnectVoteChoice.CONTINUE)).toString(),
    "WAIT",
    "Two WAIT ballots out of three settle the ballot as WAIT.",
  )
  measured(
    "T-16", "t16.s4.two-of-three-continue",
    DisconnectVote.decideVote(listOf(DisconnectVoteChoice.CONTINUE, DisconnectVoteChoice.CONTINUE, DisconnectVoteChoice.WAIT)).toString(),
    "CONTINUE",
    "Two CONTINUE ballots out of three settle the ballot as CONTINUE.",
  )
  measured(
    "T-16", "t16.s5.no-agreement",
    DisconnectVote.decideVote(listOf(DisconnectVoteChoice.WAIT, DisconnectVoteChoice.CONTINUE)).toString(),
    "NO_AGREEMENT",
    "A split or a single answer settles as no agreement: the configured bot continues.",
  )
  val ballotRaw = mapOf(
    "p2" to DisconnectVoteState("p2", DisconnectVotePhase.VOTE, 1000L, deadlineMillis = 31000L, answeredCount = 1).toFields(),
    "p3" to DisconnectVoteState(
      "p3", DisconnectVotePhase.WAIT, 1000L, deadlineMillis = 121000L, answeredCount = 2,
      verdict = DisconnectVoteStatus.WAIT,
    ).toFields(),
    "p4" to DisconnectVoteState("p4", DisconnectVotePhase.CANCELLED, 1000L).toFields(),
  )
  measured(
    "T-16", "t16.s6.the-held-seats",
    DisconnectVoteState.heldSeats(ballotRaw).sorted().joinToString(","), "p2,p3",
    "The seats the flow drives right now. A CANCELLED entry is not one: the seat it describes was removed by the 7th takeover (A8.2 / Q36).",
  )
  measured(
    "T-16", "t16.s7.the-reclaimable-seats",
    DisconnectVoteState.reclaimableSeats(ballotRaw).sorted().joinToString(","), "p2,p3",
    "The seats their owner can still take back: every live phase, including the ballot and the granted pause.",
  )
  measured(
    "T-16", "t16.s8.a-cancelled-ballot-is-terminal",
    DisconnectVoteState.parseAll(ballotRaw).getValue("p4").phase.toString(), "CANCELLED",
    "The 7th takeover cancels an open ballot at that instant: the entry is terminal, carries no deadline and no verdict, and never completes.",
  )
  val clockOneWindow = InactivityClock().windowOpened(0L).windowClosed(25_000L, 25_000L)
  val clockTwoWindows = clockOneWindow.windowOpened(25_000L).windowClosed(50_000L, 25_000L)
  measured(
    "T-16", "t16.s9.the-decision-point-clock",
    "threshold=${InactivityClock.TAKEOVER_THRESHOLD_MILLIS},one=${clockOneWindow.inactiveMillis(25_000L)}:${clockOneWindow.takeoverEligibleAt(25_000L)},two=${clockTwoWindows.inactiveMillis(50_000L)}:${clockTwoWindows.takeoverEligibleAt(50_000L)}",
    "threshold=50000,one=25000:false,two=50000:true",
    "The RD19 decision-point clock: one ignored 25 s window is 25 s (not eligible); two ignored windows accumulate to the 50 s threshold (eligible). A timeout expiry does not reset the accumulation, and a genuine interaction inside the player's own window does.",
  )
  missing(
    "T-16", "t16.s10.the-ten-second-takeover-clock",
    "The engine has no 10-second takeover clock of its own: the seconds between the decision timer and the takeover are counted by the services resolver (:services TimeoutResolution), which drives the seat control. The engine's InactivityClock is the RD19 decision-point clock (t16.s9), a different mechanism with a different threshold, so it cannot stand in for this row.",
  )
  missing(
    "T-16", "t16.s11.the-takeover-counter",
    "How many takeovers a seat has survived is counted by the services layer (:services TimeoutResolution) and is persisted on the match document; the engine has no counter and never sees one.",
  )
  missing(
    "T-16", "t16.s12.all-humans-gone",
    "The 60-second wait before a table with no humans is cancelled is a services timer; the engine has no abandonment path at all.",
  )
  missing(
    "T-16", "t16.s13.the-fifteen-timeout-counter",
    "The 15-timeout counter is S61's, kept in the services layer (:services TimeoutResolutionDb) and persisted per uid; the engine only receives the finished removal as a leave reason and prices it (t15.s2).",
  )

  // ══ T-22 — Multiplier ladder and caps ══════════════════════════════════════
  measured(
    "T-22", "t22.s1.the-pass-ladder", "next=${normalPass.joinToString(",")}", "next=2,4,6,8,8",
    "Five all-pass rounds: x2, x4, x6, x8, x8. Only the multiplier caps — the pass count itself does not.",
  )
  measured(
    "T-22", "t22.s2.the-saayda-ladder", "next=${saaydaNextMultiplier(1)},${saaydaNextMultiplier(2)},${saaydaNextMultiplier(4)}",
    "next=2,4,6",
    "Sa'ayda in rounds 18, 19 and 20: x2 on 19, x4 on 20, x6 on 21.",
  )
  measured(
    "T-22", "t22.s3.four-consecutive-saaydas", "${saaydaNextMultiplier(8)}", "8",
    "Four consecutive Sa'aydas: the ladder is at its x8 cap.",
  )
  measured(
    "T-22", "t22.s4.a-success-resets-the-ladder", "${successNextMultiplier()}", "1",
    "Any successful round resets the multiplier to x1.",
  )
  measured(
    "T-22", "t22.s5.classic-never-accumulates", "next=${classicPass.joinToString(",")}", "next=1,1,1",
    "A Classic general pass cancels the round, consumes it and deals fresh into the next round number, and the multiplier stays x1 — twice in a row.",
  )

  // ══ T-24 — Auction, Call Cap, 13 Rule and Dash limits ══════════════════════
  val auctionState = openAuction()
  val bidThree = canSubmit(auctionState, BiddingIntent.AuctionBid("p1", isPass = false, tricks = 3, suit = Suit.HEARTS))
  val bidFour = canSubmit(auctionState, BiddingIntent.AuctionBid("p1", isPass = false, tricks = 4, suit = Suit.HEARTS))
  var dashState = initNormalRound(6, "p1", SEATS, 1, GameType.FULL, ScoringMode.NORMAL)
  dashState = step(dashState, BiddingIntent.DashCallDecision("p1", true)).state
  dashState = step(dashState, BiddingIntent.DashCallDecision("p2", true)).state
  dashState = step(dashState, BiddingIntent.DashCallDecision("p3", true)).state
  val dashCallers = dashCallerIds(dashState)
  val dashThird = dashState.bids["p3"]?.type?.name ?: "none"
  measured(
    "T-24", "t24.s1.minimum-bid-three", if (bidThree.legal) "legal" else "illegal", "illegal",
    "A player who bids 3 is rejected.",
  )
  measured(
    "T-24", "t24.s2.minimum-bid-four", if (bidFour.legal) "legal" else "illegal", "legal",
    "A bid of 4 is accepted.",
  )
  measured(
    "T-24", "t24.s3.bid-seven-is-not-a-super-call",
    normalRoleFor(BidType.TRICKS, 7, isCaller = true, isWith = false, isRisk = false).toString(), "CALLER",
    "A bid of 7 is not a Super Call.",
  )
  measured(
    "T-24", "t24.s4.bid-eight-is-a-super-call",
    normalRoleFor(BidType.TRICKS, 8, isCaller = true, isWith = false, isRisk = false).toString(), "SUPER_CALL",
    "A bid of 8 is a Super Call.",
  )
  measured(
    "T-24", "t24.s5.the-dash-limit", "callers=${dashCallers.joinToString(",")},third=$dashThird",
    "callers=p1,p2,third=rejected",
    "Pre-bid Dash calls by three players: the maximum is 2. The engine does not reject the third Dash Call — it converts it to a PASS — so the cap of 2 still holds, but the plan's 'third rejected' is not what the engine does.",
  )
  val thirteenAtThirteen = thirteenRuleState(mapOf("p2" to 4, "p3" to 4))
  val thirteenAtTwelve = thirteenRuleState(mapOf("p2" to 4, "p3" to 3))
  val thirteenAtFourteen = thirteenRuleState(mapOf("p2" to 4, "p3" to 5))
  measured(
    "T-24", "t24.s6.the-thirteen-rule", thirteenAtThirteen, "forbidden=0,zero=illegal,one=legal",
    "The first three declarations sum to 13, so the last estimator may not pick 0 — the total must not be exactly 13. The estimator is forced into 1-13.",
  )
  measured(
    "T-24", "t24.s7.the-thirteen-rule-scope", thirteenAtTwelve, "forbidden=none,zero=legal,one=legal",
    "The first three declarations sum to 12: the plan's scope row says there is no force, but the engine forbids the last estimator from picking 1 (the total would be exactly 13), so the force is one step earlier than the plan states.",
  )
  measured(
    "T-24", "t24.s8.the-thirteen-rule-scope-at-14", thirteenAtFourteen, "forbidden=none,zero=legal,one=legal",
    "The first three declarations sum to 14: no number can bring the total to 13, so the last estimator is free.",
  )
  val estimatesState = estimatesStateAfterConfirm()
  val callCapNormal = canSubmit(estimatesState, BiddingIntent.FinalEstimate("p2", 6))
  measured(
    "T-24", "t24.s9.call-cap-normal", if (callCapNormal.legal) "legal" else "illegal", "illegal",
    "In a Normal round an estimate above the Caller's bid is rejected.",
  )
  var fastCap = initFastRound(15, "p1", SEATS, 1, GameType.FULL, ScoringMode.NORMAL)
  fastCap = declareFast(fastCap, "p1", 8)
  fastCap = declareFast(fastCap, "p2", 4)
  fastCap = declareFast(fastCap, "p3", 5)
  fastCap = declareFast(fastCap, "p4", 6)
  fastCap = confirmFast(fastCap, "p1", 8, Suit.HEARTS)
  val callCapFast = canSubmit(fastCap, BiddingIntent.FinalEstimate("p2", 9))
  measured(
    "T-24", "t24.s10.call-cap-fast", if (callCapFast.legal) "legal" else "illegal", "legal",
    "In a fast round an estimate above the confirmed call is allowed — there is no Call Cap there.",
  )
  val riskComponents = listOf(15 to 10, 17 to 20, 19 to 30).map { (total, expected) ->
    val risk = calculateNormalScore(Role.RISK, 5, 5, false, false, total)
    val base = calculateNormalScore(Role.NORMAL, 5, 5, false, false, total)
    check(risk - base == expected) { "the risk component is not $expected" }
    expected
  }
  measured(
    "T-24", "t24.s11.risk-value-ten", "${riskComponents[0]}", "10",
    "The Risk seat at a risk value of 10: the value applies exactly once.",
  )
  measured(
    "T-24", "t24.s12.risk-value-twenty", "${riskComponents[1]}", "20",
    "The Risk seat at a risk value of 20: the value applies exactly once.",
  )
  measured(
    "T-24", "t24.s13.risk-value-thirty", "${riskComponents[2]}", "30",
    "The Risk seat at a risk value of 30: the value applies exactly once.",
  )
  val confirmState = runAuction(
    listOf(
      Triple("p1", 5, Suit.HEARTS), Triple("p2", null, null),
      Triple("p3", null, null), Triple("p4", null, null),
    ),
  )
  val weakConfirm = canSubmit(confirmState, BiddingIntent.ConfirmCall("p1", 5, Suit.DIAMONDS))
  val equalConfirm = canSubmit(confirmState, BiddingIntent.ConfirmCall("p1", 5, Suit.HEARTS))
  val strongConfirm = canSubmit(confirmState, BiddingIntent.ConfirmCall("p1", 5, Suit.SPADES))
  measured(
    "T-24", "t24.s14.confirmation-weaker-suit", if (weakConfirm.legal) "legal" else "illegal", "illegal",
    "A confirmation with the same number in a weaker suit is rejected.",
  )
  measured(
    "T-24", "t24.s15.confirmation-equal-or-stronger",
    "${if (equalConfirm.legal) "legal" else "illegal"}/${if (strongConfirm.legal) "legal" else "illegal"}",
    "legal/legal",
    "A confirmation with the same number in an equal or a stronger suit is accepted.",
  )
  val withFloorState = runAuction(
    listOf(
      Triple("p1", 6, Suit.HEARTS), Triple("p2", 7, Suit.HEARTS),
      Triple("p3", null, null), Triple("p4", null, null), Triple("p1", null, null),
    ),
  )
  val withFloorState2 = step(withFloorState, BiddingIntent.ConfirmCall("p2", 7, Suit.HEARTS)).state
  val belowFloor = canSubmit(withFloorState2, BiddingIntent.FinalEstimate("p1", 5))
  measured(
    "T-24", "t24.s16.with-floor", if (belowFloor.legal) "legal" else "illegal", "illegal",
    "A With seat may not estimate below the last number it bid in the winning suit.",
  )

  return cases
}

// ── the small pure helpers the table needs ───────────────────────────────────

/** A fast round's declaration. */
private fun declareFast(state: BiddingState, seat: String, tricks: Int): BiddingState {
  check(state.waitingFor == seat) { "expected $seat to declare, but ${state.waitingFor} does" }
  return step(state, BiddingIntent.FinalEstimate(seat, tricks)).state
}

/** A fast round's confirmation. */
private fun confirmFast(state: BiddingState, seat: String, tricks: Int, suit: Suit): BiddingState {
  check(state.waitingFor == seat) { "expected $seat to confirm, but ${state.waitingFor} does" }
  return step(state, BiddingIntent.ConfirmCall(seat, tricks, suit)).state
}

/** The Classic risk component on a Normal Dash seat: with the Risk flag minus without it. */
private class ClassicRiskComponent(
  val successComponent: String,
  val ordinarySuccess: String,
  val riskSuccess: String,
  val failureComponent: String,
  val ordinaryFailure: String,
  val riskFailure: String,
)

/**
 * The Risk component on a Classic Normal Dash seat, measured as the
 * difference between the SAME table scored with and without the Risk
 * obligation. The two tables differ in nothing else — same bids, same
 * tricks, same sole flags — so the difference IS the component.
 */
private fun classicRiskComponentValue(): ClassicRiskComponent {
  val bids = listOf(5, 5, 5)
  val winTricks = listOf(5, 4, 4)
  val loseTricks = listOf(5, 4, 2)
  val riskWin = dashTable(bids, winTricks, dashTricks = 0, risk = true, classic = true)
  val plainWin = dashTable(bids, winTricks, dashTricks = 0, risk = false, classic = true)
  val riskLose = dashTable(bids, loseTricks, dashTricks = 2, risk = true, classic = true)
  val plainLose = dashTable(bids, loseTricks, dashTricks = 2, risk = false, classic = true)
  return ClassicRiskComponent(
    successComponent = "+${riskWin.deltas.getValue("p1") - plainWin.deltas.getValue("p1")}",
    ordinarySuccess = "${plainWin.deltas.getValue("p1")}",
    riskSuccess = "${riskWin.deltas.getValue("p1")}",
    failureComponent = "${riskLose.deltas.getValue("p1") - plainLose.deltas.getValue("p1")}",
    ordinaryFailure = "${plainLose.deltas.getValue("p1")}",
    riskFailure = "${riskLose.deltas.getValue("p1")}",
  )
}

/** A normal round in which the caller confirmed 5H and no other seat has estimated. */
private fun estimatesStateAfterConfirm(): BiddingState {
  var s = openAuction()
  s = auction(s, "p1", 5, Suit.HEARTS)
  s = auction(s, "p2", 4, Suit.HEARTS)
  s = closeAuction(s)
  return step(s, BiddingIntent.ConfirmCall("p1", 5, Suit.HEARTS)).state
}

/**
 * The 13 rule on the last estimator's turn: the value it may not pick and
 * whether 0 and 1 are legal there.
 */
private fun thirteenRuleState(estimates: Map<String, Int>): String {
  var s = estimatesStateAfterConfirm()
  for ((seat, tricks) in estimates) {
    s = step(s, BiddingIntent.FinalEstimate(seat, tricks)).state
  }
  // A NEGATIVE forbidden number is a number no estimate can ever take, so it is
  // reported as "none" — the token then answers the plan's question ("is there
  // a force on the last estimator?") rather than the engine's arithmetic.
  val forbidden = forbiddenEstimateFor(s, "p4")?.let { if (it < 0) null else it }?.toString() ?: "none"
  val zero = canSubmit(s, BiddingIntent.FinalEstimate("p4", 0))
  val one = canSubmit(s, BiddingIntent.FinalEstimate("p4", 1))
  return "forbidden=$forbidden,zero=${if (zero.legal) "legal" else "illegal"}," +
    "one=${if (one.legal) "legal" else "illegal"}"
}
