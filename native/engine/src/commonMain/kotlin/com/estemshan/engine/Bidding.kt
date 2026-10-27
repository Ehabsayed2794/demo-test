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

/**
 * One seat's bid record. [suit] is the trump in force when the declaration
 * was accepted (A8.1/A7.2): the fixed ladder trump for a fast-round estimate
 * before any Super Call, the confirmed suit for the Super Caller, and the
 * new trump for seats re-declaring after it. Normal-round estimates record
 * the confirmed trump as well, so a declaration always carries its suit.
 */
data class Bid(val type: BidType, val amount: Int = 0, val suit: Suit? = null)

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
  /**
   * All four passed the auction, so the round is cancelled and the next
   * round number is dealt fresh. [doubledMultiplier] is the multiplier that
   * immediately following round is scored at — Normal opens Q51's ladder at
   * ×2 and adds 2 per pass (×2 → ×4 → ×6 → ×8, capped at ×8), while Classic
   * carries no multiplier (1). The name is kept for its existing callers;
   * [state]'s `roundMultiplier` still describes the round just cancelled.
   */
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
  val gameType: GameType = GameType.FULL,
  val actionHistory: List<BidAction>,
  val roundMultiplier: Int,
  /**
   * Scoring ruleset governing this round, stamped by the caller (GameSession
   * owns the match-level value). Defaults to NORMAL so every caller that does
   * not pass one keeps the pre-existing behaviour.
   */
  val scoringMode: ScoringMode = ScoringMode.NORMAL,
)

const val MAX_DASH_CALLS = 2

/** The highest number of tricks a seat can ever declare (the 13-card suit). */
const val MAX_TRICKS = 13

val DEFAULT_SEATS: List<String> = listOf("p1", "p2", "p3", "p4")

private val FIXED_SUITS: List<Suit> = listOf(
  Suit.SANS, Suit.SPADES, Suit.HEARTS, Suit.DIAMONDS, Suit.CLUBS,
)

/**
 * GM1/GM8: the one shared model for a match's shape. MINI is FULL with two
 * numbers changed, never a second ruleset — the 52-card deal, auction,
 * estimates, card play, scoring, roles, and win conditions are identical.
 * FULL is byte-for-byte the pre-GameType behaviour (GM2): every caller that
 * keeps the [GameType.FULL] default compiles to exactly what it did before.
 */
enum class GameType(val baseRounds: Int, val firstFastRound: Int, val maxExtensions: Int) {
  FULL(baseRounds = 18, firstFastRound = 14, maxExtensions = 5),
  MINI(baseRounds = 10, firstFastRound = 6, maxExtensions = 1);

  /**
   * GM1: rounds at or past [firstFastRound] are Quick (fixed-trump) rounds.
   * FULL keeps the legacy `>= 14` boundary; MINI starts at 6.
   */
  fun isFastRound(round: Int): Boolean = round >= firstFastRound

  /**
   * The extension window — FULL 14-18, MINI 6-10 (GM2/GM3). Rounds past
   * [baseRounds] repeat the trump ladder but can never trigger an extension.
   */
  fun isExtensionRound(round: Int): Boolean = round in firstFastRound..baseRounds

  /**
   * The fixed trump ladder for a Quick round. [floorMod] is
   * load-bearing: Kotlin's `%` keeps the dividend's sign, so the
   * pre-GameType indexing (`(round - 14) % FIXED_SUITS.size`) threw
   * IndexOutOfBoundsException on any round below the ladder's start —
   * MINI's round 6 hit `FIXED_SUITS[-3]`. floorMod maps both directions
   * through the ladder (FULL 14→Sans … 19→Sans; MINI 6→Sans … 11→Sans)
   * and is numerically identical to `%` for FULL's round >= 14 inputs, so
   * the [BiddingTest] golden cases are unchanged.
   */
  fun fixedTrumpFor(round: Int): Suit =
    FIXED_SUITS[floorMod(round - firstFastRound, FIXED_SUITS.size)]

  /**
   * Folds a negative remainder positive. Kotlin's `%` keeps the dividend's
   * sign, so this is the identity java.lang.Math.floorMod implements;
   * kotlin.math has no floorMod and the engine now compiles to JS/Node as
   * well as JVM (ADR 0002), so the two-line form lives here.
   */
  private fun floorMod(x: Int, y: Int): Int = ((x % y) + y) % y
}

