package com.estemshan.functions

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.DisconnectVoteState
import com.estemshan.engine.EmitResult
import com.estemshan.engine.GameType
import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.Play
import com.estemshan.engine.PlayCard
import com.estemshan.engine.PlayEmit
import com.estemshan.engine.RANKS
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.biddingOrder
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
 *
 * Pointer note (S73 Stage 2, C3): the counter landed in Stage 2 and the RD19
 * takeover's read side + clock/marker write landed here — see
 * [inactiveTakeoverSeats], [seatActionCursor] and [inactivityAfterExpiredWindow].
 * "INACTIVE never increments the counter" still holds: it is enforced by
 * [botHeldSeat] suppressing a borrowed seat's expiries.
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

  /**
   * S61 Stage 2 — the validated per-seat timeout count and the removal it
   * mandates.
   *
   * The counter increments ONLY on a real TIMEOUT (§0.5-C: INACTIVE never
   * increments it; Stage 3's takeover shares none of this state). It lives at
   * `matches/{id}/timeoutCounts/{uid}` because the match document is readable
   * by every player (`request.auth.uid in resource.data.players`), so a
   * per-player count can never live there — only an owner-read-only
   * subcollection keeps one player's count from the other three.
   *
   * **Fail-closed validation.** A stored count that is not an Int in
   * [MIN_STORED_TIMEOUT_COUNT]..[REMOVAL_TIMEOUT_THRESHOLD] is untrusted, so
   * [shouldRemove] can only ever fire on a count this process validated
   * itself: the write proceeds (the match must still progress), but the
   * counter is neither incremented nor written, and NO removal happens.
   * Corrupt or malformed state therefore can never trigger a removal.
   *
   * **The removal rule.** [shouldRemove] is true iff the VALIDATED stored
   * count is exactly [REMOVAL_TIMEOUT_THRESHOLD] - 1 — i.e. the increment
   * that is about to be written reaches the threshold. [nextCount] clamps so
   * the stored value can never exceed the threshold either.
   */
  data class TimeoutCounter(
    /** The validated stored count. Always non-null once this object exists —
     *  an untrusted stored value yields a null from [fromStored] instead. */
    val stored: Int,
    /** Whether THIS increment removes the seat. Only true when [stored] sits
     *  exactly one below the threshold. */
    val shouldRemove: Boolean,
  ) {

    /** The count to write back, clamped so it can never exceed the threshold. */
    val nextCount: Int get() = (stored + 1).coerceAtMost(REMOVAL_TIMEOUT_THRESHOLD)

    companion object {
      /**
       * Validates then advances. Returns null when the stored value is
       * PRESENT but unparseable or out of range — the FAIL-CLOSED outcome:
       * the caller applies the decision (the match must still progress) but
       * writes no counter and triggers no removal. A corrupted count
       * therefore disables that seat's removal while the seat keeps
       * resolving through the MEDIUM bot, and no malformed state can ever
       * remove a player.
       *
       * A MISSING counter doc also reads null, but that is the legitimate
       * "no timeouts recorded yet" case, so [raw] null is trusted as 0 —
       * the seam's doc-exists check distinguishes the two, and a seat's
       * first timeout must create the counter.
       */
      fun fromStored(raw: Any?, docExists: Boolean = true): TimeoutCounter? {
        if (!docExists) return TimeoutCounter(stored = 0, shouldRemove = false)
        val stored = (raw as? Number)?.toInt() ?: return null
        if (stored !in MIN_STORED_TIMEOUT_COUNT..REMOVAL_TIMEOUT_THRESHOLD) return null
        return TimeoutCounter(
          stored = stored,
          shouldRemove = stored == REMOVAL_TIMEOUT_THRESHOLD - 1,
        )
      }
    }
  }

  /**
   * The `timeoutRemovedUids` write for a removal: appends [uid] to the
   * mechanism's own list, distinct. The ONLY field the timeout mechanism
   * writes for a removal — never `botSeats`, never Vote Kick's `removedUids`.
   */
  fun removalWrite(fields: Map<String, Any?>, uid: String): List<String> =
    (timeoutRemovedUids(fields) + uid).distinct()

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

  /**
   * S61 Stage 2: a seat that is already permanently bot-driven does not make
   * the table wait the full human window. The deadline the resolver derives
   * shortens to this for bot-controlled seats, so a timeout-removed (or
   * Vote-Kick-removed) seat plays promptly instead of stalling the match for
   * `decisionTimerSeconds` on every subsequent decision. Server-authoritative
   * — the anchor is still `updatedAt`, only the derived duration changes, so
   * the Stage 1 idempotency proof (the write re-stamps `updatedAt`, the next
   * caller re-anchors past it) is untouched.
   */
  const val BOT_DECISION_SECONDS = 2

  /** The timeout count at which a seat is permanently removed (§0.5-C). */
  const val REMOVAL_TIMEOUT_THRESHOLD = 15

  /** The lower bound a stored timeout count must satisfy to be trusted. */
  const val MIN_STORED_TIMEOUT_COUNT = 0

  /**
   * S73 (RD19): the lower bound a stored seat-action cursor must satisfy to be
   * trusted. Deliberately 0 rather than 1 — the cursor is 0 for a real seat
   * (round 1 before the seat has committed anything), and rejecting it would
   * make the very first window unrecordable.
   */
  const val MIN_STORED_SEAT_CURSOR = 0L

  /** `timeoutCounts/{uid}`'s single field. */
  const val COUNT_FIELD = "count"

  /**
   * The floor for [millisUntilDeadline]. A deadline that has just passed (or a
   * clock that ticked over) must not hand back a zero/negative delay the
   * client could spin on — a controlled retry floor, not a tight loop.
   */
  const val MIN_RETRY_AFTER_MILLIS = 250L

  /**
   * S61 Stage 2: the seats a bot drives, for EITHER reason — Vote Kick's
   * `botSeats`, or a seat whose uid the 15-timeout counter removed. Mirrors
   * MatchDoc.botControlledSeats() read-side-only: the two mechanisms keep
   * separate persisted lists and this never writes either.
   */
  fun botControlledSeats(fields: Map<String, Any?>): Set<String> {
    val botSeats = (fields["botSeats"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
    val timeoutRemoved = timeoutRemovedUids(fields)
    val seats = strMap(fields["seats"]) ?: return botSeats.toSet()
    return botSeats.toSet() +
      seats.entries.mapNotNull { (seat, uid) ->
        if (uid in timeoutRemoved) seat else null
      }
  }

  /** The timeout mechanism's own persisted removal list (never Vote Kick's). */
  fun timeoutRemovedUids(fields: Map<String, Any?>): Set<String> =
    ((fields["timeoutRemovedUids"] as? List<*>)?.mapNotNull { it as? String }).orEmpty().toSet()

  // ── S73 (RD19): the inactivity clock, its cursor, and the takeover ────

  /**
   * The seat-action cursor's round term. `advanceToNextRound` prunes both logs
   * and clears `bids` in the same transaction that increments `currentRound`,
   * so the round term is what keeps the cursor monotone across a round
   * boundary even though the per-round terms restart at zero.
   */
  private const val CURSOR_ROUND_STRIDE = 1_000_000L

  /** The cursor's per-card term — a seat plays at most one card per trick. */
  private const val CURSOR_CARD_STRIDE = 1_000L

  /** One logged bidding action contributes one unit; a committed estimate the
   *  same. Both stay far below [CURSOR_CARD_STRIDE], so the terms cannot carry. */
  private const val CURSOR_BIDDING_STRIDE = 1L
  private const val CURSOR_COMMITTED_BID = 1L

  /**
   * S73 (RD19): the seats the takeover currently BORROWS. Read-side only, and
   * deliberately NOT folded into [botControlledSeats] — the mirror of
   * MatchDoc.botControlledSeats() stays Vote Kick + timeout removal, because a
   * borrowed seat returns to the same player in the same seat (DEC-RD19-04) and
   * must therefore keep its Vote Kick slot and stay kickable.
   *
   * A malformed entry parses as "not borrowed" ([InactiveTakeover.parseAll]
   * drops it): corrupt takeover state must never strand a human out of their own
   * seat, and it must never hand a bot one either. The seat's own clock then
   * keeps accumulating and a genuine takeover overwrites the garbage.
   */
  fun inactiveTakeoverSeats(fields: Map<String, Any?>): Set<String> =
    InactiveTakeover.takeoverSeats(fields[InactiveTakeover.TAKEOVER_FIELD])

  /**
   * S74 — the seats the disconnect vote is holding RIGHT NOW, every phase of
   * it: `HOLD` (detected, queued for the ballot), `VOTE` (the 30 s ballot
   * open), `WAIT` (the granted pause) and `REPLACED` (the waiting ended; the
   * seat is the configured bot's for the rest of the match). From the instant
   * of detection the seat is bot-played, so this is the read every pacing
   * site consults — and, like [inactiveTakeoverSeats], it is READ-SIDE ONLY
   * and deliberately NOT folded into [botControlledSeats]: the mirror of
   * MatchDoc.botControlledSeats() stays Vote Kick + timeout removal, and a
   * disconnect-held seat keeps its Vote Kick slot exactly as a borrowed one
   * does.
   *
   * Malformed entries parse as "not held" ([DisconnectVoteState.parseAll]
   * drops them, fail-closed); the resolver re-detects a genuinely dropped
   * player on its next pass. Gated on the mode the same way every S74 write
   * is: a RANKED document never asks the question, so even a tampered marker
   * left on one can change no seat's pace there (the RD28 canary lives in
   * [DisconnectVoteFlow.modeEligible], consulted once, here).
   */
  fun disconnectHeldSeats(fields: Map<String, Any?>): Set<String> =
    if (!DisconnectVoteFlow.modeEligible(fields)) {
      emptySet()
    } else {
      DisconnectVoteState.heldSeats(fields[DisconnectVoteState.VOTE_FIELD])
    }

  /**
   * S74 — the pause a granted `WAIT` window is running, in millis remaining,
   * or null when no pause is live. A winning WAIT stops the table for a
   * bounded 90 seconds BEGINNING AFTER the verdict (A5.3 item 4): during that
   * window no decision resolves and no deadline runs, because the pause is
   * "the table waits for its player". The bound is the entry's own
   * [DisconnectVoteState.DEADLINE_FIELD] — a server stamp the resolver wrote —
   * and an expired pause returns null the moment it is overdue: the pause is
   * bounded, never indefinite, even if no one has flipped the marker to
   * `REPLACED` yet.
   */
  fun disconnectPauseRemaining(fields: Map<String, Any?>, nowMillis: Long): Long? {
    if (!DisconnectVoteFlow.modeEligible(fields)) return null
    val deadline = DisconnectVoteState.waitDeadlineMillis(
      fields[DisconnectVoteState.VOTE_FIELD],
    ) ?: return null
    val remaining = deadline - nowMillis
    if (remaining <= 0L) return null
    return remaining.coerceAtLeast(DisconnectVoteFlow.MIN_RETRY_AFTER_MILLIS)
  }

  /**
   * Whether [seatId] is being driven by a bot right now, for any reason:
   * permanently ([botControlledSeats]), borrowed ([inactiveTakeoverSeats]), or
   * held by the S74 disconnect vote ([disconnectHeldSeats]). All of them
   * shorten the window to [BOT_DECISION_SECONDS] and none may increment the
   * timeout counter; only the permanence differs, and the disconnect flow is
   * the one billed as reclaimable while the table waits.
   */
  private fun botHeldSeat(fields: Map<String, Any?>, seatId: String): Boolean =
    seatId in botControlledSeats(fields) ||
      seatId in inactiveTakeoverSeats(fields) ||
      seatId in disconnectHeldSeats(fields)

  /** The bot that plays one expired decision. [tier] is what the card path
   *  takes ([PlayBrain.decide] has no personality parameter); the bidding path
   *  takes both. */
  data class BotProfile(
    val tier: BotTier,
    val personality: BotPersonality,
  )

  /**
   * S73 (RD19, the fork-1 amendment): WHICH bot plays [seatId]'s expired
   * decision.
   *
   * A **borrowed** seat plays the table's CONFIGURED difficulty. The takeover is
   * the human's own seat played on their behalf, and the host picked that
   * difficulty for every seat at this table — an EXPERT table whose absent player
   * is suddenly played by a MEDIUM bot is a different game, and the seat's owner
   * returns to the middle of it.
   *
   * Everything else keeps RD20's pin, byte-identical to Stage 1: a permanently
   * bot-controlled seat (`botSeats`, and the 15-timeout removals S61 shipped) is
   * played by the brain the room created it with, never through here, and a seat
   * with no takeover marker — a human whose own timer expired — stays
   * MEDIUM/[BotPersonality.DEFAULT]. So no non-S73 path changes value, and the
   * only documents that can produce a different tier are exactly the ones
   * carrying a marker the resolver itself wrote.
   *
   * The configured pair is read through the `RoomDoc` fallbacks
   * ([parseBotTier]/[parseBotPersonality]): a match created before the trio's
   * persistence, or a value no client could have written, resolves to
   * MEDIUM/BALANCED rather than throwing or guessing.
   *
   * S74 adds the third reason the configured pair is used: a disconnect-held
   * seat (DEC-DISC-01's configured-bot default — \"no agreement inside 30 s
   * continues with the configured bot\", A5.2:434). Same seat-played-on-its-
   * owner's-behalf logic as the borrowed half, same fallbacks.
   */
  fun botProfileFor(fields: Map<String, Any?>, seatId: String): BotProfile =
    if (seatId in inactiveTakeoverSeats(fields) || seatId in disconnectHeldSeats(fields)) {
      BotProfile(
        tier = parseBotTier(fields["botTier"]),
        personality = parseBotPersonality(fields["botPersonality"]),
      )
    } else {
      BotProfile(tier = BotTier.MEDIUM, personality = BotPersonality.DEFAULT)
    }

  /** One log entry's (seat, round), or null when the entry cannot be attributed. */
  private fun entrySeatAndRound(value: Any?): Pair<String, Int>? {
    val map = strMap(value) ?: return null
    val seat = map["seatId"] as? String ?: return null
    val round = num(map["round"]) ?: return null
    return seat to round
  }

  /**
   * RD19's reset input: [seatId]'s own committed-action count in the current
   * round, carried forward by the round term. This is what lets a server that
   * is invoked ONLY on expiry (it never sees a window open, and never sees an
   * answered one) detect "the player acted inside their own window": the report
   * IS the move, already on the document, so no client ever writes toward RD19
   * and no permission is widened.
   *
   * The committed estimate is part of the count because a fast round's ONLY
   * bidding action is `submitBid`, which writes `bids/{seat}` and no log entry —
   * leaving it out would make an on-time estimate invisible to the cursor and
   * take over a player who was answering every window. It can decrease (round
   * advance, general-pass redeal), and a change in either direction resets, so
   * the failure mode of a decrease is a reset the rule would have granted anyway
   * — never a false takeover. Biasing every judgement here toward resetting is
   * the fail-closed direction: a wrong reset delays a takeover, a wrong
   * non-reset takes a present player out of their seat.
   *
   * Null when the document cannot be read: an entry that is not a
   * (seatId, round) pair could be this seat's unlooked-for action, so no cursor
   * — and therefore no clock advance and no takeover — is derived from it.
   */
  fun seatActionCursor(fields: Map<String, Any?>, seatId: String): Long? {
    val round = num(fields["currentRound"]) ?: return null
    var cards = 0L
    for (entry in (fields["cardLog"] as? List<*>).orEmpty()) {
      val attributed = entrySeatAndRound(entry) ?: return null
      if (attributed.first == seatId && attributed.second == round) cards++
    }
    var actions = 0L
    for (entry in (fields["biddingLog"] as? List<*>).orEmpty()) {
      val attributed = entrySeatAndRound(entry) ?: return null
      if (attributed.first == seatId && attributed.second == round) actions++
    }
    val committed = if (strMap(fields["bids"])?.get(seatId) is Number) CURSOR_COMMITTED_BID else 0L
    return round * CURSOR_ROUND_STRIDE + cards * CURSOR_CARD_STRIDE + actions + committed
  }

  /** Cards committed in [round], for the trick a takeover is stamped with. */
  private fun roundCardCount(fields: Map<String, Any?>, round: Int): Int? {
    var count = 0
    for (entry in (fields["cardLog"] as? List<*>).orEmpty()) {
      val attributed = entrySeatAndRound(entry) ?: return null
      if (attributed.second == round) count++
    }
    return count
  }

  /**
   * The cursor projected over [write] — the resolver's own decision is appended
   * to the same logs the cursor counts, and it is always attributed to this
   * seat, so persisting the un-projected count would make the bot's move look
   * like the human's next expiry did act. Every false reset in one write.
   */
  private fun cursorAfterWrite(
    fields: Map<String, Any?>,
    seatId: String,
    write: DecisionWrite,
    cursorNow: Long,
  ): Long = when {
    "biddingLog" in write.updates -> cursorNow + CURSOR_BIDDING_STRIDE
    "cardLog" in write.updates -> cursorNow + CURSOR_CARD_STRIDE
    else -> {
      val justCommitted = (strMap(fields["bids"])?.get(seatId) !is Number) &&
        (strMap(write.updates["bids"])?.get(seatId) is Number)
      if (justCommitted) cursorNow + CURSOR_COMMITTED_BID else cursorNow
    }
  }

  /**
   * The whole state of one seat's inactivity after an expired window: the three
   * fields of the private `matches/{id}/inactivity/{uid}` document, plus the
   * marker when the accumulation crossed RD19's threshold.
   */
  data class InactivityUpdate(
    /** The COMPLETE private document — [InactivityClock.storedFieldNames] plus
     *  [InactiveTakeover.CURSOR_FIELD]. Written as a replacement, never a
     *  merge, because these three are the only state this mechanism owns. */
    val docFields: Map<String, Any?>,
    /** The takeover this window earned, or null. Its marker entry is merged
     *  into the match document by the caller; the entry's own neighbours are
     *  carried verbatim by [InactiveTakeover.withTakeover]. */
    val takeover: InactiveTakeover?,
  )

  /**
   * Advances RD19's clock for the seat whose window just expired.
   *
   * The sequencing is the whole of DEC-RD19-01 as Stage 1 closed it: an
   * ordinary expiry does NOT reset the clock (the bot's move substitutes for the
   * player, it is not evidence of them), a genuine interaction inside one of the
   * player's own windows DOES, and the accumulation is checked against the 50 s
   * threshold only after that. A closed window contributes at most its own
   * length, so a trigger that fires late — or a player who wandered off after
   * the deadline — cannot manufacture time.
   *
   * Returns null — write NOTHING, take nobody over — when the state cannot be
   * trusted: the seat has no uid to key the document by, the seat is already
   * bot-driven or already borrowed (a borrowed seat's clock stopped accruing the
   * moment it was taken over; reclaim owns restarting it), the logs will not
   * yield a cursor, or the stored clock is present-but-invalid. [InactivityClock.fromStored]
   * is what makes the last of those a fail-closed read rather than a fresh zero.
   *
   * A takeover zeroes the clock on purpose, and it is load-bearing: an
   * accumulation at or above the threshold is one `fromStored` rejects, so
   * leaving 50 s on the document would kill the clock for that seat permanently
   * and leave it neither re-takeover-able nor able to earn a second one.
   */
  fun inactivityAfterExpiredWindow(
    fields: Map<String, Any?>,
    pending: Pending,
    seatUid: String?,
    storedDoc: Map<String, Any?>?,
    nowMillis: Long,
    write: DecisionWrite,
  ): InactivityUpdate? {
    val seatId = pending.seatId
    if (seatUid == null) return null
    if (botHeldSeat(fields, seatId)) return null
    val round = num(fields["currentRound"]) ?: return null
    val cursorNow = seatActionCursor(fields, seatId) ?: return null
    val clock = InactivityClock.fromStored(
      accumulatedRaw = storedDoc?.get(InactivityClock.ACCUMULATED_FIELD),
      windowStartRaw = storedDoc?.get(InactivityClock.OPEN_WINDOW_FIELD),
      docExists = storedDoc != null,
    ) ?: return null

    // A cursor the mechanism never wrote is not state to advance from; a cursor
    // that was never written at all is the seat's first window, so it only means
    // "nothing to compare yet" and cannot reset.
    val rawCursor = storedDoc?.get(InactiveTakeover.CURSOR_FIELD)
    val storedCursor = if (rawCursor == null) null else {
      val number = rawCursor as? Number ?: return null
      val value = number.toLong()
      if (value.toDouble() != number.toDouble() || value < MIN_STORED_SEAT_CURSOR) return null
      value
    }
    val acted = storedCursor != null && storedCursor != cursorNow

    val advanced = if (acted) {
      InactivityClock()
    } else {
      val durationMillis = pending.durationSeconds.toLong() * MILLIS_PER_SECOND
      // A stored open window is an anchor left by a resolution that died before
      // its write; the only window that can be named now is this one, so it
      // closes at this one's length rather than nesting a second anchor.
      val opened = if (clock.openWindowStartMillis != null) clock
      else clock.windowOpened(pending.anchorMillis)
      opened.windowClosed(nowMillis, durationMillis)
    }

    val takeover = if (advanced.takeoverEligibleAt(nowMillis)) {
      val cards = roundCardCount(fields, round) ?: return null
      InactiveTakeover(
        takenOverAtMillis = nowMillis,
        roundAtTakeover = round,
        // The trick as the document has it BEFORE this write, so a takeover
        // struck mid-trick is bounded by that trick finishing (DEC-RD19-04) and
        // not by the one the bot's own card may have just completed.
        trickAtTakeover = InactiveTakeover.trickNo(cards),
      )
    } else {
      null
    }

    val persisted = if (takeover != null) InactivityClock() else advanced
    return InactivityUpdate(
      docFields = persisted.storedFields() +
        (InactiveTakeover.CURSOR_FIELD to cursorAfterWrite(fields, seatId, write, cursorNow)),
      takeover = takeover,
    )
  }

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

  /** Mirrors RoomDoc.parseBotTier: absent/unrecognised ⇒ MEDIUM. The rules
   *  layer refuses an unknown name, so a read that cannot map one is a document
   *  from before the trio's persistence, not a difficulty nobody chose. */
  fun parseBotTier(value: Any?): BotTier =
    (value as? String)?.let { runCatching { BotTier.valueOf(it) }.getOrNull() }
      ?: BotTier.MEDIUM

  /** Mirrors RoomDoc.parseBotPersonality: absent/unrecognised ⇒ BALANCED
   *  (RD21's default) — the value the client would have shown in the lobby. */
  fun parseBotPersonality(value: Any?): BotPersonality =
    (value as? String)?.let { runCatching { BotPersonality.valueOf(it) }.getOrNull() }
      ?: BotPersonality.BALANCED

  /** The doc's `updatedAt` as epoch millis (see JsBridging.timestampToMillis). */
  fun parseUpdatedAtMillis(fields: Map<String, Any?>): Long? =
    timestampToMillis(fields["updatedAt"])

  /** Canonical seat order for the seats actually present (Settlement's rule). */
  fun parseOrder(fields: Map<String, Any?>): List<String>? {
    val seats = strMap(fields["seats"]) ?: return null
    val order = DEFAULT_SEATS.filter { seats.containsKey(it) }
    return if (order.isEmpty()) null else order
  }

  /**
   * S61 Stage 2 — the membership gate over a LIVE match document.
   *
   * Settlement.parseMatch is the wrong gate here: it requires `completedRound`,
   * a key only endMatch ever writes (MatchService.kt:737), so every mid-match
   * document — exactly the documents a timeout resolves — fails it and the
   * callable answers failed-precondition. Settlement needs that field for its
   * converged-history replay; the resolver needs only the caller's membership.
   * This parse takes what a live doc actually carries from buildMatchFields: a
   * non-empty `players` list of uids and a `status`.
   */
  fun parsePlayers(fields: Map<String, Any?>): List<String>? {
    if (fields["status"] as? String == null) return null
    val players = (fields["players"] as? List<*>) ?: return null
    return players.map { it as? String ?: return null }.takeIf { it.isNotEmpty() }
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
   *
   * The replay is the authority on which phase is live. `biddingOpen` only says
   * where to look first, so a flag left true after the auction — which is what
   * every Dash-Caller round carries — cannot leave a dealt round unowned.
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
      if (state.subPhase != BiddingPhase.DONE) {
        val waiting = state.waitingFor ?: return null
        if (waiting !in order) return null
        return Pending(
          phase = DecisionPhase.BIDDING,
          seatId = waiting,
          durationSeconds = durationFor(fields, waiting, timerSeconds, DecisionPhase.BIDDING),
          anchorMillis = anchor,
        )
      }
      // The flag is a write-shape artifact, not the auction's authority: both
      // writers derive it from "every seat has a bid", and a Dash Caller never
      // submits an estimate, so it outlives the auction on every round that has
      // one — a derivation `firestore.rules:799` pins for client writes. When the
      // replay says DONE the round is playing cards, so fall through below rather
      // than leaving the table with no timed decision at all: that silence stops
      // RD20's card timeouts and RD19's takeover from ever firing.
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
      durationSeconds = durationFor(fields, seat, timerSeconds, DecisionPhase.CARD),
      anchorMillis = anchor,
    )
  }

  /**
   * S61 Stage 2: the window for [seatId]'s decision. A bot-driven seat gets
   * [BOT_DECISION_SECONDS] — a permanently bot-held seat should not stall the
   * table through the full human timer on every later decision — while a
   * human seat keeps the RD21 phase duration. Server-authoritative either
   * way: only the derived duration moves, never the `updatedAt` anchor.
   *
   * S73 (RD19) extends the short window to a BORROWED seat, which is what the
   * owner's fork settles: the fixed 2 s pace belongs to any bot at the table,
   * whichever path put it there, and the configurable timer is for humans only.
   * A seat with no marker therefore keeps this expression's Stage-2 value,
   * byte for byte.
   */
  private fun durationFor(
    fields: Map<String, Any?>,
    seatId: String,
    timerSeconds: Int,
    phase: DecisionPhase,
  ): Int =
    if (botHeldSeat(fields, seatId)) BOT_DECISION_SECONDS
    else phase.durationSeconds(timerSeconds)

  /** True iff [nowMillis] is at or past the pending decision's derived deadline. */
  fun isExpired(pending: Pending, nowMillis: Long): Boolean =
    nowMillis >= pending.anchorMillis + pending.durationSeconds * MILLIS_PER_SECOND

  /**
   * S61 Stage 2: the server-authoritative milliseconds until [pending]'s
   * deadline, from the SAME server clock that judges expiry. This is what the
   * client re-arms its alarm on when it called early and received
   * NOT_YET_EXPIRED — it never re-arms on its own estimate, because
   * `updatedAt` is not on `MatchDoc` and the client cannot know the anchor.
   * Floored so a just-expired deadline cannot hand back a negative delay.
   */
  fun millisUntilDeadline(pending: Pending, nowMillis: Long): Long =
    (pending.anchorMillis + pending.durationSeconds * MILLIS_PER_SECOND - nowMillis)
      .coerceAtLeast(MIN_RETRY_AFTER_MILLIS)

  // ── reconstruction + decision ────────────────────────────────────────

  /**
   * Rebuilds the acting seat's engine state from the document and asks its
   * [botProfileFor] bot for the one decision the timer expired on. The bot's
   * intent is re-validated by the brain itself before any write is built (BidBrain and
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

  // ── bidding: replay the auction, then BidBrain ───────────────────────

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

    val stored = strMap(fields["bids"])?.mapNotNull { (seat, value) ->
      num(value)?.let { seat to it }
    }?.toMap().orEmpty()

    val logged = loggedIntents(fields) ?: return BiddingReplay(null, null)
    for (entry in logged) {
      if (entry.seat !in order) return BiddingReplay(null, null)
      if (fast && entry is LoggedIntent.Confirm &&
        state.subPhase == BiddingPhase.ESTIMATES
      ) {
        // Fast Super Call. A live document keeps each WIPED seat's
        // PRE-confirm estimate in bids/: submitBid is one value per seat and
        // CONFIRM does not reopen bidding (MatchService.submitBid;
        // firestore.rules), so there is no authoritative post-confirm value
        // to feed. Only a caller first in bidding order completes at CONFIRM
        // without a re-declaration; anything else fails closed rather than
        // replaying a stale number as if it were final. Archives settle
        // through Settlement.scoreRound, where the values are final.
        if (biddingOrder(state).indexOf(entry.seat) > 0) {
          return BiddingReplay(null, null)
        }
        when (val emitted = replayFastSuperConfirm(
          state, entry.seat, entry.tricks, entry.suit, stored,
        )) {
          is EmitResult.Completed -> return BiddingReplay(emitted.state, emitted.outcome)
          is EmitResult.GeneralPass -> return BiddingReplay(null, null)
          is EmitResult.Applied -> {
            state = emitted.state
            continue
          }
          is EmitResult.Rejected -> return BiddingReplay(null, null)
        }
      }
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

  /** Runs the seat's [botProfileFor] bot and builds the write the human path
   *  would make. */
  private fun decideBidding(
    fields: Map<String, Any?>,
    order: List<String>,
    state: BiddingState,
    seatId: String,
    hand: List<Card>,
  ): Resolution {
    val profile = botProfileFor(fields, seatId)
    val intent = try {
      BidBrain.decide(state, hand, seatId, profile.tier, profile.personality)
    } catch (e: Throwable) {
      return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: the bot could not decide for seat " +
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
            "botTier" to profile.tier.name,
          ),
        ))
      }
      is BiddingIntent.DashCallDecision -> biddingWrite(
        fields, loggedDashCall(seatId, state.round, intent.declaredDashCall),
        mapOf(
          "phase" to state.subPhase.name,
          "seatId" to seatId,
          "declaredDashCall" to intent.declaredDashCall,
          "botTier" to profile.tier.name,
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
          "botTier" to profile.tier.name,
        ),
      )
      is BiddingIntent.ConfirmCall -> biddingWrite(
        fields, loggedConfirmCall(seatId, state.round, intent),
        mapOf(
          "phase" to state.subPhase.name,
          "seatId" to seatId,
          "tricks" to intent.tricks,
          "suit" to intent.suit.name,
          "botTier" to profile.tier.name,
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
      // toPlain at construction, not at updateMatch: the arrayUnion sentinel
      // passes toPlain by reference, so a Kotlin map handed to it here keeps
      // its custom prototype all the way to Firestore, which rejects it with
      // "Firestore doesn't support JavaScript objects with custom prototypes".
      "biddingLog" to FieldValue.arrayUnion(toPlain(entry)),
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

  /** Runs the seat's [botProfileFor] tier and builds submitCard's own write shape. */
  private fun decideCard(
    fields: Map<String, Any?>,
    table: TableState,
    seatId: String,
  ): Resolution {
    val profile = botProfileFor(fields, seatId)
    val play = try {
      PlayBrain.decide(table, seatId, profile.tier)
    } catch (e: Throwable) {
      return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: the bot could not play for seat " +
          "'$seatId' — the reconstructed table disagrees with the document.",
      )
    }
    val preview = previewPlay(table, seatId, play.card)
    if (!preview.legal) {
      return Resolution.NoWrite(
        Reason.HISTORY_UNAVAILABLE,
        "resolveDecisionTimeout: the bot's card was rejected for seat '$seatId'.",
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
        // See biddingWrite: the entry must be plain JS before arrayUnion.
        "cardLog" to FieldValue.arrayUnion(toPlain(entry)),
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
        "botTier" to profile.tier.name,
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

  /**
   * The uid occupying [seatId], or null when the seat is empty. Public for the
   * counter: `timeoutCounts/{uid}` is keyed by the PLAYER, and the seats map
   * is RETAINED on removal (S60 writes only `botSeats`/`removedUids`), so a
   * seat whose uid `isSeated()` no longer admits still resolves to its uid
   * here — that is how the counter keeps counting for the right player after
   * a removal, and how a Vote-Kick-removed seat is recognized as already
   * bot-controlled.
   */
  fun seatUid(fields: Map<String, Any?>, seatId: String): String? =
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
