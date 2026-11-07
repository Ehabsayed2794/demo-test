package com.estemshan.engine

import kotlin.math.floor

/**
 * S73 Stage 2 — the persisted SHAPE of an RD19 inactivity takeover: the public
 * marker on the match document, the private `inactivity/{uid}` document's field
 * names, and the read that turns the marker back into a [SeatControl].
 *
 * Pure shape + parsing only, exactly like [InactivityClock]: no Firestore, no
 * clock source, no bot. It lives in `:engine` because BOTH authority sites need
 * the same names without a mirror — the Cloud Functions resolver that WRITES the
 * marker (`:functions` depends on `:engine` and cannot see `:services`) and the
 * client that READS it to draw S39's INACTIVE seat chip and return-to-seat
 * banner (`:app` → `:services` → `:engine`). RD21's timer bounds had to be
 * mirrored into `TimeoutResolution` for exactly this reason; these names are not
 * going to be.
 *
 * **Why one public field and one private document.** The frozen `firestore.rules`
 * cannot be widened for S73 (`docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`: "no Firestore
 * rules revision — `firestore.rules` stays frozen and byte-identical"), and the
 * Admin SDK bypasses rules altogether, which is how S61's `timeoutRemovedUids`
 * works with ZERO occurrences in the rules file. An admin-written field is
 * therefore readable by every seat (the match document's whole-document
 * `allow get`) and unwritable by every client (no validator names it, so the
 * update dispatch falls through to `false`). The public half is exactly what the
 * chip needs and no more; the accumulator, its window anchor and the streak
 * cursor stay in a subcollection no client can read or write — the same
 * privacy rule that keeps `matches/{id}/timeoutCounts/{uid}` off the match
 * document, because "how long was X ignoring the table" is not X's opponents'
 * business either.
 *
 * **Why `trickFinished` is derived and never stored.** DEC-RD19-04 closes
 * reclaim as "the bot finishes the **current trick**, control returns at the
 * **next decision point**, in the **same seat**". Which trick is in progress is
 * already committed state on the match document, so persisting a boolean would
 * create a second copy of a fact the document owns, free to drift from it.
 * [trickNo] + [seatTrickKey] re-derive it instead, and the flag [SeatControl]
 * carries is computed at read time — so [SeatControl.markTrickFinished] and
 * [SeatControl.reclaim]'s guard stay exactly as Stage 1 wrote them, and stay
 * tested, while nothing here ever writes a `trickFinished` field.
 *
 * **Fail-closed, and in which direction.** A value that is present but
 * untrustworthy yields no takeover and no borrowed seat, the way
 * [InactivityClock.fromStored] yields null: corrupt state must never strand a
 * human out of their own seat, and it must never hand a bot one either. A
 * rejected entry is therefore read as `Human` while the seat's own clock still
 * refuses to advance, so the next genuine accumulation re-takes the seat over
 * and overwrites the garbage with a well-formed entry.
 *
 * **Deliberately out of scope:** the takeover WRITE and its configured
 * tier/personality (C7), the reclaim callable (C4), the cursor's derivation from
 * the action logs (C3 — [CURSOR_FIELD] only names where the result is stored),
 * the 15-timeout removal (S61, shipped), the disconnect flow (S74), and RD31's
 * 2-Koz departure record (OPEN-4). A borrowed seat is NOT a departure and this
 * type owns no field that could make it one — see [entryFieldNames].
 */
