package com.estemshan.functions

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameType
import com.estemshan.engine.Play
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import com.estemshan.engine.bot.BidBrain
import com.estemshan.engine.bot.PlayBrain
import com.estemshan.engine.emit
import com.estemshan.engine.emitPlay
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.initTable
import com.estemshan.engine.previewPlay
import com.estemshan.engine.resolveTrick
import com.estemshan.engine.trickWinner

/**
 * S61 Stage 1 — the decision-timer authority.
 *
 * RD21/S37 made every human decision timed: Card Play gets
 * `decisionTimerSeconds`, and Dash / Auction / Confirm / Final Estimate get
 * `decisionTimerSeconds + 5` (§0.5-C, S37). S38 makes expiry hand that ONE
 * decision to the **MEDIUM** bot — the same BidBrain/PlayBrain a bot seat
 * uses — and the move is applied exactly as the human's would have been.
 *
 * **Why this lives in the authority layer, not on the acting client.** The
 * decision needs the acting seat's hand, and `hands/{seatId}` is
 * owner-read-only by design (`allow get: if ownsSeat()`), so only the acting
 * player's own app could ever compute it. Resolution would then depend
 * entirely on that one client being foregrounded — the exact failure mode the
 * owner ruled out (backgrounded, disconnected, or terminated). So resolution
 * runs here instead: [FirebaseAdmin]'s admin SDK bypasses firestore.rules the
 * way settlement does, the hand is read server-side, the decision is computed,
 * and only the DECISION is written back. No hand ever crosses the wire to any
 * client — not in the response, not in the doc.
 *
 * **The deadline needs no new field.** Every decision-changing write already
 * stamps `updatedAt` with `FieldValue.serverTimestamp()` (all nine sites in
 * MatchService, plus dealRound). That timestamp is the anchor; the deadline is
 * `updatedAt + duration`, derived exactly the way the rematch vote derives
 * `createdAt + 30s`. Consequently Stage 1 touches no MatchDoc field, no
 * firestore.rules line, and no rules SHA — the existing game flow stays
 * byte-identical, and the doc a client reads is the doc it reads today.
 *
 * **Host-less, like the rematch vote.** Any seated client may trigger the
 * callable when its own derived countdown expires; the transaction re-anchors
 * on the FRESH document, so a player who decided a moment late simply gets
 * NOT_YET_EXPIRED and no write happens. Nothing requires the acting player's
 * process to be alive.
 *
 * Everything here is pure Kotlin over plain parsed maps: no Firestore, no JS
 * SDK. The I/O seam is [TimeoutResolutionDb]/[resolveDecisionTimeout]; this
 * file is fully unit-testable on the JS target with no SDK loaded.
 *
 * Stage 1 deliberately excludes the 15-timeout counter and the S39 inactivity
 * takeover (§0.5-C: only TIMEOUT increments the counter; INACTIVE never does;
 * the two share no state). Those are Stages 2 and 3.
 */
object TimeoutResolution {

  /** Canonical failure reasons. The seam maps these to HttpsError codes. */
  object Reason {
    /** Caller is not a player in this match (permission-denied). */
    const val NOT_MEMBER = "NOT_MEMBER"

    /** The match is not waiting on a timed decision right now (failed-precondition). */
    const val NOT_ACTIVE = "NOT_ACTIVE"

    /** A decision is pending but the deadline has not passed. The success case
     *  for a late or raced trigger — no write, and NOT an error the client
     *  surfaces to the player. */
    const val NOT_YET_EXPIRED = "NOT_YET_EXPIRED"

    /** History needed to reconstruct the engine state is missing or contradicts
     *  the engine (failed-precondition). Fail closed; never invent state. */
    const val HISTORY_UNAVAILABLE = "HISTORY_UNAVAILABLE"
  }

  /**
   * RD21's phase durations (S37): Card Play runs at the configured value;
   * every bidding decision gets five extra seconds. The phase carries the
   * duration so a caller can render the rule legibly (S37's "must be visible").
   */
  enum class DecisionPhase(val offsetSeconds: Int) {
    BIDDING(offsetSeconds = 5),
    CARD(offsetSeconds = 0),
    ;

    /** The full window for [timerSeconds]: base for CARD, base + 5 otherwise. */
    fun durationSeconds(timerSeconds: Int): Int = timerSeconds + offsetSeconds
  }

