package com.estemshan.services.model

import com.estemshan.engine.Card
import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.RANKS
import com.estemshan.engine.Rank
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier

/**
 * Stored card shape: { suit, rank: { v, s } }. Mirrors
 * design-ui/match-service.js's isValidGenericCardValue() exactly — only
 * suit + rank.v/s are persisted; id/owner/played/displayName/value are
 * engine-internal and derived client-side.
 */
data class StoredCard(val suit: String, val rank: StoredRank) {

  fun toEngineCard(): Card? {
    val engineSuit = Suit.entries.firstOrNull { it.name == suit } ?: return null
    val engineRank = RANKS.firstOrNull { it.v == rank.v } ?: return null
    return Card(engineSuit, engineRank)
  }

  fun toFields(): Map<String, Any?> = mapOf(
    "suit" to suit,
    "rank" to mapOf("v" to rank.v, "s" to rank.s),
  )

  companion object {
    fun fromEngine(card: Card): StoredCard =
      StoredCard(card.suit.name, StoredRank(card.rank.v, card.rank.s))

    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Any?): StoredCard? {
      val map = fields as? Map<String, Any?> ?: return null
      val suit = map["suit"] as? String ?: return null
      val rankMap = map["rank"] as? Map<String, Any?> ?: return null
      val v = (rankMap["v"] as? Long)?.toInt() ?: return null
      val s = rankMap["s"] as? String ?: rankForValue(v)
      return StoredCard(suit, StoredRank(v, s))
    }
  }
}

data class StoredRank(val v: Int, val s: String)

private fun rankForValue(v: Int): String = RANKS.firstOrNull { it.v == v }?.s ?: ""

/**
 * One entry of the per-round cardLog: { seatId, card, round }. Append-only
 * within a round; round-tagged so the adapter's replay can tell which
 * window an entry belongs to (match-service.js:1134).
 */
data class CardLogEntry(val seatId: String, val card: StoredCard, val round: Int) {

  fun toFields(): Map<String, Any?> = mapOf(
    "seatId" to seatId,
    "card" to card.toFields(),
    "round" to round,
  )

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Any?): CardLogEntry? {
      val map = fields as? Map<String, Any?> ?: return null
      val seatId = map["seatId"] as? String ?: return null
      val card = StoredCard.fromFields(map["card"]) ?: return null
      val round = roundOf(map["round"]) ?: return null
      return CardLogEntry(seatId, card, round)
    }
  }
}

/**
 * The CALLER's action shape — what submitBiddingAction(matchId, action)
 * receives. Deliberately has NO `round`: the round is stamped from the
 * FRESH in-transaction document's own currentRound (never the caller's
 * local, possibly-stale round number) when [toLogEntry] builds the
 * persisted entry. Mirrors match-service.js's own split between the raw
 * `action` parameter and buildBiddingLogEntry().
 */
data class BiddingActionInput(
  val actionType: String,
  val declaredDashCall: Boolean? = null,
  val isPass: Boolean? = null,
  val tricks: Int? = null,
  val suit: String? = null,
) {

  /** Stamps the authoritative round and drops nothing the type omits. */
  fun toLogEntry(seatId: String, round: Int): BiddingLogEntry =
    BiddingLogEntry(
      seatId = seatId,
      actionType = actionType,
      declaredDashCall = declaredDashCall,
      isPass = isPass,
      tricks = tricks,
      suit = suit,
      round = round,
    )
}

/**
 * One entry of the per-round biddingLog: { seatId, actionType, ...,
 * round }. actionType IS the engine's own intent type string, reused
 * verbatim (match-service.js:1275). Only the fields the action type
 * actually carries are stored — never the raw caller object.
 */