data class InactiveTakeover(
  /** Server-clock millis at which the bot took the seat over. Re-stamped only by a real takeover. */
  val takenOverAtMillis: Long,
  /** The round in progress at takeover — the first half of the derived trick bound. */
  val roundAtTakeover: Int,
  /** The trick in progress at takeover (0 while the auction is still open). */
  val trickAtTakeover: Int,
) {

  fun toFields(): Map<String, Any?> = linkedMapOf(
    TAKEN_OVER_AT_FIELD to takenOverAtMillis,
    ROUND_AT_TAKEOVER_FIELD to roundAtTakeover,
    TRICK_AT_TAKEOVER_FIELD to trickAtTakeover,
  )

  companion object {

    /**
     * `matches/{matchId}.inactiveTakeovers` — the one public field: a map of
     * seat id to the entry above, absent while nobody's seat is borrowed. Set by
     * the resolver on a takeover, cleared by reclaim.
     */
    const val TAKEOVER_FIELD: String = "inactiveTakeovers"

    /** `matches/{matchId}/inactivity/{uid}` — the private per-player document. */
    const val INACTIVITY_COLLECTION: String = "inactivity"

    /**
     * The streak cursor: the seat's own committed-action count as of the last
     * time the resolver looked. This is what lets a server that is invoked only
     * on expiry detect DEC-RD19-01's "genuine interaction inside the player's own
     * window" — the report is the move itself, so no client ever writes toward
     * RD19 and no permission is widened.
     */
    const val CURSOR_FIELD: String = "inactiveSeatActionCursor"

    /** The three fields of the private document, in order. */
    fun inactivityDocFieldNames(): List<String> =
      InactivityClock.storedFieldNames() + CURSOR_FIELD

    const val TAKEN_OVER_AT_FIELD: String = "takenOverAtMillis"
    const val ROUND_AT_TAKEOVER_FIELD: String = "roundAtTakeover"
    const val TRICK_AT_TAKEOVER_FIELD: String = "trickAtTakeover"

    /** The marker entry's fields, in order. */
    fun entryFieldNames(): List<String> =
      listOf(TAKEN_OVER_AT_FIELD, ROUND_AT_TAKEOVER_FIELD, TRICK_AT_TAKEOVER_FIELD)

    /**
     * A round is 13 tricks of 4 cards — the 52-card deal, not a new rule: it is
     * the same structural invariant `MatchDoc.roundCardCount` uses to recognise a
     * finished round. It bounds [trickAtTakeover] here so a corrupt entry cannot
     * describe a trick that can never finish and strand its owner out of the seat.
     */
    const val TRICKS_PER_ROUND: Int = 13

    /** Cards played per trick — the divisor in [trickNo]. */
    const val CARDS_PER_TRICK: Int = 4

    /**
     * The trick index in progress after [cardsPlayedInRound] cards of the current
     * round: 0 before the first card is played (which includes the whole auction),
     * 1 once the opening trick is complete, and so on.
     */
    fun trickNo(cardsPlayedInRound: Int): Int = cardsPlayedInRound / CARDS_PER_TRICK

    /**
     * A total order over the round/trick pair, so "the trick that was in progress
     * at takeover has since finished" is one comparison. `Long` so any round number
     * is safe: 13 tricks per round keeps the low two digits free by construction.
     */
    fun seatTrickKey(round: Int, trickNo: Int): Long = round.toLong() * 100L + trickNo.toLong()

    /**
     * One trusted marker entry, or null when it is absent OR untrustworthy: a
     * non-map, a missing or fractional or negative millis, a round below 1, a
     * trick outside the round's 13, or a value the transport cannot hand over as a
     * whole number.
     */
    fun parse(raw: Any?): InactiveTakeover? {
      val entry = raw as? Map<*, *> ?: return null
      val takenOverAt = asWholeLong(entry[TAKEN_OVER_AT_FIELD]) ?: return null
      if (takenOverAt < 0L) return null
      val round = asWholeLong(entry[ROUND_AT_TAKEOVER_FIELD]) ?: return null
      if (round < 1L || round > Int.MAX_VALUE.toLong()) return null
      val trick = asWholeLong(entry[TRICK_AT_TAKEOVER_FIELD]) ?: return null
      if (trick < 0L || trick >= TRICKS_PER_ROUND.toLong()) return null
      return InactiveTakeover(
        takenOverAtMillis = takenOverAt,
        roundAtTakeover = round.toInt(),
        trickAtTakeover = trick.toInt(),
      )
    }

    /** Trusted entries of the whole map; a non-map is "nobody's seat is borrowed". */
    fun parseAll(takeoverRaw: Any?): Map<String, InactiveTakeover> {
      val map = takeoverRaw as? Map<*, *> ?: return emptyMap()
      val out = LinkedHashMap<String, InactiveTakeover>()
      for ((seatId, raw) in map) {
        val key = seatId as? String ?: continue
        parse(raw)?.let { out[key] = it }
      }
      return out
    }

    /** The borrowed seats, for the chip and for the 2 s pace. */
    fun takeoverSeats(takeoverRaw: Any?): Set<String> = parseAll(takeoverRaw).keys

    /**
     * The write that borrows [seatId]: the target's entry replaced, every other
     * entry carried over VERBATIM. Surgical on purpose — this type owns the
     * target's shape, not the neighbours', so a write can neither interpret nor
     * silently discard state it did not author.
     */
    fun withTakeover(takeoverRaw: Any?, seatId: String, takeover: InactiveTakeover): Map<String, Any?> =
      copyEntries(takeoverRaw).apply { put(seatId, takeover.toFields()) }

    /**
     * The write that returns [seatId]: its entry removed, every other entry
     * carried over verbatim. Removing an absent seat is a no-op on the map, so a
     * re-delivered reclaim cannot disturb a live takeover of another seat.
     */
    fun withoutTakeover(takeoverRaw: Any?, seatId: String): Map<String, Any?> =
      copyEntries(takeoverRaw).apply { remove(seatId) }

    private fun copyEntries(takeoverRaw: Any?): LinkedHashMap<String, Any?> {
      val out = LinkedHashMap<String, Any?>()
      (takeoverRaw as? Map<*, *>)?.forEach { (key, value) ->
        if (key is String) out[key] = value
      }
      return out
    }

    /**
     * Firestore hands a JS `number` to Kotlin/JS as a `Double` and to the JVM as an
     * `Int`/`Long`, so any whole [Number] is trusted; a string, a nested map or a
     * fractional value is not.
     */
    private fun asWholeLong(raw: Any?): Long? {
      val number = raw as? Number ?: return null
      val value = number.toDouble()
      if (value.isNaN() || value.isInfinite()) return null
      if (value != floor(value)) return null
      return number.toLong()
    }
  }
}

