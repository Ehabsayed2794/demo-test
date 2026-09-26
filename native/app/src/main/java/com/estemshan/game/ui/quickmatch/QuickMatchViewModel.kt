package com.estemshan.game.ui.quickmatch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.estemshan.engine.Bid
import com.estemshan.engine.BidType
import com.estemshan.engine.BiddingIntent
import com.estemshan.engine.BiddingOutcome
import com.estemshan.engine.Card
import com.estemshan.engine.DEFAULT_SEATS
import com.estemshan.engine.Dealer
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.nextSeat
import com.estemshan.game.ui.bot.BotDriver
import com.estemshan.game.ui.bot.BotRoster
import com.estemshan.game.ui.bidding.BiddingViewModel
import com.estemshan.game.ui.standings.FinalStandingsUiState
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.game.ui.table.TableViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Offline quick-match driver (hot-seat): real engines end to end, no
 * network, no mocks. Owns the cross-screen facts the session layer will
 * own later (hands, totals, dealer rotation, score multiplier arming):
 * screens stay stateless and move over unchanged when GameSession lands.
 */
class QuickMatchViewModel : ViewModel() {

  val seats: List<String> = DEFAULT_SEATS

  private val _round = MutableStateFlow(1)
  val round: StateFlow<Int> = _round.asStateFlow()

  private val _dealer = MutableStateFlow(DEFAULT_SEATS[0])
  val dealer: StateFlow<String> = _dealer.asStateFlow()

  /** Bidding multiplier (redeals double it); reset once the round scores. */
  private val _biddingMultiplier = MutableStateFlow(1)
  val biddingMultiplier: StateFlow<Int> = _biddingMultiplier.asStateFlow()

  /** Bumps every (re)start so the bidding screen re-initializes exactly once. */
  private val _biddingKey = MutableStateFlow(0)
  val biddingKey: StateFlow<Int> = _biddingKey.asStateFlow()

  private val _totals = MutableStateFlow(DEFAULT_SEATS.associateWith { 0 })
  val totals: StateFlow<Map<String, Int>> = _totals.asStateFlow()

  private val _standings = MutableStateFlow<FinalStandingsUiState?>(null)
  val standings: StateFlow<FinalStandingsUiState?> = _standings.asStateFlow()

  /**
   * Which seats a bot drives in this match. Defaults to [BotRoster.HUMANS_ONLY],
   * so quick-match plays exactly as it did before S11 until the Choose Level
   * screen (S12) passes a real roster into [startMatch] — the driver is
   * launched either way, it simply has nothing to do.
   */
  private val _roster = MutableStateFlow(BotRoster.HUMANS_ONLY)
  val roster: StateFlow<BotRoster> = _roster.asStateFlow()

  private var hands: Map<String, List<Card>> = Dealer.dealHands()
  private var scoreMultiplier: Int = 1

  /** The driver's lifetime job, if [attachBots] has armed one. */
  private var botDriver: Job? = null

  /**
   * Start a match, optionally with bots in [roster]. The lobby's quick-match
   * entry stays human-only; the Choose Level screen (S12) supplies the roster.
   */
  fun startMatch(roster: BotRoster = BotRoster.HUMANS_ONLY) {
    _round.value = 1
    _dealer.value = DEFAULT_SEATS[0]
    _biddingMultiplier.value = 1
    scoreMultiplier = 1
    _totals.value = DEFAULT_SEATS.associateWith { 0 }
    _standings.value = null
    _roster.value = roster
    hands = Dealer.dealHands()
    _biddingKey.value++
  }

  fun applyGeneralPass(doubled: Int) {
    // A general pass re-deals — "nobody bid, cards are redealt at the doubled
    // multiplier" — so the restarted auction sees a fresh hand at every seat.
    // Without this a pass-happy deal replays identical hands forever, which
    // soft-locks a table the moment Choose Level can seat four bots.
    hands = Dealer.dealHands()
    // The restarted round scores doubled: arm scoring now; the bidding UI
    // restarts separately from biddingMultiplier below.
    scoreMultiplier = doubled
    _biddingMultiplier.value = doubled
    _biddingKey.value++
  }