  /**
   * A timed decision the match is currently waiting on. [anchorMillis] is the
   * server timestamp the deadline derives from (the doc's `updatedAt`), so a
   * caller can compare it against a server clock without re-reading.
   */
  data class Pending(
    val phase: DecisionPhase,
    val seatId: String,
    val durationSeconds: Int,
    val anchorMillis: Long,
  )

  /** The write that applies one bot decision, in the human path's own shape. */
  data class DecisionWrite(
    /** Doc updates — the exact keys MatchService writes for this action,
     *  `updatedAt` carrying a serverTimestamp() sentinel. */
    val updates: Map<String, Any?>,
    /** What was decided, for the caller's own acknowledgement (S38). Public
     *  information only — the move itself, never any hand. */
    val decided: Map<String, Any?>,
  )

  sealed class Resolution {
    /** Nothing was written. [reason] is one of [Reason]. */
    data class NoWrite(val reason: String, val message: String) : Resolution()

    /** The MEDIUM bot decided and the write applies the decision. */
    data class Applied(val write: DecisionWrite) : Resolution()
  }

  // ── constants mirrored from :services (not visible to :functions) ────

  /**
   * RD21's Decision Timer default, bounds, and the round's card total. These
   * live on [com.estemshan.services.model.MatchModels] in :services, which
   * this module cannot see (it depends on :engine only), so the values are
   * mirrored here the way [Settlement] mirrors RoomDoc's parsers — a drift
   * between them would be caught by the rules layer's own bounds check.
   */
  private const val DEFAULT_DECISION_TIMER_SECONDS = 15
  private const val MIN_DECISION_TIMER_SECONDS = 5
  private const val MAX_DECISION_TIMER_SECONDS = 20

  private const val MILLIS_PER_SECOND = 1000L

  /** 13 tricks × the seats at the table (maybeCompleteRound's own check). */
  private fun cardTotal(seats: Int): Int = 13 * seats

  // ── parsing (plain Admin-SDK maps → typed inputs) ─────────────────────

  private fun num(value: Any?): Int? = (value as? Number)?.toInt()

