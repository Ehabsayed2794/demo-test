package com.estemshan.engine

/**
 * Bidding phase ported from design-ui/engine/bidding-engine.js for Normal
 * and Fast (14-18) rounds. THE reference is docs/specs/02-engine-api.md §3 —
 * every rule below traces to a branch there. Pure functions, no I/O:
 * seats and bid history are injected, outcomes are returned, never stored.
 * No GameSession, no Android imports (spec §7 layering).
 */
enum class BiddingPhase {
  DASH,
  AUCTION,
  CONFIRM,
  ESTIMATES,
  DONE,
}

enum class BidType {
  DASHCALL,
  PASS,
  TRICKS,
  DASH,
}

data class Bid(val type: BidType, val amount: Int = 0)

sealed class BiddingIntent {
  data class DashCallDecision(val playerId: String, val declaredDashCall: Boolean) : BiddingIntent()
  data class AuctionBid(
    val playerId: String,
    val isPass: Boolean,
    val tricks: Int? = null,
    val suit: Suit? = null,
  ) : BiddingIntent()
  data class ConfirmCall(val playerId: String, val tricks: Int, val suit: Suit) : BiddingIntent()
  data class FinalEstimate(val playerId: String, val tricks: Int) : BiddingIntent()
}

/**
 * Audit trail mirroring GameSession's actionHistory. Only entries with
 * actionType "BID" feed withFloorFor(); everything else is audit-only.
 * Types mirror the JS record calls: DASHCALL, PASS, BID, CONFIRM_TRUMP,
 * ESTIMATE.
 */
data class BidAction(
  val playerId: String,
  val actionType: String,
  val value: Int? = null,
  val suit: Suit? = null,
)

data class BiddingOutcome(
  val trump: Suit,
  val callerId: String?,
  val withPlayers: List<String>,
  /** Committed final estimates (TRICKS + Normal-Dash 0s; Dash-Call seats excluded). */
  val estimates: Map<String, Int>,
  val dashCallers: List<String>,
  val riskPlayerId: String?,
  val leaderId: String,
)

sealed class EmitResult {
  data class Applied(val state: BiddingState) : EmitResult()
  data class Rejected(val reason: String) : EmitResult()
  data class Completed(val state: BiddingState, val outcome: BiddingOutcome) : EmitResult()
  /** All four passed the auction — redeal at the doubled (×8-capped) multiplier. */
  data class GeneralPass(val state: BiddingState, val doubledMultiplier: Int) : EmitResult()
}

data class Legality(val legal: Boolean, val reason: String? = null)

data class BiddingState(
  val round: Int,
  val subPhase: BiddingPhase,
  val seats: List<String>,
  val waitingFor: String?,
  val firstBidder: String,
  val bids: Map<String, Bid>,
  val auctionTop: Int,
  val auctionSuit: Suit?,
  val auctionBidder: String?,
  val activeBidders: List<String>,
  val withPlayers: List<String>,
  val callerId: String?,
  val declaredTrump: Suit?,
  val lastBidderId: String?,
  val fastRound: Boolean,
  val noSuitConstraint: Boolean,
  val fastSuperResolved: Boolean,
  val actionHistory: List<BidAction>,
  val roundMultiplier: Int,
)

const val MAX_DASH_CALLS = 2

val DEFAULT_SEATS: List<String> = listOf("p1", "p2", "p3", "p4")

private val FIXED_SUITS: List<Suit> = listOf(
  Suit.SANS, Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS,
)

fun isFastRound(roundNumber: Int): Boolean = roundNumber >= 14

fun fixedTrumpFor(roundNumber: Int): Suit = FIXED_SUITS[(roundNumber - 14) % FIXED_SUITS.size]

