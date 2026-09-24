package com.estemshan.engine.bot

import com.estemshan.engine.Card
import com.estemshan.engine.PlayCard
import com.estemshan.engine.RANKS
import com.estemshan.engine.Suit
import com.estemshan.engine.TablePhase
import com.estemshan.engine.TableState
import com.estemshan.engine.canPlayCard
import com.estemshan.engine.cardValue
import com.estemshan.engine.legalCards

/**
 * The card-play brain (Module C), ported from `AI Bots/botPlay.ts`
 * (`selectBotCard`).
 *
 * **Three deliberate divergences from the source**, all in the direction of
 * the port's existing contract:
 *
 * 1. **No `cardRules` module.** The TS imports `beats`/`legalMoves` from
 * `./cardRules`, which does not exist in the dropped `AI Bots/` directory —
 * the import is dangling upstream. The port replaces both with the engine's
 * own `legalCards` and `cardValue`. This is not a convenience: it means the
 * brain's model of "who is winning this trick, and which of my cards beat
 * that card" is *the same function* the engine uses to resolve the trick, so
 * the brain can never disagree with the table about who won.
 * 2. **The hand comes from the state, not a parameter.** [BidBrain] takes the
 * hand as an argument because a [com.estemshan.engine.BiddingState] never
 * holds one; a [TableState] keeps every seat's hand in its config, and
 * [legalCards] reads it there. Taking it as a parameter would be a second
 * copy that could drift from the one legality is computed against.
 * 3. **Mistakes are deterministic.** `botPlay.ts:114-116` fires a random
 * legal card on `Math.random() < cfg.mistakeRate`. That breaks golden tests,
 * so the port draws from [decisionDraw] instead, keyed on
 * `(seatId, round, trickNo, decisionIndex)` — the firing *probability* is
 * preserved, the outcome is not random (risk R6).
 *
 * **The spoiler is live, the simulation is still gated.** The spoiler branch
 * (`chooseSpoilerCard`, Module D → [SpoilerStrategy]) engages when this seat's
 * contract is already busted — after the mistake gate and before the need/slack
 * computation, so a busted seat spoils instead of playing the heuristic. The
 * EXPERT Monte-Carlo *card-play* branch remains deferred: S9 shipped only the
 * bid-time half of the seam ([BidBrain] + [BotSimulation]), so until card
 * play's half lands every tier plays the heuristic path below — which the TS
 * source itself describes as "currently EXPERT == HARD logic with zero
 * mistakes."
 *
 * Like [BidBrain.decide], the contract is stronger than "it plays well": the
 * returned card clears `canPlayCard`, and a brain that would ship an illegal
 * card throws instead. `BotDriver` (S11) can then dispatch the intent through
 * the same `emitPlay()` path a human tap takes, with no second-guessing layer.
 */
object PlayBrain {

  /**
   * Decide the card [playerId] should play in [state] at difficulty [tier].
   *
   * The returned intent is guaranteed to clear `canPlayCard(state, playerId,
   * card)` for the given state, or this function throws — it never returns a
   * card the table would reject.
   *
   * @throws IllegalArgumentException if it is not [playerId]'s turn, or the
   *   seat has no hand to play in this state.
   * @throws IllegalStateException if the brain produces an illegal card,
   *   which indicates a brain bug rather than a recoverable condition.
   */
  fun decide(state: TableState, playerId: String, tier: BotTier): PlayCard {
    require(state.phase == TablePhase.PLAY) {
      "PlayBrain asked to decide outside PLAY (phase ${state.phase})"
    }
    require(state.turn == playerId) {
      "PlayBrain asked for $playerId but the turn is ${state.turn}"
    }
    // A missing hand is a caller bug, not a reconnect condition: legalCards()
    // answers "nothing legal" for the same seat and the engine recovers from
    // that, but this brain has no card to offer, so it fails loud here rather
    // than returning an intent emitPlay() is guaranteed to reject.
    val hand = state.cfg.hands[playerId]
    require(hand != null) {
      "PlayBrain asked for $playerId, who has no hand in round ${state.cfg.round}"
    }
    require(hand.isNotEmpty()) {
      "PlayBrain asked for $playerId with an empty hand — caller/turn-order bug"
    }

    val choice = choose(state, playerId, hand, tier)

    val legality = canPlayCard(state, playerId, choice.card)
    check(legality.legal) {
      "PlayBrain produced an illegal card for $playerId: ${choice.card.displayName} — " +
        "${legality.reason} — this is a brain bug, not a state error"
    }
    return PlayCard(playerId = playerId, card = choice.card)
  }

