package com.estemshan.functions

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.Card
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.Play
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.computeWinner
import com.estemshan.engine.emit
import com.estemshan.engine.EmitResult
import com.estemshan.engine.initFastRound
import com.estemshan.engine.initNormalRound
import com.estemshan.engine.isFastRound
import com.estemshan.engine.trickWinner

/**
 * S50 settlement core (RD12 correct-and-settle, RD13 idempotent).
 *
 * Re-reads the CONVERGED match document + roundArchive history (never the
 * client's claim) and re-runs the real shared :engine over it to recompute
 * the authoritative finalScores/winnerIds. A claim that disagrees is
 * CORRECTED — the recomputed value is what settles — never blindly trusted
 * and never merely rejected.
 *
 * Everything here is pure Kotlin over plain parsed maps: no Firestore, no
 * JS SDK, no clock. The Firestore read/write + callable seam lives in
 * SettlementDb.kt / Functions.kt; the denial matrix below is fully
 * unit-testable on the JS target without loading any SDK.
 *
 * What the converged history supports, exactly (verified against
 * :services + :engine source — nothing is invented):
 * - auction replay (initNormalRound/initFastRound + logged intents in
 *   order) re-derives callerId, withPlayers, dashCallers, general-pass
 *   doublings and the trump — deterministic, same engine the clients ran;
 * - archived Final Estimates are fed back as FinalEstimate intents in
 *   engine turn order, so the engine VALIDATES them (cap, forbidden-13,
 *   with-floor rejections fail the settlement closed instead of scoring
 *   an illegal number);
 * - scoring bids mirror the clients' bidsFromOutcome mapping
 *   (QuickMatchViewModel.bidsFromOutcome + table-engine.js): dashCallers
 *   keep DASHCALL, a 0 estimate is a Normal Dash, the caller takes
 *   TRICKS at the confirmed — or, with no confirm entry (fast rounds
 *   resolve the caller from estimates), estimated — number, including
 *   the all-dash edge where every seat scores an explicit REG_DASH 0
 *   rather than a Dash-Call;
 * - tricksWon replays out of the archived cardLog (13 consecutive
 *   four-card tricks per round) through trickWinner;
 * - the Saayda multiplier chain and the Classic cap ride the recomputed
 *   results exactly as the clients accumulate them.
 *
 * Requires the S50 archive fix (RoundArchiveDoc.estimates): estimates
 * submitted via submitBid are transient on the parent and reset every
 * round. Archives predating that field fail closed as HISTORY_INCOMPLETE
 * rather than settling on fabricated numbers.
 */
object Settlement {

  /** Canonical failure reasons. The seam maps these to HttpsError codes. */
  object Reason {
    /** Settler is not a player in this match (HttpsError permission-denied). */
    const val NOT_MEMBER = "NOT_MEMBER"

    /** Match status is not complete (HttpsError failed-precondition). */
    const val NOT_COMPLETE = "NOT_COMPLETE"

    /** Expected history is absent (HttpsError failed-precondition). */
    const val HISTORY_INCOMPLETE = "HISTORY_INCOMPLETE"

    /** Present history contradicts the engine or the doc shape (failed-precondition). */
    const val HISTORY_CORRUPT = "HISTORY_CORRUPT"
  }

  // ── parsed converged-history inputs ───────────────────────────────

  /** One archived card play: {seatId, card:{suit, rank:{v}}, round}. */
  data class SettledPlay(val seat: String, val suit: Suit, val rankV: Int)

  /** One archived bidding action (dash/auction/confirm only — estimates
   *  arrive via the estimates snapshot, never the log). */
  data class SettledAction(
    val seat: String,
    val kind: ActionKind,
    val declaredDashCall: Boolean?,
    val isPass: Boolean?,
    val tricks: Int?,
    val suit: Suit?,
  )

  enum class ActionKind { DASH, AUCTION, CONFIRM }

  /**
   * One round's converged inputs. [estimates] is null when the archive
   * predates the S50 fix — fail-closed, never defaulted.
   */
  data class ArchivedRound(
    val round: Int,
    val plays: List<SettledPlay>,
    val actions: List<SettledAction>,
    val estimates: Map<String, Int>?,
  )

  /**
   * The converged match document, parsed. [order] is canonical seat order;
   * [dealerSeatFinal] is the seat holding the dealer marker at completion
   * (the round-N dealer — the walk-back anchor).
   */
  data class ConvergedMatch(
    val matchId: String,
    val mode: MatchMode,
    val status: String,
    val order: List<String>,
    val players: List<String>,
    val dealerSeatFinal: String?,
    val gameType: GameType,
    val classic: Boolean,
    val completedRound: Int,
    val maxRounds: Int,
    val claimedScores: Map<String, Int>?,
    val claimedWinners: List<String>?,
    val archives: List<ArchivedRound>,
  )

