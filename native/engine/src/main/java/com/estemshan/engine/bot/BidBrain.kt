package com.estemshan.engine.bot

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.Suit
import com.estemshan.engine.auctionBidBeatsTop
import com.estemshan.engine.auctionBidIsWith
import com.estemshan.engine.canSubmit
import com.estemshan.engine.dashCallerIds
import com.estemshan.engine.fixedTrumpFor
import com.estemshan.engine.forbiddenEstimateFor
import com.estemshan.engine.withFloorFor

/**
 * The bidding brain (Module B), ported from `AI Bots/botEngine.ts` §3
 * (`evaluateBotBid`) plus the four per-phase call sites in `App.tsx`.
 *
 * **The single most important difference from the source.** The TypeScript
 * computes a raw number and then `App.tsx` shepherds it through up to three
 * separate call sites (`DASH_CALL_DECISION`, `CALL_PHASE`, `FOLLOWING_BIDS`,
 * `TRUMP_DECLARATION`), each with its own `setTimeout` block, its own local
 * clamping, and — in `FOLLOWING_BIDS` — a `while (attempts < 20)` loop that
 * mutates the bid until `validateBidding` accepts it. That loop is a
 * compensate-after-the-fact pattern: it *assumes* the engine will reject and
 * patches around it.
 *
 * The native engine inverts the relationship. `canSubmit` is the single
 * authority for what a seat may legally emit, so the brain's contract is
 * stronger and simpler: **produce an intent, then verify it clears
 * `canSubmit`.** If a brain ever returns an intent the state rejects, that is
 * a bug in the brain — not a signal to retry. [decide] therefore asserts
 * legality before returning and throws rather than shipping an intent that
 * would be silently rejected downstream. `BotDriver` (S11) can then dispatch
 * the intent through the same `emit()` path a human tap takes, with no
 * second-guessing layer in between.
 *
 * Determinism is preserved from the source: `seatJitter` is a hash of the
 * player id, so the same hand+seat always yields the same bid, which is what
 * makes S10's bot-vs-bot smoke test reproducible.
 */
object BidBrain {

  /**
   * Decide the intent [playerId] should emit in [state], holding [hand] at
   * difficulty [tier].
   *
   * The returned intent is guaranteed to clear `canSubmit(state, intent)` for
   * the given state, or this function throws — it never returns an intent the
   * reducer would reject.
   *
   * @throws IllegalStateException if the round has already completed, or if the
   *   brain produces an illegal intent — both indicate a brain/state mismatch
   *   rather than a recoverable condition.
   * @throws IllegalArgumentException if [playerId] is not the seat the state is
   *   waiting on.
   */
  fun decide(state: BiddingState, hand: List<Card>, playerId: String, tier: BotTier): BiddingIntent {
    // A completed round has no seat waiting, so the seat check below would
    // report a wrong-seat error for an ask that is really an after-completion
    // ask. Diagnose the completion first.
    check(state.subPhase != BiddingPhase.DONE) {
      "BidBrain asked to decide after bidding completed"
    }
    require(state.waitingFor == playerId) {
      "BidBrain asked for $playerId but the state is waiting on ${state.waitingFor}"
    }
    val intent = when (state.subPhase) {
      BiddingPhase.DASH -> dashDecision(state, hand, playerId, tier)
      BiddingPhase.AUCTION -> auctionBid(state, hand, playerId, tier)
      BiddingPhase.CONFIRM -> confirmCall(state, hand, playerId, tier)
      BiddingPhase.ESTIMATES -> finalEstimate(state, hand, playerId, tier)
      BiddingPhase.DONE -> error("BidBrain asked to decide after bidding completed")
    }
    val legality = canSubmit(state, intent)
    check(legality.legal) {
      "BidBrain produced an illegal intent for $playerId in ${state.subPhase}: " +
        "${legality.reason} — this is a brain bug, not a state error"
    }
    return intent
  }

  // --------------------------------------------------------------------------
  //  DASH — "do I declare a Dash Call?" (App.tsx DASH_CALL_DECISION)
  // --------------------------------------------------------------------------

  /**
   * Ported from `App.tsx`'s DASH_CALL_DECISION block. The source's dash
   * detection lives in `evaluateBotBid` and is gated on two things: the tier
   * may dash at all (`canDash`), and fewer than two seats have already dashed
   * (the engine's `MAX_DASH_CALLS`).
   *
   * A Dash commits to winning zero tricks. What matters is not how many tricks
   * the hand *could* win — length and ruffing tricks are voluntary — but how
   * many it would be *forced* to win. So this scores unduckable honors: an ace
   * is nearly always a trick, a king often enough to matter.
   *
   * Note the engine auto-converts an over-the-limit Dash Call to a PASS inside
   * `emit`, so a brain that ignores the count still produces a legal intent.
   * Respecting it here keeps the brain's own model honest and its reasoning
   * accurate for the debug panel.
   */
  private fun dashDecision(
    state: BiddingState,
    hand: List<Card>,
    playerId: String,
    tier: BotTier,
  ): BiddingIntent.DashCallDecision {
    val wantDash = tier.canDash && dashSignal(hand) && dashCallerIds(state).size < 2
    return BiddingIntent.DashCallDecision(playerId = playerId, declaredDashCall = wantDash)
  }

