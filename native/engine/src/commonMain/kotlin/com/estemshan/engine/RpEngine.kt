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
 * **RD5/RD6 (this file, S48):** the mixed-tier *asymmetry* — a
 * higher-tier winner earns less, a higher-tier loser loses more, a
 * lower-tier winner earns more — plus Rank-down's hard ×2 loss cap. It
 * composes on top of [computeBaseDelta] through [withMixedTierAdjustment]
 * and touches nothing else, which is why the base and the adjustment are
 * separate functions with separate config rates.
 *
 * **Scope (2026-10-06 amendment):** RD5/RD6 apply to public matchmaking and
 * within-one-tier Rank-down only. Two or more tiers apart can no longer
 * reach a Ranked table — RD29/S70 enforces that at the matchmaking gate,
 * not here. This engine never rejects an input for tier distance; it just
 * computes the award.
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

  // ── RD5/RD6 (S48) — the mixed-tier asymmetry rates ─────────────────────
  //
  // Every rate is a PER-ORDINAL multiplier delta applied to |tierDifference|:
  // a player one ordinal above the table gets 1x the rate, two ordinals 2x.
  //
  // RD29 caps every Ranked table — public AND Rank-down — at one tier apart
  // (the 2026-10-06 amendment repealed Rank-down's former "any tiers
  // together"; both modes now share the band). Compared by tier, not by
  // ordinal, so the widest gap the band admits is division I of the upper
  // tier against division III of the lower = 5 ordinals on this ladder.
  // At the default 0.20 loss rate that is exactly 1 + 0.2 × 5 = 2.0, i.e.
  // the loss multiplier reaches [rankDownLossMultiplierCap] precisely at the
  // eligibility edge and the cap is a no-op today. The cap is the guardrail
  // that bounds an upward retune, not something the current rates exercise.
  //
  // All three rates are placeholders pending a balance pass, like RD4's above.

  /**
   * RD5: reward REDUCTION per ordinal the winner sat ABOVE the table. A
   * higher-tier win is worth less. Multiplier is `1 - rate * ordinals`,
   * clamped at 0 so a reduction can never invert the award (sign
   * discipline — see [withMixedTierAdjustment]).
   */
  val higherTierWinReductionPerOrdinal: Double = 0.10,
  /**
   * RD5: reward INCREASE per ordinal the winner sat BELOW the table. A
   * lower-tier win is worth more. Multiplier is `1 + rate * ordinals`.
   */
  val lowerTierWinIncreasePerOrdinal: Double = 0.10,
  /**
   * RD5: loss MULTIPLIER per ordinal the loser sat ABOVE the table. A
   * higher-tier loss costs more. Multiplier is `1 + rate * ordinals`, then
   * capped by [rankDownLossMultiplierCap] in Rank-down (RD6).
   */
  val higherTierLossMultiplierPerOrdinal: Double = 0.20,
  /**
   * RD6: the hard ceiling on the loss multiplier in Rank-down — "never more
   * than ×2". Applies ONLY to Rank-down; public matchmaking is uncapped.
   * Post-amendment both modes share RD29's one-tier band, so this is a bound
   * rather than a correction: at the default 0.20 rate the multiplier
   * reaches 2.0 exactly at the band's edge (5 ordinals) and the cap never
   * changes an output. It exists to keep a future balance pass from pushing
   * a Rank-down loss past double.
   */
  val rankDownLossMultiplierCap: Double = 2.0,
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
 * @param rankDown RD6's Private/Password Ranked flag. S43 persists this as a
 *   separate `rankDown` boolean alongside `mode` — Rank-down is
 *   `RANKED + rankDown`, **never a fourth [MatchMode] value**, and this
 *   input mirrors that shape rather than inventing one. RP is only ever
 *   computed for a Ranked match (settlement gates on `mode == RANKED`), so
 *   `mode` itself is implied and the only open question RD5/RD6 has is
 *   whether this table was Rank-down. Absent ⇒ false, like an old room doc.
 */
data class RpMatchInput(
  val outcome: MatchOutcome,
  val playerOrdinal: Int,
  val opponentStrength: Int,
  val gameType: GameType,
  val rankDown: Boolean = false,
)

/**
 * Convenience: the signed tier difference (RD4 input 2). Positive = the
 * player sits above the opponents' mean on the ladder (the higher tier).
 */
fun RpMatchInput.tierDifference(): Int = playerOrdinal - opponentStrength

/**
 * RD5's win/loss split. [MatchOutcome.KING] and [MatchOutcome.SECOND] are
 * the two placements the base awards RP for; [MatchOutcome.THIRD] and
 * [MatchOutcome.KOZ] cost it. Local to this file so S48 doesn't reach into
 * `RankedStats.kt` — the classification is the RP engine's, not the
 * statistics module's.
 */
