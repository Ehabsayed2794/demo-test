package com.estemshan.game.ui.quickmatch

import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingState
import com.estemshan.engine.Card
import com.estemshan.engine.TableState
import com.estemshan.game.ui.bot.RoundStateProvider
import com.estemshan.game.ui.bidding.BiddingViewModel
import com.estemshan.game.ui.table.TableViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * The offline quick-match implementation of [RoundStateProvider] — the whole
 * reason the seam exists. It binds a [BotDriver] to the three objects the
 * quick-match flow already owns, and nothing else:
 *
 *  * [bidding] / [table] are the exact `StateFlow`s the Bidding and Table
 *    screens already `collectAsStateWithLifecycle`, so the driver reacts to
 *    the same emissions the UI renders — never a bot turn the screen does not
 *    also show as waiting.
 *  * [submitBidding] and [playCard] call the view models' own `submit` /
 *    `play`, which are the exact entry points the screens' callbacks use. A
 *    bot's intent therefore traverses the identical `emit` / `emitPlay` path
 *    a tap does, and is subject to the same rejections.
 *  * [roster] and [handFor] come from [QuickMatchViewModel], which owns the
 *    cross-screen facts (seating and the deal).
 *
 * **Why an adapter rather than having [QuickMatchViewModel] implement the
 * interface.** The plan names QuickMatchViewModel as the first host, but the
 * auction and table state are owned by [BiddingViewModel] and [TableViewModel],
 * which are scoped to the quick-match navigation graph — QuickMatchViewModel
 * cannot reach them, and folding the round state into it is the v1.1
 * cross-round consolidation the plan defers (risk R14). Composing the three
 * here keeps the driver written once without dragging that refactor into S11.
 *
 * **Lifetime.** One of these is built per [QuickMatchViewModel.attachBots]
 * call; it holds no state of its own and no coroutines, so a fresh one per
 * screen transition costs nothing. Every member delegates.
 */
class QuickMatchRoundProvider(
  private val biddingVm: BiddingViewModel,
  private val tableVm: TableViewModel,
  private val match: QuickMatchViewModel,
) : RoundStateProvider {

  override val bidding: StateFlow<BiddingState?> = biddingVm.state

  override val table: StateFlow<TableState?> = tableVm.state

  /** Read on every emission, so a roster chosen at match start is live. */
  override val roster
    get() = match.roster.value

  override fun handFor(seat: String): List<Card>? = match.handFor(seat)

  /** The Bidding screen's `onIntent` path, verbatim. */
  override fun submitBidding(intent: BiddingIntent) = biddingVm.submit(intent)

  /** The Table screen's `onPlay` path, verbatim. */
  override fun playCard(seat: String, card: Card) = tableVm.play(seat, card)
}