  // ── outcomes ──────────────────────────────────────────────────────

  /** The recomputed authoritative result for one match. */
  data class RecomputedMatch(
    val finalScores: Map<String, Int>,
    val winnerIds: List<String>,
    val rounds: Int,
    val corrected: Boolean,
    val claimedScores: Map<String, Int>,
    val claimedWinners: List<String>,
  )

  sealed class SettleDecision {
    /** Not a Ranked match — no-op by design (ROOM/UNRANKED never settle). */
    data class NoOp(val mode: MatchMode) : SettleDecision()

    /** Ranked + member — proceed to the archive stage. */
    data object Proceed : SettleDecision()

    /** Recompute matched the claim — record the settlement, change nothing. */
    data class Agreed(val recomputed: RecomputedMatch) : SettleDecision()

    /** Recompute disagrees — the recomputed value is what settles (RD12). */
    data class Corrected(val recomputed: RecomputedMatch) : SettleDecision()

    /** Fail-closed: nothing settles. The seam throws the mapped HttpsError. */
    data class Failed(val reason: String, val message: String) : SettleDecision()
  }

  // ── parsing (dynamic Admin-SDK maps → typed inputs) ───────────────

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

  /** Mirrors RoomDoc.parseScoringMode: absent/unrecognised ⇒ NORMAL. */
  fun parseScoringMode(value: Any?): ScoringMode =
    (value as? String)?.let { runCatching { ScoringMode.valueOf(it) }.getOrNull() }
      ?: ScoringMode.NORMAL

  /** Mirrors RoomDoc.parseMatchMode: absent/unrecognised ⇒ ROOM (never throws). */
  fun parseMatchMode(value: Any?): MatchMode =
    (value as? String)?.let { runCatching { MatchMode.valueOf(it) }.getOrNull() }
      ?: MatchMode.ROOM

  fun parseCard(fields: Any?): SettledPlay? {
    val map = strMap(fields) ?: return null
    val seat = map["seatId"] as? String ?: return null
    val card = strMap(map["card"]) ?: return null
    val suit = parseSuit(card["suit"]) ?: return null
    val rankV = num(strMap(card["rank"])?.get("v")) ?: return null
    if (RANKS.none { it.v == rankV }) return null
    return SettledPlay(seat, suit, rankV)
  }

  fun parseAction(fields: Any?): SettledAction? {
    val map = strMap(fields) ?: return null
    val seat = map["seatId"] as? String ?: return null
    return when (map["actionType"] as? String) {
      "SubmitDashCallDecision" -> SettledAction(
        seat, ActionKind.DASH,
        declaredDashCall = map["declaredDashCall"] as? Boolean ?: return null,
        isPass = null, tricks = null, suit = null,
      )
      "SubmitAuctionBid" -> {
        val pass = map["isPass"] as? Boolean ?: return null
        if (pass) {
          SettledAction(seat, ActionKind.AUCTION, null, true, null, null)
        } else {
          val tricks = num(map["tricks"]) ?: return null
          val suit = parseSuit(map["suit"]) ?: return null
          if (tricks !in 0..13) return null
          SettledAction(seat, ActionKind.AUCTION, null, false, tricks, suit)
        }
      }
      "SubmitConfirmCall" -> {
        val tricks = num(map["tricks"]) ?: return null
        val suit = parseSuit(map["suit"]) ?: return null
        if (tricks !in 0..13) return null
        SettledAction(seat, ActionKind.CONFIRM, null, null, tricks, suit)
      }
      else -> null
    }
  }

  fun parseEstimates(value: Any?): Map<String, Int>? {
    if (value == null) return null
    val map = strMap(value) ?: return null
    val out = LinkedHashMap<String, Int>()
    for ((seat, raw) in map) {
      val estimate = num(raw) ?: return null
      if (estimate !in 0..13) return null
      out[seat] = estimate
    }
    return out
  }