/**
 * Who holds [seatId] right now, read from the stored marker plus the caller's
 * view of the current round and trick. The clock is supplied, never parsed —
 * [InactivityClock.fromStored] owns validating it, and a caller whose clock came
 * back null must not reach here at all.
 *
 * [clock] defaults to a fresh one because the display and pacing consumers (the
 * seat chip, the 2 s window) only ask WHO holds the seat and never touch the
 * accumulation; the resolver passes the clock it just read, so a reclaimed seat
 * returning [SeatControl.Human] with a zeroed clock stays one expression.
 *
 * The pairing of marker and trick is the whole of DEC-RD19-04: a seat whose
 * marker is present is borrowed, and it is reclaimable exactly once
 * [InactiveTakeover.seatTrickKey] has moved past the trick stamped at takeover —
 * which covers the auction case (the trick in progress is 0, so the bound is
 * "the first trick of that round resolves") and the round-advanced-while-borrowed
 * case without any extra state.
 */
fun storedSeatControl(
  takeoverRaw: Any?,
  seatId: String,
  roundNow: Int,
  trickNoNow: Int,
  clock: InactivityClock = InactivityClock(),
): SeatControl {
  val marker = InactiveTakeover.parseAll(takeoverRaw)[seatId]
    ?: return SeatControl.Human(clock = clock)
  return SeatControl.Borrowed(
    takenOverAtMillis = marker.takenOverAtMillis,
    trickFinished = InactiveTakeover.seatTrickKey(roundNow, trickNoNow) >
      InactiveTakeover.seatTrickKey(marker.roundAtTakeover, marker.trickAtTakeover),
  )
}
