package com.estemshan.engine

/**
 * S47 (E6b, RD4) — the dynamic RP engine. Pure functions, no I/O.
 *
 * RP is **dynamic, never a fixed ±X** (RD4). The award for one match is a
 * function of five inputs the rules name explicitly:
 *
 * 1. **Opponent strength** — how strong the table was, as the mean ladder
 *    ordinal of the three opponents ([opponentStrength]).
 * 2. **Tier difference** — the player's ladder ordinal minus the opponents'
 *    mean ([tierDifference]); positive = the player was the higher tier.
 * 3. **Match outcome** — the seat's final placement ([MatchOutcome]).
 * 4. **Final placement / performance** — folded out of [MatchOutcome] itself
 *    (KING/SECOND/THIRD/KOZ), which is the rank-affecting signal.
 * 5. **Mixed-tier conditions** — [tierDifference] != 0 is the mixed-tier
 *    case; the RD5 asymmetry reads it directly rather than through a
 *    separate flag.
 *
 * ## The two rule layers, and where each lives
 *
 * **RD4 (this file):** the *dynamic base*. Outcome sets the sign and the
 * magnitude band; opponent strength and tier difference scale it. This is
 * the engine S47 owns, and it is the ONLY place the base magnitudes live.
 *
 * **RD5/RD6 (S48, not this file):** the mixed-tier *asymmetry* — a
 * higher-tier winner earns less, a higher-tier loser loses more, a
 * lower-tier winner earns more — plus Private Ranked's hard ×2 loss cap.
 * Those compose on top of [computeBaseDelta] via [withMixedTierAdjustment],
 * which is why this file exposes the base and the adjustment hook but
 * implements neither asymmetry direction.
 *
 * ## GM6: the Mini ×0.5, applied once (OPEN-3 CLOSED 2026-10-06)
 *
 * Mini's RP is **exactly half of Full's**, applied to the **final** delta
 * after every RD4/RD5/RD6 input, never per-round and never per-component:
 *
 * ```
 * finalDelta = round(baseDelta * (if (gameType == MINI) 0.5 else 1.0))
 * ```
 *
 * Rounding is **nearest integer, ties away from zero** — symmetric for gains
 * and losses (+15 → +8, −15 → −8, +9 → +5, −9 → −5; even deltas stay exact:
 * +20 → +10, −14 → −7). Neither `kotlin.math.round` (ties-to-even) nor
 * `java.lang.Math.round` (half-up toward +∞) implements this, so [roundDelta]
 * does it explicitly; see its doc and the mandatory asymmetry tests in
 * `RpEngineTest`. When [gameType] is FULL the multiplier is exactly
 * 1.0 and the round is a no-op, so **Full's RP is bit-identical to a system
 * with no multiplier at all** (GM6).
 *
 * ## Tunability (RD4: "independent of threshold constants")
 *
 * Every magnitude lives in [RpEngineConfig], a single data class. The 19
 * rank thresholds (RD26) stay in `RankLadder.kt` and are never duplicated
 * here — this engine produces a *delta*; the ladder decides where it lands
 * (`resolveRanked`). The two constants tables are deliberately separate so
 * a v1.1 balance pass retunes one without touching the other.
 *
 * The owner has closed the rule and its shape; the *numbers* in the default
 * config are placeholders pending a balance pass (RD4: "concept + inputs
 * only; constants not finalized"). They are structured so the owner can
 * change any of them without a redesign.
 */
data class RpEngineConfig(
  /** Base gain for finishing first, before strength/tier scaling (RD4). */
  val baseKingGain: Int = 30,
  /** Base gain for 2nd — a positive but smaller reward. */
  val baseSecondGain: Int = 15,
  /** Base loss for 3rd — small, may scale toward zero. */
  val baseThirdLoss: Int = -8,
  /** Base loss for Koz (last place) (RD4). */
  val baseKozLoss: Int = -20,
  /**
   * RD4 "opponent strength": RP added per ladder ordinal the opponents'
   * mean sits above the ladder's bottom. Facing a stronger table pays more.
   */
  val perOpponentStrengthPoint: Int = 2,
  /**
   * RD4 "tier difference": magnitude added per ordinal the player sits
   * ABOVE the opponents' mean. Positive = the player was the higher tier,
   * so a win is worth less and a loss costs more (the RD5 direction is
   * applied by [withMixedTierAdjustment], not here — this is the symmetric
   * base scaling only).
   */
  val perTierDifferencePoint: Int = 3,
  /** The ladder's bottom ordinal — Bronze III. Defaults to the real ladder. */
  val ladderFloorOrdinal: Int = 0,
  /** The ladder's top ordinal — King. Defaults to the real ladder. */
  val ladderCeilingOrdinal: Int = rankLadder.lastIndex,
)

/**
 * The inputs to one seat's RP award (RD4's five, encoded without a separate
 * mixed-tier flag — [tierDifference] != 0 IS the mixed-tier condition).
 *
 * @param outcome the seat's final placement (RD8: Match King ≠ Rank King —
 *   a [MatchOutcome.KING] here is the match win, not the account ceiling).
 * @param playerOrdinal the player's current ladder ordinal (from
 *   `rankLadder.indexOf(rankAt(profile.rp))` or the profile's own rung).
 * @param opponentStrength the mean ladder ordinal of the three opponents.
 *   Higher = stronger table.
 * @param gameType FULL or MINI (GM6 — the ×0.5 is applied here, once).
 */