  /**
   * Parses the converged match document. Returns null when the shape is
   * not a match at all — the caller treats that as HISTORY_CORRUPT.
   */
  fun parseMatch(matchId: String, fields: Map<String, Any?>): ConvergedMatch? {
    val seats = strMap(fields["seats"]) ?: return null
    val order = DEFAULT_SEATS.filter { seats.containsKey(it) }
    if (order.isEmpty()) return null
    val players = (fields["players"] as? List<*>)
      ?.map { it as? String ?: return null } ?: return null
    val dealerUid = fields["dealer"] as? String
    // MatchDoc.dealer is uid-space; the replay walks seat-space.
    val dealerSeatFinal = dealerUid?.let { uid ->
      seats.entries.firstOrNull { it.value == uid }?.key
    }
    val completedRound = num(fields["completedRound"]) ?: return null
    val maxRounds = num(fields["maxRounds"]) ?: return null
    val claimedScores = (fields["finalScores"] as? Map<*, *>)?.let { raw ->
      val out = LinkedHashMap<String, Int>()
      for ((k, v) in raw) {
        out[k as? String ?: return null] = num(v) ?: return null
      }
      out
    }
    val claimedWinners = (fields["winnerIds"] as? List<*>)
      ?.map { it as? String ?: return null }
    return ConvergedMatch(
      matchId = matchId,
      mode = parseMatchMode(fields["mode"]),
      status = fields["status"] as? String ?: return null,
      order = order,
      players = players,
      dealerSeatFinal = dealerSeatFinal,
      gameType = parseGameType(fields["gameType"]),
      classic = parseScoringMode(fields["scoringMode"]) == ScoringMode.CLASSIC,
      completedRound = completedRound,
      maxRounds = maxRounds,
      claimedScores = claimedScores,
      claimedWinners = claimedWinners,
      archives = emptyList(),
    )
  }

  fun parseArchive(docId: String, fields: Map<String, Any?>): ArchivedRound? {
    val round = docId.toIntOrNull() ?: return null
    if (num(fields["round"]) != round) return null
    val plays = (fields["cardLog"] as? List<*>)
      ?.map { parseCard(it) ?: return null } ?: return null
    val actions = (fields["biddingLog"] as? List<*>)
      ?.map { parseAction(it) ?: return null } ?: return null
    // Absent key ⇒ pre-fix archive (fail-closed downstream); present
    // non-map ⇒ corrupt. Never defaulted.
    val estimates = if (fields.containsKey("estimates")) {
      parseEstimates(fields["estimates"]) ?: return null
    } else {
      null
    }
    return ArchivedRound(round, plays, actions, estimates)
  }

  // ── gate ──────────────────────────────────────────────────────────

  /**
   * Membership + mode gate over the converged document. ROOM/UNRANKED
   * (including absent-or-legacy mode, which parses as ROOM per S43) are
   * an explicit no-op — settlement never runs outside RANKED (RD28).
   */
  fun decide(uid: String, match: ConvergedMatch): SettleDecision {
    if (uid !in match.players) {
      return SettleDecision.Failed(
        Reason.NOT_MEMBER,
        "settleMatch: you are not a player in this match.",
      )
    }
    if (match.mode != MatchMode.RANKED) {
      return SettleDecision.NoOp(match.mode)
    }
    return SettleDecision.Proceed
  }

  // ── recompute ─────────────────────────────────────────────────────