  /**
   * Bidding completed: builds the table config from the outcome plus the
   * dealt hands. Caller leads trick 1 (or the dealer, when callerless).
   * Scoring still uses the armed scoreMultiplier (redeals/saayda), which
   * only onTableDone may re-arm.
   */
  fun onBiddingComplete(outcome: BiddingOutcome): RoundCfg {
    _biddingMultiplier.value = 1
    return RoundCfg(
      round = _round.value,
      trump = outcome.trump,
      callerId = outcome.callerId,
      withPlayers = outcome.withPlayers,
      estimates = outcome.estimates,
      dashCallers = outcome.dashCallers,
      leaderId = outcome.leaderId,
      riskId = outcome.riskPlayerId,
      multiplier = scoreMultiplier,
      hands = hands,
    )
  }

  /** Table done: scores the round, arms the next multiplier, banks totals. */
  fun onTableDone(cfg: RoundCfg, tricksWon: Map<String, Int>) {
    val result = calculateRoundScore(
      RoundScoreInput(
        round = cfg.round,
        order = seats,
        bids = bidsFromOutcome(cfg),
        tricksWon = tricksWon,
        callerId = cfg.callerId,
        withPlayers = cfg.withPlayers,
        multiplier = scoreMultiplier,
        riskPlayerId = cfg.riskId,
        classic = false,
      ),
    )
    scoreMultiplier = result.nextMultiplier
    _totals.value = accumulateMatchScores(_totals.value, result.deltas)
    val saaydaSeats = if (result.isSaayda) seats.toSet() else emptySet()
    _standings.value = buildStandings(_totals.value, result.deltas, saaydaSeats)
  }

  fun nextRound() {
    _round.value = _round.value + 1
    _dealer.value = nextSeat(seats, _dealer.value)
    _biddingMultiplier.value = 1
    _standings.value = null
    hands = Dealer.dealHands()
    _biddingKey.value++
  }

  /**
   * The hand [seat] was dealt this round — the one input [BidBrain] needs that
   * the bidding state does not already carry. `null` before a deal exists.
   */
  fun handFor(seat: String): List<Card>? = hands[seat]

  /**
   * Arm the [BotDriver] against this match's screens. Called from both the
   * Bidding and Table composables: each disposes as the other composes, so a
   * screen transition detaches and re-arms the driver against the *same* view
   * models — they are scoped to the quick-match graph, not to either screen —
   * and the driver re-collects the current state without missing a turn.
   * Idempotent if both screens happen to call it while composed.
   */
  fun attachBots(bidding: BiddingViewModel, table: TableViewModel) {
    detachBots()
    botDriver = BotDriver(QuickMatchRoundProvider(bidding, table, this)).launchIn(viewModelScope)
  }

  /** Disarm the driver — called when a quick-match screen leaves composition. */
  fun detachBots() {
    botDriver?.cancel()
    botDriver = null
  }
}

/**
 * Reconstructs scoring bids from a bidding outcome — the same derivation
 * table-engine.js performs: Dash-Call seats keep their type (estimates
 * alone can't carry it), a 0 estimate is a Normal Dash, else TRICKS.
 */
fun bidsFromOutcome(cfg: RoundCfg): Map<String, Bid> =
  cfg.estimates.keys.union(cfg.dashCallers).associateWith { seat ->
    when {
      cfg.dashCallers.contains(seat) -> Bid(BidType.DASHCALL, 0)
      (cfg.estimates[seat] ?: 0) == 0 -> Bid(BidType.DASH, 0)
      else -> Bid(BidType.TRICKS, cfg.estimates.getValue(seat))
    }
  }
