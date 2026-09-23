package com.estemshan.engine.bot

import com.estemshan.engine.Card
import com.estemshan.engine.Suit
import com.estemshan.engine.TableState
import com.estemshan.engine.cardValue
import com.estemshan.engine.currentWinnerId
import com.estemshan.engine.legalCards

/**
 * The spoiler strategy (Module D), ported from `AI Bots/botStrategy.ts`
 * (`chooseSpoilerCard`).
 *
 * Real players, once they KNOW they've lost their own bid, switch to spoiling
 * the highest-value opponent — the Caller, a Super Caller, or a Dash Caller —
 * because those players score the most. This module reproduces that, with the
 * fairness rules the source was written to enforce:
 *
 *   - Targets by POINTS AT STAKE, never by "is this the human". It applies to
 *     ALL opponents symmetrically — a bot will spoil another bot's Super Call.
 *     The engine makes this structural rather than a promise: the input is a
 *     [TableState], which knows seats by id and carries the caller/with/dash
 *     rosters in its config, with no seat privileged anywhere in this file.
 *   - Engages ONLY when this seat's own contract is already lost (busted). It
 *     never sacrifices a makeable bid to spoil someone — bad players do that.
 *   - Direction-aware: pushes the target AWAY from their exact bid (deny tricks
 *     they need; force unwanted tricks onto a Dash Caller).
 *   - Tier-gated: a tier that does not model opponents never spoils.
 *
 * Returns null whenever spoiling does NOT apply, so [PlayBrain]'s normal logic
 * simply takes over.
 *
 * **Deliberate divergences from the source**, all in the direction of the
 * port's existing contract (same three families as [BidBrain] and [PlayBrain]):
 *
 * 1. **The engine state is the only input.** The source takes a `PlayerModel[]`
 *    (hand, bid, isDashCall, isWazz, tricksWon, name per player) plus a
 *    `callerId` opt and the trick as a separate array. A [TableState] already
 *    holds every one of those facts — `cfg.hands` (shrinking as the round
 *    proceeds), `cfg.estimates`, `cfg.dashCallers`, `cfg.withPlayers`,
 *    `cfg.callerId`, `tricksWon`, `plays` — so there is no second copy of any
 *    of them to drift from the one the engine resolves against.
 * 2. **The engine's trick resolution is the authority.** The source computes
 *    the current winner by folding `beats()` over the trick; this reads
 *    [currentWinnerId] and splits its legal cards with the same
 *    [cardValue] the engine uses to resolve the trick. The spoiler can never
 *    disagree with the table about who is winning, or about which of its cards
 *    beat that card. (`beats`/`legalMoves` come from the TS `cardRules`
 *    module, which does not exist in the dropped `AI Bots/` directory — the
 *    import is dangling upstream, exactly as in `botPlay.ts`.)
 * 3. **The tier gate follows the source, not `modelsOpponents`.** The source
 *    gates on `tier === 'EASY'` (beginners don't think defensively) and then
 *   *narrows* MEDIUM to an obvious top target only — a Dash caller, a bid-0
 *   seat, a Super Call (bid >= 8), or the known Caller. HARD and EXPERT get the
 *   full value-ranked, direction-aware spoiling. That is the behaviour ported
 *   here. Note it deliberately does NOT read [BotTier.modelsOpponents] (false
 *   for MEDIUM too), which `BotTier`'s docs credit to this story — the source's
 *   MEDIUM behaviour is the authority, so the config field stays unused by this
 *   module rather than gating MEDIUM out of defensive play.
 *
 * **The Dash distinction the source is careful about, and how it maps.** For
 * *mode* purposes a Dash Call and a plain zero estimate behave the same — the
 * contract is broken the instant either takes a trick. But they score under
 * different formulas (`Role.DASH_CALL`: flat ±33/±25; `Role.REG_DASH`: the
 * ordinary 10+T table), so the *value* tiering must not call them equally
 * juicy. The engine keeps the two apart where the TS kept them apart: a Dash
 * Call seat sits in [com.estemshan.engine.RoundCfg.dashCallers] and is
 * *absent* from `estimates` (see `extractEstimates` — DASHCALL never reaches
 * the estimates map), while a Normal-Dash seat is *in* `estimates` with a
 * literal 0. `isDash` below is the mode test, `dashCall` is the value test.
 */