  // --------------------------------------------------------------------------
  //  The decision itself, ported from selectBotCard
  // --------------------------------------------------------------------------

  private fun choose(
    state: TableState,
    playerId: String,
    hand: List<Card>,
    tier: BotTier,
  ): CardChoice {
    val trump = state.cfg.trump
    val ledSuit = state.ledSuit
    val legal = legalCards(state, playerId)

    // legalCards answers "nothing is legal" only for a genuinely missing
    // hand, which decide() already refused; keep the TS's failsafe anyway and
    // never synthesise a card above it.
    if (legal.isEmpty()) {
      return CardChoice(hand.last(), "Failsafe: no legal card found, using last card.")
    }
    if (legal.size == 1) {
      return CardChoice(legal.first(), "Only one legal card.")
    }

    // ---- Tier mistakes: weak bots sometimes play a random legal card. The
    // TS drew Math.random() against cfg.mistakeRate; a deterministic draw
    // keeps the firing rate and removes the flicker. A second, salted draw
    // picks which legal card the mistake wastes.
    if (tier.mistakeRate > 0.0) {
      val round = state.cfg.round
      val trickNo = state.trickNo
      val decisionIndex = state.plays.size
      if (decisionDraw(playerId, round, trickNo, decisionIndex) < tier.mistakeRate) {
        val pick = decisionDraw(playerId, round, trickNo, decisionIndex, salt = 1)
        val index = (pick * legal.size).toInt().coerceIn(0, legal.size - 1)
        return CardChoice(legal[index], "Mistake (rate ${tier.mistakeRate}): random legal card.")
      }
    }

    // ---- S8: the spoiler strategy engages only on an already-busted
    // contract, targeting the points at stake. It answers null unless this
    // seat has nothing left to lose, so a makeable bid is never sacrificed.
    val spoiler = spoilerCard(state, playerId, tier)
    if (spoiler != null) return spoiler

    // ---- S9: EXPERT Monte-Carlo sits behind the SimPort seam. Until then
    // every tier plays the heuristic path below.

    val bid = state.cfg.estimates[playerId] ?: 0 // a Dash seat is absent or 0 → need <= 0
    val tricksWon = state.tricksWon[playerId] ?: 0
    val need = bid - tricksWon // tricks still required to make the contract
    val cardsLeft = hand.size
    val slack = cardsLeft - need // tricks we can afford to LOSE
    val playToWin = need > 0 && slack <= 0
    val pacing = need > 0 && slack > 0

    val knownIds = if (tier.countsCards) knownCardIds(state, playerId) else null

    // =========================================================================
    //  LEADING (the trick is empty)
    // =========================================================================
    if (state.plays.isEmpty()) {
      return if (playToWin) {
        // No slack left — every remaining trick must be won.
        // 1) Cash a guaranteed winner if counting confirms one. Side-suit
        //    bosses first, because a non-trump boss can still be ruffed.
        if (knownIds != null) {
          val bosses = legal
            .filter { trump == Suit.SANS || it.suit != trump }
            .filter { isBoss(it, knownIds) }
            .sortedByDescending { it.value }
          if (bosses.isNotEmpty()) {
            return CardChoice(bosses.first(), "Lead: must-win, cash guaranteed ${bosses.first().displayName}.")
          }
        }
        // 2) The top trump in hand wins outright.
        val trumps = legal.filter { trump != Suit.SANS && it.suit == trump }
        if (trumps.isNotEmpty()) {
          val hi = trumps.sortedByDescending { it.value }.first()
          return CardChoice(hi, "Lead: must-win, top trump ${hi.displayName}.")
        }
        // 3) Nothing guaranteed — lead the highest card and try to win.
        val hi = legal.sortedByDescending { it.value }.first()
        CardChoice(hi, "Lead: must-win, high card ${hi.displayName}.")
      } else {
        // PACING (still need tricks, but not yet) or the bid is already met:
        // lead LOW to offload a weak card while opponents can still beat it,
        // and hold the winners for late. Prefer a low non-trump.
        val nonTrumps = legal.filter { trump == Suit.SANS || it.suit != trump }
        val pool = if (nonTrumps.isNotEmpty()) nonTrumps else legal
        val lo = pool.sortedBy { it.value }.first()
        val why = if (pacing) "pacing, hold winners for late" else "avoiding tricks"
        CardChoice(lo, "Lead: $why — lead low ${lo.displayName}.")
      }
    }

    // =========================================================================
    //  FOLLOWING (responding to a partial trick)
    // =========================================================================
    // The engine's own trick resolution, read live: the current winning card,
    // then the legal cards split by whether they beat it.
    val winning = state.plays.maxByOrNull { cardValue(trump, ledSuit, it.card) }
      ?: error("PlayBrain followed a trick with no plays — turn-order bug")
    val winningValue = cardValue(trump, ledSuit, winning.card)
    val winners = legal.filter { cardValue(trump, ledSuit, it) > winningValue }
    val losers = legal.filter { cardValue(trump, ledSuit, it) <= winningValue }

    if (need > 0) {
      if (winners.isNotEmpty()) {
        // Boss-aware control retention (counting tiers, while slack remains):
        // if the GUARANTEED winners in hand already cover the bid, duck now
        // and bank those tricks at the end instead of spending control early.
        if (knownIds != null && slack > 0) {
          val bossesInHand = hand.count { isBoss(it, knownIds) }
          if (bossesInHand >= need) {
            val lo = legal.sortedBy { it.value }.first()
            return CardChoice(
              lo,
              "Follow: hold control ($bossesInHand sure winners >= need $need), duck low ${lo.displayName}.",
            )
          }
        }
        // Otherwise bank the trick with the CHEAPEST winner — the
        // anti-rush discipline lives in the LEADING branch above.
        val cheapest = winners.sortedBy { it.value }.first()
        return CardChoice(cheapest, "Follow: win cheaply with ${cheapest.displayName}.")
      }
      // Can't win — duck low and preserve the high cards.
      val lo = legal.sortedBy { it.value }.first()
      return CardChoice(lo, "Follow: can't win, duck low ${lo.displayName}.")
    }

    // Bid already met: avoid winning, shedding dangerous high cards while it
    // is safe to do so. Prefer dumping a high non-trump.
    if (losers.isNotEmpty()) {
      val sortedLosers = losers.sortedByDescending { it.value }
      val shed = sortedLosers.firstOrNull { trump == Suit.SANS || it.suit != trump }
        ?: sortedLosers.first()
      return CardChoice(shed, "Follow: avoid, safely shed high ${shed.displayName}.")
    }

    // Forced to win — every legal card beats the current winner. Minimise it.
    val cheapestForced = legal.sortedBy { it.value }.first()
    return CardChoice(cheapestForced, "Follow: forced to win, minimise with ${cheapestForced.displayName}.")
  }

