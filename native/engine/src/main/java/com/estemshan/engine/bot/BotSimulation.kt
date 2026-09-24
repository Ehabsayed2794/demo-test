package com.estemshan.engine.bot

import com.estemshan.engine.Card
import com.estemshan.engine.Suit
import kotlin.math.min

/**
 * A bid-time estimate from a simulation, ported from `AI Bots/botSimulation.ts`'s
 * `estimateBidBySimulation` return shape.
 *
 * [bid] is the modal, *realistically makeable* trick count for the hand — not
 * the optimistic ceiling [HandEvaluator] produces. [confidence] is that modal
 * outcome's share of the samples (the source's `dist[bid] / samples`); a low
 * confidence means the hand is volatile, which matters for personality tuning
 * even before real Monte-Carlo lands.
 *
 * [estimate] is a *continuous* count, not a pre-rounded integer, even though the
 * source's `estimateBidBySimulation` returns the modal integer `bid`. The port
 * rounds exactly once — after the tier's seat jitter and the personality's bias
 * are applied — so a seam result sitting on a rounding boundary (a hand worth
 * exactly 8.5) still lands on different integers at different seats, the
 * property S6's seat-variation golden pins. Rounding inside the seam would
 * collapse that variation to a single number.
 *
 * The source also returns the full trick-count `distribution` array. It feeds
 * only an optional `debug:true` histogram, so the port carries the headline
 * number in [reasoning] instead and drops the array — nothing reads it in a
 * decision path, and a 14-entry list on every bid is dead weight on a hot path.
 */
data class SimBid(
  /** The realistically makeable trick count — the modal outcome of a real
   * simulation, or the entry-limited heuristic count from the ship default.
   * Continuous: callers round once, with jitter and personality, at the point
   * of use. See [BidBrain.botBid]. */
  val estimate: Double,
  val confidence: Double,
  val reasoning: String,
)

/**
 * **The SimPort seam (S9).** The one interface between the bot brains and any
 * simulation-based bid refinement, standing in for `AI Bots/botSimulation.ts`.
 *
 * **Why this exists as a seam at all.** `botPersonality.ts` does not call
 * `botSimulation.ts` for EXPERT only — it calls it for **HARD as well**, and
 * the source documents why in `evaluateBotBidWithPersonality`:
 *
 * > "(Essential for HARD: at full confidence it trusted the optimistic
 * > heuristic and over-bid. EXPERT still pulls ahead via its Monte-Carlo
 * > CARD PLAY.)"
 *
 * So simulation is *load-bearing for HARD's bidding sanity*, not an EXPERT
 * nicety. Real Monte-Carlo is explicitly out of scope for v1 (plan §2A ships
 * it post-launch), which raises the question of what HARD bids without it.
 * Answering "the same optimistic heuristic it always did" reintroduces the
 * documented over-bid regression the moment the seam is wired in. So the seam
 * ships with [Default]: a cheap, deterministic heuristic that corrects the
 * over-bid without playouts. Swapping in Monte-Carlo later is then a one-object
 * change behind an interface every caller already takes as a parameter, and
 * the S9/S10 golden tests keep either implementation honest — that is what
 * "deferring it regression-free" means here.
 *
 * **Why a `fun interface`.** It has exactly one abstract member ([estimateBid]),
 * so an implementation is injectable as a plain lambda — which is how S9's tests
 * override the default with a fake simulation, and how a future Monte-Carlo
 * object drops in. [estimateDashSuccess] is a *default method*, delegating to
 * the heuristic, because dash verification is the secondary use of the source's
 * simulator: a lambda implementing only the bid estimate still gets a sane dash
 * probability, and a full implementation overrides both.
 *
 * **Trump convention.** [assumedTrump] follows the engine's own convention:
 * [Suit.SANS] means no trump, exactly as [HandEvaluator] reads it (the source's
 * `'NONE'`). The source's per-call `samples` option is not part of the contract
 * — the heuristic ignores sample counts, and a real simulator carries its own
 * configuration internally instead of leaking a tuning knob into every caller.
 */
fun interface BotSimulation {

  /**
   * The realistically makeable trick count for [hand] under [assumedTrump].
   * Called only when the bot is *not* dashing — the source's `estimateBidBySimulation`
   * runs in the same "if (!base.isDashCall)" branch.
   */
  fun estimateBid(hand: List<Card>, assumedTrump: Suit): SimBid

  /**
   * The probability that [hand] can win **zero** tricks — the source's
   * `estimateDashSuccess`, which samples across random layouts *and* random
   * trumps because a pre-bid Dasher controls neither. Used to verify a
   * heuristic Dash before committing to it (a hand rated ~0.5 can still be
   * forced to win a couple of tricks).
   *
   * Default: the heuristic estimate in [HeuristicBotSimulation]. Override when
   * a real simulator lands.
   */
  fun estimateDashSuccess(hand: List<Card>): Double = HeuristicBotSimulation.estimateDashSuccess(hand)

  companion object {
    /** The ship-default: cheap, deterministic, no Monte-Carlo. See
     * [HeuristicBotSimulation] for what it corrects and why. */
    val Default: BotSimulation get() = HeuristicBotSimulation
  }
}