object SpoilerStrategy {

  /** A spoiler decision: the card, and why it was chosen. Mirrors the source's
   * `SpoilerChoice { chosenCard, reasoning }`. */
  data class SpoilerChoice(val card: Card, val reasoning: String)

  /** Which direction to push the target in. */
  private enum class Mode { DENY, FORCE }

  /** A vulnerable opponent: how to spoil them, how much is at stake, and
   * whether they count as an "obvious" target (the only kind MEDIUM spots). */
  private data class Target(val seat: String, val mode: Mode, val value: Int, val obvious: Boolean)

  /**
   * Choose a spoiler card for [playerId] in [state], or return null if
   * defensive play does not apply (EASY tier, this seat's contract is not yet
   * busted, or no opponent is a worthwhile target).
   *
   * The returned card is always drawn from [legalCards] for the same state, so
   * it clears follow-suit; [PlayBrain.decide] verifies it against
   * `canPlayCard` like any other choice, and throws if a spoiler ever ships an
   * illegal card.
   */
  fun choose(state: TableState, playerId: String, tier: BotTier): SpoilerChoice? {
    // Beginners don't think defensively. MEDIUM is narrowed further down, to an
    // obvious top target only; HARD/EXPERT spoil the top-ranked target.
    if (tier == BotTier.EASY) return null
    // Only spoil when there is nothing left to lose: never sacrifice a makeable
    // bid to spoil someone else's.
    if (!isBusted(state, playerId)) return null

    val legal = legalCards(state, playerId)
    if (legal.isEmpty()) return null

    // Build & rank the vulnerable targets by points at stake.
    val targets = state.seats
      .filter { it != playerId }
      .mapNotNull { classify(state, it) }
      .sortedByDescending { it.value }
    if (targets.isEmpty()) return null

    // MEDIUM only engages an OBVIOUS top target (Dash caller / bid 0 / Super
    // Call / the known Caller). It lacks the full value-ranked pattern
    // recognition, so it spoils only what is staring at it; with no obvious
    // target it falls through to the heuristic play path.
    val target = if (tier == BotTier.MEDIUM) {
      val obvious = targets.firstOrNull { it.obvious } ?: return null
      obvious
    } else {
      targets.first()
    }
    val name = target.seat

    // ── LEADING (the trick is empty) ────────────────────────────────────────
    if (state.plays.isEmpty()) {
      return if (target.mode == Mode.DENY) {
        // Take the trick ourselves so the target can't get a trick it needs.
        val card = legal.sortedByDescending { it.value }.first()
        SpoilerChoice(card, "Spoil(DENY $name): lead high ${card.displayName} to grab the trick.")
      } else {
        // FORCE: bait the target into winning by leading low (esp. vs a Dash
        // caller), preferring a non-trump so the trick stays cheap to lose.
        val nonTrump = legal.filter { state.cfg.trump == Suit.SANS || it.suit != state.cfg.trump }
        val pool = if (nonTrump.isNotEmpty()) nonTrump else legal
        val card = pool.sortedBy { it.value }.first()
        SpoilerChoice(card, "Spoil(FORCE $name): lead low ${card.displayName} to push a trick onto them.")
      }
    }

    // ── FOLLOWING (responding to a partial trick) ───────────────────────────
    // The engine's own read of who is on top, and its own split of the legal
    // cards by whether they beat that card.
    val trump = state.cfg.trump
    val ledSuit = state.ledSuit
      ?: error("SpoilerStrategy followed a trick with no led suit — turn-order bug")
    val winning = state.plays.maxByOrNull { cardValue(trump, ledSuit, it.card) }
      ?: error("SpoilerStrategy followed a trick with no plays — turn-order bug")
    val winningValue = cardValue(trump, ledSuit, winning.card)
    val winners = legal.filter { cardValue(trump, ledSuit, it) > winningValue }
    val losers = legal.filter { cardValue(trump, ledSuit, it) <= winningValue }
    val targetIsWinning = currentWinnerId(state) == target.seat

    if (target.mode == Mode.DENY) {
      // Deny the target: if they're winning, steal it; otherwise take it
      // cheaply so they can't have it.
      if (winners.isNotEmpty()) {
        val card = winners.sortedBy { it.value }.first()
        return SpoilerChoice(
          card,
          if (targetIsWinning) {
            "Spoil(DENY $name): steal the trick from them with ${card.displayName}."
          } else {
            "Spoil(DENY $name): take the trick with ${card.displayName} so they can't."
          },
        )
      }
      // Can't win it — duck low and save the cards.
      val card = legal.sortedBy { it.value }.first()
      return SpoilerChoice(card, "Spoil(DENY $name): can't take it, duck ${card.displayName}.")
    }

    // FORCE: we want the target to WIN this (unwanted) trick.
    if (targetIsWinning) {
      // Let them keep it — play a card that does NOT beat them, shedding a
      // high one while we're stuck not taking the trick.
      if (losers.isNotEmpty()) {
        val card = losers.sortedByDescending { it.value }.first()
        return SpoilerChoice(card, "Spoil(FORCE $name): let them keep the trick, shed ${card.displayName}.")
      }
      // Forced to overtake — minimise it.
      val card = legal.sortedBy { it.value }.first()
      return SpoilerChoice(card, "Spoil(FORCE $name): forced to overtake, minimal ${card.displayName}.")
    }
    // The target isn't on top yet — don't take the trick ourselves; duck low
    // and hope it falls to them (or a later seat pushes it their way).
    val card = legal.sortedBy { it.value }.first()
    return SpoilerChoice(card, "Spoil(FORCE $name): duck ${card.displayName} to avoid taking it ourselves.")
  }

