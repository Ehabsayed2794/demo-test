package com.estemshan.engine

/**
 * S49 — the one-match demotion shield (RD7; King ceiling per RD3 rides the
 * existing [RankedResolution.ceiling] and needs no second mechanism).
 *
 * Specified in docs/NATIVE_V1_PLAN_AND_ESTIMATE.md (E6b, S49) and
 * docs/UI_UX_ROADMAP.md §4b.3: demotion is real, with exactly ONE match of
 * demotion protection — a visible, limited state, never permanent. The
 * pipeline order is S47's RP engine → S57's [resolveRanked] → THIS file:
 * the shield wraps an already-final resolution, never a delta.
 *
 * What is engineering judgment inside that closed envelope (documented
 * here and pinned by DemotionProtectionTest, not specified by RD7):
 *
 * - The shield is ARMED by promotion ([RankedResolution.movement] > 0) and
 *   by nothing else — not by placement (S54 assigns a starting rank; that
 *   is not a promotion), not by a neutral match.
 * - The shield lasts EXACTLY one resolved match: the next
 *   [applyDemotionShield] call consumes it whatever the outcome — a
 *   countdown of one, matching the S51 shield display.
 * - A blocked demotion holds the CURRENT rung at its LOWER BOUND
 *   ([rankAt] over the pre-match RP): the rank is kept, the RP loss down
 *   to the floor is still recorded (the effective delta stays truthful),
 *   and anything already below the floor is impossible — a demotion
 *   (movement < 0) implies the resolved RP fell below the previous rung's
 *   floor, so clamping lands exactly on it. Floor-clamping rather than
 *   keeping the pre-match RP is the conservative choice: the shield saves
 *   the rank, not the points above it.
 * - King is uniform: previous King demoting below 3,000 with the shield
 *   armed holds King at 3,000. No King branch exists anywhere below.
 * - The shield never stacks: arming an armed profile stays armed (single).
 *
 * Out of scope by design: syncing the profile's tier/division identity to
 * the held rung stays with the settlement caller that persists the
 * profile — this file moves RP/rank math and the shield flag only.
 *
 * Pure data + pure functions, no I/O. Common-main: stdlib only.
 */

/**
 * The shielded outcome of one resolved match: the (possibly held)
 * resolution, whether this match consumed an armed shield, whether a
 * would-be demotion was negated, and the profile with the shield flag
 * moved forward. [resolution] is the value the caller persists.
 */
data class ShieldedResolution(
  val resolution: RankedResolution,
  /** True when an armed shield was consumed by this match. */
  val shieldConsumed: Boolean,
  /** True when a would-be demotion was negated by the shield. */
  val demotionBlocked: Boolean,
  val profile: RankedProfile,
)

/** True when the profile carries an armed one-match shield (RD7). */
fun hasDemotionShield(profile: RankedProfile): Boolean = profile.demotionShield

/**
 * Arm the shield on promotion. [resolution.movement] > 0 arms (or keeps
 * armed — never stacked); anything else returns the profile untouched.
 * Promotion here means a resolved climb, not the S54 starting assignment.
 */
fun armShieldOnPromotion(profile: RankedProfile, resolution: RankedResolution): RankedProfile =
  if (resolution.movement > 0) profile.copy(demotionShield = true) else profile

/**
 * Apply the shield to one already-final [resolution]. The pre-match rung
 * is read from [RankedResolution.previousRP] — the resolution's own
 * authority for where the match started — not from the profile's
 * tier/division identity, so the two can never disagree.
 *
 * - Unarmed: pass-through — the resolution, the consumption flag (false),
 *   and the profile are returned untouched.
 * - Armed: the shield is consumed (the returned profile is disarmed) and
 *   exactly one of:
 *   - movement < 0 → BLOCKED: RP clamps to the previous rung's lower
 *     bound, the rank holds, movement becomes 0 (settled), and the
 *     effective delta is floor − previous (still negative — the loss is
 *     recorded, the rank is saved);
 *   - otherwise → the countdown simply expires: the resolution passes
 *     through unchanged, shield gone.
 */
fun applyDemotionShield(profile: RankedProfile, resolution: RankedResolution): ShieldedResolution {
  if (!profile.demotionShield) {
    return ShieldedResolution(
      resolution = resolution,
      shieldConsumed = false,
      demotionBlocked = false,
      profile = profile,
    )
  }
  val disarmed = profile.copy(demotionShield = false)
  if (resolution.movement >= 0) {
    return ShieldedResolution(
      resolution = resolution,
      shieldConsumed = true,
      demotionBlocked = false,
      profile = disarmed,
    )
  }
  val floor = rankAt(resolution.previousRP).lowerBound
  val heldRp = maxOf(resolution.rp, floor)
  val held = resolution.copy(
    rp = heldRp,
    delta = heldRp - resolution.previousRP,
    next = rankAt(heldRp),
    movement = rankLadder.indexOf(rankAt(heldRp))
      .compareTo(rankLadder.indexOf(resolution.previous)),
  )
  return ShieldedResolution(
    resolution = held,
    shieldConsumed = true,
    demotionBlocked = true,
    profile = disarmed,
  )
}