fun initNormalRound(
  round: Int,
  dealer: String,
  seats: List<String> = DEFAULT_SEATS,
  multiplier: Int = 1,
): BiddingState = BiddingState(
  round = round,
  subPhase = BiddingPhase.DASH,
  seats = seats,
  waitingFor = dealer,
  firstBidder = dealer,
  bids = emptyMap(),
  auctionTop = 0,
  auctionSuit = null,
  auctionBidder = null,
  activeBidders = seats.toList(),
  withPlayers = emptyList(),
  callerId = null,
  declaredTrump = null,
  lastBidderId = null,
  fastRound = false,
  noSuitConstraint = false,
  fastSuperResolved = false,
  actionHistory = emptyList(),
  roundMultiplier = multiplier,
)

fun initFastRound(
  round: Int,
  dealer: String,
  seats: List<String> = DEFAULT_SEATS,
  multiplier: Int = 1,
): BiddingState {
  val fixedTrump = fixedTrumpFor(round)
  return BiddingState(
    round = round,
    subPhase = BiddingPhase.ESTIMATES,
    seats = seats,
    waitingFor = dealer,
    firstBidder = dealer,
    bids = emptyMap(),
    // Sentinel: no per-player cap in fast rounds (mirrors the JS 13).
    auctionTop = 13,
    auctionSuit = fixedTrump,
    auctionBidder = null,
    activeBidders = seats.toList(),
    withPlayers = emptyList(),
    callerId = null,
    declaredTrump = fixedTrump,
    lastBidderId = null,
    fastRound = true,
    noSuitConstraint = false,
    fastSuperResolved = false,
    actionHistory = emptyList(),
    roundMultiplier = multiplier,
  )
}

fun nextSeat(seats: List<String>, id: String): String =
  seats[(seats.indexOf(id) + 1) % seats.size]

/** Round's actual bidding order: seats rotated so the dealer bids first. */
fun biddingOrder(state: BiddingState): List<String> {
  val out = ArrayList<String>(state.seats.size)
  var seat = state.firstBidder
  repeat(state.seats.size) {
    out.add(seat)
    seat = nextSeat(state.seats, seat)
  }
  return out
}

/**
 * Risk fallback = the seat immediately BEFORE the caller (3 steps CCW in a
 * 4-cycle). Only a fallback — the real signal is lastBidderId, which also
 * covers fast rounds with no caller at all.
 */
fun computeRiskId(seats: List<String>, callerId: String): String {
  val i = seats.indexOf(callerId)
  return seats[(i - 1 + seats.size) % seats.size]
}

// ── Legality predicates (shared by canSubmit and emit — one source of truth) ──

fun auctionBidBeatsTop(bidVal: Int, bidSuit: Suit, auctionTop: Int, auctionSuit: Suit?): Boolean =
  bidVal > auctionTop ||
    (bidVal == auctionTop && bidSuit.strength > (auctionSuit?.strength ?: 0))

fun auctionBidIsWith(
  pId: String,
  bidVal: Int,
  bidSuit: Suit,
  auctionTop: Int,
  auctionSuit: Suit?,
  auctionBidder: String?,
): Boolean =
  bidVal == auctionTop && bidSuit == auctionSuit && pId != auctionBidder && auctionBidder != null

fun bidBelowWinningCall(t: Int, auctionTop: Int): Boolean = t < auctionTop

fun confirmSuitTooWeak(
  t: Int,
  s: Suit,
  auctionTop: Int,
  auctionSuit: Suit?,
  noSuitConstraint: Boolean,
): Boolean =
  !noSuitConstraint && t == auctionTop && s.strength < (auctionSuit?.strength ?: 0)

fun estimateExceedsCap(tricks: Int, cap: Int): Boolean = tricks > cap

/** A With player's floor = the last real number THEY personally bid (BID history). */
fun withFloorFor(history: List<BidAction>, pId: String): Int? =
  history.filter { it.actionType == "BID" && it.playerId == pId }.lastOrNull()?.value

fun estimateBelowWithFloor(
  history: List<BidAction>,
  pId: String,
  tricks: Int,
  withPlayers: List<String>,
): Boolean {
  if (!withPlayers.contains(pId)) return false
  val floor = withFloorFor(history, pId) ?: return false
  return tricks < floor
}

