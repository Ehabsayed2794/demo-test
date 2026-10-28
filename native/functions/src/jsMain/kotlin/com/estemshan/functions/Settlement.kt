package com.estemshan.functions

import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.GameType
import com.estemshan.engine.MatchMode
import com.estemshan.engine.MAX_TRICKS
import com.estemshan.engine.Play
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.Suit
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.biddingOrder
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.computeWinner
import com.estemshan.engine.emit
import com.estemshan.engine.EmitResult
import com.estemshan.engine.fixedTrumpFor
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

/** Minimum number that opens a fast-round Super Call (A6.10/A7.2). */
private const val FAST_SUPER_CALL_MIN = 8

/**
 * S7's 7th-inactivity-takeover removal list, read as a RAW field name: the
 * `:services` match model does not carry it yet (another task owns that
 * file), so settlement parses it straight off the document. Absent on every
 * document until S7 lands — parsed as empty, never fatal.
 */
private const val TAKEOVER_REMOVED_UIDS_FIELD = "takeoverRemovedUids"

/**
 * One fast Super Call CONFIRM from the log: the Super Caller, the number
 * they confirmed and the trump they took.
 */
internal data class FastSuperConfirm(val seat: String, val tricks: Int, val suit: Suit)

/**
 * The replayed state of a fast Super Call chain.
 *
 * [state] is the state after the LAST confirmation — the re-declaration
 * phase, where every seat except the last Super Caller declares again.
 * [outcome] is non-null only when the chain closed the round on its own;
 * the engine's fast confirmation never does, it always re-opens the
 * re-declaration.
 */
internal data class FastSuperChain(
  val state: BiddingState,
  val outcome: BiddingOutcome?,
  val confirmTricks: Int,
)

/**
 * Replays a chain of fast-round Super Call confirmations (T-09).
 *
 * A fast Super Call is NOT the end of the fast round: the confirmation
 * cancels the forced trump, the Super Caller takes a new one, and EVERY
 * seat except that caller declares again counter-clockwise under it. A
 * declaration above the confirmed number opens the NEXT cycle, so a round
 * can hold several confirmations and only the last one closes the auction.
 *
 * The archive keeps ONE value per seat, so an earlier cycle's declarations
 * are gone: only the last cycle's caller still holds its own declaration
 * (it never declares again after its own confirmation). The replay proves
 * what the archive can prove and trusts the logged confirmation for the
 * rest:
 *
 * - every confirmation is a real Super Call: 8..13 tricks. The absolute
 *   13-trick maximum holds in fast rounds too — the fast exemption drops
 *   only the Caller-relative Call Cap;
 * - the numbers strictly increase: a new cycle is opened only by a
 *   declaration ABOVE the previous cycle's confirmed number;
 * - the last caller's slot holds EXACTLY the confirmed number. That is the
 *   T-09 atomic reset's own shape: the write replaces `bids` with
 *   `{caller: tricks}` and the caller never declares again, so its slot is
 *   the confirmed number even when the caller raised it at confirmation.
 *
 * §5 (never reinterpret a historical record): a last-caller slot below the
 * confirmed number cannot come from a T-09 document — it is the pre-T-09
 * archive shape, whose auction ENDED at the confirmation and whose surviving
 * numbers are the declarations the confirmation already cancelled. Such an
 * archive is refused rather than replayed as re-declarations, which would
 * invent a With set the table never had.
 *
 * Documented limitation: a pre-T-09 archive whose last-caller slot happens
 * to EQUAL its confirmed number (no raise at confirmation — the common case)
 * is byte-identical to a T-09 archive, because no field records which engine
 * produced it. It settles under the T-09 rules; only the archive format
 * could ever disambiguate it, and rewriting historical archives is out of
 * scope by rule.
 *
 * Anything else fails closed. Each cycle is replayed through the REAL
 * engine, so the full wipe, the With recomputation and the re-declaration
 * order are the engine's own.
 */