  /**
   * The source's dash gate: no aces, very few forced tricks, and — crucially —
   * a low ceiling under the *best* trump. A pre-bid Dash does not control the
   * trump, so the danger case is the trump under which this hand wins the most
   * tricks. Dashing a no-ace hand with trump length would bust, which is the
   * documented bug the ceiling check exists to prevent ("a Dash won 5").
   */
  private fun dashSignal(hand: List<Card>): Boolean {
    val aces = hand.count { it.value == ACE }
    val kings = hand.count { it.value == KING }
    val forced = aces * 1.0 + kings * 0.4
    val ceiling = HandEvaluator.evaluateAllTrumps(hand, confidence = 1.0)
      .maxOf { it.expectedTricks }
    return aces == 0 && forced < 0.75 && ceiling <= 1.5
  }

  // --------------------------------------------------------------------------
  //  AUCTION — "call a number + suit, or pass" (App.tsx CALL_PHASE)
  // --------------------------------------------------------------------------

  /**
   * Ported from `App.tsx`'s CALL_PHASE block, minus the Wazz-challenger
   * branch: with-ness is resolved by the engine's own auction alignment
   * (`auctionBidIsWith`), so the brain only ever has to answer "raise, match,
   * or pass?".
   *
   * The auction's legal floor is 4 tricks, so a hand the evaluator rates below
   * 4 must pass rather than call — the source's `isCallPhaseLegal` field made
   * the same distinction explicit, and `canSubmit` enforces it here.
   */
  private fun auctionBid(
    state: BiddingState,
    hand: List<Card>,
    playerId: String,
    tier: BotTier,
  ): BiddingIntent.AuctionBid {
    val eval = botBid(hand, tier, playerId, chosenTrump = null)

    // Clamp to a legal trick count only. The 4-trick minimum is *not* a floor
    // on the estimate — the source is explicit about this (`isCallPhaseLegal:
    // isDashCall || bid >= 4`): a hand rated below 4 must PASS the auction and
    // estimate low, never be inflated to a 4-call it cannot back. Inflating it
    // here would make a bankrupt hand open the auction at 4.
    val call = eval.intendedBid.coerceIn(0, 13)
    if (call < 4) {
      return BiddingIntent.AuctionBid(playerId = playerId, isPass = true)
    }

    // A call that clears the floor only makes sense if it also beats the
    // current top on the engine's own strength ordering.
    val beatsTop = auctionBidBeatsTop(
      call, eval.potentialTrump, state.auctionTop, state.auctionSuit,
    )
    val isWith = auctionBidIsWith(
      playerId, call, eval.potentialTrump, state.auctionTop, state.auctionSuit, state.auctionBidder,
    )

    return if (beatsTop || isWith) {
      BiddingIntent.AuctionBid(playerId = playerId, isPass = false, tricks = call, suit = eval.potentialTrump)
    } else {
      BiddingIntent.AuctionBid(playerId = playerId, isPass = true)
    }
  }

  // --------------------------------------------------------------------------
  //  CONFIRM — the Caller locks the contract (App.tsx TRUMP_DECLARATION)
  // --------------------------------------------------------------------------

  /**
   * Ported from `App.tsx`'s TRUMP_DECLARATION block. Only the Caller is asked,
   * and `canSubmit` forbids lowering the winning call or naming a weaker suit
   * at the same number — so the brain either upgrades to a stronger contract
   * it can actually make, or confirms what it already won.
   *
   * In fast rounds with a Super Call the engine sets `noSuitConstraint`, which
   * lets the Caller replace the trump freely; the same upgrade-or-confirm
   * logic serves both, since the evaluation already picks the hand's best suit.
   */
  private fun confirmCall(
    state: BiddingState,
    hand: List<Card>,
    playerId: String,
    tier: BotTier,
  ): BiddingIntent.ConfirmCall {
    val eval = botBid(hand, tier, playerId, chosenTrump = null)
    val upgrade = auctionBidBeatsTop(
      eval.intendedBid, eval.potentialTrump, state.auctionTop, state.auctionSuit,
    )
    return if (upgrade) {
      BiddingIntent.ConfirmCall(
        playerId = playerId,
        tricks = eval.intendedBid.coerceIn(state.auctionTop, 13),
        suit = eval.potentialTrump,
      )
    } else {
      // Confirm the contract as won. auctionTop/auctionSuit are the values the
      // engine itself recorded, so this always clears canSubmit.
      BiddingIntent.ConfirmCall(
        playerId = playerId,
        tricks = state.auctionTop,
        suit = state.auctionSuit ?: eval.potentialTrump,
      )
    }
  }

