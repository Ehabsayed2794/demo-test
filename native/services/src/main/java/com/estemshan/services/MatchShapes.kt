package com.estemshan.services

import com.estemshan.engine.Suit
import com.estemshan.services.model.BiddingActionInput
import com.estemshan.services.model.BiddingLogEntry
import com.estemshan.services.model.CardLogEntry
import com.estemshan.services.model.DEFAULT_MAX_ROUNDS
import com.estemshan.services.model.GameState
import com.estemshan.services.model.MatchDoc
import com.estemshan.services.model.MAX_BID_VALUE
import com.estemshan.services.model.MAX_RANK_VALUE
import com.estemshan.services.model.MIN_RANK_VALUE
import com.estemshan.services.model.RAPID_ROUND_MAX
import com.estemshan.services.model.RAPID_ROUND_MIN
import com.estemshan.services.model.SEAT_IDS
import com.estemshan.services.model.StoredCard
import com.estemshan.services.model.roundOf

/**
 * Pure, I/O-free builders and guards behind MatchService — the exact
 * transaction shapes docs/specs/03-transactions.md pins, factored out so
 * every one is unit-testable without Firestore. The service passes the
 * serverTimestamp() sentinel in; nothing here touches the SDK.
 */

/** Canonical seat assignment: POSITIONAL from players[], never inferred
 *  or reordered (SeatIdentityModel.md "Creation"). A 2-player match gets
 *  exactly p1/p2. */
fun buildSeatMap(players: List<String>): Map<String, String> =
  players.take(SEAT_IDS.size).mapIndexed { i, uid -> SEAT_IDS[i] to uid }.toMap()

/** Fresh-room dealer: the creator when they are seated, else players[0]. */
fun initialDealer(players: List<String>, creator: String?): String? =
  when {
    creator != null && creator in players -> creator
    else -> players.firstOrNull()
  }

/**
 * buildInitialMatchDoc — matches/{matchId} at creation. Every field name,
 * nesting and null value mirrors match-service.js:429-531; a deviation is
 * a rules denial, not a style issue.
 */
fun buildInitialMatchFields(
  roomId: String,
  players: List<String>,
  creator: String?,
  serverTimestamp: Any,
  rematchOfMatchId: String? = null,
): Map<String, Any?> {
  val seats = buildSeatMap(players)
  val dealer = initialDealer(players, creator)
  return buildMatchFields(
    roomId = roomId,
    players = players,
    seats = seats,
    dealer = dealer,
    turn = dealer,
    rematchOfMatchId = rematchOfMatchId,
    serverTimestamp = serverTimestamp,
  )
}

/** Shared shape for a fresh match and a rematch match (seats verbatim). */
private fun buildMatchFields(
  roomId: String,
  players: List<String>,
  seats: Map<String, String>,
  dealer: String?,
  turn: String?,
  serverTimestamp: Any,
  rematchOfMatchId: String?,
): Map<String, Any?> = buildMap {
  put("roomId", roomId)
  if (rematchOfMatchId != null) put("rematchOfMatchId", rematchOfMatchId)
  put("players", players)
  put("status", MatchDoc.STATUS_STARTING)
  put("createdAt", serverTimestamp)
  put("currentRound", 1)
  put("maxRounds", DEFAULT_MAX_ROUNDS)
  put("extendedRounds", emptyList<Int>())
  put("dealer", dealer)
  put("turn", turn)
  put("seats", seats)
  put("version", 1)
  put("biddingOpen", true)
  put("bids", seats.keys.associateWith { null })
  put("lastBidSeat", null)
  put("cardLog", emptyList<CardLogEntry>())
  put("lastCardSeat", null)
  put("cardPhase", null)
  put("biddingLog", emptyList<BiddingLogEntry>())
  put("gameState", GameState.NOT_DEALT.toFields())
}

/**
 * Rematch match doc — seats/players copied VERBATIM from the vote's own
 * seats map (itself copied from the original match), never from a
 * client-supplied list (match-service.js:2371-2404).
 */
fun buildRematchMatchFields(
  roomId: String,
  seats: Map<String, String>,
  oldDealerFallback: String?,
  serverTimestamp: Any,
  rematchOfMatchId: String,
): Map<String, Any?> {
  val players = SEAT_IDS.filter { seats.containsKey(it) }.map { seats.getValue(it) }
  return buildMatchFields(
    roomId = roomId,
    players = players,
    seats = seats,
    dealer = players.firstOrNull() ?: oldDealerFallback,
    turn = players.firstOrNull() ?: oldDealerFallback,
    serverTimestamp = serverTimestamp,
    rematchOfMatchId = rematchOfMatchId,
  )
}

