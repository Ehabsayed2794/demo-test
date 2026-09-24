package com.estemshan.engine.bot

/**
 * Four-tier bot difficulty, ported from `AI Bots/botEngine.ts` §0.
 *
 * The TypeScript models this as a `Record<BotTier, TierConfig>` lookup table;
 * the port folds it into enum constructor parameters. Same values, idiomatic
 * Kotlin — `TIER_CONFIG[tier].canDash` in the source becomes `tier.canDash`
 * here — and the table can never drift out of sync with the enum's cases.
 *
 * **Tier is skill only.** Style (aggressive / conservative / trickster) is a
 * separate axis that lands in S9 (`botPersonality.ts`); a bot can be
 * EXPERT+Aggressive or EASY+Conservative independently.
 *
 * Every field below is read by a later story: bidding (S6) reads
 * [distributionConfidence], [bidNoise] and [canDash]; card play (S7) reads
 * [countsCards] and [mistakeRate]; the spoiler (S8) reads [modelsOpponents].
 *
 * [usesSimulation] is the exception: the S9 *bid-time* seam deliberately does
 * **not** read it, because the source runs that simulation for HARD as well as
 * EXPERT. It is gated in `BidBrain` (`usesBidSimulation`) instead; this flag
 * remains the *card-play* Monte-Carlo switch alone.
 */
enum class BotTier(
  /** Fraction of decisions deliberately made sub-optimal, so weak bots feel
   * human/beatable rather than just dumb. 0 = always optimal. */
  val mistakeRate: Double,
  /** How much length/ruffing value the bot "believes in" when bidding. Low
   * tiers under-count distribution (like a beginner), high tiers count it
   * fully. 0..1 multiplier on the *distributional* part of a hand estimate —
   * never on the high-card part, which is real regardless of skill. */
  val distributionConfidence: Double,
  /** Jitter in tricks added to a bid estimate to model uncertainty; weak bots
   * are noisier. Applied deterministically via a seat hash (S6), never
   * `Random`, so a bot-vs-bot round is reproducible in CI (S10). */
  val bidNoise: Double,
  /** Whether this tier may recognise and declare a Dash Call. EASY cannot — a
   * beginner doesn't see the shape that makes zero tricks safe. */
  val canDash: Boolean,
  /** Whether this tier counts played cards during the round (S7). */
  val countsCards: Boolean,
  /** Whether this tier models opponents' likely holdings (S8). */
  val modelsOpponents: Boolean,
  /** Whether this tier runs Monte-Carlo simulation for *card play*. EXPERT
   * only, and still deferred post-launch — until it lands, every tier (even
   * EXPERT) plays the heuristic path, which the TS source itself describes as
   * "currently EXPERT == HARD logic with zero mistakes."
   *
   * Not to be confused with the S9 *bid-time* seam: that one also covers HARD
   * and is gated in `BidBrain` (`usesBidSimulation`), not here. See
   * `AI Bots/README.md` for why neither can simply be deferred. */
  val usesSimulation: Boolean,
) {
  EASY  (0.25, 0.30, 1.20, canDash = false, countsCards = false, modelsOpponents = false, usesSimulation = false),
  MEDIUM(0.10, 0.70, 0.60, canDash = true,  countsCards = false, modelsOpponents = false, usesSimulation = false),
  HARD  (0.03, 1.00, 0.20, canDash = true,  countsCards = true,  modelsOpponents = true,  usesSimulation = false),
  EXPERT(0.00, 1.00, 0.00, canDash = true,  countsCards = true,  modelsOpponents = true,  usesSimulation = true),
  ;

  companion object {
    /** Map a difficulty level onto a tier, defaulting to MEDIUM for anything
     * unrecognised. Ported from `normalizeTier()`; case-sensitive like the
     * source, because the only producer in the native game is an enum name. */
    fun normalize(level: String?): BotTier = when (level) {
      "EASY" -> EASY
      "MEDIUM" -> MEDIUM
      "HARD" -> HARD
      "EXPERT" -> EXPERT
      else -> MEDIUM
    }
  }
}
