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
 * Determinism is preserved from the source: `seatHash` is a hash of the
 * player id, so the same hand+seat always yields the same bid, which is what
 * makes S10's bot-vs-bot smoke test reproducible.
 *
 * **S9 adds two orthogonal layers over that skill estimate**, both injected at
 * [decide] with identity defaults so every pre-S9 caller keeps behaving
 * byte-identically:
 *
 *  * **The [BotSimulation] seam** replaces the evaluator's optimistic ceiling
 *    with a realistic makeable count for the tiers that need it (HARD and
 *    EXPERT — see [usesBidSimulation]). This is the load-bearing half of the
 *    story: without it HARD trusts the heuristic at full confidence and
 *    over-bids, the regression the source documents in-place. Real Monte-Carlo
 *    is post-launch; the seam ships behind a heuristic default and exists so
 *    deferring it changes nothing.
 *  * **The [BotPersonality] style layer** shifts the estimate (see
 *    [applyPersonalityToBid]) and widens or tightens the dash gate (see
 *    [dashSignal]). Personality is style *on top of* tier skill: it never
 *    changes how well a hand is evaluated, only what the bot does with the
 *    number.
 *
 * Both layers stay under the brain's existing contract: whatever they produce
 * still has to clear `canSubmit` first try, or the brain throws.
 */
object BidBrain {

  /**
   * Decide the intent [playerId] should emit in [state], holding [hand] at
   * difficulty [tier], with [personality] as its style and [simulation] as its
   * bid-time simulation. Both default to their identities (BALANCED / the
   * heuristic seam), which is exactly the pre-S9 brain — every existing caller
   * is unaffected.
   *
   * The returned intent is guaranteed to clear `canSubmit(state, intent)` for
   * the given state, or this function throws — it never returns an intent the
   * reducer would reject. Neither injected layer weakens this: personality and
   * simulation only shape the estimate, never the legality check.
   *
   * @throws IllegalStateException if the round has already completed, or if the
   *   brain produces an illegal intent — both indicate a brain/state mismatch
   *   rather than a recoverable condition.
   * @throws IllegalArgumentException if [playerId] is not the seat the state is
   *   waiting on.
   */
  fun decide(
    state: BiddingState,
    hand: List<Card>,
    playerId: String,
    tier: BotTier,
    personality: BotPersonality = BotPersonality.DEFAULT,
    simulation: BotSimulation = BotSimulation.Default,
  ): BiddingIntent {
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
      BiddingPhase.DASH -> dashDecision(state, hand, playerId, tier, personality, simulation)
      BiddingPhase.AUCTION -> auctionBid(state, hand, playerId, tier, personality, simulation)
      BiddingPhase.CONFIRM -> confirmCall(state, hand, playerId, tier, personality, simulation)
      BiddingPhase.ESTIMATES -> finalEstimate(state, hand, playerId, tier, personality, simulation)
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
    personality: BotPersonality,
    simulation: BotSimulation,
  ): BiddingIntent.DashCallDecision {
    val wantDash = tier.canDash &&
      dashSignal(hand, personality) &&
      dashCallerIds(state).size < 2 &&
      dashVerified(hand, tier, simulation)
    return BiddingIntent.DashCallDecision(playerId = playerId, declaredDashCall = wantDash)
  }

  /**
   * The source's dash gate, now modulated by [personality]'s dashEagerness:
   * no aces, very few forced tricks, and — crucially — a low ceiling under the
   * *best* trump. A pre-bid Dash does not control the trump, so the danger case
   * is the trump under which this hand wins the most tricks. Dashing a no-ace
   * hand with trump length would bust, which is the documented bug the ceiling
   * check exists to prevent ("a Dash won 5").
   *
   * **Divergence from the source, making an inert field real.** The source's
   * `dashEagerness` never changes any decision: the only branch that reads it
   * cancels a dash when `rawFloat * (1 / eagerness) >= 3`, which for the one
   * profile below 1.0 (AGGRESSIVE, 0.6) requires `rawFloat >= 1.8` — but the
   * dash gate already capped the ceiling at 1.5, and the chosen-trump estimate
   * is always ≤ that ceiling. The cancel condition is unreachable, so in the
   * source all four personalities dash identically.
   *
   * The port instead scales the ceiling a personality will accept by its
   * eagerness. That is what the field's own documentation promises (">1 =
   * dashes more often") and what the source's blurbs already claim ("loves a
   * clean Dash"), and it separates all four profiles: BALANCED keeps the
   * historical 1.5, AGGRESSIVE tightens to 0.9, TRICKSTER widens to 2.1,
   * CONSERVATIVE to 2.4. The forced-trick and no-ace gates stay fixed — they
   * guard unduckable honors, not voluntary tricks. Widening is bounded for
   * HARD/EXPERT by the seam's own dash verification ([dashVerified]).
   */
  private fun dashSignal(hand: List<Card>, personality: BotPersonality): Boolean {
    val aces = hand.count { it.value == ACE }
    val kings = hand.count { it.value == KING }
    val forced = aces * 1.0 + kings * 0.4
    val ceiling = HandEvaluator.evaluateAllTrumps(hand, confidence = 1.0)
      .maxOf { it.expectedTricks }
    val ceilingLimit = DASH_CEILING_LIMIT * personality.dashEagerness
    return aces == 0 && forced < DASH_FORCED_LIMIT && ceiling <= ceilingLimit
  }