  // --------------------------------------------------------------------------
  //  "Is THIS seat out of contention for its own bid (nothing left to lose)?"
  //  Ported 1:1 from isBusted().
  // --------------------------------------------------------------------------

  private fun isBusted(state: TableState, seat: String): Boolean {
    val remaining = state.cfg.hands[seat]?.size ?: 0
    val won = state.tricksWon[seat] ?: 0
    // A Dash Call seat is absent from estimates (see extractEstimates), so a
    // missing estimate reads as 0 — same as the source's dash caller whose bid
    // is also 0.
    val bid = state.cfg.estimates[seat] ?: 0
    val isDash = seat in state.cfg.dashCallers || bid == 0
    if (isDash) return won >= 1 // a dash that took a trick is lost
    return won > bid || won + remaining < bid
  }

  // --------------------------------------------------------------------------
  //  "Classify an opponent as a (still-vulnerable) target, or null."
  //  Ported 1:1 from classify(); the value constants are the source's ranking
  //  heuristic, preserved verbatim.
  // --------------------------------------------------------------------------

  private fun classify(state: TableState, seat: String): Target? {
    val remaining = state.cfg.hands[seat]?.size ?: 0
    val won = state.tricksWon[seat] ?: 0
    val bid = state.cfg.estimates[seat] ?: 0
    val dashCall = seat in state.cfg.dashCallers
    val isDashLike = dashCall || bid == 0

    // MODE: which direction spoils them.
    val mode = when {
      isDashLike -> if (won == 0) Mode.FORCE else null // took a trick → busted, no value
      else -> {
        val needs = bid - won
        when {
          needs > 0 && won + remaining >= bid -> Mode.DENY  // still chasing — deny
          needs == 0 -> Mode.FORCE                          // made it — force overshoot
          else -> null                                      // needs < 0 → already busted
        }
      }
    } ?: return null

    // VALUE: points at stake — whoever stands to gain most is the juiciest.
    // A Super Call is the caller at bid >= 8 (see `Role.SUPER_CALL`), so the
    // bid test comes before the caller test and a big call outranks an ordinary
    // one. The Dash Call (flat ±33/±25) outranks everything; a plain zero
    // estimate (REG_DASH, 10+T) does not.
    val value = when {
      dashCall -> 35
      bid >= 8 -> 20 + bid               // Super Call
      state.cfg.callerId == seat -> 15 + bid // known Caller
      seat in state.cfg.withPlayers -> 8 + bid // With
      bid == 0 -> 12                     // plain zero estimate
      else -> 10 + bid                   // standard
    }
    // The MEDIUM filter (source: `isDashCall || bid === 0 || bid >= 8 || is
    // caller`) — the target any intermediate player spots without modeling the
    // whole table.
    val obvious = dashCall || bid == 0 || bid >= 8 || state.cfg.callerId == seat
    return Target(seat, mode, value, obvious)
  }
}
