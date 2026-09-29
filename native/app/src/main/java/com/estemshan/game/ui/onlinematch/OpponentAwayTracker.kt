package com.estemshan.game.ui.onlinematch

import com.estemshan.services.model.MatchDoc

/**
 * S19's passive activity indicator — "opponent appears away" — derived purely
 * from how long the match document has failed to progress while an OPPONENT
 * holds the turn. Stateless across the process: one instance per
 * [OnlineMatchViewModel], reset when the binding drops.
 *
 * WHY STALENESS, NOT PRESENCE — the frozen firestore.rules make true presence
 * impossible: players/{uid} is owner-read-only and `list: if false`, so no
 * client can ever observe another player's lastSeenAt (risk R7). This class
 * never reads a profile; it watches the ONE thing every seat already
 * subscribes to — the match document — and reports only what that directly
 * implies: nothing has moved on the table for a while. The UI wording stays
 * "appears away"; there is no online light and no stronger claim anywhere in
 * the app, and the heartbeat a player emits is not the thing any opponent is
 * shown.
 *
 * WHY THE LOCAL CLOCK — [MatchDoc] and its logs carry no timestamps (the
 * document is field-for-field the JS `buildInitialMatchDoc`, which has none),
 * so elapsed time is measured client-side from the moment a baseline is taken.
 * The consequence is stated rather than hidden: the indicator reflects THIS
 * client's observation of the document, not a shared clock. A client whose own
 * listener stalled reads "appears away" about a document that actually moved —
 * the same one-sidedness that makes this a passive hint instead of a game
 * state, and the reason it never times anyone out or blocks input.
 */
internal class OpponentAwayTracker(
  private val thresholdMillis: Long = AWAY_THRESHOLD_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  /** The states the indicator can hold. */
  enum class Presence {
    /** Our move, or no acting seat is known — nobody is being waited on. */
    WaitingOnUs,
    /** An opponent holds the turn and the document is still progressing. */
    OpponentActive,
    /** An opponent holds the turn and the document has been stale past
     *  [thresholdMillis] — they appear away. */
    AppearsAway,
  }

  /**
   * The mutable document facts a baseline is pinned to. Any change here is
   * real progress: the turn moved, a card or a bid landed, the round advanced,
   * or the table entered a new phase. `version` is deliberately NOT part of it
   * — a version bump alone (a re-delivery, a rejected write) is not play, and
   * counting it would reset the clock on noise.
   */
  private data class Progress(
    val round: Int,
    val turn: String?,
    val cardPhase: String?,
    val cards: Int,
    val bids: Int,
  )

  private fun progressOf(doc: MatchDoc) = Progress(
    round = doc.currentRound,
    turn = doc.turn,
    cardPhase = doc.cardPhase,
    cards = doc.cardLog.size,
    bids = doc.biddingLog.size,
  )

  /** The progress + acting seat the running baseline was taken against. */
  private var baseline: Progress? = null
  private var baselineSeat: String? = null
  private var baselineAt: Long = 0L

  /**
   * Re-derive from a freshly observed [doc]. [actingSeat] is the seat the
   * engines say must move now — the table's turn, or the auction's
   * `waitingFor` — and null when it is OUR move or nobody is waiting. It is
   * the caller's gate (this class stays free of engine coupling) and already
   * excludes our own seat, so a non-null value always means "an opponent".
   */
  fun onDocument(doc: MatchDoc, actingSeat: String?): Presence {
    if (actingSeat == null) {
      // Our move, or nobody waiting: the wait is over, and the clock starts
      // fresh with the next opponent's turn.
      baseline = null
      baselineSeat = null
      return Presence.WaitingOnUs
    }
    val progress = progressOf(doc)
    if (actingSeat != baselineSeat || progress != baseline) {
      // A new seat to wait on, or the document moved under the same one: the
      // baseline is (re)taken now, so staleness is measured from THIS
      // progress — a fresh play can never inherit the prior wait's age.
      baselineSeat = actingSeat
      baseline = progress
      baselineAt = clock()
      return Presence.OpponentActive
    }
    return presenceFromElapsed()
  }

  /**
   * Re-derive from the clock alone — the path the pending alarm takes when no
   * document has arrived to interrupt it, which is exactly the situation the
   * indicator exists for.
   */
  fun reevaluate(): Presence {
    if (baseline == null) return Presence.WaitingOnUs
    return presenceFromElapsed()
  }

  /**
   * Milliseconds until the indicator would flip under the running baseline.
   * Zero when nothing is pending — nobody is waited on, or the baseline
   * already crossed the threshold. The caller schedules exactly one alarm on
   * this value.
   */
  fun millisUntilAway(): Long {
    if (baseline == null) return 0L
    return (thresholdMillis - (clock() - baselineAt)).coerceAtLeast(0L)
  }

  /** Forget the baseline: a new match, or the binding dropped. */
  fun reset() {
    baseline = null
    baselineSeat = null
  }

  private fun presenceFromElapsed(): Presence =
    if (clock() - baselineAt >= thresholdMillis) Presence.AppearsAway
    else Presence.OpponentActive

  companion object {
    /**
     * How long a table may sit on one opponent's turn before the hint shows.
     * Generous on purpose: it is a passive, non-actionable hint, so a false
     * "appears away" costs more than a late one, and a long think over a bid
     * is normal Estemshan play.
     */
    const val AWAY_THRESHOLD_MILLIS = 45_000L
  }
}