/**
 * Atomic dealer rotation (match-service.js:1549): the next seated player
 * after the current dealer in canonical seat order, wraparound included.
 * null when the dealer is not a seated player.
 */
fun nextDealerUid(seats: Map<String, String>, currentDealer: String?): String? {
  if (currentDealer == null) return null
  val currentIndex = SEAT_IDS.indexOfFirst { seats[it] == currentDealer }
  if (currentIndex < 0) return null
  for (offset in 1..SEAT_IDS.size) {
    val candidate = seats[SEAT_IDS[(currentIndex + offset) % SEAT_IDS.size]]
    if (candidate != null) return candidate
  }
  return null
}

/**
 * Structural self-consistency: the highest-score seat(s) in finalScores
 * must be EXACTLY winnerIds (match-service.js:1784). Does NOT verify the
 * scores are the TRUE outcome — that is beyond the service's boundary by
 * design (wrong-but-consistent scores are accepted, re-accepted).
 */
fun winnerIdsMatchFinalScores(
  finalScores: Map<String, Int>?,
  winnerIds: List<String>?,
  seats: Map<String, String>,
): Boolean {
  if (finalScores.isNullOrEmpty()) return false
  if (winnerIds.isNullOrEmpty()) return false
  val seatIds = seats.keys.toList()
  if (seatIds.isEmpty()) return false
  if (seatIds.sorted() != finalScores.keys.sorted()) return false
  val maxScore = finalScores.values.maxOrNull() ?: return false
  val expectedWinners = seatIds.filter { finalScores.getValue(it) == maxScore }
  return expectedWinners.sorted() == winnerIds.sorted()
}

// ── GENERIC value validation (shape, never gameplay legality) ──────────

/** A finite integer 0..13 — a structural trick-count bound, NOT a bid
 *  rule. Strings/objects/NaN/Infinity all fail; nothing is coerced. */
fun isValidGenericBidValue(bid: Int?): Boolean =
  bid != null && bid in 0..MAX_BID_VALUE

/** A plain card with a real suit key and a rank.v in 2..14. */
fun isValidGenericCardValue(card: StoredCard?): Boolean {
  if (card == null) return false
  if (card.suit !in Suit.entries.map { it.name }) return false
  return card.rank.v in MIN_RANK_VALUE..MAX_RANK_VALUE
}

/**
 * Generic bidding-action SHAPE validation — never legality. Checks the
 * right fields are present and well-typed for the action type; whether
 * the action is legal for this seat right now is BiddingEngine's job
 * (match-service.js:1209). Takes the caller-side [BiddingActionInput]
 * directly: the round is stamped later from the fresh document, so it is
 * not part of this check.
 */
fun isValidGenericBiddingAction(action: BiddingActionInput?): Boolean {
  if (action == null) return false
  if (action.actionType !in BiddingLogEntry.VALID_ACTION_TYPES) return false
  if (action.declaredDashCall != null && action.actionType != BiddingLogEntry.ACTION_DASH_CALL) return false
  if (action.isPass != null && action.actionType != BiddingLogEntry.ACTION_AUCTION_BID) return false
  action.tricks?.let {
    if (it !in 0..MAX_BID_VALUE) return false
  }
  if (action.suit != null && action.suit !in BiddingLogEntry.VALID_SUITS) return false
  return when (action.actionType) {
    BiddingLogEntry.ACTION_DASH_CALL -> action.declaredDashCall != null
    BiddingLogEntry.ACTION_AUCTION_BID ->
      action.isPass != null && (action.isPass || (action.tricks != null && action.suit != null))
    BiddingLogEntry.ACTION_CONFIRM_CALL -> action.tricks != null && action.suit != null
    else -> false
  }
}

/**
 * Rapid Rounds window (rules §5): the ONLY rounds eligible to extend
 * maxRounds, whatever the current ceiling has grown to.
 */
fun isRapidRound(round: Int): Boolean = round in RAPID_ROUND_MIN..RAPID_ROUND_MAX

/**
 * The two values extendMatchRounds() accepts as a qualifying reason
 * (match-service.js:1691). Structural only — neither this service nor
 * firestore.rules can verify the event actually occurred; the caller
 * derives it from the same engine facts every client computes.
 */
val VALID_EXTENSION_REASONS: List<String> = listOf("SUPER_CALL", "SAAYDA")

/** Round-completion threshold: 13 tricks * 4 seats. */
const val ROUND_CARD_TOTAL = 52

/** Reads a server-round-tripped numeric field, or null. */
internal fun numericField(value: Any?): Int? = roundOf(value)
