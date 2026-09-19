package com.estemshan.engine

/**
 * Round scoring wrapper ported from design-ui/engine/scoring-engine.js
 * (calculateRoundScore / computeRiskPlayerId / computeRoundExtension /
 * calculateMatchScore / calculateTeamScore). THE references are
 * docs/specs/02-engine-api.md §5 and docs/specs/04-scoring.md — per §5,
 * NATIVE deltas come from 04-scoring.md (bid²/dash/sole/risk-ladder),
 * computed by Scoring.kt, not re-derived here. Pure functions, no I/O:
 * persistence (GameSession) belongs to the session layer, not here.
 */
data class RoundScoreInput(
  val round: Int,
  val order: List<String> = DEFAULT_SEATS,
  /** Bids keyed by seat; DASHCALL/DASH carry amount 0. */
  val bids: Map<String, Bid>,
  val tricksWon: Map<String, Int>,
  val callerId: String?,
  val withPlayers: List<String> = emptyList(),
  /** This round's applied multiplier (1 at baseline). */
  val multiplier: Int = 1,
  /** Real last bidder when known (covers fast rounds with no caller). */
  val riskPlayerId: String? = null,
  val classic: Boolean = false,
  /** Sa'ayda ceiling: 8 Normal, 2 Classic. */
  val escalationCap: Int = 8,
)

data class SeatBreakdown(
  val raw: Int,
  val multiplier: Int,
  val final: Int,
  val isCaller: Boolean,
  val isWith: Boolean,
  val isRisk: Boolean,
  val isSoleWinner: Boolean,
  val isSoleLoser: Boolean,
  val succeeded: Boolean,
  val role: Role?,
  val notes: List<String>,
)

data class RoundScoreResult(
  val round: Int,
  val totalBids: Int,
  val diff: Int,
  val isOver: Boolean,
  val riskValue: Int,
  val riskPlayerId: String?,
  val successCount: Int,
  val failedCount: Int,
  val isSaayda: Boolean,
  val appliedMultiplier: Int,
  val nextMultiplier: Int,
  val deltas: Map<String, Int>,
  val breakdown: Map<String, SeatBreakdown>,
)

enum class ExtensionReason {
  SUPER_CALL,
  SAAYDA,
}

data class RoundExtension(val extend: Boolean, val reason: ExtensionReason? = null)

/**
 * A Super Call is any locked bid of 8+ tricks — defined by the amount
 * alone. Only the Caller themselves holds this role; a matching With
 * player stays WIZZ/WIZZ_RISK regardless of the Caller's Super status.
 */
fun normalRoleFor(
  bidType: BidType,
  bidAmount: Int,
  isCaller: Boolean,
  isWith: Boolean,
  isRisk: Boolean,
): Role {
  if (bidType == BidType.DASHCALL) return Role.DASH_CALL
  if (bidType == BidType.DASH) return Role.REG_DASH
  if (isCaller && bidAmount >= 8) return Role.SUPER_CALL
  if (isWith && isRisk) return Role.WIZZ_RISK
  if (isCaller) return Role.CALLER
  if (isWith) return Role.WIZZ
  if (isRisk) return Role.RISK
  return Role.NORMAL
}

/**
 * Rules §2.3: the Risk obligation passes backward past any pre-bidding
 * Dash Call seat to the previous eligible (actually estimating) player —
 * a DASHCALL bid never estimates, so it is excluded regardless of any
 * caller-supplied riskPlayerId.
 */
fun computeRiskPlayerId(
  callerId: String?,
  bids: Map<String, Bid>,
  order: List<String> = DEFAULT_SEATS,
): String? {
  if (callerId == null) return null
  val seats = ArrayList<String>(order.size - 1)
  var cur = nextSeat(order, callerId)
  repeat(order.size - 1) {
    seats.add(cur)
    cur = nextSeat(order, cur)
  }
  val estimators = seats.filter {
    val b = bids[it]
    b != null && (b.type == BidType.TRICKS || b.type == BidType.DASH)
  }
  return estimators.lastOrNull()
}

private fun bidWon(bid: Bid?, won: Int): Boolean {
  if (bid == null) return false
  return when (bid.type) {
    BidType.DASHCALL, BidType.DASH -> won == 0
    BidType.TRICKS -> won == bid.amount
  }
}