/**
 * The heuristic [BotSimulation] that ships in v1, replacing the source's
 * Monte-Carlo `estimateBidBySimulation` / `estimateDashSuccess` with cheap,
 * deterministic arithmetic over [HandEvaluator].
 *
 * It exists to close one specific, documented gap: HARD trusts the hand
 * evaluator at full confidence and therefore over-bids. The evaluator is an
 * *optimistic ceiling* — it counts every trick the hand could take if
 * everything broke right. The two corrections below are the cheap version of
 * what a playout measures directly, and they are deliberately conservative: a
 * heuristic that under-bids is a weaker bot, a heuristic that over-bids is a
 * *felt* one, so the bias is toward not inflating.
 *
 * **Not a tuning surface.** These are placeholder stand-ins for simulated
 * truth, calibrated only to reproduce decisions the S6 brain already makes, so
 * wiring the seam changes nothing observable today and a real simulator can be
 * A/B'd against it by the golden suites. The constants are not up for
 * adjustment; retuning them is retuning the bots, which is post-launch work.
 */
object HeuristicBotSimulation : BotSimulation {

  /**
   * Entry-limited realization of the hand's distributional value.
   *
   * Length and ruffing tricks are *conditional*: to cash a long suit's small
   * cards the bot must first gain the lead and keep regaining it, and each of
   * those entries costs a high-card trick. The evaluator ignores that and
   * books the full value — which is precisely the optimism that made HARD
   * over-bid. So the makeable count caps the distributional part at what the
   * hand can actually support:
   *
   * ```
   * realistic = highCard + min(distributional, highCard + FREE_CASH)
   * ```
   *
   * A hand with five solid honors and three length tricks keeps all of them
   * (plenty of entries). A hand with one ace and five length tricks realizes
   * only two of the five — it cannot get the lead often enough to cash them
   * before opponents cash theirs. High-card tricks are never discounted; they
   * are real regardless of skill or simulation, as [HandEvaluator] itself
   * documents.
   *
   * [FREE_CASH] is the one distributional trick that needs no entry at all:
   * the last trump left standing, or the long card once opponents have
   * exhausted the suit. Without it, a no-honor seven-card suit would book
   * zero tricks when it reliably wins one — the cap is meant to remove
   * *optimism*, not to deny a real long card.
   */
  override fun estimateBid(hand: List<Card>, assumedTrump: Suit): SimBid {
    val evaluation = HandEvaluator.evaluateHand(hand, assumedTrump, confidence = 1.0)

    val highCard = evaluation.breakdown.sumOf { it.highCardTricks }
    val distributional = evaluation.breakdown.sumOf { it.lengthTricks + it.ruffTricks }
    // Each probable high-card trick is also an entry; FREE_CASH covers the one
    // distributional trick that materializes without ever holding the lead.
    val cashable = highCard + FREE_CASH
    val realized = min(distributional, cashable)

    val realistic = highCard + realized
    return SimBid(
      estimate = realistic,
      // No samples exist to take a modal share from; report the fraction of
      // the estimate that is high-card (the part that does not depend on
      // realization), so a caller can still tell a solid bid from a shaky one.
      confidence = if (realistic > 0.0) (highCard / realistic).coerceIn(0.0, 1.0) else 1.0,
      reasoning = "heuristic-sim: highCard=${round2(highCard)} " +
        "distributional=${round2(distributional)} cashable=${round2(cashable)} " +
        "=> realistic ${round2(realistic)} (bid ${realistic.toIntCoerce()})",
    )
  }

  /**
   * Heuristic P(win zero tricks), calibrated to *reproduce the S6 dash gate*
   * rather than to invent a probability model.
   *
   * [BidBrain.dashSignal] dashes exactly when there are no aces, few forced
   * tricks, and a low ceiling under the best trump, and it applies that gate
   * uniformly to every tier. This maps the same inputs onto a probability with
   * a linear ramp whose slope is chosen so the gate's own boundary (ceiling
   * 1.5) lands exactly on the 0.80 acceptance line — so a hand the S6 gate
   * dashes still dashes through the seam, and a hand it refuses is still
   * refused. An ace is a trick that cannot be shed, so any ace forces a
   * near-zero probability and rejects the dash, just as the gate's hard
   * `aces == 0` requirement does.
   *
   * The source grades EXPERT harder than HARD (0.85 vs 0.80). That split is
   * deliberately flattened here and in the wiring: S6's gate is already
   * tier-uniform, and inventing a steeper EXPERT boundary would change
   * dash verdicts the S6 golden tests already pin.
   */
  override fun estimateDashSuccess(hand: List<Card>): Double {
    val aces = hand.count { it.value == ACE }
    if (aces > 0) return ACE_DASH_BLOCKER

    val kings = hand.count { it.value == KING }
    val forced = aces + kings * KING_FORCED_WEIGHT
    val ceiling = HandEvaluator.evaluateAllTrumps(hand, confidence = 1.0)
      .maxOf { it.expectedTricks }

    // (1.0 - 0.80) / 1.5: the S6 gate boundary coincides with the threshold.
    return (1.0 - DASH_SLOP * maxOf(ceiling, forced))
      .coerceIn(0.0, NO_ACE_CAP)
  }

  private const val ACE = 14
  private const val KING = 13
  private const val KING_FORCED_WEIGHT = 0.4
  /** One distributional trick that needs no entry: the last trump standing, or
   * the long card once opponents exhaust the suit. */
  private const val FREE_CASH = 1.0
  /** An ace cannot be shed; P(0) is effectively out of reach. */
  private const val ACE_DASH_BLOCKER = 0.0
  /** Without a simulator the estimate is single-point, never certain. */
  private const val NO_ACE_CAP = 0.95
  /** Slope = (1.0 - 0.80) / 1.5, so the S6 dash boundary hits the 0.80 line. */
  private const val DASH_SLOP = 0.2 / 1.5

  private fun Double.toIntCoerce(): Int =
    kotlin.math.round(this).toInt().coerceIn(0, 13)

  private fun round2(n: Double): Double = HandEvaluator.round2(n)
}