data class BiddingLogEntry(
  val seatId: String,
  val actionType: String,
  val declaredDashCall: Boolean? = null,
  val isPass: Boolean? = null,
  val tricks: Int? = null,
  val suit: String? = null,
  val round: Int,
) {

  fun toFields(): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["seatId"] = seatId
    out["actionType"] = actionType
    when (actionType) {
      ACTION_DASH_CALL -> out["declaredDashCall"] = declaredDashCall
      ACTION_AUCTION_BID -> {
        out["isPass"] = isPass == true
        if (isPass != true) {
          out["tricks"] = tricks
          out["suit"] = suit
        }
      }
      ACTION_CONFIRM_CALL -> {
        out["tricks"] = tricks
        out["suit"] = suit
      }
    }
    out["round"] = round
    return out
  }

  companion object {
    const val ACTION_DASH_CALL = "SubmitDashCallDecision"
    const val ACTION_AUCTION_BID = "SubmitAuctionBid"
    const val ACTION_CONFIRM_CALL = "SubmitConfirmCall"

    val VALID_ACTION_TYPES = listOf(
      ACTION_DASH_CALL, ACTION_AUCTION_BID, ACTION_CONFIRM_CALL,
    )

    val VALID_SUITS = Suit.entries.map { it.name }

    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Any?): BiddingLogEntry? {
      val map = fields as? Map<String, Any?> ?: return null
      val seatId = map["seatId"] as? String ?: return null
      val actionType = map["actionType"] as? String ?: return null
      if (actionType !in VALID_ACTION_TYPES) return null
      val round = roundOf(map["round"]) ?: return null
      return BiddingLogEntry(
        seatId = seatId,
        actionType = actionType,
        declaredDashCall = map["declaredDashCall"] as? Boolean,
        isPass = map["isPass"] as? Boolean,
        tricks = (map["tricks"] as? Long)?.toInt(),
        suit = map["suit"] as? String,
        round = round,
      )
    }
  }
}

/** matches/{id}.gameState — the authoritative deal marker. */
data class GameState(val initialized: Boolean, val dealtRound: Int) {

  fun toFields(): Map<String, Any?> = mapOf(
    "initialized" to initialized,
    "dealtRound" to dealtRound,
  )

  companion object {
    val NOT_DEALT = GameState(initialized = false, dealtRound = 0)

    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Any?): GameState {
      val map = fields as? Map<String, Any?> ?: return NOT_DEALT
      return GameState(
        initialized = map["initialized"] as? Boolean ?: false,
        dealtRound = (map["dealtRound"] as? Long)?.toInt() ?: 0,
      )
    }
  }
}

/**
 * Parsed view of matches/{matchId}. Field names, nesting and nullability
 * mirror design-ui/match-service.js buildInitialMatchDoc() byte for
 * byte — any deviation is denied by the UNCHANGED firestore.rules
 * (docs/specs/03-transactions.md).
 */
