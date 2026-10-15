package com.estemshan.engine

import kotlin.math.floor

/**
 * S73 — the RD19 decision-point inactivity clock (roadmap closure of
 * 2026-10-14, `DEC-RD19-01`…`05`, recorded in `docs/NATIVE_V1_PLAN_AND_ESTIMATE.md`
 * at commit `7dece9c`).
 *
 * Pure arithmetic only: no Firestore, no clock source, no bot, no `:services`
 * model. It lives in `:engine` because BOTH authority sites need the same
 * numbers without a mirror — the Cloud Functions callable that decides the
 * takeover (`:functions` depends on `:engine`) and the client that displays the
 * seat chip (`:app` → `:services` → `:engine`). `TimeoutResolution` had to
 * mirror RD21's timer bounds because `:functions` cannot see `:services`; this
 * type deliberately mirrors NOTHING, and therefore declares no timer bounds at
 * all: a window's length is an INPUT supplied by the caller, who owns the
 * authoritative `decisionTimerSeconds`.
 *
 * **The rule, as closed (DEC-RD19-01).** The clock starts when a decision point
 * becomes the player's. An ordinary timeout expiry — where the MEDIUM bot
 * resolves the decision — does NOT stop or reset it, because that decision is a
 * substitute for the player's input, not evidence of it. The clock accumulates
 * across consecutive decision points. Any genuine interaction inside one of the
 * player's OWN decision windows resets it to zero. At >= 50 s the configured bot
 * takes the seat, with NO timeout-counter increment (RD20 separation).
 *
 * **Why the threshold is necessarily cumulative (risk R33).** RD21's longest
 * window is 20 s, or 25 s with the +5 s Dash/Bidding/Estimates offset, so a
 * single window can never reach 50 s on its own. Two consecutive ignored full
 * 25 s windows hit it exactly; three ignored 20 s windows overshoot it; several
 * partial ones can too. `InactivityClockTest` pins that arithmetic rather than
 * leaving it to a reader's inference.
 *
 * **Engineering judgment inside the closed envelope** (pinned by tests here,
 * NOT specified by DEC-RD19):
 *
 *  - A CLOSED window contributes at most its own length. Once the MEDIUM bot
 *    decides, that decision point is over; extra wall-clock time after it
 *    belongs to the next window. An OPEN window is deliberately not capped in
 *    [inactiveMillis], so a window whose resolution is still retrying keeps
 *    counting — the player is still being ignored.
 *  - An interaction re-anchors an open window as well as zeroing the
 *    accumulation: the time before the player acted is not evidence of
 *    inactivity, and their window is still the one being answered.
 *  - An interaction reported OUTSIDE the player's own window is not a reset.
 *    DEC-RD19-01 scopes the reset to "within one of the player's own decision
 *    windows", so [interactionDuringOwnWindow] refuses the call when no window
 *    is open rather than silently crediting a tap that proves nothing.
 *  - Reclaim zeroes the clock. The player's first act after control returns IS
 *    a genuine interaction inside their own window, so the reset rule applies on
 *    its own terms — a consequence of DEC-RD19-01, not a new rule.
 *  - Stored state is validated fail-closed, the way `TimeoutCounter.fromStored`
 *    does it: a value that is present but untrustworthy yields null, so corrupt
 *    state can NEVER take a seat over (R33's first failure mode).
 *
 * **Out of scope here, by the story's own boundary:** the takeover WRITE (the
 * configured tier + personality, all three modes), the persisted doc that carries
 * [ACCUMULATED_FIELD] and [OPEN_WINDOW_FIELD] through `firestore.rules`, the
 * self-reported activity signal and its transport, the 15-timeout removal (S61,
 * shipped), and the disconnect flow (S74). That a takeover never moves the
 * timeout counter and never produces an RD31 departure is made structural here:
 * this type has no counter to increment and no departure flag to set.
 */