/** Pure calculation — no persistence. */
fun calculateRoundScore(input: RoundScoreInput): RoundScoreResult {
  val order = input.order
  val total = order.sumOf { input.bids[it]?.amount ?: 0 }
  val diff = kotlin.math.abs(13 - total)
  val isOver = total > 13
  val ladder = riskValue(total)
  val riskId = input.riskPlayerId ?: computeRiskPlayerId(input.callerId, input.bids, order)

  val wonBy = order.associateWith { input.tricksWon[it] ?: 0 }
  val successCount = order.count { bidWon(input.bids[it], wonBy.getValue(it)) }
  val failedCount = order.size - successCount
  val isSaayda = successCount == 0 // rules §4 Escalation Round

  val deltas = LinkedHashMap<String, Int>()
  val breakdown = LinkedHashMap<String, SeatBreakdown>()
  for (id in order) {
    val bid = input.bids[id]
    val won = wonBy.getValue(id)
    val isCaller = id == input.callerId
    val isWith = input.withPlayers.contains(id)
    val isRisk = id == riskId
    val win = bidWon(bid, won)
    val soleWinner = successCount == 1 && win
    val soleLoser = failedCount == 1 && !win
    val notes = ArrayList<String>()
    var role: Role? = null
    var delta = 0

    if (isSaayda) {
      notes.add("Sa'ayda — round zeroed for everyone")
    } else if (bid == null) {
      notes.add("No bid on record for this seat — scored 0")
    } else if (input.classic) {
      role = normalRoleFor(bid.type, bid.amount, isCaller, isWith, isRisk)
      val classicBid = if (bid.type == BidType.TRICKS) bid.amount else 0
      delta = calculateClassicScore(role, classicBid, won, total, soleWinner, soleLoser)
      notes.add("Classic $role ${if (win) "success" else "failure"} → $delta")
    } else {
      role = normalRoleFor(bid.type, bid.amount, isCaller, isWith, isRisk)
      val normalBid = if (bid.type == BidType.TRICKS) bid.amount else 0
      delta = calculateNormalScore(role, normalBid, won, soleWinner, soleLoser, total)
      notes.add("$role ${if (win) "success" else "failure"} → $delta")
    }

    // A Sa'ayda round scores zero, not "zero × multiplier".
    val applied = if (isSaayda) 1 else input.multiplier
    val final = delta * applied
    deltas[id] = final
    breakdown[id] = SeatBreakdown(
      raw = delta,
      multiplier = applied,
      final = final,
      isCaller = isCaller,
      isWith = isWith,
      isRisk = isRisk,
      isSoleWinner = soleWinner,
      isSoleLoser = soleLoser,
      succeeded = win,
      role = role,
      notes = notes,
    )
  }

  // Sa'ayda ladder: ×2→×4→×6→×8 on consecutive all-fail rounds (cap
  // differs by mode), reset to ×1 the instant any seat succeeds. The
  // current multiplier 1 means "0 escalation steps so far".
  val priorSteps = if (input.multiplier == 1) 0 else input.multiplier
  val nextMultiplier = if (isSaayda) minOf(priorSteps + 2, input.escalationCap) else 1

  return RoundScoreResult(
    round = input.round,
    totalBids = total,
    diff = diff,
    isOver = isOver,
    riskValue = ladder,
    riskPlayerId = riskId,
    successCount = successCount,
    failedCount = failedCount,
    isSaayda = isSaayda,
    appliedMultiplier = if (isSaayda) 1 else input.multiplier,
    nextMultiplier = nextMultiplier,
    deltas = deltas,
    breakdown = breakdown,
  )
}

/**
 * Whether the completed round extends maxRounds by exactly +1 — reusing
 * ALREADY-COMPUTED facts, never re-deriving Super legality or success.
 * Only Rapid Rounds 14-18 are eligible; an already-extended round
 * (19+) never extends further. The two reasons are mutually exclusive
 * (Sa'ayda means the caller failed too), so at most +1 per round.
 */
fun computeRoundExtension(
  round: Int,
  callerId: String?,
  trump: Suit,
  callerSucceeded: Boolean,
  isSaayda: Boolean,
): RoundExtension {
  if (round < 14 || round > 18) return RoundExtension(false)
  if (isSaayda) return RoundExtension(true, ExtensionReason.SAAYDA)
  // In a fast round callerId is ONLY populated by the Super Call path —
  // a non-null caller here already IS the Super signal, not a re-derivation.
  if (callerId != null && callerSucceeded && trump != fixedTrumpFor(round)) {
    return RoundExtension(true, ExtensionReason.SUPER_CALL)
  }
  return RoundExtension(false)
}

/**
 * Running match totals: current + this round's deltas. A seat with no
 * recorded delta (a real upstream data-flow gap, never a completed
 * round) counts 0 — a genuine 0 delta (e.g. Sa'ayda) is added as-is.
 */
fun accumulateMatchScores(
  current: Map<String, Int>,
  deltas: Map<String, Int>,
): Map<String, Int> =
  current.mapValues { (id, score) -> score + (deltas[id] ?: 0) }

/**
 * Rules §1: 4 players, individual play, no partnerships — the document
 * defines no team concept and no team formula. Null, never invented.
 */
fun calculateTeamScore(): Nothing? = null