private val MatchOutcome.isGain: Boolean
  get() = this == MatchOutcome.KING || this == MatchOutcome.SECOND

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
 * RD5/RD6 (S48) — the mixed-tier asymmetry, composed on the RD4 base through
 * the hook S47 deliberately left. **This function is the only asymmetry
 * authority**: it never calls [computeBaseDelta] or [finalRpDelta], so GM6's
 * ×0.5 still applies last, after this adjustment.
 *
 * The mixed-tier signal is [RpMatchInput.tierDifference] — nonzero IS the
 * mixed-tier condition, positive = the player was the higher tier. RD5 names
 * three directions, each a per-ordinal multiplier on the base magnitude:
 *
 * - **higher-tier wins → reward reduced** — `1 − reduction × ordinals`
 * - **higher-tier loses → loss multiplied** — `1 + multiplier × ordinals`
 * - **lower-tier wins → reward increased** — `1 + increase × ordinals`
 *
 * The fourth cell — a **lower-tier loser** — is not one of RD5's directions
 * and returns the base untouched. RD5 is asymmetric by design rather than a
 * full redistribution, and inventing a direction the closed rules never name
 * is how a balance becomes unreviewable. The symmetric base already moved
 * all four cells; this layer adds the three RD5 cares about.
 *
 * ## RD6: the ×2 cap is Rank-down-only
 *
 * The rule text scopes the cap to the private mode — "×2 in Private Ranked,
 * RD6's cap", "the ×2 private loss cap" — so it applies only when
 * [RpMatchInput.rankDown] is set; public matchmaking is uncapped. This is
 * the mode decision, not a reachability argument.
 *
 * Since the 2026-10-06 amendment **both** modes share RD29's one-tier band
 * (Rank-down's former "any tiers together" was repealed), so the cap is not
 * correcting for a wider private mix — it no longer exists. It is a bound:
 * at the default 0.20 rate the loss multiplier reaches the cap exactly at
 * the band's edge (5 ordinals) and never alters an output. It exists to
 * keep a future balance pass from pushing a Rank-down loss past double.
 *
 * Note that this engine never **enforces** the band. RD29/S70 does that at
 * the matchmaking gate, server-side; duplicating it here would couple the RP
 * arithmetic to the eligibility check. Given a seat that is somehow 6+
 * ordinals above its table, this function still computes an award — capped
 * in Rank-down, uncapped in public — rather than rejecting the input.
 *
 * ## Sign discipline (the bug class S47's doc warns about)
 *
 * Every multiplier is non-negative, and the win reduction is clamped at 0,
 * so a **reduced reward can never become a loss and a multiplied loss can
 * never become a gain**. The sign comes from the outcome via the base and is
 * restored after scaling the magnitude — the adjustment cannot invert it
 * even at extreme tier gaps. A base already clamped to 0 (a King far above
 * a weak table) scales to 0 here too.
 */
fun withMixedTierAdjustment(
  baseDelta: Int,
  input: RpMatchInput,
  config: RpEngineConfig = RpEngineConfig(),
): Int {
  // Same tier ⇒ not mixed-tier ⇒ the base stands. RD5's precondition.
  val ordinals = kotlin.math.abs(input.tierDifference())
  if (ordinals == 0 || baseDelta == 0) return baseDelta

  val higherTier = input.tierDifference() > 0
  val multiplier = when {
    input.outcome.isGain && higherTier ->
      (1.0 - config.higherTierWinReductionPerOrdinal * ordinals).coerceAtLeast(0.0)
    input.outcome.isGain ->
      1.0 + config.lowerTierWinIncreasePerOrdinal * ordinals
    higherTier -> {
      val raw = 1.0 + config.higherTierLossMultiplierPerOrdinal * ordinals
      if (input.rankDown) raw.coerceAtMost(config.rankDownLossMultiplierCap) else raw
    }
    // A lower-tier loser: not an RD5 direction.
    else -> return baseDelta
  }
  // baseDelta != 0 here (that case returned above), so the sign is exactly
  // ±1 — restoring it after scaling the magnitude is what keeps the
  // adjustment from ever inverting the award.
  val sign = if (baseDelta > 0) 1 else -1
  return sign * roundDelta(kotlin.math.abs(baseDelta) * multiplier)
}

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
  // config is threaded to the hook, not defaulted there, or S48's tunables
  // would be silently ignored. The composition order is untouched: RD4 base
  // → RD5/RD6 asymmetry → GM6's ×0.5 last.
  val fullEquivalent = withMixedTierAdjustment(computeBaseDelta(input, config), input, config)
  val multiplier = if (input.gameType == GameType.MINI) 0.5 else 1.0
  return roundDelta(fullEquivalent * multiplier)
}