data class MatchDoc(
  val roomId: String,
  val players: List<String>,
  val status: String,
  val currentRound: Int,
  val maxRounds: Int,
  val extendedRounds: List<Int>,
  val dealer: String,
  val turn: String?,
  val seats: Map<String, String>,
  val version: Int,
  val biddingOpen: Boolean,
  val bids: Map<String, Int?>,
  val lastBidSeat: String?,
  val cardLog: List<CardLogEntry>,
  val lastCardSeat: String?,
  val cardPhase: String?,
  val biddingLog: List<BiddingLogEntry>,
  val gameState: GameState,
  val rematchOfMatchId: String? = null,
  val winnerIds: List<String>? = null,
  val finalScores: Map<String, Int>? = null,
  val completedRound: Int? = null,
  /**
   * S66 (E7): the match's game type + calculation mode. Absent in a doc
   * written before S66 ⇒ FULL / NORMAL (no migration). Serialized as the
   * enum names ('FULL'/'MINI', 'NORMAL'/'CLASSIC') — the values S67's
   * rules allowlist.
   */
  val gameType: GameType = GameType.FULL,
  val scoringMode: ScoringMode = ScoringMode.NORMAL,
  /**
   * S43 (E6b, RD28 final): the authority key gating voice, Vote Kick,
   * disconnect/pause, settlement, and Ranked statistics. Absent in a doc
   * written before S43 ⇒ ROOM (no migration). Serialized as the enum
   * name ('ROOM' | 'RANKED' | 'UNRANKED') — the values S43's rules
   * allowlist. Matches never carry rankDown: that flag lives on rooms.
   */
  val mode: MatchMode = MatchMode.ROOM,
  /**
   * RD21: the match's bot configuration — skill tier, style, and the
   * per-decision timer. Inherited from the room at startMatch and from the
   * old match at createRematchMatch, never client-supplied on a match. Absent
   * in a doc written before the trio's persistence ⇒ MEDIUM / BALANCED /
   * [DEFAULT_DECISION_TIMER_SECONDS] (no migration). Serialized as the enum
   * names — the values firestore.rules' allowlist accepts.
   */
  val botTier: BotTier = BotTier.MEDIUM,
  val botPersonality: BotPersonality = BotPersonality.BALANCED,
  val decisionTimerSeconds: Int = DEFAULT_DECISION_TIMER_SECONDS,
) {

  /** The match is over, terminal: status never moves complete → anything. */
  val isComplete: Boolean get() = status == STATUS_COMPLETE

  /**
   * The Round-1 opening window (Sprint L): a fresh match is created with
   * turn=dealer, and the rules' isValidOpeningTurnPublication() lets the
   * engine-selected opening leader publish itself exactly once from this
   * shape — round 1, dealer-held placeholder turn, no cards played yet,
   * bidding already underway. submitCard() skips assertLocalTurn() here
   * because the placeholder turn is not a real turn; the local engine's
   * preview remains the gameplay authority for who actually opens.
   */
  val isRoundOneOpeningWindow: Boolean
    get() = currentRound == 1 && cardPhase == null && turn != null && turn == dealer &&
      cardLog.isEmpty() && biddingLog.isNotEmpty()

  fun uidToSeat(uid: String): String? =
    seats.entries.firstOrNull { it.value == uid }?.key

  fun seatToUid(seatId: String): String? = seats[seatId]

  fun isPlayer(uid: String): Boolean = uid in players

  /** Round-tagged card count — the structural round-completion check
   *  (52 == 13 tricks * 4 seats) used by advance/endMatch. */
  fun roundCardCount(round: Int): Int = cardLog.count { it.round == round }

  fun roundCards(round: Int): List<CardLogEntry> = cardLog.filter { it.round == round }

  fun roundBids(round: Int): List<BiddingLogEntry> = biddingLog.filter { it.round == round }

  companion object {
    const val STATUS_STARTING = "starting"
    const val STATUS_COMPLETE = "complete"

    const val CARD_PHASE_PLAY = "PLAY"
    const val CARD_PHASE_RESOLVING = "RESOLVING"

    /** Fresh-round reset value for turn/cardPhase/bids — the same
     *  "no value yet" convention buildInitialMatchDoc() establishes. */
    val ROUND_RESET_TURN: String? = null
    val ROUND_RESET_CARD_PHASE: String? = null

    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Map<String, Any?>): MatchDoc? {
      val roomId = fields["roomId"] as? String ?: return null
      val players = (fields["players"] as? List<*>)?.mapNotNull { it as? String }
        ?: emptyList()
      val seats = (fields["seats"] as? Map<*, *>)?.entries
        ?.mapNotNull { (k, v) ->
          val key = k as? String ?: return@mapNotNull null
          val value = v as? String ?: return@mapNotNull null
          key to value
        }?.toMap() ?: emptyMap()
      return MatchDoc(
        roomId = roomId,
        players = players,
        status = fields["status"] as? String ?: STATUS_STARTING,
        currentRound = (fields["currentRound"] as? Long)?.toInt() ?: 1,
        maxRounds = (fields["maxRounds"] as? Long)?.toInt() ?: DEFAULT_MAX_ROUNDS,
        extendedRounds = (fields["extendedRounds"] as? List<*>)
          ?.mapNotNull { (it as? Long)?.toInt() } ?: emptyList(),
        dealer = fields["dealer"] as? String ?: return null,
        turn = fields["turn"] as? String,
        seats = seats,
        version = (fields["version"] as? Long)?.toInt() ?: 0,
        biddingOpen = fields["biddingOpen"] as? Boolean ?: false,
        bids = (fields["bids"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
          val key = k as? String ?: return@mapNotNull null
          key to (v as? Long)?.toInt()
        }?.toMap() ?: emptyMap(),
        lastBidSeat = fields["lastBidSeat"] as? String,
        cardLog = (fields["cardLog"] as? List<*>)
          ?.mapNotNull { CardLogEntry.fromFields(it) } ?: emptyList(),
        lastCardSeat = fields["lastCardSeat"] as? String,
        cardPhase = fields["cardPhase"] as? String,
        biddingLog = (fields["biddingLog"] as? List<*>)
          ?.mapNotNull { BiddingLogEntry.fromFields(it) } ?: emptyList(),
        gameState = GameState.fromFields(fields["gameState"]),
        rematchOfMatchId = fields["rematchOfMatchId"] as? String,
        winnerIds = (fields["winnerIds"] as? List<*>)?.mapNotNull { it as? String },
        finalScores = (fields["finalScores"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
          val key = k as? String ?: return@mapNotNull null
          val value = (v as? Long)?.toInt() ?: return@mapNotNull null
          key to value
        }?.toMap(),
        completedRound = (fields["completedRound"] as? Long)?.toInt(),
        gameType = RoomDoc.parseGameType(fields["gameType"]),
        scoringMode = RoomDoc.parseScoringMode(fields["scoringMode"]),
        mode = RoomDoc.parseMatchMode(fields["mode"]),
        botTier = RoomDoc.parseBotTier(fields["botTier"]),
        botPersonality = RoomDoc.parseBotPersonality(fields["botPersonality"]),
        decisionTimerSeconds = RoomDoc.parseDecisionTimerSeconds(fields["decisionTimerSeconds"]),
      )
    }
  }
}

/** Canonical seat ids, positional assignment only (SeatIdentityModel.md). */
val SEAT_IDS: List<String> = listOf("p1", "p2", "p3", "p4")

const val DEFAULT_MAX_ROUNDS = 18

/** Rapid Rounds window (rules §5): the only rounds eligible to extend. */
const val RAPID_ROUND_MIN = 14
const val RAPID_ROUND_MAX = 18

const val MAX_BID_VALUE = 13
const val MIN_RANK_VALUE = 2
const val MAX_RANK_VALUE = 14

const val REMATCH_VOTE_DURATION_SECONDS = 30L

/** RD21's Decision Timer default (seconds) — the value a doc written before
 *  the trio's persistence counts as, and the S36 Create Game screen's
 *  pre-fill. One owner so the parser default and the UI default cannot drift. */
const val DEFAULT_DECISION_TIMER_SECONDS = 15

/** The bounds of RD21's Decision Timer group (5 / 10 / 15 / 20 s). A value
 *  outside this window is denied by firestore.rules' isValidDecisionTimer()
 *  and reads back as the default, so a client can never store a timer the
 *  parser would silently read as something else. */
const val MIN_DECISION_TIMER_SECONDS = 5
const val MAX_DECISION_TIMER_SECONDS = 20

/** matches/{id}/roundArchive/{round} — write-once, deterministic id. */
data class RoundArchiveDoc(
  val round: Int,
  val matchId: String,
  val cardLog: List<CardLogEntry>,
  val biddingLog: List<BiddingLogEntry>,
  /**
   * S50 archive fix (E6b): the round's Final Estimates snapshot, taken from
   * the parent's transient bids/ at archive time (advance/endMatch). Bids/
   * resets every round and is never otherwise persisted — without this
   * snapshot the settlement callable cannot recompute the authoritative
   * result (RD12). Dash-Call seats never submit an estimate and are
   * legitimately absent; settlement derives them from the round's
   * biddingLog instead. Absent on archives written before this field
   * existed — those matches fail closed at settlement (HISTORY_INCOMPLETE).
   */
  val estimates: Map<String, Int> = emptyMap(),
) {

  fun toFields(): Map<String, Any?> = mapOf(
    "round" to round,
    "matchId" to matchId,
    "cardLog" to cardLog.map { it.toFields() },
    "biddingLog" to biddingLog.map { it.toFields() },
    "estimates" to estimates.toMap(),
  )
}

/** matches/{id}/hands/{seatId} — current hand only, overwritten per round. */
data class HandDoc(val seatId: String, val round: Int, val cards: List<StoredCard>) {

  /** version == the round it belongs to: this doc is overwritten each
   *  round, never appended (Architecture Gate Decision 4). */
  fun toFields(): Map<String, Any?> = mapOf(
    "seatId" to seatId,
    "round" to round,
    "cards" to cards.map { it.toFields() },
    "version" to round,
  )

  companion object {
    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Any?): HandDoc? {
      val map = fields as? Map<String, Any?> ?: return null
      val seatId = map["seatId"] as? String ?: return null
      val round = roundOf(map["round"]) ?: return null
      val cards = (map["cards"] as? List<*>)?.mapNotNull { StoredCard.fromFields(it) }
        ?: return null
      return HandDoc(seatId, round, cards)
    }
  }
}