/** The number the R1 rule forbids for this seat right now (null = no constraint). */
fun forbiddenEstimateFor(state: BiddingState, pId: String): Int? {
  val others = state.seats.filter { it != pId && state.bids[it] != null }
  if (others.size != state.seats.size - 1) return null
  val otherSum = others.sumOf { if (state.bids.getValue(it).type == BidType.TRICKS) state.bids.getValue(it).amount else 0 }
  return 13 - otherSum
}

fun estimateIsForbidden13(state: BiddingState, pId: String, tricks: Int): Boolean {
  val forbidden = forbiddenEstimateFor(state, pId) ?: return false
  return tricks == forbidden
}

fun bidSum(bids: Map<String, Bid>): Int =
  bids.values.sumOf { if (it.type == BidType.TRICKS) it.amount else 0 }

/**
 * Committed estimates: TRICKS and Normal-Dash (0-trick final) bids count;
 * DASHCALL/PASS never reach this phase shape. A 0 estimate is legitimate
 * data, not missing data.
 */
fun extractEstimates(bids: Map<String, Bid>): Map<String, Int> =
  bids.filter { it.value.type == BidType.TRICKS || it.value.type == BidType.DASH }
    .mapValues { it.value.amount }

fun dashCallerIds(state: BiddingState): List<String> =
  state.seats.filter { state.bids[it]?.type == BidType.DASHCALL }

// ── canSubmit: pure, read-only projection of emit's legality ──

fun canSubmit(state: BiddingState, intent: BiddingIntent): Legality {
  if (state.subPhase == BiddingPhase.DONE) return Legality(false, "Bidding is already complete")
  return when (intent) {
    is BiddingIntent.DashCallDecision -> {
      if (state.subPhase != BiddingPhase.DASH) return Legality(false, "Not the Dash-Call phase")
      if (state.waitingFor != intent.playerId) return Legality(false, "Not this seat's turn")
      // Over-the-limit Dash Calls auto-convert to PASS inside emit — never rejected.
      Legality(true)
    }
    is BiddingIntent.AuctionBid -> {
      if (state.subPhase != BiddingPhase.AUCTION) return Legality(false, "Not the Auction phase")
      if (state.waitingFor != intent.playerId) return Legality(false, "Not this seat's turn")
      if (intent.isPass) return Legality(true)
      val tricks = intent.tricks
      val suit = intent.suit
      if (tricks == null || tricks < 4 || tricks > 13) {
        return Legality(false, "Bid must be between 4 and 13 tricks")
      }
      if (suit == null) return Legality(false, "Malformed intent")
      val isWith = auctionBidIsWith(
        intent.playerId, tricks, suit, state.auctionTop, state.auctionSuit, state.auctionBidder,
      )
      val beatsTop = auctionBidBeatsTop(tricks, suit, state.auctionTop, state.auctionSuit)
      if (isWith || beatsTop) return Legality(true)
      return Legality(
        false,
        "Bid does not beat the current top bid of ${state.auctionTop}" +
          (if (state.auctionSuit != null) " ${state.auctionSuit.name}" else ""),
      )
    }
    is BiddingIntent.ConfirmCall -> {
      if (state.subPhase != BiddingPhase.CONFIRM) return Legality(false, "Not the Confirmation phase")
      if (state.waitingFor != intent.playerId) return Legality(false, "Not this seat's turn")
      if (bidBelowWinningCall(intent.tricks, state.auctionTop)) {
        return Legality(false, "Can't lower your winning call")
      }
      if (confirmSuitTooWeak(intent.tricks, intent.suit, state.auctionTop, state.auctionSuit, state.noSuitConstraint)) {
        return Legality(false, "Same number needs an equal or stronger suit")
      }
      Legality(true)
    }
    is BiddingIntent.FinalEstimate -> {
      if (state.subPhase != BiddingPhase.ESTIMATES) return Legality(false, "Not the Final Estimates phase")
      if (state.waitingFor != intent.playerId) return Legality(false, "Not this seat's turn")
      if (estimateExceedsCap(intent.tricks, state.auctionTop)) {
        return Legality(false, "Max is ${state.auctionTop} (Caller's cap)")
      }
      if (estimateBelowWithFloor(state.actionHistory, intent.playerId, intent.tricks, state.withPlayers)) {
        val floor = withFloorFor(state.actionHistory, intent.playerId)
        return Legality(false, "Min is $floor (your own With bid)")
      }
      if (estimateIsForbidden13(state, intent.playerId, intent.tricks)) {
        val forbidden = forbiddenEstimateFor(state, intent.playerId)
        return Legality(false, "Can't pick $forbidden — totals 13")
      }
      Legality(true)
    }
  }
}

