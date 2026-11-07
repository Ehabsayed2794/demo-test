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
import com.estemshan.engine.GameType
import com.estemshan.engine.RoundCfg
import com.estemshan.engine.RoundScoreInput
import com.estemshan.engine.ScoringMode
import com.estemshan.engine.ExtensionReason
import com.estemshan.engine.accumulateMatchScores
import com.estemshan.engine.calculateRoundScore
import com.estemshan.engine.computeRoundExtension
import com.estemshan.engine.nextSeat
import com.estemshan.game.ui.bot.BotDriver
import com.estemshan.game.ui.bot.BotRoster
import com.estemshan.game.ui.bidding.BiddingViewModel
import com.estemshan.game.ui.standings.FinalStandingsUiState
import com.estemshan.game.ui.standings.buildStandings
import com.estemshan.game.ui.table.TableViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

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

  /** The next round's multiplier, armed by the engine's general-pass value;
   *  reset once the round scores. */
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
   * Each finished round, as [onTableDone] scored it. Purely additive: a
   * caller driving several matches in one session — the S53 placement
   * coordinator — reads the per-round estimates-vs-tricks the standings alone
   * do not carry, so it can record RD10's accuracy, performance, and
   * consistency signals. Nobody else subscribes and emitting changes no
   * behaviour; the buffer holds a whole match (18 rounds) so a slow collector
   * loses nothing.
   */
  private val _roundResults = MutableSharedFlow<QuickRoundResult>(extraBufferCapacity = 32)
  val roundResults: SharedFlow<QuickRoundResult> = _roundResults.asSharedFlow()

  /**
   * Which seats a bot drives in this match. Defaults to [BotRoster.HUMANS_ONLY],
   * so quick-match plays exactly as it did before S11 until the Choose Level
   * screen (S12) passes a real roster into [startMatch] — the driver is
   * launched either way, it simply has nothing to do.
   */
  private val _roster = MutableStateFlow(BotRoster.HUMANS_ONLY)
  val roster: StateFlow<BotRoster> = _roster.asStateFlow()

  /**
   * The seed every hand in this match is dealt from — exposed so a match is
   * *reproducible*: given it, the whole deal sequence replays (S13). Fresh by
   * default, so two quick matches never see the same cards; [startMatch]
   * accepts one when a caller wants to replay a specific table.
   */
  private val _matchSeed = MutableStateFlow(Random.Default.nextLong())
  val matchSeed: StateFlow<Long> = _matchSeed.asStateFlow()

  /**
   * S69 (E7): this match's shape + calculation mode. FULL + NORMAL by
   * default (GM7); the S36 config screen will supply them when it exists
   * (S68) — until then every caller takes the defaults and FULL behaves
   * exactly as before (GM2).
   */
  private val _gameType = MutableStateFlow(GameType.FULL)
  val gameType: StateFlow<GameType> = _gameType.asStateFlow()

  private val _scoringMode = MutableStateFlow(ScoringMode.NORMAL)
  val scoringMode: StateFlow<ScoringMode> = _scoringMode.asStateFlow()

  /**
   * The round ceiling: FULL 18, MINI 10 — raised by Sa'ayda extension.
   * Observable because the ceiling moves DURING a match (A8.8/Q48): the
   * standings screen's chime, the placement coordinator's end-of-match
   * predicate and any test oracle must read the effective value, never the
   * format's static [GameType.baseRounds].
   */
  private val _maxRounds = MutableStateFlow(18)
  val maxRoundsFlow: StateFlow<Int> = _maxRounds.asStateFlow()
  val maxRounds: Int get() = _maxRounds.value

  /**
   * S1 fix round (2026-10-23): TRUE for the whole post-match window — set
   * both when a SCORED round reaches the planned ceiling ([onTableDone]) and
   * when a general pass CONSUMES the planned final round
   * ([applyGeneralPass]); [startMatch] clears it. It is the additive
   * end-of-match signal for placement-style observers: a final-round pass
   * emits no RoundResult, so an observer keyed to roundResults alone would
   * see it as a silent stall. Consumers own a once-per-match guard — for a
   * scored final, the RoundResult and this flag both arrive, exactly in
   * that order ([onTableDone] emits the result first).
   */
  private val _matchComplete = MutableStateFlow(false)
  val matchComplete: StateFlow<Boolean> = _matchComplete.asStateFlow()

  /**
   * The seed for the deal in hand: the match seed offset by the round.
   * S1 (A8.7 / Q51): a pass no longer re-deals the SAME round — it starts
   * the next round number — so a round's deal happens exactly once at
   * matchSeed + that round, and the old redeal stride has nothing left to
   * collide with a round seed for.
   */
  private fun dealSeed(): Long = _matchSeed.value + _round.value

  private var hands: Map<String, List<Card>> = Dealer.dealHands(dealSeed())
  private var scoreMultiplier: Int = 1

  /** The driver's lifetime job, if [attachBots] has armed one. */
  private var botDriver: Job? = null

  /**
   * Start a match, optionally with bots in [roster] and dealt from [matchSeed]
   * (fresh by default, so no two matches share a deal). The lobby's quick-match
   * entry stays human-only; the Choose Level screen (S12) supplies the roster.
   */
  fun startMatch(
    roster: BotRoster = BotRoster.HUMANS_ONLY,
    matchSeed: Long = Random.Default.nextLong(),
    gameType: GameType = GameType.FULL,
    scoringMode: ScoringMode = ScoringMode.NORMAL,
  ) {
    _matchSeed.value = matchSeed
    _round.value = 1
    _dealer.value = DEFAULT_SEATS[0]
    _biddingMultiplier.value = 1
    scoreMultiplier = 1
    _totals.value = DEFAULT_SEATS.associateWith { 0 }
    _standings.value = null
    _matchComplete.value = false
    _roster.value = roster
    _gameType.value = gameType
    _scoringMode.value = scoringMode
    _maxRounds.value = gameType.baseRounds
    hands = Dealer.dealHands(dealSeed())
    _biddingKey.value++
  }

  /**
   * S1 (A8.7 / Q51): a general pass is a production transition, not a
   * same-round redeal. The cancelled round's deal/table/bidding are
   * invalidated (the new bidding key re-initializes the auction), the dealer
   * rotates, a fresh hand is dealt for the NEW round number, and the next
   * round is armed with [doubled] — the multiplier the engine derived for it
   * (×2 → ×4 → ×6 → ×8 cap under Normal, always 1 under Classic).
   *
   * When the pass consumes the PLANNED final round the match completes
   * instead: standings bank the running totals unchanged (a cancelled round
   * scores nothing for everyone), no further deal happens, and the round
   * number stays at its ceiling so the standings screen's end-of-match state
   * (disabled continue button, match-win chime) applies as at the ceiling.
   */
  fun applyGeneralPass(doubled: Int) {
    if (_round.value >= maxRounds) {
      _standings.value = buildStandings(_totals.value)
      // The pass consumed the planned final round: the match is over — the
      // end-of-match signal for the coordinator (PlacementViewModel's series
      // advance; see the matchComplete KDoc).
      _matchComplete.value = true
      return
    }
    _round.value = _round.value + 1
    _dealer.value = nextSeat(seats, _dealer.value)
    // The next round scores at the engine's doubled multiplier: arm scoring
    // now; the bidding UI restarts separately from biddingMultiplier below.
    scoreMultiplier = doubled
    _biddingMultiplier.value = doubled
    hands = Dealer.dealHands(dealSeed())
    _biddingKey.value++
  }

  /**
   * Bidding completed: builds the table config from the outcome plus the
   * dealt hands. Caller leads trick 1 (or the dealer, when callerless).
   * Scoring still uses the armed scoreMultiplier (general passes/saayda), which
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
    // S69 (E7): the configured mode drives the formula AND its escalation
    // cap (CLASSIC caps ×2, NORMAL ×8 — Session.kt's mapping, repeated here
    // because this offline driver owns no Session). Previously classic was
    // hard-coded false with no cap at all, so Classic silently kept ×8.
    val classic = _scoringMode.value == ScoringMode.CLASSIC
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
        classic = classic,
        escalationCap = if (classic) 2 else 8,
      ),
    )
    scoreMultiplier = result.nextMultiplier
    _totals.value = accumulateMatchScores(_totals.value, result.deltas)
    val saaydaSeats = if (result.isSaayda) seats.toSet() else emptySet()
    _standings.value = buildStandings(_totals.value, result.deltas, saaydaSeats)
    // S2 (A8.8 / Q48): Sa'ayda and Super Call extension — extend maxRounds
    // by exactly +1 when this round qualifies. The engine already knows
    // callerSucceeded from the breakdown; a pass is never isSaayda=true.
    // The ceiling moves BEFORE the result emission: every observer of this
    // round — the placement coordinator, a test oracle, any synchronous
    // collector — must see the extended ceiling when it sees the round.
    val callerSucceeded = cfg.callerId?.let { result.breakdown[it]?.succeeded } ?: false
    // D1: the extension must know whether the call was GENUINELY a Super
    // Call. Offline every in-window round is a normal auction
    // (QuickMatchFlow starts startNormalRound unconditionally), so the
    // caller is the ordinary auction winner and the engine's own amount
    // rule — the locked number being 8+ — is the only honest discriminator.
    // The caller is always among the committed estimates; a defensive miss
    // passes null, the engine's "not supplied" legacy reading.
    val callerBidAmount = cfg.callerId?.let { cfg.estimates[it] }
    val extension = computeRoundExtension(
      cfg.round, maxRounds, cfg.callerId, cfg.trump, callerSucceeded,
      result.isSaayda, gameType = _gameType.value, callerBidAmount = callerBidAmount,
    )
    if (extension.extend) _maxRounds.value = maxRounds + 1
    _roundResults.tryEmit(QuickRoundResult(cfg.round, cfg.estimates, tricksWon))

    // A scored round at the ceiling completes the match. flag AFTER the
    // result emission so the coordinator's single merged observer always
    // sees the scored final's signal before this completion (its
    // once-per-match guard makes the completion a no-op for it). S2: the
    // extension above raises the ceiling first, so an extending round is
    // never the last.
    if (cfg.round >= maxRounds) _matchComplete.value = true
  }

  fun nextRound() {
    // S69 (E7): the match ends at its ceiling (FULL 18, MINI 10, or
    // extended past it by Sa'ayda) — past it there is no next round.
    if (_round.value >= maxRounds) return
    _round.value = _round.value + 1
    _dealer.value = nextSeat(seats, _dealer.value)
    _biddingMultiplier.value = 1
    _standings.value = null
    hands = Dealer.dealHands(dealSeed())
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

/**
 * One finished round as [QuickMatchViewModel.onTableDone] scored it — the
 * every-seat estimates the auction settled on and the tricks each seat
 * actually took. Published on [QuickMatchViewModel.roundResults] so a caller
 * running more than one match can keep per-round detail the final standings
 * throw away (S53's placement signals).
 */
data class QuickRoundResult(
  val round: Int,
  val estimates: Map<String, Int>,
  val tricksWon: Map<String, Int>,
)
