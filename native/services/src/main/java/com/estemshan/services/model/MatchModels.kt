package com.estemshan.services.model

import com.estemshan.engine.Card
import com.estemshan.engine.DisconnectVotePhase
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.GameType
import com.estemshan.engine.InactiveTakeover
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
  /**
   * S60 (E6b, RD18): seats currently bot-driven. Today only a Vote Kick
   * success writes here (the removed seat stays bot-held permanently);
   * the S39/S41 takeovers will append when they land. Absent in older
   * docs ⇒ no bot seats (no migration).
   */
  val botSeats: List<String> = emptyList(),
  /**
   * S60: uids permanently removed by a passed Vote Kick. A removed uid
   * can never play, vote, or rejoin this match again — every match write
   * path checks [isSeated], and future (re)join paths must too. Absent
   * in older docs ⇒ nobody removed (no migration).
   */
  val removedUids: List<String> = emptyList(),
  /**
   * S61 Stage 2: uids permanently removed by the 15-timeout counter. This
   * is the timeout mechanism's OWN provenance and is deliberately kept
   * separate from [removedUids] (Vote Kick) — the two mechanisms never
   * write each other's fields. Recognition is read-side only: [isRemoved]
   * unions the two lists, and [botControlledSeats] adds these seats' seats
   * to [botSeats], so a timeout-removed seat is bot-driven and excluded
   * from Vote Kick eligibility WITHOUT merging persisted state. Absent in
   * older docs ⇒ nobody timeout-removed (no migration).
   */
  val timeoutRemovedUids: List<String> = emptyList(),
  /**
   * S73 Stage 2 (RD19): seats currently BORROWED by the configured bot for
   * decision-point inactivity, keyed by seat id. Like [timeoutRemovedUids]
   * this is the takeover mechanism's own provenance and no other path writes
   * it — and it is deliberately kept out of the [botSeats]/[botControlledSeats]
   * union, because a borrowed seat is still the player's own seat: they stay
   * seated, stay kickable, keep their vote, and DEC-RD19-04 returns the same
   * seat to them. Parsed fail-closed by [InactiveTakeover.parseAll], so a
   * present but untrustworthy entry reads as no takeover rather than stranding
   * a human out of their seat. Absent in older docs ⇒ nobody borrowed (no
   * migration).
   */
  val inactiveTakeovers: Map<String, InactiveTakeover> = emptyMap(),
  /**
   * S74 (DEC-DISC-01): the disconnect vote's public marker, keyed by seat id
   * (`matches/{id}.disconnectVote`) — the seats the flow currently holds,
   * every phase of it. Like [inactiveTakeovers] this is the mechanism's own
   * provenance and no other path writes it, and like it the seats stay the
   * players' own: a held seat is a BORROWING (reclaimable through the ballot
   * and any paused wait), it is deliberately kept out of the [botSeats]/
   * [botControlledSeats] union, and it stays kickable. Parsed fail-closed by
   * [DisconnectVoteState.parseAll], so a present but untrustworthy entry reads
   * as no flow rather than stranding a human out of their seat. Absent in
   * older docs ⇒ nobody held (no migration).
   */
  val disconnectVote: Map<String, DisconnectVoteState> = emptyMap(),
  /**
   * S74: uids the disconnect flow replaced PERMANENTLY — the waiting ended
   * with no reconnect. This is the flow's OWN provenance list, deliberately
   * separate from [removedUids] (Vote Kick) and [timeoutRemovedUids] (the
   * 15-timeout counter), exactly as those are separate from each other; the
   * charge table (`DisconnectVote.permanentCharge`) is applied to it
   * downstream, so the document records the termination and never the
   * penalty. Recognition is read-side only and deliberately NOT folded into
   * [isRemoved]/[botControlledSeats] in this slice: a replaced seat's RANKED
   * path does not exist at all, and the ROOM/UNRANKED consumption sites land
   * with the flow's UI. Absent in older docs ⇒ nobody replaced (no
   * migration).
   */
  val disconnectReplacedUids: List<String> = emptyList(),
  /**
   * S60 (D8): the 5-round same-target cooldown, verbatim
   * `failedVoteKickCooldown: { targetSeat, blockedUntilRound }`. Single
   * slot by spec. Absent ⇒ no live cooldown (no migration).
   */
  val failedVoteKickCooldown: VoteKickCooldownData? = null,
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

  /**
   * S60 + S61 Stage 2: permanently removed by EITHER mechanism. The union
   * is read-side only — [removedUids] (Vote Kick) and [timeoutRemovedUids]
   * (the 15-timeout counter) stay separate persisted lists and are never
   * written by the other's path. Both make the seat unplayable, so
   * [isSeated] gates on this union rather than on either list alone.
   */
  fun isRemoved(uid: String): Boolean = uid in removedUids || uid in timeoutRemovedUids

  /**
   * S61 Stage 2: seats currently driven by a bot, for EITHER reason — a
   * Vote Kick removal ([botSeats], written by that mechanism) or a seat
   * whose uid was removed by the 15-timeout counter (resolved here from
   * [timeoutRemovedUids] through the RETAINED seats map, so a seat whose
   * uid is no longer "seated" by [isSeated] still maps to its seat id).
   * Read-side union only: no mechanism writes the other's field. This is
   * what makes a timeout-removed seat bot-driven for the timeout resolver
   * and already-excluded from Vote Kick eligibility.
   *
   * S73 (RD19) deliberately does NOT extend this union. An inactivity-borrowed
   * seat is not a removed or replaced one — adding [inactiveTakeoverSeats]
   * here would silently make a still-present player unvotable and unkickable,
   * which is the opposite of DEC-RD19-04. Ask that set separately.
   */
  fun botControlledSeats(): Set<String> =
    botSeats.toSet() + seats.filterValues { it in timeoutRemovedUids }.keys

  /**
   * S73 (RD19): the seats currently borrowed by the configured bot because
   * their owner ignored decision points. Seat ids, so the S39 chip and the
   * resolver's pacing check can each read one set without any union — see
   * [botControlledSeats] for why they are never merged.
   */
  fun inactiveTakeoverSeats(): Set<String> = inactiveTakeovers.keys

  /**
   * S74 (DEC-DISC-01): the seats the disconnect flow is driving RIGHT NOW —
   * `HOLD`, `VOTE`, `WAIT` and `REPLACED` alike, because the seat is
   * bot-played from the instant of detection. Read-side only and never merged
   * into [botControlledSeats], for [inactiveTakeoverSeats]'s exact reason: a
   * held seat is still its owner's seat and must keep its Vote Kick slot.
   */
  fun disconnectHeldSeats(): Set<String> = disconnectVote.keys

  /**
   * S74: the held seats their owner can still take BACK — `HOLD`, `VOTE` or
   * `WAIT`, i.e. the whole time the table waits. A `REPLACED` seat is not
   * reclaimable: the waiting ended with no reconnect, and the seat is the
   * configured bot's for the rest of the match.
   */
  fun disconnectReclaimableSeats(): Set<String> =
    disconnectVote.filterValues { it.phase != DisconnectVotePhase.REPLACED }.keys

  /**
   * S60: seated AND not removed — the gate every match write path
   * enforces. Removal is checked separately from membership so the
   * denial can name it (REMOVED_FROM_MATCH, S40-facing).
   */
  fun isSeated(uid: String): Boolean = isPlayer(uid) && !isRemoved(uid)

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
        botSeats = (fields["botSeats"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        removedUids = (fields["removedUids"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        timeoutRemovedUids = (fields["timeoutRemovedUids"] as? List<*>)
          ?.mapNotNull { it as? String } ?: emptyList(),
        inactiveTakeovers = InactiveTakeover.parseAll(fields["inactiveTakeovers"]),
        disconnectVote = DisconnectVoteState.parseAll(fields["disconnectVote"]),
        disconnectReplacedUids = (fields["disconnectReplacedUids"] as? List<*>)
          ?.mapNotNull { it as? String } ?: emptyList(),
        failedVoteKickCooldown = VoteKickCooldownData.fromFields(fields["failedVoteKickCooldown"]),
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