// ── Reducer: intent in → new state out; rejections are typed, never silent ──

fun emit(state: BiddingState, intent: BiddingIntent): EmitResult {
  if (state.subPhase == BiddingPhase.DONE) return EmitResult.Rejected("Bidding is already complete")
  return when (intent) {
    is BiddingIntent.DashCallDecision -> emitDashCall(state, intent)
    is BiddingIntent.AuctionBid -> emitAuctionBid(state, intent)
    is BiddingIntent.ConfirmCall -> emitConfirmCall(state, intent)
    is BiddingIntent.FinalEstimate -> emitFinalEstimate(state, intent)
  }
}

private fun emitDashCall(state: BiddingState, intent: BiddingIntent.DashCallDecision): EmitResult {
  if (state.subPhase != BiddingPhase.DASH) return EmitResult.Rejected("Not the Dash-Call phase")
  if (state.waitingFor != intent.playerId) return EmitResult.Rejected("Not this seat's turn")

  val existing = state.bids.values.count { it.type == BidType.DASHCALL }
  val effective = intent.declaredDashCall && existing < MAX_DASH_CALLS
  val record = if (effective) Bid(BidType.DASHCALL, 0) else Bid(BidType.PASS, 0)
  val newBids = state.bids + (intent.playerId to record)
  val history = state.actionHistory +
    BidAction(intent.playerId, if (effective) "DASHCALL" else "PASS")

  if (newBids.size < state.seats.size) {
    return EmitResult.Applied(
      state.copy(
        bids = newBids,
        waitingFor = nextSeat(state.seats, intent.playerId),
        actionHistory = history,
      ),
    )
  }

  val active = state.seats.filter { newBids[it]?.type != BidType.DASHCALL }
  val cleared = newBids.filterValues { it.type != BidType.PASS }
  if (active.isEmpty()) {
    // All four dashed — trump SANS, every seat scores an explicit 0.
    val allZero = state.seats.associateWith { 0 }
    val done = state.copy(
      subPhase = BiddingPhase.DONE,
      bids = cleared,
      activeBidders = active,
      declaredTrump = Suit.SANS,
      waitingFor = null,
      actionHistory = history,
    )
    return EmitResult.Completed(
      done,
      BiddingOutcome(
        trump = Suit.SANS,
        callerId = null,
        withPlayers = emptyList(),
        estimates = allZero,
        dashCallers = emptyList(),
        riskPlayerId = null,
        leaderId = state.firstBidder,
      ),
    )
  }
  return EmitResult.Applied(
    state.copy(
      subPhase = BiddingPhase.AUCTION,
      bids = cleared,
      activeBidders = active,
      auctionTop = 0,
      auctionSuit = null,
      auctionBidder = null,
      waitingFor = active[0],
      actionHistory = history,
    ),
  )
}

