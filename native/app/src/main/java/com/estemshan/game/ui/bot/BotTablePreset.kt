package com.estemshan.game.ui.bot

import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier

/**
 * A ready-made table the Choose Level screen (S12) offers as a quick pick, so
 * a player can sit down without tuning every seat. Ports `AI
 * Bots/botPersonality.ts`'s `SUGGESTED_TABLE`, which the source describes as
 * "a ready-made trio of opponents that feels varied and memorable. Assign
 * these at game init alongside each bot's skill tier."
 *
 * A preset is *seating, not difficulty*: it fixes which [BotSeat] takes each
 * opponent chair, and nothing about the engine. The roster it builds goes
 * straight into [QuickMatchViewModel.startMatch], and from there the S11
 * driver moves those seats through the exact same `emit()` path a tap takes —
 * bidding and playing still clear `canSubmit` / `canPlayCard`.
 *
 * Character text (the "The Shark" name and its blurb) is deliberately NOT
 * repeated here: the screen reads [BotSeat.personality]'s and [BotSeat.tier]'s
 * own [displayName] / [blurb], so a preset can never describe a bot the
 * enums no longer back. [name] and [blurb] describe the *table*, not its seats.
 */
data class BotTablePreset(
  /** The lineup's title, shown as the preset card's headline. */
  val name: String,
  /** One-line flavour text for the table as a whole. */
  val blurb: String,
  /** The opponents, in seat order — one [BotSeat] per chair that isn't the
   *  human's. Cycled if a table seats more opponents than the preset lists. */
  val bots: List<BotSeat>,
) {

  /**
   * Seat this preset against [human]: the opponents are [seats] minus [human],
   * in seat order, each taking the next entry of [bots]. Throws if [human] is
   * not among [seats] — the screen always seats the player at their own chair.
   */
  fun roster(human: String, seats: List<String>): BotRoster {
    require(human in seats) { "preset '$name': human seat '$human' is not in $seats" }
    val opponents = seats.filter { it != human }
    return BotRoster.of(
      *opponents.mapIndexed { i, seat -> seat to bots[i % bots.size] }.toTypedArray(),
    )
  }

  companion object {
    /**
     * The source's `SUGGESTED_TABLE`, ported in spirit: a cautious Fox, a
     * pressing Shark, and a wildcard Master. The lobby's default — a new
     * player lands here, and the spread (one gentle seat, one predator, one
     * wildcard) is the varied table the source tuned it to be.
     */
    val FoxSharkJoker = BotTablePreset(
      name = "The Rivals",
      blurb = "A cautious Fox, a pressing Shark, and a wildcard Joker.",
      bots = listOf(
        BotSeat(BotTier.EASY, BotPersonality.CONSERVATIVE),
        BotSeat(BotTier.HARD, BotPersonality.AGGRESSIVE),
        BotSeat(BotTier.EXPERT, BotPersonality.TRICKSTER),
      ),
    )

    /** Three steady Regulars on their best behaviour — the table to learn on. */
    val Gentle = BotTablePreset(
      name = "A Gentle Table",
      blurb = "Three patient opponents. Learn the rules, lose nothing.",
      bots = List(3) { BotSeat(BotTier.EASY, BotPersonality.BALANCED) },
    )

    /** Three solid Regulars. No mercy, no fireworks — a fair, even fight. */
    val Even = BotTablePreset(
      name = "An Even Table",
      blurb = "Three steady opponents who play it straight.",
      bots = List(3) { BotSeat(BotTier.MEDIUM, BotPersonality.BALANCED) },
    )

    /** Three Veterans out for blood — for a player who is very sure of themself. */
    val SharkTank = BotTablePreset(
      name = "The Shark Tank",
      blurb = "Three Veterans who bid high and come hunting.",
      bots = List(3) { BotSeat(BotTier.HARD, BotPersonality.AGGRESSIVE) },
    )

    /** The preset a fresh lobby lands on. */
    val DEFAULT: BotTablePreset = FoxSharkJoker

    /** Every shipped preset, in ascending meanness — the order the lobby shows. */
    val ALL: List<BotTablePreset> = listOf(Gentle, Even, FoxSharkJoker, SharkTank)
  }
}