data class RpMatchInput(
  val outcome: MatchOutcome,
  val playerOrdinal: Int,
  val opponentStrength: Int,
  val gameType: GameType,
)

/**
 * Convenience: the signed tier difference (RD4 input 2). Positive = the
 * player sits above the opponents' mean on the ladder (the higher tier).
 */
fun RpMatchInput.tierDifference(): Int = playerOrdinal - opponentStrength

/**
 * RD4 — the dynamic base delta, before the Mini multiplier and before any
 * RD5/RD6 asymmetry. Symmetric in tier difference by design: the mixed-tier
 * *asymmetry* is a separate rule with its own function.
 *
 * The sign comes purely from [RpMatchInput.outcome]; strength and tier
 * difference scale the magnitude and may scale it to zero, but never flip it
 * — a match King never loses RP, and Koz never gains it. That is not just
 * documentation: the arithmetic below can overshoot past zero (a King 15
 * ordinals above a weak table computes a negative raw sum), so [clampToSign]
 * stops it AT zero rather than letting the scaling invert the award. Without
 * it, winning a match at a much weaker table would cost RP.
 */
fun computeBaseDelta(input: RpMatchInput, config: RpEngineConfig = RpEngineConfig()): Int {
  val base = when (input.outcome) {
    MatchOutcome.KING -> config.baseKingGain
    MatchOutcome.SECOND -> config.baseSecondGain
    MatchOutcome.THIRD -> config.baseThirdLoss
    MatchOutcome.KOZ -> config.baseKozLoss
  }
  val strength = (input.opponentStrength - config.ladderFloorOrdinal) *
    config.perOpponentStrengthPoint
  // Symmetric base scaling: a higher-tier player's win is worth less and
  // their loss costs more even before RD5's explicit asymmetry. RD5's
  // directional multipliers layer on top in withMixedTierAdjustment().
  val tier = -input.tierDifference() * config.perTierDifferencePoint
  return clampToSign(base, base + strength + tier)
}

/**
 * The RD4 sign contract: scaling may shrink an award to zero, never invert
 * it. [scaled] is the raw base + strength + tier sum, which can overshoot;
 * this holds it on the outcome's side of zero.
 */
private fun clampToSign(base: Int, scaled: Int): Int = when {
  base > 0 -> scaled.coerceAtLeast(0)
  base < 0 -> scaled.coerceAtMost(0)
  else -> scaled
}

/**
 * RD5/RD6 — the mixed-tier asymmetry hook. S48 implements the three
 * directions (higher-tier wins → reduced; higher-tier loses → multiplied,
 * capped at ×2 in Private; lower-tier wins → increased) on top of the base.
 *
 * S47 ships the identity default so the engine is complete and testable in
 * isolation: the base is a well-defined award on its own, and S48 replaces
 * this function without touching [computeBaseDelta] or [finalRpDelta].
 */
fun withMixedTierAdjustment(baseDelta: Int, input: RpMatchInput): Int = baseDelta

/**
 * OPEN-3 (CLOSED 2026-10-06) — nearest integer, ties rounded AWAY from
 * zero, symmetric for gains and losses.
 *
 * **Neither JVM library implements this rule**, so it is written out
 * explicitly here:
 *  - `java.lang.Math.round(Double)` rounds half-up toward +∞, so
 *    `Math.round(-7.5)` returns **−7** — asymmetrizing every odd Mini loss
 *    (−15 would settle at −7 instead of −8, making a loss cheaper than the
 *    equivalent gain).
 *  - `kotlin.math.round` rounds ties toward the **even** neighbour, so
 *    `round(4.5)` returns **4.0** and `round(-4.5)` returns **−4.0** — which
 *    contradicts the closed rule's own stated cases (+9 → +5, −9 → −5, since
 *    9 × 0.5 = 4.5). The plan's insertion contract names `kotlin.math.round`,
 *    which is inconsistent with the rule it states; this function is the
 *    resolution.
 *
 * The tie branch is why the plan's cases hold: floor() already lands one step
 * away from zero on the negative side (floor(−7.5) == −8.0), so only positive
 * ties need the explicit +1. `RpEngineTest` pins both asymmetries as
 * mandatory regression guards.
 */
fun roundDelta(raw: Double): Int {
  val floor = kotlin.math.floor(raw)
  val fraction = raw - floor
  return when {
    fraction == 0.5 && raw > 0.0 -> (floor + 1.0).toInt()
    fraction > 0.5 -> (floor + 1.0).toInt()
    else -> floor.toInt()
  }
}

/**
 * GM6 — the single authority point for the final RP delta.
 *
 * Compute the full-equivalent result exactly as RD4/RD5/RD6 specify — every
 * input, every modifier — and only then multiply by 0.5 for MINI, once,
 * rounding per [roundDelta]. Applied at settlement (RD12/DR13: backend,
 * once per match), never on the client and never inside any per-round or
 * per-component computation. For FULL the multiplier is exactly 1.0 and the
 * round is a no-op, so Full is bit-identical to no multiplier at all.
 */
fun finalRpDelta(input: RpMatchInput, config: RpEngineConfig = RpEngineConfig()): Int {
  val fullEquivalent = withMixedTierAdjustment(computeBaseDelta(input, config), input)
  val multiplier = if (input.gameType == GameType.MINI) 0.5 else 1.0
  return roundDelta(fullEquivalent * multiplier)
}