private fun emitAuctionBid(state: BiddingState, intent: BiddingIntent.AuctionBid): EmitResult {
  if (state.subPhase != BiddingPhase.AUCTION) return EmitResult.Rejected("Not the Auction phase")
  if (state.waitingFor != intent.playerId) return EmitResult.Rejected("Not this seat's turn")
  val pId = intent.playerId
  var active = state.activeBidders.toMutableList()
  var withPlayers = state.withPlayers.toMutableList()
  var auctionTop = state.auctionTop
  var auctionSuit = state.auctionSuit
  var auctionBidder = state.auctionBidder
  var history = state.actionHistory
  var eliminated = false

  val bidVal = if (intent.isPass) null else intent.tricks
  val bidSuit = if (intent.isPass) null else intent.suit
  if (intent.isPass || bidVal == null || bidVal < 4 || bidVal > 13 || bidSuit == null) {
    // A pass (or an out-of-range / suit-less bid) only eliminates — it never
    // strips an earlier With match.
    active.remove(pId)
    eliminated = true
  } else {
    val isWith = auctionBidIsWith(pId, bidVal, bidSuit, auctionTop, auctionSuit, auctionBidder)
    val beatsTop = auctionBidBeatsTop(bidVal, bidSuit, auctionTop, auctionSuit)
    if (isWith) {
      if (!withPlayers.contains(pId)) withPlayers.add(pId)
    } else if (beatsTop) {
      if (bidSuit != auctionSuit) withPlayers = ArrayList()
      auctionTop = bidVal
      auctionSuit = bidSuit
      auctionBidder = pId
    } else {
      if (pId != auctionBidder) {
        active.remove(pId)
        eliminated = true
      }
    }
  }

  if (active.isEmpty() && auctionBidder == null) {
    // General pass — no rule-doc formula of its own: redeal at the doubled
    // multiplier, capped at ×8 like the Sa'ayda ladder.
    val doubled = minOf(state.roundMultiplier * 2, 8)
    return EmitResult.GeneralPass(
      state.copy(
        activeBidders = active,
        withPlayers = withPlayers,
        auctionTop = auctionTop,
        auctionSuit = auctionSuit,
        auctionBidder = auctionBidder,
        waitingFor = null,
        actionHistory = history + BidAction(pId, "PASS"),
      ),
      doubled,
    )
  }

  if (active.isEmpty() || (active.size == 1 && auctionBidder != null)) {
    val caller = auctionBidder
    // Auction Alignment (rules §2.2.1a): ANY seat that bid the winning SUIT
    // at any point — even a different number — becomes With. Pass-only
    // seats and the Caller are excluded. The concluding action itself is
    // not in history yet, exactly like the JS (conclusion records nothing).
    val suitMatchers = history
      .filter { it.actionType == "BID" && it.suit == auctionSuit && it.playerId != caller }
      .map { it.playerId }
      .distinct()
    for (id in suitMatchers) if (!withPlayers.contains(id)) withPlayers.add(id)
    return EmitResult.Applied(
      state.copy(
        subPhase = BiddingPhase.CONFIRM,
        activeBidders = active,
        withPlayers = withPlayers,
        auctionTop = auctionTop,
        auctionSuit = auctionSuit,
        auctionBidder = auctionBidder,
        callerId = caller,
        waitingFor = caller,
        actionHistory = history,
      ),
    )
  }

  var cand = nextSeat(state.seats, pId)
  var guard = 0
  while (!active.contains(cand) && guard < 8) {
    cand = nextSeat(state.seats, cand)
    guard++
  }
  history = history + BidAction(
    pId,
    if (eliminated) "PASS" else "BID",
    if (eliminated) null else bidVal,
    if (eliminated) null else bidSuit,
  )
  return EmitResult.Applied(
    state.copy(
      activeBidders = active,
      withPlayers = withPlayers,
      auctionTop = auctionTop,
      auctionSuit = auctionSuit,
      auctionBidder = auctionBidder,
      waitingFor = cand,
      actionHistory = history,
    ),
  )
}