data class InactivityClock(
  /** Decision-window time this player has ignored since their last genuine interaction. */
  val accumulatedMillis: Long = 0L,
  /** Anchor of the player's currently-open decision window, or null when it is not their turn. */
  val openWindowStartMillis: Long? = null,
) {

  /**
   * Opens the player's decision window at [startMillis]. Windows never nest:
   * opening a second over an open one is a caller bug (two decision points for
   * one seat at once), so it fails loudly rather than losing an anchor.
   */
  fun windowOpened(startMillis: Long): InactivityClock {
    check(openWindowStartMillis == null) {
      "a decision window is already open for this seat since $openWindowStartMillis"
    }
    return copy(openWindowStartMillis = startMillis)
  }

  /**
   * Closes the open window at [nowMillis]. This is the transition a TIMEOUT
   * takes — the MEDIUM bot decided — and it therefore RESETS NOTHING: the
   * window's ignored time is added to the accumulation (DEC-RD19-01). The
   * contribution is capped at the window's own [durationMillis] and floored at
   * zero, so a late or out-of-order reading can neither manufacture nor erase
   * time.
   */
  fun windowClosed(nowMillis: Long, durationMillis: Long): InactivityClock {
    val start = openWindowStartMillis ?: error("no decision window is open for this seat")
    require(durationMillis >= 0L) { "a decision window cannot be negative: $durationMillis" }
    return copy(
      accumulatedMillis = accumulatedMillis + (nowMillis - start).coerceIn(0L, durationMillis),
      openWindowStartMillis = null,
    )
  }

  /**
   * Records a genuine interaction inside the player's own decision window: the
   * accumulation goes to zero and the open window re-anchors at [nowMillis], so
   * the time before they acted does not count against them.
   */
  fun interactionDuringOwnWindow(nowMillis: Long): InactivityClock {
    val start = openWindowStartMillis
      ?: error(
        "an interaction only resets the clock when it happens inside the player's own " +
          "decision window (DEC-RD19-01) — no window is open for this seat",
      )
    require(nowMillis >= start) {
      "an interaction cannot predate the window it is inside: $nowMillis < $start"
    }
    return copy(accumulatedMillis = 0L, openWindowStartMillis = nowMillis)
  }

  /**
   * Everything this seat has been inactive for as of [nowMillis]: the closed
   * windows plus the open one so far.
   */
  fun inactiveMillis(nowMillis: Long): Long {
    val open = openWindowStartMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L
    return accumulatedMillis + open
  }

  /** True when the accumulation has reached RD19's threshold. */
  fun takeoverEligibleAt(nowMillis: Long): Boolean =
    inactiveMillis(nowMillis) >= TAKEOVER_THRESHOLD_MILLIS

  /** The two values a caller persists, the only state this mechanism owns. */
  fun storedFields(): Map<String, Long?> = mapOf(
    ACCUMULATED_FIELD to accumulatedMillis,
    OPEN_WINDOW_FIELD to openWindowStartMillis,
  )

  companion object {
    /**
     * RD19's closed takeover threshold: 50 s of accumulated decision-point
     * inactivity. Millis, because every anchor this model takes is a wall-clock
     * reading in millis.
     */
    const val TAKEOVER_THRESHOLD_MILLIS: Long = 50_000L

    /**
     * The two fields this model persists, and the ONLY two — the structural
     * proof of the RD20 separation. There is no timeout count here to increment
     * and no removal flag here to set, so the takeover mechanism and S61's
     * 15-timeout removal share no persisted state, exactly as Stage 2 kept
     * `timeoutRemovedUids` apart from Vote Kick's `removedUids`.
     */
    const val ACCUMULATED_FIELD: String = "inactiveAccumulatedMillis"
    const val OPEN_WINDOW_FIELD: String = "inactiveWindowStartMillis"

    /** The persisted shape's field names, in order. */
    fun storedFieldNames(): List<String> = listOf(ACCUMULATED_FIELD, OPEN_WINDOW_FIELD)

    /**
     * The highest accumulation it will trust: one millis below the threshold. A
     * stored value AT or above it describes a seat that should already be
     * bot-controlled, so it is not state this mechanism produced.
     */
    private const val MAX_STORED_INACTIVE_MILLIS: Long = TAKEOVER_THRESHOLD_MILLIS - 1L

    /**
     * Validates a stored accumulation. Returns null when the value is PRESENT
     * but unparseable, fractional, or outside `0..MAX_STORED_INACTIVE_MILLIS` —
     * the FAIL-CLOSED outcome, mirroring `TimeoutCounter.fromStored`: the match
     * keeps resolving through the MEDIUM bot, but the clock advances nothing and
     * NO takeover fires.
     *
     * A missing doc yields a fresh clock instead: "no inactivity recorded yet"
     * is the legitimate starting state, and [docExists] is what distinguishes it
     * from a present-but-untrusted value — a seat's first window must create it.
     */
    fun fromStored(
      accumulatedRaw: Any?,
      windowStartRaw: Any?,
      docExists: Boolean = true,
    ): InactivityClock? {
      if (!docExists) return InactivityClock()
      val accumulated = asWholeMillis(accumulatedRaw) ?: return null
      if (accumulated < 0L || accumulated > MAX_STORED_INACTIVE_MILLIS) return null
      val windowStart = if (windowStartRaw == null) null else asWholeMillis(windowStartRaw) ?: return null
      return InactivityClock(accumulatedMillis = accumulated, openWindowStartMillis = windowStart)
    }

    /**
     * Firestore hands a JS `number` to Kotlin/JS as a `Double` and to the JVM as
     * an `Int`/`Long`, so any whole [Number] is accepted; a string, a map, or a
     * fractional value is untrusted.
     */
    private fun asWholeMillis(raw: Any?): Long? {
      val number = raw as? Number ?: return null
      val value = number.toDouble()
      if (value.isNaN() || value.isInfinite()) return null
      if (value != floor(value)) return null
      return number.toLong()
    }
  }
}