  /**
   * Recomputes the authoritative result from the converged history.
   * Returns Agreed/Corrected against the claim, or Failed closed —
   * HISTORY_INCOMPLETE when expected history is absent (pre-fix archives
   * carry no estimates), HISTORY_CORRUPT when present history contradicts
   * the engine or the doc shape. Never fabricates: every number below is
   * engine-derived from converged inputs.
   */
  fun recompute(match: ConvergedMatch): SettleDecision {
    if (match.status != "complete") {
      return SettleDecision.Failed(
        Reason.NOT_COMPLETE,
        "settleMatch: match '${match.matchId}' is not complete.",
      )
    }
    if (match.completedRound + 1 <= match.maxRounds) {
      return SettleDecision.Failed(
        Reason.HISTORY_CORRUPT,
        "settleMatch: completedRound ${match.completedRound} does not end " +
          "a ${match.maxRounds}-round match.",
      )
    }
    val claimedScores = match.claimedScores
    val claimedWinners = match.claimedWinners
    if (claimedScores == null || claimedWinners == null) {
      return SettleDecision.Failed(
        Reason.HISTORY_CORRUPT,
        "settleMatch: a complete match with no claimed result cannot be corrected.",
      )
    }
    if (claimedScores.keys.sorted() != match.order.sorted()) {
      return SettleDecision.Failed(
        Reason.HISTORY_CORRUPT,
        "settleMatch: claimed finalScores do not cover exactly the match seats.",
      )
    }
    val byRound = match.archives.associateBy { it.round }
    for (round in 1..match.completedRound) {
      if (!byRound.containsKey(round)) {
        return SettleDecision.Failed(
          Reason.HISTORY_INCOMPLETE,
          "settleMatch: roundArchive/$round is absent.",
        )
      }
    }

    var multiplier = 1
    val escalationCap = if (match.classic) 2 else 8
    var totals: Map<String, Int> = match.order.associateWith { 0 }
    for (round in 1..match.completedRound) {
      val archived = byRound.getValue(round)
      val dealer = dealerSeatForRound(match, round)
        ?: return SettleDecision.Failed(
          Reason.HISTORY_INCOMPLETE,
          "settleMatch: round $round dealer is not resolvable from the match seats.",
        )
      val scored = scoreRound(match, archived, dealer, multiplier, escalationCap)
        ?: return if (archived.estimates == null) {
          SettleDecision.Failed(
            Reason.HISTORY_INCOMPLETE,
            "settleMatch: roundArchive/${archived.round} predates the estimates " +
              "snapshot — refusing to settle on fabricated numbers.",
          )
        } else {
          SettleDecision.Failed(
            Reason.HISTORY_CORRUPT,
            "settleMatch: roundArchive/${archived.round} contradicts the rules engine.",
          )
        }
      totals = accumulateMatchScores(totals, scored.deltas)
      multiplier = scored.nextMultiplier
    }

    val winners = computeWinner(totals)
    val recomputed = RecomputedMatch(
      finalScores = totals,
      winnerIds = winners,
      rounds = match.completedRound,
      corrected = totals != claimedScores || winners.sorted() != claimedWinners.sorted(),
      claimedScores = claimedScores,
      claimedWinners = claimedWinners,
    )
    return if (recomputed.corrected) {
      SettleDecision.Corrected(recomputed)
    } else {
      SettleDecision.Agreed(recomputed)
    }
  }

  /** One scored round: engine deltas plus the chained next multiplier. */
  data class ScoredRound(val deltas: Map<String, Int>, val nextMultiplier: Int)

  /**
   * Dealer walk-back: the parent marker holds the round-N dealer, and each
   * advance rotates exactly one seat forward in canonical order
   * (nextDealerUid), so round r's dealer is (N − r) steps backward.
   */
  fun dealerSeatForRound(match: ConvergedMatch, round: Int): String? {
    val final = match.dealerSeatFinal ?: return null
    if (final !in match.order) return null
    var seat = final
    repeat(match.completedRound - round) {
      val index = match.order.indexOf(seat)
      seat = match.order[(index - 1 + match.order.size) % match.order.size]
    }
    return seat
  }