private fun emitConfirmCall(state: BiddingState, intent: BiddingIntent.ConfirmCall): EmitResult {
  if (state.subPhase != BiddingPhase.CONFIRM) return EmitResult.Rejected("Not the Confirmation phase")
  if (state.waitingFor != intent.playerId) return EmitResult.Rejected("Not this seat's turn")
  val t = intent.tricks
  val s = intent.suit
  if (bidBelowWinningCall(t, state.auctionTop)) {
    return EmitResult.Rejected("Can't lower your winning call")
  }
  if (confirmSuitTooWeak(t, s, state.auctionTop, state.auctionSuit, state.noSuitConstraint)) {
    return EmitResult.Rejected("Same number needs an equal or stronger suit")
  }

  val newBids = state.bids + (intent.playerId to Bid(BidType.TRICKS, t))
  val history = state.actionHistory + BidAction(intent.playerId, "CONFIRM_TRUMP", t, s)

  if (state.noSuitConstraint) {
    // Fast-round Super Call: replacement trump wipes ONLY the seats that bid
    // before the Super Caller; later estimates remain committed.
    val order = biddingOrder(state)
    val callerIndex = order.indexOf(intent.playerId)
    val preSuper = if (callerIndex > 0) order.subList(0, callerIndex) else emptyList()
    val wiped = newBids.filterKeys { !preSuper.contains(it) }
    return if (preSuper.isNotEmpty()) {
      EmitResult.Applied(
        state.copy(
          auctionTop = t,
          auctionSuit = s,
          declaredTrump = s,
          bids = wiped,
          callerId = intent.playerId,
          subPhase = BiddingPhase.ESTIMATES,
          noSuitConstraint = false,
          fastSuperResolved = true,
          waitingFor = preSuper[0],
          lastBidderId = null,
          actionHistory = history,
        ),
      )
    } else {
      val done = state.copy(
        auctionTop = t,
        auctionSuit = s,
        declaredTrump = s,
        bids = wiped,
        callerId = intent.playerId,
        subPhase = BiddingPhase.DONE,
        noSuitConstraint = false,
        fastSuperResolved = true,
        waitingFor = null,
        actionHistory = history,
      )
      EmitResult.Completed(
        done,
        BiddingOutcome(
          trump = s,
          callerId = intent.playerId,
          withPlayers = state.withPlayers.toList(),
          estimates = extractEstimates(wiped),
          dashCallers = emptyList(),
          riskPlayerId = state.lastBidderId,
          leaderId = intent.playerId,
        ),
      )
    }
  }

  // Normal-round confirm: Dash Callers never re-estimate — start at the
  // first seat CCW from the Caller that has no bid on record yet.
  var firstEstimator = nextSeat(state.seats, intent.playerId)
  var estGuard = 0
  while (newBids[firstEstimator] != null && estGuard < 8) {
    firstEstimator = nextSeat(state.seats, firstEstimator)
    estGuard++
  }
  return EmitResult.Applied(
    state.copy(
      auctionTop = t,
      auctionSuit = s,
      declaredTrump = s,
      bids = newBids,
      subPhase = BiddingPhase.ESTIMATES,
      waitingFor = firstEstimator,
      actionHistory = history,
    ),
  )
}