  private fun strMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val out = LinkedHashMap<String, Any?>(map.size)
    for ((k, v) in map) {
      out[k as? String ?: return null] = v
    }
    return out
  }

  private fun parseSuit(value: Any?): Suit? =
    (value as? String)?.let { runCatching { Suit.valueOf(it) }.getOrNull() }

  /** Mirrors RoomDoc.parseGameType: absent/unrecognised ⇒ FULL. */
  fun parseGameType(value: Any?): GameType =
    (value as? String)?.let { runCatching { GameType.valueOf(it) }.getOrNull() }
      ?: GameType.FULL

  /** Mirrors RoomDoc.parseDecisionTimerSeconds: absent, non-numeric, or
   *  outside RD21's 5..20 s window ⇒ 15 s — a read can never surface a timer
   *  the host never picked. */
  fun parseDecisionTimerSeconds(value: Any?): Int {
    val seconds = num(value) ?: return DEFAULT_DECISION_TIMER_SECONDS
    return if (seconds in MIN_DECISION_TIMER_SECONDS..MAX_DECISION_TIMER_SECONDS) seconds
    else DEFAULT_DECISION_TIMER_SECONDS
  }

  /** The doc's `updatedAt` as epoch millis (see JsBridging.timestampToMillis). */
  fun parseUpdatedAtMillis(fields: Map<String, Any?>): Long? =
    timestampToMillis(fields["updatedAt"])

  /** Canonical seat order for the seats actually present (Settlement's rule). */
  fun parseOrder(fields: Map<String, Any?>): List<String>? {
    val seats = strMap(fields["seats"]) ?: return null
    val order = DEFAULT_SEATS.filter { seats.containsKey(it) }
    return if (order.isEmpty()) null else order
  }

  /** The seat a uid occupies, from the doc's own seats map — never a
   *  client-supplied seat id (the vote machine's own rule). */
  fun seatOfUid(fields: Map<String, Any?>, uid: String): String? {
    val seats = strMap(fields["seats"]) ?: return null
    return seats.entries.firstOrNull { it.value == uid }?.key
  }

  private fun parseCardEntry(fields: Any?): Play? {
    val map = strMap(fields) ?: return null
    val seat = map["seatId"] as? String ?: return null
    val card = strMap(map["card"]) ?: return null
    val suit = parseSuit(card["suit"]) ?: return null
    val rank = RANKS.firstOrNull { it.v == num(strMap(card["rank"])?.get("v")) } ?: return null
    return Play(seat, Card(suit, rank))
  }

  private sealed interface LoggedIntent {
    val seat: String
    data class Dash(override val seat: String, val declared: Boolean) : LoggedIntent
    data class AuctionPass(override val seat: String) : LoggedIntent
    data class AuctionBid(override val seat: String, val tricks: Int, val suit: Suit) : LoggedIntent
    data class Confirm(override val seat: String, val tricks: Int, val suit: Suit) : LoggedIntent
  }

  private fun parseLoggedAction(fields: Any?): LoggedIntent? {
    val map = strMap(fields) ?: return null
    val seat = map["seatId"] as? String ?: return null
    return when (map["actionType"] as? String) {
      "SubmitDashCallDecision" -> LoggedIntent.Dash(
        seat, map["declaredDashCall"] as? Boolean ?: return null,
      )
      "SubmitAuctionBid" -> {
        if (map["isPass"] as? Boolean == true) {
          LoggedIntent.AuctionPass(seat)
        } else {
          LoggedIntent.AuctionBid(
            seat,
            num(map["tricks"]) ?: return null,
            parseSuit(map["suit"]) ?: return null,
          )
        }
      }
      "SubmitConfirmCall" -> LoggedIntent.Confirm(
        seat,
        num(map["tricks"]) ?: return null,
        parseSuit(map["suit"]) ?: return null,
      )
      else -> null
    }
  }

  private fun loggedIntents(fields: Map<String, Any?>): List<LoggedIntent>? {
    val raw = fields["biddingLog"] as? List<*> ?: return emptyList()
    return raw.map { parseLoggedAction(it) ?: return null }
  }

  // ── detection: which decision is timed, and when does it expire ──────

  /**
   * The timed decision the match is waiting on right now, or null when there
   * is nothing a timeout could resolve (match complete, round not dealt,
   * round played out and awaiting advance, or no seat waiting).
   *
   * Pure: parses + replays the round's own bidding history through the real
   * engine to find `waitingFor` (the same replay Settlement performs), so the
   * acting seat comes from the rules rather than from a client claim. Reads no
   * hands — the caller only spends those reads once a deadline has passed.
   */
  fun pendingDecision(fields: Map<String, Any?>): Pending? {
    if ((fields["status"] as? String) == "complete") return null
    val order = parseOrder(fields) ?: return null
    val currentRound = num(fields["currentRound"]) ?: return null
    val dealtRound = num(strMap(fields["gameState"])?.get("dealtRound")) ?: 0
    if (dealtRound < currentRound) return null
    val timerSeconds = parseDecisionTimerSeconds(fields["decisionTimerSeconds"])
    val anchor = parseUpdatedAtMillis(fields) ?: return null

    if (fields["biddingOpen"] as? Boolean == true) {
      val state = replayBidding(fields, order, currentRound) ?: return null
      if (state.subPhase == BiddingPhase.DONE) return null
      val waiting = state.waitingFor ?: return null
      if (waiting !in order) return null
      return Pending(
        phase = DecisionPhase.BIDDING,
        seatId = waiting,
        durationSeconds = DecisionPhase.BIDDING.durationSeconds(timerSeconds),
        anchorMillis = anchor,
      )
    }

    // Card play. The round is done when every card is on the document —
    // maybeCompleteRound's own structural check — so a finished round waiting
    // on advanceToNextRound is NOT a timed decision.
    val cardCount = (fields["cardLog"] as? List<*>)?.size ?: 0
    if (cardCount >= cardTotal(order.size)) return null

    // Once the opening card is published, the document's own `turn` is the
    // authority (PLAY mid-trick, or RESOLVING where it already holds the
    // trick winner who leads next). Before it, the engine-selected opening
    // leader must publish — the doc's turn is still null (rounds 2+) or the
    // round-1 dealer placeholder — so the leader comes from the auction.
    val seat = if (fields["cardPhase"] != null) {
      val turnUid = fields["turn"] as? String ?: return null
      seatOfUid(fields, turnUid) ?: return null
    } else {
      replayBiddingOutcome(fields, order, currentRound)?.leaderId ?: return null
    }
    if (seat !in order) return null
    return Pending(
      phase = DecisionPhase.CARD,
      seatId = seat,
      durationSeconds = DecisionPhase.CARD.durationSeconds(timerSeconds),
      anchorMillis = anchor,
    )
  }

  /** True iff [nowMillis] is at or past the pending decision's derived deadline. */
  fun isExpired(pending: Pending, nowMillis: Long): Boolean =
    nowMillis >= pending.anchorMillis + pending.durationSeconds * MILLIS_PER_SECOND

  // ── reconstruction + decision ────────────────────────────────────────

  /**
   * Rebuilds the acting seat's engine state from the document and asks the
   * MEDIUM bot for the one decision the timer expired on. The bot's intent is
   * re-validated by the brain itself before any write is built (BidBrain and
   * PlayBrain both assert legality or throw), so an impossible reconstruction
   * fails closed instead of writing an illegal move.
   *
   * [hands] is the round's dealt hands, read server-side. The card path needs
   * all of them (PlayBrain reads the table's own hand map, and the replay
   * subtracts each played card); the bidding path needs only the acting
   * seat's. Nothing here is returned to a client.
   */
  fun resolveExpired(
    fields: Map<String, Any?>,
    hands: Map<String, List<Card>>,
    pending: Pending,
  ): Resolution {
    val order = parseOrder(fields)
      ?: return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: the match has no seats.",
      )
    val currentRound = num(fields["currentRound"])
      ?: return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: currentRound is missing.",
      )

    return when (pending.phase) {
      DecisionPhase.BIDDING -> {
        val state = replayBidding(fields, order, currentRound)
          ?: return Resolution.NoWrite(
            Reason.HISTORY_UNAVAILABLE,
            "resolveDecisionTimeout: the round's bidding history cannot be replayed.",
          )
        if (state.subPhase == BiddingPhase.DONE || state.waitingFor != pending.seatId) {
          // The match moved between detection and resolution — a late trigger.
          return Resolution.NoWrite(Reason.NOT_ACTIVE, describeState(state))
        }
        val hand = hands[pending.seatId]
          ?: return Resolution.NoWrite(
            Reason.HISTORY_UNAVAILABLE,
            "resolveDecisionTimeout: no dealt hand for seat '${pending.seatId}'.",
          )
        decideBidding(fields, order, state, pending.seatId, hand)
      }
      DecisionPhase.CARD -> {
        val table = replayTable(fields, order, currentRound, hands)
          ?: return Resolution.NoWrite(
            Reason.HISTORY_UNAVAILABLE,
            "resolveDecisionTimeout: the round's history cannot be replayed into a table.",
          )
        if (table.phase != TablePhase.PLAY || table.turn != pending.seatId) {
          return Resolution.NoWrite(Reason.NOT_ACTIVE, describeTable(table))
        }
        decideCard(fields, table, pending.seatId)
      }
    }
  }

  // ── bidding: replay the auction, then BidBrain at MEDIUM ─────────────

  /**
   * The match doc carries no round multiplier: MatchService never persists
   * one, and the clients keep it in their own session. It is not load-bearing
   * here — it only feeds scoring, which the resolver never does — and the
   * general-pass redeal's doubled multiplier is produced by the engine itself,
   * so the replay's turn order and every legality check are unaffected. The
   * fresh-round default is therefore correct for the decision.
   */
  private const val ROUND_MULTIPLIER_DEFAULT = 1

  /**
   * Replays the round's biddingLog (and, once the auction closes, the doc's
   * transient `bids` as FinalEstimate intents) through the real engine and
   * returns the resulting state — or null when the history does not replay
   * legally. Mirrors Settlement.scoreRound's replay, including the
   * general-pass redeal, which restarts the same round at the engine's own
   * doubled multiplier without a document write.
   */
  fun replayBidding(
    fields: Map<String, Any?>,
    order: List<String>,
    round: Int,
  ): BiddingState? = replayBiddingUntilOutcome(fields, order, round).state

  /** The closed auction's outcome, or null when the auction has not closed. */
  private fun replayBiddingOutcome(
    fields: Map<String, Any?>,
    order: List<String>,
    round: Int,
  ): BiddingOutcome? = replayBiddingUntilOutcome(fields, order, round).outcome

  private data class BiddingReplay(val state: BiddingState?, val outcome: BiddingOutcome?)

  /**
   * One walk over the logged intents, continuing into the doc's stored
   * estimates until the auction closes or a seat is waiting. Emits the state
   * to keep, or the outcome once closed; either may be null for the caller to
   * treat as "not replayable" / "not closed".
   */
  private fun replayBiddingUntilOutcome(
    fields: Map<String, Any?>,
    order: List<String>,
    round: Int,
  ): BiddingReplay {
    val gameType = parseGameType(fields["gameType"])
    val dealerSeat = seatOfUid(fields, fields["dealer"] as? String ?: return BiddingReplay(null, null))
      ?.takeIf { it in order } ?: return BiddingReplay(null, null)
    val fast = gameType.isFastRound(round)
    var state: BiddingState = if (fast) {
      initFastRound(round, dealerSeat, order, ROUND_MULTIPLIER_DEFAULT, gameType)
    } else {
      initNormalRound(round, dealerSeat, order, ROUND_MULTIPLIER_DEFAULT, gameType)
    }

    val logged = loggedIntents(fields) ?: return BiddingReplay(null, null)
    for (entry in logged) {
      if (entry.seat !in order) return BiddingReplay(null, null)
      val intent = when (entry) {
        is LoggedIntent.Dash -> BiddingIntent.DashCallDecision(entry.seat, entry.declared)
        is LoggedIntent.AuctionPass -> BiddingIntent.AuctionBid(entry.seat, isPass = true)
        is LoggedIntent.AuctionBid -> BiddingIntent.AuctionBid(
          entry.seat, isPass = false, tricks = entry.tricks, suit = entry.suit,
        )
        is LoggedIntent.Confirm -> BiddingIntent.ConfirmCall(entry.seat, entry.tricks, entry.suit)
      }
      when (val emitted = emit(state, intent)) {
        is EmitResult.Completed -> return BiddingReplay(emitted.state, emitted.outcome)
        is EmitResult.GeneralPass -> {
          if (fast) return BiddingReplay(null, null)
          state = initNormalRound(
            round, dealerSeat, order, emitted.doubledMultiplier, gameType,
          )
        }
        is EmitResult.Applied -> state = emitted.state
        is EmitResult.Rejected -> return BiddingReplay(null, null)
      }
    }

    // The log did not close the auction; continue from the doc's committed
    // estimates in the engine's own turn order until it closes or a seat is
    // waiting on one. This is seedStoredEstimates' own walk.
    val stored = strMap(fields["bids"])?.mapNotNull { (seat, value) ->
      num(value)?.let { seat to it }
    }?.toMap().orEmpty()
    var guard = 0
    while (guard <= order.size) {
      guard++
      val waiting = state.waitingFor ?: return BiddingReplay(state, null)
      if (state.subPhase != BiddingPhase.ESTIMATES) return BiddingReplay(state, null)
      val value = stored[waiting] ?: return BiddingReplay(state, null)
      when (val emitted = emit(state, BiddingIntent.FinalEstimate(waiting, value))) {
        is EmitResult.Completed -> return BiddingReplay(emitted.state, emitted.outcome)
        is EmitResult.Applied -> state = emitted.state
        is EmitResult.GeneralPass -> return BiddingReplay(null, null)
        is EmitResult.Rejected -> return BiddingReplay(null, null)
      }
    }
    return BiddingReplay(state, null)
  }

  /** Runs BidBrain at MEDIUM and builds the write the human path would make. */
  private fun decideBidding(
    fields: Map<String, Any?>,
    order: List<String>,
    state: BiddingState,
    seatId: String,
    hand: List<Card>,
  ): Resolution {
    val intent = try {
      BidBrain.decide(state, hand, seatId, BotTier.MEDIUM, BotPersonality.DEFAULT)
    } catch (e: Throwable) {
      return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: the MEDIUM bot could not decide for seat " +
          "'$seatId' (${state.subPhase}) — the reconstructed state disagrees " +
          "with the document.",
      )
    }

    return when (intent) {
      is BiddingIntent.FinalEstimate -> {
        // submitBid's own write shape: bids/ + biddingOpen + lastBidSeat.
        val bids = LinkedHashMap(strMap(fields["bids"]) ?: emptyMap())
        bids[seatId] = intent.tricks
        val allSubmitted = order.all { bids[it] != null }
        Resolution.Applied(DecisionWrite(
          updates = mapOf(
            "bids" to bids,
            "biddingOpen" to !allSubmitted,
            "lastBidSeat" to seatId,
            "version" to (num(fields["version"]) ?: 0) + 1,
            "updatedAt" to FieldValue.serverTimestamp(),
          ),
          decided = mapOf(
            "phase" to state.subPhase.name,
            "seatId" to seatId,
            "estimate" to intent.tricks,
            "botTier" to BotTier.MEDIUM.name,
          ),
        ))
      }
      is BiddingIntent.DashCallDecision -> biddingWrite(
        fields, loggedDashCall(seatId, state.round, intent.declaredDashCall),
        mapOf(
          "phase" to state.subPhase.name,
          "seatId" to seatId,
          "declaredDashCall" to intent.declaredDashCall,
          "botTier" to BotTier.MEDIUM.name,
        ),
      )
      is BiddingIntent.AuctionBid -> biddingWrite(
        fields, loggedAuctionBid(seatId, state.round, intent),
        mapOf(
          "phase" to state.subPhase.name,
          "seatId" to seatId,
          "isPass" to intent.isPass,
          "tricks" to intent.tricks,
          "suit" to intent.suit?.name,
          "botTier" to BotTier.MEDIUM.name,
        ),
      )
      is BiddingIntent.ConfirmCall -> biddingWrite(
        fields, loggedConfirmCall(seatId, state.round, intent),
        mapOf(
          "phase" to state.subPhase.name,
          "seatId" to seatId,
          "tricks" to intent.tricks,
          "suit" to intent.suit.name,
          "botTier" to BotTier.MEDIUM.name,
        ),
      )
    }
  }

  /** BiddingLogEntry.toFields' own shape, built from the bot's intent. */
  private fun loggedDashCall(seatId: String, round: Int, declared: Boolean): Map<String, Any?> =
    mapOf(
      "seatId" to seatId,
      "actionType" to "SubmitDashCallDecision",
      "declaredDashCall" to declared,
      "round" to round,
    )

  private fun loggedAuctionBid(
    seatId: String,
    round: Int,
    intent: BiddingIntent.AuctionBid,
  ): Map<String, Any?> = mapOf(
    "seatId" to seatId,
    "actionType" to "SubmitAuctionBid",
    "isPass" to (intent.isPass == true),
    "tricks" to intent.tricks,
    "suit" to intent.suit?.name,
    "round" to round,
  )

  private fun loggedConfirmCall(
    seatId: String,
    round: Int,
    intent: BiddingIntent.ConfirmCall,
  ): Map<String, Any?> = mapOf(
    "seatId" to seatId,
    "actionType" to "SubmitConfirmCall",
    "tricks" to intent.tricks,
    "suit" to intent.suit.name,
    "round" to round,
  )

  /** submitBiddingAction's own write shape: append-only biddingLog + version. */
  private fun biddingWrite(
    fields: Map<String, Any?>,
    entry: Map<String, Any?>,
    decided: Map<String, Any?>,
  ): Resolution = Resolution.Applied(DecisionWrite(
    updates = mapOf(
      "biddingLog" to FieldValue.arrayUnion(entry),
      "version" to (num(fields["version"]) ?: 0) + 1,
      "updatedAt" to FieldValue.serverTimestamp(),
    ),
    decided = decided,
  ))

  // ── card play: replay the tricks, then PlayBrain at MEDIUM ───────────

  /**
   * Rebuilds the round's table from the closed auction plus the dealt hands,
   * then replays the round's cardLog through emitPlay/resolveTrick — the same
   * alternation the online client's own catch-up loop performs, so the table
   * is the one every seat converged on. Null when any step contradicts the
   * engine (fail closed, never invent a card).
   */
  fun replayTable(
    fields: Map<String, Any?>,
    order: List<String>,
    round: Int,
    hands: Map<String, List<Card>>,
  ): TableState? {
    val outcome = replayBiddingOutcome(fields, order, round) ?: return null
    val resolvedHands = LinkedHashMap<String, List<Card>>()
    for (seat in order) {
      resolvedHands[seat] = hands[seat] ?: return null
    }
    val table0 = initTable(
      RoundCfg(
        round = round,
        trump = outcome.trump,
        callerId = outcome.callerId,
        withPlayers = outcome.withPlayers,
        estimates = outcome.estimates,
        dashCallers = outcome.dashCallers,
        leaderId = outcome.leaderId,
        riskId = outcome.riskPlayerId,
        multiplier = ROUND_MULTIPLIER_DEFAULT,
        hands = resolvedHands,
      ),
      order,
    )

    var table = table0
    for (play in (fields["cardLog"] as? List<*>).orEmpty().mapNotNull { parseCardEntry(it) }) {
      if (play.playerId !in order) return null
      when (val emitted = emitPlay(table, PlayCard(play.playerId, play.card))) {
        is PlayEmit.Applied -> {
          table = emitted.state
          if (table.phase == TablePhase.RESOLVING) {
            table = resolveTrick(table)
            if (table.phase != TablePhase.PLAY && table.phase != TablePhase.DONE) return null
          }
        }
        is PlayEmit.Rejected -> return null
      }
    }
    return table
  }

  /** Runs PlayBrain at MEDIUM and builds submitCard's own write shape. */
  private fun decideCard(
    fields: Map<String, Any?>,
    table: TableState,
    seatId: String,
  ): Resolution {
    val play = try {
      PlayBrain.decide(table, seatId, BotTier.MEDIUM)
    } catch (e: Throwable) {
      return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: the MEDIUM bot could not play for seat " +
          "'$seatId' — the reconstructed table disagrees with the document.",
      )
    }
    val preview = previewPlay(table, seatId, play.card)
    if (!preview.legal) {
      return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: the MEDIUM bot's card was rejected for seat '$seatId'.",
      )
    }

    // The opening card's `turn`/`cardPhase` publication is a separate atomic
    // write on the client because the card ruleset requires the old turn to
    // already hold the opening seat. The authority layer bypasses the rules,
    // so one update carries the publication and the card together — a seat
    // whose app died before publishing still unblocks the round.
    val nextTurnUid = if (preview.nextPhase == TablePhase.RESOLVING) {
      // submitCard's own fourth-card rule: the persisted turn becomes the
      // engine-derived trick winner, never null.
      val winnerSeat = trickWinnerAfter(table, seatId, play.card)
        ?: return Resolution.NoWrite(
          Reason.HISTORY_UNAVAILABLE,
          "resolveDecisionTimeout: the resolving trick has no complete engine context.",
        )
      seatUid(fields, winnerSeat)
    } else {
      preview.nextTurnSeat?.let { seatUid(fields, it) }
    }

    val entry = mapOf(
      "seatId" to seatId,
      "card" to storedCardFields(play.card),
      "round" to table.cfg.round,
    )
    return Resolution.Applied(DecisionWrite(
      updates = mapOf(
        "cardLog" to FieldValue.arrayUnion(entry),
        "lastCardSeat" to seatId,
        "turn" to nextTurnUid,
        "cardPhase" to preview.nextPhase?.name,
        "version" to (num(fields["version"]) ?: 0) + 1,
        "updatedAt" to FieldValue.serverTimestamp(),
      ),
      decided = mapOf(
        "phase" to "CARD",
        "seatId" to seatId,
        "card" to storedCardFields(play.card),
        "trickNo" to table.trickNo,
        "botTier" to BotTier.MEDIUM.name,
      ),
    ))
  }

  /**
   * The trick's winner once [card] is the table's fourth play — the engine's
   * own trickWinner over the completed trick, exactly the value
   * MatchService.resolveFourthCardWinnerUid persists (it reads the same last
   * four plays through the same function).
   */
  private fun trickWinnerAfter(table: TableState, seatId: String, card: Card): String? {
    val plays = table.plays + Play(seatId, card)
    if (plays.size != table.seats.size) return null
    return trickWinner(table.cfg.trump, plays.first().card.suit, plays)
  }

  /** The uid occupying [seatId], or null when the seat is empty. */
  private fun seatUid(fields: Map<String, Any?>, seatId: String): String? =
    (strMap(fields["seats"])?.get(seatId) as? String)

  /** { suit, rank: { v, s } } — StoredCard.toFields' own shape. */
  private fun storedCardFields(card: Card): Map<String, Any?> = mapOf(
    "suit" to card.suit.name,
    "rank" to mapOf("v" to card.rank.v, "s" to card.rank.s),
  )

  private fun describeState(state: BiddingState): String =
    "resolveDecisionTimeout: the match moved on — bidding is ${state.subPhase}, " +
      "waiting on ${state.waitingFor}."

  private fun describeTable(table: TableState): String =
    "resolveDecisionTimeout: the match moved on — the table is ${table.phase}, " +
      "waiting on ${table.turn}."
}
