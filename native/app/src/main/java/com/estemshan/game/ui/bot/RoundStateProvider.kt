package com.estemshan.game.ui.bot

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingPhase
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.TableState
import com.estemshan.engine.bot.BotPersonality
import com.estemshan.engine.bot.BotTier
import kotlinx.coroutines.flow.StateFlow

/**
 * **The seam story S11 writes the bot driver against** — everything
 * [BotDriver] needs to observe a match in progress and to move for a bot
 * seat. Implemented once per host: the offline quick-match flow today, the
 * online match view model later. The driver itself never changes between
 * them, which is the whole point of the indirection.
 *
 * **Two views, one source of truth.** [bidding] and [table] are the exact
 * `StateFlow`s the screens already render, not a parallel copy of them: the
 * driver reacts to the same emissions a `collectAsStateWithLifecycle` in the
 * UI reacts to, so it can never see a bot's turn that the UI does not also
 * show as waiting. A host implements them by forwarding its existing view
 * models' flows.
 *
 * **Dispatch is the human path.** [submitBidding] and [playCard] must route
 * an intent through the *same* `emit()` / `emitPlay()` call a tap takes —
 * in quick-match that is literally `BiddingViewModel.submit` and
 * `TableViewModel.play`. The driver therefore cannot bypass legality, turn
 * order, or the rejection surface; a bot that produces an illegal intent is
 * rejected exactly like a mis-tap, instead of corrupting state. This is what
 * the plan means by "dispatch intent through the same `emit()` path a human
 * tap takes".
 *
 * **Hands are a bidding-time concern only.** [BidBrain] needs the raw hand to
 * estimate; [PlayBrain] does not, because [TableState.cfg] already carries
 * every seat's hand and the brain reads it there. So [handFor] is consulted
 * only while the auction waits, and returns null when the host has no deal
 * for the seat yet.
 */
interface RoundStateProvider {

  /** The auction in progress, or `null` outside the Bidding Phase. */
  val bidding: StateFlow<BiddingState?>

  /** The trick table in progress, or `null` outside the Play Phase. */
  val table: StateFlow<TableState?>

  /** Which seats are bots, and at what skill/style. A seat absent from the
   *  roster is a human, and [BotDriver] never moves for it. */
  val roster: BotRoster

  /**
   * The hand [seat] was dealt for the current round, or `null` if the host
   * has no deal for it. Called only for bot seats while [bidding] waits.
   */
  fun handFor(seat: String): List<Card>?

  /**
   * Submit [intent] for the seat the auction is waiting on, through the
   * host's normal bidding entry point. Called from [BotDriver] only after
   * re-verifying the turn, so a stale intent lands on a moved-on auction as
   * a rejection, not a corruption.
   */
  fun submitBidding(intent: BiddingIntent)

  /** Play [card] for [seat], through the host's normal play entry point. */
  fun playCard(seat: String, card: Card)
}

/**
 * One bot seat's configuration: a skill [tier] and a style [personality],
 * the two being orthogonal (see [BotTier] and [BotPersonality]).
 */
data class BotSeat(
  val tier: BotTier,
  val personality: BotPersonality = BotPersonality.DEFAULT,
)

/**
 * The seating plan for a match: which seats a bot occupies, and at what
 * skill and style. Seats not present are human.
 *
 * Deliberately a snapshot, not a flow. Seating is fixed when a match starts
 * — there is no mid-match substitution in v1 — and a stable map keeps the
 * driver's "whose turn is this" check a single lookup. The Choose Level
 * screen (S12) builds one of these from its presets; quick-match ships a
 * default human-vs-bots roster.
 */
class BotRoster private constructor(
  private val seats: Map<String, BotSeat>,
) {
  /** Every seat driven by a bot. */
  val botSeats: Set<String> get() = seats.keys

  /** The bot configuration for [seat], or `null` when [seat] is human. */
  fun config(seat: String): BotSeat? = seats[seat]

  /** Whether [seat] is driven by a bot. */
  fun isBot(seat: String): Boolean = seat in seats

  override fun toString(): String = "BotRoster(${seats.keys.joinToString()})"

  override fun equals(other: Any?): Boolean =
    other is BotRoster && seats == other.seats

  override fun hashCode(): Int = seats.hashCode()

  companion object {
    /** A match with no bots — the driver is inert, everything waits on a
     *  human. The pre-S11 quick-match behaviour, preserved. */
    val HUMANS_ONLY: BotRoster = BotRoster(emptyMap())

    /** Build a roster from explicit seat → config pairs. */
    fun of(vararg seats: Pair<String, BotSeat>): BotRoster = BotRoster(mapOf(*seats))

    /**
     * The quick-match default: [human] is the one human at the table, every
     * *other* seat in [seats] is a bot. Tiers cycle through [tiers] in seat
     * order so a default match still spans all four skill levels — the same
     * spread the S10 smoke test uses, which keeps this roster observable in
     * CI rather than a "one dumb opponent" throwaway.
     *
     * Personalities cycle too, since a table of identical styles is a weaker
     * smoke signal. Throws if [human] is not among [seats].
     */
    fun versusBots(
      human: String,
      seats: List<String>,
      tiers: List<BotTier> = DEFAULT_TIERS,
      personalities: List<BotPersonality> = DEFAULT_PERSONALITIES,
    ): BotRoster {
      require(human in seats) { "versusBots: human seat '$human' is not in $seats" }
      val bots = seats.filter { it != human }
      return BotRoster(bots.mapIndexed { i, seat ->
        seat to BotSeat(
          tier = tiers[i % tiers.size],
          personality = personalities[i % personalities.size],
        )
      }.toMap())
    }

    private val DEFAULT_TIERS = listOf(BotTier.EASY, BotTier.MEDIUM, BotTier.HARD, BotTier.EXPERT)
    private val DEFAULT_PERSONALITIES = listOf(
      BotPersonality.AGGRESSIVE, BotPersonality.BALANCED,
      BotPersonality.CONSERVATIVE, BotPersonality.TRICKSTER,
    )
  }
}

/**
 * The action a [BotDriver] is about to take, fed to the delay strategy so
 * cadence can vary with the moment (a Dash declaration and a forced trick
 * should not feel identical) while staying deterministic — see
 * [BotDriver.HumanizedDelay].
 */
sealed interface BotAction {
  val seat: String

  /** A bidding decision in [subPhase] of [round]. */
  data class Bid(override val seat: String, val round: Int, val subPhase: BiddingPhase) : BotAction

  /** A card play in [trickNo] of [round]. */
  data class Play(override val seat: String, val round: Int, val trickNo: Int) : BotAction
}