/** Legacy parameterized form — delegates to [GameType.isFastRound]. */
fun isFastRound(roundNumber: Int, gameType: GameType = GameType.FULL): Boolean =
  gameType.isFastRound(roundNumber)

/** Legacy parameterized form — delegates to [GameType.fixedTrumpFor]. */
fun fixedTrumpFor(roundNumber: Int, gameType: GameType = GameType.FULL): Suit =
  gameType.fixedTrumpFor(roundNumber)

fun initNormalRound(
  round: Int,
  dealer: String,
  seats: List<String> = DEFAULT_SEATS,
  multiplier: Int = 1,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
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
  gameType = gameType,
  actionHistory = emptyList(),
  roundMultiplier = multiplier,
  scoringMode = scoringMode,
)

fun initFastRound(
  round: Int,
  dealer: String,
  seats: List<String> = DEFAULT_SEATS,
  multiplier: Int = 1,
  gameType: GameType = GameType.FULL,
  scoringMode: ScoringMode = ScoringMode.NORMAL,
): BiddingState {
  val fixedTrump = fixedTrumpFor(round, gameType)
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
    gameType = gameType,
    actionHistory = emptyList(),
    roundMultiplier = multiplier,
    scoringMode = scoringMode,
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

/**
 * Suit-aware floor (E2.2 / T-12): the last number [pId] bid in the Caller's
 * winning suit [winningSuit]. A later higher bid in another suit does not
 * raise it, and a seat that never bid that suit has no floor at all.
 */
fun withFloorFor(history: List<BidAction>, pId: String, winningSuit: Suit): Int? =
  history.filter {
    it.actionType == "BID" && it.playerId == pId && it.suit == winningSuit
  }.lastOrNull()?.value

/**
 * With-floor legality. [winningSuit] narrows the floor to the Caller's
 * winning suit; null (a Dash round, or a hand-built state without one) falls
 * back to the suit-blind floor so legacy behaviour is unchanged.
 */
fun estimateBelowWithFloor(
  history: List<BidAction>,
  pId: String,
  tricks: Int,
  withPlayers: List<String>,
  winningSuit: Suit?,
): Boolean {
  if (!withPlayers.contains(pId)) return false
  val floor = if (winningSuit == null) {
    withFloorFor(history, pId)
  } else {
    withFloorFor(history, pId, winningSuit)
  } ?: return false
  return tricks < floor
}

/** Suit-blind form kept for the app/bot callers; delegates with a null suit. */
fun estimateBelowWithFloor(
  history: List<BidAction>,
  pId: String,
  tricks: Int,
  withPlayers: List<String>,
): Boolean = estimateBelowWithFloor(history, pId, tricks, withPlayers, null)

/** The floor the "Min is …" rejection shows — suit-aware whenever the state carries the winning suit. */
private fun floorShownInRejection(
  history: List<BidAction>,
  pId: String,
  winningSuit: Suit?,
): Int? =
  if (winningSuit == null) withFloorFor(history, pId) else withFloorFor(history, pId, winningSuit)

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

/**
 * Estimation Jump-In suit gate (rules §2.2.1a): the estimate's recorded suit
 * is [BiddingState.declaredTrump]; the Caller's winning suit is
 * [BiddingState.auctionSuit]. When both are present they must be EQUAL — a
 * matching number in a different suit is not With.
 *
 * A state with no `auctionSuit` (hand-built or legacy) keeps the number-only
 * rule: the runtime always sets both together at the confirmation, so such a
 * state is not one the engine can produce, and failing closed on it would
 * reject a jump-in the rules allow.
 */
private fun jumpInSuitOk(state: BiddingState): Boolean {
  val callerSuit = state.auctionSuit ?: return true
  val estimateSuit = state.declaredTrump ?: return true
  return callerSuit == estimateSuit
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

/** One seat's final fast-round declaration: number plus the trump in force. */
internal data class FastDeclaration(val playerId: String, val amount: Int, val suit: Suit)

/** Result of [resolveFastCall]; [withPlayers] follows declaration order. */
internal data class FastCall(val callerId: String?, val withPlayers: List<String>)

/**
 * A8.1/A7.2 fast-round precedence, in this order:
 * 1. a higher number beats a lower one whatever the suit;
 * 2. at an equal number the stronger suit wins (Sans 5 → Clubs 1), so seat
 *    order can never decide a different-suit tie;
 * 3. only an exact number-and-suit duplicate falls back to declaration
 *    order: the first declaration is Caller and every later exact duplicate
 *    is With. A seat at the winning number in a weaker suit is NOT With.
 *
 * [declarations] MUST be in declaration order. Pure and total so the rule is
 * unit-testable even where the state machine cannot reach a
 * different-suit tie (every pre-Super-Call fast estimate carries the fixed
 * trump; see KNOWN RISKS in the S4 handoff).
 */
internal fun resolveFastCall(declarations: List<FastDeclaration>): FastCall {
  if (declarations.isEmpty()) return FastCall(null, emptyList())
  val topAmount = declarations.maxOf { it.amount }
  val topStrength = declarations
    .filter { it.amount == topAmount }
    .maxOf { it.suit.strength }
  val exact = declarations.filter { it.amount == topAmount && it.suit.strength == topStrength }
  return FastCall(
    callerId = exact.first().playerId,
    withPlayers = exact.drop(1).map { it.playerId }.distinct(),
  )
}

/**
 * A6.10/A7.2 final With set for a Super Call: the exact winning number under
 * the FINAL trump, excluding the locked Super Caller. A seat that kept an
 * equal number declared under the replaced trump is not With.
 */
private fun fastWithAtFinalCall(
  state: BiddingState,
  bids: Map<String, Bid>,
  callerId: String?,
  finalNumber: Int,
  finalSuit: Suit?,
): List<String> =
  biddingOrder(state).filter { seat ->
    seat != callerId && bids[seat]?.let {
      it.type == BidType.TRICKS && it.amount == finalNumber && it.suit == finalSuit
    } == true
  }

/**
 * The round's TRICKS declarations in declaration order. Fast estimates are
 * taken around the table from the dealer and CONFIRM replaces a seat's own
 * record, so bidding order is the declaration order here. [minAmount] narrows
 * the list to Super Call candidates (8+).
 */
private fun fastDeclarations(
  state: BiddingState,
  bids: Map<String, Bid>,
  minAmount: Int,
): List<FastDeclaration> =
  biddingOrder(state).mapNotNull { seat ->
    val bid = bids[seat]
    if (bid != null && bid.type == BidType.TRICKS && bid.amount >= minAmount) {
      FastDeclaration(
        seat,
        bid.amount,
        // Fast-round records always carry a suit; the fallback only protects
        // a hand-built legacy state from a silent null.
        bid.suit ?: state.declaredTrump ?: fixedTrumpFor(state.round, state.gameType),
      )
    } else {
      null
    }
  }

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
      // The absolute 13-trick maximum holds in EVERY round, fast included:
      // the fast exemption only drops the Caller-relative Call Cap.
      if (intent.tricks > MAX_TRICKS) {
        return Legality(false, "Max is $MAX_TRICKS")
      }
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
      // Fast rounds have no Call Cap (rules §2.3, T-24): the Caller's number
      // is not a ceiling there, so a seat may re-declare above the confirmed
      // call — that is the repeated Super Call of T-09.
      val cap = if (state.fastRound) MAX_TRICKS else state.auctionTop
      if (estimateExceedsCap(intent.tricks, cap)) {
        return Legality(
          false,
          if (state.fastRound) "Max is $MAX_TRICKS" else "Max is $cap (Caller's cap)",
        )
      }
      if (estimateBelowWithFloor(state.actionHistory, intent.playerId, intent.tricks, state.withPlayers, state.auctionSuit)) {
        val floor = floorShownInRejection(state.actionHistory, intent.playerId, state.auctionSuit)
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

  // Rotate from the dealer, matching biddingOrder(): the dash phase starts at
  // firstBidder (the dealer) and rotates, so the auction must open on the
  // dealer too — or the first still-active seat after them. Filtering
  // state.seats directly opened on seats[0] regardless of the dealer, which
  // mis-seated the auction opener in every round after the first, since
  // Session.rotateDealer advances the dealer each round.
  val active = biddingOrder(state).filter { newBids[it]?.type != BidType.DASHCALL }
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
    // General Pass cancels this round; Session.nextRound() then bumps the
    // number and deals fresh. This value is what the immediately following
    // round is scored at: Normal walks Q51's ladder x2 -> x4 -> x6 -> x8
    // (+2 per pass, capped at x8); Classic carries no multiplier.
    // roundMultiplier itself stays put — it describes the cancelled round.
    val nextMultiplier = when {
      state.scoringMode == ScoringMode.CLASSIC -> 1
      // x1 is the "no multiplier" entry, so the first pass opens the
      // ladder at x2 rather than x1+2; every later pass adds 2.
      state.roundMultiplier == 1 -> 2
      else -> minOf(state.roundMultiplier + 2, 8)
    }
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
      nextMultiplier,
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
  // The absolute 13-trick maximum holds in EVERY round, fast included: the
  // fast exemption only drops the Caller-relative Call Cap.
  if (t > MAX_TRICKS) {
    return EmitResult.Rejected("Max is $MAX_TRICKS")
  }
  if (bidBelowWinningCall(t, state.auctionTop)) {
    return EmitResult.Rejected("Can't lower your winning call")
  }
  if (confirmSuitTooWeak(t, s, state.auctionTop, state.auctionSuit, state.noSuitConstraint)) {
    return EmitResult.Rejected("Same number needs an equal or stronger suit")
  }

  val newBids = state.bids + (intent.playerId to Bid(BidType.TRICKS, t, s))
  val history = state.actionHistory + BidAction(intent.playerId, "CONFIRM_TRUMP", t, s)

  if (state.noSuitConstraint) {
    // Fast-round Super Call: the replacement trump wipes EVERY seat except
    // the Super Caller's own record (T-09). Every other seat re-declares,
    // counter-clockwise from the caller, so the round can never complete on
    // the confirm — not even when the caller is first in the bidding order.
    val wiped = newBids.filterKeys { it == intent.playerId }
    // A6.10/A8.1: With is the exact final number under the FINAL trump. The
    // provisional pre-confirm set (>=8 declarers) is discarded here and again
    // at completion, after the wiped seats have re-declared.
    val finalWith = fastWithAtFinalCall(state, wiped, intent.playerId, t, s)
    return EmitResult.Applied(
      state.copy(
        auctionTop = t,
        auctionSuit = s,
        declaredTrump = s,
        bids = wiped,
        withPlayers = finalWith,
        callerId = intent.playerId,
        subPhase = BiddingPhase.ESTIMATES,
        noSuitConstraint = false,
        fastSuperResolved = true,
        waitingFor = nextSeat(state.seats, intent.playerId),
        lastBidderId = null,
        actionHistory = history,
      ),
    )
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
  // Fast rounds have no Call Cap (rules §2.3, T-24) — see canSubmit.
  val cap = if (state.fastRound) MAX_TRICKS else state.auctionTop

  if (estimateExceedsCap(intent.tricks, cap)) {
    return EmitResult.Rejected(
      if (state.fastRound) "Max is $MAX_TRICKS" else "Max is $cap (Caller's cap)",
    )
  }
  if (estimateBelowWithFloor(state.actionHistory, pId, intent.tricks, state.withPlayers, state.auctionSuit)) {
    val floor = floorShownInRejection(state.actionHistory, pId, state.auctionSuit)
    return EmitResult.Rejected("Min is $floor (your own With bid)")
  }
  if (estimateIsForbidden13(state, pId, intent.tricks)) {
    val forbidden = forbiddenEstimateFor(state, pId)
    return EmitResult.Rejected("Can't pick $forbidden — totals 13")
  }

  val record = if (intent.tricks == 0) {
    Bid(BidType.DASH, 0, state.declaredTrump)
  } else {
    Bid(BidType.TRICKS, intent.tricks, state.declaredTrump)
  }
  val newBids = state.bids + (pId to record)
  var withPlayers = state.withPlayers.toMutableList()

  // Estimation Jump-In (rules §2.2.1a, normal rounds): any non-caller whose
  // final estimate matches BOTH the Caller's number and suit becomes With.
  // Open to anyone, including a passer. The estimate's suit is `declaredTrump`;
  // the Caller's winning suit is `auctionSuit`. When both are present they must
  // be equal; when `auctionSuit` is null (hand-built / legacy state) the suit
  // gate is dropped and number-only matching is kept.
  if (!state.fastRound && pId != state.callerId && intent.tricks == state.auctionTop &&
    !withPlayers.contains(pId) && jumpInSuitOk(state)
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

  val base = state.copy(
    bids = newBids,
    withPlayers = withPlayers,
    lastBidderId = pId,
    actionHistory = state.actionHistory + BidAction(pId, "ESTIMATE", intent.tricks, null),
  )

  if (state.fastRound) {
    // A declaration above the current call re-opens the Super Call (T-09):
    // the winner of the higher set becomes the new Super Caller and every
    // other seat re-declares under the new trump. This is checked BEFORE the
    // completion branch, so a higher call never ends the round.
    val higher = fastDeclarations(state, newBids, 8).filter { it.amount > state.auctionTop }
    if (higher.isNotEmpty()) {
      val higherCall = resolveFastCall(higher)
      val higherCallerId = higherCall.callerId
      if (higherCallerId != null) {
        val higherBid = newBids.getValue(higherCallerId).amount
        val reopened = base.copy(
          callerId = higherCallerId,
          // Provisional: the exact duplicates among the higher declarations.
          // The authoritative set is recomputed once the new Super Call has
          // chosen the final trump.
          withPlayers = higherCall.withPlayers,
          auctionTop = higherBid,
          subPhase = BiddingPhase.CONFIRM,
          noSuitConstraint = true,
          // The current auctionTop is not confirmed yet: only this call's own
          // confirm sets the flag back to true.
          fastSuperResolved = false,
          waitingFor = higherCallerId,
        )
        return EmitResult.Applied(reopened)
      }
    }
    // A6.10/A7.2: the >=8 Super Call is decided by number first, then the
    // suit hierarchy. Declaration order is consulted only for exact
    // number-and-suit duplicates (A8.1), never for a different-suit tie.
    val superCall = resolveFastCall(fastDeclarations(state, newBids, 8))
    val superCallerId = superCall.callerId

    if (superCallerId != null && !state.fastSuperResolved) {
      val superBid = newBids.getValue(superCallerId).amount
      val done = base.copy(
        callerId = superCallerId,
        // Provisional: the exact duplicates among the 8+ declarations. The
        // authoritative set is recomputed after the Super Call chooses the
        // final trump, so a bare 8+ is never With.
        withPlayers = superCall.withPlayers,
        auctionTop = superBid,
        subPhase = BiddingPhase.CONFIRM,
        noSuitConstraint = true,
        waitingFor = superCallerId,
      )
      return EmitResult.Applied(done)
    }
    if (state.fastSuperResolved) {
      // The Super Caller is locked (A6.10); With is the final number AND the
      // final trump. This is recomputed here because the pre-confirm
      // `>=8` set is not the answer once seats re-declared.
      val finalWith = fastWithAtFinalCall(
        state, newBids, base.callerId, base.auctionTop, base.declaredTrump,
      )
      val done = base.copy(withPlayers = finalWith, subPhase = BiddingPhase.DONE, waitingFor = null)
      return EmitResult.Completed(
        done,
        BiddingOutcome(
          trump = base.declaredTrump ?: fixedTrumpFor(state.round, state.gameType),
          callerId = base.callerId,
          withPlayers = finalWith,
          estimates = extractEstimates(newBids),
          dashCallers = emptyList(),
          riskPlayerId = base.lastBidderId,
          leaderId = base.callerId ?: state.firstBidder,
        ),
      )
    }
    // No Super Call: A8.1/A7.2 resolve Caller/With from the declared number
    // AND suit. Declaration order decides only exact duplicates; a higher
    // number wins whatever the suit, and an equal number is won by the
    // stronger suit.
    val declarations = fastDeclarations(state, newBids, 0)
    val call = resolveFastCall(declarations)
    val highestAmount = declarations.maxOfOrNull { it.amount } ?: 0
    val fastCallerId = call.callerId
    val fastWith = call.withPlayers
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
        trump = base.declaredTrump ?: fixedTrumpFor(state.round, state.gameType),
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