  // --------------------------------------------------------------------------
  //  Card counting — "is this card a guaranteed winner?"
  // --------------------------------------------------------------------------

  /**
   * A card is a "boss" (top remaining in its suit) when every higher-ranked
   * card of that suit is already accounted for — in hand, in the current
   * trick, or already played this round. Ported from botPlay.ts's `isBoss`;
   * the TS accepted a trump parameter it never read (a non-trump boss can
   * still be ruffed — callers decide how far to trust that), so it is dropped.
   */
  private fun isBoss(card: Card, knownIds: Set<String>): Boolean =
    RANKS.none { rank -> rank.v > card.value && "${card.suit.name}-${rank.v}" !in knownIds }

  /**
   * The ids a counting tier may treat as accounted for, ported from
   * `buildKnownIds`: own hand + the in-progress trick + every resolved trick
   * this round (the engine's `seenCards`).
   *
   * Keyed by [Card.id] ("SUIT-RANK"), matching the TS's `${suit}-${rank}` —
   * and deliberately NOT by Card equality: played cards carry `played = true`
   * while hand cards do not, so data-class equality would fail to match the
   * same card across those two sources.
   */
  private fun knownCardIds(state: TableState, playerId: String): Set<String> {
    val ids = HashSet<String>()
    state.cfg.hands[playerId]?.forEach { ids += it.id }
    state.plays.forEach { ids += it.card.id }
    state.seenCards.forEach { ids += it.id }
    return ids
  }

  // --------------------------------------------------------------------------
  //  Gated seams
  // --------------------------------------------------------------------------

  /**
   * S8 — the spoiler strategy (`botStrategy.ts`'s `chooseSpoilerCard`): engages
   * only when this seat's own contract is already busted, and targets the
   * points at stake (Caller / Super / Dash), direction-aware and tier-gated.
   * Delegates to [SpoilerStrategy]; the seam stays so the branch order above is
   * untouched by the port. A null answer falls through to the heuristic path.
   */
  private fun spoilerCard(state: TableState, playerId: String, tier: BotTier): CardChoice? =
    SpoilerStrategy.choose(state, playerId, tier)?.let { CardChoice(it.card, it.reasoning) }

  private data class CardChoice(val card: Card, val reasoning: String)
}