/**
 * Who holds one seat right now: the human, or the configured bot holding a
 * BORROWED seat. Borrowed, never lost — DEC-RD19-04: a takeover is not a
 * departure, reclaim returns the SAME seat, and neither event fires RD31's
 * 2-Koz result. The seat's identity is the caller's (the state is stored per
 * seat), so reclaim returns it by construction.
 */
sealed interface SeatControl {

  /** The human holds the seat; the inactivity clock is its only baggage. */
  data class Human(val clock: InactivityClock = InactivityClock()) : SeatControl

  /**
   * The configured bot holds the human's seat. [trickFinished] carries the
   * DEC-RD19-04 half-condition: the bot first completes the trick that was in
   * progress when the seat was taken, and only then does control return — at the
   * NEXT decision point.
   */
  data class Borrowed(
    val takenOverAtMillis: Long,
    val trickFinished: Boolean = false,
  ) : SeatControl

  /**
   * The bot takes the seat. Refused unless the clock has actually reached the
   * threshold at [nowMillis] — eligibility and takeover are the same fact, so no
   * caller can carry a bot over a seat that did not earn it. A borrowed seat
   * stays borrowed (idempotent: re-delivery must not re-stamp the takeover).
   */
  fun takeOver(nowMillis: Long): Borrowed = when (this) {
    is Borrowed -> this
    is Human -> {
      check(clock.takeoverEligibleAt(nowMillis)) {
        "seat is inactive for ${clock.inactiveMillis(nowMillis)} ms, below the " +
          "${InactivityClock.TAKEOVER_THRESHOLD_MILLIS} ms RD19 threshold"
      }
      Borrowed(takenOverAtMillis = nowMillis)
    }
  }

  /** The bot completed the trick in progress at takeover. A human seat has nothing to finish. */
  fun markTrickFinished(): SeatControl = when (this) {
    is Borrowed -> copy(trickFinished = true)
    is Human -> this
  }

  /**
   * Control returns to the human at the next decision point, with a zeroed
   * clock. Refused while the bot still owes the current trick, and a no-op on a
   * seat the human already holds — so a caller that re-arms on every delivery
   * cannot reclaim a live seat.
   */
  fun reclaim(): SeatControl = when (this) {
    is Borrowed -> {
      check(trickFinished) {
        "the bot must finish the current trick before control returns (DEC-RD19-04)"
      }
      Human()
    }
    is Human -> this
  }
}