  /**
   * Replays one round's converged auction (logged intents) plus its
   * archived estimates (fed as FinalEstimate intents in engine turn
   * order) through the real bidding engine, then scores the replayed
   * cardLog through the real scoring engine. Any engine rejection, any
   * unconsumed archive value, or any malformed trick fails closed (null).
   */
  fun scoreRound(
    match: ConvergedMatch,
    archived: ArchivedRound,
    dealerSeat: String,
    multiplier: Int,
    escalationCap: Int,
  ): ScoredRound? {
    val order = match.order
    val fast = isFastRound(archived.round, match.gameType)
    var state = if (fast) {
      initFastRound(archived.round, dealerSeat, order, multiplier, match.gameType)
    } else {
      initNormalRound(archived.round, dealerSeat, order, multiplier, match.gameType)
    }

    var outcome: BiddingOutcome? = null
    var confirmTricks: Int? = null
    var index = 0
    while (index < archived.actions.size) {
      val entry = archived.actions[index]
      if (entry.seat !in order) return null
      val intent = when (entry.kind) {
        ActionKind.DASH -> BiddingIntent.DashCallDecision(
          entry.seat, entry.declaredDashCall ?: return null,
        )
        ActionKind.AUCTION -> if (entry.isPass == true) {
          BiddingIntent.AuctionBid(entry.seat, isPass = true)
        } else {
          BiddingIntent.AuctionBid(
            entry.seat, isPass = false,
            tricks = entry.tricks ?: return null,
            suit = entry.suit ?: return null,
          )
        }
        ActionKind.CONFIRM -> {
          confirmTricks = entry.tricks ?: return null
          BiddingIntent.ConfirmCall(
            entry.seat, entry.tricks ?: return null, entry.suit ?: return null,
          )
        }
      }
      when (val emitted = emit(state, intent)) {
        is EmitResult.Completed -> {
          outcome = emitted.outcome
          index++
          break
        }
        is EmitResult.GeneralPass -> {
          // Redeal: the same round restarts carrying the doubled
          // multiplier the engine computed. General passes only leave
          // the auction phase, which fast rounds never enter.
          if (fast) return null
          state = initNormalRound(archived.round, dealerSeat, order, emitted.doubledMultiplier, match.gameType)
          index++
        }
        is EmitResult.Applied -> {
          state = emitted.state
          index++
        }
        is EmitResult.Rejected -> return null
      }
    }

    // Archived estimates validate through the engine: each is fed exactly
    // when the engine waits for that seat. A missing value is INCOMPLETE
    // (mapped by the caller); a rejected or unconsumed one is CORRUPT.
    val estimates = archived.estimates
    var completed = outcome
    if (completed == null) {
      if (estimates == null) return null
      val fed = mutableSetOf<String>()
      var guard = 0
      while (guard < order.size + 1) {
        val waiting = state.waitingFor ?: break
        if (waiting !in order) return null
        val estimate = estimates[waiting] ?: return null
        fed.add(waiting)
        when (val emitted = emit(state, BiddingIntent.FinalEstimate(waiting, estimate))) {
          is EmitResult.Completed -> {
            completed = emitted.outcome
            break
          }
          is EmitResult.Applied -> state = emitted.state
          is EmitResult.GeneralPass -> return null
          is EmitResult.Rejected -> return null
        }
        guard++
      }
      val done = completed ?: return null
      if (fed.size != estimates.size) return null
      completed = done
    } else if (estimates != null && estimates.isNotEmpty()) {
      // Bidding completed inside the log (all-dash / fast-super-confirm):
      // no seat could legally estimate afterwards, so any archived value
      // contradicts the converged flow.
      return null
    }

    val final = completed ?: return null
    val bids = scoringBids(match, final, confirmTricks) ?: return null
    val tricksWon = replayTricks(match, archived, final.trump) ?: return null
    val result = calculateRoundScore(
      RoundScoreInput(
        round = archived.round,
        order = order,
        bids = bids,
        tricksWon = tricksWon,
        callerId = final.callerId,
        withPlayers = final.withPlayers,
        multiplier = multiplier,
        riskPlayerId = final.riskPlayerId,
        classic = match.classic,
        escalationCap = escalationCap,
      ),
    )
    return ScoredRound(result.deltas, result.nextMultiplier)
  }

  /**
   * The clients' bidsFromOutcome mapping (QuickMatchViewModel +
   * table-engine.js), verbatim: dashCallers keep DASHCALL, a 0 estimate
   * is a Normal Dash, the caller takes TRICKS at the confirmed — or, with
   * no confirm entry (fast rounds resolve the caller from estimates),
   * estimated — number. Covers exactly the match's seats.
   */
  fun scoringBids(
    match: ConvergedMatch,
    outcome: BiddingOutcome,
    confirmTricks: Int?,
  ): Map<String, Bid>? {
    val caller = outcome.callerId
    val callTricks = if (caller == null) {
      null
    } else {
      confirmTricks ?: outcome.estimates[caller]
    }
    if (caller != null && callTricks == null) return null
    val bids = LinkedHashMap<String, Bid>()
    for (seat in match.order) {
      bids[seat] = when {
        outcome.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
        seat == caller -> Bid(BidType.TRICKS, callTricks ?: return null)
        else -> {
          val estimate = outcome.estimates[seat] ?: return null
          if (estimate == 0) Bid(BidType.DASH, 0) else Bid(BidType.TRICKS, estimate)
        }
      }
    }
    return bids
  }

  /**
   * Replays the archived cardLog through trickWinner: 13 consecutive
   * four-card tricks per round, led suit from each trick's first play.
   * Anything else is a malformed round.
   */
  fun replayTricks(
    match: ConvergedMatch,
    archived: ArchivedRound,
    trump: Suit,
  ): Map<String, Int>? {
    val expected = 13 * match.order.size
    if (archived.plays.size != expected) return null
    val won = LinkedHashMap<String, Int>()
    for (seat in match.order) won[seat] = 0
    for (trick in 0 until 13) {
      val group = archived.plays.subList(trick * 4, trick * 4 + 4)
      if (group.any { it.seat !in match.order }) return null
      val plays = group.map { entry ->
        val rank = RANKS.firstOrNull { it.v == entry.rankV } ?: return null
        Play(entry.seat, Card(entry.suit, rank))
      }
      val winner = trickWinner(trump, plays.first().card.suit, plays)
      if (winner !in match.order) return null
      won[winner] = won.getValue(winner) + 1
    }
    return won
  }
}