private fun emitFinalEstimate(state: BiddingState, intent: BiddingIntent.FinalEstimate): EmitResult {
  if (state.subPhase != BiddingPhase.ESTIMATES) return EmitResult.Rejected("Not the Final Estimates phase")
  if (state.waitingFor != intent.playerId) return EmitResult.Rejected("Not this seat's turn")
  val pId = intent.playerId
  val cap = state.auctionTop

  if (estimateExceedsCap(intent.tricks, cap)) {
    return EmitResult.Rejected("Max is $cap (Caller's cap)")
  }
  if (estimateBelowWithFloor(state.actionHistory, pId, intent.tricks, state.withPlayers)) {
    val floor = withFloorFor(state.actionHistory, pId)
    return EmitResult.Rejected("Min is $floor (your own With bid)")
  }
  if (estimateIsForbidden13(state, pId, intent.tricks)) {
    val forbidden = forbiddenEstimateFor(state, pId)
    return EmitResult.Rejected("Can't pick $forbidden — totals 13")
  }

  val record = if (intent.tricks == 0) Bid(BidType.DASH, 0) else Bid(BidType.TRICKS, intent.tricks)
  val newBids = state.bids + (pId to record)
  var withPlayers = state.withPlayers.toMutableList()

  // Estimation Jump-In (rules §2.2.1a, normal rounds): any non-caller whose
  // final estimate exactly matches the Caller's locked number becomes With.
  if (!state.fastRound && pId != state.callerId && intent.tricks == state.auctionTop &&
    !withPlayers.contains(pId)
  ) {
    withPlayers.add(pId)
  }

  val resolved = state.seats.count { newBids[it] != null }
  if (resolved < state.seats.size) {
    var cand = nextSeat(state.seats, pId)
    var guard = 0
    while (newBids[cand] != null && guard < 8) {
      cand = nextSeat(state.seats, cand)
      guard++
    }
    return EmitResult.Applied(
      state.copy(
        bids = newBids,
        withPlayers = withPlayers,
        waitingFor = cand,
        actionHistory = state.actionHistory + BidAction(pId, "ESTIMATE", intent.tricks, null),
      ),
    )
  }

  val sum = bidSum(newBids)
  val base = state.copy(
    bids = newBids,
    withPlayers = withPlayers,
    lastBidderId = pId,
    actionHistory = state.actionHistory + BidAction(pId, "ESTIMATE", intent.tricks, null),
  )

  if (state.fastRound) {
    val order = biddingOrder(state)
    val superCandidates = state.seats
      .filter { newBids[it]?.type == BidType.TRICKS && newBids.getValue(it).amount >= 8 }
      .sortedWith(compareByDescending<String> { newBids.getValue(it).amount }.thenBy { order.indexOf(it) })
    val superCallerId = superCandidates.firstOrNull()

    if (superCallerId != null && !state.fastSuperResolved) {
      val superBid = newBids.getValue(superCallerId).amount
      val done = base.copy(
        callerId = superCallerId,
        withPlayers = superCandidates.drop(1),
        auctionTop = superBid,
        subPhase = BiddingPhase.CONFIRM,
        noSuitConstraint = true,
        waitingFor = superCallerId,
      )
      return EmitResult.Applied(done)
    }
    if (state.fastSuperResolved) {
      val done = base.copy(subPhase = BiddingPhase.DONE, waitingFor = null)
      return EmitResult.Completed(
        done,
        BiddingOutcome(
          trump = base.declaredTrump ?: fixedTrumpFor(state.round),
          callerId = base.callerId,
          withPlayers = base.withPlayers,
          estimates = extractEstimates(newBids),
          dashCallers = emptyList(),
          riskPlayerId = base.lastBidderId,
          leaderId = base.callerId ?: state.firstBidder,
        ),
      )
    }
    // No Super Call: the first bidder of the highest number is Caller;
    // every other seat on that number is With (dealer-order tie-break).
    val highestAmount = state.seats
      .filter { newBids[it]?.type == BidType.TRICKS }
      .maxOfOrNull { newBids.getValue(it).amount } ?: 0
    val highestCandidates = state.seats
      .filter { newBids[it]?.type == BidType.TRICKS && newBids.getValue(it).amount == highestAmount }
      .sortedBy { order.indexOf(it) }
    val fastCallerId = highestCandidates.firstOrNull()
    val fastWith = highestCandidates.drop(1)
    val done = base.copy(
      callerId = fastCallerId,
      withPlayers = fastWith,
      auctionTop = highestAmount,
      subPhase = BiddingPhase.DONE,
      waitingFor = null,
    )
    return EmitResult.Completed(
      done,
      BiddingOutcome(
        trump = base.declaredTrump ?: fixedTrumpFor(state.round),
        callerId = fastCallerId,
        withPlayers = fastWith,
        estimates = extractEstimates(newBids),
        dashCallers = emptyList(),
        riskPlayerId = base.lastBidderId,
        leaderId = fastCallerId ?: state.firstBidder,
      ),
    )
  }

  val done = base.copy(subPhase = BiddingPhase.DONE, waitingFor = null)
  val caller = base.callerId
  return EmitResult.Completed(
    done,
    BiddingOutcome(
      trump = base.declaredTrump ?: Suit.SANS,
      callerId = caller,
      withPlayers = base.withPlayers,
      estimates = extractEstimates(newBids),
      dashCallers = dashCallerIds(base),
      riskPlayerId = base.lastBidderId
        ?: if (caller != null) computeRiskId(state.seats, caller) else null,
      leaderId = caller ?: state.firstBidder,
    ),
  )
}