  /**
   * The seam's veto on a heuristic dash. The tiers that run bid-time
   * simulation must show the hand can really win zero tricks — expected tricks
   * is only a proxy, and a hand rated ~0.5 can still be forced to win a couple.
   * MEDIUM/EASY have no simulator and trust the gate alone, as in the source.
   *
   * The acceptance line is flat across tiers. The source grades EXPERT harder
   * (0.85 vs 0.80), but S6's dash gate is already tier-uniform and a steeper
   * EXPERT boundary would change verdicts its goldens pin; the heuristic
   * ramp was calibrated to land today's decisions on this exact line.
   */
  private fun dashVerified(hand: List<Card>, tier: BotTier, simulation: BotSimulation): Boolean {
    if (!usesBidSimulation(tier)) return true
    return simulation.estimateDashSuccess(hand) >= DASH_SIM_THRESHOLD
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
    personality: BotPersonality,
    simulation: BotSimulation,
  ): BiddingIntent.AuctionBid {
    val eval = botBid(hand, tier, playerId, chosenTrump = null, personality, simulation)

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
    personality: BotPersonality,
    simulation: BotSimulation,
  ): BiddingIntent.ConfirmCall {
    val eval = botBid(hand, tier, playerId, chosenTrump = null, personality, simulation)
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
    personality: BotPersonality,
    simulation: BotSimulation,
  ): BiddingIntent.FinalEstimate {
    val trump = if (state.fastRound) state.declaredTrump ?: fixedTrumpFor(state.round) else state.declaredTrump
    val eval = botBid(hand, tier, playerId, chosenTrump = trump, personality, simulation)

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
   * `botEngine.ts`'s `evaluateBotBid`, composed with the S9 layers: the
   * [BotSimulation] seam refines the tier's estimate, then the personality's
   * style shifts it (see [applyPersonalityToBid]).
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
    personality: BotPersonality,
    simulation: BotSimulation,
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

    // Tier skill produces the estimate. For HARD and EXPERT the SimPort seam
    // replaces the evaluator's optimistic ceiling with a realistic makeable
    // count — the load-bearing correction for HARD, which at full confidence
    // trusted the heuristic and over-bid. MEDIUM/EASY keep the raw heuristic
    // on purpose, exactly as the source keeps them on the heuristic.
    var estimate = evaluation.expectedTricks
    var reasoning = evaluation.reasoning
    if (usesBidSimulation(tier)) {
      val sim = simulation.estimateBid(hand, trump)
      estimate = sim.estimate
      reasoning = "$reasoning | ${tier} sim-bid=${HandEvaluator.round2(sim.estimate)} " +
        "(confidence ${(sim.confidence * 100).toInt()}%) ${sim.reasoning}"
    }

    // Tier noise, deterministic per seat so a bot-vs-bot round is reproducible
    // and four seats still feel like four players.
    val noisy = estimate + seatHash(playerId, tier.bidNoise)

    val tierBid = BotBid(
      rawFloat = estimate,
      noisy = noisy,
      intendedBid = kotlin.math.round(noisy).toInt(),
      potentialTrump = potentialTrump,
      reasoning = reasoning,
    )
    return applyPersonalityToBid(tierBid, personality)
  }

  /**
   * The tiers the source's `botPersonality.ts` runs bid-time simulation for:
   * HARD and EXPERT. Deliberately distinct from [BotTier.usesSimulation], which
   * is the *card-play* Monte-Carlo flag (EXPERT only, deferred post-launch) —
   * the bid seam is the load-bearing one for HARD and ships in S9 behind the
   * heuristic [BotSimulation.Default].
   */
  private fun usesBidSimulation(tier: BotTier): Boolean =
    tier == BotTier.HARD || tier == BotTier.EXPERT

  private const val ACE = 14
  private const val KING = 13
  /** The dash ceiling a BALANCED personality accepts; S6's historical gate. */
  private const val DASH_CEILING_LIMIT = 1.5
  /** Forced tricks (aces + a fraction of a king) too many to risk a Dash. */
  private const val DASH_FORCED_LIMIT = 0.75
  /** A dash the seam has verified must clear this probability of winning zero. */
  private const val DASH_SIM_THRESHOLD = 0.80
}

/**
 * A bid estimate as the brain produces it, ported from `botEngine.ts`'s
 * `BotBidResult`. Three stages, mirroring the source's `rawFloat` /
 * `intendedBid` / `bid` pipeline:
 *
 *  * [rawFloat] — the tier-skill estimate: [HandEvaluator]'s expected tricks,
 *    or the seam's realistic count when [BotSimulation] ran. Pre-jitter, and
 *    the value the super-call appetite is measured against.
 *  * [noisy] — [rawFloat] plus this seat's deterministic jitter.
 *  * [intendedBid] — the rounded bid the brain actually bids, after the
 *    personality's style has been applied by [applyPersonalityToBid].
 *
 * The port carries [noisy] explicitly because the source does not: its
 * personality layer recomputes the bid from the *un-jittered* float, which
 * silently discards the seat jitter and would make every same-personality
 * seat bid identically. Keeping it lets style land on top of jitter instead of
 * replacing it.
 */
internal data class BotBid(
  val rawFloat: Double,
  val noisy: Double,
  val intendedBid: Int,
  val potentialTrump: Suit,
  val reasoning: String,
)
