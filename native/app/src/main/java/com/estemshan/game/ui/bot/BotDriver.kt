package com.estemshan.game.ui.bot

import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.bot.BidBrain
import com.estemshan.engine.bot.PlayBrain
import com.estemshan.engine.TablePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * **Story S11 — the bot driver.** Watches a match through [RoundStateProvider]
 * and moves for whichever seat the game currently waits on, whenever that seat
 * is a bot. Written once against the seam, so it serves offline quick-match
 * and online matches identically.
 *
 * **The driver owns no rules.** It is a turn detector and a dispatcher: it
 * reads the engine's own `BiddingState` / `TableState` to see who acts, asks
 * [BidBrain] or [PlayBrain] what to do, and hands the answer back to the host
 * through the same `emit()` path a human tap takes. Legality, turn order, the
 * forbidden-13 and the rejection surface all stay in the engine and the host,
 * exactly as they are for a human. A bot that mis-judges is rejected, not
 * accommodated.
 *
 * **Why a coroutine over a callback.** Bidding and play are asynchronous
 * streams of turns; the driver is a pair of long-lived collectors, one per
 * phase, launched in a host-owned [CoroutineScope] (a `viewModelScope`).
 * Each collector reacts to a state emission only when the seat to act is a
 * bot — a human's turn passes straight through and the driver stays silent,
 * which is what keeps the same flow usable for a mixed table.
 *
 * **Cadence.** A bot that answers in 0 ms does not feel like an opponent, so
 * every action waits [HumanizedDelay] first — 400–900 ms per the plan. The
 * delay is *derived* from the action, never drawn from a RNG: a match with a
 * fixed roster and a fixed deal then replays identically, the same property
 * that makes the S10 smoke test deterministic. [BotAction] exists so the
 * cadence can vary with the moment without losing that.
 *
 * **The race the guards are for.** Between deciding to wait and the wait
 * ending, the state may move on: the round could end, a redeal could restart
 * the auction, or (in a future online host) a resumed player could take the
 * seat. So the driver re-reads the *current* state after the delay and
 * re-checks the turn before dispatching. Acting on the pre-delay snapshot
 * would submit an intent for a turn that no longer exists; the engine would
 * reject it, but a rejection on a non-turn is still a visible bug, and in
 * play it would mean playing a card after the trick closed.
 */
class BotDriver(
  private val provider: RoundStateProvider,
  /** Milliseconds to wait before dispatching [BotAction]. Defaults to
   *  [HumanizedDelay]; tests inject an immediate clock so they do not have
   *  to assert on wall-clock time. */
  private val delayMillis: (BotAction) -> Long = HumanizedDelay,
) {

  /**
   * Start both collectors in [scope] and return the composite [Job]. The
   * driver stops when [scope] is cancelled — the host's `viewModelScope`
   * dies with its view model, so there is nothing extra to shut down.
   */
  fun launchIn(scope: CoroutineScope): Job = scope.launch {
    launch { driveBidding() }
    launch { drivePlay() }
  }

  /**
   * React to every auction emission. Nulls (no auction), DONE auctions and
   * human turns all fall through; a bot turn waits the cadence, re-verifies,
   * and submits.
   */
  private suspend fun driveBidding() {
    provider.bidding.collect { state ->
      val seat = state?.waitingFor ?: return@collect
      val config = provider.roster.config(seat) ?: return@collect // a human
      val hand = provider.handFor(seat) ?: return@collect // no deal yet

      delay(delayMillis(BotAction.Bid(seat, state.round, state.subPhase)))

      // The auction may have moved on while we waited; re-read, not the
      // emission we decided on. A DONE auction or a changed turn is a no-op.
      val current = provider.bidding.value ?: return@collect
      if (current.subPhase == BiddingPhase.DONE) return@collect
      if (current.waitingFor != seat) return@collect

      provider.submitBidding(
        BidBrain.decide(current, hand, seat, config.tier, config.personality),
      )
    }
  }

  /**
   * React to every table emission. Only [TablePhase.PLAY] is actable:
   * RESOLVING is the UI's trick-collection beat (it auto-collects after its
   * own highlight) and DONE is the round's end, so a bot interjecting in
   * either would race the screen.
   */
  private suspend fun drivePlay() {
    provider.table.collect { state ->
      if (state?.phase != TablePhase.PLAY) return@collect
      val seat = state.turn ?: return@collect
      val config = provider.roster.config(seat) ?: return@collect // a human

      delay(delayMillis(BotAction.Play(seat, state.cfg.round, state.trickNo)))

      val current = provider.table.value ?: return@collect
      if (current.phase != TablePhase.PLAY) return@collect
      if (current.turn != seat) return@collect

      provider.playCard(seat, PlayBrain.decide(current, seat, config.tier).card)
    }
  }

  companion object {
    /**
     * The plan's 400–900 ms humanized cadence, derived from the action so it
     * is deterministic: the same seat in the same moment of the same match
     * always pauses the same way, which keeps a bot match reproducible the
     * way [seatHash] keeps the brains reproducible. A spread of primes over
     * the action's fields gives an even distribution across the 501 ms
     * window without clustering every seat on the same value.
     */
    val HumanizedDelay: (BotAction) -> Long = { action ->
      val mix = when (action) {
        is BotAction.Bid -> action.round * 31 + action.subPhase.ordinal * 7 + action.seat.hashCode()
        is BotAction.Play -> action.round * 31 + action.trickNo * 17 + action.seat.hashCode()
      }
      HUMANIZED_MIN + ((mix and Int.MAX_VALUE) % HUMANIZED_SPREAD)
    }

    private const val HUMANIZED_MIN = 400L
    private const val HUMANIZED_SPREAD = 501L // 400..900 inclusive
  }
}