internal fun replayFastSuperCallChain(
  state: BiddingState,
  confirms: List<FastSuperConfirm>,
  stored: Map<String, Int>,
): FastSuperChain? {
  if (!state.fastRound || confirms.isEmpty()) return null
  val order = biddingOrder(state)
  var current = state
  var previousTricks: Int? = null
  var outcome: BiddingOutcome? = null
  var confirmTricks = 0

  for ((index, confirm) in confirms.withIndex()) {
    val isLast = index == confirms.lastIndex
    val seat = confirm.seat
    if (seat !in order) return null
    val tricks = confirm.tricks
    if (tricks < FAST_SUPER_CALL_MIN || tricks > MAX_TRICKS) return null
    if (previousTricks != null && tricks <= previousTricks) return null

    // The last cycle's caller slot is the confirmed number itself (see the
    // §5 note above): anything else — lower is the pre-T-09 archive shape,
    // higher contradicts the confirmation — is not a T-09 record. An earlier
    // cycle's declaration is gone (the archive keeps one value per seat), so
    // only the confirmed numbers can be checked there.
    val declaration = if (isLast) stored[seat] else null
    if (isLast && declaration != tricks) return null

    // The confirmation phase opens for this caller. The trump in force is
    // the one the previous cycle confirmed (the round's fixed trump in the
    // first cycle) and the number the caller must confirm is its own
    // declaration — known for the last cycle only.
    val inForce = current.declaredTrump ?: fixedTrumpFor(state.round, state.gameType)
    val preConfirm = current.copy(
      subPhase = BiddingPhase.CONFIRM,
      waitingFor = seat,
      noSuitConstraint = true,
      fastSuperResolved = false,
      callerId = null,
      withPlayers = emptyList(),
      auctionTop = declaration ?: previousTricks ?: (FAST_SUPER_CALL_MIN - 1),
      bids = mapOf(seat to Bid(BidType.TRICKS, declaration ?: tricks, inForce)),
      lastBidderId = null,
    )
    when (val emitted = emit(preConfirm, BiddingIntent.ConfirmCall(seat, tricks, confirm.suit))) {
      is EmitResult.Applied -> current = emitted.state
      is EmitResult.Completed -> {
        current = emitted.state
        outcome = emitted.outcome
      }
      is EmitResult.GeneralPass -> return null
      is EmitResult.Rejected -> return null
    }
    previousTricks = tricks
    confirmTricks = tricks
    if (outcome != null) break
  }
  return FastSuperChain(current, outcome, confirmTricks)
}

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
   * (the round-N dealer — the walk-back anchor). [currentMultiplier] is the
   * O-19 persisted multiplier of the FINAL round (the current round at
   * completion); null means the document predates the field, and the
   * replayed chain is then the only authority — the compatible legacy
   * fallback, never a silent reset.
   *
   * Departure provenance (S6, A7.5) — parsed from the same converged fields
   * the gate already reads, so provenance adds no read and opens no race:
   * [leftUids] (S72 explicit Leave Match), [timeoutRemovedUids] (S61's
   * 15-timeout counter), [removedUids] (S60 Vote Kick) and
   * [takeoverRemovedUids] (the 7th-inactivity takeover, written by S7 and
   * read as a raw field name until the services model carries it). All four
   * are absent ⇒ empty; each is parsed defensively (a non-list reads as
   * empty, a non-string element is skipped — the DisconnectVoteFlow
   * `stringSet` convention). [departedUids] is the union the stats-only
   * settlement's forced-KOZ override consumes.
   * `disconnectReplacedUids` is deliberately NOT parsed here: a permanently
   * replaced disconnect keeps its own charge table (A7.4: ROOM none, UNRANKED
   * 2 Koz) whose consumer does not exist yet, so it neither forces KOZ nor
   * adds a charge in S6.
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
    val currentMultiplier: Int? = null,
    val leftUids: List<String> = emptyList(),
    val timeoutRemovedUids: List<String> = emptyList(),
    val removedUids: List<String> = emptyList(),
    val takeoverRemovedUids: List<String> = emptyList(),
  ) {
    /**
     * Every uid the converged document records as departed through any of
     * the four terminal mechanisms — the set the A7.5 override folds to KOZ
     * whatever the seat's bot finished as. `disconnectReplacedUids` is
     * excluded by design (see the class KDoc). The forced KOZ is the ONLY
     * Koz the stats-only settlement records; the separate departure-charge
     * accumulator is deliberately not called on that path, so a later
     * consumer must not add a second Koz for the same event.
     */
    val departedUids: Set<String>
      get() = (leftUids.asSequence() + timeoutRemovedUids.asSequence() +
        removedUids.asSequence() + takeoverRemovedUids.asSequence()).toSet()
  }

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
    /**
     * ROOM/UNRANKED (S6, A7.5/A8.4, Q38/Q46 — superseding RD28's "never
     * settle"): the match settles STATS-ONLY — the five stored career
     * counters per attributed human seat, never RP, never a season, never a
     * correction. The seam routes this to the stats-only transaction in
     * SettlementDb (no recompute, no archive read).
     */
    data class StatsOnly(val mode: MatchMode) : SettleDecision()

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

  /**
   * A departure-provenance uid list: absent or a non-list reads as empty,
   * and a non-string element is skipped (the `stringSet` convention
   * DisconnectVoteFlow already uses for `removedUids`/`timeoutRemovedUids`).
   * Provenance is advisory input to the stats-only override, never a gate —
   * a malformed list must not make a converged match unsettleable.
   */
  fun parseUidList(value: Any?): List<String> =
    (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

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
    val classic = parseScoringMode(fields["scoringMode"]) == ScoringMode.CLASSIC
    // O-19: absent = a document written before the field (legacy fallback:
    // the replayed chain decides). Present must be a legal stored value —
    // an out-of-range integer or a Classic match above 1 is a corrupt
    // document, never silently re-read as 1.
    val currentMultiplier = when (val raw = fields["currentMultiplier"]) {
      null -> null
      else -> {
        val value = num(raw) ?: return null
        if (value < 1 || value > 8) return null
        if (classic && value != 1) return null
        value
      }
    }
    return ConvergedMatch(
      matchId = matchId,
      mode = parseMatchMode(fields["mode"]),
      status = fields["status"] as? String ?: return null,
      order = order,
      players = players,
      dealerSeatFinal = dealerSeatFinal,
      gameType = parseGameType(fields["gameType"]),
      classic = classic,
      completedRound = completedRound,
      maxRounds = maxRounds,
      claimedScores = claimedScores,
      claimedWinners = claimedWinners,
      archives = emptyList(),
      currentMultiplier = currentMultiplier,
      leftUids = parseUidList(fields["leftUids"]),
      timeoutRemovedUids = parseUidList(fields["timeoutRemovedUids"]),
      removedUids = parseUidList(fields["removedUids"]),
      takeoverRemovedUids = parseUidList(fields[TAKEOVER_REMOVED_UIDS_FIELD]),
    )
  }

  /**
   * seat → uid for the converged document, from its `seats` map — the same
   * authority [parseMatch] resolves the dealer marker with. Null when the map
   * is absent or holds an occupant that is not a uid string.
   *
   * RP needs this link and nothing else does: [RecomputedMatch.finalScores] is
   * seat-keyed (the engine seats are p1..p4) while progression is written to
   * `players/{uid}`. `players` is JOIN order, not seat order, so index-
   * correlating the two lists would hand one seat's award to another player.
   *
   * Key order is [DEFAULT_SEATS], not the document's own: a plain JS object's
   * key order would otherwise decide which seat a settlement walks. A seat the
   * document does not name is absent from the result — [parseMatch] builds
   * `order` from those same `seats` keys, so such a seat is already out of the
   * settlement before this fold runs, never a hole it silently skips. A seat
   * the document DOES name with a non-uid occupant refuses the whole map: one
   * untrustable entry makes every seat's link untrustable.
   */
  fun parseSeats(fields: Map<String, Any?>): Map<String, String>? {
    val raw = strMap(fields["seats"]) ?: return null
    val out = LinkedHashMap<String, String>(raw.size)
    for (seat in DEFAULT_SEATS) {
      val value = raw[seat] ?: continue
      out[seat] = value as? String ?: return null
    }
    return out
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
   * Membership + mode gate over the converged document. ROOM and UNRANKED
   * (including an absent-or-legacy mode, which parses as ROOM per S43)
   * settle STATS-ONLY (S6, A7.5/A8.4 / Q38/Q46, superseding RD28's
   * "never settle"): the five stored career counters per attributed human
   * seat — Games Played and one outcome row, with a departed human forced
   * to KOZ — and never RP. RANKED proceeds to the archive recompute.
   */
  fun decide(uid: String, match: ConvergedMatch): SettleDecision {
    if (uid !in match.players) {
      return SettleDecision.Failed(
        Reason.NOT_MEMBER,
        "settleMatch: you are not a player in this match.",
      )
    }
    return when (match.mode) {
      MatchMode.RANKED -> SettleDecision.Proceed
      // RD28 asks every mode switch site for an explicit UNRANKED branch:
      // an UNRANKED completion records the career counters and never RP —
      // the same stats-only answer a ROOM match gets, named apart so that
      // neither mode is forgotten the day UNRANKED gains an outcome of its
      // own.
      MatchMode.UNRANKED -> SettleDecision.StatsOnly(match.mode)
      MatchMode.ROOM -> SettleDecision.StatsOnly(match.mode)
    }
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
    var finalRoundMultiplier: Int? = null
    val escalationCap = if (match.classic) 2 else 8
    var totals: Map<String, Int> = match.order.associateWith { 0 }
    for (round in 1..match.completedRound) {
      val archived = byRound.getValue(round)
      val dealer = dealerSeatForRound(match, round)
        ?: return SettleDecision.Failed(
          Reason.HISTORY_INCOMPLETE,
          "settleMatch: round $round dealer is not resolvable from the match seats.",
        )
      finalRoundMultiplier = multiplier
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

    // O-19: the persisted value is the multiplier of the round current at
    // completion — the same value the replayed chain entered its final
    // round at. A disagreement means the document and the archives tell
    // different stories: report it, never settle on a silent ×1.
    val documented = match.currentMultiplier
    if (documented != null && finalRoundMultiplier != null && documented != finalRoundMultiplier) {
      return SettleDecision.Failed(
        Reason.HISTORY_CORRUPT,
        "settleMatch: currentMultiplier $documented does not match the replayed " +
          "ladder value $finalRoundMultiplier for round ${match.completedRound}.",
      )
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
   * Replays one round's converged history (logged intents, then its archived
   * estimates fed as FinalEstimate intents in engine turn order) through the
   * real bidding engine and, when the round actually produced play, through
   * the real scoring engine. Any engine rejection, any unconsumed archive
   * value, or any malformed trick fails closed (null).
   *
   * S1 (A8.7 / Q51): a round whose auction ALL passed is a pure pass archive
   * — no cards, no estimates, and its only log ends in the pass. The island
   * round is NOT scored (deltas all zero) and returns the doubled multiplier
   * the engine derived for the round following it, so recompute's chain
   * scores that round at ×2 → ×4 → … under Normal and ×1 under Classic. The
   * same-round re-redeal this function performed before S1 is gone: entries
   * beyond the pass in this round's own log belong to no S1 round and fail.
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
    // The engine derives a pass's next-multiplier from the mode (CLASSIC
    // never escalates) — thread the match's own mode, never the default.
    val scoringMode = if (match.classic) ScoringMode.CLASSIC else ScoringMode.NORMAL
    var state = if (fast) {
      initFastRound(archived.round, dealerSeat, order, multiplier, match.gameType, scoringMode)
    } else {
      initNormalRound(archived.round, dealerSeat, order, multiplier, match.gameType, scoringMode)
    }

    var outcome: BiddingOutcome? = null
    var confirmTricks: Int? = null
    val fed = mutableSetOf<String>()
    var index = 0
    while (index < archived.actions.size) {
      val entry = archived.actions[index]
      if (entry.seat !in order) return null
      if (fast && entry.kind == ActionKind.CONFIRM) {
        // A fast Super Call is confirmed AFTER the estimates that triggered
        // it, but only the FINAL estimates survive in the archive. Every
        // higher Super Call logs its own confirmation, so the rest of the
        // log is the chain of cycles: each one wipes every seat except the
        // new Super Caller, who then takes a new trump and every other
        // seat declares again.
        if (state.subPhase != BiddingPhase.ESTIMATES) return null
        val estimates = archived.estimates ?: return null
        val chain = archived.actions.subList(index, archived.actions.size)
        if (chain.any { it.kind != ActionKind.CONFIRM }) return null
        val confirms = chain.map {
          FastSuperConfirm(
            it.seat,
            it.tricks ?: return null,
            it.suit ?: return null,
          )
        }
        // The final Super Call was opened by a declaration ABOVE the
        // previous cycle's confirmed number. The archive keeps one value
        // per seat and the last caller never declares again after its own
        // confirmation, so its archived value IS that declaration.
        if (confirms.size > 1) {
          val declaration = estimates[confirms.last().seat] ?: return null
          if (declaration <= confirms[confirms.size - 2].tricks) return null
        }
        val replayed = replayFastSuperCallChain(state, confirms, estimates) ?: return null
        confirmTricks = replayed.confirmTricks
        outcome = replayed.outcome
        state = replayed.state
        // Only the LAST Super Caller's own declaration is materialised by
        // the chain: every other seat declared again under the final trump
        // and is fed by the estimate loop below. Marking a seat as fed
        // because of its position relative to the caller would leave the
        // round without a declaration it never made.
        fed.add(confirms.last().seat)
        break
      }
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
          // S1 (A8.7 / Q51): the pass CANCELS this round — its archive may
          // carry no cards, no estimates, and nothing after the pass. The
          // round itself is unscored; the next round starts at the doubled
          // multiplier the engine derived (Classic: 1). General passes only
          // leave the auction phase, which fast rounds never enter.
          if (fast) return null
          if (archived.plays.isNotEmpty()) return null
          if (index != archived.actions.size - 1) return null
          if (archived.estimates == null) return null
          if (archived.estimates.isNotEmpty()) return null
          return ScoredRound(order.associateWith { 0 }, emitted.doubledMultiplier)
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
      var guard = 0
      while (guard < order.size + 1) {
        val waiting = state.waitingFor ?: break
        if (waiting !in order) return null
        val estimate = estimates[waiting] ?: return null
        if (!fed.add(waiting)) return null
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
      completed = completed ?: return null
    }
    // Every archived estimate must be consumed exactly once. This covers
    // normal rounds, fast rounds resolved entirely from estimates, and fast
    // Super Calls whose caller-side estimates were materialized before CONFIRM.
    if (estimates != null && fed != estimates.keys) return null

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
