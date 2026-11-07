package com.estemshan.stage0

import com.estemshan.engine.InactiveTakeover
import com.estemshan.engine.InactivityClock
import com.estemshan.engine.SeatControl

/**
 * E9 boundary 7 — the takeover DECISION, as the engine already defines it.
 *
 * The Durable Object needs one question answered on every alarm delivery: given
 * the clock it stored and the wall-clock reading of this delivery, does a bot
 * take the seat, is the delivery a duplicate, or is it too early? Answering it
 * with a second implementation of RD19's arithmetic is exactly the kind of fork
 * E9 exists to rule out, so this wrapper calls [InactivityClock] and
 * [SeatControl.takeOver] — the same types the S73 resolver and the client chip
 * use — and renders their verdict as JSON.
 *
 * Pure: no clock source, no storage, no bot. [nowMillis] is an input, which is
 * what makes "the alarm is a trigger, not the clock" testable — a late
 * delivery and an on-time delivery with the same [nowMillis] produce the same
 * answer, and a restarted object that stored the same fields reaches it again.
 *
 * Verdicts:
 *  - `takeover`  — the clock reached RD19's threshold and no bot holds the seat.
 *  - `duplicate` — a bot already holds the seat: [SeatControl.takeOver] is
 *    idempotent by construction, so a re-delivered alarm can never re-stamp it.
 *  - `wait`      — below the threshold (an early alarm does nothing).
 *  - `refused`   — the stored clock is present but untrustworthy
 *    ([InactivityClock.fromStored]'s fail-closed rule): no takeover can fire.
 */
fun takeoverDecisionJson(
  accumulatedMillis: Long,
  openSinceMillis: Long?,
  nowMillis: Long,
  borrowed: Boolean,
): String {
  val clock = InactivityClock.fromStored(accumulatedMillis, openSinceMillis, docExists = true)
    ?: return """{"status":"refused","takeover":false,"reason":"stored clock is untrusted"}"""

  val control: SeatControl =
    if (borrowed) SeatControl.Borrowed(takenOverAtMillis = 0L) else SeatControl.Human(clock)
  // The engine's own transition decides: it throws below the threshold and
  // returns the same Borrowed seat on a second delivery.
  val attempted = runCatching { control.takeOver(nowMillis) }
  val takeover = attempted.isSuccess && control is SeatControl.Human
  val status = when {
    borrowed -> "duplicate"
    takeover -> "takeover"
    else -> "wait"
  }
  return """{"status":"$status","takeover":$takeover,"eligible":${clock.takeoverEligibleAt(nowMillis)},""" +
    """"inactiveMillis":${clock.inactiveMillis(nowMillis)},""" +
    """"thresholdMillis":${InactivityClock.TAKEOVER_THRESHOLD_MILLIS}}"""
}

/**
 * The persisted marker of a takeover, in the engine's shape
 * ([InactiveTakeover.toFields]) — the same three fields the S73 resolver writes
 * to `matches/{id}.inactiveTakeovers`, so the DO's row and the product's
 * document name the same fact the same way.
 */
fun takeoverMarkerJson(takenOverAtMillis: Long, round: Int, trick: Int): String {
  val fields = InactiveTakeover(takenOverAtMillis, round, trick).toFields()
  return """{"takenOverAtMillis":${fields[InactiveTakeover.TAKEN_OVER_AT_FIELD]},""" +
    """"roundAtTakeover":${fields[InactiveTakeover.ROUND_AT_TAKEOVER_FIELD]},""" +
    """"trickAtTakeover":${fields[InactiveTakeover.TRICK_AT_TAKEOVER_FIELD]}}"""
}