  // --------------------------------------------------------------------------
  //  ESTIMATES — "how many will I take?" (App.tsx FOLLOWING_BIDS)
  // --------------------------------------------------------------------------

  /**
   * Ported from `App.tsx`'s FOLLOWING_BIDS block. This is where the source's
   * `while (attempts < 20)` retry loop lived, and where the port diverges most:
   * instead of emitting and patching, the brain computes the three constraints
   * the engine cares about — the Caller's cap, the With floor, and
   * forbidden-13 — and folds them into the estimate before emitting.
   *
   * Fast rounds have no cap (the engine sentinel-caps at 13), and the trump is
   * fixed per round rather than chosen, so the estimate is made under the
   * round's own trump.
   */
  private fun finalEstimate(
    state: BiddingState,
    hand: List<Card>,
    playerId: String,
    tier: BotTier,
  ): BiddingIntent.FinalEstimate {
    val trump = if (state.fastRound) state.declaredTrump ?: fixedTrumpFor(state.round) else state.declaredTrump
    val eval = botBid(hand, tier, playerId, chosenTrump = trump)

    // The Caller's cap bounds every other seat; a fast round's sentinel of 13
    // means "no cap", which coerceIn handles identically.
    val cap = state.auctionTop.coerceAtLeast(4)
    var estimate = eval.intendedBid.coerceIn(0, cap)

    // A With seat cannot go below the last real number it bid itself.
    val floor = withFloorFor(state.actionHistory, playerId)
    if (floor != null && state.withPlayers.contains(playerId)) {
      estimate = estimate.coerceAtLeast(floor)
    }

    // Forbidden-13: if this is the last seat to declare, it may not pick the
    // number that makes the round total exactly 13. The source nudges either
    // direction; the port prefers down (safer for a non-caller) and never
    // lands on the forbidden value.
    val forbidden = forbiddenEstimateFor(state, playerId)
    if (forbidden != null && estimate == forbidden) {
      estimate = if (estimate > 0) estimate - 1 else estimate + 1
    }

    return BiddingIntent.FinalEstimate(playerId = playerId, tricks = estimate.coerceIn(0, cap))
  }

  // --------------------------------------------------------------------------
  //  The estimator itself, ported from evaluateBotBid
  // --------------------------------------------------------------------------

  /**
   * A tier-aware, distribution-aware trick estimate for a hand, ported from
   * `botEngine.ts`'s `evaluateBotBid`. Returns the honest float, the rounded
   * intent, and the trump the estimate was made under.
   *
   * [chosenTrump] pins the trump when the round already fixed it (final
   * estimates, fast rounds); null means the brain is free and picks its own
   * best suit, mirroring the source's `chosenTrump === 'POTENTIAL'`.
   */
  private fun botBid(
    hand: List<Card>,
    tier: BotTier,
    playerId: String,
    chosenTrump: Suit?,
  ): BotBid {
    val trump: Suit
    val potentialTrump: Suit
    if (chosenTrump == null) {
      potentialTrump = HandEvaluator.chooseBestTrump(hand, tier)
      trump = potentialTrump
    } else {
      trump = chosenTrump
      potentialTrump = chosenTrump
    }

    val evaluation = HandEvaluator.evaluateHand(hand, trump, tier.distributionConfidence)
    val rawFloat = evaluation.expectedTricks

    // Tier noise, deterministic per seat so a bot-vs-bot round is reproducible.
    val noisy = rawFloat + seatJitter(playerId, tier.bidNoise)
    val intendedBid = kotlin.math.round(noisy).toInt()

    return BotBid(
      rawFloat = rawFloat,
      intendedBid = intendedBid,
      potentialTrump = potentialTrump,
      reasoning = evaluation.reasoning,
    )
  }

  /**
   * Deterministic pseudo-jitter so the same hand+seat never flip-flops but
   * different bots vary. Ported 1:1 from `botEngine.ts`'s `seatJitter`;
   * `Math.random` is deliberately avoided for reproducibility (S10).
   */
  private fun seatJitter(playerId: String, magnitude: Double): Double {
    if (playerId.isEmpty() || magnitude == 0.0) return 0.0
    var hash = 0
    for (char in playerId) {
      hash = (hash * 31 + char.code)
    }
    // Fold to -1..1, matching the source's ((h % 1000) / 1000) * 2 - 1.
    val unit = ((hash % 1000) / 1000.0) * 2 - 1
    return unit * magnitude
  }

  private data class BotBid(
    val rawFloat: Double,
    val intendedBid: Int,
    val potentialTrump: Suit,
    val reasoning: String,
  )

  private const val ACE = 14
  private const val KING = 13
}
