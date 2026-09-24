package com.estemshan.engine.bot

/**
 * Bot personalities (Module E — the "style" layer), ported from
 * `AI Bots/botPersonality.ts` §1.
 *
 * **Personality is style, [BotTier] is skill, and the two are orthogonal.**
 * The tier decides *how well* a bot evaluates a hand (see [HandEvaluator] and
 * [BidBrain]); the personality decides what it *does* with that evaluation —
 * an EXPERT+Aggressive and an EASY+Aggressive both nudge their estimate the
 * same direction, they simply arrive at the number with different accuracy.
 * Any seat can be any pairing, exactly like the source's
 * "a bot can be EXPERT+Aggressive or EASY+Conservative independently".
 *
 * The TypeScript models this as a `Record<Personality, PersonalityProfile>`
 * lookup table keyed by a string union. The port folds the table into enum
 * constructor parameters, exactly like [BotTier] did for `TIER_CONFIG`: same
 * values, idiomatic Kotlin, and the profile can never drift out of sync with
 * the enum's cases the way a parallel table can. The source's redundant `key`
 * field is the enum constant's own name and is therefore not ported.
 *
 * **These numbers are load-bearing.** They were tuned against real bot-vs-bot
 * sessions in the JS app — the source's own comments record the values they
 * were trimmed from (`AGGRESSIVE.bidBias` was +0.8, `TRICKSTER`'s +0.3) and
 * why: a nudge, not a trap, because "variance comes from Dashes, not
 * overbidding". The golden personality tests (S9) pin the *direction* each
 * profile moves a hand, and S10's bot-vs-bot smoke test pins a whole match, so
 * a retune here shows up as a failing test rather than a silent behaviour
 * change. They are not up for adjustment in this port.
 *
 * **Not ported here, by design.** The source's `evaluateBotBidWithPersonality`
 * is folded into [BidBrain.decide] so a personality-aware bid still has to clear
 * `canSubmit` like any other intent; `adaptTier` / `pushHumanResult` /
 * `winRate` (adaptive difficulty) are v1.1; `SUGGESTED_TABLE` belongs to the
 * Choose Level lobby, a later story.
 */
enum class BotPersonality(
  /** Added to the raw trick estimate *before* rounding. Positive = bids
   * higher. This shifts the pre-round float, not the rounded bid, so a bias
   * smaller than the distance to the next integer is a genuine no-op on a
   * given hand — which is why the golden tests assert a *direction* across a
   * hand chosen to sit on a rounding boundary, not an exact delta everywhere. */
  val bidBias: Double,
  /** How readily the bot declares a Dash. **>1 = dashes more often.** The
   * source applies this as a threshold multiplier on the heuristic dash signal
   * rather than a probability: a conservative profile (1.6) keeps a marginal
   * dash that an aggressive one (0.6) converts back into a real bid. Applied
   * in [BidBrain]'s dash decision. */
  val dashEagerness: Double,
  /** 0..1 — appetite for pushing a bid to a Super Call (8+) when the estimate
   * already sits at 7. The source only fires above 0.6, so CONSERVATIVE (0.1)
   * effectively never super-calls and AGGRESSIVE (0.7) almost always does. */
  val superCallAppetite: Double,
  /** Player-facing label for the lobby and the seat roster. */
  val displayName: String,
  /** One-line flavour text for the UI; no game effect. */
  val blurb: String,
) {
  BALANCED    ( 0.00, 1.0, 0.3, "Steady",    "Plays it straight. Bids what the hand is worth."),
  AGGRESSIVE  ( 0.40, 0.6, 0.7, "The Shark", "Bids a touch high, hunts Super Calls, pressures the table."),
  CONSERVATIVE(-0.50, 1.6, 0.1, "The Fox",   "Underbids for safety and loves a clean Dash."),
  TRICKSTER   ( 0.15, 1.4, 0.6, "The Joker",  "High variance — unpredictable Dashes and bold calls."),
  ;

  companion object {
    /** The source's implicit default when a seat has no personality assigned
     * (`bot.personality ?? 'BALANCED'`); used as the default value wherever a
     * personality is injected. */
    val DEFAULT: BotPersonality = BALANCED
  }
}

/**
 * Apply a personality's style to a tier-skill bid estimate — the port of
 * `botPersonality.ts`'s `applyPersonalityToBid`, adapted to [BidBrain]'s
 * [BotBid]. Pure: same estimate + same personality always yields the same bid,
 * with no state, no randomness, and no knowledge of the auction around it.
 *
 * Skill decides how well the hand was evaluated; this decides what the bot
 * *does* with that number. Two effects, both straight from the source:
 *
 *  * **[BotPersonality.bidBias]** shifts the estimate before the single
 *    rounding point, so an aggressive bot bids a touch high and a conservative
 *    one a touch low.
 *  * **[BotPersonality.superCallAppetite]** pushes a hand already sitting at 7
 *    up to the 8-trick Super Call, but only for personalities above the
 *    source's 0.6 appetite line and only when the raw estimate itself reached
 *    7 — a bias that merely rounded a 6.8 up to 7 does not qualify.
 *
 * **Two deliberate divergences from the source**, both documented in the
 * file they touch:
 *
 *  * The source applies its bias to the *un-jittered* float, which silently
 *    discards the tier's seat jitter and would make every same-personality
 *    seat bid identically. This port applies the bias to the jittered
 *    [BotBid.noisy], so style lands on top of jitter rather than replacing it.
 *  * The source's `callerBid` clamp (never bid above the Caller's cap) is not
 *    ported. In the port, `canSubmit` and the per-phase folds are the single
 *    authority on auction legality — the engine already rejects a bid above
 *    the cap, so re-clamping here would be a second, drift-prone copy of the
 *    rule.
 *
 * The [BotPersonality.DEFAULT] personality is the identity: it returns [base]
 * untouched, which is what keeps every pre-S9 caller of `BidBrain` behaving
 * byte-identically.
 */
internal fun applyPersonalityToBid(base: BotBid, personality: BotPersonality): BotBid {
  if (personality == BotPersonality.DEFAULT) return base

  val biased = base.noisy + personality.bidBias
  var intendedBid = kotlin.math.round(biased).toInt()

  var superCall = false
  if (intendedBid == SUPER_CALL_TRIGGER &&
    personality.superCallAppetite >= SUPER_CALL_APPETITE_GATE &&
    base.rawFloat >= SUPER_CALL_RAW_FLOOR
  ) {
    intendedBid = SUPER_CALL
    superCall = true
  }

  val clamped = intendedBid.coerceIn(0, 13)
  return base.copy(
    intendedBid = clamped,
    reasoning = base.reasoning +
      " | personality=${personality.displayName}(${personality.name}) -> bid $clamped" +
      (if (superCall) " (super call)" else ""),
  )
}

private const val SUPER_CALL_TRIGGER = 7
private const val SUPER_CALL = 8
private const val SUPER_CALL_APPETITE_GATE = 0.6
private const val SUPER_CALL_RAW_FLOOR = 7.0
