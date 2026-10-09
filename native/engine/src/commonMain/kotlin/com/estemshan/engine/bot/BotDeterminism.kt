package com.estemshan.engine.bot

import kotlin.math.absoluteValue

/**
 * Deterministic pseudo-randomness for the bot brains — the single replacement
 * for every `Math.random()` in `AI Bots/`.
 *
 * **Why not `Random`.** A bot decision must be reproducible from its inputs
 * alone. S10's bot-vs-bot 18-round smoke test asserts a complete match run,
 * and that is only possible if a given state always yields the same bid and
 * the same card. The TS source uses `Math.random()` in two places — the bid
 * jitter in `botEngine.ts` and the mistake injection in `botPlay.ts:114-116` —
 * and both are exactly what makes a golden test flicker, so the port hashes
 * instead (risk R6).
 *
 * **The hash** is a 31-multiplier polynomial over the key characters, ported
 * verbatim from `botEngine.ts`'s `seatJitter`. Both brains share it from here
 * rather than duplicating the port.
 */

/**
 * Signed jitter in [-magnitude, magnitude] for [playerId], ported 1:1 from
 * `botEngine.ts`'s `seatJitter`. Same hand + same seat always lands on the
 * same side of a rounding boundary; different seats vary.
 */
internal fun seatHash(playerId: String, magnitude: Double): Double {
  if (playerId.isEmpty() || magnitude == 0.0) return 0.0
  var hash = 0
  for (char in playerId) {
    hash = (hash * 31 + char.code)
  }
  // Fold to -1..1, matching the source's ((h % 1000) / 1000) * 2 - 1.
  val unit = ((hash % 1000) / 1000.0) * 2 - 1
  return unit * magnitude
}

/**
 * A deterministic [0, 1) draw for one play decision, keyed on everything that
 * identifies it: the seat, the round, the trick within the round, and the
 * decision index (how many cards are already in this trick, so the same seat
 * deciding twice in one trick draws twice). [salt] shifts the key so one
 * decision can draw a second, independent value — used to pick *which* legal
 * card a mistake plays, separately from *whether* it fires.
 *
 * Replaces `botPlay.ts`'s `Math.random() < cfg.mistakeRate`: a draw below the
 * tier's mistake rate injects a mistake, which keeps the TS's per-decision
 * firing probability without any randomness.
 */
internal fun decisionDraw(
  playerId: String,
  round: Int,
  trickNo: Int,
  decisionIndex: Int,
  salt: Int = 0,
): Double {
  val key = "$playerId:$round:$trickNo:$decisionIndex:$salt"
  var hash = 0
  for (char in key) {
    hash = (hash * 31 + char.code)
  }
  // Kotlin's % truncates toward zero (sign follows the dividend), so fold the
  // sign in before scaling to keep the draw in [0, 1).
  return (hash % 1000).absoluteValue / 1000.0
}