/** matches/{id}/rematchVote/current — the post-match vote machine. */
data class VoteDoc(
  val matchId: String,
  val seats: Map<String, String>,
  val votes: Map<String, String?>,
  val status: String,
  val newMatchId: String?,
  val version: Int,
  /** The server timestamp the deadline is derived from. Null while the
   *  serverTimestamp() sentinel is still pending on a local cache — a
   *  deadline is never guessed from a missing value. */
  val createdAtMillis: Long? = null,
  /** The serverTimestamp() sentinel at create time; null on later writes
   *  (firestore.rules' deadline check reads only the stored value). */
  val createdAt: Any? = null,
) {

  fun toFields(): Map<String, Any?> = mapOf(
    "matchId" to matchId,
    "seats" to seats,
    "votes" to votes,
    "status" to status,
    "newMatchId" to newMatchId,
    "version" to version,
    // firestore.rules derives the 30s deadline from createdAt +
    // duration.value(30,'s'); the sentinel is written at create time and
    // read back as a real Timestamp on every subsequent read.
    "createdAt" to createdAt,
  )

  /** The acting uid's own seat, resolved from this vote's own parent-
   *  derived seats map — never a client-supplied seat id. */
  fun seatForUid(uid: String): String? =
    seats.entries.firstOrNull { it.value == uid }?.key

  companion object {
    const val STATUS_OPEN = "OPEN"
    const val STATUS_ALL_YES = "ALL_YES"
    const val STATUS_FAILED_NO = "FAILED_NO"
    const val STATUS_FAILED_TIMEOUT = "FAILED_TIMEOUT"
    const val STATUS_NEW_MATCH_CREATED = "NEW_MATCH_CREATED"

    val VOTE_VALUES = listOf("YES", "NO")

    @Suppress("UNCHECKED_CAST")
    fun fromFields(fields: Map<String, Any?>): VoteDoc? {
      val matchId = fields["matchId"] as? String ?: return null
      val seats = (fields["seats"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        val value = v as? String ?: return@mapNotNull null
        key to value
      }?.toMap() ?: emptyMap()
      val votes = (fields["votes"] as? Map<*, *>)?.entries?.mapNotNull { (k, v) ->
        val key = k as? String ?: return@mapNotNull null
        key to (v as? String)
      }?.toMap() ?: emptyMap()
      return VoteDoc(
        matchId = matchId,
        seats = seats,
        votes = votes,
        status = fields["status"] as? String ?: STATUS_OPEN,
        newMatchId = fields["newMatchId"] as? String,
        version = (fields["version"] as? Long)?.toInt() ?: 0,
        createdAt = fields["createdAt"],
        createdAtMillis = (fields["createdAt"] as? com.google.firebase.Timestamp)
          ?.toDate()?.time,
      )
    }
  }
}

/** Firestore stores all integers as 64-bit; reads come back as Long. */
internal fun roundOf(value: Any?): Int? = when (value) {
  is Long -> value.toInt()
  is Int -> value
  else -> null
}
